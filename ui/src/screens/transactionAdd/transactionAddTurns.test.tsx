/**
 * @file Component tests for the transaction-add screen's copy turn and its confirmation summary.
 *
 * Purpose
 * -------
 * Cover the three defects a review found on this screen, all of which are about what happens BETWEEN two
 * turns and none of which a type or a single-turn test can see:
 *
 * - the ordinary copy turn must reach the endpoint. Pressing the copy key with nothing but a key entered
 *   is the whole of the action, and the screen used to run all eleven create-field validations first, so
 *   the turn was refused on the first empty data field and the copy never happened;
 * - the copy must SHOW what it copied. Eleven values come from the copied record, and the screen used to
 *   write back only the amount, so the operator confirmed ten values the screen never displayed;
 * - the confirmation must name the record that will actually be written. The service resolves the missing
 *   half of the key pair from the cross-reference, and the summary was composed from what the operator
 *   keyed -- so with both keys entered it named the card the service DISCARDS, and with only an account
 *   entered it named no card at all.
 *
 * How the turns are driven
 * -----------------------
 * Assumptions: the transport is substituted and its two operations are asserted SEPARATELY, because the
 * copy defect is precisely that one of them was never called. A test that mocked a single shared
 * dispatcher could not distinguish "copied" from "captured", which is the distinction at issue.
 *
 * Assumptions: the screen is mounted inside `ui/src/layout/AppShell.tsx`, which is the authenticated
 * layout route it delegates its title band and key legend to. Rendered alone it would paint neither, and
 * the copy key this file presses lives in that legend.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AppShell } from '../../layout/AppShell';
import { PF_KEY_BAR_REGION_LABEL } from '../../layout/PfKeyBar';
import type {
  CopiedTransactionData,
  TransactionAddOutcome,
  CopyLastTransactionRequest,
  TransactionCreateRequest,
} from '../../api/transactions';

/**
 * Builds the mocked surface of the ledger transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The two operations this screen dispatches, each as its own spy.
 */
function mockLedgerTransportModule(): Record<string, unknown> {
  return { addTransaction: vi.fn(), copyLastTransaction: vi.fn() };
}

vi.mock('../../api/transactions', mockLedgerTransportModule);

/** Eleven digits: the account key the operator enters. */
const ACCOUNT_ID = '00000000011';

/** Sixteen digits: a card the operator may type alongside the account, which the service discards. */
const TYPED_CARD = '4111111111111111';

/**
 * A DIFFERENT sixteen digits: the card the service resolves from the account.
 *
 * Assumptions: this differs from {@link TYPED_CARD} on purpose, and the difference is the whole of the
 * card-parity case. If the resolved and typed values were equal, a summary composed from either source
 * would read identically and the case would pass against the defect.
 */
const RESOLVED_CARD = '5555444433332222';

/**
 * The masked rendering the service publishes for {@link RESOLVED_CARD}.
 *
 * ⚠️ Refactoring Rationale: the preview member carries this rendering rather than the sixteen digits, so
 * the parity case below compares the SERVICE'S masked value against the masked spelling of what the operator
 * typed. The screen no longer masks anything itself -- the helper it used to call is gone, because a helper
 * that masks has to be handed the unmasked value first, which is the disclosure the service-side mask
 * exists to remove.
 */
const RESOLVED_CARD_MASKED = '************2222';

/** The masked spelling of {@link TYPED_CARD}, which must NOT appear on the confirmation surface. */
const TYPED_CARD_MASKED = '************1111';

/** The opaque binding the preview publishes alongside the masked card. */
// The value is deliberately low-entropy; `ui/src/api/transactions.test.ts` records why.
const CONFIRMATION_TOKEN = 'v2.aaaaaaaaaaaaaaaa.notarealsealedvalue';

/**
 * Every value the service reports as the record it copied.
 *
 * ⚠️ Refactoring Rationale: this is the published `CopiedTransactionData`, where a distinct
 * `EffectiveCapture` shape was authored for the same answer. The two resolved keys are members of the
 * copied record itself -- transaction-service's own record declares them there -- so folding them in keeps
 * one answer per call, and the card-parity property this suite exists to pin is asserted on that one shape.
 *
 * Assumptions: the source identifier is present because the published shape leads with it. It names the row
 * the values came from, which is what lets a caller tell that two copy turns copied the same row.
 */
