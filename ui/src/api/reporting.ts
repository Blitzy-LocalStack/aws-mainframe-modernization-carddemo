/**
 * @file Typed client for the reporting and statement bounded context, written against
 * `services/reporting-service/src/main/resources/openapi/reporting-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the eight operations that contract publishes: starting an on-demand transaction report in
 * place of the report-request screen `app/cbl/CORPT00C.cbl` drives, reading that run's status,
 * reading the report's detail lines and its three subtotal bands, collecting the report document
 * itself, and rendering the statement pair `app/cbl/CBSTM03A.CBL` produces together with the
 * transactions behind it and the two documents it wrote. Every target is derived from the operation
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
 * on untouched, and each document is either located or collected as an undecoded blob -- never parsed
 * here, and never re-rendered.
 *
 * Why two operations POST what they only read
 * -------------------------------------------
 * Assumptions: `generateStatement` and `listStatementTransactions` are POSTs although neither
 * changes anything this context owns, and the selector is the reason rather than the effect. Both are
 * selected by a sixteen-digit primary account number, which may not travel in a target: the edge
 * access log records a target in full before any application code runs and the browser retains it in
 * history, so a number placed there reaches two stores no application-side control can redact. A
 * request body is recorded by neither. `lookupCard` in `ui/src/api/cards.ts` takes the same
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

import {
  correlationHeaders,
  getApiClient,
  keysetPagingMembers,
  newCorrelationId,
  requestPath,
} from './client';
import { MASKED_CARD_NUMBER } from './masking';
import type {
  ContractOperation,
  PageResponse,
  ReportExecutionStatus,
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
  ReportExecutionStatus,
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

const READ_REPORT_EXECUTION: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reports/executions/{executionName}',
  operationId: 'readReportExecution',
};

const COLLECT_REPORT_ARTIFACT: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reports/transaction-report/artifact',
  operationId: 'collectReportArtifact',
};

const COLLECT_STATEMENT_ARTIFACT: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reports/statements/artifacts/{selector}',
  operationId: 'collectArtifact',
};

/**
 * Every operation `reporting-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`.
 *
 * Refactoring Rationale: three operations joined this manifest when the report and statement
 * lifecycles were given a read side. A submission returned a handle nothing consumed and a statement
 * answer carried locations no caller could open, so this client could show a submitted state and
 * nothing after it. The three are a run's status, the report document and a statement document.
 *
 * Refactoring Rationale: ⚠️ the run's status and the report document are listed in THIS order because
 * that is the order `reporting-api.yaml` declares its paths in -- `/reports/executions/{executionName}`
 * precedes `/reports/transaction-report/artifact` there. The two were the other way round, which made
 * the sentence above false about the one property it asserts. The order is not decorative: a reader
 * comparing this manifest with the document reads them side by side, and a claim of declaration order
 * that does not hold is worse than no claim, because it invites the comparison and then misdirects it.
 */
export const REPORTING_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  SUBMIT_TRANSACTION_REPORT,
  LIST_TRANSACTION_REPORT_LINES,
  READ_TRANSACTION_REPORT_TOTALS,
  READ_REPORT_EXECUTION,
  COLLECT_REPORT_ARTIFACT,
  GENERATE_STATEMENT,
  LIST_STATEMENT_TRANSACTIONS,
  COLLECT_STATEMENT_ARTIFACT,
];

/** HTTP status the submission answers when an execution was actually started. */
const HTTP_CREATED = 201;

/** The selector shape the contract publishes: twenty-two URL-safe characters, unpadded. */
const ARTIFACT_SELECTOR_SHAPE = '[A-Za-z0-9_-]{22}';

/** The one path parameter the artifact operation declares, spelled once for both uses below. */
const ARTIFACT_SELECTOR_PARAMETER = 'selector';

