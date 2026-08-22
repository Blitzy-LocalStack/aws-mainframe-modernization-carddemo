/**
 * @file Unit tests for the shared HTTP client in `ui/src/api/client.ts`.
 *
 * Purpose
 * -------
 * Pin the cross-cutting boundaries that module applies to every request, because each of them is
 * invisible at a call site and would therefore fail silently: the bearer-token header, the correlation
 * identifier's length and alphabet, the clamped timeout, the exact-money response transform that keeps
 * a monetary value a string, the recording of the server date, and the classification of every
 * transport failure into the four kinds a screen branches on.
 *
 * Assumptions: these are asserted against the axios instance the module builds rather than against a
 * live service, so an assertion here fails for a reason inside this package. Contract agreement with
 * the services is a different question, owned by `ui/src/api/contracts.test.ts`.
 *
 * ⚠️ Measured: a custom `adapter` that RESOLVES a non-2xx response does not produce a rejection at all.
 * Axios validates a status inside its transport adapters, not in its core, so `dispatchRequest` passes a
 * resolved 500 straight to the SUCCESS branch of the response interceptor. An adapter written that way
 * -- which is how the failure cases in this file used to be driven, under a comment asserting the
 * opposite -- left the whole failure path unexercised: the classifier, the problem parsing, the target
 * masking and the 401 session discard could each have been deleted with every case here still green.
 * The failure cases below therefore drive Axios's own `fetch` adapter with the global `fetch` replaced,
 * so the rejection is settled by Axios's code rather than constructed by this file's.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  API_PATH_PREFIX,
  CORRELATION_ID_LENGTH,
  WITHOUT_STORED_SESSION,
  correlationHeaders,
  getApiClient,
  isApiRequestError,
  newCorrelationId,
  requestPath,
  resetApiClient,
  setAccessToken,
  subscribeToAuthenticationRequired,
} from './client';
import type { ApiRequestError } from './client';
import { resetServerClock, serverInstant } from './serverClock';
// Assumptions: the seven operation manifests are imported so the disclosure gate below can iterate the
// WHOLE published surface rather than a table of specimens. They are plain data and importing them opens
// no cycle: each client module imports `client.ts`, and this file imports both.
import { ACCOUNT_CONTRACT_OPERATIONS } from './accounts';
import { AUTH_CONTRACT_OPERATIONS } from './auth';
import { AUTHORIZATION_CONTRACT_OPERATIONS } from './authorization';
import { CARD_CONTRACT_OPERATIONS } from './cards';
import { REFERENCE_CONTRACT_OPERATIONS } from './reference';
import { REPORTING_CONTRACT_OPERATIONS } from './reporting';
import { TRANSACTION_CONTRACT_OPERATIONS } from './transactions';
// Assumptions: `ContractOperation` is imported for the disclosure tables below, each of which composes
// its target through `requestPath` from a real operation rather than from a hand-written string. That is
// what makes the masking under test see the same template a screen's call gives it.
import type { ContractOperation } from './types';

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

// Assumptions: the minted shape is the two-character `CD` prefix followed by
// twenty-two upper-case hexadecimal characters, which is exactly what
// `CorrelationIdFilter` produces when it mints one itself -- `GENERATED_ID_PREFIX`
// plus `GENERATED_ID_RANDOM_BYTES` rendered as hexadecimal. The assertion is on the
// shape rather than merely on the length, because the defect first guarded against
// -- `crypto.randomUUID()`, thirty-six characters with four hyphens -- would satisfy
// a length-only bound the moment anyone "fixed" it by truncation, and a truncated
// UUID still carries a hyphen.
// Refactoring Rationale: this pattern was `^[0-9A-F]{24}$`, which admitted the BARE
// hexadecimal form this client used to mint. That form is within the bound and within
// the alphabet and is still refused by the service roughly one time in seventy-eight
// thousand, because a value of twenty-four characters that happen to be all digits is
// protected-identifier-shaped. Requiring the prefix is what makes that refusal
// unrepresentable, and the exhaustive case below proves it rather than sampling it.
// Assumptions: that rate is unchanged by the floor below moving from thirteen to nine,
// because a bare hexadecimal value is refused only when EVERY one of its twenty-four
// characters is a digit -- one letter disqualifies it before any digit is counted -- and
// twenty-four clears either floor. The figure is restated rather than recomputed.
const MINTED_SHAPE = /^CD[0-9A-F]{22}$/u;

// Assumptions: nine, taken from `PROTECTED_IDENTIFIER_MIN_DIGITS` in the same filter.
// It refuses an inbound identifier whose digits -- counted with the accepted
// separators removed -- number this many or more, because nine is the shortest
// protected identifier this system holds (a customer identifier and a national
// identifier are both `PIC 9(09)`; an account identifier is eleven digits and a card
// number sixteen) and a conforming identifier is published to the mapped diagnostic
// context and echoed on the response.
// Refactoring Rationale: this mirrored thirteen while the filter did, and moved with it.
// The mirror is asserted rather than imported because the rule lives in Java and no build
// step spans the two languages -- which is precisely why it is restated here with its
// derivation, so a future divergence is visible as a contradiction rather than silent.
const PROTECTED_IDENTIFIER_MIN_DIGITS = 9;

// Assumptions: the three separator characters `ACCEPTED_PUNCTUATION` admits. They are
// stripped before the digit count, so the refusal is about the VALUE rather than about
// punctuation, and the browser-side proof has to strip them the same way.
const ACCEPTED_PUNCTUATION = /[-_.]/gu;

// Assumptions: the fixture carries the `/api/v1` operation prefix because
// `normalizeApiBaseUrl` requires it -- a base URL one segment short is refused at
// start-up rather than producing a 404 on every request. The prefix changes no
// assertion here: every case below asserts the RELATIVE request path.
const API_BASE_URL = 'https://api.carddemo.example/api/v1';

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

/**
 * Reproduces the service filter's protected-identifier refusal for one candidate identifier.
 *
 * Assumptions: this is `isProtectedIdentifierShaped` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`, restated in
 * TypeScript rather than imported, because the rule lives in Java and no build step spans the two
 * languages. Restating it is what lets this file assert the browser's own output against the exact
 * predicate that refuses it, instead of asserting a shape that merely looks safe.
 * @param {string} candidate - An identifier the browser might transmit.
 * @returns {boolean} `true` when the services would answer HTTP 400 rather than accept it.
 */
function wouldBeRefusedAsProtectedIdentifierShaped(candidate: string): boolean {
  const withoutSeparators = candidate.replace(ACCEPTED_PUNCTUATION, '');
  return (
    /^[0-9]*$/u.test(withoutSeparators) &&
    withoutSeparators.length >= PROTECTED_IDENTIFIER_MIN_DIGITS
  );
}

/**
 * Asserts no value this client can mint is one the services refuse, over the whole byte domain.
 *
 * WHY : Assumptions: this is a DETERMINISTIC proof rather than a sample. `newCorrelationId` renders
 *       each entropy byte as two hexadecimal characters and prepends a fixed prefix, so the only
 *       thing a random draw can vary is which of the 256 renderings each byte contributes. Feeding
 *       every one of those 256 renderings into the refusal predicate — with the extreme all-digit
 *       draw included explicitly — covers every string the function can produce, because the
 *       predicate is decided by the presence of a single non-digit and the prefix supplies two.
 *       Sampling `newCorrelationId()` instead would only ever fail once in seventy-eight thousand
 *       runs, which is the definition of a test that passes for the wrong reason.
 * @returns {void} Nothing; the assertions carry the result.
 */
function neverMintsAnIdentifierTheServicesRefuse(): void {
  const allDigitEntropy = '0'.repeat(CORRELATION_ID_LENGTH - 2);
  expect(wouldBeRefusedAsProtectedIdentifierShaped(allDigitEntropy)).toBe(true);
  expect(wouldBeRefusedAsProtectedIdentifierShaped(`CD${allDigitEntropy}`)).toBe(false);

  for (let byte = 0; byte < 256; byte += 1) {
    const rendered = byte.toString(16).toUpperCase().padStart(2, '0');
    const minted = `CD${rendered.repeat((CORRELATION_ID_LENGTH - 2) / 2)}`;
    expect(minted).toHaveLength(CORRELATION_ID_LENGTH);
    expect(minted).toMatch(MINTED_SHAPE);
    expect(wouldBeRefusedAsProtectedIdentifierShaped(minted)).toBe(false);
  }
}

/**
 * Asserts a directly minted identifier is exactly the published width and shape.
 *
 * WHY : Assumptions: the function is exercised directly as well as through a dispatched request,
 *       because the dispatched case can only observe one draw while this one names the width the
 *       services bound. Two hundred draws is not a proof of anything — the exhaustive case above is
 *       the proof — but it does establish that the prefix is not conditional on the entropy.
 * @returns {void} Nothing; the assertions carry the result.
 */
function mintsThePublishedWidthAndShape(): void {
  expect(CORRELATION_ID_LENGTH).toBe(CORRELATION_ID_MAX_LENGTH);
  for (let draw = 0; draw < 200; draw += 1) {
    const minted = newCorrelationId();
    expect(minted).toHaveLength(CORRELATION_ID_LENGTH);
    expect(minted).toMatch(MINTED_SHAPE);
    expect(wouldBeRefusedAsProtectedIdentifierShaped(minted)).toBe(false);
  }
}

/**
 * Dispatches one request whose correlation identifier the caller pins.
 * @param {string} pinned - The identifier to pin, passed through the published header builder rather
 *   than written into a header bag here, so the case exercises the supported way of pinning one.
 * @returns {Promise<string>} The correlation identifier that request actually transmitted.
 */
async function transmitOnePinnedRequest(pinned: string): Promise<string> {
  const client = getApiClient();
  client.defaults.adapter = captureAdapter;
  await client.get('/api/v1/cards', { headers: correlationHeaders(pinned) });
  const sent = transmitted.at(-1);
  return sent === undefined ? '' : sent;
}

/**
 * Asserts an identifier the caller pinned survives dispatch, and that pinning stays opt-in.
 *
 * Purpose: this is the case a retried unit of work cannot be recognised without. The interceptor used to
 * overwrite the header on every dispatch, which made the identifier a name for the CALL; one service
 * reads it as a name for the WORK -- `ReportExecutionService` derives a report submission's
 * deduplication key from it when no explicit submission key arrives -- so a second attempt at one
 * submission could not present the identity the first attempt was sent under.
 *
 * Assumptions: the unpinned dispatch is asserted in the SAME case rather than left to
 * `correlatesEachRequestSeparately`, because the two halves are one property: preserving a pinned value
 * must not weaken the guarantee that a request carrying none still leaves with a freshly minted one.
 * Asserting them apart would let a change that returned early satisfy each case in isolation.
 */
async function preservesAPinnedIdentifierAndStillMintsWithoutOne(): Promise<void> {
  const pinned = newCorrelationId();
  expect(await transmitOnePinnedRequest(pinned)).toBe(pinned);
  expect(await transmitOnePinnedRequest(pinned)).toBe(pinned);
  const unpinned = await transmitOneRequest();
  expect(unpinned).not.toBe(pinned);
  expect(unpinned).toMatch(MINTED_SHAPE);
}

