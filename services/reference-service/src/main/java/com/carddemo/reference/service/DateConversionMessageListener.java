package com.carddemo.reference.service;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Answers the date and time inquiry the reference program serves over its queue pair.
 *
 * <p>Purpose: this class carries the asynchronous half of the flow that
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} implements, and the shared date evaluation that the
 * synchronous endpoint of this context also calls. It receives a one-thousand-byte positional
 * request, publishes a one-thousand-byte positional reply carrying the current system date and time,
 * and routes a failed exchange to the configured error sink. That program is REFERENCE-ONLY: it is
 * read here as the specification for this class and is never modified.</p>
 *
 * <p>The reference program's {@code 1000-CONTROL} opens three queues and loops until its request
 * queue is empty. The loop, the queue handles and the bounded receive wait are all listener-container
 * concerns in the target, so this class holds the body of one iteration only: its
 * {@code 3000-GET-REQUEST} at physical line 283, its {@code 4000-PROCESS-REQUEST-REPLY} at physical
 * line 339, its {@code 4100-PUT-REPLY} at physical line 366 and its {@code 9000-ERROR} at physical
 * line 405.</p>
 *
 * <h2>The reference program never reads the request, and that governs everything below</h2>
 *
 * <p>Assumptions: the request content is NOT interpreted. {@code WS-FUNC} and {@code WS-KEY} are
 * declared at physical lines 110 and 111 and those two declaration lines are their only occurrences
 * in all 524 lines of the program: no branch tests either one, and {@code REQUEST-MSG-COPY} is
 * populated at physical line 322 and then never consulted. The program answers ANY message on its
 * request queue with the current system date and time, so the two fields are vestigial -- all four
 * lines of that group carry the same source sequence number, which is the mark of a layout
 * retrofitted onto a program that was never wired to read it. This class therefore decodes the
 * payload for diagnostic reporting and geometry validation only, and applies no guard. Any
 * request-driven conversion added here in future would be an extension BEYOND the baseline and would
 * have to be documented as one; it is emphatically not the baseline's unstated intent, and reading it
 * that way would invent a conversion engine the reference has no trace of.</p>
 *
 * <p>Assumptions: handling is stateless, and that reproduces the baseline's own contract rather than
 * imposing a migration preference on it. Physical line 2 declares
 * {@code PROGRAM-ID. CODATE01 IS INITIAL}, which gives the program a fresh working storage on every
 * invocation, and the {@code LINKAGE SECTION.} at physical line 123 is empty, so nothing is passed in
 * and nothing survives between invocations. A stateless handler is the faithful shape here, not a
 * liberty taken with one.</p>
 *
 * <h2>Delivery discipline: there is no lost-reply window, so there is no outbox</h2>
 *
 * <p>Assumptions: BOTH ends of the exchange are syncpointed, and the pair is the grounding for this
 * decision rather than either line alone. Physical lines 296 to 299 compute the get options as
 * {@code MQGMO-SYNCPOINT} and physical lines 379 to 381 compute the put options as
 * {@code MQPMO-SYNCPOINT}, so the request read and the reply write sit in one unit of work and there
 * is no window in which work is committed while the reply is lost. No transactional outbox is used
 * here for exactly that reason. The contrast is with {@code COPAUA0C}, which publishes its reply
 * outside its syncpoint and therefore does have such a window; the outbox belongs to
 * authorization-service, where a window exists to close, and adding one here would be machinery
 * guarding against a failure mode this flow does not have.</p>
 *
 * <p>Assumptions: the target equivalent of that single unit of work is the queue's own visibility
 * timeout plus delete-on-success. This method returns normally only after the reply has been
 * published, so the framework deletes the request only once it has been answered; a failure leaves
 * the request to reappear after its visibility timeout, and repeated failure carries it to the
 * dead-letter queue at the provisioned receive count. No database transaction is declared, because
 * this flow reads no row and writes none -- a transaction would take a pooled connection and hold it
 * across a queue send in order to protect nothing.</p>
 *
 * <h2>Correlation, and one point of genuine alignment</h2>
 *
 * <p>Assumptions: correlation is inherited from the request and echoed on the reply, never used to
 * select a message. Physical lines 292 and 293 move {@code MQMI-NONE} and {@code MQCI-NONE} into the
 * message descriptor before the receive at physical line 301, which is the queue idiom for match any
 * message. The target transport has no selective receive either, so this is a point of genuine
 * alignment between the two rather than a semantic gap that has to be bridged -- worth stating
 * plainly, because most of the messaging mapping in this migration is the other kind.</p>
 *
 * <p>Assumptions: BOTH identifiers are echoed, not one. Physical lines 373 and 374 restore the saved
 * message identifier and the saved correlation identifier onto the descriptor before the reply is
 * published, so a requester pairs a reply with its request on those values. A caveat on widths: the
 * twenty-four-character width of the saved pair is verifiable, because the program declares it in its
 * own working storage at physical lines 52 to 56, whereas the width of the descriptor fields
 * themselves is NOT verifiable from this repository, because they are declared in an absent copybook.
 * No width is asserted for them here, and in particular none is transferred from the
 * forty-eight-character queue-name fields, which are a different field entirely.</p>
 *
 * <p>Assumptions: the six copybooks the program includes are the only ones it includes -- the queue
 * interface books at physical lines 71, 75, 79, 83, 87 and 90 -- and a repository-wide search returns
 * no file for any of the six. They are not read here and no attempt is made to read them. That
 * absence is the specific reason certain descriptor field widths are stated as unverifiable above
 * rather than quoted.</p>
 *
 * <h2>Two date masks coexist in this context and they are different contracts</h2>
 *
 * <p>Assumptions: this reply renders the date month-first with hyphen separators and the time with
 * colon separators, because that is what the platform call at physical lines 347 to 353 produces: the
 * month-day-year request at physical line 349 with the separator supplied at physical line 350, and
 * the time request at physical line 351 with a bare separator keyword at physical line 352 that
 * leaves the platform default colon in place. The date-edit path reached through
 * {@link DateEditValidator} standardises instead on the ten-character year-first mask, which has two
 * independent supports in the sources: {@code app/cbl/CSUTLDTC.cbl} always moves the DECLARED width
 * of its date parameter at its physical lines 105 and 106, never a trimmed content width, and that
 * declared width is always ten; and {@code COTRTUPC.cbl} lays out its own edit field at its physical
 * lines 107 to 114 as a four-character year, a separator, a two-character month, a separator and a
 * two-character day, which is ten.</p>
 *
 * <p>Trade-offs: the two masks are carried side by side and neither is standardised onto the other.
 * Collapsing them into one would be tidier to read and would leave this context with a single date
 * shape, but the reply here is a wire format being preserved for existing consumers that locate its
 * values by offset, while the year-first mask is the interface the synchronous evaluation publishes.
 * Rewriting either to match the other would change an observable contract in order to reduce an
 * inconsistency that the sources themselves contain deliberately.</p>
 *
 * <h2>Queue naming and routing</h2>
 *
 * <p>Refactoring Rationale: re-expressing the baseline's queue-naming mechanism is forced rather than
 * chosen. The reference program names its queues in the dotted upper-case form, and the target
 * transport admits no period in a queue name except as the suffix that marks an ordered queue, so the
 * names cannot survive as they stand. The replacements are standard queues and deliberately not
 * ordered ones, because a date inquiry has no ordering requirement -- two replies are independent and
 * neither depends on the other having been delivered first. Ordering with a per-card group belongs to
 * the authorization flow, which has a per-card sequence to preserve. Each replacement queue carries a
 * dead-letter queue at a receive count of five and encryption at rest with a customer-managed key,
 * which is what makes the redelivery tier described above terminate somewhere useful.</p>
 *
 * <p>Assumptions: every queue name reaches this class from configuration and none is a literal in
 * it. That follows the baseline rather than departing from it: the program takes its INPUT queue name
 * from the trigger message at physical line 146 rather than from a literal, and only its reply and
 * error names are written into the source. Those two are {@code CARD.DEMO.REPLY.DATE} at physical
 * line 147 and {@code CARD.DEMO.ERROR} at physical line 243, and they appear in this file only here,
 * as provenance for the two configured values -- never as a name this class sends to. The
 * replacements they map to are a standard reply queue and a standard error sink, each named per
 * environment and each supplied to this service as an environment value.</p>
 *
 * <p>Assumptions: the reply is addressed to the queue the request names when it names one, and to the
 * configured queue otherwise. The reference program specifies its reply destination twice: physical
 * lines 146 and 147 sit inside the branch taken when the trigger retrieve succeeds and establish the
 * destination as a default up front, while the per-message reply-to queue is read afterwards, at
 * physical line 315, and saved at physical line 320. Preferring the per-message value with the
 * configured queue as the fallback follows that ordering. Stated exactly, so the divergence is not
 * dressed up as fidelity: the baseline's put at physical line 383 targets the handle opened from the
 * configured name, so the value it saves at physical line 320 is never acted on; the Java acts on it
 * when it is present, and that divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}. AAP Rule T9 (structure changes,
 * behaviour does not) is what makes the recording obligatory rather than optional: this is the one
 * behavioural difference in the flow, so it is registered as such instead of being presented as
 * fidelity.</p>
 *
 * <p>Trade-offs: honouring a destination named by the sender means a sender influences where this
 * service's output goes, which the alternative -- always using the configured queue -- forecloses.
 * The value is accepted only when it is an absolute secure URL, the send is authorized by the task
 * role rather than by this code, and a destination the role may not send to fails at the send rather
 * than leaking anything; an operator who wants the single-destination behaviour simply leaves the
 * attribute unset on the request. The exchange gains the ability to serve more than one requester
 * without a code change, and pays for it with that narrowed but real dependence on the sender.</p>
 *
 * <h2>The receive wait, and the unit it is stated in</h2>
 *
 * <p>Assumptions: the receive wait is FIVE SECONDS, and the unit is spelled out here because the
 * source states it as five thousand MILLISECONDS at physical line 286 and that figure is easy to read
 * as seconds. This service's own configuration declares a matching five-second listener poll timeout,
 * so the two agree rather than merely resembling each other, and nothing in this class restates the
 * number. The unit is worth naming at all because a sibling program in the same extension family
 * expresses the SAME five seconds two different ways without comment: its physical line 242 sets five
 * thousand milliseconds while its physical line 750 sets fifty tenths of a second, a hundredfold
 * apart on the page and identical in effect. A reader who assumed one convention held throughout
 * would misread one of the two by a factor of a hundred.</p>
 *
 * <h2>Two verbatim diagnostics are documented rather than emitted</h2>
 *
 * <p>Assumptions: the program's trigger-retrieve failure path reports {@code 'CICS RETRIEVE'} at
 * physical line 149 and builds a response-code text from {@code 'RESP: '} and {@code 'END'} at
 * physical lines 152 and 153. The mechanism those three describe is the retrieve of the trigger
 * message at physical lines 140 to 144, which is how the program learns which queue started it. That
 * mechanism has no counterpart here: the listener container is given its queue by configuration and
 * there is no trigger message to retrieve, so there is no failure of that kind to report. The three
 * literals are therefore recorded here rather than emitted, because emitting them would create a
 * branch that can never be taken. The one diagnostic that DOES have a counterpart is the put failure,
 * and it is carried verbatim as a member of this class. Recording the difference is what AAP Rule T8
 * (user-visible strings are verbatim) requires of a string whose mechanism is retired, and the
 * retirement is registered in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <h2>Documentation and validation obligations this class is written against</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) governs this file, and its validation gate
 * is conjunctive -- a member missing either its docstring or the reason for a non-obvious choice fails
 * review, not one or the other. Every member below therefore carries both, and the four labels used
 * throughout are the four that rule names.</p>
 *
 * <p>Trade-offs: this class holds no monetary value and performs no decimal arithmetic, so the
 * exact-fixed-point discipline the migration applies elsewhere has nothing to bite on here. Where it
 * would, the mechanical gate could not enforce it: the architecture rule that forbids approximating a
 * decimal amount in binary scopes its subject set to the shared money package alone, so in this
 * package the prohibition rests on review rather than on the build. Stating that is more useful than
 * implying a gate that does not reach this code.</p>
 *
 * <p>Trade-offs: parity for this class cannot be established by byte comparison. The repository's
 * golden-master oracle covers the batch flows only, and this program lives in the queue extension
 * tree with no golden output of its own, so the evidence here is the transcribed logic together with
 * the payload layout and the message-descriptor mapping -- not a compared byte stream. Saying so is
 * the honest position; claiming oracle-backed parity for this flow would not be.</p>
 */