/**
 * The media type both document operations must ASK for, and the header they ask in.
 *
 * Refactoring Rationale: ⚠️ these two calls sent the client's own `Accept: application/json` and were
 * answered HTTP 406 before either handler ran. Both handlers declare
 * `produces = APPLICATION_OCTET_STREAM_VALUE` -- `ReportController.collectReportArtifact` and
 * `StatementController.collectArtifact` -- and this contract publishes that one media type on each, so
 * a request accepting only JSON is unsatisfiable by construction. `responseType: 'blob'` did not and
 * cannot help: it tells the transport how to MATERIALISE a body that has already arrived and sets no
 * request header at all, which is exactly why the defect was invisible to a reader checking that the
 * bytes were left undecoded.
 *
 * Assumptions: the value is stated once here and overridden per call rather than widened on the shared
 * client. Adding it to the singleton's defaults would make every one of the fifty-three operations
 * announce that it accepts octet-stream, and the two that produce bytes are the only two that do -- a
 * JSON operation that started answering bytes would then be accepted silently instead of refused.
 *
 * Alternatives Considered: removing the client's JSON default so each call states its own. Rejected
 * because fifty-one calls would then have to repeat one header, and the one that forgot would negotiate
 * whatever a gateway chose to send.
 */
const OCTET_STREAM_ACCEPT: Readonly<Record<string, string>> = {
  Accept: 'application/octet-stream',
};

/**
 * Matches a statement artifact location in the form a statement ANSWER publishes it, capturing the
 * selector inside it.
 *
 * Assumptions: derived from the manifest entry's own path rather than retyped, and anchored at both
 * ends, so the whole value is fixed rather than merely its beginning.
 *
 * Measured: the published form carries the version prefix. `reporting-api.yaml` L1571 declares
 * `^/api/v1/reports/statements/artifacts/[A-Za-z0-9_-]{22}$` on the two members a statement answer
 * returns, while `requestPath` in `./client` REMOVES that prefix because a build's base URL already
 * ends in it. The two forms are therefore handled separately and deliberately: this pattern is the
 * form that arrives, and the target dispatched below is composed from the manifest.
 */
const PUBLISHED_ARTIFACT_LOCATION = new RegExp(
  `^${COLLECT_STATEMENT_ARTIFACT.path.replace(
    `{${ARTIFACT_SELECTOR_PARAMETER}}`,
    `(${ARTIFACT_SELECTOR_SHAPE})`,
  )}$`,
  'u',
);

/*
 * WHY : Refactoring Rationale: the forward default declared here was withdrawn when the pair became
 *       `keysetPagingMembers` in `./client`. The reason for stating it EXPLICITLY on the request is
 *       unchanged and now lives on that guard: a sealed cursor carries the direction it was issued for
 *       and the service refuses a backward position replayed as a forward read, so the value that
 *       reaches the query is the one fact deciding whether a browse advances or is refused. What
 *       changed is that seven modules no longer each hold a copy of it.
 */

/**
 * Header the published contract accepts an optional submission key in.
 *
 * Assumptions: the name is stated once here rather than at the call site, because it is part of the
 * published contract -- `IdempotencyKeyHeader` in the reporting service's OpenAPI document -- and a
 * second spelling of it would be a second statement of one fact.
 */
const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';

