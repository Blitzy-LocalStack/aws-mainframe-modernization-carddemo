/**
 * @file Proves neither card screen can be seeded by a read that a later read has superseded.
 *
 * Purpose
 * -------
 * Both screens read one record through `9000-READ-DATA` -- `app/cbl/COCRDSLC.cbl` L339 to L345 and
 * `app/cbl/COCRDUPC.cbl` L484 to L497 -- and both call it from more than one place: the mount effect and,
 * respectively, the Enter arm and the cancel arm. A terminal could not have two of those outstanding at
 * once, because a 3270 turn is serialised. A browser can, and it can for an ordinary reason: following a
 * second list row re-runs the effect while the first request is still in flight.
 *
 * What that cost, before the guard. On the detail screen the previous card's embossed name, expiry and
 * status could end up under the new card's route -- a record attributed to the wrong card. On the update
 * screen it is worse, because the retained record carries the optimistic-lock version the save sends: a
 * stale seed would have the form write against the version of a card the operator was no longer editing.
 *
 * Refactoring Rationale: each case drives the SECOND read to settle first and then releases the first,
 * which is the only arrangement that distinguishes a guarded screen from an unguarded one. Letting the
 * two settle in request order passes either way, and that is exactly why the defect survived review.
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
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getCard } from '../api/cards';
import type { CardDetail } from '../api/cards';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from '../routes/cards';
import { navigateSafely } from '../routes/navigation';
import { CardDetailScreen } from './cardDetail';
import { CardUpdateScreen } from './cardUpdate';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The four card operations, each a fresh spy.
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
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these cases
 * assert WHICH settlement a screen applies -- a property of the screen rather than of the interceptor
 * chain.
 */
vi.mock('../api/cards', mockCardTransportModule);

/** Selector of the card the operator navigates away from, with the published length and alphabet. */
const SUPERSEDED_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-000000000000a';

/** Selector of the card the operator navigates to. */
const CURRENT_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-000000000000b';

/** The record of the card that is no longer addressed, whose values must never reach the screen. */
const SUPERSEDED_CARD: CardDetail = {
  key: SUPERSEDED_SELECTOR,
  displayCardNumber: '************7777',
  accountId: '00000000011',
  embossedName: 'SUPERSEDED HOLDER',
  expirationDate: '2023-01-20',
  activeStatus: 'Y',
  version: 1,
};

/** The record of the addressed card, whose values must be the ones on display. */
const CURRENT_CARD: CardDetail = {
  key: CURRENT_SELECTOR,
  displayCardNumber: '************9999',
  accountId: '00000000022',
  embossedName: 'CURRENT HOLDER',
  expirationDate: '2027-06-30',
  activeStatus: 'N',
  version: 4,
};

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
 * Assumptions: this is how these cases express "outstanding". A read that is outstanding has not settled,
 * and a promise that has not resolved is exactly that -- so nothing here depends on one response being
 * faster than another, which is the assumption that let the defect read as correct.
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
 * How long a wait for an asynchronous condition is given, in milliseconds.
 *
 * Assumptions: this exceeds Testing Library's own one-second default deliberately. These cases mount a
 * whole screen and wait on a condition that needs a network settlement, a microtask and a React render,
 * and the suite runs 30-odd files in parallel workers under a container CPU quota -- so a wait that is
 * comfortable in isolation can exceed one second when every worker is busy. The file was observed passing
 * on its own and timing out inside a full run, which is a scheduling property and not a defect in the code
 * under test; raising the ceiling removes the flake without weakening any assertion, because a wait only
 * ever ends early on success.
 *
 * Alternatives Considered: raising Testing Library's global `asyncUtilTimeout` in `ui/src/test/setup.ts`.
 * Rejected because that module's own overview states it does exactly three things and deliberately nothing
 * else, and a global timeout would change the failure latency of every existing case to suit two files.
 */
const ASYNC_CONDITION_TIMEOUT_MS = 5000;

/** Accessible name of the control each case uses to reach the second card. */
const FOLLOW_CONTROL_NAME = 'follow the second card';

/**
 * Renders a control that navigates to the second card inside the router the screen is mounted in.
 *
 * Assumptions: the navigation happens THROUGH the router rather than by re-rendering a second router,
 * which was tried first and does not reproduce the scenario: `MemoryRouter` reads `initialEntries` once,
 * so a re-render with a different entry leaves the location where it was and only one read is ever issued.
 * Navigating inside the router is also what a real operator does -- both paths match the same route
 * pattern, so React Router reuses the mounted element, the screen does NOT remount, and its effect re-runs
 * against a changed parameter while the first request is still outstanding. That is the scenario the
 * finding describes, and a remount would not be it: a remounted screen holds a fresh generation counter,
 * so the first answer would be discarded by construction rather than by the guard.
 * @param {object} props - Component input.
 * @param {string} props.to - Path to navigate to.
 * @returns {ReactElement} The control.
 */
function FollowControl({ to }: { readonly to: string }): ReactElement {
  const navigate = useNavigate();

  /**
   * Navigates to the second card.
   *
   * Assumptions: the transition goes through `navigateSafely`, which is the same helper every authored
   * screen navigates with. `navigate` returns a promise under a data router and
   * `@typescript-eslint/no-floating-promises` refuses an unhandled one, so calling it bare here would
   * either fail the lint gate or need a rejection handler this file would have to invent.
   * @returns {void} Nothing.
   */
  function follow(): void {
    navigateSafely(navigate, to);
  }

  return (
    <button onClick={follow} type="button">
      {FOLLOW_CONTROL_NAME}
    </button>
  );
}

