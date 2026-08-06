import { getApiClient } from './client';
import { isCardNumber, requireCardNumber } from '../routes/cards';

/**
 * Summary row returned by the card browse endpoint.
 *
 * Refactoring Rationale: this carries the row's masked rendering, its account and its status, and
 * NOT the embossed name or the expiration date. An earlier revision of this interface declared those
 * two as well, and the browse it types has no such columns to fill them from: the baseline list row
 * is twenty-eight characters — an eleven-character account number, a sixteen-character card number
 * and a one-character status, declared at `app/cbl/COCRDLIC.cbl:258-260` — and `card-api.yaml`
 * publishes exactly those three. Two fields typed as present and never sent would have rendered as
 * blank columns, and a list disclosing more of each row than the screen it replaces disclosed is a
 * widening no requirement asks for. Both fields remain on the detail shape, which is where the update
 * screen reads them.
 *
 * Refactoring Rationale: a fourth member, an opaque row selector, is withdrawn with the opaque path
 * parameter it fed. The contract addresses a card by its number, and a number is a value a user
 * supplies rather than one a list response hands out; publishing an addressable value per row would
 * put back exactly what masking the rendering exists to prevent, since a caller could then lift an
 * addressable identity for every row of a page at once.
 */
export interface CardSummary {
  readonly displayCardNumber: string;
  readonly accountId: string;
  readonly activeStatus: 'Y' | 'N';
}

/**
 * Card-detail representation returned for one card number.
 *
 * Assumptions: this extends the summary rather than restating it, so the masked rendering, the
 * account and the status are described once. The three members declared here are the ones the detail
 * carries and the list does not — two editable attributes and the concurrency token — which matches
 * `CardDetail` in `card-api.yaml`.
 */
export interface CardDetail extends CardSummary {
  readonly embossedName: string;
  readonly expirationDate: string;
  readonly version: number;
}

/** Fields the card update operation permits a browser to change. */
export interface CardUpdateRequest {
  readonly embossedName: string;
  readonly expirationDate: string;
  readonly activeStatus: 'Y' | 'N';
  readonly version: number;
}

/**
 * Shared sealed-cursor page envelope used by browse endpoints.
 *
 * Refactoring Rationale: FOUR members are declared, matching `PageResponse` in common-lib and the
 * `CardPage`, `PageResponse` and `TransactionPage` schemas the three service contracts publish. An
 * earlier revision of this interface declared seven, adding `nextCursor`, `prevCursor` and `hasPrev`.
 * Those three describe a body no service sends: the shared Java record declares no such components,
 * so a Jackson rendering of it omits them and a browser reading them saw `undefined` on every page.
 * They are withdrawn rather than kept as optional, because a member that is always absent is worse
 * than no member — it reads as a value that merely happens to be missing this time.
 *
 * Assumptions: `lastKey` is both the last row's identity and the position a forward request is
 * issued from, and `firstKey` likewise for a backward one. The service seals the direction into each
 * token, so replaying `firstKey` with direction `next` is refused with HTTP 400 rather than answered
 * with the wrong page — which is what makes one value safe to serve both purposes.
 *
 * Assumptions: a backward step is expressible exactly when `firstKey` is non-null, so no separate
 * `hasPrev` member is needed. Deriving availability from the cursor's presence keeps one source of
 * truth; two members that had to agree were what let a page report a direction it could not supply.
 */
export interface PageResponse<T> {
  readonly items: readonly T[];
  readonly firstKey: string | null;
  readonly lastKey: string | null;
  readonly hasNext: boolean;
}

/**
 * Reading direction a browse request pairs with its cursor.
 *
 * Assumptions: the two members are spelled exactly as the services accept them — lower case — and
 * not as the Java enum's constant names. `TransactionListRequest.Direction` carries an explicit JSON
 * value on each constant and an explicit creator that reads it, so the wire vocabulary is `next` and
 * `previous`, and all three service contracts publish that pair in their `PageDirection` schema. An
 * earlier revision of this type spelled them FORWARD and BACKWARD, which the edge refuses with HTTP
 * 400 before any handler runs, so every paging request this client issued would have been rejected.
 *
 * Trade-offs: the union is exported and named rather than repeated inline at each call site, because
 * the same two values are needed by every screen that pages; a repeated inline union is what let this
 * client and the card contract disagree on the spelling while both still compiled.
 */
export type PageDirection = 'next' | 'previous';

/**
 * Criteria a card browse request may narrow by.
 *
 * Assumptions: every member is optional and the object itself may be omitted, because a browse with
 * no criteria is the first page of the unfiltered set, which is the screen's initial state. The three
 * are grouped into one object rather than passed positionally so that a caller adding a filter cannot
 * silently supply it in the cursor's position.
 */
