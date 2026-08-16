/**
 * @file Pins that every authored screen module is reachable from the browser route table.
 *
 * Purpose
 * -------
 * `ui/src/router.tsx` is the migrated form of the `EXEC CICS XCTL` reachability graph: a screen the
 * table does not name is the browser equivalent of a program with no `TRANSACTION` stanza in
 * `app/csd/CARDDEMO.CSD`, and neither one can be entered. This file asserts the census — that every
 * directory under `ui/src/screens` which publishes an `index.tsx` is both IMPORTED by the route
 * table and MOUNTED as the element of a route inside it — and that no route mounts a screen
 * directory that does not exist.
 *
 * Why this file exists — Refactoring Rationale: five screen modules were authored and never
 * mounted, and the gap was invisible from both directions. A screen compiles, type-checks and lints
 * while unreachable, because nothing in a module's own text depends on being imported; and a route
 * table cannot assert what is absent from it, because an absent route is simply a table with fewer
 * entries. `ui/src/screens/accountView/index.tsx` was the clearest case: its own contract stated
 * that the router mounts it at `/account/view` and that the route was live, while nothing in the
 * repository imported it — so the documentation and the code disagreed and no gate could tell. This
 * census closes the CLASS of defect rather than the five instances, which is why it discovers the
 * screens from the filesystem instead of listing them.
 *
 * Alternatives Considered: rendering `CardDemoRouter` at each expected path and asserting the screen
 * appears. Rejected on what such a case would actually measure. Every screen needs its
 * authentication guard satisfied, its API responses stubbed and its lazy chunk resolved before it
 * mounts at all, so a failure would name a missing token or an unstubbed request far more often than
 * a missing route, and the one property under test here — that the table names the module — would be
 * the least likely explanation of a red result. Reading the table's source is narrow, and its
 * failure names the screen and the direction of the gap.
 *
 * Alternatives Considered: listing the screens in this file, as `ui/src/layout/screenHeaderClock.test.tsx`
 * deliberately does for the header-clock contract. Rejected here because the two cases differ in
 * which direction the risk runs. That file guards a contract a NEW screen might not satisfy, so a
 * hand-written list is a feature: adding a screen becomes an explicit edit rather than a surprise
 * failure. This file guards a contract a new screen must satisfy by construction — being reachable —
 * and a hand-written list would have to be extended by the same change that forgot the route, so it
 * could never have caught the five it exists for.
 *
 * Assumptions: the census reads sources rather than importing modules, because the property under
 * test is syntactic. Whether a module appears in an import specifier and whether an identifier
 * appears as a route element are both facts about the text of `ui/src/router.tsx`, and neither is
 * observable through anything that module exports — it publishes one component and no route list.
 */

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Directory holding the screen modules, resolved from this file rather than from the runner's cwd. */
const SCREENS_ROOT = join(import.meta.dirname, '..', 'screens');

/** The route table's own source, which is the single artifact this census measures. */
const ROUTER_SOURCE = readFileSync(join(import.meta.dirname, '..', 'router.tsx'), 'utf8');

/**
 * The region of the route table between the `Routes` element's tags.
 *
 * Assumptions: "mounted" is asserted against this slice and not against the whole file, because an
 * identifier can appear in a module while being mounted nowhere — a lazy adapter is declared above
 * this region and referenced there, and a declaration alone is exactly the half-finished state the
 * five unmounted screens were in. Slicing on the element's tags rather than on a line number keeps
 * the region correct as the table grows.
 */
const ROUTES_REGION = ROUTER_SOURCE.slice(
  ROUTER_SOURCE.indexOf('<Routes>'),
  ROUTER_SOURCE.indexOf('</Routes>'),
);

/**
 * Matches one lazily-loaded screen adapter and captures the identifier it binds.
 *
 * Assumptions: the adapter's body is matched non-greedily up to the closing `\n);` that Prettier
 * puts on every one of them, so a file holding several adapters yields one match each rather than
 * one match spanning all of them.
 */
const LAZY_ADAPTER_PATTERN = /const (\w+) = lazy\(([\s\S]*?)\n\);/gu;

/** Matches one eagerly-imported screen and captures both the identifier and the screen directory. */
const EAGER_SCREEN_IMPORT_PATTERN = /import \{ (\w+) \} from '\.\/screens\/(\w+)';/gu;

/** Matches any reference to a screen directory, in either import form, and captures the directory. */
const SCREEN_SPECIFIER_PATTERN = /'\.\/screens\/(\w+)'/gu;

/**
 * Lists every screen directory that publishes a module.
 *
 * Assumptions: a directory qualifies only when it holds `index.tsx`, which is the entry-point shape
 * every authored screen uses and the specifier form the route table imports. A directory holding
 * only a test file or only a helper is therefore not a screen and is not required to be routed.
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
 * Resolves the identifiers the route table binds to one screen directory.
 *
 * Both import forms are accepted, because the table legitimately uses both: sign-on is imported
 * eagerly, being the entry screen an operator reaches before any chunk has loaded, and every other
 * screen is behind a `lazy` adapter so its chunk is fetched at navigation time.
 * @param {string} screen - A screen directory name under `ui/src/screens`.
 * @returns {readonly string[]} Every identifier bound to that module, which is empty when the route
 *   table does not import it at all.
 */
