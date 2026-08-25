/**
 * @file Keyset browse state for every paged screen, replacing the CICS browse and the position the
 * reference carried between screen turns.
 *
 * What this module holds
 * ----------------------
 * One delivered page, the two sealed cursors addressing the pages either side of it, the ordinal of
 * the page on display, whether a request is outstanding, how the last one ended -- as a problem
 * document and as the transport's own classification of it -- and the two imperative steps a screen
 * binds to its backward and forward keys. Nothing else: no row is formatted, no sentence is chosen, no
 * keyboard event is bound and no request target is addressed.
 *
 * What one page turn is, and why it is awaitable and single-flighted
 * -----------------------------------------------------------------
 * Refactoring Rationale: the three steps returned nothing, and a review measured both consequences on
 * live browses. A caller could not tell when a turn ended, so the only busy affordance any browse had
 * was the table's own overlay and the pressed key stayed indistinguishable from idle for the whole
 * flight; and nothing suppressed a repeat, so two presses of one forward key 400 ms apart produced two
 * identical requests and left the ordinal reading one page beyond the rows on display, while three
 * presses of a search control produced three identical posts. The two halves are fixed together and
 * separately: every step now resolves when its turn has SETTLED into this browse, and the dispatch is
 * additionally routed through `withoutConcurrentDuplicate` so that an identical turn already in flight
 * is JOINED rather than re-issued. The second half is what protects a screen that never adopts the
 * first -- adoption is per screen and will not be simultaneous.
 *
 * Assumptions: a caller that discards the returned promise behaves exactly as it did when the steps
 * returned nothing. Both outcomes of a read are handled inside the settlement and applied through the
 * reducer, so the promise resolves either way and never rejects -- there is no rejection for a
 * discarding caller to leave unhandled, and no outcome it fails to receive.
 *
 * Assumptions: what counts as the SAME turn is stated once, at {@link coalescingKeyOf}, because getting
 * it wrong in either direction is a defect: too narrow and the duplicate survives, too wide and a
 * legitimate turn is silently suppressed and the browse strands.
 *
 * Refactoring Rationale: the reference opens a browse with STARTBR, walks it with READNEXT and
 * READPREV, closes it with ENDBR, and carries its position across the screen turn in the passed
 * communication area -- `app/cbl/COCRDLIC.cbl` L1123 to L1258 reading forward and L1264 to L1376
 * reading backward, over the cursor pair declared at L230 to L235. Transformation rule T5 collapses
 * those four verbs into ONE keyed query per direction, so there is no browse session to open or
 * close and no server-held position at all. What was wrong with the server-held position is what
 * AAP section 0.7.1 withdraws generally: a value the client echoes back is a value the client can
 * assert, and a task holding an open browse cannot be one of several interchangeable containers
 * behind a load balancer.
 *
 * Alternatives Considered: positioning a page by counting rows from the start of the ordered set.
 * Rejected because it does not survive concurrent insertion -- rows would be addressed by their
 * distance from the start rather than by their own identity, so an insertion ahead of the reading
 * position pushes one row into the following page and repeats another on it, while a deletion drops
 * one row nobody ever sees. The concurrency is not hypothetical and the reference itself proves it:
 * `app/cbl/COBIL00C.cbl` reads the highest transaction identifier and adds one to it with nothing
 * held across the sequence -- HIGH-VALUES moved into the key at L212, STARTBR at L213, READPREV at
 * L214, ENDBR at L215, the key moved out at L216 and incremented at L217. Two bill payments running
 * together read the same maximum, so freshly created identifiers land in the middle of a key space a
 * browse may be part way through walking. A keyed read is unaffected, because a key names the row
 * rather than its distance from anything. That is also why `app/cbl/COBIL00C.cbl` is a
 * counter-example rather than a consumer: it declares no READNEXT paragraph and its STARTBR carries
 * no GTEQ, so it is a highest-key generator and the bill-pay screen is not one of the five browses
 * this module serves.
 *
 * Assumptions: the five browses are the card list, the user list, the transaction list, the
 * reference transaction-type list and the pending-authorization summary. Their row arities are
 * measured rather than assumed and they disagree: seven at `app/cbl/COCRDLIC.cbl` L177 to L178, ten
 * at `app/cbl/COUSR00C.cbl` L57, ten at `app/cbl/COTRN00C.cbl` L290, seven at the OCCURS clauses of
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L118 and L182, and five at
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L126. The arity is therefore a required
 * option here and never a value this module chooses.
 *
 * Assumptions: every sentence a user reads belongs to `ui/src/messages/messages.ts` under
 * transformation rule T8, so this module carries none. The reference answers a step taken at a
 * boundary by re-sending the same screen with a sentence attached -- `app/cbl/COTRN00C.cbl` L248 and
 * L270, `app/cbl/COUSR00C.cbl` L251 and L273,
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L381 and L409, and
 * `app/cbl/COCRDLIC.cbl` L901 to L903 -- so what this module owes a screen is the boundary STATE it
 * chooses that sentence from, which is {@link UsePagedQueryResult.hasNext} and
 * {@link UsePagedQueryResult.hasPrev}. A step taken at a boundary is a documented no-op here: the
 * rows, the cursors and the ordinal are left exactly as they are, no request is issued and nothing
 * is thrown.
 *
 * Assumptions: rows pass through untouched, and that is a correctness requirement rather than
 * economy. `ui/src/api/types.ts` declares every monetary member as text, because transformation rule
 * T3 keeps money exact at every hop and JavaScript's only numeric type is an IEEE-754 double, so a
 * single conversion here would lose a cent while producing a plausible figure. This module therefore
 * performs no numeric conversion, no rounding, no locale or date rendering, and no sort, filter or
 * other rearrangement of the rows it was handed. Formatting belongs to the screens.
 *
 * Assumptions: how a consumer wires this up, and ⚠️ this paragraph is CORRECTED. A screen renders an
 * antd `Table` with `pagination` turned off, because the component's built-in row-counting pager is
 * exactly the mechanism rejected above; it reaches this module's two steps from its own key bindings;
 * and at a boundary it shows its own verbatim sentence chosen from {@link PageBoundary}. What this
 * paragraph said before was that a screen binds its backward control's `disabled` to `hasPrev` and its
 * forward control's to `hasNext`, and that instruction was wrong on the oracle: not one of the five
 * source programs refuses a paging key -- each answers it and re-sends the screen with a message, at
 * the lines cited on {@link PageBoundary} -- so a greyed key replaces a verbatim string that
 * transformation rule T8 requires with the unmapped-key message `ui/src/layout`'s function-key hook
 * emits for a disabled binding. A review of the shipped build measured the consequence: five browses,
 * five different boundary idioms, one of them greying both keys and so showing "invalid key" where the
 * reference shows "You are already at the top of the page...", and the zero-row case answered on some
 * browses and silent on others. The two availability members remain what a STEP is guarded on -- they
 * are what makes the step a no-op -- and {@link PageBoundary} is what a SENTENCE is chosen from.
 *
 * Alternatives Considered: binding the two paging keys in here, which is the shorter route because
 * the reference dispatches them directly -- `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L239
 * to L243 sends the seventh key to its backward paragraph and the eighth to its forward one, and
 * `app/cpy/CSSTRPFY.cpy` normalises both at L42 to L45 while aliasing the second twelve keys onto the
 * first twelve, so its nineteenth key is the seventh at L66 to L67 and its twentieth is the eighth at
 * L68 to L69. Rejected on two grounds. That aliasing is a presentation concern owned by
 * `ui/src/layout`, whose own function-key hook records the reciprocal decision in its overview -- it
 * declines to reach the other way for the same reason, so neither module depends on the other and
 * there is no cycle for either to introduce. And a data module that listened for keystrokes could not
 * be exercised without simulating a keyboard, which would make the paging invariants below far harder
 * to assert than they are. A screen therefore binds the backward key to `prevPage` and the forward key
 * to `nextPage` itself.
 */

import { useCallback, useEffect, useReducer, useRef } from 'react';

import { isApiError, isApiRequestError, withoutConcurrentDuplicate } from '../api/client';
import type { ApiRequestError } from '../api/client';
import type { ApiError, PageDirection, PageResponse } from '../api/types';

/**
 * Ordinal of the opening page, and the only ordinal at which a backward step is refused.
 *
 * Assumptions: one, because the reference's condition name says one -- `88 CA-FIRST-PAGE VALUE 1` on
 * `WS-CA-SCREEN-NUM` at `app/cbl/COCRDLIC.cbl` L237 to L238. It is named here rather than written as
 * a bare number in the guards below so that each guard reads as the reference's own test.
 */
const FIRST_PAGE_NUMBER = 1;

/**
 * Prefix that keeps this module's single-flight keys apart from every other user of the shared guard.
 *
 * Assumptions: `withoutConcurrentDuplicate` in `ui/src/api/client.ts` holds ONE module-scoped map, and
 * the deletion screens already key it by method and target -- `DELETE /auth/users/USER0100`. A browse
 * turn is neither a method nor a target, so it is prefixed rather than spelled the same way: two
 * different kinds of work sharing one map must not be able to compose the same string, or a page turn
 * would join a deletion and be answered by its outcome.
 */
const COALESCING_KEY_PREFIX = 'BROWSE';

/**
 * The cursor stand-in used in a key when the turn is an opening read.
 *
 * Assumptions: a literal is needed because `null` and the empty string are both spellings a cursor
 * could in principle take, and the key is a string comparison -- so the opening read needs a token no
 * sealed cursor can equal. Parenthesised for that reason: the services seal base64url tokens, which
 * cannot contain a parenthesis.
 */
const OPENING_READ_POSITION = '(opening)';

/**
 * How many browses have been mounted in this document, which is where a browse identity comes from.
 *
 * Alternatives Considered: `useId`, which React provides for exactly this shape of problem and needs no
 * module state. Rejected because its value is derived from the component's position in the tree, so two
 * mounts of the same screen at the same position across a route change could reuse one -- and reuse is
 * the single thing this counter exists to rule out. A monotonic counter cannot repeat within a
 * document's lifetime, which is the whole of what the key needs from it.
 */
let browsesMounted = 0;

/**
 * Claims an identity no other mounted browse in this document holds.
 *
 * Purpose: the shared single-flight guard is keyed by string in one module-scoped map, so two browses
 * that composed the same key would collapse into one read. Two list screens mounted together -- or one
 * screen holding two browses -- both take their opening turn with no cursor and the forward direction,
 * so without an identity in the key the second would be handed the first's page and would never issue
 * a read of its own.
 * @returns {number} The claimed identity, distinct from every identity claimed before it.
 */
