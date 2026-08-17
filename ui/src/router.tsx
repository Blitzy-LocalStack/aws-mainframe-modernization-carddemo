/**
 * @file The route table: the client-side replacement for the CICS program-transfer graph, the one
 * place the authentication boundary between screens is drawn, and the one place the application
 * frame is mounted.
 *
 * Purpose ------- Declare every browser route backed by an authored screen module, mount the shared
 * frame those screens are painted inside, and classify each route as public, authenticated or
 * administrative. This is where `EXEC CICS XCTL` ends up. The reference programs transfer control
 * by naming a program in the communication area; the migrated equivalent is a route change, so the
 * reachability graph of the online transactions is expressed here and in no screen.
 *
 * Parameters ---------- Not applicable. This module declares components and constants and takes no
 * inputs of its own.
 *
 * Return values ------------- {@link CardDemoRouter} returns the mounted route tree; {@link
 * ROUTE_TABLE} is the same graph as inert data. Each is documented at its declaration.
 *
 * Exceptions or errors -------------------- This module raises none. A path matching no route
 * resolves to {@link NotFoundScreen} rather than throwing, and a chunk that fails to load surfaces
 * through the `Suspense` boundary below.
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
 * Ownership of navigation ----------------------- Assumptions: this module owns the route-to-screen
 * MAPPING and nothing else. It performs no navigation itself -- a screen navigates through
 * `ui/src/routes/navigation.ts`, which validates the destination -- and it holds no route constant
 * for a card path; those live in `ui/src/routes/cards.ts` beside the selector guards that validate
 * them. Splitting it that way is what lets a screen import a path builder without importing the
 * router.
 *
 * Assumptions: the sign-on branch is NOT here. `app/cbl/COSGN00C.cbl` L230-L240 transfers an `'A'`
 * operator to `COADM01C` and everyone else to `COMEN01C`, and its migrated form -- a navigation
 * chosen from the signed group claim -- belongs to `ui/src/screens/signon/index.tsx`. This file
 * only makes `/admin` and `/menu` reachable and gates them; a reader looking for the branch should
 * look there.
 *
 * Authentication boundary ----------------------- Assumptions: exactly one screen route is outside
 * the guard, and it is sign-on, because sign-on is the route that establishes the credential the
 * others require. Every other screen route is a child of the guarded layout route below, so the
 * guard is applied once, structurally, rather than being repeated per route -- and a route added
 * later inherits it by where it is written. The guard decides what is RENDERED only; every service
 * independently validates the token, so an unguarded route would expose a screen and no data.
 *
 * Two nested guards, not one -------------------------- Refactoring Rationale: the administrative
 * routes are nested inside a SECOND guard, and previously they were not guarded at all --
 * `RequireAdmin` was authored, documented as the migrated form of the reference's user-type branch,
 * and imported by nothing, so every administrative screen was reachable by any signed-on operator.
 * The reference gates these screens on the byte it carried in the communication area:
 * `app/cbl/COSGN00C.cbl` L230-L240 transfers an `'A'` operator to `COADM01C`, and
 * `app/cbl/COMEN01C.cbl` L136-L143 refuses an administrator-only option to a non-administrator
 * outright. Leaving the guard uncalled meant a non-administrator reaching user maintenance and
 * reference maintenance saw the screens and met refusals only from the services -- a screen full of
 * failures rather than the refusal the reference gave.
 *
 * The frame is mounted here ------------------------- Refactoring Rationale:
 * `ui/src/layout/AppShell.tsx` is mounted as the guarded LAYOUT route, and previously it was
 * mounted nowhere. Two authored screens -- `./screens/accountView` and `./screens/authSummary` --
 * omit the shared title band on the stated ground that the shell paints it, so with no shell
 * mounted their row-1 and row-2 fields rendered nowhere at all: the transaction identifier, the two
 * application titles, the program name and the paint date and time were simply absent from those
 * two screens. The shell renders each of its zones only when a screen has delegated one, so
 * mounting it changes nothing for the screens that compose their own bands.
 *
 * The declarative element API, and why the data-router factory is not used
 * ----------------------------------------------------------------------- Alternatives Considered:
 * `createBrowserRouter` with the route array passed to a `RouterProvider` in `ui/src/App.tsx`. It
 * is the react-router 8 data-router API and it was rejected here for a reason specific to this tree
 * rather than a preference. Two sibling regression guards assert against the SOURCE TEXT of this
 * file, and each was written to catch a defect that had already shipped.
 * `ui/src/layout/appShellIntegration.test.tsx` counts the self-closing shell element here and
 * requires exactly one, requires the layout route that renders it, and requires `App.tsx` to render
 * this component, because the shell was once mounted in both files at once and framed every guarded
 * screen twice -- two banners, two contentinfo landmarks and two live regions announcing one
 * message. `ui/src/routes/routeCensus.test.ts` measures only the region between the element tags
 * that open and close the route list below, because a screen was once imported here and mounted
 * nowhere, and a declaration alone is exactly that half-finished state. Adopting the factory would
 * remove both anchors: the census would measure an empty region and so report every screen as
 * mounted when none was, and the double-frame guard would have to be weakened. Nothing an operator
 * or a service can observe differs between the two APIs -- route matching, the guards, the lazy
 * split and the frame are identical -- and AAP section 0.1.3.1 names the preserved contract as the
 * reachability graph, not the factory that builds it. {@link ROUTE_TABLE} supplies what the factory
 * would have given: the graph as inert data, assertable without mounting React.
 */

