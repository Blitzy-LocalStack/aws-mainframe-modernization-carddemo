// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/DateConversionMessageListenerTest.java
// -----------------------------------------------------------------------------
// Purpose:
//      Holds the properties of app/app-vsam-mq/cbl/CODATE01.cbl that SPAN the
//      two types its migration was split into, and that therefore belong to
//      neither of their per-class suites. The queue half of that program is
//      DateInquiryMessageListener and the evaluation half is
//      DateConversionService; each has its own cases, and what neither can
//      assert alone is that the two still differ exactly where the reference
//      program and the reference date utility differ. Every collaborator is
//      substituted. Nothing here starts a Spring context, a listener container,
//      an emulator or a queue.
//
// WHY (non-obvious design decisions):
//  (1) Refactoring Rationale: THE TYPE THIS FILE IS NAMED FOR NO LONGER EXISTS,
//      and the name is kept rather than quietly retired because the withdrawal
//      is itself a property worth holding. A type named
//      DateConversionMessageListener declared a SECOND queue binding on the same
//      request queue as DateInquiryMessageListener, and the two answered with
//      different reply widths, different reply routing, different media types
//      and different requester-expiry handling, so which contract a message met
//      depended on which container polled it first. That is a nondeterministic
//      wire contract rather than redundancy. The queue side was withdrawn, its
//      one queue-independent member moved to DateConversionService, and the
//      supersession is recorded by that class and by
//      docs/architecture/messaging-contracts.md -- documents this file cites and
//      does not author. Re-creating the type would reinstate the defect and
//      would fail ReferenceQueueConsumerContractTest and
//      ReferenceServiceStructureTest, so the first case below asserts its
//      ABSENCE and the continued presence of both halves it was split into.
//  (2) Alternatives Considered: driving the surviving consumer through a real
//      queue instead of a substituted client. Rejected because the shared test
//      profile at src/test/resources/application-test.yml sets
//      spring.cloud.aws.sqs.listener.auto-startup to false precisely so that no
//      container starts and no receive call is made, and its closing register
//      records that the profile names no emulator endpoint and supplies no
//      credentials. A messaging integration would have to contribute both, and
//      it would prove delivery rather than the contract these cases are about.
//      Nothing in this file duplicates or contradicts a key in that profile;
//      this class loads no configuration at all and names its own queues.
//  (3) Assumptions: the reference program is STATELESS BY DECLARATION, not by
//      preference. Line 2 reads PROGRAM-ID. CODATE01 IS INITIAL., which gives
//      every invocation fresh working storage -- including the saved correlation
//      identifier at line 319 and the saved reply-to queue at line 320. A case
//      below drives one consumer instance three times and asserts that nothing
//      from one invocation reaches the next, so the target keeps a property the
//      baseline was granted by its own program header.
//  (4) Assumptions: THE REPLY IS INVARIANT TO REQUEST CONTENT, and the evidence
//      is exhaustive rather than illustrative. WS-FUNC is declared at line 110
//      and WS-KEY at line 111, and each appears at exactly ONE line in the whole
//      524-line file -- its declaration. No branch reads either. The processing
//      paragraph at line 339 blanks the reply, asks the clock at lines 343 to
//      345, formats it at lines 347 to 353, builds the answer at lines 355 to
//      360 and puts it at line 361, so the answer is a function of the clock
//      alone. The contrast is a DIFFERENT program: app/app-vsam-mq/cbl/COACCT01
//      .cbl line 393 reads IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES, which is the
//      only baseline site that routes on the function code. A case below asserts
//      the queue answer ignores its request while the evaluation answer depends
//      on its own, which is where the two halves legitimately diverge.
//  (5) Assumptions: no transactional outbox is asserted anywhere here, and its
//      absence is drawn from the baseline rather than from convenience. CODATE01
//      gets and puts inside ONE unit of work -- the syncpoint at line 276, the
//      get options at line 296, and the put options at lines 379 and 416 -- so
//      no window exists in which work is committed and the reply is lost. The
//      target discipline is delete-on-success plus the queue's visibility
//      timeout. An outbox belongs to the authorization context, and a case
//      asserting one here would assert a contract this flow does not have.
//  (6) Assumptions: the IBM system copybooks the reference program copies --
//      CMQGMOV, CMQPMOV, CMQMDV, CMQODV, CMQV and CMQTML -- are NOT in this
//      repository. They are neither read nor stubbed, which is why the message
//      descriptor's own field widths are not asserted here: nothing in this
//      repository declares them, so an assertion would be a guess wearing a
//      citation. What IS asserted is what the migrated attribute set carries.
//  (7) Assumptions: the four rationale labels used throughout this file are the
//      plural, unparenthesised, hyphen-minus forms taken from lines 31 to 34 of
//      the user-specified Explainability rule. The singular spellings that
//      appear in the reference-only trees denote these same four categories;
//      that equivalence is declared once, here, and the two forms are never
//      mixed. Line 548 of the reference-only house document renders the
//      compromise label with a non-breaking hyphen and a typographic dash, both
//      indistinguishable on screen from the ASCII forms and both behaving
//      differently in a search. This file is pure ASCII, which puts the hazard
//      out of reach rather than relying on care.
//  (8) Trade-offs: this class is the twelfth test in its directory, and the
//      volatile inventory paragraph of the charter one package ABOVE it credits
//      this package with ten. That paragraph carries no marker line, is bound by
//      no build gate, and had already fallen behind by several classes before
//      this one landed; the charter beside this file carries the marker line
//      that common-lib's PackageCharterInventoryTest re-measures on every build,
//      and that line is updated with this class. The discrepancy in the outer
//      paragraph is recorded here so a reader who checks it finds it explained
//      rather than fresh.
// =============================================================================
package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import com.carddemo.reference.mapper.DateInquiryReplyMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Asserts the properties of the date flow that span the two types its migration was split into.
 *
 * <h2>What this class answers for, and what it deliberately leaves alone</h2>
 *
 * <p>Assumptions: the per-class suites already own their own halves, and nothing here restates them.
 * {@code DateInquiryMessageListenerTest} owns the queue half's own behaviour -- the reply's exact
 * bytes, the echoed correlation identifier, the ignored sender-supplied destination, the dropped
 * expired request and the reported failure. {@code DateConversionServiceTest} owns the evaluation
 * half's wiring of mask defaulting and verdict assembly. {@code DateInquiryReplyMapperTest} owns the
 * reply body's labels, ordering and padding, and {@code ReferenceFixtureTest} owns the committed
 * request fixtures' geometry. What none of them can own is a property that is only visible when both
 * halves are in view at once, and those are what this class holds.</p>
 *
 * <p>Assumptions: there is NO GOLDEN MASTER for this flow. The repository's oracle covers the batch
 * programs, and a queue-triggered CICS transaction cannot be run on the runner at all, so the
 * evidence for every claim below is a cited reference line plus arithmetic over declared widths.
 * That is stated rather than implied, because an assertion presented as oracle-backed when it is
 * transcription-backed overstates what a green run proves.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each member below carries its own.</p>
 */
