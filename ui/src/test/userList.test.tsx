/**
 * @file Component tests for the user browse screen, `ui/src/screens/userList/index.tsx`, which is the
 * migration target of BMS mapset `app/bms/COUSR00.bms` (map `COUSR0A`, 89 `DFHMDF` fields), its
 * symbolic map `app/cpy-bms/COUSR00.CPY` and program `app/cbl/COUSR00C.cbl`, mounted at route
 * `/users`.
 *
 * Purpose
 * -------
 * Hold the migrated screen to the reference screen's measured contracts: the copybook field widths,
 * the ten-row page arity, keyset rather than offset paging, the five DISTINCT paging sentences, the
 * two-letter action-code domain, the four-key workflow, the administrative gate, and the deliberate
 * absence of the baseline's plaintext credential.
 *
 * ⚠️ Why this file carries so much of the weight
 * ----------------------------------------------
 * These cases are the ONLY verification this screen gets. `tests/README.md` section 1.1 states
 * verbatim: "**Online `CO*` CICS programs** cannot run end-to-end without a CICS runtime (absent on
 * the runner); only their extractable field-validation logic is unit-tested." The COBOL suite is the
 * parity oracle for the BATCH pipeline, so there is no golden master for `COUSR00C` and no
 * higher-level comparison behind these assertions to catch what they miss. Every number and every
 * string below is therefore cited to the baseline line it was measured from, so a reader can check
 * the assertion against the source rather than against this file's own opinion.
 *
 * Module contract, in the terms Rule 1 asks of a function
 * -------------------------------------------------------
 * Parameters: none. A test module is an entry point the runner invokes with no arguments; its inputs
 * are the modules it imports and the fixtures declared below.
 * Returns: nothing. Registration is the effect -- each `describe` body registers cases that the
 * runner executes and whose expectations are the observable result.
 * Exceptions: none are raised deliberately. A failed expectation throws from within a case, which the
 * runner reports as that case's failure; a throw at module scope would be a broken import rather than
 * a test outcome.
 *
 * Documentation obligation
 * ------------------------
 * Rule 1 (Explainability) is the ONLY user-specified rule on this project. It requires a docstring on
 * every function, class and module entry point stating purpose, parameters, returns and exceptions,
 * and requires each non-obvious decision to record at least one of Alternatives Considered,
 * Refactoring Rationale, Assumptions or Trade-offs -- never a restatement of what the code does.
 * `tests/README.md` section 12 imposes the identical obligation on "every new test, fixture builder,
 * helper, mock, and runner routine" and calls it "a hard review gate". The two agree completely, so
 * this file extends an established house convention rather than introducing one, and it follows the
 * polyglot convention recorded in `docs/CODE_DOCUMENTATION_STANDARD.md`.
 *
 * ⚠️ Refactoring Rationale: the runner's test globals are IMPORTED from `vitest` rather than relied on
 * ambiently, and the deviation from `globals: true` is deliberate and measured. `ui/vitest.config.ts`
 * does set `globals: true`, so the identifiers exist at run time -- but `ui/tsconfig.json` sets
 * `"types": []`, which loads no ambient runner declarations at all. A probe file using the bare
 * globals produced four compile errors under this project's own configuration (TS2593 for `describe`
 * and `it`, TS2304 for `vi` and `expect`) while the tree was otherwise clean, so the ambient form
 * cannot pass `tsc --noEmit -p tsconfig.json` -- a required step in `.github/workflows/ui-ci.yml` and
 * a gate this file must clear. Every one of the roughly eighty existing test modules in this package
 * imports these names for the same reason, so importing them is also the house form. Alternatives
 * Considered: adding a runner entry to `types` in `ui/tsconfig.json`. Rejected because that file is a
 * package-wide build contract this file merely consumes, and widening it to satisfy one module would
 * change how every module in the package type-checks.
 *
 * Assumptions: no request reaches a network. `msw` is absent from this package, so the identity
 * transport is replaced with `vi.mock` and the listing operation observed as a spy. No endpoint, host
 * or credential appears anywhere below.
 *
 * Refactoring Rationale: every case is a NAMED function declaration registered by reference rather
 * than an inline arrow, which is the idiom the sibling screen tests established for two mechanical
 * reasons -- `jsdoc/require-jsdoc` demands a documentation block on a function expression in any
 * position, including a `describe` or `it` callback, and Prettier detaches a block comment that
 * follows an argument comma, which separates a rationale from the case it explains.
 */

