import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Assumptions: Testing Library renders into document.body, so cleaning after
// every test prevents a previous route or alert from satisfying the next
// assertion by accident.
afterEach(cleanup);

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
  return match[1] === "min"
    ? window.innerWidth >= threshold
    : window.innerWidth <= threshold;
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
  Object.defineProperty(window, "matchMedia", {
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

installMatchMedia();
