/**
 * The single message line of the CardDemo SPA.
 *
 * Purpose
 * -------
 * This is the browser target of the 3270 row-23 `ERRMSG` field and the one
 * place the 75-character message contract is enforced. Every screen renders
 * exactly one band. A screen-level outcome — a rejected sign-on, a completed
 * update, an unsupported function key — arrives here as text plus a severity,
 * and nothing else about the request reaches this module.
 *
 * Provenance (reference-only sources; `app/**` is never modified)
 * --------------------------------------------------------------
 * - `app/cpy/CVCRD01Y.cpy` L28-L30 — the contract itself. L28
 *   `CCARD-ERROR-MSG PIC X(75)`, L29 `CCARD-RETURN-MSG PIC X(75)`, and L30
 *   `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES`, which is the *declared*
 *   no-message state rather than an inferred one.
 * - `app/cpy/CSMSG01Y.cpy` L18-L21 — the two shared message texts,
 *   `CCDA-MSG-THANK-YOU` and `CCDA-MSG-INVALID-KEY`, both `PIC X(50)`. They
 *   are owned by `ui/src/messages/messages.ts` and are deliberately not
 *   restated here; this module renders whatever text it is handed.
 * - `app/bms/COSGN00.bms` L197-L200 — the row-23 field:
 *   `ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)`.
 *   Measured across all 21 mapsets that definition is identical every time:
 *   `COLOR=RED`, `ATTRB=(ASKIP,BRT,FSET)` and `POS=(23,1)` on 21 of 21, with
 *   only `LENGTH=` varying (78 on 19, 80 on 2).
 * - `app/cbl/COSGN00C.cbl` L89 and L149, `app/cbl/COMEN01C.cbl` L100-L101,
 *   `app/cbl/COTRN00C.cbl` L130-L132 — the write path. A program moves a
 *   message constant into its own buffer, moves that into the map's `ERRMSGO`
 *   field, and re-sends the screen. The band is a sink: it never decides what
 *   the message is, and this component mirrors that exactly.
 *
 * What this module deliberately does not own
 * -----------------------------------------
 * Per-field validation errors are a different baseline mechanism and belong
 * elsewhere. `app/cpy/CSSETATY.cpy` L17-L27 moves `DFHRED` into an individual
 * field's colour subfield and a literal `'*'` into that field's data subfield;
 * that is rendered as `Form.Item validateStatus="error"` with `help` text on
 * the owning screen, never as a band message. Message *text* belongs to
 * `ui/src/messages/messages.ts`, every design value to
 * `ui/src/theme/tokens.ts`, and the single theme injection to
 * `ui/src/App.tsx` — so this file contains no user-visible string, no colour
 * literal and no `ConfigProvider`.
 */

