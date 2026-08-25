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

import { MONEY_SIGN_TEXT_TOKENS } from '../theme/tokens';
import {
  MONEY_MASK_DECIMAL_POSITIONS,
  MONEY_MASK_INTEGER_POSITIONS,
  MONEY_MASK_WIDTH,
  MONEY_PICTURES,
  applyMoneyEditMask,
  classifyMoneySign,
  moneySignTextToken,
  renderMoney,
  stripMoneyEditMask,
} from './money';
// Assumptions: imported as a type-only import because it is used solely as an annotation, which is what
// `verbatimModuleSyntax` in `ui/tsconfig.json` requires of a name that never survives compilation.
import type { MoneyPicture } from './money';

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

/**
 * Every declared picture renders the same wire value to its own exact string.
 *
 * Assumptions: the expectations are exact literals with their padding visible, for the reason the
 * file header gives — a helper that assembled them would reimplement the code under test. The three
 * rows are the three pictures the baseline declares, and the values chosen are the ones an audit
 * measured on screen, so a reader can compare this table against a screenshot.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersEachPictureExactly(): void {
  expect(applyMoneyEditMask('1234.56', MONEY_PICTURES.accountGrouped)).toBe('+      1,234.56');
  expect(applyMoneyEditMask('1234.56', MONEY_PICTURES.billPayBalance)).toBe('+0000001234.56');
  expect(applyMoneyEditMask('1234.56', MONEY_PICTURES.transactionAmount)).toBe('+00001234.56');

  expect(applyMoneyEditMask('-987.65', MONEY_PICTURES.transactionAmount)).toBe('-00000987.65');
  expect(applyMoneyEditMask('0.00', MONEY_PICTURES.transactionAmount)).toBe('+00000000.00');
  expect(applyMoneyEditMask('0.00', MONEY_PICTURES.billPayBalance)).toBe('+0000000000.00');

  /*
   * WHY : Assumptions: the authorization picture renders `1234.56` one character SHORTER in its integer
   *       field than any other picture here and with a blank in the sign position, so this one line
   *       exercises both members that make it different. Written as a literal with its spaces visible,
   *       like every other row, so a reader can count the positions against `-zzzz9.99`.
   */
  expect(applyMoneyEditMask('1234.56', MONEY_PICTURES.authorizationSummaryAmount)).toBe(
    '  1234.56',
  );
}

/**
 * Omitting the picture renders exactly as naming the account picture does.
 *
 * Assumptions: this is what makes the parameter safe to add. Every existing call site passes one
 * argument, so the default has to be the rendering those sites already produced, and asserting it
 * over the whole existing table is stronger than asserting it once.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function defaultsToTheAccountPicture(): void {
  for (const [wire] of MASKED) {
    expect(applyMoneyEditMask(wire)).toBe(applyMoneyEditMask(wire, MONEY_PICTURES.accountGrouped));
  }
}

/**
 * Each picture's declared width is the width it actually renders at.
 *
 * Assumptions: asserted per picture against its own `width` member rather than against three
 * numbers written here, so a declaration and its rendering cannot drift. The values used are ones
 * that FIT each picture, because the no-truncation trade-off deliberately widens the ones that do
 * not.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersEachPictureAtItsDeclaredWidth(): void {
  const pictures = [
    MONEY_PICTURES.accountGrouped,
    MONEY_PICTURES.billPayBalance,
    MONEY_PICTURES.transactionAmount,
    MONEY_PICTURES.authorizationSummaryAmount,
  ];
  for (const picture of pictures) {
    for (const wire of ['0.00', '1234.56', '-1234.56']) {
      expect(applyMoneyEditMask(wire, picture)).toHaveLength(picture.width);
    }
    /*
     * WHY : Assumptions: the picture STRING is exactly as long as the value it renders, because
     *       every character of a COBOL picture is one printed position -- the leading `+`, each `Z`
     *       or `9`, each comma and the point. That makes the declared width checkable against the
     *       declared picture with no arithmetic, which is the strongest available check that the two
     *       members describe the same mask.
     */
    expect(picture.picture).toHaveLength(picture.width);
  }
}

