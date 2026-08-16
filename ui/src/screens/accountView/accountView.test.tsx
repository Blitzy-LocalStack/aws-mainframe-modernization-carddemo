/**
 * @file Component tests for the account view screen in `ui/src/screens/accountView/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the properties a review found this screen had lost or invented: which sentence the
 * information line carries in each state, that no sentence is emitted on exit, that no account
 * identifier is read from the browser URL, that a refused filter is programmatically associated with
 * its refusal, that the blank marker is a decoration and never the field's value, and that the two
 * address lines are rendered under the one label the mapset paints.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, matching
 * `ui/src/screens/cardScreens.test.tsx`. These cases are about what the SCREEN does with an outcome, so
 * the shortest honest seam is the function the screen calls; going through the axios client would
 * additionally exercise the interceptor chain and make a presentation regression report itself as a
 * transport failure. The one case that IS about the client -- the redaction guard -- is asserted where
 * that guard lives, in `ui/src/api/accounts.test.ts`.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for
 * the reason `cardScreens.test.tsx` records: `ui/eslint.config.js` selects a function expression in
 * every position so an inline callback owes its own JSDoc block, and Prettier moves a block comment
 * that follows an argument comma onto the preceding literal, detaching it from what it documents.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { readAccountView } from '../../api/accounts';
import type { AccountViewResponse } from '../../api/accounts';
import { ApiRequestError } from '../../api/client';
import type { ApiError, FieldError } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { fieldErrorId } from '../../layout/fieldHelp';
import { RECORD_VIEW_BREAKPOINT, RECORD_VIEW_COLUMNS } from '../../layout/recordLayout';
/*
 * WHY : Refactoring Rationale: the customer-block labels are imported from the message catalog and were
 *       imported from the screen module as `CUSTOMER_BLOCK_FIELD_LABELS`. The screen no longer declares
 *       them: every user-visible string in this tree is catalogued in one place, and these 29 mapset
 *       labels were the last group declared beside the component that renders them. The names change
 *       case with the move -- the catalog spells its members `ADDRESS_LINE_1` rather than
 *       `addressLine1` -- so the reads below are renamed rather than merely re-pointed.
 */
import {
  ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS,
  ACCOUNT_VIEW_HEADINGS,
  STATUS_MESSAGES,
} from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';
import { BREAKPOINT_TOKENS, FIELD_ERROR_TOKENS } from '../../theme/tokens';
import { AccountViewScreen } from './index';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone
 * at registration time.
 * @returns {Record<string, unknown>} The transport functions this screen can reach, each a fresh spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    readAccountView: vi.fn(),
    updateAccount: vi.fn(),
    listAccountCardCrossReferences: vi.fn(),
  };
}

vi.mock('../../api/accounts', mockAccountTransportModule);

const MESSAGES = STATUS_MESSAGES.COACTVWC;

/** Identifier of the account filter control, as the screen declares it. */
const ACCOUNT_ID_CONTROL_ID = 'account-view-account-id';

/** Viewport width below the design system's medium breakpoint, used by the responsive case. */
const NARROW_VIEWPORT_WIDTH = 375;

/** The viewport width jsdom reports by default, restored after the responsive case. */
const DEFAULT_VIEWPORT_WIDTH = window.innerWidth;

/** An account identifier of exactly the declared width, so a read is attempted. */
const VALID_ACCOUNT_ID = '00000000011';

/**
 * Builds a composed account view whose values are recognisable in an assertion.
 *
 * Assumptions: both protected identifiers carry the fixed redaction marker the service publishes, so a
 * fixture cannot accidentally assert that an unredacted identifier renders.
 * @param {string | null} informationMessage - What the response carries on its information channel.
 * @param {string | null} addressLine2 - The optional second address line, or `null` when absent.
 * @returns {AccountViewResponse} The response for the screen to render.
 */