/*
 * WHY : Alternatives Considered: the DOM companion package -- the one whose name is this package's
 *       with `-dom` appended, which react-router 6 split its browser entry points into and which a
 *       reader familiar with that major version will reach for first. It is deliberately ABSENT
 *       from `ui/package.json` and no 8.x of it was ever published: its newest release is a
 *       compatibility shim that depends on `react-router@7.18.1`, so requiring it would silently
 *       hold routing a major version behind the pinned `react-router@8.3.0` while looking like the
 *       more specific choice. Every browser entry point that once justified the split -- the
 *       history-backed router, the link components, the DOM hooks -- is exported from
 *       `react-router` itself in 8.x, so nothing is left for the shim to add. Repointing this
 *       import at that package is the single most likely well-meant edit to this file, and it would
 *       regress the routing version; the name is described rather than written here so that a
 *       search proving this module does not depend on it stays clean.
 */
import { Button, Result, Spin } from 'antd';
import { lazy, Suspense } from 'react';
import type { ReactElement } from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useNavigate } from 'react-router';

import { AppShell } from './layout/AppShell';
import {
  OPEN_CARD_BROWSE_LABEL,
  SCREEN_NOT_AVAILABLE_DETAIL,
  SCREEN_NOT_AVAILABLE_TITLE,
} from './messages/messages';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { RequireAdmin, RequireSignOn, SIGN_ON_ROUTE } from './routes/guards';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE, navigateSafely } from './routes/navigation';
import { SignOnScreen } from './screens/signon';

/*
 * WHY : Trade-offs: every guarded screen is loaded LAZILY and sign-on is not, and the split is the
 *       decision rather than the mechanism. Eager imports would be simpler -- no `lazy`, no
 *       `Suspense`, no default-export adapter below -- and would put all twenty guarded screens
 *       plus their antd dependencies into the single chunk the browser must fetch before the
 *       sign-on form appears. Sign-on is the only screen an unauthenticated operator can reach, and
 *       the guarded screens pull the heaviest components in the tree (`Table`, `Descriptions`,
 *       `Form`, `Select` and `Popconfirm`, with account update alone painting the 128 fields of
 *       mapset `CACTUPA`), so an eager bundle makes every operator pay for the browse, detail,
 *       card-update, account-update, transaction-capture, menu, authorization and reference screens
 *       in order to see a login form. The cost accepted is real and is paid at a different moment:
 *       the FIRST navigation to each guarded route fetches its chunk, so that transition shows the
 *       `Suspense` spinner where an eager build would have shown the screen immediately, and a
 *       chunk that fails to fetch fails at navigation time rather than at start-up.
 * WHY : Assumptions: sign-on is deliberately eager for the same reason -- it is the entry screen,
 *       so deferring it would move the spinner to the one place an operator has nothing to look at
 *       yet. The shell is not lazy either: it is the frame every guarded route renders inside, so
 *       deferring it would add a second chunk fetch to the first guarded navigation and would show
 *       the spinner in place of the frame rather than in place of a screen.
 */

/*
 * WHY : Assumptions: the declarations below and the route table further down are both ordered by
 *       MAIN-MENU OPTION NUMBER rather than alphabetically, because `app/cpy/COMEN02Y.cpy` is the
 *       reachability source and it numbers `Account Update` as option 2 (`COACTUPC`, L31-L35) ahead
 *       of the three card options 3 to 5. Keeping the file in that order means a reader comparing
 *       this table against the option copybook walks both in the same direction, and a missing
 *       screen shows up as a gap in the sequence instead of having to be searched for. The two menu
 *       screens are declared first because they are what sign-on transfers to, and the
 *       administrative screens last because they form their own nested subtree.
 * WHY : Trade-offs: two forms of lazy declaration appear below -- twelve bare `lazy(() =>
 *       import(...))` calls and eight that adapt a named export -- and the difference is not
 *       arbitrary. `React.lazy` accepts only a module whose `default` IS the component, so the
 *       adapter is REQUIRED by the six screens that publish no default export (account view,
 *       account update, authorization summary, card detail, card update and user update); three
 *       screens publish only a default and can only be loaded bare (transaction list, user list and
 *       reference type list); and for the rest either form resolves. Normalising onto one form was
 *       considered and rejected in both directions: adding a default export to those six would edit
 *       six modules to change nothing an operator can observe, and the three default-only modules
 *       cannot take the adapter at all, so no single form covers the twenty.
 * WHY : Assumptions: where either form would resolve, the ADAPTER is used, because the named
 *       binding is the component each screen's own contract publishes and the default is a
 *       convenience re-export beside it. That choice is depended upon: a substituted screen module
 *       publishes the same binding the table reads, so a table that read `default` from a module
 *       whose double publishes only the name would resolve to `undefined` and render nothing.
 */

