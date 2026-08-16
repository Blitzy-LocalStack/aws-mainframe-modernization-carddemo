/**
 * @file Proves the capture screen's copy-last action resolves "the most recently stored transaction"
 * exactly once per action, and that the confirming turn writes the values the operator was shown.
 *
 * Purpose
 * -------
 * `COPY-LAST-TRAN-DATA` at `app/cbl/COTRN02C.cbl` L471 to L495 reads one row and moves ELEVEN of its
 * values into the operator's own unprotected map fields, then performs `PROCESS-ENTER-KEY` at L495. Every
 * turn after that reads the fields, so the row is read once and what is written is what is on the glass.
 * These cases assert both halves of that in the browser: the copied values reach the controls, and the
 * confirming turn goes to the CAPTURE operation carrying them rather than back through the copy.
 *
 * Refactoring Rationale: the second case exists because the screen used to route the confirming turn back
 * through the copy operation, which re-resolves which row is last -- so a row appended between the
 * operator's preview and their confirmation was written instead of the one they had approved. Only a case
 * that counts the calls can catch that: every visible expectation was satisfied by the defective
 * arrangement, because the values it wrote were a real transaction's, just not the previewed one.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it` and `waitFor` is a NAMED
 * declaration, which is the shape `ui/src/screens/cardScreens.test.tsx` records: `ui/eslint.config.js`
 * selects a function expression in every position, so an inline callback needs its own JSDoc block, and
 * Prettier moves a block comment that follows an argument comma onto the preceding string literal, which
 * detaches the block from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { addTransaction, copyLastTransaction } from '../../api/transactions';
import type { CopiedTransactionData, TransactionAddOutcome } from '../../api/transactions';
import { AppShell } from '../../layout/AppShell';
import { TRANSACTION_ADD_FIELD_LABELS, TransactionAddScreen } from './index';

/**
 * Builds the mocked surface of the ledger transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The two capture operations, each a fresh spy.
 */
