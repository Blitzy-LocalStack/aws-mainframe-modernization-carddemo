/**
 * @file Route constants and the one validated navigation helper every screen transitions through.
 *
 * Purpose
 * -------
 * Hold every route constant more than one module needs -- the two menu routes the reference programs
 * transfer to, and each parameterless screen route a menu dispatches to or a screen names as the origin
 * it was entered from -- and wrap the router's navigate function so that a transition is checked before
 * it is performed. This is where `EXEC CICS XCTL`'s destination naming ends up: the reference names a
 * program, a screen here names a route, and both are looked up rather than composed at the call site.
 * The same constants are what the origin census admits, so a destination and an admissible origin can
 * never be two different spellings of one path.
 *
 * Why a helper rather than calling navigate directly
 * -------------------------------------------------
 * Assumptions: a destination is validated, so a route that does not exist becomes the router's
 * bounded not-found result rather than an unhandled state. The helper earns its place even now that
 * every constant below names a mounted screen, because a transition is composed at a call site and a
 * path that stops matching -- a renamed segment, a route moved under a different guard -- is otherwise
 * indistinguishable from one that never existed.
 *
 * Refactoring Rationale: this section previously said that two of the constants below named screens
 * that were not authored yet, and that the resulting not-found result was what made the check
 * load-bearing. Both screens are now authored and `ui/src/router.tsx` mounts both routes, so the
 * sentence described a state the tree had left -- and it was the reason the check LOOKED provisional.
 * It is restated as what the check is actually for, which does not depend on a screen being absent.
 */

import type { NavigateFunction, To } from 'react-router';

/**
 * The values one screen may hand the screen it transitions to.
 *
 * Refactoring Rationale: this exists because two things the reference carries in the
 * shared communication area have no other carrier in a stateless target, and the
 * obvious substitutes are both wrong. `app/cpy/COCOM01Y.cpy` L19-L44 hands the next
 * program a message field and a selected account, and a screen that transitions
 * without them either drops the message the reference emits or puts the account
 * identifier in the URL. A query member was the first shape used for the identifier
 * and is withdrawn: a query string is request-target data, so it reaches browser
 * history and the load balancer's access log, which is the one place this migration
 * refuses to let a cardholder identifier land. Router state travels in the history
 * entry's state object instead, so it is readable by the destination and appears in no
 * request line.
 *
 * Assumptions: every member is optional and none is a substitute for identity or
 * authority. A destination reads these values as a HINT it may ignore - the message it
 * displays, the identifier it pre-fills - and every request it then makes carries the
 * signed token and its own body, so a hand-edited history entry can pre-fill a field
 * and change nothing else.
 */
export interface ScreenTransitionState {
  /**
   * Verbatim baseline sentence the departing screen hands the destination's message
   * band, or absent when it hands none.
   */
  readonly message?: string;
  /**
   * Account identifier the destination may pre-fill its account filter with, as
   * digits, or absent when the transition carries no selection.
   */
  readonly accountId?: string;
  /**
   * Route the departing screen occupies, which the destination's exit key returns to,
   * or absent when the transition names no origin.
   *
   * Refactoring Rationale: this is the carrier for `CDEMO-FROM-TRANID` and
   * `CDEMO-FROM-PROGRAM` (`app/cpy/COCOM01Y.cpy` L23-L26), which several programs prefer
   * over their own hard-coded menu destination on the PF3 arm -
   * `app/cbl/COACTVWC.cbl` L328-L339 is the clearest case. A screen with no carrier for it
   * had to take the fallback arm unconditionally, which is a real behaviour loss: an
   * operator who reached the screen from somewhere other than the menu was returned to the
   * menu anyway. The browser's own history is NOT that carrier - a history entry is not a
   * named origin and need not even belong to this application - which is why the origin is
   * handed over explicitly and validated against the route table by
   * {@link inApplicationRoute}.
   *
   * ⚠️ Refactoring Rationale: five screens read this member and, until this revision, nothing
   * wrote it -- so every one of them took its fallback arm on every visit and the carrier was
   * inert. There are five producers -- the two menus and the three browses that transfer with
   * a selection: the main menu, the administrative menu, the transaction browse, the user
   * browse and the reference-type browse each pass their own route as `from` at the
   * transition, which is the `MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM` every one of those
   * reference programs performs immediately before its `XCTL` (`app/cbl/COUSR00C.cbl` L194
   * and L204 are the clearest pair). `ui/src/routes/routeCensus.test.ts` requires a producer
   * for every consumer, so the carrier cannot fall inert again without a case failing.
   */
  readonly from?: string;
}

/**
 * Route the main menu occupies, which is where the source application's PF3 returns to.
 *
 * Assumptions: the constant is declared here, in the module every screen already imports its
 * transitions from, rather than being written as a literal at each call site. Four screens need it --
 * sign-on routes here once a token is installed, the card browse exits here matching
 * `app/cbl/COCRDLIC.cbl` L390-L399 where PF3 transfers to `LIT-MENUPGM`, the authorization summary
 * exits here matching `COPAUS0C.cbl` L235-L238, and the router's not-found surface offers it as the way
 * back -- and a literal repeated in four modules is four places for the path to drift apart.
 *
 * Assumptions: the route is mounted. `ui/src/router.tsx` registers it inside the authenticated layout
 * route and `ui/src/screens/menu/index.tsx` is the screen behind it, so a transition here reaches the
 * migrated `COMEN01C` rather than a not-found result. Naming the source's real destination was correct
 * while the screen was absent too -- sending PF3 to a screen the source does not return to would have
 * invented a destination -- and `ui/src/routerRoutes.test.tsx` now holds the two in agreement.
 */
