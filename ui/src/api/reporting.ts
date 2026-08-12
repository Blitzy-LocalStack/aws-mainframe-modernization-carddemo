/**
 * @file Typed client for the reporting and statement bounded context, written against
 * `services/reporting-service/src/main/resources/openapi/reporting-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the five operations that contract publishes: starting an on-demand transaction report in
 * place of the report-request screen `app/cbl/CORPT00C.cbl` drives, reading that report's detail
 * lines and its three subtotal bands, and rendering the statement pair `app/cbl/CBSTM03A.CBL`
 * produces together with the transactions behind it. Every target is derived from the operation
 * manifest below rather than written as a literal, for the reason recorded in `ui/src/api/types.ts`.
 *
 * Failures
 * --------
 * Every refusal reaches a caller as the one normalised failure `ui/src/api/client.ts` raises,
 * carrying the shared `ApiError` problem document whose `fieldErrors` array names each offending
 * property. This module contributes exactly one refusal of its own, and it is a check on what came
 * back rather than on what was sent: a statement answer whose primary account number arrived
 * unmasked is rejected here instead of being rendered.
 *
 * No report is composed here
 * --------------------------
 * Assumptions: the printed artifact is assembled by the service, and this module never reproduces
 * its layout. `app/cpy/CVTRA07Y.cpy` L48 declares `01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'`,
 * which sets the printed width at 133 columns, and the amount fields carry COBOL edit masks whose
 * sign character DIFFERS between bands: the detail amount at L30 leads with a MINUS, while the Page,
 * Account and Grand totals at L54, L60 and L66 each lead with a PLUS. The heading block at L4 to L13
 * is just as literal -- a 38-character short name 'DALYREPT', a 41-character long name 'Daily
 * Transaction Report', a 12-character 'Date Range: ' caption, two 10-character bounds and a
 * 4-character ' to ' separator between them. A browser that re-derived any of that would become a
 * second renderer of an artifact the golden-master comparison checks byte for byte, and the
 * one-character difference between those two mask families is exactly what a reimplementation
 * smooths over. Every amount below therefore arrives as an already-rendered string this module hands
 * on untouched, and the documents themselves arrive as locations.
 *
 * Why two operations POST what they only read
 * -------------------------------------------
 * Assumptions: `generateStatement` and `listStatementTransactions` are POSTs although neither
 * changes anything this context owns, and the selector is the reason rather than the effect. Both are
 * selected by a sixteen-digit primary account number, which may not travel in a target: the edge
 * access log records a target in full before any application code runs and the browser retains it in
 * history, so a number placed there reaches two stores no application-side control can redact. A
 * request body is recorded by neither. `lookupCardByNumber` in `ui/src/api/cards.ts` takes the same
 * decision for the same reason.
 *
 * Why a rendered statement arrives as a pair of locations
 * ------------------------------------------------------
 * Assumptions: both statement renderings are produced by the service, both are returned by location
 * rather than inline, and neither is negotiated -- there is no format selector in this module because
 * the contract publishes none. `app/cbl/CBSTM03A.CBL` writes both through its file-handling
 * subprogram `app/cbl/CBSTM03B.CBL` over the record layout `app/cpy/COSTM01.CPY`, and its file
 * descriptions at L45 and L47 declare an 80-byte plain-text record and a 100-byte markup record. The
 * plain-text form is the byte-for-byte parity artifact compared against the committed golden masters,
 * so inlining it into a JSON string would require an escaping step that no longer preserves those
 * bytes. A location leaves the artifact untouched, which is why this module requests a statement and
 * links to it and never assembles one.
 */

