/**
 * @file Typed client for the transaction bounded context, written against
 * `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the four operations that contract publishes: the keyset-paged transaction browse replacing
 * `app/cbl/COTRN00C.cbl`, the detail read replacing `app/cbl/COTRN01C.cbl`, the create replacing
 * `app/cbl/COTRN02C.cbl`, and the bill payment replacing `app/cbl/COBIL00C.cbl`. Every target is
 * derived from the operation manifest below rather than written as a literal, for the reason recorded
 * in `ui/src/api/types.ts`.
 *
 * Money on this boundary
 * ----------------------
 * Assumptions: every amount is a STRING on the way in and on the way out, and this module never
 * converts one to a number. The contract declares each as a pattern-constrained string because
 * transformation rule T3 forbids routing money through IEEE-754 binary floating point, and a client
 * that parsed one to render it would reintroduce exactly the loss the string exists to prevent.
 * Arithmetic on these values belongs to the service, which holds them as exact fixed-point.
 *
 * The two operations with two success statuses
 * -------------------------------------------
 * Refactoring Rationale: `addTransaction` and `payAccountBalanceInFull` each declare a 200 and a 201,
 * and this module returns a DISCRIMINATED UNION rather than one optional-heavy shape. The distinction
 * is the baseline's confirmation gate: `app/cbl/COTRN02C.cbl` and `app/cbl/COBIL00C.cbl` both redisplay
 * with the record unwritten when the user has not confirmed, and both write and report an identifier
 * when they have. Collapsing the two into one interface with every member optional would let a caller
 * read a transaction identifier that is absent precisely when nothing was written, which is the one
 * mistake this boundary can make that money depends on.
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

/** HTTP status the two write operations answer when the record was actually written. */
const HTTP_CREATED = 201;

/** Matches the masked rendering a transaction detail must carry: twelve asterisks, four digits. */
const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/** The unconfirmed outcome of a create: the amount as the service read it, and nothing written. */

/** The confirmed outcome of a create: the identifier the service assigned. */

/** The fields the bill-payment operation accepts. */

/** The unconfirmed outcome of a bill payment: the balance that would be paid, and nothing paid. */

/**
 * The confirmed outcome of a bill payment: the transaction written and the balance that was paid.
 *
 * Refactoring Rationale: this said "the balance after it", and that is the opposite of what the member
 * carries. `transaction-api.yaml` documents `currentBalance` as the balance as it stood BEFORE the
 * payment, fixed by statement order in the reference: `app/cbl/COBIL00C.cbl` L193 moves the balance to
 * the display field, L224 reuses the same untouched value as the transaction amount, L233 writes and only
 * L234 subtracts. The figure after the payment is invariably zero, because this baseline pays the balance
 * in full, so a caller rendering this value under the old description would have shown the operator a
 * paid amount labelled as a remaining balance.
 */

/** Which of the two bill-payment outcomes occurred, discriminated for the same reason as a create. */

/**
 * Lists transactions, optionally starting from a given identifier.
 * @param {TransactionListQuery} [query] - Optional criteria: an exact transaction identifier, plus a
 *   sealed cursor and the direction that cursor was issued for. Omit it for the first page.
 * @returns {Promise<PageResponse<TransactionSummary>>} One bounded page of browse rows.
 * @throws {Error} If the request fails.
 */
export async function listTransactions(
  query: TransactionListQuery = {},
): Promise<PageResponse<TransactionSummary>> {
  const params: Record<string, string> = {};
  if (query.transactionIdFilter !== undefined) {
    params.transactionIdFilter = query.transactionIdFilter;
  }
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    // Assumptions: the direction accompanies the cursor and is omitted without one, because the
    //   contract declares it meaningful only alongside a cursor. A direction alone would describe a
    //   position relative to nothing.
    params.direction = query.direction ?? 'next';
  }

  const response = await getApiClient().get<PageResponse<TransactionSummary>>(
    requestPath(LIST_TRANSACTIONS),
    { params: Object.keys(params).length === 0 ? undefined : params },
  );
  return response.data;
}

/**
 * Retrieves one transaction in full.
 * @param {string} transactionId - The transaction's sixteen-character identifier.
 * @returns {Promise<TransactionDetail>} The transaction, with its card number masked.
 * @throws {RangeError} If the response carries an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 404 when no such transaction exists.
 */
export async function viewTransaction(transactionId: string): Promise<TransactionDetail> {
  const response = await getApiClient().get<TransactionDetail>(
    requestPath(VIEW_TRANSACTION, { transactionId }),
  );

  /*
   * WHY : Assumptions: the masked rendering is checked at the boundary rather than trusted, because
   *       the failure it catches is a server-side masking fault and the alternative to catching it
   *       here is rendering a full account number into a table, a console line and a bug report. The
   *       rejected value is described and never reproduced in the message, for the same reason.
   */
  if (!MASKED_CARD_NUMBER.test(response.data.cardNumber)) {
    throw new RangeError(
      'Transaction detail must carry a masked card number; an unmasked value was returned.',
    );
  }
  return response.data;
}

/**
 * Submits a transaction, either as an unconfirmed preview or as a confirmed write.
 *
 * Assumptions: the caller decides which by supplying `confirmation`, and this function reports which
 * occurred rather than inferring intent. That mirrors the baseline, where the same screen submission
 * either redisplays for confirmation or writes, decided by one field.
 * @param {TransactionCreateRequest} request - The transaction fields, with `confirmation` set to a
 *   'Y' answer to write and omitted or set to 'N' to preview.
 * @returns {Promise<TransactionAddOutcome>} `CREATED` with the assigned identifier when the service
 *   wrote the record, otherwise `PREVIEWED` with the amount it read.
 * @throws {Error} If the request fails, including HTTP 400 for a field the service rejected, 404 for
 *   an unknown card or account and 409 for a concurrent change.
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
 * Assumptions: the outcome is discriminated from the HTTP status exactly as `addTransaction`
 * discriminates it, because L495 re-enters PROCESS-ENTER-KEY and the copied capture is therefore
 * written or prompted by the identical rule.
 * @param {TransactionCreateRequest} request - The submission whose key fields select the account or
 *   card and whose `confirmation` decides whether the copied capture is written.
 * @returns {Promise<TransactionAddOutcome>} `CREATED` with the assigned identifier when the service
 *   wrote the copied record, otherwise `PREVIEWED` with the amount it read.
 * @throws {Error} If the request fails, including HTTP 400 for a field the service rejected, 404 for
 *   an unknown card or account or for an empty table with no row to copy, and 409 for a concurrent
 *   change.
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
 * Pays an account's balance in full, either as an unconfirmed preview or as a confirmed write.
 * @param {BillPaymentRequest} request - The account, with `confirmation` set to a 'Y' answer to pay
 *   and omitted or set to 'N' to preview the payable balance.
 * @returns {Promise<BillPaymentOutcome>} `PAID` with the transaction written and the balance that was
 *   paid, otherwise `PREVIEWED` with the balance that would be paid.
 * @throws {Error} If the request fails, including HTTP 404 for an unknown account and 409 for a
 *   concurrent change.
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
  // WHY : Assumptions: the discriminator is the STATUS and not a member of the body, because that is
  //       what the published contract discriminates on -- 201 with a required `Location` header for the
  //       turn that moved money, 200 for the three that did not. Reading `paid` from the body instead
  //       would work today and would silently start reporting a payment if a future body ever carried
  //       that member set on a non-paying turn.
  return { outcome: 'PREVIEWED', preview: response.data };
}
