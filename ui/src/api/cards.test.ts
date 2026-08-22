/**
 * @file Unit tests for the card client in `ui/src/api/cards.ts`.
 *
 * Purpose
 * -------
 * Fix the one property the card client is uniquely placed to enforce: that the `displayCardNumber`
 * every card response carries IS the masked rendering `card-api.yaml` declares -- exactly twelve mask
 * characters followed by exactly four digits, at L1988 for the list row and L2161 for the detail --
 * and not merely something other than sixteen bare digits. The distinction is the whole point of these
 * cases: a revision of this client refused only the sixteen-digit form, which admitted a
 * separator-formatted primary account number, a partially masked one and a differently masked one, so
 * a whole number could reach a table, a log line and a bug report while the guard reported success.
 *
 * Assumptions: the axios instance is stubbed, so these cases measure this package's validation of a
 * response and nothing about a running service. What is being asserted is a client-side boundary
 * check, so a stubbed response is the only way to present the malformed rendering a correct service
 * would never send.
 *
 * Assumptions: this file carries TWO suites, and the split is deliberate rather than historical.
 * `card rendering contract` fixes which renderings of a card number the client will accept back from
 * a response, and `card client behaviour` fixes the target, method and body of every request it
 * composes plus the refusals it answers locally. They read opposite ends of the same call, which is
 * why each keeps its own double; the comment above the second suite records the reasoning.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getAdminCardDetail, getCard, listCards, lookupCard, updateCard } from './cards';
import { getApiClient } from './client';
import {
  answerWith,
  dispatchedRequests,
  installApiHarness,
  onlyRequest,
  pageOf,
  removeApiHarness,
} from '../test/apiHarness';

// Assumptions: the fixture carries the `/api/v1` operation prefix because
// `normalizeApiBaseUrl` requires it -- a base URL one segment short is refused at
// start-up rather than producing a 404 on every request. The prefix changes no
// assertion here: every case below asserts the RELATIVE request path.
const API_BASE_URL = 'https://api.carddemo.example/api/v1';

const CORRELATION_HEADER = 'X-Correlation-Id';

const HTTP_OK = 200;

/**
 * The masked rendering the contract declares, being twelve mask characters and four digits.
 *
 * Assumptions: the four digits are the last four of `CARD_NUMBER` below, because the two values are
 * two renderings of one card and a fixture in which they disagreed would describe a response no
 * service produces.
 */
const MASKED_CARD_NUMBER = '************7065';

/** The whole sixteen-digit number, which only the administrative detail is permitted to carry. */
const CARD_NUMBER = '4859452612877065';

/**
 * One sealed card selector, being exactly the 59 URL-safe characters the contract declares.
 *
 * Assumptions: the length is what makes a value a selector rather than its content, so this is
 * composed to the exact published length instead of being a realistic ciphertext. `../routes/cards`
 * checks the length and the alphabet and nothing else, because only the service holds the sealing key.
 */
const CARD_KEY = `${'A'.repeat(58)}_`;

/** One dispatched request, reduced to the parts these assertions are about. */
interface DispatchedRequest {
  method: string;
  url: string;
}

let dispatched: DispatchedRequest[] = [];

let nextBody: unknown = {};

/**
 * Records one dispatched request and answers it with the queued body.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A response carrying the queued body and an ok status.
 */
async function captureAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  dispatched.push({ method: config.method ?? '', url: config.url ?? '' });
  return Promise.resolve({
    data: nextBody,
    status: HTTP_OK,
    statusText: 'OK',
    headers: {},
    config,
  } as AxiosResponse);
}

/** Supplies the build-time configuration the client validates before it is constructed. */
function stubBuildConfiguration(): void {
  dispatched = [];
  nextBody = {};
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', CORRELATION_HEADER);
  getApiClient().defaults.adapter = captureAdapter;
}

/** Restores the environment so no later file inherits this file's configuration. */
function restoreBuildConfiguration(): void {
  vi.unstubAllEnvs();
}

/**
 * Builds one browse row carrying the supplied rendering and an otherwise valid shape.
 * @param {string} displayCardNumber - The rendering to place in the security-sensitive member.
 * @returns {Record<string, unknown>} One list row as the contract shapes it.
 */
function rowRendering(displayCardNumber: string): Record<string, unknown> {
  return {
    key: CARD_KEY,
    displayCardNumber,
    accountId: '00000000011',
    activeStatus: 'Y',
  };
}

