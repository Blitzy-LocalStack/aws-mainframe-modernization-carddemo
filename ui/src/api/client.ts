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
import type {
  AxiosInstance,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios';

import { runtimeApiBaseUrl } from './runtimeConfig';
import { recordServerDate } from './serverClock';
import type {
  AbendDetail,
  ApiError,
  ContractOperation,
  FieldError,
  FieldValidationState,
  PageDirection,
  Severity,
  Subsystem,
} from './types';

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

/*
 * WHY : Refactoring Rationale: the request configuration is augmented with ONE optional member rather
 *       than a second axios instance being created for the unauthenticated operations. Three requests
 *       in the whole application are declared `security: []` by their contract -- the sign-on, the
 *       token refresh and the challenge answer -- and each of them was nevertheless dispatched with
 *       whatever bearer this tab happened to hold, because the interceptor attaches one to everything.
 *       A held token that has expired or been revoked is therefore processed by the resource-server
 *       filter BEFORE the permit-all rule for these paths is reached, so a stale credential could
 *       refuse the very exchange whose purpose is to replace it -- and the operator would read
 *       "unauthorized" at the sign-on screen having presented nothing.
 * WHY : Alternatives Considered: a second axios instance with no authentication interceptor, which is
 *       the obvious shape. Rejected because the other four boundaries this module owns -- the
 *       correlation identifier, the clamped timeout, the failure normalisation and the server-clock
 *       anchoring -- would then exist twice, and the copy that fell behind would do so silently: the
 *       concrete failure is a sign-on whose requests carry no correlation identifier, which reports
 *       nothing and simply stops joining traces up.
 * WHY : Alternatives Considered: classifying by TARGET inside the interceptor, matching the three
 *       token-exchange paths. Rejected because it would put a copy of the contract's own security
 *       declaration inside this module, keyed by path text: an operation renamed on the service side
 *       would leave the classification silently stale, and this module deliberately knows no endpoint.
 *       Metadata supplied by the caller keeps the decision beside the operation that owns it.
 * WHY : Assumptions: the member is unknown to axios, which carries unknown configuration members
 *       through its merge untouched and puts none of them on the wire, so the flag reaches the
 *       interceptor and never reaches a service. It is declared through a module augmentation rather
 *       than smuggled in as a sentinel header, because a sentinel would have to be removed again and
 *       the one that was not removed would be sent.
 */
declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * Whether this request must be dispatched WITHOUT the session token this module stores.
     *
     * Assumptions: absent and `false` mean the same thing -- attach the stored token if there is one --
     * so every existing call site keeps its behaviour with no change. Only `true` suppresses, and it
     * suppresses by REMOVING the header rather than by declining to add one, so a header inherited
     * from the instance's own defaults cannot survive the suppression.
     */
    carddemoOmitStoredSession?: boolean;
  }
}

/**
 * The request configuration the three unauthenticated token exchanges are dispatched with.
 *
 * Assumptions: frozen and shared rather than constructed per call, because axios merges a supplied
 * configuration into a fresh object and never writes back into it, so one immutable value is safe for
 * every caller and makes "this request carries no session" a single named fact rather than a boolean
 * repeated at three call sites.
 */
export const WITHOUT_STORED_SESSION: Readonly<AxiosRequestConfig> = Object.freeze({
  carddemoOmitStoredSession: true,
});

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
 * The widest correlation identifier the services will carry, twenty-four characters.
 *
 * Assumptions: this is not a preference here. It is `CORRELATION_ID_MAX_LENGTH` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`, and a longer
 * value is refused with HTTP 400 before any handler runs, so a request carrying one never reaches the
 * operation it was issued for.
 */
const CORRELATION_ID_MAX_LENGTH = 24;

/**
 * The two-character prefix every generated correlation identifier begins with.
 *
 * Assumptions: this is `GENERATED_ID_PREFIX` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`, reproduced
 * here character for character. It is not decoration and it is not a provenance signal: it is the
 * one thing that guarantees a generated identifier carries a letter, and a value carrying a letter
 * at any position is admitted by that filter's `isAccountNumberShaped` test without its digits
 * being counted at all.
 *
 * Refactoring Rationale: this client used to mint twenty-four BARE hexadecimal characters and the
 * comment here argued the prefix was deliberately not imitated, because "a browser that stamped
 * `CD` would claim that provenance falsely". That argument was answered by the contract it was
 * reasoning about. The filter refuses an inbound identifier consisting only of digits and accepted
 * separators once its digits number thirteen or more — `ACCOUNT_NUMBER_MIN_DIGITS`, the shortest
 * primary account number in circulation — because a conforming identifier is published to the
 * mapped diagnostic context and therefore onto every log line. Twelve random bytes rendered as
 * hexadecimal are all digits whenever every byte falls in one of the ten decimal-only ranges, which
 * is (100/256)^12, roughly one generated identifier in seventy-eight thousand. Each of those was
 * answered HTTP 400 before the handler ran, on a request the operator had made correctly, and the
 * failure was indistinguishable at the screen from a rejected payload.
 *
 * Trade-offs: the cost accepted is that a browser-minted identifier is no longer distinguishable
 * from a service-minted one by inspection. That is a real loss and it is the smaller one: the
 * provenance was only ever readable by a human reading a log, whereas the refusal broke requests.
 * Nothing in the migrated services branches on the prefix — it appears in `CorrelationIdFilter`
 * alone, as the value that mint prepends and as the reason its own identities are never
 * account-number-shaped — so imitating it changes no behaviour beyond removing the refusal.
 */
const CORRELATION_ID_PREFIX = 'CD';

/**
 * Bytes of entropy each generated correlation identifier carries.
 *
 * Assumptions: eleven, and the number is DERIVED rather than chosen, exactly as
 * `GENERATED_ID_RANDOM_BYTES` derives it on the service side: the services accept at most
 * twenty-four characters — {@link CORRELATION_ID_MAX_LENGTH}, mirrored from the same filter — the
 * prefix consumes
 * two of them, and hexadecimal renders two characters per byte, so `(24 - 2) / 2` bytes fill the
 * remainder exactly. Stating the arithmetic rather than the literal is what keeps this value
 * correct if either the width or the prefix ever moves.
 */
const CORRELATION_ID_ENTROPY_BYTES = (CORRELATION_ID_MAX_LENGTH - CORRELATION_ID_PREFIX.length) / 2;

/**
 * The exact number of characters a generated correlation identifier occupies.
 *
 * Assumptions: this is asserted by `client.test.ts` rather than merely documented, because it is
 * the width the services bound and a value one character wider is refused with HTTP 400 before any
 * handler runs.
 */