function accountView(
  informationMessage: string | null,
  addressLine2: string | null,
): AccountViewResponse {
  return {
    accountId: VALID_ACCOUNT_ID,
    account: {
      activeStatus: 'Y',
      openDate: '2020-01-01',
      creditLimit: '5000.00',
      expirationDate: '2026-01-01',
      cashCreditLimit: '1000.00',
      reissueDate: '2024-01-01',
      currentBalance: '250.00',
      currentCycleCredit: '0.00',
      groupId: 'ZEROBAL',
      currentCycleDebit: '0.00',
    },
    customer: {
      customerId: '000000011',
      ssnMasked: '[REDACTED]',
      dateOfBirth: '1980-01-01',
      ficoCreditScore: '750',
      firstName: 'ADA',
      middleName: null,
      lastName: 'LOVELACE',
      addressLine1: '1 ANALYTICAL WAY',
      stateCode: 'NY',
      addressLine2,
      zipCode: '10001',
      city: 'NEW YORK',
      countryCode: 'USA',
      phoneNumber1: '(212)5550101',
      governmentIssuedIdMasked: '[REDACTED]',
      phoneNumber2: null,
      eftAccountId: '00000000000',
      primaryCardHolderIndicator: 'Y',
    },
    informationMessage,
    returnMessage: null,
  };
}

/**
 * Builds the partial read the reference paints: an account located, its customer absent.
 *
 * Assumptions: the miss sentence travels on `returnMessage` and not on the information line, because
 * `AccountViewResponse` in `ui/src/api/types.ts` declares it there -- `WS-RETURN-MSG` at
 * `app/cbl/COACTVWC.cbl` L117 is the seventy-five-character carrier, while the information line holds
 * the forty-character prompt that channel's floor forces back.
 *
 * Assumptions: `revision` is null on this arm. The concurrency token belongs to the account-and-customer
 * pair the update screen rewrites, and there is no customer row to hold one.
 * @returns {AccountViewResponse} The response for the screen to render.
 */
function partialAccountView(): AccountViewResponse {
  return {
    ...accountView(null, 'APT 4B'),
    customer: null,
    returnMessage: MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  };
}

/**
 * Builds the normalised failure the shared client raises for a refused read.
 * @param {number} status - Transport status the refusal carries.
 * @param {string} message - Screen-level sentence the band is expected to render.
 * @param {readonly FieldError[]} fieldErrors - Per-field entries the document names.
 * @returns {ApiRequestError} The rejection to configure the stub with.
 */
