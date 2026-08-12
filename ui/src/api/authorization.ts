/**
 * @file Typed client for the pending-authorization bounded context, written against
 * `services/authorization-service/src/main/resources/openapi/authorization-api.yaml`.
 *
 * Purpose
 * -------
 * Covers the five operations that contract publishes: the summary-plus-page list replacing
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl`, the detail read replacing `COPAUS1C.cbl`, that
 * detail's screen-shaped second representation and its forward paging move, and the fraud marking
 * replacing `COPAUS2C.cbl`. Every target is derived from the operation manifest below rather than
 * written as a literal, for the reason recorded in `ui/src/api/types.ts`.
 *
 * Refactoring Rationale: the screen and next-authorization operations were absent here while the
 * service implemented them, because the contract did not publish them either -- the service methods
 * had no caller anywhere. Publishing them without adding them here would leave this module claiming
 * parity with a document it no longer mirrors, which is what `contracts.test.ts` refuses.
 *
 * Why the list response is not a bare page
 * ----------------------------------------
 * Assumptions: `listPendingAuthorizations` answers an envelope carrying BOTH an account summary and a
 * page of rows, and that is the shape of the screen it replaces rather than a convenience. The
 * baseline screen renders the account's limits and balances in a fixed header band above the scrolling
 * row area, and both are read in one turn; splitting them into two requests would let the header and
 * the rows disagree about the account between two reads.
 *
 * Nullability
 * -----------
 * Assumptions: most detail members are nullable, and that is transcribed rather than tidied. The
 * segments behind them are `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` and `CIPAUDTY.cpy`,
 * whose fields are populated by an external authorizer message; a field the message omitted is absent
 * rather than empty, and collapsing null to an empty string would make an unreported merchant
 * indistinguishable from one reported as blank.
 *
 * What is deliberately absent
 * ---------------------------
 * Alternatives Considered: typing the two wire messages the contract also declares --
 * `AuthorizationRequestMessage` and `AuthorizationReplyMessage` -- so the SPA could describe the queue
 * payloads. Rejected because no browser sends or receives either: they are the CSV request and reply
 * exchanged over the queues, declared in that document so the payload contract is written down beside
 * the HTTP one. A client type for them would invite a screen to construct an authorization request,
 * which is the external authorizer's role and reaches no HTTP path published here.
 */

import { getApiClient, requestPath } from './client';
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
 *       declared here, so every consumer's import path is unchanged while each shape has one definition.
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

/*
 * Refactoring Rationale: this was `GET /api/v1/authorizations` with the account scope, cursor and
 * direction sent as QUERY PARAMETERS. It is `POST /api/v1/authorizations/search` sending them in a
 * body, because the scope is an account identifier and a query string is part of the request line —
 * which the load balancer writes into its mandatory access log itself, before any application code
 * runs. The scope is REQUIRED here, so every listing request previously wrote one account identifier
 * into a durable log object rather than only those that chose to narrow.
 */
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
 * Refactoring Rationale: this operation is added because the service method behind it had no caller
 * anywhere — not in a controller, not in the published contract and not here — while its own
 * documentation described it as published. That left the detail route with no source for the title
 * band, transaction name, program name and rendered instant the 3270 screen carried, so the chrome
 * had to be invented client-side or omitted. It is a SECOND representation of the member resource
 * rather than a replacement: the member path returns the record, this one the record plus the chrome.
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
 * leaving this client unable to tell a boundary from a tampered key — and the reference reports the
 * boundary on the screen rather than refusing the request.
 */
const GET_NEXT_PENDING_AUTHORIZATION: ContractOperation = {
  method: 'GET',
  path: '/api/v1/authorizations/{key}/next',
  operationId: 'getNextPendingAuthorization',
};

const SET_AUTHORIZATION_FRAUD_STATE: ContractOperation = {
  method: 'PUT',
  path: '/api/v1/authorizations/{key}/fraud',
  operationId: 'setAuthorizationFraudState',
};

/**
 * Every operation `authorization-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`.
 */
export const AUTHORIZATION_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  LIST_PENDING_AUTHORIZATIONS,
  GET_PENDING_AUTHORIZATION,
  GET_PENDING_AUTHORIZATION_SCREEN,
  GET_NEXT_PENDING_AUTHORIZATION,
  SET_AUTHORIZATION_FRAUD_STATE,
];

