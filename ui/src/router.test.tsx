/**
 * @file Integration tests for the route table in `ui/src/router.tsx` and its composition with the
 * application shell.
 *
 * Purpose
 * -------
 * Prove three things the route table alone cannot assert about itself. First, that every
 * authenticated route resolves to its screen AND renders that screen INSIDE the shell, so the
 * header band, the skip link and the sign-off key are reachable from each one. Second, that the
 * four screens which previously had no address are now addressable. Third, that the administered
 * routes admit an administrator and refuse an ordinary operator, and that each parameterised route
 * hands its screen the value under the name that screen reads.
 *
 * Assumptions: the cases mount the SHIPPED route objects in a memory router rather than rendering a
 * router component over jsdom's history, which is what `ui/src/router.tsx` now offers and the more
 * robust of the two -- the initial location is an argument, so no case can inherit a location another
 * left behind. The tree itself is the delivered one, guards and shell mounts included.
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
import { createMemoryRouter, RouterProvider, useParams } from 'react-router';
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
 * WHY : Assumptions: a session is arranged ONLY through `ui/src/test/sessionHarness.ts`, which drives
 *       the real token exchange. There is no storage key to write: `ui/src/hooks/useAuth.ts` holds the
 *       session in a module variable installed by one private validator, so a case that wrote storage
 *       would arrange nothing and would render as an anonymous caller -- the guards would then replace
 *       the screen it asked for with sign-on, and the case would fail describing the wrong thing.
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

/**
 * Paints the reference-maintenance marker together with the type code the route supplied.
 *
 * Assumptions: this stub reads `useParams().cd`, the name the frozen route table gives the
 * transaction-type key and the name `ui/src/screens/refTypeEdit/index.tsx` reads. It is a stub rather
 * than the real screen deliberately: the property under test belongs to the ROUTE PATTERN, so reading
 * the parameter here proves the pattern publishes it under that name whatever the screen does with it.
 * A mismatch resolves to `undefined` in react-router with no diagnostic, so the only proof is a value
 * arriving.
 * @returns {ReactElement} The marker followed by the type code the route supplied.
 */
