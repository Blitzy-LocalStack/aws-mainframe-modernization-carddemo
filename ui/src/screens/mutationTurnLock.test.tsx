/**
 * @file Proves the two writing screens accept nothing while a write is outstanding, and that user add
 * answers the one key its mapset advertises but its program does not dispatch.
 *
 * Purpose
 * -------
 * `app/cbl/COBIL00C.cbl` and `app/cbl/COUSR01C.cbl` each run one terminal turn at a time, and a 3270
 * keyboard locked from the moment a turn was transmitted until the region replied. Nothing on the
 * display accepted input during that window -- not the screen's own attention keys, not its entry
 * controls, and not the confirmation answer. A browser offers no such serialisation, so the equivalent
 * has to be declared, and these cases are what hold it declared: each one holds a transport promise
 * open and asserts across the whole window rather than after it. The bill-payment turn is the one that
 * moves money, so it is asserted on both of its turns -- the reporting read and the confirmed write.
 *
 * Parameters
 * ----------
 * Not applicable. This module declares test cases and takes no inputs of its own.
 *
 * Return values
 * -------------
 * Not applicable. Each case reports through its expectations.
 *
 * Exceptions or errors
 * --------------------
 * None are raised here. Every held promise is resolved by the case that held it, so no unhandled
 * rejection can outlive a case.
 *
 * Alternatives Considered: reading each screen's `disabled` member out of its `usePfKeys` call, which
 * is the cheaper assertion. Rejected because it proves the declaration rather than the behaviour: a
 * flag that reached the hook but not the rendered control, or a handler whose own in-flight guard was
 * removed, would both leave such a check green. Querying the rendered controls AND pressing the
 * physical keys exercises both paths an operator has, which are separate paths in this tree --
 * `usePfKeys` installs a document keydown listener that a disabled button does not intercept.
 *
 * Assumptions: the transport modules are substituted rather than a service stood up, because the
 * property under test is what the screen does BETWEEN the request and the reply -- a window a real
 * service closes as fast as it can and which therefore cannot be held open from outside.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../api/auth';
import type * as TransactionsModule from '../api/transactions';
import type {
  BillPaymentOutcome,
  BillPaymentPreview,
  BillPaymentResponse,
  CreatedUserResponse,
} from '../api/types';

/** Stands in for the one identity write user add issues. */
const createUserMock = vi.fn();

/**
 * Stands in for the READING turn of bill payment.
 *
 * ⚠️ Refactoring Rationale: two spies replace the single `payAccountBalanceInFull` stand-in this file
 * used to hold, because the screen no longer calls that function on either turn. It composes the read
 * and the write separately — `inquireAccountPayableBalance` takes an account identifier and nothing
 * else, so no confirmation can travel on a preview, and `payAccountBalanceConfirmed` refuses a
 * non-confirming answer before it sends. Substituting only the shared transport left BOTH turns
 * running the real wrappers, since a wrapper calls its own module's transport by an internal
 * reference a mocked export cannot intercept: the read then failed against no server, the screen
 * refused instead of locking, F3 was live, and this file failed on the navigation it asserts against
 * rather than on anything about a lock.
 */
const inquireAccountPayableBalanceMock = vi.fn();

/** Stands in for the WRITING turn of bill payment, which moves the money. */
const payAccountBalanceConfirmedMock = vi.fn();

