package com.carddemo.common.control;

/**
 * Raised when a mutating request arrives while the environment's online-write window is closed, or
 * while the gate cannot establish that it is open.
 *
 * <p>This is the target equivalent of the operator quiesce the baseline performed with an SDSF
 * command against the CICS file resources — {@code app/jcl/CLOSEFIL.jcl} closed the VSAM files
 * before the nightly chain ran and {@code app/jcl/OPENFIL.jcl} reopened them afterwards. In the
 * baseline a program attempting a write against a closed file received a file-status error and
 * abended; here the caller receives a refusal that names the condition.</p>
 *
 * <p>Assumptions: this is deliberately NOT a validation failure and NOT an authorization failure.
 * The request may be perfectly well formed and its caller fully entitled to make it; what is absent
 * is the window in which it may be applied. That is why it maps to {@code 503 Service Unavailable}
 * rather than to {@code 400} or {@code 403}: the condition is temporary, it is not the caller's
 * fault, and a client that retries after the window closes will succeed. A {@code 409} was
 * considered and rejected for the same reason — nothing about the request conflicts with stored
 * state.</p>
 *
 * <p>Trade-offs: an unchecked exception rather than a checked one, so that a mutating service
 * method does not have to declare it and so the gate can be applied by a servlet filter as well as
 * by a message listener. The cost is that the compiler does not force a caller to acknowledge it;
 * that is accepted because the intended handler is the shared {@code @RestControllerAdvice}, not
 * per-call-site recovery — there is no useful local recovery from "writes are closed".</p>
 */
public class OnlineWritesDisabledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message describing why the write was refused.
     *
     * @param message human-readable description of the refusal, which reaches the caller through the
     *     shared error handler; must name the condition rather than the parameter, because the
     *     parameter name is deployment topology and not something a client can act on
     */
    public OnlineWritesDisabledException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying failure that prevented the gate from
     * establishing the window's state.
     *
     * <p>Assumptions: this constructor exists for the FAIL-CLOSED path specifically. When the flag
     * cannot be read at all, the gate refuses the write and carries the cause, so an operator sees
     * the access-denied or timeout that caused the refusal rather than only its consequence.</p>
     *
     * @param message human-readable description of the refusal
     * @param cause the failure that prevented the gate from reading the flag; may not be
     *     {@code null}, because the message-only constructor already covers a deliberate closure
     */
    public OnlineWritesDisabledException(String message, Throwable cause) {
        super(message, cause);
    }
}
