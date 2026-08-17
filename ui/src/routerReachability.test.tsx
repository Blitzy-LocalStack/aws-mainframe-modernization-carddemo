/**
 * @file Reachability tests for the production route table in `ui/src/router.tsx`.
 *
 * Purpose
 * -------
 * Assert that the application an operator actually gets is navigable: that a successful sign-on
 * reaches a real screen for both user types, that every authored screen module is mounted at the path
 * its own contract names, that the shared frame is mounted around them, and that the administrative
 * routes refuse an ordinary operator.
 *
 * Why these cases render the REAL router
 * -------------------------------------
 * Refactoring Rationale: every other screen test in this tree mounts one screen at one path inside a
 * `MemoryRouter`, which is the right seam for asserting what a screen renders and the wrong one for
 * asserting that the application mounts it. The defect these cases exist to prevent was exactly that
 * gap: `ui/src/screens/signon/signon.test.tsx` declared private `/menu` and `/admin` routes of its own,
 * so it passed while the production table declared neither and a correct credential reached the
 * not-found result. A case that supplies its own route table can only ever prove that a screen works
 * when it is mounted -- never that it is. So these cases render `CardDemoRouter` itself and navigate
 * `jsdom`'s own history, which is the one arrangement in which an unmounted route fails.
 *
 * Assumptions: the transport is answered by the shared axios harness rather than by module mocks,
 * because these cases traverse several screens and only one of them -- the transaction-type list --
 * issues a request on arrival. A harness that answers every dispatch with an empty page lets each
 * screen settle into its own opening state without this file having to know which module each one
 * calls.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { ConfigProvider } from 'antd';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from './api/auth';
import { APP_SHELL_TEST_ID } from './layout/AppShell';
import { PF_KEY_BAR_REGION_LABEL } from './layout/PfKeyBar';
import { ACCESS_DENIED_ADMIN_ONLY, SCREEN_NOT_AVAILABLE_TITLE } from './messages/messages';
import { ADMIN_MENU_SUBTITLE } from './screens/admin';
import { MAIN_MENU_SUBTITLE } from './screens/menu';
import { cardDemoTheme } from './theme/antdTheme';
import {
  answerEveryRequestWith,
  installApiHarness,
  pageOf,
  removeApiHarness,
} from './test/apiHarness';
import { SESSION_USER_ID, endAnySession, establishSession } from './test/sessionHarness';

/*
 * WHY : Assumptions: the stub is declared through `vi.hoisted` rather than as a plain `const`, and the
 *       difference is load-bearing here where it is not in `ui/src/screens/signon/signon.test.tsx`.
 *       Vitest lifts `vi.mock` above the imports, and this file imports the shared frame -- which
 *       imports `ui/src/hooks/useAuth.ts`, which imports the mocked module -- so the factory runs
 *       DURING the import phase, before a plain `const` at this position has initialised. That fails
 *       with `Cannot access 'signOnMock' before initialization`, a message that names the symbol and
 *       not the cause. `vi.hoisted` is lifted with the mock registration, so the binding exists by the
 *       time the factory reads it.
 */
const { signOnMock } = vi.hoisted(
  /**
   * Creates the sign-on stub before the module graph is imported.
   * @returns {{ signOnMock: ReturnType<typeof vi.fn> }} The stub, hoisted above every import.
   */
  () => ({ signOnMock: vi.fn() }),
);

/**
 * Replaces the sign-on operation while leaving every other export of the auth module intact.
 *
 * Assumptions: only the identity exchange is stubbed. These cases are about where a successful
 * sign-on LANDS, so the credential exchange is the one thing that must not reach a network, while the
 * token-decoding and storage behaviour beside it is production code these cases depend on.
 * @returns {Promise<typeof AuthModule>} The real module with the sign-on operation stubbed.
 */
async function mockAuthModule(): Promise<typeof AuthModule> {
  const actual = await vi.importActual<typeof AuthModule>('./api/auth');
  return {
    ...actual,
    /**
     * Stands in for the sign-on operation.
     * @returns {Promise<unknown>} Whatever the case configured.
     */
    signOn: signOnMock,
  };
}