@Service
public class DateConversionMessageListener {

    /**
     * The invariant width of both the request and the reply buffer, in characters.
     *
     * <p>Assumptions: the program declares four separate one-thousand-character buffers at physical
     * lines 105 to 108 and sets a literal buffer length of one thousand at physical lines 291, 372 and
     * 411, so a consumer reads exactly this many characters however few of them the content
     * occupies.</p>
     */
    public static final int BUFFER_LENGTH = 1000;

    /**
     * The verbatim label preceding the date value, including the space that ends it.
     *
     * <p>Assumptions: reproduced character for character from physical line 355, where the program
     * concatenates it with its formatted date, as AAP Rule T8 (user-visible strings are verbatim)
     * requires. The colon carries a space on BOTH sides, and the label is fourteen characters; a label
     * one character shorter would shift every value behind it, because a consumer of a positional
     * buffer locates values by offset.</p>
     */
    public static final String DATE_PREFIX = "SYSTEM DATE : ";

    /**
     * The verbatim label preceding the time value, including the space that ends it.
     *
     * <p>Assumptions: reproduced character for character from physical line 356, and fourteen
     * characters like its companion. There is NO separator between the date value and this label: the
     * program's concatenation places them adjacent, so the reply content runs the two labelled values
     * together.</p>
     */
    public static final String TIME_PREFIX = "SYSTEM TIME : ";

