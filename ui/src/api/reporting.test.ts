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
// ui/vitest.config.ts sets `globals: false` and records that as a contract: ambient test globals are
// declared per PROJECT, so admitting them here would make `expect` and `vi` visible to production
// screens as well, where a stray call would compile.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient } from './client';
import {
  collectArtifact,
  collectReportArtifact,
  generateStatement,
  listStatementTransactions,
  listTransactionReportLines,
  readReportExecution,
  readTransactionReportTotals,
  submitTransactionReport,
} from './reporting';

const API_BASE_URL = 'https://api.carddemo.example';

const CORRELATION_HEADER = 'X-Correlation-Id';

const CARD_NUMBER = '4859452612877065';

const MASKED_CARD_NUMBER = '************7065';

const HTTP_OK = 200;

const HTTP_CREATED = 201;

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
 * @returns {Record<string, unknown>} The 201 body: the outcome, the composed sentence, and the nested
 *   handle.
 */
function startedSubmissionBody(): Record<string, unknown> {
  return {
    outcome: 'STARTED',
    message: 'Monthly report submitted for printing ...',
    submission: {
      executionArn: 'arn:aws:states:us-east-1:000000000000:execution:carddemo-report:1',
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
  expect(outcome.submission.executionArn).toBe(
    'arn:aws:states:us-east-1:000000000000:execution:carddemo-report:1',
  );
  expect(outcome.message).toBe('Monthly report submitted for printing ...');
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
 * Asserts every path this module composes begins with the gateway-published prefix.
 *
 * Assumptions: this is asserted across all eight operations together rather than left implicit in the
 * per-operation assertions, because the property is about the whole module: the gateway publishes
 * only `ANY /api/v1/reports` and `ANY /api/v1/reports/{proxy+}` for this service, so a path this
 * module composed outside that prefix would be answered by the gateway's own 404 with no integration
 * attempted — a failure that looks like an outage rather than a client defect.
 */
async function composesEveryPathUnderTheReportsPrefix(): Promise<void> {
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

  expect(dispatched).toHaveLength(8);
  for (const request of dispatched) {
    expect(request.url.startsWith('/reports')).toBe(true);
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
    'collects the report document from its coordinates',
    collectsTheReportDocumentFromItsCoordinates,
  );
  it('collects a statement document from its answer', collectsAStatementDocumentFromItsAnswer);
  it('refuses a location no answer could have carried', refusesALocationNoAnswerCouldHaveCarried);
  it('composes every path under the reports prefix', composesEveryPathUnderTheReportsPrefix);
}

describe('reporting client contract', reportingClientContract);
