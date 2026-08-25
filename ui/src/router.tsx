/**
 * @file The route table: the client-side replacement for the CICS program-transfer graph, the one
 * place the authentication boundary between screens is drawn, and the one place the application
 * frame is mounted.
 *
 * Purpose
 * -------
 * Declare every browser route backed by an authored screen module, mount the shared frame those
 * screens are painted inside, and classify each route as public, authenticated or administrative.
 * This is where `EXEC CICS XCTL` ends up. The reference programs transfer control by naming a
 * program in the communication area; the migrated equivalent is a route change, so the reachability
 * graph of the online transactions is expressed here and in no screen.
 *
 * Parameters
 * ----------
 * Not applicable. This module declares components, path constants and route data, and takes no
 * inputs of its own.
 *
 * Return values ------------- {@link CARD_DEMO_ROUTES} is the mounted route tree expressed as route
 * objects, {@link cardDemoRouter} is the browser data router built from it, and {@link ROUTE_TABLE}
 * is the same graph as inert data. Each is documented at its declaration.
 *
 * Exceptions or errors
 * --------------------
 * Nothing declared here raises, and an address matching no route resolves to
 * {@link NotFoundScreen} rather than throwing. A lazily imported chunk that fails to LOAD is stated
 * exactly, because the obvious assumption is wrong twice over: the `Suspense` boundary below
 * supplies a PENDING fallback only and never sees a rejected import, and no error boundary of this
 * application's own is mounted anywhere in the tree -- `ui/src/App.tsx` records why it mounts none,
 * and no route here declares an `errorElement`. What renders instead is react-router's OWN default
 * boundary, wrapped around the root match whether or not a route asks for one: an untranslated
 * `Unexpected Application Error!` heading carrying the error message and its stack, outside the
 * frame and outside the message catalogue.
 *
 * Refactoring Rationale: there is NO server-side "next program" concept anywhere in this file, and
 * its absence is the point rather than an omission. The reference carries navigation in the
 * communication area -- `CDEMO-TO-PROGRAM`, `CDEMO-FROM-PROGRAM`, `CDEMO-LAST-MAP` and
 * `CDEMO-LAST-MAPSET` in `app/cpy/COCOM01Y.cpy` L21-L44 -- because a pseudo-conversational task
 * ends at every screen turn and has nowhere else to keep it. Those four fields collapse into the
 * browser's own history and nothing replaces them: no route names its successor, no handler returns
 * a destination, and the re-entry discriminator `CDEMO-PGM-CONTEXT` disappears entirely because a
 * stateless handler has no first-entry-versus-re-entry distinction to make.
 *
 * Ownership of navigation
 * -----------------------
 * Assumptions: this module owns the route-to-screen MAPPING and nothing else. It performs no
 * navigation itself -- a screen navigates through `ui/src/routes/navigation.ts`, which validates the
 * destination -- and it holds no card path of its own: the two card routes are imported from
 * `ui/src/routes/cards.ts`, which owns them beside the selector guards that validate them. Splitting
 * it that way is what lets a screen import a path builder without importing the router.
 *
 * Assumptions: a card is addressed by an opaque selector rather than by its number, which is the
 * divergence registered as `D-CARD-SELECTOR` in
 * `docs/architecture/cobol-to-service-traceability.md`; that register holds its reasoning, and this
 * table only consumes the two route constants it produces.
 *
 * Assumptions: the sign-on branch is NOT here. `app/cbl/COSGN00C.cbl` L230-L240 transfers an `'A'`
 * operator to `COADM01C` and everyone else to `COMEN01C`, and its migrated form -- a navigation
 * chosen from the signed group claim -- belongs to `ui/src/screens/signon/index.tsx`. This file
 * makes `/menu` reachable behind the authentication guard and `/admin` reachable behind the
 * administrative one, so the same branch holds however an operator arrives; a reader looking for the
 * branch AT SIGN-ON should look there.
 *
 * ⚠️ Refactoring Rationale: the BARE ORIGIN takes the same branch, where it used to redirect every
 * operator to `/menu` regardless of claim. {@link RootRedirect} replaces that fixed redirect. The
 * note this replaces argued the fixed destination was correct because the origin is reachable with no
 * session, so choosing between the two menus would mean reading a claim that might not exist. That
 * argument does not survive: reading the claim at a public route is harmless -- `useAuth` answers
 * `isAdmin: false` when no session is held, which selects `/menu`, and the authentication guard then
 * bounces an anonymous caller to sign-on exactly as before -- while the fixed destination contradicts
 * L230-L240 for the one case it was reached in, sending an administrator holding a session to the
 * ORDINARY menu. The reference never puts `COMEN01C` in front of an `'A'` operator.
 *
 * Authentication boundary ----------------------- Assumptions: exactly one screen route is outside
 * the guard, and it is sign-on, because sign-on is the route that establishes the credential the
 * others require. It is a SIBLING of the guarded branch rather than a descendant of it, so no
 * ancestor of sign-on can ever require a credential. Every other screen route is a child of the
 * guarded layout route below, so the guard is applied once, structurally, rather than being repeated
 * per route -- and a route added later inherits it by where it is written. The guard decides what is
 * RENDERED only; every service independently validates the token, so an unguarded route would expose
 * a screen and no data.
 *
 * Assumptions: outside the GUARD is not outside the FRAME. Sign-on is a direct child of the layout
 * route and a sibling of the guard route, so it is framed and unguarded -- the reconciliation is
 * argued in full at that declaration, and the short form is that the sign-on screen delegates its
 * title band, its row-23 message and its row-24 legend to the frame, so an unframed sign-on would
 * discard every refusal sentence `app/cbl/COSGN00C.cbl` L242-L256 emits.
 *
 * Assumptions: `ui/src/layout/AppShell.tsx` is mounted EXACTLY ONCE, as the element of the layout
 * route below. Two authored screens -- `./screens/accountView` and `./screens/authSummary` -- omit
 * the shared title band on the stated ground that the shell paints it, so their row-1 and row-2
 * fields exist only while that layout route does, and a second mount anywhere would frame every
 * guarded screen twice.
 *
 * Assumptions: the administrative subtree holds the six entries of the administrative option table
 * `app/cpy/COADM02Y.cpy` -- `/users`, `/users/new`, `/users/:id/edit`, `/users/:id/delete`,
 * `/reference/transaction-types` and `/reference/transaction-types/:cd` -- AND the menu that lists
 * them, `/admin`, for seven gated paths in total.
 *
 * ⚠️⚠️ Refactoring Rationale: `/admin` was classified `authenticated` and is now `administrative`,
 * and this is the migration's largest correction to this file. The argument that stood here was that
 * the option record L56-L59 carries no `USRTYPE` field, so its six options ARE the boundary and a
 * seventh gated path would be a target-side invention; the menu was said to be "reachable but acts on
 * nothing" because every option leads to one of the six. Measurement refuted the second half and the
 * first half was answering the wrong question. A `carddemo-user` rendered the COMPLETE administrative
 * menu -- the `CA00`/`COADM01C` title band, all six option labels from `app/cpy/COADM02Y.cpy`, and a
 * focused option field that accepted and dispatched an entry -- with an EMPTY message band and no
 * refusal anywhere on the screen. So it did act: it disclosed the whole administrative capability
 * inventory and then took the operator to `/users`, where the refusal finally fired, which is a
 * refusal delivered one screen too late and at the wrong screen. What the missing `USRTYPE` field
 * means is that the six options need no per-option test, not that the menu listing them is ordinary:
 * `app/cbl/COSGN00C.cbl` L230-L240 is the reference's own statement of who may see it, and it puts
 * `COADM01C` in front of an `'A'` operator and `COMEN01C` in front of everyone else, unconditionally.
 * A `'U'` operator has no path to that menu in the baseline at all, so gating it restores the
 * reference boundary rather than inventing a target-side one -- and `app/cbl/COMEN01C.cbl` L140,
 * which refuses an administrative option ON THE MENU the operator is already looking at, is the
 * reference's evidence that a refusal belongs at the point of entry.
 *
 * Assumptions: nothing else moves. The six option paths were already gated and stay gated; the
 * guard is unchanged; and because the administrative branch is one subtree, the menu is now gated by
 * WHERE IT IS WRITTEN rather than by a second mechanism. {@link ROUTE_TABLE} publishes the resulting
 * class for every path and `ui/src/routerRoutes.test.tsx` pins all 21 by name, so an access level
 * omitted from a route added later fails a case instead of shipping.
 *
 * The frame is mounted here ------------------------- Refactoring Rationale:
 * `ui/src/layout/AppShell.tsx` is mounted as TWO SIBLING layout routes -- one holding the public
 * sign-on route, one holding the whole guarded subtree -- and previously it was mounted nowhere. Two
 * authored screens -- `./screens/accountView` and `./screens/authSummary` -- omit the shared title
 * band on the stated ground that the shell paints it, so with no shell mounted their row-1 and row-2
 * fields rendered nowhere at all: the transaction identifier, the two application titles, the
 * program name and the paint date and time were simply absent from those two screens. The shell
 * renders each of its zones only when a screen has delegated one, so mounting it changes nothing for
 * the screens that compose their own bands.
 *
 * Assumptions: two mounts cannot reproduce the double-frame defect that once framed every guarded
 * screen twice, because these two are SIBLINGS: a location matches the public branch or the guarded
 * branch and never both, so exactly one shell renders per location. That defect came from NESTING --
 * an outer shell handed the whole router as its `children`, wrapped around an inner layout-route
 * shell -- and a shell given `children` never reaches its outlet, so both painted. Nesting is what
 * this shape excludes, and it is what `ui/src/layout/appShellIntegration.test.tsx` holds it to.
 *
 * The data router
 * --------------- Assumptions: the tree is declared as route OBJECTS in {@link CARD_DEMO_ROUTES} and
 * handed to `createBrowserRouter`, whose result {@link cardDemoRouter} is rendered by exactly one
 * `RouterProvider` in `ui/src/App.tsx`. That is the composition the frozen route specification
 * requires, and what it changes is the API rather than the contract: the reachability graph, the
 * guards, the lazy split and the frame are identical to the ones the declarative
 * `BrowserRouter`/`Routes` element form expressed before it.
 *
 * Alternatives Considered: keeping that element form, on the ground that two sibling regression
 * guards read the SOURCE TEXT of this file -- `ui/src/layout/appShellIntegration.test.tsx` counting
 * the self-closing shell element, and `ui/src/routes/routeCensus.test.ts` measuring the region
 * between the tags that opened and closed the route list. The argument is withdrawn, because it
 * inverts the dependency: a source-text test states what this file must LOOK like, so preserving it
 * at the cost of the specified composition would let the test decide the architecture. Both are
 * re-anchored instead -- the census on the route array's own delimiters, the shell count on the two
 * sibling layout mounts -- and the specification is implemented as frozen.
 *
 * Trade-offs: route objects lose the nesting that JSX indentation made visually obvious, so the
 * guard a path sits behind is now read from an `element` key rather than from the shape of the
 * markup. {@link ROUTE_TABLE} is what pays that back: it publishes the same graph as inert data,
 * assertable without mounting React, and it is the surface a reader should compare against the
 * reference option copybooks.
 */

