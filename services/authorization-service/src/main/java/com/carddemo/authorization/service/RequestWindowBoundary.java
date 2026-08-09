package com.carddemo.authorization.service;

/**
 * Closes one bounded processing window of authorization requests and opens the next.
 *
 * <h2>Why this seam exists</h2>
 *
 * <p>Refactoring Rationale: the reference consumer bounds how many requests it handles before its run
 * ends. {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} declares
 * {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at L40, increments its counter at L332 and
 * tests it at L339, and the queue re-triggers the program afterwards. Because the increment precedes a
 * strict greater-than comparison, the run processes 501 requests and the target admits the same 501. The
 * target consumer is a continuously-polling container, so the bound cannot be expressed by a handler
 * returning: the only way to stop a 502nd request from being handled is to stop the container RECEIVING
 * it, and a container cannot be stopped from the thread that is currently delivering a message to it.
 * This interface is that boundary, expressed once so the admission logic and the mechanism that acts on
 * it are separable.
 *
 * <p>Alternatives Considered: refusing the request past the allowance inside the handler instead.
 * Rejected because
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
     * Called once when a processing window has granted its full allowance of admissions.
     *
     * <p>Assumptions: the implementation must not block the calling thread on the container it acts on.
     * The call arrives on the listener thread that is admitting a message, and stopping a listener
     * container waits for its in-flight messages to complete -- including that one -- so a synchronous
     * stop on this thread would wait for itself.</p>
     *
     * <p>Assumptions: an implementation that cannot close the window is required to leave intake OPEN and
     * report the failure, never to leave it closed. A window that fails to reopen halts every
     * authorization in the system, which is a strictly worse outcome than a window that ran long.</p>
     *
     * <p>Refactoring Rationale: the generation is passed as well as the count, and it was not. The count
     * alone cannot distinguish one closure from another, so nothing at this seam could tell a second
     * closure of one window from the first closure of the next -- and the defect that made that
     * distinction necessary was exactly a window being closed twice. The generation makes
     * "exactly one closure per window" an observable property rather than an argued one, in a log line and
     * in a test.</p>
     *
     * @param generation which window is closing, counting from zero and increasing by one per closure, so
     *     two calls carrying one generation are a defect rather than a repetition
     * @param admittedInWindow how many requests the closing window admitted, which is the configured
     *     allowance whenever the window closed normally
     */
    void onWindowComplete(long generation, int admittedInWindow);
}
