/**
 * @file Unit tests for the reporting client in `ui/src/api/reporting.ts`.
 *
 * Purpose
 * -------
 * Assert that each published reporting operation addresses the target its manifest entry declares
 * and shapes its request as the service contract requires, so a target edited at one of the two
 * places cannot diverge unnoticed from the other.
 *
 * Assumptions: the axios instance is stubbed, so these cases measure this package's request
 * construction and nothing about a running service.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient } from './client';
import {
  collectArtifact,
  collectReportArtifact,
  generateStatement,
  listStatementTransactions,
  listTransactionReportLines,
  newSubmissionKey,
  readReportExecution,
  readTransactionReportTotals,
  submitTransactionReport,
} from './reporting';

// Assumptions: the fixture carries the `/api/v1` operation prefix because
// `normalizeApiBaseUrl` requires it -- a base URL one segment short is refused at
// start-up rather than producing a 404 on every request. The prefix changes no
// assertion here: every case below asserts the RELATIVE request path.
const API_BASE_URL = 'https://api.carddemo.example/api/v1';

const CORRELATION_HEADER = 'X-Correlation-Id';

const CARD_NUMBER = '4859452612877065';

const MASKED_CARD_NUMBER = '************7065';

const HTTP_OK = 200;

const HTTP_CREATED = 201;

/**
 * The widest correlation identifier the services carry.
 *
 * Assumptions: twenty-four, spelled here as a literal rather than imported, for the reason
 * {@link OCTET_STREAM} is spelled as one -- this file states what the wire must carry independently of
 * the constant the module happens to hold it in, so a rename cannot change both sides at once and
 * assert nothing. It is `CORRELATION_ID_MAX_LENGTH` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`.
 */
const CORRELATION_ID_MAX_LENGTH = 24;

/**
 * The submission-key domain the reporting service publishes.
 *
 * Assumptions: `IDEMPOTENCY_KEY_PATTERN` and `IDEMPOTENCY_KEY_MAX_LENGTH` in
 * `services/reporting-service/src/main/java/com/carddemo/reporting/service/ReportExecutionService.java`
 * -- at most forty letters, digits, hyphens or underscores. A key outside it is refused with HTTP 400.
 */
const PUBLISHED_SUBMISSION_KEY_SHAPE = /^[A-Za-z0-9_-]{1,40}$/u;

/**
 * The narrower domain a value must also satisfy to travel as the correlation identifier.
 *
 * Assumptions: the same alphabet minus nothing but bounded at {@link CORRELATION_ID_MAX_LENGTH}, which
 * is why a submission identity is drawn from the INTERSECTION of the two contracts rather than from the
 * wider one: the identity is sent under both header names.
 */
const ACCEPTED_SUBMISSION_IDENTITY = new RegExp(
  `^[A-Za-z0-9._-]{1,${String(CORRELATION_ID_MAX_LENGTH)}}$`,
  'u',
);

/** One dispatched request, reduced to the parts these assertions are about. */
interface DispatchedRequest {
  method: string;
  url: string;
  params: Record<string, string>;
  body: unknown;
  // Assumptions: the requested response type is recorded because two operations return a stored
  //   document and BOTH must arrive undecoded. A client that let the transport parse those bytes --
  //   the default is a JSON parse -- would corrupt the very artifact the golden-master comparison
  //   checks byte for byte, and nothing else in this file could detect that.
  responseType: string;
  // Assumptions: ⚠️ the request HEADERS are recorded, and they were not. Recording the response type
  //   alone was what let a defect through in exactly this file's subject matter: the two document
  //   operations asked for their bytes undecoded and still sent the shared client's
  //   `Accept: application/json`, so both handlers -- each declaring octet-stream as its only produced
  //   media type -- answered 406 before running. The response type is what the transport does with a
  //   body; the Accept header is what decides whether there is one.
  headers: Record<string, string>;
}

let dispatched: DispatchedRequest[] = [];

let nextBody: unknown = {};

// Assumptions: the answered status is a settable fixture rather than a constant, because the
//   submission operation derives its outcome FROM the status -- 201 started, 200 declined -- so a
//   harness that could only answer one of the two could not tell the two readings apart at all.
let nextStatus: number = HTTP_OK;

/**
 * Parses a serialised request body back into the structure the caller passed.
 * @param {unknown} data - The value Axios placed on the configuration after its request transforms.
 * @returns {unknown} The parsed structure, or the value unchanged when it is not a JSON string.
 */
function parseBody(data: unknown): unknown {
  if (typeof data !== 'string') {
    return data;
  }
  return JSON.parse(data);
}

/**
 * Reduces Axios request headers to a plain record keyed by lower-cased name.
 *
 * Assumptions: names are lower-cased because a header name is case-insensitive on the wire while the
 * spelling a caller used survives on the request object -- the shared client sets `Accept` from its own
 * defaults and this module overrides it per call, so a case comparing the given spelling would be
 * asserting which of the two wrote it rather than what was sent.
 *
 * Assumptions: only primitive values are carried across. Axios's header bag is typed with an index
 * signature returning `any` and can hold a function for a lazily-computed header; a stringified
 * function would compare equal to nothing and reads in a failure message as noise.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Record<string, string>} One entry per header carrying a primitive value.
 */
function headersOf(config: AxiosRequestConfig): Record<string, string> {
  const headers: Record<string, string> = {};
  const source: unknown = config.headers;
  if (typeof source !== 'object' || source === null) {
    return headers;
  }
  for (const [name, value] of Object.entries(source as Record<string, unknown>)) {
    if (typeof value === 'string' || typeof value === 'number') {
      headers[name.toLowerCase()] = String(value);
    }
  }
  return headers;
}