/**
 * Mounts one screen at the superseded card's path, beside a control that follows the current card.
 * @param {string} routePattern - Route pattern the element is mounted at.
 * @param {string} firstPath - Path of the card the screen opens on.
 * @param {string} secondPath - Path the control navigates to.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderFollowable(
  routePattern: string,
  firstPath: string,
  secondPath: string,
  element: ReactElement,
): void {
  render(
    <MemoryRouter initialEntries={[firstPath]}>
      <Routes>
        <Route
          path={routePattern}
          element={
            <>
              <FollowControl to={secondPath} />
              {element}
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Restores the module mocks between cases so one case's answer cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetCardMocks(): void {
  vi.mocked(getCard).mockReset();
}

/**
 * Asserts the detail screen keeps the addressed card when a superseded read settles afterwards.
 * @returns {Promise<void>} Resolves once both settlements have been delivered.
 */
async function detailDiscardsASupersededRead(): Promise<void> {
  const supersededRead = deferred<CardDetail>();
  const currentRead = deferred<CardDetail>();

  vi.mocked(getCard)
    .mockReturnValueOnce(supersededRead.promise)
    .mockReturnValueOnce(currentRead.promise);

  renderFollowable(
    CARD_DETAIL_ROUTE,
    `/cards/${SUPERSEDED_SELECTOR}`,
    `/cards/${CURRENT_SELECTOR}`,
    <CardDetailScreen />,
  );

  await userEvent.click(screen.getByRole('button', { name: FOLLOW_CONTROL_NAME }));

  await waitFor(
    /**
     * Waits until both reads have been issued.
     * @returns {void} Nothing; throws until the second call is recorded.
     */
    () => {
      expect(vi.mocked(getCard)).toHaveBeenCalledTimes(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  currentRead.settle(CURRENT_CARD);
  await waitFor(
    /**
     * Waits until the addressed card is on display.
     * @returns {void} Nothing; throws until its holder name has rendered.
     */
    () => {
      expect(screen.getByText(CURRENT_CARD.embossedName)).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  /*
   * WHY : Assumptions: the superseded settlement is released INSIDE `act`, so every update it schedules
   *       has flushed before the assertions run. Awaiting the promise alone is not enough: the screen's
   *       continuation runs in a later microtask and React may batch the render it schedules, so a
   *       negative assertion would pass against a screen that was about to show the wrong record.
   */
  await act(
    /**
     * Releases the superseded settlement and lets every update it schedules flush.
     * @returns {Promise<void>} Resolves once the settlement has been delivered.
     */
    async (): Promise<void> => {
      supersededRead.settle(SUPERSEDED_CARD);
      await supersededRead.promise;
    },
  );

  expect(screen.getByText(CURRENT_CARD.embossedName)).toBeInTheDocument();
  expect(screen.queryByText(SUPERSEDED_CARD.embossedName)).toBeNull();
}

/**
 * Asserts the update form keeps the addressed card's values when a superseded read settles afterwards.
 *
 * Assumptions: the assertion reads the embossed-name CONTROL's value rather than a rendered string,
 * because this screen seeds a form -- a stale answer would show up as the wrong value inside an input, and
 * it is the input the save reads from.
 * @returns {Promise<void>} Resolves once both settlements have been delivered.
 */
async function updateDiscardsASupersededRead(): Promise<void> {
  const supersededRead = deferred<CardDetail>();
  const currentRead = deferred<CardDetail>();

  vi.mocked(getCard)
    .mockReturnValueOnce(supersededRead.promise)
    .mockReturnValueOnce(currentRead.promise);

  renderFollowable(
    CARD_EDIT_ROUTE,
    `/cards/${SUPERSEDED_SELECTOR}/edit`,
    `/cards/${CURRENT_SELECTOR}/edit`,
    <CardUpdateScreen />,
  );

  await userEvent.click(screen.getByRole('button', { name: FOLLOW_CONTROL_NAME }));

  await waitFor(
    /**
     * Waits until both reads have been issued.
     * @returns {void} Nothing; throws until the second call is recorded.
     */
    () => {
      expect(vi.mocked(getCard)).toHaveBeenCalledTimes(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  currentRead.settle(CURRENT_CARD);
  await waitFor(
    /**
     * Waits until the addressed card has seeded the form.
     * @returns {void} Nothing; throws until its holder name is in a control.
     */
    () => {
      expect(screen.getByDisplayValue(CURRENT_CARD.embossedName)).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await act(
    /**
     * Releases the superseded settlement and lets every update it schedules flush.
     * @returns {Promise<void>} Resolves once the settlement has been delivered.
     */
    async (): Promise<void> => {
      supersededRead.settle(SUPERSEDED_CARD);
      await supersededRead.promise;
    },
  );

  expect(screen.getByDisplayValue(CURRENT_CARD.embossedName)).toBeInTheDocument();
  expect(screen.queryByDisplayValue(SUPERSEDED_CARD.embossedName)).toBeNull();
}

/**
 * Registers the two cases.
 * @returns {void} Nothing.
 */
function cardReadSequencingCases(): void {
  afterEach(resetCardMocks);

  it('keeps the addressed card on the detail screen', detailDiscardsASupersededRead);
  it('keeps the addressed card in the update form', updateDiscardsASupersededRead);
}

describe('the card screens apply only the current record read', cardReadSequencingCases);
