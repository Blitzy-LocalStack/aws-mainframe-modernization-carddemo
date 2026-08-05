import axios from "axios";
import type { AxiosInstance, InternalAxiosRequestConfig } from "axios";

const ACCESS_TOKEN_STORAGE_KEY = "carddemo.access-token";
const DEFAULT_TIMEOUT_MS = 10_000;
const MIN_TIMEOUT_MS = 1_000;
const MAX_TIMEOUT_MS = 60_000;
const DEFAULT_CORRELATION_HEADER = "X-Correlation-ID";
const HTTP_TOKEN_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/u;

let client: AxiosInstance | undefined;

/**
 * Resolves and validates the API base URL compiled into this build.
 * @returns {string} The normalized absolute API base URL.
 * @throws {Error} If the build omitted the URL or supplied an unsafe value.
 */
function apiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
  if (configured.length === 0) {
    throw new Error(
      "CardDemo API configuration is unavailable; VITE_API_BASE_URL was not supplied for this build.",
    );
  }

  let parsed: URL;
  try {
    parsed = new URL(configured);
  } catch {
    throw new Error(
      "CardDemo API configuration is invalid; VITE_API_BASE_URL must be an absolute URL.",
    );
  }

  const localDevelopment =
    import.meta.env.DEV &&
    parsed.protocol === "http:" &&
    (parsed.hostname === "localhost" || parsed.hostname === "127.0.0.1");
  if (parsed.protocol !== "https:" && !localDevelopment) {
    throw new Error(
      "CardDemo API configuration is invalid; only HTTPS is accepted outside local development.",
    );
  }
  if (
    parsed.username.length > 0 ||
    parsed.password.length > 0 ||
    parsed.search.length > 0 ||
    parsed.hash.length > 0
  ) {
    throw new Error(
      "CardDemo API configuration is invalid; user information, queries and fragments are not permitted.",
    );
  }

  return configured.replace(/\/+$/u, "");
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
  if (
    !Number.isInteger(timeout) ||
    timeout < MIN_TIMEOUT_MS ||
    timeout > MAX_TIMEOUT_MS
  ) {
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
    import.meta.env.VITE_CORRELATION_ID_HEADER?.trim() ??
    DEFAULT_CORRELATION_HEADER;
  if (!HTTP_TOKEN_PATTERN.test(configured)) {
    throw new Error(
      "CardDemo correlation-header configuration is invalid; the value must be an HTTP token.",
    );
  }
  return configured;
}

/**
 * Adds the short-lived access token and a fresh request correlation identifier.
 * @param {InternalAxiosRequestConfig} config - Axios request configuration being dispatched.
 * @returns {InternalAxiosRequestConfig} The same configuration with bounded security headers
 *   applied.
 */
function applyRequestHeaders(
  config: InternalAxiosRequestConfig,
): InternalAxiosRequestConfig {
  const token = sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
  if (token !== null && token.length > 0) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  config.headers.set(correlationHeaderName(), crypto.randomUUID());
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
      headers: { Accept: "application/json" },
    });
    client.interceptors.request.use(applyRequestHeaders);
  }
  return client;
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
    throw new RangeError(
      "Access token must be non-empty and contain no control characters.",
    );
  }
  sessionStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
}
