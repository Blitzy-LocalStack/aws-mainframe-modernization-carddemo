package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.mapper.AuthorizationMessageMapper;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.common.money.Money;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Asserts the reopen contract of the PRODUCTION window boundary, including under concurrent deliveries.
 *
 * <p>Purpose: {@code AuthorizationRequestListenerTest} asserts the admission arithmetic against a boundary
 * supplied as a lambda, which is the right seam for arithmetic but cannot exercise the property that
 * actually failed: the boundary is asynchronous, so the interval between a window filling and intake
 * stopping is real, and what happens to admissions arriving inside it is a function of this
 * implementation's ordering rather than of the arithmetic. This class drives that implementation.</p>
 *
 * <p>Refactoring Rationale: the reopen callback exists because the admission accounting used to advance to
 * the next window at the instant the boundary was CALLED — before anything had been stopped. The container
 * therefore kept handing messages over into a window it had just been told was fresh, which both reduced
 * that window's real allowance and, with enough arrivals, let it fill and fire a second closure while the
 * container was still stopping for the first. Every case here is about the ordering that fix depends on:
 * that the reopen happens after the stop and before the start, and that it happens at all on every failure
 * path this implementation has.</p>
 *
 * <p>Assumptions: the listener container and its registry are mocked rather than stood up. Both are
 * framework types whose real implementations poll a queue, and what is under test is the ORDER in which
 * this class calls three things and whether it calls the third at all — which a mock records exactly and a
 * live container would obscure behind timing.</p>
 *
 * <p>Assumptions: no container, network endpoint or external service is used, so this class belongs under
 * Surefire as a unit test rather than under Failsafe.</p>
 *
 * <p>Refactoring Rationale: five cases here assert properties of the cycle that are not about reopen
 * ordering at all, and they are held in this class rather than in a second one because they are all
 * properties of the same object and a second class would have re-created its whole fixture. They are: that
 * the container identifier this boundary looks up is the identifier the listener is registered under, so a
 * rename cannot silently unbind every window; that the cycle leaves the admitting thread, since stopping a
 * container waits for the in-flight message that filled the window; that a start failure is logged by
 * exception CLASS and never with its message, because a messaging failure's message routinely carries an
 * endpoint or a credential fragment; that two closing windows cycle serially rather than interleaving into
 * stop, stop, start, start; and that disposal waits for a cycle caught between its stop and its
 * start.</p>
 */
class ContainerCyclingWindowBoundaryTest {

    /** A fixed instant, so an expired request is expired for a reason the clock cannot change. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /** The reply destination the listener is configured to allow. */
    private static final String ALLOWED_REPLY_QUEUE =
            "https://sqs.us-east-1.amazonaws.com/000000000000/carddemo-pauth-reply-dev.fifo";

    /** The declared per-window limit used here, kept small so a window fills in a few messages. */
    private static final int WINDOW_LIMIT = 3;

    /** What one window actually admits: the declared limit plus the reference program's off-by-one. */
    private static final int WINDOW_ADMISSIONS =
            WINDOW_LIMIT + AuthorizationRequestListener.BASELINE_COMPARISON_OFFSET;

    /** The bean-validation engine the payload mapper validates through. */
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * The transaction identifier every fabricated request carries.
     *
     * <p>Assumptions: it is exactly fifteen characters, because the delimited request layout declares
     * {@code PA-TRANSACTION-ID PIC X(15)} and the codec refuses anything wider. A sixteen-character value
     * fails the encode rather than the assertion, which in a threaded case presents as a worker that
     * delivered nothing.</p>
     */
    private static final String TRANSACTION_ID = "TXN000000000001";

    /** How long a case waits on the cycler thread before reporting that the boundary never arrived. */
    private static final long WAIT_SECONDS = 10L;

    /**
     * The name the production executor gives its single thread.
     *
     * <p>Assumptions: the literal is restated here rather than read from the class under test, because a
     * name taken from the implementation would agree with whatever that name became. The two cases that
     * assert on it are about the cycle NOT running on the admitting thread, so an independent statement
     * of the value is what makes those assertions mean anything.</p>
     */
    private static final String BOUNDARY_THREAD_NAME = "pauth-window-boundary";

