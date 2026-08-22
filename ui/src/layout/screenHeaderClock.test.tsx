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
 * WHY : Refactoring Rationale: the assertions read the DELEGATION rather than a per-screen
 * `<ScreenHeader` render, because the caller changed. `ui/src/layout/AppShell.tsx` is mounted exactly
 * once by `ui/src/App.tsx` and is the only module that composes the band, so a screen's obligation is
 * no longer to render it with a clock but to publish `now` through `useShellSlot`. Asserting the old
 * shape after that migration would fail every screen for doing the right thing, and — worse — would
 * pass a screen that reintroduced its own band. So the negative half is asserted too: a screen that
 * composes a title band, a row-23 message line or a key legend of its own fails here, which is what
 * keeps each zone painted exactly once.
 *
 * WHY : ⚠️ Refactoring Rationale: the population is DISCOVERED from the filesystem, where it was a
 * hand-written list of ten names, and every case below now covers all twenty-one authored screen
 * modules. The list was defended on a real argument — that naming the screens makes adding one an
 * explicit edit here, so a new screen cannot fail this file during an unrelated change — and the
 * argument is sound about the direction it considered and silent about the one that bit. What it
 * actually produced was a gate that stopped WATCHING eleven screens: three of them composed their own
 * title band and key legend inside an already-mounted shell, and a fourth delegated no paint instant
 * at all and so painted the operator's clock where the other twenty paint the region's. None of the
 * four was on the list, so all four passed. A census whose coverage a later change decides is not a
 * census, and discovery is the only form of it that cannot silently shrink. The cost the list was
 * bought with is paid instead by the two exemptions below, which are asserted to be EXACTLY the
 * measured set — so a new screen that quietly drops the clock fails here rather than joining them.
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

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Directory holding the screen modules, resolved from this file rather than from the runner's cwd. */
const SCREENS_ROOT = join(import.meta.dirname, '..', 'screens');

/**
 * How many screen modules this migration has delivered.
 *
 * Assumptions: this is a FLOOR on the discovered population rather than the population itself, and it
 * is the one figure in this file a change has to touch deliberately. Discovery is what makes the cases
 * below immune to a forgotten edit; its single blind spot is that deleting a screen directory outright
 * would leave every case green while measuring less, so the count is pinned. Adding a screen still
 * needs no edit here — it simply has to satisfy the contract.
 */
const DELIVERED_SCREEN_COUNT = 21;

/**
 * Screens that legitimately delegate no paint instant, with the reason each one does not.
 *
 * ⚠️ Assumptions: `authDetail` is the whole of this set, and the exemption is a fidelity requirement
 * rather than an oversight. Its contract is the one screen contract that publishes `currentDate` and
 * `currentTime` as members — `cbl/COPAUS1C.cbl` populates those header slots itself — and the screen
 * renders the service's own values in its record block. Handing the shell an instant as well would
 * paint two clocks on one screen, disagreeing with each other by however far the two readings differ.
 *
 * Assumptions: the set is asserted to be exactly this, not merely to contain it. An exemption list a
 * later screen can join by omission is the same defect as the hand-written population this file used
 * to carry, one level down — so {@link theClockExemptionsAreExactlyTheMeasuredSet} fails if any other
 * screen stops delegating an instant, and fails again if this one starts.
 */
const CLOCK_EXEMPT_SCREENS: ReadonlySet<string> = new Set(['authDetail']);

/**
 * Chrome elements a screen must not compose for itself, with the zone each one paints.
 *
 * Assumptions: `MessageBand` is absent from this list even though the shell owns the row-23 line, and
 * the omission is deliberate. Five mapsets declare a SECOND, informational message field inside the
 * screen's own field area — `INFOMSG` at `POS=(22,23)` on `app/bms/COACTVW.bms` is the plainest case —
 * so a blanket prohibition would forbid a field the baseline paints. That band is covered separately
 * and positively by {@link everyScreenBandIsTheInformationLine}, which admits exactly the row-22 form
 * and refuses a row-23 one; the row-23 line itself is covered by the `message:` delegation below.
 */
const FORBIDDEN_CHROME = ['<ScreenHeader', '<PfKeyBar'] as const;

