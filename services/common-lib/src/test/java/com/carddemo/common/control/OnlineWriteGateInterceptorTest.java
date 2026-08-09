package com.carddemo.common.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * Asserts which requests the interceptor gates and which it lets through.
 *
 * <p>Purpose. Two classifications decide everything this component does, and an error in either is
 * invisible until a batch window opens: a safe method must never be gated, and a handler that has
 * declared itself a read must never be gated even though its method says otherwise. Both directions of
 * both classifications are asserted here.</p>
 *
 * <p>Assumptions: the gate under test is a real {@link OnlineWriteGate} over a stub client rather than
 * a substitute for the gate itself, and the window is held CLOSED. The interceptor's contract is that
 * it calls the gate for the right requests, and an open window would let everything through -- so every
 * case would pass whether the interceptor consulted the gate or not.</p>
 */
@DisplayName("The online-write gate interceptor gates mutating requests and nothing else")
class OnlineWriteGateInterceptorTest {

    /** Parameter name the stubs answer for. */
    private static final String PARAMETER = "/carddemo/dev/batch/online-writes-enabled";

    /** Cache period used throughout; no case here depends on its expiry. */
    private static final Duration CACHE = Duration.ofSeconds(5);

    /**
     * A Systems Manager client reporting one fixed flag value.
     *
     * <p>Assumptions: only {@code getParameter} is overridden, because the SDK gives every other
     * operation on the service interface a default implementation that throws.</p>
     */
    private static final class FixedFlagClient implements SsmClient {

        /** The value every read reports. */
        private final String value;

        /**
         * Creates the stub.
         *
         * @param value the flag value every read reports
         */
        FixedFlagClient(String value) {
            this.value = value;
        }

        /**
         * Reports the fixed value.
         *
         * @param request the read the gate issued
         * @return a response carrying the fixed value
         */
        @Override
        public GetParameterResponse getParameter(GetParameterRequest request) {
            return GetParameterResponse.builder()
                    .parameter(Parameter.builder().name(PARAMETER).value(this.value).build())
                    .build();
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

        /** Releases nothing, because the stub holds no connection. */
        @Override
        public void close() {
            // Assumptions: present only because the client contract declares it.
        }
    }

    /** Handler methods standing in for the shapes a controller publishes. */
    private static final class PlainHandlers {

        /** A mutating operation that declares no exemption, so it must be gated. */
        void write() {
            // Assumptions: the body is irrelevant. The interceptor decides before a handler runs, so
            //   only this method's annotations and the request's method are read.
        }

        /** A read expressed as a mutating method, exempted at the method. */
        @OnlineWriteGateExempt(reason = "A test double standing in for an internal lookup: a POST only"
                + " so that an identifier travels in a request body rather than in a request line.")
        void readShapedAsWrite() {
            // Assumptions: as above.
        }
    }

    /** A controller every one of whose operations is a read, exempted once at the type. */
    @OnlineWriteGateExempt(reason = "A test double standing in for a controller publishing only"
            + " read-shaped POSTs, exempted once at the type rather than per operation.")
    private static final class ExemptHandlers {

        /** An operation that inherits the type's exemption. */
        void read() {
            // Assumptions: as in PlainHandlers.
        }
    }

    /**
     * Builds an interceptor over a gate whose window carries the given flag value.
     *
     * @param flag the value the flag reports
     * @return the interceptor, never {@code null}
     */
    private static OnlineWriteGateInterceptor interceptorWithFlag(String flag) {
        return new OnlineWriteGateInterceptor(new OnlineWriteGate(
                new FixedFlagClient(flag), PARAMETER, CACHE, Clock.systemUTC()));
    }

    /**
     * Builds the handler the dispatcher would pass for one method of one class.
     *
     * @param bean the controller instance
     * @param method the handler method's name
     * @return the handler method, never {@code null}
     * @throws IllegalStateException if the named method does not exist, so a renamed fixture fails
     *     loudly instead of making the calling case vacuous
     */
    private static HandlerMethod handlerFor(Object bean, String method) {
        try {
            return new HandlerMethod(bean, bean.getClass().getDeclaredMethod(method));
        } catch (NoSuchMethodException absent) {
            throw new IllegalStateException("test fixture has no method " + method, absent);
        }
    }

