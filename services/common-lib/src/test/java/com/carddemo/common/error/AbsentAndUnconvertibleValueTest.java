package com.carddemo.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Locks the status and shape {@link GlobalExceptionHandler} answers with when a caller omits a
 * required request value or supplies one the handler's declared type cannot hold.
 *
 * <p>Purpose: both families of failure previously reached the catch-all and were answered as HTTP 500
 * carrying the abend block, and neither could be reached by the advice's caller-input handler. An
 * absent header is a CHECKED {@code ServletException}, so no runtime handler could claim it; an
 * unconvertible value is a {@code TypeMismatchException}, so it reached the runtime handler, matched
 * none of its contention branches and fell through to the same 500. The consequence was visible in
 * two places at once: a caller that merely forgot a header was told the service had failed, and six
 * published contracts declared an HTTP 400 whose only reachable cause was one of these two.</p>
 *
 * <p>Assumptions: every case here drives a real servlet mock rather than invoking the advice method
 * directly, and that is the property under test rather than a matter of style. The framework installs
 * its own resolver for these two families, and whether the advice or that resolver renders the
 * response depends on the order the resolvers are consulted in. Calling the handler method directly
 * would assert the body the method builds while proving nothing about which component the framework
 * routes the failure to -- and it was the routing, not the body, that was wrong.</p>
 *
 * <p>Assumptions: the absent-path-variable case is included even though it asserts a 500. It is the
 * fifth member of the same family as the four the advice claims and is deliberately excluded, because
 * it is raised when a handler declares a variable its own URI template does not contain -- a defect in
 * this repository rather than anything a caller did. Asserting that it still reaches the failure
 * channel is what keeps the exclusion honest: without it, a later widening of the claimed set to the
 * whole family would silently start reporting a server defect to callers as their own mistake.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
@DisplayName("GlobalExceptionHandler absent and unconvertible request values")
final class AbsentAndUnconvertibleValueTest {

    /** The route whose handler requires a header, exercising the absent-header case. */
    private static final String HEADER_ROUTE = "/probe/header";

    /** The route whose handler requires a query parameter. */
    private static final String PARAMETER_ROUTE = "/probe/parameter";

    /** The route whose handler requires a cookie. */
    private static final String COOKIE_ROUTE = "/probe/cookie";

    /** The route whose handler declares a numeric path variable. */
    private static final String NUMERIC_PATH_ROUTE = "/probe/numeric/";

    /** The route whose handler declares a numeric query parameter. */
    private static final String NUMERIC_PARAMETER_ROUTE = "/probe/numeric-parameter";

    /** The route whose handler declares a path variable its own template does not contain. */
    private static final String UNDECLARED_VARIABLE_ROUTE = "/probe/undeclared";

    /**
     * The header the probe requires, spelled as the account update spells its precondition.
     *
     * <p>Assumptions: the real precondition header name is used rather than an invented one, because
     * the measured failure this class exists for was on that exact header and a reader comparing the
     * two should not have to establish that the probe stands in for it.</p>
     */
    private static final String REQUIRED_HEADER = "If-Match";

    /** A value no numeric handler parameter can hold, asserted absent from every rendering. */
    private static final String UNCONVERTIBLE_VALUE = "00000000011x";

    /** The instant the advice's clock is pinned to, so an emitted timestamp is reproducible. */
    private static final String PINNED_INSTANT = "2026-08-07T00:00:00Z";

    /** The mock servlet the assertions drive. */
    private MockMvc mockMvc;

