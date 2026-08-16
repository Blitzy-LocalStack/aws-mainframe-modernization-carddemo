/**
 * @file Component tests for `ui/src/screens/authSummary/index.tsx` and
 * `ui/src/screens/refTypeList/index.tsx`.
 *
 * Purpose
 * -------
 * Cover what each screen presents: its opening state and whether a request runs on mount, its filter
 * field at the declared width, the refusal sentences it renders for each unusable entry, the legend
 * the mapset paints, a successful listing rendering its rows, and a refused listing reporting through
 * the shared band.
 *
 * Assumptions: the two screens are covered together because their opening behaviour is a matched PAIR
 * and the contrast is the point. The authorization summary is scoped by an account, so it must issue
 * NOTHING until one is supplied; the reference list is unscoped, so it must issue its first page on
 * mount. Both are `usePagedQuery` callers, so a change that added or dropped the enablement gate would
 * break exactly one of them -- and a suite that only tested the screen it broke would report a
 * plausible pass on the other.
 *
 * Assumptions: the entry-validation cases assert the RENDERED sentence rather than only calling the
 * exported predicate, and they assert whether a request was issued. Both screens narrowed their entry
 * rule to the declared field width, and the defect the narrowing closed was visible only end to end:
 * an entry the screen accepted reached a service whose contract refuses it, turning the reference's own
 * refusal sentence into a transport failure. A predicate-only case cannot observe that.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { listPendingAuthorizations } from '../api/authorization';
import {
  deleteTransactionType,
  listTransactionTypes,
  replaceTransactionType,
} from '../api/reference';
import type {
  ApiError,
  PageResponse,
  PendingAuthListResponse,
  TransactionType,
} from '../api/types';
import { AppShell } from '../layout/AppShell';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { PROGRAM_MESSAGES } from '../messages/messages';
import { MAIN_MENU_ROUTE } from '../routes/navigation';
import { cardDemoTheme } from '../theme/antdTheme';
import {
  AUTH_SUMMARY_FIELD_WIDTHS,
  AUTH_SUMMARY_KEY_LABELS,
  AUTH_SUMMARY_LABELS,
  AUTH_SUMMARY_PROGRAM_NAME,
  AUTH_SUMMARY_TRANSACTION_ID,
  AuthSummaryScreen,
  classifyAccountIdEntry,
} from './authSummary';
import RefTypeListScreen, {
  REF_TYPE_LIST_KEY_LABELS,
  REF_TYPE_LIST_LABELS,
  TYPE_CODE_LENGTH,
  validateTypeFilter,
} from './refTypeList';

const AUTH_MESSAGES = PROGRAM_MESSAGES.COPAUS0C;
const ACCOUNT_ID = '00000000011';
const MENU_MARKER = 'MAIN MENU REACHED';

/**
 * Builds the mocked authorization transport surface.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory -- Vitest lifts every `vi.mock`
 * above the imports, so a factory in a `const` would be in its temporal dead zone at registration.
 * @returns {Record<string, unknown>} The transport surface.
 */
function mockAuthorizationModule(): Record<string, unknown> {
  return { listPendingAuthorizations: vi.fn() };
}

/**
 * Builds the mocked reference transport surface.
 *
 * Assumptions: the three operations this screen imports are stubbed and nothing else, so an added
 * import fails loudly here rather than reaching a real request through a partially mocked module.
 * @returns {Record<string, unknown>} The transport surface.
 */
function mockReferenceModule(): Record<string, unknown> {
  return {
    listTransactionTypes: vi.fn(),
    replaceTransactionType: vi.fn(),
    deleteTransactionType: vi.fn(),
  };
}

vi.mock('../api/authorization', mockAuthorizationModule);
vi.mock('../api/reference', mockReferenceModule);

/**
 * Collapses runs of whitespace the way Testing Library's default normaliser does.
 * @param {string} value - Catalogued literal carrying its declared pad.
 * @returns {string} The same text with whitespace runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Reads one element's text with its whitespace collapsed.
 * @param {Element} element - Element whose text is read.
 * @returns {string} The collapsed text.
 */
