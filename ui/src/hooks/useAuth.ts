/**
 * @file Identity state for the signed-on operator: the held tokens, the group claim, and sign-out.
 *
 * Purpose
 * -------
 * This module is the SPA's single owner of WHO IS SIGNED ON. It is the migration target of exactly
 * one slice of the CICS pseudo-conversational communication area: the identity fields
 * `CDEMO-USER-ID PIC X(08)` and `CDEMO-USER-TYPE PIC X(01)`, with the condition names
 * `88 CDEMO-USRTYP-ADMIN VALUE 'A'` and `88 CDEMO-USRTYP-USER VALUE 'U'`, at
 * `app/cpy/COCOM01Y.cpy` L25 to L28. That one record carried four unrelated concerns, and this
 * module implements only the second of them:
 *
 *     navigation  (L21-L24, L43-L44)  ->  client-side router history, in `ui/src/router.tsx`
 *     identity    (L25-L28)           ->  THIS MODULE, read from a signed token claim
 *     selection   (L33, L38, L41)     ->  REST path and query parameters, held by each screen
 *     re-entry    (L29-L31)           ->  no target at all; see immediately below
 *
 * Assumptions: the re-entry discriminator `CDEMO-PGM-CONTEXT` has NO equivalent here, and none is
 * simulated. A stateless request either carries an accepted token or it does not, so there is no
 * first-turn-versus-later-turn distinction for this module to remember; the baseline needed one only
 * because a CICS task ended at every screen turn. The absence is recorded rather than left silent
 * because `app/cpy/CSSETATY.cpy` gates its field highlighting on that flag, so an author porting a
 * screen could otherwise come here looking for the flag it tests.
 *
 * Why the authority is a claim and not a value this module can be told
 * -------------------------------------------------------------------
 * Refactoring Rationale: in the baseline the communication area is storage the terminal echoes back
 * between turns, so the user type travelled through the client. `app/cbl/COSGN00C.cbl` L226 to L227
 * is the only write to those two fields in the entire online estate, and it happens once, after the
 * password comparison at L223. The corroborating evidence is at `app/cbl/COMEN01C.cbl` L181 to
 * L182, where the two moves that would have re-asserted identity on menu dispatch are commented
 * out, sitting between the live navigation moves at L178 to L180 and the live
 * `MOVE ZEROS TO CDEMO-PGM-CONTEXT` at L183: identity was CARRIED and never re-derived from a
 * trusted source after sign-on. The target derives authority only from the signed `cognito:groups`
 * claim, so this module exposes NO setter for the groups, the user identifier or any user type. A
 * setter would hand the client back the ability to assert its own authority, which is the one
 * property of the baseline this migration deliberately does not preserve.
 *
 * Assumptions: reading a claim in the browser is a ROUTING AND RENDERING convenience, not a
 * security boundary, and nothing here should be read as one. The boundary is the API Gateway
 * Cognito JWT authorizer plus each service's own resource-server validation, which re-derive
 * authority from the same claim on every request. A caller who tampers with what this module
 * believes gains a menu entry and then an HTTP 403; it cannot mint a group it was not granted. That
 * is why no signature is verified here and no attempt to verify one belongs here.
 *
 * What this module deliberately does not own
 * ------------------------------------------
 * Assumptions: four neighbouring concerns live elsewhere, and each boundary is load-bearing.
 * `ui/src/routes/guards.tsx` owns route guarding and the redirect, so this module never navigates.
 * `ui/src/api/client.ts` owns the bearer and correlation headers and holds the bearer in memory.
 * `ui/src/api/auth.ts` owns the sign-on, challenge and refresh requests, so no URL is built here.
 * `ui/src/messages/messages.ts` owns every user-visible string under Transformation Rule T8, which
 * is why this module contains NO display text: the baseline's sign-on failure wording at
 * `app/cbl/COSGN00C.cbl` L242, L249 and L254, and its administrative denial at
 * `app/cbl/COMEN01C.cbl` L140 to L141, are all catalog entries. This module publishes identity and
 * failure STATE; the screen chooses the sentence.
 *
 * Assumptions: this module reads NO build-time variable, and that is a decision rather than an
 * omission. `ui/.env.example` devotes a section to declaring the absence — the SPA holds no issuer,
 * no user-pool identifier, no app-client identifier, no region and no client secret, because it
 * never calls the identity provider; it posts credentials to the auth service and receives a token.
 * `ui/src/env.d.ts` correspondingly types only the three `VITE_` names `ui/src/api/client.ts`
 * reads. Were a knob ever needed here it would arrive through `import.meta.env` with a `VITE_`
 * prefix: `ui/vite.config.ts` sets `envPrefix: 'VITE_'` so nothing else reaches the bundle, and
 * `ui/tsconfig.json` omits `"node"` from `types` so the Node process environment object does not
 * compile in `ui/src` at all. The mechanism is recorded here so a later author reaching for a knob
 * uses the one that resolves rather than the one that is denied by design.
 */
import { useSyncExternalStore } from 'react';

import {
  SIGN_ON_AUTHENTICATED,
  answerSignOnChallenge,
  refreshTokens,
  signOn,
  signOut as revokeRefreshToken,
} from '../api/auth';
import type { SignOnChallenge, SignOnResult, SignOnTokens } from '../api/auth';
import {
  isApiRequestError,
  setAccessToken,
  subscribeToAuthenticationRequired,
} from '../api/client';
import type { ApiRequestError } from '../api/client';
import type { ApiError } from '../api/types';

/*
 * WHY : ⚠️ Refactoring Rationale: NOTHING in this module writes, reads or removes browser storage, and
 *       the four `carddemo.*` session-storage keys it used to maintain -- the access token, the identity
 *       token, the identifier and the refresh token -- are gone. A review found every credential this
 *       tab held persisted in script-readable Web Storage, including a refresh token the pool
 *       provisions with a thirty-day life, and named the resolution: keep exposed access tokens in
 *       memory only and move the refresh token out of script-readable storage. The whole session moved
 *       into memory, which satisfies the first half outright and is the strongest available form of the
 *       second.
 * WHY : Alternatives Considered: retaining the refresh token SERVER-SIDE, which the review names first.
 *       Rejected on an explicit AAP constraint rather than on preference: section 0.7.1 removes
 *       server-side session state deliberately -- "all eight services are stateless with no sticky
 *       sessions and no server-side session store, which is precisely what makes horizontally-scaled
 *       Fargate tasks behind a load balancer a viable target" -- so a server-held session would undo
 *       the property the whole compute topology rests on, and section 0.2.2 excludes the cache tier one
 *       would need.
 * WHY : Alternatives Considered: an `HttpOnly; Secure; SameSite` cookie, which the review names second.
 *       Rejected on three specifics. It would have to be a CROSS-SITE cookie, because AAP section
 *       0.4.1.9 puts the SPA behind CloudFront and the API behind API Gateway as separate origins and
 *       `infra/modules/cloudfront-spa` declares one S3 origin with no API behaviour -- so it would need
 *       `SameSite=None`, readmitting the cross-site request a header-borne bearer cannot be driven by,
 *       and giving any page the ability to trigger a rotation that invalidates the legitimate tab's
 *       token. It would change the published wire contract, which declares `refreshToken` in the BODY
 *       of both the renewal and the revocation. And it would require credentialed CORS at the gateway.
 *       Holding nothing at all removes the exposure instead of protecting it.
 * WHY : Trade-offs: a page reload now ends the session, where the four keys let it survive one. That
 *       is the cost, it is user-visible, and it is accepted for two reasons. This application is used
 *       at shared workstations -- a credential that survives a reload also survives an operator walking
 *       away and someone else pressing a key -- and the operator's remedy is one credential entry at a
 *       screen the application already renders and already routes to. It is registered as
 *       `D-SESSION-NOT-PERSISTED` in `docs/architecture/cobol-to-service-traceability.md`, because it is
 *       a behavioural difference and not only an implementation one.
 * WHY : Assumptions: the bearer is not held here either. It lives in `ui/src/api/client.ts`, which owns
 *       the request interceptor that attaches it and is its single writer and single reader; this module
 *       installs and discards it through `setAccessToken`. The two halves are therefore in memory in two
 *       modules, each where its reader is, and neither is reachable from a store.
 */

/**
 * The session this tab holds, or the absence of one. Held in memory and never persisted.
 *
 * Assumptions: every member is required except the renewal token, and the completeness is the point.
 * A review found "authenticated" derived from the PRESENCE of an identity token alone, with the four
 * keys written independently and every storage failure swallowed, so a tab could hold an identity
 * token from one session beside a bearer from another and report itself signed on. A record built in
 * one step from one validated token set cannot reach that state: either all of it is installed or none
 * of it is.
 *
 * Assumptions: the generation is carried ON the record rather than compared against a variable at the
 * point of use, so a reading and the session it describes cannot disagree about which session they
 * belong to.
 *
 * Assumptions: the renewal token is nullable because the pool may answer a renewal without a
 * replacement where a retry grace period leaves the submitted one current; the reasoning is on
 * `SignOnTokens` in `ui/src/api/types.ts`. It is the one member a new token set may omit without the
 * set being incomplete.
 */
interface HeldSession {
  /** The generation this session was installed in, so a later reading can tell it apart. */
  readonly generation: number;
  /** The identifier the tokens were issued for, folded as the service folds it. */
  readonly userId: string;
  /** The identity token the group claim is decoded from. */
  readonly idToken: string;
  /** The renewal token, or `null` when the pool issued none. */
  readonly refreshToken: string | null;
  /** Epoch milliseconds at which the installed bearer stops being accepted. */
  readonly expiresAt: number;
}

