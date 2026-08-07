// Assumptions: every test API is imported rather than taken from an ambient
// global, because ui/vitest.config.ts sets `globals: false` and records that as a
// contract: ambient test globals are declared per PROJECT, so admitting them here
// would make `expect` and `vi` visible to production screens as well, where a
// stray call would compile.
import { describe, expect, it } from 'vitest';

import {
  CARD_SELECTOR_LENGTH,
  cardDetailPath,
  cardEditPath,
  isCardNumber,
  isCardSelector,
  requireCardNumber,
  requireCardSelector,
} from './cards';

const CARD_NUMBER = '4111111111111111';

const MASKED_RENDERING = '************1111';

// Assumptions: this literal has the published length and alphabet and seals nothing, so it exercises
// the guard's shape check while addressing no real card.
const SELECTOR = 'fake-selector-example-not-a-real-sealed-value-0000000000000';

// Refactoring Rationale: every case below is a NAMED function passed to `it`,
// rather than an inline arrow. Two constraints meet here and only this shape
// satisfies both: ui/eslint.config.js selects a function expression in every
// position, so an inline callback needs its own JSDoc block, and Prettier moves a
// block comment that follows an argument comma onto the preceding string literal,
// which detaches the block from the function it documents. A named declaration
// carries its documentation unambiguously and is stable under both tools.

// Refactoring Rationale: these cases have been through two polarities and this is
// the third and final one, so the history is stated once rather than being
// rediscovered. The first asserted that a route carries an opaque token; the second
// inverted that to assert a route carries the card NUMBER, because the contract of
// record then addressed a card by its number; this one asserts a route carries an
// opaque SELECTOR and REFUSES both a number and a masked rendering. The middle
// position was the defect: a card number in a route reaches the browser's own
// history, the referrer a browser sends onward and every intermediary's access log,
// none of which the service's own redaction can touch. The redaction property both
// earlier revisions guarded is kept and still asserted: a rejection reports a length
// and never the rejected value.

/**
 * Invokes the selector guard so an assertion can inspect what it throws.
 * @param {string} value - The candidate route segment to validate.
 * @returns {() => string} A thunk that runs the guard and returns its accepted value.
 */
function guardingSelector(value: string): () => string {
  return (
    /**
     * Runs the guard against the captured candidate.
     * @returns {string} The accepted selector.
     */
    () => requireCardSelector(value)
  );
}

/**
 * Invokes the card-number guard so an assertion can inspect what it throws.
 * @param {string} value - The candidate entry to validate.
 * @returns {() => string} A thunk that runs the guard and returns its accepted value.
 */
function guardingNumber(value: string): () => string {
  return (
    /**
     * Runs the guard against the captured candidate.
     * @returns {string} The accepted card number.
     */
    () => requireCardNumber(value)
  );
}

/** Asserts the selector test admits a well-formed selector and refuses everything else. */
function acceptsOnlyAWellFormedSelector(): void {
  expect(SELECTOR).toHaveLength(CARD_SELECTOR_LENGTH);
  expect(isCardSelector(SELECTOR)).toBe(true);
  expect(isCardSelector(`${SELECTOR}A`)).toBe(false);
  expect(isCardSelector(SELECTOR.slice(0, CARD_SELECTOR_LENGTH - 1))).toBe(false);
  expect(isCardSelector(`${SELECTOR.slice(0, CARD_SELECTOR_LENGTH - 1)}+`)).toBe(false);
}

/**
 * Asserts a card number is not a selector, which the length and not the alphabet is what decides.
 *
 * Assumptions: a run of digits IS valid URL-safe base64, so an alphabet check alone would report a
 * card number as a selector. That is the one confusion these two predicates exist to keep apart, so
 * it is asserted directly rather than inferred from the pattern.
 */
function aCardNumberIsNotASelector(): void {
  expect(isCardSelector(CARD_NUMBER)).toBe(false);
  expect(isCardSelector(MASKED_RENDERING)).toBe(false);
  expect(isCardNumber(SELECTOR)).toBe(false);
}

/** Asserts both path builders emit the selector they were given. */
function buildsRoutesFromTheSelector(): void {
  expect(cardDetailPath(SELECTOR)).toBe(`/cards/${SELECTOR}`);
  expect(cardEditPath(SELECTOR)).toBe(`/cards/${SELECTOR}/edit`);
}

/**
 * Asserts a card number cannot become a route segment, which is the whole point of the selector.
 */
function refusesToBuildARouteFromACardNumber(): void {
  expect(guardingSelector(CARD_NUMBER)).toThrow('received a value of length 16');
  expect(guardingSelector(MASKED_RENDERING)).toThrow('received a value of length 16');
}

/** Asserts a rejected value is redacted from the diagnostic the guard raises. */
function redactsEveryRejectedValue(): void {
  expect(guardingSelector(CARD_NUMBER)).not.toThrow(CARD_NUMBER);
  expect(guardingSelector(MASKED_RENDERING)).not.toThrow(MASKED_RENDERING);
  expect(guardingNumber(MASKED_RENDERING)).not.toThrow(MASKED_RENDERING);
}

/** Asserts the number guard still admits an entered number, which the lookup operation needs. */
function stillAcceptsAnEnteredNumberForLookup(): void {
  expect(isCardNumber(CARD_NUMBER)).toBe(true);
  expect(isCardNumber(MASKED_RENDERING)).toBe(false);
  expect(isCardNumber(`${CARD_NUMBER}0`)).toBe(false);
  expect(requireCardNumber(CARD_NUMBER)).toBe(CARD_NUMBER);
}

/** Groups the assertions that fix the card route contract. */
function cardRouteContract(): void {
  it('accepts only a well-formed selector', acceptsOnlyAWellFormedSelector);
  it('does not confuse a card number with a selector', aCardNumberIsNotASelector);
  it('builds detail and edit routes from the selector', buildsRoutesFromTheSelector);
  it('refuses to build a route from a card number', refusesToBuildARouteFromACardNumber);
  it('redacts every rejected value from its diagnostic', redactsEveryRejectedValue);
  it('still accepts an entered number for the lookup', stillAcceptsAnEnteredNumberForLookup);
}

describe('card route contract', cardRouteContract);
