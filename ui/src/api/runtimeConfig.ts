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
 *
 * WHY the base-URL validator lives here rather than in `client.ts` — Refactoring Rationale: the
 * shape rules used to be written inside `apiBaseUrl()` in `ui/src/api/client.ts`, and the document
 * loader below accepted any non-blank string. That is two validators for one value, and they
 * disagreed in both directions: the loader admitted a document the client would then refuse on the
 * first request, and neither of them checked the `/api/v1` prefix that every published operation
 * sits under, so a base URL that was one path segment short produced a 404 on every call while
 * looking correct in the client and in the gateway when each was read alone. One exported validator
 * removes the possibility of that divergence: the document is checked when it is adopted,
 * `ui/src/main.tsx` checks the FULLY RESOLVED value before it mounts anything, and the client
 * consumes an already-validated value. See {@link normalizeApiBaseUrl} for the accepted shapes and
 * {@link resolvedApiBaseUrl} for the precedence between the two sources.
 */

import { recordServerDate } from './serverClock';

/** Same-origin path the deployment publishes the configuration document to. */
const RUNTIME_CONFIG_PATH = '/config.json';

/**
 * Path suffix every accepted API base URL ends with.
 *
 * Assumptions: every operation this application calls is addressed RELATIVELY — `/cards`,
 * `/auth/signon` — while the gateway publishes its route keys under `/api/v1`, so the prefix has to
 * be part of the base. It is required rather than appended here on the caller's behalf, because
 * appending it would silently rewrite an operator's stated configuration and would then produce
 * `/api/v1/api/v1` for the value that was already correct.
 */
export const API_BASE_URL_REQUIRED_SUFFIX = '/api/v1';

/**
 * Hostnames the plain-HTTP exemption admits, which are the loopback spellings and nothing else.
 *
 * Assumptions: the bracketed IPv6 form is listed as well as the bare one, because `URL` normalises
 * `http://[::1]:8000` to a hostname of `[::1]` with the brackets retained — so a list carrying only
 * `::1` fails to admit the address a dual-stack host resolves `localhost` to.
 */
const LOOPBACK_HOSTNAMES: readonly string[] = ['localhost', '127.0.0.1', '::1', '[::1]'];

/**
 * Reports whether a candidate is the same-origin absolute-path form of an API base URL.
 *
 * Assumptions: a protocol-relative value such as `//host/api/v1` is deliberately NOT this form.
 * It begins with a slash, so a naive test would admit it, and the browser would then resolve it to
 * an origin nothing here has validated and no `connect-src` entry covers.
 * @param {string} candidate - Trimmed configuration value, with any trailing slash already removed.
 * @returns {boolean} True when the value is a same-origin absolute path.
 */
function isSameOriginPath(candidate: string): boolean {
  return candidate.startsWith('/') && !candidate.startsWith('//');
}