    /**
     * The number of characters of the reply buffer the content occupies.
     *
     * <p>Assumptions: fourteen for the date label, ten for the date, fourteen for the time label and
     * eight for the time. The date width is the field declared at physical line 37 and the time width
     * is the field declared at physical line 38; the remainder of the buffer is blank.</p>
     */
    public static final int REPLY_CONTENT_LENGTH = 46;

    /**
     * The message attribute carrying the requester's message identifier.
     *
     * <p>Assumptions: the migration maps the baseline descriptor's message identifier onto an
     * attribute of this name, which is what this class echoes at physical line 373's equivalent.</p>
     */
    public static final String ATTRIBUTE_MESSAGE_ID = "messageId";

    /**
     * The message attribute carrying the requester's correlation identifier.
     *
     * <p>Assumptions: the migration maps the baseline descriptor's correlation identifier onto an
     * attribute of this name, echoed alongside the message identifier rather than instead of it.</p>
     */
    public static final String ATTRIBUTE_CORRELATION_ID = "correlationId";

    /**
     * The message attribute naming the queue a reply should be addressed to.
     *
     * <p>Assumptions: the migration maps the baseline descriptor's reply-to queue onto an attribute of
     * this name, carrying a queue URL rather than a bare name because the target send addresses a
     * URL.</p>
     */
    public static final String ATTRIBUTE_REPLY_TO_QUEUE_URL = "replyToQueueUrl";

    /**
     * The message attribute declaring the media type of a published payload.
     *
     * <p>Assumptions: the program sets a string format indicator on every put, at physical line 375,
     * and the migration maps that indicator onto an attribute of this name.</p>
     */
    public static final String ATTRIBUTE_CONTENT_TYPE = "contentType";

    /**
     * The media type published on the reply and on the error report.
     *
     * <p>Assumptions: the migration fixes this value for the string format indicator across every
     * queue payload it maps, so it is carried as that mapping states rather than chosen here. Worth a
     * word because this particular payload is positional rather than delimited: the media type records
     * that the payload is text and must not be parsed as a structured document, and the positional
     * layout itself is published on the asynchronous reply schema of this service's contract rather
     * than implied by the media type.</p>
     */
    public static final String CONTENT_TYPE = "text/csv";

    /**
     * The verbatim diagnostic the program reports when a put fails.
     *
     * <p>Assumptions: reproduced character for character from physical lines 400 and 437, where the
     * program moves it into its return-message field on the reply path and on the error path
     * alike.</p>
     */
    public static final String DIAGNOSTIC_PUT_FAILED = "MQPUT ERR";

    /**
     * The paragraph this class transcribes when it publishes a reply.
     *
     * <p>Assumptions: reported in the error diagnostic so a reader of the error sink can locate the
     * reference paragraph the failure corresponds to, at physical line 366.</p>
     */
    public static final String PARAGRAPH_PUT_REPLY = "4100-PUT-REPLY";

    /**
     * The paragraph this class transcribes when it takes a request off the queue.
     *
     * <p>Assumptions: reported for the same reason as its companion, and corresponds to physical line
     * 283.</p>
     */
    public static final String PARAGRAPH_GET_REQUEST = "3000-GET-REQUEST";

    /** The logger this consumer reports through. */
    private static final Logger LOG =
            LoggerFactory.getLogger(DateConversionMessageListener.class);

    /**
     * The declared name of the request group, used as the layout's descriptor name.
     *
     * <p>Assumptions: the group is declared at physical line 109 and the name is carried across so a
     * decode failure names the reference structure rather than an invented one.</p>
     */
    private static final String REQUEST_LAYOUT_NAME = "REQUEST-MSG-COPY";

    /** The field name of the function code, from physical line 110. */
    private static final String FIELD_FUNCTION_CODE = "WS-FUNC";

    /** The field name of the key, from physical line 111. */
    private static final String FIELD_KEY = "WS-KEY";

    /** The field name of the trailing padding, from physical line 112. */
    private static final String FIELD_FILLER = "WS-FILLER";

    /** The zero-based offset of the key interval within the request buffer. */
    private static final int KEY_OFFSET = 4;

    /** The declared width of the key interval, in characters. */
    private static final int KEY_LENGTH = 11;