/**
 * Matches a `MessageBand` JSX element, and only that.
 *
 * ⚠️ Assumptions: the trailing character class is what makes this a measurement rather than a guess.
 * Fifteen of the twenty-one screens hold the substring `<MessageBand` with no element in sight,
 * because `useState<MessageBandSeverity>('error')` contains it — so a `toContain('<MessageBand')`
 * check would report fifteen screens as composing a band they do not compose, and one relaxed to
 * accommodate that would stop seeing the real ones.
 */
const MESSAGE_BAND_ELEMENT_PATTERN = /<MessageBand[\s/>]/u;

/**
 * Matches the dispatcher delegation, in either spelling the delivered screens use.
 *
 * Assumptions: both `onInvoke: invoke` and `onInvoke: pfKeys.invoke` are admitted, because a screen
 * may destructure the hook's result or hold it whole and both are in the tree. What the alternation
 * refuses is the shape that matters: an inline arrow or a locally-written handler in this position
 * would mean the legend's controls and the keyboard reached different code, which is the one way a
 * delegated legend can come to disagree with the keystrokes it advertises.
 */
const DISPATCHER_DELEGATION_PATTERN = /onInvoke: (?:invoke|\w+\.invoke)\b/u;

/** Matches the server-clock hook call under any local name the screens bind it to. */
const SERVER_CLOCK_CALL_PATTERN = /const \w+ = useServerInstant\(\)/u;

/**
 * Matches the paint instant reaching the shell, in either spelling the delivered screens use.
 *
 * Assumptions: `now: <identifier>` and the conditional `{ now }` spread are both admitted, because a
 * screen whose hook may answer nothing legitimately omits the member rather than publishing an
 * undefined one. What this refuses to accept is the bare word, which is why it is a pattern and not a
 * substring: `now` occurs inside ordinary prose and inside `known`, so a `toContain('now')` check
 * would be satisfied by any screen that merely mentioned it.
 */
const INSTANT_DELEGATION_PATTERN = /now: \w+|\{ now \}/u;

/**
 * Lists every screen directory that publishes a module.
 *
 * Assumptions: a directory qualifies only when it holds `index.tsx`, which is the entry-point shape
 * every authored screen uses. This is deliberately the same rule `ui/src/routes/routeCensus.test.ts`
 * applies, so the two censuses measure one population: a screen that is routed but composes its own
 * chrome, or delegates correctly and is unreachable, fails in exactly one of the two files.
 * @returns {readonly string[]} Screen directory names, in directory order.
 */
function authoredScreens(): readonly string[] {
  return readdirSync(SCREENS_ROOT, { withFileTypes: true })
    .filter(
      /**
       * Keeps the entries that are directories holding a screen module.
       * @param {{ name: string; isDirectory: () => boolean }} entry - One directory entry.
       * @returns {boolean} `true` when the entry is a screen module directory.
       */
      (entry) => entry.isDirectory() && existsSync(join(SCREENS_ROOT, entry.name, 'index.tsx')),
    )
    .map(
      /**
       * Reduces a directory entry to its name.
       * @param {{ name: string }} entry - One directory entry.
       * @returns {string} The directory name.
       */
      (entry) => entry.name,
    );
}

/**
 * Reads one screen module's source with its commentary removed.
 *
 * ⚠️ Assumptions: the commentary is stripped before anything is asserted, and every case here reads
 * this rather than the raw text. These modules document their own delegation at length, so a screen
 * that correctly delegates its legend also mentions the component it no longer renders — and a
 * textual prohibition against the raw source would fail a screen for EXPLAINING that it complies.
 * The inverse is worse: a positive check satisfied by a sentence in a docstring would pass a screen
 * that delegates nothing.
 *
 * Assumptions: only a line whose first non-space characters are `//` is treated as a line comment, so
 * a `//` inside a string literal is left alone. Block comments are removed wherever they appear, which
 * covers the brace-wrapped form these modules use to comment inside JSX as well as ordinary ones.
 * @param {string} screen - Directory name of the screen under `ui/src/screens`.
 * @returns {string} The module source with block and whole-line comments removed.
 */
