/**
 * @file Component tests for the reference type-list screen's filter edit and its exit arm.
 *
 * Purpose
 * -------
 * Cover the two findings a review raised on this screen:
 *
 * - the type-code filter must require EXACTLY two digits. The reference's field is two characters
 *   wide with no `NUM` attribute, so a single typed digit reaches its edit as a digit and a blank and
 *   fails `IS NOT NUMERIC`; the published contract agrees, declaring `minLength: 2`, `maxLength: 2`
 *   and `pattern: '^[0-9]{2}$'`. The screen admitted any run of digits, so a one-digit entry was
 *   dispatched and refused by the service with a generic validation problem instead of the
 *   reference's own sentence.
 * - the exit arm must paint no message. The reference's PF3 arm sets none, and the sentence the
 *   screen was painting is set at exactly one site in the whole program -- inside the PF2 add arm.
 *
 * Why the exit case is a regression guard and not a proof
 * -----------------------------------------------------
 * Assumptions: the exit case asserts that PF3 still reaches the administrative menu and that the
 * sentence appears nowhere. It does NOT prove the removal, and saying so matters more than the
 * assertion does. The band assignment and the transition were issued from one handler, so React
 * batched them and the screen unmounted before any render could paint the band -- the sentence was
 * unobservable in a browser BEFORE the fix as well as after it, which was measured by restoring the
 * assignment and watching this case stay green. The defect it closes is therefore one of source
 * fidelity: a dead assignment that attributed to PF3 a sentence the reference sets on PF2 and, as the
 * handler's own note now records, never displays at all. What this case guards is the half that IS
 * observable -- that removing the assignment did not disturb the transition.
 *
 * Why the screen is mounted inside the shell
 * -----------------------------------------
 * Assumptions: `ui/src/layout/AppShell.tsx` is the authenticated layout route and this screen
 * delegates its title band and its key legend to it, so rendering the screen alone would leave the
 * legend buttons absent -- and the legend is where the exit key is pressed.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ReferenceModule from '../../api/reference';
import type { PageResponse, ReferenceListQuery, TransactionType } from '../../api/types';

const listTransactionTypesMock = vi.fn();

vi.mock(
  '../../api/reference',
  /**
   * Replaces the browse operation while leaving every other export intact.
   *
   * Assumptions: only the LIST operation is substituted. The two write operations are reached solely
   * through a confirmation this file never gives, so stubbing them would stand in for calls no case
   * makes; leaving them real keeps the substitution the smallest thing that answers the question.
   * @returns {Promise<typeof ReferenceModule>} The real module with the browse stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof ReferenceModule>('../../api/reference');
    return {
      ...actual,
      /**
       * Stands in for the keyset browse of transaction types.
       * @returns {Promise<unknown>} Whatever the case configured.
       */
      listTransactionTypes: listTransactionTypesMock,
    };
  },
);

// Assumptions: the screen and the two layout modules are imported DYNAMICALLY, below the
//   substitution, because a static import is hoisted above the factory and the factory closes over
//   the spy above. That ordering was measured failing on a sibling screen's test with
//   `Cannot access ... before initialization`, and the same shape is used here rather than
//   rediscovering it.
const {
  default: RefTypeListScreen,
  REF_TYPE_LIST_KEY_LABELS,
  validateTypeFilter,
} = await import('./index');
const { AppShell } = await import('../../layout/AppShell');
const { PF_KEY_BAR_REGION_LABEL } = await import('../../layout/PfKeyBar');
const { MESSAGE_BAND_TEST_ID } = await import('../../layout/MessageBand');
const { PROGRAM_MESSAGES } = await import('../../messages/messages');
const { STATUS_MESSAGES } = await import('../../messages/messages');

/** The verbatim refusal the reference moves at `COTRTLIC.cbl` L1115-L1117. */
const TWO_DIGIT_REFUSAL =
  PROGRAM_MESSAGES.COTRTLIC.TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER;

/** The sentence the reference declares at L251-L252 and this screen must never paint. */
const EXIT_SENTENCE = STATUS_MESSAGES.COTRTLIC.WS_EXIT_MESSAGE.text;

/** Route the screen occupies, matching the router's own entry for it. */
const LIST_ROUTE = '/reference/transaction-types';

/** Text the administrative-menu route renders, which is how an exit is observed. */
const ADMIN_SENTINEL_TEXT = 'administrative menu reached';

