/**
 * @file The shared transport harness the API client tests are written against.
 *
 * Purpose
 * -------
 * Give every client-module test one way to answer a request without a network: install a recording
 * adapter on the shared Axios instance, queue the body and status the next call is to receive, and expose
 * what was dispatched so a case can assert on the method, the target, the query parameters and the body.
 *
 * Why this exists as a module rather than per test file
 * ----------------------------------------------------
 * Refactoring Rationale: six of the seven client modules had no behavioural test at all, so their
 * request construction, their local guards, their status readings and their response validation were
 * unverified -- the operation manifests were checked against the contracts and nothing else was. Writing
 * six harnesses to close that would be six chances for one of them to record a request differently, and a
 * case that fails only because its harness differs from the others is a case that gets deleted rather
 * than read. The one harness is therefore here, and each test file supplies only its own fixtures.
 *
 * Trade-offs: `ui/src/api/reporting.test.ts` predates this module and keeps its own copy. It is not
 * migrated, because that file passes and rewriting a passing suite to share a helper is churn a reader
 * cannot evaluate; the two harnesses agree on the properties that matter -- the adapter records the URL
 * Axios was ASKED for rather than the resolved one, and parses the body back out of the serialised form.
 *
 * Alternatives Considered: mocking the `axios` module wholesale with `vi.mock`, which is shorter to
 * write. Rejected because it removes the client's own interceptors from the path, and those interceptors
 * are where the bearer token, the correlation identifier and the failure normalisation live -- a test
 * dispatching through a mocked module would assert against a request the application never makes.
 *
 * Assumptions: every test API is imported explicitly rather than taken from an ambient global, because
 * `ui/vitest.config.ts` sets `globals: false` and records that as a contract.
 */

import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { expect, vi } from 'vitest';

import { getApiClient, resetApiClient } from '../api/client';

/** The base URL the harness configures the client with, so a composed target is assertable. */
export const HARNESS_API_BASE_URL = 'https://api.carddemo.example/api/v1';

/** The correlation header name the harness configures, matching the deployed edge's own. */
export const HARNESS_CORRELATION_HEADER = 'X-Correlation-Id';

/** HTTP statuses the client modules read an outcome from, named so a case does not spell a number. */
export const HTTP_OK = 200;

/** The created status a submission or an insert answers with. */
export const HTTP_CREATED = 201;

/** The no-content status a delete answers with. */
export const HTTP_NO_CONTENT = 204;

/** One dispatched request, reduced to the parts a client-module assertion is about. */
export interface DispatchedRequest {
  /** The lower-cased HTTP method Axios was asked for. */
  readonly method: string;
  /** The target relative to the configured base URL, as the client composed it. */
  readonly url: string;
  /** The query parameters, or an empty record when the client sent none. */
  readonly params: Record<string, string>;
  /** The request body, parsed back out of the serialised form Axios produced. */
  readonly body: unknown;
  /** The request headers as the interceptors left them, lower-cased by name. */
  readonly headers: Record<string, string>;
}

/** One queued answer: the body and the status the next dispatch is to receive. */
interface QueuedResponse {
  readonly body: unknown;
  readonly status: number;
  readonly headers: Record<string, string>;
}

let dispatched: DispatchedRequest[] = [];

let queued: QueuedResponse[] = [];

let fallback: QueuedResponse = { body: {}, status: HTTP_OK, headers: {} };

/**
 * Parses a serialised request body back into the structure the caller passed.
 *
 * Assumptions: the body arrives at an adapter ALREADY SERIALISED, because Axios runs its request
 * transforms first. Comparing the string would compare key order and whitespace, which neither this
 * application nor any contract has an opinion about.
 * @param {unknown} data - The value Axios placed on the configuration.
 * @returns {unknown} The parsed structure, or the value unchanged when it is not JSON text.
 */
function parseBody(data: unknown): unknown {
  if (typeof data !== 'string') {
    return data;
  }
  try {
    return JSON.parse(data) as unknown;
  } catch {
    return data;
  }
}

/**
 * Reduces Axios request headers to a plain record keyed by lower-cased name.
 * @param {AxiosRequestConfig} config - The request configuration after the interceptors ran.
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
 * Records one dispatched request and answers it from the queue without a network call.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A response carrying the queued body, status and headers.
 */
