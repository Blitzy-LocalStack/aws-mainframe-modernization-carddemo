/**
 * @file Proves the shared field-refusal mechanism renders the baseline's templated highlight.
 *
 * Purpose
 * -------
 * `app/cpy/CSSETATY.cpy` L17-L27 is a templated copybook every online program includes once per
 * validated field. It does two things and they are not the same thing: it moves `DFHRED` into the
 * field's colour attribute when the field's validation flag is not-OK OR blank, and it additionally
 * moves the literal `'*'` into the field only when the field is blank. So the colour marks "this value
 * was refused" and the asterisk marks "and there was nothing in it".
 *
 * A rendering review found that contract implemented on 3 of 8 forms, absent from three, present as a
 * never-populated slot on a fourth, and substituted on a fifth by the design system's always-on
 * required asterisk — which is the same glyph asserting a different fact. The remedy is one shared
 * mechanism in `ui/src/layout/fieldHelp.tsx`, and these cases are what pin its two halves apart: the
 * colour that applies to both states, and the marker that applies to one.
 *
 * The second group covers the other half of the same finding. The marker is a control SUFFIX, so it is
 * adjacent to the value only while the control is the width of the data; the review measured it at
 * x≈1211 inside a 1172-pixel input, roughly 1150 pixels from the value it qualified.
 * `copybookFieldWidthStyle` in `ui/src/layout/recordLayout.ts` is what sizes a transcribed field from
 * the width its PICTURE clause declares, and its cases are here because it exists for this defect.
 *
 * Assumptions: every colour assertion compares a `var(--…)` DECLARATION and never a hue. jsdom has no
 * layout engine and no cascade for these variables, so the reference form is the only observable fact —
 * and it is the right one to observe, because writing a resolved hue into an element is itself the
 * failure the token bridge exists to prevent.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted.
import { render, renderHook, screen } from '@testing-library/react';
import { Form, Input, theme } from 'antd';
import type { GlobalToken } from 'antd';
import type { CSSProperties } from 'react';
import { describe, expect, it } from 'vitest';

import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import { BLANK_FIELD_MARKER_TEST_ID, blankFieldMarker, fieldRefusalRendering } from './fieldHelp';
import { copybookFieldWidthStyle } from './recordLayout';

/** Declared width of the reference's user identifier, `PIC X(08)` at `app/cpy/CSUSR01Y.cpy` L18. */
const USER_ID_WIDTH = 8;

/** A label standing for any transcribed field caption, so a required marker has somewhere to attach. */
const FIELD_LABEL = 'User ID';

/**
 * Resolves the CSS-variable reference form of one design-system token name.
 *
 * Assumptions: the reference is read from the LIVE theme through the same hook the helpers read, rather
 * than composed here from the token name. antd's variable-naming scheme is the helpers' dependency and
 * not this suite's, so deriving `--ant-color-error-text` from `colorErrorText` in a test would put a
 * second description of that scheme in the tree, agreeing with the code only while both happened to
 * spell it alike.
 * @returns {ReturnType<typeof theme.useToken>['cssVar']} The reference form of every token name.
 */
function themeReferences(): ReturnType<typeof theme.useToken>['cssVar'] {
  const { result, unmount } = renderHook(
    /**
     * Reads the theme's variable-reference table.
     * @returns {object} The reference form of every token name, keyed by token name.
     */
    () => theme.useToken().cssVar,
  );

  const references = result.current;

  // Assumptions: the probe is unmounted before a case renders anything, so the queries below run
  //   against one container -- Testing Library appends every render to the document body.
  unmount();

  return references;
}

/**
 * Both refused states take the error colour, because the copybook moves it for either flag.
 *
 * Purpose: the colour is the half of the contract that applies to BOTH states, and it is the half a
 * screen is most likely to drop when it implements only the visible asterisk. `CSSETATY` L20 moves
 * `DFHRED` under the not-OK flag and L23 moves it again under the blank flag.
 * @returns {void} Nothing; assertions raise on failure.
 */
function paintsBothRefusedStatesInTheErrorColour(): void {
  const cssVar = themeReferences();
  const expected = cssVar[FIELD_ERROR_TOKENS.errorColor];

  expect(expected, 'the bridge must publish an error text colour').not.toBe('');
  expect(fieldRefusalRendering('NOT_OK', cssVar).style.color).toBe(expected);
  expect(fieldRefusalRendering('BLANK', cssVar).style.color).toBe(expected);
}

