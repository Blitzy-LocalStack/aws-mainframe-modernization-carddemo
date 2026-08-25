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
 * The three operations with two success statuses
 * ---------------------------------------------
 * Refactoring Rationale: `addTransaction`, `copyLastTransaction` and `payAccountBalanceInFull` each
 * declare both a 200 and a 201, and this module reports a DISCRIMINATED UNION rather than one
 * optional-heavy shape. The distinction is the baseline's confirmation gate: `app/cbl/COTRN02C.cbl`
 * and `app/cbl/COBIL00C.cbl` both redisplay with the record unwritten when the answer was withheld,
 * and both write and report an identifier when it was given. Collapsing the two into a single
 * interface with every member optional would let a caller read a transaction identifier that is
 * absent precisely when nothing was written, which is the one mistake this boundary can make that
 * money depends on.
 */

import {
  CONFIRMATION_ANSWERS,
  getApiClient,
  isConfirmingAnswer,
  keysetPagingMembers,
  requestPath,
  requireWithinPublishedWidths,
} from './client';
import { MASKED_CARD_NUMBER } from './masking';
import type {
  BillPaymentOutcome,
  BillPaymentPreview,
  BillPaymentRequest,
  BillPaymentResponse,
  ContractOperation,
  CopiedTransactionData,
  CopyLastTransactionRequest,
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
  CopiedTransactionData,
  CopyLastTransactionRequest,
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

/*
 * WHY : Refactoring Rationale: the four helpers below check a write response MEMBER BY MEMBER, and the
 *       revision they replace cast each body to its outcome type with a type assertion. The assertion
 *       stated a guarantee without testing it, so a body that omitted `transactionId` or `amount`
 *       produced a typed outcome whose members read `undefined` -- and the call still reported success.
 *       That is the one failure at this boundary that money depends on: an operator would be shown a
 *       confirmed capture with no identifier and no amount, several frames away from the response that
 *       was already wrong when it arrived. `./reporting` reached the same conclusion at
 *       `submitTransactionReport` and checks its started-run members for exactly this reason.
 * WHY : Assumptions: the STATUS still selects which shape is expected, and these helpers never choose a
 *       shape. 201 means the record was written and 200 means it was not, which is what the contract
 *       makes normative, so a body that fails the check for its status is reported as a malformed
 *       response rather than re-read as the other outcome -- re-reading it is precisely what the
 *       discriminated union exists to prevent, because a written transaction reported as a preview
 *       invites a second submission of the same money.
 * WHY : Alternatives Considered: (1) trusting the status alone, which is what the previous revision
 *       did. Rejected above. (2) validating with a schema library. Rejected because it would add a
 *       runtime dependency, and the three shapes between them declare eleven members whose rules are
 *       already written down in the contract this file is authored against -- so the checks are short
 *       enough to read, and reading them is what makes them auditable against that document. (3)
 *       checking presence and JavaScript type but not the published pattern. Rejected because the
 *       members at issue are money and identifiers: `TransactionAmount` and `AccountBalance` are exact
 *       decimal strings with two decimal places, and a value such as `50.4` or `5e1` is a string of the
 *       right type that no screen can render and no reader should have to defend against.
 * WHY : Trade-offs: a response that breaches the contract is now refused where it used to be rendered,
 *       so a service fault surfaces as a named error on the screen that made the call instead of as
 *       blank fields. That is the intended exchange at a financial boundary. What is given up is
 *       tolerance of a service that drifts from its own document, which is not a property worth keeping.
 */

/** The shape the contract publishes for a transaction identifier: exactly sixteen digits. */
const TRANSACTION_ID = /^[0-9]{16}$/u;

/**
 * The shape the contract publishes for a transaction amount.
 *
 * Assumptions: this is `TransactionAmount` from `transaction-api.yaml` verbatim -- an optional sign, one
 * to nine integer digits, a point, and exactly two decimals -- which is the serialised form of
 * `TRAN-AMT PIC S9(09)V99` at `app/cpy/CVTRA05Y.cpy` L10. The two decimal places are mandatory rather
 * than optional, because the scale is part of the value: `50.4` and `50.40` are the same number but only
 * one of them is the rendering an exact fixed-point field produces.
 */
const TRANSACTION_AMOUNT = /^-?[0-9]{1,9}\.[0-9]{2}$/u;

/** The shape the contract publishes for an account identifier: exactly eleven digits. */
const ACCOUNT_ID = /^[0-9]{11}$/u;

/*
 * WHY : ⚠️ Refactoring Rationale: a `RESOLVED_CARD_NUMBER = /^[0-9]{16}$/u` stood here, admitting the
 *       preview's card member as SIXTEEN DIGITS where every other card member this client reads is
 *       masked. It defended the exception on the ground that the resolved card "is not a read of
 *       somebody's card" but the key the row would be written under, so masking it would withhold the
 *       one value the operator is confirming. The second half was right about the operator and wrong
 *       about the remedy: AAP section 0.4.1.9 masks a primary account number in every response but the
 *       administrative card-detail read, and `transaction-api.yaml`'s own `CardNumber` schema states the
 *       unmasked form appears on requests only -- so this pattern was validating a body the contract
 *       forbids and would have PASSED the very disclosure a card guard exists to catch.
 * WHY : Assumptions: the member is now validated by the shared `MASKED_CARD_NUMBER` imported above, so
 *       the preview is held to the same rendering as every list row and every detail read, and the
 *       comparison the digits were for is made server-side against the sealed binding token. Nothing in
 *       this module admits sixteen digits in a RESPONSE any longer.
 */

/*
 * WHY : Assumptions: the binding token is validated for SHAPE and never interpreted. Its pattern is the
 *       one `transaction-api.yaml` publishes for `CursorToken`, which is also the shape
 *       `com.carddemo.common.web.CursorToken` mints -- a version marker, a fixed-width nonce and an
 *       enciphered payload, dot-separated. Checking it here means a body carrying a raw card number in
 *       this member, which is the mistake the withdrawn pattern above would have accepted, is refused
 *       before any screen can store it.
 */
const CONFIRMATION_TOKEN = /^v2\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{1,200}$/u;

/**
 * The shape the contract publishes for an account balance.
 *
 * Assumptions: one integer digit more than an amount admits, because `ACCT-CURR-BAL PIC S9(10)V99` at
 * `app/cpy/CVACT01Y.cpy` L7 is a ten-digit field where the transaction amount is a nine-digit one. The
 * two are deliberately separate constants for that reason; sharing one would silently widen or narrow
 * whichever member did not own it.
 */
const ACCOUNT_BALANCE = /^-?[0-9]{1,10}\.[0-9]{2}$/u;

/*
 * WHY : Assumptions: the six shapes below serve the COPIED members alone, and each is the shape the
 *       contract publishes for the component that member points at -- `TransactionTypeCode`,
 *       `TransactionCategoryCode`, `MerchantId`, `DateOnly10`, and the plain bounded strings the source,
 *       description, merchant name, city and postal code carry. They are declared here rather than reused
 *       from the request builder because nothing in this module validated a REQUEST field before: a
 *       request is composed by the screen and refused by the service, whereas these values arrive over
 *       the wire and are then sent back through the capture operation, which is what makes checking them
 *       on arrival worth the six declarations.
 * WHY : Alternatives Considered: one permissive `typeof value === 'string'` test for all nine text
 *       members. Rejected for the reason the block above gives for the money members and for one more
 *       that is specific to this path: a copied value that fails the capture operation's own bound would
 *       be adopted into the form, shown to the operator as the value about to be written, and then
 *       refused on the confirming turn -- reporting a field the operator never typed.
 */

/** `TransactionTypeCode`: exactly two digits. */
const TYPE_CODE = /^[0-9]{2}$/u;

/** `TransactionCategoryCode`: exactly four digits. */
const CATEGORY_CODE = /^[0-9]{4}$/u;

/** `MerchantId`: exactly nine digits, zero-padded to the width of the map field. */
const MERCHANT_ID = /^[0-9]{9}$/u;

/**
 * `DateOnly10`: an ISO calendar date, ten characters.
 *
 * Assumptions: the shape is checked and the CALENDAR is not -- `2026-02-31` satisfies this and is not a
 * date. That is deliberate: the service validates both, through the same date-edit routine the reference
 * calls at `app/cbl/COTRN02C.cbl` L389 to L427, so a second calendar implementation here would be a
 * second authority on which dates exist.
 */
const ISO_DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/u;

/** A non-empty string of at most ten characters, as `TRAN-SOURCE` and `TRAN-MERCHANT-ZIP` declare. */
const BOUNDED_TEXT_10 = /^.{1,10}$/su;

/** A non-empty string of at most fifty characters, as the two merchant name and city members declare. */
const BOUNDED_TEXT_50 = /^.{1,50}$/su;

/** A non-empty string of at most a hundred characters, as `TRAN-DESC` declares. */
const BOUNDED_TEXT_100 = /^.{1,100}$/su;

/**
 * Reads a response body as a member bag, refusing anything that is not one.
 *
 * Assumptions: `typeof null` is `'object'`, so null is excluded explicitly; an array is admitted by this
 * check and then fails on its first absent member, which reports the same breach one line later and
 * needs no separate branch.
 * @param {unknown} body - The parsed response body exactly as the transport delivered it.
 * @param {string} outcome - The outcome the status selected, named in the message so a reader knows
 *   which of the two shapes was expected.
 * @returns {Record<string, unknown>} The body, readable member by member.
 * @throws {RangeError} If the body is not a non-null object.
 */
function requireBody(body: unknown, outcome: string): Record<string, unknown> {
  if (!isMemberBag(body)) {
    throw new RangeError(`A ${outcome} response must carry an object body.`);
  }
  return body;
}

/**
 * Reports whether a value is a non-null object whose members can be read individually.
 *
 * Assumptions: this is the same predicate `./client` declares for the problem document it validates, and
 * it is declared here rather than imported because it is one expression of a language rule rather than a
 * shared contract -- importing it would couple this module's response validation to that module's
 * failure handling for no gain. The narrowing is what removes the need for a type assertion: `object` has
 * no index signature, so a body would otherwise have to be asserted before any member could be read, and
 * an assertion is exactly what these helpers exist to replace.
 * @param {unknown} value - A response body of unknown shape.
 * @returns {boolean} `true` for a non-null object, narrowing it to a bag of unknown members so each one
 *   is checked before use.
 */
function isMemberBag(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Requires one member to be a string matching the shape the contract publishes for it.
 *
 * Assumptions: the message names the MEMBER and the outcome and never the rejected value. Two of the
 * members checked through here are an account identifier and a transaction identifier, which the
 * migration's sensitive-data logging contract names alongside the primary account number, so quoting a
 * rejected value would place it in whatever renders or logs the failure.
 * @param {unknown} value - The member as read from the body.
 * @param {RegExp} shape - The published pattern the member must match.
 * @param {string} member - The member's name in the contract, for the message.
 * @param {string} outcome - The outcome the status selected, for the message.
 * @returns {string} The member, once it is a string of the published shape.
 * @throws {RangeError} If the member is absent, is not a string, or does not match the shape.
 */
function requireShaped(value: unknown, shape: RegExp, member: string, outcome: string): string {
  if (typeof value !== 'string' || !shape.test(value)) {
    throw new RangeError(
      `A ${outcome} response must carry ${member} in the form the contract publishes for it.`,
    );
  }
  return value;
}

/**
 * Requires one boolean member to carry the fixed value the contract declares for this outcome.
 *
 * Assumptions: this CROSS-CHECKS the flag against the status rather than deriving the outcome from it.
 * The contract fixes `written` to false on a preview and `paid` to true on a payment and false on a
 * preview, so a flag disagreeing with the status is a service fault in which the two sources of the same
 * fact contradict each other. Reporting it is the only safe reading: resolving it in favour of the flag
 * would report a write that did not happen, and resolving it silently in favour of the status would hide
 * a service that has begun answering incorrectly.
 * @param {unknown} value - The member as read from the body.
 * @param {boolean} expected - The value the contract fixes for this outcome.
 * @param {string} member - The member's name in the contract, for the message.
 * @param {string} outcome - The outcome the status selected, for the message.
 * @returns {boolean} The member, once it carries the fixed value.
 * @throws {RangeError} If the member is absent, is not a boolean, or contradicts the status.
 */
function requireFixedFlag(
  value: unknown,
  expected: boolean,
  member: string,
  outcome: string,
): boolean {
  if (value !== expected) {
    throw new RangeError(
      `A ${outcome} response must carry ${member} set to ${String(expected)}, which is what the` +
        ' contract fixes for this status.',
    );
  }
  return value;
}

/**
 * Reads an optional sentence member, admitting absence and an explicit null alike.
 *
 * Assumptions: absence and null are BOTH admitted and are kept distinct rather than folded together.
 * The service sets `default-property-inclusion: always`, so a null sentence arrives as a present null;
 * a member missing entirely is a body from an older or different producer. Neither is substituted with
 * an empty string, because a blank message band renders as a deliberate silence that is
 * indistinguishable from correct behaviour -- the reasoning `./reporting` records for the same member.
 * @param {unknown} value - The member as read from the body.
 * @param {string} outcome - The outcome the status selected, for the message.
 * @returns {string | null | undefined} The sentence, an explicit null, or undefined when absent.
 * @throws {RangeError} If the member is present and is neither a string nor null.
 */
function optionalSentence(value: unknown, outcome: string): string | null | undefined {
  if (value === undefined || value === null || typeof value === 'string') {
    return value;
  }
  throw new RangeError(`A ${outcome} response must carry returnMessage as a string or null.`);
}

/**
 * Validates the body a written transaction answers with.
 *
 * Assumptions: all three members are required, which is what `TransactionCreated` declares in the
 * contract, and the sentence is required as a non-empty string because the service assembles it on
 * every written turn -- `TransactionAddService.appendTransaction` supplies it unconditionally from its
 * one production call site, and the contract states its assembly verbatim from
 * `app/cbl/COTRN02C.cbl` L728 to L733.
 * @param {unknown} body - The parsed 201 body.
 * @returns {TransactionCreated} The written transaction's identifier, amount and sentence.
 * @throws {RangeError} If any of the three required members is absent or malformed.
 */
function requireTransactionCreated(body: unknown): TransactionCreated {
  const members = requireBody(body, 'created transaction');
  const transactionId = requireShaped(
    members.transactionId,
    TRANSACTION_ID,
    'transactionId',
    'created transaction',
  );
  const amount = requireShaped(members.amount, TRANSACTION_AMOUNT, 'amount', 'created transaction');
  const returnMessage = members.returnMessage;
  if (typeof returnMessage !== 'string' || returnMessage.length === 0) {
    throw new RangeError(
      'A created transaction response must carry the confirmation sentence the contract requires' +
        ' beside the identifier it assigned.',
    );
  }
  return { transactionId, amount, returnMessage };
}

/**
 * Validates the ten copied members a copy-last turn publishes, and the row they came from.
 *
 * Assumptions: the shape is checked member by member rather than trusted, on the same footing as every
 * other body this module reads, and the reason is stronger here than elsewhere: these values are about to
 * be sent BACK through the capture operation, so a member the service could not have produced would be
 * carried into a write. Each is checked against the shape the contract publishes for it, which is the
 * same shape the capture request declares — a value that fails here would have been refused there.
 *
 * Assumptions: an absent or null member is a MALFORMED body on this path, not a tolerated omission, and
 * that is the opposite of how `returnMessage` is read two functions below. The distinction is what each
 * member means: a missing message line is a screen with nothing to say, whereas a missing copied value is
 * a screen that cannot be filled in, and adopting it would leave one control blank while the operator
 * confirmed the rest.
 * @param {unknown} value - The `copied` member of a 200 body, which is null on the capture operation.
 * @returns {CopiedTransactionData | null} The copied screen state, or null when the turn copied nothing.
 * @throws {RangeError} If the member is present but is not an object, or carries a malformed member.
 */
function optionalCopiedSource(value: unknown): CopiedTransactionData | null {
  if (value === undefined || value === null) {
    return null;
  }

  const members = requireBody(value, 'copied transaction');
  return {
    sourceTransactionId: requireShaped(
      members.sourceTransactionId,
      TRANSACTION_ID,
      'sourceTransactionId',
      'copied transaction',
    ),
    typeCode: requireShaped(members.typeCode, TYPE_CODE, 'typeCode', 'copied transaction'),
    categoryCode: requireShaped(
      members.categoryCode,
      CATEGORY_CODE,
      'categoryCode',
      'copied transaction',
    ),
    source: requireShaped(members.source, BOUNDED_TEXT_10, 'source', 'copied transaction'),
    description: requireShaped(
      members.description,
      BOUNDED_TEXT_100,
      'description',
      'copied transaction',
    ),
    merchantId: requireShaped(members.merchantId, MERCHANT_ID, 'merchantId', 'copied transaction'),
    merchantName: requireShaped(
      members.merchantName,
      BOUNDED_TEXT_50,
      'merchantName',
      'copied transaction',
    ),
    merchantCity: requireShaped(
      members.merchantCity,
      BOUNDED_TEXT_50,
      'merchantCity',
      'copied transaction',
    ),
    merchantZip: requireShaped(
      members.merchantZip,
      BOUNDED_TEXT_10,
      'merchantZip',
      'copied transaction',
    ),
    originDate: requireShaped(members.originDate, ISO_DATE, 'originDate', 'copied transaction'),
    processDate: requireShaped(members.processDate, ISO_DATE, 'processDate', 'copied transaction'),
  };
}

/**
 * Validates the body an unwritten transaction submission answers with.
 * @param {unknown} body - The parsed 200 body.
 * @returns {TransactionAddPreview} The amount a confirmed submission would capture, the false capture
 *   flag, the prompt when the service sent one, and the copied screen state on a copy-last turn.
 * @throws {RangeError} If a required member is absent or malformed, or the capture flag contradicts the
 *   status.
 */
function requireTransactionAddPreview(body: unknown): TransactionAddPreview {
  const members = requireBody(body, 'previewed transaction');
  const amount = requireShaped(
    members.amount,
    TRANSACTION_AMOUNT,
    'amount',
    'previewed transaction',
  );
  const written = requireFixedFlag(members.written, false, 'written', 'previewed transaction');
  const returnMessage = optionalSentence(members.returnMessage, 'previewed transaction');
  // Refactoring Rationale: the member is CARRIED, as null when the service sent none, where this block
  //   used to omit it and the type declared it optional. `transaction-api.yaml` publishes it in the
  //   schema's `required` list against a `ReturnMessage` component whose second branch is `type: null`,
  //   and always-inclusion is pinned for the fleet in
  //   `services/common-lib/src/main/resources/carddemo-common-defaults.yml` -- so the wire always
  //   carries the key and a screen never has to distinguish an absent member from a null one. An
  //   ABSENT member is still tolerated on the read and normalised to null rather than refused, because
  //   this validator's subject is a malformed body and a missing message line is not one.
  /*
   * WHY : ⚠️ Refactoring Rationale: the resolved PAIR is validated here, on the preview, where it was
   *       validated inside the copied block. The service resolves whichever key the request omitted -- a
   *       request keyed by card number answers with the account the cross-reference gave it, and one keyed
   *       by account answers with the card the row will be written under -- and it does so on EVERY turn,
   *       because `app/cbl/COTRN02C.cbl` L166 performs `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm as
   *       L473 does for the copy arm. Reading it off the copied block therefore only ever saw it on a copy
   *       turn, so an ordinary capture left the screen showing the key the operator typed rather than the
   *       key the row would carry.
   * WHY : ⚠️ Refactoring Rationale: the resolved card is validated against the MASKED pattern, where it
   *       was validated against sixteen digits on the ground that "it is the value the confirming turn
   *       submits". It is not, any longer: the confirming turn submits the sealed token below, so the
   *       screen never needs the digits and this guard now refuses them. Accepting them was what made an
   *       unmasked primary account number indistinguishable from correct behaviour at this boundary.
   */
  return {
    amount,
    written,
    returnMessage: returnMessage ?? null,
    resolvedAccountId: requireShaped(
      members.resolvedAccountId,
      ACCOUNT_ID,
      'resolvedAccountId',
      'previewed transaction',
    ),
    resolvedCardNumberMasked: requireShaped(
      members.resolvedCardNumberMasked,
      MASKED_CARD_NUMBER,
      'resolvedCardNumberMasked',
      'previewed transaction',
    ),
    confirmationToken: requireShaped(
      members.confirmationToken,
      CONFIRMATION_TOKEN,
      'confirmationToken',
      'previewed transaction',
    ),
    copied: optionalCopiedSource(members.copied),
  };
}

/**
 * Validates the body a posted bill payment answers with.
 * @param {unknown} body - The parsed 201 body.
 * @returns {BillPaymentResponse} The transaction the payment wrote, the account, the balance as it stood
 *   before the payment, the true payment flag, and the sentence when the service sent one.
 * @throws {RangeError} If a required member is absent or malformed, or the payment flag contradicts the
 *   status.
 */
function requireBillPaymentResponse(body: unknown): BillPaymentResponse {
  const members = requireBody(body, 'posted payment');
  const transactionId = requireShaped(
    members.transactionId,
    TRANSACTION_ID,
    'transactionId',
    'posted payment',
  );
  const accountId = requireShaped(members.accountId, ACCOUNT_ID, 'accountId', 'posted payment');
  const currentBalance = requireShaped(
    members.currentBalance,
    ACCOUNT_BALANCE,
    'currentBalance',
    'posted payment',
  );
  const paid = requireFixedFlag(members.paid, true, 'paid', 'posted payment');
  const returnMessage = optionalSentence(members.returnMessage, 'posted payment');
  // Refactoring Rationale: carried as null when the service sent none, for the reason recorded on
  //   {@link requireTransactionAddPreview}: the contract requires the key and publishes null as its
  //   empty value, so omitting it here would have produced a shape the published schema does not.
  return { transactionId, accountId, currentBalance, paid, returnMessage: returnMessage ?? null };
}

/**
 * Validates the body a bill payment answers with when nothing was paid.
 *
 * Assumptions: `payableBalance` is NULLABLE here and null-valued on one of the three turns this shape
 * serves, because that turn reaches no account read at all -- the declined confirmation clears the
 * screen at `app/cbl/COBIL00C.cbl` L180 without reaching the read at L343. The contract expresses that
 * as a required member with an explicit null branch rather than as an absent one, so the key is always
 * present and a screen reads null as "no balance applies". The account identifier is non-null on all
 * three, since even the declined turn echoes what it was sent.
 *
 * Refactoring Rationale: both nullable members were OPTIONAL here and are now carried, because
 * `BillPaymentPreview` in `./types` declares each `string | null` to match the contract's `required`
 * list. A body that omits either is still accepted and read as null rather than refused: this
 * validator exists to reject a malformed value, and an absent message line or balance is not one.
 * @param {unknown} body - The parsed 200 body.
 * @returns {BillPaymentPreview} The account, the balance a confirmed request would pay or null when the
 *   turn read none, the false payment flag, and the sentence or null.
 * @throws {RangeError} If a required member is absent or malformed, if the balance is present but
 *   malformed, or if the payment flag contradicts the status.
 */
function requireBillPaymentPreview(body: unknown): BillPaymentPreview {
  const members = requireBody(body, 'previewed payment');
  const accountId = requireShaped(members.accountId, ACCOUNT_ID, 'accountId', 'previewed payment');
  const paid = requireFixedFlag(members.paid, false, 'paid', 'previewed payment');
  const payableBalance =
    members.payableBalance === undefined || members.payableBalance === null
      ? null
      : requireShaped(
          members.payableBalance,
          ACCOUNT_BALANCE,
          'payableBalance',
          'previewed payment',
        );
  const returnMessage = optionalSentence(members.returnMessage, 'previewed payment');
  return { accountId, payableBalance, paid, returnMessage: returnMessage ?? null };
}

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
 * renders. This check therefore refuses only the combinations that cannot be meaningful, and passes
 * every individual value through untouched.
 *
 * Refactoring Rationale: there are now TWO such combinations, and the second is why this paragraph
 * changed. A direction supplied without a cursor used to be dropped here in silence while the
 * sentence above claimed every individual value passed through untouched — a claim the drop made
 * false. It is now refused by `keysetPagingMembers` in `./client`, which establishes the pair for all
 * seven paged clients, so both inadmissible pairs are refused the same way and by one implementation
 * rather than by seven copies.
 * @param {TransactionListQuery} query - The caller's criteria, any member of which may be absent.
 * @returns {Record<string, string>} The query members to send, empty when the caller supplied none.
 * @throws {RangeError} If a starting identifier and a cursor are supplied together, or if a direction
 *   is supplied without a cursor.
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
  // Refactoring Rationale: ⚠️ the pair is established by `keysetPagingMembers`, which refuses a
  //   direction supplied without a cursor rather than dropping it as this block did. The direction is
  //   still named EXPLICITLY whenever a cursor is present, for the reason recorded there: the value
  //   that reaches the request is what decides whether a browse advances or is refused, so a request
  //   replayed from a log states which side was asked for without knowing the contract's default.
  const paging = keysetPagingMembers(query.cursor, query.direction);
  if (paging !== undefined) {
    params.cursor = paging.cursor;
    params.direction = paging.direction;
  }
  return params;
}

/**
 * Lists transactions as one keyset-paged browse, optionally starting from a given identifier.
 * @param {TransactionListQuery} [query] - Optional criteria: an exact sixteen-digit starting
 *   identifier, OR a sealed cursor with the direction it is being replayed for. The two are mutually
 *   exclusive, and a direction is only accepted alongside a cursor. Omit the argument entirely for the
 *   opening page.
 * @returns {Promise<PageResponse<TransactionSummary>>} One bounded page: up to ten rows in ascending
 *   identifier order, plus the `firstKey` and `lastKey` cursors a backward or forward step is issued
 *   from and the `hasNext` flag the service sets by reading beyond the page rather than by counting.
 * @throws {RangeError} If a starting identifier and a cursor are supplied together, or if a direction
 *   is supplied without a cursor -- a position relative to nothing, which the contract refuses.
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
// WHY : Refactoring Rationale: the status selects which shape is expected and the selected shape is
//       then CHECKED, where an earlier revision asserted it with `as`. The two steps answer different
//       questions and both have to be answered: the status says which outcome the service reports, and
//       the check says whether the body it sent carries that outcome's members. Asserting instead of
//       checking left the second question unanswered, so a 201 body missing `transactionId` became a
//       typed capture whose identifier read `undefined` while the call reported success -- and the
//       rationale that stood here argued the assertion was sufficient BECAUSE the status had already
//       established the shape, which conflates the service's claim about the outcome with the body's
//       conformance to it.
// WHY : Assumptions: a body failing its status's check is reported as malformed and is never re-read as
//       the other outcome. Falling back would turn a written transaction into a reported preview, which
//       invites a second submission of the same money, and the discriminated union exists precisely so
//       that cannot happen. `requireTransactionCreated` and `requireTransactionAddPreview` above are
//       therefore total: each either returns its own shape or raises.

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
 * @throws {RangeError} If a member carries a value longer than the width
 *   `TransactionCreateRequest` publishes for it, in which case nothing is sent.
 * @throws {RangeError} If the body the service sent does not carry the members its status's shape
 *   requires -- a capture without its identifier, amount or sentence, or a preview without its amount
 *   -- which is reported as a malformed response and never re-read as the other outcome.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document with its per-field error array: HTTP 400 for a field the
 *   service refused, 401 when the token is absent or expired, 403 for a caller outside the permitted
 *   group, 404 for an unknown card or account, 409 for a concurrent change, 500 for a service-side
 *   failure and 503 when the ledger is unavailable.
 */
export async function addTransaction(
  request: TransactionCreateRequest,
): Promise<TransactionAddOutcome> {
  const response = await getApiClient().post<unknown>(
    requestPath(ADD_TRANSACTION),
    // Assumptions: the description and the two merchant names are what this guard is for -- 100, 50
    //   and 50 characters at `TRAN-DESC`, `TRAN-MERCHANT-NAME` and `TRAN-MERCHANT-CITY` in
    //   `app/cpy/CVTRA05Y.cpy` -- and the amount is bounded too, at the thirteen characters the wire
    //   format admits, so a mistyped figure is refused before it is offered for confirmation.
    requireWithinPublishedWidths('TransactionCreateRequest', request),
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'CREATED', created: requireTransactionCreated(response.data) };
  }
  return { outcome: 'PREVIEWED', preview: requireTransactionAddPreview(response.data) };
}