/** Loads the main menu only when its route needs it. */
const MainMenuScreen = lazy(
  /**
   * Imports the main menu chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/menu'),
);

/** Loads the account view screen only when its route needs it. */
const AccountViewScreen = lazy(
  /**
   * Imports the account view chunk and republishes its named export under the `default` key.
   *
   * Assumptions: this screen publishes NO default export, so a bare `import()` would type the whole
   * module namespace as the loaded component and the compiler rejects it.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/accountView');
    return { default: module.AccountViewScreen };
  },
);

/** Loads the account maintenance screen only when the account update route needs it. */
const AccountUpdateScreen = lazy(
  /**
   * Imports the account maintenance chunk and republishes its named export under the `default` key,
   * which is the only shape React.lazy accepts from a module publishing no default.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/accountUpdate');
    return { default: module.AccountUpdateScreen };
  },
);

/** Loads the card browse screen only when a card route needs it. */
const CardListScreen = lazy(
  /**
   * Imports the card browse chunk and republishes its named export under the `default` key.
   *
   * Assumptions: the NAMED export is read even though this module also publishes a default, because
   * the named binding is the component the screen's own contract states and the one a substituted
   * module publishes in its place. See the note above the declarations for why the two forms
   * differ.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/cardList');
    return { default: module.CardListScreen };
  },
);

/** Loads the card detail screen only when a card route needs it. */
const CardDetailScreen = lazy(
  /**
   * Imports the card detail chunk and republishes its named export under the `default` key, which
   * is the only shape React.lazy accepts from a module publishing no default.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/cardDetail');
    return { default: module.CardDetailScreen };
  },
);

/** Loads the card maintenance screen only when a card route needs it. */
const CardUpdateScreen = lazy(
  /**
   * Imports the card maintenance chunk and republishes its named export under the `default` key,
   * which is the only shape React.lazy accepts from a module publishing no default.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/cardUpdate');
    return { default: module.CardUpdateScreen };
  },
);

/** Loads the transaction browse screen only when its route needs it. */
const TransactionListScreen = lazy(
  /**
   * Imports the transaction browse chunk, which publishes its screen as the module's default
   * export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/transactionList'),
);

/** Loads the transaction capture screen only when its route needs it. */
const TransactionAddScreen = lazy(
  /**
   * Imports the transaction capture chunk and republishes its named export under the `default` key.
   *
   * Assumptions: the NAMED export is read even though this module also publishes a default, for the
   * reason the card browse above records -- the named binding is the screen's stated contract and
   * the one a substituted module publishes in its place.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/transactionAdd');
    return { default: module.TransactionAddScreen };
  },
);

/** Loads the transaction detail screen only when its route needs it. */
const TransactionDetailScreen = lazy(
  /**
   * Imports the transaction detail chunk, which publishes its screen as the module's default
   * export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/transactionDetail'),
);

/** Loads the bill payment screen only when its route needs it. */
const BillPayScreen = lazy(
  /**
   * Imports the bill payment chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/billPay'),
);

/** Loads the transaction-report submission screen only when its route needs it. */
const ReportsScreen = lazy(
  /**
   * Imports the report chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/reports'),
);

/** Loads the pending-authorization summary only when its route needs it. */
const AuthSummaryScreen = lazy(
  /**
   * Imports the pending-authorization summary chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts from a module publishing no default.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/authSummary');
    return { default: module.AuthSummaryScreen };
  },
);

/** Loads the pending-authorization detail only when its route needs it. */
const AuthDetailScreen = lazy(
  /**
   * Imports the pending-authorization detail chunk, which publishes its screen as the module's
   * default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/authDetail'),
);

/** Loads the administrative menu only when its route needs it. */
const AdminMenuScreen = lazy(
  /**
   * Imports the administrative menu chunk, which publishes its screen as the module's default
   * export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/admin'),
);

/** Loads the user browse only when its route needs it. */
const UserListScreen = lazy(
  /**
   * Imports the user browse chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/userList'),
);

/** Loads the add-user screen only when its route needs it. */
const UserAddScreen = lazy(
  /**
   * Imports the add-user chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/userAdd'),
);

/** Loads the user maintenance screen only when its route needs it. */
const UserUpdateScreen = lazy(
  /**
   * Imports the user maintenance chunk and republishes its named export under the `default` key,
   * which is the only shape React.lazy accepts from a module publishing no default.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/userUpdate');
    return { default: module.UserUpdateScreen };
  },
);

/** Loads the user deletion screen only when its route needs it. */
const UserDeleteScreen = lazy(
  /**
   * Imports the user deletion chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/userDelete'),
);

/** Loads the transaction-type list only when its route needs it. */
const RefTypeListScreen = lazy(
  /**
   * Imports the transaction-type list chunk, which publishes its screen as the module's default
   * export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/refTypeList'),
);

/** Loads the transaction-type maintenance screen only when its route needs it. */
const RefTypeEditScreen = lazy(
  /**
   * Imports the transaction-type maintenance chunk, which publishes its screen as the module's
   * default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/refTypeEdit'),
);

/*
 * WHY : Assumptions: every path below that carries no opaque selector is written as a LITERAL here
 *       rather than imported from the screen that serves it, and the reason is the lazy split above
 *       rather than a preference. A screen module reached through `lazy` is in its own chunk;
 *       importing a constant from it at module scope would pull that whole module -- and its antd
 *       dependencies -- back into the entry chunk, silently undoing the split for every screen so
 *       referenced. The card routes are imported because `./routes/cards` is a small selector
 *       module that exists for exactly that purpose, and the menu and sign-on routes because
 *       `./routes/navigation` and `./routes/guards` are likewise not screens. The screens that
 *       publish their own route constants are kept honest by `./routerRoutes.test.tsx`, which
 *       imports both this table's literals and those constants and fails if they disagree -- so the
 *       duplication cannot drift without a test saying so, and it costs the entry chunk nothing.
 */

/** Address the bare origin redirects away from, which names no screen of its own. */
const ROOT_PATH = '/';

/** Wildcard the catch-all occupies, matching any address no route above it claimed. */
const NOT_FOUND_PATH = '*';

/** Route the account view screen occupies, which main-menu option 1 transfers to. */
export const ACCOUNT_VIEW_PATH = '/account/view';

/** Route the account maintenance screen occupies, which main-menu option 2 transfers to. */
export const ACCOUNT_UPDATE_PATH = '/account/update';

/** Route the card browse screen occupies, which main-menu option 3 transfers to. */
export const CARD_LIST_PATH = '/cards';

/** Route the transaction browse screen occupies, which main-menu option 6 transfers to. */
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
 * Assumptions: the segment is spelled `id`, which is the name
 * `ui/src/screens/transactionDetail/index.tsx` reads with `useParams`. React Router resolves a
 * parameter by NAME, so the two spellings have to agree exactly or the screen receives `undefined`
 * and silently falls back to its own parameterless arrival.
 *
 * Assumptions: the identifier travels in the path rather than in history state, and that is safe
 * here where it is not for a card. A transaction identifier is `TRAN-ID PIC X(16)`
 * (`app/cpy/CVTRA05Y.cpy` L5), an internally-assigned ledger key that identifies no cardholder and
 * is not a credential -- unlike a card number, which `ui/src/routes/cards.ts` keeps out of a path
 * precisely because a target is written into the edge access log and the browser's history. The
 * screen additionally shows the card number only in the reduced rendering the service publishes.
 */
export const TRANSACTION_DETAIL_PATH = '/transactions/:id';

/** Route the bill payment screen occupies, which main-menu option 10 transfers to. */
export const BILL_PAY_PATH = '/billpay';

/**
 * Route the transaction-report submission screen is entered at.
 *
 * Assumptions: main-menu option 9, whose `CDEMO-MENU-OPT-PGMNAME` is `CORPT00C` and whose
 * `CDEMO-MENU-OPT-USRTYPE` is `'U'` in `app/cpy/COMEN02Y.cpy`. The user type is why the route sits
 * OUTSIDE the administrative subtree below: every one of the eleven main-menu options is `'U'`, and
 * only the six entries of the admin option table are gated.
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

/** Route the pending-authorization detail occupies, selected from the summary grid. */
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
 * parameter to carry -- which is what distinguishes this route from the two maintenance routes
 * below.
 */
export const USER_ADD_PATH = '/users/new';

/**
 * Route the user maintenance screen occupies when no operator has been selected yet.
 *
 * Assumptions: the screen is mounted at BOTH this selector-free path and the parameterised one
 * below, because it serves both entries and both are reached. `ui/src/screens/admin/index.tsx`
 * sends administrative option 3 here with no identifier -- matching `COUSR02C`, whose first turn is
 * an empty screen the operator types a user identifier into -- while the user list reaches the same
 * screen with one already chosen. The screen's own mount effect reads nothing when the route
 * carries no identifier, which is what lets one module serve both without a second component.
 *
 * Assumptions: this second path is why the table declares TWENTY-TWO screen paths for TWENTY-ONE
 * programs, and it is not an invented destination. It serves `COUSR02C`, which
 * `ui/src/routes/programRoutes.ts` L42 publishes as `USER_UPDATE_UNSELECTED_ROUTE` and L128 maps
 * that program to; withdrawing it would leave administrative option 3 resolving to the not-found
 * result.
 */
export const USER_UPDATE_PATH = '/users/edit';

/**
 * Route the user browse occupies, which administrative option 1 transfers to.
 *
 * Assumptions: it is declared ABOVE the two maintenance paths below and mounted below them, and the
 * ordering is safe either way -- react-router matches a static segment ahead of a dynamic one, so
 * `/users` and `/users/edit` cannot shadow each other and neither can shadow `/users/:id/edit`. It
 * is grouped with them here because all three belong to the same `COUSR0*` family.
 */
export const USER_LIST_PATH = '/users';

/** Route the user maintenance screen occupies when an operator has already been selected. */
export const USER_UPDATE_SELECTED_PATH = '/users/:id/edit';

/**
 * Route the user deletion screen occupies, replacing program `COUSR03C` (transaction `CU03`).
 *
 * Assumptions: this path carries the identifier as `:id`, the SAME parameter name the update routes
 * above use, because `ui/src/screens/userDelete/index.tsx` reads `useParams().id` and the two
 * screens are reached from the same selector. A different spelling would resolve to `undefined`
 * silently and leave the screen waiting for a typed identifier on an arrival that named one.
 *
 * Assumptions: only the PARAMETERISED form is declared, where the update screen has two. `COUSR03C`
 * reaches its own empty first turn through the same map, but a selector-free deletion path would
 * offer a bare route whose only purpose is to destroy a record the operator has not yet named --
 * and the screen already refuses that with `User ID can NOT be empty...`. An administrator who
 * needs to delete without a prior selection reaches this screen from the identifier they type into
 * it, so the second route would add an entry point the reference's own navigation does not.
 */
export const USER_DELETE_PATH = '/users/:id/delete';

/** Route the transaction-type list occupies, which administrative option 5 transfers to. */
export const REF_TYPE_LIST_PATH = '/reference/transaction-types';

/**
 * Route the transaction-type maintenance screen occupies.
 *
 * Assumptions: the segment is spelled `typeCd`, which is the name
 * `ui/src/screens/refTypeEdit/index.tsx` reads with `useParams`. The spelling is load-bearing for
 * the reason every parameter here is: react-router resolves by name, so a near-miss yields
 * `undefined` with no diagnostic.
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
export const REF_TYPE_EDIT_PATH = '/reference/transaction-types/:typeCd';

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
  /** Path pattern react-router matches, identical to the one the mounted route below declares. */
  readonly path: string;

  /** Guard the path sits behind. */
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
 * WHY : Trade-offs: the graph is published as inert data as well as being mounted as elements
 *       below, which is a second surface to keep in step in exchange for making two properties
 *       checkable without mounting React -- that the twenty-two paths are exactly the intended
 *       ones, and that each sits behind the intended guard. Auditing the guard by eye over nested
 *       JSX is the alternative, and it is the reading that went wrong when `RequireAdmin` was
 *       authored and called by nothing. The two surfaces cannot drift on the path text because both
 *       take it from the one constant above.
 */

/**
 * The reachability graph as data: every screen path, its guard, and the program it replaces.
 *
 * Assumptions: TWENTY-TWO rows for TWENTY-ONE programs. `COUSR02C` appears twice because it serves
 * both a selector-free and a selected entry, as {@link USER_UPDATE_PATH} records. The bare origin
 * and the catch-all are deliberately absent: both are router artifacts that replace no program, so
 * including them would inflate a graph whose whole purpose is comparison with the reference.
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
  { path: USER_UPDATE_SELECTED_PATH, access: 'administrative', program: 'COUSR02C' },
  { path: USER_DELETE_PATH, access: 'administrative', program: 'COUSR03C' },
  { path: REF_TYPE_LIST_PATH, access: 'administrative', program: 'COTRTLIC' },
  { path: REF_TYPE_EDIT_PATH, access: 'administrative', program: 'COTRTUPC' },
];

/**
 * Wraps one element in the authentication guard.
 *
 * Assumptions: this wraps the LAYOUT route rather than each screen route, so it is applied once for
 * the whole authenticated subtree. It is kept as a helper because the administrative subtree below
 * needs the same treatment with a different guard, and because a route added outside the layout
 * would otherwise be one word away from being unguarded.
 * @param {ReactElement} screen - The element to protect.
 * @returns {ReactElement} The element wrapped in the sign-on guard.
 */
function guarded(screen: ReactElement): ReactElement {
  return <RequireSignOn>{screen}</RequireSignOn>;
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
 *       the catalogue constant under the name `./router.test.tsx` reads -- so the assertion and the
 *       rendered surface cannot disagree about the text.
 */

/** Heading the not-found result paints, re-published from the message catalogue. */
export const NOT_FOUND_TITLE = SCREEN_NOT_AVAILABLE_TITLE;

/** Supporting sentence the not-found result paints, re-published from the message catalogue. */
export const NOT_FOUND_SUBTITLE = SCREEN_NOT_AVAILABLE_DETAIL;

/**
 * Renders a bounded not-found result without reflecting the rejected path.
 *
 * Assumptions: the rejected address is deliberately NOT echoed. The catch-all is reachable by an
 * unauthenticated caller with any address, so interpolating the requested path into the rendered
 * document would put caller-chosen text on a page this application serves, and it would tell an
 * operator nothing they did not type.
 *
 * Trade-offs: the single control returns to the card browse rather than to the menu. The browse is
 * reachable by every operator, administrator or not, whereas offering the menu would mean choosing
 * between `/menu` and `/admin` from a claim this surface has deliberately not read -- and this
 * surface is reachable with no session at all, where neither menu can be rendered. The cost is one
 * extra hop for an operator who wanted the menu.
 * @returns {ReactElement} The not-found result.
 */
function NotFoundScreen(): ReactElement {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title={NOT_FOUND_TITLE}
      subTitle={NOT_FOUND_SUBTITLE}
      extra={
        <Button
          onClick={
            /** Returns the operator to the card browse from the not-found result. */
            () => {
              navigateSafely(navigate, CARD_LIST_PATH);
            }
          }
        >
          {OPEN_CARD_BROWSE_LABEL}
        </Button>
      }
    />
  );
}

/**
 * Renders the administrative subtree only for a caller holding the administrative group claim.
 *
 * Assumptions: a pathless layout element rather than the guard repeated on each administrative
 * route. The guard is then applied by WHERE a route is written, so an administrative screen added
 * to the subtree inherits it and one added outside is visibly outside -- which is the failure this
 * replaces, where the guard existed and no route used it.
 *
 * Refactoring Rationale: `RequireAdmin` reads the signed `carddemo-admin` group claim through
 * `ui/src/hooks/useAuth.ts` and nothing else. The reference decided this from `CDEMO-USER-TYPE`
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
 * @returns {ReactElement} The administrative subtree wrapped in the administrative guard.
 */
function AdminSubtree(): ReactElement {
  return (
    <RequireAdmin>
      <Outlet />
    </RequireAdmin>
  );
}

/**
 * Declares the browser routes backed by authored screen modules.
 *
 * Assumptions: the sign-on route is the only screen route outside the guard, because it is the
 * route that establishes the credential the others require. Guarding it would make the application
 * unreachable. It is nevertheless inside the FRAME, for the reason recorded at the layout route.
 *
 * Assumptions: the root redirect and the not-found result are the only two entries outside the
 * frame, and deliberately so. Both are router artifacts rather than migrated screens -- the
 * reference has no concept of either -- so framing them would wrap a redirect in a title band for
 * the one render before it leaves, and would report a transaction identifier for a screen that does
 * not exist. Neither shows a sign-off control in any case: the shell renders that only while a
 * session is held.
 * @returns {ReactElement} Browser router with opaque card selectors on every card detail path,
 *   every screen inside the one shared frame, every screen but sign-on additionally behind the
 *   authentication guard, and the administrative screens behind the administrative guard as well.
 */
export function CardDemoRouter(): ReactElement {
  return (
    <BrowserRouter>
      <Suspense fallback={<Spin size="large" />}>
        <Routes>
          {/*
           * WHY : Assumptions: the bare root redirects to the MAIN MENU because that is the
           *       reference's own entry sequence -- `app/cbl/COSGN00C.cbl` L230-L240 transfers to a
           *       MENU program and never to a browse -- so an operator who lands here sees the
           *       screen a sign-on would have taken them to. It resolves to `/menu` rather than
           *       choosing between the two menus, because choosing would mean reading a claim at a
           *       route that is reachable with no session; the guard on `/menu` then redirects an
           *       unauthenticated arrival to sign-on, and the sign-on screen performs the
           *       `'A'`-versus-`'U'` branch itself.
           * WHY : Assumptions: the redirect is `replace`, so the bare root does not become a
           *       history entry an operator can be bounced back to by the browser's own back
           *       control.
           */}
          <Route path={ROOT_PATH} element={<Navigate to={MAIN_MENU_ROUTE} replace />} />
          {/*
           * WHY : Refactoring Rationale: the frame is ONE layout route wrapping both the sign-on
           *       route and the guarded subtree, where sign-on used to sit outside it. Sign-on
           *       delegates its title band, its row-23 message and its row-24 legend through
           *       `useShellSlot` like every other screen, so with no frame above it the mapset's
           *       own `ERRMSG` field had no renderer: all three of the reference's refusal
           *       sentences -- `app/cbl/COSGN00C.cbl` L242-L256 -- were computed and discarded, and
           *       the `ENTER=Sign-on` legend the mapset paints was absent from the one screen every
           *       operator sees first. The alternative was to have this one screen compose its own
           *       three bands; it was rejected because it makes sign-on the single exception to the
           *       delegation contract every other screen follows, for a frame it would then paint
           *       slightly differently.
           * WHY : Assumptions: inside the FRAME is not inside the GUARD, and the distinction is
           *       what makes this safe. The guard is the pathless route nested below, so sign-on is
           *       framed and unguarded -- which it must be, since it is the route that establishes
           *       the credential every other route requires. The reference treats reaching sign-on
           *       as a deliberate session reset: `app/cbl/COMEN01C.cbl` L196-L203 defaults
           *       `CDEMO-TO-PROGRAM` to `'COSGN00C'` and transfers with NO communication area at
           *       all, discarding the identity and the selection context in one step. The shell
           *       renders its sign-off control only while a session is held, so an operator at
           *       sign-on is offered nothing they cannot do.
           */}
          <Route element={<AppShell />}>
            <Route path={SIGN_ON_ROUTE} element={<SignOnScreen />} />
            <Route element={guarded(<Outlet />)}>
              {/*
               * Assumptions: the two menu routes are the destinations sign-on transfers to, chosen
               * from the group claim rather than from a field the client supplied --
               * `app/cbl/COSGN00C.cbl` L230-L240 branches on the user-type byte, and
               * `ui/src/screens/signon/index.tsx` branches on the signed claim. The administrative
               * menu is declared in the nested subtree below instead of here, because it is one of
               * the screens the reference reaches only for an `'A'` operator.
               */}
              <Route path={MAIN_MENU_ROUTE} element={<MainMenuScreen />} />
              {/*
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
               */}
              <Route path={ACCOUNT_VIEW_PATH} element={<AccountViewScreen />} />
              <Route path={ACCOUNT_UPDATE_PATH} element={<AccountUpdateScreen />} />
              <Route path={CARD_LIST_PATH} element={<CardListScreen />} />
              <Route path={CARD_DETAIL_ROUTE} element={<CardDetailScreen />} />
              <Route path={CARD_EDIT_ROUTE} element={<CardUpdateScreen />} />
              {/*
               * WHY : Assumptions: the transaction routes sit in the guarded but NOT administrative
               *       subtree, because `app/cpy/COMEN02Y.cpy` gives main-menu options 6, 7 and 8
               *       the programs `COTRN00C`, `COTRN01C` and `COTRN02C` and the user type `'U'` --
               *       so gating any of them on the `carddemo-admin` claim would lock out exactly
               *       the operators the options exist for. The browse is a STATIC path carrying no
               *       parameter: the starting transaction identifier is entered on the screen
               *       itself, mirroring the mapset's own `TRNIDIN` search field rather than
               *       travelling in the address.
               * WHY : Assumptions: the DYNAMIC detail path coexists with the static
               *       `/transactions/new` above it and no ordering rule is needed. React Router
               *       ranks a static segment ahead of a dynamic one when it scores its routes, so
               *       `/transactions/new` continues to reach the capture screen and only some other
               *       second segment reaches the detail screen -- which is why the two are declared
               *       in reading order rather than in a precedence order.
               */}
              <Route path={TRANSACTION_LIST_PATH} element={<TransactionListScreen />} />
              <Route path={TRANSACTION_ADD_PATH} element={<TransactionAddScreen />} />
              <Route path={TRANSACTION_DETAIL_PATH} element={<TransactionDetailScreen />} />
              <Route path={BILL_PAY_PATH} element={<BillPayScreen />} />
              {/*
               * Assumptions: the report screen is mounted here, between the transaction capture and
               * the authorization summary, because that is its position in the reference's own
               * option table -- `app/cpy/COMEN02Y.cpy` lists Transaction Add as option 8,
               * Transaction Reports as option 9 and Pending Authorization View as option 11.
               * Ordering the table by the menu an operator reads keeps this file comparable with
               * the copybook it is derived from.
               */}
              <Route path={REPORTS_PATH} element={<ReportsScreen />} />
              {/*
               * Assumptions: the authorization detail path carries an OPAQUE key rather than the
               * composite the reference selects by. `ui/src/screens/authSummary/index.tsx` mints it
               * and `ui/src/api/authorization.ts` records why the account identifier, date and time
               * may not travel in a path, so this table only has to declare the parameter the
               * summary screen builds.
               */}
              <Route path={AUTH_SUMMARY_PATH} element={<AuthSummaryScreen />} />
              <Route path={AUTH_DETAIL_PATH} element={<AuthDetailScreen />} />
              {/*
               * WHY : Assumptions: SIX of the eight paths in this subtree are the six entries of
               *       `app/cpy/COADM02Y.cpy`, and nothing outside it is gated. That record
               *       (L56-L59) carries no user-type field at all, because every administrative
               *       option is administrative by construction -- unlike the main-menu record,
               *       whose every entry is explicitly `'U'`. The seventh and eighth paths are the
               *       administrative MENU itself and the second `COUSR02C` entry: the menu is gated
               *       because `app/cbl/COSGN00C.cbl` L230-L240 transfers to `COADM01C` only for an
               *       `'A'` operator, so leaving it ungated would show an ordinary operator the
               *       whole list of administrative options and let each refuse in turn, where the
               *       reference never puts that screen in front of them at all.
               */}
              <Route element={<AdminSubtree />}>
                <Route path={ADMIN_MENU_ROUTE} element={<AdminMenuScreen />} />
                {/*
                 * Assumptions: the user browse is declared INSIDE this administrative subtree, so
                 * it inherits the one `RequireAdmin` above rather than carrying a guard of its own.
                 * `app/cpy/COADM02Y.cpy` L26-L29 gives `COUSR00C` administrative option 1, so the
                 * browse is reachable only from the administrative menu -- exactly the set of
                 * screens this subtree exists for. Its screen reads no group claim, which keeps the
                 * authorization decision in one place.
                 */}
                <Route path={USER_LIST_PATH} element={<UserListScreen />} />
                {/*
                 * Assumptions: the add-user route is declared BEFORE the two maintenance routes and
                 * is a literal path, so it cannot be shadowed by them. `/users/new` and
                 * `/users/edit` are both selector-free literals and cannot collide, and the
                 * parameterised `/users/:id/edit` matches a deeper segment count, so react-router's
                 * ranking resolves all three unambiguously without an explicit order. The order
                 * below follows the administrative menu's own option sequence instead, which is the
                 * reading order `app/cpy/COADM02Y.cpy` gives the options.
                 */}
                <Route path={USER_ADD_PATH} element={<UserAddScreen />} />
                <Route path={USER_UPDATE_PATH} element={<UserUpdateScreen />} />
                <Route path={USER_UPDATE_SELECTED_PATH} element={<UserUpdateScreen />} />
                {/*
                 * WHY : Assumptions: the deletion route sits INSIDE the administrative subtree, so
                 *       the `carddemo-admin` claim gates it exactly as it gates the other user
                 *       routes. `app/csd/CARDDEMO.CSD` reaches `COUSR03C` only from the
                 *       administrative menu, and `app/cpy/COADM02Y.cpy` L41-L44 makes it
                 *       administrative option 4, so a deletion route outside this subtree would be
                 *       the one destructive operation in the table an ordinary operator could
                 *       reach. The screen itself deliberately carries no gate of its own -- this is
                 *       the single place the policy is stated.
                 */}
                <Route path={USER_DELETE_PATH} element={<UserDeleteScreen />} />
                <Route path={REF_TYPE_LIST_PATH} element={<RefTypeListScreen />} />
                <Route path={REF_TYPE_EDIT_PATH} element={<RefTypeEditScreen />} />
              </Route>
            </Route>
          </Route>
          {/*
           * Assumptions: the not-found result stays OUTSIDE the frame, and so does the root
           * redirect above it. Both are router artifacts rather than migrated screens -- the
           * reference has no concept of an unmatched address -- so framing them would wrap a
           * redirect in a title band for the one render before it leaves, and would report a
           * transaction identifier for a screen that does not exist. The nearest reference
           * behaviour is `app/cbl/COMEN01C.cbl` L127-L134, which refuses an out-of-range option
           * number on the menu it was typed into; there is no menu to return an unmatched URL to,
           * so the refusal becomes a surface of its own.
           */}
          <Route path={NOT_FOUND_PATH} element={<NotFoundScreen />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