/**
 * Records one dispatched request and answers it without a network call.
 *
 * Assumptions: the recorded URL is the one Axios was asked for rather than the fully resolved one,
 * because what these assertions fix is the path THIS module composes. The base URL is the client's
 * concern and is covered by client.test.ts.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A response carrying the queued body and the queued status.
 */
async function captureAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const params: unknown = config.params;
  dispatched.push({
    method: config.method ?? '',
    url: config.url ?? '',
    params: typeof params === 'object' && params !== null ? (params as Record<string, string>) : {},
    // Assumptions: the body arrives here ALREADY SERIALISED, because Axios runs its request
    //   transforms before handing the configuration to the adapter, so `config.data` is a JSON
    //   string rather than the object the caller passed. It is parsed back so the assertions can
    //   compare structures instead of comparing formatted text, which would fail on key order and
    //   on whitespace that neither this module nor the contract has any opinion about.
    body: parseBody(config.data),
    responseType: config.responseType ?? '',
    headers: headersOf(config),
  });
  return Promise.resolve({
    data: nextBody,
    status: nextStatus,
    statusText: 'OK',
    headers: {},
    config,
  } as AxiosResponse);
}

/** Supplies the build-time configuration the client validates before it is constructed. */
function stubBuildConfiguration(): void {
  dispatched = [];
  nextBody = {};
  nextStatus = HTTP_OK;
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', CORRELATION_HEADER);
  getApiClient().defaults.adapter = captureAdapter;
}

/** Restores the environment so no later file inherits this file's configuration. */
function restoreBuildConfiguration(): void {
  vi.unstubAllEnvs();
}

/**
 * Returns the single request these assertions dispatched.
 * @returns {DispatchedRequest} The one recorded request.
 * @throws {Error} If no request was dispatched, which the length assertion above normally reports
 *   first; the throw exists so the narrowed type is sound rather than asserted non-null.
 */
function onlyRequest(): DispatchedRequest {
  expect(dispatched).toHaveLength(1);
  const request = dispatched[0];
  // Assumptions: narrowed explicitly rather than asserted non-null, because tsconfig's strict set
  //   includes noUncheckedIndexedAccess and a non-null assertion is refused by the lint gate.
  if (request === undefined) {
    throw new Error('no request was dispatched');
  }
  return request;
}

/**
 * The plain-text statement location in the form the contract publishes it, version prefix included.
 *
 * Assumptions: taken from `reporting-api.yaml`'s own example for `plainTextUri` rather than composed
 * here, so the fixture below and the collect case agree with the document instead of with each other.
 */
const PLAIN_TEXT_ARTIFACT_LOCATION = '/api/v1/reports/statements/artifacts/0oL2fQ8xVn4tKpR7wZbY1s';

/** The markup statement location, from the same document's example for `htmlUri`. */
const HTML_ARTIFACT_LOCATION = '/api/v1/reports/statements/artifacts/9tB4mE7kXw2pQzA5nR8vLj';

/** One execution name, shaped as the reporting service mints them from a run's start instant. */
const EXECUTION_NAME = 'carddemo-transaction-report-2022-07-18T22-10-31Z';

/**
 * The media type both document operations must ask for.
 *
 * Assumptions: spelled here as a literal rather than imported from the client module, so this file
 * states what the wire must carry independently of the constant the module happens to hold it in. A
 * shared constant would let a rename change both sides at once and assert nothing.
 */
const OCTET_STREAM = 'application/octet-stream';

/**
 * Reports whether one recorded request asked for its body undecoded.
 *
 * Assumptions: named rather than written inline at the two filters below, because
 * `jsdoc/require-jsdoc` selects a function expression in every position and a block comment attached to
 * an inline argument is moved by Prettier onto the preceding expression.
 * @param {DispatchedRequest} request - One recorded request.
 * @returns {boolean} `true` when the request asked the transport for a blob.
 */
function asksForBytes(request: DispatchedRequest): boolean {
  return request.responseType === 'blob';
}

/**
 * Reports whether one recorded request expects a JSON document rather than bytes.
 *
 * Assumptions: written as the complement of {@link asksForBytes} rather than as a second rule, so the
 * two filters below partition the eight dispatches with no request able to fall into both or neither --
 * which is the property that makes the counts they assert add up to the whole module.
 * @param {DispatchedRequest} request - One recorded request.
 * @returns {boolean} `true` when the request did not ask the transport for a blob.
 */
function answersADocument(request: DispatchedRequest): boolean {
  return !asksForBytes(request);
}

/**
 * The body a run-status read answers with, carrying only the member these assertions read.
 *
 * Assumptions: partial on purpose. What this file fixes is the target and the request shape, so the
 * answer carries the one member the case asserts on; the full shape is pinned server-side by the
 * contract and its own tests, and restating it here would be a second statement of one fact.
 */
const RUN_STATUS_BODY = { executionName: EXECUTION_NAME, status: 'SUCCEEDED' };

/**
 * Stand-in for a stored document's bytes.
 *
 * Assumptions: a plain string rather than a Blob, because nothing here asserts on the payload -- the
 * assertions are that the request asked for the bytes undecoded and addressed the right target.
 */
const STORED_DOCUMENT_BYTES = 'CARDDEMO STATEMENT ARTIFACT';

