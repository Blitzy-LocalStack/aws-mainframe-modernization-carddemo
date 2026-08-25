/**
 * @file Behavioural tests for the reference client in `ui/src/api/reference.ts`.
 *
 * Purpose
 * -------
 * Assert that all nineteen reference operations address the target their contract declares with the method
 * it declares, that the five browses share one paging discipline, that the composite-key operations place
 * their key parts in the right order, and that the one guard this module holds refuses locally.
 *
 * Refactoring Rationale: this module had no behavioural test and it publishes more operations than any
 * other client -- nineteen against five or eight elsewhere -- assembled from four shared helpers. A defect
 * in one of those helpers is therefore a defect in up to five operations at once, which is exactly the
 * shape a per-operation manifest comparison cannot see.
 *
 * Assumptions: the two-part and three-part keys are asserted in ORDER. A category is addressed by its type
 * code then its category code, and a disclosure rate by group, then type, then category; transposing two
 * parts yields a well-formed target that reads a different row, which no status distinguishes.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  applyReferenceMaintenanceActions,
  createTransactionCategory,
  createTransactionType,
  deleteTransactionCategory,
  deleteTransactionType,
  evaluateDate,
  getDisclosureGroupRate,
  getTransactionCategory,
  getTransactionType,
  getUsPhoneAreaCode,
  getUsState,
  getUsStateZipPrefix,
  listTransactionCategories,
  listTransactionTypes,
  listUsPhoneAreaCodes,
  listUsStateZipPrefixes,
  listUsStates,
  replaceTransactionCategory,
  replaceTransactionType,
} from './reference';
import {
  HTTP_CREATED,
  HTTP_NO_CONTENT,
  answerEveryRequestWith,
  answerWith,
  dispatchedRequests,
  installApiHarness,
  onlyRequest,
  pageOf,
  removeApiHarness,
  requestNumber,
} from '../test/apiHarness';

/** One transaction type row, in the shape the contract publishes. */
const TYPE_ROW = { typeCd: '01', description: 'PURCHASE', version: 1 } as const;

/** One transaction category row, carrying both parts of its composite key. */
const CATEGORY_ROW = { typeCd: '01', catCd: '0001', description: 'RETAIL', version: 1 } as const;

/** Asserts the type browse reads the collection with no paging member on an opening read. */
async function browsesTypesWithNoPagingMember(): Promise<void> {
  answerWith(pageOf([TYPE_ROW]));

  await listTransactionTypes();

  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/reference/transaction-types');
  expect(request.params).toEqual({});
}

/** Asserts a browse sends its filters beside its paging members. */
async function sendsFiltersBesideThePagingMembers(): Promise<void> {
  answerWith(pageOf([CATEGORY_ROW]));

  await listTransactionCategories({
    typeCode: '01',
    description: 'RETAIL',
    cursor: 'opaque-token',
    direction: 'previous',
  });

  expect(onlyRequest().params).toEqual({
    typeCode: '01',
    description: 'RETAIL',
    cursor: 'opaque-token',
    direction: 'previous',
  });
}

/**
 * Asserts every browse in this module refuses a direction supplied without a cursor.
 *
 * Assumptions: all five are exercised rather than one, because they share `pagingParameters` and a
 * single-browse assertion would pass against a module that had re-implemented the guard in one place only.
 */
