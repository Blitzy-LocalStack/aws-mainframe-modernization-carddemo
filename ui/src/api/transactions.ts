/**
 * @file Typed client for the transaction bounded context, written against
 * `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the five operations that contract publishes: the keyset-paged transaction browse replacing
 * `app/cbl/COTRN00C.cbl`, the detail read replacing `app/cbl/COTRN01C.cbl`, the create and its
 * copy-last variant replacing `app/cbl/COTRN02C.cbl`, and the full-balance bill payment replacing
 * `app/cbl/COBIL00C.cbl`. Every target is derived from the operation manifest below rather than
 * written as a literal, so `ui/src/api/contracts.test.ts` can compare this module with that contract
 * in both directions and fail the SPA build on a drift.
 *
 * Nothing in this module belongs to `batch-service`. Transaction posting (`app/cbl/CBTRN02C.cbl`),
 * the pre-posting preflight (`app/cbl/CBTRN01C.cbl`), the reject stream and category-balance
 * maintenance are argument-driven jobs invoked by the orchestrator; they publish no OpenAPI contract,
 * so they have no browser client and are absent here by design rather than by omission.
 *
 * Money on this boundary
 * ----------------------
 * Assumptions: every amount and balance is a STRING in both directions, and this module never
 * converts one to a number. A JSON number is parsed into an IEEE-754 double by most clients, which
 * destroys exactness at the boundary the operator actually reads. The width is not arbitrary either:
 * `app/cpy/CVTRA05Y.cpy` L10 declares `TRAN-AMT PIC S9(09)V99`, an exact fixed-point zoned-decimal
 * field with scale 2, which becomes `NUMERIC(11,2)` in SQL and a `BigDecimal` at scale 2 with
 * `HALF_UP` in Java, serialised as a JSON string by `com.carddemo.common.money.MoneyModule`. The
 * baseline itself moves money as characters, which is independent corroboration rather than a
 * restatement: the bill-pay screen's balance field is `CURBALI PIC X(14)` at
 * `app/cpy-bms/COBIL00.CPY` L66 -- fourteen characters, the width a signed ten-digit value with two
 * decimals and a decimal point occupies. The category balance is the same shape,
 * `TRAN-CAT-BAL PIC S9(09)V99` at `app/cpy/CVTRA01Y.cpy` L9.
 * Trade-offs: a string cannot be summed, so no running total, no subtotal and no client-side
 * credit-limit check is available from this module, and that is the intended consequence rather than
 * a limitation to work around. Arithmetic order changes cents -- the service multiplies at full
 * precision before dividing for exactly that reason -- so the calculation belongs to the side that
 * holds the values as exact fixed-point. Concretely, no `Number()`, `parseFloat`, `parseInt`, unary
 * `+`, `toFixed`, `Math.round`, `Intl.NumberFormat` over a raw amount, or `reduce` over amounts
 * appears anywhere in this file, which makes the prohibition checkable by one search rather than by
 * adjudicating each call site.
 *
 * Paging on this boundary
 * -----------------------
 * Alternatives Considered: paging by an offset, a page number or a row count, rejected outright, so
 * this module exposes no page, offset, size or limit parameter and the contract declares none. Under
 * concurrent insertion the number of rows preceding a position changes between one request and the
 * next, so a page located by counting omits some rows and repeats others, whereas a key already read
 * keeps its place in the ordering whatever is inserted around it. For this table that is the normal
 * case and not an edge case: the ledger is written by the nightly posting chain, so an operator
 * paging through transactions while posting runs is exactly the situation offset paging gets wrong.
 * Assumptions: the substitution is one-to-one rather than an approximation, because the baseline
 * browse state is ALREADY a cursor over keys. `app/cbl/COTRN00C.cbl` carries
 * `CDEMO-CT00-TRNID-FIRST PIC X(16)` at L63, `CDEMO-CT00-TRNID-LAST PIC X(16)` at L64 and
 * `CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)` at L66, and it drives a CICS browse rather than a keyed read:
 * forward at L281 `STARTBR` with `READNEXT` at L286, L298 and L308 and `ENDBR` at L322, backward at
 * L335 with `READPREV` at L340, L352 and L360 and `ENDBR` at L371. The target replaces all four verbs
 * with one keyset query in which a forward step sends the page's `lastKey` and a backward step its
 * `firstKey`, which is the migrated form of that `READPREV` at L660. Cursors are therefore passed
 * through verbatim as opaque strings: the service seals each one, including the direction it was
 * issued for, so this module never parses, decodes, compares, increments or composes one, and
 * anything inferred from its bytes would be inference about an encoding it does not own.
 *
 * Timestamps on this boundary
 * ---------------------------
 * Trade-offs: the two timestamps stay twenty-six-character strings and are never converted to a
 * `Date`. `TRAN-ORIG-TS` and `TRAN-PROC-TS` are both `PIC X(26)` at `app/cpy/CVTRA05Y.cpy` L16 and
 * L17, that is `'YYYY-MM-DD HH:MM:SS.mmmmmm'` at MICROSECOND precision, while a JavaScript `Date`
 * resolves to milliseconds -- so a round trip through `Date` would silently discard the last three
 * digits and break a byte-level comparison against the parity baseline. What is accepted in exchange
 * is that this module offers no local date arithmetic on those values; a screen that formats one does
 * so without routing it through `Date`, and `com.carddemo.common.time.TimestampFormatter` remains the
 * single authority for producing the exact form.
 *
 * Identifiers on this boundary
 * ----------------------------
 * Assumptions: a transaction identifier is a sixteen-character string and never a number.
 * `app/cpy/CVTRA05Y.cpy` L5 declares `TRAN-ID PIC X(16)` -- a character field in the baseline itself
 * -- and a numeric type would fail twice over: it discards the leading zeros the committed seed data
 * carries, and any all-digit value of that width exceeds `Number.MAX_SAFE_INTEGER`, 2^53 - 1 or about
 * 9.007e15, so digits are lost outright. The same reasoning covers `TRAN-CARD-NUM PIC X(16)` at L15
 * and the four-digit category code `TRAN-CAT-CD PIC 9(04)` at L7, a fixed-width key for which `0001`
 * and `1` are one integer but two different keys. The browse filter is the same width and the
 * baseline requires it to be digits, rejecting anything else at `app/cbl/COTRN00C.cbl` L209.
 * Assumptions: a card number ARRIVES masked to its last four digits, and this module neither masks
 * nor unmasks one. The reduction is a server-side mapping concern, and a browser able to perform it
 * would necessarily have received the full number first, which is the disclosure the mapping exists
 * to prevent; no operation in this service is the administrative card-detail endpoint, so no
 * unmasked path belongs here at all. What this module does instead is verify at the boundary that the
 * reduction happened, because the alternative to catching a server-side masking fault here is
 * rendering a full account number into a table, a console line and a bug report.
 *
 * Bill payment
 * ------------
 * Refactoring Rationale: the payment carries its confirmation as an explicit member of the request
 * rather than as remembered client state, because it is a balance-affecting write and must not be
 * reachable by navigating, re-rendering or replaying. The baseline collects that answer on the screen
 * -- `CONFIRMI PIC X(1)` at `app/cpy-bms/COBIL00.CPY` L72, beside the account identifier
 * `ACTIDINI PIC X(11)` at L60 and the displayed balance `CURBALI PIC X(14)` at L66 -- and gates the
 * whole write on it at `app/cbl/COBIL00C.cbl` L210, refusing any other answer at L187. The two
 * resulting writes, the transaction at L233 and the account rewrite at L235, sit in one CICS task
 * whose unit of work is committed by the implicit syncpoint at the L146 `RETURN`; the program
 * contains no explicit `SYNCPOINT` verb, and the target expresses that same single unit of work as
 * one `@Transactional` boundary on the service. Consequently this module performs NO client-side
 * retry of a payment or a create: both are non-idempotent, a retry after an ambiguous timeout risks
 * paying or capturing twice, and durable redelivery belongs to the server's own mechanisms rather
 * than to a browser.
 *
 * Field errors
 * ------------
 * Assumptions: a refusal is reported by the per-field error array inside the problem document, which
 * this module passes through intact and unreordered. It is the entire field-error mechanism in the
 * target, because the stateless handler has no re-entry discriminator: the baseline gates its
 * red-highlight on `CDEMO-PGM-REENTER` at `app/cpy/CSSETATY.cpy` L18 to L20 before moving `DFHRED`
 * at L21 and the literal `'*'` at L24, and a handler that answers with a field-error array has no
 * first-entry-versus-re-entry distinction left to make.
 *
 * The two operations with two success statuses
 * -------------------------------------------
 * Refactoring Rationale: `addTransaction`, `copyLastTransaction` and `payAccountBalanceInFull` each
 * declare both a 200 and a 201, and this module reports a DISCRIMINATED UNION rather than one
 * optional-heavy shape. The distinction is the baseline's confirmation gate: `app/cbl/COTRN02C.cbl`
 * and `app/cbl/COBIL00C.cbl` both redisplay with the record unwritten when the answer was withheld,
 * and both write and report an identifier when it was given. Collapsing the two into a single
 * interface with every member optional would let a caller read a transaction identifier that is
 * absent precisely when nothing was written, which is the one mistake this boundary can make that
 * money depends on.
 */