export interface CardListQuery {
  readonly accountId?: string | undefined;
  readonly cardNumber?: string | undefined;
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

/**
 * Lists cards, optionally narrowed by account, by card number or by both.
 *
 * Assumptions: the cursor a caller supplies is `lastKey` from the page it holds when paging forward
 * and `firstKey` when paging backward, and it is sent under the single published parameter name
 * `cursor`. An earlier revision sent it as `firstKey` or `lastKey` — the names of the RESPONSE
 * members — which the contract does not declare as inputs, so the service would have seen no cursor
 * and answered the first page to every paging request. The service seals the direction into each
 * token, so replaying a backward position with direction `next` is refused with HTTP 400 rather than
 * answered with the wrong page.
 * @param {CardListQuery} [query] - Optional criteria: the two filters of the baseline list screen,
 *   an account number and a card number, plus a sealed cursor and the direction that cursor was
 *   issued for. Omit it for the first page of the unfiltered set.
 * @returns {Promise<PageResponse<CardSummary>>} One bounded page whose rows each carry a masked
 *   rendering of the card number.
 * @throws {RangeError} If a supplied card-number filter is not exactly sixteen digits.
 * @throws {Error} If the request fails.
 */
export async function listCards(query: CardListQuery = {}): Promise<PageResponse<CardSummary>> {
  const params: Record<string, string> = {};

  if (query.accountId !== undefined) {
    params.accountId = query.accountId;
  }
  if (query.cardNumber !== undefined) {
    // Assumptions: the filter is validated here rather than sent as typed, because the contract
    //   refuses any other width and a masked rendering is the value a caller is most likely to
    //   pass by mistake. Rejecting it locally names the fault instead of returning an HTTP 400.
    params.cardNumber = requireCardNumber(query.cardNumber);
  }
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    // Assumptions: the direction accompanies the cursor and is omitted without one, because the
    //   contract declares it meaningful only alongside a cursor and defaults it to next. Sending a
    //   direction alone would describe a position relative to nothing.
    params.direction = query.direction ?? 'next';
  }

  const response = await getApiClient().get<PageResponse<CardSummary>>('/cards', {
    params: Object.keys(params).length === 0 ? undefined : params,
  });

  return {
    ...response.data,
    items: response.data.items.map(validateCardSummary),
  };
}

/**
 * Retrieves one card by its number.
 * @param {string} cardNumber - The card's sixteen-digit number.
 * @returns {Promise<CardDetail>} The selected card detail.
 * @throws {RangeError} If the value is not exactly sixteen digits.
 * @throws {Error} If the request fails.
 */
export async function getCard(cardNumber: string): Promise<CardDetail> {
  const identifier = requireCardNumber(cardNumber);
  const response = await getApiClient().get<CardDetail>(`/cards/${identifier}`);
  return validateCardDetail(response.data);
}

/**
 * Updates one card by its number.
 * @param {string} cardNumber - The card's sixteen-digit number.
 * @param {CardUpdateRequest} request - Validated editable fields and optimistic-lock version.
 * @returns {Promise<CardDetail>} The updated card detail.
 * @throws {RangeError} If the value is not exactly sixteen digits.
 * @throws {Error} If the request fails or the optimistic lock is stale.
 */
export async function updateCard(
  cardNumber: string,
  request: CardUpdateRequest,
): Promise<CardDetail> {
  const identifier = requireCardNumber(cardNumber);
  const response = await getApiClient().put<CardDetail>(`/cards/${identifier}`, request);
  return validateCardDetail(response.data);
}

/**
 * Validates the one security-sensitive field a card row carries.
 *
 * Assumptions: what is checked is that the rendering is MASKED. The contract publishes a
 * sixteen-character value here and the masker replaces every position but the last four, so a value
 * that is sixteen digits has not been masked at all. Refusing it at the boundary is what turns a
 * server-side masking failure into a named client error rather than an unmasked number rendered into
 * a table, a log line and a bug report.
 * @param {CardSummary} card - Typed response row supplied by Axios.
 * @returns {CardSummary} The row, unchanged, once its rendering is confirmed masked.
 * @throws {RangeError} If the rendering carries an unmasked card number.
 */
function validateCardSummary(card: CardSummary): CardSummary {
  if (isCardNumber(card.displayCardNumber)) {
    throw new RangeError(
      'displayCardNumber must be a masked rendering; a sixteen-digit value has not been masked.',
    );
  }
  return { ...card };
}

/**
 * Validates a card detail and its optimistic-lock version.
 * @param {CardDetail} card - Typed detail supplied by Axios.
 * @returns {CardDetail} A normalized detail safe for rendering and update submission.
 * @throws {RangeError} If its rendering is unmasked or its version is invalid.
 */
function validateCardDetail(card: CardDetail): CardDetail {
  if (!Number.isSafeInteger(card.version) || card.version < 0) {
    throw new RangeError('Card detail version must be a non-negative safe integer.');
  }
  /*
   * WHY : Assumptions: the detail-only members are carried across explicitly rather than being
   *       picked up by spreading the whole record again. validateCardSummary returns the SUMMARY
   *       type, so spreading its result narrows the object to three members and the compiler reports
   *       the two it dropped - which is how the earlier revision's omission of them from this shape
   *       was caught. Naming them here keeps that check in force: a member added to CardDetail
   *       later fails to compile until it is handled, instead of being silently discarded.
   */
  return {
    ...card,
    ...validateCardSummary(card),
    embossedName: card.embossedName,
    expirationDate: card.expirationDate,
    version: card.version,
  };
}