export const MAIN_MENU_ROUTE = '/menu';

/**
 * Route the sign-on screen occupies, and the address every session boundary resolves to.
 *
 * Refactoring Rationale: this constant was declared in `ui/src/routes/guards.tsx`, which is the only
 * route module that imports from `ui/src/layout/**`. `ui/src/layout/AppShell.tsx` needs the address so
 * that signing off can move the browser's address to the screen it actually renders -- the shell
 * previously replaced its own frame without touching the address, leaving the location naming a screen
 * that was no longer mounted -- and importing it from the guards module would have closed a cycle
 * (`AppShell` -> `guards` -> `AppShell`). This module imports nothing but react-router types, so it is
 * the one place both the guards and the shell can read a route constant from. `guards.tsx` re-exports
 * this declaration, so every existing importer is unaffected and there is still exactly one spelling
 * of the address in the tree.
 *
 * Assumptions: the sign-on route is mounted INSIDE the shell rather than beside it -- `ui/src/router.tsx`
 * registers it as a child of the same layout route that wraps the guarded subtree -- so navigating here
 * restores the frame rather than leaving it, which is what makes it a safe destination for a control on
 * a surface that has replaced the frame.
 */
export const SIGN_ON_ROUTE = '/signon';

/**
 * Route the administrative menu occupies, reached when the group claim carries the admin group.
 *
 * Assumptions: the claim decides who ARRIVES here and the route itself is additionally guarded, which
 * is two mechanisms rather than a duplicated one. Sign-on chooses between this route and the main menu
 * from the signed claim, and `ui/src/router.tsx` nests this route and every screen it leads to inside
 * `RequireAdmin` -- so an operator who reaches the path another way still meets the refusal instead of
 * the menu.
 *
 * ⚠️ Assumptions: the second sentence above describes what this route does NOW and did not describe
 * it before. The route was mounted as a sibling of `RequireAdmin` rather than inside it, so an
 * operator who reached the path another way met the MENU; `ui/src/router.tsx` records the correction
 * and the measurement that forced it. The wording here is left as the contract this constant is
 * documented against, now that the router keeps it.
 */
export const ADMIN_MENU_ROUTE = '/admin';

/*
 * WHY : Refactoring Rationale: the branch below was written twice -- once at
 *       `ui/src/screens/signon/index.tsx` L892 as `admin ? ADMIN_MENU_ROUTE : MAIN_MENU_ROUTE`, and
 *       once as a fixed `/menu` at the bare-origin redirect in `ui/src/router.tsx`, which is where
 *       the second copy being fixed rather than conditional sent an administrator holding a session
 *       to the ORDINARY menu. Naming the branch once, in the module that already owns both
 *       destinations, is what stops a third caller reproducing either mistake.
 */

/**
 * Resolves the menu an operator's own claim entitles them to land on.
 *
 * Purpose: this is the migrated form of the reference's entry branch. `app/cbl/COSGN00C.cbl`
 * L230-L240 transfers an `'A'` operator to `COADM01C` and everyone else to `COMEN01C`, and it does
 * so unconditionally -- there is no path in the reference by which a `'U'` operator reaches the
 * administrative menu.
 *
 * Assumptions: the caller supplies the decision rather than this module reading it, because the
 * claim lives in `ui/src/hooks/useAuth.ts` and a route-constants module that imported the session
 * hook would pull the React layer in behind it -- every screen imports this file for its constants
 * alone. Passing the boolean also lets the one caller that must NOT use the render-time copy pass
 * the value it just received: sign-on reads the claim from the response it authenticated with,
 * because the hook's copy still describes the previous session on that turn.
 *
 * Assumptions: `false` is the safe default and the anonymous answer. An operator holding no session
 * is not an administrator, so this resolves to the main menu, and the authentication guard on that
 * route then bounces them to sign-on -- which is the same outcome an anonymous caller had before
 * this branch existed.
 * @param {boolean} isAdmin - Whether the operator's signed `cognito:groups` claim carries the
 *   administrative group.
 * @returns {string} {@link ADMIN_MENU_ROUTE} for an administrator, {@link MAIN_MENU_ROUTE}
 *   otherwise.
 */
export function roleLandingRoute(isAdmin: boolean): string {
  return isAdmin ? ADMIN_MENU_ROUTE : MAIN_MENU_ROUTE;
}

/*
 * Refactoring Rationale: the five constants below were path LITERALS written once in
 * `ui/src/router.tsx` and nowhere else, which was correct while the router was the only
 * module that named a destination. The main-menu screen changed that: it dispatches the
 * eleven options of `app/cpy/COMEN02Y.cpy` to the screens that replace their programs,
 * so a second module now needs the same paths, and two copies of a path are two places
 * for it to drift. Declaring them here - the module that already owns the destinations
 * more than one screen needs - keeps the router and the menu naming ONE value, and it
 * is why this file and not the router is where a new destination goes.
 * Alternatives Considered: exporting them from `ui/src/router.tsx`. Rejected because a
 * screen importing the router imports every lazily-loaded screen chunk's adapter with
 * it, which defeats the code splitting the router exists to arrange, and because it
 * would make a screen depend on the table that mounts it.
 */

/** Route the account-view screen occupies, replacing program `COACTVWC`. */
export const ACCOUNT_VIEW_ROUTE = '/account/view';

