/**
 * @file Unit tests for the shared keyset browse state in `ui/src/hooks/usePagedQuery.ts`.
 *
 * Purpose
 * -------
 * Fix the four invariants that hook owes every paged screen and that nothing else can assert: that a
 * failure reaches a screen with the problem document the transport carried, that a page holding more
 * rows than the screen declared room for is refused rather than silently truncated, that a browse held
 * back by its switch reads nothing and settles idle, and that each step replays the correct cursor in
 * the correct direction.
 *
 * Refactoring Rationale: this file is new because the hook had no test at all, and three of the four
 * invariants above were broken in ways no other test could see. The failure path looked for its document
 * in the raw axios shape, which the shared client's interceptor has already replaced by the time a read
 * rejects, so every real refusal reported a failure with no document. The arity a screen declares was
 * validated on the way in and then never compared with a delivered page. And the switch gated only the
 * opening read, so the restart and paging steps still read while the browse was held back.
 *
 * Assumptions: this file lives under `ui/src/test/` rather than beside the hook. The hook's own overview
 * fixes `ui/src/hooks` at the two modules a screen imports, and the project's UI tests live here, so
 * placing it here keeps both conventions rather than trading one for the other.
 *
 * Assumptions: the reader is injected, which is the property that makes these cases possible without a
 * transport. Every case supplies its own reader, so what is measured is the hook's cursor arithmetic and
 * its guards, never a service.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps `types` EMPTY, and DECLARING them here would make `expect` and `vi`
// visible to production screens as well, where a stray call would compile. (ui/vitest.config.ts
// sets `globals: true`; an injected global is not a declared one, so the import still carries the
// compiler's side of this.)
import { act, renderHook, waitFor } from '@testing-library/react';
import type { RenderHookResult } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '../api/client';
import { usePagedQuery } from '../hooks/usePagedQuery';
import type { PageBoundary, PagedQueryRequest, UsePagedQueryResult } from '../hooks/usePagedQuery';
import type { ApiError, PageDirection, PageResponse } from '../api/types';

/** The arity the card browse declares, from `WS-SCREEN-ROWS OCCURS 7 TIMES`. */
const PAGE_SIZE = 7;

/** One request the reader recorded, reduced to what these assertions are about. */
interface RecordedRequest {
  readonly cursor: string | null;
  readonly direction: PageDirection;
}

/** A page reader, as the hook's options declare one. Rows are strings; see `pageOf`. */
type Reader = (request: PagedQueryRequest) => Promise<PageResponse<string>>;

/** The three options a case varies, passed as render props so a case can change one and re-render. */
interface BrowseProps {
  readonly pageSize: number;
  readonly fetchPage: Reader;
  readonly enabled: boolean;
}

/** One mounted browse, as the render helper returns it. */
type MountedBrowse = RenderHookResult<UsePagedQueryResult<string>, BrowseProps>;

/** The live result of a mounted browse, which the waiters and steps below read. */
type BrowseView = { readonly current: UsePagedQueryResult<string> };

/**
 * Builds a page of the given size carrying the two sealed boundaries.
 *
 * Assumptions: rows are plain strings, because the hook passes rows through untouched and performs no
 * numeric conversion, formatting or reordering on them -- so their shape is immaterial to every case
 * here, and a realistic row shape would only invite an assertion about something the hook does not do.
 * @param {number} rows - How many rows the page carries.
 * @param {boolean} hasNext - Whether the envelope reports a further page.
 * @param {string} keyPrefix - Prefix distinguishing this page's boundary tokens from another's.
 * @returns {PageResponse<string>} One page envelope.
 */
function pageOf(rows: number, hasNext: boolean, keyPrefix: string): PageResponse<string> {
  const items: string[] = [];
  for (let index = 0; index < rows; index += 1) {
    items.push(`${keyPrefix}-row-${String(index)}`);
  }
  return { items, firstKey: `${keyPrefix}-first`, lastKey: `${keyPrefix}-last`, hasNext };
}

/**
 * Builds a complete problem document, as the shared client carries one on a normalised failure.
 * @param {string} correlationId - The identifier an operator quotes to support.
 * @returns {ApiError} One document with all eleven members present.
 */
function problemDocument(correlationId: string): ApiError {
  return {
    code: 'CARDDEMO-CARD-0001',
    secondaryCode: '',
    message: 'Cursor could not be opened.',
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId,
    path: '/api/v1/cards/search',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors: [{ field: 'cursor', state: 'NOT_OK', message: 'Cursor could not be opened.' }],
    abend: null,
  };
}

/**
 * Builds a reader that records each request and answers from a queue of outcomes.
 *
 * Assumptions: outcomes are queued rather than derived from the request, because several cases need one
 * reader to answer differently on the second call than on the first -- a first page then a second page,
 * or a page then a refusal -- and a queue states that sequence at the call site where it is asserted.
 *
 * Assumptions: a reader built here MUST be hoisted out of the render callback, which is why the render
 * helper below takes one rather than composing it. Its position in the queue is closure state, so a
 * reader rebuilt on every render restarts at the first outcome -- answering a second paging step with
 * the first page again and making a correct hook look as though it had ignored the cursor it just sent.
 * Two cases here failed exactly that way before the readers were hoisted, so the constraint is recorded
 * rather than left to be rediscovered.
 * @param {ReadonlyArray<PageResponse<string> | Error>} outcomes - What to answer, in order. The last
 *   entry is repeated once the queue is exhausted, so a case may take more steps than it queued.
 * @param {RecordedRequest[]} recorded - Array the reader appends each request to.
 * @returns {Reader} The reader to inject.
 */
function readerAnswering(
  outcomes: ReadonlyArray<PageResponse<string> | Error>,
  recorded: RecordedRequest[],
): Reader {
  let call = 0;

  /**
   * Records one request and answers it from the queue.
   * @param {PagedQueryRequest} request - The position and direction the hook asked for.
   * @returns {Promise<PageResponse<string>>} The queued page, or a rejection with the queued error.
   */
  function read(request: PagedQueryRequest): Promise<PageResponse<string>> {
    recorded.push({ cursor: request.cursor, direction: request.direction });
    const outcome = outcomes[Math.min(call, outcomes.length - 1)];
    call += 1;
    if (outcome === undefined) {
      return Promise.reject(new Error('no outcome was queued'));
    }
    return outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome);
  }

  return read;
}

/**
 * A reader whose answers are released by the case, so a turn can be held IN FLIGHT.
 *
 * Purpose: every other reader in this file answers in an already-resolved promise, which is enough for
 * the cursor arithmetic but useless for the coalescing cases -- a turn that has already settled cannot
 * be joined by a second one, so a duplicate would never be issued and a broken guard would pass. Holding
 * the answer is what puts two presses inside one flight, which is the arrangement the review measured.
 */
