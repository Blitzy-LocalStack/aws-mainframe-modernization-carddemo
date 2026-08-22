/**
 * @file Behavioural tests for the auth client in `ui/src/api/auth.ts`.
 *
 * Purpose
 * -------
 * Assert what each of the nine published auth operations DOES: which target it addresses, which method
 * it uses, what it puts in the request, which of the two sign-on outcomes it reads back, and which
 * combinations of arguments it refuses before dispatching at all.
 *
 * Refactoring Rationale: this module had no behavioural test. `contracts.test.ts` compared its operation
 * manifest with `auth-api.yaml` and nothing compared its REQUESTS with anything -- so a path assembled
 * from the wrong operation, a body member spelled differently from the contract, or a guard that dropped
 * a caller's argument would all have passed the build. The lone-direction refusal below is the case that
 * fails if that guard is removed again.
 *
 * Assumptions: every case dispatches through the real client and its interceptors, answered by
 * `ui/src/test/apiHarness.ts`, so these assertions measure request construction rather than a mock's
 * bookkeeping. Nothing here reaches a service.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  answerSignOnChallenge,
  createUser,
  deleteUser,
  getUser,
  listUsers,
  refreshTokens,
  signOn,
  signOut,
  updateUser,
} from './auth';
import { setAccessToken } from './client';
import {
  HARNESS_CORRELATION_HEADER,
  HTTP_CREATED,
  HTTP_NO_CONTENT,
  answerWith,
  dispatchedRequests,
  installApiHarness,
  onlyRequest,
  pageOf,
  removeApiHarness,
} from '../test/apiHarness';

/** One user row in the shape the contract's summary schema publishes. */
const USER_ROW = {
  userId: 'ADMIN001',
  firstName: 'ADA',
  lastName: 'LOVELACE',
  userType: 'A',
} as const;

/** The authenticated sign-on body, carrying the discriminator the client reads the outcome from. */
const AUTHENTICATED_BODY = {
  outcome: 'AUTHENTICATED',
  userId: 'ADMIN001',
  accessToken: 'access-token',
  idToken: 'id-token',
  refreshToken: null,
  tokenType: 'Bearer',
  expiresIn: 3600,
} as const;

/** The challenge body the pool answers a temporary password with. */
const CHALLENGE_BODY = {
  outcome: 'CHALLENGE',
  challengeName: 'NEW_PASSWORD_REQUIRED',
  session: 'session-token',
  userId: 'ADMIN001',
} as const;

/** Asserts sign-on posts both credentials to the published sign-on target. */
async function signsOnAtThePublishedTarget(): Promise<void> {
  answerWith(AUTHENTICATED_BODY);

  const result = await signOn('ADMIN001', 'PASSWORD');

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/auth/signon');
  expect(request.body).toEqual({ userId: 'ADMIN001', password: 'PASSWORD' });
  expect(result.outcome).toBe('AUTHENTICATED');
}

/**
 * Asserts the challenge outcome is read from the discriminator rather than from a missing member.
 *
 * Assumptions: the two outcomes are told apart by the `outcome` VALUE, which is the property the contract
 * publishes as a `const` on each arm. A client branching on the presence of `accessToken` would read a
 * challenge as a malformed success, which is the reading this case rules out.
 */
async function readsTheChallengeOutcomeFromItsDiscriminator(): Promise<void> {
  answerWith(CHALLENGE_BODY);

  const result = await signOn('ADMIN001', 'TEMPORARY');

  expect(result.outcome).toBe('CHALLENGE');
  if (result.outcome !== 'CHALLENGE') {
    throw new Error('the challenge body must be read as a challenge');
  }
  expect(result.session).toBe('session-token');
  expect(result.challengeName).toBe('NEW_PASSWORD_REQUIRED');
}

/** Asserts a token renewal posts the identifier and the refresh token to the renewal target. */
async function renewsTokensAtTheRenewalTarget(): Promise<void> {
  answerWith(AUTHENTICATED_BODY);

  await refreshTokens('ADMIN001', 'refresh-token');

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/auth/refresh');
  expect(request.body).toEqual({ userId: 'ADMIN001', refreshToken: 'refresh-token' });
}

/**
 * Asserts a sign-out posts the token alone to the revocation target and reads nothing back.
 *
 * Assumptions: the body is compared for EQUALITY rather than for containing the token, because the
 * absence of a `userId` is part of the request shape: the provider's revocation accepts no user name, and
 * a client that sent one would be describing a request the contract does not declare.
 *
 * Assumptions: a 204 with no body is queued, which is what the operation answers for a token it revoked
 * AND for one the provider declines to accept. The client returns nothing in either case, so there is no
 * outcome for a caller to branch on -- which this case pins by resolving to `undefined`.
 */
