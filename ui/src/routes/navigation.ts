import type { NavigateFunction, To } from 'react-router';

/**
 * Route the main menu occupies, which is where the source application's PF3 returns to.
 *
 * Assumptions: the constant is declared here, in the module every screen already imports its
 * transitions from, rather than being written as a literal at each call site. Two screens need it --
 * sign-on routes here once a token is installed, and the card browse exits here, matching
 * `app/cbl/COCRDLIC.cbl` L390-L399 where PF3 transfers to `LIT-MENUPGM` -- and a literal repeated in
 * two modules is two places for the path to drift apart.
 *
 * Assumptions: the route is not authored yet. `ui/src/router.tsx` declares four screens, so a
 * transition here currently resolves to the router's bounded not-found result, which offers a way
 * back to the card browse. Naming the source's real destination is still correct: sending PF3 to a
 * screen the source does not return to would invent a destination, and the delivery boundary is
 * already recorded in `docs/adr/ADR-006-api-and-ui.md`.
 */
export const MAIN_MENU_ROUTE = '/menu';

/** Route the administrative menu occupies, reached when the group claim carries the admin group. */
export const ADMIN_MENU_ROUTE = '/admin';

/**
 * Performs a router transition and falls back to a full navigation if a data
 * router rejects the transition.
 * @param {NavigateFunction} navigate - Router navigation function.
 * @param {To} destination - Safe application destination.
 */
export function navigateSafely(navigate: NavigateFunction, destination: To): void {
  const transition = navigate(destination);
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
