/**
 * @file Integration tests for the session integrity guarantees in `ui/src/hooks/useAuth.ts`: which
 * outcomes are allowed to change the held session, what an issued token set has to consist of before it
 * becomes one, and who keeps the bearer renewed.
 *
 * Purpose
 * -------
 * Three defects a review named share one subject and are asserted together here, because each of them
 * is a race or a partial state that no single-call unit case can express.
 *
 * The first is ORDERING. Every exchange used to mutate the session when it settled, whatever had
 * happened in between: a sign-on answered after the operator signed out reinstated the session they had
 * just ended, and a renewal refused after a newer sign-on succeeded signed them straight back out. Both
 * are now impossible because an exchange belongs to a session generation and may act only while that
 * generation is still current — so the cases below settle a superseded exchange deliberately late and
 * assert that nothing moved.
 *
 * The second is COMPLETENESS. A token set used to be written value by value, with each write able to
 * fail on its own and every failure swallowed, and "signed on" meant an identity token was present. A
 * tab could therefore hold an identity token from one session beside a bearer from another and report
 * itself signed on. The set is now validated as a whole, including that its identity token describes the
 * identifier it arrived with, and nothing is installed until all of it is accepted.
 *
 * The third is MAINTENANCE. The renewal timer was cancelled with the last subscriber and never armed
 * again, so a session that outlived a remount kept a valid renewal token and lost the machinery that
 * used it — the next request was refused with a 401 and the operator was signed out mid-task. The timer
 * is now armed from the session rather than from an issued lifetime, which is what lets anything that
 * observes the session rearm it.
 *
 * Assumptions: these are INTEGRATION cases spanning the hook, the typed client and the transport. Two of
 * the three properties are only observable across that span — an abandoned outcome is a REQUEST that
 * settles late, and a rearmed timer is a REQUEST that appears — so a unit case with a stubbed client
 * could assert neither.
 *
 * Assumptions: the pending requests are held open by an adapter this file installs, rather than answered
 * by the shared harness's queue. The shared adapter resolves immediately, which is exactly what an
 * ordering case cannot have: the outcome has to still be in flight while the session moves underneath it.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, renderHook, waitFor } from '@testing-library/react';
import { AxiosError } from 'axios';
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { resetAuthSession, useAuth } from './useAuth';
import type { UseAuthResult } from './useAuth';
import { getApiClient } from '../api/client';
import type { DispatchedRequest } from '../test/apiHarness';
import {
  answerEveryRequestWith,
  answerWith,
  dispatchedRequests,
  forgetDispatchedRequests,
  installApiHarness,
  removeApiHarness,
} from '../test/apiHarness';
import { endAnySession } from '../test/sessionHarness';

/** The identifier every case signs on as, unless it is deliberately signing on as someone else. */
const USER_ID = 'USER0001';

/** Status a successful exchange is answered with. */
const OK = 200;

/** Status the pool answers when it will not accept the credential presented. */
const UNAUTHORIZED = 401;

/** Bearer lifetime, in seconds, comfortably beyond the sixty-second renewal margin. */
const LONG_LIFETIME_SECONDS = 3600;

/**
 * Bearer lifetime, in seconds, already INSIDE the sixty-second renewal margin.
 *
 * Assumptions: thirty seconds rather than a negative or zero value, because the reachable case is a
 * token whose remaining life is short rather than one already expired — a rearm happening long after the
 * token was issued finds exactly this. `REFRESH_MARGIN_SECONDS` is 60 in `ui/src/hooks/useAuth.ts`, so
 * thirty is unambiguously inside it without relying on the clock.
 */
const SHORT_LIFETIME_SECONDS = 30;

/**
 * Milliseconds to advance so a timer armed for a long-lived session fires.
 *
 * Assumptions: the value is the lifetime less the margin, plus one second of slack. The slack absorbs
 * the few milliseconds that elapse between the token being issued and the timer being armed, which would
 * otherwise leave the timer a fraction short of firing.
 */
const ADVANCE_TO_RENEWAL_MS = (LONG_LIFETIME_SECONDS - 60 + 1) * 1000;

/** The renewal target, spelled as `ui/src/api/auth.ts` composes it. */
const RENEWAL_PATH = '/auth/refresh';

/** One request held open by {@link gatedAdapter}, with the two ways it can be settled. */
interface HeldRequest {
  /** The target the client composed, so a case can tell one held request from another. */
  readonly url: string;
  /** Answers the request successfully with the supplied body. */
  readonly answer: (body: unknown) => void;
  /** Refuses the request as the pool refuses an unacceptable credential. */
  readonly refuse: () => void;
}