vi.mock(
  '../api/auth',
  /**
   * Replaces the create this screen issues while leaving every other export intact.
   *
   * Assumptions: a partial substitution rather than a bare replacement, because `USER_ID_MAX_LENGTH`
   * is a copybook width the screen reads for a control's `maxLength` and `ui/src/hooks/useAuth.ts`
   * reads four further members from this module. A bare replacement would size the identifier control
   * `undefined` and leave the shell's identity hook holding undefined operations, so the failures
   * would be about the substitution rather than about the screens.
   * @returns {Promise<typeof AuthModule>} The real module with the create stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof AuthModule>('../api/auth');
    return { ...actual, createUser: createUserMock };
  },
);

vi.mock(
  '../api/transactions',
  /**
   * Replaces both bill-payment turns while leaving every other export intact.
   * @returns {Promise<typeof TransactionsModule>} The real module with both turns stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof TransactionsModule>('../api/transactions');
    return {
      ...actual,
      inquireAccountPayableBalance: inquireAccountPayableBalanceMock,
      payAccountBalanceConfirmed: payAccountBalanceConfirmedMock,
    };
  },
);

/*
 * Assumptions: every module that can reach a substituted transport is imported DYNAMICALLY, below the
 *   two factories. A static import is hoisted above them, runs a factory that closes over the two
 *   module-scope spies, and fails on a temporal-dead-zone access before any case runs. That reach is
 *   not obvious for the layout modules and was established rather than guessed:
 *   `ui/src/layout/AppShell.tsx` imports `ui/src/hooks/useAuth.ts`, which imports `../api/auth`, and
 *   `ui/src/router.tsx` imports `ui/src/routes/guards.tsx`, which imports the same hook.
 */
const { default: BillPayScreen } = await import('./billPay');
const { default: UserAddScreen } = await import('./userAdd');
const { AppShell } = await import('../layout/AppShell');
const { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } = await import('../layout/PfKeyBar');
const { MESSAGE_BAND_TEST_ID } = await import('../layout/MessageBand');
const { BILL_PAY_PATH, USER_ADD_PATH } = await import('../router');
const { SIGN_ON_ROUTE } = await import('../routes/guards');

/*
 * Assumptions: these two are imported statically because neither can reach a transport --
 *   `ui/src/messages/messages.ts` has no imports at all and `ui/src/routes/navigation.ts` has only a
 *   type import. Making them dynamic would suggest a dependency edge that does not exist.
 */
import {
  BILL_PAY_FIELD_LABELS,
  BILL_PAY_KEY_LABELS,
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  USER_ADD_FIELD_LABELS,
  USER_ADD_KEY_LABELS,
} from '../messages/messages';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE } from '../routes/navigation';

/** Text a probe route paints, so a navigation off a locked screen would be visible. */
const ARRIVED = 'ARRIVED';

/** Account identifier at the eleven-digit width `ACCT-ID PIC 9(11)` declares. */
const ACCOUNT_ID = '00000000011';

/** The answer the confirmation dialogue's primary control sends, `'Y'` at `app/cbl/COBIL00C.cbl` L174. */
const CONFIRMING_ANSWER = 'Y';

/** The user this screen creates, at each control's declared width. */
const SUBMITTED_USER = {
  firstName: 'ADA',
  lastName: 'LOVELACE',
  userId: 'USER0001',
  userType: 'U',
} as const;

/** What the substituted create answers with: the stored row, the credential and its locator. */
const CREATED_USER: CreatedUserResponse = {
  userId: SUBMITTED_USER.userId,
  firstName: SUBMITTED_USER.firstName,
  lastName: SUBMITTED_USER.lastName,
  userType: 'U',
  cognitoSub: '33333333-3333-3333-3333-333333333333',
  credentialSecretName: 'carddemo/dev/user/USER0001',
  oneTimeCredential: 'Aa1!aaaaaaaaaaaaaaaaaaaa',
};

/**
 * What the reporting turn answers with: a payable balance and nothing written.
 *
 * Assumptions: `returnMessage` carries the reference's own confirmation prompt rather than null, and
 * that is what makes the turn PAYABLE rather than merely successful. `previewTurn` reads the sentence
 * to decide -- `'Confirm to make a bill payment...'` at `app/cbl/COBIL00C.cbl` L237 is the prompt the
 * reference paints when it has a balance to offer, `'You have nothing to pay...'` is the advisory it
 * paints when it has not, and neither the balance nor the `paid` flag distinguishes the two. A null
 * message therefore reaches the trailing branch, which reports the sentence and offers no payment.
 */
const PAYABLE_PREVIEW: Extract<BillPaymentOutcome, { readonly outcome: 'PREVIEWED' }> = {
  outcome: 'PREVIEWED',
  preview: {
    accountId: ACCOUNT_ID,
    payableBalance: '1234.56',
    paid: false,
    returnMessage: PROGRAM_MESSAGES.COBIL00C.CONFIRM_TO_MAKE_A_BILL_PAYMENT,
  },
};

