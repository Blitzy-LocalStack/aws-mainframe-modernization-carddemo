/**
 * @file Pins what happens when a screen's code fails to arrive, or a screen throws while rendering.
 *
 * Purpose
 * -------
 * Twenty of the twenty-one screens are fetched on first navigation to them, so a redeploy that
 * replaces the hashed asset names while a tab is still holding the previous document turns the next
 * navigation into a rejected dynamic import. That case had no surface at all: the throw reached the
 * root, React unmounted the tree, and the operator was left with a blank document. These cases pin
 * the three properties the replacement has to keep -- the frame survives, the operator is told
 * something they can act on, and the internal detail is not on the page.
 *
 * Assumptions: the failure is provoked with a `lazy` loader that REJECTS rather than with a child
 * that throws directly, because the rejected import is the failure this exists for and it arrives
 * through a different path -- thrown from React's own lazy initializer, one render after the
 * `Suspense` fallback. A synchronous throw would exercise the boundary without exercising the
 * interaction with `Suspense` that the router's corrected documentation now describes.
 *
 * Assumptions: `console.error` is silenced in the cases that provoke a failure, and its calls are
 * asserted rather than merely suppressed. React reports every boundary-caught error there in
 * development, so leaving it live would print two stack traces per case; asserting the call is what
 * keeps the diagnostic path -- console, not page -- a tested property instead of a claim in a comment.
 */

import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { lazy, Suspense } from 'react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_SHELL_TEST_ID, AppShell, SHELL_CONTENT_ELEMENT_ID } from './AppShell';
import { BoundedShellOutlet, ShellContentBoundary } from './ShellContentBoundary';
import { SCREEN_LOAD_FAILURE_MESSAGES } from '../messages/messages';

/**
 * Detail the provoked failure carries, which must reach the console and never the document.
 *
 * Assumptions: it is a distinctive string rather than a realistic message, so an assertion that it
 * is absent from the page cannot pass by accident on wording that merely differs.
 */
const INTERNAL_FAILURE_DETAIL = 'chunk-load-failure-internal-detail';

/** Path the failing route occupies. */
const FAILING_PATH = '/failing';

/** Path the healthy route occupies, standing in for a navigation away from the failure. */
const HEALTHY_PATH = '/healthy';

/** Body the healthy screen paints. */
const HEALTHY_BODY = 'HEALTHY SCREEN BODY';

/**
 * A screen whose chunk never arrives, standing in for an asset a redeploy has removed.
 *
 * Assumptions: the loader rejects rather than resolving to a broken module, which is what a 404 on a
 * hashed asset produces -- the dynamic import's promise rejects and React re-throws that error from
 * the lazy initializer on the next render.
 */
const UnreachableScreen = lazy(
  /**
   * Rejects as a failed chunk fetch does.
   * @returns {Promise<{ default: () => ReactElement }>} A promise that always rejects.
   */
  () => Promise.reject(new Error(INTERNAL_FAILURE_DETAIL)),
);

/**
 * A screen that renders, standing in for the destination of a navigation away from a failure.
 * @returns {ReactElement} A marker element.
 */
function HealthyScreen(): ReactElement {
  return <div>{HEALTHY_BODY}</div>;
}

/**
 * Composes the tree the router composes: the frame, the boundary nested inside it, then the routes.
 *
 * Assumptions: `Suspense` is placed ABOVE the frame exactly as `ui/src/router.tsx` places it, so the
 * pending and the rejected paths are exercised in the arrangement that ships rather than in a
 * convenient one.
 * @returns {ReactElement} The composed tree under test.
 */
function framedRoutes(): ReactElement {
  return (
    <MemoryRouter initialEntries={[FAILING_PATH]}>
      <Suspense fallback={<div>LOADING</div>}>
        <Routes>
          <Route element={<AppShell />}>
            <Route element={<BoundedShellOutlet />}>
              <Route path={FAILING_PATH} element={<UnreachableScreen />} />
              <Route path={HEALTHY_PATH} element={<HealthyScreen />} />
            </Route>
          </Route>
        </Routes>
      </Suspense>
    </MemoryRouter>
  );
}

/**
 * Returns the shell's content region, failing the case if the frame is not mounted.
 * @returns {HTMLElement} The element the frame nests routed content inside.
 */
function shellContentRegion(): HTMLElement {
  const region = document.getElementById(SHELL_CONTENT_ELEMENT_ID);
  expect(region, 'the frame must still be mounted').not.toBeNull();
  return region as HTMLElement;
}

