// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/api/DateConversionControllerTest.java
// -----------------------------------------------------------------------------
// Purpose:
//      Boundary cases over DateConversionController that no other class in this
//      package reaches: that the shared advice registration is load-bearing and
//      not decorative, that this route publishes the date-edit contract and NOT
//      the queue-borne system-date reply, that the severity and the message
//      number arrive as two separate members with the tolerated rejection
//      intact, that the submitted pair is handed to the evaluation verbatim
//      including an absent picture, that the problem document is stamped from an
//      injected clock, that an over-wide diagnostic never reaches the caller,
//      that the correlation identity is echoed and minted through the shared
//      filter, and that nothing is retained between requests. Every date rule
//      is delegated and none is restated.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: THIS ROUTE IS NOT THE SYSTEM-DATE ECHO, and conflating the
//      two is the single easiest mistake to make here. The queue-borne date
//      service at app/app-vsam-mq/cbl/CODATE01.cbl answers with the CURRENT date
//      and time regardless of what it was sent: its request layout declares
//      WS-FUNC at line 110 and WS-KEY at line 111 inside the group opened at
//      line 109, and neither name occurs anywhere else in the whole 524-line
//      program, so neither is ever tested or read. Its answering paragraph at
//      line 339 asks the platform for the instant at lines 343 to 345 and
//      renders it at lines 347 to 353. The sibling at
//      app/app-vsam-mq/cbl/COACCT01.cbl line 393 is the one place a request
//      function IS read. This route instead reports on a date a CALLER submits,
//      so its answer follows the request and its verdict is not a clock reading;
//      the cases below assert both halves of that separation rather than
//      assuming a reader will keep them apart.
//  (2) Assumptions: the date-edit utility is absent from that program entirely
//      -- a search of app/app-vsam-mq/cbl/CODATE01.cbl for CSUTLDTC returns zero
//      occurrences -- so date VALIDATION and the queue reply are two contracts
//      and are never asserted as one.
//  (3) Alternatives Considered: the context-slicing web-test annotation, which
//      is the obvious wiring for a controller slice and is NOT on this module's
//      test class path. The framework's fourth generation moved that annotation
//      out of the test auto-configuration artifact into a separate servlet
//      slice artifact that services/reference-service/pom.xml does not declare
//      and that the framework test starter does not pull in; the annotation is
//      absent from the auto-configuration jar the build resolves. Declaring the
//      missing artifact would edit a sibling-owned descriptor, so the gap is
//      reported here and the dispatcher is assembled from types that are on the
//      path -- which is also what every other dispatcher class in this package
//      does, and is why the advice is registered by hand rather than imported.
//  (4) Assumptions: registering com.carddemo.common.error.GlobalExceptionHandler
//      on the dispatcher is what makes every status assertion here mean
//      anything, and its omission does not fail loudly. A dispatcher assembled
//      standalone has no context to discover the one shared advice from, and
//      without it the framework's own error handling answers instead -- so a
//      refusal that should render as the published four hundred can read as
//      green. Case one asserts that contrast PERMANENTLY rather than leaving it
//      to a reviewer to re-verify by deleting a line.
//  (5) Refactoring Rationale: every rule is delegated. The utility at
//      app/cbl/CSUTLDTC.cbl was itself a callable subprogram -- its linkage
//      section opens at line 83, declares the date and the picture as
//      ten-character arguments at lines 84 and 85 and the result as eighty
//      characters at line 86, and its procedure division receives all three at
//      line 88 -- so one shared holder of the rules is the faithful shape and
//      not an abstraction invented for the migration. Nothing here reimplements
//      a calendar edit.
//  (6) Trade-offs: two pictures of ten characters coexist and neither is
//      normalised away. The separated ordering is what app/cbl/COTRN02C.cbl
//      declares at line 60 as a ten-character literal, and the unseparated one
//      is what app/cpy/CSUTLDWY.cpy declares at line 58 as an eight-character
//      literal with its value on line 59, while the picture argument itself is
//      ten characters wide at app/cbl/CSUTLDTC.cbl line 85. Carrying both costs
//      a reader two admitted widths to hold in mind and buys that a caller
//      reproducing either baseline site is answered on its own terms.
//  (7) Assumptions: the labels below are the plural, unwrapped spellings the
//      rule document lists at its lines 31 to 34. The singular and the
//      parenthesised spellings mean the same thing and are simply not used in
//      this file; that equivalence is recorded in this sentence alone and the
//      forms are not mixed anywhere here. The hyphen in the trade-offs label is
//      the ordinary hyphen-minus: tests/README.md line 548 is the very line
//      listing these four labels and renders that one with a non-breaking
//      hyphen followed by an em dash, both indistinguishable by eye from their
//      plain counterparts, so the text here was taken from the rule document
//      instead and this file is plain ASCII throughout.
//  (8) Assumptions: the graded condition-code scale belongs to the baseline
//      suite, which aggregates the worst code seen at tests/README.md line 415
//      and treats its warn tier at line 420 as a passing state. That model
//      stops at that tree's boundary. The gate here is binary: these cases pass
//      or they fail, and nothing in this file tolerates a non-zero result.
// =============================================================================
package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import com.carddemo.reference.mapper.DateInquiryReplyMapper;
import com.carddemo.reference.service.DateConversionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the date-evaluation route's boundary behaviour to the contract this context publishes.
 *
 * <h2>What this class asserts, and what belongs to somebody else</h2>
 *
 * <p>Purpose: the subjects below are the ones reachable only by driving a real dispatcher over
 * {@link DateConversionController} and are not asserted anywhere else in this module -- that the shared
 * advice registration decides what a caller receives, that this route publishes the date-edit contract
 * and not the queue-borne system-date reply, that the two reported codes travel as two members with the
 * tolerated rejection intact, that the submitted pair reaches the evaluation unaltered, that the problem
 * document is stamped from an injected clock, that an over-wide diagnostic is withheld, that the
 * correlation identity is echoed and minted, and that nothing survives between requests.</p>
 *
 * <p>Assumptions: the date rules themselves are proven in {@code com.carddemo.reference.service} and the
 * schema guarantees in {@code com.carddemo.reference.repository}, and the package descriptor beside this
 * file forbids repeating either. Four sibling classes in this package already own their own subjects: the
 * routing census compares the published document against the method table, the date dispatcher class
 * owns the accepted verdict, the unusable day, the width refusal status and the two parameter presence
 * refusals, the refusal class owns which condition the handler may relabel, and the parameter-constraint
 * class owns the constrained parameters of the other five controllers. Nothing below restates one of
 * those; where a case has to touch the same input it asserts a different property of it, and the
 * paragraph on that case says which.</p>
 *
 * <p>Assumptions: authority is deliberately not decided here. The dispatchers assembled below register no
 * security chain, which is what keeps an unmounted address distinguishable from a refused caller, and
 * which authority each route demands is asserted in the sibling {@code com.carddemo.reference.config}
 * test package against the chain's own installed authorization managers. The one authority-shaped case
 * here asserts the complement of that -- that the handler itself decides nothing -- and it presents its
 * caller with the security test support rather than with a real token, a reachable issuer or a
 * credential written into a source file.</p>
 *
 * <p>Assumptions: {@code src/test/resources/application-test.yml} already supplies the single override
 * point for token decoding and already prevents the queue listener container from starting. Neither key
 * is restated or contradicted here, and no case below loads a Spring context at all, so neither is even
 * read -- which is the point: two settings for one concern can disagree while only one is in effect.</p>
 *
 * <p>Assumptions: the queue half of this context is out of reach by construction. The queue-borne date
 * route is a listener rather than a controller and is asserted in the service test package; no queue is
 * stood up here and no queue configuration is declared. That is defensible on the baseline's own terms
 * as well: app/app-vsam-mq/cbl/CODATE01.cbl composes its answer under syncpoint, taking the option at
 * its line 379 and putting the reply in the paragraph opening at line 366, so the inquiry-class flow
 * reads and replies inside one unit of work and there is no lost-reply window for an outbox to close.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; every member below carries its own.</p>
 */
