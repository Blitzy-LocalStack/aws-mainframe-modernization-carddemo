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
 * rather than as an error to retry. This is not a guard the migration invents. The baseline already
 * asserts it in Db2: `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` declares
 * `FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE
 * RESTRICT` over `TRC_TYPE_CODE CHAR(2)` and `TRC_TYPE_CATEGORY CHAR(4)`, against the parent table
 * `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` whose `TR_TYPE CHAR(2)` is the primary key. The target
 * preserves that `RESTRICT` semantic in PostgreSQL, so referential integrity is still asserted by the
 * datastore and the service translates the violation into a status. Without the translation the
 * operator would read an opaque server error instead of a sentence naming the type still in use.
 *
 * Assumptions: a caller discriminates that refusal with `isConflictFailure` from `./client`, and this
 * module deliberately re-inspects no status code of its own. Every function here propagates the
 * `ApiRequestError` the shared client already normalised, which is the only place the transport status
 * is read. A second comparison against 409 written here would be a second definition of what a
 * conflict is, and the two would answer differently the first time either moved.
 *
 * Trade-offs: a screen must NOT count a type's categories first to predict whether its delete will
 * succeed. Such a pre-flight reads committed state that another session may change before the delete
 * arrives, so it can encourage a delete that then fails and forbid one that would have succeeded. The
 * cost accepted is that the refusal is only known after the attempt; what is bought is that the answer
 * comes from the constraint that actually decides it.
 *
 * Why every replace carries a version
 * ----------------------------------
 * Assumptions: the two replace operations require the `version` the caller last read, and this client
 * makes it a required member rather than an optional one. It is the optimistic-concurrency token, and
 * the baseline already implements that pattern by hand -- `app/cbl/COACTUPC.cbl` snapshots a complete
 * before-image across the pseudo-conversational gap and compares it before rewriting. A request
 * without the version cannot express "change it only if nobody else did", so the service refuses one.
 *
 * Why every code on every signature here is a string
 * -------------------------------------------------
 * Assumptions: the type code, the category code, the area code, the state code and the state-and-ZIP
 * pair are CONSTANT-WIDTH CHARACTER values, not numbers, and every parameter below is typed `string` for
 * that reason rather than by habit. Two of them look numeric and are not. The type code is
 * `PIC X(02)` at `app/cpy/CVTRA03Y.cpy` L5 -- character even in the baseline. The category code is
 * `PIC 9(04)` at `app/cpy/CVTRA04Y.cpy` L7, which is a digits-only PICTURE used as a constant-width key
 * rather than as a quantity, and the extension DDL settles it by declaring the same column
 * `TRC_TYPE_CATEGORY CHAR(4)` in `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` -- a character
 * column, not an integer one. The consequence of typing either as a number is concrete and silent: a
 * number discards leading zeros, so the category code `0001` becomes `1`, the request goes out three
 * characters shorter than the key it names, and the contract's `^[0-9]{4}$` pattern refuses it with 400 --
 * or, worse for a filter, it matches nothing and reads as an empty collection. The same reasoning
 * settles the URL shape: the category code is a path segment carried verbatim, and the edit screen's
 * route parameter is `:cd`, so `/reference/transaction-types/:cd` and the request target below agree
 * on one spelling of the code.
 */

import {
  getApiClient,
  keysetPagingMembers,
  requestPath,
  requireWithinPublishedWidths,
  withoutConcurrentDuplicate,
} from './client';
import type {
  ContractOperation,
  DateEvaluationResult,
  DisclosureGroupRate,
  LookupListQuery,
  MaintenanceActionBatchRequest,
  MaintenanceActionBatchResponse,
  PageResponse,
  PhoneAreaCodeListQuery,
  ReferenceListQuery,
  TransactionCategory,
  TransactionCategoryCreateRequest,
  TransactionCategoryReplaceRequest,
  TransactionType,
  TransactionTypeCreateRequest,
  TransactionTypeReplaceRequest,
  UsPhoneAreaCode,
  UsState,
  UsStateZipPrefix,
} from './types';