vi.mock('./api/auth', mockAuthModule);

const { CardDemoRouter } = await import('./router');

/*
 * WHY : ⚠️ Refactoring Rationale: three `carddemo.*` session-storage key names stood here and
 *       `ui/src/hooks/useAuth.ts` reads none of them -- the session is held in a module variable and the
 *       bearer in one belonging to `ui/src/api/client.ts`, so that nothing script-readable retains a
 *       credential and closing the tab ends the session. Writing the keys established NOTHING, so every
 *       case that called the installer below rendered the guarded route ANONYMOUS and was answered by the
 *       sign-on redirect rather than by the screen it named. The session is now established through the
 *       exchange.
 */

/**
 * The heading the not-found result paints, which no mounted route may render.
 *
 * Refactoring Rationale: it is READ from the message catalogue where it was previously a literal copy
 * of the heading. A copy asserts that the surface paints this exact sentence, so correcting the
 * sentence in one place left the other asserting text nothing rendered -- and because most cases here
 * assert the heading is ABSENT, a stale copy would have gone on passing while measuring nothing.
 * Reading the constant keeps every case pointed at the catalogued heading.
 */
const NOT_FOUND_TITLE = SCREEN_NOT_AVAILABLE_TITLE;

/**
 * Builds an identity token whose claim carries the named groups.
 *
 * Assumptions: the token is three dot-separated segments with an unsigned payload, which is all the
 * client reads -- signature verification belongs to the services, which is why a screen may decode a
 * claim without being able to mint an authority. Every value here is fabricated.
 * Assumptions: the claim carries the OPERATOR as well as the groups, under `cognito:username`, because
 * the hook refuses an issued set whose token names an operator other than the one it was issued for --
 * a check that stops a pool answer for one identifier installing a session for another. A token carrying
 * groups alone is refused outright, which is what every token this file minted used to be.
 * @param {readonly string[]} groups - Group names to place in the `cognito:groups` claim.
 * @param {string} userId - Operator the token is issued for.
 * @returns {string} A three-segment token decoding to those groups and that operator.
 */
function idTokenFor(groups: readonly string[], userId: string): string {
  const payload = btoa(JSON.stringify({ 'cognito:groups': groups, 'cognito:username': userId }));
  return `header.${payload}.signature`;
}

/**
 * Builds the authenticated sign-on outcome for one operator and its groups.
 *
 * Assumptions: the identifier is a PARAMETER rather than a fixed `'USER0001'`, because the outcome's
 * `userId` and its token's operator claim must agree -- the hook refuses the pair otherwise -- and the
 * administrative case signs on as `ADMIN001`. A fixed identifier made that case's answer describe a
 * different operator from the one the form submitted.
 * @param {string} userId - Operator the outcome is issued for.
 * @param {readonly string[]} groups - Group names the identity token's claim carries.
 * @returns {object} An authenticated sign-on outcome in the shape the client publishes.
 */
function authenticated(userId: string, groups: readonly string[]): object {
  return {
    outcome: 'AUTHENTICATED',
    userId,
    accessToken: 'access-token-value',
    idToken: idTokenFor(groups, userId),
    tokenType: 'Bearer',
    expiresIn: 3600,
  };
}

/**
 * Installs a signed-on session without going through the sign-on screen.
 *
 * Assumptions: the session is established by driving the hook's own exchange against the stubbed
 * identity client, which is the only way to hold one -- the hook keeps the session in module state and
 * exposes no installer. It is still established WITHOUT the sign-on form, so a case about one route is
 * not also a case about that form: the exchange is driven from a hook the harness renders and discards.
 * @param {readonly string[]} groups - Groups the installed session's claim carries.
 * @returns {Promise<void>} Resolves once the session is held.
 */