    /**
     * Runs one request through an interceptor whose window is closed.
     *
     * @param httpMethod the request method
     * @param handler the resolved handler
     * @return the interceptor's verdict when it does not refuse
     */
    private static boolean preHandleWithClosedWindow(String httpMethod, Object handler) {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/api/v1/anything");
        return interceptorWithFlag("false")
                .preHandle(request, new MockHttpServletResponse(), handler);
    }

    /** Cases covering classification by HTTP method. */
    @Nested
    @DisplayName("classification by HTTP method")
    class ByMethod {

        /** Every method that intends to change state is refused while the window is closed. */
        @Test
        @DisplayName("every mutating method is refused while the window is closed")
        void mutatingMethodsAreRefused() {
            HandlerMethod handler = handlerFor(new PlainHandlers(), "write");
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                assertThatThrownBy(() -> preHandleWithClosedWindow(method, handler))
                        .as("%s must be gated", method)
                        .isInstanceOf(OnlineWritesDisabledException.class);
            }
        }

        /**
         * Safe methods pass even with the window closed.
         *
         * <p>Assumptions: this is the property that makes a quiesce a write window rather than an
         * outage. Gating a read would take the whole application away from its users rather than only
         * its updates -- which is narrower than what the reference did, because the reference closed
         * its files outright, and this migration deliberately narrows it.</p>
         */
        @Test
        @DisplayName("safe methods pass, so reads keep working while the window is closed")
        void safeMethodsPass() {
            HandlerMethod handler = handlerFor(new PlainHandlers(), "write");
            for (String method : List.of("GET", "HEAD", "OPTIONS", "TRACE")) {
                assertThatCode(() -> preHandleWithClosedWindow(method, handler))
                        .as("%s must not be gated", method)
                        .doesNotThrowAnyException();
            }
        }

        /**
         * A method the allow-list does not name is gated rather than admitted.
         *
         * <p>Refactoring Rationale: this case caught a real defect rather than confirming a design. The
         * classification originally enumerated the MUTATING methods, which admits every verb it fails
         * to name -- so a handler mapped to an unanticipated verb would have escaped the gate silently.
         * It now enumerates the SAFE methods and gates everything else, and this case is what pins that
         * direction so the inversion cannot be reintroduced as a simplification.</p>
         */
        @Test
        @DisplayName("an unknown method is gated rather than admitted")
        void unknownMethodIsGated() {
            HandlerMethod handler = handlerFor(new PlainHandlers(), "write");
            assertThatThrownBy(() -> preHandleWithClosedWindow("FROBNICATE", handler))
                    .isInstanceOf(OnlineWritesDisabledException.class);
        }

        /** A lower-case method is classified the same as its upper-case spelling. */
        @Test
        @DisplayName("a lower-case method is classified the same as its upper-case spelling")
        void methodComparisonIgnoresCase() {
            HandlerMethod handler = handlerFor(new PlainHandlers(), "write");
            assertThatThrownBy(() -> preHandleWithClosedWindow("post", handler))
                    .isInstanceOf(OnlineWritesDisabledException.class);
        }
    }

    /** Cases covering the declared exemptions. */
    @Nested
    @DisplayName("declared exemptions")
    class Exemptions {

        /** A method-level exemption lets a read-shaped mutating method through. */
        @Test
        @DisplayName("a method-level exemption lets a read-shaped POST through")
        void methodLevelExemptionPasses() {
            HandlerMethod handler = handlerFor(new PlainHandlers(), "readShapedAsWrite");
            assertThatCode(() -> preHandleWithClosedWindow("POST", handler))
                    .doesNotThrowAnyException();
        }

        /** A type-level exemption covers every operation the controller publishes. */
        @Test
        @DisplayName("a type-level exemption covers every operation the controller publishes")
        void typeLevelExemptionPasses() {
            HandlerMethod handler = handlerFor(new ExemptHandlers(), "read");
            assertThatCode(() -> preHandleWithClosedWindow("POST", handler))
                    .doesNotThrowAnyException();
        }

