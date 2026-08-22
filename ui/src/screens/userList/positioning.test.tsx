/**
 * @file Proves the user browse positions the WHOLE browse on the server, not the page it already holds.
 *
 * Purpose
 * -------
 * `app/cbl/COUSR00C.cbl` moves the typed identifier into `SEC-USR-ID` and issues ONE
 * `STARTBR ... RIDFLD(SEC-USR-ID)` at L588-L595, whose greater-or-equal positioning opens the browse at
 * or after that key anywhere in the file; a blank entry is seeded with `LOW-VALUES` at L219 and opens at
 * the start. The cases below hold this screen to that behaviour through the `startUserId` parameter of
 * `GET /api/v1/auth/users`.
 *
 * ⚠️ Refactoring Rationale: these cases exist because the shipped screen filtered the page it already
 * held, and every visible expectation of that arrangement passed. A page carries ten rows, so filtering
 * one can only narrow ten rows to ten or fewer -- an identifier on any later page narrowed the table to
 * NOTHING while the operator's key was reported as honoured, and an operator could only reach it by
 * pressing F8 until it appeared. The two properties that distinguish the correct behaviour from that one
 * are both about what leaves the browser: the typed identifier must appear in the REQUEST, and a row the
 * server chose to return must reach the glass unfiltered. Neither is observable from the rows alone,
 * which is why the transport is asserted here as well as the table.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it` and `waitFor` is a NAMED
 * declaration, which is the shape `ui/src/screens/userAdd/credentialHandover.test.tsx` records:
 * `ui/eslint.config.js` selects a function expression in every position, so an inline callback needs its
 * own JSDoc block, and Prettier moves a block comment that follows an argument comma onto the preceding
 * string literal, which detaches the block from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { listUsers } from '../../api/auth';
import type { PageResponse, UserSummary } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import UserListScreen, {
  USER_LIST_KEY_LABELS,
  USER_LIST_LABELS,
  USER_LIST_PAGE_SIZE,
} from './index';

/**
 * Builds the mocked surface of the identity transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 *
 * Assumptions: `USER_ID_MAX_LENGTH` is restated rather than spread from the real module, because the
 * factory may not reach the module it replaces. Its value is the `05 SEC-USR-ID PIC X(08).` width at
 * `app/cpy/CSUSR01Y.cpy` L18, which the screen applies as the search control's `maxLength`.
 * @returns {Record<string, unknown>} The listing operation as a spy, plus the width constant.
 */
function mockIdentityTransportModule(): Record<string, unknown> {
  return {
    USER_ID_MAX_LENGTH: 8,
    listUsers: vi.fn(),
  };
}

/*
 * WHY : Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these
 *       cases assert the QUERY the screen composes and the rows it paints, both properties of the screen.
 *       Mocking the client would leave the client's own mutual-exclusion guard between the assertion and
 *       the thing asserted, so a screen that sent a cursor alongside the position would fail as a
 *       transport error rather than as the contradiction it is -- and that guard has its own cases in
 *       `ui/src/api/auth.test.ts`.
 */
vi.mock('../../api/auth', mockIdentityTransportModule);

/**
 * The identifier a case types, chosen to sit BEYOND the first page.
 *
 * Assumptions: its ordinal is one past the page size, so it cannot appear on the opening page under any
 * correct implementation. That is what makes the single-request assertion meaningful: a screen that
 * filtered the page it held would answer this key with an empty table.
 */
const TARGET_BEYOND_PAGE_ONE = 'USER0012';

/**
 * Builds one browse row from its ordinal, zero-padded so lexical order matches numeric order.
 *
 * Assumptions: the padding matters rather than being cosmetic. The key column orders lexically, so an
 * unpadded tenth identifier would sort between the first and the second and every assertion about a page
 * boundary would then be checking an order the fixture did not have.
 * @param {number} ordinal - The one-based position of the row within the fixture population.
 * @returns {UserSummary} The row, with the four members the browse publishes and no credential.
 */
function rowAt(ordinal: number): UserSummary {
  return {
    userId: `USER${String(ordinal).padStart(4, '0')}`,
    firstName: `FIRST${ordinal}`,
    lastName: `LAST${ordinal}`,
    userType: ordinal % 2 === 0 ? 'U' : 'A',
  };
}

