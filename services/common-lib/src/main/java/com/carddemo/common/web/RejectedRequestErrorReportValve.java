package com.carddemo.common.web;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a refusal the servlet container issued before any application code could run, in the same
 * JSON envelope every other refusal on this fleet is answered in.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>A request target the container itself will not accept never reaches the application. The
 * connector decodes the target, finds a character it refuses, marks the response as an error, and
 * then discards the decoded form before the request is mapped to a web application. Mapping an
 * emptied target selects no application, so the request is answered by the container's own host-level
 * error reporter and by nothing else: no servlet filter runs, no dispatcher servlet runs, and no
 * handler is entered. The refusal itself is sound -- the target really is unacceptable, and no part
 * of it is acted on -- but the answer it produced was a hypertext status page carrying no correlation
 * identity, which is unreadable to the JSON clients this fleet publishes to and unjoinable to the
 * server-side record of the same refusal.</p>
 *
 * <p>This valve occupies the container's error-reporting slot and answers those refusals in the
 * {@link ApiError} envelope instead, at the status the container already chose.</p>
 *
 * <h2>Alternatives Considered: the two hooks that cannot reach this refusal</h2>
 *
 * <p>Alternatives Considered: a servlet filter, which is where every other cross-cutting response
 * concern on this fleet lives, including {@link CorrelationIdFilter} itself. It cannot answer this
 * case at all. A filter chain belongs to a web application, and this request was never mapped to one,
 * so no filter is constructed, entered or consulted -- registering one for every dispatcher type
 * changes nothing, because the request never becomes a dispatch.</p>
 *
 * <p>Alternatives Considered: an error-page mapping or a handler advice, which is where the shaped
 * refusals for caller input live. Neither can reach it either. An error page is a property of a web
 * application and is honoured by forwarding inside that application; an advice is invoked by the
 * dispatcher servlet from a thrown exception. Here there is no application to forward within, no
 * dispatcher invocation, and no exception -- the connector set a status and stopped.</p>
 *
 * <p>Assumptions: the container's error-reporting valve is therefore not one hook among several. It
 * is the only code that runs for this class of refusal, which is why the envelope has to be composed
 * here rather than shared with the filter that composes every other one.</p>
 *
 * <h2>What the envelope carries, and what it withholds</h2>
 *
 * <p>The body is the same record, built through the same factory, that
 * {@link GlobalExceptionHandler} builds for a refusal of caller input: the published machine code for
 * the status, a published sentence, the severity that status implies, the application subsystem, the
 * status itself, a correlation identity, and a timestamp in the contract's twenty-six-character form.
 * A client cannot tell a container-level refusal from an application-level one by the shape of what
 * it receives, which is the property this class exists to restore.</p>
 *
 * <p>Assumptions: three things are deliberately withheld, and each would be a disclosure rather than
 * a diagnostic. The rejected request target is not reflected, because it is caller-controlled and the
 * container's rejection of it is precisely the evidence that it is not a value this service should
 * repeat. Nothing derived from a throwable is rendered -- no message, no stack frame, no class name.
 * No file system path appears. The path member is therefore published empty rather than populated,
 * which is the same value the shared advice publishes when it answers without a request in hand.</p>
 *
 * <p>Trade-offs: withholding the target costs a caller the ability to tell two simultaneous bad
 * requests apart from the body alone, and the correlation identity is what replaces it. The identity
 * is published on the response and written to the server's own record of the refusal, so a caller
 * that quotes it can be told which target was refused without this service having echoed it.</p>
 *
 * <h2>Assumptions: when this valve declines</h2>
 *
 * <p>Assumptions: the valve answers only a refusal that produced no answer of its own. It declines,
 * leaving the container to behave exactly as it would without it, whenever the status is below the
 * first client-error status, the response is already committed, any content has already been written,
 * another reporter has already claimed the response, or the status is one for which this kernel
 * publishes no machine code. The first three make it impossible for this valve to overwrite a real
 * answer from a real handler; the last is the reason it refuses to invent a code rather than emitting
 * a plausible one.</p>
 *
 * <p>Trade-offs: declining on an unmapped status leaves the container's own page in place for the
 * statuses this kernel publishes no envelope for -- transport-level ones it has never published a
 * code for, such as an over-long request target, an unsupported protocol version or an unimplemented
 * method, and the three enumerated on the selector below. Emitting the generic client-error code for
 * those was considered and rejected: it would put a code whose numeric tail disagrees with the status
 * into the envelope, and a client keying off the code would be told the wrong thing about the refusal
 * it received. A page a client cannot parse is a smaller fault than an envelope that misdescribes
 * itself.</p>
 *
 * <p>Assumptions: the container's own reporting is suppressed on this instance -- neither the
 * diagnostic report nor the server identification is emitted on the declining path -- so the page a
 * decline falls through to discloses no more than the page this fleet already served.</p>
 *
 * <h2>Trade-offs: the identity is minted here and never echoed</h2>
 *
 * <p>Trade-offs: {@link CorrelationIdFilter} echoes a conforming identity the caller supplied and
 * mints one only when the caller supplied none. This valve always mints, so a caller that sent its
 * own identity does not get that identity back on this one class of refusal. The reason is that the
 * filter applies two rules to an inbound value and only the first of them is published: the alphabet
 * and width rule is available as
 * {@link CorrelationIdFilter#isConformingCorrelationId(String)}, while the rule that refuses a value
 * shaped like a protected identifier is not. Echoing on the published rule alone would let a
 * bare numeric value the filter would have refused be written back onto a response header, so this
 * valve reflects no inbound header at all. The minted identity uses the filter's own recipe and its
 * own published width, so the two transports mint values of one format.</p>
 *
 * <p>Assumptions: the identity is not published into the logging context. This code runs on a
 * container thread outside any filter's try-and-remove bracket, so a value put into that context here
 * would have no defined point of removal and would leak into whatever request the thread served next.
 * It is written as a field of this class's own record instead, which is what an operator joins
 * against.</p>
 *
 * <h2>Assumptions: how this class reaches a running service</h2>
 *
 * <p>Assumptions: no service declares this valve. It is installed by
 * {@link RejectedRequestErrorReportValveCustomizer}, which the shared kernel's auto-configuration
 * publishes, so every service that puts the kernel on its path answers a container-level refusal the
 * same way without configuring anything. That is the same argument the kernel makes for the
 * correlation filter and the shared handler advice.</p>
 *
 * <p>Assumptions: this class has no reference-only lineage. A container-level refusal of an HTTP
 * request target is a property of the transport this migration introduces, and the terminal-and-batch
 * baseline has no analogue of it to be faithful to.</p>
 */
public class RejectedRequestErrorReportValve extends ErrorReportValve {

    /**
     * The record this valve writes its own refusals to.
     */
    private static final Logger LOG = LoggerFactory.getLogger(RejectedRequestErrorReportValve.class);

    /**
     * The media type the envelope is served as, taken from the record that defines the envelope.
     */
    private static final String PROBLEM_MEDIA_TYPE = ApiError.MEDIA_TYPE;

    /**
     * The serialiser the envelope is rendered with.
     *
     * <p>Assumptions: a mapper of this class's own rather than the application's. The application's
     * mapper is a bean, and this valve runs on a request that never entered the application, so there
     * is no guarantee a context is available to resolve one from. The envelope needs no customisation
     * to render correctly -- every member of it is already a string, an integer, an enumeration or an
     * empty list, and the timestamp is formatted into its contract form before it reaches the
     * serialiser -- so a default mapper produces the identical bytes. This is the same choice
     * {@link CorrelationIdFilter} and {@link RequestBodySizeFilter} record for their own refusal
     * bodies, and it is repeated rather than shared because a shared static would make three
     * unrelated classes share one initialisation order.</p>
     */
    private static final ObjectMapper PROBLEM_WRITER = JsonMapper.builder().build();

    /**
     * The first client-error status, and the least status a refusal is composed for.
     *
     * <p>Assumptions: the two quantities coincide and are declared once rather than twice. The
     * container's own reporter uses the same threshold, so a status below it is not a refusal at all
     * and has no envelope to carry.</p>
     */
    private static final int BAD_REQUEST_STATUS = 400;

    /**
     * The status a request this service will not authorize is refused with.
     */
    private static final int FORBIDDEN_STATUS = 403;

    /**
     * The status a target this service does not serve is refused with.
     */
    private static final int NOT_FOUND_STATUS = 404;

    /**
     * The status a request asking for a representation this service does not produce is refused
     * with.
     */
    private static final int NOT_ACCEPTABLE_STATUS = 406;

    /**
     * The non-numeric opening of every minted identity.
     *
     * <p>Assumptions: the same two ASCII letters {@link CorrelationIdFilter} opens a minted identity
     * with, for the reason recorded there: two letters put a minted value outside the bare-numeric
     * class the filter refuses, by construction rather than by chance. The value is repeated here
     * because the filter's own constant is not published, and the two are held equal by this module's
     * tests rather than by a comment.</p>
     */
    private static final String GENERATED_ID_PREFIX = "CD";

    /**
     * The count of random bytes rendered into a minted identity.
     *
     * <p>Assumptions: derived from the published width and the prefix rather than written as a
     * literal, exactly as {@link CorrelationIdFilter} derives its own. Hexadecimal renders two
     * characters per byte, so the count is the width remaining after the prefix, halved -- which makes
     * a minted identity occupy {@link CorrelationIdFilter#CORRELATION_ID_MAX_LENGTH} characters
     * exactly. Deriving it is what keeps the width, the prefix and the entropy from disagreeing after
     * a change to any one of them.</p>
     */
    private static final int GENERATED_ID_RANDOM_BYTES =
            (CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH - GENERATED_ID_PREFIX.length()) / 2;

    /**
     * The source of entropy for a minted identity.
     *
     * <p>Assumptions: a cryptographic source rather than a general-purpose one, for the reason
     * recorded on {@link CorrelationIdFilter}: the identity is published on a response and into an
     * operational record, and a guessable one lets an observer holding a single value name the
     * identities of neighbouring requests.</p>
     */
    private static final SecureRandom ENTROPY_SOURCE = new SecureRandom();

    /**
     * The rendering applied to a minted identity's random bytes.
     */
    private static final HexFormat IDENTITY_RENDERER = HexFormat.of().withUpperCase();

    /**
     * The path member published on every envelope this valve writes, the empty string.
     *
     * <p>Assumptions: the rejected target is withheld rather than masked. Masking withholds the
     * protected identifiers inside a value and preserves everything else, which is right for a target
     * this service accepted and answered; here the container refused the target outright, so there is
     * nothing in it this service has undertaken to repeat. The empty string is what the shared handler
     * advice already publishes when it answers without a request in hand, so the member's shape is
     * unchanged.</p>
     */
    private static final String WITHHELD_PATH = "";

    /**
     * The reading the envelope's timestamp is taken from.
     */
    private final Clock clock;

    /**
     * Builds a valve reading the system clock in coordinated universal time.
     *
     * <p>Assumptions: this constructor exists because the container may construct this valve itself.
     * A host asked to guarantee an error reporter of a named class looks for one on its pipeline and,
     * finding none, instantiates the named class through its no-argument constructor; without one, a
     * host that had lost its reporter would be left with none at all. The customizer that installs
     * this valve names this class to the host for exactly that reason, so this constructor is the
     * path taken if the installed instance is ever removed.</p>
     *
     * <p>Trade-offs: a valve built this way reads a wall clock, so its envelope's timestamp is not
     * reproducible in a test. That is accepted for a constructor the container calls and a test does
     * not; the constructor taking a reading is the one the customizer uses.</p>
     */
    public RejectedRequestErrorReportValve() {
        this(Clock.systemUTC());
    }

    /**
     * Builds a valve reading the supplied clock.
     *
     * @param clock the reading the envelope's timestamp is taken from, supplied so a test and a
     *     refusal composed elsewhere in the same request can agree; must not be {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public RejectedRequestErrorReportValve(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        // WHY : Assumptions: both suppressions are set here rather than left to the installer,
        //       because this instance renders the container's page on every status it declines and a
        //       page carrying a diagnostic report or a server identification would disclose more
        //       than this fleet already serves. Setting them in the constructor is what makes the
        //       property hold for an instance the CONTAINER built through the no-argument
        //       constructor as well as for one the customizer built, and the container's instance is
        //       the one nobody configures.
        setShowReport(false);
        setShowServerInfo(false);
    }

    /**
     * Answers one container-level refusal, or declines and lets the container answer it.
     *
     * <p>Assumptions: the sequence is compose, then claim, then write, and the order is load-bearing.
     * Claiming the response is a one-shot operation -- the first reporter to claim it is the only one
     * permitted to write -- so claiming before the envelope is composed would leave the response
     * unanswered by anyone if composing then failed. Composing first means a composition that fails
     * costs nothing: the claim was never made, and the container's own reporting is still able to
     * make it.</p>
     *
     * <p>Assumptions: nothing thrown from here can reach the connector. The superclass invokes this
     * method inside its own catch of every throwable, and this method additionally catches around
     * both halves of its own work, so a fault in composing or in writing degrades to the container's
     * behaviour or to an unwritten body rather than to a failed response.</p>
     *
     * @param request the refused request, carried through to the container's own reporting on the
     *     declining path and read for nothing on the answering path
     * @param response the response the refusal is written to
     * @param throwable the failure the container is reporting, or {@code null} when it is reporting a
     *     status rather than a failure; carried through to the container's own reporting and never
     *     rendered into the envelope
     */
    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        String correlationId = null;
        byte[] body = null;
        try {
            if (isRenderable(response.getStatus(), response.isCommitted(),
                    response.getContentWritten())) {
                correlationId = mintCorrelationId();
                body = renderRefusal(response.getStatus(), correlationId, this.clock);
            }
        } catch (RuntimeException composition) {
            // WHY : Trade-offs: the fault's type is recorded and its message is not. A serialiser
            //       message quotes the member it was rendering and the value it held, and the whole
            //       discipline of this class is that a refusal discloses neither the caller's input
            //       nor this service's internals -- keeping the message out of the operational record
            //       applies that discipline to the record as well. The type together with the status
            //       distinguishes a serialising fault from a clock fault, which is the only choice
            //       this line has to support.
            LOG.warn("event=web.container.refusal.uncomposed status={} fault={}",
                    response.getStatus(), composition.getClass().getSimpleName());
            body = null;
        }

        if (body == null) {
            // WHY : Assumptions: the container is invoked rather than the response being left alone,
            //       so a status this valve declines is still answered. The superclass applies the
            //       same status and written-content guards before it writes anything, which is why
            //       this branch cannot answer a response that has already been answered.
            super.report(request, response, throwable);
            return;
        }

        if (!response.setErrorReported()) {
            // WHY : Assumptions: another reporter claimed the response between the guard above and
            //       this claim, so this valve writes nothing and does not fall through either.
            //       Falling through would ask the superclass to make a claim that has already been
            //       refused, which is the same outcome reached more slowly.
            return;
        }

        try {
            if (publish(response, correlationId, body)) {
                // WHY : Assumptions: the record names the status and the identity and no part of the
                //       target. The identity is what joins this line to the envelope the caller
                //       holds, and it is the whole of what a caller can quote, so it is the whole of
                //       what this line needs to be joinable.
                LOG.warn("event=web.container.refused status={} correlationId={}",
                        response.getStatus(), correlationId);
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("event=web.container.refusal.unwritten status={} fault={}",
                    response.getStatus(), failure.getClass().getSimpleName());
        }
    }

    /**
     * Reports whether a response is one this valve may answer.
     *
     * <p>Assumptions: the three facts are taken as arguments rather than read from a response inside
     * this method, so the decision is assertable without a container. The method is the whole of the
     * decision; the caller supplies the readings.</p>
     *
     * @param status the status the container has already chosen for the response
     * @param committed whether the response has already been sent to the caller
     * @param contentWritten the count of body bytes already written to the response
     * @return {@code true} when the response carries a refusal status and no answer of its own;
     *     {@code false} whenever answering it could displace an answer that already exists
     */
    static boolean isRenderable(int status, boolean committed, long contentWritten) {
        return status >= BAD_REQUEST_STATUS && !committed && contentWritten <= 0L;
    }

    /**
     * Renders the envelope for one refusal status.
     *
     * @param status the status the container chose, carried into the envelope unchanged
     * @param correlationId the identity published on both the envelope and the response header
     * @param clock the reading the envelope's timestamp is taken from
     * @return the envelope's bytes in the encoding it is served in, or {@code null} when this kernel
     *     publishes no machine code for the status and the container must answer instead
     */
    static byte[] renderRefusal(int status, String correlationId, Clock clock) {
        ApiError problem = problemFor(status, correlationId, clock);
        if (problem == null) {
            return null;
        }
        return PROBLEM_WRITER.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Selects the published code and sentence for one refusal status.
     *
     * <p>Assumptions: the code and the sentence for a status are chosen on one line, so the pair
     * cannot drift. Two lookups keyed by the same status were considered and rejected for exactly
     * that reason -- a status added to one and not the other would answer with a code and a sentence
     * that describe different refusals.</p>
     *
     * <p>Assumptions: every code AND every sentence selected here is one this kernel already
     * publishes. That invariant, rather than coverage, is what decides which statuses appear. Three
     * statuses this kernel publishes a code for are deliberately absent because of it: the conflict
     * status and the unavailable status, whose codes carry a business meaning -- one names a record
     * another writer changed, the other names writes deliberately held -- which a container choosing
     * the same number did not mean; and the payload status, whose only published sentence is composed
     * where the refusal happens and names the configured bound, a quantity this valve does not
     * hold.</p>
     *
     * @param status the status the container chose
     * @param correlationId the identity to publish on the envelope
     * @param clock the reading the envelope's timestamp is taken from
     * @return the envelope for the status, or {@code null} when no code is published for it
     */
    private static ApiError problemFor(int status, String correlationId, Clock clock) {
        return switch (status) {
            case BAD_REQUEST_STATUS -> ApiError.of(ApiError.CODE_VALIDATION,
                    GlobalExceptionHandler.MESSAGE_MALFORMED_REQUEST, status, correlationId,
                    WITHHELD_PATH, clock);
            case FORBIDDEN_STATUS -> ApiError.of(GlobalExceptionHandler.CODE_FORBIDDEN,
                    GlobalExceptionHandler.MESSAGE_FORBIDDEN, status, correlationId, WITHHELD_PATH,
                    clock);
            case NOT_FOUND_STATUS -> ApiError.of(ApiError.CODE_NOT_FOUND,
                    GlobalExceptionHandler.MESSAGE_NO_SUCH_PATH, status, correlationId,
                    WITHHELD_PATH, clock);
            case ApiError.METHOD_NOT_ALLOWED_STATUS -> ApiError.of(
                    ApiError.CODE_METHOD_NOT_ALLOWED,
                    GlobalExceptionHandler.MESSAGE_METHOD_NOT_ALLOWED, status, correlationId,
                    WITHHELD_PATH, clock);
            case NOT_ACCEPTABLE_STATUS -> ApiError.of(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE,
                    GlobalExceptionHandler.MESSAGE_NOT_ACCEPTABLE, status, correlationId,
                    WITHHELD_PATH, clock);
            case ApiError.UNSUPPORTED_MEDIA_TYPE_STATUS -> ApiError.of(
                    ApiError.CODE_UNSUPPORTED_MEDIA_TYPE,
                    GlobalExceptionHandler.MESSAGE_UNSUPPORTED_MEDIA_TYPE, status, correlationId,
                    WITHHELD_PATH, clock);
            case ApiError.INTERNAL_SERVER_ERROR_STATUS -> ApiError.of(ApiError.CODE_INTERNAL,
                    GlobalExceptionHandler.MESSAGE_INTERNAL, status, correlationId, WITHHELD_PATH,
                    clock);
            default -> null;
        };
    }

    /**
     * Mints one correlation identity of the format the correlation filter mints.
     *
     * @return an identity occupying {@link CorrelationIdFilter#CORRELATION_ID_MAX_LENGTH} characters
     *     exactly: the two-letter prefix followed by upper-case hexadecimal, never a bare run of
     *     digits and never a character outside the published alphabet
     */
    static String mintCorrelationId() {
        byte[] entropy = new byte[GENERATED_ID_RANDOM_BYTES];
        ENTROPY_SOURCE.nextBytes(entropy);
        return GENERATED_ID_PREFIX + IDENTITY_RENDERER.formatHex(entropy);
    }

    /**
     * Writes one composed envelope onto a response.
     *
     * <p>Assumptions: the header, the media type, the encoding and the length are all set before any
     * byte of the body is written. A response commits once enough of its body has been written, and a
     * header set after that point is discarded without an error, so the ordering is what makes the
     * correlation header a guarantee rather than a hope. This is the ordering both refusal-writing
     * filters in this package record, and it is repeated here because the three write different
     * bodies for different reasons.</p>
     *
     * <p>Assumptions: the status is read and never written. The container already chose it, and not
     * setting it is what makes carrying it true by construction rather than by a value this method
     * would have to be trusted to copy correctly.</p>
     *
     * <p>Assumptions: commitment is checked once more here, immediately before the first write, even
     * though the caller checked it before composing. The second check closes the window between the
     * two, and it is the check a test drives to prove that a response which has already answered is
     * left exactly as it was.</p>
     *
     * @param response the response to write onto
     * @param correlationId the identity to publish as the response's correlation header
     * @param body the envelope's bytes, already rendered in the encoding declared below
     * @return {@code true} when the envelope was written, {@code false} when the response was found
     *     committed and was left untouched
     * @throws IOException if the response's body cannot be written or flushed
     */
    static boolean publish(HttpServletResponse response, String correlationId, byte[] body)
            throws IOException {

        if (response.isCommitted()) {
            return false;
        }

        // WHY : Assumptions: the header name is read from the correlation filter rather than written
        //       as text, so the two transports that publish this header cannot come to disagree
        //       about its spelling. A second literal would be a second place to change.
        response.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        response.setContentType(PROBLEM_MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
        return true;
    }
}
