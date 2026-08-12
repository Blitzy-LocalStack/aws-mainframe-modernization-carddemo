package com.carddemo.common.web;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.security.CardNumberMasker;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses an HTTP request whose body exceeds a configured number of bytes, before the body is parsed.
 *
 * <h2>What this bound is for</h2>
 *
 * <p>Purpose: every migrated service accepts JSON request bodies, and nothing in the stack beneath this
 * filter puts a ceiling on how many bytes one of them may carry. The servlet container's own
 * post-size setting bounds only {@code application/x-www-form-urlencoded} data, which none of these
 * services accepts, so a JSON body reaches the message converter at whatever length the caller chose.
 * The consequence is a request that is refused only after its bytes have been received and buffered by
 * the deserialiser -- work the caller does not have to be authorised to cause. This filter makes the
 * ceiling explicit, states it in bytes, and applies it before any of that work happens.</p>
 *
 * <p>Assumptions: the exposure is not confined to the routes that require no credential, which is why
 * this filter is registered for every path rather than for the unauthenticated ones. An authenticated
 * caller can submit an over-large body just as easily, and the cost of parsing it is the same; a
 * credential narrows WHO can cause the work, not how much of it there is. Registering the bound once
 * for all paths is also what makes it a property of the platform rather than of whichever service
 * remembered to ask for it.</p>
 *
 * <h2>Two ways a body arrives, and one refusal for both</h2>
 *
 * <p>Assumptions: a request either declares its length or it does not, and the two cases need different
 * handling to reach the same refusal:</p>
 *
 * <ol>
 *   <li><b>A declared length.</b> {@code Content-Length} is present, so the bound is compared against
 *       the declared value and an over-large request is refused without one body byte being read. This
 *       is the ordinary case: the API Gateway HTTP API and the internal load balancer in front of these
 *       services both buffer a request and forward it with a declared length, and the shared
 *       {@code RestClient} callers set one for every body they send.</li>
 *   <li><b>No declared length.</b> A chunked request declares none, so there is nothing to compare and
 *       the bytes themselves have to be counted. This filter reads at most the bound plus one byte,
 *       refuses if that many arrive, and otherwise replays the buffered bytes to the rest of the chain
 *       through {@link HttpServletRequestWrapper}. The buffer therefore cannot exceed the bound by more
 *       than a single byte, which is what keeps the counting from being an exhaustion vector of its
 *       own.</li>
 * </ol>
 *
 * <p>Alternatives Considered: for the second case, wrapping the stream in a counting decorator that
 * throws once the bound is crossed, leaving the body streamed rather than buffered. Rejected because the
 * throw would surface from inside the message converter, where the shared advice's own handlers would
 * see it first: a converter that wraps it renders 400 for an unreadable body and the catch-all renders
 * 500, so the caller would receive one of two statuses that both describe the wrong thing, decided by
 * how far up the stack the exception happened to travel. Buffering a bounded prefix keeps the refusal in
 * this filter, where the status and the body are decided once. The cost is a byte array of at most the
 * bound for a chunked request, which is bounded by construction and does not arise at all for a request
 * that declares its length.</p>
 *
 * <p>Assumptions: the second case is entered only when the request carries a {@code Content-Type},
 * because a request with no media type has no body a converter could read -- every {@code GET} and
 * {@code HEAD} this system serves is in that category -- and wrapping those would allocate a reader for
 * a body that does not exist. A body-bearing request with no media type is refused by content
 * negotiation with 415 before its bytes are read, so it needs no bound here either.</p>
 *
 * <h2>What the refusal says, and what it withholds</h2>
 *
 * <p>Assumptions: the refusal names the bound and the size, and nothing else. A size is not protected
 * data -- it is a property of the request the caller itself assembled -- whereas any fragment of the
 * body could be a credential, a primary account number or a national identifier, so no part of the body
 * reaches the response or the log record. The path is placed in the response, masked by the same rule
 * the shared advice masks by, because a caller that has just been refused needs to know which request
 * was refused.</p>
 *
 * <p>Trade-offs: the response is rendered by this filter rather than by the shared advice at
 * {@code com.carddemo.common.error.GlobalExceptionHandler}. The advice is reached by propagation from
 * inside the dispatcher, and this filter deliberately refuses BEFORE the dispatcher runs, so the advice
 * is not reachable from here at all. The cost is a second place that writes the problem shape; it is
 * accepted for the same reason {@link CorrelationIdFilter} and
 * {@code com.carddemo.common.error.ApiErrorSecurityHandlers} both write it -- a refusal that happens
 * outside the dispatcher still has to carry the body every published contract declares -- and it is
 * mitigated by building that body through {@link ApiError} rather than by assembling JSON by hand.</p>
 *
 * @see CorrelationIdFilter for the identity this filter's refusal is reported under
 */
