/**
 * @file Server-anchored clock for the paint-time date and time in the screen header band.
 *
 * WHY this module exists — Refactoring Rationale: `ScreenHeader` accepts a `now` instant and states
 * in its own contract that the composing shell must pass a SERVER-derived one, because the baseline
 * read a single clock in a single zone: `FUNCTION CURRENT-DATE` returns the CICS region's local
 * time, so every operator of a region saw the same wall clock whatever their own machine said
 * (`app/cbl/COSGN00C.cbl:179`, reached from `SEND-SIGNON-SCREEN` at L147). No shell existed and
 * every production call site omitted the prop, so all four fell through to `dayjs()` — the
 * browser's clock. The user-visible consequence is in the exact slot the source sized at eight
 * characters: two operators looking at one record can read two different dates across a midnight
 * boundary. This module supplies the instant those call sites now pass.
 *
 * Alternatives Considered: displaying the last observed server instant verbatim, frozen between
 * responses. Rejected because the header would then show a stale time on any re-render that follows
 * a quiet period — the baseline repainted the value at every `SEND MAP`, so it was always current
 * as of the paint, and a frozen value trades one inaccuracy for another.
 *
 * Alternatives Considered: adding an endpoint that returns the server time, polled on a timer.
 * Rejected on two grounds: it adds a published operation to a service contract for a presentational
 * value, and a ticking clock is itself a behavioural change — `ScreenHeader` documents that the
 * baseline read the clock once per paint and never on an interval.
 *
 * The chosen mechanism is an OFFSET: record a server instant together with a local reading taken at
 * the same moment, then report `server + elapsed`. A constantly skewed client clock cancels out of
 * the elapsed term entirely, which is what removes the divergence; only the small transit time
 * between the server writing its `Date` header and this code reading it remains, and that is
 * bounded by one network round trip and is far below the one-second granularity the header displays.
 *
 * Assumptions: the anchor is the HTTP `Date` header, which every response carries by definition, so
 * no new endpoint, contract change or grant is needed. It is seeded from the runtime-configuration
 * fetch that `ui/src/main.tsx` already awaits BEFORE the first render, which is why the sign-on
 * screen — the one screen that paints before any API call — has a server instant available too, and
 * is refreshed from every subsequent API response.
 *
 * Assumptions: `Date` is a GMT-based instant, so this module closes the CLOCK substitution and not
 * the ZONE substitution. The remaining zone difference is recorded in divergence D-7 of
 * `docs/architecture/cobol-to-service-traceability.md`; stating the boundary here keeps a reader
 * from assuming more than this module delivers.
 */

/**
 * One anchoring observation: a server instant and the local reading taken beside it.
 *
 * Assumptions: both members are captured from the same response, within the same synchronous block,
 * so the elapsed term below measures time since the anchor and nothing else.
 */
interface ClockAnchor {
  /** Server instant in milliseconds since the epoch, parsed from an HTTP `Date` header. */
  readonly serverEpochMs: number;
  /** Monotonic local reading, in milliseconds, taken when the anchor was recorded. */
  readonly localElapsedMs: number;
}

let anchor: ClockAnchor | undefined;

/**
 * Reads the monotonic local elapsed-time source.
 *
 * WHY : Alternatives Considered: `Date.now()`. Rejected because it is the very thing being
 *       corrected for. A CONSTANT skew would cancel out of a difference of two `Date.now()`
 *       readings, so it would mostly work — but a clock STEP during the session, which is exactly
 *       what a machine with a bad clock does when it finally synchronises, would move the reported
 *       instant by the size of the correction. `performance.now()` is monotonic and unaffected by
 *       any adjustment to the wall clock, so the elapsed term stays a true elapsed term.
 * @returns {number} Milliseconds elapsed since an arbitrary fixed origin.
 */
function monotonicNow(): number {
  return performance.now();
}

/**
 * Records a server instant from a response's HTTP `Date` header.
 *
 * Ignores an absent or unparseable value rather than throwing: the header is advisory for this
 * purpose, and a request that succeeded must not be turned into a failure because a presentational
 * clock could not be anchored.
 * @param {string | null | undefined} headerValue - Raw `Date` header value, or `null`/`undefined`
 *   when the response carried none.
 * @returns {boolean} `true` when the value was accepted and the anchor replaced, `false` otherwise.
 */
export function recordServerDate(headerValue: string | null | undefined): boolean {
  if (typeof headerValue !== 'string' || headerValue.trim().length === 0) {
    return false;
  }
  const parsed = Date.parse(headerValue);
  if (Number.isNaN(parsed)) {
    return false;
  }
  // Assumptions: the newest observation replaces the previous one unconditionally rather than being
  //       averaged with it. Averaging would smooth transit jitter, but it would also make the
  //       reported instant depend on how many requests a session had made, which is not a property
  //       a displayed clock should have; the newest anchor has the least accumulated elapsed error.
  anchor = { serverEpochMs: parsed, localElapsedMs: monotonicNow() };
  return true;
}

/**
 * Returns the current instant according to the server, or `undefined` if none has been observed.
 *
 * Assumptions: the value is recomputed on each call rather than cached, so a caller that reads it
 * during render gets a paint-time instant — which is the property the baseline had, because
 * `POPULATE-HEADER-INFO` re-read the clock on every `SEND MAP`.
 * @returns {Date | undefined} Server-anchored current instant, or `undefined` before the first
 *   response has been observed — which is the normal case for a unit test and for a local
 *   development server with no published configuration document.
 */
export function serverInstant(): Date | undefined {
  if (anchor === undefined) {
    return undefined;
  }
  return new Date(anchor.serverEpochMs + (monotonicNow() - anchor.localElapsedMs));
}

/**
 * Discards the recorded anchor.
 *
 * Assumptions: this exists for tests. The anchor is module-level, so without it one case's
 * observation would leak into the next and a case asserting the unanchored fallback would pass or
 * fail depending on which case ran before it.
 */
export function resetServerClock(): void {
  anchor = undefined;
}
