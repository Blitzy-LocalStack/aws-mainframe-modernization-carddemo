/**
 * @file Proves no account identifier is taken from a request line, and that no superseded list response
 * can attach one account's holder details to another account's rows.
 *
 * Purpose
 * -------
 * Two properties about how an account identifier reaches, and stays on, a screen.
 *
 * The account-view screen used to accept `?accountId=` and pre-fill its filter from it. An eleven-digit
 * account identifier written into a request line reaches the browser's history, the `Referer` header of
 * every subsequent same-origin request and the load balancer's access log before any application code
 * runs, and `app/cpy/CVCUS01Y.cpy` binds an account to a named customer -- so it is the same class of
 * value the card number was taken out of every card route for, registered as `D-CARD-SELECTOR`.
 *
 * The pending-authorization summary screen writes the account holder's name, address and balances from
 * inside the function it hands to the paging hook. The hook discards a page belonging to a superseded
 * read; those two writes happened before the envelope was returned, so they landed regardless. An
 * account-A response settling after an account-B page therefore put account A's holder and balances
 * above account B's rows, attributed to account B.
 *
 * Refactoring Rationale: the summary case drives the SECOND read to settle first, then releases the
 * first, because that is the only ordering that distinguishes a guarded screen from an unguarded one.
 * Letting the two settle in request order passes either way.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it`, `act` and `waitFor` is a
 * NAMED declaration, which is the shape `ui/src/screens/cardScreens.test.tsx` records:
 * `ui/eslint.config.js` selects a function expression in every position, so an inline callback needs its
 * own JSDoc block, and Prettier moves a block comment that follows an argument comma onto the preceding
 * string literal, which detaches the block from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { readAccountView } from '../api/accounts';
import { listPendingAuthorizations } from '../api/authorization';
import type {
  PendingAuthListItem,
  PendingAuthListResponse,
  PendingAuthSummary,
} from '../api/types';
import { AccountViewScreen } from './accountView';
import { AuthSummaryScreen } from './authSummary';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The account operations the screen reaches, each a fresh spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    readAccountView: vi.fn(),
    updateAccount: vi.fn(),
    listAccountCardCrossReferences: vi.fn(),
    isConflictFailure: vi.fn(),
  };
}

/**
 * Builds the mocked surface of the authorization transport module.
 * @returns {Record<string, unknown>} The authorization operations, each a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    listPendingAuthorizations: vi.fn(),
    readPendingAuthorization: vi.fn(),
    markAuthorizationFraud: vi.fn(),
  };
}

/*
 * Assumptions: the transport modules are mocked rather than the HTTP client beneath them, because these
 * cases assert what a screen READS FROM ITS LOCATION and which settlement it applies -- properties of the
 * screens rather than of the interceptor chain.
 */
vi.mock('../api/accounts', mockAccountTransportModule);
vi.mock('../api/authorization', mockAuthorizationTransportModule);

/**
 * How long a wait for an asynchronous condition is given, in milliseconds.
 *
 * Assumptions: this exceeds Testing Library's one-second default deliberately, for the reason
 * `ui/src/screens/cardReadSequencing.test.tsx` records at its own constant: these cases mount a whole
 * screen and the suite runs 30-odd files in parallel workers under a container CPU quota, so a wait that
 * is comfortable in isolation can exceed one second when every worker is busy. A wait only ever ends
 * early on success, so raising the ceiling weakens no assertion.
 */
const ASYNC_CONDITION_TIMEOUT_MS = 5000;

/** The account an operator might try to smuggle in through the request line. */
const URL_SUPPLIED_ACCOUNT = '00000000011';

/** Account whose summary must never be shown beside another account's rows. */
const SUPERSEDED_ACCOUNT = '00000000011';

/** Account the operator ends up scoped to. */
const CURRENT_ACCOUNT = '00000000022';

/**
 * Builds one account summary panel payload.
 * @param {string} accountId - Account the summary describes.
 * @param {string} customerName - Holder name, which is the value each case asserts on.
 * @returns {PendingAuthSummary} The panel payload.
 */