public final class RequestBodySizeFilter implements Filter {

    /**
     * The bound applied when a deployment names none, 65536 bytes.
     *
     * <p>Assumptions: the figure is derived from the largest body this system publishes a contract for
     * rather than chosen for roundness. That body is the account update, whose reference screen carries
     * 128 fields at {@code app/bms/COACTUP.bms}; rendered as JSON with every member present and every
     * member at its declared width, it is a few thousand bytes. Sixty-four kibibytes is an order of
     * magnitude above that, so no legitimate request approaches it, and it is small enough that a flood
     * of concurrent chunked requests each buffering the bound cannot exhaust a task's heap -- two
     * hundred of them is thirteen megabytes against the container's configured maximum.</p>
     *
     * <p>Trade-offs: a default is supplied at all, rather than requiring every deployment to name one.
     * An unbounded default would mean a service that omitted the property served requests with no
     * ceiling and looked no different from one that had set it, which is the failure this filter exists
     * to remove. A default that is too small fails visibly -- a legitimate request is refused with a
     * message naming the bound -- so a wrong default is correctable, whereas an absent one is not
     * observable.</p>
     */
    public static final long DEFAULT_MAX_BODY_BYTES = 65_536L;

    /** Records a refused request so an operator can see a caller sending bodies it cannot submit. */
    private static final Logger LOG = LoggerFactory.getLogger(RequestBodySizeFilter.class);

    /**
     * The media type the refusal body is served as.
     *
     * <p>Assumptions: the same media type {@link CorrelationIdFilter} serves its own refusal as, so the
     * two refusals a caller can receive before the dispatcher runs are indistinguishable in shape from
     * the ones the shared advice renders after it.</p>
     */
    private static final String PROBLEM_MEDIA_TYPE = "application/json";

    /**
     * The writer the refusal body is rendered by.
     *
     * <p>Assumptions: a locally constructed mapper rather than the application's configured one,
     * because a filter runs whether or not a web application context started successfully and the
     * problem shape carries no money member, so none of the shared codec modules is needed to render
     * it. This is the same decision {@link CorrelationIdFilter} records for its own writer.</p>
     */
    private static final ObjectMapper PROBLEM_WRITER = JsonMapper.builder().build();

    /** The size a servlet container reports for a request that declares no length. */
    private static final int UNDECLARED_LENGTH = -1;

    /** The configured ceiling, in bytes, on the body of one request. */
    private final long maxBodyBytes;

    /** The clock the refusal body's timestamp is read from. */
    private final Clock clock;

    /**
     * Creates a filter refusing any request body larger than the supplied number of bytes.
     *
     * <p>Assumptions: a non-positive bound fails here rather than on the first request. A bound of zero
     * would refuse every body-bearing request, including every write this system publishes, so a
     * deployment that supplied one would start successfully and then serve nothing but refusals; a
     * negative bound has no meaning at all. Failing at assembly turns both into a startup failure naming
     * the value.</p>
     *
     * @param maxBodyBytes the ceiling on one request's body, in bytes; must be positive
     * @param clock the clock the refusal body's timestamp is read from; must not be {@code null}
     * @throws IllegalArgumentException if {@code maxBodyBytes} is not positive
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public RequestBodySizeFilter(long maxBodyBytes, Clock clock) {
        if (maxBodyBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maxBodyBytes must be positive; a bound of " + maxBodyBytes
                            + " would refuse every request carrying a body");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Reports the ceiling this filter applies.
     *
     * @return the ceiling on one request's body, in bytes; always positive
     */
    public long maxBodyBytes() {
        return this.maxBodyBytes;
    }