function claimBrowseIdentity(): number {
  browsesMounted += 1;
  return browsesMounted;
}

/**
 * Composes the key one page turn is coalesced under.
 *
 * Purpose: ⚠️ this states which turns count as THE SAME TURN, which is the whole of the coalescing
 * decision. Two turns collapse only when all four parts match: the same mounted browse, the same
 * generation of the set it is browsing, the same direction, and the same position read from.
 *
 * Assumptions: so two forward steps from one trailing cursor ARE the same turn and the second joins the
 * first -- that is the measured defect, where three presses of a forward key produced three identical
 * requests. And a forward step and a backward step from the same cursor are NOT the same turn, because
 * they read opposite ways from one position and answer with different pages; suppressing either would
 * strand the browse. Nor is a turn of one browse ever the same as a turn of another, nor a turn taken
 * after the set changed identity the same as one taken before, which is what {@link claimBrowseIdentity}
 * and the epoch supply.
 *
 * Assumptions: the cursor goes LAST and every other part is a number or one of two direction words, so
 * no two distinct turns can compose one key by running a delimiter together with a neighbouring value.
 * An opaque cursor may hold anything, and putting it anywhere but last would make that a real risk.
 * @param {number} identity - Which mounted browse is taking the turn.
 * @param {number} epoch - Which generation of that browse's set the turn belongs to; see the epoch ref
 *   in {@link usePagedQuery}.
 * @param {PageDirection} direction - Direction the cursor is replayed in.
 * @param {string | null} cursor - Position the turn reads from, or `null` for an opening read.
 * @returns {string} The key this turn is coalesced under.
 */
function coalescingKeyOf(
  identity: number,
  epoch: number,
  direction: PageDirection,
  cursor: string | null,
): string {
  const position = cursor ?? OPENING_READ_POSITION;
  return `${COALESCING_KEY_PREFIX} ${String(identity)}.${String(epoch)} ${direction} ${position}`;
}

/**
 * The two values one browse request carries.
 *
 * Assumptions: these two and no others. Every already-authored client accepts exactly a sealed
 * cursor and the direction it was sealed for -- `CardListQuery`, `UserListQuery`,
 * `ReferenceListQuery`, `LookupListQuery` and their siblings in `ui/src/api/types.ts` declare
 * `cursor` and `direction` and nothing describing a row count or a position -- because the service
 * reads one row beyond the page it intends to return and reports that row's existence rather than
 * being told how many rows to produce. A member naming the row arity would therefore describe a
 * request no service accepts.
 */
export interface PagedQueryRequest {
  /**
   * Sealed cursor the page is read from, or `null` for the opening read.
   *
   * Assumptions: opaque. It is replayed to the service byte for byte and is never parsed, split,
   * compared with another cursor or rebuilt from a row. What a cursor seals differs per browse and is
   * none of this module's business.
   *
   * Refactoring Rationale: what the card browse's cursor seals is the SINGLE sixteen-character card
   * number, and the twenty-seven-character composite cited here before is the reference's own
   * COMMAREA record identifier rather than the target's position -- the two are recorded apart because
   * conflating them is what a reader would otherwise carry away.
   * `services/card-service/src/main/java/com/carddemo/card/service/CardListService.java` states at L425
   * to L437 that the keyset predicate is that one card number in both directions and never a composite
   * of it with the account identifier, and it gives the evidence from three independent places: all six
   * record identifiers of the reference browse pass the length of the `X(16)` member alone rather than
   * the enclosing group, every statement that would have added the account identifier is commented out,
   * and the cluster definition agrees from outside the program with `KEYS(16 0)` at
   * `app/jcl/CARDFILE.jcl` L54. The account narrowing a filtered browse carries is not lost by that: it
   * goes into the cursor's authenticated BINDING scope, at that class's `listCursorBinding`, so a token
   * is honoured only under the same narrowing that produced it while the position it names stays a
   * single key. The composite still exists in the reference and is still worth citing -- the twenty-
   * seven-character group at `app/cbl/COCRDLIC.cbl` L230 to L232, card number then account identifier
   * -- as is the twenty-eight-character DISPLAY row at L258 to L260, which runs in the opposite order
   * and ends with a status character belonging to no key at all. Their value is as a warning about
   * composing a position from what a screen shows, which is why this member is opaque.
   *
   * Assumptions: the remaining browses seal scalars of three further widths -- sixteen characters at
   * `app/cbl/COTRN00C.cbl` L63 to L64, eight at `app/cbl/COUSR00C.cbl` L68 to L69 and two at
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L399 and L401 -- so one text member serves every
   * shape and no key type parameter is introduced for any of them. `ui/src/api/types.ts` records that
   * the service seals the DIRECTION into the token as well, so a token replayed the other way is
   * refused with HTTP 400 rather than answered with the wrong page.
   */
  readonly cursor: string | null;
  /** Direction the cursor is replayed in, forward for a next page and backward for a previous one. */
  readonly direction: PageDirection;
}

/**
 * What a screen supplies to open a browse.
 * @template T The row type of one page, which is whatever shape the operation behind `fetchPage`
 *   returns -- a card summary, a user summary, a transaction summary and so on.
 */
export interface UsePagedQueryOptions<T> {
  /**
   * How many rows the screen has room for, as its mapset declares.
   *
   * Alternatives Considered: giving this a default. Rejected because the measured arities are seven,
   * ten, ten, seven and five across the five browses cited in this module's own overview, so any
   * default would be wrong on at least three of them -- and wrong invisibly, since a screen that
   * accepted the default would render the arity of a different screen while compiling and passing
   * every type check.
   *
   * Assumptions: this value never reaches the service. It is not sent as a request member, it is not
   * turned into one, and {@link PagedQueryRequest} has nowhere to put it; the service establishes
   * page availability from a read of one row beyond the page it returns, which is how the reference
   * establishes it too. Its three uses here are all local: it is validated as a row count, it is the
   * bound a delivered page is checked against before being published, and it is published back on the
   * result so the screen renders that many row positions from one declaration.
   */
  readonly pageSize: number;
  /**
   * Reads one page, given a position and the direction to read it in.
   *
   * Alternatives Considered: reaching the five client modules that publish a browse -- `cards`,
   * `transactions`, `auth`, `authorization` and `reference` under `ui/src/api` -- from in here and
   * selecting one per screen. Rejected because it would couple one module to five bounded contexts
   * at once, and because those modules address a live edge through axios, so every assertion about
   * the paging invariants below would then need a transport stubbed to make it. Injection keeps this
   * module's subject the cursor arithmetic alone.
   * @param {PagedQueryRequest} request - The position to read from and the direction to read in.
   * @returns {Promise<PageResponse<T>>} One page, whose two cursors address the pages either side of
   *   it and whose `hasNext` reports whether reading on yields another.
   */
  readonly fetchPage: (request: PagedQueryRequest) => Promise<PageResponse<T>>;
  /**
   * Whether the browse may read at all, defaulting to `true`.
   *
   * Assumptions: a screen whose list is meaningless until a criterion is entered passes `false` and
   * raises it once one is. The reference has the same shape -- `app/cbl/COTRN00C.cbl` carries an
   * optional entry field beside its list and, at L206 to L207, positions from the start of the set
   * when that field is blank -- so this is the switch that lets a screen defer its opening read
   * instead of issuing one whose answer it would discard.
   *
   * Assumptions: lowered, it holds back EVERY read and not merely the opening one. The restart, forward
   * and backward steps each become documented no-ops, an outstanding read is superseded so it cannot
   * settle into the browse afterwards, and the browse returns to the state it would have had if the
   * screen had mounted with the switch already down: no rows, no cursors, the opening ordinal, nothing
   * outstanding and no failure. Raising it again opens the browse at the first page of whatever criteria
   * now hold, rather than resuming a position whose criteria the operator can no longer see.
   */
  readonly enabled?: boolean;
  /**
   * Value whose change restarts the browse at the opening page.
   *
   * Assumptions: a single string, which a screen composes from however many criteria it has. It is
   * compared for equality and never interpreted, so what a screen puts in it is its own business; a
   * string is chosen over an arbitrary value because two objects that describe identical criteria
   * are not equal to each other, and a browse that restarted on every render would page nowhere.
   */
  readonly resetKey?: string;
}

/**
 * Where the page on display sits in the browse, as one value.
 *
 * Purpose: ⚠️ this is the single derivation of boundary state, and it exists because five screens had
 * five. A review of the shipped build found the two availability members composed differently on each
 * browse -- one decided a backward step from the page ordinal, one from `hasPrev`, one from the cursor
 * pair, and the zero-row case was expressed on some screens and not on others -- so the same browse
 * state produced four different answers to "am I at the top?". Every one of those derivations was
 * CORRECT; what diverged was the idiom, and an idiom that diverges five ways is one a sixth screen
 * cannot copy. This member states the position once so a screen branches on a named state instead of
 * re-deriving one.
 *
 * Assumptions: the five states are exhaustive and mutually exclusive, and the dead end is one of them
 * rather than a flag beside them. A browse with nothing on display and nowhere to go is not "the first
 * of several pages with nothing on it": neither step is expressible, no ordinal is worth showing, and
 * the sentence a screen shows is neither boundary sentence. Folding it in is what stops each screen
 * deciding for itself whether that counts as a boundary -- which is how one browse came to leave both
 * paging keys offering a step with no rows loaded while another greyed both.
 *
 * Assumptions: ⚠️ what a screen does with this is to CHOOSE A SENTENCE, not to disable a key, and that
 * is taken from the reference rather than preferred. None of the five source programs refuses a paging
 * key: each answers it and re-sends the screen with a message attached -- `app/cbl/COUSR00C.cbl` L248
 * to L254 and L271 to L277, `app/cbl/COTRN00C.cbl` L245 to L251 and L268 to L274,
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L380 to L384 and L408 to L411,
 * `app/cbl/COCRDLIC.cbl` L901 to L903, and
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L766 to L767 and L780 to L781. A greyed key
 * therefore loses a verbatim string transformation rule T8 requires, and worse, `ui/src/layout`'s
 * function-key hook answers a disabled binding with the unmapped-key message -- so disabling a
 * backward key on the opening page shows "invalid key" exactly where the reference shows "You are
 * already at the top of the page...". The step itself is already a documented no-op here, so nothing
 * needs disabling to stay safe.
 *
 * Alternatives Considered: publishing a `hasPrevious` member on the wire envelope so the server
 * answered this. Refused on two grounds. It is not what the reference does -- every one of the
 * programs cited above decides a backward step from its own ordinal with no read issued to check --
 * and AAP section 0.4.4 fixes the envelope at `items`, `firstKey`, `lastKey` and `hasNext`, which the
 * seven published contracts implement and `ui/src/api/contracts.test.ts` holds them to. Backward
 * availability is a client derivation by design, and this is where it is derived.
 */
