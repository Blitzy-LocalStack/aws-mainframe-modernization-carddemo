/**
 * @file Typed client for the reporting and statement bounded context, written against
 * `services/reporting-service/src/main/resources/openapi/reporting-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the five operations that contract publishes: starting an on-demand transaction report in
 * place of the report-request screen `app/cbl/CORPT00C.cbl` writes job-control text for, reading that
 * report's detail lines and its three subtotal bands, and rendering the statement pair
 * `app/cbl/CBSTM03A.CBL` produces together with the transactions behind it. Every target is derived
 * from the operation manifest below rather than written as a literal, for the reason recorded in
 * `ui/src/api/types.ts`.
 *
 * Why two operations POST what they only read
 * ------------------------------------------
 * Assumptions: `generateStatement` and `listStatementTransactions` are POSTs although neither changes
 * anything this context owns, and the selector is the reason rather than the effect. Both are selected
 * by a sixteen-digit card number, and a card number may not travel in a target: the edge access log
 * records a target in full before any application code runs and the browser retains it in history, so
 * a number placed there reaches two stores no application-side control can redact. A request body is
 * recorded by neither. `lookupCardByNumber` in `ui/src/api/cards.ts` takes the same decision for the
 * same reason.
 *
 * Why the rendered statement arrives as a pair of URIs
 * --------------------------------------------------
 * Assumptions: `generateStatement` returns where each rendering was written rather than the rendering
 * itself, and the plain-text form is why. That form is the byte-for-byte parity artifact compared
 * against the committed golden masters -- 80 bytes per record, as `FD-STMTFILE-REC` declares at
 * `app/cbl/CBSTM03A.CBL` L45 -- and inlining it into a JSON string would require an escaping step that
 * no longer preserves those bytes. A URI leaves the artifact untouched.
 */

import { getApiClient, requestPath } from './client';
import type {
  ContractOperation,
  PageResponse,
  ReportRangeQuery,
  ReportRequest,
  ReportSubmission,
  ReportSubmissionOutcome,
  ReportSubmissionOutcomeBody,
  Statement,
  StatementRequest,
  StatementTransactionCollection,
  TransactionReportLine,
  TransactionReportTotals,
} from './types';

/*
 * WHY : Refactoring Rationale: the reporting wire shapes are RE-EXPORTED from ./types rather than
 *       declared here, so every consumer's import path is unchanged while each shape has one definition.
 */
export type {
  ReportRequest,
  ReportSubmissionOutcomeBody,
  ReportSubmission,
  ReportSubmissionOutcome,
  TransactionReportLine,
  ReportBand,
  ReportTotalBand,
  TransactionReportTotals,
  StatementRequest,
  Statement,
  StatementTransaction,
  StatementTransactionCollection,
  ReportRangeQuery,
} from './types';

const SUBMIT_TRANSACTION_REPORT: ContractOperation = {
  method: 'POST',
  path: '/api/v1/reports/transaction-report',
  operationId: 'submitTransactionReport',
};

const LIST_TRANSACTION_REPORT_LINES: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reports/transaction-report/lines',
  operationId: 'listTransactionReportLines',
};

const READ_TRANSACTION_REPORT_TOTALS: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reports/transaction-report/totals',
  operationId: 'readTransactionReportTotals',
};

const GENERATE_STATEMENT: ContractOperation = {
  method: 'POST',
  path: '/api/v1/reports/statements',
  operationId: 'generateStatement',
};

const LIST_STATEMENT_TRANSACTIONS: ContractOperation = {
  method: 'POST',
  path: '/api/v1/reports/statements/transactions',
  operationId: 'listStatementTransactions',
};

/**
 * Every operation `reporting-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`.
 */
export const REPORTING_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  SUBMIT_TRANSACTION_REPORT,
  LIST_TRANSACTION_REPORT_LINES,
  READ_TRANSACTION_REPORT_TOTALS,
  GENERATE_STATEMENT,
  LIST_STATEMENT_TRANSACTIONS,
];

/** HTTP status the submission answers when an execution was actually started. */
const HTTP_CREATED = 201;

/** Matches the masked rendering every statement response must carry. */
const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/** The outcome of a submission the caller declined to confirm: nothing was started. */

/** One detail line of the transaction report, as the printed detail band carries it. */

/** Which subtotal a band reports. */

/** The subtotal bands of one report run, in the order the report emits them. */

/** One posted transaction as a statement carries it. */

/** The inclusive processing-date range and paging position a report read is issued with. */

/**
 * Header the published contract accepts an optional submission key in.
 *
 * Assumptions: the name is stated once here rather than at the call site, because it is part of the
 * published contract -- `IdempotencyKeyHeader` in the reporting service's OpenAPI document -- and a
 * second spelling of it would be a second statement of one fact.
 */
const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';

/**
 * Starts an on-demand transaction report execution, or reports that the caller declined.
 * @param {ReportRequest} request - The report type selection, the range for a caller-supplied run, and
 *   the confirmation answer. Set `confirm` to a 'Y' answer to start; omit it or answer 'N' to decline.
 * @param {string} [idempotencyKey] - An optional key identifying this submission ATTEMPT. Send the same
 *   key again to retry a submission whose response was lost: the second attempt is then recognised as a
 *   duplicate and refused rather than starting a second run. Omit it -- which is the normal case, and
 *   what every screen currently does -- and each submission starts a distinct run, which is what allows
 *   the same report to be produced again over the same range. At most forty characters, and only
 *   letters, digits, hyphens and underscores; anything else is refused with HTTP 400.
 * @returns {Promise<ReportSubmissionOutcome>} `STARTED` with the execution's identity when the service
 *   started one, otherwise `DECLINED` with the report that would have run.
 * @throws {Error} If the request fails, including HTTP 400 when no report type was selected, the
 *   confirmation answer was unrecognised, or a supplied submission key was malformed.
 */
