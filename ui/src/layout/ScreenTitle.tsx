/**
 * @file The one heading outline every migrated screen renders through.
 *
 * Purpose
 * -------
 * A browser document has a heading outline; a 3270 screen does not. The baseline paints its
 * application title into rows 1 and 2 of every mapset and its per-screen caption into row 4, and the
 * only thing that distinguishes them is where they sit on a character grid. Translating that to the
 * browser means choosing a RANK for each, and until this module existed each screen chose for itself:
 * ten route captions rendered as `h3`, three as `h4`, and the shared application title the shell paints
 * above all of them rendered as `h4` as well. So the same role had two different ranks depending on the
 * route, and on the ten `h3` routes the caption structurally OUTRANKED the application title it sits
 * beneath — an operator navigating by heading was told the screen's own caption was the document's
 * highest heading and the band above it a subheading of nothing.
 *
 * This module fixes the rank in one place, for three roles, and it does so by separating two decisions
 * that `Typography.Title` deliberately fuses:
 *
 * - the SEMANTIC rank, which is what assistive technology navigates by, and
 * - the VISUAL size, which AAP section 0.3.3 maps from the mapset through
 *   `TYPOGRAPHY_TOKENS.screenTitleSize` and `screenTitleLineHeight`.
 *
 * `level` sets both at once, which is convenient exactly until the two need different answers. They do
 * here: the bridge's answer for a screen title's SIZE is the fourth heading step — chosen because a
 * larger step costs vertical space on a screen of 128 fields — while the outline needs the application
 * title ABOVE the route caption. Fusing them forced a choice between a correct size and a correct
 * outline. Stating them separately gets both, and it is why this module exports the size as a style
 * helper alongside the ranks rather than leaving `level` to imply it.
 *
 * Alternatives Considered: raising every route caption to the rank the majority already used and
 * lowering the application title beneath it. Rejected because it inverts the relationship the baseline
 * states positionally — the title band is painted on the first two rows of EVERY mapset, including the
 * four extension mapsets, and the caption on row 4 of the one screen — so the band is the page's title
 * and the caption is the section's. Making the band subordinate would encode the opposite of the source.
 *
 * Alternatives Considered: leaving each screen to pass its own `level` and asserting the values in a
 * test instead. Rejected because the alternation this replaces is exactly what a per-screen choice
 * produces, and a test over thirteen literals tells a maintainer that one of them is wrong without
 * telling them which value is right. A primitive makes the outline unstateable per screen, so the next
 * screen authored inherits it rather than choosing again.
 *
 * ⚠️ Refactoring Rationale: the outline now STARTS AT ONE, and the note that stood here argued for
 * starting at three. It said nothing above the application title exists to name, so a lower start was a
 * consequence of the band's size mapping rather than an oversight. The first half was right and the
 * conclusion was wrong: a document whose highest heading is an `h3` has no document-level heading at
 * all, which was measured on all fifteen rendered screens - a full heading scan of `/signon` returns
 * exactly one heading, `h3 "CardDemo"`, and every other screen returns an `h3` above an `h4` with no
 * ancestor. "Nothing above it exists" is the argument FOR ranking it one: the application title IS the
 * document's title, so rank one is what says so, and the rank the band held instead asserted two
 * absent ancestors.
 *
 * Assumptions: the gap from one to four is an AAP CONSTRAINT and not an accident of this edit. Section
 * 0.3.2 of the plan mandates `Typography.Title level={4}` for the screen title band, so the caption's
 * rank is fixed externally and cannot be raised to close the gap; the band above it therefore has to
 * be one, because three would leave the caption outranking nothing again and two would state an
 * ancestor the frame does not paint. A skipped level is a heading-order warning in some checkers and
 * is accepted deliberately here, in preference to either a missing `h1` or a wrong relative order -
 * the property heading navigation actually uses is the ORDER, and it is now correct on every screen.
 *
 * Refactoring Rationale: each rank also renders at a DISTINCT size, where two of them used to render
 * identically. Measured on all nine screens that paint two headings, the `h3` and the `h4` were both
 * `20px/600`, so rank conveyed nothing visually and the only difference was a colour that itself
 * varied by screen - `rgba(0,0,0,0.88)` on four routes and `rgba(0,0,0,0.65)` on five. The three ranks
 * now take three steps of the design system's own heading scale, so the visual hierarchy and the
 * semantic one agree without either being derived from the other.
 */

import { Typography, theme } from 'antd';
import type { GlobalToken } from 'antd';
import type { CSSProperties, ReactElement, ReactNode } from 'react';

