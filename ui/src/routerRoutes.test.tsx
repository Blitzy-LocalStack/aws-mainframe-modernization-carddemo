/**
 * @file Proves the route table in `ui/src/router.tsx` mounts what it claims: every authored screen is
 * reachable, every authenticated screen is painted inside the shared frame, and every administrative
 * screen is behind the administrative guard.
 *
 * Purpose
 * -------
 * Four properties are asserted here, none of which the route table can assert about itself:
 *
 * 1. Census. The table declares exactly the twenty-one screen paths the reachability graph names, one
 *    per online program it replaces. A twenty-second path or a missing one fails the census case below,
 *    which compares {@link ROUTE_TABLE} against the roster this file assembles from the table's own
 *    exported constants.
 * 2. Registration. Every path a screen or a menu publishes as a destination is a path the table mounts.
 *    A screen mounted at no route compiles, lints and type-checks exactly as one that is mounted, and
 *    its own test passes against the route that test declares -- so only a comparison against the
 *    shipped table can report it.
 * 3. The frame. `ui/src/layout/AppShell.tsx` is mounted once as the table's layout route, and two
 *    screens omit the shared title band on the documented ground that the frame paints it -- so rows 1
 *    and 2 of those screens exist only while that layout route does. The cases below assert the frame
 *    is present on an authenticated route and on sign-on, and that the delegated band carries the
 *    delegating screen's identity.
 * 4. The administrative boundary. Each administrative route admits an administrator and refuses a
 *    signed-on non-administrator with the catalogued sentence, which is the browser form of the
 *    reference's own user-type refusal.
 *
 * Assumptions: the expectations are the CONSTANTS the screens and the catalog publish rather than
 * retyped strings, for the reason `ui/src/screens/cardScreenShell.test.tsx` records -- a retyped
 * sentence passes for a screen that has drifted, provided the test drifted with it.
 *
 * ⚠️ Assumptions: a case selects its route by opening a MEMORY router on the shipped route objects,
 * where it previously pushed a history entry and rendered the exported router component. The concern
 * the previous note recorded -- that a memory router would prove the shape of a table assembled by the
 * test -- is answered by which routes are handed to it: {@link CARD_DEMO_ROUTES} is the delivered
 * array, guards, lazy boundaries and both shell mounts included, so an unmounted route still fails
 * here. What changes is only where the location comes from, and an explicit initial entry is the more
 * robust of the two: no case can inherit a location another left behind, and nothing has to keep
 * jsdom's history and the router's idea of the location in step.
 *
 * Assumptions: every callback is a named declaration rather than an inline arrow, for the two reasons
 * the card screen tests record: `ui/eslint.config.js` requires a documentation block on a function
 * expression in any position, and Prettier moves a block comment that follows an argument comma onto
 * the preceding literal, which detaches it from the function it documents.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, matchRoutes, RouterProvider } from 'react-router';
import type { RouteObject } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { installApiHarness, removeApiHarness } from './test/apiHarness';
import { endAnySession, establishSession } from './test/sessionHarness';

import { APP_SHELL_TEST_ID, SHELL_SIGN_OFF_CONTROL_TEST_ID } from './layout/AppShell';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  ADMIN_MENU_OPTIONS,
  MAIN_MENU_HEADINGS,
  MAIN_MENU_OPTIONS,
  SCREEN_NOT_AVAILABLE_TITLE,
  SIGN_ON_SUBMIT_LABEL,
} from './messages/messages';
import { MESSAGE_BAND_TEST_ID } from './layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from './layout/PfKeyBar';
import {
  ACCOUNT_UPDATE_PATH,
  ACCOUNT_VIEW_PATH,
  AUTH_DETAIL_PATH,
  AUTH_SUMMARY_PATH,
  BILL_PAY_PATH,
  CARD_DEMO_ROUTES,
  CARD_LIST_PATH,
  KEYLESS_ENTRY_ROUTES,
  REF_TYPE_EDIT_PATH,
  REF_TYPE_LIST_PATH,
  REPORTS_PATH,
  ROUTE_TABLE,
  TRANSACTION_ADD_PATH,
  TRANSACTION_DETAIL_PATH,
  TRANSACTION_LIST_PATH,
  USER_ADD_PATH,
  USER_LIST_PATH,
  USER_DELETE_PATH,
  USER_UPDATE_PATH,
} from './router';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { SIGN_ON_ROUTE } from './routes/guards';
import {
  ADMIN_MENU_ROUTE,
  MAIN_MENU_ROUTE,
  REFERENCE_TYPE_ADD_ROUTE,
  REFERENCE_TYPE_EDIT_ROUTE_TEMPLATE,
  referenceTypeEditRoute,
} from './routes/navigation';
import { ADMIN_MENU_DESTINATIONS, ADMIN_MENU_SUBTITLE } from './screens/admin';
import { AUTHORIZATION_DETAIL_ROUTE, AUTHORIZATION_SUMMARY_ROUTE } from './screens/authDetail';
import {
  AUTH_SUMMARY_BACK_ROUTE,
  AUTH_SUMMARY_PROGRAM_NAME,
  AUTH_SUMMARY_TRANSACTION_ID,
} from './screens/authSummary';
import { ACCOUNT_VIEW_PROGRAM_NAME, ACCOUNT_VIEW_TRANSACTION_ID } from './screens/accountView';
import {
  MAIN_MENU_DESTINATIONS,
  MAIN_MENU_PROGRAM_NAME,
  MAIN_MENU_SUBTITLE,
  MAIN_MENU_TRANSACTION_ID,
} from './screens/menu';
import { REF_TYPE_EDIT_ROUTE, REF_TYPE_NEW_SENTINEL } from './screens/refTypeEdit';
import { REF_TYPE_ADD_ROUTE } from './screens/refTypeList';

/*
 * WHY : Assumptions: a session is arranged through `ui/src/test/sessionHarness.ts`, which drives the
 *       real exchange, and never by writing a storage key. `ui/src/hooks/useAuth.ts` holds the session
 *       in a module variable installed by one private validator, so writing storage would arrange
 *       NOTHING: every case below would render as an anonymous caller and the guards would send it to
 *       sign-on instead of to the screen it asked for. A case that needs a signed-on operator therefore
 *       also proves a session can be established the only way one can be.
 */

/** Group name the administrative guard requires. */
const ADMIN_GROUP = 'carddemo-admin';

/** Group name a non-administrative operator carries. */
const USER_GROUP = 'carddemo-user';

/**
 * Title the bounded not-found result renders.
 *
 * Assumptions: it is bound once here and used by both the registration cases and the catch-all case,
 * because the two assert OPPOSITE things about the same string -- one requires its absence and the other
 * its presence -- and two spellings of it could drift into agreeing with each other.
 *
 * Assumptions: it is READ from the message catalogue rather than copied as a literal, so the cases
 * assert that the surface paints the CATALOGUED heading. A copy asserts only that the surface paints
 * one particular sentence, and the case that requires the heading ABSENT would go on passing against a
 * spelling nothing renders.
 */
const NOT_FOUND_TITLE = SCREEN_NOT_AVAILABLE_TITLE;

/**
 * How long a case waits for a lazily loaded screen to commit inside the frame.
 *
 * Assumptions: the registration cases below need a ceiling well above Testing Library's 1000 ms
 * default, because every guarded route in the table resolves through `React.lazy` -- so each wait
 * covers a dynamic import, its transitive module graph and a first commit. Under load that exceeds a
 * second for the heavier screens, and a case bounded at one second fails on
 * `[data-testid="app-shell"]` for whichever chunk the runner was slowest on. Such a failure reports
 * machine load rather than a missing route element, which is the one thing these cases exist to detect.
 *
 * Assumptions: fifteen seconds, and the figure is measured rather than chosen. Against Testing Library's
 * one-second default the five heaviest routes failed while the twelve lighter ones passed, and those
 * five each resolve in comfortably under three seconds once the wait is allowed to reach them. Ten
 * seconds is several times that measurement, which absorbs the parallel load a full run adds. It sits
 * well inside the 60 s per-case budget `ui/vitest.config.ts` sets, so a route element that is genuinely
 * absent still fails the case on this assertion instead of stalling it until the case times out.
 *
 * Alternatives Considered: five seconds, matching the `ASYNC_CONDITION_TIMEOUT_MS` used for the same job
 * in `ui/src/screens/cardReadSequencing.test.tsx`, `ui/src/screens/screenSelectionCarriers.test.tsx` and
 * `ui/src/screens/cardList/browseNarrowing.test.tsx`, so that this file reuses a number the tree already
 * carries rather than introducing a new one. Rejected on the measurement above: the heaviest routes sit
 * close enough to that figure that it would fail a passing assertion under parallel load, which is the
 * same intermittent failure this constant exists to remove.
 *
 * Assumptions: the shortfall this ceiling covers is measured rather than hypothetical -- the
 * `/account/update` case fails reproducibly at one second, in isolation and with file parallelism
 * disabled, reading the `Suspense` spinner instead of the frame. That screen is the largest module in
 * the tree at nearly four thousand lines and it pulls antd's form and grid surface with it, which does
 * not reliably finish inside Testing Library's default on a CPU-quota-limited runner.
 *
 * Alternatives Considered: thirty seconds, on the measurement that a four-core runner takes several
 * seconds to import and commit a screen module of nearly four thousand lines. Rejected because fifteen
 * seconds already clears that measurement several times over while staying far inside the 60 s per-case
 * budget, and because `ui/src/routerReachability.test.tsx` cannot use thirty -- its per-case budget IS
 * thirty seconds, so a query ceiling of the same size could never report its own failure -- and adopting
 * thirty here would leave the two route suites waiting different lengths for identical work, which is the
 * disagreement a single stated ceiling is meant to prevent.
 *
 * Trade-offs: raising the budget cannot mask a real regression, because the assertion is unchanged —
 * an unregistered path never resolves a screen no matter how long the case waits, and the catch-all
 * check that follows still requires the not-found result to be absent. The allowance is given at this
 * one wait rather than by raising the suite-wide `asyncUtilTimeout`, so every other case in every other
 * file keeps the one-second default and the next genuinely slow wait stays visible.
 */
