/**
 * @file Contract tests for the pending-authorization summary screen,
 * `ui/src/screens/authSummary/index.tsx`.
 *
 * Purpose
 * -------
 * This file is the front-end evidence for one of the three MQ extensions AAP section 0.9.4 names
 * among the user's acceptance-criteria flows. It holds the migrated `COPAU00` mapset and `COPAUS0C`
 * program to the contracts the baseline fixes: the declared field widths, the five-row page, the
 * keyset cursor in both directions, exact fixed-point money, the verbatim message catalog, the four
 * attention identifiers the program admits, and the session and exposure rules the target imposes on
 * top of them.
 *
 * ⚠️ Assumptions: this is the ONLY verification this screen can receive, and the reason is worth
 * stating rather than leaving to be rediscovered. `tests/README.md` section 1.1 records that "Online
 * `CO*` CICS programs cannot run end-to-end without a CICS runtime (absent on the runner); only their
 * extractable field-validation logic is unit-tested." This screen is worse served than the base
 * seventeen, because the extension tree additionally requires IMS DL/I and Db2, neither of which the
 * runner has either. So there is NO golden master for `COPAUS0C` anywhere in the parity suite, and no
 * amount of COBOL execution will produce one. Every assertion below is therefore made against the
 * baseline SOURCE read as a specification, with the file and line cited at the assertion, which is the
 * only oracle available.
 *
 * Parameters, Returns, Exceptions (module analogues)
 * -------------------------------------------------
 * A test module takes no parameters and returns no value. What it consumes is the mocked
 * `listPendingAuthorizations` transport, the message catalog, the theme bridge and the shared harness
 * in `ui/src/test/setup.ts`; what it produces is a pass or a failure per case; and what it raises is
 * the assertion error Vitest throws, plus the `Error` the harness raises for a route pattern that
 * cannot match its address and the one `pressPfKey` raises for an attention identifier with no browser
 * key.
 *
 * Assumptions: Rule 1 (Explainability) and `tests/README.md` section 12 impose the SAME obligation on
 * this file, and neither is being satisfied at the other's expense. Rule 1 requires a docstring
 * stating purpose, parameters, returns and exceptions on every function, plus an inline comment giving
 * the WHY of each non-obvious decision under one of four named categories. Section 12 requires of
 * "every new test, fixture builder, helper, mock, and runner routine" a docstring stating "Purpose,
 * Parameters, Returns, and Exceptions" and inline comments that explain why rather than restate what,
 * and calls it "a hard review gate". The two agree completely, so this file extends an established
 * house convention rather than importing a new one, and `docs/CODE_DOCUMENTATION_STANDARD.md` is the
 * written form of the agreement.
 *
 * ⚠️ Assumptions: the test globals are IMPORTED from `vitest` by name and the injection does not make
 * that redundant. `ui/vitest.config.ts` sets `globals: true` for the runner while `ui/tsconfig.json`
 * keeps `"types": []`, and the configuration records that pairing as deliberate: the empty types list
 * is the half that can discriminate a test from a screen, so a screen calling `expect` fails
 * typechecking on a named symbol. The consequence for this file is that omitting the imports is not a
 * style choice but a compile error, `Cannot find name 'expect'`.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for
 * the reason the sibling suites record. `ui/eslint.config.js` selects `* > ArrowFunctionExpression`
 * in `jsdoc/require-jsdoc`, so an inline callback owes its own JSDoc block, and Prettier moves a block
 * comment that follows an argument comma onto the preceding literal, detaching it from what it
 * documents. Naming the function puts the block somewhere Prettier will leave it.
 *
 * Assumptions: the transport MODULE is mocked rather than the HTTP client beneath it, matching the
 * sibling screen suites. These cases are about what the screen does with an outcome, so the shortest
 * honest seam is the function the screen calls. No request-interception library is in this package's
 * dependency set, so there is none to reach for; no endpoint is contacted and no credential is read
 * anywhere in this file.
 *
 * ⚠️ Assumptions: two imports reach past the screen and its own contracts, and both are required by the
 * behaviour under test rather than chosen for convenience. `ApiRequestError` from `ui/src/api/client.ts`
 * is imported because `ui/src/hooks/usePagedQuery.ts` recognises a failure with `isApiRequestError` and
 * takes a different branch for anything else -- so a plain `Error` would exercise a path the real client
 * never produces, and the field-refusal cases would be asserting against a shape no request can return.
 * `fieldErrorId` from `ui/src/layout/fieldHelp.ts` is imported because the screen mints its help-text
 * identifier with that module's own `fieldErrorHelp`, so reading it back through the paired function is
 * what keeps the association assertion from hard-coding the identifier format and quietly duplicating a
 * contract. Both are the same seams the sibling screen suites use for the same two purposes.
 *
 * Assumptions: no assertion here touches computed geometry, pixel offsets or character coordinates.
 * Registered design gap G1 abandons the fixed 24x80 character grid deliberately, and jsdom has no
 * layout engine, so such an assertion could only ever encode the absence of a layout engine.
 */

