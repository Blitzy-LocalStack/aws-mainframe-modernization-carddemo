/**
 * @file Typed client for the card bounded context, written against
 * `services/card-service/src/main/resources/openapi/card-api.yaml`.
 *
 * Purpose
 * -------
 * Cover the five operations that contract publishes -- the keyset-paged browse and the lookup
 * replacing `app/cbl/COCRDLIC.cbl`, the detail read replacing `app/cbl/COCRDSLC.cbl`, and the
 * fetch-then-replace pair replacing `app/cbl/COCRDUPC.cbl` -- and nothing else. Every request
 * target is derived from the operation manifest below rather than written as a literal, for the
 * reason recorded in `ui/src/api/types.ts`.
 *
 * Ownership of the wire shapes
 * ----------------------------
 * Assumptions: this module declares NO wire shape. Every card shape, and the shared page envelope and
 * reading direction alongside them, is declared once in `./types` and re-exported here, so the three
 * card screens that import these names from this module still resolve while each shape has exactly one
 * definition. `ui/src/api/contracts.test.ts` asserts both halves: that no client module declares a wire
 * shape, and that every client module still re-exports its contract's shapes. The detail is argued at
 * the re-exports themselves.
 *
 * Selector discipline
 * -------------------
 * Assumptions: a card is addressed on the wire by an opaque SELECTOR, never by its number, and the
 * guards enforcing that are imported from `../routes/cards` rather than restated here. A primary
 * account number written into a path would be recorded in the edge access log and in the browser's
 * history before any application code ran, and neither store is reachable by anything this module
 * could add. The one operation that accepts a number, the lookup, sends it in the request BODY the
 * contract declares for that purpose and answers with the selector the three selector-addressed
 * operations reach that card by. The same discipline settles the browser routes those selectors are
 * interpolated into: `ui/src/routes/cards.ts` spells them `/cards/:cardKey` and `/cards/:cardKey/edit`,
 * so the path builders that consume what these operations return carry a sealed selector and never a
 * card number.
 *
 * Identifiers on the wire
 * -----------------------
 * Assumptions: a card number and an account identifier are digit-validated STRINGS in every direction,
 * never numbers, and the reference itself carries them that way. `05 CARD-NUM PIC X(16).` at
 * `app/cpy/CVACT02Y.cpy` L5 is a character field, and the online copybook declares the same value
 * twice for the two uses -- `10 CC-CARD-NUM PIC X(16)` at `app/cpy/CVCRD01Y.cpy` L37 with
 * `10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16).` at L39 -- so it is characters on the wire and a
 * number only inside arithmetic. Carrying either as a JavaScript `number` would lose data rather than
 * report a failure: sixteen digits reach 10^16, above `Number.MAX_SAFE_INTEGER` at 2^53 - 1, which is
 * approximately 9.007 x 10^15, so the low-order digits are silently rounded away, and an eleven-digit
 * account identifier such as `00000000011` would additionally shed its leading zeros and stop matching
 * the exact width the contract's pattern requires.
 *
 * Field lineage
 * -------------
 * Refactoring Rationale: the expiry member is `expirationDate`, and the reference spells the same field
 * `05 CARD-EXPIRAION-DATE PIC X(10).` at `app/cpy/CVACT02Y.cpy` L9 -- a misspelling of "expiration"
 * that the baseline carries and that this migration does not propagate into a published name. The
 * lineage is written down here because the two spellings are one field: a reader comparing this module
 * with the copybook finds no `EXPIRAION` member and would otherwise have to guess whether a member was
 * renamed or dropped. Renaming rather than transcribing costs one indirection at exactly two places,
 * the mapper and the schema-mapping table in `docs/architecture`, and it buys a published surface a
 * reader can spell from the word itself rather than from the baseline's spelling of it.
 *
 * The card verification value
 * ---------------------------
 * Assumptions: the reference record carries one -- `05 CARD-CVV-CD PIC 9(03).` at
 * `app/cpy/CVACT02Y.cpy` L7, between the account identifier and the embossed name -- and NO operation
 * in `card-api.yaml` returns it and none accepts it, so this module offers no way to reference it: no
 * shape member, no parameter, no request member and no target. The omission is written down rather
 * than merely observed, because the layout declares seven items at L5 to L11 of which this module
 * carries five -- the number, the account identifier, the embossed name, the expiry and the status --
 * so a reader completing that mapping mechanically from the copybook would supply the one item whose
 * whole purpose is to be unreachable. `FILLER PIC X(59)` at L11 is the other item this module does not
 * carry, and it is left out for the ordinary reason that it pads the record to 150 bytes and holds no
 * value; this one is left out for a different reason entirely, and conflating the two is what this note
 * prevents.
 */

