// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/DateConversionFlowContractTest.java
// -----------------------------------------------------------------------------
// Purpose:
//      Holds the properties of app/app-vsam-mq/cbl/CODATE01.cbl that this module
//      still answers for once the QUEUE half of that program has left it, plus
//      the withdrawal itself. Every collaborator is real or substituted in
//      process: nothing here starts a Spring context, a listener container, an
//      emulator, a queue or a database.
//
// WHY (non-obvious design decisions):
//  (1) Refactoring Rationale: THIS FILE REPLACES DateConversionMessageListenerTest,
//      and the replacement is a narrowing rather than a rename. That file held the
//      properties spanning the two types CODATE01's migration was split into -- a
//      queue consumer and a date evaluation -- and drove the queue half directly.
//      This module no longer holds a queue half at all. The baseline drives BOTH
//      of its inquiry programs from ONE request destination,
//      DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE') at app/app-vsam-mq/README.md L53,
//      aliased to CICS as MQQUEUE(CARDREQ) at L71, and a queue admits exactly one
//      OWNING consumer because a receive hides the message from every other
//      consumer rather than delivering a copy to each. The owner is
//      com.carddemo.account.service.InquiryMessageListener, which dispatches on
//      the request's four-character function code, and its own suite owns every
//      queue-side property: the reply bytes, the correlation echo, the reply-to
//      handling, the expiry drop, the error sink and the polling contract. What
//      remains here is the evaluation half and the wire GEOMETRY both halves read.
//  (2) Alternatives Considered: keeping the old file under its old name, as that
//      file itself argued for when the FIRST competing consumer was withdrawn.
//      Rejected this time. Its name records a type that never existed in this
//      module's delivered form, its cases constructed a consumer this module does
//      not have, and its collaborators pulled a messaging client onto a test
//      classpath from which the messaging starter is now deliberately absent --
//      so the file could not compile, let alone assert. The withdrawal record it
//      carried is preserved: the first case below asserts BOTH withdrawn type
//      names are unresolvable from this module, which is the assertion that fails
//      if either returns.
//  (3) Assumptions: there is NO GOLDEN MASTER for this flow. The repository's
//      oracle covers the batch programs, and a queue-triggered CICS transaction
//      cannot run on the runner at all, so the evidence for every claim below is
//      a cited reference line plus arithmetic over declared widths. That is
//      stated rather than implied, because an assertion presented as
//      oracle-backed when it is transcription-backed overstates what a green run
//      proves.
//  (4) Assumptions: the four rationale labels used throughout this file are the
//      plural, unparenthesised, hyphen-minus forms docs/CODE_DOCUMENTATION_STANDARD.md
//      fixes -- Alternatives Considered, Refactoring Rationale, Assumptions,
//      Trade-offs. The singular parenthesised spellings that predominate in the
//      reference-only trees denote the same four categories; the equivalence is
//      declared once, here, and the two forms are never mixed.
// =============================================================================

package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.DateInquiryReplyCodec;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts what this module still answers for in the date flow, and that the queue half has left it.
 *
 * <h2>What this class answers for, and what it deliberately leaves alone</h2>
 *
 * <p>Assumptions: the per-class suites own their own halves and nothing here restates them.
 * {@code DateConversionServiceTest} owns the evaluation half's wiring of mask defaulting and verdict
 * assembly; {@code DateConversionControllerTest} owns the synchronous route's binding and refusals;
 * {@code ReferenceFixtureTest} owns the committed request fixtures' geometry; and the queue half's own
 * behaviour belongs to the account context's {@code InquiryMessageListenerTest}, together with
 * {@code DateInquiryReplyCodecTest} in the shared kernel for the reply body's labels, ordering and
 * padding. What none of them can own is a property visible only when the evaluation contract and the
 * wire contract are in view at once, and those are what this class holds.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each member below carries its own.</p>
 */
@DisplayName("the date flow this module keeps, and the queue half it does not")
class DateConversionFlowContractTest {

    /**
     * The binary name of the consumer withdrawn when two consumers raced on one queue in this module.
     *
     * <p>Assumptions: stated as a string rather than as a type reference, which is the only form
     * available: a type reference to a class that must not exist would not compile, and its
     * non-existence is the property the first case holds.</p>
     */
    private static final String RACED_CONSUMER =
            "com.carddemo.reference.service.DateConversionMessageListener";

