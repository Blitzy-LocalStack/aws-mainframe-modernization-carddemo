package com.carddemo.common.control;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link OnlineWriteGate} to every mutating request a service serves, so that the online-write
 * window is enforced once per service rather than once per handler.
 *
 * <p>Refactoring Rationale: an interceptor rather than a call inside each mutating service method. The
 * per-method call was considered and rejected on completeness — the requirement is that EVERY mutating
 * path is gated, and a per-method call is only as complete as whoever adds the next controller
 * remembers to make it. An interceptor is closed by construction: a new {@code @PostMapping} is gated
 * the moment it exists, and the exceptions have to be declared rather than assumed.</p>
 *
 * <p>Refactoring Rationale: an interceptor rather than a servlet filter, and the difference is
 * load-bearing rather than stylistic. A filter runs ahead of the dispatcher, so an exception thrown
 * there never reaches the shared {@code @RestControllerAdvice} — the correlation filter in this same
 * module records exactly that constraint and has to render its own refusal body because of it. An
 * interceptor's {@code preHandle} runs inside the dispatcher, so a refusal thrown here is resolved by
 * the shared advice and reaches the caller as the same problem shape, with the same correlation
 * identifier and the same timestamp, as every other refusal the application produces. Duplicating the
 * body-rendering here to gain nothing was the alternative, and it would have been a second place for
 * the problem shape to drift.</p>
 *
 * <p>Assumptions: requests are classified by HTTP METHOD, because before a handler runs the method is
 * the only statement available about whether the request intends to change state. The classification
 * enumerates the SAFE methods and gates everything else, so {@code POST}, {@code PUT}, {@code PATCH}
 * and {@code DELETE} are gated by not being listed rather than by being listed. Handlers whose method
 * says write but whose operation is a read carry {@link OnlineWriteGateExempt}.</p>
 *
 * <p>Assumptions: the management endpoints are unaffected, because the actuator's handler mapping is
 * built independently of the MVC interceptor registry this interceptor is added to. That is the
 * behaviour wanted rather than an oversight: a health probe has to keep answering while writes are
 * quiesced, since the quiesce is precisely when an operator is watching the tasks.</p>
 */
public class OnlineWriteGateInterceptor implements HandlerInterceptor {

    /**
     * HTTP methods this interceptor treats as incapable of changing state, and therefore never gates.
     *
     * <p>Assumptions: the SAFE methods are enumerated and everything else is gated -- the inverse of
     * enumerating the mutating ones. The two are not equivalent, and the difference is the whole
     * fail-closed property of this classification: a list of mutating methods admits anything it fails
     * to name, so a handler mapped to a verb the list did not anticipate would silently escape the
     * control, whereas a list of safe methods refuses it. Refusing an unexpected verb costs nothing
     * real, because a verb no handler is mapped to is answered as unsupported in any case.</p>
     *
     * <p>Assumptions: these four and no others. {@code GET} and {@code HEAD} are safe by definition,
     * {@code OPTIONS} is a capability query the browser preflight depends on, and {@code TRACE} is a
     * diagnostic echo. {@code CONNECT} is deliberately absent -- it is a proxy verb that never reaches
     * an application handler, so listing it would widen the exemption for a case that cannot occur.</p>
     */
    static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final OnlineWriteGate gate;

    /**
     * Creates the interceptor over one gate.
     *
     * @param gate the decision this interceptor applies; must not be {@code null}
     * @throws NullPointerException if {@code gate} is {@code null}
     */
    public OnlineWriteGateInterceptor(OnlineWriteGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
    }

    /**
     * Refuses the request before its handler runs when the request intends to change state and the
     * online-write window is closed.
     *
     * @param request the request being dispatched; must not be {@code null}
     * @param response the response being built; not written to by this interceptor, because the
     *     refusal is rendered by the shared advice rather than here
     * @param handler the resolved handler, a {@link HandlerMethod} for a controller mapping
     * @return {@code true} always, when it returns at all — the request is stopped by the exception
     *     below rather than by returning {@code false}, because returning {@code false} would commit an
     *     empty {@code 200} that a caller could not distinguish from a successful write
     * @throws OnlineWritesDisabledException if the request is gated and the window is closed, or if the
     *     window's state cannot be established
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isGated(request, handler)) {
            this.gate.requireWritesEnabled();
        }
        return true;
    }

    /**
     * Reports whether one dispatched request must pass the gate before its handler runs.
     *
     * @param request the request under consideration; must not be {@code null}
     * @param handler the resolved handler
     * @return {@code true} when the method is not one of the safe ones and the handler does not declare
     *     itself exempt
     */
    private boolean isGated(HttpServletRequest request, Object handler) {
        String method = request.getMethod();
        // WHY : Assumptions: a null method is gated rather than admitted. The servlet contract does not
        //       permit one, so reaching this branch means something upstream is wrong -- and the safe
        //       response to "the request will not say what it intends" is to refuse it.
        if (method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return !isExempt(handler);
    }

    /**
     * Reports whether a handler declares itself a read despite its method.
     *
     * @param handler the resolved handler; a non-{@link HandlerMethod} handler is never treated as
     *     exempt, which is the safe default — the only such handlers in these services serve static
     *     resources and answer no mutating method anyway, so gating them removes nothing
     * @return {@code true} when {@link OnlineWriteGateExempt} is present on the handler method or on
     *     the controller that declares it
     */
    private static boolean isExempt(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        // WHY : Assumptions: the method is checked first and the declaring type second, so a controller
        //       whose every mutating operation is a read can state the exemption once while a
        //       controller that mixes reads and writes states it per operation. hasMethodAnnotation
        //       looks only at the method, and getBeanType() rather than getMethod().getDeclaringClass()
        //       is used for the type check so that a handler reached through a proxy still resolves to
        //       the controller a reader would look at.
        return handlerMethod.hasMethodAnnotation(OnlineWriteGateExempt.class)
                || handlerMethod.getBeanType().isAnnotationPresent(OnlineWriteGateExempt.class);
    }
}