function refusal(
  status: number,
  message: string,
  fieldErrors: readonly FieldError[],
): ApiRequestError {
  const problem: ApiError = {
    code: 'CARDDEMO-ACCT-0001',
    secondaryCode: '',
    message,
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'UITESTACCT000000000AA',
    path: '/api/v1/accounts/view',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors,
    abend: null,
  };

  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`);
}

/**
 * Renders the screen inside the theme, the one shell and a router reporting the entered route.
 * @param {string} entry - The initial history entry, so a case can supply a query string.
 * @returns {ReactElement} The composed tree under test.
 */
function renderAccountView(entry: string): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={[entry]}>
        <AppShell>
          <Routes>
            <Route path="/account/view" element={<AccountViewScreen />} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Returns the stub standing in for the composed read.
 *
 * Assumptions: the return type names the mocked function precisely rather than the generic mock shape,
 * so a queued outcome is checked against the transport's real signature. Written the loose way the
 * queue accepts any object and a fixture that has drifted from `AccountViewResponse` reaches the screen
 * as a type error the suite cannot see, surfacing instead as a puzzling render assertion.
 * @returns {MockedFunction<typeof readAccountView>} The mocked transport function.
 */
function readStub(): MockedFunction<typeof readAccountView> {
  return vi.mocked(readAccountView);
}

/** Clears the transport stub so no case inherits another's queued outcome. */
function resetTransport(): void {
  vi.mocked(readAccountView).mockReset();
}

/**
 * Types an account identifier into the filter and submits it.
 * @param {string} value - The identifier to type.
 * @returns {Promise<void>} Resolves once the submission has been dispatched.
 */
async function readAccount(value: string): Promise<void> {
  await userEvent.type(screen.getByLabelText(/account number/iu), value);
  await userEvent.keyboard('{Enter}');
}

/**
 * Clears the filter and submits a different identifier, so a case can drive a second read.
 *
 * Assumptions: the field is CLEARED first, because the shared helper types into it rather than
 * replacing its contents -- a second call without the clear would submit the two identifiers
 * concatenated, which the width bound refuses before any read starts.
 * @param {string} value - The identifier the second read is for.
 * @returns {Promise<void>} Resolves once the second submission has been dispatched.
 */
async function readAnotherAccount(value: string): Promise<void> {
  await userEvent.clear(screen.getByLabelText(/account number/iu));
  await readAccount(value);
}

/**
 * A successful read leaves the information line on the reference's prompt.
 *
 * Assumptions: the response carries no information line, which is the only way this screen's fallback
 * is reachable — the service sends the prompt on every response. The fallback used to be
 * `Displaying details of given Account`, a sentence declared at `app/cbl/COACTVWC.cbl` L115 with L116
 * as the `88`-level value `WS-INFORM-OUTPUT` and `SET` nowhere in the program, so the screen showed a
 * sentence the reference never shows. The prompt is what the reference's information line holds in
 * every state, because L528 with L529 restores it whenever that field is empty.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsThePromptOnASuccessfulRead(): Promise<void> {
  readStub().mockResolvedValue({ account: accountView(null, 'APT 4B'), revision: 'W/"1"' });
  render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);

  expect(await screen.findByText('LOVELACE')).toBeInTheDocument();
  expect(screen.getByText(MESSAGES.WS_PROMPT_FOR_INPUT.text)).toBeInTheDocument();
  expect(screen.queryByText(MESSAGES.WS_INFORM_OUTPUT.text)).not.toBeInTheDocument();
}

/**
 * The exit key leaves for the menu and emits no sentence.
 *
 * Assumptions: `WS-EXIT-MESSAGE` is declared at `app/cbl/COACTVWC.cbl` L119 with L120 and `SET` nowhere
 * in the program, and its PF3 arm at L323-L345 moves navigation fields and transfers control without
 * writing a message — so showing `PF03 pressed.Exiting` was an invention rather than a lost sentence.
 * The case asserts the ABSENCE at the destination, which is where a carried message would have to
 * surface if one were carried.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function emitsNoSentenceOnExit(): Promise<void> {
  render(renderAccountView('/account/view'));

  await userEvent.click(screen.getByRole('button', { name: /F3=Exit/u }));

  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();
  expect(screen.queryByText(MESSAGES.WS_EXIT_MESSAGE.text.trim())).not.toBeInTheDocument();
}

/**
 * The exit sentence is not written at all, not merely never seen.
 *
 * Refactoring Rationale: this case exists because the runtime one above CANNOT discriminate the fix, and
 * that was established by measurement rather than assumed: with the removed write restored, the case
 * above still passed. It has to, and for the same reason the review gave for calling the write dead --
 * React batches the state write with the route transition and the screen unmounts before any paint, so
 * an unobservable write and no write at all are indistinguishable from the DOM. Asserting only through
 * the DOM would therefore have left the defect free to come back with a green suite.
 *
 * Assumptions: the assertion is made against the module's SOURCE, which is the same technique
 * `ui/src/layout/screenHeaderClock.test.tsx` uses for the delegation contract, and it is narrow: the
 * catalog entry must not be referenced by this screen at all. The entry itself stays in the catalog,
 * because transformation rule T8 keeps the transcription complete whether or not a sentence is
 * reachable -- what must not exist is a screen that emits it.
 *
 * Refactoring Rationale: the sibling module is located with `join(import.meta.dirname, ...)`, matching
 * `screenHeaderClock.test.tsx` L67 and `appShellIntegration.test.tsx` L305, and NOT with
 * `new URL('./index.tsx', import.meta.url)`. The second spelling is the natural one and it does not
 * work here: Vite recognises that exact syntax as its asset-reference pattern and rewrites it at
 * transform time, so the expression yields `http://localhost:3000/src/screens/accountView/index.tsx`
 * and `readFileSync` rejects it for not being a `file:` URL -- even though `import.meta.url` on its own
 * IS one. Measured, not assumed: this case failed that way before the spelling changed.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function referencesNoExitSentence(): void {
  const source = readFileSync(join(import.meta.dirname, 'index.tsx'), 'utf8');
  const referencing = source.split('\n').filter(
    /**
     * Keeps a line that reads the catalog entry rather than one that explains its absence.
     * @param {string} line - One line of the module.
     * @returns {boolean} `true` when the line references the entry outside a comment.
     */
    (line: string): boolean =>
      line.includes('WS_EXIT_MESSAGE') && !line.trimStart().startsWith('*'),
  );

  expect(referencing).toEqual([]);
}

/**
 * An account identifier in the query string is ignored and no read is issued.
 *
 * Assumptions: the assertion is on BOTH halves — the control is empty and the transport was never
 * called — because a screen that read the query member and then cleared the control would satisfy the
 * first alone while still having put the identifier through session history, the referrer header and
 * every edge log on the way.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function ignoresAnAccountIdentifierInTheQueryString(): Promise<void> {
  render(renderAccountView(`/account/view?accountId=${VALID_ACCOUNT_ID}`));

  const filter = await screen.findByLabelText(/account number/iu);
  expect(filter).toHaveValue('');
  expect(readStub()).not.toHaveBeenCalled();
}

/**
 * A refusal the service addressed to the filter is linked to the control it names.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function linksAServiceRefusalToTheFilter(): Promise<void> {
  const reason = 'Account Filter must  be a non-zero 11 digit number';
  readStub().mockRejectedValue(
    refusal(400, reason, [{ field: 'accountId', state: 'NOT_OK', message: reason }]),
  );
  render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);

  const filter = await screen.findByLabelText(/account number/iu);
  await waitFor(
    /**
     * Re-reads the control until the refusal has been applied to it.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(filter).toHaveAttribute('aria-invalid', 'true');
    },
  );
  expect(filter).toHaveAttribute('aria-describedby', fieldErrorId(ACCOUNT_ID_CONTROL_ID));

  /*
   * Assumptions: the described element is resolved BY IDENTIFIER and its text compared exactly, rather
   * than searched for by text. Two properties come out of that which a text search cannot give: the
   * reference actually resolves -- an `aria-describedby` naming an absent element is the failure mode
   * this wiring exists to avoid -- and the sentence is compared UNNORMALIZED, so the two spaces the
   * reference literal carries between `must` and `be` are asserted rather than collapsed away. The band
   * renders the same sentence, so a text query would additionally have to disambiguate between them.
   */
  const help = document.getElementById(fieldErrorId(ACCOUNT_ID_CONTROL_ID));
  expect(help).not.toBeNull();
  expect(help?.textContent).toBe(reason);
}

