/**
 * @file Typed client for the reference-data bounded context, written against
 * `services/reference-service/src/main/resources/openapi/reference-api.yaml`.
 *
 * Purpose
 * -------
 * Covers all nineteen operations that contract publishes. They fall into five groups: transaction-type
 * maintenance replacing `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` and `COTRTUPC.cbl`;
 * transaction-category maintenance over the same pair of screens; the disclosure-group rate lookup the
 * interest calculation reads; the three seeded lookup tables that back address validation, drawn from
 * the 490 codes in `app/cpy/CSLKPCDY.cpy`; and two operations with no screen of their own -- the date
 * edit replacing `app/cbl/CSUTLDTC.cbl`, and the batch reference update replacing
 * `app/app-transaction-type-db2/cbl/COBTUPDT.cbl`. Every target is derived from the operation manifest
 * below rather than written as a literal, for the reason recorded in `ui/src/api/types.ts`.
 *
 * Why a delete can fail with a conflict
 * ------------------------------------
 * Assumptions: `deleteTransactionType` may answer 409, and a caller must render that as a refusal
 * rather than as an error to retry. The reference schema carries a foreign key from
 * `transaction_categories.type_cd` with `ON DELETE RESTRICT`, preserving the semantic the baseline's
 * own Db2 constraint asserted, so deleting a type that still has categories is refused by the database.
 * Surfacing it as a status rather than as a 500 is what lets the screen say which type is still in use.
 *
 * Why every replace carries a version
 * ----------------------------------
 * Assumptions: the two replace operations require the `version` the caller last read, and this client
 * makes it a required member rather than an optional one. It is the optimistic-concurrency token, and
 * the baseline already implements that pattern by hand -- `app/cbl/COACTUPC.cbl` snapshots a complete
 * before-image across the pseudo-conversational gap and compares it before rewriting. A request
 * without the version cannot express "change it only if nobody else did", so the service refuses one.
 */

import { getApiClient } from './client';
import { requestPath } from './types';
import type { ContractOperation, PageDirection, PageResponse } from './types';

const LIST_TRANSACTION_TYPES: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/transaction-types',
  operationId: 'listTransactionTypes',
};

const CREATE_TRANSACTION_TYPE: ContractOperation = {
  method: 'POST',
  path: '/api/v1/reference/transaction-types',
  operationId: 'createTransactionType',
};

const GET_TRANSACTION_TYPE: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/transaction-types/{typeCd}',
  operationId: 'getTransactionType',
};

const REPLACE_TRANSACTION_TYPE: ContractOperation = {
  method: 'PUT',
  path: '/api/v1/reference/transaction-types/{typeCd}',
  operationId: 'replaceTransactionType',
};

const DELETE_TRANSACTION_TYPE: ContractOperation = {
  method: 'DELETE',
  path: '/api/v1/reference/transaction-types/{typeCd}',
  operationId: 'deleteTransactionType',
};

const LIST_TRANSACTION_CATEGORIES: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/transaction-categories',
  operationId: 'listTransactionCategories',
};

const CREATE_TRANSACTION_CATEGORY: ContractOperation = {
  method: 'POST',
  path: '/api/v1/reference/transaction-categories',
  operationId: 'createTransactionCategory',
};

const GET_TRANSACTION_CATEGORY: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/transaction-categories/{typeCd}/{catCd}',
  operationId: 'getTransactionCategory',
};

const REPLACE_TRANSACTION_CATEGORY: ContractOperation = {
  method: 'PUT',
  path: '/api/v1/reference/transaction-categories/{typeCd}/{catCd}',
  operationId: 'replaceTransactionCategory',
};

const DELETE_TRANSACTION_CATEGORY: ContractOperation = {
  method: 'DELETE',
  path: '/api/v1/reference/transaction-categories/{typeCd}/{catCd}',
  operationId: 'deleteTransactionCategory',
};

const GET_DISCLOSURE_GROUP_RATE: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/disclosure-groups/{acctGroupId}/{tranTypeCd}/{tranCatCd}',
  operationId: 'getDisclosureGroupRate',
};

