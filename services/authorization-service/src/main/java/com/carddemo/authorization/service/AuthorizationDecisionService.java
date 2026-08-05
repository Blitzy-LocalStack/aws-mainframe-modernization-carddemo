package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
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
 * <p>Assumptions: this class holds no state and touches no database. The baseline paragraph reads the
 * summary segment its caller already retrieved and decides from working storage, so the migrated form
 * takes the summary as an argument rather than fetching it. That is what lets every branch below be
 * exercised by a plain unit test with no database and no queue.</p>
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
     */
    public static final String RESP_REASON_APPROVED = "0000";

    /**
     * The reason returned when the card, account or customer could not be found, {@code '3100'} at
     * line 704.
     */
    public static final String RESP_REASON_NOT_FOUND = "3100";

    /**
     * The reason returned when the available amount is insufficient, {@code '4100'} at line 706.
     */
    public static final String RESP_REASON_INSUFFICIENT_FUND = "4100";

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
     * Decides an authorization request against the account's pending-authorization summary.
     *
     * <p>Assumptions: the available amount is the credit limit MINUS the credit balance, computed at
     * lines 666 and 667, and the request is declined when the requested amount is STRICTLY GREATER than
     * that difference. Strictness matters: a request for exactly the available amount is approved, which
     * is the same inclusive-boundary treatment the posting program applies to its own limit check, and
     * an off-by-one here would decline a transaction the baseline approves.</p>
     *
     * <p>Assumptions: the credit balance already includes authorizations taken and not yet posted,
     * which is why the check reads it rather than the account master's posted balance. Reading a posted
     * balance instead would approve a second authorization against funds the first has already
     * reserved.</p>
     *
     * <p>Trade-offs: when the account has no summary this method declines with the not-found reason,
     * which is exactly what the baseline returns on its own {@code NFOUND-ACCT-IN-MSTR} path at line 704.
     * The baseline has one further branch, at lines 673 to 679, that falls back to the ACCOUNT MASTER's
     * limit and posted balance when the summary segment is absent but the account exists; that branch
     * reads data this bounded context does not own, so reproducing it here requires the account
     * context's read endpoint and belongs with the file that introduces it. Declining with the
     * baseline's own not-found reason is the conservative outcome -- it never approves against a limit
     * it has not verified -- and it is registered as a divergence in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than left to be discovered.</p>
     *
     * @param request the decoded authorization request; must not be {@code null}
     * @param summary the account's pending-authorization summary, empty when the account has none
     * @return the decision, never {@code null}
     */
    public Decision decide(AuthRequest request, Optional<PendingAuthSummary> summary) {
        if (summary.isEmpty()) {
            return new Decision(false, RESP_CODE_DECLINED, RESP_REASON_NOT_FOUND, Money.ZERO);
        }
        PendingAuthSummary held = summary.get();
        Money available = Money.of(held.getCreditLimit()).minus(Money.of(held.getCreditBalance()));
        Money requested = request.transactionAmount();
        if (requested.compareTo(available) > 0) {
            return new Decision(false, RESP_CODE_DECLINED, RESP_REASON_INSUFFICIENT_FUND,
                    Money.ZERO);
        }
        return new Decision(true, RESP_CODE_APPROVED, RESP_REASON_APPROVED, requested);
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
