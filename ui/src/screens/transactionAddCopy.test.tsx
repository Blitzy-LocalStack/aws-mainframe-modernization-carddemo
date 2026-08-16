/**
 * @file Proves the transaction add screen's copy-last key transcribes `COPY-LAST-TRAN-DATA`: it
 * validates the key alone, it puts every copied value on the glass, and the turn that confirms writes
 * exactly what is displayed rather than re-reading whichever row is latest.
 *
 * Purpose
 * -------
 * `ui/src/screens/transactionAdd/index.tsx` replaces `app/cbl/COTRN02C.cbl`, whose PF5 arm at L146-L147
 * performs `COPY-LAST-TRAN-DATA` at L471. That paragraph performs `VALIDATE-INPUT-KEY-FIELDS` at L473
 * and NOTHING else, reads the last stored row with `STARTBR`/`READPREV` at L475-L478, moves ELEVEN
 * copied columns onto the terminal's own input fields at L480-L493, and only then performs
 * `PROCESS-ENTER-KEY` at L495 -- over the fields it has just overwritten.
 *
 * What these cases exist to prevent
 * ---------------------------------
 * Assumptions: three defects are pinned here, because all three were present and none of them was
 * observable from the type system. The screen ran the whole data-field chain before copying, so a copy
 * from an empty screen -- the ordinary way the key is used -- was refused before any request left the
 * browser. The preview carried only the amount, so nine of the eleven copied values were never rendered
 * and the operator was asked to confirm a record the screen was not showing. And the CONFIRMING turn went
 * back through the copy operation, which re-reads the latest row, so a transaction inserted between the
 * two turns silently became the record written.
 *
 * Assumptions: the transport module is mocked so each case controls what the service answers and can
 * assert what the screen sent. The verbatim sentences are read from the catalog rather than retyped, for
 * the reason the sibling screen tests record: a retyped sentence is a second spelling of a value the
 * catalog owns, and it would keep passing after the catalog changed.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { addTransaction, copyLastTransaction } from '../api/transactions';
import type { CopiedTransactionData, TransactionAddOutcome } from '../api/transactions';
import { AppShell } from '../layout/AppShell';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { PROGRAM_MESSAGES } from '../messages/messages';
import {
  TRANSACTION_ADD_FIELD_LABELS,
  TRANSACTION_ADD_KEY_LABELS,
  TransactionAddScreen,
  buildCopyRequest,
  paintCopiedValues,
  toEditMaskAmount,
} from './transactionAdd';
import type { TransactionAddValues } from './transactionAdd';

/**
 * Builds the mocked surface of the ledger transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The two transport functions this screen calls, each a fresh spy.
 */
function mockLedgerTransportModule(): Record<string, unknown> {
  return {
    addTransaction: vi.fn(),
    copyLastTransaction: vi.fn(),
  };
}

vi.mock('../api/transactions', mockLedgerTransportModule);

/** Sentences this program declares, from the single catalog that owns them. */
const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;

/** An account identifier at its declared eleven-digit width. */
const ACCOUNT_ID = '00000000011';

/**
 * The card the cross-reference resolves for that account, sixteen digits and unmasked.
 *
 * Assumptions: every withheld answer reports it, because the service resolves the pair on every turn --
 * `app/cbl/COTRN02C.cbl` L166 performs `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm as L473 does for the
 * copy arm -- and the screen repaints its card control from it, as L209 does.
 */
const RESOLVED_CARD = '4111111111111111';

/**
 * The ten non-monetary copied columns, as the service reports them, and the pair it resolved.
 *
 * Assumptions: each value is distinguishable from every other, and none of them is blank. A record whose
 * members shared a value could not prove that each one landed in its OWN field, which is the whole of
 * what the eleven `MOVE` statements at `app/cbl/COTRN02C.cbl` L480-L493 do.
 *
 * ⚠️ Refactoring Rationale: this is the published `CopiedTransactionData`, where a `TransactionCopiedDraft`
 * of ten members was authored for the same answer. Three further members are published with it -- the row
 * the values came from, and the account and card the key resolution settled on -- so a fixture carrying ten
 * describes a body the service does not send. The resolved pair is what the screen paints into its two key
 * fields, reproducing L206 and L209.
 */
const COPIED: CopiedTransactionData = {
  sourceTransactionId: '0000000000683580',
  typeCode: '07',
  categoryCode: '0042',
  source: 'POS TERM',
  description: 'COPIED MERCHANDISE PURCHASE',
  merchantId: '000123456',
  merchantName: 'COPIED MERCHANT NAME',
  merchantCity: 'COPIED CITY',
  merchantZip: '30301',
  originDate: '2024-03-14',
  processDate: '2024-03-15',
};

