package com.carddemo.common.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.SsmException;

/**
 * Asserts the online-write gate's decision, and above all that it fails closed.
 *
 * <p>Purpose. Two properties of the gate carry the whole of its value and neither is visible from its
 * signature: an unreadable flag REFUSES the write rather than admitting it, and the flag's value is
 * compared POSITIVELY so that an empty or mistyped value also refuses. Both are asserted here against
 * a stub that can be made to return anything or to throw anything.</p>
 *
 * <p>Assumptions: hand-written stubs rather than a mocking framework, matching every other test in
 * this module. The stub also records the calls it received, which is what lets the caching cases
 * assert the NUMBER of reads rather than only their answers -- the property under test there is
 * whether the flag is read again, and an answer alone cannot distinguish that.</p>
 */
@DisplayName("The online-write gate refuses unless the window is demonstrably open")
class OnlineWriteGateTest {

    /** Parameter name the stub answers for; a real one is an environment's own path. */
    private static final String PARAMETER = "/carddemo/dev/batch/online-writes-enabled";

    /** Cache period used throughout, chosen so that a clock step of six seconds crosses it. */
    private static final Duration CACHE = Duration.ofSeconds(5);

    /** Fixed instant the stepping clock starts from. */
    private static final Instant START = Instant.parse("2022-07-18T22:00:00Z");

    /**
     * A Systems Manager client that answers one parameter from a supplier and records every call.
     *
     * <p>Assumptions: only {@code getParameter} is overridden. The SDK gives every operation on a
     * service interface a default implementation that throws, so a stub implements just the operation
     * under test plus the two members of the client contract itself. That is worth stating because the
     * alternative reading -- that the interface is small -- is wrong.</p>
     */
    private static final class RecordingSsmClient implements SsmClient {

        /** Supplies the response, or throws, on each read. */
        private final Supplier<GetParameterResponse> answer;

        /** Names requested, in order, so a caller can count and inspect the reads. */
        private final List<String> requested = new ArrayList<>();

        /**
         * Creates the stub.
         *
         * @param answer invoked on each read to produce the response or raise a failure
         */
        RecordingSsmClient(Supplier<GetParameterResponse> answer) {
            this.answer = answer;
        }

        /**
         * Records the requested name and returns whatever the supplier produces.
         *
         * @param request the read the gate issued
         * @return the stubbed response
         */
        @Override
        public GetParameterResponse getParameter(GetParameterRequest request) {
            this.requested.add(request.name());
            return this.answer.get();
        }

        /**
         * Names the service, as the client contract requires.
         *
         * @return the Systems Manager service name
         */
        @Override
        public String serviceName() {
            return SsmClient.SERVICE_NAME;
        }

        /**
         * Releases nothing.
         *
         * <p>Assumptions: the stub holds no connection. The method exists only because the client
         * contract declares it, and a body that threw would fail any context that closed the bean.</p>
         */
        @Override
        public void close() {
            // Assumptions: nothing to release; see the Javadoc above.
        }

        /**
         * Reports how many reads the gate has issued.
         *
         * @return the number of calls received
         */
        int reads() {
            return this.requested.size();
        }
    }

    /**
     * One gate and the stub behind it.
     *
     * @param gate the gate under test
     * @param ssm the stub, retained so a test can count reads
     */
    private record Fixture(OnlineWriteGate gate, RecordingSsmClient ssm) {}

    /**
     * A clock whose reading a test advances explicitly.
     *
     * <p>Trade-offs: a stepping clock rather than a sleep. Sleeping would make the suite slower and
     * the boundary case flaky on a loaded runner, and the property under test -- that a decision stops
     * being reused at a particular instant -- is stated more directly by stepping past that instant
     * than by waiting near it.</p>
     */
    private static final class SteppingClock extends Clock {

        /** The current reading. */
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        /**
         * Reads the current instant.
         *
         * @return the reading, which changes only when {@link #advance(Duration)} is called
         */
        @Override
        public Instant instant() {
            return this.now.get();
        }