function codeOf(screen: string): string {
  const source = readFileSync(join(SCREENS_ROOT, screen, 'index.tsx'), 'utf8');
  return source.replace(/\/\*[\s\S]*?\*\//gu, '').replace(/^[ \t]*\/\/.*$/gmu, '');
}

/**
 * Reports whether one source line is the server-clock hook call.
 * @param {string} line - One line of a screen module's source.
 * @returns {boolean} `true` when the line declares the paint instant.
 */
function isHookCall(line: string): boolean {
  return SERVER_CLOCK_CALL_PATTERN.test(line);
}

/**
 * Reports whether one source line declares the screen component itself.
 *
 * Assumptions: matched on an exported function whose name ends in `Screen`, at zero indentation, which
 * is how every screen module declares its component -- most as a named export and `refTypeList` as a
 * default one, so both spellings are admitted. Matching the component is what makes the ordering check
 * below compare two positions inside the same function body, and matching the `export` keyword as well
 * as the name is what keeps a local helper that happens to end in `Screen` from being mistaken for it.
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
 * The discovered population is the whole delivered set, so no case below measures a subset.
 *
 * ⚠️ Assumptions: this case runs FIRST and asserts the count, because every other case in this file
 * iterates the population — so a resolution fault that returned an empty list, or a rule that quietly
 * stopped recognising a directory, would let the entire file pass while asserting nothing about
 * anything. That is the precise failure mode a census exists to prevent, and it is invisible from a
 * green result.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theCensusCoversEveryDeliveredScreen(): void {
  const screens = authoredScreens();

  expect(screens.length, 'no screen modules were discovered at all').toBeGreaterThan(0);
  expect(
    screens.length,
    `${String(screens.length)} screen modules were discovered where at least ${String(DELIVERED_SCREEN_COUNT)} are delivered`,
  ).toBeGreaterThanOrEqual(DELIVERED_SCREEN_COUNT);
}

/**
 * Asserts every production screen delegates its identity, its message and its keys to the shell.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenDelegatesItsChrome(): void {
  for (const screen of authoredScreens()) {
    const code = codeOf(screen);
    /*
     * WHY : ⚠️ Refactoring Rationale: this asserted `(paints its own band) OR (delegates it)`, with a
     *       note recording that sign-on sits outside the authenticated layout and therefore composes
     *       `ScreenHeader` for itself. That exemption no longer exists -- sign-on delegates like every
     *       other screen, and it is the shell's own route that decides which zones it paints -- so the
     *       permissive arm now permits precisely the arrangement the `noScreenComposesItsOwnChrome`
     *       case below FORBIDS. Two cases in one file, one admitting a shape and one refusing it, means
     *       one of them is dead; the delegation is asserted as the single supply route.
     */
    expect(code, `${screen} must delegate its chrome through useShellSlot`).toContain(
      'useShellSlot(',
    );
    expect(code, `${screen} must delegate its screen identity`).toContain('transactionId:');
    expect(code, `${screen} must delegate its row-23 message`).toContain('message: {');
    expect(code, `${screen} must delegate its resolved key bindings`).toContain('pfKeys: {');
    expect(
      DISPATCHER_DELEGATION_PATTERN.test(code),
      `${screen} must hand over its own usePfKeys dispatcher, not a handler of its making`,
    ).toBe(true);
  }
}

/**
 * Asserts no screen composes chrome the single shell owns.
 * @returns {void} Nothing; assertions raise on failure.
 */
function noScreenComposesItsOwnChrome(): void {
  for (const screen of authoredScreens()) {
    const code = codeOf(screen);
    for (const element of FORBIDDEN_CHROME) {
      expect(code, `${screen} must delegate ${element} rather than compose it`).not.toContain(
        element,
      );
    }
  }
}

