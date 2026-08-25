/**
 * @file Programmatic association between a control and the text that describes it.
 *
 * Purpose
 * -------
 * Every migrated screen renders its per-field refusals through
 * `Form.Item validateStatus="error"` with `help`, which is the browser target of the baseline's
 * templated field-error contract at `app/cpy/CSSETATY.cpy` L17-L27. That renders the text in the
 * right place and in the right colour, and — on the code path these screens use — it associates the
 * text with nothing.
 *
 * The reason is specific to antd and worth stating once rather than rediscovering per screen. antd
 * injects `aria-invalid` and `aria-describedby` onto a control from `Form.Item` only when the item is
 * NAMED, because the identifier it points at is derived from the field's name path. Every screen in
 * this application holds its own values in component state and passes `value`/`onChange` to the
 * control directly, so its items are unnamed by construction — the form is a layout device, not the
 * value store. The consequence is that a sighted operator sees red text under the control while a
 * screen-reader user is told neither that the control is invalid nor what is wrong with it.
 *
 * This module supplies the missing half: a stable identifier per described element, derived from the
 * control's own identifier, and the two ARIA members that point a control at them. It holds no
 * user-visible string and no design value — the text belongs to `ui/src/messages/messages.ts` or to
 * the problem document that carried it, and the colour belongs to `ui/src/theme/tokens.ts` by way of
 * antd's own explain container.
 *
 * Refactoring Rationale: the module's remit is now every fact ONE CONTROL reports about itself, not
 * only the description — the refused-value rendering the baseline's templated highlight declares, and
 * the busy and unavailable states a browser has and a 3270 terminal did not. They are together because
 * they share one mechanism: each is an ARIA member or a small element that has to agree with what the
 * screen rendered, and a review found each of them implemented on some screens, absent from others and
 * spelled two ways where it was present. One module is what makes a screen adopting the third of them
 * inherit the reasoning behind the first two.
 *
 * Alternatives Considered: adopting antd's named-field wiring instead, letting `Form` hold the
 * values and getting the ARIA members for free. Rejected for the migration as a whole rather than for
 * one screen: the values these screens hold are not only form values. They are compared against a
 * before-image (`ACUP-OLD-DETAILS` at `app/cbl/COACTUPC.cbl` L669), seeded from a response, cleared
 * on settlement, and read by key handlers that run outside any submit — and a `Form`-owned store
 * would have to be pushed and pulled at each of those points through an imperative instance, which is
 * strictly more machinery than passing `value` down. The narrow fix restores the accessibility
 * property without moving the state.
 *
 * Alternatives Considered: setting `aria-invalid` on the control and stopping there, which is what
 * one screen did before this module existed. It is insufficient on its own: it announces THAT the
 * value is wrong and never WHY, so the operator hears an error state whose remedy is on screen but
 * unreachable. Both members are needed, which is why they are produced together by one helper rather
 * than left to be remembered separately.
 */

import type { CSSProperties, ReactElement } from 'react';

import { Typography } from 'antd';
import type { GlobalToken } from 'antd';

/*
 * WHY : Assumptions: the service's two field-failure states are IMPORTED rather than re-declared, and
 *       the import is type-only so nothing from the client reaches this module at runtime. The union is
 *       the reference's own distinction - `app/cpy/CSSETATY.cpy` treats a not-OK value and a blank one
 *       differently - and it is already declared once in `ui/src/api/types.ts` beside the citation for
 *       why it has exactly two members. A local copy would be a second authority on a two-member union
 *       with nothing to fail if the contract gained a third.
 */
import type { FieldValidationState } from '../api/types';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';

/**
 * Suffix of the element carrying a control's refusal text.
 *
 * Assumptions: derived from the control's own identifier rather than generated, so the identifier is
 * stable across renders without a counter or a `useId` call. A generated value would change whenever
 * the tree remounted, which is exactly when an assertion on the association is most likely to run.
 */
const ERROR_ID_SUFFIX = '-error';