import {
  getApiClient,
  keysetPagingMembers,
  requestPath,
  requireConditionalOn,
  requireWithinPublishedWidths,
} from './client';
import { MASKED_CARD_NUMBER } from './masking';
import type {
  AdminCardDetail,
  CardDetail,
  CardListQuery,
  CardSummary,
  CardUpdateRequest,
  ContractOperation,
  PageResponse,
} from './types';
import {
  isCardNumber,
  isCardSelector,
  requireCardNumber,
  requireCardSelector,
} from '../routes/cards';

/*
 * WHY : Refactoring Rationale: the card wire shapes are RE-EXPORTED from ./types rather than declared
 *       here. The re-export keeps this module's public surface exactly as it was -- the three card
 *       screens import these names from '../../api/cards' and still may -- while the single definition
 *       of each shape, with the full rationale for each member, sits beside the other contracts' shapes
 *       in ./types, where the erasure guarantee also applies.
 */
export type {
  CardSummary,
  CardDetail,
  AdminCardDetail,
  CardUpdateRequest,
  CardListQuery,
} from './types';

/*
 * WHY : Refactoring Rationale: `PageResponse` and `PageDirection` are RE-EXPORTED from ./types rather
 *       than declared here, and both were declared here before. They are not card concepts: all five
 *       browser-facing contracts publish the same page envelope and the same direction pair, so a
 *       declaration in this module made the card client the accidental owner of a shape four other
 *       clients also need. The re-export keeps the public surface of this module unchanged -- the
 *       three card screens import both names from '../../api/cards' -- so nothing downstream had to
 *       move to make the ownership right. The full rationale for each shape's members now sits beside
 *       the declaration in ./types, where the other clients read it too.
 */
export type { PageDirection, PageResponse } from './types';

/*
 * WHY : Refactoring Rationale: every request target below is built from one of these constants
 *       through `requestPath` instead of from a string literal at the call site. The manifest and the
 *       code were previously two independent descriptions of the same five addresses, so
 *       ui/src/api/contracts.test.ts could confirm the manifest matched card-api.yaml while a literal
 *       three lines away addressed something else. Deriving the target removes that gap by
 *       construction and leaves the gate exactly one comparison to make.
 */
/*
 * Refactoring Rationale: this was `GET /api/v1/cards` with the account filter, cursor and direction
 * sent as QUERY PARAMETERS. It is `POST /api/v1/cards/search` sending them in a body, because the
 * account filter is an account identifier and a query string is part of the request line — which the
 * load balancer writes into its mandatory access log itself, before any application code runs. The
 * sibling `lookupCard` already moved a card number out of the request line for exactly that reason;
 * the migration's sensitive-data logging contract names account identifiers in the same sentence as
 * the primary account number, so leaving the account filter in the query string applied that finding
 * to only one of the two values it covers.
 *
 * Assumptions: the account narrowing this operation accepts IS an access path and not a convenience.
 * The reference surfaces the card file's alternate index to CICS as a file of its own, `CARDAIX` keyed
 * on the account identifier, and the online programs read it; the migration preserves it as a
 * non-unique secondary index on `card.cards(account_id)`. Recording that here is what stops the
 * narrowing from being read as an optional filter that could be answered by scanning and discarding.
 */
const LIST_CARDS: ContractOperation = {
  method: 'POST',
  path: '/api/v1/cards/search',
  operationId: 'listCards',
};

const LOOKUP_CARD: ContractOperation = {
  method: 'POST',
  path: '/api/v1/cards/lookup',
  operationId: 'lookupCard',
};

const GET_CARD: ContractOperation = {
  method: 'GET',
  path: '/api/v1/cards/{cardKey}',
  operationId: 'getCard',
};

const UPDATE_CARD: ContractOperation = {
  method: 'PUT',
  path: '/api/v1/cards/{cardKey}',
  operationId: 'updateCard',
};