interface HeldReader {
  /** The reader to inject; each call records its request and returns an unsettled promise. */
  readonly read: Reader;
  /** Every request the reader has been asked for, in order. */
  readonly requests: RecordedRequest[];
  /**
   * Answers the OLDEST unanswered read.
   * @param {PageResponse<string>} page - The page to answer with.
   * @returns {void} Nothing; the held promise is resolved.
   * @throws {Error} If nothing is waiting to be answered, which means the case has miscounted.
   */
  release(page: PageResponse<string>): void;
  /**
   * How many reads are waiting to be answered.
   * @returns {number} The count of unanswered reads.
   */
  outstanding(): number;
}

/**
 * Builds a reader that holds every answer until the case releases it.
 *
 * Assumptions: answers are released in REQUEST order, oldest first, because that is the order the cases
 * reason about -- a stale read issued before an idling is released before the live read issued after it,
 * so the assertion that the stale one is dropped exercises the reducer's sequence guard rather than
 * accidentally arriving second.
 * @returns {HeldReader} The reader, its recorded requests, and the release control.
 */
function heldReader(): HeldReader {
  const requests: RecordedRequest[] = [];
  const waiting: Array<(page: PageResponse<string>) => void> = [];

  /**
   * Records one request and returns a promise the case settles later.
   * @param {PagedQueryRequest} request - The position and direction the hook asked for.
   * @returns {Promise<PageResponse<string>>} Unsettled until {@link HeldReader.release} is called.
   */
  function read(request: PagedQueryRequest): Promise<PageResponse<string>> {
    requests.push({ cursor: request.cursor, direction: request.direction });
    return new Promise<PageResponse<string>>(
      /**
       * Holds this read's resolver for the case to call.
       * @param {(page: PageResponse<string>) => void} resolve - Settles this read.
       * @returns {void} Nothing; the resolver is queued.
       */
      (resolve) => {
        waiting.push(resolve);
      },
    );
  }

  /**
   * Answers the oldest unanswered read.
   * @param {PageResponse<string>} page - The page to answer with.
   * @returns {void} Nothing; the held promise is resolved.
   * @throws {Error} If nothing is waiting to be answered.
   */
  function release(page: PageResponse<string>): void {
    const resolve = waiting.shift();
    if (resolve === undefined) {
      throw new Error('no read was waiting to be answered');
    }
    resolve(page);
  }

  /**
   * Reports how many reads are waiting.
   * @returns {number} The count of unanswered reads.
   */
  function outstanding(): number {
    return waiting.length;
  }

  return { read, requests, release, outstanding };
}

/**
 * Builds a page whose two boundary cursors are the SAME token.
 *
 * Assumptions: this shape exists for one case -- proving a forward turn and a backward turn from one
 * position are not treated as the same turn. Every other page in this file carries distinct boundaries,
 * so with those a direction-blind coalescing key would still issue both requests and the case would pass
 * while the defect stood. A single-row page is the honest way to make both cursors equal, and it is a
 * shape the wire really produces.
 * @param {string} key - The token both boundaries carry.
 * @param {boolean} hasNext - Whether the envelope reports a further page.
 * @returns {PageResponse<string>} One page whose leading and trailing cursors are identical.
 */
function singleKeyPage(key: string, hasNext: boolean): PageResponse<string> {
  return { items: [`${key}-row`], firstKey: key, lastKey: key, hasNext };
}

/**
 * The component under test: the hook itself, driven by render props.
 *
 * Assumptions: a named function rather than an inline arrow at each call site, so that changing an
 * option is a `rerender` with new props rather than a new closure -- which is what lets the enable and
 * disable cases keep one reader across the change.
 * @param {BrowseProps} props - The arity, the reader and the switch for this render.
 * @returns {UsePagedQueryResult<string>} The browse as a screen would observe it.
 */
function browseUnderTest(props: BrowseProps): UsePagedQueryResult<string> {
  return usePagedQuery(props);
}

/**
 * Mounts one browse over a hoisted reader.
 * @param {Reader} reader - The reader to inject, built once and never inside a render.
 * @param {boolean} [enabled] - Whether the browse may read, defaulting to `true`.
 * @param {number} [pageSize] - The declared arity, defaulting to the card browse's seven.
 * @returns {MountedBrowse} The mounted browse, with `rerender` for changing an option.
 */
function mountBrowse(reader: Reader, enabled = true, pageSize = PAGE_SIZE): MountedBrowse {
  return renderHook(browseUnderTest, { initialProps: { pageSize, fetchPage: reader, enabled } });
}

/**
 * Waits until no read is outstanding.
 * @param {BrowseView} view - The live browse result.
 * @returns {Promise<void>} Resolves once the browse reports nothing outstanding.
 */
async function waitForIdle(view: BrowseView): Promise<void> {
  await waitFor(
    /**
     * Asserts the outstanding-read flag is lowered.
     * @returns {void} Nothing; throws until it is.
     */
    () => {
      expect(view.current.isLoading).toBe(false);
    },
  );
}

/**
 * Waits until the browse reports a failure.
 * @param {BrowseView} view - The live browse result.
 * @returns {Promise<void>} Resolves once the failure flag is raised.
 */
async function waitForFailure(view: BrowseView): Promise<void> {
  await waitFor(
    /**
     * Asserts the failure flag is raised.
     * @returns {void} Nothing; throws until it is.
     */
    () => {
      expect(view.current.isFailed).toBe(true);
    },
  );
}

/**
 * Waits until the browse publishes the given number of rows.
 * @param {BrowseView} view - The live browse result.
 * @param {number} rows - The row count to wait for.
 * @returns {Promise<void>} Resolves once the published page carries that many rows.
 */
async function waitForRowCount(view: BrowseView, rows: number): Promise<void> {
  await waitFor(
    /**
     * Asserts the published row count.
     * @returns {void} Nothing; throws until the count matches.
     */
    () => {
      expect(view.current.items).toHaveLength(rows);
    },
  );
}

/**
 * Waits until the browse reports the given page ordinal.
 * @param {BrowseView} view - The live browse result.
 * @param {number} ordinal - The ordinal to wait for.
 * @returns {Promise<void>} Resolves once the ordinal matches.
 */
async function waitForPageNumber(view: BrowseView, ordinal: number): Promise<void> {
  await waitFor(
    /**
     * Asserts the published ordinal.
     * @returns {void} Nothing; throws until it matches.
     */
    () => {
      expect(view.current.pageNumber).toBe(ordinal);
    },
  );
}

/**
 * Waits until the reader has been asked the given number of times.
 * @param {RecordedRequest[]} recorded - The reader's recorded requests.
 * @param {number} count - The request count to wait for.
 * @returns {Promise<void>} Resolves once that many requests have been recorded.
 */
async function waitForRequestCount(recorded: RecordedRequest[], count: number): Promise<void> {
  await waitFor(
    /**
     * Asserts the recorded request count.
     * @returns {void} Nothing; throws until it matches.
     */
    () => {
      expect(recorded).toHaveLength(count);
    },
  );
}