    /**
     * The layout of the one-thousand-byte request buffer.
     *
     * <p>Assumptions: this descriptor is built HERE rather than taken from the shared registry,
     * because the registry holds the eleven persisted master layouts and has no entry for a queue
     * payload, and the shared kernel may not reference this service's packages in order to gain one.
     * Its three fields, their offsets and their widths come from physical lines 110 to 112, which sum
     * to exactly one thousand, and the eleven-character key at offset four is the only identifier the
     * record declares. Declaring that identifier rather than declaring the record keyless keeps this
     * descriptor identical to the one this service's fixture test decodes the same bytes through, so
     * the two cannot drift apart and disagree about the same file.</p>
     */
    private static final CopybookLayout.RecordSpec REQUEST_LAYOUT =
            new CopybookLayout.RecordSpec(REQUEST_LAYOUT_NAME, BUFFER_LENGTH, KEY_LENGTH, KEY_OFFSET,
                    List.of(
                            CopybookLayout.text(FIELD_FUNCTION_CODE, 0, KEY_OFFSET),
                            CopybookLayout.uint(FIELD_KEY, KEY_OFFSET, KEY_LENGTH),
                            CopybookLayout.text(FIELD_FILLER, KEY_OFFSET + KEY_LENGTH, 985)))
                    .validateGeometry();

    /**
     * The rendering of the date value in the reply.
     *
     * <p>Assumptions: month, day then year, hyphen-separated, which is what the platform call at
     * physical lines 349 and 350 produces. It is deliberately NOT the year-first mask the synchronous
     * evaluation admits, and the difference between the two is a preserved contract rather than an
     * oversight.</p>
     */
    private static final DateTimeFormatter REPLY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd-yyyy");

    /**
     * The rendering of the time value in the reply.
     *
     * <p>Assumptions: colon-separated, which is the platform default that the bare separator keyword
     * at physical line 352 leaves in place. The conclusion rests on that keyword carrying no argument,
     * not on an assumption about the platform.</p>
     */
    private static final DateTimeFormatter REPLY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The width of the paragraph field in the error diagnostic, from physical line 59. */
    private static final int DIAGNOSTIC_PARAGRAPH_WIDTH = 25;

    /** The width of the return-message field in the error diagnostic, from physical line 61. */
    private static final int DIAGNOSTIC_RETURN_MESSAGE_WIDTH = 25;

    /** The width of the condition-code field in the error diagnostic, from physical line 63. */
    private static final int DIAGNOSTIC_CONDITION_CODE_WIDTH = 2;

    /** The width of the reason-code field in the error diagnostic, from physical line 65. */
    private static final int DIAGNOSTIC_REASON_CODE_WIDTH = 5;

    /** The width of the queue-name field in the error diagnostic, from physical line 67. */
    private static final int DIAGNOSTIC_QUEUE_NAME_WIDTH = 48;

    /** The two-character gap the error diagnostic carries between its fields. */
    private static final String DIAGNOSTIC_GAP = "  ";

    /** The queue client replies and error reports are published with. */
    private final SqsClient sqs;

    /** The configured queue name a reply falls back to when the request names none. */
    private final String replyQueue;

    /** The configured queue name error reports are published to. */
    private final String errorQueue;

    /** The clock the reply's date and time are read from. */
    private final Clock clock;

    /**
     * The resolved URL of each queue name this class has addressed.
     *
     * <p>Assumptions: a name is resolved to a URL once and the result is retained, which is the target
     * equivalent of the baseline holding an open handle. The program opens each of its three queues
     * once, at physical lines 182, 216 and 251, and closes them at physical lines 461, 483 and 506,
     * rather than reopening per message; retaining the resolution keeps that shape instead of issuing
     * a lookup on every exchange. The map is concurrent because the listener container may run several
     * handlers at once.</p>
     */
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    /**
     * Builds the consumer over the queue client, the configured queue names and the clock.
     *
     * <p>Assumptions: the clock is a collaborator rather than a static read, because the reply's
     * entire content IS the current date and time and a test that asserts the buffer has to be able to
     * fix it. The baseline injects its business date wherever a rerun has to reproduce an earlier one,
     * for the same reason.</p>
     *
     * <p>Alternatives Considered: declaring a configuration class here to wire the queue client and
     * the listener container. Rejected on two independent grounds: the container is auto-configured
     * from the queue properties this service already declares, including its bounded receive wait, so
     * a second declaration could disagree with the first; and the configuration package of this
     * service is a sibling this package does not own. The client arrives as a bean and the queue names
     * arrive as configured values, so this class declares nothing.</p>
     *
     * @param sqs the queue client used to publish, as an {@code SqsClient} supplied as a bean; must
     *     not be {@code null}
     * @param replyQueue the configured reply queue name, as a {@code String} resolved from the
     *     environment; must not be {@code null} or blank
     * @param errorQueue the configured error queue name, as a {@code String} resolved from the
     *     environment; must not be {@code null} or blank
     * @param clock the clock the reply's date and time are read from, as a {@code Clock}; must not be
     *     {@code null}
     * @throws NullPointerException if {@code sqs} or {@code clock} is {@code null}, or if either queue
     *     name is {@code null}
     * @throws IllegalArgumentException if either queue name is blank, because a blank name would be
     *     accepted at startup and would fail only when the first request arrived
     */
    public DateConversionMessageListener(
            SqsClient sqs,
            @Value("${carddemo.reference.inquiry.reply-queue}") String replyQueue,
            @Value("${carddemo.reference.inquiry.error-queue}") String errorQueue,
            Clock clock) {

        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.replyQueue = requireQueueName(replyQueue, "carddemo.reference.inquiry.reply-queue");
        this.errorQueue = requireQueueName(errorQueue, "carddemo.reference.inquiry.error-queue");
    }

