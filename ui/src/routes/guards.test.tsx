/**
 * @file Component tests for the authentication and authorisation guards in
 * `ui/src/routes/guards.tsx`.
 *
 * Purpose
 * -------
 * Assert what each guard RENDERS in each of its states: a caller with no token is redirected to
 * sign-on, a caller holding one is admitted, and a caller outside the administrative group receives
 * the refusal message verbatim from the catalog.
 *
 * Assumptions: the guards decide rendering only, never permission. Every service independently
 * validates the token, so a case here proves the screen an operator sees and proves nothing about
 * data access -- which is why no case below asserts that a request was refused.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement, ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ACCESS_DENIED_ADMIN_ONLY } from '../messages/messages';

// Assumptions: the catalog constant is asserted TRIMMED, and the trim belongs to the assertion rather
// than to the screen. The baseline field is fixed-width, so the constant carries the trailing blanks
// that padding produced, and the guard renders it verbatim to preserve that contract; the DOM then
// collapses trailing whitespace for display and Testing Library matches on the normalized text. The
// constant is still the source of the expectation, so a change to the baseline wording fails here.
const ACCESS_DENIED_TEXT = ACCESS_DENIED_ADMIN_ONLY.trim();
import { RequireAdmin, RequireSignOn } from './guards';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { SESSION_USER_ID, endAnySession, establishSession } from '../test/sessionHarness';

/*
 * WHY : ⚠️ Refactoring Rationale: each case below used to arrange its caller by WRITING an identity
 *       token into `sessionStorage` under `carddemo.id-token`. That key no longer exists -- a review
 *       found every credential this application held sitting in script-readable Web Storage, and the
 *       session moved into memory behind one installer that validates the whole token set -- so a
 *       session can now be arranged only by performing an exchange, which `establishSession` does.
 * WHY : Trade-offs: the arrangement costs a rendered hook and an answered request per case, where it
 *       used to cost one `setItem`. What it buys is that the caller each case guards is a caller the
 *       application could actually produce: a seeded token set was never validated, so a case could
 *       assert on authority carried by a set the real sign-on path refuses outright.
 */

/**
 * Renders a guarded subtree at `/protected`, with sign-on reachable at its own route.
 * @param {ReactNode} guard - The guard element wrapping the protected screen.
 * @returns {ReactElement} The composed tree under test.
 */
function renderGuarded(guard: ReactNode): ReactElement {
  return (
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route path="/protected" element={guard} />
        <Route path="/signon" element={<div>SIGN ON SCREEN</div>} />
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Discards any held session and installs the request harness the arrangement answers itself from.
 *
 * Assumptions: the harness is needed even though no case here dispatches a request of its own,
 * because arranging a signed-on caller performs a real sign-on exchange and that exchange has to be
 * answered locally.
 * @returns {void} Nothing; no session is held and the harness is installed.
 */
function clearSessionAndInstallHarness(): void {
  endAnySession();
  installApiHarness();
}

/**
 * Discards the session and removes the harness, so no later file inherits either.
 * @returns {void} Nothing; no session is held and the harness is removed.
 */
function clearSessionAndRemoveHarness(): void {
  removeApiHarness();
  endAnySession();
}

/**
 * An unauthenticated caller is sent to sign-on and never sees the protected screen.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function redirectsAnUnauthenticatedCallerToSignOn(): void {
  render(renderGuarded(<RequireSignOn>{<div>PROTECTED</div>}</RequireSignOn>));

  expect(screen.getByText('SIGN ON SCREEN')).toBeInTheDocument();
  expect(screen.queryByText('PROTECTED')).not.toBeInTheDocument();
}

/**
 * A caller holding an established session reaches the protected screen.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function admitsACallerHoldingAToken(): Promise<void> {
  await establishSession({ groups: ['carddemo-user'] });

  render(renderGuarded(<RequireSignOn>{<div>PROTECTED</div>}</RequireSignOn>));

  expect(screen.getByText('PROTECTED')).toBeInTheDocument();
}

/**
 * An unauthenticated caller is sent to sign-on from an administrative route too.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function redirectsAnUnauthenticatedCallerAwayFromAnAdministrativeRoute(): void {
  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.getByText('SIGN ON SCREEN')).toBeInTheDocument();
}

/**
 * An authenticated non-administrator is refused rather than redirected.
 *
 * Assumptions: an authenticated non-administrator is REFUSED rather than redirected, and the
 * distinction is the point. They are signed on correctly, so sending them to sign-on would invite
 * them to fix something that is not broken.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function refusesAnAuthenticatedCallerWithoutTheAdministrativeGroup(): Promise<void> {
  await establishSession({ groups: ['carddemo-user'] });

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  expect(screen.queryByText('SIGN ON SCREEN')).not.toBeInTheDocument();
  expect(screen.getByText(ACCESS_DENIED_TEXT)).toBeInTheDocument();
}

/**
 * A claim carrying the administrative group reaches the administrative screen.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function admitsACallerWhoseClaimCarriesTheAdministrativeGroup(): Promise<void> {
  await establishSession({ groups: ['carddemo-admin', 'carddemo-user'] });

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.getByText('ADMIN ONLY')).toBeInTheDocument();
}

/**
 * A group claim in an unagreed shape carries no groups, so the administrative surface stays closed.
 *
 * Assumptions: the failure direction matters: a decoder that returned every group on a claim it could
 * not read would open the surface to any token that carried one.
 *
 * Refactoring Rationale: ⚠️ this case used to seed the literal string `'not-a-token'` as the identity
 * token, and it can no longer do so — `installTokens` refuses a set whose identity token yields no
 * subject at all, so a wholly undecodable token cannot establish a session in the first place. That is
 * a STRONGER guarantee than this case asserted and it is covered where it is enforced, so the case is
 * reframed onto the weaker condition that survives: a token that decodes, names the right operator, and
 * carries its group claim in a shape this application never agreed with the pool. A lone group
 * serialised as a bare string is the realistic form, and coercing it would let that shape decide an
 * authority question.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function treatsAnUndecodableTokenAsCarryingNoGroups(): Promise<void> {
  const claims = JSON.stringify({
    'cognito:username': SESSION_USER_ID,
    'cognito:groups': 'carddemo-admin',
  });
  await establishSession({ idToken: `header.${btoa(claims)}.signature` });

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  expect(screen.getByText(ACCESS_DENIED_TEXT)).toBeInTheDocument();
}

/**
 * Registers the six route-guard cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function routeGuardCases(): void {
  it('redirects an unauthenticated caller to sign-on', redirectsAnUnauthenticatedCallerToSignOn);
  it('admits a caller holding a token', admitsACallerHoldingAToken);
  it(
    'redirects an unauthenticated caller away from an administrative route',
    redirectsAnUnauthenticatedCallerAwayFromAnAdministrativeRoute,
  );
  it(
    'refuses an authenticated caller without the administrative group',
    refusesAnAuthenticatedCallerWithoutTheAdministrativeGroup,
  );
  it(
    'admits a caller whose claim carries the administrative group',
    admitsACallerWhoseClaimCarriesTheAdministrativeGroup,
  );
  it(
    'treats a group claim in an unagreed shape as carrying no groups',
    treatsAnUndecodableTokenAsCarryingNoGroups,
  );
}

beforeEach(clearSessionAndInstallHarness);

afterEach(clearSessionAndRemoveHarness);

describe('route guards', routeGuardCases);
