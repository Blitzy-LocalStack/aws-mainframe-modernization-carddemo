package com.carddemo.batch.config;

import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.service.BatchErrorPublisher;
import com.carddemo.batch.service.BatchFailureReporter;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.messaging.QueueClientBudget;
import com.carddemo.common.observability.MetricsConfig;
import com.carddemo.common.web.CorrelationIdFilter;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires publish-only access to the terminal error sink, and nothing else.
 *
 * <p><b>Purpose.</b> This class supplies the two beans a batch step needs in order to notify the
 * deployment's single terminal error sink that it failed: a synchronous queue client whose call
 * bounds fit inside this module's own shutdown window, and a validated binding that turns one
 * serialised {@link BatchErrorEvent} into one send request carrying exactly three message
 * attributes. It declares no queue, no dead-letter queue, no encryption key, no listener and no
 * business rule. Its whole authority is the migration plan's per-service configuration shape at its
 * section 0.4.1.2, which scopes a queue configuration class to this module and to the authorization
 * bounded context and to no other, and its section 0.4.1.8, whose queue table designates the
 * per-environment error queue a STANDARD queue and describes it as the terminal error sink.</p>
 *
 * <h2>There is no reference producer to transcribe, and that is why this class is narrow</h2>
 *
 * <p>Assumptions: <b>no program under {@code app/cbl} and no job under {@code app/jcl} names a
 * queue, opens one, or issues any message verb at all.</b> That was established by searching both
 * trees for every message verb and every queue name the reference baseline uses, and the search
 * returns nothing. The name this target sink is the analogue of is moved into an error-queue-name
 * field at exactly two places in the whole reference tree, and both are ONLINE programs of the
 * inquiry extension that migrate to other bounded contexts:
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl:243}, which reads
 * {@code MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME} and migrates to the reference context, and
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl:294}, which reads the identical statement and migrates to
 * the account context.</p>
 *
 * <p>Assumptions: that finding is recorded here rather than left implicit because it is the whole
 * justification for the scope below. A reader who knows the authorization bounded context's own
 * queue configuration will arrive expecting a listener container factory, a reply-destination
 * allowlist and a validity-horizon attribute, because that class declares all three. None of them
 * has a counterpart here, and the reason is not that they were forgotten: batch never consumed a
 * queue and never published to one, so there is no ordering contract, no reply contract and no
 * expiry contract to preserve. Anyone "completing" this class by adding a consumer would be adding
 * behaviour the reference does not have and that nothing in this module selects work from.</p>
 *
 * <h2>Who publishes, and the two things outside this file that let them</h2>
 *
 * <p>Refactoring Rationale: this section exists because the rest of this documentation once
 * described a publish path that did not exist. Every claim below about what "a published event
 * carries" was true of the binding this class validates and false of the running system: <b>nothing
 * sent anything.</b> There was no production sender, no environment supplied the gate property, and
 * the batch task role held no {@code sqs:SendMessage} grant -- so the class was skipped in every
 * deployment and the delivered security inventory named an egress the workload did not have. All
 * three halves are now present, and they are named here together because a reader checking whether
 * this path is real has to check all three and only one of them is in this file.</p>
 *
 * <ul>
 *   <li><b>The sender.</b> {@code com.carddemo.batch.service.BatchErrorPublisher} is the one
 *       production sender, and it has two callers: {@code BatchStepLedger}'s failure path, which
 *       reaches it through the {@link #batchFailureReporter} adapter declared below, and
 *       {@code com.carddemo.batch.BatchApplication}, which publishes the run's graded outcome once the
 *       job has returned. Both occasions address this one queue and the sender admits at most ONE
 *       delivered notification per failed run, so two occasions do not become two messages. The ledger
 *       holds the adapter as an {@link java.util.Optional}, which is empty exactly when this
 *       configuration is skipped, and the entry point resolves the sender through a bean provider that
 *       is empty on the same condition -- so the gate is honoured on both paths without either of them
 *       testing a property.</li>
 *   <li><b>The grant.</b> {@code sqs:SendMessage} on the error queue alone, from the
 *       {@code batch_task_runtime} policy document in each environment root. That document wraps the
 *       batch dataset document rather than extending it, because the data-migration task inherits the
 *       latter and publishes no event.</li>
 *   <li><b>The address.</b> The {@code CARDDEMO_MESSAGING_ERROR_QUEUE_URL} runtime parameter,
 *       published by both roots from the queue module's error-queue output and admitted by name in
 *       {@code infra/modules/ecs-service}. It is admitted and deliberately NOT required, because this
 *       configuration is conditional on it: a task handed no address records its failures in the log
 *       alone, which is a supported configuration and the one a local run uses. That module's
 *       reader-set precondition does constrain it in the other direction -- a workload carrying the
 *       address must be batch -- so the name is optional for this workload and forbidden to every
 *       other one, which is the only asymmetric clause in that block and is annotated there as such.
 *       </li>
 * </ul>
 *
 * <p>Assumptions: publishing a batch failure to a queue is <b>behaviour the reference does not
 * have</b> -- its batch programs report a failure through the job log and a condition code and nothing
 * else -- so it is registered as divergence {@code D-BATCH-FAILURE-EVENT-PUBLISHED} in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than presented as a
 * transcription. The paragraph above about adding a CONSUMER still stands unchanged and is a different
 * question: a consumer would take work from a queue and change what the module processes, whereas this
 * publishes a copy of a diagnosis the log already carries and changes what the module processes not at
 * all.</p>
 *
 * <h2>The field vocabulary is inherited, not invented</h2>
 *
 * <p>Assumptions: the error-event vocabulary comes from
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}, which declares
 * {@code 01 ERROR-LOG-RECORD.} at its line 19 with eleven fields in order --
 * {@code ERR-DATE PIC X(06)}, {@code ERR-TIME PIC X(06)}, {@code ERR-APPLICATION PIC X(08)},
 * {@code ERR-PROGRAM PIC X(08)}, {@code ERR-LOCATION PIC X(04)}, {@code ERR-LEVEL PIC X(01)},
 * {@code ERR-SUBSYSTEM PIC X(01)}, {@code ERR-CODE-1 PIC X(09)}, {@code ERR-CODE-2 PIC X(09)},
 * {@code ERR-MESSAGE PIC X(50)} and {@code ERR-EVENT-KEY PIC X(20)}, which sum to 122 bytes.
 * {@code ERR-LEVEL} carries the condition names {@code ERR-LOG} {@code 'L'}, {@code ERR-INFO}
 * {@code 'I'}, {@code ERR-WARNING} {@code 'W'} and {@code ERR-CRITICAL} {@code 'C'};
 * {@code ERR-SUBSYSTEM} carries {@code ERR-APP} {@code 'A'}, {@code ERR-CICS} {@code 'C'},
 * {@code ERR-IMS} {@code 'I'}, {@code ERR-DB2} {@code 'D'}, {@code ERR-MQ} {@code 'M'} and
 * {@code ERR-FILE} {@code 'F'}. Reusing that vocabulary rather than minting a parallel one is what
 * stops the deployment holding two names for one idea.</p>
 *
 * <p>Assumptions: two of those domains map onto figures this module already publishes.
 * {@code ERR-LEVEL} {@code 'W'} is the soft-warn tier the reference sets at
 * {@code app/cbl/CBTRN02C.cbl:229}, which reads {@code IF WS-REJECT-COUNT &gt; 0}, and
 * {@code app/cbl/CBTRN02C.cbl:230}, which reads {@code MOVE 4 TO RETURN-CODE}; {@code 'C'} is the
 * hard-failure tier. {@code ERR-SUBSYSTEM} {@code 'F'} is the file subsystem, which is the correct
 * classification for a dataset-staging or generation failure. Both are carried as fields of the
 * payload, and neither is a build gate: the graded condition-code rubric belongs to the reference
 * parity oracle rooted at {@code tests/} and to the batch container's process exit status, and
 * borrowing it for a Maven, Surefire, Failsafe or assertion result would hide a real failure behind
 * a tolerated one.</p>
 *
 * <p>Assumptions: the mapping onto {@link BatchErrorEvent}, which is the payload this class
 * addresses and whose shape is fixed in the sibling {@code dto} package, is settled and is not
 * re-decided here. {@code ERR-EVENT-KEY} is the correlation key, twenty characters in the reference
 * and carried in the target as whatever the shared correlation concern minted -- this class consumes
 * {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} as the attribute name and re-bounds nothing,
 * because a shared concern lives in the shared kernel and a second bound here could disagree with
 * it. {@code ERR-CODE-1} and {@code ERR-CODE-2} are the abend code and culprit,
 * {@code ERR-MESSAGE PIC X(50)} is the abend reason -- the same width the shared abend record
 * already cites this copybook for -- {@code ERR-LOCATION} is the failed step's name, and
 * {@code ERR-APPLICATION} and {@code ERR-PROGRAM} are configuration this class validates, because a
 * deployment names its own application and program and a source file cannot. {@code ERR-DATE} and
 * {@code ERR-TIME} are deliberately not carried: the queue records its own sent timestamp, and the
 * durable step ledger row records the step's start and finish, so a stamped component would be a
 * third recording of a fact two stores already own. That is also why no {@link java.time.Clock} is
 * injected here -- this class reads no clock at all, so there is nothing for a test to fix.</p>
 *
 * <h2>The message-attribute contract: three set, two deliberately absent</h2>
 *
 * <p>Assumptions: a published event carries exactly {@value #ATTRIBUTE_CORRELATION_ID},
 * {@value #ATTRIBUTE_MESSAGE_ID} and {@value #ATTRIBUTE_CONTENT_TYPE}, which is the mapping the
 * migration plan's section 0.4.1.8 fixes for the inherited message descriptor -- correlation
 * identifier, message identifier and format indicator -- so this path and the authorization path stay
 * legible beside one another. Two attributes that same mapping admits are absent by decision. A
 * reply-destination attribute is absent because this is fire and forget: there is no reply queue,
 * nothing replies, and an address a consumer could answer on would invite an answer nothing reads. The
 * validity horizon the shared kernel publishes as {@link MessageExpiry#HEADER_EXPIRES_AT} is absent
 * because it exists to resolve the reference reply message's own short expiry, for which the transport
 * offers no per-message lifetime; a terminal sink carries no expiry contract, so carrying the horizon
 * here would assert a staleness rule that does not exist and would license a consumer to discard the
 * diagnostics somebody is looking for.</p>
 *
 * <p>Assumptions: the two ordered-queue identifiers -- a message group and a deduplication identity --
 * are not merely unset but REFUSED, and the refusal is raised while the context is starting rather
 * than at the first publish. The ordered discipline in this migration, grouping by card number and
 * deduplicating by transaction identifier, belongs exclusively to the authorization request and reply
 * path.</p>
 *
 * <h2>The property contract, named in full because no document declares it</h2>
 *
 * <p>Assumptions: none of the four keys below is declared in
 * {@code services/batch-service/src/main/resources}, and that is deliberate rather than pending. A
 * value committed to a profile would be a value this repository holds, and the first key is a queue
 * address; the module's base profile states in its own header that no queue name, queue address,
 * bucket, key identifier or account identifier appears in it, and adding one here would falsify
 * that.</p>
 *
 * <p>Refactoring Rationale: the sentence that stood here said all four keys were "supplied per
 * environment through the parameter-store import that profile already declares", and it was wrong
 * twice over. No root published any of the four at all, so the gate never opened in any environment;
 * and the channel it named is not the channel that carries them. What both roots now publish is the
 * gate key alone, as the per-service runtime parameter {@code batch|CARDDEMO_MESSAGING_ERROR_QUEUE_URL}
 * whose value is the queue module's {@code error_queue_url} output, which the task definition injects
 * as a container environment variable and the framework's relaxed binding resolves onto
 * {@value #PROPERTY_ERROR_QUEUE_URL}. That is the same channel {@code carddemo.dataset.bucket}
 * arrives on, and it is the reason the key is absent from every profile rather than declared with an
 * empty default: a declared-but-blank key is PRESENT to the gate condition, so it would open this
 * configuration onto an address that then fails validation, which is precisely the startup failure
 * the gate exists to avoid in an environment that publishes no sink. The remaining three keys carry
 * defaults and are published by no root, which is why the sink's media type, source application and
 * source program are the values this file declares until a deployment states otherwise.</p>
 *
 * <ul>
 *   <li>{@value #PROPERTY_ERROR_QUEUE_URL} -- the address of the terminal error sink, and the GATE
 *       property: absent, this whole configuration is skipped. Required shape: the address of a
 *       STANDARD queue, as published by the queue module's error-queue output. It carries no
 *       default, and no address, resource identifier or region appears anywhere in this file.</li>
 *   <li>{@value #PROPERTY_ERROR_CONTENT_TYPE} -- the value of the {@value #ATTRIBUTE_CONTENT_TYPE}
 *       attribute. Required value: {@value #DEFAULT_CONTENT_TYPE}, which is the media type of the
 *       wire form settled below; a deployment may restate it and may not contradict it.</li>
 *   <li>{@value #PROPERTY_ERROR_SOURCE_APPLICATION} -- the value that populates
 *       {@code ERR-APPLICATION}. Required shape: one to {@value #ERR_APPLICATION_LENGTH} characters,
 *       the copybook's own {@code PIC X(08)} width.</li>
 *   <li>{@value #PROPERTY_ERROR_SOURCE_PROGRAM} -- the value that populates {@code ERR-PROGRAM}.
 *       Required shape: one to {@value #ERR_PROGRAM_LENGTH} characters, the copybook's own
 *       {@code PIC X(08)} width.</li>
 *   <li>{@link QueueClientBudget#PROPERTY_API_CALL_TIMEOUT} and
 *       {@link QueueClientBudget#PROPERTY_API_CALL_ATTEMPT_TIMEOUT} -- the whole-call and per-attempt
 *       bounds in milliseconds, read through the shared kernel's own published names so that one
 *       operational procedure covers every module holding a queue client. Both carry a default.</li>
 *   <li>{@code spring.lifecycle.timeout-per-shutdown-phase} -- read for COMPARISON only, never set
 *       here. The module's base profile owns it and shortens it below the platform's own window so
 *       the process finishes and flushes rather than being terminated part-way.</li>
 * </ul>
 *
 * <h2>The infrastructure boundary</h2>
 *
 * <p>Assumptions: this class provisions nothing. The error queue, its dead-letter queue with a
 * redrive policy that moves a message after five receives, and the customer-managed encryption key
 * every queue is provisioned with all belong to {@code infra/modules/sqs}. Encryption at rest is
 * that module's server-side encryption setting and encryption in transit is the software development
 * kit's own transport; nothing in this file contributes to either, and a reader looking for them
 * here would conclude wrongly that they were absent. Credentials are the task role's and are
 * resolved by the software development kit on first call, and the region is resolved from the
 * environment by the property the base profile declares -- so no static credentials provider, access
 * key, profile name or region appears in this file.</p>
 *
 * <h2>Data minimisation</h2>
 *
 * <p>Assumptions: nothing this class puts on the queue or into a log line can carry a primary
 * account number, a card verification value, a national identifier or a government-issued
 * identifier. The three attributes it sets are a correlation identity, a message identity and a
 * media type, and the body is supplied already serialised from {@link BatchErrorEvent}, whose own
 * canonical constructor refuses identifier-shaped and credential-shaped text. The common metric tags
 * come from {@link MetricsConfig}, whose canon is exactly {@link MetricsConfig#SERVICE_TAG},
 * {@link MetricsConfig#ENVIRONMENT_TAG} and {@link MetricsConfig#VERSION_TAG} with their values from
 * configuration; that class is contributed to every module by the shared kernel's own
 * auto-configuration, so it is CONSUMED here and deliberately not re-declared -- a second
 * declaration is how a fourth tag, or a tag keyed by an account or card or transaction identifier,
 * gets added without anyone deciding to. This class registers no meter of its own, which is the
 * simplest way to be unable to raise cardinality.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}. The four rationale
 * labels are written in the plural unparenthesised form with the colon retained and no emphasis
 * markup, and this file is restricted to ASCII, as the charter at
 * {@code services/batch-service/src/main/java/com/carddemo/batch/config/package-info.java} requires
 * of every class in this package.</p>
 *
 * <p>Baseline lineage: every citation above is provenance. Nothing under {@code app/**} is read at
 * run time and nothing under it is altered by this migration -- the reference implementation is the
 * behavioural oracle and stays byte-identical. Where migrated behaviour differs, the reference does
 * one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Columns 73 to 80 of a reference line
 * carry a sequence field that is not part of the statement.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = SqsConfig.PROPERTY_ERROR_QUEUE_URL)
public class SqsConfig {

    /**
     * The property carrying the terminal error sink's address, and the gate for this configuration.
     *
     * <p>Assumptions: the value is an ADDRESS rather than a queue name, and the choice is not
     * cosmetic. Both environment roots publish the queue module's {@code error_queue_url} output as
     * the batch workload's own runtime parameter under the environment name
     * {@code CARDDEMO_MESSAGING_ERROR_QUEUE_URL}, and the queue module's
     * {@code service_queue_permissions.batch_service} entry grants the batch task role
     * {@code sqs:SendMessage} on that one queue and nothing else -- so an address is what a deployment
     * actually supplies; and an
     * address needs no name-resolution call, so the task role needs the send action alone and a
     * container that runs one job and exits spends no round trip discovering where to send. A name
     * would need a resolution call whose failure mode -- an absent queue -- would surface on the
     * failure path, which is the path least likely to have been exercised.</p>
     */
    public static final String PROPERTY_ERROR_QUEUE_URL = "carddemo.messaging.error-queue-url";

    /** The property carrying the media type published on the {@value #ATTRIBUTE_CONTENT_TYPE} attribute. */
    public static final String PROPERTY_ERROR_CONTENT_TYPE = "carddemo.messaging.error-content-type";

    /** The property carrying the value that populates the copybook's {@code ERR-APPLICATION} field. */
    public static final String PROPERTY_ERROR_SOURCE_APPLICATION =
            "carddemo.messaging.error-source-application";

    /** The property carrying the value that populates the copybook's {@code ERR-PROGRAM} field. */
    public static final String PROPERTY_ERROR_SOURCE_PROGRAM =
            "carddemo.messaging.error-source-program";

    /**
     * The property the graceful-shutdown window is read from, for comparison only.
     *
     * <p>Assumptions: the module's base profile owns this value and this class never sets it. It is
     * read here because it is the period a publish has to finish inside, and comparing against a
     * value someone else owns is the only way the comparison can be right about the deployment.</p>
     */
    public static final String PROPERTY_SHUTDOWN_BUDGET =
            "spring.lifecycle.timeout-per-shutdown-phase";

    /**
     * The window assumed when {@value #PROPERTY_SHUTDOWN_BUDGET} is unset, matching the framework's.
     *
     * <p>Assumptions: this is the framework's own default for that key, so a context that does not
     * declare it is compared against the window it will actually be given. A shorter default would
     * refuse configurations the deployment permits, and a longer one would admit a send that shutdown
     * cannot accommodate -- and the module's base profile declares a value BELOW this one, so the
     * default is the looser of the two and is only reached where the profile is absent.</p>
     */
    public static final String DEFAULT_SHUTDOWN_BUDGET = "30s";

    /**
     * The attribute carrying the correlation identity, named by the shared kernel rather than here.
     *
     * <p>Assumptions: this is bound to the shared correlation concern's own key rather than to a
     * second literal, so the attribute a consumer reads and the key this module's log lines are
     * written under cannot drift apart. The batch entry point puts the run identifier under that
     * same key, so an event's attribute and the run's log lines carry one value.</p>
     */
    public static final String ATTRIBUTE_CORRELATION_ID = CorrelationIdFilter.CORRELATION_ID_MDC_KEY;

    /**
     * The attribute carrying the publisher's own message identity.
     *
     * <p>Assumptions: this mirrors the mapping the migration plan's section 0.4.1.8 fixes for the
     * inherited message descriptor, under which the message identifier becomes an attribute of this
     * name. It is the publisher's identity for the event and is not the transport's own identifier,
     * which the queue assigns and which a producer cannot choose.</p>
     */
    public static final String ATTRIBUTE_MESSAGE_ID = "messageId";

    /** The attribute declaring the wire form of the body, so a consumer parses rather than guesses. */
    public static final String ATTRIBUTE_CONTENT_TYPE = "contentType";

    /**
     * The media type of the wire form this class publishes.
     *
     * <p>Alternatives Considered: a delimited text body, declared as comma-separated values. That is
     * the form the two reference error-queue producers use, and for every payload the migration
     * INHERITED the migration plan's section 0.4.1.8 treats field order and delimiter as the
     * contract, so the delimited form would be the default choice. It is rejected here for one
     * concrete reason: this producer has no reference counterpart at all -- neither
     * {@code app/app-vsam-mq/cbl/CODATE01.cbl:243} nor
     * {@code app/app-vsam-mq/cbl/COACCT01.cbl:294} is a batch program -- so there is no existing
     * consumer whose field order must be preserved, and that same plan section permits a structured
     * envelope additively for new consumers. Decisively, the payload type is already delivered and is
     * already shaped for this form: {@link BatchErrorEvent} carries no serialisation annotation
     * because the shared kernel's mapper module binds a record through its canonical constructor, so
     * a delimited body would need a positional codec written specially for one message and a second
     * place the wire form is decided.</p>
     *
     * <p>Assumptions: the field vocabulary is unaffected by that choice. The names on the wire are
     * the copybook's own, mapped as described on this class, and choosing an envelope changes how
     * they are framed rather than which of them exist. No monetary amount appears on this payload,
     * because a failure event reports that a step did not complete and not a sum -- so the money
     * contract has no bearing here, and had one appeared it would have travelled as a string rather
     * than as a number, for the reason the shared kernel's money module exists.</p>
     */
    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    /**
     * The suffix that marks an ordered queue, refused by {@link #batchErrorSinkBinding}.
     *
     * <p>Assumptions: the suffix is the only way to tell an ordered destination from a standard one
     * without calling the service, and the distinction has to be made at startup because the send
     * this class builds omits the two identifiers an ordered queue requires.</p>
     */
    public static final String FIFO_QUEUE_SUFFIX = ".fifo";

    /** The copybook width of {@code ERR-APPLICATION}, which is {@code PIC X(08)}. */
    public static final int ERR_APPLICATION_LENGTH = 8;

    /** The copybook width of {@code ERR-PROGRAM}, which is {@code PIC X(08)}. */
    public static final int ERR_PROGRAM_LENGTH = 8;

    /** The software development kit's name for a textual message attribute. */
    private static final String ATTRIBUTE_TYPE_STRING = "String";

    /** Records the gate decision once, at the moment the binding is built. */
    private static final Logger LOG = LoggerFactory.getLogger(SqsConfig.class);

    /**
     * Supplies the synchronous queue client a failed step publishes its notification with.
     *
     * <p>Trade-offs: this is the SYNCHRONOUS client, while the starter auto-configures an asynchronous
     * one. The choice follows from this module's lifecycle rather than from taste: the container is
     * started by one orchestrator state, runs one job and exits, so anything still buffered when the
     * virtual machine stops is lost. A synchronous send runs on the caller's own thread and has either
     * completed or reached its own deadline before that caller returns, which is what makes the
     * notification either delivered or reported. An asynchronous client with request batching would
     * hold the event in a buffer whose flush is a race against process exit, and a notification that
     * is silently dropped on the failure path is worse than none at all, because the failure path is
     * the one an operator is relying on.</p>
     *
     * <p>Assumptions: the client is closed by the container. The bean is left with the framework's
     * inferred destruction, which calls the client's own close method because the type is auto
     * closeable, so the transport is released as part of context shutdown rather than at process
     * termination. Disabling that inference was the alternative and is rejected: it would leave the
     * transport open until the virtual machine stopped, which on a task that exits immediately after
     * its job is the difference between a released connection and an abandoned one.</p>
     *
     * <p>Assumptions: region, credentials and any endpoint override are applied by the starter's own
     * configurer rather than being set here. Setting them here would create a second place this client
     * and the starter's own could disagree about which account and region they address -- a
     * disagreement that fails only at run time, and only on the failure path.</p>
     *
     * <p>Alternatives Considered: constructing a {@link QueueClientBudget} from the two bounds below,
     * which is what every sibling context's queue configuration does. Rejected here, and the reason is
     * that its third member is the period a received message stays invisible to other consumers --
     * that is, a CONSUMER-side value. This module has no consumer: it publishes to a terminal sink,
     * and its base profile disables listener startup outright so that no non-daemon poller can keep
     * the virtual machine alive after the last step. Constructing that record would therefore require
     * inventing a visibility period for a queue this module never receives from, and would assert a
     * relationship that does not exist in order to reuse a validator. What IS reused is the pair of
     * property names that record publishes, so the two bounds are configured under the same names in
     * every module holding a queue client and one operational procedure covers all of them.</p>
     *
     * <p>Refactoring Rationale: the bound this class validates against is the graceful-shutdown
     * window, which is the relationship that actually binds a run-and-exit publisher. The software
     * development kit's default for both bounds is no bound at all, so an unbounded send retries
     * indefinitely; on a task whose orchestrator state is waiting for the container to reach a
     * terminal state, that is not a slow notification but a stalled step. Requiring the whole-call
     * bound to be strictly shorter than the shutdown window means a send still running when the
     * platform asks the process to stop reaches its own deadline first, so the process finishes and
     * flushes rather than being terminated part-way.</p>
     *
     * <p>Trade-offs: the bounds are applied through the CONSUMER form of
     * {@code overrideConfiguration}, which mutates the configuration the starter's configurer already
     * built. The value form would replace it, discarding the retry policy, the user agent and any
     * execution interceptor the starter installed -- a loss visible only as absent telemetry and
     * absent retries, neither of which fails a test.</p>
     *
     * @param configurer the starter's client-builder configurer, which applies the resolved region,
     *     credentials provider and any endpoint override; must not be {@code null}
     * @param apiCallTimeoutMillis the whole-call bound in milliseconds, covering every retry attempt
     *     of one send together, from {@link QueueClientBudget#PROPERTY_API_CALL_TIMEOUT}; must be
     *     positive and must be strictly shorter than the shutdown window
     * @param apiCallAttemptTimeoutMillis the per-attempt bound in milliseconds, covering one network
     *     attempt of that send, from {@link QueueClientBudget#PROPERTY_API_CALL_ATTEMPT_TIMEOUT}; must
     *     be positive and must not exceed the whole-call bound
     * @param shutdownBudgetSpec the graceful-shutdown window as configured, read from
     *     {@value #PROPERTY_SHUTDOWN_BUDGET} for comparison only and never set here; must not be
     *     {@code null}, must be readable as a duration in the abbreviated or interval notation, and
     *     must be positive
     * @return the synchronous queue client, bounded and closed by the container, never {@code null}
     * @throws NullPointerException if {@code configurer} or {@code shutdownBudgetSpec} is
     *     {@code null}
     * @throws IllegalStateException if either bound is zero or negative, if the shutdown window is
     *     unreadable or non-positive, if the per-attempt bound exceeds the whole-call bound, or if the
     *     whole-call bound is not shorter than the shutdown window; the failure names the relationship
     *     that does not hold, so it arrives at startup and is repaired against the right property
     *     rather than presenting later as a stalled step
     */
    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient(AwsClientBuilderConfigurer configurer,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_TIMEOUT + ":10000}")
            long apiCallTimeoutMillis,
            @Value("${" + QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT + ":5000}")
            long apiCallAttemptTimeoutMillis,
            @Value("${" + PROPERTY_SHUTDOWN_BUDGET + ":" + DEFAULT_SHUTDOWN_BUDGET + "}")
            String shutdownBudgetSpec) {

        Objects.requireNonNull(configurer, "configurer must not be null");
        Duration shutdownBudget = readShutdownBudget(shutdownBudgetSpec);
        Duration apiCallTimeout = Duration.ofMillis(apiCallTimeoutMillis);
        Duration apiCallAttemptTimeout = Duration.ofMillis(apiCallAttemptTimeoutMillis);
        requirePositive(apiCallTimeout, "apiCallTimeout", QueueClientBudget.PROPERTY_API_CALL_TIMEOUT);
        requirePositive(apiCallAttemptTimeout, "apiCallAttemptTimeout",
                QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT);
        if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalStateException("apiCallAttemptTimeout " + apiCallAttemptTimeout
                    + " exceeds apiCallTimeout " + apiCallTimeout
                    + ": a per-attempt bound above the whole-call bound can never be reached, because"
                    + " the send is abandoned first; configured by "
                    + QueueClientBudget.PROPERTY_API_CALL_ATTEMPT_TIMEOUT + " and "
                    + QueueClientBudget.PROPERTY_API_CALL_TIMEOUT);
        }

        // WHY : Assumptions: the shutdown window is the period a publish has to finish inside on a
        //       task that runs one job and exits, and it is owned by the module's base profile rather
        //       than by this class. Equality is refused as well as excess: a send that used its entire
        //       allowance would finish at the exact instant the process was due to stop, so whether
        //       the notification left would be decided by scheduling. A strict comparison leaves the
        //       difference as headroom for the rest of shutdown.
        if (apiCallTimeout.compareTo(shutdownBudget) >= 0) {
            throw new IllegalStateException("apiCallTimeout " + apiCallTimeout
                    + " is not shorter than the graceful-shutdown window " + shutdownBudget
                    + ": a send that can outlive shutdown is a send the platform terminates part-way,"
                    + " which loses the notification and delays the container reaching a terminal"
                    + " state; configured by " + QueueClientBudget.PROPERTY_API_CALL_TIMEOUT + " and "
                    + PROPERTY_SHUTDOWN_BUDGET);
        }

        return configurer.configure(SqsClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(apiCallAttemptTimeout))
                .build();
    }

    /**
     * Validates the terminal sink's binding at startup and records that publishing is enabled.
     *
     * <p>Assumptions: the four values are validated HERE rather than at the first publish, because the
     * first publish only happens when a step has already failed. A misconfiguration discovered on
     * that path costs the notification of the failure that discovered it, which is the one occasion
     * the sink exists for; discovered at startup it costs a task that had nothing to report anyway.</p>
     *
     * <p>Assumptions: an ordered destination is REFUSED rather than accommodated. The migration plan's
     * section 0.4.1.8 designates the error queue a standard queue, and the send this binding builds
     * therefore sets neither of the two identifiers an ordered queue requires. Handed an ordered
     * address, the transport would reject every send for a missing group identifier -- again, only on
     * the failure path -- so the suffix is checked while the context is still starting.</p>
     *
     * <p>Trade-offs: the two source identifiers are bounded at the copybook's own widths, so a
     * deployment supplying a longer value fails at startup rather than having its value silently
     * truncated at the point a consumer reads it. The accepted cost is that a deployment cannot use a
     * longer name for its own convenience; that is the intended cost, because the widths are the
     * contract the field vocabulary carries and a value that does not fit is a value that would be
     * reported differently by two readers.</p>
     *
     * @param queueUrl the terminal error sink's address, from {@value #PROPERTY_ERROR_QUEUE_URL}; must
     *     not be {@code null}, must not be blank and must not name an ordered queue
     * @param contentType the media type published on the {@value #ATTRIBUTE_CONTENT_TYPE} attribute,
     *     from {@value #PROPERTY_ERROR_CONTENT_TYPE}, defaulting to {@value #DEFAULT_CONTENT_TYPE};
     *     must not be {@code null}, must not be blank and must equal
     *     {@value #DEFAULT_CONTENT_TYPE} exactly
     * @param sourceApplication the value populating {@code ERR-APPLICATION}, from
     *     {@value #PROPERTY_ERROR_SOURCE_APPLICATION}, defaulting to an eight-character token naming
     *     the deployment because the module's own artifact name does not fit the field; must not be
     *     {@code null}, must not be blank and must be at most {@value #ERR_APPLICATION_LENGTH}
     *     characters
     * @param sourceProgram the value populating {@code ERR-PROGRAM}, from
     *     {@value #PROPERTY_ERROR_SOURCE_PROGRAM}, defaulting to an eight-character token naming this
     *     module for the same reason; must not be {@code null}, must not be blank and must be at most
     *     {@value #ERR_PROGRAM_LENGTH} characters
     * @return the validated binding a publisher builds its send request from, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if any value is blank, if the address names an ordered queue, if
     *     the media type is any value other than {@value #DEFAULT_CONTENT_TYPE}, or if either source
     *     identifier exceeds its copybook width
     */
    @Bean
    public ErrorSinkBinding batchErrorSinkBinding(
            @Value("${" + PROPERTY_ERROR_QUEUE_URL + "}") String queueUrl,
            @Value("${" + PROPERTY_ERROR_CONTENT_TYPE + ":" + DEFAULT_CONTENT_TYPE + "}")
            String contentType,
            @Value("${" + PROPERTY_ERROR_SOURCE_APPLICATION + ":CARDDEMO}") String sourceApplication,
            @Value("${" + PROPERTY_ERROR_SOURCE_PROGRAM + ":BATCHSVC}") String sourceProgram) {

        ErrorSinkBinding binding = new ErrorSinkBinding(queueUrl, contentType, sourceApplication,
                sourceProgram);

        // WHY : Trade-offs: the gate's cost is that an environment which meant to publish but did not
        //       supply the address publishes nothing and reports nothing, because a skipped
        //       configuration has no voice. One line at the level a deployment collects is the
        //       mitigation: its presence says publishing is enabled and its absence says the gate
        //       closed, so the two states are distinguishable from the log stream alone. Assumptions:
        //       the address is NOT logged. It is deployment configuration and an operator can read it
        //       from the parameter it came from, whereas a log stream is copied into stores that
        //       inherit none of that parameter's controls.
        LOG.info("event=batch.error.sink.enabled gate={} contentType={} application={} program={}",
                PROPERTY_ERROR_QUEUE_URL, binding.contentType(), binding.sourceApplication(),
                binding.sourceProgram());
        return binding;
    }

    /**
     * Publishes the producer that actually sends a failure notification through the binding above.
     *
     * <p>Refactoring Rationale: <b>this bean is what makes the rest of this class reachable.</b> Before
     * it existed the property gate opened a configuration that validated an address, framed a media
     * type, bounded two source identifiers and built a send request, and nothing in the module called
     * any of it -- the binding's only callers were its own tests. A configuration that no production
     * path reaches is not a dormant feature, it is a claim the deployment cannot keep: the class
     * documented an error sink the orchestrator's failure-notification state could route on, and no
     * message was ever put on the wire. The producer is declared HERE, behind the same property gate as
     * the binding, so the address, the send shape and the send itself appear or are absent together.</p>
     *
     * <p>Assumptions: the producer lives in this module's service package rather than in this
     * configuration class, and only its wiring is here. This class's whole authority is configuration;
     * a send loop, a swallow policy and a log contract are behaviour, and putting them in a
     * {@code @Configuration} class would make them unreachable to a unit test that does not build a
     * context.</p>
     *
     * <p>Assumptions: the mapper is INJECTED rather than constructed. The context's own mapper carries
     * the shared kernel's modules -- decisively the money module, which is the reason no amount in this
     * migration is ever framed as a JSON number -- so a mapper built here would be a second, unmodified
     * wire form for one message. This payload carries no amount today, and that is exactly why the
     * shortcut would be invisible until one was added.</p>
     *
     * @param sqs the client declared by {@link #sqsClient}, or a caller-supplied replacement; must not
     *     be {@code null}
     * @param binding the binding declared by {@link #batchErrorSinkBinding}; must not be {@code null}
     * @param objectMapper the context's own mapper, carrying the shared kernel's modules; must not be
     *     {@code null}
     * @return the producer both publication occasions send through, which delivers one notification per
     *     failed run, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @Bean
    public BatchErrorPublisher batchErrorPublisher(SqsClient sqs, ErrorSinkBinding binding,
            ObjectMapper objectMapper) {
        return new BatchErrorPublisher(sqs, binding, objectMapper);
    }

    /**
     * Declares the step-level adapter the durable ledger reports its diagnoses through.
     *
     * <p>Purpose. The module reaches the terminal error sink on two distinct occasions, and this declares
     * the wiring for the second of them. {@link #batchErrorPublisher} serves the entry point, which
     * announces the run's graded outcome as the process exits. This serves
     * {@code com.carddemo.batch.service.BatchStepLedger}, which reports a failed step with the
     * diagnostics that step produced. The two carry different payloads -- the run notification carries no
     * {@code AbendDetail} and the step report carries a redacted one -- and they are two occasions rather
     * than two messages: the adapter delegates to the same producer, which admits the first attempt that
     * reaches the sink and suppresses any later attempt for the same run.</p>
     *
     * <p>Refactoring Rationale: this adapter used to hold a client, a mapper and the binding and issue
     * its own send, so one hard failure put TWO messages on the queue -- the step report and the run
     * notification -- against the producer's documented contract of one per failed run. Delegating is
     * what makes that contract enforceable at all: a claim held by one of two senders cannot see what the
     * other sent, and the sink has no key to deduplicate on.</p>
     *
     * <p>Refactoring Rationale: the ledger takes {@code Optional<BatchFailureReporter>} and Spring
     * resolves an absent candidate to empty, so a missing declaration here does not fail a context, does
     * not fail a test that builds one, and does not fail a build. It silently disables the step-level
     * report instead: every step failure would still be recorded in the ledger row and none would ever
     * reach the sink. Declaring the implementation is therefore the whole of what makes that path live,
     * and it is declared behind the same gate as the rest of the sink so an unconfigured deployment
     * contributes nothing at all.</p>
     *
     * <p>Assumptions: the adapter is constructed here rather than annotated as a component so that its
     * wiring is gated by this class alone. A component-scanned bean would be contributed whether or not
     * the sink's address was supplied, which is the property {@code SqsConfigTest} asserts against by
     * requiring that an unconfigured deployment gets no sink wiring of any kind.</p>
     *
     * <p>Alternatives Considered: deleting the adapter and pointing the ledger at
     * {@link BatchErrorPublisher} directly, which needs no declaration here because that bean already
     * exists. Rejected because the ledger sits in the service package and that would put the producer's
     * transport collaborators on the far side of no interface at all, where the port it takes today lets
     * its own unit test exercise the report path with a recording stub and no client -- and the adapter
     * additionally absorbs an {@link Error}, which the producer deliberately does not.</p>
     *
     * @param publisher the producer declared by {@link #batchErrorPublisher}; must not be {@code null}
     * @return the reporter the durable step ledger reports each step failure through, never
     *     {@code null}
     * @throws NullPointerException if {@code publisher} is {@code null}
     */
    @Bean
    public BatchFailureReporter batchFailureReporter(BatchErrorPublisher publisher) {
        return new SqsBatchFailureReporter(publisher);
    }

    /**
     * Reads the configured graceful-shutdown window and refuses one that cannot bound a send.
     *
     * <p>Alternatives Considered: declaring the parameter as a {@link Duration} and letting the
     * container convert the configured text, which is the shorter spelling and was the first one
     * written. Rejected because the conversion is not the language's: the abbreviated notation the
     * module's base profile writes this value in is understood by the framework's own application
     * conversion service, which a full application installs and a plain application context does not.
     * The bean was therefore constructible only inside a fully bootstrapped application, and outside
     * one it failed refresh with a type-conversion message that named neither the property nor the
     * bound it carries. Reading the text and parsing it with the framework's own duration reader
     * accepts exactly the same notations while making the bean constructible and assertable anywhere,
     * which is the same choice {@code reporting-service} records for its own call ceiling.</p>
     *
     * <p>Assumptions: a non-positive window is refused even though the value the base profile declares
     * is positive, because an overlay or an environment override could supply another and a
     * non-positive window would make the comparison below reject every configuration -- naming the
     * send bound when the fault is the window.</p>
     *
     * @param spec the configured window, in the abbreviated or interval notation; must not be
     *     {@code null}
     * @return the window the specification denotes, guaranteed strictly positive, never {@code null}
     * @throws NullPointerException if {@code spec} is {@code null}
     * @throws IllegalStateException if the text cannot be read as a duration, or denotes a duration
     *     that is zero or negative
     */
    private static Duration readShutdownBudget(String spec) {
        Objects.requireNonNull(spec, PROPERTY_SHUTDOWN_BUDGET + " must not be null");
        Duration parsed;
        try {
            parsed = DurationStyle.detectAndParse(spec);
        } catch (IllegalArgumentException unreadable) {
            // WHY : Assumptions: the cause is carried rather than discarded, because the reader's own
            //       message names the offending notation while this message names the key that carried
            //       it, and an operator needs both halves to correct the right thing. The thrown type
            //       is IllegalStateException to match every other refusal this class raises, so one
            //       catch at a call site covers the whole configuration rather than two.
            throw new IllegalStateException(PROPERTY_SHUTDOWN_BUDGET
                    + " is not a readable duration: " + spec, unreadable);
        }
        requirePositive(parsed, "shutdownBudget", PROPERTY_SHUTDOWN_BUDGET);
        return parsed;
    }

    /**
     * Refuses a non-positive duration, naming both the member and the property it was configured by.
     *
     * <p>Assumptions: the member name comes first and the property name is appended, so the sentence
     * reads as a statement about the value while still telling an operator which setting to repair.
     * This mirrors the shared kernel's own failure wording deliberately, so that a bound refused in
     * this module and the same bound refused in a sibling read alike in one aggregated log.</p>
     *
     * @param bound the duration to check; must not be {@code null}
     * @param name the member being checked, quoted in the failure; must not be {@code null}
     * @param property the property the member is configured by, quoted in the failure; must not be
     *     {@code null}
     * @throws IllegalStateException if the duration is zero or negative
     */
    private static void requirePositive(Duration bound, String name, String property) {
        if (bound.isZero() || bound.isNegative()) {
            throw new IllegalStateException(name + " must be positive but was " + bound
                    + ": a zero or negative bound is not a shorter deadline, it is an immediate one;"
                    + " configured by " + property);
        }
    }

    /**
     * Wraps a value as a textual message attribute.
     *
     * <p>Assumptions: every attribute this class sets is declared textual, because all three are text
     * and because the transport's numeric and binary types would oblige a consumer to branch on type
     * before reading a value whose meaning does not vary.</p>
     *
     * @param value the attribute value; must not be {@code null}
     * @return the attribute, never {@code null}
     */
    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType(ATTRIBUTE_TYPE_STRING).stringValue(value)
                .build();
    }

    /**
     * The validated binding of this module to the terminal error sink, and the shape of one send.
     *
     * <p><b>Purpose.</b> This record holds the four configured values a published failure notification
     * needs -- where it goes, how its body is framed, and the application and program it came from --
     * and turns one already-serialised body into one send request. It is the only place in this module
     * where a send request is shaped, which is what makes the two identifiers it does NOT set checkable
     * in one assertion rather than at every call site.</p>
     *
     * <p>Assumptions: a nested type rather than a separate file, because it exists solely to be
     * returned by {@link SqsConfig#batchErrorSinkBinding} and has no meaning apart from that
     * configuration. The authorization bounded context's queue configuration nests its own naming
     * contract the same way and for the same reason, so the shape is the tree's convention rather than
     * a local preference.</p>
     *
     * <p>Assumptions: this record does not serialise the payload and holds no mapper. The wire form of
     * every shape in this migration is decided once, in the shared kernel's mapper module, which binds
     * a record through its canonical constructor; a serialiser here would be a second place the wire
     * form is decided, and the second place is the one that silently wins. The caller therefore hands
     * in the body already serialised from {@link BatchErrorEvent}.</p>
     *
     * @param queueUrl the terminal error sink's address, as configured; never {@code null}, never
     *     blank, and never the address of an ordered queue
     * @param contentType the media type published on the {@value SqsConfig#ATTRIBUTE_CONTENT_TYPE}
     *     attribute, which must describe the body the caller supplies; never {@code null} and never
     *     blank
     * @param sourceApplication the value populating {@code ERR-APPLICATION}, bounded at that field's
     *     own {@code PIC X(08)} width; never {@code null} and never blank
     * @param sourceProgram the value populating {@code ERR-PROGRAM}, bounded at that field's own
     *     {@code PIC X(08)} width; never {@code null} and never blank
     */
    public record ErrorSinkBinding(String queueUrl, String contentType, String sourceApplication,
            String sourceProgram) {

        /**
         * Validates the four configured values, refusing an ordered destination and an over-wide name.
         *
         * <p>Assumptions: no value is trimmed except of surrounding whitespace, and none is defaulted
         * inside this constructor. Defaulting here would let a blank parameter produce a working
         * binding, which is the outcome the startup check exists to prevent; the two identifiers carry
         * their defaults at the binding method's parameters instead, where a reader can see them
         * beside the property names they belong to.</p>
         *
         * @param queueUrl the address as configured, before trimming; must not be {@code null}, must
         *     not be blank once trimmed, and must not end in {@value SqsConfig#FIFO_QUEUE_SUFFIX}
         * @param contentType the media type as configured, before trimming; must not be {@code null},
         *     must not be blank once trimmed, and must equal {@value SqsConfig#DEFAULT_CONTENT_TYPE}
         *     exactly, because that is the wire form the publisher actually produces
         * @param sourceApplication the {@code ERR-APPLICATION} value as configured, before trimming;
         *     must not be {@code null}, must not be blank once trimmed, and must be at most
         *     {@value SqsConfig#ERR_APPLICATION_LENGTH} characters
         * @param sourceProgram the {@code ERR-PROGRAM} value as configured, before trimming; must not
         *     be {@code null}, must not be blank once trimmed, and must be at most
         *     {@value SqsConfig#ERR_PROGRAM_LENGTH} characters
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalStateException if any component is blank, if {@code queueUrl} names an
         *     ordered queue, if {@code contentType} is any value other than
         *     {@value SqsConfig#DEFAULT_CONTENT_TYPE}, or if either source identifier exceeds its
         *     copybook width; each failure names the property that carries the value so it is
         *     repaired where it was set
         */
        public ErrorSinkBinding {
            queueUrl = requireConfigured(queueUrl, PROPERTY_ERROR_QUEUE_URL);
            contentType = requireExactMediaType(
                    requireConfigured(contentType, PROPERTY_ERROR_CONTENT_TYPE));
            sourceApplication = requireWithin(
                    requireConfigured(sourceApplication, PROPERTY_ERROR_SOURCE_APPLICATION),
                    ERR_APPLICATION_LENGTH, PROPERTY_ERROR_SOURCE_APPLICATION, "ERR-APPLICATION");
            sourceProgram = requireWithin(
                    requireConfigured(sourceProgram, PROPERTY_ERROR_SOURCE_PROGRAM),
                    ERR_PROGRAM_LENGTH, PROPERTY_ERROR_SOURCE_PROGRAM, "ERR-PROGRAM");

            // WHY : Assumptions: an ordered queue REQUIRES a message-group identifier on every send
            //       and accepts a deduplication identifier, and a standard queue accepts neither -- so
            //       the two are not interchangeable destinations for one send shape. Sending the
            //       ordered-only identifiers to a standard queue is a rejected request rather than a
            //       harmless extra, and omitting them on an ordered queue is equally rejected. The
            //       ordered discipline in this migration, grouping by card number and deduplicating by
            //       transaction identifier, belongs exclusively to the authorization request and reply
            //       path, where per-card ordering is the contract; a terminal sink has no such
            //       contract, so the address it is given must be the standard one.
            if (queueUrl.endsWith(FIFO_QUEUE_SUFFIX)) {
                throw new IllegalStateException(PROPERTY_ERROR_QUEUE_URL
                        + " names an ordered queue, ending in " + FIFO_QUEUE_SUFFIX
                        + ": the terminal error sink is a standard queue, so every send built here"
                        + " omits the message-group and deduplication identifiers an ordered queue"
                        + " requires, and an ordered address would have every send rejected on the"
                        + " failure path rather than at startup");
            }
        }

        /**
         * Builds the send request for one already-serialised failure notification.
         *
         * <p>Assumptions: this method is called AFTER the unit of work has committed, and never inside
         * it. The reference commits three writes as one unit of work --
         * {@code app/cbl/CBTRN02C.cbl:440} performs {@code 2700-UPDATE-TCATBAL}, its line 441 performs
         * {@code 2800-UPDATE-ACCOUNT-REC} and its line 442 performs
         * {@code 2900-WRITE-TRANSACTION-FILE} -- and the migrated posting step keeps that a single
         * atomic commit with nothing else inside it. A send issued inside the transaction would either
         * hold the commit open across a network call while the step's row locks were still held, or
         * announce an outcome for a unit of work that then rolled back.</p>
         *
         * <p>Trade-offs: a failure between the commit and this send therefore loses the notification,
         * and that is accepted rather than mitigated. The authoritative record of a batch outcome is
         * the durable step ledger's own return code together with the run's log stream, and the
         * orchestrator's catch route reports a failed state independently of the queue, so a lost
         * notification costs a duplicate of something two other channels already carry.</p>
         *
         * <p>Alternatives Considered: a transactional outbox, writing the notification inside the same
         * commit and publishing it afterwards, which is exactly what the authorization bounded context
         * does. Rejected here, and the contrast is the point. There a committed authorization decision
         * MUST produce a reply, because a requester is waiting on one and a decision with no answer is
         * a broken request-reply contract -- so the outbox closes a genuine lost-reply window. A
         * terminal sink has no requester, no reply and no waiting party, so there is no window to
         * close; and an outbox row would put a second write inside the very transaction whose atomicity
         * the posting step exists to preserve, trading a real invariant for a duplicate notification.
         * No outbox table, entity or publisher is introduced in this module.</p>
         *
         * <p>Assumptions: the correlation identity is validated by the shared kernel's messaging rule
         * rather than by its servlet rule. The value travels as queue metadata, and that is the rule
         * written for queue metadata -- it admits the printable characters the transport accepts and
         * excludes the control characters that would let a value forge a log record. It is validated
         * rather than rewritten, because a rewritten identity would no longer join the run's own log
         * lines, which is the single thing this attribute is for.</p>
         *
         * @param body the serialised notification, framed as the media type this binding declares;
         *     must not be {@code null} and must not be blank
         * @param correlationId the correlation identity the run's log lines were written under; must
         *     not be {@code null} and must satisfy the shared messaging rule
         * @param messageId the publisher's own identity for this event, distinct from the identifier
         *     the transport assigns; must not be {@code null} and must satisfy the same rule
         * @return the send request, carrying exactly the correlation, message and media-type
         *     attributes and neither ordered-queue identifier, never {@code null}
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if the body is blank, or if either identity fails the shared
         *     messaging rule, which is refused here rather than at the transport so that the rejection
         *     names the value instead of surfacing as a service-side parameter error
         */
        public SendMessageRequest publicationOf(String body, String correlationId, String messageId) {
            Objects.requireNonNull(body, "body must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
            Objects.requireNonNull(messageId, "messageId must not be null");
            if (body.isBlank()) {
                throw new IllegalArgumentException("body must not be blank: a send with an empty body"
                        + " reaches the sink as a message a reader cannot interpret, which counts as a"
                        + " failure report while carrying none");
            }
            requireCanonicalIdentity(correlationId, "correlationId");
            requireCanonicalIdentity(messageId, "messageId");

            // WHY : Assumptions: the set is exactly three, as the migration plan's section 0.4.1.8
            //       maps the inherited message descriptor, and it is closed here rather than left open
            //       for a caller to extend. The two further attributes that mapping admits, a reply
            //       destination and a validity horizon, are argued absent on this class; the reason
            //       they are absent HERE, at the only place a send is shaped, is that a caller with a
            //       map it could add to would be able to reintroduce either one without the decision
            //       being reviewed. Trade-offs: an insertion-ordered map is used rather than a plain
            //       one, which costs a little memory and buys a deterministic rendering, so a test
            //       asserting the whole attribute set does not depend on hash order.
            Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(correlationId));
            attributes.put(ATTRIBUTE_MESSAGE_ID, stringAttribute(messageId));
            attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(this.contentType));

            // WHY : Assumptions: no message-group identifier and no deduplication identifier is set,
            //       and the omission is the standard-queue contract rather than an oversight. The
            //       transport rejects either identifier on a standard queue, so this is not a case of
            //       harmless extra metadata. Trade-offs: what a standard queue gives up in exchange is
            //       ordering and single delivery -- each event may arrive more than once and events
            //       may arrive out of order. Both are acceptable for this sink because each
            //       notification describes one independent step failure and carries the run and step
            //       that identify it, so a duplicate notification is a duplicate alert rather than a
            //       duplicated action, and there is no sequence for a reader to reconstruct.
            return SendMessageRequest.builder()
                    .queueUrl(this.queueUrl)
                    .messageBody(body)
                    .messageAttributes(attributes)
                    .build();
        }

        /**
         * Refuses a blank configured value, naming the property that carries it.
         *
         * @param value the configured value; must not be {@code null}
         * @param property the property the value was bound from, quoted in the failure; must not be
         *     {@code null}
         * @return the value with surrounding whitespace removed, never {@code null} and never blank
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalStateException if the value is blank once trimmed
         */
        private static String requireConfigured(String value, String property) {
            Objects.requireNonNull(value, property + " must not be null");
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalStateException(property + " must carry a value: a blank setting is"
                        + " indistinguishable from a configured one at the moment of publishing, which"
                        + " is when a step has already failed");
            }
            return trimmed;
        }

        /**
         * Refuses a media type other than the one the publisher actually produces.
         *
         * <p>Assumptions: the check is EQUALITY against a single admitted value rather than a
         * structural test that the value parses as a media type, because the attribute is a promise
         * about the body and only one body shape is ever built. The one publisher on this binding,
         * {@code com.carddemo.batch.service.BatchErrorSink}, serialises {@link BatchErrorEvent}
         * through the context's own JSON mapper and has no second wire form to select, so any other
         * configured value labels a JSON document as something it is not. A consumer that trusts the
         * label then reads the body with the wrong reader and reports a malformed message on the one
         * occasion the sink is used, which is the failure this refusal converts into a startup
         * failure.</p>
         *
         * <p>Alternatives Considered: accepting any value whose base type is {@code application/json},
         * so a deployment could append a charset parameter. Rejected because the body is serialised as
         * UTF-8 by the mapper unconditionally and the transport carries the attribute as an opaque
         * string, so a charset parameter could only ever restate that or contradict it -- and a value
         * that may restate but may not contradict is exactly a value with one admitted spelling.
         * Accepting a family also reopens the question of which member is meant, which is what the
         * class contract closed by naming one.</p>
         *
         * <p>Trade-offs: the property therefore cannot change the wire form, only confirm it, so its
         * remaining value is that a deployment may state the contract explicitly and be checked
         * against it. Removing the property outright was the other option and was declined: the
         * attribute has to be built from something, and reading it from configuration keeps the
         * published contract and the built attribute the same value rather than two constants that
         * could drift.</p>
         *
         * @param contentType the configured media type, already trimmed and non-blank; must not be
         *     {@code null}
         * @return the value unchanged, never {@code null}
         * @throws IllegalStateException if the value is anything other than
         *     {@value SqsConfig#DEFAULT_CONTENT_TYPE}
         */
        private static String requireExactMediaType(String contentType) {
            if (!DEFAULT_CONTENT_TYPE.equals(contentType)) {
                throw new IllegalStateException(PROPERTY_ERROR_CONTENT_TYPE + " is '" + contentType
                        + "', and the only admitted value is '" + DEFAULT_CONTENT_TYPE + "': the"
                        + " publisher serialises the event as JSON and has no second wire form, so"
                        + " any other value would label a JSON document as something it is not and a"
                        + " consumer trusting the label would fail to read the one message the sink"
                        + " exists to deliver");
            }
            return contentType;
        }

        /**
         * Refuses a configured value wider than the copybook field it populates.
         *
         * <p>Assumptions: the width is the copybook's own and is checked in characters. The reference
         * field is a fixed-width display field, so a wider value has no representation there at all;
         * truncating it silently would let two readers report the same source under two names.</p>
         *
         * @param value the configured value, already trimmed and non-blank; must not be {@code null}
         * @param width the copybook field's width in characters; must be positive
         * @param property the property the value was bound from, quoted in the failure; must not be
         *     {@code null}
         * @param field the copybook field the value populates, quoted in the failure; must not be
         *     {@code null}
         * @return the value unchanged, never {@code null}
         * @throws IllegalStateException if the value is longer than the field's width
         */
        private static String requireWithin(String value, int width, String property, String field) {
            if (value.length() > width) {
                throw new IllegalStateException(property + " is " + value.length()
                        + " characters, which exceeds the " + width + " characters of " + field
                        + ": the field is fixed width in the reference record, so a longer value has"
                        + " no representation and truncating it would report one source under two"
                        + " names");
            }
            return value;
        }

        /**
         * Refuses an identity the transport would not carry as a textual attribute.
         *
         * <p>Assumptions: the shared kernel's messaging rule is applied rather than a local one,
         * because the constraint belongs to the transport and to every module that publishes on it.
         * A local predicate here would be a second expression of one contract, free to be relaxed
         * while the other two modules still claimed the guarantee.</p>
         *
         * @param identity the value about to travel as a message attribute; must not be {@code null}
         * @param name the argument being checked, quoted in the failure; must not be {@code null}
         * @throws IllegalArgumentException if the value is absent or does not satisfy the shared rule
         */
        private static void requireCanonicalIdentity(String identity, String name) {
            if (!MessagingCorrelationId.isPresent(identity)
                    || !MessagingCorrelationId.isCanonical(identity)) {
                throw new IllegalArgumentException(name + " is not usable as a message attribute: '"
                        + MessagingCorrelationId.logSafe(identity) + "' is blank, longer than "
                        + MessagingCorrelationId.MAX_LENGTH
                        + " characters, or carries a character outside the printable range the"
                        + " transport accepts");
            }
        }
    }
}