/**
 * Asserts the header builder refuses every value the shared service filter would refuse.
 *
 * Assumptions: the four refused specimens are one per condition the filter applies, and each is the
 * closest wrong value rather than an obviously absurd one -- an empty identifier, one character past the
 * bound, an identifier carrying a character outside the accepted alphabet, and an all-digit run at
 * exactly the protected-identifier floor with the accepted separators present so that the stripping step
 * is exercised too. A value refused by the filter is answered HTTP 400 before any handler runs, which at
 * a screen is indistinguishable from a rejected payload; failing at the call site names the real cause.
 */
function refusesAPinnedIdentifierTheServicesWouldRefuse(): void {
  const atTheDigitFloor = '0'.repeat(PROTECTED_IDENTIFIER_MIN_DIGITS);
  const separatedDigits = '000-000-000';
  expect(wouldBeRefusedAsProtectedIdentifierShaped(atTheDigitFloor)).toBe(true);
  expect(wouldBeRefusedAsProtectedIdentifierShaped(separatedDigits)).toBe(true);
  for (const refused of [
    '',
    'C'.repeat(CORRELATION_ID_MAX_LENGTH + 1),
    'CD 0123456789ABCDEF0123',
    atTheDigitFloor,
    separatedDigits,
  ]) {
    expect(pinning(refused)).toThrow(RangeError);
  }
  expect(correlationHeaders('CD0123456789ABCDEF012345')).toEqual({
    [CORRELATION_HEADER]: 'CD0123456789ABCDEF012345',
  });
}

/**
 * Builds a thunk that pins one identifier, so its refusal can be asserted without a dispatch.
 *
 * Assumptions: a named factory rather than an inline arrow at the assertion, because
 * `ui/eslint.config.js` selects a function expression in every position with `publicOnly: false`, so an
 * inline thunk would owe its own JSDoc block at each of the five specimens.
 * @param {string} candidate - Identifier to pin.
 * @returns {() => Readonly<Record<string, string>>} A thunk invoking the published header builder.
 */
function pinning(candidate: string): () => Readonly<Record<string, string>> {
  /**
   * Invokes the header builder for the captured candidate.
   * @returns {Readonly<Record<string, string>>} The header bag, when the candidate is accepted.
   */
  return function pinOne(): Readonly<Record<string, string>> {
    return correlationHeaders(candidate);
  };
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
  it('mints the published width and shape on every draw', mintsThePublishedWidthAndShape);
  it(
    'never mints an identifier the services refuse as protected-identifier-shaped',
    neverMintsAnIdentifierTheServicesRefuse,
  );
  it(
    'preserves a pinned identifier and still mints one without it',
    preservesAPinnedIdentifierAndStillMintsWithoutOne,
  );
  it(
    'refuses a pinned identifier the services would refuse',
    refusesAPinnedIdentifierTheServicesWouldRefuse,
  );
}

describe('request correlation contract', requestCorrelationContract);

/**
 * A fabricated sealed-selector specimen, at the width the authorization contract issues.
 *
 * Assumptions: ⚠️ this is a named constant rather than a literal written inline in the table below, and
 * the indirection is not stylistic. The contract names that path parameter `key`, so an inline value
 * produces the text `key: '<twenty-two mixed-case characters>'`, which the `generic-api-key` rule of the
 * committed-secret gate in `.github/workflows/infra-ci.yml` reports — and that step runs under
 * `set -euo pipefail`, so one fabricated test value would fail the whole gate. Naming it keeps the value
 * off an assignment the rule inspects, which is the convention `./authorization.test.ts` already follows
 * for its own selector. Alternatives Considered: adding the literal to the workflow's allowlist. Rejected
 * because that file records why exceptions are pinned to reviewed production-adjacent values only, and
 * spending one on a specimen that can simply be named would make the gate's red mean less.
 */
const SEALED_SELECTOR_SPECIMEN = 'v1AbCdEfGhIjKlMnOpQrSt';

/**
 * Every published operation that carries a protected value in its target, with what must survive it.
 *
 * Refactoring Rationale: ⚠️ each row is now an OPERATION and a parameter value, and each was a
 * hand-written target string. The masking under test derives its answer from the template a request
 * addressed, which it learns when `requestPath` composes the target — so a row that dispatched a raw
 * string exercised the unmatched-target path rather than the masking every real request takes, and two
 * of the five former rows named routes no contract publishes at all. Composing each target from its
 * operation is what makes these cases assert the behaviour a screen actually meets.
 *
 * Assumptions: the values are the widths this system really issues — a sixteen-digit card number, an
 * eleven-digit account identifier, a two-character reference code and a twenty-two-character sealed
 * selector — so the table exercises four distinct value shapes rather than four variations of one. The
 * `expected` column is written out rather than computed, because a computed expectation would restate
 * the implementation and pass with it if it were wrong.
 *
 * Assumptions: every value renders as a generic placeholder rather than as a same-length run of
 * asterisks, because this client reports the template and not a narrowed value — see the Alternatives
 * Considered on `normaliseFailure`. Two consequences are asserted by the rows rather than described: a
 * query string is dropped outright instead of being narrowed in place, and a SHORT value is withheld
 * too, since the withholding follows from its position and not from its length.
 */
const SELECTOR_BEARING_TARGETS: readonly {
  operation: ContractOperation;
  parameters?: Record<string, string>;
  query?: string;
  expected: string;
}[] = [
  {
    operation: { method: 'GET', path: '/api/v1/cards/{cardKey}', operationId: 'getCard' },
    parameters: { cardKey: '4859452612877065' },
    expected: '/cards/{id}',
  },
  {
    operation: {
      method: 'GET',
      path: '/api/v1/accounts/{accountId}/card-xrefs',
      operationId: 'listAccountCardCrossReferences',
    },
    parameters: { accountId: '00000000011' },
    query: '?direction=next',
    expected: '/accounts/{id}/card-xrefs',
  },
  {
    operation: {
      method: 'GET',
      path: '/api/v1/reference/transaction-types/{typeCd}',
      operationId: 'getTransactionType',
    },
    parameters: { typeCd: '01' },
    expected: '/reference/transaction-types/{id}',
  },
  {
    operation: {
      method: 'GET',
      path: '/api/v1/authorizations/{key}',
      operationId: 'getPendingAuthorization',
    },
    parameters: { key: SEALED_SELECTOR_SPECIMEN },
    expected: '/authorizations/{id}',
  },
];

/**
 * Every value that EQUALS a published route word, which is the disclosure a value-based mask admits.
 *
 * Purpose: ⚠️ this table is the regression bound for the finding that replaced value-membership masking
 * with template masking. A path parameter's domain overlaps the vocabulary of route names — a user
 * identifier is one to eight printable characters folded to upper case, so `admin` and `users` are both
 * legal identifiers, and a reference code or an artifact selector can spell `search` or `view` — and a
 * mask that kept a segment because its value appeared in a list of route words disclosed exactly those
 * values. Each row therefore dispatches a real operation with a value that collides with a literal used
 * elsewhere in the published surface, and asserts the value does not survive.
 *
 * Assumptions: the collisions are chosen from the words the contracts actually publish rather than
 * invented, and the last two are the two positions where a published LITERAL route and a published
 * PARAMETER route genuinely share a shape: `/cards/lookup` beside `/cards/{cardKey}`, and
 * `/transactions/copy-last` beside `/transactions/{transactionId}`. Those two are the cases a mask that
 * merely preferred the more specific template would still leak.
 */
const COLLIDING_VALUES: readonly {
  operation: ContractOperation;
  parameters: Record<string, string>;
  value: string;
  expected: string;
}[] = [
  {
    operation: { method: 'GET', path: '/api/v1/auth/users/{userId}', operationId: 'getUser' },
    parameters: { userId: 'admin' },
    value: 'admin',
    expected: '/auth/users/{id}',
  },
  {
    operation: { method: 'GET', path: '/api/v1/auth/users/{userId}', operationId: 'getUser' },
    parameters: { userId: 'users' },
    value: 'users',
    expected: '/auth/users/{id}',
  },
  {
    operation: {
      method: 'GET',
      path: '/api/v1/reference/transaction-types/{typeCd}',
      operationId: 'getTransactionType',
    },
    parameters: { typeCd: 'view' },
    value: 'view',
    expected: '/reference/transaction-types/{id}',
  },
  {
    operation: { method: 'GET', path: '/api/v1/cards/{cardKey}', operationId: 'getCard' },
    parameters: { cardKey: 'lookup' },
    value: 'lookup',
    expected: '/cards/{id}',
  },
  {
    operation: {
      method: 'GET',
      path: '/api/v1/transactions/{transactionId}',
      operationId: 'viewTransaction',
    },
    parameters: { transactionId: 'copy-last' },
    value: 'copy-last',
    expected: '/transactions/{id}',
  },
];

/**
 * Answers a request by rejecting with a proxy response whose body is not a problem document.
 *
 * Assumptions: the adapter REJECTS with a constructed `AxiosError` rather than resolving a 502
 * response, and the difference is load-bearing rather than stylistic. Axios applies
 * `validateStatus` inside its own transport adapters, not to the value a replacement adapter
 * resolves, so a resolved 502 reaches the FULFILLED interceptor and never reaches the rejection
 * interceptor these cases exist to exercise. Building the error here is what puts the request on the
 * failure path a real gateway answer takes.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} Never resolves; always rejects with a 502-bearing failure.
 */
async function proxyHtmlAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const response = {
    data: '<html><body>502 Bad Gateway</body></html>',
    status: 502,
    statusText: 'Bad Gateway',
    headers: {},
    config,
  } as AxiosResponse;
  return Promise.reject(
    new AxiosError(
      'Request failed with status code 502',
      AxiosError.ERR_BAD_RESPONSE,
      config as InternalAxiosRequestConfig,
      undefined,
      response,
    ),
  );
}

/**
 * Answers a request by rejecting with no response at all, which is the answerless failure path.
 *
 * Assumptions: the error carries the request CONFIGURATION and no response, which is exactly the
 * shape a refused connection, a CORS refusal or an absent route produces. The configuration is the
 * only place the request target survives on that path, which is why it is the member these cases
 * assert against.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} Never resolves; always rejects without a response.
 */
async function answerlessAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.reject(
    new AxiosError(
      'no route to host',
      AxiosError.ERR_NETWORK,
      config as InternalAxiosRequestConfig,
      undefined,
      undefined,
    ),
  );
}

/**
 * Dispatches one request that fails, and returns the problem document the client synthesised.
 * @param {string} target - The request target to dispatch, selector included.
 * @param {typeof proxyHtmlAdapter} adapter - Adapter answering the request locally, either with a
 *   proxy response whose body is not a problem document or with no response at all.
 * @returns {Promise<string>} The `path` member of the synthesised problem document.
 */
async function synthesisedPathFor(
  target: string,
  adapter: typeof proxyHtmlAdapter,
): Promise<string> {
  const client = getApiClient();
  client.defaults.adapter = adapter;
  try {
    await client.get(target);
  } catch (failure) {
    if (isApiRequestError(failure)) {
      return failure.problem.path;
    }
    return `unexpected failure kind: ${String(failure)}`;
  }
  return 'the request did not fail';
}

