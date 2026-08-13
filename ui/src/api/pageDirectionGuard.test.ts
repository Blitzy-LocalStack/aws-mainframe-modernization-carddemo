/**
 * @file Unit tests for the shared page-query guard in `ui/src/api/client.ts` and for its application
 * by every paged client in this package.
 *
 * Purpose
 * -------
 * Assert that a reading direction supplied WITHOUT the cursor it would step from is refused before
 * anything is dispatched, in the guard itself and in each of the seven paged operations that call it.
 * Each browse contract declares `direction` meaningful only alongside a cursor and answers 400 keyed
 * on the direction when one arrives alone, so there is no request in which a direction by itself
 * carries meaning.
 *
 * Assumptions: these cases exist because the previous behaviour was to DROP such a direction in
 * silence, which turned a caller defect — a paging handler that failed to thread its cursor through —
 * into a plausible opening page with no diagnostic anywhere. A regression would restore exactly that
 * silence, which no assertion about a request's shape would notice, so the refusal is asserted
 * directly.
 *
 * Assumptions: `./client`'s `getApiClient` is replaced by a factory that throws a plain `Error`, and
 * that substitution is what makes each case prove "refused BEFORE dispatch" rather than merely
 * "rejected". A `RangeError` can only surface if the guard ran first; had the guard been skipped, the
 * dispatch attempt's plain `Error` would reach the assertion instead and fail it, because `Error` is
 * not an instance of `RangeError`. No axios stub is needed, and no case can accidentally pass by
 * reaching a network.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { describe, expect, it, vi } from 'vitest';

// Assumptions: the module's own type is imported as a namespace here rather than written as an inline
// `typeof import('./client')` inside the mock factory below, because ui/eslint.config.js enables
// `@typescript-eslint/consistent-type-imports`, which forbids an inline `import()` type annotation. A
// type-only import is erased at compile time, so it cannot interfere with `vi.mock`'s hoisting.
import type * as ClientModule from './client';
import { listAccountCardCrossReferences } from './accounts';
import { listUsers } from './auth';
import { listPendingAuthorizations } from './authorization';
import { listCards } from './cards';
import { requireCursorForDirection } from './client';
import { listTransactionTypes, listUsStates } from './reference';
import { listTransactionReportLines } from './reporting';
import { listTransactions } from './transactions';

// Assumptions: the module is partially mocked — everything except `getApiClient` is the real
//   implementation, including the guard under test — because a wholly mocked `./client` would replace
//   the very function these cases measure. The factory is self-contained, with its sentinel declared
//   inside it, because `vi.mock` is hoisted above every module-scope binding in this file.
vi.mock(
  './client',
  /**
   * Replaces the axios accessor while leaving every other export, the guard included, untouched.
   * @param {() => Promise<typeof ClientModule>} importOriginal - Loads the real module.
   * @returns {Promise<typeof ClientModule>} The real module with `getApiClient` substituted.
   */
  async (importOriginal) => {
    const actual = await importOriginal<typeof ClientModule>();

    /**
     * Stands in for the axios accessor, failing distinguishably if a client ever reaches it.
     *
     * Assumptions: a plain `Error` and not a `RangeError`, so a case that reaches dispatch fails its
     * `rejects.toThrow(RangeError)` assertion rather than passing for the wrong reason.
     * @returns {never} Never returns; reaching this function is itself the failure being detected.
     * @throws {Error} Always, naming dispatch as the thing that should not have happened.
     */
    function refuseDispatch(): never {
      throw new Error('a paged client dispatched a request instead of refusing the query locally');
    }

    return { ...actual, getApiClient: refuseDispatch };
  },
);

/** An account identifier of admissible width, so no other validation can claim a refusal. */
const ACCOUNT_ID = '00000000011';

/** A stand-in for a sealed cursor; it is replayed verbatim and never parsed, so any text serves. */
const CURSOR = 'opaque-sealed-cursor';

/** The two published range bounds the report read requires, so only the pair under test is at issue. */
const REPORT_RANGE = { startDate: '2022-07-01', endDate: '2022-07-31' } as const;

