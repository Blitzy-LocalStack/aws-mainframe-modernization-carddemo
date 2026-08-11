package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Decides whether an authorization request is approved or declined, and with which response codes.
 *
 * <p>This is the migrated form of paragraph {@code 6000-MAKE-DECISION} in
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} at lines 657 to 734. Every literal below is
 * carried across character for character from that paragraph, because the response code and reason a
 * requester receives are an externally observable interface and a changed digit is a changed contract.
 * The paragraph is reference material: it is read, never modified.</p>
 *
 * <p>Assumptions: this class holds no state and touches no database. The baseline paragraph decides
 * from the working-storage records its caller has already read, so the migrated form takes those reads'
 * OUTCOMES as one argument rather than performing them. That is what lets every branch below be
 * exercised by a plain unit test with no database, no queue and no account context.</p>
 *
 * <p>Refactoring Rationale: this class previously took only the pending-authorization summary and could
 * therefore reach exactly two of the baseline's eight outcomes. Two consequences followed, and both were
 * parity breaks rather than simplifications. An account with no summary segment yet -- which is every
 * account until its first authorization -- was declined outright, where the baseline falls back to the
 * ACCOUNT MASTER's limit and posted balance at lines 673 to 679 and frequently approves. And the reason
 * returned was chosen from two values where the baseline chooses from seven, so a requester could not
 * distinguish an unknown card from an exhausted limit. {@link DecisionContext} now carries all four
 * lookup outcomes the baseline's own decision reads, and {@link DeclineReason} carries the complete
 * reason table.</p>
 */
@Service
public class AuthorizationDecisionService {

    /**
     * The response code returned on an approval, {@code '00'} at line 693 of the baseline paragraph.
     */
    public static final String RESP_CODE_APPROVED = "00";

    /**
     * The response code returned on a decline, {@code '05'} at line 688 of the baseline paragraph.
     *
     * <p>Assumptions: the baseline uses ONE decline code for every decline and varies only the reason,
     * so a requester distinguishes causes by the reason and not by the code. Introducing a second decline
     * code would be a contract change that no requester is written to expect.</p>
     */
    public static final String RESP_CODE_DECLINED = "05";

    /**
     * The reason returned on an approval, {@code '0000'} at line 698 of the baseline paragraph.
     *
     * <p>Assumptions: the baseline moves this literal into the reply BEFORE testing whether the
     * authorization was declined, so it is the reason on every approval and the initial value on every
     * decline. It is declared here rather than in {@link DeclineReason} because it is not a decline
     * reason, and putting it there would invite a caller to select it as one.</p>
     */
    public static final String RESP_REASON_APPROVED = "0000";

    /**
     * The complete decline-reason table, transcribed from the baseline's own selection at lines 699
     * to 717.
     *
     * <p>Assumptions: all seven reasons are declared even though only two can be reached, and the gap
     * is the baseline's rather than this migration's. The reference program declares five decline-reason
     * condition names at {@code COPAUA0C.cbl} lines 141 to 145 and SETS exactly one of them,
     * {@code INSUFFICIENT-FUND}, at its lines 670 and 678; the other four appear only as selection
     * subjects in the table below. Rule T9 of the migration plan forbids inventing a behavioural change,
     * so this migration does not start setting them: the constants exist so the mapping is complete and
     * auditable against the source, and a unit test asserts each code, but no input to
     * {@link #decide(AuthRequest, DecisionContext)} selects one of the four.</p>
     *
     * <p>Alternatives Considered: declaring only the two reachable reasons and leaving the other five
     * out. Rejected because the reason is an externally observable four-character contract, and a
     * migration that silently drops five of its seven values gives a later reader no way to tell a value
     * that was considered and found unreachable from one that was never noticed. Alternatives
     * Considered: keeping the reasons as bare string constants, which is what an earlier revision did.
     * Rejected because a string constant cannot state which reasons are selectable, and the four
     * unreachable ones then look like an oversight at every call site.</p>
     */
    public enum DeclineReason {

        /**
         * The card, the account or the customer could not be found, {@code '3100'} at line 704.
         *
         * <p>Assumptions: one reason covers all three lookups because the baseline's selection lists the
         * three not-found conditions as three subjects of ONE branch. Splitting them into three reasons
         * would tell a requester which of three records is missing, which is both a contract change and
         * an enumeration aid for anyone probing card numbers.</p>
         */
        NOT_FOUND("3100"),