const LAZY_SCREEN_COMMIT_TIMEOUT_MS = 15000;

/**
 * Every screen path the shipped table registers, apart from sign-on.
 *
 * Assumptions: it is assembled from the table's OWN exported constants rather than retyped, so a
 * renamed path cannot leave this roster describing the previous name. The two menu routes and the card
 * routes come from the modules that own them, which is where the table takes them from too.
 *
 * ⚠️ Assumptions: this roster must be the WHOLE published set, and a case below asserts exactly that
 * against {@link ROUTE_TABLE}. It is not a self-evident property: the roster drives the sweep that
 * renders each path, so a path published and left out of this list is a path nothing renders -- which is
 * how the three heaviest transaction screens once went unswept. Sign-on is therefore listed with the
 * rest rather than left to its own case, even though it has one.
 */
const REGISTERED_PATHS: readonly string[] = [
  SIGN_ON_ROUTE,
  MAIN_MENU_ROUTE,
  ACCOUNT_VIEW_PATH,
  ACCOUNT_UPDATE_PATH,
  CARD_LIST_PATH,
  CARD_DETAIL_ROUTE,
  CARD_EDIT_ROUTE,
  TRANSACTION_LIST_PATH,
  TRANSACTION_ADD_PATH,
  TRANSACTION_DETAIL_PATH,
  REPORTS_PATH,
  BILL_PAY_PATH,
  AUTH_SUMMARY_PATH,
  AUTH_DETAIL_PATH,
  ADMIN_MENU_ROUTE,
  USER_LIST_PATH,
  USER_ADD_PATH,
  /*
   * WHY : Assumptions: user maintenance contributes ONE pattern, not two, because the selector-free
   *       `/users/edit` that once sat beside `/users/:id/edit` is WITHDRAWN. One program with two
   *       routes put twenty-two paths in a graph that names twenty-one programs, and made this roster
   *       disagree with `ROUTE_TABLE` while both described the same set of addresses. The screen keeps
   *       its selector-free first turn; reaching it is the caller's business, not a second entry.
   */
  USER_UPDATE_PATH,
  USER_DELETE_PATH,
  REF_TYPE_LIST_PATH,
  REF_TYPE_EDIT_PATH,
];

/**
 * The six paths `app/cpy/COADM02Y.cpy` gives the administrative menu, in that record's own order.
 *
 * Assumptions: this roster is retyped from the copybook's option order rather than derived from the
 * route table, because it is the EXPECTATION the table is held to. Deriving it from the table would
 * make the case below assert that the table equals itself, which is exactly what let eight paths sit
 * behind the administrative guard while the reference names six options.
 */
const COADM02Y_OPTION_PATHS: readonly string[] = [
  USER_LIST_PATH,
  USER_ADD_PATH,
  USER_UPDATE_PATH,
  USER_DELETE_PATH,
  REF_TYPE_LIST_PATH,
  REF_TYPE_EDIT_PATH,
];

/**
 * The route table's own count of migrated screens, one per online program.
 *
 * Assumptions: twenty-one, and it is written as a number here on purpose. Comparing the table's length
 * against a count derived from the table would assert nothing; the reference has twenty-one online
 * programs, so the figure is the specification and a table that grows or shrinks has to be read
 * against it rather than measured by it.
 */
const MIGRATED_SCREEN_COUNT = 21;

/**
 * The splat the not-found route is registered under.
 *
 * Assumptions: this is react-router's own spelling for a catch-all rather than a project constant, so
 * it is written as a literal. It is needed because `matchRoutes` always returns a match while a splat
 * is registered -- a destination that resolves to nothing else resolves to this -- so the closure case
 * below has to be able to tell the catch-all from a real screen.
 */
const CATCH_ALL_PATTERN = '*';

/**
 * The administrative option names the not-found surface lists, one per distinct destination.
 *
 * Assumptions: retyped from `app/cpy/COADM02Y.cpy` rather than derived from the catalogue, so the case
 * asserts an expectation and not the surface's own arithmetic. Four of the six options survive
 * deduplication; {@link COLLAPSED_ADMINISTRATIVE_DESTINATIONS} names the two that do not, and the case
 * checks the two lists account for all six.
 */
const LISTED_ADMINISTRATIVE_DESTINATIONS: readonly string[] = [
  'User List (Security)',
  'User Add (Security)',
  'Transaction Type List/Update (Db2)',
  'Transaction Type Maintenance (Db2)',
];

/**
 * The administrative option names that collapse onto a destination another option already names.
 *
 * Assumptions: both need a user identifier the browse acquires, so `ui/src/routes/programRoutes.ts`
 * resolves both to `/users` -- the same route `User List (Security)` names. Listing them would offer one
 * destination under three labels.
 */
const COLLAPSED_ADMINISTRATIVE_DESTINATIONS: readonly string[] = [
  'User Update (Security)',
  'User Delete (Security)',
];

/**
 * The main-menu option names the not-found surface lists for an operator holding no admin group.
 *
 * Assumptions: retyped from `app/cpy/COMEN02Y.cpy` rather than derived from the catalogue, for the same
 * reason {@link LISTED_ADMINISTRATIVE_DESTINATIONS} is -- a list derived from the catalogue and
 * deduplicated by the same helper the surface uses would restate the surface's arithmetic instead of
 * checking it, and would pass against a surface that listed the wrong thing consistently.
 *
 * ⚠️ Refactoring Rationale: ALL ELEVEN options are listed, where eight were and three were expected
 * absent. The three -- Credit Card View, Credit Card Update and Transaction View -- were expected absent
 * because their programs resolved to the browse that mints their record key, so listing them would have
 * painted the card browse three times and the transaction browse twice. Each of those three programs now
 * has a KEYLESS entry route of its own (`ui/src/router.tsx` publishes them as `KEYLESS_ENTRY_ROUTES`),
 * which is the address of the first turn the reference paints when its selection carrier arrives blank --
 * so the eleven options name eleven distinct destinations and deduplication removes nothing. That is
 * the reachability graph `app/cpy/COMEN02Y.cpy` describes, and the companion case below now requires the
 * collapsed list to be EMPTY so a re-collapse fails here rather than being noticed in a browser.
 */
const LISTED_ORDINARY_DESTINATIONS: readonly string[] = [
  'Account View',
  'Account Update',
  'Credit Card List',
  'Credit Card View',
  'Credit Card Update',
  'Transaction List',
  'Transaction View',
  'Transaction Add',
  'Transaction Reports',
  'Bill Payment',
  'Pending Authorization View',
];

/**
 * The main-menu option names that collapse onto a destination another option already names.
 *
 * ⚠️ Assumptions: EMPTY, and it is kept rather than deleted so that the census below stays a census.
 * The case asserts the two lists account for all eleven options AND that every name here is absent from
 * the surface; with nothing collapsed, the arithmetic is what reports a regression -- an option that
 * loses its own destination stops being in `LISTED_ORDINARY_DESTINATIONS` and the eleven no longer add
 * up. Deleting the constant would remove that check along with its contents.
 */
const COLLAPSED_ORDINARY_DESTINATIONS: readonly string[] = [];

/**
 * The administrative destination required ABSENT from an ordinary operator's list of ways out.
 *
 * Assumptions: the user browse is chosen, which is administrative option 1's destination. It is the
 * strongest single check available: it is guarded, so offering it to this operator would offer a control
 * that answers with the refusal surface, and it appears in no ordinary option table at all.
 *
 * Assumptions: it is a NAMED constant rather than the first element of
 * {@link LISTED_ADMINISTRATIVE_DESTINATIONS}. `ui/tsconfig.json` sets `noUncheckedIndexedAccess`, so an
 * index into a `readonly string[]` is `string | undefined`, and `exactOptionalPropertyTypes` then
 * refuses that union where a query option requires a name -- so the index form does not type-check at
 * all. Naming the label also states WHICH destination is being withheld, where an index stated only a
 * position.
 */
const ADMINISTRATIVE_DESTINATION_WITHHELD = 'User List (Security)';

/**
 * Every concrete path that must be reachable only by an administrator.
 *
 * Assumptions: the reference-maintenance ADD path is listed as its concrete sentinel form rather than as
 * the dynamic pattern, because that is the path the administrative menu and the list screen actually
 * navigate to -- so this is the spelling a non-administrator would arrive with.
 *
 * ⚠️⚠️ Refactoring Rationale: the administrative MENU route is listed again, and the note that removed
 * it is withdrawn. That note argued the gated set is exactly the six options of `app/cpy/COADM02Y.cpy`
 * and the screen that lists them is not one of them, so a case demanding a refusal there demanded the
 * opposite of the delivered contract. It did -- and the delivered contract was the defect. An ordinary
 * operator rendered the complete administrative menu: the `COADM01C` identity band, all six option
 * labels, and a focused option field that dispatched them to `/users`, where the refusal finally
 * arrived. `app/cbl/COSGN00C.cbl` L230-L240 transfers only an `'A'` operator to `COADM01C`, so the
 * refusal belongs at the menu, and this list is what holds it there.
 *
 * Assumptions: the user-maintenance PATTERN stays absent beside its concrete form, for the reason the
 * withdrawn note gave and which still holds: a pattern is not a path an operator arrives with, `:id`
 * would be matched literally, and the concrete form below proves the refusal at the address the browse
 * actually sends. What this list holds is SEVEN concrete paths covering all seven gated patterns.
 */

const ADMINISTRATIVE_PATHS: readonly string[] = [
  ADMIN_MENU_ROUTE,
  USER_LIST_PATH,
  USER_ADD_PATH,
  '/users/000000AA/edit',
  /*
   * WHY : Assumptions: the deletion path is listed in its CONCRETE form for the same reason the update
   *       path above it is -- it carries an `:id` parameter, so the dynamic pattern is not a path an
   *       operator ever arrives with. It matters more here than on any other entry in this list: this is
   *       the table's only DESTRUCTIVE route, so a gate that silently stopped covering it would be the
   *       one omission that let an ordinary operator reach a record deletion.
   */
  '/users/000000AA/delete',
  REF_TYPE_LIST_PATH,
  `${REF_TYPE_LIST_PATH}/${REF_TYPE_NEW_SENTINEL}`,
];