/**
 * Returns a statement body whose card number is masked, as the contract declares it.
 *
 * Refactoring Rationale: the two locations were `s3://carddemo-datasets-dev/...` object keys. The
 * contract no longer publishes that form and no caller of it could open one -- the dataset bucket
 * admits only the VPC endpoint -- so `reporting-api.yaml` now declares `ArtifactLocation` as a
 * fifty-nine-character path beneath `/api/v1/reports/statements/artifacts/`. The fixture carries that
 * form, and the collect case below takes its location FROM this answer rather than from a literal,
 * which is what keeps the fixture, the client and the document tied to one another.
 * @returns {Record<string, unknown>} One statement summary with both rendering locations.
 */
function maskedStatement(): Record<string, unknown> {
  return {
    cardNumber: MASKED_CARD_NUMBER,
    accountId: '00000000011',
    customerName: 'PARITY CUSTOMER',
    totalAmount: '1234.56',
    transactionCount: 2,
    plainTextUri: PLAIN_TEXT_ARTIFACT_LOCATION,
    htmlUri: HTML_ARTIFACT_LOCATION,
    generatedAt: '2022-07-18 22:10:31.000000',
    firstRecord: 1,
    recordCount: 2,
  };
}

/** Asserts report submission posts the request body to the published submission path. */
async function submitsToThePublishedReportPath(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/reports/transaction-report');
  expect(request.body).toEqual({ monthly: 'X', confirm: 'Y' });
}

/**
 * Builds the body a started run answers with, shaped as the published contract shapes it.
 *
 * Refactoring Rationale: the three call sites below each stubbed this body inline and all three stubbed
 * it FLAT, carrying the execution handle's members at the top level. That agreed with the client they
 * were written against and with nothing else: `reporting-api.yaml` refs one `ReportSubmissionOutcome`
 * from both the 200 and the 201, and that schema nests the handle under `submission` beside a boolean and
 * a sentence. A fixture that reproduces the client's own mistake cannot detect it, so the shape is
 * single-sourced here and taken from the document.
 *
 * Refactoring Rationale: the discriminator is `outcome` and was the boolean `submitted`. Two of the
 * three published turns answer 200, so a boolean beside a status cannot tell a declined confirmation
 * from one not yet answered -- which is exactly the confusion the client made when it read the status
 * alone.
 *
 * Refactoring Rationale: ⚠️ the handle carries `executionName` and carried an `executionArn` literal.
 * The ARN was the defect this fixture had to stop reproducing: the status operation takes a NAME, so a
 * fixture that answered an ARN agreed with the client it was written against and with nothing the
 * lifecycle could actually do. The name here is {@link EXECUTION_NAME}, the same constant the status
 * case addresses its request with, which is what lets `composesTheStatusReadFromTheSubmittedHandle`
 * assert the two operations meet instead of asserting each in isolation.
 * @returns {Record<string, unknown>} The 201 body: the outcome, the composed sentence, and the nested
 *   handle.
 */
function startedSubmissionBody(): Record<string, unknown> {
  return {
    outcome: 'STARTED',
    message: 'Monthly report submitted for printing ...',
    submission: {
      executionName: EXECUTION_NAME,
      reportName: 'Monthly',
      shortName: 'MONTHLY',
      longName: 'Monthly Transaction Report',
      startDate: '2022-07-01',
      endDate: '2022-07-31',
      submittedAt: '2022-07-18 22:10:31.000000',
    },
  };
}

/**
 * Asserts a created status is read as a started execution whose handle is nested where it is published.
 *
 * Assumptions: the STATUS is what the outcome is asserted from, not a member of the body, because the
 * contract makes 201 normative for a started run. The baseline draws no such distinction at all -- the
 * declined branch of SUBMIT-JOB-TO-INTRDR sets the same flag a validation failure does -- so reading
 * the two outcomes apart is a documented improvement that only a status-driven assertion protects.
 *
 * Assumptions: the handle is reached through `submission` and the sentence is asserted beside it. Reading
 * the handle from the body's own top level was what an earlier revision did, and it is the one reading
 * this assertion exists to rule out.
 */
async function readsAStartedExecutionFromTheCreatedStatus(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  const outcome = await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  expect(outcome.outcome).toBe('STARTED');
  if (outcome.outcome !== 'STARTED') {
    throw new Error('the created status must be read as a started execution');
  }
  expect(outcome.submission.executionName).toBe(EXECUTION_NAME);
  expect(outcome.message).toBe('Monthly report submitted for printing ...');
}

/**
 * Asserts the handle a submission returns is the value the status read is addressed by.
 *
 * Purpose: this is the case a lifecycle whose two halves do not meet cannot pass, and no single-operation
 * assertion can replace it. The submission previously answered with the orchestration ARN in full while
 * this read takes a NAME, so both operations were individually correct against their own schemas and the
 * flow between them was impossible: the ARN reaches the target percent-encoded into one segment and is
 * refused on the published shape. The handle is therefore taken FROM the submission's answer and handed
 * straight to the read, with no literal in between -- a literal on both sides would pass with the two
 * spellings still disagreeing.
 *
 * Assumptions: the composed target is asserted as well as the request being made, because the shape of
 * the failure being ruled out is a target carrying `%3A` where the contract publishes a name.
 */
