/**
 * @file Vitest setup module for the CardDemo SPA test suite.
 *
 * Purpose
 * -------
 * This module is the one place the browser environment every component test
 * assumes is assembled, AND the one place the harness that mounts a subject in
 * that environment is published. `ui/vitest.config.ts` names it in `setupFiles`,
 * so it runs once per test file before that file's imports are evaluated; a test
 * file additionally imports its helper surface by name. It does exactly six
 * things and deliberately nothing else: it registers the DOM matchers; it
 * unmounts what a test rendered; it sets the asynchronous wait budget that lazily
 * loaded routes need; it installs the two browser APIs jsdom omits that the design
 * system requires, `matchMedia` and `ResizeObserver`; and it exports the shared
 * render, fixture, key-press, session and assertion helpers a component test
 * composes its subject from.
 *
 * ⚠️ Refactoring Rationale: this module exported NOTHING until this revision, and
 * its own inventory said "nothing imports it directly" -- true, and the defect.
 * Measured across `ui/src` at the moment of the change: 11 test files hand-rolled
 * an antd `ConfigProvider`, 36 hand-rolled a memory router and 10 imported the
 * theme for themselves. A fragmented harness is not an untidiness problem, because
 * the copies DIVERGE and each divergence is silent. Two are already in the tree:
 * `ui/src/layout/appShell.test.tsx` mounts the shell with no provider at all, so
 * every value `ui/src/theme/antdTheme.ts` overrides -- the turquoise informational
 * colour, the blue link colour, all three solid-control shades -- renders there as
 * the design system's own default rather than as the BMS bridge, and
 * `ui/src/screens/cardDetail/cardDetail.test.tsx` mounts the shell
 * through its `children` member, which is the one shape in which the shell never
 * reaches its own `<Outlet />`. A test that assembles its own provider stack is
 * asserting against a tree the application does not build, and nothing fails to
 * say so.
 *
 * Why this module exists at all
 * -----------------------------
 * jsdom is not a browser and does not claim to be. Three of its gaps are load
 * bearing for this application specifically. It implements no `matchMedia`, and
 * Ant Design's responsive observer calls that function the first time any
 * responsive component mounts -- which for this SPA means every screen, because
 * the fixed 24x80 character grid was deliberately replaced by a responsive
 * `Layout` with `Row`/`Col`. It implements no `ResizeObserver` either, and antd
 * wraps the message band's single-line ellipsis text in one without testing for
 * the constructor first, so its absence throws inside a passive effect and
 * unmounts the whole tree. And it shares one `document` across every test in a
 * file, so anything left mounted stays queryable. All three are addressed here
 * rather than in each test, so a screen test asserts on the screen and not on
 * the environment.
 *
 * Refactoring Rationale: this summary read "four things", "the one browser API"
 * and "two gaps" until an earlier revision. Each was accurate when written and
 * each became wrong the moment `installResizeObserver` landed at the foot of this
 * file: that shim's own block states it exists "for the same reason the
 * matchMedia shim above does", but the inventory up here was never carried
 * forward with it, so the module under-reported its own side effects by one. The
 * count is restated -- and both APIs are now named rather than totalled -- because
 * a reader checks a module's inventory against this block before reading three
 * hundred lines of it, which makes an undercount the one comment in the file
 * capable of sending someone to write a shim that is already installed twenty
 * lines below. Naming them also makes the claim falsifiable against the two
 * `install*` calls further down, where a bare number is not. The sixth entry above
 * is the same obligation honoured once more: the helper surface is a side effect of
 * importing this module in exactly the sense the shims are, and an inventory that
 * omitted it would send the next reader to write a second render helper.
 *
 * The wait budget below is a fourth concern of the same kind but not a jsdom gap:
 * it is a Testing Library default that is calibrated for a resolved import and is
 * too small for a route this application loads through `React.lazy`. It is set
 * here for the same reason - once, where the environment is assembled, rather
 * than annotated onto each case that happens to open a route.
 *
 * What this module deliberately does not own
 * -----------------------------------------
 * No message text, no design value, no screen-specific fixture and no network
 * interception. The helpers below WRAP the production collaborators and never
 * restate them: the theme reaches a subject by importing `cardDemoTheme` from
 * `ui/src/theme/antdTheme.ts`, the frame by importing `AppShell`, the key table by
 * importing `ui/src/layout/usePfKeys.ts`, the session by driving the same sign-on
 * exchange a screen drives. Nothing here holds a copy of any of them, which is
 * what stops this file becoming a second, invisible source of a design value or a
 * user-visible sentence -- those stay in `ui/src/theme/tokens.ts` and
 * `ui/src/messages/messages.ts`, and a test still imports the sentence it asserts
 * on from the catalog rather than receiving it from here.
 *
 * ⚠️ Refactoring Rationale: this section previously disclaimed the render helpers
 * and the fixture builders too, and that disclaimer is the finding rather than a
 * boundary. It argued that "a test that needs a themed render composes it in its
 * own file from `ui/src/App.tsx`'s provider", which is neither what the tree did
 * (see the measurement above) nor what `ui/src/App.tsx` permits -- that module
 * exports one component taking no props and deliberately publishes no provider a
 * test could reuse, so the advice resolved to "assemble your own" -- which is what
 * each of the 36 files that mounts a tree went on to do.
 * What survives of the original boundary is the part that was load-bearing: this
 * file owns no CONTENT, only composition.
 *
 * Assumptions: no test-RUNNER API is re-exported from here, and none is made
 * ambient. `ui/tsconfig.json` keeps its `types` list empty, so nothing is declared
 * ambiently and every test file imports `describe`, `it`, `expect` and `vi` for
 * itself or fails to compile on the symbol it omitted. Refactoring Rationale: this
 * note credited `globals: false` in `ui/vitest.config.ts` for that; the option is
 * set to `true` there, for the separate reason recorded beside it, so the empty
 * `types` list is named instead of an option that says the opposite. A convenience
 * re-export here would reintroduce exactly the ambient surface the empty list
 * exists to withhold from production sources -- so the surface below is composed of
 * named HELPERS only, and `expect` appears in it nowhere.
 *
 * Assumptions: no `msw`, no global `fetch` stub, no `axios` module mock and no
 * credential path is installed by this module, and none is available to be: `msw`
 * is absent from `ui/package.json` altogether. A file that needs an answered
 * request installs the recording transport in `ui/src/test/apiHarness.ts`, which
 * dispatches through the real client so the bearer, the correlation identifier and
 * the failure normalisation are all on the path. Masking that here would make every
 * test in the package depend on an interception it never asked for.
 *
 * How this module imports, and why it matters
 * ------------------------------------------
 * ⚠️ Assumptions: every runtime import of application code or of the design system
 * is DYNAMIC and sits inside the helper that needs it; only type-only imports and
 * the runner/matcher wiring are static. Two independent hazards make that the rule
 * rather than a preference, and both are silent.
 *
 * The first is module identity. Vitest documents that a module imported inside a
 * `setupFiles` entry is already cached by the time a test file's hoisted `vi.mock`
 * calls register, so the mock does not reach it. Measured across `ui/src`, the
 * mocked modules are the six `api/*` client modules, `api/client` (partially, in
 * `ui/src/api/pageDirectionGuard.test.ts`) and the lazily loaded screens (in
 * `ui/src/router.test.tsx`). `ui/src/hooks/useAuth.ts` imports `../api/auth`, and
 * `ui/src/layout/AppShell.tsx` imports the hook -- so a STATIC import of the shell
 * here would bind the real transport into it while the test file that mocked
 * `../api/auth` saw the mock, and the two would disagree with nothing failing.
 * Resolving the import inside the helper resolves it after registration, so the
 * helper sees exactly what its caller sees.
 *
 * The second is ordering. ES modules evaluate every static import before the first
 * statement of the module body, so a static `import { ConfigProvider } from 'antd'`
 * here would evaluate the design system BEFORE `installMatchMedia` runs -- the one
 * ordering the shim below exists to guarantee. Verified against the pinned antd
 * 6.5.2 that no module of it reads `window.matchMedia` at evaluation time today, so
 * this is a contract being kept rather than a crash being avoided; the point is that
 * a patch release could change that and this file would have no way to say so.
 *
 * Trade-offs: the consequence is that the render, key-press and session helpers are
 * ASYNCHRONOUS where a static import would have let them be synchronous, and every
 * caller writes one `await`. That cost buys a third property worth having on its
 * own: the 69 test files that use no helper pay nothing for its existence. Measured
 * per test file against this package's own suite, a static import of antd plus the
 * theme adds 1.02s of setup, `usePfKeys` plus the message catalog 0.60s and
 * `react-router` plus `user-event` 0.15s, against a 0.30s baseline -- so the
 * discipline keeps roughly 1.8s per file off 69 files that would never have used it.
 */

