/**
 * @file Proves the pending-authorization detail screen transcribes `COPAUS1C`: the values it renders,
 * the fraud transition its fifth key submits, what its eighth key does at the end of an account's
 * authorizations, and the dead end a route naming nothing produces.
 *
 * Purpose
 * -------
 * `ui/src/screens/authDetail/index.tsx` replaces
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl`, which is the screen the summary grid selects into
 * and which nothing was mounted at -- so selecting a row resolved to the router's not-found result. The
 * reference cannot run without a CICS runtime, so these cases are the only verification it receives.
 *
 * Assumptions: every expectation is the CONSTANT the screen or the catalog publishes rather than a
 * retyped sentence, for the reason the card screen tests record. The transport module is mocked so each
 * case controls what the service answers, and the screen's request shapes are asserted against the spies
 * -- which is the half that matters for the fraud transition, because the ACTION sent is derived from
 * the mark already on the row.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the card screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getNextPendingAuthorization,
  getPendingAuthorizationScreen,
  setAuthorizationFraudState,
} from '../api/authorization';
import { ApiRequestError } from '../api/client';
import type { ApiError, PendingAuthDetailScreen } from '../api/types';
import { PROGRAM_MESSAGES, UNEXPECTED_ABEND_OCCURRED } from '../messages/messages';
import { DFH_RUNTIME_COLOR_TOKENS } from '../theme/tokens';
import {
  AUTHORIZATION_DETAIL_ROUTE,
  AUTHORIZATION_SUMMARY_ROUTE,
  AUTH_DETAIL_FIELD_LABELS,
  AUTH_DETAIL_KEY_LABELS,
  AUTH_DETAIL_MERCHANT_HEADING,
  AUTH_DETAIL_SUBTITLE,
  AuthDetailScreen,
  FRAUD_REPORTED,
  FRAUD_WITHDRAWN,
  approvalToneToken,
  authorizationRows,
  detailFailureMessage,
  fraudOutcomeMessage,
  merchantRows,
  nextFraudAction,
} from './authDetail';
import type { AuthDetailRow } from './authDetail';

/**
 * Builds the mocked surface of the authorization transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`, because Vitest
 * lifts every `vi.mock` call above the imports.
 * @returns {Record<string, unknown>} The three transport functions this screen calls, each a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    getPendingAuthorizationScreen: vi.fn(),
    getNextPendingAuthorization: vi.fn(),
    setAuthorizationFraudState: vi.fn(),
  };
}

vi.mock('../api/authorization', mockAuthorizationTransportModule);

/** Sentences this program emits, from the single catalog that owns them. */
const DETAIL_MESSAGES = PROGRAM_MESSAGES.COPAUS1C;

/** Sentences the fraud-marking program emits, which the service answers a transition with. */
const FRAUD_MESSAGES = PROGRAM_MESSAGES.COPAUS2C;

/** Text a probe route renders so an exit can be observed. */
const ARRIVED = 'ARRIVED';

/**
 * Synthetic sealed selector with no meaning of its own.
 *
 * Assumptions: an authorization is addressed by an opaque selector rather than by its composite key,
 * because `ui/src/api/authorization.ts` records why an account identifier, a date and a time may not
 * travel in a path -- so a concrete route needs one of these and not a readable key.
 */
const SELECTOR = 'fake-selector-example-not-a-real-sealed-value-000000';

/** One rendered authorization, masked as every non-administrative read returns it. */
const DETAIL: PendingAuthDetailScreen = {
  transactionName: 'CPVD',
  title01: 'AWS Mainframe Modernization',
  currentDate: '01/02/25',
  programName: 'COPAUS1C',
  title02: 'CardDemo',
  currentTime: '10:11:12',
  cardNumber: '************0011',
  authDate: '2025-01-02',
  authTime: '10:11:12',
  /*
   * WHY : ⚠️ Assumptions: `authResponse` is `'A'` and not the stored `'00'` it used to hold, because the
   *       contract bounds this member to `pattern: '^[AD]$'`. `cbl/COPAUS1C.cbl` L311 tests the stored
   *       two-character code and moves `'A'` at L312 or `'D'` at L315, so the code itself never reaches
   *       a browser -- a fixture carrying it described a response no service can send, and the screen
   *       now branches on this member to choose the run-time colour the program writes over the field.
   * WHY : Assumptions: `authResponseReason` is the COMPOSED twenty-character form rather than the bare
   *       code, matching the contract, which assembles the code, a separator at position five and the
   *       description from position six at L325 to L327.
   */
  authResponse: 'A',
  authResponseReason: '0000-APPROVED',
  processingCode: '000000',
  approvedAmount: '125.50',
  posEntryMode: '01',
  messageSource: 'POS',
  merchantCategoryCode: '5411',
  cardExpiry: '2027-01',
  authType: 'A',
  transactionId: 'TRAN0000000001',
  matchStatus: 'P',
  fraudMark: ' ',
  merchantName: 'EXAMPLE GROCER',
  merchantId: 'MERCH000001',
  merchantCity: 'EXAMPLE CITY',
  merchantState: 'TX',
  merchantZip: '75001',
  message: null,
};