/**
 * What the confirming turn answers with once the payment is written.
 *
 * Assumptions: the type is the PAID arm of the union rather than the union itself, so the case can read
 * `payment.transactionId` without narrowing at the use site. Typing it as the union compiles the
 * literal and then refuses the member, because the union's other arm publishes a preview instead.
 */
const WRITTEN_PAYMENT: Extract<BillPaymentOutcome, { readonly outcome: 'PAID' }> = {
  outcome: 'PAID',
  payment: {
    transactionId: '000000000000001',
    accountId: ACCOUNT_ID,
    currentBalance: '1234.56',
    paid: true,
    returnMessage: null,
  },
};

/**
 * A promise whose settlement the case controls.
 * @template T Value the held promise resolves with.
 */
interface Deferred<T> {
  /** The promise handed to the screen, still pending. */
  readonly promise: Promise<T>;
  /** Releases the response. */
  readonly release: (value: T) => void;
}

/**
 * Creates a promise this module settles by hand, so a request can be observed mid-flight.
 *
 * Assumptions: definite assignment rather than a no-op initialiser, because a promise executor runs
 * synchronously so the resolver is assigned before this returns.
 * @template T Value the caller will settle with.
 * @returns {Deferred<T>} The pending promise and its resolver.
 */
function deferred<T>(): Deferred<T> {
  let release!: (value: T) => void;
  const promise = new Promise<T>(
    /**
     * Captures the resolver so the case decides when the request completes.
     * @param {(value: T) => void} resolve - The promise's own resolver.
     * @returns {void} Nothing; the resolver is recorded for later use.
     */
    (resolve: (value: T) => void): void => {
      release = resolve;
    },
  );
  return { promise, release };
}

/**
 * Renders bill payment inside the shared frame, with a probe at its one exit.
 *
 * Assumptions: the frame is part of the tree because this screen delegates all three persistent zones
 * to it, so the legend controls these cases query are painted by the shell and not by the screen. A
 * bare screen render would paint no legend at all and every key assertion would fail for the wrong
 * reason.
 * @returns {void} Completion is the mounted tree.
 */
function renderBillPay(): void {
  render(
    <MemoryRouter initialEntries={[BILL_PAY_PATH]}>
      <AppShell>
        <Routes>
          <Route path={BILL_PAY_PATH} element={<BillPayScreen />} />
          <Route path={MAIN_MENU_ROUTE} element={<div>{`${ARRIVED} ${MAIN_MENU_ROUTE}`}</div>} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Renders user add inside the shared frame, with probes at both destinations a key could reach.
 *
 * Assumptions: a sign-on probe is declared even though no key should reach it, and that is the point of
 * it. `app/cbl/COUSR01C.cbl` L90-L102 has no `DFHPF12` arm, so the only way to show that PF12 does not
 * end the session is to give a session end somewhere observable to land.
 * @returns {void} Completion is the mounted tree.
 */
function renderUserAdd(): void {
  render(
    <MemoryRouter initialEntries={[USER_ADD_PATH]}>
      <AppShell>
        <Routes>
          <Route path={USER_ADD_PATH} element={<UserAddScreen />} />
          <Route path={ADMIN_MENU_ROUTE} element={<div>{`${ARRIVED} ${ADMIN_MENU_ROUTE}`}</div>} />
          <Route path={SIGN_ON_ROUTE} element={<div>{`${ARRIVED} ${SIGN_ON_ROUTE}`}</div>} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Returns one legend control by the caption the frame paints on it.
 * @param {string} label - The verbatim row-24 caption the control renders.
 * @returns {HTMLElement} The control the frame painted for that caption.
 */
function legendControl(label: string): HTMLElement {
  return within(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).getByRole(
    'button',
    { name: label },
  );
}

/**
 * Returns the one-position control the operator answers the confirmation into.
 *
 * ⚠️ Refactoring Rationale: this replaces a helper that located a confirmation PANEL's own answer
 * button by eliminating the trigger that opened it. There is no panel and no answer button any more.
 * A runtime sweep measured the previous arrangement moving focus onto an enabled commit control after
 * a preview and then onto the panel's own affirmative, so three bare presses of Enter paid an account
 * balance in full with the confirming letter never typed. The gate is now the field
 * `app/bms/COBIL00.bms` L115-L119 declares — `ATTRB=(FSET,NORM,UNPROT) LENGTH=1`, carrying no `IC`,
 * so the cursor is not sent to it either — and a commit is reachable only from a character the
 * operator supplied.
 * @returns {HTMLElement} The confirmation entry control.
 */
function confirmationEntry(): HTMLElement {
  return screen.getByTestId('billpay-confirm');
}

/**
 * Fills every control user add requires, in the mapset's own reading order.
 * @returns {Promise<void>} Resolves once all five values are entered.
 */
async function enterEveryUserValue(): Promise<void> {
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.firstName), 'ADA');
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.lastName), 'LOVELACE');
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.userId), 'USER0001');
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.userType.trim()), 'U');
}

