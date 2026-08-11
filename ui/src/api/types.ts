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
 *
 * What this module holds, and what it deliberately does not
 * --------------------------------------------------------
 * Assumptions: the division of labour in `ui/src/api` is that THIS module declares the vocabulary more
 * than one contract speaks -- the page envelope, the reading direction, the error and abend shapes, the
 * money and date scalars -- while each service module declares the shapes of the one contract it
 * implements. `ui/src/api/cards.ts` shows both halves at once: it declares `CardSummary` and
 * `CardDetail` itself and re-exports `PageDirection` and `PageResponse` from here.
 *
 * Alternatives Considered: gathering every DTO of all seven contracts into this one module, so a reader
 * finds every response shape in a single place. Rejected, and not on grounds of taste. Six of the seven
 * contracts already have an owning module that declares their shapes, so a second declaration here
 * would not centralise anything -- it would leave two definitions of one wire contract in the same
 * folder, free to drift apart, with each screen binding to whichever it happened to import. That is the
 * same objection this file already records against re-declaring a contract that has an owner, and it
 * applies with more force to a shape a screen renders than to one only a consumer decodes.
 *
 * Refactoring Rationale: the account contract is the exception, and its shapes ARE declared here. It is
 * the one browser-facing contract with no owning module, so there is no second definition for these to
 * drift from, and the alternative -- leaving `account-api.yaml` with no TypeScript expression at all --
 * is what costs something: the account view and update screens are the two largest in the migration at
 * 100 and 128 map fields, and an untyped response is where an unmasked identifier gets rendered in
 * place of a masked one with nothing in the type system to object. Should an `accounts.ts` be
 * introduced, it re-exports these the way `cards.ts` re-exports the envelope rather than restating
 * them.
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
  /** Which of the five methods a CardDemo contract may declare this operation is sent with. */
  readonly method: HttpMethod;

  /**
   * The operation's path as its CONTRACT spells it, prefix included, not the target a client sends.
   *
   * Assumptions: holding the contract's own spelling is what lets the drift gate compare this value
   * with a parsed contract directly; {@link requestPath} performs the one transformation.
   */
  readonly path: string;

  /** The contract's own `operationId`, which is the key the drift gate matches an operation on. */
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
 *
 * Assumptions: `hasNext` is settled by the service from a read of one row MORE than the page holds,
 * which is how the reference settles the same question -- `app/cbl/COCRDLIC.cbl` sets its
 * `WS-CA-NEXT-PAGE-IND PIC X(1)` at L242, whose `88 CA-NEXT-PAGE-EXISTS VALUE 'Y'` at L244 is turned on
 * by discovering a record beyond the seven the screen shows. A client therefore never computes
 * availability from the number of rows it received.
 * @template T The row type of one page, which is the shape the operation returning it declares -- for
 *   example {@link CardXrefResponse} for an account's cross-reference rows.
 */
export interface PageResponse<T> {
  /**
   * The rows of this page, at most the row count the service fixes for the screen.
   *
   * Assumptions: the arity is settled server-side and a client cannot vary it -- seven for the card
   * browse, from `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7` at `app/cbl/COCRDLIC.cbl` L177 to L178.
   * A final page is legitimately shorter, and an empty page is a state rather than an error.
   */
  readonly items: readonly T[];

  /**
   * Sealed cursor identifying the FIRST row returned, or nothing when the page carried none.
   *
   * Assumptions: opaque, and the client neither parses, compares nor computes on it -- it is replayed
   * verbatim. The value it seals is composite in more than one context: the card browse key is a card
   * number with an account identifier, and the pending-authorization key is two packed-decimal
   * integers at `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L19 to L21. The service also seals
   * the direction into it, so replaying this one forward is refused with 400 rather than answered with
   * the wrong page. Anything a client inferred from its bytes would be inference about an encoding it
   * does not own.
   */
  readonly firstKey: string | null;

  /** Sealed cursor identifying the LAST row returned, on the same terms as `firstKey`. */
  readonly lastKey: string | null;

  /** Whether reading forward from `lastKey` yields a further page. */
  readonly hasNext: boolean;

  /** Whether reading backward from `firstKey` yields a further page. */
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
  /**
   * Which request property was refused, by its logical name.
   *
   * Assumptions: the name matches the member of the request shape rather than the 3270 field, so a form
   * can bind an entry to the control the user typed into. Known names in the sign-on and user family are
   * `userId`, `password`, `firstName`, `lastName`, `userType` and `confirmed`.
   */
  readonly field: string;

  /**
   * Whether the field carried an unacceptable value or carried none at all.
   *
   * Assumptions: both are errors, and the distinction exists because the baseline draws it -- a blank
   * field additionally receives a literal asterisk at `app/cpy/CSSETATY.cpy` L23 to L25, so `BLANK`
   * carries a rendering obligation `NOT_OK` does not.
   */
  readonly state: FieldValidationState;

  /** The verbatim sentence for this field, at the seventy-five characters its carrier declares. */
  readonly message: string;
}

/**
 * The structured equivalent of the baseline's `ABEND-DATA` group.
 *
 * Assumptions: four members at the widths `app/cpy/CSMSG02Y.cpy` lines 21 to 29 declare --
 * `01 ABEND-DATA.` at L21, then `ABEND-CODE PIC X(4)` at L22, `ABEND-CULPRIT PIC X(8)` at L24,
 * `ABEND-REASON PIC X(50)` at L26 and `ABEND-MSG PIC X(72)` at L28, each followed by its
 * `VALUE SPACES` continuation. It is a diagnostic surface and not a user-facing one, which is why
 * every contract declares it nullable: an ordinary refusal carries none.
 *
 * Assumptions: the line range is stated as 21 to 29 because that is where the group is. An earlier
 * revision of this block cited lines 45 to 53, a range that copybook does not have -- the whole file
 * is 35 lines, so the reference resolved to nothing and could not be checked by following it. The
 * range here agrees with `com.carddemo.common.error.AbendDetail`, whose own Javadoc cites lines 21 to
 * 29 for the group and L22, L24, L26 and L28 for the four widths. A citation nobody can follow is the
 * one kind of comment that survives review indefinitely, because reviewing it requires the very
 * lookup it makes impossible.
 *
 * Assumptions: a screen renders this through a result surface with an error status rather than through
 * the ordinary message band, because the four values are operator diagnostics and not a sentence a
 * user can act on. No component is named here: this module holds no dependency on a rendering
 * library, so what reads the type is the screen's concern and not this declaration's.
 */
export interface AbendDetail {
  /** The abend identifier, four characters. `ABEND-CODE PIC X(4)` at `app/cpy/CSMSG02Y.cpy` L22. */
  readonly abendCode: string;

  /** What failed, eight characters. `ABEND-CULPRIT PIC X(8)` at `app/cpy/CSMSG02Y.cpy` L24. */
  readonly abendCulprit: string;

  /** Why it failed, fifty characters. `ABEND-REASON PIC X(50)` at `app/cpy/CSMSG02Y.cpy` L26. */
  readonly abendReason: string;

