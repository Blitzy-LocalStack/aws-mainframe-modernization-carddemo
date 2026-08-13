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
// ui/vitest.config.ts records `globals` as a per-project contract, and admitting them here would make
// `expect` and `vi` visible to production screens as well, where a stray call would compile.
import { act, renderHook, waitFor } from '@testing-library/react';
import type { RenderHookResult } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '../api/client';
import { usePagedQuery } from '../hooks/usePagedQuery';
import type { PagedQueryRequest, UsePagedQueryResult } from '../hooks/usePagedQuery';
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
 * Takes the forward paging step.
 * @param {BrowseView} view - The live browse result.
 * @returns {void} Nothing; the outcome is observed through the browse.
 */
function stepForward(view: BrowseView): void {
  act(
    /**
     * Invokes the forward step inside a render batch.
     * @returns {void} Nothing; the step dispatches through the hook.
     */
    () => {
      view.current.nextPage();
    },
  );
}

/**
 * Takes the backward paging step.
 * @param {BrowseView} view - The live browse result.
 * @returns {void} Nothing; the outcome is observed through the browse.
 */
function stepBackward(view: BrowseView): void {
  act(
    /**
     * Invokes the backward step inside a render batch.
     * @returns {void} Nothing; the step dispatches through the hook.
     */
    () => {
      view.current.prevPage();
    },
  );
}

/**
 * Takes all three steps in one batch, which is how the held-back case proves each is refused.
 * @param {BrowseView} view - The live browse result.
 * @returns {void} Nothing; every step is expected to do nothing.
 */
function takeEveryStep(view: BrowseView): void {
  act(
    /**
     * Invokes the restart, forward and backward steps together.
     * @returns {void} Nothing; the steps dispatch through the hook.
     */
    () => {
      view.current.reset();
      view.current.nextPage();
      view.current.prevPage();
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

  stepForward(result);
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
  stepForward(result);
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
  stepForward(result);
  await waitForPageNumber(result, 2);
  stepBackward(result);
  await waitForPageNumber(result, 1);

  // Assumptions: the backward step replays the LEADING cursor of the page it was taken from, which is
  //   the reference's own arrangement -- `app/cbl/COCRDLIC.cbl` L1268 moves the current page's first
  //   key into the field its backward browse is positioned from.
  expect(recorded[2]).toEqual({ cursor: 'p2-first', direction: 'previous' });
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
 */
function refusesEveryStepWhileHeldBack(): void {
  const recorded: RecordedRequest[] = [];
  const { result } = mountBrowse(readerAnswering([pageOf(PAGE_SIZE, true, 'p1')], recorded), false);

  takeEveryStep(result);

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
  stepForward(result);
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
  it('issues no read while held back', issuesNoReadWhileHeldBack);
  it('refuses every step while held back', refusesEveryStepWhileHeldBack);
  it('idles the browse when the switch is lowered', idlesTheBrowseWhenTheSwitchIsLowered);
  it(
    'opens at the first page when the switch is raised again',
    opensAtTheFirstPageWhenTheSwitchIsRaisedAgain,
  );
  it('refuses an arity that describes no screen', refusesAnArityThatDescribesNoScreen);
}

describe('keyset browse contract', keysetBrowseContract);