function mockLedgerTransportModule(): Record<string, unknown> {
  return {
    addTransaction: vi.fn(),
    copyLastTransaction: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these cases
 * assert WHICH operation a turn reaches and what it carries -- which is a property of the screen, not of
 * the interceptor chain. Mocking the client would make a routing regression indistinguishable from a
 * transport one.
 */
vi.mock('../../api/transactions', mockLedgerTransportModule);

/** The account the operator is working on, eleven digits as the key field declares. */
const KEYED_ACCOUNT = '00000000011';

/**
 * The masked rendering the service publishes for the card it resolved from that account.
 *
 * ⚠️ Refactoring Rationale: this was the sixteen-digit number, because the preview published the card
 * unmasked and this screen painted it into the card control. A review found that a non-administrative
 * preview therefore handed a whole primary account number to the browser, so the service publishes the
 * masked rendering and the screen no longer repaints the control -- both of which this file now asserts.
 */
const RESOLVED_CARD_MASKED = '************1111';

/**
 * The opaque binding the preview publishes so the confirming turn can name the same card.
 *
 * Assumptions: only its presence on the confirming request is asserted; nothing here interprets it, because
 * only the service can open it.
 */
// The value is deliberately low-entropy; `ui/src/api/transactions.test.ts` records why.
const CONFIRMATION_TOKEN = 'v2.aaaaaaaaaaaaaaaa.notarealsealedvalue';

/**
 * The ten copied members the service publishes, plus the row they came from.
 *
 * Assumptions: every value differs from every blank the screen starts with AND from anything a case types,
 * so an assertion that a control holds one of these can only pass if the copy reached that control.
 */
const COPIED: CopiedTransactionData = {
  sourceTransactionId: '0000000000683580',
  typeCode: '02',
  categoryCode: '0002',
  source: 'ATM TERM',
  description: 'FUEL PURCHASE',
  merchantId: '987654321',
  merchantName: 'FUEL STOP',
  merchantCity: 'TACOMA',
  merchantZip: '98402',
  originDate: '2026-01-10',
  processDate: '2026-01-11',
};

/**
 * The answer a withheld copy turn produces: the normalised amount, the prompt, and what it copied.
 *
 * Assumptions: `written` is false and the status-derived outcome is `PREVIEWED`, which is the pair the
 * contract fixes for a turn that captured nothing.
 */
const COPY_WITHHELD: TransactionAddOutcome = {
  outcome: 'PREVIEWED',
  preview: {
    amount: '42.75',
    written: false,
    returnMessage: 'Confirm to add this transaction...',
    /*
     * WHY : ⚠️ Assumptions: the RESOLVED pair is a member of the preview and not of the copied record. The
     *       service reports it on every withheld answer, because `app/cbl/COTRN02C.cbl` L166 performs
     *       `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm exactly as L473 does for the copy arm, and L209
     *       and L221 write both key fields before the screen is re-sent.
     * WHY : ⚠️ Assumptions: the resolved account matches the keyed one, which is the account arm's own
     *       outcome; the resolved card arrives MASKED, and the confirming turn names it by returning the
     *       binding token rather than by re-sending digits the browser was never given.
     */
    resolvedAccountId: KEYED_ACCOUNT,
    resolvedCardNumberMasked: RESOLVED_CARD_MASKED,
    confirmationToken: CONFIRMATION_TOKEN,
    copied: COPIED,
  },
};

/**
 * The answer the ordinary capture gives a written turn.
 *
 * Assumptions: a distinct identifier from the copied row's, because the service allocates the key it
 * appends under rather than reusing the key it copied.
 */
const CAPTURED: TransactionAddOutcome = {
  outcome: 'CREATED',
  created: {
    transactionId: '0000000000683581',
    amount: '42.75',
    returnMessage: 'Transaction added successfully.  Your Tran ID is 0000000000683581',
  },
};

/**
 * Mounts the capture screen inside the one shell the application mounts, on an in-memory router.
 *
 * Assumptions: `MemoryRouter` rather than the application router, so one screen mounts without the route
 * table and without navigating a jsdom history.
 *
 * ⚠️ Refactoring Rationale: the real `AppShell` is mounted around the screen, where the screen alone was
 * rendered before. This screen DELEGATES its title band, its row-23 message line and its row-24 key legend
 * to the shell -- `ui/src/App.tsx` mounts one shell above the outlet, and a screen that painted them too
 * would show two of each -- so the copy key's own legend control exists only when the shell is present.
 * Rendering the screen bare left this suite pressing a control that is not on the page.
 * @returns {void} Nothing; the screen is rendered into the test document.
 */
function renderCaptureScreen(): void {
  render(
    <MemoryRouter initialEntries={['/transactions/new']}>
      <AppShell>
        <TransactionAddScreen />
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Reads one field's control by the label the mapset paints beside it.
 * Assumptions: no cast is applied, because `getByLabelText` is already typed to the element the control
 * renders and `@typescript-eslint/no-unnecessary-type-assertion` refuses a cast that narrows nothing.
 * @param {keyof typeof TRANSACTION_ADD_FIELD_LABELS} field - Field to read.
 * @returns {HTMLInputElement} The control rendered for that field.
 */
function controlFor(field: keyof typeof TRANSACTION_ADD_FIELD_LABELS): HTMLInputElement {
  return screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS[field]);
}

/**
 * Keys the account identifier and presses the copy key, then waits for the answer to be applied.
 * @returns {Promise<void>} Resolves once the copied description is on the screen.
 */
async function keyTheAccountAndCopy(): Promise<void> {
  await userEvent.type(controlFor('accountId'), KEYED_ACCOUNT);
  await userEvent.click(screen.getByRole('button', { name: /F5=Copy Last Tran\./u }));
  await waitFor(
    /**
     * Waits until the copied description has reached its control.
     * @returns {void} Nothing; throws until the value is applied.
     */
    () => {
      expect(controlFor('description')).toHaveValue(COPIED.description);
    },
  );
}

/**
 * Restores the module mocks between cases so one case's answer cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetLedgerMocks(): void {
  vi.mocked(addTransaction).mockReset();
  vi.mocked(copyLastTransaction).mockReset();
}

/**
 * Asserts a withheld copy turn puts all eleven copied values on the screen.
 *
 * Assumptions: the amount is asserted in the screen's own edit-mask rendering `+00000042.75` rather than
 * in the service's `42.75`, because the field is twelve characters wide and `WS-TRAN-AMT-E PIC
 * +99999999.99` at `app/cbl/COTRN02C.cbl` L59 is what L481 renders the stored figure through. Adopting the
 * service's shorter form would put a value in the control that the screen's own predicate refuses.
 *
 * ⚠️ Assumptions: the ACCOUNT field is asserted to still hold the keyed value and the CONFIRMATION to
 * still be blank, because a copy must land against the account the operator was already working on and must
 * not answer its own prompt. The CARD field is asserted to remain exactly as the operator left it -- blank
 * on this case -- where it used to be asserted to hold the resolved card. The reference does repaint that
 * field (`app/cbl/COTRN02C.cbl` L473 performs `VALIDATE-INPUT-KEY-FIELDS`, L208 reads the cross-reference
 * and L209 moves the card into `CARDNINI`), but it repaints it with sixteen digits it already holds in
 * storage. The migrated service publishes the MASKED rendering instead, so painting the answer into an
 * editable numeric key field would leave a value there that the next turn's own pattern refuses. The
 * resolved card is therefore shown as protected text on the confirmation surface, which the parity case in
 * `transactionAddTurns.test.tsx` asserts.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function adoptsEveryCopiedValue(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(COPY_WITHHELD);

  renderCaptureScreen();
  await keyTheAccountAndCopy();

  expect(controlFor('typeCode')).toHaveValue(COPIED.typeCode);
  expect(controlFor('categoryCode')).toHaveValue(COPIED.categoryCode);
  expect(controlFor('source')).toHaveValue(COPIED.source);
  expect(controlFor('description')).toHaveValue(COPIED.description);
  expect(controlFor('originDate')).toHaveValue(COPIED.originDate);
  expect(controlFor('processDate')).toHaveValue(COPIED.processDate);
  expect(controlFor('merchantId')).toHaveValue(COPIED.merchantId);
  expect(controlFor('merchantName')).toHaveValue(COPIED.merchantName);
  expect(controlFor('merchantCity')).toHaveValue(COPIED.merchantCity);
  expect(controlFor('merchantZip')).toHaveValue(COPIED.merchantZip);
  expect(controlFor('amount')).toHaveValue('+00000042.75');

  expect(controlFor('accountId')).toHaveValue(KEYED_ACCOUNT);
  expect(controlFor('cardNumber')).toHaveValue('');
  expect(controlFor('confirmation')).toHaveValue('');
}

/**
 * Asserts the confirming turn reaches the CAPTURE operation with the previewed values.
 *
 * Assumptions: the copy operation is asserted to have been reached exactly ONCE across the whole action,
 * which is the property parity depends on and the only one a defective arrangement fails. Reaching it a
 * second time would re-resolve which row is last, so a row appended between the preview and the
 * confirmation would be written in place of the one the operator approved.
 *
 * Assumptions: the request the capture receives is asserted member by member, because "the confirming turn
 * used the capture operation" is satisfied by a body carrying blanks. What must be true is that the body
 * carries the COPIED values, and it can only do so because the screen adopted them.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function confirmsThroughTheCaptureOperation(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(COPY_WITHHELD);
  vi.mocked(addTransaction).mockResolvedValue(CAPTURED);

  renderCaptureScreen();
  await keyTheAccountAndCopy();

  await userEvent.type(controlFor('confirmation'), 'Y');
  await userEvent.click(screen.getByRole('button', { name: /ENTER=Continue/u }));

  await waitFor(
    /**
     * Waits until the capture operation has been reached.
     * @returns {void} Nothing; throws until the call has been recorded.
     */
    () => {
      expect(vi.mocked(addTransaction)).toHaveBeenCalledTimes(1);
    },
  );

  expect(vi.mocked(copyLastTransaction)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]).toMatchObject({
    accountId: KEYED_ACCOUNT,
    typeCode: COPIED.typeCode,
    categoryCode: COPIED.categoryCode,
    source: COPIED.source,
    description: COPIED.description,
    originDate: COPIED.originDate,
    processDate: COPIED.processDate,
    merchantId: COPIED.merchantId,
    merchantName: COPIED.merchantName,
    merchantCity: COPIED.merchantCity,
    merchantZip: COPIED.merchantZip,
    amount: '42.75',
    confirmation: 'Y',
    /*
     * WHY : ⚠️ Assumptions: the binding token is asserted on the confirming request, and it is what
     *       replaced the sixteen-digit card the browser used to hold and re-send. The service refuses a
     *       token that does not name the card it resolves, so this member is what makes the write land on
     *       the card the operator was shown -- and asserting it here is what stops the token being quietly
     *       dropped from the builder, which would leave the service resolving the card again from the key
     *       and the parity property unenforced.
     */
    confirmationToken: CONFIRMATION_TOKEN,
  });
}

/**
 * Registers the two cases.
 * @returns {void} Nothing.
 */
function copyLastCases(): void {
  afterEach(resetLedgerMocks);

  it('puts every copied value on the screen, and leaves the keys alone', adoptsEveryCopiedValue);
  it(
    'confirms through the capture operation, resolving the last row once',
    confirmsThroughTheCaptureOperation,
  );
}

describe('the capture screen copies one stored row and writes what it showed', copyLastCases);
