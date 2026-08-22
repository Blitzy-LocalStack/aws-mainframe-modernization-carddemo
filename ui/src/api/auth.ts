/**
 * @file Typed client for the authentication and user-administration bounded context, written against
 * `services/auth-service/src/main/resources/openapi/auth-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the nine operations that contract publishes: the four session exchanges -- three that replace
 * the sign-on program `app/cbl/COSGN00C.cbl` and one, the revocation, that has no reference counterpart
 * because the baseline had nothing to revoke -- and the five user-administration operations replacing
 * `app/cbl/COUSR00C.cbl` through `COUSR03C.cbl`. Every target is derived from the operation manifest
 * below rather than written as a literal, for the reason recorded in `ui/src/api/types.ts`.
 *
 * Which requests carry a credential, and which carry an authority
 * --------------------------------------------------------------
 * Assumptions: the four session exchanges are the ONLY operations in this contract declared
 * `security: []`, and each of the five user-administration operations declares both `bearerAuth` and
 * `x-required-authority: carddemo-admin` -- so a held token is necessary but not sufficient for them.
 * This mirrors the baseline, whose sign-on program was the one program that ran before any identity
 * existed: it detected that first turn by an empty communication area, `IF EIBCALEN = 0` at
 * `app/cbl/COSGN00C.cbl` L80, and every other program in the region was reached only after it.
 * The consequence of the unauthenticated case is carried in two places, and both are needed. When no
 * token is held, `applyRequestHeaders` in `ui/src/api/client.ts` attaches NO `Authorization` header
 * rather than one reading `Bearer undefined`: a service handed a malformed credential answers 401,
 * which would report a refused token to an operator who has not yet presented one. And when a token IS
 * held, each of the four exchanges below is dispatched with `WITHOUT_STORED_SESSION`, so the header is
 * removed for exactly the operations their contract declares `security: []`.
 *
 * Refactoring Rationale: that second half was missing, and its absence was not visible at a call site.
 * All four exchanges share the one axios instance, whose interceptor attaches the stored bearer to
 * every request, so a token that had expired or been revoked travelled on the sign-on, the refresh, the
 * challenge answer and the revocation alike. The resource-server filter validates a presented credential BEFORE the
 * permit-all rule for these paths is reached, so a stale token could refuse the exchange whose whole
 * purpose is to replace it — and the failure is worst exactly when it matters most, at the moment an
 * operator returns to a tab whose session has lapsed. Suppressing per request rather than per instance
 * keeps the correlation identifier, the clamped timeout and the failure normalisation identical for
 * these four operations; the reasoning against a second instance is recorded on the flag itself.
 *
 * Nothing here may add a header, and nothing here may send a credential on the five administration
 * calls beyond the bearer token the shared interceptor attaches.
 *
 * Assumptions: every credential this module transmits -- a password, a new password, a refresh token,
 * a challenge session -- travels in a request BODY, and never in a path or a query string. That is not
 * a preference: a target is written in full into the edge access log before any application code runs
 * and is retained by the browser's history, and neither store is reachable by anything this module
 * could add. The contract declares all four session exchanges as POST with a body for exactly this
 * reason, and a convenience overload accepting a credential as a query parameter must never be added
 * here.
 *
 * How a failure arrives
 * --------------------
 * Assumptions: every rejection from this module is the normalised `ApiRequestError` that
 * `ui/src/api/client.ts` mints, never a raw transport error, so each `@throws` below names the status
 * conditions its operation can produce rather than the class. A caller renders
 * `ApiRequestError.problem.message` for the sentence and `problem.fieldErrors` for the per-field
 * marks. That array is the WHOLE field-marking mechanism on a 400: the baseline moved the error colour
 * into a field only when its validation flag was not-OK or blank AND the program was on a re-entry
 * turn -- `app/cpy/CSSETATY.cpy` L18 and L19 with `AND CDEMO-PGM-REENTER` at L20 -- and that turn
 * counter lived in the pseudo-conversational session struct the target removes, so dropping,
 * reordering or padding the array changes what a screen highlights.
 *
 * What this module does not do
 * ---------------------------
 * Alternatives Considered: storing the returned access token from inside `signOn`, so a caller could
 * not forget to. Rejected because token storage is `ui/src/api/client.ts`'s concern -- it owns the
 * session-storage key and the request interceptor that reads it -- and a second writer of that key
 * would make the sign-out path ambiguous about which module had to be told. `signOn` returns the token
 * set and the caller passes it to `setAccessToken`, so there is one writer.
 *
 * Assumptions: the same division holds in the other direction on {@link signOut}, which revokes the
 * token at the provider and CLEARS NOTHING locally. Clearing held tokens is `ui/src/hooks/useAuth.ts`'s
 * concern, and that hook discards them whether this call resolves or rejects -- a session the operator
 * has ended must not survive in the tab because the network did not cooperate. Doing the clearing here
 * as well would put two writers on the same key and would make the local half conditional on the remote
 * half succeeding, which is the failure mode that division exists to prevent.
 */

