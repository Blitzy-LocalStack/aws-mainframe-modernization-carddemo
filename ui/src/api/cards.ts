import { getApiClient } from "./client";
import { requireOpaqueCardId } from "../routes/cards";

/** Summary row returned by the card browse endpoint. */
export interface CardSummary {
  readonly opaqueCardId: string;
  readonly displayCardNumber: string;
  readonly accountId: string;
  readonly embossedName: string;
  readonly expirationDate: string;
  readonly activeStatus: "Y" | "N";
}

/** Card-detail representation returned for one opaque selector. */
export interface CardDetail extends CardSummary {
  readonly version: number;
}

/** Fields the card update operation permits a browser to change. */
export interface CardUpdateRequest {
  readonly embossedName: string;
  readonly expirationDate: string;
  readonly activeStatus: "Y" | "N";
  readonly version: number;
}

/** Shared sealed-cursor page envelope used by browse endpoints. */
export interface PageResponse<T> {
  readonly items: readonly T[];
  readonly firstKey: string | null;
  readonly lastKey: string | null;
  readonly hasNext: boolean;
}

/**
 * Lists cards without accepting a card number as a selector.
 * @param {string} [cursor] - Optional sealed keyset cursor issued by the service.
 * @param {"next" | "previous"} direction - Browse direction relative to the cursor.
 * @returns {Promise<PageResponse<CardSummary>>} One bounded page whose rows each carry an
 *   opaque card identifier.
 * @throws {Error} If the request fails.
 */
export async function listCards(
  cursor?: string,
  direction: "next" | "previous" = "next",
): Promise<PageResponse<CardSummary>> {
  const response = await getApiClient().get<PageResponse<CardSummary>>(
    "/cards",
    {
      params:
        cursor === undefined
          ? undefined
          : {
              cursor,
              direction,
            },
    },
  );

  return {
    ...response.data,
    items: response.data.items.map(validateCardSummary),
  };
}

/**
 * Retrieves one card through its server-issued opaque selector.
 * @param {string} opaqueCardId - Opaque card identifier returned by a browse response.
 * @returns {Promise<CardDetail>} The selected card detail.
 * @throws {RangeError} If the selector is not an opaque identifier.
 * @throws {Error} If the request fails.
 */
export async function getCard(opaqueCardId: string): Promise<CardDetail> {
  const identifier = requireOpaqueCardId(opaqueCardId);
  const response = await getApiClient().get<CardDetail>(`/cards/${identifier}`);
  return validateCardDetail(response.data);
}

/**
 * Updates one card through its server-issued opaque selector.
 * @param {string} opaqueCardId - Opaque card identifier returned by a browse response.
 * @param {CardUpdateRequest} request - Validated editable fields and optimistic-lock version.
 * @returns {Promise<CardDetail>} The updated card detail.
 * @throws {RangeError} If the selector is not an opaque identifier.
 * @throws {Error} If the request fails or the optimistic lock is stale.
 */
export async function updateCard(
  opaqueCardId: string,
  request: CardUpdateRequest,
): Promise<CardDetail> {
  const identifier = requireOpaqueCardId(opaqueCardId);
  const response = await getApiClient().put<CardDetail>(
    `/cards/${identifier}`,
    request,
  );
  return validateCardDetail(response.data);
}

/**
 * Validates the security-sensitive fields required to navigate from a card row.
 * @param {CardSummary} card - Typed response row supplied by Axios.
 * @returns {CardSummary} A normalized row with a verified opaque identifier.
 * @throws {RangeError} If the identifier is not a bounded opaque token.
 */
function validateCardSummary(card: CardSummary): CardSummary {
  return {
    ...card,
    opaqueCardId: requireOpaqueCardId(card.opaqueCardId),
  };
}

/**
 * Validates a card detail and its optimistic-lock version.
 * @param {CardDetail} card - Typed detail supplied by Axios.
 * @returns {CardDetail} A normalized detail safe for routing and update submission.
 * @throws {RangeError} If its identifier or version is invalid.
 */
function validateCardDetail(card: CardDetail): CardDetail {
  if (!Number.isSafeInteger(card.version) || card.version < 0) {
    throw new RangeError(
      "Card detail version must be a non-negative safe integer.",
    );
  }
  return {
    ...validateCardSummary(card),
    version: card.version,
  };
}