/**
 * Establishes a session for an operator holding the supplied groups.
 *
 * Assumptions: the token shape is the harness's, not one composed here. It builds the same unsigned
 * three-segment form and carries both claims the installer compares -- the group list AND the user name
 * it validates against the identifier the set arrived with -- so a locally composed token would be one
 * the real installer refuses while looking equivalent.
 * @param {readonly string[]} groups - Group names the operator's claim carries.
 * @returns {Promise<void>} Resolves once the session is held and the guards will admit the operator.
 */
async function signOnAs(groups: readonly string[]): Promise<void> {
  await establishSession({ groups });
}

/**
 * Opens the shipped route objects at one concrete path, in a memory router.
 *
 * Assumptions: the routes handed to the memory router are the DELIVERED array, so this selects a route
 * in the shipped tree rather than in one the test assembled. The array is spread because it is
 * published `readonly` and the factory declares a mutable parameter -- one shallow copy, which also
 * keeps a case from mutating the tree every other case reads.
 * @param {string} path - Concrete path to open.
 * @returns {void} The tree is rendered into the testing library's container.
 */
function openRoute(path: string): void {
  const router = createMemoryRouter([...CARD_DEMO_ROUTES], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
}

/**
 * Ends any held session and removes the transport harness.
 *
 * Assumptions: the session is ENDED rather than storage cleared, and the harness lifecycle is bound to
 * the same hook. `endAnySession` unmounts first and then discards the session, which is the order that
 * keeps React from reporting an update outside `act` when discarding notifies a tree Testing Library has
 * not yet torn down.
 *
 * Refactoring Rationale: jsdom's history is no longer reset here, because no case reads it any more --
 * each one opens a memory router on an explicit initial entry, so resetting the ambient location would
 * imply a dependency that has been removed.
 * @returns {void} Nothing; no session is held and no harness is installed.
 */
function clearSession(): void {
  endAnySession();
  removeApiHarness();
}

/**
 * Installs the transport harness a session exchange is answered by.
 * @returns {void} Nothing; the harness is installed.
 */
function armTransport(): void {
  installApiHarness();
}

/**
 * Asserts the table's own path constant equals the constant the screen or menu publishes.
 *
 * Assumptions: this pairing is what makes the literal paths in the route table safe. The table writes
 * them as literals rather than importing them, because importing a constant from a lazily-loaded screen
 * would pull that screen's whole chunk back into the entry bundle -- so the two spellings are kept in
 * agreement by this case instead of by a shared import.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function tableAgreesWithScreenConstants(): void {
  expect(ACCOUNT_VIEW_PATH).toBe(MAIN_MENU_DESTINATIONS.COACTVWC);
  expect(ACCOUNT_UPDATE_PATH).toBe(MAIN_MENU_DESTINATIONS.COACTUPC);
  expect(CARD_LIST_PATH).toBe(MAIN_MENU_DESTINATIONS.COCRDLIC);
  expect(TRANSACTION_ADD_PATH).toBe(MAIN_MENU_DESTINATIONS.COTRN02C);
  expect(AUTH_SUMMARY_PATH).toBe(MAIN_MENU_DESTINATIONS.COPAUS0C);
  expect(AUTH_SUMMARY_PATH).toBe(AUTHORIZATION_SUMMARY_ROUTE);
  /*
   * Assumptions: the summary screen's exit destination is checked against the MENU route rather than
   * against its own, because that is the reference's own exit -- `COPAUS0C` returns to the main menu --
   * and the destination was a literal in that screen while the route it names lives here.
   */
  expect(AUTH_SUMMARY_BACK_ROUTE).toBe(MAIN_MENU_ROUTE);
  expect(AUTH_DETAIL_PATH).toBe(AUTHORIZATION_DETAIL_ROUTE);
  expect(USER_LIST_PATH).toBe(ADMIN_MENU_DESTINATIONS.COUSR00C);
  expect(REF_TYPE_LIST_PATH).toBe(ADMIN_MENU_DESTINATIONS.COTRTLIC);
  /*
   * WHY : ⚠️ Refactoring Rationale: the user-maintenance path is no longer compared against
   *       administrative option 3's destination, because the two are no longer the same path by design.
   *       The route is `/users/:id/edit`, addressed by the operator identifier it maintains, and a menu
   *       option cannot name an identifier -- so option 3 dispatches to the browse, where one is chosen.
   *       The equality held only while a selector-free `/users/edit` existed for the option to name, and
   *       that path was withdrawn as an invented destination. What replaces the check is the closure
   *       case below, which requires every destination either menu names to RESOLVE in the shipped tree
   *       -- a property that holds whichever registered screen the option sends the operator to.
   * WHY : Assumptions: the maintenance route's own parameter agreement is asserted instead against the
   *       screen that reads it, which is the pairing that can actually go wrong: the pattern and the
   *       screen's `useParams` key are two spellings of one name in two files.
   */
  expect(REF_TYPE_EDIT_PATH).toBe(REF_TYPE_EDIT_ROUTE);
}

/**
 * Asserts administrative option 3 lands on the user BROWSE and that the update pattern binds `:id`.
 *
 * Assumptions: the destination is checked by EQUALITY against the browse, because `COUSR02C` has
 * exactly one route and the menu names a concrete address rather than a pattern. A selector-free
 * `/users/edit` beside the parameterised path was withdrawn -- it made the table publish
 * twenty-two paths for twenty-one programs -- so option 3 reaches the update screen through the
 * browse's selection, which is what `app/cbl/COUSR00C.cbl` L187-L209 does when it transfers to
 * `COUSR02C` with the selected identifier.
 *
 * Assumptions: the identifier form is additionally asserted to BIND the parameter, resolved through
 * the DELIVERED route objects rather than against the pattern string. A pattern that failed to bind
 * would send a selected row to a screen that reads `undefined` and waits for a typed identifier.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function userMaintenanceIsReachedThroughTheBrowse(): void {
  expect(ADMIN_MENU_DESTINATIONS.COUSR02C).toBe(USER_LIST_PATH);
  expect(USER_UPDATE_PATH).toBe('/users/:id/edit');

  const selected = matchRoutes([...CARD_DEMO_ROUTES], '/users/000000AA/edit') ?? [];
  const leaf = selected.at(-1);
  expect(leaf, 'the selected arrival must match a route').toBeDefined();
  expect(leaf?.params.id).toBe('000000AA');
}

/**
 * Asserts the not-found surface keeps the frame and renders the list its own sentence promises.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it covers two defects of one surface. It was mounted
 * outside the frame, so header, footer, main, message row, legend and skip link all measured zero -- an
 * operator who mistyped an address lost the entire chrome. And its subtitle ends `Use a listed screen
 * below.` while NOTHING was listed: the surface offered a single OUTLINED control opening the card
 * browse, and zero anchors, so the weakest available emphasis sat under a sentence describing a list
 * that did not exist.
 *
 * Assumptions: the destinations asserted are the administrative option names, because the case signs on
 * as an administrator and the list is drawn from the operator's own option table -- `app/cpy/COADM02Y.cpy`
 * for an administrator, `app/cpy/COMEN02Y.cpy` for everyone else -- so every label on the surface is a
 * transcription rather than an invention.
 *
 * Assumptions: the rejected address is still required ABSENT. That property was correct before and is
 * preserved: the catch-all is reachable by an unauthenticated caller with any address, so echoing the
 * requested path would put caller-chosen text on a page this application serves.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theNotFoundSurfaceOffersAFramedWayOut(): Promise<void> {
  await signOnAs([ADMIN_GROUP]);
  openRoute('/zzmarkerzz-not-a-route');

  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toBeInTheDocument();

  /*
   * WHY : Assumptions: the row-23 message line is required PRESENT and EMPTY, and both halves matter.
   *       Present, because the withdrawn surface measured no band at all -- an operator who mistyped an
   *       address lost the one line every screen in this application reports through, so the next
   *       message they were shown appeared in a place they had not been reading. Empty, because this
   *       surface has nothing to say on that line: the reference paints row 23 from a program's own
   *       `WS-MESSAGE`, and no program refused anything here. The surface asks for the channel by
   *       delegating `text: null`, which is how all 21 screens reserve the line before they have a
   *       message, and this is the measurement of that delegation arriving.
   */
  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toBeInTheDocument();
  expect(band.textContent?.trim()).toBe('');

  /*
   * WHY : Assumptions: the primary control is identified by its accessible NAME and its variant class
   *       together. The name proves it opens the menu; the class proves it carries antd's primary
   *       emphasis, which is the half that was wrong -- the withdrawn surface's only control was the
   *       library's default outlined variant.
   *
   * ⚠️ Refactoring Rationale: every query below is scoped to the MAIN landmark, where they were
   *       document-wide. `ui/src/layout/AppShell.tsx` now paints a persistent main-menu crossing in
   *       the frame's chrome for an administrator, so a document-wide query for that accessible name
   *       matches two controls -- the chrome one in `banner` and this surface's own in `main` -- and
   *       reported an ambiguous match rather than a defect. Scoping states what this case is actually
   *       about: the ways out THIS SURFACE offers. The chrome crossing is asserted where it belongs,
   *       in `ui/src/layout/appShell.test.tsx`.
   */
  const surface = within(screen.getByRole('main'));
  const primary = surface.getByRole('button', { name: MAIN_MENU_HEADINGS.SCREEN });
  expect(primary).toBeInTheDocument();
  expect(primary.className).toContain('ant-btn-primary');

  for (const label of LISTED_ADMINISTRATIVE_DESTINATIONS) {
    expect(
      surface.getByRole('button', { name: label }),
      `${label} must be listed as a way out`,
    ).toBeInTheDocument();
  }

  /*
   * WHY : Assumptions: the two administrative options NOT listed are asserted absent, and their
   *       absence is the deduplication working rather than a gap. `COUSR02C` and `COUSR03C` both
   *       resolve to `/users`, because the browse is what acquires the identifier each of them needs --
   *       `ui/src/routes/programRoutes.ts` records the collapse -- so listing them would paint one
   *       destination three times under three names. A MENU must show all six because an operator types
   *       an option number; a list of destinations must show each destination once.
   */
  for (const label of COLLAPSED_ADMINISTRATIVE_DESTINATIONS) {
    expect(
      screen.queryByRole('button', { name: label }),
      `${label} collapses onto the user browse and must not be listed twice`,
    ).toBeNull();
  }
  expect(
    LISTED_ADMINISTRATIVE_DESTINATIONS.length + COLLAPSED_ADMINISTRATIVE_DESTINATIONS.length,
  ).toBe(ADMIN_MENU_OPTIONS.length);

  expect(document.body.textContent).not.toContain('zzmarkerzz');
}