export const CORRELATION_ID_LENGTH =
  CORRELATION_ID_PREFIX.length + CORRELATION_ID_ENTROPY_BYTES * 2;

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
 * logging context, so traces stop joining up silently.
 *
 * ⚠️ Assumptions: the variable does NOT let the two halves of that contract move independently, and an
 * earlier note here claiming it spared a rebuilt bundle was wrong on both halves. The server's name is a
 * `public static final String` literal, deliberately not configurable — the constant's own Javadoc
 * records that a header name differing between two deployments is how the contract breaks silently — so
 * a rename there is a source change and a redeploy. And `import.meta.env` is inlined by Vite at BUILD
 * time, as `./runtimeConfig.ts` records for the base URL, so the browser half of a rename needs a rebuilt
 * bundle whatever this variable holds. Trade-offs: what the variable actually buys is that the browser
 * half is then a build setting rather than an edit to this module, and that a mistyped name fails loudly
 * at startup on the token check below instead of joining the silent-mismatch failure mode above. What it
 * costs is the appearance of a deploy-time knob, which is why the limit is written down here.
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
 * side.
 *
 * Refactoring Rationale: the construction is now the filter's own, prefix included, where an
 * intermediate revision minted twenty-four BARE hexadecimal characters. Matching the width alone was
 * not enough, and the residue was a rare refusal rather than a cosmetic difference: see
 * {@link CORRELATION_ID_PREFIX} for the measured rate and for why the provenance argument that kept
 * the prefix off was the weaker side of the trade.
 *
 * Alternatives Considered: keeping the bare form and re-minting whenever the generated value turned
 * out to be all digits. Rejected because it makes the guarantee probabilistic and untestable — a
 * property test could only sample it — where prepending a letter makes the refusal
 * unrepresentable by construction, which is what `client.test.ts` asserts exhaustively over the
 * whole byte domain.
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
 * @returns {string} Exactly {@link CORRELATION_ID_LENGTH} characters: the two-character
 *   {@link CORRELATION_ID_PREFIX} followed by upper-case hexadecimal, and therefore never a value
 *   the services classify as account-number-shaped.
 */
