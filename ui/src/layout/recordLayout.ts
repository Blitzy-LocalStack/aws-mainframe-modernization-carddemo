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

import type { CSSProperties } from 'react';

import type { GlobalToken } from 'antd';

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

/*
 * Refactoring Rationale: this module gains a FIELD-level measure alongside its record-level column
 * policy, because both answer the same question — how much of the browser one piece of a fixed-width
 * record is entitled to. A review measured the consequence of having no answer for the field case: an
 * eight-character input rendered 1172 pixels wide, and the blank-field marker the reference wrote into
 * the field's own columns landed at x≈1211, roughly 1150 pixels from the value it qualified. The marker
 * cannot be brought to the value by moving the marker; the field has to stop being eight times wider
 * than the data it holds.
 *
 * Assumptions: the width is expressed in `ch`, the advance measure of the font's own zero glyph, which
 * is the browser's nearest equivalent of a character column and the unit the message band already uses
 * for the same purpose. A pixel width would have to be recomputed for every theme and every font, and
 * would be a design value this module is not allowed to hold.
 *
 * Assumptions: the horizontal control padding is ADDED to the declared width rather than absorbed into
 * it. Controls are border-box, so a bare `Nch` maximum would give the text `N` columns minus the
 * design system's own padding on both sides — clipping a value that fills its declared width, which is
 * the normal case for a card number or a timestamp. The allowance is read from the design system's own
 * `controlPaddingHorizontal` rather than written as a number.
 *
 * ⚠️ Assumptions: the result is spread onto the DESIGN-SYSTEM CONTROL itself and not onto a plain
 * wrapper around it, and this is a measured constraint rather than a stylistic preference. The theme
 * scopes its custom properties to component class scopes rather than to the document root: measured in
 * a browser on a rendered screen, `getPropertyValue('--ant-control-padding-horizontal')` returns the
 * empty string on `document.documentElement` and on `document.body`, and returns `12px` on an
 * `.ant-input`. So the padding term resolves on a control and does not resolve on an arbitrary `div`.
 *
 * Trade-offs: the failure mode of getting that wrong is the mildest one available, which is why the
 * token is still read rather than a literal being written as a fallback. An unresolved `var()` inside a
 * `calc()` invalidates the declaration at computed-value time, so the maximum is dropped and the field
 * falls back to `inlineSize: '100%'` — today's full-width behaviour, never a clipped or zero-width
 * control. `CSS.supports` was confirmed true for both the token form and an equivalent literal form,
 * so the expression itself parses; only the resolution depends on the scope.
 *
 * Trade-offs: `inlineSize: '100%'` is kept alongside the maximum, so a field declared wider than the
 * viewport — the 50-character address lines, at a phone width — still shrinks to fit instead of forcing
 * the page to scroll sideways. The declared width is therefore a CEILING and not a fixed size, which
 * is a deliberate departure from the terminal, where every field was exactly its declared width because
 * the display was exactly 80 columns. Design gap G1 already records that departure for position; this
 * is the same trade for size.
 */