function collapsedTextOf(element: Element): string {
  return collapse(element.textContent ?? '');
}

/**
 * Returns the legend region the shared key bar paints.
 * @returns {HTMLElement} The legend region.
 */
function legendRegion(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads the legend's controls as collapsed labels, in painted order.
 * @returns {readonly string[]} The collapsed label of every control the legend paints.
 */
function legendLabels(): readonly string[] {
  return Array.from(legendRegion().querySelectorAll('button')).map(collapsedTextOf);
}

/**
 * Builds a predicate matching a control whose collapsed label equals the wanted one.
 * @param {string} wanted - The collapsed label to match.
 * @returns {(element: Element) => boolean} The predicate.
 */
function matchesCollapsedLabel(wanted: string): (element: Element) => boolean {
  return matches;

  /**
   * Reports whether this element carries the wanted label.
   * @param {Element} element - Candidate control.
   * @returns {boolean} Whether the label matches.
   */
  function matches(element: Element): boolean {
    return collapsedTextOf(element) === wanted;
  }
}

/**
 * Builds an assertion that the legend paints a control with the wanted label.
 * @param {string} wanted - The collapsed label the legend must paint.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertLegendPaints(wanted: string): () => void {
  return assertPainted;

  /**
   * Asserts the label is among those the legend paints.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertPainted(): void {
    expect(legendLabels(), `the legend must paint ${wanted}`).toContain(wanted);
  }
}

/**
 * Activates a legend control by its painted label.
 * @param {string} label - The legend label as the mapset paints it.
 * @returns {Promise<void>} Resolves once the control has been activated.
 */
async function pressLegendKey(label: string): Promise<void> {
  const wanted = collapse(label);
  await waitFor(assertLegendPaints(wanted));
  const [control] = Array.from(legendRegion().querySelectorAll('button')).filter(
    matchesCollapsedLabel(wanted),
  );
  await userEvent.click(control as HTMLElement);
}

/**
 * An authorization listing carrying one summary and one row.
 *
 * Assumptions: the account identifier and the card number are synthetic. The card number carries the
 * masked shape the service returns for a non-administrative read, so no unmasked primary account
 * number appears in a test file.
 * @returns {PendingAuthListResponse} The listing a successful scoped read returns.
 */
function authListing(): PendingAuthListResponse {
  return {
    summary: {
      accountId: ACCOUNT_ID,
      customerId: '000000011',
      authStatus: 'A',
      accountStatus1: 'Y',
      accountStatus2: null,
      accountStatus3: null,
      accountStatus4: null,
      accountStatus5: null,
      creditLimit: '5000.00',
      cashLimit: '1500.00',
      creditBalance: '1234.56',
      cashBalance: '0.00',
      approvedAuthCnt: 3,
      declinedAuthCnt: 1,
      approvedAuthAmt: '450.00',
      declinedAuthAmt: '75.00',
      customerName: 'PAUL T BUCK',
      addressLine1: '742 EVERGREEN TERRACE',
      addressLine2: 'APT 4B',
      phoneNumber1: '(312)5550101',
    },
    page: {
      items: [
        {
          key: 'auth-selector-synthetic-0000000000000000000000001',
          transactionId: 'TRAN000000000001',
          authOrigDate: '2023-04-01',
          authOrigTime: '10:15:00',
          authType: 'P',
          approvalStatus: 'A',
          matchStatus: 'P',
          amount: '150.00',
          cardNum: '************0011',
        },
      ],
      firstKey: 'auth-selector-synthetic-0000000000000000000000001',
      lastKey: 'auth-selector-synthetic-0000000000000000000000001',
      hasNext: false,
    },
    screenMessage: null,
  };
}

/**
 * Builds a normalised problem document, which is what a refused request rejects with.
 *
 * Assumptions: a full document is built rather than a bare `Error`, because the paging hook normalises
 * a rejection through the same recogniser the client uses and settles anything unrecognised to `null`
 * -- and a `null` problem is deliberately reported as nothing at all on this screen. Rejecting with a
 * bare error would therefore assert the absence of a message and read as though the refusal path had
 * been covered.
 * @param {number} status - HTTP status the refusal carried.
 * @param {string} message - Sentence the service reported.
 * @returns {ApiError} The problem document.
 */
function problem(status: number, message: string): ApiError {
  return {
    code: 'CARDDEMO-TEST',
    secondaryCode: '0000',
    message,
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: '00000000-0000-4000-8000-000000000001',
    path: '/api/v1/authorizations',
    timestamp: '2026-08-15T00:00:00.000Z',
    fieldErrors: [],
    abend: null,
  };
}

/**
 * A page of transaction types, populated so each row is distinguishable.
 * @returns {PageResponse<TransactionType>} The page a successful list returns.
 */
function typePage(): PageResponse<TransactionType> {
  return {
    items: [
      { typeCd: '01', description: 'PURCHASE', version: 1 },
      { typeCd: '02', description: 'PAYMENT', version: 1 },
    ],
    firstKey: '01',
    lastKey: '02',
    hasNext: false,
  };
}

/**
 * Renders one screen at a path, with the main menu mounted so navigation is observable.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderScreen(element: ReactElement): void {
  render(
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/screen']}>
        {/*
          WHY : ⚠️ Refactoring Rationale: the screen is rendered INSIDE `AppShell`, where it was rendered
                bare. The screen delegates its title band, its row-23 message line and its row-24 legend to
                the one shell that `ui/src/router.tsx` mounts as a layout route -- it composes none of the
                three itself -- so a bare render produced a screen with no legend and no band, and every
                query for either failed on a screen that is in fact correct. The children form is used
                rather than a layout route because it is the shape that needs no second route level, and
                `AppShell` renders `children ?? <Outlet />`, so both forms paint the same frame.
        */}
        <AppShell>
          <Routes>
            <Route path="/screen" element={element} />
            <Route path={MAIN_MENU_ROUTE} element={<div>{MENU_MARKER}</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>,
  );
}

