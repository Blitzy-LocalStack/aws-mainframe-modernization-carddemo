/**
 * @file Typed client for the pending-authorization bounded context, written against
 * `services/authorization-service/src/main/resources/openapi/authorization-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the five operations that contract publishes: the summary-plus-page listing that carries
 * across `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl`, the record reading and the
 * screen-shaped second representation that both carry across `COPAUS1C.cbl`, that screen's forward
 * paging move, and the single write this context admits, which carries across `COPAUS2C.cbl`. Every
 * target is derived from the operation manifest below rather than written as a literal, for the
 * reason recorded in `ui/src/api/types.ts`.
 *
 * Exports and failures
 * --------------------
 * The module evaluates to five request functions, {@link AUTHORIZATION_CONTRACT_OPERATIONS}, and the
 * wire shapes it re-exports from `./types`; it declares no shape of its own and takes no module-level
 * input. Every function rejects rather than resolving an error value: the shared instance from
 * `./client` normalises each transport, status and problem-document failure into one
 * `ApiRequestError` carrying an `ApiError`, and each function enumerates the statuses its own
 * operation can produce.
 *
 * The three key-addressed operations take an opaque token
 * ------------------------------------------------------
 * Assumptions: `key` is a sealed token this service issued on a list row, and this module never
 * constructs, parses, splits or compares one. The underlying row is NOT keyed by a single value:
 * `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L19 declares `05 PA-AUTHORIZATION-KEY.` as a
 * group of `PA-AUTH-DATE-9C` at L20 and `PA-AUTH-TIME-9C` at L21, two stored components -- in the
 * encoding the money note below names -- which become two integer columns forming a composite
 * primary key alongside the account identifier. A client assembling its own token would therefore
 * have to reproduce that encoding inside a browser, and the contract forecloses it: the token's
 * payload is sealed, and one outside the sealed shape is refused with 400 before any handler runs.
 * Percent-encoding for path safety is applied centrally by `requestPath`, so no function here
 * encodes the value a second time.
 *
 * What this module deliberately does not implement
 * -----------------------------------------------
 * Assumptions: the message-side payload layouts are owned by the service and are absent here on
 * purpose. `app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy` L19 to L36 declares the eighteen-field
 * authorization request and `CCPAURLY.cpy` L19 to L24 the six-field reply; both are delimited
 * character payloads exchanged over the message transport and both are encoded and decoded by the
 * shared codec in `common-lib`. For a payload declared as character text the field order and the
 * delimiter ARE the contract, so a second encoder here would give one contract two definitions free
 * to drift apart, and a browser is on neither end of that exchange. No packed-decimal (`COMP-3`)
 * handling appears here either: the two segments store their amounts packed, they are decoded at the
 * extract and persistence edge, and every amount consequently arrives as a decimal string.
 *
 * Money is a string; the two counts are not
 * -----------------------------------------
 * Assumptions: every monetary member of every shape here arrives as a decimal string and is passed
 * through untouched -- never parsed, rounded or re-rendered by this module. A JSON number is read
 * into an IEEE-754 binary64 double, which cannot represent most scale-two fractions exactly, so a
 * cent the service computed could be read back as a different cent. This context is the only one
 * whose persisted data begins as packed decimal: `CIPAUSMY.cpy` L23 to L26 and L29 to L30 hold
 * `PA-CREDIT-LIMIT`, `PA-CASH-LIMIT`, `PA-CREDIT-BALANCE`, `PA-CASH-BALANCE`,
 * `PA-APPROVED-AUTH-AMT` and `PA-DECLINED-AUTH-AMT` as `PIC S9(09)V99 COMP-3`, and `CIPAUDTY.cpy`
 * L34 to L35 hold `PA-TRANSACTION-AMT` and `PA-APPROVED-AMT` as `PIC S9(10)V99 COMP-3` -- which is
 * why the contract publishes two amount widths, nine integer digits for the summary and ten for the
 * detail, rather than one.
 *
 * Assumptions: that rule is precise rather than blanket, and the same segment proves it.
 * `PA-APPROVED-AUTH-CNT` and `PA-DECLINED-AUTH-CNT` at `CIPAUSMY.cpy` L27 to L28 are
 * `PIC S9(04) COMP` -- occurrence counts and not amounts -- so `PendingAuthSummary` types the two as
 * numbers and a screen may total them. Rendering a count as a string, or reading an amount as a
 * number, would each get exactly one of these two cases wrong.
 *
 * The match-status and fraud domains are closed
 * ---------------------------------------------
 * Assumptions: `MatchStatus` and `AuthFraudFlag` admit only their declared characters, so a value
 * outside them is a contract violation rather than a state to accommodate. `CIPAUDTY.cpy` L45
 * declares `PA-MATCH-STATUS PIC X(01)` with exactly four condition names -- `PA-MATCH-PENDING` 'P'
 * at L46, `PA-MATCH-AUTH-DECLINED` 'D' at L47, `PA-MATCH-PENDING-EXPIRED` 'E' at L48 and
 * `PA-MATCHED-WITH-TRAN` 'M' at L49 -- and L50 declares `PA-AUTH-FRAUD PIC X(01)` with only two,
 * `PA-FRAUD-CONFIRMED` 'F' at L51 and `PA-FRAUD-REMOVED` 'R' at L52. The fraud member is therefore
 * read NULLABLY: an authorization no reviewer has examined satisfies neither condition name, which
 * is a third state distinct from both tags, so a screen treating the member as a two-valued flag
 * would report an unexamined authorization as carrying one of them.
 *
 * The five account-status slots are five members
 * ---------------------------------------------
 * Assumptions: `PendingAuthSummary` carries `accountStatus1` through `accountStatus5` as five
 * discrete members, and anything here that reads them reads those five names rather than gathering
 * them into an array. `CIPAUSMY.cpy` L22 declares `05 PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES`,
 * an arity of exactly five that the target schema enforces as five columns; an array would admit a
 * sixth and would invite a screen to iterate a length no declaration supports.
 *
 * One member is spelt differently from the datum it carries
 * --------------------------------------------------------
 * Refactoring Rationale: `CIPAUDTY.cpy` L36 declares `PA-MERCHANT-CATAGORY-CODE PIC X(04)`, spelling
 * the middle word CATAGORY, and the target member is `merchantCategoryCode`. The baseline spelling is
 * named here so the lineage stays unambiguous to a reader matching the two side by side: the baseline
 * declares one spelling, the target uses another, and the divergence is documented in
 * `docs/architecture/data-model-and-schema-mapping.md`. The baseline is reference material this
 * migration reads and never edits.
 *
 * Where the distributed transaction went
 * -------------------------------------
 * Assumptions: no seam here spans two datastores, and a reader should not go looking for one. The
 * reference system held the authorization detail in one resource manager and the fraud row in
 * another and joined them with a two-phase commit; both live in one PostgreSQL schema in the target,
 * so that distributed transaction is eliminated rather than emulated and the fraud write below is one
 * local transaction. Nothing about the change is observable from a browser, which is precisely why it
 * is written down.
 *
 * Alternatives Considered: typing the two message shapes the contract also declares --
 * `AuthorizationRequestMessage` and `AuthorizationReplyMessage` -- so the application could describe
 * the queue payloads. Rejected because no browser sends or receives either: they are the request and
 * reply exchanged over the message transport, declared in that document so the payload contract is
 * written down beside the HTTP one. A client type for them would invite a screen to construct an
 * authorization request, which is the external authorizer's role and reaches no HTTP path published
 * here.
 */

