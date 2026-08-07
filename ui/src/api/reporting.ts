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

import { getApiClient } from './client';
import { requestPath } from './types';
import type { ContractOperation, PageDirection, PageResponse } from './types';

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

/**
 * The report-request screen's whole named surface, as the submission accepts it.
 *
 * Assumptions: THIRTEEN members, which is the component list of
 * `com.carddemo.reporting.dto.ReportRequest` name for name, and the count is arithmetic rather than a
 * selection: `app/bms/CORPT00.bms` declares 42 field definitions of which 17 carry a name, and six of
 * those 17 are the two three-part range composites that the contract consolidates into one value each.
 *
 * Assumptions: every member is optional because which of them must be supplied depends on the report
 * type selected, and no member is unconditionally present. Six of them are screen furniture the
 * request echoes -- the transaction name, the two title lines, the program name and the two stamps --
 * and a browser normally omits all six; they are declared because the contract declares them, so a
 * screen replaying a received request body can round-trip it unchanged.
 */
export interface ReportRequest {
  readonly transactionName?: string | undefined;
  readonly title01?: string | undefined;
  readonly currentDate?: string | undefined;
  readonly programName?: string | undefined;
  readonly title02?: string | undefined;
  readonly currentTime?: string | undefined;
  readonly monthly?: string | undefined;
  readonly yearly?: string | undefined;
  readonly custom?: string | undefined;
  readonly startDate?: string | undefined;
  readonly endDate?: string | undefined;
  readonly confirm?: string | undefined;
  readonly errorMessage?: string | undefined;
}

/** The outcome of a submission the caller declined to confirm: nothing was started. */
export interface ReportSubmissionPreview {
  readonly submitted: false;
  readonly reportName: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly returnMessage?: string | null | undefined;
}

/**
 * A started report execution.
 *
 * Assumptions: `executionArn` is the orchestrator's identity for the run, and it has no counterpart in
 * the baseline: writing job-control text to the transient data queue returned no identity at all, so an
 * operator had to find the job by name. A caller may quote this value to support.
 */
export interface ReportSubmission {
  readonly executionArn: string;
  readonly reportName: string;
  readonly shortName: string;
  readonly longName: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly submittedAt: string;
}

/**
 * Which of the two submission outcomes occurred, discriminated so neither can be read as the other.
 *
 * Assumptions: the discriminant is derived from the HTTP status, which the contract makes normative --
 * 201 for a started execution and 200 for a declined confirmation. The baseline cannot draw this
 * distinction at all: inside `SUBMIT-JOB-TO-INTRDR` at `app/cbl/CORPT00C.cbl` L462, the branch for a
 * declined confirmation at L480 to L483 sets the same error flag as a validation failure and supplies
 * no message, so the screen redisplays cleared with no indication of which occurred.
 */
export type ReportSubmissionOutcome =
  | { readonly outcome: 'DECLINED'; readonly preview: ReportSubmissionPreview }
  | { readonly outcome: 'STARTED'; readonly submission: ReportSubmission };

/** One detail line of the transaction report, as the printed detail band carries it. */
export interface TransactionReportLine {
  readonly transactionId: string;
  readonly accountId: string;
  readonly typeCode: string;
  readonly typeDescription: string;
  readonly categoryCode: string;
  readonly categoryDescription: string;
  readonly source: string;
  readonly amount: string;
}

/** Which subtotal a band reports. */
export type ReportBand = 'PAGE' | 'ACCOUNT' | 'GRAND';

/**
 * One subtotal band of the report.
 *
 * Assumptions: `label` carries the verbatim literal its reference group declares at
 * `app/cpy/CVTRA07Y.cpy` L51, L57 and L63, and a caller renders it unchanged rather than deriving text
 * from `band`. Two irregularities defeat derivation: 'Account Total' is two words where the band name
 * is one, and all three literals are singular where their groups are plural.
 */
export interface ReportTotalBand {
  readonly band: ReportBand;
  readonly label: string;
  readonly amount: string;
}

/** The subtotal bands of one report run, in the order the report emits them. */
export interface TransactionReportTotals {
  readonly bands: readonly ReportTotalBand[];
}

/**
 * The selector a statement operation takes.
 *
 * Assumptions: both members are optional and exactly one is supplied, which is why neither is required
 * by the contract -- either may be the one omitted. The card number is the only unmasked account number
 * this module transmits, and it travels in a body for the reason recorded in this file's header.
 */
export interface StatementRequest {
  readonly cardNumber?: string | undefined;
  readonly accountId?: string | undefined;
}

/**
 * The summary of one rendered statement and where its two renderings were written.
 *
 * Assumptions: `accountId` is up to twenty characters here while a request accepts eleven digits, and
 * the asymmetry is the statement layout's rather than an inconsistency to normalise:
 * `app/cpy/COSTM01.CPY` declares a twenty-character carrier, and reporting the layout's width is what
 * keeps a value it can hold from being truncated on the way out.
 */
export interface Statement {
  readonly cardNumber: string;
  readonly accountId: string;
  readonly customerName: string;
  readonly totalAmount: string;
  readonly transactionCount: number;
  readonly plainTextUri: string;
  readonly htmlUri: string;
  readonly generatedAt: string;
}

/** One posted transaction as a statement carries it. */
export interface StatementTransaction {
  readonly cardNumber: string;
  readonly transactionId: string;
  readonly typeCode: string;
  readonly categoryCode: string;
  readonly source: string;
  readonly description: string;
  readonly amount: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly merchantCity: string;
  readonly merchantZip: string;
  readonly originTimestamp: string;
  readonly processingTimestamp: string;
}

/**
 * The transactions one statement was rendered from.
 *
 * Assumptions: not a page. A statement covers one card's posted transactions for one period and the
 * reference generator bounds that set itself, so there is no open-ended sequence for a cursor to walk.
 */
export interface StatementTransactionCollection {
  readonly items: readonly StatementTransaction[];
}

/** The inclusive processing-date range and paging position a report read is issued with. */
export interface ReportRangeQuery {
  readonly startDate: string;
  readonly endDate: string;
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

/**
 * Starts an on-demand transaction report execution, or reports that the caller declined.
 * @param {ReportRequest} request - The report type selection, the range for a caller-supplied run, and
 *   the confirmation answer. Set `confirm` to a 'Y' answer to start; omit it or answer 'N' to decline.
 * @returns {Promise<ReportSubmissionOutcome>} `STARTED` with the execution's identity when the service
 *   started one, otherwise `DECLINED` with the report that would have run.
 * @throws {Error} If the request fails, including HTTP 400 when no report type was selected or the
 *   confirmation answer was unrecognised.
 */
export async function submitTransactionReport(
  request: ReportRequest,
): Promise<ReportSubmissionOutcome> {
  const response = await getApiClient().post<ReportSubmissionPreview | ReportSubmission>(
    requestPath(SUBMIT_TRANSACTION_REPORT),
    request,
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'STARTED', submission: response.data as ReportSubmission };
  }
  return { outcome: 'DECLINED', preview: response.data as ReportSubmissionPreview };
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

  for (const row of response.data.items) {
    requireMaskedCardNumber(row.cardNumber);
  }
  return response.data;
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
