/**
 * @file Component tests for the pending-authorization summary in `ui/src/screens/authSummary/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the properties a review found this screen had lost: that a short account identifier is refused
 * locally in the source's own words rather than sent for the service to refuse, that the filter control
 * states its refusal programmatically as well as visually, that the two unlabelled address values are
 * named for assistive technology, that the five row controls are ONE selection set, that the eight-column
 * table has a narrow-screen policy, that the record panel takes the shared responsive column policy, and
 * that route changes go through the shared navigation seam rather than a second copy of it.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, matching
 * `ui/src/screens/accountView/accountView.test.tsx` and `ui/src/screens/accountUpdate/accountUpdate.test.tsx`.
 * These cases are about what the SCREEN does with an outcome, so the shortest honest seam is the function
 * the screen calls.
 *
 * Assumptions: two properties are asserted against the module's SOURCE rather than its rendered output,
 * and both are properties of the code by nature: that this screen consumes the shared column policy
 * instead of restating a column count, and that it holds no second copy of the navigation fallback. A
 * rendered assertion cannot distinguish either — antd resolves a responsive column object itself, and two
 * identical navigation policies produce identical navigations — so the check is made where the difference
 * exists. The sibling module is located with `join(import.meta.dirname, ...)` and NOT with
 * `new URL('./index.tsx', import.meta.url)`: Vite rewrites that exact syntax as its asset-reference
 * pattern at transform time, yielding an `http:` URL that `readFileSync` rejects.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for the
 * reason the sibling suites record: `ui/eslint.config.js` selects a function expression in every position
 * so an inline callback owes its own JSDoc block, and Prettier moves a block comment that follows an
 * argument comma onto the preceding literal, detaching it from what it documents.
 */

import { ConfigProvider } from 'antd';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { listPendingAuthorizations } from '../../api/authorization';
import { ApiRequestError } from '../../api/client';
import type {
  ApiError,
  FieldError,
  PendingAuthListItem,
  PendingAuthSummary,
} from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { fieldErrorId } from '../../layout/fieldHelp';
import { cardDemoTheme } from '../../theme/antdTheme';
import {
  AUTH_SUMMARY_HIDDEN_LABELS,
  AUTH_SUMMARY_SELECTION_PROMPT,
  AuthSummaryScreen,
  accountIdRefusal,
  selectionActionLabel,
} from './index';

/**
 * Builds the mocked surface of the authorization transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The transport functions this screen can reach, each a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    listPendingAuthorizations: vi.fn(),
    readPendingAuthorization: vi.fn(),
    markAuthorizationFraud: vi.fn(),
  };
}

vi.mock('../../api/authorization', mockAuthorizationTransportModule);

/** Identifier of the account filter control, as the screen declares it. */
const ACCOUNT_ID_CONTROL_ID = 'auth-summary-account-id';

/** An account identifier of exactly the declared eleven-digit width. */
const ELEVEN_DIGIT_ACCOUNT_ID = '00000000011';

/** An account identifier of nine digits — numeric, and narrower than the field the source pads to. */
const SHORT_ACCOUNT_ID = '000000011';

/** How many rows one page of the source's mapset paints. */
const PAGE_ROWS = 3;

/**
 * Builds the account summary panel the listing returns.
 * @returns {PendingAuthSummary} The summary for the screen to render.
 */
function summary(): PendingAuthSummary {
  return {
    accountId: ELEVEN_DIGIT_ACCOUNT_ID,
    customerName: 'ADA LOVELACE',
    customerId: '000000011',
    addressLine1: '1 ANALYTICAL WAY',
    addressLine2: 'SUITE 1843',
    authStatus: 'Y',
    phoneNumber1: '(212)5550101',
    approvedAuthCnt: 3,
    declinedAuthCnt: 1,
    creditLimit: '5000.00',
    cashLimit: '1000.00',
    creditBalance: '250.00',
    cashBalance: '0.00',
    approvedAuthAmt: '250.00',
    declinedAuthAmt: '10.00',
    accountStatus1: 'AA',
    accountStatus2: 'BB',
    accountStatus3: 'CC',
    accountStatus4: 'DD',
    accountStatus5: 'EE',
  };
}

