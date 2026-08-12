/**
 * @file Typed client for the authentication and user-administration bounded context, written against
 * `services/auth-service/src/main/resources/openapi/auth-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the eight operations that contract publishes: the three token exchanges replacing the
 * sign-on program `app/cbl/COSGN00C.cbl`, and the five user-administration operations replacing
 * `app/cbl/COUSR00C.cbl` through `COUSR03C.cbl`. Every target is derived from the operation manifest
 * below rather than written as a literal, for the reason recorded in `ui/src/api/types.ts`.
 *
 * Credentials never reach a target
 * --------------------------------
 * Assumptions: every credential this module transmits -- a password, a new password, a refresh token,
 * a challenge session -- travels in a request BODY, and never in a path or a query string. That is not
 * a preference: a target is written in full into the edge access log before any application code runs
 * and is retained by the browser's history, and neither store is reachable by anything this module
 * could add. The contract declares all three token exchanges as POST with a body for exactly this
 * reason, and a convenience overload accepting a credential as a query parameter must never be added
 * here.
 *
 * What this module does not do
 * ---------------------------
 * Alternatives Considered: storing the returned access token from inside `signOn`, so a caller could
 * not forget to. Rejected because token storage is `ui/src/api/client.ts`'s concern -- it owns the
 * session-storage key and the request interceptor that reads it -- and a second writer of that key
 * would make the sign-out path ambiguous about which module had to be told. `signOn` returns the token
 * set and the caller passes it to `setAccessToken`, so there is one writer.
 */

import { getApiClient, requestPath } from './client';
import type {
  ContractOperation,
  CreateUserRequest,
  PageResponse,
  SignOnChallengeRequest,
  SignOnRequest,
  SignOnResult,
  SignOnTokens,
  TokenRefreshRequest,
  UpdateUserRequest,
  UserListQuery,
  UserResponse,
  UserSummary,
  CreatedUserResponse,
} from './types';

/*
 * WHY : Refactoring Rationale: the sign-on and user wire shapes are RE-EXPORTED from ./types rather
 *       than declared here, so this module's public surface is unchanged for every screen and hook
 *       that imports from '../../api/auth' while each shape has a single definition beside the other
 *       contracts' shapes.
 */
export type {
  UserType,
  SignOnRequest,
  SignOnTokens,
  SignOnChallenge,
  SignOnResult,
  TokenRefreshRequest,
  SignOnChallengeRequest,
  UserSummary,
  UserResponse,
  CreatedUserResponse,
  CreateUserRequest,
  UpdateUserRequest,
  UserListQuery,
} from './types';

const SIGN_ON: ContractOperation = {
  method: 'POST',
  path: '/api/v1/auth/signon',
  operationId: 'signOn',
};

const REFRESH_TOKENS: ContractOperation = {
  method: 'POST',
  path: '/api/v1/auth/refresh',
  operationId: 'refreshTokens',
};

const ANSWER_SIGN_ON_CHALLENGE: ContractOperation = {
  method: 'POST',
  path: '/api/v1/auth/challenge',
  operationId: 'answerSignOnChallenge',
};

const LIST_USERS: ContractOperation = {
  method: 'GET',
  path: '/api/v1/auth/users',
  operationId: 'listUsers',
};

const CREATE_USER: ContractOperation = {
  method: 'POST',
  path: '/api/v1/auth/users',
  operationId: 'createUser',
};

const GET_USER: ContractOperation = {
  method: 'GET',
  path: '/api/v1/auth/users/{userId}',
  operationId: 'getUser',
};

const UPDATE_USER: ContractOperation = {
  method: 'PUT',
  path: '/api/v1/auth/users/{userId}',
  operationId: 'updateUser',
};

const DELETE_USER: ContractOperation = {
  method: 'DELETE',
  path: '/api/v1/auth/users/{userId}',
  operationId: 'deleteUser',
};

/**
 * Every operation `auth-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`.
 */
export const AUTH_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  SIGN_ON,
  REFRESH_TOKENS,
  ANSWER_SIGN_ON_CHALLENGE,
  LIST_USERS,
  CREATE_USER,
  GET_USER,
  UPDATE_USER,
  DELETE_USER,
];

/** Successful sign-on outcome discriminator, as the contract declares it. */
export const SIGN_ON_AUTHENTICATED = 'AUTHENTICATED';

/** Challenge sign-on outcome discriminator, as the contract declares it. */
export const SIGN_ON_CHALLENGE = 'CHALLENGE';

/**
 * Maximum user-identifier length the contract accepts, and the 3270 field width it preserves.
 *
 * Assumptions: eight, from `SEC-USR-ID PIC X(08)` at `app/cpy/CSUSR01Y.cpy` L18. It is exported because
 * the sign-on screen sets it as the input's `maxLength`, which is how the terminal's own field width
 * survives into the browser rather than being re-derived per screen.
 */
export const USER_ID_MAX_LENGTH = 8;

/**
 * Maximum password length the contract accepts.
 *
 * Assumptions: this is NOT the baseline's eight-character field. The plaintext credential the baseline
 * stored is not carried forward at all, so the bound here is the identity provider's rather than the
 * copybook's, and a longer password is an improvement the migration deliberately admits.
 */
export const PASSWORD_MAX_LENGTH = 256;

/** The credentials a sign-on submits. */

/** The identifier and refresh token a renewal submits. */

/** The identifier, continuation session and replacement credential a challenge answer submits. */

/** Criteria the user browse is read with. */