/**
 * Builds one page envelope holding a single row with the supplied rendering.
 * @param {string} displayCardNumber - The rendering to place in the row.
 * @returns {Record<string, unknown>} A one-row page carrying both sealed positions.
 */
function pageRendering(displayCardNumber: string): Record<string, unknown> {
  return {
    items: [rowRendering(displayCardNumber)],
    firstKey: 'v1.cursor.first',
    lastKey: 'v1.cursor.last',
    hasNext: false,
  };
}

/**
 * Builds one card detail carrying the supplied rendering and an otherwise valid shape.
 * @param {string} displayCardNumber - The rendering to place in the security-sensitive member.
 * @returns {Record<string, unknown>} One detail as the contract shapes it.
 */
function detailRendering(displayCardNumber: string): Record<string, unknown> {
  return {
    ...rowRendering(displayCardNumber),
    embossedName: 'PARITY CARDHOLDER',
    expirationDate: '2025-12-31',
    version: 3,
  };
}

/**
 * Asserts a browse whose row carries the supplied rendering is refused, and reports the failure.
 *
 * Assumptions: the rejection is asserted through the PUBLIC browse call rather than by reaching the
 * module-private validator, because what is being fixed is that a caller cannot obtain the row -- a
 * test of the private function would leave the wiring between them unasserted.
 * @param {string} displayCardNumber - The rendering the stubbed service answers with.
 * @returns {Promise<Error>} The error the browse raised, for the caller to make claims about.
 * @throws {Error} If the browse resolved, which means the rendering was accepted.
 */
async function refusedBrowse(displayCardNumber: string): Promise<Error> {
  nextBody = pageRendering(displayCardNumber);
  try {
    await listCards();
  } catch (error) {
    return error as Error;
  }
  throw new Error(
    `the browse accepted a rendering it must refuse: length ${String(displayCardNumber.length)}`,
  );
}

/** Asserts the masked rendering the contract declares is accepted unchanged on a browse row. */
async function acceptsTheMaskedRendering(): Promise<void> {
  nextBody = pageRendering(MASKED_CARD_NUMBER);
  const page = await listCards();
  expect(dispatched).toHaveLength(1);
  expect(page.items).toHaveLength(1);
  expect(page.items[0]?.displayCardNumber).toBe(MASKED_CARD_NUMBER);
  expect(page.items[0]?.key).toBe(CARD_KEY);
}

/**
 * Asserts a separator-formatted primary account number is refused.
 *
 * Refactoring Rationale: this is the case the previous guard admitted and the reason it was replaced.
 * `4859-4526-1287-7065` is nineteen characters, so a test asking "is this exactly sixteen digits"
 * answers no and the row was accepted -- while the value it accepted is the whole primary account
 * number, which is precisely the disclosure the masked member exists to prevent.
 */
async function refusesASeparatorFormattedNumber(): Promise<void> {
  const error = await refusedBrowse('4859-4526-1287-7065');
  expect(error).toBeInstanceOf(RangeError);
  expect(error.message).toContain('displayCardNumber');
}

/**
 * Asserts a partially masked rendering is refused.
 *
 * Assumptions: `****7065` carries no whole number, so it is not a disclosure; it is refused because it
 * is not the rendering the contract declares, and accepting a second rendering would make the member's
 * width unpredictable to every screen that lays a column out from it.
 */
async function refusesAPartiallyMaskedRendering(): Promise<void> {
  const error = await refusedBrowse('****7065');
  expect(error).toBeInstanceOf(RangeError);
}

/**
 * Asserts a differently masked rendering of the correct length is refused.
 *
 * Refactoring Rationale: this is the second case the previous guard admitted. Sixteen characters of
 * which the first twelve are letters is not sixteen digits, so the old test passed it; the contract's
 * pattern names the mask character, and a service substituting another one has diverged from the
 * document whether or not the result happens to hide the number.
 */
async function refusesADifferentMaskCharacter(): Promise<void> {
  const error = await refusedBrowse('xxxxxxxxxxxx7065');
  expect(error).toBeInstanceOf(RangeError);
}

/** Asserts the whole sixteen-digit number is still refused, which the previous guard also did. */
async function refusesTheWholeNumber(): Promise<void> {
  const error = await refusedBrowse(CARD_NUMBER);
  expect(error).toBeInstanceOf(RangeError);
}