/**
 * Renders the screen at a concrete detail path with a probe at the summary destination.
 * @param {string} selector - Path segment the route parameter receives.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(selector: string): ReactElement {
  return (
    <MemoryRouter initialEntries={[`/authorizations/${selector}`]}>
      <Routes>
        <Route path={AUTHORIZATION_DETAIL_ROUTE} element={<AuthDetailScreen />} />
        <Route
          path={AUTHORIZATION_SUMMARY_ROUTE}
          element={<div>{`${ARRIVED} ${AUTHORIZATION_SUMMARY_ROUTE}`}</div>}
        />
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Builds one normalised failure carrying a status and an optional service sentence.
 * @param {number} status - HTTP status the service answered with.
 * @param {string | null} message - Sentence the service sent, or `null` when it sent none.
 * @returns {ApiRequestError} The normalised failure the client would raise.
 */
function failureWith(status: number, message: string | null): ApiRequestError {
  const problem: ApiError = {
    code: 'TEST',
    secondaryCode: '',
    message,
    severity: 'WARNING',
    subsystem: 'IMS',
    status,
    correlationId: 'test-correlation-id',
    path: '/api/v1/authorizations/pending',
    timestamp: '2025-01-01T00:00:00Z',
    fieldErrors: [],
    abend: null,
  };
  return new ApiRequestError('PROBLEM', status, problem, `test failure ${String(status)}`);
}

/** Resets every transport spy between cases. */
function resetSpies(): void {
  vi.mocked(getPendingAuthorizationScreen).mockReset();
  vi.mocked(getNextPendingAuthorization).mockReset();
  vi.mocked(setAuthorizationFraudState).mockReset();
}

/**
 * Asserts the fraud transition is derived from the mark already on the row, in both directions.
 *
 * Assumptions: a blank, an unrecognised value and a lower-case reported mark are all exercised, because
 * the reference stores this as a single character that is blank until a review happens -- so the
 * function has to answer for a value that is not one of the two documented codes, and answering
 * "report it" for an unreviewed row is the only safe reading.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function derivesTheFraudTransitionFromTheCurrentMark(): void {
  expect(nextFraudAction(' ')).toBe(FRAUD_REPORTED);
  expect(nextFraudAction('')).toBe(FRAUD_REPORTED);
  expect(nextFraudAction(FRAUD_WITHDRAWN)).toBe(FRAUD_REPORTED);
  expect(nextFraudAction(FRAUD_REPORTED)).toBe(FRAUD_WITHDRAWN);
  expect(nextFraudAction('f')).toBe(FRAUD_WITHDRAWN);
  /*
   * WHY : ⚠️ Assumptions: the three COMPOSED forms are the ones the service actually sends, and they are
   *       asserted because the bare characters above all passed while a reported row was still being
   *       reported again. `renderFraudMark` answers a lone hyphen for an unmarked row, the flag with its
   *       separator for a marked row whose date is blank, and the full ten characters otherwise.
   */
  expect(nextFraudAction('-')).toBe(FRAUD_REPORTED);
  expect(nextFraudAction(`${FRAUD_REPORTED}-`)).toBe(FRAUD_WITHDRAWN);
  expect(nextFraudAction(`${FRAUD_REPORTED}-01/02/25`)).toBe(FRAUD_WITHDRAWN);
  expect(nextFraudAction(`${FRAUD_WITHDRAWN}-01/02/25`)).toBe(FRAUD_REPORTED);
}

