/**
 * @file The route table: the client-side replacement for the CICS program-transfer graph, the one
 * place the authentication boundary between screens is drawn, and the one place the application
 * frame is mounted.
 *
 * Purpose
 * -------
 * Declare every browser route backed by an authored screen module, mount the shared frame those
 * screens are painted inside, and decide for each route whether it sits outside the sign-on guard,
 * inside it, or additionally inside the administrative guard. This is where `EXEC CICS XCTL` ends
 * up: the reference programs transfer control by naming a program in the communication area, and the
 * migrated equivalent is a route change, so the reachability graph of the online transactions is
 * expressed here rather than in any screen.
 *
 * Ownership of navigation
 * -----------------------
 * Assumptions: this module owns the route-to-screen MAPPING and nothing else. It performs no
 * navigation itself -- a screen navigates through `ui/src/routes/navigation.ts`, which validates
 * the destination -- and it holds no route CONSTANT for a card path; those live in
 * `ui/src/routes/cards.ts` beside the selector guards that validate them. Splitting it that way is
 * what lets a screen import a path builder without importing the router.
 *
 * Authentication boundary
 * -----------------------
 * Assumptions: exactly one screen route is outside the guard, and it is sign-on, because sign-on is
 * the route that establishes the credential the others require. Every other screen route is a child
 * of the guarded layout route below, so the guard is applied once, structurally, rather than being
 * repeated per route -- and a route added later inherits it by where it is written. The guard decides
 * what is RENDERED only; every service independently validates the token, so an unguarded route
 * would expose a screen and no data.
 *
 * Two nested guards, not one
 * --------------------------
 * Refactoring Rationale: the administrative routes are nested inside a SECOND guard, and previously
 * they were not guarded at all -- `RequireAdmin` was authored, documented as the migrated form of the
 * reference's user-type branch, and imported by nothing, so every administrative screen was reachable
 * by any signed-on operator. The reference gates these screens on the byte it carried in the
 * communication area: `app/cbl/COSGN00C.cbl` L245 transfers an `'A'` user to `COADM01C` and everyone
 * else to `COMEN01C`, and `app/cbl/COMEN01C.cbl` L136-L143 refuses an administrator-only option to a
 * non-administrator outright. Leaving the guard uncalled meant a non-administrator reaching user
 * maintenance and reference maintenance saw the screens and met refusals only from the services --
 * which is a screen full of failures rather than the refusal the reference gave.
 *
 * The frame is mounted here
 * -------------------------
 * Refactoring Rationale: `ui/src/layout/AppShell.tsx` is mounted as the guarded LAYOUT route, and
 * previously it was mounted nowhere. Two authored screens -- `./screens/accountView` and
 * `./screens/authSummary` -- omit the shared title band on the stated ground that the shell paints
 * it, so with no shell mounted their row-1 and row-2 fields rendered nowhere at all: the transaction
 * identifier, the two application titles, the program name and the paint date and time were simply
 * absent from those two screens. The shell renders each of its zones only when a screen has delegated
 * one, so mounting it changes nothing for the screens that compose their own bands.
 */

import { Button, Result, Spin } from 'antd';
import { lazy, Suspense } from 'react';
import type { ReactElement } from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useNavigate } from 'react-router';

import { AppShell } from './layout/AppShell';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { RequireAdmin, RequireSignOn, SIGN_ON_ROUTE } from './routes/guards';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE, navigateSafely } from './routes/navigation';
import { SignOnScreen } from './screens/signon';

/*
 * Trade-offs: every guarded screen is loaded LAZILY and sign-on is not, and the split is the decision
 * rather than the mechanism. Eager imports would be simpler -- no `lazy`, no `Suspense`, no
 * default-export adapter below -- and would put all fourteen guarded screens plus their antd
 * dependencies into the single chunk the browser must fetch before the sign-on form appears.
 * Sign-on is the only screen an unauthenticated operator can reach, and the guarded screens pull the
 * heaviest components in the tree (`Table`, `Descriptions`, `Form`, `Select` and `Popconfirm`, with
 * account update alone painting 43 data controls), so an eager bundle makes every operator pay for the
 * browse, detail, card-update, account-update, transaction-capture, menu, authorization and reference
 * screens in order to see a login form. The cost accepted is real and is paid at a different moment:
 * the FIRST navigation to each guarded route fetches its chunk, so that transition shows the
 * `Suspense` spinner where an eager build would have shown the screen immediately, and a chunk that
 * fails to fetch fails at navigation time rather than at start-up. Assumptions: sign-on is
 * deliberately eager for the same reason -- it is the entry screen, so deferring it would move the
 * spinner to the one place an operator has nothing to look at yet.
 *
 * Assumptions: the shell itself is NOT lazy. It is the frame every guarded route renders inside, so
 * deferring it would add a second chunk fetch to the first guarded navigation and would show the
 * spinner in place of the frame rather than in place of a screen.
 */

