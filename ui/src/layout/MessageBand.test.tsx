/**
 * Proves the message band is sized to the DISPLAY width of the mapset it stands in,
 * not to the 75-character work area.
 *
 * Purpose
 * -------
 * The baseline message contract has two halves. `CCARD-ERROR-MSG` and
 * `CCARD-RETURN-MSG` (`app/cpy/CVCRD01Y.cpy` L28-L29) are `PIC X(75)`, which bounds
 * what a message crossing the shared work area may CARRY; the `ERRMSGI`/`ERRMSGO`
 * display field is what a message is RENDERED in, and it is 78 characters on 19 of
 * the 21 mapsets and 80 on `COCRDSL` and `COCRDUP`. These cases assert the second
 * half, because it is the half that decides whether a message is clipped: 14 online
 * programs compose straight into an 80-byte `WS-MESSAGE` buffer and move it to the
 * map without passing through the work area, so a band sized to 75 would clip
 * characters the terminal itself rendered.
 *
 * Refactoring Rationale: the assertion reads the inline `maxInlineSize` off the band
 * element rather than a computed style. jsdom does not resolve `ch` against a font,
 * so a computed width would come back as the literal string or as an empty value
 * depending on the property; the inline declaration is the contract this component
 * actually writes, and it is the one thing a regression would change.
 */

// Assumptions: every test API is imported rather than taken from an ambient global,
// because ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  MESSAGE_BAND_CONTENT_WIDTH,
  MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH,
  MESSAGE_BAND_TEST_ID,
  MessageBand,
} from './MessageBand';

/** A message short enough that no case here depends on truncation behaviour. */
const SHORT_MESSAGE = 'Wrong Password. Try again ...';

/**
 * Returns the character width the rendered band's inline style caps it at.
 * @returns {string} The `max-inline-size` declaration written on the band element.
 */
function bandMaxInlineSize(): string {
  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  return band.style.maxInlineSize;
}

/**
 * The band is sized to the 78-character default when no mapset is named.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function default78(): void {
  render(<MessageBand message={SHORT_MESSAGE} />);

  expect(MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH).toBe(78);
  expect(bandMaxInlineSize()).toBe('78ch');
}

/**
 * A mapset whose display field is 78 characters is sized to 78.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function standard78(): void {
  render(<MessageBand mapset="COTRN00" message={SHORT_MESSAGE} />);

  expect(bandMaxInlineSize()).toBe('78ch');
}

/**
 * `COCRDSL` declares an 80-character display field and is sized to 80.
 *
 * Assumptions: both 80-character mapsets are asserted rather than one standing in for the other.
 * They are an exhaustive two-entry exception with no rule behind it -- COCRDSL and COCRDUP simply
 * declare a wider field -- so a table that lost one entry would still satisfy a single-case
 * assertion.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function cardDetail80(): void {
  render(<MessageBand mapset="COCRDSL" message={SHORT_MESSAGE} />);

  expect(bandMaxInlineSize()).toBe('80ch');
}

/**
 * `COCRDUP` declares an 80-character display field and is sized to 80.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function cardUpdate80(): void {
  render(<MessageBand mapset="COCRDUP" message={SHORT_MESSAGE} />);

  expect(bandMaxInlineSize()).toBe('80ch');
}

/**
 * The band is never sized to the 75-character work area the message travels through.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function notWorkArea(): void {
  render(<MessageBand mapset="COCRDSL" message={SHORT_MESSAGE} />);

  expect(MESSAGE_BAND_CONTENT_WIDTH).toBe(75);
  expect(bandMaxInlineSize()).not.toBe(`${MESSAGE_BAND_CONTENT_WIDTH}ch`);
}

/**
 * The reserved empty band carries the same width as the populated one.
 *
 * Assumptions: the empty band is asserted too, because it is the branch that reserves row 23 and it
 * renders a different element. A width applied only to the populated branch would leave the reserved
 * space a different size from the message that replaces it, which is the layout shift the band exists
 * to prevent.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function emptyBand(): void {
  render(<MessageBand mapset="COCRDUP" />);

  expect(bandMaxInlineSize()).toBe('80ch');
}

/**
 * Groups the display-width cases.
 *
 * Assumptions: every case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback. `jsdoc/require-jsdoc` is configured with `publicOnly: false`, so it selects a function
 * expression in every position -- and a JSDoc block written above an inline callback is moved by
 * Prettier onto the preceding string literal, which detaches it from the function it documents.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function messageBandDisplayWidth(): void {
  it('renders at the 78-character width when no mapset is named', default78);
  it('renders at 78 characters for a standard mapset', standard78);
  it('renders at 80 characters on COCRDSL', cardDetail80);
  it('renders at 80 characters on COCRDUP', cardUpdate80);
  it('never sizes the band to the 75-character work area', notWorkArea);
  it('applies the same width to the reserved empty band', emptyBand);
}

describe('message band display width', messageBandDisplayWidth);