@DisplayName("the date flow across the two halves of the withdrawn consumer")
class DateConversionMessageListenerTest {

    /**
     * The binary name the withdrawn queue consumer carried.
     *
     * <p>Assumptions: stated as a string rather than as a type reference, which is the only form
     * available: a type reference to a class that must not exist would not compile, and that is the
     * property the first case is here to hold.</p>
     */
    private static final String WITHDRAWN_CONSUMER =
            "com.carddemo.reference.service.DateConversionMessageListener";

    /**
     * The configured request queue name, which nothing in this class may address as a destination.
     */
    private static final String REQUEST_QUEUE = "reference-test-request";

    /** The configured reply queue name. */
    private static final String REPLY_QUEUE = "reference-test-reply";

    /** The configured error queue name. */
    private static final String ERROR_QUEUE = "reference-test-error";

    /** The address the substituted client resolves the request queue to. */
    private static final String REQUEST_URL = "https://sqs.test.invalid/queue/reference-test-request";

    /** The address the substituted client resolves the reply queue to. */
    private static final String REPLY_URL = "https://sqs.test.invalid/queue/reference-test-reply";

    /** The address the substituted client resolves the error queue to. */
    private static final String ERROR_URL = "https://sqs.test.invalid/queue/reference-test-error";

    /**
     * The instant every reply in this class is rendered from.
     *
     * <p>Assumptions: the day-of-month is deliberately past the twelfth. The reference program emits
     * a month-first date -- {@code MMDDYYYY(WS-MMDDYYYY)} with {@code DATESEP('-')} at lines 349 and
     * 350, into the {@code PIC X(10)} field declared at line 37 -- while every validated date in this
     * migration is year-first at the same ten characters. For the first twelve days of any month the
     * two orderings are indistinguishable, so an instant inside that window would let a case pass
     * against either form. The eighteenth cannot.</p>
     */
    private static final Instant NOW = Instant.parse("2022-07-18T09:04:05Z");

    /**
     * The forty-six characters the reply body carries for {@link #NOW}.
     *
     * <p>Assumptions: composed from the two labels at lines 355 and 356, each fourteen characters
     * including the space that follows its colon, the ten-character month-first date and the
     * eight-character time. It is stated as one literal rather than assembled from the renderer's
     * constants so that a change to either label fails against the contract instead of following
     * it.</p>
     */
    private static final String EXPECTED_BODY = "SYSTEM DATE : 07-18-2022SYSTEM TIME : 09:04:05";

    /**
     * The message attribute the requester's correlation identifier arrives and departs under.
     */
    private static final String CORRELATION_ID = "correlationId";

    /** The message attribute the reply's media type is declared under. */
    private static final String CONTENT_TYPE = "contentType";

    /** A well-formed candidate date in the year-first picture, taken from the baseline's own run date. */
    private static final String VALID_ISO_DATE = "2022-07-18";

    /** A well-formed ten-character candidate naming a day that does not exist. */
    private static final String UNUSABLE_ISO_DATE = "2022-02-30";

    /** The substituted queue client both publish paths address. */
    private SqsClient sqs;

    /** The surviving queue half of the withdrawn consumer. */
    private DateInquiryMessageListener queueHalf;

    /** The surviving evaluation half of the withdrawn consumer. */
    private DateConversionService evaluationHalf;

    /**
     * Builds both halves over a substituted client that resolves all three configured queue names.
     *
     * <p>Assumptions: the client resolves the REQUEST queue as well as the two destinations, even
     * though no case may address it. Resolving it is what makes the negative assertion meaningful:
     * if the client answered only the two destinations, a send to the request queue would fail on an
     * unresolvable name and the case would pass for the wrong reason.</p>
     */
    @BeforeEach
    void setUp() {
        this.sqs = mock(SqsClient.class);
        when(this.sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenAnswer(invocation -> GetQueueUrlResponse.builder()
                        .queueUrl(urlOf(invocation.<GetQueueUrlRequest>getArgument(0).queueName()))
                        .build());
        // WHY : Assumptions: the accepted send answers with an identifier the TRANSPORT chose, not
        //      one any request carried. That is the fact the closed-attribute-set case rests on: the
        //      transport already names every message it accepts, so a reply that also republished the
        //      request's identifier would carry two under one name.
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("sqs-assigned-1").build());

        this.queueHalf = new DateInquiryMessageListener(new DateInquiryReplyMapper(), this.sqs,
                REPLY_QUEUE, ERROR_QUEUE, Clock.fixed(NOW, ZoneOffset.UTC));
        this.evaluationHalf = new DateConversionService();
    }

    /**
     * Resolves one configured queue name to the address this class substitutes for it.
     *
     * @param queueName the configured queue name the consumer asked to resolve
     * @return the substituted address for that name, never {@code null}
     * @throws IllegalArgumentException if the name is none of the three this class configures, which
     *     would mean the consumer addressed a queue no deployment gave it and must fail loudly
     *     rather than resolve to a plausible default
     */
    private static String urlOf(String queueName) {
        if (REPLY_QUEUE.equals(queueName)) {
            return REPLY_URL;
        }
        if (ERROR_QUEUE.equals(queueName)) {
            return ERROR_URL;
        }
        if (REQUEST_QUEUE.equals(queueName)) {
            return REQUEST_URL;
        }
        throw new IllegalArgumentException("no queue named " + queueName + " is configured for this"
                + " class, so resolving it would invent a destination the deployment never provisions");
    }