function identifiersFor(screen: string): readonly string[] {
  const specifier = `'./screens/${screen}'`;
  const found: string[] = [];

  for (const match of ROUTER_SOURCE.matchAll(LAZY_ADAPTER_PATTERN)) {
    const [, identifier = '', body = ''] = match;
    if (body.includes(specifier)) {
      found.push(identifier);
    }
  }

  for (const match of ROUTER_SOURCE.matchAll(EAGER_SCREEN_IMPORT_PATTERN)) {
    const [, identifier = '', imported = ''] = match;
    if (imported === screen) {
      found.push(identifier);
    }
  }

  return found;
}

/**
 * Every screen directory the route table names, in either import form.
 * @returns {ReadonlySet<string>} The referenced directory names, without duplicates.
 */
function referencedScreens(): ReadonlySet<string> {
  const referenced = new Set<string>();

  for (const match of ROUTER_SOURCE.matchAll(SCREEN_SPECIFIER_PATTERN)) {
    const [, screen = ''] = match;
    referenced.add(screen);
  }

  return referenced;
}

/**
 * Every authored screen module is imported by the route table.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenIsImported(): void {
  const screens = authoredScreens();

  // Assumptions: the population is asserted non-empty first. Every case below iterates it, so a
  //   resolution fault that produced an empty list would let the whole file pass while measuring
  //   nothing -- which is the failure mode a census exists to prevent.
  expect(screens.length).toBeGreaterThan(0);

  for (const screen of screens) {
    expect(
      identifiersFor(screen),
      `ui/src/screens/${screen} is authored but ui/src/router.tsx does not import it`,
    ).not.toHaveLength(0);
  }
}

/**
 * Every authored screen module is mounted as the element of a route.
 *
 * Assumptions: this is a SEPARATE case from the import above rather than a second assertion inside
 * it, because the two failures have different causes and different fixes. An unimported screen needs
 * an adapter; an imported-but-unmounted one needs a `Route` entry, and that was the exact state of
 * the transaction-capture screen at one point -- adapter declared, route absent.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenIsMounted(): void {
  for (const screen of authoredScreens()) {
    const identifiers = identifiersFor(screen);
    const mounted = identifiers.some(
      /**
       * Reports whether one identifier is used as a route element.
       * @param {string} identifier - An identifier the route table binds to a screen module.
       * @returns {boolean} `true` when the identifier appears as an element inside `Routes`.
       */
      (identifier) => ROUTES_REGION.includes(`<${identifier} />`),
    );

    expect(
      mounted,
      `ui/src/screens/${screen} is imported as ${identifiers.join(', ')} but no route mounts it`,
    ).toBe(true);
  }
}

/**
 * No route names a screen directory that does not exist.
 *
 * Assumptions: the reverse direction is asserted too, because a renamed or deleted screen directory
 * breaks the build only for the chunk that loads it -- a dynamic import is resolved when the route is
 * entered, so a stale specifier can survive a typecheck and fail at navigation time in a browser.
 * @returns {void} Nothing; assertions raise on failure.
 */
function noRouteNamesAMissingScreen(): void {
  const authored = new Set(authoredScreens());

  for (const screen of referencedScreens()) {
    expect(
      authored.has(screen),
      `ui/src/router.tsx imports ./screens/${screen}, which has no index.tsx`,
    ).toBe(true);
  }
}

/**
 * The two populations are the same size, so the census is exhaustive in both directions.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theTwoPopulationsAgree(): void {
  expect(referencedScreens().size).toBe(authoredScreens().length);
}

/**
 * The screens this migration has authored are all present, so the census cannot silently shrink.
 *
 * Assumptions: this is the one case that names screens, and it is deliberately a FLOOR rather than
 * the census itself. The cases above discover the population, which is what makes them immune to a
 * forgotten edit; the cost of discovery alone is that deleting a screen directory and its route
 * together would keep every one of them green. Naming the eleven delivered screens means a screen
 * that disappears fails here, while a screen that is ADDED still has to satisfy the discovered
 * cases without this list being touched.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theDeliveredScreensArePresent(): void {
  const authored = new Set(authoredScreens());

  for (const screen of [
    'accountUpdate',
    'accountView',
    'authSummary',
    'cardDetail',
    'cardList',
    'cardUpdate',
    'menu',
    'refTypeList',
    'signon',
    'transactionAdd',
    'userUpdate',
  ]) {
    expect(authored.has(screen), `ui/src/screens/${screen} is missing`).toBe(true);
  }
}

/**
 * Registers the route census cases.
 *
 * Assumptions: every case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback, matching the sibling suites. `jsdoc/require-jsdoc` is configured with
 * `publicOnly: false`, so it selects a function expression in every position, and a JSDoc block
 * written above an inline callback is moved by Prettier onto the preceding string literal, which
 * detaches it from the function it documents.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function routeCensusCases(): void {
  it('imports every authored screen module', everyScreenIsImported);
  it('mounts every authored screen module on a route', everyScreenIsMounted);
  it('names no screen directory that does not exist', noRouteNamesAMissingScreen);
  it('references exactly as many screens as are authored', theTwoPopulationsAgree);
  it('still holds every screen this migration has delivered', theDeliveredScreensArePresent);
}

describe('route census', routeCensusCases);