/**
 * Reads the authorization screen's account scope control.
 * @returns {HTMLElement} The scope control.
 */
function accountScopeControl(): HTMLElement {
  return screen.getByLabelText(collapse(AUTH_SUMMARY_LABELS.searchAccountId));
}

/**
 * Reads the reference screen's type filter control.
 * @returns {HTMLElement} The filter control.
 */
function typeFilterControl(): HTMLElement {
  return screen.getByLabelText(collapse(REF_TYPE_LIST_LABELS.typeFilter));
}

/**
 * Types an account scope and submits the turn with Enter.
 * @param {string} entry - The scope entry to type, or the empty string to submit nothing.
 * @returns {Promise<void>} Resolves once the turn has been submitted.
 */
async function scopeWith(entry: string): Promise<void> {
  const control = accountScopeControl();
  await userEvent.clear(control);
  if (entry !== '') {
    await userEvent.type(control, entry);
  }
  await userEvent.keyboard('{Enter}');
}

/**
 * Types a type filter and submits the turn with Enter.
 * @param {string} entry - The filter entry to type, or the empty string to submit nothing.
 * @returns {Promise<void>} Resolves once the turn has been submitted.
 */
async function filterWith(entry: string): Promise<void> {
  const control = typeFilterControl();
  await userEvent.clear(control);
  if (entry !== '') {
    await userEvent.type(control, entry);
  }
  await userEvent.keyboard('{Enter}');
}

/**
 * Resets the transport spies so no case inherits another's answer.
 * @returns {void} Nothing.
 */
function resetTransport(): void {
  vi.mocked(listPendingAuthorizations).mockReset();
  vi.mocked(listTransactionTypes).mockReset();
  vi.mocked(replaceTransactionType).mockReset();
  vi.mocked(deleteTransactionType).mockReset();
}

