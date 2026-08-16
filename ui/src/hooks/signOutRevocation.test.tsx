/**
 * @file Integration test for the sign-out path: what `ui/src/hooks/useAuth.ts` does locally and what it
 * asks the identity provider to do.
 *
 * Purpose
 * -------
 * Assert that signing out revokes the held refresh token at the provider AND empties this tab's session
 * whether that revocation succeeds, fails or is not possible at all.
 *
 * Refactoring Rationale: sign-out discarded this tab's own copy of the session and asked the identity
 * provider for nothing. The access token it discarded lives one hour, but the refresh token it discarded lives thirty
 * days -- `refresh_token_validity_days` defaults to 30 in `infra/modules/cognito/variables.tf` -- so a
 * copy of that token taken from the session store, a synchronised browser profile or a shared workstation
 * went on minting access tokens for a month after the operator believed the session was over, and no
 * action available anywhere in this application would stop it. Nothing failed, because every local
 * assertion the suite made about sign-out was satisfied by the local clear alone.
 *
 * Assumptions: this is deliberately an INTEGRATION case spanning the hook, the client and the transport,
 * rather than a unit case with a stubbed client. The gap was not inside any of them -- the client function
 * did not exist and the hook did not call one -- and the property under assertion is a REQUEST reaching
 * the network, which only a case that crosses all three can observe.
 *
 * Assumptions: the network is answered locally by the shared harness installed on the real client, so the
 * revocation travels through the module's real interceptors. That is what makes the third case meaningful:
 * a rejection produced by the harness is normalised by the same failure interceptor a real fault would
 * pass through.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuth } from './useAuth';
import { getApiClient } from '../api/client';
import {
  HTTP_NO_CONTENT,
  answerWith,
  dispatchedRequests,
  installApiHarness,
  removeApiHarness,
} from '../test/apiHarness';
import { endAnySession, establishSession } from '../test/sessionHarness';

/** The status the provider answers when it could not be reached, which must not undo the local clear. */
const HTTP_INTERNAL_SERVER_ERROR = 500;

/** The refresh token the established session holds and the revocation must carry. */
const HELD_REFRESH_TOKEN = 'held-refresh-token';

/**
 * Establishes a session holding the named renewal token, through a real sign-on exchange.
 *
 * Refactoring Rationale: ⚠️ this used to WRITE four session-storage keys directly, which was quicker
 * and was also asserting against a state no exchange could produce. The session is now held in memory
 * behind one installer that validates the whole token set, so the only way to arrange one is to perform
 * an exchange — and driving the real path means this file's own arrangement proves a session can be
 * established that way, rather than assuming it.
 *
 * Assumptions: the sign-on's own request is dropped from the recording by the shared fixture, so the
 * request counts below still measure only what the sign-out dispatched.
 * @param {string | null} refreshToken - The renewal token the session is to hold, or `null` for a
 *   session the pool issued none for.
 * @returns {Promise<() => void>} The probe's teardown, which the caller need not run: the session is
 *   module state and survives it.
 */
async function establishASessionHolding(refreshToken: string | null): Promise<() => void> {
  const established = await establishSession({ refreshToken });
  return established.unmount;
}

/**
 * Answers one request by REJECTING with a 500, as a provider that could not be reached would.
 *
 * Assumptions: this replaces the shared harness's adapter for the one case that needs a failure, because
 * that adapter RESOLVES whatever status it is given. Axios applies `validateStatus` inside its own
 * built-in adapters and passes a custom adapter's resolution straight through, so a queued 500 arrives at
 * the caller as a success and never enters the failure interceptor -- which is the opposite of what the
 * refused case measures.
 *
 * Assumptions: the rejection carries the real `config`, because the client's failure interceptor reads the
 * request off it to decide whether the refusal invalidates the session. Rejecting with a bare `Error`
 * would exercise a different branch of the normaliser than a transport failure takes.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A promise that always rejects with the constructed transport failure.
 */
async function refusingAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  const response = {
    data: { message: 'Unable to verify the User ...' },
    status: HTTP_INTERNAL_SERVER_ERROR,
    statusText: 'Internal Server Error',
    headers: {},
    config,
  } as AxiosResponse;
  return Promise.reject(
    new AxiosError(
      `Request failed with status code ${String(HTTP_INTERNAL_SERVER_ERROR)}`,
      AxiosError.ERR_BAD_RESPONSE,
      config as never,
      null,
      response,
    ),
  );
}

/**
 * A probe that reports the session state and offers the sign-out action.
 *
 * Assumptions: the component renders `signedOn` as text rather than the test reading module state,
 * because the property that matters to a screen is what the hook PUBLISHES after the clear -- a store
 * emptied without listeners being notified would leave every mounted screen rendering a live session.
 * @returns {ReactElement} The probe.
 */