import '@testing-library/jest-dom/vitest';
import { cleanup, configure, getDefaultNormalizer, render, screen } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { afterEach, expect } from 'vitest';
import { createElement } from 'react';
import type { ReactElement } from 'react';

import type * as Antd from 'antd';
import type * as UserEventNs from '@testing-library/user-event';
import type * as ReactRouter from 'react-router';
import type { ApiError, FieldError, FieldValidationState, PageResponse } from '../api/types';
import type { CicsAid } from '../layout/usePfKeys';
import type * as SessionHarness from './sessionHarness';

/*
 * Assumptions: all four relative imports above are TYPE-ONLY and are written with
 * the `type` keyword, so `verbatimModuleSyntax` erases them and this module
 * acquires no runtime dependency on the modules they name. That is what lets the
 * DTO shapes, the AID union and the session request be spelled here without
 * reintroducing either hazard the header records -- `./sessionHarness` reaches
 * `../api/auth` through the auth store, and binding that at setup time would put a
 * real transport behind a test file's mock of it.
 *
 * Assumptions: `createElement` is the one runtime import of a rendering package here,
 * and it is exempt from the dynamic-import rule rather than an exception to it. React
 * is neither application code that a test mocks nor a design-system module that reads
 * `window.matchMedia` at evaluation time, and it is ALREADY in this file's static
 * graph by way of the matcher registration and `cleanup` above -- so importing the
 * factory costs nothing and hazards nothing. It is imported at all because
 * `ui/vitest.config.ts` names this file with a `.ts` extension, which is not a JSX
 * extension, so every element below is constructed rather than written as a tag.
 */

/**
 * Whether the case now running seeded a session through {@link seedSession}.
 *
 * Assumptions: a module-scoped flag rather than an unconditional teardown, because
 * a teardown that discarded the session on every case would change the behaviour of
 * every file in the package to serve the new surface. Gated on the flag, a file that
 * never seeds observes exactly what it observed before, and one that does cannot leak
 * its operator into the next case.
 */
let aSessionWasSeeded = false;

/**
 * Unmounts what the case rendered and discards a session it seeded, in that order.
 *
 * Assumptions: Testing Library renders into `document.body`, so cleaning after every
 * test prevents a previous route or alert from satisfying the next assertion by
 * accident. That obligation is unchanged; what is new is the second half.
 *
 * ⚠️ Refactoring Rationale: this replaces a bare `afterEach(cleanup)` and the two
 * concerns are in ONE hook rather than two on purpose. Discarding a session notifies
 * the auth store's listeners, so it has to happen AFTER the tree is unmounted or React
 * reports an update outside `act` on every seeding case. Two `afterEach` registrations
 * cannot express that ordering safely: `sequence.hooks` decides which runs first --
 * `stack` reverses them, `parallel` races them -- and `ui/vitest.config.ts` sets no
 * value, so the order would be a property of a runner default rather than of this file.
 * One hook makes the order local and readable.
 * @returns {Promise<void>} Resolves once the tree is unmounted and, if the case seeded
 *   one, the session and its transport are gone.
 */
async function unmountAndDiscardWhatTheCaseInstalled(): Promise<void> {
  cleanup();
  if (!aSessionWasSeeded) {
    return;
  }
  await resetSessionAndTransport();
}

afterEach(unmountAndDiscardWhatTheCaseInstalled);

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
 * ⚠️ Refactoring Rationale: the call is GUARDED by a check for an existing
 * `window.matchMedia`, and this block previously argued the opposite -- that
 * guarding "would make the breakpoint behaviour of the suite depend on the jsdom
 * version", so the shim should overwrite unconditionally to keep one documented
 * behaviour. The concern is real and the conclusion inverted the cost. What
 * unconditional definition buys is that the suite keeps ONE behaviour; what it
 * costs is that a real implementation is replaced by a stub whose listener members
 * are inert by construction, silently, forever -- so the day jsdom implements
 * `matchMedia` faithfully, this file substitutes a weaker implementation for a
 * better one and nothing in the run says which one answered. That is environment
 * MASKING, and it is the failure this file's own charter forbids: a shim exists to
 * fill a gap, and a gap that has closed is not a gap. The version-dependence the
 * old note feared is also cheap to bound, which is what settles it -- the property
 * is defined `writable` and `configurable`, so a case that needs the width-derived
 * behaviour deterministically installs it for itself in one assignment.
 *
 * Assumptions: this changes no current verdict, which was verified rather than
 * assumed. Probed against the pinned jsdom 29.1.1 by constructing a document
 * directly, `typeof window.matchMedia` is `undefined` -- so the guard's condition
 * holds today and the shim installs exactly as it did before, for all 69 test files.
 * The guard is therefore gate hardening with no behavioural change to observe.
 */