import { screen, waitFor, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { theme } from 'antd';
import type { ReactElement } from 'react';
import { Route, Routes, useParams } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { USER_ID_MAX_LENGTH, listUsers } from '../api/auth';
import { retainOutcomeAcrossNavigation } from '../api/client';
import type { FieldError, PageResponse, UserSummary } from '../api/types';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_CONTENT_WIDTH, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import { PRIMARY_ACTION_AIDS, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  PROGRAM_SOURCE_FILES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  formatMessageTemplate,
  messageBandWidthForMapset,
} from '../messages/messages';
/*
 * WHY : ⚠️ Assumptions: the administrative gate is imported from the ROUTE GUARD and not looked for on
 *       the screen, because the screen deliberately does not hold one -- its own module overview
 *       records that it never imports `useAuth`, so that one authorization decision has a single
 *       implementation. `ui/src/router.tsx`, which this file also imports, gates `/users` by wrapping
 *       the administrative subtree in exactly this component at its L162, so the symbol arrives here
 *       through the same door the application opens it with rather than through a second one invented
 *       for a test.
 */
import { RequireAdmin } from '../routes/guards';
import { USER_UPDATE_ROUTE_TEMPLATE } from '../routes/navigation';
import { ROUTE_TABLE, USER_DELETE_PATH, USER_LIST_PATH, USER_UPDATE_PATH } from '../router';
import UserListScreen, {
  USER_LIST_ACTION_CELL_LENGTH,
  USER_LIST_ACTION_CELL_RESERVED_COLUMNS,
  USER_LIST_KEY_LABELS,
  USER_LIST_LABELS,
  USER_LIST_MAPSET,
  USER_LIST_PAGE_SIZE,
  USER_LIST_PROGRAM_NAME,
  USER_LIST_ROW_ACTION_CODES,
  USER_LIST_TRANSACTION_ID,
  reduceUserRowSelection,
  toUserListRowActionCode,
  userDeletePath,
  userEditPath,
  userListTableMeasure,
} from '../screens/userList';
import { FIELD_ERROR_TOKENS, TARGET_SIZE_AA_MINIMUM } from '../theme/tokens';
import {
  LEADING_CURSOR,
  TRAILING_CURSOR,
  expectMaxLength,
  expectVerbatimMessage,
  pageResponse,
  pressPfKey,
  renderInAppShell,
  renderWithProviders,
  seedSession,
} from './setup';

/**
 * Replaces the identity transport, keeping every operation real except the listing.
 *
 * ⚠️ Assumptions: this is a PARTIAL mock and the partiality is load-bearing rather than tidy.
 * `ui/src/hooks/useAuth.ts` imports the sign-on operations from this very module at its L77, so
 * replacing the module wholesale would leave `seedSession` driving a sign-on against a spy that
 * resolves nothing -- and the administrative cases below, which need a real signed session carrying a
 * real group claim, would then be asserting against a session that was never established. Spreading
 * the original keeps sign-on real and makes only the operation under observation a spy.
 *
 * Alternatives Considered: mocking the HTTP client beneath this module instead. Rejected because
 * these cases assert what the SCREEN sends -- a cursor, a direction, an opening position -- and
 * mocking the client would make a request-shape regression indistinguishable from an interceptor one.
 * Alternatives Considered: a factory that restates the module's constants, which is what the sibling
 * `browsePositioning` test does. Rejected here for the reason above: that test needs no session, so
 * it can afford to lose sign-on, and these cases cannot.
 * @param {<T>() => Promise<T>} importOriginal - Loader the runner supplies for the real module.
 * @returns {Promise<Record<string, unknown>>} The real module with `listUsers` replaced by a spy.
 */
async function mockIdentityTransport(
  importOriginal: <T>() => Promise<T>,
): Promise<Record<string, unknown>> {
  const actual = await importOriginal<Record<string, unknown>>();
  return { ...actual, listUsers: vi.fn() };
}

vi.mock('../api/auth', mockIdentityTransport);

/**
 * Identifiers of one FULL page, which is ten rows.
 *
 * Assumptions: ten, and the arity is measured twice over rather than chosen. `app/cbl/COUSR00C.cbl`
 * runs `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10` at L293 and again at L347, and
 * `app/cpy-bms/COUSR00.CPY` declares exactly ten action cells, `SEL0001I` at L72 through `SEL0010I`
 * at L342. A fixture of any other length would silently stop testing the page the screen paints.
 */
const FULL_PAGE_USER_IDS = [
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
] as const;

/** Identifier of the first row of a full page, which a backward step must return to. */
const FIRST_ROW_USER_ID = FULL_PAGE_USER_IDS[0];

/** Identifier of the tenth and last row, which must be present when a full page renders. */
const LAST_ROW_USER_ID = FULL_PAGE_USER_IDS[FULL_PAGE_USER_IDS.length - 1] ?? FIRST_ROW_USER_ID;

/**
 * An identifier the service never returns, used to prove no eleventh row is painted.
 *
 * Assumptions: it is the ELEVENTH in sequence rather than an arbitrary string, so a screen that
 * rendered one row too many would be caught by a query for the row it would actually have drawn.
 */
const ELEVENTH_USER_ID = 'USER0011';

/** Identifiers of a short page, proving the table paints what it is given rather than ten slots. */
const SHORT_PAGE_USER_IDS = ['USER0021', 'USER0022', 'USER0023'] as const;

/**
 * Builds one browse row in the four-member shape the listing answers with.
 *
 * ⚠️ Assumptions: FOUR members and no fifth, because `ui/src/api/types.ts` declares `UserSummary`
 * with exactly `userId`, `firstName`, `lastName` and `userType` at L1460 to L1465. There is
 * deliberately no credential member to supply: the baseline record carries `SEC-USR-PWD PIC X(08)`,
 * an eight-character PLAINTEXT password, at `app/cpy/CSUSR01Y.cpy` L21, and AAP sections 0.5.1.2 and
 * 0.7.8 decline parity with it -- the `auth.users` table has no such column at all. A fixture that
 * invented one would let a leak pass unnoticed on the one screen where a credential column would be
 * least likely to be spotted.
 *
 * Assumptions: `SEC-USR-FILLER PIC X(23)` at L23 is likewise absent. It is padding to the
 * eighty-byte record rather than data, which transformation rule T1 drops and records.
 * @param {string} userId - The row's stored identifier, the `SEC-USR-ID PIC X(08)` key.
 * @returns {UserSummary} The row exactly as the listing operation answers it.
 */
function row(userId: string): UserSummary {
  return { userId, firstName: 'FIRST', lastName: 'LAST', userType: 'U' };
}

/**
 * Builds one page envelope over the given identifiers.
 *
 * Assumptions: the envelope is built by the shared `pageResponse` helper rather than by hand, because
 * that helper is where the four-member shape is enforced and where the cursor tokens come from. Its
 * own documentation records why a fifth member is not merely unnecessary but wrong.
 * @param {readonly string[]} userIds - Identifiers the page carries, in key order.
 * @param {boolean} hasNext - Whether the service found a row beyond this page.
 * @returns {PageResponse<UserSummary>} The page as the listing answers it.
 */
function pageOf(userIds: readonly string[], hasNext: boolean): PageResponse<UserSummary> {
  return pageResponse(userIds.map(row), { hasNext });
}

/**
 * Builds the ten-row opening page, reporting a further page beyond it.
 * @returns {PageResponse<UserSummary>} A full page whose `hasNext` is set.
 */
function fullPageWithMore(): PageResponse<UserSummary> {
  return pageOf(FULL_PAGE_USER_IDS, true);
}

/**
 * Builds a page that is the last of the set.
 * @returns {PageResponse<UserSummary>} A full page whose `hasNext` is clear.
 */
function fullPageAtTheEnd(): PageResponse<UserSummary> {
  return pageOf(FULL_PAGE_USER_IDS, false);
}

/** The spy standing in for the listing operation, typed through the runner's own helper. */
const listing = vi.mocked(listUsers);

/**
 * Renders the browse inside the real application shell at its own route.
 *
 * ⚠️ Assumptions: the shell is REQUIRED rather than convenient. The screen delegates its header, its
 * row-23 message band and its row-24 key legend to `AppShell` through `useShellSlot` and renders none
 * of the three itself, so a bare render would leave every message and every key assertion below with
 * nothing to query. `renderInAppShell` mounts the subject through the shell's `<Outlet />`, which is
 * how the router composes it.
 * @returns {Promise<UserEvent>} The keyboard and pointer operator bound to the rendered document.
 */
async function renderBrowse(): Promise<UserEvent> {
  const { user } = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  return user;
}

/**
 * Reports whether an element or any ancestor of it refuses pointer input.
 *
 * Purpose: answer the one question that decides whether a control can be typed into or clicked, which
 * is not a property of the control alone -- an overlay applied several levels above it suppresses
 * pointer input for everything inside.
 *
 * Assumptions: the tree is WALKED rather than the property read once on the element, and the reason is
 * that this mirrors exactly what `@testing-library/user-event` does before every pointer interaction
 * (`assertPointerEvents` consults the nearest `pointer-events` declaration by walking ancestors). CSS
 * inheritance would make a single read sufficient in a browser; jsdom resolves inherited properties
 * only partially, which is why the library walks and why this walks with it.
 * @param {HTMLElement} element - The control whose interactivity is in question.
 * @returns {boolean} True when this element or an ancestor declares `pointer-events: none`.
 */
function pointerInputRefused(element: HTMLElement): boolean {
  for (let node: Element | null = element; node !== null; node = node.parentElement) {
    if (window.getComputedStyle(node).pointerEvents === 'none') {
      return true;
    }
  }
  return false;
}

/*
 * WHY : ⚠️ Refactoring Rationale: this now waits for the row to be INTERACTIVE and not merely present,
 *       because presence alone turned out not to imply interactivity on this browse. A five-file run
 *       under load failed `leaves unmarked rows without an error` with `Unable to perform pointer
 *       interaction as the element has pointer-events: none` on `INPUT#user-list-action-USER0001`,
 *       while the same case passed in isolation -- the signature of a real window being observed only
 *       when the machine is slow enough to land inside it.
 * WHY : ⚠️ Assumptions: the window is genuine and belongs to the SUBJECT, not to the harness, so it is
 *       waited out rather than papered over. `ui/src/hooks/usePagedQuery.ts` `browse-started` keeps the
 *       rows on display and raises `isLoading`, which the screen hands to `Table loading` -- so during
 *       any read after the first the ten rows are painted AND antd's `Spin` has applied its blur, whose
 *       rule sets `pointer-events: none` over the table body. A row is therefore readable before it is
 *       clickable, exactly as it is in a browser, and a case that typed in between was asserting
 *       against a frame the operator would also have been unable to type into.
 * WHY : Alternatives Considered: waiting on the read COUNT instead, through {@link waitForReadCount}.
 *       Rejected because the count says a request was issued, not that its settlement has been painted,
 *       so it would answer a different question and leave the same gap. Also considered: querying
 *       antd's spinner element directly; rejected because it would tie every case in this file to a
 *       library class name, where the computed property is the thing that actually blocks the
 *       interaction and is what the interaction library itself consults.
 * WHY : Trade-offs: folding this into the shared wait strengthens all of this file's cases rather than
 *       the one that failed, at the cost of a second expectation per poll. That is the right side of
 *       the trade here: every case that types into or clicks a row goes through this helper, so fixing
 *       the one observed instance and leaving the other thirty-odd to fail later would be repairing a
 *       symptom. No case in this file holds a read open deliberately, so nothing depends on observing
 *       the browse mid-read.
 */

/**
 * Waits until one row's identifier has been painted and its row will accept pointer input.
 * @param {string} userId - The identifier to wait for.
 * @returns {Promise<void>} Resolves once the row is in the document and interactive.
 */
async function waitForRow(userId: string): Promise<void> {
  await waitFor(
    /**
     * Asserts the row has landed and is no longer covered by a loading treatment.
     * @returns {void} Nothing; the expectations throw until they hold.
     */
    (): void => {
      expect(screen.getByText(userId)).toBeInTheDocument();
      expect(
        pointerInputRefused(actionCell(userId)),
        'a row is only ready to be acted on once the read covering it has settled',
      ).toBe(false);
    },
  );
}

/**
 * Waits until the listing has been called a given number of times.
 *
 * Assumptions: reads are counted rather than awaited by timer, because a read is issued from an
 * effect and the count is the only synchronous evidence that the screen decided to issue it.
 * @param {number} times - The expected cumulative number of reads.
 * @returns {Promise<void>} Resolves once that many reads have been issued.
 */
async function waitForReadCount(times: number): Promise<void> {
  await waitFor(
    /**
     * Asserts the read count has been reached.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(listing).toHaveBeenCalledTimes(times);
    },
  );
}

/**
 * Locates the row-23 message band the shell paints for this screen.
 *
 * Assumptions: the band is found by the test identifier `ui/src/layout/MessageBand.tsx` publishes
 * rather than by role, because that module assigns `alert` or `status` according to the SEVERITY of
 * what the band holds -- so a role query would have to know the severity, which is the screen's data
 * rather than the frame's structure.
 * @returns {HTMLElement} The band element.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Locates the search control by the accessible name its visible label supplies.
 *
 * Assumptions: the query is by LABEL and not by test identifier, because the control's accessible
 * name is what an assistive technology announces and what a keyboard operator navigates by. The 3270
 * original identified this field by its position on a fixed grid, which design gap G1 gives up, so
 * the accessible name is what replaces that position and is therefore the property worth regressing
 * against.
 * @returns {HTMLElement} The search input.
 */
function searchControl(): HTMLElement {
  return screen.getByLabelText(USER_LIST_LABELS.searchUserId);
}

/**
 * Locates one row's action cell by the accessible name the screen composes for it.
 *
 * Assumptions: the name pairs the column heading with the row's identifier, because the heading alone
 * is three characters and would name ten controls identically -- the terminal distinguished them by
 * row position, which G1 surrenders.
 * @param {string} userId - Identifier of the row whose cell is wanted.
 * @returns {HTMLElement} That row's one-character action input.
 */
function actionCell(userId: string): HTMLElement {
  return screen.getByLabelText(`${USER_LIST_LABELS.selColumn.trim()} ${userId}`);
}

/**
 * Locates one function-key control by the verbatim legend text it carries.
 *
 * Assumptions: the legend text IS the accessible name, which `ui/src/layout/PfKeyBar.tsx` records as
 * deliberate -- the row-24 literal already encodes both the key and its action as one `KEY=Action`
 * string, so an added label could only restate it in words no baseline source holds.
 * @param {string} label - The legend text, taken from a key-label constant and never retyped.
 * @returns {HTMLElement} The control for that key.
 */
function keyControl(label: string): HTMLElement {
  return screen.getByRole('button', { name: label });
}

/*
 * ---------------------------------------------------------------------------
 * Field constraints, and the zero-initial-cursor anomaly
 * ---------------------------------------------------------------------------
 */

/**
 * Proves the search control refuses a ninth character, as the terminal field did.
 *
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function holdsTheSearchFieldToEightCharacters(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  // WHY : Assumptions: eight, and it is the same eight from three independent declarations --
  //       `USRIDINI PIC X(8)` at `app/cpy-bms/COUSR00.CPY` L66, `SEC-USR-ID PIC X(08)` at
  //       `app/cpy/CSUSR01Y.cpy` L18 (the record key itself, L17-L23) and `CDEMO-USER-ID PIC X(08)`
  //       at `app/cpy/COCOM01Y.cpy` L25. The 3270 enforced the width in hardware, so the ninth
  //       keystroke did nothing at all; `maxLength` is where that survives.
  expectMaxLength(searchControl(), USER_ID_MAX_LENGTH);
  expect(USER_ID_MAX_LENGTH, 'the contract width must equal the copybook width').toBe(8);
}

/**
 * Proves every one of the ten action cells accepts exactly one character.
 *
 * Assumptions: all ten are asserted rather than a sample, because the cells are declared
 * individually in the symbolic map rather than generated, so an omission would be an omission in one
 * cell rather than in a loop.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function holdsEveryActionCellToOneCharacter(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(LAST_ROW_USER_ID);

  for (const userId of FULL_PAGE_USER_IDS) {
    // WHY : Assumptions: one character, from `SEL0001I` through `SEL0010I`, each declared
    //       `PIC X(1)` at `app/cpy-bms/COUSR00.CPY` L72, L102, L132, L162, L192, L222, L252, L282,
    //       L312 and L342, and painted `LENGTH=1` in the mapset. A second letter cannot be typed at
    //       all rather than being typed and then refused, which is what the terminal did.
    expectMaxLength(actionCell(userId), USER_LIST_ACTION_CELL_LENGTH);
  }

  expect(USER_LIST_ACTION_CELL_LENGTH, 'the cell width is the mapset LENGTH=1').toBe(1);
}

/**
 * ⚠️ Proves NO control on this screen claims focus on mount.
 *
 * ⚠️ Assumptions: `app/bms/COUSR00.bms` carries NO `IC` attribute anywhere. Counted across all
 * seventeen base mapsets, fifteen declare exactly one initial-cursor field and only `COTRN00.bms` and
 * `COUSR00.bms` declare none -- the single textual `IC` match in this mapset is inside the Apache
 * licence URL. Stating the count matters because "exactly one `autoFocus` per screen" is the rule
 * everywhere else in this migration, so a reader comparing this screen with `signon` or
 * `accountUpdate` would read the absence as a defect and add one, silently breaking fidelity.
 *
 * Assumptions: the 3270 left the cursor at its default position on this browse screen, so the SPA
 * must not steal it either. An unexpected focus jump changes keyboard behaviour for an operator
 * paging with PF7 and PF8 -- keystrokes intended for the page would be captured by a field.
 *
 * Assumptions: the program's repeated `MOVE -1 TO USRIDINL OF COUSR0AI`, at L126 among others, is
 * 3270 cursor PLACEMENT on a later turn and not an `IC` attribute on the map. The screen honours it
 * through the key hook's `restoreFocusRef`, which acts in response to a key press rather than on
 * mount, so it is not this assertion's business.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function claimsFocusOnNoControlAtAll(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const { baseElement } = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    baseElement.querySelectorAll('[autofocus]'),
    'COUSR00.bms declares no IC field, so no control may claim focus on mount',
  ).toHaveLength(0);
  expect(
    searchControl(),
    'the search control must not hold focus, matching the absent IC attribute',
  ).not.toHaveFocus();
}

/**
 * Records both message-band widths without reconciling them, because they measure different things.
 *
 * ⚠️ Assumptions: 75 and 78 are BOTH correct and neither is a mistake to be corrected into the other.
 * The CONTENT contract is 75 characters, from `CCARD-ERROR-MSG PIC X(75)` and `CCARD-RETURN-MSG PIC
 * X(75)` at `app/cpy/CVCRD01Y.cpy` L28 and L29 -- that is how much text a program may compose. The
 * DISPLAY field is 78, from `ERRMSGI PIC X(78)` at `app/cpy-bms/COUSR00.CPY` L372 and
 * `ERRMSG ... LENGTH=78 POS=(23,1)` in the mapset -- that is how much of row 23 the map reserves. A
 * 75-character sentence rendered into a 78-character field is exactly what the terminal did.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function recordsTheContentAndDisplayWidthsSeparately(): void {
  expect(
    MESSAGE_BAND_CONTENT_WIDTH,
    'CVCRD01Y L28-L29 declares PIC X(75) for message content',
  ).toBe(75);
  expect(
    messageBandWidthForMapset(USER_LIST_MAPSET),
    'COUSR00.CPY L372 declares ERRMSGI PIC X(78) for the display field',
  ).toBe(78);
}

/**
 * Proves the screen publishes the header identifiers the shell paints its band from.
 *
 * ⚠️ Assumptions: only the two identifiers the SCREEN owns are asserted, and the band's own layout,
 * titles and widths deliberately are not. `app/cpy-bms/COUSR00.CPY` declares the header fields
 * `TRNNAMEI PIC X(4)` at L24, `TITLE01I PIC X(40)` at L30, `CURDATEI PIC X(8)` at L36,
 * `PGMNAMEI PIC X(8)` at L42, `TITLE02I PIC X(40)` at L48 and `CURTIMEI PIC X(8)` at L54 -- and the
 * screen fills exactly two of them, the transaction and the program name, delegating the rest to
 * `AppShell` through the shell slot. The title constants and the band geometry belong to the shell's
 * own tests, so asserting them here would duplicate a contract this screen does not own.
 *
 * Assumptions: the two values are the ones the CICS resource definition binds -- transaction `CU00` to
 * program `COUSR00C` -- so they are identifiers rather than presentation, and their widths are the
 * `X(4)` and `X(8)` the symbolic map declares.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function publishesTheHeaderIdentifiersItOwns(): void {
  expect(USER_LIST_TRANSACTION_ID, 'TRNNAMEI is PIC X(4) and carries the transaction').toBe('CU00');
  expect(USER_LIST_TRANSACTION_ID, 'so the value is four characters wide').toHaveLength(4);
  expect(USER_LIST_PROGRAM_NAME, 'PGMNAMEI is PIC X(8) and carries the program').toBe('COUSR00C');
  expect(USER_LIST_PROGRAM_NAME, 'so the value is eight characters wide').toHaveLength(8);
  expect(USER_LIST_MAPSET, 'and the mapset this screen stands in for is COUSR00').toBe('COUSR00');
}

/** Registers the field-constraint cases. */
function fieldConstraintCases(): void {
  it('holds the search field to eight characters', holdsTheSearchFieldToEightCharacters);
  it('holds every action cell to one character', holdsEveryActionCellToOneCharacter);
  it('claims focus on no control at all', claimsFocusOnNoControlAtAll);
  it('publishes the header identifiers it owns', publishesTheHeaderIdentifiersItOwns);
  it(
    'records the content and display widths separately',
    recordsTheContentAndDisplayWidthsSeparately,
  );
}

describe('the user browse reproduces its copybook field constraints', fieldConstraintCases);

/*
 * ---------------------------------------------------------------------------
 * ⚠️ Row and cell affordances, all three measured in a browser
 * ---------------------------------------------------------------------------
 */

/**
 * Locates the table row a given identifier is listed on.
 *
 * Assumptions: the row is reached from a CELL rather than queried directly, because a row carries no
 * accessible name of its own -- which is itself the reason no `tabIndex` is put on it. The cell holding
 * the identifier is unique on the page, since the identifier is the record's own key.
 * @param {string} userId - Identifier of the row wanted.
 * @returns {HTMLElement} That row's `<tr>`.
 * @throws {Error} When the identifier is painted outside a table row, which means the browse is no
 *   longer rendering a grid at all -- a failure worth naming rather than reporting as a null match.
 */
function tableRow(userId: string): HTMLElement {
  const cell = screen.getByText(userId).closest('tr');

  if (cell === null) {
    throw new Error(`No table row carries the identifier ${userId}.`);
  }

  return cell;
}

/**
 * Proves the one-character action cell reserves room for its character and its caret.
 *
 * ⚠️ Purpose: regress the measured invisibility. On the delivered screen at a 375-pixel viewport this
 * control was 24 pixels wide with a 22-pixel client width and the design system's 11-pixel padding on
 * each side, leaving a content box of 0.00 pixels against a 9.078-pixel advance for the `U` an operator
 * is told to type: a pixel scan of the whole control returned zero ink while the value was genuinely
 * stored. The proportional column share is a percentage and the padding is a fixed length, so the two
 * cross over at a narrow viewport and the character is the thing that disappears.
 *
 * ⚠️ Assumptions: the measure is asserted as a DECLARATION and not as a rendered pixel width, because
 * the test DOM performs no layout -- every rendered box in it is zero by zero, so a width assertion
 * would pass on any value including the broken one. What can be asserted here is that the control
 * carries a minimum, that the minimum reserves more columns than the field admits characters, and that
 * it names the AA target-size floor. The pixel outcome was measured in a browser, and this case exists
 * to stop the declaration being removed.
 *
 * Assumptions: the reservation is asserted to EXCEED the accepted width, which is what distinguishes
 * room for a caret from room for a glyph alone -- a browser draws its caret between character
 * positions, where the terminal's cursor occupied the character cell itself.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reservesRoomForTheCharacterAndItsCaret(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  const measure = actionCell(FIRST_ROW_USER_ID).style.minInlineSize;

  expect(measure, 'the action cell must declare a minimum measure of its own').not.toBe('');
  expect(
    measure,
    'the reserved columns must appear in the expression, in character units',
  ).toContain(`${String(USER_LIST_ACTION_CELL_RESERVED_COLUMNS)}ch`);
  expect(
    USER_LIST_ACTION_CELL_RESERVED_COLUMNS,
    'a caret needs a column beyond the one the field admits',
  ).toBeGreaterThan(USER_LIST_ACTION_CELL_LENGTH);
  expect(measure, 'the AA target-size floor must survive an unresolved padding token').toContain(
    `${String(TARGET_SIZE_AA_MINIMUM)}px`,
  );
}

/**
 * Proves a row says under the pointer that it can be acted on, and places the cursor when clicked.
 *
 * ⚠️ Purpose: regress a measured absence. A browser pass found `cursor: auto` on these rows both at rest
 * and hovered, on a browse whose whole purpose is choosing rows to act on, so nothing about a row
 * indicated it was actionable and the only clue was the leading control -- which the case above records
 * was itself invisible at a narrow viewport.
 *
 * ⚠️ Assumptions: a row click places the CURSOR and writes nothing. `app/cbl/COUSR00C.cbl` separates
 * choosing a row from acting on it -- `PROCESS-ENTER-KEY` reads the action characters and only then
 * transfers control -- so a click that typed `'U'` would leave the operator one Enter away from an
 * update they never asked for, and one that typed `'D'` one Enter from a deletion. The cell's value is
 * asserted still empty for exactly that reason.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function makesARowActionableUnderThePointer(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  const targetRow = tableRow(FIRST_ROW_USER_ID);

  expect(targetRow.style.cursor, 'a row that can be acted on must say so under the pointer').toBe(
    'pointer',
  );

  await user.click(targetRow);

  expect(
    actionCell(FIRST_ROW_USER_ID),
    'a row click puts the cursor where the row is acted on',
  ).toHaveFocus();
  expect(
    actionCell(FIRST_ROW_USER_ID),
    'and types nothing, because choosing a row is not acting on it',
  ).toHaveValue('');
}

/**
 * Proves no row is given a tab stop of its own, so the keyboard traversal is not doubled.
 *
 * ⚠️ Assumptions: this asserts an ABSENCE deliberately, because the obvious way to make a row
 * keyboard-reachable is to make the row focusable and that would be wrong here. Every row already
 * carries a focusable control named `Sel` with the row's own identifier, so the traversal exists -- ten
 * stops, each announcing which row it belongs to -- and a focusable row would double each of those with
 * an element that has no accessible name at all. The authorization browse records the same treatment for
 * the same measurement.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function addsNoSecondTabStopPerRow(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(LAST_ROW_USER_ID);

  for (const userId of FULL_PAGE_USER_IDS) {
    expect(
      tableRow(userId),
      'the row must not be focusable, because its action cell already is',
    ).not.toHaveAttribute('tabindex');
    expect(
      actionCell(userId),
      'and the cell that is focusable must carry the name the row is identified by',
    ).toHaveAccessibleName(`${USER_LIST_LABELS.selColumn.trim()} ${userId}`);
  }
}

/**
 * Proves the browse takes the design system's default cell density rather than the compact one.
 *
 * ⚠️ Purpose: a rendering comparison across the four browse screens found this one alone rendering the
 * compact table scale with 8-pixel cell padding, while the card, transaction and reference-type browses
 * all took the default 16. Nothing in `app/bms/COUSR00.bms` asks for a tighter scale -- its ten row
 * families occupy one display row each, exactly as every other browse mapset's rows do -- so the
 * override was density chosen for one screen, and three screens against one settles the idiom.
 *
 * Assumptions: the class is asserted rather than a padding value, because the class is what the design
 * system publishes for the scale and a padding figure would be a design value this file may not hold.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function takesTheDefaultTableDensity(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const { container } = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    container.querySelectorAll('.ant-table-small'),
    'the compact scale is this screen alone among the four browses, so it is not taken',
  ).toHaveLength(0);
}

/** Registers the row and cell affordance cases. */
function rowAffordanceCases(): void {
  it('reserves room for the character and its caret', reservesRoomForTheCharacterAndItsCaret);
  it('makes a row actionable under the pointer', makesARowActionableUnderThePointer);
  it('adds no second tab stop per row', addsNoSecondTabStopPerRow);
  it('takes the default table density', takesTheDefaultTableDensity);
}

describe('the user browse makes its rows and its action cells reachable', rowAffordanceCases);

/*
 * ---------------------------------------------------------------------------
 * The grid's own horizontal scrolling region, and the page overflow it removes
 * ---------------------------------------------------------------------------
 */

/** Test identifier of the probe reporting the spacing token the theme in scope resolves to. */
const RESOLVED_PADDING_TEST_ID = 'probe-resolved-padding';

/**
 * A probe reporting the spacing token the theme in scope resolves the grid's measure from.
 *
 * Purpose: the grid's declared extent is computed from a design token, so the expected string can only
 * be built from the token that actually reached the grid. This probe reports that token, which makes
 * the assertion an equality against the screen's own derivation rather than against a value this file
 * would otherwise have to restate.
 *
 * Alternatives Considered: asserting only that the declared width contains the character-cell count,
 * which needs no probe. Rejected because it would pass for any padding term at all -- including none
 * -- and the padding term is half of what keeps the five columns off their collapse. The probe idiom
 * is the one `ui/src/test/appShell.test.tsx` already uses to observe a resolved token.
 * @returns {ReactElement} An element whose text is the resolved `padding` reference.
 */
function ResolvedPaddingProbe(): ReactElement {
  const { cssVar } = theme.useToken();

  return <span data-testid={RESOLVED_PADDING_TEST_ID}>{cssVar.padding}</span>;
}

/**
 * Proves the derivation of the grid's floor is the mapset's row over its five columns.
 *
 * ⚠️ Assumptions: the two numbers in the expected string are the two measurements the floor rests on,
 * and both are checkable against the source. `app/bms/COUSR00.bms` heads row 8 at `POS=(8,5)` through
 * `POS=(8,72)` with `'Type'` at `LENGTH=4`, so the five columns span columns 5 to 75 -- 71 character
 * cells; and the design system adds its cell padding on BOTH sides of each of the five columns, which
 * is the ten padding terms. A regression that dropped the padding term, or counted three columns
 * because it was copied from the transaction-type browse, changes this string and fails here.
 *
 * Assumptions: the token is supplied as a literal rather than read from a theme, because this case
 * asserts the ARITHMETIC and a stub makes the expected string legible. The rendered case below asserts
 * the same function against the token the application actually resolves.
 *
 * ⚠️ Assumptions: the literal is passed with NO cast, which it can be because
 * {@link userListTableMeasure} declares the one token member it reads rather than the whole theme.
 * A cast was what the first form of this case used, and it does not survive
 * `exactOptionalPropertyTypes`: converting `{ padding: string }` to `GlobalToken` is a conversion
 * between types that do not sufficiently overlap, and the only way to keep it would have been a double
 * cast through `unknown` -- which suppresses the check instead of satisfying it.
 * @returns {void} Nothing; the expectation is the observable result.
 */
function derivesTheGridFloorFromTheMapsetRow(): void {
  expect(
    userListTableMeasure({ padding: '8px' }),
    'seventy-one character cells plus the design system padding on both sides of five columns',
  ).toBe('calc(71ch + 10 * 8px)');
}

/**
 * ⚠️ Proves the grid carries its own horizontal scrolling region, so the PAGE never has to pan.
 *
 * ⚠️ Purpose: close a measured page-level overflow. At a 375-pixel viewport
 * `document.documentElement.scrollWidth` was **398** against a `clientWidth` of **375**, and
 * `window.scrollTo(50, 0)` moved the document to `window.scrollX` **23** -- the `Type` heading clipped
 * to `Typ` with its values half outside the viewport. The same pass established the cause: the grid
 * measured 374.25 pixels inside a 327-pixel `.ant-table-content` whose computed `overflow-x` was
 * `visible`, and every ancestor up to `BODY` reported the same 398 against 375. With no scrolling
 * region declared anywhere, the page paid for the overflow.
 *
 * ⚠️ Assumptions: this is asserted STRUCTURALLY and not in pixels, because jsdom performs no layout --
 * every `offsetWidth` here is 0, so a width comparison would pass whatever the grid declared. What the
 * declared properties prove is exactly the mechanism the finding names: in the pinned package
 * `@rc-component/table/lib/Table.js` L259-L272 turns a declared horizontal extent into
 * `overflow-x: auto` on the `-content` element and `width: <extent>; min-width: 100%` on the inner
 * grid, and L556-L575 applies both. So `overflow-x: auto` on that element IS the container property
 * the clean sibling screen has and this one did not.
 *
 * ⚠️ Assumptions: `min-width: 100%` is asserted alongside the extent because it is what keeps every
 * wider viewport unchanged -- the grid still fills its container above the floor -- so its absence
 * would turn a fix for narrow widths into a regression at every other width.
 *
 * Assumptions: the fixed layout is asserted as well, because the extent alone does not imply it. That
 * module's L426-L442 infers `'fixed'` only for a pinned column, a pinned header, a sticky grid or an
 * ellipsised column, and this grid has none of the four; under the automatic layout a declared column
 * width becomes a minimum the content may grow, which is how the one-character action column came to
 * measure about 700 pixels.
 * @returns {Promise<void>} Resolves once the declared properties have been asserted.
 */
async function scrollsItsOwnGridRatherThanThePage(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const { container } = await renderInAppShell(
    <>
      <UserListScreen />
      <ResolvedPaddingProbe />
    </>,
    { initialEntries: [USER_LIST_PATH], routePath: USER_LIST_PATH },
  );
  await waitForRow(FIRST_ROW_USER_ID);

  const resolvedPadding = screen.getByTestId(RESOLVED_PADDING_TEST_ID).textContent ?? '';
  const scroller = container.querySelector<HTMLElement>('.ant-table-content');
  const grid = container.querySelector<HTMLTableElement>('.ant-table-content > table');

  expect(
    resolvedPadding,
    'the probe must report a spacing token for the measure to be built from',
  ).not.toBe('');
  expect(scroller, 'the grid must sit inside the element the design system scrolls').not.toBeNull();
  expect(
    scroller?.style.overflowX,
    'the grid scrolls INSIDE its container, so the page never pans -- this is the property the clean sibling browse has',
  ).toBe('auto');
  expect(
    grid?.style.width,
    'the declared extent is the mapset row, resolved through the token the application supplied',
  ).toBe(userListTableMeasure({ padding: resolvedPadding }));
  expect(
    grid?.style.minWidth,
    'and it still fills its container at every viewport above the floor',
  ).toBe('100%');
  expect(
    grid?.style.tableLayout,
    'the five mapset-derived column shares are only binding under a fixed layout',
  ).toBe('fixed');
}

/**
 * ⚠️ Proves each column reserves the design system's padding on top of its OWN characters, so the
 * narrowest column is not starved of the space its heading needs.
 *
 * ⚠️ Purpose: close a second measured defect on the same grid. The five widths were first expressed as
 * PROPORTIONAL shares -- a column's cells divided by the row's -- and applied to a total that already
 * included the padding, so each column received a slice of the padding in proportion to its characters
 * rather than the two edges it actually has. A browser pass measured what that costs the narrowest
 * column: `userType` spans 4 of 71 cells, so it drew 5.63% of a 718.63-pixel grid -- 40.48 pixels --
 * and 16 pixels of padding on each edge left a content box of roughly 8. Its own four-character
 * heading then laid out ONE CHARACTER PER LINE, standing the header row 120 pixels tall, at every
 * width from 375 to 1920. The counts were never wrong; the way they were applied was.
 *
 * ⚠️ Assumptions: the SUM is what this asserts, because the sum is the property that makes the columns
 * and {@link userListTableMeasure} agree by construction rather than by someone keeping two numbers in
 * step. Summing `cells * ch + 2 * padding` over the row gives `71ch + 10 * padding`, which is exactly
 * the floor the case above asserts. So a change to either side that is not made to both fails here.
 *
 * ⚠️ Assumptions: the cell counts are spelled as literals read from `app/bms/COUSR00.bms` -- `'Sel'`
 * at `POS=(8,5)` through `'Type'` at `POS=(8,72)` with `LENGTH=4`, giving 7, 12, 24, 24 and 4 -- rather
 * than imported from the screen's own constant. Importing it would compare the screen against itself
 * and pass however the mapset was mis-read; stating the measurement is what makes this falsifiable.
 *
 * Assumptions: the absence of a percentage is asserted explicitly. A proportional share is still a
 * valid CSS width that the design system applies without complaint, so the defect this closes leaves
 * no error behind -- only a tall header no assertion was watching. Naming the shape rules out a
 * silent return to it.
 * @returns {Promise<void>} Resolves once every declared column width has been asserted.
 */
async function reservesCellPaddingPerColumnRatherThanSharingIt(): Promise<void> {
  /** Character cells each column spans in `app/bms/COUSR00.bms`, in the mapset's own order. */
  const mapsetCells = [7, 12, 24, 24, 4];

  listing.mockResolvedValue(fullPageWithMore());
  const { container } = await renderInAppShell(
    <>
      <UserListScreen />
      <ResolvedPaddingProbe />
    </>,
    { initialEntries: [USER_LIST_PATH], routePath: USER_LIST_PATH },
  );
  await waitForRow(FIRST_ROW_USER_ID);

  const resolvedPadding = screen.getByTestId(RESOLVED_PADDING_TEST_ID).textContent ?? '';

  /*
   * WHY : Assumptions: the four traversals below are plain loops rather than `map`, `filter` and
   *       `reduce` calls. `ui/eslint.config.js` selects a function expression in every position, so an
   *       inline callback owes its own JSDoc block, and four documented one-line arrows would be longer
   *       and harder to read than the loops they replace. The file header records the same reasoning for
   *       the callbacks it does keep.
   */
  const declaredWidths: string[] = [];
  const proportional: string[] = [];

  for (const column of container.querySelectorAll<HTMLElement>('.ant-table-content col')) {
    declaredWidths.push(column.style.width);

    if (column.style.width.includes('%')) {
      proportional.push(column.style.width);
    }
  }

  const expectedWidths: string[] = [];
  let spannedCells = 0;

  for (const cells of mapsetCells) {
    expectedWidths.push(`calc(${String(cells)}ch + 2 * ${resolvedPadding})`);
    spannedCells += cells;
  }

  expect(
    declaredWidths,
    'the grid declares one width per mapset column, in the mapset order',
  ).toHaveLength(mapsetCells.length);
  expect(
    proportional,
    'no column may take a PROPORTIONAL share, which is what starved the narrowest one',
  ).toEqual([]);
  expect(
    declaredWidths,
    'each column claims its own characters plus the padding on both of its edges',
  ).toEqual(expectedWidths);
  expect(
    spannedCells,
    'and the five spans still sum to the row the grid floor is derived from',
  ).toBe(71);
}

/** Registers the cases covering the grid's own scrolling region. */
function gridScrollRegionCases(): void {
  it('derives the grid floor from the mapset row', derivesTheGridFloorFromTheMapsetRow);
  it('scrolls its own grid rather than the page', scrollsItsOwnGridRatherThanThePage);
  it(
    'reserves cell padding per column rather than sharing it',
    reservesCellPaddingPerColumnRatherThanSharingIt,
  );
}

describe('the user browse scrolls its own grid rather than the page', gridScrollRegionCases);

/*
 * ---------------------------------------------------------------------------
 * Page arity, and the deliberate absence of offset pagination
 * ---------------------------------------------------------------------------
 */

/**
 * Proves the screen declares a ten-row page.
 *
 * Assumptions: the constant is asserted as well as the rendered row count, because the two can fail
 * apart. A screen could declare ten and be handed nine, or declare nine and render the ten it was
 * handed; the arity the screen ASKS for is what the paging hook enforces a delivered page against.
 * @returns {void} Nothing; the assertion either holds or fails the case.
 */
function declaresATenRowPage(): void {
  // WHY : Assumptions: ten, verified two independent ways so neither source has to be trusted alone
  //       -- `app/cbl/COUSR00C.cbl` L293 and L347 each read
  //       `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10`, and `app/cpy-bms/COUSR00.CPY`
  //       declares exactly the ten cells `SEL0001I` (L72) through `SEL0010I` (L342). The page size is
  //       therefore visible in the symbolic map itself, independently of the program.
  expect(USER_LIST_PAGE_SIZE, 'COUSR00C L293/L347 and the ten SEL000nI cells both fix ten').toBe(
    10,
  );
}

/**
 * Proves a full page paints exactly ten rows, with a tenth present and no eleventh.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsExactlyTenRowsForAFullPage(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  const table = screen.getByRole('table');
  const bodyRows = within(table).getAllByRole('row').slice(1);
  expect(bodyRows, 'a full page is the ten rows the mapset paints at rows 10 to 19').toHaveLength(
    USER_LIST_PAGE_SIZE,
  );
  expect(screen.getByText(LAST_ROW_USER_ID), 'the tenth row must be painted').toBeInTheDocument();
  expect(
    screen.queryByText(ELEVENTH_USER_ID),
    'no eleventh row exists on a ten-row map',
  ).not.toBeInTheDocument();
}

/**
 * Proves a short page paints only the rows delivered rather than ten fixed slots.
 *
 * Assumptions: this is the complement of the case above and neither substitutes for the other. Ten
 * rows for a full page would also be satisfied by a table that always draws ten; only a short page
 * distinguishes the two.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsOnlyTheRowsAShortPageDelivers(): Promise<void> {
  listing.mockResolvedValue(pageOf(SHORT_PAGE_USER_IDS, false));
  await renderBrowse();
  await waitForRow(SHORT_PAGE_USER_IDS[0]);

  const table = screen.getByRole('table');
  expect(
    within(table).getAllByRole('row').slice(1),
    'a short page paints its own rows, not ten slots',
  ).toHaveLength(SHORT_PAGE_USER_IDS.length);
}

/**
 * ⚠️ Proves the table renders no offset pagination control of its own.
 *
 * ⚠️ Trade-offs: the design system's built-in pagination is disabled DELIBERATELY, and the trade is
 * accepted with its cost named. Offset paging is what `pagination` defaults to, and under concurrent
 * inserts an offset both SKIPS and REPEATS rows -- a row inserted before the current offset shifts
 * every later row by one, so the next page re-shows a row already seen and omits one never seen. A
 * browse by key cannot do either, because it asks for rows strictly beyond a key rather than beyond a
 * count. The reference already pages by key: it carries a first-key and last-key pair and discovers
 * one record beyond the ten it shows, so keyset paging is a one-for-one transcription and offset
 * paging would be a regression dressed as a simplification. The cost accepted is that the operator
 * gets no total row count and no page-number jump, neither of which the terminal offered either.
 *
 * Assumptions: the absence is asserted through the DOM the design system emits for a pagination
 * control rather than by reading the prop, because a prop assertion would need the component's
 * internals while the rendered list is what an operator would actually be able to click.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersNoOffsetPaginationControl(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const { baseElement } = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    baseElement.querySelectorAll('.ant-pagination'),
    'pagination={false} must leave no offset pager in the document',
  ).toHaveLength(0);
  expect(
    screen.queryByRole('listitem', { name: '1' }),
    'an offset pager would expose numbered page items',
  ).not.toBeInTheDocument();
}

/** Registers the page-arity cases. */
function pageArityCases(): void {
  it('declares a ten-row page', declaresATenRowPage);
  it('paints exactly ten rows for a full page', paintsExactlyTenRowsForAFullPage);
  it('paints only the rows a short page delivers', paintsOnlyTheRowsAShortPageDelivers);
  it('renders no offset pagination control', rendersNoOffsetPaginationControl);
}

describe('the user browse pages at the mapset arity without offsets', pageArityCases);

/*
 * ---------------------------------------------------------------------------
 * The keyset envelope and the cursors replayed in each direction
 * ---------------------------------------------------------------------------
 */

/**
 * ⚠️ Proves the page envelope carries exactly four members.
 *
 * ⚠️ Assumptions: four, and a fifth is not merely unnecessary but wrong. `ui/src/api/types.ts`
 * declares `PageResponse<T>` at L174 to L207 with `items`, `firstKey`, `lastKey` and `hasNext` and
 * nothing else, and records that every contract publishes it with `additionalProperties: false`.
 * There is no `hasPrev`, no page number, no page size and no row total, because no service sends one:
 * backward availability is the CLIENT's own page ordinal, derived in
 * `ui/src/hooks/usePagedQuery.ts`, exactly as the reference decides it from `CDEMO-CU00-PAGE-NUM`
 * alone at `app/cbl/COUSR00C.cbl` L250 without issuing any probe read. A fixture that invented the
 * flag would let a screen test pass against a body the server never sends, which is the one class of
 * fixture defect no assertion downstream can catch.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function carriesExactlyFourEnvelopeMembers(): void {
  const envelope = fullPageWithMore();

  expect(Object.keys(envelope).sort(), 'the envelope has four members and no more').toStrictEqual([
    'firstKey',
    'hasNext',
    'items',
    'lastKey',
  ]);
}

/**
 * Proves the opening read carries neither a cursor nor a position.
 *
 * Assumptions: the whole argument list is asserted, because the property under test is an ABSENCE.
 * The reference opens its browse on `LOW-VALUES` when the search field is blank, so the opening
 * request must name no position at all rather than an empty one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function opensWithNeitherCursorNorPosition(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  expect(listing).toHaveBeenNthCalledWith(1);
}

/**
 * Proves paging forward replays the trailing cursor in the forward direction.
 *
 * ⚠️ Assumptions: forward means strictly BEYOND the last row delivered, read in ascending key order,
 * which is what `READNEXT` does from the position the previous page left. The hook expresses that as
 * the page's own `lastKey` replayed with direction `next`, so the assertion is on the cursor VALUE
 * and not merely on a read having happened -- a screen that re-read the opening page would also
 * increment the count.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function pagesForwardFromTheTrailingCursor(): Promise<void> {
  listing.mockResolvedValueOnce(fullPageWithMore()).mockResolvedValueOnce(fullPageAtTheEnd());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK08');

  await waitForReadCount(2);
  expect(listing).toHaveBeenNthCalledWith(2, {
    cursor: TRAILING_CURSOR,
    direction: 'next',
  });
}

/**
 * Proves paging backward replays the leading cursor in the backward direction.
 *
 * ⚠️ Assumptions: backward means strictly BEFORE the first row delivered, read in descending key
 * order, which is precisely what `READPREV` does -- the reference reads backward from the page's own
 * first key rather than recomputing an offset. The hook expresses that as `firstKey` replayed with
 * direction `previous`.
 *
 * Assumptions: a forward step is taken first, because the backward key is refused on the opening page
 * by design. The refusal is asserted separately below; here it would only prevent the read under
 * test from being issued at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function pagesBackwardFromTheLeadingCursor(): Promise<void> {
  listing
    .mockResolvedValueOnce(fullPageWithMore())
    .mockResolvedValueOnce(fullPageAtTheEnd())
    .mockResolvedValueOnce(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);
  await pressPfKey(user, 'PFK08');
  await waitForReadCount(2);

  await pressPfKey(user, 'PFK07');

  await waitForReadCount(3);
  expect(listing).toHaveBeenNthCalledWith(3, {
    cursor: LEADING_CURSOR,
    direction: 'previous',
  });
}

/**
 * Proves both paging keys stay OFFERED at a dead end, where they used to be greyed out.
 *
 * ⚠️ Purpose: pin the property that a boundary answers instead of withdrawing. The screen greyed these
 * two keys from the envelope's availability, which reads as the mapping a page-navigation pair is
 * usually given, and it cost a verbatim sentence: `app/cbl/COUSR00C.cbl` refuses no paging key --
 * `PROCESS-PF7-KEY` at L248 to L254 and `PROCESS-PF8-KEY` at L271 to L277 each dispatch the key, move
 * their own sentence into `WS-MESSAGE` and re-send the map -- and `ui/src/layout/usePfKeys.ts` answers a
 * greyed binding through its unmapped-key path, so a greyed backward key put "Invalid key pressed" where
 * the reference puts "You are already at the top of the page...".
 *
 * ⚠️ Assumptions: the page under test reports NO further page and is the opening page, so both
 * directions are exhausted at once and one render exercises both keys. That is the browse hook's `ONLY`
 * position, and it is the state the previous form of this case asserted BOTH keys disabled in.
 *
 * Assumptions: the assertion is on the CONTROLS and not on the sentences, because the two sentences are
 * asserted where the two conditions are known, by `refusesBackwardOnTheOpeningPage` and
 * `refusesForwardOnTheLastPage`. This case exists to stop the greying returning, which would leave those
 * two passing through the wrong path or not at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function offersBothPagingKeysAtADeadEnd(): Promise<void> {
  listing.mockResolvedValue(fullPageAtTheEnd());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    keyControl(USER_LIST_KEY_LABELS.PFK07),
    'the reference answers a backward key on the opening page rather than refusing it',
  ).toBeEnabled();
  expect(
    keyControl(USER_LIST_KEY_LABELS.PFK08),
    'the reference answers a forward key at the end of the file rather than refusing it',
  ).toBeEnabled();
}

/**
 * Proves the forward key TAKES the step when a further page exists, rather than merely being offered.
 *
 * ⚠️ Refactoring Rationale: this case used to assert only that the control was enabled, which was worth
 * asserting while the screen greyed it and is worth nothing now that no binding carries a `disabled`
 * predicate -- an always-enabled control passes an enablement assertion without the screen deciding
 * anything. What distinguishes an available step from an exhausted one is now whether a READ is issued,
 * so that is what is asserted: the same press that leaves the read count at one on the last page
 * (`refusesForwardOnTheLastPage`) raises it to two here.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function takesTheForwardStepWhileAFurtherPageExists(): Promise<void> {
  listing.mockResolvedValueOnce(fullPageWithMore()).mockResolvedValueOnce(fullPageAtTheEnd());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK08');

  await waitForReadCount(2);
  expect(
    keyControl(USER_LIST_KEY_LABELS.PFK08),
    'the key remains offered on the page it arrived at, whichever end that is',
  ).toBeEnabled();
}

/** Registers the keyset-paging cases. */
function keysetPagingCases(): void {
  it('carries exactly four envelope members', carriesExactlyFourEnvelopeMembers);
  it('opens with neither cursor nor position', opensWithNeitherCursorNorPosition);
  it('pages forward from the trailing cursor', pagesForwardFromTheTrailingCursor);
  it('pages backward from the leading cursor', pagesBackwardFromTheLeadingCursor);
  it('offers both paging keys at a dead end', offersBothPagingKeysAtADeadEnd);
  it(
    'takes the forward step while a further page exists',
    takesTheForwardStepWhileAFurtherPageExists,
  );
}

describe('the user browse pages by sealed cursor in both directions', keysetPagingCases);

/*
 * ---------------------------------------------------------------------------
 * ⚠️ The five distinct paging sentences
 * ---------------------------------------------------------------------------
 */

/**
 * One paging sentence and the line of `COUSR00C` that emits it.
 *
 * Assumptions: the line number is carried beside the text so each case asserts BOTH halves -- that
 * the catalog holds the right string, and that its recorded provenance still names this program at
 * the line the string was read from. A catalog entry whose text drifted would fail the first; one
 * quietly re-pointed at another program's line would fail the second.
 */
interface PagingSentence {
  /**
   * The catalog key, which is also the key its provenance is filed under.
   *
   * Assumptions: the key is carried rather than derived from the text, because the catalog and its
   * provenance index are two objects with one key set -- the catalog declares that relationship with
   * a `satisfies` clause -- so naming the key once lets a case read both without a lookup table of
   * its own that could disagree with either.
   *
   * Assumptions: ONE key type is named rather than the intersection of the catalog's and the
   * provenance index's, because the `satisfies` clause makes them structurally identical -- writing
   * both would be a duplicated constituent that says nothing the single one does not.
   */
  readonly key: keyof typeof SHARED_MESSAGE_SOURCES;
  /** The catalogued sentence, taken from the catalog and never retyped. */
  readonly text: string;
  /** The 1-based line of `app/cbl/COUSR00C.cbl` that moves it into `WS-MESSAGE`. */
  readonly line: number;
  /** How the sentence reads, for the case name. */
  readonly wording: string;
}

/**
 * ⚠️ The five sentences `COUSR00C` emits about the edges of the browse.
 *
 * ⚠️ Alternatives Considered: collapsing these five into one "no more pages" message, which is what a
 * modern implementation would naturally express with two states. Rejected outright, and this is the
 * highest-value assertion in the file. There are THREE distinct ways this program says "top" -- "are
 * already at the top" at L251, "are at the top" at L603 and "have reached the top" at L671 -- and TWO
 * ways it says "bottom" -- "are already at the bottom" at L273 and "have reached the bottom" at L637.
 * They look like accidental drift and they are not interchangeable: two describe a REFUSED key press
 * and three describe an ARRIVAL discovered on a read. Merging any pair would be invisible to a smoke
 * test while silently destroying user-visible text that AAP transformation rule T8 requires to be
 * carried across character for character. Asserting each separately, against its own line, is what
 * makes the merge impossible to perform accidentally.
 *
 * Assumptions: the values are READ from the catalog rather than written here. A case that retyped the
 * sentence would assert only that the screen agrees with this file, which a paraphrase in both places
 * satisfies; taking the catalog entry asserts that the screen agrees with the CATALOG.
 */
const PAGING_SENTENCES: readonly PagingSentence[] = [
  {
    key: 'YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE',
    text: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
    line: 251,
    wording: "'are already at the top' refusing a backward key on the opening page",
  },
  {
    key: 'YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE',
    text: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
    line: 273,
    wording: "'are already at the bottom' refusing a forward key on the last page",
  },
  {
    key: 'YOU_ARE_AT_THE_TOP_OF_THE_PAGE',
    text: SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE,
    line: 603,
    wording: "'are at the top' with no 'already', from the opening read path",
  },
  {
    key: 'YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE',
    text: SHARED_MESSAGES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE,
    line: 637,
    wording: "'have reached the bottom' rather than 'are', from the forward read path",
  },
  {
    key: 'YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE',
    text: SHARED_MESSAGES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE,
    line: 671,
    wording: "'have reached the top' rather than 'are', from the backward read path",
  },
];

/**
 * Reports the lines of one program that a catalogued shared message is recorded as coming from.
 *
 * Assumptions: the provenance is filtered to ONE program file, because several of these sentences are
 * emitted by sibling browses too -- the transaction browse emits all five and the pending
 * authorization browse two of them -- so an unfiltered lookup would pass on another program's line.
 * @param {readonly { readonly file: string; readonly lines: readonly number[] }[]} sources - The
 *   provenance records for one catalog entry.
 * @returns {readonly number[]} The lines recorded against `COUSR00C`, empty when none is.
 */
function linesRecordedForUserBrowse(
  sources: readonly { readonly file: string; readonly lines: readonly number[] }[],
): readonly number[] {
  return sources
    .filter(
      /**
       * Keeps only the record naming this program's source file.
       * @param {object} entry - One provenance record.
       * @param {string} entry.file - Repository-relative path the record names.
       * @returns {boolean} `true` when the record names `COUSR00C`.
       */
      (entry: { readonly file: string }): boolean => entry.file === PROGRAM_SOURCE_FILES.COUSR00C,
    )
    .flatMap(
      /**
       * Takes the lines from one record.
       * @param {object} entry - One provenance record.
       * @param {readonly number[]} entry.lines - Lines that record cites.
       * @returns {readonly number[]} That record's lines.
       */
      (entry: { readonly lines: readonly number[] }): readonly number[] => entry.lines,
    );
}

/**
 * Proves the five paging sentences are five different strings.
 *
 * ⚠️ Assumptions: a set is used rather than pairwise comparison, so the case fails if ANY two of the
 * five ever become equal -- which is exactly what a well-intentioned tidy-up would do. Five entries
 * collapsing to four is the smallest possible form of the merge this suite exists to prevent, and it
 * would leave every rendering assertion below still passing.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function keepsTheFiveSentencesDistinct(): void {
  const distinct = new Set(
    PAGING_SENTENCES.map(
      /**
       * Takes one sentence's text.
       * @param {PagingSentence} sentence - The entry.
       * @returns {string} Its catalogued text.
       */
      (sentence: PagingSentence): string => sentence.text,
    ),
  );

  expect(distinct.size, 'COUSR00C emits five different sentences, not one').toBe(
    PAGING_SENTENCES.length,
  );
  expect(
    SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
    "L251 says 'already', and dropping the word would change what the operator reads",
  ).toContain('already');
  expect(
    SHARED_MESSAGES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE,
    "L671 says 'have reached', not 'are at'",
  ).toContain('have reached');
}

