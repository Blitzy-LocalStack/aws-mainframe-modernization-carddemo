/**
 * @file Proves the user browse positions on the SERVER, so an identifier sorting beyond the page on
 * display reaches the rows that follow it instead of emptying the table.
 *
 * Purpose
 * -------
 * `ui/src/screens/userList/index.tsx` replaces `app/cbl/COUSR00C.cbl`, whose search field is a browse
 * positioning key: `PROCESS-ENTER-KEY` moves `USRIDINI` into `SEC-USR-ID` at L221 and hands it to
 * `STARTBR ... RIDFLD(SEC-USR-ID)`, which opens the browse at or after that identifier. A single
 * terminal turn therefore reached any identifier in the file.
 *
 * What these cases exist to prevent
 * ---------------------------------
 * ⚠️ Assumptions: the positioning was previously applied by FILTERING the page the client already held,
 * which is a comparison over ten rows. An identifier sorting past the tenth stored row matched none of
 * them, so the operator was shown an empty table where the terminal positioned directly at the key --
 * the gentlest possible input producing the emptiest possible screen. The first case below is that exact
 * input, and it asserts the value reaches the request rather than a row count, because a client-side
 * filter would still satisfy any assertion made only about what is on the glass.
 *
 * Assumptions: the second case asserts the CONTINUATION, which is the half a positioning parameter can
 * break on its own. The service refuses a position and a cursor together, so a screen that re-sent the
 * applied key on every page turn would page a browse into a 400 on the first F8 -- and one that dropped
 * the key without keeping the page would silently restart at the top of the file.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these
 * cases assert what the screen SENDS. Mocking the client would make a request-shape regression
 * indistinguishable from an interceptor one. `USER_ID_MAX_LENGTH` is restated in the factory for the
 * reason the sibling identity-transport mocks record.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { listUsers } from '../../api/auth';
import type { PageResponse, UserSummary } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import UserListScreen, { USER_LIST_KEY_LABELS, USER_LIST_LABELS } from './index';

/**
 * Builds the mocked surface of the identity transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 *
 * Assumptions: `USER_ID_MAX_LENGTH` is restated rather than spread from the real module, because the
 * factory may not reach the module it replaces. Its value is the `05 SEC-USR-ID PIC X(08).` width at
 * `app/cpy/CSUSR01Y.cpy` L18, which the screen applies as the search control's `maxLength` -- an
 * undefined width would let a case type more than the terminal field accepted.
 * @returns {Record<string, unknown>} The listing operation as a spy, plus the width constant.
 */
function mockIdentityTransportModule(): Record<string, unknown> {
  return {
    USER_ID_MAX_LENGTH: 8,
    listUsers: vi.fn(),
  };
}

vi.mock('../../api/auth', mockIdentityTransportModule);

/** Rows the service holds beyond the opening page, which a client-side filter could never see. */
const ROWS_BEYOND_THE_OPENING_PAGE = ['USER0011', 'USER0012'] as const;

/** The identifier an operator types to reach them, which sorts after every row of the opening page. */
const KEY_BEYOND_THE_OPENING_PAGE = ROWS_BEYOND_THE_OPENING_PAGE[0];

/** The sealed cursor the positioned page carries, which the forward step must continue from. */
const POSITIONED_PAGE_LAST_KEY = 'sealed-last-key-of-the-positioned-page';

/**
 * Builds one browse row.
 * @param {string} userId - The row's stored identifier.
 * @returns {UserSummary} The row as the listing answers it.
 */
function row(userId: string): UserSummary {
  return { userId, firstName: 'FIRST', lastName: 'LAST', userType: 'U' };
}

/**
 * Builds one page envelope over the given identifiers.
 *
 * Assumptions: every member the envelope declares is supplied rather than the object being widened with
 * a cast, because the screen reads its paging affordances out of exactly these members -- a partial
 * stand-in would let a case pass against a member the service always sends.
 * @param {readonly string[]} userIds - The identifiers the page carries, in key order.
 * @param {boolean} hasNext - Whether the service found a row beyond this page.
 * @returns {PageResponse<UserSummary>} The page as the listing answers it.
 */
function pageOf(userIds: readonly string[], hasNext: boolean): PageResponse<UserSummary> {
  return {
    items: userIds.map(row),
    firstKey: userIds.length === 0 ? null : `sealed-first-${String(userIds[0])}`,
    lastKey: userIds.length === 0 ? null : POSITIONED_PAGE_LAST_KEY,
    hasNext,
  };
}

/** The ten rows the browse opens on, none of which the key below sorts at or before. */
const OPENING_PAGE = pageOf(
  [
    'USER0001',
    'USER0002',
    'USER0003',
    'USER0004',
    'USER0005',
    'USER0006',
    'USER0007',
    'USER0008',
    'USER0009',
    'USER0010',
  ],
  true,
);

/** The page the service answers a positioned read with, which no filter over the opening page holds. */
const POSITIONED_PAGE = pageOf(ROWS_BEYOND_THE_OPENING_PAGE, true);

/** The page a forward step from the positioned page answers with. */
const CONTINUED_PAGE = pageOf(['USER0013'], false);

/**
 * Mounts the browse inside the shell that owns its band and its key legend.
 *
 * Assumptions: the screen is rendered INSIDE `AppShell` for the reason the sibling screen tests record
 * -- it composes none of its title band, message line or key legend itself, so a bare render produces a
 * screen with no legend and every query for one fails on a screen that is in fact correct.
 * @returns {void} Nothing; the screen is rendered into the test document.
 */
