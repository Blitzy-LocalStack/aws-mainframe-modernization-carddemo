/**
 * @file Holds the mount contract this application is built on: one router instance, built with the data
 * router API over the published route table, and exactly one provider of each kind, owned by `App`.
 *
 * Purpose
 * -------
 * This contract is the difference between a frame that appears once and a frame that appears twice, and
 * both halves have already been violated in this tree. `ui/src/App.tsx` records the second violation in
 * its own words: a remedy once mounted the shell HERE, wrapping the router as its `children`, while the
 * route table also carried it as a layout route -- and because the shell accepts either shape, every
 * guarded screen was framed twice, with two `app-shell` regions, two banners, two skip links and two
 * live regions announcing one message. The first violation was the opposite: a frame composed here out
 * of generic primitives while the real shell was imported by nothing.
 *
 * ⚠️ Refactoring Rationale: this contract WAS asserted, by matching the source TEXT of `ui/src/App.tsx`
 * and `ui/src/router.tsx` -- counting a mount site as a substring and checking the exact spelling of the
 * element that mounted the router. Those assertions were correctly withdrawn: the spellings they matched
 * no longer exist, so they would now fail for a reason that is not a defect, and a substring count could
 * only ever see the two files it read. But withdrawing them left the ownership half of the contract with
 * no check at all, so a second provider reintroduced in the route module would fail nothing. These cases
 * restore it WITHOUT reading source text: one asserts the exported router object, the other asserts the
 * rendered document, and both are properties a reader of the files cannot fake.
 *
 * Parameters
 * ----------
 * Not applicable. This module declares test cases and takes no inputs of its own.
 *
 * Return values
 * -------------
 * Not applicable. Each case reports through its expectations.
 *
 * Exceptions or errors
 * --------------------
 * None are raised here. A second router provider would surface as a render failure inside a case rather
 * than as an exception this module throws.
 *
 * Assumptions: the application is rendered at its own entry component rather than at a route, because
 * the property under test is what `App` owns. Driving a route directly would exercise the route table
 * and skip the two providers entirely, which is how the gap this file closes went unnoticed.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';
import { PF_KEY_BAR_REGION_LABEL } from './layout/PfKeyBar';
import { CARD_DEMO_ROUTES, cardDemoRouter } from './router';

/** The `data-testid` the shell puts on its own frame, so a second frame is countable. */
const APP_SHELL_TEST_ID = 'app-shell';

/**
 * Asserts the route module publishes ONE data router built over the published route table.
 *
 * Assumptions: the DATA router API is identified by the members it exposes rather than by the name of
 * the factory that produced it. `createBrowserRouter` returns an object carrying `routes`, `state`,
 * `subscribe` and `navigate`; the declarative `<BrowserRouter>` component this replaced returns a React
 * element with none of them. Asserting the surface therefore distinguishes the two APIs without matching
 * a source-text spelling, which is the whole point of restoring the check this way.
 *
 * Assumptions: the router's own route tree is compared against the SEPARATELY exported table, so the
 * two cannot drift. A router built over a different array -- a filtered copy, or a second table added
 * beside this one -- would satisfy every other assertion here and fail this one.
 * @returns {void} Nothing; the case asserts on the exported router.
 */
function theRouteModulePublishesOneDataRouter(): void {
  expect(cardDemoRouter).toBeTypeOf('object');
  expect(cardDemoRouter).toHaveProperty('state');
  expect(cardDemoRouter).toHaveProperty('subscribe');
  expect(cardDemoRouter).toHaveProperty('navigate');

  expect(cardDemoRouter.routes).toHaveLength(CARD_DEMO_ROUTES.length);
  expect(cardDemoRouter.routes[0]?.children).toHaveLength(
    CARD_DEMO_ROUTES[0]?.children?.length ?? -1,
  );
}

/**
 * Asserts rendering the application produces exactly one frame and one legend.
 *
 * Assumptions: ONE is the number asserted, not "at least one", because the defect this replaces
 * produced two of everything rather than none. A double mount renders a working application that looks
 * right in a screenshot and announces every message twice, so a presence check is precisely the check
 * that would have passed while the defect shipped.
 *
 * Assumptions: the assertion is made on the DOCUMENT rather than on the files, so a second frame
 * introduced anywhere reaches it -- a provider re-added to the route module, a shell wrapped around the
 * router again here, a second layout route added below the first, or a screen composing its own. The
 * withdrawn text form could only see the two files it read.
 * @returns {Promise<void>} Resolves once the mounted application has painted its frame.
 */
async function renderingTheApplicationProducesOneFrame(): Promise<void> {
  render(<App />);

  /*
   * WHY : Assumptions: the frame is awaited rather than read synchronously, because every screen in the
   *       route table is code-split and reached through a `Suspense` boundary, so the first commit is
   *       the fallback and the frame arrives with the resolved chunk.
   */
  expect(await screen.findByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();

  expect(screen.getAllByTestId(APP_SHELL_TEST_ID)).toHaveLength(1);
  expect(screen.getAllByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toHaveLength(1);
  expect(screen.getAllByRole('banner')).toHaveLength(1);
  expect(screen.getAllByRole('contentinfo')).toHaveLength(1);
}

/** Registers the mount-ownership cases. */
function mountOwnershipCases(): void {
  it('publishes one data router over the route table', theRouteModulePublishesOneDataRouter);
  it('renders exactly one frame', renderingTheApplicationProducesOneFrame);
}

describe('the application mount', mountOwnershipCases);