    /**
     * Frames one request payload from a function code and a key at the reference program's widths.
     *
     * <p>Assumptions: the two operands are concatenated and the remainder is space padded, because
     * that is what the record's own declaration produces -- the filler at line 112 carries
     * {@code VALUE SPACES}, so a producer sending fewer than a thousand characters is sending spaces
     * for the rest rather than zeros.</p>
     *
     * @param function the four characters that occupy the function field, at that exact width
     * @param key the eleven characters that occupy the key field, at that exact width
     * @return the framed thousand-character payload, never {@code null}
     * @throws IllegalArgumentException if either operand is not its declared width, raised here
     *     rather than left to the codec so that a mis-sized fixture is reported against the field
     *     that is wrong instead of against the record length
     */
    private static String payload(String function, String key) {
        if (function.length() != InquiryRequestCodec.FUNCTION_WIDTH
                || key.length() != InquiryRequestCodec.KEY_WIDTH) {
            throw new IllegalArgumentException("a request is composed of a "
                    + InquiryRequestCodec.FUNCTION_WIDTH + "-character function and a "
                    + InquiryRequestCodec.KEY_WIDTH + "-character key, but received "
                    + function.length() + " and " + key.length());
        }
        return InquiryRequestCodec.frame(function + key);
    }

    /**
     * Wraps a payload and its attributes as a received message.
     *
     * @param body the message payload the consumer receives
     * @param attributes the message attributes the consumer reads its correlation identifier and
     *     optional expiry from
     * @return the received message, never {@code null}
     */
    private static Message<String> received(String body, Map<String, Object> attributes) {
        return MessageBuilder.withPayload(body).copyHeaders(attributes).build();
    }

    /**
     * Captures every send the substituted client was asked to perform, in the order asked.
     *
     * @param expected the number of sends this case expects, asserted as an exact count so that an
     *     extra send is a failure rather than an unexamined extra
     * @return the captured send requests in call order, never {@code null}
     * @throws org.mockito.exceptions.verification.TooFewActualInvocations if fewer sends occurred
     * @throws org.mockito.exceptions.verification.TooManyActualInvocations if more sends occurred
     */
    private List<SendMessageRequest> sends(int expected) {
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(expected)).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    /**
     * Declares the request record's geometry independently, straight from the copybook.
     *
     * <p>Purpose: this is the INDEPENDENT WITNESS the offset cross-check needs. Its three fields are
     * transcribed from {@code app/app-vsam-mq/cbl/CODATE01.cbl} lines 110 to 112 -- a four-character
     * function code, an eleven-digit key and nine hundred and eighty-five characters of space filler
     * -- and {@link CopybookLayout.RecordSpec#validateGeometry()} proves they tile the thousand
     * characters line 50 declares with no gap and no overlap.</p>
     *
     * <p>Trade-offs: a layout transcribed twice is normally a hazard, and {@link CopybookLayout} says
     * so in its own documentation. Here the second transcription is the INSTRUMENT rather than the
     * hazard: the cross-check has power only because one side comes from the copybook and the other
     * from the widths {@link InquiryRequestCodec} publishes, and a single shared source would make the
     * comparison agree with itself. The accepted cost is that this declaration must be kept in step
     * with the copybook, and the case that consumes it fails the moment it is not.</p>
     *
     * <p>Assumptions: the record is declared KEYLESS. The reference program retrieves it from a queue
     * and not by key -- its get names a queue at line 289, a handle at line 290 and a buffer length
     * of a thousand at line 291, with no key operand anywhere -- so {@code NO_RETRIEVAL_KEY} is the
     * honest key geometry for a message buffer. The sibling declaration in the fixtures package
     * carries an eleven-character key at offset four for its own file sweep; the FIELD geometry is
     * identical in both, the key component is the only difference, and neither the codec nor this
     * class reads it.</p>
     *
     * @return the validated request layout, never {@code null}
     * @throws CopybookLayout.LayoutException if the three fields do not tile the declared record
     *     length exactly, which would mean this transcription has fallen out of step with the
     *     copybook it cites
     */
    private static CopybookLayout.RecordSpec requestLayout() {
        return CopybookLayout.RecordSpec.keyless("REQUEST-MSG-COPY", 1000, List.of(
                CopybookLayout.text("WS-FUNC", 0, 4),
                CopybookLayout.uint("WS-KEY", 4, 11),
                CopybookLayout.text("WS-FILLER", 15, 985)));
    }

    /**
     * Reports every queue-name expression the consumer takes from configuration.
     *
     * <p>Assumptions: the expressions are read from the constructor's parameter annotations rather
     * than from resolved values, because a resolved value is whatever a profile happened to supply
     * and the property under assertion is that the NAME is external at all. The reference program
     * hard-codes two of its three: the reply queue literal at line 147 and the error queue literal at
     * line 243.</p>
     *
     * @return one entry per annotated constructor parameter, in declaration order, never {@code null}
     */
    private static List<String> configuredQueueExpressions() {
        Constructor<?>[] constructors = DateInquiryMessageListener.class.getDeclaredConstructors();
        assertThat(constructors)
                .as("the consumer declares one constructor, so there is one place its queue names"
                        + " can enter from; a second constructor could bypass the annotated one")
                .hasSize(1);

        List<String> expressions = new ArrayList<>();
        for (Annotation[] onOneParameter : constructors[0].getParameterAnnotations()) {
            for (Annotation candidate : onOneParameter) {
                if (candidate instanceof Value external) {
                    expressions.add(external.value());
                }
            }
        }
        return expressions;
    }