  /** The operator-facing sentence, seventy-two characters. `ABEND-MSG PIC X(72)` at L28. */
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
  /**
   * The stable identifier a caller may branch on, never reworded between releases.
   *
   * Assumptions: the identifiers are the shared kernel's, and their numeric suffix aligns with the HTTP
   * status, so this is what a client matches on rather than the human sentence -- which is free to change
   * wording without changing meaning.
   */
  readonly code: string;

  /**
   * A subordinate identifier qualifying {@link ApiError.code}, or the empty string when there is none.
   *
   * Assumptions: empty rather than absent, so a client reads the member unconditionally. It is what
   * discriminates the distinct conditions sharing one status -- notably the several that answer 409 --
   * so a caller distinguishing a stale revision from a referential refusal reads THIS and not the status.
   */
  readonly secondaryCode: string;

  /**
   * The aggregate sentence, or nothing.
   *
   * Assumptions: carried verbatim from the baseline copybook or program that owns the wording wherever one
   * does, so a client renders it unchanged and never rewords it. Nullable deliberately: absent is the
   * migrated form of no-message, which is distinct from an empty message.
   */
  readonly message: string | null;

  /** How serious the failure is, on the baseline's own four-level ladder. */
  readonly severity: Severity;

  /**
   * Which part of the platform the failure arose in.
   *
   * Assumptions: a given service produces only a subset of the six values, so a response naming one
   * outside its subset would misattribute the failure; a client must nonetheless accept all six.
   */
  readonly subsystem: Subsystem;

  /**
   * The HTTP status, restated inside the body.
   *
   * Assumptions: always equal to the status of the response that carried it. The restatement exists so a
   * payload logged or archived away from its response envelope is still self-describing. This is a status
   * code and therefore legitimately a number -- unlike any monetary member, which is text.
   */
  readonly status: number;

  /**
   * Identifies the unit of work that failed, for quoting to support.
   *
   * Assumptions: inherited from the inbound request rather than generated in the response, and the empty
   * string when the request carried none. It is the intended route from this response to the server-side
   * log of the same request, which is why `client.ts` sends it and the server's correlation filter echoes
   * it back on the response as well.
   */
  readonly correlationId: string;

  /** The request target that failed, so a logged payload identifies what was called. */
  readonly path: string;

  /** When the failure was rendered, as text, on the same reasoning that keeps every instant text here. */
  readonly timestamp: string;

  /**
   * One entry per refused field, empty rather than absent when no field is named.
   *
   * Assumptions: always present, so the meaningful test is its LENGTH and never its presence. This array
   * is the whole mechanism by which a form marks a field, because the baseline's own marking is gated on
   * a pseudo-conversational re-entry flag that a stateless handler does not have.
   */
  readonly fieldErrors: readonly FieldError[];