/**
 * Takes the forward paging step and waits for the turn to settle.
 *
 * Refactoring Rationale: the step is now AWAITED, where this helper used to invoke it and return. The
 * hook's three steps resolve when the turn has settled into the browse, so awaiting one is both the
 * clearest way to sequence a case and the only way to discard the promise that `no-floating-promises`
 * admits -- `ui/eslint.config.js` configures it with `ignoreVoid: false`, so an unhandled
 * promise-returning call is an error even with a `void` in front of it.
 * @param {BrowseView} view - The live browse result.
 * @returns {Promise<void>} Resolves once the forward turn has settled into the browse.
 */
async function stepForward(view: BrowseView): Promise<void> {
  await act(
    /**
     * Invokes the forward step inside a render batch and waits for its turn.
     * @returns {Promise<void>} Resolves once the turn has settled.
     */
    async () => {
      await view.current.nextPage();
    },
  );
}

/**
 * Takes the backward paging step and waits for the turn to settle.
 * @param {BrowseView} view - The live browse result.
 * @returns {Promise<void>} Resolves once the backward turn has settled into the browse.
 */
async function stepBackward(view: BrowseView): Promise<void> {
  await act(
    /**
     * Invokes the backward step inside a render batch and waits for its turn.
     * @returns {Promise<void>} Resolves once the turn has settled.
     */
    async () => {
      await view.current.prevPage();
    },
  );
}

/**
 * Takes all three steps in one batch, which is how the held-back case proves each is refused.
 *
 * Assumptions: all three are awaited together rather than one after another, because the property being
 * proved is that each is a no-op -- and a refused step resolves immediately, so awaiting them together
 * would hang only if one of them had issued a read this case's reader never answers.
 * @param {BrowseView} view - The live browse result.
 * @returns {Promise<void>} Resolves once all three steps have returned; every one is expected to have
 *   done nothing.
 */
async function takeEveryStep(view: BrowseView): Promise<void> {
  await act(
    /**
     * Invokes the restart, forward and backward steps together.
     * @returns {Promise<void>} Resolves once all three have settled.
     */
    async () => {
      await Promise.all([view.current.reset(), view.current.nextPage(), view.current.prevPage()]);
    },
  );
}

/** Asserts the opening read carries no cursor and publishes the page it answers with. */
async function opensWithNoCursorAndPublishesThePage(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE, true, 'p1')], recorded));

  await waitForIdle(result);
  expect(recorded).toEqual([{ cursor: null, direction: 'next' }]);
  expect(result.current.items).toHaveLength(PAGE_SIZE);
  expect(result.current.pageNumber).toBe(1);
  expect(result.current.hasNext).toBe(true);
  // Assumptions: the backward step is refused on the opening page from the ordinal alone, which is how
  //   the reference decides it at `app/cbl/COCRDLIC.cbl` L901 to L903 -- with no backward read issued.
  expect(result.current.hasPrev).toBe(false);
  expect(result.current.isFailed).toBe(false);
}

/**
 * Asserts a normalised transport failure reaches the screen with its problem document intact.
 *
 * Refactoring Rationale: this is the case the previous implementation could not pass. It searched a
 * rejection for `response.data`, but the shared client's response interceptor converts every axios
 * failure into an `ApiRequestError` and throws that, so the searched member never existed and every
 * refusal arrived as a failure with no document -- losing the code, the sentence, the per-field entries
 * and the correlation identifier the service had actually sent.
 */
async function carriesTheProblemDocumentOfANormalisedFailure(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const failure = new ApiRequestError(
    'PROBLEM',
    400,
    problemDocument('UIABCDEF0123456789ABCD'),
    'PROBLEM 400 CARDDEMO-CARD-0001',
  );
  const { result } = mountBrowse(readerAnswering([failure], recorded));

  await waitForFailure(result);
  expect(result.current.error).not.toBeNull();
  expect(result.current.error?.correlationId).toBe('UIABCDEF0123456789ABCD');
  expect(result.current.error?.code).toBe('CARDDEMO-CARD-0001');
  // Assumptions: the field-error array is asserted because its ORDER is the order a form marks its
  //   fields in, so passing the document through rather than rebuilding it is the property under test.
  expect(result.current.error?.fieldErrors).toHaveLength(1);
  expect(result.current.error?.fieldErrors[0]?.field).toBe('cursor');
  expect(result.current.isLoading).toBe(false);
}

/** Asserts a reader that rejects with a bare problem document is also understood. */
async function carriesADocumentARejectedReaderSuppliedDirectly(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const bare = Object.assign(new Error('refused'), problemDocument('UI000000000000000000'));
  const { result } = mountBrowse(readerAnswering([bare], recorded));

  await waitForFailure(result);
  expect(result.current.error?.correlationId).toBe('UI000000000000000000');
}

/** Asserts a rejection carrying nothing readable reports the failure with no invented document. */
async function reportsAFailureWithNoDocumentWithoutInventingOne(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(
    readerAnswering([new Error('the edge was unreachable')], recorded),
  );

  await waitForFailure(result);
  // Assumptions: null rather than a stand-in. A fabricated code or correlation identifier would be
  //   indistinguishable from one a service sent, and an operator quoting a made-up identifier to
  //   support is worse off than one quoting none.
  expect(result.current.error).toBeNull();
}

/**
 * Asserts a page carrying more rows than the screen declared room for is refused, not truncated.
 *
 * Assumptions: the surplus row is the significant one. The service establishes the further page from a
 * probe read one row beyond the page, so a page arriving one row over means the probe row was published
 * -- and the row a screen silently dropped would be the row the next forward step reads from, so the
 * operator would step past a row that was never shown.
 */
async function refusesAPageWiderThanTheDeclaredArity(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE + 1, true, 'wide')], recorded));

  await waitForFailure(result);
  expect(result.current.items).toHaveLength(0);
  expect(result.current.error).toBeNull();
  expect(result.current.isLoading).toBe(false);
}

/** Asserts a page of exactly the declared arity is published, so the bound is inclusive. */
async function acceptsAPageOfExactlyTheDeclaredArity(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE, false, 'full')], recorded));

  await waitForRowCount(result, PAGE_SIZE);
  expect(result.current.isFailed).toBe(false);
}

/**
 * Asserts a page carrying no rows is published with the boundaries the envelope supplied.
 *
 * Assumptions: this is the filtered-away page `PageResponse.ofFilteredEmpty` exists for -- every row of
 * the read failed a filter applied after fetching, so no row is returned while rows remain on both sides
 * and the boundaries are the keys at which scanning stopped. Its cursors are therefore NOT null, which
 * is why this hook states cursor nullability against the envelope rather than against the row count.
 */