import { getApiClient, keysetPagingMembers, requestPath } from './client';
import { MASKED_CARD_NUMBER } from './masking';
import type {
  ContractOperation,
  FraudMarkRequest,
  FraudMarkResponse,
  NextPendingAuthorization,
  PendingAuthDetail,
  PendingAuthDetailScreen,
  PendingAuthListQuery,
  PendingAuthListResponse,
} from './types';

/*
 * WHY : Refactoring Rationale: the authorization wire shapes are RE-EXPORTED from ./types rather than
 *       declared here, so every consumer's import path is unchanged while each shape has one
 *       definition. `contracts.test.ts` gates both halves of that: it refuses a wire shape declared
 *       in a client module, and it refuses a client module that stopped re-exporting its own.
 */
export type {
  ApprovalStatus,
  MatchStatus,
  AuthFraudFlag,
  FraudAction,
  PendingAuthSummary,
  PendingAuthListItem,
  PendingAuthListResponse,
  PendingAuthDetail,
  PendingAuthDetailScreen,
  NextPendingAuthorization,
  FraudMarkRequest,
  FraudMarkResponse,
  PendingAuthListQuery,
} from './types';

// WHY : Assumptions: the listing is a POST on a literal `search` segment and takes its account
//       scope, cursor and direction in a BODY rather than as query parameters. The scope is an
//       account identifier and it is required, so a query string would write one into the edge
//       access log -- which the load balancer records from the request line before any application
//       code runs -- on every listing request rather than only on those that chose to narrow. The
//       literal segment also resolves ahead of the templated member path beside it, and a sealed
//       token cannot spell `search`, so the two cannot collide.
const LIST_PENDING_AUTHORIZATIONS: ContractOperation = {
  method: 'POST',
  path: '/api/v1/authorizations/search',
  operationId: 'listPendingAuthorizations',
};

