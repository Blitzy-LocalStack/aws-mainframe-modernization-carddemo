/**
 * @file The single shared HTTP client every typed API module in this package reaches the services
 * through, and the one place the cross-cutting request and response concerns are applied.
 *
 * Purpose
 * -------
 * Construct and memoise one axios instance carrying six boundaries that must be identical on every
 * request, so no per-operation client can implement any of them differently:
 *
 * - Authentication: the bearer token is attached by a request interceptor from the one
 *   session-storage key this module owns. No other module writes that key.
 * - Correlation: every request carries a correlation identifier whose length and alphabet match
 *   what `CorrelationIdFilter` in `services/common-lib` accepts, so a browser-named request and a
 *   service-named one are indistinguishable in shape and a trace spans both sides.
 * - Timeout: a bounded, clamped read timeout, so a stalled gateway surfaces as a failed request
 *   rather than a screen that never leaves its loading state.
 * - Failure shape: every transport failure is normalised into one {@link ApiRequestError} carrying
 *   the shared {@link ApiError} problem document, so a screen renders one shape whether the refusal
 *   came from a service, from a proxy that answered HTML, or from a request that never arrived.
 * - Exact money: a monetary value stays the STRING the service sent, because the default response
 *   transform is left in place and nothing here reads, rounds or re-serialises a number. A JSON
 *   number would be parsed into an IEEE-754 double at this boundary, which is the one place in the
 *   browser where fixed-point exactness can be lost silently.
 * - Server clock: the response date is recorded for the header band, so the instant a screen paints
 *   comes from the service rather than from the operator's workstation.
 *
 * Failures
 * --------
 * A failure arrives at a screen in exactly one shape and is classified into exactly one of four
 * kinds — a service's own problem document, an answer from something between the browser and the
 * service whose body is not one, the configured timeout expiring, or no answer at all. The
 * classification is what decides the remedy a screen can offer; the document is what decides the
 * text it shows and which of its fields it marks. HTTP 401 additionally discards the stored token
 * and signals that re-authentication is required, and it does so WITHOUT navigating: routing is
 * `ui/src/router.tsx`'s concern and a redirect issued from an interceptor loops.
 *
 * Boundary
 * --------
 * Assumptions: this module knows no endpoint. Every path is supplied by a caller derived from a
 * service's OpenAPI contract, which is what keeps `ui/src/api/contracts.test.ts` able to compare
 * the two. It also stores no credential other than the access token: a password, a refresh token
 * and a challenge session travel in request bodies owned by `ui/src/api/auth.ts` and are never
 * held here.
 */

import axios, { AxiosError } from 'axios';
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios';

import { runtimeApiBaseUrl } from './runtimeConfig';
import { recordServerDate } from './serverClock';
import type { ApiError, ContractOperation, Severity } from './types';

// WHY : Alternatives Considered: ONE instance for the whole package, memoised below, rather than one
//       per typed client module. Seven instances would each need the bearer header, the correlation
//       identifier, the timeout and the failure normalisation attached, and four boundaries repeated
//       seven times drift: the concrete failure is a module whose requests carry no correlation
//       identifier, which reports nothing and simply stops joining traces up. One instance also
//       means one place to answer "what did the browser send", which is why `client.test.ts` can pin
//       the correlation contract by dispatching through the real instance rather than a stand-in.
// WHY : Alternatives Considered: the bearer header rather than a cookie session, and consequently no
//       `withCredentials` anywhere in this module. The edge is an API Gateway HTTP API with a
//       Cognito JWT authorizer, which validates the `Authorization` header and nothing else, so a
//       cookie would have to be exchanged for a token by something. A cookie session also
//       reintroduces server-side session state, which AAP section 0.7.1 removes deliberately — the
//       services hold none, which is what lets them scale out behind a load balancer with no sticky
//       sessions — and it would oblige every mutating request to carry CSRF machinery that a bearer
//       token in an explicit header does not need.
const ACCESS_TOKEN_STORAGE_KEY = 'carddemo.access-token';
const DEFAULT_TIMEOUT_MS = 10_000;
const MIN_TIMEOUT_MS = 1_000;
const MAX_TIMEOUT_MS = 60_000;
const DEFAULT_CORRELATION_HEADER = 'X-Correlation-Id';
const HTTP_TOKEN_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/u;

/**
 * The header a request carries its bearer token in, spelled as the edge authorizer reads it.
 *
 * Assumptions: this name is NOT configurable, unlike the correlation header. The Cognito JWT
 * authorizer on the HTTP API reads `Authorization`, so a deployment cannot rename it, and offering a
 * variable for it would advertise a flexibility that does not exist. It is named as a constant
 * because the response path reads the same header back off the failed request configuration to
 * decide whether a 401 concerns a stored session or a submitted credential.
 */
const AUTHORIZATION_HEADER = 'Authorization';

/** HTTP status a service answers when a caller's token is absent, expired or not accepted. */
const UNAUTHORIZED_STATUS = 401;

/** HTTP status a service answers when a write lost an optimistic-concurrency check. */
const CONFLICT_STATUS = 409;

