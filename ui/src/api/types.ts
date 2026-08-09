/**
 * @file Cross-contract types shared by every typed API client module, and the one helper that turns
 * a declared contract operation into the target its client sends.
 *
 * Purpose
 * -------
 * Five of the six service contracts in this repository are reachable from the browser, and all five
 * declare the same error vocabulary, the same page envelope and the same reading direction. Each of
 * them restates those schemas in its own document, because an OpenAPI document cannot reference a
 * schema in another file that no build step assembles. This module is the single TypeScript
 * declaration of them, so the SPA does not repeat that restatement a fifth time in a language where
 * it need not.
 *
 * Assumptions: a divergence between any two contracts' copies of these schemas is a defect in
 * whichever diverged, not a local choice, so one shared declaration here is correct rather than
 * merely convenient. The five documents were compared property by property while this module was
 * authored and they agree; where a contract narrows a member -- transaction-api bounds `status` to
 * 400 through 599 while authorization-api leaves it an unbounded int32 -- the NARROWER form is taken,
 * because a client accepting the wider one would type a value no service sends.
 *
 * Why the operation manifest lives beside the types
 * ------------------------------------------------
 * Refactoring Rationale: each client module exports a manifest of the operations it implements, and
 * builds every request target from it through {@link requestPath} rather than from a string literal
 * at the call site. The two arrangements are not equivalent. With literals, a module's manifest and
 * its behaviour are two independent descriptions of one thing, so a manifest can agree with the
 * contract while the code beside it calls a different address -- which is a drift a gate reading the
 * manifest cannot see. Deriving the target from the manifest removes that possibility by
 * construction, and leaves `ui/src/api/contracts.test.ts` with exactly one comparison to make:
 * manifest against contract.
 *
 * Trade-offs: the cost is a level of indirection at every call site, where a reader now follows a
 * constant instead of reading a path in place. It is accepted because the alternative failure is
 * silent and this one is merely inconvenient: a mistyped literal reaches an address the edge answers
 * with its own 404 while the service is running, healthy and correct, which is the least diagnosable
 * failure this boundary has.
 */

/**
 * The HTTP methods a CardDemo contract may declare.
 *
 * Assumptions: five members, matching the set `ReportingApiContractTest` filters path-item members
 * on, so the two gates agree about what counts as an operation. `HEAD`, `OPTIONS` and `TRACE` are
 * absent because no contract declares one and a client has no reason to send one.
 */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

/**
 * One operation a client module implements, named exactly as its contract declares it.
 *
 * Assumptions: `path` is the ABSOLUTE contract path template including the `/api/v1` prefix, and not
 * the relative target the client sends. Holding the contract's own spelling is what lets the drift
 * gate compare this value with a parsed contract directly, with no transformation on either side
 * that could itself be wrong; {@link requestPath} performs the one transformation, in one place.
 */
export interface ContractOperation {
  readonly method: HttpMethod;
  readonly path: string;
  readonly operationId: string;
}

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
 * Reading direction a browse request pairs with its cursor.
 *
 * Assumptions: the two members are spelled exactly as the services accept them -- lower case -- and
 * not as any Java enum's constant names. All five browser-facing contracts publish this pair in a
 * `PageDirection` schema, and the edge refuses an unrecognised value with HTTP 400 before any handler
 * runs, so a client spelling them otherwise would have every paging request rejected.
 */
export type PageDirection = 'next' | 'previous';