/**
 * Builds a page envelope over a run of ordinals.
 *
 * Assumptions: the envelope's cursors are composed from the first and last rows returned, which is what
 * the service does, so a case that pages onward from this fixture is paging from a value the real service
 * would have issued rather than from an invented one.
 * @param {number} fromOrdinal - The first ordinal the page carries.
 * @param {number} count - How many rows the page carries.
 * @param {boolean} hasNext - Whether the service reported a further page beyond this one.
 * @returns {PageResponse<UserSummary>} The envelope as the listing operation resolves it.
 */
function pageOf(fromOrdinal: number, count: number, hasNext: boolean): PageResponse<UserSummary> {
  const items = Array.from(
    { length: count },
    /**
     * Builds the row that sits at one offset into this page.
     * @param {undefined} _unused - The element `Array.from` supplies for a length-only source; unread.
     * @param {number} index - The row's offset into the page, counted from zero.
     * @returns {UserSummary} The row for the ordinal that offset lands on.
     */
    (_unused: undefined, index: number): UserSummary => rowAt(fromOrdinal + index),
  );
  return {
    items,
    firstKey: items.length === 0 ? null : `cursor-first-${items[0]?.userId ?? ''}`,
    lastKey: items.length === 0 ? null : `cursor-last-${items[items.length - 1]?.userId ?? ''}`,
    hasNext,
  };
}

/** The opening page: ten rows with a further page reported, as the reference's probe read settles it. */
const OPENING_PAGE = pageOf(1, USER_LIST_PAGE_SIZE, true);

/** The page the target's position opens: the target row and the one after it. */
const POSITIONED_PAGE = pageOf(12, 2, false);

/**
 * Mounts the browse inside the shell that owns its title band, message line and key legend.
 *
 * Assumptions: the screen is rendered INSIDE `AppShell`, not bare. It composes none of those three itself
 * -- `ui/src/router.tsx` mounts the shell as a layout route -- so a bare render produces a screen with no
 * legend, and every query for a function key fails on a screen that is in fact correct.
 * @returns {void} Nothing; the cases query the rendered document.
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
 * Waits until a stated identifier is on the glass.
 * @param {string} userId - The identifier expected in the table.
 * @returns {Promise<void>} Resolves once the row is rendered.
 */
async function waitForRow(userId: string): Promise<void> {
  await waitFor(
    /**
     * Waits until the row has reached the document.
     * @returns {void} Nothing; throws until the identifier is rendered.
     */
    () => {
      expect(screen.getByText(userId)).toBeInTheDocument();
    },
  );
}

/**
 * Types an identifier into the search control and presses the key that applies it.
 *
 * Assumptions: the control is reached by its label rather than by a role or a test identifier, because
 * the label is the mapset's own `Search User ID:` literal and a query through it also proves the control
 * still carries an accessible name -- which the 3270 original's keyboard-only workflow makes a fidelity
 * requirement rather than a nicety.
 * @param {string} identifier - The value to type, or the empty string to clear the control.
 * @returns {Promise<void>} Resolves once the apply key has been pressed.
 */
async function typeAndApply(identifier: string): Promise<void> {
  const control = screen.getByLabelText(USER_LIST_LABELS.searchUserId);
  await userEvent.clear(control);
  if (identifier !== '') {
    await userEvent.type(control, identifier);
  }
  await userEvent.click(screen.getByRole('button', { name: USER_LIST_KEY_LABELS.ENTER }));
}

/**
 * Restores the module mock between cases so one case's answers cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetIdentityMocks(): void {
  vi.mocked(listUsers).mockReset();
}

/**
 * Answers the opening read with the first page and the positioned read with the target's page.
 *
 * ⚠️ Assumptions: the stub DISCRIMINATES on the query rather than answering every call the same way.
 * That is the whole mechanism of these cases: a screen that never sent the position would receive the
 * opening page for its second read too, and an assertion on the rows alone would then be indistinguishable
 * from one on a screen that sent it. Answering different pages to different queries makes the rows
 * themselves evidence about the request.
 * @returns {void} Nothing; the spy is configured in place.
 */
function stubPositionedListing(): void {
  vi.mocked(listUsers).mockImplementation(
    /**
     * Answers one listing call according to the query it carries.
     * @param {object} [query] - The query the screen composed, absent on an opening read.
     * @param {string} [query.startUserId] - The identifier the browse is to open at or after.
     * @returns {Promise<PageResponse<UserSummary>>} The page that query selects.
     */
    (query?: { startUserId?: string }): Promise<PageResponse<UserSummary>> =>
      Promise.resolve(
        query?.startUserId === TARGET_BEYOND_PAGE_ONE ? POSITIONED_PAGE : OPENING_PAGE,
      ),
  );
}

