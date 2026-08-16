/**
 * @file Pins that every production screen delegates its chrome to the one shell and supplies that
 * shell a server-derived paint instant.
 *
 * WHY this file exists — Refactoring Rationale: `ScreenHeader` documents one obligation on its
 * caller, that `now` is a SERVER-derived instant, and states plainly why the obligation is not
 * enforced by the type system: the component must stay renderable in isolation, so a required clock
 * prop would force every test that does not care about time to supply one. The consequence of
 * documenting rather than type-checking it was that all four production call sites omitted the prop
 * and the band read the browser's clock. A contract that a compiler cannot enforce and no test
 * asserts is a contract that will be broken again, so this file asserts it instead — which keeps the
 * prop optional for isolated rendering while making its ABSENCE from a real screen a failure.
 *
 * WHY : Refactoring Rationale: the assertions now read the DELEGATION rather than a per-screen
 * `<ScreenHeader` render, because the caller changed. `ui/src/layout/AppShell.tsx` is mounted exactly
 * once by `ui/src/App.tsx` and is the only module that composes the band, so a screen's obligation is
 * no longer to render it with a clock but to publish `now` through `useShellSlot`. Asserting the old
 * shape after that migration would fail every screen for doing the right thing, and — worse — would
 * pass a screen that reintroduced its own band. So the negative half is now asserted too: a screen
 * that composes a title band, a message band or a key legend of its own fails here, which is the
 * invariant that keeps the shell's stand-down condition for the keyboard sound.
 *
 * WHY : Alternatives Considered: rendering each screen and reading the displayed date. Rejected
 * because each screen needs its router, its authentication state and its API responses stubbed to
 * mount at all, so the case would fail for a dozen reasons unrelated to the clock and its failure
 * message would not name the defect. Reading the sources is narrow, and its failure names exactly the
 * screen and the missing call. `ui/src/layout/appShellIntegration.test.tsx` carries the rendered half.
 *
 * WHY : Assumptions: the sources are read from disk rather than imported, because the property under
 * test is syntactic — that a call appears in a module and a component does not — and is not observable
 * through any module's exported surface.
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
 * Every screen delivered under `ui/src/screens`.
 *
 * Assumptions: the list is written out rather than discovered by globbing. A glob would grow silently
 * as screens are added, which sounds desirable but means a NEW screen that forgot to delegate would
 * make this file fail for the first time during an unrelated change; naming them makes adding a screen
 * an explicit edit here. Screens still to be delivered are absent by construction and are added
 * alongside their own delegation.
 */
/*
 * WHY : ⚠️ Refactoring Rationale: the list covers all TEN authored screens where it covered four, and it
 *       had to grow at the same moment the supply route changed. Nine of the ten now DELEGATE the band to
 *       the mounted shell rather than painting it, so a gate that only knew the direct form would have
 *       reported nine screens as failing while every one of them was correct -- and a gate narrowed to
 *       the four that happened to keep working would have stopped watching six screens that had just
 *       been given a title band for the first time.
 */
const SCREENS = [
  'signon',
  'accountView',
  'accountUpdate',
  'cardList',
  'cardDetail',
  'cardUpdate',
  'transactionAdd',
  'authSummary',
  'refTypeList',
  'userUpdate',
] as const;

/** Directory holding the screen modules, relative to this file. */
const SCREENS_ROOT = join(import.meta.dirname, '..', 'screens');

/**
 * Chrome elements a screen must not compose for itself, with the zone each one paints.
 *
 * Assumptions: `MessageBand` is absent from this list even though the shell owns the row-23 line, and
 * the omission is deliberate. Two mapsets declare a SECOND, informational message field inside the
 * screen's own field area — `INFOMSG` at `POS=(22,23)` on `app/bms/COACTVW.bms` is the delivered case —
 * so a blanket prohibition would forbid a field the baseline paints. The row-23 line is covered
 * positively instead, by the `message:` delegation asserted below.
 */
const FORBIDDEN_CHROME = ['<ScreenHeader', '<PfKeyBar'] as const;

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
 * Reports whether one source line declares the screen component itself.
 *
 * Assumptions: matched on an exported function whose name ends in `Screen`, at zero indentation, which
 * is how all ten screen modules declare theirs -- nine as a named export and `refTypeList` as a default
 * one, so both spellings are admitted. Matching the component is what makes the ordering check below
 * compare two positions inside the same function body, and matching the `export` keyword as well as the
 * name is what keeps a local helper that happens to end in `Screen` from being mistaken for it.
 *
 * ⚠️ Refactoring Rationale: THREE spellings of this predicate reached this file under three names --
 * one requiring `\(\): ReactElement \{$` and a named export, one admitting a default export, one
 * spelled with `\w*` -- and only their union is correct against the delivered tree. The strictest form
 * misses `refTypeList`, which publishes its component as a DEFAULT export, and the return-type anchor
 * pins a signature this file has no business asserting. Naming the same question three ways also left
 * two callers reading names that no longer resolved, so the three are one predicate under one name.
 * @param {string} line - One line of a screen module's source.
 * @returns {boolean} `true` when the line declares the screen component.
 */