/** Lowest HTTP status a service classifies as its own failure rather than the caller's. */
const SERVER_ERROR_STATUS = 500;

/** Lowest HTTP status a service classifies as a caller failure. */
const CLIENT_ERROR_STATUS = 400;

/**
 * The status a normalised failure reports when no response arrived at all.
 *
 * Assumptions: zero rather than an absent member, and the choice is forced rather than preferred:
 * `ApiError.status` in `./types` is a required `number`, mirroring the `int status` component of
 * `services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java`, so there is no absent
 * value to report. Zero is not a valid HTTP status, so it cannot be confused with one a service
 * could have sent, and a screen testing `status === 0` is asking exactly "did this reach anything".
 */
const NO_HTTP_STATUS = 0;

/**
 * Bytes of entropy each generated correlation identifier carries.
 *
 * Assumptions: twelve, because the identifier is rendered two hexadecimal characters per byte and
 * the services accept at most twenty-four characters. That bound is not a preference here: it is
 * `CORRELATION_ID_MAX_LENGTH` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`, and a
 * longer value is refused with HTTP 400 before any handler runs. The WIDTH is what makes a request
 * the browser named and a request the service named indistinguishable in shape: that filter mints its
 * own as a two-character `CD` prefix followed by eleven random bytes rendered as upper-case
 * hexadecimal, which is the same twenty-four upper-case hexadecimal characters twelve bytes produce
 * here. The prefix is deliberately not imitated — a minted identity is meant to be recognisable as
 * one the service minted, and a browser that stamped `CD` would claim that provenance falsely.
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
 * The decimal digits, indexed so a character's position in this string is its value.
 *
 * Assumptions: the fold below reads a digit's value from this position rather than converting the
 * character, which is what keeps the module free of every numeric-coercion form. See
 * {@link digitsToInteger} for why that property is held for the whole file rather than only where
 * money is read.
 */
const DECIMAL_DIGITS = '0123456789';

/**
 * The accepted written form of a configured timeout.
 *
 * Assumptions: at most five digits, because the largest value in range is five digits wide, so a
 * longer string cannot be accepted whatever it says and refusing it before the fold keeps the fold
 * bounded. A leading zero, a sign, a decimal point and surrounding text are all refused here rather
 * than silently reinterpreted, so a mistyped value names itself at start-up.
 */
const TIMEOUT_DIGITS_PATTERN = /^[0-9]{1,5}$/u;

/**
 * Folds a string of decimal digits into the integer it spells.
 *
 * Assumptions: the caller has already established that every character is a decimal digit, so the
 * position lookup below cannot miss; this function is not a general parser and must not be used as
 * one.
 *
 * Refactoring Rationale: this replaces `Number(configured)`. The substitution is not about the
 * timeout, which a coercion would have converted correctly — it is about the property the whole
 * module has to hold. Money crosses this boundary as a STRING precisely because a JSON number is
 * parsed into an IEEE-754 double, so a single reviewer question — "does anything in this file turn
 * text into a number?" — has to be answerable by one search rather than by reading each call site to
 * judge whether it touches an amount. With no `Number(`, no `parseInt`, no `parseFloat` and no unary
 * plus anywhere in the file, that search answers itself.
 *
 * Trade-offs: six lines of arithmetic replace one call, and the exchange is deliberate. What it buys
 * is that the money-exactness prohibition is a property of the FILE rather than a property of each
 * site, which is the difference between a rule a reviewer can check and one they have to adjudicate.
 * @param {string} digits - One to five decimal digits, already validated by the caller.
 * @returns {number} The whole number those digits spell, in order of significance.
 */
function digitsToInteger(digits: string): number {
  let total = 0;
  for (const digit of digits) {
    total = total * 10 + DECIMAL_DIGITS.indexOf(digit);
  }
  return total;
}

/**
 * Resolves the bounded request timeout.
 *
 * Assumptions: the fallback is supplied HERE rather than by the environment, and that placement is
 * stated by the configuration contract itself: `ui/.env.example` leaves `VITE_API_TIMEOUT_MS` empty
 * and records that this module must carry its own default, on the grounds that a behavioural default
 * belongs in code while the file's rule that no right-hand side is ever populated stays absolute.
 *
 * Trade-offs: an explicit request timeout is the ONLY resilience mechanism this module carries, and
 * two alternatives were rejected rather than overlooked. A browser-side retry was rejected because
 * the operations that matter are not idempotent — an account update, a transaction add and a bill
 * payment each change a balance — so a retry after an ambiguous timeout risks posting twice, and the
 * durable retry tier already exists elsewhere: SQS redelivery with a dead-letter queue for the
 * asynchronous paths, and per-state Step Functions retry for the batch chain. A circuit breaker was
 * rejected because the only synchronous hops behind this base URL are in-VPC through an internal
 * load balancer with bounded timeouts of their own, so a breaker would add a state machine that can
 * refuse a healthy request without removing any failure a bounded timeout does not already bound.
 * @returns {number} Timeout in milliseconds.
 * @throws {Error} If a configured timeout is not a whole bounded number.
 */