import { WITHOUT_STORED_SESSION, getApiClient, keysetPagingMembers, requestPath } from './client';
import type { AxiosRequestConfig } from 'axios';
import type {
  ContractOperation,
  CreateUserRequest,
  CreatedUserResponse,
  PageResponse,
  SignOnChallengeRequest,
  SignOnRequest,
  SignOnResult,
  SignOnTokens,
  SignOutRequest,
  TokenRefreshRequest,
  UpdateUserRequest,
  UserListQuery,
  UserResponse,
  UserSummary,
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
  SignOutRequest,
  UserSummary,
  UserResponse,
  CreatedUserResponse,
  CreateUserRequest,
  UpdateUserRequest,
  UserListQuery,
} from './types';

/**
 * Composes the request configuration a session exchange is dispatched with.
 *
 * Purpose: every one of the four session exchanges must be dispatched WITHOUT the held bearer, for the
 * reason argued at the head of this module, and each may additionally be given a signal that abandons
 * it. Composing both in one place means a new exchange cannot acquire the second and forget the first.
 *
 * Assumptions: the shared frozen configuration is SPREAD rather than mutated, because it is frozen and
 * because a mutation would change the configuration every other exchange is dispatched with. The signal
 * member is omitted entirely when none was supplied rather than set to `undefined`, so a caller that
 * passes nothing produces exactly the configuration these exchanges used before signals existed.
 *
 * Assumptions: the signal is the caller's to own. This module neither creates nor aborts one — the
 * session generation that decides when an exchange has been superseded lives in
 * `ui/src/hooks/useAuth.ts`, and a controller created here could not be tied to it.
 * @param {AbortSignal} [signal] - Signal that abandons the request when the session it belongs to is
 *   superseded. Omit it for a call no later event can invalidate.
 * @returns {Readonly<AxiosRequestConfig>} The configuration to dispatch with; never `null`.
 */
function sessionExchangeConfig(signal?: AbortSignal): Readonly<AxiosRequestConfig> {
  return signal === undefined ? WITHOUT_STORED_SESSION : { ...WITHOUT_STORED_SESSION, signal };
}

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