/*
 * WHY : Alternatives Considered: the DOM companion package -- the one whose name is this package's
 *       with `-dom` appended, which react-router 6 split its browser entry points into and which a
 *       reader familiar with that major version will reach for first. It is deliberately ABSENT
 *       from `ui/package.json` and no 8.x of it was ever published: its newest release is a
 *       compatibility shim that depends on `react-router@7.18.1`, so requiring it would silently
 *       hold routing a major version behind the pinned `react-router@8.3.0` while looking like the
 *       more specific choice. Every browser entry point that once justified the split -- the
 *       history-backed router factory, the link components, the DOM hooks -- is exported from
 *       `react-router` itself in 8.x, so nothing is left for the shim to add. Repointing this
 *       import at that package is the single most likely well-meant edit to this file, and it would
 *       regress the routing version; the name is described rather than written here so that a
 *       search proving this module does not depend on it stays clean.
 */
import { Button, Flex, Spin, Typography } from 'antd';
import { lazy, Suspense } from 'react';
import type { ReactElement } from 'react';
import { createBrowserRouter, Navigate, Outlet, useNavigate } from 'react-router';
import type { RouteObject } from 'react-router';

/*
 * WHY : Assumptions: the session hook is imported HERE as well as by `./routes/guards`, and that is
 *       one reader of one module rather than a second copy of the decision. Both read the same signed
 *       claim through the same hook, which holds its snapshot in module scope behind
 *       `useSyncExternalStore` with no provider -- so two callers observe one session and cannot
 *       disagree about it. What this file does with it is choose a DESTINATION at the bare origin and
 *       at the two out-of-frame surfaces; what the guards do with it is refuse. Neither can grant
 *       anything: the hook publishes no setter for the groups or for the derived flag.
 */
import { useAuth } from './hooks/useAuth';
import { AppShell, useShellSlot } from './layout/AppShell';
import { ScreenTitle } from './layout/ScreenTitle';
import { BoundedShellOutlet } from './layout/ShellContentBoundary';
import { usePfKeys } from './layout/usePfKeys';
import {
  ADMIN_MENU_OPTIONS,
  MAIN_MENU_HEADINGS,
  MAIN_MENU_OPTIONS,
  SCREEN_NOT_AVAILABLE_DETAIL,
  SCREEN_NOT_AVAILABLE_TITLE,
  SIGN_ON_SUBMIT_LABEL,
  USER_LIST_KEY_LABELS,
} from './messages/messages';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { RequireAdmin, RequireSignOn, SIGN_ON_ROUTE } from './routes/guards';
import {
  ADMIN_MENU_ROUTE,
  CARD_DETAIL_ENTRY_ROUTE,
  CARD_UPDATE_ENTRY_ROUTE,
  MAIN_MENU_ROUTE,
  TRANSACTION_DETAIL_ENTRY_ROUTE,
  navigateSafely,
  navigationHandler,
  roleLandingRoute,
} from './routes/navigation';
import { routeForProgram } from './routes/programRoutes';
import { SignOnScreen } from './screens/signon';

/*
 * WHY : Trade-offs: every guarded screen is loaded LAZILY and sign-on is not. Eager imports would
 *       put all twenty guarded screens and their antd dependencies into the single chunk fetched
 *       before the sign-on form appears -- and those screens pull the heaviest components in the
 *       tree (`Table`, `Descriptions`, `Form`, `Select` and `Popconfirm`, with account update alone
 *       painting the 128 fields of mapset `CACTUPA`). The cost is paid at a different moment: the
 *       FIRST navigation to a guarded route fetches its chunk, so that transition shows the
 *       `Suspense` spinner, and a chunk that cannot be fetched fails at navigation rather than at
 *       start-up. Sign-on and the shell stay eager because deferring either would put the spinner
 *       where an operator has nothing else to look at.
 * WHY : Assumptions: these declarations and the route table further down are both ordered by
 *       MAIN-MENU OPTION NUMBER rather than alphabetically, because `app/cpy/COMEN02Y.cpy` is the
 *       reachability source and it numbers `Account Update` as option 2 (`COACTUPC`, L31-L35) ahead
 *       of the three card options 3 to 5. A reader then walks this table and the copybook in the
 *       same direction, and a missing screen shows up as a gap in the sequence.
 * WHY : Trade-offs: two forms appear below -- bare `lazy(() => import(...))` and an adapter that
 *       republishes a named export -- because `React.lazy` accepts only a module whose `default` IS
 *       the component. The adapter is REQUIRED by the six screens publishing no default (account
 *       view, account update, authorization summary, card detail, card update, user update); three
 *       publish only a default and can only be loaded bare (transaction list, user list, reference
 *       type list). Normalising was rejected in both directions: adding a default to those six
 *       edits six modules to change nothing observable, and the default-only three cannot take an
 *       adapter. Where either resolves the ADAPTER is used, because the named binding is what each
 *       screen's contract publishes and what a substituted module publishes in its place -- reading
 *       `default` from such a double would resolve to `undefined` and render nothing.
 */

const MainMenuScreen = lazy(
  /**
   * Imports the main menu chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/menu'),
);

const AccountViewScreen = lazy(
  /**
   * Imports the account view chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/accountView');
    return { default: module.AccountViewScreen };
  },
);

const AccountUpdateScreen = lazy(
  /**
   * Imports the account maintenance chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/accountUpdate');
    return { default: module.AccountUpdateScreen };
  },
);

const CardListScreen = lazy(
  /**
   * Imports the card browse chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/cardList');
    return { default: module.CardListScreen };
  },
);

const CardDetailScreen = lazy(
  /**
   * Imports the card detail chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/cardDetail');
    return { default: module.CardDetailScreen };
  },
);

const CardUpdateScreen = lazy(
  /**
   * Imports the card maintenance chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/cardUpdate');
    return { default: module.CardUpdateScreen };
  },
);

const TransactionListScreen = lazy(
  /**
   * Imports the transaction browse chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/transactionList'),
);

const TransactionAddScreen = lazy(
  /**
   * Imports the transaction capture chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/transactionAdd');
    return { default: module.TransactionAddScreen };
  },
);

const TransactionDetailScreen = lazy(
  /**
   * Imports the transaction detail chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/transactionDetail'),
);

const BillPayScreen = lazy(
  /**
   * Imports the bill payment chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/billPay'),
);

const ReportsScreen = lazy(
  /**
   * Imports the report submission chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/reports'),
);

const AuthSummaryScreen = lazy(
  /**
   * Imports the pending-authorization summary chunk and republishes its named export as
   * `default`.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/authSummary');
    return { default: module.AuthSummaryScreen };
  },
);

const AuthDetailScreen = lazy(
  /**
   * Imports the pending-authorization detail chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/authDetail'),
);

const AdminMenuScreen = lazy(
  /**
   * Imports the administrative menu chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/admin'),
);

const UserListScreen = lazy(
  /**
   * Imports the user browse chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/userList'),
);

const UserAddScreen = lazy(
  /**
   * Imports the add-user chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/userAdd'),
);

const UserUpdateScreen = lazy(
  /**
   * Imports the user maintenance chunk and republishes its named export under the `default` key.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  async () => {
    const module = await import('./screens/userUpdate');
    return { default: module.UserUpdateScreen };
  },
);

const UserDeleteScreen = lazy(
  /**
   * Imports the user deletion chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/userDelete'),
);

const RefTypeListScreen = lazy(
  /**
   * Imports the transaction-type list chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/refTypeList'),
);

const RefTypeEditScreen = lazy(
  /**
   * Imports the transaction-type maintenance chunk, whose default export is its screen.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module React.lazy accepts.
   */
  () => import('./screens/refTypeEdit'),
);

