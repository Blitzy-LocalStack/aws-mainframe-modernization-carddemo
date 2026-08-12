package com.carddemo.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.error.ApiError;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies the one property this filter exists for -- that an over-large body is refused before it is
 * read -- and the two properties that make the refusal safe to serve.
 *
 * <p>Assumptions: the assertions are written in terms of what a caller and the rest of the chain
 * observe, never in terms of how the filter is built. A test that asserted the filter had consulted a
 * header would pass for an implementation that consulted it and then read the body anyway, which is
 * exactly the failure this filter exists to prevent, so the over-large cases assert that the body was
 * never opened and that the chain was never entered.</p>
 *
 * <p>Assumptions: the bound used throughout is deliberately tiny. The production default is sixty-four
 * kibibytes, and asserting the boundary at that size would allocate two arrays of that length per case
 * to prove a comparison that holds at any width. One case pins the default itself, so a change to it
 * still fails a test.</p>
 *
 * <p>Assumptions: the card-shaped value placed in a refused body below is a synthetic test value
 * identifying no account, and it is never reproduced into an assertion message -- only its ABSENCE from
 * the response is asserted.</p>
 */
class RequestBodySizeFilterTest {

    /** A bound small enough that every fixture in this class is a handful of bytes. */
    private static final long SMALL_BOUND = 32L;