/**
 * The authorization summary issues nothing until an account has been scoped.
 *
 * Assumptions: this is the enablement gate, and the absence of a call is the whole assertion. The
 * screen is scoped by an account, so a request issued on mount would ask the service for the pending
 * authorizations of an empty identifier -- a guaranteed refusal, on every visit, before the operator
 * has typed anything.
 * @returns {Promise<void>} Resolves once the opening state has been asserted.
 */
async function theAuthSummaryIssuesNothingUntilScoped(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(accountScopeControl()).toHaveValue('');
  expect(listPendingAuthorizations).not.toHaveBeenCalled();
}

/**
 * The authorization scope control carries the eleven-character declared width.
 * @returns {Promise<void>} Resolves once the width has been asserted.
 */
async function theAuthScopeCarriesItsDeclaredWidth(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(accountScopeControl()).toHaveAttribute(
    'maxLength',
    String(AUTH_SUMMARY_FIELD_WIDTHS.accountId),
  );
}

/**
 * A blank scope is refused with the reference's own prompt sentence and issues nothing.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aBlankScopeIsRefusedWithThePromptSentence(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await scopeWith('');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(assertBandStates(band, collapse(AUTH_MESSAGES.PLEASE_ENTER_ACCT_ID)));
  expect(listPendingAuthorizations).not.toHaveBeenCalled();
}

/**
 * A scope short of eleven digits is refused with the numeric sentence and issues nothing.
 *
 * Assumptions: a SHORT entry is the case that matters here, and it is asserted separately from a
 * non-digit one. `maxLength` bounds an entry from above only, so before the width was required a
 * ten-digit entry passed the screen and reached a service whose contract declares the parameter
 * `^[0-9]{11}$` -- turning the reference's own sentence into an HTTP 400. The sentence is the numeric
 * one rather than a width-specific one because the terminal reports exactly that: the padded field
 * fails the single `IS NOT NUMERIC` test the program makes.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aShortScopeIsRefusedWithTheNumericSentence(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await scopeWith('1234567890');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(assertBandStates(band, collapse(AUTH_MESSAGES.ACCT_ID_MUST_BE_NUMERIC)));
  expect(listPendingAuthorizations).not.toHaveBeenCalled();
}

/**
 * A scope carrying a non-digit is refused with the numeric sentence and issues nothing.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aNonNumericScopeIsRefusedWithTheNumericSentence(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await scopeWith('0000000001X');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(assertBandStates(band, collapse(AUTH_MESSAGES.ACCT_ID_MUST_BE_NUMERIC)));
  expect(listPendingAuthorizations).not.toHaveBeenCalled();
}

/**
 * The exported classifier agrees with the rendered outcomes at each boundary.
 *
 * Assumptions: the classifier is exercised directly ALONGSIDE the rendered cases rather than instead
 * of them, because the boundary has three sides -- one short of the width, exactly the width, and one
 * over it -- and driving all three through the control is impossible: `maxLength` prevents an
 * over-width entry from ever being typed. The direct call is the only way to state what the rule does
 * with a value the control cannot produce but a paste or a programmatic set could.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theScopeClassifierAgreesAtEveryBoundary(): void {
  expect(classifyAccountIdEntry('')).toBe('BLANK');
  expect(classifyAccountIdEntry('   ')).toBe('BLANK');
  expect(classifyAccountIdEntry('1234567890')).toBe('NOT_OK');
  expect(classifyAccountIdEntry('123456789012')).toBe('NOT_OK');
  expect(classifyAccountIdEntry('0000000001X')).toBe('NOT_OK');
  expect(classifyAccountIdEntry(ACCOUNT_ID)).toBeNull();
  expect(classifyAccountIdEntry('00000000000')).toBeNull();
}

/**
 * A well-formed scope issues the read and renders the returned row.
 * @returns {Promise<void>} Resolves once the listing has been rendered.
 */