    /**
     * Builds a standalone mock servlet over the probe controller and the shared advice.
     *
     * <p>Assumptions: the advice is registered as controller advice rather than constructed, for the
     * reason recorded on this class: the routing is what is under test.</p>
     */
    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(Instant.parse(PINNED_INSTANT), ZoneOffset.UTC)))
                .build();
    }

    /**
     * An omitted required header is refused as a caller error naming the header.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an omitted required header is 400 naming the header, in the blank state")
    void anOmittedRequiredHeaderIsRefusedAsCallerInput() throws Exception {
        this.mockMvc.perform(get(HEADER_ROUTE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(REQUIRED_HEADER))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()))
                // WHY : Assumptions: the abend block's absence is asserted as well as the status,
                //   because the previous behaviour emitted BOTH a 500 and that block. A fix that
                //   moved the status while still emitting the block would leave a caller's omission
                //   presented as an unrecoverable service failure in the one part of the body an
                //   operator reads first.
                .andExpect(jsonPath("$.abend").doesNotExist());
    }

    /**
     * An omitted required query parameter is refused as a caller error naming the parameter.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an omitted required query parameter is 400 naming the parameter")
    void anOmittedRequiredParameterIsRefusedAsCallerInput() throws Exception {
        this.mockMvc.perform(get(PARAMETER_ROUTE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()));
    }

    /**
     * An omitted required cookie is refused as a caller error naming the cookie.
     *
     * <p>Assumptions: this case is asserted although no operation in this migration requires a
     * cookie. The claim the advice makes is over the family, so a member left unasserted is a member
     * whose accessor could be wired to the wrong name without any test noticing.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an omitted required cookie is 400 naming the cookie")
    void anOmittedRequiredCookieIsRefusedAsCallerInput() throws Exception {
        this.mockMvc.perform(get(COOKIE_ROUTE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("session"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()));
    }

    /**
     * A supplied cookie reaches its handler, so the refusal above is about absence and not the route.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a supplied cookie reaches its handler")
    void aSuppliedCookieReachesItsHandler() throws Exception {
        this.mockMvc.perform(get(COOKIE_ROUTE).cookie(new Cookie("session", "present")))
                .andExpect(status().isOk());
    }

    /**
     * A value the declared numeric type cannot hold is refused as a caller error, and neither
     * sentence in the response quotes it.
     *
     * <p>Assumptions: the two SENTENCES are asserted clear of the value rather than the whole body,
     * and the distinction was measured rather than assumed. The problem shape carries the request path
     * and a path variable IS part of that path, so a whole-body assertion cannot pass for a path
     * value and demanding it would be demanding that the response not say which request failed. What
     * must not appear is the framework's own sentence, which quotes the value it could not convert;
     * the companion case below asserts the whole body for a query value, where the value genuinely has
     * no other home. The one value class whose appearance in a path is itself a disclosure is a
     * primary account number, and that is narrowed by the masker applied to every emitted path --
     * asserted by {@code GlobalExceptionHandlerPathMaskingTest}.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unconvertible path value is 400 naming the variable, with no framework sentence")
    void anUnconvertiblePathValueIsRefusedAsCallerInput() throws Exception {
        this.mockMvc.perform(get(NUMERIC_PATH_ROUTE + UNCONVERTIBLE_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                // WHY : Assumptions: the state is the not-acceptable one and not the blank one,
                //   because a value WAS supplied. The reference draws its asterisk only for an empty
                //   control, so reporting blank here would ask a form to mark a filled field empty.
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()))
                // WHY : Assumptions: the severity is asserted as well as the status, because severity
                //   is what an alert rule matches on. The previous rendering emitted CRITICAL with an
                //   abend block for this same request, so a fix that moved only the status would keep
                //   routing ordinary typing mistakes into the channel that means a service is broken.
                .andExpect(jsonPath("$.severity")
                        .value(ApiError.Severity.WARNING.name()))
                .andExpect(jsonPath("$.abend").doesNotExist())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(UNCONVERTIBLE_VALUE))))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(UNCONVERTIBLE_VALUE))));
    }

    /**
     * An unconvertible query value is refused without the value appearing anywhere in the response.
     *
     * <p>Assumptions: this is the case that can assert the whole body, because a query string is not
     * part of the path the problem shape reports. It is the companion to the path case above and
     * exists for one reason: the framework's sentence for this failure quotes the value, so a rendering
     * that passed the sentence through would satisfy every status and field assertion in this class
     * while copying a caller's value into a body and into every access log the response traverses.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unconvertible query value is 400 and the value appears nowhere in the response")
    void anUnconvertibleQueryValueIsRefusedWithoutEchoingIt() throws Exception {
        this.mockMvc.perform(get(NUMERIC_PARAMETER_ROUTE).param("accountId", UNCONVERTIBLE_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(UNCONVERTIBLE_VALUE))));
    }

    /**
     * A convertible path value reaches its handler, so the refusal above is about the value and not
     * the route.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a convertible path value reaches its handler")
    void aConvertiblePathValueReachesItsHandler() throws Exception {
        this.mockMvc.perform(get(NUMERIC_PATH_ROUTE + "11"))
                .andExpect(status().isOk());
    }

    /**
     * A path variable a handler declares but its own template omits stays a server failure.
     *
     * <p>Assumptions: this asserts a 500 deliberately, and it is the one case in this class that
     * should NOT become a caller error. Spring's own status for that type is 500 for the same reason,
     * and the assertion is what stops the advice's claimed set from being widened to the whole family
     * on the grounds that its four claimed members look alike.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a path variable the template omits is a server failure, not a caller error")
    void anUndeclaredPathVariableRemainsAServerFailure() throws Exception {
        this.mockMvc.perform(get(UNDECLARED_VARIABLE_ROUTE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_INTERNAL));
    }

    /**
     * The routes the assertions above drive, each declaring exactly one kind of required value.
     *
     * <p>Assumptions: a probe controller is declared here rather than a real one being borrowed,
     * because the shared kernel may not depend on any service module -- the layering rules forbid it
     * -- and because each route has to declare exactly one required value for a per-family assertion
     * to be attributable.</p>
     */
    @RestController
    static final class ProbeController {

        /**
         * Requires a header, so omitting it raises the absent-header failure.
         *
         * @param ifMatch the required header value
         * @return the value, so a satisfied request is distinguishable from a refused one
         */
        @GetMapping(HEADER_ROUTE)
        String requiresHeader(@RequestHeader(REQUIRED_HEADER) String ifMatch) {
            return ifMatch;
        }

        /**
         * Requires a query parameter, so omitting it raises the absent-parameter failure.
         *
         * @param accountId the required parameter value
         * @return the value
         */
        @GetMapping(PARAMETER_ROUTE)
        String requiresParameter(@RequestParam String accountId) {
            return accountId;
        }

        /**
         * Requires a cookie, so omitting it raises the absent-cookie failure.
         *
         * @param session the required cookie value
         * @return the value
         */
        @GetMapping(COOKIE_ROUTE)
        String requiresCookie(@CookieValue("session") String session) {
            return session;
        }

        /**
         * Declares a numeric path variable, so a non-numeric segment raises the conversion failure.
         *
         * @param accountId the identifier the segment must convert to
         * @return the identifier
         */
        @GetMapping(NUMERIC_PATH_ROUTE + "{accountId}")
        String requiresNumericPath(@PathVariable long accountId) {
            return String.valueOf(accountId);
        }

        /**
         * Declares a numeric query parameter, so a non-numeric value raises the conversion failure
         * with the value in no other part of the request the problem shape reports.
         *
         * @param accountId the identifier the parameter must convert to
         * @return the identifier
         */
        @GetMapping(NUMERIC_PARAMETER_ROUTE)
        String requiresNumericParameter(@RequestParam long accountId) {
            return String.valueOf(accountId);
        }

        /**
         * Declares a path variable this route's own template does not contain.
         *
         * <p>Assumptions: this mapping is a deliberate defect. It is the only way to provoke the
         * family member the advice does not claim, and provoking it is what makes the exclusion
         * testable rather than merely asserted in prose.</p>
         *
         * @param absent the variable the template never supplies
         * @return the variable, which is unreachable
         */
        @GetMapping(UNDECLARED_VARIABLE_ROUTE)
        String declaresAVariableItsTemplateOmits(@PathVariable("absent") String absent) {
            return absent;
        }
    }
}
