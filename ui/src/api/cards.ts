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
 * could add. The one operation that accepts a number, the lookup, sends it in a query the contract
 * declares for that purpose and returns the selector the other four use.
 */

import { getApiClient, requestPath } from './client';
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
 * @param {CardListQuery} [query] - Optional criteria: the account filter, which is the first of the
 *   baseline list screen's two filter fields, plus a sealed cursor and the direction that cursor was
 *   issued for. Omit it for the first page of the unfiltered set.
 * @returns {Promise<PageResponse<CardSummary>>} One bounded page whose rows each carry a masked
 *   rendering of the card number and the selector that addresses it.
 * @throws {RangeError} If any row arrives unmasked or without a well-formed selector.
 * @throws {Error} If the request fails.
 */
export async function listCards(query: CardListQuery = {}): Promise<PageResponse<CardSummary>> {
  // Refactoring Rationale: these criteria were assembled into a query-parameter record and are now
  //   assembled into a request body. The membership rules are unchanged — an omitted member is
  //   absent rather than null, so the service sees exactly the criteria that were supplied — because
  //   the contract still declares every member optional and still treats an absent body as the
  //   opening page of the unfiltered set.
  const body: Record<string, string> = {};

  if (query.accountId !== undefined) {
    body.accountId = query.accountId;
  }
  if (query.cursor !== undefined) {
    body.cursor = query.cursor;
    // Assumptions: the direction accompanies the cursor and is omitted without one, because the
    //   contract declares it meaningful only alongside a cursor and defaults it to next. Sending a
    //   direction alone would describe a position relative to nothing.
    body.direction = query.direction ?? 'next';
  }

  const response = await getApiClient().post<PageResponse<CardSummary>>(
    requestPath(LIST_CARDS),
    body,
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
 * @param {string} cardNumber - The card's sixteen-digit number, as a user typed it.
 * @returns {Promise<CardDetail>} The card, carrying the selector every later request addresses it by.
 * @throws {RangeError} If the value is not exactly sixteen digits, or the response is malformed.
 * @throws {Error} If the request fails, including HTTP 404 when no card carries the number.
 */
export async function lookupCard(cardNumber: string): Promise<CardDetail> {
  const number = requireCardNumber(cardNumber);
  const response = await getApiClient().post<CardDetail>(requestPath(LOOKUP_CARD), {
    cardNumber: number,
  });
  return validateCardDetail(response.data);
}

/**
 * Retrieves one card by the opaque selector a response published for it.
 * @param {string} cardKey - The card's sealed selector.
 * @returns {Promise<CardDetail>} The selected card detail.
 * @throws {RangeError} If the value is not the published selector shape, or the response is malformed.
 * @throws {Error} If the request fails.
 */
export async function getCard(cardKey: string): Promise<CardDetail> {
  const identifier = requireCardSelector(cardKey);
  const response = await getApiClient().get<CardDetail>(
    requestPath(GET_CARD, { cardKey: identifier }),
  );
  return validateCardDetail(response.data);
}

/**
 * Retrieves one card by its sealed selector, with the primary account number unmasked.
 *
 * Assumptions: this is the administrative counterpart of `getCard` and differs from it only in what
 * the response is permitted to render. The full number arrives in the response BODY and never in the
 * target, so the disclosure is confined to a payload that neither the edge access log nor the
 * browser's history retains; the selector addresses the row exactly as it does for the ordinary
 * operation.
 * @param {string} cardKey - The card's sealed selector.
 * @returns {Promise<AdminCardDetail>} The card detail, additionally carrying the unmasked
 *   sixteen-digit card number.
 * @throws {RangeError} If the value is not the published selector shape, if the masked rendering is
 *   itself unmasked, if the version is invalid, or if the unmasked number is not sixteen digits.
 * @throws {Error} If the request fails, including HTTP 403 for a caller outside the administrative
 *   group.
 */
export async function getAdminCardDetail(cardKey: string): Promise<AdminCardDetail> {
  const identifier = requireCardSelector(cardKey);
  const response = await getApiClient().get<AdminCardDetail>(
    requestPath(GET_ADMIN_CARD_DETAIL, { cardKey: identifier }),
  );

  /*
   * WHY : Assumptions: the unmasked member is checked for BEING sixteen digits, which is the exact
   *       inverse of the check validateCardSummary applies to `displayCardNumber`, and both run on
   *       this response. The two members carry the same value in two renderings, so a service fault
   *       that swapped them would leave a masked value where the unmasked one belongs -- which an
   *       administrative caller reads as a masking success rather than as a fault. Checking both
   *       directions is what distinguishes those two outcomes.
   */
  if (!isCardNumber(response.data.cardNumber)) {
    throw new RangeError(
      'Administrative card detail must carry the unmasked sixteen-digit card number.',
    );
  }
  return { ...validateCardDetail(response.data), cardNumber: response.data.cardNumber };
}

/**
 * Updates one card by the opaque selector a response published for it.
 * @param {string} cardKey - The card's sealed selector.
 * @param {CardUpdateRequest} request - Validated editable fields and optimistic-lock version.
 * @returns {Promise<CardDetail>} The updated card detail, carrying a freshly minted selector.
 * @throws {RangeError} If the value is not the published selector shape, or the response is malformed.
 * @throws {Error} If the request fails or the optimistic lock is stale.
 */
export async function updateCard(cardKey: string, request: CardUpdateRequest): Promise<CardDetail> {
  const identifier = requireCardSelector(cardKey);
  const response = await getApiClient().put<CardDetail>(
    requestPath(UPDATE_CARD, { cardKey: identifier }),
    request,
  );
  return validateCardDetail(response.data);
}

/**
 * Validates the two security-sensitive fields a card row carries.
 *
 * Assumptions: what is checked of the rendering is that it is MASKED. The contract publishes a
 * sixteen-character value here and the masker replaces every position but the last four, so a value
 * that is sixteen digits has not been masked at all. Refusing it at the boundary is what turns a
 * server-side masking failure into a named client error rather than an unmasked number rendered into
 * a table, a log line and a bug report.
 *
 * Assumptions: what is checked of the selector is its SHAPE, because a row whose selector is malformed
 * would otherwise become a link that fails only when it is followed — naming `cardDetailPath` at the
 * moment a user clicked, rather than the response that was already wrong when it arrived — and, more
 * importantly, a value that is a card number rather than a selector would put the number back into a
 * URL. Its validity is the service's to decide, since only the service holds the sealing key.
 * @param {CardSummary} card - Typed response row supplied by Axios.
 * @returns {CardSummary} The row, unchanged, once its rendering is masked and its selector well-formed.
 * @throws {RangeError} If the rendering carries an unmasked card number or the selector is malformed.
 */
function validateCardSummary(card: CardSummary): CardSummary {
  if (isCardNumber(card.displayCardNumber)) {
    throw new RangeError(
      'displayCardNumber must be a masked rendering; a sixteen-digit value has not been masked.',
    );
  }
  if (!isCardSelector(card.key)) {
    throw new RangeError(
      'key must be the sealed selector a card response publishes; a card number is not one.',
    );
  }
  /*
   * WHY : Assumptions: the selector is validated here as well as the masked rendering, because it is
   *       about to be interpolated into a browser path. Refusing a malformed one at the boundary is
   *       what guarantees that whatever this client puts in a URL has the shape of a selector -- so a
   *       service that mistakenly returned a card number in this member could not have it silently
   *       become a path segment.
   */
  return { ...card };
}

/**
 * Validates a card detail and its optimistic-lock version.
 * @param {CardDetail} card - Typed detail supplied by Axios.
 * @returns {CardDetail} A normalized detail safe for rendering and update submission.
 * @throws {RangeError} If its rendering is unmasked, its selector malformed, or its version invalid.
 */
function validateCardDetail(card: CardDetail): CardDetail {
  if (!Number.isSafeInteger(card.version) || card.version < 0) {
    throw new RangeError('Card detail version must be a non-negative safe integer.');
  }
  /*
   * WHY : Assumptions: the detail-only members are carried across explicitly rather than being
   *       picked up by spreading the whole record again. validateCardSummary returns the SUMMARY
   *       type, so spreading its result narrows the object to the summary's members and the compiler
   *       reports the ones it dropped - which is how the earlier revision's omission of them from
   *       this shape was caught. Naming them here keeps that check in force: a member added to
   *       CardDetail later fails to compile until it is handled, instead of being silently discarded.
   */
  return {
    ...card,
    ...validateCardSummary(card),
    embossedName: card.embossedName,
    expirationDate: card.expirationDate,
    version: card.version,
  };
}