        /**
         * The requested amount exceeds the available amount, {@code '4100'} at line 706.
         */
        INSUFFICIENT_FUND("4100"),

        /**
         * The card is not active, {@code '4200'} at line 708; declared by the baseline and never set.
         */
        CARD_NOT_ACTIVE("4200"),

        /**
         * The account is closed, {@code '4300'} at line 710; declared by the baseline and never set.
         */
        ACCOUNT_CLOSED("4300"),

        /**
         * The card is reported fraudulent, {@code '5100'} at line 712; declared and never set.
         */
        CARD_FRAUD("5100"),

        /**
         * The merchant is reported fraudulent, {@code '5200'} at line 714; declared and never set.
         */
        MERCHANT_FRAUD("5200"),

        /**
         * A decline with no reason condition set, {@code '9000'} at line 716.
         *
         * <p>Assumptions: this is the baseline's catch-all branch, and in the baseline it is
         * unreachable. The only decline that sets no reason condition is the one at line 681, taken when
         * neither a summary segment nor an account master record was found -- and the selection's FIRST
         * branch already catches a missing account master, so the catch-all is never reached. The
         * migrated form preserves that property exactly, which
         * {@link #resolveReason(DecisionContext, DeclineReason)} explains and a unit test asserts.</p>
         */
        UNSPECIFIED("9000");

        /**
         * The four-character reason this constant sends to the requester.
         */
        private final String responseReason;

        /**
         * Binds a constant to the reason literal the baseline moves into the reply.
         *
         * @param responseReason the four-character reason literal; never {@code null}
         */
        DeclineReason(String responseReason) {
            this.responseReason = responseReason;
        }

        /**
         * Returns the four-character reason sent to the requester.
         *
         * @return the reason literal, exactly as the baseline moves it, never {@code null}
         */
        public String responseReason() {
            return this.responseReason;
        }
    }

    /**
     * The outcomes of the four lookups the baseline's decision reads.
     *
     * <p>Assumptions: the shape mirrors {@code 5000-PROCESS-AUTH} at {@code COPAUA0C.cbl} lines 438 to
     * 466, which reads the cross-reference first and performs the account, customer and summary reads
     * ONLY when the cross-reference resolved. A context whose card was not found therefore carries an
     * empty account, an absent customer and an empty summary, which is exactly the state the reference
     * program's flags are left in, and every branch below behaves accordingly.</p>
     *
     * <p>Assumptions: the customer is a boolean rather than a record, because the baseline reads the
     * whole customer record and then uses none of its fields -- see {@code 5300-READ-CUST-RECORD} and
     * the single flag it sets at line 587. Carrying the record would move customer data across a context
     * boundary for no reader.</p>
     *
     * @param cardFound whether the card resolved through the cross-reference,
     *     {@code CARD-FOUND-XREF} at line 489
     * @param account the account master record, empty when the account was not found, which is
     *     {@code NFOUND-ACCT-IN-MSTR} at line 539; must not be {@code null}
     * @param customerFound whether the customer master holds the customer, {@code FOUND-CUST-IN-MSTR}
     *     at line 585
     * @param summary the account's pending-authorization summary, empty when the segment does not
     *     exist, which is {@code NFOUND-PAUT-SMRY-SEG} at line 631; must not be {@code null}
     */
    public record DecisionContext(boolean cardFound, Optional<AccountContextClient.Account> account,
            boolean customerFound, Optional<PendingAuthSummary> summary) {

        /**
         * Rejects a context whose optional members are {@code null}.
         *
         * <p>Assumptions: an absent record is expressed by an EMPTY optional and never by a null one, so
         * a null here is a caller defect rather than a lookup outcome. Refusing it at construction is
         * what keeps every branch below able to read the optionals without a null check.</p>
         *
         * @param cardFound whether the card resolved through the cross-reference
         * @param account the account master record, empty when it was not found; must not be
         *     {@code null}
         * @param customerFound whether the customer master holds the customer
         * @param summary the account's pending-authorization summary, empty when the segment does not
         *     exist; must not be {@code null}
         * @throws NullPointerException if {@code account} or {@code summary} is {@code null}
         */
        public DecisionContext {
            Objects.requireNonNull(account, "account must not be null; use Optional.empty()");
            Objects.requireNonNull(summary, "summary must not be null; use Optional.empty()");
        }
    }

