/**
 * @file Proves the route table in `ui/src/router.tsx` mounts what it claims: every authored screen is
 * reachable, every authenticated screen is painted inside the shared frame, and every administrative
 * screen is behind the administrative guard.
 *
 * Purpose
 * -------
 * Three properties are asserted here, and each of them was FALSE before the route table was
 * restructured, in a way nothing failed to report:
 *
 * 1. Registration. Four authored screens -- the account view, the pending-authorization summary, the
 *    transaction-type list and user maintenance -- were mounted at no route, and four destinations the
 *    menus navigate to had no screen at all. A route table cannot assert what is absent from it, so
 *    each of those resolved silently to the not-found result. The cases below compare the table's own
 *    path constants against the constants the screens and menus publish, so an unregistered
 *    destination now fails here.
 * 2. The frame. `ui/src/layout/AppShell.tsx` was mounted nowhere, and two screens omit the shared title
 *    band on the documented ground that the shell paints it -- so rows 1 and 2 of those screens
 *    rendered nothing. The cases below assert the frame is present on an authenticated route, absent on
 *    sign-on, and that the delegated band actually carries the delegating screen's identity.
 * 3. The administrative boundary. `RequireAdmin` was authored, documented as the migrated form of the
 *    reference's user-type branch, and imported by nothing. The cases below assert each administrative
 *    route admits an administrator and refuses a signed-on non-administrator with the catalogued
 *    sentence.
 *
 * Assumptions: the expectations are the CONSTANTS the screens and the catalog publish rather than
 * retyped strings, for the reason `ui/src/screens/cardScreenShell.test.tsx` records -- a retyped
 * sentence passes for a screen that has drifted, provided the test drifted with it.
 *
 * Assumptions: the table under test uses `BrowserRouter`, so a case selects its route by pushing a
 * history entry before rendering rather than by supplying an initial entry. That is deliberate: the
 * property being proved is the shape of the SHIPPED table, and substituting a memory router here would
 * prove the shape of a table assembled by the test instead.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the card screen tests record: `ui/eslint.config.js` requires a documentation block on a
 * function expression in any position, and Prettier moves a block comment that follows an argument
 * comma onto the preceding literal, which detaches it from the function it documents.
 */

import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { installApiHarness, removeApiHarness } from './test/apiHarness';
import { endAnySession, establishSession } from './test/sessionHarness';

import { APP_SHELL_TEST_ID, SHELL_SIGN_OFF_CONTROL_TEST_ID } from './layout/AppShell';
import { ACCESS_DENIED_ADMIN_ONLY } from './messages/messages';
import {
  ACCOUNT_UPDATE_PATH,
  ACCOUNT_VIEW_PATH,
  AUTH_DETAIL_PATH,
  AUTH_SUMMARY_PATH,
  CARD_LIST_PATH,
  CardDemoRouter,
  REF_TYPE_EDIT_PATH,
  REF_TYPE_LIST_PATH,
  TRANSACTION_ADD_PATH,
  USER_UPDATE_PATH,
  USER_UPDATE_SELECTED_PATH,
} from './router';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { SIGN_ON_ROUTE } from './routes/guards';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE } from './routes/navigation';
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
 * WHY : ⚠️ Refactoring Rationale: a `carddemo.id-token` session-storage key was named here and written to
 *       directly, and that key no longer exists. `ui/src/hooks/useAuth.ts` holds the session in a module
 *       variable installed by one private validator, so writing storage arranged NOTHING -- every case
 *       below rendered as an anonymous caller and the guards sent it to sign-on, which is why they could
 *       not find the screens they asked for. `ui/src/test/sessionHarness.ts` establishes a session the
 *       only way one can be established, by driving the real exchange, so a case that needs a signed-on
 *       operator also proves a session can be established that way.
 */

/** Group name the administrative guard requires. */
const ADMIN_GROUP = 'carddemo-admin';

/** Group name a non-administrative operator carries. */
const USER_GROUP = 'carddemo-user';

/**
 * Title the bounded not-found result renders.
 *
 * Assumptions: it is declared once here and used by both the registration cases and the catch-all case,
 * because the two assert OPPOSITE things about the same string -- one requires its absence and the other
 * its presence -- and two spellings of it could drift into agreeing with each other.
 */
const NOT_FOUND_TITLE = 'Screen not found';

/**
 * The complete set of paths the shipped table registers.
 *
 * Assumptions: it is assembled from the table's OWN exported constants rather than retyped, so a
 * renamed path cannot leave this set describing the previous name. The two menu routes and the card
 * routes come from the modules that own them, which is where the table takes them from too.
 */
const REGISTERED_PATHS: readonly string[] = [
  MAIN_MENU_ROUTE,
  ACCOUNT_VIEW_PATH,
  ACCOUNT_UPDATE_PATH,
  CARD_LIST_PATH,
  CARD_DETAIL_ROUTE,
  CARD_EDIT_ROUTE,
  TRANSACTION_ADD_PATH,
  AUTH_SUMMARY_PATH,
  AUTH_DETAIL_PATH,
  ADMIN_MENU_ROUTE,
  USER_UPDATE_PATH,
  USER_UPDATE_SELECTED_PATH,
  REF_TYPE_LIST_PATH,
  REF_TYPE_EDIT_PATH,
];

/**
 * Every concrete path that must be reachable only by an administrator.
 *
 * Assumptions: the reference-maintenance ADD path is listed as its concrete sentinel form rather than as
 * the dynamic pattern, because that is the path the administrative menu and the list screen actually
 * navigate to -- so this is the spelling a non-administrator would arrive with.
 */