async function composesTheStatusReadFromTheSubmittedHandle(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  const outcome = await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  if (outcome.outcome !== 'STARTED') {
    throw new Error('the created status must be read as a started execution');
  }

  // Assumptions: the recorded dispatches are cleared so the status read is the one under assertion,
  //   for the same reason the statement-collect case clears them: reading the second of two would
  //   assert its position in a list rather than the target it addressed.
  dispatched = [];
  nextStatus = HTTP_OK;
  nextBody = RUN_STATUS_BODY;
  const status = await readReportExecution(outcome.submission.executionName);
  const request = onlyRequest();
  expect(request.url).toBe(`/reports/executions/${EXECUTION_NAME}`);
  expect(request.url).not.toContain('%3A');
  expect(status.executionName).toBe(EXECUTION_NAME);
}

/**
 * Asserts an ok status is read as a declined confirmation that carries no sentence.
 *
 * Refactoring Rationale: the stubbed body carried a report name and both range bounds and the assertions
 * read them back, which described a body the service has never emitted -- the published 200 carries
 * `outcome`, `message` and `submission` and nothing else. The absent sentence is asserted as NULL
 * rather than left unasserted, because the reference writes nothing on this branch at
 * `app/cbl/CORPT00C.cbl` L480 to L483 and an invented sentence was removed from the handler for that
 * reason; asserting null here is what keeps one from being reintroduced.
 *
 * Refactoring Rationale: the body now carries `outcome: 'DECLINED'` where it carried
 * `submitted: false`, and the case reads that member rather than the status. It shares HTTP 200 with
 * the unanswered turn below, so a status-driven reading cannot separate the two -- which is what the
 * sibling case proves by failing when the reading is restored.
 */
async function readsADeclinedConfirmationFromTheOkStatus(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = { outcome: 'DECLINED', message: null, submission: null };
  const outcome = await submitTransactionReport({ monthly: 'X', confirm: 'N' });
  expect(outcome.outcome).toBe('DECLINED');
  if (outcome.outcome !== 'DECLINED') {
    throw new Error('the ok status must be read as a declined confirmation');
  }
  expect(outcome.message).toBeNull();
}

/**
 * Asserts an UNANSWERED confirmation is read as its own outcome carrying the reference's prompt.
 *
 * Purpose: this case is the one a status-driven reading cannot pass. It shares HTTP 200 with the
 * declined turn above and differs only in the `outcome` member and the sentence beside it, so a client
 * that inferred the outcome from the status labelled this turn a cancellation and discarded the prompt
 * naming the report -- which `app/cbl/CORPT00C.cbl` L464 to L474 composes and re-displays rather than
 * treating as a fault.
 *
 * Assumptions: the prompt is asserted by value here only because the fixture on the line above is the
 * one that supplies it; the sentence itself is assembled server-side from two verbatim reference
 * fragments and this module relays it untouched, so nothing about its wording is fixed by this file.
 *
 * Measured: restoring the status-driven reading -- `if (response.status !== HTTP_CREATED) return
 * { outcome: 'DECLINED', message: null }` -- fails exactly this case with `expected 'DECLINED' to be
 * 'UNANSWERED'`, while all twelve sibling cases keep passing. That division is the defect a review
 * found in this module, reproduced and then measured out of it.
 */
async function readsAnUnansweredConfirmationFromTheOkStatus(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = {
    outcome: 'UNANSWERED',
    message: 'Please confirm to print the Monthly report...',
    submission: null,
  };
  const outcome = await submitTransactionReport({ monthly: 'X' });
  expect(outcome.outcome).toBe('UNANSWERED');
  if (outcome.outcome !== 'UNANSWERED') {
    throw new Error('an unanswered confirmation must not be read as a cancellation');
  }
  expect(outcome.message).toBe('Please confirm to print the Monthly report...');
}

/**
 * Asserts the report read sends its required range and omits the direction without a cursor.
 *
 * Assumptions: the ABSENCE of `direction` is asserted, not merely the presence of the range. The
 * contract refuses `previous` sent without a cursor and defaults the parameter to `next`, so sending
 * a direction alone would describe a position relative to nothing.
 */
async function sendsTheRangeAndOmitsTheDirectionWithoutACursor(): Promise<void> {
  nextBody = { items: [], firstKey: null, lastKey: null, hasNext: false };
  await listTransactionReportLines({ startDate: '2022-07-01', endDate: '2022-07-31' });
  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/reports/transaction-report/lines');
  expect(request.params).toEqual({ startDate: '2022-07-01', endDate: '2022-07-31' });
}

/**
 * Asserts a direction supplied without a cursor is refused locally and never dispatched.
 *
 * Assumptions: ⚠️ this case is new because the behaviour changed. The client used to DROP a direction that
 * arrived without a cursor and answer the opening window, which is the combination every contract refuses
 * with a 400 keyed on the direction -- so a caller asking to move received the same lines back under a
 * different request and could not tell. `keysetPagingMembers` in `./client` now raises it for all seven
 * clients, and both halves are asserted here: that the call rejects, and that nothing reached the
 * transport, since asserting only the rejection would also pass against a client that let the service
 * refuse it.
 * @returns {Promise<void>} Resolves once both refusals and the empty dispatch log have been observed.
 */
async function refusesADirectionWithNoCursor(): Promise<void> {
  await expect(
    listTransactionReportLines({
      startDate: '2022-07-01',
      endDate: '2022-07-31',
      direction: 'previous',
    }),
  ).rejects.toThrow(RangeError);
  expect(dispatched, 'no request may be dispatched for a refused pair').toHaveLength(0);
}

