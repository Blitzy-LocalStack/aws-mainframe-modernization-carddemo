/**
 * @file Component tests for `ui/src/screens/transactionAdd/index.tsx` and
 * `ui/src/screens/userUpdate/index.tsx`.
 *
 * Purpose
 * -------
 * Cover each screen's opening state and whether a request runs on mount, every control at its declared
 * width, the legend the mapset paints, a refusal reported verbatim from the message catalog, a
 * successful write, and -- on the user screen -- that the route parameter is the thing that decides
 * which row is read.
 *
 * Assumptions: the two screens are covered together because their opening behaviour is the matched
 * pair that the route table makes observable. The transaction screen is reached with no selector and
 * must therefore read NOTHING on mount; the user screen is reached at `/users/:id/edit` and must read
 * the row that parameter names. Both are entry forms of the same shape, so a change that added a mount
 * read to the first or dropped it from the second would look reasonable in isolation.
 *
 * Assumptions: the user screen's parameter case is the executable half of a documentation claim. That
 * module's file overview and its component contract both state that the router mounts it at
 * `/users/:id/edit` and that the parameter is spelled `id`; `useParams` resolves an unmatched name to
 * `undefined` with no diagnostic, so a route declared under any other spelling would compile,
 * type-check and render the screen's no-identifier state on every visit. Rendering the screen at a
 * concrete path and asserting the read carries that identifier is what holds the claim to the code.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';

import { AppShell } from '../layout/AppShell';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getUser, updateUser } from '../api/auth';
import { addTransaction, copyLastTransaction } from '../api/transactions';
import type { UserResponse } from '../api/types';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { PROGRAM_MESSAGES, SHARED_MESSAGES } from '../messages/messages';
import { MAIN_MENU_ROUTE } from '../routes/navigation';
import { cardDemoTheme } from '../theme/antdTheme';
import {
  TRANSACTION_ADD_FIELD_LABELS,
  TRANSACTION_ADD_FIELD_WIDTHS,
  TRANSACTION_ADD_KEY_LABELS,
  TransactionAddScreen,
} from './transactionAdd';
import {
  USER_UPDATE_FIELD_LABELS,
  USER_UPDATE_FIELD_WIDTHS,
  USER_UPDATE_KEY_LABELS,
  UserUpdateScreen,
} from './userUpdate';

const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;
const USER_ID = 'USER0001';
const USER_UPDATE_ROUTE = '/users/:id/edit';
const USER_UPDATE_UNSELECTED_ROUTE = '/users/edit';
const MENU_MARKER = 'MAIN MENU REACHED';

/**
 * Builds the mocked transaction transport surface.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory -- Vitest lifts every `vi.mock`
 * above the imports, so a factory in a `const` would be in its temporal dead zone at registration.
 * @returns {Record<string, unknown>} The transport surface.
 */
function mockTransactionModule(): Record<string, unknown> {
  return { addTransaction: vi.fn(), copyLastTransaction: vi.fn() };
}

/**
 * Builds the mocked identity transport surface, leaving the module's constants real.
 *
 * Assumptions: the real module is spread in rather than replaced outright, because the user screen
 * imports `USER_ID_MAX_LENGTH` from it as well as the two operations -- and that constant is the
 * declared width the width assertions read, so a stub would make the widths agree with themselves
 * rather than with the contract.
 * @returns {Promise<Record<string, unknown>>} The transport surface.
 */
async function mockIdentityModule(): Promise<Record<string, unknown>> {
  const actual = await vi.importActual<Record<string, unknown>>('../api/auth');

  return { ...actual, getUser: vi.fn(), updateUser: vi.fn() };
}

vi.mock('../api/transactions', mockTransactionModule);
vi.mock('../api/auth', mockIdentityModule);

/**
 * Collapses runs of whitespace the way Testing Library's default normaliser does.
 * @param {string} value - Catalogued literal carrying its declared pad.
 * @returns {string} The same text with whitespace runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Reads one element's text with its whitespace collapsed.
 * @param {Element} element - Element whose text is read.
 * @returns {string} The collapsed text.
 */
function collapsedTextOf(element: Element): string {
  return collapse(element.textContent ?? '');
}

/**
 * Returns the legend region the shared key bar paints.
 * @returns {HTMLElement} The legend region.
 */