async function everyBrowseRefusesADirectionWithNoCursor(): Promise<void> {
  await expect(listTransactionTypes({ direction: 'next' })).rejects.toThrow(RangeError);
  await expect(listTransactionCategories({ direction: 'next' })).rejects.toThrow(RangeError);
  await expect(listUsPhoneAreaCodes({ direction: 'next' })).rejects.toThrow(RangeError);
  await expect(listUsStates({ direction: 'next' })).rejects.toThrow(RangeError);
  await expect(listUsStateZipPrefixes({ direction: 'next' })).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts the lookup browses address their own three collections. */
async function browsesTheThreeLookupCollections(): Promise<void> {
  answerEveryRequestWith(pageOf([]));

  await listUsPhoneAreaCodes({ codeClass: 'G' });
  await listUsStates();
  await listUsStateZipPrefixes();

  expect(requestNumber(1).url).toBe('/reference/us-phone-area-codes');
  expect(requestNumber(1).params).toEqual({ codeClass: 'G' });
  expect(requestNumber(2).url).toBe('/reference/us-states');
  expect(requestNumber(3).url).toBe('/reference/us-state-zip-prefixes');
}

/** Asserts type creation posts to the collection and reads the created body back. */
async function createsATypeAtTheCollectionTarget(): Promise<void> {
  answerWith(TYPE_ROW, HTTP_CREATED);

  await createTransactionType({ typeCd: '01', description: 'PURCHASE' });

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/reference/transaction-types');
  expect(request.body).toEqual({ typeCd: '01', description: 'PURCHASE' });
}

/** Asserts the type member operations share one target and differ only in method. */
async function addressesOneTypeByItsCode(): Promise<void> {
  answerEveryRequestWith(TYPE_ROW);

  await getTransactionType('01');
  await replaceTransactionType('01', { description: 'PURCHASE', version: 1 });
  answerWith(undefined, HTTP_NO_CONTENT);
  await deleteTransactionType('01');

  expect(requestNumber(1).method).toBe('get');
  expect(requestNumber(1).url).toBe('/reference/transaction-types/01');
  expect(requestNumber(2).method).toBe('put');
  expect(requestNumber(2).url).toBe('/reference/transaction-types/01');
  expect(requestNumber(2).body).toEqual({ description: 'PURCHASE', version: 1 });
  expect(requestNumber(3).method).toBe('delete');
  expect(requestNumber(3).url).toBe('/reference/transaction-types/01');
}

/**
 * Asserts a category is addressed by both key parts, type first.
 *
 * Assumptions: the ORDER is the assertion. Both parts are short numeric codes, so a transposed pair
 * composes a target that resolves -- to a different category, or to a 404 attributed to the wrong row.
 */
async function addressesOneCategoryByBothKeyParts(): Promise<void> {
  answerEveryRequestWith(CATEGORY_ROW);

  await getTransactionCategory('01', '0001');
  await replaceTransactionCategory('01', '0001', { description: 'RETAIL', version: 1 });
  answerWith(undefined, HTTP_NO_CONTENT);
  await deleteTransactionCategory('01', '0001');

  for (const ordinal of [1, 2, 3]) {
    expect(requestNumber(ordinal).url).toBe('/reference/transaction-categories/01/0001');
  }
  expect(requestNumber(1).method).toBe('get');
  expect(requestNumber(2).method).toBe('put');
  expect(requestNumber(3).method).toBe('delete');
}

/** Asserts category creation posts both key parts in the body rather than in the target. */
async function createsACategoryThroughTheCollection(): Promise<void> {
  answerWith(CATEGORY_ROW, HTTP_CREATED);

  await createTransactionCategory({ typeCd: '01', catCd: '0001', description: 'RETAIL' });

  const request = onlyRequest();
  expect(request.url).toBe('/reference/transaction-categories');
  expect(request.body).toEqual({ typeCd: '01', catCd: '0001', description: 'RETAIL' });
}

/** Asserts a disclosure rate is addressed by its three key parts in the published order. */
async function addressesADisclosureRateByItsThreeParts(): Promise<void> {
  answerWith({ accountGroupId: 'DEFAULT', typeCode: '01', categoryCode: '0001', rate: '12.99' });

  await getDisclosureGroupRate('DEFAULT', '01', '0001');

  expect(onlyRequest().url).toBe('/reference/disclosure-groups/DEFAULT/01/0001');
}

/** Asserts each single-part lookup member operation addresses its own collection's member. */
async function addressesEachLookupMember(): Promise<void> {
  answerEveryRequestWith({});

  await getUsPhoneAreaCode('212');
  await getUsState('NY');
  await getUsStateZipPrefix('NY10');

  expect(requestNumber(1).url).toBe('/reference/us-phone-area-codes/212');
  expect(requestNumber(2).url).toBe('/reference/us-states/NY');
  expect(requestNumber(3).url).toBe('/reference/us-state-zip-prefixes/NY10');
}

/**
 * Asserts the date evaluation sends its value and omits an absent mask.
 *
 * Assumptions: the ABSENT mask is asserted, because the service applies its own default and a client
 * sending an empty mask would ask for an evaluation against no format at all.
 */
async function evaluatesADateWithAnOptionalMask(): Promise<void> {
  answerWith({ valid: true });
  await evaluateDate('2022-07-18');
  expect(onlyRequest().params).toEqual({ date: '2022-07-18' });

  installApiHarness();
  answerWith({ valid: true });
  await evaluateDate('2022-07-18', 'YYYY-MM-DD');
  expect(onlyRequest().params).toEqual({ date: '2022-07-18', mask: 'YYYY-MM-DD' });
}

/** Asserts the maintenance batch posts its whole request to the actions target. */
async function appliesMaintenanceActionsAsOneBatch(): Promise<void> {
  answerWith({ applied: 1, results: [] });

  await applyReferenceMaintenanceActions({
    actions: [{ action: 'INSERT', typeCd: '01', description: 'PURCHASE' }],
  });

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/reference/maintenance-actions');
  expect(request.body).toEqual({
    actions: [{ action: 'INSERT', typeCd: '01', description: 'PURCHASE' }],
  });
}

/**
 * Asserts an over-long entry refuses the WHOLE batch before anything is dispatched.
 *
 * Purpose: the batch's members are one level below the request body, so the shared width guard -- which
 * deliberately does not descend into arrays -- has to be applied per entry by this module. This case is
 * what fails if that loop is removed, and it drives the SECOND entry specifically, because a loop that
 * checked only the first would pass a single-entry case.
 *
 * Assumptions: refusing the whole batch is the right severity, and that is a property of the SERVICE.
 * The batch is not all-or-nothing there -- it keeps the entries that applied and reports a return code
 * of 4 -- so dispatching a batch with one unstorable entry would apply the others and leave the caller
 * to work out which. Refusing locally leaves nothing applied.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function refusesAWholeBatchForOneOverLongEntry(): Promise<void> {
  const raised: unknown = await applyReferenceMaintenanceActions({
    actions: [
      { action: 'INSERT', typeCd: '01', description: 'PURCHASE' },
      { action: 'INSERT', typeCd: '02', description: 'D'.repeat(51) },
    ],
  }).catch(
    /**
     * Yields the refusal as a value, so the case can assert on what it was.
     * @param {unknown} error - Whatever the batch rejected with.
     * @returns {unknown} That same refusal.
     */
    function yieldTheRefusal(error: unknown): unknown {
      return error;
    },
  );

  expect(raised).toBeInstanceOf(RangeError);
  expect(raised instanceof Error ? raised.message : '').toContain('MaintenanceAction.description');
  expect(dispatchedRequests(), 'no entry may be applied for a refused batch').toHaveLength(0);
}

