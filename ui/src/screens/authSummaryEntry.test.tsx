/**
 * @file Proves the pending-authorization summary screen refuses an account entry that is not exactly
 * eleven digits, locally, with the reference's own sentence and without reaching the service.
 *
 * Purpose
 * -------
 * `ui/src/screens/authSummary/index.tsx` replaces
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl`. Its `PROCESS-ENTER-KEY` paragraph tests the entry
 * twice, in order, at L264 to L281: blank first, raising `'Please enter Acct Id...'` at L269, then
 * `IS NOT NUMERIC`, raising `'Acct Id must be Numeric ...'` at L278. Both arms set the error flag, move
 * `LOW-VALUES` into the working identifier and place the cursor back on the field, so neither reads
 * anything.
 *
 * What these cases exist to prevent
 * ---------------------------------
 * ⚠️ Assumptions: `IS NOT NUMERIC` runs over `ACCTIDI PIC X(11)` as CICS delivers it, LEFT-JUSTIFIED and
 * SPACE-PADDED to eleven. A ten-digit entry therefore arrives as ten digits and one space and fails that
 * test, so the terminal refuses a short entry with the same sentence it gives a letter. The screen's
 * classification accepted any non-blank digit run, so a ten-digit entry was SENT -- a round trip the
 * reference never makes, answered by whatever the service says about an identifier of the wrong width in
 * place of the one verbatim sentence the operator should have read.
 *
 * Assumptions: each rendered case asserts BOTH the sentence and that the transport was not called, because
 * a screen that rendered the sentence and issued the read anyway would satisfy a one-sided assertion while
 * still making the round trip the refusal exists to avoid.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';

import { AppShell } from '../layout/AppShell';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { listPendingAuthorizations } from '../api/authorization';
import type {
  PendingAuthListItem,
  PendingAuthListResponse,
  PendingAuthSummary,
} from '../api/authorization';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { PROGRAM_MESSAGES } from '../messages/messages';
import {
  AUTH_SUMMARY_FIELD_WIDTHS,
  AUTH_SUMMARY_KEY_LABELS,
  AUTH_SUMMARY_LABELS,
  AuthSummaryScreen,
  classifyAccountIdEntry,
} from './authSummary';

/**
 * Builds the mocked surface of the authorization transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The transport function this screen calls, as a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    listPendingAuthorizations: vi.fn(),
  };
}

vi.mock('../api/authorization', mockAuthorizationTransportModule);

/** Sentences this program declares, from the single catalog that owns them. */
const SUMMARY_MESSAGES = PROGRAM_MESSAGES.COPAUS0C;

/** The declared width of the account field, which is also the exact number of digits required. */
const ACCOUNT_ID_WIDTH: number = AUTH_SUMMARY_FIELD_WIDTHS.accountId;

/** An account identifier at exactly the declared width, which must be accepted. */
const ELEVEN_DIGITS = '00000000011';

/**
 * The account summary an accepted read answers with.
 *
 * Assumptions: every member the shape declares is supplied rather than the object being widened with a
 * cast, because the panel renders from it -- a partial stand-in would let a case pass against a member the
 * real service always sends.
 */
const SUMMARY: PendingAuthSummary = {
  accountId: ELEVEN_DIGITS,
  customerId: '000000001',
  authStatus: null,
  accountStatus1: null,
  accountStatus2: null,
  accountStatus3: null,
  accountStatus4: null,
  accountStatus5: null,
  creditLimit: '5000.00',
  cashLimit: '1000.00',
  creditBalance: '250.00',
  cashBalance: '0.00',
  approvedAuthCnt: 0,
  declinedAuthCnt: 0,
  approvedAuthAmt: '0.00',
  declinedAuthAmt: '0.00',
  customerName: 'ADA LOVELACE',
  addressLine1: null,
  addressLine2: null,
  phoneNumber1: null,
};

