/**
 * @file Proves the three card screens render their screen-level outcome through the
 * shared message band rather than through a component of their own.
 *
 * Purpose
 * -------
 * These cases assert one structural property per screen and nothing about the
 * screen's data: that the band element is present in BOTH states, and that a
 * failure reaches it as text. The property matters because the band is the one
 * place the message contract of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`
 * (`app/cpy/CVCRD01Y.cpy` L28-L29) is enforced, the one place the per-mapset
 * display width is applied -- 80 characters on `COCRDSL` and `COCRDUP`, 78 on the
 * other nineteen, asserted in `ui/src/layout/MessageBand.test.tsx` -- and the one
 * place the always-reserved row-23 space is guaranteed. A screen that rendered its
 * own alert would satisfy every visible expectation while silently opting out of
 * all three.
 *
 * Refactoring Rationale: the assertion is written against
 * `MESSAGE_BAND_TEST_ID` rather than against the message text or an alert role.
 * The band is deliberately contentless when empty -- it exposes no text, no role
 * and no accessible name -- so a text or role query can only ever observe the
 * populated state and could not distinguish "reserved space with no message"
 * from "no band at all". That is precisely the regression these cases exist to
 * catch, which is why the component exports a stable identifier for it.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it`,
 * `afterEach` and `waitFor` is a NAMED declaration rather than an inline arrow.
 * Two constraints meet here and only this shape satisfies both, and it is the
 * same shape `ui/src/routes/cards.test.ts` records: `ui/eslint.config.js`
 * selects a function expression in every position, so an inline callback needs
 * its own JSDoc block, and Prettier moves a block comment that follows an
 * argument comma onto the preceding string literal, which detaches the block
 * from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient
// global, because ui/vitest.config.ts sets `globals: false` and records that as a
// contract; admitting ambient globals here would make them visible to production
// screens as well.
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getCard, listCards, lookupCard, updateCard } from '../api/cards';
import type { CardDetail, CardSummary, PageResponse } from '../api/cards';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from '../routes/cards';
import { CardDetailScreen } from './cardDetail';
import { CardListScreen } from './cardList';
import { CardUpdateScreen } from './cardUpdate';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: this is a hoisted function DECLARATION rather than an inline
 * factory, and that is what makes it usable at all. Vitest lifts every
 * `vi.mock` call above the imports, so a factory held in a `const` would be in
 * its temporal dead zone at registration time; a function declaration is
 * hoisted with its binding initialised, and its body is not evaluated until the
 * mocked module is first imported.
 * @returns {Record<string, unknown>} The three transport functions, each a
 *   fresh spy whose behaviour an individual case sets.
 */