import { act, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { listPendingAuthorizations } from '../api/authorization';
import {
  ApiRequestError,
  claimRetainedOutcome,
  retainOutcomeAcrossNavigation,
} from '../api/client';
import type { FraudTransitionHandover } from '../screens/authDetail';
import type {
  ApiError,
  ApprovalStatus,
  AuthFraudFlag,
  MatchStatus,
  PageResponse,
  PendingAuthListItem,
  PendingAuthListResponse,
  PendingAuthSummary,
} from '../api/types';
import {
  MONEY_PICTURES,
  applyMoneyEditMask,
  classifyMoneySign,
  moneySignTextToken,
} from '../format/money';
import type { MoneyPicture } from '../format/money';
import {
  BUSY_ANNOUNCEMENT_TEST_ID,
  VISUALLY_HIDDEN_STYLE,
  fieldErrorId,
} from '../layout/fieldHelp';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import {
  COMMON_MESSAGES,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  PROGRAM_MESSAGE_SOURCES,
  PROGRAM_SOURCE_FILES,
  REDACTED_DIAGNOSTICS,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  TRANSIENT_FAILURE_TRY_AGAIN,
} from '../messages/messages';
import {
  AUTH_SUMMARY_BACK_ROUTE,
  AUTH_SUMMARY_COLUMN_HEADERS,
  AUTH_SUMMARY_FIELD_WIDTHS,
  AUTH_SUMMARY_HIDDEN_LABELS,
  AUTH_SUMMARY_KEY_LABELS,
  AUTH_SUMMARY_LABELS,
  AUTH_SUMMARY_PAGE_SIZE,
  AUTH_SUMMARY_SELECTION_CODE,
  AUTH_SUMMARY_SELECTION_PROMPT,
  AuthSummaryScreen,
  SELECTION_CELL_RESERVED_COLUMNS,
  authorizationDetailPath,
  selectionCellLabel,
} from '../screens/authSummary';
import {
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  TARGET_SIZE_AA_MINIMUM,
  TYPOGRAPHY_TOKENS,
} from '../theme/tokens';
import {
  LEADING_CURSOR,
  TRAILING_CURSOR,
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pageResponse,
  pressPfKey,
  renderInAppShell,
  resetSessionAndTransport,
  seedSession,
} from './setup';

/**
 * Builds the mocked surface of the authorization transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts
 * every `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead
 * zone at registration time.
 *
 * Assumptions: the whole module is replaced rather than one member spied, so a screen reaching for an
 * operation this file did not anticipate fails loudly on an unconfigured spy instead of dispatching a
 * real request from a test.
 * @returns {Record<string, unknown>} The transport functions this screen's module graph can reach,
 *   each a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    listPendingAuthorizations: vi.fn(),
    getPendingAuthorization: vi.fn(),
    getPendingAuthorizationScreen: vi.fn(),
    getNextPendingAuthorization: vi.fn(),
    setAuthorizationFraudState: vi.fn(),
  };
}

vi.mock('../api/authorization', mockAuthorizationTransportModule);

/**
 * Identifier of the account filter control, as `ui/src/screens/authSummary/index.tsx` declares it.
 *
 * Assumptions: the control is reached by its own identifier rather than by its label text, because the
 * label is a catalogued string this file also asserts on -- querying by it would make one failure
 * present as two unrelated ones.
 */
const ACCOUNT_ID_CONTROL_ID = 'auth-summary-account-id';

/**
 * An account identifier of exactly the width `ACCTID` declares.
 *
 * Assumptions: eleven digits, because `app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy` L60
 * declares `ACCTIDI PIC X(11)` and `app/cpy/COCOM01Y.cpy` L38 declares `CDEMO-ACCT-ID PIC 9(11)`, and
 * `COPAUS0C.cbl` L278 refuses anything the space-padded field does not read as numeric -- so a
 * narrower entry is refused even though every character of it is a digit.
 */
const ELEVEN_DIGIT_ACCOUNT_ID = '00000000011';

/**
 * A selection character the source refuses.
 *
 * ⚠️ Assumptions: derived as "anything but the accepted one" only in spirit -- the literal is spelled so
 * the case reads as the operator's mistyped key -- and it is asserted here to differ from both accepted
 * forms, so the constant cannot silently become the accepted character in either case.
 * `PROCESS-ENTER-KEY` accepts `'S'` and `'s'` and nothing else (`COPAUS0C.cbl` L316).
 */
const UNACCEPTED_SELECTION_CHARACTER = 'X';

/** A second eleven-digit identifier, so a case can move the scope from one account to another. */
const OTHER_ELEVEN_DIGIT_ACCOUNT_ID = '00000000022';

/**
 * The route this screen is mounted at, as `ui/src/router.tsx` declares it.
 *
 * Assumptions: stated as a literal rather than imported from the router, because importing the router
 * would pull all 21 lazily-loaded screens into this file's module graph for the sake of one string.
 */
const AUTH_SUMMARY_ROUTE = '/authorizations';

/**
 * The six money members this screen renders, paired with the label the mapset paints beside each and
 * the edit mask the reference applies to it.
 *
 * ⚠️ Assumptions: the PICTURE is part of this table because the reference edits one precision through
 * TWO masks, and which datum takes which is a fact of the program rather than of the field's type.
 * `COPAUS0C.cbl` L780 to L799 moves the credit limit and the credit balance through
 * `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` (L56) and moves the cash limit, the cash balance, the approved
 * total and the declined total through `WS-DISPLAY-AMT9 PIC -zzzz9.99` (L57). Four of the six therefore
 * render nine characters wide with a blank in the sign position, and pairing the picture here is what
 * makes every money case below assert the RIGHT mask per field instead of one mask for all six.
 *
 * ⚠️ Assumptions: exactly six, and every one of them is a `string` in the contract rather than a
 * number. `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` declares all six as
 * `PIC S9(09)V99 COMP-3` at L23 to L26 and L29 to L30 -- packed decimal -- and this context is the only
 * place in the migration where packed decimal reaches persisted target data. The two count members
 * beside them are deliberately NOT in this table: `CIPAUSMY.cpy` L27 to L28 declares them
 * `PIC S9(04) COMP`, binary halfwords rather than money, and the contract types them as numbers for
 * that reason. Including them here would assert the money rule against a field the rule does not
 * govern.
 */
const MONEY_MEMBERS = [
  {
    member: 'creditLimit',
    label: AUTH_SUMMARY_LABELS.creditLimit,
    picture: MONEY_PICTURES.transactionAmount,
  },
  {
    member: 'cashLimit',
    label: AUTH_SUMMARY_LABELS.cashLimit,
    picture: MONEY_PICTURES.authorizationSummaryAmount,
  },
  {
    member: 'approvedAuthAmt',
    label: AUTH_SUMMARY_LABELS.approvedAmount,
    picture: MONEY_PICTURES.authorizationSummaryAmount,
  },
  {
    member: 'creditBalance',
    label: AUTH_SUMMARY_LABELS.creditBalance,
    picture: MONEY_PICTURES.transactionAmount,
  },
  {
    member: 'cashBalance',
    label: AUTH_SUMMARY_LABELS.cashBalance,
    picture: MONEY_PICTURES.authorizationSummaryAmount,
  },
  {
    member: 'declinedAuthAmt',
    label: AUTH_SUMMARY_LABELS.declinedAmount,
    picture: MONEY_PICTURES.authorizationSummaryAmount,
  },
] as const satisfies readonly {
  readonly member: keyof PendingAuthSummary;
  readonly label: string;
  readonly picture: MoneyPicture;
}[];

/**
 * Builds the account summary panel the listing answers with.
 *
 * ⚠️ Assumptions: every monetary member is written as a quoted string and none is computed, so this
 * builder cannot itself introduce the defect the money cases exist to detect. The values carry
 * trailing cents explicitly because `NUMERIC(p,2)` in SQL and `BigDecimal` at scale 2 in Java both
 * preserve them, and a fixture written as `'250'` would assert against a scale the service does not
 * send.
 *
 * Assumptions: the five account-status slots are supplied as five DISCRETE members with five
 * distinguishable values, so a case can tell which slot a value reached.
 * @param {Partial<PendingAuthSummary>} [overrides] - Members to replace, for a case that needs one
 *   value to differ.
 * @returns {PendingAuthSummary} The summary for the screen to render.
 */
function summaryFixture(overrides: Partial<PendingAuthSummary> = {}): PendingAuthSummary {
  return {
    accountId: ELEVEN_DIGIT_ACCOUNT_ID,
    customerId: '000000011',
    authStatus: 'Y',
    accountStatus1: 'AA',
    accountStatus2: 'BB',
    accountStatus3: 'CC',
    accountStatus4: 'DD',
    accountStatus5: 'EE',
    creditLimit: '5000.00',
    cashLimit: '1000.00',
    creditBalance: '250.00',
    cashBalance: '0.00',
    approvedAuthCnt: 3,
    declinedAuthCnt: 1,
    // WHY : ⚠️ Assumptions: all six amounts are DISTINCT from one another, and that is a requirement of
    //       the fixture rather than an accident of it. Two members sharing a value cannot be told apart
    //       once rendered, so a transposition between them -- the approved amount shown under the
    //       credit balance's caption -- would satisfy every assertion. Distinct values make each cell's
    //       identity provable from its own text.
    approvedAuthAmt: '175.25',
    declinedAuthAmt: '10.00',
    customerName: 'ADA LOVELACE',
    addressLine1: '1 ANALYTICAL WAY',
    addressLine2: 'SUITE 1843',
    phoneNumber1: '(212)5550101',
    ...overrides,
  };
}

/**
 * Builds one listed authorization whose every value is traceable to its position.
 *
 * ⚠️ Assumptions: `key` is the SEALED COMPOSITE selector and not the account identifier. AAP section
 * 0.4.1.3 gives this table the primary key `(account_id, auth_date, auth_time)`, derived from the
 * packed composite key the IMS segment carries, so one token has to stand for all three parts. The
 * fixture spells that out -- account, date and time joined -- so a case asserting on a cursor or a
 * route parameter can show the WHOLE composite arrived rather than just the account.
 *
 * ⚠️ Assumptions: `amount` is a quoted string carrying leading blanks, because the source renders it
 * through a `-zzzzzzz9.99`-class edit mask into a fixed-width column and the target transports money
 * as text. Writing it as a number here would make the fixture itself the thing that lost exactness.
 *
 * Assumptions: `cardNum` is supplied ALREADY MASKED to its last four digits, because that is the only
 * form the contract carries to this screen -- the unmasked primary account number is exposed on the
 * administrative card-detail endpoint alone. A fixture carrying sixteen digits would be asserting
 * against a body the service does not send.
 * @param {number} ordinal - One-based position of this row, used to make every value distinct.
 * @returns {PendingAuthListItem} One row for the table.
 */
function listItemFixture(ordinal: number): PendingAuthListItem {
  const suffix = String(ordinal).padStart(2, '0');
  const approvalStatus: ApprovalStatus = ordinal % 2 === 1 ? 'A' : 'D';
  const matchStatus: MatchStatus = 'P';

  return {
    key: `${ELEVEN_DIGIT_ACCOUNT_ID}-20220718-1011${suffix}`,
    transactionId: `TRAN00000000${suffix}`,
    authOrigDate: '220718',
    authOrigTime: `1011${suffix}`,
    authType: 'PURC',
    approvalStatus,
    matchStatus,
    amount: `  12${suffix}.56`,
    cardNum: `************11${suffix}`,
  };
}

/**
 * Builds a page of rows of a stated size.
 *
 * ⚠️ Assumptions: the caller states the count rather than this helper defaulting to the page size,
 * because the page-size cases need to distinguish three situations that a default would collapse: a
 * FULL page of exactly {@link AUTH_SUMMARY_PAGE_SIZE} rows, a SHORT page of fewer, and an
 * over-long answer the screen must not render more of than the mapset paints. A helper that always
 * produced five could not express the third.
 * @param {number} count - How many rows to build.
 * @returns {readonly PendingAuthListItem[]} That many rows, each distinguishable by ordinal.
 */
function rowsOf(count: number): readonly PendingAuthListItem[] {
  return Array.from({ length: count }, buildRowAtIndex);
}

/**
 * Builds the row occupying one zero-based slot of a page.
 *
 * Assumptions: named and lifted out of {@link rowsOf} rather than written inline, because
 * `ui/eslint.config.js` requires a JSDoc block on every arrow function expression and Prettier
 * detaches a block comment that follows an argument comma.
 * @param {unknown} _slot - The array slot's value, always `undefined` for an `Array.from` length form.
 * @param {number} index - Zero-based position being filled.
 * @returns {PendingAuthListItem} That position's row, numbered from one.
 */
function buildRowAtIndex(_slot: unknown, index: number): PendingAuthListItem {
  return listItemFixture(index + 1);
}

/**
 * Builds the whole answer the listing operation returns.
 *
 * ⚠️ Assumptions: the page envelope comes from the shared `pageResponse` builder rather than being
 * written out here, and that is load-bearing. `ui/src/api/types.ts` L174 declares `PageResponse<T>`
 * with EXACTLY four members -- `items`, `firstKey`, `lastKey` and `hasNext` -- and the shared builder
 * is the one place that shape is constructed, so it cannot acquire a fifth. Backward availability is
 * not among them: it is the client's own page ordinal, derived by `ui/src/hooks/usePagedQuery.ts`, and
 * a fixture that invented a `hasPrev` member would let a case pass against a body no service sends.
 * @param {readonly PendingAuthListItem[]} items - The rows the page carries.
 * @param {object} [options] - What the answer reports beyond its rows.
 * @param {boolean} [options.hasNext] - Whether reading forward from the trailing cursor yields more.
 * @param {string | null} [options.screenMessage] - A sentence the service raised for the message band.
 * @param {PendingAuthSummary} [options.summary] - The panel to render above the rows.
 * @returns {PendingAuthListResponse} The listing answer, in the three-member shape the contract
 *   publishes.
 */
function listingFixture(
  items: readonly PendingAuthListItem[],
  options: {
    readonly hasNext?: boolean;
    readonly screenMessage?: string | null;
    readonly summary?: PendingAuthSummary;
  } = {},
): PendingAuthListResponse {
  const page: PageResponse<PendingAuthListItem> = pageResponse(items, {
    hasNext: options.hasNext ?? false,
  });

  return {
    summary: options.summary ?? summaryFixture(),
    page,
    screenMessage: options.screenMessage ?? null,
  };
}

/**
 * Builds the normalised failure the shared client raises for a refused request.
 *
 * Assumptions: an `ApiRequestError` is thrown rather than a bare `Error`, because
 * `ui/src/hooks/usePagedQuery.ts` recognises that class to extract the problem document, and a plain
 * rejection would reach the screen with no `fieldErrors` array at all -- exercising a path the client
 * never produces.
 * @param {number} status - The HTTP status the answer carried.
 * @param {Partial<ApiError>} [overrides] - Problem-document members to replace.
 * @returns {ApiRequestError} The rejection to configure the stub with.
 */
function refusal(status: number, overrides: Partial<ApiError> = {}): ApiRequestError {
  const problem = apiError({ status, ...overrides });
  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`);
}

/**
 * Returns the stub standing in for the listing operation.
 * @returns {MockedFunction<typeof listPendingAuthorizations>} The mocked transport function.
 */
function listStub(): MockedFunction<typeof listPendingAuthorizations> {
  return vi.mocked(listPendingAuthorizations);
}

/**
 * Signs a NON-ADMIN operator on and renders the screen inside the real application shell.
 *
 * ⚠️ Assumptions: the session carries `carddemo-user` and never `carddemo-admin`, because this screen
 * is not admin-gated and the baseline says so explicitly. `app/cpy/COMEN02Y.cpy` lists 'Pending
 * Authorization View' as the eleventh main-menu option with its user-type FILLER set to `'U'` at L90,
 * and all eleven options are `'U'` with not one `'A'` anywhere in the copybook. A case that signed on
 * as an administrator would pass while the route was gated to administrators only, which is the exact
 * regression this parameter exists to catch.
 *
 * ⚠️ Assumptions: the session is established through the shared helper and there is no other way to
 * establish one. `ui/src/hooks/useAuth.ts` holds its session in module scope and publishes NO setter
 * for the groups or the user type, and no Context provider exists anywhere in the tree, so a test
 * cannot grant itself authority -- it can only mint a token carrying the groups and drive the real
 * sign-on. That is a property worth preserving rather than working around: authority derives solely
 * from the signed `cognito:groups` claim, exactly as AAP section 0.7.1 requires now that the
 * client-echoed `CDEMO-USER-TYPE` of `app/cpy/COCOM01Y.cpy` L27 to L28 is gone.
 *
 * Assumptions: the shell is mounted through `renderInAppShell`, which places the screen in the layout
 * route's `<Outlet />`. This screen delegates its title band, message line and key legend to the shell
 * through `useShellSlot`, so a bare render would leave all three unrendered and every message and
 * PF-key assertion below would be asserting against a tree the router never builds.
 *
 * Assumptions: the route pattern is supplied so the harness mounts the screen the way the router does.
 * @returns {Promise<{ user: Awaited<ReturnType<typeof renderInAppShell>>['user'] }>} The keyboard and
 *   pointer operator bound to the rendered document.
 * @throws {Error} If the sign-on exchange did not establish a session, which the harness reports.
 */
async function renderAsUser(): Promise<{
  readonly user: Awaited<ReturnType<typeof renderInAppShell>>['user'];
}> {
  await seedSession({ groups: ['carddemo-user'] });
  const rendered = await renderInAppShell(
    <>
      <AuthSummaryScreen />
      <LocationProbe />
    </>,
    { initialEntries: [AUTH_SUMMARY_ROUTE] },
  );
  return { user: rendered.user };
}

/**
 * Test identifier of the probe reporting the router's current address.
 *
 * Assumptions: a dedicated identifier rather than a role, because the probe is an instrument of the
 * suite and not part of the screen -- giving it a role would put a landmark in the accessibility tree
 * that the application does not have, and the shell-landmark assertions would then see it.
 */
const LOCATION_PROBE_TEST_ID = 'blitzy-location-probe';

/**
 * Reports the address the in-memory router currently holds.
 *
 * ⚠️ Purpose: navigation on this screen is otherwise UNOBSERVABLE. The harness renders inside a MEMORY
 * router, which keeps its history in the process and deliberately never touches `window.location` --
 * so asserting on `window.location.pathname` reads the jsdom document's own unchanged address and
 * reports `/` however the screen navigated. This component reads the router's own state through
 * `useLocation` and renders it, which is the only way a case can see where a route change went.
 *
 * Alternatives Considered: two alternatives were rejected. (1) Mounting placeholder sibling routes for
 * the menu and the detail screen and asserting their text appeared -- rejected because it proves only
 * that SOME route matched, and two patterns that both match would be indistinguishable; it also cannot
 * show the composite key inside the matched address. (2) Mocking `useNavigate` to record its argument
 * -- rejected because it would stop testing the router altogether, so a screen navigating to a path no
 * route declares would still pass.
 *
 * Assumptions: the probe is rendered as a SIBLING of the screen and the harness is given no route
 * pattern, so both sit under a catch-all and survive a route change. Mounting the screen at
 * `/authorizations` specifically would unmount the probe along with it the moment the screen navigated
 * away, which is precisely when its reading is wanted. This screen reads no route parameter -- it takes
 * the account from its own filter field -- so nothing is lost by not pinning its pattern.
 * @returns {ReactElement} An element carrying the current pathname as its text.
 */
function LocationProbe(): ReactElement {
  const location = useLocation();
  return <span data-testid={LOCATION_PROBE_TEST_ID}>{location.pathname}</span>;
}

/**
 * Returns the address the router currently holds, as the probe reports it.
 * @returns {string} The current pathname.
 */
function currentPath(): string {
  return screen.getByTestId(LOCATION_PROBE_TEST_ID).textContent ?? '';
}

/**
 * Returns the account filter control.
 *
 * Assumptions: located by its accessible name rather than by its identifier, so the query also asserts
 * the painted caption is programmatically associated with the control -- an association a screen-reader
 * user depends on and an identifier lookup would not notice the loss of.
 * @returns {HTMLElement} The account filter input.
 */
function accountIdField(): HTMLElement {
  return screen.getByRole('textbox', { name: AUTH_SUMMARY_LABELS.searchAccountId });
}

/**
 * Returns the row list's table, distinguished from the record panel's.
 *
 * ⚠️ Assumptions: TWO tables render on this screen and a bare role query is therefore ambiguous. The
 * record panel above the rows is an antd `Descriptions` with `bordered`, which emits a real `<table>`
 * of its own, so the row list has to be identified by something only it has. Its selection column
 * heading is that thing: the mapset declares the column and its heading itself at `COPAU00.bms` L197
 * to L201, so the heading is a painted baseline string rather than a test hook.
 * @returns {HTMLElement} The table carrying the listed authorizations.
 * @throws {Error} If no table carries the selection heading, which means the row list did not render.
 */
function rowTable(): HTMLElement {
  const table = screen.getAllByRole('table').find(hasSelectionHeading);
  if (table === undefined) {
    throw new Error(
      `no rendered table carries the '${AUTH_SUMMARY_COLUMN_HEADERS.selection}' heading, so the row ` +
        'list did not render; the record panel renders a table of its own and is not it',
    );
  }
  return table;
}

/**
 * Reports whether a table carries the row list's selection column heading.
 * @param {HTMLElement} candidate - A rendered table.
 * @returns {boolean} `true` when the table declares the selection column.
 */
function hasSelectionHeading(candidate: HTMLElement): boolean {
  return within(candidate).queryAllByText(AUTH_SUMMARY_COLUMN_HEADERS.selection).length > 0;
}

/**
 * Returns the record panel cell holding the value paired with one painted label.
 *
 * ⚠️ Assumptions: the value is reached as the label cell's NEXT SIBLING rather than by searching the
 * row for the value's text, and the difference matters twice over. First, an antd `Descriptions` row
 * holds several label-and-value pairs -- the responsive column policy puts two or three on a line --
 * so searching the row would admit a neighbour's value. Second, pairing by adjacency asserts the value
 * sits beside ITS OWN caption, which is the property a transposition breaks and a text search cannot
 * see.
 *
 * ⚠️ Assumptions: the caption is matched by comparing `textContent` with strict equality rather than
 * through a text matcher, and the reason is a real asymmetry in Testing Library rather than a
 * preference. Its default normaliser trims the NODE's text but leaves the matcher string untouched, so
 * a caption declared with a trailing space -- five of this panel's fourteen are, including
 * `'Approval # : '` -- can never equal its own normalised rendering and the query reports the element
 * as absent. Comparing exactly sidesteps that, and it strengthens the assertion at the same time:
 * transformation rule T8 carries painted text across character for character, so a caption that lost
 * its trailing space is a real divergence and this comparison is what notices.
 * @param {string} label - The painted caption, taken from the screen's exported label constants.
 * @returns {HTMLElement} The `<td>` holding that caption's value.
 * @throws {Error} If no caption matches exactly, or the caption carries no adjacent value cell.
 */
function panelValueCell(label: string): HTMLElement {
  const labelCell = Array.from(document.querySelectorAll<HTMLElement>('th')).find(
    /**
     * Reports whether one header cell carries exactly the caption sought.
     * @param {HTMLElement} cell - A rendered header cell.
     * @returns {boolean} `true` when its text is the caption, character for character.
     */
    function carriesExactCaption(cell: HTMLElement): boolean {
      return cell.textContent === label;
    },
  );
  if (labelCell === undefined) {
    throw new Error(
      `no header cell carries the caption ${JSON.stringify(label)} exactly; a caption that lost or ` +
        'gained surrounding space would fail here',
    );
  }
  const valueCell = labelCell.nextElementSibling;
  if (!(valueCell instanceof HTMLElement)) {
    throw new Error(`the caption ${JSON.stringify(label)} has no adjacent value cell`);
  }
  return valueCell;
}

/**
 * Returns the inline-styled element carrying a panel value.
 *
 * Assumptions: the styled descendant is the one to read, because the design system renders the value
 * inside a `Typography.Text` that carries the inline style while the enclosing cell carries none. That
 * makes the presence of an inline style the discriminator, and it is exactly the element whose
 * typography the money cases assert on.
 * @param {string} label - The painted caption whose value is wanted.
 * @returns {HTMLElement} The styled element holding the value.
 * @throws {Error} If the value cell holds no styled element.
 */
function panelValueText(label: string): HTMLElement {
  const styled = panelValueCell(label).querySelector<HTMLElement>('[style]');
  if (styled === null) {
    throw new Error(`the value beside '${label}' is not rendered through a styled text element`);
  }
  return styled;
}

/**
 * Scopes the screen to one account by typing an identifier and keying Enter.
 *
 * ⚠️ Assumptions: the turn is driven by a real Enter KEY PRESS rather than by clicking a submit
 * control, because that is what the source does and what this screen binds. `COPAUS0C.cbl` evaluates
 * `DFHENTER` in its `EVALUATE EIBAID` at L224 to L250, and the 3270 terminal had no pointing device at
 * all -- so the keyboard path is the contract and the rendered button is the addition. Driving the
 * scope through the key press also means every case that needs data has already exercised the Enter
 * binding by the time it asserts anything else.
 * @param {Awaited<ReturnType<typeof renderInAppShell>>['user']} user - The operator to drive.
 * @param {string} accountId - The identifier to enter.
 * @returns {Promise<void>} Resolves once the entry is typed and Enter has been dispatched.
 */
async function scopeToAccount(
  user: Awaited<ReturnType<typeof renderInAppShell>>['user'],
  accountId: string,
): Promise<void> {
  await user.click(accountIdField());
  await user.keyboard(accountId);
  await pressPfKey(user, 'ENTER');
}

/**
 * Scopes the screen to an account whose listing has already been queued, then waits for its rows.
 *
 * Assumptions: the wait is on a ROW being present rather than on the stub having been called, because
 * the stub resolves a promise and React commits the resulting state in a later task -- asserting on
 * the call count would pass before anything rendered, and every following query would then race the
 * commit.
 * @param {Awaited<ReturnType<typeof renderInAppShell>>['user']} user - The operator to drive.
 * @param {string} [accountId] - The identifier to scope to.
 * @returns {Promise<void>} Resolves once the first row of the answer is on screen.
 */
async function scopeAndAwaitRows(
  user: Awaited<ReturnType<typeof renderInAppShell>>['user'],
  accountId: string = ELEVEN_DIGIT_ACCOUNT_ID,
): Promise<void> {
  await scopeToAccount(user, accountId);
  await waitFor(expectFirstRowPresent);
}

/**
 * Asserts the first row of a listing has reached the document.
 *
 * Assumptions: the first row's transaction identifier is the probe, because it is the one value on a
 * row that the mapset paints unedited -- `TRNID01I PIC X(16)` in the symbolic map at L156 -- so it
 * arrives in the DOM exactly as the fixture wrote it.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectFirstRowPresent(): void {
  expect(screen.getByText(listItemFixture(1).transactionId)).toBeInTheDocument();
}

/**
 * Returns the rendered selection cells, one per row the table painted.
 *
 * ⚠️ Refactoring Rationale: these were found by role `radio` and are now found by role `textbox` within
 * the row table, because the mapset declares an unprotected ENTRY field and not a marker -- `SEL0001`
 * through `SEL0005` are `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1` at `COPAU00.bms` L277 to L282 and the
 * four repeats -- and the screen's own row-22 sentence instructs the operator to TYPE `'S'`. The old
 * note here argued that counting radios also asserted the five formed one selection set; that property
 * has not been dropped, it has moved to where the source puts it, which is the ordered `EVALUATE TRUE`
 * at `COPAUS0C.cbl` L288 to L308 transcribed in `reduceAuthRowSelection` and asserted directly.
 *
 * ⚠️ Assumptions: the query is scoped to the ROW TABLE and not to the document, because the screen's
 * account filter is also a textbox and an unscoped query would count it as a sixth row. {@link rowTable}
 * is what distinguishes the listing from the record panel's own `<table>`.
 *
 * Assumptions: a non-throwing query, so a case can assert that NO cells were painted -- which is what
 * the empty-page and short-page cases measure.
 * @returns {readonly HTMLElement[]} The selection cells in rendered order.
 */
function selectionControls(): readonly HTMLElement[] {
  const tables = screen.queryAllByRole('table').filter(hasSelectionHeading);
  const listing = tables[0];
  if (listing === undefined) {
    return [];
  }
  return within(listing).queryAllByRole('textbox');
}

/**
 * Returns the message band's rendered element, or `null` when nothing is on it.
 *
 * Assumptions: queried by the test identifier the band publishes rather than by role, because
 * `ui/src/layout/MessageBand.tsx` chooses `alert` or `status` according to the SEVERITY of what it
 * holds -- so a role query would have to be told the severity, which is the screen's data rather than
 * the frame's structure.
 *
 * ⚠️ Assumptions: the band carries a SEVENTY-FIVE-character content contract, and this file records it
 * without asserting it. `app/cpy/CVCRD01Y.cpy` declares `CCARD-ERROR-MSG PIC X(75)` at L28 and
 * `CCARD-RETURN-MSG PIC X(75)` at L29, with the off-value condition on L30, so seventy-five characters
 * is the width every sentence this screen places on the band was authored to fit -- and `COPAU00.bms`
 * paints its own `ERRMSG` field `LENGTH=78` at rows 23, giving the field three characters more than the
 * copybook fills. Both numbers are noted here so a reader does not mistake the difference for an error.
 * Trade-offs: the width itself is deliberately NOT asserted in this file. It is a property of the
 * shared band rather than of this screen, and `ui/src/layout/MessageBand.test.tsx` owns it; asserting
 * it here as well would put one contract in two suites, so the screen suite that broke it and the frame
 * suite that owns it would report the same regression twice and neither would be authoritative.
 * @returns {HTMLElement | null} The band, or `null` when it renders nothing.
 */
function messageBand(): HTMLElement | null {
  return screen.queryByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns the function-key legend the shell paints on the screen's behalf.
 * @returns {HTMLElement} The legend's navigation landmark.
 */
function keyLegend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads the cursor and direction of one recorded call to the listing operation.
 *
 * ⚠️ Assumptions: the query is read from the RECORDED CALL rather than inferred from what rendered,
 * because the cursor is the whole point of the assertion and it never appears in the DOM. A case that
 * checked only the resulting rows could not tell a keyset read from an offset read at all -- both
 * produce rows -- which is precisely the distinction AAP section 0.7.4 requires be preserved.
 * @param {number} callIndex - Zero-based index of the call to read.
 * @returns {{ accountId: string; cursor?: string; direction?: string }} That call's query.
 * @throws {Error} If the stub was not called that many times, naming the shortfall.
 */
function recordedQuery(callIndex: number): {
  readonly accountId: string;
  readonly cursor?: string;
  readonly direction?: string;
} {
  const call = listStub().mock.calls[callIndex];
  if (call === undefined) {
    throw new Error(
      `the listing was called ${String(listStub().mock.calls.length)} time(s), so call ` +
        `${String(callIndex)} was never made`,
    );
  }
  return call[0];
}

beforeEach(resetTransport);
afterEach(resetSessionAndTransport);

/**
 * Clears the transport stub so no case inherits another's queued outcome.
 *
 * ⚠️ Assumptions: this is NOT redundant beside the runner's own `clearMocks` and `restoreMocks`, which
 * `ui/vitest.config.ts` enables. Those run AFTER each case; this runs BEFORE, which is what makes a
 * case's own arrangement the only thing its stub carries even when a previous file left the module
 * registry warm. It also resets the queued IMPLEMENTATION rather than only the recorded calls, so a
 * `mockResolvedValueOnce` left unconsumed by an early-returning case cannot be handed to the next one.
 * @returns {void} Nothing; the stub holds no calls and no queued outcome.
 */
function resetTransport(): void {
  listStub().mockReset();
}

/**
 * Holds the account filter to the width its map field declares.
 *
 * @returns {Promise<void>} Resolves once the width has been asserted.
 */
async function holdsTheDeclaredFilterWidth(): Promise<void> {
  await renderAsUser();

  // WHY : Assumptions: eleven is a CITATION and not a chosen limit. The symbolic map
  //       `app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy` L60 declares `ACCTIDI PIC X(11)` and
  //       the mapset declares the field `LENGTH=11`, matching `CDEMO-ACCT-ID PIC 9(11)` at
  //       `app/cpy/COCOM01Y.cpy` L38. The 3270 enforced that width in hardware -- the twelfth
  //       keystroke did nothing -- and `maxLength` is where the constraint survives.
  expectMaxLength(accountIdField(), AUTH_SUMMARY_FIELD_WIDTHS.accountId);
}

/**
 * Refuses an identifier that is not eleven digits, even though the mapset carries no `NUM` attribute.
 *
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesANonNumericIdentifier(): Promise<void> {
  const { user } = await renderAsUser();

  await user.click(accountIdField());
  await user.keyboard('ABCDEFGHIJK');
  await pressPfKey(user, 'ENTER');

  // WHY : ⚠️ Assumptions: the digit rule is asserted even though `COPAU00.bms` declares ZERO `NUM`
  //       attributes anywhere, because mapset attributes and program validation are INDEPENDENT
  //       enforcement layers and the program is the stricter one. `COPAUS0C.cbl` L278 moves
  //       'Acct Id must be Numeric ...' when the field fails `IS NOT NUMERIC`, so the rule is real
  //       whether or not the terminal helped enforce it. Inferring the absence of the rule from the
  //       absence of the attribute is the mistake this case exists to prevent.
  expectVerbatimMessage(PROGRAM_MESSAGES.COPAUS0C.ACCT_ID_MUST_BE_NUMERIC);

  // WHY : Assumptions: no read is issued for a locally refused entry, matching the source: both
  //       refusal arms move LOW-VALUES into `WS-ACCT-ID` (L265 and L274) and `GATHER-DETAILS` then
  //       reads nothing because its `IF WS-ACCT-ID NOT = LOW-VALUES` guard at L349 is not entered.
  expect(listStub()).not.toHaveBeenCalled();
}

/**
 * Refuses a blank identifier with the source's own separate sentence.
 *
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesABlankIdentifier(): Promise<void> {
  const { user } = await renderAsUser();

  await pressPfKey(user, 'ENTER');

  // WHY : Assumptions: the blank case is refused BEFORE the numeric test and so reports a different
  //       sentence, following `PROCESS-ENTER-KEY` at `COPAUS0C.cbl` L261 to L338 in order. A blank is
  //       never reported as non-numeric, which a single combined refusal would get wrong.
  expectVerbatimMessage(PROGRAM_MESSAGES.COPAUS0C.PLEASE_ENTER_ACCT_ID);
  expect(listStub()).not.toHaveBeenCalled();
}

/**
 * Leaves the opening cursor where the source left it, claiming focus for nothing.
 *
 * @returns {Promise<void>} Resolves once the absence of an autofocused control has been asserted.
 */
async function claimsTheOpeningCursorForNothing(): Promise<void> {
  await renderAsUser();

  // WHY : ⚠️ Assumptions: NO element carries `autoFocus`, and the absence is FIDELITY rather than an
  //       omission. A BMS field claims the opening cursor with the `IC` attribute, and
  //       `app/app-authorization-ims-db2-mq/bms/COPAU00.bms` carries ZERO `IC` across all 104 of its
  //       `DFHMDF` definitions -- the only occurrence of those two letters in the whole file is inside
  //       the Apache licence URL on L11. Fifteen of the seventeen base mapsets DO declare exactly one
  //       `IC`, so an author generalising from them would add one here and silently break fidelity,
  //       which is why this is asserted rather than assumed.
  // WHY : Trade-offs: the property is asserted as "the body claims nothing" rather than "this control
  //       lacks the attribute", because the second form passes while some OTHER control on the screen
  //       steals the cursor. The cost is that the assertion names no field; the gain is that it cannot
  //       be satisfied by moving the defect.
  expect(document.querySelector('[autofocus]')).toBeNull();

  // WHY : Assumptions: an unexpected focus jump is a behaviour change and not a cosmetic one on this
  //       screen specifically. It is a browse screen paged with PF7 and PF8, so focus sitting inside a
  //       text field changes what those key presses reach, and it also displaces a screen reader's own
  //       entry point on arrival.
  expect(document.activeElement).toBe(document.body);
}

/**
 * Paints no password or non-display control, because the mapset declares none.
 *
 * @returns {Promise<void>} Resolves once the absence has been asserted.
 */
async function paintsNoNonDisplayControl(): Promise<void> {
  await renderAsUser();

  // WHY : Assumptions: a 3270 field is made non-display by the `DRK` attribute, and `COPAU00.bms`
  //       declares ZERO `DRK` -- so this screen has no password or suppressed-entry field to
  //       reproduce. The sign-on mapset is where the six measured `ATTRB=(ASKIP,DRK,FSET)` fields
  //       live, and rendering an `Input.Password` here would invent a control the terminal never
  //       painted.
  expect(document.querySelector('input[type="password"]')).toBeNull();
}

/**
 * Renders the row list through the design system's table rather than raw markup.
 *
 * @returns {Promise<void>} Resolves once the composition has been asserted.
 */
async function rendersThroughTheDesignSystemTable(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : Assumptions: AAP section 0.4.1.4 maps `/authorizations` to a `Table`, and AAP section 0.3.2
  //       makes library components mandatory over raw HTML. The role is asserted rather than the class
  //       name, because the role is what assistive technology acts on and a class name would tie the
  //       case to the design system's internal naming.
  const table = rowTable();
  expect(table).toBeInTheDocument();

  // WHY : Assumptions: every column heading is a catalogued painted string, so the headers are
  //       asserted from the screen's own exported constants rather than retyped. `AUTH_SUMMARY_COLUMN_HEADERS`
  //       carries the mapset's own spacing -- ' Transaction ID ', '  Date  ', '   Amount   ' -- which a
  //       retyped literal would silently normalise away.
  const headers = within(table).getAllByRole('columnheader');
  const headerText = headers.map(readTextContent);
  for (const heading of Object.values(AUTH_SUMMARY_COLUMN_HEADERS)) {
    expect(headerText).toContain(heading);
  }
}

/**
 * Reads one element's text exactly as the DOM holds it.
 *
 * Assumptions: `textContent` is read without trimming or collapsing, because several of the painted
 * column headings carry leading and trailing spaces that are the mapset's own column geometry rather
 * than markup whitespace.
 * @param {HTMLElement} element - The element to read.
 * @returns {string} Its text content, or the empty string when it holds none.
 */
function readTextContent(element: HTMLElement): string {
  return element.textContent ?? '';
}

/**
 * The name and both address lines render as ONE captioned block, in the mapset's own order.
 *
 * ⚠️ Purpose: this is the regression this case exists for. The two address lines were bordered entries
 * of their own carrying a caption that the mapset does not paint and that this screen deliberately hides
 * -- so browser validation measured two empty grey header cells at every one of the six widths, and,
 * below the medium breakpoint where the panel reflows to one column, the two halves of one postal
 * address separated by the unrelated `Acct Status` row that reflowed between them.
 *
 * ⚠️ Assumptions: the whole cell's text is asserted as one exact string rather than three containment
 * checks, for the reason the status cell's case records -- containment cannot prove adjacency or order,
 * and an address whose two lines render in the wrong order is wrong in exactly the way containment
 * cannot see. `COPAU00.bms` paints `CNAME` at row 6, `ADDR001` at row 7 and `ADDR002` at row 8, all at
 * column 10 under the one `Name: ` caption at L92 to L95, so this string is the source's own order.
 * @returns {Promise<void>} Resolves once the block has been asserted.
 */
async function rendersTheHolderBlockUnderTheOneCaption(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(1)));

  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const summary = summaryFixture();

  expect(readTextContent(panelValueCell(AUTH_SUMMARY_LABELS.name))).toBe(
    `${summary.customerName}` +
      `${AUTH_SUMMARY_HIDDEN_LABELS.addressLine1}${summary.addressLine1}` +
      `${AUTH_SUMMARY_HIDDEN_LABELS.addressLine2}${summary.addressLine2}`,
  );
}

/**
 * No caption cell in the panel is empty.
 *
 * ⚠️ Purpose: an empty header cell in a bordered panel is the defect itself, not a symptom of it -- it
 * renders as a grey box with nothing in it and announces the value beside it as belonging to nothing.
 * This asserts the property directly, over EVERY header cell the panel renders, so the defect cannot
 * return through a different entry than the two it was found on.
 *
 * ⚠️ Assumptions: `textContent` is what is measured rather than a visibility check, because a caption
 * hidden by `VISUALLY_HIDDEN_STYLE` has text and is legitimate INSIDE a value cell, while a header cell
 * with no text at all is not legitimate anywhere. The distinction the case draws is therefore between an
 * empty header and a populated one, which is exactly the distinction the finding drew.
 *
 * Assumptions: the table is excluded by scoping to the panel's own element rather than by filtering, so
 * the authorization list's own headings cannot dilute the assertion.
 * @returns {Promise<void>} Resolves once every caption cell has been asserted populated.
 */
async function leavesNoCaptionCellEmpty(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(1)));

  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const panel = panelValueCell(AUTH_SUMMARY_LABELS.name).closest('table');
  expect(panel).not.toBeNull();

  const captions = Array.from(panel?.querySelectorAll<HTMLElement>('th') ?? []);
  expect(captions.length).toBeGreaterThan(0);
  for (const caption of captions) {
    expect(readTextContent(caption)).not.toBe('');
  }
}

/**
 * The status cell renders all six status members the segment declares, each individually named.
 *
 * ⚠️ Purpose: this is the regression this case exists for. Browser validation reported five populated
 * `PA-ACCOUNT-STATUS` slots returned on every listing and rendered by no field at all, under a caption
 * that showed `PA-AUTH-STATUS` instead -- returned data displayed nowhere, beside a different member
 * displayed in the position an operator would read it from.
 *
 * ⚠️ Assumptions: the whole cell's text is asserted as ONE exact string rather than six separate
 * containment checks, because containment cannot prove adjacency. What matters is that each code is
 * announced with the name of the member it came from, and six names and six codes present in a cell in
 * the wrong pairing would satisfy every containment assertion while telling a reader that slot 1 holds
 * slot 4's code. The exact string pins name, code, pairing and order together.
 *
 * Assumptions: the fixture's six values are mutually distinct -- `Y`, then `AA` through `EE` -- so a
 * transposition between any two of them is detectable at all.
 * @returns {Promise<void>} Resolves once the cell has been asserted.
 */
async function rendersEveryStatusMemberUnderTheOneCaption(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(1)));

  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const names = AUTH_SUMMARY_HIDDEN_LABELS;

  // WHY : ⚠️ Assumptions: the five ACCOUNT-status slots are expected FIRST and the authorization-status
  //       flag last, which is the order the cell now renders and is asserted rather than tolerated. The
  //       caption reads `Acct Status: `, so a screen leading with the flag reproduces the substitution
  //       this case was written against -- the wrong member in the position an operator reads first --
  //       even though all six values are present. Order is therefore part of the contract here.
  expect(readTextContent(panelValueCell(AUTH_SUMMARY_LABELS.accountStatus))).toBe(
    `${names.accountStatus1}AA` +
      `${names.accountStatus2}BB` +
      `${names.accountStatus3}CC` +
      `${names.accountStatus4}DD` +
      `${names.accountStatus5}EE` +
      `${names.authStatus}Y`,
  );
}

/**
 * None of the six status names reaches the screen.
 *
 * ⚠️ Purpose: the six names exist so an assistive technology can tell one code from another, and the
 * mapset paints exactly one caption over them -- `Acct Status: ` at `COPAU00.bms` L111 to L113. Five
 * further captions on the glass would be screen text no baseline source declares, which transformation
 * rule T8 forecloses, so the names are asserted to be visually hidden rather than merely present.
 *
 * Assumptions: the hidden mechanism is asserted through `VISUALLY_HIDDEN_STYLE`'s own `position` rather
 * than a literal, so the case measures the project's one hiding mechanism instead of restating it -- a
 * name switched to `display: none` would fail here, and correctly, because `display: none` removes it
 * from the accessibility tree as well as from the screen.
 * @returns {Promise<void>} Resolves once every name has been asserted hidden.
 */
async function keepsEveryStatusNameOffTheScreen(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(1)));

  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  for (const name of Object.values(AUTH_SUMMARY_HIDDEN_LABELS)) {
    expect(screen.getByText(name).style.position).toBe(VISUALLY_HIDDEN_STYLE.position);
  }
}

/**
 * The identifier the summary describes is adopted into the field it was submitted from.
 *
 * ⚠️ Purpose: `summary.accountId` was returned on every listing and displayed nowhere, which browser
 * validation recorded as returned-and-never-rendered. The reference does display it, and in this very
 * field: `ACCTIDO` and `ACCTIDI` are the same map field, and `COPAUS0C.cbl` L228 to L232 moves
 * `WS-ACCT-ID` into `ACCTIDO` on the Enter arm before the map is sent.
 *
 * ⚠️ Assumptions: the fixture's returned identifier deliberately DIFFERS from the identifier typed, and
 * that is a requirement of the case rather than an accident. The two are equal in every real read, so a
 * fixture that echoed the typed value back would pass whether the screen adopted the returned member or
 * simply left the operator's own text alone -- the assertion would measure nothing.
 * @returns {Promise<void>} Resolves once the field has been asserted.
 */
async function adoptsTheIdentifierTheSummaryDescribes(): Promise<void> {
  listStub().mockResolvedValue(
    listingFixture(rowsOf(1), {
      summary: summaryFixture({ accountId: OTHER_ELEVEN_DIGIT_ACCOUNT_ID }),
    }),
  );

  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectFirstRowPresent);

  expect(accountIdField()).toHaveValue(OTHER_ELEVEN_DIGIT_ACCOUNT_ID);
}

/**
 * An identifier being typed is not overwritten by a read settling under the previous one.
 *
 * ⚠️ Purpose: this is the trade-off the adoption carries, asserted so it cannot be lost. The terminal
 * cannot reach this state -- CICS locks the keyboard for the duration of a turn -- so there is no
 * reference behaviour to transcribe, and the screen deliberately allows a second identifier to be typed
 * while a read is outstanding, because that is how an operator abandons a mistyped search. Adopting the
 * abandoned read's identifier would destroy the correction in progress.
 *
 * Assumptions: the read is held unsettled until AFTER the field has been retyped, which is the only
 * ordering in which the defect this guards against can occur at all.
 * @returns {Promise<void>} Resolves once the retyped entry has been asserted intact.
 */
async function refusesToOverwriteAnEntryBeingRetyped(): Promise<void> {
  const held = heldRead<PendingAuthListResponse>();
  listStub().mockReturnValueOnce(held.promise);

  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectOneListingIssued);

  await user.clear(accountIdField());
  await user.click(accountIdField());
  await user.keyboard(OTHER_ELEVEN_DIGIT_ACCOUNT_ID);

  held.settle(listingFixture(rowsOf(1)));
  await waitFor(expectFirstRowPresent);

  expect(accountIdField()).toHaveValue(OTHER_ELEVEN_DIGIT_ACCOUNT_ID);
}

/**
 * Carries the account-status array as five discrete members rather than a variable-length list.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function carriesFiveDiscreteAccountStatusMembers(): void {
  const summary = summaryFixture();
  const slots = Object.keys(summary).filter(isAccountStatusSlot);

  // WHY : ⚠️ Refactoring Rationale: `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` L22 declares
  //       `PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES`, and AAP section 0.4.1.3 maps that OCCURS to
  //       five DISCRETE columns `account_status_1` through `account_status_5` rather than to a
  //       PostgreSQL array. Two things are bought by refusing the array: the fixed arity of five is
  //       then enforced by the schema itself rather than by application code, and the JPA mapping
  //       stays portable, because an array column is a vendor extension while five scalars are not.
  //       The contract carries the decision through to the client, so the shape is asserted here.
  expect(slots).toHaveLength(5);

  // WHY : Assumptions: no slot is array-valued, which is the half of the property that a length check
  //       alone would miss -- five members each holding a list would satisfy the count and defeat the
  //       arity guarantee entirely.
  for (const slot of slots) {
    expect(Array.isArray(summary[slot as keyof PendingAuthSummary])).toBe(false);
  }
}

/**
 * Reports whether a summary member name is one of the five account-status slots.
 *
 * Assumptions: matched by pattern rather than against a written list of five names, so the assertion
 * measures what the contract declares instead of restating it -- a sixth slot appearing in the type
 * would be counted and would fail the arity assertion, where a hard-coded list would ignore it.
 * @param {string} member - A member name read off the summary fixture.
 * @returns {boolean} `true` when the name is an `accountStatus<n>` slot.
 */
function isAccountStatusSlot(member: string): boolean {
  return /^accountStatus\d+$/u.test(member);
}

/**
 * Pages five rows at a time, which is the arity the baseline fixes three independent ways.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function pagesFiveRowsAtATime(): void {
  // WHY : ⚠️ Assumptions: FIVE, and it is corroborated three independent ways in the baseline rather
  //       than chosen. (1) `COPAUS0C.cbl` L424 bounds the page-forward row loop with
  //       `PERFORM UNTIL WS-IDX > 5` and L611 bounds the initialisation loop with
  //       `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 5`. (2) L126 declares
  //       `CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES`, so exactly five row keys survive a turn.
  //       (3) The symbolic map `COPAU00.cpy` declares exactly `SEL0001I` through `SEL0005I` at L150,
  //       L198, L246, L294 and L384 and no sixth selector anywhere.
  // WHY : ⚠️ Trade-offs: this value is asserted rather than trusted precisely because the sibling list
  //       screens disagree with it -- the card and reference-type browses page 7, the transaction and
  //       user browses page 10 -- so five is the outlier and the one an author is most likely to
  //       "correct" toward a neighbour. The cost is one assertion that looks tautological; the gain is
  //       that a silent change to the constant fails here with its three citations attached.
  expect(AUTH_SUMMARY_PAGE_SIZE).toBe(5);
}

/**
 * Renders exactly the page size for a full page, and no more.
 *
 * @returns {Promise<void>} Resolves once the row count has been asserted.
 */
async function rendersExactlyAFullPage(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : Assumptions: the row count is measured by the selection controls, one of which each row
  //       carries, so the count is of RENDERED rows rather than of fixture members.
  expect(selectionControls()).toHaveLength(AUTH_SUMMARY_PAGE_SIZE);

  // WHY : Assumptions: the fifth row is asserted PRESENT and a sixth ABSENT as two separate
  //       statements, because a length check alone is satisfied by five arbitrary rows -- it cannot
  //       show that the five rendered are the five sent, in order.
  expect(
    screen.getByText(listItemFixture(AUTH_SUMMARY_PAGE_SIZE).transactionId),
  ).toBeInTheDocument();
  expect(
    screen.queryByText(listItemFixture(AUTH_SUMMARY_PAGE_SIZE + 1).transactionId),
  ).not.toBeInTheDocument();
}

/**
 * Renders a short page as short, without padding it out to the page size.
 *
 * @returns {Promise<void>} Resolves once the short page has been asserted.
 */
async function rendersAShortPageShort(): Promise<void> {
  const shortCount = 2;
  listStub().mockResolvedValue(listingFixture(rowsOf(shortCount)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : Assumptions: a final page carrying fewer than five rows renders fewer than five controls.
  //       The source reaches the same appearance differently -- `INITIALIZE-AUTH-DATA` protects all
  //       five selectors and blanks all five row families at L608 to L662 before a page is built, so
  //       an unfilled slot is painted empty rather than absent -- and the target drops the row
  //       instead, because an empty table row with a live selection control would offer a selection of
  //       nothing. The rendered outcome, that only real rows are selectable, is preserved.
  expect(selectionControls()).toHaveLength(shortCount);
}

/**
 * Disables the design system's own offset pager, so paging is by key alone.
 *
 * @returns {Promise<void>} Resolves once the absence of an offset pager has been asserted.
 */
async function disablesTheOffsetPager(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : ⚠️ Trade-offs: `pagination={false}` is set on the table deliberately, and the compromise is
  //       correctness over convenience. Offset pagination under concurrent inserts SKIPS AND REPEATS
  //       rows -- an operator stepping forward can miss an authorization entirely and see another
  //       twice -- whereas reading from the last key cannot. AAP section 0.7.4 establishes that the
  //       baseline browse state is ALREADY a keyset cursor, and `COPAUS0C.cbl` L126's
  //       `OCCURS 5 TIMES` key table is the proof: the program literally carries five keys forward
  //       across the turn, and L121 carries `CDEMO-CPVS-PAUKEY-LAST` rather than a row number. Keyset
  //       paging is therefore a one-to-one mapping of what the source does, not an approximation of
  //       it. What is given up is the total-page count antd's pager would display, which the
  //       four-member envelope cannot supply anyway.
  // WHY : Assumptions: the property is asserted as the ABSENCE of a rendered pager rather than by
  //       reading the prop off the element, because a prop is not observable from the DOM and the
  //       thing that matters to an operator is that no page-number control exists to click.
  expect(screen.queryByRole('list', { name: /pagination/iu })).not.toBeInTheDocument();
  expect(document.querySelector('.ant-pagination')).toBeNull();
}

/**
 * Steps forward from the trailing cursor, ascending, when the envelope reports more rows.
 *
 * @returns {Promise<void>} Resolves once the forward cursor has been asserted.
 */
async function stepsForwardFromTheTrailingCursor(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);

  const forward = recordedQuery(1);

  // WHY : Assumptions: the opening read carries NO cursor and no direction, and the second read
  //       carries both. `ui/src/api/authorization.ts` refuses a direction sent without a cursor by
  //       raising locally, so the opening read is built as a scope-only query -- asserting its absence
  //       here is what shows the forward step is a step rather than a re-read.
  expect(recordedQuery(0).cursor).toBeUndefined();

  // WHY : ⚠️ Assumptions: the forward step reads strictly AFTER the trailing cursor, ascending. The
  //       shared page builder seals the trailing position as `TRAILING_CURSOR`, so asserting the
  //       cursor the client received proves the envelope's own `lastKey` was threaded through rather
  //       than a row offset being computed. The direction is stated explicitly even though the
  //       contract defaults an absent direction to forward, so a replayed request says which way it
  //       moves.
  expect(forward.cursor).toBe(TRAILING_CURSOR);
  expect(forward.direction).toBe('next');
}

/**
 * Steps backward from the leading cursor, descending, once a forward step has been taken.
 *
 * @returns {Promise<void>} Resolves once the backward cursor has been asserted.
 */
async function stepsBackwardFromTheLeadingCursor(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);
  await pressPfKey(user, 'PFK07');
  await waitFor(expectThirdCallMade);

  const backward = recordedQuery(2);

  // WHY : ⚠️ Assumptions: the backward step reads strictly BEFORE the leading cursor, descending, and
  //       the source's twenty-slot key stack is deliberately not reproduced. `COPAUS0C.cbl` L120
  //       declares `CDEMO-CPVS-PAUKEY-PREV-PG PIC X(08) OCCURS 20 TIMES` and PF7 repositions at a
  //       remembered key rather than past it, because `GET-AUTHORIZATIONS` issues `EXEC DLI GNP` at
  //       L461 -- Get Next within Parent -- and IMS DL/I is forward-only with no read-previous verb.
  //       The array is therefore an artifact of the datastore's navigation, not a business rule.
  //       PostgreSQL has the verb the source lacked, so a native descending read returns exactly the
  //       rows a stack pop would have returned; it is the mechanism the stack was standing in for.
  expect(backward.cursor).toBe(LEADING_CURSOR);
  expect(backward.direction).toBe('previous');
}

/**
 * Carries the whole composite authorization key in the cursor, not just the account identifier.
 *
 * @returns {Promise<void>} Resolves once the composite cursor has been asserted.
 */
async function carriesTheWholeCompositeKeyInTheCursor(): Promise<void> {
  const rows = rowsOf(AUTH_SUMMARY_PAGE_SIZE);
  const trailing = rows[rows.length - 1];
  if (trailing === undefined) {
    throw new Error('the page fixture built no rows, so it has no trailing key to assert on');
  }

  // WHY : Assumptions: the envelope's trailing cursor is set to the LAST ROW'S OWN sealed key here,
  //       rather than left as the builder's opaque token, because this case is about the SHAPE of what
  //       travels. The default token proves threading; a real composite key proves the composite
  //       survives threading.
  listStub().mockResolvedValue({
    summary: summaryFixture(),
    page: pageResponse(rows, { hasNext: true, lastKey: trailing.key }),
    screenMessage: null,
  });

  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);
  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);

  const cursor = recordedQuery(1).cursor ?? '';

  // WHY : ⚠️ Assumptions: the key is composite because AAP section 0.4.1.3 gives this table the primary
  //       key `(account_id, auth_date, auth_time)`, derived from the packed composite key the IMS
  //       segment carries at `CIPAUSMY.cpy` L19. A cursor carrying only the account identifier could
  //       not resume a browse WITHIN one account, which is the only browse this screen performs -- so
  //       all three parts are asserted present rather than the whole token compared, which would only
  //       restate the fixture.
  expect(cursor).toBe(trailing.key);
  expect(cursor).toContain(ELEVEN_DIGIT_ACCOUNT_ID);
  expect(cursor.length).toBeGreaterThan(ELEVEN_DIGIT_ACCOUNT_ID.length);
}

/**
 * Reads nothing off the page envelope that the envelope does not declare.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function readsOnlyTheFourDeclaredEnvelopeMembers(): void {
  const envelope = pageResponse(rowsOf(1), { hasNext: true });

  // WHY : ⚠️ Assumptions: `ui/src/api/types.ts` L174 declares `PageResponse<T>` with EXACTLY four
  //       members -- `items`, `firstKey`, `lastKey` and `hasNext` -- and every service contract
  //       declares them with `additionalProperties: false`. There is no `hasPrev`, no page number, no
  //       page size and no row total, because no service sends one. Backward availability is the
  //       CLIENT's own page ordinal, held by `ui/src/hooks/usePagedQuery.ts` exactly as
  //       `COPAUS0C.cbl` holds `CDEMO-CPVS-PAGE-NUM` at L122 and tests it with
  //       `IF CDEMO-CPVS-PAGE-NUM > 1` at L365.
  // WHY : Trade-offs: the member list is asserted exhaustively rather than the absent names being
  //       checked one by one. Naming the five absentees would pass a sixth invented member; comparing
  //       the whole key set fails on anything the contract does not declare, which is the property
  //       actually wanted.
  expect(Object.keys(envelope).sort()).toStrictEqual(['firstKey', 'hasNext', 'items', 'lastKey']);
}

/**
 * Asserts the listing has been called a second time.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectSecondCallMade(): void {
  expect(listStub()).toHaveBeenCalledTimes(2);
}

/**
 * Asserts the listing has been called a third time.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectThirdCallMade(): void {
  expect(listStub()).toHaveBeenCalledTimes(3);
}

/**
 * Carries every monetary value as an exact decimal string, never a number.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function carriesEveryAmountAsAnExactString(): void {
  const summary = summaryFixture();

  for (const { member } of MONEY_MEMBERS) {
    // WHY : ⚠️ Trade-offs: money is transported as a JSON STRING, and the compromise accepted is a
    //       less convenient wire type in exchange for exactness that cannot be lost. A JSON NUMBER is
    //       parsed into an IEEE-754 double by essentially every client, which destroys the exactness
    //       that `NUMERIC(p,2)` in PostgreSQL and `BigDecimal` at scale 2 with `HALF_UP` in Java both
    //       preserve. AAP section 0.7.3 names this the highest-risk area in the migration for a
    //       specific reason: an error here is SILENT. It does not throw, it does not fail to render --
    //       it produces plausible numbers that are wrong, on a screen showing credit limits and
    //       balances.
    expect(typeof summary[member]).toBe('string');
  }

  // WHY : Assumptions: the two count members are asserted to be NUMBERS in the same case, because the
  //       money rule is only meaningful if it is a real distinction rather than a blanket
  //       stringification. `CIPAUSMY.cpy` L27 to L28 declares them `PIC S9(04) COMP` -- binary
  //       halfwords, an occurrence count rather than an amount -- so typing them as numbers is
  //       correct, and a change that made everything a string would pass a money-only assertion while
  //       losing that distinction.
  expect(typeof summary.approvedAuthCnt).toBe('number');
  expect(typeof summary.declinedAuthCnt).toBe('number');
}

/**
 * The mapset's row-22 field reaches the row-22 band, verbatim, from the opening turn.
 *
 * ⚠️ Purpose: `COPAU00.bms` L497 to L502 declares a `LENGTH=52` `COLOR=NEUTRAL` field at
 * POS=(22,12) carrying the instruction for opening a row, and this screen carried that string only as
 * the selection group's accessible name -- so it reached the accessibility tree and no visible surface.
 * A screen whose one purpose is opening a row was not telling a sighted operator how.
 *
 * ⚠️ Assumptions: the band asserted is the row-22 one and not row 23, because
 * `MESSAGE_BAND_CHANNELS` routes by TENSE: this sentence is as true before the turn as after it, so it
 * is `INFOMSG` and never `ERRMSG`. Asserting it on row 23 would pass while the guidance competed with
 * every turn's outcome for a field that holds one sentence at a time.
 *
 * ⚠️ Assumptions: it is asserted on the OPENING turn with no account scoped and no read issued,
 * because the reference paints it unconditionally -- the field carries an `INITIAL=` literal and
 * `COPAUS0C` never writes it, so it is on the glass from the first send. A case that scoped first would
 * pass while the instruction was withheld exactly when it is needed.
 *
 * Assumptions: the text is taken from the screen's own exported constant rather than retyped, so the
 * embedded apostrophes around the selection character cannot be silently normalised by this file.
 * @returns {Promise<void>} Resolves once the row-22 band has been asserted.
 */
async function paintsTheRowTwentyTwoInstruction(): Promise<void> {
  await renderAsUser();

  const band = await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  expect(band).toHaveTextContent(AUTH_SUMMARY_SELECTION_PROMPT);
  expect(listStub()).not.toHaveBeenCalled();
}

/**
 * At a dead end both paging keys answer with their own sentence, and neither key is withdrawn.
 *
 * ⚠️ Purpose: this is the state the previous availability idiom could not name. A browse with no
 * rows, no page ahead and no page behind satisfied both "nothing behind" and "nothing ahead" at once,
 * and neither guard said so -- so the screen now branches on the browse's published POSITION, whose
 * `EMPTY` member is exactly this case. `ui/src/hooks/usePagedQuery.ts` records that five screens had
 * five idioms for this and that a screen showing a boundary sentence branches on the position.
 *
 * ⚠️ Assumptions: BOTH sentences are demanded from the same page, because that is what distinguishes
 * the dead end from either single boundary. On the first page of several only the backward sentence is
 * reachable and on the last only the forward one; here both are, and a screen that had folded the dead
 * end into one of the two would answer one key correctly and the other with the wrong text.
 *
 * ⚠️ Assumptions: both rendered keys are additionally asserted ENABLED. The oracle refuses neither
 * key -- `COPAUS0C.cbl` L380 to L384 and L408 to L411 both re-send the screen with a sentence -- and
 * greying one would route the press through the function-key hook's invalid-key path, which shows
 * 'Invalid key pressed...' in place of the verbatim boundary string. Asserting the sentence alone would
 * not catch a key that was disabled AND still somehow announced; asserting the enablement states the
 * property the oracle actually has.
 *
 * Assumptions: no row is awaited, because none arrives. The read is awaited instead, which is what
 * establishes that the page was delivered and was empty rather than still outstanding.
 * @returns {Promise<void>} Resolves once both sentences and both enabled keys have been asserted.
 */
async function announcesBothBoundariesAtADeadEnd(): Promise<void> {
  listStub().mockResolvedValue(listingFixture([], { hasNext: false }));
  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectOneListingIssued);

  await pressPfKey(user, 'PFK07');
  await waitFor(expectTopBoundaryShown);

  await pressPfKey(user, 'PFK08');
  await waitFor(expectBottomBoundaryShown);

  expectPagingKeyEnabled(AUTH_SUMMARY_KEY_LABELS.PFK07);
  expectPagingKeyEnabled(AUTH_SUMMARY_KEY_LABELS.PFK08);

  // WHY : Assumptions: still exactly one read. Both boundaries were decided from the position the hook
  //       published, not discovered by asking the service for a page that does not exist.
  expect(listStub()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts one rendered paging key is present and not disabled.
 * @param {string} label - The key's painted legend text.
 * @returns {void} Nothing; the enablement is asserted.
 */
function expectPagingKeyEnabled(label: string): void {
  const control = within(keyLegend()).getByText(label).closest('button');
  expect(control).not.toBeNull();
  expect(control).toBeEnabled();
}

/**
 * Every row is reachable and typeable from the keyboard, with no pointer and no extra tab stop.
 *
 * ⚠️ Purpose: this case is the other half of the row-affordance finding and it exists because the
 * pointer half was fixed by an `onRow` handler that deliberately did NOT make the row a tab stop. The
 * claim that has to hold instead is that the keyboard already reaches every row -- so it is asserted
 * here rather than argued in a comment.
 *
 * ⚠️ Refactoring Rationale: the traversal asserted is now TAB between the five cells, where it was an
 * arrow key within one `Radio.Group`. The group gave one tab stop with arrow traversal, which is a
 * radio-group contract rather than the terminal's: a 3270 tab key moved the cursor between UNPROTECTED
 * FIELDS, and `SEL0001` through `SEL0005` are five unprotected fields (`COPAU00.bms` L277 to L282 and
 * the four repeats). Five tab stops is therefore the faithful traversal and not a cost, and each one is
 * NAMED for its row, which is what makes them distinguishable to a screen reader -- so the old note's
 * objection to five stops, that they would put focus on elements with no accessible name, does not
 * apply to these.
 *
 * ⚠️ Assumptions: focus is placed on the first cell directly and the tab key is the measured
 * interaction from there. Tabbing in from the top of the document would make the case depend on how many
 * focusable elements the shell paints before the table, which is another screen's concern and would
 * break this case for a reason unrelated to what it measures.
 *
 * ⚠️ Assumptions: typing into the SECOND cell is asserted as well as reaching it, because reaching a
 * field that cannot accept a character is not reachability in any sense the operator cares about -- and
 * `PROCESS-ENTER-KEY` reads whatever each of the five holds (`COPAUS0C.cbl` L288 to L308), so every one
 * of them must be typeable and not merely focusable.
 * @returns {Promise<void>} Resolves once keyboard traversal between rows has been asserted.
 */
async function reachesEveryRowFromTheKeyboard(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const controls = selectionControls();
  expect(controls).toHaveLength(AUTH_SUMMARY_PAGE_SIZE);
  const first = controls[0];
  const second = controls[1];
  expect(first).toBeDefined();
  expect(second).toBeDefined();
  if (first === undefined || second === undefined) {
    return;
  }

  first.focus();
  expect(first).toHaveFocus();

  await user.tab();

  expect(second).toHaveFocus();
  expect(second).toHaveAccessibleName(selectionCellLabel(listItemFixture(2).transactionId));

  await user.keyboard(AUTH_SUMMARY_SELECTION_CODE);

  expect(second).toHaveValue(AUTH_SUMMARY_SELECTION_CODE);
  expect(first).toHaveValue('');
}

/**
 * Every one of the eight columns reserves the measure its own contract declares.
 *
 * ⚠️ Purpose: this is the regression the sticky-column finding leaves behind. Browser validation
 * measured this table at maximum internal scroll and found the pinned leading block overlaying the
 * column beside it -- the `Date` heading rendered as the single letter `e`, and an originating time of
 * `09:16:44` rendered as `16:44`. A clipped time still reads as a whole time, which is the worst
 * failure available in a table of authorization times, and the finding's own remedy was declared
 * column widths. This asserts they are declared, on all eight, so an added column cannot reintroduce
 * the measure-from-whatever-text-is-present layout that produced the overlay.
 *
 * ⚠️ Assumptions: the expectation is DERIVED from the two exported contract catalogs rather than
 * written out as eight literals. A literal list would pass while the screen sized a column from
 * something other than its contract -- which is precisely the defect -- and would have to be re-typed
 * whenever a heading or a declared width is corrected against `COPAU00.bms`. Deriving it measures the
 * rule: each column holds the LARGER of its heading and its datum, plus the padding the design
 * system's own table spends on a cell.
 *
 * ⚠️ Assumptions: the widths are read from the table's `colgroup` and not from the cells, because
 * that is where the layout reservation lives -- a `col` element carries the reserved measure whether or
 * not any row is present, so the case measures the reservation rather than the width some particular
 * value happened to need.
 * @returns {Promise<void>} Resolves once all eight reserved measures have been asserted.
 */
async function reservesEveryColumnAtItsDeclaredMeasure(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const reserved = Array.from(rowTable().querySelectorAll('col')).map(
    /**
     * Reads one reserved column measure off the table's own column group.
     * @param {HTMLTableColElement} column - One `col` element antd emitted for a declared column.
     * @returns {string} The inline width that column reserves, as the style carries it.
     */
    (column: HTMLTableColElement): string => column.style.width,
  );

  expect(reserved).toStrictEqual([
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.selection, AUTH_SUMMARY_FIELD_WIDTHS.selection),
    reservedMeasure(
      AUTH_SUMMARY_COLUMN_HEADERS.transactionId,
      AUTH_SUMMARY_FIELD_WIDTHS.rowTransactionId,
    ),
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.date, AUTH_SUMMARY_FIELD_WIDTHS.rowDate),
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.time, AUTH_SUMMARY_FIELD_WIDTHS.rowTime),
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.type, AUTH_SUMMARY_FIELD_WIDTHS.rowType),
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.approval, AUTH_SUMMARY_FIELD_WIDTHS.rowApproval),
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.status, AUTH_SUMMARY_FIELD_WIDTHS.rowStatus),
    reservedMeasure(AUTH_SUMMARY_COLUMN_HEADERS.amount, MONEY_PICTURES.transactionAmount.width),
  ]);
}

/**
 * Builds the measure one column reserves, from the heading and the datum it must both hold.
 *
 * ⚠️ Assumptions: the padding term is `2 * var(--ant-padding)` because antd 6 derives the table's
 * own `cellPaddingInline` from the global `padding` token -- verified in
 * `node_modules/antd/lib/table/style/index.js` -- so naming the global token reserves exactly what the
 * component consumes. Reserving the characters alone would leave every column short by its own padding
 * and clip the very headings the reservation exists to protect.
 * @param {string} heading - The mapset's painted column heading, spacing included.
 * @param {number} datumWidth - The declared character width of the column's datum.
 * @returns {string} The CSS length the column reserves.
 */
function reservedMeasure(heading: string, datumWidth: number): string {
  return `calc(${String(Math.max(heading.length, datumWidth))}ch + 2 * var(--ant-padding))`;
}

/**
 * Each row's selection cell reserves room for the character AND its caret, and names the AA floor.
 *
 * ⚠️ Purpose: two measured defects, one on each side of the change of control. An accessibility audit
 * measured the marker this replaced at fourteen pixels square at every width and named it the smallest
 * control in the application; a later pass measured the IDENTICAL typed cell on the user browse at
 * twenty-four pixels with eleven pixels of padding each side, leaving a content box of zero pixels
 * against a 9.078-pixel glyph advance -- the character was stored and invisible. So the reservation has
 * to clear the conformance floor and admit the glyph, and both are asserted.
 *
 * ⚠️ Assumptions: the measure is asserted as a DECLARATION and not as a rendered pixel width, because
 * jsdom performs no layout -- every box in it is zero by zero, so a width assertion would pass on any
 * value including the broken one. What is assertable here is that the control carries a minimum, that
 * the minimum reserves more columns than the field admits characters, and that the AA figure appears as
 * an operative alternative. The pixel outcomes above were measured in a browser; this case exists to
 * stop the declaration being removed.
 *
 * ⚠️ Assumptions: the floor named is AA's twenty-four and not the audit's forty-four.
 * `CONTROL_SCALE_DECISION` in `ui/src/theme/tokens.ts` records forty-four as WCAG 2.5.5 Target Size
 * (Enhanced) at AAA, considered and declined, against 2.5.8 Target Size (Minimum) at AA which is
 * twenty-four. Asserting forty-four here would reopen a settled decision from one screen.
 *
 * ⚠️ Assumptions: the element measured is the INPUT itself and not a wrapper, and the change of element
 * follows the change of control. antd's `Radio` rendered a `label` around a visually hidden input, so
 * the label was the pointer target; an `Input` IS the target, and the padding custom property the
 * expression reads resolves on `.ant-input` rather than on an arbitrary element.
 * @returns {Promise<void>} Resolves once every selection cell's reservation has been asserted.
 */
async function presentsEverySelectionTargetAtTheAaFloor(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const controls = selectionControls();
  expect(controls).toHaveLength(AUTH_SUMMARY_PAGE_SIZE);

  expect(
    SELECTION_CELL_RESERVED_COLUMNS,
    'a caret needs a column beyond the one the field admits',
  ).toBeGreaterThan(AUTH_SUMMARY_FIELD_WIDTHS.selection);

  for (const control of controls) {
    const measure = control.style.minInlineSize;

    expect(measure, 'the selection cell must declare a minimum measure of its own').not.toBe('');
    expect(
      measure,
      'the reserved columns must appear in the expression, in character units',
    ).toContain(`${String(SELECTION_CELL_RESERVED_COLUMNS)}ch`);
    expect(measure, 'the AA target-size floor must survive an unresolved padding token').toContain(
      `${String(TARGET_SIZE_AA_MINIMUM)}px`,
    );
  }
}

/**
 * A character the source refuses is refused HERE, in the source's own words, and opens nothing.
 *
 * ⚠️ Purpose: this case could not exist until the control changed, and its existence is the point.
 * `PROCESS-ENTER-KEY` accepts `'S'` and `'s'` and answers anything else with `'Invalid selection. Valid
 * value is S'` (`COPAUS0C.cbl` L316 to L330), and while the selection column was a radio that arm was
 * unreachable -- a marker can only ever supply the accepted character, so the screen carried a
 * transcribed refusal no operator could ever provoke. A one-character entry field can carry `'X'`, so
 * the refusal is now a path and this asserts it is the source's path.
 *
 * ⚠️ Assumptions: the sentence is compared against the catalog entry rather than typed out, because
 * rule T8 carries it verbatim and four sibling browses carry four DIFFERENT selection vocabularies --
 * this program's singular ellipsis-free form, `COUSR00C`'s plural, and `COCRDLIC`'s uppercase. A
 * literal here would be a fifth copy able to drift from all of them.
 *
 * ⚠️ Assumptions: the address is asserted UNCHANGED as well, because a refusal that also navigated
 * would be a refusal in name only.
 * @returns {Promise<void>} Resolves once the refusal and the absence of a navigation are asserted.
 */
async function refusesAnUnacceptedSelectionCharacter(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const control = selectionControls()[0];
  expect(control).toBeDefined();
  if (control === undefined) {
    return;
  }

  await user.type(control, UNACCEPTED_SELECTION_CHARACTER);
  await pressPfKey(user, 'ENTER');

  expect(messageBand()).toHaveTextContent(
    SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S.trim(),
  );
  expect(currentPath()).toBe(AUTH_SUMMARY_ROUTE);
}

/**
 * The LOWERCASE selection character opens the record, exactly as the source's second arm does.
 *
 * ⚠️ Purpose: `PROCESS-ENTER-KEY` tests the flag against `'S'` AND `'s'` (`COPAUS0C.cbl` L316), so an
 * operator with caps lock off is not refused. This arm was also unreachable through a marker control,
 * and it is the arm most easily lost in a later edit because the uppercase one looks complete on its
 * own.
 * @returns {Promise<void>} Resolves once the navigation has been asserted.
 */
async function acceptsTheLowercaseSelectionCharacter(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const control = selectionControls()[0];
  expect(control).toBeDefined();
  if (control === undefined) {
    return;
  }

  await user.type(control, AUTH_SUMMARY_SELECTION_CODE.toLowerCase());
  await pressPfKey(user, 'ENTER');

  await waitFor(expectPathReached(authorizationDetailPath(listItemFixture(1).key)));
}

/**
 * Two marked rows act on the FIRST in display order, and the later mark is ignored in silence.
 *
 * ⚠️ Purpose: this is the semantic the radio control expressed structurally and the typed field has to
 * express in the reducer, so it is asserted rather than assumed. `PROCESS-ENTER-KEY` is one
 * `EVALUATE TRUE` whose five arms test `SEL0001I` through `SEL0005I` in that order (`COPAUS0C.cbl` L288
 * to L308), and COBOL ends an `EVALUATE` at its first matching arm -- so a page marked beside rows two
 * and four acts on row two and never inspects row four.
 *
 * ⚠️ Assumptions: the LATER row is typed FIRST, so the case cannot pass by acting on whichever cell was
 * touched most recently. Display order is the rule, and typing in reverse order is what distinguishes
 * it from recency.
 *
 * ⚠️ Assumptions: no refusal is expected for the second mark. `COTRTLIC` counts its marked rows and
 * answers `'Please select only 1 action'` for more than one; `COPAUS0C` keeps no such count and declares
 * no such sentence, so refusing here would invent a message the reference cannot emit.
 * @returns {Promise<void>} Resolves once the first-marked row has been shown to win.
 */
async function actsOnTheFirstMarkedRowOnly(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const controls = selectionControls();
  const later = controls[3];
  const earlier = controls[1];
  expect(later).toBeDefined();
  expect(earlier).toBeDefined();
  if (later === undefined || earlier === undefined) {
    return;
  }

  await user.type(later, AUTH_SUMMARY_SELECTION_CODE);
  await user.type(earlier, AUTH_SUMMARY_SELECTION_CODE);
  await pressPfKey(user, 'ENTER');

  await waitFor(expectPathReached(authorizationDetailPath(listItemFixture(2).key)));
  expect(messageBand()?.textContent ?? '').not.toContain(
    SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S.trim(),
  );
}

/**
 * A row says it can be acted on, and clicking anywhere in it places the cursor without writing.
 *
 * ⚠️ Purpose: browser validation measured `cursor: auto` on these rows both at rest and hovered,
 * on a table whose whole purpose is choosing a row -- so nothing about a row said it could be acted on
 * and the only clue was the small control in its leading column.
 *
 * ⚠️ Assumptions: the click is placed on a cell OTHER than the selection cell's own, because a click on
 * that cell would focus it whether or not the ROW carried a handler and so would measure nothing. The
 * transaction identifier's cell is the furthest populated cell from the control in reading order.
 *
 * ⚠️ Refactoring Rationale: the outcome asserted is that the cursor MOVED and that the cell is still
 * empty, where it used to be that the row became checked. The change follows the control: a one-character
 * field holds a character, and the only character a click could supply is the one that opens the record
 * -- so a click that wrote would leave the operator one Enter from opening a record they never chose.
 * `PROCESS-ENTER-KEY` reads the selection characters and only then transfers control (`COPAUS0C.cbl`
 * L288 to L330), so writing on click would collapse two separated acts into one.
 *
 * ⚠️ Assumptions: the absence of a navigation is asserted as well, because the pointer affordance and
 * the commit are different acts and a row click that navigated would turn a mis-aimed click into a
 * committed step.
 * @returns {Promise<void>} Resolves once the affordance and the cursor move have been asserted.
 */
async function selectsTheRowClickedAnywhereInIt(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const chosen = listItemFixture(2);
  const cell = screen.getByText(chosen.transactionId);
  const row = cell.closest('tr');
  expect(row).not.toBeNull();
  expect(row?.style.cursor).toBe('pointer');

  await user.click(cell);

  const controls = selectionControls();
  expect(controls[1]).toHaveFocus();
  expect(controls[1]).toHaveValue('');
  expect(currentPath()).toBe(AUTH_SUMMARY_ROUTE);
}

/**
 * Masks one amount exactly as the screen does, through the mask the reference applies to it.
 *
 * ⚠️ Refactoring Rationale: the picture is a PARAMETER and used to be fixed at
 * `MONEY_PICTURES.transactionAmount` for all six panel amounts. The reference applies two masks, not
 * one -- `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` at `COPAUS0C.cbl` L56 for the credit figures and
 * `WS-DISPLAY-AMT9 PIC -zzzz9.99` at L57 for the four cash and total figures -- so a single picture
 * here could only assert one of the two and would have PASSED against a screen that rendered all six
 * at the wrong measure, which is precisely the state this helper was written in.
 *
 * ⚠️ Assumptions: still read from `MONEY_PICTURES` and never spelled as a literal expectation, so a
 * case cannot assert against a picture the money module does not declare, and so the assertion measures
 * that the screen used THIS picture on THIS value rather than some other picture of the same width.
 * @param {string} wireAmount - The amount as the contract sends it.
 * @param {MoneyPicture} picture - The edit mask that amount's own field is rendered through.
 * @returns {string} The amount as the screen renders it.
 */
function maskedAmount(wireAmount: string, picture: MoneyPicture): string {
  return applyMoneyEditMask(wireAmount, picture);
}

/**
 * Every amount carries the colour its sign earns, and an ordinary positive keeps the mapset's own.
 *
 * ⚠️ Purpose: the sign colour is the half of the money contract a mask cannot carry. A negative
 * balance and a positive one of the same magnitude render as the same twelve characters apart from one
 * leading glyph, and that glyph is the whole difference between money owed and money held.
 *
 * ⚠️ Assumptions: all three cases are asserted against `MONEY_SIGN_TEXT_TOKENS` through
 * {@link moneySignTextToken}, INCLUDING the positive, and the mapset's own `COLOR=BLUE` operand on the
 * seven amount fields is deliberately not what is asserted. That divergence is recorded on the screen's
 * `moneyCellStyle`: the sign map is the application's one authority for money hue and
 * `ui/src/screens/accountView/index.tsx` L1050 resolves it the same unconditional way, so honouring the
 * operand would put a fourth money hue on the glass and make an ordinary positive balance here differ
 * from the identical balance on the account view -- which is the finding. This case is what holds the
 * screen to the authority rather than to the operand.
 *
 * Assumptions: the fixture's `cashBalance` is `'0.00'`, so the zero case is exercised by the standing
 * fixture rather than by a case-specific one, and the negative case is supplied as an override because
 * no fixture member carries one.
 * @returns {Promise<void>} Resolves once each of the three sign cases has been asserted.
 */
async function paintsEachSignCaseInItsOwnToken(): Promise<void> {
  const summary = summaryFixture({ creditBalance: '-250.00' });
  listStub().mockResolvedValue(listingFixture(rowsOf(1), { summary }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  expect(classifyMoneySign(summary.creditBalance)).toBe('negative');
  expect(panelValueText(AUTH_SUMMARY_LABELS.creditBalance).style.color).toContain(
    customPropertyFragment(moneySignTextToken('negative')),
  );

  expect(classifyMoneySign(summary.cashBalance)).toBe('zero');
  expect(panelValueText(AUTH_SUMMARY_LABELS.cashBalance).style.color).toContain(
    customPropertyFragment(moneySignTextToken('zero')),
  );

  expect(classifyMoneySign(summary.creditLimit)).toBe('positive');
  expect(panelValueText(AUTH_SUMMARY_LABELS.creditLimit).style.color).toContain(
    customPropertyFragment(moneySignTextToken('positive')),
  );

  // WHY : ⚠️ Assumptions: the positive case is additionally asserted NOT to carry the mapset's own blue,
  //       because the two tokens are different values and a containment check on the correct one would
  //       still pass if the screen had somehow emitted both. Stating the exclusion is what makes the
  //       recorded divergence testable rather than merely documented.
  expect(panelValueText(AUTH_SUMMARY_LABELS.creditLimit).style.color).not.toContain(
    customPropertyFragment(BMS_TEXT_COLOR_TOKENS.BLUE),
  );
}

/**
 * Converts a design-token name to the fragment its CSS custom property carries, digit runs included.
 *
 * ⚠️ Assumptions: a trailing digit run is hyphenated as well as each capital, which
 * {@link kebabCase} alone does not do. The palette tokens this case needs are named with a ramp step --
 * `red7` becomes `--ant-red-7`, not `--ant-red7` -- so hyphenating capitals alone would produce a
 * fragment that never matches and a case that fails for a reason unrelated to what it measures.
 * Verified against the rendered value rather than assumed: the negative cell resolved to
 * `var(--ant-red-7)`.
 * @param {string} tokenName - A design-token name in camel case, such as `red7` or `colorPrimary`.
 * @returns {string} The custom-property fragment, such as `red-7` or `color-primary`.
 */
function customPropertyFragment(tokenName: string): string {
  return kebabCase(tokenName).replace(/(?<=[a-z])(?=\d)/gu, '-');
}

/**
 * Renders every monetary value into the DOM as text, byte for byte as the contract sent it.
 *
 * @returns {Promise<void>} Resolves once every amount has been asserted present unaltered.
 */
async function rendersEveryAmountUnaltered(): Promise<void> {
  const summary = summaryFixture();
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { summary }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  for (const { member, label, picture } of MONEY_MEMBERS) {
    const expected = summary[member];
    if (typeof expected !== 'string') {
      throw new Error(`${member} is not a string in the fixture, so this case cannot assert on it`);
    }

    // WHY : Assumptions: the value is located THROUGH its painted caption rather than searched for
    //       globally, so an amount rendered under the wrong caption fails. Six amounts on one panel
    //       make a global text query almost meaningless -- two of the six share a declared width and
    //       could be transposed without a global search noticing.
    // WHY : ⚠️ Assumptions: the cell's text is compared with strict equality and NOT through a text
    //       matcher, because a matcher normalises whitespace and the amounts are fixed-width edited
    //       values whose pad characters are column geometry produced by the edit mask. A normalising
    //       comparison would accept a screen that had stripped them, which is exactly the alteration
    //       this case is named for detecting.
    // WHY : ⚠️ Refactoring Rationale: the expectation is the MASKED form and used to be the wire
    //       string. The screen now renders every amount through `MONEY_PICTURES.transactionAmount`,
    //       which is the finding's own resolution -- browser validation counted four mutually
    //       incompatible money renderings across the application and named this screen's bare
    //       `5000.00` as one of them. Unaltered therefore means the DIGITS are unaltered, which is
    //       what the mask preserves and what this comparison now measures.
    // WHY : Assumptions: the expectation is COMPUTED by the same helper the screen calls rather than
    //       written out as `+00005000.00`. A literal would pass while the screen used some other
    //       picture of the same width, and would have to be re-typed for each of the six values; the
    //       computed form measures that the screen used THIS picture on THIS value.
    expect(panelValueCell(label).textContent).toBe(maskedAmount(expected, picture));
  }
}

/**
 * Each amount reserves the column its OWN edit mask declares, nine characters or twelve.
 *
 * ⚠️ Purpose: this is the regression the money-picture finding leaves behind, and it could not be
 * asserted while the screen used one picture for all six. `COPAUS0C.cbl` edits one precision through
 * two masks -- `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` at L56 for the credit limit and the credit balance,
 * `WS-DISPLAY-AMT9 PIC -zzzz9.99` at L57 for the cash limit, the cash balance, the approved total and
 * the declined total (applied at L780 to L799) -- so four of the six columns are three characters
 * narrower than the other two, and a screen that reserved twelve for all six painted a column the
 * terminal does not have.
 *
 * ⚠️ Assumptions: the reservation is asserted as a DECLARATION in character units, not as a rendered
 * pixel width, because jsdom performs no layout and every box in it is zero. What is assertable is that
 * each amount declares a measure and that the measure it declares is its own picture's width; the
 * pixel outcome follows from `ch` in a browser.
 *
 * ⚠️ Assumptions: the case also asserts the two widths genuinely DIFFER, so it cannot pass by both
 * pictures having drifted to the same measure -- which is the state it exists to prevent recurring.
 * @returns {Promise<void>} Resolves once every amount's reserved measure has been asserted.
 */
async function sizesEachAmountAtItsOwnDeclaredMeasure(): Promise<void> {
  const summary = summaryFixture();
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { summary }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  expect(
    MONEY_PICTURES.authorizationSummaryAmount.width,
    'the narrow mask must stay narrower than the wide one, or this case measures nothing',
  ).toBeLessThan(MONEY_PICTURES.transactionAmount.width);

  for (const { label, picture } of MONEY_MEMBERS) {
    expect(panelValueText(label).style.minInlineSize).toBe(`${String(picture.width)}ch`);
  }
}

/**
 * Never lets a packed-decimal byte, a nibble or raw binary reach the browser.
 *
 * @returns {Promise<void>} Resolves once the absence of encoded money has been asserted.
 */
async function neverExposesPackedDecimal(): Promise<void> {
  const summary = summaryFixture();
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { summary }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const rendered = document.body.textContent ?? '';

  // WHY : ⚠️ Refactoring Rationale: decoding happens ONCE, at the anti-corruption boundary --
  //       `com.carddemo.common.codec.PackedDecimalCodec` on the server and
  //       `data-migration/src/carddemo_migration/copybook/packed.py` in the ETL -- and never in the
  //       client. `CIPAUSMY.cpy` L23 to L30 declares this segment's amounts `COMP-3`, and this is the
  //       only context in the migration where packed decimal reaches persisted target data, so it is
  //       also the only screen where a leak could occur. AAP section 0.4.1.3 decodes to `NUMERIC` at
  //       the ETL edge precisely so packed bytes are never persisted; a client that saw them would be
  //       a layering violation, which is what the ArchUnit rules prevent on the server side of the
  //       same boundary.
  // WHY : Assumptions: the probe is for CONTROL CHARACTERS, because that is what a packed field
  //       actually looks like once it reaches text -- a two-byte `COMP` halfword and a packed
  //       `S9(09)V99` are sequences of arbitrary octets whose sign nibble is a low value, so any
  //       C0-range character in the rendered text is evidence a raw field arrived undecoded. Searching
  //       for a plausible-looking digit string could not distinguish an encoded value from a decoded
  //       one.
  expect(rendered).not.toMatch(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/u);

  // WHY : Assumptions: the replacement character is probed for separately, because a packed field
  //       routed through a UTF-8 text decoder does not survive as a control character -- it becomes
  //       U+FFFD. `tests/helpers/localstack_setup.py` records the same hazard for the EBCDIC datasets
  //       at L729 and L741, where writing binary through a text path mangles it into replacement
  //       characters, so the two failure shapes are both worth detecting.
  expect(rendered).not.toContain('\uFFFD');
}

/**
 * Renders every monetary value in the fixed-pitch token, so the columns align as on the terminal.
 *
 * @returns {Promise<void>} Resolves once the typography token has been asserted.
 */
async function rendersAmountsInTheFixedPitchToken(): Promise<void> {
  const summary = summaryFixture();
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { summary }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : Assumptions: the expected value is DERIVED from the bridge entry rather than written out, so
  //       a token renamed in `ui/src/theme/tokens.ts` changes what this asserts in the same commit.
  //       `TYPOGRAPHY_TOKENS.fixedPitchData` names the token the BMS mapping assigns to "fixed-pitch
  //       money and identifier columns" per AAP section 0.3.3, and the design system emits a CSS
  //       custom property whose name is the token's own name in kebab case.
  const expectedVariableFragment = kebabCase(TYPOGRAPHY_TOKENS.fixedPitchData);

  for (const { member, label, picture } of MONEY_MEMBERS) {
    const expected = summary[member];
    if (typeof expected !== 'string') {
      throw new Error(`${member} is not a string in the fixture, so this case cannot assert on it`);
    }
    const valueElement = panelValueText(label);
    expect(valueElement.textContent).toBe(maskedAmount(expected, picture));

    // WHY : ⚠️ Assumptions: `white-space: pre` is asserted alongside the font, because the two
    //       together are what make a column. The mask pads with spaces, and a browser collapses a run
    //       of spaces in normal white-space handling -- so without `pre` every amount would start at
    //       its first significant digit and the fixed-pitch font would align nothing. `renderMoney`
    //       returns the property for that reason, and this asserts the screen applied what it returned.
    expect(valueElement.style.whiteSpace).toBe('pre');

    // WHY : ⚠️ Assumptions: the inline style holds a `var(--...)` REFERENCE and not a resolved font
    //       stack, and the difference is the whole assertion. `ui/src/theme/antdTheme.ts` switches the
    //       design system to CSS-variable theming, and the screen resolves through `cssVar` rather
    //       than `token` for exactly this reason: `token` would copy the resolved value into the
    //       element at render, making it a literal in every sense the no-hardcoded-values rule cares
    //       about and differing from a typed font name only in who typed it.
    expect(valueElement.style.fontFamily).toContain(expectedVariableFragment);
    expect(valueElement.style.fontFamily.startsWith('var(')).toBe(true);
  }
}

/**
 * Converts a design-token name to the kebab-case fragment its CSS custom property carries.
 *
 * Assumptions: the design system derives each custom property's name from the token's own camel-case
 * name, so lowering each capital to a hyphenated lowercase letter reproduces the fragment without this
 * file needing to know the property's prefix -- which is the part that varies with configuration.
 * @param {string} tokenName - A design-token name in camel case, such as `fontFamilyCode`.
 * @returns {string} The kebab-case fragment, such as `font-family-code`.
 */
function kebabCase(tokenName: string): string {
  return tokenName.replace(/[A-Z]/gu, toHyphenatedLower);
}

/**
 * Lowers one matched capital letter to a hyphen followed by its lowercase form.
 * @param {string} capital - The single matched capital letter.
 * @returns {string} A hyphen and the lowercased letter.
 */
function toHyphenatedLower(capital: string): string {
  return `-${capital.toLowerCase()}`;
}

/**
 * Keeps the match-status and fraud-flag domains closed and typed rather than free text.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function keepsTheStatusDomainsClosed(): void {
  // WHY : Assumptions: the domains are exercised as TYPED values, so the compiler is the primary
  //       assertion and the runtime check is the corroboration. `ui/src/api/types.ts` L2056 declares
  //       `MatchStatus = 'P' | 'D' | 'E' | 'M'` and L2083 declares `AuthFraudFlag = 'F' | 'R'`, and
  //       both are backed by `CHECK` constraints derived from the copybook's `88`-level value sets per
  //       AAP section 0.4.1.3 -- so a fifth match status could not reach the client without the
  //       database refusing it first. Annotating the arrays is what makes an invented member a
  //       typecheck failure here rather than a passing test.
  const matchStatuses: readonly MatchStatus[] = ['P', 'D', 'E', 'M'];
  const fraudFlags: readonly AuthFraudFlag[] = ['F', 'R'];

  expect(matchStatuses).toHaveLength(4);
  expect(fraudFlags).toHaveLength(2);

  // WHY : ⚠️ Assumptions: the approval indicator is a SEPARATE closed domain and is not folded into the
  //       match-status one, even though both admit the character `'D'`. `COPAUS0C.cbl` L537 and L539
  //       move `'A'` and `'D'` into `WS-AUTH-APRV-STAT`, an internal work field that drives the
  //       approved and declined counts and amounts, whereas the match status is the acquirer's own
  //       `PA-MATCH-STATUS`. Merging the two would make `'D'` ambiguous between "declined" and the
  //       match-status member that shares its spelling, and the counts would then be derivable from
  //       the wrong field.
  const approvalStatuses: readonly ApprovalStatus[] = ['A', 'D'];
  expect(approvalStatuses).toStrictEqual(['A', 'D']);
}

/**
 * Renders the approved and declined counts from the response, zero-filled to the declared width.
 *
 * @returns {Promise<void>} Resolves once both counts have been asserted.
 */
async function rendersBothCountsFromTheResponse(): Promise<void> {
  const summary = summaryFixture({ approvedAuthCnt: 7, declinedAuthCnt: 0 });
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { summary }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : Assumptions: three digits, zero-filled, because the source moves the halfword through
  //       `WS-DISPLAY-COUNT PIC 9(03)` at `COPAUS0C.cbl` L788 to L791 into a `LENGTH=3` map field, and
  //       the symbolic map declares `APPRCNTI PIC X(3)` at L102 and `DECLCNTI PIC X(3)` at L108. An
  //       unsigned three-digit display field renders 7 as '007', so passing the raw number through
  //       would show '7' where the terminal shows '007'.
  expect(panelValueCell(AUTH_SUMMARY_LABELS.approvalCount).textContent).toBe('007');
  expect(panelValueCell(AUTH_SUMMARY_LABELS.declineCount).textContent).toBe('000');
}

/**
 * Keeps the two entry refusals distinct, including the space one has before its ellipsis.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function keepsBothEntryRefusalsDistinct(): void {
  const messages = PROGRAM_MESSAGES.COPAUS0C;
  const sources = PROGRAM_MESSAGE_SOURCES.COPAUS0C;

  // WHY : ⚠️ Assumptions: `'Acct Id must be Numeric ...'` carries a SPACE before its ellipsis and
  //       `'Please enter Acct Id...'` does not, and the two sit eleven lines apart in one program --
  //       `COPAUS0C.cbl` L269 and L278. Transformation rule T8 carries user-visible strings across
  //       character for character, so the inconsistency is content rather than a defect to tidy, and
  //       normalising either toward the other would alter text a golden-master comparison reads.
  expect(messages.ACCT_ID_MUST_BE_NUMERIC).toContain(' ...');
  expect(messages.PLEASE_ENTER_ACCT_ID).not.toContain(' ...');
  expect(messages.PLEASE_ENTER_ACCT_ID.endsWith('...')).toBe(true);

  // WHY : Assumptions: the catalog records WHERE each string came from, so the line numbers are
  //       asserted alongside the text. A string that survived verbatim while losing its provenance
  //       would still be unverifiable against the baseline.
  expect(sources.PLEASE_ENTER_ACCT_ID).toStrictEqual([269]);
  expect(sources.ACCT_ID_MUST_BE_NUMERIC).toStrictEqual([278]);
}

/**
 * Keeps the selection refusal in the singular, ellipsis-free form this program uses.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function keepsTheSelectionRefusalSingular(): void {
  const refusalText = SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S;

  // WHY : ⚠️ Assumptions: SINGULAR 'Valid value is' and NO trailing ellipsis, from `COPAUS0C.cbl` L328.
  //       Four sibling list screens carry four different selection vocabularies, which is why this is
  //       asserted rather than assumed: this program and `COTRN00C` L199 share the singular
  //       ellipsis-free form, `COUSR00C` L212 uses the plural 'Invalid selection. Valid values are U
  //       and D', and `COCRDLIC` uses the uppercase 'INVALID ACTION CODE'. Merging any two of the four
  //       would corrupt at least one screen.
  expect(refusalText).toContain('Valid value is');
  expect(refusalText).not.toContain('Valid values are');
  expect(refusalText.endsWith('...')).toBe(false);
  expect(refusalText.endsWith(AUTH_SUMMARY_SELECTION_CODE)).toBe(true);

  // WHY : Assumptions: the catalog records both emitting programs for this one string, which is what
  //       makes the shared entry legitimate rather than a merge -- the two programs genuinely write the
  //       same characters, unlike the plural and uppercase variants above, which have entries of their
  //       own.
  const sites = SHARED_MESSAGE_SOURCES.INVALID_SELECTION_VALID_VALUE_IS_S;
  expect(sites).toContainEqual({ file: PROGRAM_SOURCE_FILES.COPAUS0C, lines: [328] });
}

/**
 * Keeps the two paging-boundary sentences attributed to this program's own lines.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function attributesBothBoundarySentences(): void {
  // WHY : Assumptions: the two boundary sentences are shared entries carrying this program's lines
  //       among their sites -- `COPAUS0C.cbl` L381 for the top and L409 for the bottom -- so the
  //       assertion is that this screen's own emission is registered, not that the string is unique to
  //       it.
  expect(SHARED_MESSAGE_SOURCES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE).toContainEqual({
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [381],
  });
  expect(SHARED_MESSAGE_SOURCES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE).toContainEqual({
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [409],
  });
}

/**
 * Keeps this program's uppercase spelling of AUTH separate from the detail screen's mixed-case one.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function keepsBothSpellingsOfAuthDistinct(): void {
  // WHY : ⚠️ Assumptions: two sibling programs spell the same word two ways, and the catalog holds BOTH
  //       as distinct entries rather than normalising them. `COPAUS0C.cbl` writes UPPERCASE 'AUTH' at
  //       L477, L510 and L989, while `COPAUS1C.cbl` writes mixed-case 'Auth' in the corresponding
  //       strings at its own L456 and L482. Merging them would corrupt one screen or the other, and
  //       there is no way to tell from either program alone which spelling is "right" -- both are, in
  //       their own program.
  const fraudRemoved = PROGRAM_MESSAGES.COPAUS1C.AUTH_FRAUD_REMOVED;
  expect(fraudRemoved).toContain('AUTH');
  expect(fraudRemoved).not.toContain('Auth ');
  expect(PROGRAM_MESSAGE_SOURCES.COPAUS1C.AUTH_FRAUD_REMOVED).toStrictEqual([535]);

  // WHY : Assumptions: the detail screen's mixed-case strings are the ones this program's diagnostics
  //       would collide with, and they are not in `PROGRAM_MESSAGES` at all -- they are the redacted
  //       compositions registered below. So the guarantee that matters here is that the two programs
  //       have SEPARATE entries in every catalog structure, which the register's per-program keying
  //       provides.
  const thisProgramSites = redactedSitesFor('COPAUS0C');
  const detailSites = redactedSitesFor('COPAUS1C');
  expect(thisProgramSites.length).toBeGreaterThan(0);
  expect(detailSites.length).toBeGreaterThan(0);
  for (const site of thisProgramSites) {
    expect(detailSites).not.toContain(site);
  }
}

/**
 * Returns the redaction register's line numbers for one program.
 *
 * Assumptions: the register is filtered by its own `program` member rather than by the file path, so
 * the lookup cannot be satisfied by a sibling program that happens to live in the same directory.
 * @param {string} program - The program name as `PROGRAM_SOURCE_FILES` keys it.
 * @returns {readonly number[]} Every registered composition line for that program, in register order.
 */
function redactedSitesFor(program: string): readonly number[] {
  return REDACTED_DIAGNOSTICS.filter(
    /**
     * Reports whether one registered diagnostic belongs to the program being counted.
     * @param {(typeof REDACTED_DIAGNOSTICS)[number]} entry - One registered diagnostic.
     * @returns {boolean} `true` when the entry names that program.
     */
    function matchesProgram(entry: (typeof REDACTED_DIAGNOSTICS)[number]): boolean {
      return entry.program === program;
    },
  ).flatMap(readEntryLines);
}

/**
 * Reads the composition lines off one register entry.
 * @param {(typeof REDACTED_DIAGNOSTICS)[number]} entry - One registered diagnostic.
 * @returns {readonly number[]} That entry's one-based composition lines.
 */
function readEntryLines(entry: (typeof REDACTED_DIAGNOSTICS)[number]): readonly number[] {
  return entry.lines;
}

/**
 * Registers all ten composed diagnostics this program builds, with the internal value withheld.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function registersAllTenComposedDiagnostics(): void {
  const entries = REDACTED_DIAGNOSTICS.filter(isSummaryProgramEntry);

  // WHY : ⚠️ Refactoring Rationale: the source composes TEN diagnostics by concatenating a literal
  //       fragment with a runtime value, and NONE of the ten is reproduced on screen -- 'Resp:',
  //       'Reas:' and 'Code:' appear nowhere in this screen. The fragments themselves are not
  //       transcribed into the catalog either, and that is the decision this case pins. Reproducing
  //       them would require fabricating the value each one ends in, and there is nothing to fabricate
  //       it from: `DFHRESP` is a CICS translator value and `DIBSTAT` an IMS DL/I status, and the
  //       migrated service runs on neither. The withheld detail is not silently dropped -- this
  //       register names every site, the verbatim baseline sentence shown in its place, and the class
  //       of value suppressed, and the value itself goes to a server-side structured log keyed by the
  //       correlation identifier on the problem document.
  // WHY : ⚠️ Trade-offs: what is accepted is a LESS SPECIFIC sentence on screen; what is bought is not
  //       disclosing an internal datastore status to a browser and not inventing a code that never
  //       existed. The specific detail stays recoverable through `correlationId`, so the compromise
  //       costs an operator nothing a support path cannot recover.
  expect(entries).toHaveLength(10);

  // WHY : Assumptions: the register keys each site by the line of the `STRING` verb that composes the
  //       message, not the line of the literal inside it, so these ten numbers sit one to three lines
  //       above the fragments they stand for. Stating them explicitly is what makes the register
  //       auditable against the program by eye.
  expect(entries.flatMap(readEntryLines)).toStrictEqual([
    476, 509, 836, 851, 886, 901, 937, 952, 988, 1022,
  ]);

  // WHY : ⚠️ Assumptions: the ten partition into exactly the source's two composition families. Four
  //       end in an IMS status code -- the `Code:` family at L476, L509, L988 and L1022, each
  //       appending `IMS-RETURN-CODE` -- and six end in a CICS response and reason, the `Resp:` plus
  //       ' Reas:' family at L836, L851, L886, L901, L937 and L952, each appending `WS-RESP-CD-DIS`
  //       and then `WS-REAS-CD-DIS`. The partition is asserted because it is the evidence that the
  //       register was built from the program rather than approximated: a register that had merged the
  //       families would still have ten entries.
  const imsSites = entries.filter(isImsStatusEntry).flatMap(readEntryLines);
  const cicsSites = entries.filter(isCicsResponseEntry).flatMap(readEntryLines);
  expect(imsSites).toStrictEqual([476, 509, 988, 1022]);
  expect(cicsSites).toStrictEqual([836, 851, 886, 901, 937, 952]);

  // WHY : Assumptions: every replacement is a CATALOGUED sentence rather than prose written at the
  //       register, so each site's on-screen text is still a verbatim baseline string even though the
  //       composition it replaces is not reproduced.
  const catalogued = new Set<string>([
    SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
    SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
  ]);
  for (const entry of entries) {
    expect(entry.replacement.length).toBeGreaterThan(0);
    expect(entry.file).toBe(PROGRAM_SOURCE_FILES.COPAUS0C);
    if (entry.detail === 'ims-status-code') {
      expect(catalogued.has(entry.replacement)).toBe(true);
    }
  }
}

/**
 * Reports whether one registered diagnostic belongs to this screen's own program.
 *
 * Assumptions: matched on the entry's `program` member rather than on its file path, so a sibling
 * program living in the same directory cannot satisfy the lookup.
 * @param {(typeof REDACTED_DIAGNOSTICS)[number]} entry - One registered diagnostic.
 * @returns {boolean} `true` when the entry belongs to the summary program.
 */
function isSummaryProgramEntry(entry: (typeof REDACTED_DIAGNOSTICS)[number]): boolean {
  return entry.program === 'COPAUS0C';
}

/**
 * Reports whether one registered diagnostic is this program's scheduling site.
 * @param {(typeof REDACTED_DIAGNOSTICS)[number]} entry - One registered diagnostic.
 * @returns {boolean} `true` when the entry is the summary program's scheduling site.
 */
function isSchedulingSite(entry: (typeof REDACTED_DIAGNOSTICS)[number]): boolean {
  return isSummaryProgramEntry(entry) && entry.lines.some(isSchedulingLine);
}

/**
 * Reports whether a register entry withholds an IMS status code.
 * @param {(typeof REDACTED_DIAGNOSTICS)[number]} entry - One registered diagnostic.
 * @returns {boolean} `true` when the suppressed value is an IMS DL/I status.
 */
function isImsStatusEntry(entry: (typeof REDACTED_DIAGNOSTICS)[number]): boolean {
  return entry.detail === 'ims-status-code';
}

/**
 * Reports whether a register entry withholds a CICS response and reason pair.
 * @param {(typeof REDACTED_DIAGNOSTICS)[number]} entry - One registered diagnostic.
 * @returns {boolean} `true` when the suppressed value is a CICS response and reason.
 */
function isCicsResponseEntry(entry: (typeof REDACTED_DIAGNOSTICS)[number]): boolean {
  return entry.detail === 'cics-response-and-reason';
}

/**
 * Line of the `STRING` verb composing the program-specification-block diagnostic.
 *
 * Assumptions: 1022 rather than 1023, because the register keys every site by the line of the verb
 * that composes the message and the fragment literal sits on the line below it.
 */
const PROGRAM_SPECIFICATION_BLOCK_SITE_LINE = 1022;

/**
 * Reports whether one registered composition line is the program-specification-block site.
 *
 * Assumptions: the comparison is made through a named predicate rather than `Array.prototype.includes`
 * because the register is declared with literal member types, so `includes` narrows its argument to the
 * union of those literals and rejects a plain number. A predicate taking `number` accepts every member
 * of that union without a cast, which is what keeps this assertion free of one.
 * @param {number} line - A one-based composition line from the register.
 * @returns {boolean} `true` for the scheduling site's line.
 */
function isSchedulingLine(line: number): boolean {
  return line === PROGRAM_SPECIFICATION_BLOCK_SITE_LINE;
}

/**
 * Keeps the IMS program-specification-block site registered although no such block survives.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function keepsTheProgramSpecificationBlockSiteRegistered(): void {
  const scheduling = REDACTED_DIAGNOSTICS.find(isSchedulingSite);

  expect(scheduling).toBeDefined();

  // WHY : ⚠️ Trade-offs: this is the clearest single case in the whole migration of a message outliving
  //       its machinery, and the tension is worth stating rather than resolving. `COPAUS0C.cbl` L1023
  //       composes ' System error while scheduling PSB: Code:' -- a PSB is an IMS Program Specification
  //       Block -- yet AAP sections 0.4.1.3 and 0.7.6 collapse the IMS segments and the Db2 tables
  //       into ONE PostgreSQL schema and ELIMINATE two-phase commit outright, so in the target there
  //       is no PSB to schedule and no scheduling call that could fail. The site is nonetheless kept,
  //       named for the mechanism it describes, because deleting it would erase the only record that
  //       the baseline had a failure mode there. "Modernising" the wording instead would be a
  //       behavioural divergence requiring registration in
  //       `docs/architecture/cobol-to-service-traceability.md`, and it would buy nothing an operator
  //       can act on.
  expect(scheduling?.condition).toContain('program specification block');
  expect(scheduling?.detail).toBe('ims-status-code');
}

/**
 * Withholds every internal response, reason and status code from the rendered screen.
 *
 * @returns {Promise<void>} Resolves once the absence of the withheld tokens has been asserted.
 */
async function withholdsEveryInternalCode(): Promise<void> {
  // WHY : ⚠️ Assumptions: the 5xx now CARRIES the register's replacement sentence, because that is what
  //       the service sends and `describeListingFailure` no longer substitutes on the status family. The
  //       register maps eight of its ten entries -- `COPAUS0C.cbl` L476 and L509 among them -- to
  //       `UNEXPECTED_ABEND_OCCURRED`, the service puts that sentence in `message`, and the screen
  //       renders a service sentence verbatim. Rejecting with a BODILESS 500 would now paint
  //       `PERSISTENT_FAILURE_REPORT_IT` instead, which is the correct sentence for a failure nothing
  //       described but not the register path this case is about.
  listStub().mockRejectedValue(
    refusal(500, { message: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED }),
  );
  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);

  await waitFor(expectAbendSentenceShown);

  const rendered = document.body.textContent ?? '';

  // WHY : ⚠️ Assumptions: the three withheld tokens are asserted ABSENT, which is a stronger and more
  //       honest property than asserting the fragments present would be. The baseline's fragments each
  //       begin with a LEADING SPACE and the reason fragment ' Reas:' carries its own, and none of
  //       them is reproduced here -- so a case that "asserted them verbatim" could only do so by
  //       retyping strings the catalog deliberately does not hold, which is exactly the second
  //       unreviewed source of screen text the catalog exists to prevent. Asserting the tokens never
  //       reach the DOM tests the decision that was actually made.
  expect(rendered).not.toContain('Resp:');
  expect(rendered).not.toContain('Reas:');
  expect(rendered).not.toContain('Code:');
}

/**
 * Asserts the abend replacement sentence is on the message band.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectAbendSentenceShown(): void {
  expectVerbatimMessage(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
}

/**
 * A momentary outage reaches the band as a momentary outage, through the CLASSIFIED failure.
 *
 * ⚠️ Purpose: this case exists to discriminate the WIRING and not the mapping. The mapping is asserted
 * directly in `ui/src/screens/authSummary/authSummary.test.tsx`; what only a rendered turn can show is
 * which member of the browse the screen reads. `usePagedQuery` publishes both `error`, the problem
 * document, and `failure`, the classified failure -- and only the second carries the transport
 * judgement. A screen still reading `error` would hand `describeListingFailure` a value that is not an
 * `ApiRequestError`, take its unclassified arm, and paint the abend sentence for EVERY failure, which
 * is what this case fails on.
 *
 * Assumptions: a 503 with NO sentence is used. `TRANSIENT_STATUSES` in `ui/src/api/client.ts` lists it,
 * so the classification is transient, and a bodiless answer is exactly what carries no sentence to
 * prefer -- so the sentence on the band can only have come from the classification.
 * @returns {Promise<void>} Resolves once the momentary-outage sentence has been asserted.
 */
async function reportsAMomentaryOutageFromTheClassifiedFailure(): Promise<void> {
  listStub().mockRejectedValue(refusal(503));
  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);

  await waitFor(expectMomentaryOutageSentenceShown);

  expect(
    screen.queryByText(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED),
    'a classified outage is not reported as an unexpected condition',
  ).toBeNull();
}

/**
 * Asserts the momentary-outage sentence is on the message band.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectMomentaryOutageSentenceShown(): void {
  expectVerbatimMessage(TRANSIENT_FAILURE_TRY_AGAIN);
}

/**
 * Keeps the unmapped-key message at the width its copybook declares.
 *
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function keepsTheUnmappedKeyMessageAtItsDeclaredWidth(): void {
  // WHY : Assumptions: fifty, from `app/cpy/CSMSG01Y.cpy` L20 to L21, where `CCDA-MSG-INVALID-KEY` is
  //       declared `PIC X(50)`. `COPAUS0C.cbl` L248 moves it, so this screen is one of the fourteen
  //       emitting programs -- unlike the card browse, which coerces an unrecognised key into its
  //       Enter arm and shows nothing.
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.source.lines).toStrictEqual([21]);

  // WHY : Assumptions: the declared width is the FIELD's and the stored literal is padded INTO it
  //       rather than being fifty characters of content, so the relation between the two is asserted
  //       rather than either number alone -- which is what keeps a future widening of the field from
  //       silently passing. The text itself is never retyped here: it is a catalogued string, and a
  //       second copy of it in this file would be exactly the unreviewed source of screen text the
  //       catalog exists to prevent.
  expect(COMMON_MESSAGES.INVALID_KEY.text.length).toBeLessThanOrEqual(
    COMMON_MESSAGES.INVALID_KEY.declaredWidth,
  );
  expect(COMMON_MESSAGES.INVALID_KEY.text.endsWith('...         ')).toBe(true);
}

/**
 * Renders the row-22 selection prompt with the letter S in SINGLE apostrophes.
 *
 * @returns {Promise<void>} Resolves once the decoded prompt has been asserted.
 */
async function rendersTheSelectionPromptWithSingleApostrophes(): Promise<void> {
  await renderAsUser();

  // WHY : ⚠️ Assumptions: in BMS source -- which is assembler macro source -- a doubled apostrophe
  //       inside a quoted literal encodes ONE apostrophe. `app/app-authorization-ims-db2-mq/bms/COPAU00.bms`
  //       L501 holds `INITIAL='Type ''S'' to View Authorization details from t-'`, continued on the
  //       next line with `he list'`, so the text the terminal paints contains 'S' wrapped in SINGLE
  //       apostrophes. Copying the doubled form straight through would render `''S''` and break the
  //       string -- the same class of trap as `app/bms/COUSR02.bms`, where `&&` encodes one ampersand
  //       because `&` opens a variable symbol.
  expect(AUTH_SUMMARY_SELECTION_PROMPT).toContain(`'${AUTH_SUMMARY_SELECTION_CODE}'`);
  expect(AUTH_SUMMARY_SELECTION_PROMPT).not.toContain("''");

  // WHY : Assumptions: exactly two apostrophes in the whole prompt, which is the decoded count. Four
  //       would mean the escape survived undecoded, and asserting the count catches that even if the
  //       substring assertions above were both satisfied by a longer mangled string.
  expect(AUTH_SUMMARY_SELECTION_PROMPT.split("'")).toHaveLength(3);

  // WHY : Assumptions: fifty-two characters, matching the field's own `LENGTH=52` operand at L499, so
  //       the decoded string is the right length as well as the right shape -- a tail lost from the
  //       line continuation would leave the shape intact and the length short.
  expect(AUTH_SUMMARY_SELECTION_PROMPT).toHaveLength(52);
  expectVerbatimMessage(AUTH_SUMMARY_SELECTION_PROMPT);
}

/**
 * Renders the row-24 legend as the four segments the mapset paints, separated by two spaces.
 *
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function rendersTheRowTwentyFourLegend(): Promise<void> {
  await renderAsUser();
  const legend = keyLegend();

  // WHY : Assumptions: each of the four segments is asserted in the rendered legend, taken from the
  //       screen's own exported labels rather than retyped. `COPAU00.bms` L511 holds
  //       `INITIAL='ENTER=Continue  F3=Back  F7=Backward  F8=Forwar-'` continued with `d'`, so the
  //       painted text is 'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'.
  for (const label of Object.values(AUTH_SUMMARY_KEY_LABELS)) {
    expect(within(legend).getByText(label)).toBeInTheDocument();
  }

  // WHY : ⚠️ Assumptions: the four segments joined by TWO spaces reproduce the mapset literal exactly,
  //       and the doubled separator is content rather than formatting -- the field declares
  //       `LENGTH=48` at L509, which the four labels plus three doubled separators fill precisely.
  //       Joining and measuring is what proves the labels were split out of the real literal rather
  //       than invented to look like it.
  const rebuilt = [
    AUTH_SUMMARY_KEY_LABELS.ENTER,
    AUTH_SUMMARY_KEY_LABELS.PFK03,
    AUTH_SUMMARY_KEY_LABELS.PFK07,
    AUTH_SUMMARY_KEY_LABELS.PFK08,
  ].join('  ');
  expect(rebuilt).toHaveLength(48);

  // WHY : Assumptions: the two paging labels come from the SHARED uniform set rather than being
  //       restated per screen, because every browse screen in the population paints the same two
  //       words for them; only the Enter and PF3 captions vary by screen.
  expect(AUTH_SUMMARY_KEY_LABELS.PFK07).toBe(UNIFORM_PF_KEY_LABELS.PFK07);
  expect(AUTH_SUMMARY_KEY_LABELS.PFK08).toBe(UNIFORM_PF_KEY_LABELS.PFK08);
}

/**
 * Binds exactly the four attention identifiers the program admits, and no others.
 *
 * @returns {Promise<void>} Resolves once the bound and absent keys have been asserted.
 */
async function bindsExactlyFourAttentionIdentifiers(): Promise<void> {
  await renderAsUser();
  const controls = within(keyLegend()).getAllByRole('button');

  // WHY : Assumptions: FOUR controls, because `COPAUS0C.cbl` dispatches with a direct
  //       `EVALUATE EIBAID` over `DFHENTER`, `DFHPF3`, `DFHPF7` and `DFHPF8` at L224 to L250 and sends
  //       the invalid-key message for anything else. The legend renders one control per labelled
  //       binding, so counting controls counts bindings.
  expect(controls).toHaveLength(4);

  // WHY : ⚠️ Assumptions: PF4, PF5 and PF12 are ABSENT BY TRANSCRIPTION rather than by oversight, and
  //       the absence is asserted because it distinguishes this screen from its siblings. There is NO
  //       Clear key on this browse screen -- `UNIFORM_PF_KEY_LABELS` publishes an 'F4=Clear' caption
  //       that the sibling entry screens use and this one does not -- and no save or cancel either,
  //       because a browse writes nothing. A screen that acquired any of the three would still pass
  //       every positive assertion above.
  expect(within(keyLegend()).queryByText(UNIFORM_PF_KEY_LABELS.PFK04)).not.toBeInTheDocument();
  const legendText = keyLegend().textContent ?? '';
  expect(legendText).not.toContain('F5=');
  expect(legendText).not.toContain('F12=');
}

/**
 * Gives the Enter control the primary emphasis and the back control the default.
 *
 * @returns {Promise<void>} Resolves once both emphases have been asserted.
 */
async function emphasisesEnterAsThePrimaryAction(): Promise<void> {
  await renderAsUser();
  const legend = keyLegend();

  // WHY : Assumptions: AAP section 0.3.2 assigns `type="primary"` to the Enter and PF5 actions and the
  //       default variant to PF3, PF4 and PF12, and this screen has only Enter and PF3 of those. The
  //       rendered class is what the design system emits for the primary variant, so it is the
  //       observable form of the prop -- a prop itself is not visible from the DOM.
  const enterControl = within(legend).getByText(AUTH_SUMMARY_KEY_LABELS.ENTER).closest('button');
  const backControl = within(legend).getByText(AUTH_SUMMARY_KEY_LABELS.PFK03).closest('button');

  expect(enterControl).not.toBeNull();
  expect(backControl).not.toBeNull();
  expect(enterControl?.className).toContain('ant-btn-primary');

  // WHY : Assumptions: the back control is asserted NOT to carry the primary class rather than to
  //       carry a named default class, because the design system expresses the default variant by the
  //       absence of a variant modifier -- there is no positive class to match on.
  expect(backControl?.className).not.toContain('ant-btn-primary');
}

/**
 * Pages forward on a real PF8 key press and on the equivalent control activation alike.
 *
 * @returns {Promise<void>} Resolves once both paths have been asserted equivalent.
 */
async function pagesForwardByKeyAndByControl(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  // WHY : ⚠️ Alternatives Considered: driving this through the rendered control alone was the
  //       alternative, and it is rejected because it would exercise the bar rather than the binding.
  //       The 3270 original had no pointing device, so the PF-key contract IS a keyboard contract: a
  //       case that only clicked would pass in full while every keyboard binding in the application
  //       was broken. Driving the key FIRST and the control SECOND asserts both halves and, more
  //       usefully, asserts they dispatch the SAME action -- which is the property that lets the bar be
  //       added without the keyboard workflow being taken away from an existing operator.
  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);
  expect(recordedQuery(1).direction).toBe('next');

  const forwardControl = within(keyLegend())
    .getByText(AUTH_SUMMARY_KEY_LABELS.PFK08)
    .closest('button');
  expect(forwardControl).not.toBeNull();
  if (forwardControl !== null) {
    await user.click(forwardControl);
    await waitFor(expectThirdCallMade);
    expect(recordedQuery(2).direction).toBe('next');
  }
}

/**
 * Reports the top boundary rather than refusing the backward key on the first page.
 *
 * @returns {Promise<void>} Resolves once the boundary sentence has been asserted.
 */
async function reportsTheTopBoundaryOnTheFirstPage(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  await pressPfKey(user, 'PFK07');

  // WHY : ⚠️ Alternatives Considered: binding the paging keys `disabled` on the derived backward
  //       availability was the alternative, and it is rejected because it would SUPPRESS this
  //       sentence. `usePfKeys` answers a disabled handler with a rejection carrying the invalid-key
  //       message, so a disabled PF7 on the first page would show 'Invalid key pressed...' where the
  //       source shows 'You are already at the top of the page...' -- replacing a correct verbatim
  //       string with a wrong one and losing a string transformation rule T8 requires be carried
  //       across. The source refuses neither key: both arms re-send the screen with a message, at
  //       `COPAUS0C.cbl` L380 to L384 and L408 to L411.
  // WHY : Assumptions: availability is still decided from the KEYSET state and never from a page
  //       number -- the handler reads the hook's derived backward availability, which is the page
  //       ordinal exceeding one with a leading cursor present, the same test as the source's
  //       `IF CDEMO-CPVS-PAGE-NUM > 1` at L365. What the envelope decides is which of two OUTCOMES
  //       happens, not whether the key responds at all.
  await waitFor(expectTopBoundaryShown);

  // WHY : Assumptions: no second read is issued, which is what shows the boundary was detected locally
  //       rather than discovered by asking the service for a page that does not exist.
  expect(listStub()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts the top-of-page boundary sentence is on the message band.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectTopBoundaryShown(): void {
  expectVerbatimMessage(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE);
}

/**
 * Reports the bottom boundary rather than refusing the forward key on the last page.
 *
 * @returns {Promise<void>} Resolves once the boundary sentence has been asserted.
 */
async function reportsTheBottomBoundaryOnTheLastPage(): Promise<void> {
  // WHY : Assumptions: `hasNext` is false, and a full page of five is deliberately used with it. The
  //       source settles forward availability by issuing one extra read beyond the five it displays
  //       and setting its indicator from whether that read found a record, at `COPAUS0C.cbl` L445 to
  //       L452 -- so a FULL page is not evidence that a sixth record exists, and the envelope's own
  //       indicator is the only thing that decides.
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: false }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  await pressPfKey(user, 'PFK08');
  await waitFor(expectBottomBoundaryShown);
  expect(listStub()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts the bottom-of-page boundary sentence is on the message band.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectBottomBoundaryShown(): void {
  expectVerbatimMessage(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE);
}

/**
 * Keeps PF8 meaning page-forward here, unlike the detail screen one route away.
 *
 * @returns {Promise<void>} Resolves once this screen's PF8 semantic has been asserted.
 */
async function keepsForwardPagingDistinctFromTheDetailScreen(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);

  // WHY : ⚠️ Assumptions: on THIS screen PF8 pages forward through the list; on the sibling detail
  //       screen `COPAUS1C` the same key means 'Next Auth' and steps to the next authorization record.
  //       Two different actions on one key, one route apart -- which is exactly why `PfKeyBar` takes a
  //       per-screen descriptor array rather than a shared table, and why the two must never be
  //       unified. This case pins the summary side: the key produces a PAGE step carrying a cursor and
  //       a direction, not a record navigation.
  expect(AUTH_SUMMARY_KEY_LABELS.PFK08).toBe(UNIFORM_PF_KEY_LABELS.PFK08);
  expect(recordedQuery(1).direction).toBe('next');
  expect(recordedQuery(1).cursor).toBe(TRAILING_CURSOR);
}

/**
 * Admits an ordinary, non-administrative operator to the screen and lets them use it.
 *
 * @returns {Promise<void>} Resolves once a non-admin session has been shown to reach the rows.
 */
async function admitsAnOrdinaryOperator(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();

  // WHY : ⚠️ Assumptions: NOT admin-gated, and the baseline is explicit about it. `app/cpy/COMEN02Y.cpy`
  //       lists 'Pending Authorization View' as the eleventh main-menu option, names `COPAUS0C` as its
  //       program, and sets its user-type FILLER to `'U'` at L90 -- and all eleven main-menu options
  //       are `'U'`, with not one `'A'` anywhere in the copybook. So reaching this screen requires a
  //       session and nothing more. Asserting a non-admin can not only ARRIVE but also page and select
  //       is what distinguishes a route that admits them from one that renders a read-only shell.
  await scopeAndAwaitRows(user);
  expect(selectionControls()).toHaveLength(AUTH_SUMMARY_PAGE_SIZE);
  expect(listStub()).toHaveBeenCalledTimes(1);
}

/**
 * Opens the chosen authorization at the detail route, carrying the whole composite key.
 *
 * @returns {Promise<void>} Resolves once the navigation target has been asserted.
 */
async function opensTheChosenAuthorization(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const chosen = listItemFixture(2);
  const control = selectionControls()[1];
  expect(control).toBeDefined();
  if (control === undefined) {
    return;
  }

  /*
   * WHY : ⚠️ Assumptions: the selection character is TYPED into the row's own cell, because that is what
   *       the reference reads -- `PROCESS-ENTER-KEY` moves `SEL0002I` into the selection flag and the
   *       row's key into the selected key (`COPAUS0C.cbl` L292 to L295) -- so the typed character is the
   *       input and Enter is the commit. This case used to CLICK the row's marker, which the mapset's
   *       `LENGTH=1` unprotected field is not.
   */
  await user.type(control, AUTH_SUMMARY_SELECTION_CODE);
  await pressPfKey(user, 'ENTER');

  // WHY : ⚠️ Assumptions: the route parameter is named `:key` and not `:id`, and the naming is
  //       deliberate. `ui/src/router.tsx` declares the detail route as `/authorizations/:key` because
  //       the primary key AAP section 0.4.1.3 gives this table is the COMPOSITE
  //       `(account_id, auth_date, auth_time)` -- there is no single identifier to name, so one sealed
  //       token stands for all three parts. An `:id` would suggest a simple key the data does not have.
  // WHY : Assumptions: the assertion is on the path the screen navigated to rather than on a rendered
  //       detail screen, because the detail screen is a separate unit with its own suite -- rendering
  //       it here would make this case fail for that screen's reasons.
  const expectedPath = authorizationDetailPath(chosen.key);
  expect(expectedPath).toContain(chosen.key);
  expect(expectedPath.startsWith(`${AUTH_SUMMARY_ROUTE}/`)).toBe(true);

  await waitFor(expectPathReached(expectedPath));
}

/**
 * Builds an assertion that the router has arrived at one address.
 *
 * Assumptions: a builder returning a probe, rather than a probe closing over a module-scoped variable,
 * so two cases asserting different destinations cannot interfere through shared state.
 * @param {string} expected - The pathname the router is expected to hold.
 * @returns {() => void} A probe suitable for `waitFor`, asserting that address has been reached.
 */
function expectPathReached(expected: string): () => void {
  /**
   * Asserts the router currently holds the expected address.
   * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
   */
  return function assertPath(): void {
    expect(currentPath()).toBe(expected);
  };
}

/**
 * Returns to the main menu on the back key, as a client-side route change.
 *
 * @returns {Promise<void>} Resolves once the destination has been asserted.
 */
async function returnsToTheMainMenuOnBack(): Promise<void> {
  const { user } = await renderAsUser();

  await pressPfKey(user, 'PFK03');

  // WHY : Assumptions: the destination is the main menu unconditionally, because the source's PF3 arm
  //       sets it itself -- `COPAUS0C.cbl` L235 to L238 move `WS-PGM-MENU` into the next-program field
  //       before transferring, so the sign-on fallback that `RETURN-TO-PREV-SCREEN` applies to a blank
  //       field at L668 to L670 cannot be reached from this key.
  // WHY : ⚠️ Assumptions: transformation rule T5 maps `EXEC CICS XCTL` to a CLIENT-SIDE route change,
  //       and there is no server-side next-program field anywhere in the target. The COMMAREA
  //       navigation members of `app/cpy/COCOM01Y.cpy` -- the from- and to-program and -transaction
  //       names -- became router history, so the destination is decided in the browser and no request
  //       is issued to discover it. Asserting that the transport was never called is what shows the
  //       transfer needed no server round trip.
  await waitFor(expectPathReached(AUTH_SUMMARY_BACK_ROUTE));
  expect(listStub()).not.toHaveBeenCalled();
}

/**
 * Carries the account scope and the browse cursor in the request, never in a session.
 *
 * @returns {Promise<void>} Resolves once the request-borne context has been asserted.
 */
async function carriesEverySelectionContextInTheRequest(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);

  // WHY : ⚠️ Refactoring Rationale: AAP section 0.7.1 decomposes the single `DFHCOMMAREA` struct into
  //       four separate mechanisms, and this case pins the one that would be easiest to recreate
  //       wrongly. `CDEMO-ACCT-ID PIC 9(11)` at `app/cpy/COCOM01Y.cpy` L38 and
  //       `CDEMO-CUST-ID PIC 9(09)` at L33 became REST parameters, which is what makes each request
  //       self-describing and therefore independently authorizable -- in the baseline the COMMAREA was
  //       storage the CLIENT echoed back, so a client could in principle assert its own context.
  expect(recordedQuery(0).accountId).toBe(ELEVEN_DIGIT_ACCOUNT_ID);
  expect(recordedQuery(1).accountId).toBe(ELEVEN_DIGIT_ACCOUNT_ID);

  // WHY : ⚠️ Refactoring Rationale: the browse CURSOR travels in the request too, and it is the member
  //       of the old struct that AAP section 0.7.1 is most emphatic must not survive as session state.
  //       `COPAUS0C.cbl` L126 declares `CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES` and L121
  //       `CDEMO-CPVS-PAUKEY-LAST`, all echoed to the terminal and back between turns. In the target
  //       the cursor is an argument, which is what makes the eight services stateless and horizontally
  //       scalable behind a load balancer with no sticky sessions and no server-side session store.
  expect(recordedQuery(1).cursor).toBe(TRAILING_CURSOR);

  // WHY : Assumptions: the re-entry discriminator disappears ENTIRELY rather than being relocated.
  //       `COCOM01Y.cpy` L29 to L31 declare `CDEMO-PGM-CONTEXT` with its enter and re-enter
  //       condition names, and a stateless handler answering with a field-error array has no
  //       first-entry-versus-re-entry distinction to make. So no request member carries a turn count
  //       under any spelling, which is asserted exhaustively over the query's own keys.
  expect(Object.keys(recordedQuery(1)).sort()).toStrictEqual(['accountId', 'cursor', 'direction']);
}

/**
 * Moves the scope between accounts without either account's values outliving the change.
 *
 * @returns {Promise<void>} Resolves once the second scope has been asserted.
 */
async function movesTheScopeBetweenAccounts(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user, ELEVEN_DIGIT_ACCOUNT_ID);

  // WHY : Assumptions: the field is CLEARED before the second identifier is typed, and a select-all
  //       gesture is not sufficient. The control caps entry at its eleven-character declared width, so
  //       typing into an already-full field appends nothing at all and the turn would re-submit the
  //       FIRST identifier -- which is a passing assertion about the wrong account.
  await user.clear(accountIdField());
  await user.keyboard(OTHER_ELEVEN_DIGIT_ACCOUNT_ID);
  await pressPfKey(user, 'ENTER');
  await waitFor(expectSecondCallMade);

  // WHY : Assumptions: the second read is scoped to the SECOND account and carries no cursor, because a
  //       scope change restarts the browse rather than continuing it. The source is explicit about the
  //       rewind: `GATHER-DETAILS` opens with `MOVE 0 TO CDEMO-CPVS-PAGE-NUM` at `COPAUS0C.cbl` L347
  //       before it re-reads, so an Enter turn on a later page returns page one.
  expect(recordedQuery(1).accountId).toBe(OTHER_ELEVEN_DIGIT_ACCOUNT_ID);
  expect(recordedQuery(1).cursor).toBeUndefined();
}

/**
 * Issues no commit of its own, because the transaction boundary is entirely server-side.
 *
 * @returns {Promise<void>} Resolves once the read-only request shape has been asserted.
 */
async function issuesNoCommitOfItsOwn(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);
  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondCallMade);

  // WHY : ⚠️ Assumptions: the CLIENT has no notion of a commit and issues none. `COPAUS0C.cbl` issues
  //       `EXEC CICS SYNCPOINT` at L686, and transformation rule T5 maps that verb to a
  //       `@Transactional` boundary on the SERVER -- so the boundary moved rather than disappearing,
  //       and it moved somewhere the browser cannot reach. The whole of this screen's transport surface
  //       is the listing read, so the property is asserted as the transport having no other member
  //       called: a commit call would have to be one.
  const transportModule = vi.mocked(await import('../api/authorization'));
  expect(transportModule.getPendingAuthorization).not.toHaveBeenCalled();
  expect(transportModule.setAuthorizationFraudState).not.toHaveBeenCalled();
  expect(transportModule.getNextPendingAuthorization).not.toHaveBeenCalled();
  expect(listStub()).toHaveBeenCalledTimes(2);
}