/**
 * Asserts the not-found surface lists the ORDINARY operator's destinations, not the administrator's.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it is the companion the administrative case needed.
 * That case signs on with the administrative group, so on its own it could not tell a surface that
 * reads the operator's claim from one that lists the administrative options to everybody -- both pass
 * it. Pairing it with an ordinary operator is what makes the list role-sensitive rather than merely
 * present, and the administrative labels are required ABSENT here for exactly that reason.
 *
 * ⚠️ Refactoring Rationale: this roster is no longer deduplicated at all -- eleven options reach eleven
 * destinations -- where it collapsed from eleven to eight. The three that collapsed had no keyless route
 * to be sent to and resolved to the browse that mints their record key; each now has one, so every
 * option carries its own way out. The arithmetic below is unchanged and is what reports a re-collapse:
 * `LISTED_ORDINARY_DESTINATIONS` holds all eleven names and every one is required PRESENT, so a program
 * repointed back at a browse drops a name from the surface and fails here.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theNotFoundSurfaceListsTheOrdinaryDestinations(): Promise<void> {
  await signOnAs([]);
  openRoute('/zzmarkerzz-still-not-a-route');

  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: MAIN_MENU_HEADINGS.SCREEN })).toBeInTheDocument();

  for (const label of LISTED_ORDINARY_DESTINATIONS) {
    expect(
      screen.getByRole('button', { name: label }),
      `${label} must be listed as a way out`,
    ).toBeInTheDocument();
  }

  for (const label of COLLAPSED_ORDINARY_DESTINATIONS) {
    expect(
      screen.queryByRole('button', { name: label }),
      `${label} collapses onto a browse and must not be listed twice`,
    ).toBeNull();
  }
  expect(LISTED_ORDINARY_DESTINATIONS.length + COLLAPSED_ORDINARY_DESTINATIONS.length).toBe(
    MAIN_MENU_OPTIONS.length,
  );

  /*
   * WHY : Assumptions: an administrative destination is required ABSENT, and the one chosen is the
   *       user browse -- the destination administrative option 1 names. It is the strongest single
   *       check available: it is guarded, so offering it to this operator would offer a control that
   *       answers with the refusal surface, and it appears in no ordinary option table at all.
   */
  expect(screen.queryByRole('button', { name: ADMINISTRATIVE_DESTINATION_WITHHELD })).toBeNull();

  expect(document.body.textContent).not.toContain('zzmarkerzz');
}

/**
 * Asserts an operator holding no session reaches the not-found surface and is offered sign-on.
 *
 * Assumptions: this is the property that decides WHICH frame branch the catch-all belongs in. An
 * unmatched address is the one surface an unauthenticated caller reaches without passing a guard, so
 * putting the catch-all in the guarded branch would answer a mistyped URL with a credential prompt.
 * Requiring the surface to render for an anonymous caller is what holds it in the public branch.
 *
 * Assumptions: the destinations are required ABSENT for that caller, because every one of them is
 * guarded -- offering them would offer transitions that immediately bounce the operator back.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theNotFoundSurfaceIsReachableAnonymously(): Promise<void> {
  openRoute('/no-such-carddemo-screen');

  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: SIGN_ON_SUBMIT_LABEL })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: MAIN_MENU_HEADINGS.SCREEN })).toBeNull();
}

/**
 * Asserts the catch-all shadows none of the twenty-one declared paths.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it exists because the catch-all MOVED. It used to be
 * a top-level sibling of both frame mounts; it is now a child of the public frame branch, so that an
 * operator who mistypes an address keeps the header, the footer, the row-23 message line, the row-24
 * legend and the skip link. React Router scores a dynamic splat below every static and parameterised
 * segment, so relocating it cannot change which route wins -- but "cannot" is a claim about the matcher,
 * and this case is the measurement instead of the claim.
 *
 * Assumptions: it resolves through `matchRoutes` on the DELIVERED route objects rather than by
 * rendering, which is what makes it a complete sweep: rendering twenty-one lazily loaded screens is
 * what the per-path sweep below already pays for, and a static resolution answers the shadowing question
 * for every path at once.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function theCatchAllShadowsNoDeclaredPath(): void {
  for (const pattern of REGISTERED_PATHS) {
    const concrete = concretePathFor(pattern);
    const leaf = (matchRoutes([...CARD_DEMO_ROUTES], concrete) ?? []).at(-1);

    expect(leaf, `${concrete} matches no route`).toBeDefined();
    expect(leaf?.route.path, `${concrete} is shadowed by the catch-all`).not.toBe(
      CATCH_ALL_PATTERN,
    );
  }

  /*
   * WHY : Assumptions: the control is asserted in the same case, because a sweep proving no path
   *       reaches the catch-all would also pass against a table with no catch-all registered at all --
   *       which is the state in which an unmatched address renders nothing.
   */
  const unmatched = (matchRoutes([...CARD_DEMO_ROUTES], '/no-such-carddemo-screen') ?? []).at(-1);
  expect(unmatched?.route.path).toBe(CATCH_ALL_PATTERN);
}

/**
 * Asserts the table declares exactly the twenty-one screen paths the reachability graph names.
 *
 * Assumptions: twenty-one is the count the graph closes at -- eleven main-menu options, six
 * administrative options, and the four programs reachable outside both menus (sign-on, the two menus
 * themselves, and the authorization detail selected from the summary). A twenty-second row means one
 * program has two paths, and a twentieth means a program has none, which is why the count is asserted
 * as well as the membership.
 *
 * Assumptions: {@link ROUTE_TABLE} is compared against the roster this file assembles, so the two
 * surfaces cannot disagree about which addresses exist. Neither can substitute for the other: the
 * roster drives the per-path sweep below, and the table carries the guard classification and the
 * program name.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function theTableDeclaresExactlyTwentyOneScreenPaths(): void {
  const declared = [...ROUTE_TABLE].map(
    /**
     * Reads one row's path.
     * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the reachability graph.
     * @returns {string} The path that row declares.
     */
    (entry) => entry.path,
  );

  expect(declared).toHaveLength(21);
  /*
   * WHY : Assumptions: the roster is compared AS IS rather than with the public route prepended.
   *       `REGISTERED_PATHS` already carries `SIGN_ON_ROUTE` as its first entry, so prepending it
   *       again would compare twenty-one declared paths against a twenty-two-entry list holding one
   *       duplicate -- a failure that reads like a missing route while the table is correct.
   */
  expect([...declared].sort()).toStrictEqual([...REGISTERED_PATHS].sort());
  expect(new Set(declared).size).toBe(declared.length);
}

/**
 * Asserts the add destination both reference screens navigate to resolves under the mounted route.
 *
 * Assumptions: one dynamic route serves the add and the change entries, so the add destination is
 * proved by construction rather than by a second registered path -- the sentinel segment has to be
 * what the dynamic route's parameter receives, and both reference screens have to spell it the same
 * way.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function addDestinationResolvesUnderTheDynamicRoute(): void {
  expect(REF_TYPE_ADD_ROUTE).toBe(`${REF_TYPE_LIST_PATH}/${REF_TYPE_NEW_SENTINEL}`);
  expect(ADMIN_MENU_DESTINATIONS.COTRTUPC).toBe(REF_TYPE_ADD_ROUTE);
  /*
   * ⚠️ Assumptions: the parameter is spelled `cd`, where this assertion required `typeCd`. The frozen
   * route table names it `cd`, and the rename is asserted here as well as against the screen's own
   * constant because the two failures read differently: this one says the PATTERN drifted from the
   * specification, the equality above says the pattern and the screen disagree with each other.
   */
  expect(REF_TYPE_EDIT_PATH).toBe(`${REF_TYPE_LIST_PATH}/:cd`);
}

/**
 * Asserts the maintenance template agrees with the mounted route and that a producer for it exists.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it covers a route that was mounted, guarded, and
 * producible by nothing. `ui/src/routes/navigation.ts` published the template spelled `:typeCd` while
 * the router mounts `:cd` and the screen reads `useParams<{ cd: string }>()`, and the divergence was
 * invisible precisely because no caller built a path from the template -- the only navigation into that
 * screen is the `new` sentinel, so a fetched record stayed on the add address and was neither
 * addressable nor recoverable by the browser's back control.
 *
 * Assumptions: the producer is asserted by RESOLVING its output against the delivered route objects
 * rather than by string equality with the template. Equality would pass for a builder that emitted the
 * template's own text with the parameter left in it, which is the mistake a builder exists to prevent;
 * matching proves an operator handed this address arrives at the maintenance screen with the key bound.
 *
 * Assumptions: the add sentinel is required to be REFUSED by the producer. One dynamic route serves
 * both entries, so a builder that accepted `new` would mint the add address under the name of a key
 * lookup and the two entries would become indistinguishable at the call site.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function theMaintenanceTemplateHasAProducer(): void {
  expect(REFERENCE_TYPE_EDIT_ROUTE_TEMPLATE).toBe(REF_TYPE_EDIT_PATH);
  expect(REFERENCE_TYPE_ADD_ROUTE).toBe(REF_TYPE_ADD_ROUTE);

  const built = referenceTypeEditRoute('05');
  expect(built).toBe(`${REF_TYPE_LIST_PATH}/05`);

  const leaf = (matchRoutes([...CARD_DEMO_ROUTES], built) ?? []).at(-1);
  expect(leaf?.route.path, `${built} must resolve to the maintenance route`).toBe(
    REF_TYPE_EDIT_PATH,
  );
  expect(leaf?.params.cd).toBe('05');

  /*
   * WHY : Assumptions: the refusals cover the three values a caller is most likely to pass by mistake
   *       -- the add sentinel, an unpadded key, and a key the screen never produces. The maintenance
   *       screen canonicalises a typed entry with `padStart(2, '0')`, so `'5'` is a key that was not
   *       canonicalised and `'005'` is one that overflowed the two-character column
   *       `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 declares.
   */
  for (const rejected of [REF_TYPE_NEW_SENTINEL, '5', '005', '', 'AB']) {
    expect(
      buildingTheEditRouteFor(rejected),
      `${rejected === '' ? '(empty)' : rejected} must be refused`,
    ).toThrow(RangeError);
  }
}