import { getApiClient, requestPath } from './client';
import type {
  ContractOperation,
  PageResponse,
  ReportRangeQuery,
  ReportRequest,
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
 *       declared here, so every consumer's import path is unchanged while each shape has one
 *       definition. Declaring them locally is what let an earlier revision describe a body the
 *       service does not emit, because a second declaration has nothing holding it to the contract.
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

/**
 * Reading direction the contract applies when a cursor is supplied without one.
 *
 * Assumptions: sent EXPLICITLY rather than relied upon, even though the contract declares the same
 * default. A sealed cursor carries the direction it was issued for, and the service refuses a
 * backward position replayed as a forward read, so the value that reaches the query is the one fact
 * that decides whether a browse advances or is refused -- naming it here keeps that fact visible at
 * the call rather than resident in a default a reader has to look up.
 */
const DEFAULT_PAGING_DIRECTION = 'next';

/**
 * Header the published contract accepts an optional submission key in.
 *
 * Assumptions: the name is stated once here rather than at the call site, because it is part of the
 * published contract -- `IdempotencyKeyHeader` in the reporting service's OpenAPI document -- and a
 * second spelling of it would be a second statement of one fact.
 */
const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';

// WHY : Refactoring Rationale: submitting a report STARTS an execution, and this call resolves to a
//       HANDLE for that execution rather than to the report body. The baseline reached the same
//       asynchrony through a queue: `app/cbl/CORPT00C.cbl` L462 performs SUBMIT-JOB-TO-INTRDR, whose
//       L515 paragraph issues `EXEC CICS WRITEQ TD QUEUE ('JOBS')` at L517 to L518, and
//       `app/csd/CARDDEMO.CSD` L499 defines that queue -- described at L500 as 'SUBMIT JOBS FROM
//       CICS' -- with L501 mapping it to the internal reader through DDNAME(INREADER). Writing to it
//       returned the operator to the screen at once and produced no document, so a client written to
//       expect the report inline would appear to hang and would then time out against a genuinely
//       long assembly. The consequence for a caller is concrete: show a submitted state and then read
//       the detail and totals operations. The corollary for this package is that no separate batch
//       client exists at all -- the batch module is argument-driven and invoked by the orchestrator,
//       never by a browser, so this operation is the only entry the browser has into orchestrated
//       work.
// WHY : Assumptions: the report type is one of exactly three choices -- the current month, the
//       current year, or a caller-supplied range -- and the screen expresses that as three
//       independent one-character marks: MONTHLYI at `app/cpy-bms/CORPT00.CPY` L60, YEARLYI at L66
//       and CUSTOMI at L72. The published contract keeps all three as separate members instead of
//       collapsing them into one closed value, and that is a deliberate reading of the reference
//       rather than an oversight: `app/cbl/CORPT00C.cbl` evaluates them as a condition chain whose
//       first matching arm wins -- monthly at L213, yearly at L239, custom at L256 -- so marking two
//       is ACCEPTED and the earliest mark is the one that runs.
// WHY : Alternatives Considered: one closed value admitting a single type, which would make a double
//       selection unrepresentable rather than merely resolved. Rejected because it would also make a
//       request the service defines an outcome for impossible to express from the browser, which
//       turns a documented precedence rule into an apparent client defect the moment anyone exercises
//       it. Precedence is therefore documented at the request type and left to the service, and
//       `ReportRequest` in ./types carries the three marks the contract publishes.
// WHY : Refactoring Rationale: each range bound travels as ONE ten-character calendar value rather
//       than as the three parts the screen collected -- SDTMMI at `app/cpy-bms/CORPT00.CPY` L78,
//       SDTDDI at L84 and SDTYYYYI at L90 for the lower bound, EDTMMI at L96, EDTDDI at L102 and
//       EDTYYYYI at L108 for the upper. That split existed because a 3270 field could not carry a
//       masked date, not because any rule required it, so consolidating removes six opportunities to
//       submit an inconsistent partial bound such as a month with no year. Nothing observable is
//       given up: the stored form is already year-month-day ordered, which is what lets the service
//       compare the leading ten characters of a timestamp directly, so a lexical comparison and a
//       calendar comparison still agree. Both bounds are consequently sent as strings and this module
//       performs no arithmetic, defaulting or validation on them -- the edit rules live in
//       `com.carddemo.common.validation.DateEditValidator`, transcribed from `app/cbl/CSUTLDTC.cbl`,
//       and a second browser-side copy of a parity-critical rule would drift from it in silence.
// WHY : Assumptions: every bound is supplied by the caller and none is read from the workstation
//       clock, so two runs of one report over one range produce the same document. The baseline holds
//       the same discipline by injecting the value as a step argument -- `app/jcl/INTCALC.jcl` L22
//       passes `PARM='2022071800'` -- and `app/jcl/TRANREPT.jcl` L43 and L44 inject the report's own
//       two bounds the same way, where L47 to L48 then use them as a record-selection predicate
//       inclusive at both ends. Where the contract resolves a bound for one of the two preset types
//       it does so service-side through an injectable clock, so no default is invented here.
//       Submission is likewise an explicitly confirmed act and never a consequence of arriving
//       somewhere: the answer travels in `confirm`, at the one-character width of CONFIRMI at
//       `app/cpy-bms/CORPT00.CPY` L114, and a request naming no answer is refused rather than assumed.

/**
 * Starts an on-demand transaction report execution, or reports that the caller declined it.
 * @param {ReportRequest} request - The report-type marks, the range bounds a caller-supplied run
 *   reads, and the confirmation answer. Answer `confirm` with 'Y' to start; answer 'N' to decline.
 *   Both bounds are ten-character calendar values and are sent exactly as given.
 * @param {string} [idempotencyKey] - An optional key identifying this submission ATTEMPT. Send the
 *   same key again to retry a submission whose response was lost: the second attempt is then
 *   recognised as a duplicate and refused rather than starting a second run. Omit it -- the normal
 *   case -- and each submission starts a distinct run, which is what allows one report to be produced
 *   again over one range. At most forty characters, and only letters, digits, hyphens and
 *   underscores; anything else is refused with HTTP 400.
 * @returns {Promise<ReportSubmissionOutcome>} `STARTED` carrying the execution handle -- its
 *   `executionArn` identity and the resolved bounds the run received -- when the service accepted a
 *   run, otherwise `DECLINED` carrying only the sentence, which is null because the reference writes
 *   none when a confirmation is declined. This NEVER resolves to the report itself: the document is
 *   assembled asynchronously and is read afterwards through `listTransactionReportLines` and
 *   `readTransactionReportTotals`, or collected from the locations a statement reports.
 * @throws {RangeError} If a started run arrives without the handle or the sentence the contract
 *   requires alongside it.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 when no type was marked, the confirmation answer was absent or unrecognised, or a
 *   supplied submission key was malformed; 401 without a usable token; 403 without the required
 *   authority; 503 while the environment is not accepting mutating work, in which case nothing was
 *   applied and the same request succeeds unchanged once it reopens; and 500 otherwise.
 */
export async function submitTransactionReport(
  request: ReportRequest,
  idempotencyKey?: string,
): Promise<ReportSubmissionOutcome> {
  const response = await getApiClient().post<ReportSubmissionOutcomeBody>(
    requestPath(SUBMIT_TRANSACTION_REPORT),
    request,
    // Assumptions: the configuration argument is omitted entirely rather than carrying an undefined
    //   header, because tsconfig sets exactOptionalPropertyTypes and an explicit undefined would
    //   serialise the header name with no value on every submission that supplied no key.
    idempotencyKey === undefined
      ? undefined
      : { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } },
  );

  const body = response.data;
  if (response.status !== HTTP_CREATED) {
    return { outcome: 'DECLINED', message: body.message };
  }

  // WHY : Refactoring Rationale: the handle is read from `body.submission` rather than from the body
  //       itself. An earlier revision cast the whole body to the handle type, which looked for
  //       `executionArn` one level above where the contract declares it, so every member of a started
  //       run read as undefined while the call still reported success.
  // WHY : Alternatives Considered: asserting both members with a type assertion, which is what this
  //       did once the nesting was addressed, and substituting an empty sentence for an absent one.
  //       The assertion was rejected because it states a guarantee without checking it, so a service
  //       that broke the contract would surface as undefined members read at a distance from the call
  //       that produced them. The empty sentence was rejected because a blank message band renders as
  //       a deliberate silence, which is indistinguishable from correct behaviour. Checking here names
  //       the breach at the boundary that received it, which is the same discipline
  //       `assertCardNumberMasked` below applies to a masked value.
  const { submission, message } = body;
  if (submission === null || message === null) {
    throw new RangeError(
      'A started report submission must carry both its execution handle and its message.',
    );
  }
  return { outcome: 'STARTED', submission, message };
}