/** An envelope carrying no rows, so an accepted entry has something to resolve with. */
const EMPTY_ENVELOPE: PendingAuthListResponse = {
  summary: SUMMARY,
  page: {
    items: [] as readonly PendingAuthListItem[],
    firstKey: null,
    lastKey: null,
    hasNext: false,
  },
  screenMessage: null,
};

/**
 * Renders the screen inside a router, which `useNavigate` requires.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(): ReactElement {
  return (
    <MemoryRouter initialEntries={['/authorizations']}>
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
        <AuthSummaryScreen />
      </AppShell>
    </MemoryRouter>
  );
}

/** Restores the spy between cases. */
function resetSpies(): void {
  vi.mocked(listPendingAuthorizations).mockReset();
}

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: needed because this mapset's labels carry padding the DOM collapses on display --
 * `'Name: '` and `'Approval # : '` both do -- so the expectation is collapsed rather than the label being
 * trimmed at its declaration, which would make the test the source of truth instead of the mapset.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Returns the legend control that invokes one function key.
 *
 * Assumptions: the control is looked up INSIDE the function-key region, because `PfKeyBar` renders a
 * `nav` with an accessible name -- role `navigation`, not `region` -- and a global label lookup would
 * break the moment a screen label collided with a key label.
 * @param {string} label - The legend label, verbatim from the mapset's row-24 legend.
 * @returns {HTMLElement} The legend control bearing that label.
 * @throws {Error} If the region holds no control with that label, so a renamed label fails loudly rather
 *   than silently exercising nothing.
 */
function legendControl(label: string): HTMLElement {
  const region = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const found = Array.from(region.querySelectorAll('button')).find(
    /**
     * Reports whether one control's text is the label sought, with interior runs collapsed.
     * @param {HTMLButtonElement} control - Candidate control.
     * @returns {boolean} Whether its text matches.
     */
    (control: HTMLButtonElement): boolean => collapse(control.textContent ?? '') === label,
  );
  if (found === undefined) {
    throw new Error(`no function-key control labelled ${label}`);
  }
  return found;
}

/**
 * Submits one account entry and returns once the turn has been processed.
 * @param {string} entry - The account identifier to place in the control.
 * @returns {Promise<void>} Resolves once the submit has been dispatched.
 */
async function submitEntry(entry: string): Promise<void> {
  const operator = userEvent.setup({ delay: null });
  fireEvent.change(screen.getByLabelText(collapse(AUTH_SUMMARY_LABELS.searchAccountId)), {
    target: { value: entry },
  });
  await operator.click(legendControl(AUTH_SUMMARY_KEY_LABELS.ENTER));
}