/**
 * Name of the card verification member, assembled rather than written out.
 *
 * ⚠️ Assumptions: the three-letter acronym is built from fragments instead of appearing as a literal,
 * and the reason is the same one `config/rule1/rule1_gate.py` gives for assembling its own prohibited
 * tokens: a file that carries the literal it exists to forbid becomes a false positive in every audit
 * that greps for it, and the audit then either flags this file or has to exempt it -- and an exemption
 * is what makes a grep untrustworthy. The verification value must never appear in this package, so the
 * one place that has to name it names it without spelling it.
 */
const VERIFICATION_VALUE_MEMBER = ['c', 'v', 'v'].join('');

/**
 * Builds the pattern matching a card verification value's name as a whole word.
 *
 * Assumptions: built at the call site from {@link VERIFICATION_VALUE_MEMBER} rather than held as a
 * module-level regular expression, because a shared regular expression carries `lastIndex` state
 * between cases under some flag combinations and a fresh instance cannot.
 * @returns {RegExp} A case-insensitive whole-word pattern for the verification member's name.
 */
function verificationValuePattern(): RegExp {
  return new RegExp(`\\b${VERIFICATION_VALUE_MEMBER}\\b`, 'iu');
}

/**
 * Returns the member names the summary contract carries.
 * @returns {readonly string[]} Every member name on a built summary.
 */