  /** Operator diagnostics, present only for an unclassified failure; an ordinary refusal carries none. */
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

// WHAT: the two scalars every browser-facing contract carries in the same shape, named so that a
//       reader of a member below sees the constraint instead of an unqualified `string`.
// WHY : Assumptions: both are transparent aliases of `string`, which is what makes them safe to
//       introduce beside modules that already spell these members `string` -- an alias is the same
//       type, so no existing declaration changes meaning and no existing call site has to move. What
//       they add is the citation: the reason a monetary amount is not a `number` belongs where the
//       amount is declared, not in a reviewer's memory.

/**
 * An exact monetary amount, carried as text with two fractional digits.
 *
 * Assumptions: the wire form is the `Money` schema of `account-api.yaml`, `type: string` with pattern
 * `^-?[0-9]{1,10}\.[0-9]{2}$`, and the sibling contracts inline the same patterned string. The ten
 * integral digits are the width the reference layouts declare: `05 ACCT-CURR-BAL PIC S9(10)V99.` at
 * `app/cpy/CVACT01Y.cpy` L7, a zoned-decimal field whose sign is overpunched onto its last byte. The
 * server holds the same value as `NUMERIC(p,2)` in SQL and as a `BigDecimal` at scale 2 with
 * `HALF_UP`, and `com.carddemo.common.money.MoneyModule` is what puts it on the wire as a string.
 *
 * Alternatives Considered: `number`, which is what a JSON number would deserialise to. Rejected
 * because JavaScript has one numeric type and it is an IEEE-754 double, so `0.1 + 0.2` is not `0.3`
 * and a cent is lost at the boundary the user actually reads. The failure is the worst kind available
 * here: it produces a plausible figure rather than an error, so a statement balance that is a cent
 * wrong looks exactly like one that is right.
 *
 * Assumptions: the baseline itself transports money as characters, so this is transcription and not
 * invention. The account-update program snapshots the balance as `ACUP-OLD-CURR-BAL PIC X(12)` and
 * only then redefines it as `PIC S9(10)V99` for arithmetic, at `app/cbl/COACTUPC.cbl` L675 to L677,
 * with the same X-over-9 pair for the credit limit at L678 to L680 and the cash credit limit at L681
 * to L683. The bill-pay screen declares its balance field `CURBALI PIC X(14)` at
 * `app/cpy-bms/COBIL00.CPY` L66.
 *
 * Trade-offs: arithmetic on this type is not available without an explicit decimal conversion, and
 * that is the point rather than a cost incurred by accident. A screen that needs a total asks the
 * service for one; the reference computes its own page, account and grand totals in the batch that
 * renders the report rather than on the terminal.
 */
export type Money = string;

/**
 * A calendar date in `YYYY-MM-DD` form, ten characters wide.
 *
 * Assumptions: the stored value is already this text at this width -- `ACCT-OPEN-DATE PIC X(10)` at
 * `app/cpy/CVACT01Y.cpy` L10 and `CUST-DOB-YYYY-MM-DD PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L19 -- and
 * the components are already in most-significant-first order, so a lexical comparison of two of these
 * orders them the same way a date comparison would. Nothing is gained by parsing one to compare it.
 *
 * Alternatives Considered: a `Date`. Rejected on two independent grounds. A `Date` is an instant and
 * therefore carries a time zone, so an account expiry date read in one zone and rendered in another
 * can move by a day -- and the expiry boundary is inclusive in the reference, where a transaction
 * dated equal to the expiration date posts and one day later is refused, so a day of drift changes an
 * outcome rather than a display. And a round trip through `Date` cannot preserve what the sibling
 * timestamp members carry: `TRAN-ORIG-TS` and `TRAN-PROC-TS` are `PIC X(26)` at
 * `app/cpy/CVTRA05Y.cpy` L16 and L17, the form `YYYY-MM-DD HH:MM:SS.mmmmmm`, whose microsecond
 * precision a millisecond-resolution `Date` truncates. Those members stay text in their own modules
 * for this reason, and dates stay text here for consistency with them.
 */
export type IsoDate = string;

// WHAT: the domain decisions this module is answerable for whose TYPES are declared in the service
//       module that owns the contract carrying them. Recorded here because the decision is
//       cross-cutting even where the declaration is not, and a reader who finds only half of it in
//       one place tends to re-decide the other half.
// WHY : Assumptions: naming the owning module rather than restating its declaration is what keeps
//       one definition per wire contract. Each note below is followed to a real declaration, so a
//       reader can check it rather than take it.
//
//       Assumptions: no card verification value appears in any shape in this folder, under this or
//       any other name. The reference record does carry one -- `CARD-CVV-CD PIC 9(03)` at
//       `app/cpy/CVACT02Y.cpy` L7 -- and no operation of any contract returns it, so no shape may
//       declare it. The omission is deliberate and is recorded because the record layout is the
//       source these shapes are read from, and a later reader comparing the two would otherwise find
//       a field missing and complete the mapping in good faith.
//
//       Trade-offs: a primary account number is rendered masked everywhere except one administrative
//       shape. `ui/src/api/cards.ts` carries the masked rendering on `CardSummary.displayCardNumber`
//       and the sixteen digits only on `AdminCardDetail.cardNumber`, whose operation the service
//       restricts to the administrative group. The compromise accepted is that the disclosure exists
//       at all; what bounds it is that it is visible in the type system and reachable at one address
//       rather than being an undeclared property of a general shape.
//
//       Assumptions: the two-value user-type domain is `'A'` for administrator and `'U'` for user,
//       declared as `UserType` in `ui/src/api/auth.ts`. The authority for the VALUE SET is
//       `app/cpy/COCOM01Y.cpy` L26 to L28, where `CDEMO-USER-TYPE PIC X(01)` is followed by
//       `88 CDEMO-USRTYP-ADMIN VALUE 'A'` and `88 CDEMO-USRTYP-USER VALUE 'U'`. The width alone comes
//       from `SEC-USR-TYPE PIC X(01)` at `app/cpy/CSUSR01Y.cpy` L22, which is why that line is not
//       the citation: it constrains how wide the field is and says nothing about which values it
//       admits.
//
//       Assumptions: the authorization match domain is `'P'`, `'D'`, `'E'` and `'M'` and the fraud
//       domain is `'F'` and `'R'`, declared in `ui/src/api/authorization.ts`. Both are read from the
//       condition names of the detail segment: `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy`
//       L46 to L49 for pending, declined, pending-expired and matched, and L51 to L52 for confirmed
//       and removed. The fraud member admits an absent value because an authorization nobody has
//       marked carries none, which is a state and not a missing datum.
//
//       Assumptions: the five account-status slots of the summary segment are five discretely named
//       members and not an array, declared as `accountStatus1` through `accountStatus5` in
//       `ui/src/api/authorization.ts`. The arity is fixed at five by
//       `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` L22, `05 PA-ACCOUNT-STATUS PIC X(02)
//       OCCURS 5 TIMES.`, and the schema holds it as five columns. An array would type a length the
//       contract does not have, so a screen would have to handle a sixth element that cannot arrive
//       and a fourth that cannot be missing.
//
//       Refactoring Rationale: no shape in this folder carries a password except the sign-on request
//       in `ui/src/api/auth.ts`, which exchanges one for a token and stores nothing. The reference
//       holds the credential in the clear -- `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy`
//       L21 -- compares it directly at `app/cbl/COSGN00C.cbl` L223, and writes it back to the screen
//       at `app/cbl/COUSR02C.cbl` L169. The target keeps no such column and no such member: identity
//       moves to a managed user pool, so there is nothing for a user shape to carry. This is the
//       divergence the traceability register records as D-4, and it is the one place where parity
//       with the baseline is declined rather than preserved.
//
//       Refactoring Rationale: three reference field names carry a misspelling and the target names
//       all three as spelled. One belongs to a shape declared below and states its own lineage where
//       it is declared: `ACCT-EXPIRAION-DATE` at `app/cpy/CVACT01Y.cpy` L11. The other two belong to
//       shapes their own modules own -- `CARD-EXPIRAION-DATE` at `app/cpy/CVACT02Y.cpy` L9, which
//       `ui/src/api/cards.ts` names `expirationDate`, and `PA-MERCHANT-CATAGORY-CODE` at
//       `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L36, which `ui/src/api/authorization.ts`
//       names `merchantCategoryCode`. All three baseline spellings are named here so the lineage
//       between a member and the field it is read from is never ambiguous, and the copybooks
//       themselves are untouched: they are the behavioural oracle for this migration and stay
//       byte-identical, so the divergence is documented rather than removed at its source.
//
//       Assumptions: the authorization request and reply payloads are NOT declared in this folder.
//       They are queue contracts in comma-separated form, eighteen fields outbound and six inbound,
//       decoded by `com.carddemo.common.codec.CsvAuthCodec` on the server. A browser never receives
//       one, so declaring the shape here would create a second definition of a contract that has an
//       owner -- and a definition no test in this tree could hold to account, since nothing in the
//       browser exercises that path.

// WHAT: the shapes of `account-api.yaml`, the one browser-facing contract with no service module of
//       its own, covering the account view, the account update and the card cross-reference reads.
// WHY : Assumptions: every member below is declared exactly as that document declares it -- same
//       name, same nullability, same optionality -- and where the document and the record layout
//       disagree the DOCUMENT is followed. The two disagree deliberately in four places, because the
//       account-view screen shows a narrower value than the record stores and the reference itself
//       performs that narrowing with a direct move. Reading the record instead would type four
//       members wider than any response can carry.
//
//       Assumptions: the contract declares `additionalProperties: false` on every one of these, so a
//       member absent below is a member no response carries, and adding one here would type a value
//       the service is forbidden from sending.

/**
 * One account and its customer as the account-view operation returns them.
 *
 * Assumptions: this mirrors `AccountViewResponse` in `account-api.yaml`, whose five members are all
 * required. The composition -- an identifier, an account part, a customer part and two message
 * channels -- is the shape of the screen rather than the shape of either record, because the reference
 * builds this view from two files at once: `app/cbl/COACTVWC.cbl` reads the account and then the
 * customer the cross-reference names, and renders both on one map.
 */
export interface AccountViewResponse {
  /**
   * The account that was read, echoed as digits so the caller can confirm what it received.
   *
   * Assumptions: eleven digits at `^[0-9]{1,11}$`, from `ACCT-ID PIC 9(11)` at
   * `app/cpy/CVACT01Y.cpy` L5. It is text in this body while the path parameter addressing the same
   * account is an integer, and the contract states that difference is deliberate: every value on this
   * screen is character data, so a body member typed as a number would be the only one a client had to
   * convert back before rendering it.
   */
  readonly accountId: string;

  /** The account's own stored values, as {@link AccountDetail} describes them. */
  readonly account: AccountDetail;

  /** The customer the account belongs to, at screen widths, as {@link CustomerDetail} describes. */
  readonly customer: CustomerDetail;

  /**
   * The screen's information line, or nothing when there is none to show.
   *
   * Assumptions: forty characters, the width `05 WS-INFO-MSG PIC X(40)` declares at
   * `app/cbl/COACTVWC.cbl` L110 -- not the forty-five its map container holds. The container is the
   * box and the program's field is what the program moves into it, so forty is the binding figure and
   * a caller sizing for forty-five would be allowing for a value this system never emits. This
   * operation always leaves it empty; the member exists because the update response shares the shape
   * and does fill it.
   */
  readonly informationMessage: string | null;