/**
 * Asserts every destination the two menus name resolves to a screen in the shipped tree.
 *
 * Assumptions: only the non-null destinations are checked. A null entry is an option this delivery
 * does not mount, which each menu answers with the reference's own unavailable-option sentence, so a
 * null is a documented absence rather than a gap.
 *
 * ⚠️ Refactoring Rationale: resolution is asserted by MATCHING each destination against the shipped
 * route objects, where the case previously required the destination to appear in the roster above.
 * Membership could only ever accept a destination spelled exactly as a pattern is, which is why the
 * reference add destination needed a hand-written translation to the dynamic route it resolves under --
 * and every future parameterised destination would have needed another one. Matching answers the
 * question the case is actually asking: does an operator who chooses this option arrive at a screen.
 *
 * Assumptions: the catch-all has to be excluded explicitly, because a splat is registered -- so
 * `matchRoutes` returns a match for any address and a destination naming nothing would otherwise pass
 * by resolving to the not-found result, which is the exact failure this case exists to catch.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function everyMenuDestinationResolves(): void {
  const destinations = [
    ...Object.values(MAIN_MENU_DESTINATIONS),
    ...Object.values(ADMIN_MENU_DESTINATIONS),
  ].filter(
    /**
     * Keeps the destinations that name a route.
     * @param {string | null} destination - One menu destination.
     * @returns {boolean} True when the option names a route this delivery mounts.
     */
    (destination): destination is string => destination !== null,
  );

  expect(destinations.length).toBeGreaterThan(0);
  for (const destination of destinations) {
    const matches = matchRoutes([...CARD_DEMO_ROUTES], destination) ?? [];
    const leaf = matches.at(-1);

    expect(leaf, `${destination} matches no route`).toBeDefined();
    expect(leaf?.route.path, `${destination} resolves only to the not-found result`).not.toBe(
      CATCH_ALL_PATTERN,
    );
  }
}

/**
 * Asserts each keyless entry route aliases a published program and inherits that program's guard.
 *
 * ⚠️ Purpose: this case is NEW and it is the guard on the second route table. `KEYLESS_ENTRY_ROUTES`
 * exists so `ROUTE_TABLE` can stay a bijection, and that division only holds if an alias is provably an
 * alias: it must name a program the primary table declares, it must not shadow a primary path, and it
 * must sit behind the same guard as the screen it reaches. An alias mounted one branch out would be a
 * screen reachable with the wrong claim, and nothing else in this file would report it, because every
 * other case reads `ROUTE_TABLE` and an alias is not in it.
 *
 * ⚠️ Assumptions: the guard is compared by ROUTE-OBJECT IDENTITY, using the same ancestor walk
 * {@link signOnSitsBesideTheGuardedBranch} uses, rather than by an access field on the alias row. The
 * alias table deliberately carries no access field: recording one would create a second place for the
 * guard to be declared, and the two could then disagree while both looked deliberate. Comparing the
 * mounted chains asks the question directly -- does an operator reaching the alias pass the same guards
 * as one reaching the primary.
 *
 * Assumptions: the alias paths are required DISJOINT from the primary paths rather than merely
 * distinct from one another, because a collision is the failure that would silently withdraw a keyed
 * route: two rows at one path leaves React Router serving whichever was declared first.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function eachKeylessEntryAliasesAPublishedProgram(): void {
  const primaryPaths = new Set(ROUTE_TABLE.map(pathOf));
  const aliasPaths = KEYLESS_ENTRY_ROUTES.map(keylessPathOf);

  expect(new Set(aliasPaths).size, 'each keyless entry must be its own path').toBe(
    KEYLESS_ENTRY_ROUTES.length,
  );

  for (const alias of KEYLESS_ENTRY_ROUTES) {
    const named = ROUTE_TABLE.filter(
      /**
       * Keeps the primary rows naming the same reference program as this alias.
       * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the primary table.
       * @returns {boolean} True when the row replaces the same program.
       */
      (entry) => entry.program === alias.program,
    );
    expect(named, `${alias.path} must alias exactly one published program row`).toHaveLength(1);

    expect(
      primaryPaths.has(alias.path),
      `${alias.path} must not shadow a path the primary table publishes`,
    ).toBe(false);

    const leaf = (matchRoutes([...CARD_DEMO_ROUTES], alias.path) ?? []).at(-1);
    expect(leaf, `${alias.path} matches no route`).toBeDefined();
    expect(leaf?.route.path, `${alias.path} resolves only to the not-found result`).not.toBe(
      CATCH_ALL_PATTERN,
    );
    expect(leaf?.route.path, `${alias.path} must resolve to its own route`).toBe(alias.path);

    const primaryPath = named[0]?.path ?? '';
    expect(
      ancestorsOf(alias.path),
      `${alias.path} must stand behind the same guards as ${primaryPath}`,
    ).toEqual(ancestorsOf(primaryPath));
  }
}

/**
 * Reads one alias row's path, as a named callback the lint rules accept in a `map`.
 * @param {(typeof KEYLESS_ENTRY_ROUTES)[number]} entry - One row of the alias table.
 * @returns {string} The row's path.
 */
function keylessPathOf(entry: (typeof KEYLESS_ENTRY_ROUTES)[number]): string {
  return entry.path;
}

/**
 * Asserts the eleven main-menu options reach ELEVEN distinct screens, and the six administrative ones
 * reach the screens their own table names.
 *
 * ⚠️ Purpose: this case is NEW and it is the guard on the finding. `app/cpy/COMEN02Y.cpy` gives the main
 * menu eleven options naming eleven distinct programs, and three of them -- `COCRDSLC`, `COCRDUPC` and
 * `COTRN01C` -- had no keyless route to be sent to, so they resolved to the browse that mints their
 * record key and eleven options reached EIGHT destinations. An operator who chose Credit Card Update
 * arrived at the card browse. Nothing failed, because every option resolved to a real screen: only
 * counting the DISTINCT destinations can report it.
 *
 * ⚠️ Assumptions: the count is asserted against the catalogue's own option count rather than against a
 * literal eleven, so the case follows `app/cpy/COMEN02Y.cpy` if the copybook is ever retyped and cannot
 * be satisfied by an expectation edited down to match a collapse.
 *
 * Assumptions: no main-menu destination may be `null`. A null is the reference's not-installed answer,
 * which is correct for a program the region cannot load and wrong for all eleven of these, every one of
 * which this delivery mounts -- so admitting a null here would let an option go dark without failing.
 *
 * Assumptions: the administrative table is checked for null and for its own count but NOT for distinct
 * destinations, because two of its six options legitimately share the user browse: `COUSR02C` and
 * `COUSR03C` are addressed per record and the browse is where a record is selected, as recorded at those
 * entries in `ui/src/routes/programRoutes.ts`. Demanding distinctness there would demand a change this
 * case has no evidence for.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function theMainMenuOptionsReachDistinctScreens(): void {
  const ordinary = Object.values(MAIN_MENU_DESTINATIONS);

  expect(ordinary).toHaveLength(MAIN_MENU_OPTIONS.length);
  expect(ordinary, 'every main-menu option must name a screen this delivery mounts').not.toContain(
    null,
  );
  expect(
    new Set(ordinary).size,
    'each main-menu option must reach a screen of its own, not another option\u2019s',
  ).toBe(MAIN_MENU_OPTIONS.length);

  const administrative = Object.values(ADMIN_MENU_DESTINATIONS);
  expect(administrative).toHaveLength(ADMIN_MENU_OPTIONS.length);
  expect(
    administrative,
    'every administrative option must name a screen this delivery mounts',
  ).not.toContain(null);
}

/**
 * Asserts sign-on is framed like every other screen, and is offered no sign-off.
 *
 * Assumptions: the frame is required PRESENT on sign-on. That screen delegates its title band, its
 * row-23 message and its row-24 legend through `useShellSlot` exactly as the others do, so an unframed
 * sign-on has nothing to paint any of them: the three refusal sentences `app/cbl/COSGN00C.cbl`
 * L211-L256 writes would be computed and discarded, and the mapset's own `ENTER=Sign-on` legend would
 * be absent from the first screen every operator sees.
 *
 * Assumptions: what must be absent is the SIGN-OFF control, and it is asserted directly rather than
 * inferred from the frame's absence. The frame renders that control only while a session is held, so it
 * is absent here for a reason that does not depend on where the route sits in the tree.
 * @returns {Promise<void>} Resolves once the frame has been found and the control has not.
 */
async function signOnIsFramedWithoutASignOffControl(): Promise<void> {
  openRoute(SIGN_ON_ROUTE);
  await waitFor(
    /** Waits for the eagerly imported sign-on screen to commit inside the frame. */
    () => {
      expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
    },
  );
  expect(screen.queryByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID)).toBeNull();
}

/**
 * Asserts an authenticated screen is painted inside the frame.
 * @returns {Promise<void>} Resolves once the frame and the screen have both been found.
 */