/**
 * Asserts a target beyond the first page is reached by ONE request carrying it as the position.
 *
 * ⚠️ Assumptions: three things are asserted together and the case needs all three. The request must
 * carry the typed identifier as `startUserId`; the target row must be on the glass; and the request COUNT
 * must be exactly two -- the opening read and the positioned one. The count is what refuses the other
 * broken implementation: paging forward until the key appears would answer with the same rows while
 * turning one keystroke into a number of round trips that grows with the file.
 *
 * Assumptions: the opening page is asserted NOT to contain the target before the key is applied, so the
 * case proves the target is genuinely beyond page one rather than assuming the fixture's arithmetic.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function reachesATargetBeyondPageOneInOneRequest(): Promise<void> {
  stubPositionedListing();
  renderBrowse();
  await waitForRow('USER0001');

  expect(screen.queryByText(TARGET_BEYOND_PAGE_ONE)).not.toBeInTheDocument();

  await typeAndApply(TARGET_BEYOND_PAGE_ONE);
  await waitForRow(TARGET_BEYOND_PAGE_ONE);

  expect(vi.mocked(listUsers)).toHaveBeenCalledTimes(2);
  expect(vi.mocked(listUsers)).toHaveBeenLastCalledWith({ startUserId: TARGET_BEYOND_PAGE_ONE });
  expect(screen.getByText('USER0013')).toBeInTheDocument();
  expect(screen.queryByText('USER0001')).not.toBeInTheDocument();
}

/**
 * Asserts the applied position travels ALONE, with no cursor and no direction beside it.
 *
 * ⚠️ Assumptions: the query's key set is asserted as an equality rather than by checking that `startUserId`
 * is present. A cursor continues the browse the caller holds and a position reopens it elsewhere, so a
 * request carrying both states two incompatible intentions -- the service answers 400 keyed on the
 * position and `ui/src/api/auth.ts` refuses the pair before sending it. A presence check would pass on a
 * screen that sent both and would only fail later, as a transport error at a call site.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function sendsThePositionWithNoCursorBesideIt(): Promise<void> {
  stubPositionedListing();
  renderBrowse();
  await waitForRow('USER0001');

  await typeAndApply(TARGET_BEYOND_PAGE_ONE);
  await waitForRow(TARGET_BEYOND_PAGE_ONE);

  const query = vi.mocked(listUsers).mock.calls[1]?.[0];
  expect(Object.keys(query ?? {})).toEqual(['startUserId']);
}

/**
 * Asserts a blank entry reopens the browse at the start of the file.
 *
 * Assumptions: the request is asserted to carry NO position at all rather than an empty one, because the
 * reference expresses "open at the start" by seeding its key field with `LOW-VALUES` at
 * `app/cbl/COUSR00C.cbl` L219 -- an absence of a key rather than a blank key. An empty member would put a
 * parameter on the wire carrying no instruction, and would make the client's mutual-exclusion guard
 * refuse an ordinary page turn.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function aBlankEntryReopensAtTheStart(): Promise<void> {
  stubPositionedListing();
  renderBrowse();
  await waitForRow('USER0001');

  await typeAndApply(TARGET_BEYOND_PAGE_ONE);
  await waitForRow(TARGET_BEYOND_PAGE_ONE);

  await typeAndApply('');
  await waitForRow('USER0001');

  expect(vi.mocked(listUsers)).toHaveBeenLastCalledWith();
}

/**
 * Asserts the delivered page is painted as delivered, with no key applied to it a second time.
 *
 * ⚠️ Purpose: this is the direct refusal of the withdrawn behaviour, and it is deliberately constructed
 * so that only the withdrawn behaviour fails it. The stub answers the positioned request with a row that
 * sorts BEFORE the typed key -- which a real service is entitled to do, since the position is the
 * service's to interpret and a row can be renamed between two reads -- and a screen that re-applied the
 * key to what arrived would hide it. Nothing else about the screen distinguishes the two arrangements
 * once the request is correct, which is why this case exists beside the request assertions above.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function paintsThePageAsDelivered(): Promise<void> {
  vi.mocked(listUsers).mockImplementation(
    /**
     * Answers the positioned read with a page whose first row sorts below the position.
     * @param {object} [query] - The query the screen composed, absent on an opening read.
     * @param {string} [query.startUserId] - The identifier the browse is to open at or after.
     * @returns {Promise<PageResponse<UserSummary>>} The page that query selects.
     */
    (query?: { startUserId?: string }): Promise<PageResponse<UserSummary>> =>
      Promise.resolve(
        query?.startUserId === TARGET_BEYOND_PAGE_ONE ? pageOf(11, 3, false) : OPENING_PAGE,
      ),
  );
  renderBrowse();
  await waitForRow('USER0001');

  await typeAndApply(TARGET_BEYOND_PAGE_ONE);
  await waitForRow(TARGET_BEYOND_PAGE_ONE);

  expect(screen.getByText('USER0011')).toBeInTheDocument();
  expect(screen.getByText('USER0013')).toBeInTheDocument();
}