// WHY : Assumptions: a browse is positioned by a sealed cursor and never by a counted row position,
//       and the reference is what settles it. `app/cbl/COCRDLIC.cbl` L230 to L244 carries a last-key
//       pair, a first-key pair, a screen number, a last-window flag and a next-window indicator
//       across the pseudo-conversational gap, so the browse state the baseline already kept IS a
//       keyset position. Reading by counted position instead would omit and repeat rows under
//       concurrent inserts, which changes observable behaviour that a key-ordered browse does not.
//       Neither read below therefore accepts or constructs a numeric position of any kind, and the
//       cursor is relayed exactly as the service issued it -- it is enciphered and carries its own
//       authentication tag over the direction it was minted for, so editing or decoding one turns a
//       valid browse into an HTTP 400.
// WHY : Assumptions: both operations answer with amounts already rendered by the service, and the
//       range predicate is inclusive at BOTH ends -- `app/cbl/CBTRN03C.cbl` L173 to L174 tests the
//       leading ten characters of the processing timestamp with a greater-or-equal and a
//       less-or-equal pair. The corollary a caller depends on is that a bound named here is itself
//       part of the selected set, so narrowing a range by one day drops that day's rows entirely.

/**
 * Reads one window of transaction report detail lines over an inclusive processing-date range.
 * @param {ReportRangeQuery} query - Both range bounds, plus an optional sealed cursor and the
 *   direction it was issued for. Omit the cursor to read the opening window.
 * @returns {Promise<PageResponse<TransactionReportLine>>} One bounded window of detail lines, each
 *   carrying its amount as an already-rendered string, together with the two sealed boundaries a
 *   following or preceding read is issued from and whether a following window exists.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a malformed bound or for a cursor replayed against the direction it was sealed for,
 *   401 without a usable token, 403 without the required authority, and 500 otherwise.
 */
