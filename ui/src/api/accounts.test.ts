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
// ui/tsconfig.json keeps `types` EMPTY, and DECLARING them here would make `expect` and `vi`
// visible to production screens as well, where a stray call would compile. (ui/vitest.config.ts
// sets `globals: true`; an injected global is not a declared one, so the import still carries the
// compiler's side of this.)
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

/**
 * The two protected customer members every lawful account-view response carries.
 *
 * Assumptions: the value is the fixed marker `CustomerMapper.IDENTIFIER_REDACTED` publishes and the
 * contract now pins with a pattern on all four of its declarations. It is spelled here rather than
 * imported because the client module keeps its own pattern private -- and a fixture that imported the
 * expectation it is measured against could not fail.
 */
const REDACTED_CUSTOMER = {
  ssnMasked: '[REDACTED]',
  governmentIssuedIdMasked: '[REDACTED]',
} as const;

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
  // Assumptions: the answer carries BOTH the paging members a cross-reference walk reads and the
  //   redacted customer an account-view read validates, because one adapter answers both operations and
  //   `readAccountView` now refuses a response whose protected identifiers are not the redaction marker.
  //   A body omitting them would make these transport cases fail on the response guard rather than on
  //   the request shape they are about.
  return Promise.resolve({
    data: {
      items: [],
      firstKey: null,
      lastKey: null,
      hasNext: false,
      customer: REDACTED_CUSTOMER,
    },
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

/**
 * Asserts an identifier shorter than the declared width is REFUSED, and is neither padded nor sent.
 *
 * ⚠️ Refactoring Rationale: this case asserted the opposite -- that a short value was transmitted
 * unchanged -- on the reading that the contract admitted one to eleven digits. It does not:
 * `AccountLookupRequest.accountId` is published with `minLength: 11`, `maxLength: 11` and
 * `pattern: '^[0-9]{11}$'`, so the short value the client used to send could only ever come back as a
 * bean-validation refusal. The baseline agrees, and that is the stronger reason: the receiving field is
 * `PIC 9(11)`, so `2210-EDIT-ACCOUNT` in `app/cbl/COACTVWC.cbl` fails its `IS NOT NUMERIC` test on the
 * trailing spaces a short entry leaves rather than resolving it to a padded row.
 *
 * Assumptions: the case also asserts NOTHING was transmitted, because "refused" and "refused before a
 * round trip" are two facts and only the second one saves the request. Zero-padding here was the
 * alternative and is refused for the reason the original comment gave, which survives the inversion: it
 * would send a value the operator did not type.
 * @returns {Promise<void>} Resolves once the refusal has been observed.
 */
async function refusesAShortIdentifierWithoutSendingIt(): Promise<void> {
  await expect(bodySentByAccountView('11')).rejects.toThrow(RangeError);
  expect(sentBody).toBeUndefined();
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
    'An account identifier must be exactly eleven decimal digits.',
  );
  expect(sentBody).toBeUndefined();
}

/** Groups the assertions that fix how an account identifier crosses the wire. */
function accountIdentifierTransportContract(): void {
  beforeEach(stubTransport);
  afterEach(restoreTransport);
  it('sends the account identifier as text at its given width', sendsTheAccountIdentifierAsText);
  it('refuses a short identifier without sending it', refusesAShortIdentifierWithoutSendingIt);
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

/**
 * A minimal account-view body; only the members these assertions read are populated.
 *
 * Assumptions: the redacted customer is part of the MINIMUM, because the read validates it. A response
 * whose protected identifiers are not the marker is refused before it reaches a caller, so a fixture
 * omitting them would not be a smaller lawful body -- it would be an unlawful one.
 */
const ACCOUNT_VIEW_BODY = {
  accountId: '00000000011',
  customer: REDACTED_CUSTOMER,
} as const;

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
  //   as a string with pattern ^[0-9]{11}$, and the suite above exists for precisely this member:
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
 * Asserts a national identifier returned in the clear is refused rather than returned to a screen.
 *
 * Assumptions: the fixture is a WHOLE formatted identifier, which is the exact value the contract's own
 * withdrawn example carried and which its `maxLength: 12` admitted -- so this case measures the
 * situation the schema could not exclude. The refusal is asserted to be a `RangeError` and its message
 * asserted NOT to contain the offending value, because a message quoting it would put a national
 * identifier into whatever records the failure.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAnUnredactedNationalIdentifier(): Promise<void> {
  answerWith({
    accountId: '00000000011',
    customer: { ...REDACTED_CUSTOMER, ssnMasked: '123-45-6789' },
  });

  await expect(readAccountView('00000000011')).rejects.toThrow(RangeError);
  await expect(readAccountView('00000000011')).rejects.not.toThrow(/123-45-6789/u);
}

/**
 * Asserts a government-issued identifier returned in the clear is refused on the same terms.
 *
 * Assumptions: the second member is asserted separately rather than assumed to follow from the first.
 * The guard is two calls, and a guard that checked only the national identifier would pass a case
 * written against that member alone while leaving the other property open.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function refusesAnUnredactedGovernmentIdentifier(): Promise<void> {
  answerWith({
    accountId: '00000000011',
    customer: { ...REDACTED_CUSTOMER, governmentIssuedIdMasked: 'X1234567890' },
  });

  await expect(readAccountView('00000000011')).rejects.toThrow(RangeError);
}

/**
 * Asserts the partial-success answer -- an account with an explicit null customer -- is READ, not refused.
 *
 * ⚠️ Purpose: this is the case whose absence let a defect ship. `AccountViewResponse.customer` is
 * declared `oneOf` a customer and the null type, and the service produces that null on a real success
 * path: it answers the account together with the reference's own miss sentence when the account row was
 * located and the customer master holds no matching row, which is exactly what `app/cbl/COACTVWC.cbl`
 * paints by guarding its account region at L471 and its customer region at L493 separately. The client's
 * redaction guard reached the two masked members through optional chaining, so a null customer produced
 * `undefined` at both, and `requireRedactedIdentifier` refuses `undefined` -- so every one of those
 * successful reads was rejected with a `RangeError` and the arm was unreachable through this client. No
 * case covered it, because every fixture carried a customer.
 *
 * ⚠️ Assumptions: the OMITTED member is asserted separately below to remain a refusal, and the pair is
 * what makes this case a fix rather than a hole. `customer` is one of the response's `required` members,
 * so a null is a state and an absence is a service that did not answer its own schema -- and because the
 * screen renders the two identically, admitting the absence would hide that fault behind a legitimate
 * display.
 * @returns {Promise<void>} Resolves once both halves have been observed.
 */
async function readsAnAccountWhoseCustomerIsAbsent(): Promise<void> {
  answerWith({ accountId: '00000000011', account: {}, customer: null }, 200, { etag: REVISION });

  const outcome = await readAccountView('00000000011');

  expect(outcome.account.customer).toBeNull();
  expect(outcome.revision).toBe(REVISION);

  answerWith({ accountId: '00000000011', account: {} }, 200, { etag: REVISION });
  await expect(readAccountView('00000000011')).rejects.toThrow(RangeError);
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
  it('refuses an unredacted national identifier', refusesAnUnredactedNationalIdentifier);
  it('refuses an unredacted government-issued identifier', refusesAnUnredactedGovernmentIdentifier);
  it(
    'reads an account whose customer master holds no row, and refuses an omitted member',
    readsAnAccountWhoseCustomerIsAbsent,
  );
}

describe('account client behaviour', accountClientBehaviour);
