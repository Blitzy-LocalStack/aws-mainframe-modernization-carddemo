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
 * `ui/src/api/client.ts` owns the bearer and correlation headers and the access-token slot.
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

import { SIGN_ON_AUTHENTICATED, answerSignOnChallenge, refreshTokens, signOn } from '../api/auth';
import type { SignOnChallenge, SignOnResult, SignOnTokens } from '../api/auth';
import {
  isApiRequestError,
  setAccessToken,
  subscribeToAuthenticationRequired,
} from '../api/client';
import type { ApiRequestError } from '../api/client';
import type { ApiError } from '../api/types';

/**
 * Session-storage slot holding the bearer token, named once so two modules cannot drift apart.
 *
 * Assumptions: this value must equal the private `ACCESS_TOKEN_STORAGE_KEY` at
 * `ui/src/api/client.ts` L82, because that module's request interceptor reads this exact slot to
 * attach `Authorization: Bearer` and is the only WRITER of it. The literal is restated here rather
 * than imported because that module does not export it, and it is exported here rather than left
 * private because the name is a cross-module contract: `ui/src/api/client.ts` and four test files
 * each spell it out independently today, which is precisely the drift this constant exists to end.
 * A reader adding a fifth site should import this name instead of retyping the string.
 */
export const AUTH_STORAGE_KEY = 'carddemo.access-token';

/**
 * Session-storage slot holding the identity token, which is the sole source of the group claim.
 *
 * Assumptions: the presence of THIS token, not the bearer, is what "signed on" means here. The two
 * are cleared together on sign-out and on a refused session, but a bearer can be absent while a
 * session is live — a sign-on request necessarily carries none — so deriving the signed-on state
 * from the bearer would report an operator signed off in the middle of signing on.
 */
const ID_TOKEN_STORAGE_KEY = 'carddemo.id-token';

/** Session-storage slot holding the identifier the held tokens were issued for. */
const USER_ID_STORAGE_KEY = 'carddemo.user-id';

/** Session-storage slot holding the refresh token, present only when the pool issued one. */
const REFRESH_TOKEN_STORAGE_KEY = 'carddemo.refresh-token';

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

  /** Discards every credential and identity value held for this tab. */
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
 * Reads one session-storage value, treating an unavailable store as an absent value.
 *
 * Assumptions: every storage access in this module is guarded, because reading `sessionStorage`
 * THROWS rather than returning null in a privacy mode that denies storage to the origin, and this
 * function is on the path React takes during render. An unguarded read there would turn a browser
 * setting into a blank application; degrading to "nobody is signed on" sends the operator to the
 * sign-on screen instead, which is a state the application already knows how to present.
 * @param {string} key - Session-storage key to read.
 * @returns {string | null} The stored value, or `null` when absent or when the store is unavailable.
 */
function readSessionValue(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    // Assumptions: the failure is swallowed rather than reported because there is nothing for a
    //   caller to do differently. A denied store cannot hold a session at all, so every reading is
    //   correctly anonymous and every write below is correctly a no-op.
    return null;
  }
}

/**
 * Writes one session-storage value, tolerating an unavailable store.
 * @param {string} key - Session-storage key to write.
 * @param {string} value - Value to store.
 * @returns {void} Nothing; the value is stored when the store admits it. A denied or full store
 *   leaves the session unheld, so the next reading reports anonymous rather than reporting a session
 *   that was never persisted.
 */
function writeSessionValue(key: string, value: string): void {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // Assumptions: swallowed for the same reason as the read above, with one addition -- a quota
    //   failure is indistinguishable here from a denied store, and neither is actionable by a
    //   caller holding a token it cannot persist.
  }
}

/**
 * Removes one session-storage value, tolerating an unavailable store.
 * @param {string} key - Session-storage key to remove.
 * @returns {void} Nothing; the value is gone, or was never held because the store is unavailable.
 */
