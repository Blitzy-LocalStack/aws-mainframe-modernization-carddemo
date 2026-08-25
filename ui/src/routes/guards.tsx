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

import { Button, Flex, Typography } from 'antd';
import type { ReactElement, ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';

import { useAuth } from '../hooks/useAuth';
import { useShellSlot } from '../layout/AppShell';
import type { ShellScreenIdentity } from '../layout/AppShell';
import { ScreenTitle } from '../layout/ScreenTitle';
import { usePfKeys } from '../layout/usePfKeys';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  ACCESS_DENIED_HEADING,
  MAIN_MENU_HEADINGS,
  USER_LIST_KEY_LABELS,
} from '../messages/messages';
import type { SignOnBounceState } from './navigation';
import {
  MAIN_MENU_ROUTE,
  SIGN_ON_BOUNCE_REASON,
  SIGN_ON_ROUTE,
  navigateSafely,
  navigationHandler,
  resumablePath,
} from './navigation';

/*
 * WHY : Refactoring Rationale: the declaration moved to `./navigation` and is re-exported here rather
 *       than deleted. `ui/src/layout/AppShell.tsx` now needs the same address so that signing off can
 *       move the browser's address to the screen it renders, and this module imports from
 *       `ui/src/layout/AppShell` -- so a shell import of THIS module would close a cycle. `./navigation`
 *       imports nothing but react-router types and already holds every other route constant, which
 *       makes it the one module both sides can read from. Re-exporting keeps the four existing
 *       importers of `SIGN_ON_ROUTE` from this path working, so there is still exactly one spelling of
 *       the address anywhere in the tree.
 */
export { SIGN_ON_ROUTE };

/**
 * PF3 legend the refusal surface paints, re-published from the message catalogue.
 *
 * Assumptions: the label is BORROWED from a delivered screen rather than authored here. `F3=Back` is
 * the legend `app/bms/COUSR00.bms` paints on its row-24 field and five delivered screens carry it, so
 * an operator meeting it on the refusal reads a key they already know. The alternative catalogued
 * spelling -- `F3=Exit` on the two menus -- would be wrong, because this key returns the operator to
 * the application rather than ending their session the way `app/cbl/COADM01C.cbl` L100-L102 does.
 */
const REFUSAL_EXIT_KEY_LABEL = USER_LIST_KEY_LABELS.PFK03;

/**
 * Identity the refusal delegates to the frame's title band.
 *
 * Assumptions: it is the MAIN MENU's identity, and the reference decides that rather than this module.
 * `app/cbl/COMEN01C.cbl` L136-L143 is where this sentence comes from, and what that program does with
 * it is `MOVE 'No access - Admin Only option... ' TO WS-MESSAGE` followed by `PERFORM
 * SEND-MENU-SCREEN` -- it re-sends its OWN screen carrying the message, so the operator never leaves
 * transaction `CM00`. The values are that program's own working-storage constants, `WS-PGMNAME VALUE
 * 'COMEN01C'` and `WS-TRANID VALUE 'CM00'` at L36-L37, which `app/bms/COMEN01.bms` paints into the
 * `PGMNAME` and `TRNNAME` fields at rows 1 and 2. So an operator meeting this refusal reads the same
 * two identity fields the terminal showed them, which is also the truthful pair: the administrative
 * program never ran.
 *
 * ⚠️ Refactoring Rationale: this delegation was WITHHELD, on the ground that the refusal replaces no
 * program and naming one would report the operator into a transaction they never entered. That
 * reasoning was right about the administrative program and wrong about the consequence, and a browser
 * measurement showed the cost: `ScreenHeader` renders only when a screen delegates its identity
 * (`ui/src/layout/AppShell.tsx`), so withholding the slot did not leave the band present and
 * unpopulated -- it removed the title band entirely. The header measured just `Skip to screen
 * content\nSign off`, the document had no `h1` at all and its heading hierarchy began at `h4`, and the
 * date and time fields every other screen paints were absent. That is three of the four zones AAP
 * section 0.4.4 makes persistent, missing from a surface whose whole purpose here is to stop being an
 * out-of-frame takeover.
 *
 * Assumptions: the two values are written as literals rather than imported from
 * `ui/src/screens/menu/index.tsx`, which publishes the same pair. Every screen is a separately
 * lazily-loaded chunk and this module is in the entry bundle, so importing one at module scope would
 * merge that chunk into the bundle every caller downloads -- the same reason `ui/src/router.tsx`
 * declines to import the administrative screen's caption for its not-found surface. The citation above
 * is what holds the literals to their source.
 */
