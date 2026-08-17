/**
 * @file Vitest setup module for the CardDemo SPA test suite.
 *
 * Purpose
 * -------
 * This module is the one place the browser environment every component test
 * assumes is assembled. `ui/vitest.config.ts` names it in `setupFiles`, so it
 * runs once per test file, before that file's imports are evaluated, and nothing
 * imports it directly. It does exactly four things and deliberately nothing
 * else: it registers the DOM matchers, it unmounts what a test rendered, it sets
 * the asynchronous wait budget that lazily loaded routes need, and it supplies
 * the one browser API jsdom omits that the design system requires.
 *
 * Why this module exists at all
 * -----------------------------
 * jsdom is not a browser and does not claim to be. Two of its gaps are load
 * bearing for this application specifically. It implements no `matchMedia`, and
 * Ant Design's responsive observer calls that function the first time any
 * responsive component mounts -- which for this SPA means every screen, because
 * the fixed 24x80 character grid was deliberately replaced by a responsive
 * `Layout` with `Row`/`Col`. And it shares one `document` across every test in a
 * file, so anything left mounted stays queryable. Both are addressed here rather
 * than in each test, so a screen test asserts on the screen and not on the
 * environment.
 *
 * The wait budget below is a third concern of the same kind but not a jsdom gap:
 * it is a Testing Library default that is calibrated for a resolved import and is
 * too small for a route this application loads through `React.lazy`. It is set
 * here for the same reason - once, where the environment is assembled, rather
 * than annotated onto each case that happens to open a route.
 *
 * What this module deliberately does not own
 * -----------------------------------------
 * No fixture, no render helper, no message text and no design value. A test that
 * needs a themed render composes it in its own file from `ui/src/App.tsx`'s
 * provider; user-visible strings come from `ui/src/messages/messages.ts` and
 * design values from `ui/src/theme/tokens.ts`. Keeping this file to environment
 * assembly is what stops it becoming a second, invisible source of either.
 *
 * Assumptions: no test API is re-exported from here, and none is made ambient.
 * `ui/tsconfig.json` keeps its `types` list empty, so nothing is declared
 * ambiently and every test file imports `describe`, `it`, `expect` and `vi` for
 * itself or fails to compile on the symbol it omitted. Refactoring Rationale: this
 * note credited `globals: false` in `ui/vitest.config.ts` for that; the option is
 * set to `true` there, for the separate reason recorded beside it, so the empty
 * `types` list is named instead of an option that says the opposite. A convenience
 * re-export here would reintroduce exactly the ambient surface the empty list
 * exists to withhold from production sources.
 */

import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';

// Assumptions: Testing Library renders into document.body, so cleaning after
// every test prevents a previous route or alert from satisfying the next
// assertion by accident.
afterEach(cleanup);

/**
 * Budget, in milliseconds, that `waitFor`, `waitForElementToBeRemoved` and every
 * `findBy*` query is allowed before it reports a failure.
 *
 * ⚠️ Refactoring Rationale: Testing Library's own default is 1000ms and it is TOO
 * SHORT for any case that opens a route in `ui/src/router.tsx`. Every screen there
 * is loaded through `React.lazy`, so the first render of a route suspends on an
 * on-demand module transform rather than on a resolved import, and that transform
 * is charged to the case that triggered it. Measured on a four-core runner with
 * file parallelism disabled, the shared frame committed 1704ms after opening
 * `/menu`, 2599ms after `/transactions/:id` and 10798ms after `/transactions/new`
 * -- so `ui/src/routerRoutes.test.tsx` and `ui/src/routerReachability.test.tsx`
 * were reading the `Suspense` fallback and concluding the route resolved to
 * nothing, on route tables that were entirely correct. The symptom is
 * indistinguishable from a genuinely unmounted screen, which is what made it
 * expensive: it reports as `Unable to find an element by:
 * [data-testid="app-shell"]` and names neither lazy loading nor the budget.
 *
 * Assumptions: this weakens no assertion. Every expectation inside a `waitFor`
 * callback is unchanged, and a wait budget decides only how long the utility
 * retries before reporting the same failure -- a route that resolves to nothing
 * still fails, and takes this long to say so.
 *
 * Assumptions: raising this costs an already-passing case nothing, which was
 * measured rather than assumed. A budget bounds only the retry loop of a wait that
 * never succeeds; a wait that succeeds resolves on the render that satisfies it and
 * never observes the ceiling. Three runs of the same file in isolation at ceilings
 * of 1000ms, 30000ms and 15000ms returned 39.7s, 45.4s and 93.0s for the identical
 * case -- an ordering that no monotonic cost in this value can produce, and which
 * is host CPU contention rather than an effect of the setting.
 *
 * Trade-offs: 15000ms is chosen from those measurements rather than picked round.
 * It is 8.8x the 1704ms `/menu` commit, and 1.4x the slowest commit observed, so
 * an ordinary route has an order of magnitude of headroom and the worst one still
 * clears. It stays 4x BELOW the 60000ms `testTimeout` in `ui/vitest.config.ts` on
 * purpose, so a genuine hang is still reported as a failed case rather than as a
 * build that appears to stall -- which is the trade-off that file's own note
 * records for rejecting a 60000ms value there, and the reason this budget is not
 * simply set to match it.
 *
 * Alternatives Considered: (1) passing `{ timeout }` at each affected `waitFor`.
 * Rejected for the reason `ui/vitest.config.ts` gives for rejecting per-case
 * timeouts -- the cost is a property of mounting a lazily loaded screen, so every
 * route case added later would need the same annotation and the one that forgot it
 * would reintroduce the flake. (2) Eagerly importing the screens in the router so
 * no transform is charged to a case. Rejected because the lazy split is a
 * production decision that keeps a record screen out of the chunk an
 * unauthenticated operator fetches, and a test budget must not dictate it.
 */
