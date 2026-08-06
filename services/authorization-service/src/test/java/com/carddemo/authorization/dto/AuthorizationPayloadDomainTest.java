package com.carddemo.authorization.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.service.AuthorizationDecisionService;
import com.carddemo.authorization.service.AuthorizationDecisionService.DeclineReason;
import com.carddemo.common.money.Money;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the declared value domains of the queue payloads and the detail response's reason composition.
 *
 * <p>The subjects are {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} lines 19 to 36 and
 * {@code cpy/CCPAURLY.cpy} lines 19 to 24, which declare the eighteen request and six reply fields, and
 * {@code cbl/COPAUS1C.cbl} lines 320 to 327, which compose the twenty-character reason a detail screen
 * shows.</p>
 */
class AuthorizationPayloadDomainTest {

    /**
     * The factory backing the engine, held so it can be closed after the class has run.
     */
    private static ValidatorFactory factory;

    /**
     * The validation engine the declared constraints are applied through.
     *
     * <p>Assumptions: the engine comes from the default provider rather than from a Spring context,
     * because the subject is the constraint declarations themselves and a context would add binding
     * behaviour that is not under test here.</p>
     */
    private static Validator validator;

    /**
     * Builds the validation engine once for the class.
     */
    @BeforeAll
    static void buildValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the validation engine's resources.
     */
    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * Every reply component is required, so an incomplete reply violates its own contract.
     *
     * <p>Assumptions: six violations are expected from one instance with six nulls, which is what proves
     * the requiredness is declared on every component rather than on the first one a reader checked.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all six reply components are required")
    void everyReplyComponentIsRequired() {
        AuthorizationReplyPayload empty =
                new AuthorizationReplyPayload(null, null, null, null, null, null);

        assertEquals(6, validator.validate(empty).size());
    }