function apiTimeoutMs(): number {
  const configured = import.meta.env.VITE_API_TIMEOUT_MS?.trim();
  if (configured === undefined || configured.length === 0) {
    return DEFAULT_TIMEOUT_MS;
  }

  // WHY : Assumptions: a value that is not written as plain digits is carried into the SAME range
  //       test as one that is, by resolving to a sentinel below the minimum, so both refusals reach
  //       the operator as one sentence naming the accepted range. Reporting "not digits" separately
  //       from "out of range" would tell a reader which check tripped and still not tell them what to
  //       write, which is the only thing the message is for.
  const timeout = TIMEOUT_DIGITS_PATTERN.test(configured) ? digitsToInteger(configured) : -1;
  if (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
    throw new Error(
      `CardDemo API timeout must be a whole number from ${String(MIN_TIMEOUT_MS)} through ${String(MAX_TIMEOUT_MS)} milliseconds.`,
    );
  }
  return timeout;
}

/**
 * Resolves the request-correlation header shared with the service filter.
 *
 * Assumptions: the NAME is configuration and the obligation to send one is not. The name comes from
 * `VITE_CORRELATION_ID_HEADER` and must equal `CORRELATION_ID_HEADER` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`, which is
 * `X-Correlation-Id` — the default below, so an unset variable still agrees with the services. A
 * mismatch is the worst failure mode available here because nothing reports it: every request still
 * succeeds, the filter simply mints its own identifier, and the browser's value never reaches the
 * logging context, so traces stop joining up silently. Keeping the name configurable lets both sides
 * of that contract move together instead of requiring a rebuilt bundle to follow a server rename.
 *
 * Assumptions: correlating a request/reply pair by an identifier the initiator supplies is the
 * baseline's own mechanism rather than an addition. The authorization consumer saves the request's
 * message-descriptor correlation identifier into `05 WS-SAVE-CORRELID PIC X(24).` at
 * `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` L45 (L411 to L412) and copies it onto the
 * reply at L745, so a reply can be attributed to the request that caused it. This header is the HTTP
 * analogue, and the twenty-four-character width the filter accepts is the same width that field
 * declares.
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
 *
 * Refactoring Rationale: the token is the whole of what this client asserts about its operator, and
 * it asserts it as a SIGNED claim it cannot author. The baseline carried the operator's authority in
 * storage the client echoed back on every screen turn — `10 CDEMO-USER-TYPE PIC X(01).` at
 * `app/cpy/COCOM01Y.cpy` L26, with `88 CDEMO-USRTYP-ADMIN VALUE 'A'.` at L27 and
 * `88 CDEMO-USRTYP-USER VALUE 'U'.` at L28 — so the value a program read back was a value the
 * terminal had been handed and could in principle have altered. Nothing here sends a user type, a
 * group or an administrative flag, and nothing may be added that does: the authority lives in the
 * token's own group claim, every service revalidates the token and re-derives it, and a tampered
 * local copy changes what the browser draws and nothing about what it is permitted to do.
 *
 * Assumptions: an absent token is a NORMAL condition, not an error, so no header is attached at all
 * rather than one carrying an empty or undefined bearer. Sign-on is the one unauthenticated request
 * in the system, and a service reading `Authorization: Bearer undefined` would refuse it as a
 * malformed credential — reporting a rejected token where the operator has not yet presented one.
 * @param {InternalAxiosRequestConfig} config - Axios request configuration being dispatched.
 * @returns {InternalAxiosRequestConfig} The same configuration with bounded security headers
 *   applied.
 */
function applyRequestHeaders(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
  if (token !== null && token.length > 0) {
    config.headers.set(AUTHORIZATION_HEADER, `Bearer ${token}`);
  }
  config.headers.set(correlationHeaderName(), newCorrelationId());
  return config;
}

/**
 * Reads one header from a bag whose type Axios does not narrow, without asserting its shape.
 *
 * Assumptions: the bag is narrowed through an explicitly `unknown` local because Axios types its
 * header collections with an index signature returning `any`, which
 * `@typescript-eslint/no-unsafe-member-access` refuses. Narrowing to a string here also means a
 * non-string value reads as absent rather than as a stringified object.
 *
 * Assumptions: two spellings are tried, and the asymmetry is Axios's rather than this module's. A
 * REQUEST header is stored under the spelling it was set with, which is why `client.test.ts` finds
 * `X-Correlation-Id` by that exact name; a RESPONSE header arrives normalised to lower case, which is
 * why the clock anchor above reads `date`. Trying the given spelling and then its lower-case form
 * reads both without the caller having to know which side it is holding.
 * @param {unknown} bag - A header collection of unknown shape, or nothing at all.
 * @param {string} name - Header name as this module spells it.
 * @returns {string | undefined} The header's string value, or nothing when it is absent or is not a
 *   string.
 */
