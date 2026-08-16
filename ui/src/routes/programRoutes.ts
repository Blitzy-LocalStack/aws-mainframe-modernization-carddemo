/**
 * @file The program-transfer graph: which reference program each menu option names, and which
 * browser route this migration reaches it at.
 *
 * Purpose
 * -------
 * Both menu screens hold their option list as data taken from the baseline copybooks — every option
 * carries a `programName`, which is the value `COMEN01C` and `COADM01C` pass to `EXEC CICS XCTL`. A
 * browser has no program to transfer to, so the option's program name has to be resolved to a route.
 * That resolution is declared once here rather than inside either menu, because both menus need it,
 * `ui/src/router.tsx` needs the same set of destinations to declare its routes, and a route-closure
 * test needs to compare the two.
 *
 * Why a map rather than a route on each option
 * -------------------------------------------
 * Alternatives Considered: adding a `route` member to the option entries in
 * `ui/src/messages/messages.ts`. Rejected because that module is the verbatim catalog of baseline
 * text and structure — every entry there cites the copybook line it was transcribed from, and a
 * route is a target-side decision with no line to cite. Mixing one into the catalog would make it
 * impossible to tell transcription from design.
 *
 * Assumptions: this map is DELIBERATELY partial, and the partiality is the delivery boundary rather
 * than an oversight. It names only the programs whose screens this delivery carries. An option whose
 * program is absent here is answered with the baseline's own not-installed sentence, which is exactly
 * what `app/cbl/COMEN01C.cbl` L147-L168 does for a program the CICS region does not hold: it
 * performs `EXEC CICS INQUIRE PROGRAM(...)` and, when the answer is not `NORMAL`, composes
 * `'This option ' <name> ' is not installed...'`. Reusing that sentence means the boundary is stated
 * in the operator's own vocabulary rather than appearing as a broken navigation.
 */

import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE } from './navigation';

/**
 * Route the user-maintenance screen is reached at with no user selected.
 *
 * Assumptions: the identifier segment is OMITTED rather than left empty. `app/cbl/COUSR02C.cbl`
 * L99-L104 tests its selection carrier against `SPACES AND LOW-VALUES` before using it and otherwise
 * leaves the screen waiting for a typed identifier, so arriving with no selection is a first-class
 * baseline arrival and not a degenerate one. `ui/src/router.tsx` declares the path with an optional
 * segment so both arrivals match one declaration.
 */
export const USER_UPDATE_UNSELECTED_ROUTE = '/users/edit';

/** Route the account-enquiry screen is reached at. */
export const ACCOUNT_VIEW_ROUTE = '/account/view';

/** Route the account-maintenance screen is reached at. */
export const ACCOUNT_UPDATE_ROUTE = '/account/update';

/** Route the card browse is reached at, which is also where a card number is entered. */
export const CARD_LIST_ROUTE = '/cards';

/** Route the transaction-capture screen is reached at. */
export const TRANSACTION_ADD_ROUTE = '/transactions/new';

/** Route the pending-authorization summary is reached at. */
export const AUTH_SUMMARY_ROUTE = '/authorizations';

/** Route the transaction-type browse is reached at. */
export const REF_TYPE_LIST_ROUTE = '/reference/transaction-types';

/**
 * Reference program name to the browser route this delivery reaches it at.
 *
 * Assumptions: `COCRDSLC` and `COCRDUPC` both resolve to the card BROWSE rather than to a
 * single-card path, and that is the registered divergence `D-CARD-SELECTOR` rather than a shortcut.
 * The published contract keys a single card by an opaque selector the service mints, so there is no
 * card path an operator can be sent to before a card number has been exchanged for one — and the
 * browse is where that exchange happens: `ui/src/screens/cardList/index.tsx` takes a typed card
 * number, calls the lookup operation and navigates to the detail or update route with the selector it
 * returns. Sending these two options there puts the operator at the control that opens the card they
 * want, which is what the baseline's own empty detail screen did with its account and card fields.
 *
 * Assumptions: `COUSR02C` resolves to the unselected form of its route for the reason recorded on
 * {@link USER_UPDATE_UNSELECTED_ROUTE} — an administrator reaching it from the menu has selected
 * nobody, so the screen prompts for an identifier exactly as the reference does.
 */
export const PROGRAM_ROUTES: Readonly<Record<string, string>> = Object.freeze({
  COACTVWC: ACCOUNT_VIEW_ROUTE,
  COACTUPC: ACCOUNT_UPDATE_ROUTE,
  COCRDLIC: CARD_LIST_ROUTE,
  COCRDSLC: CARD_LIST_ROUTE,
  COCRDUPC: CARD_LIST_ROUTE,
  COTRN02C: TRANSACTION_ADD_ROUTE,
  COPAUS0C: AUTH_SUMMARY_ROUTE,
  COTRTLIC: REF_TYPE_LIST_ROUTE,
  COUSR02C: USER_UPDATE_UNSELECTED_ROUTE,
});

/**
 * The two menu routes, published here so a route-closure test can check them alongside the rest.
 *
 * Assumptions: they are re-exported rather than redeclared. `ui/src/routes/navigation.ts` owns them
 * because every screen's exit key needs one of them, and a second declaration here would be a second
 * place for either path to drift.
 */
export const MENU_ROUTES: readonly string[] = Object.freeze([MAIN_MENU_ROUTE, ADMIN_MENU_ROUTE]);

/**
 * Resolves one reference program name to the route this delivery reaches it at.
 *
 * Assumptions: an unresolved name answers `null` rather than throwing or returning a fallback route.
 * A fallback would silently send an operator to a screen they did not choose, and a throw would make
 * the delivery boundary an error rather than the message the baseline emits for it — the caller is
 * expected to render the not-installed sentence, which is what both menus do.
 * @param {string} programName - `CDEMO-MENU-OPT-PGMNAME` or `CDEMO-ADMIN-OPT-PGMNAME` for the
 *   selected option, as `ui/src/messages/messages.ts` transcribes it.
 * @returns {string | null} The route to navigate to, or `null` when this delivery carries no screen
 *   for that program.
 */
export function routeForProgram(programName: string): string | null {
  return PROGRAM_ROUTES[programName] ?? null;
}