async function revokesTheTokenAtTheRevocationTarget(): Promise<void> {
  answerWith(undefined, HTTP_NO_CONTENT);

  const answer = await signOut('refresh-token');

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/auth/signout');
  expect(request.body).toEqual({ refreshToken: 'refresh-token' });
  expect(answer).toBeUndefined();
}

/**
 * Asserts the four session exchanges are dispatched with no bearer, and the roster calls with one.
 *
 * Assumptions: this is asserted as a PARTITION across both sets rather than on the exchanges alone,
 * because the property is a boundary: suppressing the header everywhere would satisfy an exchanges-only
 * assertion while withdrawing the credential the five administrative operations require. The sign-out is
 * the sharpest member of the open set -- a caller ending an abandoned session is the caller most likely
 * to hold an access token the resource server will refuse -- so a regression that reinstated the header
 * would break exactly the operation that most needs it.
 */
async function suppressesTheStoredBearerOnEverySessionExchange(): Promise<void> {
  // Assumptions: ⚠️ Refactoring Rationale: the stale bearer is installed through `setAccessToken`,
  //   where this case used to write it into `sessionStorage` under `carddemo.access-token`. The bearer
  //   is now held in memory by `ui/src/api/client.ts` and that key no longer exists, so the public
  //   setter is the only route to it -- and it is the route production uses, which makes the arranged
  //   state the real state rather than one assembled beside it.
  setAccessToken('stale-bearer');
  answerWith(AUTHENTICATED_BODY);
  answerWith(AUTHENTICATED_BODY);
  answerWith(AUTHENTICATED_BODY);
  answerWith(undefined, HTTP_NO_CONTENT);
  answerWith(pageOf([USER_ROW]));

  await signOn('ADMIN001', 'PASSWORD');
  await refreshTokens('ADMIN001', 'refresh-token');
  await answerSignOnChallenge('ADMIN001', 'session-token', 'NEWPASSWORD1!');
  await signOut('refresh-token');
  await listUsers();

  const dispatched = dispatchedRequests();
  expect(dispatched).toHaveLength(5);
  for (const ordinal of [0, 1, 2, 3]) {
    expect(
      dispatched[ordinal]?.headers.authorization,
      'a session exchange must not carry the stored bearer',
    ).toBeUndefined();
  }
  expect(dispatched[4]?.headers.authorization, 'a roster call must carry the stored bearer').toBe(
    'Bearer stale-bearer',
  );
}

/** Asserts answering the challenge posts all three members to the challenge target. */
async function answersTheChallengeAtItsOwnTarget(): Promise<void> {
  answerWith(AUTHENTICATED_BODY);

  await answerSignOnChallenge('ADMIN001', 'session-token', 'NEWPASSWORD1!');

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/auth/challenge');
  expect(request.body).toEqual({
    userId: 'ADMIN001',
    session: 'session-token',
    newPassword: 'NEWPASSWORD1!',
  });
}

/**
 * Asserts the opening page of the user browse carries no paging parameter at all.
 *
 * Assumptions: the ABSENCE of both members is asserted, not just of the direction. The service reads a
 * request with neither as the opening page, so sending either alone would describe a different request.
 */
async function readsTheOpeningUserPageWithNoPagingParameter(): Promise<void> {
  answerWith(pageOf([USER_ROW]));

  await listUsers();

  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/auth/users');
  expect(request.params).toEqual({});
}

/**
 * Removes the harness and discards any bearer one case installed.
 *
 * Assumptions: ⚠️ the bearer is discarded explicitly, and it did not used to need to be. It lived in
 * `sessionStorage`, which the environment cleared between files; it now lives in a module variable that
 * outlives every case in this file, so one case's stale bearer would otherwise attach itself to every
 * request the cases after it dispatched.
 * @returns {void} Nothing; the harness is removed and no bearer is held.
 */
function discardTheHarnessAndTheBearer(): void {
  removeApiHarness();
  setAccessToken(null);
}

/** Asserts a supplied cursor travels with the direction the caller asked for. */
async function sendsTheCursorWithTheDirectionAsked(): Promise<void> {
  answerWith(pageOf([USER_ROW]));

  await listUsers({ cursor: 'opaque-token', direction: 'previous' });

  expect(onlyRequest().params).toEqual({ cursor: 'opaque-token', direction: 'previous' });
}

