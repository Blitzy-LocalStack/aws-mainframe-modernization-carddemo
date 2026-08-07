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

/** Session-storage key the auth hook reads the identity token from. */
const ID_TOKEN_KEY = 'carddemo.id-token';

/**
 * Builds an identity token carrying the supplied group names.
 * @param {readonly string[]} groups - Group names to place in the claim.
 * @returns {string} A three-segment token whose claim segment decodes to those groups.
 */
function idTokenFor(groups: readonly string[]): string {
  return `header.${btoa(JSON.stringify({ 'cognito:groups': groups }))}.signature`;
}

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
 * Empties session storage so no case inherits another's credential.
 * @returns {void} Nothing; storage is cleared in place.
 */
function clearStoredSession(): void {
  sessionStorage.clear();
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
 * A caller holding any identity token reaches the protected screen.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function admitsACallerHoldingAToken(): void {
  sessionStorage.setItem(ID_TOKEN_KEY, idTokenFor(['carddemo-user']));

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
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function refusesAnAuthenticatedCallerWithoutTheAdministrativeGroup(): void {
  sessionStorage.setItem(ID_TOKEN_KEY, idTokenFor(['carddemo-user']));

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  expect(screen.queryByText('SIGN ON SCREEN')).not.toBeInTheDocument();
  expect(screen.getByText(ACCESS_DENIED_TEXT)).toBeInTheDocument();
}

/**
 * A claim carrying the administrative group reaches the administrative screen.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function admitsACallerWhoseClaimCarriesTheAdministrativeGroup(): void {
  sessionStorage.setItem(ID_TOKEN_KEY, idTokenFor(['carddemo-admin', 'carddemo-user']));

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.getByText('ADMIN ONLY')).toBeInTheDocument();
}

/**
 * A token that cannot be decoded carries no groups, so the administrative surface stays closed.
 *
 * Assumptions: the failure direction matters: a decoder that returned every group on a parse
 * failure would open the surface to a malformed token.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function treatsAnUndecodableTokenAsCarryingNoGroups(): void {
  sessionStorage.setItem(ID_TOKEN_KEY, 'not-a-token');

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
    'treats an undecodable token as carrying no groups',
    treatsAnUndecodableTokenAsCarryingNoGroups,
  );
}

beforeEach(clearStoredSession);

afterEach(clearStoredSession);

describe('route guards', routeGuardCases);
