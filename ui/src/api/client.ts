import axios from 'axios';
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios';

import { runtimeApiBaseUrl } from './runtimeConfig';

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
