/**
 * Session state for the signed-on operator.
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
import { setAccessToken } from '../api/client';

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
