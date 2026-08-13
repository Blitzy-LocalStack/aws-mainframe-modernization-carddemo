package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.web.CorrelationIdFilter;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.firewall.RequestRejectedException;

/**
 * Verifies that a request refused by the filter chain -- rather than by a handler -- is answered with the
 * same error record every other refusal in this stack carries.
 *
 * <p>Assumptions: the two refusals are asserted separately because they are reached by two different
 * mechanisms. An unauthenticated request never reaches the dispatcher at all, so a controller advice
 * cannot see it; an authenticated request denied by the chain does not either. Before these handlers both
 * produced whatever the container renders for a bare status, which is a body shape the published
 * contracts do not describe.</p>
 *
 * <p>Assumptions: the path used throughout is the resolved form of the published card route, so the
 * masking obligation is asserted on the value that actually appears rather than on a placeholder.</p>
 */
class ApiErrorSecurityHandlersTest {

    /** A synthetic sixteen-digit card number, the width the published path parameter admits. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The resolved card route carrying that number, exactly as a container would report it. */
    private static final String CARD_PATH = "/api/v1/cards/" + CARD_NUMBER;

    /** The rendering the published contract gives as its example: twelve masks, four retained. */
    private static final String MASKED_CARD_PATH = "/api/v1/cards/************1111";

    /** A correlation identity the filter would already have established before the security chain. */
    private static final String CORRELATION_ID = "CD0123456789ABCDEF012345";

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /**
     * Clears the logging context after every test, so an identity set by one cannot be read by another.
     */
    @AfterEach
    void clearContext() {
        MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
    }

