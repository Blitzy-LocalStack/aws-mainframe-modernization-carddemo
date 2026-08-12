/**
 * @file Keyset browse state for every paged screen, replacing the CICS browse and the position the
 * reference carried between screen turns.
 *
 * What this module holds
 * ----------------------
 * One delivered page, the two sealed cursors addressing the pages either side of it, the ordinal of
 * the page on display, whether a request is outstanding, how the last one ended, and the two
 * imperative steps a screen binds to its backward and forward keys. Nothing else: no row is
 * formatted, no sentence is chosen, no keyboard event is bound and no request target is addressed.
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
 * Assumptions: how a consumer wires this up. A screen renders an antd `Table` with `pagination`
 * turned off, because the component's built-in row-counting pager is exactly the mechanism rejected
 * above; it binds its backward control's `disabled` to `hasPrev` and its forward control's to
 * `hasNext`; and it reaches this module's two steps from its own key bindings.
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
   * none of this module's business, and the reference shows exactly how easy it would be to get wrong
   * -- its card browse position is a composite twenty-seven characters wide, a sixteen-character card
   * number followed by an eleven-digit account identifier in physical key order at
   * `app/cbl/COCRDLIC.cbl` L230 to L232, while the row that same screen DISPLAYS is a twenty-eight
   * character group in the opposite order at L258 to L260: account number, then card number, then a
   * card status in its twenty-eighth character, which belongs to no key at all. Reading one as the
   * other would page in the wrong sequence. The remaining browses seal scalars of three further
   * widths -- sixteen characters at `app/cbl/COTRN00C.cbl` L63 to L64, eight at
   * `app/cbl/COUSR00C.cbl` L68 to L69 and two at
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L399 and L401 -- so one text member serves all
   * four shapes and no key type parameter is introduced for any of them. `ui/src/api/types.ts`
   * records that the service seals the DIRECTION into the token as well, so a token replayed the
   * other way is refused with HTTP 400 rather than answered with the wrong page.
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
   * establishes it too. Its uses here are local: it is validated as a row count, and it is published
   * back on the result so the screen renders that many row positions from one declaration.
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
  /** How many rows the screen has room for, as supplied, so it renders that many row positions. */
  readonly pageSize: number;
  /** Whether a read is outstanding. */
  readonly isLoading: boolean;
  /**
   * Whether the most recent read ended in refusal or failure.
   *
   * Assumptions: this is separate from `error` because a failure does not always arrive as a problem
   * document. A gateway answering with its own HTML page, or a request that never reached one, gives
   * a screen nothing to read a code or a correlation identifier out of -- and a browse that quietly
   * kept displaying its previous rows in that case would be the silent broken view this flag exists
   * to prevent. So this answers whether the read failed and `error` answers what the service said
   * about it.
   */
  readonly isFailed: boolean;
  /**
   * Problem document from the most recent failure, or `null`.
   *
   * Assumptions: `null` means either that nothing failed or that what failed carried no problem
   * document, which is why `isFailed` above is the flag to branch on. No stand-in is invented for the
   * second case: a fabricated code, severity or correlation identifier would be indistinguishable
   * from one a service actually sent, and a screen quoting a made-up correlation identifier to
   * support is worse off than one quoting none.
   */
  readonly error: ApiError | null;
  /**
   * Reads the page after the one on display, which is the forward paging key's action.
   *
   * Assumptions: a call made while `hasNext` is false, or with no trailing cursor to read from, is a
   * documented no-op -- no request is issued, the rows, cursors and ordinal are left untouched, and
   * nothing is thrown. That mirrors the reference, which gates its forward arm on the same indicator
   * at `app/cbl/COCRDLIC.cbl` L486 to L487 and at
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L766 to L767 and answers the refused step by
   * re-sending the screen with a sentence attached.
   * @returns {void} Nothing; the outcome is observed through this result on a later render.
   */
  readonly nextPage: () => void;
  /**
   * Reads the page before the one on display, which is the backward paging key's action.
   *
   * Assumptions: a call made on the opening page, or with no leading cursor to read from, is a
   * documented no-op on the same terms as `nextPage`. The reference gates its backward arm the same
   * way, on its ordinal rather than on anything read from the file --
   * `app/cbl/COCRDLIC.cbl` L501 to L502 and
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L780 to L781.
   * @returns {void} Nothing; the outcome is observed through this result on a later render.
   */
  readonly prevPage: () => void;
  /**
   * Returns the browse to its opening page and reads it again.
   *
   * Assumptions: the rows on display are kept until the opening page arrives, rather than being
   * cleared first. Clearing them would blank the table for the duration of a request that may well
   * return the same rows, and the reference has no equivalent moment: it composes the whole screen
   * once the read has finished.
   * @returns {void} Nothing; the outcome is observed through this result on a later render.
   */
  readonly reset: () => void;
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
  /** Sealed cursor naming this page's leading boundary, or `null` when it carried no rows. */
  readonly firstKey: string | null;
  /** Sealed cursor naming this page's trailing boundary, or `null` when it carried no rows. */
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
 * The three transitions a browse can make.
 *
 * Assumptions: every transition carries the sequence number of the read it belongs to, including the
 * one that starts a read, because that is what lets the reducer tell a settlement of the current read
 * from a settlement of an abandoned one without consulting anything outside its two arguments.
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
  | {
      readonly kind: 'page-settled';
      readonly sequence: number;
      readonly cursor: string | null;
      readonly direction: PageDirection;
      readonly page: PageResponse<T>;
    }
  | { readonly kind: 'browse-failed'; readonly sequence: number; readonly error: ApiError | null };

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
 * Narrows a value to a problem document when it carries the members a screen acts on.
 *
 * Assumptions: three members are probed rather than all eleven -- the status, the correlation
 * identifier an operator quotes to support, and the field-error array a form binds to -- which is the
 * same probe `isApiError` in `ui/src/api/client.ts` makes. The question being decided is whether the
 * body is USABLE, not whether it is exhaustive, so a body from a service that published a further
 * member still passes.
 *
 * Alternatives Considered: importing that function instead of restating its probe. Rejected because
 * `ui/src/api/client.ts` is a transport module -- it builds an axios instance and installs
 * interceptors on it -- so importing it would pull a transport into a module that issues no request,
 * and every assertion about the paging invariants would then load one.
 * @param {unknown} value - A value of unknown shape, typically a response body.
 * @returns {ApiError | null} The value as a problem document, or `null` when it is not one.
 */