/** Matches the masked rendering every authorization response must carry. */
const MASKED_CARD_NUMBER = /^[*]{12}[0-9]{4}$/u;

/** Whether an authorization was approved or declined by the authorizer. */

/** The two fraud transitions a reviewer may request. */

/** Criteria the pending-authorization list is read with. */

/**
 * Lists one account's pending authorizations with the account summary above them.
 *
 * Assumptions: `accountId` is required by the contract and is therefore required here. There is no
 * unscoped listing: a page spanning accounts would disclose one customer's authorizations to a
 * reviewer who asked about another's.
 * @param {PendingAuthListQuery} query - The account to scope to, plus an optional sealed cursor and
 *   the direction it was issued for.
 * @returns {Promise<PendingAuthListResponse>} The account summary and one bounded page of rows, each
 *   row carrying a masked card number and its own sealed selector.
 * @throws {RangeError} If a row arrives with an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 404 when the account has no summary.
 */
export async function listPendingAuthorizations(
  query: PendingAuthListQuery,
): Promise<PendingAuthListResponse> {
  // Refactoring Rationale: these criteria were assembled into a query-parameter record and are now
  //   assembled into a request body. The membership rules are unchanged — the scope is always sent
  //   and the direction accompanies a cursor or is omitted with it — so the service sees exactly the
  //   criteria it saw before, in a place the access log does not record.
  const body: Record<string, string> = { accountId: query.accountId };
  if (query.cursor !== undefined) {
    body.cursor = query.cursor;
    body.direction = query.direction ?? 'next';
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

/**
 * Retrieves one pending authorization by its sealed selector.
 * @param {string} key - The row's opaque sealed selector, taken from a list row.
 * @returns {Promise<PendingAuthDetail>} The authorization in full, with its card number masked.
 * @throws {RangeError} If the response carries an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 400 for a tampered selector and 404 for a row
 *   that no longer exists.
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
 * Assumptions: the card number is checked for masking exactly as the member reading is. The screen
 * projection carries the same account number the record does, so a projection that leaked an unmasked
 * value would bypass the guard the member path applies — and a second representation of one resource
 * with a weaker exposure rule is precisely how such a leak reaches production unnoticed.
 * @param {string} key - The row's opaque sealed selector, taken from a list row.
 * @returns {Promise<PendingAuthDetailScreen>} The screen-shaped projection.
 * @throws {RangeError} If the response carries an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 400 for a tampered selector and 404 for a row
 *   that no longer exists.
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
 * for having no card number to check, turning a successful answer into an error.
 * @param {string} key - The sealed selector of the authorization currently displayed.
 * @returns {Promise<NextPendingAuthorization>} The following authorization, or the end-of-data
 *   indicator when the one named is the oldest beneath its account.
 * @throws {RangeError} If a returned authorization carries an unmasked card number.
 * @throws {Error} If the request fails, including HTTP 400 for a tampered selector and 404 for a row
 *   that no longer exists.
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
 * Marks one authorization as fraudulent, or reverses an existing marking.
 * @param {string} key - The row's opaque sealed selector, taken from the detail being reviewed.
 * @param {FraudMarkRequest} request - The transition to apply, with the four identifying members
 *   echoed from that detail.
 * @returns {Promise<FraudMarkResponse>} The success outcome of the write.
 * @throws {Error} If the request fails, including HTTP 400 for a tampered selector, 404 for a row
 *   that no longer exists and 409 when the body and the target identify different rows.
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
 * not been masked at all; refusing it here turns a server-side masking fault into a named client error
 * rather than a full account number rendered into a table and a bug report. The rejected value is
 * described and never reproduced, for the same reason.
 * @param {string} cardNum - The rendering as the service sent it.
 * @throws {RangeError} If the value is not the masked form.
 */
function requireMaskedCardNumber(cardNum: string): void {
  if (!MASKED_CARD_NUMBER.test(cardNum)) {
    throw new RangeError(
      'Authorization responses must carry a masked card number; an unmasked value was returned.',
    );
  }
}