    /**
     * The binary name of the consumer withdrawn when the shared request queue was given one owner.
     *
     * <p>Assumptions: this is a DIFFERENT withdrawal from the one above and is asserted separately.
     * The first removed a duplicate implementation inside this module; this one moved the surviving
     * implementation to the module that owns the queue. A single assertion covering both would report
     * one fact and hide which of the two had been undone.</p>
     */
    private static final String RELOCATED_CONSUMER =
            "com.carddemo.reference.service.DateInquiryMessageListener";

    /**
     * The binary name of the renderer that moved to the shared kernel with the consumer that used it.
     *
     * <p>Assumptions: named because its absence from THIS package is the property, not its absence
     * from the repository. It exists as {@link DateInquiryReplyCodec}, which this class calls; a copy
     * left behind here would be a second transcription of a positional layout, which is the failure
     * mode the shared kernel exists to prevent.</p>
     */
    private static final String RELOCATED_RENDERER =
            "com.carddemo.reference.mapper.DateInquiryReplyMapper";

    /**
     * The instant the reply body in this class is rendered from.
     *
     * <p>Assumptions: the day-of-month is deliberately past the twelfth. The reference program emits a
     * month-first date -- {@code MMDDYYYY(WS-MMDDYYYY)} with {@code DATESEP('-')} at lines 349 and 350,
     * into the {@code PIC X(10)} field declared at line 37 -- while every validated date in this
     * migration is year-first at the same ten characters. For the first twelve days of any month the
     * two orderings are indistinguishable, so an instant inside that window would let a case pass
     * against either form. The eighteenth cannot.</p>
     */
    private static final Instant NOW = Instant.parse("2022-07-18T09:04:05Z");

    /** A well-formed candidate date in the year-first picture, taken from the baseline's own run date. */
    private static final String VALID_ISO_DATE = "2022-07-18";

    /** A well-formed ten-character candidate naming a day that does not exist. */
    private static final String UNUSABLE_ISO_DATE = "2022-02-30";

    /** The month-first rendering of {@link #NOW}, which is what the queue reply carries. */
    private static final String EMITTED_DATE = "07-18-2022";

    /** The evaluation half, constructed directly because it holds no collaborator. */
    private DateConversionService evaluationHalf;

