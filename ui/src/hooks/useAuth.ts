/**
 * @file Session state for the signed-on operator.
 *
 * WHY this replaces a COMMAREA field — Assumptions: the baseline carried the operator's identity and
 * type in `CDEMO-USER-ID` and `CDEMO-USER-TYPE` inside the structure the terminal echoed back
 * between turns (`app/cpy/COCOM01Y.cpy` L19-L44), which means the client was able to assert its own
 * user type. Here the authority lives only in the signed `cognito:groups` claim, and this module
 * reads it to decide which routes to OFFER.
 *
 * Assumptions: **the group read below is a presentation control, not a security boundary.** Every
 * service independently validates the token and re-derives authority from the same claim, so a
 * caller who tampers with what this module believes gains a menu entry and then an HTTP 403. That
 * separation is the whole reason the claim is read from a signed token rather than tracked in
 * application state: the client cannot mint a group it was not granted, it can at most mislead
 * itself about one.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';

import { SIGN_ON_AUTHENTICATED, answerSignOnChallenge, refreshTokens, signOn } from '../api/auth';
import type { SignOnChallenge, SignOnResult, SignOnTokens } from '../api/auth';
import { setAccessToken, subscribeToAuthenticationRequired } from '../api/client';

/** Group name granting the administrative surface, as the pool and the contracts name it. */
export const ADMIN_GROUP = 'carddemo-admin';

/** Group name granting the ordinary operator surface. */
export const USER_GROUP = 'carddemo-user';

/** Session-storage key holding the identity token, which is the source of the group claim. */
const ID_TOKEN_KEY = 'carddemo.id-token';

/** Session-storage key holding the identifier the tokens were issued for. */
const USER_ID_KEY = 'carddemo.user-id';

/** Session-storage key holding the refresh token, when the pool issues one. */
const REFRESH_TOKEN_KEY = 'carddemo.refresh-token';

/**
 * Seconds before expiry at which a refresh is attempted.
 *
 * Assumptions: sixty, so a refresh is in flight well before the access token stops being accepted.
 * Refreshing exactly at expiry would race the request that discovers the expiry.
 */
const REFRESH_MARGIN_SECONDS = 60;

/** The signed-on operator, or the absence of one. */
export interface AuthState {
  /** Whether a token is held for this browser tab. */
  readonly signedOn: boolean;
  /** Identifier the held tokens were issued for, or `null` when nobody is signed on. */
  readonly userId: string | null;
  /** Group names carried by the identity token's claim. */
  readonly groups: readonly string[];
  /** Whether the held claim includes the administrative group. */
  readonly isAdmin: boolean;
}

/** Everything a screen needs to establish, inspect or end a session. */
export interface AuthApi extends AuthState {
  /**
   * Signs on with a user identifier and password.
   * @param {string} userId - Operator identifier.
   * @param {string} password - Operator password.
   * @returns {Promise<SignOnResult>} The outcome, so a caller can render a challenge form.
   */
  signIn: (userId: string, password: string) => Promise<SignOnResult>;
  /**
   * Answers an outstanding challenge, completing sign-on.
   * @param {SignOnChallenge} challenge - The challenge being answered.
   * @param {string} newPassword - Replacement password.
   * @returns {Promise<SignOnTokens>} The issued tokens, so a caller can route on their claim without
   *   waiting for this hook to re-render.
   */
  answerChallenge: (challenge: SignOnChallenge, newPassword: string) => Promise<SignOnTokens>;
  /** Discards every held token for this tab. */
  signOut: () => void;
}

/**
 * Decodes the group names carried by an identity token.
 *
 * Assumptions: the signature is deliberately NOT verified here, and that is sound only because of
 * what the result is used for. Verification requires the pool's public keys and is performed by
 * every service on every request; this decode chooses which links to render. A malformed or
 * unreadable token yields no groups, so the failure mode is an operator who sees no administrative
 * entries rather than one who sees entries that then fail.
 * Refactoring Rationale: this is exported rather than private because a caller that has just
 * received a token needs the claim from THAT token, not from this hook's state. React has not
 * re-rendered at the moment sign-on resolves, so `isAdmin` still describes the previous session — a
 * screen that routed on it sent an administrator to the ordinary menu on every sign-on. Exporting the
 * decoder lets the decision be made from the value in hand.
 * @param {string | null} idToken - Identity token issued by the pool, or `null`.
 * @returns {readonly string[]} Group names from the `cognito:groups` claim, empty when absent.
 */