    /** An arbitrary non-zero generation, used by cases where the window ordinal is not the subject. */
    private static final long GENERATION = 7L;

    /** The reference's own per-window allowance, used by cases where the count is not the subject. */
    private static final int ADMITTED = 500;

    /** Records what the boundary did, in order, so the ordering can be asserted rather than inferred. */
    private List<String> steps;

    /** The registry the boundary resolves its container through. */
    private MessageListenerContainerRegistry registry;

    /** The container the boundary stops and starts. */
    private MessageListenerContainer<?> container;

    /** The boundary under test. */
    private ContainerCyclingWindowBoundary boundary;

    /**
     * Builds a boundary over a mocked registry whose container records its stop and start.
     */
    @BeforeEach
    void setUp() {
        this.steps = Collections.synchronizedList(new ArrayList<>());
        this.registry = mock(MessageListenerContainerRegistry.class);
        this.container = mock(MessageListenerContainer.class);
        when(this.registry.getContainerById(ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID))
                .thenAnswer(invocation -> this.container);
        doAnswer(invocation -> {
            this.steps.add("stop");
            return null;
        }).when(this.container).stop();
        doAnswer(invocation -> {
            this.steps.add("start");
            return null;
        }).when(this.container).start();
        this.boundary = new ContainerCyclingWindowBoundary(this.registry);
    }

    /**
     * Releases the boundary's cycler thread, which also waits for any cycle still running.
     *
     * @throws InterruptedException if the wait for an in-progress cycle is interrupted
     */
    @AfterEach
    void tearDown() throws InterruptedException {
        this.boundary.destroy();
    }