/**
 * Asserts any band a screen does render is the row-22 information line and not the row-23 one.
 *
 * ⚠️ Assumptions: the rule is that a rendered band must NAME the line it stands in, and that is what
 * separates a faithful screen from a broken one rather than any count of elements. The shell paints a
 * zone if and only if it is delegated, so a screen publishing `message:` and also rendering an
 * unqualified band puts a row-23 line inside its own field area while the zone the mapset declares at
 * `POS=(23,1)` stays empty — the message appears above the informational line instead of below it, and
 * the frame's own line is blank. Five screens legitimately render the row-22 `INFOMSG` field, and each
 * one says so.
 *
 * Assumptions: both `line="information"` and `channel="information"` are admitted, because
 * `ui/src/layout/MessageBand.tsx` publishes two props that select the row and the delivered screens use
 * one each. Admitting only one would fail a compliant screen for choosing the other prop.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenBandIsTheInformationLine(): void {
  for (const screen of authoredScreens()) {
    const code = codeOf(screen);
    if (!MESSAGE_BAND_ELEMENT_PATTERN.test(code)) {
      continue;
    }
    expect(
      /line="information"|channel="information"/u.test(code),
      `${screen} renders a MessageBand, so it must name it the row-22 information line; the row-23 line is the shell's`,
    ).toBe(true);
  }
}

/**
 * Asserts the paint instant comes from the server clock and not from the browser clock.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenUsesTheServerClock(): void {
  for (const screen of authoredScreens()) {
    const code = codeOf(screen);
    if (CLOCK_EXEMPT_SCREENS.has(screen)) {
      continue;
    }
    expect(
      SERVER_CLOCK_CALL_PATTERN.test(code),
      `${screen} must read the server clock through useServerInstant`,
    ).toBe(true);
    expect(
      INSTANT_DELEGATION_PATTERN.test(code),
      `${screen} must delegate that instant to the shell`,
    ).toBe(true);
    // Assumptions: the browser clock is asserted ABSENT as well as the server clock present. Passing
    //   `now: new Date()` or `now: dayjs()` would satisfy the positive half while reintroducing
    //   exactly the divergence this closes, so the negative half is what makes the pair meaningful.
    expect(code, `${screen} must not read the browser clock`).not.toContain('now: new Date()');
    expect(code, `${screen} must not read the browser clock`).not.toContain('now: dayjs()');
  }
}

/**
 * Asserts the clock exemptions are exactly the screens that measurably take one.
 *
 * ⚠️ Assumptions: this is the case that keeps {@link CLOCK_EXEMPT_SCREENS} from becoming the
 * hand-written population this file used to carry. Both directions are checked: a screen that stops
 * delegating an instant fails because it is not named, and a named screen that starts delegating one
 * fails because the exemption has gone stale — so the list cannot drift in either direction without
 * somebody reading the reason it records.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theClockExemptionsAreExactlyTheMeasuredSet(): void {
  const measured = authoredScreens().filter(
    /**
     * Keeps the screens that delegate no paint instant.
     * @param {string} screen - One screen directory name.
     * @returns {boolean} `true` when the screen calls no server-clock hook.
     */
    (screen) => !SERVER_CLOCK_CALL_PATTERN.test(codeOf(screen)),
  );

  expect(
    [...measured].sort(),
    'the clock exemption list and the screens that take one have diverged',
  ).toEqual([...CLOCK_EXEMPT_SCREENS].sort());
}

/**
 * Asserts the clock hook is called above every early return, as the rules of hooks require.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenCallsTheHookUnconditionally(): void {
  // WHY : the rules of hooks require an unconditional call site, and several of these screens return
  //       early for a missing selector, a loading state or an abend. A hook call placed after one of
  //       those returns is a runtime fault React reports as a changed hook order, which surfaces as a
  //       broken screen rather than a wrong date -- so the ordering is worth pinning beside the call
  //       it exists to supply.
  for (const screen of authoredScreens()) {
    if (CLOCK_EXEMPT_SCREENS.has(screen)) {
      continue;
    }
    const lines = codeOf(screen).split('\n');
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
  //       published only on its populated path would change hook order between renders; the screens
  //       with erased states instead publish an EMPTY key list there, which keeps the call
  //       unconditional while painting no zone.
  for (const screen of authoredScreens()) {
    const lines = codeOf(screen).split('\n');
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
  it('covers every delivered screen module', theCensusCoversEveryDeliveredScreen);
  it('is delegated identity, message and keys by every screen', everyScreenDelegatesItsChrome);
  it('is the only composer of the header and the legend', noScreenComposesItsOwnChrome);
  it('leaves screens only the row-22 information line', everyScreenBandIsTheInformationLine);
  it('takes its paint instant from the server clock', everyScreenUsesTheServerClock);
  it(
    'exempts exactly the screens that render their own',
    theClockExemptionsAreExactlyTheMeasuredSet,
  );
  it('reads the clock unconditionally in every screen', everyScreenCallsTheHookUnconditionally);
  it('is delegated to unconditionally in every screen', everyScreenDelegatesUnconditionally);
}

describe('AppShell delegation contract', headerClockCases);