async function installSession(groups: readonly string[]): Promise<void> {
  signOnMock.mockResolvedValueOnce(authenticated(SESSION_USER_ID, groups));
  const { unmount } = await establishSession({ groups, userId: SESSION_USER_ID });

  unmount();
}

/**
 * Renders the production router at one browser path.
 *
 * Assumptions: the path is pushed onto `jsdom`'s history before rendering, because `CardDemoRouter`
 * wraps `BrowserRouter` and therefore reads the location from the document rather than taking initial
 * entries. The theme provider is composed here for the same reason `ui/src/App.tsx` composes it: it is
 * the single injection point, and the router does not include it.
 * @param {string} path - The browser path to open.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderRouterAt(path: string): void {
  window.history.pushState({}, '', path);
  render(
    <ConfigProvider theme={cardDemoTheme}>
      <CardDemoRouter />
    </ConfigProvider>,
  );
}

/**
 * Signs on through the real form with a credential the stub accepts.
 * @param {string} userId - Operator identifier to type.
 * @returns {Promise<void>} Resolves once the submission has been dispatched.
 */
async function signOnAs(userId: string): Promise<void> {
  await userEvent.type(screen.getByLabelText(/user id/iu), userId);
  await userEvent.type(screen.getByLabelText(/^password\s*:?$/iu), 'not-a-real-password');
  await userEvent.click(screen.getByRole('button', { name: /ENTER=Sign-on/u }));
}

/**
 * Clears the session and the stub so no case inherits another's state.
 * @returns {void} Nothing; storage, the stub and the harness are reset in place.
 */
function resetEnvironment(): void {
  endAnySession();
  signOnMock.mockReset();
  installApiHarness();
  answerEveryRequestWith(pageOf([]));
}

/**
 * Removes the harness and clears the session after a case.
 * @returns {void} Nothing; the environment is restored in place.
 */
function restoreEnvironment(): void {
  removeApiHarness();
  endAnySession();
}

/**
 * A successful sign-on by an ordinary operator lands on the main menu.
 *
 * Assumptions: this is the case the missing routes made impossible. It follows the production
 * transition -- the screen navigates to `MAIN_MENU_ROUTE` and this table is what resolves it -- so an
 * unmounted menu fails here with the not-found heading rather than passing against a stand-in route.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function signOnEntersTheMainMenu(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated('USER0001', ['carddemo-user']));
  renderRouterAt('/signon');

  await signOnAs('USER0001');

  expect(
    await screen.findByRole('heading', { name: MAIN_MENU_SUBTITLE }, QUERY_TIMEOUT),
  ).toBeInTheDocument();
  expect(screen.queryByText(NOT_FOUND_TITLE)).not.toBeInTheDocument();
}

/**
 * A successful sign-on by an administrator lands on the administrative menu.
 *
 * Assumptions: the destination is chosen from the signed group claim, so this also proves the
 * administrative guard admits the operator the claim names rather than one a field asserted.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function signOnEntersTheAdministrativeMenu(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated('ADMIN001', ['carddemo-admin']));
  renderRouterAt('/signon');

  await signOnAs('ADMIN001');

  expect(
    await screen.findByRole('heading', { name: ADMIN_MENU_SUBTITLE }, QUERY_TIMEOUT),
  ).toBeInTheDocument();
  expect(screen.queryByText(NOT_FOUND_TITLE)).not.toBeInTheDocument();
}

/**
 * The bare root opens the main menu for a signed-on operator.
 *
 * Assumptions: the redirect is asserted through the guard rather than around it, so this also fixes
 * that the root is not reachable without a session -- the unauthenticated case below covers the other
 * side of the same redirect.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function theRootOpensTheMainMenu(): Promise<void> {
  await installSession(['carddemo-user']);
  renderRouterAt('/');

  expect(
    await screen.findByRole('heading', { name: MAIN_MENU_SUBTITLE }, QUERY_TIMEOUT),
  ).toBeInTheDocument();
}

/**
 * An unauthenticated visitor to a guarded route is sent to sign-on rather than shown a screen.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function anUnauthenticatedVisitorReachesSignOn(): Promise<void> {
  renderRouterAt('/menu');

  expect(await screen.findByRole('button', { name: /ENTER=Sign-on/u })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: MAIN_MENU_SUBTITLE })).not.toBeInTheDocument();
}

/**
 * An ordinary operator who opens an administrative route meets the baseline's refusal sentence.
 *
 * Assumptions: the refusal is asserted rather than a redirect, because the operator is signed on
 * correctly -- sending them to sign-on would invite them to fix something that is not broken, which is
 * the distinction `ui/src/routes/guards.tsx` draws between its two guards.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anOrdinaryOperatorIsRefusedAnAdministrativeRoute(): Promise<void> {
  await installSession(['carddemo-user']);
  renderRouterAt('/admin');

  // WHY : Assumptions: the catalog constant is TRIMMED for the lookup, because the testing library
  //   normalises the text it reads out of the DOM and compares it against the matcher string as
  //   given. The constant keeps its trailing space -- the COBOL literal ends with one and rule T8
  //   carries it across -- so an untrimmed matcher can never equal the normalised DOM text.
  //   `ui/src/screens/signon/signon.test.tsx` trims the farewell constant for the same reason.
  expect(await screen.findByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: ADMIN_MENU_SUBTITLE })).not.toBeInTheDocument();
}

/*
 * WHY : Assumptions: the sweep below waits on {@link QUERY_TIMEOUT}, the one query-level ceiling this
 *       file declares, rather than on a ceiling of its own. Every wait here covers the same work --
 *       transforming, importing, mounting and committing a whole screen module through `React.lazy`,
 *       several of them pulling in the design system for the first time in the worker -- so a second
 *       constant would only create somewhere for the two to drift apart.
 */

