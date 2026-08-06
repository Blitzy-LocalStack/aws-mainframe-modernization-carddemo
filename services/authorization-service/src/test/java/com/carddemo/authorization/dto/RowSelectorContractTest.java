package com.carddemo.authorization.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.common.web.CursorToken;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that an authorization row is addressed only by an opaque sealed selector, in both directions.
 *
 * <p>The subject is the communication-area key table the migration retires:
 * {@code MOVE PA-AUTHORIZATION-KEY TO CDEMO-CPVS-AUTH-KEYS(n)} at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} lines 545, 557, 569, 581 and 593, and the mark
 * that moves one of those slots into {@code CDEMO-CPVS-PAU-SELECTED} across lines 288 to 305. With no
 * session to hold that table, the key travels in the summary response and comes back on the fraud request,
 * and both ends are asserted here: the response refuses to publish anything but a sealed token, and the
 * request refuses to accept anything but one.</p>
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
        String selector = sealer.seal("pending-auth-summary:11111111111", RAW_ROW_KEY);

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
        String selector = sealer.seal("pending-auth-summary:11111111111", RAW_ROW_KEY);

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
     * The fraud request accepts a sealed selector and the two commands the baseline admits.
     *
     * <p>Assumptions: {@code F} and {@code R} are the two condition names at
     * {@code cbl/COPAUS2C.cbl} lines 81 and 82, and nothing else is a command there.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request accepts a sealed selector with F or R")
    void theFraudRequestAcceptsASealedSelector() {
        String selector = sealer.seal("pending-auth-detail:11111111111", RAW_ROW_KEY);

        assertTrue(validator.validate(new FraudMarkRequest(selector, "F")).isEmpty());
        assertTrue(validator.validate(new FraudMarkRequest(selector, "R")).isEmpty());
        assertEquals(1, validator.validate(new FraudMarkRequest(selector, "S")).size());
    }

    /**
     * The fraud request refuses a caller-assembled key in place of a selector.
     *
     * <p>Assumptions: this is the inbound half of the same property. The earlier contract took an account
     * identifier and two key numbers, so the row a client marked as fraudulent was chosen by the client
     * rather than by the page it had been shown.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request refuses a caller-assembled key")
    void theFraudRequestRefusesARawKey() {
        assertEquals(1, validator.validate(new FraudMarkRequest(RAW_ROW_KEY, "F")).size());
    }

    /**
     * The fraud request requires both of its components.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request requires both the selector and the action")
    void theFraudRequestRequiresBothComponents() {
        assertEquals(2, validator.validate(new FraudMarkRequest(null, null)).size());
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
        String binding = "pending-auth-detail:11111111111";
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