/** The copied amount in the service's own monetary form, which the screen re-renders through its mask. */
const COPIED_AMOUNT = '1234.56';

/** The same amount as the screen's `+99999999.99` edit mask renders it. */
const COPIED_AMOUNT_MASKED = '+00001234.56';

/** Values the screen holds once a copy has landed, used by the pure adoption cases. */
const BLANK: TransactionAddValues = {
  accountId: ACCOUNT_ID,
  cardNumber: '',
  typeCode: '',
  categoryCode: '',
  source: '',
  description: '',
  amount: '',
  originDate: '',
  processDate: '',
  merchantId: '',
  merchantName: '',
  merchantCity: '',
  merchantZip: '',
  confirmation: '',
};

/**
 * Builds the preview answer a copy produces: the normalised amount and the ten-member draft.
 * @returns {TransactionAddOutcome} The 200 outcome carrying a populated draft.
 */
function copiedPreview(): TransactionAddOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      amount: COPIED_AMOUNT,
      written: false,
      returnMessage: null,
      resolvedAccountId: ACCOUNT_ID,
      resolvedCardNumber: RESOLVED_CARD,
      copied: COPIED,
    },
  };
}

/**
 * Builds the preview answer an ordinary unconfirmed capture produces, which copies nothing.
 * @param {string} amount - Amount the service normalised, in its monetary form.
 * @returns {TransactionAddOutcome} The 200 outcome carrying no draft.
 */
function capturePreview(amount: string): TransactionAddOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      amount,
      written: false,
      returnMessage: null,
      resolvedAccountId: ACCOUNT_ID,
      resolvedCardNumber: RESOLVED_CARD,
      copied: null,
    },
  };
}

/**
 * Renders the screen inside the one shell the application mounts, on a router `useNavigate` requires.
 *
 * ⚠️ Refactoring Rationale: the real `AppShell` is mounted around the screen, where the screen alone was
 * rendered before. This screen DELEGATES its title band, its row-23 message line and its row-24 key legend
 * to the shell -- `ui/src/App.tsx` mounts one shell above the outlet, and a screen painting them too would
 * render two of each -- so the function-key legend these cases press exists only when the shell is present.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(): ReactElement {
  return (
    <MemoryRouter initialEntries={['/transactions/new']}>
      <AppShell>
        <TransactionAddScreen />
      </AppShell>
    </MemoryRouter>
  );
}

/**
 * Builds the interaction driver with the inter-keystroke delay suppressed.
 *
 * ⚠️ Assumptions: `delay: null` is load-bearing rather than a speed preference. The default driver waits
 * between keystrokes, and one case here fills all twelve data controls -- 60-odd characters -- to prove an
 * ordinary capture adopts no draft. At the default delay that case runs in under three seconds alone and
 * in over fourteen under the full parallel suite, so it passed in isolation and exceeded the five-second
 * per-case budget when every worker was busy. Suppressing the delay removes the dependence on how loaded
 * the machine is without weakening a single assertion; raising the budget instead would leave a genuinely
 * hung case indistinguishable from a slow one.
 * @returns {ReturnType<typeof userEvent.setup>} The configured driver.
 */
function operatorDriver(): ReturnType<typeof userEvent.setup> {
  return userEvent.setup({ delay: null });
}

/** Restores the spies between cases. */
function resetSpies(): void {
  vi.mocked(addTransaction).mockReset();
  vi.mocked(copyLastTransaction).mockReset();
}

/**
 * Returns the legend control that invokes one function key.
 *
 * Assumptions: the control is looked up INSIDE the function-key region rather than by label alone,
 * because the submit control beside the confirmation field carries the SCREEN TITLE while the legend
 * carries the key labels -- and `PfKeyBar` renders a `nav` with an accessible name, so its role is
 * `navigation` rather than `region`. A global label lookup would work today and would break the moment a
 * screen label collided with a key label, which is the failure the sibling screen tests already hit.
 * @param {string} label - The legend label, verbatim from the mapset's row-24 literal.
 * @returns {HTMLElement} The legend control bearing that label.
 * @throws {Error} If the region holds no control with that label, so a renamed label fails loudly rather
 *   than silently exercising nothing.
 */
