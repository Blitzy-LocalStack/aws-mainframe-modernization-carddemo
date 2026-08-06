package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Verifies that the shared advice never lets a primary account number out of the process, through
 * either of the two destinations it writes a request path to.
 *
 * <p>Assumptions: the path used throughout is the resolved form of the published card route,
 * {@code /api/v1/cards/{cardNumber}}, whose parameter is declared {@code pattern '^[0-9]{16}$'} and
 * described as supplied in full and unmasked. Using the real route rather than a short placeholder is
 * what makes these assertions about the exposure that actually exists, and the number itself is a
 * synthetic test value that identifies no account.</p>
 *
 * <p>Assumptions: two destinations are asserted separately because they fail separately. The emitted
 * problem shape is read from the returned entity; the operational record is read from a list appender
 * attached to the advice's own logger, so the assertion is made against the formatted message a log
 * pipeline would ship rather than against the argument the call site passed.</p>
 */
class GlobalExceptionHandlerTest {

    /** A synthetic sixteen-digit card number, the width the published path parameter admits. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The resolved card route carrying that number, exactly as a container would report it. */
    private static final String CARD_PATH = "/api/v1/cards/" + CARD_NUMBER;

    /** The rendering the published contract gives as its example: twelve masks, four retained. */
    private static final String MASKED_CARD_PATH = "/api/v1/cards/************1111";

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /** The advice under test, rebuilt per test so no state can carry between them. */
    private GlobalExceptionHandler handler;

    /** The appender capturing what the advice actually wrote, attached for the duration of a test. */
    private ListAppender<ILoggingEvent> captured;

    /** The advice's own logger, held so the appender can be detached again. */
    private Logger adviceLogger;

    /**
     * Attaches a list appender to the advice's logger and builds the advice under test.
     *
     * <p>Assumptions: the logger is addressed by the class rather than by a literal name, so a rename
     * cannot leave this test silently capturing nothing. The level is lowered explicitly because the
     * two sites asserted here log at warn and at error, and a configuration that raised the threshold
     * would make an empty capture look like a passing masking assertion.</p>
     */
    @BeforeEach
    void attachAppender() {
        this.handler = new GlobalExceptionHandler(CLOCK);
        this.adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.adviceLogger.addAppender(this.captured);
        this.adviceLogger.setLevel(Level.WARN);
    }

    /**
     * Detaches the appender so one test's capture cannot be read by the next.
     */
    @AfterEach
    void detachAppender() {
        this.adviceLogger.detachAppender(this.captured);
        this.captured.stop();
    }

