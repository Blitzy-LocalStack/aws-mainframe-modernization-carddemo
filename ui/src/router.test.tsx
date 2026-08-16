/**
 * @file Integration tests for the route table in `ui/src/router.tsx` and its composition with the
 * application shell.
 *
 * Purpose
 * -------
 * Prove three things the route table alone cannot assert about itself. First, that every
 * authenticated route resolves to its screen AND renders that screen INSIDE the shell, so the
 * header band, the skip link and the sign-off key are reachable from each one. Second, that the
 * four screens which previously had no address are now addressable. Third, that the two
 * administered routes admit an administrator and refuse an ordinary operator.
 *
 * Why the screens are substituted
 * -------------------------------
 * Alternatives Considered: rendering the REAL screen modules, which would drag every resource
 * client, form and table in the tree into a test whose subject is the route table. The failure mode
 * that invites is the one worth avoiding: a screen's own data-loading defect would fail cases here,
 * where it says nothing about routing, and a routing defect could be masked by a screen that
 * refuses to render for an unrelated reason. Each screen is therefore replaced by a marker, and the
 * shell and both guards are kept REAL, because those are the collaborators whose composition with
 * the table is the subject.
 *
 * Trade-offs: this proves a route resolves to the right MODULE and that the module renders within
 * the shell's content region. It does not prove what that module paints -- each screen's own test
 * file owns that, and `ui/src/layout/screenHeaderClock.test.tsx` separately owns the rule that every
 * screen supplies a header instant by one of the two supply routes.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, within } from '@testing-library/react';
import { useParams } from 'react-router';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  APP_SHELL_TEST_ID,
  SHELL_CONTENT_ELEMENT_ID,
  SHELL_SIGN_OFF_LABEL,
  SKIP_TO_CONTENT_LABEL,
} from './layout/AppShell';
import { ACCESS_DENIED_ADMIN_ONLY } from './messages/messages';
import { installApiHarness, removeApiHarness } from './test/apiHarness';
import { endAnySession, establishSession } from './test/sessionHarness';

/*
 * WHY : ⚠️ Refactoring Rationale: a `carddemo.id-token` session-storage key was named here and written
 *       to directly, and that key no longer exists -- `ui/src/hooks/useAuth.ts` holds the session in a
 *       module variable installed by one private validator. Writing storage therefore arranged nothing,
 *       so every case below rendered as an anonymous caller and the guards replaced the screen it asked
 *       for with sign-on. `ui/src/test/sessionHarness.ts` establishes a session by driving the real
 *       exchange, which is the only path that installs one.
 */

/**
 * Builds a screen stub that renders a single recognisable marker.
 *
 * Assumptions: the marker is returned as the component rather than as an element, because `lazy`
 * resolves a module to a COMPONENT and the substituted modules must present the same shape the real
 * ones do.
 * @param {string} marker - Text the stub paints, used as the route's identity in an assertion.
 * @returns {() => ReactElement} A component painting exactly that marker.
 */
function stubScreen(marker: string): () => ReactElement {
  /**
   * Paints the marker for the substituted screen.
   * @returns {ReactElement} A single element carrying the marker text.
   */
  return function StubScreen(): ReactElement {
    return <div>{marker}</div>;
  };
}

/**
 * Paints the user-update marker together with the route parameter the screen reads.
 *
 * Assumptions: this stub reads `useParams` with the SAME key the real screen reads (`id`), so a case
 * asserting the identifier reaches the screen proves the route table and the screen agree on the
 * spelling. A mismatch resolves to `undefined` in react-router without any diagnostic, so the only
 * way to prove the two sides agree is to observe a value arriving.
 * @returns {ReactElement} The marker followed by the identifier the route supplied.
 */
function StubUserUpdateScreen(): ReactElement {
  const { id } = useParams<{ id: string }>();
  return <div>{`USER UPDATE:${id ?? 'NONE'}`}</div>;
}