/*
 * WHY : Refactoring Rationale: the reference wire shapes are RE-EXPORTED from ./types rather than
 *       declared here, so every consumer's import path is unchanged while each shape has one definition.
 */
export type {
  PhoneAreaCodeClass,
  TransactionType,
  TransactionTypeCreateRequest,
  TransactionTypeReplaceRequest,
  TransactionCategory,
  TransactionCategoryCreateRequest,
  TransactionCategoryReplaceRequest,
  DisclosureGroupRate,
  UsPhoneAreaCode,
  UsState,
  UsStateZipPrefix,
  DateMask,
  DateFeedbackCode,
  DateEvaluationResult,
  MaintenanceActionType,
  MaintenanceAction,
  MaintenanceActionBatchRequest,
  MaintenanceActionOutcomeState,
  MaintenanceActionOutcome,
  MaintenanceActionBatchResponse,
  ReferenceListQuery,
  LookupListQuery,
  PhoneAreaCodeListQuery,
} from './types';

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

/*
 * WHY : Refactoring Rationale: fifteen single-line docstrings stood here, each describing one wire
 *       shape -- the transaction type, the category, the lookup rows, the date verdict, the batch
 *       entry and the three query criteria. Their declarations moved to `./types` so that one shape
 *       has one definition, but the docstrings were left behind and documented nothing: a reader
 *       looking for the shape found a sentence about it with no members beneath, and
 *       `jsdoc/require-description` cannot report a block that describes no declaration. Each
 *       description now lives on the declaration itself in `./types`, where it stays true when a
 *       member changes.
 */

/**
 * Assembles the paging parameters shared by every browse in this module.
 *
 * Assumptions: the direction is sent only alongside a cursor, because the contract declares it
 * meaningful only there and defaults it to next. A direction alone describes a position relative to
 * nothing, and the edge would refuse the request before a handler saw it.
 *
 * Refactoring Rationale: ⚠️ that refusal is now raised HERE, by `keysetPagingMembers`, instead of the
 * direction being dropped. Dropping it made the five browses in this module answer the opening page to
 * a caller that had asked to step from a position it did not hold, which is indistinguishable from a
 * browse that simply has nothing further to show. Because all five funnel through this one function, a
 * single call covers every one of them -- which is the same reason the request itself is issued once
 * below, and the same failure mode it avoids: a detail applied in four places out of five.
 * @param {LookupListQuery} query - The cursor and direction a caller supplied, either or both absent.
 * @returns {Record<string, string>} The paging parameters, empty when the caller supplied no cursor.
 * @throws {RangeError} If a direction is supplied without a usable cursor.
 */
function pagingParameters(query: LookupListQuery): Record<string, string> {
  const params: Record<string, string> = {};
  const paging = keysetPagingMembers(query.cursor, query.direction);
  if (paging !== undefined) {
    params.cursor = paging.cursor;
    params.direction = paging.direction;
  }
  return params;
}

/**
 * Issues one browse and returns its page.
 *
 * Refactoring Rationale: the five browses in this module differ only in their operation and their
 * extra criteria, so the request itself is issued once here rather than five times. Repeating it
 * would repeat the empty-parameter handling five times as well, and that is precisely the kind of
 * detail that ends up applied in four places out of five.
 * @template T The row type the browse returns.
 * @param {ContractOperation} operation - The browse operation to issue.
 * @param {Record<string, string>} params - Every query parameter, paging and criteria together.
 * @returns {Promise<PageResponse<T>>} One bounded page.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails.
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
 * @throws {RangeError} If a direction is supplied with no cursor to step from.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails.
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
 * @throws {RangeError} If a member carries a value longer than the width
 *   `TransactionTypeCreateRequest` publishes for it, in which case nothing is sent.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 409 when the code already exists.
 */
export async function createTransactionType(
  request: TransactionTypeCreateRequest,
): Promise<TransactionType> {
  const response = await getApiClient().post<TransactionType>(
    requestPath(CREATE_TRANSACTION_TYPE),
    requireWithinPublishedWidths('TransactionTypeCreateRequest', request),
  );
  return response.data;
}

