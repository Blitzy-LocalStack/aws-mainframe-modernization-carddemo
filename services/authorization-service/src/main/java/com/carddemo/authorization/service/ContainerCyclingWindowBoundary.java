package com.carddemo.authorization.service;

import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Closes a bounded processing window by cycling the request listener container.
 *
 * <h2>What this does and why it is the mechanism</h2>
 *
 * <p>Refactoring Rationale: this is the production {@link RequestWindowBoundary}. It stops the request
 * listener container and starts it again, which is the container-model equivalent of the reference
 * consumer's run ending and the queue re-triggering it. Stopping is what makes the bound real: while the
 * container is stopped it issues no receive, so the request that would have been admitted past the
 * window's allowance is simply not delivered, and it stays on the queue until the next window opens.
 * Nothing is refused, dead-lettered or deleted unhandled.
 *
 * <p>Assumptions: the work happens on this class's own single-threaded executor rather than on the
 * calling thread, and that is a correctness requirement rather than a performance choice. The boundary is
 * reached on the listener thread that is ADMITTING a message, and
 * {@code AbstractMessageListenerContainer.stop()} waits for in-flight messages to complete; calling it
 * inline would therefore wait for the very message whose admission triggered it, which is a deadlock
 * rather than a slow shutdown.
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
public class ContainerCyclingWindowBoundary
        implements RequestWindowBoundary, DisposableBean, HealthIndicator {

    /**
     * The identifier the request listener container is registered under.
     *
     * <p>Assumptions: this constant IS the {@code id} of the {@code @SqsListener} on
     * {@link AuthorizationRequestListener} -- that annotation references this field rather than repeating
     * its text, so the two cannot drift apart while the reference stands. The value lives here rather than
     * there because a mismatch between the two would not fail at startup: the registry would simply return
     * nothing and every window would run long without the bound ever taking effect, and a silent loss of a
     * bound is the failure mode this whole class exists to prevent. That agreement is asserted from the
     * annotation by {@code ContainerCyclingWindowBoundaryTest}, so a later edit replacing the reference with
     * a literal fails a test rather than passing quietly.</p>
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
     * How many times a restart is attempted before the container is declared unrecoverable.
     *
     * <p>⚠️ Assumptions: three attempts, and the bound is the point of the constant. The failures worth
     * retrying here are transient by nature -- a transport connection refused mid-cycle, a credential
     * refresh in flight -- and they clear in seconds; a failure that survives three attempts over seven
     * seconds is a configuration or permission fault that will not clear by being asked again. Retrying
     * without a bound would keep one thread trying forever while the service accepted no work and
     * reported itself healthy, which is the state this class now exists to make impossible.</p>
     */
    public static final int START_ATTEMPTS = 3;

    /**
     * The delay before the second restart attempt, in milliseconds, doubled for each attempt after it.
     *
     * <p>Assumptions: one second, doubling to two, so three attempts span roughly three seconds of
     * waiting. The wait happens on this class's own single cycling thread, so it delays nothing else --
     * and it is a wait rather than an immediate retry because an immediate one re-runs the same failing
     * call against the same unchanged condition, which is three attempts spent as one.</p>
     */
    public static final long START_BACKOFF_MILLIS = 1_000L;

    /**
     * Whether the request container is believed to be stopped and unrecoverable.
     *
     * <p>⚠️ Assumptions: this flag is the whole reason this class reports health, and it is what makes a
     * dead listener visible OUTSIDE the log. A container that fails to restart consumes no messages, so
     * the service is entirely inert while still answering its readiness probe -- requests accumulate on
     * the queue, the reply the requester is waiting for never comes, and the only trace was one log line
     * on one task. With the flag reported through health, the orchestrator's own health check fails and
     * the task is replaced, which is the action a human would take on reading that line.</p>
     *
     * <p>Assumptions: it is set only after every attempt has been spent, and it is CLEARED by a later
     * successful cycle, so a single transient failure that the next window recovers from does not leave a
     * task permanently marked down.</p>
     */
    private final AtomicBoolean requestListenerStopped = new AtomicBoolean();

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
     * Cycles the request container so the next window starts with a fresh allowance.
     *
     * <p>Assumptions: the generation is recorded on the closing line, which is what makes a window closed
     * twice visible in an operational record rather than only in a test. Two lines carrying one generation
     * are a defect in the admission accounting; two lines carrying consecutive generations are two windows
     * doing exactly what they should.</p>
     *
     * <p>Refactoring Rationale: the reopen action is run on the cycling thread in a {@code finally}, and
     * the admission accounting used to reopen the next window itself in the same atomic step that fired
     * this call. That made one physical run able to exceed the allowance -- the next generation was already
     * open while this container was still being stopped, so every message it had already dispatched was
     * admitted into it. Reopening HERE, after the cycle, is what confines the over-admission window to
     * nothing.</p>
     *
     * <p>⚠️ Refactoring Rationale: admission is reopened only on a CONFIRMED restart, and it used to be
     * reopened in a {@code finally} whatever the cycle did. The reasoning for the {@code finally} was that
     * a cycle which threw would otherwise leave the service refusing every authorization -- true, and it
     * addressed the wrong failure. Reopening after a failed restart does not make the service work: the
     * container is stopped, so nothing is dispatched to be admitted, and the accounting reports an open
     * window over a listener that consumes nothing. What that bought was silence -- a task inert, healthy
     * by its own probe, with one log line as the only evidence. Admission now stays closed on an
     * unconfirmed restart AND the health signal goes down, so the orchestrator replaces the task instead
     * of the service pretending to serve.</p>
     *
     * <p>Assumptions: an unexpected failure escaping the cycle is treated exactly as a failed restart
     * rather than as an unknown, which is what preserves the property the {@code finally} was reaching
     * for. Nothing can now leave this method with admission closed and health up.</p>
     *
     * @param generation which window is closing, counting from zero
     * @param admittedInWindow how many requests the closing window admitted
     * @param reopenAdmission the action that opens the next window, run once the restart is confirmed
     * @throws NullPointerException if {@code reopenAdmission} is {@code null}, because admission is already
     *     closed by the time this method is entered and there would then be nothing able to reopen it
     */
    @Override
    public void onWindowComplete(long generation, int admittedInWindow, Runnable reopenAdmission) {
        Objects.requireNonNull(reopenAdmission, "reopenAdmission must not be null");
        LOG.info("event=auth.window.closed generation={} admitted={} containerId={}",
                generation, admittedInWindow, REQUEST_CONTAINER_ID);
        this.cycler.execute(() -> {
            boolean restarted;
            try {
                restarted = cycleContainer();
            } catch (RuntimeException | Error unexpected) {
                // WHY : Trade-offs: `Error` is caught alongside `RuntimeException`, which is ordinarily
                //       wrong and is right here. This runs on a private single-threaded executor, so an
                //       Error escaping would kill the only thread that can ever cycle this container and
                //       would do it silently -- leaving admission closed, health up and no further window
                //       ever completing. Catching it converts an invisible thread death into the same
                //       health-down signal every other unrecoverable outcome on this path produces.
                restarted = false;
                LOG.error("event=auth.window.cycle.failed generation={} containerId={} exception={}",
                        generation, REQUEST_CONTAINER_ID, unexpected.getClass().getName());
            }
            if (restarted) {
                reopenAdmission.run();
                return;
            }
            // WHY : Assumptions: the reopen is deliberately NOT run here, and the log says which window
            //       it was so an operator can correlate the stall with the last admitted request.
            LOG.error("event=auth.window.not-reopened generation={} containerId={}"
                    + " reason=listener-not-running", generation, REQUEST_CONTAINER_ID);
        });
    }

    /**
     * Reports this service unhealthy while its request listener is stopped and unrecoverable.
     *
     * <p>⚠️ Assumptions: the health contribution is DOWN rather than OUT_OF_SERVICE, and the distinction
     * decides whether the task is replaced. This service exists to consume authorization requests; a task
     * whose listener cannot restart performs none of its function, so it is broken rather than
     * deliberately withdrawn -- and only the down state fails the orchestrator's health check and gets the
     * task replaced by one whose listener starts.</p>
     *
     * <p>Assumptions: the detail names the container and nothing else. The failure's own message is
     * withheld for the reason recorded on the restart attempt -- a transport failure composes it from
     * endpoint and credential material, and a health endpoint is more widely readable than a log.</p>
     *
     * <p>Assumptions: the contributor interface is imported from {@code org.springframework.boot.health
     * .contributor} and not from the {@code actuate.health} package a reader may expect. The framework
     * generation this service builds against moved the health contributor API into its own module, and the
     * older package does not exist on this classpath at all -- so the import is a version fact rather than
     * a preference.</p>
     *
     * @return {@code Health.down()} naming the stopped container while the last cycle left the listener
     *     stopped, and {@code Health.up()} otherwise; never {@code null}
     */
    @Override
    public Health health() {
        if (this.requestListenerStopped.get()) {
            return Health.down()
                    .withDetail("containerId", REQUEST_CONTAINER_ID)
                    .withDetail("reason", "listener-not-running")
                    .build();
        }
        return Health.up().withDetail("containerId", REQUEST_CONTAINER_ID).build();
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
     * Stops the request container and restarts it, reporting whether the listener is running afterwards.
     *
     * <p>Assumptions: the stop and the start are guarded separately and the start is unconditional. If the
     * stop threw, nothing was closed and starting an already-running container is a no-op; if the stop
     * succeeded, the start is the only thing that reopens intake. Guarding them together would admit a
     * path that closed intake and never reopened it.</p>
     *
     * <p>⚠️ Refactoring Rationale: this returned nothing and swallowed the start failure, which is what
     * let the listener stay stopped indefinitely. A single failed {@code start()} was logged and then
     * treated by the caller exactly like a successful one, so the service continued to report itself
     * healthy while consuming no messages at all. It now RETRIES within a bound and reports the outcome,
     * because the caller's two decisions -- whether to reopen admission and whether to report health --
     * both hinge on the one fact this method is in a position to know.</p>
     *
     * @return {@code true} when the container is confirmed running after the cycle, {@code false} when it
     *     is absent from the registry, when every restart attempt failed, or when the wait between
     *     attempts was interrupted
     */
    private boolean cycleContainer() {
        MessageListenerContainer<?> container = this.registry.getContainerById(REQUEST_CONTAINER_ID);
        if (container == null) {
            LOG.error("event=auth.window.cycle.skipped reason=container-not-registered containerId={}",
                    REQUEST_CONTAINER_ID);
            // WHY : Assumptions: an absent container reports NOT running rather than reporting success and
            //       moving on, which is how it was treated before. A registry that cannot resolve the
            //       listener is a wiring fault, and a task that cannot resolve its own listener consumes
            //       nothing -- the same inert state a failed restart produces, so it earns the same signal.
            this.requestListenerStopped.set(true);
            return false;
        }
        try {
            container.stop();
        } catch (RuntimeException failure) {
            LOG.error("event=auth.window.cycle.stop-failed containerId={} exception={}",
                    REQUEST_CONTAINER_ID, failure.getClass().getName());
        }
        boolean running = restartWithinBound(container);
        // WHY : Assumptions: the flag is assigned from the outcome rather than only set on failure, so a
        //       window that recovers CLEARS a health-down left by the window before it. A flag that only
        //       ever latched would take a task out of service for a failure it had already recovered from,
        //       and an operator would find a task reporting down beside a listener demonstrably consuming.
        this.requestListenerStopped.set(!running);
        return running;
    }

    /**
     * Attempts the restart up to the bound, waiting a doubling interval between attempts.
     *
     * <p>Assumptions: each attempt is confirmed with {@link MessageListenerContainer#isRunning()} rather
     * than inferred from {@code start()} returning normally. The two are not the same claim: the container
     * starts its polling asynchronously, so a call that returns without throwing has not yet established
     * that anything is consuming -- and it is the consuming that the caller is about to reopen admission
     * for.</p>
     *
     * <p>Assumptions: an interruption ABANDONS the remaining attempts and restores the interrupt flag,
     * rather than being swallowed to continue the loop. The only thing that interrupts this thread is the
     * context shutting down, and a shutdown that is made to wait through the remaining backoff would be
     * delayed by exactly the interval this method exists to spend.</p>
     *
     * @param container the container to restart; must not be {@code null}
     * @return {@code true} as soon as an attempt leaves the container running, {@code false} when the
     *     bound is spent or the wait is interrupted
     */
    private boolean restartWithinBound(MessageListenerContainer<?> container) {
        long backoff = START_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            try {
                container.start();
                if (container.isRunning()) {
                    LOG.info("event=auth.window.opened containerId={} attempt={}",
                            REQUEST_CONTAINER_ID, attempt);
                    return true;
                }
                LOG.error("event=auth.window.cycle.start-not-running containerId={} attempt={} of={}",
                        REQUEST_CONTAINER_ID, attempt, START_ATTEMPTS);
            } catch (RuntimeException failure) {
                // WHY : Trade-offs: the failure's CLASS is logged and its message is not, on every
                //       attempt. A container that failed to start accepts no requests at all, so the
                //       record has to be loud enough to alarm on; the message is withheld because a
                //       transport failure composes it from endpoint and credential material and a log line
                //       is retained more widely than the build.
                LOG.error("event=auth.window.cycle.start-failed containerId={} attempt={} of={}"
                        + " exception={}", REQUEST_CONTAINER_ID, attempt, START_ATTEMPTS,
                        failure.getClass().getName());
            }
            if (attempt == START_ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.error("event=auth.window.cycle.restart-interrupted containerId={} attempt={}",
                        REQUEST_CONTAINER_ID, attempt);
                return false;
            }
            backoff *= 2;
        }
        LOG.error("event=auth.window.cycle.restart-exhausted containerId={} attempts={}",
                REQUEST_CONTAINER_ID, START_ATTEMPTS);
        return false;
    }
}
