/**
 * @file Pins the session-ordering invariant in `ui/src/hooks/useAuth.ts`: a result that settles after
 * its session has been replaced or discarded is dropped, not applied.
 *
 * Purpose
 * -------
 * Every exchange the hook performs — sign-on, challenge answer, refresh — is asynchronous and ends by
 * writing shared module state. Two failures follow if nothing scopes that write to the session the
 * exchange was started for, and both are reachable with ordinary timing rather than a contrived race:
 *
 * - **Resurrection (CWE-613).** A scheduled refresh is in flight, the operator signs off, and the
 *   response arrives afterwards. Applied, it reinstalls a bearer, an identity token and a refresh
 *   token for a session the operator ended. Cancelling the refresh TIMER cannot prevent it: the timer
 *   had already fired, and cancelling a timer does nothing to an exchange it has already started —
 *   which is why the first case below drives the real timer rather than calling an exchange directly.
 * - **Cross-session clearing (CWE-362).** A refresh for operator A is refused after operator B has
 *   signed on. Applied, it discards B's live session and reports A's refusal against it.
 *
 * WHY these cases are written against the REAL store — Assumptions: the hook's state is module scope
 * and its only observable surface is the reading it publishes and the bearer it installs on the
 * transport, so a case asserts on those rather than on an injected double.
 *
 * WHY : ⚠️ Refactoring Rationale: every assertion here read one of four `sessionStorage` keys, and the
 * session is not in `sessionStorage`. `ui/src/hooks/useAuth.ts` holds the whole session in a
 * module-scoped variable and hands the bearer to `ui/src/api/client.ts`, which keeps it in a
 * module-scoped variable of its own and exports only a setter -- an arrangement chosen so that a
 * cross-site script cannot read a token out of a store the document exposes, and so that closing a tab
 * genuinely ends the session. Reading a store nothing writes made all four cases assert `null` against
 * `null` for the two positive halves and pass vacuously for the negative ones, so this file agreed with
 * a hook that had stopped installing sessions altogether. The assertions now read what IS observable:
 * the published reading, and the bearer as it appears on a probe request the transport records. Only `ui/src/api/auth.ts` is
 * mocked, because that is the boundary where an exchange's timing is decided: each case holds a
 * response open with a deferred promise, performs the interleaving event, and only then settles it.
 * That is what makes the ordering deterministic instead of timing-dependent.
 *
 * WHY : Assumptions: the refresh cases advance FAKE timers to fire the hook's own schedule, rather
 * than reaching for an exported exchange. The hook exposes no refresh operation — a refresh happens
 * only from the timer it arms itself — and driving that timer is also what makes these cases evidence
 * about the reachable defect rather than about an internal function.
 *
 * WHY : Assumptions: every callback is a named declaration rather than an inline arrow, because
 * `ui/eslint.config.js` requires a documentation block on a function expression in any position.
 */

import { act, render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../api/auth';
import { getApiClient } from '../api/client';
import type { SignOnTokens } from '../api/types';
import type { DispatchedRequest } from '../test/apiHarness';
import {
  answerEveryRequestWith,
  dispatchedRequests,
  forgetDispatchedRequests,
  installApiHarness,
  removeApiHarness,
} from '../test/apiHarness';

const signOnMock = vi.fn();
const refreshTokensMock = vi.fn();
const answerChallengeMock = vi.fn();

// Assumptions: the auth CLIENT is mocked and the hook is not, because the property under test belongs
// to the hook. Stubbing the three requests lets a case decide exactly when each one settles, which is
// the only lever that makes an ordering assertion deterministic.
vi.mock(
  '../api/auth',
  /**
   * Replaces the three exchange operations while leaving every other export intact.
   * @returns {Promise<typeof AuthModule>} The real module with the three operations stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof AuthModule>('../api/auth');
    return {
      ...actual,
      /**
       * Stands in for the sign-on operation.
       * @returns {Promise<unknown>} Whatever the case configured.
       */
      signOn: signOnMock,
      /**
       * Stands in for the refresh operation.
       * @returns {Promise<unknown>} Whatever the case configured.
       */
      refreshTokens: refreshTokensMock,
      /**
       * Stands in for the challenge-answer operation.
       * @returns {Promise<unknown>} Whatever the case configured.
       */
      answerSignOnChallenge: answerChallengeMock,
    };
  },
);

