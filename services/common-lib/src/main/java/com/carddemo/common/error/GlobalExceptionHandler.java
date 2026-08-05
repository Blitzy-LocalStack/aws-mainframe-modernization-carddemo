package com.carddemo.common.error;

import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * The single advice that renders every failed request of every migrated service as an
 * {@link ApiError}.
 *
 * <p>Refactoring Rationale: this class is the baseline's own pattern with one failure mode removed.
 * The authorization consumer {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} factors error
 * emission into one paragraph defined at its line 983 and invokes it from fourteen separate places --
 * lines 282, 316, 429, 500, 512, 547, 560, 595, 608, 639, 778, 846, 931 and 975. Centralising the
 * emission is therefore already house practice; the migrated form differs in exactly one respect,
 * which is that the advice is reached by an exception propagating out of a handler rather than by an
 * invocation written at each site. That single difference is the whole benefit: fourteen sites is
 * fourteen opportunities to add a fifteenth error path and forget the invocation, and a forgotten
 * invocation is invisible -- the program still works and the record simply is not written. An advice
 * reached by propagation has nothing at the call site to omit.</p>
 *
 * <p>Assumptions: nothing this class emits carries a credential, a connection string, a stack trace or
 * a vendor error code out to a client. Every problem shape is assembled from members
 * {@link ApiError} defines and never from the text of a caught exception; the caught exception goes to
 * the operational record instead, under the same correlation identity the response carries, so a
 * client can be answered with one short sentence while the failure stays fully diagnosable. The
 * restrict-on-delete mapping below is the sharpest case: a driver's constraint-violation text names
 * the schema, the table and the constraint, so returning it would both fail to tell the caller what to
 * do and disclose the internal shape of the store to an untrusted caller.</p>
 *
 * <p>Assumptions: this advice is registered by the shared kernel's own auto-configuration rather than
 * by each service, and is therefore active in a service that has written no configuration class at
 * all. That is deliberate for the same reason the propagation argument above is: a registration a
 * service has to remember is a registration a service can omit, and the symptom of omitting it is a
 * framework-shaped error body escaping from one service while the other seven answer in the migrated
 * shape.</p>
 *
 * <p>Trade-offs: the two conflict mappings are separate handlers rather than one handler testing the
 * exception type, even though both answer HTTP 409. Keeping them apart is what lets each carry its own
 * verbatim baseline message -- the optimistic-lock message is 46 characters and reproduced character
 * for character from line 522 of {@code app/cbl/COACTUPC.cbl} -- and transformation rule T8 requires
 * exactly that. One handler would have to choose a message before it knew which conflict it had.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * The verbatim message the baseline displays when a record was altered by another user.
     *
     * <p>Assumptions: reproduced character for character from
     * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'Record changed by some one else. Please review'}
     * at line 522 of {@code app/cbl/COACTUPC.cbl}. Three properties are carried across unchanged: it
     * is 46 characters, measured from the literal itself; it has no terminating period, the period
     * visible after the closing quote in the source being the statement terminator rather than part of
     * the text; and the two-word spelling of the third-party pronoun is preserved exactly as written,
     * the same spelling appearing again at line 286 of {@code app/cpy/CSUTLDPY.cpy}. Transformation
     * rule T8 requires user-visible strings to be carried character for character, and a spelling
     * regularised on the way through would be a silent behavioural change of precisely the kind that
     * rule exists to prevent.</p>
     */
    public static final String MESSAGE_RECORD_CHANGED =
            "Record changed by some one else. Please review";

    /**
     * The message returned when a reference row cannot be deleted because rows still point at it.
     *
     * <p>Assumptions: this describes the business rule rather than the constraint. The reference-data
     * foreign key that preserves the baseline's restrict semantic makes deleting a transaction type
     * impossible while categories still reference it, and the migrated response names that rule so the
     * caller knows what to do about it, where the database's own complaint would name the schema, the
     * table and the constraint and say nothing actionable.</p>
     */
    public static final String MESSAGE_REFERENCED_ROW =
            "Cannot delete: other records still refer to this entry";

    /**
     * The message returned for a failure the client cannot correct.
     *
     * <p>Assumptions: this sentence names nothing about the cause on purpose. The cause is written to
     * the operational record under the same correlation identity that the response carries, which is
     * what allows the response to stay uninformative without the failure becoming undiagnosable.</p>
     */
    public static final String MESSAGE_INTERNAL = "Unable to complete the request. Please try again";

    /**
     * The message returned when a request failed field validation.
     *
     * <p>Assumptions: the aggregate sentence reports that there are field errors and does not
     * enumerate them, because the enumeration travels in the per-field array. The baseline behaves the
     * same way: the aggregate text is written under a message-off latch and so reports the first
     * failure only, while the field markers accumulate.</p>
     */
    public static final String MESSAGE_VALIDATION_FAILED = "Please correct the highlighted fields";

    /**
     * The message returned when a request body could not be read at all.
     *
     * <p>Assumptions: kept distinct from {@link #MESSAGE_VALIDATION_FAILED} because an unreadable body
     * produces no field array -- there is no bound object to have fields -- so a client receiving the
     * validation sentence with an empty array would have nothing to act on.</p>
     */
    public static final String MESSAGE_MALFORMED_REQUEST = "The request could not be read";

    /**
     * The message returned when the record a request named does not exist.
     *
     * <p>Assumptions: deliberately generic. The baseline expresses this as a file status rather than as
     * an exception, and its reject reasons distinguish an unmatched cross-reference read from an
     * unmatched account read; a bounded context that needs that distinction supplies its own message
     * through {@link ApiError} rather than reusing this one.</p>
     */
    public static final String MESSAGE_NOT_FOUND = "The requested record was not found";

    /**
     * The message returned when an authenticated caller is not permitted the operation.
     *
     * <p>Assumptions: the message says the caller is not permitted and never says why, and it is the
     * same message whatever the missing authority was. Naming the required group would tell an
     * untrusted caller which authority to go and obtain.</p>
     */
    public static final String MESSAGE_FORBIDDEN = "Not authorized for this operation";

    /**
     * The code borne by a request from an authenticated caller lacking the required authority.
     *
     * <p>Assumptions: declared on this advice rather than on {@link ApiError} because it has no
     * counterpart anywhere else in the migrated model -- the baseline's authorization split is decided
     * inside a program rather than reported as a record status -- whereas the four codes on that type
     * each correspond to a shape a service builds directly.</p>
     */
    public static final String CODE_FORBIDDEN = "CARDDEMO-0403";

    /**
     * The fully-qualified name of the persistence abstraction's optimistic-locking failure.
     *
     * <p>Assumptions: a name rather than a class literal, for the reason recorded on
     * {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} -- the shared kernel must not
     * take a compile-time dependency on the persistence abstraction. It is a constant rather than an
     * inline literal so that the two names sit together and can be asserted by one test against the
     * library on the test path, which is where a rename in a future major version would be caught.</p>
     */
    private static final String OPTIMISTIC_LOCK_EXCEPTION_NAME =
            "org.springframework.dao.OptimisticLockingFailureException";

    /**
     * The fully-qualified name of the persistence abstraction's data-integrity violation.
     *
     * <p>Assumptions: named for the same reason as its sibling above. This is the base type of the
     * constraint-violation family, so a subclass raised for a specific breach is recognised through the
     * superclass walk rather than needing an entry of its own.</p>
     */
    private static final String INTEGRITY_VIOLATION_EXCEPTION_NAME =
            "org.springframework.dao.DataIntegrityViolationException";

    /**
     * The operational log this advice writes the caught failure to.
     *
     * <p>Assumptions: one static logger named for this class, so every failure in every service is
     * retrievable under one category. The mapped diagnostic context already carries the correlation
     * identity, published by the shared kernel's correlation filter, so no field of the log line has to
     * repeat it.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The mapped-diagnostic-context key the correlation identity is published under.
     *
     * <p>Assumptions: spelled as a literal here rather than imported from the sibling {@code web}
     * package's filter. The filter belongs to the servlet-facing half of the shared kernel and its
     * dependency is optional, so importing the constant would put a web type on the path of a service
     * that has none -- the batch context among them. The two spellings are asserted equal by this
     * module's tests, which is the same discipline the codec package applies to a width it cannot
     * import.</p>
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * The clock every emitted problem shape reads its timestamp from.
     *
     * <p>Assumptions: injected rather than resolved inside each handler, so a test can assert the
     * emitted timestamp exactly. A handler calling the system clock directly would emit a value no
     * assertion could name, which for a type whose timestamp format is itself a migrated contract --
     * the 26-character form of {@code app/cbl/CBTRN02C.cbl} -- would leave that format unverifiable.</p>
     */
    private final Clock clock;

    /**
     * Creates the advice with the clock its problem shapes are timestamped from.
     *
     * @param clock the clock every emitted {@link ApiError} reads its failure instant from; must not
     *     be {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public GlobalExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Renders a failure of bean validation on a request body as HTTP 400 with the per-field array.
     *
     * <p>Assumptions: the framework's own field-error list is translated into the migrated per-field
     * entries in the order the framework reports, which for a bound object is declaration order. That
     * is the closest available analogue of the baseline's reporting order, which is the order its
     * highlight expansions are written in -- lines 3208 to 3432 of {@code app/cbl/COACTUPC.cbl} -- and
     * therefore the order the fields appear on the screen.</p>
     *
     * @param failure the validation failure the framework raised, carrying one entry per rejected
     *     field; must not be {@code null}
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, the aggregate sentence and one
     *     per-field entry for each rejected field, never {@code null}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalidBody(MethodArgumentNotValidException failure,
            HttpServletRequest request) {

        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        for (FieldError rejected : failure.getBindingResult().getFieldErrors()) {
            // WHY : Assumptions: a rejected value of null and a rejected value of blank are told apart
            //       here, because the baseline tells them apart. Lines 18 and 19 of
            //       app/cpy/CSSETATY.cpy are one disjunctive test whose two branches are an
            //       unacceptable value and a never-supplied one, and the never-supplied branch does
            //       something extra rather than something different -- it additionally writes a marker.
            //       Collapsing the two would oblige every client to handle one case where the baseline
            //       handles two, and would lose the information a form needs to decide whether to show
            //       that marker.
            FieldValidationFlag state = isNeverSupplied(rejected.getRejectedValue())
                    ? FieldValidationFlag.BLANK
                    : FieldValidationFlag.NOT_OK;
            fieldErrors.add(new ApiError.FieldError(rejected.getField(), state,
                    messageOf(rejected)));
        }

        // WHY : Assumptions: logged at warning level rather than error, because a rejected request is
        //       an outcome the service is designed to produce and not a fault in it. The baseline draws
        //       the same line: a business-rule reject sets return code 4 at line 230 of
        //       app/cbl/CBTRN02C.cbl, which its condition-code rubric treats as a warning tier that the
        //       following step still runs, while a failed operation sets 8 or 16.
        LOG.warn("event=api.request.rejected code={} status=400 path={} fields={}",
                ApiError.CODE_VALIDATION, pathOf(request), fieldErrors.size());

        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(MESSAGE_VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST.value(), correlationId(), pathOf(request), fieldErrors,
                this.clock));
    }

    /**
     * Renders a failure of validation on a handler parameter as HTTP 400 with the per-field array.
     *
     * <p>Assumptions: handled separately from a rejected request body because the framework raises a
     * different type for a constraint on a path variable, a query parameter or a header, and that type
     * carries no bound object. Without this handler such a failure would fall through to the
     * catch-all below and be reported as an internal failure, which would tell a caller its own
     * malformed input was the service's fault.</p>
     *
     * @param failure the parameter validation failure the framework raised; must not be {@code null}
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION} and one per-field entry for each
     *     rejected parameter, never {@code null}
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> onInvalidParameter(HandlerMethodValidationException failure,
            HttpServletRequest request) {

        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        for (var parameterResult : failure.getParameterValidationResults()) {
            String field = parameterResult.getMethodParameter().getParameterName();
            // WHY : Assumptions: a class compiled without parameter names reports null here, but the
            //       method-parameter index is still available. The indexed fallback stays non-empty,
            //       as ApiError.FieldError requires, and preserves one identity per rejection just as
            //       every expansion at app/cpy/CSSETATY.cpy lines 17 to 27 receives a concrete screen
            //       field identity; dropping the entry would instead report a validation failure with
            //       nothing for the caller to act on.
            String fieldIdentity = field == null
                    ? "parameter[" + parameterResult.getMethodParameter().getParameterIndex() + "]"
                    : field;
            for (MessageSourceResolvable resolvable : parameterResult.getResolvableErrors()) {
                FieldValidationFlag state = isNeverSupplied(parameterResult.getArgument())
                        ? FieldValidationFlag.BLANK
                        : FieldValidationFlag.NOT_OK;
                fieldErrors.add(new ApiError.FieldError(fieldIdentity, state,
                        resolvedMessageOf(resolvable)));
            }
        }

        LOG.warn("event=api.request.rejected code={} status=400 path={} parameters={}",
                ApiError.CODE_VALIDATION, pathOf(request), fieldErrors.size());

        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(MESSAGE_VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST.value(), correlationId(), pathOf(request), fieldErrors,
                this.clock));
    }

    /**
     * Renders an unreadable request body as HTTP 400 without a per-field array.
     *
     * <p>Assumptions: the caught exception's own text is never returned. A body that fails to parse
     * produces a message naming the offending token and the position, which for a payload carrying a
     * primary account number or an amount would place that content in a response body and, from there,
     * into whatever logs the client keeps. The migrated form of that same protection is already applied
     * one layer down, where the money deserialiser reports a stable reason instead of the value it
     * could not read.</p>
     *
     * @param failure the parse failure the framework raised; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, the malformed-request sentence and an
     *     empty field array, never {@code null}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadableBody(HttpMessageNotReadableException failure,
            HttpServletRequest request) {

        // WHY : Trade-offs: the exception TYPE is logged and its message is not, which is the same rule
        //       the fixed-width codecs apply to a sensitive field: the constraint that was breached is
        //       safe to record and the content that breached it is not. What is given up is the offending
        //       token in the log line; what it buys is that no payload content reaches log storage, which
        //       is the one place the masking applied at the API edge does not reach.
        LOG.warn("event=api.request.unreadable code={} status=400 path={} exception={}",
                ApiError.CODE_VALIDATION, pathOf(request), failure.getClass().getName());

        return ResponseEntity.badRequest().body(ApiError.of(ApiError.CODE_VALIDATION,
                MESSAGE_MALFORMED_REQUEST, HttpStatus.BAD_REQUEST.value(), correlationId(),
                pathOf(request), this.clock));
    }

    /**
     * Renders a missing record as HTTP 404.
     *
     * <p>Assumptions: the standard-library no-such-element type is the one this advice recognises,
     * because a repository read that finds nothing surfaces as an empty optional and a caller that
     * insists on a value raises exactly that type. A bounded context needing the baseline's own reject
     * wording supplies it through {@link ApiError} rather than relying on this default.</p>
     *
     * @param failure the lookup failure raised by the service layer; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 404 carrying {@link ApiError#CODE_NOT_FOUND} and the not-found sentence, never
     *     {@code null}
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> onMissingRecord(NoSuchElementException failure,
            HttpServletRequest request) {

        LOG.warn("event=api.record.absent code={} status=404 path={} exception={}",
                ApiError.CODE_NOT_FOUND, pathOf(request), failure.getClass().getName());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ApiError.CODE_NOT_FOUND,
                MESSAGE_NOT_FOUND, HttpStatus.NOT_FOUND.value(), correlationId(), pathOf(request),
                this.clock));
    }

    /**
     * Renders the two conflict conditions as HTTP 409 and every other runtime failure as HTTP 500.
     *
     * <p>Refactoring Rationale: the two conflicts are discriminated by CLASS NAME rather than by
     * declaring a handler per exception type, and that is a deliberate consequence of what this module
     * is. Both types belong to the persistence abstraction, which the shared kernel does not depend on
     * -- it is a library every service compiles against, including the batch context, and adding a
     * persistence dependency here would put it on the path of every one of them. This package's charter
     * anticipates exactly this: the advice discriminates some exception types by name in order to avoid
     * a compile-time dependency on a framework it does not require. The names below are third-party
     * framework names, which is the only category such a string may hold.</p>
     *
     * <p>Assumptions: the whole superclass chain is examined rather than the exception's own class
     * alone, because the persistence layer raises SUBCLASSES -- an object-optimistic-locking failure
     * for a versioned entity, a constraint-violation subclass for a specific integrity breach -- and a
     * test on the exact class would miss every real occurrence while passing a unit test that raised
     * the base type.</p>
     *
     * <p>Refactoring Rationale on the first conflict: the baseline implements it by hand, so the
     * migrated form is a change of expression rather than of behaviour. {@code app/cbl/COACTUPC.cbl}
     * snapshots the pre-edit record from line 669, carries the change marker declared at line 168,
     * commits on the success path at lines 945 to 958, and on a failed rewrite sets a
     * locked-but-failed state and rolls back at lines 4097 to 4103. Nothing is lost in translation,
     * because the read-for-update lock was never held across the user's thinking time -- which is
     * precisely why the before-image had to exist. Other commit boundaries produce the same conflict
     * and therefore the same status: line 470 of {@code app/cbl/COCRDUPC.cbl}, line 335 of
     * {@code COPAUA0C.cbl}, line 686 of {@code COPAUS0C.cbl} and lines 557 to 558 of
     * {@code COPAUS1C.cbl}.</p>
     *
     * <p>Refactoring Rationale on the second conflict: the reference-data foreign key carries
     * {@code ON DELETE RESTRICT}, preserving the semantic the baseline's own Db2 constraint asserts, so
     * deleting a transaction type that categories still point at fails at the database. Answering with
     * the driver's own text would name the schema, the table and the constraint -- telling an untrusted
     * caller the internal shape of the store while still not saying what to do about it. Answering with
     * the business rule tells the caller what to change.</p>
     *
     * @param failure the runtime failure; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 409 with {@link #MESSAGE_RECORD_CHANGED} for an optimistic-lock conflict, HTTP 409
     *     with {@link #MESSAGE_REFERENCED_ROW} for an integrity violation, and otherwise the same
     *     HTTP 500 shape {@link #onUnexpectedFailure(Exception, HttpServletRequest)} produces; never
     *     {@code null}
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> onRuntimeFailure(RuntimeException failure,
            HttpServletRequest request) {

        if (isOfType(failure, OPTIMISTIC_LOCK_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.optimistic-lock code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ApiError.CODE_CONFLICT,
                    MESSAGE_RECORD_CHANGED, HttpStatus.CONFLICT.value(), correlationId(),
                    pathOf(request), this.clock));
        }

        if (isOfType(failure, INTEGRITY_VIOLATION_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.integrity code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ApiError.CODE_CONFLICT,
                    MESSAGE_REFERENCED_ROW, HttpStatus.CONFLICT.value(), correlationId(),
                    pathOf(request), this.clock));
        }

        return onUnexpectedFailure(failure, request);
    }

    /**
     * Reports whether a failure is, or descends from, the named exception type.
     *
     * @param failure the failure to classify; never {@code null} on any path that reaches here
     * @param typeName the fully-qualified name of the framework type to look for
     * @return {@code true} when the failure's own class or any of its superclasses carries that name
     */
    private static boolean isOfType(Throwable failure, String typeName) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if (typeName.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders an authorization failure as HTTP 403 without naming the missing authority.
     *
     * <p>Assumptions: the caller is authenticated by the time this is reached -- an unauthenticated
     * request is refused at the edge by the API's own token check and never arrives -- so this is the
     * admin-versus-user split of the baseline, whose one-character user type at lines 19 to 44 of
     * {@code app/cpy/COCOM01Y.cpy} becomes a signed group claim rather than a field the client supplies
     * about itself.</p>
     *
     * @param failure the access denial raised by the security layer; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 403 carrying {@link #CODE_FORBIDDEN} and {@link #MESSAGE_FORBIDDEN}, never
     *     {@code null}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> onAccessDenied(AccessDeniedException failure,
            HttpServletRequest request) {

        LOG.warn("event=api.access.denied code={} status=403 path={} exception={}",
                CODE_FORBIDDEN, pathOf(request), failure.getClass().getName());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(CODE_FORBIDDEN,
                MESSAGE_FORBIDDEN, HttpStatus.FORBIDDEN.value(), correlationId(), pathOf(request),
                this.clock));
    }

    /**
     * Renders any other failure as HTTP 500 with a sentence that names nothing about the cause.
     *
     * <p>Assumptions: this handler is the reason a framework-shaped or provider-shaped body can never
     * escape. Without it, an exception no other handler claims is rendered by the framework's own
     * default, whose body carries the exception message for many exception types -- and a persistence
     * or client-library message routinely carries a statement, a connection detail or a vendor code.
     * The caught throwable is logged with its stack trace through the logging pipeline, where the
     * appender configuration governs it, and the response carries only the fixed sentence.</p>
     *
     * <p>Trade-offs: {@link Exception} is caught rather than {@link Throwable}. An {@link Error} signals
     * that the virtual machine itself is in trouble -- exhausted memory, an unlinkable class -- and
     * answering such a condition with a rendered 500 would keep an unhealthy instance in the load
     * balancer's rotation, where the container health check is what should remove it. The batch context
     * makes the opposite choice for a reason particular to it, which is that its process exit status is
     * its only channel to the orchestrator; a request-serving process has a health check as well.</p>
     *
     * @param failure the unclaimed failure; logged with its stack trace, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 500 carrying {@link ApiError#CODE_INTERNAL} and {@link #MESSAGE_INTERNAL}, never
     *     {@code null}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpectedFailure(Exception failure,
            HttpServletRequest request) {

        // WHY : Assumptions: the exception TYPE is a structured field and the trace is carried as the
        //       throwable argument, so the message text is never interpolated into the log line itself.
        //       Passing the throwable rather than formatting it is what leaves redaction and layout to
        //       the appender configuration, which one place owns, instead of to this call site.
        LOG.error("event=api.request.failed code={} status=500 path={} exception={}",
                ApiError.CODE_INTERNAL, pathOf(request), failure.getClass().getName(), failure);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ApiError.CODE_INTERNAL, MESSAGE_INTERNAL,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), correlationId(), pathOf(request),
                        this.clock));
    }

    /**
     * Reads the correlation identity established for the current request.
     *
     * @return the identity the shared kernel's correlation filter published for this request, or the
     *     empty string when no filter ran -- which is the case in a slice test that exercises a
     *     controller without the servlet filter chain
     */
    private static String correlationId() {
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        return correlationId == null ? "" : correlationId;
    }

    /**
     * Reads the path of the request being answered.
     *
     * @param request the request to read, which may be {@code null} when this advice is exercised
     *     without a servlet request
     * @return the request's URI, or the empty string when no request was supplied
     */
    private static String pathOf(HttpServletRequest request) {
        return request == null ? "" : textOr(request.getRequestURI());
    }

    /**
     * Returns the given text, or the empty string when it is {@code null}.
     *
     * @param value the text to normalise, possibly {@code null}
     * @return the same text when it is non-{@code null}, otherwise the empty string
     */
    private static String textOr(String value) {
        return value == null ? "" : value;
    }

    /**
     * Extracts the help text a rejected field should display.
     *
     * @param rejected the framework's own field error, carrying the constraint's resolved message
     * @return the resolved message, or a fixed fallback when the constraint supplied none, so that an
     *     entry can never reach a client with nothing to display
     */
    private static String messageOf(FieldError rejected) {
        String resolved = rejected.getDefaultMessage();
        // WHY : Assumptions: a blank constraint message is replaced rather than passed through. The
        //       per-field entry type requires content, so an entry built from a blank message would
        //       raise inside this advice -- turning a rejected request into an internal failure, which
        //       is the one outcome an error path must not produce.
        return (resolved == null || resolved.isBlank()) ? MESSAGE_VALIDATION_FAILED : resolved;
    }

    /**
     * Extracts the help text a rejected handler parameter should display.
     *
     * @param resolvable the framework's own resolvable error for the rejected parameter, carrying the
     *     constraint's resolved message
     * @return the resolved message, or a fixed fallback when the constraint supplied none, so that an
     *     entry can never reach a client with nothing to display
     */
    private static String resolvedMessageOf(MessageSourceResolvable resolvable) {
        String resolved = resolvable.getDefaultMessage();
        // WHY : Assumptions: the same substitution as on a rejected body field, and for the same
        //       reason -- the per-field entry type requires content, so an entry built from a blank
        //       message would raise inside this advice and turn a rejected request into an internal
        //       failure.
        return (resolved == null || resolved.isBlank()) ? MESSAGE_VALIDATION_FAILED : resolved;
    }

    /**
     * Reports whether a rejected value represents a field the caller never supplied.
     *
     * @param rejectedValue the value the framework rejected, which is {@code null} for an absent
     *     member and a blank string for one submitted empty
     * @return {@code true} when the value is absent, empty or entirely whitespace, matching the
     *     never-supplied branch of the baseline's disjunctive highlight test
     */
    private static boolean isNeverSupplied(Object rejectedValue) {
        if (rejectedValue == null) {
            return true;
        }
        return rejectedValue instanceof CharSequence text && text.toString().isBlank();
    }
}