function isScreenComponentDeclaration(line: string): boolean {
  return /^export (?:default )?function [A-Za-z0-9_]*Screen\(/u.test(line);
}

/**
 * Reports whether one source line opens a top-level `if` inside whatever function encloses it.
 *
 * Assumptions: matched at exactly two spaces of indentation, which is the outermost body level of a
 * module-level function. A deeper `if` sits inside a callback or a nested block and cannot be an early
 * return from the enclosing function, so matching any indentation would find the wrong line.
 *
 * Refactoring Rationale: indentation alone is NOT sufficient to identify a COMPONENT-level conditional,
 * which is what the ordering assertion needs, and treating it as sufficient produced a false failure.
 * The card detail module gained module-level helper functions whose own guard clauses sit at exactly
 * two spaces, so the first match in the file stopped being the component's first early return and the
 * assertion compared the hook's line against an `if` in an unrelated function two hundred lines
 * earlier. The callers therefore restrict the search to the component's own body; this predicate is
 * back to answering only the question its name asks.
 * @param {string} line - One line of a screen module's source.
 * @returns {boolean} `true` when the line opens a conditional at its function's outermost body level.
 */
function isComponentLevelConditional(line: string): boolean {
  return /^ {2}if \(/u.test(line);
}

/**
 * Locates a screen's first early return, searching only inside the component.
 *
 * Refactoring Rationale: the search is bounded by the component declaration, where it used to run over
 * the whole file. Unbounded, it matched the first two-space `if (` anywhere — including inside a
 * module-level helper, which is neither component-level nor an early return — so a screen with a
 * helper above its component failed a rules-of-hooks assertion naming a hook that helper never calls.
 * Three modules carry comments recording that they avoided guard clauses purely to keep this search
 * honest; bounding it removes the constraint instead of asking authors to work around it.
 * @param {readonly string[]} lines - The module's source lines.
 * @returns {number} Index of the first component-level conditional, or `-1` when the component has
 *   none or the component declaration cannot be found.
 */
function firstEarlyReturnIndex(lines: readonly string[]): number {
  const componentLine = lines.findIndex(isScreenComponentDeclaration);
  if (componentLine === -1) {
    return -1;
  }
  const offset = lines.slice(componentLine).findIndex(isComponentLevelConditional);
  return offset === -1 ? -1 : componentLine + offset;
}

/**
 * Asserts every production screen delegates its identity and paint instant to the shell.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenDelegatesAnInstant(): void {
  for (const screen of SCREENS) {
    const source = sourceOf(screen);
    /*
     * WHY : ⚠️ Refactoring Rationale: this asserted `(paints its own band) OR (delegates it)`, with a
     *       note recording that sign-on sits outside the authenticated layout and therefore composes
     *       `ScreenHeader` for itself. That exemption no longer exists -- sign-on delegates like the
     *       other nine, and it is the shell's own route that decides which zones it paints -- so the
     *       permissive arm now permits precisely the arrangement the `noScreenComposesItsOwnChrome`
     *       case below FORBIDS. Two cases in one file, one admitting a shape and one refusing it, means
     *       one of them is dead; the delegation is asserted as the single supply route.
     */
    expect(source, `${screen} must delegate its chrome through useShellSlot`).toContain(
      'useShellSlot(',
    );
    expect(source, `${screen} must delegate its screen identity`).toContain('transactionId:');
    expect(source, `${screen} must delegate a paint instant`).toContain('now: paintedAt');
  }
}

/**
 * Asserts every production screen delegates a row-23 message and a key legend.
 *
 * Assumptions: the key delegation is what makes the shell stand its own sign-off key down, so its
 * absence is a keyboard defect and not merely a missing legend — `ui/src/layout/AppShell.tsx` renders
 * its own sign-off control beside the legend and every screen installs a document listener of its own,
 * so the publication is what keeps exactly one listener installed. Asserting the publication is
 * therefore asserting single ownership at the source level.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenDelegatesItsMessageAndKeys(): void {
  for (const screen of SCREENS) {
    const source = sourceOf(screen);
    expect(source, `${screen} must delegate its row-23 message`).toContain('message: {');
    expect(source, `${screen} must delegate its resolved key bindings`).toContain('pfKeys: {');
    expect(source, `${screen} must hand over its own usePfKeys result`).toContain(
      'onInvoke: invoke',
    );
  }
}

/**
 * Asserts no screen composes chrome the single shell owns.
 * @returns {void} Nothing; assertions raise on failure.
 */
