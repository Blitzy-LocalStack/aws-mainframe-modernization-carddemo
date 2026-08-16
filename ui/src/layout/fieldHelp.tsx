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

/** Style that leaves an element's text in the accessibility tree while removing it from view. */
export const VISUALLY_HIDDEN_STYLE: CSSProperties = {
  blockSize: '1px',
  clipPath: 'inset(50%)',
  inlineSize: '1px',
  overflow: 'hidden',
  position: 'absolute',
  whiteSpace: 'nowrap',
};