    /**
     * Builds the evaluation half.
     *
     * <p>Assumptions: no queue client, no clock and no repository is substituted, because the
     * surviving half needs none of the three -- it delegates every rule to the shared validator and
     * reads nothing. A setup that built collaborators it does not use would suggest dependencies this
     * class then asserts nothing about.</p>
     */
    @BeforeEach
    void setUp() {
        this.evaluationHalf = new DateConversionService();
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
     * @throws IllegalArgumentException if either operand is not its declared width, raised here rather
     *     than left to the codec so that a mis-sized fixture is reported against the field that is
     *     wrong instead of against the record length
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
     * Declares the request record's geometry independently, straight from the copybook.
     *
     * <p>Purpose: this is the INDEPENDENT WITNESS the offset cross-check needs. Its three fields are
     * transcribed from {@code app/app-vsam-mq/cbl/CODATE01.cbl} lines 110 to 112 -- a four-character
     * function code, an eleven-digit key and nine hundred and eighty-five characters of space filler --
     * and {@link CopybookLayout.RecordSpec#validateGeometry()} proves they tile the thousand characters
     * line 50 declares with no gap and no overlap.</p>
     *
     * <p>Trade-offs: a layout transcribed twice is normally a hazard, and {@link CopybookLayout} says
     * so in its own documentation. Here the second transcription is the INSTRUMENT rather than the
     * hazard: the cross-check has power only because one side comes from the copybook and the other
     * from the widths {@link InquiryRequestCodec} publishes, and a single shared source would make the
     * comparison agree with itself. The accepted cost is that this declaration must be kept in step
     * with the copybook, and the case that consumes it fails the moment it is not.</p>
     *
     * <p>Assumptions: the record is declared KEYLESS. The reference program retrieves it from a queue
     * and not by key -- its get names a queue at line 289, a handle at line 290 and a buffer length of
     * a thousand at line 291, with no key operand anywhere -- so a keyless record is the honest
     * geometry for a message buffer.</p>
     *
     * @return the validated request layout, never {@code null}
     * @throws CopybookLayout.LayoutException if the three fields do not tile the declared record length
     *     exactly, which would mean this transcription has fallen out of step with the copybook it
     *     cites
     */
    private static CopybookLayout.RecordSpec requestLayout() {
        return CopybookLayout.RecordSpec.keyless("REQUEST-MSG-COPY", 1000, List.of(
                CopybookLayout.text("WS-FUNC", 0, 4),
                CopybookLayout.uint("WS-KEY", 4, 11),
                CopybookLayout.text("WS-FILLER", 15, 985)));
    }

    /**
     * Confirms both withdrawn consumers and the relocated renderer are absent from this module.
     *
     * <p>Purpose: two different withdrawals happened here and each is asserted by binary name, because
     * a name lookup is the only assertion that fails when a type RETURNS. The first withdrawal removed
     * a second consumer bound to the same request queue as the first, so which contract a message met
     * depended on which container polled it first. The second moved the surviving consumer to the
     * module that owns the shared request queue, because one queue admits one owning consumer and a
     * consumer here would take work only the account context can answer.</p>
     *
     * <p>Assumptions: the evaluation half is asserted PRESENT in the same case, so the withdrawal
     * cannot be satisfied by deleting the flow. Its published answer type is asserted too, because a
     * surviving member that no longer answered the contract shape would leave the synchronous route
     * broken while every absence assertion still passed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws NoSuchMethodException if the evaluation member is renamed, which is a structural failure
     *     rather than an assertion failure and is reported as one
     */
    @Test
    @DisplayName("both withdrawn consumers are absent and the evaluation half remains")
    void bothWithdrawnConsumersAreAbsentAndTheEvaluationHalfRemains() throws NoSuchMethodException {
        assertThatThrownBy(() -> Class.forName(RACED_CONSUMER))
                .as("a second consumer of one request queue makes the contract a message meets depend"
                        + " on which container polled it first, which is why this type was withdrawn")
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(RELOCATED_CONSUMER))
                .as("one queue admits one owning consumer, and the owner is the account context, so a"
                        + " consumer here would race another module for every inquiry message")
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(RELOCATED_RENDERER))
                .as("the reply layout is single-sourced in the shared kernel; a copy left here would"
                        + " be a second transcription free to disagree about an offset")
                .isInstanceOf(ClassNotFoundException.class);

        assertThat(DateConversionService.class
                .getDeclaredMethod("convert", DateConversionRequest.class).getReturnType())
                .as("the evaluation half is the member of the split that had no queue concern in it,"
                        + " and it still answers the published verdict shape")
                .isEqualTo(DateConversionResponse.class);
    }

