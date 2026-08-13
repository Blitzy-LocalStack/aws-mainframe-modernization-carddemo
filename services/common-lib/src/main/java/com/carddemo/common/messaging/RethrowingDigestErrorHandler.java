package com.carddemo.common.messaging;

import com.carddemo.common.observability.FailureSummary;
import com.carddemo.common.observability.ThrowableDigest;
import io.awspring.cloud.sqs.listener.ListenerExecutionFailedException;
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;

/**
 * Records a failed queue delivery as a message-free digest and then rethrows it unchanged.
 *
 * <p><b>Purpose.</b> A listener failure has to be visible to an operator and must not change how the
 * queue treats the message. Those two obligations pull in opposite directions in the pinned starter, and
 * this handler is what holds both of them at once: it writes one line naming the failure's chain of TYPES
 * and its frames, with no exception message text anywhere in it, and it then rethrows the original
 * throwable so the delivery outcome is byte for byte what it would have been with no handler registered
 * at all.</p>
 *
 * <h2>Why the condition is named rather than the message quoted</h2>
 *
 * <p>The exception messages on a listener failure are not written by this repository. They come from a
 * JDBC driver quoting the statement it could not run, a codec quoting the bytes it could not read, or a
 * validation library naming the value it rejected — and the values in a queue payload here are primary
 * account numbers, account identifiers and whole request records. Rendering the chain's messages as
 * written therefore publishes exactly the data that
 * {@code com.carddemo.common.error.GlobalExceptionHandler} refuses to publish on the servlet side.
 * {@link ThrowableDigest} is the rendering written for that exposure, and this handler carries it for the
 * same reason: it names every type in the chain and the frame each was raised at, and no message text at
 * all.</p>
 *
 * <p>⚠️ Refactoring Rationale: the digest alone was NOT enough, and this line carried nothing else for
 * long enough to be measured. A persistence failure reaches this handler as one driver exception type
 * whatever went wrong, so a numeric overflow, a unique violation and a serialisation conflict were all
 * recorded identically — and the operator response to each is different. Diagnosing one required turning
 * on driver debug logging against a live consumer, which is a worse disclosure than the one being
 * avoided. The line therefore now carries two further fields: the database state code through
 * {@link FailureSummary#sqlStateOrAbsent(Throwable)}, which is a fixed five-character class name no
 * requester can influence; and the failure's condition through
 * {@link FailureSummary#databaseConditionOf(Throwable)}.</p>
 *
 * <p>⚠️ Assumptions: that second field carries NO MESSAGE TEXT AT ALL, which is the property that makes it
 * safe on a handler that cannot know what it is holding. It is default-withheld and admitted only on
 * evidence — a chain carrying a state code was composed by a database driver — and what is then admitted is
 * the engine's own CONDITION NAME for that code, drawn from a closed map, followed by any constraint,
 * relation or column name the engine quoted after one of its own keywords. A chain carrying no state code
 * could have been composed by anything, and this handler's own test set already held the case that proves
 * why that matters: a transport failure reporting {@code connect failed to https://... using key AKIA...}
 * carries an access-key identifier, which contains no digit run and would pass any digit rule untouched.
 * Such a message renders as {@link FailureSummary#WITHHELD}, and the digest still names every type in the
 * chain.</p>
 *
 * <p>⚠️ Refactoring Rationale: this paragraph previously read that a driver's messages "quote DDL names and
 * record values — the names are letters and survive, the values are digit runs and are replaced", because
 * the field then carried the driver's redacted MESSAGE. That was wrong in a measurable way: the PostgreSQL
 * driver folds the server's {@code DETAIL} field into its message by default, and a check violation's
 * detail enumerates every column of the rejected row, so a cardholder's given name and surname are letters
 * and survived exactly as identifiers did. The field now names the condition instead of quoting the
 * sentence, so there is no message for a digit rule to be wrong about.</p>
 *
 * <p>⚠️ Trade-offs: a diagnostic sentence is therefore lost at this line — from a non-database failure
 * entirely, and from a database one reduced to a condition name and the names of the objects involved. That
 * is accepted because the located facts are this line's own structured fields: the broker's message
 * identifier, the queue and the redelivery count. A site that DOES know what composed its failure renders
 * the message itself and does not rely on this one; the authorization listener's wire-format refusal is
 * the example, and it names the copybook field the payload broke.</p>
 *
 * <h2>Why it must rethrow, and what happens if it does not</h2>
 *
 * <p>This is the safety-critical half of the class and the reason the rethrow is named in the class name
 * rather than left as an implementation detail. The pinned starter installs the error-handler stage
 * through {@code CompletableFutures.exceptionallyCompose}, so the stage <em>recovers</em> from the
 * failure: a handler that returns normally leaves the pipeline future SUCCESSFUL. The acknowledgement
 * stage runs last, after this one, and it selects
 * {@code AcknowledgementHandler.onSuccess} for a successful future — which under the
 * {@code ON_SUCCESS} acknowledgement mode this repository pins means the message is DELETED. A
 * swallowing error handler would therefore destroy the redrive contract silently: no visibility-timeout
 * redelivery, and no dead-letter at the fifth receive. Rethrowing leaves the future exceptional,
 * {@code onError} runs instead, the message is not acknowledged, and redelivery and dead-lettering are
 * unchanged.</p>
 *
 * <p>Assumptions: the rethrow preserves the throwable's IDENTITY where it can, not merely its type. An
 * unchecked throwable is rethrown as the very same instance, so the starter's own
 * {@code ErrorHandlerExecutionStage.maybeWrap} — which wraps only what is not already a
 * {@code MessageProcessingException} — behaves exactly as it does with no handler present. The listener
 * stage upstream has already wrapped the listener's own exception in a
 * {@link ListenerExecutionFailedException}, so in the ordinary case nothing is wrapped twice and nothing
 * is unwrapped.</p>
 *
 * <p>Alternatives Considered, and why each was rejected:</p>
 *
 * <ul>
 *   <li>Registering no handler and relying on the starter's own log line. Rejected because that line is
 *       the exposure: it is
 *       {@code error("Error processing message {}.", id, throwable)}, and a trailing throwable argument
 *       makes the logging facade render the whole chain including every message. It is switched off by
 *       name in the shared defaults, which is why a replacement line has to exist here.</li>
 *   <li>Suppressing the starter's line and adding nothing. Rejected because a failure would then be
 *       invisible: the message would silently reappear and silently dead-letter, with no record naming
 *       what threw.</li>
 *   <li>Logging {@code failure.toString()} instead of the digest. Rejected because
 *       {@code Throwable.toString} is exactly {@code type + ": " + message}, so it carries the message
 *       text this class exists to withhold.</li>
 *   <li>A message interceptor rather than an error handler. Rejected because an interceptor that only
 *       observes cannot see the throwable, and one that consumes it takes on the same swallow hazard
 *       described above without the error-handler contract making that visible.</li>
 * </ul>
 *
 * <p>Trade-offs: the digest deliberately withholds information that would sometimes shorten a diagnosis.
 * The chain of types, the frames and the broker's own message identifier are enough to find the failing
 * code and to fetch the offending message from the dead-letter queue under access control, which is
 * where a payload may legitimately be read. Putting the payload's own values in a log stream instead
 * would make every reader of that stream a reader of card data.</p>
 *
 * <p>Assumptions: this type is transport-aware, unlike the three value rules beside it, and it is in this
 * package rather than in a nested one of its own for a stated architectural reason rather than for
 * convenience. The shared kernel's root charter declares a CLOSED inventory of a root and ten flat
 * subpackages, and {@code SharedKernelInventoryTest} re-derives that table from the directory one level
 * deep, recording that a nested subpackage is "the drift this test exists to expose rather than absorb".
 * Adding a twelfth package for one class would have meant widening that gate against its own documented
 * intent. Alternatives Considered: exactly that -- a {@code messaging.listener} subpackage, which keeps
 * this package's charter able to claim that nothing in it touches a message. Rejected because the charter
 * can instead state the one exception precisely, which costs a sentence, whereas widening the inventory
 * gate costs the flat closed set the module is designed around.</p>
 *
 * <p>Assumptions: this is the only type in the shared kernel that needs the queue starter, which is why
 * that starter is declared {@code optional} in this module's descriptor -- the four services that own a
 * listener already declare it themselves, and the four that own no queue carry no queue client because of
 * the shared kernel.</p>
 *
 * @param <T> the payload type of the messages the listener this handler is registered on receives; the
 *     handler never reads the payload, so it places no constraint on this type and exists as a generic
 *     type only because the interface it implements is generic in the same position
 */
