/**
 * @file Unit tests for the account client in `ui/src/api/accounts.ts`.
 *
 * Purpose
 * -------
 * Pin the two properties of the account-keyed reads that are invisible at a call site and would
 * therefore fail silently: the identifier crosses the wire as digits-only TEXT at the width the
 * operator typed, and a malformed identifier is refused here without the value appearing in the
 * failure.
 *
 * Refactoring Rationale: the first property is the one this file exists for. The account identifier
 * used to be folded into a JavaScript number at the wire boundary, because the published schema
 * declared `type: integer` — so `00000000011` left the browser as `11`, discarding the leading zeroes
 * that belong to the declared eleven-character width, while the module's own documentation said the
 * identifier stayed text. Nothing failed: the service resolved the same row, so the loss was invisible
 * from either side. The schema now declares digits-only text and these cases assert what is actually
 * transmitted, which is the only place that difference is observable.
 *
 * Assumptions: the transport is answered locally by a stub adapter, so a case here asserts what this
 * package SENDS. Whether the service accepts it is that contract's own business, asserted on its side
 * by `AccountControllerTest` and `AccountContextContractTest`.
 *
 * Assumptions: this file carries TWO suites, and the split is deliberate rather than historical.
 * `account identifier transport contract` fixes what the wire carries for an account-keyed read, and
 * `account client behaviour` fixes the target, method and body of every request the module composes
 * plus the refusals it answers without dispatching. They are kept apart because they assert through
 * different seams -- a stub adapter reading the serialised body, and the shared `../test/apiHarness`
 * reading the recorded request -- and the comment above the second suite records why neither seam
 * substitutes for the other.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts records `globals` as a per-project contract, and admitting them here would make
// `expect` and `vi` visible to production screens as well, where a stray call would compile.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { listAccountCardCrossReferences, readAccountView, updateAccount } from './accounts';
import { resetApiClient } from './client';
import {
  answerWith,
  dispatchedRequests,
  installApiHarness,
  onlyRequest,
  pageOf,
  removeApiHarness,
} from '../test/apiHarness';

/** Base URL the client is configured with for this file; no request leaves the process. */
const API_BASE_URL = 'https://api.carddemo.example/api/v1';

/**
 * An account identifier written at the full declared width, with leading zeroes.
 *
 * Assumptions: this specimen is the whole point of the file. Eleven characters of which nine are
 * leading zeroes is exactly the value a numeric wire form cannot carry, so a case built on it fails
 * the moment a conversion is reintroduced anywhere between a screen and the request.
 */
const PADDED_ACCOUNT_KEY = '00000000011';

let sentBody: unknown;

/**
 * Records the body of one dispatched request and answers it without a network call.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success response carrying the same configuration back.
 */
async function captureBodyAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  // Assumptions: what is recorded is the SERIALISED body, because that is what Axios has already made
  //   of it by the time an adapter is reached -- its request transform runs first. That is the stronger
  //   subject in any case: a quoted member is observable proof that no numeric conversion happened
  //   anywhere between the screen and the wire, where an assertion on the composed object would still
  //   pass if a later transform coerced it.
  sentBody = config.data;
  return Promise.resolve({
    data: { items: [], firstKey: null, lastKey: null, hasNext: false },
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  } as AxiosResponse);
}

/** Installs the client configuration and the capturing transport for one case. */
function stubTransport(): void {
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  resetApiClient();
  sentBody = undefined;
}

/** Discards the configuration and the memoised client so no later file inherits either. */
function restoreTransport(): void {
  vi.unstubAllEnvs();
  resetApiClient();
}

/**
 * Dispatches the account-view read and returns the serialised body it transmitted.
 * @param {string} accountId - The identifier to read, exactly as a screen would hold it.
 * @returns {Promise<unknown>} The request body as Axios serialised it for the wire.
 */
