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

import { getApiClient } from './client';
import { requestPath } from './types';
import type { ContractOperation, PageDirection, PageResponse } from './types';

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
  VIEW_TRANSACTION,
  PAY_ACCOUNT_BALANCE_IN_FULL,
];

/** HTTP status the two write operations answer when the record was actually written. */
const HTTP_CREATED = 201;

/** Matches the masked rendering a transaction detail must carry: twelve asterisks, four digits. */
const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/**
 * One row of the transaction browse.
 *
 * Assumptions: FOUR members, matching `TransactionSummary` in the contract, and no card number among
 * them. The baseline list row shows the identifier, the originating timestamp, the description and the
 * amount; adding the card would disclose more per row than the screen it replaces disclosed, on a
 * response that returns a whole page at once.
 */
export interface TransactionSummary {
  readonly transactionId: string;
  readonly originTimestamp: string;
  readonly description: string;
  readonly amount: string;
}

/**
 * One transaction in full, as the detail screen renders it.
 *
 * Assumptions: `cardNumber` is the MASKED rendering and `processTimestamp` is nullable, both exactly
 * as the contract declares. A transaction that has been accepted but not yet posted carries no
 * processing timestamp, so null here is a real state and not a missing value.
 */
export interface TransactionDetail {
  readonly transactionId: string;
  readonly typeCode: string;
  readonly categoryCode: string;
  readonly source: string;
  readonly description: string;
  readonly amount: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly merchantCity: string;
  readonly merchantZip: string;
  readonly cardNumber: string;
  readonly originTimestamp: string;
  readonly processTimestamp: string | null;
  readonly returnMessage?: string | null | undefined;
}

/**
 * The fields the create operation accepts.
 *
 * Assumptions: `accountId` and `cardNumber` are both optional and exactly one identifies the card to
 * post against, which is why neither is required by the contract. `confirmation` is optional too: its
 * absence is the unconfirmed first pass that answers 200 with nothing written.
 */
export interface TransactionCreateRequest {
  readonly accountId?: string | undefined;
  readonly cardNumber?: string | undefined;
  readonly typeCode: string;
  readonly categoryCode: string;
  readonly source: string;
  readonly description: string;
  readonly amount: string;
  readonly originDate: string;
  readonly processDate: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly merchantCity: string;
  readonly merchantZip: string;
  readonly confirmation?: string | undefined;
}

/** The unconfirmed outcome of a create: the amount as the service read it, and nothing written. */
export interface TransactionAddPreview {
  readonly amount: string;
  readonly written: boolean;
  readonly returnMessage?: string | null | undefined;
}

/** The confirmed outcome of a create: the identifier the service assigned. */
export interface TransactionCreated {
  readonly transactionId: string;
  readonly amount: string;
  readonly returnMessage: string;
}

/**
 * Which of the two create outcomes occurred, discriminated so neither can be read as the other.
 *
 * Assumptions: the discriminant is derived from the HTTP status and not from the `written` member of
 * the preview shape. Both are present in the 200 body, and the status is the one the contract makes
 * normative -- 201 for a written record -- so deriving from it keeps this client agreeing with the
 * contract rather than with one property of one body.
 */
export type TransactionAddOutcome =
  | { readonly outcome: 'PREVIEWED'; readonly preview: TransactionAddPreview }
  | { readonly outcome: 'CREATED'; readonly created: TransactionCreated };

/** The fields the bill-payment operation accepts. */
export interface BillPaymentRequest {
  readonly accountId: string;
  readonly confirmation?: string | undefined;
}

/** The unconfirmed outcome of a bill payment: the balance that would be paid, and nothing paid. */
export interface BillPaymentPreview {
  readonly accountId: string;
  readonly payableBalance: string;
  readonly paid: boolean;
  readonly returnMessage?: string | null | undefined;
}

/** The confirmed outcome of a bill payment: the transaction written and the balance after it. */
export interface BillPaymentResponse {
  readonly transactionId: string;
  readonly accountId: string;
  readonly currentBalance: string;
  readonly paid: boolean;
  readonly returnMessage?: string | null | undefined;
}

/** Which of the two bill-payment outcomes occurred, discriminated for the same reason as a create. */
export type BillPaymentOutcome =
  | { readonly outcome: 'PREVIEWED'; readonly preview: BillPaymentPreview }
  | { readonly outcome: 'PAID'; readonly payment: BillPaymentResponse };

/**
 * Criteria a transaction browse may narrow by.
 *
 * Assumptions: the contract's only filter is an exact transaction identifier, so no free-text or
 * range criterion is offered here. The baseline browse screen accepts a starting identifier and
 * nothing else, and offering a criterion the service does not implement would fail at the edge with
 * 400 against a field the user was invited to fill.
 */
export interface TransactionListQuery {
  readonly transactionIdFilter?: string | undefined;
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

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
 * Pays an account's balance in full, either as an unconfirmed preview or as a confirmed write.
 * @param {BillPaymentRequest} request - The account, with `confirmation` set to a 'Y' answer to pay
 *   and omitted or set to 'N' to preview the payable balance.
 * @returns {Promise<BillPaymentOutcome>} `PAID` with the transaction written and the balance after
 *   it, otherwise `PREVIEWED` with the balance that would be paid.
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
  return { outcome: 'PREVIEWED', preview: response.data as BillPaymentPreview };
}