/**
 * Group granting the administrative surface, the target of `SEC-USR-TYPE` value `'A'`.
 *
 * Assumptions: `88 CDEMO-USRTYP-ADMIN VALUE 'A'` at `app/cpy/COCOM01Y.cpy` L27 maps to this group,
 * and the pool, the service authority conversion and the six administrative routes all name it
 * identically. The six are the migration of the administrative option table at
 * `app/cpy/COADM02Y.cpy`, whose live count is `VALUE 6` at L22 — L21 holds a commented-out earlier
 * `VALUE 4` — listing `COUSR00C` (L29), `COUSR01C` (L34), `COUSR02C` (L39), `COUSR03C` (L44),
 * `COTRTLIC` (L49) and `COTRTUPC` (L53). Which routes those become is `ui/src/routes/guards.tsx`'s
 * to know; this module's part is to publish the group so that module can decide.
 */
export const CARDDEMO_ADMIN_GROUP = 'carddemo-admin';

/**
 * Group granting the ordinary operator surface, the target of `SEC-USR-TYPE` value `'U'`.
 *
 * Assumptions: `88 CDEMO-USRTYP-USER VALUE 'U'` at `app/cpy/COCOM01Y.cpy` L28 maps to this group.
 * The baseline authorises per DESTINATION rather than per screen — `app/cbl/COMEN01C.cbl` L136 to
 * L137 refuses an option whose table entry is marked `'A'` to a user-type operator — so membership
 * alone decides nothing here; it is one input to a per-route decision made by the guards.
 */
export const CARDDEMO_USER_GROUP = 'carddemo-user';

/**
 * Group granting the administrative surface, under the name this tree's callers already import.
 *
 * Assumptions: `ui/src/screens/signon/index.tsx` imports this name to choose the landing route from
 * the token it has just received. It is an alias of {@link CARDDEMO_ADMIN_GROUP} rather than a
 * second literal, so the two names cannot come to hold different values; the longer name is the
 * contract stated by the migration plan and this is the spelling already in use at the call site.
 */
export const ADMIN_GROUP = CARDDEMO_ADMIN_GROUP;

/** Group granting the ordinary operator surface; an alias of {@link CARDDEMO_USER_GROUP}. */
export const USER_GROUP = CARDDEMO_USER_GROUP;

/**
 * Claim carrying the operator's group memberships in the identity token.
 *
 * Assumptions: named as a constant rather than written inline at the one place it is read, because
 * the same string is the contract on the service side — each resource server converts this claim to
 * authorities — so it is a shared name and not a local detail of the decoder below.
 */
export const COGNITO_GROUPS_CLAIM = 'cognito:groups';

/**
 * Claim carrying the pool user name an identity token was issued for.
 *
 * Assumptions: this is the same claim `CognitoIdentityService.subjectOfIdentityToken` reads on the
 * service side, named identically here because it is one contract with the pool rather than a local
 * detail. The pool's `sub` claim is a generated identifier and is deliberately NOT used: the value this
 * application binds a session to is the eight-character operator identifier the sign-on screen collects
 * and the `auth.users` row is keyed by, and that is what the pool records as the user name.
 */
const ID_TOKEN_SUBJECT_CLAIM = 'cognito:username';

/**
 * Seconds before expiry at which a token refresh is attempted.
 *
 * Trade-offs: sixty seconds early, so a refresh is in flight well before the bearer stops being
 * accepted. Refreshing exactly at expiry would race the very request that discovers the expiry, and
 * refreshing much earlier would spend exchanges on a credential still comfortably valid.
 */
const REFRESH_MARGIN_SECONDS = 60;

/**
 * Milliseconds in one second, for converting an expiry expressed in seconds into a timer delay.
 */
const MILLISECONDS_PER_SECOND = 1000;

/** Number of dot-delimited segments a JSON Web Token carries: header, claims and signature. */
const JWT_SEGMENT_COUNT = 3;

/** Length multiple that base64 decoding requires, used to re-pad a base64url claim segment. */
const BASE64_QUANTUM = 4;

/**
 * Where the session stands, as a value a screen can branch on without inspecting a token.
 *
 * Assumptions: four states, and they are distinguishable because a screen needs to tell them apart
 * without holding its own copy of the answer. `anonymous` is the resting state with nothing held;
 * `authenticating` covers an in-flight sign-on, challenge or refresh exchange; `authenticated`
 * means tokens are held; `error` means the last exchange was refused. The state is published here
 * as a value precisely so that the SENTENCE stays in `ui/src/messages/messages.ts` under
 * Transformation Rule T8 — a status is not display text, so it can live here while the wording
 * cannot.
 */
export type AuthStatus = 'anonymous' | 'authenticating' | 'authenticated' | 'error';

/**
 * The signed-on operator, or the absence of one, as one immutable reading.
 *
 * Assumptions: every member is `readonly`, and `groups` is a `readonly string[]`, so a caller can
 * neither replace a member nor mutate the array in place. That is the type-level half of the
 * no-setter decision argued in this file's overview; the runtime half is that the object and its
 * group array are frozen when built.
 */
export interface AuthSnapshot {
  /** Where the session stands; see {@link AuthStatus}. */
  readonly status: AuthStatus;

  /**
   * Whether tokens are held for this browser tab.
   *
   * Assumptions: derived from the presence of the identity token on every reading rather than
   * stored as a flag of its own, so it cannot fall out of step with the tokens it describes.
   */
  readonly isAuthenticated: boolean;

  /** Whether tokens are held for this tab; the spelling this tree's guards already destructure. */
  readonly signedOn: boolean;

  /**
   * Identifier the held tokens were issued for, or `null` when nobody is signed on.
   *
   * Assumptions: eight characters, because `SEC-USR-ID PIC X(08)` at `app/cpy/CSUSR01Y.cpy` L18
   * fixes the width and Transformation Rule T1 makes the copybook normative. The width is enforced
   * where an identifier is ENTERED — `USER_ID_MAX_LENGTH` in `ui/src/api/auth.ts` — rather than
   * re-asserted here, because this member reports what the service issued rather than validating
   * it.
   */
  readonly userId: string | null;

  /** Group names carried by the identity token's `cognito:groups` claim; empty when absent. */
  readonly groups: readonly string[];

  /** Whether the held claim includes {@link CARDDEMO_ADMIN_GROUP}. */
  readonly isAdmin: boolean;

  /**
   * The normalised problem from the last refused exchange, or `null`.
   *
   * Assumptions: populated only when the refusal actually carried a problem document, so a plain
   * transport failure leaves this `null` while `status` still reports `error`. Synthesising a
   * problem shape would mean inventing a correlation identifier and a timestamp no service issued,
   * which would put fabricated values where a reader expects service-reported ones.
   *
   * Trade-offs: the screen that awaited the call reports the failure from its own `catch`, so this
   * member is a second observer of the same event rather than the primary one. Publishing it is
   * still worth the duplication because a component that did not issue the request — a shell
   * showing a stale-session indicator, say — has no `catch` to read; what it must not do is render
   * a message from here, since a message detached from the action that caused it is the reason the
   * screen keeps that job.
   */
  readonly error: ApiError | null;
}

/** The signed-on operator, or the absence of one; an alias of {@link AuthSnapshot}. */
export type AuthState = AuthSnapshot;

/** Everything a screen needs to establish, inspect or end a session. */
export interface UseAuthResult extends AuthSnapshot {
  /**
   * Signs on with an operator identifier and password.
   *
   * Assumptions: two positional parameters rather than a single request object, because
   * `ui/src/screens/signon/index.tsx` already calls it this way and that module is not this one's
   * to change. The information content is identical, and the result is RETURNED rather than
   * swallowed so the caller can render a challenge form or route on the claim in the token it has
   * just received.
   * @param {string} userId - Operator identifier, as entered.
   * @param {string} password - Operator password, used for this call only and never retained.
   * @returns {Promise<SignOnResult>} Tokens when the credential was accepted, or the challenge the
   *   pool raised. Rejects with the transport's failure when the credential was refused.
   */
  signIn: (userId: string, password: string) => Promise<SignOnResult>;

  /**
   * Answers an outstanding challenge, completing sign-on.
   * @param {SignOnChallenge} challenge - The challenge being answered, as sign-on returned it.
   * @param {string} newPassword - Replacement password, used for this call only and never retained.
   * @returns {Promise<SignOnTokens>} The issued tokens, returned so a caller can route on their
   *   claim without waiting for a re-render. Rejects with the transport's failure when refused.
   */
  answerChallenge: (challenge: SignOnChallenge, newPassword: string) => Promise<SignOnTokens>;

  /**
   * Discards every credential held for this tab and revokes the refresh token at the provider.
   *
   * Assumptions: the local clear is unconditional and immediate; the revocation is dispatched
   * afterwards and its outcome is not reported, because the sign-out has already happened in every
   * respect a screen can observe. The reasoning is recorded on the implementation.
   */
  signOut: () => void;
}

/** Everything a screen needs to establish, inspect or end a session; alias of {@link UseAuthResult}. */
export type AuthApi = UseAuthResult;

/**
 * The single empty group list every anonymous reading shares.
 *
 * Assumptions: one frozen instance rather than a fresh `[]` per reading, because the array is a
 * member of a snapshot React compares by identity. A new empty array each time would make two
 * otherwise identical readings unequal, which is the re-render trap described on {@link getSnapshot}.
 */
const NO_GROUPS: readonly string[] = Object.freeze([]);

