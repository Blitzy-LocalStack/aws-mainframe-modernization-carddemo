package com.carddemo.authorization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.service.AuthorizationDecisionService.Decision;
import com.carddemo.authorization.service.AuthorizationDecisionService.DecisionContext;
import com.carddemo.authorization.service.AuthorizationDecisionService.DeclineReason;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins every outcome of the migrated decision paragraph, including the reasons it cannot reach.
 *
 * <p>The subject is {@code 6000-MAKE-DECISION} at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 657 to 734, which the class under test
 * transcribes. Three properties of that paragraph are asserted here and each of the three was absent
 * before: the fallback to the account master when no summary segment exists, at its lines 673 to 679; the
 * seven-value reason table at its lines 699 to 717; and the ORDER of that table's selection, which
 * reports a missing record ahead of exhausted funds when both are true.</p>
 *
 * <p>Assumptions: every fixture below is built with plain objects and no container, because the class
 * under test holds no state and reads no datastore. A summary's balance is established through
 * {@code recordApproved}, which is the only way this context's own code raises it, so the fixture reaches
 * its state by the same route production does rather than by reflection.</p>
 */
class AuthorizationDecisionServiceTest {

    /**
     * The class under test, stateless and therefore shared by every case.
     */
    private final AuthorizationDecisionService decisions = new AuthorizationDecisionService();

    /**
     * A request for one hundred dollars and ninety-nine cents, reused wherever the amount is incidental.
     */
    private static final Money ONE_HUNDRED = Money.of("100.99");