/** Asserts a cursor supplied without a direction is read forward, which the contract defaults to. */
async function defaultsTheDirectionToForwardForACursorAlone(): Promise<void> {
  answerWith(pageOf([USER_ROW]));

  await listUsers({ cursor: 'opaque-token' });

  expect(onlyRequest().params).toEqual({ cursor: 'opaque-token', direction: 'next' });
}

/**
 * Asserts a direction supplied without a cursor is refused locally and never dispatched.
 *
 * Assumptions: BOTH halves are asserted -- that the call rejects, and that nothing reached the transport.
 * Asserting only the rejection would pass against a client that sent the refused pair and let the service
 * answer 400, which is the round trip this guard exists to remove.
 */
async function refusesADirectionWithNoCursor(): Promise<void> {
  await expect(listUsers({ direction: 'previous' })).rejects.toThrow(RangeError);
  await expect(listUsers({ direction: 'previous' })).rejects.toThrow(/cannot be/u);
  expect(dispatchedRequests(), 'no request may be dispatched for a refused pair').toHaveLength(0);
}

/**
 * Asserts an opening position travels as the one query member the service positions on.
 *
 * Assumptions: the asserted params are the WHOLE object rather than one member of it, because the
 * property under test is that the position travels ALONE on an opening read. A direction alongside it
 * would be the pair the contract refuses, and a cursor alongside it would be the pair refused below.
 */
async function sendsTheOpeningPositionAsTheOnlyMember(): Promise<void> {
  answerWith(pageOf([USER_ROW]));

  await listUsers({ startUserId: 'USER0011' });

  const request = onlyRequest();
  expect(request.method).toBe('get');
  expect(request.url).toBe('/auth/users');
  expect(request.params).toEqual({ startUserId: 'USER0011' });
}

/**
 * Asserts an EMPTY opening position is sent rather than dropped.
 *
 * Assumptions: the contract admits the empty form and gives it the same meaning as omission -- the
 * reference seeks on `LOW-VALUES` when its search field is blank, at `app/cbl/COUSR00C.cbl` L219 -- so
 * sending it is answerable. It is asserted because dropping it would make an explicit "read from the
 * start" indistinguishable from a caller that never named a position, in a replayed request.
 */
async function sendsAnEmptyOpeningPositionRatherThanDroppingIt(): Promise<void> {
  answerWith(pageOf([USER_ROW]));

  await listUsers({ startUserId: '' });

  expect(onlyRequest().params).toEqual({ startUserId: '' });
}

/**
 * Asserts an opening position and a cursor are refused locally and never dispatched.
 *
 * Assumptions: BOTH halves are asserted for the reason the direction case above records -- asserting
 * only the rejection would pass against a client that sent the refused pair and let the service answer
 * 400 naming both members, which is the round trip this guard removes.
 */
async function refusesAnOpeningPositionWithACursor(): Promise<void> {
  await expect(listUsers({ startUserId: 'USER0011', cursor: 'opaque-token' })).rejects.toThrow(
    RangeError,
  );
  await expect(listUsers({ startUserId: 'USER0011', cursor: 'opaque-token' })).rejects.toThrow(
    /not both/u,
  );
  expect(dispatchedRequests(), 'no request may be dispatched for a refused pair').toHaveLength(0);
}

/** Asserts creating a user posts the request body unchanged and reads the created body back. */
async function createsAUserAtTheCollectionTarget(): Promise<void> {
  answerWith({ ...USER_ROW, cognitoSub: 'sub', credentialSecretName: 'secret' }, HTTP_CREATED);

  const created = await createUser({
    userId: 'ADMIN001',
    firstName: 'ADA',
    lastName: 'LOVELACE',
    userType: 'A',
  });

  const request = onlyRequest();
  expect(request.method).toBe('post');
  expect(request.url).toBe('/auth/users');
  expect(request.body).toEqual({
    userId: 'ADMIN001',
    firstName: 'ADA',
    lastName: 'LOVELACE',
    userType: 'A',
  });
  expect(created.credentialSecretName).toBe('secret');
}

/**
 * Asserts a member read addresses the identifier as a path segment and percent-encodes it.
 *
 * Assumptions: the encoded case is asserted alongside the ordinary one, because an identifier is
 * user-entered and a value carrying a slash would otherwise read as two path segments and reach a
 * different operation entirely.
 */