/** Route the account-update screen occupies, replacing program `COACTUPC`. */
export const ACCOUNT_UPDATE_ROUTE = '/account/update';

/** Route the card browse screen occupies, replacing program `COCRDLIC`. */
export const CARD_LIST_ROUTE = '/cards';

/** Route the transaction-capture screen occupies, replacing program `COTRN02C`. */
export const TRANSACTION_ADD_ROUTE = '/transactions/new';

/**
 * Route the transaction browse occupies, replacing program `COTRN00C`.
 *
 * Assumptions: it is declared here because it is an ORIGIN as well as a destination.
 * `ui/src/screens/transactionList/index.tsx` opens the detail screen from a selected row, and
 * `app/cbl/COTRN01C.cbl` L246-L253 returns on PF3 to whichever program transferred to it rather
 * than to a fixed menu -- so the browse has to be nameable as an origin for the detail screen's exit
 * key to reproduce that, and {@link inApplicationRoute} admits only what this module declares.
 */
export const TRANSACTION_LIST_ROUTE = '/transactions';

/**
 * Route the user browse occupies, replacing program `COUSR00C`.
 *
 * ⚠️ Assumptions: it is declared here for the same reason the transaction browse above is -- it is
 * the origin of two screens. `ui/src/screens/userList/index.tsx` opens user maintenance and user
 * deletion from a selected row, and both of those screens read a handed-over origin on their exit
 * key, so without this route in the admissible set the origin they were given would be discarded
 * and the operator returned to the administrative menu instead of to the list they came from. It was
 * absent while no user browse was mounted; `ui/src/router.tsx` mounts it at this path, so the
 * absence had become a behaviour loss rather than a boundary.
 */
export const USER_LIST_ROUTE = '/users';

/** Route the pending-authorization summary occupies, replacing program `COPAUS0C`. */
export const AUTHORIZATION_SUMMARY_ROUTE = '/authorizations';

/** Route the transaction-type maintenance list occupies, replacing program `COTRTLIC`. */
export const REFERENCE_TYPE_LIST_ROUTE = '/reference/transaction-types';

/*
 * ⚠️ Refactoring Rationale: the six constants below were declared in the modules that
 * navigate to them -- four in `ui/src/routes/programRoutes.ts`, one in
 * `ui/src/screens/transactionList/index.tsx` and one in `ui/src/screens/refTypeList/index.tsx`
 * -- and each is moved here because the origin census below has to name the SAME value a
 * screen navigates by. A census holding its own copy of a path admits an origin that is
 * spelled like a route rather than one that is a route, which is the defect that left
 * `/users` inadmissible while the user browse was being navigated to: the browse's path was
 * declared where the browse was, and this module could not see it. Both files now import
 * these from here, so a renamed segment moves the destination and the admissible origin
 * together.
 */

/** Route the add-user screen occupies, replacing program `COUSR01C`. */
export const USER_ADD_ROUTE = '/users/new';

/** Route the bill-payment screen occupies, replacing program `COBIL00C`. */
export const BILL_PAY_ROUTE = '/billpay';

/** Route the transaction-report submission screen occupies, replacing program `CORPT00C`. */
export const REPORTS_ROUTE = '/reports';

/**
 * Concrete route the transaction-type maintenance screen occupies when adding a type.
 *
 * Assumptions: this is a CONCRETE path under the parameterised template
 * {@link REFERENCE_TYPE_EDIT_ROUTE_TEMPLATE} rather than a route of its own, because the
 * reference reaches its add and change entries through one program: `COTRTLIC.cbl` L630-L652
 * transfers to `COTRTUPC` with no key, and `COTRTUPC` then prompts for one. The final segment
 * is a sentinel the maintenance screen recognises, and it cannot collide with a key because
 * `TR_TYPE` is `CHAR(2)` in `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 -- so no code
 * can ever be `new`. It is declared beside the parameterless routes because a caller CAN hand
 * it over unresolved, which is what makes it admissible below where the template is not.
 */
export const REFERENCE_TYPE_ADD_ROUTE = `${REFERENCE_TYPE_LIST_ROUTE}/new`;