    /**
     * Confirms the copybook geometry and the shared codec agree on every declared offset.
     *
     * <p>Purpose: two independent transcriptions of one layout exist in this system -- the field
     * descriptors this class builds from {@code app/app-vsam-mq/cbl/CODATE01.cbl} lines 110 to 112, and
     * the widths {@link InquiryRequestCodec} publishes and slices by. Nothing else compares them. This
     * case does, on both the geometry and one decoded record.</p>
     *
     * <p>Assumptions: an offset disagreement between two transcriptions does not raise. It presents as
     * a field that is silently one character short, decoding to a plausible value at every field after
     * it, which is exactly the failure the shared codec's own documentation warns of and exactly the
     * failure no single-sided case can see.</p>
     *
     * <p>Assumptions: this case stays in THIS module although the queue consumer has left it. The
     * request geometry is what the synchronous route's own two date pictures are contrasted against,
     * and this module holds the independent copybook transcription; moving the case to the consuming
     * module would put both sides of the comparison in one place, which is what deprives it of its
     * power.</p>
     *
     * <p>Assumptions: the key is compared as a NUMBER on one side and as characters on the other,
     * because the two transcriptions type it differently on purpose. The codec carries the key's raw
     * characters so a non-numeric key can still be answered rather than raised on, while the field
     * descriptor declares an unsigned display integer because the copybook declares {@code PIC 9(11)}.
     * The comparison therefore goes through the codec's own numeric query, which is the only reading in
     * which the two are the same value.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
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
     * Confirms the two ten-character date pictures are two contracts and never one.
     *
     * <p>Purpose: the queue reply emits a month-first date while the evaluation route reads year-first,
     * and both are exactly ten characters. Normalising either into the other would change bytes a far
     * end reads by offset, and it would appear to work: the two orderings are indistinguishable for the
     * first twelve days of any month, which is why the instant rendered here is the eighteenth.</p>
     *
     * <p>Refactoring Rationale: the emitted form is obtained from {@link DateInquiryReplyCodec} rather
     * than by driving a consumer, because the consumer is no longer in this module. The property under
     * assertion is unaffected -- it is about the two PICTURES, not about who sends them -- and reading
     * the shared renderer directly makes the comparison independent of any transport.</p>
     *
     * <p>Assumptions: interchangeability is disproved in the direction that matters. The emitted
     * month-first value, read back under the year-first picture, must NOT be reported acceptable --
     * that is what makes them two contracts rather than two spellings of one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the two ten-character date forms are not interchangeable")
    void theTwoTenCharacterDateFormsAreNotInterchangeable() {
        String emitted = DateInquiryReplyCodec.systemDateAndTime(
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertThat(emitted)
                .as("the emitted date is month-first, as lines 349 and 350 request it")
                .contains(EMITTED_DATE);
        assertThat(emitted)
                .as("and is therefore NOT the year-first form the evaluation route reads")
                .doesNotContain(VALID_ISO_DATE);

        assertThat(EMITTED_DATE.length())
                .as("both forms are ten characters, which is precisely why they can be confused")
                .isEqualTo(VALID_ISO_DATE.length())
                .isEqualTo(DateEditValidator.MASKED_DATE_LENGTH);

        assertThat(DateEditValidator.evaluateWithLanguageEnvironment(
                        EMITTED_DATE, DateEditValidator.DATE_FORMAT_MASK).acceptable())
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
     * restate no date rule of its own, and the way to assert that is to compare its answer against the
     * shared validator's answer for the same operands, member for member, rather than against literals
     * that would pass equally well against a local reimplementation that happened to agree on the cases
     * chosen.</p>
     *
     * <p>Refactoring Rationale: one shared implementation is the FAITHFUL shape here and not a liberty
     * taken for tidiness. The date utility at {@code app/cbl/CSUTLDTC.cbl} was itself a dynamically
     * called shared subprogram: its procedure division at line 88 takes the date, the picture and a
     * result, it forwards its severity to the return code at line 98 and hands control back at line
     * 100. Re-expressing it as one shared callable re-expresses the baseline's own mechanism;
     * transcribing its rules into each caller would not.</p>
     *
     * <p>Assumptions: the queue route is NOT part of this claim. A search for {@code CSUTLDTC} across
     * all 524 lines of {@code app/app-vsam-mq/cbl/CODATE01.cbl} returns zero occurrences, so date
     * validation and date-and-time emission are separate contracts in the baseline, and asserting them
     * as one here would invent a relationship the reference programs do not have.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
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
     * <p>Purpose: the two numbers must not be merged and must not collapse into an acceptance flag. Two
     * baseline callers forgive a rejected evaluation when the message number is the one they tolerate,
     * while the shared driver forgives none, so a caller that received only a boolean could not make
     * the distinction its own reference program makes.</p>
     *
     * <p>Assumptions: the acceptable verdict arrives under a name that reads as its opposite, and that
     * is the baseline's own name rather than a mistake here. Line 62 of {@code app/cbl/CSUTLDTC.cbl}
     * declares the all-zero feedback token under a condition name that says invalid, and lines 129 and
     * 130 select that same condition to report the date VALID. The name is carried across so a caller
     * branches on the value its reference program produced; the rules live in the shared validator and
     * the misleading name is not reproduced as behaviour.</p>
     *
     * <p>Alternatives Considered: asserting the two codes only on a rejected evaluation, where they
     * plainly differ. Rejected because the accepted evaluation is where a merge would hide: both codes
     * are zero there, so a single merged member would satisfy every assertion an accepted case could
     * make. The rejected evaluation is what separates them and the accepted one is what fixes their
     * padded width, so both are needed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
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
