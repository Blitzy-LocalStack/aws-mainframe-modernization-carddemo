/**
 * @file Establishes a real signed-on session for a test, through the only path that can establish one.
 *
 * Purpose
 * -------
 * `ui/src/hooks/useAuth.ts` holds its session in memory as one frozen record, installed by one private
 * function that validates the whole token set before accepting any of it. A test that needs a signed-on
 * caller therefore cannot arrange one by writing values anywhere: it has to perform an exchange.
 *
 * Refactoring Rationale: this module exists because seven test files used to arrange a session by
 * writing the four `carddemo.*` session-storage keys directly, and those keys are gone — a review found
 * every credential persisted in script-readable Web Storage and the session moved into memory. The
 * seeding was never only a convenience, though, and its removal is an improvement in its own right: a
 * test that wrote the keys asserted against a state no exchange could actually produce, so it would
 * have passed for a token set the real sign-on path refuses. Driving the real exchange means every case
 * that needs a session also proves a session can be established that way.
 *
 * Alternatives Considered: exporting a seeding function from `ui/src/hooks/useAuth.ts` for tests to
 * call. Rejected because it would publish a second writer of the session record on the production
 * surface, which is exactly the multiple-owner problem the single installer was introduced to end — and
 * a writer that skipped the validation would let every case that used it drift away from what the
 * application accepts.
 *
 * Assumptions: the caller has already installed `ui/src/test/apiHarness.ts`, because that is what makes
 * the exchange answerable without a network. This module queues one answer into that harness and drives
 * the hook; it installs nothing itself, so a caller keeps one harness lifecycle rather than two.
 */

import { act, cleanup, renderHook } from '@testing-library/react';

import { answerWith, forgetDispatchedRequests } from './apiHarness';
import { resetAuthSession, useAuth } from '../hooks/useAuth';
import type { UseAuthResult } from '../hooks/useAuth';
import { setAccessToken } from '../api/client';

/** The identifier a session is established for unless a case names another. */
export const SESSION_USER_ID = 'USER0001';

/** The access token a established session installs unless a case names another. */
export const SESSION_ACCESS_TOKEN = 'an-accepted-access-token';

/** The renewal token an established session holds unless a case names another. */
export const SESSION_REFRESH_TOKEN = 'a-refresh-token';

/**
 * Bearer lifetime, in seconds, that an established session reports.
 *
 * Assumptions: one hour, which is what the pool client is provisioned with — `access_token_validity`
 * defaults to 60 minutes in `infra/modules/cognito/variables.tf`. It matters that this is comfortably
 * longer than the sixty-second refresh margin: a shorter value would put the session inside the margin
 * at the moment it was installed, so the renewal timer would fire immediately and every case that merely
 * wanted a signed-on caller would also be exercising the renewal path.
 */
export const SESSION_LIFETIME_SECONDS = 3600;

/**
 * Builds an identity token carrying a user name and a group list, in the shape the pool issues.
 *
 * Assumptions: BOTH claims are present, and both are required. `cognito:username` is what
 * `useAuth`'s installer compares against the identifier the token set arrived with — a set whose
 * identity token names another operator is refused, so a token carrying only the group claim cannot
 * establish a session at all. `cognito:groups` is what the guards read to choose the administrative
 * surface.
 *
 * Assumptions: only the claim segment has to decode. The signature is a fixed placeholder and the
 * header names no algorithm that is honoured, because nothing in the browser verifies an identity
 * token — every service does that independently, and the reasoning is recorded on the decoder itself.
 * @param {readonly string[]} groups - Group names to place in the `cognito:groups` claim.
 * @param {string} [userId] - The user name to place in the `cognito:username` claim.
 * @returns {string} A three-segment token whose claim segment decodes to those two claims.
 */
export function idTokenFor(groups: readonly string[], userId: string = SESSION_USER_ID): string {
  const claims = JSON.stringify({ 'cognito:username': userId, 'cognito:groups': groups });
  return `header.${btoa(claims)}.signature`;
}

/** What an established session is to consist of, where a case needs something other than the default. */
export interface SessionRequest {
  /** Group names the identity token is to carry. */
  readonly groups?: readonly string[];
  /** The identifier the session is established for. */
  readonly userId?: string;
  /** The identity token to issue, for a case that needs one this module would not build. */
  readonly idToken?: string;
  /** The access token to install. */
  readonly accessToken?: string;
  /** The renewal token to hold, or `null` for a session the pool issued none for. */
  readonly refreshToken?: string | null;
  /** The bearer lifetime, in seconds, the answer is to report. */
  readonly expiresIn?: number;
}

/**
 * Establishes a signed-on session by driving a real sign-on exchange, and returns the hook's reading.
 *
 * Assumptions: the exchange is driven through `renderHook` rather than by calling the module's own
 * function, because the sign-on action is reachable only through the hook's result — which is also the
 * only way a real caller reaches it. Rendering a hook additionally means the listener that arms the
 * session's renewal timer is registered, so the session established here is maintained exactly as one
 * established by a screen would be.
 *
 * Assumptions: the returned handle is UNMOUNTED by the caller or by the file's own teardown, and this
 * module does not unmount it. A session survives its probe being unmounted — it is module state, not
 * component state — so unmounting here would leave the session in place with no listener arming its
 * timer, which is a state no application reaches.
 * @param {SessionRequest} [request] - What the session is to consist of; every member has a default.
 * @returns {Promise<{ result: { current: UseAuthResult }; unmount: () => void }>} The rendered hook's
 *   live reading and its teardown. The reading reports the established session by the time this resolves.
 * @throws {Error} If the sign-on exchange did not establish a session, which means the harness was not
 *   installed or the token set this module composed is no longer one the installer accepts.
 */
