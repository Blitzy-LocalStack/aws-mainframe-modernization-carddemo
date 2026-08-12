/**
 * @file Typed client for the account bounded context, written against
 * `services/account-service/src/main/resources/openapi/account-api.yaml`.
 *
 * Purpose
 * -------
 * Cover the three operations that contract publishes to a BROWSER -- the account-and-customer read
 * replacing `app/cbl/COACTVWC.cbl`, the conditional edit replacing `app/cbl/COACTUPC.cbl`, and the
 * by-account cross-reference walk carrying the migrated `CXACAIX` access path -- and nothing else.
 * Every request target is derived from the operation manifest below through `requestPath` rather
 * than written as a literal, matching the discipline recorded in `ui/src/api/cards.ts`.
 *
 * The eight operations deliberately absent from this module
 * --------------------------------------------------------
 * Assumptions: `account-api.yaml` declares ELEVEN operations and tags eight of them `internal`,
 * securing each with `internalServiceToken` -- the account-context read, the three standalone
 * cross-reference reads and the four customer reads. No browser token satisfies that scheme, so this
 * module addresses none of them, and `ui/src/api/contracts.test.ts` asserts precisely that: its
 * `theInternalContractHasNoClient` case compares every client manifest against the internal set by
 * method AND path together. The customer data an account screen renders is therefore not fetched by
 * a customer call at all; it arrives composed inside the account-view response, which the contract
 * assembles from both records in ONE read-only transaction so that the account and the customer on
 * one screen cannot be a pairing that never existed. `app/cbl/CBCUS01C.cbl` carries no `EXEC CICS`
 * verb and appears in no resource definition, so it is a batch reader with no terminal behind it,
 * and publishing a browser-facing customer read would add a capability no baseline screen had.
 *
 * Ownership of the wire shapes
 * ----------------------------
 * Assumptions: this module declares NO wire shape. Every account, customer and cross-reference shape
 * is declared once in `./types` and re-exported here, so an account screen may import a name from
 * this module while exactly one definition of that name exists.
 *
 * Money and identifiers
 * ---------------------
 * Assumptions: every amount crosses this boundary as a STRING and is never read as a number. The
 * five account amounts are zoned-decimal fields -- `ACCT-CURR-BAL PIC S9(10)V99` at
 * `app/cpy/CVACT01Y.cpy` L7 among them -- held server-side as `NUMERIC(p,2)` and put on the wire as
 * text, and a JSON number would be parsed into an IEEE-754 double. Nothing in this file turns text
 * into a number except the single documented fold below, so a reviewer asking whether an amount can
 * lose a cent here can answer it by searching for one thing rather than by reading every call site.
 */

import { getApiClient, requestPath } from './client';
import type {
  AccountDetail,
  AccountUpdateRequest,
  AccountUpdateResponse,
  AccountViewResponse,
  CardXrefPage,
  CardXrefResponse,
  ContractOperation,
  CustomerDetail,
  PageDirection,
  PageResponse,
  SensitiveAccountUpdateFields,
  SensitiveAccountUpdateRequest,
} from './types';

/*
 * WHY : Refactoring Rationale: these are RE-EXPORTED rather than declared here. The re-export gives
 *       an account screen one import site for both the calls and the shapes they carry, while the
 *       single definition of each shape -- with the per-member rationale for every one of the
 *       forty-odd update members -- stays beside the other contracts' shapes in `./types`. Declaring
 *       any of them here would create a second definition free to drift from the contract, which is
 *       the failure `contracts.test.ts` exists to prevent for the six modules it scans.
 */
export type {
  AccountDetail,
  AccountUpdateRequest,
  AccountUpdateResponse,
  AccountViewResponse,
  CardXrefPage,
  CardXrefResponse,
  CustomerDetail,
  PageDirection,
  PageResponse,
  SensitiveAccountUpdateFields,
  SensitiveAccountUpdateRequest,
};

/*
 * WHY : Refactoring Rationale: the optimistic-concurrency predicate is re-exported from `./client`
 *       rather than reimplemented, so the account-update screen discriminates a conflict from an
 *       ordinary failure without this module inspecting a transport status itself. A local
 *       comparison of `status` with 409 would be a second place that fact is encoded, and the copy
 *       that fell out of step would render a concurrent change as a generic error -- losing
 *       behaviour the parity requirement protects, with nothing failing to say so.
 */
export { isConflictFailure } from './client';