async function publishesAFilteredEmptyPageWithItsBoundaries(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const filteredEmpty: PageResponse<string> = {
    items: [],
    firstKey: 'scan-first',
    lastKey: 'scan-last',
    hasNext: true,
  };
  const { result } = mountBrowse(readerAnswering([filteredEmpty], recorded));

  await waitForIdle(result);
  expect(result.current.items).toHaveLength(0);
  expect(result.current.isFailed).toBe(false);
  // Assumptions: the forward step is available from an empty page, which is what keeps a filtered-away
  //   page pageable. Deciding availability from the row count would strand the browse here.
  expect(result.current.hasNext).toBe(true);

  await stepForward(result);
  await waitForRequestCount(recorded, 2);
  expect(recorded[1]).toEqual({ cursor: 'scan-last', direction: 'next' });
}

/** Asserts the forward step replays the trailing cursor forward and raises the ordinal. */
async function stepsForwardFromTheTrailingCursor(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(
    readerAnswering([pageOf(PAGE_SIZE, true, 'p1'), pageOf(PAGE_SIZE, false, 'p2')], recorded),
  );

  await waitForRowCount(result, PAGE_SIZE);
  await stepForward(result);
  await waitForPageNumber(result, 2);

  expect(recorded[1]).toEqual({ cursor: 'p1-last', direction: 'next' });
  expect(result.current.hasPrev).toBe(true);
  expect(result.current.hasNext).toBe(false);
}

/** Asserts the backward step replays the leading cursor backward and lowers the ordinal. */
async function stepsBackwardFromTheLeadingCursor(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(
    readerAnswering(
      [
        pageOf(PAGE_SIZE, true, 'p1'),
        pageOf(PAGE_SIZE, false, 'p2'),
        pageOf(PAGE_SIZE, true, 'p1'),
      ],
      recorded,
    ),
  );

  await waitForRowCount(result, PAGE_SIZE);
  await stepForward(result);
  await waitForPageNumber(result, 2);
  await stepBackward(result);
  await waitForPageNumber(result, 1);

  // Assumptions: the backward step replays the LEADING cursor of the page it was taken from, which is
  //   the reference's own arrangement -- `app/cbl/COCRDLIC.cbl` L1268 moves the current page's first
  //   key into the field its backward browse is positioned from.
  expect(recorded[2]).toEqual({ cursor: 'p2-first', direction: 'previous' });
}

/**
 * Builds a page carrying no rows and whichever continuation positions were supplied.
 *
 * Assumptions: this is `PageResponse.ofFilteredEmpty` as the wire carries it -- the boundary members
 * hold the keys at which scanning stopped rather than any row's identity, and a further page is
 * reported exactly when the forward position is present.
 * @param {string | null} forwardPosition - Where the forward scan stopped, or `null` for nothing ahead.
 * @param {string | null} backwardPosition - Where the backward scan stopped, or `null` for nothing
 *   behind.
 * @returns {PageResponse<string>} One row-less page envelope.
 */
function rowlessPage(
  forwardPosition: string | null,
  backwardPosition: string | null,
): PageResponse<string> {
  return {
    items: [],
    firstKey: backwardPosition,
    lastKey: forwardPosition,
    hasNext: forwardPosition !== null,
  };
}

/**
 * Reads the boundary of a browse settled on its opening page.
 * @param {PageResponse<string>} page - The page the reader answers the opening read with.
 * @returns {Promise<PageBoundary>} The boundary published for it.
 */
async function openingBoundaryOf(page: PageResponse<string>): Promise<PageBoundary> {
  const { result } = mountBrowse(readerAnswering([page], []));
  await waitForIdle(result);
  return result.current.boundary;
}

/**
 * Asserts the boundary names each position exactly once as a browse is walked end to end.
 *
 * Purpose: this is the single derivation V254, V112, V220 and V166 answer between them. The shipped
 * build had five screens composing five different expressions of the same browse state -- one deciding
 * a backward step from the ordinal, one from `hasPrev`, one from the cursor pair -- so the same state
 * gave four different answers to "am I at the top?", and the zero-row case was answered on some browses
 * and silent on others. Every one of those derivations was correct; what this fixes is that there was
 * no shared one to copy.
 *
 * Assumptions: the walk is asserted rather than three separate mounts, because the property is that the
 * boundary MOVES with the browse. A per-state mount would pass against a derivation that returned the
 * right name for an opening page and then never changed.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function namesEachPositionAsTheBrowseIsWalked(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(
    readerAnswering(
      [
        pageOf(PAGE_SIZE, true, 'p1'),
        pageOf(PAGE_SIZE, true, 'p2'),
        pageOf(PAGE_SIZE, false, 'p3'),
      ],
      recorded,
    ),
  );

  await waitForRowCount(result, PAGE_SIZE);
  expect(result.current.boundary).toBe('FIRST');

  await stepForward(result);
  await waitForPageNumber(result, 2);
  expect(result.current.boundary).toBe('INTERIOR');

  await stepForward(result);
  await waitForPageNumber(result, 3);
  expect(result.current.boundary).toBe('LAST');
}

/**
 * Asserts a single full page is named as the only page rather than as either boundary.
 *
 * Assumptions: this is the state a screen must not describe with either boundary sentence -- there is
 * no page above and none below, so "you are already at the top" is as wrong as "at the bottom".
 * @returns {Promise<void>} Nothing; the assertion is the outcome.
 */
async function namesASinglePageAsTheOnlyOne(): Promise<void> {
  expect(await openingBoundaryOf(pageOf(PAGE_SIZE, false, 'only'))).toBe('ONLY');
}

/**
 * Asserts a browse with nothing on display and nowhere to go is the dead end.
 *
 * Purpose: this is the measured inconsistency. With no rows loaded, one browse left both paging keys
 * offering a step and another greyed both, because each screen decided for itself whether that state
 * was a boundary. It is now one named state, so a screen has something to branch on instead of a
 * predicate to invent.
 * @returns {Promise<void>} Nothing; the assertion is the outcome.
 */
async function namesANothingToShowBrowseAsTheDeadEnd(): Promise<void> {
  expect(await openingBoundaryOf(rowlessPage(null, null))).toBe('EMPTY');
}

