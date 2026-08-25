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
 * Assumptions: an option whose program is absent from this map is answered with the baseline's own
 * not-installed sentence, which is exactly what `app/cbl/COMEN01C.cbl` L147-L168 does for a program
 * the CICS region does not hold: it performs `EXEC CICS INQUIRE PROGRAM(...)` and, when the answer is
 * not `NORMAL`, composes `'This option ' <name> ' is not installed...'`. Reusing that sentence means
 * a delivery boundary is stated in the operator's own vocabulary rather than appearing as a broken
 * navigation.
 *
 * ⚠️ Refactoring Rationale: that sentence no longer answers any MAIN-MENU option, and saying so is the
 * point of this paragraph. The map was described as "deliberately partial" while it omitted programs
 * whose screens this delivery does carry, so the not-installed sentence was reporting a delivered
 * workflow as absent -- which is the one thing it must never do, because an operator cannot tell that
 * answer from a screen nobody wrote. Every one of the eleven programs `app/cpy/COMEN02Y.cpy` names now
 * resolves to a route, and the two that reach a screen only through a selector enter the browse where
 * the selector is acquired rather than reporting themselves missing. The arm is retained as reference
 * parity for a program name this map does not carry -- `app/cbl/COMEN01C.cbl` evaluates its
 * `INQUIRE PROGRAM` on every dispatch -- and it is now unreachable from the live option table, exactly
 * as the transcribed `DUMMY`-prefix and administrator-only arms beside it are.
 */

/*
 * ⚠️ Refactoring Rationale: every route below is IMPORTED from `ui/src/routes/navigation.ts`, where
 * ten of them were declared as literals in this module. That module already owned the two menu routes
 * for the reason its own docstring gives -- a second declaration is a second place for a path to drift
 * -- and the same reason applies to the rest of them with one addition: the origin census that decides
 * which route a screen's exit key may return to lives there, so a path declared here could be
 * navigated to and simultaneously refused as an origin. `/users` was exactly that: this module sent
 * the two user-maintenance options to it while the census could not see it, so the deletion screen's
 * exit returned an administrator to the administrative menu instead of to the browse they came from.
 * Importing keeps this module what its title says it is -- the program-to-route MAP -- and leaves the
 * paths themselves in one place.
 */
import {
  ACCOUNT_UPDATE_ROUTE,
  ACCOUNT_VIEW_ROUTE,
  ADMIN_MENU_ROUTE,
  AUTHORIZATION_SUMMARY_ROUTE,
  BILL_PAY_ROUTE,
  CARD_DETAIL_ENTRY_ROUTE,
  CARD_LIST_ROUTE,
  CARD_UPDATE_ENTRY_ROUTE,
  MAIN_MENU_ROUTE,
  REFERENCE_TYPE_ADD_ROUTE,
  REFERENCE_TYPE_LIST_ROUTE,
  REPORTS_ROUTE,
  TRANSACTION_ADD_ROUTE,
  TRANSACTION_DETAIL_ENTRY_ROUTE,
  TRANSACTION_LIST_ROUTE,
  USER_ADD_ROUTE,
  USER_LIST_ROUTE,
} from './navigation';

