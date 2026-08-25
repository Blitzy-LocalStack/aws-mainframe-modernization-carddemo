/**
 * @file The single message line of the CardDemo SPA.
 *
 * Purpose
 * -------
 * This is the browser target of the 3270 row-23 `ERRMSG` field and, on the two
 * mapsets that declare a second message line, of the row-22 `INFOMSG` field. A
 * screen-level outcome — a rejected sign-on, a completed update, an unsupported
 * function key — arrives here as text, a severity, the mapset it stands in and the
 * CHANNEL it belongs to, and nothing else about the request reaches this module.
 *
 * Refactoring Rationale: a screen renders one band PER CHANNEL, not one band. The
 * earlier contract said one band per screen and gave every instance the same
 * `data-testid`, which held while every delivered screen painted only `ERRMSG`; the
 * account-view mapset declares `INFOMSG` at `POS=(22,23)` as well, so that screen
 * renders two and the shared identifier appeared twice in one document — ambiguous to
 * a query and contradicted by the contract it was meant to state. {@link
 * MessageBandChannel} models the two BMS lines explicitly, so each band carries its
 * own semantic identifier and the one-per-channel invariant is expressible.
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

import { CheckCircleFilled, CloseCircleFilled, InfoCircleFilled } from '@ant-design/icons';
import { Alert, Flex, Tooltip, Typography, theme } from 'antd';

/*
 * WHY : ⚠️ Refactoring Rationale: this module now imports from `ui/src/api`, and the note beside
 *       {@link MessageBandProps} used to state flatly that it never does. The statement is narrowed
 *       rather than withdrawn: what that note refuses is the API's error OBJECT, because accepting one
 *       would invite a caller to pour a field-error array into the single reserved line. This is a
 *       TYPE-ONLY import of one string union, erased entirely at build, so it adds no runtime edge and
 *       no dependency on the client's behaviour - it adds a shared vocabulary.
 * WHY : Alternatives Considered: re-declaring the four members here as a band-local union, which keeps
 *       the import out. Rejected because the two unions would then be independent declarations of one
 *       contract with nothing to fail when the service adds a fifth member; a reader would find two
 *       lists and no way to tell which is authoritative. Importing the type makes drift a compile error
 *       in {@link API_SEVERITY_BAND_SEVERITIES}, whose `satisfies Record<Severity, …>` clause fails the
 *       moment the source union gains a member this band has no mapping for.
 */