/** Requests currently held open, in dispatch order. */
let held: HeldRequest[] = [];

/** Whatever the refused sign-on in the ordering cases rejected with, once it has. */
let recordedRefusal: unknown;

/**
 * Builds an identity token for one identifier, in the shape the pool issues.
 *
 * Assumptions: the subject claim is what `installTokens` compares against the identifier the set arrived
 * with, so it is the claim these cases vary. Only the claim segment has to decode: nothing in the browser
 * verifies an identity token, because every service does that independently.
 * @param {string} subject - Value for the `cognito:username` claim.
 * @returns {string} A three-segment token whose claim segment decodes to that subject and one group.
 */
function idTokenNaming(subject: string): string {
  const claims = JSON.stringify({
    'cognito:username': subject,
    'cognito:groups': ['carddemo-user'],
  });
  return `header.${btoa(claims)}.signature`;
}

/**
 * Builds a complete authenticated outcome.
 * @param {number} [expiresIn] - Bearer lifetime in seconds.
 * @param {string} [subject] - Subject the identity token names, for the mismatch case.
 * @returns {Record<string, unknown>} An outcome the installer accepts, unless the subject was varied.
 */
function authenticatedBody(
  expiresIn: number = LONG_LIFETIME_SECONDS,
  subject: string = USER_ID,
): Record<string, unknown> {
  return {
    outcome: 'AUTHENTICATED',
    userId: USER_ID,
    accessToken: 'an-accepted-access-token',
    idToken: idTokenNaming(subject),
    refreshToken: 'a-refresh-token',
    tokenType: 'Bearer',
    expiresIn,
  };
}

/**
 * Holds every dispatched request open until a case settles it.
 *
 * Assumptions: this REPLACES the shared harness's adapter for the cases that need one, because that
 * adapter resolves immediately and an ordering case needs the outcome to still be in flight while the
 * session changes underneath it. Requests are still recorded by the harness, because the harness's
 * adapter is what recorded them and this one is installed in its place — so a case that needs the
 * recording uses the harness adapter and a case that needs the delay uses this.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} A promise that settles only when the case settles it.
 */
async function gatedAdapter(config: AxiosRequestConfig): Promise<AxiosResponse> {
  return new Promise<AxiosResponse>(
    /**
     * Registers this request's two settlement routes without taking either.
     * @param {(value: AxiosResponse) => void} resolve - Answers the request.
     * @param {(reason: unknown) => void} reject - Refuses the request.
     * @returns {void} Nothing; the request is held until a case settles it.
     */
    function holdItOpen(resolve, reject): void {
      held.push({
        url: config.url ?? '',
        /**
         * Answers this request successfully.
         * @param {unknown} body - The response body.
         * @returns {void} Nothing; the awaiting caller resumes.
         */
        answer(body: unknown): void {
          resolve({
            data: body,
            status: OK,
            statusText: '',
            headers: {},
            config,
          } as AxiosResponse);
        },
        /**
         * Refuses this request as an unacceptable credential is refused.
         * @returns {void} Nothing; the awaiting caller's promise rejects.
         */
        refuse(): void {
          reject(
            new AxiosError(
              `Request failed with status code ${String(UNAUTHORIZED)}`,
              AxiosError.ERR_BAD_REQUEST,
              config as InternalAxiosRequestConfig,
              null,
              {
                data: { message: 'Wrong Password. Try again ...' },
                status: UNAUTHORIZED,
                statusText: 'Unauthorized',
                headers: {},
                config,
              } as AxiosResponse,
            ),
          );
        },
      });
    },
  );
}

/**
 * The exchanges a case has started and will settle later, in the order they were started.
 *
 * Assumptions: held at module scope and emptied per case, because a case starts an exchange inside an
 * `act` scope — where it cannot return a value to the caller — and settles it several statements later.
 */
let started: Promise<unknown>[] = [];

