/**
 * @file Integration test for the re-authentication signal, from the transport that raises it to the
 * session state that answers it.
 *
 * Purpose
 * -------
 * `ui/src/api/client.ts` discards the access token it owns when a request carrying one is answered 401,
 * and announces the fact; `ui/src/hooks/useAuth.ts` owns every other session value and subscribes to
 * that announcement. Neither half is a complete sign-out, so neither half can be asserted alone: the
 * client's own unit tests prove the token is discarded and the listeners are told, and this file proves
 * a listener EXISTS in production and that what it does is discard the identity token, the retained
 * identifier and the refresh token, leaving the hook reporting nobody signed on.
 *
 * Refactoring Rationale: the signal was previously raised into an empty registry. The failure that
 * produced was a split session rather than an error — the interceptor stopped sending a credential
 * while the hook still held a signed claim, a refresh token and `signedOn`, so the route guards kept
 * rendering protected screens whose every request was refused, and the scheduled refresh re-presented
 * a credential belonging to a session already rejected. A test that only inspected the two modules
 * separately would have passed throughout, which is why this one spans them.
 *
 * Assumptions: the failure is raised by a stub adapter rather than by a live service, so the case
 * asserts this package's own wiring. Whether a service answers 401 for an expired token is that
 * service's contract and is asserted on its own side.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts records `globals` as a per-project contract, and admitting them here would make
// `expect` and `vi` visible to production screens as well, where a stray call would compile.
import { act, renderHook } from '@testing-library/react';
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiClient, resetApiClient, setAccessToken } from '../api/client';
import { useAuth } from '../hooks/useAuth';

/** Base URL the client is configured with for this file; no request leaves the process. */
const API_BASE_URL = 'https://api.carddemo.example/api/v1';

/** The session-storage key the shared client owns the access token under. */
const ACCESS_TOKEN_KEY = 'carddemo.access-token';

/** The session-storage key the auth hook owns the identity token under. */
const ID_TOKEN_KEY = 'carddemo.id-token';

/** The session-storage key the auth hook retains the signed-on identifier under. */
const USER_ID_KEY = 'carddemo.user-id';

/** The session-storage key the auth hook owns the refresh token under. */
const REFRESH_TOKEN_KEY = 'carddemo.refresh-token';

/** Status a service answers when the token a request carried is no longer accepted. */
const UNAUTHORIZED = 401;

/**
 * An identity token whose claim segment decodes to one group.
 *
 * Assumptions: it is not signed, and does not need to be. The hook decodes the claim to decide which
 * routes to OFFER and every service revalidates the token itself, so a case about session teardown is
 * unaffected by the signature it does not check.
 */
const ID_TOKEN = `header.${btoa(JSON.stringify({ 'cognito:groups': ['carddemo-user'] }))}.signature`;

/**
 * Answers every dispatched request with a 401 carrying a complete problem document.
 * @param {AxiosRequestConfig} config - Request configuration after the client's interceptors ran, so
 *   the `Authorization` header the interceptor attached is visible to the classifier.
 * @returns {Promise<AxiosResponse>} Never resolves; rejects as a real transport does for a refusal.
 */
async function unauthorizedAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.reject(
    new AxiosError(
      'Request failed with status code 401',
      AxiosError.ERR_BAD_RESPONSE,
      config as InternalAxiosRequestConfig,
      undefined,
      {
        data: {
          code: 'CARDDEMO-0401',
          secondaryCode: '',
          message: null,
          severity: 'CRITICAL',
          subsystem: 'APPLICATION',
          status: UNAUTHORIZED,
          correlationId: 'CD0123456789ABCDEF012345',
          path: '/api/v1/cards',
          timestamp: '2025-07-15 14:23:45.123456',
          fieldErrors: [],
          abend: null,
        },
        status: UNAUTHORIZED,
        statusText: 'Unauthorized',
        headers: {},
        config,
      } as AxiosResponse,
    ),
  );
}