/**
 * Proves the browse refuses a backward key on the opening page in the reference's own words.
 *
 * ⚠️ Assumptions: the sentence asserted is the "already at" one from L251 and NOT the "have reached"
 * one from L671, because the two describe different events. This is a refused KEY PRESS on the first
 * page, which the reference decides on `CDEMO-CU00-PAGE-NUM > 1` alone at L250 without reading
 * anything; L671 answers a backward READ that ran off the front of the file.
 *
 * ⚠️ Assumptions: the key is PRESSED rather than the control clicked, and the reason changed with the
 * screen. It used to be that the control was greyed at this boundary and a disabled control cannot fire
 * a click at all; no binding is greyed now, so both paths reach the same handler and either would carry
 * the sentence. The keystroke is kept because the terminal had no pointer, so it is the only path the
 * reference itself has -- `offersBothPagingKeysAtADeadEnd` covers the control's own availability.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesBackwardOnTheOpeningPage(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK07');

  expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE);
  expect(listing, 'a refused backward step issues no read').toHaveBeenCalledTimes(1);
}

/**
 * Proves the browse refuses a forward key on the last page in the reference's own words.
 *
 * ⚠️ Assumptions: the sentence is the "already at" one from L273 and not the "have reached" one from
 * L637, for the mirror of the reason above -- `PROCESS-PF8-KEY` refuses on the availability flag
 * alone at L270 to L273, while L637 answers a forward read that hit end-of-file.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesForwardOnTheLastPage(): Promise<void> {
  listing.mockResolvedValue(fullPageAtTheEnd());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK08');

  expect(messageBand()).toHaveTextContent(
    SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
  );
  expect(listing, 'a refused forward step issues no read').toHaveBeenCalledTimes(1);
}

/**
 * Builds the case proving one paging sentence is catalogued and still traced to its own line.
 *
 * ⚠️ Refactoring Rationale: the case body is returned from a NAMED factory rather than written as an
 * inline arrow beside the case name, and the reason is mechanical rather than stylistic. Prettier
 * detaches a block comment that follows an argument comma -- it rewrote the documentation block onto
 * the case NAME and left the function undocumented, and the reformat was not idempotent, so
 * `prettier --check` failed on a file `prettier --write` had just produced. Both are required steps in
 * `.github/workflows/ui-ci.yml`. A factory keeps the block inside a function body where the formatter
 * leaves it alone, which is the same idiom the sibling screen tests adopted for the same reason.
 * @param {PagingSentence} sentence - The sentence whose text and provenance are under test.
 * @returns {() => void} The case body, which asserts both halves.
 */