import type { Severity } from '../api/types';
import {
  MESSAGE_BAND,
  isMessageBandEmpty,
  messageBandWidthForMapset,
  normaliseMessageBandValue,
} from '../messages/messages';
import type { MapsetName } from '../messages/messages';
import type { AntdTokenName } from '../theme/tokens';
import { BMS_TEXT_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';

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
 *
 * Refactoring Rationale: `"neutral"` IS added, and it is a translation rather than an
 * invention. The row-22 `INFOMSG` field of `app/bms/COACTVW.bms` is declared
 * `ATTRB=(PROT) COLOR=NEUTRAL`, and `ui/src/theme/tokens.ts` resolves NEUTRAL to
 * `colorTextSecondary` while TURQUOISE resolves to `colorInfo` — two measured colours
 * that the bridge deliberately keeps apart. Rendering that field as `"info"` painted
 * a de-emphasised line in the informational hue, which is the one substitution the
 * token bridge's G3 note exists to prevent.
 */

/**
 * The four severities the message band can render.
 *
 * Each value maps onto a measured BMS colour and onto an ARIA role, and onto an antd
 * `Alert` type through {@link SEVERITY_ALERT_TYPES} — so a severity is simultaneously
 * the accessibility signal, the colour decision and the alert variant.
 */
export type MessageBandSeverity = 'error' | 'success' | 'info' | 'neutral';

/*
 * Assumptions: the two channels are the two message lines a BMS mapset can declare,
 * and they are modelled as a closed union rather than as a free-form identifier. A
 * screen cannot invent a third: `ERRMSGI`/`ERRMSGO` and `INFOMSGI`/`INFOMSGO` are the
 * only message fields in the 21 symbolic maps, so an open string would admit names no
 * mapset declares and would put the test-handle vocabulary outside this module.
 */

/**
 * The BMS message line a band stands in for.
 *
 * `"error"` is the row-23 `ERRMSG` field every mapset declares; `"information"` is the
 * row-22 `INFOMSG` field the account-view and account-update mapsets add above it.
 */
export type MessageBandChannel = 'error' | 'information';

/*
 * Assumptions: the empty band is deliberately contentless, exposing no text, role
 * or accessible name a query could find, so a stable data attribute is the only way
 * to assert the invariant this component exists to guarantee — that the band
 * occupies the same space whether or not a message is present. It is exported
 * rather than inlined so a drifting copy in a test file cannot fail as a "missing
 * element" rather than as the contract change it actually is.
 */

/**
 * Stable `data-testid` per channel, present in both the empty and populated states so
 * the reserved-space contract can be asserted for either band.
 *
 * Assumptions: the error channel keeps the historical value unchanged. Four existing
 * suites query it, and renaming it would turn a contract EXTENSION into a breaking
 * change for every screen that paints one band — which is all of them but account
 * view.
 */
export const MESSAGE_BAND_TEST_IDS = {
  error: 'message-band',
  information: 'message-band-information',
} as const satisfies Record<MessageBandChannel, string>;

/**
 * Stable `data-testid` of the row-23 error band, which is the band a screen renders
 * when it declares no channel.
 */
export const MESSAGE_BAND_TEST_ID = MESSAGE_BAND_TEST_IDS.error;

/*
 * Assumptions: the error channel is the default because 21 of 21 mapsets declare
 * `ERRMSG` and two declare `INFOMSG`, so the band a caller means when it names no
 * channel is the one every mapset has.
 */

/** Channel a band renders when the caller names none. */
const DEFAULT_CHANNEL: MessageBandChannel = 'error';

/**
 * Stable `data-testid` on a band standing in for the mapsets' INFORMATION line.
 *
 * Purpose: five mapsets declare TWO message fields, not one — `app/bms/COACTUP.bms` puts `INFOMSG`
 * at row 22 inside the screen's own field area and `ERRMSG` at row 23 beneath it — so a screen with
 * both channels renders this band itself and delegates only the row-23 line to the shell. Both bands
 * are the same component and must be distinguishable: while they shared one identifier, every screen
 * carrying two channels published TWO elements under {@link MESSAGE_BAND_TEST_ID}, which made the
 * shell's single-band contract unassertable and made a query for it match two nodes.
 *
 * Assumptions: the row-23 line keeps the original identifier because it is the one every mapset has
 * and the one the shell owns; the row-22 line is the addition and takes the new name.
 */
/*
 * WHY : Refactoring Rationale: this is an ALIAS into MESSAGE_BAND_TEST_IDS rather than its own literal,
 *       which it used to be. Two constants named the information band -- this one as 'information-band'
 *       and that map's `information` entry -- so a case querying one could not find a band rendered
 *       under the other, which is exactly the mismatch that made a two-band screen untestable. One
 *       literal per band is what keeps every query and every render naming the same element.
 */
export const INFORMATION_BAND_TEST_ID = MESSAGE_BAND_TEST_IDS.information;

/**
 * Which of the mapsets' two message lines a band is standing in for.
 *
 * Assumptions: the vocabulary is the source's, not the target's — `message` is row 23's `ERRMSG` and
 * `information` is row 22's `INFOMSG` — so a reader comparing a screen against its mapset does not
 * have to translate. A boolean would have named neither.
 */
export type MessageBandLine = 'information' | 'message';

/*
 * WHY : ⚠️ Assumptions: there is no `DEFAULT_LINE` constant, and the default it expressed is the SAME
 *       default {@link DEFAULT_CHANNEL} expresses -- the row-23 line every one of the 21 mapsets declares.
 *       Two names for one message line were authored independently ({@link MessageBandLine}'s `'message'`
 *       and {@link MessageBandChannel}'s `'error'`), and both are accepted so neither caller vocabulary
 *       breaks; but only ONE of them can carry the default, or a caller that named nothing would resolve
 *       through whichever constant the code happened to read. `DEFAULT_CHANNEL` carries it, and `line`
 *       resolves through it when it is absent -- which is exactly what the resolution below does.
 */

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
  /*
   * Assumptions: neutral is polite for the same reason success and info are. The
   * row-22 field it renders carries guidance the operator has not asked to be
   * interrupted by — `Enter or update id of account to display` is its value on every
   * turn of the account-view screen — so an assertive region would interrupt a screen
   * reader with a prompt on every read.
   */
  neutral: 'status',
} as const satisfies Record<MessageBandSeverity, 'alert' | 'status'>;