/**
 * Asserts a proxy answer withholds every protected identifier in the target it reports.
 * @returns {Promise<void>} Resolves once every published selector-bearing target is asserted.
 */
async function withholdsSelectorsFromAProxyAnswer(): Promise<void> {
  for (const { operation, parameters, query, expected } of SELECTOR_BEARING_TARGETS) {
    const target = requestPath(operation, parameters) + (query ?? '');
    expect(await synthesisedPathFor(target, proxyHtmlAdapter)).toBe(expected);
  }
}

/**
 * Asserts an answerless failure withholds every protected identifier in the target it reports.
 * @returns {Promise<void>} Resolves once every published selector-bearing target is asserted.
 */
async function withholdsSelectorsFromAnAnswerlessFailure(): Promise<void> {
  for (const { operation, parameters, query, expected } of SELECTOR_BEARING_TARGETS) {
    const target = requestPath(operation, parameters) + (query ?? '');
    expect(await synthesisedPathFor(target, answerlessAdapter)).toBe(expected);
  }
}

/** Matches one target segment that is entirely a path-template placeholder, for example `{cardKey}`. */
const PLACEHOLDER_SEGMENT = /^\{[A-Za-z][A-Za-z0-9]*\}$/u;

/**
 * Reports whether one segment of a contract path is a placeholder rather than a literal.
 * @param {string} segment - One segment of a versionless contract path.
 * @returns {boolean} `true` when the whole segment is a placeholder.
 */
function isPlaceholderSegment(segment: string): boolean {
  return PLACEHOLDER_SEGMENT.test(segment);
}

/**
 * Reports which segment of one operation's versionless target its single path parameter occupies.
 *
 * Assumptions: the position is derived from the operation's own contract path rather than carried as
 * another column in the table above, so a row cannot state a position its operation does not have. Every
 * row in {@link COLLIDING_VALUES} declares exactly one parameter, and that is asserted here rather than
 * assumed — a two-parameter operation would otherwise silently have only its first position checked.
 * @param {ContractOperation} operation - The operation whose target the case dispatches.
 * @returns {number} The index, within the slash-separated target, of the substituted value.
 */
function parameterPositionOf(operation: ContractOperation): number {
  const segments = operation.path.slice(API_PATH_PREFIX.length).split('/');
  expect(
    segments.filter(isPlaceholderSegment),
    `${operation.operationId} must declare exactly one path parameter for this case to be precise`,
  ).toHaveLength(1);
  return segments.findIndex(isPlaceholderSegment);
}

/**
 * Asserts a value that spells a published route word is withheld exactly as any other value is.
 *
 * Purpose: ⚠️ this is the case the shipped mask could not pass. It kept a segment whenever the segment's
 * VALUE appeared in a set of published route words, so a legal user identifier of `admin` reached a
 * caller's `ApiError.path` in the clear — an enumerable member of a document any error reporter,
 * breadcrumb trail or `JSON.stringify` of a caught failure serialises. Every row here dispatches a real
 * operation whose parameter value collides with a literal the contracts publish elsewhere.
 *
 * Assumptions: BOTH the whole masked rendering and the state of the parameter's own position are
 * asserted. The equality fixes the rendering; the positional assertion states the property that must
 * hold however the rendering changes, and it is the one a future mask has to keep.
 *
 * ⚠️ Measured: the positional assertion was first written as "the value appears nowhere in the reported
 * path", and it FAILED against a correct mask: `getUser('users')` reports `/auth/users/{id}`, where
 * `users` is the route's own published literal at position two and the value at position three is
 * withheld. Asserting absence anywhere would therefore forbid a legitimate route name whenever a caller
 * passed a value spelling it — so the assertion is made at the parameter's position, which is the only
 * place disclosure could occur.
 * @returns {Promise<void>} Resolves once every colliding value is asserted on both failure paths.
 */
async function withholdsAValueThatSpellsARouteWord(): Promise<void> {
  for (const { operation, parameters, value, expected } of COLLIDING_VALUES) {
    const position = parameterPositionOf(operation);
    for (const adapter of [proxyHtmlAdapter, answerlessAdapter]) {
      const reported = await synthesisedPathFor(requestPath(operation, parameters), adapter);
      expect(reported, `${operation.operationId} must not disclose a value of '${value}'`).toBe(
        expected,
      );
      expect(
        reported.split('/')[position],
        `${operation.operationId} must withhold the value at the position it was substituted into`,
      ).not.toBe(value);
    }
  }
}

/**
 * Asserts a target no operation of this application composed has every segment withheld.
 *
 * Assumptions: masking answers from the templates it has been told about, so a target it recognises
 * nothing of is one about which nothing can be asserted to be a literal — and it is masked entirely
 * rather than partially. Trade-offs: the route name is lost, which is accepted because guessing which
 * segments of an unknown path are safe is the guess this whole change removes; and no request this
 * package makes takes that path, since every one of them is composed by `requestPath`.
 * @returns {Promise<void>} Resolves once the assertion is made.
 */
async function withholdsEverySegmentOfAnUnknownTarget(): Promise<void> {
  const reported = await synthesisedPathFor('/not-an-operation/00000000011', proxyHtmlAdapter);

  expect(reported).toBe('/{id}/{id}');
  expect(reported).not.toContain('00000000011');
}

/**
 * Every operation the seven browser clients publish, which is the whole surface a failure can report.
 *
 * Assumptions: the manifests are the source rather than the contract documents, and the substitution is
 * sound because `ui/src/api/contracts.test.ts` asserts each manifest equals its contract's browser-facing
 * operations method for method, path for path and identifier for identifier. Reading the YAML here would
 * duplicate that file's scanner for no additional guarantee.
 */
const EVERY_PUBLISHED_OPERATION: readonly ContractOperation[] = [
  ...ACCOUNT_CONTRACT_OPERATIONS,
  ...AUTH_CONTRACT_OPERATIONS,
  ...AUTHORIZATION_CONTRACT_OPERATIONS,
  ...CARD_CONTRACT_OPERATIONS,
  ...REFERENCE_CONTRACT_OPERATIONS,
  ...REPORTING_CONTRACT_OPERATIONS,
  ...TRANSACTION_CONTRACT_OPERATIONS,
];

/**
 * The measured size of that surface, and of the parameterised subset within it.
 *
 * Assumptions: both figures are pinned, because the exhaustive case below is only exhaustive if the
 * iteration really covers the surface. Fifty-four operations of which twenty-three carry at least one
 * path parameter is the measured state; a manifest that stopped being spread into the array above would
 * otherwise leave the case passing over a smaller set, which is the way an exhaustive gate goes quiet.
 *
 * Refactoring Rationale: it is now 54. The account client gained the no-write validation turn the
 * reference screen's first turn needs, and the PARAMETERISED figure is unchanged at twenty-three because
 * that operation carries its account in a body rather than in its target -- which is the whole point of
 * the account contract's addressing and is what this case exists to keep true.
 *
 * ⚠️ Refactoring Rationale: it is now 55, and the PARAMETERISED figure is again unchanged at twenty-three.
 * The auth client gained `POST /api/v1/auth/sign-out`, which carries the renewal token to be revoked in a
 * body -- a target-borne token would reach every access log between the browser and the service -- so it
 * adds to the surface without adding a path parameter. The figure is re-measured against the seven
 * manifests rather than incremented, which is what makes the disagreement between it and
 * `ui/src/api/contracts.test.ts` impossible to leave standing: that file measures the same surface from
 * the contract documents, so the two figures are the same measurement taken from opposite ends.
 */
const PUBLISHED_OPERATION_COUNT = 55;

/** The measured number of published operations whose target carries a value. */
const PARAMETERISED_OPERATION_COUNT = 23;

/** Matches each placeholder in a contract path, capturing its parameter name. */
const PATH_PLACEHOLDER = /\{([A-Za-z][A-Za-z0-9]*)\}/gu;

/**
 * Pairs one placeholder name with a sentinel value that cannot occur by accident.
 *
 * Assumptions: the sentinel embeds the parameter's own name, so a failing assertion names the placeholder
 * that leaked rather than only the operation; and it is a shape no published literal could ever be, so
 * finding it in a reported path is unambiguous disclosure rather than a coincidental substring.
 * @param {string} name - The placeholder's parameter name.
 * @returns {[string, string]} The name paired with its sentinel value.
 */
function sentinelEntry(name: string): [string, string] {
  return [name, `SENTINEL-${name}-4859452612877065`];
}

/**
 * Projects one placeholder match onto the parameter name it captured.
 * @param {RegExpMatchArray} match - One placeholder match from a contract path.
 * @returns {string} The captured parameter name, or the empty string when nothing was captured.
 */
function capturedParameterName(match: RegExpMatchArray): string {
  return match[1] ?? '';
}

/**
 * Composes every published operation once, so masking is asserted against the whole published surface.
 *
 * Purpose: ⚠️ Measured — without this, mutating the mask's intersection rule from "every matching template
 * declares a literal here" to "some matching template does" left all thirty-five cases green. The mask
 * learns a template when `requestPath` composes it, so a case that had only ever composed
 * `/cards/{cardKey}` was answered from one candidate, where the two rules agree. Composing the whole
 * surface first is what puts `/cards/lookup` beside `/cards/{cardKey}` and makes the difference between
 * the two rules observable — and it is also the state a long-lived browser session converges to.
 *
 * Assumptions: the direction of that dependence is safe in production, which is why the lazy registry is
 * not itself the defect. An additional candidate can only ever mask a further position, never reveal one,
 * so a session that has composed fewer templates discloses no more than this worst case.
 * @returns {void} Nothing; the templates are recorded as a side effect of composing each target.
 */
function recordEveryPublishedTemplate(): void {
  for (const operation of EVERY_PUBLISHED_OPERATION) {
    const names = [...operation.path.slice(API_PATH_PREFIX.length).matchAll(PATH_PLACEHOLDER)].map(
      capturedParameterName,
    );
    requestPath(operation, Object.fromEntries(names.map(sentinelEntry)));
  }
}

/**
 * Asserts no value of any published operation reaches the target a failure reports.
 *
 * Purpose: ⚠️ this is the exhaustive form of the finding this group answers, and it is what makes the
 * claim "no real identifier reaches `ApiError.path`" a measured property of the whole published surface
 * rather than of the handful of operations a table happens to name. Every one of the fifty-three
 * operations is dispatched with a sentinel in each of its path parameters, and the reported target must
 * contain none of them.
 *
 * Assumptions: the first segment is additionally asserted to SURVIVE, which is what stops the case from
 * passing on a mask that simply replaced everything. It is safe to require because no published path
 * declares a placeholder in that position — measured across all seven manifests — so the route family a
 * diagnostic is about is never lost.
 *
 * Trade-offs: one failure is dispatched per operation, which is fifty-three adapter round-trips settled
 * locally with no network and no timer. Sampling would run faster and would stop being exhaustive, which
 * is the only property this case has.
 * @returns {Promise<void>} Resolves once every published operation is asserted.
 */