/*
 * WHY : ⚠️ Purpose of the three constants below: give the three programs whose screens are addressed
 *       per record a KEYLESS entry a menu option can name. `app/cpy/COMEN02Y.cpy` gives eleven options
 *       eleven distinct target programs, and `COCRDSLC`, `COCRDUPC` and `COTRN01C` had no route of
 *       their own to be sent to -- so three options resolved to the browse that mints their selector
 *       and eleven options reached eight destinations. On the terminal each of those three programs has
 *       a first turn of its own: `app/cbl/COCRDSLC.cbl` L490-L491 falls back to `WS-PROMPT-FOR-INPUT`
 *       and paints an empty map the operator types an account and card number into, and
 *       `app/cbl/COTRN01C.cbl` L103-L109 paints its empty map when the selection carrier arrives
 *       blank. These constants are the addresses of those first turns.
 * WHY : ⚠️ Assumptions: each is a STATIC path rather than a sentinel segment under the parameterised
 *       template, which is where this differs from {@link REFERENCE_TYPE_ADD_ROUTE} above even though
 *       both answer "an option carries no key". A sentinel would arrive at the screen as a route
 *       PARAMETER, and `ui/src/screens/cardDetail/index.tsx` distinguishes a present-but-unusable
 *       identifier from an absent one: its information line selects the invalid-link guidance whenever
 *       the parameter is defined and the selector is not, and falls back to the reference's own
 *       `WS-PROMPT-FOR-INPUT` only when nothing was supplied. A sentinel arrival would therefore tell
 *       an operator who chose a menu option that their link was broken. `ui/src/screens/transactionDetail/index.tsx`
 *       is stronger still -- it seeds its lookup control from the parameter and reads on a present one
 *       -- so a sentinel there would issue a lookup for the literal sentinel text. A static path leaves
 *       the parameter `undefined`, which both screens already document as a first-class arrival.
 * WHY : Assumptions: react-router scores a static segment above a dynamic one, so these three resolve
 *       to their own routes rather than to the parameterised siblings they sit beside. That is the same
 *       ranking `/transactions/new` has always relied on next to `/transactions/:id`, recorded at that
 *       route's declaration in `ui/src/router.tsx`.
 * WHY : Alternatives Considered: an empty segment -- `/cards//edit` -- so that one route served both
 *       arrivals. Rejected on measurement: react-router matches no empty path segment, so that address
 *       falls through to the shell's route fallback and paints `Screen not available`, which is the one
 *       answer a legitimately chosen menu option must never receive.
 * WHY : Trade-offs: the router therefore declares three paths more than the twenty-one screen routes
 *       AAP section 0.4.1.4 enumerates, and `ui/src/router.tsx` keeps them in a table of their own so
 *       its `ROUTE_TABLE` stays the one-row-per-program bijection that can be counted against the two
 *       option copybooks. The alternative -- adding them as ordinary rows -- is what the withdrawn
 *       `/users/edit` row did, and it cost exactly that countability.
 */

/**
 * Keyless entry to the card-detail screen, replacing program `COCRDSLC`.
 *
 * Assumptions: the screen this reaches is the one mounted at `/cards/:cardKey`, arriving with no
 * selector, so it opens on the account-and-card search turn `app/bms/COCRDSL.bms` paints rather than on
 * a record. Registered divergence `D-CARD-SELECTOR` is unaffected and stays documented: a single card is
 * still addressable only by the opaque selector the service mints, and this route does not invent one --
 * it is the address of the turn where the operator supplies the account and card number instead.
 */
export const CARD_DETAIL_ENTRY_ROUTE = `${CARD_LIST_ROUTE}/view`;

/**
 * Keyless entry to the card-update screen, replacing program `COCRDUPC`.
 *
 * Assumptions: the final segment is `edit` rather than `update`, matching the keyed sibling
 * `/cards/:cardKey/edit` its screen is also mounted at, so the two addresses of one screen differ only
 * in carrying a key. A third verb for the same screen would read as a third screen.
 */
export const CARD_UPDATE_ENTRY_ROUTE = `${CARD_LIST_ROUTE}/edit`;

/**
 * Keyless entry to the transaction-detail screen, replacing program `COTRN01C`.
 *
 * Assumptions: this is the exact address `ui/src/screens/transactionDetail/index.tsx` already names as
 * its selector-free arrival, so declaring it here makes that screen's own contract true rather than
 * choosing a spelling for it.
 */
export const TRANSACTION_DETAIL_ENTRY_ROUTE = `${TRANSACTION_LIST_ROUTE}/view`;

/**
 * Route template the user-update screen occupies, replacing program `COUSR02C`.
 *
 * Assumptions: this is a TEMPLATE carrying an `:id` parameter and is not a navigable
 * destination. The screen reads the identifier from the route, so a caller transitioning
 * there builds the concrete path from the user it selected; the constant exists so any
 * future selector screen agrees on the shape of the selected form.
 *
 * Assumptions: the router's own pattern is `/users/:id/edit`, and this template is the same
 * shape deliberately rather than by accident. A selector-free `/users/edit` was declared
 * beside it and is withdrawn -- it made the table publish twenty-two paths for twenty-one
 * programs -- so `COUSR02C` has one route and administrative option 3 reaches this screen
 * through the browse's selection. A caller substituting an optional form would build a path
 * carrying a literal `?` segment that matches nothing this table declares.
 */
export const USER_UPDATE_ROUTE_TEMPLATE = '/users/:id/edit';

/**
 * Route template the transaction-type maintenance screen occupies, replacing program `COTRTUPC`.
 *
 * Assumptions: a TEMPLATE for the same reason {@link USER_UPDATE_ROUTE_TEMPLATE} is one -- it
 * carries the router's `:cd` parameter, so it is not a value a caller hands over unresolved.
 * It is declared here so that {@link REFERENCE_TYPE_ADD_ROUTE} can state which template its
 * sentinel resolves under, and so the exclusion recorded below names a constant rather than a
 * shape.
 *
 * ⚠️ Refactoring Rationale: the parameter was spelled `:typeCd` here while `ui/src/router.tsx`
 * mounts the screen at `/reference/transaction-types/:cd` and
 * `ui/src/screens/refTypeEdit/index.tsx` reads `useParams<{ cd: string }>()`. That divergence
 * was invisible because nothing built a path from this constant -- it was referenced only by
 * the prose around it -- so the wrong spelling cost nothing until the first caller used it, at
 * which point `useParams` would have resolved `cd` to `undefined` with no diagnostic and the
 * maintenance screen would have prompted for a key its own URL already carried. The AAP fixes
 * the route as `/reference/transaction-types/:cd` (section 0.4.1.4), so the router's spelling
 * is the authority and this constant is brought onto it. The transport property is unaffected:
 * `typeCd` remains what `ui/src/api/reference.ts` sends and receives.
 */