if (typeof window.matchMedia !== 'function') {
  installMatchMedia();
}

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

/*
 * ⚠️ Assumptions: this call is GUARDED for the reason recorded in full at the
 * `installMatchMedia` call above, and the case for guarding is stronger here
 * because this stand-in is a NO-OP rather than a partial implementation. A real
 * `ResizeObserver` that jsdom one day ships would report actual size changes to a
 * component that asked to hear about them; replacing it with a constructor that
 * never schedules a callback would leave every such component permanently
 * unnotified, with no failure to attribute it to. The guard tests for a callable
 * constructor rather than for the property's presence, because a non-callable value
 * of that name is not something a component can construct.
 *
 * Assumptions: this changes no current verdict either. Probed against the pinned
 * jsdom 29.1.1, `'ResizeObserver' in window` is `false` and `typeof
 * window.ResizeObserver` is `undefined`, so the condition holds and the stand-in
 * installs exactly as it did before.
 *
 * Trade-offs: the check reads `globalThis` while the `matchMedia` guard reads
 * `window`, matching the object each shim installs onto -- antd constructs the
 * observer as a bare global while it reaches for `matchMedia` through `window`, so
 * each guard tests the exact expression the consumer evaluates. Under the jsdom
 * environment the two objects are the same, so the asymmetry costs nothing and keeps
 * each guard falsifiable against the definition immediately above it.
 */
if (typeof globalThis.ResizeObserver !== 'function') {
  installResizeObserver();
}

/**
 * Installs a no-op `Element.prototype.scrollIntoView`, which jsdom does not implement.
 *
 * Refactoring Rationale: this shim exists for the same reason the two above it do -- jsdom omits a
 * browser API application code calls unconditionally -- and it became necessary for one measured
 * defect. A browser pass found the transaction-capture screen's confirmation overlay rendering
 * twenty-three pixels below the foot of the display, with the lower eleven pixels of both answers cut
 * off, because the overlay is anchored to a control at the foot of a form long enough to overflow the
 * screen body and a turn taken from the function-key legend needs no scrolling to reach that control.
 * The screen now brings the anchor onto the display before raising the question, and jsdom answers
 * that call with `TypeError: ...scrollIntoView is not a function` -- thrown inside a click handler, so
 * fifteen cases across four files failed on an exception rather than on an assertion.
 *
 * Alternatives Considered: (1) guarding the call site with `typeof anchor.scrollIntoView ===
 * 'function'`. Rejected: the API is present in every browser this application ships to and absent
 * only in the harness, so the guard would put a permanently-true condition into production code to
 * describe a property of the test environment -- and it would read to the next maintainer as though
 * the API were genuinely optional. An environment gap belongs in the file that assembles the
 * environment. (2) Stubbing it per test file. Rejected because the four files that failed are owned
 * by different areas and any fifth screen that scrolls an element into view would fail the same way,
 * with nothing to attribute it to.
 *
 * Assumptions: a no-op is sufficient and weakens no assertion. jsdom performs NO layout -- every
 * element reports a zero-sized rectangle and the document has no scrollport -- so a faithful
 * implementation would have nothing to scroll and no observable to offer. Nothing in this suite
 * asserts a scroll position, and nothing can: the property this shim's motivating fix delivers is a
 * rectangle inside the viewport, which is measurable only in a real browser and is verified there.
 * What the tests need is for the call to succeed.
 * @returns {void} The method is present on every element for every subsequent line of every test file.
 */
function installScrollIntoView(): void {
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    writable: true,
    configurable: true,
    /**
     * Accepts a scroll request and performs nothing, jsdom having no layout to scroll.
     * @returns {void} No scrolling occurs and no event is dispatched.
     */
    value: function scrollIntoView(): void {
      // Assumptions: deliberately empty. jsdom lays nothing out, so there is no position to change
      //   and no scroll event a caller could observe; the contract this satisfies is "does not throw".
    },
  });
}

/*
 * ⚠️ Assumptions: GUARDED for the reason recorded at the two calls above, and the guard matters more
 * here than for either of them because this stand-in is installed onto a SHARED PROTOTYPE rather than
 * onto a global of its own. Overwriting a real implementation would silently disable scrolling for
 * every element in every test, in a runner where nothing asserts a scroll position and so nothing
 * would fail to attribute it to. The check reads the prototype rather than an instance, because that
 * is where the method would arrive if jsdom implemented it.
 *
 * Assumptions: this changes no current verdict. Probed against the pinned jsdom 29.1.1,
 * `typeof Element.prototype.scrollIntoView` is `undefined`, so the condition holds and the stand-in
 * installs; the day jsdom ships a real one, the guard hands the suite the real one instead.
 */
if (typeof Element.prototype.scrollIntoView !== 'function') {
  installScrollIntoView();
}

/*
 * ---------------------------------------------------------------------------
 * The shared harness
 * ---------------------------------------------------------------------------
 * Everything below is the surface a component test composes its subject from.
 * The environment above is assembled first and unconditionally; the harness is
 * reached only by a file that imports one of its members, and every runtime
 * dependency it has is resolved inside the member that needs it, for the two
 * reasons the header records.
 */

/**
 * What a rendered subject is to be surrounded by.
 *
 * Assumptions: both members are optional and a subject that needs neither passes
 * nothing, because the common case is a screen that reads no route parameter and
 * starts at the root -- and a harness whose common case requires an argument gets
 * copied rather than called.
 */
export interface HarnessRenderOptions {
  /**
   * The history the memory router starts with. The LAST entry is the one rendered,
   * which is the router's own behaviour rather than this harness's: a memory router
   * opens at `initialEntries.length - 1`, so earlier entries exist to give a
   * back-navigation something to return to.
   */
  readonly initialEntries?: readonly string[];
  /**
   * The route pattern the subject is mounted at, for a screen that reads a parameter
   * from it. Supply the pattern as `ui/src/router.tsx` declares it -- for instance
   * `/transactions/:id` or `/authorizations/:key` -- and put the concrete address in
   * `initialEntries`. Omitted, the subject is rendered directly and reads no
   * parameter.
   */
  readonly routePath?: string;
}

/**
 * A rendered subject and the keyboard and pointer operator that drives it.
 *
 * Assumptions: this INTERSECTS Testing Library's own result rather than wrapping it,
 * so `rerender`, `unmount`, `container` and the bound queries all remain available
 * under the names every caller already knows. A harness that renamed or hid them
 * would make itself the thing a reader has to learn before reading a test.
 */
