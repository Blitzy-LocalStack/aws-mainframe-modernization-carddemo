/**
 * @file Route constants and the one validated navigation helper every screen transitions through.
 *
 * Purpose
 * -------
 * Hold the destination constants that more than one screen needs -- the main and administrative menu
 * routes the reference programs transfer to -- and wrap the router's navigate function so that a
 * transition is checked before it is performed. This is where `EXEC CICS XCTL`'s destination naming
 * ends up: the reference names a program, a screen here names a route, and both are looked up rather
 * than composed at the call site.
 *
 * Why a helper rather than calling navigate directly
 * -------------------------------------------------
 * Assumptions: a destination is validated, so a route that does not exist becomes the router's
 * bounded not-found result rather than an unhandled state. The helper earns its place even now that
 * every constant below names a mounted screen, because a transition is composed at a call site and a
 * path that stops matching -- a renamed segment, a route moved under a different guard -- is otherwise
 * indistinguishable from one that never existed.
 *
 * Refactoring Rationale: this section previously said that two of the constants below named screens
 * that were not authored yet, and that the resulting not-found result was what made the check
 * load-bearing. Both screens are now authored and `ui/src/router.tsx` mounts both routes, so the
 * sentence described a state the tree had left -- and it was the reason the check LOOKED provisional.
 * It is restated as what the check is actually for, which does not depend on a screen being absent.
 */

import type { NavigateFunction, To } from 'react-router';

/**
 * The values one screen may hand the screen it transitions to.
 *
 * Refactoring Rationale: this exists because two things the reference carries in the
 * shared communication area have no other carrier in a stateless target, and the
 * obvious substitutes are both wrong. `app/cpy/COCOM01Y.cpy` L19-L44 hands the next
 * program a message field and a selected account, and a screen that transitions
 * without them either drops the message the reference emits or puts the account
 * identifier in the URL. A query member was the first shape used for the identifier
 * and is withdrawn: a query string is request-target data, so it reaches browser
 * history and the load balancer's access log, which is the one place this migration
 * refuses to let a cardholder identifier land. Router state travels in the history
 * entry's state object instead, so it is readable by the destination and appears in no
 * request line.
 *
 * Assumptions: every member is optional and none is a substitute for identity or
 * authority. A destination reads these values as a HINT it may ignore - the message it
 * displays, the identifier it pre-fills - and every request it then makes carries the
 * signed token and its own body, so a hand-edited history entry can pre-fill a field
 * and change nothing else.
 */
export interface ScreenTransitionState {
  /**
   * Verbatim baseline sentence the departing screen hands the destination's message
   * band, or absent when it hands none.
   */
  readonly message?: string;
  /**
   * Account identifier the destination may pre-fill its account filter with, as
   * digits, or absent when the transition carries no selection.
   */
  readonly accountId?: string;
  /**
   * Route the departing screen occupies, which the destination's exit key returns to,
   * or absent when the transition names no origin.
   *
   * Refactoring Rationale: this is the carrier for `CDEMO-FROM-TRANID` and
   * `CDEMO-FROM-PROGRAM` (`app/cpy/COCOM01Y.cpy` L23-L26), which several programs prefer
   * over their own hard-coded menu destination on the PF3 arm -
   * `app/cbl/COACTVWC.cbl` L328-L339 is the clearest case. A screen with no carrier for it
   * had to take the fallback arm unconditionally, which is a real behaviour loss: an
   * operator who reached the screen from somewhere other than the menu was returned to the
   * menu anyway. The browser's own history is NOT that carrier - a history entry is not a
   * named origin and need not even belong to this application - which is why the origin is
   * handed over explicitly and validated against the route table by
   * {@link inApplicationRoute}.
   */
  readonly from?: string;
}

/**
 * Route the main menu occupies, which is where the source application's PF3 returns to.
 *
 * Assumptions: the constant is declared here, in the module every screen already imports its
 * transitions from, rather than being written as a literal at each call site. Four screens need it --
 * sign-on routes here once a token is installed, the card browse exits here matching
 * `app/cbl/COCRDLIC.cbl` L390-L399 where PF3 transfers to `LIT-MENUPGM`, the authorization summary
 * exits here matching `COPAUS0C.cbl` L235-L238, and the router's not-found surface offers it as the way
 * back -- and a literal repeated in four modules is four places for the path to drift apart.
 *
 * Assumptions: the route is mounted. `ui/src/router.tsx` registers it inside the authenticated layout
 * route and `ui/src/screens/menu/index.tsx` is the screen behind it, so a transition here reaches the
 * migrated `COMEN01C` rather than a not-found result. Naming the source's real destination was correct
 * while the screen was absent too -- sending PF3 to a screen the source does not return to would have
 * invented a destination -- and `ui/src/routerRoutes.test.tsx` now holds the two in agreement.
 */