/** Suffix of the element carrying a control's static hint. */
const HINT_ID_SUFFIX = '-hint';

/**
 * The ARIA members that link a control to the text describing it.
 *
 * Assumptions: both members are optional and are OMITTED rather than emitted empty when they do not
 * apply. `aria-invalid` defaults to false in the absence of the attribute, and an
 * `aria-describedby` pointing at an element that is not rendered is a dangling reference that some
 * assistive technologies announce as nothing and others skip entirely — so the attribute is present
 * only while its target is.
 */
export interface FieldAriaProps {
  /** Present and true only while the control holds a refused value. */
  readonly 'aria-invalid'?: true;
  /** Identifiers of the rendered elements describing the control, in reading order. */
  readonly 'aria-describedby'?: string;
}

/**
 * Builds the identifier of the element carrying a control's refusal text.
 * @param {string} controlId - The `id` of the control the text describes.
 * @returns {string} The identifier to place on the element holding the refusal text.
 */
export function fieldErrorId(controlId: string): string {
  return `${controlId}${ERROR_ID_SUFFIX}`;
}

/**
 * Builds the identifier of the element carrying a control's static hint.
 * @param {string} controlId - The `id` of the control the hint describes.
 * @returns {string} The identifier to place on the element holding the hint text.
 */
export function fieldHintId(controlId: string): string {
  return `${controlId}${HINT_ID_SUFFIX}`;
}

/**
 * What is currently true of one control, as the screen rendering it knows.
 *
 * Assumptions: the three members are stated SEPARATELY rather than derived from one another, because
 * they genuinely come apart. A screen may refuse a value without printing a sentence under the control
 * — the account-view screen does exactly that for a locally rejected filter, on the ground that the
 * message band already carries the sentence and printing it twice would have an operator read one
 * refusal in two places — so `invalid` can be true while `hasError` is false. Deriving one from the
 * other would force such a screen either to print the sentence it deliberately withholds or to lose
 * the invalid state, and a helper that quietly makes that choice for its callers is worse than one
 * that asks.
 */
export interface FieldDescription {
  /** Whether the control currently holds a value the screen or the service refused. */
  readonly invalid: boolean;
  /** Whether an element carrying {@link fieldErrorId} is rendered for this control. */
  readonly hasError: boolean;
  /** Whether an element carrying the hint identifier is rendered for this control. */
  readonly hasHint: boolean;
  /**
   * Identifier of the hint element, when it is NOT the one {@link fieldHintId} derives.
   *
   * Assumptions: supplied where ONE hint element describes several controls, which the
   * account-update screen needs: the national identifier is three boxes under one caption, so the
   * caption cannot carry a per-control identifier and each part references the shared one instead.
   * Where a control has its own hint the member is omitted and the derived identifier is used, so the
   * common case states nothing.
   */
  readonly hintId?: string | undefined;
}

/**
 * Builds the ARIA members pointing a control at the text that describes it.
 *
 * Assumptions: the refusal is listed BEFORE the hint when both are present, because that is the order
 * antd renders them in — `Form.Item` emits its explain container, which holds `help`, above its extra
 * container, which holds `extra` — so an operator hearing the description hears it in the order a
 * sighted operator reads it.
 *
 * Assumptions: an identifier is listed only when the element carrying it is RENDERED. An
 * `aria-describedby` naming an absent element is a dangling reference that some assistive technologies
 * announce as nothing and others skip, which is why the caller states what it rendered rather than
 * this helper assuming.
 *
 * Trade-offs: the hint is described rather than labelled. Folding it into the control's accessible
 * name was considered and rejected: the name is the transcribed field label, and appending a width
 * hint to it would make every announcement of the control repeat the hint, including in a list of
 * form fields where only names are read.
 *
 * Assumptions: the hint identifier is DERIVED from the control unless the caller supplies one, which
 * is what lets a single caption describe a split group. A helper that always derived it would force a
 * three-box group either to render three copies of one caption or to leave two of its boxes
 * undescribed.
 * @param {string} controlId - The `id` of the control being described.
 * @param {FieldDescription} description - What is currently true of the control.
 * @returns {FieldAriaProps} The members to spread onto the control; empty when nothing describes it
 *   and it holds no refusal.
 */