    /**
     * Refuses an over-large body, and otherwise passes the request on unchanged.
     *
     * <p>Assumptions: a request that is not HTTP passes straight through. This filter reads a header and
     * writes a status, neither of which a non-HTTP request has, and the servlet contract permits a
     * container to run a chain for one; passing it on is the only behaviour that neither invents a
     * refusal nor pretends to have applied a bound.</p>
     *
     * @param request the request to bound; must not be {@code null}
     * @param response the response a refusal is written to; must not be {@code null}
     * @param chain the remainder of the chain, invoked for every request that is not refused; must not
     *     be {@code null}
     * @throws IOException if the body cannot be read or the refusal cannot be written
     * @throws ServletException if the remainder of the chain fails
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        long declared = httpRequest.getContentLengthLong();
        if (declared > this.maxBodyBytes) {
            refuse(httpRequest, httpResponse, declared);
            return;
        }

        // WHY : Assumptions: a declared length within the bound is trusted for the purpose of this
        //       filter, and the reason is worth stating because trusting a caller's header looks
        //       careless. The container enforces the declared length itself -- it stops the body at
        //       that many bytes and treats an inconsistent one as a protocol error -- so a caller that
        //       understates its length does not thereby smuggle a longer body past this bound. What a
        //       caller CAN do is omit the header entirely, which is the case the branch below counts.
        if (declared == UNDECLARED_LENGTH && httpRequest.getContentType() != null) {
            byte[] body = readBounded(httpRequest);
            if (body == null) {
                refuse(httpRequest, httpResponse, UNDECLARED_LENGTH);
                return;
            }
            chain.doFilter(new BufferedBodyRequest(httpRequest, body), response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Reads the body up to the bound, reporting an over-large body rather than returning it.
     *
     * <p>Assumptions: one byte beyond the bound is read deliberately. Reading exactly the bound cannot
     * distinguish a body of exactly that size, which is acceptable, from a longer one that has been
     * truncated, which is not, so the extra byte is what makes the two cases tell apart. It is the same
     * lookahead-by-one the reference baseline uses to decide whether a further browse page exists, at
     * line 1197 of {@code app/cbl/COCRDLIC.cbl}.</p>
     *
     * @param request the request whose body is to be read; must not be {@code null}
     * @return the whole body when it is within the bound, or {@code null} when it exceeds it
     * @throws IOException if the body cannot be read
     */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        long ceiling = this.maxBodyBytes + 1L;
        try (InputStream body = request.getInputStream()) {
            byte[] read = body.readNBytes((int) Math.min(ceiling, Integer.MAX_VALUE));
            return read.length > this.maxBodyBytes ? null : read;
        }
    }

    /**
     * Writes the refusal, naming the bound and the size that broke it.
     *
     * <p>Assumptions: the status, the media type and the content length are all set BEFORE the body is
     * written, because a response commits once enough of its body has been written and a header set
     * after that point is discarded silently. This is the ordering {@link CorrelationIdFilter} records
     * for its own refusal, and it is repeated here rather than shared because the two filters write
     * different bodies for different reasons.</p>
     *
     * @param request the refused request, read for the path the refusal is attributed to; must not be
     *     {@code null}
     * @param response the response the refusal is written to; must not be {@code null}
     * @param declared the length the request declared, or {@code -1} when it declared none
     * @throws IOException if the refusal cannot be written
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response, long declared)
            throws IOException {

        String size = declared == UNDECLARED_LENGTH
                ? "the submitted body declares no length and exceeds that bound"
                : "the submitted body declares " + declared + " bytes";
        String detail = "The request body must be at most " + this.maxBodyBytes + " bytes; " + size
                + ". Submit a smaller request.";

        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        String path = CardNumberMasker.maskEmbeddedCardNumbers(request.getRequestURI());

        // WHY : Assumptions: the record names the method, the masked path and the two sizes, and no
        //       part of the body. A refusal is the one case where the body is known to be something
        //       this service would not accept, which is precisely when a fragment of it is most likely
        //       to be neither valid JSON nor safe to render; the two sizes are what an operator needs
        //       to tell a misconfigured client from a deliberate flood, and they are properties of the
        //       request rather than contents of it.
        LOG.warn("event=web.body.refused method={} path={} declaredBytes={} limitBytes={}",
                request.getMethod(), path, declared, this.maxBodyBytes);

        ApiError problem = ApiError.of(ApiError.CODE_PAYLOAD_TOO_LARGE, detail,
                ApiError.PAYLOAD_TOO_LARGE_STATUS, correlationId, path, this.clock);
        byte[] body = PROBLEM_WRITER.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);

        response.setStatus(ApiError.PAYLOAD_TOO_LARGE_STATUS);
        response.setContentType(PROBLEM_MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * Replays a body this filter has already read, so the rest of the chain can read it once more.
     *
     * <p>Assumptions: the wrapper reports the buffered length as the request's content length, where the
     * wrapped request reported none. A message converter that sizes its own buffer from the reported
     * length would otherwise size it for an absent body, and a caller reading the wrapper cannot tell
     * that the length it now sees was counted rather than declared -- which is the point, because from
     * the converter's side the two are the same fact.</p>
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        /** The whole body, already read from the wrapped request. */
        private final byte[] body;

        /**
         * Wraps a request around a body that has already been read from it.
         *
         * @param request the request whose body was read; must not be {@code null}
         * @param body the bytes read from it, replayed to every later reader; must not be {@code null}
         */
        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        /**
         * Reports the counted length of the buffered body.
         *
         * @return the number of bytes buffered, or {@code -1} when that exceeds an {@code int}
         */
        @Override
        public int getContentLength() {
            return this.body.length;
        }

        /**
         * Reports the counted length of the buffered body.
         *
         * @return the number of bytes buffered; never negative
         */
        @Override
        public long getContentLengthLong() {
            return this.body.length;
        }

        /**
         * Opens a stream over the buffered body.
         *
         * @return a stream replaying every buffered byte, never {@code null}
         */
        @Override
        public ServletInputStream getInputStream() {
            return new BufferedServletInputStream(this.body);
        }

        /**
         * Opens a reader over the buffered body in the request's own character encoding.
         *
         * <p>Assumptions: an absent or unusable encoding falls back to UTF-8 rather than failing.
         * The servlet contract permits the encoding to be absent, and a name the platform cannot
         * resolve is a caller-supplied value; refusing either here would answer a charset problem with
         * a body-size filter's failure, whereas falling back leaves the decision to the converter that
         * actually cares about the encoding.</p>
         *
         * @return a reader over the buffered body, never {@code null}
         */
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(this.body), charset()));
        }

        /**
         * Resolves the request's character encoding, defaulting to UTF-8.
         *
         * @return the resolved charset, never {@code null}
         */
        private Charset charset() {
            String declared = getCharacterEncoding();
            if (declared == null || declared.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(declared.trim());
            } catch (IllegalArgumentException unusable) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /**
     * A servlet stream over a byte array already in memory.
     *
     * <p>Trade-offs: {@link #setReadListener(ReadListener)} refuses rather than pretending to register
     * one. A read listener exists so that a container can notify a caller when more of a body has
     * arrived over the network, and this stream's body arrived in full before the stream was created,
     * so there is no later arrival to notify. Accepting the registration and never calling the listener
     * would leave an asynchronous reader waiting forever, which is a worse failure than a refusal
     * naming the reason. Nothing on the blocking request path this filter serves registers one: the
     * message converters read the stream to its end synchronously.</p>
     */
    private static final class BufferedServletInputStream extends ServletInputStream {

        /** The buffered body this stream reads from. */
        private final ByteArrayInputStream buffer;

        /**
         * Opens a stream over the supplied bytes.
         *
         * @param body the bytes to replay; must not be {@code null}
         */
        private BufferedServletInputStream(byte[] body) {
            this.buffer = new ByteArrayInputStream(body);
        }

        /**
         * Reports whether every buffered byte has been read.
         *
         * @return {@code true} once no byte remains
         */
        @Override
        public boolean isFinished() {
            return this.buffer.available() == 0;
        }

        /**
         * Reports that a read never blocks, because the body is already in memory.
         *
         * @return always {@code true}
         */
        @Override
        public boolean isReady() {
            return true;
        }

        /**
         * Refuses to register a read listener for a body that has already arrived in full.
         *
         * @param readListener the listener offered; never registered
         * @throws UnsupportedOperationException always, for the reason recorded on this class
         */
        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException(
                    "a buffered request body has already arrived in full, so there is no later"
                            + " arrival for a read listener to be notified of");
        }

        /**
         * Reads the next buffered byte.
         *
         * @return the next byte, or {@code -1} once no byte remains
         */
        @Override
        public int read() {
            return this.buffer.read();
        }

        /**
         * Reads the next run of buffered bytes into the supplied array.
         *
         * @param target the array to read into; must not be {@code null}
         * @param offset the first position in {@code target} to write
         * @param length the greatest number of bytes to read
         * @return the number of bytes read, or {@code -1} once no byte remains
         */
        @Override
        public int read(byte[] target, int offset, int length) {
            return this.buffer.read(target, offset, length);
        }

        /**
         * Reports how many buffered bytes remain.
         *
         * @return the number of bytes not yet read; never negative
         */
        @Override
        public int available() {
            return this.buffer.available();
        }
    }
}