/**
 * Retrieves one transaction type.
 * @param {string} typeCd - The two-character type code.
 * @returns {Promise<TransactionType>} The type.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when no such type exists.
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
 * @throws {RangeError} If a member carries a value longer than the width
 *   `TransactionTypeReplaceRequest` publishes for it, in which case nothing is sent.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when no such type exists and 409 when the version supplied is no longer current.
 */
export async function replaceTransactionType(
  typeCd: string,
  request: TransactionTypeReplaceRequest,
): Promise<TransactionType> {
  const response = await getApiClient().put<TransactionType>(
    requestPath(REPLACE_TRANSACTION_TYPE, { typeCd }),
    // Assumptions: the description is bounded at fifty here and at one hundred on a transaction
    //   capture, which is why the guard is keyed by schema rather than by member name -- a table keyed
    //   on `description` alone would have to choose one of the two and would refuse valid input for the
    //   other.
    requireWithinPublishedWidths('TransactionTypeReplaceRequest', request),
  );
  return response.data;
}

/**
 * Deletes one transaction type, unless categories still reference it.
 *
 * Assumptions: 409 is an expected outcome of this call rather than a fault, and a caller distinguishes
 * it with `isConflictFailure` from `./client` instead of comparing a status itself. The refusal comes
 * from the `ON DELETE RESTRICT` foreign key the baseline declares in
 * `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` and the target preserves, so the correct response is
 * to tell the operator the type is still in use -- not to retry, and not to report a server fault. See
 * the module block for why no category count is taken first.
 * @param {string} typeCd - The two-character type code, as a constant-width string.
 * @returns {Promise<void>} Nothing. The operation answers 204 with no body, so there is no
 *   representation of a deleted row to return.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when no such type exists and **409 when categories still reference it**.
 */
export async function deleteTransactionType(typeCd: string): Promise<void> {
  const target = requestPath(DELETE_TRANSACTION_TYPE, { typeCd });
  // Assumptions: guarded on the same terms as the user deletion, and for the same reason -- a repeated
  //   confirmation of one deletion is never two deletions. Here the second attempt would also answer
  //   404, so without the guard an operator who pressed twice would see the row vanish and then be told
  //   it does not exist, which reads as a failure of the deletion that in fact succeeded.
  await withoutConcurrentDuplicate(
    `DELETE ${target}`,
    /**
     * Issues the deletion.
     * @returns {Promise<void>} Nothing; the operation answers 204 with no body.
     */
    async (): Promise<void> => {
      await getApiClient().delete<void>(target);
    },
  );
}

/**
 * Lists transaction categories by key.
 * @param {ReferenceListQuery} [query] - Optional type-code and description filters, plus a sealed
 *   cursor and the direction it was issued for.
 * @returns {Promise<PageResponse<TransactionCategory>>} One bounded page of categories.
 * @throws {RangeError} If a direction is supplied with no cursor to step from.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails.
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
 * @throws {RangeError} If a member carries a value longer than the width
 *   `TransactionCategoryCreateRequest` publishes for it, in which case nothing is sent.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 409 when the pair already exists or the type does not.
 */
export async function createTransactionCategory(
  request: TransactionCategoryCreateRequest,
): Promise<TransactionCategory> {
  const response = await getApiClient().post<TransactionCategory>(
    requestPath(CREATE_TRANSACTION_CATEGORY),
    requireWithinPublishedWidths('TransactionCategoryCreateRequest', request),
  );
  return response.data;
}

/**
 * Retrieves one transaction category.
 * @param {string} typeCd - The two-character type code.
 * @param {string} catCd - The four-character category code.
 * @returns {Promise<TransactionCategory>} The category.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when no such category exists.
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
 * @throws {RangeError} If a member carries a value longer than the width
 *   `TransactionCategoryReplaceRequest` publishes for it, in which case nothing is sent.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when no such category exists and 409 when the version supplied is no longer current.
 */
export async function replaceTransactionCategory(
  typeCd: string,
  catCd: string,
  request: TransactionCategoryReplaceRequest,
): Promise<TransactionCategory> {
  const response = await getApiClient().put<TransactionCategory>(
    requestPath(REPLACE_TRANSACTION_CATEGORY, { typeCd, catCd }),
    requireWithinPublishedWidths('TransactionCategoryReplaceRequest', request),
  );
  return response.data;
}