/**
 * Re-captures the most recently stored transaction as a new one, previewing or writing it.
 *
 * ⚠️ Refactoring Rationale: the request shape is the copy operation's OWN and is no longer the capture's.
 * The reasoning that stood here -- that "the baseline reaches this action from the same screen with the
 * same fields keyed" -- described the screen and not the request: `app/cbl/COTRN02C.cbl` L473 validates the
 * KEY fields and nothing else before reading the row, and L481 to L492 then fill the eleven data fields. A
 * body requiring those eleven made the action reachable only from a screen that was already filled in.
 *
 * ⚠️ Refactoring Rationale: this operation is reached ONCE per action. The withheld answer carries the
 * copied members, so a caller adopts them and CONFIRMS through {@link addTransaction}; reaching this
 * operation again to confirm would resolve "the most recently stored transaction" a second time, and a row
 * appended in between would be written in place of the one the operator previewed.
 * @param {CopyLastTransactionRequest} request - The key that selects the account or card, and the
 *   `confirmation` that decides whether the copied capture is written in this same turn.
 * @returns {Promise<TransactionAddOutcome>} `CREATED` carrying the identifier the service assigned to
 *   the copied record, or `PREVIEWED` carrying the normalised amount together with the ten other copied
 *   members and the identifier of the row they came from.
 * @throws {RangeError} If a member carries a value longer than the width
 *   `CopyLastRequest` publishes for it, in which case nothing is sent.
 * @throws {RangeError} If the body the service sent does not carry the members its status's shape
 *   requires, which is reported as a malformed response and never re-read as the other outcome.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, carrying the
 *   normalised `ApiError` problem document with its per-field error array: HTTP 400 for a field the
 *   service refused, 401 when the token is absent or expired, 403 for a caller outside the permitted
 *   group, 404 for an unknown card or account or for an empty table with no row to copy, 409 for a
 *   concurrent change, 500 for a service-side failure and 503 when the ledger is unavailable.
 */