    /**
     * Answers one request taken from the inquiry queue.
     *
     * <p>Purpose: this is the migrated body of one iteration of the reference program's loop. The
     * request buffer is decoded for reporting, the reply buffer is composed from the current date and
     * time, and the reply is published to the resolved destination carrying both inherited
     * identifiers. A failure publishes an error report and then propagates, which leaves the request
     * undeleted so the transport redelivers it.</p>
     *
     * <p>Assumptions: the decoded content does not influence the answer or its routing, for the reason
     * given on this class. A request carrying a function code nothing recognises still receives the
     * standard reply, because the reference answers every message on its queue.</p>
     *
     * <p>Trade-offs: a failed reply publish is fatal to the invocation, matching the baseline, which
     * writes its error report and then terminates at physical lines 401 and 402 rather than retrying
     * in place. The exception is therefore rethrown after the error report rather than absorbed, and
     * the retry tier is the transport's own: the request becomes visible again after its visibility
     * timeout and repeated failure carries it to the dead-letter queue. An in-process retry loop was
     * the alternative; it would hold the handler open across repeated failures of the same send and
     * duplicate, less observably, a redelivery mechanism the queue already provides.</p>
     *
     * @param payload the received request buffer, as a {@code String} that the wire contract declares
     *     to be exactly {@link #BUFFER_LENGTH} characters
     * @param messageId the requester's message identifier, as a {@code String}, or {@code null} when
     *     the request carried none
     * @param correlationId the requester's correlation identifier, as a {@code String}, or
     *     {@code null} when the request carried none
     * @param replyToQueueUrl the queue URL the requester asks to be answered on, as a {@code String},
     *     or {@code null} when the request named none
     * @throws FixedWidthCodec.RecordLengthException if the payload is not exactly
     *     {@link #BUFFER_LENGTH} characters, which the codec reports before reading any field
     * @throws FixedWidthCodec.FieldCodecException if the key interval holds content that is neither
     *     blank nor decimal digits
     * @throws CopybookLayout.LayoutException if the request layout is rejected as ill-formed
     * @throws NullPointerException if {@code payload} is {@code null}
     */
    @SqsListener(queueNames = "${carddemo.reference.inquiry.request-queue}")
    public void onDateConversionRequest(
            @Payload String payload,
            @Header(name = ATTRIBUTE_MESSAGE_ID, required = false) String messageId,
            @Header(name = ATTRIBUTE_CORRELATION_ID, required = false) String correlationId,
            @Header(name = ATTRIBUTE_REPLY_TO_QUEUE_URL, required = false) String replyToQueueUrl) {

        try {
            DecodedRequest request = decodeRequest(payload);

            // Assumptions: the decoded fields are reported and go no further. They are recorded at all
            //     so that an operator can see what arrived and so that a later consumer of the layout
            //     does not have to rebuild it, which is the only role the reference gives them.
            LOG.debug("answering a date inquiry carrying function code [{}] and key [{}]",
                    sanitisedForLog(request.functionCode()), request.key());

            publishReply(resolveReplyQueueUrl(replyToQueueUrl), buildReplyPayload(), messageId,
                    correlationId);
        } catch (RuntimeException failure) {
            // Assumptions: the error report is published before the failure propagates, in that order,
            //     because the baseline performs its error paragraph and only then terminates. Reversing
            //     the order would lose the report whenever the propagation itself was what ended the
            //     invocation.
            reportFailure(failure, messageId, correlationId);
            throw failure;
        }
    }

    /**
     * Reports the verdict on one candidate date against one mask.
     *
     * <p>Purpose: this is the shared evaluation the synchronous endpoint of this context calls, kept
     * on this class so the two routes into the flow cannot report different verdicts for one input.</p>
     *
     * <p>Assumptions: this member does NOT convert anything, and the naming of the surrounding shapes
     * should not be read as implying that it does. The reference program answers with the system date
     * and reads no field of its request, so there is no baseline conversion to expose; what exists in
     * the sources is a date EDIT, and this member reports its verdict. A request-driven conversion
     * would be an extension beyond the baseline and would have to be documented as one.</p>
     *
     * <p>Refactoring Rationale: every date-edit rule is delegated and none is restated here, which is
     * AAP Rule T2 (one {@code COPY} becomes one import) applied to a callable rather than to a record
     * layout, and re-expresses the baseline's own mechanism rather than reorganising it. The utility at
     * {@code app/cbl/CSUTLDTC.cbl} was ITSELF a shared callable subprogram: its procedure division at
     * physical line 88 takes the date, the mask and a result, its date and mask parameters are each ten
     * characters so the mask is a PARAMETER rather than a constant, its result is eighty characters,
     * and it hands control back at physical line 100. One shared implementation is therefore the
     * faithful shape and not a liberty. The rules live in the shared kernel rather than the platform
     * interface they were expressed through, because that interface has no counterpart available to
     * reproduce; what is carried across is the rules, not the call.</p>
     *
     * <p>Assumptions: one naming trap in that utility is worth recording so nobody reproduces it. Its
     * physical line 62 declares an all-zero feedback token under a name that reads as though it meant
     * an invalid date, and its physical lines 129 and 130 select that condition to report the date
     * VALID -- the name means the opposite of what it says, and a tenth branch at its physical lines
     * 147 and 148 reports the genuinely invalid case. The shared validator carries the rules, not the
     * misleading name.</p>
     *
     * @param request the candidate date and the optional mask, as a {@link DateConversionRequest};
     *     must not be {@code null}
     * @return the verdict as a {@link DateConversionResponse}, carrying the named feedback code, the
     *     numeric severity and message number the utility produces, the verdict text, and the
     *     submitted date and applied mask echoed back
     * @throws NullPointerException if {@code request} is {@code null}, or if its date is {@code null}
     * @throws IllegalArgumentException if the date is not the width the applied mask declares, which
     *     the validator reports because it reads the date's components by offset
     */
    public DateConversionResponse convert(DateConversionRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // Assumptions: the mask defaults here rather than on the request shape, and a supplied but
        //     empty mask defaults with an absent one. Defaulting on the shape would make an omitted
        //     mask indistinguishable from one a caller stated explicitly, and the reply echoes the mask
        //     actually APPLIED so a caller can see which of the two it received.
        String mask = request.mask() == null || request.mask().isBlank()
                ? DateEditValidator.DATE_FORMAT_MASK
                : request.mask();

        DateEditValidator.LanguageEnvironmentResult result =
                DateEditValidator.evaluateWithLanguageEnvironment(request.date(), mask);

        // Assumptions: the named code and the two numbers are all carried across. The numbers are what
        //     the reference utility actually returns and the only values reconcilable against it,
        //     while the name is what a caller branches on without embedding a numeric table. Carrying
        //     one without the other would either oblige every caller to hold that table or make the
        //     reply impossible to reconcile against the utility it transcribes.
        return new DateConversionResponse(
                result.feedbackCode().name(),
                result.severity(),
                result.messageNumber(),
                result.verdict(),
                result.date(),
                result.mask());
    }

