/**
 * @file Unit tests for the transaction-detail screen's card-number presentation in
 * `ui/src/screens/transactionDetail/index.tsx`.
 *
 * Purpose
 * -------
 * Pin the one property of that screen a rendering cannot usefully show: which card-number values reach
 * the glass. `presentMaskedCardNumber` is the last gate between a response and the DOM, and the values
 * worth asserting about it are the ones the transport is supposed to have already refused -- so a case
 * that drove the real screen could only ever exercise the value the transport lets through.
 *
 * ⚠️ Assumptions: this file exists because the function used to RE-MASK. It carried a local
 * `/^\*+[0-9]{4}$/u`, looser than the contract's twelve-and-four, and on a miss it sliced the last four
 * characters and prefixed a run of asterisks -- holding the unreduced value in the browser to do it.
 * Nothing asserted either half, which is how the divergence between the code and its own docstring
 * survived. The cases below assert the corrected behaviour directly: the contract's rendering passes
 * through unchanged, and everything else is withheld.
 *
 * Assumptions: the accepted value is composed from `MASKED_CARD_NUMBER` itself rather than typed out,
 * so a change to the shared contract moves the expectation with it instead of leaving a literal here
 * agreeing with a pattern that has moved on.
 *
 * Assumptions: every card number below is fabricated. The four visible digits are sequential and the
 * sixteen-digit value is a counting sequence, so no case carries a value resembling a real account.
 */

import { describe, expect, it } from 'vitest';

import { MASKED_CARD_NUMBER } from '../../api/masking';

import { presentMaskedCardNumber } from './index';

/** Asterisks the contract's masked rendering conceals the leading positions behind. */
const CONCEALED_POSITIONS = 12;

/** The four digits the contract's masked rendering leaves visible. */
const VISIBLE_DIGITS = '1234';

/** A card number in exactly the shape `ui/src/api/masking.ts` publishes. */
const CONTRACT_RENDERING = `${'*'.repeat(CONCEALED_POSITIONS)}${VISIBLE_DIGITS}`;

/**
 * The contract's own rendering is passed through byte for byte.
 *
 * Assumptions: the composed value is checked against the shared pattern first, so a case that passed
 * because the pattern had been relaxed would fail on that line rather than silently prove nothing.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function passesTheContractRenderingThrough(): void {
  expect(MASKED_CARD_NUMBER.test(CONTRACT_RENDERING)).toBe(true);
  expect(presentMaskedCardNumber(CONTRACT_RENDERING)).toBe(CONTRACT_RENDERING);
}

/**
 * A whole account number is withheld rather than reduced in the browser.
 *
 * ⚠️ Assumptions: this is the case the previous implementation failed. It answered
 * `************3456` for this input -- a mask composed HERE, from a value this module had already been
 * handed -- which `ui/src/api/masking.ts` forbids on the ground that a client-side mask invites a
 * caller to hold the unmasked value first. The assertion is that no part of the input survives, which
 * is stronger than asserting the empty string alone: it would fail for a reduction as well as for a
 * pass-through.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function withholdsAnUnreducedNumber(): void {
  const unreduced = '1234567890123456';

  expect(MASKED_CARD_NUMBER.test(unreduced)).toBe(false);
  expect(presentMaskedCardNumber(unreduced)).toBe('');
  expect(presentMaskedCardNumber(unreduced)).not.toContain('3456');
}

/**
 * A partial mask the contract does not admit is withheld, not repaired.
 *
 * Assumptions: eleven asterisks is the near-miss the shared module names as the drift it exists to
 * prevent, and the previous local pattern accepted it because its leading run was `+` rather than a
 * fixed twelve. Asserting it here is what keeps this screen agreeing with its four sibling clients
 * about a value they all refuse.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function withholdsAPartialMask(): void {
  const elevenAsterisks = `${'*'.repeat(CONCEALED_POSITIONS - 1)}${VISIBLE_DIGITS}`;

  expect(MASKED_CARD_NUMBER.test(elevenAsterisks)).toBe(false);
  expect(presentMaskedCardNumber(elevenAsterisks)).toBe('');
}

/**
 * A masked number carrying anything either side of it is withheld.
 *
 * Assumptions: the two anchors of the shared pattern are what refuse these, and this case is what
 * proves the screen inherits them. An unanchored pattern would admit both -- the first is a value that
 * merely CONTAINS the rendering, and the second is sixteen digits behind a masked prefix, which is the
 * disclosure the trailing anchor exists to stop.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function withholdsAnEmbeddedRendering(): void {
  expect(presentMaskedCardNumber(`card ${CONTRACT_RENDERING}`)).toBe('');
  expect(presentMaskedCardNumber(`${CONTRACT_RENDERING}7890123456`)).toBe('');
}

/**
 * The cleared record's blank renders blank, which is what the mapset paints.
 *
 * Assumptions: the blank takes the same withholding branch as an unrecognised value and needs no
 * special case, which is what `BLANK_TRANSACTION_RECORD`'s own note in the screen asserts.
 * `app/cbl/COTRN01C.cbl` L309-L326 moves `SPACES` into `CARDNUMI` on PF4 and on every failed lookup,
 * so a blank value standing beside its own label is the reference's cleared state rather than a gap.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function rendersTheClearedRecordBlank(): void {
  expect(presentMaskedCardNumber('')).toBe('');
}

/**
 * Registers the card-number presentation cases.
 *
 * Assumptions: every case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback, matching the sibling suites. `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with
 * `publicOnly: false`, so it selects a function expression in every position, and a JSDoc block written
 * above an inline callback is moved by Prettier onto the preceding string literal, which detaches it
 * from the function it documents.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function cardNumberPresentationCases(): void {
  it('passes the contract rendering through unchanged', passesTheContractRenderingThrough);
  it('withholds a whole account number instead of reducing it', withholdsAnUnreducedNumber);
  it('withholds a partial mask the contract does not admit', withholdsAPartialMask);
  it('withholds a rendering carrying anything either side of it', withholdsAnEmbeddedRendering);
  it('renders the cleared record blank', rendersTheClearedRecordBlank);
}

describe('transaction detail card-number presentation', cardNumberPresentationCases);