vi.mock(
  './screens/signon',
  /**
   * Substitutes the sign-on screen so the route table is exercised without its dependencies.
   * @returns {{ SignOnScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ SignOnScreen: stubScreen('SIGN ON') }),
);

vi.mock(
  './screens/accountView',
  /**
   * Substitutes the account-view screen so the route table is exercised without its dependencies.
   * @returns {{ AccountViewScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ AccountViewScreen: stubScreen('ACCOUNT VIEW') }),
);

vi.mock(
  './screens/accountUpdate',
  /**
   * Substitutes the account-update screen so the route table is exercised without its dependencies.
   * @returns {{ AccountUpdateScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ AccountUpdateScreen: stubScreen('ACCOUNT UPDATE') }),
);

vi.mock(
  './screens/cardList',
  /**
   * Substitutes the card-list screen so the route table is exercised without its dependencies.
   * @returns {{ CardListScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ CardListScreen: stubScreen('CARD LIST') }),
);

vi.mock(
  './screens/cardDetail',
  /**
   * Substitutes the card-detail screen so the route table is exercised without its dependencies.
   * @returns {{ CardDetailScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ CardDetailScreen: stubScreen('CARD DETAIL') }),
);

vi.mock(
  './screens/cardUpdate',
  /**
   * Substitutes the card-update screen so the route table is exercised without its dependencies.
   * @returns {{ CardUpdateScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ CardUpdateScreen: stubScreen('CARD UPDATE') }),
);

vi.mock(
  './screens/transactionAdd',
  /**
   * Substitutes the transaction-capture screen so the route table is exercised without its dependencies.
   * @returns {{ TransactionAddScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ TransactionAddScreen: stubScreen('TRANSACTION ADD') }),
);

vi.mock(
  './screens/authSummary',
  /**
   * Substitutes the authorization-summary screen so the route table is exercised without its dependencies.
   * @returns {{ AuthSummaryScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ AuthSummaryScreen: stubScreen('AUTH SUMMARY') }),
);

vi.mock(
  './screens/refTypeList',
  /**
   * Substitutes the reference-type list screen so the route table is exercised without its dependencies.
   * Assumptions: this module publishes its screen as the DEFAULT export, which is why the
   * substitution names `default` where its siblings name a screen. The route table loads it
   * without the adapter the others need, so a substitution asserting a named export would
   * resolve to `undefined` and render nothing.
   * @returns {{ default: () => ReactElement }} The substituted module shape.
   */
  () => ({ default: stubScreen('REF TYPE LIST') }),
);

vi.mock(
  './screens/userUpdate',
  /**
   * Substitutes the user-update screen so the route table is exercised without its dependencies.
   * Assumptions: this substitution reads the route parameter rather than painting a fixed
   * marker, because the identifier arriving under the name the screen reads is itself an
   * assertion this file makes.
   * @returns {{ UserUpdateScreen: () => ReactElement }} The substituted module shape.
   */
  () => ({ UserUpdateScreen: StubUserUpdateScreen }),
);

/*
 * WHY : Refactoring Rationale: a local `idTokenFor` composed the identity token here and is withdrawn.
 *       `ui/src/test/sessionHarness.ts` composes the token the exchange is answered with, and it carries
 *       BOTH claims the installer compares -- the group list and the user name it checks against the
 *       identifier the token set arrived with. A locally composed token carried only the first, so it
 *       looked equivalent and would be refused by the real installer.
 */

/**
 * Establishes a held session for an operator carrying the supplied groups.
 * @param {readonly string[]} groups - Group names the operator's identity claim asserts.
 * @returns {Promise<void>} Resolves once the session is held and the guards will admit the operator.
 */
async function signOnAs(groups: readonly string[]): Promise<void> {
  await establishSession({ groups });
}

/**
 * Renders the real route table at the supplied address.
 *
 * Assumptions: the router under test mounts `BrowserRouter`, so the address is set on the history
 * before rendering rather than passed as an initial entry. The module is imported through `await
 * import` BELOW the substitutions above, because a static import would be evaluated before the stub
 * helpers this file defines are initialised.
 * @param {string} path - Address to place on the history before mounting.
 * @returns {Promise<void>} Resolves once the table is mounted.
 */
async function renderRouterAt(path: string): Promise<void> {
  window.history.pushState({}, '', path);
  const { CardDemoRouter } = await import('./router');
  render(<CardDemoRouter />);
}

/**
 * Returns the shell's content region, failing the case if the shell is not mounted.
 * @returns {HTMLElement} The element the shell nests routed screens inside.
 */
function shellContentRegion(): HTMLElement {
  const region = document.getElementById(SHELL_CONTENT_ELEMENT_ID);
  expect(region).not.toBeNull();
  return region as HTMLElement;
}

/**
 * Asserts the shell is mounted, offers its accessibility affordances, and contains the marker.
 *
 * Assumptions: containment is asserted rather than mere co-presence. Both elements being somewhere
 * in the document would also hold if the shell and the screen were siblings, which is precisely the
 * arrangement that would leave the screen outside the content region the skip link targets.
 * @param {string} marker - Text the routed screen paints.
 * @returns {Promise<void>} Resolves once the lazily-loaded screen has appeared.
 */
async function expectScreenInsideShell(marker: string): Promise<void> {
  expect(await screen.findByText(marker)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.getByText(SKIP_TO_CONTENT_LABEL)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: SHELL_SIGN_OFF_LABEL })).toBeInTheDocument();
  expect(within(shellContentRegion()).getByText(marker)).toBeInTheDocument();
}