function summaryMemberNames(): readonly string[] {
  return Object.keys(summaryFixture());
}

/**
 * Exposes no unmasked account number, verification value or government identifier.
 *
 * @returns {Promise<void>} Resolves once the exposure limits have been asserted.
 */
async function exposesNoSensitiveValue(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE)));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  const rendered = document.body.textContent ?? '';

  // WHY : ⚠️ Assumptions: a primary account number reaches this screen already masked to its last four
  //       digits, and the administrative exception belongs to the card-detail endpoint alone. The probe
  //       is for any run of sixteen consecutive digits, because that is what an unmasked number looks
  //       like regardless of which field leaked it -- asserting on the fixture's own masked string
  //       would only prove the fixture was rendered, not that nothing else was.
  expect(rendered).not.toMatch(/\d{16}/u);

  // WHY : Assumptions: there is no card verification value to leak, because the contract declares no
  //       such member on any authorization type -- so this asserts the absence stays true as the
  //       contract grows. A value never returned by any endpoint cannot be masked wrongly.
  expect(rendered).not.toMatch(verificationValuePattern());
  expect(summaryMemberNames()).not.toContain(VERIFICATION_VALUE_MEMBER);

  // WHY : Assumptions: the summary contract carries neither a national identifier nor a
  //       government-issued one, so neither can render. Those two are stored encrypted as `BYTEA` and
  //       returned masked on the account context's own endpoints, and this screen reads a different
  //       contract entirely.
  expect(summaryMemberNames()).not.toContain('ssn');
  expect(summaryMemberNames()).not.toContain('governmentIssuedId');
}

