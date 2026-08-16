/**
 * @file Unit tests for the runtime configuration loader in `ui/src/api/runtimeConfig.ts`.
 *
 * Purpose
 * -------
 * Pin the three outcomes the start-up sequence depends on: a published document resolves and its
 * base URL is adopted, an ABSENT document is not a failure and the build-time variable is used
 * instead, and a malformed value is refused rather than adopted. `ui/src/main.tsx` awaits this
 * loader before mounting, so an error in any of the three would misaddress every request the
 * application makes for the life of the tab.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadRuntimeConfig, resetRuntimeConfig, runtimeApiBaseUrl } from './runtimeConfig';

const PUBLISHED_BASE_URL = 'https://api.example.test/api/v1';

/**
 * Installs a `fetch` that answers the configuration request with a chosen response.
 * @param {Response} response - The response the stub resolves with.
 */
function stubFetch(response: Response): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      /**
       * Answers any request with the configured response.
       * @returns {Promise<Response>} The configured response.
       */
      () => Promise.resolve(response),
    ),
  );
}

/**
 * Builds a JSON response carrying the given body.
 * @param {unknown} body - Body to serialize.
 * @param {number} status - HTTP status to report.
 * @returns {Response} A response the loader can read.
 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * Discards any configuration a previous case adopted, so each case starts unconfigured.
 * @returns {void} Nothing; the module-level cache is cleared in place.
 */
function clearAdoptedConfiguration(): void {
  resetRuntimeConfig();
}

/**
 * Removes the stubbed `fetch` and the adopted configuration after each case.
 *
 * Assumptions: both are undone rather than only the stub. A leaked configuration would make a later
 * case pass on a value it never published, which is the failure mode hardest to attribute.
 * @returns {void} Nothing; both are cleared in place.
 */
function releaseStubsAndConfiguration(): void {
  vi.unstubAllGlobals();
  resetRuntimeConfig();
}

/**
 * A published document's base URL is adopted and then reported by the accessor.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function adoptsAPublishedApiBaseUrl(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: PUBLISHED_BASE_URL }));

  const config = await loadRuntimeConfig();

  expect(config?.apiBaseUrl).toBe(PUBLISHED_BASE_URL);
  expect(runtimeApiBaseUrl()).toBe(PUBLISHED_BASE_URL);
}

/**
 * An absent document resolves to no configuration rather than rejecting.
 *
 * Assumptions: this is the case that keeps `npm run dev` working. A development server publishes
 * no configuration document, and the client must then fall back to its build-time variable rather
 * than failing -- so absence has to resolve, not reject.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function treatsAnAbsentDocumentAsNoConfiguration(): Promise<void> {
  stubFetch(new Response('', { status: 404 }));

  await expect(loadRuntimeConfig()).resolves.toBeUndefined();
  expect(runtimeApiBaseUrl()).toBeUndefined();
}

/**
 * A transport failure resolves to no configuration, exactly as an absent document does.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function treatsAnUnreachableDocumentAsNoConfiguration(): Promise<void> {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      /**
       * Rejects as a network failure would.
       * @returns {Promise<Response>} A rejected promise.
       */
      () => Promise.reject(new Error('offline')),
    ),
  );

  await expect(loadRuntimeConfig()).resolves.toBeUndefined();
}

/**
 * A document present but missing the base URL rejects, naming the absent member.
 *
 * Assumptions: a present-but-malformed document REJECTS while an absent one resolves, and the
 * asymmetry is deliberate. A document that exists is a deployment artifact, so a fault in it is a
 * deployment error worth surfacing; ignoring it would send every request to whatever stale value
 * was compiled into the bundle.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rejectsADocumentThatOmitsTheApiBaseUrl(): Promise<void> {
  stubFetch(jsonResponse({ somethingElse: true }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/apiBaseUrl/u);
  expect(runtimeApiBaseUrl()).toBeUndefined();
}

/**
 * A blank base URL is refused, so whitespace cannot pass as a configured value.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function rejectsABlankApiBaseUrl(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: '   ' }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/apiBaseUrl/u);
}

/**
 * A document that is not JSON rejects, naming the parse failure rather than the body.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function rejectsADocumentThatIsNotJson(): Promise<void> {
  stubFetch(new Response('not json', { status: 200 }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/not JSON/u);
}

/**
 * A server error rejects rather than being read as an absent document.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function rejectsAServerErrorRatherThanTreatingItAsAbsence(): Promise<void> {
  stubFetch(new Response('', { status: 500 }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/500/u);
}

/**
 * Registers the eight runtime-configuration cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function runtimeConfigurationCases(): void {
  it('adopts a published API base URL', adoptsAPublishedApiBaseUrl);
  it(
    'treats an absent document as no configuration rather than a failure',
    treatsAnAbsentDocumentAsNoConfiguration,
  );
  it(
    'treats an unreachable document as no configuration',
    treatsAnUnreachableDocumentAsNoConfiguration,
  );
  it('rejects a document that omits the API base URL', rejectsADocumentThatOmitsTheApiBaseUrl);
  it('rejects a document whose API base URL is blank', rejectsABlankApiBaseUrl);
  it('rejects a document that is not JSON', rejectsADocumentThatIsNotJson);
  it(
    'rejects a server error rather than treating it as absence',
    rejectsAServerErrorRatherThanTreatingItAsAbsence,
  );
}

beforeEach(clearAdoptedConfiguration);

afterEach(releaseStubsAndConfiguration);

describe('runtime configuration', runtimeConfigurationCases);