/*
 * WHY : Assumptions: every path carrying no opaque selector is written as a LITERAL here rather than
 *       imported from the screen that serves it, and the lazy split above is the reason. A screen
 *       reached through `lazy` is in its own chunk, so importing a constant from it at module scope
 *       would pull that module and its antd dependencies back into the entry chunk, silently undoing
 *       the split. The card, menu and sign-on routes ARE imported, because `./routes/cards`,
 *       `./routes/navigation` and `./routes/guards` are small route modules rather than screens.
 *       Where a screen publishes its own constant too, `./routerRoutes.test.tsx` imports both and
 *       fails if they disagree, so the duplication cannot drift unremarked.
 */

const ROOT_PATH = '/';

const NOT_FOUND_PATH = '*';

export const ACCOUNT_VIEW_PATH = '/account/view';

export const ACCOUNT_UPDATE_PATH = '/account/update';

export const CARD_LIST_PATH = '/cards';

export const TRANSACTION_LIST_PATH = '/transactions';

/**
 * Route the transaction capture screen occupies, which main-menu option 8 transfers to.
 *
 * Assumptions: this route is deliberately NOT administrative, and the copybook is the reason. A
 * commented-out alternative at `app/cpy/COMEN02Y.cpy` L69 labels option 8 `'Transaction Add (Admin
 * Only) '`, but the LIVE value is L70's `'Transaction Add '` and L72 gives the option the user type
 * `'U'` -- so `app/cbl/COMEN01C.cbl` L136-L143, which refuses an option whose user type is `'A'` to
 * a non-administrator, never fires for this one. Reading the dead line as live and moving this
 * route into the administrative subtree below would lock out exactly the operators the option
 * exists for, and it is the likeliest well-meant "correction" to this table.
 */
export const TRANSACTION_ADD_PATH = '/transactions/new';

/**
 * Route the transaction detail screen occupies, addressed by a transaction identifier.
 *
 * Assumptions: the segment is spelled `id`, the name
 * `ui/src/screens/transactionDetail/index.tsx` reads with `useParams`; a near-miss leaves the screen
 * on its own parameterless arrival, for the reason recorded at the reference route below.
 *
 * Assumptions: the identifier travels in the path rather than in history state, and that is safe
 * here where it is not for a card. A transaction identifier is `TRAN-ID PIC X(16)`
 * (`app/cpy/CVTRA05Y.cpy` L5), an internally-assigned ledger key that identifies no cardholder and
 * is not a credential -- unlike a card number, which `ui/src/routes/cards.ts` keeps out of a path
 * precisely because a target is written into the edge access log and the browser's history. The
 * screen additionally shows the card number only in the reduced rendering the service publishes.
 */
export const TRANSACTION_DETAIL_PATH = '/transactions/:id';

export const BILL_PAY_PATH = '/billpay';

/**
 * Route the transaction-report submission screen is entered at.
 *
 * Assumptions: main-menu option 9, whose `CDEMO-MENU-OPT-PGMNAME` is `CORPT00C` and whose
 * `CDEMO-MENU-OPT-USRTYPE` is `'U'` in `app/cpy/COMEN02Y.cpy`. The user type is why the route sits
 * OUTSIDE the administrative subtree below: every one of the eleven main-menu options is `'U'`, so
 * nothing reached from that menu is gated on the administrative claim.
 */
export const REPORTS_PATH = '/reports';

/**
 * Route the pending-authorization summary occupies, which main-menu option 11 transfers to.
 *
 * Assumptions: this screen belongs to an OPTIONAL extension, and the route is declared
 * unconditionally anyway. `app/cbl/COMEN01C.cbl` L147-L168 special-cases this one option: it issues
 * `EXEC CICS INQUIRE PROGRAM ... NOHANDLE` first and, when the extension is not installed, paints
 * `'This option '` + the option name + `' is not installed...'` in `DFHRED` instead of
 * transferring. No client-side equivalent of that probe is invented here -- a feature-detection
 * protocol between the browser and the router would be a target-side invention with no baseline
 * counterpart -- so in the migrated system the authorization service's availability surfaces where
 * every other service outage surfaces: as an ordinary API error the screen reports. The route
 * existing is therefore not a claim that the extension is deployed.
 */
export const AUTH_SUMMARY_PATH = '/authorizations';

export const AUTH_DETAIL_PATH = '/authorizations/:key';

/**
 * Route the add-user screen occupies, which administrative option 2 transfers to.
 *
 * Assumptions: option 2 is the entry, from `app/cpy/COADM02Y.cpy` L31-L34, which pairs the number
 * `2` and the label `'User Add (Security)'` with the program name `COUSR01C`.
 *
 * Assumptions: the path is selector-free because the screen CREATES a row rather than addressing
 * one. `app/cbl/COUSR01C.cbl` opens on an empty map with the cursor homed to the first name and
 * reads no selected user from the session structure at all, so there is no identifier for a path
 * parameter to carry -- which is what distinguishes this route from the maintenance route below.
 */
export const USER_ADD_PATH = '/users/new';

/**
 * Route the user browse occupies, which administrative option 1 transfers to.
 *
 * Assumptions: it is declared ABOVE the maintenance paths below and mounted before them, and the
 * ordering is safe either way -- react-router ranks a static segment ahead of a dynamic one and
 * matches on segment count, so `/users`, `/users/new` and `/users/:id/edit` cannot shadow one
 * another. It is grouped with them here because all three belong to the same `COUSR0*` family.
 */
export const USER_LIST_PATH = '/users';

/**
 * Route the user maintenance screen occupies, addressed by the operator identifier it maintains.
 *
 * Assumptions: the segment is spelled `id`, which is the name `ui/src/screens/userUpdate/index.tsx`
 * reads with `useParams`, and the same name the deletion route below carries. React Router resolves
 * a parameter by NAME, so a near-miss yields `undefined` with no diagnostic and leaves the screen
 * waiting for a typed identifier on an arrival that named one.
 *
 * ⚠️ Refactoring Rationale: this is the ONLY user-maintenance route, where a second selector-free
 * `/users/edit` was declared beside it and mounted on the same screen. That second path made the
 * table publish twenty-two paths for twenty-one programs, which is one more than the frozen route
 * specification carries, and it was an invented destination rather than a migrated one: no reference
 * transfer names a screen without also naming what it operates on. It is withdrawn, so `COUSR02C`
 * has exactly one route. The screen keeps its selector-free first turn -- `app/cbl/COUSR02C.cbl`
 * L99-L104 reads the selection carrier only when it is present, and the screen's mount effect
 * reproduces that by reading nothing when the route names no row -- but reaching that turn is now
 * the caller's business rather than a second entry in this table.
 */
export const USER_UPDATE_PATH = '/users/:id/edit';

/**
 * Route the user deletion screen occupies, replacing program `COUSR03C` (transaction `CU03`).
 *
 * Assumptions: this path carries the identifier as `:id`, the SAME parameter name the update route
 * above uses, because `ui/src/screens/userDelete/index.tsx` reads `useParams().id` and the two
 * screens are reached from the same selector. A different spelling would resolve to `undefined`
 * silently and leave the screen waiting for a typed identifier on an arrival that named one.
 *
 * Assumptions: only the PARAMETERISED form is declared. `COUSR03C` reaches its own empty first turn
 * through the same map, but a selector-free deletion path would offer a bare route whose only
 * purpose is to destroy a record the operator has not yet named -- and the screen already refuses
 * that with `User ID can NOT be empty...`. An administrator who needs to delete without a prior
 * selection reaches this screen from the identifier they type into it, so a second route would add
 * an entry point the reference's own navigation does not.
 */
export const USER_DELETE_PATH = '/users/:id/delete';

export const REF_TYPE_LIST_PATH = '/reference/transaction-types';

