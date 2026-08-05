// Assumptions: every test API is imported rather than taken from an ambient
// global, because ui/vitest.config.ts sets `globals: false` and records that as a
// contract: ambient test globals are declared per PROJECT, so admitting them here
// would make `expect` and `vi` visible to production screens as well, where a
// stray call would compile.
import { describe, expect, it } from "vitest";

import {
  cardDetailPath,
  cardEditPath,
  isOpaqueCardId,
  requireOpaqueCardId,
} from "./cards";

const OPAQUE_CARD_ID = "AbCdEfGhIjKlMnOpQrStUv";

const PAN = "4111111111111111";

// Refactoring Rationale: every case below is a NAMED function passed to `it`,
// rather than an inline arrow. Two constraints meet here and only this shape
// satisfies both: ui/eslint.config.js selects a function expression in every
// position, so an inline callback needs its own JSDoc block, and Prettier moves a
// block comment that follows an argument comma onto the preceding string literal,
// which detaches the block from the function it documents. A named declaration
// carries its documentation unambiguously and is stable under both tools.

/**
 * Invokes the route guard so an assertion can inspect what it throws.
 * @param {string} value - The candidate route segment to validate.
 * @returns {() => string} A thunk that runs the guard and returns its accepted value.
 */
function guarding(value: string): () => string {
  return (
    /**
     * Runs the guard against the captured candidate.
     * @returns {string} The accepted opaque identifier.
     */
    () => requireOpaqueCardId(value)
  );
}

/** Asserts the guard admits the token shape and refuses a PAN-shaped value. */
function acceptsOnlyTheServerTokenShape(): void {
  expect(isOpaqueCardId(OPAQUE_CARD_ID)).toBe(true);
  expect(isOpaqueCardId(PAN)).toBe(false);
  expect(isOpaqueCardId(`${OPAQUE_CARD_ID}x`)).toBe(false);
}

/** Asserts both path builders emit the opaque token and never a PAN. */
function buildsRoutesWithoutAPan(): void {
  expect(cardDetailPath(OPAQUE_CARD_ID)).toBe(`/cards/${OPAQUE_CARD_ID}`);
  expect(cardEditPath(OPAQUE_CARD_ID)).toBe(`/cards/${OPAQUE_CARD_ID}/edit`);
  expect(cardDetailPath(OPAQUE_CARD_ID)).not.toContain(PAN);
}

/** Asserts the guard reports only the rejected value's length, never the value. */
function rejectsPanAndRedactsIt(): void {
  expect(guarding(PAN)).toThrow("received a value of length 16");
  expect(guarding(PAN)).not.toThrow(PAN);
}

/** Groups the assertions that fix the opaque-token card route contract. */
function opaqueCardRouteContract(): void {
  it("accepts only the server token shape", acceptsOnlyTheServerTokenShape);
  it("builds detail and edit routes without a PAN", buildsRoutesWithoutAPan);
  it(
    "rejects PAN and redacts the rejected value from diagnostics",
    rejectsPanAndRedactsIt,
  );
}

describe("opaque card route contract", opaqueCardRouteContract);
