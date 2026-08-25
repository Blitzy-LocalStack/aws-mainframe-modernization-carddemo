/**
 * @file Pins the one heading outline every screen renders through.
 *
 * WHY this file exists — Refactoring Rationale: the rank of a heading was a per-screen literal, and the
 * literals disagreed. Ten route captions rendered `level={3}` and three rendered `level={4}`, while the
 * application title the shell paints above every one of them rendered `level={4}`, so the same role had
 * two ranks depending on the route and on the ten majority routes the caption structurally OUTRANKED the
 * band it sits beneath. `ui/src/layout/ScreenTitle.tsx` now owns the outline. The reason that module is
 * not sufficient on its own is that nothing stops the next screen from writing its own `level` again:
 * the defect was not a wrong value, it was thirteen independent choices, and only an assertion over all
 * of them prevents a fourteenth.
 *
 * WHY : Assumptions: the file asserts the outline in THREE independent ways, because each catches a
 * different way of breaking it. The ordering case catches a change to the constants themselves; the
 * rendered case catches a screen that stopped using them; and the source census catches a screen that
 * reintroduced a literal, which the other two cannot see because a literal that happens to match today's
 * constant renders identically and drifts only when the constant moves.
 *
 * WHY : Assumptions: the appearance is asserted alongside the rank. Separating semantic rank from
 * tokenized visual size is the whole mechanism, so a change that moved the rank AND the size together
 * would satisfy the outline while altering the band the mapset fixes at one of 24 rows — which is a
 * regression this file would otherwise wave through.
 */