    /**
     * Decodes the request buffer into the two fields the reference declares.
     *
     * <p>Purpose: transcribes the part of {@code 3000-GET-REQUEST} at physical line 283 that moves the
     * received buffer into the declared group, at physical line 322.</p>
     *
     * <p>Assumptions: a blank or absent key decodes as zero rather than as invalid, and that is the
     * one place this decode departs from what the shared codec would do unaided. The reference
     * initialises the group's numeric field to zeroes at physical line 294 before every receive, so
     * zero is the value it associates with a key it has not been given; the shared codec, written for
     * persisted master records where a blank numeric key IS corruption, rejects a space as a non-digit.
     * The key interval is therefore filled with zeroes when it is wholly blank, before the codec sees
     * it. Rejecting a blank key was the obvious alternative and is wrong here for a second, stronger
     * reason than the initialise: the reference answers EVERY message on its queue, so refusing one it
     * would have answered is a behavioural change however defensible the refusal looks in isolation.</p>
     *
     * <p>Assumptions: the trailing padding field is declared so the descriptor covers all one thousand
     * characters with no gap, and it is read back rather than dropped. The shared codec drops blank
     * padding only for layouts in its own registry, and this descriptor is deliberately not registered;
     * that difference is immaterial because the fields are read by name and the padding is simply not
     * one of the names read.</p>
     *
     * @param payload the received buffer, as a {@code String} the wire contract declares to be exactly
     *     {@link #BUFFER_LENGTH} characters
     * @return the decoded function code and key as a {@link DecodedRequest}, never {@code null}
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws FixedWidthCodec.RecordLengthException if the buffer is not exactly
     *     {@link #BUFFER_LENGTH} characters
     * @throws FixedWidthCodec.FieldCodecException if the key interval is neither blank nor decimal
     *     digits
     * @throws CopybookLayout.LayoutException if the descriptor is rejected as ill-formed
     */
    private DecodedRequest decodeRequest(String payload) {
        Objects.requireNonNull(payload, "payload must not be null");

        byte[] record = zeroFilledKeyInterval(payload.getBytes(StandardCharsets.US_ASCII));
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(record, REQUEST_LAYOUT);

        return new DecodedRequest(
                String.valueOf(fields.get(FIELD_FUNCTION_CODE)),
                ((Number) fields.get(FIELD_KEY)).longValue());
    }

    /**
     * Fills a wholly blank key interval with zeroes, leaving every other byte untouched.
     *
     * <p>Purpose: transcribes the initialise at physical line 294, which sets the group's numeric
     * field to zeroes before each receive.</p>
     *
     * <p>Assumptions: only a WHOLLY blank interval is substituted. Substituting each blank byte
     * individually was the alternative and is wrong: it would turn a key of three digits followed by
     * spaces into that value multiplied by a power of ten, silently reporting a different key rather
     * than an absent one. A partly filled interval is left exactly as it arrived, so the codec reports
     * it rather than this method guessing at it.</p>
     *
     * <p>Assumptions: the interval is only inspected when the record is long enough to hold it, so a
     * short buffer reaches the codec untouched and is reported as the length failure it is rather than
     * as an index failure raised here.</p>
     *
     * @param record the received buffer as a {@code byte} array, which may be any length
     * @return the same array when no substitution applies, or a copy carrying a zero-filled key
     *     interval, never {@code null}
     */
    private byte[] zeroFilledKeyInterval(byte[] record) {
        if (record.length < KEY_OFFSET + KEY_LENGTH) {
            return record;
        }

        for (int index = KEY_OFFSET; index < KEY_OFFSET + KEY_LENGTH; index++) {
            if (record[index] != ' ') {
                return record;
            }
        }

        byte[] initialised = record.clone();
        for (int index = KEY_OFFSET; index < KEY_OFFSET + KEY_LENGTH; index++) {
            initialised[index] = '0';
        }
        return initialised;
    }

    /**
     * Composes the reply buffer from the current date and time.
     *
     * <p>Purpose: transcribes {@code 4000-PROCESS-REQUEST-REPLY} at physical line 339, whose
     * concatenation at physical lines 355 and 356 produces the reply the requester reads.</p>
     *
     * <p>Assumptions: the two labelled values are concatenated with NO separator between the date
     * value and the second label, which is what that concatenation does. The content occupies
     * {@link #REPLY_CONTENT_LENGTH} characters and the remainder of the buffer is blank, because the
     * reply moves into a one-thousand-character field. A structured envelope may be offered additively
     * to new consumers, but never as a replacement: the program declares the payload's format as a
     * string at physical line 375, and with a string format the field order and the label text ARE the
     * contract, so an existing consumer locates values by offset and a reshaped payload breaks it
     * silently.</p>
     *
     * @return the reply buffer, exactly {@link #BUFFER_LENGTH} characters, never {@code null}
     */
    private String buildReplyPayload() {
        LocalDateTime now = LocalDateTime.now(this.clock);

        return frame(DATE_PREFIX + REPLY_DATE_FORMAT.format(now)
                + TIME_PREFIX + REPLY_TIME_FORMAT.format(now));
    }

    /**
     * Chooses the queue URL a reply is addressed to.
     *
     * <p>Purpose: resolves the destination that {@code 4100-PUT-REPLY} at physical line 366 reaches
     * through an opened handle, applying the preference this class documents: the value the request
     * names when it names a usable one, and the configured queue otherwise.</p>
     *
     * <p>Assumptions: a supplied destination is accepted only when it is an absolute secure URL, and
     * anything else falls back to the configured queue rather than failing. A malformed attribute is a
     * defect in a requester, not a reason to refuse a message the reference would have answered, and
     * falling back keeps the exchange answering while the substitution is visible in the log.</p>
     *
     * @param replyToQueueUrl the destination the request names, as a {@code String}, or {@code null}
     *     when it names none
     * @return the queue URL to publish the reply to, never {@code null}
     * @throws software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException if the configured
     *     fallback queue cannot be resolved, which is a deployment fault rather than a request fault
     */
    private String resolveReplyQueueUrl(String replyToQueueUrl) {
        if (replyToQueueUrl != null && replyToQueueUrl.startsWith("https://")) {
            return replyToQueueUrl;
        }

        if (replyToQueueUrl != null && !replyToQueueUrl.isBlank()) {
            LOG.warn("ignoring an unusable reply destination on a date inquiry and answering on the"
                    + " configured queue instead");
        }
        return queueUrl(this.replyQueue);
    }