/**
 * Builds a thunk that starts a sign-on INSIDE an `act` scope and retains its promise for later.
 *
 * Assumptions: the exchange is started inside the scope rather than before it, and that is not
 * cosmetic: `signIn` increments the in-flight count and notifies its listeners synchronously, so a call
 * evaluated as an argument to `act` updates React before the scope opens — which React reports as an
 * unwrapped update on every case in this file.
 *
 * Assumptions: a factory returning a NAMED function expression rather than an inline arrow, because
 * `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with `publicOnly: false` and selects a function
 * in every position — and a block comment attached to an inline argument is moved by Prettier onto the
 * preceding expression, which detaches it from what it documents.
 * @param {object} result - The rendered hook's live reading.
 * @param {UseAuthResult} result.current - The reading itself, re-read on each access.
 * @param {string} password - The password to submit, which nothing in this process evaluates.
 * @returns {() => Promise<void>} A thunk that starts the exchange and yields one turn, so the request
 *   reaches the adapter. It does NOT wait for the exchange to settle.
 */
function startSignOnInside(
  result: { current: UseAuthResult },
  password: string,
): () => Promise<void> {
  /**
   * Starts the sign-on and lets its request reach the adapter.
   * @returns {Promise<void>} Resolves after one turn of the microtask queue.
   */
  return async function startTheSignOn(): Promise<void> {
    started.push(result.current.signIn(USER_ID, password).catch(recordTheRefusal));
    await Promise.resolve();
  };
}

/**
 * Builds a thunk that awaits every started exchange inside an `act` scope.
 * @returns {() => Promise<void>} A thunk that resolves once all of them have settled and React has
 *   flushed whatever they changed.
 */
function settleStartedInside(): () => Promise<void> {
  /**
   * Awaits every exchange started so far.
   * @returns {Promise<void>} Resolves once all of them have settled.
   */
  return async function settleTheExchanges(): Promise<void> {
    await Promise.all(started);
  };
}

/**
 * Builds a thunk that discards the module's session INSIDE an `act` scope, abandoning what is in flight.
 *
 * Assumptions: the reset runs inside the scope rather than before it because it notifies listeners
 * synchronously, and a notification delivered outside a scope is reported by React as an unwrapped
 * update -- the same reason {@link startSignOnInside} exists.
 * @returns {() => Promise<void>} A thunk that resets and yields one turn, so whatever the abandonment
 *   rejected can reach its handlers.
 */
function resetInside(): () => Promise<void> {
  /**
   * Discards the session and lets the abandonment propagate.
   * @returns {Promise<void>} Resolves after one turn of the microtask queue.
   */
  return async function resetTheSession(): Promise<void> {
    resetAuthSession();
    await Promise.resolve();
  };
}

/**
 * Builds a thunk that advances the mocked clock inside an `act` scope.
 * @param {number} milliseconds - How far to advance.
 * @returns {() => Promise<void>} A thunk that runs every timer due in that span.
 */
function advanceInside(milliseconds: number): () => Promise<void> {
  /**
   * Advances the mocked clock and lets everything it triggered settle.
   * @returns {Promise<void>} Resolves once the timers and their continuations have run.
   */
  return async function advanceTheClock(): Promise<void> {
    await vi.advanceTimersByTimeAsync(milliseconds);
  };
}

/**
 * Builds a thunk that lets work an install started reach the transport, inside an `act` scope.
 *
 * Assumptions: this crosses a MACROTASK boundary rather than yielding a fixed number of microtask
 * turns, because the request path is a promise chain of unknown length -- the client's interceptors,
 * then the adapter -- and a count that happened to be one turn short would report a request as never
 * dispatched. A macrotask boundary drains the whole microtask queue behind it, so what has not been
 * dispatched by then was not going to be.
 *
 * Assumptions: this is the REAL-CLOCK counterpart of {@link advanceInside}, which requires mocked
 * timers. A case asserting that nothing was dispatched must not mock the clock, because the immediate
 * renewal it is ruling out is dispatched without any timer at all.
 * @returns {() => Promise<void>} A thunk that resolves once the queues behind it have drained.
 */
function letStartedWorkReachTheTransportInside(): () => Promise<void> {
  /**
   * Yields to the event loop so anything already started can reach the transport.
   * @returns {Promise<void>} Resolves on the next macrotask.
   */
  return async function letItReach(): Promise<void> {
    await new Promise<void>(
      /**
       * Resumes on the next macrotask.
       * @param {() => void} resume - Continues the awaiting caller.
       * @returns {void} Nothing; the caller resumes once the queue behind it has drained.
       */
      function onTheNextMacrotask(resume: () => void): void {
        setTimeout(resume, 0);
      },
    );
  };
}