export const REFERENCE_TYPE_EDIT_ROUTE_TEMPLATE = `${REFERENCE_TYPE_LIST_ROUTE}/:cd`;

/**
 * The canonical form of a transaction-type key, which is what the route segment may carry.
 *
 * Assumptions: exactly two decimal digits, corroborated three ways rather than assumed. The record
 * declares `TRAN-TYPE PIC X(02)` (`app/cpy/CVTRA03Y.cpy` L5) and the table declares
 * `TR_TYPE CHAR(2)` (`app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2), which fixes the WIDTH; the
 * maintenance screen's own key edit admits digits only and then canonicalises with
 * `String(Number.parseInt(entry, 10)).padStart(2, '0')`, which fixes the CHARACTER SET. So a stored
 * key is always two digits even though the picture clause would tolerate letters, and admitting more
 * than the screen itself produces would let this module mint an address the screen cannot resolve.
 */
const REFERENCE_TYPE_CODE_PATTERN = /^[0-9]{2}$/u;

/**
 * Reports whether a value has the canonical two-digit form a stored transaction-type key carries.
 * @param {string} value - Candidate key from a response, a table row or a typed entry.
 * @returns {boolean} `true` only for exactly two decimal digits. The add sentinel `new` is refused,
 *   because it is three characters and is not a key.
 */
export function isReferenceTypeCode(value: string): boolean {
  return REFERENCE_TYPE_CODE_PATTERN.test(value);
}

/**
 * Validates a transaction-type key before it becomes part of a browser address.
 *
 * Assumptions: it THROWS rather than answering `undefined`, matching `requireCardSelector` in
 * `ui/src/routes/cards.ts`. A key that fails here is a programming error and not an operator error --
 * the screens that build this path hold a key the service already resolved to a stored record -- so
 * failing loudly at the call site is what surfaces it, where a silent `undefined` would compose into
 * the string `'/reference/transaction-types/undefined'` and navigate somewhere real.
 *
 * Alternatives Considered: encoding the value with `encodeURIComponent` and admitting anything.
 * Rejected because encoding makes an unusable address safe rather than correct: the route's only
 * consumer looks the key up, so a key the store cannot hold produces a screen reporting a record that
 * was never there, which is a worse outcome than a refused navigation.
 * @param {string | null | undefined} value - Candidate key from a response or a table row.
 * @returns {string} The validated two-digit key.
 * @throws {RangeError} If the value is absent or is not exactly two decimal digits. The add sentinel
 *   fails here deliberately: {@link REFERENCE_TYPE_ADD_ROUTE} is the constant for that entry, and
 *   routing it through this builder would make the sentinel look like a key.
 */
export function requireReferenceTypeCode(value: string | null | undefined): string {
  if (value === null || value === undefined || !isReferenceTypeCode(value)) {
    throw new RangeError(
      `transaction-type code must be exactly two digits, as a type response publishes it; received a value of length ${String(value?.length ?? 0)}.`,
    );
  }
  return value;
}

/**
 * Builds the browser location of one stored transaction type's maintenance screen.
 *
 * Purpose: give {@link REFERENCE_TYPE_EDIT_ROUTE_TEMPLATE} a producer. The route is mounted and
 * guarded, and no control in the delivered application could reach it: administrative option 6
 * navigates to {@link REFERENCE_TYPE_ADD_ROUTE}, and keying a code there loads the record IN PLACE
 * while the address stays on the `new` sentinel -- so a fetched record was not addressable, a reload
 * lost it, and the browser's back control returned to a URL that had never described what was on the
 * screen. This is the half of that repair that belongs to the route layer: the concrete address exists
 * and is validated. The other half is the maintenance screen replacing its own history entry with this
 * path once a record is loaded, which is recorded on that screen.
 *
 * Assumptions: it composes from {@link REFERENCE_TYPE_LIST_ROUTE} rather than restating the segments,
 * so a renamed prefix moves the template, the add route and this builder together.
 * @param {string} typeCd - The two-digit key a transaction-type response published.
 * @returns {string} The concrete maintenance route for that key.
 * @throws {RangeError} If the key is not the canonical two-digit form.
 */
export function referenceTypeEditRoute(typeCd: string): string {
  return `${REFERENCE_TYPE_LIST_ROUTE}/${requireReferenceTypeCode(typeCd)}`;
}

