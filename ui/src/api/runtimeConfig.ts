/**
 * @file Runtime configuration for the single-page application.
 *
 * WHY this module exists at all — Refactoring Rationale: the API base URL used to be read only
 * from `import.meta.env.VITE_API_BASE_URL`, which Vite inlines at **build** time. The deployment
 * workflow builds this bundle before it applies the environment, so the API Gateway endpoint does
 * not exist yet when the bundle is produced: the variable was necessarily empty, and
 * `apiBaseUrl()` threw on the first render, which is a blank screen for every visitor. Reordering
 * the workflow so the build follows the apply would fix that one ordering but would keep a worse
 * property — the bundle would be environment-specific, so `dev` and `prod` could never share a
 * reviewed artifact and every environment would need its own rebuild of identical source.
 *
 * The resolution is to keep the bundle environment-independent and to fetch its configuration at
 * runtime from a document published beside it. The deployment writes `config.json` into the same
 * bucket after the endpoint is known, so one immutable bundle is configured per environment by a
 * file it reads at start-up.
 *
 * Alternatives Considered: injecting the value into `index.html` as a `window.__CONFIG__` global at
 * publication time. Rejected because it mutates a built artifact after review — the document that
 * ships would differ from the document that was built — and because a global is reachable from any
 * script on the page, whereas this module keeps the value behind a function.
 *
 * Assumptions: `config.json` is same-origin, so it is fetched under the content-security policy's
 * `default-src 'self'` without needing any `connect-src` entry. Only the API calls need one.
 */

import { recordServerDate } from './serverClock';

/** Same-origin path the deployment publishes the configuration document to. */
const RUNTIME_CONFIG_PATH = '/config.json';

/**
 * Shape of the published configuration document.
 *
 * Assumptions: the API base URL is the only required member. It is deliberately not optional: a
 * document that omitted it would leave the application unable to call anything, and failing at
 * start-up with a stated reason is more useful than failing later on whichever screen happens to
 * issue the first request.
 */
export interface RuntimeConfig {
  /**
   * Absolute API base URL, including the `/api/v1` prefix every published operation sits under.
   */
  readonly apiBaseUrl: string;
}

let loaded: RuntimeConfig | undefined;

/**
 * Returns the API base URL supplied at runtime, if the configuration document has been loaded.
 * @returns {string | undefined} The configured base URL, or `undefined` when no document has been
 *   loaded — which is the normal case for a local development server and for unit tests.
 */
export function runtimeApiBaseUrl(): string | undefined {
  return loaded?.apiBaseUrl;
}

/**
 * Discards any loaded configuration.
 *
 * Assumptions: this exists for tests, which need each case to start from a known state because the
 * loaded value is module-level and would otherwise leak between them.
 */
export function resetRuntimeConfig(): void {
  loaded = undefined;
}

/**
 * Validates one candidate configuration document.
 * @param {unknown} candidate - Parsed JSON body of the configuration document.
 * @returns {RuntimeConfig} The validated configuration.
 * @throws {Error} If the document is not an object or its API base URL is missing or not a string.
 */
function validate(candidate: unknown): RuntimeConfig {
  if (typeof candidate !== 'object' || candidate === null) {
    throw new Error('CardDemo runtime configuration is invalid; the document is not an object.');
  }
  const apiBaseUrl: unknown = (candidate as Record<string, unknown>).apiBaseUrl;
  if (typeof apiBaseUrl !== 'string' || apiBaseUrl.trim().length === 0) {
    throw new Error(
      'CardDemo runtime configuration is invalid; apiBaseUrl must be a non-empty string.',
    );
  }
  return { apiBaseUrl: apiBaseUrl.trim() };
}

/**
 * Loads the published configuration document, if one is present.
 *
 * Trade-offs: a missing document resolves rather than rejecting, and the caller then falls back to
 * the build-time variable. That is what keeps `npm run dev` working with a local `.env` while a
 * deployed environment is configured by the published document. A document that is present but
 * malformed **does** reject, because that is a deployment error rather than an absence, and
 * silently ignoring it would send every request to whatever stale value happened to be compiled in.
 * @returns {Promise<RuntimeConfig | undefined>} The loaded configuration, or `undefined` when no
 *   document is published at the expected path.
 * @throws {Error} If a document is published but cannot be parsed or fails validation.
 */
export async function loadRuntimeConfig(): Promise<RuntimeConfig | undefined> {
  let response: Response;
  try {
    // WHY : Assumptions: `cache: 'no-store'` because this document is the one artifact that must
    //       not be served from a stale cache. The bundle is content-hashed and may be cached
    //       indefinitely; its configuration is rewritten in place on every deployment, so a cached
    //       copy would point a freshly published bundle at the previous environment's endpoint.
    response = await fetch(RUNTIME_CONFIG_PATH, { cache: 'no-store' });
  } catch {
    return undefined;
  }
  // WHY : Assumptions: the server clock is anchored from THIS response, before any status check,
  //       because the header band needs a server instant on the very first paint and this fetch is
  //       the only server contact `ui/src/main.tsx` awaits before rendering. The sign-on screen
  //       paints before any API call, so without this it alone would fall back to the browser clock.
  //       Trade-offs: anchored even for a 404 or an error status. A response that says the document
  //       is absent still came from the server and still carries its `Date`, so discarding it would
  //       throw away a usable anchor for a reason unrelated to timekeeping -- and the absent-document
  //       case is the normal one for a local development server.
  recordServerDate(response.headers.get('Date'));
  if (response.status === 404) {
    return undefined;
  }
  if (!response.ok) {
    throw new Error(
      `CardDemo runtime configuration could not be read; the request returned ${String(response.status)}.`,
    );
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error('CardDemo runtime configuration is invalid; the document is not JSON.');
  }
  loaded = validate(body);
  return loaded;
}