/**
 * A locally refused filter is marked invalid and describes nothing that is not rendered.
 *
 * Assumptions: this screen deliberately prints no help text for a LOCAL refusal, because the message
 * band already carries the sentence and printing it twice would have one refusal read in two places.
 * The property under test is therefore that the two ARIA members come apart correctly: invalid without
 * a dangling description.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksALocalRefusalWithoutDanglingDescription(): Promise<void> {
  render(renderAccountView('/account/view'));

  await userEvent.keyboard('{Enter}');

  expect(await screen.findByText(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text)).toBeInTheDocument();
  const filter = screen.getByLabelText(/account number/iu);
  expect(filter).toHaveAttribute('aria-invalid', 'true');
  expect(filter).not.toHaveAttribute('aria-describedby');
  expect(readStub()).not.toHaveBeenCalled();
}

/**
 * The blank marker is a hidden decoration and never becomes the filter's value.
 *
 * Assumptions: three properties are asserted together because they are the three ways the previous
 * behaviour was wrong. The control's value stays empty, so the marker is not the field's data; the
 * rendered marker is `aria-hidden`, so it is not announced as the account number; and it is present at
 * all, so the reference's own visible treatment at `app/cbl/COACTVWC.cbl` L561-L565 is not simply
 * dropped.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersTheBlankMarkerAsAHiddenDecoration(): Promise<void> {
  render(renderAccountView('/account/view'));

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Re-reads the marker until the refusal has been applied.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByText(FIELD_ERROR_TOKENS.blankMarker)).toBeInTheDocument();
    },
  );
  expect(screen.getByText(FIELD_ERROR_TOKENS.blankMarker)).toHaveAttribute('aria-hidden', 'true');
  expect(screen.getByLabelText(/account number/iu)).toHaveValue('');
}

/**
 * Both address lines are rendered under the one label the mapset paints.
 *
 * Assumptions: the second line is asserted to share the labelled item rather than merely to appear
 * somewhere, by reading the row the label heads — an unlabelled row elsewhere in the table would
 * satisfy a bare text query while reproducing exactly the defect this closes.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersBothAddressLinesUnderOneLabel(): Promise<void> {
  readStub().mockResolvedValue({ account: accountView(null, 'APT 4B'), revision: 'W/"1"' });
  render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);

  const label = await screen.findByText(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.ADDRESS_LINE_1);
  const row = label.closest('tr');
  expect(row).not.toBeNull();
  expect(row?.textContent).toContain('1 ANALYTICAL WAY');
  expect(row?.textContent).toContain('APT 4B');
}

/**
 * An absent second address line contributes no line at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function omitsAnAbsentSecondAddressLine(): Promise<void> {
  readStub().mockResolvedValue({ account: accountView(null, null), revision: 'W/"1"' });
  render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);

  const label = await screen.findByText(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.ADDRESS_LINE_1);
  const row = label.closest('tr');
  expect(row?.textContent).toContain('1 ANALYTICAL WAY');
  // Assumptions: the row is asserted to hold the first line and NOT the word `null`, which is what a
  //   nullable member reaches the DOM as when it is rendered without being checked.
  expect(row?.textContent).not.toContain('null');

  // WHY : Refactoring Rationale: the assertion above discriminates only ONE of the two ways an absent
  //       line can be mishandled, and that was measured: reverting the fix to a second row carrying an
  //       empty label left it green, because an empty label and an empty value put no text in the DOM
  //       to catch. The pre-fix shape is what the review named -- an unlabelled cell -- so it is
  //       asserted directly and over BOTH tables, since a label cell with no text is a defect wherever
  //       it appears rather than only in the address item.
  const labelTexts = Array.from(document.querySelectorAll('th')).map(
    /**
     * Reads one label cell's text.
     * @param {HTMLTableCellElement} cell - One rendered label cell.
     * @returns {string} The cell's text with surrounding blanks removed.
     */
    (cell: HTMLTableCellElement): string => (cell.textContent ?? '').trim(),
  );
  expect(labelTexts).not.toHaveLength(0);
  expect(labelTexts.filter(isBlank)).toEqual([]);
}