        /**
         * Reports the zone this clock reads in.
         *
         * @return coordinated universal time
         */
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Returns this clock unchanged, because the tests never rebase its zone.
         *
         * @param zone ignored
         * @return this clock
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * Moves the reading forward.
         *
         * @param amount how far to advance; must not be {@code null}
         */
        void advance(Duration amount) {
            this.now.updateAndGet(current -> current.plus(amount));
        }
    }

    /**
     * Builds a gate whose stub returns the given parameter value.
     *
     * @param value the value the flag carries, or {@code null} for a response carrying no parameter
     * @param clock the clock the gate measures its cache against
     * @return the gate and its recording stub, never {@code null}
     */
    private static Fixture gateReturning(String value, Clock clock) {
        RecordingSsmClient ssm = new RecordingSsmClient(() -> value == null
                ? GetParameterResponse.builder().build()
                : GetParameterResponse.builder()
                        .parameter(Parameter.builder().name(PARAMETER).value(value).build())
                        .build());
        return new Fixture(new OnlineWriteGate(ssm, PARAMETER, CACHE, clock), ssm);
    }

    /**
     * Builds a gate whose stub raises the given failure on every read.
     *
     * @param failure the failure every read raises
     * @return the gate and its recording stub, never {@code null}
     */
    private static Fixture gateThrowing(RuntimeException failure) {
        RecordingSsmClient ssm = new RecordingSsmClient(() -> {
            throw failure;
        });
        return new Fixture(new OnlineWriteGate(ssm, PARAMETER, CACHE, Clock.systemUTC()), ssm);
    }

    /** Cases covering how the flag's value is interpreted. */
    @Nested
    @DisplayName("the flag's value")
    class FlagValue {

        /** The enabled value opens the window and lets a write proceed. */
        @Test
        @DisplayName("the enabled value opens the window")
        void enabledValueOpensTheWindow() {
            Fixture fixture = gateReturning("true", Clock.systemUTC());
            assertThat(fixture.gate().writesEnabled()).isTrue();
            assertThatCode(() -> fixture.gate().requireWritesEnabled()).doesNotThrowAnyException();
        }

        /**
         * Case and surrounding blanks do not change the answer.
         *
         * <p>Assumptions: an operator setting the flag by hand is the expected way it is set, so the
         * comparison tolerates the two variations that act of typing produces. It tolerates nothing
         * beyond them, which the unexpected-value case asserts.</p>
         */
        @Test
        @DisplayName("the enabled value is matched ignoring case and surrounding blanks")
        void enabledValueIsMatchedLoosely() {
            assertThat(gateReturning("  TRUE  ", Clock.systemUTC()).gate().writesEnabled()).isTrue();
            assertThat(gateReturning("True", Clock.systemUTC()).gate().writesEnabled()).isTrue();
        }

        /** The disabled value closes the window and the refusal explains itself. */
        @Test
        @DisplayName("the disabled value closes the window")
        void disabledValueClosesTheWindow() {
            Fixture fixture = gateReturning("false", Clock.systemUTC());
            assertThat(fixture.gate().writesEnabled()).isFalse();
            assertThatThrownBy(() -> fixture.gate().requireWritesEnabled())
                    .isInstanceOf(OnlineWritesDisabledException.class)
                    .hasMessageContaining("closed")
                    .hasMessageContaining("retry once the window reopens");
        }

        /**
         * Anything other than the enabled value closes the window.
         *
         * <p>Assumptions: this is the case a negative comparison against a "false" literal would get
         * wrong, and it is why the comparison is positive. Every value below is one a real mistake
         * produces -- an empty parameter, a blank one, the opposite word, a typed convention, a
         * truncation -- and each must read as closed rather than as permission to write.</p>
         */
        @Test
        @DisplayName("any unexpected value closes the window rather than opening it")
        void unexpectedValuesCloseTheWindow() {
            List<String> values = List.of("", "   ", "FALSE", "no", "0", "1", "yes", "tru", "on");
            for (String value : values) {
                assertThat(gateReturning(value, Clock.systemUTC()).gate().writesEnabled())
                        .as("value '%s' must not be read as permission to write", value)
                        .isFalse();
            }
        }