    /**
     * Confirms an unauthenticated request is answered with 401, the bearer challenge and the record.
     *
     * <p>Assumptions: the challenge header is asserted because preserving it is the reason this handler
     * DELEGATES rather than replacing the framework's entry point. A client's refresh logic reads that
     * header, so a handler that answered 401 with a body but no challenge would be a regression against
     * the OAuth 2.0 contract even while fixing the body.</p>
     *
     * @throws IOException if writing the refusal fails, which it does not
     * @throws jakarta.servlet.ServletException if the delegate reports one, which it does not
     */
    @Test
    @DisplayName("an unauthenticated request is answered with 401, the challenge and the error record")
    void unauthenticatedRequestCarriesChallengeAndRecord()
            throws IOException, jakarta.servlet.ServletException {

        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
        MockHttpServletRequest request = requestFor(CARD_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiErrorSecurityHandlers.UnauthenticatedEntryPoint(CLOCK).commence(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isNotBlank();
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED)
                .contains(ApiErrorSecurityHandlers.MESSAGE_UNAUTHENTICATED)
                .contains(CORRELATION_ID)
                .contains(MASKED_CARD_PATH)
                .doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms a denied request is answered with 403 and the same sentence a method-security denial gives.
     *
     * <p>Assumptions: the code and the sentence are asserted against the advice's own constants rather
     * than against literals, because the property under test is that the two denial routes are
     * indistinguishable from outside. Asserting literals would let the two drift apart while both tests
     * still passed.</p>
     *
     * @throws IOException if writing the refusal fails, which it does not
     */
    @Test
    @DisplayName("a denied request is answered with 403 and the shared forbidden record")
    void deniedRequestCarriesTheSharedForbiddenRecord() throws IOException {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
        MockHttpServletRequest request = requestFor(CARD_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiErrorSecurityHandlers.ApiErrorAccessDeniedHandler(CLOCK).handle(request, response,
                new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains(GlobalExceptionHandler.CODE_FORBIDDEN)
                .contains(GlobalExceptionHandler.MESSAGE_FORBIDDEN)
                .contains(MASKED_CARD_PATH)
                .doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms neither handler renders the failure it was given, only its class.
     *
     * <p>Assumptions: an authentication failure's message is composed by the resource server from the
     * token it rejected, and a denial's message is composed by whatever raised it, so both can quote
     * content the caller supplied. The body must therefore carry the fixed sentence and never the
     * failure's own text, which is asserted directly because the omission is the security property.</p>
     *
     * @throws IOException if writing the refusal fails, which it does not
     * @throws jakarta.servlet.ServletException if the delegate reports one, which it does not
     */
    @Test
    @DisplayName("neither refusal renders the failure's own message")
    void refusalsNeverRenderTheFailureMessage()
            throws IOException, jakarta.servlet.ServletException {

        MockHttpServletResponse unauthenticated = new MockHttpServletResponse();
        new ApiErrorSecurityHandlers.UnauthenticatedEntryPoint(CLOCK).commence(requestFor(CARD_PATH),
                unauthenticated,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token",
                        "the sub claim held " + CARD_NUMBER, null)));

        MockHttpServletResponse denied = new MockHttpServletResponse();
        new ApiErrorSecurityHandlers.ApiErrorAccessDeniedHandler(CLOCK).handle(requestFor(CARD_PATH),
                denied, new AccessDeniedException("group check failed for " + CARD_NUMBER));

        assertThat(unauthenticated.getContentAsString())
                .doesNotContain(CARD_NUMBER)
                .doesNotContain("sub claim");
        assertThat(denied.getContentAsString())
                .doesNotContain(CARD_NUMBER)
                .doesNotContain("group check");
    }

    /**
     * Confirms an absent correlation identity renders as absent rather than as an invented value.
     *
     * <p>Assumptions: the identity is read from the logging context, and the context is empty when the
     * security chain refuses a request the correlation filter never reached -- which happens when the
     * chain is ordered ahead of it. The absence renders as the empty string rather than as a null,
     * because {@link ApiError} normalises an absent identity to empty at line 460 so that every response
     * carries the component with a string value; the property asserted here is that no substitute
     * identity is invented, not which of the two absences is rendered.</p>
     *
     * @throws IOException if writing the refusal fails, which it does not
     */
    @Test
    @DisplayName("an absent correlation identity is reported as absent, not invented")
    void absentCorrelationIdentityIsReportedAsAbsent() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiErrorSecurityHandlers.ApiErrorAccessDeniedHandler(CLOCK).handle(requestFor(CARD_PATH),
                response, new AccessDeniedException("denied"));

        assertThat(response.getContentAsString()).contains("\"correlationId\":\"\"");
    }

    /**
     * Confirms a response another layer already committed is left exactly as it is.
     *
     * <p>Assumptions: writing a body into a committed response throws in some containers and is silently
     * discarded in others, so the handler checks rather than depending on which. The status set before
     * committing is asserted to survive, because the failure mode being guarded against is a second write
     * that replaces or corrupts a refusal already sent.</p>
     *
     * @throws IOException if writing the refusal fails, which it does not
     */
    @Test
    @DisplayName("a response already committed is not written to a second time")
    void committedResponseIsLeftAlone() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.getWriter().write("already sent");
        response.flushBuffer();

        new ApiErrorSecurityHandlers.ApiErrorAccessDeniedHandler(CLOCK).handle(requestFor(CARD_PATH),
                response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }

    /**
     * Confirms a request the HTTP firewall refuses is answered 400 with the shared envelope and a
     * POPULATED correlation identifier.
     *
     * <p>⚠️ Assumptions: this handler exists because the condition was answered 401 with an EMPTY body
     * correlation identifier, to a caller holding a valid token. The framework's default handler calls
     * {@code sendError}, the container performs an internal ERROR dispatch, the bearer-token filter does
     * not run on that dispatch — it is a once-per-request filter and those skip error dispatches — so the
     * request arrives anonymous and the deny-by-default rule refuses it. Answering here means no dispatch
     * happens, so nothing can overwrite the status.</p>
     *
     * <p>Assumptions: the populated identifier is asserted specifically, because that is the half of the
     * finding a status assertion would not catch. The identifier is available on this path only because
     * the correlation filter is ordered ahead of the security chain, so its logging context is still
     * established when the firewall rejects; on the error dispatch it is not replayed at all, which is why
     * the body carried an empty value before.</p>
     *
     * @throws IOException if writing the refusal fails, which it does not
     */
    @Test
    @DisplayName("a firewall rejection is answered 400 with the shared envelope and a real correlation id")
    void firewallRejectionCarriesTheSharedEnvelope() throws IOException {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler(CLOCK).handle(requestFor(CARD_PATH),
                response, new RequestRejectedException(
                        "The request was rejected because the URL contained \"//\" " + CARD_NUMBER));

        assertThat(response.getStatus())
                .as("a request line the firewall cannot route is a malformed request, not an "
                        + "unauthenticated one")
                .isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains(ApiError.CODE_VALIDATION)
                .contains(GlobalExceptionHandler.MESSAGE_MALFORMED_REQUEST)
                .contains(CORRELATION_ID)
                .contains(MASKED_CARD_PATH);
        assertThat(response.getContentAsString())
                .as("the framework composes the rejection message from the offending URL, and a path "
                        + "segment on this system can carry a primary account number")
                .doesNotContain(CARD_NUMBER)
                .doesNotContain("\"correlationId\":\"\"");
    }

    /**
     * The firewall's log line and its problem body both withhold a nine-digit customer identifier.
     *
     * <p>⚠️ Purpose: this is the exposure the single-sourced sanitizer closes, at the narrowest width. This
     * handler used to narrow the rejected path with {@code CardNumberMasker.maskEmbeddedCardNumbers},
     * which recognises a contiguous run of SIXTEEN digits and nothing shorter, while the shared advice
     * narrowed the same path at nine. A rejected request line carrying the nine-digit customer identifier
     * therefore reached a retained log line AND a problem body in the clear -- and a rejected line is
     * exactly the case a caller controls, because the firewall rejects on the URL's own shape.</p>
     *
     * <p>Assumptions: BOTH surfaces are asserted in one case, because they were narrowed by two separate
     * expressions -- the log statement's and {@code writeProblem}'s -- and a correction reaching only one
     * would leave the other leaking while a single-surface case passed. The whole run is asserted absent
     * rather than partially masked: the platform publishes no partial rendering of a customer identifier
     * anywhere, so retaining any of it would have nothing to justify it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if writing the mock response fails, which fails the case rather than being
     *     handled
     */
    @Test
    @DisplayName("a nine-digit customer identifier is withheld from the firewall log and the body")
    void aCustomerIdentifierIsWithheldFromBothFirewallSurfaces() throws IOException {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler(CLOCK)
                .handle(requestFor("/api/v1/customers/900000001"), response,
                        new RequestRejectedException("rejected"));

        assertThat(response.getContentAsString())
                .as("no partial rendering of a customer identifier is published anywhere, so the run "
                        + "is withheld whole")
                .doesNotContain("900000001")
                .contains("/api/v1/customers/*********");
    }

    /**
     * The firewall surfaces withhold an eleven-digit account identifier whole and a card number's head.
     *
     * <p>⚠️ Purpose: three widths matter and each fails differently, so each is asserted. Nine and eleven
     * are the customer and account identifiers, for which nothing partial is published, so the run is
     * withheld ENTIRELY -- and both were in the clear here before the sanitizer was single-sourced.
     * Sixteen is a card number, for which the platform already publishes a last-four rendering in list
     * rows, in detail bodies and in the card contract, so retaining four discloses nothing a successful
     * response would not. Asserting only the identifiers would let a correction that withheld a card
     * number whole pass, which would break every diagnostic that lines a path up against a card row.</p>
     *
     * <p>Assumptions: the two identifiers are asserted with their exact masked renderings rather than
     * merely as absences, so a correction that dropped the run instead of overwriting it in place would
     * fail. Length preservation is what lets a reader line a diagnostic path up against an access record
     * without re-parsing either.</p>
     *
     * <p>Assumptions: a short run is asserted to stay LEGIBLE in the same case, because a sanitizer that
     * masked every digit would satisfy every assertion above while making page sizes, ordinals and date
     * components unreadable -- and the threshold exists precisely so they stay readable.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if writing one of the mock responses fails, which fails the case rather than
     *     being handled
     */
    @Test
    @DisplayName("account, card and short-run paths are each narrowed by their own rule")
    void eachIdentifierWidthIsNarrowedByItsOwnRule() throws IOException {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);

        MockHttpServletResponse account = new MockHttpServletResponse();
        new ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler(CLOCK)
                .handle(requestFor("/api/v1/accounts/00000000011"), account,
                        new RequestRejectedException("rejected"));
        assertThat(account.getContentAsString())
                .doesNotContain("00000000011")
                .contains("/api/v1/accounts/***********");

        MockHttpServletResponse card = new MockHttpServletResponse();
        new ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler(CLOCK)
                .handle(requestFor("/api/v1/cards/4111111111111111"), card,
                        new RequestRejectedException("rejected"));
        assertThat(card.getContentAsString())
                .as("a card number keeps the last-four rendering the platform already publishes")
                .doesNotContain("4111111111111111")
                .contains("/api/v1/cards/************1111");

        MockHttpServletResponse legible = new MockHttpServletResponse();
        new ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler(CLOCK)
                .handle(requestFor("/api/v1/transactions?page=12345678"), legible,
                        new RequestRejectedException("rejected"));
        assertThat(legible.getContentAsString())
                .as("eight digits is below every protected width, so an ordinal stays readable")
                .contains("12345678");
    }

    /**
     * Confirms the factory publishes the same handler the nested class implements.
     *
     * <p>Assumptions: the factory is asserted because it is what the shared auto-configuration publishes
     * as a bean, and a factory returning a different implementation from the one the case above exercises
     * would leave the tested behaviour unreachable in a running service.</p>
     */
    @Test
    @DisplayName("the published factory returns the rendering firewall handler")
    void theFactoryReturnsTheRenderingFirewallHandler() {
        assertThat(ApiErrorSecurityHandlers.requestRejectedHandler(CLOCK))
                .isInstanceOf(ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler.class);
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
}