/** Every authenticated address, paired with the marker its screen paints. */
const AUTHENTICATED_ROUTES: ReadonlyArray<readonly [string, string]> = [
  ['/account/view', 'ACCOUNT VIEW'],
  ['/account/update', 'ACCOUNT UPDATE'],
  ['/cards', 'CARD LIST'],
  ['/transactions/new', 'TRANSACTION ADD'],
  ['/authorizations', 'AUTH SUMMARY'],
];

/** The two addresses the administrative group claim gates. */
const ADMINISTERED_ROUTES: ReadonlyArray<readonly [string, string]> = [
  ['/reference/transaction-types', 'REF TYPE LIST'],
  ['/users/U0000001/edit', 'USER UPDATE:U0000001'],
];

/**
 * Ends any held session, replaces the transport harness and restores the address.
 *
 * Assumptions: the session is ENDED rather than storage cleared, and the harness is reinstalled in the
 * same call so a file keeps one lifecycle. `endAnySession` unmounts before discarding, which is the order
 * that keeps React from reporting an update outside `act` when the discard notifies a live tree.
 * @returns {void} Nothing; no session is held, the harness is armed and the history is at the root.
 */
function resetSessionAndAddress(): void {
  endAnySession();
  removeApiHarness();
  installApiHarness();
  window.history.pushState({}, '', '/');
}

/**
 * Registers the cases proving each authenticated route renders its screen inside the shell.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function shellCompositionCases(): void {
  beforeEach(resetSessionAndAddress);
  afterEach(resetSessionAndAddress);

  it.each(AUTHENTICATED_ROUTES)(
    'renders %s inside the shell for a signed-on operator',
    /**
     * Asserts one non-administered route composes with the shell.
     * @param {string} path - Address under test.
     * @param {string} marker - Marker its screen paints.
     * @returns {Promise<void>} Resolves once the assertions have run.
     */
    async (path: string, marker: string): Promise<void> => {
      await signOnAs(['carddemo-user']);

      await renderRouterAt(path);

      await expectScreenInsideShell(marker);
    },
  );

  it.each(ADMINISTERED_ROUTES)(
    'renders %s inside the shell for an administrator',
    /**
     * Asserts one administered route composes with the shell.
     * @param {string} path - Address under test.
     * @param {string} marker - Marker its screen paints.
     * @returns {Promise<void>} Resolves once the assertions have run.
     */
    async (path: string, marker: string): Promise<void> => {
      await signOnAs(['carddemo-admin']);

      await renderRouterAt(path);

      await expectScreenInsideShell(marker);
    },
  );
}

/*
 * WHY : ⚠️ Refactoring Rationale: the case below asserted sign-on paints NO shell, on the ground that
 *       the shell offers sign-off and a signed-off operator has nothing to sign off from. The premise
 *       is right and the conclusion does not follow: the shell renders its sign-off control only while
 *       a session is held, so an unframed sign-on was not what kept the control away -- and being
 *       unframed is what left the screen with no row-23 message line and no row-24 legend, since it
 *       delegates both. All three of the reference's refusal sentences were therefore computed and
 *       discarded. The frame is asserted PRESENT and the control ABSENT, which is the pair the
 *       original intent actually describes.
 */
/**
 * Sign-on is painted inside the one frame, and the frame offers no sign-off with no session held.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function framesSignOnWithoutOfferingSignOff(): Promise<void> {
  await renderRouterAt('/signon');

  expect(await screen.findByText('SIGN ON')).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.queryByText(SHELL_SIGN_OFF_LABEL)).not.toBeInTheDocument();
}

/**
 * An operator holding no credential reaches sign-on rather than the screen they asked for.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function redirectsAnUnauthenticatedOperator(): Promise<void> {
  await renderRouterAt('/account/view');

  expect(await screen.findByText('SIGN ON')).toBeInTheDocument();
  /*
   * WHY : ⚠️ Refactoring Rationale: the SCREEN is asserted absent and the frame is no longer, because
   *       the frame is now above the guard rather than behind it. What this case is about is that the
   *       guard sends the operator to sign-on instead of rendering the account screen, and the marker
   *       below is the whole of that; asserting the frame away as well asserted the guard's position
   *       in the tree, which is `ui/src/router.tsx`'s decision and not this case's subject.
   */
  expect(screen.queryByText('ACCOUNT VIEW')).not.toBeInTheDocument();
  expect(screen.queryByText(SHELL_SIGN_OFF_LABEL)).not.toBeInTheDocument();
}