/**
 * Asserts a page filtered down to no rows is NOT the dead end while the browse continues.
 *
 * Purpose: this is the case a row-count test alone gets wrong, and it is the reference's own state
 * rather than a hypothetical. The card list reads a screen's worth of records and applies its filter
 * afterwards at `app/cbl/COCRDLIC.cbl` L1382, so a read whose every record fails the filter displays
 * nothing while records remain on both sides; `PageResponse.ofFilteredEmpty` carries exactly that. A
 * screen told this was `EMPTY` would show "there are no records" over a browse it can page straight out
 * of -- and the existing case above already proves the forward step works from here.
 *
 * Assumptions: the backward-only form is asserted too, since a filtered-away page reached by stepping
 * back has a page behind it and none ahead, which is `LAST` and not the dead end.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function doesNotCallAFilteredAwayPageTheDeadEnd(): Promise<void> {
  expect(await openingBoundaryOf(rowlessPage('scan-last', 'scan-first'))).toBe('FIRST');
  expect(await openingBoundaryOf(rowlessPage('scan-last', null))).toBe('FIRST');
}

/**
 * Asserts the boundary and the two availability members can never disagree.
 *
 * Assumptions: they are asserted TOGETHER at each position rather than separately, because the whole
 * value of publishing a derived state is that it is derived from the values a step is guarded on -- a
 * boundary saying `LAST` beside a `hasNext` of true would send a screen's sentence and its key
 * arm in opposite directions, which is precisely the class of divergence this member exists to end.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function agreesWithTheTwoAvailabilityMembers(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(
    readerAnswering([pageOf(PAGE_SIZE, true, 'p1'), pageOf(PAGE_SIZE, false, 'p2')], recorded),
  );

  await waitForRowCount(result, PAGE_SIZE);
  expect([result.current.boundary, result.current.hasPrev, result.current.hasNext]).toEqual([
    'FIRST',
    false,
    true,
  ]);

  await stepForward(result);
  await waitForPageNumber(result, 2);
  expect([result.current.boundary, result.current.hasPrev, result.current.hasNext]).toEqual([
    'LAST',
    true,
    false,
  ]);
}

/**
 * Asserts a browse held back by its switch issues no read at all and reports the opening state.
 *
 * Assumptions: the read count is asserted as zero rather than merely checking a flag, because the defect
 * this closes was reads being ISSUED -- a flag could have been correct while a request went out.
 */
function issuesNoReadWhileHeldBack(): void {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE, true, 'p1')], recorded), false);

  expect(recorded).toHaveLength(0);
  expect(result.current.isLoading).toBe(false);
  expect(result.current.items).toHaveLength(0);
}

/**
 * Asserts the restart and both paging steps are no-ops while the browse is held back.
 *
 * Refactoring Rationale: this is the case the previous implementation could not pass. The switch gated
 * only the opening effect, so each of these three steps still issued a read -- and the answer settled
 * into a browse the screen was showing no rows for.
 *
 * Assumptions: awaiting the three steps also proves a refused step RESOLVES rather than rejecting. A
 * rejection would surface here as this case failing, which is what makes the awaited form stronger than
 * the discarded one it replaced.
 * @returns {Promise<void>} Resolves once all three refused steps have returned.
 */
async function refusesEveryStepWhileHeldBack(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE, true, 'p1')], recorded), false);

  await takeEveryStep(result);

  expect(recorded).toHaveLength(0);
  expect(result.current.isLoading).toBe(false);
  expect(result.current.isFailed).toBe(false);
}

/**
 * Asserts lowering the switch after a page was published returns the browse to its opening state.
 *
 * Assumptions: the rows are discarded rather than kept, because the switch is lowered when the screen's
 * criteria stop being meaningful, so the rows on display answer a question no longer being asked. The
 * ordinal returns to the opening page too, so re-enabling reads the first page of the new criteria
 * rather than resuming a position whose criteria the operator can no longer see.
 */
async function idlesTheBrowseWhenTheSwitchIsLowered(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const reader = readerAnswering(
    [pageOf(PAGE_SIZE, true, 'p1'), pageOf(PAGE_SIZE, true, 'p2')],
    recorded,
  );
  const { result, rerender } = mountBrowse(reader);

  await waitForRowCount(result, PAGE_SIZE);
  await stepForward(result);
  await waitForPageNumber(result, 2);

  rerender({ pageSize: PAGE_SIZE, fetchPage: reader, enabled: false });

  expect(result.current.items).toHaveLength(0);
  expect(result.current.pageNumber).toBe(1);
  expect(result.current.hasNext).toBe(false);
  expect(result.current.hasPrev).toBe(false);
  expect(result.current.isLoading).toBe(false);
  expect(result.current.isFailed).toBe(false);
  expect(result.current.error).toBeNull();
  // Assumptions: the read count is asserted unchanged, so idling is proved not to issue a read of its
  //   own while returning the browse to the state a screen mounted with the switch down would see.
  expect(recorded).toHaveLength(2);
}

/** Asserts raising the switch again opens the browse at the first page. */
async function opensAtTheFirstPageWhenTheSwitchIsRaisedAgain(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const reader = readerAnswering([pageOf(PAGE_SIZE, true, 'p1')], recorded);
  const { result, rerender } = mountBrowse(reader, false);

  expect(recorded).toHaveLength(0);
  rerender({ pageSize: PAGE_SIZE, fetchPage: reader, enabled: true });

  await waitForRowCount(result, PAGE_SIZE);
  expect(recorded).toEqual([{ cursor: null, direction: 'next' }]);
  expect(result.current.pageNumber).toBe(1);
}

/**
 * Asserts an arity that is not a whole number of at least one is refused as a wiring mistake.
 *
 * Assumptions: the refusal is a throw from the hook rather than a reported failure, because the value is
 * a constant each screen declares from its own mapset -- so it cannot be wrong for one render and right
 * for the next, and a screen cannot recover from it at runtime.
 */
function refusesAnArityThatDescribesNoScreen(): void {
  const recorded: RecordedRequest[] = [];
  const reader = readerAnswering([pageOf(1, false, 'x')], recorded);

  expect(
    /**
     * Mounts a browse whose declared arity describes no screen.
     * @returns {MountedBrowse} Never; the mount is expected to throw.
     */
    () => mountBrowse(reader, true, 0),
  ).toThrow(RangeError);
  expect(recorded).toHaveLength(0);
}

/**
 * Asserts a browse can tell WHICH failure it suffered, not merely that one occurred.
 *
 * Purpose: this is the measured defect. Two reviews found a timeout, a dropped connection and a 500
 * rendered identically on every route, one screen describing a 404 as a temporary availability problem,
 * and a conflict on a save reported as "the record does not exist" -- because the only failure channel a
 * browse published was the flattened problem document, and a document synthesised for a timeout and a
 * document a service sent for a refusal are the same shape. The classification existed the whole time
 * on the shared client's own failure type and was discarded on the way through this hook.
 *
 * Assumptions: BOTH channels are asserted on the same failure, because the fix is additive and that is
 * the whole point of it. `error` must still carry the document verbatim -- its code, its correlation
 * identifier and its per-field entries in order -- since five screens already render from it; and
 * `failure` must carry the kind and the two remedy members beside it.
 *
 * Assumptions: a TIMEOUT is chosen rather than the 400 the neighbouring case uses, because a timeout is
 * the failure the reviews found indistinguishable from a refusal. It is the one whose `transient` member
 * differs from a refusal's, so it is the one that proves the classification survived rather than being
 * re-derived from the status.
 * @returns {Promise<void>} Resolves once the browse has settled its opening read.
 */
