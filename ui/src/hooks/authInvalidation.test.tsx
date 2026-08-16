/**
 * @file Integration test for the re-authentication path: what a bearer-authenticated HTTP 401 does to
 * the session `ui/src/hooks/useAuth.ts` holds and to the screen `ui/src/routes/guards.tsx` renders.
 *
 * Purpose
 * -------
 * Assert that a 401 answered to a request which CARRIED a bearer token discards the whole session and
 * returns the operator to sign-on. `ui/src/api/client.ts` signals re-authentication rather than
 * performing it, and the signal is only worth anything if something subscribes: before the
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
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useAuth } from './useAuth';
import { WITHOUT_STORED_SESSION, getApiClient } from '../api/client';
import { RequireSignOn } from '../routes/guards';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { endAnySession, establishSession } from '../test/sessionHarness';

const UNAUTHORIZED_STATUS = 401;

const OK_STATUS = 200;

/*
 * WHY : ⚠️ Refactoring Rationale: this file used to enumerate FOUR session-storage keys and require
 *       each of them to be absent, because the defect it was written against was a PARTIAL clear and an
 *       assertion covering only the key already being cleared would have passed against it. Those keys
 *       are gone: a review found every credential sitting in script-readable Web Storage, and the four
 *       independent values were replaced by ONE frozen in-memory record. A partial clear is therefore no
 *       longer expressible -- the record is either held or it is `null` -- so the totality is asserted
 *       where it is now observable instead: the hook publishes no identifier and no authority, the guard
 *       renders sign-on, the transport attaches no bearer, and a sign-out finds no renewal token to
 *       revoke. Those four observations cover the same four values the keys did, one each.
 */

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
 * Publishes the hook's reading and offers the sign-out action, alongside the guarded route.
 *
 * Assumptions: the identifier and the authority are rendered rather than read from the module, because
 * they are what a screen sees. A record cleared without listeners being notified would leave every
 * mounted screen still describing a session no service accepts, which is the same class of defect this
 * file was written for.
 * @returns {ReactElement} The probe.
 */
function SessionProbe(): ReactElement {
  const { userId, groups, signOut } = useAuth();
  return (
    <div>
      <span data-testid="user-id">{userId ?? 'none'}</span>
      <span data-testid="groups">{groups.length === 0 ? 'none' : groups.join(',')}</span>
      <button type="button" onClick={signOut}>
        Sign off
      </button>
    </div>
  );
}

/**
 * Builds the thunk `act` runs to await a dispatch that must be refused.
 *
 * Assumptions: a factory returning a NAMED function expression rather than an inline arrow, because
 * `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with `publicOnly: false` and selects a function
 * in every position — and a block comment attached to an inline argument is moved by Prettier onto the
 * preceding expression, which detaches it from what it documents.
 *
 * Assumptions: the request is DISPATCHED by the caller and its promise handed over, because a rejection
 * left unattached for even one turn is an unhandled rejection. Passing the promise means the attachment
 * happens at the call site, in the same expression that created it.
 * @param {Promise<unknown>} dispatched - The in-flight request, which must reject.
 * @returns {() => Promise<void>} A thunk that resolves once the refusal has settled and the resulting
 *   renders have been flushed.
 */
function refusalThunk(dispatched: Promise<unknown>): () => Promise<void> {
  /**
   * Awaits the refusal inside the act scope.
   * @returns {Promise<void>} Resolves once the request has been rejected.
   */
  return async function awaitTheRefusal(): Promise<void> {
    await expect(dispatched).rejects.toBeDefined();
  };
}

/**
 * Renders a guarded screen at `/protected`, with sign-on reachable at its own route.
 * @returns {ReactElement} The composed tree under test.
 */