export type PageBoundary =
  /**
   * Nothing on display and nowhere to go: no rows, no further page and no page behind.
   *
   * Assumptions: this is narrower than "no rows", and the difference matters. A page can carry no rows
   * while the browse continues on both sides -- the reference filters AFTER reading a screen's worth,
   * at `app/cbl/COCRDLIC.cbl` L1382, and `PageResponse.ofFilteredEmpty` reproduces it -- and such a
   * page reports whichever positional state its cursors support, not this one.
   */
  | 'EMPTY'
  /** The only page: rows are on display and neither step is expressible. */
  | 'ONLY'
  /** The opening page of several: a forward step is expressible, a backward one is not. */
  | 'FIRST'
  /** Between two pages: both steps are expressible. */
  | 'INTERIOR'
  /** The final page of several: a backward step is expressible, a forward one is not. */
  | 'LAST';

/**
 * The browse as a screen observes it.
 * @template T The row type of one page, as supplied to {@link UsePagedQueryOptions}.
 */
export interface UsePagedQueryResult<T> {
  /**
   * Rows of the page on display, in the order they are meant to be read.
   *
   * Assumptions: already ascending whichever direction produced them, so nothing is reordered here.
   * A backward read runs descending in the store and the service turns it back before publishing it
   * -- `services/card-service/src/main/java/com/carddemo/card/service/CardListService.java` states at
   * L331 to L332 that a published page is always ascending whichever way the browse travelled, and
   * reverses a copy at L963 to L966 to make it so. The envelope's own contract closes the argument:
   * `PageResponse` documents `firstKey` as the first row IN `items` and `lastKey` as the last, which
   * only holds while `items` runs from the lower key towards the higher one.
   *
   * Trade-offs: reversing them here is therefore refused even though it superficially resembles the
   * reference, which does reverse -- `app/cbl/COTRN00C.cbl` fills its row array from the tenth
   * position down to the first while reading backward, at L349 to L355, precisely so the terminal
   * displays them ascending. The service now performs that same turn, so doing it again would reverse
   * an already-ascending page and the browse would appear to walk two pages backward for every step
   * taken. Passing them through is what preserves the reference's outcome; imitating its mechanism
   * would not.
   */
  readonly items: readonly T[];
  /**
   * Whether reading forward from this page yields another.
   *
   * Assumptions: taken from the envelope verbatim in both directions and never worked out from how
   * many rows arrived, because the row that settles the question is deliberately not among them. The
   * reference settles it the same way, by reading one row past the page and reporting whether that
   * read succeeded: `app/cbl/COCRDLIC.cbl` issues the extra READNEXT at L1197, turns the indicator on
   * at L1207 to L1214 and off at L1215 to L1221; `app/cbl/COTRN00C.cbl` runs the same probe at L308;
   * and `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` runs it at L446. A count of the rows
   * received cannot answer it, since a full page and a full page that happens to be the last one hold
   * the same number of rows.
   *
   * Trade-offs: this one member carries what the reference splits across two. Beside its
   * further-page indicator it declares a separate last-page-displayed flag -- `app/cbl/COCRDLIC.cbl`
   * L239 to L241 and `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L405 to L407 -- whose polarity
   * runs the way nobody expects: shown is nought and not-shown is nine. The envelope drops it and
   * this member subsumes it, so no second boolean is reintroduced here. What is given up is the one
   * distinction the reference draws with it, at `app/cbl/COCRDLIC.cbl` L905 to L908, where the
   * sentence about having reached the end is raised only when both the indicator is off AND the last
   * page has been shown. A screen wanting that distinction composes it from this member and the
   * ordinal. The polarity is recorded here so that nobody restoring the flag restores it inverted.
   */
  readonly hasNext: boolean;
  /**
   * Whether a backward step is available.
   *
   * Assumptions: derived from the ordinal below and from whether a position exists to read backward
   * from, never from the envelope, which carries no such member by design. This is not a workaround
   * for something missing -- it is what the reference does. It never asks the file whether an earlier
   * page exists; it asks its own ordinal: `app/cbl/COTRN00C.cbl` L245, `app/cbl/COUSR00C.cbl` L248,
   * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L365, and `app/cbl/COCRDLIC.cbl` L901 to
   * L903, where the refusal is decided from `CA-FIRST-PAGE` alone with no backward read issued to
   * check. `firstKey` is required as well because it is the value the request would be issued FROM,
   * and `ui/src/api/types.ts` records that its presence is what tells a caller a backward step is
   * expressible at all.
   */
  readonly hasPrev: boolean;
  /**
   * Which page is on display, counting from one, for display and for the backward guard.
   *
   * Trade-offs: this counts pages seen and is never a row position -- nothing derives a request from
   * it, it is never sent anywhere, and no row is addressed through it. What is given up is any
   * ability to reach an arbitrary page directly or to say how many pages exist, because a keyed
   * envelope cannot answer either without walking the whole set. What is kept is the two things the
   * reference uses its own ordinal for, and it uses it for nothing else: refusing a backward step
   * from the opening page, and displaying the number -- `app/cbl/COTRN00C.cbl` increments it at L306
   * to L307 and moves it to the map field at L324, and
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` moves the trailing cursor and increments at L768
   * to L770, then moves the leading cursor and decrements at L782 to L784.
   */
  readonly pageNumber: number;
  /**
   * Where this page sits in the browse, as one named state.
   *
   * Assumptions: this is derived from `items`, `hasNext` and `hasPrev` and adds no information beyond
   * them -- deliberately, because the defect it answers was not missing information but five different
   * expressions of the same information. A screen showing a boundary sentence branches on this; a
   * screen showing a page ordinal reads {@link UsePagedQueryResult.pageNumber}; a screen wanting to
   * know whether one step is expressible reads {@link UsePagedQueryResult.hasNext} or
   * {@link UsePagedQueryResult.hasPrev}. Those three uses were previously served by
   * each screen composing its own predicate, which is how `EMPTY` came to be handled on some browses
   * and not on others.
   *
   * Trade-offs: it is published as well as the two booleans rather than instead of them, because the
   * booleans are what a step is guarded on and what an availability test asserts, and collapsing them
   * into this would force every guard to compare against a set of names. The redundancy is stated
   * here so nobody removes one half believing it duplicates the other.
   */
  readonly boundary: PageBoundary;
  /** How many rows the screen has room for, as supplied, so it renders that many row positions. */
  readonly pageSize: number;
  /** Whether a read is outstanding. */
  readonly isLoading: boolean;
  /**
   * Whether the most recent read ended in refusal or failure.
   *
   * Assumptions: this is separate from `error` because two failures carry no document at all, and a
   * browse that quietly kept displaying its previous rows in either case would be the silent broken
   * view this flag exists to prevent. The first is a reader that rejected with something other than the
   * shared client's normalised failure -- a plain `Error` from a screen's own reader. The second is
   * this module's own refusal of a page carrying more rows than the screen declared room for, which no
   * service reported and for which nothing may be invented. So this answers whether the read failed and
   * `error` answers what the service said about it.
   */
  readonly isFailed: boolean;
  /**
   * Problem document from the most recent failure, or `null`.
   *
   * Refactoring Rationale: this is populated for every failure the shared client raises, which is every
   * failure a browse reading through `ui/src/api` can have. That client classifies each one and carries
   * a complete document on it -- the service's own body verbatim for a classified problem response, and
   * a document it synthesises with an empty field-error array for a timeout, a network fault or an
   * unusable body -- so the code, the sentence, the per-field entries and above all the correlation
   * identifier an operator quotes to support all reach a screen. An earlier revision of this module
   * looked for that document in the raw axios shape, which the client's interceptor has already replaced
   * by the time a read rejects, so `error` was null on every single failure while `isFailed` was true.
   *
   * Assumptions: `null` therefore now means one of three things -- nothing has failed, a reader rejected
   * with something that is neither the shared failure nor a document, or this module refused an
   * over-long page. `isFailed` remains the flag to branch on. No stand-in is invented for those cases: a
   * fabricated code, severity or correlation identifier would be indistinguishable from one a service
   * actually sent, and a screen quoting a made-up correlation identifier to support is worse off than
   * one quoting none.
   */
  readonly error: ApiError | null;
  /**
   * The shared client's classified failure from the most recent read, or `null`.
   *
   * Purpose: ⚠️ a browse could not tell a retryable transport fault from a refusal the operator has to
   * act on, and two reviews measured the consequence from opposite ends. One found a timeout, a dropped
   * connection and a 500 rendered identically on every route, with one screen describing a 404 as a
   * temporary availability problem because nothing on the failure said otherwise. The other found a
   * conflict on a save reported as "the record does not exist". The information existed the whole time:
   * `ui/src/api/client.ts` classifies every failure into one of four kinds and carries
   * `transient` and `repeatable` beside it, and this module already narrowed the rejection to that type
   * in order to read {@link UsePagedQueryResult.error} out of it -- and then dropped everything else.
   *
   * Assumptions: this sits BESIDE `error` rather than replacing or re-typing it, and that is deliberate
   * rather than cautious. `error` is the flattened problem document a screen renders a sentence and its
   * per-field entries from, five screens already read it, and it is populated on routes this member is
   * not -- a reader that rejects with a bare document supplies one and no classified failure. Re-typing
   * it would have made every existing consumer reach one member deeper to reach the sentence it already
   * has, for no gain to any of them.
   *
   * Assumptions: `null` therefore means one of three things -- nothing has failed, the read rejected
   * with something that is not one of the shared client's failures (a reader's own `Error`, or a bare
   * document), or this module refused the delivered page itself. In the last case no service failed at
   * all, so there is no kind to report and none is invented; `isFailed` remains the flag to branch on.
   *
   * Assumptions: a screen reads this through the exported predicates rather than comparing `kind`
   * itself -- `isTransientFailure`, `isRepeatableFailure` and `isConflictFailure` in
   * `ui/src/api/client.ts` -- for the reason those predicates were exported: a status list copied into
   * each screen is a status list one screen will omit an entry from.
   */
  readonly failure: ApiRequestError | null;
  /**
   * Reads the page after the one on display, which is the forward paging key's action.
   *
   * Assumptions: a call made while `hasNext` is false, with no trailing cursor to read from, or while
   * {@link UsePagedQueryOptions.enabled} is lowered, is a documented no-op -- no request is issued, the
   * rows, cursors and ordinal are left untouched, and nothing is thrown. That mirrors the reference,
   * which gates its forward arm on the same indicator
   * at `app/cbl/COCRDLIC.cbl` L486 to L487 and at
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L766 to L767 and answers the refused step by
   * re-sending the screen with a sentence attached.
   *
   * Assumptions: ⚠️ the returned promise resolves when the turn has SETTLED into this browse -- the
   * page published or the failure recorded -- and a refused step resolves immediately having issued
   * nothing. It never rejects: both outcomes are recorded through the reducer, which is what lets a
   * caller that discards it behave exactly as it did when this returned nothing.
   *
   * Refactoring Rationale: it returned `void`, and a caller therefore could not tell when the turn
   * ended. A review measured both halves of the consequence on one browse: the pressed key stayed
   * `disabled:false` with an unchanged class for the whole flight, because the only busy affordance was
   * the table's own overlay, and two presses 400 ms apart produced two identical requests that left the
   * ordinal reading one page further than the rows on display. Awaiting the turn is what lets a screen
   * paint a busy state over the control that owns it; the duplicate itself is closed inside this module
   * so that a screen which never adopts the promise is protected anyway.
   * @returns {Promise<void>} Resolves once the turn has settled into this browse, or immediately when
   *   the step is refused. The outcome itself is observed through this result on a later render.
   */
  readonly nextPage: () => Promise<void>;
  /**
   * Reads the page before the one on display, which is the backward paging key's action.
   *
   * Assumptions: a call made on the opening page, with no leading cursor to read from, or while
   * {@link UsePagedQueryOptions.enabled} is lowered, is a documented no-op on the same terms as
   * `nextPage`. The reference gates its backward arm the same
   * way, on its ordinal rather than on anything read from the file --
   * `app/cbl/COCRDLIC.cbl` L501 to L502 and
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L780 to L781.
   *
   * Assumptions: the returned promise behaves exactly as `nextPage`'s does -- it resolves when the turn
   * has settled, resolves immediately for a refused step, and never rejects.
   * @returns {Promise<void>} Resolves once the turn has settled into this browse, or immediately when
   *   the step is refused. The outcome itself is observed through this result on a later render.
   */
  readonly prevPage: () => Promise<void>;
  /**
   * Returns the browse to its opening page and reads it again.
   *
   * Assumptions: the rows on display are kept until the opening page arrives, rather than being
   * cleared first. Clearing them would blank the table for the duration of a request that may well
   * return the same rows, and the reference has no equivalent moment: it composes the whole screen
   * once the read has finished.
   *
   * Assumptions: a call made while {@link UsePagedQueryOptions.enabled} is lowered is a documented
   * no-op, as the two paging steps are. This step has no guard of its own -- an opening read is always
   * expressible -- so the switch is the only thing that refuses it, and it does so at the one point all
   * three steps pass through.
   *
   * Assumptions: the returned promise behaves exactly as `nextPage`'s does, and awaiting it is what lets
   * a screen refreshing after a mutation write its sentence once the refreshed page is on display rather
   * than over the page the mutation replaced.
   * @returns {Promise<void>} Resolves once the refreshed opening page has settled into this browse, or
   *   immediately when the browse is held back. The outcome itself is observed through this result on a
   *   later render.
   */
  readonly reset: () => Promise<void>;
}