async function carriesTheClassificationOfANormalisedFailure(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const failure = new ApiRequestError(
    'TIMEOUT',
    0,
    problemDocument('UI1111111111111111'),
    'TIMEOUT 0 CARDDEMO-UI-TIMEOUT UI1111111111111111',
  );
  const { result } = mountBrowse(readerAnswering([failure], recorded));

  await waitForFailure(result);

  expect(result.current.failure, 'the classified failure is reachable').not.toBeNull();
  expect(result.current.failure?.kind).toBe('TIMEOUT');
  expect(result.current.failure?.status).toBe(0);
  // Assumptions: the two remedy members are asserted separately rather than as one object, because they
  //   answer different questions -- whether the CONDITION may clear, and whether the REQUEST is safe to
  //   repeat -- and a screen offering a re-submit reads only the second.
  expect(result.current.failure?.transient).toBe(true);
  expect(result.current.failure?.repeatable).toBe(false);

  // Assumptions: the document channel is asserted on the SAME failure, so the case would fail if the
  //   classification had been added by re-typing `error` rather than beside it.
  expect(result.current.error?.correlationId).toBe('UI1111111111111111');
  expect(result.current.error?.fieldErrors).toHaveLength(1);
  expect(result.current.error?.fieldErrors[0]?.field).toBe('cursor');
}

/**
 * Asserts a refusal a service described is classified as one, and is not called momentary.
 *
 * Assumptions: this is the counterpart of the timeout case and exists because a screen chooses between
 * two sentences on `transient` alone. A 400 the service described must report `transient: false`, or a
 * browse would offer "not available at the moment" for input the operator has to correct -- which is
 * exactly the wording a review found on a 404.
 * @returns {Promise<void>} Resolves once the browse has settled its opening read.
 */
async function classifiesADescribedRefusalAsNotMomentary(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const failure = new ApiRequestError(
    'PROBLEM',
    400,
    problemDocument('UI2222222222222222'),
    'PROBLEM 400 CARDDEMO-CARD-0001 UI2222222222222222',
  );
  const { result } = mountBrowse(readerAnswering([failure], recorded));

  await waitForFailure(result);
  expect(result.current.failure?.kind).toBe('PROBLEM');
  expect(result.current.failure?.status).toBe(400);
  expect(result.current.failure?.transient).toBe(false);
  expect(result.current.failure?.repeatable).toBe(false);
}

/**
 * Asserts a rejection that is not one of the shared client's failures publishes NO classification.
 *
 * Assumptions: a bare document is a real route -- a reader may reject with one directly, and the
 * neighbouring case proves the document still reaches the screen from it. What it cannot supply is a
 * classification, because a classification is a JUDGEMENT the transport made and nothing here made one.
 * Inventing a kind would put a browse in the position the reviews measured, only backwards: confidently
 * reporting a transport judgement about a failure the transport never saw.
 *
 * Assumptions: `isFailed` is asserted true alongside, because it -- not the presence of a
 * classification -- remains the flag a screen branches on to know a read failed at all.
 * @returns {Promise<void>} Resolves once the browse has settled its opening read.
 */
async function publishesNoClassificationForANonClientRejection(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const bare = Object.assign(new Error('refused'), problemDocument('UI3333333333333333'));
  const { result } = mountBrowse(readerAnswering([bare], recorded));

  await waitForFailure(result);
  expect(result.current.error?.correlationId).toBe('UI3333333333333333');
  expect(result.current.failure, 'no transport made a judgement here').toBeNull();
  expect(result.current.isFailed).toBe(true);
}

/**
 * Asserts this module's own refusal of an over-long page publishes neither channel.
 *
 * Assumptions: no request failed here at all -- the read was ANSWERED and this module is refusing the
 * answer -- so there is no document a service sent and no transport judgement to report. Labelling it
 * transient would tell a screen that pressing the key again might help when the service would answer
 * identically, and labelling it repeatable would invite exactly that press.
 * @returns {Promise<void>} Resolves once the refusal has settled.
 */
async function publishesNeitherChannelForALocalRefusal(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE + 1, true, 'wide')], recorded));

  await waitForFailure(result);
  expect(result.current.error).toBeNull();
  expect(result.current.failure).toBeNull();
  expect(result.current.items).toHaveLength(0);
}

/**
 * Asserts a new read clears the previous failure's classification along with its document.
 *
 * Assumptions: the two are cleared TOGETHER or a screen ends up branching on a classification while the
 * document it would render has already gone -- offering a repeat control for a failure no longer being
 * reported. This walks the whole cycle: a failure, then a successful turn over the same browse.
 * @returns {Promise<void>} Resolves once the recovering read has settled.
 */
async function clearsTheClassificationWhenAReadSucceeds(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const failure = new ApiRequestError(
    'NETWORK',
    0,
    problemDocument('UI4444444444444444'),
    'NETWORK 0 CARDDEMO-UI-NETWORK UI4444444444444444',
  );
  const { result } = mountBrowse(
    readerAnswering([failure, pageOf(PAGE_SIZE, false, 'recovered')], recorded),
  );

  await waitForFailure(result);
  expect(result.current.failure?.kind).toBe('NETWORK');

  await act(
    /**
     * Refreshes the browse so the second, succeeding outcome is taken from the queue.
     * @returns {Promise<void>} Resolves once the refreshed page has settled.
     */
    async () => {
      await result.current.reset();
    },
  );

  expect(result.current.items).toHaveLength(PAGE_SIZE);
  expect(result.current.isFailed).toBe(false);
  expect(result.current.error).toBeNull();
  expect(result.current.failure, 'the classification was cleared with the document').toBeNull();
}

/**
 * Asserts three presses of one forward key inside a single flight issue ONE request.
 *
 * Purpose: this is the measured defect. A review pressed a browse's forward key twice 400 ms apart and
 * got two identical requests, leaving the ordinal reading one page further than the rows on display;
 * three presses of another browse's control produced three identical posts. Nothing anywhere coalesced
 * an in-flight duplicate, and the screens that were protected were protected by their own refs.
 *
 * Assumptions: the three presses are taken inside ONE render batch, which is what puts them inside one
 * flight -- the reader holds its answer, so the first turn is still outstanding when the second and third
 * arrive. Pressing after a settlement is a different turn and must still be answered, which the walk
 * cases already prove.
 *
 * Assumptions: the request COUNT is what is asserted, not a flag. A guard that raised a busy flag while
 * still issuing the request would leave the duplicate write in place, and the count is the only thing
 * that distinguishes the two.
 * @returns {Promise<void>} Resolves once the coalesced turn has settled.
 */