/** Identifier the screen assigns its type-filter control, stated once here as it states it. */
const TYPE_FILTER_INPUT_ID = 'ref-type-list-type-filter';

/** One page of two rows, enough for the grid to render without paging. */
const FIRST_PAGE: PageResponse<TransactionType> = {
  items: [
    { typeCd: '01', description: 'PURCHASE', version: 1 },
    { typeCd: '02', description: 'PAYMENT', version: 1 },
  ],
  firstKey: '01',
  lastKey: '02',
  hasNext: false,
};

/**
 * Stands in for the administrative menu so a transfer of control is observable.
 * @returns {ReactElement} A paragraph carrying the sentinel text.
 */
function AdminSentinel(): ReactElement {
  return <p>{ADMIN_SENTINEL_TEXT}</p>;
}

/**
 * Renders the screen inside the shell at its own route.
 * @returns {void} Completion is the mounted tree.
 */
function renderScreen(): void {
  render(
    <MemoryRouter initialEntries={[LIST_ROUTE]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path={LIST_ROUTE} element={<RefTypeListScreen />} />
          <Route path="/admin" element={<AdminSentinel />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Drains the microtask queue inside `act` so every state change a response caused is committed.
 * @returns {Promise<void>} Resolves once the queue is empty.
 */
async function settle(): Promise<void> {
  await act(
    /**
     * Yields once so pending promise continuations run.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Returns the type-filter control.
 *
 * Assumptions: located by its own DOM identifier rather than by label, because the screen assigns
 * one explicitly and ties the prompt to it with `aria-labelledby` -- so the identifier is the stable
 * handle and the label is a rendered node that the prompt shares with the grid heading.
 * @returns {HTMLInputElement} The control.
 * @throws {Error} When no control carries that identifier, which is a rename this test must report.
 */
function typeFilter(): HTMLInputElement {
  const element = document.getElementById(TYPE_FILTER_INPUT_ID);
  if (element === null) {
    throw new Error(`No control is rendered with the identifier ${TYPE_FILTER_INPUT_ID}.`);
  }
  return element as HTMLInputElement;
}

/**
 * Returns the message band this screen paints above its grid.
 *
 * Assumptions: sentences are asserted INSIDE the band rather than anywhere on the screen, because a
 * refused filter renders its sentence twice -- once in the band and once as the control's help text
 * -- so an unscoped query finds two nodes and throws before it can assert anything.
 * @returns {HTMLElement} The band element.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns one legend button by its label.
 * @param {string} label - The button's legend text.
 * @returns {HTMLElement} That button.
 */
function keyButton(label: string): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return within(legend).getByRole('button', { name: label });
}

/**
 * Mounts the screen and completes its opening browse.
 * @returns {Promise<void>} Resolves once the first page is on screen.
 */
async function renderWithFirstPage(): Promise<void> {
  listTransactionTypesMock.mockResolvedValue(FIRST_PAGE);
  renderScreen();
  await settle();
  await settle();
}

/**
 * Types a filter entry and dispatches the reference's ENTER arm.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @param {string} entry - The filter entry to type.
 * @returns {Promise<void>} Resolves once the turn has been dispatched and settled.
 */
async function submitFilter(
  user: ReturnType<typeof userEvent.setup>,
  entry: string,
): Promise<void> {
  await user.click(typeFilter());
  await user.type(typeFilter(), entry);
  await user.keyboard('{Enter}');
  await settle();
}

/**
 * The exported edit accepts blank and exactly two digits, and refuses everything else.
 *
 * Assumptions: the table is asserted as a whole rather than case by case, because the property under
 * test is the SHAPE of the accepted set and a single example cannot express it. `'1'` is the entry
 * the defect admitted; `'00'` is admitted deliberately and means "no narrowing" once the service
 * collapses it; `'123'` cannot be typed through the control's `maxLength` but is asserted anyway,
 * because this function is exported and a caller that is not the control can reach it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function acceptsOnlyBlankOrTwoDigits(): Promise<void> {
  expect(validateTypeFilter('')).toBeNull();
  expect(validateTypeFilter('   ')).toBeNull();
  expect(validateTypeFilter('01')).toBeNull();
  expect(validateTypeFilter('00')).toBeNull();
  expect(validateTypeFilter('99')).toBeNull();
  expect(validateTypeFilter('1')).toBe(TWO_DIGIT_REFUSAL);
  expect(validateTypeFilter('9')).toBe(TWO_DIGIT_REFUSAL);
  expect(validateTypeFilter('123')).toBe(TWO_DIGIT_REFUSAL);
  expect(validateTypeFilter('a1')).toBe(TWO_DIGIT_REFUSAL);
  expect(validateTypeFilter('1 ')).toBe(TWO_DIGIT_REFUSAL);
  await Promise.resolve();
}

/**
 * A one-digit filter is refused on the screen and never dispatched.
 *
 * Assumptions: the dispatch assertion is the one that fails under the defect, and it is expressed as
 * "no call carries the entry" rather than "no call was made", because the opening browse has already
 * called the transport once with no filter. Asserting the sentence alone would not distinguish the
 * defect either: the service would eventually refuse the request too, and a case that waited for a
 * message could be satisfied by a round trip the reference never makes.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAOneDigitFilterWithoutDispatchingIt(): Promise<void> {
  const user = userEvent.setup();
  await renderWithFirstPage();
  const callsBefore = listTransactionTypesMock.mock.calls.length;

  await submitFilter(user, '1');

  expect(within(messageBand()).getByText(TWO_DIGIT_REFUSAL)).toBeInTheDocument();
  expect(listTransactionTypesMock.mock.calls.length).toBe(callsBefore);
}

/**
 * A two-digit filter is accepted and narrows the browse.
 *
 * Assumptions: the query the transport received is inspected rather than the grid, because the grid
 * renders whatever the stubbed page contains and would look the same for an unnarrowed read. The
 * `typeCode` member is what carries the narrowing to the service.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function acceptsATwoDigitFilterAndNarrowsTheBrowse(): Promise<void> {
  const user = userEvent.setup();
  await renderWithFirstPage();

  await submitFilter(user, '01');
  await settle();

  expect(within(messageBand()).queryByText(TWO_DIGIT_REFUSAL)).toBeNull();
  const narrowed = listTransactionTypesMock.mock.calls
    .map(
      /**
       * Reads the query from one recorded call.
       * @param {unknown[]} call - The arguments the transport was called with.
       * @returns {ReferenceListQuery} That call's query.
       */
      (call: unknown[]): ReferenceListQuery => call[0] as ReferenceListQuery,
    )
    .filter(
      /**
       * Reports whether a query carried the typed narrowing.
       * @param {ReferenceListQuery} query - One recorded query.
       * @returns {boolean} `true` when it narrowed on the typed code.
       */
      (query: ReferenceListQuery): boolean => query.typeCode === '01',
    );
  expect(narrowed.length).toBeGreaterThan(0);
}

/**
 * The exit key reaches the administrative menu and leaves no sentence behind.
 *
 * Assumptions: this is a REGRESSION GUARD on the transition, not a proof of the message removal --
 * see the file header for the measurement behind that distinction. The sentence assertion is kept
 * because it costs nothing and because it would catch a future revision that painted the sentence
 * somewhere a render could reach, which is the only form of the defect a browser could show.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function exitsToTheAdministrativeMenuWithoutASentence(): Promise<void> {
  const user = userEvent.setup();
  await renderWithFirstPage();

  await user.click(keyButton(REF_TYPE_LIST_KEY_LABELS.PFK03));
  await settle();

  expect(screen.getByText(ADMIN_SENTINEL_TEXT)).toBeInTheDocument();
  expect(screen.queryByText(EXIT_SENTENCE)).toBeNull();
}

/**
 * Registers every case, and resets the spy between them.
 * @returns {void} Nothing; the registrations are the effect.
 */
function refTypeListCases(): void {
  beforeEach(
    /**
     * Clears any response a previous case queued.
     * @returns {void} Nothing.
     */
    (): void => {
      listTransactionTypesMock.mockReset();
    },
  );
  afterEach(
    /**
     * Clears any spy state a case installed.
     * @returns {void} Nothing.
     */
    (): void => {
      vi.restoreAllMocks();
    },
  );

  it('accepts only blank or exactly two digits', acceptsOnlyBlankOrTwoDigits);
  it(
    'refuses a one-digit filter without dispatching it',
    refusesAOneDigitFilterWithoutDispatchingIt,
  );
  it(
    'accepts a two-digit filter and narrows the browse',
    acceptsATwoDigitFilterAndNarrowsTheBrowse,
  );
  it(
    'exits to the administrative menu without a sentence',
    exitsToTheAdministrativeMenuWithoutASentence,
  );
}

describe('reference type list screen', refTypeListCases);