export function groupsFromIdToken(idToken: string | null): readonly string[] {
  if (idToken === null) {
    return [];
  }
  const segments = idToken.split('.');
  // WHY : Assumptions: the claim segment is read through an explicit undefined check rather than by
  //       indexing after a length test. `noUncheckedIndexedAccess` types every element access as
  //       possibly undefined, and a non-null assertion here would silently reassert what the length
  //       test already implies -- which is exactly the kind of claim this decoder must not make
  //       about an attacker-supplied string.
  const claimSegment = segments.length === 3 ? segments[1] : undefined;
  if (claimSegment === undefined) {
    return [];
  }
  try {
    const payload = claimSegment.replace(/-/gu, '+').replace(/_/gu, '/');
    const decoded: unknown = JSON.parse(
      atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')),
    );
    if (typeof decoded !== 'object' || decoded === null) {
      return [];
    }
    const groups: unknown = (decoded as Record<string, unknown>)['cognito:groups'];
    if (!Array.isArray(groups)) {
      return [];
    }
    return groups.filter(
      /**
       * Narrows one claim element to a string, discarding anything else the claim carried.
       * @param {unknown} group - One element of the decoded groups claim.
       * @returns {boolean} `true` only when the element is a string, which narrows the result type.
       */
      (group): group is string => typeof group === 'string',
    );
  } catch {
    return [];
  }
}

/**
 * Reads the identity token held for this tab.
 * @returns {string | null} The stored identity token, or `null`.
 */
function storedIdToken(): string | null {
  return sessionStorage.getItem(ID_TOKEN_KEY);
}

/**
 * Installs an issued token set for this browser tab.
 *
 * Assumptions: the access token goes through `setAccessToken` rather than being written here, so the
 * request interceptor and this hook agree on one storage key by construction. This function is the
 * production caller of that exported setter — before it existed, the setter had no caller at all and
 * every request left the browser unauthenticated.
 * @param {SignOnTokens} tokens - Tokens returned by sign-on, challenge or refresh.
 * @returns {void} Nothing; the four session-storage keys are written. A refresh token is written only
 *   when one was issued, so a set without one leaves any earlier value in place rather than clearing
 *   it -- clearing belongs to `clearTokens`, which is the one path that ends a session.
 */
function installTokens(tokens: SignOnTokens): void {
  setAccessToken(tokens.accessToken);
  sessionStorage.setItem(ID_TOKEN_KEY, tokens.idToken);
  sessionStorage.setItem(USER_ID_KEY, tokens.userId);
  if (typeof tokens.refreshToken === 'string' && tokens.refreshToken.length > 0) {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
  }
}

/**
 * Discards every token held for this browser tab.
 *
 * Assumptions: the access token is cleared through the same setter that installs it, for the same
 * single-key reason, and the identity and refresh tokens are removed alongside it so no signed claim
 * outlives the credential it accompanied.
 * @returns {void} Nothing; all four session-storage keys are removed. This is the storage half of a
 *   sign-out; the state half is the caller's, which is why the two always run together.
 */
function clearTokens(): void {
  setAccessToken(null);
  sessionStorage.removeItem(ID_TOKEN_KEY);
  sessionStorage.removeItem(USER_ID_KEY);
  sessionStorage.removeItem(REFRESH_TOKEN_KEY);
}

