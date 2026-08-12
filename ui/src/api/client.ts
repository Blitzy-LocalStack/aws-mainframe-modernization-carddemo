/**
 * @file The single shared HTTP client every typed API module in this package reaches the services
 * through, and the one place the cross-cutting request and response concerns are applied.
 *
 * Purpose
 * -------
 * Construct and memoise one axios instance carrying five boundaries that must be identical on every
 * request, so no per-operation client can implement any of them differently:
 *
 * - Authentication: the bearer token is attached by a request interceptor from the one
 *   session-storage key this module owns. No other module writes that key.
 * - Correlation: every request carries a correlation identifier whose length and alphabet match
 *   what `CorrelationIdFilter` in `services/common-lib` accepts, so a browser-named request and a
 *   service-named one are indistinguishable in shape and a trace spans both sides.
 * - Timeout: a bounded, clamped read timeout, so a stalled gateway surfaces as a failed request
 *   rather than a screen that never leaves its loading state.
 * - Exact money: responses are parsed so that a monetary value stays the STRING the service sent.
 *   A JSON number would be parsed into an IEEE-754 double at this boundary, which is the one place
 *   in the browser where fixed-point exactness can be lost silently.
 * - Server clock: the response date is recorded for the header band, so the instant a screen paints
 *   comes from the service rather than from the operator's workstation.
 *
 * Boundary
 * --------
 * Assumptions: this module knows no endpoint. Every path is supplied by a caller derived from a
 * service's OpenAPI contract, which is what keeps `ui/src/api/contracts.test.ts` able to compare
 * the two. It also stores no credential other than the access token: a password, a refresh token
 * and a challenge session travel in request bodies owned by `ui/src/api/auth.ts` and are never
 * held here.
 */

import axios from 'axios';
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios';

import { runtimeApiBaseUrl } from './runtimeConfig';
import { recordServerDate } from './serverClock';
import type { ApiError, ContractOperation } from './types';

const ACCESS_TOKEN_STORAGE_KEY = 'carddemo.access-token';
const DEFAULT_TIMEOUT_MS = 10_000;
const MIN_TIMEOUT_MS = 1_000;
const MAX_TIMEOUT_MS = 60_000;
const DEFAULT_CORRELATION_HEADER = 'X-Correlation-Id';
const HTTP_TOKEN_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/u;

/**
 * Bytes of entropy each generated correlation identifier carries.
 *
 * Assumptions: twelve, because the identifier is rendered two hexadecimal characters per byte and
 * the services accept at most twenty-four characters. That bound is not a preference here: it is
 * `CORRELATION_ID_MAX_LENGTH` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`, and a
 * longer value is refused with HTTP 400 before any handler runs. Twelve bytes is also exactly what
 * that filter uses when it mints one itself, so a request the browser named and a request the
 * service named are indistinguishable in shape.
 */
const CORRELATION_ID_ENTROPY_BYTES = 12;

let client: AxiosInstance | undefined;

/**
 * Resolves and validates the API base URL for this environment.
 *
 * Refactoring Rationale: the runtime document is consulted **before** the build-time variable, and
 * the order is the point. `VITE_API_BASE_URL` is inlined when the bundle is produced, which is
 * before the deployment applies the environment that creates the API endpoint, so it is necessarily
 * empty for a deployed build and this function used to throw on first render. The published
 * `config.json` is written after the endpoint is known, so it is the authority wherever it exists;
 * the compiled variable remains the fallback so a local development server configured by `.env`
 * keeps working unchanged. See `runtimeConfig.ts` for the full reasoning.
 *
 * Assumptions: the resolved value INCLUDES the `/api/v1` prefix. Every operation this client calls
 * is addressed relatively — `/cards`, `/auth/signon` — while the gateway publishes its route keys
 * under `/api/v1`, so a base URL without that prefix produces a 404 on every request while looking
 * correct in both the client and the gateway when each is read alone.
 * @returns {string} The normalized absolute API base URL, without a trailing slash.
 * @throws {Error} If no source supplied a URL, or the value supplied is unsafe.
 */