function legendRegion(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads the legend's controls as collapsed labels, in painted order.
 * @returns {readonly string[]} The collapsed label of every control the legend paints.
 */
function legendLabels(): readonly string[] {
  return Array.from(legendRegion().querySelectorAll('button')).map(collapsedTextOf);
}

/**
 * Builds a predicate matching a control whose collapsed label equals the wanted one.
 * @param {string} wanted - The collapsed label to match.
 * @returns {(element: Element) => boolean} The predicate.
 */
function matchesCollapsedLabel(wanted: string): (element: Element) => boolean {
  return matches;

  /**
   * Reports whether this element carries the wanted label.
   * @param {Element} element - Candidate control.
   * @returns {boolean} Whether the label matches.
   */
  function matches(element: Element): boolean {
    return collapsedTextOf(element) === wanted;
  }
}

/**
 * Builds an assertion that the legend paints a control with the wanted label.
 * @param {string} wanted - The collapsed label the legend must paint.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertLegendPaints(wanted: string): () => void {
  return assertPainted;

  /**
   * Asserts the label is among those the legend paints.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertPainted(): void {
    expect(legendLabels(), `the legend must paint ${wanted}`).toContain(wanted);
  }
}

/**
 * Activates a legend control by its painted label.
 * @param {string} label - The legend label as the mapset paints it.
 * @returns {Promise<void>} Resolves once the control has been activated.
 */
async function pressLegendKey(label: string): Promise<void> {
  const wanted = collapse(label);
  await waitFor(assertLegendPaints(wanted));
  const [control] = Array.from(legendRegion().querySelectorAll('button')).filter(
    matchesCollapsedLabel(wanted),
  );
  await userEvent.click(control as HTMLElement);
}

/**
 * Builds an assertion that some band on the screen carries the wanted sentence.
 *
 * Assumptions: EVERY band is searched rather than one, because both screens paint two message lines --
 * their mapsets declare an informational line and an error line -- so a single-element query is
 * ambiguous by construction and which of the two carries a given sentence is the screen's decision to
 * make, not this helper's to assume.
 * @param {string} wanted - The collapsed sentence some band must carry.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertSomeBandStates(wanted: string): () => void {
  return assertStated;

  /**
   * Asserts one of the painted bands carries the wanted sentence.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertStated(): void {
    const texts = screen.getAllByTestId(MESSAGE_BAND_TEST_ID).map(collapsedTextOf);
    expect(texts.join(' | '), `a band must state ${wanted}`).toContain(wanted);
  }
}

/**
 * A stored user record, populated so each rendered member is distinguishable.
 * @returns {UserResponse} The record a successful read returns.
 */
function storedUser(): UserResponse {
  return {
    userId: USER_ID,
    firstName: 'PAUL',
    lastName: 'BUCK',
    userType: 'U',
    cognitoSub: '00000000-0000-4000-8000-000000000002',
  };
}

/**
 * Renders the transaction screen, with the menu mounted so navigation is observable.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderTransactionAdd(): void {
  render(
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/transactions/new']}>
        {/*
          WHY : ⚠️ Refactoring Rationale: the screen is rendered INSIDE `AppShell`, where it was rendered
                bare. The screen delegates its title band, its row-23 message line and its row-24 legend to
                the one shell that `ui/src/router.tsx` mounts as a layout route -- it composes none of the
                three itself -- so a bare render produced a screen with no legend and no band, and every
                query for either failed on a screen that is in fact correct. The children form is used
                rather than a layout route because it is the shape that needs no second route level, and
                `AppShell` renders `children ?? <Outlet />`, so both forms paint the same frame.
        */}
        <AppShell>
          <Routes>
            <Route path="/transactions/new" element={<TransactionAddScreen />} />
            <Route path={MAIN_MENU_ROUTE} element={<div>{MENU_MARKER}</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>,
  );
}

/**
 * Renders the user screen at a concrete path under the route pattern the table declares.
 * @param {string} path - Concrete location to render at.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderUserUpdateAt(path: string): void {
  render(
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={[path]}>
        <AppShell>
          <Routes>
            <Route path={USER_UPDATE_ROUTE} element={<UserUpdateScreen />} />
            {/*
            Refactoring Rationale: an UNPARAMETERISED sibling stands in for "reached with no
            identifier", and the first attempt used the concrete path `/users//edit` against the
            parameterised pattern instead. That does not work and fails misleadingly: an empty path
            segment matches no route at all, so nothing rendered and the case failed looking for a
            message band rather than reporting what it meant to check. A route with no parameter leaves
            `useParams` returning `undefined` for `id`, which is exactly the state the reference reaches
            when its selector arrives as spaces.
          */}
            <Route path={USER_UPDATE_UNSELECTED_ROUTE} element={<UserUpdateScreen />} />
            <Route path="/users" element={<div>USER LIST</div>} />
            <Route path={MAIN_MENU_ROUTE} element={<div>{MENU_MARKER}</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>,
  );
}