function pagingSentenceCase(sentence: PagingSentence): () => void {
  /**
   * Asserts the catalog holds this sentence and still cites this line of `COUSR00C`.
   * @returns {void} Nothing; the assertions either hold or fail the case.
   */
  return function assertsTheSentenceAndItsProvenance(): void {
    expect(sentence.text, 'the entry the key names is the text this case carries').toBe(
      SHARED_MESSAGES[sentence.key],
    );
    expect(
      linesRecordedForUserBrowse(SHARED_MESSAGE_SOURCES[sentence.key]),
      'the catalog must still trace this sentence to this line of COUSR00C',
    ).toContain(sentence.line);
  };
}

/** Registers one case per paging sentence, plus the distinctness and refusal cases. */
function pagingSentenceCases(): void {
  for (const sentence of PAGING_SENTENCES) {
    it(`carries ${sentence.wording} from L${String(sentence.line)}`, pagingSentenceCase(sentence));
  }

  it('keeps the five sentences distinct', keepsTheFiveSentencesDistinct);
  it('refuses backward on the opening page', refusesBackwardOnTheOpeningPage);
  it('refuses forward on the last page', refusesForwardOnTheLastPage);
}

describe('the user browse keeps all five paging sentences apart', pagingSentenceCases);

/*
 * ---------------------------------------------------------------------------
 * The one lookup-failure sentence, shared by all three read paths
 * ---------------------------------------------------------------------------
 */