const LAZY_ROUTE_WAIT_BUDGET_MS = 15000;

configure({ asyncUtilTimeout: LAZY_ROUTE_WAIT_BUDGET_MS });

/**
 * Evaluates a single `(min-width: Npx)` or `(max-width: Npx)` feature against
 * the jsdom window width.
 *
 * Assumptions: Ant Design asks only these two features. Its responsive observer
 * builds every query from one of them - `xs` is `(max-width: screenXSMax)` and
 * `sm` through `xxxl` are `(min-width: screenSM..screenXXXL)` - so parsing the
 * pair covers the whole breakpoint set rather than a sample of it. A query in any
 * other shape returns false, which is the safe direction: an unrecognised feature
 * then behaves as "breakpoint not active" and the component falls back to its
 * unprefixed span instead of silently claiming a width the window does not have.
 * @param {string} query - A CSS media query string, for example
 *   `(min-width: 768px)`.
 * @returns {boolean} True when the query holds for the current
 *   `window.innerWidth`.
 */
function evaluateWidthQuery(query: string): boolean {
  const match = /\((min|max)-width:\s*(\d+)px\)/.exec(query);
  if (match === null) {
    return false;
  }
  const threshold = Number(match[2]);
  return match[1] === 'min' ? window.innerWidth >= threshold : window.innerWidth <= threshold;
}

/**
 * Installs the width-aware `window.matchMedia` that jsdom does not implement.
 *
 * jsdom ships no `matchMedia` at all, so any component reaching for it throws
 * `TypeError: window.matchMedia is not a function` on mount. Ant Design's
 * responsive observer calls it once per breakpoint the first time a `Row`, `Col`
 * or any other responsive component mounts, which means the whole grid - and
 * therefore every screen in this application - is unmountable under jsdom
 * without this shim.
 *
 * Alternatives Considered: a constant stub returning `matches: false`, which is
 * the usual one-line recipe. Rejected because it is not merely incomplete, it is
 * actively misleading: every breakpoint would report inactive, so a `Col` written
 * `span={24} md={6}` would always render at its 24-wide fallback and a test would
 * assert the narrow-viewport branch while believing it had checked the desktop
 * one. Deriving `matches` from `window.innerWidth` instead makes the shim agree
 * with the viewport jsdom actually reports, so the branch under test is the
 * branch the query selects - and a test that wants the other branch can get it by
 * assigning `window.innerWidth`.
 *
 * Trade-offs: only `matches` and `media` carry real values; the listener members
 * are present but inert. Ant Design guards each of them with a function check and
 * uses them solely to react to a viewport that changes after mount, which jsdom
 * never does on its own, so implementing dispatch would add moving parts no test
 * can currently exercise. The full member set is still supplied rather than a
 * partial object, because `window.matchMedia` is typed to return a complete
 * `MediaQueryList` and a cast would move this compromise out of sight.
 * @returns {void} Nothing; `window.matchMedia` is defined as a side effect.
 */
function installMatchMedia(): void {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    /**
     * Builds a `MediaQueryList` for one query.
     * @param {string} query - The media query to evaluate.
     * @returns {MediaQueryList} A list whose `matches` reflects the window width.
     */
    value: function matchMedia(query: string): MediaQueryList {
      const list: MediaQueryList = {
        matches: evaluateWidthQuery(query),
        media: query,
        onchange: null,
        /**
         * Accepts a legacy listener registration without retaining it.
         * @returns {void} Nothing.
         */
        addListener: function addListener(): void {},
        /**
         * Accepts a legacy listener removal without retaining it.
         * @returns {void} Nothing.
         */
        removeListener: function removeListener(): void {},
        /**
         * Accepts a listener registration without retaining it.
         * @returns {void} Nothing.
         */
        addEventListener: function addEventListener(): void {},
        /**
         * Accepts a listener removal without retaining it.
         * @returns {void} Nothing.
         */
        removeEventListener: function removeEventListener(): void {},
        /**
         * Reports that no listener consumed the event, matching an inert list.
         * @returns {boolean} Always false.
         */
        dispatchEvent: function dispatchEvent(): boolean {
          return false;
        },
      };
      return list;
    },
  });
}