function summaryFor(accountId: string, customerName: string): PendingAuthSummary {
  return {
    accountId,
    customerId: '000000001',
    authStatus: 'A',
    accountStatus1: 'AC',
    accountStatus2: null,
    accountStatus3: null,
    accountStatus4: null,
    accountStatus5: null,
    creditLimit: '5000.00',
    cashLimit: '1000.00',
    creditBalance: '250.00',
    cashBalance: '0.00',
    approvedAuthCnt: 1,
    declinedAuthCnt: 0,
    approvedAuthAmt: '250.00',
    declinedAuthAmt: '0.00',
    customerName,
    addressLine1: '1 EXAMPLE STREET',
    addressLine2: null,
    phoneNumber1: '2065551234',
  };
}

/**
 * Builds one authorization row.
 * @param {string} transactionId - Identifier the row displays.
 * @returns {PendingAuthListItem} The row.
 */
function rowFor(transactionId: string): PendingAuthListItem {
  return {
    key: `fake-auth-selector-${transactionId}`,
    transactionId,
    authOrigDate: '2026-01-10',
    authOrigTime: '10:15:00',
    authType: '01',
    approvalStatus: 'A',
    matchStatus: 'P',
    amount: '250.00',
    cardNum: '************0011',
  };
}

/**
 * Builds one list envelope: a summary, a single-row page and no screen sentence.
 * @param {string} accountId - Account the envelope describes.
 * @param {string} customerName - Holder name the panel would render.
 * @param {string} transactionId - Identifier the single row displays.
 * @returns {PendingAuthListResponse} The envelope.
 */
function envelopeFor(
  accountId: string,
  customerName: string,
  transactionId: string,
): PendingAuthListResponse {
  return {
    summary: summaryFor(accountId, customerName),
    page: {
      items: [rowFor(transactionId)],
      firstKey: `first-${transactionId}`,
      lastKey: `last-${transactionId}`,
      hasNext: false,
    },
    screenMessage: null,
  };
}

/**
 * One promise whose settlement a case controls by hand.
 * @template T Value the promise settles with.
 */
interface Deferred<T> {
  /** The promise a case hands to the transport spy in place of a resolved value. */
  readonly promise: Promise<T>;
  /** Settles that promise with the value given, which is what releases the held read. */
  readonly settle: (value: T) => void;
}

/**
 * Builds a promise whose settlement is deferred until a case asks for it.
 *
 * Assumptions: this is how the ordering case expresses "outstanding". A read that is outstanding has not
 * settled, and a promise that has not resolved is exactly that -- so nothing depends on one response
 * being faster than another, which is the assumption that let the defect read as correct.
 * @template T Value the promise settles with.
 * @returns {Deferred<T>} The promise and the function that settles it.
 */
function deferred<T>(): Deferred<T> {
  /** Resolver of the promise below, replaced the moment the executor runs. */
  let capture: (value: T) => void = ignoreUntilCaptured;

  /**
   * Records the promise's resolver so a case can reach it.
   * @param {(value: T) => void} resolve - The resolver the promise supplies.
   * @returns {void} Nothing; the resolver is recorded above.
   */
  function captureResolver(resolve: (value: T) => void): void {
    capture = resolve;
  }

  /**
   * Settles the promise with the value given.
   * @param {T} value - What the held read answers with.
   * @returns {void} Nothing; the promise settles.
   */
  function settle(value: T): void {
    capture(value);
  }

  return { promise: new Promise<T>(captureResolver), settle };
}

/**
 * Stands in for a resolver until the executor supplies the real one.
 *
 * Assumptions: unreachable in practice, because a promise executor runs synchronously inside the
 * constructor, so the real resolver is in place before `deferred` returns.
 * @returns {void} Nothing.
 */
function ignoreUntilCaptured(): void {
  return undefined;
}

/**
 * Restores the module mocks between cases so one case's answer cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetTransportMocks(): void {
  vi.mocked(readAccountView).mockReset();
  vi.mocked(listPendingAuthorizations).mockReset();
}

/**
 * Asserts the account-view screen ignores an account identifier supplied in the query string.
 *
 * Assumptions: THREE things are asserted, because each is a different way the value could still be
 * honoured -- the control is empty, the value appears nowhere in the rendered document, and no read was
 * issued for it. A screen that pre-filled the control without reading would satisfy the third alone, and
 * one that read without pre-filling would satisfy the first.
 * @returns {Promise<void>} Resolves once the screen has settled.
 */