/**
 * Proves the lookup failure is ONE catalog entry recorded against all three read sites.
 *
 * ⚠️ Assumptions: `'Unable to lookup User...'` appears three times in `COUSR00C` -- at L610 under the
 * opening `STARTBR`, at L644 under `READNEXT` and at L678 under `READPREV` -- and it is the SAME
 * string at all three. It is therefore one catalog entry with three recorded lines, and the catalog
 * must not accidentally come to hold three near-duplicates. That is the failure this case exists to
 * catch, because three entries differing by a word would each still render plausibly.
 *
 * Assumptions: the reference answers every unexpected condition on a read with this single sentence
 * rather than a per-status vocabulary, so the screen mapping many failures onto one sentence is
 * transcription rather than a loss of detail.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function catalogsOneLookupFailureForThreeReadSites(): void {
  expect(
    linesRecordedForUserBrowse(SHARED_MESSAGE_SOURCES.UNABLE_TO_LOOKUP_USER),
    'L610, L644 and L678 all emit the one sentence',
  ).toStrictEqual([610, 644, 678]);
}

/**
 * Proves a failed OPENING read paints the lookup-failure sentence.
 *
 * Assumptions: the read is failed by rejecting the listing, which is how every failure reaches the
 * screen -- the browse hook catches the rejection and publishes it, and the screen maps it onto the
 * sentence. `ui/src/layout/MessageBand.tsx` deliberately refuses a problem document, so choosing the
 * sentence is the screen's work rather than the band's.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsAFailedOpeningRead(): Promise<void> {
  listing.mockRejectedValue(new Error('the opening read did not complete'));
  await renderBrowse();

  await waitFor(
    /**
     * Asserts the failure sentence has been painted.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER);
    },
  );
}

/**
 * Proves a failed FORWARD read paints the same sentence.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsAFailedForwardRead(): Promise<void> {
  listing
    .mockResolvedValueOnce(fullPageWithMore())
    .mockRejectedValueOnce(new Error('the forward read did not complete'));
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK08');

  await waitFor(
    /**
     * Asserts the failure sentence has been painted.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER);
    },
  );
}

/**
 * Proves a failed BACKWARD read paints the same sentence.
 *
 * Assumptions: a forward step is taken first so the backward key is available, for the reason the
 * cursor case above records.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsAFailedBackwardRead(): Promise<void> {
  listing
    .mockResolvedValueOnce(fullPageWithMore())
    .mockResolvedValueOnce(fullPageAtTheEnd())
    .mockRejectedValueOnce(new Error('the backward read did not complete'));
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);
  await pressPfKey(user, 'PFK08');
  await waitForReadCount(2);

  await pressPfKey(user, 'PFK07');

  await waitFor(
    /**
     * Asserts the failure sentence has been painted.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER);
    },
  );
}

/** Registers the lookup-failure cases. */
function lookupFailureCases(): void {
  it('catalogs one lookup failure for three read sites', catalogsOneLookupFailureForThreeReadSites);
  it('reports a failed opening read', reportsAFailedOpeningRead);
  it('reports a failed forward read', reportsAFailedForwardRead);
  it('reports a failed backward read', reportsAFailedBackwardRead);
}

describe('the user browse reports every read failure in one sentence', lookupFailureCases);

/*
 * ---------------------------------------------------------------------------
 * The two-letter action-code domain
 * ---------------------------------------------------------------------------
 */