/*
 * Assumptions: the shim is installed at MODULE SCOPE, as a side effect of this
 * file being loaded, rather than from a `beforeEach` or `beforeAll` hook. Vitest
 * evaluates a setup file before the test file's own imports run, and an antd
 * module can consult `window.matchMedia` during import evaluation rather than
 * only on mount -- so a hook, which runs after all imports have been evaluated,
 * would install the shim too late for that case and produce a failure inside an
 * import with no test in the stack to attribute it to. Installing at load time
 * makes the property present for every subsequent line of every test file.
 *
 * Trade-offs: this makes the module impure -- importing it mutates the global
 * `window` -- which is ordinarily worth avoiding and is accepted here because
 * the mutation IS the module's purpose and its only caller is the test runner's
 * own `setupFiles` wiring. The property is defined `writable` and
 * `configurable`, so a test that needs different matching behaviour can replace
 * it and a later `cleanup` cannot be obstructed by it.
 *
 * Alternatives Considered: guarding the call with a check for an existing
 * `window.matchMedia` so a future jsdom that implements it would win. Rejected
 * because it would make the breakpoint behaviour of the suite depend on the
 * jsdom version: tests written against the width-derived shim would silently
 * start exercising a different implementation on an upgrade. Overwriting
 * unconditionally keeps one documented behaviour, and the shim is removed in one
 * edit here on the day it is genuinely redundant.
 */
installMatchMedia();

/**
 * Installs a no-op `ResizeObserver` on the global object, which jsdom does not implement.
 *
 * Refactoring Rationale: this shim exists for the same reason the `matchMedia` shim above does --
 * jsdom omits a browser API that antd's components construct unconditionally -- but it became
 * necessary for a specific pairing. The message band renders an antd `Alert` containing a
 * single-line ellipsis text component, and antd wraps such a component in a resize observer of its
 * own that, unlike the band's own measurement, does not test for the constructor before calling it.
 * The result under jsdom is a throw inside a passive effect, which unmounts the whole tree, so a
 * screen asserting on the band's `role="alert"` finds an empty document and reports a missing
 * element rather than the missing API that caused it.
 *
 * Alternatives Considered: (1) stubbing the antd resize wrapper, rejected because it would assert
 * against the library's internals and would silently stop shimming anything the day those internals
 * were renamed. (2) making the band avoid the ellipsis text component, rejected because the ellipsis
 * and its tooltip are the band's way of honouring the baseline's fixed message width without
 * truncating a message silently -- removing them to satisfy a test environment would change the
 * shipped behaviour to suit the harness.
 *
 * Assumptions: a no-op implementation is sufficient and is not a weakening of any assertion. Nothing
 * in this suite asserts on a size-change callback: jsdom reports every element as zero-sized, so a
 * faithful observer would report no change either, and each component that measures already reads
 * the element directly on mount. What the tests need is for construction to succeed.
 * @returns {void} The constructor is present for every subsequent line of every test file.
 */
function installResizeObserver(): void {
  /** No-op observer standing in for the browser API jsdom does not provide. */
  class NoopResizeObserver implements ResizeObserver {
    /**
     * Accepts an element and reports nothing, jsdom having no layout to report.
     * @returns {void} No callback is ever scheduled.
     */
    public observe(): void {
      // Assumptions: deliberately empty. jsdom measures every element as zero by zero, so a
      //   callback here could only ever report a size change that did not happen.
    }

    /**
     * Stops observing an element, which was never observed.
     * @returns {void} Nothing was scheduled, so nothing is cancelled.
     */
    public unobserve(): void {
      // Assumptions: deliberately empty, for the same reason `observe` is.
    }

    /**
     * Releases every observation, of which there are none.
     * @returns {void} Nothing was retained, so nothing is released.
     */
    public disconnect(): void {
      // Assumptions: deliberately empty. Components call this from an effect cleanup, so it must
      //   exist and must not throw; there is no state for it to clear.
    }
  }

  Object.defineProperty(globalThis, 'ResizeObserver', {
    writable: true,
    configurable: true,
    value: NoopResizeObserver,
  });
}

installResizeObserver();
