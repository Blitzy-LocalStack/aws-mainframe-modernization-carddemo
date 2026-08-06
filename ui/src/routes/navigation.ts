import type { NavigateFunction, To } from 'react-router';

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