export const MAIN_MENU_ROUTE = '/menu';

/**
 * Route the administrative menu occupies, reached when the group claim carries the admin group.
 *
 * Assumptions: the claim decides who ARRIVES here and the route itself is additionally guarded, which
 * is two mechanisms rather than a duplicated one. Sign-on chooses between this route and the main menu
 * from the signed claim, and `ui/src/router.tsx` nests this route and every screen it leads to inside
 * `RequireAdmin` -- so an operator who reaches the path another way still meets the refusal instead of
 * the menu.
 */
export const ADMIN_MENU_ROUTE = '/admin';

/*
 * Refactoring Rationale: the five constants below were path LITERALS written once in
 * `ui/src/router.tsx` and nowhere else, which was correct while the router was the only
 * module that named a destination. The main-menu screen changed that: it dispatches the
 * eleven options of `app/cpy/COMEN02Y.cpy` to the screens that replace their programs,
 * so a second module now needs the same paths, and two copies of a path are two places
 * for it to drift. Declaring them here - the module that already owns the destinations
 * more than one screen needs - keeps the router and the menu naming ONE value, and it
 * is why this file and not the router is where a new destination goes.
 * Alternatives Considered: exporting them from `ui/src/router.tsx`. Rejected because a
 * screen importing the router imports every lazily-loaded screen chunk's adapter with
 * it, which defeats the code splitting the router exists to arrange, and because it
 * would make a screen depend on the table that mounts it.
 */

/** Route the account-view screen occupies, replacing program `COACTVWC`. */
export const ACCOUNT_VIEW_ROUTE = '/account/view';

/** Route the account-update screen occupies, replacing program `COACTUPC`. */
export const ACCOUNT_UPDATE_ROUTE = '/account/update';

/** Route the card browse screen occupies, replacing program `COCRDLIC`. */
export const CARD_LIST_ROUTE = '/cards';

/** Route the transaction-capture screen occupies, replacing program `COTRN02C`. */
export const TRANSACTION_ADD_ROUTE = '/transactions/new';

/** Route the pending-authorization summary occupies, replacing program `COPAUS0C`. */
export const AUTHORIZATION_SUMMARY_ROUTE = '/authorizations';

/** Route the transaction-type maintenance list occupies, replacing program `COTRTLIC`. */
export const REFERENCE_TYPE_LIST_ROUTE = '/reference/transaction-types';

/**
 * Route template the user-update screen occupies, replacing program `COUSR02C`.
 *
 * Assumptions: this is a TEMPLATE carrying the router's `:id` parameter and is not a
 * navigable destination. The screen reads the identifier from the route, so a caller
 * transitioning there builds the concrete path from the user it selected; the constant
 * exists so the router and any future selector screen agree on the shape.
 */
export const USER_UPDATE_ROUTE_TEMPLATE = '/users/:id/edit';

/*
 * Assumptions: the census below is a CLOSED set of admissible origins and deliberately
 * excludes four paths. The user-update template carries a route parameter, so it is not a
 * path a caller can hand over unresolved; the two card detail routes are minted by
 * `ui/src/routes/cards.ts` from an opaque selector, so a caller holding one already holds a
 * concrete path this set could not enumerate; and sign-on is excluded because it is never an
 * origin - `app/cbl/COSGN00C.cbl` L245 transfers to a MENU and to nothing else, so no screen
 * with a PF3 arm is ever entered from it. Excluding sign-on additionally keeps this module
 * free of `ui/src/routes/guards.tsx`, which owns that path: this file is imported by every
 * screen for its constants alone, and importing the guard module would pull the component
 * layer in behind it.
 */

/**
 * Every parameterless application route a screen may name as its origin.
 * @returns {readonly string[]} The routes {@link inApplicationRoute} admits.
 */
function navigableRoutes(): readonly string[] {
  return [
    MAIN_MENU_ROUTE,
    ADMIN_MENU_ROUTE,
    ACCOUNT_VIEW_ROUTE,
    ACCOUNT_UPDATE_ROUTE,
    CARD_LIST_ROUTE,
    TRANSACTION_ADD_ROUTE,
    AUTHORIZATION_SUMMARY_ROUTE,
    REFERENCE_TYPE_LIST_ROUTE,
  ];
}

