// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract: ambient test globals are
// declared per PROJECT, so admitting them here would make `expect` and `vi` visible to production
// screens as well, where a stray call would compile.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient } from './client';
import {
  generateStatement,
  listStatementTransactions,
  listTransactionReportLines,
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
 * Returns a statement body whose card number is masked, as the contract declares it.
 * @returns {Record<string, unknown>} One statement summary with both rendering URIs.
 */
function maskedStatement(): Record<string, unknown> {
  return {
    cardNumber: MASKED_CARD_NUMBER,
    accountId: '00000000011',
    customerName: 'PARITY CUSTOMER',
    totalAmount: '1234.56',
    transactionCount: 2,
    plainTextUri: 's3://carddemo-datasets-dev/statements/dt=2022-07-18/gen=0001/stmt.txt',
    htmlUri: 's3://carddemo-datasets-dev/statements/dt=2022-07-18/gen=0001/stmt.html',
    generatedAt: '2022-07-18 22:10:31.000000',
  };
}

/** Asserts report submission posts the request body to the published submission path. */
async function submitsToThePublishedReportPath(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = {
    executionArn: 'arn:aws:states:us-east-1:000000000000:execution:carddemo-report:1',
    reportName: 'Monthly',
    shortName: 'MONTHLY',
    longName: 'Monthly Transaction Report',
    startDate: '2022-07-01',
    endDate: '2022-07-31',
    submittedAt: '2022-07-18 22:10:31.000000',
  };
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/reports/transaction-report');
  expect(request.body).toEqual({ monthly: 'X', confirm: 'Y' });
}

/**
 * Asserts a created status is read as a started execution.
 *
 * Assumptions: the STATUS is what the outcome is asserted from, not a member of the body, because the
 * contract makes 201 normative for a started run. The baseline draws no such distinction at all -- the
 * declined branch of SUBMIT-JOB-TO-INTRDR sets the same flag a validation failure does -- so reading
 * the two outcomes apart is a documented improvement that only a status-driven assertion protects.
 */
async function readsAStartedExecutionFromTheCreatedStatus(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = {
    executionArn: 'arn:aws:states:us-east-1:000000000000:execution:carddemo-report:1',
    reportName: 'Monthly',
    shortName: 'MONTHLY',
    longName: 'Monthly Transaction Report',
    startDate: '2022-07-01',
    endDate: '2022-07-31',
    submittedAt: '2022-07-18 22:10:31.000000',
  };
  const outcome = await submitTransactionReport({ monthly: 'X', confirm: 'Y' });
  expect(outcome.outcome).toBe('STARTED');
  if (outcome.outcome !== 'STARTED') {
    throw new Error('the created status must be read as a started execution');
  }
  expect(outcome.submission.executionArn).toBe(
    'arn:aws:states:us-east-1:000000000000:execution:carddemo-report:1',
  );
}

/** Asserts an ok status is read as a declined confirmation carrying the report that would have run. */
async function readsADeclinedConfirmationFromTheOkStatus(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = {
    submitted: false,
    reportName: 'Monthly',
    startDate: '2022-07-01',
    endDate: '2022-07-31',
  };
  const outcome = await submitTransactionReport({ monthly: 'X', confirm: 'N' });
  expect(outcome.outcome).toBe('DECLINED');
  if (outcome.outcome !== 'DECLINED') {
    throw new Error('the ok status must be read as a declined confirmation');
  }
  expect(outcome.preview.submitted).toBe(false);
  expect(outcome.preview.reportName).toBe('Monthly');
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

/** Asserts the transactions read posts the selector to its own published target. */
async function sendsTheSelectorForTheTransactionsOperation(): Promise<void> {
  nextBody = { items: [] };
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
  };
  await expect(listStatementTransactions({ cardNumber: CARD_NUMBER })).rejects.toBeInstanceOf(
    RangeError,
  );
}

/**
 * Asserts every path this module composes begins with the gateway-published prefix.
 *
 * Assumptions: this is asserted across all five operations together rather than left implicit in the
 * per-operation assertions, because the property is about the whole module: the gateway publishes
 * only `ANY /api/v1/reports` and `ANY /api/v1/reports/{proxy+}` for this service, so a path this
 * module composed outside that prefix would be answered by the gateway's own 404 with no integration
 * attempted — a failure that looks like an outage rather than a client defect.
 */
async function composesEveryPathUnderTheReportsPrefix(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = {
    executionArn: 'arn:x',
    reportName: 'Monthly',
    shortName: 'MONTHLY',
    longName: 'Monthly Transaction Report',
    startDate: '2022-07-01',
    endDate: '2022-07-31',
    submittedAt: '2022-07-18 22:10:31.000000',
  };
  await submitTransactionReport({ monthly: 'X', confirm: 'Y' });

  nextStatus = HTTP_OK;
  nextBody = { items: [], firstKey: null, lastKey: null, hasNext: false };
  await listTransactionReportLines({ startDate: '2022-07-01', endDate: '2022-07-31' });

  nextBody = { bands: [] };
  await readTransactionReportTotals('2022-07-01', '2022-07-31');

  nextBody = maskedStatement();
  await generateStatement({ cardNumber: CARD_NUMBER });

  nextBody = { items: [] };
  await listStatementTransactions({ cardNumber: CARD_NUMBER });

  expect(dispatched).toHaveLength(5);
  for (const request of dispatched) {
    expect(request.url.startsWith('/reports')).toBe(true);
  }
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
    'sends the required range and omits the direction without a cursor',
    sendsTheRangeAndOmitsTheDirectionWithoutACursor,
  );
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
  it('composes every path under the reports prefix', composesEveryPathUnderTheReportsPrefix);
}

describe('reporting client contract', reportingClientContract);