public final class RethrowingDigestErrorHandler<T> implements ErrorHandler<T> {

    /**
     * The event name every line this handler writes begins with.
     *
     * <p>Assumptions: one event name is used for both the single-message and the batch overload, with the
     * count carried as a field rather than encoded in a second name. A log-insights query that counts
     * listener failures should not have to know which overload the container happened to call, and the
     * container's choice of overload is a function of its batching configuration rather than of anything
     * that failed.</p>
     */
    public static final String EVENT = "event=messaging.listener.failed";

    /**
     * Rendered in place of a message identifier when the message carries none.
     *
     * <p>Assumptions: a message with no identifier is possible rather than hypothetical, because the
     * batch overload can in principle be handed an empty collection, and an absent value must render as
     * a stable token rather than as the string {@code null} so a query can match it.</p>
     */
    public static final String NO_IDENTIFIER = "(none)";

    /**
     * Wrapped around a CHECKED failure so it can leave a method that declares no checked exception.
     *
     * <p>Assumptions: the wrapper's own message is a fixed literal and carries nothing from the failure
     * it wraps, because this text can reach a log through a path this class does not control.</p>
     */
    public static final String CHECKED_FAILURE_WRAPPER_MESSAGE =
            "listener failure rethrown to preserve the queue redrive contract";