async function coalescesRepeatedForwardTurns(): Promise<void> {
  const reader = heldReader();
  const { result } = mountBrowse(reader.read);

  await act(
    /**
     * Answers the opening read so the browse has a page and a trailing cursor.
     * @returns {Promise<void>} Resolves once the page is on display.
     */
    async () => {
      reader.release(pageOf(PAGE_SIZE, true, 'p1'));
      await Promise.resolve();
    },
  );
  expect(result.current.items).toHaveLength(PAGE_SIZE);
  expect(reader.requests).toHaveLength(1);

  const turns: Array<Promise<void>> = [];
  act(
    /**
     * Presses the forward key three times without letting the first turn settle.
     * @returns {void} Nothing; the turns are collected for awaiting.
     */
    () => {
      turns.push(result.current.nextPage(), result.current.nextPage(), result.current.nextPage());
    },
  );

  expect(reader.outstanding(), 'one read is in flight, not three').toBe(1);
  expect(reader.requests, 'the opening read and exactly one forward turn').toHaveLength(2);
  expect(reader.requests[1]).toEqual({ cursor: 'p1-last', direction: 'next' });

  await act(
    /**
     * Answers the single forward read and waits for all three joined turns.
     * @returns {Promise<void>} Resolves once every turn has settled.
     */
    async () => {
      reader.release(pageOf(PAGE_SIZE, false, 'p2'));
      await Promise.all(turns);
    },
  );

  // Assumptions: the ordinal is asserted because it is where the measured defect became visible to the
  //   operator -- two requests advanced it twice while one page arrived, so the screen read "Page: 3"
  //   over page two's rows.
  expect(result.current.pageNumber).toBe(2);
  expect(result.current.isLoading).toBe(false);
  expect(reader.requests).toHaveLength(2);
}

/**
 * Asserts a forward turn and a backward turn from ONE cursor are both issued.
 *
 * Purpose: this is the other half of the coalescing decision, and the half a careless key would break.
 * Two turns reading opposite ways from one position answer with different pages, so suppressing either
 * would strand the browse -- and a key composed from the cursor alone would suppress exactly this.
 * @returns {Promise<void>} Resolves once both turns have settled.
 */
async function issuesBothDirectionsFromOneCursor(): Promise<void> {
  const reader = heldReader();
  const { result } = mountBrowse(reader.read);

  await act(
    /**
     * Answers the opening read with a page whose two cursors are one token.
     * @returns {Promise<void>} Resolves once the page is on display.
     */
    async () => {
      reader.release(singleKeyPage('p1', true));
      await Promise.resolve();
    },
  );

  const forward: Array<Promise<void>> = [];
  act(
    /**
     * Steps forward once, so the browse leaves its opening page and a backward step becomes expressible.
     * @returns {void} Nothing; the turn is collected for awaiting.
     */
    () => {
      forward.push(result.current.nextPage());
    },
  );
  await act(
    /**
     * Answers the forward read with a second single-cursor page.
     * @returns {Promise<void>} Resolves once the second page is on display.
     */
    async () => {
      reader.release(singleKeyPage('p2', true));
      await Promise.all(forward);
    },
  );
  expect(result.current.pageNumber).toBe(2);
  expect(result.current.hasPrev).toBe(true);

  const both: Array<Promise<void>> = [];
  act(
    /**
     * Takes a forward and a backward turn from the same cursor in one batch.
     * @returns {void} Nothing; the turns are collected for awaiting.
     */
    () => {
      both.push(result.current.nextPage(), result.current.prevPage());
    },
  );

  expect(reader.requests, 'both directions were issued from the one cursor').toHaveLength(4);
  expect(reader.requests[2]).toEqual({ cursor: 'p2', direction: 'next' });
  expect(reader.requests[3]).toEqual({ cursor: 'p2', direction: 'previous' });

  await act(
    /**
     * Answers both reads so neither turn is left outstanding.
     * @returns {Promise<void>} Resolves once both turns have settled.
     */
    async () => {
      reader.release(singleKeyPage('p3', false));
      reader.release(singleKeyPage('p1', true));
      await Promise.all(both);
    },
  );
  // Assumptions: the backward turn is the later of the two, so the reducer's own sequence guard drops
  //   the forward settlement and the browse ends on the page the backward turn read.
  expect(result.current.pageNumber).toBe(1);
}

/**
 * Asserts two independently mounted browses do not collapse into one read.
 *
 * Purpose: the shared single-flight guard holds ONE module-scoped map, so a key that named only the
 * direction and the cursor would make two browses mounted together share a turn -- both open with no
 * cursor and the forward direction. The second would be handed the first's promise, would never issue a
 * read, and would sit empty and loading for ever while the first looked healthy.
 *
 * Assumptions: the two mounts happen with no await between them, which is what keeps both opening reads
 * in flight at once -- the reader holds its answers, and nothing releases a microtask in between.
 * @returns {Promise<void>} Resolves once both browses have published their pages.
 */
async function doesNotCollapseTurnsOfTwoBrowses(): Promise<void> {
  const first = heldReader();
  const second = heldReader();

  const one = mountBrowse(first.read);
  const two = mountBrowse(second.read);

  expect(first.requests, 'the first browse issued its own opening read').toHaveLength(1);
  expect(second.requests, 'the second browse issued its own opening read').toHaveLength(1);

  await act(
    /**
     * Answers both browses' opening reads.
     * @returns {Promise<void>} Resolves once both pages are on display.
     */
    async () => {
      first.release(pageOf(1, false, 'one'));
      second.release(pageOf(2, false, 'two'));
      await Promise.resolve();
    },
  );

  expect(one.result.current.items).toHaveLength(1);
  expect(two.result.current.items).toHaveLength(2);
}

/**
 * Asserts awaiting a turn is enough to observe its page, with no polling.
 *
 * Purpose: this is what the promise is FOR. A screen painting a busy affordance over the pressed key
 * needs to know when to take it away, and the review found the pressed control left `disabled:false`
 * with an unchanged class for the whole flight because there was nothing to know it from.
 *
 * Assumptions: the ordinal is checked BEFORE the answer is released as well as after, so the case proves
 * the promise is unsettled while the turn is in flight rather than merely resolved at some point.
 *
 * Assumptions: no `waitFor` appears after the await, deliberately. A poll would pass whether the promise
 * tracked the turn or resolved immediately, which is the whole property under test.
 * @returns {Promise<void>} Resolves once the awaited turn has settled.
 */
async function resolvesTheTurnOnceThePageIsOnDisplay(): Promise<void> {
  const reader = heldReader();
  const { result } = mountBrowse(reader.read);

  await act(
    /**
     * Answers the opening read.
     * @returns {Promise<void>} Resolves once the opening page is on display.
     */
    async () => {
      reader.release(pageOf(PAGE_SIZE, true, 'p1'));
      await Promise.resolve();
    },
  );

  const turns: Array<Promise<void>> = [];
  act(
    /**
     * Presses the forward key once, holding its turn.
     * @returns {void} Nothing; the turn is collected for awaiting.
     */
    () => {
      turns.push(result.current.nextPage());
    },
  );
  expect(result.current.pageNumber, 'the turn is still in flight').toBe(1);
  expect(result.current.isLoading).toBe(true);

  await act(
    /**
     * Answers the forward read and awaits the turn itself.
     * @returns {Promise<void>} Resolves once the turn has settled.
     */
    async () => {
      reader.release(pageOf(PAGE_SIZE, false, 'p2'));
      await Promise.all(turns);
    },
  );

  expect(result.current.pageNumber).toBe(2);
  expect(result.current.isLoading).toBe(false);
}