export async function copyLastTransaction(
  request: CopyLastTransactionRequest,
): Promise<TransactionAddOutcome> {
  const response = await getApiClient().post<unknown>(
    requestPath(COPY_LAST_TRANSACTION),
    requireWithinPublishedWidths('CopyLastRequest', request),
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'CREATED', created: requireTransactionCreated(response.data) };
  }
  return { outcome: 'PREVIEWED', preview: requireTransactionAddPreview(response.data) };
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
 *   -- null on a declined answer, which reaches no account read at all.
 * @throws {RangeError} If a member carries a value longer than the width
 *   `BillPaymentRequest` publishes for it, in which case nothing is sent.
 * @throws {RangeError} If the body the service sent does not carry the members its status's shape
 *   requires -- a payment without its transaction identifier, account or balance, or either shape whose
 *   `paid` flag contradicts the status -- which is reported as a malformed response and never re-read as
 *   the other outcome.
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
  const response = await getApiClient().post<unknown>(
    requestPath(PAY_ACCOUNT_BALANCE_IN_FULL),
    requireWithinPublishedWidths('BillPaymentRequest', request),
  );

  if (response.status === HTTP_CREATED) {
    return { outcome: 'PAID', payment: requireBillPaymentResponse(response.data) };
  }

  // WHY : Refactoring Rationale: this branch is VALIDATED, symmetrically with the one above, where it
  //       used to pass the body through on a cast. Two arguments once excused that cast and neither
  //       survives. The first was structural -- every member the preview declares is one the payment
  //       shape also declares or leaves optional, so the union already satisfied this slot without an
  //       assertion. That was about what the COMPILER would accept, not about what arrived: the
  //       compiler's knowledge of the body came from the type argument on the request, which asserted
  //       the shape rather than observing it, so the branch that needed no cast was equally the branch
  //       that checked nothing, and a 200 body carrying no `accountId` reached a screen as a preview of
  //       an account it could not name. The second was that assignability held only while
  //       `payableBalance` was OPTIONAL -- an accident of the type system rather than a property of the
  //       wire. The contract now publishes that member as required and nullable, because the service
  //       writes it on every response and carries null on the declined turn, so a cast would assert
  //       precisely the shape the wire is no longer guaranteed to match. Narrowing observes it instead.
  return { outcome: 'PREVIEWED', preview: requireBillPaymentPreview(response.data) };
}

