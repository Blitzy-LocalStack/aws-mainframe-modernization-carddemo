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
 * Assumptions: the ranks start at three rather than one. Nothing in this application renders an `h1` or
 * an `h2`, because nothing above the application title exists to name: the shell paints one band per
 * screen and the SPA has no page-level title element beyond it. Starting the outline lower is a
 * consequence of the band's size mapping, not an oversight, and the relative order — which is what
 * heading navigation uses — is unbroken.
 */

import { Typography, theme } from 'antd';
import type { GlobalToken } from 'antd';
import type { CSSProperties, ReactElement, ReactNode } from 'react';

import { TYPOGRAPHY_TOKENS } from '../theme/tokens';

/**
 * Semantic rank of the shared application title the shell paints on rows 1 and 2.
 *
 * Assumptions: the highest rank this application renders, because the band is painted above every
 * screen and names the enclosing region through `aria-labelledby`. Its VISUAL size is unchanged by the
 * rank and comes from {@link screenHeadingSizeStyle}.
 */
export const APP_TITLE_HEADING_LEVEL = 3;

/**
 * Semantic rank of a route's own caption, the mapset's row-4 field.
 *
 * Assumptions: one below {@link APP_TITLE_HEADING_LEVEL} on every route without exception, which is
 * what makes a caption a subheading of the band above it rather than a peer or a parent of it.
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
 * Renders one route's caption at the single rank every route shares.
 *
 * Purpose: the component thirteen screens call instead of choosing a `level`, so the outline is a
 * property of this module rather than of each screen.
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

  return (
    <Typography.Title
      level={SCREEN_TITLE_HEADING_LEVEL}
      {...(type === undefined ? {} : { type })}
      style={{ ...style, ...screenHeadingSizeStyle(cssVar) }}
    >
      {children}
    </Typography.Title>
  );
}