    /**
     * Reports the queue-binding annotation the surviving consumer carries.
     *
     * @return the annotation on the one listening member, never {@code null}
     * @throws NoSuchMethodException if the listening member is absent or its signature changed, which
     *     is a structural failure rather than an assertion failure and is reported as one
     */
    private static SqsListener queueBinding() throws NoSuchMethodException {
        SqsListener binding = DateInquiryMessageListener.class
                .getDeclaredMethod("onRequest", Message.class)
                .getAnnotation(SqsListener.class);
        assertThat(binding)
                .as("the surviving consumer's listening member must carry the queue binding; without"
                        + " it the flow has no consumer and every request would age out unanswered")
                .isNotNull();
        return binding;
    }

    /**
     * Confirms the withdrawn consumer is gone and both halves it was split into remain.
     *
     * <p>Purpose: this is the case that justifies the file's name. The withdrawn type is asserted
     * absent by binary name, and the two members it used to carry are asserted present on the two
     * types that now hold them, each on the correct side of the split -- the queue member carrying
     * the binding and the evaluation member carrying none.</p>
     *
     * <p>Alternatives Considered: asserting only that one binding exists, which is what the two
     * structural guards beside this file already do. Rejected as insufficient on its own: a
     * reinstated consumer could carry the same name and no binding, or a binding on a second queue,
     * and both would satisfy a count while restoring the two-implementations-of-one-contract problem
     * that the withdrawal removed. Naming the withdrawn type is the only assertion that fails on its
     * return, and it is deliberately paired with the presence of both halves so that a reader cannot
     * satisfy it by deleting functionality.</p>
     *
     * @throws NoSuchMethodException if either surviving member is absent or its signature changed,
     *     which means the split lost a half rather than relocating it
     */
    @Test
    @DisplayName("the withdrawn consumer is absent and both of its halves remain")
    void theWithdrawnConsumerIsAbsentAndBothOfItsHalvesRemain() throws NoSuchMethodException {
        assertThatThrownBy(() -> Class.forName(WITHDRAWN_CONSUMER))
                .as("a second consumer of one request queue makes the contract a message meets depend"
                        + " on which container polled it first, which is why this type was withdrawn")
                .isInstanceOf(ClassNotFoundException.class);

        Method queueMember = DateInquiryMessageListener.class
                .getDeclaredMethod("onRequest", Message.class);
        Method evaluationMember = DateConversionService.class
                .getDeclaredMethod("convert", DateConversionRequest.class);

        assertThat(queueMember.getAnnotation(SqsListener.class))
                .as("the queue half keeps the binding, so the flow still has exactly one consumer")
                .isNotNull();
        assertThat(evaluationMember.getAnnotation(SqsListener.class))
                .as("the evaluation half addresses no queue: it is the one member of the withdrawn"
                        + " type that had no queue concern in it, which is why it survived the removal")
                .isNull();
        assertThat(evaluationMember.getReturnType())
                .as("the evaluation half still answers the published verdict shape")
                .isEqualTo(DateConversionResponse.class);
    }

    /**
     * Confirms the copybook geometry and the shared codec agree on every declared offset.
     *
     * <p>Purpose: two independent transcriptions of one layout exist in this system -- the field
     * descriptors this class builds from {@code app/app-vsam-mq/cbl/CODATE01.cbl} lines 110 to 112,
     * and the widths {@link InquiryRequestCodec} publishes and slices by. Nothing compared them. This
     * case does, on both the geometry and one decoded record.</p>
     *
     * <p>Assumptions: an offset disagreement between two transcriptions does not raise. It presents
     * as a field that is silently one character short, decoding to a plausible value at every field
     * after it, which is exactly the failure the shared codec's own documentation warns of and
     * exactly the failure no single-sided case can see.</p>
     *
     * <p>Assumptions: the key is compared as a NUMBER on one side and as characters on the other,
     * because the two transcriptions type it differently on purpose. The codec carries the key's raw
     * characters so a non-numeric key can still be answered rather than raised on, while the field
     * descriptor declares an unsigned display integer because the copybook declares
     * {@code PIC 9(11)}. The comparison therefore goes through the codec's own numeric query, which
     * is the only reading in which the two are the same value.</p>
     */
    @Test
    @DisplayName("the copybook geometry and the shared codec agree on every declared offset")
    void theTwoRequestDecodersAgreeOnEveryDeclaredOffset() {
        CopybookLayout.RecordSpec layout = requestLayout();

        assertThat(layout.reclen())
                .as("line 50 declares a PIC X(1000) buffer and the codec publishes the same length")
                .isEqualTo(InquiryRequestCodec.MESSAGE_LENGTH);
        assertThat(layout.field("WS-FUNC").start()).isZero();
        assertThat(layout.field("WS-FUNC").length())
                .isEqualTo(InquiryRequestCodec.FUNCTION_WIDTH);
        assertThat(layout.field("WS-KEY").start())
                .as("the key begins where the four-character function code ends")
                .isEqualTo(InquiryRequestCodec.FUNCTION_WIDTH);
        assertThat(layout.field("WS-KEY").length()).isEqualTo(InquiryRequestCodec.KEY_WIDTH);
        assertThat(layout.field("WS-FILLER").start())
                .as("the filler begins where the function code and the key end")
                .isEqualTo(InquiryRequestCodec.FUNCTION_WIDTH + InquiryRequestCodec.KEY_WIDTH);
        assertThat(layout.field("WS-FILLER").length()).isEqualTo(InquiryRequestCodec.FILLER_WIDTH);

        String request = payload("DATE", "00000000001");

        // WHY : Assumptions: the payload is handed over as US-ASCII bytes because that is the charset
        //      the record codec reads a character field under by default. Encoding as UTF-8 would
        //      agree with it for this content and stop agreeing the moment a field carried a byte
        //      above the ASCII range, which is a disagreement that shows up as a shifted field rather
        //      than as an error.
        Map<String, Object> byGeometry = FixedWidthCodec.decodeRecord(
                request.getBytes(StandardCharsets.US_ASCII), layout);
        InquiryRequestCodec.InquiryRequest byCodec = InquiryRequestCodec.decode(request);

        assertThat(byGeometry.get("WS-FUNC"))
                .as("the same bytes read through the copybook geometry and through the codec")
                .isEqualTo(byCodec.function());
        assertThat(byCodec.hasUsableKey())
                .as("the key has to be readable as a number for the two sides to be comparable")
                .isTrue();
        assertThat(byGeometry.get("WS-KEY")).isEqualTo(byCodec.keyValue());
        assertThat(byGeometry.get("WS-FILLER"))
                .as("line 112 declares VALUE SPACES, so an under-length payload pads with spaces")
                .isEqualTo(" ".repeat(InquiryRequestCodec.FILLER_WIDTH));
    }