    /**
     * The three character components accept any character their copybook picture admits.
     *
     * <p>Assumptions: all three are {@code PIC X} at {@code CCPAURLY.cpy} lines 19 to 21, so a
     * non-numeric value is legitimate. The earlier digits-only expressions refused exactly these values
     * while the reference program accepted them, so a letter in each is the assertion that closes
     * that.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("PIC X reply components admit non-digit characters")
    void theCharacterComponentsAdmitNonDigits() {
        AuthorizationReplyPayload payload = new AuthorizationReplyPayload("CARD-TOKEN-A0001",
                "TXNREF-000000AB", "IDC-01", "00", "0000", Money.of("100.99"));

        assertTrue(validator.validate(payload).isEmpty());
    }

    /**
     * A value shorter than its declared width is accepted, because the codec pads it.
     *
     * <p>Assumptions: this is the drift the exact-width constraints caused. {@code CsvAuthCodec} pads a
     * short value to its declared width when it assembles the payload, so a three-character
     * identification code reaches the wire as six characters; a boundary that refused it disagreed with
     * the codec about the same contract.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a short reply component is accepted because the codec pads it")
    void aShortComponentIsAccepted() {
        AuthorizationReplyPayload payload = new AuthorizationReplyPayload("4111111111111111",
                "TXN01", "IDC", "05", "4100", Money.ZERO);

        assertTrue(validator.validate(payload).isEmpty());
    }

    /**
     * A value longer than its declared width is refused.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an over-wide reply component is refused")
    void anOverWideComponentIsRefused() {
        AuthorizationReplyPayload payload = new AuthorizationReplyPayload("41111111111111119",
                "TXN000000000001", "104530", "00", "0000", Money.of("100.99"));

        assertEquals(1, validator.validate(payload).size());
    }

    /**
     * The reply's reason domain admits exactly the eight literals the decision table can produce.
     *
     * <p>Assumptions: this is the anti-drift assertion for a constant that has to be duplicated. A
     * Jakarta pattern must be a compile-time constant, so the reason table exists both as an enumeration
     * in the service package and as an expression on this payload; asserting the payload against the
     * enumeration is what keeps the copy honest. A ninth value is refused, so the domain is closed rather
     * than merely inclusive.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply reason domain matches the decision table member for member")
    void theReasonDomainMatchesTheDecisionTable() {
        assertTrue(validator.validate(replyWithReason(
                AuthorizationDecisionService.RESP_REASON_APPROVED)).isEmpty());
        for (DeclineReason reason : DeclineReason.values()) {
            assertTrue(validator.validate(replyWithReason(reason.responseReason())).isEmpty(),
                    "reason " + reason.responseReason() + " should be admitted by the payload domain");
        }

        assertEquals(1, validator.validate(replyWithReason("1234")).size());
    }

    /**
     * The reply's response-code domain admits the two literals the decision emits and nothing else.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply response-code domain is closed to 00 and 05")
    void theResponseCodeDomainIsClosed() {
        assertTrue(validator.validate(replyWithCode(
                AuthorizationDecisionService.RESP_CODE_APPROVED)).isEmpty());
        assertTrue(validator.validate(replyWithCode(
                AuthorizationDecisionService.RESP_CODE_DECLINED)).isEmpty());

        assertEquals(1, validator.validate(replyWithCode("01")).size());
    }

    /**
     * A negative request amount is outside the record's domain and is reported as one violation.
     *
     * <p>Assumptions: the predicate and the constraint are asserted together, because they are one rule
     * with two call sites -- the queue consumer calls the predicate directly and the validator reaches it
     * through the annotation. Asserting only one of the two would leave the other free to diverge.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a negative transaction amount is outside the record's domain")
    void aNegativeAmountIsOutsideTheRecordDomain() {
        assertFalse(AuthorizationRequestPayload.isAmountWithinRecordDomain(Money.of("-0.01")));
        assertEquals(1, validator.validate(requestWithAmount(Money.of("-0.01"))).size());
    }

    /**
     * Zero and the record's greatest magnitude are both inside the domain, inclusively.
     *
     * <p>Assumptions: the upper bound is asserted at the boundary rather than beyond it, because beyond it
     * is unreachable: {@link Money} refuses a magnitude greater than its own maximum at construction, so
     * no {@link Money} outside the domain can be built to test with. That is why the predicate's upper
     * comparison is documented as defence for a future carrier rather than as a reachable path.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("zero and the greatest representable amount are both inside the domain")
    void theDomainBoundsAreInclusive() {
        assertTrue(AuthorizationRequestPayload.isAmountWithinRecordDomain(Money.ZERO));
        assertTrue(AuthorizationRequestPayload.isAmountWithinRecordDomain(
                Money.of(Money.MAX_MAGNITUDE)));
        assertTrue(validator.validate(requestWithAmount(Money.ZERO)).isEmpty());
    }

    /**
     * A null amount is left to the requiredness constraint rather than reported twice.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a null amount yields one violation, from requiredness alone")
    void aNullAmountIsReportedOnce() {
        assertTrue(AuthorizationRequestPayload.isAmountWithinRecordDomain(null));
        assertEquals(1, validator.validate(requestWithAmount(null)).size());
    }

    /**
     * The composed reason fills exactly twenty positions and truncates the description's sixteenth.
     *
     * <p>Assumptions: the description used here is a full sixteen characters, which is the only input that
     * exhibits the truncation. {@code MOVE DECL-DESC TO AUTHRSNO(6:)} at {@code cbl/COPAUS1C.cbl} line 327
     * targets fifteen positions, so the sixteenth character is discarded on the way in and the result is
     * the code, the separator and fifteen characters of description.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the composed reason is 20 characters and drops the description's sixteenth")
    void theComposedReasonTruncatesAtTwenty() {
        String composed = PendingAuthDetailResponse.composeAuthResponseReason("4100",
                "ABCDEFGHIJKLMNOP");

        assertEquals(20, composed.length());
        assertEquals("4100-ABCDEFGHIJKLMNO", composed);
    }

    /**
     * A short code and a short description are space-padded so the separator keeps position five.
     *
     * <p>Assumptions: the no-entry path of the reference program is the case that matters here. Lines 320
     * to 322 write {@code '9999'}, the separator and {@code 'ERROR'} into the same twenty positions, so the
     * description's ten unreached positions are spaces and the field is still twenty characters.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the no-entry composition pads to 20 with the separator still in position five")
    void theComposedReasonPadsShortInputs() {
        String composed = PendingAuthDetailResponse.composeAuthResponseReason("9999", "ERROR");

        assertEquals(20, composed.length());
        assertEquals('-', composed.charAt(4));
        assertEquals("9999-ERROR          ", composed);
    }

    /**
     * A composed reason of twenty characters is accepted by the response, and twenty-one is refused.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the detail response accepts 20 reason characters and refuses 21")
    void theDetailResponseCapsTheReasonAtTwenty() {
        assertTrue(validator.validate(detailWithReason("4100-ABCDEFGHIJKLMNO")).isEmpty());
        assertEquals(1, validator.validate(detailWithReason("4100-ABCDEFGHIJKLMNOP")).size());
    }

    /**
     * The composer refuses a null on either argument rather than composing the word "null" into a screen.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the composer refuses a null code or description")
    void theComposerRefusesNulls() {
        assertThrows(NullPointerException.class,
                () -> PendingAuthDetailResponse.composeAuthResponseReason(null, "ERROR"));
        assertThrows(NullPointerException.class,
                () -> PendingAuthDetailResponse.composeAuthResponseReason("9999", null));
    }

    /**
     * Builds a well-formed reply carrying the supplied reason.
     *
     * @param authResponseReason the reason to place on the reply
     * @return the reply payload, never {@code null}
     */
    private AuthorizationReplyPayload replyWithReason(String authResponseReason) {
        return new AuthorizationReplyPayload("4111111111111111", "TXN000000000001", "104530", "00",
                authResponseReason, Money.of("100.99"));
    }

    /**
     * Builds a well-formed reply carrying the supplied response code.
     *
     * @param authResponseCode the code to place on the reply
     * @return the reply payload, never {@code null}
     */
    private AuthorizationReplyPayload replyWithCode(String authResponseCode) {
        return new AuthorizationReplyPayload("4111111111111111", "TXN000000000001", "104530",
                authResponseCode, "0000", Money.of("100.99"));
    }

    /**
     * Builds a well-formed request carrying the supplied amount.
     *
     * @param amount the amount to place on the request, which may be {@code null}
     * @return the request payload, never {@code null}
     */
    private AuthorizationRequestPayload requestWithAmount(Money amount) {
        return new AuthorizationRequestPayload("250801", "104530", "4111111111111111", "0100",
                "1230", "0100", "POS001", "000000", amount, "5411", "840", "05",
                "MERCHANT0000001", "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000",
                "TXN000000000001");
    }

    /**
     * Builds a detail response whose only populated component is the composed reason.
     *
     * <p>Assumptions: every other component is left absent, which the response admits, so the case
     * isolates the one constraint it is about. A fixture that populated all of them would fail for a
     * reason unrelated to the width under test.</p>
     *
     * @param authResponseReason the composed reason to place on the response
     * @return the detail response, never {@code null}
     */
    private PendingAuthDetailResponse detailWithReason(String authResponseReason) {
        return new PendingAuthDetailResponse(null, null, null, null, null, null, null, null, null,
                null, authResponseReason, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
