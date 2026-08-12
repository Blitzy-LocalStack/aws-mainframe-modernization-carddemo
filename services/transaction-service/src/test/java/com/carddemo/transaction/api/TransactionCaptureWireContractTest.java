package com.carddemo.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.service.TransactionAddService;
import com.carddemo.transaction.service.TransactionListService;
import com.carddemo.transaction.service.TransactionViewService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the capture operation admits and refuses the exact submissions its contract publishes.
 *
 * <h2>Purpose</h2>
 *
 * <p>Two properties of the capture payload can only be established at the WIRE, and both were previously
 * unheld. The first is which combinations of the two key fields are admitted: whether supplying both an
 * account identifier and a card number is accepted, as {@code app/cbl/COTRN02C.cbl} accepts it, or
 * refused. The second is which LEXICAL forms of the amount are admitted: the published
 * {@code TransactionAmount} schema fixes the characters a producer may send, and by the time any bean
 * validator or service runs, those characters are gone -- the value has already been parsed into an
 * exact-decimal object, so a form the document refuses is indistinguishable from one it accepts.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a test class: no caller constructs it,
 * it yields no value and it raises nothing outside the test engine, so the type itself accepts no
 * parameter, returns nothing and throws nothing. The inapplicability is stated rather than passed over,
 * because the user-specified Explainability rule forbids a docstring that omits parameters, return values
 * or purpose, and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own at-clauses.</p>
 *
 * <h2>Alternatives Considered: why these cases are not service unit tests</h2>
 *
 * <p>Alternatives Considered: asserting both properties against {@link TransactionAddService} directly,
 * which is where every other property of this screen is held. Rejected for both, on the same underlying
 * ground and with two different consequences. A service test constructs its submission as a Java object,
 * so the key-combination case would exercise the record's own constructor rather than the constraint the
 * framework applies to a deserialised body -- and a class-level constraint that is never triggered passes
 * every such test. The amount case is worse: the characters a producer sent do not survive into the
 * object at all, so a service test asserting the amount cannot distinguish {@code "1234.5"} from
 * {@code "1234.50"} because both arrive as the same value. Only a request carrying raw JSON can hold
 * either property.</p>
 *
 * <h2>Assumptions: the standalone builder, and what it does and does not supply</h2>
 *
 * <p>Assumptions: the controller is exercised through the standalone builder rather than a started
 * application context, for the reason the sibling failure-contract class records -- a context for this
 * module needs a resolvable token issuer and a reachable database. The builder does install a validator,
 * which is what makes the class-level key constraint reachable here, and the shared advice is registered
 * explicitly, because without it a refusal surfaces as a raw servlet error and none of these assertions
 * would be about the published shape.</p>
 *
 * <p>Assumptions: the two refusal families render DIFFERENTLY and the difference is asserted rather than
 * smoothed over. A key combination that no arm admits is a constraint violation, so it arrives as a
 * validation failure carrying a per-field entry; a malformed amount is refused before any object exists
 * to validate, so it arrives as an unreadable body carrying the shared malformed-request sentence and an
 * empty field array. Expecting one shape for both would fail against a correct implementation.</p>
 */
@DisplayName("the capture operation's admitted and refused submissions")
class TransactionCaptureWireContractTest {

    /** A fixed instant so the rendered problem timestamp is deterministic. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /** The eleven digit account identifier the admitted submissions carry. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The sixteen digit card number the admitted submissions carry. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The identifier the stubbed append reports back, at the declared width. */
    private static final String APPENDED_ID = "0000000000000042";

    /** The capture this controller delegates to, stubbed per test. */
    private TransactionAddService addService;

    /** The entry point under test. */
    private MockMvc mockMvc;