  /**
   * The screen's message line, or nothing when there is none to show.
   *
   * Assumptions: seventy-five characters, matching `WS-RETURN-MSG` at `app/cbl/COACTVWC.cbl` L117 and
   * the two communication-area carriers `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`, both
   * `PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28 and L29. It is a rendering constraint carried across
   * rather than a suggestion, and it is NOT the seventy-eight of the map's `ERRMSGI` field.
   */
  readonly returnMessage: string | null;
}

/**
 * The stored values of one account.
 *
 * Assumptions: this mirrors `AccountDetail` in `account-api.yaml`, whose ten members are all required,
 * and the member ORDER is the contract's rather than the record's -- the document interleaves the
 * money and date members, and following it keeps a reader comparing the two files line for line. The
 * source record is `ACCOUNT-RECORD` at `app/cpy/CVACT01Y.cpy` L4 to L17, three hundred bytes.
 *
 * Assumptions: the record's `ACCT-ID` and `ACCT-ADDR-ZIP` are absent here because the contract does
 * not declare them on this shape. The identifier is carried once by the enclosing response instead of
 * twice, and the postal code the account-view screen shows is the customer's. `FILLER PIC X(178)` at
 * L17 is padding to the fixed record length and is dropped, as every fixed-width pad is.
 */
export interface AccountDetail {
  /**
   * Whether the account is active, as one character.
   *
   * Trade-offs: typed as `string` and NOT as a two-value union, even though the example the contract
   * gives is `Y`. `account-api.yaml` declares this member as `type: string` with `maxLength: 1` and no
   * enumeration, while `card-api.yaml` DOES enumerate its own card status as `Y` or `N` -- which is
   * why `ui/src/api/cards.ts` narrows there and this does not. Narrowing here would reject a value
   * this contract permits its service to send, and a response the client refuses to type is worse than
   * a character it renders as it arrives.
   */
  readonly activeStatus: string;

  /** When the account was opened. `ACCT-OPEN-DATE PIC X(10)` at `app/cpy/CVACT01Y.cpy` L10. */
  readonly openDate: IsoDate;

  /** The account's credit limit. `ACCT-CREDIT-LIMIT PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L8. */
  readonly creditLimit: Money;

  /**
   * When the account expires.
   *
   * Refactoring Rationale: the baseline declares this field `ACCT-EXPIRAION-DATE` at
   * `app/cpy/CVACT01Y.cpy` L11, with `EXPIRAION` for `EXPIRATION`; the target names it
   * `expirationDate`. The baseline spelling is named here so the lineage between the two is never in
   * doubt, and the copybook itself is untouched -- it is the behavioural oracle for this migration and
   * stays byte-identical. The divergence is documented rather than left for a reader to infer from a
   * name that does not match its source.
   *
   * Assumptions: the expiry boundary this value participates in is INCLUSIVE -- a transaction dated
   * equal to it posts and one day later is refused -- which is why the member is the same ten-
   * character text the reference compares rather than a parsed instant.
   */
  readonly expirationDate: IsoDate;

  /** The cash advance limit. `ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L9. */
  readonly cashCreditLimit: Money;

  /** When the account was last reissued. `ACCT-REISSUE-DATE PIC X(10)` at L12 of that copybook. */
  readonly reissueDate: IsoDate;

  /** The balance owed. `ACCT-CURR-BAL PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L7. */
  readonly currentBalance: Money;

  /** Credits posted this cycle. `ACCT-CURR-CYC-CREDIT PIC S9(10)V99` at L13 of that copybook. */
  readonly currentCycleCredit: Money;

  /**
   * The disclosure group whose interest rates apply to this account.
   *
   * Assumptions: ten characters, `ACCT-GROUP-ID PIC X(10)` at `app/cpy/CVACT01Y.cpy` L16. It is the
   * first component of the disclosure-group key, and a value with no matching group row falls back to
   * the group named `DEFAULT` rather than failing, which is why an unfamiliar value here is not
   * necessarily an error.
   */
  readonly groupId: string;

  /** Debits posted this cycle. `ACCT-CURR-CYC-DEBIT PIC S9(10)V99` at L14 of that copybook. */
  readonly currentCycleDebit: Money;
}

/**
 * One customer at the widths the account-view MAP declares.
 *
 * Assumptions: this mirrors `CustomerDetail` in `account-api.yaml`, eighteen required members, and it
 * is NOT interchangeable with {@link CustomerResponse}. Four members are narrower here than the record
 * stores them and one is named differently, because this shape is what the account-view screen shows
 * and the reference narrows those values itself with a direct move. Sending a {@link CustomerResponse}
 * where this is expected would carry a ten-character postal code into a five-character field.
 *
 * Assumptions: the national identifier and the government-issued identifier appear ONLY in masked
 * form. Both are held encrypted by the service -- `CUST-SSN PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L17
 * and `CUST-GOVT-ISSUED-ID PIC X(20)` at L18 -- and no read operation returns either in the clear, so
 * no unmasked member is declared for a screen to reach for.
 */
export interface CustomerDetail {
  /**
   * The customer read, as digits. `CUST-ID PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L5.
   *
   * Assumptions: text rather than a number, on the same terms as every identifier in these shapes. A
   * nine-digit value would survive as a number, but a sixteen-digit card number would not -- ten to
   * the sixteenth exceeds the largest exactly representable integer, about 9.007 times ten to the
   * fifteenth -- and leading zeros are part of a fixed-width contract that a numeric type discards.
   * The reference draws the same distinction itself, holding each identifier as characters and
   * redefining it as digits only where it computes: `CC-CUST-ID PIC X(09)` with
   * `CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9)` at `app/cpy/CVCRD01Y.cpy` L40 and L42, and the same
   * pair for the account at L34 and L36 and for the card number at L37 and L39.
   */
  readonly customerId: string;

  /**
   * The national identifier, rendered masked.
   *
   * Assumptions: up to twelve characters, which is WIDER than the nine digits stored, because a mask
   * carries separators the stored value does not. Derived from `CUST-SSN PIC 9(09)` at
   * `app/cpy/CVCUS01Y.cpy` L17. The member name says `Masked` so that a screen cannot bind it to a
   * field expecting the whole value and quietly render a partial one as if it were complete.
   */
  readonly ssnMasked: string;

  /** The customer's date of birth. `CUST-DOB-YYYY-MM-DD PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L19. */
  readonly dateOfBirth: IsoDate;

  /**
   * The customer's credit score, as up to three digits of text.
   *
   * Trade-offs: this is a bounded small integer and is still typed as `string`, which looks like the
   * money rule applied where it does not belong. It is the CONTRACT that decides: `account-api.yaml`
   * declares this member `type: string`, `maxLength: 3`, pattern `^[0-9]{1,3}$`, and cites the map
   * field `ACSTFCOI PIC X(3)` over the stored `CUST-FICO-CREDIT-SCORE PIC 9(03)` at
   * `app/cpy/CVCUS01Y.cpy` L22. The screen carries it as characters, so the body does too. The reason
   * money is text is exactness; the reason this is text is agreement with the document -- two
   * different reasons reaching the same type, and worth distinguishing so neither is applied by reflex
   * to a member the contract types as a number.
   */
  readonly ficoCreditScore: string;