async function addressesOneUserByPathSegment(): Promise<void> {
  answerWith(USER_ROW);
  await getUser('ADMIN001');
  expect(onlyRequest().url).toBe('/auth/users/ADMIN001');

  installApiHarness();
  answerWith(USER_ROW);
  await getUser('A/B');
  expect(onlyRequest().url).toBe('/auth/users/A%2FB');
}

/** Asserts an update puts the body to the member target and takes the identifier from the path. */
async function updatesOneUserAtItsMemberTarget(): Promise<void> {
  answerWith(USER_ROW);

  await updateUser('ADMIN001', { firstName: 'ADA', lastName: 'BYRON', userType: 'U' });

  const request = onlyRequest();
  expect(request.method).toBe('put');
  expect(request.url).toBe('/auth/users/ADMIN001');
  expect(request.body).toEqual({ firstName: 'ADA', lastName: 'BYRON', userType: 'U' });
  // Assumptions: the identifier is asserted ABSENT from the body, because the contract takes it from the
  //   path and admitting it in both would allow a request whose two halves named different users.
  expect(request.body).not.toHaveProperty('userId');
}

/**
 * Asserts a delete carries the explicit confirmation the contract requires.
 *
 * Assumptions: the confirmation is a QUERY parameter and its literal spelling is asserted, because the
 * baseline's delete screen confirms by re-keying and the migrated form of that confirmation is this
 * parameter -- a delete that omitted it would be refused, and one that spelled it differently would be
 * accepted as unconfirmed.
 */
async function confirmsADeleteExplicitly(): Promise<void> {
  answerWith(undefined, HTTP_NO_CONTENT);

  await deleteUser('ADMIN001', true);

  const request = onlyRequest();
  expect(request.method).toBe('delete');
  expect(request.url).toBe('/auth/users/ADMIN001');
  expect(request.params).toEqual({ confirmed: true });
}

/**
 * Asserts every dispatch carries a correlation identifier, which the client's interceptor adds.
 *
 * Assumptions: this is asserted once rather than on every case, and it is asserted through the harness's
 * recorded HEADERS rather than by inspecting the interceptor -- so it measures what a request carries.
 */
async function carriesACorrelationIdentifierOnEveryRequest(): Promise<void> {
  answerWith(AUTHENTICATED_BODY);

  await signOn('ADMIN001', 'PASSWORD');

  const header = onlyRequest().headers[HARNESS_CORRELATION_HEADER.toLowerCase()];
  expect(header, 'the client must stamp a correlation identifier on every request').toBeDefined();
  expect(header).not.toBe('');
}

/**
 * Registers every auth client case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function authClientBehaviour(): void {
  beforeEach(installApiHarness);
  afterEach(discardTheHarnessAndTheBearer);
  it('signs on at the published target', signsOnAtThePublishedTarget);
  it(
    'reads the challenge outcome from its discriminator',
    readsTheChallengeOutcomeFromItsDiscriminator,
  );
  it('renews tokens at the renewal target', renewsTokensAtTheRenewalTarget);
  it('answers the challenge at its own target', answersTheChallengeAtItsOwnTarget);
  it('revokes the token at the revocation target', revokesTheTokenAtTheRevocationTarget);
  it(
    'suppresses the stored bearer on every session exchange',
    suppressesTheStoredBearerOnEverySessionExchange,
  );
  it(
    'reads the opening user page with no paging parameter',
    readsTheOpeningUserPageWithNoPagingParameter,
  );
  it('sends the cursor with the direction asked', sendsTheCursorWithTheDirectionAsked);
  it(
    'defaults the direction to forward for a cursor alone',
    defaultsTheDirectionToForwardForACursorAlone,
  );
  it('refuses a direction with no cursor', refusesADirectionWithNoCursor);
  it('sends the opening position as the only member', sendsTheOpeningPositionAsTheOnlyMember);
  it(
    'sends an empty opening position rather than dropping it',
    sendsAnEmptyOpeningPositionRatherThanDroppingIt,
  );
  it('refuses an opening position with a cursor', refusesAnOpeningPositionWithACursor);
  it('creates a user at the collection target', createsAUserAtTheCollectionTarget);
  it('addresses one user by path segment', addressesOneUserByPathSegment);
  it('updates one user at its member target', updatesOneUserAtItsMemberTarget);
  it('confirms a delete explicitly', confirmsADeleteExplicitly);
  it(
    'carries a correlation identifier on every request',
    carriesACorrelationIdentifierOnEveryRequest,
  );
}

describe('auth client behaviour', authClientBehaviour);