function legendControl(label: string): HTMLElement {
  const region = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const found = Array.from(region.querySelectorAll('button')).find(
    /**
     * Reports whether one control's text is the label sought, with interior runs collapsed.
     * @param {HTMLButtonElement} control - Candidate control.
     * @returns {boolean} Whether its text matches.
     */
    (control: HTMLButtonElement): boolean => collapse(control.textContent ?? '') === label,
  );
  if (found === undefined) {
    throw new Error(`no function-key control labelled ${label}`);
  }
  return found;
}

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: this exists because the legend labels and the message-band sentences both carry padding
 * the DOM collapses on display -- the row-24 literal separates its segments with two spaces and every
 * catalogued sentence is padded to its declared `PIC X` width. Collapsing the expectation rather than
 * trimming the constant keeps the catalog and the mapset as the source of truth.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Sets one control's value in a single change event.
 *
 * ⚠️ Assumptions: this is used ONLY by the case that must fill all twelve data controls, and it uses one
 * change event per control where every other interaction in this file goes through the keystroke-level
 * driver. The reason is measured rather than stylistic: each control is React-controlled, so
 * keystroke-level typing re-renders the whole form once per character -- roughly a hundred renders of a
 * screen carrying fourteen antd `Form.Item`s -- which took that one case past the five-second per-case
 * budget under the full parallel suite while it passed comfortably in isolation. That case's subject is
 * the RESPONSE HANDLER, not the typing, so one change event per control exercises exactly what it is
 * about; every case whose subject IS the interaction, including all four function-key dispatches, keeps
 * the keystroke-level driver.
 * @param {string} label - The field's label, verbatim from the mapset.
 * @param {string} value - The value to place in the control.
 * @returns {void} Completion is the updated control.
 */
function setField(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

/**
 * Reads one editable field's current value.
 * @param {string} label - The field's label, verbatim from the mapset.
 * @returns {string} The value the control currently holds.
 */
function fieldValue(label: string): string {
  const control = screen.getByLabelText(label);
  return control instanceof HTMLInputElement ? control.value : '';
}

/**
 * Proves the copy key sends the key alone and never the eleven data fields.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function copiesFromAnOtherwiseEmptyScreen(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(copiedPreview());
  const operator = operatorDriver();
  render(renderScreen());

  await operator.type(screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.PFK05));

  await waitFor(
    /**
     * Waits for the copy to have been dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(copyLastTransaction).toHaveBeenCalledTimes(1);
    },
  );

  /*
   * Assumptions: the body is compared for EXACT equality rather than by member, because the defect this
   * case pins is a body carrying more than a key. A member-wise check would pass against the wide shape
   * that refused an empty screen.
   */
  expect(vi.mocked(copyLastTransaction).mock.calls[0]?.[0]).toEqual({ accountId: ACCOUNT_ID });
  expect(addTransaction).not.toHaveBeenCalled();
}

/**
 * Proves the copy key still refuses a submission naming neither key, with the reference's sentence.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesACopyWithNoKey(): Promise<void> {
  const operator = operatorDriver();
  render(renderScreen());

  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.PFK05));

  /*
   * Assumptions: the sentence is expected TWICE and the count is asserted rather than one occurrence
   * being fetched. The reference moves one string into `WS-MESSAGE` and also highlights the field it
   * names, so the migrated screen renders it in the message band and again beneath the control -- and a
   * single-element query would fail on the duplicate rather than on the behaviour.
   */
  expect(
    await screen.findAllByText(ADD_MESSAGES.ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED),
  ).toHaveLength(2);
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Proves all eleven copied values reach the glass and the confirmation prompt follows.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersEveryCopiedValue(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(copiedPreview());
  const operator = operatorDriver();
  render(renderScreen());

  await operator.type(screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.PFK05));

  expect(await screen.findByText(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION)).toBeInTheDocument();

  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.typeCode)).toBe(COPIED.typeCode);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.categoryCode)).toBe(COPIED.categoryCode);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.source)).toBe(COPIED.source);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.description)).toBe(COPIED.description);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.merchantId)).toBe(COPIED.merchantId);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.merchantName)).toBe(COPIED.merchantName);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.merchantCity)).toBe(COPIED.merchantCity);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.merchantZip)).toBe(COPIED.merchantZip);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.originDate)).toBe(COPIED.originDate);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.processDate)).toBe(COPIED.processDate);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.amount)).toBe(COPIED_AMOUNT_MASKED);
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.accountId)).toBe(ACCOUNT_ID);
}