        /** A response with no parameter at all closes the window. */
        @Test
        @DisplayName("a response carrying no parameter closes the window")
        void absentParameterClosesTheWindow() {
            assertThat(gateReturning(null, Clock.systemUTC()).gate().writesEnabled()).isFalse();
        }
    }

    /** Cases covering what happens when the flag cannot be read. */
    @Nested
    @DisplayName("the fail-closed contract")
    class FailClosed {

        /**
         * Every failure shape the read can raise refuses the write.
         *
         * <p>Assumptions: four unrelated types are exercised because they are the four an operator
         * actually meets and they share no useful supertype below {@link RuntimeException} -- a
         * parameter that does not exist, a service refusal such as an access denial, a client-side
         * failure such as a timeout, and an outright programming error. The gate must treat all four
         * identically, because to it they all mean that the window's state is unknown.</p>
         */
        @Test
        @DisplayName("an unreadable flag refuses the write instead of admitting it")
        void unreadableFlagRefusesTheWrite() {
            List<RuntimeException> failures = List.of(
                    ParameterNotFoundException.builder().message("no such parameter").build(),
                    SsmException.builder().message("access denied").build(),
                    new IllegalStateException("connection timed out"),
                    new NullPointerException("a defect, not a dependency failure"));

            for (RuntimeException failure : failures) {
                Fixture fixture = gateThrowing(failure);
                assertThat(fixture.gate().writesEnabled())
                        .as("%s must leave the window closed", failure.getClass().getSimpleName())
                        .isFalse();
                assertThatThrownBy(() -> fixture.gate().requireWritesEnabled())
                        .as("%s must refuse the write", failure.getClass().getSimpleName())
                        .isInstanceOf(OnlineWritesDisabledException.class);
            }
        }

        /** The gate reads exactly the parameter it was constructed with. */
        @Test
        @DisplayName("the parameter the gate was given is the parameter it reads")
        void readsTheConfiguredParameter() {
            Fixture fixture = gateReturning("true", Clock.systemUTC());
            fixture.gate().writesEnabled();
            assertThat(fixture.ssm().requested).containsExactly(PARAMETER);
        }
    }

    /** Cases covering the short-lived cache in front of the read. */
    @Nested
    @DisplayName("the cache in front of the read")
    class Caching {

        /** A decision inside the period is reused without a second read. */
        @Test
        @DisplayName("a decision inside the period is reused without a second read")
        void decisionIsReusedInsideThePeriod() {
            SteppingClock clock = new SteppingClock();
            Fixture fixture = gateReturning("true", clock);

            assertThat(fixture.gate().writesEnabled()).isTrue();
            clock.advance(Duration.ofSeconds(4));
            assertThat(fixture.gate().writesEnabled()).isTrue();

            assertThat(fixture.ssm().reads())
                    .as("a second call inside the cache period must not read the flag again")
                    .isEqualTo(1);
        }

        /**
         * A decision past the period is read again.
         *
         * <p>Assumptions: without this the cache would be permanent, and a quiesce would never take
         * effect in a task that had already served one write.</p>
         */
        @Test
        @DisplayName("a decision past the period is read again")
        void decisionIsReReadPastThePeriod() {
            SteppingClock clock = new SteppingClock();
            Fixture fixture = gateReturning("true", clock);

            assertThat(fixture.gate().writesEnabled()).isTrue();
            clock.advance(Duration.ofSeconds(6));
            assertThat(fixture.gate().writesEnabled()).isTrue();

            assertThat(fixture.ssm().reads())
                    .as("a call past the cache period must read the flag again")
                    .isEqualTo(2);
        }