/**
 * Asserts every target this module composes sits under the reference prefix.
 *
 * Assumptions: this is the module-level counterpart of the per-operation cases above, and it exists for
 * the reason the reporting client's equivalent does: a target composed from the wrong operation constant
 * would still be well-formed, and the prefix is what makes such a mistake visible in one assertion.
 */
async function composesEveryTargetUnderTheReferencePrefix(): Promise<void> {
  answerEveryRequestWith(pageOf([]));

  await listTransactionTypes();
  await listTransactionCategories();
  await listUsStates();
  await evaluateDate('2022-07-18');
  await getUsState('NY');

  for (const request of dispatchedRequests()) {
    expect(request.url.startsWith('/reference/')).toBe(true);
  }
}

/**
 * Registers every reference client case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function referenceClientBehaviour(): void {
  beforeEach(installApiHarness);
  afterEach(removeApiHarness);
  it('browses types with no paging member', browsesTypesWithNoPagingMember);
  it('sends filters beside the paging members', sendsFiltersBesideThePagingMembers);
  it(
    'refuses a direction with no cursor on every browse',
    everyBrowseRefusesADirectionWithNoCursor,
  );
  it('browses the three lookup collections', browsesTheThreeLookupCollections);
  it('creates a type at the collection target', createsATypeAtTheCollectionTarget);
  it('addresses one type by its code', addressesOneTypeByItsCode);
  it('addresses one category by both key parts', addressesOneCategoryByBothKeyParts);
  it('creates a category through the collection', createsACategoryThroughTheCollection);
  it('addresses a disclosure rate by its three parts', addressesADisclosureRateByItsThreeParts);
  it('addresses each lookup member', addressesEachLookupMember);
  it('evaluates a date with an optional mask', evaluatesADateWithAnOptionalMask);
  it('applies maintenance actions as one batch', appliesMaintenanceActionsAsOneBatch);
  it('refuses a whole batch for one over-long entry', refusesAWholeBatchForOneOverLongEntry);
  it(
    'composes every target under the reference prefix',
    composesEveryTargetUnderTheReferencePrefix,
  );
}

describe('reference client behaviour', referenceClientBehaviour);