/**
 * Asserts no rejection message reproduces the value it rejected.
 *
 * Assumptions: the four renderings checked here include two whole primary account numbers, so this
 * asserts the property that matters more than the refusal itself: a guard that refused the value and
 * then quoted it would have carried the number into whatever renders or logs the failure, which is the
 * same disclosure arriving by a different route.
 */
async function neverReproducesTheRejectedValue(): Promise<void> {
  const rejected = [CARD_NUMBER, '4859-4526-1287-7065', '4859 4526 1287 7065', '****7065'];
  for (const value of rejected) {
    const error = await refusedBrowse(value);
    expect(error.message).not.toContain(value);
    expect(error.message).not.toContain('7065');
  }
}

/**
 * Asserts the detail read applies the same rendering guard as the browse.
 *
 * Assumptions: the detail path is asserted separately because it reaches the guard through a different
 * validator -- `validateCardDetail` spreads `validateCardSummary` -- so a change breaking that spread
 * would leave the browse green while the single-card screen accepted an unmasked number.
 */
async function refusesAnUnmaskedRenderingOnTheDetailRead(): Promise<void> {
  nextBody = detailRendering(CARD_NUMBER);
  await expect(getCard(CARD_KEY)).rejects.toThrow(RangeError);
}

/** Asserts the detail read accepts the masked rendering and carries the version through. */
async function acceptsTheMaskedRenderingOnTheDetailRead(): Promise<void> {
  nextBody = detailRendering(MASKED_CARD_NUMBER);
  const detail = await getCard(CARD_KEY);
  expect(detail.displayCardNumber).toBe(MASKED_CARD_NUMBER);
  expect(detail.version).toBe(3);
}

/**
 * Asserts the administrative read requires BOTH renderings, each in its own member.
 *
 * Assumptions: this is the one operation permitted to carry the whole number, and it carries the masked
 * member as well, so the two checks are complementary rather than contradictory: the masked member must
 * be masked and the whole member must be whole. A service fault that swapped them would leave a short
 * value where the whole one belongs, which an administrative caller reads as a successful rendering.
 */
async function requiresBothRenderingsOnTheAdministrativeRead(): Promise<void> {
  nextBody = { ...detailRendering(MASKED_CARD_NUMBER), cardNumber: CARD_NUMBER };
  const detail = await getAdminCardDetail(CARD_KEY);
  expect(detail.cardNumber).toBe(CARD_NUMBER);
  expect(detail.displayCardNumber).toBe(MASKED_CARD_NUMBER);

  nextBody = { ...detailRendering(MASKED_CARD_NUMBER), cardNumber: MASKED_CARD_NUMBER };
  await expect(getAdminCardDetail(CARD_KEY)).rejects.toThrow(RangeError);
}

/** Groups the assertions that fix the card client's rendering guard. */
function cardRenderingContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it('accepts the masked rendering the contract declares', acceptsTheMaskedRendering);
  it('refuses a separator-formatted card number', refusesASeparatorFormattedNumber);
  it('refuses a partially masked rendering', refusesAPartiallyMaskedRendering);
  it('refuses a different mask character', refusesADifferentMaskCharacter);
  it('refuses the whole sixteen-digit number', refusesTheWholeNumber);
  it('never reproduces the rejected value', neverReproducesTheRejectedValue);
  it('refuses an unmasked rendering on the detail read', refusesAnUnmaskedRenderingOnTheDetailRead);
  it('accepts the masked rendering on the detail read', acceptsTheMaskedRenderingOnTheDetailRead);
  it(
    'requires both renderings on the administrative read',
    requiresBothRenderingsOnTheAdministrativeRead,
  );
}

describe('card rendering contract', cardRenderingContract);

// ------------------------------------------------------------------------------------------------
// Assumptions: what follows is a second suite over the same module, kept beside the first rather
// than folded into it. The two fix different properties: the block above fixes the RENDERING the
// client will accept from a response -- a masked card number and nothing else -- while this one
// fixes the SHAPE of every request the client composes, the selector it addresses a member by, and
// the refusals it answers locally. Alternatives Considered: re-expressing one suite in the other's
// arrangement so the file held a single harness. Rejected because the first reads the response the
// client returns while the second reads the request it sent, and the two need different doubles;
// collapsing either into the other would have cost a case rather than a helper. Assumptions: the
// two card-number specimens the second suite declared are the same values as the first suite's, so
// they are taken from the declarations above rather than restated -- one specimen, one place to
// change it.
// ------------------------------------------------------------------------------------------------
/** A well-formed sealed selector: exactly fifty-nine URL-safe characters, as a response publishes one. */
const SELECTOR = 'v1'.padEnd(59, 'A');