const GET_ADMIN_CARD_DETAIL: ContractOperation = {
  method: 'GET',
  path: '/api/v1/admin/cards/{cardKey}',
  operationId: 'getAdminCardDetail',
};

/**
 * Every operation `card-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: the set is exhaustive rather than a selection. `ui/src/api/contracts.test.ts` compares
 * it with the contract for equality in both directions, so an operation published without a client
 * fails the gate and so does a client function addressing an operation the contract does not declare.
 * The second direction is the dangerous one: it addresses a target the edge answers with its own 404
 * while the service is running and correct.
 */
export const CARD_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  LIST_CARDS,
  LOOKUP_CARD,
  GET_CARD,
  UPDATE_CARD,
  GET_ADMIN_CARD_DETAIL,
];

/*
 * WHY : Refactoring Rationale: the rendering guard below matches the masked form POSITIVELY, and the
 *       revision it replaces refused only the sixteen-digit form -- `isCardNumber(displayCardNumber)`
 *       -- which is a strictly narrower test than the contract states. `card-api.yaml` declares
 *       `pattern: '^\*{12}[0-9]{4}$'` on this member in BOTH shapes that carry it, at L1988 for the
 *       list row and L2161 for the detail, so anything else in it is a contract breach the client is
 *       positioned to catch. The negative form admitted every unmasked rendering that was not exactly
 *       sixteen bare digits: a separator-formatted number such as `4111-1111-1111-1111`, a
 *       space-grouped one, a fifteen-digit or seventeen-digit value, a partially masked `****1111`,
 *       and the empty string. The first two of those are whole primary account numbers -- the very
 *       disclosure this guard exists to stop -- and they reached a table, a log line and a bug report
 *       unremarked, because a test asking "is this sixteen digits" answers no to a value carrying
 *       nineteen characters of which sixteen are digits.
 * WHY : Alternatives Considered: extending `isCardNumber` in `../routes/cards` to strip separators
 *       before counting digits. Rejected because it fixes the narrower test rather than replacing it,
 *       and it would still admit a partial mask; the guard's obligation is the contract's pattern, not
 *       a family of near-misses enumerated one at a time.
 * WHY : ⚠️ Refactoring Rationale: the pattern this module tests against is now IMPORTED from
 *       `./masking` rather than declared here, and the note that stood in its place rejected exactly
 *       that move -- on the reasoning that four sibling clients each declared the literal locally, so a
 *       fifth local copy kept this module consistent with its folder. A review overturned that: five
 *       owners of one security-critical constant can drift, and a drifted mask pattern fails in the
 *       PERMISSIVE direction, so the consistency being preserved was consistency in carrying a risk.
 *       The hoist is a five-module change and was made as one; the pattern's own reasoning, including
 *       the positive form and the two anchors, now lives with the declaration.
 */

// WHY : Alternatives Considered: the two operations below are the collection-level pair, serving the
//       browse screen's two entry fields between them -- one page of rows optionally narrowed by
//       account, and one row resolved from a card number a user typed -- and the page is positioned by
//       KEY. The alternative was offset paging, expressing a position as a row ordinal and a count,
//       which is what a page number in a request would carry. Rejected because it does not preserve
//       behaviour: rows enter and leave this set while an operator pages through it, and an offset
//       names a POSITION rather than a row, so an insert ahead of the current offset repeats a row on
//       the following page and a delete skips one. Paging by key can do neither, because the position
//       it resumes from identifies a row rather than counting the rows in front of it.
// WHY : Assumptions: paging by key transcribes the reference rather than redesigning it, so the
//       mapping is one-to-one. `app/cbl/COCRDLIC.cbl` keeps the entire browse position in its own
//       communication area at L229: `WS-CA-LAST-CARDKEY` at L230, being
//       `WS-CA-LAST-CARD-NUM PIC X(16)` at L231 with `WS-CA-LAST-CARD-ACCT-ID PIC 9(11)` at L232;
//       the mirrored `WS-CA-FIRST-CARDKEY` at L233 to L235; the screen ordinal
//       `WS-CA-SCREEN-NUM PIC 9(1)` at L237 with `88 CA-FIRST-PAGE VALUE 1` at L238;
//       `WS-CA-LAST-PAGE-DISPLAYED PIC 9(1)` at L239; and `WS-CA-NEXT-PAGE-IND PIC X(1)` at L242 with
//       `88 CA-NEXT-PAGE-EXISTS VALUE 'Y'` at L244. Those are the same four ideas the page envelope
//       carries -- a position to read forward from, a position to read backward from, the ordinal that
//       decides whether a backward step exists, and one forward-availability flag -- so the envelope
//       renames the reference's own state instead of introducing a scheme of its own.
// WHY : Assumptions: the forward-availability flag is settled identically on both sides, by reading
//       ONE row more than the page shows. The reference issues a look-ahead READNEXT at L1197 whose
//       normal arm sets `CA-NEXT-PAGE-EXISTS` at L1210, which is precisely the size-plus-one probe the
//       service performs before it answers `hasNext`. A client that instead compared the row count it
//       received against a page size it assumed would report an exhausted set wrongly, because a page
//       may be short while further rows remain: the account narrowing is applied per record as the
//       reference does it at L1159, calling the filter paragraph at L1382.
// WHY : Assumptions: the eight browse verbs collapse into ONE request. Forward is STARTBR at
//       L1129, READNEXT at L1146, the look-ahead READNEXT at L1197 and ENDBR at L1258; backward is
//       STARTBR at L1273, READPREV at L1294, READPREV at L1322 and ENDBR at L1376. A stateless service
//       holds no browse between requests, so what those two verb sequences expressed is expressed
//       instead by WHICH cursor a caller sends -- `lastKey` reads forward and `firstKey` reads
//       backward -- and nothing here has an open browse to end.