/*
 * Refactoring Rationale: the alert VARIANT is a separate table from the severity,
 * which it did not need to be while every severity happened to be a valid antd `Alert`
 * type. `"neutral"` is not one of the four the component accepts, so mapping it here is
 * what lets the measured BMS colour and the component's own chrome differ: the text
 * takes `colorTextSecondary` from the token bridge below, while the icon and border
 * take the informational variant, which is the closest chrome the design system offers
 * for a non-error advisory line.
 * Alternatives Considered: rendering a neutral band with no `Alert` at all, as a plain
 * styled run of text. Rejected because the band would then lose the ARIA role and the
 * reserved geometry the alert brings, so the two channels would announce differently
 * and be laid out differently for a difference that is only a colour.
 */

/** antd `Alert` variant each severity renders with. */
const SEVERITY_ALERT_TYPES = {
  error: 'error',
  success: 'success',
  info: 'info',
  neutral: 'info',
} as const satisfies Record<MessageBandSeverity, 'error' | 'success' | 'info'>;

/*
 * ⚠️ Refactoring Rationale: the severity icon is supplied HERE rather than left to `showIcon`, and
 * the only difference in the rendered glyph is that this one is hidden from assistive technology. An
 * accessibility pass found the component's own icon exposed under its machine name — announced as
 * `image "close-circle"` on an error band and `image "info-circle"` on an informational one, and
 * announced INSIDE the live region and BEFORE the sentence, so a screen-reader user heard the name of
 * a drawing file before hearing what had happened. The icon is decorative by construction: severity
 * already reaches assistive technology through the alert's role, which is `alert` for a rejection and
 * `status` for everything else, so the glyph carries nothing for a listener and nothing is lost by
 * hiding it.
 *
 * Alternatives Considered: dropping `showIcon`, which removes the announcement by removing the icon.
 * Rejected because the icon is the second visual channel severity travels on: the sentence itself is
 * painted in the base text grade for contrast reasons recorded at {@link SEVERITY_COLOR_TOKENS}, so
 * without the glyph a reader who cannot separate the alert tints has only the tint to go on.
 *
 * Alternatives Considered: hiding the component's own icon instead of replacing it. Rejected because
 * there is no seam to do it through - antd renders the icon internally when `showIcon` is set and
 * exposes no props for it, so `aria-hidden` cannot be reached onto that element from here at all.
 *
 * Assumptions: the four glyphs are the FILLED variants, which is what the component itself renders
 * for an alert with no description, so replacing the node changes no pixel. The mapping follows
 * {@link SEVERITY_ALERT_TYPES} exactly - `neutral` takes the informational glyph because it takes the
 * informational variant - so the icon and the chrome cannot disagree about which severity is showing.
 */
const SEVERITY_ALERT_ICONS = {
  error: <CloseCircleFilled aria-hidden="true" />,
  success: <CheckCircleFilled aria-hidden="true" />,
  info: <InfoCircleFilled aria-hidden="true" />,
  neutral: <InfoCircleFilled aria-hidden="true" />,
} as const satisfies Record<MessageBandSeverity, ReactElement>;