/**
 * An unrefused field takes no colour and no marker.
 *
 * Purpose: the mechanism has to be safe to call for every field on every render, including the fields
 * nothing is wrong with — otherwise a screen has to guard each call and the guard is the thing that
 * gets forgotten. An empty style object is what lets a caller merge unconditionally.
 * @returns {void} Nothing; assertions raise on failure.
 */
function leavesAnUnrefusedFieldAlone(): void {
  const rendering = fieldRefusalRendering(undefined, themeReferences());

  expect(rendering.style.color).toBeUndefined();
  expect(rendering.suffix).toBeUndefined();
}

/**
 * The asterisk is written for a BLANK field only, exactly as the copybook writes it.
 *
 * Purpose: this is the distinction the review found collapsed. `CSSETATY` writes the literal `'*'` only
 * inside its blank arm at L24, so a field holding a value the service rejected is coloured and NOT
 * marked — marking it would tell the operator the field is empty when it is not.
 * @returns {void} Nothing; assertions raise on failure.
 */
function writesTheAsteriskForABlankFieldOnly(): void {
  const cssVar = themeReferences();

  expect(fieldRefusalRendering('BLANK', cssVar).suffix).not.toBeUndefined();
  expect(fieldRefusalRendering('NOT_OK', cssVar).suffix).toBeUndefined();
}

/**
 * The marker is the copybook's glyph, in the bridge's colour, and hidden from assistive technology.
 *
 * Purpose: pin all three properties of the rendered marker at once, because each has a plausible wrong
 * value. The glyph must be the transcribed literal rather than a typographic bullet; the colour must be
 * a token reference rather than a hue; and the element must be hidden, because a lone asterisk
 * announced beside a value conveys nothing to a listener — the blankness reaches assistive technology
 * through `aria-invalid` and the associated refusal sentence instead.
 * @returns {void} Nothing; assertions raise on failure.
 */
function rendersTheMarkerAsTheCopybookDeclaresIt(): void {
  const cssVar = themeReferences();

  render(blankFieldMarker(cssVar));

  const marker = screen.getByTestId(BLANK_FIELD_MARKER_TEST_ID);

  expect(marker.textContent, "CSSETATY L24 writes the literal '*'").toBe(
    FIELD_ERROR_TOKENS.blankMarker,
  );
  expect(marker.style.color).toBe(cssVar[FIELD_ERROR_TOKENS.errorColor]);
  expect(marker.getAttribute('aria-hidden')).toBe('true');
  expect(
    screen.queryByText(FIELD_ERROR_TOKENS.blankMarker, { ignore: '[aria-hidden="true"]' }),
    'the glyph must not be announced on its own',
  ).toBeNull();
}

/**
 * The blank marker and the required marker are separate mechanisms, reachable separately.
 *
 * Purpose: a review reported the two conflated — one form substituted the design system's always-on
 * required asterisk for the baseline's blank marker, so a field that must be filled and a field that
 * WAS left blank rendered identically. They assert different facts and have to stay distinguishable.
 *
 * Assumptions: the required marker is asserted by its CLASS and not by its text, because the design
 * system draws it with a stylesheet pseudo-element. That is the strongest available statement of the
 * separation: the two glyphs cannot collide in a query, because only one of them is a DOM node at all —
 * and it is the one that carries {@link BLANK_FIELD_MARKER_TEST_ID}.
 * @returns {void} Nothing; assertions raise on failure.
 */