export async function submitTransactionReport(
  request: ReportRequest,
  idempotencyKey?: string,
): Promise<ReportSubmissionOutcome> {
  const response = await getApiClient().post<ReportSubmissionOutcomeBody>(
    requestPath(SUBMIT_TRANSACTION_REPORT),
    request,
    idempotencyKey === undefined
      ? undefined
      : { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } },
  );

  const body = response.data;
  if (response.status === HTTP_CREATED) {
    // WHY : Assumptions: the handle is read from `body.submission` rather than from the body itself.
    //       An earlier revision cast the whole body to the handle type, which put `executionArn` one
    //       level above where the contract declares it, so every member of a started run read
    //       `undefined` while the call still reported success. Reading the nested member is the fix at
    //       its source; widening the handle type to make the flat reading valid would have made the
    //       client's own description of the body disagree with the published one.
    // WHY : Trade-offs: both members are NARROWED by assertion rather than defaulted. The contract
    //       requires all three on a 201 and the handler's factory refuses a null sentence, so the
    //       narrowing rests on a published guarantee. Substituting an empty string for an absent
    //       sentence was the alternative and was rejected: an empty message band renders as a
    //       deliberate silence, so a service that broke the guarantee would look correct here, whereas
    //       an undefined value surfaces where it is read.
    return {
      outcome: 'STARTED',
      submission: body.submission as ReportSubmission,
      message: body.message as string,
    };
  }
  return { outcome: 'DECLINED', message: body.message };
}

/**
 * Reads one page of transaction report detail lines over an inclusive processing-date range.
 * @param {ReportRangeQuery} query - Both range bounds, plus an optional sealed cursor and the direction
 *   it was issued for.
 * @returns {Promise<PageResponse<TransactionReportLine>>} One bounded page of detail lines.
 * @throws {Error} If the request fails, including HTTP 400 for a malformed bound.
 */
export async function listTransactionReportLines(
  query: ReportRangeQuery,
): Promise<PageResponse<TransactionReportLine>> {
  const params: Record<string, string> = { startDate: query.startDate, endDate: query.endDate };
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    params.direction = query.direction ?? 'next';
  }

  const response = await getApiClient().get<PageResponse<TransactionReportLine>>(
    requestPath(LIST_TRANSACTION_REPORT_LINES),
    { params },
  );
  return response.data;
}

/**
 * Reads the three subtotal bands of the transaction report over an inclusive processing-date range.
 * @param {string} startDate - Inclusive lower bound of the processing-date range, as YYYY-MM-DD.
 * @param {string} endDate - Inclusive upper bound of the processing-date range, as YYYY-MM-DD.
 * @returns {Promise<TransactionReportTotals>} The bands, in the order the report emits them.
 * @throws {Error} If the request fails, including HTTP 400 for a malformed bound.
 */
export async function readTransactionReportTotals(
  startDate: string,
  endDate: string,
): Promise<TransactionReportTotals> {
  const response = await getApiClient().get<TransactionReportTotals>(
    requestPath(READ_TRANSACTION_REPORT_TOTALS),
    { params: { startDate, endDate } },
  );
  return response.data;
}

/**
 * Renders the statement pair for one card or one account.
 * @param {StatementRequest} request - Exactly one of a sixteen-digit card number or an account
 *   identifier.
 * @returns {Promise<Statement>} The statement summary and the URIs of its two renderings, with the card
 *   number echoed back masked.
 * @throws {RangeError} If the response carries an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 404 when the selector matches nothing.
 */
export async function generateStatement(request: StatementRequest): Promise<Statement> {
  const response = await getApiClient().post<Statement>(requestPath(GENERATE_STATEMENT), request);
  requireMaskedCardNumber(response.data.cardNumber);
  return response.data;
}

/**
 * Reads the transactions one statement was rendered from.
 * @param {StatementRequest} request - Exactly one of a sixteen-digit card number or an account
 *   identifier, the same selector the statement was rendered with.
 * @returns {Promise<StatementTransactionCollection>} The transactions, each with its card number
 *   masked.
 * @throws {RangeError} If any row carries an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 404 when the selector matches nothing.
 */
export async function listStatementTransactions(
  request: StatementRequest,
): Promise<StatementTransactionCollection> {
  const response = await getApiClient().post<StatementTransactionCollection>(
    requestPath(LIST_STATEMENT_TRANSACTIONS),
    request,
  );

  const body = response.data;
  for (const row of body.items) {
    requireMaskedCardNumber(row.cardNumber);
  }
  return body;
}

/**
 * Asserts that a card number arrived masked before it is rendered anywhere.
 *
 * Assumptions: what is checked is that the value HAS been masked, not merely that it is sixteen
 * characters, because the masker replaces every position but the last four. This module is the one
 * place in the SPA that SENDS an unmasked number, which is exactly why it checks what comes back:
 * a service echoing the request value instead of the masked rendering would be indistinguishable from
 * correct behaviour without this check. The rejected value is described and never reproduced.
 * @param {string} cardNumber - The rendering as the service sent it.
 * @throws {RangeError} If the value is not the masked form.
 */
function requireMaskedCardNumber(cardNumber: string): void {
  if (!MASKED_CARD_NUMBER.test(cardNumber)) {
    throw new RangeError(
      'Statement responses must carry a masked card number; an unmasked value was returned.',
    );
  }
}