/**
 * Asserts a refused step resolves immediately and issues nothing.
 *
 * Assumptions: this is the backward-compatibility half of the change stated as a case. A step at a
 * boundary was a documented no-op that returned nothing, and it is now a documented no-op that returns
 * an already-settled promise -- so a caller that awaits is released at once and a caller that discards
 * sees what it always saw. Awaiting is also what proves the refusal does not REJECT: a rejection would
 * fail this case rather than passing silently into a caller's unhandled-rejection log.
 * @returns {Promise<void>} Resolves once both refused steps have returned.
 */
async function resolvesImmediatelyForARefusedStep(): Promise<void> {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE, false, 'only')], recorded));

  await waitForIdle(result);
  expect(result.current.boundary).toBe('ONLY');

  await act(
    /**
     * Presses both paging keys at a boundary that refuses each of them.
     * @returns {Promise<void>} Resolves once both refusals have returned.
     */
    async () => {
      await result.current.nextPage();
      await result.current.prevPage();
    },
  );

  expect(recorded, 'neither refused step issued a read').toHaveLength(1);
  expect(result.current.isFailed).toBe(false);
  expect(result.current.pageNumber).toBe(1);
}

/**
 * Asserts a browse re-opened after an idling reads again rather than joining the read it abandoned.
 *
 * Purpose: this is the hazard the coalescing key's generation exists for, and it is reachable through the
 * ordinary enablement switch. Lowering the switch idles the browse and makes the outstanding read stale;
 * raising it opens the browse again with no cursor and the forward direction -- which is the SAME
 * direction and position the abandoned read carries. Without a generation in the key, the re-opening turn
 * would be handed the abandoned read's promise, no request would be issued, and the abandoned read's
 * answer would then be dropped by the reducer's sequence guard: an empty browse, nothing outstanding, no
 * failure, and no way for the screen to tell.
 *
 * Assumptions: the stale answer is released FIRST, so the case also confirms it is discarded rather than
 * published -- the rows that arrive are the ones the second read answered with.
 * @returns {Promise<void>} Resolves once the re-opened read has settled.
 */
async function readsAgainAfterIdlingAnOutstandingTurn(): Promise<void> {
  const reader = heldReader();
  const { result, rerender } = mountBrowse(reader.read);

  expect(reader.requests).toHaveLength(1);

  rerender({ pageSize: PAGE_SIZE, fetchPage: reader.read, enabled: false });
  rerender({ pageSize: PAGE_SIZE, fetchPage: reader.read, enabled: true });

  expect(reader.requests, 'the re-opened browse issued a read of its own').toHaveLength(2);

  await act(
    /**
     * Answers the abandoned read and then the re-opened one.
     * @returns {Promise<void>} Resolves once both answers have been applied or dropped.
     */
    async () => {
      reader.release(pageOf(1, true, 'abandoned'));
      reader.release(pageOf(PAGE_SIZE, false, 'reopened'));
      await Promise.resolve();
    },
  );

  expect(result.current.items).toHaveLength(PAGE_SIZE);
  expect(result.current.items[0]).toBe('reopened-row-0');
  expect(result.current.isLoading).toBe(false);
  expect(result.current.isFailed).toBe(false);
}

/** Groups the assertions that fix the shared browse's invariants. */
function keysetBrowseContract(): void {
  it('opens with no cursor and publishes the page', opensWithNoCursorAndPublishesThePage);
  it(
    'carries the problem document of a normalised failure',
    carriesTheProblemDocumentOfANormalisedFailure,
  );
  it(
    'carries a document a rejected reader supplied directly',
    carriesADocumentARejectedReaderSuppliedDirectly,
  );
  it(
    'reports a failure with no document without inventing one',
    reportsAFailureWithNoDocumentWithoutInventingOne,
  );
  it('refuses a page wider than the declared arity', refusesAPageWiderThanTheDeclaredArity);
  it('accepts a page of exactly the declared arity', acceptsAPageOfExactlyTheDeclaredArity);
  it(
    'publishes a filtered-empty page with its boundaries',
    publishesAFilteredEmptyPageWithItsBoundaries,
  );
  it('steps forward from the trailing cursor', stepsForwardFromTheTrailingCursor);
  it('steps backward from the leading cursor', stepsBackwardFromTheLeadingCursor);
  it('names each position as the browse is walked', namesEachPositionAsTheBrowseIsWalked);
  it('names a single page as the only one', namesASinglePageAsTheOnlyOne);
  it('names a nothing-to-show browse as the dead end', namesANothingToShowBrowseAsTheDeadEnd);
  it('does not call a filtered-away page the dead end', doesNotCallAFilteredAwayPageTheDeadEnd);
  it('agrees with the two availability members', agreesWithTheTwoAvailabilityMembers);
  it('issues no read while held back', issuesNoReadWhileHeldBack);
  it('refuses every step while held back', refusesEveryStepWhileHeldBack);
  it('idles the browse when the switch is lowered', idlesTheBrowseWhenTheSwitchIsLowered);
  it(
    'opens at the first page when the switch is raised again',
    opensAtTheFirstPageWhenTheSwitchIsRaisedAgain,
  );
  it('refuses an arity that describes no screen', refusesAnArityThatDescribesNoScreen);
  it(
    'carries the classification of a normalised failure',
    carriesTheClassificationOfANormalisedFailure,
  );
  it('classifies a described refusal as not momentary', classifiesADescribedRefusalAsNotMomentary);
  it(
    'publishes no classification for a non-client rejection',
    publishesNoClassificationForANonClientRejection,
  );
  it('publishes neither channel for a local refusal', publishesNeitherChannelForALocalRefusal);
  it('clears the classification when a read succeeds', clearsTheClassificationWhenAReadSucceeds);
  it('coalesces repeated forward turns into one read', coalescesRepeatedForwardTurns);
  it('issues both directions from one cursor', issuesBothDirectionsFromOneCursor);
  it('does not collapse turns of two browses', doesNotCollapseTurnsOfTwoBrowses);
  it('resolves the turn once the page is on display', resolvesTheTurnOnceThePageIsOnDisplay);
  it('resolves immediately for a refused step', resolvesImmediatelyForARefusedStep);
  it('reads again after idling an outstanding turn', readsAgainAfterIdlingAnOutstandingTurn);
}

describe('keyset browse contract', keysetBrowseContract);