export type HarnessRenderResult = RenderResult & {
  /**
   * The operator, from `userEvent.setup()`. Created BEFORE the render, which is the
   * ordering `@testing-library/user-event` documents: setup installs the clipboard
   * and pointer state the instance uses, and doing it after a render has already
   * dispatched events puts the instance behind the document it is driving.
   */
  readonly user: UserEvent;
};

/**
 * The routing package as this harness resolves it.
 *
 * Assumptions: the module's own shape is used as the type rather than the components
 * being typed individually, so the internal helpers below name exactly what
 * `react-router` publishes and cannot drift from it. The module is reached through the
 * `import type * as` namespace at the head of this file, which TypeScript erases in
 * full, so naming it here adds no runtime import. Alternatives Considered: an inline
 * `typeof import('react-router')`, which says the same thing in one line;
 * `ui/eslint.config.js` forbids an `import()` type annotation, so the namespace form is
 * the one this package accepts.
 */
type RouterModule = typeof ReactRouter;

/**
 * Rejects a route pattern the rendered address cannot match.
 *
 * ⚠️ Assumptions: the check uses react-router's OWN `matchPath` rather than comparing
 * segment counts here, so the harness can never disagree with the matcher that
 * decides the real outcome. The failure it prevents is the most confusing one this
 * surface can produce: a pattern and an address that do not match render an EMPTY
 * document, and the case then fails several lines later on a query for an element
 * that was never going to exist, naming the element rather than the mismatch.
 *
 * Assumptions: the search and fragment are stripped before matching, because
 * `matchPath` compares pathnames only while a paged screen's entry legitimately
 * carries a query string -- `/cards?page=2` is a normal address for a list screen to
 * open at, and matching it whole would reject it.
 * @param {RouterModule} router - The resolved routing module, whose matcher decides.
 * @param {string} routePath - The route pattern the subject is mounted at.
 * @param {string} renderedEntry - The address the router opens at.
 * @returns {void} Nothing; returns when the pattern matches.
 * @throws {Error} If the pattern cannot match the address, naming both.
 */
function assertPatternMatchesEntry(
  router: RouterModule,
  routePath: string,
  renderedEntry: string,
): void {
  const pathnameOnly = renderedEntry.split('#')[0]?.split('?')[0] ?? '';
  if (router.matchPath(routePath, pathnameOnly) === null) {
    throw new Error(
      `routePath '${routePath}' cannot match the rendered entry '${renderedEntry}': the subject would ` +
        'render into an empty document. Give initialEntries a concrete address for that pattern.',
    );
  }
}

/**
 * Reports the address a memory router opens at, given the history it is handed.
 *
 * Assumptions: the LAST entry, because that is where a memory router starts -- it
 * opens at `initialEntries.length - 1` -- so earlier entries are the back-stack
 * rather than the subject. Naming that in one place keeps the two render helpers from
 * each having to restate it, and keeps the pattern check honest about which entry it
 * is checking.
 * @param {readonly string[]} entries - The history handed to the router.
 * @returns {string} The address that will be rendered.
 */
function addressTheRouterOpensAt(entries: readonly string[]): string {
  return entries[entries.length - 1] ?? '/';
}

/**
 * The collaborators a render helper needs, once resolved.
 *
 * Assumptions: the whole routing module is carried rather than the three components
 * taken out of it, because `assertPatternMatchesEntry` needs its matcher as well as
 * its components and a caller of one always needs the other.
 */
interface ResolvedHarnessModules {
  /** The design system's provider, which is what injects the theme. */
  readonly configProvider: typeof Antd.ConfigProvider;
  /** The routing module, components and matcher together. */
  readonly router: RouterModule;
  /** The application's own theme, imported and never restated. */
  readonly themeConfig: Antd.ThemeConfig;
  /** The operator factory, from which one instance is created per render. */
  readonly userEvent: typeof UserEventNs.default;
}

/**
 * Resolves the design system, the router, the operator factory and the theme.
 *
 * Assumptions: the four are resolved CONCURRENTLY, because they are independent and
 * the design system is by far the slowest -- measured at roughly a second on first
 * use in a worker, against 0.15s for the router and the operator together. Awaiting
 * them in sequence would add that latency to the first render of every file that uses
 * a helper, for no benefit.
 *
 * Assumptions: every one of the four is a DYNAMIC import for the reasons the header
 * records, and this function is the single place they are named -- so a fifth
 * collaborator is added here once rather than in each render helper.
 * @returns {Promise<ResolvedHarnessModules>} The resolved collaborators.
 */
async function resolveHarnessModules(): Promise<ResolvedHarnessModules> {
  const [{ ConfigProvider }, router, userEventModule, { cardDemoTheme }] = await Promise.all([
    import('antd'),
    import('react-router'),
    import('@testing-library/user-event'),
    import('../theme/antdTheme'),
  ]);
  return {
    configProvider: ConfigProvider,
    router,
    themeConfig: cardDemoTheme,
    userEvent: userEventModule.default,
  };
}

/**
 * Renders an already-composed subject inside the provider stack the application uses.
 *
 * ⚠️ Assumptions: the operator is created BEFORE `render`, and this is the single
 * place in this file where that ordering exists -- which is the reason both public
 * helpers route through here rather than each assembling the stack. `userEvent.setup()`
 * installs the document-level pointer and clipboard state the instance drives, and an
 * instance created after a render has already dispatched events starts out behind the
 * document it is driving. A caller cannot make that mistake because it never sees the
 * seam.
 *
 * Assumptions: `initialEntries` is COPIED into a mutable array, because the router
 * declares the property mutable while this file's options declare it `readonly` --
 * copying satisfies both without either weakening the caller's guarantee or asserting
 * a type.
 * @param {ResolvedHarnessModules} resolved - The resolved collaborators.
 * @param {readonly string[]} entries - The history the router starts with.
 * @param {ReactElement} routed - The subject, already wrapped in whatever routes it needs.
 * @returns {HarnessRenderResult} The render result, with the operator attached.
 */
function renderInsideProviders(
  resolved: ResolvedHarnessModules,
  entries: readonly string[],
  routed: ReactElement,
): HarnessRenderResult {
  const user = resolved.userEvent.setup();
  const rendered = render(
    createElement(
      resolved.configProvider,
      { theme: resolved.themeConfig },
      createElement(resolved.router.MemoryRouter, { initialEntries: [...entries] }, routed),
    ),
  );
  return { ...rendered, user };
}