/**
 * Reads the subject one identity token was issued for.
 *
 * Purpose: this is the browser half of the binding the auth service applies to a renewal. That service
 * compares the identifier a caller submits against the `cognito:username` claim of the identity token
 * the pool returns, and refuses a mismatch; this reads the same claim so a token set is installed only
 * when its identity token actually describes the identifier it arrived with. The two checks are
 * independent on purpose — this one refuses a set the browser should never render, and the service's
 * refuses one it should never issue — and neither substitutes for the other.
 *
 * Assumptions: the signature is deliberately NOT verified, on the same reasoning recorded on
 * {@link groupsFromIdToken}: verification needs the pool's keys and is performed by every service on
 * every request, while this decides what the browser installs. An unreadable token yields `null`, which
 * the caller treats as an incomplete token set and refuses — so the failure direction is a refused
 * sign-on rather than an installed session whose identity nobody could read.
 * @param {string} idToken - Identity token as the service returned it.
 * @returns {string | null} The `cognito:username` claim, or `null` when the token cannot be read or
 *   carries the claim in any shape other than a non-empty string.
 */
function subjectOfIdToken(idToken: string): string | null {
  const claims = claimsOfIdToken(idToken);
  if (claims === null) {
    return null;
  }
  const subject: unknown = claims[ID_TOKEN_SUBJECT_CLAIM];
  return typeof subject === 'string' && subject.length > 0 ? subject : null;
}

/**
 * Decodes an identity token's claim segment, without verifying it.
 *
 * Assumptions: hoisted so the group reader and the subject reader share one decoder rather than each
 * carrying its own base64url handling. Two copies of this would be two places for the padding
 * arithmetic and the two alphabet substitutions to be got wrong, and a decoder that silently produced
 * the wrong bytes would answer "no groups" and "no subject" rather than failing visibly.
 * @param {string} idToken - Identity token as the service returned it.
 * @returns {Record<string, unknown> | null} The decoded claims, or `null` when the token does not carry
 *   three segments, the claim segment is not base64url, or the bytes are not a JSON object.
 */
function claimsOfIdToken(idToken: string): Record<string, unknown> | null {
  const segments = idToken.split('.');
  // Assumptions: the claim segment is taken through an explicit undefined check rather than by
  //   indexing after a length test, because `noUncheckedIndexedAccess` types every element access as
  //   possibly undefined and a non-null assertion would reassert what the length test only implies
  //   -- which is exactly the kind of claim a decoder reading an untrusted string must not make.
  const claimSegment = segments.length === JWT_SEGMENT_COUNT ? segments[1] : undefined;
  if (claimSegment === undefined) {
    return null;
  }
  try {
    // Assumptions: base64url differs from base64 in two substitutions and in dropping the padding,
    //   so both are undone before `atob`, which accepts only the padded standard alphabet.
    const payload = claimSegment.replace(/-/gu, '+').replace(/_/gu, '/');
    const padded = payload.padEnd(Math.ceil(payload.length / BASE64_QUANTUM) * BASE64_QUANTUM, '=');
    const decoded: unknown = JSON.parse(atob(padded));
    if (typeof decoded !== 'object' || decoded === null) {
      return null;
    }
    return decoded as Record<string, unknown>;
  } catch {
    // Assumptions: one catch covers `atob` rejecting a non-base64 segment and `JSON.parse` rejecting
    //   the bytes it yields, because the two are indistinguishable to a caller and have the same
    //   correct answer. This is the path `ui/src/routes/guards.tsx`'s undecodable-token case takes.
    return null;
  }
}

/**
 * Decodes the group names carried by an identity token.
 *
 * Assumptions: the signature is deliberately NOT verified, and that is sound only because of what
 * the result is used for. Verification requires the pool's public keys and is performed by every
 * service on every request; this decode chooses which links to render. A malformed or unreadable
 * token yields NO groups, so the failure direction is an operator who sees no administrative entries
 * rather than one who sees entries that then fail — a decoder returning every group on a parse
 * failure would open the surface to any string.
 *
 * Refactoring Rationale: this is exported rather than kept private because a caller that has just
 * received a token needs the claim from THAT token, not from a rendered reading. React has not
 * re-rendered at the moment sign-on resolves, so `isAdmin` still describes the previous session, and
 * `ui/src/screens/signon/index.tsx` routing on it sent an administrator to the ordinary menu on
 * every sign-on. Exporting the decoder lets the landing route be chosen from the value in hand,
 * which is the browser's equivalent of the branch at `app/cbl/COSGN00C.cbl` L230.
 * @param {string | null} idToken - Identity token issued by the pool, or `null`.
 * @returns {readonly string[]} Group names from the `cognito:groups` claim, empty when the token is
 *   absent, undecodable, or carries the claim in any shape other than an array of strings.
 */
export function groupsFromIdToken(idToken: string | null): readonly string[] {
  if (idToken === null) {
    return NO_GROUPS;
  }
  // Assumptions: ⚠️ Refactoring Rationale: the base64url decode this used to perform inline now comes
  //   from {@link claimsOfIdToken}, which the subject reader shares. Two copies appeared the moment a
  //   second claim had to be read, and two copies of padding arithmetic and two alphabet substitutions
  //   are two chances to produce the wrong bytes -- a failure that would answer "no groups" rather than
  //   failing visibly, and so would present as a missing administrative menu with no diagnostic.
  const claims = claimsOfIdToken(idToken);
  if (claims === null) {
    return NO_GROUPS;
  }
  const claim: unknown = claims[COGNITO_GROUPS_CLAIM];
  if (!Array.isArray(claim)) {
    // Assumptions: a claim present but not an array -- a lone group serialised as a bare string is
    //   the realistic case -- yields no groups rather than being coerced. Coercing would let a
    //   shape this application has not agreed with the pool decide an authority question.
    return NO_GROUPS;
  }
  return Object.freeze(claim.filter(isGroupName));
}

/**
 * Narrows one decoded claim element to a string.
 *
 * Assumptions: a hoisted predicate rather than an inline arrow, because `ui/eslint.config.js`
 * configures `jsdoc/require-jsdoc` with `publicOnly: false` and selects `* > ArrowFunctionExpression`
 * in every position, and Prettier moves a block comment attached to an inline argument onto the
 * preceding expression, detaching it from what it documents.
 * @param {unknown} group - One element of the decoded groups claim.
 * @returns {boolean} `true` only when the element is a string, which narrows the filtered result.
 */
function isGroupName(group: unknown): group is string {
  return typeof group === 'string';
}

/**
 * The identities of the sign-on, challenge and refresh exchanges currently in flight.
 *
 * Assumptions: a SET of identities rather than a boolean, so two exchanges overlapping — a scheduled
 * refresh firing while an operator re-submits sign-on — cannot have the first to settle report the
 * session as no longer authenticating while the second is still running.
 *
 * ⚠️ Refactoring Rationale: a set of identities rather than a COUNT, and the count could go negative.
 * {@link resetAuthSession} zeroes what is in flight, because the published status is derived and an
 * exchange in flight dominates that derivation; every exchange decrements in a `finally`. So an exchange
 * abandoned rather than awaited — which is exactly what the reset is for — decremented a counter the
 * reset had already zeroed, leaving it at minus one. From there `> 0` reads false while an exchange is
 * genuinely running, so the next sign-on published `anonymous` instead of `authenticating`, and a second
 * abandoned exchange took it to minus two, from which even two live exchanges could not lift it above
 * zero. A set cannot hold a negative number of anything: retiring an identity that is no longer a member
 * is a no-op, which is precisely the semantics an abandoned exchange needs.
 */
const exchangesInFlight = new Set<number>();

/**
 * The identity the next exchange will be recorded under.
 *
 * Assumptions: a monotonically increasing number rather than the session generation, because two
 * exchanges can belong to ONE generation — a challenge answer completes the sign-on that raised it, and
 * a scheduled refresh can fire alongside either — so a generation would not identify them apart and the
 * first to settle would retire the other's membership.
 */
let nextExchangeIdentity = 0;

/** Whether the most recent completed exchange was refused, which is what `status` reports. */
let exchangeWasRefused = false;

/** The problem document from the most recent refusal, or `null` when none carried one. */
let lastFailure: ApiError | null = null;

/** The most recently returned reading, retained so an unchanged store returns an equal object. */
let cachedSnapshot: AuthSnapshot | null = null;

/** The raw identity token {@link cachedSnapshot} was built from, used to detect a real change. */
let cachedIdToken: string | null = null;

/** The identity token {@link groupsCacheValue} was decoded from, or `null` when none is cached. */
let groupsCacheToken: string | null = null;

/** The decoded groups for {@link groupsCacheToken}, reused so the array keeps its identity. */
let groupsCacheValue: readonly string[] = NO_GROUPS;

/** Callbacks React has registered to be told when a reading changes. */
const listeners = new Set<() => void>();

/** Releases the shared re-authentication subscription, or `null` when none is held. */
let releaseRefusalSubscription: (() => void) | null = null;

/** Handle of the pending refresh timer, or `null` when none is scheduled. */
let scheduledRefresh: ReturnType<typeof setTimeout> | null = null;

/**
 * The session this tab holds, or `null` when none is held. This is the authority for every reading.
 *
 * Assumptions: ⚠️ Refactoring Rationale: this replaces reading `sessionStorage` on every reading. The
 * store was called the authority precisely so that two mounted callers could not disagree, and one
 * module-scoped variable delivers that property for the same reason a shared store did — there is
 * exactly one of it — while adding two the store could not. It cannot be read by another script, and it
 * cannot be half-written: a token set is installed as one frozen record or not at all.
 */
let heldSession: HeldSession | null = null;