const GET_PENDING_AUTHORIZATION: ContractOperation = {
  method: 'GET',
  path: '/api/v1/authorizations/{key}',
  operationId: 'getPendingAuthorization',
};

/**
 * The screen-shaped reading of one authorization.
 *
 * Assumptions: this is a SECOND representation of the member resource rather than a replacement for
 * it. The member path answers the stored record; this one answers the record plus the six chrome
 * components no segment holds -- the transaction name, the two title lines, the program name, the
 * rendered instant and the message line -- which the service derives from its own constants and
 * clock and accepts from no caller. A client that draws no terminal screen has no use for the second,
 * which is why the contract publishes two paths rather than one negotiated body.
 */
const GET_PENDING_AUTHORIZATION_SCREEN: ContractOperation = {
  method: 'GET',
  path: '/api/v1/authorizations/{key}/screen',
  operationId: 'getPendingAuthorizationScreen',
};

/**
 * The forward paging move of the detail screen.
 *
 * Assumptions: reaching the oldest authorization is a SUCCESSFUL answer carrying an end-of-data
 * indicator, not a 404. A 404 would be indistinguishable from a selector naming nothing at all,
 * leaving this client unable to tell a boundary from a tampered token -- and the reference reports
 * the boundary on the screen rather than refusing the request.
 */
const GET_NEXT_PENDING_AUTHORIZATION: ContractOperation = {
  method: 'GET',
  path: '/api/v1/authorizations/{key}/next',
  operationId: 'getNextPendingAuthorization',
};

// WHY : Refactoring Rationale: marking fraud is a destructive, externally meaningful state change,
//       so it stays a separate explicit call instead of being folded into a general detail update.
//       The baseline reaches it through a program of its own --
//       `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl`, "Mark Authorization Message Fraud" --
//       which writes its row to a table of its own, declared at
//       `app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl`. Keeping it distinct is what stops it
//       being triggered as a side effect of viewing or editing an authorization, and it is what lets
//       the detail screen put a confirmation in front of it. The service gates this path by prefix
//       for the same reason: a mixed body would put the write behind the path of a read, so a
//       deployment narrowing one could not avoid narrowing the other.
// WHY : Alternatives Considered: PUT rather than POST, because the reference action is not one-way.
//       `WS-FRD-ACTION` admits a report and a removal, so the request names the state to end in; a
//       retry after a lost response therefore leaves the same state instead of applying a second
//       change.
const SET_AUTHORIZATION_FRAUD_STATE: ContractOperation = {
  method: 'PUT',
  path: '/api/v1/authorizations/{key}/fraud',
  operationId: 'setAuthorizationFraudState',
};

