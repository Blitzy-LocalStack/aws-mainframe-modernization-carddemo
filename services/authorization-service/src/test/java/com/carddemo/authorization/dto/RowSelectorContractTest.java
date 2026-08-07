package com.carddemo.authorization.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.common.web.CursorToken;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts how an authorization row is addressed on the way out and on the way back in.
 *
 * <p>The subject is the communication-area key table the migration retires:
 * {@code MOVE PA-AUTHORIZATION-KEY TO CDEMO-CPVS-AUTH-KEYS(n)} at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} lines 545, 557, 569, 581 and 593, and the mark
 * that moves one of those slots into {@code CDEMO-CPVS-PAU-SELECTED} across lines 288 to 305. With no
 * session to hold that table, the key travels in the summary response and comes back on the fraud
 * request, and the two ends are asserted here under the two different rules that govern them.</p>
 *
 * <p>Assumptions: the two ends are deliberately NOT symmetric, and asserting one rule for both would
 * assert the wrong thing. The summary response publishes an opaque sealed token per row, so what is
 * asserted outbound is that it never publishes a raw row address. Inbound, the sealed token travels as
 * the operation's path selector and is the row's ONLY address, so what is asserted is that the fraud
 * request body declares no row address of its own and carries the action alone. The baseline's
 * request-direction communication-area fields at {@code cbl/COPAUS2C.cbl} lines 75, 76 and 80 are
 * therefore not all reproduced on the body: only line 80 is.</p>
 *
 * <p>Assumptions: the key material below is a fixed byte pattern of the minimum accepted length, not a
 * secret. A test that read real key material would couple these assertions to a deployment, and the
 * property under test -- that a raw key is not a token -- does not depend on which key sealed it.</p>
 */
class RowSelectorContractTest {

    /**
     * The raw row key the baseline holds in one slot of its key table: account, date and time joined.
     *
     * <p>Assumptions: this is exactly the value that must NOT be publishable as a selector. It is what a
     * caller could edit into any other row's address, which is the failure the sealed shape prevents.</p>
     */
    private static final String RAW_ROW_KEY = "11111111111:26217:104530123";

    /**
     * The account component of the row addressed throughout, as eleven digits.
     *
     * <p>Assumptions: this is the first of the three parts joined in {@code RAW_ROW_KEY} above, held
     * separately because it also scopes the token binding, so one edit changes both the row sealed and
     * the subject it is sealed for and the two cannot drift apart.</p>
     */
    private static final String ROW_ACCOUNT_ID = "11111111111";

    /**
     * The single component the fraud request body is expected to declare.
     *
     * <p>Assumptions: naming it here rather than inline is what lets one assertion cover both halves of
     * the contract -- that the component exists and that no second component sits beside it.</p>
     *
     * <p>Assumptions: the member is named for the action alone and not for the action of a fraud
     * operation. The resource it is sent to is already the fraud state of an authorization, so a
     * {@code fraud} qualifier inside the body would restate in the member name what the route says --
     * and the shared advice keys a rejected member by its record component name, so that restatement
     * would reach every client as part of a per-field key.</p>
     */
    private static final String ACTION_COMPONENT = "action";

    /**
     * The factory backing the engine, held so it can be closed after the class has run.
     */
    private static ValidatorFactory factory;

    /**
     * The validation engine the fraud request's constraints are applied through.
     */
    private static Validator validator;

    /**
     * The sealer used to mint a legitimate selector.
     */
    private static CursorToken sealer;

