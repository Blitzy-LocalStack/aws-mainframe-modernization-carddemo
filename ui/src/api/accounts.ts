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
 * text, and a JSON number would be parsed into an IEEE-754 double.
 *
 * Refactoring Rationale: NOTHING in this file turns text into a number any more, and the account
 * identifier is why the qualification used to be needed. It travelled as an integer because the
 * published schema declared one, so this module folded the digits at the wire boundary -- discarding the
 * leading zeroes of the declared eleven-character width while the file's own prose said the identifier
 * stayed text. The schema now declares digits-only text, the fold is gone, and the property is
 * unqualified: a reviewer asking whether a value can lose precision here can answer it by finding no
 * numeric conversion at all rather than by judging the one that was excepted.
 */

import {
  getApiClient,
  keysetPagingMembers,
  requestPath,
  requireConditionalOn,
  requireWithinPublishedWidths,
} from './client';
import { MASKED_CARD_NUMBER } from './masking';
import type {
  AccountDetail,
  AccountLookupRequest,
  AccountUpdateRequest,
  AccountUpdateResponse,
  AccountUpdateValidationResponse,
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
  AccountLookupRequest,
  AccountUpdateRequest,
  AccountUpdateResponse,
  AccountUpdateValidationResponse,
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

/*
 * WHY : Assumptions: this operation is declared beside the write it judges, and its address sits
 *       BENEATH the write's, because the two share a request body and a reader has to read them
 *       together. Its own identifier carries no account, so nothing about it needed moving out of the
 *       request line.
 */
const VALIDATE_ACCOUNT_UPDATE: ContractOperation = {
  method: 'POST',
  path: '/api/v1/accounts/update/validate',
  operationId: 'validateAccountUpdate',
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
 * The four END-USER operations `account-api.yaml` publishes, in the order that document declares
 * them.
 *
 * Assumptions: this is a SUBSET of the contract and not the whole of it, which is what distinguishes
 * it from the sibling manifests. `account-api.yaml` declares twelve operations; the eight it tags
 * `internal` are unreachable with a browser token and are absent here by design. A reader adding
 * this manifest to the `BROWSER_CLIENTS` table in `ui/src/api/contracts.test.ts` would therefore
 * fail that gate rather than satisfy it: `theClientMatchesItsContract` compares a manifest with its
 * contract's FULL declared set for equality in both directions, so the correct gate for this module
 * is the internal-surface case that already runs, which asserts no client addresses an internal
 * operation.
 */
export const ACCOUNT_CONTRACT_OPERATIONS: readonly ContractOperation[] = [
  UPDATE_ACCOUNT,
  VALIDATE_ACCOUNT_UPDATE,
  READ_ACCOUNT_VIEW,
  LIST_ACCOUNT_CARD_CROSS_REFERENCES,
];

/**
 * Matches an account identifier at EXACTLY the eleven-digit width the contract admits.
 *
 * ⚠️ Refactoring Rationale: this admitted one to eleven digits and now admits eleven, because the
 * contract admits eleven: `AccountLookupRequest.accountId` is published with `minLength: 11`,
 * `maxLength: 11` and `pattern: '^[0-9]{11}$'`, and every other account-keyed member of this migration
 * spells the field the same way. The looser form let this client pass a short value that the service
 * then refused with a bean-validation error naming a member the operator never saw as short -- a round
 * trip whose only outcome was a refusal, and one that could not carry the reference's own sentence. It
 * also misdescribed the baseline: the receiving field is `PIC 9(11)`, so `1210-EDIT-ACCOUNT` at
 * `app/cbl/COACTUPC.cbl` L1783-L1817 and `2210-EDIT-ACCOUNT` in `app/cbl/COACTVWC.cbl` both fail their
 * `IS NOT NUMERIC` test on the trailing spaces a short entry leaves, so the reference REFUSES a short
 * form rather than resolving it to a padded row.
 *
 * Alternatives Considered: zero-padding a short value here so the previously-admitted entries kept
 * working. Rejected because it would send a value the operator did not type and would make a refusal
 * quote a value they cannot find on their screen; and because it would make this client the only place
 * in the system where the field has two widths. Both screens that reach these operations already require
 * the full width before they call -- `accountView` and `accountUpdate` each hold their own
 * `^[0-9]{11}$` edit -- so nothing reaches here short except a caller that skipped the screen.
 */
const ACCOUNT_ID_DIGITS = /^[0-9]{11}$/u;

/*
 * WHY : ⚠️ Assumptions: the masked-card pattern is IMPORTED from `./masking` and is no longer declared
 *       here. The same regular expression was declared locally in five client modules and then extracted
 *       into one, and the merge left this module holding both -- the import and its own copy. One owner is
 *       transformation rule T2: a shape with two declarations can drift in one of them, and this one is
 *       the boundary check that decides whether a published card rendering is masked at all.
 */

/**
 * Matches the fixed marker the service publishes in place of either protected customer identifier.
 *
 * Assumptions: the marker is a CONSTANT and not a partial mask, which is why this pattern is anchored
 * on a literal rather than shaped like {@link MASKED_CARD_NUMBER}. `CustomerMapper` declares
 * `IDENTIFIER_REDACTED = "[REDACTED]"` and publishes it for both the national identifier and the
 * government-issued one, and its own note records why nothing derived from the stored value can reach a
 * caller through it: the values are enciphered and that class publishes no accessor for the clear
 * form, so a trailing-digits mask of the kind a primary account number gets is not even constructible
 * there. The marker therefore discloses neither a fragment of the value nor its length nor, for the
 * optional government-issued identifier, whether the row has one at all.
 *
 * Refactoring Rationale: the pattern is asserted at the client boundary because the CONTRACT could not
 * assert it. Both properties were published as `type: string` with a `maxLength` alone — 12 and 20, the
 * screen-field widths — and a bare maximum admits a whole formatted national identifier, whose
 * `NNN-NN-NNNN` form is eleven characters and satisfies `maxLength: 12` exactly as the ten-character
 * marker does. So a service, a stub or a proxy that returned the clear value would have satisfied the
 * schema, and this screen would have painted it. The contract now publishes the marker as a `pattern`
 * on all four declarations, and this guard is what makes a violation fail HERE rather than on screen.
 *
 * Assumptions: the width above is stated as a FORM and never as an instance, and the case in
 * `./accounts.test.ts` that drives this guard with a clear value uses the non-issuable sentinel
 * `000-00-0000` rather than a realistic one. The issuing authority has never assigned an area number of
 * 000, never a group number of 00 and never a serial number of 0000, so that value fails three
 * independent allocation rules at once and can belong to nobody, while remaining the same eleven
 * characters the withdrawn maximum admitted. A literal shaped like an ISSUABLE identifier — written
 * here, or as a fixture — reads as a live one to whoever finds it by search, which is the disclosure
 * this guard exists to stop; that is why the reasoning sits beside the pattern rather than only in the
 * test.
 */
const REDACTED_IDENTIFIER = /^\[REDACTED\]$/u;

/** The response header carrying the revision the conditional edit requires back, lower-cased. */
const REVISION_HEADER = 'etag';

/** The request header carrying the revision an edit is conditional on. */
const PRECONDITION_HEADER = 'If-Match';

/*
 * WHY : Refactoring Rationale: the local forward-default constant that stood here was withdrawn when
 *       the cursor-and-direction pair became `keysetPagingMembers` in `./client`. Seven clients each
 *       spelled that default for themselves, which is seven places for one contract fact to be edited
 *       and six chances for the edit to be missed; the guard now applies it once for all of them.
 */

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
 * @param {string} accountId - The identifier as a screen holds it, expected to be exactly eleven
 *   decimal digits.
 * @returns {string} The same value, once established to be well formed.
 * @throws {RangeError} If the value is not exactly eleven characters, or holds a character that is not
 *   a decimal digit.
 */
function requireAccountIdDigits(accountId: string): string {
  if (!ACCOUNT_ID_DIGITS.test(accountId)) {
    // Assumptions: the offending value is described and never reproduced. An account identifier is
    //   one of the values this migration's sensitive-data contract keeps out of a durable
    //   diagnostic, and a thrown message reaches exactly such a store once anything logs it.
    throw new RangeError('An account identifier must be exactly eleven decimal digits.');
  }
  return accountId;
}

/**
 * Builds the sole request body both account-keyed reads take.
 *
 * Refactoring Rationale: the member is carried as TEXT and this function performs no conversion of any
 * kind. It used to fold the digits into a JavaScript number, because the published schema declared the
 * property `type: integer` -- and that conversion contradicted this module's own account of the
 * identifier, discarded the leading zeroes that belong to the declared eleven-character width, and
 * obliged every consumer of the operation to convert, correctly, in a place of its own. The schema now
 * declares digits-only text at that width, matching `AccountUpdateRequest` on the neighbouring route,
 * every `accountId` the sibling contracts declare, and the rule AAP section 0.7.2 states: these
 * identifiers transport as strings validated digits-only, because the reference holds them as characters
 * and reinterprets them as numbers only for arithmetic. What a screen holds is now what the request
 * carries, and the service converts once, when it addresses the stored row.
 *
 * Assumptions: the shape is imported from `./types` rather than written here. This function used to
 * declare its own `{ accountId: number }` return type at the point of use, which was a second definition
 * of one contract shape -- free to drift from the document, and outside the reach of the gate in
 * `ui/src/api/contracts.test.ts` that keeps every wire shape to a single declaration.
 * @param {string} accountId - The account to read, as exactly eleven decimal digits.
 * @returns {AccountLookupRequest} The request body, carrying the identifier exactly as it was supplied.
 * @throws {RangeError} If the identifier is not exactly eleven decimal digits.
 */
function accountLookupBody(accountId: string): AccountLookupRequest {
  return { accountId: requireAccountIdDigits(accountId) };
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
 * Rejects a protected customer identifier that is not the redaction marker.
 *
 * Assumptions: this is a POSITIVE check — the value must MATCH the marker — rather than a search for
 * anything that looks like a national identifier. A negative check has to enumerate the shapes it means
 * to catch, and it would pass every shape it had not thought of: nine bare digits, a differently
 * separated grouping, a government-issued identifier of any format at all. Requiring the one value the
 * service is known to publish inverts that: anything else is refused, whether or not this module can
 * recognise what it is.
 *
 * Assumptions: the check runs on the two members of the account-view read even though the same mapper
 * fills the standalone customer read. The account-view composition is the one a browser token can
 * reach — the contract tags the standalone customer read `internal` — so this is the surface a
 * misconfiguration could actually expose.
 * Assumptions: the parameter admits `undefined` although the contract declares the member required, and
 * the check refuses that case too. A response missing the member entirely is as much a contract
 * violation as one carrying a clear identifier, and it is the shape a stub or a partially-implemented
 * service is most likely to send -- so refusing it here reports the violation as this guard's own
 * `RangeError` naming the member, rather than as a `TypeError` raised several frames away when a screen
 * tries to render it.
 * @param {string | undefined} value - The published identifier exactly as the service sent it, or
 *   `undefined` when the response omitted the member.
 * @param {string} member - The response member being checked, named in the refusal so a failure says
 *   which of the two was at fault.
 * @returns {void} Nothing; the function asserts.
 * @throws {RangeError} If the value is absent, is not text, or is anything other than the marker.
 */
function requireRedactedIdentifier(value: string | undefined, member: string): void {
  if (typeof value !== 'string' || !REDACTED_IDENTIFIER.test(value)) {
    // Assumptions: the offending value is NOT reproduced in the message, for the same reason the card
    //   guard withholds its own. Naming the value here would copy a national identifier into an error
    //   message and from there into whatever records the failure, which is precisely the disclosure
    //   this guard exists to stop -- so the message names the MEMBER and describes the expectation.
    throw new RangeError(
      `The account view's ${member} must be the fixed redaction marker;` +
        ' an unredacted identifier was returned.',
    );
  }
}

/**
 * Validates the two protected identifiers of a composed account view and returns it unchanged.
 *
 * Refactoring Rationale: the response is returned rather than rewritten. Substituting the marker for an
 * offending value was the alternative and is refused: it would let a service that leaked a national
 * identifier keep serving a screen that looked correct, so the leak would persist undetected. Failing
 * the read surfaces the misconfiguration at once, and the screen already renders a rejected read on its
 * error channel without echoing what the rejection carried.
 *
 * ⚠️ Refactoring Rationale: the customer half is now branched on rather than reached through optional
 * chaining, and the difference is the whole of a reported defect. `customer` is `CustomerDetail | null`
 * because the contract declares it `oneOf` a customer and the null type, and the service produces that
 * null on a real success path -- `AccountViewService` answers the account with `customer: null` and the
 * reference's own miss sentence when the account row was located and the customer master holds no
 * matching row, which `app/cbl/COACTVWC.cbl` paints by guarding its two screen regions separately at
 * L471 and L493. Chaining into a `null` yields `undefined`, and `requireRedactedIdentifier` refuses
 * `undefined`, so every one of those successful reads was rejected with a `RangeError` before any screen
 * could render it: the partial-success arm the service and the contract both publish was unreachable
 * through this client. The redaction markers are therefore required on the arm that HAS a customer, and
 * an absent customer is passed through as the state it is.
 *
 * Assumptions: only an explicit `null` takes the absent arm, and an OMITTED member is still refused.
 * The contract lists `customer` among the response's `required` members and declares it `oneOf` a
 * customer and the null type, so nullable and optional are different facts here: a null is a state the
 * service produces, and a missing member is a service that did not answer its own schema -- the shape a
 * stub or a partly-implemented service sends. Admitting the omission would let it reach the screen as the
 * legitimate partial arm, which renders identically, so the fault would be invisible from either side.
 *
 * Assumptions: on the non-null arm the members are reached through a `Partial` view and an ABSENT member
 * is refused, which is the property the branch must not lose. A customer that arrived without its masked
 * identifiers is as much a contract violation as one carrying clear ones; refusing it here names the
 * member instead of failing several frames away in a screen.
 * @param {AccountViewResponse} view - The composed read exactly as the service sent it.
 * @returns {AccountViewResponse} The same response, once both identifiers are established to be
 *   redacted, or once the response is established to carry an explicit null customer.
 * @throws {RangeError} If the response omits the customer member, or carries a customer whose national
 *   or government-issued identifier is absent or is anything other than the redaction marker.
 */
function validateAccountView(view: AccountViewResponse): AccountViewResponse {
  if (view.customer === null) {
    return view;
  }

  // Assumptions: the members are reached through a Partial view although the contract declares them
  //   required, so a customer that omitted one -- or a response that omitted the customer itself, which
  //   reaches here as `undefined` -- is refused by the guard rather than raising a TypeError here. The
  //   declared type says the member is present; a response is not obliged to agree.
  const customer: Partial<CustomerDetail> | undefined = view.customer;
  requireRedactedIdentifier(customer?.ssnMasked, 'ssnMasked');
  requireRedactedIdentifier(customer?.governmentIssuedIdMasked, 'governmentIssuedIdMasked');
  return view;
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
 * @param {string} accountId - The account to read, as exactly eleven decimal digits, being the value
 *   the user typed into the screen's filter field.
 * @returns {Promise<{ account: AccountViewResponse; revision: string | null }>} Resolves with the
 *   account and its customer as the screen renders them, the two message channels alongside them,
 *   and the revision to submit an edit under -- `null` when the response carried no entity tag, in
 *   which case the account may be displayed but not edited from this read.
 * @throws {RangeError} If the identifier is not exactly eleven decimal digits, or if the response's
 *   national or government-issued identifier is not the fixed redaction marker -- see
 *   {@link requireRedactedIdentifier} for why that is checked here rather than trusted from the schema.
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

  return {
    account: validateAccountView(response.data),
    revision: revisionFrom(response.headers),
  };
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
 * @throws {RangeError} If a member carries a value longer than the width
 *   `AccountUpdateRequest` publishes for it, in which case nothing is sent.
 * @throws {RangeError} If the supplied revision is empty. ⚠️ Note what this is NOT: it is not a stand-in
 *   for the service's refusal of an ABSENT `If-Match`, which is a 400 this function cannot reach because
 *   it always sends the header. A blank value is sent as a blank header, and the service treats a blank
 *   precondition as a FAILED one — `requireCurrentRevision` in
 *   `services/account-service/src/main/java/com/carddemo/account/service/AccountUpdateService.java`
 *   tests `expectedRevision.isBlank()` first and raises the same stale-version conflict — so the answer
 *   would be 409 with the changed-record sentence, reporting a concurrent edit that never happened.
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
  {
    // Trade-offs: refused here rather than sent as an empty header, and the two ways a precondition can
    //   be missing have DIFFERENT answers, which is what makes the local refusal worth its one branch.
    //   An absent `If-Match` is refused by the framework before any account code runs, because
    //   `AccountController.update` declares it as a required `@RequestHeader`, and that answer is a 400
    //   naming the header -- self-explanatory, and unreachable from here since this function always sends
    //   the header. A PRESENT but blank one reaches the business rule instead, where
    //   `requireCurrentRevision` tests `expectedRevision.isBlank()` before comparing anything and raises
    //   the same stale-version conflict a genuinely outdated token raises. So the answer to a blank
    //   revision is a FALSE 409: the screen would show `Record changed by some one else. Please review`
    //   and offer a re-read for an edit nobody else touched, sending the operator to look for a
    //   concurrent change that does not exist. Failing locally names the real defect -- a caller that
    //   lost the revision its read returned -- and the cost is one branch that anticipates a rule the
    //   service still enforces on its own.
    //
    // Refactoring Rationale: the branch itself moved to `requireConditionalOn`, which the card edit's
    //   version check now shares. The two operations were answering one question -- is this edit
    //   conditional on the revision it was read at -- in two hand-written forms with two messages, and
    //   an operation acquiring optimistic concurrency next would have written a third. What could NOT
    //   move is the wire form: this one is a header against an opaque entity tag because
    //   `account-api.yaml` declares `If-Match` required, and that is reported as a service-side
    //   divergence rather than papered over with a facade here.
    requireConditionalOn(revision, 'An account edit');
  }

  const response = await getApiClient().post<AccountUpdateResponse>(
    requestPath(UPDATE_ACCOUNT),
    // Assumptions: forty-three members are bound-checked here, which is the widest request this
    //   package sends and the one where a form attribute is least likely to be the only guard the value
    //   passed -- a restored draft, a paste into a grouped field, or a screen that composes a date from
    //   three parts all reach this body without passing through the input that bounds it.
    requireWithinPublishedWidths('AccountUpdateRequest', request),
    { headers: { [PRECONDITION_HEADER]: revision } },
  );

  return { account: response.data, revision: revisionFrom(response.headers) };
}

/**
 * Judges a submitted account edit without writing anything, which is the screen's first turn.
 *
 * Assumptions: NO revision is sent, unlike {@link updateAccount}. A verdict changes nothing, so there
 * is no state a precondition would protect, and requiring one would make the first turn depend on a
 * value it has no use for.
 *
 * Assumptions: a refused value resolves rather than rejects. The operation answers with HTTP 200
 * carrying the verdict, so a caller reads `inputError` and `noChangesFound`; only a transport or key
 * failure rejects. Treating a refusal as a rejection here would make an ordinary expected outcome
 * indistinguishable from a broken request.
 * @param {SensitiveAccountUpdateRequest} request - The submission to judge, exactly as the write would
 *   receive it.
 * @returns {Promise<AccountUpdateValidationResponse>} The verdict those edits reached.
 * @throws {RangeError} If a member carries a value longer than the width
 *   `AccountUpdateRequest` publishes for it, in which case nothing is sent.
 */
export async function validateAccountUpdate(
  request: SensitiveAccountUpdateRequest,
): Promise<AccountUpdateValidationResponse> {
  const response = await getApiClient().post<AccountUpdateValidationResponse>(
    requestPath(VALIDATE_ACCOUNT_UPDATE),
    // Assumptions: the verdict turn is guarded with the SAME schema as the write, because it receives
    //   the same body -- so an over-long value is refused on the first turn rather than surviving the
    //   preview and failing on the write, which would report it against a different action.
    requireWithinPublishedWidths('AccountUpdateRequest', request),
  );

  return response.data;
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
 * @param {string} accountId - The account whose rows are wanted, as exactly eleven decimal digits.
 * @param {string} [cursor] - The opaque boundary a previous page of THIS account's walk issued,
 *   resumed strictly beyond it: `lastKey` when reading forward and `firstKey` when reading backward.
 *   It is replayed verbatim and is never parsed, compared or incremented -- the service seals it
 *   against the query, the caller, the account and the direction together, so it carries meaning only
 *   to the service that minted it. Omit it for the opening page.
 * @param {PageDirection} [direction] - Which way to step from that cursor. Meaningful only alongside
 *   one, and defaulted to forward when a cursor is supplied without it; a direction alone describes a
 *   position relative to nothing, so supplying one without a cursor — or with a blank one — is refused
 *   rather than sent.
 * @returns {Promise<CardXrefPage>} Resolves with at most seven rows in ascending card-number order,
 *   each carrying a card number masked to its last four digits, together with both sealed boundaries
 *   and the forward-availability indicator the caller pages on.
 * @throws {RangeError} If the identifier is not exactly eleven decimal digits, if a direction is
 *   supplied without a usable cursor, or if any row arrives with a card number that is not masked.
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
  // Assumptions: the blank cursor this module used to normalise here is normalised inside
  //   `keysetPagingMembers` instead, so this module and the shared helper cannot come to disagree
  //   about what "present" means. A blank cursor is no cursor on the same grounds the service reads it
  //   that way -- as a subset of absent rather than as a third state -- so a direction accompanied by
  //   a blank cursor is a direction with no cursor and is refused as one.
  const query: Record<string, string> = {};

  // Refactoring Rationale: ⚠️ the pair is established by the shared guard, which REFUSES a direction
  //   supplied without a usable cursor instead of dropping it. Dropping it -- what this block did --
  //   turned "step backward from here" into "read the opening page" for a caller that had asked for
  //   something the contract answers with a 400 keyed on the direction. The blank-cursor reading is
  //   unchanged and now lives in the guard: a blank cursor is no cursor, on the same grounds the
  //   service reads it that way, so a direction sent with one is refused rather than silently ignored.
  const paging = keysetPagingMembers(cursor, direction);
  if (paging !== undefined) {
    query.cursor = paging.cursor;
    query.direction = paging.direction;
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