        /**
         * An exemption on one method does not reach its siblings.
         *
         * <p>Assumptions: asserted because the mistake it guards against fails in the dangerous
         * direction. An exemption that spread across a controller would silently un-gate the update
         * sitting beside a lookup, and in this migration the two do live on one controller -- the
         * account context read and the account update are both on {@code AccountController}.</p>
         */
        @Test
        @DisplayName("a sibling of an exempt method is still gated")
        void exemptionDoesNotLeakToSiblings() {
            HandlerMethod handler = handlerFor(new PlainHandlers(), "write");
            assertThatThrownBy(() -> preHandleWithClosedWindow("POST", handler))
                    .isInstanceOf(OnlineWritesDisabledException.class);
        }

        /**
         * Every exemption states a reason, because the annotation gives none by default.
         *
         * <p>Assumptions: asserted rather than left to review. The absence of a default is the whole
         * mechanism by which an exemption cannot be added silently, so a later revision adding one
         * would defeat the control's reviewability without breaking anything else.</p>
         */
        @Test
        @DisplayName("every exemption states a reason, because the annotation has no default")
        void everyExemptionStatesAReason() {
            OnlineWriteGateExempt onMethod = handlerFor(new PlainHandlers(), "readShapedAsWrite")
                    .getMethodAnnotation(OnlineWriteGateExempt.class);
            assertThat(onMethod).isNotNull();
            assertThat(onMethod.reason()).isNotBlank();
            assertThat(ExemptHandlers.class.getAnnotation(OnlineWriteGateExempt.class).reason())
                    .isNotBlank();
        }
    }

    /** Cases covering handlers that are not controller methods. */
    @Nested
    @DisplayName("handlers that are not controller methods")
    class OtherHandlers {

        /**
         * A handler that is not a controller method is gated rather than exempted.
         *
         * <p>Assumptions: the safe default. The only such handlers in these services serve static
         * resources and answer no mutating method, so gating them removes nothing, whereas treating
         * them as exempt would create a category of request that escapes the control by not being a
         * controller.</p>
         */
        @Test
        @DisplayName("a non-controller handler is gated on a mutating method")
        void nonControllerHandlerIsGated() {
            assertThatThrownBy(() -> preHandleWithClosedWindow("POST", new Object()))
                    .isInstanceOf(OnlineWritesDisabledException.class);
        }

        /** A non-controller handler still passes on a safe method. */
        @Test
        @DisplayName("a non-controller handler still passes on a safe method")
        void nonControllerHandlerPassesOnSafeMethod() {
            assertThatCode(() -> preHandleWithClosedWindow("GET", new Object()))
                    .doesNotThrowAnyException();
        }
    }

    /** Cases covering the ordinary case, in which the window is open. */
    @Nested
    @DisplayName("the open window")
    class OpenWindow {

        /**
         * Nothing is refused once the window is open.
         *
         * <p>Assumptions: asserted so that the cases above cannot be satisfied by an interceptor that
         * simply refuses everything. Together the two directions pin the behaviour rather than one
         * half of it.</p>
         */
        @Test
        @DisplayName("nothing is refused once the window is open")
        void nothingIsRefusedWhenOpen() {
            OnlineWriteGateInterceptor interceptor = interceptorWithFlag("true");
            HandlerMethod handler = handlerFor(new PlainHandlers(), "write");

            for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "GET")) {
                MockHttpServletRequest request =
                        new MockHttpServletRequest(method, "/api/v1/anything");
                assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler))
                        .as("%s must proceed when the window is open", method)
                        .isTrue();
            }
        }
    }

    /** Cases covering the constructor's guard. */
    @Nested
    @DisplayName("construction")
    class Construction {

        /** The gate is required, so a mis-wired interceptor fails at assembly. */
        @Test
        @DisplayName("the gate is required")
        void gateIsRequired() {
            assertThatThrownBy(() -> new OnlineWriteGateInterceptor(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