export function newCorrelationId(): string {
  const entropy = new Uint8Array(CORRELATION_ID_ENTROPY_BYTES);
  crypto.getRandomValues(entropy);
  return (
    CORRELATION_ID_PREFIX +
    Array.from(
      entropy,
      /**
       * Renders one byte as two upper-case hexadecimal characters.
       * @param {number} byte - One byte of entropy, 0 through 255.
       * @returns {string} Its two-character upper-case hexadecimal rendering, zero-padded so that
       *   every byte contributes exactly two characters and the total length is fixed.
       */
      (byte: number): string => byte.toString(16).toUpperCase().padStart(2, '0'),
    ).join('')
  );
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
 * rather than one carrying an empty or undefined bearer. ⚠️ It is normal because THREE published
 * operations are reached before any token exists — `signOn`, `answerSignOnChallenge` and
 * `refreshTokens`, the only three operations in the whole published surface that declare `security: []`
 * in `services/auth-service/src/main/resources/openapi/auth-api.yaml`, each presenting its credential in
 * the request body instead. A service reading `Authorization: Bearer undefined` would refuse any of them
 * as a malformed credential — reporting a rejected token where the operator has not yet presented one,
 * or, on the refresh, signing an operator out for arriving a moment late.
 *
 * Refactoring Rationale: the stored token is attached CONDITIONALLY on the request's own metadata,
 * and the condition is checked first. A request marked {@link WITHOUT_STORED_SESSION} has the header
 * removed rather than merely not added, which is what makes the guarantee hold for the three token
 * exchanges however their configuration was composed: the reasoning for the flag, and the two
 * alternatives rejected in its favour, are recorded on its declaration above.
 *
 * Assumptions: the correlation identifier is attached to EVERY request including the suppressed ones.
 * It carries no credential and confers no authority — it is a name for a unit of work — so an
 * unauthenticated exchange is exactly as much in need of being traceable as any other, and a sign-on
 * that could not be found in the logs would be the one failure an operator reports most often.
 * @param {InternalAxiosRequestConfig} config - Axios request configuration being dispatched.
 * @returns {InternalAxiosRequestConfig} The same configuration with bounded security headers
 *   applied.
 */
function applyRequestHeaders(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  if (config.carddemoOmitStoredSession === true) {
    config.headers.delete(AUTHORIZATION_HEADER);
  } else {
    const token = sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
    if (token !== null && token.length > 0) {
      config.headers.set(AUTHORIZATION_HEADER, `Bearer ${token}`);
    }
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
 * Matches a media type that carries a JSON document, including the problem-document variant.
 *
 * Assumptions: the test is on the type and subtree rather than on an exact string, because a service
 * may answer `application/json`, `application/json;charset=UTF-8` or `application/problem+json`, and
 * all three carry the document {@link isApiError} narrows. Parameters after the semicolon are ignored
 * rather than parsed: nothing here reads the charset, since the decode below goes through the
 * platform's own text reader.
 */
const JSON_MEDIA_TYPE = /^application\/(?:[\w.+-]+\+)?json(?:\s*;.*)?$/iu;

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
 * Assumptions: the PRODUCTION subscriber is `ui/src/hooks/useAuth.ts`, which is the module that owns
 * every session key and is held by the route guards. That placement matters rather than being an
 * arrangement detail: this module owns the access token alone, so the signal is the only way the
 * identity token, the retained identifier and the refresh token get discarded when the services stop
 * accepting the session. A signal with no subscriber left the browser holding a signed claim and a
 * refresh token for a session that had already been refused, and reporting itself signed on.
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

/** Trailing digits a card-number-width run keeps, matching the masked rendering every row carries. */
/** The character a withheld digit is overwritten with, matching the service's own rendering. */
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
 *
 * Assumptions: `path` is a MASKED template and never a dispatched target. The member is enumerable and
 * travels with the document into whatever a screen or an error sink does with a caught failure, so a
 * concrete target here would carry an account identifier, a card number, a cursor or a selector into a
 * console or a bug report for a failure no service saw. `maskedTarget` is the one place that reduction
 * happens.
 * @param {string} code - One of this module's `CARDDEMO-UI-` codes.
 * @param {number} status - HTTP status received, or `0` when none was.
 * @param {string} correlationId - Identifier the request was carried out under, possibly empty.
 * @param {string} path - Request target the failure concerns, relative to the configured base URL,
 *   already narrowed by {@link maskedTarget}. Callers must not pass a raw target: this
 *   document is enumerable and reachable from a caught error, so an unnarrowed selector reaching
 *   here is an unnarrowed selector reaching an error reporter.
 * @returns {ApiError} A complete problem document carrying no invented field entries, no
 *   fabricated instant and no protected identifier.
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
 * Narrows a response body to the two members needed to read a `Blob`, without an `instanceof` test.
 *
 * Assumptions: the test is STRUCTURAL, and `body instanceof Blob` is deliberately not used. That test is
 * false for a genuine blob whenever the value was constructed in a different realm from the one this
 * module's `Blob` binding resolves in, and the test environment is exactly such a case: `ui/vitest.config.ts`
 * runs the suite in jsdom, whose `Blob` global is jsdom's own, while the body a failed request carries is
 * built by the platform `fetch` implementation underneath it. Measured: the constructor of that value
 * reports `Blob` and carries both `type` and `text`, and `instanceof` still answers false -- so an
 * `instanceof` guard would leave the decode below dead in every test while working in a browser, which is
 * the worst arrangement available: a gate that passes because it never runs.
 *
 * Assumptions: the two members tested are the two this module uses -- the declared media type and the
 * text reader -- so the narrowing asserts exactly what it consumes rather than an identity. A value
 * carrying both is readable as a document whatever built it.
 * @param {unknown} body - A response body of unknown shape.
 * @returns {Blob | undefined} The same value narrowed to a `Blob`, or nothing when it is not one.
 */
function asBlobLike(body: unknown): Blob | undefined {
  if (typeof body !== 'object' || body === null) {
    return undefined;
  }
  const candidate = body as { type?: unknown; text?: unknown };
  if (typeof candidate.type !== 'string' || typeof candidate.text !== 'function') {
    return undefined;
  }
  return body as Blob;
}

/**
 * Recovers the body of a failed response in a form {@link isApiError} can narrow.
 *
 * Purpose: two operations in this package ask for their body as a `Blob`, because they collect a
 * document whose bytes must not be decoded. Axios materialises EVERY body of such a request according
 * to that setting, including the ones a service sends to refuse it — so a JSON problem document
 * arrives as a `Blob` rather than as an object.
 *
 * Refactoring Rationale: ⚠️ without this step those two operations lost every refusal a service
 * described. The document arrived as a `Blob`, failed the object test in {@link isApiError} for the
 * shape of its container rather than for its contents, and was replaced by a synthesised
 * `CARDDEMO-UI-BODY` — discarding the service's code, its sentence, its `fieldErrors` array and its
 * abend detail, and reporting a described refusal as an unrecognised response. A 400 naming the
 * offending parameter became "the body was not a problem document".
 *
 * Assumptions: only a JSON-typed `Blob` is decoded, and the media type is read from the `Blob` itself
 * with the response header as the fallback — Axios copies the header onto the `Blob`, but a transport
 * that left it blank would otherwise stop the decode. A `Blob` of any other type is returned
 * untouched, which is deliberate on two counts: a successful octet-stream body never reaches this
 * function at all, and an error body that really is bytes is not read into memory in order to discover
 * that it is not a document.
 *
 * Assumptions: a decode that fails for any reason yields the original value, so the caller still
 * synthesises its `CARDDEMO-UI-BODY` document. Malformed JSON and an unreadable `Blob` are the same
 * outcome from a caller's point of view -- a response whose body cannot be read as a problem -- and
 * distinguishing them would add a classification no screen can act on differently.
 *
 * Trade-offs: this makes the rejection path asynchronous, which is why {@link normaliseFailureAndThrow}
 * is an `async` function. The cost is one microtask on every failure, including the ones that need no
 * decoding; the alternative was decoding inside the two calling operations, which would put the shared
 * problem contract in two client modules and leave a third binary call added later without it.
 * @param {TransportFailure} failure - The rejected Axios failure, with a response or without one.
 * @returns {Promise<unknown>} The body as received, or the value parsed out of a JSON-typed `Blob`.
 */
async function failureBody(failure: TransportFailure): Promise<unknown> {
  const body: unknown = failure.response?.data;
  const blob = asBlobLike(body);
  if (blob === undefined) {
    return body;
  }

  const declared =
    blob.type.length > 0
      ? blob.type
      : (headerValue(failure.response?.headers, 'Content-Type') ?? '');
  if (!JSON_MEDIA_TYPE.test(declared)) {
    return body;
  }

  try {
    return JSON.parse(await blob.text()) as unknown;
  } catch {
    // Assumptions: the guard covers the read as well as the parse, because a body whose backing data is
    //   gone rejects rather than returning malformed text, and a rejection escaping here would replace
    //   the failure the caller is waiting for with an unrelated one.
    return body;
  }
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
 * @param {unknown} body - The response body as {@link failureBody} recovered it, which is the received
 *   value for every ordinary request and the decoded document for a `Blob`-typed request a service
 *   refused. It is passed in rather than read from the failure because recovering it is asynchronous
 *   and this classification is not.
 * @returns {ApiRequestError} The normalised failure, classified into one of four kinds.
 */
function normaliseFailure(failure: TransportFailure, body: unknown): ApiRequestError {
  const correlationId = correlationIdOf(failure);
  // WHY : Assumptions: the target is withheld HERE, at the one place it enters a document a caller
  //       can serialise, rather than at each of the two construction sites below. Both sites reach the
  //       same variable, so narrowing it once is what makes "no synthetic problem carries a raw
  //       selector" a property of this function instead of a rule two call sites have to remember, and
  //       it means no later edit can introduce a path that skipped the step.
  // WHY : Alternatives Considered: narrowing by the SERVER's rule instead -- mask each digit run of
  //       nine or more, keep the last four of a card-width run, preserve length -- so that a browser
  //       diagnostic lines up character for character against a gateway access record. Rejected as the
  //       weaker of the two: it withholds digits only, so a non-numeric selector such as a sealed
  //       cursor, and any query or fragment, would survive it, whereas the operation-template mask
  //       admits a segment only where every template that could have composed the target declares a
  //       literal, and drops the query and fragment outright. The cost accepted is that a masked
  //       browser target no longer has the same length as the one the gateway logged.
  const target = maskedTarget(failure.config?.url ?? '');
  const response = failure.response;

  if (response !== undefined) {
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
 * Assumptions: what is discarded HERE is the access token alone, because that is the only session
 * value this module owns and writes. The identity token, the retained identifier and the refresh token
 * are `ui/src/hooks/useAuth.ts`'s, and the signal below is what tells that module to discard them —
 * so the complete sign-out is the two halves together, and neither half is sufficient. Reaching into
 * the other module's keys from here would put two writers on one session and leave a sign-out
 * ambiguous about which of them had to run.
 * @param {TransportFailure} failure - The rejected Axios failure, read for the header it carried.
 * @param {ApiRequestError} normalised - The normalised failure, passed on to each listener.
 * @returns {void} Nothing; the effect is the discarded access token and the notified listeners, each
 *   of which completes the sign-out for the state it owns.
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
 * Refactoring Rationale: ⚠️ this handler is `async`, and it was synchronous. The reason it changed is
 * {@link failureBody}: the two document operations ask for their body as a `Blob`, so a service's
 * problem document arrives on those two as a `Blob` and has to be READ before it can be recognised,
 * and reading one is asynchronous. The paragraph withdrawn from here argued for a synchronous throw on
 * the ground that `Promise.reject` is refused by `prefer-promise-reject-errors` for the pass-through
 * branch and an `async` function with no `await` is refused by `require-await`. The first half still
 * holds and is why nothing here calls `Promise.reject`; the second no longer applies, because this
 * function now awaits the body recovery on every path. A `throw` inside an `async` function is a
 * rejected promise carrying this exact reason, so the contract every caller sees is unchanged.
 * @param {unknown} failure - Whatever Axios rejected with; not necessarily an `Error`.
 * @returns {Promise<never>} Never resolves; the returned promise always rejects.
 * @throws {ApiRequestError} For every transport failure, carrying the classification and the problem
 *   document.
 * @throws {unknown} Unchanged, for a rejection raised before a request was attempted.
 */
async function normaliseFailureAndThrow(failure: unknown): Promise<never> {
  if (!axios.isAxiosError<unknown, unknown>(failure)) {
    throw failure;
  }
  recordServerDate(headerValue(failure.response?.headers, 'Date'));
  // Assumptions: the body is recovered before the classification and on EVERY failure, not only on the
  //   two operations that request a `Blob`. A conditional recovery would have to know which requests
  //   asked for one, which is per-call configuration this handler deliberately does not read -- and the
  //   recovery is a no-op for every body that is not a JSON-typed `Blob`.
  const normalised = normaliseFailure(failure, await failureBody(failure));
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
 * @returns {void} Nothing; the memoized instance is discarded. The next `getApiClient` call constructs
 *   a fresh one from whatever configuration is loaded at that moment, so nothing is returned here for
 *   a caller to hold -- holding the discarded instance is exactly what this exists to prevent.
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

/**
 * Every operation template a target has been composed for, each already split into its segments.
 *
 * Purpose: this is the vocabulary {@link maskedTarget} narrows against. An entry is the contract path of
 * one operation with the version prefix removed and its placeholders intact, for example
 * `/auth/users/{userId}`, so a masked target can be derived from the TEMPLATE the request addressed
 * rather than from the values it carried.
 *
 * Assumptions: it is populated by {@link requestPath}, which is the one function that composes a target
 * in this package -- measured, forty-nine call sites across the seven client modules dispatch all
 * fifty-three published operations, the difference being `browse` in `reference.ts`, one generic helper
 * that composes five list operations from the operation it is given -- and the operation arrives there as
 * an ARGUMENT. That is what makes the registry dependency-neutral: this module learns each template
 * without importing the module that declares it, so there is no import edge from here to the seven
 * clients and no cycle. It is also what makes the registry sufficient at the moment it is read, because a
 * target can only reach a failure after `requestPath` composed it.
 *
 * Assumptions: bounded by the number of distinct path TEMPLATES the application composes -- measured,
 * forty-two across the seven manifests, fewer than the fifty-three operations because an entry is keyed
 * by the template and a path publishing both a read and a write contributes one. Traffic does not enter
 * into it: a thousand card reads add one entry. Nothing is ever removed, and {@link resetApiClient}
 * deliberately does not clear it: a template is contract data, not configuration, so discarding it
 * between clients would only make masking depend on which requests a session happened to make first.
 *
 * Refactoring Rationale: ⚠️ this registry REPLACES a set of published literal segments that masking used
 * as an allow-list, keeping any segment whose VALUE appeared in it. A review established that as an
 * information disclosure: a path parameter admitting arbitrary text -- `UserIdPath` in `auth-api.yaml`
 * permits one to eight printable characters, case-folded -- carries values that can equal a route word,
 * so `getUser('admin')` dispatched `/auth/users/admin`, every segment was allow-listed, and the real
 * identifier survived into an enumerable `ApiError.path` on any network, proxy or unexpected-body
 * failure. The rejected reasoning behind the allow-list held that recovering the template would close an
 * import cycle; it would have, read as an import, and this registry gets the same information by
 * inversion instead.
 */
const composedOperationTemplates = new Map<string, readonly string[]>();

/**
 * Records one operation template so a failure on it can be reported without its values.
 *
 * Assumptions: the split is done once, when the template is first seen, rather than on each failure. A
 * failure path is the worst place to do avoidable work, and the segments of a template never change.
 * @param {string} template - The operation's contract path with the version prefix removed and its
 *   placeholders intact.
 * @returns {void} Nothing; the effect is the recorded template.
 */
function rememberOperationTemplate(template: string): void {
  if (!composedOperationTemplates.has(template)) {
    composedOperationTemplates.set(template, template.split('/'));
  }
}

/**
 * What stands in a masked target where a value stood.
 *
 * Assumptions: one generic marker rather than the parameter's published name -- `{accountId}`,
 * `{selector}` and so on. The name is available now that masking works from templates, and it is still
 * not used: a target may match more than one template, and two templates can publish different names
 * for the same position, so a name would have to be chosen from among them. Alternatives Considered:
 * rendering the matched template's own placeholder text where exactly one template matches and this
 * marker otherwise. Rejected because a diagnostic would then vary in kind with how many operations share
 * a shape, which is a fact about the contract rather than about the failure, and what a reader needs is
 * that a value stood in that position.
 */
const MASKED_SEGMENT = '{id}';

/** Matches a template segment that stands for a value rather than for a published literal. */
const TEMPLATE_PLACEHOLDER_SEGMENT = /^\{[A-Za-z][A-Za-z0-9]*\}$/u;

/**
 * Reports whether one recorded template could have produced the given target segments.
 *
 * Assumptions: a template matches when it has the same number of segments and every LITERAL segment of
 * it equals the target's segment in that position; a placeholder position matches anything, because a
 * value is percent-encoded by {@link requestPath} and so can never introduce a segment boundary of its
 * own. Comparing segment counts first is what makes that true: the target's shape is the template's
 * shape, so position `n` of one describes position `n` of the other.
 * @param {readonly string[]} template - One recorded template, already split into segments.
 * @param {readonly string[]} segments - The dispatched target's segments, free of query and fragment.
 * @returns {boolean} `true` when the target could have been composed from that template.
 */
function templateCouldHaveComposed(
  template: readonly string[],
  segments: readonly string[],
): boolean {
  if (template.length !== segments.length) {
    return false;
  }
  return template.every(
    /**
     * Reports whether one template segment admits the target segment in the same position.
     * @param {string} candidate - One template segment, literal or placeholder.
     * @param {number} index - Its position, shared with the target's segments.
     * @returns {boolean} `true` for a placeholder, or for a literal equal to the target's segment.
     */
    (candidate: string, index: number): boolean =>
      TEMPLATE_PLACEHOLDER_SEGMENT.test(candidate) || candidate === segments[index],
  );
}

/**
 * Reduces a request target to the masked template of the operation it addressed.
 *
 * Purpose: the problem document this module SYNTHESISES is enumerable and reaches whatever a screen or
 * an error sink does with a caught failure. A target carries account identifiers, card numbers,
 * transaction identifiers, sealed cursors, opaque artifact selectors and query values, so copying one
 * into that document would put every one of them wherever the document goes -- a browser console, a
 * bug report, a support attachment -- for a failure no service ever saw.
 *
 * Refactoring Rationale: ⚠️ a segment is kept only where the TEMPLATE the request addressed declares a
 * literal, and it used to be kept wherever the segment's own VALUE appeared in a set of published route
 * words. That test was an information disclosure and not merely a loose approximation: a path parameter
 * whose domain includes route words -- a user identifier is one to eight printable characters, folded to
 * upper case -- produces a target every segment of which is allow-listed, so `getUser('admin')` reported
 * `/auth/users/admin` and disclosed the very identifier the mask exists to withhold. Deciding by
 * position removes the whole class: a value cannot be mistaken for a literal, because nothing about the
 * value is consulted.
 *
 * Assumptions: where more than one template matches, a position is kept only if EVERY matching template
 * declares a literal there. Measured, four published positions genuinely collide -- `/cards/lookup` and
 * `/cards/search` with `/cards/{cardKey}`, `/authorizations/search` with `/authorizations/{key}`, and
 * `/transactions/copy-last` with `/transactions/{transactionId}` -- so a target such as `/cards/lookup`
 * is ambiguous between a literal route and a parameter whose value happens to be that word. Masking the
 * ambiguity costs a route name in a diagnostic; keeping it would restore the disclosure this change
 * removes, on exactly the values an attacker would choose. The census is pinned by
 * `theMaskedLiteralCensusIsUnchanged` in `ui/src/api/contracts.test.ts`, so a new collision arrives as a
 * failing case rather than as a silently less precise diagnostic.
 *
 * Assumptions: a target matching NO recorded template has every non-empty segment masked. Every target
 * this package dispatches is composed by {@link requestPath}, which records its template first, so an
 * unmatched target is one no operation of this application produced -- and about such a target nothing
 * is known, so nothing in it can be asserted to be a literal. Trade-offs: the route name is lost in that
 * case, which is accepted because the alternative is guessing which of its segments are safe.
 *
 * Assumptions: the query and the fragment are DROPPED rather than masked. A masked query would still
 * disclose which parameters were sent and how many, and no diagnostic needs that: the operation is
 * identified by its path, and the parameters a screen sent are the screen's own to report.
 *
 * Assumptions: this is deliberately STRICTER than the server's own rule, and the two are answering
 * different questions. `GlobalExceptionHandler` in `services/common-lib` narrows a card number inside a
 * path and leaves other segments alone, because its `path` travels back to the caller that composed the
 * target and has to stay recognisable as the request that was made. A document synthesised here is a
 * local diagnostic no service produced, so it can carry the template alone -- and the server's
 * card-number rule would not touch an account identifier, a cursor or a selector, all of which reach
 * this function.
 * @param {string} target - The request target as dispatched, relative to the configured base URL.
 * @returns {string} The same path with every value segment replaced by {@link MASKED_SEGMENT} and with
 *   no query and no fragment; the empty string when no target was recorded.
 */
function maskedTarget(target: string): string {
  const withoutFragment = target.split('#', 1)[0] ?? '';
  const withoutQuery = withoutFragment.split('?', 1)[0] ?? '';
  const segments = withoutQuery.split('/');

  const candidates = [...composedOperationTemplates.values()].filter(
    /**
     * Reports whether one recorded template describes the dispatched target.
     * @param {readonly string[]} template - One recorded template's segments.
     * @returns {boolean} `true` when it could have composed the target.
     */
    (template: readonly string[]): boolean => templateCouldHaveComposed(template, segments),
  );

  /**
   * Keeps one segment only where every matching template declares a literal in that position.
   *
   * Assumptions: the empty segment is kept, because it is what separates two slashes and dropping it
   * would change the shape of the path rather than mask a value.
   * @param {string} segment - One path segment, already free of any query or fragment.
   * @param {number} index - Its position, compared with the same position of each candidate template.
   * @returns {string} The segment itself, or {@link MASKED_SEGMENT} where a value stood.
   */
  function maskSegment(segment: string, index: number): string {
    if (segment === '') {
      return segment;
    }
    if (candidates.length === 0) {
      return MASKED_SEGMENT;
    }
    const literalEverywhere = candidates.every(
      /**
       * Reports whether one candidate template declares a literal in the position under test.
       * @param {readonly string[]} template - One matching template's segments.
       * @returns {boolean} `true` when its segment at this position is not a placeholder.
       */
      (template: readonly string[]): boolean =>
        !TEMPLATE_PLACEHOLDER_SEGMENT.test(template[index] ?? ''),
    );
    return literalEverywhere ? segment : MASKED_SEGMENT;
  }

  return segments.map(maskSegment).join('/');
}

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

  // WHY : Assumptions: the template is recorded HERE, after both refusals and before the target is
  //       returned, which is what lets {@link maskedTarget} report a failure by position instead of by
  //       value. Recording it before the refusals would enter templates for requests that were never
  //       dispatched; recording it at the call sites would be forty-nine chances to forget, and the one
  //       that forgot would silently fall back to a fully-masked path.
  // WHY : Assumptions: what is recorded is the TEMPLATE and never the composed target, so no parameter
  //       value is retained anywhere by this module. A registry keyed by target would grow with traffic
  //       and would hold the very values the mask exists to withhold.
  rememberOperationTemplate(template);
  return target;
}

/**
 * Refuses a reading direction supplied without the cursor it would step from.
 *
 * Assumptions: every browse contract in this migration declares `direction` meaningful ONLY alongside
 * a cursor, defaults it to forward when a cursor arrives without one, and answers 400 keyed on the
 * direction when a direction arrives without a cursor. There is therefore no request in which a
 * direction alone carries meaning: it would describe a position relative to nothing.
 *
 * Refactoring Rationale: this guard exists because each of the seven paged clients previously DROPPED
 * a direction it received without a cursor, silently. That behaviour was defended in
 * `./authorization.ts` on the grounds that answering the opening page is kinder than a guaranteed
 * refusal, and the reasoning is reversed here deliberately. A dropped value is a caller defect made
 * invisible: the caller asked to step backward, received the first page, and nothing anywhere said
 * why — so a screen whose PF7 handler forgot to thread its cursor through looks like a service that
 * pages wrongly. It also made a neighbouring docstring untrue, since `./transactions.ts` claimed to
 * pass "every individual value through untouched" while dropping this one. Refusing locally turns a
 * silent wrong answer into an immediate, attributable error naming both inputs.
 *
 * Assumptions: this mirrors the mutually-exclusive check the transaction browse already applied to a
 * starting identifier combined with a cursor, so both of the two combinations a browse contract
 * cannot satisfy are now refused the same way, in one place, rather than one being refused locally
 * and the other quietly rewritten.
 *
 * Trade-offs: refusing is NOT a substitute for server-side validation, and no caller may read it as
 * one. The service remains the authority on every value, including whether a cursor is well-formed,
 * unexpired and sealed for this caller and direction, and it is the side that returns the per-field
 * error array a screen renders. This guard refuses exactly one combination that cannot be meaningful
 * and inspects nothing else — in particular it never parses, compares or reformats the cursor.
 *
 * Refactoring Rationale: this paragraph recorded a shared assembler returning the two page members as
 * "the better shape if a paged client is ever added and forgets this call", and rejected it as
 * disproportionate on three grounds -- two of the seven clients put these members in a request BODY
 * and five in a query string, one treated a blank cursor as no cursor, and two named their forward
 * default in a local constant. That rejection is WITHDRAWN, because the assembler was built and
 * answers all three: {@link keysetPagingMembers} returns the pair and leaves each client to place it
 * in the body or the query string it publishes, it normalises the blank cursor for every caller, and
 * it holds the single forward default the seven local constants were copies of. All seven clients call
 * it, so the pair can no longer be assembled without the refusal.
 *
 * Assumptions: this function REMAINS, delegating to that assembler and discarding its result, and it
 * is not a second implementation of the rule -- there is exactly one refusal, raised in one place,
 * with one message. It is kept because it names the rule for a caller that wants to check the pair
 * without assembling anything, which is what `./pageDirectionGuard.test.ts` measures directly, and
 * because a client added later may reach for a guard rather than an assembler.
 * @param {string | undefined} cursor - The sealed cursor the caller supplied, or `undefined` when it
 *   supplied none. A blank string counts as absent, because the delegate normalises it -- so a caller
 *   need no longer normalise before calling, and one that already does loses nothing by it.
 * @param {PageDirection | undefined} direction - The reading direction the caller supplied, or
 *   `undefined`.
 * @returns {void} Nothing when the pair is admissible; the caller proceeds to assemble its request.
 * @throws {RangeError} If a direction was supplied with no usable cursor to step from, carrying the
 *   message the assembler raises, so a caller sees one wording however it reached the rule.
 */
export function requireCursorForDirection(
  cursor: string | undefined,
  direction: PageDirection | undefined,
): void {
  keysetPagingMembers(cursor, direction);
}

/**
 * The four severities a service classifies a problem document with.
 *
 * Assumptions: transcribed from the `Severity` union in `./types`, which mirrors the enumeration
 * `services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java` serialises by name. The
 * values are listed here because a union is erased at compile time and cannot be consulted at run
 * time, and this guard has to decide whether a value a SERVICE sent is one of them.
 */
const SEVERITIES = ['LOG', 'INFO', 'WARNING', 'CRITICAL'] as const satisfies readonly Severity[];

/**
 * The six subsystems a failure may be attributed to.
 *
 * Assumptions: transcribed from the `Subsystem` union in `./types` for the same erasure reason as the
 * severities. Three members name mainframe runtimes the migrated system has none of, and they are
 * accepted rather than pruned because the enumeration carries the baseline's own attribution
 * vocabulary and a client must accept every value a service may send.
 */
const SUBSYSTEMS = [
  'APPLICATION',
  'CICS',
  'IMS',
  'RELATIONAL',
  'QUEUE',
  'OBJECT_STORE',
] as const satisfies readonly Subsystem[];

/**
 * The two states one field error may report.
 *
 * Assumptions: transcribed from the `FieldValidationState` union in `./types`. The two are not
 * interchangeable in rendering — the baseline's templated highlight writes a literal asterisk into a
 * field for the blank case and not for the not-acceptable one — so admitting a third value here would
 * hand a screen a state it has no rendering for.
 */
const FIELD_VALIDATION_STATES = [
  'NOT_OK',
  'BLANK',
] as const satisfies readonly FieldValidationState[];

/**
 * Fails to compile if any of the three domains above stops covering the union it transcribes.
 *
 * Assumptions: the two halves catch opposite mistakes and both are needed. `satisfies` on each
 * declaration above rejects an entry that is NOT a member of the union, so a typo or a value removed
 * from `./types` is caught there; the three `Exclude` types below resolve to `never` only while the
 * sequence covers the union completely, so a value ADDED to a union in `./types` and not added here
 * makes this declaration's type `never` and the assignment fails. A sequence typed as
 * `readonly Severity[]` catches neither, which is why this pair exists at all.
 *
 * Alternatives Considered: declaring each domain as `Readonly<Record<Union, true>>` instead, which
 * makes a missing key an incomplete record and an extra key unassignable, so it needs no companion
 * assertion. Rejected on integration grounds rather than on merit: {@link isMemberOf} narrows an
 * `unknown` through a SEQUENCE, seven paged clients and the client contract suite read it, and a
 * record-keyed domain would replace that helper's signature and every call to it to buy a property
 * these three lines already buy. The record form's argument is preserved rather than discarded -- it
 * is the reason this assertion is here.
 *
 * Trade-offs: the three witnesses are declared and never read, which reads as dead code. Accepted
 * because a type-level assertion has no runtime form: the check happens when `npm run typecheck`
 * elaborates the declaration, and the alternative -- a runtime assertion in a module every screen
 * imports -- would spend startup work to discover a fault that cannot survive a build.
 */
type CoversUnion<Domain extends readonly string[], Union extends string> =
  Exclude<Union, Domain[number]> extends never ? true : never;

const SEVERITIES_COVER_SEVERITY: CoversUnion<typeof SEVERITIES, Severity> = true;
const SUBSYSTEMS_COVER_SUBSYSTEM: CoversUnion<typeof SUBSYSTEMS, Subsystem> = true;
const STATES_COVER_FIELD_VALIDATION_STATE: CoversUnion<
  typeof FIELD_VALIDATION_STATES,
  FieldValidationState
> = true;

// Assumptions: the three witnesses are referenced once, here, so the module's own lint rule against an
//   unused declaration passes without an exemption -- a suppression comment would have to be renewed
//   every time the set changes, and a reader would have to decide whether it still applied.
void SEVERITIES_COVER_SEVERITY;
void SUBSYSTEMS_COVER_SUBSYSTEM;
void STATES_COVER_FIELD_VALIDATION_STATE;

/**
 * Reports whether a value is one of a fixed set of accepted strings, narrowing it to that set.
 *
 * Assumptions: the domain is compared through a widened `readonly string[]` view because the argument
 * is `unknown` and the platform's membership test is typed to accept only a member of the array's own
 * element type — so the widening is what lets an unvalidated value be tested at all, and it discards
 * no check, since the comparison is by value.
 * @template T The accepted string literals, which the narrowed result carries.
 * @param {readonly T[]} domain - Every value the member may hold, in the order `./types` declares.
 * @param {unknown} value - A member of a response body, of unknown shape.
 * @returns {boolean} `true` when the value is a string and is one of the accepted values, narrowing
 *   it to the domain's own type.
 */
function isMemberOf<T extends string>(domain: readonly T[], value: unknown): value is T {
  return typeof value === 'string' && (domain as readonly string[]).includes(value);
}

/**
 * Reports whether a value is a non-null object whose members can be read individually.
 * @param {unknown} value - A response body or one of its members.
 * @returns {boolean} `true` for a non-null object, narrowing it to a bag of unknown members so each
 *   one is checked before use rather than asserted.
 */
function isMemberBag(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Reports whether a value is one complete field error a form may bind a mark to.
 *
 * Assumptions: all three members are required and the state is restricted to its declared domain,
 * because this array is the WHOLE field-marking mechanism on a 400 — the target keeps no
 * pseudo-conversational re-entry flag to gate a highlight with, so an entry naming no field, or
 * carrying no sentence to show beside it, marks a form position with nothing in it.
 * @param {unknown} value - One element of a candidate field-error array.
 * @returns {boolean} `true` when the element carries a field name, an accepted state and a sentence,
 *   narrowing it to {@link FieldError}.
 */
function isFieldError(value: unknown): value is FieldError {
  if (!isMemberBag(value)) {
    return false;
  }
  // Assumptions: the members are read through a destructuring rather than by indexing the bag at each
  //   test, because a local declared from an index access narrows reliably while a repeated element
  //   access does not — and a guard whose narrowing depends on the compiler's flow analysis surviving a
  //   refactor is a guard that can silently stop checking.
  const { field, state, message } = value;
  return (
    typeof field === 'string' &&
    isMemberOf(FIELD_VALIDATION_STATES, state) &&
    typeof message === 'string'
  );
}

/**
 * Reports whether a value is the structured abend detail, or its documented absence.
 *
 * Assumptions: `null` is accepted as readily as a complete detail, because every published contract
 * declares this member nullable and an ordinary refusal carries none. A PARTIAL detail is refused: the
 * four members are the baseline's `ABEND-CODE`, `ABEND-CULPRIT`, `ABEND-REASON` and `ABEND-MSG` group,
 * and a diagnostic surface showing three of the four reads as though the fourth were empty rather than
 * missing.
 * @param {unknown} value - The `abend` member of a candidate problem document.
 * @returns {boolean} `true` for `null` or for a complete detail, narrowing it accordingly.
 */
function isAbendDetail(value: unknown): value is AbendDetail | null {
  if (value === null) {
    return true;
  }
  if (!isMemberBag(value)) {
    return false;
  }
  const { abendCode, abendCulprit, abendReason, abendMsg } = value;
  return (
    typeof abendCode === 'string' &&
    typeof abendCulprit === 'string' &&
    typeof abendReason === 'string' &&
    typeof abendMsg === 'string'
  );
}

/**
 * Reports whether an unknown value is a complete problem document a screen may render from.
 *
 * Refactoring Rationale: this probed THREE members — the status, the correlation identifier and the
 * presence of a field-error array — and its answer was then used as proof of the whole document.
 * {@link normaliseFailure} reads `code` from the value the moment this returns true and hands the
 * value on as {@link ApiRequestError.problem}, which a screen reads a sentence, a severity, an
 * instant and per-field marks out of. A partial or mistyped body therefore became TRUSTED data: a
 * JSON object carrying a numeric status and an empty array satisfied the old probe, so an undefined
 * code reached a message band and an element of the field array that was not a field error reached a
 * form's mark. Every member is checked here because every member is consumed somewhere.
 *
 * Assumptions: what is checked of each member is exactly what the shared advice guarantees, so a
 * conforming service is never refused. Its canonical constructor normalises an absent code to
 * `CARDDEMO-0500` and an absent secondary code, correlation identifier, path and timestamp to the
 * empty string, requires the severity and the subsystem, and copies an absent field-error array to an
 * empty one — so a present-and-string test on the five text members, a domain test on the two
 * enumerated ones, an integer test on the status and an element-wise test on the array admit every
 * document a service can produce. Only `message` and `abend` are nullable there, and only those two
 * are accepted as null here.
 *
 * Trade-offs: a body from a future service that added a member still passes, because unknown members
 * are ignored rather than refused. The alternative — refusing anything with an unexpected member —
 * would make this client reject documents it can render perfectly, which is a worse failure than
 * ignoring a member it has no use for.
 *
 * Trade-offs: a service that stopped emitting one member would have its problem documents classified
 * `RESPONSE` rather than `PROBLEM` by {@link normaliseFailure}, so its own sentence and field errors
 * would be replaced by a synthesised document. That is accepted because the alternative is worse in
 * the same situation: admitting the body would hand a screen a value whose members it reads without
 * them being there. The wire shape is gated on the service side, so the case is a contract break
 * rather than a variation to tolerate.
 *
 * Alternatives Considered: narrowing with a cast at each call site instead, which needs no helper.
 * Rejected because a cast asserts the shape without checking it, so a screen reading `fieldErrors`
 * from an HTML error page returned by a misconfigured gateway would throw on `undefined.length` and
 * report as a rendering bug rather than as a non-JSON response.
 * @param {unknown} value - A response body of unknown shape, typically from a rejected request.
 * @returns {boolean} `true` only when every member the interface declares is present and within its
 *   declared domain, narrowing the value to {@link ApiError}. A value failing any check is classified
 *   as a response whose body is not a problem document, and a synthesised document is used instead.
 */
export function isApiError(value: unknown): value is ApiError {
  if (!isMemberBag(value)) {
    return false;
  }
  const { code, secondaryCode, message, severity, subsystem, status } = value;
  const { correlationId, path: reportedPath, timestamp, fieldErrors, abend } = value;
  if (!Array.isArray(fieldErrors)) {
    return false;
  }
  // Assumptions: the array is retyped as a read-only sequence of unknown members before it is walked.
  //   The platform's array test narrows to an implicitly-typed array, and every element read off one is
  //   an unchecked value the rule set refuses — so naming the element type here is what keeps each entry
  //   checked by {@link isFieldError} rather than trusted because its container was an array.
  const entries: readonly unknown[] = fieldErrors;
  return (
    typeof code === 'string' &&
    code.length > 0 &&
    typeof secondaryCode === 'string' &&
    (message === null || typeof message === 'string') &&
    isMemberOf(SEVERITIES, severity) &&
    isMemberOf(SUBSYSTEMS, subsystem) &&
    Number.isInteger(status) &&
    typeof correlationId === 'string' &&
    typeof reportedPath === 'string' &&
    typeof timestamp === 'string' &&
    entries.every(isFieldError) &&
    isAbendDetail(abend)
  );
}

/**
 * The reading direction every contract applies to a cursor that arrives without one.
 *
 * Assumptions: ⚠️ forward is the default all SEVEN contracts declare, not six. Every one of them
 * publishes `default: next` on the direction its browser-facing paged operations take — six through a
 * shared `PageDirection` schema and account-api inline on `listAccountCardCrossReferences` — and this
 * client addresses paged operations in all seven. It is stated here once rather than in each client,
 * because a default spelled per module is a default that can come to differ per module while every
 * module still looks right on its own; the seven local constants this replaced were copies of it.
 */
const DEFAULT_PAGE_DIRECTION: PageDirection = 'next';

/**
 * Establishes the two paging members a keyset request may carry, refusing a direction sent alone.
 *
 * Purpose
 * -------
 * Decide, in ONE place for all seven clients, what a caller's cursor and direction mean: nothing to
 * send, a cursor read forward by default, a cursor read in the direction asked for, or a request that
 * cannot be made at all.
 *
 * Refactoring Rationale: ⚠️ each client used to assemble these two members itself, and all seven
 * dropped a supplied direction when no cursor accompanied it -- `if (cursor !== undefined) { ... }`
 * with the direction assigned inside the block. That silently rewrote the caller's request into a
 * different one: a screen asking to step BACKWARD from nowhere received the opening page and rendered
 * it as though the step had been taken, so a paging defect surfaced as rows that did not move rather
 * than as an error.
 *
 * Assumptions: ⚠️ every contract declares the pair asymmetrically -- a cursor without a direction is read
 * forward -- but they do NOT agree on the reverse combination, and that disagreement is the reason the
 * rule belongs here rather than being left to the service. Measured across the seven documents: auth,
 * card, reference and authorization publish that a direction with no cursor is refused with 400 keyed on
 * the direction; account publishes the opposite for `listAccountCardCrossReferences`, returning the
 * opening page whichever direction is named; and reporting and transaction publish no answer for it at
 * all. Deferring to the service would therefore give a screen three different behaviours for one caller
 * mistake, two of them silent.
 *
 * Alternatives Considered: modelling each query as a discriminated union that admits the pair only
 * together, which would move the refusal to compile time and is the stronger form. Rejected for now
 * because the cursor and the direction are separate optional members of seven published request
 * schemas, and a union would either change those wire shapes or add a client-only shape that no
 * contract describes -- while `ui/src/api/contracts.test.ts` holds every client shape to its contract
 * member for member. A runtime refusal keeps the published shapes exact and still turns a request whose
 * answer varies by service into an immediate, attributable error.
 *
 * Trade-offs: this is deliberately NOT a substitute for the service's own validation. The service
 * remains the authority on whether a cursor can be opened at all -- it is sealed against the query,
 * the caller and the direction it was minted for -- and it answers with the per-field entry a form
 * binds to. This guard refuses only the one combination that names no page.
 * @param {string | undefined} cursor - The sealed cursor a previous page issued, replayed verbatim, or
 *   `undefined` for an opening read. A blank string is treated as absent, because a caller holding an
 *   empty cursor holds no position and the service reads a blank value the same way.
 * @param {PageDirection | undefined} direction - Which way to step from that cursor, or `undefined` to
 *   accept the contract's forward default.
 * @returns {{ readonly cursor: string; readonly direction: PageDirection } | undefined} Both members
 *   when a usable cursor was supplied, or `undefined` when neither member is to be sent.
 * @throws {RangeError} If a direction is supplied without a usable cursor, naming both inputs. The
 *   cursor's value is never included in the message: it is opaque, it is bound to the caller, and this
 *   message reaches consoles and issue trackers.
 */
export function keysetPagingMembers(
  cursor: string | undefined,
  direction: PageDirection | undefined,
): { readonly cursor: string; readonly direction: PageDirection } | undefined {
  const positioned = cursor !== undefined && cursor.length > 0;
  if (!positioned) {
    if (direction !== undefined) {
      throw new RangeError(
        `A paging direction states which way to step from a position, so '${direction}' cannot be` +
          " sent without a cursor: replay the page envelope's lastKey to read forward or its" +
          ' firstKey to read backward, or omit the direction to read the opening page.',
      );
    }
    return undefined;
  }
  return { cursor, direction: direction ?? DEFAULT_PAGE_DIRECTION };
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
 * @returns {void} Nothing; the effect is the stored value. A token REPLACES whatever this tab held
 *   under this module's single key, and `null` REMOVES it, so the request interceptor stops attaching
 *   an `Authorization` header from the very next request. Discarding the access token is not by itself
 *   a sign-out: the identity and refresh tokens belong to `ui/src/hooks/useAuth.ts`, which clears them
 *   alongside this one.
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