const { useAuth } = await import('./useAuth');

/*
 * WHY : Assumptions: the shared identity-token builder is imported DYNAMICALLY, beside the hook and for
 *       the same reason. `ui/src/test/sessionHarness.ts` imports the hook, so a static import of it
 *       would evaluate the hook's module -- and therefore the mock factory -- before this file's mock
 *       functions exist, which is the "cannot access before initialization" the hoist produces. Taking
 *       it after the hook keeps one definition of the claim name without reordering the mock.
 */
const { idTokenFor } = await import('../test/sessionHarness');

/*
 * WHY : ⚠️ Refactoring Rationale: four `sessionStorage` key names stood here -- the identity token, the
 *       identifier, the renewal token and the bearer -- and no code writes any of them. They are
 *       withdrawn rather than corrected, because there is no key to correct them to: the session is
 *       module state and the bearer is module state behind a setter. What replaced them is
 *       {@link bearerOnTheWire}, which observes the bearer where it is actually observable.
 */

/**
 * Bearer lifetime every issued set in this file reports, in seconds.
 *
 * Assumptions: chosen so the hook arms its refresh one second from now — its own margin is sixty
 * seconds and it floors the delay at one — which keeps {@link fireScheduledRefresh} advancing a small,
 * stated amount rather than an opaque one.
 */
const EXPIRES_IN_SECONDS = 61;

/** Milliseconds to advance so the armed refresh fires. */
const REFRESH_DELAY_MS = 1000;

/*
 * WHY : ⚠️ Refactoring Rationale: a local identity-token builder stood here and named the operator in a
 *       `sub` claim. The hook reads `cognito:username`, so every token this file issued described no
 *       operator -- and since a review added a check that an issued set's token names the identifier it
 *       was issued for, `signIn` began refusing all four cases with a `RangeError` rather than
 *       establishing anything. The builder in `ui/src/test/sessionHarness.ts` is used instead of a
 *       corrected copy, so the claim name lives in ONE place and a second suite cannot drift from it.
 */

/** Groups every operator in this file signs on with; the cases are about ordering, not authority. */
const ORDINARY_GROUPS: readonly string[] = ['carddemo-user'];

/**
 * Builds one issued token set for an operator.
 * @param {string} userId - Identifier the set is issued for.
 * @returns {SignOnTokens} A complete authenticated result.
 */
function tokensFor(userId: string): SignOnTokens {
  return {
    outcome: 'AUTHENTICATED',
    userId,
    accessToken: `access-${userId}`,
    idToken: idTokenFor(ORDINARY_GROUPS, userId),
    refreshToken: `refresh-${userId}`,
    tokenType: 'Bearer',
    expiresIn: EXPIRES_IN_SECONDS,
  };
}

/** The live hook result, republished by {@link Probe} on every reading. */
let live: ReturnType<typeof useAuth> | null = null;

/**
 * Mounts the hook so a case can drive it and read what it publishes.
 * @returns {ReactElement} An empty element; the reading is captured in {@link live}.
 */
function Probe(): ReactElement {
  live = useAuth();
  return <div />;
}

/**
 * Returns the mounted reading, failing loudly rather than returning null.
 * @returns {ReturnType<typeof useAuth>} The current reading.
 * @throws {Error} When {@link Probe} has not been mounted, so a case that forgot to render fails
 *   naming the omission instead of dereferencing null several lines later.
 */
function reading(): ReturnType<typeof useAuth> {
  if (live === null) {
    throw new Error('the probe is not mounted');
  }
  return live;
}

/**
 * A promise a case settles by hand, so an exchange can be held open across another event.
 * @template T - Value the promise resolves to.
 */
interface Deferred<T> {
  /** The promise handed to the code under test. */
  readonly promise: Promise<T>;
  /** Resolves it with the supplied value. */
  readonly resolve: (value: T) => void;
  /** Rejects it with the supplied cause. */
  readonly reject: (cause: unknown) => void;
}