/**
 * How many times a session has been established or ended in this tab.
 *
 * Purpose: this is the guard a review asked for. Sign-on, the challenge answer and the renewal are all
 * asynchronous, and nothing stopped one that was already in flight from applying its outcome after the
 * session it belonged to had ended or been replaced. Two concrete failures followed. A stale SUCCESS
 * could reinstall tokens after a sign-out — resurrecting a session the operator had closed — or
 * overwrite a newer one with an older token set. A stale FAILURE could clear a session established
 * after it started, signing out an operator whose sign-on had just succeeded.
 *
 * Assumptions: every exchange captures this value before it dispatches and may mutate state only while
 * it still matches. Sign-on, sign-out and every session-ending path increment it, so "superseded" is a
 * comparison rather than a judgement, and the comparison is made at the moment of mutation rather than
 * at the moment the promise settles.
 *
 * Alternatives Considered: comparing the identifier the exchange was for against the one currently
 * held, which needs no counter. Rejected because it cannot see a sign-out followed by a sign-on as the
 * SAME operator — the identifiers match, the session is nonetheless a different one, and a stale
 * exchange would be admitted. A monotonic counter distinguishes them because it counts events rather
 * than comparing values.
 */
let sessionGeneration = 0;

/**
 * Abandons the exchanges belonging to the current generation, or `null` before any has been created.
 *
 * Assumptions: one controller per generation rather than one per exchange, because "superseded" is a
 * property of the generation and every exchange in it is superseded at the same moment. Aborting is the
 * belt to the generation guard's braces: the guard makes a late outcome harmless, and the abort stops
 * the request being completed at all, which also stops a credential travelling for a session that has
 * ended.
 */
let sessionAbort: AbortController | null = null;

/**
 * Decodes an identity token's groups, reusing the previous result for an unchanged token.
 *
 * Trade-offs: the decode is memoised on the raw token string, which costs one retained token and one
 * retained array. What it buys is that a reading rebuilt for a status change alone keeps the SAME
 * group array, so a consumer holding `groups` in a dependency list is not woken by a change that did
 * not touch it. Re-decoding per reading would also mean base64-decoding a token on every render,
 * which is work with no observable result.
 * @param {string | null} idToken - Identity token to decode, or `null` when none is held.
 * @returns {readonly string[]} The token's groups, empty when no token is held.
 */
function decodeGroupsMemoised(idToken: string | null): readonly string[] {
  if (idToken === null) {
    return NO_GROUPS;
  }
  if (idToken === groupsCacheToken) {
    return groupsCacheValue;
  }
  groupsCacheToken = idToken;
  groupsCacheValue = groupsFromIdToken(idToken);
  return groupsCacheValue;
}

/**
 * Derives the published status from what is held and what is in flight.
 *
 * Assumptions: status is DERIVED on every reading rather than assigned, so it cannot contradict the
 * tokens it accompanies. The order matters: an exchange in flight dominates, because an operator
 * re-submitting after a refusal is authenticating and not failed; a held token then wins over a
 * remembered refusal, because a refused sign-on must not describe a session that is still valid.
 * @param {boolean} authenticated - Whether an identity token is currently held.
 * @returns {AuthStatus} The status to publish for this reading.
 */
function deriveStatus(authenticated: boolean): AuthStatus {
  if (exchangesInFlight.size > 0) {
    return 'authenticating';
  }
  if (authenticated) {
    return 'authenticated';
  }
  return exchangeWasRefused ? 'error' : 'anonymous';
}

/**
 * Produces the current reading, returning the previous object when nothing has changed.
 *
 * Assumptions: {@link heldSession} is the AUTHORITY for what is held, and this function reads it on
 * every call rather than caching a copy of its members. Because there is exactly one of it, two mounted
 * callers cannot hold different answers — which is the defect a per-instance `useState` model has: a
 * sign-out performed through one instance left every other instance reporting the previous session until
 * something unrelated re-rendered it.
 *
 * Assumptions: ⚠️ Refactoring Rationale: the authority was `sessionStorage` and is now a module
 * variable, and one property of the old arrangement is deliberately given up. The store was shared with
 * `ui/src/api/client.ts`, so both modules read one value; they now hold their halves separately — this
 * one the identity, that one the bearer — and the halves are kept in step by {@link installTokens}
 * being the only writer of both. The property gained in exchange is that neither half is reachable by
 * another script on the origin, which is the whole point of the move.
 *
 * Assumptions: a reading is derived from the session record ALONE and never from a stored token, so a
 * reading cannot describe a session the record does not hold. That is what makes "authenticated" mean
 * the complete validated set rather than the presence of one token — the record exists only when
 * {@link installTokens} accepted all of it.
 *
 * Trade-offs: `useSyncExternalStore` compares readings by identity and calls this function during
 * render, so returning a freshly built object each time would re-render without end. The reading is
 * therefore CACHED and only rebuilt when one of the values it is composed of actually differs. The
 * cost is the four comparisons below, which must be kept in step with the members above; the
 * alternative — a version counter bumped by every mutator — was rejected because a mutator that
 * forgot to bump it would silently freeze the interface, a failure far harder to see than a stale
 * comparison here.
 * @returns {AuthSnapshot} The current reading, referentially equal to the previous one when the
 *   session, the in-flight state and the last failure are all unchanged.
 */
function getSnapshot(): AuthSnapshot {
  const idToken = heldSession === null ? null : heldSession.idToken;
  const userId = heldSession === null ? null : heldSession.userId;
  const status = deriveStatus(heldSession !== null);
  if (
    cachedSnapshot !== null &&
    cachedIdToken === idToken &&
    cachedSnapshot.userId === userId &&
    cachedSnapshot.status === status &&
    cachedSnapshot.error === lastFailure
  ) {
    return cachedSnapshot;
  }
  const groups = decodeGroupsMemoised(idToken);
  const authenticated = heldSession !== null;
  cachedIdToken = idToken;
  cachedSnapshot = Object.freeze({
    status,
    isAuthenticated: authenticated,
    signedOn: authenticated,
    userId,
    groups,
    isAdmin: groups.includes(CARDDEMO_ADMIN_GROUP),
    error: lastFailure,
  });
  return cachedSnapshot;
}

/**
 * Tells every registered listener that a new reading is available.
 * @returns {void} Nothing; each listener re-reads through {@link getSnapshot}.
 */
function notifyListeners(): void {
  for (const listener of listeners) {
    listener();
  }
}

/**
 * Cancels the pending refresh, if one is scheduled.
 * @returns {void} Nothing; no exchange will fire from a timer after this returns.
 */
function cancelScheduledRefresh(): void {
  if (scheduledRefresh !== null) {
    clearTimeout(scheduledRefresh);
    scheduledRefresh = null;
  }
}

/**
 * Stores or clears the bearer token through the module that owns it.
 *
 * Assumptions: the token is written through `setAccessToken` in `ui/src/api/client.ts` rather than held
 * here, so that module stays the single writer AND the single reader of the value its own request
 * interceptor attaches. Its docstring fixes the direction — it owns the token and this module calls IN —
 * and honouring it is what keeps the import graph acyclic: this module already imports that one, so an
 * import back would close a cycle between a hook and the transport its requests are dispatched by.
 *
 * Assumptions: ⚠️ Refactoring Rationale: that module now holds the token in MEMORY rather than in a
 * session-storage slot, so this call installs it into a variable rather than into a store. Nothing about
 * the direction changes; what changes is that neither module leaves a credential where another script on
 * the origin could read it.
 * ⚠️ Refactoring Rationale: EVERY failure of the write is allowed out, where anything that was not a
 * `RangeError` used to be swallowed. The reasoning for swallowing was that such a failure comes from the
 * owning module rather than from this token, and that "the caller's own guard reports the refusal" -- but
 * there is no such guard: {@link installTokens} calls this and then writes the session record on the very
 * next statement, so a swallowed failure installed an identity whose requests would carry NO bearer. That
 * is the one state the whole all-or-nothing install exists to prevent, and it is worse than a raised
 * error because the session looks established and every request is refused. Which module the failure came
 * from does not change what it means here: the bearer was not written, so no session may be claimed.
 * @param {string | null} token - Bearer token to store, or `null` to discard the stored one.
 * @returns {void} Nothing; the slot holds the token, or holds nothing.
 * @throws {unknown} Whatever the owning module raised. A `RangeError` means the token itself is
 *   unusable -- blank or carrying control characters -- and anything else means the write failed for a
 *   reason this module cannot interpret. Both leave the bearer unwritten, so both must reach the caller
 *   rather than let a session be installed without a credential.
 */
function applyBearerToken(token: string | null): void {
  setAccessToken(token);
}

/**
 * Forgets any remembered refusal, so a later reading does not report a failure already superseded.
 * @returns {void} Nothing; callers notify once they have finished changing state.
 */
function clearFailureState(): void {
  exchangeWasRefused = false;
  lastFailure = null;
}

/**
 * Discards every credential and identity value held for this tab, and supersedes what is in flight.
 *
 * Refactoring Rationale: this clears EVERYTHING, and the totality is taken from the baseline rather
 * than chosen. `app/cbl/COMEN01C.cbl` L196 to L203 returns to the sign-on program with
 * `XCTL PROGRAM(CDEMO-TO-PROGRAM)` carrying NO `COMMAREA` clause, which is the one transfer in the
 * estate that omits it — compare L184 to L187 in the same program and `app/cbl/COSGN00C.cbl` L231 to
 * L234, which both pass it. The next program therefore starts with `EIBCALEN = 0` and no identity at
 * all. A partial clear here, dropping the bearer while leaving the identity token in place, would
 * leave `ui/src/routes/guards.tsx` reading authority from a session that no longer exists.
 *
 * Assumptions: ⚠️ Refactoring Rationale: the generation is incremented and the in-flight exchanges are
 * abandoned, which this function did neither of. Without the increment a renewal or a sign-on already
 * dispatched could install its token set after this returned, reinstating a session the operator had
 * just ended — the totality argued above held for the values present at the instant of the call and not
 * for the ones arriving a moment later. The increment is what extends it over time.
 *
 * Assumptions: the order is deliberate. The generation moves FIRST, so any outcome settling during the
 * rest of this function is already superseded; the abort follows, so a request still open is abandoned;
 * and the values are discarded last.
 * @returns {void} Nothing; no session is held, no refresh is pending, and nothing in flight can install
 *   one. The caller notifies, because the callers differ in what they do to the remembered failure.
 */