function renderGuardedScreen(): ReactElement {
  return (
    <MemoryRouter initialEntries={['/protected']}>
      <SessionProbe />
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
 * Establishes an ordinary operator's session by performing a real sign-on exchange.
 *
 * Refactoring Rationale: ⚠️ this used to write four session-storage keys. Arranging a session now
 * requires an exchange, because the one installer that can hold one validates the whole token set — so
 * the caller each case refuses is a caller the application could actually produce, rather than a token
 * set assembled by the test and never checked.
 * @returns {Promise<void>} Resolves once the session is held.
 */
async function establishAnOrdinarySession(): Promise<void> {
  await establishSession({ groups: ['carddemo-user'] });
}

/**
 * Installs the shared request harness, which supplies the client's configuration and answers the
 * arrangement's own exchange.
 *
 * Refactoring Rationale: ⚠️ this file used to stub the two build-time variables itself and reset the
 * client by hand. The shared harness does exactly that and additionally answers a request without a
 * network, which the arrangement above now needs — two mechanisms doing the same job would be two
 * definitions of the client's configuration in one file.
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
 * Dispatches one authenticated request that is refused, and waits for the refusal to be delivered.
 *
 * Assumptions: the dispatch is wrapped in `act`, because the refusal reaches the hook through a
 * microtask-delivered signal and the discard that follows re-renders every mounted subscriber. Awaiting
 * the rejection alone leaves those renders outside any act scope, which React reports on every case in
 * this file — noise that would bury a real warning rather than telling anyone anything.
 * @returns {Promise<void>} Resolves once the request has been rejected and the resulting renders have
 *   been flushed.
 */
async function dispatchARefusedAuthenticatedRequest(): Promise<void> {
  const client = getApiClient();
  client.defaults.adapter = unauthorizedAdapter;
  await act(refusalThunk(client.get('/api/v1/cards')));
}

/**
 * Asserts the hook has stopped publishing an identifier, which is the observable the clear produces.
 *
 * Assumptions: a HOISTED named function rather than an inline arrow inside `waitFor`, because
 * ui/eslint.config.js selects `* > ArrowFunctionExpression` for `jsdoc/require-jsdoc`, and a block
 * comment on an inline argument is moved by Prettier onto the preceding expression.
 * @returns {void} Nothing; the assertion carries the outcome and `waitFor` retries it.
 */
function theIdentifierHasBeenDiscarded(): void {
  expect(screen.getByTestId('user-id').textContent).toBe('none');
}

/**
 * A bearer-authenticated 401 discards the whole session, not only the bearer.
 *
 * Assumptions: all four values the session consisted of are covered, one observation each: the
 * identifier and the authority from the hook's published reading, the bearer from the header the next
 * request carries, and the renewal token from a sign-out finding nothing to revoke. The enumeration
 * matters for the same reason the four-key list used to: the defect this case exists for was a PARTIAL
 * clear, so an assertion covering one value would pass against it.
 * @returns {Promise<void>} Resolves once every observation holds.
 */
async function clearsEveryStoredIdentityKeyOnAnAuthenticatedRefusal(): Promise<void> {
  await establishAnOrdinarySession();
  render(renderGuardedScreen());
  expect(screen.getByText('PROTECTED')).toBeInTheDocument();

  await dispatchARefusedAuthenticatedRequest();

  // Assumptions: the wait is on an observable outcome rather than on a fixed delay, because the
  //   interceptor notifies its listeners in a microtask so that a listener cannot replace the failure
  //   the caller is awaiting. Polling is what makes this case independent of that scheduling decision.
  await waitFor(theIdentifierHasBeenDiscarded);
  expect(screen.getByTestId('groups').textContent, 'no authority may survive the refusal').toBe(
    'none',
  );

  getApiClient().defaults.adapter = recordingAdapter;
  recorded.length = 0;
  await getApiClient().get('/api/v1/cards');
  expect(
    recorded[0]?.authorization,
    'a request dispatched after the refusal must carry no bearer',
  ).toBeUndefined();

  recorded.length = 0;
  await userEvent.click(screen.getByRole('button', { name: 'Sign off' }));
  expect(
    recorded,
    'the renewal token must be gone, so a sign-out has nothing to revoke and dispatches nothing',
  ).toHaveLength(0);
}

/** What each request dispatched through {@link recordingAdapter} carried. */
const recorded: { readonly url: string; readonly authorization: string | undefined }[] = [];

/**
 * Answers one request successfully, recording its target and whatever bearer the interceptor attached.
 *
 * Assumptions: this replaces the refusing adapter once a case has finished with the refusal, because the
 * observations that follow it are about what the client SENDS and a refusing adapter would reject them
 * before they could be read.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success.
 */
async function recordingAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  let authorization: string | undefined;
  const headers: unknown = config.headers;
  if (typeof headers === 'object' && headers !== null) {
    for (const [name, value] of Object.entries(headers as Record<string, unknown>)) {
      if (name.toLowerCase() === 'authorization' && typeof value === 'string') {
        authorization = value;
      }
    }
  }
  recorded.push({ url: config.url ?? '', authorization });
  return Promise.resolve({
    data: {},
    status: OK_STATUS,
    statusText: '',
    headers: {},
    config,
  } as AxiosResponse);
}

/**
 * The guard sends the operator back to sign-on once the refused session has been discarded.
 *
 * Assumptions: the rendered ROUTE is asserted as well as the reading, because discarding the session is
 * only half of what was broken. The record is module state published through `useSyncExternalStore`, so
 * a discard that did not notify would leave the guard rendering the protected screen from a reading it
 * had already taken, until something unrelated re-rendered it.
 * @returns {Promise<void>} Resolves once the redirect has been observed.
 */
async function returnsTheOperatorToSignOnAfterAnAuthenticatedRefusal(): Promise<void> {
  await establishAnOrdinarySession();
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
 * Assumptions: ⚠️ Refactoring Rationale: the no-bearer condition is produced by the request's own
 * `carddemoOmitStoredSession` flag, where it used to be produced by REMOVING the stored access token. The flag
 * is the more faithful model as well as the only one still available: it is exactly what
 * `ui/src/api/auth.ts` sets on the sign-on call, and a request that carries no bearer BECAUSE IT DECLARED
 * ITSELF UNAUTHENTICATED is the real condition — a session that merely happened to hold no bearer is a
 * different state, and one no exchange can now produce, since the installer accepts a token set only as
 * a whole.
 * @returns {Promise<void>} Resolves once the surviving keys have been asserted.
 */
async function leavesTheSessionIntactWhenTheRefusedRequestCarriedNoToken(): Promise<void> {
  await establishAnOrdinarySession();
  render(renderGuardedScreen());

  const client = getApiClient();
  client.defaults.adapter = unauthorizedAdapter;
  await act(refusalThunk(client.get('/api/v1/auth/signon', WITHOUT_STORED_SESSION)));

  // Assumptions: a settled microtask queue is awaited before asserting, so this case would observe an
  //   over-eager clear rather than racing it. Asserting immediately would pass even if the interceptor
  //   had scheduled one, because the signal is delivered in a microtask.
  await Promise.resolve();
  expect(screen.getByTestId('user-id').textContent, 'the identifier must survive').toBe('USER0001');
  expect(screen.getByTestId('groups').textContent, 'the authority must survive').toBe(
    'carddemo-user',
  );
  expect(screen.getByText('PROTECTED')).toBeInTheDocument();
}

/**
 * Registers the three re-authentication cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function reAuthenticationCases(): void {
  beforeEach(installTheHarness);
  afterEach(removeTheHarness);
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