/**
 * Reads the balance a bill payment would pay, and CANNOT pay it.
 *
 * Purpose: ⚠️ this is the client half of a measured hazard. The inquiry and the money movement are one
 * operation on one target, distinguished only by whether a `confirmation` member is present -- so a
 * caller holding a request object with a stale confirming answer in it moves money while believing it
 * is reading a balance, and nothing about the call site says which of the two it is. This function
 * cannot: it composes the body itself from the account alone, so no answer a caller is holding can
 * reach the wire through it.
 *
 * Assumptions: the one operation stays one operation, and splitting it is REFUSED rather than
 * overlooked. The baseline decides between previewing and posting inside a single `EVALUATE` at
 * `app/cbl/COBIL00C.cbl` L173 to L191, within one transaction and one input set, and the published
 * contract states that splitting the preview off "would invent an endpoint the baseline does not have".
 * A separate read target would also have to read the balance from the context that owns accounts. So
 * what is separated here is the CALL SITE and not the operation.
 *
 * Assumptions: a `PAID` answer to this request is treated as a failure and not returned. It would mean
 * money moved on a request that carried no confirmation -- so reporting it as a preview would hide a
 * posted payment from the operator, and returning it as a payment would present one nobody confirmed.
 * Raising is the only outcome that leaves the discrepancy visible.
 * @param {string} accountId - The account whose payable balance is to be read.
 * @returns {Promise<BillPaymentPreview>} The account, the balance a confirmed request would pay, and
 *   the prompt the baseline shows with it.
 * @throws {RangeError} If the account identifier is longer than the width `BillPaymentRequest`
 *   publishes for it, in which case nothing is sent; or if the service reports a payment for a request
 *   that carried no confirmation.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, on the terms
 *   {@link payAccountBalanceInFull} documents.
 */