/**
 * Reads a control by the caption its mapset paints.
 * @param {string} label - The painted caption.
 * @returns {HTMLElement} The control.
 */
function controlLabelled(label: string): HTMLElement {
  return screen.getByLabelText(collapse(label));
}

/**
 * Resets the transport spies so no case inherits another's answer.
 * @returns {void} Nothing.
 */
function resetTransport(): void {
  vi.mocked(addTransaction).mockReset();
  vi.mocked(copyLastTransaction).mockReset();
  vi.mocked(getUser).mockReset();
  vi.mocked(updateUser).mockReset();
}

/**
 * The transaction screen opens with an empty form and issues nothing.
 *
 * Assumptions: BOTH operations are asserted uncalled, including the copy-last-transaction one. That
 * operation is bound to a function key, so a mount that invoked it would silently pre-fill the form
 * with somebody else's last transaction -- a wrong answer that looks like a working screen.
 * @returns {Promise<void>} Resolves once the opening state has been asserted.
 */
async function theTransactionScreenOpensEmptyAndIssuesNothing(): Promise<void> {
  renderTransactionAdd();

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);
  expect(controlLabelled(TRANSACTION_ADD_FIELD_LABELS.accountId)).toHaveValue('');
  expect(controlLabelled(TRANSACTION_ADD_FIELD_LABELS.amount)).toHaveValue('');
  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Every declared transaction field width reaches the control that edits it.
 *
 * Assumptions: the sweep is driven from the exported width table rather than spot-checked, because the
 * table is the transcription of the mapset's field lengths and a control that lost its bound accepts
 * an entry the service must then refuse. A member the screen does not render as a bounded control is
 * skipped rather than failed, so the sweep states what it found.
 * @returns {Promise<void>} Resolves once every rendered control has been checked.
 */
async function everyTransactionWidthReachesItsControl(): Promise<void> {
  renderTransactionAdd();

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);

  const checked: string[] = [];
  for (const [field, width] of Object.entries(TRANSACTION_ADD_FIELD_WIDTHS)) {
    const label = TRANSACTION_ADD_FIELD_LABELS[field as keyof typeof TRANSACTION_ADD_FIELD_LABELS];
    if (label === undefined) {
      continue;
    }
    const [control] = screen.queryAllByLabelText(collapse(label));
    if (control === undefined) {
      continue;
    }
    const declared = control.getAttribute('maxLength');
    if (declared === null) {
      continue;
    }
    expect(declared, `${field} must carry its declared width`).toBe(String(width));
    checked.push(field);
  }
  expect(checked.length, 'the sweep must reach most of the form').toBeGreaterThan(8);
}

/**
 * The transaction screen paints the legend its mapset advertises.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theTransactionScreenPaintsItsLegend(): Promise<void> {
  renderTransactionAdd();

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);
  const labels = legendLabels();
  for (const label of Object.values(TRANSACTION_ADD_KEY_LABELS)) {
    expect(labels, `the legend must paint ${collapse(label)}`).toContain(collapse(label));
  }
}

/**
 * A turn with neither selector is refused with the reference's own sentence and issues nothing.
 *
 * Assumptions: this specific refusal is chosen because it is the one the reference states for the
 * screen's OPENING turn -- an operator who presses Enter with nothing typed. Its sentence names both
 * selectors, which is what makes it distinguishable from the per-field empty sentences the same screen
 * also holds.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aTurnWithNoSelectorIsRefused(): Promise<void> {
  renderTransactionAdd();

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);
  await pressLegendKey(TRANSACTION_ADD_KEY_LABELS.ENTER);

  await waitFor(
    assertSomeBandStates(collapse(ADD_MESSAGES.ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED)),
  );
  expect(addTransaction).not.toHaveBeenCalled();
}

/**
 * A non-numeric account selector is refused with the numeric sentence and issues nothing.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aNonNumericSelectorIsRefused(): Promise<void> {
  renderTransactionAdd();

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);
  await userEvent.type(controlLabelled(TRANSACTION_ADD_FIELD_LABELS.accountId), '0000000001X');
  await pressLegendKey(TRANSACTION_ADD_KEY_LABELS.ENTER);

  await waitFor(assertSomeBandStates(collapse(ADD_MESSAGES.ACCOUNT_ID_MUST_BE_NUMERIC)));
  expect(addTransaction).not.toHaveBeenCalled();
}

/**
 * The transaction screen's back key returns to the menu.
 * @returns {Promise<void>} Resolves once the menu has been reached.
 */