    /**
     * The outcome of a decision: the response triple plus the amount approved.
     *
     * <p>Assumptions: the approved amount is part of the outcome rather than being inferred by the
     * caller, because the baseline moves either the requested amount or literal zero into the reply at
     * lines 689 and 694 and the choice belongs with the decision that made it.</p>
     *
     * @param approved whether the authorization was approved
     * @param responseCode the two-character response code returned to the requester
     * @param responseReason the four-character response reason returned to the requester
     * @param approvedAmount the amount approved, which is zero on a decline
     */
    public record Decision(boolean approved, String responseCode, String responseReason,
            Money approvedAmount) {
    }

    /**
     * Decides an authorization request against the outcomes of the baseline's four lookups.
     *
     * <p>Assumptions: the available amount is a limit MINUS a balance and the request is declined when
     * the requested amount is STRICTLY GREATER than that difference, at lines 668 and 676. Strictness
     * matters: a request for exactly the available amount is approved, and an off-by-one here would
     * decline a transaction the baseline approves.</p>
     *
     * <p>Assumptions: this boundary and the POSTING program's over-limit boundary reach the same verdict
     * at the boundary value and are written in OPPOSITE forms, and the two must not be conflated. Here
     * the test is {@code IF WS-TRANSACTION-AMT > WS-AVAILABLE-AMT} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 668 and 676, so the STRICT operator
     * sits on the REFUSAL branch and equality falls through to the approval. In the posting program the
     * test is {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl} line 407, so
     * the INCLUSIVE operator sits on the ACCEPTANCE branch and equality is admitted by the comparison
     * itself. Both therefore admit the exact boundary, and either operator transcribed into the other's
     * position inverts it: an inclusive comparison on this refusal branch would decline a request for
     * exactly the available amount, and a strict one on the posting program's acceptance branch would
     * post a transaction one cent over the limit that reject reason 102 exists to stop. The two are
     * compared here, rather than each being described as "the boundary", because they read as the same
     * rule and are not the same expression of it.</p>
     *
     * <p>Assumptions: the reference program's own arithmetic can lose the high-order digits of the
     * available amount, and the exposure belongs to ONE of the two arms. Its accumulator is declared
     * {@code 05 WS-AVAILABLE-AMT PIC S9(09)V99 COMP-3} at line 62, nine integer digits. The summary arm
     * at lines 666 and 667 subtracts two members declared {@code PIC S9(09)V99 COMP-3} in
     * {@code cpy/CIPAUSMY.cpy}, so source and destination are the same declared width and the
     * subtraction has nowhere to overflow to. The account-master arm at lines 674 and 675 subtracts
     * {@code ACCT-CURR-BAL} from {@code ACCT-CREDIT-LIMIT}, both declared {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy} lines 7 and 8 -- TEN integer digits into nine -- so an account whose
     * available amount reaches a thousand million loses the leading digit there and is measured against a
     * remainder. The migrated form carries {@link Money}, which is not width-bounded, so it does not
     * reproduce the loss; nothing is claimed to be reproduced, and the arithmetic is named here because a
     * reader comparing the two on a large account will find figures that differ and is owed the reason.</p>
     *
     * <p>Assumptions: WHICH limit and balance are used depends on the summary, and the order is the
     * baseline's. When a summary segment exists the check reads the summary's credit limit and credit
     * balance, because that balance already includes authorizations taken and not yet posted; reading
     * the account's posted balance instead would approve a second authorization against funds the first
     * has already reserved. Only when no summary exists does the check fall back to the account master's
     * credit limit and posted balance, which is the baseline's own fallback and the reason this context
     * reads the account at all.</p>
     *
     * @param request the decoded authorization request; must not be {@code null}
     * @param context the outcomes of the cross-reference, account, customer and summary lookups; must
     *     not be {@code null}
     * @return the decision, never {@code null}
     */
    public Decision decide(AuthRequest request, DecisionContext context) {
        DeclineReason detected = detectDecline(request.transactionAmount(), context);
        if (detected == null) {
            return new Decision(true, RESP_CODE_APPROVED, RESP_REASON_APPROVED,
                    request.transactionAmount());
        }
        return new Decision(false, RESP_CODE_DECLINED, resolveReason(context, detected), Money.ZERO);
    }