function headerValue(bag: unknown, name: string): string | undefined {
  if (typeof bag !== 'object' || bag === null) {
    return undefined;
  }
  const headers = bag as Record<string, unknown>;
  const exact: unknown = headers[name];
  const normalised: unknown = headers[name.toLowerCase()];
  if (typeof exact === 'string') {
    return exact;
  }
  return typeof normalised === 'string' ? normalised : undefined;
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
 * The failure shape Axios rejects with, narrowed so its body and its configuration are `unknown`.
 *
 * Assumptions: both type arguments are given explicitly. Left to their defaults, the response body
 * would be typed `any`, and every read of it would be an unsafe member access that the rule set
 * refuses — for a body that genuinely is of unknown shape, since a proxy may answer HTML.
 */
type TransportFailure = AxiosError<unknown, unknown>;

/**
 * How a failed request was classified, which is what decides the remedy a screen can offer.
 *
 * Assumptions: four members, because four failures are distinguishable here and no more.
 * `PROBLEM` is a service's own refusal, carrying its verbatim document; `RESPONSE` is an answer from
 * something between the browser and the service — a gateway, a load balancer, or the origin serving
 * the SPA — whose body is not a problem document; `TIMEOUT` is the configured bound expiring, which a
 * screen may offer to repeat; `NETWORK` is no answer at all, which it may not. An ABORTED request is
 * reported as `NETWORK` rather than earning a fifth member: no caller in this package passes an abort
 * signal, and a member nothing can produce reads to a screen author as a case they must handle.
 */
export type ApiFailureKind = 'PROBLEM' | 'RESPONSE' | 'TIMEOUT' | 'NETWORK';

// WHY : Assumptions: a problem code minted in the BROWSER carries a `CARDDEMO-UI-` prefix, which no
//       service can produce.
//       `services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java` declares the
//       whole server-side set as four-digit codes -- `CARDDEMO-0400`, `CARDDEMO-0404`,
//       `CARDDEMO-0409`, `CARDDEMO-0405`, `CARDDEMO-0413`, `CARDDEMO-0415`, `CARDDEMO-0500` and
//       `CARDDEMO-0503` -- so the prefix tells an operator reading a code which side classified the
//       failure, without them having to know which statuses a service emits documents for.
//       Alternatives Considered: reusing `CARDDEMO-0500` for an unreachable service, which needs no
//       new code. Rejected because it would report a service's own internal failure for a request
//       that never reached one, sending an investigation to the wrong logs.

/** Problem code for a response whose body is not a problem document, such as a proxy's HTML. */
const CODE_UNEXPECTED_BODY = 'CARDDEMO-UI-BODY';

/** Problem code for a request abandoned when the configured timeout expired. */
const CODE_TIMEOUT = 'CARDDEMO-UI-TIMEOUT';

/** Problem code for a request that received no response at all. */
const CODE_NO_RESPONSE = 'CARDDEMO-UI-NETWORK';

/**
 * The Axios error codes that mean the configured timeout expired.
 *
 * Assumptions: BOTH are accepted because which one Axios reports depends on a transitional option
 * this module does not set — `ECONNABORTED` by default and `ETIMEDOUT` when `clarifyTimeoutError` is
 * enabled — and a classification that depended on that option would change with a dependency
 * upgrade. The values are read from the library's own constants rather than written as literals, so
 * a rename cannot leave a stale string here compiling.
 */
const TIMEOUT_CODES: readonly string[] = [AxiosError.ECONNABORTED, AxiosError.ETIMEDOUT];

/**
 * One normalised transport failure, which is the only reason this client's requests ever reject with.
 *
 * Assumptions: a screen reads {@link ApiRequestError.problem} for what to SHOW — the service's own
 * sentence and its per-field entries — and {@link ApiRequestError.kind} for what to OFFER. The two
 * are separate because a document can be present for a refusal a screen must not offer to repeat,
 * and absent for a timeout it should.
 *
 * Assumptions: an `Error` subclass rather than a plain object, for two independent reasons. The rule
 * set refuses a rejection whose reason is not known to be an `Error`, and a caller's `catch` gets a
 * stack that names the call site rather than a bare shape it has to identify by probing. `instanceof`
 * is reliable here because `ui/tsconfig.json` targets ES2022, so the class is emitted natively and
 * needs none of the `setPrototypeOf` repair a down-levelled subclass of `Error` requires.
 *
 * Trade-offs: the originating Axios error is deliberately NOT attached as `cause`, which would be the
 * conventional choice and would help debugging. It is refused because that object holds the request
 * configuration, and the request configuration holds the `Authorization` header — so attaching it
 * would place a live bearer token inside an object that any error reporter, breadcrumb trail or
 * `JSON.stringify` of a caught failure would serialise. The diagnostic sentence and the correlation
 * identifier are what an investigation needs, and neither carries a credential.
 */
export class ApiRequestError extends Error {
  /** Which of the four distinguishable failures this is. */
  readonly kind: ApiFailureKind;

  /** HTTP status received, or `0` when no response arrived; see the `NO_HTTP_STATUS` note. */
  readonly status: number;

  /**
   * The problem document to render from, carried verbatim when a service sent one.
   *
   * Assumptions: for `PROBLEM` this is the service's own body and is NOT rewritten — not its status,
   * not its code, and above all not its `fieldErrors`, whose order is the order a form marks its
   * fields in. For every other kind it is synthesised here with `fieldErrors` empty, never invented.
   *
   * Assumptions: on a 400 that array is the WHOLE field-marking mechanism, because the target keeps
   * no re-entry state to gate it with. The baseline's templated highlight moves the error colour into
   * a field only when its validation flag is not-OK or blank AND the program is on a re-entry turn —
   * `app/cpy/CSSETATY.cpy` L18 to L20, with the additional literal asterisk for the blank case at L23
   * to L25 — and that turn counter was carried in the pseudo-conversational session struct, which
   * AAP section 0.7.1 removes entirely. A stateless handler has no turn to remember, so the response
   * body is the only thing left that can say which fields to mark: dropping, reordering or padding
   * this array changes what a screen highlights.
   */
  readonly problem: ApiError;

  /** The identifier a user quotes to support, echoed from the response or the sent request. */
  readonly correlationId: string;

  /**
   * Builds one normalised failure.
   * @param {ApiFailureKind} kind - Which of the four distinguishable failures this is.
   * @param {number} status - HTTP status received, or `0` when no response arrived.
   * @param {ApiError} problem - The problem document to render from, verbatim where a service sent
   *   one.
   * @param {string} diagnostic - Sentence for a developer or a log line. It names the kind, the
   *   status, the problem code and the correlation identifier, and deliberately NOT the request
   *   target, because a target can carry a primary account number as a path parameter and this
   *   sentence is the member a logger is most likely to print.
   */
  constructor(kind: ApiFailureKind, status: number, problem: ApiError, diagnostic: string) {
    super(diagnostic);
    this.name = 'ApiRequestError';
    this.kind = kind;
    this.status = status;
    this.problem = problem;
    this.correlationId = problem.correlationId;
  }
}

/**
 * A listener told that the stored session is no longer accepted.
 *
 * Assumptions: it receives the normalised 401 that invalidated the session, so a shell can quote the
 * correlation identifier and render the service's own sentence rather than composing one, and it
 * returns nothing — the registry ignores any value, and a listener acts on its own state.
 */
export type AuthenticationRequiredListener = (failure: ApiRequestError) => void;

const authenticationRequiredListeners = new Set<AuthenticationRequiredListener>();

/**
 * Registers a listener told when a stored session stops being accepted.
 *
 * Trade-offs: this module SIGNALS re-authentication and does not perform it. Sending the operator to
 * the sign-on route from inside an interceptor was the shorter option and is refused on two counts:
 * the sign-on request itself can answer 401, so a redirect issued from here can loop through the very
 * screen it navigates to, and a module that navigates cannot be exercised without a router, which
 * would put the whole failure-classification path behind a rendering fixture. The app shell owns the
 * route change; this owns the fact.
 *
 * Assumptions: the token is discarded BEFORE any listener runs, so a listener that re-reads the
 * session observes it already gone rather than racing the interceptor for it.
 * @param {AuthenticationRequiredListener} listener - Called once per 401 that invalidated a session.
 * @returns {() => void} A function that removes this listener; calling it twice is harmless.
 */
export function subscribeToAuthenticationRequired(
  listener: AuthenticationRequiredListener,
): () => void {
  authenticationRequiredListeners.add(listener);
  /**
   * Removes the listener registered above.
   * @returns {void} Nothing; the registry shrinks by at most one entry.
   */
  function unsubscribe(): void {
    authenticationRequiredListeners.delete(listener);
  }
  return unsubscribe;
}

/**
 * Mirrors the severity a service would have derived from the same status.
 *
 * Assumptions: the three bands are transcribed from `severityForStatus` in
 * `services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java`, so a document
 * synthesised in the browser classifies the way one from a service would and a screen keying on
 * severity behaves the same either way. A failure with no status at all is `CRITICAL`, because
 * nothing answered.
 * @param {number} status - HTTP status received, or `0` when none was.
 * @returns {Severity} The severity band a service assigns that status.
 */
function severityForStatus(status: number): Severity {
  if (status === NO_HTTP_STATUS || status >= SERVER_ERROR_STATUS) {
    return 'CRITICAL';
  }
  return status >= CLIENT_ERROR_STATUS ? 'WARNING' : 'INFO';
}

/**
 * Recovers the correlation identifier a failed request was carried out under.
 *
 * Assumptions: the RESPONSE header is preferred over the one this client sent, because the filter
 * that logged the request is entitled to replace a non-conforming inbound value — it answers 400 and
 * stamps its own identifier — so the response header is the value that actually appears in the
 * service's logs, which is the only thing quoting it is for. The sent header is the fallback, and it
 * is what makes a timeout traceable at all: no response arrived, so nothing echoed anything.
 * @param {TransportFailure} failure - The rejected Axios failure.
 * @returns {string} The identifier, or the empty string when neither side recorded one.
 */
function correlationIdOf(failure: TransportFailure): string {
  const name = correlationHeaderName();
  const echoed = headerValue(failure.response?.headers, name);
  if (echoed !== undefined) {
    return echoed;
  }
  return headerValue(failure.config?.headers, name) ?? '';
}

/**
 * Builds the problem document for a failure no service described.
 *
 * Assumptions: `fieldErrors` is EMPTY and never populated here. `ApiError` in `./types` declares it
 * required, matching every published contract, so an absent array is not representable and empty is
 * the only honest value — but empty is a statement that no field was named, and inventing an entry to
 * make a screen show something would mark a field the service never objected to.
 *
 * Assumptions: `timestamp` is empty for the same class of reason. The member carries the instant a
 * SERVICE recorded, in the twenty-six-character form `TimestampFormatter` writes, and no service
 * recorded this one. Stamping the workstation clock into it would put a local instant in a field a
 * screen presents as the service's, which is the exact confusion `ui/src/api/serverClock.ts` exists
 * to prevent.
 *
 * Assumptions: `subsystem` is `APPLICATION` because the enumeration transcribes the baseline's own
 * attribution vocabulary and has no browser member; the `CARDDEMO-UI-` code is what identifies the
 * classifier, so nothing is lost by not inventing one.
 * @param {string} code - One of this module's `CARDDEMO-UI-` codes.
 * @param {number} status - HTTP status received, or `0` when none was.
 * @param {string} correlationId - Identifier the request was carried out under, possibly empty.
 * @param {string} path - Request target the failure concerns, relative to the configured base URL.
 * @returns {ApiError} A complete problem document carrying no invented field entries and no
 *   fabricated instant.
 */
function synthesisedProblem(
  code: string,
  status: number,
  correlationId: string,
  path: string,
): ApiError {
  return {
    code,
    secondaryCode: '',
    message: null,
    severity: severityForStatus(status),
    subsystem: 'APPLICATION',
    status,
    correlationId,
    path,
    timestamp: '',
    fieldErrors: [],
    abend: null,
  };
}

/**
 * Composes the developer-facing sentence carried as the `Error` message.
 * @param {ApiFailureKind} kind - Which of the four distinguishable failures this is.
 * @param {number} status - HTTP status received, or `0` when none was.
 * @param {string} code - Problem code, from the service's document or minted here.
 * @param {string} correlationId - Identifier to quote when reporting the failure.
 * @returns {string} A single line naming the classification, the status, the code and the identifier,
 *   and no part of the request target or of any credential.
 */
function diagnosticFor(
  kind: ApiFailureKind,
  status: number,
  code: string,
  correlationId: string,
): string {
  const quoted = correlationId.length === 0 ? 'none' : correlationId;
  return `CardDemo API request failed: ${kind} status ${String(status)} ${code} correlation ${quoted}`;
}

/**
 * Normalises one Axios failure into the single shape every caller handles.
 *
 * Assumptions: a service's problem document is used AS IT STANDS when the body carries the members a
 * caller acts on, and its `status` is not overwritten with the transport status even though the two
 * agree in practice — rewriting a member of a document described as verbatim would make that
 * description false. The transport status is reported separately on the error, which is what the 409
 * and 401 tests below read.
 *
 * Trade-offs: a body that is not a problem document is turned into one rather than passed through.
 * That loses the original body — a gateway's HTML, a load balancer's plain text — and what it buys is
 * that a screen renders one shape. The alternative was measured against the consumers: seven typed
 * clients and twenty-one screens would each need three branches, and the branch nobody writes is the
 * one for the proxy failure that only happens in a deployed environment.
 * @param {TransportFailure} failure - The rejected Axios failure, with a response or without one.
 * @returns {ApiRequestError} The normalised failure, classified into one of four kinds.
 */
function normaliseFailure(failure: TransportFailure): ApiRequestError {
  const correlationId = correlationIdOf(failure);
  const target = failure.config?.url ?? '';
  const response = failure.response;

  if (response !== undefined) {
    const body: unknown = response.data;
    if (isApiError(body)) {
      return new ApiRequestError(
        'PROBLEM',
        response.status,
        body,
        diagnosticFor('PROBLEM', response.status, body.code, correlationId),
      );
    }
    return new ApiRequestError(
      'RESPONSE',
      response.status,
      synthesisedProblem(CODE_UNEXPECTED_BODY, response.status, correlationId, target),
      diagnosticFor('RESPONSE', response.status, CODE_UNEXPECTED_BODY, correlationId),
    );
  }

  // WHY : Assumptions: a timeout is separated from every other answerless failure because the two
  //       have different remedies and a screen can only offer the right one if it is told which
  //       happened: a request that timed out reached something and may succeed if repeated, while one
  //       that resolved nothing, was refused by CORS or found no route will fail again identically.
  const timedOut = failure.code !== undefined && TIMEOUT_CODES.includes(failure.code);
  const kind: ApiFailureKind = timedOut ? 'TIMEOUT' : 'NETWORK';
  const code = timedOut ? CODE_TIMEOUT : CODE_NO_RESPONSE;
  return new ApiRequestError(
    kind,
    NO_HTTP_STATUS,
    synthesisedProblem(code, NO_HTTP_STATUS, correlationId, target),
    diagnosticFor(kind, NO_HTTP_STATUS, code, correlationId),
  );
}

/**
 * Delivers the re-authentication signal to one listener, outside the rejection's own turn.
 *
 * Assumptions: this exists as a named function rather than as a closure written at the call site
 * because `jsdoc/require-jsdoc` is configured with `publicOnly: false` and selects a function
 * expression in every position, and a block comment attached to an inline argument is moved by
 * Prettier onto the preceding expression, which detaches it from what it documents.
 * @param {AuthenticationRequiredListener} listener - The listener to tell.
 * @param {ApiRequestError} failure - The normalised 401 that invalidated the session.
 * @returns {void} Nothing; the delivery is scheduled, not awaited.
 */
function scheduleAuthenticationSignal(
  listener: AuthenticationRequiredListener,
  failure: ApiRequestError,
): void {
  /**
   * Calls the listener once the current rejection has been handed to its caller.
   * @returns {void} Nothing; the listener acts on its own state.
   */
  function deliver(): void {
    listener(failure);
  }
  queueMicrotask(deliver);
}

/**
 * Discards a session the services have stopped accepting, and tells whoever asked to be told.
 *
 * Assumptions: the token is discarded only when the failed request actually CARRIED one. The sign-on
 * contract publishes 401 for a rejected credential — one status deliberately covering both a wrong
 * password and an unknown identifier, so the endpoint cannot be used to discover which — so treating
 * every 401 as an expired session would report "signed out" to an operator mistyping a password at
 * the sign-on screen, and would clear a session that request never used.
 *
 * Assumptions: 403 is left ALONE and that is the point of testing for 401 exactly. A 403 is a valid
 * token refused a particular route, which is what an ordinary operator reaching an administrative
 * screen receives; discarding the session for it would sign out a user who is correctly signed in,
 * and they would sign straight back in and meet the same refusal.
 *
 * Trade-offs: listeners are notified in a microtask rather than inline. A listener that throws would
 * otherwise replace the failure the caller is waiting for with its own, so the screen that asked for
 * an account would receive an unrelated error; deferring keeps this function's contract — reject with
 * the normalised failure — independent of any listener's behaviour, and a listener's own defect still
 * surfaces, attributed to itself. The cost is that a listener runs after the rejection reaches the
 * caller, which is harmless because the session state it reads was already cleared here.
 * @param {TransportFailure} failure - The rejected Axios failure, read for the header it carried.
 * @param {ApiRequestError} normalised - The normalised failure, passed on to each listener.
 * @returns {void} Nothing; the effect is the discarded token and the notified listeners.
 */
function invalidateSessionOnUnauthorized(
  failure: TransportFailure,
  normalised: ApiRequestError,
): void {
  const carriedBearer = headerValue(failure.config?.headers, AUTHORIZATION_HEADER) !== undefined;
  if (normalised.status !== UNAUTHORIZED_STATUS || !carriedBearer) {
    return;
  }
  setAccessToken(null);
  // WHY : Assumptions: the registry is copied before it is walked, so a listener that unsubscribes in
  //       response to the signal cannot shorten the sequence of listeners still to be told.
  for (const listener of [...authenticationRequiredListeners]) {
    scheduleAuthenticationSignal(listener, normalised);
  }
}

/**
 * Anchors the server clock from a failed request, then rejects with the normalised failure.
 *
 * Trade-offs: an ERROR response still came from the server and still carries its `Date`, so it is
 * used as an anchor rather than discarded. Skipping it would drop a good anchor precisely when a
 * session is having trouble and making the most requests.
 *
 * Trade-offs: a rejection that is NOT an Axios failure is re-thrown exactly as it arrived. That is
 * the only path out of this module that does not carry the normalised shape, and it is deliberate: a
 * failure reaching here without a request having been attempted is a defect in this module's own
 * request preparation — a `RangeError` from a path template, a configuration `Error` — and wrapping
 * it in a problem document would present a programming mistake to a screen as a service refusal,
 * losing both its type and its stack. `reporting.test.ts` asserts that a client-side guard still
 * reaches its caller as a `RangeError` for exactly this reason.
 *
 * Alternatives Considered: `Promise.reject(...)`, and an `async` function that throws. The first is
 * refused by `prefer-promise-reject-errors` for the pass-through branch, whose reason is `unknown`;
 * the second is refused by `require-await`, because it would contain no `await`. A SYNCHRONOUS throw
 * satisfies both with no exemption: Axios invokes this handler inside its own promise chain, so a
 * throw here becomes a rejected promise carrying this exact reason.
 * @param {unknown} failure - Whatever Axios rejected with; not necessarily an `Error`.
 * @returns {never} Never returns normally.
 * @throws {ApiRequestError} For every transport failure, carrying the classification and the problem
 *   document.
 * @throws {unknown} Unchanged, for a rejection raised before a request was attempted.
 */
function normaliseFailureAndThrow(failure: unknown): never {
  if (!axios.isAxiosError<unknown, unknown>(failure)) {
    throw failure;
  }
  recordServerDate(headerValue(failure.response?.headers, 'Date'));
  const normalised = normaliseFailure(failure);
  invalidateSessionOnUnauthorized(failure, normalised);
  throw normalised;
}

/**
 * Reports whether a caught value is one of this client's normalised failures.
 *
 * Assumptions: this is the guard a `catch` block uses before reading `problem` or `kind`, and it is
 * an `instanceof` test rather than a shape probe because this module is the only producer — a value
 * that merely looks like one did not come from here and should not be read as though it had.
 * @param {unknown} value - A caught value of unknown provenance.
 * @returns {boolean} `true` when the value is an {@link ApiRequestError}, narrowing it to that type.
 */
export function isApiRequestError(value: unknown): value is ApiRequestError {
  return value instanceof ApiRequestError;
}

/**
 * Reports whether a caught value is the optimistic-concurrency refusal, HTTP 409.
 *
 * Refactoring Rationale: 409 is singled out because it is the one refusal that is not the operator's
 * mistake, and the baseline already performs the check it reports. The account-update program
 * snapshots a complete pre-edit before-image — `05 ACUP-OLD-DETAILS.` at `app/cbl/COACTUPC.cbl` L669,
 * holding each numeric as a display field with a numeric `REDEFINES`, the account identifier at L671
 * to L673 and the balance at L675 to L677 — and carries
 * `05 WS-DATACHANGED-FLAG PIC X(1).` at L168 with `88 CHANGE-HAS-OCCURRED VALUE '1'.` at L170, so a
 * record altered across the screen turn is detected before the rewrite and reported rather than
 * overwritten. The target expresses the same check as a JPA `@Version` column, and the
 * `OptimisticLockException` it raises surfaces here as 409 carrying the baseline's own sentence at
 * L521 to L522, `Record changed by some one else. Please review` — two words in "some one", carried
 * character for character in the document this error holds and never reworded by a client.
 *
 * Alternatives Considered: letting each screen compare `status` with `409` itself, which needs no
 * export. Rejected because the literal would then appear in every screen that writes, and the one
 * that omitted it would show a concurrent change as an ordinary failure — losing behaviour the parity
 * requirement protects, with nothing failing to say so.
 * @param {unknown} value - A caught value of unknown provenance.
 * @returns {boolean} `true` when the value is an {@link ApiRequestError} whose transport status is
 *   409, narrowing it to that type.
 */
export function isConflictFailure(value: unknown): value is ApiRequestError {
  return isApiRequestError(value) && value.status === CONFLICT_STATUS;
}

/**
 * Returns the singleton API client after validating build-time configuration.
 * @returns {AxiosInstance} Configured Axios client.
 * @throws {Error} If the endpoint, timeout or correlation header is invalid.
 */
export function getApiClient(): AxiosInstance {
  if (client === undefined) {
    // WHY : Assumptions: the default `transformResponse` is left in place and NOTHING is installed
    //       beside it, which is the single most consequential omission in this file. A monetary amount
    //       arrives as a JSON string -- `05 ACCT-CURR-BAL PIC S9(10)V99.` at `app/cpy/CVACT01Y.cpy` L7
    //       is a zoned-decimal field held server-side as `NUMERIC(p,2)` and put on the wire as text by
    //       `com.carddemo.common.money.MoneyModule` -- and `JSON.parse` leaves a string a string. A
    //       replacement transform, a reviver, or any read of an amount as a number would convert it to
    //       an IEEE-754 double, and the failure that follows is the worst kind available: a statement
    //       balance a cent wrong looks exactly like one that is right.
    //       Assumptions: identifiers are strings for the same arithmetic reason, not for tidiness. A
    //       card number is sixteen digits -- `CC-CARD-NUM PIC X(16)` at `app/cpy/CVCRD01Y.cpy` L37,
    //       redefined as `PIC 9(16)` at L39 -- and sixteen nines exceed `Number.MAX_SAFE_INTEGER`, so
    //       a coerced card number can compare equal to a different card number.
    client = axios.create({
      baseURL: apiBaseUrl(),
      timeout: apiTimeoutMs(),
      headers: { Accept: 'application/json' },
    });
    client.interceptors.request.use(applyRequestHeaders);
    // WHY : Assumptions: a RESPONSE interceptor rather than a per-call site, so the server clock is
    //       re-anchored and every failure is normalised by every operation without any caller knowing
    //       either happens. The header band displays a paint-time instant, and re-anchoring on each
    //       response keeps the elapsed term small, which is what bounds the accumulated error of a
    //       long-lived tab.
    //       Trade-offs: the error path re-anchors too. An error response is still a response from the
    //       server and still carries a `Date`, so skipping it would discard a good anchor precisely
    //       when a session is having trouble.
    client.interceptors.response.use(anchorClockFromResponse, normaliseFailureAndThrow);
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
 *
 * Alternatives Considered: this module OWNS the token and `ui/src/hooks/useAuth.ts` calls in, rather
 * than this module importing an accessor from that hook. The direction is what keeps the graph
 * acyclic: the hook already imports this module for the client every screen calls through, so an
 * import back would close a cycle between a React hook and the module its own requests are dispatched
 * by. It also keeps the writer single — the request interceptor reads exactly the key this function
 * writes, so a sign-out has one place to happen and the interceptor cannot observe a token some other
 * module stored under a name of its own.
 *
 * Assumptions: the store is `sessionStorage` rather than `localStorage`, so the token is scoped to the
 * tab and is discarded when it closes; a token surviving in `localStorage` would outlive the operator's
 * session on a shared workstation, which is the setting this application is used in.
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