/**
 * ⚠️ Proves the refusal sentence is this program's own, distinct from its two sibling browses.
 *
 * ⚠️ Assumptions: three sibling list screens use three DIFFERENT selection vocabularies, and this one
 * must not be normalised toward either of the others. `COUSR00C` L212 reads
 * `'Invalid selection. Valid values are U and D'` -- PLURAL "values are", a two-letter domain, and no
 * trailing ellipsis. `COTRN00C` L199 reads `'Invalid selection. Valid value is S'` -- SINGULAR "value
 * is", one letter. `COCRDLIC` refuses with an upper-case `'INVALID ACTION CODE'`. Each is carried
 * across as written, so a shared helper that produced one wording for all three would break two of
 * them while type-checking perfectly.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function keepsItsOwnSelectionVocabulary(): void {
  const refusal = PROGRAM_MESSAGES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D;

  expect(refusal, 'the domain is the two letters U and D').toBe(
    'Invalid selection. Valid values are U and D',
  );
  expect(refusal, "L212 is plural: 'values are'").toContain('values are');
  expect(refusal, 'L212 carries no trailing ellipsis, unlike most sentences here').not.toContain(
    '...',
  );
  expect(
    PROGRAM_MESSAGE_SOURCES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D,
    'the catalog traces this refusal to L212',
  ).toStrictEqual([212]);
  expect(
    SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S,
    'the transaction browse stays singular, so the two must not converge',
  ).not.toBe(refusal);
}

/**
 * Proves an unusable action code is refused in the program's own words, against the marked row.
 *
 * Assumptions: the refusal is raised on the ENTER turn rather than as the character is typed, because
 * the reference evaluates the marked cell inside `PROCESS-ENTER-KEY` at L189 to L216 -- a screen that
 * refused on keystroke would answer before the operator committed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAnActionCodeOutsideTheDomain(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await user.type(actionCell(FIRST_ROW_USER_ID), 'X');
  await pressPfKey(user, 'ENTER');

  expect(messageBand()).toHaveTextContent(
    PROGRAM_MESSAGES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D,
  );
}

/**
 * Proves a marked cell carrying an unusable code is shown in error, with its refusal bound to it.
 *
 * ⚠️ Refactoring Rationale: the highlight is driven ENTIRELY by the response and the turn, with no
 * re-entry discriminator behind it. `app/cpy/CSSETATY.cpy` L17 to L27 gates the red attribute on
 * `CDEMO-PGM-REENTER` as well as on the field's flag, but AAP section 0.7.1 removes that discriminator
 * completely -- a stateless handler has no first-entry-versus-re-entry distinction to make -- so the
 * target renders the error state from what the turn produced. `ui/src/layout/MessageBand.tsx` refuses
 * a problem document by design, which is why the mapping from failure to field state is the screen's.
 *
 * Assumptions: the asterisk is asserted ABSENT here rather than present, and that is the copybook's
 * own rule rather than a gap. L23 to L26 write the literal `'*'` into the field only in the BLANK
 * case; a cell carrying `'X'` is not blank, it is populated and unusable, so the marker must not
 * appear. Asserting its absence is what proves the two conditions are still distinguished.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksTheRefusedCellWithoutTheBlankAsterisk(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await user.type(actionCell(FIRST_ROW_USER_ID), 'X');
  await pressPfKey(user, 'ENTER');

  const cell = actionCell(FIRST_ROW_USER_ID);
  expect(cell, 'a refused entry is announced as invalid').toHaveAttribute('aria-invalid', 'true');
  /*
   * WHY : ⚠️ Assumptions: the help element is located through the cell's OWN `aria-describedby` rather
   *       than by its text, and the reason is a real duplication the first run of this case exposed:
   *       the sentence is painted TWICE on a refused turn, once as the field help and once in the
   *       row-23 band, which is faithful -- the reference moves it into `WS-MESSAGE` and highlights
   *       the field. A text query therefore matches two elements. Following the binding asserts the
   *       stronger property anyway: not that the sentence is somewhere on the screen, but that it is
   *       the accessible description OF THIS CELL, which is what an assistive technology announces.
   */
  const describedBy = cell.getAttribute('aria-describedby') ?? '';
  expect(describedBy, 'the refusal must be bound to the cell it refuses').not.toBe('');
  const help = document.getElementById(describedBy.split(' ')[0] ?? '');
  expect(help, 'the bound description must exist in the document').not.toBeNull();
  expect(help?.textContent).toBe(
    PROGRAM_MESSAGES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D,
  );
  expect(
    help?.textContent ?? '',
    "CSSETATY writes '*' only for a BLANK field, and this one carries a character",
  ).not.toContain(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * Proves a row nobody marked shows no error at all.
 *
 * Assumptions: this is the negative half of the highlight contract and it cannot be inferred from the
 * positive half. A screen that marked every cell whenever any cell was refused would satisfy every
 * assertion above while highlighting nine rows the operator never touched.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function leavesUnmarkedRowsWithoutAnError(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await user.type(actionCell(FIRST_ROW_USER_ID), 'X');
  await pressPfKey(user, 'ENTER');

  expect(
    actionCell(LAST_ROW_USER_ID),
    'a cell nobody marked carries no error state',
  ).not.toHaveAttribute('aria-invalid', 'true');
  expect(
    searchControl(),
    'the search control was not named in the refusal, so it shows no error',
  ).not.toHaveAttribute('aria-invalid', 'true');
}

/**
 * Proves both codes are admitted, in either case, and nothing else is.
 *
 * ⚠️ Assumptions: the comparison is case-INSENSITIVE because the reference's own arms are.
 * `app/cbl/COUSR00C.cbl` L190 and L191 read `WHEN 'U'` and `WHEN 'u'`, and L200 and L201 read
 * `WHEN 'D'` and `WHEN 'd'`, so a lower-case entry is accepted there and must be accepted here. This
 * is easy to lose: a target that compared only the upper-case letters would refuse an entry the
 * terminal took, and the refusal would look like correct validation.
 *
 * Assumptions: the domain is exercised through the screen's own exported resolver rather than through
 * ten rendered turns, because the resolver is where the domain is decided and a pure call asserts the
 * whole domain -- including the negative -- without ten renders to reach it.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function admitsBothCodesInEitherCase(): void {
  expect(toUserListRowActionCode('U'), 'L190 accepts the upper-case update code').toBe('U');
  expect(toUserListRowActionCode('u'), 'L191 accepts the lower-case update code').toBe('U');
  expect(toUserListRowActionCode('D'), 'L200 accepts the upper-case delete code').toBe('D');
  expect(toUserListRowActionCode('d'), 'L201 accepts the lower-case delete code').toBe('D');
  expect(USER_LIST_ROW_ACTION_CODES.update, 'the update code is U').toBe('U');
  expect(USER_LIST_ROW_ACTION_CODES.delete, 'the delete code is D').toBe('D');

  for (const outside of ['S', 'X', '1', ' ', 'UD']) {
    expect(
      toUserListRowActionCode(outside),
      `'${outside}' is outside this screen's two-letter domain`,
    ).toBeNull();
  }
}

/**
 * Proves the FIRST marked row wins and later entries are ignored without being reported.
 *
 * ⚠️ Assumptions: `PROCESS-ENTER-KEY` at L149 to L184 is one `EVALUATE TRUE` whose ten arms test
 * `SEL0001I` through `SEL0010I` in display order, and COBOL ends an `EVALUATE` at its first matching
 * arm -- so a page carrying entries beside rows 2 and 5 acts on row 2 and never inspects row 5.
 *
 * ⚠️ Assumptions: a second entry is NOT refused, and refusing it would be an invention.
 * `COUSR00C` keeps no count of marked rows and declares no multi-selection sentence, unlike the
 * transaction-type browse which counts and answers `'Please select only 1 action'`. Adding a refusal
 * here would put a message on screen that the reference cannot emit.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function actsOnTheFirstMarkedRowOnly(): void {
  const rows = FULL_PAGE_USER_IDS.map(row);
  const secondRowId = FULL_PAGE_USER_IDS[1] ?? FIRST_ROW_USER_ID;
  const fifthRowId = FULL_PAGE_USER_IDS[4] ?? FIRST_ROW_USER_ID;

  const selection = reduceUserRowSelection(rows, { [secondRowId]: 'U', [fifthRowId]: 'D' });

  expect(selection.userId, 'the earlier row in display order wins').toBe(secondRowId);
  expect(selection.code, "and it carries that row's own code").toBe('U');
  expect(selection.message, 'a later entry is ignored rather than refused').toBeNull();
}

/**
 * Proves a page with no entry at all requests nothing and refuses nothing.
 *
 * Assumptions: the arms test `NOT = SPACES AND LOW-VALUES`, so an untouched cell is skipped rather
 * than refused, and L186 to L188 guard the code evaluation on both carried fields being non-blank --
 * so an unmarked page never produces the refusal sentence.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function requestsNothingFromAnUnmarkedPage(): void {
  const selection = reduceUserRowSelection(FULL_PAGE_USER_IDS.map(row), {});

  expect(selection.userId, 'no row was marked').toBeNull();
  expect(selection.code, 'so no action is requested').toBeNull();
  expect(selection.message, 'and an unmarked page is not an error').toBeNull();
}

/**
 * Proves the row-21 prompt that defines the two codes to the operator is painted verbatim.
 *
 * ⚠️ Assumptions: the mapset source reads `Type ''U'' to Update or ''D'' to Delete a User from the
 * list` at `ATTRB=(ASKIP,BRT) COLOR=NEUTRAL LENGTH=56 POS=(21,12)`, and the DOUBLED apostrophes are
 * BMS literal escaping rather than content -- a terminal displays one apostrophe each. Reproducing
 * the doubled form would be a transcription defect that this tree's byte-exactness rule would then
 * protect, so the single-apostrophe form is the faithful one.
 *
 * ⚠️ Assumptions: the match is made with the harness's NON-COLLAPSING matcher rather than a substring
 * check, because internal whitespace here is content rather than formatting -- several baseline
 * strings carry doubled spaces deliberately, and a collapsing matcher would accept a screen that
 * emitted one where the mapset has two.
 *
 * Assumptions: the prompt is painted on every turn rather than only when a cell is refused, because
 * it is a static `INITIAL=` field on the map and a map is sent whole. It is also what makes the
 * one-character cells labelable by a code rather than by a word.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheRowActionPromptVerbatim(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  expect(expectVerbatimMessage(USER_LIST_LABELS.rowActionPrompt)).toBeInTheDocument();
  expect(
    USER_LIST_LABELS.rowActionPrompt,
    'BMS doubled apostrophes are escaping, so the painted form carries single ones',
  ).not.toContain("''");
  expect(USER_LIST_LABELS.rowActionPrompt, 'and the prompt names both codes the cells admit').toBe(
    "Type 'U' to Update or 'D' to Delete a User from the list",
  );
}

/**
 * Proves the field-error contract is expressible in the shape the services actually send.
 *
 * Assumptions: the two states are asserted as DISTINCT because `app/cpy/CSSETATY.cpy` gives them
 * different rendering obligations -- both colour the field, and only the blank one additionally
 * writes the asterisk at L24. A single state would collapse that difference.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function distinguishesTheBlankAndUnusableFieldStates(): void {
  const unusable: FieldError = {
    field: 'selection',
    state: 'NOT_OK',
    message: PROGRAM_MESSAGES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D,
  };
  const blank: FieldError = { ...unusable, state: 'BLANK' };

  expect(unusable.state, 'a populated but unusable entry is NOT_OK').toBe('NOT_OK');
  expect(blank.state, 'an empty entry is BLANK, which additionally earns the marker').toBe('BLANK');
  expect(FIELD_ERROR_TOKENS.blankMarker, "CSSETATY L24 writes the literal '*'").toBe('*');
}

/** Registers the action-code domain cases. */
function actionCodeCases(): void {
  it('keeps its own selection vocabulary', keepsItsOwnSelectionVocabulary);
  it('admits both codes in either case', admitsBothCodesInEitherCase);
  it('acts on the first marked row only', actsOnTheFirstMarkedRowOnly);
  it('requests nothing from an unmarked page', requestsNothingFromAnUnmarkedPage);
  it('paints the row action prompt verbatim', paintsTheRowActionPromptVerbatim);
  it('refuses an action code outside the domain', refusesAnActionCodeOutsideTheDomain);
  it(
    'marks the refused cell without the blank asterisk',
    marksTheRefusedCellWithoutTheBlankAsterisk,
  );
  it('leaves unmarked rows without an error', leavesUnmarkedRowsWithoutAnError);
  it(
    'distinguishes the blank and unusable field states',
    distinguishesTheBlankAndUnusableFieldStates,
  );
}

describe('the user browse admits only its own two action codes', actionCodeCases);

/*
 * ---------------------------------------------------------------------------
 * The four-key workflow
 * ---------------------------------------------------------------------------
 */

/**
 * Proves the legend paints exactly the four keys the mapset names, with their measured labels.
 *
 * Assumptions: the row-24 field of `app/bms/COUSR00.bms` is `COLOR=YELLOW LENGTH=48 POS=(24,1)` and
 * paints `ENTER=Continue  F3=Back  F7=Backward  F8=Forward`, with two spaces between each pair, the
 * literal continued across a line break in the source. Those four descriptors are exactly the four
 * attention identifiers `app/cbl/COUSR00C.cbl` L121 to L133 dispatches.
 *
 * Assumptions: the backward and forward labels are compared against the SHARED pair rather than
 * retyped, because this mapset's `F7=Backward` and `F8=Forward` are byte-identical to the uniform
 * ones, and a second transcription would only create a way for the two to disagree.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheFourMeasuredKeyLabels(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  expect(USER_LIST_KEY_LABELS.ENTER).toBe('ENTER=Continue');
  expect(USER_LIST_KEY_LABELS.PFK03).toBe('F3=Back');
  expect(USER_LIST_KEY_LABELS.PFK07).toBe(UNIFORM_PF_KEY_LABELS.PFK07);
  expect(USER_LIST_KEY_LABELS.PFK08).toBe(UNIFORM_PF_KEY_LABELS.PFK08);
  for (const label of Object.values(USER_LIST_KEY_LABELS)) {
    expect(
      keyControl(label),
      'each measured descriptor is painted as a control',
    ).toBeInTheDocument();
  }
}

/**
 * ⚠️ Proves no key outside the measured four is offered.
 *
 * ⚠️ Assumptions: `COUSR00C` dispatches a direct `EVALUATE EIBAID` over exactly `DFHENTER`, `DFHPF3`,
 * `DFHPF7` and `DFHPF8`, answering everything else through its `WHEN OTHER` arm at L133 to L136.
 * There is therefore no Clear key on this browse screen and no save or cancel key either. The key bar
 * takes a per-screen descriptor array precisely because key sets differ this much between mapsets --
 * the uniform label set publishes an `F4=Clear` that this screen must NOT pick up.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function offersNoKeyBeyondTheMeasuredFour(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    Object.keys(USER_LIST_KEY_LABELS).sort(),
    'exactly the four AIDs COUSR00C evaluates',
  ).toStrictEqual(['ENTER', 'PFK03', 'PFK07', 'PFK08']);
  expect(
    screen.queryByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK04 }),
    'this mapset paints no Clear key',
  ).not.toBeInTheDocument();
}

/**
 * ⚠️ Proves this browse emphasises NOTHING, because none of its four keys changes stored state.
 *
 * ⚠️ Refactoring Rationale: this case previously asserted the opposite and therefore pinned a defect.
 * It required `ENTER` to render primary on the ground that the design system's emphasis mapping is keyed
 * by the ATTENTION IDENTIFIER, which lists `ENTER` and `PFK05`. That table cannot be right per-screen:
 * the same `PFK05` it emphasises is `F5=Save` on one mapset and `F5=Delete` on another, and a measured
 * pass found the delete painted in benign primary blue because of it. The screens now declare what each
 * key DOES and the bar paints from that, so this browse -- whose four keys transfer, return and reposition
 * and whose `app/cbl/COUSR00C.cbl` L121-L133 dispatch writes nothing at all -- correctly carries no
 * emphasised control. An emphasis that appears on every screen distinguishes nothing.
 *
 * ⚠️ Assumptions: all FOUR controls are asserted, not just the two the old case named. The property is
 * that the bar has no primary member, and a case that checked Enter and PF3 alone would pass while a
 * paging key was emphasised.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function emphasisesTheCommitKeyOnly(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  /*
   * WHY : ⚠️ Assumptions: the AID fallback is asserted to STILL LIST `ENTER`, which is what makes the
   *       assertions below evidence that this screen's declaration OVERRODE it rather than that the
   *       fallback changed. `ui/src/layout/PfKeyBar.tsx` keeps the list deliberately unchanged so every
   *       screen that has not classified its keys renders exactly as it did; if a future change removed
   *       it, this case would stop being evidence of anything and this line is what fails.
   */
  expect(PRIMARY_ACTION_AIDS, 'the AID fallback still lists ENTER').toContain('ENTER');
  expect(PRIMARY_ACTION_AIDS, 'and still omits PF3').not.toContain('PFK03');

  for (const label of Object.values(USER_LIST_KEY_LABELS)) {
    expect(keyControl(label).className, label).toContain('ant-btn-default');
    expect(keyControl(label).className, label).not.toContain('ant-btn-primary');

    /*
     * WHY : Assumptions: the absence of the dangerous variant is asserted too. A browse offers no
     *       irreversible action, so a key here painted as one would misdescribe the screen as strongly
     *       as an emphasised read key does -- and `'read-only'`, `'mutating'` and `'destructive'` all
     *       resolve to different paints, so the classification is only proven by pinning which one.
     */
    expect(keyControl(label).className, label).not.toContain('ant-btn-dangerous');
  }
}

/**
 * Proves the forward step works from a real key press AND from the equivalent control click.
 *
 * ⚠️ Alternatives Considered: driving the workflow through the controls alone, which is the easier
 * thing to write. Rejected because the contract is a KEYBOARD contract: the 3270 original had no
 * pointer at all, so the key bindings are the fidelity-bearing path and the buttons are the additive
 * one. A suite that only clicked would pass in full while every keyboard binding in the application
 * was broken -- and an operator who knows the workflow would find it gone. Asserting both is what
 * keeps the two activation paths in step, which is why the component funnels them through one
 * dispatch.
 *
 * Assumptions: the two activations are asserted in one case rather than two, because the property
 * under test is their EQUIVALENCE, and equivalence measured across two cases would not fail if one
 * path silently stopped dispatching while the other still worked.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function pagesForwardByBothKeyAndControl(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK08');
  await waitForReadCount(2);

  await user.click(keyControl(USER_LIST_KEY_LABELS.PFK08));
  await waitForReadCount(3);

  expect(listing).toHaveBeenNthCalledWith(2, { cursor: TRAILING_CURSOR, direction: 'next' });
  expect(listing).toHaveBeenNthCalledWith(3, { cursor: TRAILING_CURSOR, direction: 'next' });
}

/**
 * Proves an unmapped key is answered with the shared invalid-key sentence at its declared width.
 *
 * Assumptions: `COUSR00C` L135 moves `CCDA-MSG-INVALID-KEY` on its `WHEN OTHER` arm, so this screen is
 * one of the programs that emits it. The constant is declared `PIC X(50)` at `app/cpy/CSMSG01Y.cpy`
 * L20 to L21 with a value 49 characters long, so the catalog stores the text and the declared width
 * separately -- the runtime field is blank-padded and the source literal is not, and storing the
 * padded form would fabricate bytes the copybook does not contain.
 *
 * Assumptions: the key pressed is one the hook resolves but this screen does not bind, so the refusal
 * comes from the screen's own unmapped arm rather than from the browser ignoring an unknown key.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function answersAnUnmappedKeyWithTheSharedSentence(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK05');

  /*
   * WHY : ⚠️ Assumptions: the RENDERED form is the trimmed literal while the CATALOG holds the padded
   *       one, and the difference is the contract rather than a discrepancy to be smoothed over. The
   *       copybook literal is 49 characters carrying nine trailing spaces inside a `PIC X(50)` field,
   *       so the catalog stores it exactly as the copybook writes it -- that is what transformation
   *       rule T8 fidelity means for a padded constant. Trailing padding is introduced by the fixed
   *       field width rather than by the sentence, so the band renders the content and the browser
   *       would collapse the padding anyway. Both halves are asserted: the screen paints the content,
   *       and the catalog still holds the source bytes.
   */
  expect(messageBand()).toHaveTextContent(INVALID_KEY_PRESSED.trim());
  expect(INVALID_KEY_PRESSED, 'the catalog keeps the copybook padding intact').toHaveLength(49);
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth, 'CSMSG01Y L20 declares PIC X(50)').toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.source.lines, 'the catalog traces it to L21').toStrictEqual([
    21,
  ]);
}

