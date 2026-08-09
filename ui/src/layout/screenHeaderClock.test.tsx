/**
 * @file Pins that every production screen supplies the header band a server-derived paint instant.
 *
 * WHY this file exists — Refactoring Rationale: `ScreenHeader` documents one obligation on its
 * callers, that they pass `now` a SERVER-derived instant, and states plainly why the obligation is
 * not enforced by the type system: the component must stay renderable in isolation, so a required
 * clock prop would force every test that does not care about time to supply one. The consequence of
 * documenting rather than type-checking it was that all four production call sites omitted the prop
 * and the band read the browser's clock. A contract that a compiler cannot enforce and no test
 * asserts is a contract that will be broken again, so this file asserts it instead — which keeps the
 * prop optional for isolated rendering while making its ABSENCE from a real screen a failure.
 *
 * WHY : Alternatives Considered: rendering each screen and reading the displayed date. Rejected
 * because each screen needs its router, its authentication state and its API responses stubbed to
 * mount at all, so the case would fail for a dozen reasons unrelated to the clock and its failure
 * message would not name the defect. Reading the sources is narrow, and its failure names exactly the
 * screen and the missing prop.
 *
 * WHY : Assumptions: the sources are read from disk rather than imported, because the property under
 * test is syntactic — that a prop appears at a call site — and is not observable through any module's
 * exported surface.
 *
 * WHY : Assumptions: each case loops over the screens internally instead of using `describe.each`.
 * The parameterised form needs an inline callback per screen, and `eslint.config.js` requires a JSDoc
 * block on every function including those handed to a test registrar; looping inside one named case
 * keeps the failure message screen-specific without one documented wrapper per screen.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/**
 * Every screen that composes the header band in production.
 *
 * Assumptions: the list is written out rather than discovered by globbing for `<ScreenHeader`. A glob
 * would grow silently as screens are added, which sounds desirable but means a NEW screen that forgot
 * the prop would make this file fail for the first time during an unrelated change; naming them makes
 * adding a screen an explicit edit here. Screens still to be delivered are absent by construction and
 * are added alongside their own call site.
 */
const SCREENS = ['signon', 'cardList', 'cardDetail', 'cardUpdate'] as const;

/** Directory holding the screen modules, relative to this file. */
const SCREENS_ROOT = join(import.meta.dirname, '..', 'screens');

/**
 * Reads one screen module's source.
 * @param {string} screen - Directory name of the screen under `ui/src/screens`.
 * @returns {string} The module source.
 */
function sourceOf(screen: string): string {
  return readFileSync(join(SCREENS_ROOT, screen, 'index.tsx'), 'utf8');
}

/**
 * Reports whether one source line is the server-clock hook call.
 * @param {string} line - One line of a screen module's source.
 * @returns {boolean} `true` when the line declares the paint instant.
 */
function isHookCall(line: string): boolean {
  return line.includes('const paintedAt = useServerInstant()');
}

/**
 * Reports whether one source line opens a component-level `if`, which precedes an early return.
 *
 * Assumptions: matched at exactly two spaces of indentation, which is component-body level. A deeper
 * `if` sits inside a callback or a nested block and is not an early return from the component, so
 * matching any indentation would find the wrong line and make the ordering assertion meaningless.
 * @param {string} line - One line of a screen module's source.
 * @returns {boolean} `true` when the line opens a component-level conditional.
 */
function isComponentLevelConditional(line: string): boolean {
  return /^ {2}if \(/u.test(line);
}

/**
 * Asserts every production screen passes the band a paint instant.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenSuppliesAnInstant(): void {
  for (const screen of SCREENS) {
    const source = sourceOf(screen);
    expect(source, `${screen} must compose ScreenHeader`).toContain('<ScreenHeader');
    expect(source, `${screen} must pass now= to ScreenHeader`).toContain('now={paintedAt}');
  }
}

/**
 * Asserts that instant comes from the server clock and not from the browser clock.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenUsesTheServerClock(): void {
  for (const screen of SCREENS) {
    const source = sourceOf(screen);
    expect(source, `${screen} must read the server clock`).toContain(
      'const paintedAt = useServerInstant();',
    );
    // Assumptions: the browser clock is asserted ABSENT as well as the server clock present. Passing
    //   `now={new Date()}` or `now={dayjs()}` would satisfy the positive half while reintroducing
    //   exactly the divergence this closes, so the negative half is what makes the pair meaningful.
    expect(source, `${screen} must not read the browser clock`).not.toContain('now={new Date()');
    expect(source, `${screen} must not read the browser clock`).not.toContain('now={dayjs()');
  }
}

/**
 * Asserts the hook is called above every early return, as the rules of hooks require.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenCallsTheHookUnconditionally(): void {
  // WHY : the rules of hooks require an unconditional call site, and two of these screens return
  //       early for a missing selector and for a loading state. A hook call placed after one of those
  //       returns is a runtime fault React reports as a changed hook order, which surfaces as a broken
  //       screen rather than a wrong date -- so the ordering is worth pinning beside the prop it
  //       exists to supply.
  for (const screen of SCREENS) {
    const lines = sourceOf(screen).split('\n');
    const hookLine = lines.findIndex(isHookCall);
    expect(hookLine, `${screen} must call useServerInstant`).toBeGreaterThan(-1);
    const firstEarlyReturn = lines.findIndex(isComponentLevelConditional);
    if (firstEarlyReturn !== -1) {
      expect(
        hookLine,
        `${screen} must call useServerInstant above its first early return`,
      ).toBeLessThan(firstEarlyReturn);
    }
  }
}

/**
 * Registers the header-clock contract cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function headerClockCases(): void {
  it('is supplied a paint instant by every production screen', everyScreenSuppliesAnInstant);
  it('takes that instant from the server clock', everyScreenUsesTheServerClock);
  it('reads the clock unconditionally in every screen', everyScreenCallsTheHookUnconditionally);
}

describe('ScreenHeader paint instant', headerClockCases);