import { getApiClient, requestPath } from './client';
import type {
  BillPaymentOutcome,
  BillPaymentPreview,
  BillPaymentRequest,
  BillPaymentResponse,
  ContractOperation,
  PageResponse,
  TransactionAddOutcome,
  TransactionAddPreview,
  TransactionCreateRequest,
  TransactionCreated,
  TransactionDetail,
  TransactionListQuery,
  TransactionSummary,
} from './types';

/*
 * WHY : Refactoring Rationale: the ledger wire shapes are RE-EXPORTED from ./types rather than declared
 *       here, so every consumer's import path is unchanged while each shape has one definition.
 *       `ui/src/api/contracts.test.ts` gates both halves of that arrangement -- it fails a client
 *       module that declares a shape of its own, and equally one that stops re-exporting its shapes,
 *       since the second would compile while quietly breaking the promise that no consumer import had
 *       to move when the declarations were relocated.
 */
export type {
  TransactionSummary,
  TransactionDetail,
  TransactionCreateRequest,
  TransactionAddPreview,
  TransactionCreated,
  TransactionAddOutcome,
  BillPaymentRequest,
  BillPaymentPreview,
  BillPaymentResponse,
  BillPaymentOutcome,
  TransactionListQuery,
} from './types';

const LIST_TRANSACTIONS: ContractOperation = {
  method: 'GET',
  path: '/api/v1/transactions',
  operationId: 'listTransactions',
};