    /**
     * The header the starter carries the broker's own approximate redelivery count in.
     *
     * <p>Assumptions: the starter's constant is referenced rather than the literal spelled out, for the
     * reason recorded on the queue-name header below.</p>
     */
    private static final String RECEIVE_COUNT_HEADER =
            SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT;

    /**
     * The header the starter carries the broker's own identifier for the message in.
     *
     * <p>Assumptions: this is the identifier a queue search and a dead-letter search both take, which is
     * why it leads the identifier field ahead of the framework's standard id header.</p>
     */
    private static final String TRANSPORT_IDENTIFIER_HEADER =
            SqsHeaders.MessageSystemAttributes.MESSAGE_ID;

    /**
     * Rendered in place of a redelivery count when the message carries no such header.
     */
    private static final String UNKNOWN_RECEIVE_COUNT = "(unknown)";

    /**
     * Separates the per-message values of the batch overload's identifier and redelivery-count fields.
     *
     * <p>Assumptions: a comma with no space, so one field stays one whitespace-delimited token and a
     * log-insights parse that splits on whitespace does not turn one field into several.</p>
     */
    private static final String FIELD_SEPARATOR = ",";

    /**
     * The header the starter's own header mapper carries the originating queue name in.
     *
     * <p>Assumptions: the starter's constant is referenced rather than the literal spelled out, so a
     * starter upgrade that renames or moves the header fails this module's COMPILATION instead of quietly
     * producing a log line with an empty queue field. A {@code static final String} is inlined by the
     * compiler, so compilation is the only place this coupling can be checked at all -- which is the
     * reason to take the compile-time check rather than to spell the name out and lose it.</p>
     */
    private static final String QUEUE_NAME_HEADER = SqsHeaders.SQS_QUEUE_NAME_HEADER;

    /**
     * Rendered in place of a queue name when the message carries no queue header.
     */
    private static final String UNKNOWN_QUEUE = "(unknown)";