async function withholdsEveryValueOfEveryPublishedOperation(): Promise<void> {
  expect(
    EVERY_PUBLISHED_OPERATION,
    'every manifest must be spread into the surface, or this case is not exhaustive',
  ).toHaveLength(PUBLISHED_OPERATION_COUNT);
  let parameterised = 0;

  for (const operation of EVERY_PUBLISHED_OPERATION) {
    const template = operation.path.slice(API_PATH_PREFIX.length);
    const names = [...template.matchAll(PATH_PLACEHOLDER)].map(capturedParameterName);
    const parameters = Object.fromEntries(names.map(sentinelEntry));
    const reported = await synthesisedPathFor(requestPath(operation, parameters), proxyHtmlAdapter);

    for (const value of Object.values(parameters)) {
      expect(reported, `${operation.operationId} must withhold ${value}`).not.toContain(value);
    }
    expect(
      reported.split('/')[1],
      `${operation.operationId} must still report the route family it addressed`,
    ).toBe(template.split('/')[1]);
    if (names.length > 0) {
      parameterised += 1;
    }
  }

  expect(
    parameterised,
    'the surface must carry the measured number of value-bearing targets, or nothing was withheld',
  ).toBe(PARAMETERISED_OPERATION_COUNT);
}

/**
 * Asserts no multi-digit run from the request survives into the reported target.
 *
 * Refactoring Rationale: this case asserted that the reported target kept the LENGTH of the
 * requested one, so that a browser diagnostic lined up character for character against a gateway
 * access record. The narrowing that shipped is the published-template allow-list rather than the
 * server's digit-run rule, and it deliberately gives that up: it replaces a whole segment with the
 * template placeholder and drops the query string, so the length necessarily changes. Alignment was
 * never the property this group exists to defend — non-disclosure is — so the assertion is made on
 * non-disclosure directly, which is also the stronger claim: it admits no rendering that leaks a
 * selector, whereas a length check passes on any narrowing of the right width.
 * @returns {Promise<void>} Resolves once the assertion is made.
 */
async function withholdsEveryMultiDigitRunFromTheReportedTarget(): Promise<void> {
  const target =
    requestPath(
      {
        method: 'GET',
        path: '/api/v1/accounts/{accountId}/card-xrefs',
        operationId: 'listAccountCardCrossReferences',
      },
      { accountId: '00000000011' },
    ) + '?cursor=4859452612877065';
  const reported = await synthesisedPathFor(target, proxyHtmlAdapter);
  expect(reported).not.toContain('4859452612877065');
  expect(reported).not.toContain('00000000011');
  // Assumptions: ⚠️ no digit at all may remain, and the assertion is on digits rather than on runs of
  //   two or more. It admitted `v1` as the one permitted digit pair when the version prefix was part of
  //   the reported path; `requestPath` removes that prefix, because a build's base URL already carries
  //   it, so nothing published in a target this client dispatches contains a digit.
  expect(reported).not.toMatch(/[0-9]/u);
}

/**
 * Groups the assertions that keep a protected identifier out of a synthesised problem document.
 *
 * WHY : these cases exist because `ApiRequestError.problem` is an ordinary enumerable object, so
 *       anything that serialises a caught failure serialises whatever is in it. The Axios `cause` was
 *       already removed from that class to stop a bearer token travelling that way; the request
 *       target was the remaining member carrying caller-visible identifiers, and several published
 *       routes put a card number, an account identifier or a customer identifier in one.
 */
function synthesisedProblemDisclosureContract(): void {
  beforeEach(stubBuildConfiguration);
  beforeEach(recordEveryPublishedTemplate);
  afterEach(restoreBuildConfiguration);
  it('withholds selectors from a proxy answer', withholdsSelectorsFromAProxyAnswer);
  it('withholds selectors from an answerless failure', withholdsSelectorsFromAnAnswerlessFailure);
  it('withholds a value that spells a published route word', withholdsAValueThatSpellsARouteWord);
  it('withholds every segment of an unrecognised target', withholdsEverySegmentOfAnUnknownTarget);
  it(
    'withholds every value of every published operation',
    withholdsEveryValueOfEveryPublishedOperation,
  );
  it(
    'withholds every multi-digit run from the reported target',
    withholdsEveryMultiDigitRunFromTheReportedTarget,
  );
}

describe('synthesised problem disclosure contract', synthesisedProblemDisclosureContract);

/**
 * A fixed server instant, in the IMF-fixdate form an HTTP `Date` response header carries.
 *
 * Assumptions: deliberately far from any plausible test-run clock, so a case that passed by reading
 * the LOCAL clock instead of this header would be visible rather than coincidentally correct.
 */
const SERVER_DATE_HEADER = 'Tue, 15 Jul 2025 14:23:45 GMT';

/**
 * Builds the failure a real transport raises for a refused request, carrying its response.
 *
 * Assumptions: the response is attached to the failure rather than returned in place of it, because
 * that is the shape `normaliseFailureAndThrow` reads — it asks Axios whether the rejection is a
 * transport failure, then classifies from `failure.response`. A resolved non-2xx response would reach
 * the SUCCESS interceptor instead, since Axios applies `validateStatus` inside its own transports and
 * not around a supplied adapter, so every case below would assert the error path without entering it.
 * @param {AxiosRequestConfig} config - The dispatched configuration, echoed onto the response so the
 *   classifier can recover the correlation identifier the request was sent under.
 * @param {number} status - HTTP status the service answered with.
 * @param {string} statusText - Reason phrase, carried for fidelity with a real response.
 * @param {unknown} body - The response body exactly as it arrived, which may be a problem document, a
 *   partial object, or something that is not JSON at all.
 * @param {Record<string, string>} headers - Response headers, lower-cased as a transport delivers them.
 * @returns {AxiosError} The failure to reject with, recognised by `axios.isAxiosError`.
 */
function failureCarrying(
  config: AxiosRequestConfig,
  status: number,
  statusText: string,
  body: unknown,
  headers: Record<string, string> = {},
): AxiosError {
  return new AxiosError(
    `Request failed with status code ${String(status)}`,
    AxiosError.ERR_BAD_RESPONSE,
    config as InternalAxiosRequestConfig,
    undefined,
    {
      data: body,
      status,
      statusText,
      headers,
      config,
    } as AxiosResponse,
  );
}

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
 *
 * Refactoring Rationale: this adapter used to RESOLVE the 500 under a comment claiming that Axios would
 * convert it into a rejection. Measured, it does not: status validation lives in Axios's transport
 * adapters, so a resolved 500 reaches the success branch and this case anchored the clock through the
 * path a 200 takes. It now rejects the way `settle` does -- an `AxiosError` carrying the response, the
 * configuration and the code Axios itself assigns a 5xx -- so the case exercises the failure branch it
 * is named for. The failure-classification cases below go further and use Axios's own `fetch` adapter.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} Never resolves; always rejects with an `AxiosError`.
 */
async function datedFailureAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  // Refactoring Rationale: the failure is RAISED here rather than returned as a 500 response. A
  //   custom adapter is the component that decides which statuses are failures -- Axios applies
  //   `validateStatus` inside its own transports and not around a supplied adapter -- so an adapter
  //   that RESOLVED with a 500 sent the response down the SUCCESS interceptor, and this case asserted
  //   the error path while never entering it. Raising an `AxiosError` carrying the response is what a
  //   real transport does, and it is the only way the rejection interceptor under test is reached. The
  //   construction is delegated to `failureCarrying` so this adapter and the failure-classification
  //   cases below build the rejection the one way, exactly as Axios's own `settle` does.
  return Promise.reject(
    failureCarrying(config, 500, 'Internal Server Error', {}, { date: SERVER_DATE_HEADER }),
  );
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

/**
 * A problem document shaped exactly as every published contract declares one.
 *
 * Assumptions: complete rather than partial, because `isApiError` in the module under test admits a body
 * only when it carries the whole shape -- so a partial fixture would be classified RESPONSE and the
 * PROBLEM branch would never be reached, which is the mistake this file exists to catch.
 * @param {number} status - The status the document reports, matching the status it is answered with.
 * @param {string} code - The service code the document carries.
 * @returns {Record<string, unknown>} The document a service would have sent.
 */
function serviceProblem(status: number, code: string): Record<string, unknown> {
  return {
    code,
    secondaryCode: '',
    message: 'Refused by the service.',
    // Refactoring Rationale: the non-5xx arm read 'ERROR', which is not a member of the published
    //   `Severity` union -- `ApiError.Severity` in common-lib declares LOG, INFO, WARNING and CRITICAL
    //   only, and its 4xx factories all pass WARNING. The module's problem-document guard admits the
    //   published domain and no more, so a document carrying 'ERROR' was correctly classified as an
    //   unrecognised response body rather than as a problem: the fixture was wrong, not the guard.
    severity: status >= 500 ? 'CRITICAL' : 'WARNING',
    subsystem: 'APPLICATION',
    status,
    correlationId: SERVICE_CORRELATION_ID,
    path: SERVICE_REPORTED_PATH,
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors: [],
    abend: null,
  };
}

/** The identifier a service echoes, distinct from any this client mints, so its origin is visible. */
const SERVICE_CORRELATION_ID = 'SERVICEECHOED0000000ABCD';

/** The path a SERVICE reports, which this client must carry through untouched. */
const SERVICE_REPORTED_PATH = '/api/v1/cards/************7065';

/** A target carrying an identifier, a query and a fragment -- everything masking has to remove. */
const TARGET_WITH_IDENTIFIERS = '/accounts/00000000011/card-xrefs?direction=next#row';

/** What that target must become in a document this module synthesises. */
const MASKED_TARGET = '/accounts/{id}/card-xrefs';

/** The four kinds the module classifies a transport failure into, as its own type declares them. */
const PROBLEM_CODE_UNEXPECTED_BODY = 'CARDDEMO-UI-BODY';

/** Code the module mints for a request that reached something and then ran out of time. */
const PROBLEM_CODE_TIMEOUT = 'CARDDEMO-UI-TIMEOUT';

/** Code the module mints for a request nothing answered. */
const PROBLEM_CODE_NETWORK = 'CARDDEMO-UI-NETWORK';

/** Status the module reports when no response arrived at all. */
const NO_HTTP_STATUS = 0;

/**
 * Installs Axios's own `fetch` adapter with the global `fetch` replaced by the given answer.
 *
 * Assumptions: the ADAPTER is Axios's rather than this file's, which is the whole point. Settlement,
 * status validation, header parsing and the `AxiosError` code assignment are then performed by the
 * library exactly as they are in a browser, and what this file supplies is only the transport answer.
 * @param {(request: unknown) => Promise<Response>} answer - Called for each request with whatever Axios
 *   handed the transport; resolve to answer it, reject to fail it the way a browser's `fetch` fails.
 * @returns {void} Nothing; the client and the global are both restored by the fixture teardown.
 */
function answerFetchWith(answer: (request: unknown) => Promise<Response>): void {
  const client = getApiClient();
  client.defaults.adapter = 'fetch';
  vi.stubGlobal('fetch', vi.fn(answer));
}

