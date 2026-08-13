/**
 * @file Unit tests for the ledger client's write-response validation in `ui/src/api/transactions.ts`.
 *
 * Purpose
 * -------
 * Fix the property the three write operations depend on: the HTTP status selects which outcome shape is
 * expected, and the body is then CHECKED against that shape rather than asserted to be it. A revision of
 * this client cast each body to its outcome type, so a 201 that carried no `transactionId` became a
 * typed capture whose identifier read `undefined` while the call reported success -- and a 200 payment
 * body was passed through with nothing checked at all. Both readings are money-affecting, so every case
 * below either pins an accepted body member for member or pins a malformed body being refused rather
 * than re-read as the opposite outcome.
 *
 * Assumptions: the axios instance is stubbed, so these cases measure this module's validation of a
 * response and nothing about a running service. A malformed body is the subject of half of them, and a
 * correct service never sends one, so a stub is the only way to present it.
 *
 * Assumptions: this file carries TWO suites, and the split is deliberate rather than historical.
 * `ledger write outcome contract` fixes which outcome the client reads back from a money-affecting
 * write, and `transaction client behaviour` fixes the target, method and body of every request it
 * composes -- including the paging members and the amount's string form. They read opposite ends of
 * the same call, which is why each keeps its own double; the comment above the second suite records
 * the reasoning.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts records `globals: false` as a contract: ambient test globals are declared per
// PROJECT, so admitting them here would make `expect` and `vi` visible to production screens too.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient } from './client';
import {
  addTransaction,
  copyLastTransaction,
  listTransactions,
  payAccountBalanceInFull,
  viewTransaction,
} from './transactions';
import type { TransactionCreateRequest } from './types';
import {
  answerWith,
  dispatchedRequests,
  installApiHarness,
  onlyRequest,
  pageOf,
  removeApiHarness,
} from '../test/apiHarness';

const API_BASE_URL = 'https://api.carddemo.example';

const CORRELATION_HEADER = 'X-Correlation-Id';

const HTTP_OK = 200;

const HTTP_CREATED = 201;

const TRANSACTION_ID = '0000000000683580';

const ACCOUNT_ID = '00000000011';

/** An amount in the exact fixed-point rendering the contract publishes: two decimals, always. */
const AMOUNT = '50.47';

/** A balance one integer digit wider than an amount admits, which the balance pattern permits. */
const BALANCE = '1234.56';

const ADDED_SENTENCE = 'Transaction added successfully.  Your Tran ID is 0000000000683580.';

let nextStatus: number = HTTP_OK;

let nextBody: unknown = {};

/**
 * Records nothing and answers with the queued status and body.
 *
 * Assumptions: the status is a settable fixture rather than a constant, because each operation derives
 * its outcome FROM the status -- 201 wrote, 200 did not -- so a harness able to answer only one of the
 * two could not tell the two readings apart at all.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A response carrying the queued status and body.
 */
async function queuedAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.resolve({
    data: nextBody,
    status: nextStatus,
    statusText: nextStatus === HTTP_CREATED ? 'Created' : 'OK',
    headers: {},
    config,
  } as AxiosResponse);
}

/** Supplies the build-time configuration the client validates before it is constructed. */
function stubBuildConfiguration(): void {
  nextStatus = HTTP_OK;
  nextBody = {};
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', CORRELATION_HEADER);
  getApiClient().defaults.adapter = queuedAdapter;
}

/** Restores the environment so no later file inherits this file's configuration. */
function restoreBuildConfiguration(): void {
  vi.unstubAllEnvs();
}

/**
 * Builds a submission whose members satisfy the request shape, since none of them is under test here.
 * @returns {TransactionCreateRequest} One complete submission carrying the confirmation answer.
 */
function submission(): TransactionCreateRequest {
  return {
    accountId: ACCOUNT_ID,
    typeCode: '01',
    categoryCode: '0001',
    source: 'POS TERM',
    description: 'PARITY CAPTURE',
    amount: AMOUNT,
    originDate: '2022-07-18',
    processDate: '2022-07-18',
    merchantId: '000000000001',
    merchantName: 'PARITY MERCHANT',
    merchantCity: 'PARITY CITY',
    merchantZip: '00001',
    confirmation: 'Y',
  };
}