/**
 * A value wider than its picture keeps every digit rather than losing the high-order one.
 *
 * Assumptions: the no-truncation divergence `D-MONEY-MASK-NO-TRUNCATION` applies to the zero-filled
 * pictures too, and it has to be asserted separately because they reach it by a different route —
 * `padStart` is a no-op on an over-wide value where the suppressed path skips its pad. The
 * transaction picture holds eight integer digits, so a ten-digit balance is the case that matters.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsEveryDigitBeyondEachPictureWidth(): void {
  expect(applyMoneyEditMask('9999999999.99', MONEY_PICTURES.transactionAmount)).toBe(
    '+9999999999.99',
  );
  expect(applyMoneyEditMask('9999999999.99', MONEY_PICTURES.transactionAmount)).not.toBe(
    '+99999999.99',
  );
  expect(applyMoneyEditMask('9999999999.99', MONEY_PICTURES.billPayBalance)).toBe('+9999999999.99');
}

/**
 * Every picture's rendering is reversible to the plain form the service parses.
 *
 * Assumptions: this is the round trip that keeps the zero-filled pictures usable, and it is the
 * reason the reverse strips leading zeroes — without that, `+00001234.56` would reverse to
 * `00001234.56` and the padding would be sent as if it were magnitude.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function reversesEveryPicture(): void {
  const pictures = [
    MONEY_PICTURES.accountGrouped,
    MONEY_PICTURES.billPayBalance,
    MONEY_PICTURES.transactionAmount,
    MONEY_PICTURES.authorizationSummaryAmount,
  ];
  for (const picture of pictures) {
    for (const wire of ['0.00', '7.05', '1234.56', '-1234.56', '999999999.99']) {
      expect(stripMoneyEditMask(applyMoneyEditMask(wire, picture))).toBe(wire);
    }
  }
}

/**
 * Each declared picture cites where in the baseline it was measured.
 *
 * Assumptions: asserted rather than trusted, because a picture with no provenance is a convention
 * adopted here, and the whole basis for keeping three pictures instead of unifying them is that all
 * three are measured source values. A citation must name a file under `app/`.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function citesTheBaselineForEachPicture(): void {
  /*
   * WHY : Assumptions: the values are widened to `MoneyPicture` before being walked, and the annotation
   *       is load-bearing rather than decorative. `MONEY_PICTURES` is declared `as const satisfies
   *       Record<string, MoneyPicture>`, so each entry keeps its own LITERAL type -- and an entry that
   *       omits an optional member has no such property in that type at all, which makes reading it off
   *       the union a compile error rather than the `undefined` the `??` below expects. Widening to the
   *       declared interface is what lets one loop check every picture against the same contract.
   *       Alternatives Considered: dropping `as const` so every entry widens on declaration. Rejected
   *       because the literal types are what let a reader see each picture's exact string in a hover and
   *       what let the other cases assert against those strings without a cast.
   */
  const pictures: readonly MoneyPicture[] = Object.values(MONEY_PICTURES);
  for (const picture of pictures) {
    expect(picture.source).toMatch(/^app\//u);
    expect(picture.decimalPositions).toBe(MONEY_MASK_DECIMAL_POSITIONS);
    /*
     * WHY : Refactoring Rationale: this used to assert `grouped === !zeroFilled`, which held over the
     *       three pictures declared at the time and was never a property of a COBOL picture -- it was a
     *       coincidence of that particular set. `-zzzz9.99` is ungrouped AND zero-suppressed, so the
     *       coincidence broke the moment a fourth measured picture was added. Each flag is now checked
     *       against the PICTURE STRING instead, which is the check the string is carried for and is
     *       stronger than the invariant it replaces: it would catch a flag that disagreed with its own
     *       declaration, which the old form could not.
     */
    expect(picture.grouped, `${picture.picture} grouping`).toBe(picture.picture.includes(','));
    expect(picture.zeroFilled, `${picture.picture} zero fill`).toBe(!/[Zz]/u.test(picture.picture));
    expect(picture.signsNonNegative ?? true, `${picture.picture} sign`).toBe(
      picture.picture.startsWith('+'),
    );
    /*
     * WHY : Assumptions: a zero-filled picture must declare no suppressed positions and a suppressed one
     *       must declare a count within its own integer field, because the two members describe one
     *       field between them. A suppressed count above the position count would silently produce a
     *       negative mandatory count, which `String.prototype.slice` accepts and renders as nonsense.
     */
    const suppressed = picture.suppressedIntegerPositions ?? picture.integerPositions;
    expect(suppressed).toBeGreaterThanOrEqual(0);
    expect(suppressed).toBeLessThanOrEqual(picture.integerPositions);
    if (picture.zeroFilled) {
      expect(picture.suppressedIntegerPositions, `${picture.picture} suppresses nothing`).toBe(
        undefined,
      );
    }
  }
}