    /**
     * Detects whether the request is declined, and on what ground, following the baseline's branch
     * order.
     *
     * <p>Assumptions: a missing account or a missing customer does NOT by itself decline anything. The
     * baseline's decline decision at lines 664 to 683 tests only the summary, the account master's
     * presence and the amount; the two not-found flags reach the outcome only through the reason
     * selection, and then only when some other ground has already declined the request. So an
     * authorization whose customer record is missing but whose limit accommodates it is APPROVED, with
     * reason {@code '0000'} -- faithfully surprising, and a divergence here would be visible to any
     * requester comparing the two systems.</p>
     *
     * @param requested the requested amount; must not be {@code null}
     * @param context the lookup outcomes; must not be {@code null}
     * @return the ground for declining, or {@code null} when the request is approved
     */
    private DeclineReason detectDecline(Money requested, DecisionContext context) {
        if (context.summary().isPresent()) {
            PendingAuthSummary held = context.summary().get();
            Money available = Money.of(held.getCreditLimit())
                    .minus(Money.of(held.getCreditBalance()));
            return requested.compareTo(available) > 0 ? DeclineReason.INSUFFICIENT_FUND : null;
        }
        if (context.account().isPresent()) {
            AccountContextClient.Account account = context.account().get();
            Money available = Money.of(account.creditLimit())
                    .minus(Money.of(account.currentBalance()));
            return requested.compareTo(available) > 0 ? DeclineReason.INSUFFICIENT_FUND : null;
        }
        // WHY : Assumptions: with neither a summary segment nor an account master record there is no
        // limit to check against, so the baseline declines at its line 681 WITHOUT setting any reason
        // condition. UNSPECIFIED is the faithful expression of that state -- it names the absence of a
        // ground rather than inventing one -- and the reason resolution below then re-labels it, exactly
        // as the baseline's selection does, because a context that reaches here necessarily has an empty
        // account.
        return DeclineReason.UNSPECIFIED;
    }

    /**
     * Chooses the reason literal a decline reports, in the baseline's selection order.
     *
     * <p>Assumptions: the ORDER of the selection is itself part of the contract, and reversing it
     * changes what a requester sees. The baseline's {@code EVALUATE} at lines 700 to 717 tests the three
     * not-found conditions FIRST and the insufficient-funds condition second, so a request that was
     * declined for want of funds against an account whose master record is missing reports
     * {@code '3100'} and not {@code '4100'}. Testing the detected ground first would look more natural
     * and would report a different reason on that path.</p>
     *
     * <p>Assumptions: {@link DeclineReason#UNSPECIFIED} is unreachable as an OUTCOME even though it can
     * be detected, and the proof is short enough to state here. It is detected only when the context
     * carries no account, and the first predicate below already returns {@link DeclineReason#NOT_FOUND}
     * for exactly that condition. The baseline's catch-all branch is unreachable for the same reason, so
     * the migrated form neither gains nor loses a reachable outcome; a unit test pins the property so a
     * later edit to either predicate cannot quietly change it.</p>
     *
     * <p>Assumptions: the four never-set conditions have no predicate here at all, and their absence is
     * deliberate. There is no input from which this method could conclude that a card is inactive, an
     * account is closed or either party is fraudulent -- the port that supplies the account deliberately
     * carries no status field for exactly this reason -- so a predicate for one of them could only be
     * driven by a datum this migration invented, which Rule T9 forbids.</p>
     *
     * @param context the lookup outcomes; must not be {@code null}
     * @param detected the ground the detection reached; must not be {@code null}
     * @return the four-character reason literal to report, never {@code null}
     */
    private String resolveReason(DecisionContext context, DeclineReason detected) {
        if (!context.cardFound() || context.account().isEmpty() || !context.customerFound()) {
            return DeclineReason.NOT_FOUND.responseReason();
        }
        return detected.responseReason();
    }

    /**
     * Derives the authorization identification code returned to the requester.
     *
     * <p>Assumptions: the baseline moves the REQUEST'S OWN authorization time into the reply's
     * identification code at line 662, so the code is not generated and carries no independent meaning.
     * Minting a fresh identifier here would look more correct and would break every requester that
     * correlates the reply by comparing this field with what it sent.</p>
     *
     * @param request the decoded authorization request; must not be {@code null}
     * @return the six-character identification code to return, never {@code null}
     */
    public String identificationCodeFor(AuthRequest request) {
        return request.authTime();
    }
}