/**
 * Proves the confirming turn is a CAPTURE of the displayed draft and not a second copy.
 *
 * Assumptions: this is the case the CRITICAL finding turns on. The screen previously routed the
 * confirming turn back through the copy operation, which re-reads whichever row is latest -- so the
 * assertion that matters is not merely that a write happened but that `copyLastTransaction` was called
 * exactly ONCE across the whole exchange, and that the body sent to `addTransaction` carries the drafted
 * values rather than the blanks the operator keyed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function confirmsWithTheDisplayedDraft(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(copiedPreview());
  vi.mocked(addTransaction).mockResolvedValue({
    outcome: 'CREATED',
    created: { transactionId: '000000000000099', amount: COPIED_AMOUNT, returnMessage: '' },
  });
  const operator = operatorDriver();
  render(renderScreen());

  await operator.type(screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.PFK05));
  expect(await screen.findByText(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION)).toBeInTheDocument();

  await operator.type(screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS.confirmation), 'Y');
  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.ENTER));

  await waitFor(
    /**
     * Waits for the capture to have been dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(addTransaction).toHaveBeenCalledTimes(1);
    },
  );

  expect(copyLastTransaction).toHaveBeenCalledTimes(1);
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]).toEqual({
    accountId: ACCOUNT_ID,
    typeCode: COPIED.typeCode,
    categoryCode: COPIED.categoryCode,
    source: COPIED.source,
    description: COPIED.description,
    amount: COPIED_AMOUNT,
    originDate: COPIED.originDate,
    processDate: COPIED.processDate,
    merchantId: COPIED.merchantId,
    merchantName: COPIED.merchantName,
    merchantCity: COPIED.merchantCity,
    merchantZip: COPIED.merchantZip,
    confirmation: 'Y',
  });
}

/**
 * Proves the ordinary capture turn still runs the data chain the copy turn now skips.
 *
 * Assumptions: this is the counterpart of the first case and it exists so that the fix cannot be read as
 * having removed the data-field chain altogether. `PROCESS-ENTER-KEY` performs BOTH validations at
 * `app/cbl/COTRN02C.cbl` L166-L167, so Enter on a screen with a key and nothing else must still report
 * the first blank data field and reach no service at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function stillValidatesDataFieldsOnEnter(): Promise<void> {
  const operator = operatorDriver();
  render(renderScreen());

  await operator.type(screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.ENTER));

  expect(await screen.findAllByText(ADD_MESSAGES.TYPE_CD_CAN_NOT_BE_EMPTY)).toHaveLength(2);
  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Proves an ordinary preview still re-renders the amount alone and adopts no draft.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anOrdinaryPreviewAdoptsNoDraft(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(capturePreview('42.00'));
  const operator = operatorDriver();
  render(renderScreen());

  setField(TRANSACTION_ADD_FIELD_LABELS.accountId, ACCOUNT_ID);
  setField(TRANSACTION_ADD_FIELD_LABELS.typeCode, '07');
  setField(TRANSACTION_ADD_FIELD_LABELS.categoryCode, '0042');
  setField(TRANSACTION_ADD_FIELD_LABELS.source, 'POS');
  setField(TRANSACTION_ADD_FIELD_LABELS.description, 'KEYED');
  setField(TRANSACTION_ADD_FIELD_LABELS.amount, '+00000042.00');
  setField(TRANSACTION_ADD_FIELD_LABELS.originDate, '2024-03-14');
  setField(TRANSACTION_ADD_FIELD_LABELS.processDate, '2024-03-15');
  setField(TRANSACTION_ADD_FIELD_LABELS.merchantId, '000123456');
  setField(TRANSACTION_ADD_FIELD_LABELS.merchantName, 'KEYED M');
  setField(TRANSACTION_ADD_FIELD_LABELS.merchantCity, 'KEYED C');
  setField(TRANSACTION_ADD_FIELD_LABELS.merchantZip, '30301');
  await operator.click(legendControl(TRANSACTION_ADD_KEY_LABELS.ENTER));

  expect(await screen.findByText(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION)).toBeInTheDocument();
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.amount)).toBe('+00000042.00');
  expect(fieldValue(TRANSACTION_ADD_FIELD_LABELS.description)).toBe('KEYED');
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Asserts the copy body carries one zero-filled key and nothing else, for either key.
 * @returns {void} Nothing; the case asserts.
 */