/**
 * Shared sealed-cursor page envelope returned by every browse operation.
 *
 * Assumptions: FIVE members, matching the `PageResponse` record in common-lib and the `CardPage`,
 * `PageResponse`, `TransactionPage`, `PendingAuthPage`, `TransactionTypePage` and sibling schemas the
 * contracts publish, each of which lists all five as required with `additionalProperties: false`. A
 * sixth member -- a next cursor, a previous cursor, a row total -- would describe a body no service
 * sends, and a member that is always absent is worse than no member because it reads as a value that
 * merely happens to be missing this time.
 *
 * Assumptions: `lastKey` is both the last row's identity and the position a forward request is issued
 * from, and `firstKey` likewise for a backward one. The service seals the direction into each token,
 * so replaying `firstKey` with direction `next` is refused with HTTP 400 rather than answered with
 * the wrong page -- which is what makes one value safe to serve both purposes.
 *
 * Refactoring Rationale: `hasPrevious` exists because the earlier four-member shape asked this client
 * to derive backward availability from `firstKey` being non-null, and that derivation was wrong. Every
 * page that returns rows names its own first row, so the OPENING page satisfied it, the backward
 * control was enabled there, and following it replaced the rows on screen with an empty page. The
 * services now establish the answer from the read that built the page, and this client reads it rather
 * than inferring it.
 */
export interface PageResponse<T> {
  readonly items: readonly T[];
  readonly firstKey: string | null;
  readonly lastKey: string | null;
  readonly hasNext: boolean;
  readonly hasPrevious: boolean;
}

/**
 * Which of the baseline's two field-level failure conditions applies.
 *
 * Assumptions: two members and no more, because the baseline draws exactly this distinction. The
 * templated highlight copybook `app/cpy/CSSETATY.cpy` moves the error colour into a field when its
 * validation flag is not-OK OR blank, and additionally writes a literal asterisk into the field only
 * in the blank case, so `BLANK` carries a rendering obligation `NOT_OK` does not.
 */
export type FieldValidationState = 'NOT_OK' | 'BLANK';

/** How serious a failure the services classify a problem document as. */
export type Severity = 'LOG' | 'INFO' | 'WARNING' | 'CRITICAL';

/**
 * Which subsystem a failure is attributed to.
 *
 * Assumptions: the six members include three the migrated system has no runtime for -- `CICS`,
 * `IMS` and `QUEUE` in its mainframe sense -- and they are retained rather than pruned because the
 * enumeration transcribes the baseline's own attribution vocabulary and a client must accept every
 * value a service may send.
 */
export type Subsystem = 'APPLICATION' | 'CICS' | 'IMS' | 'RELATIONAL' | 'QUEUE' | 'OBJECT_STORE';

/**
 * One failing request property, as the shared advice renders it.
 *
 * Assumptions: `message` is the verbatim string the baseline raises for that field, at the
 * seventy-five-character width its carriers declare, and it is rendered to the user unchanged.
 * Transformation rule T8 requires user-visible strings to be carried across character for character,
 * so a client must never reword one.
 */
export interface FieldError {
  readonly field: string;
  readonly state: FieldValidationState;
  readonly message: string;
}

/**
 * The structured equivalent of the baseline's `ABEND-DATA` group.
 *
 * Assumptions: four members at the widths `app/cpy/CSMSG02Y.cpy` L45 to L53 declares. It is a
 * diagnostic surface and not a user-facing one, which is why every contract declares it nullable:
 * an ordinary refusal carries none.
 */
export interface AbendDetail {
  readonly abendCode: string;
  readonly abendCulprit: string;
  readonly abendReason: string;
  readonly abendMsg: string;
}

/**
 * One problem document, as the shared advice in common-lib renders it.
 *
 * Assumptions: `fieldErrors` is always present and is empty rather than absent when a failure names
 * no field. Every contract declares it required, so a client checking for its presence would be
 * checking a condition that never occurs; checking its length is the meaningful test.
 */
export interface ApiError {
  readonly code: string;
  readonly secondaryCode: string;
  readonly message: string | null;
  readonly severity: Severity;
  readonly subsystem: Subsystem;
  readonly status: number;
  readonly correlationId: string;
  readonly path: string;
  readonly timestamp: string;
  readonly fieldErrors: readonly FieldError[];
  readonly abend: AbendDetail | null;
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