/*
 * WHY : Refactoring Rationale: every target is derived from these entries through `requestPath`
 *       instead of from a string literal at the call site. Held as literals, the manifest and the
 *       code would be two independent descriptions of one address, so a drift gate could confirm the
 *       manifest matched the contract while a literal three lines away addressed something else.
 * WHY : Refactoring Rationale: all three carry their selector in a request BODY and none of them
 *       carries it in the request line. Each was once keyed -- the view a `GET`, the edit a `PUT`
 *       and this walk a `GET`, all on `/api/v1/accounts/{accountId}` forms -- and the contract
 *       REMOVED those forms outright rather than keeping them as aliases, because a load balancer
 *       composes its access record from the request line inside the process terminating the
 *       connection, before any application code runs, so a path segment reaches a durable store that
 *       no masker, filter or exception handler inside a service can reach; this migration's
 *       sensitive-data contract names account identifiers among the values a durable diagnostic may
 *       not hold. The consequence for this module is that it contributes no path parameter at all:
 *       `requestPath` is called with the operation alone, and the identifier is a body member.
 *       The baseline's own carrier for that selection was the shared communication area --
 *       `10 CDEMO-ACCT-ID PIC 9(11).` at `app/cpy/COCOM01Y.cpy` L38 -- which the client echoed back
 *       between pseudo-conversational turns; sending it explicitly per request keeps every request
 *       self-describing and therefore independently authorizable, and leaves no server-remembered
 *       selection state behind.
 */
const UPDATE_ACCOUNT: ContractOperation = {
  method: 'POST',
  path: '/api/v1/accounts/update',
  operationId: 'updateAccount',
};

const READ_ACCOUNT_VIEW: ContractOperation = {
  method: 'POST',
  path: '/api/v1/accounts/view',
  operationId: 'readAccountView',
};

const LIST_ACCOUNT_CARD_CROSS_REFERENCES: ContractOperation = {
  method: 'POST',
  path: '/api/v1/accounts/card-cross-references/search',
  operationId: 'listAccountCardCrossReferences',
};

/**
 * The three END-USER operations `account-api.yaml` publishes, in the order that document declares
 * them.
 *
 * Assumptions: this is a SUBSET of the contract and not the whole of it, which is what distinguishes
 * it from the sibling manifests. `account-api.yaml` declares eleven operations; the eight it tags
 * `internal` are unreachable with a browser token and are absent here by design. A reader adding
 * this manifest to the `BROWSER_CLIENTS` table in `ui/src/api/contracts.test.ts` would therefore
 * fail that gate rather than satisfy it: `theClientMatchesItsContract` compares a manifest with its
 * contract's FULL declared set for equality in both directions, so the correct gate for this module
 * is the internal-surface case that already runs, which asserts no client addresses an internal
 * operation.
 */
export const ACCOUNT_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  UPDATE_ACCOUNT,
  READ_ACCOUNT_VIEW,
  LIST_ACCOUNT_CARD_CROSS_REFERENCES,
];

/** Matches an account identifier within the eleven-digit range the contract admits. */
const ACCOUNT_ID_DIGITS = /^[0-9]{1,11}$/u;

/** The decimal digits in value order, so that a digit's value is its index in this string. */
const DECIMAL_DIGITS = '0123456789';

/** Matches the masked rendering every cross-reference row must carry. */
const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/** The response header carrying the revision the conditional edit requires back, lower-cased. */
const REVISION_HEADER = 'etag';

/** The request header carrying the revision an edit is conditional on. */
const PRECONDITION_HEADER = 'If-Match';

/** The reading direction the contract applies when a cursor is supplied without one. */
const DEFAULT_DIRECTION: PageDirection = 'next';

/*
 * WHY : Assumptions: each is a HOISTED named function rather than an inline expression at its call
 *       site, because `jsdoc/require-jsdoc` is configured with `publicOnly: false` and additionally
 *       selects `* > ArrowFunctionExpression`, so a closure written inline owes its own block -- and
 *       a block comment attached to an inline argument is moved by Prettier onto the preceding
 *       expression, which detaches it from what it documents.
 */