async function aWellFormedScopeRendersTheListing(): Promise<void> {
  vi.mocked(listPendingAuthorizations).mockResolvedValue(authListing());

  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await scopeWith(ACCOUNT_ID);

  expect(await screen.findByText('TRAN000000000001')).toBeInTheDocument();
  expect(listPendingAuthorizations).toHaveBeenCalled();
  expect(screen.getByText('PAUL T BUCK')).toBeInTheDocument();
}

/**
 * A refused listing reports through the shared band rather than a component of its own.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aRefusedListingReportsThroughTheBand(): Promise<void> {
  vi.mocked(listPendingAuthorizations).mockRejectedValue(
    problem(500, 'Authorization listing refused'),
  );

  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await scopeWith(ACCOUNT_ID);

  const alert = await screen.findByRole('alert');
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toContainElement(alert);
  expect(alert.textContent).not.toBe('');
}

/**
 * The authorization summary paints the four legend keys its mapset advertises.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theAuthSummaryPaintsItsLegend(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  const labels = legendLabels();
  expect(labels).toContain(collapse(AUTH_SUMMARY_KEY_LABELS.ENTER));
  expect(labels).toContain(collapse(AUTH_SUMMARY_KEY_LABELS.PFK03));
}

/**
 * The authorization summary delegates its identity, so the frame paints its title band.
 *
 * Assumptions: the screen is rendered INSIDE the frame, because it composes no title band of its own --
 * its contract records that the band is delegated rather than mounted -- so the identity is observable
 * only when the frame that receives the delegation is mounted around it. The frame is given
 * unconfigured, matching the production table: the frame binds no function key of its own, so its PF12 does not contend
 * with the legend this screen paints.
 *
 * Assumptions: this case exists because both exported identity constants were previously read by
 * nothing at all. The delegation mechanism was authored and unused, so an operator had no `Tran:` or
 * `Prog:` slot on this screen while every sibling painted both -- and no test would have noticed.
 * @returns {Promise<void>} Resolves once the delegated identity has painted.
 */