import { BMS_TEXT_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';

/**
 * Semantic rank of the shared application title the shell paints on rows 1 and 2.
 *
 * Assumptions: rank ONE, because this is the document's own title. The band is painted above every
 * screen, it names the enclosing region through `aria-labelledby`, and `ui/index.html` renders the
 * same constant as the document `<title>` - so the element that carries it is the document-level
 * heading whether or not it is ranked as one. Its VISUAL size comes from
 * {@link appTitleSizeStyle} and is stated separately, for the reason the file header records.
 */
export const APP_TITLE_HEADING_LEVEL = 1;

/**
 * Semantic rank of a route's own caption, the mapset's row-4 field.
 *
 * Assumptions: BELOW {@link APP_TITLE_HEADING_LEVEL} on every route without exception, which is what
 * makes a caption a subheading of the band above it rather than a peer or a parent of it. The value is
 * four rather than two because AAP section 0.3.2 names `Typography.Title level={4}` for this element
 * explicitly; the plan is frozen, so this rank is transcribed rather than chosen, and the file header
 * records why the resulting one-to-four gap is the constraint's consequence and not an oversight.
 */
export const SCREEN_TITLE_HEADING_LEVEL = 4;

/**
 * Semantic rank of a heading that introduces one block inside a screen body.
 *
 * Assumptions: one below {@link SCREEN_TITLE_HEADING_LEVEL}, which the two screens using it already
 * claim positionally — the account view paints `Customer Details` at row 11 while its caption is at
 * row 4, and the authorization detail paints its merchant heading below its own first panel. Unlike the
 * two ranks above it this one carries NO size from the bridge, because the bridge maps a screen title
 * and a block heading is not one; the design system's own step for the rank is used, which is the only
 * value that needs no decision.
 */
export const SECTION_HEADING_LEVEL = 5;

/** What a screen supplies to render its caption. */
export interface ScreenTitleProps {
  /** The caption text, verbatim from the mapset's row-4 field. */
  readonly children: ReactNode;
  /**
   * Colour and weight the mapset's own attributes call for, resolved to tokens by the caller.
   *
   * Assumptions: the caller owns the COLOUR because it is a per-mapset attribute — `COLOR=NEUTRAL` on
   * most captions, the de-emphasis role on the account view — and this component owns the SIZE because
   * that is one bridge entry for every screen. Splitting them this way is what stops a screen from
   * having to restate the size in order to set its colour.
   */
  readonly style?: CSSProperties | undefined;
  /**
   * Ant Design's own text grade, where a caption uses one instead of a colour token.
   *
   * Assumptions: passed through rather than translated, because the account view's caption is
   * de-emphasised by grade and re-expressing that as a colour literal would leave the theme unable to
   * re-derive it.
   */
  readonly type?: 'secondary' | undefined;
}

/**
 * Builds the style that carries a screen heading's size independently of its rank.
 *
 * Purpose: the two members AAP section 0.3.3 maps for a screen title, stated explicitly so that the
 * rank above them can be chosen for the OUTLINE without moving the appearance.
 *
 * Assumptions: written as a style rather than left to `level` deliberately, and this reverses an
 * earlier decision recorded in `ScreenHeader.tsx` that rejected exactly this on the ground that
 * `level={4}` already applies the two tokens. That was true while rank and size agreed. They no longer
 * do — the band's rank is now three and its size is still the fourth step — so the tokens have to be
 * named to be applied, and naming them is what keeps the appearance fixed while the outline moves.
 *
 * Trade-offs: the size is frozen at render time, where `level` would let a re-derived heading scale
 * move it. The cost is real and accepted: a theme that re-derived `fontSizeHeading4` would move
 * `Typography.Title level={4}` and leave this override behind. It is accepted because the value is read
 * from the token as a CSS VARIABLE REFERENCE rather than as a resolved number — `cssVar` returns the
 * `var(--...)` form — so a re-derivation that changes the variable's value still reaches this element.
 * Only a theme that renamed the token would strand it, and a rename would fail the type of
 * `TYPOGRAPHY_TOKENS` first.
 * Assumptions: the parameter is the whole `GlobalToken` rather than a narrower string map, because
 * that is what `theme.useToken()` hands back for both of its members: a colour token indexes to a
 * string and a size token to a number in the type, while `cssVar` supplies the reference form for both
 * at run time. Taking the published type keeps the caller from having to assert.
 * @param {GlobalToken} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`.
 * @returns {CSSProperties} The size and line height every screen heading shares.
 */
export function screenHeadingSizeStyle(cssVar: GlobalToken): CSSProperties {
  return {
    fontSize: cssVar[TYPOGRAPHY_TOKENS.screenTitleSize],
    lineHeight: cssVar[TYPOGRAPHY_TOKENS.screenTitleLineHeight],
  };
}

/**
 * Builds the style that sizes the shared application title one step above a screen caption.
 *
 * Purpose: give the document's own heading a size that matches its rank. The band and the caption
 * beneath it both rendered at the fourth heading step - measured `20px/600` for both on all nine
 * screens that paint two headings - so rank one and rank four were visually indistinguishable and the
 * hierarchy existed only in the accessibility tree.
 *
 * Alternatives Considered: the FIRST heading step, which is the obvious partner for rank one. Rejected
 * on the measurement `ui/src/theme/tokens.ts` already records for this exact decision: the source title
 * occupies one of the baseline's 24 rows, and the first step is nearly twice the fourth, so it would
 * take vertical space from screens whose field count reaches 128 - and every pixel the band takes is
 * now permanent, because the shell pins the two bottom lines and the body scrolls between them.
 *
 * Alternatives Considered: leaving the two elements one size and distinguishing them by weight instead.
 * Rejected because `TYPOGRAPHY_TOKENS.brightEmphasis` is already committed to the `ATTRB=BRT`
 * brightness axis - 12 of the 18 measured row-4 captions are BRT and 6 are not - so a weight difference
 * introduced here would collide with an operand the mapsets do use.
 *
 * Assumptions: the third step is therefore the choice: one visible step above the caption, which is
 * what a reader needs to see the rank, and one step below the display sizes, which is what keeps the
 * band's cost in rows unchanged. Both members are token references, so the pair moves with the theme.
 * @param {GlobalToken} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`.
 * @returns {CSSProperties} The size and line height the shared application title renders at.
 */
export function appTitleSizeStyle(cssVar: GlobalToken): CSSProperties {
  return {
    fontSize: cssVar.fontSizeHeading3,
    lineHeight: cssVar.lineHeightHeading3,
  };
}

/**
 * Renders one route's caption at the single rank every route shares.
 *
 * Purpose: the component every screen painting a caption calls instead of choosing a `level`, so the
 * outline is a property of this module rather than of each screen. Twenty of the twenty-one screens
 * paint one; `signon` is the exception, because `app/bms/COSGN00.bms` declares no row-4 caption field.
 *
 * Assumptions: the size members are applied AFTER the caller's style, so a caller cannot displace them
 * by passing a size of its own. The colour and weight a mapset calls for are still the caller's,
 * because those genuinely differ per screen; the size does not, and a screen that overrode it would
 * reintroduce the inconsistency this module exists to remove.
 * @param {ScreenTitleProps} props - The caption text and the mapset's own text attributes.
 * @returns {ReactElement} The caption, ranked one below the shared application title.
 */
export function ScreenTitle({ children, style, type }: ScreenTitleProps): ReactElement {
  const { cssVar } = theme.useToken();

  /*
   * Refactoring Rationale: the caption's COLOUR is now resolved here and applied last, where it used
   * to be whatever the calling screen passed. That produced three different colours for one role -
   * measured `rgba(0,0,0,0.88)` on `/admin`, `/menu`, `/users` and `/cards` against
   * `rgba(0,0,0,0.65)` on five other routes - so the only visual difference between a rank-one and a
   * rank-four heading varied by screen, and on four screens there was none at all.
   *
   * Assumptions: one colour is the FAITHFUL reading and not a simplification. Every mapset that
   * declares a row-4 caption declares it `COLOR=NEUTRAL` - all eighteen of them, from
   * `app/bms/COACTVW.bms` L75-L78 to `app/bms/COUSR00.bms` L75-L79 - and the bridge maps NEUTRAL onto
   * the secondary text grade. What genuinely varies between those eighteen is `ATTRB=BRT`, present on
   * twelve and absent on six, which is a WEIGHT axis rather than a colour one. So a per-screen colour
   * was expressing a distinction the source does not draw, in the axis the source reserves for a
   * different one.
   *
   * Trade-offs: a screen can no longer tint its own caption, and the prop that let it is retained
   * rather than removed. Removing `style` would break every caller at once for a benefit this
   * declaration already delivers, and the prop still carries anything that is NOT the colour - the
   * spacing overrides two screens pass, for instance. `type` is likewise still honoured, and it is
   * consistent rather than redundant: the design system resolves `secondary` to the same token this
   * declaration names, so the five screens that pass it were already correct and the other thirteen
   * now join them.
   */
  const captionColorStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };

  return (
    <Typography.Title
      level={SCREEN_TITLE_HEADING_LEVEL}
      {...(type === undefined ? {} : { type })}
      style={{ ...style, ...screenHeadingSizeStyle(cssVar), ...captionColorStyle }}
    >
      {children}
    </Typography.Title>
  );
}
