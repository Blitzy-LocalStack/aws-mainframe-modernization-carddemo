package com.carddemo.card.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.domain.Card;
import com.carddemo.card.domain.EncryptedCvv;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.service.CardAdminViewService;
import com.carddemo.card.service.CardListService;
import com.carddemo.card.service.CardUpdateService;
import com.carddemo.card.service.CardViewService;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Drives the update route over HTTP with the REAL update service behind it, so the reference field-state
 * gates and their verbatim sentences are asserted where a caller actually meets them.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: the review found that the declarative constraints on the request body ran ahead
 * of the service and decided the outcome for exactly the inputs the service transcribes most carefully -- a
 * blank attribute and an all-zeros attribute. Withdrawing those constraints is only half a fix, because
 * nothing then proved that the service's classification reaches a caller: every existing case for it called
 * the service directly, and a request-mapping layer that still refused first would have satisfied all of
 * them. This class closes that gap by asserting through the mapping layer, which is the only place the
 * ordering of the two layers is observable.</p>
 *
 * <p>Assumptions: the service under test is REAL and only the store is substituted. A mocked service would
 * make every assertion here a statement about the stub, and the property under test -- which layer answers
 * first -- would be unobservable by construction. The shared advice is registered for the same reason: the
 * status and the body shape are its work, not the controller's, so a test that bypassed it would assert a
 * response no deployment returns.</p>
 *
 * <p>Assumptions: the three read collaborators are mocked and never exercised. They are constructor
 * arguments of the controller rather than participants in the update route, and substituting them keeps a
 * failure here attributable to the write path.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
class CardUpdateHttpValidationTest {

    /**
     * The selector-sealing key, fabricated and used only by this class.
     *
     * <p>Assumptions: the width is the thirty-two bytes the sealer requires. The value identifies nothing and
     * is committed deliberately, because what it proves is that a selector minted under a key opens under the
     * same key -- a property no real key is needed for.</p>
     */
    private static final byte[] SELECTOR_KEY =
            "card-http-selector-key-0123456789".substring(0, 32).getBytes(StandardCharsets.UTF_8);

    /**
     * The cursor-signing key, needed only because the browse service is a constructor argument.
     */
    private static final byte[] CURSOR_KEY =
            "card-http-cursor-key-01234567890".substring(0, 32).getBytes(StandardCharsets.UTF_8);

    /** The cursor lifetime, immaterial here because no cursor is minted. */
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(10);

    /** A fixed instant, so a rendered error body is byte-stable across runs. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-02T03:04:05Z");

    /** The stored card number every case addresses. */
    private static final String CARD_NUMBER = "4111111111110011";

    /** The stored account the card belongs to. */
    private static final long ACCOUNT_ID = 10000000001L;

    /** The stored embossed name, conforming, so a case varying another attribute is attributable. */
    private static final String STORED_NAME = "JOHN Q PUBLIC";

    /** The stored active status. */
    private static final String STORED_STATUS = "Y";

    /** The stored expiry, whose two parts a case may vary independently. */
    private static final LocalDate STORED_EXPIRY = LocalDate.of(2026, 12, 31);

    /** The stored revision, which a conforming submission echoes. */
    private static final int STORED_VERSION = 0;

    /** A revision no stored row holds, for the conflict case. */
    private static final int STALE_VERSION = 7;

    /** The substituted store, so no database is reached. */
    private CardRepository cards;

    /** The real mapper, so a selector minted here opens in the service. */
    private CardMapper mapper;

    /** The route under test, wired exactly as the application wires it. */
    private MockMvc mockMvc;

