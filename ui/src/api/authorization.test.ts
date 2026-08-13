/**
 * @file Behavioural tests for the authorization client in `ui/src/api/authorization.ts`.
 *
 * Purpose
 * -------
 * Assert the five pending-authorization operations address their published targets, keep the composite row
 * key out of every request target by carrying the sealed selector instead, refuse a direction with no
 * cursor, and refuse any reading whose card number was not masked -- on all three readings that carry one.
 *
 * Refactoring Rationale: this module had no behavioural test, and it is the client whose types this
 * checkpoint corrected most heavily: its summary shape omitted four published members and forty-six
 * members were published as optional that the service always sends. Those are type-level facts the closure
 * gate now holds; what the cases below hold is the behaviour around them -- that the selector is opaque,
 * that a masked rendering is required, and that the fraud transition is a PUT of one action.
 *
 * Assumptions: the row key is a sealed selector standing for a composite of an account identifier and a
 * packed date and time. It is passed through verbatim, so a test that asserted anything about its content
 * would be asserting something the client is required not to know.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  getNextPendingAuthorization,
  getPendingAuthorization,
  getPendingAuthorizationScreen,
  listPendingAuthorizations,
  setAuthorizationFraudState,
} from './authorization';
import {
  answerWith,
  dispatchedRequests,
  installApiHarness,
  onlyRequest,
  pageOf,
  removeApiHarness,
} from '../test/apiHarness';

/** The masked rendering every authorization reading is required to carry. */
const MASKED_CARD_NUMBER = '************7065';

/** An opaque sealed row selector, carried verbatim and never parsed. */
const SELECTOR = 'v1.MDAwMDAwMDAwMTE6MjYyMTU6OTE2NDQ5MDI';

/** The account summary block, in the twenty-member shape the contract publishes. */
const SUMMARY = {
  accountId: '00000000011',
  customerId: '000000009',
  authStatus: null,
  accountStatus1: null,
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
  customerName: null,
  addressLine1: null,
  addressLine2: null,
  phoneNumber1: null,
} as const;

/** One list row, carrying its own sealed selector and a masked card number. */
const ROW = {
  key: SELECTOR,
  transactionId: '0000000000000123',
  authOrigDate: null,
  authOrigTime: null,
  authType: null,
  approvalStatus: 'APPROVED',
  matchStatus: 'P',
  amount: '250.00',
  cardNum: MASKED_CARD_NUMBER,
} as const;

/**
 * Builds one list response: the summary block, one bounded page, and no boundary sentence.
 * @param {string} cardNum - The card rendering the single row carries, so a case can vary it.
 * @returns {Record<string, unknown>} The response body.
 */
function listBody(cardNum: string = MASKED_CARD_NUMBER): Record<string, unknown> {
  return { summary: SUMMARY, page: pageOf([{ ...ROW, cardNum }]), screenMessage: null };
}

/** Asserts the list posts its account scope in a body at the published search target. */
async function listsThroughABodyAtTheSearchTarget(): Promise<void> {
  answerWith(listBody());

  await listPendingAuthorizations({ accountId: '00000000011' });

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/authorizations/search');
  expect(request.body).toEqual({ accountId: '00000000011' });
  // Assumptions: the target is asserted to carry no digit, because the account scope is an account
  //   identifier and a query string is written into the edge access log before any handler runs.
  expect(request.url).not.toMatch(/[0-9]/u);
}

/** Asserts a cursor travels in the same body with the direction asked for. */
async function sendsTheCursorAndDirectionInTheBody(): Promise<void> {
  answerWith(listBody());

  await listPendingAuthorizations({
    accountId: '00000000011',
    cursor: 'opaque-token',
    direction: 'previous',
  });

  expect(onlyRequest().body).toEqual({
    accountId: '00000000011',
    cursor: 'opaque-token',
    direction: 'previous',
  });
}

/** Asserts a cursor with no direction is read forward, as the contract's asymmetric rule declares. */
async function readsACursorForwardWhenNoDirectionIsGiven(): Promise<void> {
  answerWith(listBody());

  await listPendingAuthorizations({ accountId: '00000000011', cursor: 'opaque-token' });

  expect(onlyRequest().body).toEqual({
    accountId: '00000000011',
    cursor: 'opaque-token',
    direction: 'next',
  });
}

/**
 * Asserts a direction with no cursor is refused locally.
 *
 * Assumptions: this is the one combination the contract refuses of the four its query schema enumerates,
 * and the refusal is asserted to happen BEFORE dispatch -- the previous behaviour sent the opening page
 * instead, which a caller cannot distinguish from a successful step.
 */
