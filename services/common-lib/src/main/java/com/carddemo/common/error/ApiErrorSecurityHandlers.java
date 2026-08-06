package com.carddemo.common.error;

import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the two security refusals the filter chain answers itself as the shared {@link ApiError} body.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Refactoring Rationale: every published contract in this migration declares HTTP 401 and HTTP 403 as
 * carrying the problem shape, and none of the eight services produced one. A refusal decided by the
 * security filter chain never reaches a controller, so it never reaches
 * {@link GlobalExceptionHandler}: the framework's default entry point writes the status and the
 * {@code WWW-Authenticate} challenge and NO BODY, and its default denial handler writes the status and no
 * body either. A client written against the contract therefore received an empty body on exactly the two
 * statuses it is most likely to branch on, and had no correlation identity in the body to report. These
 * two handlers close that gap by rendering the same record the advice renders, at the one point in the
 * chain where the decision is made.</p>
 *
 * <p>Assumptions: the challenge header is PRESERVED rather than replaced. The bearer-token entry point
 * this class delegates to is what composes {@code WWW-Authenticate} from the error the resource server
 * detected -- an invalid token, an insufficient scope, an expired one -- and that header is part of the
 * OAuth 2.0 contract a client's token-refresh logic reads. Writing a body INSTEAD of the header would
 * trade one contract for another; delegating first and then writing the body honours both.</p>
 *
 * <h2>What the bodies say, and what they deliberately do not</h2>
 *
 * <p>Assumptions: neither body names what was missing. A 401 does not say whether the token was absent,
 * malformed, expired or signed by the wrong key, and a 403 does not name the authority the caller lacked
 * -- the sentence is the one {@link GlobalExceptionHandler#MESSAGE_FORBIDDEN} already publishes for a
 * denial reached through a controller, so the two paths are indistinguishable from outside. Telling an
 * unauthenticated caller which of four things was wrong with its credential, or which group it would need,
 * lets it probe; the operational log carries the distinction instead, keyed by the same correlation
 * identity the body reports.</p>
 *
 * <p>Trade-offs: two nested types in one file rather than two files. They share every constant, the writer
 * and the rendering method, and the two are only ever registered together -- a chain that rendered one and
 * not the other would be worse than a chain that rendered neither, because a client would then have to
 * branch on the status to know whether a body was present. Keeping them in one file is what makes that
 * pairing visible.</p>
 */
public final class ApiErrorSecurityHandlers {

    /**
     * The response code a 401 refusal carries.
     *
     * <p>Assumptions: the four-digit suffix is the HTTP status, matching the convention every other code
     * in {@link ApiError} follows, so a reader can map a code to a status without a table.</p>
     */
    public static final String CODE_UNAUTHENTICATED = "CARDDEMO-0401";

    /**
     * The sentence a 401 refusal carries.
     *
     * <p>Assumptions: the baseline's own sign-on refusals are not reused here, and the omission is
     * deliberate. Those three sentences belong to a credential comparison the migration moved to a
     * managed user pool, and reusing one would tell a caller presenting a bad TOKEN that its password was
     * wrong. This sentence is new text for a condition the baseline has no counterpart for, which is
     * permitted precisely because there is no baseline string to carry across.</p>
     */
    public static final String MESSAGE_UNAUTHENTICATED = "Sign on to continue";

    /**
     * The logger both handlers write their refusal lines to.
     *
     * <p>Assumptions: one logger named for this class rather than one per nested handler, so a deployment
     * raises or lowers the level for both refusals together. They are two halves of one decision and an
     * operator reading either wants both.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorSecurityHandlers.class);

    /**
     * The media type both refusal bodies are written with.
     *
     * <p>Assumptions: the same media type every other error in this stack is rendered as, so a client
     * parses one body shape regardless of which layer refused the request.</p>
     */
    private static final String PROBLEM_MEDIA_TYPE = "application/json";

    /**
     * The writer that renders both refusal bodies.
     *
     * <p>Alternatives Considered: resolving the application's configured mapper by injection. Rejected
     * because a security handler is constructed while the chain is being built and the record it writes
     * carries neither a money component nor a temporal one -- its timestamp is already a formatted string
     * -- so none of the modules a service registers changes how it serialises. A default mapper removes an
     * ordering dependency for no loss of fidelity.</p>
     */
    private static final ObjectMapper PROBLEM_WRITER = JsonMapper.builder().build();

    /**
     * Prevents instantiation of this handler holder.
     *
     * <p>Assumptions: the two handlers are the members callers want; the enclosing type exists to pair
     * them and to hold what they share. A private constructor states that, where an implicit public one
     * would invite a caller to inject the holder.</p>
     *
     * @throws AssertionError always, so that reflective instantiation fails as loudly as direct
     *     instantiation is prevented
     */
    private ApiErrorSecurityHandlers() {
        throw new AssertionError("ApiErrorSecurityHandlers is a holder and is never instantiated");
    }

    /**
     * Builds the entry point that answers an unauthenticated request.
     *
     * <p>Refactoring Rationale: the two handlers are reached through factories rather than constructed at
     * each of the seven call sites that install them. A chain that installed one handler and not the other
     * would publish the problem shape on one status and not the other, and the asymmetry is invisible in a
     * diff of a single service; naming the pair here and installing them together through
     * {@link #renderingRefusals(Clock)} makes the omission impossible to make quietly.</p>
     *
     * @param clock the clock the rendered body reads its failure instant from; must not be {@code null}
     * @return the entry point, never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public static AuthenticationEntryPoint entryPoint(Clock clock) {
        return new UnauthenticatedEntryPoint(clock);
    }

    /**
     * Builds the handler that answers an authenticated but unauthorised request.
     *
     * @param clock the clock the rendered body reads its failure instant from; must not be {@code null}
     * @return the handler, never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public static AccessDeniedHandler accessDeniedHandler(Clock clock) {
        return new ApiErrorAccessDeniedHandler(clock);
    }

    /**
     * Installs both handlers on a chain's exception-handling stage in one call.
     *
     * <p>Assumptions: a chain needs this stage AND the resource-server stage configured, not one of them.
     * The resource server installs its own entry point and denied handler on the bearer-token filter, and
     * that filter answers a request whose token was absent, expired or malformed before the exception
     * stage is reached; the exception stage answers a request that got past the filter and was then denied
     * by an authorisation rule. Configuring only the exception stage leaves the more common of the two
     * refusals -- a missing token -- rendered by the framework default, which is the state this class was
     * written to correct.</p>
     *
     * <p>Alternatives Considered: publishing the two handlers as beans from the shared auto-configuration
     * so that a service picked them up without naming them. Rejected because Spring Security does not
     * resolve either handler from the context for a resource-server chain -- both must be set on the
     * builder -- so a bean would be created, injected nowhere, and silently ineffective, which is a worse
     * failure than an explicit call a reader can see.</p>
     *
     * @param clock the clock the rendered bodies read their failure instant from; must not be {@code null}
     * @return a customizer that installs the entry point and the denied handler, never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public static Customizer<ExceptionHandlingConfigurer<HttpSecurity>> renderingRefusals(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return handling -> handling
                .authenticationEntryPoint(entryPoint(clock))
                .accessDeniedHandler(accessDeniedHandler(clock));
    }

    /**
     * Writes one problem shape onto a response that the filter chain is refusing.
     *
     * <p>Assumptions: the correlation identity is read from the mapped diagnostic context rather than
     * from the response header, because {@link CorrelationIdFilter} runs ahead of the security chain and
     * has already established it. Reading the context is what makes the body's identity the same value
     * the log line for this refusal carries.</p>
     *
     * <p>Assumptions: every header, the status, the media type and the length are set BEFORE the body is
     * written, because a response commits as soon as enough of its body has been written and a header set
     * after that point is discarded silently. Setting the length explicitly also keeps the refusal
     * byte-identical across containers.</p>
     *
     * @param request the request being refused, read only for its path
     * @param response the response to write onto; a response already committed by the delegate is left
     *     alone rather than written to twice
     * @param status the HTTP status to answer with
     * @param code the response code the body carries
     * @param message the sentence the body carries
     * @param clock the clock the body reads its failure instant from
     * @throws java.io.IOException if writing the body fails
     */
    private static void writeProblem(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message, Clock clock) throws java.io.IOException {

        // WHY : Assumptions: a response the delegate has already committed is left exactly as it is. The
        //       bearer-token entry point does not normally commit -- it sets a status and a header -- but
        //       a wrapper or a container could, and writing a body into a committed response throws in
        //       some containers and is silently discarded in others. Checking is cheaper than depending
        //       on which.
        if (response.isCommitted()) {
            return;
        }

        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        String path = CardNumberMasker.maskEmbeddedCardNumbers(request.getRequestURI());
        ApiError problem = new ApiError(code, "", message, ApiError.Severity.WARNING,
                ApiError.Subsystem.APPLICATION, status, correlationId, path,
                com.carddemo.common.time.TimestampFormatter.formatNow(clock), List.of(), null);

        byte[] body = PROBLEM_WRITER.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(PROBLEM_MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * Answers an unauthenticated request with HTTP 401, the bearer challenge, and the problem shape.
     *
     * <p>Assumptions: the framework's bearer-token entry point runs FIRST and is not replaced. It is what
     * composes the {@code WWW-Authenticate} challenge from the error the resource server detected, and
     * that header is part of the OAuth 2.0 contract a client's refresh logic reads. This handler adds the
     * body the published contracts promise and changes nothing else about the response.</p>
     */
    public static final class UnauthenticatedEntryPoint implements AuthenticationEntryPoint {

        /**
         * The delegate that sets the status and composes the challenge header.
         */
        private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();

        /**
         * The clock the rendered body reads its failure instant from.
         */
        private final Clock clock;

        /**
         * Creates the entry point.
         *
         * @param clock the clock the rendered body reads its failure instant from; must not be
         *     {@code null}
         * @throws NullPointerException if {@code clock} is {@code null}
         */
        public UnauthenticatedEntryPoint(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
        }

        /**
         * Renders the refusal.
         *
         * @param request the request that could not be authenticated, read only for its path
         * @param response the response to refuse on
         * @param failure the authentication failure the chain detected; its class is logged and its
         *     message is never rendered nor logged
         * @throws java.io.IOException if writing the body fails
         * @throws jakarta.servlet.ServletException if the delegate that composes the challenge header
         *     reports one, which the bearer-token entry point does not
         */
        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException failure)
                throws java.io.IOException, jakarta.servlet.ServletException {

            // WHY : Assumptions: the failure's own message is neither rendered nor logged, only its
            //       class. A resource server composes that message from the token it rejected, and a
            //       token is a credential: a message naming which claim failed can quote claim content,
            //       and a log line is the one destination the masking applied at the API edge does not
            //       reach. The class alone distinguishes an absent token from an invalid one, which is
            //       what an operator needs.
            LOG.warn("event=api.request.unauthenticated code={} status=401 path={} exception={}",
                    CODE_UNAUTHENTICATED, CardNumberMasker.maskEmbeddedCardNumbers(
                            request.getRequestURI()), failure.getClass().getName());

            this.challenge.commence(request, response, failure);
            writeProblem(request, response, HttpServletResponse.SC_UNAUTHORIZED, CODE_UNAUTHENTICATED,
                    MESSAGE_UNAUTHENTICATED, this.clock);
        }
    }

    /**
     * Answers an authenticated but unauthorised request with HTTP 403 and the problem shape.
     *
     * <p>Assumptions: the sentence and the code are the ones
     * {@link GlobalExceptionHandler#onAccessDenied} already publishes, so a denial decided by the filter
     * chain and one decided by a method-security check are indistinguishable from outside. A client that
     * could tell them apart would learn where in the stack the authority is checked.</p>
     */
    public static final class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

        /**
         * The clock the rendered body reads its failure instant from.
         */
        private final Clock clock;

        /**
         * Creates the handler.
         *
         * @param clock the clock the rendered body reads its failure instant from; must not be
         *     {@code null}
         * @throws NullPointerException if {@code clock} is {@code null}
         */
        public ApiErrorAccessDeniedHandler(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
        }

        /**
         * Renders the refusal.
         *
         * @param request the request that was denied, read only for its path
         * @param response the response to refuse on
         * @param failure the denial the chain raised; its class is logged and its message is never
         *     rendered
         * @throws java.io.IOException if writing the body fails
         */
        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                AccessDeniedException failure) throws java.io.IOException {

            LOG.warn("event=api.request.forbidden code={} status=403 path={} exception={}",
                    GlobalExceptionHandler.CODE_FORBIDDEN, CardNumberMasker.maskEmbeddedCardNumbers(
                            request.getRequestURI()), failure.getClass().getName());

            writeProblem(request, response, HttpServletResponse.SC_FORBIDDEN,
                    GlobalExceptionHandler.CODE_FORBIDDEN, GlobalExceptionHandler.MESSAGE_FORBIDDEN,
                    this.clock);
        }
    }
}
