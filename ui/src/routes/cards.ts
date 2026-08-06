/**
 * Card-route contract shared by the router, links and API client.
 *
 * Assumptions: a card is addressed by its sixteen-digit number, which is the
 * primary key of `card.cards` and the value a user supplies. The baseline's own
 * detail and update screens are entered by typing that number into the list
 * screen's card-number filter field, so the number a route carries is a number a
 * user entered rather than one a response handed out.
 *
 * Refactoring Rationale: an intermediate revision of this module addressed a card
 * by a 22-character URL-safe token, because reusing the card number as a browser
 * path segment copies it into distribution access logs, browser history and
 * referrer metadata. That exposure is real and it is answered at the two places
 * that retain the value rather than by making the primary key unaddressable: the
 * service redacts any sixteen-digit run embedded in a request path before writing
 * it to an operational record, and this module refuses to build a route from
 * anything but a well-formed number, so a masked rendering taken from a response
 * cannot become a link. The token cost more than it saved -- it left the card
 * contract unable to publish its own key, which forced a second read operation
 * and a POST whose only purpose was to accept in a body the number the URL could
 * not carry, and it left this module unable to build a route from the one value a
 * user actually holds.
 *
 * Trade-offs: the number reaches the browser's own history and the distribution's
 * access log, which the SPA cannot redact. What that buys is that every card
 * route is constructible from user input and bookmarkable to the card it names,
 * and the alternative was not "no number in a URL" but "a second opaque value the
 * user cannot obtain without first being shown the card".
 */

const CARD_NUMBER_PATTERN = /^[0-9]{16}$/u;

/** Browser path pattern for a card detail screen. */
export const CARD_DETAIL_ROUTE = '/cards/:cardNumber';

/** Browser path pattern for a card update screen. */
export const CARD_EDIT_ROUTE = '/cards/:cardNumber/edit';

/**
 * Reports whether a value has the sixteen-digit form the card contract accepts.
 * @param {string} value - Candidate route or response value.
 * @returns {boolean} `true` only for exactly sixteen digit characters.
 */
export function isCardNumber(value: string): boolean {
  return CARD_NUMBER_PATTERN.test(value);
}

/**
 * Validates a route identifier without echoing a rejected value.
 * @param {string | null | undefined} value - Candidate card number from a route parameter or
 *   user input.
 * @returns {string} The validated card number.
 * @throws {RangeError} If the value is absent or is not exactly sixteen digits. A masked
 *   rendering fails here, which is the value a caller is most likely to pass by mistake because
 *   it is the only card-number-shaped value any response carries.
 */
export function requireCardNumber(value: string | null | undefined): string {
  if (value === null || value === undefined || !isCardNumber(value)) {
    throw new RangeError(
      `cardNumber must be exactly 16 digits; received a value of length ${String(value?.length ?? 0)}.`,
    );
  }
  return value;
}

/**
 * Builds the browser location for one card.
 * @param {string} cardNumber - The card's sixteen-digit number.
 * @returns {string} The card-detail route.
 * @throws {RangeError} If the value is not exactly sixteen digits.
 */
export function cardDetailPath(cardNumber: string): string {
  return `/cards/${requireCardNumber(cardNumber)}`;
}

/**
 * Builds the browser location for editing one card.
 * @param {string} cardNumber - The card's sixteen-digit number.
 * @returns {string} The card-update route.
 * @throws {RangeError} If the value is not exactly sixteen digits.
 */
export function cardEditPath(cardNumber: string): string {
  return `${cardDetailPath(cardNumber)}/edit`;
}
