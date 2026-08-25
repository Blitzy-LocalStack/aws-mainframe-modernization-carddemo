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
import { getApiClient, setAccessToken } from './client';
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
 * Asserts a value wider than the field it is stored in is refused locally and never dispatched.
 *
 * Purpose: this is the measured defect. `POST /auth/users` answered `201` in 7 ms for a body whose
 * `firstName` held 10,000 characters against a copybook width of 20 -- 10,061 bytes dispatched for a
 * name that cannot be stored -- because the create screen's `maxLength` attribute bounds TYPING and
 * this request path bounded nothing. A value set programmatically, pasted, or restored from a draft
 * reaches the body without passing the input that was supposed to have stopped it.
 *
 * Assumptions: THREE properties are asserted, and each fails a different way of getting this wrong.
 * That the call rejects -- a client that dispatched and let the service answer 400 would pass on that
 * alone. That NOTHING reached the transport -- which is the whole point of refusing before dispatch.
 * And that the message names the member and the two lengths and NOT the value, because this guard also
 * runs on credentials, primary account numbers and national identifiers, and an exception message is
 * copied verbatim into consoles and bug reports.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function refusesANameWiderThanTheFieldItIsStoredIn(): Promise<void> {
  const overLong = 'A'.repeat(10_000);

  const raised: unknown = await createUser({
    userId: 'ADMIN001',
    firstName: overLong,
    lastName: 'LOVELACE',
    userType: 'A',
  }).catch(
    /**
     * Yields the refusal as a value, so the case can assert on what it was.
     * @param {unknown} error - Whatever the request rejected with.
     * @returns {unknown} That same refusal.
     */
    function yieldTheRefusal(error: unknown): unknown {
      return error;
    },
  );

  expect(raised).toBeInstanceOf(RangeError);
  const reported = raised instanceof Error ? raised.message : '';
  expect(reported).toContain('CreateUserRequest.firstName');
  expect(reported).toContain('10000');
  expect(reported).toContain('20');
  expect(reported, 'a refusal must not reproduce the value it refused').not.toContain(overLong);
  expect(dispatchedRequests(), 'nothing may be dispatched for a refused body').toHaveLength(0);
}

/**
 * Asserts a value AT its published width is admitted, so the guard bounds rather than narrows.
 *
 * Assumptions: this case is what stops the refusal above from being satisfied by a guard that is one
 * character too strict. Twenty characters is exactly `SEC-USR-FNAME PIC X(20)`, so a client refusing it
 * would reject the longest name the field can hold -- a defect no operator could work around, and one
 * that an over-length case alone would never detect.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function admitsANameFillingTheFieldExactly(): Promise<void> {
  const exactly20 = 'A'.repeat(20);
  answerWith({ ...USER_ROW, cognitoSub: 'sub', credentialSecretName: 'secret' }, HTTP_CREATED);

  await createUser({
    userId: 'ADMIN001',
    firstName: exactly20,
    lastName: 'LOVELACE',
    userType: 'A',
  });

  expect(onlyRequest().body).toEqual({
    userId: 'ADMIN001',
    firstName: exactly20,
    lastName: 'LOVELACE',
    userType: 'A',
  });
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
 * Asserts two concurrent deletions of the SAME row issue one request and both settle.
 *
 * Purpose: ⚠️ duplicate protection was present on the safe verb and absent on the dangerous one -- the
 * report submission carries an `Idempotency-Key` header while this deletion carried nothing at all. The
 * header cannot be the remedy here: it is declared in exactly one of the seven published contracts, on
 * `submitTransactionReport` alone, so sending it on a deletion would be a header no service reads, and a
 * key the server ignores is worse than none because the request then looks protected. What a client can
 * close on its own is the CONCURRENT duplicate -- a double-click, a repeated key, a second confirmation
 * -- which is what this asserts.
 *
 * Assumptions: BOTH properties are asserted. One request reached the transport, and both callers
 * received the outcome: a guard that refused the second caller instead of answering it would leave a
 * screen holding a rejection for a deletion that in fact succeeded.
 *
 * Assumptions: exactly ONE answer is queued, so a second dispatch could not have been answered at all --
 * which is a second, independent way for this case to fail if the guard stops working.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function collapsesTwoConcurrentDeletesOfOneRow(): Promise<void> {
  answerWith(undefined, HTTP_NO_CONTENT);

  const [first, second] = await Promise.all([
    deleteUser('ADMIN001', true),
    deleteUser('ADMIN001', true),
  ]);

  expect(dispatchedRequests(), 'one row, one deletion').toHaveLength(1);
  expect(first).toBeUndefined();
  expect(second).toBeUndefined();
}

/**
 * Asserts two concurrent deletions of DIFFERENT rows both go out.
 *
 * Assumptions: this is what stops the guard from being a global mutex on the verb. The row is part of
 * the key, so deleting two users at once is two deletions -- and a guard keyed on the method alone would
 * silently drop the second, leaving a row the operator was told had gone.
 * @returns {Promise<void>} Nothing; the assertion is the outcome.
 */