/**
 * Sizes a control from the character width its copybook PICTURE clause declares.
 *
 * Purpose: keep a transcribed field the width of the data it carries, so that what sits at the field's
 * right-hand edge — the blank-field marker, a clear affordance, a unit — is adjacent to the value rather
 * than at the far side of the viewport.
 *
 * Assumptions: the caller passes the DECLARED width, which is a constant transcribed from a copybook
 * (`PIC X(08)` gives 8, `PIC S9(10)V99` gives 13 counting the sign and the point as rendered), so a
 * non-positive or fractional value is a coding error rather than a runtime condition. It is refused
 * loudly for that reason: a silently clamped zero renders a control with no visible text area, which
 * reads as a missing field and is far harder to trace back to the call site than a thrown message is.
 * ⚠️ Assumptions: a control that carries a SUFFIX must say so through `markerSlotCharacters`, because
 * the measure below sizes the box the suffix shares. When a suffix is present `@rc-component/input`
 * puts this style on the affix WRAPPER rather than on the input, and the wrapper's inline space is then
 * divided between the input and the suffix slot -- so a maximum computed for the data alone leaves the
 * data itself short by whatever the slot takes. A browser measured that outcome on the narrowest field
 * in the delivery, the two-character transaction-type key: its wrapper was capped at `2ch + 2 * 12px` =
 * 40.8 pixels, of which two borders took 2, the wrapper's own padding 24 and the suffix slot the rest,
 * leaving the input a 12-pixel content box for a 17-pixel value. It rendered `01` as `0:` -- a record's
 * own identity, unreadable, and identically so at 375, 768, 1280 and 1920, because a fixed cap does not
 * vary with the viewport. Its fifty-character sibling on the same screen was unaffected: there the
 * container bound the width long before the cap did, which is why the defect surfaced only where the
 * declared width was small enough for the cap to bind.
 *
 * ⚠️ Assumptions: the allowance is itemised rather than estimated, and it covers TWO costs rather than
 * one. The first is the slot itself: the marker's own characters, plus `paddingXXS` twice -- the value
 * `antd/lib/input/style/token.js` L11 derives `inputAffixPadding` from and
 * `antd/lib/input/style/index.js` L444-L447 applies as the suffix's `margin-inline-start`.
 *
 * ⚠️ Assumptions: the second cost is a UNIT mismatch this module had not accounted for, and it is why
 * one extra cell is reserved beyond the marker's own. `ch` is resolved against the font of the element
 * the declaration sits on, and with a suffix present that element is the affix wrapper, which inherits
 * the theme's PROPORTIONAL face -- while the value inside renders in the wider fixed-pitch face, because
 * the callers put that face on `styles.input` for the reason recorded at their own call sites. A `ch` on
 * the wrapper is therefore about 7.8 pixels while each rendered character advances about 8.4, so the
 * declared width under-reserves by roughly 8 per cent. That is invisible on a fifty-character field,
 * where the container binds long before the cap does, and decisive on a two-character one: it is the
 * difference between the 15.6 pixels `2ch` bought and the 16.81 the value needed. Reserving a whole
 * extra cell covers the shortfall at any declared width without needing both faces' metrics in the
 * expression -- which CSS cannot state -- and leaves the two-character key about 8 pixels clear rather
 * than a fraction of one.
 *
 * Trade-offs: the allowance therefore makes a suffixed field about one and a half characters wider than
 * its data, which is a real departure from this function's purpose of keeping the marker adjacent to the
 * value. It is accepted because the alternative is a field that cannot show its own contents, and
 * because the surplus is bounded and constant rather than proportional -- a fifty-character field grows
 * by the same one and a half characters as a two-character one.
 *
 * Alternatives Considered: (1) giving the inner input a `minInlineSize` floor instead, so it keeps its
 * characters and the wrapper grows around it. Rejected because the wrapper's maximum is what clips --
 * a floor on the child cannot lift a ceiling on the parent, so the value would be cut in the same place
 * by the same number of pixels. (2) Moving the width onto `styles.input`, which would size the input
 * and leave the wrapper free. Rejected because the wrapper is the bordered box an operator sees as the
 * field, so sizing the child would draw a short value inside a full-width border. (3) Widening only the
 * one screen that clipped. Rejected because a shared helper that silently clips whenever a suffix is
 * present is a trap for its next caller, and the marker slot is a contract this module already owns.
 * @param {number} declaredWidth - Character width the field's PICTURE clause declares; a positive
 *   integer.
 * @param {GlobalToken} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`.
 * @param {number} markerSlotCharacters - Characters to reserve for a suffix the control renders inside
 *   the same box, or `0` for a control with no suffix. Pass the marker's own width in characters; the
 *   affix padding around it is added here.
 * @returns {CSSProperties} The inline size and maximum measure to spread onto the control.
 * @throws {RangeError} When `declaredWidth` is not a positive integer, or when
 *   `markerSlotCharacters` is not a non-negative integer.
 */
export function copybookFieldWidthStyle(
  declaredWidth: number,
  cssVar: GlobalToken,
  markerSlotCharacters = 0,
): CSSProperties {
  if (!Number.isInteger(declaredWidth) || declaredWidth <= 0) {
    throw new RangeError(
      `A copybook field width must be a positive integer, got ${String(declaredWidth)}.`,
    );
  }
  if (!Number.isInteger(markerSlotCharacters) || markerSlotCharacters < 0) {
    throw new RangeError(
      `A marker slot must be a non-negative whole number of characters, got ${String(
        markerSlotCharacters,
      )}.`,
    );
  }

  /*
   * Assumptions: with no suffix the expression is byte-identical to the one this function has always
   * returned, so the twelve call sites that render a bare control are unaffected by the parameter's
   * existence. Both allowance terms appear only when a slot was actually asked for, which keeps a
   * suffix-less field exactly as wide as its data plus the control's own padding.
   *
   * Assumptions: the extra cell is added to the marker's own characters rather than to the declared
   * width, so it is charged once per suffixed control and not once per declared character. It covers
   * the proportional-versus-fixed-pitch `ch` shortfall described above, which is a constant fraction of
   * the whole measure rather than of the marker alone -- but on the widest field in the delivery that
   * fraction is absorbed by the container long before the cap binds, so the single cell is sufficient
   * everywhere the cap can actually clip.
   */
  const reservedCells =
    markerSlotCharacters === 0 ? declaredWidth : declaredWidth + markerSlotCharacters + 1;
  const affixAllowance = markerSlotCharacters === 0 ? '' : ` + 2 * ${String(cssVar.paddingXXS)}`;

  return {
    inlineSize: '100%',
    maxInlineSize: `calc(${String(reservedCells)}ch + 2 * ${String(
      cssVar.controlPaddingHorizontal,
    )}${affixAllowance})`,
  };
}

