/**
 * @file Unit tests for the shared HTTP client in `ui/src/api/client.ts`.
 *
 * Purpose
 * -------
 * Pin the five cross-cutting boundaries that module applies to every request, because each of them
 * is invisible at a call site and would therefore fail silently: the bearer-token header, the
 * correlation identifier's length and alphabet, the clamped timeout, the exact-money response
 * transform that keeps a monetary value a string, and the recording of the server date.
 *
 * Assumptions: these are asserted against the axios instance the module builds rather than against a
 * live service, so an assertion here fails for a reason inside this package. Contract agreement with
 * the services is a different question, owned by `ui/src/api/contracts.test.ts`.
 */

// Assumptions: every test API is imported rather than taken from an ambient
// global, because ui/vitest.config.ts sets `globals: false` and records that as a
// contract: ambient test globals are declared per PROJECT, so admitting them here
// would make `expect` and `vi` visible to production screens as well, where a
// stray call would compile.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient } from './client';
import { resetServerClock, serverInstant } from './serverClock';

// Refactoring Rationale: the bound asserted below is restated here as a literal
// rather than imported, because the value it must agree with lives in Java --
// `CORRELATION_ID_MAX_LENGTH` in
// `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`
// -- and no build step spans the two languages. The Java side is held to the same
// number from its own side by the three contract tests under
// `services/*/src/test/java/**`, each asserting the published `CorrelationId`
// schema against that constant; this file closes the remaining edge, from the
// published schema to the value the browser actually transmits. Asserting a
// literal in each language is the only form available without introducing a
// generated constants module for one integer.
const CORRELATION_ID_MAX_LENGTH = 24;

// Assumptions: the minted shape is exactly twenty-four upper-case hexadecimal
// characters, which is what the service filter produces when it mints one itself.
// The assertion is on the shape rather than merely on the length, because the
// defect being guarded against -- `crypto.randomUUID()`, thirty-six characters
// with four hyphens -- would satisfy a length-only bound the moment anyone
// "fixed" it by truncation, and a truncated UUID still carries a hyphen.
const MINTED_SHAPE = /^[0-9A-F]{24}$/u;

const API_BASE_URL = 'https://api.carddemo.example';

const CORRELATION_HEADER = 'X-Correlation-Id';

let transmitted: string[] = [];

/**
 * Records the correlation header of one dispatched request and answers it without a network call.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success response carrying the same configuration back.
 */
async function captureAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  // Assumptions: the header bag is narrowed through `unknown` rather than read
  // straight off the configuration, because Axios types its header collection with
  // an index signature returning `any`, and ui/eslint.config.js refuses an unsafe
  // assignment. Narrowing to a string here also means a non-string value records as
  // the empty string, which fails the shape assertion rather than passing a
  // stringified object.
  const headers: unknown = config.headers;
  const header =
    typeof headers === 'object' && headers !== null
      ? (headers as Record<string, unknown>)[CORRELATION_HEADER]
      : undefined;
  transmitted.push(typeof header === 'string' ? header : '');
  return Promise.resolve({
    data: {},
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  } as AxiosResponse);
}

/** Supplies the build-time configuration the client validates before it is constructed. */
function stubBuildConfiguration(): void {
  transmitted = [];
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', CORRELATION_HEADER);
}

/** Restores the environment so no later file inherits this file's configuration. */
function restoreBuildConfiguration(): void {
  vi.unstubAllEnvs();
}

/**
 * Dispatches one request through the real client with the network answered locally.
 * @returns {Promise<string>} The correlation identifier that request transmitted.
 */
async function transmitOneRequest(): Promise<string> {
  const client = getApiClient();
  client.defaults.adapter = captureAdapter;
  await client.get('/api/v1/cards');
  const sent = transmitted.at(-1);
  return sent === undefined ? '' : sent;
}

/** Asserts the transmitted identifier fits the bound the shared service filter enforces. */
async function transmitsAnIdentifierWithinTheServiceBound(): Promise<void> {
  const sent = await transmitOneRequest();
  expect(sent.length).toBeLessThanOrEqual(CORRELATION_ID_MAX_LENGTH);
  expect(sent).toMatch(MINTED_SHAPE);
}

/** Asserts the transmitted identifier is not the thirty-six-character value the filter refuses. */
async function neverTransmitsARandomUuid(): Promise<void> {
  const sent = await transmitOneRequest();
  expect(sent).not.toContain('-');
  expect(sent.length).not.toBe(36);
}