/**
 * Validates and normalizes one API base URL, whatever source supplied it.
 *
 * Two shapes are accepted and everything else is refused with a stated reason:
 *
 * 1. an absolute URL — HTTPS on any host, or plain HTTP on a loopback host only — whose path ends
 *    with {@link API_BASE_URL_REQUIRED_SUFFIX}, which is the deployed shape and the shape a
 *    developer's `.env` normally carries; and
 * 2. a same-origin absolute path ending with the same suffix, which is the shape to use when the
 *    API is published under the same origin as the bundle and no cross-origin `connect-src` entry
 *    is wanted at all.
 *
 * Assumptions: loopback is the only plain-HTTP host admitted, and admitting it costs nothing the
 * HTTPS requirement was protecting — traffic to a loopback address never reaches a network
 * interface, and a browser cannot be induced to resolve these names elsewhere. Every other host,
 * including a private address inside a deployment's own network, still requires HTTPS, which is what
 * carries the encryption-in-transit constraint for a body bearing a primary account number.
 *
 * Assumptions: a trailing slash is stripped rather than refused, and the asymmetry with
 * `ui/docker-entrypoint.sh` — which refuses one outright — is deliberate. The entrypoint PRODUCES
 * the published document and so is held to the canonical form; this function also reads documents
 * this image did not write, notably the one the deploy runbook generates from Terraform outputs for
 * the CloudFront path, and refusing a cosmetic difference there would take a whole environment
 * down over one character.
 *
 * Alternatives Considered: accepting any absolute URL and letting the first request fail. Rejected
 * because the resulting failure is a 404 on a screen, which reads as missing data rather than as
 * misconfiguration, and it reaches an operator instead of the deployment that caused it.
 * @param {string} candidate - The configured value, which may carry surrounding whitespace.
 * @param {string} subject - How to name the source in a refusal, so the message says which input to
 *   correct — for example `apiBaseUrl in the published runtime configuration`.
 * @returns {string} The normalized value: trimmed, with any trailing slashes removed.
 * @throws {Error} If the value is blank, is neither accepted shape, uses an unsafe scheme, carries
 *   user information, a query or a fragment, or does not end with the required path suffix.
 */
export function normalizeApiBaseUrl(candidate: string, subject: string): string {
  const normalized = candidate.trim().replace(/\/+$/u, '');
  if (normalized.length === 0) {
    throw new Error(`CardDemo API configuration is invalid; ${subject} is blank.`);
  }

  if (!isSameOriginPath(normalized)) {
    let parsed: URL;
    try {
      parsed = new URL(normalized);
    } catch {
      throw new Error(
        `CardDemo API configuration is invalid; ${subject} must be an absolute URL ending in ${API_BASE_URL_REQUIRED_SUFFIX}, or the same-origin path ${API_BASE_URL_REQUIRED_SUFFIX}.`,
      );
    }

    // Refactoring Rationale: the loopback exemption is decided from the RESOLVED HOST at run time,
    //       and it used to be decided from `import.meta.env.DEV` at build time. That was a defect
    //       rather than a preference, and it was measured rather than reasoned about: a production
    //       bundle inlines that flag as `false`, so the compiler constant-folded the whole exemption
    //       away and the shipped asset carried an UNCONDITIONAL refusal of plain HTTP. Serving the
    //       built bundle against the documented local edge — which publishes
    //       `http://localhost:8000/api/v1` — therefore threw before any request was dispatched, and
    //       every screen rendered its catalogued last-resort message instead.
    // Assumptions: the whole hostname is compared rather than tested for a `localhost` prefix,
    //       because `localhost.attacker.example` resolves wherever its owner points it and a
    //       substring test would exempt an attacker-controlled host from the scheme requirement.
    const loopbackApi = parsed.protocol === 'http:' && LOOPBACK_HOSTNAMES.includes(parsed.hostname);
    if (parsed.protocol !== 'https:' && !loopbackApi) {
      throw new Error(
        `CardDemo API configuration is invalid; ${subject} may use plain HTTP on a loopback host only, because only HTTPS is accepted for any other host.`,
      );
    }

    // WHY : Assumptions: a PATH is permitted here and the other four components are not. The base
    //       URL is required to carry the operation prefix, so rejecting a path would reject every
    //       correct value; user information, a query and a fragment have no meaning on a base URL
    //       and each would be silently dropped or appended by the client, so refusing them is
    //       refusing a configuration that cannot work as written.
    if (
      parsed.username.length > 0 ||
      parsed.password.length > 0 ||
      parsed.search.length > 0 ||
      parsed.hash.length > 0
    ) {
      throw new Error(
        `CardDemo API configuration is invalid; ${subject} must carry no user information, query or fragment.`,
      );
    }
  }

  // Assumptions: the suffix is checked on the WHOLE normalized value rather than on a parsed
  //   pathname, so one comparison serves both accepted shapes. A query or fragment could not
  //   survive to here on the absolute branch — it is refused above — and neither shape can carry
  //   one on the path branch without failing this test, which is why the path branch needs no
  //   component check of its own.
  if (!normalized.endsWith(API_BASE_URL_REQUIRED_SUFFIX)) {
    throw new Error(
      `CardDemo API configuration is invalid; ${subject} must end with ${API_BASE_URL_REQUIRED_SUFFIX}, which is the prefix every published operation sits under.`,
    );
  }

  return normalized;
}

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
   * API base URL in either accepted shape, always carrying the `/api/v1` operation prefix.
   *
   * Assumptions: the value stored here has already passed {@link normalizeApiBaseUrl}, so a reader
   * needs no further check — which is the property that lets `ui/src/api/client.ts` use it directly.
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
 * @returns {void} Nothing; the module-level value is discarded, after which `runtimeApiBaseUrl`
 *   reports the unloaded state again.
 */