        /**
         * A refusal is cached no longer than a permission.
         *
         * <p>Assumptions: asserted explicitly because caching a refusal for longer is the easy mistake
         * to make. It looks safe and is not: a service would keep refusing writes after the window
         * reopened, turning a scheduled bracket into an outage of unbounded length.</p>
         */
        @Test
        @DisplayName("a cached refusal expires on the same period as a cached permission")
        void refusalExpiresOnTheSamePeriod() {
            SteppingClock clock = new SteppingClock();
            AtomicReference<String> flag = new AtomicReference<>("false");
            RecordingSsmClient ssm = new RecordingSsmClient(() -> GetParameterResponse.builder()
                    .parameter(Parameter.builder().name(PARAMETER).value(flag.get()).build())
                    .build());
            OnlineWriteGate gate = new OnlineWriteGate(ssm, PARAMETER, CACHE, clock);

            assertThat(gate.writesEnabled()).isFalse();
            flag.set("true");
            clock.advance(Duration.ofSeconds(4));
            assertThat(gate.writesEnabled())
                    .as("still inside the period, so the refusal is still reused")
                    .isFalse();
            clock.advance(Duration.ofSeconds(2));
            assertThat(gate.writesEnabled())
                    .as("past the period, so the reopened window is observed")
                    .isTrue();
        }
    }

    /** Cases covering the constructor's guards. */
    @Nested
    @DisplayName("construction")
    class Construction {

        /** Every collaborator is required, so a missing one fails at assembly. */
        @Test
        @DisplayName("every collaborator is required")
        void collaboratorsAreRequired() {
            Clock clock = Clock.systemUTC();
            SsmClient ssm = new RecordingSsmClient(() -> GetParameterResponse.builder().build());

            assertThatThrownBy(() -> new OnlineWriteGate(null, PARAMETER, CACHE, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new OnlineWriteGate(ssm, null, CACHE, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new OnlineWriteGate(ssm, PARAMETER, null, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new OnlineWriteGate(ssm, PARAMETER, CACHE, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * A blank parameter name is refused at construction rather than at the first write.
         *
         * <p>Assumptions: this is the one input whose acceptance would be silently catastrophic. A
         * blank name cannot be read, and because the gate fails closed it would then refuse every
         * mutating request in the service -- a total write outage presenting as a working control.
         * Failing the context at assembly is what makes it a visible misconfiguration, and the message
         * says so rather than merely reporting a blank string.</p>
         */
        @Test
        @DisplayName("a blank parameter name is refused, naming the consequence")
        void blankParameterNameIsRefused() {
            SsmClient ssm = new RecordingSsmClient(() -> GetParameterResponse.builder().build());
            assertThatThrownBy(() ->
                    new OnlineWriteGate(ssm, "   ", CACHE, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank")
                    .hasMessageContaining("refuse every mutating request");
        }

        /** A zero or negative cache period is refused. */
        @Test
        @DisplayName("a non-positive cache period is refused")
        void nonPositiveCachePeriodIsRefused() {
            SsmClient ssm = new RecordingSsmClient(() -> GetParameterResponse.builder().build());
            for (Duration period : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
                assertThatThrownBy(() ->
                        new OnlineWriteGate(ssm, PARAMETER, period, Clock.systemUTC()))
                        .as("period %s", period)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("positive");
            }
        }
    }

    /** Cases covering what the refusal says and what it withholds. */
    @Nested
    @DisplayName("the refusal")
    class Refusal {

        /** The refusal names the window and states that nothing was applied. */
        @Test
        @DisplayName("the refusal names the window and says the request was not applied")
        void refusalNamesTheWindow() {
            Fixture fixture = gateReturning("false", Clock.systemUTC());
            assertThatThrownBy(() -> fixture.gate().requireWritesEnabled())
                    .isInstanceOf(OnlineWritesDisabledException.class)
                    .hasMessageContaining("batch window")
                    .hasMessageContaining("not applied");
        }

        /**
         * The refusal does not disclose the parameter's name.
         *
         * <p>Assumptions: a caller can do nothing with a Parameter Store path, and this message reaches
         * a caller through the shared advice, so naming it would put an environment's internal
         * topology into a response body for no benefit. The operator's copy of that detail is the log
         * line the gate writes, which is where it is actionable.</p>
         */
        @Test
        @DisplayName("the refusal does not name the parameter it read")
        void refusalDoesNotNameTheParameter() {
            Fixture fixture = gateReturning("false", Clock.systemUTC());
            assertThatThrownBy(() -> fixture.gate().requireWritesEnabled())
                    .hasMessageNotContaining(PARAMETER);
        }
    }
}
