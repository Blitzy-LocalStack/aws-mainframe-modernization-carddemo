package com.carddemo.authorization.service;

/**
 * Closes one bounded processing window of authorization requests and opens the next.
 *
 * <h2>Why this seam exists</h2>
 *
 * <p>Refactoring Rationale: the reference consumer bounds how many requests it handles before its run
 * ends. {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} declares
 * {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at L40, increments its counter at L332 and
 * tests it at L339, and the queue re-triggers the program afterwards. The target consumer is a
 * continuously-polling container, so the bound cannot be expressed by a handler returning: the only way
 * to stop a 501st request from being handled is to stop the container RECEIVING it, and a container
 * cannot be stopped from the thread that is currently delivering a message to it. This interface is that
 * boundary, expressed once so the counting logic and the mechanism that acts on it are separable.
 *
 * <p>Alternatives Considered: refusing the 501st request inside the handler instead. Rejected because
 * neither available outcome is correct -- throwing sends a legitimate request toward the dead-letter
 * queue on a bound that has nothing to do with the request, and returning without handling deletes a
 * request nobody answered. Only closing intake before the receive keeps the bound and the message both
 * intact, which is exactly what the reference program's test-before-next-read achieves.
 *
 * <p>Alternatives Considered: leaving the bound unimplemented and documenting the container's
 * concurrency and poll settings as its replacement. Rejected because those settings bound work IN
 * FLIGHT rather than work COMPLETED, so they express a different quantity: a consumer configured for ten
 * concurrent messages still handles an unbounded number of them, and the published contract's promise to
 * enforce the declared limit would remain untrue.
 */
@FunctionalInterface
public interface RequestWindowBoundary {

    /**
     * Called once when a processing window has handled its full quota of requests.
     *
     * <p>Assumptions: the implementation must not block the calling thread on the container it acts on.
     * The call arrives on the listener thread that has just finished a message, and stopping a listener
     * container waits for its in-flight messages to complete -- including this one -- so a synchronous
     * stop on this thread would wait for itself.</p>
     *
     * <p>Assumptions: an implementation that cannot close the window is required to leave intake OPEN and
     * report the failure, never to leave it closed. A window that fails to reopen halts every
     * authorization in the system, which is a strictly worse outcome than a window that ran long.</p>
     *
     * @param handledInWindow how many requests the closing window handled, which is the configured quota
     *     whenever the window closed normally
     */
    void onWindowComplete(int handledInWindow);
}
