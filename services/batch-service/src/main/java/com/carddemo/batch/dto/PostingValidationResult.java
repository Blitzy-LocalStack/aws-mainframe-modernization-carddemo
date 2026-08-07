package com.carddemo.batch.dto;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of validating one daily transaction: whether it may post, and which reason refused it.
 *
 * <p>Purpose: this type owns the reject-reason PRECEDENCE. The service that validates a transaction
 * decides which conditions it fails; this type decides which of those failures is the one reported, and it
 * does so in a factory so that no caller can invert the order. The baseline expresses the same decision
 * numerically, testing {@code IF WS-VALIDATION-FAIL-REASON = 0} at {@code app/cbl/CBTRN02C.cbl:211} to
 * select posting on zero and the reject path otherwise.</p>
 *
 * <p>Assumptions: the precedence is NOT "most severe wins" and is not a comparison between codes at all.
 * It is the order the baseline's own control flow reaches the assignments in, and that order has one
 * asymmetry a reader will not expect: the first two conditions SHORT-CIRCUIT while the last two do not.
 * The card lookup at {@code :372} guards the account lookup at {@code :373}, so reason 100 excludes 101;
 * the account read's not-invalid-key branch at {@code :400} encloses both boundary tests, so reason 101
 * excludes both 102 and 103. But the two boundary blocks at {@code :407} and {@code :414} are sequential
 * and unguarded -- line 413 closes the first and line 414 opens the second with no test of the reason
 * between them -- so a transaction failing both has the second assignment OVERWRITE the first and is
 * reported as 103.</p>
 *
 * <p>Alternatives Considered: writing the boundary pair as a single either-or, which is the shape a reader
 * expects and which is what a naive transcription produces. It is rejected because it reports 102 for a
 * transaction that fails both, where the baseline reports 103 -- a divergence on exactly the records that
 * are over the limit AND late, which is a plausible combination rather than a contrived one.</p>
 *
 * @param reason the reason that refused the transaction, or {@code null} when it may post
 */
public record PostingValidationResult(RejectReason reason) {

    /**
     * The single instance describing a transaction that failed no condition.
     *
     * <p>Assumptions: a passing outcome is a shared constant rather than a fresh instance, because it
     * carries no state to distinguish two of them and the posting loop produces one per accepted record.</p>
     */
    private static final PostingValidationResult POSTED = new PostingValidationResult(null);

    /**
     * Returns the outcome for a transaction that failed no condition.
     *
     * @return the passing outcome, never {@code null}
     */
    public static PostingValidationResult posted() {
        return POSTED;
    }

    /**
     * Returns the outcome for a transaction refused under one named reason.
     *
     * @param reason the reason that refused it; must not be {@code null}
     * @return the refusing outcome, never {@code null}
     * @throws NullPointerException if {@code reason} is {@code null}
     */
    public static PostingValidationResult rejected(RejectReason reason) {
        return new PostingValidationResult(Objects.requireNonNull(reason, "reason must not be null"));
    }

    /**
     * Resolves the four validation conditions into the one outcome the baseline reports.
     *
     * <p>Assumptions: the four arguments describe what the reads and the comparisons FOUND, not what the
     * caller concluded. That division is what keeps the precedence here: a caller passing all four
     * findings cannot express a preference between them, so the order below is the only order the codebase
     * has.</p>
     *
     * @param cardResolved whether the card cross-reference resolved, which the baseline tests at
     *     {@code :372}
     * @param accountFound whether the account record was read, which the baseline tests at {@code :400};
     *     read only when the card resolved
     * @param overCreditLimit whether the projected balance strictly exceeded the credit limit, the
     *     negation of the inclusive guard at {@code :407}; evaluated only when the account was read
     * @param afterExpiration whether the originating date fell strictly after the account expiration
     *     date, the negation of the inclusive guard at {@code :414}; evaluated only when the account was
     *     read
     * @return the outcome, never {@code null}
     */
    public static PostingValidationResult of(boolean cardResolved, boolean accountFound,
            boolean overCreditLimit, boolean afterExpiration) {

        // WHY : Assumptions: the first two tests return rather than fall through, which is how the
        //       baseline's two guards are expressed. Falling through and choosing later would be the
        //       same logic only if nothing in between could assign, and the two boundary assignments
        //       can.
        if (!cardResolved) {
            return rejected(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
        }
        if (!accountFound) {
            return rejected(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
        }

        // WHY : Assumptions: the expiration test is placed AFTER the limit test and overwrites it,
        //       because the baseline's two blocks are sequential and unguarded. This is deliberately not
        //       an else-if: an else-if reports 102 for a record failing both, and the baseline reports
        //       103.
        RejectReason reason = null;
        if (overCreditLimit) {
            reason = RejectReason.OVER_CREDIT_LIMIT;
        }
        if (afterExpiration) {
            reason = RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION;
        }
        return reason == null ? posted() : rejected(reason);
    }

    /**
     * Reports whether the transaction may post.
     *
     * @return {@code true} when no reason refused it, otherwise {@code false}
     */
    public boolean mayPost() {
        return this.reason == null;
    }

    /**
     * Returns the refusing reason, if any.
     *
     * @return the reason, or an empty optional when the transaction may post
     */
    public Optional<RejectReason> rejectReason() {
        return Optional.ofNullable(this.reason);
    }
}
