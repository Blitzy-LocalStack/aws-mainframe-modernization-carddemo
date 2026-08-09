/**
 * @file Supplies the screen header band its server-derived paint instant.
 *
 * WHY this hook exists — Refactoring Rationale: `ScreenHeader` accepts a `now` prop and its contract
 * places one obligation on whoever composes it: pass a SERVER-derived instant, because the baseline
 * read one region clock in one zone for every terminal. That obligation was met by nobody. All four
 * production call sites omitted the prop, so all four displayed the BROWSER's clock, and two
 * operators looking at one record could read two different dates across a midnight boundary. The
 * component deliberately does not acquire a clock itself — it documents that a presentational band
 * which acquired a clock source would own configuration it has no other reason to know about and
 * would stop being renderable in isolation — so the acquisition belongs in a hook the screens call.
 *
 * Alternatives Considered: an app-shell component that renders the band once for every screen, with
 * the instant held in a context provider. Rejected because each screen must supply its OWN
 * transaction and program identifiers to the band — those are per-screen values from the mapset, and
 * every call site says so — so a shell could not render the band on their behalf and would exist
 * only to carry one value. A hook carries that value without inventing a component to hold it.
 *
 * Alternatives Considered: a React context with a provider at the router root. Rejected because the
 * anchor is already module-level state in `serverClock`, so a provider would be a second place the
 * same value lives, and the two could disagree while a provider's value waited for a re-render.
 *
 * Assumptions: this hook holds no state and subscribes to nothing. It reads the anchored clock during
 * render, which is what makes the value a PAINT-time instant — the property the baseline had, because
 * `POPULATE-HEADER-INFO` re-read the clock on every `SEND MAP` rather than on a timer.
 */

import { serverInstant } from '../api/serverClock';

/**
 * Returns the current instant according to the server, for the header band's date and time slots.
 *
 * Assumptions: `undefined` is a legitimate result and is passed straight through to `ScreenHeader`,
 * where it is indistinguishable from omitting the prop and falls back to the browser clock. That is
 * the case for a unit test and for a local development server that has reached no server at all; in
 * a deployed environment `ui/src/main.tsx` awaits the runtime-configuration fetch before the first
 * render, so an anchor exists by the time any screen paints.
 * @returns {Date | undefined} Server-anchored current instant, or `undefined` when no response has
 *   been observed yet.
 */
export function useServerInstant(): Date | undefined {
  return serverInstant();
}
