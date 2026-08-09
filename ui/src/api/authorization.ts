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

import { getApiClient } from './client';
import { requestPath } from './types';
import type { ContractOperation, PageDirection, PageResponse } from './types';

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
export type ApprovalStatus = 'A' | 'D';

/**
 * How an authorization matched against posted activity.
 *
 * Assumptions: four members, which are the values the `authorization.pending_auth_detail` check
 * constraint admits: pending, declined, expired and matched.
 */
export type MatchStatus = 'P' | 'D' | 'E' | 'M';

/**
 * The fraud state a reviewer may set, or the state a row already carries.
 *
 * Assumptions: three members on the way out and two on the way in. A response may carry a single
 * space, which is the baseline's unmarked value in a fixed-width field, whereas a request may only
 * mark fraud or reverse a marking -- so {@link FraudAction} is the narrower request vocabulary and is
 * declared separately rather than reused.
 */
export type FraudState = 'F' | 'R' | ' ';

/** The two fraud transitions a reviewer may request. */
export type FraudAction = 'F' | 'R';

/**
 * The account-level header the list operation renders above its rows.
 *
 * Assumptions: the five account-status members are five discrete members rather than an array,
 * matching `PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES` at
 * `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` L22 as the schema declares it. The fixed arity
 * of five is part of the contract, and an array would admit a sixth.
 */
export interface PendingAuthSummary {
  readonly accountId: string;
  readonly customerId: string;
  readonly authStatus: string | null;
  readonly accountStatus1: string | null;
  readonly accountStatus2: string | null;
  readonly accountStatus3: string | null;
  readonly accountStatus4: string | null;
  readonly accountStatus5: string | null;
  readonly creditLimit: string;
  readonly cashLimit: string;
  readonly creditBalance: string;
  readonly cashBalance: string;
  readonly approvedAuthCnt: number;
  readonly declinedAuthCnt: number;
  readonly approvedAuthAmt: string;
  readonly declinedAuthAmt: string;
}

/**
 * One row of the pending-authorization list.
 *
 * Assumptions: `key` is the opaque sealed selector the detail and fraud operations are addressed by,
 * and it is the ONLY address a row carries. The underlying row is keyed by an account identifier and
 * a packed date and time, and publishing those three as a target would put the composite key of a
 * financial record into the edge access log and the browser's history.
 */
export interface PendingAuthListItem {
  readonly key: string;
  readonly transactionId: string;
  readonly authOrigDate: string | null;
  readonly authOrigTime: string | null;
  readonly authType: string | null;
  readonly approvalStatus: ApprovalStatus;
  readonly matchStatus: MatchStatus;
  readonly amount: string;
  readonly cardNum: string;
}

/**
 * The list operation's envelope: one account summary and one bounded page of rows.
 *
 * Assumptions: `screenMessage` carries the baseline's navigation-boundary text verbatim -- the
 * already-at-the-top, already-at-the-bottom and already-at-the-last strings the contract enumerates --
 * and is null when the page needs none. Transformation rule T8 requires it to be rendered unchanged.
 */
export interface PendingAuthListResponse {
  readonly summary: PendingAuthSummary;
  readonly page: PageResponse<PendingAuthListItem>;
  readonly screenMessage?: string | null | undefined;
}

/**
 * One pending authorization in full.
 *
 * Assumptions: `authDate` and `authTime` are NUMBERS while every other date and time member here is a
 * string, and the asymmetry is the contract's. Those two are the row's composite key, held in the
 * segment as packed decimal -- a Julian day number and a millisecond-of-day -- and they are echoed
 * back into the fraud request unchanged. The remaining members are the authorizer's own
 * character-format fields, which are text in the message and stay text here.
 */