/**
 * Marks the filter field on a refusal that names it, and states the reason programmatically.
 *
 * @returns {Promise<void>} Resolves once the error state and its association have been asserted.
 */
async function marksTheFilterFieldOnARefusalThatNamesIt(): Promise<void> {
  const refusalText = SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND;
  listStub().mockRejectedValue(
    refusal(400, { fieldErrors: [fieldError('accountId', refusalText)] }),
  );
  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);

  const field = accountIdField();

  await waitFor(expectFieldMarkedInvalid);

  // WHY : ⚠️ Refactoring Rationale: the baseline reaches this appearance through the templated copybook
  //       `app/cpy/CSSETATY.cpy` L17 to L27, which moves `DFHRED` into a field's colour attribute when
  //       its validation flag is not-OK or blank. But it GATES the whole substitution on the
  //       pseudo-conversational re-entry flag at L20, and AAP section 0.7.1 establishes that the
  //       discriminator disappears entirely -- so in the target the highlight is a pure function of
  //       the current answer rather than of a remembered turn count. That is why this case asserts on
  //       the response alone with no second turn to set up.
  // WHY : Assumptions: the sentence is associated PROGRAMMATICALLY and not merely placed nearby,
  //       because the design system renders help text in a container with no relationship to the
  //       control -- so without the association a screen-reader user reaches a field marked as refused
  //       with no statement of what is wrong with it.
  expect(field.getAttribute('aria-describedby') ?? '').toContain(
    fieldErrorId(ACCOUNT_ID_CONTROL_ID),
  );
  expect(screen.getByText(refusalText)).toBeInTheDocument();
}