/** Asserts a supplied cursor is sent under the published parameter name with its direction. */
async function sendsTheCursorUnderThePublishedParameterName(): Promise<void> {
  nextBody = { items: [], firstKey: null, lastKey: null, hasNext: false };
  await listTransactionReportLines({
    startDate: '2022-07-01',
    endDate: '2022-07-31',
    cursor: 'opaque-token',
    direction: 'previous',
  });
  const request = onlyRequest();
  // Assumptions: the assertion names `cursor` explicitly and checks that neither response member
  //   name appears, because sending `firstKey` or `lastKey` — which the contract declares as
  //   RESPONSE members and not as inputs — would leave the server seeing no cursor and answering the
  //   first page to every paging request, silently.
  expect(request.params.cursor).toBe('opaque-token');
  expect(request.params.direction).toBe('previous');
  expect(request.params.firstKey).toBeUndefined();
  expect(request.params.lastKey).toBeUndefined();
}

/**
 * Asserts the subtotal read reaches its own published target with the same range.
 *
 * Assumptions: the totals are asserted to be a SEPARATE target rather than a member of the detail
 * page, because the three bands the reference generator emits at `app/cpy/CVTRA07Y.cpy` are totals over
 * the whole range and not over the page: folding them into a page response would make each page report
 * a different grand total.
 */
async function sendsTheRangeToTheTotalsOperation(): Promise<void> {
  nextBody = { bands: [] };
  await readTransactionReportTotals('2022-07-01', '2022-07-31');
  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/reports/transaction-report/totals');
  expect(request.params).toEqual({ startDate: '2022-07-01', endDate: '2022-07-31' });
}

/**
 * Asserts the statement selector travels in the body and never in the request target.
 *
 * Assumptions: what is asserted is that the composed target holds NO digit at all, which is stronger
 * than checking the card number is absent from it. The edge access log records a target in full before
 * any application code runs and the browser retains it in history, so an account number placed there
 * reaches two stores no application-side control can redact; a digit-free target cannot carry either
 * selector by accident. This is the assertion that would have caught the withdrawn path form that
 * addressed a statement by its card number.
 */
async function sendsTheStatementSelectorInTheBody(): Promise<void> {
  nextBody = maskedStatement();
  await generateStatement({ cardNumber: CARD_NUMBER });
  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/reports/statements');
  expect(/[0-9]/u.test(request.url)).toBe(false);
  expect(request.body).toEqual({ cardNumber: CARD_NUMBER });
}

/** Asserts an account selector reaches the same target, so neither selector implies a target shape. */
async function sendsAnAccountSelectorToTheSameTarget(): Promise<void> {
  nextBody = maskedStatement();
  await generateStatement({ accountId: '00000000011' });
  const request = onlyRequest();
  expect(request.url).toBe('/reports/statements');
  expect(request.body).toEqual({ accountId: '00000000011' });
}

/**
 * Asserts a statement whose card number came back unmasked is refused.
 *
 * Assumptions: the refusal is asserted on the RESPONSE rather than before dispatch, because this
 * module is the one place in the SPA that legitimately SENDS an unmasked number -- so what needs
 * fixing is that the service does not echo it back. A service returning the request value instead of
 * the masked rendering would otherwise be indistinguishable from correct behaviour.
 */
async function refusesAnUnmaskedCardNumberInTheStatementResponse(): Promise<void> {
  nextBody = { ...maskedStatement(), cardNumber: CARD_NUMBER };
  await expect(generateStatement({ cardNumber: CARD_NUMBER })).rejects.toBeInstanceOf(RangeError);
}

/**
 * Asserts the transactions read posts the selector to its own published target.
 *
 * Assumptions: the stub carries all THREE published members rather than the rows alone. The body
 * declares a true count and a truncation flag beside its window, and a fixture that omitted them would
 * describe a body this operation does not answer with.
 */
async function sendsTheSelectorForTheTransactionsOperation(): Promise<void> {
  nextBody = { items: [], transactionCount: 0, truncated: false };
  await listStatementTransactions({ cardNumber: CARD_NUMBER });
  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/reports/statements/transactions');
  expect(/[0-9]/u.test(request.url)).toBe(false);
  expect(request.body).toEqual({ cardNumber: CARD_NUMBER });
}

/**
 * Asserts an unmasked card number on ANY returned row is refused.
 *
 * Assumptions: the fixture masks the first row and leaves the second unmasked, so the assertion fails
 * a module that checked only the first element -- which is the shape a loop written as an index lookup
 * would silently take.
 */
async function refusesAnUnmaskedCardNumberInAnyTransactionRow(): Promise<void> {
  nextBody = {
    items: [
      { cardNumber: MASKED_CARD_NUMBER, transactionId: '000000000000001' },
      { cardNumber: CARD_NUMBER, transactionId: '000000000000002' },
    ],
    transactionCount: 2,
    truncated: false,
  };
  await expect(listStatementTransactions({ cardNumber: CARD_NUMBER })).rejects.toBeInstanceOf(
    RangeError,
  );
}

/**
 * Calls every operation this module publishes once, in the contract's own declaration order.
 *
 * Refactoring Rationale: this sequence was the body of the prefix case below and is now shared with the
 * negotiation case beside it. Two whole-module properties are asserted over the same eight dispatches --
 * that every target sits under the gateway's published prefix, and that exactly the two document
 * operations negotiate a byte stream -- and duplicating the sequence would let the two drift, so that a
 * ninth operation added to one would be absent from the other and the missing coverage would be
 * invisible.
 * @returns {Promise<void>} Resolves once all eight operations have been dispatched and recorded.
 */
