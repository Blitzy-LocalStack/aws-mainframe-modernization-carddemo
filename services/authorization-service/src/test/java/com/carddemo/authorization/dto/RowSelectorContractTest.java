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
 * asserted outbound is that it never publishes a raw row address. The fraud request carries the three
 * decoded primary-key columns of {@code authorization.pending_auth_detail} together with the customer
 * identifier and the action, exactly as the request-direction fields of the baseline communication area
 * at {@code cbl/COPAUS2C.cbl} lines 75, 76 and 80 carry them, so what is asserted inbound is that each
 * component is held to its own declared domain. The path selector on the operation is what the outbound
 * token is for; the service compares the triple it opens from that token against the triple in the body
 * and refuses a disagreement, which is a service-layer property and is not asserted from here.</p>
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
     * separately so that the same row is addressed by both the outbound and the inbound assertions and a
     * failure cannot be explained away as two cases having used two different rows.</p>
     */
    private static final String ROW_ACCOUNT_ID = "11111111111";

    /**
     * The customer the fraud row is filed against, as nine digits.
     *
     * <p>Assumptions: it is not part of any key. {@code cbl/COPAUS2C.cbl} line 139 moves the
     * caller-supplied value into the fraud row's customer column, so it is required on the request while
     * contributing nothing to addressing the row.</p>
     */
    private static final String ROW_CUSTOMER_ID = "000000011";

    /**
     * The decoded Julian authorization date of the row addressed, as a day of year in a two-digit year.
     */
    private static final Integer ROW_AUTH_DATE_KEY = 26217;

    /**
     * The decoded millisecond-of-day authorization time of the row addressed, 10:45:30 and 123
     * milliseconds.
     */
    private static final Integer ROW_AUTH_TIME_KEY = 104530123;

    /**
     * The five-digit constant a nines-complemented authorization date is subtracted from.
     *
     * <p>Assumptions: this is not a date. It is the constant at {@code cbl/COPAUA0C.cbl} line 874, and a
     * value equal to it can only have arrived by carrying the storage representation instead of the
     * decoded one.</p>
     */
    private static final Integer NINES_COMPLEMENT_DATE = 99999;

    /**
     * The nine-digit constant a nines-complemented authorization time is subtracted from.
     *
     * <p>Assumptions: as with the date constant above, this is the constant at
     * {@code cbl/COPAUA0C.cbl} line 875 and not a time of day.</p>
     */
    private static final Integer NINES_COMPLEMENT_TIME = 999999999;

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
     * The fraud request accepts the row key with either of the two commands the baseline admits.
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
    @DisplayName("the fraud request accepts the row key with F or R and refuses S")
    void theFraudRequestAcceptsTheTwoCommands() {
        assertTrue(validator.validate(fraudRequestWith(ROW_ACCOUNT_ID, "F")).isEmpty());
        assertTrue(validator.validate(fraudRequestWith(ROW_ACCOUNT_ID, "R")).isEmpty());
        assertEquals(1, validator.validate(fraudRequestWith(ROW_ACCOUNT_ID, "S")).size());
    }

    /**
     * The action character of the request is not interchangeable with the outcome flag of the reply.
     *
     * <p>Assumptions: this is the hazard the two records are documented against, and it is asserted
     * rather than only described. {@code WS-FRD-ACTION} at {@code cbl/COPAUS2C.cbl} line 80 reads
     * {@code F} as REPORT FRAUD at line 81, while {@code WS-FRD-UPDATE-STATUS} at line 83 reads the same
     * character as UPDATE FAILED at line 85. The two fields are adjacent inside one group item, so the
     * collision is easy to read past; what makes it harmless is that the two domains do not overlap
     * beyond that character, and that is what this asserts. Swapping the two values across the two
     * records leaves each one invalid, so a mapper that crossed them cannot pass validation.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the request action and the reply outcome flag are not interchangeable")
    void theActionAndTheOutcomeFlagAreNotInterchangeable() {
        assertTrue(validator.validate(new FraudMarkResponse("F", "UPDT SUCCESS")).isEmpty());
        assertEquals(1, validator.validate(fraudRequestWith(ROW_ACCOUNT_ID, "S")).size());
        assertEquals(1, validator.validate(new FraudMarkResponse("R", null)).size());
    }

    /**
     * The fraud request holds each key component to its own declared domain.
     *
     * <p>Assumptions: the values refused here are the three ways a wrong value reaches this boundary
     * looking plausible. A raw joined row address is refused because the account component is a
     * fixed-width digit string and a colon is not a digit. A Julian date of 99999 is refused because that
     * is the five-nine constant a nines complement is subtracted FROM rather than a day of any year, and
     * the complement is the representation {@code db/migration/V1__authorization.sql} deliberately does
     * not store. A millisecond time of 999999999 is refused for the same reason at nine digits. Bounding
     * each component at its domain rather than at its width is what turns those three into rejections
     * instead of into a write against a different row.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request refuses a raw joined key and either nines complement")
    void theFraudRequestRefusesValuesOutsideItsDomains() {
        assertEquals(1, validator.validate(fraudRequestWith(RAW_ROW_KEY, "F")).size());
        assertEquals(1, validator.validate(new FraudMarkRequest(ROW_ACCOUNT_ID, ROW_CUSTOMER_ID,
                NINES_COMPLEMENT_DATE, ROW_AUTH_TIME_KEY, "F")).size());
        assertEquals(1, validator.validate(new FraudMarkRequest(ROW_ACCOUNT_ID, ROW_CUSTOMER_ID,
                ROW_AUTH_DATE_KEY, NINES_COMPLEMENT_TIME, "F")).size());
    }

    /**
     * The fraud request requires all five of its components.
     *
     * <p>Assumptions: five is the closed component count of the record, so asserting the number is what
     * catches a component silently gaining or losing its requiredness. Each omission reports exactly one
     * violation because the requiredness annotation is the only constraint a null reaches.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fraud request requires all five of its components")
    void theFraudRequestRequiresAllFiveComponents() {
        assertEquals(5,
                validator.validate(new FraudMarkRequest(null, null, null, null, null)).size());
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
     * Builds a fraud request that varies only in the two components a case is about.
     *
     * <p>Assumptions: the customer identifier and the two key integers are held at their valid values so
     * that each case reports violations from the one component it varies. Varying more than one at a time
     * would make a violation count ambiguous about which constraint produced it.</p>
     *
     * @param accountId the account component to place on the request, which may be a value the domain
     *     refuses
     * @param action the one-character command to place on the request, which may be a value the domain
     *     refuses
     * @return the request, never {@code null}
     */
    private FraudMarkRequest fraudRequestWith(String accountId, String action) {
        return new FraudMarkRequest(accountId, ROW_CUSTOMER_ID, ROW_AUTH_DATE_KEY,
                ROW_AUTH_TIME_KEY, action);
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