const ADMINISTRATIVE_PATHS: readonly string[] = [
  ADMIN_MENU_ROUTE,
  USER_UPDATE_PATH,
  '/users/000000AA/edit',
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
 * Selects a route by pushing a history entry, then renders the shipped table.
 * @param {string} path - Concrete path to open.
 * @returns {void} The tree is rendered into the testing library's container.
 */
function openRoute(path: string): void {
  window.history.pushState({}, '', path);
  render(<CardDemoRouter />);
}

/**
 * Ends any held session, removes the transport harness and returns the history to the root.
 *
 * Assumptions: the session is ENDED rather than storage cleared, and the harness lifecycle is bound to
 * the same hook. `endAnySession` unmounts first and then discards the session, which is the order that
 * keeps React from reporting an update outside `act` when discarding notifies a tree Testing Library has
 * not yet torn down.
 * @returns {void} Nothing; no session is held and the history is at the root.
 */
function clearSession(): void {
  endAnySession();
  removeApiHarness();
  window.history.pushState({}, '', '/');
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
  expect(USER_UPDATE_PATH).toBe(ADMIN_MENU_DESTINATIONS.COUSR02C);
  expect(REF_TYPE_LIST_PATH).toBe(ADMIN_MENU_DESTINATIONS.COTRTLIC);
  expect(REF_TYPE_EDIT_PATH).toBe(REF_TYPE_EDIT_ROUTE);
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
  expect(REF_TYPE_EDIT_PATH).toBe(`${REF_TYPE_LIST_PATH}/:typeCd`);
}

/**
 * Asserts every destination the two menus name is a path the table registers.
 *
 * Assumptions: only the non-null destinations are checked. A null entry is an option this delivery
 * does not mount, which each menu answers with the reference's own unavailable-option sentence, so a
 * null is a documented absence rather than a gap.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function everyMenuDestinationIsRegistered(): void {
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
    /*
     * Assumptions: the add destination is compared against the DYNAMIC route it resolves under,
     * because it is a concrete path rather than a pattern -- the case above proves that resolution
     * separately.
     */
    const pattern = destination === REF_TYPE_ADD_ROUTE ? REF_TYPE_EDIT_PATH : destination;
    expect(REGISTERED_PATHS).toContain(pattern);
  }
}

/**
 * Asserts sign-on is framed like every other screen, and is offered no sign-off.
 *
 * WHY : ⚠️ Refactoring Rationale: this asserted the frame was ABSENT on the sign-on route. Sign-on
 * delegates its title band, its row-23 message and its row-24 legend through `useShellSlot` exactly as
 * the other nine screens do, so an unframed sign-on had nothing to paint any of them: the three refusal
 * sentences `app/cbl/COSGN00C.cbl` L211-L256 writes were computed and discarded, and the mapset's own
 * `ENTER=Sign-on` legend was absent from the first screen every operator sees. What the absence was
 * really guarding against is a sign-off control offered to an operator with no session, and that is
 * asserted directly instead -- the shell renders that control only while a session is held, so it is
 * absent here for a reason that does not depend on where the route sits.
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
 * frame -- which is exactly what rendered nowhere while the frame was unmounted.
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
 * @param {string} pattern - Route pattern, possibly carrying `:name` segments.
 * @returns {string} A concrete path that matches the pattern.
 */
function concretePathFor(pattern: string): string {
  return pattern.replace(/:[A-Za-z]+/gu, 'synthetic-segment');
}

/**
 * Builds a case asserting one registered path resolves to a screen rather than to the not-found result.
 *
 * Refactoring Rationale: this is the case that proves REGISTRATION, and the constant-comparison cases
 * above do not. Those compare one published spelling against another, so a route element deleted from
 * the table while its constant stayed would satisfy every one of them -- which is precisely the failure
 * mode that let four authored screens sit unmounted. Rendering each path and requiring that the
 * catch-all did NOT win is the assertion that cannot be satisfied by a constant alone.
 *
 * Refactoring Rationale: one case PER path, built by this factory, rather than one case looping over
 * every path. The loop was written first and timed out: fourteen routes each resolve a lazily loaded
 * chunk, which exceeds the default per-case budget, and a case that times out mid-render leaves its
 * tree mounted -- so the two cases after it failed on elements belonging to the abandoned tree rather
 * than on anything they asserted. Separate cases get separate budgets and the testing library unmounts
 * between them.
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
      await waitFor(
        /**
         * Waits for the lazily loaded screen to commit inside the frame.
         * @returns {void} Nothing; failure is reported by the expectation.
         */
        () => {
          expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
        },
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
 * Asserts an unknown path still resolves to the bounded not-found result.
 *
 * Assumptions: the case signs on first, so the assertion proves the not-found route is reached rather
 * than the sign-on redirect being reached -- the failure a guarded catch-all would have produced.
 * @returns {Promise<void>} Resolves once the not-found result has been found.
 */
async function anUnknownPathResolvesToNotFound(): Promise<void> {
  await signOnAs([ADMIN_GROUP]);
  openRoute('/no-such-carddemo-screen');
  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(screen.queryByTestId(APP_SHELL_TEST_ID)).toBeNull();
}

/** Registers the route-table cases. */
function routeTableCases(): void {
  beforeEach(clearSession);
  beforeEach(armTransport);
  afterEach(clearSession);

  it('registers the path every screen and menu constant names', tableAgreesWithScreenConstants);
  it(
    'resolves the reference add destination under the dynamic maintenance route',
    addDestinationResolvesUnderTheDynamicRoute,
  );
  it('registers every destination the two menus name', everyMenuDestinationIsRegistered);

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
  it('resolves an unknown path to the bounded not-found result', anUnknownPathResolvesToNotFound);
}

describe('route table', routeTableCases);