/**
 * Lists cards, optionally narrowed by account.
 *
 * Assumptions: the cursor a caller supplies is `lastKey` from the page it holds when paging forward
 * and `firstKey` when paging backward, and it is sent under the single published parameter name
 * `cursor`. An earlier revision sent it as `firstKey` or `lastKey` — the names of the RESPONSE
 * members — which the contract does not declare as inputs, so the service would have seen no cursor
 * and answered the first page to every paging request. The service seals the direction into each
 * token, so replaying a backward position with direction `next` is refused with HTTP 400 rather than
 * answered with the wrong page.
 *
 * Assumptions: a cursor is an OPAQUE string that this module round-trips verbatim and never parses,
 * splits, decodes, compares, orders or increments. The rule is stated rather than assumed because the
 * two sides hold different things: the reference's position is composite, the card number beside the
 * account identifier at `app/cbl/COCRDLIC.cbl` L231 to L232, whereas `card-api.yaml` declares its
 * `CursorToken` an enciphered value of which no part is a card number, an account identifier or a row
 * ordinal, bound to the query, the caller and the direction it was minted for. Splitting on a
 * delimiter, comparing two tokens or deriving one from another would therefore encode a key structure
 * this module does not own into the browser, against a value that does not carry one. Where a cursor
 * reaches a target, `requestPath` in `./client` percent-encodes it, because base64url text may
 * legitimately hold characters a target would otherwise read as structure.
 *
 * Assumptions: no page number, offset, size or limit crosses this boundary in either direction, so
 * none appears in this signature. How many rows a page holds is the service's to decide -- the
 * reference's own bound is the seven rows its map paints, `WS-SCREEN-ROWS OCCURS 7 TIMES` at
 * `app/cbl/COCRDLIC.cbl` L255 -- and publishing it as an input would let one request ask for the whole
 * collection.
 * @param {CardListQuery} [query] - Optional criteria: the account filter, which is the first of the
 *   baseline list screen's two filter fields, plus a sealed cursor and the direction that cursor was
 *   issued for. Omit it for the first page of the unfiltered set.
 * @returns {Promise<PageResponse<CardSummary>>} One bounded page whose rows each render the card
 *   number's last four digits beside the selector that addresses it, together with the two sealed
 *   positions and the forward-availability flag the following request is built from.
 * @throws {RangeError} If a member carries a value longer than the width
 *   `CardPageQuery` publishes for it, in which case nothing is sent.
 * @throws {RangeError} If a direction is supplied without a usable cursor, if any row's rendering is not
 *   the masked form the contract declares, or if the row carries no well-formed selector.
 * @throws {Error} If the request fails, as the normalised failure `./client` raises, carrying the
 *   service's problem document: HTTP 400 for a malformed account filter or a cursor that cannot be
 *   opened, 401 when no session is held, 403 for a caller outside the required group, and 500 for a
 *   service fault. This operation publishes NO 503: only the update below declares one, because the
 *   write window it reports applies to a write.
 */
