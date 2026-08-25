/**
 * @file Fixes the two properties of the shared keyset browse that no other file can settle: that a
 * failure arriving from the real transport is surfaced with the service's own problem document, and
 * that a change of QUERY drops the previous query's page instead of leaving it on display.
 *
 * Refactoring Rationale: a THIRD property joined the two below -- that one mount issues exactly ONE
 * opening read, and that a re-render which changes nothing this hook depends on issues none. A review
 * measured duplicate requests on three screens and asked whether the shared browse was the source; it
 * is not, because the reader is held in a ref refreshed by a dependency-free effect and the opening
 * effect depends only on the switch and the restart value. Nothing pinned that, though, and the risk is
 * specific: every browse screen composes its reader INLINE, so a reader in the opening effect's
 * dependency list would re-read on every render and turn this hook into the duplicate source it was
 * suspected of being. The case below fails on exactly that change.
 *
 * Refactoring Rationale: this file did not exist, and both properties were broken while every other
 * gate stayed green. `problemDocumentOf` examined the rejected value itself and a nested
 * `response.data`, on the belief that `ui/src/api/client.ts` re-throws whatever axios rejected with;
 * it does not -- its response interceptor normalises every failure into one `ApiRequestError` and
 * throws that, carrying the document on a `problem` member. So no route matched, `error` settled
 * `null` on every real refusal, and `isFailed` still went true, which is why nothing looked wrong.
 * And a change of the restart value dispatched the transition that KEEPS rows and cursors, so a
 * screen whose criteria changed kept the previous query's rows under the new criteria -- and kept
 * them again when the new query's opening read failed.
 *
 * Assumptions: the failure cases drive the REAL client, with the network answered by a local adapter,
 * rather than constructing an `ApiRequestError` by hand. The defect was a disagreement BETWEEN two
 * files about the shape one of them throws, so a hand-built failure would have encoded this file's
 * belief about that shape and could have agreed with the hook while disagreeing with the transport --
 * which is exactly the state the two were already in. Routing through the interceptor removes the
 * belief from the test.
 *
 * Alternatives Considered: asserting these two properties from a screen test instead, since every
 * browse screen consumes this hook. Rejected because a screen renders one query and never changes its
 * criteria mid-test, so the identity-change transition would never be reached; and a screen test
 * would have to stub the client module, which puts the shape belief straight back.
 *
 * Assumptions: every reader and every render callback below is a NAMED function rather than an arrow
 * written at its call site, and each inline callback handed to `waitFor` or `act` carries its own
 * one-line block. `ui/eslint.config.js` sets `publicOnly: false` on `jsdoc/require-jsdoc`, so the
 * gate reaches a module-private helper and an inline closure alike; the naming follows the gate
 * rather than working around it, and matches `src/screens/cardScreenShell.test.tsx`.
 */

import { act, renderHook, waitFor } from '@testing-library/react';
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient, resetApiClient } from '../api/client';
import type { ApiError, PageResponse } from '../api/types';
import type { UsePagedQueryResult } from './usePagedQuery';
import { usePagedQuery } from './usePagedQuery';

/**
 * The base address the client is configured with; no request leaves the process.
 *
 * Assumptions: it carries the `/api/v1` operation prefix because `normalizeApiBaseUrl` requires it,
 * and the prefix changes no assertion here because the cases assert relative request paths.
 */
const API_BASE_URL = 'https://api.carddemo.example/api/v1';

/** The correlation header name the client is configured to send and read back. */
const CORRELATION_HEADER = 'X-Correlation-Id';

/** The row arity every browse in this file declares, standing for a list screen's row count. */
const ROWS_PER_PAGE = 7;

/** The correlation identifier the answering adapter echoes, as an operator would quote it. */
const CORRELATION_ID = '11111111-2222-3333-4444-555555555555';

/** The verbatim sentence the refusing service sends, which a screen renders unchanged. */
const REFUSAL_MESSAGE = 'Account ID must be an 11-digit number';

/** The code the refusing service sends, which an investigation correlates on. */
const REFUSAL_CODE = 'CARDDEMO-VALIDATION';

/** The status the refusing service answers with, which the interceptor classifies from. */
const REFUSAL_STATUS = 400;