/**
 * Creates a promise whose settlement a case controls.
 *
 * Assumptions: this is what replaces waiting. The interleaving under test is "response arrives after
 * sign-out", which is a statement about ORDER and not about elapsed time, so expressing it as an
 * explicitly-settled promise removes real waiting from the case entirely.
 * @template T - Value the promise resolves to.
 * @returns {Deferred<T>} The promise together with its two settlement functions.
 */
function defer<T>(): Deferred<T> {
  let resolveFn: (value: T) => void = doNothing;
  let rejectFn: (cause: unknown) => void = doNothing;
  const promise = new Promise<T>(
    /**
     * Captures both settlement functions for the case to call later.
     * @param {(value: T) => void} resolve - Resolver for the promise.
     * @param {(cause: unknown) => void} reject - Rejecter for the promise.
     * @returns {void} Nothing; both are stored.
     */
    (resolve: (value: T) => void, reject: (cause: unknown) => void): void => {
      resolveFn = resolve;
      rejectFn = reject;
    },
  );
  return { promise, resolve: resolveFn, reject: rejectFn };
}

/**
 * Placeholder settlement function, replaced synchronously by the promise executor.
 * @returns {void} Nothing.
 */
function doNothing(): void {
  return undefined;
}

/**
 * Lets every already-resolved continuation run.
 *
 * Assumptions: microtask turns rather than a timer, because fake timers are installed and because the
 * chain being drained is `await` continuations inside the hook, which are microtasks. Three turns
 * covers the deepest chain here: settle, the exchange's own continuation, and its `finally`.
 * @returns {Promise<void>} Resolves once the queue has been drained.
 */
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

/**
 * Establishes a signed-on session for one operator through the real sign-on path.
 * @param {string} userId - Operator to sign on.
 * @returns {Promise<void>} Resolves once the tokens have been installed.
 */
async function signOnAs(userId: string): Promise<void> {
  signOnMock.mockResolvedValueOnce(tokensFor(userId));
  await act(
    /**
     * Runs the sign-on exchange inside React's commit boundary.
     * @returns {Promise<void>} Resolves once the exchange has settled.
     */
    async (): Promise<void> => {
      await reading().signIn(userId, 'PASS0001');
    },
  );
}

/**
 * Fires the refresh the hook armed when it adopted the current token set.
 * @returns {Promise<void>} Resolves once the exchange has been started, not settled.
 */
async function fireScheduledRefresh(): Promise<void> {
  await act(
    /**
     * Advances past the armed delay so the hook's own timer runs.
     * @returns {Promise<void>} Resolves once the exchange has been started.
     */
    async (): Promise<void> => {
      vi.advanceTimersByTime(REFRESH_DELAY_MS);
      await flushMicrotasks();
    },
  );
}

/**
 * Settles a held response and lets the hook process it.
 * @param {() => void} settle - Resolves or rejects the held promise.
 * @returns {Promise<void>} Resolves once the outcome has been applied or dropped.
 */
async function settleHeldResponse(settle: () => void): Promise<void> {
  await act(
    /**
     * Settles the response inside React's commit boundary.
     * @returns {Promise<void>} Resolves once the continuations have run.
     */
    async (): Promise<void> => {
      settle();
      await flushMicrotasks();
    },
  );
}

/**
 * Signs out through the hook, leaving no session behind.
 * @returns {void} Nothing; the store is anonymous afterwards.
 */
function signOutThroughHook(): void {
  act(
    /**
     * Ends the session inside React's commit boundary.
     * @returns {void} Nothing.
     */
    (): void => {
      reading().signOut();
    },
  );
}

/** Target the bearer probe is issued against, chosen so it cannot collide with a real call. */
const PROBE_TARGET = '/probe';

/**
 * Reports whether one recorded request is the bearer probe.
 * @param {DispatchedRequest} request - One request the harness recorded.
 * @returns {boolean} `true` when the request is the probe this file issued.
 */
function isProbe(request: DispatchedRequest): boolean {
  return request.url === PROBE_TARGET;
}