    /** Builds the controller over stubbed services and registers the shared advice. */
    @BeforeEach
    void setUp() {
        this.addService = mock(TransactionAddService.class);
        TransactionController controller = new TransactionController(
                mock(TransactionListService.class), mock(TransactionViewService.class),
                this.addService, mock(CursorToken.class));

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().addModule(new MoneyModule()).build()))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Composes a capture body carrying the given keys and the given raw amount token.
     *
     * <p>Assumptions: the body is assembled as TEXT rather than serialised from an object, which is the
     * whole mechanism of this class. Serialising an object would re-render the amount through the same
     * writer the production path uses, so a form the document refuses could not be expressed at all --
     * the test would be sending the canonical form and asserting that it is accepted.</p>
     *
     * @param accountId the account identifier to send, of type {@link String}, empty to omit the key;
     *     must not be {@code null}
     * @param cardNumber the card number to send, of type {@link String}, empty to omit the key; must not
     *     be {@code null}
     * @param rawAmount the amount token to send verbatim between quotes, of type {@link String}; must not
     *     be {@code null}
     * @return the request body, never {@code null}
     */
    private static String body(String accountId, String cardNumber, String rawAmount) {
        return "{\"accountId\":\"" + accountId + "\",\"cardNumber\":\"" + cardNumber + "\","
                + "\"typeCode\":\"01\",\"categoryCode\":\"0001\",\"source\":\"POS TERM\","
                + "\"description\":\"GROCERY PURCHASE\",\"amount\":\"" + rawAmount + "\","
                + "\"merchantId\":\"123456789\",\"merchantName\":\"CORNER STORE\","
                + "\"merchantCity\":\"SEATTLE\",\"merchantZip\":\"98101\","
                + "\"originDate\":\"2026-01-15\",\"processDate\":\"2026-01-16\","
                + "\"confirmation\":\"Y\"}";
    }

    /** Arranges the capture to answer with an appended row, so an admitted body reaches 201. */
    private void theCaptureAppends() {
        when(this.addService.addTransaction(any())).thenReturn(new TransactionAddResponse(
                APPENDED_ID, Money.of("1234.50"), "Transaction added successfully."));
    }

    /**
     * A submission carrying BOTH keys is admitted, and the account key is the one that decides.
     *
     * <p>Purpose: this pins {@code VALIDATE-INPUT-KEY-FIELDS} at lines 193 to 230 of
     * {@code app/cbl/COTRN02C.cbl} at the wire. The construct at line 195 is an {@code EVALUATE TRUE}, so
     * the FIRST arm whose condition holds is the only arm that runs: the account identifier at line 196,
     * then the card number at line 210, then the complaint at line 224. A submission carrying both
     * therefore takes the account arm, and the card number it supplied is discarded at line 209 -- the
     * reference reads the cross-reference by account and overwrites the card field with the entry's
     * value.</p>
     *
     * <p>⚠️ Refactoring Rationale: this submission used to be REFUSED, and the refusal was a defect with
     * two independent costs. The record carried an exclusivity constraint -- exactly one key, expressed as
     * an exclusive-or -- and no line of the reference asserts exclusivity: line 195 SELECTS a key, it does
     * not validate the combination. So the constraint refused submissions the baseline captures, which is
     * a parity failure on its own; and because it refused them before any arm ran, the account-first
     * precedence at line 196 was UNREACHABLE, so the one behaviour a reader would come to this code to
     * verify could not be observed at all. The constraint is now inclusive -- at least one key -- and the
     * published document expresses it with {@code anyOf} rather than {@code oneOf} plus a negation.</p>
     *
     * <p>Assumptions: the case asserts admission at the WIRE and delegates the precedence itself to the
     * service, where the cross-reference reads can be verified by name. Establishing here that the request
     * reached the service at all is what proves the constraint no longer stands in front of it; asserting
     * which cross-reference it then read would duplicate a case that already exists and would need the
     * service unstubbed to mean anything.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("admit a submission carrying both keys, per the EVALUATE TRUE at line 195")
    void admitsASubmissionCarryingBothKeys() throws Exception {
        theCaptureAppends();

        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ACCOUNT_ID, CARD_NUMBER, "1234.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(APPENDED_ID));

        verify(this.addService).addTransaction(any());
    }

    /**
     * Each key on its own is admitted too, so the inclusive constraint has not become a no-op.
     *
     * <p>Assumptions: both single-key forms are exercised beside the both-keys form above, because a
     * constraint relaxed by deleting it would satisfy that case as readily as a constraint relaxed
     * correctly. Together the three admitted forms and the refused form below pin all four combinations
     * the two nullable keys can take, which is what makes the inclusive reading complete rather than
     * merely wider than the exclusive one.</p>
     *
     * @param accountId the account identifier to send, of type {@link String}, empty to omit it
     * @param cardNumber the card number to send, of type {@link String}, empty to omit it
     * @throws Exception if the request could not be performed
     */
    @ParameterizedTest(name = "account [{0}] card [{1}]")
    @CsvSource({"00000000011,''", "'',4111111111111111"})
    @DisplayName("admit either key on its own, per the arms at lines 196 and 210")
    void admitsEitherKeyOnItsOwn(String accountId, String cardNumber) throws Exception {
        theCaptureAppends();

        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(accountId, cardNumber, "1234.50")))
                .andExpect(status().isCreated());
    }

    /**
     * A submission carrying NEITHER key is still refused, with the reference's own complaint.
     *
     * <p>Purpose: this pins the residual arm at line 224 of {@code app/cbl/COTRN02C.cbl}, whose sentence
     * at line 226 is the one the record's constraint publishes. It is the arm the {@code EVALUATE TRUE}
     * reaches when neither condition held, so it is the only combination of the two keys the reference
     * refuses.</p>
     *
     * <p>Assumptions: the refusal is asserted to reach the service NOT AT ALL, because a constraint
     * violation is answered before the handler body runs. That also distinguishes this case from the
     * service's own key-validation cases, which assert the same sentence raised from inside the
     * service for a submission constructed in Java.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("refuse a submission carrying neither key, per the residual arm at line 224")
    void refusesASubmissionCarryingNeitherKey() throws Exception {
        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "", "1234.50")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(TransactionAddRequest.KEY_FIELD_REQUIRED));

        verify(this.addService, never()).addTransaction(any());
    }

    /**
     * The canonical amount form is admitted, which is the control for the four refusals below.
     *
     * <p>Assumptions: this case exists so that the refusals below cannot pass by refusing everything. A
     * gate that rejected every string token would satisfy four negative cases and break the operation
     * outright, and nothing in those four would reveal it.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("admit the published amount form: digits, one point, exactly two more digits")
    void admitsTheCanonicalAmountForm() throws Exception {
        theCaptureAppends();

        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ACCOUNT_ID, "", "1234.50")))
                .andExpect(status().isCreated());
    }

    /**
     * A negative amount in the published form is admitted, sign and all.
     *
     * <p>Assumptions: the leading minus is asserted admitted separately from the leading plus being
     * refused below, because the two are one character apart and a gate written against the wrong
     * character class would refuse both. {@code TRAN-AMT} is {@code PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy}, so the sign position is part of the record contract, and the screen
     * field's own edit at lines 339 to 345 of {@code app/cbl/COTRN02C.cbl} admits a minus at position
     * one.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("admit a negative amount, whose minus the record's sign position declares")
    void admitsANegativeAmount() throws Exception {
        when(this.addService.addTransaction(any())).thenReturn(new TransactionAddResponse(
                APPENDED_ID, Money.of("-1234.50"), "Transaction added successfully."));

        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ACCOUNT_ID, "", "-1234.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("-1234.50"));
    }

    /**
     * Four lexical forms the published schema refuses are refused rather than silently normalised.
     *
     * <p>Purpose: this pins the {@code TransactionAmount} pattern in
     * {@code openapi/transaction-api.yaml}, {@code ^-?[0-9]{1,9}\.[0-9]{2}$}, against the four forms a
     * producer is most likely to send that do not match it. Each corresponds to a positional refusal in
     * {@code app/cbl/COTRN02C.cbl} lines 339 to 345, where the keyed characters are edited position by
     * position and answered with {@code 'Amount should be in format -99999999.99'}.</p>
     *
     * <p>⚠️ Refactoring Rationale: all four were previously ACCEPTED and silently rewritten, and the
     * mechanism is worth stating because nothing in the service could have caught it. The shared money
     * type's own grammar is deliberately lenient -- it admits an optional sign, one to twenty integer
     * digits and an optional fractional part of up to its maximum input scale, then normalises to scale
     * two with half-up rounding -- because that leniency is load-bearing where a report edit mask is read
     * back. So a body carrying {@code "1234.5"} was parsed, rounded to {@code 1234.50} and captured, and
     * the service's own shape check then inspected the RE-RENDERED value, which by construction always
     * matches. The gate therefore has to run on the raw token, inside a deserialiser bound to the amount
     * component, which is the only point at which the submitted characters still exist.</p>
     *
     * <p>Alternatives Considered: tightening the shared money type's grammar in {@code common-lib} so that
     * every consumer inherits the strict form. Rejected because the leniency has a caller that needs it --
     * the reporting context reads its own 133-column edit masks back through the same type, and those
     * carry grouping separators and a trailing sign -- so tightening it would break a path this change has
     * no business touching. Also considered: a second {@code String} component holding the raw token
     * beside the parsed one. Rejected because the published request schema closes its object, so a
     * fourteenth property would break the contract for every producer.</p>
     *
     * <p>Assumptions: the response shape asserted is the UNREADABLE-body one and not the validation one,
     * carrying the shared malformed-request sentence and an empty field array. A deserialiser refuses
     * before an object exists, so no bean-validation report can be produced for it, and the submitted
     * characters are deliberately absent from the rendered body -- an amount is monetary data, and a
     * message echoed to a caller's logs is the one destination the masking applied at the API edge does
     * not reach.</p>
     *
     * @param rawAmount the amount token to send verbatim, of type {@link String}
     * @param why the reason the published pattern refuses it, of type {@link String}, carried so a
     *     failure names the form rather than only the value
     * @throws Exception if the request could not be performed
     */
    @ParameterizedTest(name = "[{0}] refused: {1}")
    @CsvSource({
        "1234.5,a single fractional digit where the pattern requires exactly two",
        "+1234.50,a leading plus where the pattern admits only a minus",
        "'1,234.50',a grouping separator the pattern does not admit",
        "' 1234.50 ',surrounding whitespace the pattern does not admit",
    })
    @DisplayName("refuse each lexical form the published amount pattern excludes")
    void refusesEachExcludedAmountForm(String rawAmount, String why) throws Exception {
        // WHY : Trade-offs: the outcome is read off the result and asserted through the fluent library
        //       rather than through the request builder's own status expectation, so that the REASON the
        //       published pattern excludes this form reaches the failure message. A bare status
        //       expectation reports "expected 400 but was 201" for four different forms and leaves a
        //       reader to work out which one and why; the cost is two statements where one would do.
        MvcResult refused = this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ACCOUNT_ID, "", rawAmount)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("[%s] must be refused: %s", rawAmount, why)
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(refused.getResponse().getContentAsString())
                .as("a deserialiser refuses before an object exists, so the body is the unreadable one")
                .contains(ApiError.CODE_VALIDATION)
                .contains(GlobalExceptionHandler.MESSAGE_MALFORMED_REQUEST)
                // WHY : Assumptions: the submitted characters are asserted ABSENT from the body as well
                //       as the sentence asserted present. An amount is monetary data and a message
                //       echoed to a caller's logs is the one destination the masking applied at the API
                //       edge does not reach, so a diagnostic that quoted the value would leak it.
                .doesNotContain(rawAmount.trim());

        // WHY : Assumptions: the service is asserted untouched, which is the claim that distinguishes a
        //       refusal from a normalisation. Every one of these four forms previously REACHED the
        //       service, as a value it could not tell apart from the canonical one, so "the body was
        //       refused" and "the append did not happen" are two different facts here and both matter.
        verify(this.addService, never()).addTransaction(any());
    }

    /**
     * A JSON number for the amount is refused, which is the money-on-the-wire rule at its boundary.
     *
     * <p>Purpose: transformation rule T3 carries money as a JSON STRING, because a JSON number is parsed
     * into an IEEE-754 double by most clients and by several parsers before any application code sees it.
     * The refusal is asserted here rather than assumed from the schema, because a producer sending a
     * number is the likeliest way exactness is lost at this boundary and the loss is silent.</p>
     *
     * <p>Assumptions: the value chosen is one a double represents exactly, so the case cannot pass merely
     * because the number happened to round. What is being refused is the TOKEN TYPE, not the value.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("refuse a JSON number for the amount, per transformation rule T3")
    void refusesAJsonNumberForTheAmount() throws Exception {
        String numericAmount = body(ACCOUNT_ID, "", "1234.50")
                .replace("\"amount\":\"1234.50\"", "\"amount\":1234.50");

        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(numericAmount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(GlobalExceptionHandler.MESSAGE_MALFORMED_REQUEST));

        verify(this.addService, never()).addTransaction(any());
    }
}