/*
 * Assumptions: the census below is a CLOSED set of admissible origins, and it holds every
 * parameterless route `ui/src/router.tsx` registers except sign-on. Sign-on is excluded
 * because it is never an origin -- `app/cbl/COSGN00C.cbl` L245 transfers to a MENU and to
 * nothing else, so no screen with a PF3 arm is ever entered from it -- and excluding it
 * additionally keeps this module free of `ui/src/routes/guards.tsx`, which owns that path:
 * this file is imported by every screen for its constants alone, and importing the guard
 * module would pull the component layer in behind it.
 * Assumptions: the PARAMETERISED routes are excluded as a class rather than individually,
 * and the exclusion is about shape rather than about trust. The two card detail routes are
 * minted by `ui/src/routes/cards.ts` from an opaque selector; the user update and delete
 * routes, the transaction detail route, the authorization detail route and the reference-type
 * maintenance template each carry a route parameter. None of those is a value a caller can
 * hand over unresolved, so a closed set could not enumerate them -- while a resolved instance
 * of one is a concrete path that would have to be admitted by pattern rather than by
 * membership, which is the string comparison this check exists to avoid. The one exception is
 * {@link REFERENCE_TYPE_ADD_ROUTE}: its final segment is a fixed sentinel rather than a
 * parameter, so it IS enumerable, and it is admitted.
 * ⚠️ Assumptions: the three keyless entry routes above are enumerable too and are deliberately
 * NOT admitted, so their omission is a decision rather than the oversight that left `/users`
 * out. An admissible origin is a route a screen HANDS OVER as the place its caller's exit key
 * should return to, and no screen hands over any of the three: the card-detail screen forwards
 * the origin it was itself given rather than its own address -- argued at its
 * `forwardedOrigin` derivation, so that an operator returns to where the sequence began -- and
 * neither the card-update nor the transaction-detail screen opens another screen at all.
 * Admitting a route nothing hands over would widen the set that guards a writable history
 * value for no behaviour, and the set is the whole check.
 * ⚠️ Refactoring Rationale: this set held eight of those routes and omitted six --
 * `/users`, `/users/new`, `/transactions`, `/billpay`, `/reports` and the reference-type add
 * route. The omission was not a policy: those six were simply declared in other modules, so a
 * screen could navigate from one of them and the destination's exit key would silently take
 * its hard-coded fallback instead of returning where the operator came from. `/users` was the
 * live case -- the user browse transfers to the deletion screen, whose exit reads this set --
 * so an administrator who arrived from the browse was returned to the administrative menu.
 * Completing the set is half of that repair; the other half is that a departing screen has to
 * HAND the origin over, which `ui/src/routes/routeCensus.test.ts` now holds to the code by
 * requiring a producer for every consumer.
 */

/**
 * Every parameterless application route a screen may name as its origin.
 *
 * ⚠️ Refactoring Rationale: the two browse routes at the end are ADMITTED where they were
 * absent, and their absence was silently discarding origins the delivered screens hand over.
 * `ui/src/screens/transactionList/index.tsx` opens the transaction detail screen and
 * `ui/src/screens/userList/index.tsx` opens user maintenance and user deletion, so all three
 * destinations read an origin that {@link inApplicationRoute} refused - and a refused origin
 * is not an error, it is a fall back to the destination's own default, which is the generic
 * menu. That is the behaviour loss `app/cbl/COACTVWC.cbl` L328-L339 shows the reference did
 * not have: a program that was transferred to returns to the transferring program, not to a
 * fixed menu.
 * @returns {readonly string[]} The routes {@link inApplicationRoute} admits.
 */
function navigableRoutes(): readonly string[] {
  return [
    MAIN_MENU_ROUTE,
    ADMIN_MENU_ROUTE,
    ACCOUNT_VIEW_ROUTE,
    ACCOUNT_UPDATE_ROUTE,
    CARD_LIST_ROUTE,
    TRANSACTION_LIST_ROUTE,
    TRANSACTION_ADD_ROUTE,
    BILL_PAY_ROUTE,
    REPORTS_ROUTE,
    AUTHORIZATION_SUMMARY_ROUTE,
    USER_LIST_ROUTE,
    USER_ADD_ROUTE,
    REFERENCE_TYPE_LIST_ROUTE,
    REFERENCE_TYPE_ADD_ROUTE,
  ];
}

/**
 * Resolves a claimed origin to an application route, or to nothing.
 *
 * Assumptions: the claim is matched against a declared set rather than merely tested for a
 * leading slash, and the difference is the whole point of the check. Router state is
 * attacker-writable through a hand-edited history entry, and a path-shaped value that is
 * not one of this application's own routes would either reach the not-found result or, if
 * it were protocol-relative, leave the application entirely on a key press the operator
 * believes goes back one screen. Matching a closed set makes both impossible.
 * @param {string | undefined} claimed - The origin the transition state carried, if any.
 * @returns {string | undefined} The claimed route when this application serves it, and
 *   `undefined` for anything else, which leaves a caller to use its own default.
 */
export function inApplicationRoute(claimed: string | undefined): string | undefined {
  return claimed !== undefined && navigableRoutes().includes(claimed) ? claimed : undefined;
}

/**
 * The discriminant a guard's bounce to sign-on carries, so the screen can tell one from a first visit.
 *
 * Assumptions: it is a fixed literal rather than a message, because the SENTENCE an operator reads
 * belongs to `ui/src/messages/messages.ts` and this module holds no readable text. What the guard knows
 * and the screen cannot infer is the CAUSE -- that the operator was turned away from a route rather than
 * arriving at sign-on deliberately -- so that is what travels.
 */
export const SIGN_ON_BOUNCE_REASON = 'session-required';

/**
 * The discriminant a deliberate sign-off carries to the sign-on entry it replaces the address with.
 *
 * Refactoring Rationale: this sits beside {@link SIGN_ON_BOUNCE_REASON} on ONE discriminant rather
 * than in a shape of its own, because both answer the same question -- why is the operator looking at
 * sign-on -- and both are read on the same entry by the same code. `ui/src/layout/AppShell.tsx` writes
 * this one when the operator ends a session on purpose; `ui/src/routes/guards.tsx` writes the other
 * when a guard turns them away. Two shapes would have meant two readers of `history.state` disagreeing
 * about what a missing member meant.
 *
 * Assumptions: it carries no {@link SignOnBounceState.attempted} path. A deliberate sign-off has no
 * destination to resume -- the operator asked to leave -- so resuming one would carry them back into
 * the screen they had just chosen to exit.
 */
export const SIGN_OFF_ACKNOWLEDGED_REASON = 'signed-off';