async function recordingAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const params: unknown = config.params;
  dispatched.push({
    method: config.method ?? '',
    url: config.url ?? '',
    params:
      typeof params === 'object' && params !== null
        ? { ...(params as Record<string, string>) }
        : {},
    body: parseBody(config.data),
    headers: headersOf(config),
  });
  const answer = queued.shift() ?? fallback;
  return Promise.resolve({
    data: answer.body,
    status: answer.status,
    statusText: '',
    headers: answer.headers,
    config,
  } as AxiosResponse);
}

/**
 * Installs the harness: stubs the build configuration, rebuilds the client and clears the recordings.
 *
 * Assumptions: the client is RESET rather than reused, because it is a module-level singleton built from
 * the environment on first use -- a file that ran after another had stubbed a different base URL would
 * otherwise inherit the first file's instance and dispatch against its configuration.
 * @returns {void} Nothing; the harness is installed as a side effect.
 */
export function installApiHarness(): void {
  dispatched = [];
  queued = [];
  fallback = { body: {}, status: HTTP_OK, headers: {} };
  vi.stubEnv('VITE_API_BASE_URL', HARNESS_API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', HARNESS_CORRELATION_HEADER);
  resetApiClient();
  getApiClient().defaults.adapter = recordingAdapter;
}

/**
 * Removes the harness so no later file inherits this file's configuration or its adapter.
 * @returns {void} Nothing; the environment is restored as a side effect.
 */
export function removeApiHarness(): void {
  resetApiClient();
  vi.unstubAllEnvs();
  dispatched = [];
  queued = [];
}

/**
 * Queues the body, status and headers the next dispatch is to receive.
 *
 * Assumptions: answers are a QUEUE rather than a single value, because several client functions issue
 * more than one request and a harness that could answer only once could not tell a two-request flow from
 * a one-request flow at all.
 * @param {unknown} body - The response body.
 * @param {number} [status] - The response status, defaulting to 200.
 * @param {Record<string, string>} [headers] - Response headers, defaulting to none.
 * @returns {void} Nothing; the answer is appended to the queue.
 */
export function answerWith(
  body: unknown,
  status: number = HTTP_OK,
  headers: Record<string, string> = {},
): void {
  queued.push({ body, status, headers });
}

/**
 * Sets the answer every dispatch beyond the queue receives.
 * @param {unknown} body - The response body.
 * @param {number} [status] - The response status, defaulting to 200.
 * @returns {void} Nothing; the fallback is replaced.
 */
export function answerEveryRequestWith(body: unknown, status: number = HTTP_OK): void {
  fallback = { body, status, headers: {} };
}

/**
 * Returns every request dispatched since the harness was installed, in order.
 * @returns {readonly DispatchedRequest[]} The recordings.
 */
export function dispatchedRequests(): readonly DispatchedRequest[] {
  return dispatched;
}

/**
 * Returns the single request a case dispatched, failing when it dispatched a different number.
 * @returns {DispatchedRequest} The one recorded request.
 * @throws {Error} If no request was dispatched, which the length assertion reports first; the throw
 *   exists so the narrowed type is sound rather than asserted non-null, which the lint gate refuses.
 */
export function onlyRequest(): DispatchedRequest {
  expect(dispatched).toHaveLength(1);
  const request = dispatched[0];
  if (request === undefined) {
    throw new Error('no request was dispatched');
  }
  return request;
}

/**
 * Returns the nth dispatched request, one-based.
 * @param {number} ordinal - Which request to read, counting from one.
 * @returns {DispatchedRequest} That request.
 * @throws {Error} If fewer requests were dispatched than the ordinal names.
 */
export function requestNumber(ordinal: number): DispatchedRequest {
  const request = dispatched[ordinal - 1];
  if (request === undefined) {
    throw new Error(`request ${ordinal} was never dispatched; ${dispatched.length} were`);
  }
  return request;
}

/**
 * Builds one bounded page envelope in the four-member shape every contract publishes.
 * @template T The row type of the page.
 * @param {readonly T[]} items - The rows the page carries.
 * @param {boolean} [hasNext] - Whether a following page exists, defaulting to false.
 * @returns {Record<string, unknown>} The envelope, with both cursors null when the page is empty.
 */
export function pageOf<T>(items: readonly T[], hasNext: boolean = false): Record<string, unknown> {
  return {
    items,
    firstKey: items.length === 0 ? null : 'leading-cursor',
    lastKey: items.length === 0 ? null : 'trailing-cursor',
    hasNext,
  };
}