function discardSession(): void {
  supersedeGeneration();
  cancelScheduledRefresh();
  applyBearerToken(null);
  heldSession = null;
  cachedIdToken = null;
  groupsCacheToken = null;
  groupsCacheValue = NO_GROUPS;
}

/**
 * Opens a new session generation, abandoning every exchange belonging to the previous one.
 *
 * Assumptions: the controller is REPLACED rather than reused, because an aborted controller stays
 * aborted — a signal taken from it afterwards would abandon the next exchange before it dispatched. The
 * previous one is aborted first so nothing belonging to the superseded generation is left open.
 * @returns {number} The generation now current, which a caller starting an exchange captures.
 */
function supersedeGeneration(): number {
  sessionGeneration += 1;
  if (sessionAbort !== null) {
    sessionAbort.abort();
  }
  sessionAbort = new AbortController();
  return sessionGeneration;
}

/**
 * Reports the signal an exchange in the current generation is dispatched with.
 *
 * Assumptions: the controller is created on demand rather than at module load, because a module that
 * constructed one on import would do so in every environment this module is imported into, including
 * ones with no such constructor. Creating it at the first exchange keeps the module importable as data,
 * which `ui/src/router.tsx` and its tests depend on.
 * @returns {AbortSignal} The signal for the current generation; never `undefined`.
 */
function currentAbortSignal(): AbortSignal {
  sessionAbort ??= new AbortController();
  return sessionAbort.signal;
}

/**
 * Records that an exchange has begun, and answers the identity it was recorded under.
 *
 * Assumptions: the caller keeps the identity and hands it back to {@link endExchange} from a `finally`,
 * so an exchange retires exactly its own membership. A helper that retired "the most recent" instead
 * would let the first of two overlapping exchanges to settle retire the second's.
 * @returns {number} The identity this exchange is recorded under; never reused within a page load.
 */
function beginExchange(): number {
  nextExchangeIdentity += 1;
  exchangesInFlight.add(nextExchangeIdentity);
  return nextExchangeIdentity;
}

/**
 * Records that an exchange has settled, whatever its outcome.
 *
 * Assumptions: retiring an identity that is no longer a member is a NO-OP rather than an error, and that
 * is the property {@link resetAuthSession} depends on: the reset abandons everything in flight, and the
 * abandoned exchanges still run their own `finally` afterwards. With a counter each of those decremented
 * below zero; here each removes a member that is already gone and nothing is disturbed.
 * @param {number} identity - The identity {@link beginExchange} answered for this exchange.
 * @returns {void} Nothing; the caller notifies listeners.
 */
function endExchange(identity: number): void {
  exchangesInFlight.delete(identity);
}

/**
 * Records a refused exchange, keeping the problem document when the refusal carried one.
 *
 * Assumptions: a refusal does NOT by itself end the session. A rejected password is answered with the
 * same status as an expired session, and `ui/src/api/client.ts` already separates the two — it raises
 * the re-authentication signal only for a refusal of a request that CARRIED a bearer. Clearing here
 * as well would sign out an operator who merely mistyped a password.
 * @param {unknown} cause - Whatever the exchange rejected with.
 * @returns {void} Nothing; the caller notifies.
 */
function recordRefusal(cause: unknown): void {
  exchangeWasRefused = true;
  // Assumptions: the problem is taken only from a failure that actually carries one. Synthesising an
  //   `ApiError` from a bare transport error would require inventing a correlation identifier, a path
  //   and a timestamp that no service issued, putting fabricated values where a reader is entitled to
  //   assume service-reported ones.
  lastFailure = isApiRequestError(cause) ? cause.problem : null;
}

/**
 * Signs the operator out because a service stopped accepting the session this tab holds.
 *
 * Assumptions: this runs only for a refusal `ui/src/api/client.ts` has already judged to be a
 * refusal of THIS SESSION — a 401 answered to a request that carried a bearer. A 403, which is a
 * valid token refused one route, never reaches here, which is what keeps an ordinary operator who
 * meets an administrative screen signed in rather than signed out.
 * @param {ApiRequestError} failure - The normalised refusal, whose problem document is retained so a
 *   consumer can report why the session ended.
 * @returns {void} Nothing; every listener is told, so every mounted caller re-reads at once.
 */
function handleSessionRefused(failure: ApiRequestError): void {
  discardSession();
  recordRefusal(failure);
  notifyListeners();
}

/**
 * Registers a listener and, with the first of them, opens the shared re-authentication subscription.
 *
 * Assumptions: the subscription to `ui/src/api/client.ts` is REFERENCE-COUNTED to the window in which
 * at least one caller is mounted, rather than opened once when this module loads. A permanent
 * subscription would keep clearing storage for a page with nothing rendered, and
 * `stopsListeningOnceUnmounted` in `ui/src/test/authSessionSignal.test.ts` fixes the opposite
 * behaviour: after the last caller unmounts, a refused request must leave the identity token exactly
 * as it was. Counting also means the signal is delivered to EVERY mounted caller through one
 * subscription, instead of each caller holding its own and clearing the same keys in turn.
 * Assumptions: the first listener also REARMS the refresh timer, so refresh maintenance is owned by the
 * session rather than by the one call that issued its tokens. The pairing is deliberate: the same
 * transition that opens the refusal subscription is the transition at which an application becomes able
 * to maintain a session again, and the teardown below cancels the timer at the matching transition, so
 * a page with nothing rendered runs no timer and a page with something rendered always does.
 * @param {() => void} listener - Callback React registers to be told a reading changed.
 * @returns {() => void} The matching unsubscribe, which React calls on teardown.
 */
function subscribe(listener: () => void): () => void {
  /**
   * Stops delivering readings to this listener, closing the shared subscription with the last one.
   * @returns {void} Nothing; the listener is removed and, when it was the last, the subscription to
   *   the transport is released and any pending refresh is cancelled so no timer outlives the
   *   rendered application.
   */
  function unsubscribe(): void {
    listeners.delete(listener);
    if (listeners.size === 0) {
      if (releaseRefusalSubscription !== null) {
        releaseRefusalSubscription();
        releaseRefusalSubscription = null;
      }
      cancelScheduledRefresh();
    }
  }

  listeners.add(listener);
  if (listeners.size === 1) {
    if (releaseRefusalSubscription === null) {
      releaseRefusalSubscription = subscribeToAuthenticationRequired(handleSessionRefused);
    }
    // Assumptions: ⚠️ Refactoring Rationale: the refresh timer is REARMED here, and its absence was a
    //   defect a review named: the sole timer was cancelled with the last subscriber and never armed
    //   again, so a session that outlived a remount kept its renewal token and lost the maintenance
    //   that used it. Every path that reaches this point with a session held is a path on which the
    //   timer may be gone -- a route change that swaps the mounted tree, a development hot reload,
    //   React's strict-mode unmount-and-remount -- and arming from the session rather than from an
    //   issued lifetime is what makes rearming possible at all.
    armRefresh();
  }
  return unsubscribe;
}

/**
 * Exchanges the held refresh token for a new token set, on behalf of one generation.
 *
 * Assumptions: the generation is a PARAMETER rather than read here, because it has to be the one that
 * was current when the exchange was decided on. Reading it at the point of mutation would compare the
 * current generation with itself and admit every stale outcome, which is the defect this guard exists
 * to close.
 * @param {string} userId - Identifier the held tokens were issued for.
 * @param {string} refreshToken - The held refresh token.
 * @param {number} generation - The session generation this renewal belongs to.
 * @returns {Promise<void>} Resolves once the outcome has been applied, or discarded as superseded. A
 *   refused exchange ends the session rather than retrying: the token is either accepted or it is not,
 *   so a retry would re-present a credential already refused, whereas ending the session returns the
 *   operator to a screen that can obtain a working one.
 */
async function exchangeRefreshToken(
  userId: string,
  refreshToken: string,
  generation: number,
): Promise<void> {
  const exchange = beginExchange();
  notifyListeners();
  try {
    installTokens(
      await refreshTokens(userId, refreshToken, currentAbortSignal()),
      generation,
      'RENEWAL',
    );
  } catch (cause: unknown) {
    // Assumptions: the FAILURE path is guarded as well as the success path, and a review named the
    //   reason: a stale failure could clear a session established after this exchange started, so an
    //   operator whose sign-on had just succeeded would be signed straight back out by a renewal
    //   belonging to the session before it. An aborted request arrives here too, and it is superseded
    //   by construction, so the same guard covers it without a second test for cancellation.
    if (generation !== sessionGeneration) {
      return;
    }
    discardSession();
    recordRefusal(cause);
  } finally {
    endExchange(exchange);
    notifyListeners();
  }
}

/**
 * Runs the scheduled refresh, reading the credentials it needs at the moment it fires.
 *
 * Assumptions: the identifier and refresh token are read HERE rather than captured when the timer was
 * armed, because a sign-out or a second sign-on between arming and firing would otherwise present a
 * credential belonging to a session that has already ended. The generation is captured at the same
 * instant and for the same reason, so the outcome is judged against the session that actually owned the
 * credential presented.
 * @returns {void} Nothing; the exchange is started and settles on its own.
 */