/**
 * Resolves a claimed origin to an application route, or to nothing.
 *
 * Assumptions: the claim is matched against a declared set rather than merely tested for a
 * leading slash, and the difference is the whole point of the check. Router state is
 * attacker-writable through a hand-edited history entry, and a path-shaped value that is
 * not one of this application's own routes would either reach the not-found result or, if
 * it were protocol-relative, leave the application entirely on a key press the operator
 * believes goes back one screen. Matching a closed set makes both impossible.
 * @param {string | undefined} claimed - The origin the transition state carried, if any.
 * @returns {string | undefined} The claimed route when this application serves it, and
 *   `undefined` for anything else, which leaves a caller to use its own default.
 */
export function inApplicationRoute(claimed: string | undefined): string | undefined {
  return claimed !== undefined && navigableRoutes().includes(claimed) ? claimed : undefined;
}

/**
 * Reads the transition state a destination screen was entered with.
 *
 * Assumptions: the value is validated structurally rather than cast, because
 * `useLocation().state` is whatever the previous entry put there - including a value a
 * hand-edited history entry supplied - so a cast would let a non-string reach a message
 * band or a form control. A member of the wrong type is dropped rather than coerced:
 * dropping it leaves the destination in its own first-entry state, which is a state it
 * already renders correctly, while coercing would display `[object Object]` as though
 * the reference had emitted it.
 * @param {unknown} state - The `state` member of the destination's location.
 * @returns {ScreenTransitionState} The members that are present and well-typed; empty
 *   when the screen was entered directly, by a full document navigation, or with a
 *   value of any other shape.
 */
export function screenTransitionState(state: unknown): ScreenTransitionState {
  if (typeof state !== 'object' || state === null) {
    return {};
  }

  const { message, accountId, from } = state as {
    readonly message?: unknown;
    readonly accountId?: unknown;
    readonly from?: unknown;
  };

  return {
    ...(typeof message === 'string' ? { message } : {}),
    ...(typeof accountId === 'string' ? { accountId } : {}),
    ...(typeof from === 'string' ? { from } : {}),
  };
}

/**
 * Performs a router transition and falls back to a full navigation if a data
 * router rejects the transition.
 *
 * Trade-offs: the fallback path drops `state`, because a full document navigation
 * starts a new history entry with none. That is accepted rather than worked around -
 * the values carried are a transient message and a pre-fill hint, so the destination
 * renders its own first-entry state instead, which is correct if less informative.
 * Encoding them into the URL to survive the fallback is the alternative and is exactly
 * what {@link ScreenTransitionState} exists to avoid.
 * @param {NavigateFunction} navigate - Router navigation function.
 * @param {To} destination - Safe application destination.
 * @param {ScreenTransitionState} [state] - Values the destination may read on arrival;
 *   omitted for a transition that hands over nothing.
 */
export function navigateSafely(
  navigate: NavigateFunction,
  destination: To,
  state?: ScreenTransitionState,
): void {
  const transition = state === undefined ? navigate(destination) : navigate(destination, { state });
  if (transition instanceof Promise) {
    transition.catch(
      /**
       * Completes the transition with a full document navigation when the data
       * router rejects it, so the operator still reaches the destination.
       */
      () => {
        if (typeof destination === 'string') {
          window.location.assign(destination);
        } else {
          window.location.assign(destination.pathname ?? '/');
        }
      },
    );
  }
}

// Refactoring Rationale: eight controls across the three card screens carried the
// same inline `() => navigateSafely(navigate, X)` closure. Each was a documentable
// function under ui/eslint.config.js, so the alternative to this factory was eight
// copies of one sentence -- and eight places for the transition semantics to drift
// apart. Naming the handler once keeps the fallback behaviour above in a single
// documented seam and leaves the call sites reading as intent rather than mechanics.
/**
 * Builds an event handler that performs one router transition when invoked.
 * @param {NavigateFunction} navigate - Router navigation function.
 * @param {To} destination - Safe application destination.
 * @returns {() => void} A handler suitable for an Ant Design control's `onClick`.
 */
export function navigationHandler(navigate: NavigateFunction, destination: To): () => void {
  return (
    /** Performs the transition, falling back to a full navigation if it is rejected. */
    () => {
      navigateSafely(navigate, destination);
    }
  );
}