    /**
     * Confirms nothing from one invocation reaches the next on a single consumer instance.
     *
     * <p>Purpose: this is the target form of the program header at line 2, which declares
     * {@code CODATE01 IS INITIAL} and so is granted fresh working storage on every invocation. The
     * fields that would leak if it were not are named in the baseline: the correlation identifier
     * saved at line 319, the reply-to queue saved at line 320, the message identifier saved at line
     * 321 and the request copy taken at line 322.</p>
     *
     * <p>Assumptions: the middle invocation supplies NO correlation identifier, which is what makes
     * the case able to fail. A consumer that carried the first invocation's saved identifier forward
     * would answer the second with it, and three invocations that each supplied one could not tell
     * that apart from correct behaviour.</p>
     *
     * <p>Assumptions: this is asserted separately from the address caching the queue half's own suite
     * covers. Caching a resolved address deliberately DOES persist between invocations; a correlation
     * identifier deliberately does not, and a case that counted resolutions would not notice the
     * difference between the two kinds of retained value.</p>
     */
    @Test
    @DisplayName("no state carries from one invocation to the next")
    void noStateCarriesFromOneInvocationToTheNext() {
        this.queueHalf.onRequest(received(payload("DATE", "00000000001"),
                Map.of(CORRELATION_ID, "cid-first")));
        this.queueHalf.onRequest(received(payload("DTE ", "00000000002"), Map.of()));
        this.queueHalf.onRequest(received(payload("XXXX", "00000000003"),
                Map.of(CORRELATION_ID, "cid-third")));

        List<SendMessageRequest> replies = sends(3);

        assertThat(replies.get(0).messageAttributes().get(CORRELATION_ID).stringValue())
                .isEqualTo("cid-first");
        assertThat(replies.get(1).messageAttributes())
                .as("the second request supplied no identifier, so the reply must carry none; the"
                        + " first invocation's saved value must not survive into it")
                .containsOnlyKeys(CONTENT_TYPE);
        assertThat(replies.get(2).messageAttributes().get(CORRELATION_ID).stringValue())
                .isEqualTo("cid-third");

        assertThat(replies).extracting(SendMessageRequest::messageBody)
                .as("one clock serves all three, so three identical bodies show no counter, no"
                        + " accumulated text and no earlier request leaking into a later answer")
                .containsExactly(InquiryRequestCodec.frame(EXPECTED_BODY),
                        InquiryRequestCodec.frame(EXPECTED_BODY),
                        InquiryRequestCodec.frame(EXPECTED_BODY));
    }

    /**
     * Confirms the queue answer ignores its request while the evaluation answer depends on its own.
     *
     * <p>Purpose: this is the property the split exists to keep visible, and it is the one property
     * neither half's own suite can state. The two halves are NOT two routes to one rule. The queue
     * route reads no field of its request -- {@code WS-FUNC} and {@code WS-KEY} are declared at lines
     * 110 and 111 and appear at no other line in the file -- so it answers every message alike. The
     * evaluation route takes its date and its picture as parameters, exactly as the date utility's
     * linkage at {@code app/cbl/CSUTLDTC.cbl} line 88 does, so its answer changes with its input.</p>
     *
     * <p>Assumptions: the contrast is drawn against a DIFFERENT program rather than against a branch
     * of this one. {@code app/app-vsam-mq/cbl/COACCT01.cbl} line 393 reads
     * {@code IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES} and is the only baseline site that routes on
     * the function code; that program needs a key because it looks something up, and this one does
     * not because its answer is the clock. Imposing one flow's guard on the other would refuse
     * requests the baseline answers, dressed as consistency.</p>
     *
     * <p>Refactoring Rationale: if request-driven conversion were ever added to the queue route it
     * would be an EXTENSION beyond {@code CODATE01} and would have to be documented as one in
     * {@code docs/architecture/cobol-to-service-traceability.md}. It has not been, and this case is
     * what would fail if it were added silently.</p>
     */
    @Test
    @DisplayName("the queue answer ignores its request while the evaluation answer depends on its own")
    void theQueueReplyIgnoresRequestContentWhileTheVerdictDependsOnIt() {
        this.queueHalf.onRequest(received(payload("DATE", "00000000001"), Map.of()));
        this.queueHalf.onRequest(received(payload("    ", "00000000000"), Map.of()));

        assertThat(sends(2)).extracting(SendMessageRequest::messageBody)
                .as("a named function with a usable key and a blank function with a zero key are"
                        + " answered identically, because no branch reads either field")
                .containsExactly(InquiryRequestCodec.frame(EXPECTED_BODY),
                        InquiryRequestCodec.frame(EXPECTED_BODY));

        DateConversionResponse onValid =
                this.evaluationHalf.convert(new DateConversionRequest(VALID_ISO_DATE, null));
        DateConversionResponse onUnusable =
                this.evaluationHalf.convert(new DateConversionRequest(UNUSABLE_ISO_DATE, null));

        assertThat(onValid.severity()).isEqualTo(DateEditValidator.SEVERITY_VALID);
        assertThat(onUnusable.severity())
                .as("the evaluation route is the content-driven half: two candidates that differ only"
                        + " in their day must not receive one verdict")
                .isNotEqualTo(onValid.severity());
        assertThat(onUnusable.verdict()).isNotEqualTo(onValid.verdict());
    }

