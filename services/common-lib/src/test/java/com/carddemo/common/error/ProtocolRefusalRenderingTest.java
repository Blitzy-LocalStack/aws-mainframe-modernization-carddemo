package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Verifies that the four PROTOCOL-level refusals a caller can provoke are answered with their own
 * status class rather than as a server fault.
 *
 * <p>Refactoring Rationale: every case in this class was previously answered HTTP 500 with the critical
 * severity and the abend block reserved for a service that has actually failed. Three of the four
 * conditions -- an unsupported request content type, an unsupported method and an unacceptable
 * representation -- are raised by the framework as SERVLET exceptions rather than runtime ones, so they
 * fell past the runtime arm to the unclaimed-failure handler; the fourth, an unpublished path, was
 * simply unclaimed. The consequence was two-fold and both halves are asserted here: a client was told
 * to retry a request that could never succeed, and the error stream carried one critical record for
 * every mistyped URL and every browser sending its default accept header, which is the condition under
 * which a genuine 500 goes unnoticed.</p>
 *
 * <p>Assumptions: each case asserts the STATUS, the CODE, the SENTENCE and the log LEVEL together,
 * because the finding they close was about the status class while the observability half of it was about
 * the level. A fix that returned the right status and still logged at error would leave the alert
 * channel exactly as noisy as it was.</p>
 *
 * <p>Assumptions: the advice is exercised directly rather than through a container, so what is asserted
 * is the advice's own contract. The one behaviour that cannot be seen this way -- that the framework
 * writes the not-acceptable body at all, rather than failing negotiation a second time -- is asserted
 * here as the explicit content type on the returned entity, which is the mechanism that makes it true,
 * and is additionally verified at runtime against the running service.</p>
 */