const ADD_TRANSACTION: ContractOperation = {
  method: 'POST',
  path: '/api/v1/transactions',
  operationId: 'addTransaction',
};

/*
 * WHY : Refactoring Rationale: the copy-last action is declared here because the service now publishes
 *       it. `app/cbl/COTRN02C.cbl` binds it to PF5 at L146 and L147 and performs COPY-LAST-TRAN-DATA at
 *       L471, and the migrated transcription existed with no route, no contract entry and no client --
 *       so a documented baseline capability was unreachable. Declaring it here is what keeps this
 *       manifest exhaustive against the contract, which `ui/src/api/contracts.test.ts` compares in both
 *       directions.
 */
const COPY_LAST_TRANSACTION: ContractOperation = {
  method: 'POST',
  path: '/api/v1/transactions/copy-last',
  operationId: 'copyLastTransaction',
};

/*
 * WHY : Assumptions: the placeholder is spelled `{transactionId}` because that is the parameter name
 *       the contract's path key and its `TransactionId` path parameter both declare, and `requestPath`
 *       refuses a name it cannot substitute rather than emitting a target that still carries the
 *       literal placeholder. The SPA's own route spelling is a screen-layer concern and does not reach
 *       this module: no transaction route exists in `ui/src/router.tsx` yet, and when one is added its
 *       segment name is free to differ from the service's without touching this manifest.
 */
const VIEW_TRANSACTION: ContractOperation = {
  method: 'GET',
  path: '/api/v1/transactions/{transactionId}',
  operationId: 'viewTransaction',
};

const PAY_ACCOUNT_BALANCE_IN_FULL: ContractOperation = {
  method: 'POST',
  path: '/api/v1/billpay',
  operationId: 'payAccountBalanceInFull',
};

/**
 * Every operation `transaction-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`.
 */