/**
 * Renders a subject inside the providers the application itself supplies.
 *
 * Purpose
 * -------
 * `ui/src/App.tsx` is the SOLE place the application injects the design system's
 * theme, and it does so around the whole router. A subject rendered bare therefore
 * has no theme at all: antd falls back to its own defaults, so every colour, radius
 * and spacing it renders is an Ant Design value rather than the BMS bridge in
 * `ui/src/theme/tokens.ts`, and any assertion that depends on a tokenised value is
 * asserting against a tree the application does not build. This helper reproduces
 * that provider and the router around one subject.
 *
 * Assumptions: the theme reaches the subject by IMPORTING `cardDemoTheme`, never by
 * restating a token here. That is what makes a token change visible to the suite: a
 * value edited in `ui/src/theme/tokens.ts` reaches every test that renders through
 * this helper, and a helper holding its own copy would quietly keep testing the old
 * one.
 *
 * Assumptions: the router is a MEMORY router. A screen reaches its siblings with
 * `useNavigate` and its parameters with `useParams`, both of which throw outside a
 * router, and a memory router keeps the history in the process so a navigation is
 * observable without a URL bar.
 *
 * Alternatives Considered: exporting a `Providers` component for a caller to pass as
 * Testing Library's `wrapper` option. Rejected because `wrapper` is applied to
 * `rerender` too but NOT to anything the caller renders separately, so a file that
 * mixed the two would get a themed first render and an unthemed second one -- and
 * because it would leave every caller to remember `userEvent.setup()` before the
 * render, which is the ordering mistake this helper exists to make unmakeable.
 * @param {ReactElement} ui - The subject to render.
 * @param {HarnessRenderOptions} [options] - The history and route pattern to surround it with.
 * @returns {Promise<HarnessRenderResult>} The render result, with the operator attached.
 * @throws {Error} If `options.routePath` cannot match the address the router opens at.
 */
export async function renderWithProviders(
  ui: ReactElement,
  options: HarnessRenderOptions = {},
): Promise<HarnessRenderResult> {
  const resolved = await resolveHarnessModules();
  const entries = options.initialEntries ?? ['/'];
  const routed =
    options.routePath === undefined
      ? ui
      : routedSubject(resolved.router, ui, options.routePath, entries);
  return renderInsideProviders(resolved, entries, routed);
}

/**
 * Wraps a subject in the single route that gives it its parameters.
 *
 * Assumptions: a `Routes`/`Route` pair is built ONLY when a pattern was supplied. A
 * subject that reads no parameter is rendered directly under the router instead,
 * because mounting it under a catch-all route would change how a relative `<Link>`
 * inside it resolves -- relative resolution is against the matched route, and a
 * splat route is not the route the application matches.
 * @param {RouterModule} router - The resolved routing module.
 * @param {ReactElement} ui - The subject.
 * @param {string} routePath - The route pattern to mount it at.
 * @param {readonly string[]} entries - The history, whose last member is rendered.
 * @returns {ReactElement} The subject wrapped in its route.
 * @throws {Error} If the pattern cannot match the address the router opens at.
 */
function routedSubject(
  router: RouterModule,
  ui: ReactElement,
  routePath: string,
  entries: readonly string[],
): ReactElement {
  assertPatternMatchesEntry(router, routePath, addressTheRouterOpensAt(entries));
  return createElement(
    router.Routes,
    null,
    createElement(router.Route, { path: routePath, element: ui }),
  );
}

/**
 * Renders a subject inside the real application shell, as a child route of it.
 *
 * Purpose
 * -------
 * The shell is a LAYOUT route in `ui/src/router.tsx`: it renders its four zones
 * around an `<Outlet />` that the matched child fills. This helper reproduces that,
 * so the subject reaches the frame the way a screen does -- through the outlet.
 *
 * ⚠️ Alternatives Considered: passing the subject as the shell's `children` member,
 * which `ui/src/layout/AppShell.tsx` accepts and which two files in this package
 * already do. Rejected because `children` REPLACES the outlet rather than filling
 * it: the shell renders `children ?? <Outlet />`, so a tree assembled that way is
 * the one arrangement in which the outlet is never exercised at all. A regression
 * that broke outlet rendering -- a misplaced layout route, a lost `<Outlet />` --
 * would leave every such test passing. The `children` member exists for the shell's
 * own unit tests, and mounting a SCREEN through it tests a composition the router
 * never builds.
 * @param {ReactElement} ui - The screen to render inside the shell.
 * @param {HarnessRenderOptions} [options] - The history and route pattern to surround it with.
 * @returns {Promise<HarnessRenderResult>} The render result, with the operator attached.
 * @throws {Error} If `options.routePath` cannot match the address the router opens at.
 */
export async function renderInAppShell(
  ui: ReactElement,
  options: HarnessRenderOptions = {},
): Promise<HarnessRenderResult> {
  const [resolved, { AppShell }] = await Promise.all([
    resolveHarnessModules(),
    import('../layout/AppShell'),
  ]);

  const entries = options.initialEntries ?? ['/'];
  const childPath = options.routePath ?? '*';
  if (options.routePath !== undefined) {
    assertPatternMatchesEntry(resolved.router, options.routePath, addressTheRouterOpensAt(entries));
  }

  return renderInsideProviders(
    resolved,
    entries,
    createElement(
      resolved.router.Routes,
      null,
      // Assumptions: the layout route is PATHLESS, which is how `ui/src/router.tsx`
      //   declares it -- a layout route contributes no segment, so the child's pattern
      //   is the whole address and a caller's `routePath` stays the string the
      //   application's own route table spells.
      createElement(
        resolved.router.Route,
        { element: createElement(AppShell) },
        createElement(resolved.router.Route, { path: childPath, element: ui }),
      ),
    ),
  );
}

/**
 * The cursor a built page reports as the position of its first row.
 *
 * Assumptions: the token is opaque and self-describing rather than realistic,
 * because a client neither parses nor computes on a cursor -- `ui/src/api/types.ts`
 * records that the service seals the direction into it -- so the only property a
 * fixture needs is that a replay be recognisable when it arrives.
 */
export const LEADING_CURSOR = 'a-leading-cursor';

/**
 * The cursor a built page reports as the position of its last row.
 */
export const TRAILING_CURSOR = 'a-trailing-cursor';

/**
 * What a built page envelope is to report beyond its rows.
 */
export interface PageEnvelopeOptions {
  /** Whether reading forward from the trailing cursor yields a further page. */
  readonly hasNext?: boolean;
  /** The leading cursor to report, for a case that asserts on a specific replay. */
  readonly firstKey?: string | null;
  /** The trailing cursor to report. */
  readonly lastKey?: string | null;
}

