/**
 * @file Tests for the baseline monetary edit mask in `ui/src/format/money.ts`.
 *
 * Purpose
 * -------
 * Pin the exact character output of `+ZZZ,ZZZ,ZZZ.99` for every branch of the picture: the fixed sign
 * position, zero suppression to blanks, separator placement, the always-visible decimal positions,
 * the widest value that fits, and the two documented trade-offs -- no high-order truncation, and
 * pass-through for a value that does not match the wire contract.
 *
 * Assumptions: expectations are written as exact strings with their spaces visible in the source
 * rather than assembled by padding helpers. A helper would reimplement the code under test, so a
 * shared mistake would agree with itself and pass; a literal cannot.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { describe, expect, it } from 'vitest';

import {
  MONEY_MASK_DECIMAL_POSITIONS,
  MONEY_MASK_INTEGER_POSITIONS,
  MONEY_MASK_WIDTH,
  applyMoneyEditMask,
  stripMoneyEditMask,
} from './money';

/** Wire values paired with the exact string the mask must produce for each. */
const MASKED: ReadonlyArray<readonly [string, string]> = [
  // A positive amount: five significant digits, one separator, six blanks ahead of it.
  ['1234.56', '+      1,234.56'],
  // The same magnitude negative: only the sign position differs.
  ['-1234.56', '-      1,234.56'],
  // Zero: the integer field suppresses entirely, the decimal positions do not.
  ['0.00', '+           .00'],
  // A single significant digit: ten blanks ahead of it, and a leading zero kept in the decimals.
  ['7.05', '+          7.05'],
  // Exactly one thousand: the first separator appears with nothing suppressed after it.
  ['1000.00', '+      1,000.00'],
  // Under one thousand: no separator at all.
  ['999.99', '+        999.99'],
  // The widest value the picture holds: nine integer digits, both separators.
  ['999999999.99', '+999,999,999.99'],
  // Negative at that same width.
  ['-999999999.99', '-999,999,999.99'],
];

/**
 * Every wire value renders to the exact masked string the picture specifies.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersEachValueExactly(): void {
  for (const [wire, expected] of MASKED) {
    expect(applyMoneyEditMask(wire)).toBe(expected);
  }
}

/**
 * Every value that fits the picture renders at exactly the declared width.
 *
 * Assumptions: the width is asserted against the exported constant rather than against fifteen
 * written again here, so the constant and the rendering cannot drift apart.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersAtTheDeclaredWidth(): void {
  for (const [wire] of MASKED) {
    expect(applyMoneyEditMask(wire)).toHaveLength(MONEY_MASK_WIDTH);
  }
}

/**
 * The declared width is the sum of the picture's own positions.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function widthIsTheSumOfThePicturePositions(): void {
  expect(MONEY_MASK_INTEGER_POSITIONS).toBe(9);
  expect(MONEY_MASK_DECIMAL_POSITIONS).toBe(2);
  expect(MONEY_MASK_WIDTH).toBe(15);
}

/**
 * A value needing a tenth integer digit keeps every digit instead of losing the high-order one.
 *
 * Assumptions: this is the documented divergence `D-MONEY-MASK-NO-TRUNCATION`. The reference would
 * render `+234,567,890.12` here, having discarded the leading `1` in a decimal-aligned MOVE into a
 * nine-position field. The assertion states BOTH halves -- what is produced, and that the truncated
 * form is not -- so the case fails if the behaviour is ever quietly changed to match the reference.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsEveryDigitBeyondThePictureWidth(): void {
  expect(applyMoneyEditMask('1234567890.12')).toBe('+1,234,567,890.12');
  expect(applyMoneyEditMask('1234567890.12')).not.toBe('+234,567,890.12');
  expect(applyMoneyEditMask('-9999999999.99')).toBe('-9,999,999,999.99');
}

/**
 * The maximum the service can publish is rendered in full.
 *
 * Assumptions: `Money.MAX_MAGNITUDE` is `9999999999.99`, so this is the widest value that can ever
 * arrive rather than an arbitrary large number.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function rendersTheServiceMaximumInFull(): void {
  expect(applyMoneyEditMask('9999999999.99')).toBe('+9,999,999,999.99');
}

/** Values that do not match the wire contract, each of which must pass straight through. */
const NOT_WIRE_MONEY: readonly string[] = [
  '',
  '1234',
  '1234.5',
  '1234.567',
  '1,234.56',
  '+1234.56',
  'abc',
  ' 1234.56',
  '1234.56 ',
];