    /**
     * The reopen happens after the cycle and EXACTLY ONCE, which is what the accounting depends on.
     *
     * <p>Purpose: the original defect was that the admission accounting advanced to the next window at the
     * instant the boundary was CALLED, before anything had been stopped, so the container kept handing
     * messages into a window it had just been told was fresh. Every ordering asserted here is about that
     * defect being gone: the reopen follows the stop, so no admission is charged to a window that has not
     * begun, and it happens once.</p>
     *
     * <p>⚠️ Refactoring Rationale: this case previously asserted the reopen landed BETWEEN the stop and the
     * start, and it now asserts it follows both. The landed implementation runs the callback once, in a
     * finally block around the whole cycle, and the placement is not a detail that can be moved without
     * cost. {@code AuthorizationRequestListener.openNextWindow} increments the generation and resets the
     * granted count UNCONDITIONALLY, so a callback run twice skips a window and resets its allowance twice
     * -- which is exactly what an implementation that reopens between the pair AND keeps a finally-block
     * safety net does on its success path. Exactly-once is therefore the stronger property, and the cost
     * of taking it is bounded and visible: a message the container delivers between the start and the
     * reopen is refused with {@code WindowClosedException} and redelivered, rather than admitted. Nothing
     * is lost, one receive is spent, and the case below pins that behaviour rather than leaving it
     * undescribed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the wait for the cycle is interrupted
     */
    @Test
    @DisplayName("the reopen follows the stop, follows the start, and happens exactly once")
    void theReopenLandsAfterTheCycleExactlyOnce() throws InterruptedException {
        CountDownLatch reopened = new CountDownLatch(1);

        this.boundary.onWindowComplete(0L, WINDOW_ADMISSIONS, () -> {
            this.steps.add("reopen");
            reopened.countDown();
        });
        assertThat(reopened.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the cycle must run and reopen intake")
                .isTrue();
        this.boundary.destroy();

        assertThat(this.steps)
                .as("the accounting may not advance until the container has been cycled, or the window it"
                        + " advances into is charged for messages the previous window admitted")
                .containsExactly("stop", "start", "reopen");
        assertThat(this.steps.stream().filter("reopen"::equals).count())
                .as("openNextWindow increments the generation unconditionally, so a second run would skip"
                        + " a window and reset its allowance twice")
                .isOne();
    }

    /**
     * A stop that throws still reopens, because nothing was closed and intake is open in fact.
     *
     * <p>Assumptions: this is the case the interface's own contract names — an implementation that cannot
     * close the window must leave intake OPEN and report the failure. Leaving the accounting closed instead
     * would charge every later admission as overspill of a window that had ended, and the bound would never
     * be re-armed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the wait for the cycle is interrupted
     */
    @Test
    @DisplayName("a stop that throws still reopens intake, and still attempts the start")
    void aFailedStopStillReopens() throws InterruptedException {
        doThrow(new IllegalStateException("stop refused")).when(this.container).stop();
        CountDownLatch reopened = new CountDownLatch(1);

        this.boundary.onWindowComplete(0L, WINDOW_ADMISSIONS, reopened::countDown);
        assertThat(reopened.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        this.boundary.destroy();

        verify(this.container).start();
    }

    /**
     * A start that throws still reopens, so the accounting is not left waiting on a container that is down.
     *
     * <p>Assumptions: a container that failed to start accepts no requests, so nothing will be admitted
     * either way. The reopen still matters, because a later manual or automatic recovery must find the
     * accounting armed rather than stuck reporting overspill of a window that closed long before.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the wait for the cycle is interrupted
     */
    @Test
    @DisplayName("a start that throws still reopens intake")
    void aFailedStartStillReopens() throws InterruptedException {
        doThrow(new IllegalStateException("start refused")).when(this.container).start();
        CountDownLatch reopened = new CountDownLatch(1);

        this.boundary.onWindowComplete(0L, WINDOW_ADMISSIONS, reopened::countDown);

        assertThat(reopened.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    /**
     * An unregistered container still reopens, rather than leaving the accounting closed forever.
     *
     * <p>Assumptions: this path RETURNS early, which is exactly why the reopen is guaranteed by a
     * {@code finally} guard rather than by a statement on the normal path. A return that skipped the reopen
     * would be the one failure mode with no recovery: nothing would ever reopen the window, because nothing
     * else calls the callback.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the wait for the cycle is interrupted
     */
    @Test
    @DisplayName("an unregistered container still reopens intake")
    void anUnregisteredContainerStillReopens() throws InterruptedException {
        when(this.registry.getContainerById(ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID))
                .thenReturn(null);
        CountDownLatch reopened = new CountDownLatch(1);

        List<String> lines = capturingBoundaryLog(() -> {
            this.boundary.onWindowComplete(0L, WINDOW_ADMISSIONS, reopened::countDown);
            assertThat(reopened.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("a window nothing can reopen halts every authorization in the system")
                    .isTrue();
            // WHY : Assumptions: the executor is DRAINED before the captured lines are read. The latch
            //       above is released from the reopen callback, which the implementation runs in a
            //       finally block AFTER the skip line is written, so the drain is belt and braces here
            //       rather than load-bearing -- but the start-failure case below genuinely needs it, so
            //       both cases read the log the same way.
            this.boundary.destroy();
        });

        verify(this.container, never()).stop();
        verify(this.container, never()).start();
        assertThat(lines)
                .as("a window that closed nothing must say so, or it is indistinguishable from one that"
                        + " did")
                .anyMatch(line -> line.contains("event=auth.window.cycle.skipped")
                        && line.contains("reason=container-not-registered"));
    }

    /**
     * An absent callback is refused on the calling thread, not discovered as a window that never reopened.
     *
     * <p>Assumptions: the check is on the caller's thread rather than inside the cycle, because a
     * {@code NullPointerException} raised on the single cycler thread would be reported against that
     * thread's uncaught handler and would present as intake never reopening rather than as a programming
     * error at this call.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent reopen callback is refused at the call, not on the cycler thread")
    void anAbsentCallbackIsRefusedAtTheCall() {
        assertThatNullPointerException()
                .isThrownBy(() -> this.boundary.onWindowComplete(0L, WINDOW_ADMISSIONS, null));
    }

    /**
     * Driven by the real asynchronous boundary and several delivering threads, no window closes twice.
     *
     * <p>Purpose: this exercises the real listener, the real boundary and a real second thread together,
     * with the container's stop made deliberately slow so the interval between a window filling and intake
     * being reopened is wide and four threads keep admitting inside it. What it establishes is that the two
     * halves compose: nothing deadlocks, every message is delivered, every closure reports the full
     * allowance, and the generations are a consecutive run with no repetition, all while a cycle is in
     * progress.</p>
     *
     * <p>Assumptions: this case does NOT detect the eager-generation-advance defect, and saying so is worth
     * more than implying otherwise. The signature of that defect is a window closing while the previous one
     * is still closing, and it is asserted deterministically in
     * {@code AuthorizationRequestListenerTest.aClosedWindowDoesNotCloseAgainUntilIntakeReopens}, which
     * drives three allowances through a boundary that never reopens and requires exactly one closure. An
     * attempt to detect it here through a "is a cycle in progress" flag was withdrawn because it cannot
     * distinguish the two states it needs to: the reopen deliberately lands BETWEEN the stop and the start,
     * so the next window filling while the cycle is still running is correct behaviour rather than the
     * defect, and the flag reported it as the defect.</p>
     *
     * <p>Assumptions: the assertion is a conservation law rather than an exact closure count, for the same
     * reason it is in the arithmetic case: overspill is no longer credited forward, so the number of
     * closures depends on how much overspill the interval produced. What must hold exactly is that every
     * closure reports the full allowance and that no generation repeats, and both are asserted.</p>
     *
     * <p>Assumptions: the container is never actually started or stopped in any meaningful sense — the
     * threads here stand in for the container's delivery threads and keep running through the cycle, which
     * is the WORST case rather than the realistic one. A real container stops delivering once its stop
     * completes; modelling it as never stopping means the property is asserted against more overspill than
     * production can produce, not less.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the wait for the delivering threads is interrupted
     */
    @Test
    @DisplayName("the real async boundary with concurrent deliveries closes each window exactly once")
    void theRealBoundaryUnderConcurrentDeliveriesClosesEachWindowOnce() throws InterruptedException {
        // WHY : Assumptions: the stop is slowed on purpose. The defect only appears while a cycle is in
        //   progress, so a cycle that completed instantly would leave the interval this case is about
        //   unexercised. Ten milliseconds is long enough for several admissions on four threads and short
        //   enough that the case stays well inside a test run.
        doAnswer(invocation -> {
            Thread.sleep(10L);
            return null;
        }).when(this.container).stop();

        List<Long> generations = Collections.synchronizedList(new ArrayList<>());
        List<Integer> admitted = Collections.synchronizedList(new ArrayList<>());
        List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger delivered = new AtomicInteger();

        AuthorizationRequestListener listener = new AuthorizationRequestListener(
                mock(PendingAuthSummaryRepository.class), mock(PendingAuthDetailRepository.class),
                mock(OutboxRepository.class), new AuthorizationDecisionService(),
                new AuthorizationMessageMapper(VALIDATOR), mock(AccountContextClient.class),
                List.of(ALLOWED_REPLY_QUEUE), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), WINDOW_LIMIT,
                (generation, admittedInWindow, intakeReopened) -> {
                    generations.add(generation);
                    admitted.add(admittedInWindow);
                    this.boundary.onWindowComplete(generation, admittedInWindow, intakeReopened);
                });

        int threads = 4;
        int perThread = 40;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int worker = 0; worker < threads; worker++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int message = 0; message < perThread; message++) {
                    try {
                        listener.onRequest(expiredMessage());
                        delivered.incrementAndGet();
                    } catch (RuntimeException refused) {
                        // WHY : Assumptions: a refusal is COLLECTED and the worker CONTINUES, where it
                        //   previously abandoned its remaining messages. An uncaught throw would stop that
                        //   worker delivering and the case would fail on a count that looked like an
                        //   accounting defect; returning early would do the same thing more quietly, and
                        //   a deferral is a normal outcome here rather than a reason to stop. The type is
                        //   asserted after the join, so a fabrication error is still named for what it is.
                        failures.add(refused);
                    }
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : workers) {
            thread.join();
        }
        this.boundary.destroy();

        // WHY : ⚠️ Refactoring Rationale: a delivery refused with WindowClosedException is EXPECTED here
        //       and is asserted as such, where this case previously required the collected failures to be
        //       empty. The landed listener refuses a request that arrives while a window is closing
        //       instead of returning quietly, so the container leaves the message on the queue and
        //       redelivers it. Requiring no failure at all would only pass against an implementation that
        //       swallowed those requests -- and a swallowed request is ACKNOWLEDGED, which loses an
        //       authorization rather than deferring it. Any OTHER exception type still fails the case, so
        //       a fabrication error in the message below is still reported as one.
        assertThat(failures)
                .as("only the documented deferral may be raised here; anything else means the fabricated"
                        + " message is wrong rather than that the accounting is")
                .allMatch(AuthorizationRequestListener.WindowClosedException.class::isInstance);
        assertThat(admitted)
                .as("a window may only close on its FULL allowance, whatever thread took the last place")
                .isNotEmpty()
                .containsOnly(WINDOW_ADMISSIONS);
        assertThat(generations)
                .as("no window may close twice; this is the property the closing state exists to hold"
                        + " while the container is still stopping")
                .doesNotHaveDuplicates();
        assertThat(new TreeSet<>(generations))
                .as("the generations must be a consecutive run starting at zero")
                .containsExactlyElementsOf(
                        LongStream.range(0, generations.size()).boxed().toList());
        assertThat((long) generations.size() * WINDOW_ADMISSIONS)
                .as("closures may never account for more admissions than were delivered")
                .isLessThanOrEqualTo(delivered.get());
        assertThat(delivered.get() + failures.size())
                .as("every message must be either admitted or deferred, and none may be lost: the closing"
                        + " state changes WHEN a request is handled, never whether it is")
                .isEqualTo(threads * perThread);
    }

    /**
     * Fails when the identifier this boundary cycles is not the one the listener is registered under.
     *
     * <p>Assumptions: the two names are declared in different files and nothing but this case makes them
     * agree. A registry lookup that misses returns nothing, and the implementation then logs a skip and
     * returns -- so a renamed listener would not fail anywhere, it would quietly stop bounding every
     * window in the system.</p>
     *
     * @throws NoSuchMethodException if the listener no longer declares the annotated handler method
     */
    @Test
    @DisplayName("the boundary resolves the same container identifier the listener is registered under")
    void theResolvedIdentifierIsTheListenersRegisteredIdentifier() throws NoSuchMethodException {
        Method handler = AuthorizationRequestListener.class.getMethod("onRequest", Message.class);

        SqsListener declared = handler.getAnnotation(SqsListener.class);

        assertThat(declared)
                .as("the request handler must still be the annotated listener this boundary cycles")
                .isNotNull();
        assertThat(declared.id())
                .as("a registry lookup that misses returns nothing and silently loses the window bound")
                .isEqualTo(ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID);
        assertThat(ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID)
                .as("the identifier is the deployed queue's listener name")
                .isEqualTo("carddemo-pauth-request-listener");
    }

    /**
     * Fails when the cycle runs on the thread that admitted the last message of the window.
     *
     * <p>Assumptions: stopping a listener container waits for its in-flight messages, and the message
     * that filled the window IS one of those. Cycling inline would therefore have the last admission
     * wait for itself, which is a deadlock rather than a slow path, so the property under test is that
     * the work leaves the admitting thread entirely.</p>
     *
     * @throws InterruptedException if the wait for the cycler thread is interrupted
     */
    @Test
    @DisplayName("the cycle runs on the boundary's own named thread, not on the admitting thread")
    void theCycleLeavesTheAdmittingThread() throws InterruptedException {
        List<String> cyclingThreads = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(call -> {
            cyclingThreads.add(Thread.currentThread().getName());
            return null;
        }).when(this.container).stop();
        doAnswer(call -> {
            started.countDown();
            return null;
        }).when(this.container).start();
        String admittingThread = Thread.currentThread().getName();

        this.boundary.onWindowComplete(GENERATION, ADMITTED, () -> {
        });

        awaitCycle(started);
        assertThat(cyclingThreads)
                .as("stopping the container inline would wait for the message that triggered it")
                .containsExactly(BOUNDARY_THREAD_NAME)
                .doesNotContain(admittingThread);
    }

    /**
     * Fails when a start failure escapes to the caller, goes unlogged, or carries its own message.
     *
     * <p>Assumptions: a container that will not restart accepts no request at all, so the event has to be
     * loud enough to alarm on -- and it has to name the failure by CLASS rather than by message, because
     * a log line is retained more widely than a build and the message of a messaging failure routinely
     * carries an endpoint or a credential fragment.</p>
     *
     * @throws InterruptedException if the wait for the cycler thread is interrupted
     */
    @Test
    @DisplayName("a failing start is logged by class without its message and never escapes")
    void aFailingStartIsRecordedWithoutItsMessage() throws InterruptedException {
        String confidentialDetail = "endpoint=https://sqs.invalid credential=AKIAEXAMPLE";
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(call -> {
            attempted.countDown();
            throw new IllegalStateException(confidentialDetail);
        }).when(this.container).start();

        List<String> lines = capturingBoundaryLog(() -> {
            assertThatCode(() -> this.boundary.onWindowComplete(GENERATION, ADMITTED, () -> {
            }))
                    .as("a start failure must not surface on the admitting thread")
                    .doesNotThrowAnyException();
            awaitCycle(attempted);
            // WHY : Assumptions: the executor is DRAINED before the captured lines are read, because the
            //       latch above is released from inside the stubbed start and the line under assertion is
            //       written by the catch block that follows it. Reading straight after the latch is a race
            //       the boundary thread loses about as often as it wins. Disposal shuts the executor down
            //       and waits for the running task, so it is the one point at which the cycle is known to
            //       have finished.
            // WHY : Alternatives Considered: sleeping for a few milliseconds, and polling the captured
            //       list until the line appeared. Both rejected for the same reason: each turns a missing
            //       line into a slow test that then fails, where draining turns it into an immediate
            //       failure that names the missing line.
            this.boundary.destroy();
        });

        assertThat(lines)
                .as("a container that accepts no requests at all has to be loud enough to alarm on")
                .anyMatch(line -> line.contains("event=auth.window.cycle.start-failed")
                        && line.contains(IllegalStateException.class.getName()));
        assertThat(lines)
                .as("a log line is retained more widely than a build, so the message is withheld")
                .noneMatch(line -> line.contains(confidentialDetail));
    }

    /**
     * Fails when two closing windows interleave their stop and start instead of cycling one at a time.
     *
     * <p>Assumptions: the single-thread executor is what makes the pair serial. An overlapping pair could
     * otherwise run as stop, stop, start, start and leave the container stopped after the second start
     * had already been issued against a container the first stop was still closing.</p>
     *
     * @throws InterruptedException if the wait for the two cycles is interrupted
     */
    @Test
    @DisplayName("two closing windows cycle the container one after the other on a single thread")
    void twoClosingWindowsCycleSerially() throws InterruptedException {
        List<String> order = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch bothStarted = new CountDownLatch(2);
        doAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            order.add("stop");
            return null;
        }).when(this.container).stop();
        doAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            order.add("start");
            bothStarted.countDown();
            return null;
        }).when(this.container).start();