export function fieldAriaProps(controlId: string, description: FieldDescription): FieldAriaProps {
  const described: string[] = [];
  if (description.hasError) {
    described.push(fieldErrorId(controlId));
  }
  if (description.hasHint) {
    described.push(description.hintId ?? fieldHintId(controlId));
  }

  return {
    ...(description.invalid ? { 'aria-invalid': true as const } : {}),
    ...(described.length === 0 ? {} : { 'aria-describedby': described.join(' ') }),
  };
}

/**
 * Wraps refusal text in the element that {@link fieldAriaProps} points at.
 *
 * Assumptions: the wrapper carries the identifier and nothing else — no colour, no weight, no role.
 * antd's explain container is what colours the text through `FIELD_ERROR_TOKENS`, and a `role`
 * would announce the sentence twice: once as the control's description and once as a live region,
 * where the baseline announced it once beside the field.
 * @param {string} controlId - The `id` of the control the text describes.
 * @param {string} refusal - The refusal sentence, already chosen by the screen or carried by the
 *   problem document; never composed here.
 * @returns {ReactElement} The element to pass as a `Form.Item`'s `help`.
 */
export function fieldErrorHelp(controlId: string, refusal: string): ReactElement {
  return <span id={fieldErrorId(controlId)}>{refusal}</span>;
}

/*
 * WHY : Assumptions: this style makes an element's text available to assistive technology and to nothing
 *       else, and it exists because some reference screens name a value with NO painted label at all.
 *       The pending-authorization summary paints two address lines whose mapset fields have no preceding
 *       `INITIAL=` literal, so the migrated `Descriptions` entries had an empty header cell and their
 *       values were announced with no name. The two ways out are a VISIBLE label, which would put text
 *       on screen that no baseline source declares and which transformation rule T8 forecloses, and a
 *       label that is present in the accessibility tree only -- which is this.
 * WHY : Assumptions: every value below is MECHANISM rather than design, which is why none of them is a
 *       design token and none needs to be. This is the long-established clip-rect recipe: a one-pixel
 *       box, its overflow clipped away, pulled out of the flow and prevented from wrapping. The single
 *       pixel is deliberate -- a zero-sized or `display: none` element is removed from the accessibility
 *       tree by most engines, so the name would vanish along with the appearance, which is the failure
 *       this replaces rather than a variation of it.
 * WHY : Alternatives Considered: (1) `aria-label` on the value element itself. Rejected because for a
 *       static text node an accessible name REPLACES the content for a screen reader, so the address
 *       would be announced as the word "Address" and the address itself never read. (2) Merging the two
 *       address lines into one labelled entry. Rejected because the mapset paints them on two different
 *       rows with another field between them, and merging would reorder the panel -- reading order is
 *       the half of design gap G1 that is kept. (3) `aria-labelledby` pointing at an existing label.
 *       Rejected because there is no such label anywhere on that screen to point at.
 */

/**
 * Stable `data-testid` on the blank-field marker, so it is never mistaken for the required asterisk.
 *
 * Purpose: the two glyphs are the same character and the design system's required marker is drawn by a
 * stylesheet pseudo-element, which no query can reach. A review found the two conflated on one screen
 * and found the marker missing on five others, and a case that searched for the character alone could
 * not tell a screen that renders this marker from one that merely marks a field required. This handle
 * is what makes the distinction assertable.
 */
export const BLANK_FIELD_MARKER_TEST_ID = 'blank-field-marker';