/**
 * What a guard hands the sign-on screen when it turns an operator away.
 *
 * Purpose: replace the two things a silent bounce destroyed. The session is held in memory only -- a
 * deliberate decision this migration does not reverse -- so any reload of a guarded route ends it and
 * `RequireSignOn` redirects. Measured, that redirect carried nothing at all: the message band was
 * present and EMPTY, so being thrown out of `/users/USER0100/delete` looked exactly like a first visit,
 * and the destination was unrecoverable -- `location.search` empty, every storage empty, and
 * `<Navigate replace>` resetting `history.state.idx` to 0 so the browser's back control could not
 * reach it either.
 *
 * Alternatives Considered: persisting the attempted destination to `sessionStorage`, which would also
 * survive the reload. Rejected outright: the whole reason the session is memory-only is that a review
 * found every credential this application held sitting in script-readable Web Storage, and reopening
 * that store for a convenience value invites the next value into it. History-entry state is destroyed by
 * the same reload that destroys the session, which is exactly the lifetime this value should have --
 * the operator is told why they are here on the turn they arrive, and nothing outlives the turn.
 *
 * Assumptions: nothing here is authority and nothing here is trusted. A hand-edited history entry can
 * name any destination it likes; {@link signOnBounceState} admits only an application-shaped path, the
 * route it names is still guarded when the operator reaches it, and every service revalidates the
 * signed claim regardless.
 */
export interface SignOnBounceState {
  /**
   * Why the operator is at sign-on, present only when something put them there deliberately.
   *
   * ⚠️ Refactoring Rationale: this was a single-literal union naming only the guard's bounce. A
   * deliberate sign-off now reaches the same entry and needs to be told apart from both a bounce and a
   * first visit, so the second literal joins it here rather than being carried on a parallel shape.
   */
  readonly reason?: typeof SIGN_ON_BOUNCE_REASON | typeof SIGN_OFF_ACKNOWLEDGED_REASON;
  /**
   * Path the operator was trying to open, for the screen to resume once a session is held.
   *
   * Trade-offs: the PATHNAME only, with no query string and no fragment. Both would widen the character
   * set {@link resumablePath} admits, and neither carries anything here: QA measured `location.search`
   * empty on all nineteen guarded routes, because every selection this application makes travels either
   * in a path segment or in history-entry state, never in a request target.
   */
  readonly attempted?: string;
}

/*
 * WHY : Assumptions: the shape below is an ALLOW-LIST and not a rejection list, and the leading
 *       `(?!\/)` is the member that matters most. A value of `//evil.example` is a legal relative
 *       reference that `navigate` will hand to `pushState`, and the browser resolves it as
 *       PROTOCOL-RELATIVE -- so an operator who signed on would be carried off the application by a
 *       transition they believe resumes their own screen. Requiring one leading slash and forbidding a
 *       second closes that; the character class then admits exactly what this application's own paths
 *       are built from, including the 59-character opaque card selectors `ui/src/routes/cards.ts`
 *       mints from `[A-Za-z0-9_-]`.
 * WHY : Alternatives Considered: matching the claim against the mounted route table with `matchRoutes`,
 *       which would additionally reject a path no screen serves. Rejected because the table lives in
 *       `ui/src/router.tsx`, which imports THIS module -- so reading it here would close an import
 *       cycle -- and because the residual it removes is harmless: a well-shaped path that matches no
 *       route resolves to the framed not-found surface, which itself offers a way back.
 * WHY : Alternatives Considered: reusing {@link inApplicationRoute}. Rejected because that set is
 *       deliberately PARAMETERLESS -- it answers "which route may a screen name as its PF3 origin" --
 *       and the destinations most worth resuming are parameterised: the deletion screen QA measured the
 *       loss on is `/users/:id/delete`.
 */
const RESUMABLE_PATH_PATTERN = /^\/(?!\/)[A-Za-z0-9._~/-]*$/u;

/**
 * Reads the reason a history entry gives for the operator being at sign-on.
 *
 * Assumptions: the value is narrowed structurally and compared against the two known literals rather
 * than cast, because `history.state` is writable by any page and every other member on it belongs to
 * some other feature. An unrecognised reason reads as no reason at all, which is the same as a first
 * visit -- the safe reading, since the only thing a reason does is add an explanation.
 * @param {unknown} entryState - Whatever the current history entry carries as its state.
 * @returns {string | undefined} One of the two known reason literals, or `undefined`.
 */
export function signOnEntryReason(entryState: unknown): string | undefined {
  if (typeof entryState !== 'object' || entryState === null) {
    return undefined;
  }
  const { reason } = entryState as { readonly reason?: unknown };
  if (reason === SIGN_ON_BOUNCE_REASON || reason === SIGN_OFF_ACKNOWLEDGED_REASON) {
    return reason;
  }
  return undefined;
}

/**
 * Admits a claimed destination only when it is shaped like one of this application's own paths.
 * @param {unknown} claimed - The destination a history entry claimed, of any type.
 * @returns {string | undefined} The claim when it is an application-shaped path, `undefined` otherwise.
 */
export function resumablePath(claimed: unknown): string | undefined {
  return typeof claimed === 'string' && RESUMABLE_PATH_PATTERN.test(claimed) ? claimed : undefined;
}