    /**
     * An account with an ample limit and no authorizations taken against it approves.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request inside the summary's available amount is approved with reason 0000")
    void aRequestInsideTheSummaryAvailableAmountIsApproved() {
        DecisionContext context = contextWithSummary("5000.00", "0.00");

        Decision decision = this.decisions.decide(requestFor(ONE_HUNDRED), context);

        assertTrue(decision.approved());
        assertEquals(AuthorizationDecisionService.RESP_CODE_APPROVED, decision.responseCode());
        assertEquals(AuthorizationDecisionService.RESP_REASON_APPROVED, decision.responseReason());
        assertEquals(ONE_HUNDRED, decision.approvedAmount());
    }

    /**
     * A request for exactly the available amount is approved, because the reference test is strict.
     *
     * <p>Assumptions: the boundary is the assertion. {@code IF WS-TRANSACTION-AMT > WS-AVAILABLE-AMT} at
     * line 668 declines only what EXCEEDS the difference, so a request for the whole of it posts. An
     * off-by-one here would decline a transaction the reference program approves, and it would do so only
     * on the one input a test that used round numbers would never supply.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request for exactly the available amount is approved, one cent more is declined")
    void theAvailableAmountBoundaryIsInclusive() {
        DecisionContext exact = contextWithSummary("5000.00", "4899.01");
        DecisionContext oneCentOver = contextWithSummary("5000.00", "4899.02");

        assertTrue(this.decisions.decide(requestFor(ONE_HUNDRED), exact).approved());

        Decision declined = this.decisions.decide(requestFor(ONE_HUNDRED), oneCentOver);
        assertFalse(declined.approved());
        assertEquals(DeclineReason.INSUFFICIENT_FUND.responseReason(), declined.responseReason());
        assertEquals(AuthorizationDecisionService.RESP_CODE_DECLINED, declined.responseCode());
        assertEquals(Money.ZERO, declined.approvedAmount());
    }

    /**
     * With no summary segment the decision falls back to the account master's limit and posted balance.
     *
     * <p>Assumptions: this is the branch at lines 673 to 679, and it is the one the earlier revision could
     * not reach. Its absence declined every first authorization on every account, because a summary
     * segment does not exist until one has been decided.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("with no summary the account master's limit less its posted balance is used")
    void theAccountMasterIsTheFallbackWhenNoSummaryExists() {
        DecisionContext withinLimit = contextWithAccountOnly("5000.00", "1000.00");
        DecisionContext beyondLimit = contextWithAccountOnly("5000.00", "4999.00");

        assertTrue(this.decisions.decide(requestFor(ONE_HUNDRED), withinLimit).approved());

        Decision declined = this.decisions.decide(requestFor(ONE_HUNDRED), beyondLimit);
        assertFalse(declined.approved());
        assertEquals(DeclineReason.INSUFFICIENT_FUND.responseReason(), declined.responseReason());
    }

    /**
     * The summary's own balance is preferred to the account's posted balance when both are available.
     *
     * <p>Assumptions: the summary balance counts authorizations taken and not yet posted, so preferring
     * the posted balance would approve a second authorization against funds a first has already reserved.
     * The fixture makes the two disagree deliberately: the posted balance leaves room and the pending
     * balance does not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the summary balance is preferred to the account's posted balance")
    void theSummaryBalanceIsPreferredToThePostedBalance() {
        PendingAuthSummary summary = summaryWith("5000.00", "4990.00");
        DecisionContext context = new DecisionContext(true,
                Optional.of(new AccountContextClient.Account(new BigDecimal("5000.00"),
                        new BigDecimal("500.00"), new BigDecimal("0.00"))),
                true, Optional.of(summary));

        Decision decision = this.decisions.decide(requestFor(ONE_HUNDRED), context);

        assertFalse(decision.approved());
        assertEquals(DeclineReason.INSUFFICIENT_FUND.responseReason(), decision.responseReason());
    }

    /**
     * A card that does not resolve through the cross-reference is declined with reason 3100.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unresolved card is declined with reason 3100")
    void anUnresolvedCardIsDeclinedWithTheNotFoundReason() {
        DecisionContext context =
                new DecisionContext(false, Optional.empty(), false, Optional.empty());

        Decision decision = this.decisions.decide(requestFor(ONE_HUNDRED), context);

        assertFalse(decision.approved());
        assertEquals(DeclineReason.NOT_FOUND.responseReason(), decision.responseReason());
        assertEquals(Money.ZERO, decision.approvedAmount());
    }

    /**
     * A missing account is reported as 3100 even when the ground for declining was exhausted funds.
     *
     * <p>Assumptions: this is the selection ORDER, and it is the property most easily lost in
     * translation. The reference {@code EVALUATE} at lines 700 to 717 lists the three not-found
     * conditions as its first branch and insufficient funds only as its second, so a request declined for
     * want of funds against an account whose master record is missing reports {@code '3100'}. Testing the
     * detected ground first would read more naturally and would return {@code '4100'} here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a missing account outranks insufficient funds in the reason selection")
    void aMissingAccountOutranksInsufficientFunds() {
        PendingAuthSummary exhausted = summaryWith("100.00", "100.00");
        DecisionContext context =
                new DecisionContext(true, Optional.empty(), true, Optional.of(exhausted));

        Decision decision = this.decisions.decide(requestFor(ONE_HUNDRED), context);

        assertFalse(decision.approved());
        assertEquals(DeclineReason.NOT_FOUND.responseReason(), decision.responseReason());
    }

    /**
     * A missing customer does not decline a request its limit accommodates.
     *
     * <p>Assumptions: faithfully surprising. The reference decline test at lines 664 to 683 reads only
     * the summary, the account master's presence and the amount; the customer flag reaches the outcome
     * only through the reason selection, and only once some other ground has declined the request. So an
     * authorization whose customer record is missing and whose funds suffice is APPROVED with reason
     * {@code '0000'}, and a target that declined it would diverge visibly from the baseline.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a missing customer alone does not decline a request within its limit")
    void aMissingCustomerAloneDoesNotDecline() {
        PendingAuthSummary summary = summaryWith("5000.00", "0.00");
        DecisionContext context = new DecisionContext(true,
                Optional.of(new AccountContextClient.Account(new BigDecimal("5000.00"),
                        new BigDecimal("500.00"), new BigDecimal("0.00"))),
                false, Optional.of(summary));

        Decision approved = this.decisions.decide(requestFor(ONE_HUNDRED), context);
        assertTrue(approved.approved());
        assertEquals(AuthorizationDecisionService.RESP_REASON_APPROVED, approved.responseReason());
    }

    /**
     * A missing customer is reported as 3100 once some other ground has declined the request.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a missing customer relabels an insufficient-funds decline as 3100")
    void aMissingCustomerRelabelsADecline() {
        PendingAuthSummary exhausted = summaryWith("100.00", "100.00");
        DecisionContext context = new DecisionContext(true,
                Optional.of(new AccountContextClient.Account(new BigDecimal("100.00"),
                        new BigDecimal("0.00"), new BigDecimal("100.00"))),
                false, Optional.of(exhausted));

        Decision decision = this.decisions.decide(requestFor(ONE_HUNDRED), context);

        assertFalse(decision.approved());
        assertEquals(DeclineReason.NOT_FOUND.responseReason(), decision.responseReason());
    }

    /**
     * Every reason in the table carries the four-character literal the reference selection moves.
     *
     * <p>Assumptions: four of these seven are declared by the reference program and never set, so this
     * assertion is the whole of what can honestly be tested about them. Their presence keeps the mapping
     * complete and auditable against lines 699 to 717; driving one of them would require inventing a
     * decline condition the baseline cannot produce, which Rule T9 of the migration plan forbids.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reason table reproduces all seven literals from lines 699 to 717")
    void theReasonTableReproducesEveryLiteral() {
        assertEquals("3100", DeclineReason.NOT_FOUND.responseReason());
        assertEquals("4100", DeclineReason.INSUFFICIENT_FUND.responseReason());
        assertEquals("4200", DeclineReason.CARD_NOT_ACTIVE.responseReason());
        assertEquals("4300", DeclineReason.ACCOUNT_CLOSED.responseReason());
        assertEquals("5100", DeclineReason.CARD_FRAUD.responseReason());
        assertEquals("5200", DeclineReason.MERCHANT_FRAUD.responseReason());
        assertEquals("9000", DeclineReason.UNSPECIFIED.responseReason());
        assertEquals(7, DeclineReason.values().length);
    }

    /**
     * No reachable input produces one of the five reasons the reference program never sets.
     *
     * <p>Assumptions: the four never-set conditions and the catch-all are unreachable in the baseline,
     * and this asserts the migrated form preserves that. The three inputs that can vary are swept -- the
     * cross-reference outcome, the account outcome and the customer outcome -- across both a sufficient
     * and an exhausted limit, and every decline that results reports either {@code '3100'} or
     * {@code '4100'}. The catch-all in particular is detectable but never reported, because the only
     * context that detects it has no account and the first predicate of the selection already covers
     * that.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("only 0000, 3100 and 4100 are reachable outcomes")
    void theOtherFiveReasonsAreUnreachable() {
        for (boolean cardFound : new boolean[] {true, false}) {
            for (boolean accountFound : new boolean[] {true, false}) {
                for (boolean customerFound : new boolean[] {true, false}) {
                    for (boolean summaryFound : new boolean[] {true, false}) {
                        for (String balance : new String[] {"0.00", "5000.00"}) {
                            Optional<AccountContextClient.Account> account = accountFound
                                    ? Optional.of(new AccountContextClient.Account(
                                            new BigDecimal("5000.00"), new BigDecimal("500.00"),
                                            new BigDecimal(balance)))
                                    : Optional.empty();
                            Optional<PendingAuthSummary> summary = summaryFound
                                    ? Optional.of(summaryWith("5000.00", balance))
                                    : Optional.empty();
                            Decision decision = this.decisions.decide(requestFor(ONE_HUNDRED),
                                    new DecisionContext(cardFound, account, customerFound, summary));
                            assertTrue(isReachableReason(decision.responseReason()),
                                    "unreachable reason " + decision.responseReason()
                                            + " produced for cardFound=" + cardFound + " accountFound="
                                            + accountFound + " customerFound=" + customerFound
                                            + " summaryFound=" + summaryFound + " balance=" + balance);
                        }
                    }
                }
            }
        }
    }

    /**
     * The identification code echoed to the requester is the request's own authorization time.
     *
     * <p>Assumptions: line 662 moves {@code PA-RQ-AUTH-TIME} into the reply's identification code, so
     * the field is an echo rather than a generated identifier. Minting one here would break every
     * requester that correlates its reply by comparing this field with what it sent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the identification code echoes the request's authorization time")
    void theIdentificationCodeEchoesTheRequestTime() {
        AuthRequest request = requestFor(ONE_HUNDRED);

        assertEquals(request.authTime(), this.decisions.identificationCodeFor(request));
    }

    /**
     * A context built with a null optional is refused rather than silently treated as absent.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a null optional in the context is refused at construction")
    void aNullOptionalIsRefused() {
        assertThrows(NullPointerException.class,
                () -> new DecisionContext(true, null, true, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new DecisionContext(true, Optional.empty(), true, null));
    }

    /**
     * Reports whether a reason is one of the three the baseline can actually reach.
     *
     * @param responseReason the reason a decision reported
     * @return {@code true} when the reason is the approved literal, the not-found literal or the
     *     insufficient-funds literal
     */
    private boolean isReachableReason(String responseReason) {
        return AuthorizationDecisionService.RESP_REASON_APPROVED.equals(responseReason)
                || DeclineReason.NOT_FOUND.responseReason().equals(responseReason)
                || DeclineReason.INSUFFICIENT_FUND.responseReason().equals(responseReason);
    }

