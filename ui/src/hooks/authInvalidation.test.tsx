/**
 * @file Integration test for the re-authentication path: what a bearer-authenticated HTTP 401 does to
 * the session `ui/src/hooks/useAuth.ts` holds and to the screen `ui/src/routes/guards.tsx` renders.
 *
 * Purpose
 * -------
 * Assert that a 401 answered to a request which CARRIED a bearer token clears every stored identity
 * key and returns the operator to sign-on. `ui/src/api/client.ts` signals re-authentication rather
 * than performing it, and the signal is only worth anything if something subscribes: before the
 * subscription in `useAuth` existed, the interceptor discarded the access token alone, leaving the
 * identity token, the refresh token and the retained identifier in place — so the guards, which read
 * `signedOn` from the identity token, kept rendering protected screens for a session every service
 * had stopped accepting.
 *
 * Assumptions: this is deliberately an INTEGRATION case spanning the interceptor, the hook and the
 * guard, rather than three unit cases. The defect was not inside any one of them: each behaved as
 * written, and the gap was the absent wiring between them, which only a case that crosses all three
 * can observe.
 *
 * Assumptions: the network is answered locally by an adapter installed on the real client, so the
 * request travels through the module's real interceptors — which is what makes the 401 arrive with the
 * `Authorization` header the interceptor requires before it invalidates anything.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, waitFor } from '@testing-library/react';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient, resetApiClient } from '../api/client';
import { RequireSignOn } from '../routes/guards';

const API_BASE_URL = 'https://api.carddemo.example';

const CORRELATION_HEADER = 'X-Correlation-Id';

const UNAUTHORIZED_STATUS = 401;

/**
 * Every session-storage key an established session writes, so the assertion can name all of them.
 *
 * Assumptions: the list is exhaustive rather than a sample, and that is the whole point of the case.
 * The defect was a PARTIAL clear, so an assertion covering only the key that was already being cleared
 * would have passed against the defect.
 */
const SESSION_KEYS = [
  'carddemo.access-token',
  'carddemo.id-token',
  'carddemo.user-id',
  'carddemo.refresh-token',
] as const;

/**
 * Builds an identity token carrying the supplied group names.
 *
 * Assumptions: only the claim segment has to decode, because the hook never verifies the signature —
 * every service does that independently — so a three-segment token with placeholder header and
 * signature is exactly what the decoder is written against.
 * @param {readonly string[]} groups - Group names to place in the claim.
 * @returns {string} A three-segment token whose claim segment decodes to those groups.
 */
function idTokenFor(groups: readonly string[]): string {
  return `header.${btoa(JSON.stringify({ 'cognito:groups': groups }))}.signature`;
}

/**
 * Answers one request with 401 and no body, without reaching a network.
 *
 * Assumptions: the adapter REJECTS with a constructed `AxiosError` rather than resolving the refusing
 * status, because Axios applies `validateStatus` inside its own built-in adapters and passes whatever a
 * CUSTOM adapter resolves straight through. A resolved 401 therefore reaches the caller as a success
 * and never enters the failure interceptor at all, which is the opposite of what this case measures.
 *
 * Assumptions: the error carries the real `config` — including the `Authorization` header the request
 * interceptor added — because the interceptor invalidates a session only for a 401 whose request
 * actually carried a bearer, and it reads that fact from exactly this object.
 * @param {InternalAxiosRequestConfig} config - The request configuration after the interceptors ran.
 * @returns {Promise<never>} A rejection carrying the 401 and the originating configuration.
 */
function unauthorizedAdapter(config: InternalAxiosRequestConfig): Promise<never> {
  const response = {
    data: {},
    status: UNAUTHORIZED_STATUS,
    statusText: 'Unauthorized',
    headers: {},
    config,
  } as AxiosResponse;
  return Promise.reject(
    new AxiosError(
      `Request failed with status code ${String(UNAUTHORIZED_STATUS)}`,
      AxiosError.ERR_BAD_REQUEST,
      config,
      null,
      response,
    ),
  );
}

/**
 * Renders a guarded screen at `/protected`, with sign-on reachable at its own route.
 * @returns {ReactElement} The composed tree under test.
 */