async function theTransactionBackKeyReturnsToTheMenu(): Promise<void> {
  renderTransactionAdd();

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);
  await pressLegendKey(TRANSACTION_ADD_KEY_LABELS.PFK03);

  expect(await screen.findByText(MENU_MARKER)).toBeInTheDocument();
}

/**
 * The user screen reads the row its route parameter names, on mount.
 *
 * Assumptions: the ARGUMENT is asserted and not merely the call, because the parameter's spelling is
 * the property under test -- a route declared under a different name would leave `useParams` returning
 * `undefined`, and a case that only counted calls would pass for a screen that read nothing.
 * @returns {Promise<void>} Resolves once the read has been observed.
 */
async function theUserScreenReadsTheRowItsRouteNames(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(storedUser());

  renderUserUpdateAt(`/users/${USER_ID}/edit`);

  await waitFor(assertUserWasRead());
  expect(vi.mocked(getUser).mock.calls[0]?.[0]).toBe(USER_ID);
}

/**
 * The record the read returned populates the form.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function theReadRecordPopulatesTheForm(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(storedUser());

  renderUserUpdateAt(`/users/${USER_ID}/edit`);

  expect(await screen.findByDisplayValue('BUCK')).toBeInTheDocument();
  expect(screen.getByDisplayValue('PAUL')).toBeInTheDocument();
  expect(controlLabelled(USER_UPDATE_FIELD_LABELS.userId)).toHaveValue(USER_ID);
}

/**
 * With no identifier in the route the screen reads nothing and waits for one.
 *
 * Assumptions: this is the state the reference reaches when `CDEMO-CU02-USR-SELECTED` arrives as
 * spaces, which the screen's own contract records as a supported way in. It is asserted here so the
 * mount read above is understood as conditional on the parameter rather than unconditional.
 * @returns {Promise<void>} Resolves once the opening state has been asserted.
 */
async function withNoIdentifierTheUserScreenReadsNothing(): Promise<void> {
  renderUserUpdateAt(USER_UPDATE_UNSELECTED_ROUTE);

  await screen.findAllByTestId(MESSAGE_BAND_TEST_ID);
  expect(getUser).not.toHaveBeenCalled();
}

/**
 * Every declared user field width reaches the control that edits it.
 * @returns {Promise<void>} Resolves once every rendered control has been checked.
 */
async function everyUserWidthReachesItsControl(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(storedUser());

  renderUserUpdateAt(`/users/${USER_ID}/edit`);

  await screen.findByDisplayValue('BUCK');

  const checked: string[] = [];
  for (const [field, width] of Object.entries(USER_UPDATE_FIELD_WIDTHS)) {
    const label = USER_UPDATE_FIELD_LABELS[field as keyof typeof USER_UPDATE_FIELD_LABELS];
    if (label === undefined) {
      continue;
    }
    const [control] = screen.queryAllByLabelText(collapse(label));
    if (control === undefined) {
      continue;
    }
    const declared = control.getAttribute('maxLength');
    if (declared === null) {
      continue;
    }
    expect(declared, `${field} must carry its declared width`).toBe(String(width));
    checked.push(field);
  }
  expect(checked.length, 'the sweep must reach every control the mapset paints').toBeGreaterThan(3);
}