/**
 * Asserts the reporting turn locks the entry and all three keys, then releases them.
 *
 * Assumptions: BOTH states are asserted -- shut during the window and usable after it -- because a
 * control disabled unconditionally would satisfy the first half while removing the only way off the
 * screen, and a lock that never released would be the more damaging defect of the two.
 *
 * ⚠️ Refactoring Rationale: the key that OWNS the outstanding turn is now asserted enabled and
 * `aria-busy`, where this case previously asserted it disabled. The screen moved its Enter binding
 * from the `disabled` channel onto the `busy` channel `ui/src/layout/usePfKeys.ts` added, and the two
 * are mutually exclusive by construction -- the hook tests `disabled` before `busy`, so an entry
 * carrying both would never report busy at all. Trade-offs: `busy` keeps the control present, named
 * and focusable and declines the press SILENTLY, which is the 3270 input-inhibit behaviour a valid
 * key pressed early earns; `disabled` additionally states the action is unavailable, which is the
 * honest signal for the keys that are NOT the outstanding turn. Both channels are therefore asserted
 * here, each on the control it belongs to -- collapsing them onto one would stop distinguishing "your
 * key arrived early" from "this key is unavailable", and the money-safety assertions below hold
 * either way because a busy AID is declined before any dispatch.
 * @returns {Promise<void>} Resolves once both states have been observed.
 */
async function theReportingTurnLocksTheScreen(): Promise<void> {
  const reported = deferred<BillPaymentPreview>();
  inquireAccountPayableBalanceMock.mockReturnValueOnce(reported.promise);

  renderBillPay();
  const entry = screen.getByLabelText(BILL_PAY_FIELD_LABELS.accountId);
  await userEvent.type(entry, ACCOUNT_ID);
  await userEvent.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toBeEnabled();
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toHaveAttribute('aria-busy', 'true');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).toBeDisabled();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).toBeDisabled();
  expect(entry).toBeDisabled();

  /*
   * WHY : Assumptions: the keys are PRESSED as well as asserted disabled, because the two reach the
   *       screen by different routes -- `usePfKeys` installs a document keydown listener that a
   *       disabled button never sees. A lock that greyed the legend and still dispatched the keystroke
   *       would pass the four assertions above and fail the operator: F3 would leave the screen and F4
   *       would blank the entry while the read was still on its way back.
   */
  await userEvent.keyboard('{F3}{F4}');
  expect(screen.queryByText(`${ARRIVED} ${MAIN_MENU_ROUTE}`)).toBeNull();
  expect(entry).toHaveValue(ACCOUNT_ID);
  expect(inquireAccountPayableBalanceMock).toHaveBeenCalledTimes(1);
  expect(payAccountBalanceConfirmedMock).not.toHaveBeenCalled();

  reported.release(PAYABLE_PREVIEW.preview);

  expect(await screen.findByTestId('billpay-confirm')).toBeEnabled();
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).toBeEnabled();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).toBeEnabled();
  expect(screen.getByLabelText(BILL_PAY_FIELD_LABELS.accountId)).toBeEnabled();
}

/**
 * Asserts the confirming turn locks the confirmation answer as well as the keys.
 *
 * Assumptions: this is the turn that MOVES MONEY, so the confirmation trigger is asserted alongside
 * the three keys. `app/cbl/COBIL00C.cbl` L210-L235 writes the ledger record and reduces the balance
 * inside one CICS task, and the terminal accepted nothing while it ran; a trigger left live would let
 * a second activation pay the same balance twice where the operator asked once.
 * @returns {Promise<void>} Resolves once the locked window and its release have been observed.
 */