async function bodySentByAccountView(accountId: string): Promise<unknown> {
  const { getApiClient } = await import('./client');
  getApiClient().defaults.adapter = captureBodyAdapter;
  await readAccountView(accountId);
  return sentBody;
}

/** Asserts the account view sends the identifier as text, at the width it was given. */
async function sendsTheAccountIdentifierAsText(): Promise<void> {
  expect(await bodySentByAccountView(PADDED_ACCOUNT_KEY)).toBe(
    `{"accountId":"${PADDED_ACCOUNT_KEY}"}`,
  );
}

/** Asserts an identifier shorter than the declared width is sent as given, not padded or converted. */
async function sendsAShortIdentifierUnchanged(): Promise<void> {
  // WHY : the contract admits one to eleven digits and resolves a short value to the same row as its
  //       padded form, so neither padding nor conversion belongs in the browser: doing either here
  //       would send a value the operator did not type and would make a refusal quote a value they
  //       could not find on their screen.
  expect(await bodySentByAccountView('11')).toBe('{"accountId":"11"}');
}

/** Asserts the cross-reference walk composes the same textual body. */
async function walksCrossReferencesWithATextualIdentifier(): Promise<void> {
  const { getApiClient } = await import('./client');
  getApiClient().defaults.adapter = captureBodyAdapter;
  await listAccountCardCrossReferences(PADDED_ACCOUNT_KEY);

  expect(sentBody).toBe(`{"accountId":"${PADDED_ACCOUNT_KEY}"}`);
}

/** Asserts a malformed identifier is refused before a request, and is not quoted in the failure. */
async function refusesAMalformedIdentifierWithoutQuotingIt(): Promise<void> {
  // WHY : an account identifier is one of the values the migration's sensitive-data contract keeps out
  //       of a durable diagnostic, and a thrown message reaches exactly such a store once anything logs
  //       it -- so the refusal describes the accepted form and never reproduces the rejected value.
  await expect(readAccountView('12X45678901')).rejects.toThrow(RangeError);
  await expect(readAccountView('123456789012')).rejects.toThrow(
    'An account identifier must be one to eleven decimal digits.',
  );
  expect(sentBody).toBeUndefined();
}

/** Groups the assertions that fix how an account identifier crosses the wire. */
function accountIdentifierTransportContract(): void {
  beforeEach(stubTransport);
  afterEach(restoreTransport);
  it('sends the account identifier as text at its given width', sendsTheAccountIdentifierAsText);
  it('sends a short identifier unchanged', sendsAShortIdentifierUnchanged);
  it(
    'composes the same textual body for the cross-reference walk',
    walksCrossReferencesWithATextualIdentifier,
  );
  it(
    'refuses a malformed identifier without quoting it',
    refusesAMalformedIdentifierWithoutQuotingIt,
  );
}

describe('account identifier transport contract', accountIdentifierTransportContract);

// ------------------------------------------------------------------------------------------------
// Assumptions: what follows is a second suite over the same module, kept beside the first rather
// than folded into it. The two fix different properties: the block above fixes what the wire
// CARRIES for an account-keyed read -- digits-only text at the operator's width -- while this one
// fixes the SHAPE of every request the client composes and the refusals it answers locally without
// dispatching at all. Alternatives Considered: re-expressing one suite in the other's arrangement,
// so the file held a single harness. Rejected because the two harnesses assert through different
// seams on purpose: the first installs a stub adapter and reads the SERIALISED body, which is the
// only place a numeric coercion is observable, while the second drives the shared
// `../test/apiHarness` and reads the recorded request objects, which is what makes a target and a
// method assertable. Collapsing either into the other would have cost a case rather than a helper.
// ------------------------------------------------------------------------------------------------
/** The revision an account read publishes, in the weak entity-tag form the contract declares. */
const REVISION = 'W/"7"';

/** One cross-reference row, carrying the masked rendering every row is required to carry. */
const XREF_ROW = { cardNumberMasked: '************7065', accountId: 11, customerId: 9 } as const;