        this.boundary.onWindowComplete(GENERATION, ADMITTED, () -> {
        });
        this.boundary.onWindowComplete(GENERATION + 1L, ADMITTED, () -> {
        });

        awaitCycle(bothStarted);
        assertThat(order)
                .as("an overlapping pair could interleave as stop, stop, start, start and end stopped")
                .containsExactly("stop", "start", "stop", "start");
        assertThat(threads)
                .as("one executor thread is what makes the pair serial rather than merely usually serial")
                .containsOnly(BOUNDARY_THREAD_NAME);
    }

    /**
     * Fails when disposal returns while a cycle is between its stop and its start.
     *
     * <p>Assumptions: shutdown that did not wait would leave the container stopped for good, because the
     * start that reopens it is the second half of a task the executor had already accepted. The stop is
     * held open by a latch so the disposal happens inside exactly that interval.</p>
     *
     * @throws InterruptedException if the wait for the held stop or the disposal is interrupted
     */
    @Test
    @DisplayName("disposal waits for an in-progress cycle to finish reopening intake")
    void disposalWaitsForAnInProgressCycle() throws InterruptedException {
        CountDownLatch reachedStop = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        AtomicBoolean reopened = new AtomicBoolean();
        doAnswer(call -> {
            reachedStop.countDown();
            releaseStop.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }).when(this.container).stop();
        doAnswer(call -> {
            reopened.set(true);
            return null;
        }).when(this.container).start();

        this.boundary.onWindowComplete(GENERATION, ADMITTED, () -> {
        });

        awaitCycle(reachedStop);
        releaseStop.countDown();
        this.boundary.destroy();
        assertThat(reopened)
                .as("a disposal that did not wait could leave the container stopped between the pair")
                .isTrue();
        verify(this.container).start();
    }

    /**
     * Waits for a latch the container stubs release, failing with the elapsed bound rather than hanging.
     *
     * @param latch the latch a stubbed container call counts down; must not be {@code null}
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private static void awaitCycle(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the boundary did not reach the container within %d seconds", WAIT_SECONDS)
                .isTrue();
    }

    /**
     * Runs a body with the boundary's own logger captured, and returns the formatted lines it wrote.
     *
     * <p>Alternatives Considered: asserting on standard output, and injecting a logger. The first cannot
     * tell this class's lines from any other's; the second would add a constructor parameter that exists
     * only for a test. Attaching an appender to the one named logger reads exactly what the deployed
     * class writes, and the level is restored afterwards so no other case inherits it.</p>
     *
     * @param body the work to run while the appender is attached; must not be {@code null}
     * @return the formatted messages captured during the body, in order; never {@code null}
     * @throws InterruptedException if the body is interrupted
     */
    private static List<String> capturingBoundaryLog(InterruptibleBody body) throws InterruptedException {
        Logger boundaryLogger = (Logger) LoggerFactory.getLogger(ContainerCyclingWindowBoundary.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        boundaryLogger.addAppender(captured);
        Level previousLevel = boundaryLogger.getLevel();
        boundaryLogger.setLevel(Level.INFO);
        try {
            body.run();
            return new ArrayList<>(captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            boundaryLogger.setLevel(previousLevel);
            boundaryLogger.detachAppender(captured);
        }
    }

    /** A body that may be interrupted, so a capturing helper can wrap latch waits and disposal. */
    @FunctionalInterface
    private interface InterruptibleBody {

        /**
         * Runs the body.
         *
         * @throws InterruptedException if the body is interrupted while waiting
         */
        void run() throws InterruptedException;
    }

    /**
     * Builds an already-expired request message, so admission is exercised without account stubbing.
     *
     * @return a message the listener admits and then drops as stale, never {@code null}
     */
    private static Message<String> expiredMessage() {
        AuthRequest request = new AuthRequest("250801", "104530", "4111111111111111", "0100", "1230",
                "0100", "POS001", "000000", Money.of("100.99"), "5411", "840", "05",
                "MERCHANT0000001", "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000",
                TRANSACTION_ID);
        return MessageBuilder.withPayload(CsvAuthCodec.encodeRequest(request))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_CONTENT_TYPE,
                        AuthorizationRequestListener.CONTENT_TYPE_CSV)
                .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT,
                        String.valueOf(FIXED_INSTANT.minusSeconds(1).toEpochMilli()))
                .build();
    }
}
