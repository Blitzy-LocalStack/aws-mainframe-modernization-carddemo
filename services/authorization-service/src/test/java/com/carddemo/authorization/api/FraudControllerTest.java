package com.carddemo.authorization.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.service.FraudMarkingService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the HTTP boundary of the one write this context publishes: its route, the status code that
 * distinguishes the two write paths, and the structured refusal a key disagreement produces.
 *
 * <p>Refactoring Rationale: the server is assembled STANDALONE rather than through a sliced application
 * context. This module's {@code SecurityConfig} builds its decoder with
 * {@code NimbusJwtDecoder.withIssuerLocation}, which resolves the issuer's discovery document EAGERLY at
 * bean construction, so loading any context that includes that configuration reaches the network from a
 * unit test. A standalone server exercises exactly what this class is responsible for -- routing, binding,
 * parameter and body validation, delegation and status selection -- while the authority matrix is asserted
 * against the decision object itself in {@code config/SecurityConfigTest}, which is where it belongs.</p>
 *
 * <p>Assumptions: the shared advice is registered on the standalone server, because half of what this class
 * publishes is the SHAPE of a refusal. Without it a refusal would surface as a raised exception rather than
 * as the problem body a client parses, and the per-field array the contract promises would be unasserted.</p>
 */
class FraudControllerTest {

    /**
     * The authenticated principal the controller reads the selector's binding subject from.
     *
     * <p>Assumptions: a standalone {@code MockMvc} runs no security chain, so the principal is supplied on
     * the request builder. The handler passes its name to the service, which is the value these cases stub
     * against, so a request without one would fail on a null principal rather than on the property asserted.
     * </p>
     */
    private static final String SUBJECT = "authorization-operator";

    /**
     * The principal instance every request in this class is performed as.
     */
    private static final java.security.Principal PRINCIPAL = () -> SUBJECT;

    /** The route the contract publishes, with a placeholder for the sealed selector. */
    private static final String FRAUD_ROUTE = "/api/v1/authorizations/{key}/fraud";

    /**
     * A value of the sealed shape, used where the redemption is the service's to perform.
     *
     * <p>Assumptions: this is a shape-valid token and NOT a redeemable one -- its authentication code is
     * filler. Nothing in this class redeems it: the service is a double here, so what is asserted is that a
     * value of the published shape reaches the handler rather than being refused by the path constraint.</p>
     */
    private static final String SEALED_SHAPE_SELECTOR =
            "v1.MDAwMDAwMDAwMTE6MjYyMTU6OTE2NDQ5MDI."
                    + "0123456789012345678901234567890123456789012";

    /** The service double, so this class asserts the boundary rather than the behaviour behind it. */
    private FraudMarkingService marking;

    /** The standalone server under test. */
    private MockMvc mockMvc;

    /** The codec the standalone server binds every body with. */
    private JsonMapper jsonMapper;

    /**
     * Assembles the standalone server over the service double, the shared advice and the money codec.
     *
     * <p>Assumptions: the money module is registered even though this operation's bodies carry no amount,
     * because the converter installed here is the one the standalone server uses for every body and
     * registering it selectively per test class is how a service ends up with two different codecs.</p>
     */
    @BeforeEach
    void setUp() {
        this.marking = mock(FraudMarkingService.class);
        this.jsonMapper = JsonMapper.builder().addModule(new MoneyModule()).build();
        this.mockMvc = MockMvcBuilders.standaloneSetup(new FraudController(this.marking))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(this.jsonMapper))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(Instant.parse("2026-08-06T09:20:00Z"), ZoneOffset.UTC)))
                .build();
    }

    /**
     * A write that created the fraud row answers 201 with the reference insert sentence.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a created fraud row answers 201 carrying the reference insert sentence")
    void createdFraudRowAnswersCreated() throws Exception {
        when(this.marking.mark(eq(SEALED_SHAPE_SELECTOR), any(FraudMarkRequest.class), eq(SUBJECT)))
                .thenReturn(new FraudMarkingService.FraudMarkOutcome(
                        FraudMarkResponse.added(), true));

        this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                        .principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson("F")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.updateStatus")
                        .value(FraudMarkResponse.UPDATE_STATUS_SUCCESS))
                .andExpect(jsonPath("$.message")
                        .value(FraudMarkResponse.MESSAGE_ADD_SUCCESS));
    }

    /**
     * A write that replaced an existing fraud row answers 200 with the reference update sentence.
     *
     * <p>Assumptions: asserted separately from the created case because the status code is the ONLY thing
     * that distinguishes the two paths on the wire -- the body shape is identical, and the contract
     * deliberately carries no discriminating member.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a replaced fraud row answers 200 carrying the reference update sentence")
    void replacedFraudRowAnswersOk() throws Exception {
        when(this.marking.mark(eq(SEALED_SHAPE_SELECTOR), any(FraudMarkRequest.class), eq(SUBJECT)))
                .thenReturn(new FraudMarkingService.FraudMarkOutcome(
                        FraudMarkResponse.updated(), false));

        this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                        .principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson("R")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS));
    }

    /**
     * A fraud action outside its two-character domain is refused before the service is reached.
     *
     * <p>Assumptions: {@code S} is used as the refused value specifically because it is the SUCCESS value of
     * the response's own one-character flag, so a caller or a mapper that confused the two adjacent
     * reference fields would send exactly this. The refusal is keyed {@code action}, which is the member
     * name the request schema publishes.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a fraud action outside its domain is refused before the service is reached")
    void fraudActionOutsideItsDomainIsRefused() throws Exception {
        this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                        .principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson("S")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("action"));

        verifyNoInteractions(this.marking);
    }

    /**
     * A selector naming no row answers 404 rather than 400.
     *
     * <p>Assumptions: the two are told apart because a caller can correct a malformed selector and cannot
     * correct a row the expiry sweep has removed.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a selector naming no row answers 404")
    void selectorNamingNoRowAnswersNotFound() throws Exception {
        when(this.marking.mark(any(), any(FraudMarkRequest.class), any()))
                .thenThrow(new NoSuchElementException("the selector names no pending authorization"));

        this.mockMvc.perform(put(FRAUD_ROUTE, SEALED_SHAPE_SELECTOR)
                        .principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson("F")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND));
    }

    /**
     * Renders a request body naming the canonical row with one stated action.
     *
     * @param action the fraud action to place in the body
     * @return the JSON body
     */
    private String bodyJson(String action) {
        return """
                {"action":"%s"}""".formatted(action);
    }
}