/**
 * Exposes the signed-on operator and the operations that change who that is.
 *
 * Trade-offs: session state is held in `sessionStorage` rather than in memory alone, so a page
 * reload does not sign the operator out. The cost is that the token is readable by script running on
 * this origin; the content-security policy admits no third-party script, and the alternative —
 * memory only — would sign an operator out on every refresh, which the 3270 workflow this replaces
 * never did. `sessionStorage` rather than `localStorage` bounds the exposure to the tab and clears
 * it when the tab closes.
 *
 * Assumptions: FOUR keys carry the session, and they are named here because "held in
 * `sessionStorage`" does not tell a reader what to clear or what a cross-site script could reach:
 * `carddemo.access-token` (written and cleared by `setAccessToken` in `ui/src/api/client.ts`),
 * `carddemo.id-token`, `carddemo.user-id` and `carddemo.refresh-token` — the last present only when
 * the pool issued one — all written by `installTokens` and removed by `clearTokens` below. Because
 * the scope is the TAB, a session survives a reload and an in-tab navigation, does not exist in a
 * second tab, and is discarded when the tab closes; nothing is ever written to `localStorage`.
 *
 * Assumptions: all four are cleared together, on three triggers and by one path. An explicit
 * sign-out, a refresh exchange the pool refuses, and any 401 answered to a request that CARRIED a
 * bearer token all reach `clearTokens`, so no signed claim outlives the credential it accompanied.
 * Two statuses deliberately clear NOTHING: a 401 from the sign-on request itself, which means a
 * rejected password rather than an expired session, and a 403, which is a valid token refused one
 * route. `ui/.env.example` states the same three facts for a reader who never opens this file.
 * @returns {AuthApi} The current session and the operations that change it.
 */