/**
 * Dispatches one request expected to fail and returns the normalised failure it rejected with.
 * @param {string} target - The target to request, relative to the configured base URL.
 * @param {number} [timeout] - Per-request timeout in milliseconds; omitted for the client's own.
 * @returns {Promise<ApiRequestError>} The failure this module raised.
 * @throws {Error} If the request did not fail, or failed with something this module did not normalise.
 */
async function failureFrom(target: string, timeout?: number): Promise<ApiRequestError> {
  try {
    await getApiClient().get(target, timeout === undefined ? {} : { timeout });
  } catch (raised: unknown) {
    if (isApiRequestError(raised)) {
      return raised;
    }
    throw new Error(`the module raised something it had not normalised: ${String(raised)}`);
  }
  throw new Error('the request settled successfully where a failure was required');
}

/**
 * A transport that never answers and fails only when Axios's own timeout aborts it.
 *
 * Measured: the abort signal has to be read off the REQUEST rather than from a second argument, because
 * Axios's fetch adapter composes a `Request` and passes that alone. A mock reading an `init.signal`
 * never observes the abort at all, and the case then fails by exceeding its own five-second limit
 * instead of by classifying anything -- which is how this was first written.
 * @param {unknown} request - The `Request` Axios composed, read for the signal it carries.
 * @returns {Promise<Response>} A promise that rejects when the request is aborted and never resolves.
 */
async function neverAnswering(request: unknown): Promise<Response> {
  /**
   * Waits for Axios's own abort and then fails the way a browser transport does.
   * @param {(value: Response) => void} _resolve - Never called; this transport never answers.
   * @param {(reason: Error) => void} reject - Called when the composed signal aborts.
   * @returns {void} Nothing; the promise stays pending until the abort fires.
   */
  function awaitTheAbort(
    _resolve: (value: Response) => void,
    reject: (reason: Error) => void,
  ): void {
    const signal = (request as { signal?: AbortSignal }).signal;

    /**
     * Fails the pending request with the error a browser raises for an aborted fetch.
     * @returns {void} Nothing; the promise settles as a rejection.
     */
    function failAsAborted(): void {
      reject(new DOMException('The operation was aborted.', 'AbortError'));
    }
    signal?.addEventListener('abort', failAsAborted);
  }
  return new Promise<Response>(awaitTheAbort);
}

/**
 * A per-request timeout short enough to elapse within one case and long enough not to race setup.
 *
 * Assumptions: a real elapsed timer rather than a fake clock, because the abort is wired by Axios
 * through `AbortController` and the composed signal, and replacing the clock would test the fake.
 */
const TINY_TIMEOUT_MS = 25;

/**
 * Asserts a service's own problem document is adopted verbatim, including the path it reported.
 *
 * Assumptions: the reported PATH is asserted, not just the code. It is the one member this module could
 * plausibly overwrite with its own masked template, and doing so would replace a service's statement
 * about what it refused with this client's guess at it.
 */
async function adoptsAServiceProblemDocument(): Promise<void> {
  /**
   * Answers with a refusal carrying a complete problem document.
   * @returns {Promise<Response>} The 400 a service would have sent.
   */
  async function refuseWithAProblem(): Promise<Response> {
    return Promise.resolve(
      new Response(JSON.stringify(serviceProblem(400, 'CARDDEMO-0400')), {
        status: 400,
        headers: { 'content-type': 'application/json' },
      }),
    );
  }
  answerFetchWith(refuseWithAProblem);

  const failure = await failureFrom(TARGET_WITH_IDENTIFIERS);
  expect(failure.kind).toBe('PROBLEM');
  expect(failure.status).toBe(400);
  expect(failure.problem.code).toBe('CARDDEMO-0400');
  expect(failure.correlationId).toBe(SERVICE_CORRELATION_ID);
  expect(failure.problem.path).toBe(SERVICE_REPORTED_PATH);
}

/**
 * Asserts a refusal carrying something other than a problem document is classified and masked.
 *
 * Purpose: this is the case that fixes both halves of the synthesised document -- that it is classified
 * RESPONSE rather than PROBLEM, and that the target it names is the masked template rather than the
 * dispatched value. A gateway or a proxy answering HTML is the realistic source, and the target it
 * failed on is the one carrying an account identifier.
 */
async function classifiesAndMasksANonProblemRefusal(): Promise<void> {
  // Assumptions: three separate absences are asserted after the equality, not instead of it. The
  //   equality fixes the whole value, and the three name what must not survive -- the identifier, the
  //   query and the fragment -- so a future masking rule that changed the placeholder still has to keep
  //   all three out rather than merely produce a different string.
  /**
   * Answers with the markup a gateway sends when it refuses upstream.
   * @returns {Promise<Response>} A 502 carrying no problem document.
   */
  async function refuseWithMarkup(): Promise<Response> {
    return Promise.resolve(
      new Response('<html>upstream refused</html>', {
        status: 502,
        headers: { 'content-type': 'text/html' },
      }),
    );
  }
  answerFetchWith(refuseWithMarkup);

  const failure = await failureFrom(TARGET_WITH_IDENTIFIERS);
  expect(failure.kind).toBe('RESPONSE');
  expect(failure.status).toBe(502);
  expect(failure.problem.code).toBe(PROBLEM_CODE_UNEXPECTED_BODY);
  expect(failure.problem.path).toBe(MASKED_TARGET);
  expect(failure.problem.path).not.toContain('00000000011');
  expect(failure.problem.path).not.toContain('direction');
  expect(failure.problem.path).not.toContain('#');
}

/**
 * Asserts a request that reached something and ran out of time is classified as a timeout.
 *
 * Assumptions: the timeout is REAL -- the transport never answers, Axios aborts on its own timer, and
 * the code under test reads the code Axios assigned. Constructing an `AxiosError` with a timeout code by
 * hand would assert this file's belief about which code Axios uses rather than the behaviour.
 */
async function classifiesARealTimeout(): Promise<void> {
  answerFetchWith(neverAnswering);

  const failure = await failureFrom(TARGET_WITH_IDENTIFIERS, TINY_TIMEOUT_MS);
  expect(failure.kind).toBe('TIMEOUT');
  expect(failure.status).toBe(NO_HTTP_STATUS);
  expect(failure.problem.code).toBe(PROBLEM_CODE_TIMEOUT);
  expect(failure.problem.path).toBe(MASKED_TARGET);
}

/** Asserts a request nothing answered at all is classified as a network failure. */
async function classifiesANetworkFailure(): Promise<void> {
  /**
   * Fails the request the way a browser's `fetch` fails when nothing answers.
   * @returns {Promise<Response>} A rejected promise carrying the browser's own error type.
   */
  async function failWithoutAnswering(): Promise<Response> {
    return Promise.reject(new TypeError('Failed to fetch'));
  }
  answerFetchWith(failWithoutAnswering);

  const failure = await failureFrom(TARGET_WITH_IDENTIFIERS);
  expect(failure.kind).toBe('NETWORK');
  expect(failure.status).toBe(NO_HTTP_STATUS);
  expect(failure.problem.code).toBe(PROBLEM_CODE_NETWORK);
  expect(failure.problem.path).toBe(MASKED_TARGET);
}

/**
 * Dispatches one request that asks for its body undecoded and returns the failure it rejected with.
 *
 * Assumptions: this exists beside {@link failureFrom} rather than replacing it, because the two ask for
 * different things and the difference is the subject of the cases below. A request carrying
 * `responseType: 'blob'` has EVERY body materialised as a `Blob` -- the refusals included -- so a
 * service's problem document arrives on this path in a container the shape guard cannot narrow.
 * @param {string} target - The target to request, relative to the configured base URL.
 * @returns {Promise<ApiRequestError>} The failure this module raised.
 * @throws {Error} If the request did not fail, or failed with something this module did not normalise.
 */
async function blobFailureFrom(target: string): Promise<ApiRequestError> {
  try {
    await getApiClient().get(target, { responseType: 'blob' });
  } catch (raised: unknown) {
    if (isApiRequestError(raised)) {
      return raised;
    }
    throw new Error(`the module raised something it had not normalised: ${String(raised)}`);
  }
  throw new Error('the request settled successfully where a failure was required');
}

/**
 * Asserts a service's problem document survives arriving as a `Blob` on a binary request.
 *
 * Purpose: ⚠️ this is the case the two document operations needed and did not have. Their refusals are
 * JSON even though their success bodies are bytes, and Axios materialises a body according to the
 * response type the REQUEST asked for -- so the document arrived as a `Blob`, failed the object test in
 * `isApiError` for the shape of its container rather than for its contents, and was replaced by a
 * synthesised `CARDDEMO-UI-BODY`. Everything a screen renders was lost with it: the service's code, its
 * sentence, its `fieldErrors` array and its abend detail.
 *
 * Assumptions: the service's own `message` and reported `path` are asserted, not just the code, because
 * they are what separates an ADOPTED document from a synthesised one. A synthesised document carries a
 * null message and this client's masked template, so either assertion alone would fail if the decode
 * were dropped -- and asserting the code alone would not, since a synthesised code is also a string.
 */
async function preservesAProblemDocumentDeliveredAsABlob(): Promise<void> {
  /**
   * Answers with the JSON refusal a service sends to a request that asked for bytes.
   * @returns {Promise<Response>} The 400 a service would have sent.
   */
  async function refuseWithAProblem(): Promise<Response> {
    return Promise.resolve(
      new Response(JSON.stringify(serviceProblem(400, 'CARDDEMO-0400')), {
        status: 400,
        headers: { 'content-type': 'application/json' },
      }),
    );
  }
  answerFetchWith(refuseWithAProblem);

  const failure = await blobFailureFrom(TARGET_WITH_IDENTIFIERS);
  expect(failure.kind).toBe('PROBLEM');
  expect(failure.status).toBe(400);
  expect(failure.problem.code).toBe('CARDDEMO-0400');
  expect(failure.problem.message).toBe('Refused by the service.');
  expect(failure.problem.path).toBe(SERVICE_REPORTED_PATH);
  expect(failure.correlationId).toBe(SERVICE_CORRELATION_ID);
}

/**
 * Asserts a refusal whose body really is bytes still reaches a caller as the synthesised document.
 *
 * Assumptions: this is the other half of the pair, and without it the decode above could be satisfied by
 * reading EVERY failed blob body into memory and parsing it. A body a service declares as bytes is left
 * alone: it is not a problem document, reading it would materialise an arbitrary payload to discover
 * that, and the caller is told what it is told for every unrecognised body -- `CARDDEMO-UI-BODY` with
 * the masked target and no invented sentence.
 */
async function keepsTheSyntheticDocumentForANonJsonBlob(): Promise<void> {
  /**
   * Answers with a refusal whose body is declared as an opaque byte stream.
   * @returns {Promise<Response>} A 502 carrying bytes rather than a document.
   */
  async function refuseWithBytes(): Promise<Response> {
    return Promise.resolve(
      new Response('\u0000\u0001not a document', {
        status: 502,
        headers: { 'content-type': 'application/octet-stream' },
      }),
    );
  }
  answerFetchWith(refuseWithBytes);

  const failure = await blobFailureFrom(TARGET_WITH_IDENTIFIERS);
  expect(failure.kind).toBe('RESPONSE');
  expect(failure.status).toBe(502);
  expect(failure.problem.code).toBe(PROBLEM_CODE_UNEXPECTED_BODY);
  expect(failure.problem.message).toBeNull();
  expect(failure.problem.path).toBe(MASKED_TARGET);
}

