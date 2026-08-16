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
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
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
import type { CopiedTransactionData, TransactionCreateRequest } from './types';
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

/**
 * How many data fields the copy block moves onto the screen.
 *
 * Assumptions: eleven, counted at `app/cbl/COTRN02C.cbl` L480 to L493. It is asserted as a count rather
 * than left implicit in the fixture, so a twelfth member appearing in the answer -- or one of the eleven
 * quietly dropping out of the shape -- fails here instead of reaching a screen with one stale control.
 */
const COPIED_FIELD_COUNT = 11;

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

/** The card the cross-reference resolves, sixteen digits, used only where a REQUEST carries a key. */
const RESOLVED_CARD = '4111111111111111';

/**
 * The masked rendering a preview publishes for that same card: twelve asterisks then its last four digits.
 *
 * ⚠️ Refactoring Rationale: the preview fixture used to carry `RESOLVED_CARD` itself, because the
 * response member was the full number. A review found that a non-administrative preview therefore disclosed
 * a whole primary account number to the browser, so the member now publishes this rendering and the
 * unmasked spelling has become a REFUSAL case rather than the happy path.
 */
const RESOLVED_CARD_MASKED = '************1111';

/**
 * A binding token shaped as the sealed-cursor grammar the services emit and this client shape-checks.
 *
 * Assumptions: the value is opaque and nothing here interprets it; only its SHAPE is asserted, because
 * that is all the client verifies -- the service alone can open it. The three segments are the version
 * marker, a sixteen-character key identifier and the sealed payload.
 */
/*
 * WHY : ⚠️ Assumptions: the fabricated value is deliberately LOW-ENTROPY, and the shape is the only
 *       property any assertion reads. It was
 *       'v2.<sixteen hex characters>.<a base64 payload>', which is shape-valid and was flagged by the
 *       repository's secret scan as a generic API key -- the identifier beside it contains the word
 *       "token", so the rule falls back to entropy, and a base64-looking payload clears its threshold.
 *       A fixture that trips the scanner is worse than a duller one: the gate runs under `set -euo
 *       pipefail` in `.github/workflows/infra-ci.yml` and fails on any finding, so five copies of this
 *       constant would have failed every run, and the usual answer to a gate that always fails is to
 *       widen its allowlist -- which would then admit any value assigned to a name containing "token".
 *       Alternatives Considered: allowlisting this exact literal, as that workflow already does for two
 *       fabricated signing keys. Rejected because those two have to be high-entropy to stand in for
 *       real keys, whereas this value is never used as key material -- nothing here signs or opens it --
 *       so the cheaper fix is to make it self-evidently not a secret.
 */
const CONFIRMATION_TOKEN = 'v2.aaaaaaaaaaaaaaaa.notarealsealedvalue';

/**
 * The copied record a copy turn's preview carries, in the published shapes.
 *
 * ⚠️ Refactoring Rationale: this is the published `CopiedTransactionData` and it carries NO resolved key.
 * A distinct `EffectiveCapture` block was authored carrying the ten data values AND the two resolved keys
 * together; the keys are members of the PREVIEW instead, because the service resolves them on every turn
 * -- `app/cbl/COTRN02C.cbl` L166 performs `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm exactly as L473
 * does for the copy arm -- while this record is attached to the copy turn alone.
 *
 * Assumptions: `merchantId` is exactly nine digits, which is the pattern the contract publishes here. The
 * submission helper's own merchant identifier is wider, and the difference is deliberate: this is the
 * value the SERVICE reports, normalised to the record's column width, not the text an operator typed.
 * @returns {CopiedTransactionData} A record every member check admits.
 */
function copiedRecord(): CopiedTransactionData {
  return {
    sourceTransactionId: '0000000000683580',
    typeCode: '01',
    categoryCode: '0001',
    source: 'POS TERM',
    description: 'PARITY CAPTURE',
    merchantId: '000000001',
    merchantName: 'PARITY MERCHANT',
    merchantCity: 'PARITY CITY',
    merchantZip: '00001',
    originDate: '2022-07-18',
    processDate: '2022-07-18',
  };
}

/**
 * The whole body a withheld turn answers with, whichever operation produced it.
 *
 * Assumptions: the resolved pair is present on every preview fixture because the contract requires it on
 * every preview. A case that wants one member to fail replaces exactly that member, so the failure it
 * observes can only be that member's.
 * @param {Record<string, unknown>} overrides - Members to replace, for a case that needs one to fail.
 * @returns {Record<string, unknown>} The body a successful withheld turn answers with.
 */
function previewBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    amount: AMOUNT,
    written: false,
    returnMessage: null,
    resolvedAccountId: ACCOUNT_ID,
    resolvedCardNumberMasked: RESOLVED_CARD_MASKED,
    confirmationToken: CONFIRMATION_TOKEN,
    copied: copiedRecord(),
    ...overrides,
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
  nextBody = previewBody();
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
  nextBody = previewBody({ written: true });
  await expect(addTransaction(submission())).rejects.toThrow(RangeError);
}

/**
 * Asserts the copy-last answer is read as a withheld preview carrying the copied record.
 *
 * ⚠️ Refactoring Rationale: the answer is a PREVIEW and not a bare block of eleven values. Two shapes
 * were authored on the second reading -- a lookup answering the values directly -- and the service
 * publishes neither: the reference's copy paragraph ends by performing `PROCESS-ENTER-KEY` at
 * `app/cbl/COTRN02C.cbl` L495, so one turn copies AND validates AND asks, and the operation answers the
 * same outcome the ordinary capture answers with. What is asserted here is that the copied record reaches
 * the caller through it, with all eleven values -- the ten data members plus the source row's identifier.
 */
async function readsTheCopiedRecordFromTheCopyAnswer(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = previewBody();

  const outcome = await copyLastTransaction({ accountId: ACCOUNT_ID });

  expect(outcome.outcome).toBe('PREVIEWED');
  if (outcome.outcome !== 'PREVIEWED') {
    throw new Error('the ok status must be read as a preview');
  }
  expect(outcome.preview.copied).toEqual(copiedRecord());
  expect(Object.keys(outcome.preview.copied ?? {})).toHaveLength(COPIED_FIELD_COUNT);
  expect(outcome.preview.resolvedAccountId).toBe(ACCOUNT_ID);
  expect(outcome.preview.resolvedCardNumberMasked).toBe(RESOLVED_CARD_MASKED);
  expect(outcome.preview.confirmationToken).toBe(CONFIRMATION_TOKEN);
}

/**
 * Asserts a copy answer missing any one member of its copied record is refused rather than applied in part.
 *
 * Assumptions: every member is dropped in turn rather than one representative member, because the caller's
 * purpose is to replace ten controls at once -- so a single member admitted absent would leave one control
 * holding what the operator typed beside nine that were replaced, which is precisely the mixture this
 * operation was corrected to stop producing.
 */