function SessionProbe(): ReactElement {
  const { signedOn, userId, groups, signOut } = useAuth();
  return (
    <div>
      <span data-testid="signed-on">{signedOn ? 'signed-on' : 'anonymous'}</span>
      <span data-testid="user-id">{userId ?? 'none'}</span>
      <span data-testid="groups">{groups.length === 0 ? 'none' : groups.join(',')}</span>
      <button type="button" onClick={signOut}>
        Sign off
      </button>
    </div>
  );
}

/**
 * Waits for the probe to publish the anonymous reading.
 * @returns {void} Nothing; throws until the hook reports no session.
 */
function theReadingHasGoneAnonymous(): void {
  expect(screen.getByTestId('signed-on').textContent).toBe('anonymous');
}

/**
 * Waits for exactly one request to have been dispatched.
 * @returns {void} Nothing; throws until the revocation has reached the transport.
 */
function exactlyOneRequestHasBeenDispatched(): void {
  expect(
    dispatchedRequests(),
    'a sign-out that never dispatched, or dispatched twice, is not the operation under assertion',
  ).toHaveLength(1);
}

/**
 * Asserts every value the session consisted of is gone, from the published reading and from the transport.
 *
 * Refactoring Rationale: ⚠️ this used to read four session-storage keys and require each to be absent.
 * The session no longer occupies storage, so the totality is asserted where it is now observable: the
 * hook publishes no identifier, no group and no session, and the client attaches no bearer to the next
 * request. Both halves are checked because they live in different modules — a discard that emptied the
 * hook and left the bearer in place would leave this tab still able to act as the operator who signed
 * out, which is precisely the exposure the sign-out exists to close.
 *
 * Assumptions: the bearer is observed by DISPATCHING a request and reading the header the interceptor
 * attached, because that is the only route to it. The value is deliberately unreachable from outside
 * `ui/src/api/client.ts`, and observing the header measures the property that matters — what a request
 * carries — rather than the variable that produces it.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function expectTheSessionEnded(): Promise<void> {
  await waitFor(theReadingHasGoneAnonymous);
  expect(screen.getByTestId('user-id').textContent, 'no identifier may survive a sign-out').toBe(
    'none',
  );
  expect(screen.getByTestId('groups').textContent, 'no authority may survive a sign-out').toBe(
    'none',
  );

  getApiClient().defaults.adapter = recordingProbeAdapter;
  await getApiClient().get('/probe');
  expect(
    attachedAuthorization,
    'a request dispatched after a sign-out must carry no bearer',
  ).toBeUndefined();
}

/** The `Authorization` header the probe request carried, or `undefined` when it carried none. */
let attachedAuthorization: string | undefined;

/**
 * Answers one probe request, recording whatever `Authorization` header the interceptor attached.
 *
 * Assumptions: a dedicated adapter is installed rather than the shared harness's recording being read,
 * because the refused case replaces that adapter with one that rejects — a probe dispatched into it
 * would fail rather than report a header.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success.
 */
async function recordingProbeAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  attachedAuthorization = undefined;
  const headers: unknown = config.headers;
  if (typeof headers === 'object' && headers !== null) {
    for (const [name, value] of Object.entries(headers as Record<string, unknown>)) {
      if (name.toLowerCase() === 'authorization' && typeof value === 'string') {
        attachedAuthorization = value;
      }
    }
  }
  return Promise.resolve({
    data: {},
    status: 200,
    statusText: '',
    headers: {},
    config,
  } as AxiosResponse);
}

/**
 * Asserts a sign-out posts the held refresh token to the revocation target and empties the tab.
 *
 * Assumptions: BOTH halves are asserted in one case, because either alone passes against a defect. A
 * local-only assertion is what the suite had before this file existed and it passed against a sign-out
 * that revoked nothing; a request-only assertion would pass against one that revoked the token and left
 * the tab signed on.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function revokesTheHeldTokenAndEmptiesTheTab(): Promise<void> {
  await establishASessionHolding(HELD_REFRESH_TOKEN);
  answerWith(undefined, HTTP_NO_CONTENT);
  render(<SessionProbe />);

  await userEvent.click(screen.getByRole('button', { name: 'Sign off' }));

  await waitFor(exactlyOneRequestHasBeenDispatched);
  const request = dispatchedRequests()[0];
  expect(request?.method).toBe('post');
  expect(request?.url).toBe('/auth/signout');
  expect(request?.body).toEqual({ refreshToken: HELD_REFRESH_TOKEN });
  await expectTheSessionEnded();
}

/**
 * Asserts the token is read BEFORE the local clear, which is the ordering the request depends on.
 *
 * Assumptions: this is a distinct case from the one above even though both inspect the request body,
 * because the failure it catches is an ORDERING mistake rather than an absent call. Clearing first and
 * reading afterwards compiles, dispatches nothing, and reports no error -- so it would pass a case that
 * only asserted the tab was emptied, and it would leave the token live exactly as the original defect did.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function readsTheTokenBeforeClearingIt(): Promise<void> {
  await establishASessionHolding(HELD_REFRESH_TOKEN);
  answerWith(undefined, HTTP_NO_CONTENT);
  render(<SessionProbe />);

  await userEvent.click(screen.getByRole('button', { name: 'Sign off' }));

  await waitFor(exactlyOneRequestHasBeenDispatched);
  expect(
    dispatchedRequests()[0]?.body,
    'a sign-out that cleared the store before reading it would carry no token',
  ).toEqual({ refreshToken: HELD_REFRESH_TOKEN });
}

/**
 * Asserts a refused revocation still ends the session in this tab.
 *
 * Assumptions: this is the case that decides the design. Awaiting the revocation and clearing only on
 * success is the obvious implementation and it fails here: it would leave a signed-out operator signed on
 * whenever the provider was unreachable, which is worse than the exposure it was meant to close. The
 * diagnostic the hook writes for this condition is silenced rather than asserted, because the channel is
 * a console entry and its wording is not an interface -- what matters is that the sign-out completed.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function endsTheSessionEvenWhenTheRevocationIsRefused(): Promise<void> {
  const silenced = vi.spyOn(console, 'warn').mockImplementation(swallowTheDiagnostic);
  try {
    await establishASessionHolding(HELD_REFRESH_TOKEN);
    getApiClient().defaults.adapter = refusingAdapter;
    render(<SessionProbe />);

    await userEvent.click(screen.getByRole('button', { name: 'Sign off' }));

    await expectTheSessionEnded();
    await waitFor(theUnrevokedTokenHasBeenRecorded(silenced));
  } finally {
    silenced.mockRestore();
  }
}

/**
 * Stands in for the console writer, so the diagnostic is observed without reaching the run's output.
 * @returns {undefined} Nothing; the call is recorded by the spy and discarded.
 */