/** The ordinal of the opening page, which a cleared browse must return to. */
const FIRST_PAGE = 1;

/** The single row every satisfied read in this file delivers, and the key both cursors carry. */
const ONLY_ROW_KEY = 'A';

/**
 * One problem document shaped exactly as the shared advice in common-lib emits it.
 *
 * Assumptions: all eleven members are present, with `abend` carried as `null` rather than omitted,
 * because `ApiErrorWireShapeTest` in `services/common-lib` pins that shape and `isApiError` in
 * `ui/src/api/client.ts` checks it member by member. A document short of one member would be
 * classified `RESPONSE` by the interceptor and replaced with a synthesised one, so this fixture being
 * the real shape is what makes the cases below exercise the `PROBLEM` route rather than a fallback.
 */
const SERVICE_PROBLEM: ApiError = {
  code: REFUSAL_CODE,
  secondaryCode: '',
  message: REFUSAL_MESSAGE,
  severity: 'WARNING',
  subsystem: 'APPLICATION',
  status: REFUSAL_STATUS,
  correlationId: CORRELATION_ID,
  path: '/api/v1/accounts',
  timestamp: '2022-07-18 22:10:31.000000',
  fieldErrors: [{ field: 'accountId', state: 'NOT_OK', message: 'Account ID must be numeric' }],
  abend: null,
};

/** One row of a page, standing for whatever a browse operation returns. */
interface Row {
  /** The row's key, which is also what both cursors name. */
  readonly key: string;
}

/** Whether the reader refuses its next read, so one case can fail a read it earlier satisfied. */
let refusing = false;

/** The failure the real interceptor produced, held so a reader can reject with that exact value. */
let normalisedFailure: Error = new Error('no transport failure has been produced yet');

/**
 * Answers every request with the refusal above, so the rejection travels the real interceptor.
 *
 * Assumptions: this adapter SETTLES the refusal itself, rejecting with the `AxiosError` that carries
 * the response, rather than resolving with a 4xx and expecting Axios to convert it. That is what a
 * real adapter does -- `validateStatus` is applied inside the adapter, not by the dispatcher -- and it
 * was measured here: an earlier version of this file resolved with the 400 and every case failed,
 * because the request completed successfully and the rejection interceptor under test never ran. The
 * error is built exactly as Axios builds it, from the message, the bad-request code, the request
 * configuration and the response, so the interceptor receives nothing this file invented.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} Never resolves; rejects with the refusal Axios would have built.
 */
function refusingAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const response = {
    data: SERVICE_PROBLEM,
    status: REFUSAL_STATUS,
    statusText: 'Bad Request',
    headers: { [CORRELATION_HEADER.toLowerCase()]: CORRELATION_ID },
    config,
  } as AxiosResponse;
  return Promise.reject(
    new AxiosError(
      `Request failed with status code ${String(REFUSAL_STATUS)}`,
      AxiosError.ERR_BAD_REQUEST,
      response.config,
      undefined,
      response,
    ),
  );
}

/**
 * Issues one request through the real client and holds whatever its interceptor threw.
 *
 * Assumptions: the caught value is narrowed to `Error` before it is held, and the narrowing is an
 * assertion rather than a convenience. The interceptor's own contract is that it throws an
 * `ApiRequestError`, which extends `Error`, so a value failing this check would mean the transport had
 * stopped normalising -- and every case below would then be exercising a route production no longer
 * takes. Holding it as `Error` is also what lets a reader reject with it without widening to
 * `unknown`, which `@typescript-eslint/prefer-promise-reject-errors` refuses for good reason.
 * @returns {Promise<void>} Resolves once the failure has been produced and held.
 * @throws {Error} If the request resolves, or rejects with something that is not an `Error`.
 */
async function produceNormalisedFailure(): Promise<void> {
  const client = getApiClient();
  client.defaults.adapter = refusingAdapter;
  try {
    await client.get('/api/v1/accounts');
  } catch (rejected: unknown) {
    if (!(rejected instanceof Error)) {
      throw new Error('the transport rejected with a value that is not an Error');
    }
    normalisedFailure = rejected;
    return;
  }
  throw new Error('the refusing adapter resolved, so no failure was produced to hand to the hook');
}