async function refusesADirectionWithNoCursor(): Promise<void> {
  await expect(
    listPendingAuthorizations({ accountId: '00000000011', direction: 'previous' }),
  ).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts the two detail readings and the forward move address the selector's own sub-targets. */
async function addressesEachReadingBySelector(): Promise<void> {
  answerWith({ cardNum: MASKED_CARD_NUMBER });
  await getPendingAuthorization(SELECTOR);
  expect(onlyRequest().url).toBe(`/authorizations/${encodeURIComponent(SELECTOR)}`);

  installApiHarness();
  answerWith({ cardNumber: MASKED_CARD_NUMBER });
  await getPendingAuthorizationScreen(SELECTOR);
  expect(onlyRequest().url).toBe(`/authorizations/${encodeURIComponent(SELECTOR)}/screen`);

  installApiHarness();
  answerWith({ authorization: null });
  await getNextPendingAuthorization(SELECTOR);
  expect(onlyRequest().url).toBe(`/authorizations/${encodeURIComponent(SELECTOR)}/next`);
}

/**
 * Asserts a selector is percent-encoded into every target it addresses.
 *
 * Assumptions: a sealed token is base64url text, which may legitimately carry characters a target reads as
 * structure. Encoding it is what stops such a value from resolving to extra path segments -- and the
 * client's own path builder owns that, so this case asserts the composed result rather than the mechanism.
 */
async function encodesASelectorCarryingStructuralCharacters(): Promise<void> {
  answerWith({ cardNum: MASKED_CARD_NUMBER });

  await getPendingAuthorization('a/b+c');

  expect(onlyRequest().url).toBe('/authorizations/a%2Fb%2Bc');
}

/**
 * Asserts an unmasked card number is refused on all three readings that carry one.
 *
 * Assumptions: all three are exercised because each reads its card from a differently-named member --
 * `cardNum` on the row and the detail, `cardNumber` on the screen projection, and the nested authorization
 * on the forward move -- so one shared guard is reached through three different accessors.
 */
async function refusesAnUnmaskedCardNumberOnEveryReading(): Promise<void> {
  answerWith(listBody('4859452612877065'));
  await expect(listPendingAuthorizations({ accountId: '00000000011' })).rejects.toThrow(RangeError);

  installApiHarness();
  answerWith({ cardNumber: '4859452612877065' });
  await expect(getPendingAuthorizationScreen(SELECTOR)).rejects.toThrow(RangeError);

  installApiHarness();
  answerWith({ authorization: { ...ROW, cardNum: '4859452612877065' } });
  await expect(getNextPendingAuthorization(SELECTOR)).rejects.toThrow(RangeError);
}

/**
 * Asserts an exhausted forward move is a success carrying no authorization.
 *
 * Assumptions: the absence is asserted to be ACCEPTED rather than refused, because reaching the end of the
 * set is an ordinary outcome the reference screen renders as a message -- a client treating it as a fault
 * would report a boundary as an error.
 */
async function acceptsAnExhaustedForwardMove(): Promise<void> {
  answerWith({
    authorization: null,
    screenMessage: 'YOU ARE ALREADY AT THE BOTTOM OF THE PAGE...',
  });

  const outcome = await getNextPendingAuthorization(SELECTOR);

  expect(outcome.authorization).toBeNull();
}

/** Asserts the fraud transition puts one action to the selector's fraud sub-target. */
async function marksFraudWithOneAction(): Promise<void> {
  answerWith({ updateStatus: 'S', message: 'ADD SUCCESS' });

  const outcome = await setAuthorizationFraudState(SELECTOR, { action: 'F' });

  const request = onlyRequest();
  expect(request.method).toBe('put');
  expect(request.url).toBe(`/authorizations/${encodeURIComponent(SELECTOR)}/fraud`);
  expect(request.body).toEqual({ action: 'F' });
  expect(outcome.updateStatus).toBe('S');
  expect(outcome.message).toBe('ADD SUCCESS');
}

/**
 * Asserts the summary block reaches a caller with all twenty published members.
 *
 * Assumptions: the four customer display members are asserted PRESENT and null, which is the state this
 * checkpoint corrected the contract and the type to describe: they are composed from the account context,
 * so a summary whose neighbouring customer record is gone carries them as null rather than omitting them.
 */
async function carriesEverySummaryMemberThrough(): Promise<void> {
  answerWith(listBody());

  const response = await listPendingAuthorizations({ accountId: '00000000011' });

  expect(Object.keys(response.summary)).toHaveLength(20);
  expect(response.summary.customerName).toBeNull();
  expect(response.summary.addressLine1).toBeNull();
  expect(response.summary.addressLine2).toBeNull();
  expect(response.summary.phoneNumber1).toBeNull();
}

/**
 * Registers every authorization client case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function authorizationClientBehaviour(): void {
  beforeEach(installApiHarness);
  afterEach(removeApiHarness);
  it('lists through a body at the search target', listsThroughABodyAtTheSearchTarget);
  it('sends the cursor and direction in the body', sendsTheCursorAndDirectionInTheBody);
  it(
    'reads a cursor forward when no direction is given',
    readsACursorForwardWhenNoDirectionIsGiven,
  );
  it('refuses a direction with no cursor', refusesADirectionWithNoCursor);
  it('addresses each reading by selector', addressesEachReadingBySelector);
  it(
    'encodes a selector carrying structural characters',
    encodesASelectorCarryingStructuralCharacters,
  );
  it('refuses an unmasked card number on every reading', refusesAnUnmaskedCardNumberOnEveryReading);
  it('accepts an exhausted forward move', acceptsAnExhaustedForwardMove);
  it('marks fraud with one action', marksFraudWithOneAction);
  it('carries every summary member through', carriesEverySummaryMemberThrough);
}

describe('authorization client behaviour', authorizationClientBehaviour);