export function resetRuntimeConfig(): void {
  loaded = undefined;
}

/**
 * How a refusal names the document's member, so the message says which input to correct.
 *
 * Assumptions: the member name is spelled here rather than in each message, because
 * `runtimeConfig.test.ts` asserts that a refusal names `apiBaseUrl` — an operator reading a start-up
 * failure has the document in front of them and needs the key, not a prose description of it.
 */
const CONFIG_DOCUMENT_SUBJECT = 'apiBaseUrl in the published runtime configuration';

/**
 * Validates one candidate configuration document.
 *
 * Refactoring Rationale: this used to accept any non-blank string, which made a published document
 * the one input to this application that nothing checked. The shape rules were in
 * `ui/src/api/client.ts` instead, so a malformed document was adopted here and refused there — at
 * the first request, on a screen, long after the deployment that published it had reported success.
 * The document is now held to exactly the rules the client applies, because they are the same
 * function.
 * @param {unknown} candidate - Parsed JSON body of the configuration document.
 * @returns {RuntimeConfig} The validated configuration, with its base URL normalized.
 * @throws {Error} If the document is not an object, or its API base URL is missing, not a string, or
 *   not one of the two shapes {@link normalizeApiBaseUrl} accepts.
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
  return { apiBaseUrl: normalizeApiBaseUrl(apiBaseUrl, CONFIG_DOCUMENT_SUBJECT) };
}

/**
 * Resolves the API base URL this tab will use, from the runtime document or the build-time variable.
 *
 * Refactoring Rationale: the runtime document is consulted BEFORE the build-time variable, and the
 * order is the point. `VITE_API_BASE_URL` is inlined when the bundle is produced, which is before
 * the deployment applies the environment that creates the API endpoint, so it is necessarily empty
 * for a deployed build. The published document is written after the endpoint is known, so it is the
 * authority wherever it exists; the compiled variable remains the fallback so a local development
 * server configured by `.env` keeps working unchanged, and so a component test that stubs the
 * variable needs no published document.
 *
 * Assumptions: the document's value is returned without re-validation because {@link validate}
 * normalized it at adoption, while the build-time variable is validated HERE — it is the one source
 * that reaches this function unchecked, since Vite inlines whatever the `.env` said.
 * @returns {string} The normalized API base URL, without a trailing slash.
 * @throws {Error} If neither source supplied a value, or the build-time variable is not one of the
 *   two shapes {@link normalizeApiBaseUrl} accepts.
 */
export function resolvedApiBaseUrl(): string {
  const published = runtimeApiBaseUrl();
  if (published !== undefined) {
    return published;
  }
  const compiled = import.meta.env.VITE_API_BASE_URL?.trim() ?? '';
  if (compiled.length === 0) {
    throw new Error(
      'CardDemo API configuration is unavailable; neither the published runtime configuration nor VITE_API_BASE_URL supplied an API base URL.',
    );
  }
  return normalizeApiBaseUrl(compiled, 'VITE_API_BASE_URL');
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