export async function listCards(query: CardListQuery = {}): Promise<PageResponse<CardSummary>> {
  // Refactoring Rationale: these criteria were assembled into a query-parameter record and are now
  //   assembled into a request body. The membership rules are unchanged — an omitted member is
  //   absent rather than null, so the service sees exactly the criteria that were supplied — because
  //   the contract still declares every member optional and still treats an absent body as the
  //   opening page of the unfiltered set.
  // Assumptions: the pair is checked before the body is assembled, so a direction that arrives with
  //   no cursor is refused rather than dropped. The membership rules above say an omitted member is
  //   absent rather than null, which is a statement about what the CALLER omitted; silently omitting
  //   something the caller did supply is a different thing and is what `keysetPagingMembers` stops.
  const body: Record<string, string> = {};

  if (query.accountId !== undefined) {
    body.accountId = query.accountId;
  }
  // Refactoring Rationale: ⚠️ the pair is established by `keysetPagingMembers`, which refuses a
  //   direction supplied without a cursor. This block used to DROP it, so a caller asking to step
  //   backward from no position received the opening page of the unfiltered set and could not tell the
  //   two apart. The refusal is this CLIENT's, and the note here used to claim it was the contract's:
  //   `card-api.yaml` publishes the opposite for this operation, answering that same pair with the
  //   opening page, because the reference does -- `app/cbl/COCRDLIC.cbl` L444-L454 answers the
  //   backward paging key pressed on the first page by reading FORWARD and adds only the sentence at
  //   L901-L904. Refusing before dispatch is still right, for the reason `keysetPagingMembers` records:
  //   the seven contracts of this migration do not answer that pair alike, so one screen calling
  //   several of them would otherwise see one caller mistake behave several ways. What is not right is
  //   telling a reader the server would have refused it.
  const paging = keysetPagingMembers(query.cursor, query.direction);
  if (paging !== undefined) {
    body.cursor = paging.cursor;
    body.direction = paging.direction;
  }

  const response = await getApiClient().post<PageResponse<CardSummary>>(
    requestPath(LIST_CARDS),
    requireWithinPublishedWidths('CardPageQuery', body),
  );

  return {
    ...response.data,
    items: response.data.items.map(validateCardSummary),
  };
}

/**
 * Resolves a typed card number into the card it names, sending the number in a request body.
 *
 * Assumptions: the number travels in a BODY and the method is POST for a transport reason and not a
 * semantic one — this call reads and changes nothing. A body is the only part of a request that neither
 * the load balancer's access log nor the distribution's records, and both record a path and a query
 * string verbatim before any application code runs. A GET with a body was rejected because caches and
 * intermediaries may drop it, which would leave the number no place to travel except the request line.
 * @param {string} cardNumber - The card's sixteen-digit number, as a user typed it, carried as a
 *   string for the reason the module note on identifiers gives.
 * @returns {Promise<CardDetail>} The card, carrying the selector the three selector-addressed
 *   operations reach it by.
 * @throws {RangeError} If the value is not exactly sixteen digits, or the response is malformed.
 * @throws {Error} If the request fails, as the normalised failure `./client` raises, carrying the
 *   service's problem document: HTTP 400 for a number the service refuses, 401 when no session is
 *   held, 403 for a caller outside the required group, 404 when no card carries the number, and 500
 *   for a service fault. No 503 is declared for it, for the reason recorded on the browse above.
 */
export async function lookupCard(cardNumber: string): Promise<CardDetail> {
  const number = requireCardNumber(cardNumber);
  const response = await getApiClient().post<CardDetail>(requestPath(LOOKUP_CARD), {
    cardNumber: number,
  });
  return validateCardDetail(response.data);
}