/**
 * Asserts paging forward from a positioned page carries the cursor alone.
 *
 * ⚠️ Assumptions: the anchor established by the position must be inheritable by the paging keys, or the
 * migrated screen would be narrower than the reference -- whose `READNEXT` continues the browse its
 * single `STARTBR` opened. The position is asserted ABSENT from the forward request, because the cursor
 * the positioned page issued already encodes where the walk resumed and sending both would be the
 * contradiction the case above refuses.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function pagesForwardFromTheNewAnchorWithTheCursorAlone(): Promise<void> {
  vi.mocked(listUsers).mockImplementation(
    /**
     * Answers the opening read, the positioned read and the forward step from it.
     * @param {object} [query] - The query the screen composed, absent on an opening read.
     * @param {string} [query.startUserId] - The identifier the browse is to open at or after.
     * @param {string} [query.cursor] - The sealed cursor a continuation replays.
     * @returns {Promise<PageResponse<UserSummary>>} The page that query selects.
     */
    (query?: { startUserId?: string; cursor?: string }): Promise<PageResponse<UserSummary>> => {
      if (query?.cursor !== undefined) {
        return Promise.resolve(pageOf(22, 1, false));
      }
      return Promise.resolve(
        query?.startUserId === TARGET_BEYOND_PAGE_ONE
          ? pageOf(12, USER_LIST_PAGE_SIZE, true)
          : OPENING_PAGE,
      );
    },
  );
  renderBrowse();
  await waitForRow('USER0001');

  await typeAndApply(TARGET_BEYOND_PAGE_ONE);
  await waitForRow(TARGET_BEYOND_PAGE_ONE);

  await userEvent.click(screen.getByRole('button', { name: USER_LIST_KEY_LABELS.PFK08 }));
  await waitForRow('USER0022');

  const forwardQuery = vi.mocked(listUsers).mock.calls[2]?.[0];
  expect(forwardQuery?.cursor).toBeDefined();
  expect(forwardQuery).not.toHaveProperty('startUserId');
}

/**
 * Registers the browse-positioning cases and the reset that keeps them independent.
 *
 * Assumptions: this is a NAMED function passed to `describe` rather than an inline arrow, which is the
 * convention the sibling suite beside it follows. The documentation gate requires a block on every
 * function including a suite body, and a named declaration is the only position a block can occupy
 * without sitting inside an argument list.
 *
 * ⚠️ Assumptions: the mock reset runs `afterEach` rather than `beforeEach`. Each case asserts on the
 * ORDER and ARITY of the calls the screen made, so a case must open with an empty call record; clearing
 * on the way out gives that to the first case as well as to the rest, where clearing on the way in would
 * leave whatever the module-level setup performed visible to the first one.
 * @returns {void} Nothing; the cases are registered as a side effect of the call.
 */
function positioningCases(): void {
  afterEach(resetIdentityMocks);

  it('reaches a target beyond page one in one request', reachesATargetBeyondPageOneInOneRequest);
  it('sends the position with no cursor beside it', sendsThePositionWithNoCursorBesideIt);
  it('reopens at the start of the file for a blank entry', aBlankEntryReopensAtTheStart);
  it('paints the delivered page as delivered', paintsThePageAsDelivered);
  it(
    'pages forward from the new anchor with the cursor alone',
    pagesForwardFromTheNewAnchorWithTheCursorAlone,
  );
}

describe('the user browse positions the whole browse on the server', positioningCases);