/**
 * Builds one typed page envelope in the four-member shape every contract publishes.
 *
 * Purpose
 * -------
 * This is the DECODED envelope, which is what a mocked client function returns:
 * a file that declares `vi.mock('../api/cards')` has to hand its screen a
 * `PageResponse<T>`, and building that by hand is where a fifth member gets invented.
 *
 * ⚠️ Assumptions: exactly four members, and a fifth is not merely unnecessary but
 * wrong. There is no `hasPrev`, no page number, no page size and no row total,
 * because no service sends one -- `ui/src/api/types.ts` records that every contract
 * declares the four with `additionalProperties: false`. Backward availability is the
 * CLIENT's own page ordinal, held by `ui/src/hooks/usePagedQuery.ts` exactly as
 * `app/cbl/COCRDLIC.cbl` holds `WS-CA-SCREEN-NUM` at L237 and refuses the backward
 * step on it at L902. A builder that invented the flag would let a screen test pass
 * against a body the server never sends, which is the one failure a fixture can
 * produce that no assertion catches.
 *
 * Assumptions: both cursors are `null` when the page carries no rows, which is the
 * envelope's own contract -- a cursor identifies a row, and an empty page has none.
 *
 * ⚠️ Assumptions: an override is detected by comparing against `undefined` rather than
 * with `??`, because `null` is a MEANINGFUL value for a cursor and `??` would treat a
 * caller's explicit `null` as if it had passed nothing -- handing back a token for a
 * page the caller was deliberately describing as cursorless. `ui/tsconfig.json` sets
 * `exactOptionalPropertyTypes`, so an absent member is the only way `undefined` reaches
 * here, which is what makes the comparison exact rather than merely conventional.
 *
 * Alternatives Considered: reusing `pageOf` from `ui/src/test/apiHarness.ts`.
 * Rejected because the two sit at different layers and neither substitutes for the
 * other: `pageOf` builds the untyped WIRE body the recording transport answers with,
 * so its cursors travel through the real client, while this builds the typed value a
 * MOCKED client function returns, so its cursors never reach a transport at all. The
 * two are never both in play for one page, which is why the tokens here are
 * deliberately different strings -- equal ones would suggest a shared contract that
 * does not exist.
 * @template T The row type of one page.
 * @param {readonly T[]} items - The rows the page carries.
 * @param {PageEnvelopeOptions} [options] - What the envelope reports beyond its rows.
 * @returns {PageResponse<T>} The envelope, typed as the operation's return value.
 */
export function pageResponse<T>(
  items: readonly T[],
  options: PageEnvelopeOptions = {},
): PageResponse<T> {
  const empty = items.length === 0;
  return {
    items: [...items],
    firstKey: options.firstKey === undefined ? (empty ? null : LEADING_CURSOR) : options.firstKey,
    lastKey: options.lastKey === undefined ? (empty ? null : TRAILING_CURSOR) : options.lastKey,
    hasNext: options.hasNext ?? false,
  };
}

/**
 * Builds one field-level failure in the shape the shared advice renders.
 *
 * Assumptions: `message` is supplied by the caller and is never defaulted, because
 * transformation rule T8 carries every user-visible string across character for
 * character -- so the sentence belongs to `ui/src/messages/messages.ts` and a builder
 * that produced one of its own would be a second, unreviewed source of screen text.
 *
 * Assumptions: the state defaults to `NOT_OK` rather than `BLANK` because the two are
 * not interchangeable: `app/cpy/CSSETATY.cpy` writes a literal asterisk into the field
 * only in the blank case, so `BLANK` carries a rendering obligation `NOT_OK` does not
 * and a case exercising it says so explicitly.
 * @param {string} field - The request property that failed, as the contract names it.
 * @param {string} message - The verbatim sentence, from the message catalog.
 * @param {FieldValidationState} [state] - Which failure condition applies, defaulting to `NOT_OK`.
 * @returns {FieldError} The field error.
 */
export function fieldError(
  field: string,
  message: string,
  state: FieldValidationState = 'NOT_OK',
): FieldError {
  return { field, state, message };
}

/**
 * Builds one problem document in the eleven-member shape the shared advice renders.
 *
 * Assumptions: `message` defaults to `null` rather than to a sentence. Every contract
 * declares the member nullable and an ordinary refusal may carry none, so `null` is a
 * real value rather than a placeholder -- and defaulting it to text would put a
 * user-visible string in this file, which owns none.
 *
 * Assumptions: `fieldErrors` defaults to an empty array and never to absent, because
 * every contract declares it required; `ui/src/api/types.ts` records that checking its
 * presence would be checking a condition that never occurs.
 *
 * Assumptions: the timestamp default is in the twenty-six-character form
 * `YYYY-MM-DD HH:MM:SS.mmmmmm` that `Timestamp26` declares, and NOT an ISO instant
 * with a `T` and a `Z`. The date is the business date `app/jcl/INTCALC.jcl` injects at
 * L22 as `PARM='2022071800'`, so a fixture reads as a value from this system's own
 * reference data rather than as today.
 * @param {Partial<ApiError>} [overrides] - Members to replace; every member has a default.
 * @returns {ApiError} The problem document.
 */
export function apiError(overrides: Partial<ApiError> = {}): ApiError {
  return {
    code: 'CARDDEMO-0400',
    secondaryCode: '',
    message: null,
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId: '00000000-0000-4000-8000-000000000000',
    path: '/api/v1',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors: [],
    abend: null,
    ...overrides,
  };
}

/**
 * Builds the HTTP 409 problem document a refused write carries.
 *
 * Purpose
 * -------
 * Three screens surface a 409 and they do NOT mean the same thing. Account update and
 * card update refuse on optimistic concurrency -- the JPA `@Version` column detects
 * that the record moved under the operator, which is the migrated form of the
 * before-image comparison `app/cbl/COACTUPC.cbl` performs with
 * `ACUP-OLD-DETAILS` at L669 and its change flag at L521. Reference-type delete
 * refuses because a category still points at the type, which is the
 * `ON DELETE RESTRICT` foreign key preserving the baseline's `XTRNTYCAT` semantic.
 * The status is shared; the sentence is not.
 *
 * ⚠️ Assumptions: the message is therefore a required argument. `ui/src/api/client.ts`
 * publishes `isConflictFailure` as the sanctioned way to recognise the status, so what
 * a case actually needs from a fixture is the OTHER half -- the right sentence for the
 * right refusal -- and a builder that defaulted it would quietly give the restrict
 * refusal the concurrency wording.
 * @param {string} message - The verbatim refusal, from the message catalog.
 * @param {Partial<ApiError>} [overrides] - Further members to replace.
 * @returns {ApiError} The problem document, at status 409.
 */