import { render, screen } from '@testing-library/react';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import { APP_TITLE_DISPLAY } from '../messages/messages';
import { BMS_TEXT_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
import { ScreenHeader } from './ScreenHeader';
import {
  APP_TITLE_HEADING_LEVEL,
  SCREEN_TITLE_HEADING_LEVEL,
  SECTION_HEADING_LEVEL,
  ScreenTitle,
} from './ScreenTitle';

/** A caption standing in for whatever text a route paints on its row 4. */
const CAPTION_TEXT = 'View Account';

/** Directory holding the screen modules, relative to this file. */
const SCREENS_ROOT = join(import.meta.dirname, '..', 'screens');

/**
 * Every screen module, discovered from the filesystem.
 *
 * ⚠️ Refactoring Rationale: the population was a hand-written list of thirteen names, defended by
 * citing the convention `ui/src/layout/screenHeaderClock.test.tsx` then recorded -- that a glob grows
 * silently, so a new screen would first fail an unrelated change rather than its own. That file has
 * since abandoned the convention for the reason this one demonstrates: `transactionList` wrote
 * `level={4}` directly, which is the fourteenth independent choice of rank that
 * `ui/src/layout/ScreenTitle.tsx` was authored to make unstateable, and it passed here because it was
 * not on the list. A gate whose coverage the next author decides is not a gate.
 *
 * Assumptions: discovery is safe for the census below in a way it would not be for a positive
 * assertion, because the assertion is a PROHIBITION -- a screen that paints no heading at all trivially
 * writes no rank, so nothing is forced into the heading-bearing subset. `signon` is the delivered case:
 * `app/bms/COSGN00.bms` declares no row-4 caption, so it renders no `ScreenTitle` and is required to,
 * and covering it costs nothing while omitting it would have to be justified.
 *
 * Assumptions: a directory qualifies only when it holds `index.tsx`, matching
 * `ui/src/routes/routeCensus.test.ts` and the delegation census, so all three measure one population.
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
 * Matches a heading whose rank is written as a bare number.
 *
 * Assumptions: the pattern admits optional whitespace inside the braces because a formatter may
 * introduce it, and it deliberately does NOT match a named constant — `level={SECTION_HEADING_LEVEL}`
 * is the shape this file requires, so the census passes only while every rank is named.
 */
const NUMERIC_LEVEL = /level=\{\s*\d/u;

/**
 * Strips comments so a census over source text cannot be fooled by prose.
 *
 * Assumptions: this exists because several screens QUOTE the literal they no longer write -- the
 * rationale at each converted site records the rank it used to carry, which is what makes the change
 * auditable -- and a bare text search would read those quotations as the defect. Stripping block and
 * line comments leaves only what the compiler sees.
 * @param {string} source - A module's source text.
 * @returns {string} The same text with every comment removed.
 */
function withoutComments(source: string): string {
  return source.replaceAll(/\/\*[\s\S]*?\*\//gu, '').replaceAll(/\/\/[^\n]*/gu, '');
}

/**
 * Converts a design-token name to the CSS custom property the theme publishes it as.
 *
 * Assumptions: derived rather than written out, so the assertion is bound to the token NAME and cannot
 * pass against a hand-written variable that merely looks similar. The theme emits the reference form
 * -- `var(--ant-font-size-heading-4)` for `fontSizeHeading4` -- so each word boundary and each digit
 * run becomes a dash.
 * @param {string} token - A key of the theme's token surface, in camel case.
 * @returns {string} The kebab-cased fragment the published custom property contains.
 */
function customPropertyFragment(token: string): string {
  return token
    .replaceAll(/([A-Z])/gu, '-$1')
    .replaceAll(/(\d+)/gu, '-$1')
    .toLowerCase();
}

/**
 * Reads one screen module's source.
 * @param {string} screen - Directory name of the screen under `ui/src/screens`.
 * @returns {string} The module's source text.
 */
function sourceOf(screen: string): string {
  return readFileSync(join(SCREENS_ROOT, screen, 'index.tsx'), 'utf8');
}

/**
 * The three ranks descend, so no heading ever outranks the one above it.
 *
 * Assumptions: asserted as a strict ordering rather than as three equalities against literal numbers.
 * The numbers themselves are a design-system mapping and may legitimately move together; what may not
 * move is their ORDER, so pinning the order is pinning the property and pinning the values would pin a
 * decision this file has no standing to make.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theRanksDescendFromTheBandToTheSection(): void {
  expect(
    APP_TITLE_HEADING_LEVEL,
    'the application title must outrank every route caption',
  ).toBeLessThan(SCREEN_TITLE_HEADING_LEVEL);
  expect(
    SCREEN_TITLE_HEADING_LEVEL,
    'a route caption must outrank a block heading inside its own body',
  ).toBeLessThan(SECTION_HEADING_LEVEL);
}

/**
 * The rendered band outranks the rendered caption, and keeps the size the bridge maps.
 *
 * Assumptions: both headings are rendered TOGETHER, because the defect was relational — each element
 * was defensible alone and only their combination was wrong. Rendering them apart and comparing two
 * recorded numbers would restate the ordering case above rather than exercise the components.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theBandOutranksTheCaptionWhenBothAreRendered(): void {
  render(
    <>
      <ScreenHeader transactionId="CAVW" programName="COACTVWC" />
      <ScreenTitle>{CAPTION_TEXT}</ScreenTitle>
    </>,
  );

  const band = screen.getByRole('heading', { name: APP_TITLE_DISPLAY });
  const caption = screen.getByRole('heading', { name: CAPTION_TEXT });

  expect(band.tagName).toBe(`H${String(APP_TITLE_HEADING_LEVEL)}`);
  expect(caption.tagName).toBe(`H${String(SCREEN_TITLE_HEADING_LEVEL)}`);
  /*
   * Assumptions: the tag names are compared as ranks rather than merely checked against the constants,
   * so the case fails if the two components are wired to constants that no longer descend even though
   * each matches its own. That is the exact shape of the original defect.
   */
  expect(
    Number(band.tagName.slice(1)),
    'the rendered band must outrank the rendered caption',
  ).toBeLessThan(Number(caption.tagName.slice(1)));

  expect(
    caption.getAttribute('style'),
    'a route caption must carry the size the bridge maps, independently of its rank',
  ).toContain(customPropertyFragment(TYPOGRAPHY_TOKENS.screenTitleSize));

  /*
   * ⚠️ Refactoring Rationale: the band is now asserted to carry a DIFFERENT size from the caption,
   * where this case used to require both to carry the same one -- the bridge's screen-title entry.
   * Requiring one size was a defect the case was enforcing: a rendering review measured both headings
   * at `20px/600` on all nine screens that paint two, so rank conveyed nothing a reader could see and
   * the only visible difference was a colour that itself varied by screen. The bridge entry belongs to
   * the CAPTION -- `ui/src/theme/tokens.ts` names it `screenTitleSize` and argues it from the row-4
   * field -- so the band taking it too was the mistake, not the mapping.
   *
   * Assumptions: the two are compared to EACH OTHER rather than to a second token name, because what
   * matters is that they differ; which step the band takes is argued at `appTitleSizeStyle` and pinning
   * the name here would duplicate that decision in a place with no standing to make it.
   */
  const bandSize = band.style.fontSize;
  const captionSize = caption.style.fontSize;

  expect(bandSize, 'the band must state a size of its own').not.toBe('');
  expect(captionSize, 'the caption must state a size of its own').not.toBe('');
  expect(bandSize, 'rank must be visible as well as semantic: the two sizes must differ').not.toBe(
    captionSize,
  );
}

/**
 * The band is the document's own heading, so the outline has a rank-one element.
 *
 * Purpose: a rendering review scanned every heading on all fifteen rendered screens and found no `h1`
 * anywhere - the highest rank was `h3`, so every document asserted two ancestors that do not exist.
 * The band is the element that should carry rank one: it names the enclosing region and `ui/index.html`
 * renders the same constant as the document `<title>`.
 *
 * Assumptions: the count is asserted as well as the rank, because two rank-one headings would be a
 * different defect with the same passing rank - and the band is rendered by the shell on eighteen
 * routes and by the screen itself on three, so a duplicate is a real failure mode rather than a
 * theoretical one.
 * @returns {void} Nothing; assertions raise on failure.
 */
function givesTheDocumentARankOneHeading(): void {
  render(<ScreenHeader transactionId="CAVW" programName="COACTVWC" />);

  const band = screen.getByRole('heading', { name: APP_TITLE_DISPLAY });

  expect(band.tagName, 'the application title must be the document-level heading').toBe('H1');
  expect(
    document.querySelectorAll('h1').length,
    'exactly one element may claim the document-level rank',
  ).toBe(1);
}

/**
 * Every route caption resolves one colour, the one its mapset declares.
 *
 * Purpose: a rendering review measured the caption in three values across the routes -
 * `rgba(0,0,0,0.88)` on four and `rgba(0,0,0,0.65)` on five - because the colour was whatever the
 * calling screen passed. Every mapset that declares a row-4 caption declares it `COLOR=NEUTRAL`, all
 * eighteen of them, so the variation expressed a distinction the source does not draw.
 *
 * Assumptions: the case renders a caption that PASSES A COLOUR of its own and asserts the mapped token
 * wins, because the population that measured wrong is exactly the screens that passed one - a case that
 * rendered the bare component would pass without exercising the precedence that fixes them.
 * @returns {void} Nothing; assertions raise on failure.
 */
function resolvesOneColourForEveryCaption(): void {
  render(<ScreenTitle style={{ color: 'var(--ant-color-error)' }}>{CAPTION_TEXT}</ScreenTitle>);

  const caption = screen.getByRole('heading', { name: CAPTION_TEXT });

  expect(
    caption.getAttribute('style'),
    'a caption must resolve the NEUTRAL role its mapset declares, whatever its screen passes',
  ).toContain(customPropertyFragment(BMS_TEXT_COLOR_TOKENS.NEUTRAL));
}

/**
 * No screen writes a heading rank of its own.
 *
 * Assumptions: this reads the sources because the property is syntactic — that a literal does not
 * appear — and is invisible through any module's exported surface or its rendered output. A literal
 * matching today's constant renders identically, so only the text can distinguish "uses the outline"
 * from "happens to agree with it".
 * @returns {void} Nothing; assertions raise on failure.
 */
function noScreenWritesAHeadingRankOfItsOwn(): void {
  const screens = authoredScreens();

  // Assumptions: the population is asserted non-empty first, because this case is a prohibition over
  //   it -- a resolution fault returning an empty list would let it pass while examining nothing, and a
  //   prohibition that examines nothing is indistinguishable from one that holds.
  expect(screens.length, 'no screen modules were discovered at all').toBeGreaterThan(0);

  for (const name of screens) {
    expect(
      NUMERIC_LEVEL.test(withoutComments(sourceOf(name))),
      `${name} must take its heading rank from ScreenTitle.tsx, not from a literal level`,
    ).toBe(false);
  }
}

/**
 * Registers the heading-outline cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function headingOutlineCases(): void {
  it('ranks descend from the band to a section heading', theRanksDescendFromTheBandToTheSection);
  it('renders the band above the caption', theBandOutranksTheCaptionWhenBothAreRendered);
  it('gives the document a rank-one heading', givesTheDocumentARankOneHeading);
  it('resolves one colour for every caption', resolvesOneColourForEveryCaption);
  it('lets no screen write a heading rank of its own', noScreenWritesAHeadingRankOfItsOwn);
}

describe('the shared heading outline', headingOutlineCases);