/*
 * WHY : Assumptions: each of the five invocations below is a HOISTED named function rather than an
 *       inline arrow inside `expect(...)`, because ui/eslint.config.js configures
 *       `jsdoc/require-jsdoc` with `publicOnly: false` and additionally selects
 *       `* > ArrowFunctionExpression`, so an inline thunk owes its own documentation block -- and a
 *       block comment attached to an inline argument is moved by Prettier onto the preceding
 *       expression, which detaches it from what it documents.
 */

/**
 * Invokes the guard with a forward direction and no cursor.
 * @returns {void} Nothing; the guard is expected to throw before returning.
 */
function forwardDirectionWithNoCursor(): void {
  requireCursorForDirection(undefined, 'next');
}

/**
 * Invokes the guard with a backward direction and no cursor.
 * @returns {void} Nothing; the guard is expected to throw before returning.
 */
function backwardDirectionWithNoCursor(): void {
  requireCursorForDirection(undefined, 'previous');
}

/**
 * Invokes the guard with a backward direction and the cursor it steps from.
 * @returns {void} Nothing; the guard admits this pair.
 */
function backwardDirectionWithACursor(): void {
  requireCursorForDirection(CURSOR, 'previous');
}

/**
 * Invokes the guard with neither member, which is how an opening page is read.
 * @returns {void} Nothing; the guard admits this pair.
 */
function neitherCursorNorDirection(): void {
  requireCursorForDirection(undefined, undefined);
}

/**
 * Invokes the guard with a cursor and no direction, which the contract defaults to forward.
 * @returns {void} Nothing; the guard admits this pair.
 */
function cursorWithoutADirection(): void {
  requireCursorForDirection(CURSOR, undefined);
}

/**
 * The guard refuses a forward direction that arrives with no cursor.
 *
 * Assumptions: `next` is asserted as well as `previous`, even though it is the value the contract
 * would have defaulted to anyway. A guard that admitted the default would still hide the caller
 * defect for every forward step, which is the more common one.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function refusesAForwardDirectionWithNoCursor(): void {
  expect(forwardDirectionWithNoCursor).toThrow(RangeError);
}

/**
 * The guard refuses a backward direction that arrives with no cursor, and names it in the message.
 *
 * Assumptions: the message is asserted to quote the offending direction, because the value the caller
 * supplied is the one piece of the diagnostic a caller cannot derive from the call site alone.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function refusesABackwardDirectionWithNoCursorAndNamesIt(): void {
  expect(backwardDirectionWithNoCursor).toThrow(RangeError);
  expect(backwardDirectionWithNoCursor).toThrow(/previous/u);
}

/**
 * The guard admits a direction accompanied by the cursor it steps from.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function admitsADirectionAccompaniedByACursor(): void {
  expect(backwardDirectionWithACursor).not.toThrow();
}

/**
 * The guard admits a query that names neither member, and one that names only a cursor.
 *
 * Assumptions: both admissible absences are asserted in one case because they share a reason — the
 * contract defaults an absent direction to forward — and because a guard that refused either would
 * break every opening page rather than any edge case.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function admitsAnOpeningPageAndACursorWithoutADirection(): void {
  expect(neitherCursorNorDirection).not.toThrow();
  expect(cursorWithoutADirection).not.toThrow();
}

/**
 * The transaction browse refuses a direction with no cursor rather than dropping it.
 * @returns {Promise<void>} Resolves once the rejection has been asserted.
 */
async function theTransactionBrowseRefusesIt(): Promise<void> {
  await expect(listTransactions({ direction: 'previous' })).rejects.toThrow(RangeError);
}

/**
 * The user browse refuses a direction with no cursor.
 * @returns {Promise<void>} Resolves once the rejection has been asserted.
 */
async function theUserBrowseRefusesIt(): Promise<void> {
  await expect(listUsers({ direction: 'previous' })).rejects.toThrow(RangeError);
}