// WHY : Trade-offs: the two reads below answer with one row in the two renderings the contract keeps
//       apart -- the ordinary one showing the card number's last four digits, the administrative one
//       showing it whole -- so the primary account number is rendered to its last four digits
//       everywhere except that administrative read. The exchange is deliberate rather than
//       incidental. What is given
//       up is the ability to show a whole number on the browse, the detail and the edit screens, where
//       the reference did show one -- its list row paints `WS-ROW-CARD-NUM PIC X(16)` at
//       `app/cbl/COCRDLIC.cbl` L259, one of seven such rows declared at L255. What is bought is that
//       disclosure of a whole number is confined to a single address under a single authority, which
//       can be authorised, routed, rate-limited and audited on its own, so an ordinary caller reaches
//       it not with a partial answer but with HTTP 403.
// WHY : Assumptions: the rendering is produced SERVER-SIDE in the mapping layer, so this module does
//       not produce, reverse, shorten, pad or re-format one in either direction. That omission is what
//       makes the boundary checkable: a browser able to render the short form must first have received
//       the whole number, which is exactly the disclosure the short form exists to prevent, so the
//       absence of any such code here is evidence rather than style. `card-api.yaml` states the same
//       thing structurally -- the ordinary shapes publish `displayCardNumber` as twelve asterisks
//       followed by four digits, and only `AdminCardDetail` publishes a sixteen-digit `cardNumber` --
//       so the two responses are distinguishable by a schema validator and not only by a reader. What
//       this module does instead is CHECK the rendering, at `validateCardSummary` below.

/**
 * Retrieves one card by the opaque selector a response published for it.
 * @param {string} cardKey - The card's sealed selector.
 * @returns {Promise<CardDetail>} The selected card detail, whose card number is rendered to its last
 *   four digits and whose version token an update submission echoes back.
 * @throws {RangeError} If the value is not the published selector shape, or the response is malformed.
 * @throws {Error} If the request fails, as the normalised failure `./client` raises, carrying the
 *   service's problem document: HTTP 400 for a selector that cannot be opened, 401 when no session is
 *   held, 403 for a caller outside the required group, 404 when the selector addresses no row, and 500
 *   for a service fault. No 503 is declared for it, for the reason recorded on the browse above.
 */
export async function getCard(cardKey: string): Promise<CardDetail> {
  const identifier = requireCardSelector(cardKey);
  const response = await getApiClient().get<CardDetail>(
    requestPath(GET_CARD, { cardKey: identifier }),
  );
  return validateCardDetail(response.data);
}

/**
 * Retrieves one card by its sealed selector, with the primary account number rendered whole.
 *
 * Assumptions: this is the administrative counterpart of `getCard` and differs from it only in what
 * the response is permitted to render. It is the single documented exception to the rendering rule
 * stated above: the whole number arrives in the response BODY and never in the target, so the
 * disclosure is confined to a payload that neither the edge access log nor the browser's history
 * retains, and the selector addresses the row exactly as it does for the ordinary operation.
 * @param {string} cardKey - The card's sealed selector.
 * @returns {Promise<AdminCardDetail>} The card detail, additionally carrying the whole sixteen-digit
 *   card number as a string.
 * @throws {RangeError} If the value is not the published selector shape, if the masked rendering is
 *   not exactly twelve mask characters followed by four digits, if the version is invalid, or if the
 *   whole number is not sixteen digits.
 * @throws {Error} If the request fails, as the normalised failure `./client` raises, carrying the
 *   service's problem document: HTTP 400 for a selector that cannot be opened, 401 when no session is
 *   held, 403 for a caller outside the administrative group, 404 when the selector addresses no row,
 *   and 500 for a service fault. No 503 is declared for it either: it is a read.
 */
export async function getAdminCardDetail(cardKey: string): Promise<AdminCardDetail> {
  const identifier = requireCardSelector(cardKey);
  const response = await getApiClient().get<AdminCardDetail>(
    requestPath(GET_ADMIN_CARD_DETAIL, { cardKey: identifier }),
  );

  /*
   * WHY : Assumptions: the whole-number member is checked for BEING sixteen digits, which is the exact
   *       inverse of the check validateCardSummary applies to `displayCardNumber`, and both run on
   *       this response. The two members carry the same value in two renderings, so a service fault
   *       that swapped them would leave a short value where the whole one belongs -- which an
   *       administrative caller reads as a successful rendering rather than as a fault. Checking both
   *       directions is what distinguishes those two outcomes.
   */
  if (!isCardNumber(response.data.cardNumber)) {
    throw new RangeError(
      'Administrative card detail must carry the whole sixteen-digit card number.',
    );
  }
  return { ...validateCardDetail(response.data), cardNumber: response.data.cardNumber };
}

