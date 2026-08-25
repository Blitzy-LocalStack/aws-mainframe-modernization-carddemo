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
 * Why this file exists — Assumptions: an unmounted screen is invisible from both directions. A screen
 * compiles, type-checks and lints while unreachable, because nothing in a module's own text depends
 * on being imported; and a route table cannot assert what is absent from it, because an absent route
 * is simply a table with fewer entries. A screen's own contract can even state the path the router
 * mounts it at while nothing in the repository imports it, and the two disagree with no gate able to
 * tell. This census closes that CLASS of defect, which is why it discovers the screens from the
 * filesystem instead of listing them.
 *
 * Alternatives Considered: mounting the shipped route array at each expected path and asserting the
 * screen appears. Rejected on what such a case would actually measure. Every screen needs its
 * authentication guard satisfied, its API responses stubbed and its lazy chunk resolved before it
 * mounts at all, so a failure would name a missing token or an unstubbed request far more often than
 * a missing route, and the one property under test here — that the table names the module — would be
 * the least likely explanation of a red result. `ui/src/routerRoutes.test.tsx` pays that cost
 * deliberately for the paths it renders; reading the table's source is narrow, and its failure names
 * the screen and the direction of the gap.
 *
 * Alternatives Considered: listing the screens in this file, as `ui/src/layout/screenHeaderClock.test.tsx`
 * deliberately does for the header-clock contract. Rejected here because the two cases differ in
 * which direction the risk runs. That file guards a contract a NEW screen might not satisfy, so a
 * hand-written list is a feature: adding a screen becomes an explicit edit rather than a surprise
 * failure. This file guards a contract a new screen must satisfy by construction — being reachable —
 * and a hand-written list would have to be extended by the same change that forgot the route, so it
 * could never catch the omission it exists for.
 *
 * Assumptions: the census reads sources rather than importing modules, and it stays that way now
 * that `ui/src/router.tsx` publishes its route objects. Importing them would give the mounted
 * ELEMENTS, and an element built from `React.lazy` carries no module specifier at all — there is no
 * way back from a lazy component to the `./screens/<name>` directory this census is about. Whether a
 * directory appears in an import specifier and whether the identifier bound to it is an `element`
 * value are both facts about the text of that file, so the text is what is read. What the exported
 * array is good for is the complementary property — that the paths and guards are the intended ones
 * — which `ui/src/routerRoutes.test.tsx` asserts against the objects themselves.
 */

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Directory holding the screen modules, resolved from this file rather than from the runner's cwd. */
const SCREENS_ROOT = join(import.meta.dirname, '..', 'screens');

/** The route table's own source, which is the single artifact this census measures. */
const ROUTER_SOURCE = readFileSync(join(import.meta.dirname, '..', 'router.tsx'), 'utf8');

/** First text of the mounted route array's declaration, which opens the measured region. */
const MOUNTED_REGION_OPENS = 'export const CARD_DEMO_ROUTES';

/** Text closing the mounted route array, which Prettier puts at column 0 on its own line. */
const MOUNTED_REGION_CLOSES = '\n];';

/**
 * The region of the route table between the mounted route array's own delimiters.
 *
 * Assumptions: "mounted" is asserted against this slice and not against the whole file, because an
 * identifier can appear in a module while being mounted nowhere — a lazy adapter is declared above
 * this region and referenced there, and a declaration alone is exactly the half-finished state the
 * five unmounted screens were in. Slicing on the declaration's own text rather than on a line number
 * keeps the region correct as the table grows.
 *
 * ⚠️ Refactoring Rationale: the delimiters are the route ARRAY's, where they were the `Routes`
 * element's opening and closing tags. `ui/src/router.tsx` builds the tree as route objects handed to
 * `createBrowserRouter`, so there is no `<Routes>` element left to slice on and the previous anchors
 * would both resolve to -1 — which `slice(-1, -1)` turns into an EMPTY string, and an empty region
 * makes every mounting assertion below vacuously false rather than reporting the missing anchor. The
 * emptiness is therefore asserted against directly by {@link theMountedRegionIsMeasurable}, so a
 * future rename of the array breaks that one case with a message naming the anchor instead of
 * failing twenty cases with a message naming screens.
 * @returns {string} The source text between the array's declaration and its closing bracket, or the
 *   empty string when either delimiter is absent.
 */
function mountedRegion(): string {
  const opens = ROUTER_SOURCE.indexOf(MOUNTED_REGION_OPENS);
  if (opens < 0) {
    return '';
  }

  const closes = ROUTER_SOURCE.indexOf(MOUNTED_REGION_CLOSES, opens);
  if (closes < 0) {
    return '';
  }

  return ROUTER_SOURCE.slice(opens, closes);
}