function renderBrowse(): void {
  render(
    <MemoryRouter initialEntries={['/users']}>
      <AppShell>
        <UserListScreen />
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Waits until a given identifier has been painted into the table.
 * @param {string} userId - The identifier expected on the glass.
 * @returns {Promise<void>} Resolves once it is there.
 */
async function waitForRow(userId: string): Promise<void> {
  await waitFor(
    /**
     * Waits for one row to have landed.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByText(userId)).toBeInTheDocument();
    },
  );
}

/**
 * Types an identifier into the search control and applies it with Enter.
 * @param {ReturnType<typeof userEvent.setup>} operator - The interaction driver.
 * @param {string} value - The identifier to position on.
 * @returns {Promise<void>} Resolves once the applied read has been issued.
 */
async function applyPositioningKey(
  operator: ReturnType<typeof userEvent.setup>,
  value: string,
): Promise<void> {
  await operator.type(screen.getByLabelText(USER_LIST_LABELS.searchUserId), value);
  await operator.keyboard('{Enter}');
  await waitFor(
    /**
     * Waits for the positioned read to have been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(listUsers).toHaveBeenCalledTimes(2);
    },
  );
}

/**
 * Proves a key sorting beyond the opening page is SENT, and its rows rendered.
 *
 * ⚠️ Assumptions: the case asserts the request AND the rows, and needs both. Asserting the rows alone
 * would pass against a client that fetched everything and filtered locally; asserting the request alone
 * would pass against a screen that sent the key and then discarded the page it answered with -- which is
 * precisely the defect being fixed, one layer further on.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function sendsAKeyBeyondTheOpeningPageToTheService(): Promise<void> {
  vi.mocked(listUsers).mockResolvedValueOnce(OPENING_PAGE).mockResolvedValueOnce(POSITIONED_PAGE);
  const operator = userEvent.setup();
  renderBrowse();
  await waitForRow('USER0001');

  await applyPositioningKey(operator, KEY_BEYOND_THE_OPENING_PAGE);

  expect(listUsers).toHaveBeenNthCalledWith(1);
  expect(listUsers).toHaveBeenNthCalledWith(2, { startUserId: KEY_BEYOND_THE_OPENING_PAGE });
  await waitForRow(ROWS_BEYOND_THE_OPENING_PAGE[1]);
  expect(
    screen.queryByText('USER0001'),
    'a positioned page replaces the opening page rather than being drawn beside it',
  ).not.toBeInTheDocument();
}

/**
 * Proves the forward step continues from the positioned page's own cursor and drops the key.
 *
 * ⚠️ Assumptions: the asserted argument is the WHOLE query object, because the property under test is an
 * ABSENCE -- the applied key must not travel again. The service refuses a position and a cursor together,
 * naming both, so a screen that re-sent the key would turn the first F8 after a search into a 400.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function continuesFromThePositionedPageWithoutResendingTheKey(): Promise<void> {
  vi.mocked(listUsers)
    .mockResolvedValueOnce(OPENING_PAGE)
    .mockResolvedValueOnce(POSITIONED_PAGE)
    .mockResolvedValueOnce(CONTINUED_PAGE);
  const operator = userEvent.setup();
  renderBrowse();
  await waitForRow('USER0001');
  await applyPositioningKey(operator, KEY_BEYOND_THE_OPENING_PAGE);
  await waitForRow(ROWS_BEYOND_THE_OPENING_PAGE[1]);

  await operator.click(screen.getByRole('button', { name: USER_LIST_KEY_LABELS.PFK08 }));

  await waitFor(
    /**
     * Waits for the forward step to have been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(listUsers).toHaveBeenCalledTimes(3);
    },
  );
  expect(listUsers).toHaveBeenNthCalledWith(3, {
    cursor: POSITIONED_PAGE_LAST_KEY,
    direction: 'next',
  });
  await waitForRow('USER0013');
}

/**
 * Proves clearing the search control and applying it reopens the browse at the start of the set.
 *
 * Assumptions: the reference reaches this read by leaving its search field empty -- L218 tests
 * `USRIDINI` for spaces or low values and L219 seeds the seek with `LOW-VALUES`, the start of the file --
 * so the request must carry NO position rather than an empty one. This is the case that pins the applied
 * key being dropped from the request when it is dropped from the control, which a screen holding the
 * previous key in a closure would fail.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reopensAtTheStartOfTheSetWhenTheKeyIsCleared(): Promise<void> {
  vi.mocked(listUsers)
    .mockResolvedValueOnce(OPENING_PAGE)
    .mockResolvedValueOnce(POSITIONED_PAGE)
    .mockResolvedValueOnce(OPENING_PAGE);
  const operator = userEvent.setup();
  renderBrowse();
  await waitForRow('USER0001');
  await applyPositioningKey(operator, KEY_BEYOND_THE_OPENING_PAGE);
  await waitForRow(ROWS_BEYOND_THE_OPENING_PAGE[1]);

  /*
   * Assumptions: the control is already empty at this point -- the screen clears it once a key has been
   * applied, transcribing L231-L233 -- so applying again is a bare Enter with nothing marked, which is
   * the turn that reopens the browse unpositioned.
   */
  await operator.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the reopened read to have been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(listUsers).toHaveBeenCalledTimes(3);
    },
  );
  expect(listUsers).toHaveBeenNthCalledWith(3);
  await waitForRow('USER0001');
}

/** Restores the spy between cases. */
function resetSpies(): void {
  vi.mocked(listUsers).mockReset();
}

/** Registers the server-side positioning cases. */
function positioningCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it(
    'sends a key beyond the opening page to the service',
    sendsAKeyBeyondTheOpeningPageToTheService,
  );
  it(
    'continues from the positioned page without resending the key',
    continuesFromThePositionedPageWithoutResendingTheKey,
  );
  it(
    'reopens at the start of the set when the key is cleared',
    reopensAtTheStartOfTheSetWhenTheKeyIsCleared,
  );
}

describe('the user browse positions on the server', positioningCases);