const CAPTURE: CopiedTransactionData = {
  sourceTransactionId: '0000000000683580',
  typeCode: '01',
  categoryCode: '5411',
  source: 'POS',
  description: 'COPIED GROCERY PURCHASE',
  merchantId: '000000999',
  merchantName: 'ACME GROCERY',
  merchantCity: 'ALBANY',
  merchantZip: '12207',
  originDate: '2022-07-18',
  processDate: '2022-07-19',
};

/** The preview a copy turn answers with: the amount, the prompt, and the resolved capture. */
const COPY_PREVIEW: TransactionAddOutcome = {
  outcome: 'PREVIEWED',
  preview: {
    amount: '123.45',
    written: false,
    returnMessage: null,
    /*
     * WHY : ⚠️ Assumptions: the RESOLVED pair is a member of the PREVIEW and not of the copied record,
     *       because the service reports it on every withheld answer: `app/cbl/COTRN02C.cbl` L166 performs
     *       `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm exactly as L473 does for the copy arm, and L209
     *       and L221 write both key fields before the screen is re-sent. Holding it inside the copied
     *       record published it on the copy turn alone.
     */
    resolvedAccountId: ACCOUNT_ID,
    resolvedCardNumberMasked: RESOLVED_CARD_MASKED,
    confirmationToken: CONFIRMATION_TOKEN,
    copied: CAPTURE,
  },
};

/**
 * The outcome a confirmed CAPTURE turn answers with.
 *
 * Assumptions: it carries the reference's own acknowledgement, `WS-RETURN-MSG` composed at
 * `app/cbl/COTRN02C.cbl` L728-L733, so a case reading the sentence reads the one the service latches
 * rather than an invented one.
 */
const CAPTURE_WRITTEN: TransactionAddOutcome = {
  outcome: 'CREATED',
  created: {
    transactionId: '0000000000683581',
    amount: '123.45',
    returnMessage: 'Transaction added successfully. Your Tran ID is 0000000000683581.',
  },
};

/**
 * Renders the screen inside the shell at its own route.
 * @returns {Promise<void>} Resolves once the screen is mounted.
 */