/**
 * Builds one page envelope over a single row.
 * @returns {PageResponse<Row>} The envelope, with its four members and no more.
 */
function onePage(): PageResponse<Row> {
  return {
    items: [{ key: ONLY_ROW_KEY }],
    firstKey: ONLY_ROW_KEY,
    lastKey: ONLY_ROW_KEY,
    hasNext: true,
  };
}

/**
 * Reads one page, or refuses with the held transport failure when the switch is set.
 * @returns {Promise<PageResponse<Row>>} The page, or a rejection carrying the normalised failure.
 */
function switchableReader(): Promise<PageResponse<Row>> {
  return refusing ? Promise.reject(normalisedFailure) : Promise.resolve(onePage());
}

/**
 * Reads nothing and always refuses with the held transport failure.
 * @returns {Promise<PageResponse<Row>>} A rejection carrying the normalised failure.
 */
function alwaysRefusingReader(): Promise<PageResponse<Row>> {
  return Promise.reject(normalisedFailure);
}

/**
 * Refuses with a plain error, standing for an edge that answered with its own page.
 * @returns {Promise<PageResponse<Row>>} A rejection carrying no problem document at all.
 */
function documentlessReader(): Promise<PageResponse<Row>> {
  return Promise.reject(new Error('the edge answered with its own page'));
}

/**
 * Renders the browse over the always-refusing reader, with no restart value.
 * @returns {UsePagedQueryResult<Row>} The browse's result for this render.
 */
function renderRefusedBrowse(): UsePagedQueryResult<Row> {
  return usePagedQuery<Row>({ pageSize: ROWS_PER_PAGE, fetchPage: alwaysRefusingReader });
}

/**
 * Renders the browse over the reader that rejects without a document.
 * @returns {UsePagedQueryResult<Row>} The browse's result for this render.
 */
function renderDocumentlessBrowse(): UsePagedQueryResult<Row> {
  return usePagedQuery<Row>({ pageSize: ROWS_PER_PAGE, fetchPage: documentlessReader });
}

/**
 * Renders the browse over the switchable reader under the restart value the props carry.
 * @param {object} props - The render's props.
 * @param {string} props.resetKey - The query identity this render is browsing.
 * @returns {UsePagedQueryResult<Row>} The browse's result for this render.
 */
function renderKeyedBrowse(props: { readonly resetKey: string }): UsePagedQueryResult<Row> {
  return usePagedQuery<Row>({
    pageSize: ROWS_PER_PAGE,
    resetKey: props.resetKey,
    fetchPage: switchableReader,
  });
}

/** How many reads the counting reader has been asked for, so a duplicate is visible as a number. */
let readsIssued = 0;

/**
 * Renders the browse over a reader composed INLINE, as every browse screen composes it.
 *
 * Assumptions: the closure is written at the call site deliberately, and recreating it on every render
 * is the whole point -- a memoised reader would pass this file's case while every real screen's inline
 * one still re-read, which is the disagreement the case exists to prevent.
 * @param {object} props - The render's props.
 * @param {string} props.resetKey - The query identity this render is browsing.
 * @returns {UsePagedQueryResult<Row>} The browse's result for this render.
 */
function renderInlineReaderBrowse(props: { readonly resetKey: string }): UsePagedQueryResult<Row> {
  return usePagedQuery<Row>({
    pageSize: ROWS_PER_PAGE,
    resetKey: props.resetKey,
    /**
     * Reads one page and counts the read.
     * @returns {Promise<PageResponse<Row>>} The single-row page every satisfied read delivers.
     */
    fetchPage: (): Promise<PageResponse<Row>> => {
      readsIssued += 1;
      return Promise.resolve(onePage());
    },
  });
}

/** Configures the build-time values the client validates, and discards any earlier client. */
function stubBuildConfiguration(): void {
  refusing = false;
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', CORRELATION_HEADER);
  resetApiClient();
}

/** Discards the stubbed environment and the client so no later file inherits either. */
function restoreBuildConfiguration(): void {
  refusing = false;
  vi.unstubAllEnvs();
  resetApiClient();
}