export const TRANSACTION_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  LIST_TRANSACTIONS,
  ADD_TRANSACTION,
  COPY_LAST_TRANSACTION,
  VIEW_TRANSACTION,
  PAY_ACCOUNT_BALANCE_IN_FULL,
];

/** HTTP status the three write operations answer when the record was actually written. */
const HTTP_CREATED = 201;

/**
 * The masked rendering a transaction detail must carry: twelve asterisks then the last four digits.
 *
 * Assumptions: this recognises the reduction the service performed and never performs one. It is a
 * predicate over a value already received, so it cannot disclose anything the response did not
 * already contain, and it holds the shape in one place so the check cannot drift between operations.
 */
const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/**
 * Builds the browse query string members from the caller's criteria.
 *
 * Trade-offs: the mutually-exclusive pair is refused HERE, before dispatch, rather than being left to
 * the service. The contract states that a starting identifier cannot be combined with a cursor --
 * a cursor already states a position -- and answers the combination with 400 instead of silently
 * resolving it in favour of one. Refusing locally turns a guaranteed round trip into an immediate,
 * attributable error naming both inputs. What this deliberately is NOT is a substitute for
 * server-side validation: the service remains the authority on every value, including the width and
 * digit-shape of the identifier, and it is the side that returns the per-field error array a screen
 * renders. This check therefore refuses only the one combination that cannot be meaningful, and
 * passes every individual value through untouched.
 * @param {TransactionListQuery} query - The caller's criteria, any member of which may be absent.
 * @returns {Record<string, string>} The query members to send, empty when the caller supplied none.
 * @throws {RangeError} If a starting identifier and a cursor are supplied together.
 */
function browseQueryParameters(query: TransactionListQuery): Record<string, string> {
  if (query.transactionIdFilter !== undefined && query.cursor !== undefined) {
    throw new RangeError(
      'A transaction browse takes either a starting identifier or a cursor, not both; a cursor' +
        ' already states the position to read from.',
    );
  }

  const params: Record<string, string> = {};
  if (query.transactionIdFilter !== undefined) {
    params.transactionIdFilter = query.transactionIdFilter;
  }
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    // Assumptions: the direction accompanies the cursor and is omitted without one, because the
    //   contract declares it meaningful only alongside a cursor -- a direction alone would describe a
    //   position relative to nothing. `next` is named explicitly rather than left to the contract's
    //   default so the request records which side was asked for, which is what makes a sealed cursor
    //   replayed in the wrong direction a 400 the caller can read rather than a silently wrong page.
    params.direction = query.direction ?? 'next';
  }
  return params;
}

/**
 * Lists transactions as one keyset-paged browse, optionally starting from a given identifier.
 * @param {TransactionListQuery} [query] - Optional criteria: an exact sixteen-digit starting
 *   identifier, OR a sealed cursor with the direction it is being replayed for. The two are mutually
 *   exclusive. Omit the argument entirely for the opening page.
 * @returns {Promise<PageResponse<TransactionSummary>>} One bounded page: up to ten rows in ascending
 *   identifier order, plus the `firstKey` and `lastKey` cursors a backward or forward step is issued
 *   from and the `hasNext` flag the service sets by reading beyond the page rather than by counting.
 * @throws {RangeError} If a starting identifier and a cursor are supplied together.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document with its per-field error array: HTTP 400 for a malformed
 *   identifier or a cursor the service will not accept, 401 when the token is absent or expired, 403
 *   for a caller outside the permitted group, and 500 for a service-side failure.
 */
export async function listTransactions(
  query: TransactionListQuery = {},
): Promise<PageResponse<TransactionSummary>> {
  const params = browseQueryParameters(query);

  // Assumptions: an empty criteria set is sent as `undefined` rather than as an empty object, and the
  //   two are equivalent in the composed URL -- Axios omits a query string for either -- so this is
  //   about what the dispatched CONFIGURATION says rather than about the request on the wire. An
  //   interceptor or a test inspecting `config.params` then reads "no query was asked for" instead of
  //   "an empty query was asked for", which is the distinction that matters when the opening page and
  //   a page whose every criterion was dropped have to be told apart.
  const response = await getApiClient().get<PageResponse<TransactionSummary>>(
    requestPath(LIST_TRANSACTIONS),
    { params: Object.keys(params).length === 0 ? undefined : params },
  );
  return response.data;
}