class ProtocolRefusalRenderingTest {

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC);

    /** A request path with no protected identifier in it, so the assertions read the path verbatim. */
    private static final String PATH = "/api/v1/auth/users";

    /** The advice under test, rebuilt per test so no state can carry between them. */
    private GlobalExceptionHandler handler;

    /** The appender capturing what the advice actually wrote, attached for the duration of a test. */
    private ListAppender<ILoggingEvent> captured;

    /** The advice's own logger, held so the appender can be detached again. */
    private Logger adviceLogger;

    /**
     * Attaches a list appender to the advice's logger and builds the advice under test.
     *
     * <p>Assumptions: the level is lowered to the warn threshold the four arms log at, so a capture that
     * comes back empty means the arm did not log rather than that the configuration filtered it.</p>
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
     * Confirms an unpublished path is answered 404 with the path sentence rather than 500 with an abend.
     */
    @Test
    @DisplayName("an unpublished path answers 404 with the path sentence and no abend block")
    void anUnpublishedPathAnswers404() {
        ResponseEntity<ApiError> response = this.handler.onAbsentPath(
                new NoResourceFoundException(HttpMethod.GET, "/actuator/loggers", "loggers"),
                requestFor("/actuator/loggers"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiError.CODE_NOT_FOUND);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_NO_SUCH_PATH);
        assertThat(response.getBody().severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(response.getBody().abend()).isNull();
        assertThat(response.getBody().fieldErrors()).isEmpty();
        assertThat(response.getBody().path()).isEqualTo("/actuator/loggers");
        assertThat(loggedLevels()).containsExactly(Level.WARN);
    }

    /**
     * Confirms the dispatcher's own no-handler condition reaches the same arm as the resource one.
     *
     * <p>Assumptions: both types are claimed by that arm because which of them fires is decided by
     * whether a service serves static resources, which is a property of configuration this advice cannot
     * read. Asserting the second type is what stops the arm from being narrowed to the first one on the
     * reasoning that it is the only one observed in the services as they are configured today.</p>
     */
    @Test
    @DisplayName("the dispatcher's no-handler condition answers 404 through the same arm")
    void theNoHandlerConditionAnswers404() {
        ResponseEntity<ApiError> response = this.handler.onAbsentPath(
                new NoHandlerFoundException("GET", "/nothing/here", new HttpHeaders()),
                requestFor("/nothing/here"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_NO_SUCH_PATH);
    }

    /**
     * Confirms an unsupported request content type is answered 415 with its own code and sentence.
     */
    @Test
    @DisplayName("an unsupported request content type answers 415 with its own code")
    void anUnsupportedContentTypeAnswers415() {
        ResponseEntity<ApiError> response = this.handler.onUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                        List.of(MediaType.APPLICATION_JSON)),
                requestFor(PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(ApiError.CODE_UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(response.getBody().abend()).isNull();
        assertThat(response.getBody().fieldErrors()).isEmpty();
        assertThat(loggedLevels()).containsExactly(Level.WARN);
    }

    /**
     * Confirms the type the caller sent is written to neither the response nor the operational record.
     *
     * <p>Assumptions: a media type is a header value this code did not author, so it is treated exactly
     * as the unreadable-body arm treats a parse diagnostic: the constraint that was breached is recorded
     * and the caller-supplied value that breached it is not.</p>
     */
    @Test
    @DisplayName("the rejected content type reaches neither the body nor the log")
    void theRejectedContentTypeIsNotDisclosed() {
        ResponseEntity<ApiError> response = this.handler.onUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException(
                        MediaType.parseMediaType("application/x-secret-probe"),
                        List.of(MediaType.APPLICATION_JSON)),
                requestFor(PATH));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("x-secret-probe");
        assertThat(loggedMessages()).isNotEmpty();
        assertThat(loggedMessages())
                .allSatisfy(line -> assertThat(line).doesNotContain("x-secret-probe"));
    }

    /**
     * Confirms an unsupported method is answered 405 and names the admitted methods in {@code Allow}.
     */
    @Test
    @DisplayName("an unsupported method answers 405 and publishes the admitted methods")
    void anUnsupportedMethodAnswers405() {
        ResponseEntity<ApiError> response = this.handler.onMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PATCH", Set.of("GET", "PUT", "DELETE")),
                requestFor(PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(ApiError.CODE_METHOD_NOT_ALLOWED);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE);
        assertThat(loggedLevels()).containsExactly(Level.WARN);
    }

    /**
     * Confirms the header is OMITTED rather than sent empty when the framework names no admitted method.
     *
     * <p>Assumptions: an empty {@code Allow} states that no method at all is admitted at the path, which
     * is a claim about the path that this advice is in no position to make, so the absence of the header
     * is the assertion rather than an incidental detail.</p>
     */
    @Test
    @DisplayName("an unsupported method with no admitted set omits the Allow header entirely")
    void anUnsupportedMethodWithNoAdmittedSetOmitsAllow() {
        ResponseEntity<ApiError> response = this.handler.onMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PATCH"), requestFor(PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.ALLOW)).isFalse();
    }

    /**
     * Confirms an unacceptable representation is answered 406 as JSON whatever the caller asked for.
     *
     * <p>Assumptions: the explicit content type is asserted because it is the mechanism the fix rests
     * on. The framework's converter selection uses a concrete type already present on the response
     * instead of negotiating one, so without this member the handler would fail exactly where the
     * generic one did and the observed 403 on {@code /error} would survive the fix.</p>
     */
    @Test
    @DisplayName("an unacceptable representation answers 406 with an explicit JSON content type")
    void anUnacceptableRepresentationAnswers406AsJson() {
        ResponseEntity<ApiError> response = this.handler.onUnacceptableRepresentation(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                requestFor(PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_NOT_ACCEPTABLE);
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
        assertThat(response.getBody().severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(response.getBody().abend()).isNull();
        assertThat(loggedLevels()).containsExactly(Level.WARN);
    }

    /**
     * Confirms the four codes are distinct from each other and from the shape's own published set.
     *
     * <p>Assumptions: a client branches on the code, so two conditions calling for different corrective
     * action must not share one. The 404 arm deliberately DOES share the shape's not-found code, because
     * the code names the status class and both not-found conditions are the same class; the distinction
     * between them travels in the sentence, which the case above asserts.</p>
     */
    @Test
    @DisplayName("the protocol codes are distinct and follow the published spelling")
    void theProtocolCodesAreDistinct() {
        assertThat(List.of(ApiError.CODE_METHOD_NOT_ALLOWED,
                        GlobalExceptionHandler.CODE_NOT_ACCEPTABLE,
                        ApiError.CODE_UNSUPPORTED_MEDIA_TYPE,
                        GlobalExceptionHandler.CODE_FORBIDDEN,
                        ApiError.CODE_VALIDATION,
                        ApiError.CODE_NOT_FOUND))
                .doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).matches("CARDDEMO-0\\d{3}"));

        assertThat(ApiError.CODE_METHOD_NOT_ALLOWED).isEqualTo("CARDDEMO-0405");
        assertThat(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE).isEqualTo("CARDDEMO-0406");
        assertThat(ApiError.CODE_UNSUPPORTED_MEDIA_TYPE).isEqualTo("CARDDEMO-0415");
    }

    /**
     * Confirms the four new sentences carry no framework grammar and fit the published rendering band.
     *
     * <p>Assumptions: the band is asserted here as well as in the shape's own test because these four
     * are the sentences most likely to be reworded -- they are the only ones in this advice with no
     * reference literal behind them -- and a rewording that overran the band would otherwise be found by
     * a client rendering it into a fixed-height message line.</p>
     */
    @Test
    @DisplayName("the four protocol sentences fit the rendering band and read as CardDemo prose")
    void theProtocolSentencesFitTheBand() {
        assertThat(List.of(GlobalExceptionHandler.MESSAGE_NO_SUCH_PATH,
                        GlobalExceptionHandler.MESSAGE_UNSUPPORTED_MEDIA_TYPE,
                        GlobalExceptionHandler.MESSAGE_METHOD_NOT_ALLOWED,
                        GlobalExceptionHandler.MESSAGE_NOT_ACCEPTABLE))
                .allSatisfy(sentence -> {
                    assertThat(sentence).isNotBlank();
                    assertThat(sentence.length())
                            .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
                    assertThat(sentence).doesNotContain("must match");
                    assertThat(sentence).doesNotContain("size must be between");
                });
    }

    /**
     * Builds a request the advice can read a path from.
     *
     * @param path the request path the container would report; must not be {@code null}
     * @return a mock request carrying that path, never {@code null}
     */
    private static MockHttpServletRequest requestFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    /**
     * Reads the formatted messages the advice wrote during the current test.
     *
     * @return the formatted lines a log pipeline would ship, never {@code null}
     */
    private List<String> loggedMessages() {
        return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Reads the levels the advice logged at during the current test.
     *
     * @return the levels in the order they were written, never {@code null}
     */
    private List<Level> loggedLevels() {
        return this.captured.list.stream().map(ILoggingEvent::getLevel).toList();
    }
}