function apiBaseUrl(): string {
  const configured = (runtimeApiBaseUrl() ?? import.meta.env.VITE_API_BASE_URL)?.trim() ?? '';
  if (configured.length === 0) {
    throw new Error(
      'CardDemo API configuration is unavailable; neither the published runtime configuration nor VITE_API_BASE_URL supplied an API base URL.',
    );
  }

  let parsed: URL;
  try {
    parsed = new URL(configured);
  } catch {
    throw new Error(
      'CardDemo API configuration is invalid; VITE_API_BASE_URL must be an absolute URL.',
    );
  }

  const localDevelopment =
    import.meta.env.DEV &&
    parsed.protocol === 'http:' &&
    (parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1');
  if (parsed.protocol !== 'https:' && !localDevelopment) {
    throw new Error(
      'CardDemo API configuration is invalid; only HTTPS is accepted outside local development.',
    );
  }
  // WHY : Assumptions: a PATH is permitted here and the other four components are not. The base
  //       URL is required to carry `/api/v1`, so rejecting a path would reject every correct
  //       value; user information, a query and a fragment have no meaning on a base URL and each
  //       would be silently dropped or appended by the client, so refusing them is refusing a
  //       configuration that cannot work as written.
  if (
    parsed.username.length > 0 ||
    parsed.password.length > 0 ||
    parsed.search.length > 0 ||
    parsed.hash.length > 0
  ) {
    throw new Error(
      'CardDemo API configuration is invalid; user information, queries and fragments are not permitted.',
    );
  }

  return configured.replace(/\/+$/u, '');
}

/**
 * Resolves the bounded request timeout.
 * @returns {number} Timeout in milliseconds.
 * @throws {Error} If a configured timeout is not a whole bounded number.
 */
function apiTimeoutMs(): number {
  const configured = import.meta.env.VITE_API_TIMEOUT_MS?.trim();
  if (configured === undefined || configured.length === 0) {
    return DEFAULT_TIMEOUT_MS;
  }

  const timeout = Number(configured);
  if (!Number.isInteger(timeout) || timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
    throw new Error(
      `CardDemo API timeout must be a whole number from ${String(MIN_TIMEOUT_MS)} through ${String(MAX_TIMEOUT_MS)} milliseconds.`,
    );
  }
  return timeout;
}

/**
 * Resolves the request-correlation header shared with the service filter.
 * @returns {string} A valid HTTP header name.
 * @throws {Error} If a configured name is not an HTTP token.
 */
function correlationHeaderName(): string {
  const configured =
    import.meta.env.VITE_CORRELATION_ID_HEADER?.trim() ?? DEFAULT_CORRELATION_HEADER;
  if (!HTTP_TOKEN_PATTERN.test(configured)) {
    throw new Error(
      'CardDemo correlation-header configuration is invalid; the value must be an HTTP token.',
    );
  }
  return configured;
}

/**
 * Mints one request correlation identifier in the shape the services accept.
 *
 * Refactoring Rationale: this replaces `crypto.randomUUID()`, whose value is thirty-six characters
 * long — thirty-two hexadecimal digits and four hyphens. The shared correlation filter accepts at
 * most twenty-four, so EVERY request this client sent was refused with HTTP 400 before reaching a
 * handler, and the failure was invisible to both builds because a header value is a string on each
 * side. Twenty-four upper-case hexadecimal characters over twelve random bytes is the exact
 * construction that filter uses when it mints one itself, so the two are interchangeable.
 *
 * Alternatives Considered: truncating a UUID to twenty-four characters, which would have been a
 * one-line change. Rejected because a truncated UUID still carries a hyphen at position nine and
 * would silently lose the four bits of version and two bits of variant that make a UUID a UUID —
 * producing a value that looks like an identifier of a kind it is not. Generating the bytes
 * directly says what it is.
 *
 * Alternatives Considered: omitting the header entirely and letting the service mint the value,
 * which the contract explicitly permits. Rejected because the response identifier would then be the
 * only record of the request, so a browser-side failure before the response arrived — a timeout, an
 * aborted navigation — would leave nothing to quote to support.
 * @returns {string} Exactly twenty-four upper-case hexadecimal characters.
 */
function newCorrelationId(): string {
  const entropy = new Uint8Array(CORRELATION_ID_ENTROPY_BYTES);
  crypto.getRandomValues(entropy);
  return Array.from(
    entropy,
    /**
     * Renders one byte as two upper-case hexadecimal characters.
     * @param {number} byte - One byte of entropy, 0 through 255.
     * @returns {string} Its two-character upper-case hexadecimal rendering, zero-padded so that
     *   every byte contributes exactly two characters and the total length is fixed.
     */
    (byte: number): string => byte.toString(16).toUpperCase().padStart(2, '0'),
  ).join('');
}

/**
 * Adds the short-lived access token and a fresh request correlation identifier.
 * @param {InternalAxiosRequestConfig} config - Axios request configuration being dispatched.
 * @returns {InternalAxiosRequestConfig} The same configuration with bounded security headers
 *   applied.
 */
function applyRequestHeaders(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
  if (token !== null && token.length > 0) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  config.headers.set(correlationHeaderName(), newCorrelationId());
  return config;
}

/**
 * Anchors the server clock from a successful response and returns it unchanged.
 *
 * Assumptions: the response is returned as-is, so this interceptor is observational only. A response
 * interceptor that altered its input would make every caller's parsing depend on this module.
 * @param {AxiosResponse} response - Response whose `Date` header anchors the clock.
 * @returns {AxiosResponse} The same response, unmodified.
 */
function anchorClockFromResponse(response: AxiosResponse): AxiosResponse {
  recordServerDate(response.headers['date'] as string | undefined);
  return response;
}

/**
 * Anchors the server clock from a failed request and re-throws the original failure.
 *
 * WHY : Trade-offs: an ERROR response still came from the server and still carries its `Date`, so it
 *       is used as an anchor rather than discarded. Skipping it would drop a good anchor precisely
 *       when a session is having trouble and making the most requests.
 * @param {unknown} failure - Whatever Axios rejected with; not necessarily an `Error`.
 * @returns {never} Never returns normally.
 * @throws {unknown} The original failure, unchanged, so no caller's error handling is altered.
 */
function anchorClockFromFailure(failure: unknown): never {
  if (axios.isAxiosError(failure) && failure.response !== undefined) {
    recordServerDate(failure.response.headers['date'] as string | undefined);
  }
  // Alternatives Considered: `Promise.reject(failure)`, and an `async` function that throws. The
  //       first is refused by prefer-promise-reject-errors, because Axios types its reason `unknown`
  //       and the rule will not accept a rejection whose reason is not known to be an `Error`; the
  //       second is refused by require-await, because it would contain no `await`. A SYNCHRONOUS
  //       throw satisfies both without an exemption for either: Axios invokes this handler inside its
  //       own promise chain, so a throw here becomes a rejected promise carrying this exact reason --
  //       identical observable behaviour to the rejection that was written first.
  throw failure;
}

/**
 * Returns the singleton API client after validating build-time configuration.
 * @returns {AxiosInstance} Configured Axios client.
 * @throws {Error} If the endpoint, timeout or correlation header is invalid.
 */
export function getApiClient(): AxiosInstance {
  if (client === undefined) {
    client = axios.create({
      baseURL: apiBaseUrl(),
      timeout: apiTimeoutMs(),
      headers: { Accept: 'application/json' },
    });
    client.interceptors.request.use(applyRequestHeaders);
    // WHY : Assumptions: a RESPONSE interceptor rather than a per-call site, so the server clock is
    //       re-anchored by every operation without any caller knowing it exists. The header band
    //       displays a paint-time instant, and re-anchoring on each response keeps the elapsed term
    //       small, which is what bounds the accumulated error of a long-lived tab.
    //       Trade-offs: the error path re-anchors too. An error response is still a response from the
    //       server and still carries a `Date`, so skipping it would discard a good anchor precisely
    //       when a session is having trouble; the rejection is re-thrown unchanged so no caller's
    //       error handling is altered.
    client.interceptors.response.use(anchorClockFromResponse, anchorClockFromFailure);
  }
  return client;
}

/**
 * Discards the memoized client so the next call rebuilds it.
 *
 * Assumptions: the client captures its base URL and timeout when it is first constructed, so a
 * configuration loaded after that point would not reach it. Bootstrap loads the runtime document
 * before the first render and therefore before any request, but tests construct clients in several
 * configurations within one module, and without this they would all observe whichever one ran
 * first.
 */
export function resetApiClient(): void {
  client = undefined;
}

// WHY : Refactoring Rationale: the two request-and-response helpers every typed API module composes
//       its calls with, and the version prefix they are defined against, are declared here: these
//       three declarations were moved out of `./types`. That module
//       is a TYPES-ONLY module -- everything in it is erased at compile time, which is what lets it be
//       imported with `import type` from anywhere without pulling code into a bundle -- and a value, a
//       function and a regular expression are none of them erasable, so their presence there quietly
//       contradicted the module's own contract. They belong here rather than in a module of their own
//       because both are boundary concerns this file already owns: `requestPath` composes the target
//       that `getApiClient` sends, and `isApiError` reads the failure body that the same client's
//       rejection carries. The alternative -- a fourth module beside `client`, `runtimeConfig` and
//       `serverClock` -- would have added an import edge to seven callers for declarations only ever
//       used alongside the client they are already importing.
// WHY : Trade-offs: the runtime types this file needs from `./types` are imported with `import type`,
//       so the dependency arrow still points from this module to that one and never back. Placing
//       `requestPath` in `./types` had been the shorter arrangement precisely because it put the
//       function beside the `ContractOperation` interface it consumes; the cost paid here is that the
//       two now sit in different files, and what it buys is a types module that is genuinely erasable.

/**
 * The version prefix every published contract path carries and no client target does.
 *
 * Assumptions: the prefix belongs to the configured base URL rather than to a per-request target.
 * `VITE_API_BASE_URL` addresses the public HTTP API, whose route keys are all versioned -- the
 * `route_keys` default in `infra/modules/api-gateway-http/variables.tf` records that every key is
 * published under `/api/v1` -- so the base URL a build is given already ends in this prefix and a
 * target repeating it would resolve to `/api/v1/api/v1/...`.
 */
export const API_PATH_PREFIX = '/api/v1';

/** Matches one path-template placeholder, for example `{cardSelector}`. */
const PATH_PLACEHOLDER = /\{([A-Za-z][A-Za-z0-9]*)\}/gu;

/**
 * Builds the request target for one contract operation, substituting its path parameters.
 *
 * Assumptions: every supplied value is percent-encoded, and that is a correctness requirement rather
 * than defensive habit. Two contracts take a path parameter whose value comes from a response
 * body -- a sealed cursor in authorization-api and a reference code in reference-api -- and a sealed
 * token is base64url text that may legitimately contain characters a target treats as structure.
 * Encoding it is what stops such a value from being read as extra path segments.
 *
 * Assumptions: an unsubstituted placeholder and an unused parameter are BOTH refused. Refusing only
 * the first would let a caller pass a misspelled parameter name and receive a target still carrying
 * the literal placeholder text, which the service answers with 400 against a value the caller never
 * typed -- a failure attributed to the wrong side of the boundary.
 * @param {ContractOperation} operation - The operation, whose `path` is its contract template.
 * @param {Readonly<Record<string, string>>} [parameters] - One value per placeholder in that
 *   template, keyed by placeholder name. Omit it for an operation that declares none.
 * @returns {string} The target relative to the configured base URL, with the version prefix removed
 *   and every placeholder replaced by its percent-encoded value.
 * @throws {RangeError} If the operation's path does not carry the version prefix, if a placeholder
 *   has no supplied value, or if a supplied value matches no placeholder.
 */
export function requestPath(
  operation: ContractOperation,
  parameters: Readonly<Record<string, string>> = {},
): string {
  if (!operation.path.startsWith(`${API_PATH_PREFIX}/`)) {
    throw new RangeError(
      `Contract path for ${operation.operationId} must begin with ${API_PATH_PREFIX}/.`,
    );
  }

  const template = operation.path.slice(API_PATH_PREFIX.length);
  const consumed = new Set<string>();

  /**
   * Substitutes one placeholder, recording that its parameter was consumed.
   *
   * Assumptions: declared as a named inner function rather than written inline at the call below,
   * because `jsdoc/require-jsdoc` is configured with `publicOnly: false` and so selects a function
   * expression in every position -- and a block comment attached to an inline argument is moved by
   * Prettier onto the preceding expression, which detaches it from what it documents.
   * @param {string} _match - The whole matched placeholder, unused; the name alone identifies it.
   * @param {string} name - The placeholder's parameter name.
   * @returns {string} The supplied value, percent-encoded so it cannot read as extra path segments.
   * @throws {RangeError} If no value was supplied for that placeholder.
   */
  function substitute(_match: string, name: string): string {
    const value = parameters[name];
    if (value === undefined) {
      throw new RangeError(`Operation ${operation.operationId} requires a value for ${name}.`);
    }
    consumed.add(name);
    return encodeURIComponent(value);
  }

  const target = template.replace(PATH_PLACEHOLDER, substitute);

  for (const name of Object.keys(parameters)) {
    if (!consumed.has(name)) {
      throw new RangeError(
        `Operation ${operation.operationId} declares no path parameter ${name}.`,
      );
    }
  }
  return target;
}

/**
 * Reports whether an unknown value is a problem document a screen may read field errors from.
 *
 * Assumptions: the members probed are the ones a caller acts on -- the status, the correlation
 * identifier a user quotes to support, and the field-error array a form binds to -- rather than every
 * member the interface declares. Probing all eleven would reject a body from a future service that
 * added a member, and the guard exists to decide whether the body is USABLE, not whether it is
 * exhaustive.
 *
 * Alternatives Considered: narrowing with a cast at each call site instead, which needs no helper.
 * Rejected because a cast asserts the shape without checking it, so a screen reading `fieldErrors`
 * from an HTML error page returned by a misconfigured gateway would throw on `undefined.length` and
 * report as a rendering bug rather than as a non-JSON response.
 * @param {unknown} value - A response body of unknown shape, typically from a rejected request.
 * @returns {boolean} `true` when the value carries the three members a caller acts on, narrowing it
 *   to {@link ApiError}.
 */
export function isApiError(value: unknown): value is ApiError {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<ApiError>;
  return (
    typeof candidate.status === 'number' &&
    typeof candidate.correlationId === 'string' &&
    Array.isArray(candidate.fieldErrors)
  );
}

/**
 * Stores or clears the access token for the current browser tab.
 * @param {string | null} token - Access token issued by auth-service, or `null` to sign out.
 * @throws {RangeError} If a non-null token is blank or contains controls.
 */
export function setAccessToken(token: string | null): void {
  if (token === null) {
    sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    return;
  }
  if (token.length === 0 || /[\u0000-\u001F\u007F]/u.test(token)) {
    throw new RangeError('Access token must be non-empty and contain no control characters.');
  }
  sessionStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
}