/**
 * Records whatever a deliberately refused exchange rejected with.
 *
 * Assumptions: the handler is attached at the moment the exchange is STARTED rather than at the end of
 * the case, so the rejection is never momentarily unhandled — a rejection left unattached for even one
 * turn is reported as an unhandled rejection and fails the run for the wrong reason.
 * @param {unknown} cause - Whatever the exchange rejected with.
 * @returns {void} Nothing; the cause is retained for inspection.
 */
function recordTheRefusal(cause: unknown): void {
  recordedRefusal = cause;
}

/**
 * Asserts a renewal has reached the transport.
 * @returns {void} Nothing; throws until a renewal appears among the dispatched requests.
 */
function aRenewalHasBeenDispatched(): void {
  expect(
    dispatchedRequests().map(targetOf),
    'the session had to be renewed, so a renewal must have been dispatched',
  ).toContain(RENEWAL_PATH);
}

/**
 * Asserts a second renewal has reached the transport.
 *
 * Assumptions: the COUNT is asserted rather than the presence of the target, because the case that uses
 * this needs the argument of the renewal that followed an answer carrying no refresh member -- so one
 * renewal is not enough and waiting for one would read the first renewal's argument, which proves
 * nothing about what the client retained.
 * @returns {void} Nothing; throws until two renewals appear among the dispatched requests.
 */
function twoRenewalsHaveBeenDispatched(): void {
  expect(
    dispatchedRequests().map(targetOf).filter(isRenewal).length,
    'the client had to renew twice, so two renewals must have been dispatched',
  ).toBeGreaterThan(1);
}

/**
 * Reports whether one recorded target is a renewal.
 * @param {string} target - The target the client composed.
 * @returns {boolean} `true` when the target is the renewal endpoint.
 */
function isRenewal(target: string): boolean {
  return target.includes(RENEWAL_PATH);
}

/**
 * Reports whether one recorded request is a renewal.
 *
 * Assumptions: this exists alongside the target predicate above rather than replacing it, because the
 * two are used for different things -- counting renewals needs only their targets, while reading a
 * renewal's BODY needs the recorded request itself.
 * @param {DispatchedRequest} dispatched - One recorded request.
 * @returns {boolean} `true` when the request was addressed to the renewal endpoint.
 */
function dispatchIsARenewal(dispatched: DispatchedRequest): boolean {
  return isRenewal(targetOf(dispatched));
}

/**
 * Reports one dispatched request's target.
 * @param {DispatchedRequest} request - The recorded request.
 * @returns {string} The target the client composed for it.
 */
function targetOf(request: DispatchedRequest): string {
  return request.url;
}