function mockCardTransportModule(): Record<string, unknown> {
  return {
    listCards: vi.fn(),
    getCard: vi.fn(),
    updateCard: vi.fn(),
    lookupCard: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client
 * beneath it. These cases assert how a screen PRESENTS an outcome, so the
 * shortest honest seam is the function the screen calls; going through the axios
 * client would additionally exercise the interceptor chain, which has its own
 * concerns and its own failure modes and would make a presentation regression
 * indistinguishable from a transport one.
 */
vi.mock('../api/cards', mockCardTransportModule);

// Assumptions: a card is addressed by an opaque SELECTOR, so a concrete route needs one of those and
// not a card number. This literal is synthetic: it has the published length and alphabet, so it
// satisfies the route guard, and it seals nothing, so it addresses no real card. Using a number here
// would make every route in this file fail its own guard.
const CARD_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-0000000000000';

const EMPTY_PAGE: PageResponse<CardSummary> = {
  items: [],
  firstKey: null,
  lastKey: null,
  hasNext: false,
  hasPrevious: false,
};

/*
 * Assumptions: the display card number is already masked to its last four
 * digits, because that is what the service returns for a non-administrative
 * read. A fixture carrying an unmasked number would put a PAN-shaped literal in
 * a test file for no assertion's benefit.
 */
const CARD: CardDetail = {
  key: CARD_SELECTOR,
  displayCardNumber: '************0011',
  accountId: '00000000011',
  embossedName: 'PAUL BUCK',
  expirationDate: '2023-01-20',
  activeStatus: 'Y',
  version: 1,
};

/**
 * Renders one screen at a concrete path inside an in-memory router.
 *
 * Assumptions: `MemoryRouter` rather than the application's own
 * `CardDemoRouter`, which wraps `BrowserRouter`. A memory router lets a case
 * mount one screen at one path without navigating a jsdom `history`, and it
 * keeps a case from depending on the whole route table -- so a route added or
 * renamed later cannot break an assertion about a message band.
 * @param {string} path - Initial location for the router.
 * @param {string} routePattern - Route pattern the element is mounted at.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderAt(path: string, routePattern: string, element: ReactElement): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={routePattern} element={element} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Asserts the band element exists, which it must in both states.
 * @returns {void} Nothing; throws when the band is absent.
 */
function expectBandPresent(): void {
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toBeInTheDocument();
}

/**
 * Waits for a rendered alert and asserts the band contains it.
 * @returns {Promise<void>} Resolves once the alert is inside the band.
 */
async function expectFailureInsideBand(): Promise<void> {
  const alert = await screen.findByRole('alert');
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toContainElement(alert);
}

/**
 * Restores the module mocks between cases so a rejection cannot leak forward.
 * @returns {void} Nothing.
 */
function resetTransportMocks(): void {
  vi.mocked(listCards).mockReset();
  vi.mocked(getCard).mockReset();
  vi.mocked(updateCard).mockReset();
  vi.mocked(lookupCard).mockReset();
}

/**
 * Asserts the browse screen reserves the band on a successful load.
 * @returns {Promise<void>} Resolves once the loaded page has settled.
 */
async function listReservesBandWhenThereIsNoMessage(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);

  await waitFor(expectBandPresent);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
}

/**
 * Asserts the browse screen routes a failed load into the band.
 * @returns {Promise<void>} Resolves once the failure has been rendered.
 */
async function listSendsFailureToTheBand(): Promise<void> {
  vi.mocked(listCards).mockRejectedValue(new Error('transport'));

  renderAt('/cards', '/cards', <CardListScreen />);

  await expectFailureInsideBand();
}

/**
 * Asserts the detail screen reserves an empty band on a successful read.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function detailReservesBandWhenThereIsNoMessage(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  expect(await screen.findByTestId(MESSAGE_BAND_TEST_ID)).toBeEmptyDOMElement();
}

/**
 * Asserts the detail screen routes a failed read into the band.
 * @returns {Promise<void>} Resolves once the failure has been rendered.
 */
async function detailSendsFailureToTheBand(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await expectFailureInsideBand();
}

/**
 * Asserts the update screen reserves an empty band once the form has loaded.
 * @returns {Promise<void>} Resolves once the form has been rendered.
 */
async function updateReservesBandWhenThereIsNoMessage(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);

  expect(await screen.findByTestId(MESSAGE_BAND_TEST_ID)).toBeEmptyDOMElement();
}

/**
 * Asserts the update screen routes a failed load into the band.
 * @returns {Promise<void>} Resolves once the failure has been rendered.
 */
async function updateSendsFailureToTheBand(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);

  await expectFailureInsideBand();
}

/**
 * Registers the six band cases, two per screen.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function cardScreenBandCases(): void {
  afterEach(resetTransportMocks);

  it(
    'reserves the band on the card list when there is no message',
    listReservesBandWhenThereIsNoMessage,
  );
  it('renders a card list failure inside the band', listSendsFailureToTheBand);
  it(
    'reserves the band on the card detail when there is no message',
    detailReservesBandWhenThereIsNoMessage,
  );
  it('renders a card detail failure inside the band', detailSendsFailureToTheBand);
  it(
    'reserves the band on the card update when there is no message',
    updateReservesBandWhenThereIsNoMessage,
  );
  it('renders a card update failure inside the band', updateSendsFailureToTheBand);
}

describe('card screens render outcomes through the shared message band', cardScreenBandCases);
