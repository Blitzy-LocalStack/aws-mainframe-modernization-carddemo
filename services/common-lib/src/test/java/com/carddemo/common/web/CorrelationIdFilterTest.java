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
     * The same synthetic value written with the three separators this header admits.
     *
     * <p>Assumptions: it is named beside the contiguous form because the two are one value under the
     * digit-counting rule, and an assertion that admitted one while refusing the other would be
     * asserting the defect rather than the rule.</p>
     */
    private static final String SEPARATED_PAN_SHAPED_ID = "4111-1111-1111-11";

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
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();

        // WHY : Refactoring Rationale: this assertion was inverted. It formerly required the response
        //       to carry NO correlation header on a refusal, which is what the container-rendered
        //       refusal produced and which left the caller with a 400 it could not correlate to
        //       anything in the platform's logs. The refusal now mints a FRESH identity, publishes it,
        //       and reports it inside the problem body, so the exchange is traceable without the
        //       refused value ever being reflected.
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo(PAN_SHAPED_ID);
    }

    /**
     * Confirms the refusal is rendered as the platform's own error record rather than left to the
     * container, and that the record names the shape rule without reproducing the refused value.
     *
     * <p>Refactoring Rationale: this test formerly read {@code response.getErrorMessage()}, which is
     * populated only by {@code sendError} -- the very mechanism the correction replaced, because it
     * hands the response to the container's error page and produces a body whose shape depends on the
     * container rather than on the published contract. The assertion now reads the rendered body, which
     * is the artifact a client actually receives.</p>
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("the refusal renders the shared error record and never repeats the value")
    void refusalNamesTheRuleWithoutTheValue() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, PAN_SHAPED_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        String body = response.getContentAsString();

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(body)
                .contains("or more digits once separators are removed")
                .contains(CorrelationIdFilter.CORRELATION_ID_HEADER)
                .contains("\"status\":400")
                .doesNotContain(PAN_SHAPED_ID);
        assertThat(body)
                .contains(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    /**
     * Confirms the generated identity opens with a non-numeric prefix, so a minted value can never be
     * mistaken for -- or refused as -- a card-shaped one.
     *
     * <p>Assumptions: the refusal rule counts digits once separators are removed, and hexadecimal
     * rendering of eleven random bytes can produce twenty-two characters that are all digits. Without a
     * literal prefix the platform could therefore mint an identity its own rule would refuse on the
     * next hop. Asserting the prefix pins the guarantee rather than leaving it to chance, which a
     * probabilistic assertion over the digits could not do.</p>
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a minted identity carries a non-numeric prefix and the contracted width")
    void mintedIdentityCarriesNonNumericPrefix() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .hasSize(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH)
                .containsPattern("^[A-Za-z]");
    }

    /**
     * Confirms an eight-digit all-numeric identity still conforms, so the rule refuses the shape of a
     * protected identifier rather than every numeric value a caller might legitimately choose.
     *
     * <p>⚠️ Refactoring Rationale: this case asserted TWELVE digits, and twelve is now refused. It was
     * chosen to sit one below a thirteen-digit floor, and that floor was the defect: it was derived from
     * the card number alone while the rule it governs protects four identifiers, the shortest of which --
     * a customer identifier and a national identifier, both {@code PIC 9(09)} -- is nine digits. Eight is
     * the new one-below value, and it is asserted rather than the boundary itself so that this case and
     * {@link #protectedIdentifierShapedIdentitiesAreRefused()} together bracket the threshold from both
     * sides.</p>
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a short all-numeric identity is still echoed unaltered")
    void shortNumericIdentityIsEchoed() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.invoked).isTrue();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("12345678");
    }

    /**
     * Confirms every protected identifier shape this system holds is refused, not merely the card number.
     *
     * <p>Assumptions: three widths are asserted and each is a DIFFERENT identifier, because one rule
     * covering all three is the whole of the correction and a case naming only one width would pass
     * against a rule that had been widened by four digits instead of to the measured floor. Nine is a
     * customer identifier and a national identifier ({@code CUST-ID PIC 9(09)} at line 5 of
     * {@code app/cpy/CVCUS01Y.cpy}, {@code CUST-SSN PIC 9(09)} at line 16); eleven is an account
     * identifier ({@code ACCT-ID PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy}); and the
     * separated eleven-digit form is asserted as well, because the rule counts digits after removing
     * separators and a caller writing an account identifier with a hyphen must not slip past it.</p>
     *
     * <p>Assumptions: each refusal asserts BOTH the status and that the supplied value is absent from the
     * response, because a refusal that echoed the value in its own body or header would have published
     * exactly what it was refusing. The minted identity in the body is twenty-two uppercase hexadecimal
     * characters, whose alphabet includes every digit, so the assertion is made against the WHOLE
     * supplied value rather than any substring of it -- a short window would collide by chance.</p>
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("nine-digit and eleven-digit identifier shapes are refused, separated or not")
    void protectedIdentifierShapedIdentitiesAreRefused() throws IOException, ServletException {
        assertRefused("123456789");
        assertRefused("00000000011");
        assertRefused("000-0000-0011");
    }

    /**
     * Drives one request through a fresh filter and asserts the supplied identity was refused outright.
     *
     * <p>Assumptions: the chain is asserted NOT to have run, because the refusal must happen before the
     * request reaches anything that logs -- a chain that ran would have published the value to the mapped
     * diagnostic context, which is the exposure being closed rather than an implementation detail.</p>
     *
     * @param identity the correlation identity to supply, which must be refused
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    private static void assertRefused(String identity) throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, identity);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.invoked).isFalse();
        assertThat(response.getContentAsString()).doesNotContain(identity);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isNotEqualTo(identity);
    }

    /**
     * Confirms a separator-bearing identity carrying a card number's worth of digits is refused too.
     *
     * <p>Refactoring Rationale: this test formerly asserted that {@code 4111-1111-1111-11} was ECHOED,
     * and it was the exact exposure the correction closes rather than an incidental case. The three
     * separators this header admits are the three a card number is conventionally written with, so a
     * rule that counted characters instead of digits refused the contiguous form and published the
     * separated form of the same value into every log line of the request. The rule now removes
     * separators before counting, so both forms are refused and the echo contract is unchanged for
     * everything that is not a run of digits.</p>
     *
     * <p>Assumptions: non-reflection is asserted against the WHOLE supplied value and its
     * separator-stripped form, never against a short substring of it. The refusal body carries a
     * freshly minted identity of twenty-two uppercase hexadecimal characters, whose alphabet includes
     * every decimal digit, so any four-digit window of the supplied value occurs in it by chance on
     * roughly one run in three thousand five hundred -- a flake that reports as a masking failure and
     * sends a reader to the filter rather than to the assertion. Neither form asserted here can appear
     * in a minted identity: one carries hyphens, and the other is a sixteen-digit run.</p>
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a separator-bearing identity of card-number digit count is refused")
    void separatedCardShapedIdentityIsRefused() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, SEPARATED_PAN_SHAPED_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.invoked).isFalse();
        assertThat(response.getContentAsString())
                .doesNotContain(SEPARATED_PAN_SHAPED_ID)
                .doesNotContain(PAN_SHAPED_ID);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isNotEqualTo(SEPARATED_PAN_SHAPED_ID);
    }

    /**
     * Confirms the echo contract survives the widened rule for the identities real callers send.
     *
     * <p>Assumptions: the rule refuses only values built ENTIRELY from digits and accepted separators
     * whose digits reach the protected-identifier threshold, so the two shapes asserted here are the ones
     * that prove it did not become a blanket refusal: a separated value whose digits stay under the
     * threshold, and a value of full width that carries a letter. The second is the common case -- a
     * trace identifier is hexadecimal -- and admitting it on the first non-digit character is why the
     * rule costs nothing on the conforming path.</p>
     *
     * <p>⚠️ Refactoring Rationale: the separated value asserted here was {@code 2022-07-18-0930}, twelve
     * digits, which the widened rule refuses. It is now {@code 2022-07-18}, eight digits, which is the
     * same kind of value -- a separated date a caller might legitimately correlate on -- kept below the
     * new floor. The letter-bearing case is unchanged and is the more important of the two: it is what
     * every platform-minted identity and every hexadecimal trace identifier looks like, so it is the
     * evidence that the widening costs no internal hop.</p>
     *
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    @Test
    @DisplayName("separated short identities and letter-bearing identities are still echoed unaltered")
    void conformingSeparatedAndAlphanumericIdentitiesAreEchoed() throws IOException, ServletException {
        assertEchoed("2022-07-18");
        assertEchoed("a1b2c3d4e5f6a7b8c9d0e1f2");
    }

    /**
     * Drives one request through a fresh filter and asserts the supplied identity was echoed unaltered.
     *
     * <p>Assumptions: extracting this keeps each echo case to one line, so a reader compares the VALUES
     * being admitted rather than re-reading four identical assertions. The filter is constructed per
     * call because it is stateless and a shared instance would say nothing about isolation.</p>
     *
     * @param identity the correlation identity to supply on the request, which must conform
     * @throws IOException if the mock chain reports one, which it does not
     * @throws ServletException if the mock chain reports one, which it does not
     */
    private static void assertEchoed(String identity) throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/11");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, identity);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.invoked).isTrue();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(identity);
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
     * Confirms a SECOND pass of this filter over one request is a no-op, which is what makes a duplicate
     * registration invisible rather than harmful.
     *
     * <p>Refactoring Rationale: this expectation pins a claim two service configurations make in prose.
     * One of them previously made the OPPOSITE claim -- that a duplicate registration minted two
     * identities for one request and that the inner pass's cleanup stripped the identity from every line
     * the outer pass then logged. That is not what happens, and a prose correction alone would leave the
     * next reader with two contradicting paragraphs and no way to settle them. This asserts the
     * behaviour, so the paragraphs can cite something.</p>
     *
     * <p>Assumptions: both passes are driven over the SAME request object, because the guard is a request
     * attribute -- that is precisely the shape a container produces for a forward, an include, an async
     * resume, an error dispatch, or a filter registered twice. The identity captured by the inner chain is
     * compared with the outer one, and the response header is asserted to carry exactly one value, which
     * together rule out both a second mint and a lost identity.</p>
     *
     * @throws IOException if a mock chain reports one, which it does not
     * @throws ServletException if a mock chain reports one, which it does not
     */
    @Test
    @DisplayName("a second pass over one request mints nothing and disturbs neither context nor response")
    void secondPassOverOneRequestIsANoOp() throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cards");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CorrelationIdFilter filter = new CorrelationIdFilter();

        ContextCapturingChain inner = new ContextCapturingChain();
        ContextCapturingChain outer = new ContextCapturingChain();

        // WHY : Assumptions: the outer pass is driven with a chain that itself invokes the filter a
        //       second time, which is the arrangement two registrations of one filter produce. Calling
        //       the filter twice in sequence instead would not reproduce it: the outer pass removes its
        //       guard attribute on the way out, so the second call would legitimately be a fresh request
        //       as far as the filter can tell.
        FilterChain nested = (nestedRequest, nestedResponse) -> {
            outer.doFilter(nestedRequest, nestedResponse);
            filter.doFilter(nestedRequest, nestedResponse, inner);
        };

        filter.doFilter(request, response, nested);

        assertThat(outer.correlationId)
                .as("the outer pass must establish an identity")
                .isNotBlank();
        assertThat(inner.correlationId)
                .as("the inner pass must observe the SAME identity, not a second minted one")
                .isEqualTo(outer.correlationId);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY))
                .as("the inner pass must not clear the context the outer pass still owns; the outer pass "
                        + "clears it on the way out, which is why this reads null AFTER both returned")
                .isNull();
        assertThat(response.getHeaders(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .as("one request must carry exactly one correlation header, however often the filter runs")
                .hasSize(1);
        assertThat(response.getStatus()).isEqualTo(200);
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
