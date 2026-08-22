/**
 * @file Unit tests for the runtime configuration loader in `ui/src/api/runtimeConfig.ts`.
 *
 * Purpose
 * -------
 * Pin the outcomes the start-up sequence depends on. Three concern the document itself: a published
 * document resolves and its base URL is adopted, an ABSENT document is not a failure and the
 * build-time variable is used instead, and a malformed value is refused rather than adopted.
 * `ui/src/main.tsx` awaits this loader before mounting, so an error in any of them would misaddress
 * every request the application makes for the life of the tab.
 *
 * The rest pin the SHAPE rules and the precedence between the two sources, and they are here rather
 * than in `client.test.ts` because the rules moved here: one validator serves the document, the
 * build-time variable and the start-up check, so a shape admitted in one place cannot be refused in
 * another. Each accepted shape and each refusal has a case below, because the refusals are what
 * stand between a deployment mistake and a screen that reports it as a rejected credential.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  loadRuntimeConfig,
  resetRuntimeConfig,
  resolvedApiBaseUrl,
  runtimeApiBaseUrl,
} from './runtimeConfig';

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
  // Assumptions: the environment is unstubbed as well as the globals, because the resolution cases
  //   below stub `VITE_API_BASE_URL` and a leaked value would make a later case resolve against a
  //   base URL it never configured -- the same attribution failure the configuration reset avoids.
  vi.unstubAllEnvs();
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
 * Registers the document-loading cases.
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

/**
 * A same-origin absolute path is adopted, which is the shape used when no cross-origin call is made.
 *
 * Assumptions: this shape exists so a deployment publishing the API under the bundle's own origin
 * needs no `connect-src` entry at all -- the request is covered by `default-src 'self'`. It is
 * asserted because an absolute-URL-only validator would refuse the most locked-down configuration
 * this application supports.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function adoptsASameOriginApiPath(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: '/api/v1' }));

  await expect(loadRuntimeConfig()).resolves.toStrictEqual({ apiBaseUrl: '/api/v1' });
}

/**
 * A trailing slash is normalized away rather than refused.
 *
 * Assumptions: this document is not always written by `ui/docker-entrypoint.sh` -- the CloudFront
 * path's document is generated from Terraform outputs by the deploy runbook -- so a cosmetic
 * difference must not take an environment down. The entrypoint refuses one outright because it
 * PRODUCES the canonical document; this loader also reads documents it did not write.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function normalizesATrailingSlash(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: `${PUBLISHED_BASE_URL}/` }));

  await expect(loadRuntimeConfig()).resolves.toStrictEqual({ apiBaseUrl: PUBLISHED_BASE_URL });
}

/**
 * A base URL without the operation prefix is refused at adoption.
 *
 * Assumptions: this is the refusal that matters most, because the value looks correct. Every
 * operation is addressed relatively while the gateway publishes its route keys under `/api/v1`, so
 * without the prefix each request answers 404 -- and a 404 reads on a screen as missing data rather
 * than as a misconfigured deployment.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function rejectsABaseUrlWithoutTheOperationPrefix(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: 'https://api.example.test' }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/apiBaseUrl.*\/api\/v1/su);
}

/**
 * Plain HTTP is refused on any host that is not loopback.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function rejectsPlainHttpOnANonLoopbackHost(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: 'http://api.example.test/api/v1' }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/only HTTPS is accepted/u);
}

/**
 * A protocol-relative value is refused rather than read as a same-origin path.
 *
 * Assumptions: `//api.example.test/api/v1` begins with a slash, so a validator testing only for a
 * leading slash would admit it -- and the browser would then resolve it to an origin nothing
 * validated and no `connect-src` entry covers, which is a cross-origin call the policy would block
 * after the configuration had already been accepted.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function rejectsAProtocolRelativeBaseUrl(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: '//api.example.test/api/v1' }));

  await expect(loadRuntimeConfig()).rejects.toThrow(/apiBaseUrl/u);
}

/**
 * A query or a fragment is refused, because neither can survive as part of a base URL.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rejectsAQueryOrFragment(): Promise<void> {
  stubFetch(jsonResponse({ apiBaseUrl: 'https://api.example.test/api/v1?stage=dev' }));
  await expect(loadRuntimeConfig()).rejects.toThrow(/query or fragment/u);

  resetRuntimeConfig();
  stubFetch(jsonResponse({ apiBaseUrl: 'https://api.example.test/api/v1#top' }));
  await expect(loadRuntimeConfig()).rejects.toThrow(/query or fragment/u);
}

/**
 * Registers the shape cases the document is held to.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function apiBaseUrlShapeCases(): void {
  it('adopts a same-origin API path', adoptsASameOriginApiPath);
  it('normalizes a trailing slash away', normalizesATrailingSlash);
  it(
    'rejects a base URL without the /api/v1 operation prefix',
    rejectsABaseUrlWithoutTheOperationPrefix,
  );
  it('rejects plain HTTP on a host that is not loopback', rejectsPlainHttpOnANonLoopbackHost);
  it('rejects a protocol-relative base URL', rejectsAProtocolRelativeBaseUrl);
  it('rejects a base URL carrying a query or a fragment', rejectsAQueryOrFragment);
}

/**
 * Invokes the resolution so an assertion can inspect what it refuses with.
 *
 * Assumptions: a thunk FACTORY rather than an inline arrow at each call site, matching
 * `ui/src/routes/cards.test.ts`. `ui/eslint.config.js` selects a function expression in every
 * position, so an inline `() => resolvedApiBaseUrl()` owes its own documentation block -- and a block
 * written above an argument is moved by Prettier onto the preceding token, detaching it from the
 * function it documents.
 * @returns {() => string} A thunk that runs the resolution and returns the base URL it accepted.
 */