/**
 * Mints one identity for one report submission, reusable across every attempt at that submission.
 *
 * Purpose: gives a screen a value it can hold while it retries a submission whose answer never
 * arrived, so the service recognises the second attempt as the same submission instead of starting a
 * second run of the same report.
 *
 * ⚠️ Assumptions: the identity is a correlation identifier, and it is one because the same value is sent
 * as BOTH headers this submission pins -- the submission key and the correlation identifier -- so it has
 * to satisfy both published domains at once, and the correlation domain is strictly the narrower of the
 * two. The reporting service accepts up to forty characters of letters, digits, hyphens and underscores
 * (`IDEMPOTENCY_KEY_MAX_LENGTH` and `IDEMPOTENCY_KEY_PATTERN` in
 * `services/reporting-service/src/main/java/com/carddemo/reporting/service/ReportExecutionService.java`).
 * The shared correlation filter accepts at most twenty-four characters, admits only letters, digits and
 * three separators, and additionally REFUSES a value that is a run of nine or more digits once separators
 * are removed. `newCorrelationId` already produces exactly the intersection: a two-character prefix
 * followed by twenty-two hexadecimal characters, twenty-four in all, whose leading letter is what keeps
 * it out of the refused digit-run shape by construction.
 *
 * Alternatives Considered: minting a wider identity of this module's own -- a UUID, or forty characters
 * of entropy to use the submission key's whole width. Rejected because the extra width is unusable: a
 * value the reporting service accepts but the correlation filter refuses is answered HTTP 400 before any
 * handler runs, so the submission would fail on the header meant to make it retryable. The eleven random
 * bytes behind the prefix are eighty-eight bits, far past any collision concern for a value that only has
 * to be unique among one operator's submissions.
 *
 * Alternatives Considered: deriving the identity from the submission itself -- a digest of the report
 * type and the two bounds. Rejected because it would make two DELIBERATE runs of one report over one
 * range indistinguishable, and the service remembers an execution name for ninety days -- a retention
 * `ReportExecutionService.start` documents on the orchestrator's behalf; the operator
 * would be told their second, intended submission was a duplicate. A minted identity separates "the same
 * submission again" from "the same report again", which is the distinction the retry needs.
 * @returns {string} A submission identity satisfying both the submission-key and the correlation-identifier
 *   contracts, suitable for {@link submitTransactionReport}'s second argument.
 */
export function newSubmissionKey(): string {
  return newCorrelationId();
}

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
 * @param {string} [submissionKey] - An optional identity for this SUBMISSION, minted by
 *   {@link newSubmissionKey}. Send the same identity again to retry a submission whose response was
 *   lost: the second attempt is then recognised as the same submission and answered with the run that
 *   already exists rather than starting a second one. It pins the request's correlation identifier as
 *   well as its submission key, so the attempts are also one unit of work in the logs. Omit it and each
 *   submission starts a distinct run, which is what allows one report to be produced again over one
 *   range.
 * @returns {Promise<ReportSubmissionOutcome>} One of THREE outcomes, read from the body's own
 *   `outcome` member. `STARTED` carries the execution handle -- its `executionName`, which
 *   `readReportExecution` is addressed by, and the resolved bounds the run received -- together with
 *   the submitted sentence. `DECLINED` is the caller answering no and carries a null sentence, because
 *   the reference writes none when a confirmation is declined. `UNANSWERED` is a confirmation left
 *   blank: nothing was started, nothing was declined, and the sentence is the reference's own prompt
 *   naming the report, so a screen asks again rather than reporting a cancellation. This NEVER resolves
 *   to the report itself: the document is assembled asynchronously and is polled through
 *   `readReportExecution`, read through `listTransactionReportLines` and `readTransactionReportTotals`,
 *   and collected through `collectReportArtifact`.
 * @throws {RangeError} If a started run arrives without the handle or the sentence the contract
 *   requires alongside it, if an unanswered turn arrives without the prompt, or -- before anything is
 *   sent -- if a supplied submission identity is one the shared correlation filter would refuse. That
 *   last case names a caller that minted its own identity instead of calling
 *   {@link newSubmissionKey}: the identity is sent as the correlation identifier too, so the wider
 *   submission-key domain alone is not enough for it.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 when no type was marked, the confirmation answer was UNRECOGNISED -- a blank answer is
 *   the `UNANSWERED` outcome at 200 and not a refusal -- or a supplied submission key was malformed;
 *   401 without a usable token; 403 without the required authority; 503 while the environment is not
 *   accepting mutating work, in which case nothing was applied and the same request succeeds unchanged
 *   once it reopens; and 500 otherwise.
 */