/**
 * Wire values paired with the exact string `-zzzz9.99` must produce for each.
 *
 * Assumptions: written as literals with their spaces visible, like {@link MASKED}, and chosen to walk
 * every branch the picture has: the blank sign position, the mandatory units digit, the suppressed run,
 * the widest value that fits, and both signs at that width.
 */
const AUTHORIZATION_MASKED: ReadonlyArray<readonly [string, string]> = [
  // ⚠️ Zero: FOUR blanks and a printed `0`, because the units position is a `9` and not a `Z`. An
  //    all-`Z` picture blanks the whole field; this one cannot, and that difference is the reason
  //    `suppressedIntegerPositions` exists.
  ['0.00', '     0.00'],
  // A single significant digit, with the blank sign position ahead of the suppressed run.
  ['5.00', '     5.00'],
  // ⚠️ A positive amount leads with a BLANK, not a `+`, because the picture's sign character is a
  //    single `-`. This is the row that would break if the sign position were treated as universal.
  ['1234.56', '  1234.56'],
  // The same magnitude negative: only the sign position differs, so the width is unchanged.
  ['-1234.56', '- 1234.56'],
  // The widest value the picture holds: five integer digits, nothing suppressed, sign still blank.
  ['99999.99', ' 99999.99'],
  // Negative at that same width, which is the only row where every position prints a character.
  ['-99999.99', '-99999.99'],
  // A negative under one unit: the sign prints, the suppressed run blanks, the units digit stays.
  ['-0.05', '-    0.05'],
];