async function accountViewIgnoresAQueryMember(): Promise<void> {
  render(
    <MemoryRouter initialEntries={[`/account/view?accountId=${URL_SUPPLIED_ACCOUNT}`]}>
      <AccountViewScreen />
    </MemoryRouter>,
  );

  const entry = await screen.findByRole('textbox');

  expect(entry).toHaveValue('');
  expect(screen.queryByDisplayValue(URL_SUPPLIED_ACCOUNT)).toBeNull();
  expect(screen.queryByText(URL_SUPPLIED_ACCOUNT)).toBeNull();
  expect(vi.mocked(readAccountView)).not.toHaveBeenCalled();
}

/**
 * Asserts a superseded list response cannot put its account's holder beside another account's rows.
 *
 * Assumptions: the holder NAME is the value asserted on, because it is the panel member that most
 * directly names a person and it is carried on the same envelope as the rows -- so if the guard is absent
 * it is the first thing to appear against the wrong account.
 * @returns {Promise<void>} Resolves once both settlements have been delivered.
 */
async function authSummaryDiscardsASupersededSummary(): Promise<void> {
  const supersededRead = deferred<PendingAuthListResponse>();
  const currentRead = deferred<PendingAuthListResponse>();

  vi.mocked(listPendingAuthorizations)
    .mockReturnValueOnce(supersededRead.promise)
    .mockReturnValueOnce(currentRead.promise);

  const user = userEvent.setup();

  render(
    <MemoryRouter initialEntries={['/authorizations']}>
      <AuthSummaryScreen />
    </MemoryRouter>,
  );

  const entry = await screen.findByRole('textbox');

  await user.type(entry, SUPERSEDED_ACCOUNT);
  await user.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits until the first read has been issued.
     * @returns {void} Nothing; throws until the call has been recorded.
     */
    () => {
      expect(vi.mocked(listPendingAuthorizations)).toHaveBeenCalledTimes(1);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await user.clear(entry);
  await user.type(entry, CURRENT_ACCOUNT);
  await user.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits until the second read has been issued.
     * @returns {void} Nothing; throws until the second call has been recorded.
     */
    () => {
      expect(vi.mocked(listPendingAuthorizations)).toHaveBeenCalledTimes(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  currentRead.settle(envelopeFor(CURRENT_ACCOUNT, 'CURRENT HOLDER', '0000000000000002'));
  await waitFor(
    /**
     * Waits until the current account's holder is on display.
     * @returns {void} Nothing; throws until the name has rendered.
     */
    () => {
      expect(screen.getByText('CURRENT HOLDER')).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  /*
   * WHY : Assumptions: the superseded settlement is released INSIDE `act`, so every update it schedules
   *       has flushed before the assertions run. Awaiting the promise alone is not enough: the screen's
   *       continuation runs in a later microtask and React may batch the render it schedules, so a
   *       negative assertion would pass against a screen that was about to show the wrong holder.
   */
  await act(
    /**
     * Releases the superseded settlement and lets every update it schedules flush.
     * @returns {Promise<void>} Resolves once the settlement has been delivered.
     */
    async (): Promise<void> => {
      supersededRead.settle(
        envelopeFor(SUPERSEDED_ACCOUNT, 'SUPERSEDED HOLDER', '0000000000000001'),
      );
      await supersededRead.promise;
    },
  );

  expect(screen.getByText('CURRENT HOLDER')).toBeInTheDocument();
  expect(screen.queryByText('SUPERSEDED HOLDER')).toBeNull();
}

/**
 * Registers the two cases.
 * @returns {void} Nothing.
 */
function selectionCarrierCases(): void {
  afterEach(resetTransportMocks);

  it('takes no account identifier from the request line', accountViewIgnoresAQueryMember);
  it(
    'shows no superseded account holder beside current rows',
    authSummaryDiscardsASupersededSummary,
  );
}

describe(
  'the screens carry an account selection without leaking or crossing it',
  selectionCarrierCases,
);