/**
 * Reads the bounce state the sign-on screen was entered with.
 *
 * Assumptions: validated structurally rather than cast, for the reason
 * {@link screenTransitionState} records -- `useLocation().state` is whatever the previous entry put
 * there, including a hand-edited value -- and each member is dropped independently. Dropping the
 * destination while keeping the reason still leaves the operator an explanation, which is the half of
 * this that must never depend on the other.
 * @param {unknown} state - The `state` member of the sign-on screen's location.
 * @returns {SignOnBounceState} The members present and well-formed; empty for a direct arrival.
 */
export function signOnBounceState(state: unknown): SignOnBounceState {
  if (typeof state !== 'object' || state === null) {
    return {};
  }

  const { reason, attempted } = state as {
    readonly reason?: unknown;
    readonly attempted?: unknown;
  };
  const resumable = resumablePath(attempted);

  return {
    ...(reason === SIGN_ON_BOUNCE_REASON ? { reason: SIGN_ON_BOUNCE_REASON } : {}),
    ...(resumable === undefined ? {} : { attempted: resumable }),
  };
}

/**
 * Reads the transition state a destination screen was entered with.
 *
 * Assumptions: the value is validated structurally rather than cast, because
 * `useLocation().state` is whatever the previous entry put there - including a value a
 * hand-edited history entry supplied - so a cast would let a non-string reach a message
 * band or a form control. A member of the wrong type is dropped rather than coerced:
 * dropping it leaves the destination in its own first-entry state, which is a state it
 * already renders correctly, while coercing would display `[object Object]` as though
 * the reference had emitted it.
 * @param {unknown} state - The `state` member of the destination's location.
 * @returns {ScreenTransitionState} The members that are present and well-typed; empty
 *   when the screen was entered directly, by a full document navigation, or with a
 *   value of any other shape.
 */
export function screenTransitionState(state: unknown): ScreenTransitionState {
  if (typeof state !== 'object' || state === null) {
    return {};
  }

  const { message, accountId, from } = state as {
    readonly message?: unknown;
    readonly accountId?: unknown;
    readonly from?: unknown;
  };

  return {
    ...(typeof message === 'string' ? { message } : {}),
    ...(typeof accountId === 'string' ? { accountId } : {}),
    ...(typeof from === 'string' ? { from } : {}),
  };
}

/**
 * Performs a router transition and falls back to a full navigation if a data
 * router rejects the transition.
 *
 * Trade-offs: the fallback path drops `state`, because a full document navigation
 * starts a new history entry with none. That is accepted rather than worked around -
 * the values carried are a transient message and a pre-fill hint, so the destination
 * renders its own first-entry state instead, which is correct if less informative.
 * Encoding them into the URL to survive the fallback is the alternative and is exactly
 * what {@link ScreenTransitionState} exists to avoid.
 * @param {NavigateFunction} navigate - Router navigation function.
 * @param {To} destination - Safe application destination.
 * @param {ScreenTransitionState | SignOnBounceState} [state] - Values the destination may read on
 *   arrival; omitted for a transition that hands over nothing.
 * @param {object} [options] - Transition options; omitted for an ordinary forward transition.
 * @param {boolean} [options.replace] - `true` to replace the current history entry instead of pushing
 *   a new one, which is what a session boundary needs so the screen left behind stays unreachable.
 */
export function navigateSafely(
  navigate: NavigateFunction,
  destination: To,
  state?: ScreenTransitionState | SignOnBounceState,
  options?: { readonly replace?: boolean },
): void {
  /*
   * WHY : Refactoring Rationale: the two parameters above widened this helper for the shell's two
   *       SESSION-boundary transitions, which are the only ones in the tree that must not leave the
   *       screen they came from reachable behind a discarded session. `replace` is what makes that
   *       true; the widened state type is because a sign-off carries a reason on the sign-on entry
   *       rather than a screen-to-screen hand-over. Both are optional, so every existing caller is
   *       unaffected and still gets exactly the transition it did before.
   *       Alternatives Considered: calling `navigate` directly from the shell. Rejected because this
   *       helper's rejection fallback matters MORE at a session boundary than anywhere else -- a
   *       transition that silently fails there leaves the operator on a screen their session no longer
   *       authorises.
   */
  const navigateOptions = {
    ...(state === undefined ? {} : { state }),
    ...(options?.replace === true ? { replace: true } : {}),
  };
  const transition =
    Object.keys(navigateOptions).length === 0
      ? navigate(destination)
      : navigate(destination, navigateOptions);
  if (transition instanceof Promise) {
    transition.catch(
      /**
       * Completes the transition with a full document navigation when the data
       * router rejects it, so the operator still reaches the destination.
       */
      () => {
        if (typeof destination === 'string') {
          window.location.assign(destination);
        } else {
          window.location.assign(destination.pathname ?? '/');
        }
      },
    );
  }
}

// Refactoring Rationale: eight controls across the three card screens carried the
// same inline `() => navigateSafely(navigate, X)` closure. Each was a documentable
// function under ui/eslint.config.js, so the alternative to this factory was eight
// copies of one sentence -- and eight places for the transition semantics to drift
// apart. Naming the handler once keeps the fallback behaviour above in a single
// documented seam and leaves the call sites reading as intent rather than mechanics.
/**
 * Builds an event handler that performs one router transition when invoked.
 * @param {NavigateFunction} navigate - Router navigation function.
 * @param {To} destination - Safe application destination.
 * @returns {() => void} A handler suitable for an Ant Design control's `onClick`.
 */
export function navigationHandler(navigate: NavigateFunction, destination: To): () => void {
  return (
    /** Performs the transition, falling back to a full navigation if it is rejected. */
    () => {
      navigateSafely(navigate, destination);
    }
  );
}