/**
 * Rejects an account identifier that is not the digits the contract admits.
 *
 * Assumptions: the identifier is text here and is never a number, because the baseline itself holds
 * it as characters and reinterprets it as a number only for arithmetic. `app/cpy/CVCRD01Y.cpy` L34
 * declares `10 CC-ACCT-ID PIC X(11)` with L36 redefining it as
 * `10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11).`, and L40 with L42 repeat the pattern for
 * `CC-CUST-ID` as `X(09)` over `9(9)`. Typing this parameter as a number would additionally discard
 * leading zeros, which belong to the declared eleven-character width rather than being incidental
 * padding.
 *
 * Trade-offs: this check is a convenience for the caller and NOT the authority. The service applies
 * the reference's own key edit -- `app/cbl/COACTVWC.cbl` L666 with L667 refuse a value that is not
 * numeric or equal to zeroes before any file is read -- and answers with that program's own sentence
 * from L672 in a `fieldErrors` entry naming `accountId`, which is what a form binds to. Rejecting a
 * malformed value here saves a round trip and gives immediate feedback; it must never be read as
 * replacing or pre-empting the server-side validation, so this function deliberately admits the
 * all-zeroes value the service refuses rather than duplicating that refusal with wording of its own.
 * @param {string} accountId - The identifier as a screen holds it, expected to be one to eleven
 *   decimal digits.
 * @returns {string} The same value, once established to be well formed.
 * @throws {RangeError} If the value is empty, is wider than eleven digits, or holds a character that
 *   is not a decimal digit.
 */
function requireAccountIdDigits(accountId: string): string {
  if (!ACCOUNT_ID_DIGITS.test(accountId)) {
    // Assumptions: the offending value is described and never reproduced. An account identifier is
    //   one of the values this migration's sensitive-data contract keeps out of a durable
    //   diagnostic, and a thrown message reaches exactly such a store once anything logs it.
    throw new RangeError('An account identifier must be one to eleven decimal digits.');
  }
  return accountId;
}

/**
 * Folds a string of decimal digits into the integer it spells.
 *
 * Refactoring Rationale: this stands in for `Number(accountId)`, and the substitution is not about
 * this identifier -- a coercion would convert eleven digits correctly. It is about the property the
 * whole file has to hold: an amount crosses this boundary as text precisely because a JSON number
 * becomes an IEEE-754 double, so the question "does anything here turn text into a number?" has to
 * be answerable by one search rather than by judging each call site. With no `Number(`, no
 * `parseInt`, no `parseFloat` and no unary plus anywhere in this module, that search answers itself
 * and lands on this function, whose input is an identifier and never an amount. The same exchange is
 * recorded on the equivalent fold in `./client`.
 *
 * Assumptions: the caller has already established that every character is a decimal digit, so the
 * position lookup cannot miss; this is not a general parser and must not be used as one. The result
 * is exact because the contract bounds the value at eleven digits, which is far below the largest
 * integer a double represents without loss.
 * @param {string} digits - One to eleven decimal digits, already validated by the caller.
 * @returns {number} The whole number those digits spell, in order of significance.
 */
function digitsToInteger(digits: string): number {
  let total = 0;
  for (const digit of digits) {
    total = total * 10 + DECIMAL_DIGITS.indexOf(digit);
  }
  return total;
}

/**
 * Builds the sole request body both account-keyed reads take.
 *
 * Assumptions: the member is an INTEGER because that is what `AccountLookupRequest` declares --
 * `type: integer`, `format: int64`, bounded at eleven digits -- while this module's own parameters
 * are text, as every screen-facing shape in `./types` is. The conversion therefore happens once, at
 * the wire boundary, rather than in each caller: a screen holds what the user typed and the document
 * receives what it declares. The schema is shared with the internal account-context read, which is
 * why a second identical shape is not declared for these two operations.
 * @param {string} accountId - The account to read, as one to eleven decimal digits.
 * @returns {{ accountId: number }} The request body, carrying the identifier as the integer the
 *   contract's schema declares.
 * @throws {RangeError} If the identifier is not one to eleven decimal digits.
 */
function accountLookupBody(accountId: string): { accountId: number } {
  return { accountId: digitsToInteger(requireAccountIdDigits(accountId)) };
}

/**
 * Reads the revision a response carries in its entity-tag header.
 *
 * Assumptions: the lookup is case-insensitive over the header names actually present, because an
 * HTTP field name is case-insensitive and the transport's own normalisation is not something this
 * module should depend on -- a hand-built double in a test may carry `ETag` where the browser client
 * carries `etag`, and a case-sensitive read would silently return nothing for one of them. Returning
 * nothing is a representable state rather than a fault: the account view is also used read-only by
 * the hundred-field view screen, which never submits an edit, so an absent tag must not fail a read
 * that had no use for it.
 * @param {unknown} headers - The response headers as the transport exposes them, of unknown shape.
 * @returns {string | null} The entity tag verbatim, or `null` when the response carried none.
 */