  /** Given name. `ACSFNAMI PIC X(25)`, from `CUST-FIRST-NAME PIC X(25)` at L6. */
  readonly firstName: string;

  /**
   * Middle name, or nothing.
   *
   * Assumptions: required-but-nullable, because a customer legitimately has none and the load records
   * an all-blank fixed-width field as absent rather than as a string of spaces. From
   * `CUST-MIDDLE-NAME PIC X(25)` at `app/cpy/CVCUS01Y.cpy` L7. The key is present in the body either
   * way, carrying an explicit empty value, so a screen tests the value and never the key.
   */
  readonly middleName: string | null;

  /** Family name. `ACSLNAMI PIC X(25)`, from `CUST-LAST-NAME PIC X(25)` at L8. */
  readonly lastName: string;

  /** First address line. `ACSADL1I PIC X(50)`, from `CUST-ADDR-LINE-1 PIC X(50)` at L9. */
  readonly addressLine1: string;

  /**
   * Two-character state code. `ACSSTTEI PIC X(2)`, from `CUST-ADDR-STATE-CD PIC X(02)` at L12.
   *
   * Assumptions: the service validates a submitted code against seeded reference data, so a value
   * here is one that passed that check rather than arbitrary text.
   */
  readonly stateCode: string;

  /** Second address line, or nothing. From `CUST-ADDR-LINE-2 PIC X(50)` at L10; nullable as stored. */
  readonly addressLine2: string | null;

  /**
   * Postal code at the screen's five characters.
   *
   * Assumptions: this is the first of the four places this shape is narrower than the record.
   * `ACSZIPCI PIC X(5)` against the stored `CUST-ADDR-ZIP PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L14,
   * and the reference performs the narrowing itself with a direct move onto the shorter field. The
   * narrowing is reproduced rather than widened back, because widening it would let this shape carry
   * ten characters the screen it describes cannot show.
   */
  readonly zipCode: string;

  /**
   * The city line of the address.
   *
   * Assumptions: fifty characters, stored in `CUST-ADDR-LINE-3 PIC X(50)` at `app/cpy/CVCUS01Y.cpy`
   * L11. The map names this field for what the screen asks of it while the record names it by position
   * in the address block; the contract follows the map, and {@link CustomerResponse} follows the
   * record. The two names describe one stored value.
   */
  readonly city: string;

  /** Three-character country code. `ACSCTRYI PIC X(3)`, from `CUST-ADDR-COUNTRY-CD PIC X(03)` at L13. */
  readonly countryCode: string;

  /**
   * Primary telephone number at the screen's thirteen characters.
   *
   * Assumptions: `ACSPHN1I PIC X(13)`, narrowed from `CUST-PHONE-NUM-1 PIC X(15)` at
   * `app/cpy/CVCUS01Y.cpy` L15 by the reference's own direct move, which drops exactly the two-
   * character trailing pad the wider field declares. Non-null on this shape, unlike its counterpart
   * on {@link CustomerResponse}.
   */
  readonly phoneNumber1: string;

  /**
   * The government-issued identifier, rendered masked.
   *
   * Assumptions: twenty characters, the stored width of `CUST-GOVT-ISSUED-ID PIC X(20)` at
   * `app/cpy/CVCUS01Y.cpy` L18. Held encrypted by the service and never returned in the clear by a
   * read; the update request is the only shape carrying the whole value, and only inbound.
   */
  readonly governmentIssuedIdMasked: string;

  /** Secondary telephone number, or nothing. From `CUST-PHONE-NUM-2 PIC X(15)` at L16, narrowed to 13. */
  readonly phoneNumber2: string | null;

  /** Electronic funds transfer account. `ACSEFTCI PIC X(10)`, from `CUST-EFT-ACCOUNT-ID PIC X(10)` at L20. */
  readonly eftAccountId: string;

  /**
   * Whether this customer is the primary card holder, as one character.
   *
   * Assumptions: `ACSPFLGI PIC X(1)`, from `CUST-PRI-CARD-HOLDER-IND PIC X(01)` at
   * `app/cpy/CVCUS01Y.cpy` L21. Typed as `string` because the contract enumerates no values for it,
   * on the same reasoning given for the account's active status.
   */
  readonly primaryCardHolderIndicator: string;
}

/**
 * One customer at the widths the RECORD declares.
 *
 * Assumptions: this mirrors `CustomerResponse` in `account-api.yaml`, eighteen required members, and it
 * is the record-width counterpart of {@link CustomerDetail} rather than a duplicate of it. Both
 * describe one stored customer; this one is returned where no screen is narrowing the values, so the
 * postal code keeps all ten characters, both telephone numbers keep fifteen, and the third address
 * line is named for its position in the record instead of for the city field a map binds it to. The
 * source record is `CUSTOMER-RECORD` at `app/cpy/CVCUS01Y.cpy` L4 to L23, five hundred bytes, with
 * `FILLER PIC X(168)` at L23 dropped as padding.
 *
 * Trade-offs: carrying two shapes for one entity is duplication, and the alternative -- one shape at
 * record widths, narrowed by whichever screen needs it -- was available. It is rejected because the
 * narrowing is not a display preference: the reference performs it in COBOL with a direct move onto a
 * shorter field, so it is part of what the account-view operation RETURNS. Typing one shape would
 * oblige every consumer to know which of two width regimes its response was in, with nothing in the
 * type to tell it.
 */
export interface CustomerResponse {
  /** The customer, as digits. `CUST-ID PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L5, pattern `^[0-9]{1,9}$`. */
  readonly customerId: string;

  /** Given name. `CUST-FIRST-NAME PIC X(25)` at `app/cpy/CVCUS01Y.cpy` L6. */
  readonly firstName: string;

  /** Middle name, or nothing when the stored field is unpopulated. `CUST-MIDDLE-NAME PIC X(25)` at L7. */
  readonly middleName: string | null;

  /** Family name. `CUST-LAST-NAME PIC X(25)` at `app/cpy/CVCUS01Y.cpy` L8. */
  readonly lastName: string;

  /** First address line. `CUST-ADDR-LINE-1 PIC X(50)` at `app/cpy/CVCUS01Y.cpy` L9. */
  readonly addressLine1: string;

  /** Second address line, or nothing when unpopulated. `CUST-ADDR-LINE-2 PIC X(50)` at L10. */
  readonly addressLine2: string | null;

  /**
   * Third address line, at the record's own name and width.
   *
   * Assumptions: `CUST-ADDR-LINE-3 PIC X(50)` at `app/cpy/CVCUS01Y.cpy` L11. This is the member
   * {@link CustomerDetail} calls `city`, and the difference in name is the difference between the
   * record and the map that binds it. One stored value, two shapes, and neither name is wrong for the
   * shape it appears in.
   */
  readonly addressLine3: string;

  /** Two-character state code. `CUST-ADDR-STATE-CD PIC X(02)` at `app/cpy/CVCUS01Y.cpy` L12. */
  readonly stateCode: string;

  /** Three-character country code. `CUST-ADDR-COUNTRY-CD PIC X(03)` at `app/cpy/CVCUS01Y.cpy` L13. */
  readonly countryCode: string;