export async function inquireAccountPayableBalance(accountId: string): Promise<BillPaymentPreview> {
  const settled = await payAccountBalanceInFull({ accountId });
  if (settled.outcome === 'PAID') {
    throw new RangeError(
      'A bill-payment inquiry carrying no confirmation was answered with a posted payment; the' +
        ' balance was not read and the payment is not being reported as one.',
    );
  }
  return settled.preview;
}

/**
 * Pays an account's balance in full, and CANNOT be answered by a preview.
 *
 * Purpose: the mirror of {@link inquireAccountPayableBalance}, and it closes the other half of the same
 * hazard. A caller that means to pay but whose confirmation went missing -- a form reset, a member
 * dropped in transit through a screen's state -- gets a 200 preview today, which carries `paid: false`
 * and a prompt, and a call site that only awaited the promise would report the payment as made. This
 * function refuses the request locally when the answer does not confirm, and refuses to return when the
 * service says nothing was posted.
 *
 * Assumptions: the answer is checked with `isConfirmingAnswer`, which accepts exactly what the baseline
 * accepts -- both cases of the confirming letter and nothing else -- so this function and the service
 * cannot disagree about whether a given character was a confirmation.
 * @param {string} accountId - The account whose balance is to be paid in full.
 * @param {string} [confirmation] - The answer collected from the operator. Defaults to the confirming
 *   answer, because a caller reaching THIS function has already decided to pay; passing a declining or
 *   empty answer is refused rather than quietly previewing.
 * @returns {Promise<BillPaymentResponse>} The transaction the payment wrote, the account and the
 *   balance as it stood before the payment.
 * @throws {RangeError} If the answer does not confirm, in which case nothing is sent; if the account
 *   identifier exceeds its published width, in which case nothing is sent; or if the service answered
 *   with a preview, meaning nothing was posted and no payment may be reported.
 * @throws {Error} An `ApiRequestError` from `./client` for every transport failure, on the terms
 *   {@link payAccountBalanceInFull} documents.
 */
export async function payAccountBalanceConfirmed(
  accountId: string,
  confirmation: string = CONFIRMATION_ANSWERS.CONFIRM,
): Promise<BillPaymentResponse> {
  if (!isConfirmingAnswer(confirmation)) {
    throw new RangeError(
      'A bill payment was requested with an answer that does not confirm, so nothing was sent. Read' +
        ' the payable balance with inquireAccountPayableBalance instead.',
    );
  }
  const settled = await payAccountBalanceInFull({ accountId, confirmation });
  if (settled.outcome === 'PREVIEWED') {
    throw new RangeError(
      'A confirmed bill payment was answered with a preview, so nothing was posted and no payment is' +
        ' being reported.',
    );
  }
  return settled.payment;
}