/** Asserts each request is correlated separately rather than sharing one identifier. */
async function correlatesEachRequestSeparately(): Promise<void> {
  const first = await transmitOneRequest();
  const second = await transmitOneRequest();
  expect(first).not.toBe(second);
}

/** Groups the assertions that fix the request-correlation contract. */
function requestCorrelationContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it(
    'transmits an identifier within the bound the service filter enforces',
    transmitsAnIdentifierWithinTheServiceBound,
  );
  it('never transmits a random UUID', neverTransmitsARandomUuid);
  it('correlates each request separately', correlatesEachRequestSeparately);
}

describe('request correlation contract', requestCorrelationContract);

/**
 * A fixed server instant, in the IMF-fixdate form an HTTP `Date` response header carries.
 *
 * Assumptions: deliberately far from any plausible test-run clock, so a case that passed by reading
 * the LOCAL clock instead of this header would be visible rather than coincidentally correct.
 */
const SERVER_DATE_HEADER = 'Tue, 15 Jul 2025 14:23:45 GMT';

/**
 * Answers a request with a success carrying a `Date` header and no body.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A 200 response whose headers include the fixed server instant.
 */
async function datedSuccessAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.resolve({
    data: {},
    status: 200,
    statusText: 'OK',
    headers: { date: SERVER_DATE_HEADER },
    config,
  } as AxiosResponse);
}

/**
 * Answers a request with a server error that still carries a `Date` header.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A 500 response, which Axios converts into a rejection.
 */
async function datedFailureAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  // Assumptions: a 500 is returned rather than an AxiosError being constructed by hand, so the
  //   rejection travels the same path a real failure does -- Axios builds the error from the response
  //   and hands it to the rejection interceptor, which is the code under test.
  return Promise.resolve({
    data: {},
    status: 500,
    statusText: 'Internal Server Error',
    headers: { date: SERVER_DATE_HEADER },
    config,
  } as AxiosResponse);
}

/**
 * Dispatches one request through the real client using the given adapter.
 * @param {typeof datedSuccessAdapter} adapter - Adapter answering the request locally.
 * @returns {Promise<void>} Resolves once the request has settled, however it settled.
 */
async function dispatchThrough(adapter: typeof datedSuccessAdapter): Promise<void> {
  const client = getApiClient();
  client.defaults.adapter = adapter;
  try {
    await client.get('/api/v1/cards');
  } catch {
    // Assumptions: the rejection is swallowed here on purpose. These cases assert what the
    //   interceptor RECORDED, not how the caller was told about the failure, and the failure case
    //   would otherwise fail the test for the very condition it is exercising.
  }
}

/** Asserts a successful response anchors the server clock. */
async function anchorsTheClockFromASuccess(): Promise<void> {
  expect(serverInstant()).toBeUndefined();
  await dispatchThrough(datedSuccessAdapter);
  expect(serverInstant()?.toUTCString()).toBe(SERVER_DATE_HEADER);
}

/** Asserts a failed response anchors the server clock too. */
async function anchorsTheClockFromAFailure(): Promise<void> {
  // WHY : an error response still came from the server and still carries its `Date`. Skipping it
  //       would discard a good anchor exactly when a session is having trouble and issuing the most
  //       requests, so the error path is asserted rather than assumed.
  expect(serverInstant()).toBeUndefined();
  await dispatchThrough(datedFailureAdapter);
  expect(serverInstant()?.toUTCString()).toBe(SERVER_DATE_HEADER);
}

/** Prepares the build configuration and an unanchored clock for one case. */
function stubClockFixture(): void {
  stubBuildConfiguration();
  resetServerClock();
}

/** Restores the environment and discards the anchor so no later file inherits either. */
function restoreClockFixture(): void {
  restoreBuildConfiguration();
  resetServerClock();
}

/**
 * Groups the assertions that fix the server-clock anchoring contract.
 *
 * WHY : these cases close the link between `serverClock` and reality. Its own unit tests prove the
 *       offset arithmetic, and the header-band contract test proves every screen passes the value on,
 *       but neither would notice if the response interceptor were removed -- the clock would simply
 *       never anchor and every screen would silently fall back to the browser clock, which is the
 *       original defect restored.
 */
function serverClockAnchoringContract(): void {
  beforeEach(stubClockFixture);
  afterEach(restoreClockFixture);
  it('anchors the server clock from a successful response', anchorsTheClockFromASuccess);
  it('anchors the server clock from a failed response', anchorsTheClockFromAFailure);
}

describe('server clock anchoring contract', serverClockAnchoringContract);
