/**
 * The single message line of the CardDemo SPA.
 *
 * Purpose
 * -------
 * This is the browser target of the 3270 row-23 `ERRMSG` field. Every screen
 * renders exactly one band. A screen-level outcome — a rejected sign-on, a
 * completed update, an unsupported function key — arrives here as text, a
 * severity and the mapset it stands in, and nothing else about the request
 * reaches this module.
 *
 * The message contract has two halves and this module enforces the second.
 * `CCARD-ERROR-MSG`/`CCARD-RETURN-MSG` are `PIC X(75)`, so 75 is the CONTENT
 * limit for any message that crosses the shared work area; the region a message
 * is RENDERED in is the `ERRMSGI`/`ERRMSGO` display field, which is 78
 * characters on 19 mapsets and 80 on `COCRDSL` and `COCRDUP`. The band is sized
 * to the display width of the mapset it is standing in, so a message composed
 * straight into the 80-byte `WS-MESSAGE` buffer — which 14 online programs do,
 * the authorization and transaction-type extension screens among them — is
 * never clipped to the narrower work-area figure.
 *
 * Provenance (reference-only; `app/**` is never modified): the contract is
 * `app/cpy/CVCRD01Y.cpy` L28-L30, including the declared no-message state
 * `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES`; the rendered field is
 * `app/bms/COSGN00.bms` L197-L200, whose definition is identical on 21 of 21
 * mapsets apart from `LENGTH=`; the write path is `app/cbl/COSGN00C.cbl` L89 and
 * L149. `docs/architecture/service-catalog.md` records the mapset inventory.
 *
 * Assumptions: the band is a SINK, exactly as the baseline's `ERRMSGO` field is.
 * It never decides what a message says, so this file contains no user-visible
 * string, no colour literal and no `ConfigProvider` — text belongs to
 * `ui/src/messages/messages.ts`, design values to `ui/src/theme/tokens.ts`, the
 * theme injection to `ui/src/App.tsx`. Per-field validation errors are a
 * different baseline mechanism (`app/cpy/CSSETATY.cpy` L17-L27) rendered as
 * `Form.Item validateStatus="error"` on the owning screen, never here.
 */