    /**
     * Confirms the reply and the diagnostic address two distinct queues and never the request queue.
     *
     * <p>Purpose: the reference program has TWO put paths and a suite that covered one would leave
     * half the flow unproven. The reply put is at line 383 against the handle named at line 384,
     * opened from the reply queue at line 210; the diagnostic put is at line 420 against the handle
     * named at line 421, opened from the error queue named at line 243. This case fixes that they
     * remain two destinations rather than one.</p>
     *
     * <p>Assumptions: the case asserts the DESTINATION SEPARATION and leaves the ordering and the
     * propagation of a failed reply to the queue half's own suite, which owns them. Separation is a
     * different property: a consumer that reported failures onto its own reply queue would still
     * report, still propagate and still be observed in the right order, and only a case that compares
     * the two addresses would see it.</p>
     *
     * <p>Assumptions: the request queue is asserted never to be ADDRESSED and never to be RESOLVED.
     * Resolving it would be the first half of the mistake, and asserting only the send would let a
     * consumer resolve it and then not use it, which is a state a later change can complete.</p>
     */
    @Test
    @DisplayName("the reply and the diagnostic address two distinct queues, never the request queue")
    void theReplyAndTheDiagnosticAddressTwoDistinctQueuesAndNeverTheRequestQueue() {
        SdkClientException replyFailed = SdkClientException.create("the reply queue is unreachable");

        // WHY : Alternatives Considered: failing EVERY send, which is what an unreachable transport
        //      would really do. Rejected for this case: the diagnostic would fail too, its own
        //      destination would never be recorded, and the address comparison this case exists to
        //      make would have nothing to compare. Failing only the first send is what leaves the
        //      second one observable. The all-sends-fail shape is a different property and belongs to
        //      the suppression cases in the queue half's own suite, which own it.
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(replyFailed)
                .thenReturn(SendMessageResponse.builder().messageId("sqs-assigned-2").build());

        assertThatThrownBy(() ->
                this.queueHalf.onRequest(received(payload("DATE", "00000000001"), Map.of())))
                .isSameAs(replyFailed);

        List<SendMessageRequest> both = sends(2);
        assertThat(both.get(0).queueUrl())
                .as("the answer goes to the configured reply queue")
                .isEqualTo(REPLY_URL);
        assertThat(both.get(1).queueUrl())
                .as("the diagnostic goes to the configured error queue, which the baseline opens as a"
                        + " separate object from the reply queue")
                .isEqualTo(ERROR_URL);
        assertThat(both).extracting(SendMessageRequest::queueUrl)
                .as("two put paths, two destinations, and neither of them the request queue")
                .doesNotContain(REQUEST_URL)
                .doesNotHaveDuplicates();

        ArgumentCaptor<GetQueueUrlRequest> resolved =
                ArgumentCaptor.forClass(GetQueueUrlRequest.class);
        verify(this.sqs, times(2)).getQueueUrl(resolved.capture());
        assertThat(resolved.getAllValues()).extracting(GetQueueUrlRequest::queueName)
                .as("only the two destinations are resolved; the request queue is consumed from and"
                        + " is never an address this consumer holds")
                .containsExactly(REPLY_QUEUE, ERROR_QUEUE);
    }

    /**
     * Confirms the reply carries a closed attribute set and asserts no message identifier of its own.
     *
     * <p>Purpose: the reference program echoes BOTH saved identifiers onto its reply descriptor --
     * the message identifier at line 373 and the correlation identifier at line 374. Only one of the
     * two crosses as an attribute, and this case fixes which, so the asymmetry is asserted rather
     * than discovered.</p>
     *
     * <p>Assumptions: the message identifier is NOT re-published, and that is a property of the
     * target transport rather than a lapse. The transport assigns a message identifier to every
     * message it accepts, so a reply carrying a copy of the request's would carry two identifiers
     * with one name and leave a consumer to guess which it had. The requester's own correlation
     * identifier is what matches an answer to a request, and it crosses verbatim. The divergence
     * belongs in {@code docs/architecture/cobol-to-service-traceability.md}, a document this file
     * cites and does not author.</p>
     *
     * <p>Assumptions: the assertion is a CLOSED key set rather than a presence check, which is the
     * only form that can fail. A presence check on the correlation identifier passes whatever else is
     * added beside it, and the property here is that nothing else is.</p>
     */
    @Test
    @DisplayName("the reply carries a closed attribute set with no message identifier")
    void theReplyCarriesAClosedAttributeSetWithNoMessageIdentifier() {
        this.queueHalf.onRequest(received(payload("DATE", "00000000001"),
                Map.of(CORRELATION_ID, "cid-echoed", "messageId", "producer-assigned-77")));

        SendMessageRequest reply = sends(1).get(0);

        assertThat(reply.messageAttributes())
                .as("exactly the media type and the requester's correlation identifier; a request that"
                        + " also supplied a message identifier does not add a third attribute")
                .containsOnlyKeys(CONTENT_TYPE, CORRELATION_ID);
        assertThat(reply.messageAttributes().get(CORRELATION_ID).stringValue())
                .as("echoed verbatim, because it is the requester's own value and an altered one is"
                        + " unmatchable at the far end")
                .isEqualTo("cid-echoed");
    }