  /**
   * Postal code at the record's full ten characters.
   *
   * Assumptions: `CUST-ADDR-ZIP PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L14, carrying all ten rather than
   * the five the account-view map shows. A consumer moving this value into a shape typed for the screen
   * must narrow it, and the narrowing belongs at that boundary rather than here.
   */
  readonly zipCode: string;

  /** Primary telephone at the record's fifteen characters, or nothing. `CUST-PHONE-NUM-1 PIC X(15)` at L15. */
  readonly phoneNumber1: string | null;

  /** Secondary telephone at the record's fifteen characters, or nothing. `CUST-PHONE-NUM-2 PIC X(15)` at L16. */
  readonly phoneNumber2: string | null;

  /** The national identifier, masked. Derived from `CUST-SSN PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L17. */
  readonly ssnMasked: string;

  /** The government-issued identifier, masked. From `CUST-GOVT-ISSUED-ID PIC X(20)` at L18. */
  readonly governmentIssuedIdMasked: string;

  /**
   * Date of birth, the one date this record stores.
   *
   * Assumptions: `CUST-DOB-YYYY-MM-DD PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L19, whose field name states
   * its own component order, so the stored text is already the form this type describes.
   */
  readonly dateOfBirth: IsoDate;

  /** Electronic funds transfer account. `CUST-EFT-ACCOUNT-ID PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L20. */
  readonly eftAccountId: string;

  /** Primary card-holder indicator, one character. `CUST-PRI-CARD-HOLDER-IND PIC X(01)` at L21. */
  readonly primaryCardHolderIndicator: string;

  /** Credit score as up to three digits of text. `CUST-FICO-CREDIT-SCORE PIC 9(03)` at L22. */
  readonly ficoCreditScore: string;
}

/**
 * The three account limits and balances a card or transaction context needs.
 *
 * Assumptions: this mirrors `AccountContextView` in `account-api.yaml`, three required members, and it
 * exists so that a caller needing only the money values does not read a whole {@link AccountDetail} to
 * reach them. Every member is {@link Money} for the reason that type records.
 */
export interface AccountContextView {
  /** The account's credit limit. `ACCT-CREDIT-LIMIT PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L8. */
  readonly creditLimit: Money;

  /** The cash advance limit. `ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L9. */
  readonly cashCreditLimit: Money;

  /**
   * The balance owed. `ACCT-CURR-BAL PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L7.
   *
   * Assumptions: the over-limit test this value participates in is INCLUSIVE at the limit -- a balance
   * exactly at the credit limit posts and one cent beyond is refused -- so the cent this member carries
   * decides an outcome. That is the whole reason it is text and not a binary fraction.
   */
  readonly currentBalance: Money;
}

/**
 * The account and customer a caller's own card belongs to.
 *
 * Assumptions: this mirrors `CardXrefView` in `account-api.yaml`, both members required, and both are
 * `integer` with `format: int64` -- NOT text. The difference from every screen-facing shape here is the
 * contract's and is deliberate: this is the answer to a lookup whose caller supplied the card, so the
 * two values are identifiers to carry rather than fields to render, and no map width applies to them.
 *
 * Assumptions: an integer is safe for exactly these two and would not be for a card number. Eleven
 * digits reach about ten to the eleventh and nine digits ten to the ninth, both far below the largest
 * exactly representable integer of about 9.007 times ten to the fifteenth, whereas a sixteen-digit card
 * number at ten to the sixteenth is above it and would lose its low digits. That is why this shape
 * carries no card number and why {@link CardXrefResponse}, which does, carries it as text.
 *
 * Assumptions: both are required because the reference record declares both, so a row missing either is
 * a data defect rather than a representable state -- `XREF-CUST-ID PIC 9(09)` and
 * `XREF-ACCT-ID PIC 9(11)` at `app/cpy/CVACT03Y.cpy` L6 and L7.
 */
export interface CardXrefView {
  /** The account the card belongs to. `XREF-ACCT-ID PIC 9(11)` at `app/cpy/CVACT03Y.cpy` L7. */
  readonly accountId: number;

  /** The customer the card belongs to. `XREF-CUST-ID PIC 9(09)` at `app/cpy/CVACT03Y.cpy` L6. */
  readonly customerId: number;
}

/**
 * One row of an account's card cross-reference.
 *
 * Assumptions: this mirrors `CardXrefResponse` in `account-api.yaml`, three required members, and the
 * source record is `CARD-XREF-RECORD` at `app/cpy/CVACT03Y.cpy` L4 to L8, fifty bytes, with
 * `FILLER PIC X(14)` at L8 dropped as padding.
 *
 * Assumptions: it differs from {@link CardXrefView} in exactly one respect and deliberately -- it
 * carries the card the row is FOR. A list of an account's cards cannot be read without something to
 * tell the rows apart, whereas the view's caller supplied the card and needs no echo of it. That single
 * difference is why both shapes exist rather than one.
 */
export interface CardXrefResponse {
  /**
   * The card this row is for, masked to its last four digits.
   *
   * Trade-offs: a masked rendering is unavoidably ambiguous -- two cards on one account sharing their
   * last four digits render identically -- and the ambiguity is accepted because the alternative is a
   * list response that discloses a full primary account number per row. Sixteen characters are allowed
   * rather than four because a mask carries its own filler. The stored field is
   * `XREF-CARD-NUM PIC X(16)` at `app/cpy/CVACT03Y.cpy` L5, and the one shape in this folder carrying
   * the unmasked sixteen digits is the administrative card detail in `ui/src/api/cards.ts`, at an
   * address the service restricts to the administrative group.
   */
  readonly cardNumberMasked: string;

  /** The customer that card belongs to, as digits. `XREF-CUST-ID PIC 9(09)` at L6, `^[0-9]{1,9}$`. */
  readonly customerId: string;