/**
 * Asserts a failure is reported with the service's sentence when it has one and the shared abend
 * sentence when it does not.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function prefersTheServiceSentence(): void {
  expect(detailFailureMessage(failureWith(409, 'Authorization already reviewed'))).toBe(
    'Authorization already reviewed',
  );
  expect(detailFailureMessage(failureWith(500, null))).toBe(UNEXPECTED_ABEND_OCCURRED);
  expect(detailFailureMessage(failureWith(500, '   '))).toBe(UNEXPECTED_ABEND_OCCURRED);
  expect(detailFailureMessage(new Error('not from the client'))).toBe(UNEXPECTED_ABEND_OCCURRED);
}

/**
 * Asserts the screen renders the read authorization, its labels and its masked card number.
 *
 * Assumptions: the masked form is asserted rather than a card number, because the transport module
 * refuses an unmasked value on arrival -- so a rendered unmasked number would mean the mask had been
 * removed somewhere between the two.
 * @returns {Promise<void>} Resolves once the rendered values have been found.
 */
async function rendersTheReadAuthorization(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  render(renderScreen(SELECTOR));

  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledWith(SELECTOR);
  expect(screen.getByText(AUTH_DETAIL_MERCHANT_HEADING)).toBeInTheDocument();
  expect(screen.getByText(AUTH_DETAIL_FIELD_LABELS.cardNumber)).toBeInTheDocument();
  expect(screen.getByText(DETAIL.cardNumber)).toBeInTheDocument();
  expect(screen.getByText(DETAIL.transactionId)).toBeInTheDocument();
  expect(screen.getByText(DETAIL.approvedAmount)).toBeInTheDocument();
}

/**
 * Presses the fifth key and confirms the prompt it opens, which is the only path that writes.
 *
 * ⚠️ Assumptions: the key OPENS a confirmation and no longer writes on its own, so a case that only
 * pressed it would assert nothing about the write. Browser validation found the reason the guard was
 * lifted out of the trigger: the confirmation used to wrap one button while the function-key bar's own
 * F5 button, carrying the identical accessible name, wrote immediately -- two controls with one name
 * and two safety semantics. Every entry now opens the same prompt.
 * @returns {Promise<void>} Resolves once the confirmation has been accepted.
 */
async function pressFifthKeyAndConfirm(): Promise<void> {
  await userEvent.keyboard('{F5}');
  await userEvent.click(await screen.findByRole('button', { name: /^OK$/u }));
}

/**
 * Asserts the fifth key submits the derived transition and reports the program's own confirmation.
 *
 * Assumptions: the re-read is asserted as well as the write, because the reference paints the map again
 * from the row after the transition -- so a screen that patched its rendered tag locally would leave
 * every other value, including the report date the write sets, describing the row as it was before.
 *
 * ⚠️ Refactoring Rationale: the sentence asserted is `COPAUS1C`'s own `AUTH MARKED FRAUD...` and this
 * case used to assert `COPAUS2C`'s `ADD SUCCESS` -- which the reference never puts on the message line
 * at all. `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` L253 to L262 moves `WS-FRD-ACT-MSG`, the
 * field holding that string, into `WS-MESSAGE` only on the FAILURE arm at L257; the success arm
 * performs `UPDATE-AUTH-DETAILS`, which writes its own confirmation at L535 or L537 according to the
 * state the row reached. The service's sentence is still returned and is still mocked here, so the case
 * proves the screen does not paint it.
 * @returns {Promise<void>} Resolves once the success sentence is on the glass.
 */
async function submitsTheFraudTransitionOnTheFifthKey(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: FRAUD_MESSAGES.ADD_SUCCESS,
  });
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  await pressFifthKeyAndConfirm();

  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).toBeInTheDocument();
  expect(screen.queryByText(FRAUD_MESSAGES.ADD_SUCCESS)).not.toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledWith(SELECTOR, {
    action: FRAUD_REPORTED,
  });
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(2);
}

/**
 * Asserts a marked row submits the withdrawal rather than a second report.
 *
 * Assumptions: the withdrawal confirmation is the second of the two sentences `UPDATE-AUTH-DETAILS`
 * chooses between, so asserting both directions is what proves the choice is made from the action
 * rather than fixed. `UPDT SUCCESS` is asserted absent for the reason the report case records.
 * @returns {Promise<void>} Resolves once the withdrawal has been submitted.
 */