import { useEffect, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';

import { Alert, Flex, Tooltip, Typography, theme } from 'antd';

import { MESSAGE_BAND, isMessageBandEmpty, normaliseMessageBandValue } from '../messages/messages';
import type { AntdTokenName } from '../theme/tokens';
import { BMS_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';

/*
 * Alternatives Considered: 78 and 80 were both evaluated as the band width and
 * both were rejected. They are the `ERRMSGI`/`ERRMSGO` display-field widths,
 * and widening the contract to either looks like the obvious correction: 78
 * appears on 15 of the 17 base mapsets (30 declarations) and 80 on exactly 2 —
 * `app/cpy-bms/COCRDSL.CPY` L102/L194 and `app/cpy-bms/COCRDUP.CPY` L108/L212
 * (4 declarations). 15 + 2 accounts for all 17, and across all 21 mapsets the
 * split is 19 to 2, so no mapset is unaccounted for and neither figure is an
 * estimate. Those widths describe how much the screen region can *hold*, not
 * how much content a message may *carry*. The decisive evidence is that the
 * two mapsets with the widest field belong to `COCRDSLC` and `COCRDUPC`, and
 * both of those programs compose their message into a `PIC X(75)` field — as
 * do all five programs that copy `CVCRD01Y` (`COACTUPC`, `COACTVWC`,
 * `COCRDLIC`, `COCRDSLC`, `COCRDUPC`). The widest display field in the whole
 * baseline is therefore driven by 75-character content, which is what makes 75
 * the contract and 78/80 display slack.
 *
 * Refactoring Rationale: the contract is attributed to `CVCRD01Y.cpy` and not
 * to `COCOM01Y.cpy`. The migration plan cites `COCOM01Y.cpy` as this file's
 * source and that citation is wrong — `COCOM01Y.cpy` declares no `PIC X(75)`
 * field at all. It carries navigation, identity and selection context
 * (`CDEMO-FROM-TRANID`, `CDEMO-USER-TYPE`, `CDEMO-LAST-MAPSET` and the
 * customer/account/card groups) and no message field of any width. Searching
 * `app/cpy/` for `X(75)` returns exactly two lines, both in `CVCRD01Y.cpy`.
 * This is recorded because a reader following the plan would otherwise open
 * the wrong copybook, find nothing, and have no way to tell whether the
 * contract or the citation was at fault.
 *
 * Assumptions: the numeral is read from `MESSAGE_BAND.workAreaWidth` rather
 * than written again here. `ui/src/messages/messages.ts` already derives that
 * value from `CVCRD01Y.cpy` L28-L29 and records the four other widths a band
 * message passes through. A second literal `75` would give one contract two
 * sources that could drift apart with no build failure to catch it.
 */

/**
 * Width, in characters, of the message-band content contract: 75.
 *
 * This is the width of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`
 * (`app/cpy/CVCRD01Y.cpy` L28-L29), the two `PIC X(75)` fields that carry
 * every band message across the baseline's pseudo-conversational boundary. The
 * band is sized to hold this many characters. It is explicitly *not* the
 * display-field width, which is 78 or 80 depending on the mapset and which
 * `ui/src/messages/messages.ts` models per screen.
 */
export const MESSAGE_BAND_CONTENT_WIDTH = MESSAGE_BAND.workAreaWidth;

/*
 * Alternatives Considered: including `"warning"`, which antd's `Alert`
 * supports as a fourth type. It is excluded because the design-system mapping
 * for the message line names exactly three severities, and because the
 * baseline band has no warning state to migrate: the row-23 field is
 * `COLOR=RED` on 21 of 21 mapsets, and a warning tier would therefore be an
 * invention rather than a translation. Screens that need a caution tone use
 * `"info"`; nothing in the baseline is lost by the omission.
 */

/**
 * The three severities the message band can render.
 *
 * Each value maps one-to-one onto an antd `Alert` type and onto a measured BMS
 * colour, so a severity is simultaneously the accessibility signal, the colour
 * decision and the alert variant.
 */
export type MessageBandSeverity = 'error' | 'success' | 'info';

/*
 * Assumptions: the empty band is deliberately contentless, so it exposes no
 * text, role or accessible name that a query could find. That makes a stable
 * data attribute the only way to assert the invariant this component exists to
 * guarantee — that the band occupies the same space whether or not a message
 * is present. Exporting the value rather than inlining the string keeps the
 * assertion and the element on one definition; a copy in each test file could
 * drift from the component and would fail as a "missing element" rather than
 * as the contract change it actually was.
 */

/**
 * Stable `data-testid` on the band's outer element, present in both the empty
 * and populated states so the reserved-space contract can be asserted.
 */
export const MESSAGE_BAND_TEST_ID = 'message-band';

/*
 * Alternatives Considered: making the always-present band element a live
 * region of its own with `aria-live`. That was rejected on evidence rather
 * than on taste: antd's `Alert` already renders `role="alert"` on its root
 * element, so a live region on the wrapper would nest two of them and a single
 * message would be announced twice. Leaving the `Alert` as the only live
 * region and choosing the role it renders per severity gives one announcement
 * with the right urgency — `alert` is assertive and interrupts, which suits a
 * rejection the operator must act on, while `status` is polite and suits a
 * confirmation that should not cut across whatever a screen reader is already
 * reading. `AlertProps.role` is a declared prop that antd spreads after its
 * own default, so this overrides the default rather than fighting it.
 *
 * Assumptions: announcing at all is fidelity rather than embellishment for
 * most screens, but not for all, and the difference is measured. 14 of the 21
 * mapsets set `CTRL=(ALARM,FREEKB)` on their `DFHMSD`, which sounded the
 * terminal alarm as the screen was re-sent; the remaining 7 — `COACTUP`,
 * `COACTVW`, `COCRDLI`, `COCRDSL`, `COCRDUP`, `COTRTLI` and `COTRTUP` — set
 * only `FREEKB` and were silent. A band cannot know which mapset it stands in,
 * so it announces uniformly. The alternative, staying silent to match the
 * quieter 7, would drop the alarm on the 14 that had one, which is the larger
 * fidelity loss of the two.
 */
const SEVERITY_ALERT_ROLES = {
  error: 'alert',
  success: 'status',
  info: 'status',
} as const satisfies Record<MessageBandSeverity, 'alert' | 'status'>;

/*
 * Assumptions: these three token names come from the measured BMS colour
 * bridge and not from this file. `RED` resolves to the error token, `GREEN` to
 * the success token and `TURQUOISE` to the informational token, so the only
 * design decision made here is which BMS colour each severity corresponds to;
 * the mapping from BMS colour to design-system token stays in
 * `ui/src/theme/tokens.ts`, where its rationale and measured frequencies live.
 * Naming the token and resolving its value from the live theme is what keeps
 * this component free of colour literals: under CSS-variable theming a literal
 * would not merely duplicate a token, it would opt this element out of the
 * theme silently.
 */
const SEVERITY_COLOR_TOKENS = {
  error: BMS_COLOR_TOKENS.RED,
  success: BMS_COLOR_TOKENS.GREEN,
  info: BMS_COLOR_TOKENS.TURQUOISE,
} as const satisfies Record<MessageBandSeverity, AntdTokenName>;

/*
 * Assumptions: error is the default because the baseline field has no other
 * appearance. `ERRMSG` is `COLOR=RED` on 21 of 21 mapsets unconditionally —
 * the field is red whether it carries a rejection or the sign-off
 * acknowledgement — so a caller that supplies text without a severity gets the
 * appearance the terminal always gave it. Requiring the prop was the
 * alternative; it would force all 21 screens to restate a value the source
 * never varies.
 */
const DEFAULT_SEVERITY: MessageBandSeverity = 'error';

/*
 * Trade-offs: the `Alert` is a flex item with a zero basis and a zero minimum
 * inline size rather than a full-width block. `flex: 1 1 0` lets it fill the
 * band, and `minInlineSize: 0` is what allows it to shrink below its own
 * content width — without it a flex item refuses to go under its min-content
 * size, the text never becomes narrower than the message, and the ellipsis
 * below can never engage. The cost is two structural declarations that look
 * redundant next to a plainer full-width rule; the benefit is that the
 * over-long-message policy actually takes effect at every viewport.
 *
 * Assumptions: these two are STRUCTURAL values and not design values, so the
 * zero-hardcoded-values rule is not in play here — the rule governs colour,
 * spacing, radius and typography, and permits `0` explicitly. They are written as
 * a style object because the design system offers no route to them: its layout
 * primitive does expose a `flex` prop, but that prop sets the shorthand on the
 * primitive ITSELF, and the element that needs it here is the alert inside the
 * primitive. Reaching it through a prop would mean nesting the alert in a second
 * layout primitive whose only purpose is to carry one declaration, which this
 * tree's component rules flatten away. Two declarations on the item are the
 * smaller of the two costs.
 */
const ALERT_STYLE: CSSProperties = { flex: '1 1 0', minInlineSize: 0 };

/*
 * Assumptions: the empty state is a declared state, not an inferred one.
 * `app/cpy/CVCRD01Y.cpy` L30 defines `88 CCARD-RETURN-MSG-OFF VALUE
 * LOW-VALUES`, and the programs additionally clear the field with
 * `MOVE SPACES`, so "no message" arrives as binary zeros or as blanks
 * depending on the path. `isMessageBandEmpty` already resolves both
 * conventions, and it is re-exported here rather than reimplemented so the
 * predicate a caller tests with and the predicate this component renders by
 * cannot disagree about what "empty" means. Writing a second, band-local
 * predicate was the alternative and would have produced exactly that class of
 * silent divergence.
 */
export { isMessageBandEmpty };

/**
 * Props accepted by {@link MessageBand}.
 *
 * Deliberately narrow: text and a severity, nothing else. The band is
 * presentational and stateless.
 */
export interface MessageBandProps {
  /*
   * Alternatives Considered: accepting the API error object directly, so a
   * screen could hand a failed response straight to the band. Rejected because
   * it would blur the split the design-system mapping draws: per-field
   * validation errors belong to `Form.Item validateStatus="error"` with `help`
   * text, and only a screen-level message belongs here. A band that understood
   * the transport type would invite callers to pour a field-error array into
   * the one line reserved for the screen's own message, so callers map an
   * error to text and this module never imports from `ui/src/api`.
   *
   * Assumptions: `null` and `undefined` are both admitted, and the union is
   * written out rather than left to the optional marker because
   * `exactOptionalPropertyTypes` is enabled — under that setting an omitted
   * property and an explicitly `undefined` one are different, and a caller
   * holding `string | null | undefined` from a response body could not pass it
   * through without the explicit `undefined`.
   */

  /**
   * Screen-level message text, or `null`/`undefined` when there is none. Empty,
   * all-blank and `LOW-VALUES` values all mean "no message".
   */
  readonly message?: string | null | undefined;

  /**
   * Severity governing the alert variant, the message colour and the ARIA
   * role. Defaults to `"error"`, the appearance the source field always had.
   */
  readonly severity?: MessageBandSeverity | undefined;
}

/**
 * Renders the screen's single message line, reserving its space at all times.
 *
 * The band always occupies the same height. When there is no message it
 * renders that space and nothing else, so a message appearing or clearing
 * never moves the content around it — the browser analogue of row 23 always
 * existing on a 24-row terminal. When there is a message it renders an antd
 * `Alert` whose type, colour and ARIA role all follow the severity.
 * @param {MessageBandProps} props - The component's props, destructured below.
 * @param {string | null | undefined} props.message - Message text for the
 *   band, or `null`/`undefined` when the response carried none. A value that
 *   is empty, all blanks, or the `LOW-VALUES` sentinel is treated as no
 *   message.
 * @param {MessageBandSeverity} props.severity - Severity to render with;
 *   defaults to `"error"`.
 * @returns {ReactElement} The band element: reserved space alone when there is
 *   no message, otherwise reserved space containing the alert.
 */
export function MessageBand({
  message,
  severity = DEFAULT_SEVERITY,
}: MessageBandProps): ReactElement {
  /*
   * Alternatives Considered: the `token` member of the same hook, which is the
   * obvious one to reach for and is what this component used until the accessor
   * contract was checked against the package. It returns RESOLVED values — the
   * error colour comes back as a hex string and the large control height as a
   * number — so writing one into a `style` attribute bakes today's palette into
   * the element. Under CSS-variable theming that is a literal in every sense that
   * matters: it opts the element out of the theme silently, and a later token
   * change leaves this one band behind with nothing failing to say so. Verified
   * from the pinned package rather than assumed: the public hook destructures the
   * library's internal tuple positionally, so its `token` is the resolved token
   * object and its `cssVar` is the `var(--…)` reference form of the same names,
   * typed identically.
   *
   * Assumptions: the reference form is safe for the two numeric tokens this
   * component reads, and that is a property of how the variables are emitted
   * rather than a hope. The style layer appends `px` to a numeric token when it
   * writes the custom property, unless the token is on its unitless list — so the
   * large control height and the body font size arrive as lengths, while the
   * strong font weight is on that list and arrives as a bare number, which is
   * exactly what the weight property needs. A token whose declaration carried no
   * unit would produce an invalid length and be dropped silently, which is why
   * this was checked before switching rather than after.
   *
   * Trade-offs: `ui/src/layout/ScreenHeader.tsx` already resolves its two design
   * values this way, so standardising here removes a split in which two sibling
   * shell components read the same bridge through two different mechanisms. The
   * cost is that a value read this way cannot be inspected in a test that has no
   * browser to resolve the variable, which is why the assertions that matter for
   * this component are the reserved-height and empty-state ones rather than colour
   * equality.
   */
  const { cssVar } = theme.useToken();

  /*
   * Refactoring Rationale: the normalised value is resolved before the hooks
   * below rather than immediately before it is rendered, because the truncation
   * effect depends on it - a new message has to be re-measured - and a hook
   * cannot be declared after the early return for the empty state.
   */
  const text = normaliseMessageBandValue(message);

  /*
   * Refactoring Rationale: the truncation state is measured here rather than
   * delegated to the text component's own ellipsis tooltip, and the reason is a
   * measured limitation of the pinned version rather than a preference. That
   * configuration does gate its reveal on real truncation, which is the half of
   * this that matters most - but it drives the reveal from its own pointer state
   * and does not forward a trigger list to the tooltip it renders, so a `focus`
   * trigger is silently dropped. Confirmed in a browser three ways: a programmatic
   * focus and a dispatched focus event both produced no tooltip while a pointer
   * event on the same element at the same instant produced one; the tooltip hid
   * the moment the pointer left even though focus was retained; and a real
   * Shift+Tab/Tab back onto the truncated text produced no tooltip and no
   * `aria-describedby`. A reveal a keyboard cannot summon does not answer the
   * finding, because the 3270 original was operated entirely from the keyboard.
   *
   * Alternatives Considered: passing `open` through the ellipsis configuration's
   * tooltip props, which does override the component's internal state because
   * those props are spread after it. Rejected because taking over `open` also
   * takes over the truncation gate the configuration was being used for, leaving
   * exactly the measurement below to be written anyway - with the reveal now
   * fighting the component for control of the same state.
   */
  const [messageTextElement, setMessageTextElement] = useState<HTMLSpanElement | null>(null);
  const [isMessageTruncated, setIsMessageTruncated] = useState(false);

  useEffect(
    /**
     * Tracks whether the rendered message is actually clipped.
     *
     * Assumptions: the comparison is the element's scroll width against its
     * client width, which is the only reliable read of single-line ellipsis
     * state - the ellipsis is applied by the stylesheet, so no event announces
     * it. The observer re-measures on every size change because the band's
     * inline size is capped in character units and therefore moves with the
     * viewport: a message that fits at one width clips at another, and a reveal
     * that measured once at mount would be wrong for the rest of the session.
     *
     * Assumptions: the observer is created only when the platform provides one,
     * so a non-browser environment falls back to the mount-time measurement
     * rather than throwing. The measurement itself needs no observer to be
     * correct at the width it was taken.
     *
     * Assumptions: a zero client width is treated as "not measurable" rather than
     * as truncation, and the distinction is not theoretical. Any scroll width
     * beyond zero exceeds a zero client width, so an element that has no laid-out
     * inline size at the moment it is read - because an ancestor is not displayed,
     * or because the viewport is mid-change and the browser has produced an
     * intermediate frame - would otherwise report every message as clipped and
     * give the band a tab stop and a reveal it does not need. Observed in a
     * browser during an abrupt two-step viewport change, where all three
     * unclipped messages briefly flipped to clipped and back within about 50ms.
     * @returns {(() => void) | undefined} Observer teardown, or `undefined` when
     * nothing was observed.
     */
    function trackMessageTruncation(): (() => void) | undefined {
      if (messageTextElement === null) {
        setIsMessageTruncated(false);
        return undefined;
      }

      /**
       * Re-reads the clipped state from laid-out geometry.
       * @returns {void} Completion is the updated truncation state.
       */
      function measure(): void {
        setIsMessageTruncated(
          messageTextElement !== null &&
            messageTextElement.clientWidth > 0 &&
            messageTextElement.scrollWidth > messageTextElement.clientWidth,
        );
      }

      measure();

      if (typeof ResizeObserver === 'undefined') {
        return undefined;
      }

      const observer = new ResizeObserver(measure);
      observer.observe(messageTextElement);

      /**
       * Stops observing the element this effect measured.
       * @returns {void} The observer no longer reports size changes.
       */
      function stopObserving(): void {
        observer.disconnect();
      }

      return stopObserving;
    },
    [messageTextElement, text],
  );

  /*
   * Trade-offs: the reserved height is fixed rather than a minimum, so the
   * band's outer height is constant by construction instead of by measurement.
   * A minimum would leave the two states equal only while the alert's
   * intrinsic height happened to stay under it, making zero layout shift a
   * property that held by coincidence and could regress on a theme change
   * without any test noticing. What is given up is tolerance: under a theme
   * whose font makes the alert taller than one large control, the overflow
   * rule clips a few pixels rather than letting the band grow. That is the
   * right way round for this element, because growth would move every screen's
   * content and clipping does not.
   *
   * Assumptions: the height comes from the design system's own large-control
   * token. `ui/src/theme/tokens.ts` declares no height token because the fixed
   * 24x80 character grid is a documented, deliberate deviation, so there is no
   * measured BMS height to bridge — and a pixel literal is not an option. A
   * control-height token is the system's nearest expression of "one
   * single-line control", which is exactly what one terminal row became.
   *
   * Refactoring Rationale: `display` is declared here even though a `Flex` is
   * already a flex container, and it is not redundant — without it the empty
   * band collapsed to zero height and reserved nothing, which is the exact
   * failure this component exists to prevent. antd's own Flex stylesheet ships
   * `:where(.css-dev-only-do-not-override-<hash>).ant-flex:empty{display:none}`,
   * and the empty branch below renders a `Flex` with no children, so it matched
   * that selector. Setting the property inline outranks a stylesheet rule that
   * carries no `!important`, so the band keeps its box in both states. This was
   * found by measuring real laid-out geometry in a browser: a jsdom test cannot
   * catch it, because the inline `blockSize` it can see was correct all along
   * and only the CSS cascade suppressed the box.
   *
   * Assumptions: the `ch` basis is pinned to the design system's body font
   * rather than inherited. `ch` is the advance measure of the font's own `0`
   * glyph, so an inherited face makes the 75-character cap resolve against
   * whatever ancestor typography happens to apply: measured in a browser, the
   * cap came out at 600px purely because a 16px serif fallback was inheriting
   * in, and it would land somewhere else again under any other shell. Pinning
   * the family and size to the same tokens the message text renders in makes
   * the cap mean 75 characters of that text — measured at 590.1px, the antd
   * body font's 7.87px `0` advance times 75 — deterministically, wherever the
   * band is mounted.
   *
   * Assumptions: the three declarations that carry no token — the display mode,
   * the full inline size and the overflow rule — are STRUCTURAL rather than
   * design values, and none of them has a prop on the layout primitive to go
   * through instead. Its props cover direction, wrapping, main-axis and
   * cross-axis alignment, the gap, the rendered element, and the flex shorthand
   * the primitive applies to itself as an item; a container's own inline size, its
   * overflow behaviour and an override of the library's own empty-container rule
   * are outside that set. They are recorded here so that a reader looking for the
   * prop equivalent knows the search has already been done, and so that the four
   * declarations this object no longer carries — the single-line ellipsis
   * treatment, now the text component's own — are not put back by hand.
   */
  const bandStyle: CSSProperties = {
    display: 'flex',
    inlineSize: '100%',
    maxInlineSize: `${MESSAGE_BAND_CONTENT_WIDTH}ch`,
    blockSize: cssVar.controlHeightLG,
    fontFamily: cssVar.fontFamily,
    fontSize: cssVar.fontSize,
    overflow: 'hidden',
  };

  if (isMessageBandEmpty(text)) {
    /*
     * Assumptions: the empty band is pure reserved space, so it is hidden from
     * assistive technology. Exposing an empty element would offer a screen
     * reader something to land on that conveys nothing; hiding it states that
     * the element is there for layout alone. Nothing is lost when a message
     * arrives, because the alert that replaces this branch brings its own live
     * region and is announced on insertion.
     */
    return (
      <Flex
        align="center"
        aria-hidden="true"
        data-testid={MESSAGE_BAND_TEST_ID}
        style={bandStyle}
      />
    );
  }

  /*
   * Assumptions: colour and weight are both applied explicitly, overriding
   * antd's defaults, because the source field states both. The row-23 field is
   * `COLOR=RED` with `ATTRB=(ASKIP,BRT,FSET)` on 21 of 21 mapsets, and antd's
   * alert renders its title in the ordinary text colour at the ordinary
   * weight — so accepting the component defaults here would silently drop two
   * attributes the baseline sets on every screen. Both values are resolved
   * from the live theme by the token names above rather than written as
   * literals.
   *
   * Trade-offs: an over-long message is truncated visually and never in the
   * data. The full string stays the element's child, so it remains intact in
   * the DOM and is announced in full, while the component's own single-line
   * ellipsis treatment confines it to the reserved line. Slicing the value at 75
   * was the alternative and was rejected: the display region is 78 or 80
   * characters wide depending on the mapset, so cutting the string at the
   * 75-character content contract would discard characters the baseline itself
   * can render, and it would discard them irreversibly rather than merely
   * off-screen. What is given up is that a clipped tail is not visible at a
   * glance, which the reveal configured below mitigates.
   *
   * Refactoring Rationale: the overflow, white-space and text-overflow
   * declarations that used to sit in this object are gone, and so is the
   * `display` declaration that made them apply to an inline element. The
   * component's own single-line ellipsis treatment sets all four - it renders the
   * text element as an inline block capped at the container's inline size and
   * applies the three overflow properties - so writing them here restated the
   * component's own styling while also making this object look like the authority
   * on truncation, which it was not. Only the two design values remain, which is
   * what this object is for.
   *
   * Trade-offs: the 75-character cap sizes the band, so the text itself gets
   * that width less the alert's icon and padding — measured at 542px of a
   * 590px band, a 48px difference. A typical 75-character message needs about
   * 460px and fits with room to spare, but one made entirely of the widest
   * glyphs would ellipsize a few characters early. Widening the band by the
   * alert's chrome was the alternative and was rejected because that chrome is
   * a component internal with no token to read it from, so the correction would
   * have had to be a hard-coded pixel figure that silently rots the next time
   * the component's padding changes. Sizing the band to the contract and
   * letting the documented clipping policy above cover the worst case keeps
   * every value tokenised.
   */
  const messageTextStyle: CSSProperties = {
    color: cssVar[SEVERITY_COLOR_TOKENS[severity]],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };

  /*
   * Refactoring Rationale: the reveal is driven by MEASURED truncation rather
   * than by a character count, and the count is what was wrong before. A native
   * `title` was attached only when the text exceeded the 75-character content
   * contract, on the assumption that 75 characters is where clipping begins. It is
   * not: the band is sized to 75 characters, and the alert's icon and padding then
   * take roughly 48px of that, so the text region holds about 69 characters of
   * average width and fewer of wide ones. The reveal was therefore absent for
   * exactly the messages that had begun to clip. The component's ellipsis
   * configuration measures the rendered element instead and opens the reveal only
   * while the text is actually ellipsized, so the trigger condition is the
   * condition itself rather than a proxy for it.
   *
   * Alternatives Considered, both rejected: widening the band by the alert's
   * chrome so that 75 characters always fit, which would require a hard-coded
   * pixel figure for a component internal that exposes no token and would rot the
   * next time that component's padding changed; and rendering the run of text as
   * the paragraph component instead, whose ellipsis configuration additionally
   * offers an expandable affordance - a real button, and the more discoverable
   * control of the two. The text component deliberately omits `expandable` and
   * `rows` from its own ellipsis type, so that alternative is a component swap
   * rather than a prop, and it is rejected on the band's shape: expanding puts the
   * text on further lines, and this band has a fixed reserved height with
   * `overflow: hidden`, so the expanded tail would be clipped by the very rule
   * that stops a message moving every screen's content.
   *
   * Trade-offs: the reveal is a design-system tooltip triggered by hover AND by
   * focus, and the element is given a tab stop so that focus can reach it. Two
   * costs are accepted for that. A native `title` needs no tab stop, but it is
   * offered to a pointer only - a keyboard user cannot summon it and a touch user
   * cannot hover - and the 3270 original was operated entirely from the keyboard,
   * so a status message whose tail only a mouse can read is a fidelity loss rather
   * than a cosmetic one. The second cost is the tab stop itself, and it is bounded:
   * the empty branch above renders no text element at all, so the band adds a stop
   * only while it is actually carrying a message, and the message is the one thing
   * on the screen the operator is most likely to want to read. The full string is
   * in the DOM either way, so a screen reader was never the audience this fixes.
   */
  /*
   * Assumptions: the trigger list is declared with its element type written out
   * rather than inferred, because the design system's tooltip declares the prop
   * as a mutable array of its own action union. An inferred literal array widens
   * to `string[]`, which that prop rejects, and a read-only tuple is rejected for
   * being read-only - so naming the two members is the only form that compiles
   * without a type assertion.
   */
  const messageRevealTriggers: ('hover' | 'focus')[] = ['hover', 'focus'];

  /*
   * Refactoring Rationale: the tooltip is always in the tree and is held closed
   * when there is nothing to reveal, rather than being wrapped around the text
   * only while the text is clipped. Conditional wrapping was written first and
   * rejected on a real failure mode: adding or removing a parent changes the
   * element tree, so the text element unmounts and remounts, which fires the
   * measuring ref with `null` and then with a new node - and since the measurement
   * is what decides whether to wrap, the two would drive each other. Keeping the
   * tree shape fixed and controlling the open state instead makes the reveal a
   * function of the measurement rather than a cause of it.
   *
   * Assumptions: both props are supplied by spreading an object that either holds
   * them or is empty, because `exactOptionalPropertyTypes` is enabled: an explicit
   * `undefined` is not the same as an absent property, and only absence leaves the
   * tooltip uncontrolled so that its own trigger handling applies.
   *
   * Trade-offs: leaving the open state to the design system means inheriting one
   * behaviour of its trigger handling, and it is recorded rather than worked
   * around. It keeps a single open state for the whole trigger set, so the most
   * recent trigger event decides: sweeping the pointer across a message that is
   * already keyboard-focused, and then off it, closes the reveal even though focus
   * has not moved - tabbing away and back re-opens it. Observed in a browser and
   * accepted, because the alternative is to take over `open` entirely and rebuild
   * the component's own focus-and-hover state machine here, which would put a
   * second implementation of it in the tree to keep in step with the first. A
   * keyboard-only operator, which is the audience this reveal exists for, never
   * produces the pointer event that triggers it.
   */
  const messageRevealProps: { open?: false } = isMessageTruncated ? {} : { open: false };

  /*
   * Trade-offs: the tab stop exists only while the message is clipped, so the
   * band adds one to the screen exactly when it has something a pointer-free user
   * cannot otherwise read, and none at all the rest of the time. What is given up
   * is that the tab order changes when the viewport is resized across the width at
   * which a particular message starts to clip. That is accepted because the
   * alternative - a permanent stop on every message band on all 21 screens - is a
   * cost paid on every screen for a case that arises on few, and because a stop
   * that reveals nothing is the defect the finding described rather than a
   * mitigation of it.
   */
  const messageFocusProps: { tabIndex?: 0 } = isMessageTruncated ? { tabIndex: 0 } : {};

  return (
    <Flex align="center" data-testid={MESSAGE_BAND_TEST_ID} style={bandStyle}>
      {/*
       * Alternatives Considered: `Typography.Text` alone for the whole band,
       * which the fixed-width source field superficially resembles. Rejected
       * because the design-system mapping names `Alert` for the message line
       * and supplies the three severities through its `type`, and because
       * `Text` carries neither a severity treatment nor an ARIA role — the
       * announcement and the icon would both have had to be rebuilt by hand.
       * `Text` is still used, but for what it is good at: the styled run of
       * text inside the alert's title slot.
       *
       * Assumptions: the text is passed as `title` rather than `message`.
       * antd 6.4 renamed the slot and warns on `message` as deprecated, so the
       * older prop would emit a console deprecation on every render while
       * resolving to the same slot.
       *
       * Assumptions: `showIcon` is enabled so severity is not carried by
       * colour alone. The icon is additive — the terminal had none — and it is
       * what keeps a red, a green and a turquoise message distinguishable to a
       * reader who cannot separate those hues.
       */}
      <Alert
        role={SEVERITY_ALERT_ROLES[severity]}
        showIcon
        style={ALERT_STYLE}
        title={
          /*
           * Assumptions: the text component is asked only for the single-line
           * ellipsis treatment, with no tooltip configuration of its own, so the
           * stylesheet supplies the overflow behaviour and the reveal stays under
           * this component's control. Passing a tooltip there as well would render
           * a second, pointer-only tooltip over the same text.
           */
          <Tooltip title={text} trigger={messageRevealTriggers} {...messageRevealProps}>
            <Typography.Text
              ellipsis
              ref={setMessageTextElement}
              style={messageTextStyle}
              {...messageFocusProps}
            >
              {text}
            </Typography.Text>
          </Tooltip>
        }
        type={severity}
      />
    </Flex>
  );
}