/** The measured region, resolved once because the source it reads cannot change mid-run. */
const ROUTES_REGION = mountedRegion();

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
 * The measured region is present and non-empty, so the mounting case measures the route array.
 *
 * Assumptions: this is asserted as a case of its own rather than inside the mounting case below,
 * exactly as the screen population is asserted non-empty before it is iterated. Both delimiters are
 * required by name, so a rename of the exported array fails HERE naming the anchor rather than
 * failing every screen for a reason that has nothing to do with screens.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theMountedRegionIsMeasurable(): void {
  expect(
    ROUTER_SOURCE,
    `ui/src/router.tsx must declare the mounted route array as ${MOUNTED_REGION_OPENS}`,
  ).toContain(MOUNTED_REGION_OPENS);
  expect(
    ROUTER_SOURCE,
    `the mounted route array must close with ${JSON.stringify(MOUNTED_REGION_CLOSES)}`,
  ).toContain(MOUNTED_REGION_CLOSES);
  expect(
    ROUTER_SOURCE.indexOf(MOUNTED_REGION_OPENS),
    'the closing delimiter must follow the declaration it closes',
  ).toBeLessThan(
    ROUTER_SOURCE.indexOf(MOUNTED_REGION_CLOSES, ROUTER_SOURCE.indexOf(MOUNTED_REGION_OPENS)),
  );
  expect(
    ROUTES_REGION.length,
    'the mounted route region resolved empty, so nothing below measures the route array',
  ).toBeGreaterThan(0);

  /*
   * Assumptions: the region has to contain route DECLARATIONS, not merely be non-empty. Two anchors
   * that had drifted together would satisfy a length check while enclosing no route at all, so the
   * assertion is on the two keys every declared route carries.
   */
  expect(ROUTES_REGION, 'the measured region encloses no route declaration').toContain('path:');
  expect(ROUTES_REGION, 'the measured region encloses no route element').toContain('element:');
}

/**
 * Every authored screen module is mounted as the element of a route.
 *
 * Assumptions: this is a SEPARATE case from the import above rather than a second assertion inside
 * it, because the two failures have different causes and different fixes. An unimported screen needs
 * an adapter; an imported-but-unmounted one needs a route entry, and that was the exact state of
 * the transaction-capture screen at one point -- adapter declared, route absent.
 *
 * ⚠️ Assumptions: the match is `element: <Identifier />` rather than the bare JSX element, because
 * the tree is now route OBJECTS: a screen is mounted by being the value of an `element` key, and a
 * bare element match would also be satisfied by an identifier appearing anywhere in the region --
 * inside a fallback, or as a prop -- which is the vacuous pass this census exists to prevent.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyScreenIsMounted(): void {
  expect(ROUTES_REGION.length, 'the mounted route region resolved empty').toBeGreaterThan(0);

  for (const screen of authoredScreens()) {
    const identifiers = identifiersFor(screen);
    const mounted = identifiers.some(
      /**
       * Reports whether one identifier is mounted as a route element.
       * @param {string} identifier - An identifier the route table binds to a screen module.
       * @returns {boolean} `true` when the identifier is an `element` value in the route array.
       */
      (identifier) => ROUTES_REGION.includes(`element: <${identifier} />`),
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
 * The route constants whose only purpose is to give a per-record screen a keyless address.
 *
 * ⚠️ Assumptions: they are named as CONSTANT IDENTIFIERS rather than as path strings, because that is
 * the form the mounted region holds -- `ui/src/router.tsx` writes `path: CARD_DETAIL_ENTRY_ROUTE` and
 * the constant is composed in `ui/src/routes/navigation.ts`, so the literal `/cards/view` appears
 * nowhere in the region this file measures. Matching the identifier is therefore the only text check
 * available, and it is also the more useful one: it fails when the mount is removed, which is the
 * regression, and not when the path is renamed, which is a decision the constant carries.
 */
const KEYLESS_ENTRY_CONSTANTS: readonly string[] = [
  'CARD_DETAIL_ENTRY_ROUTE',
  'CARD_UPDATE_ENTRY_ROUTE',
  'TRANSACTION_DETAIL_ENTRY_ROUTE',
];

/**
 * The three keyless entry routes are still mounted, so no menu option can lose its destination.
 *
 * ⚠️ Purpose: this case is NEW and it is a FLOOR in the same sense {@link theDeliveredScreensArePresent}
 * is. The discovered cases above cannot report it: all three addresses mount screens that are ALSO
 * mounted at a keyed path, so deleting a keyless route leaves every screen imported, mounted and
 * accounted for while three main-menu options quietly collapse back onto the browses that mint their
 * record keys. `ui/src/routerRoutes.test.tsx` catches the same regression by counting distinct menu
 * destinations; this case catches it in the router's own text, which is where the deletion would happen.
 *
 * Assumptions: it asserts the mount and not the alias table beside it. A row published in
 * `KEYLESS_ENTRY_ROUTES` but never mounted is the failure this pairs against, and that direction is
 * held by the reachability sweep in `ui/src/routerReachability.test.tsx`, which projects its addresses
 * from that table and renders each one.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theKeylessEntryRoutesAreMounted(): void {
  expect(ROUTES_REGION.length, 'the mounted route region resolved empty').toBeGreaterThan(0);

  for (const constant of KEYLESS_ENTRY_CONSTANTS) {
    expect(
      ROUTES_REGION.includes(`path: ${constant}`),
      `ui/src/router.tsx mounts no route at ${constant}, so a main-menu option has no destination`,
    ).toBe(true);
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
  it('measures a non-empty mounted route region', theMountedRegionIsMeasurable);
  it('imports every authored screen module', everyScreenIsImported);
  it('mounts every authored screen module on a route', everyScreenIsMounted);
  it('names no screen directory that does not exist', noRouteNamesAMissingScreen);
  it('references exactly as many screens as are authored', theTwoPopulationsAgree);
  it('still holds every screen this migration has delivered', theDeliveredScreensArePresent);
  it('still mounts every keyless entry route', theKeylessEntryRoutesAreMounted);
}

describe('route census', routeCensusCases);