/**
 * Everything one browse holds between renders.
 *
 * Alternatives Considered: several independent pieces of state, one per member, which is how the
 * already-authored card list screen holds the same information for one screen. Rejected for the
 * shared arrangement because these members are not independent of one another -- a delivered page
 * moves the rows, both cursors, the availability indicator, the ordinal and the outstanding-read flag
 * together, and all of those moves have to be abandoned together when the page turns out to be a
 * stale one. Held as separate pieces, that atomicity rests on the order the updates happen to be
 * written in; held as one value behind a reducer, it is a property of a single function that can be
 * read and asserted on directly.
 * @template T The row type of one page.
 */
interface PagedQueryState<T> {
  /** Rows of the page on display, ascending, exactly as the envelope delivered them. */
  readonly items: readonly T[];
  /**
   * Sealed cursor naming this page's leading boundary, or `null` when the envelope carried none.
   *
   * Refactoring Rationale: nullability is stated against the ENVELOPE and not against the row count,
   * because the two come apart and this pair is what the backward guard is decided from. An earlier
   * revision described this as null "when the page carried no rows", which reads as though an empty
   * page always has null boundaries -- and one kind of empty page does not.
   * `PageResponse.ofFilteredEmpty` in common-lib exists for exactly that case: a read whose every row
   * failed a filter applied after the rows were fetched returns no rows while rows remain on both sides
   * of it, so its two boundaries carry the keys at which scanning stopped in each direction rather than
   * the identities of returned rows. That page is still pageable in both directions, which is the
   * baseline's own behaviour -- `app/cbl/COCRDLIC.cbl` applies `9500-FILTER-RECORDS.` at L1382 only
   * after reading a screen's worth. The correct reading is therefore the simple one: this is whatever
   * the envelope published, empty rows or not, and `null` means the envelope published none.
   */
  readonly firstKey: string | null;
  /**
   * Sealed cursor naming this page's trailing boundary, or `null` when the envelope carried none.
   *
   * Assumptions: the same reading as the leading boundary above, and for the same reason -- taken
   * verbatim from the envelope, never inferred from how many rows arrived.
   */
  readonly lastKey: string | null;
  /** Whether the envelope reported a further page beyond this one. */
  readonly hasNext: boolean;
  /** Which page is on display, counting from one; a page count and never a row position. */
  readonly pageNumber: number;
  /** Whether a read is outstanding. */
  readonly isLoading: boolean;
  /** Whether the most recent read ended in refusal or failure. */
  readonly isFailed: boolean;
  /** Problem document from the most recent failure, or `null`. */
  readonly error: ApiError | null;
  /**
   * Classified failure from the most recent read, or `null`.
   *
   * Assumptions: held beside the document rather than being the only member, because the two are
   * populated on overlapping but different sets of rejections -- a bare document supplies the first and
   * not the second, and this module's own refusal of an over-long page supplies neither. Deriving one
   * from the other in the reducer would therefore have to invent the missing half.
   */
  readonly failure: ApiRequestError | null;
  /**
   * Sequence number of the most recently STARTED read.
   *
   * Assumptions: this is the whole of the stale-response guard, and it lives in the state rather than
   * beside it so that the discard is a property of the reducer. A settlement carrying any other
   * number belongs to a read that has since been superseded and is dropped without touching a single
   * other member.
   */
  readonly startedSequence: number;
}

/**
 * The four transitions a browse can make.
 *
 * Assumptions: every transition carries the sequence number of the read it belongs to, including the
 * one that starts a read, because that is what lets the reducer tell a settlement of the current read
 * from a settlement of an abandoned one without consulting anything outside its two arguments. The
 * idling transition belongs to no read and carries a freshly allocated number for the same mechanism
 * read the other way round: adopting a number no outstanding read holds is what supersedes every read
 * started before it.
 *
 * Assumptions: a settlement also carries the cursor and direction of the read that produced it, and
 * carries them for one purpose -- they are the only two things the ordinal can be worked out from, and
 * working it out from the request rather than from the answer is what distinguishes an opening read
 * from a forward step. Neither value is stored: they are consumed by `ordinalAfter` and discarded,
 * because the cursors a browse keeps are the ones the envelope publishes.
 * @template T The row type of one page.
 */
type PagedQueryAction<T> =
  | { readonly kind: 'browse-started'; readonly sequence: number }
  | { readonly kind: 'query-restarted'; readonly sequence: number }
  | {
      readonly kind: 'page-settled';
      readonly sequence: number;
      readonly cursor: string | null;
      readonly direction: PageDirection;
      readonly page: PageResponse<T>;
    }
  | {
      readonly kind: 'browse-failed';
      readonly sequence: number;
      readonly error: ApiError | null;
      /**
       * The shared client's classified failure, when the rejection was one.
       *
       * Assumptions: carried as a SECOND member of this transition rather than being derived from
       * `error` inside the reducer. The document and the classification are read out of the rejection
       * by two separate narrowings at the dispatch site, and a reducer given only the document could
       * not recover the classification from it -- a synthesised document for a timeout and a service's
       * own document for a refusal are the same shape, which is the whole reason the kind exists.
       */
      readonly failure: ApiRequestError | null;
    }
  | { readonly kind: 'browse-idled'; readonly sequence: number };

/**
 * Works out which page a delivered page is, from the request that produced it.
 *
 * Assumptions: a read carrying no cursor is an opening read -- first render, or a restart -- and
 * lands on the opening page, so it assigns rather than adds. This mirrors the reference, where the
 * ordinal is raised to one on first entry at `app/cbl/COCRDLIC.cbl` L1177 to L1181 and only the two
 * paging arms move it afterwards: `ADD +1` at L492 and `SUBTRACT 1` at L508.
 *
 * Assumptions: the ordinal moves only once a page has actually been DELIVERED. Moving it when a read
 * STARTS would leave it one page away from the rows on display after any failure, and the backward
 * guard is decided from it -- so a refused forward step would then permit a backward step from the
 * page still on screen.
 *
 * Trade-offs: the floor is applied here as well as in the backward guard, so the invariant that no
 * page precedes the first holds in the reducer whether or not a caller respected the guard. The
 * reference floors it in the same belt-and-braces way, subtracting one at `app/cbl/COTRN00C.cbl` L364
 * only when its own test passed and otherwise assigning one outright at L366. No ceiling is applied:
 * the reference field is `PIC 9(1)` at `app/cbl/COCRDLIC.cbl` L237 so its tenth page wraps to zero,
 * but the only use made of the value is the comparison with one, which zero fails exactly as ten
 * does, so the wrap is unobservable and is not reproduced.
 * @param {number} current - Ordinal of the page displayed before this one arrived.
 * @param {string | null} cursor - Cursor the delivered page was read from, or `null` for an opening
 *   read.
 * @param {PageDirection} direction - Direction the cursor was replayed in.
 * @returns {number} Ordinal of the delivered page, never below the opening page.
 */
function ordinalAfter(current: number, cursor: string | null, direction: PageDirection): number {
  if (cursor === null) {
    return FIRST_PAGE_NUMBER;
  }
  return direction === 'previous' ? Math.max(current - 1, FIRST_PAGE_NUMBER) : current + 1;
}