const LIST_US_PHONE_AREA_CODES: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/us-phone-area-codes',
  operationId: 'listUsPhoneAreaCodes',
};

const GET_US_PHONE_AREA_CODE: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/us-phone-area-codes/{areaCd}',
  operationId: 'getUsPhoneAreaCode',
};

const LIST_US_STATES: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/us-states',
  operationId: 'listUsStates',
};

const GET_US_STATE: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/us-states/{stateCd}',
  operationId: 'getUsState',
};

const LIST_US_STATE_ZIP_PREFIXES: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/us-state-zip-prefixes',
  operationId: 'listUsStateZipPrefixes',
};

const GET_US_STATE_ZIP_PREFIX: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/us-state-zip-prefixes/{stateZipCd}',
  operationId: 'getUsStateZipPrefix',
};

const EVALUATE_DATE: ContractOperation = {
  method: 'GET',
  path: '/api/v1/reference/date-evaluations',
  operationId: 'evaluateDate',
};

const APPLY_REFERENCE_MAINTENANCE_ACTIONS: ContractOperation = {
  method: 'POST',
  path: '/api/v1/reference/maintenance-actions',
  operationId: 'applyReferenceMaintenanceActions',
};

/**
 * Every operation `reference-api.yaml` declares, in the order the contract declares them.
 *
 * Assumptions: exhaustive rather than a selection, and compared with the contract for equality in
 * both directions by `ui/src/api/contracts.test.ts`.
 */
export const REFERENCE_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  LIST_TRANSACTION_TYPES,
  CREATE_TRANSACTION_TYPE,
  GET_TRANSACTION_TYPE,
  REPLACE_TRANSACTION_TYPE,
  DELETE_TRANSACTION_TYPE,
  LIST_TRANSACTION_CATEGORIES,
  CREATE_TRANSACTION_CATEGORY,
  GET_TRANSACTION_CATEGORY,
  REPLACE_TRANSACTION_CATEGORY,
  DELETE_TRANSACTION_CATEGORY,
  GET_DISCLOSURE_GROUP_RATE,
  LIST_US_PHONE_AREA_CODES,
  GET_US_PHONE_AREA_CODE,
  LIST_US_STATES,
  GET_US_STATE,
  LIST_US_STATE_ZIP_PREFIXES,
  GET_US_STATE_ZIP_PREFIX,
  EVALUATE_DATE,
  APPLY_REFERENCE_MAINTENANCE_ACTIONS,
];

/**
 * Which class of North American area code a row records.
 *
 * Assumptions: two members, 'G' for a geographic code and 'E' for a non-geographic one, transcribed
 * from the two allow-lists in `app/cpy/CSLKPCDY.cpy`. Address validation admits both but treats them
 * differently, which is why the class is published rather than filtered out at the source.
 */
export type PhoneAreaCodeClass = 'G' | 'E';

/** One transaction type, with the concurrency token its replace operation requires. */
export interface TransactionType {
  readonly typeCd: string;
  readonly description: string;
  readonly version: number;
}

/** The fields a transaction-type creation accepts. */
export interface TransactionTypeCreateRequest {
  readonly typeCd: string;
  readonly description: string;
}

/**
 * The fields a transaction-type replace accepts.
 *
 * Assumptions: the code is not among them. It addresses the row from the target, so admitting it in
 * the body as well would create a request whose two halves could disagree about which row changes.
 */
export interface TransactionTypeReplaceRequest {
  readonly description: string;
  readonly version: number;
}

/** One transaction category, keyed by its type and its own code. */
export interface TransactionCategory {
  readonly typeCd: string;
  readonly catCd: string;
  readonly description: string;
  readonly version: number;
}

/** The fields a transaction-category creation accepts. */
export interface TransactionCategoryCreateRequest {
  readonly typeCd: string;
  readonly catCd: string;
  readonly description: string;
}

/** The fields a transaction-category replace accepts. */
export interface TransactionCategoryReplaceRequest {
  readonly description: string;
  readonly version: number;
}