/**
 * A sign-on that settles after the operator signed out must not reinstate the session.
 *
 * Assumptions: TWO mechanisms cooperate here and the case asserts the outcome of both. Signing out
 * supersedes the session generation, which aborts the in-flight request — axios converts an abort into a
 * cancellation even after the adapter has answered, so the outcome never reaches the installer at all.
 * And the cancellation that results is judged against the generation it belonged to, so it is reported to
 * its own caller and is NOT published as this session's state. Either half alone would leave a defect: no
 * abort and the answer would install a session the operator had ended; no generation guard and the
 * abandoned request's failure would be rendered at the sign-on screen as though the operator's sign-out
 * had gone wrong.
 *
 * Assumptions: the installer's own generation guard is deliberately belt-and-braces rather than the
 * primary defence, and it is worth keeping for that reason. It holds if a transport ever delivers a
 * response despite the abort — an adapter that ignores `signal`, or a future one — where the abort alone
 * would not.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function aSupersededSignOnCannotResurrectASignedOutSession(): Promise<void> {
  getApiClient().defaults.adapter = gatedAdapter;
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'a-password'));

  // Assumptions: the transport is answered FIRST and the sign-out follows immediately, without a turn in
  //   between, so the outcome is in flight at the moment the session is ended. Answering after the
  //   sign-out would exercise only the abort of an unanswered request, which is a different arrangement.
  held[0]?.answer(authenticatedBody());
  // Assumptions: `signOut` is synchronous, so the generation moves before the answer's continuation can
  //   run. That is what makes this deterministic rather than a race the case hopes to win.
  result.current.signOut();

  await act(settleStartedInside());

  expect(result.current.signedOn, 'a signed-out session must not be reinstated').toBe(false);
  expect(result.current.userId).toBeNull();
  expect(result.current.status, 'the abandoned exchange must not publish an error state').toBe(
    'anonymous',
  );
  expect(
    result.current.error,
    'the abandoned exchange must not publish a problem document',
  ).toBeNull();
  expect(recordedRefusal, 'the abandonment must still be reported to its own caller').toBeDefined();
}

/**
 * A refusal belonging to a superseded sign-on must not end the session a later one established.
 *
 * Assumptions: two sign-ons are the realistic form of this. An operator who mistypes a password and
 * resubmits before the first attempt is answered produces exactly this ordering, and the outcome without
 * the guard is that their successful second attempt is undone by the failure of their first.
 *
 * Assumptions: the first attempt is refused by the pool AFTER the second has been accepted, which is
 * what makes its failure unambiguously stale. Starting the second attempt also aborts the first request,
 * and an abandonment lands in the very same guarded catch — `exchangeRefreshToken` and `signIn` both
 * record that an aborted request is superseded by construction — so this one arrangement covers both a
 * late refusal and a cancellation, and the file needs no separate case for the latter.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function aSupersededRefusalCannotClearANewerSession(): Promise<void> {
  getApiClient().defaults.adapter = gatedAdapter;
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'a-mistyped-password'));
  await act(startSignOnInside(result, 'the-right-password'));

  // Assumptions: the second attempt is answered BEFORE the first is refused, so the refusal is
  //   unambiguously the stale one. Refusing first would let the two continuations run in the opposite
  //   order and the case would then be asserting nothing about staleness.
  held[1]?.answer(authenticatedBody());
  held[0]?.refuse();
  await act(settleStartedInside());

  expect(
    recordedRefusal,
    'the first attempt must still be reported to its own caller',
  ).toBeDefined();
  expect(result.current.signedOn, 'the second attempt must have established a session').toBe(true);
  expect(result.current.userId).toBe(USER_ID);
  expect(result.current.status, 'a stale failure must not end a newer session').toBe(
    'authenticated',
  );
  expect(
    result.current.error,
    'a stale failure must not be published as this session state',
  ).toBeNull();
}

/**
 * An issued set missing one member establishes nothing, rather than half a session.
 *
 * Assumptions: the ACCESS token is the member omitted, because it is the one whose absence the old
 * behaviour hid completely: "signed on" was the identity token's presence, so a set without a bearer
 * reported a live session in which every request travelled unauthenticated and was refused.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function anIncompleteIssuedSetEstablishesNoSession(): Promise<void> {
  const incomplete = authenticatedBody();
  delete incomplete['accessToken'];
  answerWith(incomplete);
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'a-password'));
  await act(settleStartedInside());

  expect(recordedRefusal, 'an incomplete set must be refused, not tolerated').toBeInstanceOf(
    RangeError,
  );
  expect(result.current.signedOn, 'an incomplete set must establish no session').toBe(false);
  expect(result.current.userId).toBeNull();
}

/**
 * An issued set whose identity token names another operator establishes nothing.
 *
 * Assumptions: this is a service fault rather than an operator error, and it is refused anyway. The
 * consequence of installing it is a tab whose guards read one operator's authority while every request
 * carries another's bearer, which is the worst available outcome and the hardest to notice.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function aMismatchedSubjectEstablishesNoSession(): Promise<void> {
  answerWith(authenticatedBody(LONG_LIFETIME_SECONDS, 'OTHER001'));
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'a-password'));
  await act(settleStartedInside());

  expect(recordedRefusal, 'a mismatched set must be refused, not tolerated').toBeInstanceOf(
    RangeError,
  );
  expect(result.current.signedOn, 'a mismatched set must establish no session').toBe(false);
  expect(result.current.userId).toBeNull();
}

/**
 * A session whose bearer is already inside the renewal margin is renewed at once.
 *
 * Assumptions: this case became REACHABLE with the rearm. A timer armed at the moment a token is issued
 * always had time in hand; a timer armed from the session, long afterwards, may find none — and waiting
 * out a floored delay would spend an exchange presenting a credential that had already expired.
 * @returns {Promise<void>} Resolves once a renewal has been dispatched.
 */
async function aSessionInsideTheMarginIsRenewedAtOnce(): Promise<void> {
  answerWith(authenticatedBody(SHORT_LIFETIME_SECONDS));
  // Assumptions: the renewal is answered from the FALLBACK rather than queued, because the queue is
  //   consumed in order and a queued renewal answer would be handed to the sign-on instead.
  answerEveryRequestWith(authenticatedBody());
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'a-password'));
  await act(settleStartedInside());

  await waitFor(aRenewalHasBeenDispatched);
  expect(result.current.signedOn, 'the renewal must leave the session established').toBe(true);
}