/**
 * Asserts a JSON-typed `Blob` whose contents are not JSON falls back rather than raising.
 *
 * Assumptions: the declared media type and the actual bytes are allowed to disagree, because a gateway
 * that truncates a response leaves exactly that state -- a `content-type` naming JSON over a body that
 * no longer parses. The decode is guarded, so the caller receives the ordinary unrecognised-body
 * document; an unguarded parse would replace the failure the caller is waiting for with a `SyntaxError`
 * raised inside the interceptor, which reads as a defect in the client rather than in the response.
 */
async function fallsBackWhenAJsonBlobDoesNotParse(): Promise<void> {
  /**
   * Answers with a truncated body that still claims to be JSON.
   * @returns {Promise<Response>} A 500 whose declared type and contents disagree.
   */
  async function refuseWithTruncatedJson(): Promise<Response> {
    return Promise.resolve(
      new Response('{"code":"CARDDEMO-0500"', {
        status: 500,
        headers: { 'content-type': 'application/json' },
      }),
    );
  }
  answerFetchWith(refuseWithTruncatedJson);

  const failure = await blobFailureFrom(TARGET_WITH_IDENTIFIERS);
  expect(failure.kind).toBe('RESPONSE');
  expect(failure.status).toBe(500);
  expect(failure.problem.code).toBe(PROBLEM_CODE_UNEXPECTED_BODY);
  expect(failure.problem.path).toBe(MASKED_TARGET);
}

/** Every `Authorization` header value the transport saw, in order, with `null` for its absence. */
let authorizationHeaders: (string | null)[] = [];

/** The token a signed-in session holds for these cases. */
const STORED_TOKEN = 'stored.session.token';

/**
 * Records the `Authorization` header one request carried and answers it as instructed.
 *
 * Assumptions: the header is read off the composed `Request` rather than from the client's defaults,
 * because what a session discard has to change is what the NEXT request transmits -- reading the
 * defaults would assert the module's own bookkeeping instead of the observable effect.
 * @param {unknown} request - The `Request` Axios composed.
 * @param {() => Response} answer - Builds the response to answer with.
 * @returns {Promise<Response>} The answer, after the header has been recorded.
 */
async function recordAuthorizationAndAnswer(
  request: unknown,
  answer: () => Response,
): Promise<Response> {
  const headers = (request as { headers?: { get?: (name: string) => string | null } }).headers;
  const carried = headers?.get?.('authorization') ?? null;
  authorizationHeaders.push(carried);
  return Promise.resolve(answer());
}

/**
 * Answers with one refusal carrying a problem document, recording what the request carried.
 * @param {number} status - The status to refuse with.
 * @returns {(request: unknown) => Promise<Response>} A transport answer for `answerFetchWith`.
 */
function refusalRecordingAuthorization(status: number): (request: unknown) => Promise<Response> {
  /**
   * Answers one request with the refusal above.
   * @param {unknown} request - The `Request` Axios composed.
   * @returns {Promise<Response>} The refusal.
   */
  /**
   * Builds the refusal this answer carries.
   * @returns {Response} The refusal, carrying a complete problem document.
   */
  function refusal(): Response {
    return new Response(JSON.stringify(serviceProblem(status, `CARDDEMO-0${String(status)}`)), {
      status,
      headers: { 'content-type': 'application/json' },
    });
  }

  /**
   * Answers one request with the refusal above.
   * @param {unknown} request - The `Request` Axios composed.
   * @returns {Promise<Response>} The refusal.
   */
  async function answer(request: unknown): Promise<Response> {
    return recordAuthorizationAndAnswer(request, refusal);
  }
  return answer;
}

/**
 * Answers one request successfully, recording what it carried.
 * @param {unknown} request - The `Request` Axios composed.
 * @returns {Promise<Response>} An empty success.
 */
async function successRecordingAuthorization(request: unknown): Promise<Response> {
  /**
   * Builds the empty success this answer carries.
   * @returns {Response} A 200 with an empty JSON body.
   */
  function success(): Response {
    return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
  }
  return recordAuthorizationAndAnswer(request, success);
}

/**
 * Lets any queued authentication signal run before the assertion reads it.
 *
 * Assumptions: a real task turn rather than a microtask, because the module schedules each signal with
 * `queueMicrotask` deliberately -- so that a listener which throws cannot replace the failure the caller
 * is waiting for -- and a case that read the listener synchronously would observe nothing and pass while
 * the signal was never delivered.
 * @returns {Promise<void>} Resolves after the queued signals have run.
 */
async function afterQueuedSignals(): Promise<void> {
  /**
   * Resolves on the next task turn, after any queued microtask has run.
   * @param {() => void} resolve - Called once the turn has elapsed.
   * @returns {void} Nothing; the timer settles the promise.
   */
  function onTheNextTurn(resolve: () => void): void {
    setTimeout(resolve, 0);
  }
  await new Promise<void>(onTheNextTurn);
}

/**
 * Registers a listener that collects every authentication signal delivered while a case runs.
 *
 * Assumptions: one helper for all three refusal cases rather than a listener written at each, because the
 * registry is module-level state in the code under test and a case that forgot to unsubscribe would leak
 * its listener into the next -- so registration and removal are stated once, together.
 * @returns {{ signalled: ApiRequestError[]; unsubscribe: () => void }} The collector and its removal.
 */
function collectAuthenticationSignals(): {
  signalled: ApiRequestError[];
  unsubscribe: () => void;
} {
  const signalled: ApiRequestError[] = [];

  /**
   * Records one delivered signal.
   * @param {ApiRequestError} failure - The normalised failure the module passed to the listener.
   * @returns {void} Nothing; the collector grows by one.
   */
  function record(failure: ApiRequestError): void {
    signalled.push(failure);
  }
  return { signalled, unsubscribe: subscribeToAuthenticationRequired(record) };
}

/**
 * Asserts a 401 on a request that carried a bearer discards the session and signals once.
 *
 * Purpose: this is the one failure with an EFFECT beyond its rejection, and the effect is invisible at
 * every call site: the stored token is discarded and whoever asked to be told is told. Without this case
 * the discard could be deleted and every screen would keep sending a token the services have stopped
 * accepting, receiving 401 after 401 with no prompt to sign in again.
 */
async function discardsTheSessionOnAnUnauthorizedBearer(): Promise<void> {
  const { signalled, unsubscribe } = collectAuthenticationSignals();
  setAccessToken(STORED_TOKEN);
  answerFetchWith(refusalRecordingAuthorization(401));

  const failure = await failureFrom(TARGET_WITH_IDENTIFIERS);
  await afterQueuedSignals();

  expect(failure.kind).toBe('PROBLEM');
  expect(failure.status).toBe(401);
  expect(authorizationHeaders).toEqual([`Bearer ${STORED_TOKEN}`]);
  expect(signalled).toHaveLength(1);
  expect(signalled[0]).toBe(failure);

  answerFetchWith(successRecordingAuthorization);
  await getApiClient().get(TARGET_WITH_IDENTIFIERS);
  expect(authorizationHeaders.at(-1)).toBeNull();
  unsubscribe();
}

/**
 * Asserts a 401 on a request that carried NO bearer neither discards nor signals.
 *
 * Assumptions: this is the sign-on case, and it is the reason the discard tests the header rather than
 * the status alone. The sign-on contract answers 401 for a rejected credential -- one status covering a
 * wrong password and an unknown identifier, so the endpoint cannot be used to tell them apart -- so
 * treating every 401 as an expired session would report "signed out" to an operator mistyping a
 * password.
 */
async function leavesAnUnauthenticatedRefusalAlone(): Promise<void> {
  const { signalled, unsubscribe } = collectAuthenticationSignals();
  answerFetchWith(refusalRecordingAuthorization(401));

  const failure = await failureFrom('/auth/signon');
  await afterQueuedSignals();

  expect(failure.status).toBe(401);
  expect(authorizationHeaders).toEqual([null]);
  expect(signalled).toHaveLength(0);
  unsubscribe();
}

/**
 * Asserts a 403 leaves the session in place.
 *
 * Assumptions: 403 is asserted explicitly because it is the refusal an ordinary operator meets on an
 * administrative screen. Discarding the session for it would sign out someone who is correctly signed
 * in, and they would sign straight back in and meet the same refusal.
 */
async function keepsTheSessionOnAForbiddenRefusal(): Promise<void> {
  const { signalled, unsubscribe } = collectAuthenticationSignals();
  setAccessToken(STORED_TOKEN);
  answerFetchWith(refusalRecordingAuthorization(403));

  const failure = await failureFrom('/users');
  await afterQueuedSignals();

  expect(failure.status).toBe(403);
  expect(signalled).toHaveLength(0);

  answerFetchWith(successRecordingAuthorization);
  await getApiClient().get('/users');
  expect(authorizationHeaders.at(-1)).toBe(`Bearer ${STORED_TOKEN}`);
  unsubscribe();
}

/** Prepares one failure-classification case: fresh configuration, no session, nothing recorded. */
function stubFailureFixture(): void {
  stubBuildConfiguration();
  authorizationHeaders = [];
  resetApiClient();
  setAccessToken(null);
}

/** Restores the environment, the transport and the session so no later case inherits any of them. */
function restoreFailureFixture(): void {
  vi.unstubAllGlobals();
  setAccessToken(null);
  resetApiClient();
  restoreBuildConfiguration();
}

/**
 * Groups the assertions that fix how a transport failure reaches a caller.
 *
 * WHY : Refactoring Rationale: these cases exist because the ones that were here could not fail. They
 *       drove failures through a custom adapter that RESOLVED a non-2xx response, and Axios validates a
 *       status inside its transport adapters rather than in its core -- so nothing rejected, the
 *       classifier never ran, and the four kinds, the problem parsing, the target masking and the
 *       session discard were all unasserted. Each case below drives Axios's own `fetch` adapter, so the
 *       rejection is settled by the library.
 *
 * WHY : Measured: four mutations, four disjoint results, and no case moved that should not have.
 *       Copying the dispatched target into the synthesised document instead of masking it fails exactly
 *       the three cases that assert a masked path. Removing the branch that adopts a service's own
 *       problem document fails `adopts a service problem document` AND
 *       `discards the session on an unauthorized bearer`, because the latter asserts the kind as well as
 *       the effect. Inverting the timeout test fails exactly the two answerless cases. Removing the token
 *       discard fails exactly the 401 case, on the header the FOLLOWING request carried.
 */