async function theConfirmingTurnLocksTheAnswer(): Promise<void> {
  const paid = deferred<BillPaymentResponse>();
  inquireAccountPayableBalanceMock.mockResolvedValueOnce(PAYABLE_PREVIEW.preview);
  payAccountBalanceConfirmedMock.mockReturnValueOnce(paid.promise);

  renderBillPay();
  await userEvent.type(screen.getByLabelText(BILL_PAY_FIELD_LABELS.accountId), ACCOUNT_ID);
  await userEvent.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));

  await screen.findByTestId('billpay-confirm');

  /*
   * WHY : ⚠️ Refactoring Rationale: the confirming letter is TYPED into the field and the turn is then
   *       taken, where this case used to click a trigger and then the panel's affirmative. Both of
   *       those controls are gone, and the sequence they described is the defect: the screen armed a
   *       commit control and moved focus onto it, so the turn that paid could be reached without the
   *       operator ever supplying an answer. `app/cbl/COBIL00C.cbl` L173-L191 reads the answer out of
   *       its own `CONFIRM` field and performs the write on `'Y'`/`'y'` alone, which is what this now
   *       reproduces.
   */
  await userEvent.type(confirmationEntry(), CONFIRMING_ANSWER);
  await userEvent.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));

  expect(confirmationEntry()).toBeDisabled();
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toBeEnabled();
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toHaveAttribute('aria-busy', 'true');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).toBeDisabled();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).toBeDisabled();
  expect(screen.getByLabelText(BILL_PAY_FIELD_LABELS.accountId)).toBeDisabled();

  await userEvent.keyboard('{enter}{F3}{F4}');
  expect(screen.queryByText(`${ARRIVED} ${MAIN_MENU_ROUTE}`)).toBeNull();

  /*
   * WHY : Assumptions: ONE read and ONE payment, counted on their own spies rather than as two calls to
   *       a shared one. Separating them is what makes a second payment for one instruction visible as
   *       such -- that is the harm the lock exists to prevent and the only harm here the operator
   *       cannot reverse from the screen -- and it additionally pins that the paying turn did not
   *       re-read the balance.
   */
  expect(payAccountBalanceConfirmedMock).toHaveBeenCalledTimes(1);
  expect(inquireAccountPayableBalanceMock).toHaveBeenCalledTimes(1);

  paid.release(WRITTEN_PAYMENT.payment);

  expect(
    await screen.findByText(WRITTEN_PAYMENT.payment.transactionId, { exact: false }),
  ).toBeVisible();
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).toBeEnabled();
}

/**
 * Asserts the create turn locks all four registered keys, then releases them.
 *
 * Assumptions: all FOUR are asserted rather than the three that navigate or clear, because the fourth
 * is Enter and a lock that left Enter live would issue a second create for the row the first was
 * writing -- which the service answers `User ID already exist...` for a user the operator asked for
 * once.
 *
 * ⚠️ Refactoring Rationale: all four are now asserted enabled and `aria-busy` rather than disabled.
 * The screen moved every key onto the `busy` channel `ui/src/layout/usePfKeys.ts` added, keeping its
 * own pre-existing decision that the whole legend declines for the write window -- the 3270 keyboard
 * lock -- and changing only HOW that decline is expressed. Assumptions: the protection asserted below
 * is unchanged, because the hook declines a busy AID before any dispatch, so the keyboard probe still
 * reaches nothing; what changes is that the controls stay present, named and focusable while they
 * decline, so a screen reader is told a turn is outstanding instead of being told four controls
 * vanished. Trade-offs: `disabled` before `busy` is the hook's own precedence, so an entry cannot
 * carry both and this case cannot assert both on one control.
 * @returns {Promise<void>} Resolves once both states have been observed.
 */