export function useAuth(): AuthApi {
  const [idToken, setIdToken] = useState<string | null>(storedIdToken);
  const [userId, setUserId] = useState<string | null>(
    /**
     * Reads the retained identifier once, on the first render only.
     *
     * Assumptions: the lazy form is used rather than the eager one so the storage read happens on
     * the initial render alone. An eager argument would read storage on every render and discard
     * the result, which is a cost with no effect.
     * @returns {string | null} The retained identifier, or `null` when no session is present.
     */
    () => sessionStorage.getItem(USER_ID_KEY),
  );
  const [expiresInSeconds, setExpiresInSeconds] = useState<number | null>(null);

  const groups = useMemo(
    /**
     * Decodes the group authorities carried by the current identity token.
     * @returns {string[]} The group names the token asserts, empty when there is no token.
     */
    () => groupsFromIdToken(idToken),
    [idToken],
  );

  const adopt = useCallback(
    /**
     * Installs a freshly issued token set and mirrors it into this hook's state.
     * @param {SignOnTokens} tokens - The token set the sign-on or refresh call returned.
     * @returns {void} Nothing; the caller observes the change through the returned state.
     */
    (tokens: SignOnTokens): void => {
      installTokens(tokens);
      setIdToken(tokens.idToken);
      setUserId(tokens.userId);
      setExpiresInSeconds(tokens.expiresIn);
    },
    [],
  );

  const signIn = useCallback(
    /**
     * Signs on and installs tokens when the outcome carries them.
     * @param {string} candidateUserId - Operator identifier.
     * @param {string} password - Operator password.
     * @returns {Promise<SignOnResult>} The raw outcome, so a challenge can be rendered.
     */
    async (candidateUserId: string, password: string): Promise<SignOnResult> => {
      const result = await signOn(candidateUserId, password);
      if (result.outcome === SIGN_ON_AUTHENTICATED) {
        adopt(result);
      }
      return result;
    },
    [adopt],
  );

  const answerChallenge = useCallback(
    /**
     * Answers a challenge and installs the tokens it yields.
     * @param {SignOnChallenge} challenge - The challenge being answered.
     * @param {string} newPassword - Replacement password.
     * @returns {Promise<SignOnTokens>} The issued tokens, returned so the caller can route on the
     *   claim in the token it just received rather than on this hook's not-yet-updated state.
     */
    async (challenge: SignOnChallenge, newPassword: string): Promise<SignOnTokens> => {
      const tokens = await answerSignOnChallenge(challenge.userId, challenge.session, newPassword);
      adopt(tokens);
      return tokens;
    },
    [adopt],
  );

  const signOut = useCallback(
    /**
     * Discards every retained credential and returns this hook to its signed-off state.
     * @returns {void} Nothing; the caller observes the change through the returned state.
     */
    (): void => {
      clearTokens();
      setIdToken(null);
      setUserId(null);
      setExpiresInSeconds(null);
    },
    [],
  );

  useEffect(
    /**
     * Signs the operator out when a service stops accepting the session this tab holds.
     *
     * Assumptions: this subscription is what makes the transport's re-authentication signal have an
     * effect. `ui/src/api/client.ts` discards the access token on a 401 that CARRIED one and then
     * notifies its listener registry, deliberately signalling rather than navigating; before this
     * effect existed the registry had no production subscriber, so the identity token, the refresh
     * token and the retained identifier all survived a rejected session, and the refresh this hook had
     * scheduled went on re-presenting a credential belonging to a session already refused. The guards in
     * `ui/src/routes/guards.tsx` read `signedOn` from the identity token, so a tab in that state kept
     * rendering protected screens for an operator whose every request was refused — a stale-session
     * state the operator could only leave by signing out by hand or closing the tab.
     *
     * Assumptions: the listener is `signOut` itself rather than a narrower clear, so an expired
     * session and a deliberate sign-out take the SAME path. Clearing only what the interceptor missed
     * would leave two partial clears to keep in agreement, and the next credential added to
     * `installTokens` would have to be remembered in both; routing both through one function means a
     * credential added in one place is discarded in every case by construction. The failure is
     * deliberately not inspected either: the client raises this signal only for a 401 on a request that
     * actually CARRIED a bearer, so the decision "was this session refused" has already been taken by
     * the module that saw the response, and a 403 -- a valid token refused one route -- never reaches
     * here, which is what keeps an ordinary operator meeting an administrative screen signed in.
     *
     * Trade-offs: `signOut` takes no argument while a listener is handed the normalised 401, so the
     * failure — and with it the correlation identifier and the service's own sentence — is discarded
     * here. That is accepted because this hook renders no message: the screen that issued the request
     * receives the same rejection through its own await and is the one placed to report it, whereas a
     * message raised from here would appear detached from any action the operator took.
     *
     * Trade-offs: routing is NOT performed here either. `signOut` returns this hook to its signed-off
     * state and `ui/src/routes/guards.tsx`, which reads that state, sends the operator to the sign-on
     * screen; navigating from a subscription would duplicate the guards' decision in a second place and
     * would make this hook untestable without a router.
     *
     * Alternatives Considered: hoisting session state into a module-level store read through
     * `useSyncExternalStore`, so one clear served every consumer. It is the better long-term shape
     * and is refused here as disproportionate: this hook holds its state per instance, so that change
     * rewrites its state model and touches every screen that calls it, whereas the defect is only
     * that a published signal had nobody listening. Per-instance subscription is correct in the
     * meantime because `clearTokens` is idempotent — several mounted instances each clearing the same
     * three keys is the same end state as one — and because each instance must reset its OWN
     * `useState` to re-render, which a single module-level clear could not have made it do.
     *
     * Alternatives Considered: subscribing once from the app shell. Rejected because the shell holds
     * none of this state, so it would have to reach into the hook to reset it, and any screen mounted
     * outside the shell — the sign-on screen is one — would not be covered.
     * @returns {() => void} The registry's own unsubscribe, returned as the effect's cleanup so a
     *   torn-down hook stops being told and cannot set state after it unmounts.
     */
    () => subscribeToAuthenticationRequired(signOut),
    [signOut],
  );

  useEffect(
    /**
     * Schedules one refresh ahead of expiry while a refresh token is held.
     * @returns {(() => void) | undefined} Timer cleanup, or `undefined` when nothing was scheduled.
     */
    () => {
      const refreshToken = sessionStorage.getItem(REFRESH_TOKEN_KEY);
      if (expiresInSeconds === null || refreshToken === null || userId === null) {
        return undefined;
      }
      const delaySeconds = Math.max(expiresInSeconds - REFRESH_MARGIN_SECONDS, 1);
      const timer = setTimeout(
        /**
         * Exchanges the retained refresh token, signing out if the exchange is refused.
         *
         * Assumptions: a failed refresh signs the operator out rather than retrying. The refresh
         * token is either accepted or it is not, so a retry would re-present a credential already
         * refused; signing out returns the operator to a screen that can obtain a working
         * credential, which is the outcome a retry would only delay.
         * @returns {void} Nothing; both outcomes are applied through the two handlers.
         */
        () => {
          refreshTokens(userId, refreshToken).then(adopt, signOut);
        },
        delaySeconds * 1000,
      );
      return (
        /**
         * Cancels the pending refresh when the effect is torn down or its inputs change.
         * @returns {void} Nothing; the timer is cleared in place.
         */
        () => {
          clearTimeout(timer);
        }
      );
    },
    [adopt, expiresInSeconds, signOut, userId],
  );

  return {
    signedOn: idToken !== null,
    userId,
    groups,
    isAdmin: groups.includes(ADMIN_GROUP),
    signIn,
    answerChallenge,
    signOut,
  };
}