async function keepsTwoDeletesOfDifferentRowsApart(): Promise<void> {
  answerWith(undefined, HTTP_NO_CONTENT);
  answerWith(undefined, HTTP_NO_CONTENT);

  await Promise.all([deleteUser('ADMIN001', true), deleteUser('ADMIN002', true)]);

  expect(dispatchedRequests()).toHaveLength(2);
  expect(
    dispatchedRequests().map(
      /**
       * Reads one recorded request's target.
       * @param {(ReturnType<typeof dispatchedRequests>)[number]} request - One recorded request.
       * @returns {string} The target that request was dispatched to.
       */
      function targetOf(request: ReturnType<typeof dispatchedRequests>[number]): string {
        return request.url;
      },
    ),
  ).toEqual(['/auth/users/ADMIN001', '/auth/users/ADMIN002']);
}

/**
 * Asserts a deletion that failed can be retried at once.
 *
 * Assumptions: the guard releases on SETTLEMENT and not on success, which matters because the opposite
 * would leave a row undeletable until a reload -- a worse outcome than allowing a deliberate retry, and
 * one an operator could not work around. The retry is asserted as a second dispatch rather than as a
 * resolved promise, since a guard still holding the failed attempt would answer from it without sending
 * anything.
 * @returns {Promise<void>} Nothing; the assertions are the outcome.
 */
async function allowsARetryAfterAFailedDelete(): Promise<void> {
  const client = getApiClient();
  const harnessAdapter = client.defaults.adapter;
  // Assumptions: asserted rather than defaulted, because this case depends on RESTORING the harness
  //   adapter after the rejecting one -- a harness that installed none would leave the second attempt
  //   reaching real transport, and the count below would then be asserting nothing about a retry.
  if (harnessAdapter === undefined) {
    throw new Error(
      'the api harness installs an adapter; this case restores it after failing once',
    );
  }
  let attempts = 0;
  // Assumptions: the first attempt is made to REJECT by an adapter installed here, because the shared
  //   harness always resolves -- it answers a queued status without running Axios's own status check --
  //   so a queued 500 would resolve and this case would assert nothing about a failed deletion. The
  //   harness adapter is restored immediately afterwards so the count below is still its recording.
  /**
   * Fails every dispatch, standing for a transport that cannot reach the service.
   * @returns {Promise<never>} A rejection, always.
   */
  client.defaults.adapter = async function failEveryDispatch(): Promise<never> {
    attempts += 1;
    return Promise.reject(new Error('the deletion failed'));
  };

  await expect(deleteUser('ADMIN001', true)).rejects.toThrow(Error);

  client.defaults.adapter = harnessAdapter;
  answerWith(undefined, HTTP_NO_CONTENT);
  await deleteUser('ADMIN001', true);

  expect(attempts, 'the failed attempt reached the transport').toBe(1);
  expect(dispatchedRequests(), 'the retry reached the transport too').toHaveLength(1);
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
  it(
    'refuses a name wider than the field it is stored in',
    refusesANameWiderThanTheFieldItIsStoredIn,
  );
  it('admits a name filling the field exactly', admitsANameFillingTheFieldExactly);
  it('addresses one user by path segment', addressesOneUserByPathSegment);
  it('updates one user at its member target', updatesOneUserAtItsMemberTarget);
  it('confirms a delete explicitly', confirmsADeleteExplicitly);
  it('collapses two concurrent deletes of one row', collapsesTwoConcurrentDeletesOfOneRow);
  it('keeps two deletes of different rows apart', keepsTwoDeletesOfDifferentRowsApart);
  it('allows a retry after a failed delete', allowsARetryAfterAFailedDelete);
  it(
    'carries a correlation identifier on every request',
    carriesACorrelationIdentifierOnEveryRequest,
  );
}

describe('auth client behaviour', authClientBehaviour);