    /**
     * Writes the digest line.
     *
     * <p>Assumptions: the logger is named for this class rather than for the consuming service, so one
     * logger name covers every listener in the fleet and a reader looking for listener failures has one
     * name to look for. The consuming listener is identified by the {@code source} field on the line
     * instead, which is a value a query can group by.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(RethrowingDigestErrorHandler.class);

    /**
     * Names the listener this instance is registered on, for the {@code source} field of the log line.
     */
    private final String source;

    /**
     * Recognises a failure that is an EXPECTED control outcome rather than a fault, or admits none.
     *
     * <p>Assumptions: this is a predicate supplied by the consuming service rather than a type this
     * module knows, because which throwable means "come back later" is a property of that service's own
     * flow-control design and not of messaging. The default admits nothing, so a handler constructed
     * without one behaves exactly as it did before this arm existed.</p>
     */
    private final Predicate<Throwable> expectedOutcome;

    /**
     * The event name an expected control outcome is recorded under, at debug rather than error level.
     */
    private final String expectedOutcomeEvent;

    /**
     * Retains the listener name this handler reports failures against.
     *
     * @param source names the listener this handler is registered on, used verbatim as the
     *     {@code source} field of every line written; must not be {@code null} and must not be blank,
     *     because a blank source would make the one field that distinguishes two listeners' failures
     *     empty
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source} is blank
     */
    public RethrowingDigestErrorHandler(String source) {
        this(source, failure -> false, EVENT);
    }