/*
 * Assumptions: these three token names come from the measured BMS colour
 * bridge and not from this file. The mapping from BMS colour to design-system
 * token stays in `ui/src/theme/tokens.ts`, where its rationale and measured
 * frequencies live. Naming the token and resolving its value from the live theme
 * is what keeps this component free of colour literals: under CSS-variable
 * theming a literal would not merely duplicate a token, it would opt this
 * element out of the theme silently.
 *
 * Refactoring Rationale: all three severities now resolve to the TEXT-GRADE
 * token of the base text role, where each used to resolve to the semantic anchor
 * of its own hue - RED to the error colour, GREEN to the success colour and
 * TURQUOISE to the informational one. The three anchors are mid-ramp fill
 * colours, and this band paints its sentence on the alert's OWN tinted
 * background, against which they measure 2.99:1, 2.07:1 and 2.02:1 where WCAG AA
 * asks 4.5:1 for normal text. Nor is the fix a darker shade of each ramp: the
 * darkest shade any of the three publishes measures 4.22:1, 3.17:1 and 3.25:1
 * against those same tints, so no in-family value clears the threshold at this
 * version. The base text role measures 15.36:1 against the error tint and better
 * against the other two.
 *
 * Assumptions: severity is NOT carried by the sentence's colour and does not need
 * to be, which is what makes the collapse admissible rather than a loss. The
 * alert's `type` already paints a per-severity background and border, `showIcon`
 * renders a per-severity icon, and `SEVERITY_ALERT_ROLES` announces error as an
 * assertive live region and the other two politely - three independent channels,
 * two of them non-visual. This is also the design system's own treatment: its
 * alert renders its message in the base text colour and carries severity in the
 * chrome around it. The map is kept keyed by severity rather than reduced to one
 * constant so that a future version publishing an AA-capable shade per ramp can
 * be adopted by editing three token names here.
 */