function runScheduledRefresh(): void {
  scheduledRefresh = null;
  const session = heldSession;
  if (session === null || session.refreshToken === null) {
    return;
  }
  // Assumptions: an explicit rejection handler rather than a discarded promise, because
  //   `ui/eslint.config.js` overrides `ignoreVoid` to false on `no-floating-promises` so that a
  //   fire-and-forget call has to state what it does with a failure. This is the same shape
  //   `ui/src/screens/signon/index.tsx` uses for its own submission.
  exchangeRefreshToken(session.userId, session.refreshToken, session.generation).catch(
    endSessionAfterFailedRefresh,
  );
}

/**
 * Ends the session when a scheduled refresh failed in a way the exchange did not itself convert.
 *
 * Assumptions: {@link exchangeRefreshToken} already turns every refusal into published state, so this
 * handler runs only on a defect in that conversion. It still ends the session rather than being
 * empty, because an empty handler is how a real fault becomes invisible, and a refresh that could not
 * be completed leaves a bearer nobody has renewed — the same terminal state a refused exchange
 * reaches.
 * @param {unknown} cause - Whatever escaped the exchange.
 * @returns {void} Nothing; the session is discarded and every listener is told.
 */
function endSessionAfterFailedRefresh(cause: unknown): void {
  discardSession();
  recordRefusal(cause);
  notifyListeners();
}

/**
 * Arms a single refresh ahead of the held bearer's expiry, when a renewal token is held.
 *
 * Purpose: ⚠️ Refactoring Rationale: this reads the expiry from the HELD SESSION rather than taking a
 * lifetime as a parameter, and that change is what makes refresh maintenance session-owned. A review
 * found the sole timer cancelled with the last subscriber and never rearmed, so a valid renewal token
 * did not prevent expiry: an application whose last consumer unmounted and remounted — a route change
 * that swaps the mounted tree, a development hot reload, React's own strict-mode remount — kept its
 * session and lost the timer that maintained it, and the next request was refused with a 401 that signed
 * the operator out. Reading the expiry off the session means the timer can be rearmed from the session
 * alone, by anything that observes the session exists, at any later moment.
 *
 * Assumptions: a session already inside the margin is refreshed IMMEDIATELY rather than given a floored
 * delay. That case is now reachable in a way it was not before — rearming happens long after the token
 * was issued, so the remaining life may already be gone — and waiting a second to present a credential
 * that has expired would spend an exchange to be refused.
 * @returns {void} Nothing; at most one timer is pending, because the previous one is cancelled first.
 *   A session with no renewal token arms nothing, because there is nothing to renew it with.
 */
function armRefresh(): void {
  cancelScheduledRefresh();
  const session = heldSession;
  if (session === null || session.refreshToken === null) {
    return;
  }
  const remainingMs =
    session.expiresAt - Date.now() - REFRESH_MARGIN_SECONDS * MILLISECONDS_PER_SECOND;
  if (remainingMs <= 0) {
    runScheduledRefresh();
    return;
  }
  scheduledRefresh = setTimeout(runScheduledRefresh, remainingMs);
}

/**
 * Which kind of exchange produced a token set, which decides whether a held renewal token may survive it.
 *
 * ⚠️ Purpose: this distinction exists because a review found one token set inheriting another
 * PRINCIPAL'S renewal token. It is named rather than expressed as a boolean so that a call site states
 * which exchange it is, and a reader of that call site does not have to work out what `true` meant.
 */
type TokenInstallOrigin = 'FRESH_SIGN_ON' | 'RENEWAL';

/**
 * Installs an issued token set as this tab's session, if the generation that asked for it is current.
 *
 * Refactoring Rationale: no password reaches this function, and none reaches this module's state.
 * `app/cpy/CSUSR01Y.cpy` L21 declares `SEC-USR-PWD PIC X(08)` in plain text and
 * `app/cbl/COSGN00C.cbl` L223 compares it directly against what the terminal supplied; that field has
 * no target here, because identity moved to the managed pool. A password exists in this module only
 * as a parameter handed straight to `ui/src/api/auth.ts`, and what is retained afterwards is the
 * issued token — never the credential that obtained it, in the session, in a reading, or in a failure.
 *
 * Assumptions: ⚠️ Refactoring Rationale: the set is validated as a WHOLE before anything is installed,
 * where this function used to write four independent values and check none of them. A review found two
 * consequences of that. Each write could fail on its own and every failure was swallowed, so a tab could
 * end up holding some of a session; and "signed on" was the presence of the identity token alone, so a
 * tab holding an identity token from one session beside a bearer from another reported itself signed on.
 * Both are closed by the same change: the members are checked, the identity token's subject is compared
 * with the identifier, and only then is one frozen record installed. There is no partial outcome to
 * roll back from, because nothing is written until everything is accepted.
 *
 * Assumptions: the subject comparison is the browser half of the binding the auth service applies to a
 * renewal, and it is applied to EVERY exchange rather than to the renewal alone. A token set whose
 * identity token describes a different operator than the identifier it arrived with is a service fault
 * on any path, and installing it would let the guards read authority from one operator's claim while
 * every request carried another's bearer.
 * @param {SignOnTokens} tokens - Tokens returned by sign-on, the challenge answer or a renewal.
 * @param {number} generation - The session generation the exchange that obtained them belonged to.
 * @param {TokenInstallOrigin} origin - Whether the set came from a fresh sign-on, in which case it
 *   REPLACES everything held, or from a renewal of the session already held, in which case a set arriving
 *   without a renewal token keeps the one held.
 * @returns {boolean} `true` when the session was installed, `false` when the generation had been
 *   superseded and nothing was changed. A caller uses the answer to decide whether to publish an
 *   outcome, never to decide whether the exchange succeeded.
 * @throws {RangeError} When the token set is incomplete, its lifetime is not positive, or its identity
 *   token does not describe the identifier it arrived with — and propagated from
 *   {@link applyBearerToken} when the issued bearer is unusable. Nothing is installed in any of those
 *   cases, so a refused set leaves whatever was held before exactly as it was.
 */
function installTokens(
  tokens: SignOnTokens,
  generation: number,
  origin: TokenInstallOrigin,
): boolean {
  // Assumptions: the guard is FIRST, before the validation and before any mutation, because a
  //   superseded outcome must not even be able to raise. A stale set that failed validation would
  //   otherwise report a refusal against a session it does not belong to.
  if (generation !== sessionGeneration) {
    return false;
  }

  const userId = tokens.userId;
  // Assumptions: each member is narrowed with `typeof` rather than compared against `null`, even though
  //   `SignOnTokens` declares them required. A declared type describes what the contract PROMISES, and
  //   these values crossed a network boundary: a response that OMITS a member leaves it `undefined`,
  //   which passes a `!== null` test and then fails on use. The `typeof` form admits a string and
  //   rejects absent, null and every other shape in one test.
  if (
    typeof userId !== 'string' ||
    userId.length === 0 ||
    typeof tokens.accessToken !== 'string' ||
    tokens.accessToken.length === 0 ||
    typeof tokens.idToken !== 'string' ||
    tokens.idToken.length === 0 ||
    typeof tokens.expiresIn !== 'number' ||
    !Number.isFinite(tokens.expiresIn) ||
    tokens.expiresIn <= 0
  ) {
    throw new RangeError(
      'An issued token set must carry an identifier, an access token, an identity token and a positive' +
        ' lifetime; a set missing any of them cannot establish a session.',
    );
  }

  // Assumptions: the comparison folds both sides to upper case, because the identifier is folded before
  //   it is used as a key -- `app/cbl/COSGN00C.cbl` L133 moves it through `FUNCTION UPPER-CASE` -- and
  //   the pool records the user name in the form the account was created with. Comparing unfolded
  //   values would refuse a correct set for a difference in case the rest of the system ignores.
  const subject = subjectOfIdToken(tokens.idToken);
  if (subject === null || subject.toUpperCase() !== userId.toUpperCase()) {
    throw new RangeError(
      'An issued identity token must describe the identifier it was issued for; a set whose identity' +
        ' token names another operator, or names none, cannot establish a session.',
    );
  }

  // Assumptions: the bearer is installed BEFORE the record, because installing it is the one step that
  //   can still fail -- `applyBearerToken` refuses a blank or control-bearing token -- and a record
  //   installed first would describe a session no request could carry a credential for.
  applyBearerToken(tokens.accessToken);

  /*
   * Assumptions: a RENEWAL without a renewal token keeps the one already held, which is the rotation
   *   tolerance `SignOnTokens` documents: the pool answers a renewal without a replacement where a retry
   *   grace period leaves the submitted token current, and discarding the held one would make the session
   *   unrenewable from that point on.
   * ⚠️ Refactoring Rationale: that tolerance is now applied to a renewal ALONE, where it applied to
   *   every install. A fresh sign-on and a challenge answer both reached it, and neither clears the held
   *   session first -- `supersedeGeneration` moves the generation and aborts what is in flight, and does
   *   not touch `heldSession` -- so an operator signing on at a tab another operator had signed on at,
   *   with an answer that carried no renewal token, was installed WITH THE PREVIOUS OPERATOR'S. From
   *   there the scheduled refresh presents one principal's renewal token under the other's identifier:
   *   at best the pool refuses it and the new operator's session dies at the first renewal, at worst it
   *   is accepted and this tab holds a session neither operator asked for. A challenge answer is the
   *   likeliest arrival without a renewal token, so the state was reachable by the ordinary path.
   *   Alternatives Considered: clearing `heldSession` in `supersedeGeneration` so the fallback would find
   *   nothing on a fresh sign-on. Rejected because that function is also what a renewal's own supersession
   *   goes through, and it is called from `discardSession` where the clear already happens -- making it
   *   clear the session would end the current session every time a sign-on was merely ATTEMPTED, so a
   *   mistyped password would sign the operator out.
   */
  const renewal =
    typeof tokens.refreshToken === 'string' && tokens.refreshToken.length > 0
      ? tokens.refreshToken
      : origin === 'RENEWAL'
        ? (heldSession?.refreshToken ?? null)
        : null;

  heldSession = Object.freeze({
    generation,
    userId,
    idToken: tokens.idToken,
    refreshToken: renewal,
    expiresAt: Date.now() + tokens.expiresIn * MILLISECONDS_PER_SECOND,
  });
  clearFailureState();
  armRefresh();
  notifyListeners();
  return true;
}