/**
 * Character width of the blank-field marker, for a caller sizing the box that has to hold it.
 *
 * Purpose: a control that renders this marker as an antd `suffix` shares one box between the value and
 * the marker, so a width computed for the value alone clips the value the marker is drawing attention
 * to. `copybookFieldWidthStyle` in `ui/src/layout/recordLayout.ts` takes the slot's width as an
 * argument for that reason, and this is the value to pass: the marker's own character count, taken from
 * the same token the marker itself is drawn from rather than written as a literal `1` at each call site.
 *
 * ⚠️ Assumptions: derived from `FIELD_ERROR_TOKENS.blankMarker` rather than stated, so a marker changed
 * to a two-character sequence widens every field that carries it in the same edit. A literal would
 * leave nine call sites silently reserving one character for a two-character marker, which is exactly
 * the clipping this constant exists to prevent.
 */
export const BLANK_FIELD_MARKER_CHARACTERS: number = FIELD_ERROR_TOKENS.blankMarker.length;

/**
 * How the baseline's templated highlight renders one refused field.
 *
 * Assumptions: two members because the copybook does two things, and they do not always coincide -
 * `app/cpy/CSSETATY.cpy` L17-L27 moves the error colour into the field when the validation flag is
 * not-OK OR blank, and writes the literal asterisk into the field ONLY in the blank case. Returning one
 * object with an always-present colour and a conditional marker is what keeps a caller from having to
 * remember which half applies to which state.
 */
export interface FieldRefusalRendering {
  /**
   * Style to merge into the control's own, empty when the field holds no refused value.
   *
   * Assumptions: it is a style to MERGE rather than a style to pass, and the distinction matters at the
   * call site: several screens already give a control a fixed-pitch face or a width, and a caller that
   * passed this object as `style` would drop theirs. Merge it last, so the refusal colour wins over a
   * screen's own colour for as long as the refusal stands.
   */
  readonly style: CSSProperties;

  /**
   * The blank marker, present only while the field is blank; pass it as the control's `suffix`.
   *
   * Assumptions: omitted rather than `null` when it does not apply, because `exactOptionalPropertyTypes`
   * is enabled and an explicitly `undefined` suffix is not the same as an absent one to antd's `Input`.
   */
  readonly suffix?: ReactElement | undefined;
}

/*
 * Refactoring Rationale: the marker is built HERE rather than per screen, and that is the whole point
 * of this helper. A rendering review found it on 3 of 8 forms - present on user-add, user-update and
 * transaction-add, absent from sign-on, bill-pay and reports, and on account-update rendered as a slot
 * that is never populated - while a ninth form substituted the design system's always-on required
 * asterisk, which says "this field must be filled" and not "this field was left blank on the turn just
 * taken". Three screens had authored the same nine lines independently, so a fourth screen adopting the
 * contract meant copying them again.
 *
 * ⚠️ Assumptions: the marker is HIDDEN from assistive technology, which sounds like a loss and is not.
 * A lone asterisk announced beside a value conveys nothing to a listener - it is a positional
 * convention of a character terminal - and the same fact reaches assistive technology properly through
 * `aria-invalid` and the refusal sentence that {@link fieldAriaProps} associates with the control. The
 * three screens that already render it made this choice too; it is recorded here so the fourth does not
 * have to rediscover the reasoning.
 *
 * Alternatives Considered: rendering the marker as a prefix, which is where the design system puts its
 * required asterisk. Rejected because it would place the two identical glyphs in the same position for
 * two different meanings, which is precisely the conflation a review reported: the reference wrote its
 * asterisk INTO the field's own columns, so a suffix inside the control's box is the position that
 * corresponds and the one that cannot be confused with a label decoration.
 *
 * Trade-offs: a suffix sits at the RIGHT-HAND end of the control's box, so it is only adjacent to the
 * value while the control is sized to the data. A review measured the marker at x≈1211 on a
 * full-width 1172-pixel input, roughly 1150 pixels from the value it qualified. The other half of the
 * remedy is therefore `copybookFieldWidthStyle` in `ui/src/layout/recordLayout.ts`, which sizes a
 * control from the width its copybook PICTURE declares - and the two halves are complementary rather
 * than alternative, because a marker cannot be brought to the value by any means while the field is
 * eight times wider than the value.
 */