/**
 * Anchors a monetary value to the trailing edge of the record-view cell that holds it.
 *
 * Purpose: make the amounts standing in one rendered column of a record view line up on their decimal
 * points. This is spread onto the CELL — an item's `styles.content` — and not onto the amount, because
 * the defect it removes is where the amount's box sits, not how wide that box is.
 *
 * ⚠️ Purpose: browser measurement is what this exists for, so the numbers are recorded rather than
 * described. On the pending-authorization summary, whose six amounts carry two different declared
 * widths, every amount in a rendered column shared an identical LEADING edge and split into two
 * trailing edges exactly 25.203125px apart — at 1280, at 768 and at 375 alike. The cause is visible in
 * the rects: the value cell computes `text-align: start`, so an `inline-block` amount is placed flush
 * against the cell's leading edge, and its box width comes from its own `min-inline-size` — 12ch
 * resolves to 100.816px and 9ch to 75.6123px, a difference of 25.2037px that reproduces the measured
 * spread to within sub-pixel rounding. The amount's own `text-align: end` positions glyphs INSIDE that
 * box and can do nothing about where the box lands. Because the widths are `ch` on a fixed-pitch face
 * the gap never changes with the viewport, and it is most conspicuous at the narrowest width, where a
 * one-column reflow stacks every amount into a single column.
 *
 * ⚠️ Assumptions: aligning the TRAILING edge rather than the leading one is settled by the mapset, not
 * by taste. `app/app-authorization-ims-db2-mq/bms/COPAU00.bms` L143 to L197 gives each money column a
 * constant start column AND a constant length across both of its rows — column one is `POS=(11,19)`
 * and `POS=(12,19)` at `LENGTH=12`, column two `POS=(11,46)` and `POS=(12,46)` at `LENGTH=9` — so on the
 * terminal both edges coincide and the choice never arises. It arises here only because the responsive
 * reflow folds three declared columns into two rendered ones, or into one, which puts a 12-character
 * field and a 9-character field in a column the reference never puts them in. One invariant has to be
 * chosen, and the trailing edge is the one carrying the observable property: a column of figures whose
 * decimal points line up is the whole reason money is right-aligned in its field at all, and every
 * picture in this population renders exactly two decimal places, so anchoring the trailing edge aligns
 * the points themselves.
 *
 * ⚠️ Alternatives Considered: giving the amount `inlineSize: '100%'` so its box fills the cell and its
 * existing `text-align: end` does the work. Rejected because it dissolves the declared field width into
 * a mere floor and makes the box viewport-dependent, changing the amount's SIZE to fix its PLACEMENT —
 * and the declared width is a transcribed contract that `min-inline-size` states exactly.
 *
 * ⚠️ Alternatives Considered: equalising the pictures so every amount renders the same number of
 * characters. Rejected because it contradicts the oracle: the mapset declares `LENGTH=12` for the first
 * money column and `LENGTH=9` and `LENGTH=10` for the second and third, so the differing widths are the
 * specification rather than the defect.
 *
 * ⚠️ Alternatives Considered: reordering the entries so each rendered column holds one width. Rejected
 * on two counts — at two columns no ordering can distribute three declared columns into two rendered
 * ones, and reordering would disturb the mapset's row-major reading order, which is precisely the
 * property design gap G1 commits to preserving at the same time as it surrenders position.
 *
 * Trade-offs: the leading edges of a column's amounts no longer coincide, and that is accepted because
 * under reflow that edge is not the mapset's declared start column either — it is wherever the
 * responsive grid happened to put the cell.
 * ⚠️ Trade-offs: this is a function returning a fresh object rather than a shared exported constant,
 * even though the value never varies. A single object handed to every caller is one that any caller
 * could mutate for all of them, and the cost of not sharing it is one object literal per cell per
 * render — which is what every other style helper on these screens already costs.
 * @returns {CSSProperties} The style to spread onto a monetary cell's content.
 */
export function monetaryRecordCellStyle(): CSSProperties {
  return { textAlign: 'end' };
}