function renderGuardedScreen(): ReactElement {
  return (
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route
          path="/protected"
          element={
            <RequireSignOn>
              <div>PROTECTED</div>
            </RequireSignOn>
          }
        />
        <Route path="/signon" element={<div>SIGN ON SCREEN</div>} />
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Writes the four keys an established session holds for this tab.
 * @returns {void} Nothing; storage is populated in place.
 */
function establishSession(): void {
  sessionStorage.setItem('carddemo.access-token', 'an-accepted-access-token');
  sessionStorage.setItem('carddemo.id-token', idTokenFor(['carddemo-user']));
  sessionStorage.setItem('carddemo.user-id', 'USER0001');
  sessionStorage.setItem('carddemo.refresh-token', 'a-refresh-token');
}

/** Supplies the build-time configuration the client validates before it is constructed. */
function stubBuildConfiguration(): void {
  sessionStorage.clear();
  resetApiClient();
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  vi.stubEnv('VITE_CORRELATION_ID_HEADER', CORRELATION_HEADER);
}

/** Restores the environment and the storage so no later file inherits this file's state. */
function restoreBuildConfiguration(): void {
  vi.unstubAllEnvs();
  sessionStorage.clear();
  resetApiClient();
}

/**
 * Dispatches one authenticated request that is refused, and waits for the refusal to be delivered.
 * @returns {Promise<void>} Resolves once the request has been rejected by the client.
 */
async function dispatchARefusedAuthenticatedRequest(): Promise<void> {
  const client = getApiClient();
  client.defaults.adapter = unauthorizedAdapter;
  await expect(client.get('/api/v1/cards')).rejects.toBeDefined();
}

/**
 * Asserts the last key an invalidation removes is gone.
 *
 * Assumptions: a HOISTED named function rather than an inline arrow inside `waitFor`, because
 * ui/eslint.config.js selects `* > ArrowFunctionExpression` for `jsdoc/require-jsdoc`, and a block
 * comment on an inline argument is moved by Prettier onto the preceding expression.
 * @returns {void} Nothing; the assertion carries the outcome and `waitFor` retries it.
 */
function refreshTokenHasBeenRemoved(): void {
  expect(sessionStorage.getItem('carddemo.refresh-token')).toBeNull();
}

/**
 * A bearer-authenticated 401 clears every stored identity key, not only the access token.
 * @returns {Promise<void>} Resolves once every key has been asserted absent.
 */
async function clearsEveryStoredIdentityKeyOnAnAuthenticatedRefusal(): Promise<void> {
  establishSession();
  render(renderGuardedScreen());
  expect(screen.getByText('PROTECTED')).toBeInTheDocument();

  await dispatchARefusedAuthenticatedRequest();

  // Assumptions: the wait is on the LAST key to be removed rather than on a fixed delay, because the
  //   interceptor notifies its listeners in a microtask so that a listener cannot replace the failure
  //   the caller is awaiting. Polling for the observable outcome is what makes this case independent
  //   of that scheduling decision.
  await waitFor(refreshTokenHasBeenRemoved);
  for (const key of SESSION_KEYS) {
    expect(sessionStorage.getItem(key)).toBeNull();
  }
}

/**
 * The guard sends the operator back to sign-on once the refused session has been discarded.
 *
 * Assumptions: the rendered route is asserted as well as the storage, because clearing storage is only
 * half of what was broken. The hook holds its state per instance in `useState`, so a clear that did
 * not also reset that state would leave the guard rendering the protected screen from a stale value
 * until something unrelated re-rendered it.
 * @returns {Promise<void>} Resolves once the redirect has been observed.
 */
async function returnsTheOperatorToSignOnAfterAnAuthenticatedRefusal(): Promise<void> {
  establishSession();
  render(renderGuardedScreen());
  expect(screen.getByText('PROTECTED')).toBeInTheDocument();

  await dispatchARefusedAuthenticatedRequest();

  expect(await screen.findByText('SIGN ON SCREEN')).toBeInTheDocument();
  expect(screen.queryByText('PROTECTED')).not.toBeInTheDocument();
}

/**
 * A 401 answered to a request that carried NO bearer token leaves the rest of the session alone.
 *
 * Assumptions: this is the counter-case that keeps the fix from becoming a different defect. The
 * sign-on contract answers 401 for a rejected credential — one status deliberately covering both a
 * wrong password and an unknown identifier — so clearing on every 401 would report "signed out" to an
 * operator mistyping a password, and would discard a session that request never used.
 *
 * Assumptions: the no-bearer condition is produced by removing the stored ACCESS token rather than by
 * suppressing the header on one request, because the request interceptor sets that header from storage
 * unconditionally whenever a token is there and would overwrite a per-request override. Removing it is
 * also the more faithful model: an absent access token is exactly why a sign-on request carries no
 * bearer, and the three keys asserted below are the ones that must survive a rejected credential.
 * @returns {Promise<void>} Resolves once the surviving keys have been asserted.
 */
async function leavesTheSessionIntactWhenTheRefusedRequestCarriedNoToken(): Promise<void> {
  establishSession();
  sessionStorage.removeItem('carddemo.access-token');
  render(renderGuardedScreen());

  const client = getApiClient();
  client.defaults.adapter = unauthorizedAdapter;
  await expect(client.get('/api/v1/auth/signon')).rejects.toBeDefined();

  // Assumptions: a settled microtask queue is awaited before asserting, so this case would observe an
  //   over-eager clear rather than racing it. Asserting immediately would pass even if the interceptor
  //   had scheduled one, because the signal is delivered in a microtask.
  await Promise.resolve();
  for (const key of ['carddemo.id-token', 'carddemo.user-id', 'carddemo.refresh-token']) {
    expect(sessionStorage.getItem(key)).not.toBeNull();
  }
  expect(screen.getByText('PROTECTED')).toBeInTheDocument();
}

/**
 * Registers the three re-authentication cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function reAuthenticationCases(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it(
    'clears every stored identity key on an authenticated refusal',
    clearsEveryStoredIdentityKeyOnAnAuthenticatedRefusal,
  );
  it(
    'returns the operator to sign-on after an authenticated refusal',
    returnsTheOperatorToSignOnAfterAnAuthenticatedRefusal,
  );
  it(
    'leaves the session intact when the refused request carried no token',
    leavesTheSessionIntactWhenTheRefusedRequestCarriedNoToken,
  );
}

describe('re-authentication on an unauthorized response', reAuthenticationCases);