/**
 * Route the transaction-type maintenance screen occupies.
 *
 * ⚠️ Assumptions: the segment is spelled `cd`, which is the name the frozen route specification
 * gives it and the name `ui/src/screens/refTypeEdit/index.tsx` reads with `useParams`. It was
 * previously spelled `typeCd`, and the rename is not cosmetic: react-router resolves a parameter by
 * NAME, so the pattern and the screen's own read have to agree exactly or the screen receives
 * `undefined` with no diagnostic and falls back to its parameterless arrival. The two sides are
 * therefore changed together, and `ui/src/router.test.tsx` observes a value arriving under this name
 * rather than trusting either spelling.
 *
 * Assumptions: the short spelling is not a truncation. The reference calls this key
 * `TRAN-TYPE-CD-X` (`app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L226) and reads it into
 * `WS-IN-TYPE-CD` (L310), so its name carries both the record it belongs to and the fact that it is a
 * code. The path segment `/reference/transaction-types/` already states the first half, which leaves
 * the parameter naming only the second -- so `:cd` says exactly what `TYPE-CD` said minus the
 * qualifier the address has already supplied.
 *
 * Refactoring Rationale: the segment was spelled `typeCd`, which is the TRANSPORT property name --
 * `ui/src/api/types.ts` declares `TransactionType.typeCd` and the published contract keys the record
 * by it. Two different names for one value in one URL is what the correction removes: `ui/src/api/
 * reference.ts` L62 already describes this route as `/reference/transaction-types/:cd`, so the path
 * and the request target it is built into now agree on one spelling of the code. The transport
 * property keeps its own name, because that one is published rather than chosen here.
 *
 * Assumptions: ONE dynamic route serves both the add and the change entries, and the add entry is
 * the reserved `new` segment its screen recognises. A second, static
 * `/reference/transaction-types/new` route was considered -- react-router matches a static segment
 * ahead of a dynamic one, so both would resolve -- and rejected because the screen already has to
 * recognise the sentinel (the list screen's PF2 navigates to it, and
 * `ui/src/screens/refTypeEdit/index.tsx` records why a two-character key can never collide with a
 * three-character word), so a second route would express the same decision twice and let the two
 * spellings drift.
 *
 * Assumptions: both Db2 reference routes exist because `app/cpy/COADM02Y.cpy` L22 sets
 * `CDEMO-ADMIN-OPT-COUNT` to `6`. The `VALUE 4` on L21 is commented out, so reading it as live
 * would drop options 5 and 6 -- this route and the list above it -- from the reachable graph.
 */
export const REF_TYPE_EDIT_PATH = '/reference/transaction-types/:cd';

/** How a route is reached: with no session, with any session, or with the administrative claim. */
export type RouteAccess = 'public' | 'authenticated' | 'administrative';

/**
 * One row of the reachability graph: a path, how it is guarded, and the program it replaces.
 *
 * Assumptions: `program` is carried so the graph can be checked against the reference rather than
 * against itself. The two option copybooks name programs, not paths, so a row that cites its
 * program is directly comparable with `app/cpy/COMEN02Y.cpy` and `app/cpy/COADM02Y.cpy`.
 */
export interface RouteTableEntry {
  /** Path pattern react-router matches, identical to the one the route object below declares. */
  readonly path: string;

  /** Guard the path sits behind, which is the session `./routerRoutes.test.tsx` signs on with. */
  readonly access: RouteAccess;

  /** Name of the CICS program this route replaces, from the reference option tables. */
  readonly program: string;
}

/*
 * WHY : Assumptions: this table is DERIVED from the two reference option copybooks and not
 *       invented. `app/cpy/COMEN02Y.cpy` sets `CDEMO-MENU-OPT-COUNT` to 11 and gives every one of
 *       those options the user type `'U'`; `app/cpy/COADM02Y.cpy` sets `CDEMO-ADMIN-OPT-COUNT` to 6
 *       and its record (L56-L59) carries NO user-type field at all, because every administrative
 *       option is administrative by construction. Four further programs are reachable without
 *       appearing in either menu: `COSGN00C` is the entry point, `COMEN01C` and `COADM01C` are what
 *       sign-on transfers to for `'U'` and `'A'` respectively, and `COPAUS1C` is selected from
 *       within the authorization summary. Eleven plus six plus four is twenty-one programs, which
 *       closes the graph exactly -- every online program the reference can reach has a route here,
 *       and no route here names a program the reference cannot reach.
 * WHY : Trade-offs: the graph is published as inert data as well as being mounted as route objects
 *       below, which is a second surface to keep in step in exchange for making two properties
 *       checkable without mounting React -- that the twenty-one paths are exactly the intended
 *       ones, and that each sits behind the intended guard. Auditing the guard by eye over a nested
 *       route array is the alternative, and the equivalent reading over nested JSX is what went
 *       wrong when `RequireAdmin` was authored and called by nothing. The two surfaces cannot drift
 *       on the path text because both take it from the one constant above.
 */

/**
 * The reachability graph as data: every screen path, its guard, and the program it replaces.
 *
 * Assumptions: TWENTY-ONE rows for TWENTY-ONE programs, one row each, in the order the reference's
 * own option tables give them. The bare origin and the catch-all are deliberately absent: both are
 * router artifacts that replace no program, so including them would inflate a graph whose whole
 * purpose is comparison with the reference.
 *
 * ⚠️ Refactoring Rationale: there were twenty-two rows, because `COUSR02C` held two -- a
 * selector-free `/users/edit` beside the parameterised path. One program with two routes made this
 * table stop being a bijection with the reference programs, which is the single property that lets a
 * reader check it against `app/cpy/COMEN02Y.cpy` and `app/cpy/COADM02Y.cpy` by counting. The extra
 * row is withdrawn with the route it described.
 *
 * ⚠️⚠️ Refactoring Rationale: `/admin` is `administrative` here, where it was `authenticated`. The
 * withdrawn note argued that the administrative class is reserved for exactly the six options of
 * `app/cpy/COADM02Y.cpy`, that the menu listing them is not one of them, and that a
 * non-administrator rendering it obtains nothing because the screen reads no record and every option
 * refuses them at its destination. Two of those three statements are true and the conclusion is
 * still wrong. What a `carddemo-user` obtained was the entire administrative capability inventory --
 * the `COADM01C` identity band, all six option labels, and a live dispatcher that accepted an entry
 * and moved them to `/users` before anything refused them -- disclosed on a screen whose message band
 * was EMPTY, so nothing on it said they were not entitled to be there. Disclosure is what a guard on
 * a menu prevents; the absence of a data read is why the disclosure was silent rather than why it was
 * harmless.
 *
 * Assumptions: the enforcement claim in the withdrawn note stands and is unaffected either way --
 * each service validates the token and its group claim independently, so no administrative RECORD
 * was ever reachable with an ordinary claim. This row governs disclosure and refusal placement, and
 * on both counts `app/cbl/COSGN00C.cbl` L230-L240 is unambiguous: an `'A'` operator is transferred to
 * `COADM01C`, everyone else to `COMEN01C`, so a `'U'` operator has no path to this menu at all. The
 * divergence the withdrawn note described as deliberate was therefore a divergence FROM the reference
 * as well as from the AAP's own rule that the admin/user split comes from the signed claim, and it is
 * closed.
 */
export const ROUTE_TABLE: readonly RouteTableEntry[] = [
  { path: SIGN_ON_ROUTE, access: 'public', program: 'COSGN00C' },
  { path: MAIN_MENU_ROUTE, access: 'authenticated', program: 'COMEN01C' },
  { path: ACCOUNT_VIEW_PATH, access: 'authenticated', program: 'COACTVWC' },
  { path: ACCOUNT_UPDATE_PATH, access: 'authenticated', program: 'COACTUPC' },
  { path: CARD_LIST_PATH, access: 'authenticated', program: 'COCRDLIC' },
  { path: CARD_DETAIL_ROUTE, access: 'authenticated', program: 'COCRDSLC' },
  { path: CARD_EDIT_ROUTE, access: 'authenticated', program: 'COCRDUPC' },
  { path: TRANSACTION_LIST_PATH, access: 'authenticated', program: 'COTRN00C' },
  { path: TRANSACTION_ADD_PATH, access: 'authenticated', program: 'COTRN02C' },
  { path: TRANSACTION_DETAIL_PATH, access: 'authenticated', program: 'COTRN01C' },
  { path: BILL_PAY_PATH, access: 'authenticated', program: 'COBIL00C' },
  { path: REPORTS_PATH, access: 'authenticated', program: 'CORPT00C' },
  { path: AUTH_SUMMARY_PATH, access: 'authenticated', program: 'COPAUS0C' },
  { path: AUTH_DETAIL_PATH, access: 'authenticated', program: 'COPAUS1C' },
  { path: ADMIN_MENU_ROUTE, access: 'administrative', program: 'COADM01C' },
  { path: USER_LIST_PATH, access: 'administrative', program: 'COUSR00C' },
  { path: USER_ADD_PATH, access: 'administrative', program: 'COUSR01C' },
  { path: USER_UPDATE_PATH, access: 'administrative', program: 'COUSR02C' },
  { path: USER_DELETE_PATH, access: 'administrative', program: 'COUSR03C' },
  { path: REF_TYPE_LIST_PATH, access: 'administrative', program: 'COTRTLIC' },
  { path: REF_TYPE_EDIT_PATH, access: 'administrative', program: 'COTRTUPC' },
];