function asProblemDocument(value: unknown): ApiError | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const candidate = value as Partial<ApiError>;
  const usable =
    typeof candidate.status === 'number' &&
    typeof candidate.correlationId === 'string' &&
    Array.isArray(candidate.fieldErrors);
  return usable ? (value as ApiError) : null;
}

/**
 * Finds the problem document a rejected read carried, if it carried one.
 *
 * Assumptions: two shapes are examined, because a rejection reaches here by two routes. A caller may
 * reject with the document itself, and the already-authored clients reject with the transport's own
 * failure, which nests the response body one level down -- `ui/src/api/client.ts` re-throws whatever
 * axios rejected with, unchanged, so the body arrives as that value's `response.data`. Examining only
 * the outer value would report every service refusal as carrying no document at all, which would
 * throw away the code and correlation identifier the service did in fact send.
 *
 * Assumptions: anything else yields `null` rather than a stand-in. A failure with no document is a
 * real outcome -- an unreachable edge, or a gateway answering with its own page -- and
 * {@link UsePagedQueryResult.isFailed} is what reports it, so nothing has to be invented here.
 * @param {unknown} reason - Whatever the read rejected with; not necessarily an `Error`.
 * @returns {ApiError | null} The problem document, or `null` when the rejection carried none.
 */
function problemDocumentOf(reason: unknown): ApiError | null {
  const direct = asProblemDocument(reason);
  if (direct !== null) {
    return direct;
  }
  if (typeof reason !== 'object' || reason === null || !('response' in reason)) {
    return null;
  }
  const response: unknown = reason.response;
  if (typeof response !== 'object' || response === null || !('data' in response)) {
    return null;
  }
  return asProblemDocument(response.data);
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
      //   read fails, remains the page the ordinal describes.
      return {
        ...state,
        isLoading: true,
        isFailed: false,
        error: null,
        startedSequence: action.sequence,
      };
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
        startedSequence: state.startedSequence,
      };
    case 'browse-failed':
      if (action.sequence !== state.startedSequence) {
        return state;
      }
      // Assumptions: a failed read leaves the rows, both cursors and the ordinal exactly as they
      //   were, so the operator keeps the page in front of them and can take the same step again.
      //   The reference does the same, re-sending the screen it already composed.
      return { ...state, isLoading: false, isFailed: true, error: action.error };
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
 *   restart steps.
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
     * Keeps the held reader current, so the next step uses the one this render was given.
     *
     * Assumptions: no dependency list, deliberately. The reader is whatever the most recent render
     * supplied, so this has to run after every render rather than after some of them; a list would
     * decide when to refresh it, and every possible list is wrong for a reader composed inline.
     * @returns {void} Nothing; the held reader is replaced in place.
     */
    () => {
      fetchPageRef.current = fetchPage;
    },
  );

  const runRequest = useCallback(
    /**
     * Issues one read and settles it, unless a later read has started or this browse has gone.
     * @param {string | null} cursor - Position to read from, or `null` for an opening read.
     * @param {PageDirection} direction - Direction to replay the cursor in.
     * @returns {void} Nothing; both outcomes are applied through the two handlers.
     */
    (cursor: string | null, direction: PageDirection): void => {
      const sequence = sequenceRef.current + 1;
      sequenceRef.current = sequence;
      dispatch({ kind: 'browse-started', sequence });

      // Alternatives Considered: two other ways to stop an earlier read overwriting a later one. An
      //   AbortController cancelling the outstanding request was rejected because the reader is
      //   injected, so this module cannot require that whatever implements it honours a signal, and a
      //   guard that only works for some readers is worse than one that works for all. Refusing input
      //   while a read is outstanding was rejected because it makes one slow page freeze both paging
      //   controls, and an operator holding the forward key would be answered by nothing at all. A
      //   sequence number needs no cooperation from the reader and leaves both controls live.
      // Assumptions: `then` with BOTH handlers rather than a trailing `catch`, so the rejection is
      //   handled in the same expression that handles success and neither can be added without the
      //   other being visible beside it.
      fetchPageRef.current({ cursor, direction }).then(
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
          if (mountedRef.current) {
            dispatch({ kind: 'page-settled', sequence, cursor, direction, page });
          }
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
          if (mountedRef.current) {
            dispatch({ kind: 'browse-failed', sequence, error: problemDocumentOf(reason) });
          }
        },
      );
    },
    [],
  );

  const reset = useCallback(
    /**
     * Reads the opening page again, returning the browse to the start of the set.
     * @returns {void} Nothing; the new page arrives through this hook's result.
     */
    (): void => {
      // Alternatives Considered: giving an opening read a direction of its own, or a distinct action
      //   the reducer would recognise. Rejected because an opening read IS a forward read that starts
      //   from nowhere, and the absent cursor already says so; a third direction would have to be
      //   accepted by every reader and refused by the edge, which seals a direction into each token.
      runRequest(null, 'next');
    },
    [runRequest],
  );

  useEffect(
    /**
     * Opens the browse, and reopens it whenever the screen's criteria change.
     *
     * Assumptions: the restart value is named in the dependency list and read nowhere in the body,
     * which is the whole of its purpose -- it exists so a screen can say that its criteria have
     * changed without this module knowing what any of them are. It is compared for equality by React,
     * so a screen that composes it from its criteria gets one restart per genuine change.
     * @returns {void} Nothing; the page arrives through this hook's result.
     */
    () => {
      if (enabled) {
        reset();
      }
    },
    [enabled, resetKey, reset],
  );

  const nextPage = useCallback(
    /**
     * Reads the page after the one on display, which is the forward paging key's action.
     *
     * Assumptions: refused when the envelope reported no further page, and refused when there is no
     * trailing cursor to read from, and a refusal here does nothing at all -- no read is issued and
     * no member of the browse moves. Telling the operator why is the screen's part, and it chooses
     * its sentence from `hasNext`.
     * @returns {void} Nothing; the page arrives through this hook's result.
     */
    (): void => {
      if (!state.hasNext || state.lastKey === null) {
        return;
      }
      runRequest(state.lastKey, 'next');
    },
    [runRequest, state.hasNext, state.lastKey],
  );

  const prevPage = useCallback(
    /**
     * Reads the page before the one on display, which is the backward paging key's action.
     *
     * Assumptions: refused on the opening page, decided from the ordinal exactly as the reference
     * decides it at `app/cbl/COCRDLIC.cbl` L901 to L903, and refused when there is no leading cursor
     * to read from. A refusal here does nothing at all.
     * @returns {void} Nothing; the page arrives through this hook's result.
     */
    (): void => {
      if (state.pageNumber <= FIRST_PAGE_NUMBER || state.firstKey === null) {
        return;
      }
      runRequest(state.firstKey, 'previous');
    },
    [runRequest, state.firstKey, state.pageNumber],
  );

  return {
    items: state.items,
    hasNext: state.hasNext,
    // Assumptions: composed from the ordinal and the presence of a leading cursor, which are the two
    //   things a backward step needs -- a page to go back to, and a position to go back from. No
    //   member of the envelope answers the first, by design, and the reference answers it from its
    //   own ordinal too.
    hasPrev: state.pageNumber > FIRST_PAGE_NUMBER && state.firstKey !== null,
    pageNumber: state.pageNumber,
    pageSize,
    isLoading: state.isLoading,
    isFailed: state.isFailed,
    error: state.error,
    nextPage,
    prevPage,
    reset,
  };
}