/**
 * Deletes one transaction category.
 * @param {string} typeCd - The two-character type code.
 * @param {string} catCd - The four-character category code.
 * @returns {Promise<void>} Nothing. The operation answers 204 with no body.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when no such category exists.
 */
export async function deleteTransactionCategory(typeCd: string, catCd: string): Promise<void> {
  const target = requestPath(DELETE_TRANSACTION_CATEGORY, { typeCd, catCd });
  await withoutConcurrentDuplicate(
    `DELETE ${target}`,
    /**
     * Issues the deletion.
     * @returns {Promise<void>} Nothing; the operation answers 204 with no body.
     */
    async (): Promise<void> => {
      await getApiClient().delete<void>(target);
    },
  );
}

/**
 * Reads the interest rate one disclosure group applies to one type and category.
 *
 * Assumptions: `interestRate` arrives as a decimal STRING and is returned untouched, and this function
 * performs no arithmetic on it. The money rule that forbids floating point covers RATES and not only
 * amounts, which is worth stating because a reader may assume a rate is exempt for being small.
 * `app/cpy/CVTRA02Y.cpy` L9 declares `05 DIS-INT-RATE PIC S9(04)V99` -- an exact scaled-decimal value at
 * scale 2 -- which becomes `NUMERIC(6,2)` in PostgreSQL and `BigDecimal` at scale 2 in the service. A
 * JSON number would be parsed into an IEEE-754 double by the browser before any code here saw it, and
 * that conversion is lossy and irreversible: the exactness is gone at the parse, not at the first sum.
 * Transporting it as a string is what keeps the value the service computed identical to the value a
 * screen displays.
 *
 * Assumptions: the rate is an OPERAND of the interest computation and that computation belongs to the
 * service, so no caller multiplies with the value this returns. The baseline computes
 * `( TRAN-CAT-BAL * DIS-INT-RATE) / 1200` in `1300-COMPUTE-INTEREST` at `app/cbl/CBACT04C.cbl` L464
 * to L465, and the target multiplies at full precision before dividing with an explicit scale and
 * rounding mode. Reordering those two steps changes the result by whole cents on ordinary inputs, so a
 * browser reproducing the formula would show a figure that disagrees with the interest actually posted
 * while looking entirely plausible.
 *
 * Assumptions: a response naming `DEFAULT` is a correct 200 and NOT a missing-data condition. The group
 * literally named `DEFAULT` is a real seeded row, and the baseline's interest calculation falls back to
 * it in `1200-GET-INTEREST-RATE` at `app/cbl/CBACT04C.cbl` L415 to L440: a read returning status 23
 * moves the literal `DEFAULT` into the group key at L437 and reads again, so the fallback is a second
 * keyed read of a row that has to exist. The target seeds that row deliberately for the same reason. A
 * client treating a defaulted result as "no rate found" would report an error where the baseline
 * reports a number.
 *
 * Assumptions: the contract already distinguishes a configured rate from a defaulted one, so this
 * client mirrors that distinction rather than inventing one. `requestedAcctGroupId` is the group asked
 * about, `appliedAcctGroupId` the group whose row answered, and `defaultGroupApplied` is true exactly
 * when the fallback supplied the rate -- which is false when the caller asked about `DEFAULT` directly,
 * because that is a configured hit on its own row. A screen wanting to mark a rate as inherited reads
 * that flag; it must not infer the fallback by comparing the two identifiers itself.
 * @param {string} acctGroupId - The account's disclosure-group identifier, up to ten characters.
 * @param {string} tranTypeCd - The two-character transaction type code.
 * @param {string} tranCatCd - The four-character transaction category code, leading zeros retained.
 * @returns {Promise<DisclosureGroupRate>} The rate as an exact decimal string, the group that supplied
 *   it, and whether the `DEFAULT` fallback answered. A defaulted rate is a success, not a miss.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 400 when a code is not the published width, and HTTP 404 when neither the named group nor
 *   the `DEFAULT` group carries a row for the pair.
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
 * @throws {RangeError} If a direction is supplied with no cursor to step from.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails.
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
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when the code is not a seeded one.
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
 * @throws {RangeError} If a direction is supplied with no cursor to step from.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails.
 */