/** A minimal account-view body; only the members these assertions read are populated. */
const ACCOUNT_VIEW_BODY = { accountId: '00000000011' } as const;

/** An account edit body carrying one ordinary member and the four sensitive ones. */
const ACCOUNT_EDIT = {
  accountId: '00000000011',
  accountStatus: 'Y',
  ssnPart1: '111',
  ssnPart2: '22',
  ssnPart3: '3333',
  governmentIssuedId: 'X1234567890',
} as const;

/** Asserts the account read posts the identifier in a body as the integer the contract declares. */
async function readsAnAccountThroughABody(): Promise<void> {
  answerWith(ACCOUNT_VIEW_BODY, 200, { etag: REVISION });

  const outcome = await readAccountView('00000000011');

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/accounts/view');
  // Refactoring Rationale: the body carries the identifier as TEXT at the width it was given, where
  //   this case expected the number 11. `account-api.yaml` declares `AccountLookupRequest.accountId`
  //   as a string with pattern ^[0-9]{1,11}$, and the suite above exists for precisely this member:
  //   folding it into a number discards the leading zeroes of the declared eleven-character width
  //   while resolving the same row, so nothing failed and the loss was invisible from either side.
  expect(request.body).toEqual({ accountId: PADDED_ACCOUNT_KEY });
  expect(outcome.revision).toBe(REVISION);
}

/**
 * Asserts no account identifier reaches the request target of any operation.
 *
 * Assumptions: what is asserted is that the composed target carries NO DIGIT at all, which is stronger
 * than checking one identifier is absent from it. A target is recorded in full by the edge access log and
 * retained by the browser's history, so a digit-free target cannot carry a selector by accident.
 */
async function keepsEverySelectorOutOfTheTarget(): Promise<void> {
  answerWith(ACCOUNT_VIEW_BODY);
  await readAccountView('00000000011');
  answerWith(pageOf([]));
  await listAccountCardCrossReferences('00000000011');

  for (const request of dispatchedRequests()) {
    expect(request.url, `${request.url} must carry no identifier`).not.toMatch(/[0-9]/u);
  }
}