export async function submitTransactionReport(
  request: ReportRequest,
  submissionKey?: string,
): Promise<ReportSubmissionOutcome> {
  const response = await getApiClient().post<ReportSubmissionOutcomeBody>(
    requestPath(SUBMIT_TRANSACTION_REPORT),
    request,
    // Assumptions: the configuration argument is omitted entirely rather than carrying an undefined
    //   header, because tsconfig sets exactOptionalPropertyTypes and an explicit undefined would
    //   serialise the header name with no value on every submission that supplied no key. A submission
    //   that supplies none therefore leaves the correlation identifier to `./client`, which mints a
    //   fresh one per dispatch -- correct for a submission with no identity to preserve.
    // Assumptions: one identity is sent under BOTH names, and the second is not redundant. The service
    //   prefers the submission key and falls back to a digest of the correlation identifier when none
    //   arrives, so pinning the correlation identifier too keeps the deduplication identity stable even
    //   where the submission-key header does not survive the hop -- an allow list that omits it, a proxy
    //   that strips it -- and makes every attempt at one submission one unit of work in the logs.
    submissionKey === undefined
      ? undefined
      : {
          headers: {
            [IDEMPOTENCY_KEY_HEADER]: submissionKey,
            ...correlationHeaders(submissionKey),
          },
        },
  );

  const body = response.data;
  // WHY : ⚠️ Refactoring Rationale: the outcome is READ from the body and was inferred from the
  //       status. Two of the three turns answer 200 -- a declined confirmation and one not yet
  //       answered -- so a status cannot separate them; this call labelled every non-created answer
  //       DECLINED and told a caller it had cancelled when it had merely not answered, dropping the
  //       prompt naming the report in the process. The status is still checked, below, but only for
  //       the started arm, where it and the member must agree.
  // WHY : Alternatives Considered: inferring the unanswered turn from the presence of a message on a
  //       200. Rejected because that is the same class of inference read from a different member, and
  //       it breaks the moment a declined answer carries a sentence -- which the contract permits no
  //       more and no less than it permitted an unanswered one to carry none.
  if (body.outcome === 'DECLINED') {
    return { outcome: 'DECLINED', message: null };
  }
  if (body.outcome === 'UNANSWERED') {
    if (body.message === null) {
      throw new RangeError(
        'An unanswered report confirmation must carry the prompt naming the report.',
      );
    }
    return { outcome: 'UNANSWERED', message: body.message };
  }
  if (response.status !== HTTP_CREATED) {
    throw new RangeError(
      'A started report submission must be answered with HTTP 201; the status and the outcome disagree.',
    );
  }

  // WHY : Refactoring Rationale: the handle is read from `body.submission` rather than from the body
  //       itself. An earlier revision cast the whole body to the handle type, which looked for the
  //       handle one level above where the contract declares it, so every member of a started run read
  //       as undefined while the call still reported success.
  // WHY : ⚠️ Assumptions: the handle a caller receives carries `executionName`, which is exactly what
  //       `readReportExecution` below takes. It carried the orchestration ARN in full, and the pair did
  //       not compose: that operation is addressed by name, so a caller polling with what it had been
  //       given had the ARN's colons and slashes percent-encoded into one segment and was refused with
  //       HTTP 400. Starting a report and then observing it is one flow, and a handle that cannot be
  //       replayed to the next operation in that flow is not a handle.
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
 * @throws {RangeError} If a direction is supplied without a cursor, which names no position to move
 *   from and which the contract refuses.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a malformed bound or for a cursor replayed against the direction it was sealed for,
 *   401 without a usable token, 403 without the required authority, and 500 otherwise.
 */
export async function listTransactionReportLines(
  query: ReportRangeQuery,
): Promise<PageResponse<TransactionReportLine>> {
  // Assumptions: a direction with no cursor is refused before the window is assembled, rather than
  //   dropped as it previously was. This read is the one paged operation whose other two members are
  //   MANDATORY, so a dropped direction here produced the most misleading outcome of the seven: a
  //   fully-populated opening window over the requested date range, which looks like a correct answer
  //   to a backward step rather than like the caller defect it is.
  const params: Record<string, string> = { startDate: query.startDate, endDate: query.endDate };
  // Refactoring Rationale: ⚠️ the direction is sent only ALONGSIDE a cursor, and supplying one without
  //   a cursor is now REFUSED rather than dropped. Sending it on an opening read would name a position
  //   that does not exist; dropping it silently answered the opening window to a caller that had asked
  //   to move, which for a report read means the same page of lines returned under a different request.
  const paging = keysetPagingMembers(query.cursor, query.direction);
  if (paging !== undefined) {
    params.cursor = paging.cursor;
    params.direction = paging.direction;
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

// WHY : Assumptions: the three reads below are the LIFECYCLE half of this client, and they exist
//       because a submission returned a handle nothing consumed. `app/cbl/CORPT00C.cbl` L462 performs
//       SUBMIT-JOB-TO-INTRDR and receives nothing back at all -- the queue definition at
//       `app/csd/CARDDEMO.CSD` L501 maps to the internal reader with ERROROPTION(IGNORE), so even a
//       failed write is silent -- so an operator learned an outcome by looking somewhere else
//       entirely. Reporting a run's status and handing back the document it produced is therefore a
//       documented improvement rather than a port, and it is the reason a screen can show more than a
//       submitted state.
// WHY : Assumptions: both documents arrive as a BINARY blob and neither is parsed here. The
//       plain-text statement is the byte-for-byte parity artifact compared against the committed
//       golden masters and the report line is fixed at 133 columns with COBOL edit masks, so a client
//       that decoded either into text would become a second renderer of an artifact a golden-master
//       comparison checks byte for byte. Trade-offs: a caller receives a blob it must download or hand
//       to an object URL rather than a string it can inspect, which is the point.
// WHY : Assumptions: the two documents are addressed DIFFERENTLY on purpose. A statement is reached
//       through an opaque selector the service minted, because a statement names one cardholder; a
//       report is reached through its type and its two date bounds in the clear, because those
//       describe a query and name no person -- and an operator holding the coordinates of a run must
//       be able to construct its location, which is what a runbook does.

/**
 * Reports what became of one submitted report run.
 *
 * Refactoring Rationale: ⚠️ this read is composable with the submission above, and it was not. It takes
 * the execution NAME, which is what the contract publishes on this path; the submission answered with
 * the orchestration ARN in full, so the value a caller held was the one value this operation refuses --
 * percent-encoded into a single segment and answered with HTTP 400 on the published shape. Both sides
 * were corrected rather than one bent to the other: `ReportSubmission.executionName` is now the handle
 * a submission returns, and this parameter is unchanged.
 * @param {string} executionName - The execution NAME a submission returned in
 *   `ReportSubmission.executionName`, never an ARN. The service composes the ARN from its own
 *   configured state machine, so a name is all a caller can supply and all it needs to.
 * @returns {Promise<ReportExecutionStatus>} The orchestration status, the two instants, the three
 *   coordinates when the run was started through this surface, and the document's location and write
 *   instant once the run has succeeded and the store holds it.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a name outside the published shape, 401 without a usable token, 403 without the
 *   required authority, 404 when no run of that name is known -- which is also the answer for a run
 *   the orchestrator has forgotten, so the two are deliberately indistinguishable -- and 500
 *   otherwise.
 */
export async function readReportExecution(executionName: string): Promise<ReportExecutionStatus> {
  const response = await getApiClient().get<ReportExecutionStatus>(
    requestPath(READ_REPORT_EXECUTION, { executionName }),
  );
  return response.data;
}

/**
 * Collects the transaction report document one run produced.
 * @param {string} reportType - The report type token the run was started for, one of the four the
 *   contract publishes. Sent exactly as given; no token is derived here.
 * @param {string} startDate - Inclusive lower bound of the run's processing-date range.
 * @param {string} endDate - Inclusive upper bound of the run's processing-date range.
 * @returns {Promise<Blob>} The 133-column document exactly as the service wrote it, undecoded.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a type outside the published set or a malformed bound, 401 without a usable token,
 *   403 without the required authority, 404 when no document exists for those coordinates -- which is
 *   the answer both for a run that never happened and for one whose document a lifecycle rule has
 *   expired -- and 500 otherwise. A refusal arrives as JSON even though the success body is bytes, and
 *   `./client` decodes it back into the shared document, so the `fieldErrors` of a 400 survive here as
 *   they do on every other operation.
 */
export async function collectReportArtifact(
  reportType: string,
  startDate: string,
  endDate: string,
): Promise<Blob> {
  const response = await getApiClient().get<Blob>(requestPath(COLLECT_REPORT_ARTIFACT), {
    params: { type: reportType, startDate, endDate },
    // Assumptions: the two members below are BOTH required and neither substitutes for the other.
    //   {@link OCTET_STREAM_ACCEPT} decides whether the service answers at all -- without it this
    //   operation's only published media type is unacceptable to the request and Spring refuses with
    //   406 before the handler runs -- while `responseType` decides what the transport does with the
    //   bytes once they arrive. Sending one without the other fails in a way the other cannot report.
    headers: OCTET_STREAM_ACCEPT,
    responseType: 'blob',
  });
  return response.data;
}

/**
 * Collects one statement document from a location a statement answer reported.
 * @param {string} location - The location as `generateStatement` returned it, either
 *   `plainTextUri` or `htmlUri`, in the published form that carries the version prefix. The selector
 *   inside it is opaque: it is matched and carried through unchanged, never composed or shortened.
 * @returns {Promise<Blob>} The document exactly as the service wrote it, undecoded.
 * @throws {RangeError} If the location is not one this contract publishes, which is what stops a
 *   value from another response being sent to this operation.
 * @throws {Error} The normalised failure from `./client`, carrying the shared `ApiError` document:
 *   HTTP 400 for a selector outside the published shape, 401 without a usable token, 403 without the
 *   required authority, 404 when the selector names no stored document, and 500 otherwise. As with the
 *   report document above, a refusal arrives as JSON while the success body is bytes, and `./client`
 *   decodes it back into the shared document.
 */
export async function collectArtifact(location: string): Promise<Blob> {
  // WHY : Assumptions: the location is MATCHED against the form a statement answer publishes, and the
  //       selector it carries is then handed, unchanged, to the manifest's own template. Neither half
  //       is optional. The value arrives carrying the version prefix that a build's base URL already
  //       supplies, so dispatching it as it stands would address `/api/v1/api/v1/...`; and the
  //       selector is never synthesised here, so a document this client was not told about stays
  //       unaddressable -- which is the whole point of an opaque token.
  // WHY : Alternatives Considered: slicing the prefix off the validated value and dispatching the
  //       remainder, which needs no capture group. Rejected because it states the prefix rule a second
  //       time when `requestPath` already owns it, and the two statements would drift apart the moment
  //       the gateway published a different prefix.
  const published = PUBLISHED_ARTIFACT_LOCATION.exec(location);
  const selector = published?.[1];
  if (selector === undefined) {
    throw new RangeError(
      'A statement artifact location must be one this contract publishes; it is never composed here.',
    );
  }

  const response = await getApiClient().get<Blob>(
    requestPath(COLLECT_STATEMENT_ARTIFACT, { [ARTIFACT_SELECTOR_PARAMETER]: selector }),
    // Assumptions: the statement document is negotiated on exactly the terms the report document is,
    //   and for the same reason: `StatementController.collectArtifact` declares one produced media type
    //   and it is not JSON, so the shared client's default Accept made this operation unsatisfiable.
    //   The two calls share {@link OCTET_STREAM_ACCEPT} rather than each spelling the media type, so a
    //   correction to one cannot leave the other behind.
    { headers: OCTET_STREAM_ACCEPT, responseType: 'blob' },
  );
  return response.data;
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