// WHY : Refactoring Rationale: the write below is the one operation here that can be refused because
//       the stored row moved between the read and the submission, and HTTP 409 is surfaced DISTINCTLY
//       rather than folded into the general failure path, because it is the one refusal that is not
//       the operator's mistake and because the
//       reference already performs the check it reports. `app/cbl/COCRDUPC.cbl` commits this update at
//       its single SYNCPOINT at L470, and the account program of the same family shows the mechanism in
//       full: it snapshots a complete pre-edit before-image at `05 ACUP-OLD-DETAILS.`,
//       `app/cbl/COACTUPC.cbl` L669, holding each numeric as a display field with a numeric REDEFINES
//       -- the account identifier at L671 to L673 -- and it carries
//       `05 WS-DATACHANGED-FLAG PIC X(1).` at L168 with `88 CHANGE-HAS-OCCURRED VALUE '1'.` at L170,
//       so a record altered across the screen turn is detected before the rewrite instead of being
//       overwritten by it. The target expresses the same guarantee as a JPA `@Version` column whose
//       `OptimisticLockException` becomes 409, carrying the card program's own sentence
//       `Record changed by some one else. Please review` at `app/cbl/COCRDUPC.cbl` L207 to L208 -- two
//       words in "some one", and no closing full stop. The consequence of not distinguishing it is
//       concrete: an operator whose edit lost a race would read the same sentence as one whose write
//       failed outright, and would retry the same values rather than re-read the row.
// WHY : Assumptions: the discriminant is `isConflictFailure`, which `./client` exports, and this module
//       compares no status number of its own. That module owns the one comparison, so a second copy
//       here would be a second declaration of the same fact and could drift from it; and the sentence
//       a screen renders is the service's own, carried verbatim in the problem document the normalised
//       failure holds, never reworded here. The card program declares three refusals of this class and
//       the contract reports each of them separately -- `Could not lock record for update` at L205 to
//       L206, the data-changed sentence at L207 to L208, and `Update of record failed` at L209 to
//       L210 -- and only the data-changed one carries the row as now stored, so a caller can resubmit
//       against the version that refusal reports.
// WHY : Assumptions: that refreshed row is left for the caller to read and is deliberately not
//       projected into a typed member here. `./types` declares no shape for the conflict body, and a
//       client module may declare none of its own -- the gate in ui/src/api/contracts.test.ts refuses
//       a wire shape declared in a client module so that every shape keeps exactly one definition --
//       so typing that member belongs beside the other declarations in `./types` rather than in this
//       module, where it would become a second definition of one contract shape.

/**
 * Updates one card by the opaque selector a response published for it.
 *
 * Assumptions: the version token the request carries is the one the card was last read at, and the
 * service compares it with the stored row rather than trusting it. Submitting a token from a stale read
 * is therefore refused rather than applied, which is the whole point of sending it.
 * @param {string} cardKey - The card's sealed selector.
 * @param {CardUpdateRequest} request - The editable fields the contract admits, together with the
 *   optimistic-lock version the card was last read at.
 * @returns {Promise<CardDetail>} The card as now stored, carrying the incremented version so a second
 *   change needs no intervening read, and a freshly minted selector.
 * @throws {RangeError} If a member carries a value longer than the width
 *   `CardUpdateRequest` publishes for it, in which case nothing is sent.
 * @throws {RangeError} If the value is not the published selector shape, or the response is malformed.
 * @throws {Error} If the request fails, as the normalised failure `./client` raises, carrying the
 *   service's problem document: HTTP 400 for a field the service refuses or a selector that cannot be
 *   opened, 401 when no session is held, 403 for a caller outside the required group, 404 when the
 *   selector addresses no row, 405 and 406 and 415 for a request the edge will not accept, 409 for the
 *   three contention refusals of which the optimistic-concurrency one is distinguished by
 *   `isConflictFailure` from `./client`, and 500 or 503 for a service fault or a write window.
 */
export async function updateCard(cardKey: string, request: CardUpdateRequest): Promise<CardDetail> {
  const identifier = requireCardSelector(cardKey);
  const response = await getApiClient().put<CardDetail>(
    requestPath(UPDATE_CARD, { cardKey: identifier }),
    // Assumptions: the embossed name is the member this guard exists for -- fifty characters at
    //   `CARD-EMBOSSED-NAME PIC X(50)` in `app/cpy/CVACT02Y.cpy` -- and the version member is not
    //   checked because it is an integer, which a length bound does not describe.
    requireWithinPublishedWidths('CardUpdateRequest', request),
  );
  return validateCardDetail(response.data);
}