export async function listUsStates(query: LookupListQuery = {}): Promise<PageResponse<UsState>> {
  return browse<UsState>(LIST_US_STATES, pagingParameters(query));
}

/**
 * Retrieves one state code.
 * @param {string} stateCd - The two-letter state code.
 * @returns {Promise<UsState>} The state code.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when the code is not a seeded one.
 */
export async function getUsState(stateCd: string): Promise<UsState> {
  const response = await getApiClient().get<UsState>(requestPath(GET_US_STATE, { stateCd }));
  return response.data;
}

/**
 * Lists state-and-ZIP-prefix pairs by key.
 * @param {LookupListQuery} [query] - Optional sealed cursor and the direction it was issued for.
 * @returns {Promise<PageResponse<UsStateZipPrefix>>} One bounded page of prefix pairs.
 * @throws {RangeError} If a direction is supplied with no cursor to step from.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails.
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
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 404 when the pair is not a seeded one.
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
 * Refactoring Rationale: the baseline has no route a screen could take to this rule at all. It reaches
 * date conversion through an IBM MQ request/reply exchange -- `app/app-vsam-mq/cbl/CODATE01.cbl` reads
 * a request message, edits the date and puts a reply on a reply queue -- so the caller had to be a
 * message producer, which a browser is not. The target replaces that exchange with this synchronous
 * endpoint for interactive callers and keeps an SQS consumer for the message path, so both callers
 * reach one implementation. The consequence is the reason this function exists: a screen can now ask
 * the authoritative validator directly, where previously it could only have guessed locally.
 *
 * Assumptions: the edit rules live SERVER-SIDE and this module performs no date logic whatsoever --
 * no parsing, no arithmetic, no formatting, no pattern test. They are transcribed from
 * `app/cbl/CSUTLDTC.cbl` and its two companion copybooks `app/cpy/CSUTLDPY.cpy` and
 * `app/cpy/CSUTLDWY.cpy`, including leap-year handling and the supported-range and era checks, and they
 * resolve to `com.carddemo.common.validation.DateEditValidator`. A convenience check written here would
 * be a SECOND implementation of a rule that has exactly one authority, and the two would not fail
 * together: a browser regular expression that accepts 29 February in a common year, or rejects a year
 * the baseline's range allows, disagrees with the service silently. Nothing would report the
 * divergence, because both answers are well-formed -- it would surface as a wrong verdict on a real
 * date, which is precisely the class of parity defect the golden masters cannot see from the browser
 * side. Deferring every judgement to this call is what keeps one rule with one answer.
 *
 * Assumptions: a date the validator judges INVALID comes back as a 200 carrying a feedback code, not
 * as an HTTP error, so a caller reads `feedbackCode` rather than catching. Only a malformed REQUEST --
 * a missing date, or one outside the eight-to-ten-character window the parameter declares -- is a 400.
 * The distinction matters because the two look alike from a form's point of view: conflating them
 * would put a transport error in the message band where the baseline shows a specific date complaint.
 *
 * Assumptions: the date travels as a query parameter, which is safe here in a way it would not be for
 * a card number: a calendar date a user typed into a form field is not a secret, and the operation is
 * a pure read that a browser may legitimately cache and repeat.
 *
 * Assumptions: the mask parameter is typed as a plain string and NOT as the `DateMask` union declared
 * in `./types`, even though that union names the two forms the service supports. The contract accepts
 * any string of up to ten characters here, and the one legitimate caller of the wider type is a screen
 * echoing back a mask it received in a previous result -- narrowing the parameter would make that round
 * trip need a cast. Alternatives Considered: writing it as `DateMask | string`, which was the first
 * attempt. Rejected because a union of a literal type with `string` collapses to `string`, so it reads
 * as a constraint while imposing none -- and the lint rule that forbids that redundancy,
 * `@typescript-eslint/no-redundant-type-constituents`, is right to reject it: a reader of the signature
 * would believe the two named forms were enforced, while every other string passed just as well. The
 * plain `string` states the truth, and the two supported forms are named in the parameter's own
 * documentation below, where a statement of intent belongs when the type system cannot carry it.
 * @param {string} date - Eight or ten characters of date text, matching the mask.
 * @param {string} [mask] - The mask to read it against, normally one of the two `DateMask` forms.
 *   Omit it for the hyphenated form the contract defaults to.
 * @returns {Promise<DateEvaluationResult>} The verdict and the structured feedback behind it: a
 *   feedback code, a severity, a message number and the date and mask as evaluated.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 400 when the date is absent or outside the published width. An invalid DATE is not a throw.
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
 * Assumptions: this belongs on a browser client because the contract publishes it as an ordinary
 * synchronous administrative operation, and that reading was checked rather than assumed. The
 * migrated program `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` is a batch reader, which makes
 * "a job, not an endpoint" the natural expectation; `reference-api.yaml` states the opposite outright
 * for this service -- the operation accepts no schedule, returns no job or execution identifier and
 * offers nothing to poll, because the module declares no batch starter and no `spring.batch`
 * configuration and so has no repository to record an execution in. It is published under
 * `admin-reference-data` with the administrative authority alongside the individual create, replace
 * and delete operations. Where the contract and an expectation disagree the contract is authoritative,
 * so the operation is exposed here; `ui/src/api/contracts.test.ts` compares this module's manifest with
 * that document in both directions, so omitting it would fail the build rather than pass quietly.
 *
 * Assumptions: this operation answers 200 even when an entry matched no rows, and the aggregate
 * return code reports that. A caller must read `returnCode` and each entry's `outcome` rather than the
 * HTTP status alone, because the status describes whether the batch RAN and the codes describe what it
 * did -- the same separation the baseline job's condition code expresses.
 *
 * Assumptions: the batch is NOT all-or-nothing, so a caller may not treat a single failed entry as
 * having discarded the others. The baseline continues past a failed statement and keeps the successes
 * -- its failure paragraph moves 4 to `RETURN-CODE` and exits to the read loop rather than rolling
 * back -- and the service reproduces that by attempting each entry between its own savepoint and
 * release. A caller that resubmitted the whole batch after a partial failure would therefore reapply
 * entries that already took effect.
 * @param {MaintenanceActionBatchRequest} request - The entries to apply.
 * @returns {Promise<MaintenanceActionBatchResponse>} One outcome per entry, and the aggregate return
 *   code: 0 when every entry applied, 4 when at least one matched nothing.
 * @throws {RangeError} If any entry carries a value longer than the width
 *   `MaintenanceAction` publishes for it, in which case the whole batch is refused and nothing is sent.
 * @throws {Error} The normalised `ApiRequestError` from `./client` if the request fails, including
 *   HTTP 400 when an entry is malformed.
 */
export async function applyReferenceMaintenanceActions(
  request: MaintenanceActionBatchRequest,
): Promise<MaintenanceActionBatchResponse> {
  // Assumptions: the bound-check is applied per ENTRY and not to the request, because the request
  //   carries nothing but the array -- the values that have widths are one level down, and
  //   `requireWithinPublishedWidths` deliberately does not descend. The loop is here rather than inside
  //   that helper for the reason its own note records: this is the only nested request shape in any of
  //   the seven contracts, so the traversal is visible at the one call site that needs it.
  // Assumptions: the whole batch is refused when any entry is over-long, before anything is sent. The
  //   batch is not all-or-nothing at the SERVICE -- it keeps the entries that applied -- so dispatching
  //   a batch with one unstorable entry would apply the rest and leave the caller to work out which,
  //   whereas refusing locally leaves nothing applied and names the entry that is wrong.
  for (const action of request.actions) {
    requireWithinPublishedWidths('MaintenanceAction', action);
  }
  const response = await getApiClient().post<MaintenanceActionBatchResponse>(
    requestPath(APPLY_REFERENCE_MAINTENANCE_ACTIONS),
    request,
  );
  return response.data;
}