/**
 * A partial read fills the account block, keeps every customer label, and carries no customer value.
 *
 * ⚠️ Refactoring Rationale: this case is RESTORED. The remedy it guards is in place -- the screen
 * renders `customerBlockRows(view.customer ?? UNPOPULATED_CUSTOMER)` unconditionally -- but no case
 * arranged a customer-less read, so nothing held the screen to it and reinstating the suppression form
 * would have been silent.
 *
 * Assumptions: the heading and the labels are asserted PRESENT and only the values absent, which is what
 * the mapset does. `app/bms/COACTVW.bms` declares 100 `DFHMDF` fields of which 63 carry no name and only
 * an `INITIAL=` literal -- 35 of them at row 11 or below, the `Customer Details` heading at `POS=(11,32)`
 * among them -- and a literal field is part of the map, transmitted on every send.
 * `app/cbl/COACTVWC.cbl` L493 guards the eighteen NAMED value fields and nothing else, so the terminal
 * shows every label with its value blank.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersBlankCustomerValuesOnAPartialRead(): Promise<void> {
  readStub().mockResolvedValue({ account: partialAccountView(), revision: null });
  render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);

  // Assumptions: the account half is asserted through its OPEN DATE rather than through an amount.
  //   Every amount on this screen is rendered through the reference's `+ZZZ,ZZZ,ZZZ.99` edit mask, whose
  //   interior blanks the query normalizer collapses, so matching one needs a custom normalizer that
  //   would be about text matching rather than about this case. A date renders as stored.
  expect(await screen.findByText('2020-01-01')).toBeInTheDocument();
  expect(screen.getByText(ACCOUNT_VIEW_HEADINGS.CUSTOMER)).toBeInTheDocument();
  expect(
    screen.getByText(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.FICO_CREDIT_SCORE),
  ).toBeInTheDocument();
  expect(screen.queryByText('750')).not.toBeInTheDocument();
  expect(screen.queryByText('LOVELACE')).not.toBeInTheDocument();
  expect(screen.getByText(MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT.text)).toBeInTheDocument();
}

/**
 * The customer region keeps its shape across both arms, so the screen does not reflow.
 *
 * ⚠️ Refactoring Rationale: restored alongside the case above, and it is the half that discriminates
 * the fix. Rendering the heading with no table would satisfy a heading query while leaving the region
 * empty, so the LABEL POPULATION is compared between the two arms.
 *
 * Assumptions: the count is compared rather than asserted as a figure, so the case states the INVARIANT
 * -- one region, one label set -- instead of restating eighteen. The mapset transmits those labels
 * unconditionally, so a difference here means the browser screen changes height on an arm where the
 * terminal's does not.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function keepsTheCustomerRegionShapeOnBothArms(): Promise<void> {
  readStub().mockResolvedValueOnce({ account: accountView(null, 'APT 4B'), revision: 'W/"1"' });
  render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);
  await screen.findByText('LOVELACE');
  const complete = document.querySelectorAll('th').length;
  expect(complete).toBeGreaterThan(0);

  readStub().mockResolvedValueOnce({ account: partialAccountView(), revision: null });
  await readAnotherAccount('00000000012');
  await screen.findByText(MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT.text);

  expect(document.querySelectorAll('th')).toHaveLength(complete);
}

/**
 * Re-submitting on an unchanged filter retires the earlier read, so the earlier answer cannot land last.
 *
 * ⚠️ Refactoring Rationale: restored. This is the ordering the retirement at the START of a read is the
 * only guard for, and the sibling race case does not reach it -- there the filter is edited between the
 * two submissions, so the keystroke retires the first read. Here the operator presses Enter twice on an
 * unchanged filter, which is exactly what a slow response invites, and no keystroke intervenes. Without
 * that call both submissions would carry the same sequence token, so the earlier answer would be
 * accepted when it finally arrived and would replace the later one.
 *
 * Assumptions: the two answers differ in a way the DOM shows -- the first carries a customer and the
 * second does not -- so "the earlier answer landed last" is observable rather than inferred from a
 * call count.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aResubmissionRetiresTheEarlierRead(): Promise<void> {
  let releaseFirst: (value: { account: AccountViewResponse; revision: string | null }) => void =
    /**
     * Stands in until the deferred read installs the real resolver.
     * @returns {void} Nothing; replaced before it is called.
     */
    () => {};

  readStub().mockImplementationOnce(
    /**
     * Answers the first submission only when the case releases it.
     * @returns {Promise<{ account: AccountViewResponse; revision: string | null }>} The held answer.
     */
    async () =>
      new Promise(
        /**
         * Retains the resolver so the case decides when the first submission answers.
         * @param {(value: { account: AccountViewResponse; revision: string | null }) => void} resolve -
         *   The promise's own resolver.
         * @returns {void} Nothing; the resolver is retained.
         */
        (resolve) => {
          releaseFirst = resolve;
        },
      ),
  );
  readStub().mockResolvedValueOnce({ account: partialAccountView(), revision: null });

  render(renderAccountView('/account/view'));
  await readAccount(VALID_ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');
  expect(await screen.findByText(MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT.text)).toBeInTheDocument();

  releaseFirst({ account: accountView(null, 'APT 4B'), revision: 'W/"1"' });

  await waitFor(bothReadsHaveBeenDispatched);
  expect(screen.queryByText('LOVELACE')).not.toBeInTheDocument();
  expect(screen.getByText(MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT.text)).toBeInTheDocument();
}