    /** A fixed reading, so the refusal body's timestamp is a constant rather than a wall clock. */
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-02-01T09:15:30.123456Z"),
            ZoneOffset.UTC);

    /** A synthetic sixteen-digit value, placed in a body to prove no fragment of it is echoed. */
    private static final String PAN_SHAPED_VALUE = "4111111111111111";

    /**
     * Confirms the production default is the figure the charter argues for.
     *
     * <p>Assumptions: pinned as a test rather than left to the constant's own declaration, because the
     * figure is derived from the largest published request body and a later change to it should have to
     * restate that derivation rather than pass silently.</p>
     */
    @Test
    @DisplayName("the default bound is 65536 bytes")
    void theDefaultBoundIsSixtyFourKibibytes() {
        assertThat(RequestBodySizeFilter.DEFAULT_MAX_BODY_BYTES).isEqualTo(65_536L);
    }

    /**
     * Confirms the configured bound is reported as supplied.
     */
    @Test
    @DisplayName("the configured bound is reported")
    void theConfiguredBoundIsReported() {
        assertThat(new RequestBodySizeFilter(SMALL_BOUND, FIXED).maxBodyBytes())
                .isEqualTo(SMALL_BOUND);
    }

    /**
     * Confirms a bound that would refuse every body fails at assembly rather than at the first request.
     *
     * <p>Assumptions: both zero and a negative are asserted, because they fail for different reasons --
     * one refuses every body and the other has no meaning -- and a guard written for only one of them
     * would let the other through.</p>
     */
    @Test
    @DisplayName("a non-positive bound is refused at construction, naming the value")
    void aNonPositiveBoundIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new RequestBodySizeFilter(0L, FIXED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBodyBytes")
                .hasMessageContaining("0");
        assertThatThrownBy(() -> new RequestBodySizeFilter(-1L, FIXED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    /**
     * Confirms an absent clock fails at assembly.
     */
    @Test
    @DisplayName("an absent clock is refused at construction")
    void anAbsentClockIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new RequestBodySizeFilter(SMALL_BOUND, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
    }

    /**
     * Confirms a body inside the bound reaches the rest of the chain untouched.
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("a body within the bound reaches the chain with no refusal")
    void aBodyWithinTheBoundReachesTheChain() throws IOException, ServletException {
        MockHttpServletRequest request = jsonRequest("{\"a\":1}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED).doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("the chain was entered").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /**
     * Confirms a body of exactly the bound posts, which fixes the boundary as inclusive.
     *
     * <p>Assumptions: asserted explicitly because an inclusive boundary is the discipline the migration
     * follows wherever the reference baseline states one -- the over-limit comparison at lines 403 to
     * 413 of {@code app/cbl/CBTRN02C.cbl} posts a balance exactly at the credit limit -- and a bound
     * that refused a body of exactly its own size would refuse a request the message naming that size
     * says is acceptable.</p>
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("a body of exactly the bound is accepted, one byte more is refused")
    void theBoundaryIsInclusive() throws IOException, ServletException {
        MockHttpServletRequest atBound = jsonRequest("x".repeat((int) SMALL_BOUND));
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                .doFilter(atBound, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletRequest overBound = jsonRequest("x".repeat((int) SMALL_BOUND + 1));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                .doFilter(overBound, refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(ApiError.PAYLOAD_TOO_LARGE_STATUS);
    }

    /**
     * Confirms an over-large declared length is refused without the body being opened at all.
     *
     * <p>Assumptions: this is the assertion the filter exists for. The recording request reports whether
     * its stream was ever asked for, so the case fails for an implementation that answers 413 after
     * reading the bytes -- which would carry the correct status and none of the benefit.</p>
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("an over-large declared length is refused before the body is opened")
    void anOverLargeDeclaredLengthIsRefusedWithoutOpeningTheBody()
            throws IOException, ServletException {
        RecordingRequest request = new RecordingRequest("x".repeat((int) SMALL_BOUND + 8), true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(ApiError.PAYLOAD_TOO_LARGE_STATUS);
        assertThat(request.streamOpened).as("the body must not be opened").isFalse();
        assertThat(chain.getRequest()).as("the chain must not be entered").isNull();
    }

    /**
     * Confirms the refusal carries the contracted code, status and correlation identity.
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("the refusal carries the payload-too-large code and the request's correlation identity")
    void theRefusalCarriesTheContractedCodeAndIdentity() throws IOException, ServletException {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-body-size-1");
        try {
            MockHttpServletRequest request = jsonRequest("x".repeat((int) SMALL_BOUND + 1));
            request.setRequestURI("/api/v1/auth/refresh");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                    .doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentType()).startsWith("application/json");
            String body = response.getContentAsString();
            assertThat(body)
                    .contains(ApiError.CODE_PAYLOAD_TOO_LARGE)
                    .contains("corr-body-size-1")
                    .contains("/api/v1/auth/refresh")
                    .contains(String.valueOf(SMALL_BOUND));
        } finally {
            MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Confirms the refusal reports no fragment of the body it refused.
     *
     * <p>Assumptions: the body carries a card-shaped value, because that is the class of content whose
     * appearance in a response or a log record would be the real cost of an echoing refusal. The
     * assertion is an absence, which is the only way an absence stays absent.</p>
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("the refusal echoes no byte of the body it refused")
    void theRefusalWithholdsTheBody() throws IOException, ServletException {
        MockHttpServletRequest request =
                jsonRequest("{\"cardNumber\":\"" + PAN_SHAPED_VALUE + "\",\"pad\":\"aaaaaaaaaa\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString())
                .doesNotContain(PAN_SHAPED_VALUE)
                .doesNotContain("cardNumber");
    }

    /**
     * Confirms an undeclared length within the bound is counted and replayed to the chain.
     *
     * <p>Assumptions: the replayed request is asserted to report the COUNTED length, because a converter
     * that sizes its own buffer from the reported length would otherwise size it for an absent body. The
     * bytes are asserted to be identical, because a bound that corrupted the body it admitted would
     * trade one failure for a worse one.</p>
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("an undeclared length within the bound is replayed to the chain in full")
    void anUndeclaredLengthWithinTheBoundIsReplayed() throws IOException, ServletException {
        RecordingRequest request = new RecordingRequest("{\"a\":1}", false);
        MockFilterChain chain = new MockFilterChain();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.streamOpened).as("an undeclared length must be counted").isTrue();
        HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
        assertThat(downstream).isNotSameAs(request);
        assertThat(downstream.getContentLengthLong()).isEqualTo("{\"a\":1}".length());
        assertThat(new String(downstream.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":1}");
        assertThat(downstream.getReader().readLine()).isEqualTo("{\"a\":1}");
    }

    /**
     * Confirms an undeclared length over the bound is refused and the chain is never entered.
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("an undeclared length over the bound is refused")
    void anUndeclaredLengthOverTheBoundIsRefused() throws IOException, ServletException {
        RecordingRequest request = new RecordingRequest("x".repeat((int) SMALL_BOUND + 1), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(ApiError.PAYLOAD_TOO_LARGE_STATUS);
        assertThat(response.getContentAsString()).contains("declares no length");
        assertThat(chain.getRequest()).as("the chain must not be entered").isNull();
    }

    /**
     * Confirms a request with no media type is passed on as the very same instance.
     *
     * <p>Assumptions: identity is asserted rather than equality, because the point is that no wrapper
     * was allocated. Every read this system serves is in this category, so wrapping them would allocate
     * a reader per request for a body that does not exist.</p>
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("a request with no media type is passed on unwrapped")
    void aRequestWithNoMediaTypeIsPassedOnUnwrapped() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/1");
        MockFilterChain chain = new MockFilterChain();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    /**
     * Confirms a request that is not an HTTP request is passed on rather than refused.
     *
     * <p>Assumptions: built by wrapping an HTTP request in the servlet contract's own non-HTTP wrapper,
     * which yields a {@code ServletRequest} that is genuinely not an {@code HttpServletRequest} without
     * this test having to implement thirty delegating methods to obtain one.</p>
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("a non-HTTP request is passed on rather than refused")
    void aNonHttpRequestIsPassedOn() throws IOException, ServletException {
        ServletRequestWrapper nonHttp =
                new ServletRequestWrapper(jsonRequest("x".repeat((int) SMALL_BOUND + 1)));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED).doFilter(nonHttp, response, chain);

        assertThat(chain.getRequest()).isSameAs(nonHttp);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Confirms a replayed body refuses an asynchronous read listener rather than never calling it.
     *
     * @throws IOException if the filter cannot run
     * @throws ServletException if the chain cannot run
     */
    @Test
    @DisplayName("a replayed body refuses a read listener rather than silently ignoring it")
    void aReplayedBodyRefusesAReadListener() throws IOException, ServletException {
        RecordingRequest request = new RecordingRequest("{\"a\":1}", false);
        MockFilterChain chain = new MockFilterChain();

        new RequestBodySizeFilter(SMALL_BOUND, FIXED)
                .doFilter(request, new MockHttpServletResponse(), chain);

        ServletInputStream replayed =
                ((HttpServletRequest) chain.getRequest()).getInputStream();
        assertThat(replayed.isReady()).isTrue();
        assertThat(replayed.isFinished()).isFalse();
        assertThatThrownBy(() -> replayed.setReadListener(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("arrived in full");
    }

    /**
     * Builds a JSON request whose declared length is the length of the supplied body.
     *
     * @param body the body the request carries; must not be {@code null}
     * @return a POST request declaring {@code application/json} and that body, never {@code null}
     */
    private static MockHttpServletRequest jsonRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/probe");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /**
     * A request that reports no declared length on demand and records whether its body was opened.
     *
     * <p>Assumptions: the mock request in the framework's test support derives its content length from
     * the content it holds, so it cannot express the one case that matters here -- a body present with
     * no length declared, which is what a chunked request is. Overriding the two length accessors is the
     * whole of the difference, and recording the stream access is what lets the over-large cases assert
     * that the body was never read.</p>
     */
    private static final class RecordingRequest extends MockHttpServletRequest {

        /** Whether {@link #getInputStream()} has been called. */
        private boolean streamOpened;

        /** Whether the request reports its content length or reports none. */
        private final boolean declaresLength;

        /**
         * Builds a JSON request around the supplied body.
         *
         * @param body the body the request carries; must not be {@code null}
         * @param declaresLength {@code true} to report the body's length, {@code false} to report none
         */
        private RecordingRequest(String body, boolean declaresLength) {
            super("POST", "/api/v1/probe");
            this.declaresLength = declaresLength;
            setContentType("application/json");
            setContent(body.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Reports the body's length, or none when this request was built to declare none.
         *
         * @return the declared length, or {@code -1}
         */
        @Override
        public long getContentLengthLong() {
            return this.declaresLength ? super.getContentLengthLong() : -1L;
        }

        /**
         * Reports the body's length, or none when this request was built to declare none.
         *
         * @return the declared length, or {@code -1}
         */
        @Override
        public int getContentLength() {
            return this.declaresLength ? super.getContentLength() : -1;
        }

        /**
         * Opens the body, recording that it was opened.
         *
         * @return the body stream, never {@code null}
         */
        @Override
        public ServletInputStream getInputStream() {
            this.streamOpened = true;
            return super.getInputStream();
        }
    }
}