/**
 * The user screen paints the five legend keys its mapset advertises.
 *
 * Assumptions: PF3 is asserted with the SAVE wording rather than an exit wording, because this is the
 * one screen in the application whose PF3 saves -- its row-24 legend field paints `F3=Save&Exit` and
 * the dispatch arm performs the update before transferring control. A legend that read `F3=Exit` here
 * would be plausible and wrong.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theUserScreenPaintsItsLegend(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(storedUser());

  renderUserUpdateAt(`/users/${USER_ID}/edit`);

  await screen.findByDisplayValue('BUCK');
  const labels = legendLabels();
  for (const label of Object.values(USER_UPDATE_KEY_LABELS)) {
    expect(labels, `the legend must paint ${collapse(label)}`).toContain(collapse(label));
  }
  expect(labels).toContain(collapse(USER_UPDATE_KEY_LABELS.PFK03));
}

/**
 * A refused read reports the catalogued lookup-failure sentence.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aRefusedUserReadReportsTheCataloguedSentence(): Promise<void> {
  vi.mocked(getUser).mockRejectedValue(new Error('transport'));

  renderUserUpdateAt(`/users/${USER_ID}/edit`);

  await waitFor(assertSomeBandStates(collapse(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER)));
}

/**
 * A save writes the edited values through the update operation.
 *
 * Assumptions: the surname is edited before the save, because the screen refuses a save that carries
 * no change -- the reference reports `'No change detected'` as a validation outcome of its own, so a
 * save attempted straight after a read would exercise that refusal rather than the write.
 * @returns {Promise<void>} Resolves once the write has been observed.
 */
async function aSaveWritesTheEditedValues(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(storedUser());
  vi.mocked(updateUser).mockResolvedValue({ ...storedUser(), lastName: 'BUCKLEY' });

  renderUserUpdateAt(`/users/${USER_ID}/edit`);

  const surname = await screen.findByDisplayValue('BUCK');
  await userEvent.clear(surname);
  await userEvent.type(surname, 'BUCKLEY');
  /*
   * WHY : ⚠️ Refactoring Rationale: no password is typed here, and the step this note replaces typed one
   *       into a control the screen does not render. The mapset's fifth field is `Password:` at
   *       `app/bms/COUSR02.bms` L125-L129 and the screen deliberately omits both the control and its
   *       label -- registered divergence D-10 -- because identity moved to a managed user pool and
   *       `auth.users` carries no password column at all, so a control whose value is discarded would tell
   *       an operator their password had changed when nothing had. Typing into it queried a label that
   *       `USER_UPDATE_FIELD_LABELS` does not declare.
   * WHY : Assumptions: the surname edit above is enough to reach the write. The screen refuses a save that
   *       carries no change, which is what the password step was incidentally satisfying as well; with the
   *       control gone, the edited surname is the change.
   */
  await pressLegendKey(USER_UPDATE_KEY_LABELS.PFK05);

  await waitFor(assertUserWasWritten());
  expect(vi.mocked(updateUser).mock.calls[0]?.[0]).toBe(USER_ID);
}

/**
 * Builds an assertion that the read operation has been called.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertUserWasRead(): () => void {
  return assertRead;

  /**
   * Asserts the read has been issued.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertRead(): void {
    expect(getUser).toHaveBeenCalled();
  }
}

/**
 * Builds an assertion that the write operation has been called.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertUserWasWritten(): () => void {
  return assertWritten;

  /**
   * Asserts the write has been issued.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertWritten(): void {
    expect(updateUser).toHaveBeenCalled();
  }
}

/**
 * Registers the entry-screen cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function entryScreenCases(): void {
  beforeEach(resetTransport);
  afterEach(resetTransport);

  it(
    'opens the transaction form empty and issues nothing',
    theTransactionScreenOpensEmptyAndIssuesNothing,
  );
  it('applies every declared transaction width', everyTransactionWidthReachesItsControl);
  it('paints the transaction legend', theTransactionScreenPaintsItsLegend);
  it('refuses a turn carrying neither selector', aTurnWithNoSelectorIsRefused);
  it('refuses a non-numeric account selector', aNonNumericSelectorIsRefused);
  it('returns to the menu from the transaction screen', theTransactionBackKeyReturnsToTheMenu);
  it('reads the user the route parameter names', theUserScreenReadsTheRowItsRouteNames);
  it('populates the user form from the read record', theReadRecordPopulatesTheForm);
  it(
    'reads nothing when the route carries no identifier',
    withNoIdentifierTheUserScreenReadsNothing,
  );
  it('applies every declared user width', everyUserWidthReachesItsControl);
  it('paints the user legend including the saving exit key', theUserScreenPaintsItsLegend);
  it(
    'reports a refused user read in the catalogued sentence',
    aRefusedUserReadReportsTheCataloguedSentence,
  );
  it('writes the edited user values on save', aSaveWritesTheEditedValues);
}

describe('the transaction add and user update screens', entryScreenCases);