async function anAuthenticatedScreenIsPaintedInsideTheFrame(): Promise<void> {
  await signOnAs([USER_GROUP]);
  openRoute(MAIN_MENU_ROUTE);
  expect(await screen.findByText(MAIN_MENU_SUBTITLE)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
}

/**
 * Asserts a screen that composes its own title band gets exactly one, not two.
 *
 * Assumptions: this is the risk mounting the frame introduces, and it is the reason the frame paints each
 * zone only for a screen that has DELEGATED one. Most screens compose their own header, message band and
 * key legend; a frame that painted those unconditionally would give every one of them a second title
 * band, two live regions announcing one message, and a duplicate band test handle where callers expect
 * one. Counting the identifier is the assertion that the opt-in contract holds.
 * @returns {Promise<void>} Resolves once the single band has been counted.
 */
async function aSelfComposingScreenGetsOneTitleBand(): Promise<void> {
  await signOnAs([USER_GROUP]);
  openRoute(MAIN_MENU_ROUTE);
  expect(await screen.findByText(MAIN_MENU_SUBTITLE)).toBeInTheDocument();

  expect(screen.getAllByText(MAIN_MENU_TRANSACTION_ID)).toHaveLength(1);
  expect(screen.getAllByText(MAIN_MENU_PROGRAM_NAME)).toHaveLength(1);
}

/**
 * Asserts the frame paints the delegating screen's title band.
 *
 * Assumptions: the account view screen renders no `ScreenHeader` of its own and delegates its identity
 * instead, so finding its transaction identifier and program name proves the delegation reached the
 * frame. Those two fields have no other renderer: without the layout route they appear nowhere.
 * @returns {Promise<void>} Resolves once both identity slots have been found.
 */
async function theFramePaintsADelegatedTitleBand(): Promise<void> {
  await signOnAs([USER_GROUP]);
  openRoute(ACCOUNT_VIEW_PATH);
  expect(await screen.findByText(ACCOUNT_VIEW_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(ACCOUNT_VIEW_PROGRAM_NAME)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
}

/**
 * Asserts the frame paints the OTHER delegating screen's title band.
 *
 * Assumptions: both delegating screens are asserted rather than one standing in for the other, because
 * each publishes its own identity and omits its own band independently -- so a delegation added to one and
 * not the other would leave the second screen's rows 1 and 2 empty with nothing failing.
 * @returns {Promise<void>} Resolves once both identity slots have been found.
 */
async function theFramePaintsTheSummaryTitleBand(): Promise<void> {
  await signOnAs([USER_GROUP]);
  openRoute(AUTH_SUMMARY_PATH);
  expect(await screen.findByText(AUTH_SUMMARY_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(AUTH_SUMMARY_PROGRAM_NAME)).toBeInTheDocument();
}

/**
 * Asserts an administrator reaches the administrative menu.
 * @returns {Promise<void>} Resolves once the menu has been found.
 */
async function anAdministratorReachesTheAdministrativeMenu(): Promise<void> {
  await signOnAs([ADMIN_GROUP]);
  openRoute(ADMIN_MENU_ROUTE);
  expect(await screen.findByText(ADMIN_MENU_SUBTITLE)).toBeInTheDocument();
}

/**
 * Substitutes a synthetic value for every dynamic segment of a route pattern.
 *
 * Assumptions: the value only has to be a segment, not a valid selector. A card selector guard and a
 * user identifier edit both REFUSE a value they do not recognise, and a refusal is a screen -- which is
 * what this case distinguishes from the not-found result.
 *
 * Assumptions: the expression consumes an OPTIONAL marker as well as the parameter name, so
 * `/users/:id/edit` yields `/users/synthetic-segment/edit`. Leaving a `?` behind would produce
 * `/users/synthetic-segment?/edit`, whose query string swallows the rest of the path -- so the sweep
 * would mount the user browse and pass while telling nothing about the maintenance route.
 * @param {string} pattern - Route pattern, possibly carrying `:name` or `:name?` segments.
 * @returns {string} A concrete path that matches the pattern.
 */
function concretePathFor(pattern: string): string {
  return pattern.replace(/:[A-Za-z]+\??/gu, 'synthetic-segment');
}

/*
 * WHY : Assumptions: the registration sweep below waits on {@link LAZY_SCREEN_COMMIT_TIMEOUT_MS}, the one
 *       query-level ceiling this file declares, rather than on a second ceiling of its own. Every wait
 *       here covers identical work -- a `React.lazy` chunk being transformed, imported, mounted and
 *       committed, for screen modules whose imports pull in the whole design system -- so two constants
 *       would only give the same measurement two places to drift apart.
 * WHY : Alternatives Considered: raising the GLOBAL `asyncUtilTimeout` in `ui/src/test/setup.ts`.
 *       Rejected on the ground `ui/src/screens/cardList/browseNarrowing.test.tsx` records for its own
 *       local ceiling -- a global change would alter the failure latency of every case in the suite to
 *       suit the handful that load a chunk.
 */

/**
 * Builds a case asserting one registered path resolves to a screen rather than to the not-found result.
 *
 * Assumptions: this is the case that proves REGISTRATION, and the constant-comparison cases above do
 * not. Those compare one published spelling against another, so a route entry deleted from the table
 * while its constant stayed would satisfy every one of them. Mounting each path and requiring that the
 * catch-all did NOT win is the assertion no constant can satisfy.
 *
 * Assumptions: one case PER path, built by this factory, rather than one case looping over every path.
 * Twenty routes each resolve a lazily loaded chunk, which exceeds a single per-case budget, and a case
 * that times out mid-render leaves its tree mounted -- so the cases after it would fail on elements
 * belonging to the abandoned tree rather than on anything they asserted. Separate cases get separate
 * budgets and the testing library unmounts between them.
 *
 * Assumptions: each case signs on as an administrator so that neither guard refuses it, and asserts
 * only the ABSENCE of the not-found result. Every request fails here, there being no service, and a
 * screen that renders its own failure state is still a screen. Asserting on any screen's content would
 * duplicate that screen's own tests and would fail for reasons unrelated to registration.
 * @param {string} pattern - One registered route pattern.
 * @returns {() => Promise<void>} The case body for that pattern.
 */
function registeredPathResolvesToAScreen(pattern: string): () => Promise<void> {
  return (
    /**
     * Opens the pattern's concrete path and requires a screen inside the frame.
     * @returns {Promise<void>} Resolves once the frame has committed.
     */
    async function pathResolves(): Promise<void> {
      await signOnAs([ADMIN_GROUP]);
      openRoute(concretePathFor(pattern));
      /*
       * Trade-offs: the wait is for the frame rather than for any screen element, because the frame is
       * the one thing every authenticated route has in common and it commits after the lazily loaded
       * chunk resolves. Waiting for it therefore also waits out the `Suspense` fallback, which is what
       * would otherwise let this case read the spinner and conclude nothing.
       */
      /*
       * WHY : ⚠️ Refactoring Rationale: this wait carries an explicit ceiling, where it took Testing
       *       Library's one-second default. Five of the seventeen routes failed against that default --
       *       `/menu`, `/account/update`, `/cards`, `/transactions/new` and the user-maintenance route,
       *       spelled `/users/edit` at the time of that measurement and since withdrawn -- and they are
       *       the five heaviest screens in the tree, while the twelve lighter ones passed. The wait is on
       *       a `React.lazy` chunk resolving AND its screen committing, and in jsdom the commit of a
       *       screen this size, mounted inside the frame, exceeds one second on its own. The failure was
       *       therefore reporting the harness's ceiling rather than a route that does not resolve, which
       *       the passing `/cards/:cardKey` and `/authorizations` cases in the same table demonstrate.
       * WHY : Assumptions: raising a ceiling cannot weaken this assertion, because `waitFor` only ever
       *       ends EARLY on success -- a route that genuinely resolves to nothing still fails, it simply
       *       takes the full ceiling to say so. What a too-low ceiling does is fail a route that works.
       * WHY : Trade-offs: the allowance is given at this one wait rather than by raising the suite-wide
       *       `asyncUtilTimeout`, so every other case in every other file keeps the one-second default
       *       and the next genuinely slow wait stays visible instead of being absorbed by a global. The
       *       cost is that a route which never resolves takes
       *       {@link LAZY_SCREEN_COMMIT_TIMEOUT_MS} to report rather than a second.
       */
      await waitFor(
        /**
         * Waits for the lazily loaded screen to commit inside the frame.
         * @returns {void} Nothing; failure is reported by the expectation.
         */
        () => {
          expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
        },
        { timeout: LAZY_SCREEN_COMMIT_TIMEOUT_MS },
      );
      expect(screen.queryByText(NOT_FOUND_TITLE)).toBeNull();
    }
  );
}

/**
 * Builds a case asserting one administrative path refuses a signed-on non-administrator.
 *
 * Assumptions: the refusal is asserted TRIMMED, for the reason `ui/src/routes/guards.test.tsx`
 * records -- the catalogued sentence carries the trailing blanks its fixed-width field produced, and
 * the DOM collapses them on display while the constant stays the source of the expectation.
 * @param {string} path - One concrete administrative path.
 * @returns {() => Promise<void>} The case body for that path.
 */
function administrativePathRefusesANonAdministrator(path: string): () => Promise<void> {
  return (
    /**
     * Opens the path as a non-administrator and requires the catalogued refusal.
     * @returns {Promise<void>} Resolves once the refusal is on the glass.
     */
    async function pathRefuses(): Promise<void> {
      await signOnAs([USER_GROUP]);
      openRoute(path);
      /*
       * Assumptions: the refusal is awaited rather than read synchronously, because the guard is a
       * child of the frame's layout route and the frame commits before the guard's decision does.
       */
      expect(await screen.findByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeInTheDocument();
      expect(screen.queryByText(ADMIN_MENU_SUBTITLE)).toBeNull();
    }
  );
}

/**
 * Asserts an unknown path resolves to the not-found surface INSIDE the application frame.
 *
 * Assumptions: the case signs on first, so the assertion proves the not-found route is reached rather
 * than the sign-on redirect being reached -- the failure a guarded catch-all would have produced.
 *
 * ⚠️ Refactoring Rationale: the second expectation is inverted. It required the frame to be ABSENT,
 * which was the delivered behaviour and the defect: the catch-all was a top-level sibling of both frame
 * mounts, so an operator who mistyped an address lost the header, the footer, the message row, the key
 * legend and the skip link at once, leaving the browser's own back control as the only way out. The
 * surface is now a child of the public frame branch, so the frame is required to be present.
 * @returns {Promise<void>} Resolves once the not-found surface has been found inside the frame.
 */
async function anUnknownPathResolvesToNotFound(): Promise<void> {
  await signOnAs([ADMIN_GROUP]);
  openRoute('/no-such-carddemo-screen');
  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(screen.queryByTestId(APP_SHELL_TEST_ID)).not.toBeNull();
}

/**
 * Asserts the table publishes one path per migrated program, and the roster above covers them all.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it is the one the delivery needed. The table
 * published twenty-two paths for twenty-one programs -- `COUSR02C` held a parameterised path and a
 * selector-free one -- and no case could see it, because every other case here asks whether a path
 * resolves and never how many there are. A count is the only assertion an extra route fails.
 *
 * Assumptions: the paths are also required to be DISTINCT and the programs to be distinct, so the
 * count cannot be satisfied by a duplicated row or by twenty-one rows naming twenty programs. And the
 * roster this file renders from is required to be the same set, so a path can never be published
 * without being rendered by the sweep below.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function theTableRegistersOnePathPerProgram(): void {
  expect(ROUTE_TABLE).toHaveLength(MIGRATED_SCREEN_COUNT);
  expect(new Set(ROUTE_TABLE.map(pathOf)).size).toBe(MIGRATED_SCREEN_COUNT);
  expect(new Set(ROUTE_TABLE.map(programOf)).size).toBe(MIGRATED_SCREEN_COUNT);

  expect([...ROUTE_TABLE.map(pathOf)].sort()).toEqual([...REGISTERED_PATHS].sort());
}

/**
 * Reads one table row's path, as a named callback the lint rules accept in a `map`.
 * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the published table.
 * @returns {string} The row's path.
 */
function pathOf(entry: (typeof ROUTE_TABLE)[number]): string {
  return entry.path;
}

/**
 * Reads one table row's program name.
 * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the published table.
 * @returns {string} The reference program the row replaces.
 */
function programOf(entry: (typeof ROUTE_TABLE)[number]): string {
  return entry.program;
}

/**
 * Asserts exactly seven paths are administrative: the six reference options and the menu listing them.
 *
 * ⚠️ Refactoring Rationale: this case is NEW. Eight paths sat behind the administrative guard where
 * `app/cpy/COADM02Y.cpy` names six options, and the two extras were the administrative MENU and the
 * transaction-capture screen -- one of which the reference gives every operator (`app/cpy/COMEN02Y.cpy`
 * gives option 8 the user type `'U'`). The refusal sweep below could never report that, because a route
 * gated in error refuses exactly as convincingly as a route gated correctly; only the SET can be wrong.
 *
 * ⚠️⚠️ Refactoring Rationale: the expected set is now the six options PLUS `ADMIN_MENU_ROUTE`, where it
 * was the six options alone and the menu was asserted `authenticated` by name. That expectation encoded
 * a measured defect: an ordinary operator rendered the complete administrative menu -- the `COADM01C`
 * identity band, all six option labels and a focused option field that dispatched them onward -- with an
 * empty message band, and the refusal arrived one screen late at `/users`. `app/cbl/COSGN00C.cbl`
 * L230-L240 transfers only an `'A'` operator to `COADM01C`, so the menu is administrative and the
 * refusal belongs at it.
 *
 * Assumptions: {@link COADM02Y_OPTION_PATHS} is left at six entries and the menu is added to the
 * expectation HERE rather than into that roster. The roster is the retyped copybook, and the copybook
 * names six options; widening it would redefine the reference to make the delivery fit, which is the
 * one thing a hand-retyped expectation exists to prevent.
 *
 * Assumptions: the expectation is a retyped roster rather than a filter of the table, and the public
 * class is asserted at the same time -- exactly one path may be reachable with no session, and it must
 * be sign-on, because a second public path would be a screen with no credential behind it.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function exactlySevenPathsAreAdministrative(): void {
  const administrative = ROUTE_TABLE.filter(isAdministrative).map(pathOf);
  expect([...administrative].sort()).toEqual([ADMIN_MENU_ROUTE, ...COADM02Y_OPTION_PATHS].sort());

  const publicPaths = ROUTE_TABLE.filter(isPublic).map(pathOf);
  expect(publicPaths).toEqual([SIGN_ON_ROUTE]);

  /*
   * WHY : ⚠️ Refactoring Rationale: the administrative menu is named individually as ADMINISTRATIVE
   *       here, where the withdrawn form of this block named it as `authenticated` and explained that
   *       it merely LISTS the six options. Transaction capture keeps its individual assertion for the
   *       reason the withdrawn note gave, which still holds: the reference gives main-menu option 8 the
   *       user type `'U'`, and the `(Admin Only)` label a reader might cite sits on a commented-out line
   *       of `app/cpy/COMEN02Y.cpy`, so reading it as live is the likeliest well-meant regression.
   */
  expect(accessOf(ADMIN_MENU_ROUTE)).toBe('administrative');
  expect(accessOf(TRANSACTION_ADD_PATH)).toBe('authenticated');
}

/**
 * The access class every published path must carry, written out path by path.
 *
 * ⚠️ Refactoring Rationale: this map is NEW, and it exists because of how V195 was able to happen. The
 * suite already counted the administrative rows and named two paths individually, so a row whose class
 * was neither counted nor named could be wrong without failing anything -- which is exactly what
 * `/admin` was. Writing all twenty-one classes out means a route added or reclassified later has to be
 * declared here as well, so an omission is a failing case rather than a review someone has to notice.
 *
 * Assumptions: it is a retyped roster and not a projection of `ROUTE_TABLE`, for the same reason
 * {@link COADM02Y_OPTION_PATHS} is: a projection would assert the table equals itself. The
 * classification each entry carries is the reference's, not the delivery's -- `app/cbl/COSGN00C.cbl`
 * L230-L240 for the administrative menu, `app/cpy/COADM02Y.cpy` for its six options, and
 * `app/cpy/COMEN02Y.cpy`'s `'U'` user type for every main-menu screen.
 */
const EXPECTED_ACCESS_BY_PATH: ReadonlyArray<readonly [string, string]> = [
  [SIGN_ON_ROUTE, 'public'],
  [MAIN_MENU_ROUTE, 'authenticated'],
  [ACCOUNT_VIEW_PATH, 'authenticated'],
  [ACCOUNT_UPDATE_PATH, 'authenticated'],
  [CARD_LIST_PATH, 'authenticated'],
  [CARD_DETAIL_ROUTE, 'authenticated'],
  [CARD_EDIT_ROUTE, 'authenticated'],
  [TRANSACTION_LIST_PATH, 'authenticated'],
  [TRANSACTION_ADD_PATH, 'authenticated'],
  [TRANSACTION_DETAIL_PATH, 'authenticated'],
  [REPORTS_PATH, 'authenticated'],
  [BILL_PAY_PATH, 'authenticated'],
  [AUTH_SUMMARY_PATH, 'authenticated'],
  [AUTH_DETAIL_PATH, 'authenticated'],
  [ADMIN_MENU_ROUTE, 'administrative'],
  [USER_LIST_PATH, 'administrative'],
  [USER_ADD_PATH, 'administrative'],
  [USER_UPDATE_PATH, 'administrative'],
  [USER_DELETE_PATH, 'administrative'],
  [REF_TYPE_LIST_PATH, 'administrative'],
  [REF_TYPE_EDIT_PATH, 'administrative'],
];

/**
 * Asserts every published path carries the access class the reference gives it, and that none is
 * left unclassified.
 *
 * Assumptions: the case asserts the map and the table cover the SAME paths before comparing classes,
 * so a path added to the table and forgotten here fails on the roster comparison rather than passing
 * silently because nothing looked for it.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function everyPathCarriesItsDeclaredAccessClass(): void {
  const declared = EXPECTED_ACCESS_BY_PATH.map(pathOfExpectation);
  expect([...declared].sort()).toEqual([...ROUTE_TABLE.map(pathOf)].sort());

  for (const [path, access] of EXPECTED_ACCESS_BY_PATH) {
    expect(accessOf(path)).toBe(access);
  }
}

/**
 * Reads the path from one entry of the expected-access roster.
 * @param {readonly [string, string]} entry - One `[path, access]` pair.
 * @returns {string} The path the pair classifies.
 */
function pathOfExpectation(entry: readonly [string, string]): string {
  return entry[0];
}

/**
 * Reports whether a table row is gated on the administrative claim.
 * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the published table.
 * @returns {boolean} True when the row is administrative.
 */
function isAdministrative(entry: (typeof ROUTE_TABLE)[number]): boolean {
  return entry.access === 'administrative';
}

/**
 * Reports whether a table row is reachable with no session at all.
 * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the published table.
 * @returns {boolean} True when the row is public.
 */
function isPublic(entry: (typeof ROUTE_TABLE)[number]): boolean {
  return entry.access === 'public';
}

/**
 * Reads the access class the table publishes for one path.
 * @param {string} path - A path the table is expected to publish.
 * @returns {string | undefined} The row's access class, or `undefined` when the path is absent.
 */
function accessOf(path: string): string | undefined {
  return ROUTE_TABLE.find(
    /**
     * Matches the row publishing the wanted path.
     * @param {(typeof ROUTE_TABLE)[number]} entry - One row of the published table.
     * @returns {boolean} True when the row publishes that path.
     */
    (entry) => entry.path === path,
  )?.access;
}

/**
 * Returns the ancestor route objects above the first route registering the wanted path.
 *
 * Assumptions: the chain is returned rather than a boolean, because the properties asserted from it
 * are about POSITION -- which branch a path sits in, and how many guards stand above it -- and a
 * boolean would have to decide those questions here instead of in the case that asks them.
 * @param {string} target - Path to locate.
 * @param {readonly RouteObject[]} routes - Subtree to search.
 * @param {readonly RouteObject[]} above - Ancestors already walked through to reach `routes`.
 * @returns {ReadonlyArray<RouteObject> | null} The ancestors above the match, or `null` when absent.
 *   Assumptions: the documented type is written in the generic form because `readonly T[] | null` is
 *   not a type the JSDoc grammar `ui/eslint.config.js` validates against can parse -- the annotation
 *   and the signature describe the same type either way.
 */
function ancestorsOf(
  target: string,
  routes: readonly RouteObject[] = CARD_DEMO_ROUTES,
  above: readonly RouteObject[] = [],
): readonly RouteObject[] | null {
  for (const route of routes) {
    if (route.path === target) {
      return above;
    }

    const below = ancestorsOf(target, route.children ?? [], [...above, route]);
    if (below !== null) {
      return below;
    }
  }

  return null;
}

/**
 * Asserts sign-on is a SIBLING of the guarded branch rather than a descendant of it.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and the property it pins is structural rather than
 * behavioural on purpose. Sign-on rendering for an anonymous caller -- which the case below asserts --
 * would hold just as well with sign-on nested inside the branch the guard sits in, because the guard is
 * nested one level deeper still. What that arrangement cannot survive is an edit: a guard moved up one
 * level, or a loader added to the shared branch, would put a credential requirement above the route
 * that ESTABLISHES the credential and lock every operator out of the application. As siblings the two
 * branches cannot acquire a common guard by accident.
 *
 * Assumptions: the branches are compared by route-object identity rather than by name, so the case
 * says nothing about what the guards are called and cannot be satisfied by a rename. The two chains
 * must share exactly ONE route object -- the pathless boundary at the top of the tree -- and the
 * guarded chain must be strictly deeper, which is the shape of "sign-on is outside what /menu is
 * inside".
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function signOnSitsBesideTheGuardedBranch(): void {
  const signOnChain = ancestorsOf(SIGN_ON_ROUTE);
  const menuChain = ancestorsOf(MAIN_MENU_ROUTE);
  const adminChain = ancestorsOf(USER_LIST_PATH);

  expect(signOnChain, `${SIGN_ON_ROUTE} is not registered`).not.toBeNull();
  expect(menuChain, `${MAIN_MENU_ROUTE} is not registered`).not.toBeNull();
  expect(adminChain, `${USER_LIST_PATH} is not registered`).not.toBeNull();

  const signOnAbove = signOnChain ?? [];
  const menuAbove = menuChain ?? [];
  const adminAbove = adminChain ?? [];

  const shared = signOnAbove.filter(
    /**
     * Keeps the ancestors sign-on shares with the guarded branch.
     * @param {RouteObject} route - One ancestor of sign-on.
     * @returns {boolean} True when the guarded branch has the same ancestor.
     */
    (route) => menuAbove.includes(route),
  );
  expect(shared, 'sign-on and the guarded branch may share only the top boundary').toHaveLength(1);
  expect(menuAbove.length, 'the guarded branch must stand deeper than sign-on').toBeGreaterThan(
    signOnAbove.length,
  );

  /*
   * Assumptions: the administrative subtree is asserted to sit INSIDE the guarded branch, one level
   * deeper, because that is the other half of the guard arrangement: every ancestor of the guarded
   * branch is an ancestor of an administrative path, and there is exactly one more above it.
   */
  for (const route of menuAbove) {
    expect(
      adminAbove,
      'every guarded ancestor must stand above the administrative subtree',
    ).toContain(route);
  }
  expect(adminAbove).toHaveLength(menuAbove.length + 1);
}

/**
 * Asserts an operator without the administrative claim is refused at the menu and shown NONE of it.
 *
 * ⚠️⚠️ Refactoring Rationale: this case asserted the OPPOSITE -- that a signed-on operator without the
 * claim "still reaches the administrative menu" -- on the argument that `app/cpy/COADM02Y.cpy` names six
 * options and the screen listing them is not one of them, so the residual was a concession written down
 * rather than hidden. The concession was larger than the note claimed. What an ordinary operator
 * received was the whole administrative capability inventory: the `COADM01C` identity band, every one of
 * the six option labels, and a focused option field that accepted an entry and moved them to `/users`,
 * where the refusal finally arrived -- with an EMPTY message band throughout, so nothing on the screen
 * said they were not entitled to be there. `app/cbl/COSGN00C.cbl` L230-L240 transfers only an `'A'`
 * operator to `COADM01C`.
 *
 * Assumptions: this case survives alongside the refusal sweep rather than being deleted as a duplicate
 * of it, because the sweep asserts the refusal and the ABSENCE OF THE CAPTION, and the caption is not
 * the disclosure. The disclosure was the option list, so this case names every destination the menu
 * offers and requires each label to be absent -- which is the property the withdrawn case measured as
 * present and called acceptable.
 * @returns {Promise<void>} Resolves once the refusal is on the glass and no option label is.
 */
async function anOrdinaryOperatorIsRefusedTheAdministrativeMenu(): Promise<void> {
  await signOnAs([USER_GROUP]);
  openRoute(ADMIN_MENU_ROUTE);

  expect(await screen.findByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeInTheDocument();
  expect(screen.queryByText(ADMIN_MENU_SUBTITLE)).toBeNull();

  for (const option of ADMIN_MENU_OPTIONS) {
    /*
     * WHY : Assumptions: the label is matched with a substring predicate rather than exactly, because
     *       the menu composes each option into one fixed-pitch line -- option number, separator and the
     *       35-character padded name -- so an exact match on the name alone would report absence for a
     *       label that is on the glass inside a longer line.
     * WHY : Assumptions: the roster is the CATALOGUE's option table rather than the admin screen's
     *       `ADMIN_MENU_DESTINATIONS`, which is keyed by program name and carries routes rather than
     *       labels. What was disclosed was the operator-visible names, so those are what this asserts.
     */
    expect(
      screen.queryByText(labelSubstringMatcher(option.name)),
      `${option.name.trim()} must not be disclosed to an operator without the claim`,
    ).toBeNull();
  }
}

/**
 * Builds a Testing Library text matcher that succeeds on any node CONTAINING the wanted label.
 *
 * Assumptions: the matcher reads the node's own text content rather than the accumulated text of its
 * ancestors, which is what `queryByText` supplies it, so a match reports the label painted on the
 * glass and not merely present somewhere in the document.
 * @param {string} label - The operator-visible option name to look for.
 * @returns {(content: string) => boolean} Predicate `queryByText` accepts.
 */
function labelSubstringMatcher(label: string): (content: string) => boolean {
  /**
   * Reports whether one candidate text carries the captured label.
   * @param {string} content - Text of one node the query offered.
   * @returns {boolean} `true` when the trimmed label appears within it.
   */
  return function carriesTheLabel(content: string): boolean {
    return content.includes(label.trim());
  };
}

/**
 * Builds a thunk that asks for one maintenance edit route, so its refusal can be asserted.
 *
 * Assumptions: a named factory rather than an inline thunk at the assertion, matching the convention
 * `ui/src/api/client.test.ts` established -- `ui/eslint.config.js` selects a function expression in
 * every position, so an inline thunk would owe its own block at each of the five specimens.
 * @param {string} candidate - Type code to ask for, valid or not.
 * @returns {() => string} A thunk invoking the published route builder.
 */
function buildingTheEditRouteFor(candidate: string): () => string {
  /**
   * Invokes the route builder for the captured candidate.
   * @returns {string} The concrete route, when the candidate is accepted.
   */
  return function buildOne(): string {
    return referenceTypeEditRoute(candidate);
  };
}

/** Registers the route-table cases. */
function routeTableCases(): void {
  beforeEach(clearSession);
  beforeEach(armTransport);
  afterEach(clearSession);

  it('declares exactly the twenty-one screen paths', theTableDeclaresExactlyTwentyOneScreenPaths);
  it('registers the path every screen and menu constant names', tableAgreesWithScreenConstants);
  it('reaches user maintenance through the browse', userMaintenanceIsReachedThroughTheBrowse);
  it(
    'resolves the reference add destination under the dynamic maintenance route',
    addDestinationResolvesUnderTheDynamicRoute,
  );
  it('publishes a producer for the maintenance template', theMaintenanceTemplateHasAProducer);
  it('resolves every destination the two menus name', everyMenuDestinationResolves);
  it(
    'aliases a published program for every keyless entry',
    eachKeylessEntryAliasesAPublishedProgram,
  );
  it(
    'reaches a distinct screen from every main-menu option',
    theMainMenuOptionsReachDistinctScreens,
  );
  it('registers one path per migrated program', theTableRegistersOnePathPerProgram);
  it(
    'gates the six administrative options and the menu listing them',
    exactlySevenPathsAreAdministrative,
  );
  it('publishes the declared access class for every path', everyPathCarriesItsDeclaredAccessClass);
  it('places sign-on beside the guarded branch, not inside it', signOnSitsBesideTheGuardedBranch);

  for (const pattern of REGISTERED_PATHS) {
    it(`resolves ${pattern} to a screen`, registeredPathResolvesToAScreen(pattern));
  }

  for (const path of ADMINISTRATIVE_PATHS) {
    it(
      `refuses a signed-on non-administrator at ${path}`,
      administrativePathRefusesANonAdministrator(path),
    );
  }
  it('frames sign-on without offering sign-off', signOnIsFramedWithoutASignOffControl);
  it(
    'paints an authenticated screen inside the application frame',
    anAuthenticatedScreenIsPaintedInsideTheFrame,
  );
  it('gives a self-composing screen exactly one title band', aSelfComposingScreenGetsOneTitleBand);
  it('paints a delegated title band in the frame', theFramePaintsADelegatedTitleBand);
  it('paints the summary screen delegated title band', theFramePaintsTheSummaryTitleBand);
  it(
    'admits an administrator to the administrative menu',
    anAdministratorReachesTheAdministrativeMenu,
  );
  it(
    'refuses a signed-on operator without the claim at the administrative menu',
    anOrdinaryOperatorIsRefusedTheAdministrativeMenu,
  );
  it('shadows no declared path with the catch-all', theCatchAllShadowsNoDeclaredPath);
  it('resolves an unknown path to the bounded not-found result', anUnknownPathResolvesToNotFound);
  it('offers a framed way out of the not-found surface', theNotFoundSurfaceOffersAFramedWayOut);
  it(
    'lists the ordinary destinations for an operator holding no admin group',
    theNotFoundSurfaceListsTheOrdinaryDestinations,
  );
  it(
    'reaches the not-found surface with no session at all',
    theNotFoundSurfaceIsReachableAnonymously,
  );
}

describe('route table', routeTableCases);