const REFUSAL_SCREEN_IDENTITY: ShellScreenIdentity = {
  transactionId: 'CM00',
  programName: 'COMEN01C',
};

/**
 * Renders the administrative refusal inside the application frame, with a way back.
 *
 * Purpose: state the refusal, name the reason, and leave the operator somewhere to go -- without
 * rendering any part of the screen they were refused.
 *
 * ⚠️⚠️ Refactoring Rationale: this replaces an antd `Result status="403"` returned inline from
 * {@link RequireAdmin}, and every property below is a measured defect of that surface. The result was a
 * full-page takeover, so the frame around it was DESTROYED: the title band, the `Tran`/`Prog`/date/time
 * identity fields, the row-23 message line and the row-24 `nav[aria-label="Function keys"]` legend all
 * measured absent, leaving only the skip link and the sign-off control, and because reloading the page
 * destroys the in-memory session the browser's own back control was the sole way to recover. The
 * refusal's heading was a `div.ant-result-title` rather than a heading element, so with every landmark
 * gone a screen-reader operator had neither a heading nor a region to orient by. And the status carried
 * a decorative illustration -- a cartoon figure with its hands on its head beside a potted cactus, and a
 * padlock badge in a purple that resolves to no entry in the token bridge -- on a screen AAP section
 * 0.4.4 requires to read as one cohesive, imagery-free system.
 *
 * Assumptions: it is a COMPONENT rather than markup returned from the guard's `if`. Both surfaces it
 * needs are hooks -- `usePfKeys` for the legend and `useShellSlot` for the delegation -- and calling
 * either from inside a conditional branch of the guard would make the guard's hook order depend on the
 * caller's claim, which React forbids outright.
 *
 * Assumptions: the refusal sentence is rendered as CONTENT and passed to the frame's message band as
 * well only in its empty form. The catalogue's own `normaliseMessageBandValue`, which
 * `ui/src/layout/MessageBand.tsx` applies to everything it paints, ends in `.trim()` -- so routing this
 * sentence through the band would silently drop the trailing blank the fixed-width source field
 * produced, which Rule T8 carries across and which `ui/src/test/admin.test.tsx` asserts byte for byte.
 * Delegating `text: null` reserves the band's row without putting the sentence through the trim.
 *
 * Trade-offs: exactly ONE copy of the sentence may exist in the document, which is why it is not
 * rendered twice for emphasis. Several suites locate it with `findByText`, which throws when a query
 * matches more than one element, so a second copy would fail them as a duplicate rather than pass as a
 * repetition.
 *
 * Trade-offs: the way back is offered rather than taken -- this surface renders IN PLACE and does not
 * redirect. `app/cbl/COMEN01C.cbl` L140 paints this same sentence on the menu the operator was already
 * looking at, which argues for a redirect; against it is that the address has to keep naming the route
 * that was refused, because that is the only thing distinguishing a gated route from an unmounted one.
 * The destination is the MAIN menu, which every operator can reach whatever their claim, and which is
 * the screen the reference paints this refusal on.
 * @returns {ReactElement} The refusal, painted in the frame's content region.
 */