/** Asserts a written capture is read from the created status with all three members intact. */
async function readsACaptureFromTheCreatedStatus(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: TRANSACTION_ID, amount: AMOUNT, returnMessage: ADDED_SENTENCE };
  const outcome = await addTransaction(submission());
  expect(outcome.outcome).toBe('CREATED');
  if (outcome.outcome !== 'CREATED') {
    throw new Error('the created status must be read as a capture');
  }
  expect(outcome.created.transactionId).toBe(TRANSACTION_ID);
  expect(outcome.created.returnMessage).toBe(ADDED_SENTENCE);
  // Assumptions: the amount is asserted to be a STRING as well as to be equal, because equality alone
  //   would pass a value the transport had already turned into a double. That is the failure
  //   transformation rule T3 exists to prevent, and it is invisible at these magnitudes.
  expect(typeof outcome.created.amount).toBe('string');
  expect(outcome.created.amount).toBe(AMOUNT);
}

/**
 * Asserts a capture body missing its identifier is refused rather than reported as a preview.
 *
 * Assumptions: what is asserted is BOTH halves -- that the call raises, and that it does not resolve.
 * Falling back to the preview reading is the specific failure the discriminated union exists to prevent,
 * because a written transaction reported as unwritten invites a second submission of the same money.
 */
async function refusesACaptureWithoutItsIdentifier(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { amount: AMOUNT, returnMessage: ADDED_SENTENCE };
  await expect(addTransaction(submission())).rejects.toThrow(RangeError);
}

/** Asserts a capture body carrying its amount as a JSON number is refused. */
async function refusesACaptureWithANumericAmount(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: TRANSACTION_ID, amount: 50.47, returnMessage: ADDED_SENTENCE };
  await expect(addTransaction(submission())).rejects.toThrow(RangeError);
}

/** Asserts an amount rendered with one decimal place is refused, since the scale is part of the value. */
async function refusesACaptureWithAnUnscaledAmount(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: TRANSACTION_ID, amount: '50.4', returnMessage: ADDED_SENTENCE };
  await expect(addTransaction(submission())).rejects.toThrow(RangeError);
}

/** Asserts a capture body without the confirmation sentence the contract requires is refused. */
async function refusesACaptureWithoutItsSentence(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: TRANSACTION_ID, amount: AMOUNT };
  await expect(addTransaction(submission())).rejects.toThrow(RangeError);
}

/** Asserts a preview is read from the ok status, with its absent sentence left absent. */
async function readsAPreviewFromTheOkStatus(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = { amount: AMOUNT, written: false };
  const outcome = await addTransaction({ ...submission(), confirmation: 'N' });
  expect(outcome.outcome).toBe('PREVIEWED');
  if (outcome.outcome !== 'PREVIEWED') {
    throw new Error('the ok status must be read as a preview');
  }
  expect(outcome.preview.amount).toBe(AMOUNT);
  expect(outcome.preview.written).toBe(false);
  // Refactoring Rationale: the member is asserted PRESENT AND NULL, where this case asserted it
  //   absent. `transaction-api.yaml` lists returnMessage in TransactionAddPreview's `required` set
  //   against a component whose second branch is `type: null`, and always-inclusion is pinned for
  //   every service, so the wire carries the key on every body and null is its empty value. A body
  //   that omits it -- as this arrangement does -- is still accepted and read as null rather than
  //   refused, because a missing message line is not a malformed one.
  expect('returnMessage' in outcome.preview).toBe(true);
  expect(outcome.preview.returnMessage).toBeNull();
}

/**
 * Asserts a preview whose capture flag contradicts the ok status is refused.
 *
 * Assumptions: the contract fixes `written` to false on this shape, so a body setting it true makes the
 * status and the flag disagree about whether money moved. Neither source is preferred: the disagreement
 * is reported, because resolving it toward the flag reports a write that did not happen and resolving it
 * silently toward the status conceals a service that has begun answering incorrectly.
 */
async function refusesAPreviewWhoseFlagContradictsTheStatus(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = { amount: AMOUNT, written: true };
  await expect(addTransaction(submission())).rejects.toThrow(RangeError);
}