async function withdrawsAnExistingFraudMark(): Promise<void> {
  /*
   * WHY : ⚠️ Assumptions: the mark is the COMPOSED ten-character field the service sends for a reported
   *       authorization -- the flag, a hyphen and the report date, per `cbl/COPAUS1C.cbl` L345 to L347 --
   *       and it used to be the bare flag `'F'`, which the service never sends. That fixture was what
   *       hid a real defect: the transition was derived by comparing the WHOLE field against `'F'`, so
   *       every genuinely marked row asked to be reported a second time and the withdrawal was
   *       unreachable in production while this case passed.
   */
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue({
    ...DETAIL,
    fraudMark: `${FRAUD_REPORTED}-01/02/25`,
  });
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'UPDATED',
    message: FRAUD_MESSAGES.UPDT_SUCCESS,
  });
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  await pressFifthKeyAndConfirm();

  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED)).toBeInTheDocument();
  expect(screen.queryByText(FRAUD_MESSAGES.UPDT_SUCCESS)).not.toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledWith(SELECTOR, {
    action: FRAUD_WITHDRAWN,
  });
}

/**
 * Asserts the twentieth painted value field is on the glass with the label the mapset paints above it.
 *
 * ⚠️ Assumptions: `Auth Code:` is asserted specifically because it was the one painted field the screen
 * omitted -- its label was declared and never used, so fourteen of the fifteen values the mapset paints
 * in rows 7 to 15 reached the DOM. The value bound to it is the PROCESSING code, which is
 * `cbl/COPAUS1C.cbl` L331's own binding and not a mistake in this expectation.
 * @returns {Promise<void>} Resolves once the field and its label have been found.
 */
async function rendersTheAuthorizationCodeField(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  render(renderScreen(SELECTOR));

  expect(await screen.findByText(AUTH_DETAIL_FIELD_LABELS.authCode)).toBeInTheDocument();
  expect(screen.getByText(String(DETAIL.processingCode))).toBeInTheDocument();
}

/**
 * Asserts the approval indicator takes the colour the program writes over it at run time.
 *
 * Assumptions: the token NAME is asserted rather than a rendered colour, because the screen resolves it
 * through the theme's CSS-variable surface -- so a resolved hue would test the pinned palette instead of
 * the mapping. `cbl/COPAUS1C.cbl` L311 to L317 moves `DFHGREEN` beside the approval character and
 * `DFHRED` beside the decline, overriding the mapset's static `COLOR=PINK` on every send.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function resolvesTheRuntimeApprovalColour(): void {
  expect(approvalToneToken('A')).toBe(DFH_RUNTIME_COLOR_TOKENS.DFHGREEN);
  expect(approvalToneToken('D')).toBe(DFH_RUNTIME_COLOR_TOKENS.DFHRED);
  expect(approvalToneToken('')).toBe(DFH_RUNTIME_COLOR_TOKENS.DFHRED);
}

/**
 * Asserts every painted value field of both blocks is declared, in the mapset's own reading order.
 *
 * Assumptions: the row arrays are compared as DATA against the twenty labels, which is the property
 * that made the omitted field detectable at all -- with the fields written out as elements, a missing
 * one is a line nobody typed and nothing can see.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function declaresEveryPaintedValueField(): void {
  const labels = [
    ...authorizationRows(DETAIL).map(labelOfRow),
    ...merchantRows(DETAIL).map(labelOfRow),
  ];
  expect(labels).toStrictEqual(Object.values(AUTH_DETAIL_FIELD_LABELS));
  expect(labels).toHaveLength(20);
}

/**
 * Reads one declared row's painted label.
 * @param {AuthDetailRow} row - The declared row.
 * @returns {string} That row's label.
 */
function labelOfRow(row: AuthDetailRow): string {
  return row.label;
}