/**
 * Builds the marker the baseline writes into a field that was left blank.
 *
 * Assumptions: the character comes from `FIELD_ERROR_TOKENS.blankMarker` and the colour from
 * `FIELD_ERROR_TOKENS.errorColor`, so neither the glyph nor the hue is written here. The copybook is
 * what declares both, and the token module is where that transcription lives.
 * @param {GlobalToken} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`.
 * @returns {ReactElement} The marker element, ready to pass as a control's `suffix`.
 */
export function blankFieldMarker(cssVar: GlobalToken): ReactElement {
  return (
    <Typography.Text
      aria-hidden="true"
      data-testid={BLANK_FIELD_MARKER_TEST_ID}
      style={{ color: cssVar[FIELD_ERROR_TOKENS.errorColor] }}
    >
      {FIELD_ERROR_TOKENS.blankMarker}
    </Typography.Text>
  );
}

/**
 * Reports how one field renders while the turn just taken refused its value.
 *
 * Purpose: give every form ONE way to render the baseline's field-level highlight, so the two states
 * the copybook distinguishes are distinguished the same way on all of them. `app/cpy/CSSETATY.cpy`
 * L17-L27 is the contract: the error colour goes into the field for a not-OK value AND for a blank one,
 * and the literal asterisk goes into the field for a blank one only.
 *
 * Assumptions: the caller passes the state it received rather than deriving it from the value, because
 * `BLANK` is a decision the service or the screen's own edit made about a turn - a field that is empty
 * because nothing has been typed into it yet is not a refused field, and rendering it as one would mark
 * every untouched control on first paint.
 * @param {FieldValidationState | undefined} state - The refusal state carried for this field, or
 *   `undefined` when the field's value was not refused.
 * @param {GlobalToken} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`.
 * @returns {FieldRefusalRendering} The style to merge into the control and, for a blank field, the
 *   marker to pass as its suffix.
 */
export function fieldRefusalRendering(
  state: FieldValidationState | undefined,
  cssVar: GlobalToken,
): FieldRefusalRendering {
  if (state === undefined) {
    return { style: {} };
  }

  const style: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };

  /*
   * Assumptions: `NOT_OK` returns the colour and NO marker, which is the copybook's own asymmetry
   * rather than an omission here. L20 moves `DFHRED` for either flag; only L24, inside the blank arm,
   * moves the asterisk. A helper that marked both states would tell an operator a field is empty when
   * it holds a value the service rejected.
   */
  return state === 'BLANK' ? { style, suffix: blankFieldMarker(cssVar) } : { style };
}

/** Style that leaves an element's text in the accessibility tree while removing it from view. */
export const VISUALLY_HIDDEN_STYLE: CSSProperties = {
  blockSize: '1px',
  clipPath: 'inset(50%)',
  inlineSize: '1px',
  overflow: 'hidden',
  position: 'absolute',
  whiteSpace: 'nowrap',
};

/*
 * Refactoring Rationale: the two state axes below — is this control WORKING, and is this control
 * UNAVAILABLE — join the refusal axis already in this module, because all three answer the same
 * question about one control and all three were being answered per screen or not at all. A review
 * measured the consequence on both new axes. `aria-busy` reached 6 of 13 busy states and never a
 * button, and no live region anywhere announced that a request had started or finished, so a
 * keyboard-only operator pressed a key and was told nothing until the answer arrived. `aria-disabled`
 * was absent on 6 of 6 unavailable controls, `pointer-events` stayed `auto` on all 6, and nothing on
 * screen said WHY a control was unavailable.
 *
 * Alternatives Considered: a module of its own for the two state axes. Rejected because it would
 * separate `aria-describedby` from the only helper that builds it: an unavailable control explains
 * itself by POINTING at rendered text, which is the mechanism {@link fieldAriaProps} and
 * {@link fieldHintId} already implement here. Two modules would each need half of it.
 */