  /** The account that card belongs to, as digits. `XREF-ACCT-ID PIC 9(11)` at L7 of that copybook. */
  readonly accountId: string;
}

/**
 * The body that asks which account and customer a card belongs to.
 *
 * Assumptions: this mirrors `CardXrefLookupRequest` in `account-api.yaml`, whose one member is
 * required. The card travels in a BODY and never in a path segment or query string, because both of
 * those are written verbatim into access logs by the load balancer and the distribution before any
 * application code runs -- so a full card number in either would be persisted outside the application's
 * control. `ui/src/api/cards.ts` records the same reasoning for its own lookup.
 */
export interface CardXrefLookupRequest {
  /** The sixteen-character card to resolve. `XREF-CARD-NUM PIC X(16)` at `app/cpy/CVACT03Y.cpy` L5. */
  readonly cardNumber: string;
}

/**
 * The body that asks for one account by its identifier.
 *
 * Assumptions: this mirrors `AccountLookupRequest` in `account-api.yaml`, one required member typed
 * `integer` with `format: int64`. Inbound lookup bodies carry their identifier as a number while
 * screen-facing response bodies carry it as text, and the split is the contract's: a lookup argument is
 * never rendered, so it has no map width to honour, and eleven digits are exactly representable.
 */
export interface AccountLookupRequest {
  /** The account to resolve. `ACCT-ID PIC 9(11)` at `app/cpy/CVACT01Y.cpy` L5. */
  readonly accountId: number;
}

/**
 * The body that asks for one customer by identifier.
 *
 * Assumptions: this mirrors `CustomerLookupRequest` in `account-api.yaml`, one required member typed
 * `integer` with `format: int64`, on the same terms as {@link AccountLookupRequest}.
 */
export interface CustomerLookupRequest {
  /** The customer to resolve. `CUST-ID PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L5. */
  readonly customerId: number;
}

/**
 * One page of the customer scan.
 *
 * Assumptions: the rows arrive in ascending customer-identifier order, which is the order the reference
 * walk produces by construction rather than an ordering this contract adds -- `app/cbl/CBCUS01C.cbl`
 * reads the file sequentially on its record key. The envelope is the shared five-member one, so it
 * carries no row total and no page number.
 */
export type CustomerPage = PageResponse<CustomerResponse>;

/**
 * One page of an account's card cross-reference rows.
 *
 * Assumptions: the rows arrive in ascending card-number order and a full page holds at most seven of
 * them, the row count the card-list program fixes for its own map; the page is empty when the account
 * holds no card, which is a representable state and not an error.
 */
export type CardXrefPage = PageResponse<CardXrefResponse>;

/**
 * One submission of the account-update screen.
 *
 * Assumptions: this mirrors `AccountUpdateRequest` in `account-api.yaml`, and EVERY member is optional
 * because that schema declares no `required` list at all. That is not an oversight to tighten here: the
 * reference validates the presence of each field itself, field by field, and reports the first failure
 * it reaches on the screen -- so a submission with a field left empty is a case the service answers with
 * a field error, not a body a client should be unable to construct. Making a member mandatory here would
 * move a validation the service owns into the browser, where it would produce a different first message
 * than the reference does.
 *
 * Assumptions: every member is text at the width `app/cpy-bms/COACTUP.CPY` declares, including the five
 * money members, which are fifteen-character screen fields rather than the {@link Money} pattern. The
 * reference validates each as characters before it converts any, so an inbound amount is whatever the
 * user typed and not yet a well-formed decimal. This is the one place in this module where a monetary
 * value is deliberately NOT {@link Money}: applying that type would assert a pattern the caller has not
 * yet been told to satisfy, and would type away the malformed input the service exists to reject.
 *
 * Assumptions: the composite values arrive in PARTS -- three dates as year, month and day, the national
 * identifier as three groups, each telephone number as three groups -- because the map declares a
 * separate input field for each part and the reference validates them separately. Recombining them here
 * would discard which part a field error refers to.
 *
 * Assumptions: this shape is INBOUND ONLY and is never a component of a response. It carries the
 * national identifier in three clear parts and the government-issued identifier in the clear, because an
 * edit has to be able to set them; the read shapes carry only the masked forms, which is why
 * {@link CustomerDetail} has no counterpart to these members.
 *
 * Refactoring Rationale: there is NO version or revision member. The concurrency token travels as a weak
 * entity tag in an `If-Match` header, which the contract declares required on the update operation, and
 * the value is the one the view operation returned in its `ETag`. This replaces the reference's own
 * optimistic check rather than inventing one: `app/cbl/COACTUPC.cbl` snapshots a complete pre-edit
 * before-image from L669 onward -- with the X-over-9 pairs at L671 to L683 that hold each number as
 * characters and redefine it for arithmetic -- and carries `WS-DATACHANGED-FLAG PIC X(1)` at L168 to
 * record whether the stored row moved while the user was typing. A stale tag is answered with HTTP 409,
 * carrying the message that condition names at L521 to L522, verbatim and including its two-word
 * spelling: `Record changed by some one else. Please review`.
 */
export interface AccountUpdateRequest {
  /** The account being edited. `ACCTSIDI PIC X(11)`, for `ACCT-ID PIC 9(11)`. */
  readonly accountId?: string | undefined;

  /** Active status as typed. `ACSTTUSI PIC X(1)`, for `ACCT-ACTIVE-STATUS PIC X(01)`. */
  readonly activeStatus?: string | undefined;

  // WHAT: the five money members of this submission.
  // WHY : Assumptions: fifteen characters each, the map's own field width, which is wider than any
  //       well-formed amount needs precisely so that a malformed entry can be received and reported
  //       rather than truncated into a different number.

  /** Credit limit as typed. `ACRDLIMI PIC X(15)`, for `ACCT-CREDIT-LIMIT`. */
  readonly creditLimit?: string | undefined;

  /** Cash advance limit as typed. `ACSHLIMI PIC X(15)`, for `ACCT-CASH-CREDIT-LIMIT`. */
  readonly cashCreditLimit?: string | undefined;

  /** Balance as typed. `ACURBALI PIC X(15)`, for `ACCT-CURR-BAL`. */
  readonly currentBalance?: string | undefined;

  /** Cycle credits as typed. `ACRCYCRI PIC X(15)`, for `ACCT-CURR-CYC-CREDIT`. */
  readonly currentCycleCredit?: string | undefined;

  /** Cycle debits as typed. `ACRCYDBI PIC X(15)`, for `ACCT-CURR-CYC-DEBIT`. */
  readonly currentCycleDebit?: string | undefined;

  // WHAT: the three account dates, each in three parts.
  // WHY : Assumptions: the map declares a year, month and day field per date, so a client sends what
  //       the user typed into each. The whole-date forms these compose are `ACCT-OPEN-DATE`,
  //       `ACCT-EXPIRAION-DATE` and `ACCT-REISSUE-DATE` at `app/cpy/CVACT01Y.cpy` L10, L11 and L12 --
  //       the middle one carrying the baseline's `EXPIRAION` spelling that the target names
  //       `expirationDate`.

  /** Opening year as typed. `OPNYEARI PIC X(4)`. */
  readonly openDateYear?: string | undefined;

  /** Opening month as typed. `OPNMONI PIC X(2)`. */
  readonly openDateMonth?: string | undefined;

  /** Opening day as typed. `OPNDAYI PIC X(2)`. */
  readonly openDateDay?: string | undefined;

  /** Expiry year as typed. `EXPYEARI PIC X(4)`. */
  readonly expirationDateYear?: string | undefined;

  /** Expiry month as typed. `EXPMONI PIC X(2)`. */
  readonly expirationDateMonth?: string | undefined;

  /** Expiry day as typed. `EXPDAYI PIC X(2)`. */
  readonly expirationDateDay?: string | undefined;

  /** Reissue year as typed. `RISYEARI PIC X(4)`. */
  readonly reissueDateYear?: string | undefined;

  /** Reissue month as typed. `RISMONI PIC X(2)`. */
  readonly reissueDateMonth?: string | undefined;

  /** Reissue day as typed. `RISDAYI PIC X(2)`. */
  readonly reissueDateDay?: string | undefined;

  /** Disclosure group as typed. `AADDGRPI PIC X(10)`, for `ACCT-GROUP-ID PIC X(10)`. */
  readonly groupId?: string | undefined;

  /** The customer the account refers to. `ACSTNUMI PIC X(9)`, for `CUST-ID PIC 9(09)`. */
  readonly customerId?: string | undefined;