/**
 * Retrieves one transaction in full.
 * @param {string} transactionId - The transaction's sixteen-character identifier, passed through as
 *   the string it is and percent-encoded into the target by `requestPath`.
 * @returns {Promise<TransactionDetail>} The transaction's fourteen carried fields, with every
 *   monetary value a string, both timestamps in their twenty-six-character form, and the card number
 *   reduced to its last four digits.
 * @throws {RangeError} If the response carries a card number the service did not reduce.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document: HTTP 400 for a malformed identifier, 401 when the token is
 *   absent or expired, 403 for a caller outside the permitted group, 404 when no transaction carries
 *   the identifier, and 500 for a service-side failure.
 */
export async function viewTransaction(transactionId: string): Promise<TransactionDetail> {
  const response = await getApiClient().get<TransactionDetail>(
    requestPath(VIEW_TRANSACTION, { transactionId }),
  );

  /*
   * WHY : Assumptions: the reduced rendering is checked at the boundary rather than trusted, because
   *       the failure it catches is a server-side mapping fault and the alternative to catching it
   *       here is rendering a full account number into a table, a console line and a bug report. The
   *       refused value is described and never reproduced in the message, for the same reason.
   */
  if (!MASKED_CARD_NUMBER.test(response.data.cardNumber)) {
    throw new RangeError(
      'Transaction detail must carry a card number reduced to its last four digits; a full value was' +
        ' returned.',
    );
  }
  return response.data;
}

// WHY : Assumptions: both derive the outcome from the HTTP STATUS and not from the `written` member of
//       the 200 body. That member is present on the preview, so reading it would work today and would
//       start reporting a write the moment a future body carried it set on a non-writing turn. The
//       status is what the contract makes normative -- 201 with a required `Location` header for the
//       turn that wrote, 200 for the turn that did not -- so deriving from it keeps this module
//       agreeing with the contract rather than with one property of one body.
// WHY : Refactoring Rationale: neither function retries, and neither generates an identifier. The
//       service assigns the identifier -- the baseline reads the highest key and increments it at
//       `app/cbl/COTRN02C.cbl` L444 and L449 with nothing holding a lock across the two statements --
//       so a client-side value would collide under exactly the concurrency the keyset paging above
//       exists to tolerate.
// WHY : Alternatives Considered: narrowing each branch with a runtime type predicate over the body,
//       rejected because the status has ALREADY established which shape arrived. A predicate would
//       test the same fact a second time from a different source, and the two can disagree -- a 201
//       whose body a predicate declined would leave a written transaction reported as a preview, which
//       is the failure the discriminated union exists to prevent. An assertion is required on both
//       branches here, unlike the payment below, because neither `TransactionAddPreview` nor
//       `TransactionCreated` is structurally assignable to the other: the first requires `written` and
//       the second requires `transactionId`.

/**
 * Submits a transaction, either as an unconfirmed preview or as a confirmed write.
 *
 * Assumptions: the caller decides which by supplying `confirmation`, and this function reports which
 * occurred rather than inferring intent. That mirrors the baseline, where one screen submission either
 * redisplays for confirmation or writes, decided by the single character `CONFIRMI PIC X(1)` at
 * `app/cpy-bms/COTRN02.CPY` L138.
 * @param {TransactionCreateRequest} request - The transaction fields, every amount a string, with
 *   `confirmation` set to a 'Y' answer to write and omitted or set to 'N' to preview.
 * @returns {Promise<TransactionAddOutcome>} `CREATED` carrying the identifier the service assigned
 *   and the amount as it normalised it, or `PREVIEWED` carrying that amount with nothing written.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document with its per-field error array: HTTP 400 for a field the
 *   service refused, 401 when the token is absent or expired, 403 for a caller outside the permitted
 *   group, 404 for an unknown card or account, 409 for a concurrent change, 500 for a service-side
 *   failure and 503 when the ledger is unavailable.
 */
export async function addTransaction(
  request: TransactionCreateRequest,
): Promise<TransactionAddOutcome> {
  const response = await getApiClient().post<TransactionAddPreview | TransactionCreated>(
    requestPath(ADD_TRANSACTION),
    request,
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'CREATED', created: response.data as TransactionCreated };
  }
  return { outcome: 'PREVIEWED', preview: response.data as TransactionAddPreview };
}