function revisionFrom(headers: unknown): string | null {
  if (typeof headers !== 'object' || headers === null) {
    return null;
  }

  // Assumptions: the narrowing above establishes an object, and the cast reads its members as
  //   `unknown` so each is checked before use rather than trusted; the alternative of typing this
  //   parameter as the transport's own header class would couple a response detail to one client.
  for (const [name, value] of Object.entries(headers as Record<string, unknown>)) {
    if (name.toLowerCase() === REVISION_HEADER && typeof value === 'string' && value.length > 0) {
      return value;
    }
  }
  return null;
}

/**
 * Rejects a cross-reference card number that did not arrive masked.
 *
 * Assumptions: the contract bounds `cardNumberMasked` at sixteen characters but declares NO pattern
 * for it, so nothing on the wire distinguishes the masked rendering from the sixteen digits the
 * stored field holds -- `XREF-CARD-NUM PIC X(16)` at `app/cpy/CVACT03Y.cpy` L5. A service echoing
 * the stored value instead of the masked one would therefore be indistinguishable from correct
 * behaviour without this check, and the one shape in this folder that legitimately carries the
 * unmasked digits is the administrative card detail in `ui/src/api/cards.ts`.
 * @param {string} cardNumberMasked - The rendering exactly as the service sent it.
 * @returns {void} Nothing; the function asserts.
 * @throws {RangeError} If the value is not twelve mask characters followed by four digits.
 */
function requireMaskedCardNumber(cardNumberMasked: string): void {
  if (!MASKED_CARD_NUMBER.test(cardNumberMasked)) {
    // Assumptions: the rejected value is described and never reproduced, because reproducing it
    //   would put the very primary account number this guard exists to catch into the message, and
    //   from there into whatever logs the failure.
    throw new RangeError(
      'Cross-reference rows must carry a card number masked to its last four digits;' +
        ' an unmasked value was returned.',
    );
  }
}

/**
 * Validates one cross-reference row and returns it unchanged.
 * @param {CardXrefResponse} row - One row of the page as the service sent it.
 * @returns {CardXrefResponse} The same row, once its card number is established to be masked.
 * @throws {RangeError} If the row's card number is not the masked rendering.
 */
function validateCardXrefRow(row: CardXrefResponse): CardXrefResponse {
  requireMaskedCardNumber(row.cardNumberMasked);
  return row;
}

/**
 * Reads one account together with its customer, for the account-view screen.
 *
 * Assumptions: one call returns both records because the contract composes them in a single
 * read-only transaction, reproducing `app/cbl/COACTVWC.cbl`, which reads the account master by key
 * and then follows the pairing to the customer master to fill one map from both. The customer is
 * located through the cross-reference rather than through a column on the account, because the
 * account record declares no customer identifier. A missing account, a missing cross-reference row
 * and a missing customer row are all reported as the same 404, so this call cannot report which of
 * the three was absent -- the distinction is real but naming it would disclose the shape of the data.
 *
 * Assumptions: the revision returned alongside the account is the value the edit requires back, and
 * a caller that intends to edit must keep it. It is a WEAK entity tag, prefixed `W/`, because two
 * responses at one revision are semantically equivalent without being byte-identical: the masked
 * identifiers and the two message channels are assembled per response.
 * @param {string} accountId - The account to read, as one to eleven decimal digits, being the value
 *   the user typed into the screen's filter field.
 * @returns {Promise<{ account: AccountViewResponse; revision: string | null }>} Resolves with the
 *   account and its customer as the screen renders them, the two message channels alongside them,
 *   and the revision to submit an edit under -- `null` when the response carried no entity tag, in
 *   which case the account may be displayed but not edited from this read.
 * @throws {RangeError} If the identifier is not one to eleven decimal digits.
 * @throws {Error} If the request fails. The rejection is the normalised problem document `./client`
 *   raises, whose `fieldErrors` array names `accountId` on HTTP 400 -- carrying the reference's own
 *   refusal wording from `app/cbl/COACTVWC.cbl` L672 -- and which reports HTTP 404 when the account,
 *   its cross-reference row or its customer does not exist.
 */
export async function readAccountView(accountId: string): Promise<{
  readonly account: AccountViewResponse;
  readonly revision: string | null;
}> {
  const response = await getApiClient().post<AccountViewResponse>(
    requestPath(READ_ACCOUNT_VIEW),
    accountLookupBody(accountId),
  );

  return { account: response.data, revision: revisionFrom(response.headers) };
}