/**
 * One disclosure-group interest rate, and which group actually supplied it.
 *
 * Assumptions: three members describe the fallback rather than one, and that is the point of the
 * shape. `app/cbl/CBACT04C.cbl` L415 to L441 falls back to the group literally named `DEFAULT` when the
 * account's own group has no row, and a response reporting only the rate would leave a caller unable to
 * tell a configured rate from a defaulted one. `requestedAcctGroupId`, `appliedAcctGroupId` and
 * `defaultGroupApplied` make that distinction explicit.
 */
export interface DisclosureGroupRate {
  readonly requestedAcctGroupId: string;
  readonly appliedAcctGroupId: string;
  readonly tranTypeCd: string;
  readonly tranCatCd: string;
  readonly interestRate: string;
  readonly defaultGroupApplied: boolean;
}

/** One North American area code and its class. */
export interface UsPhoneAreaCode {
  readonly areaCd: string;
  readonly codeClass: PhoneAreaCodeClass;
}

/** One two-letter state code. */
export interface UsState {
  readonly stateCd: string;
}

/** One four-character state-and-ZIP-prefix pair. */
export interface UsStateZipPrefix {
  readonly stateZipCd: string;
}

/**
 * Which mask a date is being evaluated against.
 *
 * Assumptions: the two published forms are the hyphenated and the compact one, and the contract
 * accepts any string of up to ten characters for the parameter while defaulting it to the hyphenated
 * form. The union here names the two the service supports, so a screen cannot offer a third by
 * accident, and the parameter type below widens to string for exactly the case where a caller is
 * echoing a mask it received.
 */
export type DateMask = 'YYYY-MM-DD' | 'YYYYMMDD';

/**
 * Which specific defect a date evaluation found, or that it found none.
 *
 * Assumptions: ten members, transcribed from the feedback codes `app/cbl/CSUTLDTC.cbl` and its two
 * companion copybooks distinguish. They are carried across individually rather than collapsed into a
 * boolean because the baseline's reply is a structured triple -- a severity, a message number and
 * message text -- and a per-field error must be able to name which part of a date was wrong.
 */
export type DateFeedbackCode =
  | 'INVALID_DATE'
  | 'INSUFFICIENT_DATA'
  | 'BAD_DATE_VALUE'
  | 'INVALID_ERA'
  | 'UNSUPP_RANGE'
  | 'INVALID_MONTH'
  | 'BAD_PIC_STRING'
  | 'NON_NUMERIC_DATA'
  | 'YEAR_IN_ERA_ZERO'
  | 'OTHER';

/** The verdict on one date, with the structured feedback the baseline's date utility returns. */
export interface DateEvaluationResult {
  readonly feedbackCode: DateFeedbackCode;
  readonly severity: number;
  readonly messageNumber: number;
  readonly verdict: string;
  readonly date: string;
  readonly mask: string;
}

/** Which change one batch maintenance entry requests. */
export type MaintenanceActionType = 'INSERT' | 'UPDATE' | 'DELETE';

/**
 * One entry of a batch reference update.
 *
 * Assumptions: `description` is nullable because a delete entry carries none. Requiring it would make
 * a caller invent a value for a row it is removing, and the service would then have to ignore it.
 */
export interface MaintenanceAction {
  readonly action: MaintenanceActionType;
  readonly typeCd: string;
  readonly description?: string | null | undefined;
}

/** A batch of reference maintenance entries, applied in the order given. */
export interface MaintenanceActionBatchRequest {
  readonly actions: readonly MaintenanceAction[];
}

/** How one batch maintenance entry resolved. */
export type MaintenanceActionOutcomeState = 'APPLIED' | 'NO_ROWS_FOUND' | 'FAILED';

/**
 * The outcome of one batch maintenance entry, positioned so it can be matched to its request entry.
 *
 * Assumptions: `position` is zero-based and is echoed rather than inferred from array order, so a
 * caller can match an outcome to its entry even if a future service answered out of order.
 */