/** Restores the console after a case that silenced it. */
afterEach(
  /**
   * Removes the console spy so a later case reports its own diagnostics.
   * @returns {void} Nothing; the spy is removed as a side effect.
   */
  () => {
    vi.restoreAllMocks();
  },
);

/**
 * A rejected chunk paints the failure surface INSIDE the frame, leaving the chrome mounted.
 *
 * Assumptions: containment is asserted, not co-presence. Both being somewhere in the document would
 * also hold if the surface had replaced the frame and the frame's own markup happened to remain,
 * which is the arrangement this whole mechanism exists to prevent.
 * @returns {Promise<void>} Resolves once the failure surface has been painted.
 */
async function containsAFailedChunkInsideTheFrame(): Promise<void> {
  vi.spyOn(console, 'error').mockImplementation(
    /**
     * Swallows the diagnostics React and the boundary both write while a case provokes a failure.
     * @returns {void} Nothing.
     */
    () => undefined,
  );

  render(framedRoutes());

  const heading = await screen.findByText(SCREEN_LOAD_FAILURE_MESSAGES.TITLE);

  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(within(shellContentRegion()).getByText(SCREEN_LOAD_FAILURE_MESSAGES.TITLE)).toBe(heading);
  expect(
    within(shellContentRegion()).getByText(SCREEN_LOAD_FAILURE_MESSAGES.EXPLANATION),
  ).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: SCREEN_LOAD_FAILURE_MESSAGES.RELOAD_CONTROL }),
  ).toBeInTheDocument();
}

/**
 * The thrown detail reaches the console and never the document.
 * @returns {Promise<void>} Resolves once the failure surface has been painted.
 */
async function reportsTheDetailToTheConsoleOnly(): Promise<void> {
  const reported = vi.spyOn(console, 'error').mockImplementation(
    /**
     * Captures the diagnostics instead of printing them.
     * @returns {void} Nothing.
     */
    () => undefined,
  );

  render(framedRoutes());
  await screen.findByText(SCREEN_LOAD_FAILURE_MESSAGES.TITLE);

  expect(
    document.body.textContent,
    'internal failure detail must not reach the document',
  ).not.toContain(INTERNAL_FAILURE_DETAIL);
  expect(
    reported.mock.calls.some(
      /**
       * Reports whether one console call is the boundary's own diagnostic.
       * @param {unknown[]} call - Arguments of one recorded call.
       * @returns {boolean} True when the call carries the boundary's prefix.
       */
      (call: unknown[]) => call[0] === 'carddemo: a screen failed to render',
    ),
    'the boundary must report the failure to the console',
  ).toBe(true);
}

/**
 * The single control performs the recovery, which in production reloads the document.
 *
 * Assumptions: the recovery is injected so the case can observe it. jsdom implements no navigation,
 * so a real `location.reload()` reports an unimplemented feature and asserts nothing about whether
 * the control is wired.
 * @returns {Promise<void>} Resolves once the control has been operated.
 */
async function offersARecoveryControl(): Promise<void> {
  vi.spyOn(console, 'error').mockImplementation(
    /**
     * Swallows the diagnostics while a case provokes a failure.
     * @returns {void} Nothing.
     */
    () => undefined,
  );
  const recover = vi.fn();
  const user = userEvent.setup();

  render(
    <MemoryRouter initialEntries={[FAILING_PATH]}>
      <Suspense fallback={<div>LOADING</div>}>
        <ShellContentBoundary recoveryKey="entry" onRecover={recover}>
          <UnreachableScreen />
        </ShellContentBoundary>
      </Suspense>
    </MemoryRouter>,
  );
  await screen.findByText(SCREEN_LOAD_FAILURE_MESSAGES.TITLE);

  await user.click(
    screen.getByRole('button', { name: SCREEN_LOAD_FAILURE_MESSAGES.RELOAD_CONTROL }),
  );

  expect(recover).toHaveBeenCalledTimes(1);
}

/**
 * A navigation clears a caught failure, so the failed screen does not outlive the address it was at.
 *
 * Assumptions: the caught state is cleared by a CHANGED recovery key rather than by remounting the
 * boundary, so this case drives the prop the router supplies from the history entry. Without the
 * reset, the content region would stay refused for the rest of the session even after the operator
 * moved somewhere that loads perfectly well.
 * @returns {Promise<void>} Resolves once the healthy child has been painted.
 */