import { useEffect, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';

import { Alert, Flex, Tooltip, Typography, theme } from 'antd';

import {
  MESSAGE_BAND,
  isMessageBandEmpty,
  messageBandWidthForMapset,
  normaliseMessageBandValue,
} from '../messages/messages';
import type { MapsetName } from '../messages/messages';
import type { AntdTokenName } from '../theme/tokens';
import { BMS_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';

/*
 * Alternatives Considered: sizing the band to the 75-character work area instead of
 * to the mapset's DISPLAY width. Rejected on a measurement of the `ERRMSGI`/`ERRMSGO`
 * declarations: 19 mapsets declare `PIC X(78)` and two — `app/cpy-bms/COCRDSL.CPY`
 * and `app/cpy-bms/COCRDUP.CPY` — declare `PIC X(80)`, with every `DFHMDF LENGTH=`
 * operand agreeing. A 75-character band would clip characters the baseline itself
 * renders, and would clip them on exactly the messages that reach the screen without
 * passing through the work area: 14 online programs compose straight into an 80-byte
 * `WS-MESSAGE` buffer. Both limits are real; only the display half is a rendering
 * constraint.
 *
 * Assumptions: every width is read from `ui/src/messages/messages.ts`, which holds
 * the exhaustive per-mapset display table. A second literal here would give one
 * contract two sources that could drift apart with no build failure to catch it.
 *
 * Assumptions: the contract is attributed to `CVCRD01Y.cpy`, not to the
 * `COCOM01Y.cpy` the migration plan cites — that copybook declares no `PIC X(75)`
 * field at all, carrying navigation, identity and selection context instead. Noted
 * because a reader following the plan would open the wrong copybook, find nothing,
 * and have no way to tell whether the contract or the citation was at fault.
 */

/**
 * Width, in characters, of the message-band CONTENT contract: 75.
 *
 * This is the width of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`
 * (`app/cpy/CVCRD01Y.cpy` L28-L29), the two `PIC X(75)` fields that carry a band
 * message across the baseline's pseudo-conversational boundary. It bounds what a
 * message crossing the work area may CARRY. It is explicitly not the width the
 * band is sized to — see {@link MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH}.
 */
export const MESSAGE_BAND_CONTENT_WIDTH = MESSAGE_BAND.workAreaWidth;

/**
 * Display width, in characters, used when a caller names no mapset: 78.
 *
 * This is the `ERRMSGI`/`ERRMSGO` width on 19 of the 21 mapsets, so it is the
 * regime that applies unless a screen names itself as one of the two exceptions.
 * Defaulting to the narrower of the two figures is deliberate: a screen that
 * forgets its mapset renders at the width nineteen of them use rather than at the
 * width only two do.
 */
export const MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH = MESSAGE_BAND.displayWidthStandard;

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
 * Assumptions: the empty band is deliberately contentless, exposing no text, role
 * or accessible name a query could find, so a stable data attribute is the only way
 * to assert the invariant this component exists to guarantee — that the band
 * occupies the same space whether or not a message is present. It is exported
 * rather than inlined so a drifting copy in a test file cannot fail as a "missing
 * element" rather than as the contract change it actually is.
 */

/**
 * Stable `data-testid` on the band's outer element, present in both the empty
 * and populated states so the reserved-space contract can be asserted.
 */
export const MESSAGE_BAND_TEST_ID = 'message-band';

/*
 * Alternatives Considered: making the always-present band element a live region of
 * its own with `aria-live`. Rejected because antd's `Alert` already renders
 * `role="alert"` on its root, so a live region on the wrapper would nest two and
 * announce one message twice. Choosing the role the `Alert` renders per severity
 * gives one announcement with the right urgency: `alert` is assertive and
 * interrupts, which suits a rejection the operator must act on, while `status` is
 * polite and suits a confirmation.
 *
 * Trade-offs: the band announces uniformly, although the baseline did not — 14 of
 * the 21 mapsets set `CTRL=(ALARM,FREEKB)` and sounded the terminal alarm, and 7
 * set only `FREEKB` and were silent. A band cannot know which mapset it stands in
 * for this purpose, and staying silent to match the quieter 7 would drop the alarm
 * on the 14 that had one, which is the larger fidelity loss of the two.
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
 * Trade-offs: `minInlineSize: 0` is what lets the `Alert` shrink below its own
 * content width — without it a flex item refuses to go under its min-content size,
 * the text never becomes narrower than the message, and the ellipsis can never
 * engage. Both declarations are STRUCTURAL rather than design values, so the
 * zero-hardcoded-values rule is not in play; it governs colour, spacing, radius and
 * typography and permits `0` explicitly. They are a style object because the layout
 * primitive's `flex` prop sets the shorthand on the primitive ITSELF, and the
 * element that needs it is the alert inside it.
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
   * Alternatives Considered: accepting the API error object directly, so a screen
   * could hand a failed response straight to the band. Rejected because it would
   * blur the split the design-system mapping draws — per-field validation errors
   * belong to `Form.Item validateStatus="error"`, only a screen-level message
   * belongs here — and would invite callers to pour a field-error array into the
   * one reserved line. Callers map an error to text, and this module never imports
   * from `ui/src/api`.
   *
   * Assumptions: the `undefined` arm is written out rather than left to the
   * optional marker because `exactOptionalPropertyTypes` is enabled, under which an
   * omitted property and an explicitly `undefined` one differ — so a caller holding
   * `string | null | undefined` from a response body could not otherwise pass it.
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

  /*
   * Alternatives Considered: deriving the width from the route instead of taking the
   * mapset as a prop. Declined because a route is a target shape this migration
   * chose, whereas the mapset is the reference identity the width is a property of —
   * so a renamed route would silently change a rendering contract. The two widths
   * follow no rule either: `COCRDSL` and `COCRDUP` simply declare a wider field, so
   * the table in `ui/src/messages/messages.ts` is exhaustive rather than computed.
   */

  /**
   * Mapset the band is standing in, which selects the display width the band is
   * sized to: 78 characters on 19 of the 21 mapsets and 80 on `COCRDSL` and
   * `COCRDUP`. Omit it to render at {@link MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH}.
   */
  readonly mapset?: MapsetName | undefined;
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
 * @param {MapsetName} props.mapset - Mapset the band stands in, selecting the
 *   display width it is sized to; omitted, the band renders at the 78-character
 *   width nineteen of the twenty-one mapsets use.
 * @returns {ReactElement} The band element: reserved space alone when there is
 *   no message, otherwise reserved space containing the alert.
 */
export function MessageBand({
  message,
  severity = DEFAULT_SEVERITY,
  mapset,
}: MessageBandProps): ReactElement {
  /*
   * Alternatives Considered: the `token` member of the same hook, which is the
   * obvious one to reach for. Rejected because it returns RESOLVED values — a hex
   * string, a number — so writing one into a `style` attribute bakes today's
   * palette into the element. Under CSS-variable theming that is a literal in every
   * sense that matters: it opts the element out of the theme silently, and a later
   * token change leaves this one band behind with nothing failing to say so.
   * `cssVar` returns the `var(--…)` reference form of the same names, typed
   * identically.
   *
   * Assumptions: the reference form is safe for the numeric tokens read here. The
   * style layer appends `px` to a numeric token unless it is on its unitless list,
   * so the control height and font size arrive as lengths while the strong font
   * weight arrives as a bare number, which is what the weight property needs.
   * Trade-offs: a value read this way cannot be inspected in a test with no browser
   * to resolve the variable, which is why this component's assertions are the
   * reserved-height and empty-state ones rather than colour equality.
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
   * Alternatives Considered: delegating to the text component's own ellipsis
   * tooltip. Rejected on a measured limitation of the pinned version: it gates its
   * reveal on real truncation, which is the half that matters most, but drives the
   * reveal from its own POINTER state and forwards no trigger list, so a `focus`
   * trigger is silently dropped — confirmed in a browser, where a programmatic
   * focus produced no tooltip while a pointer event on the same element did. A
   * reveal a keyboard cannot summon is a fidelity loss, because the 3270 original
   * was operated entirely from the keyboard.
   * Alternatives Considered: passing `open` through that configuration's tooltip
   * props, which does override its internal state. Rejected because taking over
   * `open` also takes over the truncation gate the configuration was being used
   * for, leaving the measurement below to be written anyway with the reveal now
   * fighting the component for the same state.
   */
  const [messageTextElement, setMessageTextElement] = useState<HTMLSpanElement | null>(null);
  const [isMessageTruncated, setIsMessageTruncated] = useState(false);

  useEffect(
    /**
     * Tracks whether the rendered message is actually clipped.
     *
     * Assumptions: scroll width against client width is the only reliable read of
     * single-line ellipsis state, because the ellipsis is applied by the stylesheet
     * and no event announces it. The observer re-measures on every size change
     * because the band's inline size is capped in CHARACTER units and therefore
     * moves with the viewport, so a measurement taken once at mount would be wrong
     * for the rest of the session; it is created only when the platform provides
     * one, so a non-browser environment falls back to that mount-time read.
     *
     * Assumptions: a zero client width means "not measurable", not truncation. Any
     * scroll width beyond zero exceeds a zero client width, so an element with no
     * laid-out inline size — an undisplayed ancestor, or an intermediate frame
     * mid-viewport-change — would otherwise report every message as clipped and give
     * the band a tab stop and a reveal it does not need. Observed in a browser
     * during an abrupt viewport change, where unclipped messages briefly flipped to
     * clipped and back.
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
   * Trade-offs: the reserved height is FIXED rather than a minimum, so the band's
   * outer height is constant by construction. A minimum would make zero layout
   * shift hold by coincidence — only while the alert's intrinsic height stayed
   * under it — and regress silently on a theme change. The cost is that a taller
   * theme clips a few pixels instead of growing, which is the right way round
   * here: growth moves every screen's content, clipping does not. The value is
   * the design system's large-control token, its nearest expression of "one
   * single-line control", because the fixed 24x80 grid is a documented deviation
   * and leaves no measured BMS height to bridge.
   *
   * Assumptions: `display` is NOT redundant on a `Flex`. antd's Flex stylesheet
   * ships an `.ant-flex:empty{display:none}` rule, and the empty branch below
   * renders a `Flex` with no children, so without this the reserved band collapses
   * to zero height — the exact failure this component exists to prevent. Only real
   * laid-out geometry catches it: the inline `blockSize` a jsdom test can see is
   * correct all along and only the cascade suppresses the box.
   *
   * Assumptions: the `ch` basis is pinned to the design system's body font rather
   * than inherited, because `ch` is the advance measure of the font's own `0` glyph
   * — an inherited face resolves the character cap against whatever ancestor
   * typography happens to apply. Pinning family and size to the tokens the message
   * text renders in makes the cap mean that many characters OF THAT TEXT.
   *
   * Assumptions: the width is resolved per render from the mapset the caller named,
   * through the accessor rather than by indexing the table, so the two permitted
   * figures stay typed as `78 | 80` and an unknown mapset name fails to compile.
   */
  const displayWidth: 78 | 80 =
    mapset === undefined ? MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH : messageBandWidthForMapset(mapset);

  const bandStyle: CSSProperties = {
    display: 'flex',
    inlineSize: '100%',
    maxInlineSize: `${displayWidth}ch`,
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
   * Assumptions: colour and weight are BOTH applied explicitly, overriding antd's
   * defaults, because the source field states both — the row-23 field is
   * `COLOR=RED` with `ATTRB=(ASKIP,BRT,FSET)` on 21 of 21 mapsets, and the alert
   * renders its title in the ordinary colour at the ordinary weight. This object
   * carries those two DESIGN values and nothing else; the overflow, white-space,
   * text-overflow and display declarations belong to the text component's own
   * ellipsis treatment.
   *
   * Trade-offs: an over-long message is truncated VISUALLY and never in the data —
   * the full string stays the element's child, so it is intact in the DOM and
   * announced in full. Alternatives Considered: slicing the value at the
   * 75-character work-area figure. Rejected because every mapset's display field is
   * wider than 75, so that would discard characters the baseline itself renders,
   * and discard them irreversibly rather than merely off-screen. The clipped tail
   * is not visible at a glance, which the reveal below mitigates.
   */
  const messageTextStyle: CSSProperties = {
    color: cssVar[SEVERITY_COLOR_TOKENS[severity]],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };

  /*
   * Refactoring Rationale: the reveal is driven by MEASURED truncation, not by a
   * character count. A count is the wrong instrument because the text region is
   * narrower than the band by the alert's chrome and narrower again for wide
   * glyphs, so any character threshold is absent for exactly the messages that
   * have begun to clip. Measuring the rendered element makes the trigger condition
   * the condition itself rather than a proxy for it.
   *
   * Trade-offs: the reveal is a design-system tooltip triggered by hover AND focus,
   * with a tab stop so focus can reach it. A native `title` needs no tab stop but is
   * offered to a pointer only, and the 3270 original was operated entirely from the
   * keyboard, so a message whose tail only a mouse can read is a fidelity loss.
   * Alternatives Considered: the paragraph component, whose ellipsis configuration
   * also offers an expandable affordance. Rejected on the band's shape — expanding
   * puts the text on further lines, and the fixed reserved height with
   * `overflow: hidden` would clip the expanded tail by the very rule that stops a
   * message moving every screen's content.
   *
   * Assumptions: the trigger list's element type is written out rather than
   * inferred, because the tooltip declares the prop as a MUTABLE array of its own
   * action union — an inferred literal array widens to `string[]` and a read-only
   * tuple is rejected, so naming the members is the only form that compiles without
   * a type assertion.
   */
  const messageRevealTriggers: ('hover' | 'focus')[] = ['hover', 'focus'];

  /*
   * Alternatives Considered: wrapping the text in the tooltip only while it is
   * clipped, rather than keeping the tooltip always in the tree and held closed.
   * Rejected on a real failure mode — adding or removing a parent changes the
   * element tree, so the text element unmounts and remounts, firing the measuring
   * ref with `null` and then a new node; since the measurement is what decides
   * whether to wrap, the two would drive each other. A fixed tree shape makes the
   * reveal a function of the measurement rather than a cause of it.
   *
   * Assumptions: both props are supplied by spreading an object that either holds
   * them or is empty, because `exactOptionalPropertyTypes` is enabled and only
   * ABSENCE leaves the tooltip uncontrolled so its own trigger handling applies.
   *
   * Trade-offs: the design system keeps a single open state for the whole trigger
   * set, so the most recent event decides — sweeping a pointer across an
   * already-focused message and off it closes the reveal even though focus has not
   * moved, and tabbing away and back re-opens it. Accepted because the alternative
   * is to take over `open` and rebuild the component's own focus-and-hover state
   * machine here, and a keyboard-only operator, the audience this reveal exists
   * for, never produces the pointer event that triggers it.
   */
  const messageRevealProps: { open?: false } = isMessageTruncated ? {} : { open: false };

  /*
   * Trade-offs: the tab stop exists only while the message is clipped, so the band
   * adds one exactly when it holds something a pointer-free user cannot otherwise
   * read. The cost is that the tab order changes when the viewport is resized across
   * the width at which a message starts to clip; the alternative, a permanent stop
   * on every band on every screen, pays that cost everywhere for a case that arises
   * on few, and a stop that reveals nothing is a defect rather than a mitigation.
   */
  const messageFocusProps: { tabIndex?: 0 } = isMessageTruncated ? { tabIndex: 0 } : {};

  return (
    <Flex align="center" data-testid={MESSAGE_BAND_TEST_ID} style={bandStyle}>
      {/*
       * Alternatives Considered: `Typography.Text` alone for the whole band, which
       * the fixed-width source field superficially resembles. Rejected because the
       * design-system mapping names `Alert` for the message line and supplies the
       * three severities through its `type`, and because `Text` carries neither a
       * severity treatment nor an ARIA role. `Text` is still used for what it is
       * good at: the styled run of text inside the alert's title slot.
       *
       * Assumptions: the text is passed as `title`, not `message` — antd 6.4 renamed
       * the slot and warns on the older prop, which would emit a console deprecation
       * on every render while resolving to the same slot. `showIcon` is enabled so
       * severity is not carried by colour alone: the icon is additive, the terminal
       * had none, and it is what keeps a red, a green and a turquoise message
       * distinguishable to a reader who cannot separate those hues.
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