export interface PendingAuthDetail {
  readonly key: string;
  readonly accountId: string;
  readonly authDate: number;
  readonly authTime: number;
  readonly authOrigDate: string | null;
  readonly authOrigTime: string | null;
  readonly cardNum: string;
  readonly authType: string | null;
  readonly cardExpiryDate: string | null;
  readonly messageType: string | null;
  readonly messageSource: string | null;
  readonly authIdCode: string | null;
  readonly authRespCode: string | null;
  readonly authRespReason: string | null;
  readonly processingCode: string | null;
  readonly transactionAmt: string;
  readonly approvedAmt: string;
  readonly merchantCategoryCode: string | null;
  readonly acqrCountryCode: string | null;
  readonly posEntryMode: string | null;
  readonly merchantId: string | null;
  readonly merchantName: string | null;
  readonly merchantCity: string | null;
  readonly merchantState: string | null;
  readonly merchantZip: string | null;
  readonly transactionId: string;
  readonly matchStatus: MatchStatus;
  readonly authFraud: FraudState | null;
  readonly fraudRptDate: string | null;
}

/**
 * One authorization projected onto the shape the 3270 detail screen rendered.
 *
 * Assumptions: this is a DIFFERENT shape from {@link PendingAuthDetail} rather than that shape with
 * extra members, and the difference is the contract's. The record reading answers the segment's own
 * fields; this reading answers what the terminal displayed, so its amounts and codes are already
 * rendered -- `authResponse` carries the approval word rather than the two-character code, `authTime`
 * carries the rendered time rather than the packed millisecond-of-day, and the card number arrives
 * masked under the member name `cardNumber`. Reusing one interface for both would let a component read
 * a rendered value as a raw one.
 *
 * Assumptions: the six chrome members are non-nullable because the service derives every one of them
 * from its own constants and clock and accepts none from the caller. The authorizer-supplied members
 * stay nullable for the reason recorded on {@link PendingAuthDetail}: the segments behind them are
 * populated from an external message, and a field that message omitted is absent rather than blank.
 */
export interface PendingAuthDetailScreen {
  readonly transactionName: string;
  readonly title01: string;
  readonly currentDate: string;
  readonly programName: string;
  readonly title02: string;
  readonly currentTime: string;
  readonly cardNumber: string;
  readonly authDate: string;
  readonly authTime: string;
  readonly authResponse: string;
  readonly authResponseReason: string;
  readonly processingCode: string | null;
  readonly approvedAmount: string;
  readonly posEntryMode: string;
  readonly messageSource: string | null;
  readonly merchantCategoryCode: string | null;
  readonly cardExpiry: string;
  readonly authType: string | null;
  readonly transactionId: string;
  readonly matchStatus: MatchStatus;
  readonly fraudMark: string;
  readonly merchantName: string | null;
  readonly merchantId: string | null;
  readonly merchantCity: string | null;
  readonly merchantState: string | null;
  readonly merchantZip: string | null;
  readonly message: string | null;
}

/**
 * The outcome of the detail screen's forward paging move.
 *
 * Assumptions: exactly one of `authorization` and `message` is populated, discriminated by
 * `endOfData`. That mirrors the reference program's forward step, which either sets its end-of-data
 * condition and the message the screen shows or replaces the current authorization, never both -- so a
 * caller must read `endOfData` before reading either.
 */
export interface NextPendingAuthorization {
  readonly authorization: PendingAuthDetail | null;
  readonly endOfData: boolean;
  readonly message: string | null;
}

/**
 * The fraud transition a reviewer submits.
 *
 * Assumptions: the four identifying members are echoed from the detail the reviewer is looking at,
 * even though the sealed key in the target already identifies the row. The contract requires them
 * because the baseline's own update reads them from the screen, and sending them lets the service
 * refuse a request whose body and target disagree -- which is the case a stale browser tab produces.
 */
export interface FraudMarkRequest {
  readonly accountId: string;
  readonly customerId: string;
  readonly authDateKey: number;
  readonly authTimeKey: number;
  readonly action: FraudAction;
}

/**
 * The outcome of a fraud transition.
 *
 * Assumptions: this shape describes SUCCESS only. A write that failed is answered with a non-2xx
 * status and a problem document, so `updateStatus` never reports a failure and a caller must not read
 * it as one.
 */
export interface FraudMarkResponse {
  readonly updateStatus: string;
  readonly message?: string | null | undefined;
}

/** Criteria the pending-authorization list is read with. */
export interface PendingAuthListQuery {
  readonly accountId: string;
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

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