function StubRefTypeEditScreen(): ReactElement {
  const { cd } = useParams<{ cd: string }>();
  return <div>{`REF TYPE EDIT:${cd ?? 'NONE'}`}</div>;
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
  './screens/refTypeEdit',
  /**
   * Substitutes the reference-type maintenance screen so the route table is exercised without its
   * dependencies.
   * Assumptions: `default` is named because this module publishes its screen as the default export,
   * exactly as its list sibling above does, and a substitution naming a screen instead would resolve
   * to `undefined` and render nothing.
   * @returns {{ default: () => ReactElement }} The substituted module shape.
   */
  () => ({ default: StubRefTypeEditScreen }),
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
 * WHY : Assumptions: the identity token is composed ONLY by `ui/src/test/sessionHarness.ts`, never
 *       locally. That harness carries BOTH claims the installer compares -- the group list and the
 *       user name it checks against the identifier the token set arrived with -- and a locally
 *       composed token carrying only the first looks equivalent and is refused by the real installer.
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
 * Renders the SHIPPED route objects at the supplied address, in a memory router.
 *
 * ⚠️ Refactoring Rationale: the address was previously pushed onto jsdom's history and the exported
 * router COMPONENT rendered over it, because the route table built its own `BrowserRouter` internally
 * and offered no other way in. It now exports its route objects, so the initial entry is passed to
 * `createMemoryRouter` instead. That is the more robust arrangement for three reasons: the initial
 * location is an argument rather than ambient state, so a case cannot be affected by a location a
 * previous case left behind; no module reset is needed to re-home the router; and nothing in the file
 * has to keep jsdom's history and the router's idea of the location in step.
 *
 * Assumptions: a memory router over the SHIPPED array is not a re-declaration of the tree. The routes,
 * the guards, the lazy boundaries and both shell mounts are the delivered ones -- only the history
 * implementation differs -- so an unmounted route still fails here, where a test supplying its own
 * routes could only ever prove that a screen renders once mounted.
 *
 * Assumptions: the module is imported through `await import` BELOW the substitutions above, because a
 * static import evaluates the route table -- and therefore the stub factories it resolves -- before
 * this file's helper declarations are initialised.
 * @param {string} path - Address the router opens on.
 * @returns {Promise<void>} Resolves once the table is mounted.
 */
async function renderRouterAt(path: string): Promise<void> {
  const { CARD_DEMO_ROUTES } = await import('./router');
  const router = createMemoryRouter([...CARD_DEMO_ROUTES], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
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

/**
 * The administered addresses, paired with the marker each one's screen paints.
 *
 * Assumptions: three of the six gated paths are exercised here, chosen because each carries a
 * different shape -- a static path, a path parameterised by an operator identifier, and a path
 * parameterised by a two-character type code. `ui/src/routerRoutes.test.tsx` asserts the gated SET is
 * exactly the six administrative options; what these cases add is that the guard admits and refuses at
 * a rendered route, which is a property of a mounted tree rather than of the table.
 */
const ADMINISTERED_ROUTES: ReadonlyArray<readonly [string, string]> = [
  ['/reference/transaction-types', 'REF TYPE LIST'],
  ['/reference/transaction-types/AA', 'REF TYPE EDIT:AA'],
  ['/users/U0000001/edit', 'USER UPDATE:U0000001'],
];

/**
 * Ends any held session and replaces the transport harness.
 *
 * Assumptions: the session is ENDED rather than storage cleared, and the harness is reinstalled in the
 * same call so a file keeps one lifecycle. `endAnySession` unmounts before discarding, which is the order
 * that keeps React from reporting an update outside `act` when the discard notifies a live tree.
 *
 * Refactoring Rationale: this no longer resets jsdom's history. Each case now opens a memory router on
 * an explicit initial entry, so the ambient location influences nothing and resetting it would suggest
 * a dependency that no longer exists.
 * @returns {void} Nothing; no session is held and the harness is armed.
 */
function resetSessionAndTransport(): void {
  endAnySession();
  removeApiHarness();
  installApiHarness();
}

/**
 * Registers the cases proving each authenticated route renders its screen inside the shell.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function shellCompositionCases(): void {
  beforeEach(resetSessionAndTransport);
  afterEach(resetSessionAndTransport);

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

/**
 * Sign-on is painted inside the one frame, and the frame offers no sign-off with no session held.
 *
 * Assumptions: the frame is asserted PRESENT and the sign-off control ABSENT, which are two separate
 * properties and not one. The shell renders its sign-off control only while a session is held, so
 * framing sign-on offers an anonymous operator nothing they cannot do; and sign-on delegates its
 * row-23 message line and its row-24 legend to that frame, so an unframed sign-on would compute all
 * three of the reference's refusal sentences and discard them.
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
   * WHY : Assumptions: the SCREEN is asserted absent and the frame is not, because the frame sits
   *       ABOVE the guard in the route tree and is therefore present on both sides of the boundary.
   *       What this case is about is that the guard sends the operator to sign-on instead of
   *       rendering the account screen, and the two markers below are the whole of that; asserting
   *       the frame away as well would assert the guard's position in the tree, which is
   *       `ui/src/router.tsx`'s decision and not this case's subject.
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
 * The transaction-type route parameter is spelled `cd`, so the type code arrives.
 *
 * ⚠️ Assumptions: this case exists because the parameter was RENAMED -- the pattern spelled it
 * `typeCd` and the frozen route table spells it `cd` -- and a rename of a route parameter is silent in
 * both directions. React Router resolves by name, so a pattern and a screen that disagree still mount
 * the screen and merely hand it `undefined`, which the maintenance screen treats as an arrival with no
 * type code selected. Observing the VALUE is the only assertion that can tell the two apart.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function carriesTheTypeCodeToReferenceMaintenance(): Promise<void> {
  await signOnAs(['carddemo-admin']);

  await renderRouterAt('/reference/transaction-types/07');

  expect(await screen.findByText('REF TYPE EDIT:07')).toBeInTheDocument();
}

/**
 * An address the table does not match renders the bounded not-found result, outside the frame.
 *
 * Assumptions: the catch-all is worth a gate rather than a note, because it is the ONE surface an
 * unauthenticated caller reaches without passing either guard -- so what it renders is a security
 * property as much as a navigation one.
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
 * The composition root provides the router this module exports, and provides it once.
 *
 * Purpose: every other case here builds its own router from the exported route objects, which proves
 * the TABLE and proves nothing about who mounts it. This one closes that: `ui/src/router.tsx` exports
 * an initialised data router and renders no provider itself, and `ui/src/App.tsx` is the module that
 * hands it to `RouterProvider` inside the one theme provider. The arrangement is not observable from
 * either file alone, and the failure it guards against is silent -- a provider mounted in both files
 * frames every screen twice, which is the defect `ui/src/layout/appShellIntegration.test.tsx` records.
 *
 * Assumptions: the exported instance is asserted to BE a router rather than a component -- it answers
 * `navigate` and carries the routes it was built from -- because that is the half of the contract a
 * rendered result cannot show. A component that owned `BrowserRouter` internally would produce the same
 * document; it would not answer `navigate`, and `ui/src/App.tsx` could not import it under this name.
 *
 * Assumptions: the address is chosen by navigating the exported instance rather than by pushing a
 * history entry, because this instance read its location once as it initialised at module evaluation and
 * does not observe a later `pushState`. Navigating it is also the only way to make the case independent
 * of the order the file's cases run in, since the address the instance captured is whatever address the
 * first case to import this module happened to be at.
 *
 * Assumptions: sign-on is the address used because it needs no session, so the case asserts the
 * composition and not the guards -- which have their own cases below.
 * @returns {Promise<void>} Resolves once the framed document has been counted.
 */
async function theCompositionRootProvidesTheExportedRouter(): Promise<void> {
  const { CARD_DEMO_ROUTES, cardDemoRouter } = await import('./router');
  const { App } = await import('./App');

  expect(typeof cardDemoRouter.navigate, 'the export must be a data router').toBe('function');
  expect(cardDemoRouter.routes, 'the router must carry the exported route objects').toHaveLength(
    CARD_DEMO_ROUTES.length,
  );

  await cardDemoRouter.navigate('/signon');

  render(<App />);

  expect(await screen.findByText('SIGN ON')).toBeInTheDocument();
  /*
   * Assumptions: the frame is counted with `queryAllBy*` and required to be a singleton rather than
   * fetched with `getBy*`, because the number found IS the diagnostic -- a `getBy*` query throws on
   * multiple matches without reporting how many there were.
   */
  expect(
    screen.queryAllByTestId(APP_SHELL_TEST_ID),
    'the composition root may mount exactly one shell',
  ).toHaveLength(1);
  expect(screen.queryAllByRole('banner'), 'exactly one banner may be painted').toHaveLength(1);
  expect(
    screen.queryAllByRole('contentinfo'),
    'exactly one contentinfo landmark may be painted',
  ).toHaveLength(1);
}

/**
 * Registers the cases covering the authentication boundary the shell sits inside.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function authenticationBoundaryCases(): void {
  beforeEach(resetSessionAndTransport);
  afterEach(resetSessionAndTransport);

  it(
    'is provided to the tree by the composition root',
    theCompositionRootProvidesTheExportedRouter,
  );
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
  beforeEach(resetSessionAndTransport);
  afterEach(resetSessionAndTransport);

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

  it(
    'carries the type code to the reference-maintenance screen under the name it reads',
    carriesTheTypeCodeToReferenceMaintenance,
  );
}

describe('route table shell composition', shellCompositionCases);
describe('route table authentication boundary', authenticationBoundaryCases);
describe('route table administrative guard', administrativeGuardCases);