export function conflictProblem(message: string, overrides: Partial<ApiError> = {}): ApiError {
  return apiError({ code: 'CARDDEMO-0409', status: 409, message, ...overrides });
}

/**
 * Presses the browser key that raises one CICS attention identifier.
 *
 * Purpose
 * -------
 * The 3270 original was keyboard-only, so the PF-key contract is a KEYBOARD contract.
 * Clicking the corresponding control in `ui/src/layout/PfKeyBar.tsx` exercises the
 * bar, not the binding -- a case that only clicks passes in full while every keyboard
 * binding in the application is broken, which is the regression this helper exists to
 * make catchable.
 *
 * ⚠️ Assumptions: the browser key is DERIVED by inverting the published
 * `KEYBOARD_KEY_TO_AID`, skipping the members `PF_KEY_ALIASES` contributes, and the
 * derivation is then round-tripped back through the hook's own `resolveAid` before the
 * key is pressed. Deriving rather than tabulating is what makes drift impossible: a
 * key added to or moved within the hook's table changes what this presses, in the same
 * commit, with no second table to remember. The round-trip is the belt: it fails loudly
 * here rather than in a screen assertion if the inversion ever stops agreeing with the
 * forward lookup.
 *
 * Assumptions: the alias members are skipped for the same reason
 * `ui/src/layout/PfKeyBar.tsx` skips them when it derives the key each control
 * advertises -- `F13` through `F24` collapse onto `PFK01` through `PFK12` per
 * `app/cpy/CSSTRPFY.cpy` L54 to L77, so an un-skipped inversion would resolve `PFK01`
 * to whichever of `F1` and `F13` the table happened to list first. The two derivations
 * are deliberately identical and are not shared, because the constant in `PfKeyBar` is
 * private to that module.
 * @param {UserEvent} user - The operator from a render helper's result.
 * @param {CicsAid} aid - The attention identifier to raise.
 * @returns {Promise<void>} Resolves once the key event has been dispatched and settled.
 * @throws {Error} If the identifier has no browser key. `CLEAR`, `PA1` and `PA2` are
 *   real members of the AID domain that deliberately have none: `ui/src/layout/usePfKeys.ts`
 *   records that measured usage across all 21 online programs is zero, so inventing a
 *   web key for them would create behaviour absent from the source application. A case
 *   reaching for one is testing a path the application does not have.
 */
export async function pressPfKey(user: UserEvent, aid: CicsAid): Promise<void> {
  const { KEYBOARD_KEY_TO_AID, PF_KEY_ALIASES, resolveAid } = await import('../layout/usePfKeys');

  let browserKey: string | undefined;
  for (const [candidate, mapped] of Object.entries(KEYBOARD_KEY_TO_AID)) {
    // Assumptions: the alias test is the `in` operator, matching the derivation in
    //   `ui/src/layout/PfKeyBar.tsx` exactly, and it is safe here for a reason that does
    //   not hold generally: `candidate` comes from the frozen table's OWN entries, so it
    //   is one of `Enter` and `F1` through `F24` and can never be an inherited name.
    //   `ui/src/layout/usePfKeys.ts` guards its own exported lookup with `Object.hasOwn`
    //   because that one accepts an arbitrary string from a keyboard event; this one does
    //   not accept anything.
    if (mapped !== aid || candidate in PF_KEY_ALIASES) {
      continue;
    }
    browserKey = candidate;
    break;
  }

  if (browserKey === undefined) {
    throw new Error(
      `${aid} has no browser key: it is a terminal attention identifier with no web equivalent, so ` +
        'no keyboard press can raise it. Drive the case through the action it is bound to instead.',
    );
  }
  if (resolveAid(browserKey) !== aid) {
    throw new Error(
      `the key '${browserKey}' derived for ${aid} does not normalise back to it, so this helper and ` +
        'ui/src/layout/usePfKeys.ts have diverged',
    );
  }
  await user.keyboard(`{${browserKey}}`);
}

/**
 * A seeded session and the probe that maintains it, as the session harness returns them.
 *
 * Assumptions: the shape is taken from `establishSession` rather than restated, so it
 * cannot drift from what that function actually returns. The module is reached through
 * the erased `import type * as` namespace at the head of this file, so naming it here
 * adds no runtime import -- which matters more than tidiness, because a RUNTIME import
 * of this module at setup scope is one of the two hazards the header records.
 */
export type SeededSession = Awaited<ReturnType<typeof SessionHarness.establishSession>>;

/**
 * Signs a case on by driving the same exchange a screen drives, and arms its teardown.
 *
 * Purpose
 * -------
 * A screen behind the shell reads the operator from `ui/src/hooks/useAuth.ts`, and an
 * unauthenticated tree renders the sign-on route instead of the subject. This is how a
 * case gets an operator without either mocking the hook or asserting against a tree the
 * application does not build.
 *
 * ⚠️ Assumptions: there is NO provider to mount and none is invented here. The hook holds
 * its session in module scope, and it deliberately publishes no setter for the groups or
 * the user type, because authority derives solely from the signed `cognito:groups` claim.
 * A seeding helper that assigned a group directly would create a second, weaker path to
 * authority that production does not have -- so this one mints a token carrying the
 * groups and drives the real sign-on, which is the only path there is. Pass the groups
 * through `request.groups`.
 *
 * ⚠️ Assumptions: the recording transport is installed FIRST and the ordering is
 * load-bearing in one direction only. `installApiHarness` clears the answer queue, so a
 * case must seed BEFORE queueing the answers its own subject needs -- seeding afterwards
 * discards them and the subject then receives the harness's empty fallback body.
 *
 * Assumptions: the returned probe is NOT unmounted here. A session survives its probe
 * being unmounted -- it is module state, not component state -- so unmounting would leave
 * the session in place with no listener arming its renewal timer, which is a state no
 * application reaches. The teardown this file registers unmounts it.
 *
 * Assumptions: calling this ARMS the automatic reset in this file's `afterEach`, and the
 * flag is set before the exchange rather than after it, so a case whose sign-on fails is
 * cleaned up as thoroughly as one whose sign-on succeeds.
 * @param {SessionHarness.SessionRequest} [request] - What the session is to consist of; every member
 *   has a default.
 * @returns {Promise<SeededSession>} The live reading of the hook and the probe's teardown.
 * @throws {Error} If the exchange did not establish a session, which the harness reports.
 */
export async function seedSession(request?: SessionHarness.SessionRequest): Promise<SeededSession> {
  const [{ installApiHarness }, { establishSession }] = await Promise.all([
    import('./apiHarness'),
    import('./sessionHarness'),
  ]);
  installApiHarness();
  aSessionWasSeeded = true;
  return await establishSession(request);
}