/**
 * Names where the page on display sits in the browse.
 *
 * Assumptions: ⚠️ `EMPTY` requires no rows AND no step in either direction, and the row count alone is
 * deliberately NOT enough. A page can carry no rows while records remain on both sides of it, and that
 * state is the reference baseline's rather than an invention: the card list reads a screen's worth of
 * records and only then applies its filter at `app/cbl/COCRDLIC.cbl` L1382, so a read whose every
 * record fails the filter displays nothing while the browse continues in both directions.
 * `PageResponse.ofFilteredEmpty` in
 * `services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java` L500 produces exactly
 * that envelope -- no rows, both boundary members carrying the keys at which scanning stopped, and a
 * further page reported. Calling it `EMPTY` would tell a screen to show "there are no records" over a
 * browse it can page straight out of, which is why the two availability values are tested and not just
 * the row count.
 *
 * Assumptions: so `EMPTY` is the DEAD END -- nothing on display and nowhere to go -- and it is the one
 * state where both paging keys have nothing to answer with but the boundary sentence. A filtered-away
 * page reports whichever of the four positional states its cursors support, and a screen tells that
 * apart from a populated page by the row count it already renders from.
 *
 * Trade-offs: `ONLY` therefore never describes a page with no rows, because no rows and no steps is
 * `EMPTY` by the test above. That is stated rather than left to be inferred, so nobody adds a row-count
 * test to a screen's `ONLY` arm to cover a case that cannot reach it.
 * @param {number} rowCount - How many rows the delivered page holds.
 * @param {boolean} hasNext - Whether the envelope reported a further page.
 * @param {boolean} hasPrev - Whether a backward step is expressible, as derived from the ordinal and
 *   the leading cursor.
 * @returns {PageBoundary} The one named state describing this page's position.
 */
function pageBoundaryOf(rowCount: number, hasNext: boolean, hasPrev: boolean): PageBoundary {
  if (rowCount === 0 && !hasNext && !hasPrev) {
    return 'EMPTY';
  }
  if (hasPrev) {
    return hasNext ? 'INTERIOR' : 'LAST';
  }
  return hasNext ? 'FIRST' : 'ONLY';
}

/**
 * Finds the problem document a rejected read carried, if it carried one.
 *
 * Refactoring Rationale: this reads the NORMALISED failure the shared client raises, where an earlier
 * revision reached for `reason.response.data` -- the raw axios shape -- as its PRIMARY route. That
 * shape never arrives from a shipped client. `ui/src/api/client.ts` installs a response interceptor
 * whose rejection handler converts every axios failure into an `ApiRequestError` and throws THAT, so a
 * rejected read carries no `response` member at all and the raw-shape branch could not match. The
 * consequence was total rather than partial: every service refusal reported `isFailed` true with
 * `error` null, so a screen lost the code, the sentence, the per-field entries and the correlation
 * identifier the service had in fact sent, on every failure of every browse.
 *
 * Refactoring Rationale: the earlier rationale for restating the document probe LOCALLY -- that
 * importing from the client module would pull a transport into a module that issues no request -- is
 * withdrawn, and with it a weaker local probe of three members. That module constructs its axios
 * instance lazily inside `getApiClient`, so importing two predicates from it starts nothing, and the
 * assertions below exercise this hook with an injected reader exactly as before. What the local probe
 * bought was a weaker check on a value the transport had usually already gated; what it cost was two
 * implementations of one rule, which is the condition under which they come to disagree.
 *
 * Assumptions: the nested `response.data` route is KEPT as a documented FALLBACK, even though no
 * shipped client takes it. The reader is injected, so a caller is free to reject with a raw transport
 * failure of its own -- a screen composing its own reader, or an assertion building one -- and reading
 * a document out of one costs nothing. It is validated through the same exported `isApiError` as every
 * other route, so the fallback admits nothing the primary routes would refuse.
 *
 * Assumptions: two routes are recognised and they are not equivalent. The first is the shared
 * failure, whose `problem` member is already a complete document -- verbatim from the service for a
 * classified problem response, and synthesised by that module with an empty field-error array for a
 * timeout, a network fault or an unusable body. Reading it needs no validation here, because the
 * producer built it. The second is a reader that rejects with a bare document, which the injected-reader
 * contract permits and which a screen composing its own reader may do; that route IS validated, through
 * the same exported `isApiError` the client uses, so exactly one implementation of that check exists.
 *
 * Assumptions: anything else yields `null` rather than a stand-in -- a reader that threw a plain
 * `Error`, or rejected with a string. {@link UsePagedQueryResult.isFailed} is what reports the failure
 * in that case, so nothing is invented here.
 * @param {unknown} reason - Whatever the read rejected with; not necessarily an `Error`.
 * @returns {ApiError | null} The problem document, or `null` when the rejection carried none.
 */
function problemDocumentOf(reason: unknown): ApiError | null {
  if (isApiRequestError(reason)) {
    return reason.problem;
  }
  if (isApiError(reason)) {
    return reason;
  }
  // Assumptions: the fallback below is reached only by a reader that rejects with a raw transport
  //   failure, which no shipped client does. Each step is checked rather than asserted -- a non-object
  //   rejection, a rejection with no `response`, and a response with no `data` all settle to `null` --
  //   and the payload is put through the SAME `isApiError` the two routes above use, so there is
  //   exactly one implementation of what a problem document is.
  if (typeof reason !== 'object' || reason === null || !('response' in reason)) {
    return null;
  }
  const response: unknown = reason.response;
  if (typeof response !== 'object' || response === null || !('data' in response)) {
    return null;
  }
  const payload: unknown = response.data;
  return isApiError(payload) ? payload : null;
}

/**
 * Finds the shared client's classified failure a rejected read carried, if it carried one.
 *
 * Purpose: this is the half of a rejection {@link problemDocumentOf} deliberately discards. That
 * function flattens a rejection to the problem document a screen renders a sentence from, which loses
 * the classification -- the kind, and whether the condition may clear or the request may be repeated --
 * because a document synthesised for a timeout and a document a service sent for a refusal are the same
 * shape. A browse that can only see the document therefore cannot tell "not available at the moment"
 * from "that did not work", which is what two reviews measured across every route.
 *
 * Assumptions: the ONE recognised route is the shared client's own failure type, tested with the
 * predicate that module exports rather than by probing for members. That predicate is an `instanceof`
 * check and this is the only producer of the type, so a value that merely looks like one did not come
 * from the transport and must not be presented as though its classification were the transport's.
 *
 * Assumptions: no fallback is offered and none is possible. `problemDocumentOf` can fall back to a bare
 * document because a document is data a caller may legitimately supply; a classification is a JUDGEMENT
 * the transport made, so there is nothing to read it out of when the transport was not involved. A
 * reader that rejects with its own `Error` therefore yields `null` here, and
 * {@link UsePagedQueryResult.isFailed} remains what reports that the read failed.
 * @param {unknown} reason - Whatever the read rejected with; not necessarily an `Error`.
 * @returns {ApiRequestError | null} The classified failure, or `null` when the rejection was not one.
 */
function requestFailureOf(reason: unknown): ApiRequestError | null {
  return isApiRequestError(reason) ? reason : null;
}

/**
 * Discards a settled turn's outcome, for the one call site that cannot observe it.
 *
 * Purpose: the opening read is issued from an effect, and an effect may return only a teardown -- so
 * the promise the engine returns cannot be returned from there, and `ui/eslint.config.js` configures
 * `no-floating-promises` with `ignoreVoid: false`, which withdraws the `void` discard as well. This
 * names what is happening instead of leaving an empty arrow at the call site.
 *
 * Assumptions: discarding is CORRECT there rather than merely permitted. Every outcome of a read is
 * already applied through the reducer, so a screen observes the opening read exactly as it observes
 * every other read -- through this hook's result on a later render. There is nothing here to act on.
 * @returns {void} Nothing; the outcome has already been recorded by the reducer.
 */
function ignoreSettledTurn(): void {
  // Assumptions: an empty body is the whole implementation, and it is deliberate rather than
  //   unfinished. Logging here would emit a line for every ordinary page turn on every browse.
}

/**
 * Builds the state a browse starts in.
 *
 * Assumptions: the outstanding-read flag starts raised exactly when the browse is allowed to read, so
 * that a screen shows its loading treatment from its very first paint instead of showing an empty
 * table for one frame and then replacing it. A browse held back by its switch starts idle, because
 * claiming to be reading when no read was issued would be a lie a screen renders.
 * @param {boolean} reading - Whether an opening read is going to be issued.
 * @returns {PagedQueryState<T>} The opening state: no rows, no cursors, no further page, the opening
 *   ordinal, and no read yet settled.
 * @template T The row type of one page.
 */
function openingState<T>(reading: boolean): PagedQueryState<T> {
  return {
    items: [],
    firstKey: null,
    lastKey: null,
    hasNext: false,
    pageNumber: FIRST_PAGE_NUMBER,
    isLoading: reading,
    isFailed: false,
    error: null,
    failure: null,
    startedSequence: 0,
  };
}

/**
 * Applies one transition to a browse.
 *
 * Assumptions: a settlement whose sequence number is not the one the most recent read started with is
 * DROPPED, and the state is returned unchanged. Two forward steps taken in quick succession can
 * settle in either order, and without this the earlier page would arrive last and overwrite the later
 * one -- leaving the rows of one page beside the cursors of another, from which the next step would
 * read the wrong place. The reference cannot have this failure at all: its tasks are
 * pseudo-conversational, so one screen turn is one task and a second turn cannot begin until the
 * first has ended. There is therefore no reference behaviour to preserve here, only a failure mode
 * this arrangement has to close.
 * @param {PagedQueryState<T>} state - The browse before the transition.
 * @param {PagedQueryAction<T>} action - The transition to apply.
 * @returns {PagedQueryState<T>} The browse after it, or the argument unchanged when the transition
 *   belongs to a superseded read.
 * @template T The row type of one page.
 */
