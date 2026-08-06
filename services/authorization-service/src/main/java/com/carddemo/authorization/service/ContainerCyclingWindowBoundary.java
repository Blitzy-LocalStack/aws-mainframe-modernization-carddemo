package com.carddemo.authorization.service;

import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Closes a bounded processing window by cycling the request listener container.
 *
 * <h2>What this does and why it is the mechanism</h2>
 *
 * <p>Refactoring Rationale: this is the production {@link RequestWindowBoundary}. It stops the request
 * listener container and starts it again, which is the container-model equivalent of the reference
 * consumer's run ending and the queue re-triggering it. Stopping is what makes the bound real: while the
 * container is stopped it issues no receive, so the request that would have been the quota-plus-first of
 * the window is simply not delivered, and it stays on the queue until the next window opens. Nothing is
 * refused, dead-lettered or deleted unhandled.
 *
 * <p>Assumptions: the work happens on this class's own single-threaded executor rather than on the
 * calling thread, and that is a correctness requirement rather than a performance choice. The boundary is
 * reached on the listener thread that has just finished a message, and
 * {@code AbstractMessageListenerContainer.stop()} waits for in-flight messages to complete; calling it
 * inline would therefore wait for the very message whose completion triggered it.
 *
 * <p>Trade-offs: cycling a container is a heavier act than the reference program's run simply ending, and
 * the cost is a brief pause in intake at each boundary -- for the queue depth this workload carries, that
 * pause is bounded by how long the in-flight messages take to finish. What it buys is that the declared
 * limit is enforced exactly, which is what the published messaging contract states. The lighter
 * alternative -- resetting the counter and continuing -- was rejected because it makes the bound
 * unobservable and enforces nothing, which is the state this class was written to correct.
 *
 * <p>Trade-offs: every failure path here leaves intake OPEN. If the restart fails, the container is
 * started again on the next attempt and the failure is logged at error level; if the stop fails, nothing
 * was closed and the window simply ran long. A window that fails to reopen would halt every authorization
 * in the system, so failing open on availability is the deliberate choice, and it is the reason the stop
 * and the start are not wrapped in one try block that could exit between them.
 */
@Component
public class ContainerCyclingWindowBoundary implements RequestWindowBoundary, DisposableBean {

    /**
     * The identifier the request listener container is registered under.
     *
     * <p>Assumptions: the same literal is set as the {@code id} of the {@code @SqsListener} on
     * {@link AuthorizationRequestListener}. It is a constant here because a mismatch between the two would
     * not fail at startup -- the registry would simply return nothing and every window would run long
     * without the bound ever taking effect -- and a silent loss of a bound is the failure mode this whole
     * class exists to prevent.</p>
     */
    public static final String REQUEST_CONTAINER_ID = "carddemo-pauth-request-listener";

    /**
     * How long a stop is allowed to take before the restart is attempted regardless.
     *
     * <p>Assumptions: the container's own stop applies its own shutdown timeout, so this bound exists only
     * to keep this class's executor from being occupied indefinitely by a container that never returns.
     * Thirty seconds is longer than any single authorization takes, an authorization being one decision
     * over a handful of keyed reads and three writes.</p>
     */
    public static final long STOP_TIMEOUT_SECONDS = 30L;

    /**
     * The logger for window boundaries, named for this class so a deployment can raise or lower the
     * cycling record without touching the consumer's own logging.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ContainerCyclingWindowBoundary.class);

    /**
     * The registry the request container is resolved from.
     */
    private final MessageListenerContainerRegistry registry;

    /**
     * The single thread every boundary is executed on.
     *
     * <p>Assumptions: exactly one thread, so two boundaries reached in quick succession cycle the
     * container one after the other rather than concurrently. A concurrent stop and start of the same
     * container is a race whose outcome is a stopped container, which is the one state this class must
     * never leave behind.</p>
     */
    private final ExecutorService cycler = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pauth-window-boundary");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Creates the boundary over the application's listener registry.
     *
     * @param registry the registry the request container is registered in; must not be {@code null}
     * @throws NullPointerException if {@code registry} is {@code null}
     */
    public ContainerCyclingWindowBoundary(MessageListenerContainerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Cycles the request container so the next window starts with a fresh quota.
     *
     * @param handledInWindow how many requests the closing window handled
     */
    @Override
    public void onWindowComplete(int handledInWindow) {
        LOG.info("event=auth.window.closed handled={} containerId={}",
                handledInWindow, REQUEST_CONTAINER_ID);
        this.cycler.execute(this::cycleContainer);
    }

    /**
     * Releases the boundary thread when the application context closes.
     *
     * <p>Assumptions: the executor is shut down rather than left to the daemon flag alone, so a context
     * that closes while a cycle is in progress waits briefly for it instead of tearing the thread down
     * mid-cycle and leaving the container stopped.</p>
     *
     * @throws InterruptedException if the wait for the in-progress cycle is interrupted, which is
     *     propagated so the shutdown sequence sees it
     */
    @Override
    public void destroy() throws InterruptedException {
        this.cycler.shutdown();
        if (!this.cycler.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            this.cycler.shutdownNow();
        }
    }

    /**
     * Stops and restarts the request container, leaving intake open whatever happens.
     *
     * <p>Assumptions: the stop and the start are guarded separately and the start is unconditional. If the
     * stop threw, nothing was closed and starting an already-running container is a no-op; if the stop
     * succeeded, the start is the only thing that reopens intake. Guarding them together would admit a
     * path that closed intake and never reopened it.</p>
     */
    private void cycleContainer() {
        MessageListenerContainer<?> container = this.registry.getContainerById(REQUEST_CONTAINER_ID);
        if (container == null) {
            LOG.error("event=auth.window.cycle.skipped reason=container-not-registered containerId={}",
                    REQUEST_CONTAINER_ID);
            return;
        }
        try {
            container.stop();
        } catch (RuntimeException failure) {
            LOG.error("event=auth.window.cycle.stop-failed containerId={} exception={}",
                    REQUEST_CONTAINER_ID, failure.getClass().getName());
        }
        try {
            container.start();
            LOG.info("event=auth.window.opened containerId={}", REQUEST_CONTAINER_ID);
        } catch (RuntimeException failure) {
            // WHY : Trade-offs: this is the one failure that matters and it is logged at error level with
            //       the class of the failure and nothing else. A container that failed to start accepts no
            //       requests at all, so the record has to be loud enough to alarm on; the failure's own
            //       message is withheld because a transport failure composes it from endpoint and
            //       credential material and a log line is retained more widely than the build.
            LOG.error("event=auth.window.cycle.start-failed containerId={} exception={}",
                    REQUEST_CONTAINER_ID, failure.getClass().getName());
        }
    }
}
