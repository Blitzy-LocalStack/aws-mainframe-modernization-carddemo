/**
 * @file The route guards: the two components that decide which screens a caller may SEE, and the
 * route an unauthenticated caller is sent to.
 *
 * Purpose
 * -------
 * Publish the sign-on route constant every screen navigates to on sign-out, and the two guard
 * components `ui/src/router.tsx` wraps its routes in -- one requiring a token, one additionally
 * requiring the administrative group claim. Together they replace what the reference application did
 * by branching on the user-type byte it carried in the communication area.
 *
 * Rendering, not permission
 * -------------------------
 * Assumptions: a guard decides what is RENDERED and never what is permitted. Every service validates
 * the token and its group claim independently, so a guard that admitted the wrong caller would show
 * a screen and no data. Stating that here matters because the opposite reading -- treating these as
 * the authorisation boundary -- would make it reasonable to relax a service check, and the reference
 * application's own weakness was exactly that kind of client-side trust.
 */

import { Result } from 'antd';
import type { ReactElement, ReactNode } from 'react';
import { Navigate } from 'react-router';

import { useAuth } from '../hooks/useAuth';
import { ACCESS_DENIED_ADMIN_ONLY, ACCESS_DENIED_HEADING } from '../messages/messages';

/** Route the guards send an unauthenticated caller to. */
export const SIGN_ON_ROUTE = '/signon';

/** Props every guard takes: the subtree it protects. */
export interface GuardProps {
  /** The screen rendered when the guard admits the caller. */
  readonly children: ReactNode;
}

/**
 * Admits only a caller holding a token, redirecting anyone else to sign-on.
 *
 * Assumptions: this guard decides what is RENDERED, not what is permitted. Every service validates
 * the bearer token independently and answers 401 without one, so a caller who bypasses this guard
 * reaches a screen whose requests are all refused. The guard exists so that the refusal is not the
 * first thing an operator sees — it sends them to the screen that can fix it.
 *
 * Refactoring Rationale: the redirect is `replace`, so the unauthenticated attempt does not become a
 * history entry. Without it, signing on and pressing back would return the operator to a guarded
 * path and immediately bounce them forward again, which reads as the browser being stuck.
 * @param {GuardProps} props - The subtree to protect.
 * @returns {ReactElement} The protected subtree, or a redirect to sign-on.
 */
export function RequireSignOn({ children }: GuardProps): ReactElement {
  const { signedOn } = useAuth();
  if (!signedOn) {
    return <Navigate to={SIGN_ON_ROUTE} replace />;
  }
  return <>{children}</>;
}

/**
 * Admits only a caller whose group claim carries the administrative group.
 *
 * Assumptions: an authenticated operator without the administrative group is shown a refusal rather
 * than being redirected. They are signed on correctly, so sending them to sign-on would invite them
 * to fix something that is not broken; the refusal states the actual reason.
 *
 * Assumptions: the refusal text is the baseline's own wording for this condition, taken from the
 * catalog rather than composed here, so an operator meets the same sentence the 3270 menu gave them.
 *
 * Refactoring Rationale: BOTH strings on the refusal surface now come from the catalog. The heading was
 * previously the literal `'Access denied'` written at the call site, which a review named a
 * Transformation Rule T8 violation: the rule's point is that one module answers "where does this
 * sentence come from" for every readable string, and a heading with no baseline source needs that
 * answer more than a transcribed one rather than less. {@link ACCESS_DENIED_HEADING} records that it is
 * authored and why the baseline has no heading to transcribe.
 * @param {GuardProps} props - The subtree to protect.
 * @returns {ReactElement} The protected subtree, a redirect to sign-on, or a refusal.
 */
export function RequireAdmin({ children }: GuardProps): ReactElement {
  const { signedOn, isAdmin } = useAuth();
  if (!signedOn) {
    return <Navigate to={SIGN_ON_ROUTE} replace />;
  }
  if (!isAdmin) {
    /*
     * WHY : Assumptions: `status="403"` is a design-system VARIANT selector and not a visible string,
     *       which is why it stays at the call site while the two sentences move to the catalog. antd
     *       answers this status with an illustration and no text of its own, so nothing an operator
     *       reads is declared here. Trade-offs: the literal is the HTTP status a service would answer
     *       the same caller with, so the surface and the transport agree on the refusal's identity
     *       without this component importing anything from the API layer to say so.
     */
    return (
      <Result status="403" title={ACCESS_DENIED_HEADING} subTitle={ACCESS_DENIED_ADMIN_ONLY} />
    );
  }
  return <>{children}</>;
}