function pagedQueryReducer<T>(
  state: PagedQueryState<T>,
  action: PagedQueryAction<T>,
): PagedQueryState<T> {
  switch (action.kind) {
    case 'browse-started':
      // Assumptions: the previous outcome is cleared as the new read starts, so a screen never shows
      //   a refusal from the read before beside a spinner for the read now outstanding. The rows and
      //   both cursors are deliberately kept, because the page on display stays legible and, if this
      //   read fails, remains the page the ordinal describes. That retention is correct ONLY while the
      //   rows belong to the query still being read; when the query itself changes, the sibling
      //   transition below is the one that runs.
      // Assumptions: the classification is cleared alongside the document, because the two describe
      //   ONE outcome and a screen branching on the classification while the document had been cleared
      //   would offer a repeat control for a failure that is no longer being reported.
      return {
        ...state,
        isLoading: true,
        isFailed: false,
        error: null,
        failure: null,
        startedSequence: action.sequence,
      };
    case 'query-restarted':
      // Refactoring Rationale: a change of query used to dispatch `browse-started`, which keeps the
      //   rows, both cursors and the ordinal. The consequence was not a stale frame but a WRONG one
      //   that persisted: a screen whose criteria changed kept the previous query's rows on display
      //   under the new criteria, and if the opening read of the new query then FAILED, the failure
      //   transition kept them too -- so the operator was left looking at rows belonging to a query
      //   they had already left, with no indication that the set on screen answered a different
      //   question. Clearing here is what makes the retention above safe to keep: retention is now
      //   scoped to reads OF THE SAME query, which is where its argument -- the page stays legible and
      //   the same step can be taken again -- actually holds.
      // Assumptions: the ordinal returns to the opening page as well as the rows being dropped,
      //   because it is the value `prevPage` refuses on and leaving it raised would offer a backward
      //   step from the first page of the new set.
      return { ...openingState<T>(true), startedSequence: action.sequence };
    case 'page-settled':
      if (action.sequence !== state.startedSequence) {
        return state;
      }
      return {
        // Assumptions: the envelope's own array is adopted rather than copied. It is declared
        //   read-only, so nothing here can disturb it, and copying would imply this module owns rows
        //   it is only forwarding.
        items: action.page.items,
        // Assumptions: both cursors are taken verbatim and neither is re-derived from the rows, which
        //   matters because the reference disagrees with itself about what its own trailing key
        //   names: `app/cbl/COCRDLIC.cbl` L1207 to L1214 overwrites it with the key of the surplus
        //   row read to establish availability, while `app/cbl/COTRN00C.cbl` reads its surplus row at
        //   L308 and keeps nothing from it. The envelope settles the question -- each cursor names a
        //   row actually delivered, never the surplus one -- so accepting its answer is what keeps
        //   the two sides agreeing. Re-deriving one here would also mean composing one, which the
        //   two incompatible field orders recorded on {@link PagedQueryRequest.cursor} show is not
        //   this module's business.
        firstKey: action.page.firstKey,
        lastKey: action.page.lastKey,
        hasNext: action.page.hasNext,
        pageNumber: ordinalAfter(state.pageNumber, action.cursor, action.direction),
        isLoading: false,
        isFailed: false,
        error: null,
        failure: null,
        startedSequence: state.startedSequence,
      };
    case 'browse-failed':
      if (action.sequence !== state.startedSequence) {
        return state;
      }
      // Assumptions: a failed read leaves the rows, both cursors and the ordinal exactly as they
      //   were, so the operator keeps the page in front of them and can take the same step again.
      //   The reference does the same, re-sending the screen it already composed.
      // Assumptions: both halves of the outcome are carried through together -- the document a screen
      //   renders its sentence from and the classification it chooses BETWEEN sentences on. They were
      //   narrowed from one rejection at the dispatch site, so publishing one without the other would
      //   put a screen in the position the reviews measured: able to show what went wrong and unable
      //   to say whether trying again could help.
      return {
        ...state,
        isLoading: false,
        isFailed: true,
        error: action.error,
        failure: action.failure,
      };
    case 'browse-idled':
      // Assumptions: this returns the browse to the state it would have had if the screen had mounted
      //   with its switch down, and it is applied UNCONDITIONALLY rather than under the sequence guard
      //   the two settlements carry. The guard's question is "does this settlement belong to the
      //   current read", and this transition belongs to no read: it is the browse being told there is
      //   nothing to read at all, so there is nothing for it to be stale against.
      // Assumptions: the rows and both cursors are DISCARDED here, where a failure keeps them. The
      //   switch is lowered when the screen's criteria stop being meaningful -- an entry field cleared
      //   -- so the rows on display answer a question that is no longer being asked, and a page read
      //   under criteria that no longer hold is worse than an empty table because nothing about it
      //   says which criteria produced it. Discarding them also makes re-enabling deterministic: the
      //   browse opens at the first page of the new criteria rather than resuming a position whose
      //   criteria the operator can no longer see.
      // Assumptions: the freshly allocated number is adopted as the started sequence, which is what
      //   makes an outstanding read's settlement arrive stale and be dropped by the two guards above
      //   rather than repopulating a browse that has been idled.
      return { ...openingState<T>(false), startedSequence: action.sequence };
  }
}

/**
 * Walks an ordered set one page at a time, by key, for a screen that pages with two keys.
 *
 * Assumptions: the envelope this consumes is the one every browse operation publishes, with four
 * members and no more -- the rows, a cursor at each boundary and whether reading forward yields
 * another page. It is declared once in `ui/src/api/types.ts` and mirrored from the `PageResponse`
 * record in common-lib, and it is imported rather than restated so that the two cannot drift apart.
 *
 * Assumptions: forward reads the rows after the trailing cursor and backward reads the rows before
 * the leading one, strictly in both cases. The strictness on the backward side is the reference's
 * own: `app/cbl/COCRDLIC.cbl` L1268 moves the leading key into the field the browse is positioned
 * from and then reads once at L1294 purely to discard the row sitting on it, and
 * `app/cbl/COTRN00C.cbl` primes its backward walk the same way at L340. The service performs both
 * reads; what this module contributes is which cursor to replay and in which direction.
 *
 * Refactoring Rationale: the pending-authorization summary is served by this same shared arrangement,
 * and its own mechanism is deliberately not carried across. That program keeps a stack of previous
 * page keys twenty deep -- `CDEMO-CPVS-PAUKEY-PREV-PG PIC X(08) OCCURS 20 TIMES` at
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L120 -- pushing the key of each page's FIRST
 * row as that page is built, at L436 to L441, and paging backward by popping an entry and replaying
 * FORWARD from it, at L362 to L385. It declares no leading-key field at all. Three things settle the
 * divergence. It is behaviour-preserving: the datum pushed onto that stack IS the page's first key,
 * which is exactly what the envelope's `firstKey` carries for the page on display, so reading
 * strictly before it in descending order returns the rows the replay would have returned. The stack
 * exists only because the program cannot read the other way: it reaches segments through
 * GET-AUTHORIZATIONS and REPOSITION-AUTHORIZATIONS and has no backward equivalent, so remembering a
 * start key and replaying forward was the only route available, and the relational store the
 * migration targets reads descending directly. And the stack imposes a real bound that the shared
 * arrangement does not: twenty entries cap backward travel at twenty pages, and a twenty-first would
 * address past the end of that table. The baseline behaves as described; the target implements the
 * shared keyed read; the divergence is documented here and in
 * `docs/architecture/cobol-to-service-traceability.md`.
 *
 * Alternatives Considered: keeping that key stack on this side, in this module, so the authorization
 * summary paged the way its program does. Rejected on four counts: it would restore client-held
 * browse position of the kind AAP section 0.7.1 sets out to remove; it would make one of the five
 * browses behave unlike the other four for no outcome a user could observe; it would either cap
 * backward travel arbitrarily or grow without bound; and the envelope carries no page identity, so
 * nothing on the service side could ever confirm that a remembered entry still names the page the
 * screen thinks it does.
 *
 * Assumptions: an honest consequence of reading backward rather than replaying a remembered page --
 * the previous page is derived from the set as it stands, so under concurrent insertion it may not be
 * row for row the page that was displayed before. This is not a regression against the reference,
 * which has the same property for the same reason: it replays from a remembered KEY rather than
 * restoring a remembered page, so it too re-reads whatever the file holds at the moment the step is
 * taken.
 * @param {UsePagedQueryOptions<T>} options - The row arity the screen has room for, the reader that
 *   fetches one page, and optionally the switch that holds the opening read back and the value whose
 *   change restarts the browse.
 * @returns {UsePagedQueryResult<T>} The page on display with its two boundary states, the ordinal,
 *   the outstanding-read and failure flags, any problem document, and the forward, backward and
 *   restart steps. A delivered page carrying more rows than the declared arity is refused rather than
 *   published, and reported through the failure flag with no document beside it; a browse held back by
 *   its switch reports the opening state with nothing outstanding.
 * @throws {RangeError} If the row arity is not a whole number of at least one, which is a wiring
 *   mistake in the calling screen rather than anything a user can cause.
 * @template T The row type of one page.
 */