    /**
     * Builds a context whose card resolved, whose account and customer exist, and which has a summary.
     *
     * @param creditLimit the summary's credit limit as a decimal string
     * @param creditBalance the summary's already-reserved credit balance as a decimal string
     * @return the context, never {@code null}
     */
    private DecisionContext contextWithSummary(String creditLimit, String creditBalance) {
        return new DecisionContext(true,
                Optional.of(new AccountContextClient.Account(new BigDecimal(creditLimit),
                        new BigDecimal("500.00"), new BigDecimal("0.00"))),
                true, Optional.of(summaryWith(creditLimit, creditBalance)));
    }

    /**
     * Builds a context whose card resolved and whose account exists, but which has no summary yet.
     *
     * @param creditLimit the account's credit limit as a decimal string
     * @param currentBalance the account's posted balance as a decimal string
     * @return the context, never {@code null}
     */
    private DecisionContext contextWithAccountOnly(String creditLimit, String currentBalance) {
        return new DecisionContext(true,
                Optional.of(new AccountContextClient.Account(new BigDecimal(creditLimit),
                        new BigDecimal("500.00"), new BigDecimal(currentBalance))),
                true, Optional.empty());
    }

    /**
     * Builds a summary at a stated limit whose reserved balance has been raised by approvals.
     *
     * <p>Assumptions: the balance is reached by calling {@code recordApproved}, which is how production
     * raises it, rather than by setting a field. A fixture that bypassed that method could hold a balance
     * with no matching counter, which is a state this context's own invariants exclude.</p>
     *
     * @param creditLimit the credit limit to refresh onto the summary as a decimal string
     * @param creditBalance the reserved balance to reach as a decimal string, which may be zero
     * @return the summary, never {@code null}
     */
    private PendingAuthSummary summaryWith(String creditLimit, String creditBalance) {
        PendingAuthSummary summary = new PendingAuthSummary(11111111111L, 999999999L);
        summary.refreshLimits(new BigDecimal(creditLimit), new BigDecimal("500.00"));
        BigDecimal reserved = new BigDecimal(creditBalance);
        if (reserved.signum() != 0) {
            summary.recordApproved(reserved);
        }
        return summary;
    }

    /**
     * Builds a well-formed request carrying the supplied amount.
     *
     * <p>Assumptions: every other component is a plausible fixed-width value of its declared width, and
     * none of them affects a decision. The amount is the only component the decision reads, so it is the
     * only one this helper parameterises.</p>
     *
     * @param amount the transaction amount the request carries
     * @return the request, never {@code null}
     */
    private AuthRequest requestFor(Money amount) {
        return new AuthRequest("250801", "104530", "4111111111111111", "0100", "1230", "0100",
                "POS001", "000000", amount, "5411", "840", "05", "MERCHANT0000001",
                "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000", "TXN000000000001");
    }
}