function buildsACopyBodyCarryingOneKeyOnly(): void {
  /*
   * WHY : Assumptions: the confirmation is passed BLANK on both calls and is asserted absent from both
   *       bodies, which is the third parameter this builder now takes. An unconfirmed turn is spelled by
   *       absence, so a body carrying an empty confirmation would be a second spelling of it; the arm that
   *       does carry one is asserted below.
   */
  expect(buildCopyRequest({ ...BLANK, accountId: '11' }, 'accountId', '')).toEqual({
    accountId: '00000000011',
  });
  expect(
    buildCopyRequest({ ...BLANK, accountId: '', cardNumber: '4111111111111111' }, 'cardNumber', ''),
  ).toEqual({ cardNumber: '4111111111111111' });
  expect(buildCopyRequest({ ...BLANK, accountId: '11' }, 'accountId', 'Y')).toEqual({
    accountId: '00000000011',
    confirmation: 'Y',
  });
}

/**
 * Asserts adoption writes all eleven copied values, the resolved pair, and leaves the confirmation alone.
 *
 * ⚠️ Refactoring Rationale: the expectation is written out member by member rather than spread from the
 * fixture, and the CARD field is expected to hold the resolved card rather than to stay blank. The fixture
 * now carries three members that are not screen fields -- the source row and the resolved pair -- so
 * spreading it would assert three properties on a values object that has no such fields. And the card field
 * is written: `app/cbl/COTRN02C.cbl` L473 performs `VALIDATE-INPUT-KEY-FIELDS` before the read, whose
 * account arm moves the cross-reference's card number into `CARDNINI` at L209, after which the screen is
 * re-sent.
 * @returns {void} Nothing; the case asserts.
 */
function adoptsElevenValuesAndKeepsTheKey(): void {
  const held: TransactionAddValues = { ...BLANK, confirmation: 'N', description: 'TYPED OVER' };
  const adopted = paintCopiedValues(
    held,
    COPIED,
    { accountId: ACCOUNT_ID, cardNumber: RESOLVED_CARD },
    toEditMaskAmount(COPIED_AMOUNT),
  );

  expect(adopted).toEqual({
    accountId: ACCOUNT_ID,
    cardNumber: RESOLVED_CARD,
    typeCode: COPIED.typeCode,
    categoryCode: COPIED.categoryCode,
    source: COPIED.source,
    description: COPIED.description,
    amount: COPIED_AMOUNT_MASKED,
    originDate: COPIED.originDate,
    processDate: COPIED.processDate,
    merchantId: COPIED.merchantId,
    merchantName: COPIED.merchantName,
    merchantCity: COPIED.merchantCity,
    merchantZip: COPIED.merchantZip,
    confirmation: 'N',
  });
}

/**
 * Asserts a monetary form the screen's mask cannot express leaves the amount field untouched.
 *
 * Assumptions: the mask spends eight of its twelve characters on integer digits, so a nine-digit figure
 * -- which the 350-byte record's `TRAN-AMT PIC S9(09)V99` can hold and this screen's field cannot -- has
 * no rendering here. Writing a truncated value would put a figure on the glass that is not the one
 * stored, and refusing to write leaves the field showing what the operator can still see is blank.
 * @returns {void} Nothing; the case asserts.
 */
function leavesAnUnrenderableAmountAlone(): void {
  const adopted = paintCopiedValues(
    { ...BLANK, amount: '' },
    COPIED,
    { accountId: ACCOUNT_ID, cardNumber: RESOLVED_CARD },
    toEditMaskAmount('123456789.00'),
  );
  expect(adopted.amount).toBe('');
  expect(adopted.typeCode).toBe(COPIED.typeCode);
}

/** Registers the rendered copy-exchange cases. */
function renderedCopyCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('validates the key alone and sends no data fields', copiesFromAnOtherwiseEmptyScreen);
  it('still refuses a copy naming neither key', refusesACopyWithNoKey);
  it('renders every copied value and prompts for confirmation', rendersEveryCopiedValue);
  it(
    'confirms by capturing the displayed draft, never by copying again',
    confirmsWithTheDisplayedDraft,
  );
  it('still validates the data fields on an ordinary Enter', stillValidatesDataFieldsOnEnter);
  it('adopts no draft on an ordinary preview', anOrdinaryPreviewAdoptsNoDraft);
}

/** Registers the pure request-building and draft-adoption cases. */
function pureCopyCases(): void {
  it('carries one zero-filled key and nothing else', buildsACopyBodyCarryingOneKeyOnly);
  it('writes eleven values and keeps the key and confirmation', adoptsElevenValuesAndKeepsTheKey);
  it('leaves an amount the mask cannot express alone', leavesAnUnrenderableAmountAlone);
}

describe('the transaction add screen copies the last transaction', renderedCopyCases);

describe('the copy request and the draft adoption', pureCopyCases);