/**
 * Signs on with an operator identifier and password.
 *
 * Assumptions: declared at module scope rather than rebuilt inside the hook, so its identity is
 * stable for every caller without a `useCallback` and a submit handler holding it in a dependency
 * list is never re-created. The state it changes is the module's, so there is nothing per-instance for
 * it to close over.
 * @param {string} userId - Operator identifier, as entered.
 * @param {string} password - Operator password, forwarded to `ui/src/api/auth.ts` and not retained.
 * @returns {Promise<SignOnResult>} Tokens when the credential was accepted, or the challenge the pool
 *   raised. A challenge is not a failure and leaves any remembered refusal cleared.
 * @throws {unknown} Re-raises whatever the exchange rejected with, after recording it. The rejection
 *   is deliberately not swallowed: `ui/src/screens/signon/index.tsx` reports the refusal from its own
 *   `catch`, and a screen that awaited a call must not be left to infer the outcome from state.
 */
async function signIn(userId: string, password: string): Promise<SignOnResult> {
  // Assumptions: the generation is opened BEFORE the request, so this sign-on supersedes anything
  //   already in flight -- an earlier sign-on the operator retried past, and any renewal belonging to a
  //   session being replaced. Opening it here also abandons those requests, so a credential for the
  //   previous session stops travelling the moment a new one is submitted.
  const generation = supersedeGeneration();
  const exchange = beginExchange();
  notifyListeners();
  try {
    const result = await signOn(userId, password, currentAbortSignal());
    if (result.outcome === SIGN_ON_AUTHENTICATED) {
      installTokens(result, generation, 'FRESH_SIGN_ON');
    } else if (generation === sessionGeneration) {
      // Assumptions: a challenge is not a failure, so the remembered refusal is cleared -- but only
      //   while this sign-on is still the current one. Clearing it from a superseded exchange would
      //   erase the refusal a LATER sign-on had just recorded, which is the mirror of the stale-failure
      //   defect the guard above closes.
      clearFailureState();
    }
    return result;
  } catch (cause: unknown) {
    if (generation === sessionGeneration) {
      recordRefusal(cause);
    }
    throw cause;
  } finally {
    endExchange(exchange);
    notifyListeners();
  }
}

/**
 * Answers an outstanding challenge, completing sign-on.
 * @param {SignOnChallenge} challenge - The challenge being answered, as sign-on returned it.
 * @param {string} newPassword - Replacement password, forwarded onward and not retained.
 * @returns {Promise<SignOnTokens>} The issued tokens, returned so a caller can route on their claim
 *   without waiting for a re-render.
 * @throws {unknown} Re-raises whatever the exchange rejected with, after recording it, for the same
 *   reason as {@link signIn}.
 */
async function answerChallenge(
  challenge: SignOnChallenge,
  newPassword: string,
): Promise<SignOnTokens> {
  // Assumptions: the generation is NOT opened here, where sign-on opens one. This exchange COMPLETES
  //   the sign-on that raised the challenge rather than starting a new attempt, so it belongs to the
  //   generation that sign-on opened; opening a second would supersede the very exchange whose session
  //   handle it is presenting. What it does capture is that generation, so a sign-out or a fresh sign-on
  //   while the operator is choosing a password still supersedes the answer.
  const generation = sessionGeneration;
  const exchange = beginExchange();
  notifyListeners();
  try {
    const tokens = await answerSignOnChallenge(
      challenge.userId,
      challenge.session,
      newPassword,
      currentAbortSignal(),
    );
    /*
     * WHY : ⚠️ Assumptions: a challenge answer is a FRESH sign-on for this purpose, even though it
     *       completes the sign-on that raised the challenge rather than starting a new attempt. What the
     *       origin decides is whether a held renewal token may survive the install, and the session being
     *       established here is not the one any held token belongs to -- it is the first session this
     *       operator has had at this tab. It is also the arrival LEAST likely to carry a renewal token,
     *       which is what made it the reachable path into the defect.
     */
    installTokens(tokens, generation, 'FRESH_SIGN_ON');
    return tokens;
  } catch (cause: unknown) {
    if (generation === sessionGeneration) {
      recordRefusal(cause);
    }
    throw cause;
  } finally {
    endExchange(exchange);
    notifyListeners();
  }
}

/**
 * Discards every credential held for this tab AND revokes the refresh token at the identity provider.
 *
 * Refactoring Rationale: this function used to clear locally and tell the provider nothing, which meant
 * signing out ended a session only in the tab that ended it. The refresh token is provisioned with a
 * thirty-day life -- `refresh_token_validity_days` defaults to 30 in
 * `infra/modules/cognito/variables.tf` -- so a copy of it taken from the session store, a synchronised
 * browser profile or a shared workstation went on minting one-hour access tokens for a month after the
 * operator believed the session was over, and nothing an operator or an administrator could do from this
 * application would stop it. The revocation added below is what makes a sign-out an event at the provider.
 *
 * Assumptions: the local clear is UNCONDITIONAL and happens first. A session the operator has ended must
 * not survive in the tab because the network did not cooperate, so the revocation is dispatched after the
 * store has already been emptied and its failure changes nothing here. The alternative -- awaiting the
 * revocation and clearing on success -- was rejected outright: it would leave a signed-out operator
 * signed on whenever the provider was unreachable, which is the worse of the two failures by a wide
 * margin.
 *
 * Assumptions: the token is read BEFORE the clear, because {@link discardSession} drops the record it
 * lives on. Reading afterwards would revoke nothing and would do so silently.
 *
 * Trade-offs: this does NOT navigate, and the omission is deliberate. `EXEC CICS XCTL` maps to a
 * client route change under Transformation Rule T5, but the route change belongs to the caller:
 * `ui/src/routes/guards.tsx` already sends a caller with no session to the sign-on screen, so
 * navigating from here would put that decision in two places. Calling `useNavigate` would also make
 * this module unusable outside a router context, and `ui/src/router.tsx` must stay importable as
 * data by tests that mount no router at all.
 *
 * Trade-offs: it also does not become asynchronous, so its two callers -- the application shell's
 * sign-off action and the sign-on screen's own reset -- keep a statement call. Returning a promise would
 * make both call sites floating promises that `ui/eslint.config.js` refuses, for a settlement neither
 * caller can act on: the local half is already done, and the remote half has no outcome a screen would
 * render differently.
 * @returns {void} Nothing; the reading that follows is anonymous, with no remembered failure, and
 *   every mounted caller is told at once. The revocation settles afterwards and reports nothing.
 */
function signOut(): void {
  const heldRefreshToken = heldSession?.refreshToken ?? null;
  discardSession();
  clearFailureState();
  notifyListeners();
  if (heldRefreshToken === null) {
    return;
  }
  // Assumptions: an explicit rejection handler rather than a discarded promise, for the reason
  //   `runScheduledRefresh` records -- `ui/eslint.config.js` overrides `ignoreVoid` to false on
  //   `no-floating-promises`, so a fire-and-forget call has to state what it does with a failure.
  revokeRefreshToken(heldRefreshToken).catch(reportUnrevokedToken);
}

/**
 * Reports a revocation the provider did not complete, without disturbing the sign-out that requested it.
 *
 * Assumptions: this is deliberately not a re-throw and deliberately not store state. The sign-out has
 * already succeeded in every respect a caller can observe -- the store is empty, the reading is anonymous
 * and every listener has been told -- so surfacing the failure would report a completed action as failed
 * and would give a screen nothing to do about it. What is lost is that the token remains live at the
 * provider until it expires, which is why the condition is recorded rather than swallowed silently: a
 * console entry is the only channel available to a module that must not raise and must not navigate.
 *
 * Trade-offs: `console` is used because this tree carries no client-side telemetry sink, and inventing
 * one for a single diagnostic would be a larger decision than this warrants. The line names the condition
 * and no credential -- the token is not interpolated, because a console entry is copied into bug reports.
 *
 * Alternatives Considered: routing this through {@link recordRefusal}, which is what
 * `endSessionAfterFailedRefresh` does for the comparable refresh failure. Rejected because that function
 * latches `exchangeWasRefused` and the problem document's own sentence, so the sign-on screen the operator
 * has just been returned to would display the provider's credential wording -- "Unable to verify the
 * User ..." for the 500 this can produce -- as though the sign-out had been refused. The sign-out was not
 * refused; only its remote half was, and the operator has no action to take about it.
 * @param {unknown} cause - Whatever the revocation rejected with, recorded for diagnosis.
 * @returns {void} Nothing; the sign-out is unaffected.
 */