const SIGN_OUT: ContractOperation = {
  method: 'POST',
  path: '/api/v1/auth/signout',
  operationId: 'signOut',
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
 * both directions by `ui/src/api/contracts.test.ts`. Nine entries, because the contract publishes
 * nine: the refresh, challenge and revocation exchanges are declared operations rather than optional
 * extras, so omitting any would leave a published operation with no client and would fail that
 * comparison in the contract-to-client direction.
 */
export const AUTH_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  SIGN_ON,
  REFRESH_TOKENS,
  ANSWER_SIGN_ON_CHALLENGE,
  SIGN_OUT,
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
 * Assumptions: eight, from `05 SEC-USR-ID PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L18 and again from
 * `02 USERIDI PIC X(8).` at `app/cpy-bms/COSGN00.CPY` L72. It is exported because the sign-on screen
 * sets it as the input's `maxLength`, which is how the terminal's own field width survives into the
 * browser rather than being re-derived per screen.
 */
export const USER_ID_MAX_LENGTH = 8;

/**
 * Maximum password length the contract accepts.
 *
 * Assumptions: this is NOT the baseline's eight-character field at `app/cpy-bms/COSGN00.CPY` L78. The
 * plaintext credential the baseline stored is not carried forward at all, so the bound here is the
 * identity provider's rather than the copybook's, and a longer password is an improvement the
 * migration deliberately admits. It is exported so the sign-on input's `maxLength` states the bound a
 * submission is actually judged against.
 */
export const PASSWORD_MAX_LENGTH = 256;

// WHY : Refactoring Rationale: NO password appears on any type below except the sign-on and challenge
//       REQUESTS, and none appears on any response or on either user-administration request. The
//       baseline did all three of the things this declines. It STORED the credential in the record --
//       `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21 -- it COMPARED it directly at
//       sign-on with `IF SEC-USR-PWD = WS-USER-PWD` at `app/cbl/COSGN00C.cbl` L223, and its update
//       screen ECHOED it back to the terminal with `MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI` at
//       `app/cbl/COUSR02C.cbl` L169. The target carries no password column and no password property
//       outside these two request bodies: identity moves to a managed user pool and a new account's
//       initial credential is generated at provisioning time into a secret store, which is why
//       creation answers with the NAME of that entry and never its value. This is the documented
//       divergence D-4 and it is the one place in the migration where parity is explicitly declined
//       -- porting a plaintext credential faithfully would be indefensible -- so it is recorded here
//       rather than left for a reader to infer from an absence.
// WHY : Refactoring Rationale: no response type this module reads carries an authoritative user type,
//       and `SignOnTokens` carries none at all. The baseline kept the operator's authority in
//       communication-area storage the terminal echoed back on every screen turn --
//       `10 CDEMO-USER-TYPE PIC X(01).` at `app/cpy/COCOM01Y.cpy` L26, with
//       `88 CDEMO-USRTYP-ADMIN VALUE 'A'.` at L27 and `88 CDEMO-USRTYP-USER VALUE 'U'.` at L28 -- so
//       a client could in principle have asserted its own user type. In the target the
//       `cognito:groups` claim is signed, is converted server-side to authorities ('A' to
//       `carddemo-admin`, 'U' to `carddemo-user`), and is revalidated by every service, so the client
//       asserts nothing. The consequence is a rule for callers: the admin-or-user branch taken after
//       a sign-on reads the signed claim, never a member of a body this module returned. The
//       `userType` on `UserSummary` and `UserResponse` is the ADMINISTERED row's stored value -- the
//       datum an administrator is listing or editing -- and must never be read as the caller's own
//       authority; restoring a user type onto the sign-on response for convenience would reintroduce
//       exactly the assertion the signed claim removes.

/**
 * Exchanges a user identifier and password for a token set.
 *
 * Assumptions: TWO verbatim sentences reach a caller on a refusal, not three, and the difference is a
 * documented divergence rather than a gap. The baseline raises three -- "Wrong Password. Try again ..."
 * at `app/cbl/COSGN00C.cbl` L242 and L243, "User not found. Try again ..." at L249, and "Unable to verify
 * the User ..." at L254 -- and the service deliberately does NOT emit the second. An identifier this
 * context holds no row for, and a credential the provider refused, are answered with the SAME wrong-password
 * sentence, so the two cannot be told apart; the unverifiable sentence is the third and is unaffected.
 * Both sentences that ARE emitted arrive as the `message` of a problem document on a 401 rather than as a
 * member of a success body, and a caller renders each unchanged under transformation rule T8 without
 * substituting its own wording.
 *
 * Refactoring Rationale: this block claimed all three sentences were delivered, which contradicted the
 * merge the service performs and which the traceability register records as divergence. The merge is
 * deliberate: two distinguishable sentences let an unauthenticated caller harvest valid identifiers one
 * request at a time, which anyone able to reach the sign-on screen could do in the baseline. What is given
 * up is the diagnostic telling a caller WHICH of the identifier and the credential was at fault, and a
 * screen must therefore not offer "check your user identifier" guidance a 401 cannot support. The register
 * is `docs/architecture/cobol-to-service-traceability.md`.
 * @param {string} userId - Operator identifier, at most `USER_ID_MAX_LENGTH` characters.
 * @param {string} password - The password as typed. It is placed in the request body and is neither
 *   logged, stored, nor transformed on the way.
 * @param {AbortSignal} [signal] - Signal that abandons the exchange when the session it belongs to has
 *   been superseded — a second sign-on, or a sign-out, while this one is in flight. Omitting it leaves
 *   the request to run to completion, which a caller holding no session generation should do.
 * @returns {Promise<SignOnResult>} The token set when the provider authenticated the caller, or the
 *   challenge it requires to be answered first. Discriminate on `outcome`.
 * @throws {Error} The normalised `ApiRequestError`, whose `problem` carries the service's own
 *   sentence: 400 with `fieldErrors` keyed `userId` or `password` for a value outside its domain, and
 *   401 for a credential the provider refused. This is the one operation here that cannot answer 403,
 *   because it requires no authority to call.
 */
export async function signOn(
  userId: string,
  password: string,
  signal?: AbortSignal,
): Promise<SignOnResult> {
  const request: SignOnRequest = { userId, password };
  // Assumptions: dispatched WITHOUT the stored session, because this operation is declared
  //   `security: []` and a bearer left over from a lapsed session would be validated by the
  //   resource-server filter before the permit-all rule for this path is reached -- refusing the
  //   credential the operator is in the middle of replacing.
  const response = await getApiClient().post<SignOnResult>(
    requestPath(SIGN_ON),
    request,
    sessionExchangeConfig(signal),
  );

  return response.data;
}

/**
 * Renews a token set from a held refresh token, which the provider ROTATES in the process.
 *
 * Assumptions: rotation is enabled on the pool client, so this exchange ordinarily answers with a
 * REPLACEMENT refresh token and invalidates the one submitted. A caller that kept the submitted token
 * would be refused on its next renewal, so a non-null `refreshToken` on the answer MUST be stored over
 * the held one. The null case is a pool-side retry grace period leaving the submitted token current, and
 * a caller must then keep what it holds; the full reasoning is on `SignOnTokens` in
 * `ui/src/api/types.ts`.
 *
 * Refactoring Rationale: this block previously described the answer's `refreshToken` as one that "may be
 * null, in which case the held one remains current", stating only the tolerated case and never the
 * ordinary one. A caller written from that sentence would discard every rotated token and be signed out
 * on its second renewal.
 * @param {string} userId - The identifier the tokens belong to. The service compares it against the
 *   subject of the identity token the provider issues and refuses a renewal that names another, so it
 *   must be the identifier the held tokens were issued for and not a value a caller chose.
 * @param {string} refreshToken - The refresh token last issued, sent in the body as a credential.
 * @param {AbortSignal} [signal] - Abandons the renewal when the session generation that armed it has
 *   been superseded, so an answer to a renewal for a session that has since ended -- or been replaced by
 *   a second sign-on -- is never stored over the current one. Optional for the same reason as on sign-on:
 *   a caller holding no generation has no signal to give.
 * @returns {Promise<SignOnTokens>} A fresh token set. Under the deployed pool's refresh-token
 *   rotation its `refreshToken` carries the successor that replaces the token just presented, so a
 *   caller must store it; the member stays nullable for a pool configured without rotation, and a null
 *   means the held one remains current and must not be overwritten with the null.
 * @throws {Error} The normalised `ApiRequestError`: 401 when the refresh token has been revoked or
 *   has expired, which a caller treats as a completed sign-out rather than as a retryable failure.
 */
export async function refreshTokens(
  userId: string,
  refreshToken: string,
  signal?: AbortSignal,
): Promise<SignOnTokens> {
  const request: TokenRefreshRequest = { userId, refreshToken };
  // Assumptions: the stored session is suppressed here for a sharper reason than on sign-on. This
  //   exchange runs precisely when the access token is about to stop being accepted, so the token most
  //   likely to be attached is the one whose expiry triggered the call; presenting it could refuse the
  //   refresh and sign the operator out for the sole reason that the refresh happened a moment late.
  //   The credential this operation actually presents is the refresh token, in the body.
  const response = await getApiClient().post<SignOnTokens>(
    requestPath(REFRESH_TOKENS),
    request,
    sessionExchangeConfig(signal),
  );
  return response.data;
}

/**
 * Completes a sign-on that the provider will not finish until the credential is replaced.
 * @param {string} userId - The identifier the challenge was raised for.
 * @param {string} session - The opaque continuation handle from the challenge, passed back unread.
 * @param {string} newPassword - The replacement credential, at most `PASSWORD_MAX_LENGTH` characters
 *   and sent only in the request body.
 * @param {AbortSignal} [signal] - Signal that abandons the exchange when the session it completes has
 *   been superseded.
 * @returns {Promise<SignOnTokens>} The token set issued once the replacement credential was accepted.
 * @throws {Error} The normalised `ApiRequestError`: 400 for a credential the provider's policy
 *   rejects, and 401 for a session that has expired, which obliges a fresh sign-on.
 */
export async function answerSignOnChallenge(
  userId: string,
  session: string,
  newPassword: string,
  signal?: AbortSignal,
): Promise<SignOnTokens> {
  const request: SignOnChallengeRequest = { userId, session, newPassword };
  // Assumptions: suppressed for the same reason as the two exchanges above. A caller answering a
  //   challenge holds no accepted token by definition -- the provider has not finished authenticating
  //   it -- so any bearer this tab still holds belongs to an earlier session and can only be refused.
  //   The credential here is the continuation handle and the replacement password, both in the body.
  const response = await getApiClient().post<SignOnTokens>(
    requestPath(ANSWER_SIGN_ON_CHALLENGE),
    request,
    sessionExchangeConfig(signal),
  );
  return response.data;
}

/**
 * Revokes a held refresh token at the identity provider, ending the session there and not only here.
 *
 * Assumptions: discarding tokens in the browser ends nothing at the provider. The refresh token is
 * provisioned with a thirty-day life -- `refresh_token_validity_days` defaults to 30 in
 * `infra/modules/cognito/variables.tf` -- so a copy taken from a browser store, a synchronised profile or
 * a shared workstation keeps minting access tokens for a month after the operator believed the session
 * was over. This call is what makes a sign-out an event at the provider rather than a gesture in a tab.
 *
 * Assumptions: it has NO reference counterpart. The baseline's sign-off transferred control back to the
 * sign-on screen and ended nothing, because the terminal session WAS the session and it lasted until the
 * terminal disconnected. No reference program, screen field or literal corresponds to this operation.
 *
 * Assumptions: this function clears no local state, and a caller must not depend on it to.
 * `ui/src/hooks/useAuth.ts` discards the held tokens whether this resolves or rejects, because a session
 * the operator has ended must not survive in the tab merely because the network did not cooperate; the
 * division is argued at the head of this module.
 * @param {string} refreshToken - The refresh token to revoke, sent in the body as a credential. It is
 *   the whole of the request: the provider's revocation accepts no user name, because the token
 *   identifies its own subject.
 * @returns {Promise<void>} Resolves with no value once the provider has been asked. The operation
 *   answers `204` both for a token it revoked and for one it declines to accept -- already revoked,
 *   expired, or not a revocable type -- because each of those states describes a token that can no longer
 *   mint anything, and distinguishing them would tell an unauthenticated caller whether a token was live.
 * @throws {Error} The normalised `ApiRequestError`: 400 with a `refreshToken` field error for an absent
 *   or over-long token, and 500 when the provider could not be reached, in which case nothing was
 *   revoked. It cannot answer 401 or 403, because it requires no authority to call.
 */
export async function signOut(refreshToken: string): Promise<void> {
  const request: SignOutRequest = { refreshToken };
  // Assumptions: suppressed for the sharpest version of the reason the three exchanges above suppress
  //   it. A caller signing out is the caller MOST likely to hold a token the resource server will
  //   refuse -- the access token lives one hour and the refresh token thirty days -- and the filter
  //   validates a presented credential before the permit-all rule for this path is reached, so
  //   attaching it would refuse the revocation in exactly the case that most needs it. The credential
  //   this operation presents is the refresh token, in the body.
  // Assumptions: this is the ONE session exchange that takes no abort signal, and the omission is
  //   deliberate. The other three establish or renew a session, so one superseded by a later event has
  //   nothing left to accomplish and abandoning it is free. This one ENDS a session: the event that
  //   would supersede it is the sign-out itself, and abandoning it would leave the very token the call
  //   exists to revoke alive at the provider. It must be allowed to finish.
  await getApiClient().post<void>(requestPath(SIGN_OUT), request, WITHOUT_STORED_SESSION);
}

// WHY : Alternatives Considered: positioning the browse by a page number, a row offset or a page
//       size, which is the obvious shape and is rejected on a specific defect. Under concurrent
//       insertion the number of rows preceding a position changes between one request and the next,
//       so an offset-paged reader silently skips some rows and shows others twice, whereas a key
//       already read keeps its place in the ordering however many rows are inserted around it. The
//       substitution is one-for-one rather than an approximation, because the baseline's browse was
//       ALREADY a cursor over keys: it stored the page's first and last key as real key values --
//       `CDEMO-CU00-USRID-FIRST` at `app/cbl/COUSR00C.cbl` L389 and `CDEMO-CU00-USRID-LAST` at L435
//       -- and kept no count of rows consumed anywhere. The screen's own page-number field is a trap
//       for this reason: `02 PAGENUMI PIC X(8).` at `app/cpy-bms/COUSR00.CPY` L60 exists, but it is
//       display-only and never positions the browse, so exposing a page number here would invent
//       positioning semantics the baseline never had. No parameter below names a page, an offset, a
//       size or a limit.
// WHY : Refactoring Rationale: `CreateUserRequest` and `UpdateUserRequest` stay two DISTINCT types
//       and neither is derived from the other with `Omit`, `Partial` or an intersection, even though
//       they overlap in two of their members. The property order is behaviour, not style: the
//       baseline's validation cascade short-circuits in physical screen order, so the first error a
//       user sees genuinely differs between the two flows. Creation validates first name, last name,
//       user identifier, then user type -- `app/cbl/COUSR01C.cbl` L120, L126, L132 and L144 -- while
//       the update path validates first name, last name, then user type at
//       `app/cbl/COUSR02C.cbl` L188, L194 and L206, its L200 password check having no target here at
//       all. The service orders `fieldErrors` to match, so merging the two shapes would silently
//       change which field a form marks first, in the most user-visible place there is. Creation
//       carries the identifier because it assigns it; the update takes the identifier from the PATH,
//       so admitting it in the body as well would allow a request whose two halves disagreed about
//       which user is being changed.

/**
 * Lists users one bounded page at a time, positioned by key.
 *
 * Assumptions: a cursor is an opaque sealed token minted by the service and is echoed back EXACTLY as
 * received -- never parsed, compared, incremented or constructed. A caller copies the `lastKey` of
 * the page it holds to advance or the `firstKey` to retreat, and states the matching direction; the
 * contract refuses a direction whose cursor is absent with a 400, which is why
 * {@link keysetPagingMembers} refuses that combination here rather than sending it, and why a cursor
 * supplied without a direction is read forward rather than being refused.
 *
 * Assumptions: the page size is set by the service at ten rows and is never supplied by the caller,
 * proven twice in the baseline -- `02 USER-REC OCCURS 10 TIMES.` at `app/cbl/COUSR00C.cbl` L57, and
 * exactly ten row groups on the screen itself at `app/cpy-bms/COUSR00.CPY` L78 through L348.
 *
 * Assumptions: reaching an end of the file is a SUCCESS. A short or empty page answers 200 with a
 * short `items` and `hasNext` false; it is never a 404, so a caller must not render an
 * end-of-browse as an error.
 *
 * Assumptions: an opening page may be POSITIONED instead of read from the start of the set, by
 * `startUserId`, which is the reference's own search field -- `app/cbl/COUSR00C.cbl` reads `USRIDINI`
 * on the enter turn at L218 to L221 and seeks the browse on it. Positioning is inclusive, so the row
 * named is the first row of the page, and a value no row carries positions on the next identifier
 * rather than refusing. It belongs to the OPENING turn only: once a page is held, a caller continues
 * from that page's sealed cursors and drops the identifier, which is why the two are refused together
 * below rather than one being silently preferred.
 * @param {UserListQuery} [query] - Optional opening position, OR a sealed cursor and the direction it
 *   was issued for. The two positions are mutually exclusive. Omit it entirely for the first page read
 *   from the start of the set.
 * @returns {Promise<PageResponse<UserSummary>>} One bounded page of user rows, with the `firstKey`
 *   and `lastKey` a caller pages from and the `hasNext` that says whether a forward move exists.
 * @throws {RangeError} If an opening position and a cursor are supplied together, or if a direction is
 *   supplied without a usable cursor -- which names no page to step from and which the contract
 *   refuses. Both refusals are raised here rather than sent, so the caller learns which two inputs
 *   disagreed instead of receiving a page as though one of them had not been asked for.
 * @throws {Error} The normalised `ApiRequestError`: 400 for a cursor sealed for the other direction or
 *   for an opening position outside the identifier domain, 401 for an absent or expired token, and 403
 *   for an authenticated caller outside the administrative group -- which is distinct from 401 and must
 *   not sign the caller out.
 */
export async function listUsers(query: UserListQuery = {}): Promise<PageResponse<UserSummary>> {
  // Assumptions: the two POSITIONS are refused here rather than sent, and named in the same order the
  //   service's own refusal names them, so a client that surfaces this message and a client that
  //   surfaces the service's 400 tell an operator the same thing. Sending the pair would spend a round
  //   trip to learn what is decidable without one, and preferring either silently would discard a
  //   position the caller stated.
  if (query.startUserId !== undefined && query.cursor !== undefined) {
    throw new RangeError(
      'A user browse takes either an opening identifier or a cursor, not both; a cursor already' +
        ' states the position to read from.',
    );
  }
  // Assumptions: the pair is checked before assembly rather than after, so a direction with no cursor
  //   is refused instead of being dropped. Dropping it was the previous behaviour and it contradicted
  //   the paragraph above, which states that the contract refuses that pair with a 400 -- a caller
  //   reading that sentence would expect a refusal and silently receive the opening page instead.
  const params: Record<string, string> = {};
  // Assumptions: an EMPTY opening position is sent rather than dropped, because the contract admits
  //   the empty form and gives it the same meaning as omission -- the reference seeks on LOW-VALUES
  //   when its search field is blank, at L219. Dropping it would make an explicit "read from the
  //   start" indistinguishable from a caller that never named a position, which is a distinction a
  //   replayed request should keep.
  if (query.startUserId !== undefined) {
    params.startUserId = query.startUserId;
  }
  // Refactoring Rationale: the pair is established by the shared guard rather than assembled here,
  //   because this module used to drop a supplied direction whenever no cursor accompanied it and
  //   answer the caller with the opening page -- the one combination the contract refuses with a 400
  //   keyed on the direction. Centralising it also puts the forward default in one place for all seven
  //   clients, which is what stops the seven from coming to disagree about it.
  const paging = keysetPagingMembers(query.cursor, query.direction);
  if (paging !== undefined) {
    params.cursor = paging.cursor;
    params.direction = paging.direction;
  }

  const response = await getApiClient().get<PageResponse<UserSummary>>(requestPath(LIST_USERS), {
    params: Object.keys(params).length === 0 ? undefined : params,
  });
  return response.data;
}

/**
 * Creates one user and provisions the matching identity-provider account.
 *
 * Assumptions: the resolved value names the managed-secret entry the account's one-time credential
 * was published to, and never the credential itself. A caller collects the value from that entry and
 * presents it to the person the account is for. See {@link CreatedUserResponse} for why the handover
 * works this way.
 * @param {CreateUserRequest} request - The four values a creation accepts, in the order the create
 *   screen validates them. No credential is among them, because the provider mints the initial one.
 * @returns {Promise<CreatedUserResponse>} The created user on 201, carrying the subject the provider
 *   minted and the name of the managed-secret entry holding its one-time credential.
 * @throws {Error} The normalised `ApiRequestError`: 400 with `fieldErrors` keyed `firstName`,
 *   `lastName`, `userId` or `userType` in that cascade order, 401, 403 for a caller outside the
 *   administrative group, and 409 when the identifier is already taken -- the branch the baseline
 *   reports as 'User ID already exist...' at `app/cbl/COUSR01C.cbl` L263. That literal is reproduced
 *   as the baseline writes it, missing its trailing letter, because it is an externally observable
 *   string and message fidelity under rule T8 outranks its grammar.
 */
export async function createUser(request: CreateUserRequest): Promise<CreatedUserResponse> {
  const response = await getApiClient().post<CreatedUserResponse>(
    requestPath(CREATE_USER),
    request,
  );
  return response.data;
}

/**
 * Reads one user, which is the shared load contract for the update and delete views.
 * @param {string} userId - The user's identifier, at most `USER_ID_MAX_LENGTH` characters. It is
 *   percent-encoded into the target by `requestPath`, so it cannot read as extra path segments.
 * @returns {Promise<UserResponse>} The user, carrying the provider subject that creation minted.
 * @throws {Error} The normalised `ApiRequestError`: 400 for a blank or over-long identifier, 401, 403
 *   for a caller outside the administrative group, and 404 when no row carries the identifier.
 */
export async function getUser(userId: string): Promise<UserResponse> {
  const response = await getApiClient().get<UserResponse>(requestPath(GET_USER, { userId }));
  return response.data;
}

/**
 * Updates the mutable values of one user.
 * @param {string} userId - The user's identifier, taken from the path and never from the body.
 * @param {UpdateUserRequest} request - The three values an update accepts, in the order the update
 *   screen validates them. The identifier is not among them and neither is a credential.
 * @returns {Promise<UserResponse>} The user as stored after the change.
 * @throws {Error} The normalised `ApiRequestError`: 400 with `fieldErrors` keyed `firstName`,
 *   `lastName` or `userType` in that cascade order, 401, 403 for a caller outside the administrative
 *   group, and 404 when no row carries the identifier.
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
 * Deletes one user, with the deletion explicitly confirmed by the caller.
 *
 * Refactoring Rationale: the confirmation is a REQUIRED parameter of this function and carries no
 * default, because the baseline made deleting a two-step act and that safeguard has to be
 * reconstructed rather than dropped. Its load arm displayed the record and then invited a second,
 * different keystroke -- 'Press PF5 key to delete this user ...' at `app/cbl/COUSR03C.cbl` L283 -- so
 * displaying a user and destroying one were never the same operation. A keystroke cannot survive as a
 * keystroke over HTTP, and without a replacement a prefetch, a retried request or a crawler following
 * a link could destroy a row. Requiring the caller to pass the affirmative value explicitly means
 * mere navigation to a target can never delete a user. It is typed as the literal `true` rather than
 * as a boolean because the contract declares the query parameter with `const: true`: false is not a
 * second mode that deletes nothing, it is a refusal, and a caller that means to keep the row does not
 * call this function at all.
 * @param {string} userId - The user's identifier, at most `USER_ID_MAX_LENGTH` characters.
 * @param {true} confirmed - Explicit confirmation that the row named is to be destroyed. The only
 *   accepted value; the service refuses an absent or false one with 400 and deletes nothing.
 * @returns {Promise<void>} Nothing. The operation answers 204 with no body, so there is no
 *   representation of the removed row to hand back.
 * @throws {Error} The normalised `ApiRequestError`: 400 with a single `fieldErrors` entry keyed
 *   `confirmed` when the confirmation is absent or not true, 401, 403 for a caller outside the
 *   administrative group, and 404 when no row carries the identifier. Its 500 branch reports an
 *   UPDATE failure on a delete, which is what the baseline writes at `app/cbl/COUSR03C.cbl` L332 --
 *   the same literal its sibling writes at `app/cbl/COUSR02C.cbl` L386 -- and the service carries it
 *   across as it stands for the same message-fidelity reason as the 409 above.
 */
export async function deleteUser(userId: string, confirmed: true): Promise<void> {
  await getApiClient().delete<void>(requestPath(DELETE_USER, { userId }), {
    params: { confirmed },
  });
}