/**
 * ⚠️ The browse ANNOUNCES an outstanding page turn, not only paints a spinner over the rows.
 *
 * ⚠️ Purpose: a review found no live region anywhere naming a wait and `aria-busy` on no control, so a
 * page turn was reported by a visual treatment alone. This browse replaces its whole row set on every
 * turn, which is the change an operator most needs told about, and an operator who cannot see the
 * spinner was told nothing.
 *
 * ⚠️ Assumptions: the announcement is asserted from INSIDE the held turn and its emptiness after it,
 * because the region is always mounted -- a live region has to be in the accessibility tree before its
 * content changes for the first change to be announced, so an absent region would be the defect and an
 * empty one is the resting state.
 *
 * Assumptions: the read is held with a promise this case resolves itself rather than with a timer, so
 * the in-flight window is bounded by the assertions inside it rather than by a duration.
 * @returns {Promise<void>} Resolves once the held turn has been released and its page has landed.
 */
async function announcesAnOutstandingPageTurn(): Promise<void> {
  listing.mockResolvedValueOnce(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID),
    'nothing is outstanding once the first page has landed',
  ).toHaveTextContent('');

  /**
   * Placeholder resolver, replaced the moment the held promise hands over its own.
   *
   * Assumptions: an initialiser is supplied rather than declaring the binding possibly-undefined,
   * because the promise executor runs synchronously inside the constructor below and therefore always
   * replaces it before any code can call it.
   * @returns {void} Nothing; it is never the resolver that runs.
   */
  function releaseNothing(): void {
    // Assumptions: an empty body is the whole implementation; see the doc block above.
  }

  let releasePage: (page: PageResponse<UserSummary>) => void = releaseNothing;

  listing.mockReturnValueOnce(
    new Promise<PageResponse<UserSummary>>(
      /**
       * Captures the resolver so the case controls when the next page lands.
       * @param {(page: PageResponse<UserSummary>) => void} resolve - The promise's own resolver.
       * @returns {void} Nothing; the resolver is retained for later.
       */
      (resolve: (page: PageResponse<UserSummary>) => void): void => {
        releasePage = resolve;
      },
    ),
  );

  await pressPfKey(user, 'PFK08');
  await waitForReadCount(2);

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);

  releasePage(fullPageAtTheEnd());
  await waitFor(
    /**
     * Waits until the held turn has settled, so no state escapes the case.
     * @returns {void} Nothing; the expectation throws until the region has fallen silent.
     */
    (): void => {
      expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
    },
  );
}

/** Registers the function-key cases. */
function functionKeyCases(): void {
  it('paints the four measured key labels', paintsTheFourMeasuredKeyLabels);
  it('offers no key beyond the measured four', offersNoKeyBeyondTheMeasuredFour);
  it(
    'emphasises no key, because none of the four changes stored state',
    emphasisesTheCommitKeyOnly,
  );
  it('pages forward by both key and control', pagesForwardByBothKeyAndControl);
  it('answers an unmapped key with the shared sentence', answersAnUnmappedKeyWithTheSharedSentence);
  it('announces an outstanding page turn', announcesAnOutstandingPageTurn);
}

describe('the user browse binds exactly the four keys its mapset paints', functionKeyCases);

/*
 * ---------------------------------------------------------------------------
 * ⚠️ The administrative gate, and the self-grant negative
 * ---------------------------------------------------------------------------
 */

/**
 * Renders the browse behind the real administrative guard.
 *
 * ⚠️ Assumptions: the guard is the REAL one rather than a stand-in, because a stand-in would prove
 * only that this file can write a conditional. `ui/src/router.tsx` wraps the administrative subtree
 * in exactly this component, so composing it here reproduces the tree the router builds for `/users`.
 *
 * Assumptions: the screen itself holds no group check and none is looked for. Its module overview
 * records that it deliberately never imports the identity hook, so that the authorization decision has
 * one implementation -- two copies of one decision being how the two come to disagree.
 * @returns {Promise<UserEvent>} The operator bound to the rendered document.
 */
async function renderGuardedBrowse(): Promise<UserEvent> {
  const { user } = await renderInAppShell(
    <RequireAdmin>
      <UserListScreen />
    </RequireAdmin>,
    { initialEntries: [USER_LIST_PATH], routePath: USER_LIST_PATH },
  );
  return user;
}

/**
 * Proves the route table still classifies this path as administrative.
 *
 * Assumptions: the declarative classification is asserted as well as the runtime refusal, because the
 * two can drift apart -- a route moved out of the administrative subtree would still satisfy a runtime
 * test that mounted the guard by hand. `app/cpy/COADM02Y.cpy` L22 declares six live administrative
 * options and L28 names this one `'User List (Security)'`, reaching `'COUSR00C'` at L29.
 *
 * Assumptions: the administrative option record at L55 to L59 carries NO user-type field at all,
 * unlike the main-menu option table, because reaching the administrative menu is itself the
 * authorization. So the gate is a property of the ROUTE rather than of a per-option flag.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function classifiesTheBrowseAsAdministrative(): void {
  const entry = ROUTE_TABLE.find(
    /**
     * Finds the route table entry for this browse.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One route table entry.
     * @returns {boolean} `true` when the entry is this browse's.
     */
    (candidate: (typeof ROUTE_TABLE)[number]): boolean => candidate.path === USER_LIST_PATH,
  );

  expect(entry, 'the browse must be present in the route table').toBeDefined();
  expect(entry?.access, 'COADM02Y option 1 makes this an administrative route').toBe(
    'administrative',
  );
  expect(entry?.program, 'the route carries its source program for traceability').toBe(
    USER_LIST_PROGRAM_NAME,
  );
}

/**
 * Proves an administrator reaches the browse, and can page and mark a row.
 *
 * Assumptions: the session is established through the shared identity helper, which mints a token
 * carrying the group and drives the real sign-on -- the only path to authority there is.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function admitsAnAdministrator(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const session = await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  try {
    const user = await renderGuardedBrowse();
    await waitForRow(FIRST_ROW_USER_ID);

    expect(keyControl(USER_LIST_KEY_LABELS.PFK08), 'an administrator may page').toBeEnabled();
    await user.type(actionCell(FIRST_ROW_USER_ID), 'U');
    expect(actionCell(FIRST_ROW_USER_ID), 'an administrator may mark a row').toHaveValue('U');
  } finally {
    session.unmount();
  }
}

/**
 * ⚠️ Proves a signed-on non-administrator is refused, and that the listing is never called.
 *
 * ⚠️ Assumptions: the refusal is asserted TOGETHER with the read never being issued, and the second
 * half is the one that matters. A guard that rendered a refusal over a screen which had already
 * fetched the user list would look identical to an operator while having disclosed the security file
 * to a caller with no right to it. Only the spy proves the data was never asked for.
 *
 * Assumptions: an authenticated non-administrator is shown a refusal rather than redirected to
 * sign-on, which is the guard's documented choice -- they are signed on correctly, so sending them to
 * sign on would invite them to fix something that is not broken.
 *
 * Assumptions: the denial sentence carries a TRAILING SPACE and it is compared trimmed, for the same
 * reason the invalid-key sentence is: the trailing byte is part of the transcribed literal and the
 * renderer paints the content. The untrimmed value is asserted separately so the byte is not lost.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesANonAdministratorWithoutReadingAnything(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });
  try {
    await renderGuardedBrowse();

    await waitFor(
      /**
       * Asserts the refusal surface has been rendered.
       * @returns {void} Nothing; the expectation throws until it holds.
       */
      (): void => {
        expect(screen.getByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeInTheDocument();
      },
    );
    expect(
      listing,
      'a refused caller must never cause the security file to be read',
    ).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('table'),
      'no browse table is rendered behind the refusal',
    ).not.toBeInTheDocument();
    expect(
      ACCESS_DENIED_ADMIN_ONLY,
      'COMEN01C L140 ends this sentence with a space, which the catalog keeps',
    ).toBe('No access - Admin Only option... ');
  } finally {
    session.unmount();
  }
}

/**
 * ⚠️ Proves no client-side value can grant administrative access.
 *
 * ⚠️ Refactoring Rationale: this negative is the whole point of the migration's identity change, and
 * it is a genuine security improvement rather than a port. In the baseline, `CDEMO-USER-TYPE PIC
 * X(01)` travelled in the `DFHCOMMAREA` -- `app/cpy/COCOM01Y.cpy` L26 with its `88` values `'A'` and
 * `'U'` at L27 and L28 -- which is storage the CLIENT echoes back between pseudo-conversational
 * turns, so a client could in principle assert its own user type. In the target the authority comes
 * from the signed `cognito:groups` claim and the client cannot assert anything at all. Asserting the
 * negative is what proves the property; the positive cases above would pass either way.
 *
 * ⚠️ Assumptions: there is NO group setter to misuse and no context provider to override.
 * `ui/src/hooks/useAuth.ts` publishes the two group names and the claim name but deliberately
 * publishes no setter, and it holds its session in module scope read through an external store rather
 * than behind a provider -- so this case has nothing to inject even if it tried. What it does instead
 * is the strongest available demonstration: hold a non-administrative session, write the
 * administrative group name into the DOM the way a tampering client would, and show the refusal
 * stands.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function cannotBeGrantedAdminFromTheClient(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });
  try {
    await renderGuardedBrowse();
    await waitFor(
      /**
       * Asserts the refusal surface has been rendered before tampering begins.
       * @returns {void} Nothing; the expectation throws until it holds.
       */
      (): void => {
        expect(screen.getByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeInTheDocument();
      },
    );

    /*
     * WHY : Assumptions: the tampering is a DOM mutation rather than a call into the hook, because a
     *       call is not available -- there is no setter to call. A dataset attribute carrying the
     *       administrative group name is what a client-side attempt would actually look like, and it
     *       must have no effect whatever on a decision made from a signed claim.
     */
    document.body.dataset.cognitoGroups = CARDDEMO_ADMIN_GROUP;
    try {
      expect(
        screen.getByText(ACCESS_DENIED_ADMIN_ONLY.trim()),
        'a client-side value must not turn a refusal into access',
      ).toBeInTheDocument();
      expect(listing, 'and it must still not cause a read').not.toHaveBeenCalled();
    } finally {
      delete document.body.dataset.cognitoGroups;
    }
  } finally {
    session.unmount();
  }
}

/** Registers the administrative-gate cases. */
function administrativeGateCases(): void {
  it('classifies the browse as administrative', classifiesTheBrowseAsAdministrative);
  it('admits an administrator', admitsAnAdministrator);
  it(
    'refuses a non-administrator without reading anything',
    refusesANonAdministratorWithoutReadingAnything,
  );
  it('cannot be granted admin from the client', cannotBeGrantedAdminFromTheClient);
}

describe(
  'the user browse is reachable only with the administrative claim',
  administrativeGateCases,
);

/*
 * ---------------------------------------------------------------------------
 * Navigation, identity in the request, and disclosure
 * ---------------------------------------------------------------------------
 */

/**
 * Proves the two selection targets are the routes the route table declares.
 *
 * ⚠️ Assumptions: the builders are compared against the route TEMPLATES rather than against literals,
 * because the property under test is that they agree -- a builder producing a path no route matches
 * would navigate to the not-found surface, which is exactly what a hand-written literal invites. The
 * selector segment is named `:id` in both templates, so a concrete path is the template with that one
 * segment substituted.
 *
 * Assumptions: `'U'` reaches the maintenance screen and `'D'` the deletion screen, transcribing
 * `app/cbl/COUSR00C.cbl` L192, which transfers to `'COUSR02C'`, and L202, which transfers to
 * `'COUSR03C'`.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function buildsTheTwoSelectionTargetsFromTheRouteTable(): void {
  expect(USER_UPDATE_PATH, 'the update route names its selector :id').toBe('/users/:id/edit');
  expect(USER_DELETE_PATH, 'the deletion route names its selector :id').toBe('/users/:id/delete');
  expect(userEditPath(FIRST_ROW_USER_ID), "'U' transfers to COUSR02C's route").toBe(
    USER_UPDATE_PATH.replace(':id', FIRST_ROW_USER_ID),
  );
  expect(userDeletePath(FIRST_ROW_USER_ID), "'D' transfers to COUSR03C's route").toBe(
    USER_DELETE_PATH.replace(':id', FIRST_ROW_USER_ID),
  );
}

/**
 * ⚠️ Proves the selected identifier travels in the PATH, not in a carried session field.
 *
 * ⚠️ Refactoring Rationale: the reference carried the choice in `CDEMO-CU00-USR-SELECTED`, a
 * COMMAREA field the client echoed back, and AAP section 0.7.1 replaces it with a path parameter so
 * the receiving request is self-describing and can be authorized on its own terms rather than on a
 * value the client asserted. The identifier therefore appears in the built path and the path is the
 * whole carrier.
 *
 * Assumptions: the identifier is taken from the ROW rather than from any screen state, so the built
 * path is asserted against the row's own identifier.
 * @returns {void} Nothing; the assertions either hold or fail the case.
 */
function carriesTheSelectedIdentifierInThePath(): void {
  const target = userEditPath(LAST_ROW_USER_ID);

  expect(target, 'the identifier is a path segment').toContain(`/${LAST_ROW_USER_ID}/`);
  expect(target.startsWith(`${USER_LIST_PATH}/`), 'and it hangs off the browse route').toBe(true);
}

/** Text a transfer-target sentinel paints so the arm that was taken is identifiable. */
const TRANSFER_SENTINELS = {
  /** Painted by the route `'U'` must reach. */
  update: 'reached the user maintenance route',
  /** Painted by the route `'D'` must reach. */
  delete: 'reached the user deletion route',
} as const;

/**
 * Renders one transfer-target sentinel that also reports the identifier it was reached with.
 *
 * ⚠️ Assumptions: the sentinel reads the route parameter through the router's own hook rather than
 * being handed the identifier, because the property under test is that the identifier travels in the
 * PATH. A sentinel given the value some other way would render identically while proving nothing.
 * @param {{ readonly label: string }} props - The component's props.
 * @param {string} props.label - Which target this sentinel stands for.
 * @returns {ReactElement} The sentinel text followed by the identifier the route carried.
 */
function TransferSentinel({ label }: { readonly label: string }): ReactElement {
  const { id } = useParams();
  return (
    <span>
      {label} {id ?? ''}
    </span>
  );
}