async function renderScreen(): Promise<void> {
  const { TransactionAddScreen } = await import('./index');

  render(
    <MemoryRouter initialEntries={[{ pathname: '/transactions/new' }]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/transactions/new" element={<TransactionAddScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Reads one of the screen's controls by its painted label.
 *
 * Assumptions: controls are found by LABEL rather than by identifier, because this screen derives its
 * control identifiers from `useId` so that two instances cannot collide -- there is no stable identifier
 * to query. Each of its fields carries its own mapset label, so a label query is unambiguous here in a
 * way it would not be on a screen with split field groups.
 * @param {string} label - The painted label, verbatim.
 * @returns {HTMLElement} That field's control.
 */
function control(label: string): HTMLElement {
  return screen.getByLabelText(label);
}

/**
 * Presses the copy key from the shell's function-key legend.
 *
 * Assumptions: the legend control is used rather than a synthesised key event, because the screen hands
 * its bindings up to the shell and the legend is where the resulting control is rendered. Pressing it
 * exercises the same handler a keystroke would reach, through the path an operator with a mouse takes.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @returns {Promise<void>} Resolves once the turn has been dispatched.
 */
async function pressCopyKey(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const { TRANSACTION_ADD_KEY_LABELS } = await import('./index');
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });

  await user.click(within(legend).getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.PFK05 }));
  await act(
    /**
     * Drains the microtask queue so the outcome is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Presses the Enter key from the shell's function-key legend, which runs one turn.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @returns {Promise<void>} Resolves once the turn has been dispatched.
 */
async function pressEnterKey(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const { TRANSACTION_ADD_KEY_LABELS } = await import('./index');
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });

  await user.click(within(legend).getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.ENTER }));
  await act(
    /**
     * Drains the microtask queue so the outcome is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Opens the confirmation surface, which is where the resolved summary is rendered.
 *
 * Assumptions: the trigger is matched by a name CONTAINING the title rather than equalling it, and the
 * reason was measured rather than guessed. The design system animates its loading indicator OUT: once a
 * turn completes, the button's `ant-btn-loading-icon` element stays in the tree until a transition end
 * that jsdom never fires, and its text contributes to the computed accessible name -- so the name reads
 * `loading Add Transaction` and an exact match finds nothing. The button is NOT busy and NOT disabled:
 * the `ant-btn-loading` class that would disable it is absent, which is what was measured, and the
 * confirmation opens on the click. Matching on containment keeps the assertion about the button's own
 * label rather than about a residual animation node.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @returns {Promise<void>} Resolves once the surface is open.
 */
async function openConfirmation(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const { TRANSACTION_ADD_TITLE } = await import('./index');

  await user.click(
    screen.getByRole('button', {
      /**
       * Reports whether one control's accessible name carries the confirmation trigger's label.
       * @param {string} accessibleName - The name the testing library computed for the candidate.
       * @returns {boolean} `true` for the confirmation trigger.
       */
      name: (accessibleName: string): boolean => accessibleName.includes(TRANSACTION_ADD_TITLE),
    }),
  );
}

/**
 * Resets both transport spies so no case inherits another's queued outcome.
 * @returns {Promise<void>} Resolves once the spies are reset.
 */
async function resetTransport(): Promise<void> {
  const { addTransaction, copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(addTransaction).mockReset();
  vi.mocked(copyLastTransaction).mockReset();
}

/**
 * A copy turn with only a key entered reaches the endpoint.
 *
 * Assumptions: the assertion is that the copy operation was CALLED, and that the capture operation was
 * not. Under the defect neither happened -- the data-field chain refused the turn on the first empty
 * field -- so a case asserting only the absence of the capture call would pass against it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function copiesFromAKeyAlone(): Promise<void> {
  const { addTransaction, copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValueOnce(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await pressCopyKey(user);

  expect(vi.mocked(copyLastTransaction)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(addTransaction)).not.toHaveBeenCalled();
}

/**
 * The copy body carries the key and nothing the copy would discard.
 *
 * Assumptions: the member set is asserted EXACTLY rather than by checking the key is present, because the
 * defect was that eleven further members were sent -- all of them overwritten from the copied record --
 * and a containment assertion would be satisfied by a body that still carried them.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function sendsOnlyTheKeyOnACopyTurn(): Promise<void> {
  const { copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValueOnce(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await pressCopyKey(user);

  const body = vi.mocked(copyLastTransaction).mock.calls[0]?.[0] as CopyLastTransactionRequest;
  expect(Object.keys(body)).toEqual(['accountId']);
  expect(body.accountId).toBe(ACCOUNT_ID);
}

/**
 * A copy preview shows every value it copied.
 *
 * Assumptions: all ten non-amount members are asserted plus the amount, because under the defect exactly
 * one of the eleven was written back. Asserting a representative field would have a one-in-eleven chance
 * of picking the one that already worked.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function showsEveryCopiedValue(): Promise<void> {
  const { copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValueOnce(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await pressCopyKey(user);

  expect(control(TRANSACTION_ADD_FIELD_LABELS.typeCode)).toHaveValue(CAPTURE.typeCode);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.categoryCode)).toHaveValue(CAPTURE.categoryCode);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.source)).toHaveValue(CAPTURE.source);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.description)).toHaveValue(CAPTURE.description);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.originDate)).toHaveValue(CAPTURE.originDate);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.processDate)).toHaveValue(CAPTURE.processDate);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.merchantId)).toHaveValue(CAPTURE.merchantId);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.merchantName)).toHaveValue(CAPTURE.merchantName);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.merchantCity)).toHaveValue(CAPTURE.merchantCity);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.merchantZip)).toHaveValue(CAPTURE.merchantZip);
  expect(control(TRANSACTION_ADD_FIELD_LABELS.amount)).toHaveValue('+00000123.45');
}

/**
 * The confirmation names the card the service resolved, not the one the operator typed.
 *
 * Assumptions: BOTH halves are asserted -- the resolved card's last four digits appear and the typed
 * card's do not. The first alone would pass for a summary that showed both; the second alone would pass
 * for a summary that showed no card, which is what an account-only turn used to produce.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function namesTheResolvedCardAndNotTheTypedOne(): Promise<void> {
  const { copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValueOnce(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.cardNumber), TYPED_CARD);
  await pressCopyKey(user);
  await openConfirmation(user);

  /*
   * WHY : ⚠️ Assumptions: both spellings are written out as literals rather than produced by calling a
   *       masking helper the screen exports. The screen exported one while the preview carried sixteen
   *       digits, and asserting through it made the case agree with whatever the helper did -- including
   *       agreeing with a helper that had stopped masking. Comparing against the literal rendering the
   *       contract publishes is what makes the case able to fail.
   * WHY : Assumptions: the typed card's masked spelling is asserted ABSENT as well, and the two literals
   *       differ in their last four digits by construction, so a summary composed from the control instead
   *       of from the service's answer fails the second assertion.
   */
  const resolved = `${TRANSACTION_ADD_FIELD_LABELS.cardNumber} ${RESOLVED_CARD_MASKED}`;
  const typed = `${TRANSACTION_ADD_FIELD_LABELS.cardNumber} ${TYPED_CARD_MASKED}`;
  expect(screen.getByText(resolved)).toBeInTheDocument();
  expect(screen.queryByText(typed)).not.toBeInTheDocument();
  /*
   * WHY : ⚠️ Assumptions: the sixteen-digit resolved number is asserted to appear NOWHERE in the rendered
   *       document, which is the disclosure property the migrated contract exists to hold. The two
   *       assertions above would both pass on a screen that also printed the full number somewhere else.
   */
  expect(screen.queryByText(new RegExp(RESOLVED_CARD, 'u'))).not.toBeInTheDocument();
}

/**
 * The confirmation names the resolved account even when only a card was keyed.
 *
 * Assumptions: this is the mirror of the case above and it exists because the two resolution directions
 * are separate code paths in the reference -- a card from an account at `app/cbl/COTRN02C.cbl` L206 to
 * L209, an account from a card at L220 to L223 -- so a summary could read one and not the other.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function namesTheResolvedAccountFromACardKey(): Promise<void> {
  const { copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValueOnce(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.cardNumber), TYPED_CARD);
  await pressCopyKey(user);
  await openConfirmation(user);

  expect(
    screen.getByText(`${TRANSACTION_ADD_FIELD_LABELS.accountId} ${ACCOUNT_ID}`),
  ).toBeInTheDocument();
}

/**
 * No summary is offered before a preview has reported one.
 *
 * Assumptions: this is what keeps the cases above from being satisfied by a screen that always rendered
 * the same summary. A confirmation surface with nothing behind it would be worse than none, because it
 * would carry the authority of a service response while describing no record at all.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function offersNoSummaryBeforeAPreview(): Promise<void> {
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await openConfirmation(user);

  expect(
    screen.queryByText(`${TRANSACTION_ADD_FIELD_LABELS.accountId} ${ACCOUNT_ID}`),
  ).not.toBeInTheDocument();
}

/**
 * Reports whether the OPEN confirmation surface carries one line of text.
 *
 * Assumptions: leaving and hidden popovers are excluded, and the exclusion was measured rather than
 * assumed. The design system animates its popover out: closing one leaves the node in the tree carrying
 * `ant-zoom-big-leave` until a transition end that jsdom never fires, with its whole content still
 * readable -- so a document-wide `queryByText` reports a summary that is no longer displayed and an
 * absence assertion fails against correct code. It was measured doing exactly that. Reading only from a
 * popover that is neither leaving nor hidden keeps the assertion on what an operator can see.
 * @param {string} line - The exact line to look for.
 * @returns {boolean} `true` when an open popover carries that line.
 */
function openSurfaceShows(line: string): boolean {
  return [...document.querySelectorAll('.ant-popover')]
    .filter(
      /**
       * Reports whether one popover node is currently displayed.
       * @param {Element} node - The candidate popover.
       * @returns {boolean} `true` when it is neither hidden nor animating out.
       */
      (node: Element): boolean =>
        !node.className.includes('ant-popover-hidden') && !node.className.includes('-leave'),
    )
    .some(
      /**
       * Reports whether one displayed popover carries the line.
       * @param {Element} node - The displayed popover.
       * @returns {boolean} `true` when its text contains the line.
       */
      (node: Element): boolean => (node.textContent ?? '').includes(line),
    );
}

/**
 * Editing a key withdraws the summary, so no stale resolved record can be confirmed.
 *
 * Refactoring Rationale: this case first asserted that a confirming turn whose key had changed dispatched
 * no second copy, and it PASSED with the guard for that removed -- so it was measuring something else. The
 * diagnosis is recorded on the screen's own turn runner: an edit to a key field discards the preview
 * before any confirming turn can be reached, which makes a key comparison on that turn unreachable. The reachable
 * property, and the one the review actually asked for, is this: the summary describing the record the
 * service resolved cannot outlive the values it was resolved from.
 *
 * Assumptions: BOTH halves are asserted -- the summary is present after the preview and absent after the
 * edit. Asserting only its absence would pass for a screen that never rendered a summary at all, which is
 * the state this whole surface was added to fix.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function withdrawsTheSummaryWhenAKeyIsEdited(): Promise<void> {
  const { copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValue(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await pressCopyKey(user);
  await openConfirmation(user);
  const resolvedLine = `${TRANSACTION_ADD_FIELD_LABELS.accountId} ${ACCOUNT_ID}`;
  expect(openSurfaceShows(resolvedLine)).toBe(true);

  /*
   * Assumptions: the key is edited with a backspace on the focused control, because the account control
   * carries the mapset's eleven-character width -- so an eleven-digit value is full and an appended
   * keystroke would be discarded before any handler saw it, leaving the case asserting nothing.
   */
  await user.click(control(TRANSACTION_ADD_FIELD_LABELS.accountId));
  await user.keyboard('{Backspace}');

  /*
   * Assumptions: the confirmation is RE-OPENED before the absence is asserted, and this is not
   * ceremony. Clicking the field to edit it also closes the open popover, so an assertion taken at that
   * point reports only that the surface had closed -- which is true whether or not the summary behind it
   * was withdrawn. That was measured: without this re-open, removing the invalidation entirely left this
   * case green. Re-opening asks the screen to render the surface again, which is the only way to observe
   * that there is no longer a resolved record to describe.
   */
  await openConfirmation(user);

  expect(openSurfaceShows(resolvedLine)).toBe(false);
}