/**
 * Asserts both reads have reached the transport, so the released answer has had its chance to apply.
 * @returns {void} Nothing; throws until the second read appears.
 */
function bothReadsHaveBeenDispatched(): void {
  expect(readStub()).toHaveBeenCalledTimes(2);
}

/**
 * The record view is one column below the medium breakpoint and two from it upward.
 *
 * Assumptions: the policy is asserted as a VALUE rather than by measuring a rendered table, because
 * antd resolves the responsive object itself and a DOM measurement would be asserting antd's behaviour
 * rather than this application's decision. The correspondence between the screen name the policy keys
 * on and the design token that holds its threshold is asserted alongside it, derived from the token
 * rather than restated, so a rename on either side fails here.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function statesOneColumnNarrowAndTwoWide(): void {
  expect(RECORD_VIEW_COLUMNS.xs).toBe(1);
  expect(RECORD_VIEW_COLUMNS.sm).toBe(1);
  expect(RECORD_VIEW_COLUMNS.md).toBe(2);
  expect(RECORD_VIEW_BREAKPOINT).toBe(BREAKPOINT_TOKENS.medium);
}

/**
 * Reports whether a label cell carries no text.
 * @param {string} text - One label cell's trimmed text.
 * @returns {boolean} `true` when the cell is empty.
 */