/** One card summary row in the shape the contract publishes. */
const CARD_ROW = {
  key: SELECTOR,
  displayCardNumber: MASKED_CARD_NUMBER,
  accountId: '00000000011',
  activeStatus: 'Y',
} as const;

/** A card update body in the five-member shape the contract's update schema declares. */
const CARD_EDIT = {
  embossedName: 'ADA BYRON',
  expirationMonth: '08',
  expirationYear: '2026',
  activeStatus: 'N',
  version: 3,
} as const;

/** One card detail body: the summary members plus the three the detail adds. */
const CARD_DETAIL = {
  ...CARD_ROW,
  embossedName: 'ADA LOVELACE',
  expirationDate: '2026-08-31',
  version: 3,
} as const;

/** Asserts the browse posts its criteria in a body, at the published search target. */
async function browsesCardsThroughABody(): Promise<void> {
  answerWith(pageOf([CARD_ROW], true));

  await listCards({ accountId: '00000000011' });

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/cards/search');
  expect(request.body).toEqual({ accountId: '00000000011' });
}

/** Asserts a cursor and its direction travel in the same body as the filter. */
async function sendsTheCursorAndDirectionInTheBody(): Promise<void> {
  answerWith(pageOf([CARD_ROW]));

  await listCards({ cursor: 'opaque-token', direction: 'previous' });

  expect(onlyRequest().body).toEqual({ cursor: 'opaque-token', direction: 'previous' });
}

/**
 * Asserts an opening browse sends an empty body rather than one naming absent criteria.
 *
 * Assumptions: the contract treats an absent member as absent and reads a body with none as the opening
 * page of the unfiltered set, so a body carrying `null` members would describe a different request.
 */
async function sendsAnEmptyBodyForTheOpeningPage(): Promise<void> {
  answerWith(pageOf([]));

  await listCards();

  expect(onlyRequest().body).toEqual({});
}

/**
 * Asserts a direction with no cursor is refused locally and never dispatched.
 *
 * Assumptions: the refusal asserted here is the CLIENT's and not the card contract's, and the
 * distinction is written down because the two differ deliberately. `card-api.yaml` answers that pair
 * with the opening page, transcribing `app/cbl/COCRDLIC.cbl` L444-L454, so this case must assert that
 * nothing was dispatched rather than that a 400 came back -- an assertion on the status would be
 * asserting a response this contract never sends.
 */