/**
 * Builds one listed authorization whose identifier is recognisable in an assertion.
 * @param {number} ordinal - Which row this is, used to make every value distinct.
 * @returns {PendingAuthListItem} One row for the table.
 */
function listItem(ordinal: number): PendingAuthListItem {
  const suffix = String(ordinal).padStart(2, '0');

  return {
    key: `SEALED-KEY-${suffix}`,
    transactionId: `TRAN00000000${suffix}`,
    authOrigDate: `07/1${suffix.slice(-1)}/22`,
    authOrigTime: '10:11:12',
    authType: 'PURC',
    approvalStatus: 'A',
    matchStatus: 'P',
    amount: '  1234.56',
    cardNum: '************1111',
  };
}

/**
 * Builds the answer the listing returns, carrying only the members the screen reads.
 * @returns {object} The response shape the screen destructures.
 */
function listing(): {
  readonly summary: PendingAuthSummary;
  readonly screenMessage: string | null;
  readonly page: {
    readonly items: readonly PendingAuthListItem[];
    readonly firstKey: string | null;
    readonly lastKey: string | null;
    readonly hasNext: boolean;
    readonly hasPrevious: boolean;
  };
} {
  const items = Array.from(
    { length: PAGE_ROWS },
    /**
     * Builds the row at one position of the page.
     * @param {unknown} _unused - The array slot's value, which is always `undefined` here.
     * @param {number} index - The zero-based position being filled.
     * @returns {PendingAuthListItem} That position's row.
     */
    (_unused: unknown, index: number): PendingAuthListItem => listItem(index + 1),
  );

  return {
    summary: summary(),
    screenMessage: null,
    page: {
      items,
      firstKey: items[0]?.key ?? null,
      lastKey: items[items.length - 1]?.key ?? null,
      hasNext: false,
      hasPrevious: false,
    },
  };
}

/**
 * Builds the normalised failure the shared client raises for a refused request.
 * @param {readonly FieldError[]} fieldErrors - Per-field entries the document names.
 * @returns {ApiRequestError} The rejection to configure the stub with.
 */
function refusal(fieldErrors: readonly FieldError[]): ApiRequestError {
  const problem: ApiError = {
    code: 'CARDDEMO-PAUS-0001',
    secondaryCode: '',
    message: 'Please correct the highlighted fields',
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId: 'UITESTAUTH000000000AA',
    path: '/api/v1/authorizations',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors,
    abend: null,
  };

  return new ApiRequestError('PROBLEM', 400, problem, 'PROBLEM 400');
}

/**
 * Renders the screen inside the theme, the one shell and a router.
 * @returns {ReactElement} The composed tree under test.
 */