    /**
     * Confirms the 404 site masks the number in the emitted problem shape, rendering it exactly as the
     * published example does.
     */
    @Test
    @DisplayName("an absent record emits the path with the card number masked to its last four")
    void absentRecordEmitsMaskedPath() {
        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException(), requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(response.getBody().path()).doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms the 404 site writes no unmasked number into the operational record, which is the
     * destination the finding this test closes was raised against.
     */
    @Test
    @DisplayName("an absent record logs the masked path and never the card number")
    void absentRecordLogsMaskedPath() {
        this.handler.onMissingRecord(new NoSuchElementException(), requestFor(CARD_PATH));

        assertThat(loggedMessages()).isNotEmpty();
        assertThat(loggedMessages()).allSatisfy(line -> {
            assertThat(line).doesNotContain(CARD_NUMBER);
            assertThat(line).contains(MASKED_CARD_PATH);
        });
    }

    /**
     * Confirms the 500 site behaves identically, because both destinations read the same helper and a
     * fix applied at one call site only would leave this one exposed.
     */
    @Test
    @DisplayName("an unexpected failure logs and emits the masked path at error level")
    void unexpectedFailureMasksBothDestinations() {
        ResponseEntity<ApiError> response = this.handler.onUnexpectedFailure(
                new IllegalStateException("failure text is never rendered"), requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(loggedMessages()).isNotEmpty();
        assertThat(loggedMessages()).allSatisfy(line -> assertThat(line).doesNotContain(CARD_NUMBER));
    }

    /**
     * Confirms a run longer than the declared width is masked too, so an endpoint that ever accepted a
     * seventeen-to-nineteen-digit number could not start passing one through unnoticed.
     */
    @Test
    @DisplayName("a nineteen-digit run is masked with only its last four retained")
    void longerThanDeclaredWidthIsMasked() {
        ResponseEntity<ApiError> response = this.handler.onMissingRecord(
                new NoSuchElementException(), requestFor("/api/v1/cards/4111111111111111222"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/cards/***************1222");
    }

    /**
     * Confirms the shorter identifiers a migrated path actually carries are left intact, so the
     * masking does not cost the diagnostic value of the field where there is no exposure to remove.
     *
     * <p>Assumptions: eleven digits is {@code ACCT-ID PIC 9(11)} and nine is a customer identifier, so
     * both are below the threshold by declaration rather than by coincidence.</p>
     */
    @Test
    @DisplayName("account and customer identifiers are carried through unmasked")
    void shorterIdentifiersAreNotMasked() {
        ResponseEntity<ApiError> response = this.handler.onMissingRecord(
                new NoSuchElementException(), requestFor("/api/v1/accounts/00000000011/customer/9"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/accounts/00000000011/customer/9");
    }

    /**
     * Confirms every qualifying run in one path is masked, not merely the first, and that the text
     * around them is preserved byte for byte.
     */
    @Test
    @DisplayName("two card-shaped runs in one path are both masked")
    void everyQualifyingRunIsMasked() {
        ResponseEntity<ApiError> response = this.handler.onMissingRecord(new NoSuchElementException(),
                requestFor("/api/v1/admin/cards/4111111111111111/replaces/5555444433332222"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path())
                .isEqualTo("/api/v1/admin/cards/************1111/replaces/************2222");
    }

    /**
     * Confirms masking is idempotent: a value that already carries the mask characters is returned
     * unchanged, so a path assembled from an already-masked value is not masked twice into a shorter
     * or differently-shaped string.
     */
    @Test
    @DisplayName("an already-masked path is returned unchanged")
    void maskingIsIdempotent() {
        ResponseEntity<ApiError> response = this.handler
                .onMissingRecord(new NoSuchElementException(), requestFor(MASKED_CARD_PATH));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
    }

    /**
     * Confirms the absent-request case still yields the empty string, which is the value the published
     * contract names for a problem shape with no path available.
     */
    @Test
    @DisplayName("a missing servlet request yields the empty path")
    void absentRequestYieldsEmptyPath() {
        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException(), null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEmpty();
    }

    /**
     * Confirms the width this package records from the baseline is not the width the transport mints,
     * which is the reason that constant is provenance rather than an enforceable contract.
     *
     * <p>Assumptions: this is the assertion that makes the narrowed visibility of
     * {@code ApiError.CORRELATION_ID_WIDTH} safe to rely on. If the two ever became equal, the
     * constant would stop being a record of a superseded width and the note on it would need
     * rewriting; asserting the inequality is what makes that a build failure rather than a silent
     * drift.</p>
     */
    @Test
    @DisplayName("the recorded baseline correlation width is narrower than the minted identity")
    void recordedCorrelationWidthIsNotTheLiveContract() {
        assertThat(ApiError.CORRELATION_ID_WIDTH).isEqualTo(20);
        assertThat(ApiError.CORRELATION_ID_WIDTH)
                .isLessThan(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
    }

    /**
     * Confirms the narrowing is a property of the shared path reader rather than of one handler, by
     * asserting it on a second handler that emits a different code and status.
     *
     * <p>Assumptions: the reading of the request target is factored into one helper inside the advice,
     * so covering a second handler that reaches it shows the guarantee belongs to the reader and not to
     * one response shape.</p>
     */
    @Test
    @DisplayName("the same masking applies to the 403 body")
    void cardNumberInTargetIsMaskedOutOfForbiddenBody() {
        ResponseEntity<ApiError> response = this.handler
                .onAccessDenied(new AccessDeniedException("denied"), requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(response.getBody().path()).doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms the target every published single-card route actually carries is echoed unchanged.
     *
     * <p>Assumptions: this is the case that matters for diagnosis. A narrowing measure that also
     * altered a well-formed opaque selector would make the path member useless for the purpose it
     * exists for, so the pass-through is asserted rather than assumed.</p>
     */
    @Test
    @DisplayName("a published opaque-selector target is echoed unchanged")
    void publishedOpaqueTargetIsEchoedUnchanged() {
        String target = "/api/v1/cards/8Qk2vN7pR4tL0aXsY6bJdF/unmasked";

        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException(), requestFor(target));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(target);
    }

    /**
     * Confirms the emitted shape masks a path supplied directly to it, so the guarantee does not
     * depend on this advice being the only producer of one.
     *
     * <p>Assumptions: this asserts the canonical constructor rather than the advice, because a service
     * adding its own advice, or a component assembling a failure of its own, reaches the shape without
     * passing through the reader the tests above drive. The two together are what make the masking a
     * rule rather than a convention.</p>
     */
    @Test
    @DisplayName("the emitted shape masks a path supplied directly to it")
    void suppliedPathIsMaskedByTheShapeItself() {
        ApiError emitted = ApiError.of(ApiError.CODE_NOT_FOUND, "absent", HttpStatus.NOT_FOUND.value(),
                "", CARD_PATH, CLOCK);

        assertThat(emitted.path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(emitted.path()).doesNotContain(CARD_NUMBER);
    }

    /**
     * Builds a request reporting the given URI, with no query string.
     *
     * @param uri the request URI a container would report, never {@code null}
     * @return a request whose {@code getRequestURI} returns exactly {@code uri}
     */
    private static MockHttpServletRequest requestFor(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    /**
     * Reads the formatted messages the advice logged during the current test.
     *
     * @return one entry per captured event, each already interpolated, so an assertion reads what a
     *     log pipeline would ship rather than the pattern and its arguments separately
     */
    private java.util.List<String> loggedMessages() {
        return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