export function usePagedQuery<T>(options: UsePagedQueryOptions<T>): UsePagedQueryResult<T> {
  const { pageSize, fetchPage, enabled = true, resetKey = '' } = options;

  // Assumptions: an arity of nought or a fraction cannot describe any screen, and a browse
  //   configured with one would render a table with no row positions while every type check passed.
  //   It is refused here, before any state exists, because the value is a constant each screen
  //   declares from its own mapset -- so this cannot fire for one render and not the next.
  if (!Number.isInteger(pageSize) || pageSize < 1) {
    throw new RangeError(`Row arity must be a whole number of at least one, not ${pageSize}.`);
  }

  const [state, dispatch] = useReducer(pagedQueryReducer<T>, enabled, openingState<T>);

  // Assumptions: the sequence allocator is a ref rather than state because it has to advance at the
  //   moment a read is issued and be readable by that read's own settlement handlers. State advanced
  //   through a render would hand two reads issued in one turn the same number, which is precisely
  //   the case the guard exists for.
  const sequenceRef = useRef(0);

  // Assumptions: initialised raised, so a settlement that arrives before any effect has run is still
  //   applied. The teardown lowers it, and a remount raises it again, which is what keeps this
  //   correct under a development double-mount.
  const mountedRef = useRef(true);

  // Alternatives Considered: naming the reader in the dependencies of every callback below instead of
  //   holding the latest one here. Rejected because a screen composes it inline -- an arrow closing
  //   over the account being filtered on, for instance -- so it is a new function on every render,
  //   and the paging steps would then be new functions on every render too. Any effect a screen keyed
  //   on one of them would re-run continuously, and the opening read would re-issue on every render.
  const fetchPageRef = useRef(fetchPage);

  // Assumptions: the switch is held here as well as read directly, because the paging steps have to
  //   consult the CURRENT value from inside a callback that names no dependency. Naming it in those
  //   dependencies instead would rebuild every step, and therefore the restart step the opening effect
  //   is keyed on, whenever a screen raised or lowered it -- which is exactly the moment the browse
  //   must not re-issue an opening read for a second reason.
  const enabledRef = useRef(enabled);

  // Assumptions: the arity is held the same way and for the same reason: the delivered page is checked
  //   against it inside that same dependency-free callback. It is validated as a whole number at the
  //   top of this hook, so what the ref holds is always a usable count.
  const pageSizeRef = useRef(pageSize);

  // Assumptions: this browse's identity in the shared single-flight guard, claimed lazily on the first
  //   render and never reclaimed. A ref rather than state because nothing renders from it and changing
  //   it would be a defect; lazily rather than as an initialiser argument because `useRef` evaluates
  //   its argument on every render, so claiming there would burn an identity per render.
  // Assumptions: a remount claims a NEW identity, which is correct rather than wasteful -- a remounted
  //   browse must not join a read the previous mount left in flight, since that read settles into a
  //   reducer the previous mount owned.
  const identityRef = useRef<number | null>(null);
  identityRef.current ??= claimBrowseIdentity();

  // Assumptions: which generation of the browsed set the next turn belongs to, advanced whenever the
  //   set the browse is walking stops being the set it was walking -- an idling, and a restart under
  //   changed criteria. It exists so those two events cannot be joined ACROSS: without it, a restart
  //   whose key matched a read still in flight from before the restart would be handed that read's
  //   promise, no read of the new criteria would be issued, and the joined read's settlement would then
  //   be dropped by the reducer's own sequence guard as stale -- leaving the browse empty and idle with
  //   nothing outstanding and no failure to report.
  const epochRef = useRef(0);

  useEffect(
    /**
     * Tracks whether this browse is still mounted, so no settlement is applied after it is gone.
     * @returns {() => void} Teardown that lowers the flag.
     */
    () => {
      // Assumptions: raised again here even though the ref is created raised, because a remount
      //   reuses neither the ref's initial value nor the previous teardown's -- under a development
      //   double-mount the first teardown lowers the flag and the second mount has to restore it, or
      //   every later read would settle into nothing. This raise and the teardown below are one
      //   decision about the flag's lifetime, so they are justified once and together.
      mountedRef.current = true;
      return (
        /**
         * Lowers the mounted flag so an outstanding read settles into nothing.
         * @returns {void} Nothing; the flag is lowered in place.
         */
        () => {
          mountedRef.current = false;
        }
      );
    },
    [],
  );

  useEffect(
    /**
     * Keeps the three held values current, so the next step uses what this render was given.
     *
     * Assumptions: no dependency list, deliberately. The reader is whatever the most recent render
     * supplied, so this has to run after every render rather than after some of them; a list would
     * decide when to refresh it, and every possible list is wrong for a reader composed inline. The
     * switch and the arity ride along in the same effect because they have the same lifetime and the
     * same reason to be held -- a callback with no dependencies reads them -- so justifying them apart
     * would say the same thing three times.
     *
     * Assumptions: this effect being declared BEFORE the opening effect is what makes the ordering
     * correct rather than incidental. React runs a component's effects in declaration order, so on the
     * render that raises the switch, the ref carries the raised value before the opening effect calls
     * the restart step -- whose gate consults exactly that ref. Moving this below the opening effect
     * would make the first read after enabling take the previous, lowered value and do nothing.
     * @returns {void} Nothing; the three held values are replaced in place.
     */
    () => {
      fetchPageRef.current = fetchPage;
      enabledRef.current = enabled;
      pageSizeRef.current = pageSize;
    },
  );

  const runRequest = useCallback(
    /**
     * Issues one read and settles it, unless the browse is held back, a later read has started, or
     * this browse has gone.
     *
     * Assumptions: the switch is consulted HERE rather than at each of the three call sites, because
     * this is the single point every read passes through. An earlier revision gated only the opening
     * effect, which left the restart, forward and backward steps able to read while the browse was held
     * back -- so a screen that lowered the switch on a cleared entry field still answered its paging
     * keys, and the read that resulted settled into a browse the screen was no longer showing rows for.
     * Gating one function closes all three at once and cannot be forgotten at a fourth call site.
     *
     * Assumptions: the refusal happens BEFORE a sequence number is allocated and before the started
     * transition is dispatched, so a held-back browse does not briefly report a read outstanding for a
     * read that was never issued.
     *
     * Assumptions: which opening transition to dispatch is a PARAMETER rather than a decision made
     * here, because this function cannot tell the two cases apart -- a change of query and a caller
     * asking to return to the start of the set both issue an opening read with no cursor. The caller
     * knows which it is, so the caller says, and the two entry points below are the two answers.
     *
     * Assumptions: ⚠️ the returned promise resolves once the read has SETTLED into the reducer, and it
     * never rejects. Both outcomes are handled inside the settlement below, so what a caller awaits is
     * "this turn is over", not "this turn succeeded" -- which is what a busy affordance needs and all it
     * needs. A caller that discards the promise is in exactly the position every caller was in when this
     * returned nothing: the outcome still arrives through the reducer, and nothing is left unhandled.
     * @param {string | null} cursor - Position to read from, or `null` for an opening read.
     * @param {PageDirection} direction - Direction to replay the cursor in.
     * @param {boolean} clearing - Whether this read belongs to a DIFFERENT query, in which case the
     *   rows, both cursors and the ordinal of the previous one are dropped before it is issued.
     * @returns {Promise<void>} Resolves once this turn has settled into the browse, or immediately when
     *   the browse is held back. Both outcomes are applied through the two handlers.
     */
    (cursor: string | null, direction: PageDirection, clearing = false): Promise<void> => {
      if (!enabledRef.current) {
        return Promise.resolve();
      }
      // Assumptions: a clearing read is a read of a DIFFERENT set, so it opens a new generation before
      //   its key is composed. That is what stops it joining -- and being answered by -- a read of the
      //   set it is replacing, which is still in flight at exactly the moment a screen's criteria change.
      if (clearing) {
        epochRef.current += 1;
      }
      const turn = coalescingKeyOf(identityRef.current ?? 0, epochRef.current, direction, cursor);

      // Alternatives Considered: two other ways to stop an earlier read overwriting a later one. An
      //   AbortController cancelling the outstanding request was rejected because the reader is
      //   injected, so this module cannot require that whatever implements it honours a signal, and a
      //   guard that only works for some readers is worse than one that works for all. Refusing input
      //   while a read is outstanding was rejected because it makes one slow page freeze both paging
      //   controls, and an operator holding the forward key would be answered by nothing at all. A
      //   sequence number needs no cooperation from the reader and leaves both controls live.
      // Assumptions: the sequence guard and the single-flight guard below answer two DIFFERENT
      //   questions and neither replaces the other. The sequence guard decides which of two settlements
      //   is allowed to land, so two genuinely different turns taken together end on the later one; the
      //   single-flight guard decides whether a second REQUEST is issued at all when the turn is the
      //   same one. Without the second, a review measured three presses of one forward key producing
      //   three identical requests and a browse whose ordinal read one page beyond its rows.
      // ⚠️ WHY : Refactoring Rationale: the sequence allocation and the started transition moved INSIDE
      //   the attempt, where they used to stand above it. They have to: a joined caller must not
      //   allocate a number, because adopting a number the in-flight read does not hold is precisely
      //   what makes that read's settlement stale -- the reducer would drop the only answer coming, and
      //   the browse would report a read outstanding for ever.
      return withoutConcurrentDuplicate(
        turn,
        /**
         * Issues this turn's read and settles it, having established that no identical turn is running.
         * @returns {Promise<void>} Resolves once the settlement has been dispatched.
         */
        () => {
          const sequence = sequenceRef.current + 1;
          sequenceRef.current = sequence;
          dispatch({ kind: clearing ? 'query-restarted' : 'browse-started', sequence });

          // Assumptions: `then` with BOTH handlers rather than a trailing `catch`, so the rejection is
          //   handled in the same expression that handles success and neither can be added without the
          //   other being visible beside it. It is also what keeps the promise this returns from ever
          //   rejecting, which is the property the shared guard needs: a rejection would be handed to
          //   every joined caller as well, and a joined caller that discarded it would report an
          //   unhandled rejection for a failure this module has already recorded.
          return fetchPageRef.current({ cursor, direction }).then(
            /**
             * Publishes a delivered page, if it is still the page that was asked for.
             * @param {PageResponse<T>} page - The page the reader returned.
             * @returns {void} Nothing; the page is published through the reducer.
             */
            (page) => {
              // Assumptions: the mounted flag is consulted here, inside the settlement, rather than
              //   before the read is issued. A read that was legitimate when it started can still be
              //   answered after the screen has gone, and it is the delivery that would touch a state
              //   container nobody is reading any more. The sequence this closure captured is the one
              //   allocated for this read, so a later read's settlement cannot be mistaken for this one.
              if (!mountedRef.current) {
                return;
              }

              // Assumptions: the delivered row count is checked against the arity the screen declared, and
              //   an over-long page is refused rather than published. The arity is the number of row
              //   positions the screen's mapset paints -- seven, ten, ten, seven and five across the five
              //   browses -- so a page carrying more rows than that has rows the screen cannot show, and
              //   publishing it would silently drop the surplus. What makes that a correctness matter
              //   rather than a cosmetic one is which rows go missing: the service establishes the further
              //   page from a probe read one row beyond the page, so a surplus row reaching the array means
              //   the probe row was published, and the row a screen dropped is the row the next forward
              //   step would have read from. The operator would then step past a row that was never shown.
              // Alternatives Considered: truncating the array to the arity and publishing the rest.
              //   Rejected because it makes this module a participant in paging arithmetic it does not own
              //   -- the cursors delivered alongside would name rows outside the page as published, so the
              //   next step would read from a position inconsistent with what is on screen. Refusing keeps
              //   the browse on the page the operator can see and reports that something is wrong.
              // Assumptions: the refusal carries NO problem document. No service sent one, and
              //   `UsePagedQueryResult.error` may not hold a document this module invented -- a fabricated
              //   code or correlation identifier would be indistinguishable from one a service produced.
              //   The sentence a screen shows comes from `ui/src/messages/messages.ts` under rule T8, which
              //   is why none is composed here either.
              // Assumptions: ⚠️ Refactoring Rationale: the delivered value is checked for BEING a page before
              //   its rows are counted, and it was not. `page.items.length` on an answer that carries no
              //   `items` throws a `TypeError` inside a `then` handler, which becomes an UNHANDLED REJECTION
              //   for every caller that does not await the turn -- and a caller is free not to, because the
              //   reducer is how an outcome arrives and the returned promise is an affordance rather than an
              //   obligation -- so a screen reached with a stubbed or proxied transport reported a browse
              //   still loading while the runner reported an unhandled error against whichever test happened
              //   to be running. A malformed answer is a failed read, which is a state this hook already has
              //   and already reports; it is not a reason to throw out of a settlement.
              // Assumptions: this guard is also what makes the promise this function returns unable to
              //   reject, which the public contract on the three steps states. Both handlers dispatch and
              //   return; neither can throw past this check, so a joined caller handed the same promise
              //   cannot be given a rejection either.
              // Assumptions: the refusal carries NO problem document, for the reason recorded below: no
              //   service sent one, and a fabricated code would be indistinguishable from one that did.
              // Assumptions: it carries no classified failure either, and for the stronger reason. No
              //   request failed at all here -- the read was ANSWERED, and this module is refusing the
              //   answer -- so there is no transport judgement to report, and labelling it transient or
              //   repeatable would tell a screen that pressing the key again might help when the service
              //   would answer identically.
              if (!Array.isArray(page?.items)) {
                dispatch({ kind: 'browse-failed', sequence, error: null, failure: null });
                return;
              }
              if (page.items.length > pageSizeRef.current) {
                dispatch({ kind: 'browse-failed', sequence, error: null, failure: null });
                return;
              }
              dispatch({ kind: 'page-settled', sequence, cursor, direction, page });
            },
            /**
             * Records a refusal or failure, keeping the page on display.
             * @param {unknown} reason - Whatever the reader rejected with.
             * @returns {void} Nothing; the outcome is recorded through the reducer.
             */
            (reason: unknown) => {
              // Assumptions: the same mounted flag and the same captured sequence guard the refusal path,
              //   because a refusal is as capable of arriving late as a delivery is. The refusal is
              //   narrowed to a problem document before it reaches the reducer, so the reducer never has
              //   to know what shape a rejected reader threw.
              // Assumptions: the rejection is narrowed TWICE, by two functions, at this one point. One
              //   yields the document a screen renders its sentence and its field marks from; the other
              //   yields the transport's classification of the same failure. Narrowing once and deriving
              //   the second from the first is not available -- a document synthesised for a timeout and
              //   a document a service sent for a refusal are the same shape -- and doing it here rather
              //   than in the reducer keeps the reducer ignorant of rejection shapes, exactly as before.
              if (mountedRef.current) {
                dispatch({
                  kind: 'browse-failed',
                  sequence,
                  error: problemDocumentOf(reason),
                  failure: requestFailureOf(reason),
                });
              }
            },
          );
        },
      );
    },
    [],
  );

  const reset = useCallback(
    /**
     * Reads the opening page again, returning the browse to the start of the SAME set.
     *
     * Assumptions: the rows on display are RETAINED while this read is outstanding, because this is a
     * refresh of the query already being browsed -- a caller reaches for it after a mutation, and the
     * page it is refreshing is still an answer to the same question. A change of QUERY is the other
     * case and does not come through here; the effect below dispatches the clearing transition for it.
     * @returns {Promise<void>} Resolves once the refreshed opening page has settled, or immediately
     *   when the browse is held back. The page itself arrives through this hook's result.
     */
    (): Promise<void> => {
      // Alternatives Considered: giving an opening read a direction of its own, or a distinct action
      //   the reducer would recognise. Rejected because an opening read IS a forward read that starts
      //   from nowhere, and the absent cursor already says so; a third direction would have to be
      //   accepted by every reader and refused by the edge, which seals a direction into each token.
      // Assumptions: the engine's promise is RETURNED rather than discarded, which is what makes a
      //   refresh awaitable. A screen that refreshes after a mutation can then write its sentence over
      //   the refreshed page instead of over the page the mutation replaced.
      return runRequest(null, 'next');
    },
    [runRequest],
  );

  const restartQuery = useCallback(
    /**
     * Drops the previous query's page and reads the opening page of the new one.
     *
     * Assumptions: this is the ONLY entry point that clears, and it is reached only from the effect
     * that watches the restart value. Exposing it to callers would let a screen clear the rows of the
     * query it is still browsing, which is the failure the clearing transition exists to prevent
     * rather than to enable.
     * @returns {Promise<void>} Resolves once the new query's opening page has settled. The page itself
     *   arrives through this hook's result.
     */
    (): Promise<void> => {
      return runRequest(null, 'next', true);
    },
    [runRequest],
  );

  useEffect(
    /**
     * Opens the browse, reopens it whenever the screen's criteria change, and idles it when the screen
     * holds it back.
     *
     * Assumptions: the restart value is named in the dependency list and read nowhere in the body,
     * which is the whole of its purpose -- it exists so a screen can say that its criteria have
     * changed without this module knowing what any of them are. It is compared for equality by React,
     * so a screen that composes it from its criteria gets one restart per genuine change.
     *
     * Refactoring Rationale: the lowered case now has a body, where an earlier revision simply did
     * nothing. Doing nothing left a browse that had been reading before the switch went down still
     * reporting a read outstanding, with the previous criteria's rows and cursors on display, and left
     * that read able to settle into it afterwards -- so a screen whose entry field was cleared went on
     * showing rows for the cleared criterion, and a page in flight would arrive and repopulate it.
     * Idling here is the state half of the same decision `runRequest` implements for new reads: the
     * gate stops reads starting while the switch is down, and this stops the ones already outstanding
     * from landing.
     *
     * Assumptions: the raised case uses the CLEARING entry point, and the public {@link reset} does
     * not. Every run of this effect is a read of a query this hook was not previously reading -- the
     * first one because there was no previous query, and each later one because the restart value
     * compared unequal -- so there are never rows here that answer the query about to be read. Clearing
     * on the first run is therefore a no-op over already-empty state, which is why one entry point
     * serves both runs rather than the effect having to tell them apart.
     * @returns {void} Nothing; the page arrives through this hook's result, or the browse settles idle.
     */
    () => {
      if (enabled) {
        // Assumptions: the opening read's promise is settled with BOTH handlers here rather than
        //   discarded. An effect may return only a teardown, so the promise cannot be returned; and
        //   `ui/eslint.config.js` sets `no-floating-promises` with `ignoreVoid: false`, so a `void`
        //   discard is not available either. Supplying the pair states the same thing the engine's own
        //   dispatch site states: this outcome is observed through the reducer and nowhere else.
        // Assumptions: the rejection handler is unreachable by construction -- the engine handles both
        //   outcomes internally and resolves either way -- and it is written anyway, because a handler
        //   that exists cannot become the unhandled rejection a later edit to the engine would
        //   otherwise introduce here silently.
        restartQuery().then(ignoreSettledTurn, ignoreSettledTurn);
        return;
      }
      // Assumptions: the sequence is advanced here, in the effect, rather than inside the reducer.
      //   The allocator is a ref precisely so that a number can be claimed at the moment an event
      //   happens rather than a render later, and claiming one for the idling is what makes every read
      //   started before it stale. A reducer cannot claim one, because it may be invoked more than once
      //   for a single dispatch and would then allocate twice.
      sequenceRef.current += 1;
      // Assumptions: the generation is advanced alongside the sequence, and for the mirror-image
      //   reason. The sequence stops an outstanding read's answer LANDING in an idled browse; the
      //   generation stops the next turn JOINING that outstanding read, which would leave the browse
      //   waiting on an answer the reducer has already been told to drop.
      epochRef.current += 1;
      dispatch({ kind: 'browse-idled', sequence: sequenceRef.current });
    },
    [enabled, resetKey, restartQuery],
  );

  const nextPage = useCallback(
    /**
     * Reads the page after the one on display, which is the forward paging key's action.
     *
     * Assumptions: refused when the envelope reported no further page, and refused when there is no
     * trailing cursor to read from, and a refusal here does nothing at all -- no read is issued and
     * no member of the browse moves. A browse held back by its switch is refused too, one level down in
     * `runRequest`, which is where that gate belongs because all three steps pass through it. Telling
     * the operator why is the screen's part, and it chooses its sentence from `hasNext`.
     *
     * Assumptions: a refused step resolves an already-settled promise rather than returning nothing, so
     * a caller awaiting the turn is released immediately and a caller ignoring it sees exactly what it
     * saw before. `Promise.resolve()` and not a rejection, because a step at a boundary is a documented
     * no-op and not an error -- the reference answers the same key by re-sending the screen with a
     * sentence attached.
     * @returns {Promise<void>} Resolves once the page has settled, or immediately when the step is
     *   refused. The page itself arrives through this hook's result.
     */
    (): Promise<void> => {
      if (!state.hasNext || state.lastKey === null) {
        return Promise.resolve();
      }
      return runRequest(state.lastKey, 'next');
    },
    [runRequest, state.hasNext, state.lastKey],
  );

  const prevPage = useCallback(
    /**
     * Reads the page before the one on display, which is the backward paging key's action.
     *
     * Assumptions: refused on the opening page, decided from the ordinal exactly as the reference
     * decides it at `app/cbl/COCRDLIC.cbl` L901 to L903, and refused when there is no leading cursor
     * to read from. A refusal here does nothing at all, and resolves immediately on the same terms as
     * `nextPage`'s refusal.
     * @returns {Promise<void>} Resolves once the page has settled, or immediately when the step is
     *   refused. The page itself arrives through this hook's result.
     */
    (): Promise<void> => {
      if (state.pageNumber <= FIRST_PAGE_NUMBER || state.firstKey === null) {
        return Promise.resolve();
      }
      return runRequest(state.firstKey, 'previous');
    },
    [runRequest, state.firstKey, state.pageNumber],
  );

  // Assumptions: composed from the ordinal and the presence of a leading cursor, which are the two
  //   things a backward step needs -- a page to go back to, and a position to go back from. No member
  //   of the envelope answers the first, by design, and the reference answers it from its own ordinal
  //   too. It is bound to a local here rather than written inline in the result so that the boundary
  //   below is derived from the SAME value a screen reads, and the two cannot come to disagree.
  const hasPrev = state.pageNumber > FIRST_PAGE_NUMBER && state.firstKey !== null;

  return {
    items: state.items,
    hasNext: state.hasNext,
    hasPrev,
    pageNumber: state.pageNumber,
    boundary: pageBoundaryOf(state.items.length, state.hasNext, hasPrev),
    pageSize,
    isLoading: state.isLoading,
    isFailed: state.isFailed,
    error: state.error,
    failure: state.failure,
    nextPage,
    prevPage,
    reset,
  };
}