/**
 * The renewal timer is armed again when the last consumer unmounts and something mounts anew.
 *
 * Assumptions: EVERY consumer is unmounted before the remount, because the timer is cancelled with the
 * LAST listener — a case that left one mounted would still hold a live timer and would pass without the
 * rearm existing at all.
 *
 * Assumptions: the clock is mocked only for the second half. The session is established against the real
 * clock, so its expiry is a real instant; mocking afterwards freezes the clock at that same instant, and
 * advancing it is what makes an hour-long wait expressible.
 * @returns {Promise<void>} Resolves once a renewal has been dispatched.
 */
async function theRenewalTimerIsRearmedOnRemount(): Promise<void> {
  answerWith(authenticatedBody());
  answerEveryRequestWith(authenticatedBody());
  const first = renderHook(useAuth);
  await act(startSignOnInside(first.result, 'a-password'));
  await act(settleStartedInside());
  expect(first.result.current.signedOn).toBe(true);

  first.unmount();
  forgetDispatchedRequests();

  vi.useFakeTimers();
  try {
    const second = renderHook(useAuth);

    await act(advanceInside(ADVANCE_TO_RENEWAL_MS));

    aRenewalHasBeenDispatched();
    expect(second.result.current.signedOn, 'the renewed session must still be held').toBe(true);
    second.unmount();
  } finally {
    vi.useRealTimers();
  }
}

/**
 * Installs the harness and empties this file's own recordings.
 * @returns {void} Nothing; no session is held, nothing is in flight and the harness is installed.
 */
function installTheHarness(): void {
  endAnySession();
  installApiHarness();
  held = [];
  started = [];
  recordedRefusal = undefined;
}

/**
 * Settles anything still held open, then removes the harness and discards the session.
 *
 * Assumptions: the held requests are answered rather than abandoned, because a promise left pending at
 * teardown keeps its awaiting continuation alive into the next case — and a continuation that resumes
 * there would mutate a session that case had arranged.
 * @returns {void} Nothing; nothing is in flight, the harness is removed and no session is held.
 */
function removeTheHarness(): void {
  for (const request of held) {
    request.answer({});
  }
  held = [];
  started = [];
  removeApiHarness();
  endAnySession();
}

/**
 * Registers every session-integrity case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
/**
 * A renewal that carries no refresh token leaves the held one in place.
 *
 * Purpose
 * -------
 * Refresh-token ROTATION is enabled on the pool client, so a renewal ordinarily answers with a
 * replacement. The pool's retry grace period is a server-side setting, and configured above zero it
 * lets the submitted token stay current for a window -- in which case the answer carries no
 * replacement at all. A client that treated the absent member as "revoked" and cleared the held token
 * would sign the operator out at the next expiry, having thrown away a credential the pool still
 * honours.
 *
 * Refactoring Rationale: ⚠️ this property was asserted in a suite that read the refresh token back out
 * of `sessionStorage`. Nothing writes storage any more -- the session is held in a module variable so
 * no script-readable slot retains a refresh token -- so the observation moved to the only place the
 * value is still observable: the argument of the NEXT renewal. That is also the stronger assertion,
 * because it proves the retained token is the one actually presented rather than merely stored.
 *
 * Assumptions: two renewals are driven. The first answers without a refresh member, and the second's
 * argument is what carries the verdict; a client that had cleared the token would present `null` or
 * dispatch nothing at all, and both fail here.
 * @returns {Promise<void>} Resolves once the second renewal has been dispatched.
 */