/**
 * Asserts no nullable member reaches the glass as the word `null`.
 *
 * Assumptions: an all-null projection is used because every optional member of this contract is
 * declared nullable, and React renders a `null` CHILD as nothing while a `null` interpolated into text
 * renders the word -- so the guard has to be asserted rather than assumed from the framework.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function rendersNoNullText(): void {
  const empty: PendingAuthDetailScreen = {
    ...DETAIL,
    authDate: null,
    authTime: null,
    processingCode: null,
    posEntryMode: null,
    messageSource: null,
    merchantCategoryCode: null,
    cardExpiry: null,
    authType: null,
    merchantName: null,
    merchantId: null,
    merchantCity: null,
    merchantState: null,
    merchantZip: null,
  };
  const values = [...authorizationRows(empty), ...merchantRows(empty)].map(valueOfRow);
  expect(values).not.toContain('null');
  expect(values).not.toContain('undefined');
}

/**
 * Reads one declared row's rendered value.
 * @param {AuthDetailRow} row - The declared row.
 * @returns {string} That row's value.
 */
function valueOfRow(row: AuthDetailRow): string {
  return row.value;
}

/**
 * Asserts this screen renders no data-entry control at all.
 *
 * Assumptions: absence is asserted explicitly because it is a fidelity claim rather than an accident --
 * every named field of `bms/COPAU01.bms` is `ASKIP`, the mapset declares no `UNPROT` and no `IC`, and a
 * later edit adding an input would otherwise pass unnoticed.
 * @returns {Promise<void>} Resolves once the record is on the glass and has been inspected.
 */
async function rendersNoDataEntryControl(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  const { container } = render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  expect(container.querySelectorAll('input')).toHaveLength(0);
  expect(container.querySelectorAll('textarea')).toHaveLength(0);
  expect(container.querySelectorAll('form')).toHaveLength(0);
  expect(container.querySelector('[autofocus]')).toBeNull();
}

/**
 * Asserts the eighth key reports the end of an account's authorizations and stays on the row.
 *
 * Assumptions: staying put is asserted as well as the sentence, because `PROCESS-PF8-KEY` does not clear
 * the map -- so a screen that blanked itself at the end of the set would lose the row the reviewer was
 * looking at.
 * @returns {Promise<void>} Resolves once the end-of-set sentence is on the glass.
 */
async function reportsTheEndOfTheAuthorizationSet(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  vi.mocked(getNextPendingAuthorization).mockResolvedValue({
    authorization: null,
    endOfData: true,
    message: DETAIL_MESSAGES.ALREADY_AT_THE_LAST_AUTHORIZATION,
  });
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  await userEvent.keyboard('{F8}');

  expect(
    await screen.findByText(DETAIL_MESSAGES.ALREADY_AT_THE_LAST_AUTHORIZATION),
  ).toBeInTheDocument();
  expect(screen.getByText(DETAIL.transactionId)).toBeInTheDocument();
}

/**
 * Asserts the third key returns to the summary screen the selection was made on.
 * @returns {Promise<void>} Resolves once the summary has reported its arrival.
 */
async function returnsToTheSummaryOnTheThirdKey(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  await userEvent.keyboard('{F3}');

  expect(await screen.findByText(`${ARRIVED} ${AUTHORIZATION_SUMMARY_ROUTE}`)).toBeInTheDocument();
}

/**
 * Asserts a failed read is reported with the service's own sentence.
 * @returns {Promise<void>} Resolves once the refusal is on the glass.
 */
async function reportsAFailedRead(): Promise<void> {
  const reported = 'Pending authorization not found';
  vi.mocked(getPendingAuthorizationScreen).mockRejectedValue(failureWith(404, reported));
  render(renderScreen(SELECTOR));

  expect(await screen.findByText(reported)).toBeInTheDocument();
  /*
   * Assumptions: the fifth key must be inert with no row loaded, which the screen expresses by disabling
   * that binding -- so a reviewer cannot mark an authorization the screen never read.
   */
  await userEvent.keyboard('{F5}');
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
}

/**
 * Asserts a mount carrying no selector renders the bounded dead end with its one exit.
 *
 * Assumptions: the screen is mounted at a PARAMETERLESS route to produce the condition, because that is
 * the only way the parameter is genuinely absent -- `useParams` resolves an unmatched name to
 * `undefined`, and the screen's own docstring records that a misspelled parameter name would land here on
 * every visit. A concrete path with a blank segment would not: the parameter would be present and blank,
 * so the screen would ask the service about it, which is a different condition with a different answer.
 *
 * Assumptions: the absence of a read is asserted as well as the rendering, because the branch exists to
 * avoid asking the service about an authorization the route never named.
 * @returns {Promise<void>} Resolves once the bounded result has been found.
 */
