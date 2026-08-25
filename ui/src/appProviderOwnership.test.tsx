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
 * Class the design system puts on the element that carries its reset.
 *
 * Assumptions: written as a literal rather than imported, because the library exports no constant for
 * it -- the class is composed from its own prefix at render time -- and because a test that derived the
 * name from the same source the component does could not tell a renamed class from a missing wrapper.
 */
const ANT_RESET_SCOPE_CLASS = 'ant-app';

/**
 * Scope key the theme emits its CSS custom properties under.
 *
 * Assumptions: this must equal `cssVar.key` in `ui/src/theme/antdTheme.ts`. It is retyped rather than
 * imported for the reason that module records for choosing an explicit key at all: the value is a
 * contract with the emitted stylesheet, so a case that read it from the theme would follow a rename
 * silently instead of reporting that the emitted scope had moved.
 */
const THEME_SCOPE_KEY = 'carddemo';

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

/**
 * Asserts the application establishes exactly one reset scope, and that the frame stays inside it.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it pins a scope that was measured ABSENT. A browser
 * measurement of the running application found `document.body` computing `font-family` as
 * `"Times New Roman"` and `color` as `rgb(0, 0, 0)`, with no element matching `[class*=ant-app]`
 * anywhere in the document: the theme reached every antd component and nothing else, so any text node
 * that is not itself a component rendered in the user agent's serif default. A jsdom probe of this
 * exact tree counted zero such elements before the wrapper was added and one after.
 *
 * Assumptions: ONE is the number asserted, for the same reason the frame count is. The wrapper carries
 * the CSS-variable scope, so a second one nested inside the first would redefine every `--ant-*`
 * property mid-document -- which renders correctly while the two agree and diverges silently the
 * moment a second provider is given a different theme, exactly the class of defect the single-provider
 * rule exists to prevent.
 *
 * Assumptions: the wrapper is required to generate NO layout box. `ui/index.html` sizes the mount point
 * as a flex column one viewport tall so the frame's key legend sits at the bottom edge; a normally
 * displayed element between the two would take that height for itself and the frame inside it would
 * size to its content. The declaration is therefore part of the contract rather than a detail of how it
 * was implemented.
 *
 * Assumptions: the frame is required to be INSIDE the wrapper rather than merely present. Inheritance
 * carries the reset and the variable scope down the element tree, so a wrapper rendered beside the
 * frame would satisfy a presence check while reaching nothing an operator can see.
 * @returns {Promise<void>} Resolves once the mounted application has painted its frame.
 */
async function theApplicationEstablishesOneResetScope(): Promise<void> {
  render(<App />);

  const frame = await screen.findByTestId(APP_SHELL_TEST_ID);

  const scopes = document.querySelectorAll(`.${ANT_RESET_SCOPE_CLASS}`);
  expect(scopes).toHaveLength(1);

  const scope = scopes[0];
  expect(scope, 'the reset scope must be an element').toBeInstanceOf(HTMLElement);
  expect((scope as HTMLElement).style.display).toBe('contents');
  expect(scope?.contains(frame), 'the frame must render inside the reset scope').toBe(true);

  /*
   * WHY : Assumptions: the CSS-variable scope is asserted on the same element, because the wrapper is
   *       what makes the token layers reach a NON-component element. `ui/src/theme/antdTheme.ts` sets
   *       `cssVar: { key: 'carddemo' }`, so the library emits the `--ant-*` properties under the
   *       selector `.carddemo` and applies that class to what it renders -- sixty separate elements in
   *       this tree, each resolving only for itself. Carrying it here puts one scope above everything,
   *       which is the difference between a token a plain element can read and one it cannot.
   * WHY : Assumptions: the emitted DEFINITION is checked as well as the class, because a class alone
   *       proves nothing about what it resolves -- the properties are defined by an injected sheet, and
   *       a scope key that failed to register would leave the class on the element with nothing behind
   *       it. `--ant-color-primary` is the property checked for because the bridge's own dominant role
   *       maps onto it: `COLOR=BLUE` at 289 occurrences across the seventeen mapsets.
   */
  expect(scope?.classList.contains(THEME_SCOPE_KEY)).toBe(true);

  const definitions = [...document.querySelectorAll('style')].filter(
    /**
     * Reports whether one style element defines the theme scope's custom properties.
     * @param {Element} node - One style element from the document.
     * @returns {boolean} `true` when this element opens the scoped custom-property block.
     */
    function definesTheThemeScope(node: Element): boolean {
      return (node.textContent ?? '').includes(`.${THEME_SCOPE_KEY}{--ant-`);
    },
  );
  expect(definitions.length).toBeGreaterThan(0);
  expect(
    definitions.some(
      /**
       * Reports whether one style element defines the primary colour property.
       * @param {Element} node - One style element that defines the theme scope.
       * @returns {boolean} `true` when the primary colour is among the properties it declares.
       */
      function declaresThePrimaryColour(node: Element): boolean {
        return (node.textContent ?? '').includes('--ant-color-primary:');
      },
    ),
  ).toBe(true);
}

/** Registers the mount-ownership cases. */
function mountOwnershipCases(): void {
  it('publishes one data router over the route table', theRouteModulePublishesOneDataRouter);
  it('renders exactly one frame', renderingTheApplicationProducesOneFrame);
  it(
    'establishes exactly one reset scope around the frame',
    theApplicationEstablishesOneResetScope,
  );
}

describe('the application mount', mountOwnershipCases);