async function theCreateTurnLocksEveryKey(): Promise<void> {
  const created = deferred<CreatedUserResponse>();
  createUserMock.mockReturnValueOnce(created.promise);

  renderUserAdd();
  await enterEveryUserValue();
  await userEvent.click(legendControl(USER_ADD_KEY_LABELS.ENTER));

  for (const label of [
    USER_ADD_KEY_LABELS.ENTER,
    USER_ADD_KEY_LABELS.PFK03,
    UNIFORM_PF_KEY_LABELS.PFK04,
    USER_ADD_KEY_LABELS.PFK12,
  ]) {
    expect(legendControl(label)).toBeEnabled();
    expect(legendControl(label)).toHaveAttribute('aria-busy', 'true');
  }

  await userEvent.keyboard('{F3}{F4}{F12}');
  expect(screen.queryByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeNull();
  expect(screen.queryByText(`${ARRIVED} ${SIGN_ON_ROUTE}`)).toBeNull();
  expect(screen.getByLabelText(USER_ADD_FIELD_LABELS.lastName)).toHaveValue(
    SUBMITTED_USER.lastName,
  );
  expect(createUserMock).toHaveBeenCalledTimes(1);

  created.release(CREATED_USER);

  expect(await screen.findByRole('button', { name: USER_ADD_KEY_LABELS.PFK03 })).toBeEnabled();
  expect(legendControl(USER_ADD_KEY_LABELS.ENTER)).toBeEnabled();
  expect(legendControl(USER_ADD_KEY_LABELS.PFK12)).toBeEnabled();
}

/**
 * Asserts user add paints `F12=Exit` and answers the key as an unaccepted one.
 *
 * Assumptions: BOTH halves are asserted because the mapset and the program disagree about this key and
 * both halves are observable. `app/bms/COUSR01.bms` L155-L159 paints the whole row-24 legend as one
 * 43-character `ATTRB=(ASKIP,NORM)` field that includes `F12=Exit`, so the caption is on the glass on
 * every turn; `app/cbl/COUSR01C.cbl` L90-L102 dispatches `DFHENTER`, `DFHPF3` and `DFHPF4` only, so
 * `DFHPF12` reaches `WHEN OTHER`, which paints `CCDA-MSG-INVALID-KEY` and moves `-1` into `FNAMEL`.
 * Withdrawing the caption or honouring the key would each lose one half, and the screen previously
 * honoured it -- signing the operator off on a key the reference refuses.
 * @returns {Promise<void>} Resolves once the sentence, the severity and the cursor are observed.
 */
async function theAdvertisedExitKeyIsRefused(): Promise<void> {
  renderUserAdd();
  const firstName = screen.getByLabelText(USER_ADD_FIELD_LABELS.firstName);
  await userEvent.type(firstName, 'ADA');
  expect(legendControl(USER_ADD_KEY_LABELS.PFK12)).toBeEnabled();

  await userEvent.keyboard('{F12}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(INVALID_KEY_PRESSED.trim());

  /*
   * WHY : Assumptions: the SEVERITY is asserted through the announced role rather than a class name,
   *       because `ui/src/layout/MessageBand.tsx` maps error to `role="alert"` and the two polite
   *       severities to `role="status"`. A refusal painted as an acknowledgement would keep the
   *       sentence and lose the urgency, and that is the difference this assertion can see.
   */
  expect(within(band).getByRole('alert')).toBeInTheDocument();

  expect(screen.queryByText(`${ARRIVED} ${SIGN_ON_ROUTE}`)).toBeNull();
  expect(screen.queryByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeNull();
  expect(firstName).toHaveFocus();
  expect(firstName).toHaveValue('ADA');
  expect(createUserMock).not.toHaveBeenCalled();
}

/** Discards both spies so one case's calls cannot be counted by the next. */
function resetTransports(): void {
  createUserMock.mockReset();
  inquireAccountPayableBalanceMock.mockReset();
  payAccountBalanceConfirmedMock.mockReset();
}

/** Registers the mutation-turn cases. */
function mutationTurnCases(): void {
  beforeEach(resetTransports);
  afterEach(resetTransports);

  it('locks the bill-payment screen for the reporting turn', theReportingTurnLocksTheScreen);
  it('locks the confirmation answer for the paying turn', theConfirmingTurnLocksTheAnswer);
  it('locks every registered key for the create turn', theCreateTurnLocksEveryKey);
  it('refuses the exit key its mapset advertises', theAdvertisedExitKeyIsRefused);
}

describe('a turn in flight accepts nothing', mutationTurnCases);