function keepsTheBlankMarkerApartFromTheRequiredMarker(): void {
  const cssVar = themeReferences();
  const rendering = fieldRefusalRendering('BLANK', cssVar);

  render(
    <Form>
      <Form.Item label={FIELD_LABEL} required>
        <Input suffix={rendering.suffix} style={rendering.style} />
      </Form.Item>
    </Form>,
  );

  const required = document.querySelector('.ant-form-item-required');

  expect(required, 'the design system must still mark the field required').not.toBeNull();
  expect(required?.textContent, 'the required glyph is drawn, not written').toBe(FIELD_LABEL);
  expect(screen.getByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeInTheDocument();
  expect(
    document.querySelectorAll(`[data-testid="${BLANK_FIELD_MARKER_TEST_ID}"]`),
    'exactly one glyph may assert that the field was left blank',
  ).toHaveLength(1);
}

/**
 * Groups the field-refusal rendering cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function fieldRefusalCases(): void {
  it('paints both refused states in the error colour', paintsBothRefusedStatesInTheErrorColour);
  it('leaves an unrefused field alone', leavesAnUnrefusedFieldAlone);
  it('writes the asterisk for a blank field only', writesTheAsteriskForABlankFieldOnly);
  it('renders the marker as the copybook declares it', rendersTheMarkerAsTheCopybookDeclaresIt);
  it(
    'keeps the blank marker apart from the required marker',
    keepsTheBlankMarkerApartFromTheRequiredMarker,
  );
}

describe('field refusal rendering', fieldRefusalCases);

/**
 * A transcribed field is capped at its declared character width plus the control's own padding.
 *
 * Purpose: a control sized to the viewport puts everything at its right-hand edge — the blank marker
 * among them — an arbitrary distance from the value. The cap is expressed in `ch`, the advance measure
 * of the font's zero glyph, which is the browser's nearest equivalent of a character column.
 *
 * Assumptions: the padding allowance is asserted to be a TOKEN REFERENCE rather than a number, because
 * controls are border-box: a bare `8ch` cap would give the text eight columns minus the design system's
 * padding on both sides and clip a value that fills its declared width.
 * @returns {void} Nothing; assertions raise on failure.
 */
function capsAFieldAtItsDeclaredWidth(): void {
  const cssVar = themeReferences();
  const style = copybookFieldWidthStyle(USER_ID_WIDTH, cssVar);

  expect(style.maxInlineSize).toContain(`${String(USER_ID_WIDTH)}ch`);
  expect(style.maxInlineSize).toContain(String(cssVar.controlPaddingHorizontal));
  expect(style.inlineSize, 'a field must still shrink inside a narrow viewport').toBe('100%');
}

/**
 * A wider declaration yields a wider cap, so the measure follows the copybook rather than a category.
 *
 * Purpose: rule out an implementation that snapped every field onto a handful of sizes. The reference's
 * widths are not a small set — `PIC X(08)` for a user identifier and `PIC X(50)` for an address line
 * both occur — so the measure has to be the declared number itself.
 * @returns {void} Nothing; assertions raise on failure.
 */
function tracksTheDeclaredWidthRatherThanACategory(): void {
  const cssVar = themeReferences();
  const narrow = copybookFieldWidthStyle(USER_ID_WIDTH, cssVar);
  const wide = copybookFieldWidthStyle(50, cssVar);

  expect(narrow.maxInlineSize).not.toBe(wide.maxInlineSize);
  expect(wide.maxInlineSize).toContain('50ch');
}

/**
 * Builds a thunk that sizes a field from one declared width, so a refusal can be asserted.
 *
 * Assumptions: a named factory rather than an inline thunk at each assertion, matching the convention
 * `ui/src/api/client.test.ts` established -- `ui/eslint.config.js` selects a function expression in
 * every position with `publicOnly: false`, so an inline thunk would owe its own block at every
 * specimen.
 * @param {number} declaredWidth - Copybook PICTURE width to size from, valid or not.
 * @param {GlobalToken} cssVar - The theme's CSS-variable references.
 * @returns {() => CSSProperties} A thunk invoking the published width helper.
 */
function sizingAFieldOf(declaredWidth: number, cssVar: GlobalToken): () => CSSProperties {
  /**
   * Invokes the width helper for the captured width.
   * @returns {CSSProperties} The style declaration, when the width is accepted.
   */
  return function sizeOne(): CSSProperties {
    return copybookFieldWidthStyle(declaredWidth, cssVar);
  };
}

/**
 * A width no PICTURE clause could declare is refused loudly.
 *
 * Purpose: the argument is a constant transcribed from a copybook, so a non-positive or fractional
 * value is a coding error. Clamping it would render a control with no visible text area, which reads on
 * screen as a missing field and is far harder to trace to its call site than a thrown message.
 * @returns {void} Nothing; assertions raise on failure.
 */
function refusesAWidthNoPictureCouldDeclare(): void {
  const cssVar = themeReferences();

  for (const rejected of [0, -1, 2.5]) {
    expect(sizingAFieldOf(rejected, cssVar), `${rejected} is not a field width`).toThrow(
      RangeError,
    );
  }
}

/**
 * Groups the field-width cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function copybookFieldWidthCases(): void {
  it('caps a field at its declared width', capsAFieldAtItsDeclaredWidth);
  it('tracks the declared width rather than a category', tracksTheDeclaredWidthRatherThanACategory);
  it('refuses a width no PICTURE could declare', refusesAWidthNoPictureCouldDeclare);
}

describe('copybook field width', copybookFieldWidthCases);