/** The ARIA member reporting that a control or region is mid-request. */
export interface BusyAriaProps {
  /**
   * Present and true only while the request is outstanding.
   *
   * Assumptions: omitted rather than emitted as `false` when idle, for the reason the refusal member
   * above is omitted — `aria-busy` defaults to false in the absence of the attribute, so an explicit
   * `false` adds an attribute change to every settle without adding a fact.
   */
  readonly 'aria-busy'?: true;
}

/** The ARIA members reporting that a control is present but cannot be used. */
export interface UnavailableAriaProps {
  /** Present and true only while the control is unavailable. */
  readonly 'aria-disabled'?: true;
  /** Identifier of the rendered element saying why, when the caller renders one. */
  readonly 'aria-describedby'?: string;
}

/**
 * Stable `data-testid` on the scoped busy live region.
 *
 * Purpose: the region is visually hidden by design, so a case asserting that a screen announces its
 * requests has nothing to query by appearance and no user-visible string it is allowed to assume.
 */
export const BUSY_ANNOUNCEMENT_TEST_ID = 'busy-announcement';

/*
 * Refactoring Rationale: this suppression exists because the design system's own busy wrapper makes
 * EVERYTHING it wraps a live region. `node_modules/antd/lib/spin/index.js` L131-L133 puts
 * `aria-live="polite"` and `aria-busy` on the wrapper's root and then spreads `...restProps` AFTER
 * them, so a caller's own value wins — which is the seam this constant uses. A review measured what
 * happens without it: the busy wrapper around one screen's form made a 1173×210 region holding 193
 * characters into a polite live region, so every re-render of that form re-announced all 193
 * characters, and the same shape surrounded a table on another screen.
 *
 * Assumptions: `aria-busy` is deliberately LEFT IN PLACE. It is the correct statement about a region
 * whose content is being replaced, and a review found too few of them rather than too many; it is
 * only the live-region part that turns a large subtree into an announcement.
 *
 * Alternatives Considered: (1) Not wrapping a large subtree at all, and rendering a standalone
 * indicator instead — which five screens already do and which remains the better shape where the
 * content can be replaced wholesale. It is not always available: a wrapper is what keeps the fields an
 * operator just filled visible while the turn is in flight, which is the reason the wrapping screens
 * chose it. (2) A wrapper class overriding the attribute — impossible, an ARIA attribute is not
 * styleable. (3) `aria-live="off"` on an ancestor — rejected because a nearer `aria-live` on the
 * wrapper itself wins, which is exactly the element that carries it.
 */

/**
 * Props that stop the design system's busy wrapper from making its whole subtree a live region.
 *
 * Purpose: pass this to a busy wrapper that wraps more than the announcement itself, and pair it with
 * {@link busyAnnouncement} so the transition is still announced — once, as a sentence, rather than as
 * the entire wrapped subtree read again.
 */
export const WRAPPED_BUSY_REGION_PROPS: { readonly 'aria-live': 'off' } = { 'aria-live': 'off' };

/**
 * Style that makes an `aria-disabled` element inert to the pointer as well as to assistive technology.
 *
 * Assumptions: this is for the `aria-disabled` path ONLY, and a natively `disabled` control neither
 * needs it nor should be given it — the browser already refuses activation there, and the design
 * system's own disabled rendering already carries the appearance. A review measured `pointer-events`
 * still `auto` on all six unavailable controls it found, which matters precisely on this path: an
 * element that advertises itself as disabled and still responds to a click is worse than either
 * consistent state.
 *
 * Trade-offs: an inert element cannot open a hover tooltip, so the reason a control is unavailable has
 * to be rendered as text and pointed at with {@link unavailableProps} rather than hidden behind a
 * hover. That is the better arrangement anyway — a hover-only explanation is unreachable by keyboard
 * and by touch — but it does mean the caller must render the sentence somewhere.
 */
export const UNAVAILABLE_CONTROL_STYLE: CSSProperties = { pointerEvents: 'none' };