/**
 * Reads the bearer the hook installed, as the transport would send it.
 *
 * Assumptions: the bearer is observed by DISPATCHING rather than by reading a variable, because
 * `ui/src/api/client.ts` exports a setter and no getter -- deliberately, so that nothing but the
 * request interceptor can read the token. Issuing one throwaway request and reading the header the
 * interceptor attached observes exactly what a real call would carry, and it is the same route
 * `ui/src/screens/signon/signon.test.tsx` uses for the same reason.
 *
 * Assumptions: the probe is selected BY TARGET rather than by being the only recorded request, and the
 * record is cleared afterwards. Sign-out is not among the three mocked operations, so ending a session
 * dispatches a real revocation call that this harness records -- reading "the one request" would fail on
 * that traffic, and reading "the last request" would race it, since the revocation settles on its own
 * schedule. Selecting `/probe` is stable under both.
 * @returns {Promise<string | undefined>} The `authorization` header, or `undefined` when the transport
 *   would send none.
 */
async function bearerOnTheWire(): Promise<string | undefined> {
  forgetDispatchedRequests();
  answerEveryRequestWith({});
  await getApiClient().get(PROBE_TARGET);
  const probes = dispatchedRequests().filter(isProbe);
  expect(probes, 'the probe request must have been recorded exactly once').toHaveLength(1);
  const probe = probes[0];
  if (probe === undefined) {
    throw new Error('the probe request was not recorded');
  }
  forgetDispatchedRequests();
  return probe.headers.authorization;
}

/**
 * Installs fake timers, an intercepting transport and a clean store, so no case inherits another's
 * session, schedule or recorded requests.
 *
 * Assumptions: the transport harness is installed BEFORE any session is established, because
 * installing it discards the axios instance -- which would discard the request interceptor's identity
 * but never the bearer, since the bearer is module state the instance does not own. Installing it
 * afterwards would therefore still work; doing it first keeps the order the same as every other suite's.
 * @returns {void} Nothing; the environment is reset in place.
 */
function resetEnvironment(): void {
  vi.useFakeTimers();
  installApiHarness();
  live = null;
}

/**
 * Restores real timers and removes the intercepting transport again.
 * @returns {void} Nothing; the environment is restored in place.
 */
function restoreEnvironment(): void {
  vi.useRealTimers();
  removeApiHarness();
}

beforeEach(resetEnvironment);
afterEach(restoreEnvironment);

/**
 * A refresh that settles after sign-out does not reinstall the session.
 * @returns {Promise<void>} Resolves once the late response has been dropped.
 */
async function aRefreshSettlingAfterSignOutIsDropped(): Promise<void> {
  render(<Probe />);
  await signOnAs('USER0001');
  expect(reading().signedOn).toBe(true);
  expect(await bearerOnTheWire()).toBe('Bearer access-USER0001');

  const held = defer<SignOnTokens>();
  refreshTokensMock.mockReturnValueOnce(held.promise);
  await fireScheduledRefresh();
  expect(refreshTokensMock).toHaveBeenCalledTimes(1);

  signOutThroughHook();
  expect(reading().signedOn).toBe(false);

  await settleHeldResponse(
    /**
     * Delivers the response for a session that no longer exists.
     * @returns {void} Nothing.
     */
    (): void => {
      held.resolve(tokensFor('USER0001'));
    },
  );

  /*
   * Assumptions: the identifier, the groups and the bearer are all asserted, because the defect this
   *   case exists for reinstalls a WHOLE session and any one of the three surviving would be the
   *   resurrection. The renewal token has no observable surface of its own -- nothing publishes it and
   *   nothing may -- so it is asserted indirectly: a reinstalled renewal token would have re-armed the
   *   schedule, and the refresh count below is what would have moved.
   */
  expect(reading().signedOn).toBe(false);
  expect(reading().userId).toBeNull();
  expect(reading().groups).toEqual([]);
  expect(reading().status).toBe('anonymous');
  expect(await bearerOnTheWire()).toBeUndefined();
  expect(refreshTokensMock).toHaveBeenCalledTimes(1);
}

