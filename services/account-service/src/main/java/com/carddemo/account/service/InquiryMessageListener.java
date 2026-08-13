package com.carddemo.account.service;

import com.carddemo.account.domain.Account;
import com.carddemo.account.mapper.AccountInquiryReplyMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.InquiryReplyLedger;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.codec.InquiryRequestCodec.InquiryRequest;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.observability.ThrowableDigest;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Answers the asynchronous account-inquiry exchange, replacing the queue-driven transaction that served it.
 *
 * <h2>Purpose</h2>
 * <p>This class is the migrated form of {@code app/app-vsam-mq/cbl/COACCT01.cbl}, a 620-line queue-triggered
 * CICS transaction that reads a fixed one-thousand-character request, performs one keyed read of the account
 * master and puts a labelled fixed-width reply on a reply queue. That program is REFERENCE-ONLY: it is read
 * as the specification for this class and is never modified. Every divergence named below is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Paragraph-to-method traceability, cited by PHYSICAL line in that file because it is legacy
 * sequence-numbered and its column-one sequence numbers are not line numbers:</p>
 * <ul>
 *   <li>{@code 4000-MAIN-PROCESS} at physical line 325, whose {@code EXEC CICS SYNCPOINT} verb sits at
 *       physical line 327, together with {@code 3000-GET-REQUEST} at physical line 334, become
 *       {@link #onRequest(Message)} plus the container properties on its annotation.</li>
 *   <li>{@code 4000-PROCESS-REQUEST-REPLY} at physical line 390 becomes
 *       {@link #replyFor(InquiryRequest)}.</li>
 *   <li>{@code 4100-PUT-REPLY} at physical line 462 becomes
 *       {@link #publishReply(String, String, String, String)}.</li>
 *   <li>{@code 9000-ERROR} at physical line 501 becomes {@link #publishError(String)}, reached on the
 *       failure path through {@link #reportFailure(RuntimeException, String, String)}.</li>
 * </ul>
 *
 * <p>The baseline's driver is not reproduced as code. Its {@code 1000-CONTROL} opens the input, output and
 * error queues and then loops, performing {@code 3000-GET-REQUEST} and
 * {@code 4000-MAIN-PROCESS UNTIL NO-MORE-MSGS}. Those queue handles and that loop are the listener
 * container's job in the target, so this class holds only the body of one iteration.</p>
 *
 * <h2>Delivery discipline: NO transactional outbox, and the absence is the decision</h2>
 * <p>Assumptions: this consumer needs no transactional outbox, and the reason is in the reference programs
 * rather than in a preference. Every message-queue option in {@code COACCT01.cbl} is a SYNCPOINT option:
 * {@code grep -n SYNCPOINT} over that file returns exactly four hits, at physical lines 327, 347, 475 and
 * 512, and {@code grep -c NO-SYNCPOINT} over it returns ZERO. Physical line 347 computes the get options as
 * {@code MQGMO-SYNCPOINT}, physical line 475 computes the put options as {@code MQPMO-SYNCPOINT}, and the
 * datastore read sits between them -- the {@code EXEC CICS READ} at physical lines 396 to 403 runs inside the
 * unit of work the {@code EXEC CICS SYNCPOINT} at physical line 327 opens. Get, read and put are therefore
 * ONE unit of work, so there is no window in which the read is committed and the reply is lost, and there is
 * nothing for an outbox to close.</p>
 *
 * <p>Assumptions: the pending-authorization consumer is the exact opposite and must not be read as the same
 * discipline. {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} computes its get options as
 * {@code MQGMO-NO-SYNCPOINT} at physical line 389 and its put options as {@code MQPMO-NO-SYNCPOINT} at
 * physical line 753, so it publishes its reply OUTSIDE the commit and does have a lost-reply window -- which
 * is why that flow needs an outbox and this one does not. Getting the two backwards in either direction is
 * the failure mode this paragraph exists to prevent: adding an outbox here would add durable machinery to
 * close a window that does not exist, and removing it there would drop machinery that flow cannot do
 * without.</p>
 *
 * <p>Refactoring Rationale: the target acknowledgement model is consequently delete-on-success plus the
 * queue's own visibility timeout, and it replaces the syncpoint bracket at physical lines 347 and 475 rather
 * than emulating it. {@link #onRequest(Message)} returns normally only after the reply has been sent, so the
 * framework deletes the request only then; if the read or the send fails the exception propagates, the
 * request becomes visible again, and repeated failure carries it to the dead-letter queue at the configured
 * receive count. Nothing is ever acknowledged for a request that was not answered, which is the property the
 * baseline's single unit of work provided.</p>
 *
 * <h2>What delete-on-success does NOT provide: the duplicate reply</h2>
 * <p>⚠️ Refactoring Rationale: the paragraphs above were complete about ONE direction of the syncpoint
 * bracket and silent about the other, and the silence read as an all-clear. A baseline unit of work spanning
 * get, read and put rolls the PUT back when the unit fails, so the baseline can neither lose a reply nor send
 * one twice. Delete-on-success reproduces the first half only. The send is committed at the queue the moment
 * it returns, and the acknowledgement is a SEPARATE call afterwards, so a task killed between them -- or a
 * container cycled there, or an acknowledgement lost there, or a visibility timeout that elapsed while the
 * send was in flight -- leaves the request visible again and the next delivery composes and sends a SECOND
 * reply carrying the same correlation identifier as the first. A requester pairing an answer to a question on
 * that identifier then holds two answers for one question, and nothing on the wire distinguishes the
 * duplicate from the original. That is a divergence from the baseline, not a property of it.</p>
 *
 * <p>Assumptions: the remedy is a durable CLAIM keyed by the QUEUE SERVICE's own identifier for the
 * delivery -- stable across every redelivery of one message and unique per accepted send -- in
 * {@code account.inquiry_reply_ledger} through
 * {@link com.carddemo.account.repository.InquiryReplyLedger}. The reply is composed, then recorded and
 * COMMITTED, then sent, then marked sent. A redelivery finding the claim already retired suppresses its
 * duplicate; a redelivery finding it outstanding re-sends the RECORDED bytes rather than recomposing them,
 * so the second copy of one answer cannot disagree with the first about a balance that moved in between.</p>
 *
 * <p>Assumptions: this is NOT the outbox the section above rules out, and the distinction is the
 * requirement rather than the mechanism. An outbox guarantees a reply EXISTS for every committed decision,
 * which this exchange does not need because it commits no decision -- its read is read-only, so no state
 * survives that a missing reply would contradict. A claim guarantees a reply is not sent TWICE. The
 * authorization consumer needs the first and has one; this consumer needs the second and now has one, and
 * neither is a substitute for the other.</p>
 *
 * <p>Trade-offs: one crash window remains open and is stated rather than glossed. A task that dies after the
 * send and before the mark leaves the claim outstanding, so the redelivery re-sends and the requester
 * receives two byte-identical copies. Closing it entirely would require the queue send and the database mark
 * to commit together across two resource managers, which is the two-phase commit AAP section 0.7.6 records as
 * eliminated by this migration and not to be refilled. What is bought is that duplication is now one specific
 * failure rather than the outcome of every redelivery.</p>
 *
 * <p>⚠️ Refactoring Rationale: the claim was keyed on the PRODUCER-supplied message attribute, falling back
 * to the correlation identifier, and that inverted the guarantee for a whole class of requester. Neither value
 * is authenticated or constrained: a producer that reuses one correlation identifier across several
 * questions -- which a requester is entitled to do, and which this system's own contract permits -- had its
 * second, genuine inquiry suppressed as a redelivery of the first and received the earlier answer for the
 * later question. Keying on the broker's identifier removes that outcome by construction, because a second
 * send is a second identifier whatever the producer labels it with, and it costs nothing: the value is present
 * on every delivery the queue makes. The two producer-supplied values remain BELOW it as legacy fallbacks for
 * a request that reaches this consumer without passing the broker at all, which deployed traffic cannot
 * arrange.</p>
 *
 * <p>Trade-offs: a request carrying NEITHER a broker identifier nor any usable identity of its own is answered
 * unguarded, and the fact is logged. There is nothing to key a claim on, and the alternative -- keying on a
 * digest of the payload -- was rejected because it cannot tell a redelivery of one request from a second,
 * legitimately identical request, so it would silently answer only the first of two genuine inquiries.
 * Treating an unidentified request as new is also the baseline's own behaviour, which performs no idempotency
 * check of any kind, so the unguarded path is a preserved property rather than a weakened one.</p>
 *
 * <p>Assumptions: every identity that reaches the durable row is BOUNDED at intake by the shared queue-identity
 * rule in {@link com.carddemo.common.messaging.MessagingCorrelationId}, so an arbitrarily long or
 * control-character-bearing producer value can no longer be discovered by an insert. An unusable value is
 * treated as absent for the exchange and reported to the error sink as a controlled protocol diagnostic
 * carrying its LENGTH and never its bytes, while the request itself is still answered -- because the payload
 * of such a request is ordinarily valid, and dead-lettering it would answer a legitimate question with
 * silence.</p>
 *
 * <h2>Statelessness</h2>
 * <p>Assumptions: this bean holds no cross-message state, and the baseline asks for exactly that.
 * {@code PROGRAM-ID. COACCT01 IS INITIAL.} at physical line 2 forces every invocation to start from a fresh
 * {@code WORKING-STORAGE}, so no value may survive from one message to the next. Every field on this class
 * is either an injected collaborator, a configured name, or a queue-address cache whose entries are stable
 * for the life of a queue; nothing that varies per message is held in a field.</p>
 *
 * <p>Refactoring Rationale: the CICS pseudo-conversational session structure is decomposed rather than
 * ported. {@code app/cpy/COCOM01Y.cpy} declares the 160-byte {@code CARDDEMO-COMMAREA} at physical lines 19
 * to 44 in five {@code 05} groups, and its parts go four different ways: navigation becomes client-side
 * router history; identity -- {@code CDEMO-USER-ID PIC X(08)} at physical line 25 and
 * {@code CDEMO-USER-TYPE PIC X(01)} at physical line 26 with its {@code 'A'} and {@code 'U'} conditions at
 * physical lines 27 and 28 -- becomes validated token claims on the synchronous routes; selection context
 * becomes request parameters; and the re-entry discriminator at physical lines 29 to 31, whose
 * {@code 88 CDEMO-PGM-ENTER VALUE 0.} and {@code 88 CDEMO-PGM-REENTER VALUE 1.} distinguish a first turn
 * from a later one, is REMOVED outright. A one-shot message handler has no turn to remember, so there is no
 * session state, no sticky session and no server-side session store anywhere in this flow.</p>
 *
 * <h2>Why a business outcome is a reply and an infrastructure fault is an exception</h2>
 * <p>Trade-offs: the two are separated deliberately, and conflating them is the other failure mode this
 * class is written to avoid. A request naming an account that does not exist, or naming the wrong function,
 * is a BUSINESS outcome: {@code 4000-PROCESS-REQUEST-REPLY} answers it with an
 * {@code INVALID REQUEST PARAMETERS} sentence and consumes the message -- at physical lines 428 to 435 for a
 * read that found nothing and at physical lines 448 to 456 for a request that never qualified -- so this
 * class publishes the same sentence and returns normally. Raising instead would redeliver a request whose
 * answer can never change and then dead-letter one the baseline answered. An unexpected read failure is an
 * INFRASTRUCTURE fault whose retry may succeed, and the baseline treats it differently too: its
 * {@code WHEN OTHER} branch at physical lines 437 to 445 performs {@code 9000-ERROR} and then
 * {@code 8000-TERMINATION}, reporting to the error sink and ending the task rather than replying. This class
 * does the same and then propagates.</p>
 *
 * <h2>Queue topology and endpoints</h2>
 * <p>Assumptions: all three destinations arrive from configuration and none is written into this class, and
 * that is what the baseline itself does. {@code 01 QUEUE-INFO.} at physical line 92 declares four
 * queue-name fields -- the queue manager, the input queue, the reply queue and the error queue -- and every
 * one of them is {@code PIC X(48) VALUE SPACES}, filled at run time. The two places the baseline does assign
 * a name by literal produce dotted uppercase names that
 * are not legal queue names in the target service at all, so no name from the baseline could be carried
 * across as written even if hard-coding one were acceptable.</p>
 *
 * <p>Alternatives Considered: first-in-first-out queues, as the pending-authorization flow uses. Rejected
 * because this exchange has no ordering requirement: each inquiry is an independent keyed read whose reply is
 * matched by correlation rather than by position, and nothing in {@code COACCT01.cbl} sequences one request
 * against another -- its counter at physical line 375 is incremented and never compared. The authorization
 * flow needs ordering per card because authorizations against one card must apply in order; imposing the same
 * here would serialise independent inquiries behind one another and cap throughput to no purpose. These are
 * standard queues, each with a dead-letter queue at a maximum receive count of five, provisioned by
 * {@code infra/modules/sqs}.</p>
 *
 * <h2>Money</h2>
 * <p>Alternatives Considered: rendering the reply's five monetary fields in this class with a decimal
 * format. Rejected on both exactness and width. {@code app/cpy/CVACT01Y.cpy} declares them
 * {@code PIC S9(10)V99} DISPLAY at physical lines 7, 8, 9, 13 and 14, so on the wire each is twelve
 * characters with the sign overpunched into the last digit and no decimal point present; a conventional
 * rendering would be the wrong shape AND the wrong width, and a consumer decoding by the copybook would read
 * the following label as part of the number. {@code tests/README.md} records at physical lines 273 and 274
 * that the EBCDIC sign option is required precisely because the default misreads that overpunch and silently
 * corrupts negative balances, which is the same data this reply carries. Every amount therefore reaches the
 * wire through {@link AccountInquiryReplyMapper}, which encodes it with the shared zoned-decimal codec from
 * exact scaled values; no IEEE-754 binary floating point appears anywhere in this path, and this class
 * performs no monetary arithmetic at all, so no rounding or operation-ordering decision arises in it.</p>
 *
 * <h2>Resilience</h2>
 * <p>Alternatives Considered: a retry annotation on the handler, or a circuit breaker in front of the
 * repository. Both rejected, and no resilience library is added. The durable retry tier for this flow is the
 * queue itself -- redelivery after the visibility timeout, bounded by a dead-letter queue at five receives --
 * which survives a task restart in a way an in-process retry does not, and which is the only tier that can
 * honour the delete-on-success contract above. An in-process retry would also hold the message invisible for
 * the whole of its own backoff, shrinking the window the queue has to hand the request to a healthy task. The
 * framework's core retry support does exist -- it is enabled with
 * {@code @EnableResilientMethods} and bounded by {@code maxRetries}, where total attempts are one plus that
 * value, and it is NOT the older {@code @EnableRetry} with {@code maxAttempts} -- but it is deliberately
 * unused here. A breaker is omitted for the same structural reason: the only dependency on this path is the
 * cluster this service already fails fast against, and a breaker would add a failure mode without removing
 * one.</p>
 *
 * <h2>Parity</h2>
 * <p>Assumptions: there is no executable parity oracle for this program and none is claimed.
 * {@code tests/README.md} states at physical lines 83 to 85 that the online CICS programs cannot run
 * end-to-end without a CICS runtime and that only their extractable validation logic is unit-tested, and its
 * business-rules section governs the posting, interest and category-balance programs rather than this one.
 * Parity here rests on the transcribed logic and on the copybook contract, both cited line by line above and
 * on each method below.</p>
 */
@Service
public class InquiryMessageListener {

    /**
     * The attribute carrying the requester's correlation identifier on both the request and the reply.
     *
     * <p>Assumptions: this is the target form of the 24-byte {@code SAVE-CORELID} declared at
     * {@code app/app-vsam-mq/cbl/COACCT01.cbl} physical line 55, which exists solely to hold the inbound
     * correlation identifier across the turn. It is published so a test and a producer can assert the
     * descriptor mapping against one constant rather than against a repeated literal.</p>
     */
    public static final String ATTRIBUTE_CORRELATION_ID = "correlationId";

    /**
     * The attribute carrying the request's own message identifier onto the reply.
     *
     * <p>Assumptions: the baseline captures the inbound message identifier at physical line 364, saves it
     * into {@code SAVE-MSGID} at physical line 372 and restores it onto the reply descriptor at physical
     * line 469. A message identifier is assigned by the queue service in the target and cannot be set on a
     * send, so the captured value travels as this attribute instead -- the same value reaching the requester
     * by the only route available.</p>
     */
    public static final String ATTRIBUTE_MESSAGE_ID = "messageId";

    /**
     * The attribute naming the destination a request asks to be answered on.
     *
     * <p>Assumptions: this is the target form of the reply-to field the baseline captures at physical line
     * 366, the file's only occurrence of that descriptor field, and saves at physical line 371. How far the
     * request's preference is honoured is decided in {@link #resolveReplyDestination(String)} and explained
     * there rather than here.</p>
     */
    public static final String ATTRIBUTE_REPLY_TO_QUEUE_URL = "replyToQueueUrl";

    /**
     * The header the QUEUE SERVICE's own identifier for a delivery arrives under.
     *
     * <p>Assumptions: this is the message identifier the broker assigns when a request is accepted for
     * delivery, not a value any producer can set. It is stable across every redelivery of one message --
     * only the receipt handle changes -- which is exactly the property a duplicate-suppression key needs,
     * and it is unique per accepted send, which is the property a producer-supplied attribute does not
     * have.</p>
     *
     * <p>Assumptions: the constant is DERIVED from the framework's own
     * {@code SqsHeaders.MessageSystemAttributes.MESSAGE_ID} rather than restating its literal
     * {@code "Sqs_Msa_messageId"}, so a framework rename fails compilation here instead of silently
     * turning every lookup into an absent header -- which would demote this consumer back to the
     * producer-supplied fallback without any test noticing.</p>
     */
    static final String HEADER_BROKER_MESSAGE_ID = SqsHeaders.MessageSystemAttributes.MESSAGE_ID;

    /**
     * The header carrying the broker's identifier in its unconverted form.
     *
     * <p>Assumptions: the framework may republish the broker identifier as a UUID in the framework's own
     * identity header while keeping the original string here, so this is read as the second source rather
     * than as an equivalent of the first. Reading both is what keeps the durable key broker-assigned under
     * either framework setting instead of depending on one of them.</p>
     */
    static final String HEADER_BROKER_RAW_MESSAGE_ID = SqsHeaders.SQS_RAW_MESSAGE_ID_HEADER;

    /**
     * The attribute every payload this class publishes declares its media type under.
     *
     * <p>Assumptions: the NAME is published separately from the VALUE in {@link #CONTENT_TYPE} because a
     * test and a producer assert against different halves of the same contract -- a consumer looks for the
     * attribute by name, and only then reads what it declares -- and a single constant carrying both would
     * force one of the two to be a repeated literal.</p>
     */
    public static final String ATTRIBUTE_CONTENT_TYPE = "contentType";

    /**
     * The media type every payload this class publishes is declared as.
     *
     * <p>Assumptions: the baseline declares its payload format as a string on every put -- physical line 471
     * for the reply and physical line 508 for the error report -- and with a string format the FIELD ORDER
     * and the OFFSETS are the contract, because a consumer locates each value by position. The target
     * declaration of that indicator is this media type. A structured envelope may be offered additively to
     * new consumers, but never as a replacement: reshaping this payload would break an existing consumer
     * silently rather than loudly.</p>
     *
     * <p>Refactoring Rationale: the declared type is {@code text/plain} and NOT {@code text/csv}, and the
     * correction matters because a media type is a parsing instruction. Nothing this class publishes is
     * comma-separated: the reply is the labelled fixed-width block {@code WS-ACCT-RESPONSE} declared at
     * physical lines 130 to 169, whose eleven label-and-value pairs are located by offset and framed to the
     * declared message length, and the error report is the positional diagnostic prefix assembled below. A
     * consumer that trusted a {@code text/csv} label would split on commas and find one field, or would
     * split a free-text value containing a comma into two. {@code text/csv} is reserved in this migration
     * for the one wire that genuinely is comma-separated, the authorization request and reply of
     * {@code com.carddemo.common.codec.CsvAuthCodec}; the date-inquiry reply, which is positional for the
     * same reason as this one, already declares {@code text/plain}.</p>
     *
     * <p>Trade-offs: {@code text/plain} states less than a registered fixed-width type would. No such type
     * is registered, and inventing one under an {@code application/vnd.} name would give consumers a label
     * no library recognises while still telling them nothing about the offsets -- which the published
     * contract and this class's own width constants state instead. Naming the payload as text and letting
     * the layout be documented is the honest of the two.</p>
     */
    public static final String CONTENT_TYPE = "text/plain";

    /**
     * The logger for this consumer.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InquiryMessageListener.class);

    /**
     * The diagnostic-context key the correlation identifier is published under.
     */
    private static final String MDC_CORRELATION_ID = "correlationId";

    // WHY : Assumptions: the reported paragraph name is the BASELINE's paragraph rather than a Java method
    //   name, because an operator reading this sink is diagnosing against the reference program and a name
    //   only the target uses would not locate anything in it.
    private static final String PARAGRAPH_PROCESS_REQUEST_REPLY = "4000-PROCESS-REQUEST-REPLY";

    /**
     * The verbatim return message the baseline reports for an unexpected read failure.
     *
     * <p>Assumptions: this is the literal moved into the diagnostic's return-message field at physical lines
     * 442 and 443, carried across character for character. It is 28 characters and that field is
     * {@code PIC X(25)}, so the baseline's own buffer holds only the leading 25 -- a truncation this class
     * reproduces rather than widens, because widening it would shift every following field to an offset no
     * existing reader of that sink expects.</p>
     */
    private static final String DIAGNOSTIC_READ_FAILED = "ERROR WHILE READING ACCTFILE";

    // WHY : Assumptions: the six widths below are the declared widths of the diagnostic group at physical
    //   lines 58 to 67, whose nine members are a 25-character paragraph name, a gap, a 25-character return
    //   message, a gap, a 2-digit condition code, a gap, a 5-digit reason code, a gap and a 48-character
    //   queue name. They matter because a consumer of that sink locates every value by OFFSET, so each
    //   width is part of the wire contract and not a formatting preference: changing one shifts every
    //   following field to a position no existing reader expects.
    private static final int DIAGNOSTIC_PARAGRAPH_WIDTH = 25;

    private static final int DIAGNOSTIC_MESSAGE_WIDTH = 25;

    private static final int DIAGNOSTIC_GAP_WIDTH = 2;

    private static final int DIAGNOSTIC_CONDITION_CODE_WIDTH = 2;

    private static final int DIAGNOSTIC_REASON_CODE_WIDTH = 5;

    private static final int DIAGNOSTIC_QUEUE_NAME_WIDTH = 48;

    /**
     * The combined width of the diagnostic's positional prefix.
     *
     * <p>Assumptions: this is SUMMED from the field widths above rather than written as a number, so the
     * prefix length and the fields that make it up cannot drift apart.</p>
     */
    private static final int DIAGNOSTIC_PREFIX_LENGTH =
            DIAGNOSTIC_PARAGRAPH_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_MESSAGE_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_CONDITION_CODE_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_REASON_CODE_WIDTH + DIAGNOSTIC_GAP_WIDTH
            + DIAGNOSTIC_QUEUE_NAME_WIDTH;

    /**
     * The account master rows this consumer reads.
     */
    private final AccountRepository accounts;

    /**
     * The renderer for all three reply forms.
     *
     * <p>Alternatives Considered: rendering the reply through {@code mapper/AccountMapper.java}, the mapper
     * this context already uses elsewhere. Rejected because that class renders the synchronous response
     * shapes, whereas this reply is the labelled fixed-width block {@code WS-ACCT-RESPONSE} declared at
     * physical lines 130 to 169 and moved to the reply field at physical line 426, whose eleven labels and
     * eleven values are a wire layout rather than a document. The dedicated mapper carries that layout and
     * asserts its own length, and it routes every amount through the shared codecs, so no codec is declared
     * anywhere in this service.</p>
     */
    private final AccountInquiryReplyMapper replies;

    /**
     * The queue client replies and error reports are published with.
     */
    private final SqsClient sqs;

    /**
     * The configured queue name replies are published to.
     */
    private final String replyQueue;

    /**
     * The configured queue name error reports are published to.
     */
    private final String errorQueue;

    /**
     * The clock the expiry comparison reads the current instant from.
     */
    private final Clock clock;

    /**
     * The template the keyed read runs inside.
     *
     * <p>Assumptions: read-only and short -- one keyed lookup -- and it ends before the reply is published.
     * Refactoring Rationale: a template rather than the annotation this listener used to carry, because the
     * unit of work has to END at a visible point inside the method; an annotation can only end where the
     * method does, which is after the publish.</p>
     */
    private final TransactionTemplate readTransaction;

    /**
     * The durable record of which requests have already been answered.
     *
     * <p>Assumptions: a repository rather than a service, because it holds three named statements over one
     * table and no rule of its own. The class documentation records why the claim it performs cannot be a
     * mapped write.</p>
     */
    private final InquiryReplyLedger ledger;

    /**
     * The unit of work the claim and the mark are written in, one each.
     *
     * <p>Assumptions: a SECOND template rather than a reuse of the read one, and the difference is not
     * cosmetic: that one is read-only, so a write through it would be refused, and this one must COMMIT
     * before the send while that one must commit before it. Two templates is what lets the claim, the send
     * and the mark be three separate committed steps in that order, which is the whole of the guarantee.</p>
     */
    private final TransactionTemplate ledgerTransaction;

    /**
     * Resolved queue addresses, cached by configured name.
     *
     * <p>Assumptions: a queue's address is stable for the life of the queue, and this service is configured
     * to FAIL rather than create a queue that does not exist, so a cached entry cannot become a pointer to a
     * queue this system did not provision. Resolving per message instead would add a service call to every
     * reply for a value that never changes. The map is concurrent because the container dispatches messages
     * on several threads at once.</p>
     *
     * <p>Refactoring Rationale: this description was authored immediately ABOVE a second documentation block
     * and therefore documented nothing -- a compiler attaches only the last block before a declaration, so
     * the explanation of the one piece of mutable state on this class was dropped from the generated
     * documentation while this field carried none at all. It is attached to the field it describes.</p>
     */
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    /**
     * Creates the consumer.
     *
     * @param accounts the account master repository; must not be {@code null}
     * @param replies the reply renderer; must not be {@code null}
     * @param sqs the queue client; must not be {@code null}
     * @param replyQueue the configured reply queue name; must not be {@code null} or blank
     * @param errorQueue the configured error queue name; must not be {@code null} or blank
     * @param ledger the durable record of already-answered requests; must not be {@code null}
     * @param clock the clock the expiry check reads; must not be {@code null}
     * @param transactionManager the manager the short units of work are opened against; must not
     *     be {@code null}
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if either queue name is blank, because a consumer that cannot address
     *     its reply queue would take requests off the request queue and answer none of them
     */
    public InquiryMessageListener(AccountRepository accounts,
            AccountInquiryReplyMapper replies,
            SqsClient sqs,
            @Value("${carddemo.account.inquiry.reply-queue}") String replyQueue,
            @Value("${carddemo.account.inquiry.error-queue}") String errorQueue,
            InquiryReplyLedger ledger,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        // WHY : Assumptions: every collaborator arrives through the CONSTRUCTOR rather than through field
        //   injection or a static lookup, which is how the baseline's linkage is replaced. COACCT01 reaches
        //   its dependencies by static CALL -- 'MQGET' at physical lines 352 to 355 and 'MQPUT' at physical
        //   lines 479 to 486 -- against values held in shared WORKING-STORAGE. Constructor injection makes
        //   the same collaborators substitutable, so this handler is testable without a queue or a database,
        //   and it makes every one of them non-null for the life of the bean.
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.replies = Objects.requireNonNull(replies, "replies must not be null");
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.replyQueue = requireQueueName(replyQueue, "carddemo.account.inquiry.reply-queue");
        this.errorQueue = requireQueueName(errorQueue, "carddemo.account.inquiry.error-queue");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");

        // WHY : Assumptions: REQUIRES_NEW so the read is a unit of work of its own. A listener invocation
        //   carries no ambient transaction in the ordinary case, and declaring the propagation explicitly is
        //   what keeps that true if one is ever introduced around it.
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);

        // WHY : Assumptions: REQUIRES_NEW and NOT read-only, for the reason recorded on the field: the
        //   claim must be committed before the reply is sent and the mark must be committed after it, so
        //   each has to be a unit of work that ends when this class says it does rather than one that
        //   ends with the handler.
        this.ledgerTransaction = new TransactionTemplate(transactionManager);
        this.ledgerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Validates a configured queue name.
     *
     * <p>Assumptions: a blank name is refused at construction rather than at first use. The baseline opens
     * all three of its queues before it gets a single message -- input at physical line 222, output at
     * physical line 255, error at physical line 289 -- and terminates the task if any open fails, so a
     * misconfigured destination stops the program before it can consume anything. Deferring the check would
     * let this consumer start, take requests off the request queue and fail to answer every one of them.</p>
     *
     * @param value the configured value
     * @param property the property name, so a refusal names exactly what to set
     * @return the trimmed name, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is blank
     */
    private static String requireQueueName(String value, String property) {
        Objects.requireNonNull(value, property + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(property
                    + " must name a queue: without it this consumer would take requests off the request"
                    + " queue and be unable to answer any of them");
        }
        return value.trim();
    }

    /**
     * Answers one account-inquiry request.
     *
     * <p>Purpose: this is the body of one iteration of the baseline's driver loop. It carries
     * {@code 3000-GET-REQUEST} at physical line 334, whose success branch at physical line 363 captures the
     * descriptor and hands the payload on at physical line 374, and it runs inside the unit of work
     * {@code 4000-MAIN-PROCESS} opens with its {@code EXEC CICS SYNCPOINT} verb at physical line 327.</p>
     *
     * <p>Assumptions: two of the three polling values on the annotation are the baseline's own rather than
     * chosen defaults. The wait is five seconds because physical line 337 moves 5000 into the get's wait
     * interval, in milliseconds, under the program's own comment on physical line 336 recording that as five
     * seconds. The loop is bounded by queue emptiness alone: physical lines 214 to 216 perform the get and
     * then repeat until the no-more-messages condition, which physical lines 377 and 378 set when the get
     * reports that no message is available, and the counter incremented at physical line 375 is never
     * compared against a ceiling. The five-hundred-message ceiling that DOES exist in this system belongs to
     * the authorization consumer, at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} physical line 40, and is not this program's
     * discipline.</p>
     *
     * <p>Trade-offs: the third value, concurrency, has no counterpart in the reference and is ADDITIVE. The
     * baseline's driver is one task performing one get at a time, whereas this container fetches a batch and
     * runs handlers concurrently, so the target processes more requests per unit of time than the reference
     * does and two requests in one batch are not guaranteed to be answered in the order they were enqueued
     * in. That is admissible for this flow specifically: the exchange is request/reply keyed on a correlation
     * identifier the reply echoes, each request is answered from one keyed read of one account, and no
     * request mutates anything -- so no outcome depends on which of two requests is answered first.
     * Alternatives Considered: a single-threaded container with a one-message batch, which would reproduce
     * the reference's serialisation exactly. Rejected because the property it would preserve is one nothing
     * observes, and the cost is a queue depth that grows without bound behind a five-second poll. The
     * authorization consumer does NOT get this treatment: its ordering is observable per card, which is why
     * that flow is keyed onto an ordered queue instead.</p>
     *
     * <p>Assumptions: no container-factory name is named on the annotation because
     * {@code config/SqsConfig.java} declares none, so the framework's own default factory applies. Naming a
     * factory that does not exist fails at context refresh rather than at compilation, so the coupling is
     * stated here as the reason for an absent attribute rather than left to be rediscovered.</p>
     *
     * <p>Assumptions: the transaction is {@code REQUIRES_NEW} so each message is its own unit of work, which
     * is the target form of the syncpoint at physical line 327 being issued at the top of EVERY iteration.
     * Sharing a transaction across messages would let one message's failure roll back another's work. It is
     * read-only because this exchange only reads -- the keyed read at physical lines 396 to 403 is the whole
     * of its datastore access -- so there is no write whose commit could fail after a reply had already been
     * sent.</p>
     *
     * <p>Assumptions: a request whose expiry has passed is DROPPED, not answered. Answering would publish an
     * account's financial position to a requester that has already stopped waiting, leaving it sitting on a
     * reply queue for nobody. The rule is the shared one in {@link MessageExpiry}, so every consumer in this
     * system honours a requester's expiry identically rather than each inventing a tolerance.</p>
     *
     * <p>Trade-offs: an unexpected failure is reported to the error sink BEFORE it propagates, in that order,
     * because the baseline performs {@code 9000-ERROR} at physical line 444 and only then
     * {@code 8000-TERMINATION} at physical line 445. Reversing the order would lose the report whenever the
     * propagation itself ended the invocation. What this accepts is one extra publish attempt on a failing
     * path; {@link #reportFailure(RuntimeException, String, String)} makes that attempt unable to replace the
     * original fault.</p>
     *
     * @param message the received message, whose payload is the fixed-width request and whose attributes
     *     carry the correlation identifier, the message identifier, the requested reply destination and the
     *     optional expiry; must not be {@code null}
     * @throws NullPointerException if {@code message} is {@code null}, which is a caller fault rather than a
     *     wire condition
     * @throws RuntimeException if the read or the publish fails, propagating so the request is redelivered
     *     instead of being acknowledged unanswered; the concrete types are the data-access exceptions the
     *     repository raises and the queue-client exceptions the send raises
     */
    @SqsListener(queueNames = "${carddemo.account.inquiry.request-queue}",
            maxConcurrentMessages = "${carddemo.account.inquiry.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.account.inquiry.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.account.inquiry.poll-timeout-seconds:5}")
    public void onRequest(Message<String> message) {
        Objects.requireNonNull(message, "message must not be null");

        // WHY : Assumptions: all three descriptor values are read ONCE, up front, exactly as the baseline
        //   copies them out of the descriptor the moment the get succeeds -- the message identifier at
        //   physical line 364, the correlation identifier at physical line 365 and the reply-to queue at
        //   physical line 366 -- and are then held for the rest of the exchange, as the baseline holds them:
        //   the correlation identifier into SAVE-CORELID at physical line 370, the reply-to queue into
        //   SAVE-REPLY2Q at physical line 371 and the message identifier into SAVE-MSGID at physical line
        //   372, all before any processing that could overwrite the descriptor. Reading them later would be
        //   reading them after the work rather than before it, and those three save areas exist in the
        //   baseline precisely to make that impossible.
        String brokerMessageId = brokerMessageId(message);
        String correlationId = usableIdentity(correlationId(message), ATTRIBUTE_CORRELATION_ID);
        String messageId =
                usableIdentity(attribute(message, ATTRIBUTE_MESSAGE_ID), ATTRIBUTE_MESSAGE_ID);
        String requestedReplyTo = attribute(message, ATTRIBUTE_REPLY_TO_QUEUE_URL);

        // WHY : Assumptions: the LOGGING context carries the sanitised rendering while the reply carries the
        //   value verbatim. The two are deliberately different renderings of one identity: a log record must
        //   not be able to carry a delimiter or a line terminator, and a reply must carry the requester's own
        //   bytes so it can match the answer to its request.
        MDC.put(MDC_CORRELATION_ID, MessagingCorrelationId.logSafe(correlationId));
        try {
            String rawExpiry = attribute(message, MessageExpiry.HEADER_EXPIRES_AT);
            if (MessageExpiry.isMalformed(rawExpiry)) {
                // WHY : Trade-offs: an unparseable expiry is treated as ABSENT rather than as already passed,
                //   because treating it as passed would silently discard every request from a requester whose
                //   formatting differs. Only the LENGTH is logged: the value came off the wire.
                LOG.warn("event=account.inquiry.expiry-unparseable length={}", rawExpiry.trim().length());
            } else if (MessageExpiry.isExpired(rawExpiry, this.clock.instant())) {
                LOG.warn("event=account.inquiry.dropped reason=expired");
                return;
            }

            InquiryRequest request = InquiryRequestCodec.decode(message.getPayload());

            // WHY : Refactoring Rationale: the reply is composed inside a short READ-ONLY transaction that
            //   ends before it is sent, and the send happens with no transaction open. This method was
            //   annotated transactional across the whole exchange, so a database connection was held for the
            //   duration of a queue publish -- a network round trip to a service this one does not control.
            //   With ten concurrent messages configured by default, a slow or unreachable queue endpoint
            //   could therefore hold ten connections while doing no database work at all, and starve every
            //   request-serving path in this process. The read itself is one keyed lookup, so the
            //   transaction it needs is very short.
            // WHY : Assumptions: nothing about the exchange's guarantees changes. The read is read-only, so
            //   there is nothing to commit or roll back, and the request is still acknowledged only after
            //   its answer has been sent, because a publish failure propagates out of this method exactly as
            //   before -- the acknowledgement follows a clean return, not a commit.
            String reply = Objects.requireNonNull(
                    this.readTransaction.execute(status -> replyFor(request)),
                    "the read transaction returned no reply, which its callback cannot do");

            answerOnce(reply, resolveReplyDestination(requestedReplyTo), brokerMessageId, messageId,
                    correlationId);
        } catch (RuntimeException failure) {
            reportFailure(failure, messageId, correlationId);
            throw failure;
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    /**
     * Chooses and renders the reply for one decoded request.
     *
     * <p>Purpose: transcribes {@code 4000-PROCESS-REQUEST-REPLY} at physical line 390, whose three outcomes
     * are the keyed read succeeding, the read finding nothing, and the request never qualifying.</p>
     *
     * <p>Assumptions: the guard is the baseline's own, transcribed rather than reinterpreted.
     * {@code IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES} at physical line 393 admits a request only when BOTH
     * hold, and its {@code ELSE} answers everything else with the one sentence that names the key and the
     * function together. Splitting the guard into two separate refusals would produce two different messages
     * where the baseline produces one, and a requester comparing against the baseline's text would stop
     * recognising it.</p>
     *
     * <p>Assumptions: the function code and the key are read through the shared codec's own queries rather
     * than by inspecting substrings here. The layout -- a four-character function, an eleven-digit key and
     * 985 characters of filler, declared at physical lines 109 to 112 -- is single-sourced in
     * {@link InquiryRequestCodec} so that this class and any future producer cannot disagree about an
     * offset.</p>
     *
     * @param request the decoded request; must not be {@code null}
     * @return the framed reply body, exactly the message length, never {@code null}
     * @throws org.springframework.dao.DataAccessException if the keyed read fails unexpectedly, which is the
     *     target form of the baseline's {@code WHEN OTHER} branch at physical lines 437 to 445 and propagates
     *     rather than becoming a reply
     * @throws NullPointerException if {@code request} is {@code null}; the precondition is not re-validated
     *     here because the sole caller has already rejected a null message and the codec cannot return null,
     *     so a null could only arrive from a direct call and is a defect rather than a wire condition
     */
    private String replyFor(InquiryRequest request) {
        if (!request.isFunction(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY) || !request.hasUsableKey()) {
            LOG.info("event=account.inquiry.rejected reason=guard function={}", request.trimmedFunction());
            return this.replies.frame(this.replies.invalidRequest(request.key(), request.function()));
        }

        Optional<Account> account = this.accounts.findById(request.keyValue());
        if (account.isEmpty()) {
            // WHY : Assumptions: this is ANSWERED rather than raised. The baseline's not-found branch at
            //   physical lines 428 to 435 replies and consumes the message, so raising here would redeliver a
            //   request whose answer cannot change and then dead-letter a request the baseline answered.
            // WHY : Refactoring Rationale: the account identifier is NOT logged, and these two lines
            //   carried it. An eleven-digit ACCT-ID -- app/cpy/CVACT01Y.cpy line 5 declares
            //   ACCT-ID PIC 9(11) -- reached the mapped diagnostic context of every inquiry the queue
            //   delivered, so ordinary successful traffic wrote an account identifier per message into log
            //   storage. That is the same exposure the correlation attribute's own rule exists to prevent,
            //   arriving through a hand-written format argument instead of through a metadata field, which
            //   is why no character or shape rule could see it.
            // WHY : Assumptions: what replaces it is already present. The correlation identity is put into
            //   the diagnostic context for the whole handling of the message, so every line below is
            //   already attributable to one request without naming its subject, and the requester holds
            //   the key it sent because the reply still carries it.
            // WHY : Alternatives Considered: logging a digest of the identifier instead of dropping it.
            //   Rejected because an eleven-digit space has a hundred billion members and is enumerable in
            //   seconds, so an UNKEYED digest is a reversible rendering of the value rather than a
            //   redaction of it. A KEYED one -- com.carddemo.common.security.OpaqueIdentifier, which the
            //   authorization context uses for exactly this -- is safe and was rejected here on cost: this
            //   service holds no signing material and needs none for anything else, so adopting it would
            //   introduce a secret, an environment variable, an IAM grant and a rotation obligation to
            //   improve two log lines.
            LOG.info("event=account.inquiry.not-found");
            return this.replies.frame(this.replies.accountNotFound(request.key()));
        }

        LOG.info("event=account.inquiry.answered");
        return this.replies.frame(this.replies.accountFound(account.get()));
    }

    /**
     * Chooses the destination one reply is addressed to.
     *
     * <p>Purpose: applies the reply-to descriptor mapping. The baseline captures the request's reply-to queue
     * at physical line 366 -- the file's only occurrence of that descriptor field -- and saves it at physical
     * line 371, and this method decides what that captured preference is allowed to reach.</p>
     *
     * <p>Assumptions: the requested route is treated as ROUTING DATA, not as authority, and is honoured only
     * when it matches this context's own reply destination exactly. That is the contract recorded in
     * {@code docs/architecture/messaging-contracts.md}, and it is enforced independently of this class:
     * {@code infra/modules/ecs-service} grants this task role a send action on the exact reply and error
     * queue identifiers alone and assembles none from an inbound attribute, so a send anywhere else would be
     * refused at run time in any case. The authored topology exposes one shared inquiry-reply queue, so the
     * permitted set here is a single address.</p>
     *
     * <p>Trade-offs: a value that does not match falls back to the configured destination rather than
     * failing, and the substitution is logged. Failing would dead-letter a request the baseline answered,
     * because the baseline reaches its destination through a handle its driver opens once from the
     * configured reply-queue name, and therefore answers a request whose reply-to field names anything at
     * all.
     * Honouring an arbitrary address instead would make this consumer a confused deputy, able to direct an
     * account's financial position to a queue of the sender's choosing.</p>
     *
     * @param requestedReplyTo the destination the request names, or {@code null} when it names none
     * @return the destination to publish the reply to, never {@code null}
     * @throws software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException if the configured
     *     destination cannot be resolved, which is a deployment fault rather than a request fault
     */
    private String resolveReplyDestination(String requestedReplyTo) {
        String permitted = queueUrl(this.replyQueue);
        if (permitted.equals(requestedReplyTo)) {
            return permitted;
        }

        if (requestedReplyTo != null && !requestedReplyTo.isBlank()) {
            // WHY : Assumptions: the rejected value is NOT logged. It came off the wire, so logging it would
            //   place an unvalidated address into a log line, and the substitution is the only fact an
            //   operator needs -- the permitted address is already known from configuration.
            LOG.warn("event=account.inquiry.reply-destination-substituted reason=not-permitted");
        }
        return permitted;
    }

    /**
     * Publishes one reply, echoing both identifiers the request carried.
     *
     * <p>Purpose: transcribes {@code 4100-PUT-REPLY} at physical line 462, which restores the saved
     * identifiers and declares the payload format before addressing the reply destination through the handle
     * its driver opened.</p>
     *
     * <p>Assumptions: the correlation identifier is echoed VERBATIM, matching
     * {@code MOVE SAVE-CORELID TO MQMD-CORRELID} at physical line 470 -- the definitive proof that this is a
     * request-and-reply exchange rather than a one-way notification. It is the requester's own opaque value
     * and it is how the requester pairs an answer with a request, so altering it in any way, including
     * sanitising it, would make the reply unpairable. The message identifier the baseline restores at
     * physical line 469 is echoed for the same reason, as an attribute, because the queue service assigns the
     * reply its own identifier and one cannot be supplied on a send.</p>
     *
     * <p>Assumptions: an identifier the request did not carry is simply not attached rather than being
     * replaced by a value this class invents. A substituted identifier would let a requester pair a reply
     * against something it never sent, which is worse than an unpaired reply because it cannot be detected
     * downstream.</p>
     *
     * <p>Assumptions: the body arrives already framed to the message length, matching the baseline's own
     * discipline of moving whichever reply it built into a one-thousand-character field and then putting a
     * literal length of 1000 at physical line 468 regardless of how much of it carries data.</p>
     *
     * @param body the framed reply body; must not be {@code null}
     * @param destination the resolved reply destination; must not be {@code null}
     * @param messageId the request's message identifier to echo, or {@code null} to attach none
     * @param correlationId the request's correlation identifier to echo, possibly empty when none was
     *     supplied
     * @throws software.amazon.awssdk.core.exception.SdkException if the send fails, which propagates so the
     *     request becomes visible again rather than being acknowledged unanswered
     * @throws NullPointerException if {@code body} or {@code destination} is {@code null}, raised by the send
     *     below rather than validated here, for the reason recorded on {@link #replyFor(InquiryRequest)}: both
     *     values are produced inside this class on the path to this call
     */
    private void publishReply(String body, String destination, String messageId, String correlationId) {
        send(destination, body, replyAttributes(messageId, correlationId));
    }

    /**
     * Sends one reply at most once for a request, whatever the delivery count.
     *
     * <p>Purpose: this is the step the baseline obtains from its syncpoint bracket and delete-on-success does
     * not provide. The class documentation carries the full argument; in outline, the reply is recorded and
     * COMMITTED, then sent, then marked sent, so a redelivery can tell an answer that has already gone out
     * from one that has not.</p>
     *
     * <p>Assumptions: the three steps are in that ORDER and each is committed before the next begins.
     * Recording after the send would leave a reply a redelivery cannot discover, which is the state being
     * removed; marking before the send would suppress the re-send of a reply that never reached the queue,
     * which turns a duplicated answer into a missing one -- the worse failure, because a requester waiting on
     * an answer that will never arrive receives no signal at all.</p>
     *
     * <p>Assumptions: a redelivery whose claim is still outstanding re-sends the RECORDED bytes to the
     * RECORDED destination with the recorded identities, rather than the reply just composed. The two are
     * normally identical; when they are not, it is because the account moved between the two deliveries, and
     * sending the recorded copy is what stops two replies bearing one correlation identifier from disagreeing
     * about a balance. It also means a configuration change between deliveries cannot send the second copy of
     * one answer to a different queue from the first.</p>
     *
     * <p>Assumptions: the mark is NOT required to succeed for the exchange to be complete. A redelivery may
     * have retired the claim already, in which case this delivery's send was the duplicate and the count is
     * simply reported; the request is still acknowledged, because the requester has its answer.</p>
     *
     * @param reply the framed reply this delivery composed; must not be {@code null}
     * @param destination the resolved reply destination; must not be {@code null}
     * @param brokerMessageId the queue service's own identifier for this delivery, or {@code null} when the
     *     delivery carried none, which only a non-broker producer can arrange
     * @param messageId the request's message identity to echo, or {@code null} if it supplied none or supplied
     *     an unusable one
     * @param correlationId the request's correlation identity to echo, possibly empty when none was supplied
     * @throws software.amazon.awssdk.core.exception.SdkException if the send fails, which propagates so the
     *     request becomes visible again rather than being acknowledged unanswered
     * @throws org.springframework.dao.DataAccessException if the ledger cannot be written, which propagates
     *     for the same reason -- an unrecorded answer must not be sent
     */
    private void answerOnce(String reply, String destination, String brokerMessageId, String messageId,
            String correlationId) {

        String requestKey = requestKey(brokerMessageId, messageId, correlationId);
        if (requestKey == null) {
            // WHY : Assumptions: an unidentified request is answered unguarded and the fact is logged, for
            //   the reason the class documentation records -- there is nothing to key a claim on, keying on
            //   a payload digest would suppress a second genuine inquiry, and the baseline performs no
            //   idempotency check at all. The line exists so the gap is visible in the operational record
            //   rather than silent.
            // WHY : Assumptions: this is now reachable only for a delivery that carries NO broker identifier
            //   and no usable identity of its own. Queue traffic always carries one, so an occurrence of this
            //   event in a deployed environment says a request reached this consumer without passing the
            //   broker -- which is worth knowing on its own and is why the event is kept rather than removed
            //   as unreachable.
            LOG.warn("event=account.inquiry.unidentified reason=no-request-identity");
            publishReply(reply, destination, messageId, correlationId);
            return;
        }

        boolean claimed = Boolean.TRUE.equals(this.ledgerTransaction.execute(status ->
                this.ledger.claim(requestKey, reply, destination, correlationId, messageId,
                        LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))));

        if (claimed) {
            publishReply(reply, destination, messageId, correlationId);
            retireClaim(requestKey);
            return;
        }

        // WHY : Assumptions: the recorded row is read in its own unit of work, and its absence is treated
        //   as a defect rather than as a first delivery. The claim reported a conflict, so a row holds that
        //   key; a read finding none means it was removed underneath this delivery, and answering anyway
        //   would send a reply this class can no longer record.
        InquiryReplyLedger.RecordedReply recorded = this.ledgerTransaction
                .execute(status -> this.ledger.find(requestKey))
                .orElseThrow(() -> new IllegalStateException(
                        "the claim reported a conflict and no row holds the key"));

        if (recorded.sent()) {
            // WHY : Assumptions: the duplicate is DROPPED and the request is acknowledged, not raised. The
            //   requester already has its answer, so redelivering this request could only produce the
            //   duplicate again and would eventually dead-letter a request that was correctly answered.
            LOG.info("event=account.inquiry.duplicate-suppressed");
            return;
        }

        LOG.warn("event=account.inquiry.reply-resent reason=claim-outstanding");
        publishReply(recorded.payload(), recorded.destination(), recorded.messageId(),
                recorded.correlationId());
        retireClaim(requestKey);
    }

    /**
     * Marks a sent reply as sent, reporting rather than raising when another delivery got there first.
     *
     * @param requestKey the claim key of the request, the broker's own identifier for the delivery
     *     where it supplied one; must not be {@code null}
     */
    private void retireClaim(String requestKey) {
        boolean retired = Boolean.TRUE.equals(this.ledgerTransaction.execute(status ->
                this.ledger.markSent(requestKey,
                        LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))));
        if (!retired) {
            // WHY : Assumptions: this is reported and not raised. It means a concurrent delivery retired
            //   the claim between this one's read and its mark, so THIS send was the duplicate -- and
            //   raising would redeliver a request the requester has already been answered twice for.
            LOG.warn("event=account.inquiry.claim-already-retired");
        }
    }

    /**
     * Chooses the identity a claim is keyed on, preferring the identity the BROKER assigned.
     *
     * <p>Assumptions: the broker-assigned identifier is preferred over anything the producer supplied,
     * because only it has both properties a duplicate-suppression key needs: it is STABLE across every
     * redelivery of one message, so a redelivery finds the row its first delivery wrote, and it is UNIQUE per
     * accepted send, so two distinct requests can never collide onto one row. It is read from
     * {@link #HEADER_BROKER_MESSAGE_ID}, falling back to {@link #HEADER_BROKER_RAW_MESSAGE_ID}, and it is not
     * settable by a producer at all.</p>
     *
     * <p>Refactoring Rationale: the producer-supplied message attribute used to be preferred, and that was a
     * correctness defect rather than a style choice. Nothing authenticates or constrains a producer attribute:
     * a producer that reuses one value across two questions -- or a retry framework that replays a value it
     * minted once -- had its SECOND, genuine inquiry suppressed as though it were a redelivery of the first,
     * and the requester received the earlier answer for a later question. The broker identifier removes that
     * class of failure entirely, because a second send is a second identifier however the producer labels
     * it.</p>
     *
     * <p>Trade-offs: the two producer-supplied values are retained BELOW the broker identifier as explicitly
     * legacy fallbacks rather than dropped. They are reachable only when a delivery carries no broker
     * identifier, which real queue traffic cannot arrange -- a direct in-process call and a substituted client
     * can -- so keeping them preserves at-most-once for a producer that bypasses the broker, at the cost of
     * one collision mode confined to a path no deployed request takes. Dropping them would answer such a
     * request unguarded instead, which is strictly weaker for no gain.</p>
     *
     * <p>Assumptions: a blank value counts as absent, and a non-canonical producer value has already been
     * refused by {@link #usableIdentity(String, String)} before reaching here, so every value this method can
     * return is bounded by {@link MessagingCorrelationId#MAX_LENGTH} or is a broker identifier of the same
     * order. That is what keeps the returned key inside the durable ledger's own column width instead of
     * discovering the width on an insert. An empty key would collide every unidentified request onto one row,
     * so the first such request would suppress every later one.</p>
     *
     * @param brokerMessageId the queue service's own identifier for the delivery, or {@code null} when it
     *     carried none
     * @param messageId the request's message identity, or {@code null} if it supplied none or an unusable one
     * @param correlationId the request's correlation identity, possibly empty when none was supplied
     * @return the key to claim on, or {@code null} when neither the broker nor the request supplied a usable
     *     identity
     */
    private static String requestKey(String brokerMessageId, String messageId, String correlationId) {
        if (brokerMessageId != null && !brokerMessageId.isBlank()) {
            return brokerMessageId;
        }
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }
        return null;
    }

    /**
     * Reports the queue service's own identifier for one delivery.
     *
     * <p>Assumptions: the system-attribute header is read first and the unconverted header second, because a
     * framework configured to republish the identifier as a UUID leaves the original string in the second one.
     * Both are broker-assigned, so either is equally usable as a durable key, and reading both is what makes
     * the key broker-assigned under either setting rather than under one of them.</p>
     *
     * <p>Assumptions: the value is put through the same canonical rule as a producer-supplied identity even
     * though a broker identifier is a hyphenated hexadecimal token well inside that rule. One rule for every
     * source is what guarantees the returned key fits the durable column whatever produced it; a value that
     * failed the rule is treated as absent so the fallbacks below it still apply, rather than being trusted
     * because of where it came from.</p>
     *
     * @param message the received message; must not be {@code null}
     * @return the broker's identifier for this delivery, or {@code null} when the delivery carries none
     * @throws NullPointerException if {@code message} is {@code null}
     */
    private static String brokerMessageId(Message<String> message) {
        String system = attribute(message, HEADER_BROKER_MESSAGE_ID);
        if (MessagingCorrelationId.isCanonical(system)) {
            return system;
        }
        String raw = attribute(message, HEADER_BROKER_RAW_MESSAGE_ID);
        return MessagingCorrelationId.isCanonical(raw) ? raw : null;
    }

    /**
     * Admits a producer-supplied identity only when it can be echoed and recorded, reporting one that cannot.
     *
     * <p>Purpose: this is the intake bound finding 44 of the code review asks for. An identity that reaches
     * the reply's attributes and the durable ledger unchecked can be arbitrarily long and can carry control
     * characters, and both end the exchange the same way: the send or the insert raises, the request is
     * redelivered, the redelivery raises identically, and the requester receives NO reply while its request
     * ends on the dead-letter queue. The rule applied is the shared one,
     * {@link MessagingCorrelationId#isCanonical(String)} -- non-blank, at most
     * {@link MessagingCorrelationId#MAX_LENGTH} characters, printable US-ASCII only -- so every queue identity
     * in this system is bounded by one rule rather than by each consumer's own.</p>
     *
     * <p>Assumptions: an ABSENT identity is not a malformed one and is returned unchanged. The baseline
     * accepts a request that supplies neither identifier, clearing its own field to spaces at physical line
     * 338, so absence is ordinary and is never routed through the rule.</p>
     *
     * <p>Trade-offs: an unusable identity is treated as ABSENT for this exchange -- not echoed, not recorded,
     * not keyed on -- and the requester still receives its business answer, with a controlled protocol
     * diagnostic published to the error sink naming the attribute and its LENGTH. The alternatives were both
     * worse. Refusing the message, as the pending-authorization consumer does, dead-letters a request whose
     * PAYLOAD is perfectly valid and answers the requester with silence, which is the very outcome the review
     * finding is about. Truncating the value to the column width would record and echo a value that is neither
     * the requester's nor absent, so a requester pairing on it would match the answer to nothing while
     * believing it had matched. What this accepts is that such a requester cannot pair the reply it receives;
     * it could not pair a reply it never received either, and the diagnostic says why.</p>
     *
     * <p>Assumptions: the diagnostic and the log line carry the LENGTH and never the value, for the same
     * reason every other line on this path does -- the value came off the wire, and a non-canonical one is
     * precisely the kind that can carry a field or line terminator into a record.</p>
     *
     * @param candidate the identity the request supplied, {@code null} or empty when it supplied none
     * @param attributeName the attribute the value arrived under, named in the diagnostic; must not be
     *     {@code null}
     * @return the identity when absent or usable, or {@code null} when it was present and unusable
     */
    private String usableIdentity(String candidate, String attributeName) {
        if (!MessagingCorrelationId.isPresent(candidate)) {
            return candidate;
        }
        if (MessagingCorrelationId.isCanonical(candidate)) {
            return candidate;
        }
        LOG.warn("event=account.inquiry.identity-refused attribute={} length={}",
                attributeName, candidate.length());
        reportProtocolFault(attributeName, candidate.length());
        return null;
    }

    /**
     * Publishes one controlled protocol diagnostic for an identity this exchange cannot carry.
     *
     * <p>Assumptions: the diagnostic goes to the ERROR SINK and is framed in the same positional shape as
     * every other report published there, so an operator reads it at the offsets the baseline's diagnostic
     * group establishes. The return-message field is left BLANK deliberately: the baseline has no literal for
     * this condition, and inventing one would put a sentence in a field whose every other value is carried
     * across from the reference character for character.</p>
     *
     * <p>Assumptions: a failure to publish the diagnostic is SWALLOWED after being logged. The request itself
     * is answerable and is about to be answered, so raising here would dead-letter a valid request over a
     * report about an attribute -- which is the failure mode this whole path exists to remove.</p>
     *
     * @param attributeName the attribute whose value was refused; must not be {@code null}
     * @param length the refused value's length in characters, which is the only property of it that is safe
     *     to publish
     */
    private void reportProtocolFault(String attributeName, int length) {
        try {
            send(queueUrl(this.errorQueue),
                    this.replies.frame(errorDiagnostic(PARAGRAPH_PROCESS_REQUEST_REPLY, null,
                            this.errorQueue,
                            "identity-refused attribute=" + attributeName + " length=" + length)),
                    replyAttributes(null, null));
        } catch (RuntimeException reportingFailure) {
            LOG.warn("event=account.inquiry.identity-refusal-unreported failure={}",
                    ThrowableDigest.of(reportingFailure));
        }
    }

    /**
     * Publishes a diagnostic to the configured error sink.
     *
     * <p>Purpose: transcribes {@code 9000-ERROR} at physical line 501, which frames its diagnostic block to
     * the buffer length, declares the payload format and puts to the error handle.</p>
     *
     * <p>Assumptions: this is exposed rather than kept private because the error sink is reachable in the
     * baseline before any request exists, and the target keeps that reachability. {@code 1000-CONTROL}
     * performs {@code 2100-OPEN-ERROR-QUEUE} at physical line 187, ahead of its {@code EXEC CICS RETRIEVE}
     * and ahead of both the input and output opens, and then enters {@code 9000-ERROR} when that retrieve
     * fails -- at a point where no queue has been read and there is nothing to reply to. Paragraph
     * DECLARATION order is the reverse of that execution order, so reading the declarations alone gives the
     * opposite impression. Publishing this operation is what lets a caller in this context report a
     * diagnostic with no exchange in progress. It is deliberately NOT reached from the reply path: a business
     * outcome is a reply, not an error report.</p>
     *
     * <p>Assumptions: the diagnostic text is composed by the caller and must name no value that came off the
     * wire, for the same reason the reply path logs a length rather than a value. The failure path composes
     * its own text through {@link #reportFailure(RuntimeException, String, String)}, which renders a failure
     * as its chain of types and carries no message text at all.</p>
     *
     * @param diagnostic the diagnostic text, at most the message length; must not be {@code null}
     * @throws NullPointerException if {@code diagnostic} is {@code null}
     * @throws IllegalArgumentException if the text is longer than the message length, which is a defect in
     *     the caller's own formatting rather than a wire condition and must not be silently truncated
     * @throws software.amazon.awssdk.core.exception.SdkException if the send fails
     */
    public void publishError(String diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic must not be null");
        send(queueUrl(this.errorQueue), this.replies.frame(diagnostic), replyAttributes(null, null));
    }

    /**
     * Reports an unexpected failure to the error sink without letting the report replace it.
     *
     * <p>Purpose: this is the target form of the baseline's {@code WHEN OTHER} branch at physical lines 437 to
     * 445, which fills the diagnostic fields, performs {@code 9000-ERROR} and only then performs
     * {@code 8000-TERMINATION}. The caller propagates afterwards, which is this flow's form of that
     * termination.</p>
     *
     * <p>Assumptions: a failure in the REPORT is attached to the original failure rather than thrown, so an
     * unreachable error sink can never disguise the fault an operator is actually looking for. This matters
     * concretely here: when the queue client is what failed, the report will fail for the same reason, and
     * without this guard the second failure would replace the first on its way out.</p>
     *
     * <p>Assumptions: the report's failure is attached only when it is a DIFFERENT object from the original.
     * Attaching a throwable to itself is rejected outright by the platform, so a client that answers both
     * sends with one exception instance -- which a client is free to do, and which the send path here makes
     * reachable because a single unreachable queue fails the reply and the report identically -- would turn a
     * queue outage into an unrelated argument failure and lose the outage entirely. The guard is an identity
     * comparison rather than an equality one because it is object identity the platform refuses.</p>
     *
     * <p>Assumptions: both identifiers are attached to the report, and that is faithful rather than an
     * addition. The baseline's error paragraph never touches the descriptor's identifier fields, so the
     * descriptor it puts with is still the one the get populated at physical lines 352 to 355 -- meaning the
     * inbound identifiers travel with the report by inheritance. Attaching them explicitly reproduces that
     * outcome on a transport where nothing is inherited between sends.</p>
     *
     * <p>Assumptions: the failure is rendered as its chain of TYPES with no message text, by the shared
     * digest. A driver's or parser's own message is the one part of a failure into which a request value can
     * be interpolated, and this buffer is published onto a queue, so the type chain answers what failed
     * without opening that channel.</p>
     *
     * @param failure the failure to report; must not be {@code null}
     * @param messageId the request's message identifier to echo, or {@code null} to attach none
     * @param correlationId the request's correlation identifier to echo, possibly empty when none was
     *     supplied
     * @throws NullPointerException if {@code failure} is {@code null}, raised by the digest below. It is the
     *     ONE precondition here whose violation is not swallowed: every other failure inside this method is
     *     attached to {@code failure} and suppressed deliberately, so a null argument is the only way this
     *     method can throw, and it means the caller had no failure to report
     */
    private void reportFailure(RuntimeException failure, String messageId, String correlationId) {
        String digest = ThrowableDigest.of(failure);
        LOG.error("event=account.inquiry.error-sink paragraph={} failure={}",
                PARAGRAPH_PROCESS_REQUEST_REPLY, digest);

        try {
            send(queueUrl(this.errorQueue),
                    this.replies.frame(errorDiagnostic(PARAGRAPH_PROCESS_REQUEST_REPLY,
                            DIAGNOSTIC_READ_FAILED, this.errorQueue, digest)),
                    replyAttributes(messageId, correlationId));
        } catch (RuntimeException reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }

    /**
     * Composes the diagnostic buffer in the positional shape the baseline reports failures through.
     *
     * <p>Assumptions: the field widths and the two-character gaps between them are those of the diagnostic
     * group declared at physical lines 58 to 67, so a reader of the error sink can locate every value at the
     * offsets that group establishes. Each field is left-justified, space-padded and truncated at its
     * declared width, which is what a group move into a fixed picture does -- and it is why the 28-character
     * return message carried in {@link #DIAGNOSTIC_READ_FAILED} arrives as its leading 25 characters.</p>
     *
     * <p>Alternatives Considered: omitting the condition-code and reason-code intervals, which hold
     * queue-manager values that have no counterpart in the target and are therefore unfillable. Rejected
     * because omitting them shortens the prefix by nine characters and shifts the queue name behind them to
     * an offset no reader of that sink expects. They are preserved and left blank instead, which keeps every
     * other field where the baseline puts it and states the absence in the buffer itself.</p>
     *
     * <p>Trade-offs: the failure detail is appended AFTER the positional prefix, in space the baseline leaves
     * blank, and it is truncated to what remains of the message length. Folding it into the 25-character
     * return-message field instead would have displaced the baseline's own literal, so the choice is between
     * losing the literal and using blank space; the blank space costs nothing a reader relies on.</p>
     *
     * @param paragraph the reporting paragraph's name, or {@code null} to leave the field blank
     * @param returnMessage the baseline's verbatim return message for this failure, or {@code null} to leave
     *     the field blank
     * @param queueName the configured queue name the failure concerns, or {@code null} to leave the field
     *     blank
     * @param detail the failure rendering appended after the positional prefix, or {@code null} to append
     *     nothing
     * @return the composed buffer, at most the message length, never {@code null}
     */
    private static String errorDiagnostic(String paragraph, String returnMessage, String queueName,
            String detail) {

        String prefix = fixed(paragraph, DIAGNOSTIC_PARAGRAPH_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + fixed(returnMessage, DIAGNOSTIC_MESSAGE_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + blanks(DIAGNOSTIC_CONDITION_CODE_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + blanks(DIAGNOSTIC_REASON_CODE_WIDTH)
                + blanks(DIAGNOSTIC_GAP_WIDTH)
                + fixed(queueName, DIAGNOSTIC_QUEUE_NAME_WIDTH);

        // WHY : Assumptions: the appended detail is bounded by what the message length leaves after the
        //   positional prefix, and an absent detail appends nothing. The shared framing helper REFUSES an
        //   over-long body rather than truncating it, which is the right default for a reply a consumer
        //   decodes by offset but the wrong one here: a long failure rendering would then turn a report about
        //   one fault into a second, unrelated fault on the reporting path.
        String tail = detail == null ? "" : detail;
        int room = InquiryRequestCodec.MESSAGE_LENGTH - DIAGNOSTIC_PREFIX_LENGTH;
        return prefix + fixed(tail, Math.min(tail.length(), room));
    }

    /**
     * Renders a value at exactly one fixed width.
     *
     * <p>Assumptions: an absent value renders as blanks and an over-long one is truncated on the right, which
     * is what a move into a fixed alphanumeric picture does. Reproducing those two behaviours in one place is
     * what keeps every field of the diagnostic buffer at the offset the baseline's group establishes.</p>
     *
     * @param value the value, or {@code null} to render blanks
     * @param width the declared field width, which must not be negative
     * @return the value padded or truncated to the width, never {@code null}
     * @throws IndexOutOfBoundsException if {@code width} is negative, which can only mean a field-width
     *     constant has been given a nonsensical value rather than a caller passing wire content
     */
    private static String fixed(String value, int width) {
        String text = value == null ? "" : value;
        return text.length() >= width ? text.substring(0, width) : text + blanks(width - text.length());
    }

    /**
     * Renders one run of blanks.
     *
     * @param width the number of blanks, which must not be negative
     * @return the blanks, never {@code null}
     * @throws IllegalArgumentException if {@code width} is negative
     */
    private static String blanks(int width) {
        return " ".repeat(width);
    }

    /**
     * Assembles the attributes every payload this class publishes carries.
     *
     * <p>Assumptions: the media type is always attached and the two identifiers only when the request carried
     * them, which is the descriptor mapping stated once so the reply and the error report cannot drift into
     * declaring themselves differently.</p>
     *
     * @param messageId the message identifier to echo, or {@code null} to attach none
     * @param correlationId the correlation identifier to echo, or {@code null} or empty to attach none
     * @return the attributes, never {@code null}
     */
    private static Map<String, MessageAttributeValue> replyAttributes(String messageId,
            String correlationId) {

        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(CONTENT_TYPE));
        if (messageId != null && !messageId.isEmpty()) {
            attributes.put(ATTRIBUTE_MESSAGE_ID, stringAttribute(messageId));
        }
        if (correlationId != null && !correlationId.isEmpty()) {
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(correlationId));
        }
        return attributes;
    }

    /**
     * Publishes one payload to one destination.
     *
     * <p>Assumptions: both publishing paths funnel through here so that neither can acquire a different
     * failure behaviour by accident. Nothing is caught: a send failure must reach the caller, because the
     * whole delivery guarantee of this flow is that a request is acknowledged only after its answer has been
     * sent.</p>
     *
     * @param destination the resolved destination; must not be {@code null}
     * @param body the framed payload; must not be {@code null}
     * @param attributes the message attributes; must not be {@code null}
     * @throws software.amazon.awssdk.core.exception.SdkException if the send fails
     * @throws NullPointerException if {@code destination}, {@code body} or {@code attributes} is
     *     {@code null}, raised by the request builder. The preconditions are not re-validated here because
     *     every one of the three is produced inside this class on the path to this call, so a null is a defect
     *     in this class rather than a condition a caller or the wire can present
     */
    private void send(String destination, String body, Map<String, MessageAttributeValue> attributes) {
        this.sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(destination)
                .messageBody(body)
                .messageAttributes(attributes)
                .build());
    }

    /**
     * Reports the requester's correlation identifier, or an empty string when none was supplied.
     *
     * <p>Assumptions: an ABSENT correlation identifier is accepted rather than refused, because the baseline
     * accepts one -- it clears the field to spaces at physical line 338 and echoes whatever the descriptor
     * carried, including nothing. This method reports the value as the request carried it; whether it is
     * USABLE is decided once, by {@link #usableIdentity(String, String)}, so the rule applies identically to
     * this identity and to the message identity beside it.</p>
     *
     * <p>Refactoring Rationale: a non-canonical identifier used to be echoed unchanged, on the ground that it
     * is the requester's own value and a reply is useless to it otherwise. That reasoning was sound about the
     * REPLY and silent about the two places the value also reaches. It is attached as a message attribute,
     * where a control character is rejected by the queue service itself, and it is recorded in the durable
     * ledger, where a value longer than the column is rejected by the database -- and either rejection ends
     * the exchange with the request redelivered, then dead-lettered, and the requester answered with nothing
     * at all. So the earlier decision did not deliver an echoed value to such a requester; it delivered
     * silence. The bound is applied at intake instead, and the requester receives its answer.</p>
     *
     * @param message the received message; must not be {@code null}
     * @return the correlation identifier, or an empty string, never {@code null}
     * @throws NullPointerException if {@code message} is {@code null}
     */
    private static String correlationId(Message<String> message) {
        String candidate = attribute(message, ATTRIBUTE_CORRELATION_ID);
        return candidate == null ? "" : candidate;
    }

    /**
     * Reads one message attribute as text.
     *
     * <p>Assumptions: a non-textual value reports as absent rather than being coerced. Every attribute this
     * flow reads is declared as a string by the producer contract, so a value of another type is a producer
     * defect, and treating it as absent lets the exchange continue on the baseline's own terms rather than
     * dead-lettering a request over an attribute.</p>
     *
     * @param message the received message; must not be {@code null}
     * @param name the attribute name; must not be {@code null}
     * @return the attribute value, or {@code null} when absent or not textual
     * @throws NullPointerException if {@code message} is {@code null}
     */
    private static String attribute(Message<String> message, String name) {
        Object value = message.getHeaders().get(name);
        return value instanceof String text ? text : null;
    }

    /**
     * Resolves a configured queue name to its address, caching the result.
     *
     * @param name the configured queue name; must not be {@code null}
     * @return the queue address, never {@code null}
     * @throws software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException if the queue is absent,
     *     which propagates rather than being defaulted because a consumer that cannot address its reply
     *     queue must not acknowledge a request it cannot answer
     * @throws NullPointerException if {@code name} is {@code null}, raised by the caching map, which refuses
     *     a null key. The precondition holds by construction: every name reaching here is a constructor
     *     argument already refused when blank, so a null names a defect in this class
     */
    private String queueUrl(String name) {
        return this.queueUrls.computeIfAbsent(name, queueName -> this.sqs
                .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                .queueUrl());
    }

    /**
     * Wraps a value as a textual message attribute.
     *
     * @param value the value; must not be {@code null}
     * @return the attribute, never {@code null}
     */
    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