function transportFailureContract(): void {
  beforeEach(stubFailureFixture);
  afterEach(restoreFailureFixture);
  it('adopts a service problem document', adoptsAServiceProblemDocument);
  it('classifies and masks a non-problem refusal', classifiesAndMasksANonProblemRefusal);
  it('preserves a problem document delivered as a blob', preservesAProblemDocumentDeliveredAsABlob);
  it('keeps the synthetic document for a non-json blob', keepsTheSyntheticDocumentForANonJsonBlob);
  it('falls back when a json blob does not parse', fallsBackWhenAJsonBlobDoesNotParse);
  it('classifies a real timeout', classifiesARealTimeout);
  it('classifies a network failure', classifiesANetworkFailure);
  it('discards the session on an unauthorized bearer', discardsTheSessionOnAnUnauthorizedBearer);
  it('leaves an unauthenticated refusal alone', leavesAnUnauthenticatedRefusalAlone);
  it('keeps the session on a forbidden refusal', keepsTheSessionOnAForbiddenRefusal);
}

describe('transport failure contract', transportFailureContract);

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

/**
 * A complete problem document, carrying every member the shared advice publishes.
 *
 * Assumptions: this is the shape
 * `services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java` serialises — eleven
 * members, with `message` and `abend` the only nullable ones — so a case built on it asserts what a
 * conforming service actually sends rather than a convenient subset of it.
 */
const COMPLETE_PROBLEM = {
  code: 'CARDDEMO-0400',
  secondaryCode: '',
  message: 'Account Filter must be a non-zero 11 digit number',
  severity: 'WARNING',
  subsystem: 'APPLICATION',
  status: 400,
  correlationId: 'CD0123456789ABCDEF012345',
  path: '/api/v1/accounts/view',
  timestamp: '2025-07-15 14:23:45.123456',
  fieldErrors: [
    {
      field: 'accountId',
      state: 'NOT_OK',
      message: 'Account Filter must be a non-zero 11 digit number',
    },
  ],
  abend: null,
};

/**
 * The exact body that satisfied the superseded three-member probe and nothing more.
 *
 * Assumptions: a numeric status, a correlation identifier and an array were the whole of what the
 * guard used to check, so this object was promoted to a trusted problem document — leaving an
 * undefined code to reach a message band and an undefined severity to reach a screen keying on it.
 * It is retained as the regression guard for that promotion.
 */
const PARTIAL_PROBLEM = {
  status: 400,
  correlationId: 'CD0123456789ABCDEF012345',
  fieldErrors: [],
};

/** The problem code this client mints for a response whose body is not a problem document. */
const CODE_UNEXPECTED_BODY = 'CARDDEMO-UI-BODY';

/** Status a service answers when a caller's token is absent, expired or not accepted. */
const UNAUTHORIZED = 401;

/** Status a service answers when a valid token is refused one particular route. */
const FORBIDDEN = 403;

/*
 * WHY : ⚠️ Refactoring Rationale: a `carddemo.access-token` session-storage key was named here and read
 *       by two cases below. It no longer exists: a review found every credential this application held
 *       sitting in script-readable Web Storage, and the bearer moved into a module variable that nothing
 *       outside `ui/src/api/client.ts` can reach. Those two cases now observe the bearer through the
 *       header a DISPATCHED request carries, which is both the only route left to it and the better
 *       observable -- what a service sees is the property under assertion, and a stored value that never
 *       reached a request was only ever a proxy for it.
 */

let plannedStatus = UNAUTHORIZED;

let plannedBody: unknown = {};

/**
 * Answers the dispatched request with the planned refusal, as a real transport raises it.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} Never resolves; rejects with the planned failure.
 */
async function plannedRefusalAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.reject(failureCarrying(config, plannedStatus, 'Refused', plannedBody));
}

/**
 * Dispatches one request through the real client and returns the failure it rejected with.
 * @returns {Promise<ApiRequestError>} The normalised failure the client raised.
 * @throws {Error} If the request did not fail, or failed with something this client did not mint,
 *   because either outcome means the case is no longer exercising the classifier it was written for.
 */
async function refusedRequest(): Promise<ApiRequestError> {
  const client = getApiClient();
  client.defaults.adapter = plannedRefusalAdapter;
  try {
    await client.get('/api/v1/accounts/view');
  } catch (failure: unknown) {
    if (isApiRequestError(failure)) {
      return failure;
    }
    throw new Error('the client rejected with a failure it did not normalise');
  }
  throw new Error('the planned refusal was answered as a success');
}

/**
 * Yields to the microtask queue so a deferred listener notification has been delivered.
 *
 * Assumptions: the client notifies its listeners from a microtask rather than inline, so a case
 * asserting the notification has to let that queue drain. One turn is sufficient because the delivery
 * is enqueued before the rejection propagates, and the second is insurance against a further hop
 * being introduced between the two.
 * @returns {Promise<void>} Resolves once the queue has been given two turns.
 */
async function settleNotifications(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/** Plans a refusal carrying the complete problem document and discards any held bearer. */
function stubProblemFixture(): void {
  stubBuildConfiguration();
  setAccessToken(null);
  plannedStatus = 400;
  plannedBody = COMPLETE_PROBLEM;
}

/**
 * Restores the environment and discards the bearer so no later case inherits either.
 *
 * Assumptions: ⚠️ the bearer is discarded rather than `sessionStorage` cleared. It never lived in a
 * store as far as these cases are concerned any more, and clearing a store that holds nothing would
 * read as isolation this file no longer has.
 * @returns {void} Nothing; no bearer is held.
 */
function restoreProblemFixture(): void {
  restoreBuildConfiguration();
  setAccessToken(null);
}

/** Asserts a complete problem document is carried through verbatim and classified as one. */
async function carriesACompleteProblemDocumentVerbatim(): Promise<void> {
  const failure = await refusedRequest();

  expect(failure.kind).toBe('PROBLEM');
  expect(failure.status).toBe(400);
  expect(failure.problem.code).toBe(COMPLETE_PROBLEM.code);
  expect(failure.problem.severity).toBe('WARNING');
  expect(failure.problem.fieldErrors).toHaveLength(1);
  expect(failure.problem.fieldErrors[0]?.field).toBe('accountId');
  expect(failure.correlationId).toBe(COMPLETE_PROBLEM.correlationId);
}

/** Asserts a body carrying only the three formerly-probed members is NOT trusted as a document. */
async function refusesAPartialProblemDocument(): Promise<void> {
  // WHY : this is the B9-001 regression guard. The superseded guard checked the status, the
  //       correlation identifier and the presence of an array, so this body was classified as a
  //       service's own refusal and its absent code, severity and message reached a screen as
  //       undefined values. The synthesised shape is the honest answer: something responded, and what
  //       it sent is not a problem document.
  plannedBody = PARTIAL_PROBLEM;

  const failure = await refusedRequest();

  expect(failure.kind).toBe('RESPONSE');
  expect(failure.problem.code).toBe(CODE_UNEXPECTED_BODY);
  expect(failure.problem.severity).toBe('WARNING');
  expect(failure.problem.fieldErrors).toStrictEqual([]);
}

/** Asserts a document whose severity is outside its declared domain is not trusted. */
async function refusesAnUndeclaredSeverity(): Promise<void> {
  plannedBody = { ...COMPLETE_PROBLEM, severity: 'FATAL' };

  const failure = await refusedRequest();

  expect(failure.kind).toBe('RESPONSE');
  expect(failure.problem.code).toBe(CODE_UNEXPECTED_BODY);
}

/** Asserts a document is not trusted when one field-error entry is not a field error. */
async function refusesAMalformedFieldError(): Promise<void> {
  // WHY : the array is the whole field-marking mechanism on a 400, so an entry that names no field
  //       would mark a form position with nothing in it. Refusing the document is what keeps a screen
  //       from binding a mark to an entry it cannot render.
  plannedBody = { ...COMPLETE_PROBLEM, fieldErrors: [{ field: 'accountId', state: 'NOT_OK' }] };

  const failure = await refusedRequest();

  expect(failure.kind).toBe('RESPONSE');
  expect(failure.problem.code).toBe(CODE_UNEXPECTED_BODY);
}

/** Asserts an HTML page from a proxy is classified as a response with no document. */
async function refusesANonJsonBody(): Promise<void> {
  plannedBody = '<html><body>502 Bad Gateway</body></html>';

  const failure = await refusedRequest();

  expect(failure.kind).toBe('RESPONSE');
  expect(failure.problem.code).toBe(CODE_UNEXPECTED_BODY);
  expect(failure.problem.timestamp).toBe('');
}

/** Groups the assertions that fix which bodies may be read as problem documents. */
function problemDocumentContract(): void {
  beforeEach(stubProblemFixture);
  afterEach(restoreProblemFixture);
  it('carries a complete problem document verbatim', carriesACompleteProblemDocumentVerbatim);
  it('refuses a body carrying only the formerly-probed members', refusesAPartialProblemDocument);
  it('refuses a document whose severity is outside its domain', refusesAnUndeclaredSeverity);
  it('refuses a document carrying a malformed field error', refusesAMalformedFieldError);
  it(
    'refuses a non-JSON body from something between the browser and the service',
    refusesANonJsonBody,
  );
}

describe('problem document contract', problemDocumentContract);

/**
 * Records every re-authentication signal one case observed.
 *
 * Assumptions: held at module scope and emptied per case, because the subscription is registered
 * inside each case and the signal arrives asynchronously — a local would be captured by the listener
 * and read by the assertion, which works, but this keeps the reset in one documented place.
 */
let signalled: ApiRequestError[] = [];

/**
 * Records one re-authentication signal.
 * @param {ApiRequestError} failure - The normalised 401 that invalidated the session.
 * @returns {void} Nothing; the signal is appended to the observed list.
 */
function recordSignal(failure: ApiRequestError): void {
  signalled.push(failure);
}

/** Plans a refusal, installs a bearer, and empties the observed signals. */
function stubSessionFixture(): void {
  stubBuildConfiguration();
  setAccessToken(null);
  signalled = [];
  plannedStatus = UNAUTHORIZED;
  plannedBody = { ...COMPLETE_PROBLEM, status: UNAUTHORIZED, code: 'CARDDEMO-0401' };
  setAccessToken(HELD_TOKEN);
}

/**
 * Restores the environment and discards the bearer and the observed signals.
 *
 * Assumptions: ⚠️ the bearer is discarded explicitly, where this used to clear `sessionStorage`. It is a
 * module variable now, so it outlives every case in this file rather than every file in this worker —
 * one case's bearer left in place would attach itself to the requests of every case after it.
 * @returns {void} Nothing; no bearer is held and no signal is recorded.
 */
function restoreSessionFixture(): void {
  restoreBuildConfiguration();
  setAccessToken(null);
  signalled = [];
}

/** Asserts a 401 on a request that carried the stored token discards it and signals. */
async function discardsTheSessionOnUnauthorized(): Promise<void> {
  const unsubscribe = subscribeToAuthenticationRequired(recordSignal);
  try {
    const failure = await refusedRequest();
    await settleNotifications();

    expect(failure.status).toBe(UNAUTHORIZED);
    expect(
      await transmitWith(),
      'the discarded bearer must not reach the next request',
    ).toBeUndefined();
    expect(signalled).toHaveLength(1);
    expect(signalled[0]?.status).toBe(UNAUTHORIZED);
  } finally {
    unsubscribe();
  }
}

/** Asserts a 403 leaves the session alone and tells nobody. */
async function keepsTheSessionOnForbidden(): Promise<void> {
  // WHY : a 403 is a valid token refused one route, which is what an ordinary operator reaching an
  //       administrative screen receives. Discarding the session for it would sign out a caller who is
  //       correctly signed in, and they would sign straight back in and meet the same refusal.
  plannedStatus = FORBIDDEN;
  plannedBody = { ...COMPLETE_PROBLEM, status: FORBIDDEN, code: 'CARDDEMO-0403' };

  const unsubscribe = subscribeToAuthenticationRequired(recordSignal);
  try {
    const failure = await refusedRequest();
    await settleNotifications();

    expect(failure.status).toBe(FORBIDDEN);
    expect(await transmitWith(), 'the retained bearer must still reach the next request').toBe(
      `Bearer ${HELD_TOKEN}`,
    );
    expect(signalled).toHaveLength(0);
  } finally {
    unsubscribe();
  }
}

/** Asserts a 401 on a request that carried no token is not read as an expired session. */
async function keepsSilentWhenNoTokenWasSent(): Promise<void> {
  // WHY : the sign-on contract publishes 401 for a rejected credential -- one status deliberately
  //       covering both a wrong password and an unknown identifier -- so a signal here would report
  //       "signed out" to an operator mistyping a password at the sign-on screen.
  setAccessToken(null);

  const unsubscribe = subscribeToAuthenticationRequired(recordSignal);
  try {
    await refusedRequest();
    await settleNotifications();

    expect(signalled).toHaveLength(0);
  } finally {
    unsubscribe();
  }
}

/** Groups the assertions that fix what a refusal does to the session this tab holds. */
function sessionInvalidationContract(): void {
  beforeEach(stubSessionFixture);
  afterEach(restoreSessionFixture);
  it(
    'discards the stored token and signals on an unauthorized refusal',
    discardsTheSessionOnUnauthorized,
  );
  it('keeps the session and stays silent on a forbidden refusal', keepsTheSessionOnForbidden);
  it('stays silent when the refused request carried no token', keepsSilentWhenNoTokenWasSent);
}

describe('session invalidation contract', sessionInvalidationContract);

/** The bearer value stored for the cases that assert what the interceptor attaches. */
const HELD_TOKEN = 'a-held-access-token';

let authorizationSent: string | undefined;

/**
 * Records the `Authorization` header of one dispatched request and answers it locally.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success response carrying the same configuration back.
 */
async function recordAuthorizationAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  authorizationSent = headerOf(config, 'Authorization');
  return Promise.resolve({
    data: {},
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  } as AxiosResponse);
}