/**
 * A refresh refused for a previous operator does not discard the current operator's session.
 * @returns {Promise<void>} Resolves once the late refusal has been dropped.
 */
async function aStaleRefusalDoesNotClearTheNewerSession(): Promise<void> {
  render(<Probe />);
  await signOnAs('USER0001');

  const held = defer<SignOnTokens>();
  refreshTokensMock.mockReturnValueOnce(held.promise);
  await fireScheduledRefresh();

  // A second operator signs on while the first operator's refresh is still in flight.
  await signOnAs('USER0002');
  expect(reading().userId).toBe('USER0002');

  await settleHeldResponse(
    /**
     * Refuses the held refresh, raised against the earlier session.
     * @returns {void} Nothing.
     */
    (): void => {
      held.reject(new Error('refresh refused'));
    },
  );

  expect(reading().userId).toBe('USER0002');
  expect(await bearerOnTheWire()).toBe('Bearer access-USER0002');
  expect(reading().signedOn).toBe(true);
  expect(reading().status).toBe('authenticated');
  expect(reading().error).toBeNull();
}

/**
 * A sign-on settling after a later sign-on does not overwrite the newer operator's tokens.
 * @returns {Promise<void>} Resolves once the earlier response has been dropped.
 */
async function anOvertakenSignOnDoesNotOverwriteTheNewerSession(): Promise<void> {
  render(<Probe />);

  const held = defer<SignOnTokens>();
  signOnMock.mockReturnValueOnce(held.promise);
  /*
   * Assumptions: starting the exchange is wrapped in `act` even though nothing is awaited here, because
   * the hook publishes a reading SYNCHRONOUSLY when an exchange begins - the in-flight count changes -
   * so the probe re-renders inside this call. Leaving it unwrapped is what React's act warning is for.
   */
  let first: Promise<unknown> = Promise.resolve();
  act(
    /**
     * Starts the first sign-on and keeps its promise for later settlement.
     * @returns {void} Nothing; the promise is held in `first`.
     */
    (): void => {
      first = reading().signIn('USER0001', 'PASS0001');
    },
  );

  await signOnAs('USER0002');

  await settleHeldResponse(
    /**
     * Delivers the earlier operator's tokens after the later operator signed on.
     * @returns {void} Nothing.
     */
    (): void => {
      held.resolve(tokensFor('USER0001'));
    },
  );
  await first;

  expect(reading().userId).toBe('USER0002');
  expect(await bearerOnTheWire()).toBe('Bearer access-USER0002');
  expect(reading().signedOn).toBe(true);
}

/**
 * A refusal raised against the CURRENT session is still applied, so the guard is not over-broad.
 *
 * Assumptions: this is the negative control, and it is what stops the three cases above from being
 * satisfied by a hook that simply ignored every settlement. A refused refresh for the live session must
 * still end that session, which is the behaviour the module documents.
 * @returns {Promise<void>} Resolves once the refusal has been applied.
 */
async function aCurrentRefusalStillEndsTheSession(): Promise<void> {
  render(<Probe />);
  await signOnAs('USER0001');

  const held = defer<SignOnTokens>();
  refreshTokensMock.mockReturnValueOnce(held.promise);
  await fireScheduledRefresh();

  await settleHeldResponse(
    /**
     * Refuses the refresh for the session that is still current.
     * @returns {void} Nothing.
     */
    (): void => {
      held.reject(new Error('refresh refused'));
    },
  );

  expect(reading().signedOn).toBe(false);
  expect(await bearerOnTheWire()).toBeUndefined();
  expect(reading().status).toBe('error');
}

/**
 * Registers the session-ordering cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function sessionOrderingCases(): void {
  it('drops a refresh that settles after sign-out', aRefreshSettlingAfterSignOutIsDropped);
  it('drops a refusal raised against a replaced session', aStaleRefusalDoesNotClearTheNewerSession);
  it('drops an overtaken sign-on result', anOvertakenSignOnDoesNotOverwriteTheNewerSession);
  it('still applies a refusal for the current session', aCurrentRefusalStillEndsTheSession);
}

describe('useAuth session ordering', sessionOrderingCases);