/**
 * Reference program name to the browser route this delivery reaches it at.
 *
 * ⚠️ Refactoring Rationale: `COCRDSLC`, `COCRDUPC` and `COTRN01C` resolve to a KEYLESS ENTRY route of
 * their own, where all three resolved to the browse that mints their selector. `app/cpy/COMEN02Y.cpy`
 * gives the main menu's eleven options eleven distinct target programs, and while those three shared
 * two destinations the menu reached only eight -- so an option an operator chose deliberately took them
 * somewhere they had not asked for and left them to select a record before the screen they named could
 * open at all. The three keyless routes are the addresses of each program's OWN first turn, which the
 * reference paints: `app/cbl/COCRDSLC.cbl` L490-L491 falls back to `WS-PROMPT-FOR-INPUT` on an empty
 * map with account and card fields to type into, and `app/cbl/COTRN01C.cbl` L103-L109 paints its empty
 * map when the selection carrier arrives blank. Eleven options now reach eleven destinations, which is
 * the reachability graph AAP section 0.1.3.1 preserves.
 *
 * Assumptions: registered divergence `D-CARD-SELECTOR` is UNCHANGED and stays documented. It records
 * that a single card is addressable only through the opaque selector the service mints -- the published
 * contract has no card-number path -- and nothing here invents one: `/cards/view` and `/cards/edit`
 * carry no key, and the card the operator wants is still opened by exchanging an account and card number
 * for a selector, either on the screen's own search turn or through the browse. What changes is only
 * which screen the operator is standing on while they do it.
 *
 * Assumptions: the browse remains the destination of `COCRDLIC` and `COTRN00C`, which are the two
 * programs that ARE the browse, so no option lost a destination in the repointing.
 *
 * ⚠️ Refactoring Rationale: `COUSR02C` resolves to the user BROWSE, where it resolved to a
 * selector-free `/users/edit`. That path was withdrawn because it was a second route for a screen that
 * already had one, and the screen is addressable only with a user identifier that a menu option does
 * not carry -- so the browse, which is the turn that acquires one, is where administrative option 3
 * lands. `ui/src/screens/userList/index.tsx` navigates to `/users/:id/edit` for a row marked `U`, which
 * is what `app/cbl/COUSR00C.cbl` L187-L209 does when it transfers to `COUSR02C` with the selected
 * identifier.
 *
 * ⚠️ Assumptions: this is NOT the arrangement the three keyless entry routes above replace, and the
 * difference is what the reference paints on the program's own first turn. `COCRDSLC` and `COTRN01C`
 * each paint an EMPTY map with the key as a field to type into, so a keyless address lands the operator
 * on a screen that can be used; `app/cbl/COUSR02C.cbl` L99-L104 pre-fills its identifier from the
 * selection carrier and its first turn is otherwise the same waiting map, so the same treatment is
 * available to it and is not taken here -- administrative options 3 and 4 are outside the finding this
 * change answers, and extending the pattern to them without their own verification would be a second
 * change wearing the first one's evidence.
 */