/**
 * Reads one header off a dispatched configuration without asserting the bag's shape.
 *
 * Assumptions: narrowed through an explicitly `unknown` local because Axios types its header
 * collection with an index signature returning an unchecked value, which the rule set refuses; a
 * non-string value therefore reads as absent rather than as a stringified object.
 * @param {AxiosRequestConfig} config - The dispatched configuration.
 * @param {string} name - Header name as this module spells it.
 * @returns {string | undefined} The header's value, or nothing when it was not sent.
 */
function headerOf(config: AxiosRequestConfig, name: string): string | undefined {
  const headers: unknown = config.headers;
  if (typeof headers !== 'object' || headers === null) {
    return undefined;
  }
  const value: unknown = (headers as Record<string, unknown>)[name];
  return typeof value === 'string' ? value : undefined;
}

/**
 * Dispatches one request through the real client with the given configuration.
 * @param {AxiosRequestConfig} [configuration] - Per-request configuration, or nothing for an ordinary
 *   authenticated request.
 * @returns {Promise<string | undefined>} The `Authorization` header that request transmitted, or
 *   nothing when it transmitted none.
 */
async function transmitWith(configuration?: AxiosRequestConfig): Promise<string | undefined> {
  authorizationSent = undefined;
  const client = getApiClient();
  client.defaults.adapter = recordAuthorizationAdapter;
  await client.post('/api/v1/auth/signon', {}, configuration);
  return authorizationSent;
}

/** Installs a bearer and the build configuration for one case. */
function stubHeldToken(): void {
  stubBuildConfiguration();
  setAccessToken(HELD_TOKEN);
}

/** Discards the bearer and the build configuration so no later case inherits either. */
function restoreHeldToken(): void {
  restoreBuildConfiguration();
  setAccessToken(null);
}

/** Asserts an ordinary request carries the stored bearer. */
async function attachesTheStoredBearerByDefault(): Promise<void> {
  expect(await transmitWith()).toBe(`Bearer ${HELD_TOKEN}`);
}

/** Asserts a request marked as carrying no session transmits no bearer at all. */
async function omitsTheStoredBearerWhenSuppressed(): Promise<void> {
  // WHY : the three token exchanges are declared `security: []` by their contract, and the
  //       resource-server filter validates a presented credential before the permit-all rule for
  //       those paths is reached -- so a lapsed token travelling on a sign-on could refuse the very
  //       exchange that replaces it. This case is what keeps the suppression from being removed as
  //       redundant, since nothing else about the request would look different.
  expect(await transmitWith(WITHOUT_STORED_SESSION)).toBeUndefined();
}

/** Asserts the suppression is per request, so the next ordinary request is unaffected. */
async function suppressionDoesNotOutlastTheRequest(): Promise<void> {
  await transmitWith(WITHOUT_STORED_SESSION);

  expect(await transmitWith()).toBe(`Bearer ${HELD_TOKEN}`);
}

/** Asserts a suppressed request still carries a correlation identifier. */
async function stillCorrelatesASuppressedRequest(): Promise<void> {
  // WHY : the identifier carries no credential and confers no authority, so suppressing the session
  //       must not suppress traceability -- a sign-on that cannot be found in the logs is the one
  //       failure an operator reports most often.
  authorizationSent = undefined;
  const client = getApiClient();
  client.defaults.adapter = captureAdapter;
  await client.post('/api/v1/auth/signon', {}, WITHOUT_STORED_SESSION);

  expect(transmitted.at(-1)).toMatch(MINTED_SHAPE);
}

/** Groups the assertions that fix which requests carry the stored session. */
function storedSessionAttachmentContract(): void {
  beforeEach(stubHeldToken);
  afterEach(restoreHeldToken);
  it('attaches the stored bearer to an ordinary request', attachesTheStoredBearerByDefault);
  it(
    'omits the stored bearer on a request declared without a session',
    omitsTheStoredBearerWhenSuppressed,
  );
  it('suppresses for one request only', suppressionDoesNotOutlastTheRequest);
  it('still correlates a request dispatched without a session', stillCorrelatesASuppressedRequest);
}

describe('stored session attachment contract', storedSessionAttachmentContract);

/**
 * Builds the client once against one configured base URL and reports what the factory did.
 *
 * Assumptions: the memoised instance is discarded first, because the factory validates the base URL
 * exactly once and hands back the cached client on every later call — so a case that did not reset
 * would assert against whichever URL a previous case happened to configure.
 * @param {string} baseUrl - The value the runtime document is taken to have published.
 * @returns {Error | undefined} The error the factory threw, or `undefined` when it built a client.
 */
function buildAgainst(baseUrl: string): Error | undefined {
  resetApiClient();
  vi.stubEnv('VITE_API_BASE_URL', baseUrl);
  try {
    getApiClient();
    return undefined;
  } catch (refusal) {
    return refusal instanceof Error ? refusal : new Error(String(refusal));
  }
}

/** Asserts a loopback API over plain HTTP is admitted, by name and by both literal addresses. */
function admitsPlainHttpOnlyOnLoopback(): void {
  // Assumptions: the three spellings are asserted separately rather than as one representative
  //   case. A dual-stack host resolves `localhost` to the IPv6 loopback, and `URL` keeps the
  //   brackets on that hostname, so a guard written for the IPv4 address alone admits a developer's
  //   configuration on one machine and refuses the identical configuration on another.
  expect(buildAgainst('http://localhost:8000/api/v1')).toBeUndefined();
  expect(buildAgainst('http://127.0.0.1:8000/api/v1')).toBeUndefined();
  expect(buildAgainst('http://[::1]:8000/api/v1')).toBeUndefined();
}

/** Asserts the loopback exemption does not depend on how the bundle was built. */
function admitsLoopbackIndependentlyOfTheBuildMode(): void {
  // Refactoring Rationale: this case exists because the exemption USED to be conditioned on
  //   `import.meta.env.DEV`, which a production build inlines as `false` — so the whole branch was
  //   folded out of the shipped asset and the built SPA refused the local edge's published
  //   `http://localhost:8000/api/v1`, throwing inside this factory before any request was
  //   dispatched. A test cannot read the folded artifact, so it asserts the property that folding
  //   destroyed instead: the decision is made from the resolved host, and stubbing the build mode
  //   to production leaves the outcome unchanged.
  vi.stubEnv('DEV', false);
  vi.stubEnv('PROD', true);
  expect(buildAgainst('http://localhost:8000/api/v1')).toBeUndefined();
}

/** Asserts every host other than loopback still has to be reached over HTTPS. */
function refusesPlainHttpOnAnyOtherHost(): void {
  // Assumptions: a private address and a resolvable public name are both asserted, because the
  //   requirement is not about reachability from the internet — a body carrying a primary account
  //   number crosses a network interface in both cases, which is the whole of what the scheme
  //   protects.
  const named = buildAgainst('http://api.carddemo.example/api/v1');
  const private4 = buildAgainst('http://10.0.3.14:8080/api/v1');
  const lookalike = buildAgainst('http://localhost.attacker.example/api/v1');

  expect(named?.message).toContain('only HTTPS is accepted');
  expect(private4?.message).toContain('only HTTPS is accepted');
  // Assumptions: the look-alike host is the reason the check compares the WHOLE hostname rather
  //   than testing for a `localhost` prefix or substring. `localhost.attacker.example` resolves
  //   wherever its owner points it, so a substring test would exempt an attacker-controlled host
  //   from the scheme requirement.
  expect(lookalike?.message).toContain('only HTTPS is accepted');
}

/** Asserts an HTTPS API is admitted on any host, which is the deployed shape. */
function admitsHttpsAnywhere(): void {
  expect(buildAgainst('https://api.carddemo.example/api/v1')).toBeUndefined();
  expect(buildAgainst('https://localhost:8443/api/v1')).toBeUndefined();
}

/**
 * Restores the stubbed environment and discards the memoised client after each scheme case.
 *
 * Assumptions: the client is reset as well as the environment, because the factory caches its
 * instance — leaving one built against a loopback URL would make a later file's first request
 * travel to this file's configuration rather than to its own.
 */
function restoreConfigurationAndClient(): void {
  restoreBuildConfiguration();
  resetApiClient();
}

/** Groups the assertions that fix which API base URLs this client will build against. */
function apiBaseUrlSchemeContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreConfigurationAndClient);
  it('admits plain HTTP on loopback, by name and by both addresses', admitsPlainHttpOnlyOnLoopback);
  it('admits loopback whatever the build mode says', admitsLoopbackIndependentlyOfTheBuildMode);
  it('refuses plain HTTP on every other host', refusesPlainHttpOnAnyOtherHost);
  it('admits HTTPS on any host', admitsHttpsAnywhere);
}

describe('API base URL scheme contract', apiBaseUrlSchemeContract);