async function dispatchEveryOperation(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' });

  nextStatus = HTTP_OK;
  nextBody = { items: [], firstKey: null, lastKey: null, hasNext: false };
  await listTransactionReportLines({ startDate: '2022-07-01', endDate: '2022-07-31' });

  nextBody = { bands: [] };
  await readTransactionReportTotals('2022-07-01', '2022-07-31');

  nextBody = maskedStatement();
  await generateStatement({ cardNumber: CARD_NUMBER });

  nextBody = { items: [], transactionCount: 0, truncated: false };
  await listStatementTransactions({ cardNumber: CARD_NUMBER });

  nextBody = RUN_STATUS_BODY;
  await readReportExecution(EXECUTION_NAME);

  nextBody = STORED_DOCUMENT_BYTES;
  await collectReportArtifact('monthly', '2022-07-01', '2022-07-31');
  await collectArtifact(PLAIN_TEXT_ARTIFACT_LOCATION);
}

/**
 * Asserts every path this module composes begins with the gateway-published prefix.
 *
 * Assumptions: this is asserted across all eight operations together rather than left implicit in the
 * per-operation assertions, because the property is about the whole module: the gateway publishes
 * only `ANY /api/v1/reports` and `ANY /api/v1/reports/{proxy+}` for this service, so a path this
 * module composed outside that prefix would be answered by the gateway's own 404 with no integration
 * attempted — a failure that looks like an outage rather than a client defect.
 */
async function composesEveryPathUnderTheReportsPrefix(): Promise<void> {
  await dispatchEveryOperation();

  expect(dispatched).toHaveLength(8);
  for (const request of dispatched) {
    expect(request.url.startsWith('/reports')).toBe(true);
  }
}

/**
 * Asserts exactly the two document operations negotiate bytes and the other six negotiate JSON.
 *
 * Purpose: ⚠️ this is the module-wide half of the negotiation contract, and it is what a binary
 * operation added later cannot quietly omit. Asserting the Accept header only at the two call sites
 * that have one would leave a third document operation free to ship with the shared client's JSON
 * default and be refused with 406 in a deployed environment -- which is precisely how the two here were
 * shipped. Stated as a partition of all eight operations, the case fails on a new blob request that
 * does not negotiate AND on a JSON request that starts asking for bytes.
 *
 * Assumptions: the partition is keyed on the requested response type rather than on a list of operation
 * names, so the rule is "every request that asks for undecoded bytes accepts a byte stream" rather than
 * "these two operations do". A list would have to be edited by the same author who forgot the header.
 */
async function everyDocumentOperationNegotiatesABinaryBody(): Promise<void> {
  await dispatchEveryOperation();

  expect(dispatched).toHaveLength(8);
  const binary = dispatched.filter(asksForBytes);
  const json = dispatched.filter(answersADocument);

  expect(binary).toHaveLength(2);
  for (const request of binary) {
    expect(
      request.headers.accept,
      `${request.url} asks for undecoded bytes, so it must accept a byte stream: both artifact` +
        ' handlers publish octet-stream as their only produced media type and answer 406 to a' +
        ' JSON-only Accept before running',
    ).toBe(OCTET_STREAM);
  }

  expect(json).toHaveLength(6);
  for (const request of json) {
    expect(
      request.headers.accept,
      `${request.url} answers a JSON document, so it must keep the shared client's own Accept`,
    ).toBe('application/json');
  }
}

/**
 * Asserts a run's status is read from the execution path, addressed by the run's NAME.
 *
 * Assumptions: the name is sent as the only path value and no handle is composed here. The contract
 * takes a name and composes the state-machine handle server-side, so a client that assembled one
 * would be asserting which machine ran the report -- a deployment fact it does not hold.
 */
async function readsARunStatusFromThePublishedExecutionPath(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = RUN_STATUS_BODY;
  const status = await readReportExecution(EXECUTION_NAME);
  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe(`/reports/executions/${EXECUTION_NAME}`);
  expect(status.executionName).toBe(EXECUTION_NAME);
}

/**
 * Asserts the report document is collected by its coordinates, undecoded.
 *
 * Assumptions: the three coordinates travel as query parameters exactly as given, because this
 * document is addressed by the range it covers rather than by an opaque selector -- a report names no
 * account, so there is nothing in those coordinates to withhold from a target.
 *
 * Assumptions: ⚠️ the ACCEPT header is asserted beside the response type, and it is the assertion this
 * case was missing. `ReportController.collectReportArtifact` declares
 * `produces = APPLICATION_OCTET_STREAM_VALUE`, so a request accepting only JSON -- which is what the
 * shared client sends by default -- is refused with 406 before the handler runs. `responseType: 'blob'`
 * sets no request header, so the two members are independent and only one of them decides whether the
 * service answers at all.
 */
async function collectsTheReportDocumentFromItsCoordinates(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = STORED_DOCUMENT_BYTES;
  await collectReportArtifact('monthly', '2022-07-01', '2022-07-31');
  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/reports/transaction-report/artifact');
  expect(request.params).toEqual({
    type: 'monthly',
    startDate: '2022-07-01',
    endDate: '2022-07-31',
  });
  expect(request.responseType).toBe('blob');
  expect(request.headers.accept).toBe(OCTET_STREAM);
}