/**
 * Builds the ARIA member reporting that a control or region has a request outstanding.
 *
 * Purpose: give every screen one way to state busyness, including on a BUTTON — a review found
 * `aria-busy` on no button anywhere, so the control an operator had just activated was the one element
 * that never said it was working.
 *
 * Assumptions: this states busyness and does NOT announce it; the two are separate because they have
 * different scopes. `aria-busy` tells assistive technology that the element it is on is mid-change,
 * which suppresses chatter rather than producing any, and {@link busyAnnouncement} is what produces
 * the single sentence an operator hears.
 * @param {boolean} busy - Whether a request for this control or region is outstanding.
 * @returns {BusyAriaProps} The member to spread onto the element; empty while idle.
 */
export function busyProps(busy: boolean): BusyAriaProps {
  return busy ? { 'aria-busy': true as const } : {};
}

/**
 * Renders the scoped live region that announces one screen's request transitions.
 *
 * Purpose: replace a live region wrapped around a whole form with one wrapped around the announcement,
 * so what is announced is the sentence the caller chose rather than every character the form happens to
 * hold.
 *
 * Assumptions: the region is ALWAYS rendered, empty while there is nothing to announce, and this is
 * load-bearing rather than defensive. A live region has to be in the accessibility tree BEFORE its
 * content changes for the change to be announced; an element mounted with its text already in place is
 * frequently treated as initial content and read out by nothing. Returning `null` when idle would
 * therefore produce a region that never announces its first transition, which is the one that matters.
 *
 * Assumptions: `role="status"` rather than an explicit `aria-live`, because the role carries a polite
 * live region plus the semantic that this is advisory status. Politeness is the same choice
 * `ui/src/layout/MessageBand.tsx` makes for its informational channel and for the same reason: a
 * transition an operator did not have to wait for must not interrupt them mid-field.
 *
 * Assumptions: the sentence is passed IN and never composed here. Every user-visible string belongs to
 * `ui/src/messages/messages.ts`, and this module holds none.
 * @param {string | undefined} announcement - The sentence to announce, or `undefined` while there is
 *   nothing to announce.
 * @returns {ReactElement} The live region, visually hidden because the screen's own spinner or busy
 *   control already carries the visible half.
 */
export function busyAnnouncement(announcement: string | undefined): ReactElement {
  return (
    <span data-testid={BUSY_ANNOUNCEMENT_TEST_ID} role="status" style={VISUALLY_HIDDEN_STYLE}>
      {announcement ?? ''}
    </span>
  );
}

/**
 * Builds the ARIA members reporting that a control is present but cannot be used, and why.
 *
 * Purpose: a review found `aria-disabled` on none of the unavailable controls it measured and found
 * nothing anywhere explaining why a control was unavailable. Both halves are produced together for the
 * reason {@link fieldAriaProps} produces its two together: stating THAT a control cannot be used
 * without stating WHY leaves the operator with a dead control and no remedy.
 *
 * Assumptions: for a control rendered with the platform's own `disabled` attribute this returns the
 * REASON only and deliberately not `aria-disabled`, because the platform already reports the state and
 * a second statement of it is redundant. Pass `unavailable` as true on the `aria-disabled` path — where
 * the element stays focusable so the operator can reach it and hear why — and pair it with
 * {@link UNAVAILABLE_CONTROL_STYLE}.
 * @param {boolean} unavailable - Whether the control is unavailable on the `aria-disabled` path.
 * @param {string | undefined} reasonId - Identifier of the rendered element saying why, or `undefined`
 *   when the caller renders no such element.
 * @returns {UnavailableAriaProps} The members to spread onto the control; empty when it is available
 *   and nothing explains it.
 */
export function unavailableProps(
  unavailable: boolean,
  reasonId: string | undefined,
): UnavailableAriaProps {
  return {
    ...(unavailable ? { 'aria-disabled': true as const } : {}),
    ...(reasonId === undefined ? {} : { 'aria-describedby': reasonId }),
  };
}