function swallowTheDiagnostic(): undefined {
  return undefined;
}

/**
 * Builds the predicate that waits for the hook's unrevoked-token diagnostic.
 *
 * Assumptions: only that the diagnostic was WRITTEN is asserted, never its wording. The channel is a
 * console entry rather than an interface, so pinning the sentence would make a diagnostic message a
 * contract; what the case needs is that the condition is recorded rather than swallowed silently.
 * @param {ReturnType<typeof vi.spyOn>} silenced - The spy standing in for the console writer.
 * @returns {() => void} A predicate that throws until the diagnostic has been written.
 */
function theUnrevokedTokenHasBeenRecorded(silenced: ReturnType<typeof vi.spyOn>): () => void {
  /**
   * Waits for the diagnostic to have been written.
   * @returns {void} Nothing; throws until the spy has been called.
   */
  return function theDiagnosticWasWritten(): void {
    expect(
      silenced,
      'an unrevoked token must be recorded rather than swallowed',
    ).toHaveBeenCalled();
  };
}

/**
 * Asserts a sign-out with no held refresh token dispatches nothing and still ends the session.
 *
 * Assumptions: this state is reachable rather than hypothetical. `SignOnResponse` declares the renewal
 * token nullable and the pool omits it for a client provisioned without renewal, so a session can be
 * established holding none — which is exactly what the arrangement below does. A client that posted a
 * null or an empty token would spend a round trip to be told 400 by the service's own presence guard.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function dispatchesNothingWhenNoTokenIsHeld(): Promise<void> {
  await establishASessionHolding(null);
  render(<SessionProbe />);

  await userEvent.click(screen.getByRole('button', { name: 'Sign off' }));

  await expectTheSessionEnded();
  expect(
    dispatchedRequests(),
    'there is nothing to revoke, so nothing may be dispatched',
  ).toHaveLength(0);
}

/**
 * Empties this tab's store and installs the shared harness on the real client.
 *
 * Assumptions: the store is cleared BEFORE each case rather than only after, because a file that ran
 * earlier in the same worker may have left keys behind and a seeded session must be the only session.
 * @returns {void} Nothing; the store is empty and the harness is installed.
 */
function clearTheTabAndInstallTheHarness(): void {
  attachedAuthorization = undefined;
  endAnySession();
  installApiHarness();
}

/**
 * Removes the harness and empties the store, so no later file inherits either.
 *
 * Assumptions: the harness removal also resets the client, which is what discards the refusing adapter one
 * case installs -- without it every later file in this worker would dispatch into that adapter.
 * @returns {void} Nothing; the harness is removed and the store is empty.
 */
function removeTheHarnessAndClearTheTab(): void {
  removeApiHarness();
  endAnySession();
}

/**
 * Registers every sign-out case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function signOutRevocation(): void {
  beforeEach(clearTheTabAndInstallTheHarness);
  afterEach(removeTheHarnessAndClearTheTab);
  it('revokes the held token and empties the tab', revokesTheHeldTokenAndEmptiesTheTab);
  it('reads the token before clearing it', readsTheTokenBeforeClearingIt);
  it(
    'ends the session even when the revocation is refused',
    endsTheSessionEvenWhenTheRevocationIsRefused,
  );
  it('dispatches nothing when no token is held', dispatchesNothingWhenNoTokenIsHeld);
}

describe('sign-out revocation', signOutRevocation);