/**
 * Applies an edited account and its customer under a revision precondition.
 *
 * Refactoring Rationale: the precondition is REQUIRED, and a stale one is answered with HTTP 409
 * carried distinctly rather than as a generic client error, because the baseline already performs
 * this exact check and reports it in its own words. `app/cbl/COACTUPC.cbl` snapshots the complete
 * pre-edit record into `05 ACUP-OLD-DETAILS.` at L669 -- holding each numeric as a display field with
 * a numeric `REDEFINES`, the account identifier as `ACUP-OLD-ACCT-ID-X PIC X(11)` redefined
 * `PIC 9(11)` at L671 to L673 and the balance as `ACUP-OLD-CURR-BAL PIC X(12)` redefined
 * `PIC S9(10)V99` at L675 to L677 -- and carries `05 WS-DATACHANGED-FLAG PIC X(1).` at L168 with
 * `88 CHANGE-HAS-OCCURRED VALUE '1'.` at L170, so a record altered across the screen turn is detected
 * before the rewrite and reported instead of overwritten. That before-image exists because the
 * read-for-update lock was never held across a user's think time. The target expresses the same check
 * as a JPA `@Version` column published as an entity tag, and the `OptimisticLockException` it raises
 * surfaces here as 409 carrying the sentence `88 DATA-WAS-CHANGED-BEFORE-UPDATE` declares at L521
 * with its literal at L522: `Record changed by some one else. Please review` -- two words in
 * "some one", and ending at "review", the period after the closing quote being COBOL's statement
 * terminator rather than part of the value. The consequence of treating 409 as its own case is that
 * the screen renders that specific sentence and offers a re-read, where a generic failure would lose
 * behaviour the parity requirement protects. Use the re-exported `isConflictFailure` to discriminate
 * it.
 *
 * Assumptions: the account and the customer are submitted TOGETHER because the reference screen does
 * so -- its map carries 128 fields across both records and one enter key commits them -- and
 * splitting them would let a screen that either succeeded or failed as a whole succeed in halves.
 *
 * Assumptions: the submitted identifier is a member of the body and is not additionally addressed,
 * so the operation has one key where the keyed form had two and a submitted identifier that
 * disagreed with an addressed one is no longer expressible. The body already carried it as its first
 * member -- screen field `ACCTSIDI` at `app/cpy-bms/COACTUP.CPY` L60, which the reference user types
 * into the map.
 * @param {SensitiveAccountUpdateRequest} request - The complete submitted state of the edit, every
 *   value a string exactly as typed, including the personal identifiers this shape carries in the
 *   clear so that an edit can set them. A caller must clear those members once the submission
 *   resolves rather than retaining them to prefill a retry, and must never log this object whole.
 * @param {string} revision - The entity tag the read returned, echoed back verbatim as the
 *   precondition. Reformatting by an intermediary is tolerated by the service; a caller should pass
 *   what it received.
 * @returns {Promise<{ account: AccountUpdateResponse; revision: string | null }>} Resolves with the
 *   state as stored, both protected identifiers already masked, the screen's information and message
 *   lines, the per-field error array the form binds to, and the NEW revision so that consecutive
 *   edits need no intervening read -- `null` when the response carried no entity tag.
 * @throws {RangeError} If no revision is supplied, which the service would otherwise answer as
 *   HTTP 400 naming the absent header.
 * @throws {Error} If the request fails. The rejection is the normalised problem document `./client`
 *   raises, and HTTP 409 is the optimistic-concurrency refusal described above -- its `fieldErrors`
 *   entry is keyed `version` and reports the CURRENT revision, so a caller may re-read and retry
 *   without a further round trip to discover what it moved to. HTTP 400 names the offending request
 *   property, and HTTP 404 reports that neither record was written.
 */
export async function updateAccount(
  request: SensitiveAccountUpdateRequest,
  revision: string,
): Promise<{
  readonly account: AccountUpdateResponse;
  readonly revision: string | null;
}> {
  if (revision.length === 0) {
    // Trade-offs: refused here rather than sent as an empty header. The service answers a blank
    //   precondition with the same 409 it uses for a stale one, which would report a conflict the
    //   caller never had; failing locally names the real defect, and the cost is one branch that
    //   duplicates a server-side rule.
    throw new RangeError('An account edit requires the revision the account read returned.');
  }

  const response = await getApiClient().post<AccountUpdateResponse>(
    requestPath(UPDATE_ACCOUNT),
    request,
    { headers: { [PRECONDITION_HEADER]: revision } },
  );

  return { account: response.data, revision: revisionFrom(response.headers) };
}

