package com.carddemo.reference.dto;

/**
 * The verdict on one candidate date, satisfying the contract schema {@code DateEvaluationResult}.
 *
 * <p>Purpose: the outbound shape of the date evaluation. It reports the outcome as a named feedback
 * code together with the numeric severity and message number the baseline utility produces, so that a
 * caller can branch on the name while an operator can still reconcile the numbers against the
 * reference.</p>
 *
 * <p>Assumptions: both the name and the two numbers are published rather than one or the other. The
 * numbers are what the baseline actually returns and are the only values that can be reconciled
 * against it; the name is what a caller can branch on without embedding a numeric table. Publishing
 * only the numbers would oblige every client to carry that table, and publishing only the name would
 * make the reply impossible to reconcile against the utility it transcribes.</p>
 *
 * @param feedbackCode the named outcome, one of the values the contract enumerates
 * @param severity the numeric severity the baseline utility reports
 * @param messageNumber the numeric message identifier the baseline utility reports
 * @param verdict the human-readable verdict text
 * @param date the candidate date exactly as received, echoed so a reply can be matched to its request
 * @param mask the picture the candidate was read against, which is the supplied mask or the default
 */
public record DateConversionResponse(
        String feedbackCode,
        int severity,
        int messageNumber,
        String verdict,
        String date,
        String mask) {
}