/**
 * Renders the browse alongside real routes for both of its transfer targets.
 *
 * ⚠️ Assumptions: the route tree is composed here and handed to the harness UNMOUNTED at a pattern,
 * which is why `routePath` is not passed -- the harness renders the subject directly under its memory
 * router when no pattern is given, so a caller may supply its own `Routes`. That is what makes the
 * destination observable: with only the browse mounted, a transfer leaves an empty document and any
 * two destinations look alike.
 *
 * Assumptions: the shell is deliberately NOT used for these cases. The screen publishes to it through
 * a module-level store rather than a context, so it renders correctly without one, and Enter is
 * dispatched from the document by the key hook rather than by a control in the legend -- so the
 * keyboard turn under test works exactly as it does behind the shell.
 *
 * Assumptions: the patterns are the route table's own constants, so a template renamed in the router
 * moves these sentinels with it instead of leaving them matching a path the application abandoned.
 * @returns {Promise<UserEvent>} The operator bound to the rendered document.
 */
async function renderBrowseWithTransferTargets(): Promise<UserEvent> {
  const { user } = await renderWithProviders(
    <Routes>
      <Route path={USER_LIST_PATH} element={<UserListScreen />} />
      <Route
        path={USER_UPDATE_PATH}
        element={<TransferSentinel label={TRANSFER_SENTINELS.update} />}
      />
      <Route
        path={USER_DELETE_PATH}
        element={<TransferSentinel label={TRANSFER_SENTINELS.delete} />}
      />
    </Routes>,
    { initialEntries: [USER_LIST_PATH] },
  );
  return user;
}

/**
 * Proves the update code reaches the maintenance route, carrying the row's identifier in the path.
 *
 * Assumptions: transcribes `app/cbl/COUSR00C.cbl` L192, which moves `'COUSR02C'` into the transfer
 * target before its `XCTL`. AAP rule T5 turns that `XCTL` into a client-side route change, so the
 * observable equivalent is the destination route rendering.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function transfersTheUpdateCodeToTheMaintenanceRoute(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowseWithTransferTargets();
  await waitForRow(FIRST_ROW_USER_ID);

  await user.type(actionCell(FIRST_ROW_USER_ID), 'U');
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Asserts the maintenance route has been reached with this row's identifier.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(
        screen.getByText(`${TRANSFER_SENTINELS.update} ${FIRST_ROW_USER_ID}`),
      ).toBeInTheDocument();
    },
  );
  expect(
    screen.queryByText(TRANSFER_SENTINELS.delete, { exact: false }),
    'the update code must not reach the deletion route',
  ).not.toBeInTheDocument();
}

/**
 * Proves the delete code reaches the DELETION route and not the maintenance one.
 *
 * ⚠️ Assumptions: this case is what distinguishes the two arms, and it cannot be inferred from the
 * one above. `app/cbl/COUSR00C.cbl` L202 moves `'COUSR03C'` on the delete arm, a different program
 * from L192's -- and a screen that wired both codes to the update route would satisfy every other
 * assertion in this file, including the whole action-code domain, while quietly making deletion
 * unreachable.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function transfersTheDeleteCodeToTheDeletionRoute(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowseWithTransferTargets();
  await waitForRow(FIRST_ROW_USER_ID);

  await user.type(actionCell(LAST_ROW_USER_ID), 'D');
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Asserts the deletion route has been reached with the marked row's identifier.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(
        screen.getByText(`${TRANSFER_SENTINELS.delete} ${LAST_ROW_USER_ID}`),
      ).toBeInTheDocument();
    },
  );
  expect(
    screen.queryByText(TRANSFER_SENTINELS.update, { exact: false }),
    'the delete code must not reach the maintenance route',
  ).not.toBeInTheDocument();
}

/**
 * Proves a marked row leaves the browse rather than re-reading the page it abandons.
 *
 * Assumptions: the row action is settled BEFORE the browse is repositioned, which is the reference's
 * own order -- `PROCESS-ENTER-KEY` evaluates the selection and transfers control at L189 to L216, and
 * only a turn that transferred nowhere falls through to the key read at L218. So a committed
 * selection must issue no further read.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function leavesTheBrowseOnACommittedSelection(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await user.type(actionCell(FIRST_ROW_USER_ID), 'U');
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Asserts the browse table is no longer rendered.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    },
  );
  expect(listing, 'a transfer re-reads nothing').toHaveBeenCalledTimes(1);
}

/**
 * ⚠️ Proves the exit key returns to the ADMINISTRATIVE menu and not to the main menu.
 *
 * ⚠️ Assumptions: `app/cbl/COUSR00C.cbl` L126 moves `'COADM01C'` into the transfer target on its
 * `DFHPF3` arm, which is the ADMINISTRATIVE menu program -- not `'COMEN01C'`, the main menu. This
 * screen is reached from the administrative menu as option 1, so returning to the main menu would
 * strand an administrator one level above where they came from. The distinction is easy to get wrong
 * because most screens in this migration do exit to the main menu.
 *
 * Assumptions: the transfer is observed as the browse ceasing to be rendered, because the harness
 * mounts this screen at its own route alone -- a client-side route change away from it therefore
 * leaves no matched route. `EXEC CICS XCTL` becoming a client-side route change is AAP rule T5, and
 * no server-side "next program" field exists to inspect instead.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function exitsToTheAdministrativeMenu(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  await pressPfKey(user, 'PFK03');

  await waitFor(
    /**
     * Asserts the browse has been navigated away from.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    },
  );
}

/**
 * ⚠️ Proves no credential is rendered anywhere on the browse.
 *
 * ⚠️ Refactoring Rationale: this is the single place the migration explicitly DECLINES parity, and a
 * list screen is exactly where a leaked credential column would be least likely to be noticed. The
 * baseline record carries `SEC-USR-PWD PIC X(08)`, an eight-character PLAINTEXT password, at
 * `app/cpy/CSUSR01Y.cpy` L21, and `app/cbl/COSGN00C.cbl` compares it directly; AAP sections 0.5.1.2
 * and 0.7.8 do not carry the field forward at all, so `auth.users` has no password column and
 * `UserSummary` declares no such member. There is consequently nothing here to withhold -- the
 * assertion is that nothing appeared, which is a different and stronger property than masking.
 *
 * Assumptions: the row type is checked as well as the rendered document, because a column can only
 * leak what the type admits. Four members and no fifth is what makes the render assertion sufficient.
 *
 * Assumptions: no card verification value is looked for either, because `ui/src/api/types.ts`
 * declares no such member on any user-facing shape at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersNoCredentialColumn(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const { baseElement } = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    Object.keys(row(FIRST_ROW_USER_ID)).sort(),
    'UserSummary carries four members and no credential',
  ).toStrictEqual(['firstName', 'lastName', 'userId', 'userType']);
  const table = screen.getByRole('table');
  const headings = within(table)
    .getAllByRole('columnheader')
    .map(
      /**
       * Takes one heading's rendered text.
       * @param {HTMLElement} heading - One column header cell.
       * @returns {string} Its text, lower-cased for comparison.
       */
      (heading: HTMLElement): string => (heading.textContent ?? '').toLowerCase(),
    );
  // WHY : Assumptions: five columns, which is the count `app/bms/COUSR00.bms` heads on row 8 -- at
  //       `POS=(8,5)` for `'Sel'`, `(8,12)` for the identifier, `(8,24)` and `(8,48)` for the two
  //       names and `(8,72)` for the type. The count is asserted as well as each heading's text
  //       because a SIXTH column is how a credential would arrive: the four data members plus the
  //       action cell is exactly five, so any sixth column is carrying something `UserSummary` does
  //       not declare.
  expect(headings, 'row 8 of the mapset heads five columns and no sixth').toHaveLength(5);
  for (const heading of headings) {
    expect(heading, 'no column may head a credential').not.toContain('password');
  }
  expect(
    baseElement.querySelectorAll('input[type="password"]'),
    'a browse renders no credential control',
  ).toHaveLength(0);
}

/**
 * Proves the reference's own dataset name never surfaces to the operator.
 *
 * ⚠️ Assumptions: `app/cbl/COUSR00C.cbl` L39 declares `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '`,
 * with two trailing spaces, and hands it to every `STARTBR`, `READNEXT` and `READPREV`. It is the
 * VSAM cluster name, which the target replaces with the `auth.users` table in Aurora -- so it is a
 * server-side resource identifier with no place in a browser at all. A mainframe dataset name
 * appearing in the SPA would be a finding rather than fidelity, which is why its absence is asserted
 * rather than its text.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function neverSurfacesTheClusterName(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  const { baseElement } = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  await waitForRow(FIRST_ROW_USER_ID);

  expect(
    baseElement.textContent ?? '',
    "the VSAM cluster name 'USRSEC' is a server-side resource and must not reach the operator",
  ).not.toContain('USRSEC');
}

/** Registers the navigation and disclosure cases. */
function navigationAndDisclosureCases(): void {
  it(
    'builds the two selection targets from the route table',
    buildsTheTwoSelectionTargetsFromTheRouteTable,
  );
  it('carries the selected identifier in the path', carriesTheSelectedIdentifierInThePath);
  it(
    'transfers the update code to the maintenance route',
    transfersTheUpdateCodeToTheMaintenanceRoute,
  );
  it('transfers the delete code to the deletion route', transfersTheDeleteCodeToTheDeletionRoute);
  it('leaves the browse on a committed selection', leavesTheBrowseOnACommittedSelection);
  it('exits to the administrative menu', exitsToTheAdministrativeMenu);
  it('renders no credential column', rendersNoCredentialColumn);
  it('never surfaces the cluster name', neverSurfacesTheClusterName);
}

describe(
  'the user browse transfers by route and discloses no credential',
  navigationAndDisclosureCases,
);

/**
 * Name the update screen leaves its save outcome under, composed the way both screens compose it.
 *
 * Assumptions: the route half is imported and only the suffix is written out, which is exactly what
 * `ui/src/screens/userList/index.tsx` and `ui/src/screens/userUpdate/index.tsx` each do. The two agree
 * on this name without importing each other, so the agreement is the property a case must be able to
 * fail on -- and the same constant is composed identically in `ui/src/test/userUpdate.test.tsx`, which
 * is what makes the pair fail together if either side's suffix moves.
 */
const USER_UPDATE_SAVE_CLAIM = `${USER_UPDATE_ROUTE_TEMPLATE}#saved`;

/**
 * The sentence a committed save hands over, composed the way the reference composes it.
 *
 * Assumptions: it is COMPOSED from the catalog template rather than retyped, because the reference
 * composes it -- the `STRING` at `app/cbl/COUSR02C.cbl` L372-L374 concatenates `'User '`, the key
 * `DELIMITED BY SPACE` and `' has been updated ...'`. A retyped literal here would let a lost space in
 * the template pass unnoticed in both this file and the screen.
 */
const HANDED_SAVE_SENTENCE = formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
  'SEC-USR-ID': FIRST_ROW_USER_ID,
});

/**
 * Confirms the browse paints the sentence a save on the update screen left for it.
 * @returns {Promise<void>} Resolves once the handed sentence is on the band.
 */
async function paintsTheHandedSaveSentence(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  /*
   * WHY : Assumptions: the outcome is retained BEFORE the render, because that is the real ordering.
   *       `ui/src/screens/userUpdate/index.tsx` retains and then navigates inside one synchronous
   *       handler, so the outcome exists before this screen is mounted -- which is why the collector is
   *       a mount effect with no subscription. Retaining afterwards would exercise a path the
   *       application does not have.
   */
  retainOutcomeAcrossNavigation(USER_UPDATE_SAVE_CLAIM, {
    settled: 'COMPLETED',
    value: { message: HANDED_SAVE_SENTENCE, severity: 'success' },
  });

  await renderBrowse();

  await waitFor(
    /**
     * Waits until the handed sentence reaches the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(HANDED_SAVE_SENTENCE);
    },
  );
}

/**
 * Confirms the handed sentence obeys a turn's lifetime and is cleared by the operator's next key.
 * @returns {Promise<void>} Resolves once the band has been emptied by a later turn.
 */
async function clearsTheHandedSentenceOnTheNextTurn(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  retainOutcomeAcrossNavigation(USER_UPDATE_SAVE_CLAIM, {
    settled: 'COMPLETED',
    value: { message: HANDED_SAVE_SENTENCE, severity: 'success' },
  });

  const user = await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);
  await waitFor(
    /**
     * Waits until the handed sentence has been painted, so its removal is observable.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(HANDED_SAVE_SENTENCE);
    },
  );

  await pressPfKey(user, 'ENTER');

  // WHY : Assumptions: the handed sentence is given NO more life than a locally raised one. Every turn
  //       of the reference begins `MOVE SPACES TO WS-MESSAGE`, which is what `beginTurn` reproduces, so
  //       an outcome of the previous screen must not still be on the glass after the operator has acted
  //       on this one -- they would read it as a report of what they just did.
  await waitFor(
    /**
     * Waits until the band no longer carries the handed sentence.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).not.toHaveTextContent(HANDED_SAVE_SENTENCE);
    },
  );
}

/**
 * Confirms an outcome is delivered ONCE, so a later arrival is not told about it again.
 * @returns {Promise<void>} Resolves once the second mount has been shown to paint nothing.
 */
async function deliversTheHandedSentenceOnlyOnce(): Promise<void> {
  listing.mockResolvedValue(fullPageWithMore());
  retainOutcomeAcrossNavigation(USER_UPDATE_SAVE_CLAIM, {
    settled: 'COMPLETED',
    value: { message: HANDED_SAVE_SENTENCE, severity: 'success' },
  });

  const first = await renderInAppShell(<UserListScreen />, {
    initialEntries: [USER_LIST_PATH],
    routePath: USER_LIST_PATH,
  });
  await waitFor(
    /**
     * Waits until the first mount has collected and painted the outcome.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(HANDED_SAVE_SENTENCE);
    },
  );
  first.unmount();

  await renderBrowse();
  await waitForRow(FIRST_ROW_USER_ID);

  // WHY : Assumptions: collection REMOVES the outcome, and this is the case that holds it to that. An
  //       operator returning to the browse a second time and being shown the same acknowledgement
  //       cannot tell it from a second write, which on an administrative record is worse than silence.
  expect(messageBand()).not.toHaveTextContent(HANDED_SAVE_SENTENCE);
}

/**
 * Groups the cases holding the browse to painting a save handed to it by the update screen.
 *
 * ⚠️ Purpose: the reading end of the defect measured on `F3=Save&&Exit`. That key writes and transfers
 * in one turn, so the band it publishes dies with its own unmount -- `PUT` returning `200` followed by
 * an arrival whose band was empty. The reference sends the same sentence and loses it the same way, to
 * the `EXEC CICS XCTL` at `app/cbl/COUSR02C.cbl` L258-L261 overwriting the terminal.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function handedSaveOutcomeCases(): void {
  it('paints a save the update screen handed over', paintsTheHandedSaveSentence);
  it('clears the handed sentence on the next turn', clearsTheHandedSentenceOnTheNextTurn);
  it('paints a handed sentence once only', deliversTheHandedSentenceOnlyOnce);
}

describe('the user browse paints an outcome handed to it', handedSaveOutcomeCases);