export const PROGRAM_ROUTES: Readonly<Record<string, string>> = Object.freeze({
  COACTVWC: ACCOUNT_VIEW_ROUTE,
  COACTUPC: ACCOUNT_UPDATE_ROUTE,
  COCRDLIC: CARD_LIST_ROUTE,
  COCRDSLC: CARD_DETAIL_ENTRY_ROUTE,
  COCRDUPC: CARD_UPDATE_ENTRY_ROUTE,
  COTRN02C: TRANSACTION_ADD_ROUTE,
  /*
   * WHY : Refactoring Rationale: the browse and the report screen are registered here because both are
   *       now mounted -- `ui/src/router.tsx` declares `/transactions` and `/reports` -- and this map is
   *       what decides whether a menu option can reach them. While either program was absent the option
   *       answered the baseline's not-installed sentence, which was correct for a screen that did not
   *       exist and became wrong the moment one did: it would refuse an operator two delivered flows the
   *       acceptance criteria name among those that must work end to end.
   * WHY : ⚠️ Refactoring Rationale: `COTRN01C` is registered to its own KEYLESS ENTRY route, having
   *       been registered first to nothing at all -- so option 7 answered the not-installed sentence for
   *       a screen this delivery mounts -- and then to the BROWSE, which stopped the false sentence and
   *       left option 7 entering the screen option 6 enters. Both corrections were right about what they
   *       fixed and both left the operator somewhere they had not asked for. The reference's own answer
   *       is neither: `app/cbl/COTRN01C.cbl` L103-L108 reads its selection carrier and L109 paints the
   *       EMPTY map when it is blank, with the transaction identifier as a field to type into, so the
   *       program has a first turn that needs no selection and this route is its address.
   * WHY : Assumptions: the browse remains reachable and remains the destination of `COTRN00C`, so
   *       nothing about row selection changes -- `ui/src/screens/transactionList/index.tsx` still marks
   *       a row `S` and enters `/transactions/:id` with the identifier that row carries, which is what
   *       `app/cbl/COTRN00C.cbl` L183-L195 does when it transfers to `COTRN01C` with a selection. The
   *       two arrivals are the reference's two arrivals, and now each has an address.
   */
  COTRN00C: TRANSACTION_LIST_ROUTE,
  COTRN01C: TRANSACTION_DETAIL_ENTRY_ROUTE,
  CORPT00C: REPORTS_ROUTE,
  /*
   * WHY : Refactoring Rationale: this entry names a route where the program was previously ABSENT from
   *       this map, which resolved it to `null`. That absence was correct while no bill payment screen
   *       existed; `ui/src/screens/billPay/index.tsx` is now authored and `ui/src/router.tsx` mounts it
   *       at this path, so leaving the program unregistered would answer main-menu option 10 with the
   *       baseline's not-installed sentence for a screen that IS installed -- refusing an operator a
   *       delivered flow that the acceptance criteria name among those which must work end to end.
   * WHY : Assumptions: the registration is made HERE rather than in either menu's own destination map.
   *       Both menus derive their destinations from this module, so this is the single edit that makes
   *       the option reachable; writing it into a menu would recreate the second, hand-maintained
   *       program-to-route table this module's own docstring forbids, and the two would then be free to
   *       disagree about which screens the delivery carries.
   */
  COBIL00C: BILL_PAY_ROUTE,
  COPAUS0C: AUTHORIZATION_SUMMARY_ROUTE,
  COTRTLIC: REFERENCE_TYPE_LIST_ROUTE,
  COUSR02C: USER_LIST_ROUTE,
  /*
   * WHY : Refactoring Rationale: the three administrative programs below are registered here because
   *       `ui/src/router.tsx` mounts each of them at a path that needs no selection -- `COUSR00C` at
   *       `/users` and `COUSR01C` at `/users/new`, both from `app/cpy/COADM02Y.cpy` L26-L34, and
   *       `COTRTUPC` at the add sentinel of its dynamic route, option 6 of the same copybook. They
   *       were resolvable only through the administrative menu's own hand-written copy of this
   *       mapping, so `routeForProgram` answered `null` for a screen the delivery carries and the
   *       copy answered a route: two tables disagreeing about the delivery boundary, which is the
   *       drift that made the copy a finding.
   * WHY : Assumptions: `COTRTUPC` resolves to the CONCRETE add path rather than to the dynamic
   *       pattern its screen is mounted under, for the reason recorded on {@link REFERENCE_TYPE_ADD_ROUTE}
   *       -- an option carries no type code, and the sentinel segment is the arrival the screen
   *       treats as an add.
   */
  COUSR00C: USER_LIST_ROUTE,
  COUSR01C: USER_ADD_ROUTE,
  COTRTUPC: REFERENCE_TYPE_ADD_ROUTE,
  /*
   * WHY : ⚠️ Refactoring Rationale: `COUSR03C` resolves to the user BROWSE where it was ABSENT from
   *       this map, and the absence made administrative option 4 answer the not-installed sentence for
   *       a screen this delivery carries. That sentence is the baseline's answer for a program the
   *       region cannot LOAD (`app/cbl/COADM01C.cbl` L141-L157 for a `DUMMY` target and its `PGMIDERR`
   *       handler at L270-L283 for a load failure), so spending it on a mounted screen reported a
   *       delivery gap that does not exist -- `ui/src/router.tsx` mounts user deletion at
   *       `/users/:id/delete`. AAP section 0.1.3.1 keeps the reachability graph of the eighteen
   *       transactions, and an administrator has no other way in: the session is memory-only, so any
   *       hard load of an administrative route bounces to sign-on.
   * WHY : Assumptions: it resolves to the browse for the SAME reason `COUSR02C` does above, which makes
   *       the two administrative record options one rule rather than two workarounds -- the screen is
   *       addressed only per record and a menu option carries no selection, which is exactly the state
   *       the reference's own first turn paints. `app/cbl/COUSR03C.cbl` L99-L104 pre-fills the
   *       identifier only when its selection carrier arrives non-blank and otherwise leaves the screen
   *       waiting for a typed key, and the browse is the reference's own caller for it:
   *       `app/cbl/COUSR00C.cbl` L192-L207 transfers there naming itself in `CDEMO-FROM-PROGRAM`, so
   *       the screen's PF3 returns to the list it was selected from.
   * WHY : Alternatives Considered: a placeholder identifier such as `/users/0/delete`, which would have
   *       kept one uniform parameterised shape per option. Rejected because it asks the receiving
   *       screen to read a record the operator never selected, and on this option that record would
   *       arrive under a live delete trigger.
   */
  COUSR03C: USER_LIST_ROUTE,
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