export async function listTransactionReportLines(
  query: ReportRangeQuery,
): Promise<PageResponse<TransactionReportLine>> {
  const params: Record<string, string> = { startDate: query.startDate, endDate: query.endDate };
  // Assumptions: the direction is sent only ALONGSIDE a cursor, because the contract declares it
  //   meaningful only there. Sending it on an opening read would name a position that does not exist.
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    params.direction = query.direction ?? DEFAULT_PAGING_DIRECTION;
  }

  const response = await getApiClient().get<PageResponse<TransactionReportLine>>(
    requestPath(LIST_TRANSACTION_REPORT_LINES),
    { params },
  );
  return response.data;
}

/**
 * Reads the three subtotal bands of the transaction report over an inclusive processing-date range.
 * @param {string} startDate - Inclusive lower bound of the processing-date range, as a ten-character
 *   calendar value. Sent exactly as given; no bound is derived here.
 * @param {string} endDate - Inclusive upper bound of the processing-date range, as a ten-character
 *   calendar value. Sent exactly as given; no bound is derived here.
 * @returns {Promise<TransactionReportTotals>} The bands in the order the report emits them, each with
 *   the verbatim label its reference group declares and its amount already rendered.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a malformed bound, 401 without a usable token, 403 without the required authority,
 *   and 500 otherwise.
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
 * @param {StatementRequest} request - Exactly one of a sixteen-digit primary account number or an
 *   eleven-digit account identifier.
 * @returns {Promise<Statement>} The heading figures, the assembled total as an already-rendered
 *   string, and the locations of the two renderings the service wrote -- never the documents
 *   themselves. The primary account number is echoed back masked.
 * @throws {RangeError} If the response carries an unmasked primary account number.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a selector that names neither value or both, 401 without a usable token, 403 without
 *   the required authority, 404 when the selector matches nothing -- and that refusal names no digit
 *   of the value searched for -- and 500 otherwise.
 */
export async function generateStatement(request: StatementRequest): Promise<Statement> {
  const response = await getApiClient().post<Statement>(requestPath(GENERATE_STATEMENT), request);
  assertCardNumberMasked(response.data.cardNumber);
  return response.data;
}

/**
 * Reads the transactions one statement was rendered from.
 * @param {StatementRequest} request - Exactly one of a sixteen-digit primary account number or an
 *   eleven-digit account identifier, the same selector the statement was rendered with.
 * @returns {Promise<StatementTransactionCollection>} The rows ordered by primary account number and
 *   then by transaction identifier, each with its number masked and its amount already rendered,
 *   together with the count the statement covers and whether the array stops short of that count.
 * @throws {RangeError} If any row carries an unmasked primary account number.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a selector that names neither value or both, 401 without a usable token, 403 without
 *   the required authority, 404 when the selector matches nothing, and 500 otherwise.
 */
export async function listStatementTransactions(
  request: StatementRequest,
): Promise<StatementTransactionCollection> {
  const response = await getApiClient().post<StatementTransactionCollection>(
    requestPath(LIST_STATEMENT_TRANSACTIONS),
    request,
  );

  const body = response.data;
  // Assumptions: every row is checked rather than the first, because the masking is applied per row
  //   by the service's mapper; a single unmasked row among masked ones is precisely the failure a
  //   sampled check would pass over.
  for (const row of body.items) {
    assertCardNumberMasked(row.cardNumber);
  }
  return body;
}

/**
 * Asserts that a primary account number arrived masked before it is rendered anywhere.
 *
 * Assumptions: what is checked is that the value HAS been masked, not merely that it is sixteen
 * characters, because the masker replaces every position but the last four. This module is the one
 * place in the application that SENDS an unmasked number, which is exactly why it checks what comes
 * back: a service echoing the request value instead of the masked rendering would be
 * indistinguishable from correct behaviour without this check.
 *
 * Alternatives Considered: naming the offending value in the message, which would make a report
 * easier to act on. Rejected because the message would then carry the very number the mask exists to
 * withhold, into logs and onto a screen. The rejected value is described and never reproduced.
 * @param {string} cardNumber - The rendering as the service sent it.
 * @returns {void} Nothing; the assertion either passes or raises.
 * @throws {RangeError} If the value is not the masked form.
 */
function assertCardNumberMasked(cardNumber: string): void {
  if (!MASKED_CARD_NUMBER.test(cardNumber)) {
    throw new RangeError(
      'Statement responses must carry a masked card number; an unmasked value was returned.',
    );
  }
}