async function anAnswerWithoutARefreshTokenKeepsTheHeldOne(): Promise<void> {
  /*
   * WHY : ⚠️ Refactoring Rationale: the answers are queued as a SEQUENCE ending in a long-lived one,
   *       where `answerEveryRequestWith` answered every renewal with another lifetime already inside the
   *       margin. That is an unterminated loop, not a two-renewal drive: the hook renews at once for a
   *       token inside its margin -- the case above this one exists for exactly that -- so an answer that
   *       is always inside the margin re-arms immediately, forever, and the fallback answers it forever.
   *       The run did not fail; the worker exhausted its heap on the recorded requests and was killed,
   *       taking this whole file's seven cases out of the reported totals while every one of them read as
   *       neither passed nor failed.
   * WHY : Assumptions: the two renewals the assertion needs are driven by the two QUEUED answers -- the
   *       sign-on's, inside the margin, and the first renewal's, inside the margin and carrying no
   *       refresh member -- while the FALLBACK is comfortably outside it, so the second renewal re-arms
   *       an hour out and the drive terminates. The verdict is unchanged: the second renewal's argument
   *       is what proves the token the first answer omitted is still held.
   */
  answerWith(authenticatedBody(SHORT_LIFETIME_SECONDS));

  const { refreshToken: removedMember, ...withoutRefresh } =
    authenticatedBody(SHORT_LIFETIME_SECONDS);

  // Assumptions: the removed member is ASSERTED rather than discarded into an unread binding. The
  //   arrangement's whole premise is that a complete answer had a refresh token taken out of it, and a
  //   body that never carried one would arrange nothing while leaving this case green -- so reading the
  //   binding is what makes the premise checked instead of assumed.
  expect(
    removedMember,
    'the answer being reduced must have carried a refresh token for its removal to arrange anything',
  ).not.toBeUndefined();
  answerWith(withoutRefresh);
  answerEveryRequestWith(authenticatedBody(LONG_LIFETIME_SECONDS));
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'a-password'));
  await act(settleStartedInside());

  await waitFor(twoRenewalsHaveBeenDispatched);

  const renewals = dispatchedRequests().filter(dispatchIsARenewal);

  expect(
    renewals.length,
    'two renewals must have been dispatched to observe the second one argument',
  ).toBeGreaterThan(1);
  expect(
    JSON.stringify(renewals[renewals.length - 1]?.body ?? {}),
    'the renewal must still present the refresh token the sign-on issued',
  ).toContain('a-refresh-token');
  expect(result.current.signedOn, 'the session must remain established').toBe(true);
}

/**
 * A FRESH sign-on that answers without a refresh token does not inherit the previous operator's.
 *
 * ⚠️ Purpose: this is the complement of the case above, and the pair states where the rotation
 * tolerance ends. The tolerance keeps a held refresh token when an answer omits one, which is right for a
 * RENEWAL of the session that token belongs to and wrong for every other install. Signing on does not
 * clear the held session first -- the generation moves and what is in flight is abandoned, and the record
 * stays until an install replaces it -- so an operator signing on at a tab another operator had signed on
 * at, with an answer carrying no refresh token, was installed WITH THE PREVIOUS OPERATOR'S refresh token.
 * The scheduled renewal then presents one principal's credential under the other's identifier.
 *
 * Assumptions: the verdict is that NO renewal is dispatched, which is what a session holding no refresh
 * token does -- nothing is armed for it, and the scheduled refresh returns without exchanging even if it
 * ran. Against the defect a renewal IS dispatched, carrying the first operator's token, so the two
 * outcomes are distinguishable by the presence of the request alone. The token is additionally asserted
 * absent from every dispatched body, so a renewal reaching some other target could not slip past.
 *
 * Assumptions: the second sign-on answers with a SHORT lifetime, already inside the renewal margin, so
 * the renewal the defect would dispatch is dispatched IMMEDIATELY and with no timer -- arming a session
 * already inside its margin exchanges at once. A case arranging a long lifetime would have to mock and
 * advance a clock to reach the same point, and would read as green against the defect for as long as it
 * did not.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aFreshSignOnDoesNotInheritAHeldRefreshToken(): Promise<void> {
  answerWith(authenticatedBody(LONG_LIFETIME_SECONDS));

  const { refreshToken: removedMember, ...withoutRefresh } =
    authenticatedBody(SHORT_LIFETIME_SECONDS);
  expect(
    removedMember,
    'the answer being reduced must have carried a refresh token for its removal to arrange anything',
  ).not.toBeUndefined();
  answerWith(withoutRefresh);
  answerEveryRequestWith(authenticatedBody(LONG_LIFETIME_SECONDS));
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'the-first-password'));
  await act(settleStartedInside());
  forgetDispatchedRequests();

  await act(startSignOnInside(result, 'the-second-password'));
  await act(settleStartedInside());
  await act(letStartedWorkReachTheTransportInside());

  expect(
    dispatchedRequests().filter(dispatchIsARenewal),
    'a session installed without a refresh token has nothing to renew with, so no renewal may be sent',
  ).toHaveLength(0);
  expect(
    dispatchedRequests().map(
      /**
       * Renders one dispatched request's body as text, so it can be searched for a credential.
       * @param {DispatchedRequest} dispatched - A request the transport recorded.
       * @returns {string} That request's body, serialised.
       */
      function bodyOf(dispatched: DispatchedRequest): string {
        return JSON.stringify(dispatched.body ?? {});
      },
    ),
    'the first operator credential must not travel on any request made for the second',
  ).not.toContainEqual(expect.stringContaining('a-refresh-token'));
  expect(result.current.signedOn, 'the second session must be established').toBe(true);
}

