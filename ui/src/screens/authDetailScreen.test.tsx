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
  detailFailureMessage,
  nextFraudAction,
} from './authDetail';

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
  authResponse: '00',
  authResponseReason: '0000',
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
 * Asserts the fifth key submits the derived transition and reports the service's own sentence.
 *
 * Assumptions: the re-read is asserted as well as the write, because the reference paints the map again
 * from the row after the transition -- so a screen that patched its rendered tag locally would leave
 * every other value, including the report date the write sets, describing the row as it was before.
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

  await userEvent.keyboard('{F5}');

  expect(await screen.findByText(FRAUD_MESSAGES.ADD_SUCCESS)).toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledWith(SELECTOR, {
    action: FRAUD_REPORTED,
  });
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(2);
}

/**
 * Asserts a marked row submits the withdrawal rather than a second report.
 * @returns {Promise<void>} Resolves once the withdrawal has been submitted.
 */
async function withdrawsAnExistingFraudMark(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue({
    ...DETAIL,
    fraudMark: FRAUD_REPORTED,
  });
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'UPDATED',
    message: FRAUD_MESSAGES.UPDT_SUCCESS,
  });
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  await userEvent.keyboard('{F5}');

  expect(await screen.findByText(FRAUD_MESSAGES.UPDT_SUCCESS)).toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledWith(SELECTOR, {
    action: FRAUD_WITHDRAWN,
  });
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
}

describe('pending-authorization detail screen', authDetailCases);