/**
 * Asserts a statement document is collected from the location its own answer reported.
 *
 * Purpose: this is the case a client that confused the two path forms cannot pass. A statement answer
 * publishes its locations WITH the version prefix -- `ArtifactLocation` in `reporting-api.yaml` is
 * fifty-nine characters beginning `/api/v1/reports/statements/artifacts/` -- while the target
 * dispatched for it must omit that prefix, because a build's base URL already ends in it. The location
 * asserted here is therefore taken from the answer, and the target from the request.
 *
 * Measured: validating the caller's location against the request-path form instead -- the shape
 * `requestPath` returns, with the prefix removed -- fails exactly three of this file's seventeen
 * cases. This one and the whole-module case raise `A statement artifact location must be one this
 * contract publishes`, because no location the service returns has that shape; and `refuses a location
 * no answer could have carried` inverts, reporting `promise resolved "{}" instead of rejecting`,
 * because the wrong form is then the accepted one. That three-way division is the defect this case was
 * written against, and no other case in the file moves.
 */
async function collectsAStatementDocumentFromItsAnswer(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = maskedStatement();
  const statement = await generateStatement({ cardNumber: CARD_NUMBER });
  if (statement.plainTextUri === null) {
    throw new Error('the fixture must report a stored plain-text artifact');
  }

  // Assumptions: the recorded dispatches are cleared so the collect request is the one under
  //   assertion. The statement request that produced the location is asserted by its own case above,
  //   and reading this one out of a two-element list would assert its position rather than its target.
  dispatched = [];
  nextBody = STORED_DOCUMENT_BYTES;
  await collectArtifact(statement.plainTextUri);
  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/reports/statements/artifacts/0oL2fQ8xVn4tKpR7wZbY1s');
  expect(request.responseType).toBe('blob');
  // Assumptions: ⚠️ the statement document negotiates on the same terms the report document does, and
  //   it is asserted here rather than only in the module-wide case below, because
  //   `StatementController.collectArtifact` publishes octet-stream as its one produced media type and a
  //   JSON-only Accept is refused with 406 before the handler runs.
  expect(request.headers.accept).toBe(OCTET_STREAM);
}

/**
 * Returns one recorded request by position.
 *
 * Assumptions: a positional reader exists beside {@link onlyRequest} because the submission-identity
 * cases below are ABOUT the relationship between two dispatches, so neither of them can be the only one.
 * @param {number} index - Zero-based position in the order the requests were dispatched.
 * @returns {DispatchedRequest} The request at that position.
 * @throws {Error} If no request was dispatched at that position, so the narrowed type is sound rather
 *   than asserted non-null -- tsconfig's `noUncheckedIndexedAccess` makes the check load-bearing.
 */
function requestAt(index: number): DispatchedRequest {
  const request = dispatched[index];
  if (request === undefined) {
    throw new Error(`no request was dispatched at position ${String(index)}`);
  }
  return request;
}

/**
 * Asserts every attempt at ONE submission carries one submission key and one correlation identifier.
 *
 * Purpose: this is the case a retry cannot be deduplicated without. `ReportExecutionService` composes an
 * orchestration execution name from the report type, both bounds and a submission key, and it takes that
 * key from the `Idempotency-Key` header when one arrives and from a digest of the request's correlation
 * identifier when none does. A second attempt whose headers differ from the first therefore starts a
 * SECOND run of the same report, which is what an operator retrying after a client timeout produces.
 *
 * Assumptions: both headers are asserted, not just the submission key, because the client mints a fresh
 * correlation identifier for any request that carries none -- so a submission that pinned only the key
 * would still present a different fallback identity on its second attempt.
 */
async function sendsOneIdentityOnEveryAttemptAtOneSubmission(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  const identity = newSubmissionKey();
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' }, identity);
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' }, identity);
  expect(dispatched).toHaveLength(2);
  const first = requestAt(0);
  const second = requestAt(1);
  expect(first.headers['idempotency-key']).toBe(identity);
  expect(second.headers['idempotency-key']).toBe(identity);
  expect(first.headers['x-correlation-id']).toBe(identity);
  expect(second.headers['x-correlation-id']).toBe(identity);
}

/**
 * Asserts a distinct submission carries a distinct identity, and that the identity is one both
 * contracts accept.
 *
 * Purpose: the complement of the case above, and the reason the identity is minted rather than derived
 * from the request. Two deliberate runs of one report over one range must remain two runs, and the
 * service remembers an execution name for ninety days -- so an identity that repeated would have the
 * operator's second, intended submission refused as a duplicate.
 *
 * Assumptions: the shape is asserted against BOTH published domains rather than against the generator
 * that produced it. `IDEMPOTENCY_KEY_PATTERN` in the reporting service admits at most forty letters,
 * digits, hyphens and underscores; the shared correlation filter admits at most twenty-four and refuses
 * a run of nine or more digits once separators are removed. Asserting the intersection here is what
 * keeps a later change to the generator from producing a value one of the two sides would refuse with
 * HTTP 400.
 */
async function mintsADistinctIdentityForEachSubmission(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  const first = newSubmissionKey();
  const second = newSubmissionKey();
  expect(second).not.toBe(first);
  for (const identity of [first, second]) {
    expect(identity).toMatch(PUBLISHED_SUBMISSION_KEY_SHAPE);
    expect(identity).toMatch(ACCEPTED_SUBMISSION_IDENTITY);
    // Assumptions: a letter is required because the filter refuses a value that is a run of nine or
    //   more digits, and one letter anywhere disqualifies a value from that test before any digit is
    //   counted -- so this is the whole of the third condition rather than a sample of it.
    expect(identity).toMatch(/[A-Za-z]/u);
  }
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' }, first);
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' }, second);
  expect(requestAt(0).headers['idempotency-key']).toBe(first);
  expect(requestAt(1).headers['idempotency-key']).toBe(second);
}