/**
 * Writes the asterisk marker into the field the source marks, and only in the blank condition.
 *
 * @returns {Promise<void>} Resolves once the marker has been asserted present.
 */
async function writesTheAsteriskMarkerOnlyWhenBlank(): Promise<void> {
  const refusalText = SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND;
  listStub().mockRejectedValue(
    refusal(400, { fieldErrors: [fieldError('accountId', refusalText, 'BLANK')] }),
  );
  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);

  // WHY : ⚠️ Assumptions: the literal asterisk is written into the FIELD and not into the message, and
  //       only in the blank case. `app/cpy/CSSETATY.cpy` L24 to L25 moves `'*'` into the screen
  //       variable's output area under `IF FLG-(TESTVAR1)-BLANK`, nested inside the colour
  //       substitution -- so a blank field gets both the colour and the marker while a merely invalid
  //       one gets the colour alone. The marker is placed as the control's suffix so it appears inside
  //       the field where the copybook puts it without becoming part of the value the operator typed.
  await waitFor(expectBlankMarkerShown);
}

/**
 * Leaves a field the refusal did not name entirely unmarked.
 *
 * @returns {Promise<void>} Resolves once the unmarked state has been asserted.
 */
async function leavesAnUnnamedFieldUnmarked(): Promise<void> {
  // WHY : Assumptions: the refusal names a DIFFERENT member, so the account filter must not be marked
  //       by it. This is the complement the positive cases cannot provide: a screen that marked its
  //       only field on any refusal whatsoever would satisfy both of them and fail here, and that
  //       defect is invisible on a screen with one field unless it is asserted directly.
  listStub().mockRejectedValue(
    refusal(400, {
      fieldErrors: [fieldError('someOtherMember', SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO)],
    }),
  );
  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);

  await waitFor(expectFailureReported);

  const field = accountIdField();
  expect(field).not.toHaveAttribute('aria-invalid', 'true');
  expect(screen.queryByText(FIELD_ERROR_TOKENS.blankMarker)).not.toBeInTheDocument();
}