function reportUnrevokedToken(cause: unknown): void {
  // Assumptions: no `eslint-disable` directive accompanies this call, and its absence is deliberate
  //   rather than an oversight. `ui/eslint.config.js` sets `noInlineConfig: true`, so a directive here
  //   would have no effect and would itself be reported; the configuration enables no `no-console` rule
  //   for it to suppress in any case.
  console.warn('carddemo: sign-out completed locally but the refresh token was not revoked', cause);
}

/*
 * WHY : ⚠️ Refactoring Rationale: a `getAccessToken` reader and an `AUTH_STORAGE_KEY` constant stood
 *       here and are both withdrawn. Each existed to let a second module reach the bearer through the
 *       storage slot that held it -- the constant so two files would name one key rather than two
 *       copies of a string, the reader so a caller outside React could take its value. Neither has a
 *       consumer, and neither can have one now: the bearer is held in memory by
 *       `ui/src/api/client.ts`, which is the only module that needs it because it is the module that
 *       attaches it, and there is no key left to name. Publishing a reader for a credential nobody
 *       reads would be a second route to the value for no caller, which is the shape of exposure the
 *       move into memory exists to remove.
 * WHY : Assumptions: the reasoning the withdrawn reader carried about EXPIRY is preserved because it is
 *       still the design. A token whose lifetime has elapsed is not withheld on the browser's own
 *       judgement -- the browser clock is not authoritative, and a workstation running a few minutes
 *       fast would report no session while every service still accepted the token. Expiry is handled
 *       from both ends instead: `armRefresh` renews the bearer ahead of it, and a token nonetheless
 *       presented after it is refused by the service with a 401 that `handleSessionRefused` acts on,
 *       which is the authority of the party that actually decides. The cost accepted is one refused
 *       request in that window.
 */

/**
 * The composed result most recently returned, retained so an unchanged reading returns one object.
 */
let cachedResult: UseAuthResult | null = null;

/** The reading {@link cachedResult} was composed from, used to detect a real change. */
let cachedResultSnapshot: AuthSnapshot | null = null;

/**
 * Combines a reading with the three actions, reusing the previous object for an unchanged reading.
 * @param {AuthSnapshot} snapshot - The reading to expose.
 * @returns {UseAuthResult} The frozen result, referentially equal to the previous one when the
 *   reading has not changed, so a caller may pass it to a memoised child without re-rendering it.
 */
function composeResult(snapshot: AuthSnapshot): UseAuthResult {
  if (cachedResult !== null && cachedResultSnapshot === snapshot) {
    return cachedResult;
  }
  cachedResultSnapshot = snapshot;
  cachedResult = Object.freeze({
    ...snapshot,
    signIn,
    answerChallenge,
    signOut,
  });
  return cachedResult;
}

/**
 * Exposes the signed-on operator and the operations that change who that is.
 *
 * Alternatives Considered: a React context with an `AuthProvider`, which is the idiomatic shape and
 * was rejected on two counts. `ui/src/App.tsx` is authored to own no global state store and mounts no
 * such provider, so every call would fall back to a default or throw; and `ui/src/router.tsx` must
 * stay importable as data by tests that render no tree, which a context-dependent hook would break.
 * A module-scoped store surfaced through `useSyncExternalStore` needs no provider, works from any
 * component, and is what lets the guards be called on an unauthenticated first load.
 *
 * Refactoring Rationale: the state was previously held per instance in `useState`, so each caller had
 * its own copy of a single fact. Storage was shared but React state was not, so a sign-out performed
 * through one caller left the others reporting the previous session until something unrelated
 * re-rendered them — a guard in that state keeps rendering a protected screen for an operator whose
 * every request is refused. One store with one notification removes the possibility rather than
 * making it less likely.
 *
 * Trade-offs: ⚠️ session state lives in MEMORY and in no browser store, so nothing a cross-site script
 * could read holds a credential — and a page reload ends the session. That is the whole of the exchange
 * and both halves are real. This paragraph previously argued the opposite, that `sessionStorage` was
 * worth its exposure because memory alone would sign the operator out on every reload; a review
 * overturned it on the specific ground that the rationale offered for the exposure was FALSE. The claim
 * was that the content-security policy admits no third-party script and so removes the supply-chain
 * path to a reader. It does not: `ui/nginx.conf` permits `'self'`, and every third-party package in this
 * bundle is compiled INTO the self-hosted bundle, so a compromised dependency executes as `'self'` and
 * reads any store this origin can. The policy bounds where script may be FETCHED from; it says nothing
 * about what the script that is already there may read. With the rationale gone the exposure had no
 * defence, and holding nothing is the resolution.
 *
 * Trade-offs: the reload cost is accepted for two reasons. This application is used at shared
 * workstations — the setting the withdrawn `localStorage` argument already invoked — and a credential
 * that survives a reload also survives an operator walking away and someone else pressing a key. And the
 * operator's remedy is one credential entry at a screen this application already renders and already
 * routes to, whereas the exposure's remedy was nothing the operator could do. The difference from the
 * baseline is registered as `D-SESSION-NOT-PERSISTED` in
 * `docs/architecture/cobol-to-service-traceability.md`, because a 3270 session did survive far more than
 * a refresh and that is a behavioural difference rather than an implementation one.
 *
 * Assumptions: the session is ONE frozen record and no part of it is written separately, named here
 * because "held in memory" does not tell a reader what a sign-out discards — the identifier, the
 * identity token, the renewal token when the pool issued one, the bearer's expiry instant and the
 * generation the record belongs to, with the bearer itself held beside them by `ui/src/api/client.ts`.
 * All of it is discarded together, on an explicit sign-out, on a renewal the pool refuses, and on any
 * 401 answered to a request that carried a bearer. `ui/.env.example` states the same for a reader who
 * never opens this file.
 * @returns {UseAuthResult} The current session and the operations that change it: `status`, one of
 *   {@link AuthStatus}; `isAuthenticated` and its established alias `signedOn`, both true while an
 *   validated token set is held; `userId`, the eight-character identifier the tokens were issued for or
 *   `null`; `groups`, the claim's group names as a frozen `readonly` array; `isAdmin`, true when those
 *   include {@link CARDDEMO_ADMIN_GROUP}; `error`, the problem document from the last refusal that
 *   carried one, or `null`; `signIn` and `answerChallenge`, which establish a session and re-raise a
 *   refusal to their caller; and `signOut`, which discards everything without navigating. The object
 *   and its group array are frozen, and no member is a setter for an identity value — authority comes
 *   from the signed claim alone, for the reason argued in this file's overview.
 */
export function useAuth(): UseAuthResult {
  // Assumptions: the same reader is passed as the server snapshot, because it reads a module variable
  //   and depends on no browser API at all -- which is a property the move into memory GAINED, since the
  //   storage read it replaced could throw where an origin is denied a store. Omitting the
  //   third argument would make this hook throw if the tree were ever rendered on a server, which is a
  //   failure mode with no upside; supplying it degrades to the anonymous reading instead.
  return composeResult(useSyncExternalStore(subscribe, getSnapshot, getSnapshot));
}

/**
 * Discards the held session WITHOUT contacting the identity provider, returning the module to its
 * loaded state.
 *
 * Purpose
 * -------
 * A test file runs several cases against one module instance, and the session this module holds is
 * module state rather than component state — it deliberately outlives every component that observes it,
 * because that is what lets a screen unmount and remount without ending the operator's session. One
 * case establishing a session would therefore leave the next case signed on as whoever the first case
 * signed on as.
 *
 * Refactoring Rationale: this exists because the session used to live in `sessionStorage`, so a file
 * could return the module to its loaded state with `sessionStorage.clear()` and the reset needed no
 * published surface. The session is now held in memory and nothing outside this module can reach it, so
 * the reset has to be published or the isolation those files depend on is simply lost. This is the same
 * bargain `resetApiClient` in `ui/src/api/client.ts` already strikes for the same reason.
 *
 * ⚠️ Alternatives Considered: having those files call {@link useAuth}'s own `signOut`. Rejected because
 * it DISPATCHES a revocation, so every case would have to arrange an answer for a request it has no
 * interest in, and one that carried a held token would count against its own request assertions. The
 * second count that stood here -- that `signOut` "is asynchronous, so a synchronous `beforeEach` could
 * not complete it" -- was false: it is declared `signOut(): void`, and it clears locally and
 * synchronously before dispatching the revocation without awaiting it. What a fixture actually needs is a
 * reset that talks to NOTHING, which is the count above; the withdrawn one would have had a reader
 * looking for an `await` that no signature admits.
 *
 * Assumptions: this is a CLEARER and not a writer, which is the whole reason it is safe to publish. It
 * can only move the module towards holding nothing, so no caller can use it to arrange a session that
 * {@link installTokens} would have refused — which is exactly the drift a published installer would
 * have permitted. A test that needs a session must still perform an exchange.
 * Assumptions: what is in flight is emptied as well as the session discarded, because the published
 * status is DERIVED and an exchange in flight dominates that derivation. A case whose exchange was
 * abandoned rather than awaited would otherwise leave a membership standing, and the next case would read
 * `authenticating` from a module holding nothing at all.
 *
 * ⚠️ Assumptions: the abandoned exchanges still run their own `finally` after this returns, and that is
 * why what is in flight is a SET of identities rather than a count. Each retires a membership this has
 * already removed, which is a no-op; against a counter each decremented below zero, and a negative count
 * reads as "nothing in flight" while an exchange is genuinely running.
 * @returns {void} Nothing; no session is held, nothing in flight can install one, and the next reading
 *   is the anonymous one. Listeners are notified, so a mounted component re-renders.
 */
export function resetAuthSession(): void {
  discardSession();
  clearFailureState();
  exchangesInFlight.clear();
  notifyListeners();
}