/**
 * An exchange abandoned by a reset must not leave a later, live exchange reading as not in flight.
 *
 * ⚠️ Purpose: this is the arithmetic case. What is in flight decides the published status, and an
 * exchange in flight dominates that derivation, so the bookkeeping has to survive the one thing that
 * deliberately abandons exchanges: the reset a fixture performs between cases. With a COUNT it did not.
 * The reset zeroed the count, the abandoned exchange still ran its own `finally` and decremented to minus
 * one, and from there `> 0` read false while an exchange was genuinely running -- so the next sign-on
 * published `anonymous` while it was in flight, and a screen keyed on that status showed no progress and
 * left its submit control live for a second submission.
 *
 * Assumptions: the discriminating assertion is the one taken while the SECOND sign-on is in flight, not
 * the ones around the reset. Before the second sign-on both a set and a negative count report nothing in
 * flight, so a case that stopped there would read as green against the defect; it is the live exchange
 * that a negative count cannot lift above zero.
 *
 * Assumptions: the abandoned exchange is settled EXPLICITLY rather than left pending, because its
 * `finally` is what the defect ran too late -- a case that never let it run would never reach the state
 * being ruled out.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function anAbandonedExchangeCannotHideALiveOne(): Promise<void> {
  getApiClient().defaults.adapter = gatedAdapter;
  const { result } = renderHook(useAuth);

  await act(startSignOnInside(result, 'the-abandoned-password'));
  expect(result.current.status, 'a dispatched sign-on is in flight').toBe('authenticating');

  await act(resetInside());
  // Assumptions: the abandoned request is ANSWERED rather than left pending, and it has to be answered
  //   by the case: this file's adapter holds every request open and ignores `signal`, so the reset's
  //   abort does not settle it and awaiting it unanswered would hang. The answer is judged against the
  //   generation it belonged to and installs nothing -- which the first case in this file asserts -- so
  //   what it contributes here is only that the exchange's own `finally` runs.
  held[0]?.answer(authenticatedBody());
  await act(settleStartedInside());

  expect(
    result.current.status,
    'with nothing held and nothing running the reading is the anonymous one',
  ).toBe('anonymous');

  await act(startSignOnInside(result, 'the-live-password'));

  expect(
    result.current.status,
    'a live exchange must be reported as in flight however many were abandoned before it',
  ).toBe('authenticating');

  held[held.length - 1]?.answer(authenticatedBody());
  await act(settleStartedInside());

  expect(result.current.signedOn, 'the live exchange must still establish its session').toBe(true);
  expect(result.current.status, 'and the settled session is the authenticated reading').toBe(
    'authenticated',
  );
}

/**
 * Registers the session-integrity cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function sessionIntegrityCases(): void {
  beforeEach(installTheHarness);
  afterEach(removeTheHarness);
  it(
    'refuses to reinstate a signed-out session from a superseded sign-on',
    aSupersededSignOnCannotResurrectASignedOutSession,
  );
  it(
    'refuses to end a newer session from a superseded refusal',
    aSupersededRefusalCannotClearANewerSession,
  );
  it(
    'establishes no session from an incomplete issued set',
    anIncompleteIssuedSetEstablishesNoSession,
  );
  it(
    'establishes no session when the identity token names another operator',
    aMismatchedSubjectEstablishesNoSession,
  );
  it('renews at once a session already inside the margin', aSessionInsideTheMarginIsRenewedAtOnce);
  it(
    'reports a live exchange as in flight after a reset abandoned another',
    anAbandonedExchangeCannotHideALiveOne,
  );
  it(
    'does not inherit a held refresh token on a fresh sign-on',
    aFreshSignOnDoesNotInheritAHeldRefreshToken,
  );
  it(
    'keeps the held refresh token when a renewal answers without one',
    anAnswerWithoutARefreshTokenKeepsTheHeldOne,
  );
  it('rearms the renewal timer when a consumer mounts anew', theRenewalTimerIsRearmedOnRemount);
}

describe('session integrity', sessionIntegrityCases);