    /**
     * Builds the validation engine and the sealer once for the class.
     */
    @BeforeAll
    static void buildFixtures() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x5A);
        sealer = new CursorToken(keyMaterial, Duration.ofMinutes(5));
    }

    /**
     * Releases the validation engine's resources.
     */
    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * A sealed selector is publishable on a summary row.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a sealed token is accepted as a summary row selector")
    void aSealedTokenIsAcceptedAsASelector() {
        String selector = sealer.seal("pending-auth-summary:" + ROW_ACCOUNT_ID, RAW_ROW_KEY);

        PendingAuthSummaryResponse response = responseWithSelectors(selector, null, null, null, null);

        assertEquals(selector, response.row1Selector());
        assertTrue(CursorToken.hasSealedShape(response.row1Selector()));
    }

    /**
     * The raw row key cannot be published as a selector.
     *
     * <p>Assumptions: this is the assertion the whole design rests on. Publishing the raw key would hand a
     * client three joined identifiers it could edit, so it could open or mark any authorization on any
     * account by arithmetic on a value this service handed it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a raw composite key is refused as a summary row selector")
    void aRawKeyIsRefusedAsASelector() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> responseWithSelectors(RAW_ROW_KEY, null, null, null, null));

        assertTrue(refusal.getMessage().contains("row1Selector"));
        assertTrue(refusal.getMessage().contains("sealed"));
    }

    /**
     * The refusal names the offending row rather than the first row, and never quotes the value.
     *
     * <p>Assumptions: the message is asserted not to carry the value because the value that fails this
     * check is either a raw key, which is three identifiers a log should not hold, or a corrupted token,
     * whose text tells a reader nothing its length does not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the refusal names the offending row and withholds the value")
    void theRefusalNamesTheOffendingRow() {
        String selector = sealer.seal("pending-auth-summary:" + ROW_ACCOUNT_ID, RAW_ROW_KEY);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> responseWithSelectors(selector, selector, RAW_ROW_KEY, null, null));

        assertTrue(refusal.getMessage().contains("row3Selector"));
        assertTrue(refusal.getMessage().contains(String.valueOf(RAW_ROW_KEY.length())));
        assertTrue(!refusal.getMessage().contains(RAW_ROW_KEY));
    }

    /**
     * An absent selector is accepted, because a page of fewer than five rows leaves the rest unset.
     *
     * <p>Assumptions: a blank selector is absent too, on the baseline's own representation of an unused
     * key slot, which it clears to spaces.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent or blank selector is accepted for an unpopulated row")
    void anAbsentSelectorIsAccepted() {
        PendingAuthSummaryResponse allAbsent =
                responseWithSelectors(null, null, null, null, null);
        PendingAuthSummaryResponse blank = responseWithSelectors("   ", null, null, null, null);

        assertEquals(null, allAbsent.row5Selector());
        assertEquals("   ", blank.row1Selector());
    }

    /**
     * The fraud request accepts either of the two commands the baseline admits.
     *
     * <p>Assumptions: {@code F} and {@code R} are the two condition names at
     * {@code cbl/COPAUS2C.cbl} lines 81 and 82, and nothing else is a command there. {@code S} is
     * asserted as a rejection specifically because it is the SUCCESS value of the adjacent
     * {@code WS-FRD-UPDATE-STATUS} field at line 84, so a caller or a mapper that confused the two
     * one-character fields would send it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request accepts F or R and refuses S")
    void theFraudRequestAcceptsTheTwoCommands() {
        assertTrue(validator.validate(new FraudMarkRequest("F")).isEmpty());
        assertTrue(validator.validate(new FraudMarkRequest("R")).isEmpty());
        assertEquals(1, validator.validate(new FraudMarkRequest("S")).size());
    }

    /**
     * The action character of the request is not interchangeable with the outcome flag of the reply.
     *
     * <p>Assumptions: this is the hazard the two records are documented against, and it is asserted
     * rather than only described. {@code WS-FRD-ACTION} at {@code cbl/COPAUS2C.cbl} line 80 reads
     * {@code F} as REPORT FRAUD at line 81, while {@code WS-FRD-UPDATE-STATUS} at line 83 reads the same
     * character as UPDATE FAILED at line 85. The two fields are adjacent inside one group item, so the
     * collision is easy to read past; what makes it harmless is that the two domains do not overlap at
     * all, and that is what this asserts. Swapping the two values across the two records leaves each one
     * invalid, so a mapper that crossed them cannot pass validation.</p>
     *
     * <p>Refactoring Rationale: the response half of this assertion was inverted and is corrected here.
     * It previously asserted that {@code 'F'} is VALID on the response, which was true of the record at
     * the time and contradicted the published contract, whose {@code updateStatus} is a {@code const} of
     * {@code 'S'}. The response now admits the success value alone -- a failed write is answered with a
     * non-2xx problem body rather than with a 2xx that says it failed -- so the two domains are disjoint
     * in both directions: {@code 'F'} and {@code 'R'} are request-only and {@code 'S'} is response-only.
     * Asserting the disjointness in both directions is what makes a crossed mapper impossible to write
     * rather than merely unlikely.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the request action and the reply outcome flag are not interchangeable")
    void theActionAndTheOutcomeFlagAreNotInterchangeable() {
        assertTrue(validator.validate(
                new FraudMarkResponse(FraudMarkResponse.UPDATE_STATUS_SUCCESS, "UPDT SUCCESS"))
                .isEmpty());
        assertEquals(1, validator.validate(new FraudMarkRequest("S")).size());
        assertEquals(1, validator.validate(new FraudMarkResponse("F", "UPDT SUCCESS")).size());
        assertEquals(1, validator.validate(new FraudMarkResponse("R", null)).size());
    }

    /**
     * The fraud request body declares no row address, so no row address can be sent on it.
     *
     * <p>Assumptions: this is the structural half of the inbound contract and it is asserted by
     * reflection because it is a property of the declaration rather than of any one value. A body that
     * named the row as well as the action would admit two hazards the sealed path selector closes: a
     * caller could address a row it was never shown, and it could send the nines-complemented storage
     * forms of the key components -- 99999 and 999999999, the constants subtracted from at
     * {@code cbl/COPAUA0C.cbl} lines 874 and 875 -- which are not the decoded values
     * {@code db/migration/V1__authorization.sql} stores. With one component there is nothing to
     * disagree with the selector and nothing to bound.</p>
     *
     * <p>Refactoring Rationale: the two temporal assertions this test once carried are WITHDRAWN rather
     * than rewritten. They asserted that an out-of-range Julian date and an out-of-range time key were
     * each refused and attributed to the key member at fault -- and both members have been removed from
     * the body, so there is no member left for such a refusal to be attributed to. Asserting the
     * declaration instead is the stronger claim in any case: a bound on a member can be relaxed, whereas
     * a member that does not exist cannot carry a value at all. The two constants those assertions read
     * from went with them, because a constant no assertion reads is a claim about a rule the code no
     * longer has.</p>
     *
     * <p>Assumptions: the third assertion sends the whole raw row key as the action. It is refused by the
     * action's own one-character domain, which is the remaining evidence that a row address cannot travel
     * on this body even as the value of the one member it does declare.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request body carries the action alone and no row address")
    void theFraudRequestDeclaresNoRowAddress() {
        RecordComponent[] components = FraudMarkRequest.class.getRecordComponents();

        assertEquals(1, components.length);
        assertEquals(ACTION_COMPONENT, components[0].getName());
        assertEquals(1, validator.validate(new FraudMarkRequest(RAW_ROW_KEY)).size());
    }

    /**
     * The fraud request requires its one component.
     *
     * <p>Assumptions: one violation is expected rather than none because requiredness is declared
     * separately from the character domain, and a null reaches only the former.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request requires its action component")
    void theFraudRequestRequiresItsActionComponent() {
        assertEquals(1, validator.validate(new FraudMarkRequest(null)).size());
    }

    /**
     * A selector this service issued recovers the row key it was issued for.
     *
     * <p>Assumptions: this is what makes the opaque selector usable rather than merely opaque. The service
     * that holds the key material opens the token under the same binding it sealed it with and gets the
     * three key parts back unchanged; a token opened under a different binding is refused, which is what
     * scopes a selector to the operation and the subject it was issued for.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a selector round-trips to its row key and is bound to its issuing context")
    void aSelectorRoundTripsToItsRowKey() {
        String binding = "pending-auth-detail:" + ROW_ACCOUNT_ID;
        String selector = sealer.seal(binding, RAW_ROW_KEY);

        assertEquals(RAW_ROW_KEY, sealer.open(binding, selector));
        assertThrows(CursorToken.InvalidCursorException.class,
                () -> sealer.open("pending-auth-detail:22222222222", selector));
    }

    /**
     * Builds a summary response whose only populated components are the five selectors.
     *
     * <p>Assumptions: the 62 map-derived components are left absent, which the response admits, so each
     * case isolates the one family of constraints it is about. Populating them would fail for reasons
     * unrelated to the selectors under test, and the absence is the baseline's own representation of a
     * screen row that carries nothing.</p>
     *
     * @param row1 the row 1 selector, which may be {@code null}
     * @param row2 the row 2 selector, which may be {@code null}
     * @param row3 the row 3 selector, which may be {@code null}
     * @param row4 the row 4 selector, which may be {@code null}
     * @param row5 the row 5 selector, which may be {@code null}
     * @return the response, never {@code null}
     */
    private PendingAuthSummaryResponse responseWithSelectors(String row1, String row2, String row3,
            String row4, String row5) {
        return new PendingAuthSummaryResponse(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null,
                row1, row2, row3, row4, row5);
    }
}