/**
 * A failure the real transport produced is surfaced with the service's own document.
 *
 * Assumptions: the members asserted are the ones an operator and a form act on -- the verbatim
 * sentence, the correlation identifier quoted to support, the code an investigation correlates on,
 * and the field-error array a form marks its fields from. Asserting only that `error` is non-null
 * would pass for a synthesised document carrying none of them.
 * @returns {Promise<void>} Resolves once the browse has settled its opening read.
 */
async function surfacesTheServiceProblemDocument(): Promise<void> {
  await produceNormalisedFailure();

  const { result } = renderHook(renderRefusedBrowse);

  await waitFor(
    /**
     * Waits for the opening read to have been refused.
     * @returns {void} Nothing; throws until the browse reports failure.
     */
    () => {
      expect(result.current.isFailed).toBe(true);
    },
  );

  expect(result.current.error).not.toBeNull();
  expect(result.current.error?.message).toBe(REFUSAL_MESSAGE);
  expect(result.current.error?.correlationId).toBe(CORRELATION_ID);
  expect(result.current.error?.code).toBe(REFUSAL_CODE);
  expect(result.current.error?.fieldErrors).toHaveLength(1);
  expect(result.current.error?.fieldErrors[0]?.field).toBe('accountId');
  expect(result.current.isLoading).toBe(false);
}

/**
 * A rejection carrying no document at all still reports failure, and invents nothing.
 * @returns {Promise<void>} Resolves once the browse has settled its opening read.
 */
async function reportsAFailureThatCarriedNoDocument(): Promise<void> {
  const { result } = renderHook(renderDocumentlessBrowse);

  await waitFor(
    /**
     * Waits for the opening read to have failed.
     * @returns {void} Nothing; throws until the browse reports failure.
     */
    () => {
      expect(result.current.isFailed).toBe(true);
    },
  );
  expect(result.current.error).toBeNull();
}

/**
 * A change of query drops the previous query's page, even when the new opening read fails.
 *
 * Assumptions: the NEW query's read is made to fail, because a read that succeeds replaces the rows
 * anyway and the case would then pass whether the clearing transition existed or not. A failing new
 * query is the only arrangement in which retained rows would stay on display, which is the state
 * being ruled out.
 *
 * Assumptions: the ordinal is asserted alongside the rows. It is the value a backward step is refused
 * on, so a browse that cleared its rows and kept its ordinal would offer a backward step from the
 * first page of the new set.
 * @returns {Promise<void>} Resolves once both queries have settled.
 */
async function clearsThePreviousQueryOnAnIdentityChange(): Promise<void> {
  await produceNormalisedFailure();

  const { result, rerender } = renderHook(renderKeyedBrowse, {
    initialProps: { resetKey: 'account=1' },
  });

  await waitFor(
    /**
     * Waits for the first query's page to be on display.
     * @returns {void} Nothing; throws until the row has arrived.
     */
    () => {
      expect(result.current.items).toHaveLength(1);
    },
  );
  expect(result.current.hasNext).toBe(true);

  refusing = true;
  rerender({ resetKey: 'account=2' });

  await waitFor(
    /**
     * Waits for the second query's opening read to have been refused.
     * @returns {void} Nothing; throws until the browse reports failure.
     */
    () => {
      expect(result.current.isFailed).toBe(true);
    },
  );

  expect(result.current.items).toHaveLength(0);
  expect(result.current.hasNext).toBe(false);
  expect(result.current.hasPrev).toBe(false);
  expect(result.current.pageNumber).toBe(FIRST_PAGE);
  expect(result.current.error?.correlationId).toBe(CORRELATION_ID);
}

/**
 * A refresh of the SAME query keeps its page on display when the refresh fails.
 *
 * Assumptions: this is the retention the clearing transition was deliberately NOT allowed to remove,
 * so it is asserted here rather than left implied. A caller reaches for the reset after a mutation,
 * and the page it is refreshing is still an answer to the same question -- the reference behaves the
 * same way, re-sending the screen it has already composed.
 * @returns {Promise<void>} Resolves once the refresh has failed and the page has been re-examined.
 */