/** Installs the client configuration and a complete held session for one case. */
function stubSignedOnSession(): void {
  vi.stubEnv('VITE_API_BASE_URL', API_BASE_URL);
  resetApiClient();
  sessionStorage.clear();
  setAccessToken('a-held-access-token');
  sessionStorage.setItem(ID_TOKEN_KEY, ID_TOKEN);
  sessionStorage.setItem(USER_ID_KEY, 'ADMIN001');
  sessionStorage.setItem(REFRESH_TOKEN_KEY, 'a-held-refresh-token');
}

/** Discards the configuration, the client and the session so no later file inherits any of them. */
function restoreSignedOnSession(): void {
  vi.unstubAllEnvs();
  resetApiClient();
  sessionStorage.clear();
}

/**
 * Dispatches one refused request and lets the deferred signal reach the hook.
 *
 * Assumptions: the dispatch and the microtask turn are both inside one `act`, because the listener
 * updates React state from a microtask — outside `act` the assertion would read the state as it stood
 * before the update and React would additionally report an unacted update.
 *
 * Assumptions: the rejection is swallowed. The case is about what the refusal did to the SESSION, and
 * a rejected request is the very condition it is exercising.
 * @returns {Promise<void>} Resolves once the refusal has settled and the signal has been delivered.
 */
async function dispatchRefusedRequest(): Promise<void> {
  await act(refuseOneRequest);
}

/**
 * Issues one request that the stub adapter refuses, then yields to the microtask queue.
 *
 * Assumptions: declared as a named function rather than written inline inside `act`, because
 * `jsdoc/require-jsdoc` is configured with `publicOnly: false` and selects a function expression in
 * every position — and a block comment attached to an inline argument is moved by Prettier onto the
 * preceding expression, which detaches it from what it documents.
 *
 * Assumptions: the rejection is swallowed. The case is about what the refusal did to the SESSION, and
 * a rejected request is the very condition it is exercising.
 * @returns {Promise<void>} Resolves once the refusal has settled and the queued signal has run.
 */
async function refuseOneRequest(): Promise<void> {
  const client = getApiClient();
  client.defaults.adapter = unauthorizedAdapter;
  try {
    await client.get('/cards');
  } catch {
    // Assumptions: deliberately empty, and empty for a stated reason rather than by omission — the
    //   refusal is the stimulus, not an unexpected outcome, and the client's own tests assert the
    //   shape it rejects with.
  }
  await Promise.resolve();
}

/** Asserts a refused session leaves no credential and no signed-on state behind. */
async function discardsEverySessionValueOnRefusal(): Promise<void> {
  const { result } = renderHook(useAuth);
  expect(result.current.signedOn).toBe(true);

  await dispatchRefusedRequest();

  expect(sessionStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
  expect(sessionStorage.getItem(ID_TOKEN_KEY)).toBeNull();
  expect(sessionStorage.getItem(USER_ID_KEY)).toBeNull();
  expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  expect(result.current.signedOn).toBe(false);
  expect(result.current.userId).toBeNull();
  expect(result.current.groups).toStrictEqual([]);
  expect(result.current.isAdmin).toBe(false);
}

/** Asserts an unmounted hook is no longer told, so nothing updates state after teardown. */
async function stopsListeningOnceUnmounted(): Promise<void> {
  const { unmount } = renderHook(useAuth);
  unmount();

  await dispatchRefusedRequest();

  // WHY : the access token is discarded by the transport itself, which is unaffected by unmounting, so
  //       the observable consequence of the subscription having been removed is that the keys the HOOK
  //       owns are left as they were. Asserting one of those rather than the token is what makes this
  //       case about the teardown instead of about the interceptor.
  expect(sessionStorage.getItem(ID_TOKEN_KEY)).toBe(ID_TOKEN);
}

/** Groups the assertions that fix what a refused session does to held state. */
function sessionSignalCases(): void {
  beforeEach(stubSignedOnSession);
  afterEach(restoreSignedOnSession);
  it(
    'discards every session value when the services refuse the session',
    discardsEverySessionValueOnRefusal,
  );
  it('stops listening once the hook is unmounted', stopsListeningOnceUnmounted);
}

describe('re-authentication signal', sessionSignalCases);