function resolving(): () => string {
  return (
    /**
     * Runs the resolution against whatever the case has configured.
     * @returns {string} The accepted API base URL.
     */
    () => resolvedApiBaseUrl()
  );
}

/**
 * A published document outranks the build-time variable.
 *
 * Assumptions: the order is the point of the whole mechanism. The variable is inlined when the
 * bundle is built, which is before the environment that creates the API endpoint exists, so a
 * deployed build's compiled value is either empty or stale.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function prefersThePublishedDocumentOverTheBuildVariable(): Promise<void> {
  vi.stubEnv('VITE_API_BASE_URL', 'https://compiled.example.test/api/v1');
  stubFetch(jsonResponse({ apiBaseUrl: PUBLISHED_BASE_URL }));

  await loadRuntimeConfig();

  expect(resolvedApiBaseUrl()).toBe(PUBLISHED_BASE_URL);
}

/**
 * With no document published, the build-time variable is used, which is the development server.
 * @returns {void} Nothing; the assertion runs inline.
 */
function fallsBackToTheBuildVariable(): void {
  vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080/api/v1');

  expect(resolvedApiBaseUrl()).toBe('http://localhost:8080/api/v1');
}

/**
 * With neither source supplying a value, resolution refuses rather than returning an empty base.
 *
 * Assumptions: this is the exact state the container image was in before it published a document --
 * the bundle carries no compiled variable, because it is built before any environment exists. The
 * refusal is what `ui/src/main.tsx` turns into a start-up failure instead of mounting an application
 * whose every request throws inside the client factory.
 * @returns {void} Nothing; the assertion runs inline.
 */
function refusesWhenNeitherSourceSuppliesABaseUrl(): void {
  vi.stubEnv('VITE_API_BASE_URL', '');

  expect(resolving()).toThrow(/configuration is unavailable/u);
}

/**
 * A build-time variable is held to the same shape rules as a published document.
 * @returns {void} Nothing; the assertion runs inline.
 */
function refusesABuildVariableOfTheWrongShape(): void {
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test');

  expect(resolving()).toThrow(/VITE_API_BASE_URL.*\/api\/v1/su);
}

/**
 * Registers the cases that fix which source wins and what happens when neither does.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function apiBaseUrlResolutionCases(): void {
  it(
    'prefers the published document over the build-time variable',
    prefersThePublishedDocumentOverTheBuildVariable,
  );
  it(
    'falls back to the build-time variable when no document is published',
    fallsBackToTheBuildVariable,
  );
  it('refuses when neither source supplies a base URL', refusesWhenNeitherSourceSuppliesABaseUrl);
  it('refuses a build-time variable of the wrong shape', refusesABuildVariableOfTheWrongShape);
}

beforeEach(clearAdoptedConfiguration);

afterEach(releaseStubsAndConfiguration);

describe('runtime configuration', runtimeConfigurationCases);

describe('API base URL shape', apiBaseUrlShapeCases);

describe('API base URL resolution', apiBaseUrlResolutionCases);