async function refusesACopyAnswerMissingAnyOneField(): Promise<void> {
  for (const member of Object.keys(copiedRecord())) {
    const partial: Record<string, unknown> = { ...copiedRecord() };
    delete partial[member];
    nextStatus = HTTP_OK;
    nextBody = previewBody({ copied: partial });
    await expect(copyLastTransaction({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);
  }
}

/**
 * Asserts a copy answer whose members breach their published shapes is refused.
 *
 * Assumptions: the breaches are chosen one per validation kind the shapes use -- a pattern on the category
 * code, a bound on the merchant name, the amount's mandatory two decimal places, an UNMASKED spelling of
 * the resolved card, and a binding token outside the sealed grammar -- so a validator that checked presence
 * and JavaScript type alone would fail every one.
 *
 * ⚠️ Refactoring Rationale: the resolved-card breach is INVERTED from what it was. It used to arrange
 * the masked rendering and expect a refusal, because the member published sixteen digits; the member now
 * publishes the masked rendering, so the sixteen-digit spelling is the breach -- and it is the one that
 * matters, because admitting it would let a service that had stopped masking put a whole primary account
 * number on a screen while every assertion still passed.
 */
async function refusesACopyAnswerBreachingItsPublishedShapes(): Promise<void> {
  const breaches: readonly Record<string, unknown>[] = [
    previewBody({ copied: { ...copiedRecord(), categoryCode: '1' } }),
    previewBody({ copied: { ...copiedRecord(), merchantName: '' } }),
    previewBody({ amount: '100.4' }),
    previewBody({ resolvedCardNumberMasked: RESOLVED_CARD }),
    previewBody({ confirmationToken: 'not-a-sealed-token' }),
  ];
  for (const breach of breaches) {
    nextStatus = HTTP_OK;
    nextBody = breach;
    await expect(copyLastTransaction({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);
  }
}

/**
 * Asserts the copy-last answer goes through the SAME preview validation as an ordinary capture's.
 *
 * ⚠️ Purpose: the two operations answer one shape, so one validator reads both bodies. This states it as a
 * property rather than leaving it to be inferred from the source: a copy answer whose capture flag
 * contradicts its status is refused exactly as the capture's is, which can only be true if the copy's body
 * reaches the same check. A second validator for the copy operation is what this forbids.
 */
async function copyLastSharesTheSameValidation(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = previewBody({ written: true });
  await expect(copyLastTransaction({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);

  nextStatus = HTTP_OK;
  nextBody = previewBody({ amount: 42.75 });
  await expect(copyLastTransaction({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);
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

/*
 * WHY : ⚠️ Refactoring Rationale: a SECOND generation of copy-answer cases stood here, written against a
 *       read-only lookup that answered the eleven values directly -- `copiedBody()` plus
 *       `readsTheElevenCopiedValues`, `refusesACopyEchoingAKey`, `refusesACopiedValueTheFormCannotHold`,
 *       `refusesACopiedAmountAsANumber` and `refusesACopyMissingAValue`. The service publishes no such
 *       operation: the reference's copy paragraph ends by performing `PROCESS-ENTER-KEY` at
 *       `app/cbl/COTRN02C.cbl` L495, so the copy answers the same withheld preview the ordinary capture
 *       answers with. Every property those five cases established is asserted above against that shape --
 *       all eleven values arriving, a missing member refused, a shape breach refused, a numeric amount
 *       refused -- and `refusesACopiedValueTheFormCannotHold` is kept below, because the two spellings it
 *       rejects are members the cases above do not touch.
 * WHY : Assumptions: the key-echo case is NOT carried across, and its premise is what withdrew it. It
 *       refused a body naming a key on the ground that the copy block moves nothing into the two key
 *       controls; the preview now publishes the RESOLVED pair deliberately, because
 *       `VALIDATE-INPUT-KEY-FIELDS` writes both key fields at L209 and L221 before the screen is re-sent,
 *       so a body naming a key is the contract rather than a breach. The property that replaced it -- that
 *       the pair is present, validated and unmasked -- is asserted by
 *       `readsTheCopiedRecordFromTheCopyAnswer` and by the masked-card breach above.
 */

/**
 * Asserts a copied value that the form's own field could not hold is refused on arrival.
 *
 * Assumptions: the two rejected spellings are a merchant identifier one digit short and a date carrying a
 * time. Both would be painted into a control whose predicate then refuses them, leaving the operator with
 * a refusal they did not cause and nothing on screen to explain it, so the breach is reported where it
 * happened instead.
 */
async function refusesACopiedValueTheFormCannotHold(): Promise<void> {
  nextStatus = HTTP_OK;
  nextBody = previewBody({ copied: { ...copiedRecord(), merchantId: '87654321' } });
  await expect(copyLastTransaction({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);

  nextBody = previewBody({
    copied: { ...copiedRecord(), originDate: '2026-01-10 12:00:00.000000' },
  });
  await expect(copyLastTransaction({ accountId: ACCOUNT_ID })).rejects.toThrow(RangeError);
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
  it('reads the copied record from the copy answer', readsTheCopiedRecordFromTheCopyAnswer);
  it('refuses a copied value the form cannot hold', refusesACopiedValueTheFormCannotHold);
  it('refuses a copy answer missing any one member', refusesACopyAnswerMissingAnyOneField);
  it(
    'refuses a copy answer breaching its published shapes',
    refusesACopyAnswerBreachingItsPublishedShapes,
  );
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
  answerWith(previewBody({ amount: '100.00', returnMessage: 'CONFIRM?' }), HTTP_OK);

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
  answerWith(previewBody({ amount: '100.00', returnMessage: 'CONFIRM?' }), HTTP_OK);
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

/**
 * Asserts the copy-last action has its own target and sends the key fields alone.
 *
 * Refactoring Rationale: the body is asserted, where this case previously sent a whole create request
 * and asserted only the target. Sending the eleven data members is what made the action unusable from
 * the screen that reaches it -- an operator presses the copy key INSTEAD of typing them -- so the body's
 * membership is the property worth fixing here, not just the URL.
 */
async function copiesTheLastTransactionAtItsOwnTarget(): Promise<void> {
  answerWith(previewBody(), HTTP_OK);

  const outcome = await copyLastTransaction({ cardNumber: RESOLVED_CARD });

  const request = onlyRequest();
  expect(request.url).toBe('/transactions/copy-last');
  expect(request.body).toEqual({ cardNumber: RESOLVED_CARD });
  /*
   * WHY : Assumptions: a copied value is read off the PREVIEW's copied record rather than off the answer
   *       itself, because the operation answers the same withheld preview the ordinary capture answers
   *       with -- the reference's own fall-through at `app/cbl/COTRN02C.cbl` L495 -- and not a bare block
   *       of copied values.
   */
  expect(outcome.outcome).toBe('PREVIEWED');
  if (outcome.outcome !== 'PREVIEWED') {
    throw new Error('the ok status must be read as a preview');
  }
  expect(outcome.preview.copied?.description).toBe('PARITY CAPTURE');
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