/**
 * Exchanges a user identifier and password for a token set.
 *
 * Assumptions: the three verbatim sign-on messages the baseline raises -- for a wrong password, an
 * unknown user and an unverifiable user -- arrive as the `message` of a problem document on a 401,
 * not as a member of a success body. A caller renders that message unchanged under transformation
 * rule T8 and must not substitute its own wording.
 * @param {string} userId - Operator identifier, at most `USER_ID_MAX_LENGTH` characters.
 * @param {string} password - The password as typed.
 * @returns {Promise<SignOnResult>} The token set when the provider authenticated the caller, or the
 *   challenge it requires to be answered first.
 * @throws {Error} If the request fails, including HTTP 401 for a refused credential.
 */
export async function signOn(userId: string, password: string): Promise<SignOnResult> {
  const request: SignOnRequest = { userId, password };
  const response = await getApiClient().post<SignOnResult>(requestPath(SIGN_ON), request);

  return response.data;
}

/**
 * Renews a token set from a held refresh token.
 * @param {string} userId - The identifier the tokens belong to.
 * @param {string} refreshToken - The refresh token last issued.
 * @returns {Promise<SignOnTokens>} A fresh token set. Its `refreshToken` may be null, in which case
 *   the held one remains current.
 * @throws {Error} If the request fails, including HTTP 401 when the refresh token has been revoked or
 *   has expired.
 */
export async function refreshTokens(userId: string, refreshToken: string): Promise<SignOnTokens> {
  const request: TokenRefreshRequest = { userId, refreshToken };
  const response = await getApiClient().post<SignOnTokens>(requestPath(REFRESH_TOKENS), request);
  return response.data;
}

/**
 * Completes a sign-on that required a new credential.
 * @param {string} userId - The identifier the challenge was raised for.
 * @param {string} session - The opaque session handle from the challenge.
 * @param {string} newPassword - The replacement credential.
 * @returns {Promise<SignOnTokens>} The token set issued once the credential was accepted.
 * @throws {Error} If the request fails, including HTTP 400 for a credential the provider's policy
 *   rejects and 401 for an expired session.
 */
export async function answerSignOnChallenge(
  userId: string,
  session: string,
  newPassword: string,
): Promise<SignOnTokens> {
  const request: SignOnChallengeRequest = { userId, session, newPassword };
  const response = await getApiClient().post<SignOnTokens>(
    requestPath(ANSWER_SIGN_ON_CHALLENGE),
    request,
  );
  return response.data;
}

/**
 * Lists users by key.
 * @param {UserListQuery} [query] - Optional sealed cursor and the direction it was issued for. Omit it
 *   for the first page.
 * @returns {Promise<PageResponse<UserSummary>>} One bounded page of user rows.
 * @throws {Error} If the request fails, including HTTP 403 for a caller outside the administrative
 *   group.
 */
export async function listUsers(query: UserListQuery = {}): Promise<PageResponse<UserSummary>> {
  const params: Record<string, string> = {};
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    params.direction = query.direction ?? 'next';
  }

  const response = await getApiClient().get<PageResponse<UserSummary>>(requestPath(LIST_USERS), {
    params: Object.keys(params).length === 0 ? undefined : params,
  });
  return response.data;
}

/**
 * Creates one user and provisions the matching identity-provider account.
 *
 * Assumptions: the resolved value names the managed-secret entry the account's one-time credential was
 * published to, and never the credential itself. A caller collects the value from that entry and presents
 * it to the person the account is for. See {@link CreatedUserResponse} for why the handover works this
 * way.
 * @param {CreateUserRequest} request - The four fields a creation accepts.
 * @returns {Promise<CreatedUserResponse>} The created user, carrying the subject the provider minted
 *   and the name of the managed-secret entry holding its one-time credential.
 * @throws {Error} If the request fails, including HTTP 409 when the identifier is already taken.
 */
export async function createUser(request: CreateUserRequest): Promise<CreatedUserResponse> {
  const response = await getApiClient().post<CreatedUserResponse>(
    requestPath(CREATE_USER),
    request,
  );
  return response.data;
}

/**
 * Retrieves one user.
 * @param {string} userId - The user's identifier, at most eight characters.
 * @returns {Promise<UserResponse>} The user.
 * @throws {Error} If the request fails, including HTTP 404 when no such user exists.
 */
export async function getUser(userId: string): Promise<UserResponse> {
  const response = await getApiClient().get<UserResponse>(requestPath(GET_USER, { userId }));
  return response.data;
}

/**
 * Updates one user's name and type.
 * @param {string} userId - The user's identifier, at most eight characters.
 * @param {UpdateUserRequest} request - The three fields an update accepts.
 * @returns {Promise<UserResponse>} The user as stored after the change.
 * @throws {Error} If the request fails, including HTTP 404 when no such user exists.
 */
export async function updateUser(
  userId: string,
  request: UpdateUserRequest,
): Promise<UserResponse> {
  const response = await getApiClient().put<UserResponse>(
    requestPath(UPDATE_USER, { userId }),
    request,
  );

  return response.data;
}

/**
 * Deletes one user.
 *
 * Assumptions: the confirmation is a REQUIRED query parameter whose only accepted value is true, and
 * this function supplies it rather than exposing it. The contract declares it as a constant, so a
 * request without it is refused with 400; making it a parameter of this function would offer a caller
 * a value that has exactly one legal setting. It stands in for the baseline's re-key-to-confirm
 * convention, which the SPA renders as a confirmation the user dismisses or accepts before this
 * function is reached at all.
 * @param {string} userId - The user's identifier, at most eight characters.
 * @returns {Promise<void>} Nothing. The operation answers 204 with no body.
 * @throws {Error} If the request fails, including HTTP 404 when no such user exists.
 */
export async function deleteUser(userId: string): Promise<void> {
  await getApiClient().delete<void>(requestPath(DELETE_USER, { userId }), {
    params: { confirmed: true },
  });
}