/**
 * Discards any session and removes the recording transport.
 *
 * Purpose
 * -------
 * Both halves of a session outlive every component -- the hook holds the identity and
 * `ui/src/api/client.ts` holds the bearer -- so either surviving into the next case
 * would sign that case on as whoever this one signed on as.
 *
 * ⚠️ Assumptions: this is NOT a duplicate of the runner's own `clearMocks` and
 * `restoreMocks`, which `ui/vitest.config.ts` enables. Those reset MOCKS: a spy's
 * recorded calls and its implementation. Neither reaches a module-scoped variable in
 * `ui/src/hooks/useAuth.ts`, an axios default header, or a stubbed environment value --
 * none of which is a mock, and all of which are what a session consists of.
 *
 * Assumptions: the session is discarded BEFORE the transport is removed. Discarding it
 * clears the bearer through the client, and `removeApiHarness` rebuilds that client from
 * the environment -- so removing the transport first would clear the bearer on an
 * instance nothing dispatches through, leaving it attached to the one that does.
 *
 * Trade-offs: `removeApiHarness` calls `vi.unstubAllEnvs()`, so a case that both seeded a
 * session and stubbed an environment value of its own loses the second stub here too.
 * Accepted because that is the harness's documented behaviour rather than something this
 * function adds, and because the alternative -- leaving a stubbed base URL installed for
 * the next file -- is the failure the harness exists to prevent.
 * @returns {Promise<void>} Resolves once nothing is rendered, no session is held, no
 *   bearer is attached and the transport is the real one again.
 */
export async function resetSessionAndTransport(): Promise<void> {
  aSessionWasSeeded = false;
  const [{ endAnySession }, { removeApiHarness }] = await Promise.all([
    import('./sessionHarness'),
    import('./apiHarness'),
  ]);
  endAnySession();
  removeApiHarness();
}

/**
 * Asserts that a field refuses more characters than its copybook declares.
 *
 * Purpose
 * -------
 * The 3270 terminal enforced a field's width in hardware: a `PIC X(8)` field accepted
 * eight characters and the ninth keystroke did nothing. `maxLength` is where that
 * constraint survives, and it is derived from the copybook `PICTURE` width rather than
 * chosen -- so the value a case passes is a citation, and the assertion is worth having
 * a name because a bare `toHaveAttribute('maxlength', '8')` reads as a magic number.
 *
 * Assumptions: the attribute is compared as TEXT, because that is what the DOM holds --
 * an attribute value is a string, and `8` and `'8'` are the same attribute.
 * @param {HTMLElement} field - The rendered input, from a query on the render result.
 * @param {number} declaredWidth - The copybook width the field is held to.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
export function expectMaxLength(field: HTMLElement, declaredWidth: number): void {
  expect(field).toHaveAttribute('maxlength', String(declaredWidth));
}

/**
 * Finds an element whose text is one catalogued string, character for character.
 *
 * Purpose
 * -------
 * Transformation rule T8 carries every user-visible string across from the baseline
 * unchanged, and `ui/src/messages/messages.ts` is the one place they live. A case
 * asserting on a sentence it retyped is asserting that the screen agrees with the TEST,
 * which a paraphrase in both places satisfies. Passing the catalog entry through here
 * asserts that the screen agrees with the CATALOG.
 *
 * ⚠️ Assumptions: internal whitespace is NOT collapsed, which is the whole reason this
 * wraps the query rather than a case calling `getByText` directly. Testing Library's
 * default normaliser collapses runs of whitespace to one space, and several baseline
 * strings carry doubled spaces that are content rather than formatting -- the row-24
 * legend in `app/bms/COUSR02.bms` separates its key labels with two spaces, and a
 * collapsing matcher accepts a screen that emits one. Boundary whitespace IS trimmed,
 * because that is introduced by the markup rather than by the string.
 *
 * Alternatives Considered: a snapshot of the rendered band. Rejected outright: a
 * paraphrased sentence makes a snapshot fail, the failure is resolved by re-recording
 * the snapshot, and the guarantee the catalog exists to provide is gone with one
 * keystroke. No snapshot testing is enabled in this package for that reason.
 * @param {string} expected - The catalogued string, taken from the message catalog and
 *   not retyped. For a fixed-width entry pass its `text` member, which is the content
 *   without the declared-width padding.
 * @returns {HTMLElement} The element carrying the string.
 * @throws {Error} If no element carries exactly that text, which Testing Library raises.
 */
export function expectVerbatimMessage(expected: string): HTMLElement {
  return screen.getByText(expected, {
    normalizer: getDefaultNormalizer({ trim: true, collapseWhitespace: false }),
  });
}

/**
 * One of the shell's three landmark zones.
 *
 * Assumptions: three members and not four. The shell renders a fourth zone -- the
 * message band, between the content and the footer -- and it is deliberately absent
 * here because it is not a landmark: `ui/src/layout/MessageBand.tsx` gives it `alert`
 * or `status` according to the SEVERITY of what it holds, so a locator for it would
 * have to be told the severity, which is the screen's data rather than the frame's
 * structure. A case reaching for the band queries it by the test identifier that module
 * publishes.
 */
export type ShellLandmark = 'titleBand' | 'screenBody' | 'keyLegend';

/**
 * Locates one of the shell's landmark zones by its accessible role.
 *
 * Purpose
 * -------
 * Every 3270 screen in the baseline shares three regions: the title band on rows 1 and
 * 2, the screen body, and the function-key legend on row 24. The shell renders them as
 * real landmarks -- antd's `Layout.Header` emits `<header>`, `Layout.Content` emits
 * `<main>` and `Layout.Footer` emits `<footer>`, inside a `Layout` that emits a plain
 * `<div>` rather than a sectioning element, so all three keep their implicit roles.
 *
 * Assumptions: the query is by ROLE rather than by test identifier, because the role is
 * the property a screen reader acts on and therefore the one worth regressing against.
 * A test identifier would pass just as well on a `<div>` that announces nothing, which
 * is precisely the change this locator should fail on.
 * @param {ShellLandmark} zone - Which zone to locate.
 * @returns {HTMLElement} The zone's element.
 * @throws {Error} If the zone is absent, which Testing Library raises. Absence usually
 *   means the subject was rendered without the shell around it.
 */
export function shellLandmark(zone: ShellLandmark): HTMLElement {
  if (zone === 'titleBand') {
    return screen.getByRole('banner');
  }
  if (zone === 'screenBody') {
    return screen.getByRole('main');
  }
  return screen.getByRole('contentinfo');
}
