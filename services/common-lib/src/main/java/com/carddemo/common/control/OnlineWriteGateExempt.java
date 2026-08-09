package com.carddemo.common.control;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request handler whose HTTP method LOOKS like a write but whose operation is a read, so that
 * {@link OnlineWriteGateInterceptor} lets it through while the online-write window is closed.
 *
 * <p>Purpose. The gate classifies a request by its HTTP method, because the method is the only signal
 * available before a handler runs. That classification has a documented exception in this codebase:
 * several operations are {@code POST} despite being reads, because they carry an account, card or
 * customer identifier in a request body specifically to keep it out of the request line and therefore
 * out of the load balancer's access record. Those operations must stay available during the batch
 * window — reads are exactly what a quiesce is supposed to leave working — so each one says so here.</p>
 *
 * <p>Refactoring Rationale: an annotation at the handler rather than a list of exempt paths held by
 * the gate. A path list was considered first and rejected on drift: the path and the exemption would
 * live in different files, so renaming a route would silently move it from exempt to gated, and the
 * failure would surface as an internal read refused during the one window in which nobody is watching
 * for it. An annotation cannot drift from the handler it is attached to, and it puts the exemption
 * where a reviewer of the handler sees it.</p>
 *
 * <p>Assumptions: {@link #reason()} has NO default, deliberately. Exempting a route from a safety
 * control is exactly the kind of change that should be impossible to make silently, and requiring the
 * justification at the site makes the reason reviewable next to the operation it describes rather than
 * inferred from a route name. A default of the empty string was considered and rejected for that
 * reason alone.</p>
 *
 * <p>Trade-offs: absence of this annotation is what causes gating, so the safe direction is the
 * default — a mutating handler added tomorrow is gated the moment it exists, without anyone deciding
 * to gate it. The cost is that a genuine read added as a {@code POST} and not annotated will be
 * refused during the window. That is the right way round for the mistake to break: a refused read is
 * visible and recoverable, whereas a write applied during posting is neither.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface OnlineWriteGateExempt {

    /**
     * Why this operation is a read despite its method, and therefore why refusing it during the batch
     * window would remove a capability the quiesce is not meant to remove.
     *
     * @return the justification; must be stated, because there is no default
     */
    String reason();
}