/**
 * Lists one bounded page of an account's card cross-reference rows, in ascending card-number order.
 *
 * Assumptions: this is the `CXACAIX` access path made explicit, and it is a real access path rather
 * than a convenience. The baseline surfaces that alternate index to the online region as a file over
 * the cross-reference record -- `01 CARD-XREF-RECORD.` at `app/cpy/CVACT03Y.cpy` L4, whose fifty
 * bytes are `XREF-CARD-NUM PIC X(16)` at L5, `XREF-CUST-ID PIC 9(09)` at L6,
 * `XREF-ACCT-ID PIC 9(11)` at L7 and `FILLER PIC X(14)` at L8 -- and reads it by account, the same
 * record `app/cbl/CBACT03C.cbl` walks sequentially in batch. The target preserves it as a non-unique
 * secondary index and this function is its browser-facing face. The ordering is part of the contract
 * rather than an artefact of the query, because an alternate-index read returns rows in index order
 * and a caller diffing the list depends on that order being stable.
 *
 * Assumptions: the route hangs off the ACCOUNT rather than off a cross-reference subtree of its own,
 * because the by-account read is a property of an account and because the standalone subtree is the
 * internal surface -- it carries a by-card lookup taking a whole primary account number.
 *
 * Assumptions: the page is positioned by KEY and never by an ordinal, a count or a window size, and
 * the parameters below are the only two the contract declares. An offset is evaluated against the
 * table as it stands when each page is fetched, so a row inserted or removed between two fetches
 * shifts the window and the caller skips a row or receives one twice -- which browsing by key cannot
 * do. An account holding no card yields an EMPTY page rather than a 404, matching an alternate-index
 * browse that ends immediately: having no card is a state an account is legitimately in, and it is
 * not the absence of the account.
 * @param {string} accountId - The account whose rows are wanted, as one to eleven decimal digits.
 * @param {string} [cursor] - The opaque boundary a previous page of THIS account's walk issued,
 *   resumed strictly beyond it: `lastKey` when reading forward and `firstKey` when reading backward.
 *   It is replayed verbatim and is never parsed, compared or incremented -- the service seals it
 *   against the query, the caller, the account and the direction together, so it carries meaning only
 *   to the service that minted it. Omit it for the opening page.
 * @param {PageDirection} [direction] - Which way to step from that cursor. Meaningful only alongside
 *   one, and defaulted to forward when a cursor is supplied without it; a direction alone would
 *   describe a position relative to nothing, so it is not sent without a cursor.
 * @returns {Promise<CardXrefPage>} Resolves with at most seven rows in ascending card-number order,
 *   each carrying a card number masked to its last four digits, together with both sealed boundaries
 *   and the forward-availability indicator the caller pages on.
 * @throws {RangeError} If the identifier is not one to eleven decimal digits, or if any row arrives
 *   with a card number that is not masked.
 * @throws {Error} If the request fails. The rejection is the normalised problem document `./client`
 *   raises, whose `fieldErrors` array names `accountId`, `direction` or `cursor` on HTTP 400 -- a
 *   cursor being refused when it was altered, has expired, or was issued for another query, caller,
 *   account or direction.
 */
export async function listAccountCardCrossReferences(
  accountId: string,
  cursor?: string,
  direction?: PageDirection,
): Promise<CardXrefPage> {
  const query: Record<string, string> = {};

  if (cursor !== undefined && cursor.length > 0) {
    query.cursor = cursor;
    // Assumptions: the direction accompanies the cursor and is omitted without one, because the
    //   contract declares it meaningful only alongside a cursor and defaults it to forward. A blank
    //   cursor is treated as no cursor here for the same reason the service treats it that way --
    //   as a subset of error rather than as a third state.
    query.direction = direction ?? DEFAULT_DIRECTION;
  }

  const response = await getApiClient().post<CardXrefPage>(
    requestPath(LIST_ACCOUNT_CARD_CROSS_REFERENCES),
    accountLookupBody(accountId),
    { params: query },
  );

  // Assumptions: the envelope is spread and only `items` replaced, so both sealed boundaries and the
  //   availability indicator reach the caller exactly as the service set them. The guard rejects a
  //   page rather than rendering one unmasked row, because a list is the shape that would disclose a
  //   primary account number per row.
  return { ...response.data, items: response.data.items.map(validateCardXrefRow) };
}