/**
 * Asserts one entry is refused locally with the non-numeric sentence and reaches no read.
 * @param {string} entry - The account identifier to submit.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesLocally(entry: string): Promise<void> {
  vi.mocked(listPendingAuthorizations).mockResolvedValue(EMPTY_ENVELOPE);
  render(renderScreen());
  await submitEntry(entry);

  expect(await screen.findByText(SUMMARY_MESSAGES.ACCT_ID_MUST_BE_NUMERIC)).toBeInTheDocument();
  expect(listPendingAuthorizations).not.toHaveBeenCalled();
}

/**
 * Proves a ten-digit entry is refused locally, which is the case the finding names.
 *
 * ⚠️ Assumptions: ten digits is one character short of the declared width, and it is the entry the
 * previous classification accepted -- every character was a digit, so the digits-only test passed and the
 * read went out. On the terminal the same entry arrives space-padded to eleven and fails `IS NOT NUMERIC`.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesATenDigitEntry(): Promise<void> {
  await refusesLocally('1234567890');
}

/**
 * Proves a single-digit entry is refused locally too, so the rule is a width and not an off-by-one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesASingleDigitEntry(): Promise<void> {
  await refusesLocally('7');
}

/**
 * Proves an entry carrying a letter is still refused with the same sentence.
 *
 * Assumptions: this is the arm that already worked, and it is asserted beside the two new ones because
 * they share one sentence and one code path -- a change that refused short entries by a separate route
 * could leave this one behind, and nothing else here would say so.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesANonNumericEntry(): Promise<void> {
  await refusesLocally('0000000001A');
}

/**
 * Proves a blank entry keeps its own, different sentence.
 *
 * Assumptions: the two sentences are never merged. L264 tests blank BEFORE L275 tests numeric, so a blank
 * entry is reported as blank and never as non-numeric, and the width rule must not have collapsed the two.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesABlankEntryWithItsOwnSentence(): Promise<void> {
  vi.mocked(listPendingAuthorizations).mockResolvedValue(EMPTY_ENVELOPE);
  render(renderScreen());
  await submitEntry('   ');

  expect(await screen.findByText(SUMMARY_MESSAGES.PLEASE_ENTER_ACCT_ID)).toBeInTheDocument();
  expect(screen.queryByText(SUMMARY_MESSAGES.ACCT_ID_MUST_BE_NUMERIC)).not.toBeInTheDocument();
  expect(listPendingAuthorizations).not.toHaveBeenCalled();
}

/**
 * Proves an entry at exactly the declared width IS read, so the rule has not refused everything.
 *
 * ⚠️ Assumptions: this is the control for the four refusals above. A classification tightened by refusing
 * every entry would satisfy all four and would break the screen outright, and nothing in those four would
 * reveal it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function readsAnEntryAtTheDeclaredWidth(): Promise<void> {
  vi.mocked(listPendingAuthorizations).mockResolvedValue(EMPTY_ENVELOPE);
  render(renderScreen());
  await submitEntry(ELEVEN_DIGITS);

  await waitFor(
    /**
     * Waits for the accepted entry to have issued a read.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(listPendingAuthorizations).toHaveBeenCalled();
    },
  );
  expect(screen.queryByText(SUMMARY_MESSAGES.ACCT_ID_MUST_BE_NUMERIC)).not.toBeInTheDocument();
}

/**
 * Asserts the classification itself, across the width boundary in both directions.
 *
 * Assumptions: the boundary is exercised at the declared width and at one character either side of it,
 * which is what makes the rule a width rather than a lower bound that happens to pass today. The width is
 * read from the published constant rather than written as `11`, so the mapset's own `LENGTH=11` remains
 * the single authority.
 * @returns {void} Nothing; the case asserts.
 */
function classifiesTheWidthBoundary(): void {
  expect(classifyAccountIdEntry('9'.repeat(ACCOUNT_ID_WIDTH))).toBeNull();
  expect(classifyAccountIdEntry('9'.repeat(ACCOUNT_ID_WIDTH - 1))).toBe('NOT_OK');
  expect(classifyAccountIdEntry('9'.repeat(ACCOUNT_ID_WIDTH + 1))).toBe('NOT_OK');
  expect(classifyAccountIdEntry('')).toBe('BLANK');
  expect(classifyAccountIdEntry(' '.repeat(ACCOUNT_ID_WIDTH))).toBe('BLANK');
  expect(classifyAccountIdEntry(`${'9'.repeat(ACCOUNT_ID_WIDTH - 1)}A`)).toBe('NOT_OK');
}

/** Registers the rendered entry-refusal cases. */
function renderedEntryCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('refuses a ten-digit entry locally', refusesATenDigitEntry);
  it('refuses a single-digit entry locally', refusesASingleDigitEntry);
  it('refuses an entry carrying a letter', refusesANonNumericEntry);
  it('refuses a blank entry with its own sentence', refusesABlankEntryWithItsOwnSentence);
  it('reads an entry at the declared width', readsAnEntryAtTheDeclaredWidth);
}

/** Registers the pure classification case. */
function pureEntryCases(): void {
  it('classifies the width boundary in both directions', classifiesTheWidthBoundary);
}

describe(
  'the authorization summary refuses an account entry of the wrong width',
  renderedEntryCases,
);

describe('the account-entry classification', pureEntryCases);