    /**
     * Retains the listener name and the discrimination that keeps an expected outcome out of the fault
     * record.
     *
     * <p>Refactoring Rationale: this arm exists because one consuming listener bounds its own intake and
     * REFUSES a delivery that arrives while its window is closing, so that the broker redelivers it in the
     * next window. That refusal travels as a throwable, which is the only way to decline a delivery
     * without acknowledging it -- and with one record shape it was written as
     * {@value #EVENT} at error level, on every window boundary. The consequence is not cosmetic: a service
     * behaving exactly as designed reports a burst of listener failures at every boundary, which either
     * raises an alarm for a healthy service or teaches an operator to ignore the one signal that says a
     * listener is broken. A per-service handler was the alternative and was rejected -- three services
     * would then carry three copies of the redaction, the digest and the rethrow rules, which is the
     * duplication this class was extracted to remove.</p>
     *
     * <p>Assumptions: an expected outcome is still RETHROWN, unchanged and by the same rules. What differs
     * is only the record: one line at debug level under the caller's own event name. Swallowing it would
     * acknowledge the delivery and lose the message the refusal exists to defer.</p>
     *
     * @param source names the listener this handler is registered on; must not be {@code null} or blank
     * @param expectedOutcome recognises a failure that is a control outcome rather than a fault; must not
     *     be {@code null}, and admits nothing in the single-argument form
     * @param expectedOutcomeEvent the event name such an outcome is recorded under, used verbatim; must
     *     not be {@code null} or blank
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code source} or {@code expectedOutcomeEvent} is blank
     */
    public RethrowingDigestErrorHandler(String source, Predicate<Throwable> expectedOutcome,
            String expectedOutcomeEvent) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(expectedOutcome, "expectedOutcome");
        Objects.requireNonNull(expectedOutcomeEvent, "expectedOutcomeEvent");
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                    "source must name the listener this handler is registered on, so that two"
                            + " listeners' failures are distinguishable in one log stream");
        }
        if (expectedOutcomeEvent.isBlank()) {
            throw new IllegalArgumentException(
                    "expectedOutcomeEvent must name the event an expected control outcome is recorded"
                            + " under, so that a query can tell it from a fault");
        }
        this.source = source;
        this.expectedOutcome = expectedOutcome;
        this.expectedOutcomeEvent = expectedOutcomeEvent;
    }

    /**
     * Records one failed delivery and rethrows it so the queue's redrive contract is untouched.
     *
     * @param message the message whose handling failed; must not be {@code null}
     * @param failure what the listener threw; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws RuntimeException always — the original failure when it is unchecked, otherwise a
     *     {@link ListenerExecutionFailedException} wrapping it. Returning normally would let the
     *     acknowledgement stage delete the message, so this method has no non-throwing exit.
     * @throws Error always, when the failure is an {@link Error}, rethrown as the same instance rather
     *     than wrapped, because a virtual-machine error must not be turned into an application exception
     */
    @Override
    public void handle(Message<T> message, Throwable failure) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(failure, "failure");

        // WHY : Assumptions: the two identifiers on this line are safe by construction rather than by
        //   redaction, which is why neither is passed through a masker. The message identifier is the
        //   broker's own: the starter's header mapper puts the queue's own messageId into the standard
        //   id header, so it is a value the broker generated and not one a requester chose. The queue
        //   name is infrastructure configuration. Neither can carry a payload value, so masking them
        //   would suggest a doubt that does not exist. This is the distinction the correlation identity
        //   does NOT enjoy -- that one IS requester-supplied, which is why it is redacted before it
        //   reaches a log line and is not repeated here.
        if (this.expectedOutcome.test(failure)) {
            // WHY : Assumptions: debug level and the CALLER's event name, because this is the service
            //       declining a delivery on purpose. It is still recorded rather than passed over, so a
            //       reader can see the boundary was reached and how often, and it still carries the digest
            //       so a refusal that turns out to be the wrong type does not become invisible.
            LOG.debug("{} source={} messageId={} queue={} receiveCount={} outcome={} messageCount=1",
                    this.expectedOutcomeEvent, this.source, identifierOf(message), queueOf(message),
                    receiveCountOf(message), ThrowableDigest.of(failure));
            throw rethrowable(failure, message);
        }

        // WHY : ⚠️ Refactoring Rationale: this line carries the failure's database CONDITION and its
        //       state code beside the type chain, where it previously carried the chain alone. The chain
        //       names what threw and where; it does not name the condition, and for a persistence failure
        //       the condition is the whole diagnosis -- a numeric overflow, a unique violation and a
        //       serialisation conflict all arrive as one driver exception type. An operator reading the
        //       previous line could not tell them apart, and the measured cost of that was a diagnosis
        //       that required enabling driver debug logging on a live consumer.
        // WHY : ⚠️ Assumptions: the detail field is a CONDITION NAME and object names, not the
        //       failure's message. This comment previously said the field carried "the failure's own
        //       deepest MESSAGE" redacted, and it did; the section above records the measured disclosure
        //       that ended -- the driver's message carries the server's DETAIL line, which enumerates the
        //       rejected row. Nothing a requester supplied can reach this line.
        LOG.error("{} source={} messageId={} queue={} receiveCount={} failure={} detail={} sqlState={}"
                        + " messageCount=1",
                EVENT, this.source, identifierOf(message), queueOf(message),
                receiveCountOf(message), ThrowableDigest.of(failure),
                FailureSummary.databaseConditionOf(failure), FailureSummary.sqlStateOrAbsent(failure));

        throw rethrowable(failure, message);
    }

    /**
     * Records a failed batch delivery and rethrows it so the queue's redrive contract is untouched.
     *
     * <p>Assumptions: the batch overload is implemented rather than left to the interface's default,
     * because both methods on the interface are {@code default} and both defaults do nothing. A handler
     * that overrode only the single-message form would swallow every batch failure -- the worst possible
     * outcome, since swallowing is what deletes the message -- and would do so silently in exactly the
     * configuration a maintainer is least likely to be testing.</p>
     *
     * <p>Assumptions: one line is written for the batch rather than one per message. The failure is a
     * single event with a single throwable, and the whole batch shares its outcome, so a line per
     * message would multiply one fact by the batch size while adding nothing an operator can act on.
     * The identifier of the FIRST message is carried so the batch can still be located, and the count is
     * carried so the line is not mistaken for a single-message failure.</p>
     *
     * @param messages the messages whose handling failed; must not be {@code null}, and may be empty,
     *     in which case the identifier renders as {@link #NO_IDENTIFIER}
     * @param failure what the listener threw; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws RuntimeException always — the original failure when it is unchecked, otherwise a
     *     {@link ListenerExecutionFailedException} wrapping it. Returning normally would let the
     *     acknowledgement stage delete every message in the batch, so this method has no non-throwing
     *     exit.
     * @throws Error always, when the failure is an {@link Error}, rethrown as the same instance rather
     *     than wrapped, because a virtual-machine error must not be turned into an application exception
     */
    @Override
    public void handle(Collection<Message<T>> messages, Throwable failure) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(failure, "failure");

        Message<T> first = messages.stream().findFirst().orElse(null);

        // WHY : Refactoring Rationale: the identifier and redelivery-count fields carry EVERY message in
        //       the batch, comma separated, where they previously carried the first message's alone. One
        //       failure with one throwable is still ONE event -- the line count is unchanged and so is the
        //       queryability that rests on it -- but a batch failure whose record named only the first
        //       delivery left the rest unfindable, which is precisely the set an operator needs when the
        //       messages reappear on the dead-letter queue. The first identifier still leads the field, so
        //       a query written against the previous shape continues to match.
        String identifiers = messages.isEmpty() ? NO_IDENTIFIER : messages.stream()
                .map(RethrowingDigestErrorHandler::identifierOf)
                .collect(Collectors.joining(FIELD_SEPARATOR));
        String receiveCounts = messages.isEmpty() ? UNKNOWN_RECEIVE_COUNT : messages.stream()
                .map(RethrowingDigestErrorHandler::receiveCountOf)
                .collect(Collectors.joining(FIELD_SEPARATOR));
        String queue = first == null ? UNKNOWN_QUEUE : queueOf(first);

        if (this.expectedOutcome.test(failure)) {
            LOG.debug("{} source={} messageId={} queue={} receiveCount={} outcome={} messageCount={}",
                    this.expectedOutcomeEvent, this.source, identifiers, queue, receiveCounts,
                    ThrowableDigest.of(failure), messages.size());
            throw rethrowable(failure, messages);
        }

        // WHY : ⚠️ Assumptions: the batch line carries the same two additional fields as the
        //       single-message line above, in the same positions, so one query serves both overloads.
        //       The failure is one throwable whatever the batch size, so there is one condition to
        //       report and it is reported once.
        LOG.error("{} source={} messageId={} queue={} receiveCount={} failure={} detail={} sqlState={}"
                        + " messageCount={}",
                EVENT, this.source, identifiers, queue, receiveCounts,
                ThrowableDigest.of(failure), FailureSummary.databaseConditionOf(failure),
                FailureSummary.sqlStateOrAbsent(failure), messages.size());

        throw rethrowable(failure, messages);
    }

    /**
     * Renders the broker's approximate redelivery count for one message, or a stable token when absent.
     *
     * <p>Assumptions: the value is rendered rather than parsed. It reaches a log line and nothing else, so
     * converting it to a number would add a parse that can fail on a failure path and buy nothing. It is
     * also safe to record unmasked: the broker computes it, so no requester can place a value in it.</p>
     *
     * <p>Purpose: this is the field that tells a reader how close a message is to its redrive threshold,
     * which is the difference between a transient failure and one about to reach the dead-letter queue.</p>
     *
     * @param message the message whose redelivery count is wanted; must not be {@code null}
     * @return the count as the broker reported it, or {@link #UNKNOWN_RECEIVE_COUNT}; never {@code null}
     */
    private static String receiveCountOf(Message<?> message) {
        Object count = message.getHeaders().get(RECEIVE_COUNT_HEADER);
        return count == null ? UNKNOWN_RECEIVE_COUNT : String.valueOf(count);
    }

    /**
     * Renders the broker's identifier for one message, or a stable token when it carries none.
     *
     * @param message the message to read the identifier from; must not be {@code null}
     * @return the identifier as text, or {@link #NO_IDENTIFIER} when the header is absent, never
     *     {@code null}
     */
    private static String identifierOf(Message<?> message) {
        // WHY : Refactoring Rationale: the BROKER's own message-identifier attribute is preferred, and the
        //   framework's standard id header is the fallback rather than the only source. The two agree in a
        //   deployment -- the starter's header mapper populates the standard id from the queue's message id
        //   -- but they agree by that mapper's behaviour and not by anything this class can see. Reading
        //   the broker's attribute first makes the field name what it claims to be under any mapper, and it
        //   is also the value an operator pastes into a queue or dead-letter search.
        Object transportIdentifier = message.getHeaders().get(TRANSPORT_IDENTIFIER_HEADER);
        if (transportIdentifier != null) {
            return String.valueOf(transportIdentifier);
        }
        // WHY : Assumptions: the header is read as an Object and rendered with String.valueOf rather
        //   than read as a UUID. The starter's own helper casts this header to UUID, so a converter that
        //   ever put a String there would make the helper throw ClassCastException from inside a
        //   FAILURE path -- replacing the failure being reported with a second one. Rendering
        //   defensively cannot do that.
        Object identifier = message.getHeaders().get(MessageHeaders.ID);
        return identifier == null ? NO_IDENTIFIER : String.valueOf(identifier);
    }

    /**
     * Renders the queue a message arrived on, or a stable token when the header is absent.
     *
     * @param message the message to read the queue name from; must not be {@code null}
     * @return the queue name, or {@link #UNKNOWN_QUEUE} when the header is absent, never {@code null}
     */
    private static String queueOf(Message<?> message) {
        Object queue = message.getHeaders().get(QUEUE_NAME_HEADER);
        return queue == null ? UNKNOWN_QUEUE : String.valueOf(queue);
    }

    /**
     * Converts a failure into something a method with no {@code throws} clause can throw.
     *
     * <p>Assumptions: an unchecked failure is returned as the same instance rather than wrapped. That is
     * what makes the rethrow indistinguishable from having registered no handler at all: the starter
     * wraps only what is not already a processing exception, so returning the original leaves the
     * throwable that reaches the acknowledgement stage identical either way.</p>
     *
     * <p>Alternatives Considered: an unchecked cast that rethrows a checked exception without declaring
     * it -- the "sneaky throw" idiom. Rejected because it puts a checked exception on a path no caller
     * declared, and a handler in a failure path is the worst place to hide a surprise. Wrapping is
     * visible in the digest as one extra link in the chain of types.</p>
     *
     * @param failure what the listener threw; must not be {@code null}
     * @param message the message being handled, attached to the wrapper so the starter can unwrap it;
     *     must not be {@code null}
     * @return the throwable to rethrow, never {@code null}
     * @throws Error immediately, when {@code failure} is an {@link Error}; an error is not returned for
     *     the caller to throw because wrapping or delaying it would change what the virtual machine is
     *     reporting
     */
    private static RuntimeException rethrowable(Throwable failure, Message<?> message) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException unchecked) {
            return unchecked;
        }
        return new ListenerExecutionFailedException(CHECKED_FAILURE_WRAPPER_MESSAGE, failure, message);
    }

    /**
     * Converts a batch failure into something a method with no {@code throws} clause can throw.
     *
     * @param failure what the listener threw; must not be {@code null}
     * @param messages the batch being handled, attached to the wrapper so the starter can unwrap it;
     *     must not be {@code null}
     * @param <T> the payload type of the batch, carried only so the starter's collection-taking
     *     constructor can be called without an unchecked conversion
     * @return the throwable to rethrow, never {@code null}
     * @throws Error immediately, when {@code failure} is an {@link Error}, for the reason given on the
     *     single-message form of this method
     */
    private static <T> RuntimeException rethrowable(Throwable failure,
            Collection<Message<T>> messages) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException unchecked) {
            return unchecked;
        }
        return new ListenerExecutionFailedException(CHECKED_FAILURE_WRAPPER_MESSAGE, failure, messages);
    }
}