/**
 * Every path the table declares resolves to a screen inside the shared frame.
 *
 * Purpose: this is the assertion that catches an authored screen module reachable from nothing. Four of
 * these paths had no route at all, so each rendered the not-found result while its module compiled,
 * linted and type-checked -- and no case could see it, because a route table cannot assert what is
 * absent from it.
 *
 * Assumptions: an administrator's session is installed for all of them, because four of the paths are
 * administrative and this case is about REACHABILITY rather than about authority, which the two guard
 * cases above cover on their own.
 *
 * Assumptions: the assertion per path is that the frame is present and the not-found heading is not.
 * It deliberately does not assert what each screen renders -- each screen has its own cases for that,
 * and a case that also asserted content would fail for a screen reason and be read as a routing one.
 * @returns {Promise<void>} Resolves once every path has been visited.
 */
async function everyDeclaredPathResolvesToAScreen(): Promise<void> {
  const paths = [
    '/menu',
    '/admin',
    '/account/view',
    '/account/update',
    '/cards',
    '/transactions/new',
    '/authorizations',
    '/users/USER0001/edit',
    '/reference/transaction-types',
  ];

  for (const path of paths) {
    await installSession(['carddemo-admin', 'carddemo-user']);
    renderRouterAt(path);

    // Assumptions: the frame is awaited rather than asserted synchronously, because seven of the nine
    //   screens arrive through `React.lazy` and are therefore behind one microtask at least.
    /*
     * WHY : Refactoring Rationale: the wait carries an EXPLICIT budget, and the default one is what
     *       made this case fail intermittently while asserting nothing wrong. Testing Library's default
     *       is one second per wait, but this loop visits nine paths and each visit transforms and
     *       commits a separate `React.lazy` chunk -- the very cost this file's own `TIMEOUT` note
     *       measures at "just over five seconds" for the first case. A one-second inner budget inside a
     *       thirty-second case budget meant the case could fail on whichever chunk happened to be
     *       slowest while the runner was busy, and it did: on a four-core container it reported the
     *       `Suspense` fallback instead of the frame.
     * WHY : Assumptions: this widens a WAIT and weakens nothing. Both assertions are unchanged -- the
     *       frame must appear and the not-found result must not -- so a path that genuinely resolves to
     *       nothing still fails, and still fails naming the path through the message below. The budget
     *       reuses this file's own `TIMEOUT` rather than introducing a second number, so the inner wait
     *       and the case that contains it cannot drift apart.
     */
    await waitFor(
      /**
       * Waits until the frame has painted for this path.
       * @returns {void} Nothing; the assertion carries the outcome.
       */
      () => {
        expect(screen.getAllByTestId(APP_SHELL_TEST_ID).length).toBeGreaterThan(0);
      },
      QUERY_TIMEOUT,
    );
    expect(
      screen.queryByText(NOT_FOUND_TITLE),
      `path ${path} must resolve`,
    ).not.toBeInTheDocument();
    cleanup();
  }
}