/** Asserts the copy-last operation validates through the same two shapes as the plain add. */
async function copyLastSharesTheSameValidation(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: TRANSACTION_ID, amount: AMOUNT, returnMessage: ADDED_SENTENCE };
  const captured = await copyLastTransaction(submission());
  expect(captured.outcome).toBe('CREATED');

  nextBody = { amount: AMOUNT, returnMessage: ADDED_SENTENCE };
  await expect(copyLastTransaction(submission())).rejects.toThrow(RangeError);
}

/** Asserts a posted payment is read from the created status with all four required members. */
async function readsAPostedPaymentFromTheCreatedStatus(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = {
    transactionId: TRANSACTION_ID,
    accountId: ACCOUNT_ID,
    currentBalance: BALANCE,
    paid: true,
    returnMessage: null,
  };
  const outcome = await payAccountBalanceInFull({ accountId: ACCOUNT_ID, confirmation: 'Y' });
  expect(outcome.outcome).toBe('PAID');
  if (outcome.outcome !== 'PAID') {
    throw new Error('the created status must be read as a posted payment');
  }
  expect(outcome.payment.transactionId).toBe(TRANSACTION_ID);
  expect(outcome.payment.accountId).toBe(ACCOUNT_ID);
  expect(typeof outcome.payment.currentBalance).toBe('string');
  expect(outcome.payment.currentBalance).toBe(BALANCE);
  expect(outcome.payment.paid).toBe(true);
  // Assumptions: an explicitly null sentence is carried through as null rather than dropped or replaced
  //   with an empty string, because the service sets default-property-inclusion to always, so a present
  //   null is a value the body stated and a blank sentence would render as a deliberate silence.
  expect(outcome.payment.returnMessage).toBeNull();
}

/** Asserts a posted payment missing the balance it paid is refused. */
async function refusesAPostedPaymentWithoutItsBalance(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: TRANSACTION_ID, accountId: ACCOUNT_ID, paid: true };
  await expect(
    payAccountBalanceInFull({ accountId: ACCOUNT_ID, confirmation: 'Y' }),
  ).rejects.toThrow(RangeError);
}

/**
 * Asserts the declined turn's preview is accepted without a payable balance.
 *
 * Assumptions: this is why `payableBalance` is optional and must stay so. The declined confirmation
 * clears the screen at `app/cbl/COBIL00C.cbl` L180 without reaching the account read at L343, so a body
 * without that member is correct rather than malformed, and requiring it would refuse a legitimate turn.
 */
async function readsADeclinedPreviewWithoutAPayableBalance(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = { accountId: ACCOUNT_ID, paid: false };
  const outcome = await payAccountBalanceInFull({ accountId: ACCOUNT_ID, confirmation: 'N' });
  expect(outcome.outcome).toBe('PREVIEWED');
  if (outcome.outcome !== 'PREVIEWED') {
    throw new Error('the ok status must be read as a preview');
  }
  expect(outcome.preview.accountId).toBe(ACCOUNT_ID);
  expect(outcome.preview.paid).toBe(false);
  // Refactoring Rationale: present and null rather than absent, for the reason recorded on
  //   {@link readsAPreviewFromTheOkStatus}: `BillPaymentPreview` publishes payableBalance in its
  //   `required` set with an explicit null branch, which is how the declined turn -- the one that
  //   reaches no account read at all -- states that no balance applies.
  expect('payableBalance' in outcome.preview).toBe(true);
  expect(outcome.preview.payableBalance).toBeNull();
}

/** Asserts the confirmable turn's preview carries the balance and the prompt through unchanged. */
async function readsAConfirmablePreviewWithItsPayableBalance(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = {
    accountId: ACCOUNT_ID,
    payableBalance: BALANCE,
    paid: false,
    returnMessage: 'Confirm to make a bill payment...',
  };
  const outcome = await payAccountBalanceInFull({ accountId: ACCOUNT_ID });
  if (outcome.outcome !== 'PREVIEWED') {
    throw new Error('the ok status must be read as a preview');
  }
  expect(outcome.preview.payableBalance).toBe(BALANCE);
  expect(outcome.preview.returnMessage).toBe('Confirm to make a bill payment...');
}

/**
 * Asserts a previewed payment naming no account is refused.
 *
 * Refactoring Rationale: this is the case the previous revision could not catch at all. That branch
 * passed the body straight through, justified on the grounds that the payment shape is structurally
 * assignable to the preview shape so no assertion was needed -- which was true of what the compiler
 * would accept and said nothing about what arrived, since the compiler's only knowledge of the body came
 * from the type argument on the request. A screen therefore received a preview of an account it could
 * not name.
 */
