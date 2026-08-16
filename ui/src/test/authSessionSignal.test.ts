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
// ui/tsconfig.json keeps `types` EMPTY, and DECLARING them here would make `expect` and `vi`
// visible to production screens as well, where a stray call would compile. (ui/vitest.config.ts
// sets `globals: true`; an injected global is not a declared one, so the import still carries the
// compiler's side of this.)
import { act, renderHook } from '@testing-library/react';
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { getApiClient } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { SESSION_USER_ID, endAnySession, establishSession } from '../test/sessionHarness';

/** Status a service answers when the token a request carried is no longer accepted. */
const UNAUTHORIZED = 401;

/** Status the probe request that observes the attached bearer is answered with. */
const OK = 200;

/** The single group the operator whose session each case establishes belongs to. */
const OPERATOR_GROUP = 'carddemo-user';

/*
 * WHY : ⚠️ Refactoring Rationale: this file used to name four session-storage keys and read each of them
 *       directly. They no longer exist -- a review found every credential this application held in
 *       script-readable Web Storage, and the session moved into memory as one frozen record behind an
 *       installer that validates the whole token set. The four values are now observed where they became
 *       observable: three of them through the hook's published reading, and the bearer through the header
 *       the next request carries. Reading a key was never the property under assertion; what the file
 *       exists to prove is that a listener EXISTS in production and that it empties the session.
 */

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

/**
 * Installs the shared request harness, which supplies the client's configuration and answers the
 * exchange each case's arrangement performs.
 *
 * Refactoring Rationale: ⚠️ this replaces a hand-rolled stub of one build-time variable plus a direct
 * seeding of the session. The harness already owns the configuration and the local answering, and a
 * session can now be arranged only by exchanging for one — so a second definition of the client's
 * configuration in this file would be a second definition that could fall behind.
 * @returns {void} Nothing; no session is held and the harness is installed.
 */
function installTheHarness(): void {
  endAnySession();
  installApiHarness();
}

/**
 * Removes the harness and discards any session, so no later file inherits either.
 * @returns {void} Nothing; the harness is removed and no session is held.
 */
function removeTheHarness(): void {
  removeApiHarness();
  endAnySession();
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

/**
 * Asserts a refused session leaves no credential and no signed-on state behind.
 *
 * Assumptions: all four values the session consisted of are covered, three from the hook's reading and
 * the bearer from the header the next request carries. The enumeration is the point rather than a
 * flourish: the defect this file exists for was a SPLIT session, so an assertion covering one value
 * would have passed against it.
 * @returns {Promise<void>} Resolves once every observation holds.
 */
async function discardsEverySessionValueOnRefusal(): Promise<void> {
  const { result } = await establishSession({ groups: [OPERATOR_GROUP] });
  expect(result.current.signedOn).toBe(true);

  await dispatchRefusedRequest();

  expect(result.current.signedOn).toBe(false);
  expect(result.current.userId).toBeNull();
  expect(result.current.groups).toStrictEqual([]);
  expect(result.current.isAdmin).toBe(false);
  expect(await bearerOnTheNextRequest(), 'no bearer may survive the refusal').toBeUndefined();
}

/**
 * Dispatches one request and reports whatever bearer the client's interceptor attached to it.
 *
 * Assumptions: the bearer is observed through a REQUEST rather than read from a variable, because it is
 * deliberately unreachable from outside `ui/src/api/client.ts` — and what a request carries is the
 * property that matters in any case, since that is what a service sees.
 * @returns {Promise<string | undefined>} The `Authorization` header value, or `undefined` when the
 *   request carried none.
 */
async function bearerOnTheNextRequest(): Promise<string | undefined> {
  attachedBearer = undefined;
  getApiClient().defaults.adapter = bearerRecordingAdapter;
  await getApiClient().get('/cards');
  return attachedBearer;
}

/** The `Authorization` header the last probe request carried, or `undefined` when it carried none. */
let attachedBearer: string | undefined;

/**
 * Answers one probe request successfully, recording whatever bearer the interceptor attached.
 *
 * Assumptions: a module-level function writing a module-level variable rather than a closure, because
 * `ui/eslint.config.js` selects a function in every position for `jsdoc/require-jsdoc` and a documented
 * declaration is the shape this tree uses for it.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success.
 */
async function bearerRecordingAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const headers: unknown = config.headers;
  if (typeof headers === 'object' && headers !== null) {
    for (const [name, value] of Object.entries(headers as Record<string, unknown>)) {
      if (name.toLowerCase() === 'authorization' && typeof value === 'string') {
        attachedBearer = value;
      }
    }
  }
  return Promise.resolve({
    data: {},
    status: OK,
    statusText: '',
    headers: {},
    config,
  } as AxiosResponse);
}

/**
 * Asserts an unmounted hook is no longer told, so nothing changes the session after teardown.
 *
 * Assumptions: the arrangement's OWN probe is unmounted too, and that is what makes the case
 * meaningful. The subscription to the signal is shared and released with the LAST listener, so a case
 * that left any listener mounted would be asserting nothing about teardown.
 *
 * Refactoring Rationale: ⚠️ the surviving value is observed by mounting the hook AGAIN, where this case
 * used to read a storage key. The session is module state and outlives every component, so a fresh
 * mount reports whatever is still held — which is precisely the state a real remount would find.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function stopsListeningOnceUnmounted(): Promise<void> {
  const established = await establishSession({ groups: [OPERATOR_GROUP] });
  established.unmount();

  await dispatchRefusedRequest();

  // WHY : the access token is discarded by the transport itself, which is unaffected by unmounting, so
  //       the observable consequence of the subscription having been removed is that the half the HOOK
  //       owns is left as it was. Asserting that rather than the bearer is what makes this case about
  //       the teardown instead of about the interceptor.
  const { result } = renderHook(useAuth);
  expect(result.current.signedOn, 'the identity half must survive an unsubscribed refusal').toBe(
    true,
  );
  expect(result.current.userId).toBe(SESSION_USER_ID);
  expect(result.current.groups).toStrictEqual([OPERATOR_GROUP]);
}

/** Groups the assertions that fix what a refused session does to held state. */
function sessionSignalCases(): void {
  beforeEach(installTheHarness);
  afterEach(removeTheHarness);
  it(
    'discards every session value when the services refuse the session',
    discardsEverySessionValueOnRefusal,
  );
  it('stops listening once the hook is unmounted', stopsListeningOnceUnmounted);
}

describe('re-authentication signal', sessionSignalCases);
