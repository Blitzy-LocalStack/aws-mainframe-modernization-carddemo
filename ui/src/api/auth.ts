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

import { getApiClient } from './client';
import { requestPath } from './types';
import type { ContractOperation, PageDirection, PageResponse } from './types';

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

/**
 * The two user types the baseline admits, and the two Cognito groups they map to.
 *
 * Assumptions: exactly two members, 'A' and 'U', transcribed from `SEC-USR-TYPE` at
 * `app/cpy/CSUSR01Y.cpy` L22. The `auth.users` check constraint admits the same two, so a third value
 * is refused by the database as well as by the contract.
 */
export type UserType = 'A' | 'U';

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
export interface SignOnRequest {
  readonly userId: string;
  readonly password: string;
}

/**
 * A completed sign-on, carrying the token set the SPA presents on every later request.
 *
 * Assumptions: `refreshToken` is nullable because the identity provider omits it on a renewal, which
 * is the flow that consumed the previous one. A caller that stored null over a held refresh token
 * would sign the user out at the next expiry, so a null must be treated as "keep what you have"
 * rather than as "the token was revoked".
 */
export interface SignOnTokens {
  readonly outcome: 'AUTHENTICATED';
  readonly userId: string;
  readonly accessToken: string;
  readonly idToken: string;
  readonly refreshToken: string | null;
  readonly tokenType: string;
  readonly expiresIn: number;
}

/**
 * A sign-on the provider will not complete until the credential is changed.
 *
 * Assumptions: `session` is an opaque continuation value and is passed back unread. It is a
 * credential-equivalent for the length of the exchange, which is why it travels in a body on the way
 * back as well as on the way out.
 */
export interface SignOnChallenge {
  readonly outcome: 'CHALLENGE';
  readonly challengeName: 'NEW_PASSWORD_REQUIRED';
  readonly session: string;
  readonly userId: string;
}

/**
 * Which of the two sign-on outcomes occurred.
 *
 * Assumptions: the union is discriminated on `outcome`, which the contract declares as a constant on
 * each member and names as the discriminator property. Deriving the branch from the presence of
 * `accessToken` instead would work today and would break silently the moment a third outcome carried
 * one, whereas an unhandled constant is a compile error.
 */
export type SignOnResult = SignOnTokens | SignOnChallenge;

/** The identifier and refresh token a renewal submits. */
export interface TokenRefreshRequest {
  readonly userId: string;
  readonly refreshToken: string;
}

/** The identifier, continuation session and replacement credential a challenge answer submits. */
export interface SignOnChallengeRequest {
  readonly userId: string;
  readonly session: string;
  readonly newPassword: string;
}

/**
 * One row of the user browse.
 *
 * Assumptions: four members and no credential among them. The baseline record carries an
 * eight-character plaintext password at `app/cpy/CSUSR01Y.cpy` L21; the migrated system does not carry
 * that field at all, so there is nothing here to omit rather than a member deliberately withheld.
 */
export interface UserSummary {
  readonly userId: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly userType: UserType;
}

/**
 * One user as the administration screens render it.
 *
 * Assumptions: `cognitoSub` is an OUTPUT and never an input. It is minted by the identity provider
 * when the account is provisioned, so a creation request that supplied one would be asserting an
 * identity it cannot have created -- which is why {@link CreateUserRequest} below declares four members
 * and this shape declares five.
 */
export interface UserResponse extends UserSummary {
  readonly cognitoSub: string;
}

/**
 * The fields a user creation accepts.
 *
 * Assumptions: FOUR members. No credential is among them, because this service creates none: the
 * identity provider is asked to provision the account and it mints the initial credential itself. No
 * subject is among them either, for the reason recorded on {@link UserResponse}.
 */
export interface CreateUserRequest {
  readonly firstName: string;
  readonly lastName: string;
  readonly userId: string;
  readonly userType: UserType;
}

/**
 * The fields a user update accepts.
 *
 * Assumptions: three members, and the identifier is not one of them. It addresses the row from the
 * target, so admitting it in the body as well would create a request whose two halves could disagree
 * about which user is being changed.
 */
export interface UpdateUserRequest {
  readonly firstName: string;
  readonly lastName: string;
  readonly userType: UserType;
}

/** Criteria the user browse is read with. */
export interface UserListQuery {
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

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
 * @param {CreateUserRequest} request - The four fields a creation accepts.
 * @returns {Promise<UserResponse>} The created user, carrying the subject the provider minted.
 * @throws {Error} If the request fails, including HTTP 409 when the identifier is already taken.
 */
export async function createUser(request: CreateUserRequest): Promise<UserResponse> {
  const response = await getApiClient().post<UserResponse>(requestPath(CREATE_USER), request);
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