/**
 * Validates the two security-sensitive fields a card row carries.
 *
 * Assumptions: what is checked of the rendering is that it IS the masked form the contract declares --
 * exactly twelve mask characters followed by exactly four digits -- and not merely that it is something
 * other than sixteen bare digits. The service replaces every position but the last four, so the masked
 * form is the only rendering this member is ever permitted to hold, and requiring it is what turns a
 * server-side rendering failure into a named client error rather than a whole card number reaching a
 * table, a log line and a bug report. The `MASKED_CARD_NUMBER` declaration above records why the
 * narrower negative test this replaced was insufficient.
 *
 * Assumptions: what is checked of the selector is its SHAPE, because a row whose selector is malformed
 * would otherwise become a link that fails only when it is followed — naming `cardDetailPath` at the
 * moment a user clicked, rather than the response that was already wrong when it arrived — and, more
 * importantly, a value that is a card number rather than a selector would put the number back into a
 * URL. Its validity is the service's to decide, since only the service holds the sealing key.
 * @param {CardSummary} card - Typed response row supplied by Axios.
 * @returns {CardSummary} The row, unchanged, once its rendering is the masked form and its selector is
 *   well-formed.
 * @throws {RangeError} If the rendering is not exactly twelve mask characters followed by four digits,
 *   or the selector is malformed.
 */
function validateCardSummary(card: CardSummary): CardSummary {
  if (!MASKED_CARD_NUMBER.test(card.displayCardNumber)) {
    /*
     * WHY : Assumptions: the rejected value is DESCRIBED and never reproduced, and the four sibling
     *       guards state the same reason. The values this branch exists to catch include whole primary
     *       account numbers, so quoting the offending value in the message would carry the number into
     *       whatever renders or logs the failure -- which is the disclosure the guard is here to
     *       prevent, arriving by a different route.
     */
    throw new RangeError(
      'displayCardNumber must be exactly twelve mask characters followed by the last four digits.',
    );
  }
  if (!isCardSelector(card.key)) {
    throw new RangeError(
      'key must be the sealed selector a card response publishes; a card number is not one.',
    );
  }
  /*
   * WHY : Assumptions: the selector is validated here as well as the rendering, because it is about to
   *       be interpolated into a browser path. Refusing a malformed one at the boundary is what
   *       guarantees that whatever this client puts in a URL has the shape of a selector -- so a
   *       service that mistakenly returned a card number in this member could not have it silently
   *       become a path segment.
   */
  return { ...card };
}

/**
 * Validates a card detail and its optimistic-lock version.
 * @param {CardDetail} card - Typed detail supplied by Axios.
 * @returns {CardDetail} A normalized detail safe for rendering and update submission.
 * @throws {RangeError} If its rendering is not the masked form, its selector is malformed, or its
 *   version is invalid.
 */
function validateCardDetail(card: CardDetail): CardDetail {
  /*
   * WHY : Refactoring Rationale: the check is `requireConditionalOn`, shared with the account edit's
   *       revision check. Both ask whether an edit can be conditional on the revision it was read at;
   *       both had their own branch and their own message, and the account one refused a blank tag
   *       while this one refused a negative integer, so the shared rule now states BOTH unusable forms
   *       in one place. The encodings stay different because the contracts publish different ones --
   *       a body member here, a header there -- and that difference is reported rather than hidden.
   */
  requireConditionalOn(card.version, 'A card edit');
  /*
   * WHY : Assumptions: the detail-only members are carried across explicitly rather than being
   *       picked up by spreading the whole record again. validateCardSummary returns the SUMMARY
   *       type, so spreading its result narrows the object to the summary's members and the compiler
   *       reports the ones it dropped - which is how the earlier revision's omission of them from
   *       this shape was caught. Naming them here keeps that check in force: a member added to
   *       CardDetail fails to compile until it is handled here, instead of being silently discarded.
   */
  return {
    ...card,
    ...validateCardSummary(card),
    embossedName: card.embossedName,
    expirationDate: card.expirationDate,
    version: card.version,
  };
}