/**
 * Every operation `authorization-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`. The message-driven half of this context appears
 * in neither, because it is reached over the message transport and publishes no HTTP path: the
 * request consumer, the expiry sweep and the extract utilities are all absent by that rule.
 */
export const AUTHORIZATION_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  LIST_PENDING_AUTHORIZATIONS,
  GET_PENDING_AUTHORIZATION,
  GET_PENDING_AUTHORIZATION_SCREEN,
  GET_NEXT_PENDING_AUTHORIZATION,
  SET_AUTHORIZATION_FRAUD_STATE,
];

/**
 * Lists one account's pending authorizations with the account summary above them.
 *
 * Assumptions: the response is an ENVELOPE carrying both the account summary and one page of rows,
 * and that is the shape of the screen it replaces rather than a convenience. The reference renders
 * the account's limits and balances in a header band above the scrolling row area and reads both in
 * one turn, so splitting them into two requests would let the header and the rows disagree about the
 * account between two reads.
 *
 * Assumptions: `accountId` is the contract's MANDATORY scope and not an optional filter, so there is
 * no unscoped listing. A page spanning accounts would disclose one customer's authorizations to a
 * reviewer who asked about another's, and both callers of this operation hold the same authority, so
 * no authority distinguishes them.
 *
 * Assumptions: rows arrive NEWEST FIRST within the account rather than in ascending order, and that
 * ordering is the baseline's own -- `app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl` declares its
 * index as `(CARD_NUM ASC, AUTH_TS DESC)`, descending on the authorization instant. A caller that
 * assumed ascending order would offer the oldest authorization as the one to review.
 *
 * Assumptions: an account with no summary row is a SUCCESS and not a 404. The service answers a
 * summary block whose counts and totals are zero, an empty row array and no cursor tokens, because
 * the reference renders that absence on the screen rather than reporting it as an error; a caller
 * must therefore read the row count and not a status to decide whether anything is pending.
 * @param {PendingAuthListQuery} query - The account to scope to, plus an optional sealed cursor and
 *   the direction it is replayed in.
 * @returns {Promise<PendingAuthListResponse>} The account summary and one bounded page of rows, each
 *   row carrying a masked card number and its own sealed selector, together with the page envelope's
 *   cursor tokens and its forward-availability indicator.
 * @throws {RangeError} If a direction is supplied without a cursor, or if a row arrives with an
 *   unmasked card number.
 * @throws {Error} If the request fails, normalised to `ApiRequestError` carrying an `ApiError`. This
 *   operation can answer 400 for a malformed scope, cursor or direction, 401, 403, 405, 415 and 500;
 *   it has no 404.
 */
export async function listPendingAuthorizations(
  query: PendingAuthListQuery,
): Promise<PendingAuthListResponse> {
  // WHY : ⚠️ Refactoring Rationale: the contract defines all four combinations of the two optional
  //       members and refuses exactly one -- a direction sent with no cursor is a 400 keyed on the
  //       direction. This block used to answer that combination by DROPPING the direction, and the
  //       comment that stood here defended it on the grounds that a caller gets the opening page
  //       rather than "a guaranteed refusal it can do nothing with". That reasoning is withdrawn: what
  //       a caller can do with a refusal is fix the request, whereas the opening page is a wrong answer
  //       it cannot detect -- the rows simply do not move. It is also wrong about who is at fault: the
  //       only way a direction arrives without a cursor is a caller defect -- a PF7 handler that forgot
  //       to thread the `firstKey` through -- and dropping it hid that defect behind a plausible
  //       answer, with no diagnostic anywhere connecting the two. `keysetPagingMembers` in `./client`
  //       now raises it locally for all seven paged clients, which costs the caller no round trip and
  //       names both inputs.
  // WHY : Trade-offs: the direction is stated explicitly whenever a cursor is present, even though
  //       the contract already defaults an absent direction to forward. The redundancy costs one
  //       member and makes every paging request say which way it moves, so a request replayed from a
  //       log is unambiguous without knowing the default.
  const body: Record<string, string> = { accountId: query.accountId };
  const paging = keysetPagingMembers(query.cursor, query.direction);
  if (paging !== undefined) {
    body.cursor = paging.cursor;
    body.direction = paging.direction;
  }

  const response = await getApiClient().post<PendingAuthListResponse>(
    requestPath(LIST_PENDING_AUTHORIZATIONS),
    body,
  );

  for (const row of response.data.page.items) {
    requireMaskedCardNumber(row.cardNum);
  }
  return response.data;
}