/**
 * A value outside the wire contract is returned unchanged rather than rejected or altered.
 *
 * Assumptions: this pins the documented trade-off. The amount stays truthful even when its
 * presentation cannot be applied, and pinning it here means the pass-through is a decision rather
 * than an accident of control flow.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function passesNonContractValuesThrough(): void {
  for (const value of NOT_WIRE_MONEY) {
    expect(applyMoneyEditMask(value)).toBe(value);
  }
}

/**
 * The mask never routes a value through a numeric conversion.
 *
 * Assumptions: this is asserted BEHAVIOURALLY rather than by reading the source. A value carrying
 * more precision than a double can hold is rendered digit for digit, which is only possible if no
 * parse happened -- `Number('9007199254740993.99')` loses the final integer digit, so a
 * parse-and-reformat implementation could not produce this string.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function neverRoutesThroughADouble(): void {
  expect(applyMoneyEditMask('9007199254740993.99')).toBe('+9,007,199,254,740,993.99');
}

/**
 * Registers the mask cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function moneyEditMaskCases(): void {
  it('renders each wire value to its exact masked string', rendersEachValueExactly);
  it('renders every fitting value at the declared width', rendersAtTheDeclaredWidth);
  it(
    'declares a width equal to the sum of its picture positions',
    widthIsTheSumOfThePicturePositions,
  );
  it('keeps every digit beyond the picture width', keepsEveryDigitBeyondThePictureWidth);
  it('renders the service maximum in full', rendersTheServiceMaximumInFull);
  it('passes a value outside the wire contract through unchanged', passesNonContractValuesThrough);
  it('never routes a value through a double', neverRoutesThroughADouble);
}

describe('applyMoneyEditMask', moneyEditMaskCases);

/**
 * Every value the mask emits is reduced back to the plain form the service parses.
 *
 * Assumptions: this is the round trip that matters, and it is asserted over the SAME table the mask
 * cases use, so a mask output the reverse cannot read is impossible to add without failing here. The
 * expectation is the canonical wire form, which for a zero amount is `0.00` rather than the blank
 * integer field the mask paints.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function reversesEveryMaskedValue(): void {
  for (const [wire, masked] of MASKED) {
    expect(stripMoneyEditMask(masked)).toBe(wire);
  }
}

/**
 * Decoration an operator may type by hand is accepted as well as the mask's own.
 *
 * Assumptions: these forms are admitted because the control is editable, so the reverse has to read
 * whatever a person plausibly types -- not only what the mask paints. The reference's own parser allows
 * the same latitude on that field.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function reversesHandTypedDecoration(): void {
  expect(stripMoneyEditMask('1,234.56')).toBe('1234.56');
  expect(stripMoneyEditMask('  1234.56  ')).toBe('1234.56');
  expect(stripMoneyEditMask('1234.56-')).toBe('-1234.56');
  expect(stripMoneyEditMask('-1234.56')).toBe('-1234.56');
  expect(stripMoneyEditMask('+1234.56')).toBe('1234.56');
  expect(stripMoneyEditMask('1234')).toBe('1234.00');
  expect(stripMoneyEditMask('1234.5')).toBe('1234.50');
  expect(stripMoneyEditMask('1 234.56')).toBe('1234.56');

  /*
   * WHY : Assumptions: an absent integer part reads as zero, matching the reference's own parser and
   *       matching the mask's rendering of zero, whose integer field is entirely suppressed to blanks.
   */
  expect(stripMoneyEditMask('.56')).toBe('0.56');
}

/**
 * Text that is not an amount is handed back untouched for the service to refuse.
 *
 * Assumptions: the service composes the operator-facing refusal, so this must not normalise something
 * it cannot read into something that looks valid, and must not invent a second refusal of its own.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function leavesNonAmountsAlone(): void {
  for (const value of ['', '   ', 'ABC', '--1234.56', '1234.567', '*']) {
    expect(stripMoneyEditMask(value)).toBe(value);
  }
}

/**
 * The reverse never routes a value through a numeric conversion either.
 *
 * Assumptions: asserted behaviourally, like the mask's own case -- a magnitude beyond a double's exact
 * range survives digit for digit, which a parse-and-reformat implementation could not achieve.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function reverseNeverRoutesThroughADouble(): void {
  expect(stripMoneyEditMask('+9,007,199,254,740,993.99')).toBe('9007199254740993.99');
}

/**
 * Registers the reverse-mask cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function stripMoneyEditMaskCases(): void {
  it('reverses every value the mask emits', reversesEveryMaskedValue);
  it('reverses decoration an operator may type by hand', reversesHandTypedDecoration);
  it('leaves text that is not an amount alone', leavesNonAmountsAlone);
  it('never routes a value through a double', reverseNeverRoutesThroughADouble);
}

describe('stripMoneyEditMask', stripMoneyEditMaskCases);