async function theAuthSummaryDelegatesItsIdentityToTheFrame(): Promise<void> {
  render(
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/screen']}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/screen" element={<AuthSummaryScreen />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ConfigProvider>,
  );

  expect(await screen.findByText(AUTH_SUMMARY_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(AUTH_SUMMARY_PROGRAM_NAME)).toBeInTheDocument();
}

/**
 * The authorization summary's back key returns to the menu.
 * @returns {Promise<void>} Resolves once the menu has been reached.
 */
async function theAuthSummaryBackKeyReturnsToTheMenu(): Promise<void> {
  renderScreen(<AuthSummaryScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await pressLegendKey(AUTH_SUMMARY_KEY_LABELS.PFK03);

  expect(await screen.findByText(MENU_MARKER)).toBeInTheDocument();
}

/**
 * The reference list issues its first page on mount and renders the returned rows.
 *
 * Assumptions: this screen is the counterpart to the enablement gate above -- it is unscoped, so its
 * opening state DOES read. Asserting the call is what distinguishes the two behaviours; without it a
 * gate added here by analogy would leave the screen permanently empty with nothing to show why.
 * @returns {Promise<void>} Resolves once the first page has been rendered.
 */
async function theReferenceListReadsOnMount(): Promise<void> {
  vi.mocked(listTransactionTypes).mockResolvedValue(typePage());

  renderScreen(<RefTypeListScreen />);

  expect(await screen.findByText('PURCHASE')).toBeInTheDocument();
  expect(listTransactionTypes).toHaveBeenCalled();
  expect(screen.getByText('PAYMENT')).toBeInTheDocument();
}

/**
 * The type filter control carries the two-character declared width.
 * @returns {Promise<void>} Resolves once the width has been asserted.
 */
async function theTypeFilterCarriesItsDeclaredWidth(): Promise<void> {
  vi.mocked(listTransactionTypes).mockResolvedValue(typePage());

  renderScreen(<RefTypeListScreen />);

  await screen.findByText('PURCHASE');
  expect(typeFilterControl()).toHaveAttribute('maxLength', String(TYPE_CODE_LENGTH));
}

/**
 * A one-digit type filter is refused with the two-digit sentence and issues no further read.
 *
 * Assumptions: a SINGLE digit is the entry that matters. `maxLength` bounded the field from above, so
 * before the width was required a one-digit entry passed the screen and reached a service whose
 * contract declares the parameter `^[0-9]{2}$`; the reference's field is `TYPCODE PIC X(2)`, so two is
 * the whole domain rather than a maximum.
 *
 * Assumptions: the call count is compared with the count BEFORE the turn rather than asserted zero,
 * because this screen legitimately reads on mount -- an absolute assertion would fail for the opening
 * read and say nothing about the refused turn.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aOneDigitTypeFilterIsRefused(): Promise<void> {
  vi.mocked(listTransactionTypes).mockResolvedValue(typePage());

  renderScreen(<RefTypeListScreen />);

  await screen.findByText('PURCHASE');
  const before = vi.mocked(listTransactionTypes).mock.calls.length;

  await filterWith('1');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(assertBandNotEmpty(band));
  expect(vi.mocked(listTransactionTypes).mock.calls.length).toBe(before);
}

/**
 * A two-digit type filter is accepted, and one that NARROWS issues a further read.
 *
 * Assumptions: `'00'` is accepted deliberately, and it is asserted here rather than left implicit. Two
 * zeroes are two digits, so the rule admits them; whether a type coded `00` exists is the service's
 * answer to give, and refusing it on the screen would invent a refusal the reference has no sentence
 * for.
 *
 * WHY : ⚠️ Refactoring Rationale: `'00'` is asserted to be ACCEPTED, and the further read is asserted of
 *       `'01'` instead -- this case pressed `'00'` and required a new call, which it never gets. All
 *       zeroes are "no narrowing" in the reference, not a narrowing to type zero: `1220-EDIT-TYPECD` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1101-L1107 exits on `LOW-VALUES`, `SPACES` OR
 *       `ZEROS`, so `canonicalTypeFilter` reduces `'00'` to the empty filter and the applied narrowing is
 *       unchanged from the opening read -- the screen correctly re-sends what is already on the glass
 *       rather than asking the service for the same rows again. Requiring a call there would have forced
 *       the screen to re-read on a turn that changes nothing, which is the request-per-keystroke the
 *       comparison is placed after canonicalisation to avoid. Both halves of the case's own name are now
 *       asserted: two digits are accepted, and a two-digit filter that narrows is acted on.
 * @returns {Promise<void>} Resolves once the acceptance and the filtered read have been observed.
 */
async function aTwoDigitTypeFilterIsAccepted(): Promise<void> {
  vi.mocked(listTransactionTypes).mockResolvedValue(typePage());

  renderScreen(<RefTypeListScreen />);

  await screen.findByText('PURCHASE');
  const before = vi.mocked(listTransactionTypes).mock.calls.length;

  await filterWith('00');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(collapse(band.textContent ?? '')).not.toBe(collapse(validateTypeFilter('1') ?? ''));
  expect(validateTypeFilter('00')).toBeNull();
  expect(vi.mocked(listTransactionTypes).mock.calls.length).toBe(before);

  await filterWith('01');

  await waitFor(assertCallsExceed(before));
}

/**
 * The exported filter rule agrees with the rendered outcomes at each boundary.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theTypeFilterRuleAgreesAtEveryBoundary(): void {
  expect(validateTypeFilter('')).toBeNull();
  expect(validateTypeFilter('   ')).toBeNull();
  expect(validateTypeFilter('01')).toBeNull();
  expect(validateTypeFilter('00')).toBeNull();
  expect(validateTypeFilter('1')).not.toBeNull();
  expect(validateTypeFilter('123')).not.toBeNull();
  expect(validateTypeFilter('1A')).not.toBeNull();
}

/**
 * A refused listing on the reference screen reports through the shared band.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aRefusedTypeListingReportsThroughTheBand(): Promise<void> {
  vi.mocked(listTransactionTypes).mockRejectedValue(new Error('transport'));

  renderScreen(<RefTypeListScreen />);

  const alert = await screen.findByRole('alert');
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toContainElement(alert);
}

/**
 * The reference list paints the five legend keys its mapset advertises.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theReferenceListPaintsItsLegend(): Promise<void> {
  vi.mocked(listTransactionTypes).mockResolvedValue(typePage());

  renderScreen(<RefTypeListScreen />);

  await screen.findByText('PURCHASE');
  const labels = legendLabels();
  for (const label of Object.values(REF_TYPE_LIST_KEY_LABELS)) {
    expect(labels, `the legend must paint ${collapse(label)}`).toContain(collapse(label));
  }
}

/**
 * Builds an assertion that a band has settled on the wanted sentence.
 * @param {HTMLElement} band - The band whose text is read.
 * @param {string} wanted - The collapsed sentence the band must carry.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertBandStates(band: HTMLElement, wanted: string): () => void {
  return assertStated;

  /**
   * Asserts the band carries the wanted sentence.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertStated(): void {
    expect(band).toHaveTextContent(wanted);
  }
}

/**
 * Builds an assertion that a band is carrying some sentence.
 * @param {HTMLElement} band - The band whose text is read.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertBandNotEmpty(band: HTMLElement): () => void {
  return assertPopulated;

  /**
   * Asserts the band is not empty.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertPopulated(): void {
    expect(collapsedTextOf(band)).not.toBe('');
  }
}

/**
 * Builds an assertion that the list operation has been called more than a recorded number of times.
 * @param {number} before - Call count recorded before the turn.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertCallsExceed(before: number): () => void {
  return assertExceeded;

  /**
   * Asserts a further call has been issued.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertExceeded(): void {
    expect(vi.mocked(listTransactionTypes).mock.calls.length).toBeGreaterThan(before);
  }
}

/**
 * Registers the authorization-summary and reference-list cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function referenceAndAuthScreenCases(): void {
  beforeEach(resetTransport);
  afterEach(resetTransport);

  it('issues nothing until an account has been scoped', theAuthSummaryIssuesNothingUntilScoped);
  it('bounds the account scope to its declared width', theAuthScopeCarriesItsDeclaredWidth);
  it('refuses a blank scope with the prompt sentence', aBlankScopeIsRefusedWithThePromptSentence);
  it('refuses a short scope with the numeric sentence', aShortScopeIsRefusedWithTheNumericSentence);
  it(
    'refuses a non-numeric scope with the numeric sentence',
    aNonNumericScopeIsRefusedWithTheNumericSentence,
  );
  it('classifies every scope boundary consistently', theScopeClassifierAgreesAtEveryBoundary);
  it('renders the listing for a well-formed scope', aWellFormedScopeRendersTheListing);
  it('reports a refused listing through the band', aRefusedListingReportsThroughTheBand);
  it('paints the authorization legend', theAuthSummaryPaintsItsLegend);
  it('delegates its identity to the frame', theAuthSummaryDelegatesItsIdentityToTheFrame);
  it('returns to the menu from the authorization summary', theAuthSummaryBackKeyReturnsToTheMenu);
  it('reads the first page of transaction types on mount', theReferenceListReadsOnMount);
  it('bounds the type filter to its declared width', theTypeFilterCarriesItsDeclaredWidth);
  it('refuses a one-digit type filter', aOneDigitTypeFilterIsRefused);
  it('accepts a two-digit type filter', aTwoDigitTypeFilterIsAccepted);
  it(
    'applies the filter rule consistently at every boundary',
    theTypeFilterRuleAgreesAtEveryBoundary,
  );
  it('reports a refused type listing through the band', aRefusedTypeListingReportsThroughTheBand);
  it('paints the reference-list legend', theReferenceListPaintsItsLegend);
}

describe('the authorization summary and reference type list screens', referenceAndAuthScreenCases);