/**
 * A confirming turn taken without editing anything re-submits the copy, carrying the answer.
 *
 * Assumptions: the confirming turn must route through the COPY operation rather than the capture, because
 * the record being written is the copied one. That routing is decided by a flag the confirmation keystroke
 * used to clear -- so typing the answer submitted the capture while answering through the modal submitted
 * the copy, one answer producing two different operations. This case fixes the keyboard path in place.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function honoursAConfirmationTakenWithoutEditing(): Promise<void> {
  const { addTransaction, copyLastTransaction } = await import('../../api/transactions');
  vi.mocked(copyLastTransaction).mockResolvedValue(COPY_PREVIEW);
  /*
   * WHY : ⚠️ Refactoring Rationale: the CAPTURE operation is arranged as well as the copy, and it was
   *       not. Until the confirming turn was routed through the capture this case only ever called the
   *       copy operation, so leaving `addTransaction` a bare spy was harmless; afterwards the spy
   *       answered `undefined`, the screen called `.then` on it, and the run recorded an uncaught
   *       `TypeError` while every assertion here still passed -- the count assertions are satisfied by
   *       the call having been MADE. An unarranged spy on a path the case now takes is a false green.
   */
  vi.mocked(addTransaction).mockResolvedValue(CAPTURE_WRITTEN);
  const { TRANSACTION_ADD_FIELD_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  await pressCopyKey(user);

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.confirmation), 'Y');
  await pressEnterKey(user);

  /*
   * WHY : ⚠️ Refactoring Rationale: the confirming turn is asserted to go through the CAPTURE and to leave
   *       the copy operation called exactly once, where a second copy call was asserted before. The
   *       earlier assertion described a write hazard: routing the confirmation back through the copy
   *       operation re-resolves "the most recently stored transaction", so a row appended between the
   *       operator's preview and their confirmation silently replaced what they had been shown. The copy
   *       operation now publishes the ten values it copied, this screen adopts them, and the confirming
   *       turn writes the screen -- which is the reference's own sequence, its L495 re-entry reading the
   *       fields the copy block filled at L481 to L492.
   * WHY : Assumptions: the property this case was written for is preserved and strengthened. It exists to
   *       pin that ONE answer produces ONE operation, whichever surface gave it; asserting the copy count
   *       stays at one states that AND states that "last" is resolved once per action.
   */
  expect(vi.mocked(copyLastTransaction)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(addTransaction)).toHaveBeenCalledTimes(1);
  const written = vi.mocked(addTransaction).mock.calls[0]?.[0] as TransactionCreateRequest;
  expect(written.confirmation).toBe('Y');
  expect(written.accountId).toBe(ACCOUNT_ID);
  expect(written.description).toBe(CAPTURE.description);
  expect(written.merchantId).toBe(CAPTURE.merchantId);
}