/**
 * The keyless second address of three screens whose primary address carries a record key.
 *
 * Purpose: `app/cpy/COMEN02Y.cpy` gives the main menu eleven options naming eleven distinct programs,
 * and `COCRDSLC`, `COCRDUPC` and `COTRN01C` are addressed per record -- so a menu option, which carries
 * no record, had nowhere of their own to send an operator and three options resolved to the browse that
 * mints their key. These three rows are the addresses of each program's OWN first turn, the one the
 * reference paints when its selection carrier arrives blank.
 *
 * ⚠️ Assumptions: they are published as a SEPARATE table rather than as three more rows of
 * {@link ROUTE_TABLE}, and the reason is that table's one load-bearing property: it is a bijection, one
 * row per program, which is what lets a reader check it against `app/cpy/COMEN02Y.cpy` and
 * `app/cpy/COADM02Y.cpy` by counting. A withdrawn `/users/edit` row cost exactly that countability and
 * is the recorded precedent -- so a second address for a program that already has one goes here, where
 * it is visibly an ALIAS of a program named there, and `ui/src/routerRoutes.test.tsx` holds each row to
 * naming a program the primary table declares exactly once and to sharing that row's guard.
 *
 * Assumptions: no `access` field. An alias may not sit behind a different guard from the screen it
 * reaches, so recording one here would create a second place for the guard to be wrong; the test derives
 * it from the primary row instead, which makes divergence unrepresentable rather than merely unlikely.
 */
export const KEYLESS_ENTRY_ROUTES: readonly { readonly path: string; readonly program: string }[] =
  [
    { path: CARD_DETAIL_ENTRY_ROUTE, program: 'COCRDSLC' },
    { path: CARD_UPDATE_ENTRY_ROUTE, program: 'COCRDUPC' },
    { path: TRANSACTION_DETAIL_ENTRY_ROUTE, program: 'COTRN01C' },
  ];

/**
 * Renders the authenticated subtree only for a caller holding a session.
 *
 * Assumptions: a pathless LAYOUT element rather than the guard repeated on each route, so the guard
 * is applied once for the whole subtree and a route added inside it inherits the guard by where it
 * is written. A route added outside is then visibly outside rather than one word away from being
 * unguarded.
 *
 * Refactoring Rationale: this replaces a `guarded(screen)` helper that wrapped an element passed to
 * it. The helper suited the element form, where a layout route's guard was written inline as
 * `element={guarded(<Outlet />)}`; with the tree expressed as route objects a named component states
 * the same thing without a call site, and it reads as the sibling of {@link AdminSubtree} that it is.
 * @returns {ReactElement} The authenticated subtree wrapped in the sign-on guard.
 */
function AuthenticatedSubtree(): ReactElement {
  return (
    <RequireSignOn>
      <Outlet />
    </RequireSignOn>
  );
}

/**
 * Sends the bare origin to the menu the operator's own claim entitles them to.
 *
 * Purpose: reproduce the reference's entry branch for the one address that has no reference
 * equivalent. `app/cbl/COSGN00C.cbl` L230-L240 transfers an `'A'` operator to `COADM01C` and everyone
 * else to `COMEN01C`; sign-on performs that branch on the response it authenticated with, and this
 * performs the same branch for an operator who arrives at `/` with a session already held.
 *
 * ⚠️ Refactoring Rationale: this component replaces a fixed `<Navigate to={MAIN_MENU_ROUTE} replace />`
 * written inline in the route table. That redirect was role-independent, so an administrator who
 * opened the bare origin landed on the ORDINARY main menu -- `COMEN01C`, transaction `CM00` -- which
 * is a screen the reference never puts in front of an `'A'` operator. The argument for the fixed form
 * was that the origin is reachable with no session, so choosing between the menus would mean reading
 * a claim that might be absent; that is not a cost, because `useAuth` answers `isAdmin: false` with no
 * session held and the main menu's own guard then bounces the anonymous arrival to sign-on, which is
 * exactly what the fixed redirect did.
 *
 * Assumptions: the claim is READ and never asserted. `isAdmin` derives from the signed
 * `cognito:groups` claim through `ui/src/hooks/useAuth.ts`, which publishes no setter for it, so this
 * component chooses a destination and grants nothing -- and the destination it chooses is itself
 * guarded, so a claim a caller could somehow influence would change which guard refuses them rather
 * than whether one does.
 *
 * Assumptions: the transition is `replace`, so the bare origin does not become a history entry the
 * browser's own back control can return an operator to -- which would bounce them forward again.
 * @returns {ReactElement} A redirect to the administrative menu for an administrator, and to the main
 *   menu for every other caller including an anonymous one.
 */
function RootRedirect(): ReactElement {
  const { isAdmin } = useAuth();
  return <Navigate to={roleLandingRoute(isAdmin)} replace />;
}

/*
 * WHY : Refactoring Rationale: the three strings the not-found surface paints are IMPORTED from
 *       `./messages/messages` and were previously spelled inline here. That put operator-visible
 *       text in two kinds of place, which is the one thing the catalogue exists to prevent: a
 *       reader auditing it for completeness could not tell whether an absent string was additive or
 *       forgotten, and the same sentence rendered by the shell and by this file could drift the
 *       first time one copy was corrected. The catalogue's own note on this group records the same
 *       conclusion and names this file as the importer.
 * WHY : Assumptions: the two aliases below keep the names this module already published while the
 *       literals live only in the catalogue. They are re-publications, not second copies -- each is
 *       the catalogue constant under this module's own name for it, and the heading is the one
 *       `./router.test.tsx` imports to assert against -- so the assertion and the rendered surface
 *       cannot disagree about the text.
 */

/** Heading the not-found result paints, re-published from the message catalogue. */
export const NOT_FOUND_TITLE = SCREEN_NOT_AVAILABLE_TITLE;

/** Supporting sentence the not-found result paints, re-published from the message catalogue. */
export const NOT_FOUND_SUBTITLE = SCREEN_NOT_AVAILABLE_DETAIL;

/**
 * PF3 legend the not-found surface paints, re-published from the message catalogue.
 *
 * Assumptions: the label is BORROWED from a delivered screen rather than authored here. `F3=Back` is
 * the legend `app/bms/COUSR00.bms` paints on its row-24 field and five of the delivered screens carry
 * it, so an operator meeting it on this surface reads the key they already know. Authoring a new
 * sentence for an additive surface is what Rule T8 exists to prevent, and the alternative catalogued
 * spelling -- `F3=Exit` on the two menus -- would be wrong here, because this key returns the operator
 * to the application rather than ending their session the way `app/cbl/COADM01C.cbl` L100-L102 does.
 */
const RECOVERY_EXIT_KEY_LABEL = USER_LIST_KEY_LABELS.PFK03;

/** One destination an out-of-frame surface offers: the label it paints and the route it opens. */
interface RecoveryDestination {
  /** Operator-visible label, taken from the message catalogue and never composed here. */
  readonly label: string;
  /** Route the label opens, resolved from the reference program the option names. */
  readonly path: string;
}

/**
 * Lists the screens the operator's own claim entitles them to, one entry per destination.
 *
 * Purpose: supply the list {@link NOT_FOUND_SUBTITLE} promises. That sentence ends `Use a listed
 * screen below.` and nothing was listed -- the surface offered one control -- so the sentence
 * described a list the operator could not see.
 *
 * Assumptions: the entries are derived from the reference's own option tables rather than authored
 * here, so every label is a verbatim transcription: `app/cpy/COADM02Y.cpy` for an administrator and
 * `app/cpy/COMEN02Y.cpy` for everyone else, through the catalogue's `ADMIN_MENU_OPTIONS` and
 * `MAIN_MENU_OPTIONS`. Deriving them is what keeps this surface free of invented screen names, which
 * is Rule T8's requirement even for a surface the reference has no equivalent of.
 *
 * Assumptions: entries are DEDUPLICATED by destination, keeping the first label that reaches each
 * route. Several options share a route by design -- the three card options and the two transaction
 * browse options resolve to the browse that acquires the selector each screen needs, which
 * `ui/src/routes/programRoutes.ts` records -- so listing every option would paint the same
 * destination three times under three names. A menu must show all eleven because an operator types an
 * option number; a list of destinations must show each destination once.
 *
 * Assumptions: an option this delivery carries no screen for is DROPPED rather than listed as
 * unavailable. `routeForProgram` answers `null` for exactly that case, and the not-installed sentence
 * belongs to the menu that dispatched the option, not to a surface offering somewhere to go.
 * @param {boolean} isAdmin - Whether the operator's signed claim carries the administrative group.
 * @returns {readonly RecoveryDestination[]} One entry per reachable destination, in the option
 *   table's own order.
 */
function recoveryDestinations(isAdmin: boolean): readonly RecoveryDestination[] {
  const options = isAdmin ? ADMIN_MENU_OPTIONS : MAIN_MENU_OPTIONS;
  const claimed = new Set<string>();
  const destinations: RecoveryDestination[] = [];

  for (const option of options) {
    const path = routeForProgram(option.programName);
    if (path !== null && !claimed.has(path)) {
      claimed.add(path);
      /*
       * WHY : Assumptions: the name is TRIMMED for display. Every option name is stored at the
       *       35-character width `app/cpy/COMEN02Y.cpy` and `app/cpy/COADM02Y.cpy` declare, and the
       *       two menu screens render it padded inside a fixed-pitch composed line so the option
       *       list aligns on its separator exactly as the terminal's did. A control label has no
       *       column to align with and HTML collapses the padding anyway, so the padding would be
       *       invisible weight on the value. `ui/index.html` takes the same decision for the same
       *       reason with the browser tab's copy of the `PIC X(40)` application title.
       */
      destinations.push({ label: option.name.trim(), path });
    }
  }

  return destinations;
}