function renderAuthSummary(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/authorizations']}>
        <AppShell>
          <Routes>
            <Route path="/authorizations" element={<AuthSummaryScreen />} />
            <Route path="/authorizations/:key" element={<div>AUTHORIZATION DETAIL</div>} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Returns the stub standing in for the listing.
 * @returns {MockedFunction<typeof listPendingAuthorizations>} The mocked transport function.
 */
function listStub(): MockedFunction<typeof listPendingAuthorizations> {
  return vi.mocked(listPendingAuthorizations);
}

/** Clears the transport stub so no case inherits another's queued outcome. */
function resetTransport(): void {
  listStub().mockReset();
}

/**
 * Returns the account filter control.
 * @returns {HTMLInputElement} The rendered control.
 */
function filterControl(): HTMLInputElement {
  const element = document.getElementById(ACCOUNT_ID_CONTROL_ID);
  expect(element).not.toBeNull();

  return element as HTMLInputElement;
}

/**
 * Reads this module's own source, for the two properties that exist only in the code.
 * @returns {string} The module source.
 */
function moduleSource(): string {
  return readFileSync(join(import.meta.dirname, 'index.tsx'), 'utf8');
}

/**
 * Types an account identifier into the filter and submits the turn.
 * @param {string} entry - The identifier to type.
 * @returns {Promise<void>} Resolves once the turn has been submitted.
 */
async function submitFilter(entry: string): Promise<void> {
  render(renderAuthSummary());
  await userEvent.type(filterControl(), entry);
  await userEvent.keyboard('{Enter}');
}

/**
 * Reads a page so the panel and the table are populated.
 * @returns {Promise<void>} Resolves once the first row is on screen.
 */
async function fetchPage(): Promise<void> {
  listStub().mockResolvedValue(listing());
  await submitFilter(ELEVEN_DIGIT_ACCOUNT_ID);
  await screen.findByText(listItem(1).transactionId);
}

/**
 * A numeric identifier narrower than the declared field is refused locally, in the source's words.
 *
 * Assumptions: BOTH halves are asserted — the sentence appears AND no request was issued — because the
 * defect was that a short entry passed local validation and was sent, so a case that only checked the
 * sentence would pass against a screen that showed the service's refusal one round trip later.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAShortIdentifierLocally(): Promise<void> {
  await submitFilter(SHORT_ACCOUNT_ID);

  await waitFor(
    /**
     * Waits for the numeric refusal the program moves into its message field.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.getByText(accountIdRefusal('NOT_OK'))).toBeInTheDocument();
    },
  );
  expect(listStub()).not.toHaveBeenCalled();
}

/**
 * An identifier of exactly the declared width is accepted and reaches the transport.
 *
 * Assumptions: this is the other side of the width rule, and it is asserted because a fix that refused
 * everything would satisfy the refusal case on its own.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function acceptsTheDeclaredWidth(): Promise<void> {
  await fetchPage();

  expect(listStub()).toHaveBeenCalledWith({ accountId: ELEVEN_DIGIT_ACCOUNT_ID });
}

/**
 * The filter states a service refusal programmatically as well as visually.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function linksTheFilterRefusalToTheControl(): Promise<void> {
  listStub().mockRejectedValue(
    refusal([{ field: 'accountId', state: 'NOT_OK', message: 'Acct Id must be Numeric ...' }]),
  );

  await submitFilter(ELEVEN_DIGIT_ACCOUNT_ID);

  await waitFor(
    /**
     * Waits for the refusal to reach the control the document named.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(filterControl()).toHaveAttribute('aria-invalid', 'true');
    },
  );
  const help = document.getElementById(fieldErrorId(ACCOUNT_ID_CONTROL_ID));
  expect(help).not.toBeNull();
  expect(help?.textContent).toBe('Acct Id must be Numeric ...');
  expect(filterControl().getAttribute('aria-describedby')).toContain(
    fieldErrorId(ACCOUNT_ID_CONTROL_ID),
  );
}

/**
 * Both address values carry a name, and no panel entry is left nameless.
 *
 * Assumptions: the assertion is that NO label cell in the panel is empty, rather than that the two
 * address cells hold particular text. An empty header cell is the defect — a value announced with no
 * name — so asserting its absence catches any entry that loses its name, not only these two.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function namesBothAddressLines(): Promise<void> {
  await fetchPage();

  expect(screen.getByText(AUTH_SUMMARY_HIDDEN_LABELS.addressLine1)).toBeInTheDocument();
  expect(screen.getByText(AUTH_SUMMARY_HIDDEN_LABELS.addressLine2)).toBeInTheDocument();
  const panel = document.querySelector('.ant-descriptions');
  expect(panel).not.toBeNull();
  const nameless = Array.from(panel?.querySelectorAll('.ant-descriptions-item-label') ?? []).filter(
    /**
     * Keeps a label cell that carries no text.
     * @param {Element} cell - One label cell of the panel.
     * @returns {boolean} `true` when the cell is empty.
     */
    (cell: Element): boolean => (cell.textContent ?? '').trim() === '',
  );
  expect(nameless).toEqual([]);
}