    /**
     * Confirms all three queue names arrive from configuration and carry no baseline literal.
     *
     * <p>Purpose: the reference program hard-codes two of its three queue names -- the reply queue
     * literal {@code 'CARD.DEMO.REPLY.DATE'} at line 147 and the error queue literal
     * {@code 'CARD.DEMO.ERROR'} at line 243 -- and takes only the request queue from outside, from
     * the trigger message at line 146. The target takes all three from configuration, and this case
     * fixes that neither literal was carried across and that none of the three has an in-code
     * fallback.</p>
     *
     * <p>Assumptions: the inquiry queues are STANDARD queues, and the target names could not be the
     * baseline's even if the literals were wanted. The transport's naming rules exclude the separator
     * those literals are built from, and the flow needs no per-message ordering or duplicate
     * suppression: the answer is the clock, so two answers to one request are the same answer. The
     * consumer accordingly populates no ordering or deduplication operand on either put, which is
     * readable in its own publish members and fenced by the exact-expression guard beside this
     * file.</p>
     *
     * <p>Assumptions: an in-code default is asserted ABSENT for all three names, and that is the
     * substantive half of the claim. A placeholder carrying a fallback is a literal with extra steps
     * -- it would let a deployment that set nothing still start, addressing a queue chosen in source,
     * which is the outcome taking the names from configuration exists to prevent.</p>
     *
     * @throws NoSuchMethodException if the listening member is absent or its signature changed
     */
    @Test
    @DisplayName("all three queue names arrive from configuration and carry no baseline literal")
    void theThreeQueueNamesArriveFromConfigurationAndCarryNoBaselineLiteral()
            throws NoSuchMethodException {
        List<String> expressions = new ArrayList<>(configuredQueueExpressions());
        assertThat(expressions)
                .as("the reply and the error queue names, the two the baseline states in source")
                .containsExactly("${carddemo.reference.inquiry.reply-queue}",
                        "${carddemo.reference.inquiry.error-queue}");

        // WHY : Assumptions: the listening member's own expression is APPENDED to the constructor's two
        //      rather than checked apart from them, because the property is about all three names
        //      together. Two names taken from configuration and a third written in source would still
        //      satisfy two separate assertions while leaving one destination chosen in the code.
        expressions.addAll(List.of(queueBinding().queueNames()));
        assertThat(expressions)
                .as("the request queue name joins them, so all three are external")
                .hasSize(3);

        for (String expression : expressions) {
            assertThat(expression)
                    .as("%s must be a property reference rather than a name chosen in source", expression)
                    .startsWith("${")
                    .endsWith("-queue}");
            assertThat(expression.indexOf(':'))
                    .as("%s must carry no in-code fallback, so a deployment that supplies no name"
                            + " fails to start instead of addressing a queue named in source", expression)
                    .isEqualTo(-1);
            assertThat(expression)
                    .as("%s must not carry either baseline queue literal across", expression)
                    .doesNotContain("CARD.DEMO.REPLY.DATE")
                    .doesNotContain("CARD.DEMO.ERROR");
        }
    }

    /**
     * Confirms the receive wait is the five seconds the reference program waits.
     *
     * <p>Purpose: line 286 reads {@code MOVE 5000 TO MQGMO-WAITINTERVAL}, and the operand is
     * MILLISECONDS, so the baseline waits five seconds for a message. The target carries the same
     * quantity as the listening member's poll timeout. The value is asserted here because no other
     * case in this module reads it, and a wait silently reduced to zero would turn every empty poll
     * into a billed short poll while every functional case still passed.</p>
     *
     * <p>Assumptions: the value is asserted through the ANNOTATION's default segment rather than
     * through a started container, because the annotation is where the quantity is written down and a
     * container would resolve whatever a profile supplied instead. The deployed profile states the
     * same five seconds as the container-wide poll timeout; both are configuration, and neither is a
     * number chosen in Java.</p>
     *
     * @throws NoSuchMethodException if the listening member is absent or its signature changed
     */
    @Test
    @DisplayName("the receive wait is the five seconds the reference program waits")
    void theReceiveWaitIsTheFiveSecondsTheBaselineWaits() throws NoSuchMethodException {
        String expression = queueBinding().pollTimeoutSeconds();

        assertThat(expression)
                .as("the poll timeout is external, with the baseline's own quantity as its default")
                .isEqualTo("${carddemo.reference.inquiry.poll-timeout-seconds:5}");
        assertThat(expression.substring(expression.indexOf(':') + 1, expression.length() - 1))
                .as("five seconds, being the 5000 milliseconds line 286 moves into the wait interval")
                .isEqualTo("5");
    }

    /**
     * Confirms the two ten-character date forms in this flow are not interchangeable.
     *
     * <p>Purpose: this flow carries TWO different ten-character date pictures and they must never be
     * merged. The queue route emits month-first, from {@code MMDDYYYY(WS-MMDDYYYY)} with
     * {@code DATESEP('-')} at lines 349 and 350 into the {@code PIC X(10)} field at line 37. The
     * evaluation route reads year-first, which is the picture the shared validator publishes as its
     * default and the ordering
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} lines 108 to 114 redefine a ten-character
     * field into -- four characters of year, a separator, two of month, a separator, two of day.</p>
     *
     * <p>Trade-offs: two pictures of identical width is the specific compromise accepted here, and it
     * is accepted in exchange for emitting and reading each value in the form its own reference
     * source declares. The cost is real and is stated rather than smoothed over: the two forms are
     * indistinguishable for the first twelve days of any month, so a change that normalised one into
     * the other would appear to work in most tests and would silently alter a value the far end reads
     * by offset. The instant this class renders is deliberately outside that window, which is what
     * turns the compromise into something a case can hold.</p>
     *
     * <p>Assumptions: interchangeability is disproved in the direction that matters. The emitted
     * month-first value, read back under the year-first picture, must NOT be reported acceptable --
     * that is what makes them two contracts rather than two spellings of one.</p>
     */
    @Test
    @DisplayName("the two ten-character date forms are not interchangeable")
    void theTwoTenCharacterDateFormsAreNotInterchangeable() {
        this.queueHalf.onRequest(received(payload("DATE", "00000000001"), Map.of()));
        String emitted = sends(1).get(0).messageBody();

        assertThat(emitted)
                .as("the emitted date is month-first, as lines 349 and 350 request it")
                .contains("07-18-2022");
        assertThat(emitted)
                .as("and is therefore NOT the year-first form the evaluation route reads")
                .doesNotContain(VALID_ISO_DATE);

        assertThat("07-18-2022".length())
                .as("both forms are ten characters, which is precisely why they can be confused")
                .isEqualTo(VALID_ISO_DATE.length())
                .isEqualTo(DateEditValidator.MASKED_DATE_LENGTH);

        assertThat(DateEditValidator.evaluateWithLanguageEnvironment(
                        "07-18-2022", DateEditValidator.DATE_FORMAT_MASK).acceptable())
                .as("the emitted form read under the evaluation picture is not acceptable, so the two"
                        + " ten-character pictures are two contracts and never one")
                .isFalse();
        assertThat(DateEditValidator.evaluateWithLanguageEnvironment(
                        VALID_ISO_DATE, DateEditValidator.DATE_FORMAT_MASK).acceptable())
                .as("the same instant in the evaluation route's own picture is acceptable")
                .isTrue();
    }

