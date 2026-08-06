package com.carddemo.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies the two properties of the correlation filter that decide whether a caller-supplied header
 * can put data into log storage: which values are echoed, and which are refused.
 *
 * <p>Assumptions: the filter's own contract is that a conforming identity is echoed exactly, an absent
 * one is minted, and one that cannot be carried is refused with a client error. These tests assert the
 * third case for the class that matters most -- a bare run of digits shaped like a primary account
 * number -- and assert the first case around it, because a rule that refused a card number by refusing
 * everything numeric would break the echo contract for legitimate callers.</p>
 *
 * <p>Assumptions: every card-shaped value used below is a synthetic test value identifying no account,
 * and none is reproduced into any assertion message.</p>
 */
class CorrelationIdFilterTest {

    /** A synthetic sixteen-digit value: token-safe, inside the width bound, and PAN-shaped. */
    private static final String PAN_SHAPED_ID = "4111111111111111";

    /**
     * Confirms a sixteen-digit correlation identity is refused rather than published, which is the
     * exposure this test exists to close: a conforming value reaches the logging context and therefore
     * every log line of the request.
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a card-shaped correlation identity is refused with a client error")
    void panShapedIdentityIsRefused() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, PAN_SHAPED_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.invoked).isFalse();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    /**
     * Confirms the refusal explains itself in terms of the shape and never by repeating the value,
     * because a diagnostic that echoed the refused number would reintroduce the exposure into the
     * container's own error handling.
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("the refusal names the numeric-shape rule without repeating the value")
    void refusalNamesTheRuleWithoutTheValue() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, PAN_SHAPED_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getErrorMessage()).contains("must not be a bare run of");
        assertThat(response.getErrorMessage()).doesNotContain(PAN_SHAPED_ID);
    }

    /**
     * Confirms a twelve-digit all-numeric identity still conforms, so the rule refuses the card-shaped
     * class rather than every numeric value a caller might legitimately choose.
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a short all-numeric identity is still echoed unaltered")
    void shortNumericIdentityIsEchoed() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "123456789012");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.invoked).isTrue();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("123456789012");
    }

    /**
     * Confirms a sixteen-character identity that carries a separator conforms, which is the shape a
     * real caller sends and is the reason the rule tests the value as a whole rather than its length.
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a hyphenated identity of card-number length is still echoed unaltered")
    void hyphenatedIdentityOfCardLengthIsEchoed() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "4111-1111-1111-11");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.invoked).isTrue();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("4111-1111-1111-11");
    }

    /**
     * Confirms the same rule governs the edge identifier, which is dropped rather than refused because
     * the caller did not set it -- so a platform header cannot smuggle a number into the log field
     * either.
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a card-shaped edge request identifier is dropped, not published")
    void panShapedRequestIdentifierIsDropped() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, PAN_SHAPED_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.requestId).isNull();
        assertThat(chain.correlationId)
                .hasSize(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH)
                .isNotEqualTo(PAN_SHAPED_ID);
    }

    /**
     * A chain that records whether it was reached, so a refusal can be told from a pass-through.
     */
    private static final class RecordingChain implements FilterChain {

        /** True once the chain has been invoked, which a refused request must never do. */
        private boolean invoked;

        /**
         * Records the invocation and does nothing else.
         *
         * @param request the request being passed on, unused
         * @param response the response being passed on, unused
         */
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response) {
            this.invoked = true;
        }
    }

    /**
     * A chain that reads the logging context while it is in force, since the filter clears it on the
     * way out and an assertion made afterwards would read nothing on every path.
     */
    private static final class ContextCapturingChain implements FilterChain {

        /** The correlation identity in force when the chain ran, or {@code null} if none was set. */
        private String correlationId;

        /** The edge identity in force when the chain ran, or {@code null} if none was published. */
        private String requestId;

        /**
         * Captures both logging-context keys at the moment the chain is reached.
         *
         * @param request the request being passed on, unused
         * @param response the response being passed on, unused
         */
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response) {
            this.correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            this.requestId = MDC.get(CorrelationIdFilter.REQUEST_ID_MDC_KEY);
        }
    }
}