@DisplayName("the date-evaluation HTTP boundary")
class DateConversionControllerTest {

    /**
     * The instant every problem document below is stamped from.
     *
     * <p>Assumptions: an injected clock rather than the ambient one, so a rendered document is
     * comparable character for character. The shared timestamp helper publishes a member that takes a
     * clock and deliberately publishes no argument-free equivalent, which is what makes this the
     * supported path rather than a preference.</p>
     */
    private static final Instant STAMPED_INSTANT = Instant.parse("2026-02-01T00:00:00Z");

    /**
     * A second instant, used only to show the stamp follows the clock it was given.
     *
     * <p>Assumptions: chosen a whole day away from the first so the two renderings differ in their date
     * part and not merely in their microseconds, which keeps the comparison legible in a failure
     * report.</p>
     */
    private static final Instant ALTERNATE_INSTANT = Instant.parse("2026-02-02T13:45:07Z");

    /** The clock the dispatcher under test stamps its problem documents from. */
    private static final Clock STAMPED_CLOCK = Clock.fixed(STAMPED_INSTANT, ZoneOffset.UTC);

    /** A date that names a usable day under the separated ten-character picture. */
    private static final String USABLE_DATE = "2022-07-18";

    /** That same day written under the unseparated eight-character picture. */
    private static final String USABLE_PACKED_DATE = "20220718";

    /**
     * A well-formed calendar day the supported range still declines, which is the tolerated rejection.
     *
     * <p>Assumptions: the day immediately below the inclusive lower bound the shared holder publishes as
     * {@code GREGORIAN_FLOOR}. October carries thirty-one days, so this is a real day rather than a
     * malformed one, and the evaluation therefore reaches the out-of-range outcome rather than the
     * malformed-value one -- which is precisely the outcome two baseline screens forgive.</p>
     */
    private static final String OUT_OF_RANGE_DATE = "1582-10-14";

    /**
     * A picture one character wider than the contract admits, used to provoke a parameter refusal.
     *
     * <p>Assumptions: eleven characters, one past the ten the baseline picture argument declares at
     * app/cbl/CSUTLDTC.cbl line 85. Alternatives Considered: provoking the same refusal with a
     * too-narrow date, which the sibling dispatcher class already owns. This input is used instead so
     * that the contrast case below rests on a refusal no other class in this package exercises, and so
     * that removing this file could not silently remove the only coverage of either.</p>
     */
    private static final String OVER_WIDE_MASK = "YYYY-MM-DDD";

    /**
     * The first label of the queue-borne reply, transcribed here so its absence can be asserted.
     *
     * <p>Assumptions: exactly fourteen characters -- the word, a space, four letters, a space, a colon
     * and a trailing space -- read from app/app-vsam-mq/cbl/CODATE01.cbl line 355. It is transcribed
     * rather than imported because the composing mapper holds both labels privately, and a private
     * constant cannot be named from here. The width of this transcription is itself asserted below, so a
     * typo in it fails immediately instead of weakening the assertion it supports.</p>
     */
    private static final String QUEUE_REPLY_DATE_LABEL = "SYSTEM DATE : ";

    /**
     * The second label of that same reply, transcribed on the same terms as the first.
     *
     * <p>Assumptions: also exactly fourteen characters, read from that program's line 356. The
     * concatenation at lines 355 to 360 runs under {@code DELIMITED BY SIZE}, which inserts nothing
     * between the date value and this label, so the two labels and the two values published as
     * ten and eight characters wide at lines 37 and 38 total the length the mapper publishes.</p>
     */
    private static final String QUEUE_REPLY_TIME_LABEL = "SYSTEM TIME : ";

    /** The width both queue-reply labels are declared at, asserted rather than assumed. */
    private static final int QUEUE_REPLY_LABEL_WIDTH = 14;

    /**
     * The shape a time of day takes in the queue reply, which this route must never emit.
     *
     * <p>Assumptions: two digits, a colon, two digits, a colon, two digits. The colon is settled by the
     * reference rather than chosen: app/app-vsam-mq/cbl/CODATE01.cbl line 352 requests the time separator
     * with NO argument at all, so the time takes the platform default, while line 350 supplies the date
     * separator explicitly. The asymmetry is in the source, so mirroring the hyphen onto the time would
     * contradict it while looking tidier.</p>
     */
    private static final String TIME_OF_DAY_SHAPE = "\\d{2}:\\d{2}:\\d{2}";

    /**
     * The United States ordering the queue reply carries and this route must never emit.
     *
     * <p>Assumptions: two digits, a hyphen, two digits, a hyphen, four digits, requested at
     * app/app-vsam-mq/cbl/CODATE01.cbl line 349 with its separator supplied at line 350. The separated
     * ordering this route echoes cannot match this shape, because its own leading component is four digits
     * and its total width is two characters short of a match.</p>
     */
    private static final String UNITED_STATES_DATE_SHAPE = "\\d{2}-\\d{2}-\\d{4}";

    /** An inbound correlation identity in the shape the shared filter admits. */
    private static final String INBOUND_CORRELATION_ID = "CDREFDATEEVAL0000000001";

    /**
     * A query parameter this contract does not declare, in the shape a screen turn counter would take.
     *
     * <p>Assumptions: the eliminated re-entry discriminator is what this stands in for. The baseline
     * screens carried a context marker across a screen turn and branched on whether the turn was a first
     * entry or a re-entry; this API exposes no such member, so a request carrying one must be answered
     * exactly as a request without it. Sending a plausible one is the only way to show that.</p>
     */
    private static final String UNDECLARED_REENTRY_PARAMETER = "pgmContext";

