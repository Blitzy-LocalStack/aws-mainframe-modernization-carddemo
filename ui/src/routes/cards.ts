/**
 * @file Card-route contract shared by the router, links and API client.
 *
 * Assumptions: a card is addressed by the OPAQUE SELECTOR the service mints for it, published as the
 * `key` member of every card response, and never by its sixteen-digit number. A selector is a
 * deployment-keyed sealing of the card's own key: the service can open it and nobody else can, so it is
 * safe in a browser path while still naming exactly one row. A route is therefore built from a value a
 * response handed out, and the one value a user types — the number — reaches a card through the lookup
 * call, which carries it in a request body.
 *
 * Refactoring Rationale: this module addressed a card by its number, on the reasoning that reusing the
 * primary key as a path segment is answered "at the two places that retain the value rather than by
 * making the primary key unaddressable" — the service redacting a sixteen-digit run in a path it logs,
 * and this module refusing to build a route from anything but a well-formed number. A review
 * established that neither place is the one that retains the value. The load balancer in front of the
 * services composes and delivers its own access-log record, from the request line, before any
 * application code runs, and access logging is mandatory in that deployment; the distribution in front
 * of this bundle does the same for the browser's own request line, and a browser keeps a history entry
 * and may send a referrer onward. All of those records are written by infrastructure the SPA and the
 * service are behind, so nothing either of them does can redact them. Under the previous spelling every
 * card view and every card edit wrote one durable copy of a primary account number into an object
 * store. The selector carries no number at all, which is a property no logging configuration has to be
 * trusted for.
 *
 * Trade-offs: two costs land on the user and neither is hidden. A card route can no longer be
 * constructed from a typed number alone — the number is resolved through `lookupCard` first, which is
 * one request rather than none — and because a selector is derived from the deployment key, ROTATING
 * that key changes every selector and a bookmarked card URL stops resolving. The selector is otherwise
 * STABLE for one card, so a bookmark survives every restart, deployment and session in between. Both
 * costs were cited as reasons for withdrawing this shape once before. They are accepted now because the
 * alternative was not "no identifier in a URL" but "the account number in every card URL, in every
 * history entry, forever".
 *
 * Refactoring Rationale: an earlier revision of this header said a selector "expires with the service's
 * configured token lifetime". It does not, and the difference is user-visible: the selector was sealed
 * by the PAGING primitive at that revision, which carries a lifetime because a cursor is a position a
 * caller moves on from. A row selector must outlive the session, so it is sealed by a different
 * primitive that has no lifetime at all, and only a key rotation invalidates one.
 *
 * Assumptions: this is a user-visible behavioural divergence from the reference system and is
 * registered as D-CARD-SELECTOR in `docs/architecture/cobol-to-service-traceability.md`. The baseline's
 * list screen narrowed itself to the card number an operator typed into `CARDSIDI PIC X(16)`
 * (`app/cpy-bms/COCRDLI.CPY:72`); here the same entry resolves to that one card and opens it, which is
 * the same outcome reached in one interaction, because the card number is the unique primary key and
 * narrowing by it could only ever yield one row.
 *
 * Assumptions: the selector's shape is checked here and its VALIDITY is not. A shape check is what
 * keeps a masked rendering, a card number or an empty string from becoming a link; whether a selector
 * opens is decided by the service, which holds the sealing key, and a rejected one is reported as an
 * ordinary refusal. Duplicating the authentication here is impossible and pretending to would be
 * worse than not checking at all.
 */

/**
 * The exact number of characters a card selector carries.
 *
 * Assumptions: 59 is what the service's sealer produces for a sixteen-character card
 * number, and `card-api.yaml` declares it as both the minimum and the maximum length
 * of every selector property and of the path parameter. It is stated as an exact
 * length rather than a range because a run of digits is itself valid URL-safe base64,
 * so the length is the only thing that distinguishes a selector from the card number
 * it stands for.
 */
export const CARD_SELECTOR_LENGTH = 59;