export interface MaintenanceActionOutcome {
  readonly position: number;
  readonly action: MaintenanceActionType;
  readonly typeCd: string;
  readonly outcome: MaintenanceActionOutcomeState;
  readonly applied: boolean;
  readonly message: string;
}

/**
 * The outcome of a whole batch, with the aggregate return code the baseline job would have set.
 *
 * Assumptions: `returnCode` is 0 or 4 and 4 is a WARNING rather than a failure, matching the
 * mainframe condition-code convention the reference batch program uses: a row that matched nothing is
 * a soft outcome, not an error. A caller treating 4 as a failure would report a successful run as
 * broken.
 */
export interface MaintenanceActionBatchResponse {
  readonly outcomes: readonly MaintenanceActionOutcome[];
  readonly returnCode: number;
}

/** Criteria the transaction-type and transaction-category browses may narrow by. */
export interface ReferenceListQuery {
  readonly typeCode?: string | undefined;
  readonly description?: string | undefined;
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

/** Criteria a keyset browse over a seeded lookup table is read with. */
export interface LookupListQuery {
  readonly cursor?: string | undefined;
  readonly direction?: PageDirection | undefined;
}

/** Criteria the area-code browse may narrow by. */
export interface PhoneAreaCodeListQuery extends LookupListQuery {
  readonly codeClass?: PhoneAreaCodeClass | undefined;
}

/**
 * Assembles the paging parameters shared by every browse in this module.
 *
 * Assumptions: the direction is sent only alongside a cursor, because the contract declares it
 * meaningful only there and defaults it to next. A direction alone describes a position relative to
 * nothing, and the edge would refuse the request before a handler saw it.
 * @param {LookupListQuery} query - The cursor and direction a caller supplied, either or both absent.
 * @returns {Record<string, string>} The paging parameters, empty when the caller supplied no cursor.
 */
function pagingParameters(query: LookupListQuery): Record<string, string> {
  const params: Record<string, string> = {};
  if (query.cursor !== undefined) {
    params.cursor = query.cursor;
    params.direction = query.direction ?? 'next';
  }
  return params;
}

/**
 * Issues one browse and returns its page.
 *
 * Refactoring Rationale: the seven browses in this module differ only in their operation and their
 * extra criteria, so the request itself is issued once here rather than seven times. Repeating it
 * would repeat the empty-parameter handling seven times as well, and that is precisely the kind of
 * detail that ends up applied in six places out of seven.
 * @template T The row type the browse returns.
 * @param {ContractOperation} operation - The browse operation to issue.
 * @param {Record<string, string>} params - Every query parameter, paging and criteria together.
 * @returns {Promise<PageResponse<T>>} One bounded page.
 * @throws {Error} If the request fails.
 */
async function browse<T>(
  operation: ContractOperation,
  params: Record<string, string>,
): Promise<PageResponse<T>> {
  const response = await getApiClient().get<PageResponse<T>>(requestPath(operation), {
    params: Object.keys(params).length === 0 ? undefined : params,
  });
  return response.data;
}

/**
 * Assembles the criteria the two reference browses share.
 * @param {ReferenceListQuery} query - The caller's criteria, any or all absent.
 * @returns {Record<string, string>} The query parameters to send.
 */
function referenceParameters(query: ReferenceListQuery): Record<string, string> {
  const params = pagingParameters(query);
  if (query.typeCode !== undefined) {
    params.typeCode = query.typeCode;
  }
  if (query.description !== undefined) {
    params.description = query.description;
  }
  return params;
}

/**
 * Lists transaction types by key.
 * @param {ReferenceListQuery} [query] - Optional code and description filters, plus a sealed cursor and
 *   the direction it was issued for.
 * @returns {Promise<PageResponse<TransactionType>>} One bounded page of transaction types.
 * @throws {Error} If the request fails.
 */
export async function listTransactionTypes(
  query: ReferenceListQuery = {},
): Promise<PageResponse<TransactionType>> {
  return browse<TransactionType>(LIST_TRANSACTION_TYPES, referenceParameters(query));
}

/**
 * Creates one transaction type.
 * @param {TransactionTypeCreateRequest} request - The code and description.
 * @returns {Promise<TransactionType>} The created type, carrying its initial version.
 * @throws {Error} If the request fails, including HTTP 409 when the code already exists.
 */
export async function createTransactionType(
  request: TransactionTypeCreateRequest,
): Promise<TransactionType> {
  const response = await getApiClient().post<TransactionType>(
    requestPath(CREATE_TRANSACTION_TYPE),
    request,
  );
  return response.data;
}

/**
 * Retrieves one transaction type.
 * @param {string} typeCd - The two-character type code.
 * @returns {Promise<TransactionType>} The type.
 * @throws {Error} If the request fails, including HTTP 404 when no such type exists.
 */
export async function getTransactionType(typeCd: string): Promise<TransactionType> {
  const response = await getApiClient().get<TransactionType>(
    requestPath(GET_TRANSACTION_TYPE, { typeCd }),
  );
  return response.data;
}

/**
 * Replaces one transaction type's description, if nobody else has changed it.
 * @param {string} typeCd - The two-character type code.
 * @param {TransactionTypeReplaceRequest} request - The new description and the version last read.
 * @returns {Promise<TransactionType>} The type as stored after the change, with its new version.
 * @throws {Error} If the request fails, including HTTP 404 when no such type exists and 409 when the
 *   version supplied is no longer current.
 */
export async function replaceTransactionType(
  typeCd: string,
  request: TransactionTypeReplaceRequest,
): Promise<TransactionType> {
  const response = await getApiClient().put<TransactionType>(
    requestPath(REPLACE_TRANSACTION_TYPE, { typeCd }),
    request,
  );
  return response.data;
}

/**
 * Deletes one transaction type.
 * @param {string} typeCd - The two-character type code.
 * @returns {Promise<void>} Nothing. The operation answers 204 with no body.
 * @throws {Error} If the request fails, including HTTP 404 when no such type exists and 409 when
 *   categories still reference it.
 */
export async function deleteTransactionType(typeCd: string): Promise<void> {
  await getApiClient().delete<void>(requestPath(DELETE_TRANSACTION_TYPE, { typeCd }));
}

/**
 * Lists transaction categories by key.
 * @param {ReferenceListQuery} [query] - Optional type-code and description filters, plus a sealed
 *   cursor and the direction it was issued for.
 * @returns {Promise<PageResponse<TransactionCategory>>} One bounded page of categories.
 * @throws {Error} If the request fails.
 */
export async function listTransactionCategories(
  query: ReferenceListQuery = {},
): Promise<PageResponse<TransactionCategory>> {
  return browse<TransactionCategory>(LIST_TRANSACTION_CATEGORIES, referenceParameters(query));
}

/**
 * Creates one transaction category beneath an existing type.
 * @param {TransactionCategoryCreateRequest} request - The type code, category code and description.
 * @returns {Promise<TransactionCategory>} The created category, carrying its initial version.
 * @throws {Error} If the request fails, including HTTP 409 when the pair already exists or the type
 *   does not.
 */
export async function createTransactionCategory(
  request: TransactionCategoryCreateRequest,
): Promise<TransactionCategory> {
  const response = await getApiClient().post<TransactionCategory>(
    requestPath(CREATE_TRANSACTION_CATEGORY),
    request,
  );
  return response.data;
}

/**
 * Retrieves one transaction category.
 * @param {string} typeCd - The two-character type code.
 * @param {string} catCd - The four-character category code.
 * @returns {Promise<TransactionCategory>} The category.
 * @throws {Error} If the request fails, including HTTP 404 when no such category exists.
 */
export async function getTransactionCategory(
  typeCd: string,
  catCd: string,
): Promise<TransactionCategory> {
  const response = await getApiClient().get<TransactionCategory>(
    requestPath(GET_TRANSACTION_CATEGORY, { typeCd, catCd }),
  );
  return response.data;
}

/**
 * Replaces one transaction category's description, if nobody else has changed it.
 * @param {string} typeCd - The two-character type code.
 * @param {string} catCd - The four-character category code.
 * @param {TransactionCategoryReplaceRequest} request - The new description and the version last read.
 * @returns {Promise<TransactionCategory>} The category as stored after the change.
 * @throws {Error} If the request fails, including HTTP 404 when no such category exists and 409 when
 *   the version supplied is no longer current.
 */
export async function replaceTransactionCategory(
  typeCd: string,
  catCd: string,
  request: TransactionCategoryReplaceRequest,
): Promise<TransactionCategory> {
  const response = await getApiClient().put<TransactionCategory>(
    requestPath(REPLACE_TRANSACTION_CATEGORY, { typeCd, catCd }),
    request,
  );
  return response.data;
}

/**
 * Deletes one transaction category.
 * @param {string} typeCd - The two-character type code.
 * @param {string} catCd - The four-character category code.
 * @returns {Promise<void>} Nothing. The operation answers 204 with no body.
 * @throws {Error} If the request fails, including HTTP 404 when no such category exists.
 */
export async function deleteTransactionCategory(typeCd: string, catCd: string): Promise<void> {
  await getApiClient().delete<void>(requestPath(DELETE_TRANSACTION_CATEGORY, { typeCd, catCd }));
}

/**
 * Reads the interest rate one disclosure group applies to one type and category.
 * @param {string} acctGroupId - The account's disclosure-group identifier.
 * @param {string} tranTypeCd - The two-character transaction type code.
 * @param {string} tranCatCd - The four-character transaction category code.
 * @returns {Promise<DisclosureGroupRate>} The rate, and which group supplied it.
 * @throws {Error} If the request fails, including HTTP 404 when neither the named group nor the
 *   `DEFAULT` group carries a row for the pair.
 */
export async function getDisclosureGroupRate(
  acctGroupId: string,
  tranTypeCd: string,
  tranCatCd: string,
): Promise<DisclosureGroupRate> {
  const response = await getApiClient().get<DisclosureGroupRate>(
    requestPath(GET_DISCLOSURE_GROUP_RATE, { acctGroupId, tranTypeCd, tranCatCd }),
  );
  return response.data;
}

/**
 * Lists North American area codes by key.
 * @param {PhoneAreaCodeListQuery} [query] - Optional class filter, plus a sealed cursor and the
 *   direction it was issued for.
 * @returns {Promise<PageResponse<UsPhoneAreaCode>>} One bounded page of area codes.
 * @throws {Error} If the request fails.
 */
export async function listUsPhoneAreaCodes(
  query: PhoneAreaCodeListQuery = {},
): Promise<PageResponse<UsPhoneAreaCode>> {
  const params = pagingParameters(query);
  if (query.codeClass !== undefined) {
    params.codeClass = query.codeClass;
  }
  return browse<UsPhoneAreaCode>(LIST_US_PHONE_AREA_CODES, params);
}

/**
 * Retrieves one North American area code.
 * @param {string} areaCd - The three-digit area code.
 * @returns {Promise<UsPhoneAreaCode>} The area code and its class.
 * @throws {Error} If the request fails, including HTTP 404 when the code is not a seeded one.
 */
export async function getUsPhoneAreaCode(areaCd: string): Promise<UsPhoneAreaCode> {
  const response = await getApiClient().get<UsPhoneAreaCode>(
    requestPath(GET_US_PHONE_AREA_CODE, { areaCd }),
  );
  return response.data;
}

/**
 * Lists state codes by key.
 * @param {LookupListQuery} [query] - Optional sealed cursor and the direction it was issued for.
 * @returns {Promise<PageResponse<UsState>>} One bounded page of state codes.
 * @throws {Error} If the request fails.
 */
export async function listUsStates(query: LookupListQuery = {}): Promise<PageResponse<UsState>> {
  return browse<UsState>(LIST_US_STATES, pagingParameters(query));
}

/**
 * Retrieves one state code.
 * @param {string} stateCd - The two-letter state code.
 * @returns {Promise<UsState>} The state code.
 * @throws {Error} If the request fails, including HTTP 404 when the code is not a seeded one.
 */
export async function getUsState(stateCd: string): Promise<UsState> {
  const response = await getApiClient().get<UsState>(requestPath(GET_US_STATE, { stateCd }));
  return response.data;
}

/**
 * Lists state-and-ZIP-prefix pairs by key.
 * @param {LookupListQuery} [query] - Optional sealed cursor and the direction it was issued for.
 * @returns {Promise<PageResponse<UsStateZipPrefix>>} One bounded page of prefix pairs.
 * @throws {Error} If the request fails.
 */
export async function listUsStateZipPrefixes(
  query: LookupListQuery = {},
): Promise<PageResponse<UsStateZipPrefix>> {
  return browse<UsStateZipPrefix>(LIST_US_STATE_ZIP_PREFIXES, pagingParameters(query));
}

/**
 * Retrieves one state-and-ZIP-prefix pair.
 * @param {string} stateZipCd - The four-character pair: two letters of state, two digits of prefix.
 * @returns {Promise<UsStateZipPrefix>} The prefix pair.
 * @throws {Error} If the request fails, including HTTP 404 when the pair is not a seeded one.
 */
export async function getUsStateZipPrefix(stateZipCd: string): Promise<UsStateZipPrefix> {
  const response = await getApiClient().get<UsStateZipPrefix>(
    requestPath(GET_US_STATE_ZIP_PREFIX, { stateZipCd }),
  );
  return response.data;
}

/**
 * Evaluates one date against a mask, reproducing the baseline's date-edit utility.
 *
 * Assumptions: the date travels as a query parameter, which is safe here in a way it would not be for
 * a card number: a calendar date a user typed into a form field is not a secret, and the operation is
 * a pure read that a browser may legitimately cache and repeat.
 * Assumptions: the mask parameter is typed as a plain string and NOT as {@link DateMask}, even though
 * that union names the two forms the service supports. The contract accepts any string of up to ten
 * characters here, and the one legitimate caller of the wider type is a screen echoing back a mask it
 * received in a previous result -- narrowing the parameter would make that round trip need a cast.
 * Writing it as `DateMask | string` was the first attempt and is rejected: a union of a literal type
 * with `string` collapses to `string`, so it reads as a constraint while imposing none, and the lint
 * rule that forbids it is right to.
 * @param {string} date - Eight or ten characters of date text, matching the mask.
 * @param {string} [mask] - The mask to read it against, normally one of the two {@link DateMask} forms.
 *   Omit it for the hyphenated form the contract defaults to.
 * @returns {Promise<DateEvaluationResult>} The verdict and the structured feedback behind it.
 * @throws {Error} If the request fails.
 */
export async function evaluateDate(date: string, mask?: string): Promise<DateEvaluationResult> {
  const params: Record<string, string> = { date };
  if (mask !== undefined) {
    params.mask = mask;
  }

  const response = await getApiClient().get<DateEvaluationResult>(requestPath(EVALUATE_DATE), {
    params,
  });
  return response.data;
}

/**
 * Applies a batch of reference maintenance entries in the order given.
 *
 * Assumptions: this operation answers 200 even when an entry matched no rows, and the aggregate
 * return code reports that. A caller must read `returnCode` and each entry's `outcome` rather than the
 * HTTP status alone, because the status describes whether the batch RAN and the codes describe what it
 * did -- the same separation the baseline job's condition code expresses.
 * @param {MaintenanceActionBatchRequest} request - The entries to apply.
 * @returns {Promise<MaintenanceActionBatchResponse>} One outcome per entry, and the aggregate return
 *   code: 0 when every entry applied, 4 when at least one matched nothing.
 * @throws {Error} If the request fails, including HTTP 400 when an entry is malformed.
 */
export async function applyReferenceMaintenanceActions(
  request: MaintenanceActionBatchRequest,
): Promise<MaintenanceActionBatchResponse> {
  const response = await getApiClient().post<MaintenanceActionBatchResponse>(
    requestPath(APPLY_REFERENCE_MAINTENANCE_ACTIONS),
    request,
  );
  return response.data;
}