async function clearsTheFailureOnTheNextNavigation(): Promise<void> {
  vi.spyOn(console, 'error').mockImplementation(
    /**
     * Swallows the diagnostics while a case provokes a failure.
     * @returns {void} Nothing.
     */
    () => undefined,
  );

  const { rerender } = render(
    <MemoryRouter initialEntries={[FAILING_PATH]}>
      <Suspense fallback={<div>LOADING</div>}>
        <ShellContentBoundary recoveryKey="entry">
          <UnreachableScreen />
        </ShellContentBoundary>
      </Suspense>
    </MemoryRouter>,
  );
  await screen.findByText(SCREEN_LOAD_FAILURE_MESSAGES.TITLE);

  rerender(
    <MemoryRouter initialEntries={[HEALTHY_PATH]}>
      <Suspense fallback={<div>LOADING</div>}>
        <ShellContentBoundary recoveryKey="after-navigation">
          <HealthyScreen />
        </ShellContentBoundary>
      </Suspense>
    </MemoryRouter>,
  );

  expect(await screen.findByText(HEALTHY_BODY)).toBeInTheDocument();
  expect(screen.queryByText(SCREEN_LOAD_FAILURE_MESSAGES.TITLE)).not.toBeInTheDocument();
}

/**
 * The route table nests the boundary inside the frame, which is what makes the containment hold.
 *
 * WHY : Assumptions: read from the source rather than rendered, for the reason
 * `ui/src/layout/appShellIntegration.test.tsx` records of the single-mount case -- the property is
 * syntactic, and rendering the real router to observe it would drag every screen module and the
 * runtime configuration reader into a case about one nesting relationship. A boundary moved ABOVE
 * the frame would still catch the same errors and would still pass every rendering case in this
 * file, while blanking the three delegated zones it exists to preserve.
 * @returns {void} Nothing; assertions raise on failure.
 */
function nestsTheBoundaryInsideTheFrame(): void {
  const routerSource = readFileSync(join(import.meta.dirname, '..', 'router.tsx'), 'utf8');

  /*
   * WHY : ⚠️ Refactoring Rationale: the three probes read the OBJECT form of a layout route, where they
   *       previously read the JSX element form `<Route element={<AppShell />}>`. The route table is
   *       declared as `RouteObject[]` and handed to `createBrowserRouter`, so the element-form strings
   *       are absent from the module and all three probes answered -1 -- a case that would have reported
   *       the boundary as unmounted while it was mounted correctly, which is the failure mode this file
   *       exists to rule out.
   * WHY : Assumptions: both mounts are counted as well as located, because the frame is mounted TWICE --
   *       one sibling layout route holds the public screen and the other the guarded subtree. Locating
   *       only the first pair would let a second frame be added with no boundary beneath it and still
   *       pass, which is precisely the gap an index comparison cannot see.
   */
  const frame = routerSource.indexOf('element: <AppShell />');
  const boundary = routerSource.indexOf('element: <BoundedShellOutlet />');
  const signOn = routerSource.indexOf('path: SIGN_ON_ROUTE, element:');
  const frames = routerSource.split('element: <AppShell />').length - 1;
  const boundaries = routerSource.split('element: <BoundedShellOutlet />').length - 1;

  expect(frame, 'the frame must be mounted as a layout route').toBeGreaterThan(-1);
  expect(boundary, 'the boundary must be mounted as a layout route').toBeGreaterThan(-1);
  expect(boundary, 'the boundary must be nested inside the frame').toBeGreaterThan(frame);
  expect(boundaries, 'every mounted frame must hold a boundary beneath it').toBe(frames);
  // Assumptions: sign-on is asserted to be INSIDE the boundary as well. It is imported eagerly so it
  //   cannot fail to load, but it can throw while rendering, and a boundary that covered every
  //   screen except the first one an operator meets would be the one gap nobody tests.
  expect(signOn, 'sign-on must be inside the boundary').toBeGreaterThan(boundary);
}

/**
 * Registers the shell-containment cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function shellContentBoundaryCases(): void {
  it('contains a failed chunk inside the frame', containsAFailedChunkInsideTheFrame);
  it('reports the failure detail to the console only', reportsTheDetailToTheConsoleOnly);
  it('offers a recovery control', offersARecoveryControl);
  it('clears the failure on the next navigation', clearsTheFailureOnTheNextNavigation);
  it('is nested inside the frame by the route table', nestsTheBoundaryInsideTheFrame);
}

describe('shell content boundary', shellContentBoundaryCases);