/**
 * A path outside the table still renders the bounded not-found result.
 *
 * Assumptions: this is the control for the case above. Without it, a table that mounted a catch-all
 * screen at every path would satisfy the reachability sweep while telling an operator nothing.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function anUndeclaredPathRendersTheNotFoundResult(): Promise<void> {
  await installSession(['carddemo-user']);
  renderRouterAt('/no/such/screen');

  expect(await screen.findByText(NOT_FOUND_TITLE)).toBeInTheDocument();
}

/**
 * The frame paints exactly one function-key legend around a screen that owns its own.
 *
 * Purpose: this is the collision the shell avoids by installing NO keyboard listener of its own,
 * asserted from the outside. Every delivered screen resolves its own keys and paints its own legend, so a
 * shell that also bound its sign-off key would paint a second legend region -- and, worse, would sit
 * alongside the screen's own document listener, so PF12 would both cancel an edit and end the session.
 * Asserting the outcome here rather than a configuration flag is what makes the property hold for every
 * mount site rather than for a correctly-configured one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theFramePaintsOneKeyLegendOnly(): Promise<void> {
  await installSession(['carddemo-user']);
  renderRouterAt('/menu');

  await screen.findByRole('heading', { name: MAIN_MENU_SUBTITLE }, QUERY_TIMEOUT);

  expect(screen.getAllByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toHaveLength(1);
  expect(screen.queryByRole('button', { name: /F12=Sign off/u })).not.toBeInTheDocument();
}

/*
 * WHY : Assumptions: every case here carries an explicit timeout well above the runner's five-second
 *       default, and the reason is the subject rather than slowness on this machine. These cases render
 *       the PRODUCTION table, so each one pulls the route's screen chunk through `React.lazy` -- nine
 *       separate chunks across the file, transformed on first use -- and the same run also imports the
 *       shared frame, the message catalog and the theme. Measured on the development container the
 *       first case alone takes just over five seconds, so the default would fail a passing assertion
 *       for a reason that has nothing to do with routing. Alternatives Considered: raising
 *       `testTimeout` for the whole project in `ui/vitest.config.ts`. Rejected because that would
 *       loosen the bound for every unit test in the tree, where five seconds is a useful signal that
 *       something is genuinely stuck.
 */

/**
 * Milliseconds the multi-path reachability case is allowed, in place of the single-case {@link TIMEOUT}.
 *
 * ⚠️ Assumptions: this case is not one navigation but NINE, walked in sequence, and each visits a
 * distinct route whose screen arrives through `React.lazy` -- so it pays the chunk-transform cost nine
 * times over where every other case in this file pays it once. Budgeting it like a single case is what
 * left it failing at just over thirty seconds while each of its nine assertions individually passed.
 * Trade-offs: a genuinely stuck run takes two minutes to report here rather than thirty seconds. That is
 * accepted for the one case in the file whose cost is a multiple of the others; the alternative is a
 * bound that the case exceeds when it is working correctly, which is a failure that carries no
 * information about routing at all.
 */
const MULTI_PATH_TIMEOUT = 120_000;

const TIMEOUT = 30_000;