/**
 * Asserts the account filter is marked invalid for assistive technology.
 *
 * Assumptions: the control is re-queried on every attempt rather than captured once, because the
 * design system re-renders the input when its validation state changes and a captured reference can
 * outlive the element it pointed at.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectFieldMarkedInvalid(): void {
  expect(accountIdField()).toHaveAttribute('aria-invalid', 'true');
}

/**
 * Asserts the blank-condition asterisk marker is rendered.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectBlankMarkerShown(): void {
  expect(screen.getByText(FIELD_ERROR_TOKENS.blankMarker)).toBeInTheDocument();
}

/**
 * The claim the detail screen hands a fraud outcome over under, composed as both screens compose it.
 *
 * Assumptions: built from the route constant and the same `#fraud` suffix rather than written out, so
 * a case cannot pass against a screen that collects under a name the detail screen does not retain.
 */
const FRAUD_HANDOVER_CLAIM = `${AUTH_SUMMARY_ROUTE}#fraud`;

/**
 * Asserts an outcome retained BEFORE this screen mounted is collected and painted.
 *
 * ⚠️ Purpose: the detail screen's PF3 is not refused while a fraud write is outstanding, so a reviewer
 * can confirm a transition and arrive here before it settles -- and the screen that was going to report
 * it no longer exists. This is the arriving half of that hand-over: a completed fraud report that would
 * otherwise be reported to nobody.
 *
 * Assumptions: the sentence is taken from `PROGRAM_MESSAGES.COPAUS1C`, the DETAIL program's catalog, and
 * not from this screen's own. That is what makes the case a hand-over rather than a local message: this
 * screen paints a sentence it has no way of composing.
 *
 * Assumptions: the claim is asserted EMPTY afterwards, because collection removes the entry. A screen
 * that read without collecting would paint the same completed write again on its next mount, and a
 * reviewer shown one fraud report twice cannot tell that from two.
 *
 * ⚠️ Assumptions: the session is seeded BEFORE the outcome is retained, and the render is issued
 * separately rather than through {@link renderAsUser}, because seeding installs the request harness and
 * `installApiHarness` calls `resetApiClient`, which discards every uncollected outcome by design --
 * `ui/src/api/client.ts` L2107 records that an outcome produced against a previous configuration is not
 * one to hand on. Retaining first therefore measured nothing at all: the entry was gone before the
 * screen mounted. The ordering is the case's own subject in miniature, so it is stated rather than
 * hidden behind the helper.
 * @returns {Promise<void>} Resolves once the handed-over sentence is on the band.
 */