    /** Reads a rendered body so its member set and its members can be inspected directly. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * How many links of a raised condition's cause chain the contrast case walks.
     *
     * <p>Assumptions: a bound rather than a walk to the end, because a self-referential cause chain would
     * otherwise turn a failing assertion into a hang and a hung build reports nothing at all. Sixteen is
     * far past any wrapping a dispatcher applies, so the bound cannot hide the condition being looked
     * for.</p>
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** The dispatcher under test: the real evaluation, the shared advice and the shared filter. */
    private MockMvc dispatcher;

    /** The same controller with no advice registered, held only for the contrast in case one. */
    private MockMvc undefendedDispatcher;

    /**
     * Assembles both dispatchers over one real evaluation before each case.
     *
     * <p>Assumptions: the evaluation beneath is REAL rather than substituted wherever a case turns on
     * which verdict or which status a particular input produces, because a substitute would answer
     * whatever it was stubbed with and the assertion would then be about the stub. It holds no field, no
     * clock and no client, so there is nothing a substitute would isolate. The two cases that genuinely
     * need a substitute build their own dispatcher and say why.</p>
     */
    @BeforeEach
    void assembleDispatchers() {
        DateConversionService evaluator = new DateConversionService();
        this.dispatcher = defendedDispatcherOver(evaluator, STAMPED_CLOCK);
        this.undefendedDispatcher = undefendedDispatcherOver(evaluator);
    }

    /**
     * Discards any caller the security test support installed, so no case inherits another's identity.
     *
     * <p>Assumptions: the support publishes the caller through a holder that outlives one request, so
     * leaving it populated would let a case that asserts an authority-free handler run under an identity
     * an earlier case installed. Clearing it unconditionally is cheaper than reasoning about which cases
     * set it.</p>
     */
    @AfterEach
    void discardInstalledCaller() {
        TestSecurityContextHolder.clearContext();
    }

    /**
     * Shows that registering the shared advice is what produces the published refusal at all.
     *
     * <p>Purpose: this is the one case in the file whose value is the CONTRAST rather than either half,
     * and it is asserted permanently instead of being demonstrated once by deleting a line. Two refusals
     * are driven through two dispatchers over the SAME controller, and the two refusals fail differently
     * without the advice, which is why both are here.</p>
     *
     * <p>Assumptions: an over-wide picture is refused by the framework's OWN default resolver, which
     * answers the same four hundred with an EMPTY body. That is the trap in its purest form: a case
     * asserting only the status passes identically whether the shared advice is registered or not, and the
     * published problem document is simply absent. Only an assertion that reads the BODY can tell the two
     * apart, which is why the defended half below names the shared code, the path and the per-field
     * array.</p>
     *
     * <p>Assumptions: a width disagreement is not handled by that default resolver at all, so it escapes
     * an undefended dispatcher and nothing is rendered. The two halves together therefore show both
     * failure modes -- a silent default answer and an unhandled escape -- and a reader cannot conclude
     * from one of them that the other is impossible.</p>
     *
     * <p>Assumptions: a dispatcher assembled standalone has no context to discover the one shared advice
     * from, and the shared advice sits outside this context's scan root, so it can only arrive by hand.
     * Nothing in this module declares an advice of its own and nothing here declares one either: one
     * handler is selected per condition, so a second declaration would make which of the two answered
     * depend on ordering.</p>
     *
     * <p>Alternatives Considered: driving only the width disagreement, which the sibling refusal class
     * already raises directly. Rejected because that condition escapes loudly and would leave the far more
     * dangerous mode -- a defaulted four hundred that reads as green -- unasserted. The over-wide picture
     * is additionally a refusal no other class in this package drives, so including it adds coverage
     * rather than restating any.</p>
     *
     * @throws Exception if the defended requests cannot be performed, which surfaces here rather than as
     *     a silent absence of assertions
     */
    @Test
    @DisplayName("the registered advice is what renders the problem document, and its absence is silent")
    void theRegisteredAdviceIsLoadBearing() throws Exception {
        this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .param(DateConversionController.PARAM_MASK, OVER_WIDE_MASK))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.path").value(DateConversionController.BASE_PATH))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(DateConversionController.PARAM_MASK));

