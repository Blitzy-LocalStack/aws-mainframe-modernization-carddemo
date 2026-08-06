// Assumptions: every test API is imported rather than taken from an ambient
// global, because ui/vitest.config.ts sets `globals: false` and records that as a
// contract: ambient test globals are declared per PROJECT, so admitting them here
// would make `expect` and `vi` visible to production screens as well, where a
// stray call would compile.
import { describe, expect, it } from 'vitest';

import { cardDetailPath, cardEditPath, isCardNumber, requireCardNumber } from './cards';

const CARD_NUMBER = '4111111111111111';

const MASKED_RENDERING = '************1111';

// Refactoring Rationale: every case below is a NAMED function passed to `it`,
// rather than an inline arrow. Two constraints meet here and only this shape
// satisfies both: ui/eslint.config.js selects a function expression in every
// position, so an inline callback needs its own JSDoc block, and Prettier moves a
// block comment that follows an argument comma onto the preceding string literal,
// which detaches the block from the function it documents. A named declaration
// carries its documentation unambiguously and is stable under both tools.

// Refactoring Rationale: these cases once asserted that a route carries an opaque
// 22-character token and REFUSES a card number. The contract of record addresses a
// card by its number, so the polarity is inverted: the number is what a route must
// accept, and what must be refused is a MASKED rendering -- the value a caller is
// most likely to pass by mistake, because it is the only card-number-shaped value
// any response of that contract hands out. The redaction property that the old
// cases guarded is kept and still asserted: a rejection reports a length and never
// the rejected value.

/**
 * Invokes the route guard so an assertion can inspect what it throws.
 * @param {string} value - The candidate route segment to validate.
 * @returns {() => string} A thunk that runs the guard and returns its accepted value.
 */
function guarding(value: string): () => string {
  return (
    /**
     * Runs the guard against the captured candidate.
     * @returns {string} The accepted card number.
     */
    () => requireCardNumber(value)
  );
}

/** Asserts the guard admits a sixteen-digit number and refuses anything else. */
function acceptsOnlySixteenDigits(): void {
  expect(isCardNumber(CARD_NUMBER)).toBe(true);
  expect(isCardNumber(MASKED_RENDERING)).toBe(false);
  expect(isCardNumber(`${CARD_NUMBER}0`)).toBe(false);
  expect(isCardNumber(CARD_NUMBER.slice(0, 15))).toBe(false);
}

/** Asserts both path builders emit the number they were given. */
function buildsRoutesFromTheNumber(): void {
  expect(cardDetailPath(CARD_NUMBER)).toBe(`/cards/${CARD_NUMBER}`);
  expect(cardEditPath(CARD_NUMBER)).toBe(`/cards/${CARD_NUMBER}/edit`);
}

/** Asserts a masked rendering cannot become a route and is redacted from diagnostics. */
function rejectsAMaskedRenderingAndRedactsIt(): void {
  expect(guarding(MASKED_RENDERING)).toThrow('received a value of length 16');
  expect(guarding(MASKED_RENDERING)).not.toThrow(MASKED_RENDERING);
}

/** Groups the assertions that fix the card-number route contract. */
function cardNumberRouteContract(): void {
  it('accepts only a sixteen-digit card number', acceptsOnlySixteenDigits);
  it('builds detail and edit routes from the number', buildsRoutesFromTheNumber);
  it(
    'rejects a masked rendering and redacts the rejected value from diagnostics',
    rejectsAMaskedRenderingAndRedactsIt,
  );
}

describe('card number route contract', cardNumberRouteContract);