/**
 * The authorization picture renders every value to the exact string the program's edit field produces.
 *
 * Purpose: this is the picture V27's authorization instance is about. The summary screen has four
 * narrow amounts and `money.ts` had no nine-character picture at all, so they were rendered through a
 * twelve-character one — three characters wider than the terminal, in a column beside amounts that used
 * the narrow field.
 *
 * Assumptions: the whole table is asserted rather than a representative row, because the two members
 * this picture is the first to declare are exercised by different rows — the sign position by the
 * positive rows and the mandatory units digit by the zero row — and a single row would test one of them.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersTheAuthorizationPictureExactly(): void {
  for (const [wire, expected] of AUTHORIZATION_MASKED) {
    expect(applyMoneyEditMask(wire, MONEY_PICTURES.authorizationSummaryAmount)).toBe(expected);
    expect(applyMoneyEditMask(wire, MONEY_PICTURES.authorizationSummaryAmount)).toHaveLength(
      MONEY_PICTURES.authorizationSummaryAmount.width,
    );
  }
}

/**
 * The authorization picture's declaration matches the COBOL line it cites.
 *
 * Assumptions: asserted field by field against the numbers readable off `-zzzz9.99` itself, so the
 * declaration cannot drift from the source it names. Five integer positions, two decimals, no comma, a
 * suppressed run of four, a `-` sign position, and a total of nine — which is also the length of the
 * picture string, since every character of a COBOL picture is one printed position.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function declaresTheAuthorizationPictureAsMeasured(): void {
  const picture = MONEY_PICTURES.authorizationSummaryAmount;
  expect(picture.picture).toBe('-zzzz9.99');
  expect(picture.integerPositions).toBe(5);
  expect(picture.decimalPositions).toBe(MONEY_MASK_DECIMAL_POSITIONS);
  expect(picture.grouped).toBe(false);
  expect(picture.zeroFilled).toBe(false);
  expect(picture.suppressedIntegerPositions).toBe(4);
  expect(picture.signsNonNegative).toBe(false);
  expect(picture.width).toBe(9);
  expect(picture.picture).toHaveLength(picture.width);
  expect(picture.source).toContain('COPAUS0C.cbl L57');
}

/**
 * Adding the authorization picture changed no other picture's rendering.
 *
 * Purpose: ⚠️ the two members the new picture declares are read on EVERY path through the mask, so this
 * is the case that proves the three pictures that omit them are untouched. A browser run pinned the
 * account rendering to the character — `"+      5,000.00"` and `"+        250.00"` at an identical
 * measured width — so those two exact strings are asserted here rather than paraphrased.
 *
 * Assumptions: the whole {@link MASKED} table is re-asserted through the explicit account picture as
 * well, so the proof covers every branch of the suppressed path and not only the two measured values.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function leavesEveryOtherPictureUnchanged(): void {
  expect(applyMoneyEditMask('5000.00', MONEY_PICTURES.accountGrouped)).toBe('+      5,000.00');
  expect(applyMoneyEditMask('250.00', MONEY_PICTURES.accountGrouped)).toBe('+        250.00');
  for (const [wire, expected] of MASKED) {
    expect(applyMoneyEditMask(wire, MONEY_PICTURES.accountGrouped)).toBe(expected);
  }
  expect(applyMoneyEditMask('1234.56', MONEY_PICTURES.billPayBalance)).toBe('+0000001234.56');
  expect(applyMoneyEditMask('1234.56', MONEY_PICTURES.transactionAmount)).toBe('+00001234.56');
}

/**
 * The authorization picture renders a magnitude a double cannot hold, without arithmetic.
 *
 * Assumptions: the value is beyond the exact range of an IEEE-754 double, so rendering it intact is
 * only possible from the characters. It also exercises the no-truncation trade-off on this picture:
 * every digit is kept and the rendering widens past nine, rather than the high-order digits being lost.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersTheAuthorizationPictureWithoutADouble(): void {
  expect(applyMoneyEditMask('9007199254740993.99', MONEY_PICTURES.authorizationSummaryAmount)).toBe(
    ' 9007199254740993.99',
  );
  expect(
    stripMoneyEditMask(
      applyMoneyEditMask('9007199254740993.99', MONEY_PICTURES.authorizationSummaryAmount),
    ),
  ).toBe('9007199254740993.99');
}

/**
 * Registers the multi-picture cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function moneyPictureCases(): void {
  it('renders each declared picture exactly', rendersEachPictureExactly);
  it('defaults to the account picture', defaultsToTheAccountPicture);
  it('renders each picture at its declared width', rendersEachPictureAtItsDeclaredWidth);
  it('keeps every digit beyond each picture width', keepsEveryDigitBeyondEachPictureWidth);
  it('reverses every picture back to the wire form', reversesEveryPicture);
  it('cites the baseline for each picture', citesTheBaselineForEachPicture);
  it('renders the authorization picture exactly', rendersTheAuthorizationPictureExactly);
  it('declares the authorization picture as measured', declaresTheAuthorizationPictureAsMeasured);
  it('leaves every other picture unchanged', leavesEveryOtherPictureUnchanged);
  it(
    'renders the authorization picture without a double',
    rendersTheAuthorizationPictureWithoutADouble,
  );
}

describe('MONEY_PICTURES', moneyPictureCases);

/**
 * The three sign cases are classified from characters, including beyond a double's exact range.
 *
 * Assumptions: the large-magnitude case is the behavioural proof that no numeric comparison happens.
 * A value of `9007199254740993.99` cannot survive `Number` intact, so classifying it correctly is
 * only possible from the digits.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function classifiesEverySign(): void {
  expect(classifyMoneySign('1234.56')).toBe('positive');
  expect(classifyMoneySign('0.01')).toBe('positive');
  expect(classifyMoneySign('-987.65')).toBe('negative');
  expect(classifyMoneySign('-0.01')).toBe('negative');
  expect(classifyMoneySign('0.00')).toBe('zero');
  expect(classifyMoneySign('9007199254740993.99')).toBe('positive');
  expect(classifyMoneySign('-9007199254740993.99')).toBe('negative');
}

/**
 * A signed zero and an unreadable value both classify without asserting a meaning they lack.
 *
 * Assumptions: `-0.00` classifies as zero rather than negative, because the magnitude is what makes
 * a value adverse and the reference publishes a scale-two zero as `0.00` in any case. A value
 * outside the wire contract classifies as positive, which is the ordinary rendering, so an
 * unformattable amount is never painted as though it were a debit.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function classifiesEdgeCasesWithoutAssertingMeaning(): void {
  expect(classifyMoneySign('-0.00')).toBe('zero');
  expect(classifyMoneySign('')).toBe('positive');
  expect(classifyMoneySign('abc')).toBe('positive');
  expect(classifyMoneySign('1234')).toBe('positive');
}

/**
 * Each sign case resolves to a distinct design token.
 *
 * Assumptions: distinctness is the requirement, and the actual token VALUES are asserted for
 * contrast and mutual difference in `ui/src/theme/textContrast.test.ts`. This case owns only the
 * mapping — that the three cases do not collapse into one token here, which would leave the
 * distinguishability promise unmet with the theme still correct.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function mapsEachSignToADistinctToken(): void {
  const tokens = [
    moneySignTextToken('positive'),
    moneySignTextToken('negative'),
    moneySignTextToken('zero'),
  ];
  expect(new Set(tokens).size).toBe(tokens.length);
  for (const [sign, token] of Object.entries(MONEY_SIGN_TEXT_TOKENS)) {
    expect(moneySignTextToken(sign as 'positive' | 'negative' | 'zero')).toBe(token);
  }
}

/**
 * One call renders the text, the sign, the token, the picture and the whitespace mode together.
 *
 * Assumptions: the whitespace mode is asserted because it is the half of the rendering a screen is
 * most likely to drop — the suppression blanks that align a column are collapsed by HTML unless it
 * is applied, so a correct string can still be painted wrongly.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersTextSignAndTokenTogether(): void {
  const credit = renderMoney('1234.56');
  expect(credit.text).toBe('+      1,234.56');
  expect(credit.sign).toBe('positive');
  expect(credit.colorToken).toBe(MONEY_SIGN_TEXT_TOKENS.positive);
  expect(credit.picture).toBe(MONEY_PICTURES.accountGrouped);
  expect(credit.whiteSpace).toBe('pre');

  const debit = renderMoney('-987.65', MONEY_PICTURES.transactionAmount);
  expect(debit.text).toBe('-00000987.65');
  expect(debit.sign).toBe('negative');
  expect(debit.colorToken).toBe(MONEY_SIGN_TEXT_TOKENS.negative);
  expect(debit.picture).toBe(MONEY_PICTURES.transactionAmount);

  const settled = renderMoney('0.00', MONEY_PICTURES.transactionAmount);
  expect(settled.text).toBe('+00000000.00');
  expect(settled.sign).toBe('zero');
  expect(settled.colorToken).toBe(MONEY_SIGN_TEXT_TOKENS.zero);
}

/**
 * The three values an audit measured painting identically now differ by token.
 *
 * Assumptions: this is the finding restated as an assertion. `+00001234.56`, `-00000987.65` and
 * `+00000000.00` were measured rendering in one colour with no weight or class difference between
 * them; the entry point must now return three different tokens for them while leaving the text
 * exactly as the picture specifies.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function distinguishesTheThreeMeasuredRenderings(): void {
  const rendered = ['1234.56', '-987.65', '0.00'].map(
    /**
     * Renders one measured amount through the transaction picture.
     * @param {string} wire - The wire amount.
     * @returns {ReturnType<typeof renderMoney>} The rendering description.
     */
    (wire: string) => renderMoney(wire, MONEY_PICTURES.transactionAmount),
  );

  expect(
    rendered.map(
      /**
       * Reads one rendering's masked text.
       * @param {(typeof rendered)[number]} entry - One rendered money value.
       * @returns {string} The masked text that rendering produced.
       */
      function maskedTextOf(entry: (typeof rendered)[number]): string {
        return entry.text;
      },
    ),
  ).toStrictEqual(['+00001234.56', '-00000987.65', '+00000000.00']);
  expect(
    new Set(
      rendered.map(
        /**
         * Reads one rendering's semantic sign token.
         * @param {(typeof rendered)[number]} entry - One rendered money value.
         * @returns {string} The token name that rendering resolved its colour to.
         */
        function signTokenOf(entry: (typeof rendered)[number]): string {
          return entry.colorToken;
        },
      ),
    ).size,
  ).toBe(3);
}

/**
 * No part of the rendering path routes a value through a numeric conversion.
 *
 * Assumptions: asserted behaviourally across the whole entry point, not only the mask — a magnitude
 * beyond a double's exact range survives digit for digit AND classifies correctly, which a
 * parse-and-compare implementation could not achieve.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rendersWithoutADouble(): void {
  const rendered = renderMoney('-9007199254740993.99', MONEY_PICTURES.transactionAmount);
  expect(rendered.text).toBe('-9007199254740993.99');
  expect(rendered.sign).toBe('negative');
}

/**
 * Registers the sign and entry-point cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function moneySignCases(): void {
  it('classifies every sign from characters', classifiesEverySign);
  it('classifies edge cases without asserting meaning', classifiesEdgeCasesWithoutAssertingMeaning);
  it('maps each sign to a distinct token', mapsEachSignToADistinctToken);
  it('renders text, sign and token together', rendersTextSignAndTokenTogether);
  it('distinguishes the three measured renderings', distinguishesTheThreeMeasuredRenderings);
  it('renders without a double', rendersWithoutADouble);
}

describe('renderMoney', moneySignCases);