// WHY : Assumptions: each passes `key` through VERBATIM as one path value and reads nothing from it.
//       The token stands for a composite row key, as the module note above records, so any inference
//       drawn from its bytes would be inference about an encoding this module does not own; and
//       `requestPath` percent-encodes it, which is what stops a sealed value containing a solidus
//       from being read as extra path segments.

/**
 * Retrieves one pending authorization by its sealed selector.
 * @param {string} key - The row's opaque sealed selector, taken from a list row and sent back
 *   unchanged.
 * @returns {Promise<PendingAuthDetail>} The stored authorization in full, with its card number
 *   masked, its amounts as decimal strings and its fraud tag null when no reviewer has examined it.
 * @throws {RangeError} If the response carries an unmasked card number.
 * @throws {Error} If the request fails, normalised to `ApiRequestError` carrying an `ApiError`. This
 *   operation can answer 400 for a selector outside the sealed shape, 401, 403, 404 for a row that no
 *   longer exists, 405 and 500.
 */
export async function getPendingAuthorization(key: string): Promise<PendingAuthDetail> {
  const response = await getApiClient().get<PendingAuthDetail>(
    requestPath(GET_PENDING_AUTHORIZATION, { key }),
  );
  requireMaskedCardNumber(response.data.cardNum);
  return response.data;
}

/**
 * Reads one authorization in the screen shape, with the chrome the terminal carried.
 *
 * Assumptions: the card number is checked for masking exactly as the member reading is, and under a
 * different member name -- this projection carries it as `cardNumber` where the record carries
 * `cardNum`. Both stand for the same datum, so a projection that leaked an unmasked value would
 * bypass the guard the member path applies, and a second representation of one resource with a weaker
 * exposure rule is precisely how such a leak reaches production unnoticed.
 * @param {string} key - The row's opaque sealed selector, taken from a list row and sent back
 *   unchanged.
 * @returns {Promise<PendingAuthDetailScreen>} The screen-shaped projection: the rendered authorization
 *   fields with the card number masked, plus the six chrome components the service derives itself.
 * @throws {RangeError} If the response carries an unmasked card number.
 * @throws {Error} If the request fails, normalised to `ApiRequestError` carrying an `ApiError`. This
 *   operation can answer 400 for a selector outside the sealed shape, 401, 403, 404 for a row that no
 *   longer exists, 405 and 500.
 */
export async function getPendingAuthorizationScreen(key: string): Promise<PendingAuthDetailScreen> {
  const response = await getApiClient().get<PendingAuthDetailScreen>(
    requestPath(GET_PENDING_AUTHORIZATION_SCREEN, { key }),
  );
  requireMaskedCardNumber(response.data.cardNumber);
  return response.data;
}

/**
 * Reads the authorization immediately following the one named, which is the forward paging move.
 *
 * Assumptions: the masking guard is applied only when an authorization is PRESENT, because the
 * end-of-data response carries none. Applying it unconditionally would refuse the boundary response
 * for having no card number to check, turning a successful answer into an error; `endOfData` is
 * therefore read before either of the other two members.
 * @param {string} key - The sealed selector of the authorization on display, sent back unchanged.
 * @returns {Promise<NextPendingAuthorization>} The following authorization, or the end-of-data
 *   indicator and the reference screen's own boundary sentence when the one named is the oldest
 *   beneath its account.
 * @throws {RangeError} If a returned authorization carries an unmasked card number.
 * @throws {Error} If the request fails, normalised to `ApiRequestError` carrying an `ApiError`. This
 *   operation can answer 400 for a selector outside the sealed shape, 401, 403, 404 for a row that no
 *   longer exists, 405 and 500; exhaustion is not among them.
 */