async function rendersABoundedDeadEndWithNoSelector(): Promise<void> {
  render(
    <MemoryRouter initialEntries={['/authorizations/detail']}>
      <Routes>
        <Route path="/authorizations/detail" element={<AuthDetailScreen />} />
        <Route
          path={AUTHORIZATION_SUMMARY_ROUTE}
          element={<div>{`${ARRIVED} ${AUTHORIZATION_SUMMARY_ROUTE}`}</div>}
        />
      </Routes>
    </MemoryRouter>,
  );

  expect(await screen.findByText(UNEXPECTED_ABEND_OCCURRED)).toBeInTheDocument();
  expect(vi.mocked(getPendingAuthorizationScreen)).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK03 }));
  expect(await screen.findByText(`${ARRIVED} ${AUTHORIZATION_SUMMARY_ROUTE}`)).toBeInTheDocument();
}

/**
 * Asserts each transition maps to the confirmation `UPDATE-AUTH-DETAILS` writes for it.
 *
 * Assumptions: the two sentences come from the catalog rather than being retyped, and the two
 * `COPAUS2C` strings are asserted to be different values -- which is what makes the "not rendered"
 * expectations in the two transition cases meaningful rather than vacuous.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function reportsTheConfirmationForEachTransition(): void {
  expect(fraudOutcomeMessage(FRAUD_REPORTED)).toBe(DETAIL_MESSAGES.AUTH_MARKED_FRAUD);
  expect(fraudOutcomeMessage(FRAUD_WITHDRAWN)).toBe(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED);
  expect(fraudOutcomeMessage(FRAUD_REPORTED)).not.toBe(FRAUD_MESSAGES.ADD_SUCCESS);
  expect(fraudOutcomeMessage(FRAUD_WITHDRAWN)).not.toBe(FRAUD_MESSAGES.UPDT_SUCCESS);
}

/**
 * Asserts no fraud entry point writes without confirmation, and that cancelling writes nothing.
 *
 * ⚠️ Assumptions: BOTH controls carrying the fifth key's label are exercised, plus the key itself,
 * because browser validation proved they had different safety semantics -- the one beside the record
 * confirmed while the bar's wrote instantly. Cancelling is asserted too: a prompt that opens but whose
 * dismissal still writes would pass a test that only checked the confirmed path.
 * @returns {Promise<void>} Resolves once every entry point has been exercised.
 */
async function guardsEveryFraudEntryPoint(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: FRAUD_MESSAGES.ADD_SUCCESS,
  });
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  const triggers = screen.getAllByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK05 });
  expect(triggers.length).toBeGreaterThan(1);
  for (const trigger of triggers) {
    await userEvent.click(trigger);
    expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /^Cancel$/u }));
  }

  await userEvent.keyboard('{F5}');
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();

  await userEvent.click(await screen.findByRole('button', { name: /^OK$/u }));
  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
}

/** Registers the detail-screen cases. */
function authDetailCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it(
    'derives the fraud transition from the current mark',
    derivesTheFraudTransitionFromTheCurrentMark,
  );
  it('prefers the service sentence for a failure', prefersTheServiceSentence);
  it('renders the read authorization', rendersTheReadAuthorization);
  it('submits the fraud transition on the fifth key', submitsTheFraudTransitionOnTheFifthKey);
  it('withdraws an existing fraud mark', withdrawsAnExistingFraudMark);
  it('reports the end of the authorization set', reportsTheEndOfTheAuthorizationSet);
  it('returns to the summary on the third key', returnsToTheSummaryOnTheThirdKey);
  it('reports a failed read', reportsAFailedRead);
  it('renders a bounded dead end with no selector', rendersABoundedDeadEndWithNoSelector);
  it('renders the authorization code field', rendersTheAuthorizationCodeField);
  it('resolves the runtime approval colour', resolvesTheRuntimeApprovalColour);
  it('declares every painted value field', declaresEveryPaintedValueField);
  it('renders no null text for absent members', rendersNoNullText);
  it('renders no data entry control', rendersNoDataEntryControl);
  it('guards every fraud entry point behind one confirmation', guardsEveryFraudEntryPoint);
  it('reports the confirmation for each transition', reportsTheConfirmationForEachTransition);
}

describe('pending-authorization detail screen', authDetailCases);