/**
 * The card browse refuses a direction with no cursor, though it carries its criteria in a body.
 *
 * Assumptions: the body-carrying clients are asserted alongside the query-string ones because the
 * dropped direction was duplicated across both shapes, so a guard applied only to the query-string
 * form would have left two operations unchanged.
 * @returns {Promise<void>} Resolves once the rejection has been asserted.
 */
async function theCardBrowseRefusesIt(): Promise<void> {
  await expect(listCards({ direction: 'previous' })).rejects.toThrow(RangeError);
}

/**
 * The pending-authorization browse refuses a direction with no cursor, alongside its required scope.
 * @returns {Promise<void>} Resolves once the rejection has been asserted.
 */
async function thePendingAuthorizationBrowseRefusesIt(): Promise<void> {
  await expect(
    listPendingAuthorizations({ accountId: ACCOUNT_ID, direction: 'previous' }),
  ).rejects.toThrow(RangeError);
}

/**
 * The cross-reference browse refuses a direction with no cursor, and with a BLANK one.
 *
 * Assumptions: the blank case is asserted here and nowhere else because this operation is the only
 * one that treats a blank cursor as no cursor, so it is the only one where "present" could have
 * meant two different things to the caller and to the guard.
 * @returns {Promise<void>} Resolves once both rejections have been asserted.
 */
async function theCrossReferenceBrowseRefusesItIncludingWithABlankCursor(): Promise<void> {
  await expect(listAccountCardCrossReferences(ACCOUNT_ID, undefined, 'previous')).rejects.toThrow(
    RangeError,
  );
  await expect(listAccountCardCrossReferences(ACCOUNT_ID, '', 'previous')).rejects.toThrow(
    RangeError,
  );
}

/**
 * Both routes into the reference module's shared paging assembly refuse a direction with no cursor.
 *
 * Assumptions: two of the module's five browses are exercised rather than one, because they reach the
 * shared assembly by different routes — one through the filter-adding wrapper and one directly — and
 * only exercising both proves the guard covers the wrapper as well as the helper.
 * @returns {Promise<void>} Resolves once both rejections have been asserted.
 */
async function bothReferenceBrowseRoutesRefuseIt(): Promise<void> {
  await expect(listTransactionTypes({ direction: 'previous' })).rejects.toThrow(RangeError);
  await expect(listUsStates({ direction: 'previous' })).rejects.toThrow(RangeError);
}

/**
 * The report read refuses a direction with no cursor, even though its range bounds are supplied.
 *
 * Assumptions: this operation is asserted with its mandatory range present, because that is what made
 * its dropped direction the most misleading of the seven: it answered a fully-populated opening
 * window over the requested dates, which reads as a correct answer rather than as a refused input.
 * @returns {Promise<void>} Resolves once the rejection has been asserted.
 */
async function theReportReadRefusesIt(): Promise<void> {
  await expect(
    listTransactionReportLines({ ...REPORT_RANGE, direction: 'previous' }),
  ).rejects.toThrow(RangeError);
}

/**
 * Registers the four guard cases and the seven paged-client cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function pageDirectionGuardCases(): void {
  it('refuses a forward direction with no cursor', refusesAForwardDirectionWithNoCursor);
  it(
    'refuses a backward direction with no cursor and names it',
    refusesABackwardDirectionWithNoCursorAndNamesIt,
  );
  it('admits a direction accompanied by a cursor', admitsADirectionAccompaniedByACursor);
  it(
    'admits an opening page and a cursor without a direction',
    admitsAnOpeningPageAndACursorWithoutADirection,
  );
  it('is applied by the transaction browse', theTransactionBrowseRefusesIt);
  it('is applied by the user browse', theUserBrowseRefusesIt);
  it('is applied by the card browse', theCardBrowseRefusesIt);
  it('is applied by the pending-authorization browse', thePendingAuthorizationBrowseRefusesIt);
  it(
    'is applied by the cross-reference browse, including with a blank cursor',
    theCrossReferenceBrowseRefusesItIncludingWithABlankCursor,
  );
  it('is applied by both reference browse routes', bothReferenceBrowseRoutesRefuseIt);
  it('is applied by the report read', theReportReadRefusesIt);
}

describe('page direction guard', pageDirectionGuardCases);