/*
 * Assumptions: the declarations below and the route table further down are both ordered by MAIN-MENU
 * OPTION NUMBER rather than alphabetically, because `app/cpy/COMEN02Y.cpy` is the reachability source
 * and it numbers `Account Update` as option 2 (`COACTUPC`, L31-L35) ahead of the three card options 3
 * to 5. Keeping the file in that order means a reader comparing this table against the option
 * copybook walks both in the same direction, and a missing screen shows up as a gap in the sequence
 * instead of having to be searched for. The two menu screens are declared first because they are what
 * sign-on transfers to, and the administrative screens last because they form their own nested
 * subtree.
 */

/*
 * WHY : Trade-offs: two forms of lazy declaration appear below and the difference is not arbitrary. A
 *       screen that publishes a `default` export is loaded with a bare `lazy(() => import(...))`,
 *       which is the whole declaration; a screen that publishes only a NAMED export needs the adapter
 *       shape, because republishing the named export under the `default` key is the only shape
 *       `React.lazy` accepts. Normalising every screen onto one form was considered and rejected in
 *       both directions: adding a default export to the five adapter-shaped screens would edit five
 *       modules to change nothing an operator can observe, and forcing an adapter onto the screens
 *       that already publish a default would add nine wrappers that exist only for symmetry.
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
   * Imports the account view screen chunk, which publishes its screen as the module's default export.
   *
   * ⚠️ Assumptions: the module's NAMED export is republished under the `default` key, which is the shape
   * React.lazy accepts. This screen publishes no default export -- three of the fourteen screen modules
   * publish only a named one -- so a direct `import()` typed the whole module namespace as the loaded
   * component and was rejected by the compiler.
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
   * Imports the account maintenance chunk and republishes its named export under
   * the `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/accountUpdate');
    return { default: module.AccountUpdateScreen };
  },
);

/** Loads the browse screen only when a card route needs it. */
const CardListScreen = lazy(
  /**
   * Imports the browse screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/cardList');
    return { default: module.CardListScreen };
  },
);

/** Loads the detail screen only when a card route needs it. */
const CardDetailScreen = lazy(
  /**
   * Imports the detail screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/cardDetail');
    return { default: module.CardDetailScreen };
  },
);

/** Loads the update screen only when a card route needs it. */
const CardUpdateScreen = lazy(
  /**
   * Imports the update screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/cardUpdate');
    return { default: module.CardUpdateScreen };
  },
);

/*
 * WHY : Refactoring Rationale: this route was MISSING, and the gap was not visible from either side. The
 *       screen module `./screens/transactionAdd` was authored against a contract that states this file
 *       mounts it at `/transactions/new`, and this file declared no transaction route at all -- so the
 *       screen compiled, linted and type-checked while being unreachable, and `/transactions/new`
 *       resolved to the not-found result below. Nothing failed to say so, because a route table cannot
 *       assert what is absent from it. AAP section 0.5.1.10 assigns the whole program-transfer graph to
 *       this module and the acceptance criteria require the transaction-add flow to work end to end,
 *       which an unmounted screen cannot do.
 * WHY : Assumptions: it is loaded LAZILY and behind the guard, for the same two reasons the card screens
 *       are. It pulls `Form`, `Input` and `Popconfirm`, so an eager import would put the heaviest capture
 *       screen in the chunk an unauthenticated operator fetches to see a login form; and every service it
 *       calls validates the token independently, so an unguarded route would render a form whose every
 *       submission was refused.
 */

/** Loads the transaction capture screen only when its route needs it. */
const TransactionAddScreen = lazy(
  /**
   * Imports the capture screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/transactionAdd');
    return { default: module.TransactionAddScreen };
  },
);

/** Loads the pending-authorization summary only when its route needs it. */
const AuthSummaryScreen = lazy(
  /**
   * Imports the pending-authorization summary chunk, which publishes its screen as the module's default export.
   *
   * ⚠️ Assumptions: the module's NAMED export is republished under the `default` key, which is the shape
   * React.lazy accepts. This screen publishes no default export -- three of the fourteen screen modules
   * publish only a named one -- so a direct `import()` typed the whole module namespace as the loaded
   * component and was rejected by the compiler.
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
   * Imports the pending-authorization detail chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/authDetail'),
);

/** Loads the administrative menu only when its route needs it. */
const AdminMenuScreen = lazy(
  /**
   * Imports the administrative menu chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/admin'),
);

/** Loads the user maintenance screen only when its route needs it. */
const UserUpdateScreen = lazy(
  /**
   * Imports the user maintenance screen chunk, which publishes its screen as the module's default export.
   *
   * ⚠️ Assumptions: the module's NAMED export is republished under the `default` key, which is the shape
   * React.lazy accepts. This screen publishes no default export -- three of the fourteen screen modules
   * publish only a named one -- so a direct `import()` typed the whole module namespace as the loaded
   * component and was rejected by the compiler.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, adapted to the
   *   default-export shape.
   */
  async () => {
    const module = await import('./screens/userUpdate');
    return { default: module.UserUpdateScreen };
  },
);

/** Loads the transaction-type list only when its route needs it. */
const RefTypeListScreen = lazy(
  /**
   * Imports the transaction-type list chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/refTypeList'),
);

/** Loads the transaction-type maintenance screen only when its route needs it. */
const RefTypeEditScreen = lazy(
  /**
   * Imports the transaction-type maintenance screen chunk, which publishes its screen as the module's default export.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module, already in the
   *   default-export shape React.lazy accepts.
   */
  () => import('./screens/refTypeEdit'),
);

/*
 * WHY : Assumptions: every path below that carries no opaque selector is written as a LITERAL here
 *       rather than imported from the screen that serves it, and the reason is the lazy split above
 *       rather than a preference. A screen module reached through `lazy` is in its own chunk; importing
 *       a constant from it at module scope would pull that whole module -- and its antd dependencies --
 *       back into the entry chunk, silently undoing the split for every screen so referenced. The card
 *       routes are imported because `./routes/cards` is a small selector module that exists for exactly
 *       that purpose, and the menu and sign-on routes because `./routes/navigation` and
 *       `./routes/guards` are likewise not screens. The screens that publish their own route constants
 *       are kept honest by `./routerRoutes.test.tsx`, which imports both this table's literals and
 *       those constants and fails if they disagree -- so the duplication cannot drift without a test
 *       saying so, and it costs the entry chunk nothing.
 */

/** Route the account view screen occupies, which main-menu option 1 transfers to. */
export const ACCOUNT_VIEW_PATH = '/account/view';

/** Route the account maintenance screen occupies, which main-menu option 2 transfers to. */
export const ACCOUNT_UPDATE_PATH = '/account/update';

/** Route the card browse screen occupies, which main-menu option 3 transfers to. */
export const CARD_LIST_PATH = '/cards';

/** Route the transaction capture screen occupies, which main-menu option 8 transfers to. */
export const TRANSACTION_ADD_PATH = '/transactions/new';

/** Route the pending-authorization summary occupies, which main-menu option 11 transfers to. */
export const AUTH_SUMMARY_PATH = '/authorizations';

/** Route the pending-authorization detail occupies, selected from the summary grid. */
export const AUTH_DETAIL_PATH = '/authorizations/:key';

/**
 * Route the user maintenance screen occupies when no operator has been selected yet.
 *
 * Assumptions: it is mounted at BOTH this selector-free path and the parameterised one below, because
 * the screen serves both entries. `ui/src/screens/admin/index.tsx` sends administrative option 3 here
 * with no identifier -- matching `COUSR02C`, whose first turn is an empty screen the operator types a
 * user identifier into -- while the user list reaches the same screen with one already chosen. The
 * screen's own mount effect reads nothing when the route carries no identifier, which is what lets one
 * module serve both without a second component.
 */
export const USER_UPDATE_PATH = '/users/edit';

/** Route the user maintenance screen occupies when an operator has already been selected. */
export const USER_UPDATE_SELECTED_PATH = '/users/:id/edit';

/** Route the transaction-type list occupies, which administrative option 5 transfers to. */
export const REF_TYPE_LIST_PATH = '/reference/transaction-types';

/**
 * Route the transaction-type maintenance screen occupies.
 *
 * Assumptions: ONE dynamic route serves both the add and the change entries, and the add entry is the
 * reserved `new` segment its screen recognises. A second, static `/reference/transaction-types/new`
 * route was considered -- react-router matches a static segment ahead of a dynamic one, so both would
 * resolve -- and rejected because the screen already has to recognise the sentinel (the list screen's
 * PF2 navigates to it, and `ui/src/screens/refTypeEdit/index.tsx` records why a two-character key can
 * never collide with a three-character word), so a second route would express the same decision twice
 * and let the two spellings drift.
 */
export const REF_TYPE_EDIT_PATH = '/reference/transaction-types/:typeCd';

/**
 * Wraps one element in the authentication guard.
 *
 * Assumptions: this now wraps the LAYOUT route rather than each screen route, so it is applied once
 * for the whole authenticated subtree. It is kept as a helper because the administrative subtree below
 * needs the same treatment with a different guard, and because a route added outside the layout would
 * otherwise be one word away from being unguarded.
 * @param {ReactElement} screen - The element to protect.
 * @returns {ReactElement} The element wrapped in the sign-on guard.
 */
function guarded(screen: ReactElement): ReactElement {
  return <RequireSignOn>{screen}</RequireSignOn>;
}

/**
 * Heading the not-found result paints.
 *
 * Assumptions: exported so the case asserting the catch-all reads the same string the component paints
 * rather than a copy of it. It is an ADDITIVE string with no baseline counterpart -- the reference has
 * no analogue of an unmatched address -- so it is declared here beside its one use rather than in
 * `ui/src/messages/messages.ts`, which carries transcribed message constants only.
 */
export const NOT_FOUND_TITLE = 'Screen not found';

/** Supporting sentence the not-found result paints, additive for the same reason as the heading. */
export const NOT_FOUND_SUBTITLE = 'The requested CardDemo screen is not available.';

/**
 * Renders a bounded not-found result without reflecting the rejected path.
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
            /** Returns the operator to the browse screen from the not-found result. */
            () => {
              navigateSafely(navigate, CARD_LIST_PATH);
            }
          }
        >
          Open cards
        </Button>
      }
    />
  );
}

/**
 * Renders the administrative subtree only for a caller holding the administrative group claim.
 *
 * Assumptions: a pathless layout element rather than the guard repeated on each administrative route.
 * The guard is then applied by WHERE a route is written, so an administrative screen added to the
 * subtree inherits it and one added outside is visibly outside -- which is the failure this replaces,
 * where the guard existed and no route used it.
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
 * Assumptions: the sign-on route is the only screen route outside the guard, because it is the route
 * that establishes the credential the others require. Guarding it would make the application
 * unreachable. It is nevertheless inside the FRAME, for the reason recorded at the layout route.
 *
 * Assumptions: the root redirect and the not-found result are the only two entries outside the frame,
 * and deliberately so. Both are router artifacts rather than migrated screens -- the reference has no
 * concept of either -- so framing them would wrap a redirect in a title band for the one render before
 * it leaves, and would report a transaction identifier for a screen that does not exist. Neither shows
 * a sign-off control in any case: the shell renders that only while a session is held.
 * @returns {ReactElement} Browser router with opaque card selectors on every card detail path, every
 *   screen inside the one shared frame, every screen but sign-on additionally behind the authentication
 *   guard, and the administrative screens behind the administrative guard as well.
 */
export function CardDemoRouter(): ReactElement {
  return (
    <BrowserRouter>
      <Suspense fallback={<Spin size="large" />}>
        <Routes>
          {/*
           * WHY : ⚠️ Refactoring Rationale: the bare root redirects to the MAIN MENU where it redirected
           *       to the card browse. The browse was the nearest thing to a landing screen while no menu
           *       existed; now that one does, the reference's own entry sequence is what the root
           *       reproduces -- `app/cbl/COSGN00C.cbl` L245 transfers to a MENU program and never to a
           *       browse -- and an operator who lands there sees the same screen a sign-on would have
           *       taken them to. Two independent reviews reached this remedy; the redirect stays OUTSIDE
           *       the frame below for the reason recorded there.
           * WHY : Assumptions: the redirect is `replace`, so the bare root does not become a history entry
           *       an operator can be bounced back to by the browser's own back control.
           */}
          <Route path="/" element={<Navigate to={MAIN_MENU_ROUTE} replace />} />
          {/*
           * WHY : ⚠️ Refactoring Rationale: the frame is ONE layout route wrapping both the sign-on route
           *       and the guarded subtree, where sign-on used to sit outside it. Sign-on delegates its
           *       title band, its row-23 message and its row-24 legend through `useShellSlot` like every
           *       other screen, so with no frame above it the mapset's own `ERRMSG` field had no
           *       renderer: all three of the reference's refusal sentences -- `app/cbl/COSGN00C.cbl`
           *       L211-L256 -- were computed and discarded, and the `ENTER=Sign-on` legend the mapset
           *       paints was absent from the one screen every operator sees first. The alternative was to
           *       have this one screen compose its own three bands; it was rejected because it makes
           *       sign-on the single exception to the delegation contract two gates now assert of all ten
           *       screens, for a frame it would then paint slightly differently.
           * WHY : Assumptions: inside the FRAME is not inside the GUARD, and the distinction is what
           *       makes this safe. The guard is the pathless route nested below, so sign-on is framed and
           *       unguarded -- which it must be, since it is the route that establishes the credential
           *       every other route requires. The shell renders its sign-off control only while a session
           *       is held, so an operator at sign-on is offered nothing they cannot do.
           */}
          <Route element={<AppShell />}>
            <Route path={SIGN_ON_ROUTE} element={<SignOnScreen />} />
            <Route element={guarded(<Outlet />)}>
              {/*
               * Assumptions: the two menu routes are the destinations sign-on transfers to, chosen from
               * the group claim rather than from a field the client supplied -- `app/cbl/COSGN00C.cbl`
               * L245 branches on the user-type byte, and `ui/src/screens/signon/index.tsx` branches on
               * the signed claim. The administrative menu is declared in the nested subtree below
               * instead of here, because it is one of the screens the reference reaches only for an
               * `'A'` operator.
               */}
              <Route path={MAIN_MENU_ROUTE} element={<MainMenuScreen />} />
              {/*
               * Assumptions: `/account/view` and `/account/update` are declared as STATIC paths with no
               * parameter and with no admin guard. The account identifier is entered on the screen
               * itself rather than carried in the URL, mirroring `COACTVWC` and `COACTUPC`, whose only
               * unprotected control on first entry is the `ACCTSID` search key; and
               * `app/cpy/COMEN02Y.cpy` L31-L35 gives options 1 and 2 the user type `'U'`, so gating
               * them on the `carddemo-admin` claim would lock out exactly the operators the options
               * exist for. The card routes need their own selector module because they carry an opaque
               * identifier in the path, and these routes need no such helper because they carry
               * nothing. `ui/nginx.conf` already names these paths in its SPA history fallback, so the
               * served application and this table agree.
               */}
              <Route path={ACCOUNT_VIEW_PATH} element={<AccountViewScreen />} />
              <Route path={ACCOUNT_UPDATE_PATH} element={<AccountUpdateScreen />} />
              <Route path={CARD_LIST_PATH} element={<CardListScreen />} />
              <Route path={CARD_DETAIL_ROUTE} element={<CardDetailScreen />} />
              <Route path={CARD_EDIT_ROUTE} element={<CardUpdateScreen />} />
              <Route path={TRANSACTION_ADD_PATH} element={<TransactionAddScreen />} />
              {/*
               * Assumptions: the authorization detail path carries an OPAQUE key rather than the
               * composite the reference selects by. `ui/src/screens/authSummary/index.tsx` mints it and
               * `ui/src/api/authorization.ts` records why the account identifier, date and time may not
               * travel in a path, so this table only has to declare the parameter the summary screen
               * builds.
               */}
              <Route path={AUTH_SUMMARY_PATH} element={<AuthSummaryScreen />} />
              <Route path={AUTH_DETAIL_PATH} element={<AuthDetailScreen />} />
              <Route element={<AdminSubtree />}>
                <Route path={ADMIN_MENU_ROUTE} element={<AdminMenuScreen />} />
                <Route path={USER_UPDATE_PATH} element={<UserUpdateScreen />} />
                <Route path={USER_UPDATE_SELECTED_PATH} element={<UserUpdateScreen />} />
                <Route path={REF_TYPE_LIST_PATH} element={<RefTypeListScreen />} />
                <Route path={REF_TYPE_EDIT_PATH} element={<RefTypeEditScreen />} />
              </Route>
            </Route>
          </Route>
          {/*
           * Assumptions: the not-found result stays OUTSIDE the frame, and so does the root redirect
           * above it. Both are router artifacts rather than migrated screens -- the reference has no
           * concept of either -- so framing them would wrap a redirect in a title band for the one
           * render before it leaves, and would report a transaction identifier for a screen that does
           * not exist.
           */}
          <Route path="*" element={<NotFoundScreen />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