/**
 * Renders the not-found surface inside the frame, without reflecting the rejected path.
 *
 * Assumptions: the rejected address is deliberately NOT echoed, and this is the one property of the
 * previous surface that is preserved unchanged. The catch-all is reachable by an unauthenticated
 * caller with any address, so interpolating the requested path into the rendered document would put
 * caller-chosen text on a page this application serves, and it would tell an operator nothing they
 * did not type.
 *
 * ⚠️ Refactoring Rationale: this replaces an antd `Result status="404"` mounted OUTSIDE the frame,
 * and both halves of that were measured defects. Outside the frame, the surface had no header, no
 * footer, no message row, no key legend and no skip link -- every one of them measured absent -- so
 * an operator who mistyped an address lost the whole application chrome and had only the browser's
 * own back control to recover with. And the result's `extra` slot held ONE outlined control opening
 * the card browse, while its own subtitle promised a list of screens: the weakest available emphasis
 * on the only way out, beneath a sentence describing something that was not there.
 *
 * Assumptions: it delegates a message slot whose text is `null` and a key legend, and NO screen
 * identity. The empty message slot reserves the row-23 line, which is how all 21 screens ask for the
 * frame's message channel without having anything to say; the legend puts a real control in the
 * row-24 zone and binds PF3 to it. Identity is withheld because there is no program here -- the
 * reference paints `Tran:` and `Prog:` from a program's own `WS-TRANID` and `WS-PGMNAME`, and this
 * surface replaces no program, so naming one would report an operator into a transaction that does
 * not exist.
 *
 * Trade-offs: the primary control opens the MAIN menu for every signed-on operator, administrator or
 * not, rather than the menu their claim lands them on. `app/bms/COADM01.bms`'s `'Admin Menu'` caption
 * is authored in the administrative screen's own lazily-loaded module rather than in the shared
 * catalogue, and importing it here would pull that whole chunk into the entry bundle -- so this
 * surface can name the ordinary menu and not the administrative one. The cost is bounded: every
 * administrative destination listed below returns to `/admin` on its own PF3, so an administrator is
 * one key from their menu from any of them.
 * @returns {ReactElement} The not-found surface, painted inside the frame's content region.
 */