function removeSessionValue(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    // Assumptions: swallowed because a store that cannot be read cannot be holding the value this
    //   call is removing, so the intended end state already holds.
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
  const segments = idToken.split('.');
  // Assumptions: the claim segment is taken through an explicit undefined check rather than by
  //   indexing after a length test, because `noUncheckedIndexedAccess` types every element access as
  //   possibly undefined and a non-null assertion would reassert what the length test only implies
  //   -- which is exactly the kind of claim a decoder reading an untrusted string must not make.
  const claimSegment = segments.length === JWT_SEGMENT_COUNT ? segments[1] : undefined;
  if (claimSegment === undefined) {
    return NO_GROUPS;
  }
  try {
    // Assumptions: base64url differs from base64 in two substitutions and in dropping the padding,
    //   so both are undone before `atob`, which accepts only the padded standard alphabet.
    const payload = claimSegment.replace(/-/gu, '+').replace(/_/gu, '/');
    const padded = payload.padEnd(Math.ceil(payload.length / BASE64_QUANTUM) * BASE64_QUANTUM, '=');
    const decoded: unknown = JSON.parse(atob(padded));
    if (typeof decoded !== 'object' || decoded === null) {
      return NO_GROUPS;
    }
    const claim: unknown = (decoded as Record<string, unknown>)[COGNITO_GROUPS_CLAIM];
    if (!Array.isArray(claim)) {
      // Assumptions: a claim present but not an array -- a lone group serialised as a bare string is
      //   the realistic case -- yields no groups rather than being coerced. Coercing would let a
      //   shape this application has not agreed with the pool decide an authority question.
      return NO_GROUPS;
    }
    return Object.freeze(claim.filter(isGroupName));
  } catch {
    // Assumptions: one catch covers `atob` rejecting a non-base64 segment and `JSON.parse` rejecting
    //   the bytes it yields, because the two are indistinguishable to a caller and have the same
    //   correct answer. This is the path `ui/src/routes/guards.tsx`'s undecodable-token case takes.
    return NO_GROUPS;
  }
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
 * Number of sign-on, challenge or refresh exchanges currently in flight.
 *
 * Assumptions: a count rather than a boolean, so two exchanges overlapping — a scheduled refresh
 * firing while an operator re-submits sign-on — cannot have the first to settle report the session
 * as no longer authenticating while the second is still running.
 */
let exchangesInFlight = 0;

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
  if (exchangesInFlight > 0) {
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
 * Assumptions: session storage is the AUTHORITY for what is held, and this function reads it on
 * every call rather than mirroring it into a variable at module load. Three things depend on that.
 * `ui/src/api/client.ts`'s request interceptor reads the same store, so a mirror could disagree with
 * what the next request actually carries. A reading taken at module load would be taken before a
 * caller — a test writing the four keys, or a second tab's own bundle — had written anything, and
 * would then be wrong for the lifetime of the page. And because there is exactly one store, two
 * mounted callers cannot hold different answers, which is the defect a per-instance `useState` model
 * has: a sign-out performed through one instance left every other instance reporting the previous
 * session until something unrelated re-rendered it.
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
  const idToken = readSessionValue(ID_TOKEN_STORAGE_KEY);
  const userId = readSessionValue(USER_ID_STORAGE_KEY);
  const status = deriveStatus(idToken !== null);
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
  const authenticated = idToken !== null;
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
 * Assumptions: the token is written through `setAccessToken` in `ui/src/api/client.ts` rather than
 * to {@link AUTH_STORAGE_KEY} directly, so that module stays the single WRITER of the slot its own
 * request interceptor reads. Its L1919 to L1927 docstring fixes the direction — it owns the token and
 * this module calls IN — and honouring it is what keeps the import graph acyclic: this module already
 * imports that one, so an import back would close a cycle between a hook and the transport its
 * requests are dispatched by. {@link getAccessToken} below therefore only READS.
 * @param {string | null} token - Bearer token to store, or `null` to discard the stored one.
 * @returns {void} Nothing; the slot holds the token, or holds nothing.
 * @throws {RangeError} If a non-null token is blank or carries control characters. The error is
 *   allowed out because a service that issued an unusable bearer has not established a session, so
 *   the caller must see the refusal rather than proceed with a half-installed one.
 */
function applyBearerToken(token: string | null): void {
  try {
    setAccessToken(token);
  } catch (cause: unknown) {
    if (cause instanceof RangeError) {
      throw cause;
    }
    // Assumptions: anything that is not a RangeError came from the store itself rather than from the
    //   token's shape, and is swallowed for the reason given on `writeSessionValue` -- a tab that
    //   cannot persist a bearer reads as anonymous, which the application already handles.
  }
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
 * Discards every credential and identity value held for this tab.
 *
 * Refactoring Rationale: this clears EVERYTHING, and the totality is taken from the baseline rather
 * than chosen. `app/cbl/COMEN01C.cbl` L196 to L203 returns to the sign-on program with
 * `XCTL PROGRAM(CDEMO-TO-PROGRAM)` carrying NO `COMMAREA` clause, which is the one transfer in the
 * estate that omits it — compare L184 to L187 in the same program and `app/cbl/COSGN00C.cbl` L231 to
 * L234, which both pass it. The next program therefore starts with `EIBCALEN = 0` and no identity at
 * all. A partial clear here, dropping the bearer while leaving the identity token in place, would
 * leave `ui/src/routes/guards.tsx` reading authority from a session that no longer exists.
 * @returns {void} Nothing; the four keys are gone and no refresh is pending. The caller notifies,
 *   because the two callers differ in what they do to the remembered failure.
 */
function clearSession(): void {
  cancelScheduledRefresh();
  applyBearerToken(null);
  removeSessionValue(ID_TOKEN_STORAGE_KEY);
  removeSessionValue(USER_ID_STORAGE_KEY);
  removeSessionValue(REFRESH_TOKEN_STORAGE_KEY);
  cachedIdToken = null;
  groupsCacheToken = null;
  groupsCacheValue = NO_GROUPS;
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
  clearSession();
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
  if (listeners.size === 1 && releaseRefusalSubscription === null) {
    releaseRefusalSubscription = subscribeToAuthenticationRequired(handleSessionRefused);
  }
  return unsubscribe;
}

/**
 * Exchanges the retained refresh token for a new token set.
 * @param {string} userId - Identifier the retained tokens were issued for.
 * @param {string} refreshToken - The retained refresh token.
 * @returns {Promise<void>} Resolves once the outcome has been applied. A refused exchange signs the
 *   operator out rather than retrying: the token is either accepted or it is not, so a retry would
 *   re-present a credential already refused, whereas signing out returns the operator to a screen
 *   that can obtain a working one.
 */
async function exchangeRefreshToken(userId: string, refreshToken: string): Promise<void> {
  exchangesInFlight += 1;
  notifyListeners();
  try {
    adoptTokens(await refreshTokens(userId, refreshToken));
  } catch (cause: unknown) {
    clearSession();
    recordRefusal(cause);
  } finally {
    exchangesInFlight -= 1;
    notifyListeners();
  }
}

/**
 * Runs the scheduled refresh, re-reading the credentials it needs at the moment it fires.
 *
 * Assumptions: the identifier and refresh token are read HERE rather than captured when the timer was
 * armed, because a sign-out or a second sign-on between arming and firing would otherwise present a
 * credential belonging to a session that has already ended.
 * @returns {void} Nothing; the exchange is started and settles on its own.
 */
function runScheduledRefresh(): void {
  scheduledRefresh = null;
  const userId = readSessionValue(USER_ID_STORAGE_KEY);
  const refreshToken = readSessionValue(REFRESH_TOKEN_STORAGE_KEY);
  if (userId === null || refreshToken === null) {
    return;
  }
  // Assumptions: an explicit rejection handler rather than a discarded promise, because
  //   `ui/eslint.config.js` overrides `ignoreVoid` to false on `no-floating-promises` so that a
  //   fire-and-forget call has to state what it does with a failure. This is the same shape
  //   `ui/src/screens/signon/index.tsx` uses for its own submission.
  exchangeRefreshToken(userId, refreshToken).catch(endSessionAfterFailedRefresh);
}

/**
 * Ends the session when a scheduled refresh failed in a way the exchange did not itself convert.
 *
 * Assumptions: {@link exchangeRefreshToken} already turns every refusal into store state, so this
 * handler runs only on a defect in that conversion. It still ends the session rather than being
 * empty, because an empty handler is how a real fault becomes invisible, and a refresh that could not
 * be completed leaves a bearer nobody has renewed — the same terminal state a refused exchange
 * reaches.
 * @param {unknown} cause - Whatever escaped the exchange.
 * @returns {void} Nothing; the session is discarded and every listener is told.
 */
function endSessionAfterFailedRefresh(cause: unknown): void {
  clearSession();
  recordRefusal(cause);
  notifyListeners();
}

/**
 * Arms a single refresh ahead of the bearer's expiry, when a refresh token is held.
 * @param {number} expiresInSeconds - Lifetime the service reported for the issued bearer.
 * @returns {void} Nothing; at most one timer is pending, because the previous one is cancelled first.
 */
function scheduleRefresh(expiresInSeconds: number): void {
  cancelScheduledRefresh();
  if (readSessionValue(REFRESH_TOKEN_STORAGE_KEY) === null) {
    return;
  }
  // Assumptions: the floor of one second covers a service reporting a lifetime shorter than the
  //   margin. A negative delay would fire immediately and, with a token already at expiry, would
  //   spend an exchange to be refused; one second at least lets the caller that triggered sign-on
  //   settle first.
  const delaySeconds = Math.max(expiresInSeconds - REFRESH_MARGIN_SECONDS, 1);
  scheduledRefresh = setTimeout(runScheduledRefresh, delaySeconds * MILLISECONDS_PER_SECOND);
}

/**
 * Installs an issued token set for this tab and publishes the new reading.
 *
 * Refactoring Rationale: no password reaches this function, and none reaches this module's state.
 * `app/cpy/CSUSR01Y.cpy` L21 declares `SEC-USR-PWD PIC X(08)` in plain text and
 * `app/cbl/COSGN00C.cbl` L223 compares it directly against what the terminal supplied; that field has
 * no target here, because identity moved to the managed pool. A password exists in this module only
 * as a parameter handed straight to `ui/src/api/auth.ts`, and what is retained afterwards is the
 * issued token — never the credential that obtained it, in the store, in a reading, or in a failure.
 * @param {SignOnTokens} tokens - Tokens returned by sign-on, challenge or refresh.
 * @returns {void} Nothing; the bearer is installed through its owning module, the identity token and
 *   identifier are stored, and a refresh token is stored when one was issued. An issued set without a
 *   refresh token leaves any earlier one in place, because ending a session is `clearSession`'s job
 *   and doing part of it here would make two functions responsible for one outcome.
 * @throws {RangeError} Propagated from {@link applyBearerToken} when the issued bearer is unusable.
 *   Nothing is stored in that case, because the bearer is installed first.
 */
function adoptTokens(tokens: SignOnTokens): void {
  applyBearerToken(tokens.accessToken);
  writeSessionValue(ID_TOKEN_STORAGE_KEY, tokens.idToken);
  writeSessionValue(USER_ID_STORAGE_KEY, tokens.userId);
  // Assumptions: the refresh token is narrowed with `typeof` rather than compared against `null`,
  //   even though `SignOnTokens` declares it `string | null`. A declared type describes what the
  //   contract PROMISES, and this value crossed a network boundary: a response that OMITS the member
  //   leaves it `undefined`, which passes a `!== null` test and then throws on `.length`. The
  //   `typeof` form admits a string and rejects absent, null and every other shape in one test, so a
  //   response that under-delivers against the contract costs a refresh rather than the sign-on.
  if (typeof tokens.refreshToken === 'string' && tokens.refreshToken.length > 0) {
    writeSessionValue(REFRESH_TOKEN_STORAGE_KEY, tokens.refreshToken);
  }
  clearFailureState();
  scheduleRefresh(tokens.expiresIn);
  notifyListeners();
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
  exchangesInFlight += 1;
  notifyListeners();
  try {
    const result = await signOn(userId, password);
    if (result.outcome === SIGN_ON_AUTHENTICATED) {
      adoptTokens(result);
    } else {
      clearFailureState();
    }
    return result;
  } catch (cause: unknown) {
    recordRefusal(cause);
    throw cause;
  } finally {
    exchangesInFlight -= 1;
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
  exchangesInFlight += 1;
  notifyListeners();
  try {
    const tokens = await answerSignOnChallenge(challenge.userId, challenge.session, newPassword);
    adoptTokens(tokens);
    return tokens;
  } catch (cause: unknown) {
    recordRefusal(cause);
    throw cause;
  } finally {
    exchangesInFlight -= 1;
    notifyListeners();
  }
}

/**
 * Discards every credential and identity value held for this tab.
 *
 * Trade-offs: this does NOT navigate, and the omission is deliberate. `EXEC CICS XCTL` maps to a
 * client route change under Transformation Rule T5, but the route change belongs to the caller:
 * `ui/src/routes/guards.tsx` already sends a caller with no session to the sign-on screen, so
 * navigating from here would put that decision in two places. Calling `useNavigate` would also make
 * this module unusable outside a router context, and `ui/src/router.tsx` must stay importable as
 * data by tests that mount no router at all.
 * @returns {void} Nothing; the reading that follows is anonymous, with no remembered failure, and
 *   every mounted caller is told at once.
 */
function signOut(): void {
  clearSession();
  clearFailureState();
  notifyListeners();
}

/**
 * Reads the bearer token held for this tab, for callers that cannot use a hook.
 *
 * Assumptions: exported as a plain function because the request interceptor in
 * `ui/src/api/client.ts` runs OUTSIDE React and so can never call `useAuth`. It reads the same slot
 * this function names, which is why {@link AUTH_STORAGE_KEY} is exported alongside it: the two files
 * agree on one name rather than on two copies of a string.
 *
 * Alternatives Considered: rejecting a token whose `exp` claim has passed, and returning `null` for
 * it. That was rejected because the browser clock is not authoritative — a workstation running a few
 * minutes fast would report no session while the services still accept the token, signing an operator
 * out of a live session for a reason no server agrees with. Expiry is handled instead from both ends:
 * {@link scheduleRefresh} renews the bearer ahead of expiry, and if it is nonetheless presented after
 * expiry the service answers 401 and `handleSessionRefused` ends the session on the authority of the
 * party that actually decides. The cost accepted is one refused request in that window.
 * @returns {string | null} The stored bearer token, or `null` when none is held or the store is
 *   unavailable.
 */
export function getAccessToken(): string | null {
  return readSessionValue(AUTH_STORAGE_KEY);
}

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
 * Trade-offs: session state lives in `sessionStorage` rather than in memory alone, so a reload does
 * not sign the operator out. The cost is that the token is readable by script on this origin; the
 * content-security policy admits no third-party script, and the alternative would sign an operator out
 * on every refresh, which the 3270 workflow this replaces never did. `sessionStorage` rather than
 * `localStorage` bounds the exposure to the TAB and has the browser discard it when the tab closes,
 * which is the same total-reset semantic as `app/cbl/COMEN01C.cbl` L196 to L203; a session in
 * `localStorage` would outlive the operator at a shared workstation.
 *
 * Assumptions: four keys carry the session, named here because "held in `sessionStorage`" does not
 * tell a reader what to clear or what a cross-site script could reach — `carddemo.access-token`
 * (written and cleared through `setAccessToken` in `ui/src/api/client.ts`), `carddemo.id-token`,
 * `carddemo.user-id`, and `carddemo.refresh-token` when the pool issued one. All four are discarded
 * together, on an explicit sign-out, on a refresh the pool refuses, and on any 401 answered to a
 * request that carried a bearer. `ui/.env.example` states the same list for a reader who never opens
 * this file.
 * @returns {UseAuthResult} The current session and the operations that change it: `status`, one of
 *   {@link AuthStatus}; `isAuthenticated` and its established alias `signedOn`, both true while an
 *   identity token is held; `userId`, the eight-character identifier the tokens were issued for or
 *   `null`; `groups`, the claim's group names as a frozen `readonly` array; `isAdmin`, true when those
 *   include {@link CARDDEMO_ADMIN_GROUP}; `error`, the problem document from the last refusal that
 *   carried one, or `null`; `signIn` and `answerChallenge`, which establish a session and re-raise a
 *   refusal to their caller; and `signOut`, which discards everything without navigating. The object
 *   and its group array are frozen, and no member is a setter for an identity value — authority comes
 *   from the signed claim alone, for the reason argued in this file's overview.
 */
export function useAuth(): UseAuthResult {
  // Assumptions: the same reader is passed as the server snapshot, because it depends on no browser
  //   API beyond the guarded storage read, which answers `null` where there is no store. Omitting the
  //   third argument would make this hook throw if the tree were ever rendered on a server, which is a
  //   failure mode with no upside; supplying it degrades to the anonymous reading instead.
  return composeResult(useSyncExternalStore(subscribe, getSnapshot, getSnapshot));
}