/*
 * WHY : Assumptions: this is the shape `card-api.yaml` publishes for its `CardSelector` schema and
 *       that `com.carddemo.common.security.SealedSelector` mints — exactly CARD_SELECTOR_LENGTH
 *       URL-safe base64 characters, being an authenticated encryption of the card's own key. It is
 *       duplicated here rather than imported because a browser bundle cannot import a Java constant,
 *       and it is anchored so a longer value carrying a valid selector inside it is refused rather
 *       than accepted.
 * WHY : Refactoring Rationale: the expression was a `v1.payload.code` form while the selector was
 *       sealed by the paging primitive, `com.carddemo.common.web.CursorToken`. It is not any more: a
 *       cursor expires and a row selector must not, so the two are sealed by different primitives and
 *       this module tracks the one the contract now declares. Keeping the old expression would have
 *       made this module refuse every selector the service issues.
 * WHY : Trade-offs: the bound is EXACT rather than a range, so this expression accepts exactly what
 *       the service accepts. A looser bound here would let this module build a link the service then
 *       refuses with HTTP 400, which reports the failure one hop later than necessary — and it would
 *       admit a raw card number, since a run of digits is itself valid URL-safe base64.
 */
const CARD_SELECTOR_PATTERN = /^[A-Za-z0-9_-]{59}$/u;

const CARD_NUMBER_PATTERN = /^[0-9]{16}$/u;

/** Browser path pattern for a card detail screen. */
export const CARD_DETAIL_ROUTE = '/cards/:cardKey';

/** Browser path pattern for a card update screen. */
export const CARD_EDIT_ROUTE = '/cards/:cardKey/edit';

/**
 * Reports whether a value has the sixteen-digit form the lookup call accepts.
 * @param {string} value - Candidate user entry or response value.
 * @returns {boolean} `true` only for exactly sixteen digit characters.
 */
export function isCardNumber(value: string): boolean {
  return CARD_NUMBER_PATTERN.test(value);
}

/**
 * Reports whether a value has the sealed form a card selector carries.
 * @param {string} value - Candidate route parameter or response member.
 * @returns {boolean} `true` only for exactly `CARD_SELECTOR_LENGTH` URL-safe base64
 *   characters. A sixteen-digit card number is refused, because it is shorter.
 */
export function isCardSelector(value: string): boolean {
  return CARD_SELECTOR_PATTERN.test(value);
}

/**
 * Validates a typed card number without echoing a rejected value.
 * @param {string | null | undefined} value - Candidate card number from user input.
 * @returns {string} The validated card number.
 * @throws {RangeError} If the value is absent or is not exactly sixteen digits. A
 *   masked rendering fails here, which is the value a caller is most likely to pass
 *   by mistake because it is the only card-number-shaped value any response carries.
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
 * Validates a card selector without echoing a rejected value.
 *
 * Assumptions: the message names neither the value nor its length beyond the count, for the same
 * reason the number guard above does not: the value most likely to be passed here by mistake is a card
 * number, and echoing it would put into a browser console the value this whole change removed from
 * every URL.
 * @param {string | null | undefined} value - Candidate selector from a route parameter or a response.
 * @returns {string} The validated selector.
 * @throws {RangeError} If the value is absent or is not the published selector shape.
 */
export function requireCardSelector(value: string | null | undefined): string {
  if (value === null || value === undefined || !isCardSelector(value)) {
    throw new RangeError(
      `cardKey must be exactly ${String(CARD_SELECTOR_LENGTH)} URL-safe characters, as a card response publishes it; received a value of length ${String(value?.length ?? 0)}.`,
    );
  }
  return value;
}

/**
 * Builds the browser location for one card.
 * @param {string} cardKey - The opaque selector a card response published for it.
 * @returns {string} The card-detail route.
 * @throws {RangeError} If the value is not the published selector shape.
 */
export function cardDetailPath(cardKey: string): string {
  return `/cards/${requireCardSelector(cardKey)}`;
}

/**
 * Builds the browser location for editing one card.
 * @param {string} cardKey - The opaque selector a card response published for it.
 * @returns {string} The card-update route.
 * @throws {RangeError} If the value is not the published selector shape.
 */
export function cardEditPath(cardKey: string): string {
  return `${cardDetailPath(cardKey)}/edit`;
}