async function refusesADirectionWithNoCursor(): Promise<void> {
  await expect(listCards({ direction: 'next' })).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/**
 * Asserts a lookup sends the whole card number in a body and never in the target.
 *
 * Assumptions: the target is asserted to carry no digit at all, for the reason recorded in the account
 * client's tests: an access log and the browser's history both retain a target in full.
 */
async function looksUpACardThroughABody(): Promise<void> {
  answerWith(CARD_DETAIL);

  await lookupCard(CARD_NUMBER);

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/cards/lookup');
  expect(request.url).not.toMatch(/[0-9]/u);
  expect(request.body).toEqual({ cardNumber: CARD_NUMBER });
}

/** Asserts a card number that is not sixteen digits is refused before any request. */
async function refusesAMalformedCardNumber(): Promise<void> {
  await expect(lookupCard('485945261287706')).rejects.toThrow(RangeError);
  await expect(lookupCard('485945261287706A')).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts the member read addresses the card by its sealed selector. */
async function readsOneCardByItsSelector(): Promise<void> {
  answerWith(CARD_DETAIL);

  await getCard(SELECTOR);

  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe(`/cards/${SELECTOR}`);
}

/**
 * Asserts a selector that is not the published shape is refused, including a card number.
 *
 * Assumptions: a card number is asserted to be refused as a selector specifically, because that is the
 * substitution a caller is most likely to make -- and it would put a primary account number into a
 * request target, which is the disclosure the sealed selector exists to prevent.
 */
async function refusesAMalformedSelector(): Promise<void> {
  await expect(getCard(CARD_NUMBER)).rejects.toThrow(RangeError);
  await expect(getCard('short')).rejects.toThrow(RangeError);
  await expect(updateCard(CARD_NUMBER, CARD_EDIT)).rejects.toThrow(RangeError);
  await expect(getAdminCardDetail(CARD_NUMBER)).rejects.toThrow(RangeError);
  expect(dispatchedRequests()).toHaveLength(0);
}

/** Asserts an update puts its body to the member target under the same selector. */
async function updatesOneCardAtItsMemberTarget(): Promise<void> {
  answerWith(CARD_DETAIL);

  await updateCard(SELECTOR, CARD_EDIT);

  const request = onlyRequest();
  expect(request.method).toBe('put');
  expect(request.url).toBe(`/cards/${SELECTOR}`);
  expect(request.body).toEqual(CARD_EDIT);
}

/** Asserts the administrative reading has its own target under the admin prefix. */
async function readsTheAdministrativeDetailAtItsOwnTarget(): Promise<void> {
  answerWith({ ...CARD_DETAIL, cardNumber: CARD_NUMBER });

  const detail = await getAdminCardDetail(SELECTOR);

  expect(onlyRequest().url).toBe(`/admin/cards/${SELECTOR}`);
  expect(detail.cardNumber).toBe(CARD_NUMBER);
}

/**
 * Asserts an ordinary reading that renders a whole card number is refused.
 *
 * Assumptions: this is asserted on the LIST and on the detail, because the two fail differently in
 * practice -- a list would disclose one number per row -- and one shared validator serves both, so a
 * regression in it must fail on both readings rather than on whichever happens to be tested.
 */
async function refusesAnUnmaskedRendering(): Promise<void> {
  answerWith(pageOf([{ ...CARD_ROW, displayCardNumber: CARD_NUMBER }]));
  await expect(listCards()).rejects.toThrow(RangeError);

  installApiHarness();
  answerWith({ ...CARD_DETAIL, displayCardNumber: CARD_NUMBER });
  await expect(getCard(SELECTOR)).rejects.toThrow(RangeError);
}

/**
 * Asserts a row whose selector is not a sealed selector is refused.
 *
 * Assumptions: a card number in the `key` position is the case asserted, because a service that regressed
 * to publishing one would make every row of the list address a card by its number -- and the client would
 * then compose exactly the target this module refuses to compose.
 */
async function refusesARowWithoutASealedSelector(): Promise<void> {
  answerWith(pageOf([{ ...CARD_ROW, key: CARD_NUMBER }]));

  await expect(listCards()).rejects.toThrow(RangeError);
}

/**
 * Asserts a detail whose version is not a non-negative safe integer is refused.
 *
 * Assumptions: the version is the value an optimistic edit is conditional on, so a reading that accepted a
 * fractional or negative one would submit an edit under a revision the service cannot match, which
 * surfaces as a conflict the operator cannot act on.
 */
async function refusesADetailWithAnUnusableVersion(): Promise<void> {
  answerWith({ ...CARD_DETAIL, version: -1 });

  await expect(getCard(SELECTOR)).rejects.toThrow(RangeError);
}

/**
 * Registers every card client case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function cardClientBehaviour(): void {
  beforeEach(installApiHarness);
  afterEach(removeApiHarness);
  it('browses cards through a body', browsesCardsThroughABody);
  it('sends the cursor and direction in the body', sendsTheCursorAndDirectionInTheBody);
  it('sends an empty body for the opening page', sendsAnEmptyBodyForTheOpeningPage);
  it('refuses a direction with no cursor', refusesADirectionWithNoCursor);
  it('looks up a card through a body', looksUpACardThroughABody);
  it('refuses a malformed card number', refusesAMalformedCardNumber);
  it('reads one card by its selector', readsOneCardByItsSelector);
  it('refuses a malformed selector', refusesAMalformedSelector);
  it('updates one card at its member target', updatesOneCardAtItsMemberTarget);
  it(
    'reads the administrative detail at its own target',
    readsTheAdministrativeDetailAtItsOwnTarget,
  );
  it('refuses an unmasked rendering', refusesAnUnmaskedRendering);
  it('refuses a row without a sealed selector', refusesARowWithoutASealedSelector);
  it('refuses a detail with an unusable version', refusesADetailWithAnUnusableVersion);
}

describe('card client behaviour', cardClientBehaviour);
