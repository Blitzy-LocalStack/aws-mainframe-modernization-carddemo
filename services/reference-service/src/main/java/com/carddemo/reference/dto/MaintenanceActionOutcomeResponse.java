package com.carddemo.reference.dto;

/**
 * The outcome of one requested maintenance action, satisfying the contract schema
 * {@code MaintenanceActionOutcome}.
 *
 * <p>Purpose: an element of the maintenance batch reply, reporting what happened to one action. The
 * position ties it back to the request array, so a caller submitting many actions can tell which of
 * them the outcome describes without matching on the key.</p>
 *
 * <p>Assumptions: a not-found outcome is reported as its own state rather than as a failure, and the
 * distinction is the baseline's. Its batch driver treats a row that matched nothing as a soft reject
 * and carries on with the next record rather than abandoning the run, so collapsing that state into a
 * failure would report a batch as broken that the baseline reports as warned.</p>
 *
 * @param position the one-based index of the action in the request array
 * @param action the action kind that was requested, echoed so an outcome is readable on its own
 * @param typeCd the transaction type the action addressed
 * @param outcome the resulting state, one of {@code APPLIED}, {@code NO_ROWS_FOUND} or {@code FAILED}
 * @param applied {@code true} only when the action changed stored data
 * @param message the per-action explanation, carried at the width this context composes messages in
 */
public record MaintenanceActionOutcomeResponse(
        int position,
        String action,
        String typeCd,
        String outcome,
        boolean applied,
        String message) {
}