/**
 * The five row controls are one selection set, each named for the action it performs.
 *
 * Assumptions: the group is asserted through the ARIA role rather than through antd's class names,
 * because the property under test is what an assistive technology is told: five independent radios expose
 * no group at all, so a `radiogroup` containing every row control is exactly what was missing.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function groupsTheRowSelectors(): Promise<void> {
  await fetchPage();

  const group = screen.getByRole('radiogroup', { name: AUTH_SUMMARY_SELECTION_PROMPT });
  const controls = within(group).getAllByRole('radio');
  expect(controls).toHaveLength(PAGE_ROWS);
  expect(
    within(group).getByRole('radio', { name: selectionActionLabel(listItem(1).transactionId) }),
  ).toBe(controls[0]);
  expect(controls[0]?.getAttribute('name')).toBe(controls[1]?.getAttribute('name'));
}

/**
 * Choosing a row and pressing the view key reaches that authorization's detail screen.
 *
 * Assumptions: this exercises the group's own change handler, which replaced five per-row handlers, and
 * it is asserted through the destination rather than through state so that the selection and the
 * navigation are shown to address the SAME record.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function navigatesToTheChosenAuthorization(): Promise<void> {
  await fetchPage();

  await userEvent.click(
    screen.getByRole('radio', { name: selectionActionLabel(listItem(2).transactionId) }),
  );
  await userEvent.keyboard('{Enter}');

  expect(await screen.findByText('AUTHORIZATION DETAIL')).toBeInTheDocument();
}

/**
 * The eight-column table pins its two identifying columns, which requires the scroll policy.
 *
 * Assumptions: the assertion is on the rendered markup rather than on the props, because antd only emits
 * its fixed-column markup when a horizontal scroll extent is also given — so the two class names below
 * prove both halves of the policy at once, and prove them as delivered rather than as configured.
 *
 * Assumptions: the class names are antd 6's LOGICAL-property spellings — `scroll-horizontal`,
 * `has-fix-start` and `cell-fix-start` — and they were read out of the rendered document rather than
 * assumed. The physical spellings a reader would expect from earlier majors (`ant-table-has-fix-left`,
 * `ant-table-cell-fix-left`) are not emitted at all, and asserting them passed for no version.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function pinsTheKeyColumns(): Promise<void> {
  await fetchPage();

  const table = document.querySelector('.ant-table');
  expect(table).not.toBeNull();
  expect(table?.className).toContain('ant-table-scroll-horizontal');
  expect(table?.className).toContain('ant-table-has-fix-start');
  expect(document.querySelectorAll('.ant-table-cell-fix-start').length).toBeGreaterThan(0);
}

/**
 * The record panel takes the shared column policy rather than restating a column count.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function takesTheSharedColumnPolicy(): void {
  const source = moduleSource();

  expect(source).toContain('column={RECORD_VIEW_COLUMNS}');
  expect(source).not.toContain('column={2}');
}

/**
 * Route changes go through the shared navigation seam, with no second copy of its fallback.
 *
 * Assumptions: the absence of the fallback CALL is what is asserted, not the absence of a name. The
 * duplicate helper's whole substance was its `window.location.assign` fallback, so a screen that still
 * held one would still hold the duplication however the function were renamed.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function usesTheSharedNavigationSeam(): void {
  const source = moduleSource();
  const assigning = source.split('\n').filter(
    /**
     * Keeps a line that performs the document-level fallback outside a comment.
     * @param {string} line - One line of the module.
     * @returns {boolean} `true` when the line calls the fallback.
     */
    (line: string): boolean =>
      line.includes('window.location.assign') && !line.trimStart().startsWith('*'),
  );

  expect(source).toContain("from '../../routes/navigation'");
  expect(assigning).toEqual([]);
}

/** Registers every case of this suite. */
function authSummaryCases(): void {
  it('refuses a short identifier locally', refusesAShortIdentifierLocally);
  it('accepts the declared width', acceptsTheDeclaredWidth);
  it('links the filter refusal to the control', linksTheFilterRefusalToTheControl);
  it('names both address lines', namesBothAddressLines);
  it('groups the row selectors', groupsTheRowSelectors);
  it('navigates to the chosen authorization', navigatesToTheChosenAuthorization);
  it('pins the key columns', pinsTheKeyColumns);
  it('takes the shared column policy', takesTheSharedColumnPolicy);
  it('uses the shared navigation seam', usesTheSharedNavigationSeam);
}

beforeEach(resetTransport);

afterEach(resetTransport);

describe('pending authorization summary screen', authSummaryCases);