        // WHY : Assumptions: the undefended half answers the SAME status with nothing in it, which is
        //       exactly why an assertion on the status alone cannot detect a missing advice. Reading the
        //       body is the only observation that separates the two, so the body is what is asserted.
        assertThat(bodyOf(this.undefendedDispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .param(DateConversionController.PARAM_MASK, OVER_WIDE_MASK))
                .andExpect(status().isBadRequest()))).isEmpty();

        this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_PACKED_DATE)
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

        // WHY : Assumptions: the second refusal is a raised caller condition rather than a framework one,
        //       so no default resolver claims it and it leaves an undefended dispatcher unanswered. Its
        //       own type is asserted as well, because a dispatcher failing for an unrelated reason would
        //       otherwise satisfy a bare expectation that something was thrown.
        assertThatThrownBy(() -> this.undefendedDispatcher.perform(
                get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_PACKED_DATE)
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.DATE_FORMAT_MASK)))
                .satisfies(raised -> assertThat(causeChainOf(raised))
                        .hasAtLeastOneElementOfType(ClientInputException.class));
    }

    /**
     * Shows this route publishes the date-edit verdict and never the queue-borne system-date reply.
     *
     * <p>Purpose: the two contracts share a subject and nothing else, and a reader who merges them would
     * expect a system date and a time on this body. Neither queue label appears, the answer is not the
     * length the queue reply is declared at, and no time of day is emitted at all.</p>
     *
     * <p>Assumptions: the width of each transcribed label is asserted BEFORE it is searched for, because
     * the labels are held privately by the composing mapper and had to be transcribed here. A label
     * mistyped by one space would otherwise be absent from every body for the wrong reason and the case
     * would pass while proving nothing. The two widths and the two value widths the queue reply carries
     * sum to the length that mapper publishes, which is asserted from the published constant rather than
     * written as a number.</p>
     *
     * <p>Assumptions: the answer here is a structured document and the queue answer is a single padded
     * string, so the comparison is made on the rendered body as text. That is deliberate -- a comparison
     * made member by member could not detect a label smuggled into a member's value.</p>
     *
     * @throws Exception if the request cannot be performed or the body cannot be parsed
     */
    @Test
    @DisplayName("the route emits neither queue-reply label, the queue reply length nor any time of day")
    void theRouteEmitsNeitherQueueLabelNorAnyTime() throws Exception {
        assertThat(QUEUE_REPLY_DATE_LABEL).hasSize(QUEUE_REPLY_LABEL_WIDTH);
        assertThat(QUEUE_REPLY_TIME_LABEL).hasSize(QUEUE_REPLY_LABEL_WIDTH);
        assertThat(QUEUE_REPLY_LABEL_WIDTH * 2 + DateEditValidator.MASKED_DATE_LENGTH
                + DateEditValidator.PACKED_DATE_LENGTH)
                .isEqualTo(DateInquiryReplyMapper.REPLY_BODY_LENGTH);

        String body = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE))
                .andExpect(status().isOk()));

        assertThat(body)
                .doesNotContain(QUEUE_REPLY_DATE_LABEL)
                .doesNotContain(QUEUE_REPLY_TIME_LABEL)
                .doesNotContainPattern(TIME_OF_DAY_SHAPE)
                .doesNotContainPattern(UNITED_STATES_DATE_SHAPE);
        assertThat(MAPPER.readTree(body).path("date").asString()).isEqualTo(USABLE_DATE);
        assertThat(memberNamesOf(body)).doesNotContain("time", "systemDate", "systemTime");
    }

    /**
     * Shows the verdict follows the submitted request and not the clock, and repeats byte for byte.
     *
     * <p>Purpose: two halves of one property. Two materially different candidates are answered
     * differently, which is what separates this route from the queue-borne service whose answer ignores
     * its payload entirely; and one candidate submitted twice is answered identically, which is what
     * makes the route reproducible without any injected instant of its own.</p>
     *
     * <p>Refactoring Rationale: a request-driven answer is a documented EXTENSION of the queue-borne
     * contract and is never presented as parity with it. That program answers with the current instant
     * whatever it was sent, because the two members its request layout declares at
     * app/app-vsam-mq/cbl/CODATE01.cbl lines 110 and 111 are never read anywhere in it. This route
     * reports on a candidate a caller supplies, which is the date-edit utility's contract rather than the
     * queue service's, and the difference is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: this controller reads no clock and holds no clock member, which is why the second
     * half can compare two bodies for equality at all. The only clock in this slice belongs to the shared
     * advice and reaches a problem document rather than a verdict.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the verdict follows the request content and repeats identically for one request")
    void theVerdictFollowsTheRequestAndNotTheClock() throws Exception {
        String usable = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE))
                .andExpect(status().isOk()));
        String declined = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, OUT_OF_RANGE_DATE))
                .andExpect(status().isOk()));

        assertThat(usable).isNotEqualTo(declined);
        assertThat(MAPPER.readTree(usable).path("severity").asInt())
                .isEqualTo(DateEditValidator.SEVERITY_VALID);
        assertThat(MAPPER.readTree(declined).path("severity").asInt())
                .isEqualTo(DateEditValidator.SEVERITY_ERROR);

        String repeated = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE))
                .andExpect(status().isOk()));

        assertThat(repeated).isEqualTo(usable);
    }

    /**
     * Shows both admitted pictures are accepted and each is echoed at its own declared width.
     *
     * <p>Purpose: the contract admits a candidate of eight or ten characters and a picture of up to ten,
     * and the echo is what tells a caller which picture was actually applied. Both admitted pairs are
     * driven and each echo is compared against the picture that was sent and against the width the
     * shared holder publishes for it.</p>
     *
     * <p>Assumptions: this route ACCEPTS the separated ten-character ordering and the unseparated
     * eight-character one, and it EMITS neither the United States ordering nor a time. The separated
     * ordering is the one the migrated edits read, corroborated by
     * app/app-transaction-type-db2/cbl/COTRTUPC.cbl, whose ten-character scratch field at line 108 is
     * redefined at lines 109 to 114 as four characters of year, a separator, two of month, a separator
     * and two of day -- year first. The United States ordering belongs to the queue reply, requested at
     * app/app-vsam-mq/cbl/CODATE01.cbl line 349 with its separator supplied at line 350, and it is
     * asserted absent by the case above rather than expected here.</p>
     *
     * <p>Assumptions: the sibling dispatcher class owns the case where no picture is sent and the default
     * is applied. This case sends BOTH pictures explicitly, which is the half that shows the parameter is
     * honoured rather than ignored in favour of the default.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("both admitted pictures are accepted and echoed at their own widths")
    void bothAdmittedPicturesAreAcceptedAndEchoed() throws Exception {
        this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mask").value(DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(jsonPath("$.date").value(USABLE_DATE))
                .andExpect(jsonPath("$.severity").value(DateEditValidator.SEVERITY_VALID));

        this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_PACKED_DATE)
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.BASELINE_DATE_FORMAT_MASK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mask").value(DateEditValidator.BASELINE_DATE_FORMAT_MASK))
                .andExpect(jsonPath("$.date").value(USABLE_PACKED_DATE))
                .andExpect(jsonPath("$.severity").value(DateEditValidator.SEVERITY_VALID));

        assertThat(DateEditValidator.DATE_FORMAT_MASK)
                .hasSize(DateEditValidator.MASKED_DATE_LENGTH);
        assertThat(DateEditValidator.BASELINE_DATE_FORMAT_MASK)
                .hasSize(DateEditValidator.PACKED_DATE_LENGTH);
    }

    /**
     * Shows the severity and the message number reach the caller as two separate members.
     *
     * <p>Purpose: the two codes are the whole content of a verdict's acceptability and they are carried
     * separately rather than merged or reduced to a flag. The member set is additionally asserted to be
     * exactly the six the contract declares, which is what proves the separation is a WIRE property and
     * not merely an internal one.</p>
     *
     * <p>Assumptions: two baseline screens accept a rejected evaluation when the message number is the
     * tolerated one, and they compare the two codes independently -- app/cbl/COTRN02C.cbl tests the
     * severity against a four-character zero at line 397 and only then tests the message number at line
     * 400, and app/cbl/CORPT00C.cbl does the same at its lines 396 and 399. A single flag, or one member
     * carrying both, would make that reading unreproducible: a caller could no longer distinguish the
     * forgiven rejection from any other. The two four-character readings the reference compares are
     * published as derived members and withheld from serialisation, which is why the closed member set
     * asserted below has six entries and not eight.</p>
     *
     * <p>Alternatives Considered: asserting the member set by naming the members expected to be absent.
     * Rejected because a census of what IS present also catches a member nobody thought to exclude, and
     * the published schema closes its member set outright, so the stronger assertion is the one that
     * matches the document a client validates against.</p>
     *
     * @throws Exception if the request cannot be performed or the body cannot be parsed
     */
    @Test
    @DisplayName("the severity and the message number are two separate members of a closed six-member set")
    void theSeverityAndTheMessageNumberAreTwoSeparateMembers() throws Exception {
        String body = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, OUT_OF_RANGE_DATE))
                .andExpect(status().isOk()));
        JsonNode verdict = MAPPER.readTree(body);

        assertThat(memberNamesOf(body)).containsExactly(
                "feedbackCode", "severity", "messageNumber", "verdict", "date", "mask");
        assertThat(verdict.path("severity").asInt()).isEqualTo(DateEditValidator.SEVERITY_ERROR);
        assertThat(verdict.path("messageNumber").asInt())
                .isEqualTo(DateConversionResponse.TOLERATED_MESSAGE_NUMBER);
        assertThat(verdict.path("severity").asInt()).isNotEqualTo(verdict.path("messageNumber").asInt());
        assertThat(DateConversionResponse.TOLERATED_MESSAGE_NUMBER_CODE)
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);
        assertThat(DateConversionResponse.ACCEPTED_SEVERITY_CODE)
                .hasSize(DateConversionResponse.BASELINE_CODE_WIDTH);
    }

    /**
     * Shows the tolerated rejection reaches the caller as an answer rather than as a failure.
     *
     * <p>Purpose: a non-zero severity accompanied by the tolerated message number must arrive as a
     * two-hundred carrying the verdict, so that a caller reproducing either forgiving baseline screen can
     * apply the tolerance itself. Turning it into a refusal at this boundary would take that decision
     * away from the caller and no request could recover it.</p>
     *
     * <p>Assumptions: the verdict wording is compared against the outcome's own published text rather
     * than retyped, and its declared width is asserted from the published constant. The reference declares
     * that field fifteen characters wide at app/cpy/CSUTLDWY.cpy line 71 and notes the width for itself
     * in the comment above its selection at app/cbl/CSUTLDTC.cbl lines 126 and 127, selecting this
     * outcome's wording at line 138; the longest wordings occupy the width exactly, so trailing blanks
     * are part of the value and are not trimmed away.</p>
     *
     * <p>Assumptions: the outcome is identified by its named code as well as by its two numbers, because
     * the name is what a caller branches on without embedding a numeric table. The naming trap that makes
     * this worth stating explicitly is in the reference and is cited, never reproduced:
     * app/cbl/CSUTLDTC.cbl line 62 declares the ACCEPTING condition with an all-zero feedback token
     * under a name that reads as though it meant the opposite, and line 129 selects on that name.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a rejection carrying the tolerated message number still answers with its verdict")
    void theToleratedRejectionIsNotSurfacedAsAFailure() throws Exception {
        DateEditValidator.FeedbackCode tolerated = DateEditValidator.FeedbackCode.UNSUPP_RANGE;

        this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, OUT_OF_RANGE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackCode").value(tolerated.name()))
                .andExpect(jsonPath("$.verdict").value(tolerated.verdict()))
                .andExpect(jsonPath("$.date").value(OUT_OF_RANGE_DATE))
                .andExpect(jsonPath("$.mask").value(DateEditValidator.DATE_FORMAT_MASK));

        assertThat(tolerated.messageNumber()).isEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);
        assertThat(tolerated.acceptable()).isFalse();
        assertThat(tolerated.verdict()).hasSize(DateConversionResponse.VERDICT_WIDTH);
        assertThat(DateEditValidator.RESULT_LENGTH).isEqualTo(ApiError.DATE_DIAGNOSTIC_WIDTH);
    }

    /**
     * Shows the submitted pair reaches the evaluation unaltered, an absent picture included.
     *
     * <p>Purpose: the boundary's whole obligation on this route is to bind two values and hand them on.
     * The captured request is compared against what was sent, and the answer is compared against what the
     * evaluation returned, so neither a silently applied default nor a reshaped answer could pass.</p>
     *
     * <p>Refactoring Rationale: the picture is defaulted by the evaluation and not here, and that is why
     * the absent case is asserted as {@code null} rather than as the default value. Defaulting at the
     * boundary would make an omitted picture indistinguishable from one a caller stated, and the answer
     * echoes the picture actually applied, so the distinction is the caller's to read.</p>
     *
     * <p>Alternatives Considered: comparing the rendered answer against a second, independent call of the
     * shared evaluation for the same input instead of verifying the interaction. Rejected because the two
     * cannot distinguish a boundary that reimplemented the rules identically from one that delegated; the
     * captured argument can, and it is the argument -- not the answer -- that the eliminated default would
     * have corrupted. The answer is nevertheless compared as well, so both directions are pinned.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the submitted date and picture are delegated verbatim, an absent picture included")
    void theDelegatedPairIsHandedOnVerbatimIncludingAnAbsentPicture() throws Exception {
        DateConversionService evaluator = mock(DateConversionService.class);
        DateConversionResponse stubbed = new DateConversionResponse(
                DateEditValidator.FeedbackCode.INVALID_DATE.name(),
                DateEditValidator.SEVERITY_VALID, 0,
                DateEditValidator.FeedbackCode.INVALID_DATE.verdict(),
                USABLE_DATE, DateEditValidator.DATE_FORMAT_MASK);
        when(evaluator.convert(any(DateConversionRequest.class))).thenReturn(stubbed);
        MockMvc delegating = defendedDispatcherOver(evaluator, STAMPED_CLOCK);
        ArgumentCaptor<DateConversionRequest> delegated =
                ArgumentCaptor.forClass(DateConversionRequest.class);

        delegating.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackCode").value(stubbed.feedbackCode()))
                .andExpect(jsonPath("$.verdict").value(stubbed.verdict()))
                .andExpect(jsonPath("$.mask").value(stubbed.mask()));
        delegating.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_PACKED_DATE)
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.BASELINE_DATE_FORMAT_MASK))
                .andExpect(status().isOk());

        verify(evaluator, times(2)).convert(delegated.capture());
        verifyNoMoreInteractions(evaluator);
        assertThat(delegated.getAllValues().get(0).date()).isEqualTo(USABLE_DATE);
        assertThat(delegated.getAllValues().get(0).mask()).isNull();
        assertThat(delegated.getAllValues().get(1).date()).isEqualTo(USABLE_PACKED_DATE);
        assertThat(delegated.getAllValues().get(1).mask())
                .isEqualTo(DateEditValidator.BASELINE_DATE_FORMAT_MASK);
    }

    /**
     * Shows the problem document is stamped from the clock the advice was given, not from the ambient one.
     *
     * <p>Purpose: a rendered refusal carries a reading of the moment it was produced, and a reading taken
     * from the ambient clock cannot be asserted at all -- only pattern-matched. Two dispatchers differing
     * in nothing but their instant are driven with the same request, and each stamp is compared against
     * the value the shared timestamp helper renders for that instant.</p>
     *
     * <p>Assumptions: the helper publishes a member that takes a clock and deliberately publishes no
     * argument-free equivalent, which is exactly why the clock is a constructor argument of the advice.
     * The rendered width is the twenty-six characters that helper publishes, so the assertion names the
     * published constant rather than the number.</p>
     *
     * <p>Assumptions: this controller reads no clock of its own, so nothing in a VERDICT can be stamped
     * and this case necessarily drives a refusal. That is the whole reason the case sits here rather than
     * beside the verdict cases above.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the refusal document is stamped from the injected clock and follows it")
    void theFixedClockMakesTheRefusalDocumentDeterministic() throws Exception {
        String stamped = TimestampFormatter.formatNow(STAMPED_CLOCK);

        this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .param(DateConversionController.PARAM_MASK, OVER_WIDE_MASK))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value(stamped));

        Clock alternate = Clock.fixed(ALTERNATE_INSTANT, ZoneOffset.UTC);
        String alternateStamp = TimestampFormatter.formatNow(alternate);
        defendedDispatcherOver(new DateConversionService(), alternate)
                .perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .param(DateConversionController.PARAM_MASK, OVER_WIDE_MASK))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value(alternateStamp));

        assertThat(stamped)
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH)
                .isNotEqualTo(alternateStamp);
    }

    /**
     * Shows a refusal names the offending parameter in the per-field array, in both reportable states.
     *
     * <p>Purpose: the per-field array is what a client renders against, so the entry's key and its state
     * are the assertion rather than the status alone. A supplied value that will not do and a value that
     * was never supplied are two distinguishable states and both are driven, because a client that
     * highlights a field also has to know whether to draw the blank marker.</p>
     *
     * <p>Assumptions: the state comes from the value rather than from the constraint, so a never-supplied
     * value is reported blank and a supplied one is reported not-acceptable. That reproduces the shape the
     * reference expands, whose template at app/cpy/CSSETATY.cpy lines 17 to 27 highlights a field when its
     * marker is either not-acceptable at line 18 or blank at line 19, and additionally writes a literal
     * asterisk into the field at lines 23 to 25 when it is blank. The three states themselves are the
     * tri-state markers app/app-transaction-type-db2/cbl/COTRTUPC.cbl declares twice over, at lines 94 to
     * 97 and again at lines 99 to 103 -- and the acceptable state there is LOW-VALUES rather than a
     * printable character, which is the one detail in this area easiest to invert by accident.</p>
     *
     * <p>Refactoring Rationale: that template gates its highlight on the screen-turn marker at its line
     * 20, and this API exposes no such member at all, so the presentation here is driven purely by the
     * response body. Nothing in a request can suppress or provoke the array, which is why no case here
     * sends a turn marker to enable it. The blank marker itself is a derived reading withheld from
     * serialisation, so it is asserted from the state the body carries rather than expected as a seventh
     * member of an entry.</p>
     *
     * <p>Assumptions: the aggregate sentence is taken from the FIRST entry of the array, which is the
     * discipline the shared advice applies and the analogue of the reference's own first-error-wins
     * guard -- app/app-transaction-type-db2/cbl/COTRTUPC.cbl tests that its message slot is still unset
     * before writing, at fifteen separate sites beginning with its declaration at line 168 and its first
     * use at line 362. Asserting the equality is what shows a second refusal cannot overwrite the
     * first.</p>
     *
     * @throws Exception if either request cannot be performed or a body cannot be parsed
     */
    @Test
    @DisplayName("a refusal names the date parameter and reports its state in the per-field array")
    void theRefusalNamesTheDateParameterInThePerFieldArray() throws Exception {
        String supplied = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, "2022")
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(status().isBadRequest()));
        JsonNode suppliedEntry = MAPPER.readTree(supplied).path("fieldErrors").get(0);

        assertThat(suppliedEntry.path("field").asString())
                .isEqualTo(DateConversionController.PARAM_DATE);
        assertThat(suppliedEntry.path("state").asString())
                .isEqualTo(FieldValidationFlag.NOT_OK.name());
        assertThat(MAPPER.readTree(supplied).path("message").asString())
                .isEqualTo(suppliedEntry.path("message").asString());
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);

        String blank = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, ""))
                .andExpect(status().isBadRequest()));
        JsonNode blankEntry = MAPPER.readTree(blank).path("fieldErrors").get(0);

        assertThat(blankEntry.path("field").asString())
                .isEqualTo(DateConversionController.PARAM_DATE);
        assertThat(blankEntry.path("state").asString()).isEqualTo(FieldValidationFlag.BLANK.name());
        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(FieldValidationFlag.valueOf(blankEntry.path("state").asString())
                .requiresBlankMarker()).isTrue();
    }

    /**
     * Shows a diagnostic wider than the inherited message line is withheld rather than rendered.
     *
     * <p>Purpose: the width is asserted as a GATE the boundary actually applies, not as a documented
     * aspiration. The handler's own width diagnostic is measured by raising it, and the rendered body for
     * the same request is then shown to carry the shared sentence within the inherited width instead.
     * Both halves are needed: measuring the diagnostic alone would not show what a caller receives, and
     * reading the body alone would not show that the longer sentence existed to be suppressed.</p>
     *
     * <p>Assumptions: the inherited width is seventy-five characters, read from
     * {@code 10 CCARD-ERROR-MSG PIC X(75).} at app/cpy/CVCRD01Y.cpy line 28 and from the identically
     * declared {@code 10 CCARD-RETURN-MSG PIC X(75).} on line 29 beside it, and published as a constant
     * so this case names it rather than the number. The same width is the reference's own message slot at
     * app/app-transaction-type-db2/cbl/COTRTUPC.cbl line 167.</p>
     *
     * <p>Assumptions: the two inherited slots disagree on their empty sentinel and neither is asserted
     * here, because the divergence would make either choice arbitrary: line 168 of that program marks its
     * slot empty with SPACES while line 30 of that copybook marks its own with LOW-VALUES. What is
     * asserted is the WIDTH, which the two agree on, and the sentence that is actually rendered.</p>
     *
     * <p>Alternatives Considered: naming the handler's diagnostic as a constant here and comparing
     * against it. It cannot be done -- that sentence is private to the controller -- and measuring the
     * raised condition instead is better than transcribing it, because a transcription could drift from
     * the sentence being suppressed and the case would then measure itself.</p>
     *
     * @throws Exception if the request cannot be performed or the body cannot be parsed
     */
    @Test
    @DisplayName("a diagnostic wider than the inherited message line does not reach the caller")
    void theRenderedSentenceHonoursTheSeventyFiveCharacterWidth() throws Exception {
        DateConversionController handler = new DateConversionController(new DateConversionService());

        assertThatThrownBy(() -> handler.evaluateDate("2022-07-1", DateEditValidator.DATE_FORMAT_MASK))
                .isInstanceOf(ClientInputException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage().length())
                        .isGreaterThan(ApiError.MESSAGE_RENDERING_WIDTH));

        String body = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, "2022-07-1")
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(status().isBadRequest()));

        assertThat(MAPPER.readTree(body).path("message").asString())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_VALIDATION_FAILED)
                .hasSizeLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH)
                .doesNotContain("2022-07-1")
                .doesNotContain(DateEditValidator.DATE_FORMAT_MASK);
    }

    /**
     * Shows the correlation identity is echoed when supplied, minted when not, and cleared afterwards.
     *
     * <p>Purpose: the published contract declares the correlation header on this operation's answer, so
     * the three obligations of the shared filter are asserted where a caller actually meets them -- a
     * supplied identity comes back unaltered, an absent one is answered under a minted identity that the
     * filter's own published rule admits, and the logging context is populated during the request and
     * emptied after it.</p>
     *
     * <p>Assumptions: the logging context is observed from INSIDE the request, through a substituted
     * evaluation that reads it while answering. That substitution is required rather than convenient: the
     * filter removes the entry in a finally block, so a read taken after the request has returned can only
     * ever see it absent and could not distinguish a populated context from one that was never set. This
     * is the second of the two cases that needs a substituted collaborator, and it is why.</p>
     *
     * <p>Assumptions: the identity mirrors what the reference inherits and restores rather than inventing
     * a scheme. app/app-vsam-mq/cbl/CODATE01.cbl saves the inbound identity at its line 319 alongside the
     * reply destination at line 320 and the message identity at line 321, under the condition it tests at
     * line 312, and restores BOTH the message identity and the correlation identity onto the reply in the
     * paragraph opening at line 366, at its lines 373 and 374. The declared width of those saved slots is
     * twenty-four characters at that program's lines 55 and 56, which is the same bound the shared filter
     * publishes, so an identity this boundary accepts is one the queue attribute can still carry.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the correlation identity is echoed, minted when absent, and cleared after the request")
    void theCorrelationIdentityIsEchoedAndMintedThroughTheSharedFilter() throws Exception {
        AtomicReference<String> observedInFlight = new AtomicReference<>();
        MockMvc observing = defendedDispatcherOver(
                evaluationObserving(observedInFlight), STAMPED_CLOCK);

        observing.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, INBOUND_CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER,
                        INBOUND_CORRELATION_ID));

        assertThat(CorrelationIdFilter.isConformingCorrelationId(INBOUND_CORRELATION_ID)).isTrue();
        assertThat(observedInFlight.get()).isEqualTo(INBOUND_CORRELATION_ID);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();

        observedInFlight.set(null);
        String minted = observing.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);

        assertThat(minted).isNotNull().isNotEqualTo(INBOUND_CORRELATION_ID);
        assertThat(CorrelationIdFilter.isConformingCorrelationId(minted)).isTrue();
        assertThat(minted.length()).isLessThanOrEqualTo(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
        assertThat(observedInFlight.get()).isEqualTo(minted);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    /**
     * Shows the route retains nothing between requests and honours no turn marker.
     *
     * <p>Purpose: statelessness is asserted from three directions a single request cannot show -- that no
     * session is created to hold anything in, that no cookie is issued to find one again by, and that a
     * request carrying a screen-turn marker is answered exactly as one without it.</p>
     *
     * <p>Assumptions: statelessness here is the reference's OWN contract rather than a preference this
     * migration introduced. app/app-vsam-mq/cbl/CODATE01.cbl line 2 declares its program identity with
     * the initial-state attribute, which reinstates its working storage on every invocation, so nothing
     * that program held survived one request either. The migrated form removes the screen-turn marker
     * outright rather than porting it: identity arrives in a validated token and the selection context in
     * the request itself, so there is no remembered turn for a marker to discriminate.</p>
     *
     * <p>Assumptions: no caller is installed on these two requests, deliberately. The security test
     * support persists its caller through a context repository that would create a session to hold it,
     * which is exactly the artefact this case asserts the absence of -- so the authority-shaped case is a
     * separate member and says so.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("no session, no cookie, and an undeclared turn marker changes nothing")
    void theRouteHoldsNothingBetweenRequests() throws Exception {
        MvcResult plain = this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(plain.getRequest().getSession(false)).isNull();
        assertThat(plain.getResponse().getHeaders("Set-Cookie")).isEmpty();

        String withMarker = bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .param(UNDECLARED_REENTRY_PARAMETER, "1"))
                .andExpect(status().isOk()));

        assertThat(withMarker).isEqualTo(plain.getResponse().getContentAsString());
        assertThat(memberNamesOf(withMarker)).doesNotContain(UNDECLARED_REENTRY_PARAMETER);
    }

    /**
     * Shows the handler reaches the same verdict whichever group the caller holds, or none.
     *
     * <p>Purpose: the complement of what the configuration test package owns. That package asserts which
     * authority the deployed chain demands of each route; this case asserts that the HANDLER contributes
     * nothing to that decision, so the two together leave exactly one owner of the rule. Three callers
     * differing only in their group membership receive byte-identical answers.</p>
     *
     * <p>Assumptions: the caller is minted by the security test support and its authorities are derived by
     * the REAL group converter from a token claim, rather than being asserted directly. The converter
     * publishes the claim name and both group names as constants, and it admits only the groups it
     * recognises, so a caller built this way is the caller a deployment would derive. No real token, no
     * reachable issuer and no credential appears: the token value below identifies nobody and carries no
     * signature.</p>
     *
     * <p>Alternatives Considered: re-driving the deployed security chain here so the three answers were
     * 401, 403 and 200. Rejected on ownership rather than on cost -- the configuration test package
     * already asserts that model against the chain's own installed authorization managers, and a rule
     * asserted in two places can be satisfied in one of them and reported as satisfied in both, leaving
     * the weaker assertion the one nobody maintains. The dispatchers here install no chain at all, which
     * is also what keeps an unmounted address distinguishable from a refused caller.</p>
     *
     * @throws Exception if any of the three requests cannot be performed
     */
    @Test
    @DisplayName("the handler decides no authority: either group and neither receive one answer")
    void theHandlerMakesNoAuthorityDecisionOfItsOwn() throws Exception {
        String asUser = verdictFor(List.of(JwtRoleConverter.USER_AUTHORITY));
        String asAdmin = verdictFor(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
        String asNeither = verdictFor(List.of("carddemo-unrecognised-group"));

        assertThat(asUser).isEqualTo(asAdmin).isEqualTo(asNeither);
        assertThat(MAPPER.readTree(asUser).path("severity").asInt())
                .isEqualTo(DateEditValidator.SEVERITY_VALID);
        assertThat(authoritiesFrom(List.of(JwtRoleConverter.USER_AUTHORITY)))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(JwtRoleConverter.USER_AUTHORITY);
        assertThat(authoritiesFrom(List.of("carddemo-unrecognised-group"))).isEmpty();
    }

    /**
     * Assembles a dispatcher that registers the shared advice and the shared correlation filter.
     *
     * <p>Assumptions: the advice is registered by hand because a dispatcher assembled standalone has no
     * context to discover it from, and the filter is added rather than declared for the same reason. Both
     * are the deployed types rather than substitutes, so what the cases observe is the deployed rendering
     * and not a local approximation of it.</p>
     *
     * <p>Assumptions: the filter is given the same clock the advice is given, so a refusal the FILTER
     * itself writes would be stamped identically to one the advice writes. No case here provokes that
     * refusal, and passing one clock rather than two is what keeps the possibility from becoming a
     * discrepancy if a case ever does.</p>
     *
     * @param evaluator the holder of the date rules to build the controller over; must not be
     *     {@code null}
     * @param stamp the clock the shared advice reads when stamping a problem document; must not be
     *     {@code null}
     * @return a dispatcher over the one date-evaluation handler with the shared advice and filter
     *     installed; never {@code null}
     */
    private static MockMvc defendedDispatcherOver(DateConversionService evaluator, Clock stamp) {
        return MockMvcBuilders
                .standaloneSetup(new DateConversionController(evaluator))
                .addFilters(new CorrelationIdFilter(stamp))
                .setControllerAdvice(new GlobalExceptionHandler(stamp))
                .build();
    }

    /**
     * Assembles the same dispatcher with no advice and no filter, for the contrast in the first case.
     *
     * <p>Assumptions: this exists solely so that the value of registering the advice can be asserted
     * rather than asserted about. It is deliberately not used by any other case: every other case here
     * depends on the shared rendering being present, and driving them through this dispatcher would
     * observe the framework's default handling instead.</p>
     *
     * @param evaluator the holder of the date rules to build the controller over; must not be
     *     {@code null}
     * @return a dispatcher over the same handler with nothing registered to render a refusal; never
     *     {@code null}
     */
    private static MockMvc undefendedDispatcherOver(DateConversionService evaluator) {
        return MockMvcBuilders.standaloneSetup(new DateConversionController(evaluator)).build();
    }

    /**
     * Builds a substituted evaluation that records the logging context it was called under.
     *
     * <p>Assumptions: the recorded value is read while the request is still in flight, because the shared
     * filter empties the logging context in a finally block and a read taken afterwards can only ever see
     * it absent. The answer it returns is a usable verdict so the request completes normally and the
     * response header can be asserted on the same call.</p>
     *
     * @param sink the holder the observed logging-context value is written into; must not be
     *     {@code null}
     * @return a substituted evaluation answering one usable verdict and recording the context; never
     *     {@code null}
     */
    private static DateConversionService evaluationObserving(AtomicReference<String> sink) {
        DateConversionService observing = mock(DateConversionService.class);
        when(observing.convert(any(DateConversionRequest.class))).thenAnswer(invocation -> {
            sink.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));
            return new DateConversionResponse(
                    DateEditValidator.FeedbackCode.INVALID_DATE.name(),
                    DateEditValidator.SEVERITY_VALID, 0,
                    DateEditValidator.FeedbackCode.INVALID_DATE.verdict(),
                    USABLE_DATE, DateEditValidator.DATE_FORMAT_MASK);
        });
        return observing;
    }

    /**
     * Drives one usable candidate as a caller holding the stated groups and returns the rendered verdict.
     *
     * <p>Assumptions: the caller's authorities are derived by the deployed group converter from the claim
     * it publishes, so a group it does not recognise yields no authority at all -- which is what makes the
     * third caller in the authority case genuinely group-less rather than merely differently named.</p>
     *
     * @param groups the group names to place in the token claim the converter reads; must not be
     *     {@code null}
     * @return the rendered verdict body for that caller; never {@code null}
     * @throws Exception if the request cannot be performed, which surfaces here rather than being
     *     reported as an empty body
     */
    private String verdictFor(List<String> groups) throws Exception {
        return bodyOf(this.dispatcher.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, USABLE_DATE)
                        .with(jwt().jwt(tokenFor(groups)).authorities(authoritiesFrom(groups))))
                .andExpect(status().isOk()));
    }

    /**
     * Mints an unsigned token carrying the stated groups in the claim the deployed converter reads.
     *
     * <p>Assumptions: the token value identifies nobody and carries no signature, and the algorithm header
     * names none. It is never presented to a decoder -- the security test support installs the caller
     * directly -- so nothing here needs to be verifiable, and a value that could be mistaken for a real
     * credential is what this deliberately avoids.</p>
     *
     * @param groups the group names to place in the claim; must not be {@code null}
     * @return an unsigned token carrying those groups; never {@code null}
     */
    private static Jwt tokenFor(List<String> groups) {
        return Jwt.withTokenValue("not-a-token")
                .header("alg", "none")
                .subject("REFUSR01")
                .claim(JwtRoleConverter.GROUPS_CLAIM, groups)
                .build();
    }

    /**
     * Derives the authorities the deployed converter grants a token carrying the stated groups.
     *
     * <p>Assumptions: the converter is constructed with the two group names it itself publishes, because
     * it refuses any other configured pair; that refusal is its own contract and is asserted in the shared
     * kernel rather than here.</p>
     *
     * @param groups the group names the token carries; must not be {@code null}
     * @return the authorities the converter grants, which is empty for groups it does not recognise;
     *     never {@code null}
     */
    private static Collection<GrantedAuthority> authoritiesFrom(List<String> groups) {
        return new JwtRoleConverter(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY)
                .convert(tokenFor(groups));
    }

    /**
     * Reads the rendered body of a completed exchange as text.
     *
     * <p>Assumptions: the body is taken as text rather than as a parsed tree, because two of the cases
     * above compare whole bodies for equality and one searches a body for a label that a member-by-member
     * comparison could not see.</p>
     *
     * @param exchange the completed exchange to read; must not be {@code null}
     * @return the rendered body as text, empty when nothing was written; never {@code null}
     * @throws Exception if the body cannot be read as text, which an encoding the response never declares
     *     would cause
     */
    private static String bodyOf(ResultActions exchange) throws Exception {
        return exchange.andReturn().getResponse().getContentAsString();
    }

    /**
     * Reads the member names a rendered object carries, at its top level only.
     *
     * <p>Assumptions: the order is preserved, because one case above asserts the closed member set the
     * published schema declares and the order those members are written in is part of what a reader
     * compares against the document.</p>
     *
     * @param json the rendered object to inspect; must not be {@code null}
     * @return the member names in the order they were written; never {@code null}
     * @throws JacksonException if the value cannot be parsed, which a body that is not an object would
     *     cause and which must surface rather than yielding an empty set that silently satisfies a census
     */
    private static Set<String> memberNamesOf(String json) throws JacksonException {
        Set<String> names = new LinkedHashSet<>();
        JsonNode tree = MAPPER.readTree(json);
        for (String name : tree.propertyNames()) {
            names.add(name);
        }
        return names;
    }

    /**
     * Collects a raised condition together with every cause beneath it.
     *
     * <p>Assumptions: the condition itself is included alongside its causes, because a dispatcher may
     * either wrap what escapes it or let it through unwrapped and the contrast case must hold under both.
     * Walking a bounded number of links rather than looping until null is what keeps a self-referential
     * cause chain from turning a failing assertion into a hang.</p>
     *
     * @param raised the condition to walk; must not be {@code null}
     * @return the condition and its causes, outermost first; never {@code null}
     */
    private static List<Throwable> causeChainOf(Throwable raised) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = raised;
        while (current != null && chain.size() < MAX_CAUSE_DEPTH) {
            chain.add(current);
            current = current.getCause() == current ? null : current.getCause();
        }
        return chain;
    }
}