function noScreenComposesItsOwnChrome(): void {
  for (const screen of SCREENS) {
    const source = sourceOf(screen);
    for (const element of FORBIDDEN_CHROME) {
      expect(source, `${screen} must delegate ${element} rather than compose it`).not.toContain(
        element,
      );
    }
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
    //   `now: new Date()` or `now: dayjs()` would satisfy the positive half while reintroducing
    //   exactly the divergence this closes, so the negative half is what makes the pair meaningful.
    expect(source, `${screen} must not read the browser clock`).not.toContain('now: new Date()');
    expect(source, `${screen} must not read the browser clock`).not.toContain('now: dayjs()');
  }
}

/**
 * Asserts the hook is called above every early return, as the rules of hooks require.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenCallsTheHookUnconditionally(): void {
  // WHY : the rules of hooks require an unconditional call site, and four of these screens return
  //       early for a missing selector, a loading state or an abend. A hook call placed after one of
  //       those returns is a runtime fault React reports as a changed hook order, which surfaces as a
  //       broken screen rather than a wrong date -- so the ordering is worth pinning beside the call
  //       it exists to supply.
  for (const screen of SCREENS) {
    const lines = sourceOf(screen).split('\n');
    const hookLine = lines.findIndex(isHookCall);
    expect(hookLine, `${screen} must call useServerInstant`).toBeGreaterThan(-1);
    // ⚠️ Refactoring Rationale: the early return is searched for from the COMPONENT declaration
    //   onwards, where it used to be searched for from the top of the file. `isComponentLevelConditional`
    //   matches an `if` at two spaces of indentation, which is component-body level -- but it is also
    //   the indentation of an `if` inside any other top-level function in the same module, and several of
    //   these screens declare helper functions above their component. So the whole-file search found a
    //   conditional in a helper, compared it against a hook call that legitimately sits below it, and
    //   reported a rules-of-hooks violation for correct code: `accountView` failed with `expected 913 to
    //   be less than 411`, where 411 is an `if` inside a helper and 913 is the component's own hook call.
    //   Anchoring the search at the component makes the two indices comparable, which is what the
    //   assertion assumed all along.
    const componentLine = lines.findIndex(isScreenComponentDeclaration);
    expect(componentLine, `${screen} must declare a screen component`).toBeGreaterThan(-1);
    const withinComponent = lines.slice(componentLine);
    const earlyReturnOffset = withinComponent.findIndex(isComponentLevelConditional);
    if (earlyReturnOffset !== -1) {
      expect(
        hookLine,
        `${screen} must call useServerInstant above its first early return`,
      ).toBeLessThan(componentLine + earlyReturnOffset);
    }
  }
}

/**
 * Asserts the delegation itself is published above every early return.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenDelegatesUnconditionally(): void {
  // WHY : `useShellSlot` is a hook, so it is bound by the same rule as the clock above. A screen that
  //       published only on its populated path would change hook order between renders; the two card
  //       screens instead publish an EMPTY key list in their erased states, which keeps the call
  //       unconditional while painting no zone.
  for (const screen of SCREENS) {
    const lines = sourceOf(screen).split('\n');
    const publishLine = lines.findIndex(
      /**
       * Reports whether one line opens the delegation call.
       * @param {string} line - One line of a screen module's source.
       * @returns {boolean} `true` when the line calls the delegation hook.
       */
      (line: string): boolean => line.includes('useShellSlot('),
    );
    expect(publishLine, `${screen} must call useShellSlot`).toBeGreaterThan(-1);
    const firstEarlyReturn = firstEarlyReturnIndex(lines);
    if (firstEarlyReturn !== -1) {
      expect(
        publishLine,
        `${screen} must call useShellSlot above its first early return`,
      ).toBeLessThan(firstEarlyReturn);
    }
  }
}

/**
 * Registers the header-clock and chrome-delegation contract cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function headerClockCases(): void {
  it('is delegated a paint instant by every production screen', everyScreenDelegatesAnInstant);
  it('is delegated a message and a key legend too', everyScreenDelegatesItsMessageAndKeys);
  it('is the only composer of the header and the legend', noScreenComposesItsOwnChrome);
  it('takes that instant from the server clock', everyScreenUsesTheServerClock);
  it('reads the clock unconditionally in every screen', everyScreenCallsTheHookUnconditionally);
  it('is delegated to unconditionally in every screen', everyScreenDelegatesUnconditionally);
}

describe('AppShell delegation contract', headerClockCases);