function isBlank(text: string): boolean {
  return text === '';
}

/**
 * Counts the label cells in the first row of every rendered record table.
 * @returns {readonly number[]} One count per table, in document order.
 */
function labelCellsPerFirstRow(): readonly number[] {
  return Array.from(document.querySelectorAll('table')).map(
    /**
     * Counts the label cells of one table's first body row.
     * @param {HTMLTableElement} table - One rendered record table.
     * @returns {number} How many label cells its first row holds.
     */
    (table: HTMLTableElement): number =>
      table.querySelector('tbody tr')?.querySelectorAll('th').length ?? 0,
  );
}

/**
 * The rendered record view is two-up at a wide viewport and one-up at a narrow one.
 *
 * Refactoring Rationale: this case measures the RENDERED grid, because the value assertion above cannot
 * discriminate the fix and that was established by measurement: with both blocks reverted to a fixed
 * two columns, the value case still passed -- it asserts what the policy says, not that the screen
 * consults it. Counting the label cells of a rendered row asserts the outcome an operator gets.
 *
 * Assumptions: the width is assigned before the render at each step, because antd's responsive observer
 * reads `matchMedia` when it subscribes in a layout effect and the suite's shim derives every query from
 * `window.innerWidth`.
 * @returns {Promise<void>} Resolves once both widths have been measured.
 */
async function rendersOneColumnNarrowAndTwoWide(): Promise<void> {
  readStub().mockResolvedValue({ account: accountView(null, 'APT 4B'), revision: 'W/"1"' });
  const wide = render(renderAccountView('/account/view'));

  await readAccount(VALID_ACCOUNT_ID);
  await screen.findByText('LOVELACE');
  expect(labelCellsPerFirstRow()).toEqual([2, 2]);
  wide.unmount();

  window.innerWidth = NARROW_VIEWPORT_WIDTH;
  render(renderAccountView('/account/view'));
  await readAccount(VALID_ACCOUNT_ID);
  await screen.findByText('LOVELACE');
  expect(labelCellsPerFirstRow()).toEqual([1, 1]);
}

/**
 * Registers the account-view cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function accountViewCases(): void {
  it('keeps the reference prompt on a successful read', keepsThePromptOnASuccessfulRead);
  it('emits no sentence when the exit key is pressed', emitsNoSentenceOnExit);
  it(
    'ignores an account identifier supplied in the query string',
    ignoresAnAccountIdentifierInTheQueryString,
  );
  it('links a service refusal to the filter control', linksAServiceRefusalToTheFilter);
  it(
    'marks a local refusal invalid without describing absent text',
    marksALocalRefusalWithoutDanglingDescription,
  );
  it('renders the blank marker as a hidden decoration', rendersTheBlankMarkerAsAHiddenDecoration);
  it('renders both address lines under one label', rendersBothAddressLinesUnderOneLabel);
  it('omits an absent second address line', omitsAnAbsentSecondAddressLine);
  it('renders blank customer values on a partial read', rendersBlankCustomerValuesOnAPartialRead);
  it('keeps the customer region shape on both arms', keepsTheCustomerRegionShapeOnBothArms);
  it('retires the earlier read when the filter is resubmitted', aResubmissionRetiresTheEarlierRead);
  it('states one column narrow and two wide', statesOneColumnNarrowAndTwoWide);
  it('references no exit sentence at all', referencesNoExitSentence);
  it('renders one column narrow and two wide', rendersOneColumnNarrowAndTwoWide);
}

/**
 * Restores the viewport width the responsive case changed, so no later case inherits it.
 * @returns {void} Nothing; the width is restored in place.
 */
function restoreViewportWidth(): void {
  window.innerWidth = DEFAULT_VIEWPORT_WIDTH;
}

beforeEach(resetTransport);

afterEach(resetTransport);

afterEach(restoreViewportWidth);

describe('account view screen', accountViewCases);