    /**
     * Publishes the reply, echoing both identifiers the request carried.
     *
     * <p>Purpose: transcribes the put at physical line 383, together with the restoration of the saved
     * identifiers at physical lines 373 and 374 that immediately precedes it.</p>
     *
     * <p>Assumptions: BOTH identifiers are echoed when the request carried them, and an absent one is
     * simply not attached rather than being replaced by a value this class invents. A substituted
     * identifier would let a requester pair a reply against something it never sent, which is worse
     * than an unpaired reply because it cannot be detected downstream.</p>
     *
     * @param queueUrl the resolved destination, as a {@code String}; must not be {@code null}
     * @param payload the reply buffer, as a {@code String} of {@link #BUFFER_LENGTH} characters; must
     *     not be {@code null}
     * @param messageId the message identifier to echo, as a {@code String}, or {@code null} to attach
     *     none
     * @param correlationId the correlation identifier to echo, as a {@code String}, or {@code null} to
     *     attach none
     */
    private void publishReply(String queueUrl, String payload, String messageId,
            String correlationId) {

        this.sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(payload)
                .messageAttributes(replyAttributes(messageId, correlationId))
                .build());
    }

    /**
     * Publishes an error report describing a failed exchange.
     *
     * <p>Purpose: transcribes {@code 9000-ERROR} at physical line 405, whose put at physical line 420
     * addresses the terminal error sink.</p>
     *
     * <p>Trade-offs: the report carries the inherited identifiers, which the baseline's error paragraph
     * does not -- its physical lines 409 to 412 set the buffer, the length and the format indicator and
     * never touch the descriptor's identifier fields, so a report reaching that sink cannot be paired
     * with the request that caused it. The Java attaches them, and that divergence is recorded in
     * {@code docs/architecture/cobol-to-service-traceability.md}. It buys diagnosability at the sink
     * and touches nothing on the successful path, which is why it is accepted where a change to the
     * reply itself would not be.</p>
     *
     * <p>Assumptions: a failure to publish the report must not displace the failure that caused it. The
     * secondary failure is attached to the original and the original is the one that propagates, so an
     * unreachable error sink cannot disguise the fault an operator is actually looking for. The
     * baseline reaches the same outcome differently, reporting to its console and terminating at its
     * physical lines 438 and 439.</p>
     *
     * @param failure the failure to report, as a {@code RuntimeException}; must not be {@code null}
     * @param messageId the message identifier to echo, as a {@code String}, or {@code null} to attach
     *     none
     * @param correlationId the correlation identifier to echo, as a {@code String}, or {@code null} to
     *     attach none
     */
    private void reportFailure(RuntimeException failure, String messageId, String correlationId) {
        String paragraph = failure instanceof FixedWidthCodec.RecordLengthException
                || failure instanceof FixedWidthCodec.FieldCodecException
                ? PARAGRAPH_GET_REQUEST
                : PARAGRAPH_PUT_REPLY;

        LOG.error("routing a failed date inquiry to the error sink from {}", paragraph, failure);

        try {
            this.sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl(this.errorQueue))
                    .messageBody(errorDiagnostic(paragraph, DIAGNOSTIC_PUT_FAILED, this.errorQueue,
                            failure.toString()))
                    .messageAttributes(replyAttributes(messageId, correlationId))
                    .build());
        } catch (RuntimeException reportingFailure) {
            failure.addSuppressed(reportingFailure);
        }
    }

    /**
     * Composes the error buffer in the positional shape the reference reports errors through.
     *
     * <p>Assumptions: the field widths and the gaps between them are those of the diagnostic group
     * declared at physical lines 58 to 67, so a reader of the error sink can read this buffer at the
     * offsets that group establishes.</p>
     *
     * <p>Alternatives Considered: omitting the condition-code and reason-code intervals, which carry
     * queue-manager values that have no counterpart in the target and are therefore unfillable.
     * Rejected because omitting them shortens the prefix by eleven characters and shifts the queue name
     * behind them to an offset no existing reader expects. The intervals are preserved and left blank
     * instead, which keeps every other field where the reference puts it and states the absence in the
     * buffer itself.</p>
     *
     * <p>Trade-offs: the failure detail is appended AFTER the positional prefix, in space the reference
     * leaves blank. Truncating the detail into the twenty-five-character return-message field was the
     * alternative and would have discarded most of what makes a report worth reading; appending it
     * keeps the positional prefix byte-compatible and uses the remainder of a buffer that is one
     * thousand characters wide precisely because it is read as a whole.</p>
     *
     * @param paragraph the reference paragraph the failure corresponds to, as a {@code String}; must
     *     not be {@code null}
     * @param returnMessage the diagnostic text, as a {@code String}; must not be {@code null}
     * @param queueName the queue the failure concerns, as a {@code String}; must not be {@code null}
     * @param detail the failure detail appended after the positional prefix, as a {@code String}; must
     *     not be {@code null}
     * @return the error buffer, exactly {@link #BUFFER_LENGTH} characters, never {@code null}
     */
    private static String errorDiagnostic(String paragraph, String returnMessage, String queueName,
            String detail) {

        String prefix = pad(paragraph, DIAGNOSTIC_PARAGRAPH_WIDTH)
                + DIAGNOSTIC_GAP
                + pad(returnMessage, DIAGNOSTIC_RETURN_MESSAGE_WIDTH)
                + DIAGNOSTIC_GAP
                + " ".repeat(DIAGNOSTIC_CONDITION_CODE_WIDTH)
                + DIAGNOSTIC_GAP
                + " ".repeat(DIAGNOSTIC_REASON_CODE_WIDTH)
                + DIAGNOSTIC_GAP
                + pad(queueName, DIAGNOSTIC_QUEUE_NAME_WIDTH);

        return frame(prefix + DIAGNOSTIC_GAP + detail);
    }

    /**
     * Builds the attribute map carried on a published message.
     *
     * <p>Assumptions: the media type is always attached while each identifier is attached only when
     * the request supplied it, because the transport rejects an attribute whose value is absent and
     * because an identifier this class invented would be worse than none, as the publish member
     * records.</p>
     *
     * @param messageId the message identifier to attach, as a {@code String}, or {@code null} to
     *     attach none
     * @param correlationId the correlation identifier to attach, as a {@code String}, or {@code null}
     *     to attach none
     * @return the attributes to publish, never {@code null} and never containing a null value
     */
    private static Map<String, MessageAttributeValue> replyAttributes(String messageId,
            String correlationId) {

        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(CONTENT_TYPE));
        if (messageId != null && !messageId.isBlank()) {
            attributes.put(ATTRIBUTE_MESSAGE_ID, stringAttribute(messageId));
        }
        if (correlationId != null && !correlationId.isBlank()) {
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(correlationId));
        }
        return attributes;
    }

    /**
     * Wraps one text value as a message attribute.
     *
     * <p>Assumptions: the attribute is declared as text rather than as opaque bytes, because every
     * value this class attaches is an identifier or a media type that an operator reads directly. The
     * binary form would carry the same bytes while making them unreadable in the console and in a
     * dead-letter inspection, which is where these values are actually looked at.</p>
     *
     * @param value the value to carry, as a {@code String}; must not be {@code null}
     * @return the attribute, never {@code null}
     */
    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    /**
     * Resolves one queue name to its URL, retaining the result.
     *
     * <p>Assumptions: the resolution is retained for the reason the field it populates records -- the
     * baseline opens a queue once and holds the handle rather than reopening per message.</p>
     *
     * @param queueName the configured queue name, as a {@code String}; must not be {@code null}
     * @return the resolved queue URL, never {@code null}
     * @throws software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException if no queue of that
     *     name exists, which is a deployment fault: this service is configured to fail rather than to
     *     create a queue it did not provision
     */
    private String queueUrl(String queueName) {
        return this.queueUrls.computeIfAbsent(queueName, name -> this.sqs
                .getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build())
                .queueUrl());
    }

    /**
     * Pads or truncates one value to a declared field width.
     *
     * <p>Assumptions: truncation on the right and padding with spaces reproduce what a move into a
     * fixed-width character field does, which is how every field of the reference's diagnostic group
     * is filled.</p>
     *
     * @param value the value to lay out, as a {@code String}; must not be {@code null}
     * @param width the declared field width in characters, as an {@code int}; must be zero or greater
     * @return the value at exactly {@code width} characters, never {@code null}
     */
    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Lays one body out in a buffer of the declared wire width.
     *
     * <p>Assumptions: the buffer is exactly {@link #BUFFER_LENGTH} characters whatever the body's
     * length, because the reference sets that literal length on every put and a consumer reads that
     * many characters regardless of how much of them the content occupies.</p>
     *
     * @param body the content to lay out, as a {@code String}; must not be {@code null}
     * @return the buffer, exactly {@link #BUFFER_LENGTH} characters, never {@code null}
     */
    private static String frame(String body) {
        return pad(body, BUFFER_LENGTH);
    }

    /**
     * Renders one externally supplied value safe to place in a log record.
     *
     * <p>Assumptions: line terminators are replaced because the value reaches this service from a
     * requester and a log record must not be able to carry a terminator that would let one field
     * forge a second record. The substitution is visible rather than silent, so a reader can tell that
     * the value arrived that way.</p>
     *
     * @param value the value to render, as a {@code String}, or {@code null}
     * @return the value with line terminators replaced, or the text {@code null} when the value was
     *     absent, never {@code null}
     */
    private static String sanitisedForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\r', '?').replace('\n', '?');
    }

    /**
     * Accepts one configured queue name, refusing an absent or blank one.
     *
     * <p>Assumptions: a blank name is refused during construction rather than at the first request,
     * because the queue names arrive from the environment with no defaults and a blank one would
     * otherwise produce a context that starts and then fails on every message it is given.</p>
     *
     * @param value the configured value, as a {@code String}; must not be {@code null}
     * @param property the property the value came from, as a {@code String}, named in the failure so an
     *     operator does not have to guess which variable is unset
     * @return the accepted queue name, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    private static String requireQueueName(String value, String property) {
        Objects.requireNonNull(value, property + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
        return value;
    }

    /**
     * The two fields the reference declares on its request buffer.
     *
     * <p>Assumptions: these two fields are carried as a decoded shape purely so a diagnostic can
     * report what arrived. Neither influences the answer, for the reason this class records at length:
     * the reference declares them and never reads them.</p>
     *
     * <p>Alternatives Considered: modelling the function code as a named set of recognised values.
     * Rejected here because the reference recognises none, so a named set would assert a domain the
     * sources do not contain. A future extension that genuinely interpreted the code would need such a
     * type, and would need it with SEPARATE mappings per direction: the copybook that carries a
     * comparable pair of codes elsewhere in the migration gives the same two characters opposite
     * meanings on input and on output, so a raw character passed between the two directions would
     * invert the meaning without changing the value. That copybook is not a source for this class --
     * the reference never includes it, and it belongs to an account-service program that reaches a
     * retired assembler module -- and it is named here only to keep that trap from being rediscovered
     * as a surprise.</p>
     *
     * @param functionCode the four-character function code declared at physical line 110, read for
     *     reporting only, as a {@code String}
     * @param key the eleven-digit key declared at physical line 111, zero when the request carried a
     *     blank interval, as a {@code long}
     */
    public record DecodedRequest(String functionCode, long key) {

        /**
         * Accepts one decoded request, refusing an absent function code.
         *
         * <p>Assumptions: the code is required because the field is declared with a width and is
         * therefore always present in a buffer that decoded at all; an absent one would mean the decode
         * had produced something the layout cannot describe.</p>
         *
         * @param functionCode the function code to accept, required because the field is declared
         * @param key the key to accept, unconstrained because the declared field admits every
         *     eleven-digit value including zero
         * @throws NullPointerException if {@code functionCode} is {@code null}
         */
        public DecodedRequest {
            Objects.requireNonNull(functionCode, "functionCode must not be null");
        }
    }
}