    /**
     * Confirms the evaluation route reports exactly what the shared validator reports.
     *
     * <p>Purpose: this is a DELEGATION proof rather than a wiring proof. The evaluation half must
     * restate no date rule of its own, and the way to assert that is to compare its answer against
     * the shared validator's answer for the same operands, member for member, rather than against
     * literals that would pass equally well against a local reimplementation that happened to agree
     * on the cases chosen.</p>
     *
     * <p>Refactoring Rationale: one shared implementation is the FAITHFUL shape here and not a
     * liberty taken for tidiness. The date utility at {@code app/cbl/CSUTLDTC.cbl} was itself a
     * dynamically called shared subprogram: its procedure division at line 88 takes the date, the
     * picture and a result, it forwards its severity to the return code at line 98 and hands control
     * back at line 100. Re-expressing it as one shared callable re-expresses the baseline's own
     * mechanism; transcribing its rules into each caller would not.</p>
     *
     * <p>Assumptions: the queue route is NOT part of this claim. A search for {@code CSUTLDTC} across
     * all 524 lines of {@code app/app-vsam-mq/cbl/CODATE01.cbl} returns zero occurrences, so date
     * validation and date-and-time emission are separate contracts in the baseline and asserting them
     * as one here would invent a relationship the reference programs do not have.</p>
     */
    @Test
    @DisplayName("the evaluation route reports exactly what the shared validator reports")
    void theVerdictIsExactlyWhatTheSharedValidatorReports() {
        DateConversionResponse reported =
                this.evaluationHalf.convert(new DateConversionRequest(UNUSABLE_ISO_DATE, null));
        DateEditValidator.LanguageEnvironmentResult delegated =
                DateEditValidator.evaluateWithLanguageEnvironment(
                        UNUSABLE_ISO_DATE, DateEditValidator.DATE_FORMAT_MASK);

        assertThat(reported.feedbackCode()).isEqualTo(delegated.feedbackCode().name());
        assertThat(reported.severity()).isEqualTo(delegated.severity());
        assertThat(reported.messageNumber()).isEqualTo(delegated.messageNumber());
        assertThat(reported.verdict()).isEqualTo(delegated.verdict());
        assertThat(reported.date()).isEqualTo(delegated.date());
        assertThat(reported.mask())
                .as("the picture the answer was produced under is echoed, which is how a caller that"
                        + " sent none learns which one was applied")
                .isEqualTo(delegated.mask());
        assertThat(reported.verdict())
                .as("the verdict crosses at the width the reference result writes it")
                .hasSizeLessThanOrEqualTo(DateConversionResponse.VERDICT_WIDTH);
    }

    /**
     * Confirms the severity and the message number stay two separate four-character codes.
     *
     * <p>Purpose: the two numbers must not be merged and must not collapse into an acceptance flag.
     * Two baseline callers forgive a rejected evaluation when the message number is the one they
     * tolerate, while the shared driver forgives none, so a caller that received only a boolean could
     * not make the distinction its own reference program makes.</p>
     *
     * <p>Assumptions: the acceptable verdict arrives under a name that reads as its opposite, and
     * that is the baseline's own name rather than a mistake here. Line 62 of
     * {@code app/cbl/CSUTLDTC.cbl} declares the all-zero feedback token under a condition name that
     * says invalid, and lines 129 and 130 select that same condition to report the date VALID. The
     * name is carried across so a caller branches on the value its reference program produced; the
     * rules live in the shared validator and the misleading name is not reproduced as behaviour.</p>
     *
     * <p>Alternatives Considered: asserting the two codes only on a rejected evaluation, where they
     * plainly differ. Rejected because the accepted evaluation is where a merge would hide: both
     * codes are zero there, so a single merged member would satisfy every assertion an accepted case
     * could make. The rejected evaluation is what separates them and the accepted one is what fixes
     * their padded width, so both are needed.</p>
     */
    @Test
    @DisplayName("the severity and the message number stay two separate four-character codes")
    void theSeverityAndTheMessageNumberStayTwoSeparateFourDigitCodes() {
        DateConversionResponse accepted =
                this.evaluationHalf.convert(new DateConversionRequest(VALID_ISO_DATE, null));

        assertThat(accepted.severityCode())
                .as("the accepted severity, padded to the width the reference declares for it")
                .isEqualTo(DateConversionResponse.ACCEPTED_SEVERITY_CODE)
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);
        assertThat(accepted.messageNumberCode())
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);
        assertThat(accepted.feedbackCode())
                .as("the baseline's own name for the acceptable outcome, which reads as its opposite")
                .isEqualTo(DateEditValidator.FeedbackCode.INVALID_DATE.name());

        DateConversionResponse rejected =
                this.evaluationHalf.convert(new DateConversionRequest(UNUSABLE_ISO_DATE, null));

        assertThat(rejected.severityCode())
                .as("a rejected evaluation raises the severity away from the accepted code")
                .isNotEqualTo(DateConversionResponse.ACCEPTED_SEVERITY_CODE)
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);
        assertThat(rejected.messageNumberCode())
                .as("and reports a message number of its own, at the same width and distinct from the"
                        + " severity, which is what a merged member could not do")
                .isNotEqualTo(rejected.severityCode())
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);

        assertThat(DateConversionResponse.TOLERATED_MESSAGE_NUMBER_CODE)
                .as("the padded form of the one message number two baseline callers forgive")
                .isEqualTo("2513")
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);
        assertThat(DateConversionResponse.TOLERATED_MESSAGE_NUMBER)
                .as("its numeric twin, which the shared validator publishes under its own name")
                .isEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);
    }
}