function AdministrativeRefusal(): ReactElement {
  const navigate = useNavigate();

  const { bindings, invoke } = usePfKeys({
    PFK03: {
      /**
       * Leaves the refusal for the main menu.
       * @returns {void} Nothing; the transition is a side effect on the router.
       */
      onInvoke: (): void => {
        navigateSafely(navigate, MAIN_MENU_ROUTE);
      },
      label: REFUSAL_EXIT_KEY_LABEL,
    },
  });

  useShellSlot({
    screen: REFUSAL_SCREEN_IDENTITY,
    message: { text: null },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  /*
   * WHY : Assumptions: the heading is a real heading ELEMENT rather than styled text, and it is the one
   *       thing on this surface that has no counterpart in the reference. `ScreenTitle` renders an `h4`,
   *       so the document's outline reads `h1` (the frame's application title) then `h4` -- the same
   *       shape every delivered screen has. The withdrawn surface put its title in a
   *       `div.ant-result-title`, which with every landmark also destroyed left an operator using a
   *       screen reader nothing at all to orient by.
   */
  return (
    <Flex vertical gap="middle" align="flex-start">
      <ScreenTitle>{ACCESS_DENIED_HEADING}</ScreenTitle>
      <Typography.Text>{ACCESS_DENIED_ADMIN_ONLY}</Typography.Text>
      <Button type="primary" onClick={navigationHandler(navigate, MAIN_MENU_ROUTE)}>
        {MAIN_MENU_HEADINGS.SCREEN}
      </Button>
    </Flex>
  );
}

/** Props every guard takes: the subtree it protects. */
export interface GuardProps {
  /** The screen rendered when the guard admits the caller. */
  readonly children: ReactNode;
}

/**
 * Builds the state a bounce to sign-on hands the screen it lands on.
 *
 * Purpose: make a bounce distinguishable from a first visit, and make the destination recoverable.
 *
 * ⚠️ Refactoring Rationale: the redirects below carried NO state, and both consequences were measured on
 * all nineteen guarded routes. Because the session is held in memory, any reload of a guarded route ends
 * it and the operator arrives at sign-on with the message band present and EMPTY -- nothing separated
 * "thrown out of `/users/USER0100/delete`" from opening the application for the first time. And the
 * destination was gone: no query string, no storage, and `replace` resetting `history.state.idx` to 0 so
 * the browser's back control could not reach it. The memory-only session is deliberate and stays; what
 * changes is that the turn now says why it happened and where the operator was going.
 *
 * Assumptions: the attempted path is passed through {@link resumablePath} HERE as well as on read. This
 * side is the honest one -- the value comes from `useLocation()` and is a path this router matched -- so
 * the check is cheap and it means a malformed value is never written in the first place, which keeps the
 * reader's rejection a defence against a hand-edited entry rather than the only line of defence.
 * @param {string} attempted - Path the operator was trying to open, from the current location.
 * @returns {SignOnBounceState} The state to hand `<Navigate>`; the reason alone when the path is
 *   rejected, so the explanation never depends on the destination surviving.
 */
function signOnBounce(attempted: string): SignOnBounceState {
  const resumable = resumablePath(attempted);

  return {
    reason: SIGN_ON_BOUNCE_REASON,
    ...(resumable === undefined ? {} : { attempted: resumable }),
  };
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
 *
 * ⚠️ Refactoring Rationale: the redirect now carries {@link signOnBounce}, where it carried nothing. It
 * keeps `replace` -- that decision is unchanged and is the reason the destination needed a carrier at
 * all, since `replace` is also what resets `history.state.idx` and puts the attempted path beyond the
 * browser's back control.
 * @param {GuardProps} props - The subtree to protect.
 * @returns {ReactElement} The protected subtree, or a redirect to sign-on.
 */
export function RequireSignOn({ children }: GuardProps): ReactElement {
  const { signedOn } = useAuth();
  const { pathname } = useLocation();
  if (!signedOn) {
    return <Navigate to={SIGN_ON_ROUTE} replace state={signOnBounce(pathname)} />;
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
 *
 * ⚠️ Refactoring Rationale: the refusal is delegated to {@link AdministrativeRefusal} rather than
 * composed here, where this function returned an antd `Result status="403"` inline. The status literal
 * that stood here was defended as a variant selector carrying no visible string; what it actually
 * selected was a full-page takeover that destroyed the frame and a decorative illustration, which
 * {@link AdministrativeRefusal} records in full. Nothing about the DECISION moves: the guard still
 * refuses on the same claim, still refuses rather than redirecting a correctly signed-on operator, and
 * still renders none of the subtree.
 * @param {GuardProps} props - The subtree to protect.
 * @returns {ReactElement} The protected subtree, a redirect to sign-on, or a refusal.
 */
export function RequireAdmin({ children }: GuardProps): ReactElement {
  const { signedOn, isAdmin } = useAuth();
  const { pathname } = useLocation();
  if (!signedOn) {
    /*
     * WHY : Assumptions: the unauthenticated branch carries the same bounce state as
     *       {@link RequireSignOn}, because an administrative route reloaded is the SAME event as an
     *       ordinary route reloaded -- the session died and the operator has to sign on again. Only the
     *       claim-bearing branch below differs between the two guards, and it does not redirect at all.
     */
    return <Navigate to={SIGN_ON_ROUTE} replace state={signOnBounce(pathname)} />;
  }
  if (!isAdmin) {
    return <AdministrativeRefusal />;
  }
  return <>{children}</>;
}