/**
 * Asserts a submission with no identity sends no submission key and a fresh correlation identifier.
 *
 * Purpose: fixes the unchanged behaviour of the ordinary case, which is what makes the pinned case a
 * genuine opt-in. A submission with no identity to preserve has each attempt named separately, so one
 * report can be produced again over one range -- and the absent header is what leaves the service free
 * to derive its own key.
 */
async function omitsTheSubmissionKeyWithoutAnIdentity(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = startedSubmissionBody();
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  const first = requestAt(0);
  const second = requestAt(1);
  expect(first.headers['idempotency-key']).toBeUndefined();
  expect(second.headers['idempotency-key']).toBeUndefined();
  expect(first.headers['x-correlation-id']).toMatch(ACCEPTED_SUBMISSION_IDENTITY);
  expect(second.headers['x-correlation-id']).not.toBe(first.headers['x-correlation-id']);
}

/**
 * Asserts an identity the shared correlation filter would refuse fails at the call site.
 *
 * Assumptions: the rejected value is one the REPORTING service would accept -- forty letters is within
 * `IDEMPOTENCY_KEY_MAX_LENGTH` and matches its shape -- and it is refused here because the same value is
 * sent as the correlation identifier, whose published width is twenty-four. Failing before the dispatch
 * names the caller that minted its own identity instead of calling `newSubmissionKey`; sending it would
 * be answered HTTP 400 by the filter, which at a screen is indistinguishable from a rejected payload.
 */
async function refusesAnIdentityTheCorrelationContractWouldNotCarry(): Promise<void> {
  const tooWideForCorrelation = 'A'.repeat(CORRELATION_ID_MAX_LENGTH + 1);
  await expect(
    submitTransactionReport({ monthly: 'X', confirm: 'Y' }, tooWideForCorrelation),
  ).rejects.toBeInstanceOf(RangeError);
  expect(dispatched).toHaveLength(0);
}

/**
 * Asserts a location no statement answer could have carried is refused without being dispatched.
 *
 * Assumptions: the rejected value is the request-path form of a REAL location -- the same selector
 * with the version prefix removed. It is the closest wrong value there is, and refusing it is what
 * pins the client to the form the contract publishes rather than to any string ending in a
 * selector-shaped token. A caller only ever holds `plainTextUri` or `htmlUri`, and both carry the
 * prefix.
 */
async function refusesALocationNoAnswerCouldHaveCarried(): Promise<void> {
  const withoutPrefix = PLAIN_TEXT_ARTIFACT_LOCATION.replace('/api/v1', '');
  await expect(collectArtifact(withoutPrefix)).rejects.toBeInstanceOf(RangeError);
  expect(dispatched).toHaveLength(0);
}

/** Groups the assertions that fix this module's agreement with the reporting contract. */
function reportingClientContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it('submits to the published report path', submitsToThePublishedReportPath);
  it(
    'reads a started execution from the created status',
    readsAStartedExecutionFromTheCreatedStatus,
  );
  it('reads a declined confirmation from the ok status', readsADeclinedConfirmationFromTheOkStatus);
  it(
    'reads an unanswered confirmation from the ok status',
    readsAnUnansweredConfirmationFromTheOkStatus,
  );
  it(
    'sends the required range and omits the direction without a cursor',
    sendsTheRangeAndOmitsTheDirectionWithoutACursor,
  );
  it('refuses a direction with no cursor', refusesADirectionWithNoCursor);
  it(
    'sends the cursor under the published parameter name',
    sendsTheCursorUnderThePublishedParameterName,
  );
  it('sends the range to the totals operation', sendsTheRangeToTheTotalsOperation);
  it('sends the statement selector in the body', sendsTheStatementSelectorInTheBody);
  it('sends an account selector to the same target', sendsAnAccountSelectorToTheSameTarget);
  it(
    'refuses an unmasked card number in the statement response',
    refusesAnUnmaskedCardNumberInTheStatementResponse,
  );
  it(
    'sends the selector for the transactions operation',
    sendsTheSelectorForTheTransactionsOperation,
  );
  it(
    'refuses an unmasked card number in any transaction row',
    refusesAnUnmaskedCardNumberInAnyTransactionRow,
  );
  it(
    'reads a run status from the published execution path',
    readsARunStatusFromThePublishedExecutionPath,
  );
  it(
    'composes the status read from the submitted handle',
    composesTheStatusReadFromTheSubmittedHandle,
  );
  it(
    'collects the report document from its coordinates',
    collectsTheReportDocumentFromItsCoordinates,
  );
  it('collects a statement document from its answer', collectsAStatementDocumentFromItsAnswer);
  it('refuses a location no answer could have carried', refusesALocationNoAnswerCouldHaveCarried);
  it(
    'sends one identity on every attempt at one submission',
    sendsOneIdentityOnEveryAttemptAtOneSubmission,
  );
  it('mints a distinct identity for each submission', mintsADistinctIdentityForEachSubmission);
  it('omits the submission key without an identity', omitsTheSubmissionKeyWithoutAnIdentity);
  it(
    'refuses an identity the correlation contract would not carry',
    refusesAnIdentityTheCorrelationContractWouldNotCarry,
  );
  it('composes every path under the reports prefix', composesEveryPathUnderTheReportsPrefix);
  it(
    'negotiates a binary body on exactly the two document operations',
    everyDocumentOperationNegotiatesABinaryBody,
  );
}

describe('reporting client contract', reportingClientContract);