async function retainsThePageAcrossASameQueryRefresh(): Promise<void> {
  await produceNormalisedFailure();

  const { result } = renderHook(renderKeyedBrowse, {
    initialProps: { resetKey: 'account=1' },
  });

  await waitFor(
    /**
     * Waits for the query's page to be on display.
     * @returns {void} Nothing; throws until the row has arrived.
     */
    () => {
      expect(result.current.items).toHaveLength(1);
    },
  );

  refusing = true;
  await act(
    /**
     * Asks the browse to refresh the query it is already on, and waits for that turn.
     *
     * Refactoring Rationale: the refresh is AWAITED, where this call used to be made and left. The step
     * now resolves when its turn has settled, so awaiting it is what sequences this case -- and it is
     * the only discard `ui/eslint.config.js` admits, since `no-floating-promises` is configured with
     * `ignoreVoid: false`.
     * @returns {Promise<void>} Resolves once the refused refresh has settled.
     */
    async () => {
      await result.current.reset();
    },
  );

  await waitFor(
    /**
     * Waits for the refresh to have been refused.
     * @returns {void} Nothing; throws until the browse reports failure.
     */
    () => {
      expect(result.current.isFailed).toBe(true);
    },
  );

  expect(result.current.items).toHaveLength(1);
  expect(result.current.items[0]?.key).toBe(ONLY_ROW_KEY);
  expect(result.current.hasNext).toBe(true);
  expect(result.current.pageNumber).toBe(FIRST_PAGE);
}

/**
 * One mount issues one opening read, an unrelated re-render issues none, and a new query issues one.
 *
 * Assumptions: the count is asserted at three points rather than once at the end, because the three
 * numbers say three different things and only together do they describe the contract: that the browse
 * opens, that it does not re-open for a render, and that it DOES re-open for a genuine change of
 * criteria. A single final count would pass for a hook that read twice on mount and never again.
 *
 * Assumptions: two consecutive re-renders are performed with the same restart value, not one, because
 * a single re-render cannot distinguish "does not re-read on a render" from "re-reads on alternate
 * renders" -- and a stale-closure refresh that dispatched a read would be visible on the second.
 * @returns {Promise<void>} Resolves once the browse has settled every read this case causes.
 */
async function issuesOneReadPerQueryAndNoneForARender(): Promise<void> {
  readsIssued = 0;

  const { result, rerender } = renderHook(renderInlineReaderBrowse, {
    initialProps: { resetKey: 'accountId=' },
  });

  await waitFor(
    /**
     * Waits for the opening read to have delivered its page.
     * @returns {void} Nothing; throws until the row is on display.
     */
    () => {
      expect(result.current.items).toHaveLength(1);
    },
  );
  expect(readsIssued, 'the mount issued exactly one opening read').toBe(1);

  rerender({ resetKey: 'accountId=' });
  rerender({ resetKey: 'accountId=' });

  await waitFor(
    /**
     * Waits for both re-renders to have been committed with the page still on display.
     * @returns {void} Nothing; throws until the row is on display.
     */
    () => {
      expect(result.current.items).toHaveLength(1);
    },
  );
  expect(readsIssued, 'a render that changed nothing issued no read').toBe(1);

  rerender({ resetKey: 'accountId=00000000011' });

  await waitFor(
    /**
     * Waits for the changed query to have issued its own opening read.
     * @returns {void} Nothing; throws until the second read has been asked for.
     */
    () => {
      expect(readsIssued).toBe(2);
    },
  );
  expect(readsIssued, 'a changed query issued exactly one further read').toBe(2);
}

/**
 * The classification the REAL interceptor made survives the trip through the browse.
 *
 * Purpose: the sibling case next to this one proves the problem document survives; this proves the
 * judgement about it does. The two reviews that measured the defect found the consequence on the
 * production route -- a timeout, a dropped connection and a 500 indistinguishable on every screen -- so
 * the case that closes it has to run on the production route too, through the real client's own response
 * interceptor rather than a failure this file constructed.
 *
 * Assumptions: `kind` is `PROBLEM` and `transient` is false because the adapter answers 400 with a
 * problem document, which is a refusal the operator must act on rather than a condition that clears.
 * Asserting the remedy members and not only the kind is deliberate: a screen chooses between "not
 * available at the moment" and "that did not work" on `transient` alone.
 * @returns {Promise<void>} Resolves once the browse has settled its opening read.
 */