/**
 * Re-captures the most recently stored transaction as a new one, previewing or writing it.
 *
 * Assumptions: the request shape is the SAME one `addTransaction` takes, because the baseline reaches
 * this action from the same screen with the same fields keyed. Eleven of those fields are overwritten
 * from the copied record -- `app/cbl/COTRN02C.cbl` L480 to L493 -- so values supplied for them are
 * replaced rather than merged; they stay required because the contract requires them, the baseline
 * having validated the key fields at L473 against the screen the operator was already on.
 * @param {TransactionCreateRequest} request - The submission whose key fields select the account or
 *   card and whose `confirmation` decides whether the copied capture is written.
 * @returns {Promise<TransactionAddOutcome>} `CREATED` carrying the identifier the service assigned to
 *   the copied record, or `PREVIEWED` carrying the amount it read with nothing written.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document with its per-field error array: HTTP 400 for a field the
 *   service refused, 401 when the token is absent or expired, 403 for a caller outside the permitted
 *   group, 404 for an unknown card or account or for an empty table with no row to copy, 409 for a
 *   concurrent change, 500 for a service-side failure and 503 when the ledger is unavailable.
 */
export async function copyLastTransaction(
  request: TransactionCreateRequest,
): Promise<TransactionAddOutcome> {
  const response = await getApiClient().post<TransactionAddPreview | TransactionCreated>(
    requestPath(COPY_LAST_TRANSACTION),
    request,
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'CREATED', created: response.data as TransactionCreated };
  }
  return { outcome: 'PREVIEWED', preview: response.data as TransactionAddPreview };
}

/**
 * Pays an account's balance in full, either as an unconfirmed preview or as a confirmed payment.
 *
 * Assumptions: `currentBalance` on the paid outcome is the balance as it stood BEFORE the payment,
 * which is the figure the baseline displays and the amount the transaction carries. Statement order in
 * the reference settles it: `app/cbl/COBIL00C.cbl` L193 moves the balance to the display field, L224
 * reuses that same untouched value as the transaction amount, L233 writes, and only then does L234
 * subtract. Reading it as the balance remaining would show the operator the amount just paid under the
 * wrong label -- and the remaining figure is invariably zero here, because this operation pays the
 * balance in full.
 * @param {BillPaymentRequest} request - The account, with `confirmation` set to a 'Y' answer to pay
 *   and omitted or set to 'N' to preview the payable balance. The payment cannot be made without it.
 * @returns {Promise<BillPaymentOutcome>} `PAID` carrying the transaction the payment wrote and the
 *   balance as it stood before it, or `PREVIEWED` carrying the balance a confirmed request would pay
 *   -- absent on a declined answer, which reaches no account read at all.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document with its per-field error array: HTTP 400 for a malformed
 *   account identifier or a confirmation outside the accepted set, 401 when the token is absent or
 *   expired, 403 for a caller outside the permitted group, 404 for an unknown account, 409 for a
 *   concurrent change to the balance, 500 for a service-side failure and 503 when the ledger is
 *   unavailable.
 */
export async function payAccountBalanceInFull(
  request: BillPaymentRequest,
): Promise<BillPaymentOutcome> {
  const response = await getApiClient().post<BillPaymentPreview | BillPaymentResponse>(
    requestPath(PAY_ACCOUNT_BALANCE_IN_FULL),
    request,
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'PAID', payment: response.data as BillPaymentResponse };
  }

  // WHY : Assumptions: this branch carries NO assertion where the one above does, and the asymmetry is
  //       a property of the two shapes rather than an oversight. Every member `BillPaymentPreview`
  //       declares is one `BillPaymentResponse` also declares or leaves optional, so the response shape
  //       is structurally assignable to the preview shape and the union already satisfies this slot; an
  //       assertion here would be refused by the type-aware lint rule as unnecessary. The reverse does
  //       not hold, because the response declares `transactionId` and `currentBalance` that the preview
  //       has no member for, which is why the branch above must narrow explicitly.
  return { outcome: 'PREVIEWED', preview: response.data };
}