  // WHAT: the national identifier in the three groups the map asks for.
  // WHY : Assumptions: the parts compose `CUST-SSN PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L17. They are
  //       in the clear because an edit must be able to set the value; the service stores it encrypted
  //       and every read shape returns only the masked form.

  /** First three digits as typed. `ACTSSN1I PIC X(3)`. */
  readonly ssnPart1?: string | undefined;

  /** Middle two digits as typed. `ACTSSN2I PIC X(2)`. */
  readonly ssnPart2?: string | undefined;

  /** Last four digits as typed. `ACTSSN3I PIC X(4)`. */
  readonly ssnPart3?: string | undefined;

  /** Birth year as typed. `DOBYEARI PIC X(4)`, one part of `CUST-DOB-YYYY-MM-DD`. */
  readonly dateOfBirthYear?: string | undefined;

  /** Birth month as typed. `DOBMONI PIC X(2)`. */
  readonly dateOfBirthMonth?: string | undefined;

  /** Birth day as typed. `DOBDAYI PIC X(2)`. */
  readonly dateOfBirthDay?: string | undefined;

  /** Credit score as typed. `ACSTFCOI PIC X(3)`, for `CUST-FICO-CREDIT-SCORE PIC 9(03)`. */
  readonly ficoCreditScore?: string | undefined;

  /** Given name as typed. `ACSFNAMI PIC X(25)`. */
  readonly firstName?: string | undefined;

  /** Middle name as typed; matches the nullable stored column. `ACSMNAMI PIC X(25)`. */
  readonly middleName?: string | undefined;

  /** Family name as typed. `ACSLNAMI PIC X(25)`. */
  readonly lastName?: string | undefined;

  /** First address line as typed. `ACSADL1I PIC X(50)`. */
  readonly addressLine1?: string | undefined;

  /** Second address line as typed; matches the nullable stored column. `ACSADL2I PIC X(50)`. */
  readonly addressLine2?: string | undefined;

  /** City as typed, stored as `CUST-ADDR-LINE-3 PIC X(50)`. `ACSCITYI PIC X(50)`. */
  readonly city?: string | undefined;

  /**
   * State code as typed. `ACSSTTEI PIC X(2)`.
   *
   * Assumptions: a code outside the seeded reference data is refused by the service as a field error,
   * so this member carries what the user typed rather than a value already known to be valid.
   */
  readonly stateCode?: string | undefined;

  /** Country code as typed. `ACSCTRYI PIC X(3)`. */
  readonly countryCode?: string | undefined;

  /** Postal code as typed, at the SCREEN width of five against a stored ten. `ACSZIPCI PIC X(5)`. */
  readonly zipCode?: string | undefined;

  // WHAT: the two telephone numbers, each in the three groups the map asks for.
  // WHY : Assumptions: the parts compose `CUST-PHONE-NUM-1` and `CUST-PHONE-NUM-2`, both
  //       `PIC X(15)` at `app/cpy/CVCUS01Y.cpy` L15 and L16. The reference validates an area code
  //       against seeded reference data, which is why the area code is its own field and not a slice
  //       of a longer one.

  /** First number's area code as typed. `ACTPHA1I PIC X(3)`. */
  readonly phone1AreaCode?: string | undefined;

  /** First number's exchange prefix as typed. `ACTPHB1I PIC X(3)`. */
  readonly phone1Prefix?: string | undefined;

  /** First number's line digits as typed. `ACTPHC1I PIC X(4)`. */
  readonly phone1LineNumber?: string | undefined;

  /** Second number's area code as typed. `ACTPHA2I PIC X(3)`. */
  readonly phone2AreaCode?: string | undefined;

  /** Second number's exchange prefix as typed. `ACTPHB2I PIC X(3)`. */
  readonly phone2Prefix?: string | undefined;

  /** Second number's line digits as typed. `ACTPHC2I PIC X(4)`. */
  readonly phone2LineNumber?: string | undefined;

  /**
   * The government-issued identifier as typed, in the clear.
   *
   * Assumptions: `ACSGOVTI PIC X(20)`, for `CUST-GOVT-ISSUED-ID PIC X(20)` at `app/cpy/CVCUS01Y.cpy`
   * L18. Inbound only, for the same reason the identifier parts above are: the value has to be
   * settable, and it is returned only masked.
   */
  readonly governmentIssuedId?: string | undefined;

  /** Electronic funds transfer account as typed. `ACSEFTCI PIC X(10)`. */
  readonly eftAccountId?: string | undefined;

  /** Primary card-holder indicator as typed. `ACSPFLGI PIC X(1)`. */
  readonly primaryCardHolderIndicator?: string | undefined;
}

/**
 * What the account-update operation returns.
 *
 * Assumptions: this mirrors `AccountUpdateResponse` in `account-api.yaml`, whose six members are all
 * required. It returns the stored state ALONGSIDE any field errors rather than one or the other, which
 * is what lets the screen redisplay the row it failed to write without a second read -- the behaviour
 * the reference produces by redisplaying its own map.
 *
 * Assumptions: `fieldErrors` is the whole mechanism by which a failing field is marked, and it is
 * load-bearing rather than decorative. The reference marks a field by moving a colour attribute into it,
 * and additionally an asterisk when the field is blank, through the templated block at
 * `app/cpy/CSSETATY.cpy` L17 to L27 -- but that block is gated on `AND CDEMO-PGM-REENTER` at L20, the
 * re-entry flag of `CDEMO-PGM-CONTEXT` at `app/cpy/COCOM01Y.cpy` L29 to L31. A stateless handler has no
 * first-entry-versus-re-entry distinction to gate on, so the marking cannot be inferred from a remembered
 * turn and must be carried in the body. That is why the array is present on a success as well, empty.
 */
export interface AccountUpdateResponse {
  /** The account that was written, echoed as digits. `ACCT-ID PIC 9(11)` at `app/cpy/CVACT01Y.cpy` L5. */
  readonly accountId: string;

  /**
   * The screen's information line, or nothing.
   *
   * Assumptions: forty characters, the width `WS-INFO-MSG` declares at `app/cbl/COACTUPC.cbl` L463.
   * Unlike the view operation, this one does fill it -- it is where a successful update reports itself.
   */
  readonly informationMessage: string | null;

  /**
   * The screen's message line, or nothing.
   *
   * Assumptions: seventy-five characters, the width `WS-RETURN-MSG` declares at `app/cbl/COACTUPC.cbl`
   * L479, matching the two communication-area carriers. This is the channel that carries the conflict
   * sentence when a stale entity tag is refused.
   */
  readonly returnMessage: string | null;

  /**
   * One entry per refused field, empty when nothing was refused.
   *
   * Assumptions: always present, so a screen tests the length rather than the key. Each entry pairs the
   * logical field name with the verbatim sentence the reference raises for it, at the seventy-five
   * characters that sentence's carrier declares.
   */
  readonly fieldErrors: readonly FieldError[];

  /** The account's stored state after the attempt, whether or not it changed. */
  readonly account: AccountDetail;

  /** The customer's stored state after the attempt, at screen widths. */
  readonly customer: CustomerDetail;
}