export async function establishSession(
  request: SessionRequest = {},
): Promise<{ result: { current: UseAuthResult }; unmount: () => void }> {
  const userId = request.userId ?? SESSION_USER_ID;
  const tokens = {
    outcome: 'AUTHENTICATED',
    userId,
    accessToken: request.accessToken ?? SESSION_ACCESS_TOKEN,
    idToken: request.idToken ?? idTokenFor(request.groups ?? ['carddemo-user'], userId),
    refreshToken: request.refreshToken === undefined ? SESSION_REFRESH_TOKEN : request.refreshToken,
    tokenType: 'Bearer',
    expiresIn: request.expiresIn ?? SESSION_LIFETIME_SECONDS,
  };
  answerWith(tokens);

  const rendered = renderHook(useAuth);
  // Assumptions: the exchange is wrapped in `act`, because it settles asynchronously and notifies the
  //   hook's listener when it does. Awaiting it unwrapped completes the exchange but leaves React to
  //   process the resulting render outside any act scope, which React reports as an unwrapped update on
  //   every case that arranges a session -- a warning that is noise rather than information, and noise
  //   loud enough to bury a real one.
  await act(exchangeThunk(rendered.result, userId));

  if (!rendered.result.current.signedOn) {
    throw new Error('the sign-on exchange did not establish a session');
  }
  // Assumptions: the arrangement's own request is dropped from the recording, so a case may assert on
  //   the requests its SUBJECT produced without its counts and indices encoding how this fixture happens
  //   to be built. The reasoning is argued in full on `forgetDispatchedRequests`.
  forgetDispatchedRequests();
  return { result: rendered.result, unmount: rendered.unmount };
}

/**
 * Builds the thunk `act` runs to perform the sign-on exchange.
 *
 * Assumptions: a factory returning a NAMED function expression rather than an inline arrow at the call
 * site, because `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with `publicOnly: false` and
 * selects a function in every position — and a block comment attached to an inline argument is moved by
 * Prettier onto the preceding expression, which detaches it from what it documents.
 * @param {object} result - The rendered hook's live reading, whose `signIn` is called.
 * @param {UseAuthResult} result.current - The reading itself, re-read on each access.
 * @param {string} userId - The identifier to sign on as.
 * @returns {() => Promise<void>} A thunk that performs the exchange and resolves when it has settled.
 */
function exchangeThunk(result: { current: UseAuthResult }, userId: string): () => Promise<void> {
  /**
   * Performs the sign-on exchange for the probe.
   *
   * Assumptions: the password is a fixed placeholder, because the harness answers the exchange itself
   * and no credential is ever evaluated — the pool that would evaluate one is not in this process.
   * @returns {Promise<void>} Resolves once the exchange has settled.
   */
  return async function performTheExchange(): Promise<void> {
    await result.current.signIn(userId, 'a-password');
  };
}

/**
 * Reports whether a session is currently held, without a case having to mount anything.
 *
 * Assumptions: the reading is taken by rendering the hook once and discarding it, rather than by
 * exporting the module's session variable. The variable is deliberately private -- that privacy is the
 * point of holding the session in module scope behind a hook -- and the published reading is what the
 * application itself acts on, so asserting on it asserts what a screen would see.
 *
 * Assumptions: the temporary hook is unmounted before returning, so this leaves nothing mounted for a
 * later query to find and cannot perturb a tree the case already rendered.
 * @returns {boolean} `true` when a session is held.
 */
export function isSignedOn(): boolean {
  // Assumptions: the hook is passed BY NAME rather than wrapped in an arrow. It takes no arguments, so
  //   the wrapper adds nothing, and `jsdoc/require-jsdoc` is configured with `publicOnly: false` here --
  //   it selects a function expression in every position, including an inline callback whose whole body
  //   is one call.
  const { result, unmount } = renderHook(useAuth);
  const held = result.current.signedOn;

  unmount();
  return held;
}

/**
 * Returns the module state a session touches to its loaded condition, without contacting anything.
 *
 * Purpose
 * -------
 * A test file runs its cases against one module instance, and both halves of a session outlive every
 * component: `ui/src/hooks/useAuth.ts` holds the identity values and `ui/src/api/client.ts` holds the
 * bearer. Either surviving into the next case would sign that case on as whoever the previous one signed
 * on as, which is the isolation `window.sessionStorage.clear()` used to provide when the session lived
 * in Web Storage.
 *
 * Assumptions: BOTH modules are reset, and the second is not redundant. The hook's discard clears the
 * bearer through the client on the way past, but a case that installed one directly — several in
 * `ui/src/api/client.test.ts` do, because the bearer is the subject there — never established a session
 * for the hook to discard, so the hook's reset alone would leave that bearer attached to every request
 * the next case dispatched.
 *
 * Assumptions: anything rendered is unmounted FIRST, and the order is the whole reason the unmount is
 * here rather than left to the caller. Discarding a session notifies the hook's listeners, and Vitest
 * runs a file's own `afterEach` BEFORE the one `ui/src/test/setup.ts` registered — so a file calling
 * this in teardown would notify a tree that Testing Library had not yet unmounted, and React reports
 * that as an update outside `act` on every such case. Unmounting here removes the listeners before the
 * notification rather than requiring every file to remember the ordering. The unmount is idempotent, so
 * calling this in setup as well is harmless.
 * @returns {void} Nothing; nothing is rendered, no session is held and no bearer is attached.
 */
export function endAnySession(): void {
  cleanup();
  resetAuthSession();
  setAccessToken(null);
}