    /**
     * Builds the collaborators and the request-mapping layer before each case.
     *
     * <p>Assumptions: one mapper instance serves a case because a selector sealed by one instance opens only
     * under an instance holding the same key, and every case seals a selector and hands it straight back
     * through a request path.</p>
     */
    @BeforeEach
    void buildRoute() {
        this.cards = mock(CardRepository.class);
        this.mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));

        CardUpdateService writes = new CardUpdateService(this.cards, this.mapper);
        CardListService reads =
                new CardListService(this.cards, this.mapper, new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));
        CardViewService views = new CardViewService(this.cards, this.mapper);
        CardAdminViewService adminViews = new CardAdminViewService(views, this.mapper);

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new CardController(reads, views, adminViews, writes))
                .setControllerAdvice(
                        new GlobalExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Asserts that a conforming submission reaches the write path, so every refusal below is attributable.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a conforming submission is accepted over HTTP")
    void aConformingSubmissionIsAcceptedOverHttp() throws Exception {
        Card stored = storedCard();
        when(this.cards.findById(CARD_NUMBER)).thenReturn(Optional.of(stored));
        when(this.cards.saveAndFlush(stored)).thenReturn(stored);

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(STORED_NAME, "N", "11", "2027", STORED_VERSION)))
                .andExpect(status().isOk());

        verify(this.cards).saveAndFlush(stored);
    }

    /**
     * Asserts the reference sentence for each faulted attribute reaches the caller, with its field name and
     * its field STATE, rather than a declarative violation raised before the service.
     *
     * <p>Assumptions: the expected state is asserted alongside the sentence because the two carry different
     * information and only together reproduce the reference. {@code BLANK} is what earns the literal asterisk
     * the reference writes into an empty field at {@code app/cbl/COCRDUPC.cbl:1263-1272}, and {@code NOT_OK}
     * is the highlight without it, so a response that carried the right sentence with the wrong state would
     * render the wrong screen.</p>
     *
     * <p>Assumptions: the all-zeros forms are included as their own rows. The reference classifies zeros with
     * spaces and low values in the same BLANK arm -- {@code :811-813} for the name and the corresponding
     * arms for the expiry parts -- which is the single least obvious thing about these gates and the one a
     * declarative pattern silently got wrong.</p>
     *
     * @param name the embossed name to submit
     * @param status the active status to submit
     * @param month the expiry month to submit
     * @param year the expiry year to submit
     * @param field the response field the fault must be reported against
     * @param state the field state the response must carry
     * @param sentence the reference sentence the response must carry verbatim
     * @throws Exception if the request cannot be performed
     */
    @ParameterizedTest(name = "{4} [{5}] {6}")
    @CsvSource({
        "'   ',Y,12,2026,embossedName,BLANK,Card name not provided",
        "'0000',Y,12,2026,embossedName,BLANK,Card name not provided",
        "JOHN-PAUL,Y,12,2026,embossedName,NOT_OK,Card name can only contain alphabets and spaces",
        "JOHN Q PUBLIC,X,12,2026,activeStatus,NOT_OK,Card Active Status must be Y or N",
        "JOHN Q PUBLIC,' ',12,2026,activeStatus,BLANK,Card Active Status must be Y or N",
        "JOHN Q PUBLIC,Y,13,2026,expirationMonth,NOT_OK,Card expiry month must be between 1 and 12",
        "JOHN Q PUBLIC,Y,00,2026,expirationMonth,BLANK,Card expiry month must be between 1 and 12",
        "JOHN Q PUBLIC,Y,12,1949,expirationYear,NOT_OK,Invalid card expiry year",
        "JOHN Q PUBLIC,Y,12,0000,expirationYear,BLANK,Invalid card expiry year",
    })
    @DisplayName("each faulted attribute is answered 400 with its reference sentence and field state")
    void eachFaultedAttributeCarriesItsReferenceSentence(String name, String status, String month,
            String year, String field, String state, String sentence) throws Exception {

        when(this.cards.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, status, month, year, STORED_VERSION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='" + field + "')].message")
                        .value(org.hamcrest.Matchers.hasItem(sentence)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='" + field + "')].state")
                        .value(org.hamcrest.Matchers.hasItem(state)));

        verify(this.cards, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(Card.class));
    }

    /**
     * Asserts every faulted attribute is reported in ONE response when the four faults agree on a state.
     *
     * <p>Assumptions: the accumulation is the reference's own behaviour. Each of the four gates runs
     * unconditionally at {@code app/cbl/COCRDUPC.cbl:698-708} and each gate's every branch names only its own
     * exit, so four flags can be set from one submission. A declarative layer stops at whatever it validates
     * first, which is why this property could not survive with the constraints in place.</p>
     *
     * <p>Assumptions: all four attributes are submitted BLANK rather than in a mix of failing states, and the
     * reason is the shape of the shared refusal rather than a limitation of the gates. That type carries ONE
     * state for every member it names, so
     * {@code CardUpdateService.AttributeStates.fieldsSharingSingleErrorState} names several attributes only
     * when their states agree and names none when they differ -- labelling a mixed set with one state would
     * misdirect the blank marker onto an attribute that was not blank. The mixed case is asserted separately
     * below, so both halves of that rule are covered rather than one of them looking like a defect.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("four attributes faulting in the same state are reported in one response")
    void allFourFaultedAttributesAreReportedTogether() throws Exception {
        when(this.cards.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", " ", "00", "0000", STORED_VERSION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(4))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "embossedName", "activeStatus", "expirationMonth", "expirationYear")))
                .andExpect(jsonPath("$.fieldErrors[*].state").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.equalTo(FieldValidationFlag.BLANK.name()))));
    }

    /**
     * Asserts a submission whose faults DISAGREE on a state names the first faulted attribute with its own
     * state, rather than labelling several attributes with a state that is not theirs.
     *
     * <p>Assumptions: the precedence is the reference program's own and it is first-fault-wins in the gates'
     * evaluation order -- every sentence write in the field stage is wrapped in {@code IF WS-RETURN-MSG-OFF}
     * at {@code app/cbl/COCRDUPC.cbl} lines 816, 833, 855, 868, 888, 903, 921 and 939, and the gates are
     * performed name, status, month, year at lines 698 to 708 -- so the name's sentence wins over the
     * status's. The submission here faults the name BLANK and the other three NOT_OK, so the two rules are
     * separable: precedence chooses the name, and the single-state rule stops the other three being reported
     * as blank.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("faults disagreeing on a state report the first attribute with its own state")
    void mixedFaultStatesReportTheFirstAttributeOnly() throws Exception {
        when(this.cards.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", "X", "13", "1949", STORED_VERSION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("embossedName"))
                .andExpect(jsonPath("$.fieldErrors[0].state").value(FieldValidationFlag.BLANK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(CardUpdateService.MESSAGE_NAME_NOT_PROVIDED));
    }

    /**
     * Asserts the published state member still tells a blank attribute apart from an unacceptable one, which
     * is what a renderer draws the reference's asterisk from.
     *
     * <p>Assumptions: the STATE is asserted rather than the derived marker, because the state is what crosses
     * the wire. {@code ApiError.FieldError} declares three components and the marker is not one of them: it is
     * a derived accessor, so no contract publishes it and no response body carries it. A client reproduces the
     * literal asterisk the reference writes into an empty field at {@code app/cbl/COCRDUPC.cbl:1263-1272} by
     * reading the state and applying the same derivation, which is the mapping the shared flag type owns.</p>
     *
     * <p>Assumptions: two requests are needed rather than one, because a single refusal carries one state for
     * every member it names -- the rule recorded on the two cases above. Asserting the two states from two
     * submissions is therefore the only way to show they remain distinguishable at the boundary a client
     * reads, which is the whole reason the declarative constraints were withdrawn.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the published state distinguishes a blank attribute from an unacceptable one")
    void thePublishedStateDistinguishesBlankFromUnacceptable() throws Exception {
        when(this.cards.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", STORED_STATUS, "12", "2026", STORED_VERSION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("embossedName"))
                .andExpect(jsonPath("$.fieldErrors[0].state").value(FieldValidationFlag.BLANK.name()));

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(STORED_NAME, "X", "12", "2026", STORED_VERSION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("activeStatus"))
                .andExpect(jsonPath("$.fieldErrors[0].state").value(FieldValidationFlag.NOT_OK.name()));

        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
    }

    /**
     * Asserts a value one character past its declared width is still refused by the declarative layer.
     *
     * <p>Assumptions: the width is the one constraint the request body retains, because the reference field
     * physically could not hold more characters and therefore has no sentence for an over-long value. The
     * response is a 400 without a per-field reference sentence, which is the correct shape for a fault the
     * reference has no words for -- and asserting it here is what stops "the service owns validation" from
     * being read as "nothing is validated at the boundary".</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an over-width attribute is refused at the boundary, with no reference sentence")
    void anOverWidthAttributeIsRefusedAtTheBoundary() throws Exception {
        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A".repeat(51), "Y", "12", "2026", STORED_VERSION)))
                .andExpect(status().isBadRequest());

        verify(this.cards, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(Card.class));
    }

    /**
     * Asserts a stale revision is answered 409 over HTTP even when the submission equals the stored row.
     *
     * <p>Assumptions: this is the ordering defect stated as a response status. The service used to answer the
     * no-change outcome before consulting the revision, so a caller whose view of the row was stale -- and
     * whose submission matched the row only because another writer had already made that change -- received
     * 200 and was told its own edit had been accepted. The reference refuses it through
     * {@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:1498-1511}.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a stale revision is answered 409 even when the submission changes nothing")
    void aStaleRevisionIsAnsweredConflictEvenWhenNothingWouldChange() throws Exception {
        when(this.cards.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

        this.mockMvc.perform(put(CardController.CARD_PATH, selector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(STORED_NAME, STORED_STATUS, "12", "2026", STALE_VERSION)))
                .andExpect(status().isConflict());

        verify(this.cards, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(Card.class));
    }

    /**
     * Builds the stored row every case reads.
     *
     * @return a card at the stored values and revision, never {@code null}
     */
    private Card storedCard() {
        return new Card(CARD_NUMBER, ACCOUNT_ID, storedVerificationValue(), STORED_NAME, STORED_EXPIRY,
                STORED_STATUS);
    }

    /**
     * Builds an enciphered verification value in the framing the domain type requires.
     *
     * <p>Assumptions: the envelope is assembled through the domain type rather than handed a raw array of
     * the right length, because the type checks its own framing and an array that merely had the right size
     * would be refused at construction -- so the case would fail for a reason unrelated to its subject. No
     * part of the value is a real verification value, and nothing here decrypts it: this route neither reads
     * nor renders it.</p>
     *
     * @return the enciphered holder, never {@code null}
     */
    private static EncryptedCvv storedVerificationValue() {
        byte[] dataKey = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] initialisationVector = new byte[EncryptedCvv.INITIALISATION_VECTOR_LENGTH];
        byte[] ciphertext = new byte[EncryptedCvv.MIN_CIPHERTEXT_LENGTH];
        Arrays.fill(initialisationVector, (byte) 9);
        Arrays.fill(ciphertext, (byte) 7);
        return EncryptedCvv.wrap(dataKey, initialisationVector, ciphertext);
    }

    /**
     * Seals the opaque selector the route addresses the stored card by.
     *
     * @return the selector, never {@code null}
     */
    private String selector() {
        return this.mapper.toSummary(storedCard()).key();
    }

    /**
     * Renders one request body.
     *
     * <p>Assumptions: the body is assembled as text rather than through a serializer, so that a case can send
     * a value the record's own components could not hold -- an over-width string, for instance -- and so that
     * what travels is visible in the case that sends it.</p>
     *
     * @param name the embossed name member
     * @param status the active status member
     * @param month the expiry month member
     * @param year the expiry year member
     * @param version the revision member
     * @return the JSON body, never {@code null}
     */
    private static String body(String name, String status, String month, String year, int version) {
        return """
                {"embossedName":"%s","activeStatus":"%s","expirationMonth":"%s",\
                "expirationYear":"%s","version":%d}"""
                .formatted(name, status, month, year, version);
    }

    /**
     * Asserts the stored fixture is self-consistent, so a case reading it is reading what it intends to.
     */
    @Test
    @DisplayName("the stored fixture carries the values the cases assume")
    void theStoredFixtureCarriesTheValuesTheCasesAssume() {
        Card stored = storedCard();
        assertThat(stored.getCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(stored.getVersion()).isEqualTo(STORED_VERSION);
        assertThat(stored.getEmbossedName()).isEqualTo(STORED_NAME);
        assertThat(stored.getActiveStatus()).isEqualTo(STORED_STATUS);
    }
}