export async function getNextPendingAuthorization(key: string): Promise<NextPendingAuthorization> {
  const response = await getApiClient().get<NextPendingAuthorization>(
    requestPath(GET_NEXT_PENDING_AUTHORIZATION, { key }),
  );
  if (response.data.authorization) {
    requireMaskedCardNumber(response.data.authorization.cardNum);
  }
  return response.data;
}

/**
 * Reports one authorization as fraudulent, or withdraws an existing report.
 *
 * Assumptions: the body names the state to END IN and carries nothing else. The contract admits one
 * member, the action, and admits no other, so the report date is not sent: the reference stamps it
 * from its own clock before either write path is chosen, and a caller-supplied date would let a
 * browser decide when a fraud report was made. A withdrawal is a reached state that records when the
 * report was taken back, not a return to the never-examined state the module note above describes.
 *
 * Alternatives Considered: resolving an envelope that pairs the body with the created-or-updated
 * outcome, since the contract carries the insert-versus-update distinction on the STATUS CODE -- 201
 * where the fraud row was inserted, 200 where an existing one was replaced. Rejected because such an
 * envelope is a shape the contract does not declare, and every wire shape in this tree has exactly
 * one definition in `./types`; inventing one here would be the first exception. The distinction stays
 * available to a screen through the verbatim outcome sentence the service carries in `message`, which
 * is worded differently for the two paths, and it is a difference no screen needs to branch on
 * because the confirmation the reference displays is chosen by the ACTION rather than by which write
 * path ran.
 * @param {string} key - The row's opaque sealed selector, taken from the detail under review and sent
 *   back unchanged.
 * @param {FraudMarkRequest} request - The fraud state to set: report or withdraw.
 * @returns {Promise<FraudMarkResponse>} The success outcome, carrying the reference program's own
 *   sentence for whichever write path ran. A non-success is never reported in this body.
 * @throws {Error} If the request fails, normalised to `ApiRequestError` carrying an `ApiError`. This
 *   operation can answer 400 for a selector outside the sealed shape or an action outside its
 *   two-character domain, 401, 403, 404 for a row that no longer exists, 405, 409 when another
 *   reviewer holds the row and this request could not obtain it within the configured wait, 415, 500
 *   and 503 while writes are quiesced.
 */
export async function setAuthorizationFraudState(
  key: string,
  request: FraudMarkRequest,
): Promise<FraudMarkResponse> {
  const response = await getApiClient().put<FraudMarkResponse>(
    requestPath(SET_AUTHORIZATION_FRAUD_STATE, { key }),
    request,
  );
  return response.data;
}

/**
 * Asserts that a card number arrived masked before it is rendered anywhere.
 *
 * Assumptions: what is checked is that the value HAS been masked, not merely that it is sixteen
 * characters. The masker replaces every position but the last four, so a value of sixteen digits has
 * not been masked at all; refusing it here turns a server-side masking fault into a named client
 * error rather than a whole account number rendered into a table and a bug report. The rejected value
 * is described and never reproduced, for the same reason.
 * @param {string} cardNum - The rendering as the service sent it.
 * @returns {void} Nothing; the function asserts. A returned value would invite a caller to use the
 *   result in place of the checked one, and there is nothing to substitute -- the rendering is either
 *   the masked form the service sent or a fault, and this function never produces a rendering of its
 *   own.
 * @throws {RangeError} If the value is not the masked form.
 */
function requireMaskedCardNumber(cardNum: string): void {
  if (!MASKED_CARD_NUMBER.test(cardNum)) {
    throw new RangeError(
      'Authorization responses must carry a masked card number; an unmasked value was returned.',
    );
  }
}