async function refusesAPreviewedPaymentWithoutItsAccount(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = { paid: false, payableBalance: BALANCE };
  await expect(payAccountBalanceInFull({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);
}

/**
 * Asserts no refusal message reproduces an identifier or a balance it rejected.
 *
 * Assumptions: an account identifier and a transaction identifier are named alongside the primary
 * account number in the migration's sensitive-data logging contract, so a message quoting a rejected
 * value would place it in whatever renders or logs the failure.
 */
async function neverReproducesARejectedValue(): Promise<void> {
  nextStatus = HTTP_CREATED;
  nextBody = { transactionId: '12345', accountId: ACCOUNT_ID, currentBalance: BALANCE, paid: true };
  let message = '';
  try {
    await payAccountBalanceInFull({ accountId: ACCOUNT_ID, confirmation: 'Y' });
  } catch (error) {
    message = (error as Error).message;
  }
  expect(message).toContain('transactionId');
  expect(message).not.toContain('12345');
  expect(message).not.toContain(ACCOUNT_ID);
  expect(message).not.toContain(BALANCE);
}

/** Groups the assertions that fix the ledger client's write-response validation. */
function ledgerWriteOutcomeContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it('reads a capture from the created status', readsACaptureFromTheCreatedStatus);
  it('refuses a capture without its identifier', refusesACaptureWithoutItsIdentifier);
  it('refuses a capture with a numeric amount', refusesACaptureWithANumericAmount);
  it('refuses a capture with an unscaled amount', refusesACaptureWithAnUnscaledAmount);
  it('refuses a capture without its sentence', refusesACaptureWithoutItsSentence);
  it('reads a preview from the ok status', readsAPreviewFromTheOkStatus);
  it(
    'refuses a preview whose flag contradicts the status',
    refusesAPreviewWhoseFlagContradictsTheStatus,
  );
  it('validates the copy-last outcome through the same shapes', copyLastSharesTheSameValidation);
  it('reads a posted payment from the created status', readsAPostedPaymentFromTheCreatedStatus);
  it('refuses a posted payment without its balance', refusesAPostedPaymentWithoutItsBalance);
  it(
    'reads a declined preview without a payable balance',
    readsADeclinedPreviewWithoutAPayableBalance,
  );
  it(
    'reads a confirmable preview with its payable balance',
    readsAConfirmablePreviewWithItsPayableBalance,
  );
  it('refuses a previewed payment without its account', refusesAPreviewedPaymentWithoutItsAccount);
  it('never reproduces a rejected value', neverReproducesARejectedValue);
}

describe('ledger write outcome contract', ledgerWriteOutcomeContract);

// ------------------------------------------------------------------------------------------------
// Assumptions: what follows is a second suite over the same module, kept beside the first rather
// than folded into it. The two fix different properties: the block above fixes the OUTCOME the
// client reads back from a ledger write -- which status it trusts and which member it refuses to
// invent -- while this one fixes the SHAPE of every request it composes, including the paging
// members and the amount's string form. Alternatives Considered: re-expressing one suite in the
// other's arrangement so the file held a single harness. Rejected because the two read opposite
// ends of the same call and need different doubles. Assumptions: the second suite's own
// sixteen-character identifier specimen and its two status constants are dropped in favour of the
// declarations above, which carry the same shape and the same two numbers -- a second specimen for
// the same field is a value that can drift out of step with nothing detecting it.
// ------------------------------------------------------------------------------------------------
/** The masked rendering every transaction reading carries in place of a card number. */
const MASKED_CARD_NUMBER = '************7065';

/** One transaction summary row, carrying its amount as a string. */
const TRANSACTION_ROW = {
  transactionId: TRANSACTION_ID,
  typeCode: '01',
  categoryCode: '0001',
  source: 'POS TERM',
  amount: '100.00',
  cardNumber: MASKED_CARD_NUMBER,
} as const;

/** One transaction detail body, only the members these assertions read being populated. */
const TRANSACTION_DETAIL = {
  transactionId: TRANSACTION_ID,
  cardNumber: MASKED_CARD_NUMBER,
  amount: '100.00',
  processTimestamp: null,
  returnMessage: null,
} as const;

/** A create request in the shape the contract's add schema declares. */
const CREATE_REQUEST = {
  accountId: '00000000011',
  typeCode: '01',
  categoryCode: '0001',
  source: 'POS TERM',
  description: 'RETAIL PURCHASE',
  amount: '100.00',
  originDate: '2022-07-18',
  processDate: '2022-07-18',
  merchantId: '000000001',
  merchantName: 'ACME HARDWARE',
  merchantCity: 'NEW YORK',
  merchantZip: '10001',
  confirmation: 'Y',
} as const;

/** Asserts the browse addresses the collection and sends no paging member on an opening read. */
async function browsesTheOpeningPageWithNoPagingMember(): Promise<void> {
  answerWith(pageOf([TRANSACTION_ROW], true));

  await listTransactions();

  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/transactions');
  expect(request.params).toEqual({});
}

/** Asserts a starting identifier travels as the published filter parameter. */
async function sendsAStartingIdentifierAsItsOwnParameter(): Promise<void> {
  answerWith(pageOf([TRANSACTION_ROW]));

  await listTransactions({ transactionIdFilter: TRANSACTION_ID });

  expect(onlyRequest().params).toEqual({ transactionIdFilter: TRANSACTION_ID });
}

/**
 * Asserts a starting identifier and a cursor together are refused locally.
 *
 * Assumptions: this refusal is the module's own and predates the paging guard; it is asserted here because
 * the two guards now sit in the same function and a rewrite of one could remove the other.
 */
async function refusesAnIdentifierAndACursorTogether(): Promise<void> {
  await expect(
    listTransactions({ transactionIdFilter: TRANSACTION_ID, cursor: 'opaque-token' }),
  ).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts a direction with no cursor is refused locally and never dispatched. */
async function refusesADirectionWithNoCursor(): Promise<void> {
  await expect(listTransactions({ direction: 'previous' })).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts a cursor is sent with the direction asked for. */
async function sendsTheCursorWithItsDirection(): Promise<void> {
  answerWith(pageOf([TRANSACTION_ROW]));

  await listTransactions({ cursor: 'opaque-token', direction: 'previous' });

  expect(onlyRequest().params).toEqual({ cursor: 'opaque-token', direction: 'previous' });
}

/** Asserts the member read addresses the transaction by identifier as a path segment. */
async function readsOneTransactionByItsIdentifier(): Promise<void> {
  answerWith(TRANSACTION_DETAIL);

  await viewTransaction(TRANSACTION_ID);

  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe(`/transactions/${TRANSACTION_ID}`);
}

/**
 * Asserts a detail rendering a whole card number is refused.
 *
 * Assumptions: the refusal is asserted rather than the masked rendering being asserted present, because a
 * service fault here reaches a table, a console line and a bug report -- and the refused value must not be
 * reproduced in the message either, which the client's own wording observes.
 */
async function refusesAnUnmaskedDetail(): Promise<void> {
  answerWith({ ...TRANSACTION_DETAIL, cardNumber: '4859452612877065' });

  await expect(viewTransaction(TRANSACTION_ID)).rejects.toThrow(RangeError);
}

/**
 * Asserts an add posts its body to the collection target.
 *
 * Refactoring Rationale: the arranged body carries all three members `TransactionAddPreview` publishes
 * as required, where this case arranged the message line alone. The client validates a preview body
 * member for member -- it is the reading that decides whether a capture happened -- so a partial body is
 * refused before the request under test can be inspected.
 */
async function addsATransactionAtTheCollectionTarget(): Promise<void> {
  answerWith({ amount: '100.00', written: false, returnMessage: 'CONFIRM?' }, HTTP_OK);

  await addTransaction(CREATE_REQUEST);

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/transactions');
  expect(request.body).toEqual(CREATE_REQUEST);
}

/**
 * Asserts the add outcome is read from the status and not from the body.
 *
 * Assumptions: both statuses are exercised in one case, because the property under test is the
 * DISTINCTION -- a client that always reported one of the two would satisfy a single-status assertion.
 */
async function readsTheAddOutcomeFromTheStatus(): Promise<void> {
  answerWith({ amount: '100.00', written: false, returnMessage: 'CONFIRM?' }, HTTP_OK);
  const previewed = await addTransaction(CREATE_REQUEST);
  expect(previewed.outcome).toBe('PREVIEWED');

  installApiHarness();
  answerWith(
    { transactionId: TRANSACTION_ID, amount: '100.00', returnMessage: ADDED_SENTENCE },
    HTTP_CREATED,
  );
  const created = await addTransaction(CREATE_REQUEST);
  expect(created.outcome).toBe('CREATED');
  if (created.outcome !== 'CREATED') {
    throw new Error('a created status must be read as a written row');
  }
  expect(created.created.transactionId).toBe(TRANSACTION_ID);
}

/** Asserts the copy-last action has its own target and reads the same two outcomes. */
async function copiesTheLastTransactionAtItsOwnTarget(): Promise<void> {
  answerWith(
    { transactionId: TRANSACTION_ID, amount: '100.00', returnMessage: ADDED_SENTENCE },
    HTTP_CREATED,
  );

  const outcome = await copyLastTransaction(CREATE_REQUEST);

  expect(onlyRequest().url).toBe('/transactions/copy-last');
  expect(outcome.outcome).toBe('CREATED');
}

/** Asserts bill payment posts to its own target and reads its two outcomes from the status. */
async function paysTheBalanceAtTheBillPaymentTarget(): Promise<void> {
  answerWith(
    { accountId: '00000000011', payableBalance: '1234.56', paid: false, returnMessage: 'CONFIRM?' },
    HTTP_OK,
  );
  const previewed = await payAccountBalanceInFull({ accountId: '00000000011', confirmation: 'Y' });
  expect(onlyRequest().url).toBe('/billpay');
  expect(previewed.outcome).toBe('PREVIEWED');

  installApiHarness();
  answerWith(
    {
      transactionId: TRANSACTION_ID,
      accountId: '00000000011',
      currentBalance: '1234.56',
      paid: true,
      returnMessage: 'Payment successful.  Your Transaction ID is 0000000000683580.',
    },
    HTTP_CREATED,
  );
  const paid = await payAccountBalanceInFull({ accountId: '00000000011', confirmation: 'Y' });
  expect(paid.outcome).toBe('PAID');
}

/**
 * Asserts every amount crossing this boundary is a string in both directions.
 *
 * Assumptions: the REQUEST is asserted as well as the response, because a screen holding a number would
 * serialise one and the service would receive a value it has to parse as a double before it can refuse it.
 */
async function keepsEveryAmountAString(): Promise<void> {
  answerWith(pageOf([TRANSACTION_ROW]));
  const page = await listTransactions();
  expect(typeof page.items[0]?.amount).toBe('string');

  installApiHarness();
  answerWith(
    { transactionId: TRANSACTION_ID, amount: '100.00', returnMessage: ADDED_SENTENCE },
    HTTP_CREATED,
  );
  await addTransaction(CREATE_REQUEST);
  const body = onlyRequest().body as Record<string, unknown>;
  expect(typeof body.amount).toBe('string');
}

/**
 * Registers every transaction client case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function transactionClientBehaviour(): void {
  beforeEach(installApiHarness);
  afterEach(removeApiHarness);
  it('browses the opening page with no paging member', browsesTheOpeningPageWithNoPagingMember);
  it('sends a starting identifier as its own parameter', sendsAStartingIdentifierAsItsOwnParameter);
  it('refuses an identifier and a cursor together', refusesAnIdentifierAndACursorTogether);
  it('refuses a direction with no cursor', refusesADirectionWithNoCursor);
  it('sends the cursor with its direction', sendsTheCursorWithItsDirection);
  it('reads one transaction by its identifier', readsOneTransactionByItsIdentifier);
  it('refuses an unmasked detail', refusesAnUnmaskedDetail);
  it('adds a transaction at the collection target', addsATransactionAtTheCollectionTarget);
  it('reads the add outcome from the status', readsTheAddOutcomeFromTheStatus);
  it('copies the last transaction at its own target', copiesTheLastTransactionAtItsOwnTarget);
  it('pays the balance at the bill-payment target', paysTheBalanceAtTheBillPaymentTarget);
  it('keeps every amount a string', keepsEveryAmountAString);
}

describe('transaction client behaviour', transactionClientBehaviour);