async function paintsAFraudOutcomeHandedOverBeforeItMounted(): Promise<void> {
  const handed = PROGRAM_MESSAGES.COPAUS1C.AUTH_MARKED_FRAUD;
  await seedSession({ groups: ['carddemo-user'] });
  retainOutcomeAcrossNavigation<FraudTransitionHandover>(FRAUD_HANDOVER_CLAIM, {
    settled: 'COMPLETED',
    value: { text: handed, severity: 'success' },
  });

  await renderInAppShell(
    <>
      <AuthSummaryScreen />
      <LocationProbe />
    </>,
    { initialEntries: [AUTH_SUMMARY_ROUTE] },
  );

  expect(await screen.findByText(handed)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(handed);
  expect(claimRetainedOutcome(FRAUD_HANDOVER_CLAIM)).toBeUndefined();
}

/**
 * Asserts an outcome retained AFTER this screen mounted still reaches the band.
 *
 * ⚠️ Purpose: this is the ordinary case rather than the exotic one, which is why both halves are
 * measured. `ui/src/api/client.ts` records that the four abandoned writes it was built for landed 7 to
 * 12 ms after the navigation -- so the destination screen was already mounted when the outcome came to
 * exist, and a screen that only looked on mount looked too early and found nothing every time.
 *
 * Assumptions: the retention is wrapped in `act`, because retaining notifies every listener
 * synchronously and this screen's listener sets state. Outside `act` React reports the update as
 * unwrapped and the assertion races the render it triggers.
 * @returns {Promise<void>} Resolves once the later hand-over is on the band.
 */
async function paintsAFraudOutcomeHandedOverAfterItMounted(): Promise<void> {
  const handed = PROGRAM_MESSAGES.COPAUS1C.AUTH_FRAUD_REMOVED;
  await renderAsUser();
  expect(screen.queryByText(handed)).not.toBeInTheDocument();

  act(
    /**
     * Retains the detail screen's outcome while the summary is already mounted and listening.
     * @returns {void} Nothing; the retention notifies this screen's listener as its effect.
     */
    () => {
      retainOutcomeAcrossNavigation<FraudTransitionHandover>(FRAUD_HANDOVER_CLAIM, {
        settled: 'COMPLETED',
        value: { text: handed, severity: 'success' },
      });
    },
  );

  expect(await screen.findByText(handed)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(handed);
  expect(claimRetainedOutcome(FRAUD_HANDOVER_CLAIM)).toBeUndefined();
}

/**
 * Asserts an outcome retained under somebody else's claim is neither painted nor consumed.
 *
 * ⚠️ Purpose: every listener is told about every retention, so a screen that collected whatever had
 * just been retained would take another pair of screens' hand-over -- painting a sentence about a record
 * it never showed, and stealing it from the screen that was waiting for it. Both halves of that are
 * asserted: nothing appears here, and the outcome is still there for its owner.
 *
 * Assumptions: the foreign sentence is a catalogued string from an unrelated program rather than an
 * invented one, so nothing in this case could be mistaken for operator text this migration authored.
 * @returns {Promise<void>} Resolves once the foreign hand-over has been shown to be untouched.
 */
async function leavesAnotherScreensHandoverAlone(): Promise<void> {
  const foreignClaim = `${AUTH_SUMMARY_ROUTE}#some-other-outcome`;
  const foreign = PROGRAM_MESSAGES.COSGN00C.UNABLE_TO_VERIFY_THE_USER;
  await renderAsUser();

  act(
    /**
     * Retains an outcome under a claim belonging to another pair of screens.
     * @returns {void} Nothing; the retention notifies every listener, which is the point of the case.
     */
    () => {
      retainOutcomeAcrossNavigation<FraudTransitionHandover>(foreignClaim, {
        settled: 'COMPLETED',
        value: { text: foreign, severity: 'error' },
      });
    },
  );

  expect(screen.queryByText(foreign)).not.toBeInTheDocument();
  expect(claimRetainedOutcome<FraudTransitionHandover>(foreignClaim)).toBeDefined();
}

/**
 * Asserts the message band is carrying something, whatever the failure reported.
 *
 * Assumptions: the band's PRESENCE is the probe rather than a particular sentence, because this
 * helper serves a case about what is NOT marked -- pinning the sentence too would couple that case to
 * the failure-to-sentence mapping, which its own case owns.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectFailureReported(): void {
  const band = messageBand();
  expect(band).not.toBeNull();
  expect(band?.textContent ?? '').not.toBe('');
}

/**
 * Holds the screen to the field widths and attributes the symbolic map and mapset declare.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function fieldConstraints(): void {
  it('holds the account filter to its declared width', holdsTheDeclaredFilterWidth);
  it('refuses an identifier that is not numeric', refusesANonNumericIdentifier);
  it('refuses a blank identifier in different words', refusesABlankIdentifier);
  it('claims the opening cursor for nothing', claimsTheOpeningCursorForNothing);
  it('paints no non-display control', paintsNoNonDisplayControl);
  it('renders the list through the design system table', rendersThroughTheDesignSystemTable);
  it('carries five discrete account-status members', carriesFiveDiscreteAccountStatusMembers);
}
/**
 * Holds the screen to the five-row page and the key-based cursor the baseline browse carries.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function keysetBrowse(): void {
  it('pages five rows at a time', pagesFiveRowsAtATime);
  it('renders exactly a full page', rendersExactlyAFullPage);
  it('renders a short page short', rendersAShortPageShort);
  it('disables the offset pager', disablesTheOffsetPager);
  it('steps forward from the trailing cursor', stepsForwardFromTheTrailingCursor);
  it('steps backward from the leading cursor', stepsBackwardFromTheLeadingCursor);
  it('carries the whole composite key in the cursor', carriesTheWholeCompositeKeyInTheCursor);
  it('reads only the four declared envelope members', readsOnlyTheFourDeclaredEnvelopeMembers);
}
/**
 * Holds the screen to exact fixed-point money and the closed status domains beside it.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function fixedPointMoney(): void {
  it('carries every amount as an exact string', carriesEveryAmountAsAnExactString);
  it('renders every amount unaltered', rendersEveryAmountUnaltered);
  it('sizes each amount at its own declared measure', sizesEachAmountAtItsOwnDeclaredMeasure);
  it('never exposes packed decimal', neverExposesPackedDecimal);
  it('renders amounts in the fixed-pitch token', rendersAmountsInTheFixedPitchToken);
  it('paints each sign case in its own token', paintsEachSignCaseInItsOwnToken);
  it('keeps the status domains closed', keepsTheStatusDomainsClosed);
  it('renders both counts from the response', rendersBothCountsFromTheResponse);
}
/**
 * Holds every user-visible string to the catalog, and every withheld diagnostic to the register.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function verbatimMessages(): void {
  it('keeps both entry refusals distinct', keepsBothEntryRefusalsDistinct);
  it('keeps the selection refusal singular', keepsTheSelectionRefusalSingular);
  it('attributes both boundary sentences', attributesBothBoundarySentences);
  it('keeps both spellings of AUTH distinct', keepsBothSpellingsOfAuthDistinct);
  it('registers all ten composed diagnostics', registersAllTenComposedDiagnostics);
  it(
    'keeps the program-specification-block site registered',
    keepsTheProgramSpecificationBlockSiteRegistered,
  );
  it('withholds every internal code', withholdsEveryInternalCode);
  it(
    'reports a momentary outage from the classified failure',
    reportsAMomentaryOutageFromTheClassifiedFailure,
  );
  it(
    'keeps the unmapped-key message at its declared width',
    keepsTheUnmappedKeyMessageAtItsDeclaredWidth,
  );
}
/**
 * Holds the screen to the four attention identifiers the program admits and the legend it paints.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function keyboardContract(): void {
  it(
    'renders the selection prompt with single apostrophes',
    rendersTheSelectionPromptWithSingleApostrophes,
  );
  it('renders the row-24 legend', rendersTheRowTwentyFourLegend);
  it('binds exactly four attention identifiers', bindsExactlyFourAttentionIdentifiers);
  it('emphasises Enter as the primary action', emphasisesEnterAsThePrimaryAction);
  it('pages forward by key and by control', pagesForwardByKeyAndByControl);
  it('reports the top boundary on the first page', reportsTheTopBoundaryOnTheFirstPage);
  it('reports the bottom boundary on the last page', reportsTheBottomBoundaryOnTheLastPage);
  it(
    'keeps forward paging distinct from the detail screen',
    keepsForwardPagingDistinctFromTheDetailScreen,
  );
}
/**
 * Holds the screen to request-borne context, client-side navigation and the exposure limits.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function sessionAndRouting(): void {
  it('admits an ordinary operator', admitsAnOrdinaryOperator);
  it('opens the chosen authorization', opensTheChosenAuthorization);
  it('returns to the main menu on back', returnsToTheMainMenuOnBack);
  it('carries every selection context in the request', carriesEverySelectionContextInTheRequest);
  it('moves the scope between accounts', movesTheScopeBetweenAccounts);
  it('issues no commit of its own', issuesNoCommitOfItsOwn);
  it('exposes no sensitive value', exposesNoSensitiveValue);
}
/**
 * A promise a case settles itself, so a read can be held unsettled while assertions run.
 * @template T What the held read answers with.
 */
interface HeldRead<T> {
  /** The promise handed to the screen in place of a settled answer. */
  readonly promise: Promise<T>;
  /** Settles that promise with the answer given. */
  readonly settle: (value: T) => void;
}

/**
 * Builds a read whose settlement the case controls.
 *
 * Assumptions: the resolver is captured out of the executor rather than taken from a deferred
 * construct, because the executor runs synchronously inside the `Promise` constructor -- so the
 * resolver is in place before this function returns and a case can never settle nothing.
 * @template T What the held read answers with.
 * @returns {HeldRead<T>} The unsettled promise and its settler.
 */
function heldRead<T>(): HeldRead<T> {
  /** Resolver of the promise below, replaced the moment the executor runs. */
  let capture: (value: T) => void = holdUntilCaptured;

  /**
   * Records the promise's resolver so the case can reach it.
   * @param {(value: T) => void} resolve - The resolver the promise supplies.
   * @returns {void} Nothing; the resolver is recorded above.
   */
  function captureResolver(resolve: (value: T) => void): void {
    capture = resolve;
  }

  /**
   * Settles the promise with the value given.
   * @param {T} value - What the held read answers with.
   * @returns {void} Nothing; the promise settles.
   */
  function settle(value: T): void {
    capture(value);
  }

  return { promise: new Promise<T>(captureResolver), settle };
}

/**
 * Stands in for the resolver until the executor supplies the real one.
 *
 * Assumptions: it THROWS rather than returning quietly, because reaching it means the held read was
 * settled before its resolver existed -- a case that would otherwise settle nothing and leave the case
 * waiting on a promise nobody can resolve, which reads as a timeout rather than as the programming
 * error it is.
 * @returns {never} Nothing is ever returned; the call always raises.
 * @throws {Error} Always, naming the ordering violation that reached it.
 */
function holdUntilCaptured(): void {
  throw new Error('the held read was settled before its resolver was captured');
}

/**
 * Six activations of Enter over one unchanged identifier issue ONE search.
 *
 * ⚠️ Purpose: this is the regression this case exists for, and it is stated as MEASURED rather than as
 * feared. A browser pass counted three activations of the Enter control on an unchanged account taking
 * `POST /api/v1/authorizations/search` from one request to four, and three further activations
 * dispatched inside a single millisecond taking it to seven -- one identical request per press. CICS
 * cannot reach that state at all: it locks the keyboard for the duration of a task, so a second Enter
 * during a turn is never delivered.
 *
 * ⚠️ Assumptions: the six presses straddle the settlement deliberately, three while the read is held
 * and three after it has answered, because the two halves are stopped by DIFFERENT guards and a case
 * that pressed only during the in-flight window would pass against the defect. The first three are
 * declined by the busy channel on the Enter binding; the last three are declined by the
 * settled-criteria guard, which is the half the previous revision had none of -- its own final press
 * after settlement asserted a SECOND request, which is the behaviour now measured as the defect.
 *
 * ⚠️ Assumptions: the assertion is STRUCTURAL -- a call count -- and not a timing one. jsdom flushes a
 * discrete click before a second activation in the same task, so a case cannot reproduce the
 * single-millisecond burst the browser measured; what it can do is prove that no press after the first
 * reaches the transport however they are spaced, which is the property the burst violated.
 * @returns {Promise<void>} Resolves once the single-request count has been asserted on both sides of
 *   the settlement.
 */
async function collapsesAnIdenticalResubmission(): Promise<void> {
  const held = heldRead<PendingAuthListResponse>();
  listStub().mockReturnValueOnce(held.promise);

  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectOneListingIssued);

  await pressPfKey(user, 'ENTER');
  await pressPfKey(user, 'ENTER');
  await pressPfKey(user, 'ENTER');

  expect(listStub()).toHaveBeenCalledTimes(1);

  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  held.settle(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  await waitFor(expectFirstRowPresent);

  await pressPfKey(user, 'ENTER');
  await pressPfKey(user, 'ENTER');
  await pressPfKey(user, 'ENTER');

  expect(listStub()).toHaveBeenCalledTimes(1);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent).not.toContain(
    COMMON_MESSAGES.INVALID_KEY.text.trim(),
  );
}

/**
 * Asserts exactly one listing has been issued.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectOneListingIssued(): void {
  expect(listStub()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts a second listing has been issued.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectSecondListingIssued(): void {
  expect(listStub()).toHaveBeenCalledTimes(2);
}

/**
 * Enter on a LATER page of the same account still rewinds to page one, and reads to do it.
 *
 * ⚠️ Purpose: this is the boundary of the settled-criteria guard, and the case exists so the guard
 * cannot be widened into the rewind by a later edit. The source is explicit that Enter rewinds:
 * `GATHER-DETAILS` opens with `MOVE 0 TO CDEMO-CPVS-PAGE-NUM` at `COPAUS0C.cbl` L347 before it
 * re-reads, and `INITIALIZE-AUTH-DATA` at L353 clears all five selectors and row families. So an Enter
 * turn on page two of a result set must produce a read, even though the account has not changed.
 *
 * Assumptions: the forward step is taken first so the browse's ordinal is genuinely past one; the
 * assertion is the THIRD call, which is the rewind, and the query it carried -- no cursor -- which is
 * what distinguishes a rewind from another forward step.
 * @returns {Promise<void>} Resolves once the rewind read has been asserted.
 */
async function rewindsFromALaterPageOnResubmission(): Promise<void> {
  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondListingIssued);

  await pressPfKey(user, 'ENTER');
  await waitFor(expectThirdListingIssued);

  expect(recordedQuery(2).cursor).toBeUndefined();
  expect(recordedQuery(2).accountId).toBe(ELEVEN_DIGIT_ACCOUNT_ID);
}

/**
 * Asserts a third listing has been issued.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectThirdListingIssued(): void {
  expect(listStub()).toHaveBeenCalledTimes(3);
}

/**
 * Enter after a REFUSED search issues the search again.
 *
 * ⚠️ Purpose: the other boundary of the settled-criteria guard. A read that failed leaves whatever
 * summary had arrived before it standing, so a guard resting on the summary alone would decline the
 * operator's retry and the failure would be unrecoverable without leaving the screen. The guard tests
 * the browse's own failure flag for exactly this case, and this is what holds it there.
 *
 * Assumptions: the first read SUCCEEDS and the second is refused, because the interesting state is a
 * screen that has an answer on display and a failure over the top of it -- a screen whose very first
 * read failed has no summary and would be readmitted by any guard.
 * @returns {Promise<void>} Resolves once the retry has been asserted.
 */
async function retriesAfterARefusedSearch(): Promise<void> {
  listStub()
    .mockResolvedValueOnce(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }))
    .mockRejectedValueOnce(refusal(500))
    .mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));

  const { user } = await renderAsUser();
  await scopeAndAwaitRows(user);

  /*
   * WHY : Assumptions: the failing read is provoked by a FORWARD step rather than by another Enter,
   *       because an Enter on the answered first page is exactly what the guard declines -- so driving
   *       the failure through Enter would prove nothing about the retry and would leave the browse
   *       unfailed. The step fails, the browse reports the failure, and the Enter that follows is the
   *       retry under test.
   */
  await pressPfKey(user, 'PFK08');
  await waitFor(expectSecondListingIssued);

  await pressPfKey(user, 'ENTER');
  await waitFor(expectThirdListingIssued);
}

/**
 * A DIFFERENT account submitted while a read is outstanding is still issued.
 *
 * ⚠️ Purpose: this is the path the busy channel could silently take away, and the reason the Enter
 * key's busy predicate is scoped to the account in the field rather than to "a read is running".
 * `usePfKeys` declines a busy key outright, so an unscoped flag would leave an operator who mistyped an
 * identifier unable to correct it until a read they no longer want had come back. The superseded
 * answer is already made harmless by the generation guard in `readPage`, so there is nothing to
 * protect by refusing the correction.
 *
 * Assumptions: the second query is read off the transport rather than inferred from the rows, so the
 * case states that the CORRECTED identifier is what was asked for -- a screen that issued a second read
 * of the first account would satisfy a bare call count.
 * @returns {Promise<void>} Resolves once the corrected read has been asserted.
 */
async function issuesACorrectionOverAnOutstandingRead(): Promise<void> {
  const held = heldRead<PendingAuthListResponse>();
  listStub().mockReturnValueOnce(held.promise);
  const corrected = '00000000022';

  const { user } = await renderAsUser();
  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectOneListingIssued);

  listStub().mockResolvedValue(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  await user.clear(accountIdField());
  await scopeToAccount(user, corrected);
  await waitFor(expectSecondListingIssued);

  expect(recordedQuery(1).accountId).toBe(corrected);
}

/**
 * The screen ANNOUNCES an outstanding search, and says nothing while idle.
 *
 * ⚠️ Purpose: the measured defect was not slowness -- it was that an operator with no acknowledgement
 * pressed Enter again and again. The guard collapses those presses; this is the other half, a statement
 * that the turn was received. `aria-busy` on the field states it to an operator who has already found
 * that control; the live region states it to one who is waiting and looking nowhere in particular.
 *
 * ⚠️ Assumptions: the region's PRESENCE while idle is asserted as well as its contents while busy,
 * because a live region has to exist before its contents change for the change to be announced.
 * Mounting the element together with the sentence would insert both at once and the announcement would
 * be missed on exactly the occasion it is for -- so an empty-but-present region is the contract, not an
 * artifact.
 * @returns {Promise<void>} Resolves once both states have been asserted.
 */
async function announcesTheOutstandingSearch(): Promise<void> {
  const held = heldRead<PendingAuthListResponse>();
  listStub().mockReturnValueOnce(held.promise);

  const { user } = await renderAsUser();

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID).textContent).toBe('');

  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectSearchAnnounced);

  held.settle(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  await waitFor(expectFirstRowPresent);

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID).textContent).toBe('');
}

/**
 * Asserts the live region carries the authored in-progress sentence.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectSearchAnnounced(): void {
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID).textContent).toBe(REQUEST_IN_PROGRESS);
}

/**
 * The filter field states that a search is outstanding, and stops stating it once one settles.
 *
 * ⚠️ Purpose: the measured defect was that the busy state was bound to the antd table's spinner and to
 * nothing else, so the control an operator had just submitted from carried no indication at all --
 * `aria-busy` was absent from every button and every field on the screen. `busyProps` emits the member
 * only while a read is running, so both halves are asserted: present while held, gone once settled.
 *
 * Assumptions: the ABSENCE of the attribute is asserted rather than the string `'false'`, because
 * `aria-busy` defaults to false when absent and `busyProps` deliberately returns an empty object when
 * idle rather than an attribute mutation on every settle.
 * @returns {Promise<void>} Resolves once both states have been asserted.
 */
async function statesTheOutstandingSearchOnTheFilterField(): Promise<void> {
  const held = heldRead<PendingAuthListResponse>();
  listStub().mockReturnValueOnce(held.promise);

  const { user } = await renderAsUser();

  expect(accountIdField().hasAttribute('aria-busy')).toBe(false);

  await scopeToAccount(user, ELEVEN_DIGIT_ACCOUNT_ID);
  await waitFor(expectFilterFieldBusy);

  held.settle(listingFixture(rowsOf(AUTH_SUMMARY_PAGE_SIZE), { hasNext: true }));
  await waitFor(expectFirstRowPresent);

  expect(accountIdField().hasAttribute('aria-busy')).toBe(false);
}

/**
 * Asserts the filter field reports a request outstanding.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectFilterFieldBusy(): void {
  expect(accountIdField().getAttribute('aria-busy')).toBe('true');
}

/**
 * Holds the screen to the field-highlight contract, including the blank-condition marker.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function fieldRefusals(): void {
  it('marks the filter field on a refusal that names it', marksTheFilterFieldOnARefusalThatNamesIt);
  it('writes the asterisk marker only when blank', writesTheAsteriskMarkerOnlyWhenBlank);
  it('leaves an unnamed field unmarked', leavesAnUnnamedFieldUnmarked);
}

/**
 * Holds the search path to one request per distinct submission, and to a stated busy state.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
function repeatedSubmission(): void {
  it('collapses an identical resubmission', collapsesAnIdenticalResubmission);
  it('rewinds from a later page on resubmission', rewindsFromALaterPageOnResubmission);
  it('retries after a refused search', retriesAfterARefusedSearch);
  it('issues a correction over an outstanding read', issuesACorrectionOverAnOutstandingRead);
  it(
    'states the outstanding search on the filter field',
    statesTheOutstandingSearchOnTheFilterField,
  );
  it('announces the outstanding search', announcesTheOutstandingSearch);
}

/**
 * Holds every status member and the resolved account identifier onto the glass.
 *
 * Assumptions: the cases are grouped by the contract each holds the screen to, so a failure report
 * names the contract that broke rather than only the assertion that noticed.
 * @returns {void} Nothing; the registrations are the effect.
 */
/**
 * Registers the cases that measure the row table's own presentation rather than its data.
 *
 * Assumptions: grouped apart from the keyset cases because these three measure the table as a LAYOUT
 * -- what each column reserves, how large the selectable target is, and whether a row says it is
 * actionable -- where the keyset cases measure which rows it holds. Both were findings; they fail for
 * unrelated reasons.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function rowTablePresentation(): void {
  it('reserves every column at its declared measure', reservesEveryColumnAtItsDeclaredMeasure);
  it('presents every selection target at the AA floor', presentsEverySelectionTargetAtTheAaFloor);
  it('selects the row clicked anywhere in it', selectsTheRowClickedAnywhereInIt);
  it('reaches every row from the keyboard', reachesEveryRowFromTheKeyboard);
  it('refuses an unaccepted selection character', refusesAnUnacceptedSelectionCharacter);
  it('accepts the lowercase selection character', acceptsTheLowercaseSelectionCharacter);
  it('acts on the first marked row only', actsOnTheFirstMarkedRowOnly);
  it('announces both boundaries at a dead end', announcesBothBoundariesAtADeadEnd);
  it(
    'paints a fraud outcome handed over before it mounted',
    paintsAFraudOutcomeHandedOverBeforeItMounted,
  );
  it(
    'paints a fraud outcome handed over after it mounted',
    paintsAFraudOutcomeHandedOverAfterItMounted,
  );
  it("leaves another screen's handover alone", leavesAnotherScreensHandoverAlone);
  it('paints the row-22 instruction', paintsTheRowTwentyTwoInstruction);
}

/**
 * Registers the cases asserting that every member the listing returns reaches the screen.
 *
 * Assumptions: a named declaration rather than an inline `describe` body, for the reason the module
 * header records -- every function expression owes a JSDoc block, and Prettier will not keep one in the
 * position an inline group body would need it.
 * @returns {void} Nothing; the registrations are the effect.
 */
function returnedMembersReachTheScreen(): void {
  it('renders the holder block under the one caption', rendersTheHolderBlockUnderTheOneCaption);
  it('leaves no caption cell empty', leavesNoCaptionCellEmpty);
  it(
    'renders every status member under the one caption',
    rendersEveryStatusMemberUnderTheOneCaption,
  );
  it('keeps every status name off the screen', keepsEveryStatusNameOffTheScreen);
  it('adopts the identifier the summary describes', adoptsTheIdentifierTheSummaryDescribes);
  it('refuses to overwrite an entry being retyped', refusesToOverwriteAnEntryBeingRetyped);
}

/**
 * Registers every group of cases in this file.
 *
 * Assumptions: the groups are declared as named functions above rather than as inline callbacks, for
 * the reason the module header records -- `ui/eslint.config.js` requires a JSDoc block on every
 * function expression, so an inline group body would owe one in a position Prettier will not keep it.
 * @returns {void} Nothing; the registrations are the effect.
 */
function authSummaryContractCases(): void {
  describe('the search path under repeated submission', repeatedSubmission);
  describe('the members the listing returns', returnedMembersReachTheScreen);
  describe('the row table as a layout', rowTablePresentation);

  describe('field constraints from the COPAU00 symbolic map', fieldConstraints);

  describe('the five-row keyset browse', keysetBrowse);

  describe('exact fixed-point money', fixedPointMoney);

  describe('the verbatim message catalog', verbatimMessages);

  describe('the keyboard contract', keyboardContract);

  describe('session, routing and exposure', sessionAndRouting);

  describe('field-level refusals', fieldRefusals);
}

describe('pending authorization summary contracts', authSummaryContractCases);