/** Asserts a malformed identifier is refused locally, before any request is made. */
async function refusesAMalformedIdentifierLocally(): Promise<void> {
  await expect(readAccountView('')).rejects.toThrow(RangeError);
  await expect(readAccountView('123456789012')).rejects.toThrow(RangeError);
  await expect(readAccountView('1234A')).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/**
 * Asserts an edit carries the revision as a precondition header rather than as a body member.
 *
 * Assumptions: the header name and the body are asserted together, because the two carry different
 * things: the header is what makes the write conditional, and the body must NOT restate it or a request
 * could name two revisions.
 */
async function submitsAnEditUnderItsPrecondition(): Promise<void> {
  answerWith({ accountId: '00000000011' }, 200, { etag: 'W/"8"' });

  const outcome = await updateAccount(ACCOUNT_EDIT, REVISION);

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/accounts/update');
  expect(request.headers['if-match']).toBe(REVISION);
  expect(request.body).toEqual(ACCOUNT_EDIT);
  expect(outcome.revision).toBe('W/"8"');
}

/**
 * Asserts an edit with no revision is refused locally.
 *
 * ⚠️ Assumptions: refusing here is what keeps a lost revision from being reported as somebody else's
 * edit. This claimed the contract answers a conditionless write with 428, and no operation in any of the
 * seven contracts publishes that status. What the service actually does is send the header blank —
 * `updateAccount` always sets it — and `requireCurrentRevision` in `AccountUpdateService` tests
 * `expectedRevision.isBlank()` before comparing, raising the same stale-version conflict a genuinely
 * outdated token raises. So the answer is 409 with the changed-record sentence, which is a FALSE conflict:
 * the operator is sent to look for a concurrent change nobody made. The second assertion below is
 * therefore part of the claim rather than incidental — nothing is dispatched, so the hundred-field body is
 * never assembled and no conflict is ever reported.
 */
async function refusesAnEditWithNoRevision(): Promise<void> {
  await expect(updateAccount(ACCOUNT_EDIT, '')).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts the cross-reference browse sends the identifier in the body and the cursor in the query. */
async function browsesCrossReferencesByKey(): Promise<void> {
  answerWith(pageOf([XREF_ROW], true));

  await listAccountCardCrossReferences('00000000011', 'opaque-token', 'previous');

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/accounts/card-cross-references/search');
  // Refactoring Rationale: text rather than the number 11, for the reason recorded on
  //   {@link readsAnAccountThroughABody} -- the published request schema declares a digit string.
  expect(request.body).toEqual({ accountId: PADDED_ACCOUNT_KEY });
  expect(request.params).toEqual({ cursor: 'opaque-token', direction: 'previous' });
}

/** Asserts an opening cross-reference read carries neither paging member. */
async function readsTheOpeningCrossReferencePageWithoutPaging(): Promise<void> {
  answerWith(pageOf([XREF_ROW]));

  await listAccountCardCrossReferences('00000000011');

  expect(onlyRequest().params).toEqual({});
}

/** Asserts a blank cursor is treated as no cursor, as the service treats it. */
async function treatsABlankCursorAsNone(): Promise<void> {
  answerWith(pageOf([XREF_ROW]));

  await listAccountCardCrossReferences('00000000011', '');

  expect(onlyRequest().params).toEqual({});
}

/**
 * Asserts a direction with no usable cursor is refused rather than dropped.
 *
 * Assumptions: the blank-cursor form is asserted as well as the absent one, because a blank cursor is no
 * position either -- dropping the direction there would answer the opening page to a caller that asked to
 * step backward, which is the defect this guard closes.
 */
async function refusesADirectionWithNoUsableCursor(): Promise<void> {
  await expect(
    listAccountCardCrossReferences('00000000011', undefined, 'previous'),
  ).rejects.toThrow(RangeError);
  await expect(listAccountCardCrossReferences('00000000011', '', 'next')).rejects.toThrow(
    RangeError,
  );
  expect(dispatchedRequests()).toHaveLength(0);
}

/**
 * Asserts a cross-reference row carrying an unmasked card number is refused.
 *
 * Assumptions: the page is REJECTED rather than filtered, because a list is the shape that would disclose
 * a primary account number per row and a partially-rendered list hides the fault from the operator.
 */
async function refusesAnUnmaskedRow(): Promise<void> {
  answerWith(pageOf([{ ...XREF_ROW, cardNumberMasked: '4859452612877065' }]));

  await expect(listAccountCardCrossReferences('00000000011')).rejects.toThrow(RangeError);
}

/**
 * Registers every account client case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function accountClientBehaviour(): void {
  beforeEach(installApiHarness);
  afterEach(removeApiHarness);
  it('reads an account through a body', readsAnAccountThroughABody);
  it('keeps every selector out of the target', keepsEverySelectorOutOfTheTarget);
  it('refuses a malformed identifier locally', refusesAMalformedIdentifierLocally);
  it('submits an edit under its precondition', submitsAnEditUnderItsPrecondition);
  it('refuses an edit with no revision', refusesAnEditWithNoRevision);
  it('browses cross-references by key', browsesCrossReferencesByKey);
  it(
    'reads the opening cross-reference page without paging',
    readsTheOpeningCrossReferencePageWithoutPaging,
  );
  it('treats a blank cursor as none', treatsABlankCursorAsNone);
  it('refuses a direction with no usable cursor', refusesADirectionWithNoUsableCursor);
  it('refuses an unmasked row', refusesAnUnmaskedRow);
}

describe('account client behaviour', accountClientBehaviour);