/*
 * WHY : ⚠️ Refactoring Rationale: a QUERY-level ceiling is declared beside the case-level one above,
 *       because the two bound different things and only the first was set. `TIMEOUT` is passed to `it`,
 *       so it bounds the whole case; every `findBy*` and `waitFor` inside a case still took Testing
 *       Library's own one-second default. That is what failed here: the case had thirty seconds to run
 *       while its wait for a lazily loaded screen gave up after one, so the case reported a missing
 *       heading rather than the slow chunk it was actually waiting on -- and it reported it for the
 *       heaviest screens only, which is the signature of a ceiling rather than of a routing defect.
 * WHY : Assumptions: raising it cannot weaken an assertion, since a wait ends early on success and only
 *       a genuinely unreachable route consumes the whole ceiling before failing.
 * WHY : Alternatives Considered: five seconds, matching the `ASYNC_CONDITION_TIMEOUT_MS` used for the
 *       same job in `ui/src/screens/cardReadSequencing.test.tsx`,
 *       `ui/src/screens/screenSelectionCarriers.test.tsx` and
 *       `ui/src/screens/cardList/browseNarrowing.test.tsx`, so that this file reuses a number the tree
 *       already carries rather than introducing a new one. Rejected on measurement: the first case in
 *       this file alone takes just over five seconds, so that ceiling sits ON the boundary it is meant
 *       to clear and would fail a passing assertion under load. Fifteen seconds is the same fix with
 *       headroom -- it is the largest figure that still leaves a clear margin under the 30 s per-case
 *       budget {@link TIMEOUT} gives every case here, so a screen that genuinely never mounts still fails on its own assertion rather than
 *       stalling the case.
 * WHY : Alternatives Considered: thirty seconds, on the measurement that a four-core runner routinely
 *       exceeds one second for a screen whose imports pull in the design system for the first time in
 *       the worker. Rejected for THIS file specifically: {@link TIMEOUT} gives each case exactly thirty
 *       seconds, so a query ceiling of the same size could never report its own failure -- an
 *       unreachable route would consume the case budget and be reported as a case timeout instead of as
 *       the missing heading this file exists to name. Fifteen seconds keeps the query ceiling strictly
 *       inside the case budget with room for a case that runs two queries in sequence, which is what
 *       preserves the diagnostic.
 * WHY : Trade-offs: this single ceiling bounds EVERY lazy-screen wait in the file -- the sign-on
 *       landings, the bare-root redirect, the per-path frame check and the key-legend case -- rather
 *       than each site carrying its own number. One constant is what makes the ceiling auditable; two
 *       would leave a reader unable to tell which waits were deliberately given less room and which
 *       were simply missed.
 */
const QUERY_TIMEOUT = { timeout: 15_000 };

/**
 * Registers the router reachability cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function routerReachabilityCases(): void {
  it('lands an ordinary operator on the main menu after sign-on', signOnEntersTheMainMenu, TIMEOUT);
  it(
    'lands an administrator on the administrative menu after sign-on',
    signOnEntersTheAdministrativeMenu,
    TIMEOUT,
  );
  it('opens the main menu from the bare root', theRootOpensTheMainMenu, TIMEOUT);
  it('sends an unauthenticated visitor to sign-on', anUnauthenticatedVisitorReachesSignOn, TIMEOUT);
  it(
    'refuses an ordinary operator an administrative route with the baseline sentence',
    anOrdinaryOperatorIsRefusedAnAdministrativeRoute,
    TIMEOUT,
  );
  it(
    'resolves every declared path to a screen inside the frame',
    everyDeclaredPathResolvesToAScreen,
    MULTI_PATH_TIMEOUT,
  );
  it(
    'renders the not-found result for an undeclared path',
    anUndeclaredPathRendersTheNotFoundResult,
    TIMEOUT,
  );
  it(
    'paints exactly one function-key legend around a screen',
    theFramePaintsOneKeyLegendOnly,
    TIMEOUT,
  );
}

beforeEach(resetEnvironment);

afterEach(restoreEnvironment);

describe('production route table', routerReachabilityCases);
