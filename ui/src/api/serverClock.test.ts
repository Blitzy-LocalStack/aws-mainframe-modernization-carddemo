/**
 * Pins the server-anchored clock that supplies the screen header band its paint instant.
 *
 * WHY these cases exist — the divergence being closed is a WRONG DATE, not a crash: before this
 * clock existed, every production call site omitted `ScreenHeader`'s `now` prop and the band read the
 * browser's clock, so a client machine skewed across midnight showed a different date from the one
 * the service would have shown. A test that only proved "some Date comes back" would pass against a
 * module that returned the browser clock, which is exactly the defect. So the cases below pin the two
 * properties that distinguish an anchored clock from a local one: the reported instant is derived
 * from the SERVER's value, and it advances by LOCAL ELAPSED time rather than tracking the local
 * absolute clock.
 *
 * WHY : Assumptions: `performance.now()` is stubbed rather than waited on. Waiting for real elapsed
 * time would make each case slow and, worse, flaky at the millisecond assertions; stubbing the
 * monotonic source is what lets the offset arithmetic be asserted exactly.
 *
 * WHY : Assumptions: every case is a NAMED function passed to `it`, matching the convention the
 * sibling suites use, because `eslint.config.js` requires a JSDoc block on every function including
 * the ones handed to a test registrar.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

import { recordServerDate, resetServerClock, serverInstant } from './serverClock';

/** An arbitrary but fixed server instant, in the IMF-fixdate form an HTTP `Date` header uses. */
const SERVER_DATE_HEADER = 'Tue, 15 Jul 2025 14:23:45 GMT';

/** The same instant in milliseconds since the epoch, for exact arithmetic in the assertions. */
const SERVER_EPOCH_MS = Date.parse(SERVER_DATE_HEADER);

/** Values that must never anchor the clock, paired with the case name each is exercised under. */
const UNUSABLE_HEADERS: ReadonlyArray<readonly [string, string | null | undefined]> = [
  ['an absent header', undefined],
  ['a null header', null],
  ['an empty header', ''],
  ['a blank header', '   '],
  ['an unparseable header', 'not-a-date'],
];

/**
 * Restores the module anchor and every stub after each case.
 * @returns {void} Nothing; the reset happens as a side effect.
 */
function restoreClockState(): void {
  // Assumptions: both the anchor and the stubs are torn down. The anchor is module-level state, so a
  //   case asserting the unanchored fallback would otherwise pass or fail depending on order.
  resetServerClock();
  vi.restoreAllMocks();
}

/**
 * Asserts no instant is reported before any response has been observed.
 * @returns {void} Nothing; assertions raise on failure.
 */
function reportsNothingBeforeAnyResponse(): void {
  expect(serverInstant()).toBeUndefined();
}

/**
 * Asserts the anchored value is reported unchanged when no local time has elapsed.
 * @returns {void} Nothing; assertions raise on failure.
 */
function reportsTheServerInstantAtTheAnchor(): void {
  vi.spyOn(performance, 'now').mockReturnValue(1_000);
  expect(recordServerDate(SERVER_DATE_HEADER)).toBe(true);
  expect(serverInstant()?.getTime()).toBe(SERVER_EPOCH_MS);
}

/**
 * Asserts the reported instant advances by local elapsed time rather than by the local clock.
 * @returns {void} Nothing; assertions raise on failure.
 */
function advancesByLocalElapsedTime(): void {
  // Assumptions: this is the case that distinguishes an anchored clock from the browser's. The
  //   monotonic source moves forward by five seconds while the wall clock is never consulted, so a
  //   result five seconds past the SERVER value proves the offset is being applied.
  const monotonic = vi.spyOn(performance, 'now');
  monotonic.mockReturnValue(1_000);
  recordServerDate(SERVER_DATE_HEADER);
  monotonic.mockReturnValue(6_000);
  expect(serverInstant()?.getTime()).toBe(SERVER_EPOCH_MS + 5_000);
}

/**
 * Asserts a browser clock skewed by a full day does not move the reported instant.
 * @returns {void} Nothing; assertions raise on failure.
 */
function ignoresASkewedBrowserClock(): void {
  // WHY : this is the defect stated as a test. The browser clock is moved a full day ahead -- the
  //       midnight-boundary case the header band's own contract calls out -- and the reported instant
  //       must not move with it.
  const monotonic = vi.spyOn(performance, 'now');
  monotonic.mockReturnValue(0);
  recordServerDate(SERVER_DATE_HEADER);
  const skewed = SERVER_EPOCH_MS + 24 * 60 * 60 * 1_000;
  vi.spyOn(Date, 'now').mockReturnValue(skewed);
  const instant = serverInstant();
  expect(instant?.getTime()).toBe(SERVER_EPOCH_MS);
  expect(instant?.getTime()).not.toBe(skewed);
}

/**
 * Asserts a newer observation replaces an older anchor outright.
 * @returns {void} Nothing; assertions raise on failure.
 */
function adoptsTheNewestAnchor(): void {
  const monotonic = vi.spyOn(performance, 'now');
  monotonic.mockReturnValue(0);
  recordServerDate(SERVER_DATE_HEADER);
  const later = 'Tue, 15 Jul 2025 15:00:00 GMT';
  monotonic.mockReturnValue(10_000);
  expect(recordServerDate(later)).toBe(true);
  // Assumptions: the newest anchor wins outright rather than being averaged with the previous one.
  //   An averaged value would depend on how many requests a session had made, which is not a
  //   property a displayed clock should have.
  expect(serverInstant()?.getTime()).toBe(Date.parse(later));
}

/**
 * Asserts every unusable header value is refused and anchors nothing.
 * @returns {void} Nothing; assertions raise on failure.
 */
function refusesEveryUnusableHeader(): void {
  for (const [label, value] of UNUSABLE_HEADERS) {
    resetServerClock();
    expect(recordServerDate(value), `${label} must not anchor the clock`).toBe(false);
    expect(serverInstant(), `${label} must leave the clock unanchored`).toBeUndefined();
  }
}

/**
 * Asserts an unusable later value leaves a previously good anchor in place.
 * @returns {void} Nothing; assertions raise on failure.
 */
function preservesAGoodAnchor(): void {
  // Assumptions: a later response with a missing or malformed header must not DISCARD a good anchor.
  //   Clearing it would send the band back to the browser clock partway through a session, which is
  //   a harder fault to notice than never having anchored at all.
  vi.spyOn(performance, 'now').mockReturnValue(0);
  recordServerDate(SERVER_DATE_HEADER);
  expect(recordServerDate('nonsense')).toBe(false);
  expect(serverInstant()?.getTime()).toBe(SERVER_EPOCH_MS);
}

/**
 * Registers the reporting cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function serverInstantCases(): void {
  afterEach(restoreClockState);
  it('returns undefined before any response has been observed', reportsNothingBeforeAnyResponse);
  it('reports the server instant at the anchor', reportsTheServerInstantAtTheAnchor);
  it('advances by local elapsed time, not by the local clock', advancesByLocalElapsedTime);
  it('is unaffected by a skewed browser clock', ignoresASkewedBrowserClock);
  it('replaces an older anchor with a newer observation', adoptsTheNewestAnchor);
}

/**
 * Registers the anchoring cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function recordServerDateCases(): void {
  afterEach(restoreClockState);
  it('refuses an absent, blank or unparseable header', refusesEveryUnusableHeader);
  it('leaves an existing anchor in place when given an unusable value', preservesAGoodAnchor);
}

describe('serverInstant', serverInstantCases);
describe('recordServerDate', recordServerDateCases);