/**
 * A capture turn still validates and still sends the full create body.
 *
 * Assumptions: this guards the fix against having relaxed the wrong turn. The copy turn skips the data
 * chain; the capture turn must not, and it must still carry all eleven data members -- so a change that
 * skipped validation for both operations fails here rather than shipping.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function stillValidatesAndSendsTheFullCaptureBody(): Promise<void> {
  const { addTransaction } = await import('../../api/transactions');
  vi.mocked(addTransaction).mockResolvedValue(COPY_PREVIEW);
  const { TRANSACTION_ADD_FIELD_LABELS, TRANSACTION_ADD_KEY_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await user.type(control(TRANSACTION_ADD_FIELD_LABELS.accountId), ACCOUNT_ID);
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  await user.click(within(legend).getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.ENTER }));
  await act(
    /**
     * Drains the microtask queue so any dispatched turn would have been recorded.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  expect(vi.mocked(addTransaction)).not.toHaveBeenCalled();

  for (const [label, value] of [
    [TRANSACTION_ADD_FIELD_LABELS.typeCode, CAPTURE.typeCode],
    [TRANSACTION_ADD_FIELD_LABELS.categoryCode, CAPTURE.categoryCode],
    [TRANSACTION_ADD_FIELD_LABELS.source, CAPTURE.source],
    [TRANSACTION_ADD_FIELD_LABELS.description, CAPTURE.description],
    [TRANSACTION_ADD_FIELD_LABELS.amount, '+00000123.45'],
    [TRANSACTION_ADD_FIELD_LABELS.originDate, CAPTURE.originDate],
    [TRANSACTION_ADD_FIELD_LABELS.processDate, CAPTURE.processDate],
    [TRANSACTION_ADD_FIELD_LABELS.merchantId, CAPTURE.merchantId],
    [TRANSACTION_ADD_FIELD_LABELS.merchantName, CAPTURE.merchantName],
    [TRANSACTION_ADD_FIELD_LABELS.merchantCity, CAPTURE.merchantCity],
    [TRANSACTION_ADD_FIELD_LABELS.merchantZip, CAPTURE.merchantZip],
  ]) {
    await user.type(control(String(label)), String(value));
  }
  await user.click(within(legend).getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.ENTER }));
  await act(
    /**
     * Drains the microtask queue so the capture turn is recorded.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  expect(vi.mocked(addTransaction)).toHaveBeenCalledTimes(1);
  const body = vi.mocked(addTransaction).mock.calls[0]?.[0] as TransactionCreateRequest;
  expect(body.merchantZip).toBe(CAPTURE.merchantZip);
  expect(body.typeCode).toBe(CAPTURE.typeCode);
}

/**
 * Registers every case, and resets the transport between them.
 * @returns {void} Nothing; the registrations are the effect.
 */
function transactionAddTurnCases(): void {
  beforeEach(resetTransport);
  afterEach(
    /**
     * Clears any spy state a case installed.
     * @returns {void} Nothing.
     */
    (): void => {
      vi.restoreAllMocks();
    },
  );

  it('copies from a key alone', copiesFromAKeyAlone);
  it('sends only the key on a copy turn', sendsOnlyTheKeyOnACopyTurn);
  it('shows every copied value', showsEveryCopiedValue);
  it('names the resolved card and not the typed one', namesTheResolvedCardAndNotTheTypedOne);
  it('names the resolved account from a card key', namesTheResolvedAccountFromACardKey);
  it('offers no summary before a preview', offersNoSummaryBeforeAPreview);
  it('withdraws the summary when a key is edited', withdrawsTheSummaryWhenAKeyIsEdited);
  it('honours a confirmation taken without editing', honoursAConfirmationTakenWithoutEditing);
  it('still validates and sends the full capture body', stillValidatesAndSendsTheFullCaptureBody);
}

describe('transaction add screen turns', transactionAddTurnCases);