/**
 * The route parameter is spelled `id` on both sides, so the identifier arrives.
 *
 * Assumptions: the VALUE is asserted rather than the screen merely appearing, because a name mismatch
 * between the route pattern and the screen's own read resolves to `undefined` silently -- the screen
 * still mounts, and only the identifier it paints reveals the fault.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function carriesTheRouteIdentifierToUserUpdate(): Promise<void> {
  await signOnAs(['carddemo-admin']);

  await renderRouterAt('/users/ADMIN001/edit');

  expect(await screen.findByText('USER UPDATE:ADMIN001')).toBeInTheDocument();
}

/**
 * An address the table does not match renders the bounded not-found result, outside the frame.
 *
 * ⚠️ Refactoring Rationale: this case is RESTORED. A remediation replaced the catch-all with a
 * coverage table over all 21 specified routes, so an unauthored screen answered deliberately instead of
 * dead-ending; the delivered table answers the same way with a single bounded result at `*`, and the
 * case that held the catch-all to anything did not survive with it. The property is worth a gate rather
 * than a note, because the catch-all is the ONE surface an unauthenticated caller reaches without
 * passing the guard.
 *
 * Assumptions: the result is asserted OUTSIDE the shell. `*` sits beside the frame deliberately -- there
 * is no screen to frame, so painting a header band, a message line and a legend around a not-found
 * result would offer an operator a turn that leads nowhere.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anUnknownAddressRendersTheBoundedNotFoundResult(): Promise<void> {
  // Assumptions: the heading is read from the module through the SAME dynamic import the render helper
  //   uses, because a static import of the route table evaluates it before the screen stubs are
  //   registered -- which is why nothing in this file imports it at the top.
  const { NOT_FOUND_TITLE } = await import('./router');

  await renderRouterAt('/no/such/screen');

  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(screen.queryByTestId(APP_SHELL_TEST_ID)).not.toBeInTheDocument();
  expect(screen.queryByText('SIGN ON')).not.toBeInTheDocument();
}

/**
 * The not-found result reflects no part of the address that produced it.
 *
 * Assumptions: reflection is asserted absent for a security reason and not a cosmetic one. The
 * catch-all is reachable by an unauthenticated caller with any address, so echoing the requested path
 * into the rendered document would put attacker-chosen text on a page this application serves -- and it
 * is one interpolation away, since the path is available to the component through the router.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theNotFoundResultReflectsNoPartOfTheAddress(): Promise<void> {
  const { NOT_FOUND_TITLE } = await import('./router');
  const distinctive = '/zzmarkerzz-not-a-route';

  await renderRouterAt(distinctive);

  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
  expect(document.body.textContent).not.toContain('zzmarkerzz');
}

/**
 * Registers the cases covering the authentication boundary the shell sits inside.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function authenticationBoundaryCases(): void {
  beforeEach(resetSessionAndAddress);
  afterEach(resetSessionAndAddress);

  it('frames sign-on without offering sign-off', framesSignOnWithoutOfferingSignOff);
  it(
    'renders the bounded not-found result for an unknown address',
    anUnknownAddressRendersTheBoundedNotFoundResult,
  );
  it(
    'reflects no part of the address in the not-found result',
    theNotFoundResultReflectsNoPartOfTheAddress,
  );

  it(
    'redirects an unauthenticated operator away from an authenticated route',
    redirectsAnUnauthenticatedOperator,
  );
}

/**
 * Registers the cases covering the administrative group claim on the two administered routes.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function administrativeGuardCases(): void {
  beforeEach(resetSessionAndAddress);
  afterEach(resetSessionAndAddress);

  it.each(ADMINISTERED_ROUTES)(
    'refuses an ordinary operator at %s',
    /**
     * Asserts an authenticated non-administrator is refused rather than admitted or redirected.
     * @param {string} path - Administered address under test.
     * @param {string} marker - Marker the guarded screen would have painted.
     * @returns {Promise<void>} Resolves once the assertions have run.
     */
    async (path: string, marker: string): Promise<void> => {
      await signOnAs(['carddemo-user']);

      await renderRouterAt(path);

      expect(await screen.findByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeInTheDocument();
      expect(screen.queryByText(marker)).not.toBeInTheDocument();
      expect(screen.queryByText('SIGN ON')).not.toBeInTheDocument();
    },
  );

  it(
    'carries the route identifier to the user-update screen under the name it reads',
    carriesTheRouteIdentifierToUserUpdate,
  );
}

describe('CardDemoRouter shell composition', shellCompositionCases);
describe('CardDemoRouter authentication boundary', authenticationBoundaryCases);
describe('CardDemoRouter administrative guard', administrativeGuardCases);
