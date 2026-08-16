/**
 * @file How a migrated record view maps onto the design system's breakpoints.
 *
 * Purpose
 * -------
 * Every screen that displays a stored record — the account view, the card detail, the pending
 * authorization detail — renders it as an antd `Descriptions` block standing in for a region of the
 * fixed 24×80 character grid. That substitution is documented design gap G1: the reference positioned
 * every field absolutely, and the browser cannot reproduce absolute character positioning responsively
 * or accessibly, so grouping and reading order are preserved and pixel-for-character placement is not.
 *
 * What G1 does not say is how many columns the substitute grid should have, and answering that per
 * screen produced the defect this module exists to remove: each block was authored with a fixed
 * `column={2}`, which is a two-up grid at EVERY width. At a phone width a two-up bordered table gives
 * each value roughly half of 375 pixels minus its label cell, so a 50-character address line or a
 * `+ZZZ,ZZZ,ZZZ.99` money field either wraps to several lines or pushes the table wider than the
 * viewport — the latter being the same horizontal-overflow failure the sign-on screen's fixed-pitch
 * decoration produced before it gained a breakpoint policy.
 *
 * This module states the policy once: one column below the design system's medium breakpoint, two from
 * it upward. A screen imports the policy rather than restating the object, so the three record screens
 * cannot come to disagree about where a record view stops being two-up.
 *
 * Assumptions: this holds no pixel value and no colour. The thresholds themselves live in the design
 * system, named by `BREAKPOINT_TOKENS` in `ui/src/theme/tokens.ts`, and antd's responsive observer is
 * what turns a screen name into a media query — so the only thing declared here is the column COUNT
 * per screen name, which is a layout decision rather than a design value.
 */

import type { BREAKPOINT_TOKENS } from '../theme/tokens';

/**
 * Screen names this module states a column count for, in the spelling antd keys its screen map by.
 *
 * Assumptions: only the three narrow names are listed, and that is sufficient rather than partial.
 * antd resolves a responsive `column` object by walking its breakpoints from widest to narrowest and
 * taking the first that both matches and is present, so a value given at `md` applies at `lg`, `xl`,
 * `xxl` and `xxxl` too. Listing those four as well would be four more places for one decision to be
 * edited and three chances for the edit to be missed.
 */
type RecordColumnScreen = 'xs' | 'sm' | 'md';

/**
 * Column count a record view uses at each breakpoint.
 *
 * Assumptions: ONE column below the medium breakpoint and TWO from it upward. The narrow case is a
 * label-above-value stack, which is what keeps a long address line and a right-aligned money field
 * readable in 375 pixels without the table growing past the viewport; the wide case is the two-up grid
 * the reference's own paired columns most nearly resemble.
 *
 * Trade-offs: collapsing to one column at narrow widths abandons the reference's left/right field
 * PAIRING, which on the account-view customer block corresponds to terminal rows 16, 17 and 18
 * exactly. That pairing is positional identification, which G1 has already surrendered, and the
 * alternative — holding two columns at every width to protect it — protects it only for a sighted
 * operator on a wide screen while making the same block unusable on a narrow one. Reading order is
 * preserved at both widths, which is the property G1 commits to.
 */
export const RECORD_VIEW_COLUMNS: Readonly<Record<RecordColumnScreen, number>> = {
  xs: 1,
  sm: 1,
  md: 2,
};

/**
 * The design-system token whose threshold the column change happens at.
 *
 * Assumptions: exported so a test can assert that the screen name this module changes column count at
 * is the screen name antd derives from that token, rather than the correspondence being asserted only
 * in prose. It is a TYPE-level reference plus a literal rather than a value read from the token module,
 * because the token module publishes the token's NAME and this module publishes the screen name — the
 * check is that the two spellings correspond, which a test performs by transformation.
 */
export const RECORD_VIEW_BREAKPOINT: (typeof BREAKPOINT_TOKENS)['medium'] = 'screenMD';