function NotFoundScreen(): ReactElement {
  const navigate = useNavigate();
  const { signedOn, isAdmin } = useAuth();

  /*
   * WHY : Assumptions: an operator holding NO session is offered sign-on and nothing else. Every
   *       other route in the table is guarded, so listing screens to an anonymous caller would list
   *       destinations that immediately bounce them here-adjacent -- to sign-on -- with a redirect
   *       they did not ask for. The label is the catalogue's own sign-on control label, so the
   *       wording matches the button they are about to meet.
   */
  const primary: RecoveryDestination = signedOn
    ? { label: MAIN_MENU_HEADINGS.SCREEN, path: MAIN_MENU_ROUTE }
    : { label: SIGN_ON_SUBMIT_LABEL, path: SIGN_ON_ROUTE };
  const alternatives = signedOn ? recoveryDestinations(isAdmin) : [];

  const { bindings, invoke } = usePfKeys({
    PFK03: {
      /**
       * Leaves the not-found surface for the primary destination.
       * @returns {void} Nothing; the transition is performed as a side effect on the router.
       */
      onInvoke: (): void => {
        navigateSafely(navigate, primary.path);
      },
      label: RECOVERY_EXIT_KEY_LABEL,
    },
  });

  useShellSlot({
    message: { text: null },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  return (
    <Flex vertical gap="middle" align="flex-start">
      <ScreenTitle>{NOT_FOUND_TITLE}</ScreenTitle>
      <Typography.Text>{NOT_FOUND_SUBTITLE}</Typography.Text>
      {/*
        Assumptions: the destinations are laid out with the design system's own primitives and each
        one is a `Button`, so no raw element and no bespoke CSS appears on this surface. The primary
        variant marks the one destination that leads everywhere else; the rest are `type="link"`,
        which is antd's low-emphasis variant rather than a colour written here.
      */}
      <Flex vertical gap="small" align="flex-start">
        <Button type="primary" onClick={navigationHandler(navigate, primary.path)}>
          {primary.label}
        </Button>
        {alternatives.map(
          /**
           * Renders one alternative destination as a low-emphasis control.
           * @param {RecoveryDestination} destination - The label and route to offer.
           * @returns {ReactElement} The control that opens it.
           */
          (destination: RecoveryDestination): ReactElement => (
            <Button
              key={destination.label}
              type="link"
              onClick={navigationHandler(navigate, destination.path)}
            >
              {destination.label}
            </Button>
          ),
        )}
      </Flex>
    </Flex>
  );
}

/**
 * Renders the administrative subtree only for a caller holding the administrative group claim.
 *
 * Assumptions: `RequireAdmin` reads the signed `carddemo-admin` group claim through
 * `ui/src/hooks/useAuth.ts` and nothing else. The reference decides this from `CDEMO-USER-TYPE`
 * (`app/cpy/COCOM01Y.cpy` L26-L28, `88 CDEMO-USRTYP-ADMIN VALUE 'A'`), a byte held in the
 * communication area -- storage the terminal echoes back between turns, which a client could in
 * principle assert for itself. `app/cbl/COMEN01C.cbl` L181-L182 shows how thin that was: both moves
 * that would have re-established the identity on menu dispatch are commented out, so the byte was
 * merely carried. The migrated form cannot be asserted by a caller: the claim is signed by the
 * identity provider, `useAuth` exposes no setter for it, and every service verifies the token
 * independently.
 *
 * Assumptions: this depends on the identity token being validated and on `cognito:groups` being
 * part of the signed payload, so a group a caller adds locally changes what is RENDERED and never
 * what is permitted.
 *
 * Assumptions: SEVEN paths sit in this subtree -- the six options of `app/cpy/COADM02Y.cpy` (user
 * list, user add, user update, user delete, transaction-type list and transaction-type maintenance)
 * and `/admin`, the menu that lists them. That record (L51-L55) carries no user-type field at all,
 * because every administrative option is administrative by construction, unlike the main-menu record
 * whose every entry is explicitly `'U'`.
 *
 * ⚠️ Refactoring Rationale: `/admin` was NOT in this subtree and now is. It was excluded on the
 * ground that the option table names six options and the menu is not one of them; what that reading
 * missed is that the same copybook is a list of administrative capabilities, so rendering it to an
 * operator who may use none of them discloses the inventory and defers the refusal by one screen.
 * `app/cbl/COSGN00C.cbl` L230-L240 is the boundary the reference draws, and it draws it at the menu.
 *
 * Assumptions: nothing else is gated. `/transactions/new` in particular stays outside, because it is
 * main-menu option 8 with user type `'U'` -- the `'(Admin Only)'` wording at `app/cpy/COMEN02Y.cpy`
 * L69 sits on a commented-out variant that no live entry asserts.
 * @returns {ReactElement} The administrative subtree wrapped in the administrative guard.
 */
function AdminSubtree(): ReactElement {
  return (
    <RequireAdmin>
      <Outlet />
    </RequireAdmin>
  );
}

/*
 * WHY : Trade-offs: ONE `Suspense` boundary for the whole tree, declared on the pathless layout
 *       route below rather than one boundary per lazily loaded route. A boundary per route would
 *       keep the frame on screen while a chunk arrived, because the fallback would then sit inside
 *       the shell's content zone instead of replacing the whole document -- which is the nicer
 *       transition -- and it was rejected because it multiplies the one thing this file must keep
 *       auditable: with a boundary per lazily loaded route, a route added without one falls back to
 *       the nearest ancestor silently, and a reader cannot tell by counting whether every route is
 *       covered. One boundary above everything covers every route including any added later, and the
 *       cost is a spinner in place of the frame on a first navigation rather than inside it.
 * WHY : Assumptions: the boundary is ABOVE both frame branches, so the two shell mounts stay
 *       siblings. Pushing it down into each branch would make it two boundaries and would give the
 *       shell-nesting check in `ui/src/layout/appShellIntegration.test.tsx` two shapes to allow
 *       rather than one.
 */

/**
 * The mounted route tree: every browser route backed by an authored screen module.
 *
 * Purpose: this is the graph `EXEC CICS XCTL` becomes. It is declared as route objects rather than
 * as elements because `createBrowserRouter` takes objects, and it is exported separately from
 * {@link cardDemoRouter} so a test can mount the SHIPPED tree in a memory router at any initial
 * entry -- which is the arrangement in which an unmounted route fails, where a test that supplied
 * its own routes could only ever prove that a screen works once mounted.
 *
 * Assumptions: the paths and guards here are exactly {@link ROUTE_TABLE}'s, in the same order, so
 * the two surfaces can be compared row by row. `ui/src/routerRoutes.test.tsx` asserts that
 * correspondence; keeping the order identical as well as the content is what makes the comparison a
 * reading rather than a search.
 *
 * ⚠️ Assumptions: the bare-origin redirect is the ONLY entry outside the frame, where the not-found
 * result used to be outside it too. A redirect is genuinely unframeable -- it renders for one commit
 * and then leaves, so a frame around it would paint a title band for a screen nobody sees -- while an
 * unmatched address is a surface an operator READS, and reading it with no chrome cost them the
 * message row, the key legend and the skip link. {@link NotFoundScreen} records the measurement.
 * Neither shows a sign-off control unless a session is held: the shell renders that from the session,
 * not from the route.
 *
 * Exceptions or errors: none are raised from here. A path matching nothing resolves to
 * {@link NotFoundScreen}, and a chunk that fails to load surfaces through the one `Suspense`
 * boundary above.
 */
export const CARD_DEMO_ROUTES: readonly RouteObject[] = [
  {
    element: (
      <Suspense fallback={<Spin size="large" />}>
        <Outlet />
      </Suspense>
    ),
    children: [
      /*
       * WHY : Assumptions: the bare root redirects to a MENU because that is the reference's own
       *       entry sequence -- `app/cbl/COSGN00C.cbl` L230-L240 transfers to a menu program and
       *       never to a browse -- so an operator who lands here sees the screen a sign-on would
       *       have taken them to. WHICH menu is the claim's decision and not this table's, which is
       *       why the element is a component: {@link RootRedirect} records what the previous fixed
       *       `/menu` destination cost an administrator.
       */
      { path: ROOT_PATH, element: <RootRedirect /> },
      /*
       * WHY : ⚠️ Refactoring Rationale: sign-on has its OWN frame branch, a SIBLING of the guarded
       *       branch below, where it was previously a child of one shell mount shared with the
       *       guarded subtree. Sign-on is the route that establishes the credential every other
       *       route requires, so nothing above it may require one -- and while it sat inside the
       *       shared branch the only thing keeping it unguarded was that the guard happened to be
       *       nested one level deeper. A guard moved up one level, or a loader added to that shared
       *       branch, would have made the application unreachable; as a sibling it cannot.
       * WHY : Assumptions: it is nevertheless FRAMED, and an unframed sign-on was rejected outright.
       *       `ui/src/screens/signon/index.tsx` delegates its title band, its row-23 message and its
       *       row-24 legend through `useShellSlot`, so with no frame above it the mapset's `ERRMSG`
       *       field had no renderer: all three of the reference's refusal sentences --
       *       `app/cbl/COSGN00C.cbl` L242-L256 -- were computed and discarded, and the
       *       `ENTER=Sign-on` legend the mapset paints was absent from the one screen every operator
       *       sees first. This tree repaired that once already. Alternatives Considered: having this
       *       one screen compose its own three bands; rejected because it makes sign-on the single
       *       exception to the delegation contract every other screen follows, for a frame it would
       *       then paint slightly differently.
       * WHY : Assumptions: two shell mounts cannot frame one screen twice, because a location
       *       matches this branch or the guarded branch and never both -- so exactly one shell
       *       renders per location. The double-frame defect
       *       `ui/src/layout/appShellIntegration.test.tsx` guards came from NESTING one shell inside
       *       another, and siblings cannot nest.
       * WHY : Assumptions: reaching sign-on is a deliberate session reset in the reference too:
       *       `app/cbl/COMEN01C.cbl` L196-L203 defaults `CDEMO-TO-PROGRAM` to `'COSGN00C'` and
       *       transfers with NO communication area at all, discarding the identity and the selection
       *       context in one step. The shell renders its sign-off control only while a session is
       *       held, so an operator at sign-on is offered nothing they cannot do.
       */
      {
        element: <AppShell />,
        children: [
          {
            element: <BoundedShellOutlet />,
            children: [
              { path: SIGN_ON_ROUTE, element: <SignOnScreen /> },
              /*
               * WHY : ⚠️ Refactoring Rationale: the catch-all is a child of THIS branch, where it was
               *       a top-level sibling of both `<AppShell />` mounts. Mounted outside the frame it
               *       had no header, no footer, no message row, no key legend and no skip link --
               *       every one measured absent -- so mistyping an address cost an operator the whole
               *       chrome and left the browser's back control as the only way out. The note that
               *       stood here argued the surface "replaces no program" so framing it would report a
               *       transaction identifier for a screen that does not exist; that conclusion does not
               *       follow from its premise, because the frame paints identity only when a screen
               *       DELEGATES it, and {@link NotFoundScreen} delegates none. What it delegates is the
               *       message row and a legend, so the operator keeps every zone and gains a control.
               * WHY : Assumptions: it is in the PUBLIC branch, so it stays reachable with no session --
               *       which it must be, because an unmatched address is the one surface an
               *       unauthenticated caller reaches without passing a guard, and bouncing them to
               *       sign-on instead would answer a mistyped URL with a credential prompt. The frame
               *       renders its sign-off control only while a session is held, so an anonymous
               *       arrival is still offered nothing they cannot do.
               * WHY : Assumptions: a splat cannot shadow a declared route. React Router scores a
               *       dynamic splat below every static and every parameterised segment, so all 21
               *       paths continue to match their own screens whichever branch they sit in; the
               *       reachability census in `ui/src/routerReachability.test.tsx` renders each of them
               *       through the shipped table and would report any shadowing as a not-found result
               *       in place of a screen.
               */
              { path: NOT_FOUND_PATH, element: <NotFoundScreen /> },
            ],
          },
        ],
      },
      {
        element: <AppShell />,
        children: [
          {
            element: <BoundedShellOutlet />,
            children: [
              {
                element: <AuthenticatedSubtree />,
                children: [
                  /*
                   * Assumptions: the main menu is what sign-on transfers to for an ordinary operator,
                   * chosen from the group claim rather than from a field the client supplied --
                   * `app/cbl/COSGN00C.cbl` L230-L240 branches on the user-type byte, and
                   * `ui/src/screens/signon/index.tsx` branches on the signed claim. The administrative
                   * menu is the other half of that branch and is mounted further down, at the boundary
                   * between the main-menu options and the gated six.
                   */
                  { path: MAIN_MENU_ROUTE, element: <MainMenuScreen /> },
                  /*
                   * Assumptions: `/account/view` and `/account/update` are declared as STATIC paths
                   * with no parameter and with no admin guard. The account identifier is entered on the
                   * screen itself rather than carried in the URL, mirroring `COACTVWC` and `COACTUPC`,
                   * whose only unprotected control on first entry is the `ACCTSID` search key; and
                   * `app/cpy/COMEN02Y.cpy` L25-L35 gives options 1 and 2 the user type `'U'`, so gating
                   * them on the `carddemo-admin` claim would lock out exactly the operators the options
                   * exist for. The card routes need their own selector module because they carry an
                   * opaque identifier in the path, and these routes need no such helper because they
                   * carry nothing. `ui/nginx.conf` already names these paths in its SPA history
                   * fallback, so the served application and this table agree.
                   */
                  { path: ACCOUNT_VIEW_PATH, element: <AccountViewScreen /> },
                  { path: ACCOUNT_UPDATE_PATH, element: <AccountUpdateScreen /> },
                  { path: CARD_LIST_PATH, element: <CardListScreen /> },
                  /*
                   * WHY : ⚠️ Purpose: the two KEYLESS card addresses are mounted here, beside the keyed
                   *       ones, because they reach the same two screens with no selector -- which is the
                   *       arrival `app/cbl/COCRDSLC.cbl` L490-L491 paints when it falls back to
                   *       `WS-PROMPT-FOR-INPUT` on an empty map with account and card fields to type
                   *       into. Main-menu options 4 and 5 name `COCRDSLC` and `COCRDUPC` and carry no
                   *       record, so without these two addresses both options resolved to the browse and
                   *       eleven options reached eight destinations.
                   * WHY : Assumptions: no ordering rule is needed against the dynamic siblings below,
                   *       for the reason already recorded at `/transactions/new`: React Router scores a
                   *       static segment above a dynamic one, so `/cards/view` and `/cards/edit` reach
                   *       their own routes and only a real selector reaches `/cards/:cardKey`. They are
                   *       declared in reading order rather than in a precedence order.
                   * WHY : ⚠️ Assumptions: they are their own ROUTES rather than sentinel values matched
                   *       by `/cards/:cardKey`, and `ui/src/screens/cardDetail/index.tsx` is why -- it
                   *       distinguishes a parameter that is present but unusable from one that is absent,
                   *       painting invalid-link guidance for the first. A sentinel would tell an operator
                   *       who chose a menu option that their link was broken. `ui/src/routes/navigation.ts`
                   *       records the measurement behind the third alternative, an empty segment: React
                   *       Router matches none, so `/cards//edit` falls through to the catch-all below and
                   *       paints `Screen not available`.
                   */
                  { path: CARD_DETAIL_ENTRY_ROUTE, element: <CardDetailScreen /> },
                  { path: CARD_UPDATE_ENTRY_ROUTE, element: <CardUpdateScreen /> },
                  { path: CARD_DETAIL_ROUTE, element: <CardDetailScreen /> },
                  { path: CARD_EDIT_ROUTE, element: <CardUpdateScreen /> },
                  /*
                   * WHY : Assumptions: the transaction routes sit in the guarded but NOT administrative
                   *       subtree, because `app/cpy/COMEN02Y.cpy` gives main-menu options 6, 7 and 8 the
                   *       programs `COTRN00C`, `COTRN01C` and `COTRN02C` and the user type `'U'` -- so
                   *       gating any of them on the `carddemo-admin` claim would lock out exactly the
                   *       operators the options exist for. `/transactions/new` in particular stays
                   *       authenticated and ungated: the commented-out alternative at
                   *       `app/cpy/COMEN02Y.cpy` L69 labels option 8 `'Transaction Add (Admin Only) '`,
                   *       and reading that dead line as live is the likeliest well-meant correction to
                   *       this tree. The browse is a STATIC path carrying no parameter: the starting
                   *       transaction identifier is entered on the screen itself, mirroring the mapset's
                   *       own `TRNIDIN` search field rather than travelling in the address.
                   * WHY : Assumptions: the DYNAMIC detail path coexists with the static
                   *       `/transactions/new` above it and no ordering rule is needed. React Router
                   *       ranks a static segment ahead of a dynamic one when it scores its routes, so
                   *       `/transactions/new` continues to reach the capture screen and only some other
                   *       second segment reaches the detail screen -- which is why the two are declared
                   *       in reading order rather than in a precedence order.
                   */
                  { path: TRANSACTION_LIST_PATH, element: <TransactionListScreen /> },
                  { path: TRANSACTION_ADD_PATH, element: <TransactionAddScreen /> },
                  /*
                   * WHY : ⚠️ Purpose: the KEYLESS transaction-detail address, which main-menu option 7
                   *       names. `app/cbl/COTRN01C.cbl` L103-L108 reads its selection carrier and L109
                   *       paints the EMPTY map when it arrives blank, with the transaction identifier as
                   *       a field to type into -- so the program has a first turn that needs no
                   *       selection, and option 7 previously entered the screen option 6 enters.
                   * WHY : ⚠️ Assumptions: a route of its own rather than a sentinel under
                   *       `/transactions/:id`, and this screen forces it more strongly than the card
                   *       screens do: `ui/src/screens/transactionDetail/index.tsx` seeds its lookup
                   *       control from the parameter and reads on a present one, so a sentinel arrival
                   *       would issue a lookup for the literal sentinel text and paint a not-found
                   *       sentence about a record nobody asked for. A static path leaves the parameter
                   *       `undefined`, which that screen already documents as its selector-free arrival.
                   */
                  { path: TRANSACTION_DETAIL_ENTRY_ROUTE, element: <TransactionDetailScreen /> },
                  { path: TRANSACTION_DETAIL_PATH, element: <TransactionDetailScreen /> },
                  { path: BILL_PAY_PATH, element: <BillPayScreen /> },
                  /*
                   * Assumptions: the report screen is mounted here, between the transaction capture and
                   * the authorization summary, because that is its position in the reference's own
                   * option table -- `app/cpy/COMEN02Y.cpy` lists Transaction Add as option 8,
                   * Transaction Reports as option 9 and Pending Authorization View as option 11.
                   * Ordering the table by the menu an operator reads keeps this file comparable with
                   * the copybook it is derived from.
                   */
                  { path: REPORTS_PATH, element: <ReportsScreen /> },
                  /*
                   * Assumptions: the authorization detail path carries an OPAQUE key rather than the
                   * composite the reference selects by. `ui/src/screens/authSummary/index.tsx` mints it
                   * and `ui/src/api/authorization.ts` records why the account identifier, date and time
                   * may not travel in a path, so this table only has to declare the parameter the
                   * summary screen builds.
                   */
                  { path: AUTH_SUMMARY_PATH, element: <AuthSummaryScreen /> },
                  { path: AUTH_DETAIL_PATH, element: <AuthDetailScreen /> },
                  {
                    element: <AdminSubtree />,
                    children: [
                      /*
                       * WHY : ⚠️⚠️ Refactoring Rationale: the administrative MENU is mounted INSIDE the
                       *       gated subtree, where it sat outside it as a sibling declared just above
                       *       this element. The note that stood there argued the gated set is exactly
                       *       the six options of `app/cpy/COADM02Y.cpy` and the menu listing them is
                       *       not one of them, so it could be authenticated like any screen an
                       *       ordinary operator may render, conceding only that such an operator would
                       *       see "six static option labels" and be refused at each destination. What
                       *       an ordinary operator actually got was the whole administrative menu --
                       *       `CA00`/`COADM01C` in the title band, all six labels, and a focused option
                       *       field that accepted an entry and dispatched it -- with an EMPTY message
                       *       band, and the refusal then arrived at `/users` rather than here. That is
                       *       a disclosure the reference does not make: `app/cbl/COSGN00C.cbl`
                       *       L230-L240 transfers only an `'A'` operator to `COADM01C`.
                       * WHY : Assumptions: it is the FIRST child of the subtree, which is both the
                       *       administrative option table's own reading order -- the menu, then its
                       *       six options -- and the position that makes the boundary visible: every
                       *       administrative path is now inside one element, so a seventh added later
                       *       is gated by where it is written rather than by remembering to classify
                       *       it. `ui/src/routerRoutes.test.tsx` pins the class of all 21 paths by
                       *       name, so an omission fails a case.
                       */
                      { path: ADMIN_MENU_ROUTE, element: <AdminMenuScreen /> },
                      /*
                       * Assumptions: the user browse is declared INSIDE this administrative subtree, so
                       * it inherits the one `RequireAdmin` above rather than carrying a guard of its
                       * own. `app/cpy/COADM02Y.cpy` L26-L29 gives `COUSR00C` administrative option 1,
                       * so the browse is reachable only from the administrative menu -- exactly the set
                       * of screens this subtree exists for. Its screen reads no group claim, which
                       * keeps the authorization decision in one place.
                       */
                      { path: USER_LIST_PATH, element: <UserListScreen /> },
                      /*
                       * Assumptions: the add-user route is declared BEFORE the maintenance routes and
                       * is a literal path, so it cannot be shadowed by them: `/users/new` has two
                       * segments and `/users/:id/edit` has three, and react-router ranks a static
                       * segment ahead of a dynamic one at the same depth. The order below follows the
                       * administrative menu's own option sequence instead, which is the reading order
                       * `app/cpy/COADM02Y.cpy` gives the options.
                       */
                      { path: USER_ADD_PATH, element: <UserAddScreen /> },
                      { path: USER_UPDATE_PATH, element: <UserUpdateScreen /> },
                      /*
                       * WHY : Assumptions: the deletion route sits INSIDE the administrative subtree, so
                       *       the `carddemo-admin` claim gates it exactly as it gates the other user
                       *       routes. `app/csd/CARDDEMO.CSD` reaches `COUSR03C` only from the
                       *       administrative menu, and `app/cpy/COADM02Y.cpy` L41-L44 makes it
                       *       administrative option 4, so a deletion route outside this subtree would be
                       *       the one destructive operation in the table an ordinary operator could
                       *       reach. The screen itself deliberately carries no gate of its own -- this
                       *       is the single place the policy is stated.
                       */
                      { path: USER_DELETE_PATH, element: <UserDeleteScreen /> },
                      { path: REF_TYPE_LIST_PATH, element: <RefTypeListScreen /> },
                      { path: REF_TYPE_EDIT_PATH, element: <RefTypeEditScreen /> },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  },
];

/*
 * WHY : Assumptions: the array is SPREAD into the factory rather than passed by reference, because
 *       {@link CARD_DEMO_ROUTES} is published `readonly` and `createBrowserRouter` declares its
 *       parameter as a mutable `RouteObject[]`. The copy is one shallow array allocation at module
 *       load and it keeps the exported tree immutable for every other reader, which matters because
 *       `./routerRoutes.test.tsx` and `./layout/appShellIntegration.test.tsx` both read the array: a
 *       caller that could push a route onto the shipped array could add an unguarded path at runtime
 *       without touching this file.
 * WHY : Trade-offs: the router is built at MODULE SCOPE, so importing this module instantiates a
 *       browser history. That is what the data-router API asks for -- the router object owns the
 *       history and `RouterProvider` merely subscribes to it -- and it is why the route ARRAY is
 *       exported beside it: a test that wants a different history builds its own memory router from
 *       {@link CARD_DEMO_ROUTES} instead of reaching for this one.
 */

/**
 * The browser data router the application renders, built from {@link CARD_DEMO_ROUTES}.
 *
 * Assumptions: exactly one `RouterProvider` renders this, in `ui/src/App.tsx`, and nothing else in
 * the tree imports it. A second provider over the same router object would subscribe twice to one
 * history and render the matched route twice, which is the composition-level form of the
 * double-frame defect the two sibling shell mounts above are shaped to prevent.
 */
export const cardDemoRouter = createBrowserRouter([...CARD_DEMO_ROUTES]);
