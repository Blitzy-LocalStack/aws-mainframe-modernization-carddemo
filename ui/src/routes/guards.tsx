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
import { ACCESS_DENIED_ADMIN_ONLY } from '../messages/messages';

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
 * @param {GuardProps} props - The subtree to protect.
 * @returns {ReactElement} The protected subtree, a redirect to sign-on, or a refusal.
 */
export function RequireAdmin({ children }: GuardProps): ReactElement {
  const { signedOn, isAdmin } = useAuth();
  if (!signedOn) {
    return <Navigate to={SIGN_ON_ROUTE} replace />;
  }
  if (!isAdmin) {
    return <Result status="403" title="Access denied" subTitle={ACCESS_DENIED_ADMIN_ONLY} />;
  }
  return <>{children}</>;
}