const SEVERITY_COLOR_TOKENS = {
  /*
   * WHY : Refactoring Rationale: every severity resolves through BMS_TEXT_COLOR_TOKENS rather than
   *       BMS_COLOR_TOKENS, and the difference is a measured contrast one rather than a preference.
   *       This band is a run of PROSE, and five of the eight semantic ramps publish no shade that
   *       reaches the 4.5:1 AA threshold at normal text size -- the mid-ramp anchor these severities
   *       would otherwise take measured 2.21:1 for the informational role. The text grade of the same
   *       ramp is what BMS_TEXT_CONTRAST_AUDIT records each role as having been snapped to.
   * WHY : Assumptions: `neutral` is present, and its absence was a type error rather than a style one:
   *       MessageBandSeverity admits four values and this map is declared to cover the Record, so a
   *       missing key fails the type check. It resolves at the text grade like the other three, for the
   *       same reason.
   */
  error: BMS_TEXT_COLOR_TOKENS.RED,
  success: BMS_TEXT_COLOR_TOKENS.GREEN,
  info: BMS_TEXT_COLOR_TOKENS.TURQUOISE,
  neutral: BMS_TEXT_COLOR_TOKENS.NEUTRAL,
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

/**
 * What one of the two message lines is, stated in the reference's own terms.
 *
 * Assumptions: three structural members and no prose. The row and the field name are what a reader
 * checks a screen against in `app/bms/**`, and the default severity is what the field's own `COLOR=`
 * operand declares — so every member is a measurement rather than a description, and the description
 * belongs in the documentation on {@link MESSAGE_BAND_CHANNELS} where it cannot be mistaken for
 * displayable content.
 */
export interface MessageBandChannelContract {
  /** Terminal row the field occupies: 22 for the advisory line, 23 for the outcome line. */
  readonly row: 22 | 23;
  /** Name of the BMS field this channel stands in for, as the mapsets declare it. */
  readonly sourceField: 'INFOMSG' | 'ERRMSG';
  /** Severity the channel renders with when the caller names none, from the field's `COLOR=`. */
  readonly defaultSeverity: MessageBandSeverity;
}

/**
 * The two message channels this application has, and what belongs on each.
 *
 * Purpose: publish ONE authority for a routing decision every screen makes and several got wrong. A
 * rendering review found advisory text painted into the row-23 outcome band on 4 of 17 routes, and
 * found two screens rendering their advisory line as a second alert inside `<main>` roughly 200 pixels
 * above a shell band that stood empty at its reserved height — two message zones where the terminal
 * showed two adjacent rows. The rule is short enough to state exhaustively, so it is stated here
 * rather than re-derived per screen:
 *
 * - `information` is row 22's `INFOMSG`. It carries STANDING GUIDANCE — what the operator may do on
 *   this screen, which is true before the turn and still true after it. `Enter or update id of account
 *   to display` is its value on every turn of the account-view screen. Five mapsets declare it.
 * - `error` is row 23's `ERRMSG`. It carries the OUTCOME OF THE TURN JUST TAKEN — a rejection, a
 *   confirmation, an unsupported key, the sign-off acknowledgement. All 21 mapsets declare it, which is
 *   why it is what a caller naming no channel gets.
 *
 * Assumptions: the test is TENSE, not tone. A sentence that would still be true if the operator had
 * pressed nothing belongs on row 22 however urgent it sounds; a sentence that answers what just
 * happened belongs on row 23 however mild it is. Deciding by tone is what produced the measured
 * mis-routings, because an advisory prompt reads mild and lands in the channel whose colour looks
 * mild — which is the informational-looking alert this review found in the ERROR band.
 *
 * Assumptions: the two channels are never collapsed and never substituted for one another. A screen
 * whose mapset declares both renders both, because collapsing them drops whichever sentence the other
 * overwrote — the guidance and the outcome are simultaneously true on every turn of the five two-line
 * mapsets.
 */
export const MESSAGE_BAND_CHANNELS = {
  information: { row: 22, sourceField: 'INFOMSG', defaultSeverity: 'neutral' },
  error: { row: 23, sourceField: 'ERRMSG', defaultSeverity: DEFAULT_SEVERITY },
} as const satisfies Record<MessageBandChannel, MessageBandChannelContract>;

/**
 * Reports the severity a channel renders with when its caller names none.
 *
 * Purpose: keep an advisory line out of the error appearance by default. The band's single default was
 * `error` for both channels — the measured `COLOR=RED` of row 23 — so a screen that delegated its
 * row-22 line without restating a severity painted standing guidance in the rejection colour, with
 * the assertive live-region role that goes with it. Row 22 is `COLOR=NEUTRAL` on every mapset that
 * declares it, so the correct default differs per channel and can be read from the channel.
 *
 * Assumptions: the answer comes from {@link MESSAGE_BAND_CHANNELS} rather than from a second table
 * here, so the value a reader finds documented is the value the component renders.
 * @param {MessageBandChannel} channel - The message line the band is standing in for.
 * @returns {MessageBandSeverity} The severity that channel's BMS field declares.
 */
export function defaultMessageBandSeverity(channel: MessageBandChannel): MessageBandSeverity {
  return MESSAGE_BAND_CHANNELS[channel].defaultSeverity;
}

/**
 * Translates the legacy `line` vocabulary onto the channel vocabulary.
 *
 * Purpose: one concept reached this component under two names — `line`, which distinguishes the
 * reference's row-22 information line from its row-23 message line, and `channel`, which distinguishes
 * the same two bands by what each carries. Both have call sites, so both are accepted; this is the one
 * place the older name is mapped onto the newer, so a caller holding either vocabulary resolves to the
 * same band.
 * @param {MessageBandLine | undefined} line - The line a caller named, or `undefined` for none.
 * @returns {MessageBandChannel} The channel that line denotes, defaulting to the row-23 outcome line.
 */
export function messageBandChannelForLine(line: MessageBandLine | undefined): MessageBandChannel {
  if (line === undefined) {
    return DEFAULT_CHANNEL;
  }

  return line === 'information' ? 'information' : 'error';
}

/*
 * Refactoring Rationale: the mapping is a table rather than a `switch`, for the same reason the three
 * tables above it are: `satisfies Record<Severity, MessageBandSeverity>` makes an unmapped member of
 * the source union a compile error, where a `switch` with a `default` arm would silently absorb a
 * fifth severity into whichever appearance the fallback named.
 *
 * ⚠️ Trade-offs: WARNING and CRITICAL both resolve to `error`, so the band renders them identically -
 * which is the very observation that prompted this helper. A rendering review reported the two as
 * byte-identical and called the severity inert. The collapse is nonetheless the faithful answer and
 * not a shortcut: the row-23 field is `COLOR=RED` on 21 of 21 mapsets unconditionally, so the source
 * has ONE appearance for every unsuccessful turn, and the band's severity union deliberately omits a
 * warning member for that reason - a fourth appearance would be an invention rather than a
 * translation. What the helper fixes is the other half of the same finding: LOG and INFO no longer
 * render as rejections, so the severity is no longer inert across the range.
 *
 * Assumptions: the WARNING-versus-CRITICAL distinction is preserved where it is actionable rather than
 * discarded - it stays on the problem document, whose `status`, `code` and `abend` members are what a
 * screen reasons about when deciding whether an operation may be retried. It was never a colour.
 */
const API_SEVERITY_BAND_SEVERITIES = {
  /*
   * Assumptions: LOG is the quietest tier the services publish and maps onto the quietest appearance
   * the band has. Its documented use is a condition worth recording rather than acting on, which is
   * the row-22 advisory tone - de-emphasised text announced politely - not a red interruption.
   */
  LOG: 'neutral',
  INFO: 'info',
  WARNING: 'error',
  CRITICAL: 'error',
} as const satisfies Record<Severity, MessageBandSeverity>;

/**
 * Translates a service severity into the appearance the band renders it with.
 *
 * Purpose: the two vocabularies are different by design and nothing joined them, so every screen
 * holding a problem document had to invent the correspondence or ignore it — and a rendering review
 * found that they ignored it, rendering every fault in the default rejection appearance whatever the
 * document said. This is the single documented correspondence for the whole application: the API
 * publishes `LOG | INFO | WARNING | CRITICAL`, the band renders `error | success | info | neutral`, and
 * the mapping between them belongs beside the appearances rather than beside the transport.
 *
 * Assumptions: `success` is unreachable from this helper, and that is correct rather than an omission.
 * A problem document describes a failure, so no severity it can carry means "the turn succeeded"; a
 * screen names `success` itself when a write completes.
 * @param {Severity} severity - The `severity` member of the problem document the request produced.
 * @returns {MessageBandSeverity} The band severity to render that document's message with.
 */
export function messageBandSeverityForApiSeverity(severity: Severity): MessageBandSeverity {
  return API_SEVERITY_BAND_SEVERITIES[severity];
}

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
   * one reserved line. Callers map an error to text; this module imports nothing from
   * `ui/src/api` at RUNTIME, and the one type-only exception is argued at the import
   * itself, where {@link messageBandSeverityForApiSeverity} needs the service severity
   * union to be mappable rather than re-declared.
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
   * Severity governing the alert variant, the message colour, the severity icon and the ARIA role.
   *
   * Omit it to take the appearance the resolved channel's own BMS field declares — `error` for row 23,
   * which is `COLOR=RED` on 21 of 21 mapsets, and `neutral` for row 22, which is `COLOR=NEUTRAL` on
   * every mapset that declares it. {@link defaultMessageBandSeverity} is that resolution, and
   * {@link messageBandSeverityForApiSeverity} is how a screen holding a problem document arrives at a
   * value to pass here.
   */
  readonly severity?: MessageBandSeverity | undefined;

  /**
   * BMS message line this band stands in for, which selects its `data-testid`.
   * Defaults to `"error"`, the row-23 field every mapset declares.
   *
   * Assumptions: the channel is independent of the severity, and the two are not
   * collapsed into one prop. The row-23 field carries a rejection on one turn and the
   * sign-off acknowledgement on another, so its severity varies while its channel does
   * not; conversely the row-22 field is `COLOR=NEUTRAL` on every turn.
   *
   * Assumptions: this prop OUTRANKS {@link MessageBandProps.line} when both are given, because it is
   * the current name for the concept and a caller stating it is stating what it means.
   * {@link MESSAGE_BAND_CHANNELS} records what belongs on each channel.
   */
  readonly channel?: MessageBandChannel | undefined;

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

  /**
   * Which of the mapsets' two message lines this band stands in for, which selects the
   * `data-testid` it carries. Defaults to `"message"`, the row-23 line every mapset has and the
   * one the shell owns; pass `"information"` for the row-22 line a screen renders itself.
   */
  readonly line?: MessageBandLine | undefined;
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
 * @param {MessageBandSeverity | undefined} props.severity - Severity to render
 *   with; optional, and when omitted the band takes the severity its resolved channel's own BMS field
 *   declares — `"error"` for the row-23 line and `"neutral"` for the row-22 line.
 * @param {MapsetName | undefined} props.mapset - Mapset the band stands in,
 *   selecting the display width it is sized to; optional, and when omitted the
 *   band renders at the 78-character width nineteen of the twenty-one mapsets
 *   use.
 * @param {MessageBandChannel | undefined} props.channel - BMS message line this
 *   band stands in for, selecting its `data-testid` and its default severity; optional, and it
 *   outranks `props.line` when both are given.
 * @param {MessageBandLine | undefined} props.line - The same choice of message line in the older
 *   `information`/`message` vocabulary; optional, consulted only when `props.channel` is absent, and
 *   resolved through {@link messageBandChannelForLine}.
 * @returns {ReactElement} The band element: reserved space alone when there is
 *   no message, otherwise reserved space containing the alert.
 */
export function MessageBand({
  message,
  severity,
  mapset,
  /*
   * WHY : Refactoring Rationale: BOTH `line` and `channel` are accepted, and `channel` is the one the
   *       component reasons in. They are two names for one concept authored independently -- `line`
   *       distinguishing the reference's row-22 information line from its row-23 message line, and
   *       `channel` distinguishing the same two bands by what each carries -- and both have call sites
   *       and both have tests. Resolving to one name only would have silently dropped whichever set of
   *       call sites lost, so `line` is retained as an alias and mapped onto `channel` below; the
   *       test-identifier map is keyed by channel alone, so there is still ONE identifier per band.
   */
  line,
  channel,
}: MessageBandProps): ReactElement {
  /*
   * ⚠️ Refactoring Rationale: NEITHER prop is defaulted in the destructuring any more, and both
   * defaults are resolved below instead. The reason is that a default assigned there is
   * indistinguishable from a value the caller passed, which made two decisions unstateable.
   *
   * The first is precedence. `channel` used to carry `DEFAULT_CHANNEL` at the parameter, so by the time
   * the resolution ran, "the caller named the outcome channel" and "the caller named nothing" were the
   * same value - and the note that stood here claimed an explicit `line` wins "over the default, and
   * only over the default", which the code could not honour and did not: `line` won over an explicit
   * `channel` too. Taking both raw makes the documented rule enforceable, and `??` states it: an
   * explicit `channel` is taken at its word, `line` answers only when `channel` is absent.
   *
   * The second is the severity default, which is now CHANNEL-DEPENDENT for the reason recorded at
   * {@link defaultMessageBandSeverity} - row 23 is `COLOR=RED` and row 22 is `COLOR=NEUTRAL`, so one
   * default cannot serve both. That resolution needs the channel, so it cannot happen at the parameter
   * list at all.
   */
  const resolvedChannel: MessageBandChannel = channel ?? messageBandChannelForLine(line);

  const resolvedSeverity: MessageBandSeverity =
    severity ?? defaultMessageBandSeverity(resolvedChannel);
  /*
   * Assumptions: the identifier is resolved once here and used by BOTH returns below, because the
   * reserved-space contract this component exists to guarantee is that the SAME element is present
   * whether or not a message is present -- and it stops being the same element if the empty and
   * populated branches can disagree about what identifies it.
   */
  // The band identifier is MESSAGE_BAND_TEST_IDS[resolvedChannel]; see the resolution above.
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
   * Trade-offs: what a test can assert about a value obtained this way is the
   * REFERENCE, not the colour. A `var(--…)` declaration survives into the element's
   * inline style verbatim, so a case can prove the band paints the token the BMS bridge
   * names for a severity; nothing without a layout engine can resolve that reference to
   * a hue, so no case can prove the resulting colour. That split is the reason the
   * severity assertions in `ui/src/layout/MessageBand.test.tsx` compare the declaration
   * against the same bridge entry this file reads rather than against a literal.
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
   * tooltip. Rejected on a limitation of the pinned version: it gates its reveal on
   * real truncation, which is the half that matters most, but drives the reveal from
   * its own POINTER state and forwards no trigger list, so a `focus` trigger is
   * silently dropped. A reveal a keyboard cannot summon is a fidelity loss, because
   * the 3270 original was operated entirely from the keyboard. Passing `open` through
   * that configuration's tooltip props does override its internal state and is
   * rejected too, because taking over `open` also takes over the truncation gate the
   * configuration was being used for, leaving the measurement below to be written
   * anyway with the reveal fighting the component for the same state.
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
       * @returns {void} Completion leaves the observer disconnected.
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
        data-testid={MESSAGE_BAND_TEST_IDS[resolvedChannel]}
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
    color: cssVar[SEVERITY_COLOR_TOKENS[resolvedSeverity]],
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
   * ⚠️ Refactoring Rationale: the design-system tooltip now answers FOCUS ONLY, and the pointer is
   * answered by the native `title` below. It answered both, and the note here argued that a native
   * `title` is "offered to a pointer only" and therefore a fidelity loss for a keyboard-operated
   * terminal. That argument is sound and is why the tooltip is kept for focus; what it does not
   * establish is that the tooltip should also own hover. A rendering review measured an over-length
   * sentence clipped at `scrollWidth 747 > clientWidth 566` and reported the tail as unrecoverable,
   * having inspected the element and found no `title` — a reveal that exists only while a script has
   * mounted, measured and re-rendered is invisible to inspection and absent whenever any of those
   * three has not happened yet.
   *
   * Alternatives Considered: adding `title` and leaving the tooltip on hover as well, which is the
   * smaller edit. Rejected because the browser renders a native tooltip from `title` on the same hover
   * that opens the design-system one, so the same sentence would appear twice, in two boxes, offset
   * from each other. Splitting the triggers gives one reveal per input device.
   *
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
  const messageRevealTriggers: 'focus'[] = ['focus'];

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

  /*
   * Refactoring Rationale: the clipped sentence is ALSO carried in a native `title`, which is what a
   * pointer reveals and what an inspection of the element finds. The full string has always been the
   * element's own text — nothing is sliced, so a screen reader announces the whole sentence and always
   * did — but a sighted pointer user had only a scripted tooltip, and an accessibility review that read
   * the element reported no recoverable tail at all because there was no attribute to read.
   *
   * Assumptions: the attribute is set only while the text is MEASURED as clipped, not whenever a
   * message is present. A `title` duplicating a fully visible sentence is noise that appears on every
   * hover over every band on every screen, and browsers give it the same dwell delay whether it says
   * anything new or not.
   *
   * Alternatives Considered: `aria-label` carrying the same string, which is the other attribute the
   * review named. Rejected as an ARIA violation rather than a preference: the element is a `span` with
   * no role, so it maps to `generic`, and `aria-label` is prohibited on a generic element - axe reports
   * it under `aria-prohibited-attr` and several screen readers ignore it outright. It would also be
   * redundant even where permitted, because the accessible name it would supply is the text it already
   * has.
   */
  const messageTitleProps: { title?: string } = isMessageTruncated ? { title: text } : {};

  return (
    <Flex align="center" data-testid={MESSAGE_BAND_TEST_IDS[resolvedChannel]} style={bandStyle}>
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
       * distinguishable to a reader who cannot separate those hues. The `icon` is
       * supplied rather than left to the component so it can be hidden from assistive
       * technology, for the reason recorded at {@link SEVERITY_ALERT_ICONS}.
       */}
      <Alert
        icon={SEVERITY_ALERT_ICONS[resolvedSeverity]}
        role={SEVERITY_ALERT_ROLES[resolvedSeverity]}
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
              {...messageTitleProps}
            >
              {text}
            </Typography.Text>
          </Tooltip>
        }
        type={SEVERITY_ALERT_TYPES[resolvedSeverity]}
      />
    </Flex>
  );
}