async function surfacesTheClassificationTheInterceptorMade(): Promise<void> {
  await produceNormalisedFailure();

  const { result } = renderHook(renderRefusedBrowse);

  await waitFor(
    /**
     * Waits for the opening read to have been refused.
     * @returns {void} Nothing; throws until the browse reports failure.
     */
    () => {
      expect(result.current.isFailed).toBe(true);
    },
  );

  expect(
    result.current.failure,
    'the interceptor classified this and the browse kept it',
  ).not.toBeNull();
  expect(result.current.failure?.kind).toBe('PROBLEM');
  expect(result.current.failure?.status).toBe(REFUSAL_STATUS);
  expect(result.current.failure?.transient).toBe(false);
  expect(result.current.failure?.repeatable).toBe(false);
  expect(result.current.failure?.correlationId).toBe(CORRELATION_ID);
  // Assumptions: the document is asserted alongside, so a fix that replaced `error` with the classified
  //   failure rather than adding it beside would fail here rather than in five screens later.
  expect(result.current.error?.message).toBe(REFUSAL_MESSAGE);
}

/**
 * A turn that FAILS still resolves, so a caller that discards its promise leaves nothing unhandled.
 *
 * Purpose: this is the backward-compatibility property of making the three steps awaitable, stated as a
 * case rather than as prose. Seven browse screens call these steps as bare statements today and only
 * some will adopt the promise; if a failed turn rejected, every screen that had not adopted it would
 * report an unhandled rejection for a failure this hook has already recorded and displayed -- a
 * regression introduced by the fix rather than by the defect.
 *
 * Assumptions: the failure is produced through the REAL client, as every case in this file is, so what
 * is proved is that a genuine transport rejection does not escape the settlement. A hand-built rejection
 * would prove only that this file can build one.
 *
 * Assumptions: the outcome is recorded through BOTH handlers into a list rather than asserted with
 * `resolves`/`rejects`, because the property is which of the two ran -- and a list that names it makes
 * the failure message say `[ 'rejected' ]` instead of a bare boolean.
 * @returns {Promise<void>} Resolves once the failed turn has settled.
 */
async function resolvesAFailedTurnRatherThanRejectingIt(): Promise<void> {
  await produceNormalisedFailure();

  const { result } = renderHook(renderRefusedBrowse);

  await waitFor(
    /**
     * Waits for the opening read to have been refused, so a further turn has a browse to run in.
     * @returns {void} Nothing; throws until the browse reports failure.
     */
    () => {
      expect(result.current.isFailed).toBe(true);
    },
  );

  const howItSettled: string[] = [];
  await act(
    /**
     * Refreshes the browse over the always-refusing reader and records which handler ran.
     * @returns {Promise<void>} Resolves once the refused turn has settled either way.
     */
    async () => {
      await result.current.reset().then(
        /**
         * Records that the turn resolved.
         * @returns {void} Nothing; the outcome is appended.
         */
        () => {
          howItSettled.push('resolved');
        },
        /**
         * Records that the turn rejected, which is the outcome this case rules out.
         * @returns {void} Nothing; the outcome is appended.
         */
        () => {
          howItSettled.push('rejected');
        },
      );
    },
  );

  expect(howItSettled).toEqual(['resolved']);
  expect(result.current.isFailed).toBe(true);
  expect(result.current.error?.correlationId).toBe(CORRELATION_ID);
}

/**
 * Groups the cases that fix the failure contract and the query-identity contract.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function browseFailureAndResetContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it(
    'surfaces the problem document a real transport failure carried',
    surfacesTheServiceProblemDocument,
  );
  it(
    'reports a failure that carried no document without inventing one',
    reportsAFailureThatCarriedNoDocument,
  );
  it(
    'clears the previous query page when the query identity changes',
    clearsThePreviousQueryOnAnIdentityChange,
  );
  it('retains the page across a refresh of the same query', retainsThePageAcrossASameQueryRefresh);
  it('issues one read per query and none for a re-render', issuesOneReadPerQueryAndNoneForARender);
  it(
    'surfaces the classification the interceptor made',
    surfacesTheClassificationTheInterceptorMade,
  );
  it('resolves a failed turn rather than rejecting it', resolvesAFailedTurnRatherThanRejectingIt);
}

describe('shared keyset browse failure and reset contract', browseFailureAndResetContract);
