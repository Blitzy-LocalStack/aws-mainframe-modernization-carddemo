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
 *
 * ⚠️ Refactoring Rationale: two cases cover the INTEGRITY of the fraud confirmation's target, which
 * nothing here covered while the prompt was held as a bare boolean -- every case asserted the write
 * from a screen that had not moved underneath it, so a confirmation retargeted by the eighth key or by
 * a re-read satisfied all of them. Both new cases exercise a transition between opening the prompt and
 * confirming it, and both assert on the TRANSPORT: what was written, to which selector, in which
 * direction. A case that asserted the prompt's visibility alone would pass against a screen that
 * merely hid a prompt whose confirmation still wrote.
 */

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
import { MONEY_PICTURES, applyMoneyEditMask } from '../format/money';
import type { ApiError, PendingAuthDetail, PendingAuthDetailScreen } from '../api/types';
import { AppShell } from '../layout/AppShell';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import {
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  UNEXPECTED_ABEND_OCCURRED,
} from '../messages/messages';
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
 * Synthetic sealed selector of the authorization the eighth key steps to.
 *
 * Assumptions: distinct from {@link SELECTOR} and nothing more, because the property under test is
 * that a captured confirmation target and the selector on the route can DIFFER -- so the two values
 * only have to be told apart, never parsed.
 */
const NEXT_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-000001';

/**
 * The rendering the authorization after {@link SELECTOR} answers with.
 *
 * ⚠️ Assumptions: this record is already MARKED while {@link DETAIL} is not, and the asymmetry is the
 * point. `nextFraudAction` derives the transition from the rendered mark, so an unmarked record asks
 * for a report and a marked one asks for a removal -- which means a confirmation opened on `DETAIL`
 * and confirmed against this record would invert the write as well as retargeting it. A fixture that
 * differed only in its identifier would prove half the property.
 */
const NEXT_DETAIL: PendingAuthDetailScreen = {
  ...DETAIL,
  transactionId: 'TRAN0000000002',
  fraudMark: `${FRAUD_REPORTED}-01/02/25`,
};

/**
 * The member record the paging operation answers with for that same authorization.
 *
 * Assumptions: the full member shape is built out rather than cast, because the screen reads only
 * `key` from it and a partial fixture would let a later contract member appear with no test noticing.
 * The card number carries the masked rendering every non-administrative read returns.
 */
const NEXT_ROW: PendingAuthDetail = {
  key: NEXT_SELECTOR,
  accountId: '00000000011',
  authDate: 25002,
  authTime: 36672000,
  authOrigDate: '250102',
  authOrigTime: '101112',
  cardNum: DETAIL.cardNumber,
  authType: 'A',
  cardExpiryDate: '0127',
  messageType: '0100',
  messageSource: 'POS',
  authIdCode: '000001',
  authRespCode: '00',
  authRespReason: '0000',
  processingCode: '000000',
  transactionAmt: '125.50',
  approvedAmt: '125.50',
  merchantCategoryCode: '5411',
  acqrCountryCode: '840',
  posEntryMode: '01',
  merchantId: 'MERCH000001',
  merchantName: 'EXAMPLE GROCER',
  merchantCity: 'EXAMPLE CITY',
  merchantState: 'TX',
  merchantZip: '75001',
  transactionId: NEXT_DETAIL.transactionId,
  matchStatus: 'P',
  authFraud: FRAUD_REPORTED,
  fraudRptDate: '2025-01-02',
};

/**
 * A read whose completion the case controls, so the screen can be observed mid-read.
 *
 * Assumptions: the in-flight state is produced by withholding a resolution rather than by timers,
 * because `userEvent` flushes microtasks on every interaction -- a read delayed by a resolved promise
 * would already have completed by the time the next keystroke returned, and the state under test
 * would never be entered.
 */
interface DeferredRead {
  /** The pending read, handed to the transport spy in place of a resolved value. */
  readonly promise: Promise<PendingAuthDetailScreen>;
  /** Completes that read with one rendering, at the moment the case chooses. */
  readonly release: (screen: PendingAuthDetailScreen) => void;
}

/**
 * Builds a read the case completes itself.
 * @returns {DeferredRead} The pending read and the handle that completes it.
 */
function deferRead(): DeferredRead {
  const holder: { resolve?: (screen: PendingAuthDetailScreen) => void } = {};

  /**
   * Publishes the promise's own resolver so it can be called from outside the executor.
   * @param {(screen: PendingAuthDetailScreen) => void} resolve - The promise's resolver.
   * @returns {void} Nothing; the resolver is published on the holder.
   */
  function captureResolver(resolve: (screen: PendingAuthDetailScreen) => void): void {
    holder.resolve = resolve;
  }

  const promise = new Promise<PendingAuthDetailScreen>(captureResolver);

  /**
   * Completes the withheld read with one rendering.
   * @param {PendingAuthDetailScreen} screen - The rendering the service answers with.
   * @returns {void} Nothing; the screen observes the resolution.
   * @throws {Error} If the executor has not run, which would leave the read uncompletable.
   */
  function release(screen: PendingAuthDetailScreen): void {
    if (holder.resolve === undefined) {
      throw new Error('The deferred read has no resolver, so the promise executor never ran.');
    }
    holder.resolve(screen);
  }

  return { promise, release };
}

/**
 * Answers each read with the rendering that belongs to the selector it was asked about.
 *
 * Assumptions: the spy is driven by the ARGUMENT rather than by call order, because the case under
 * test changes which authorization is addressed part way through -- an order-driven mock would answer
 * correctly whatever the screen asked for, which is exactly the fault being tested.
 * @param {string} key - The selector the screen asked about.
 * @returns {Promise<PendingAuthDetailScreen>} The rendering for that selector.
 */
function readAddressed(key: string): Promise<PendingAuthDetailScreen> {
  return Promise.resolve(key === NEXT_SELECTOR ? NEXT_DETAIL : DETAIL);
}

/**
 * Confirms an open prompt, and does nothing when none is offered.
 *
 * Assumptions: the confirmation is ATTEMPTED rather than assumed present, so one case can cover both
 * admissible outcomes of a stale prompt -- a screen that closed it and a screen that kept it open
 * against the authorization it was opened on. The write assertions that follow are what separate
 * them, and neither is satisfied by a write against the authorization that arrived.
 * @returns {Promise<void>} Resolves once the confirmation has been accepted, or immediately.
 */
async function confirmIfStillOffered(): Promise<void> {
  /*
   * WHY : Assumptions: the accept is looked for INSIDE the dialog, because it carries the mapset's own
   *       row-24 legend text and so shares its accessible name with the trigger beside the record and
   *       with the legend's own control. An unscoped query would find one of those and click a control
   *       that opens the prompt rather than one that accepts it.
   * WHY : Assumptions: the dialog's absence is a real answer here rather than an environment artefact.
   *       The dialog primitive hides a closed-but-mounted surface with an inline `display: none`, which
   *       the query engine honours without a stylesheet.
   */
  const dialog = screen.queryByRole('dialog');
  if (dialog === null) {
    return;
  }
  const confirmation = within(dialog).queryByRole('button', {
    name: AUTH_DETAIL_KEY_LABELS.PFK05,
  });
  if (confirmation !== null) {
    await userEvent.click(confirmation);
  }
}

/**
 * Asserts no fraud write was addressed to the authorization the prompt was NOT opened on.
 *
 * Assumptions: both members of the action domain are named, because `FraudAction` admits exactly `F`
 * and `R` -- so refusing both is exhaustive over every request that could reach that selector, and
 * needs no reasoning about which direction a defect would have chosen.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function assertNothingWrittenAgainstTheNextAuthorization(): void {
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalledWith(NEXT_SELECTOR, {
    action: FRAUD_REPORTED,
  });
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalledWith(NEXT_SELECTOR, {
    action: FRAUD_WITHDRAWN,
  });
}

/**
 * Renders the screen at a concrete detail path with a probe at the summary destination.
 * @param {string} selector - Path segment the route parameter receives.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(selector: string): ReactElement {
  return (
    <MemoryRouter initialEntries={[`/authorizations/${selector}`]}>
      {/*
        Refactoring Rationale: ⚠️ the screen is rendered INSIDE `AppShell`, where it was rendered bare.
        It now DELEGATES its title band, its row-23 message line and its row-24 legend to the shell
        rather than composing them, so a bare mount would leave all three rendered by nothing -- the
        message assertions below would find no band and the fraud case, which requires more than one
        control carrying the fifth key's label, would find only the one in the record block.
        Assumptions: `ui/src/App.tsx` mounts the shell around the router and `AppShell` renders
        `children ?? <Outlet />`, so mounting it around the routes here paints the same frame the
        application paints. This is the arrangement `ui/src/screens/entryScreens.test.tsx` uses.
      */}
      <AppShell>
        <Routes>
          <Route path={AUTHORIZATION_DETAIL_ROUTE} element={<AuthDetailScreen />} />
          <Route
            path={AUTHORIZATION_SUMMARY_ROUTE}
            element={<div>{`${ARRIVED} ${AUTHORIZATION_SUMMARY_ROUTE}`}</div>}
          />
        </Routes>
      </AppShell>
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
 * Asserts a failure is reported with the service's sentence when it has one, and otherwise with the
 * shared sentence its own classification selects.
 *
 * ⚠️ Refactoring Rationale: the two message-less cases previously both expected the abend sentence,
 * and they now expect DIFFERENT sentences from each other. The shared catalogue declares
 * `TRANSIENT_FAILURE_TRY_AGAIN` and `PERSISTENT_FAILURE_REPORT_IT`, so a gateway that timed out is no
 * longer reported in the same words as a program that failed. A 504 is on the client's transient status
 * list and a 500 is not, which is what makes this pair the discriminating one: an implementation that
 * ignored the classification and answered one sentence for both would fail on whichever case it did not
 * choose.
 *
 * Assumptions: the abend sentence is still expected for a raised `Error`, because that is a rejection
 * the transport never classified -- there is no `transient` judgement to read, and the reference's own
 * sentence for "the program could not say more" is the right answer.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function prefersTheServiceSentence(): void {
  expect(detailFailureMessage(failureWith(409, 'Authorization already reviewed'))).toBe(
    'Authorization already reviewed',
  );
  expect(detailFailureMessage(failureWith(500, null))).toBe(PERSISTENT_FAILURE_REPORT_IT);
  expect(detailFailureMessage(failureWith(500, '   '))).toBe(PERSISTENT_FAILURE_REPORT_IT);
  expect(detailFailureMessage(failureWith(504, null))).toBe(TRANSIENT_FAILURE_TRY_AGAIN);
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
  /*
   * WHY : ⚠️ Refactoring Rationale: the amount is looked for in its MASKED form, and was looked for
   *       as the wire string. The screen renders it through `MONEY_PICTURES.transactionAmount` -- the
   *       money finding's own resolution, since browser validation named this screen's bare `125.50`
   *       one of four incompatible money renderings. The expectation is computed by the helper the
   *       screen calls rather than written out, so it measures the picture in use instead of restating
   *       one picture's output in a second place.
   */
  expect(
    screen.getByText(applyMoneyEditMask(DETAIL.approvedAmount, MONEY_PICTURES.transactionAmount)),
  ).toBeInTheDocument();
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
  await userEvent.click(await fraudAcceptControl());
}

/**
 * Locates the accept inside the open fraud confirmation.
 *
 * ⚠️ Assumptions: the surface is found by `role="dialog"` and the accept is scoped INSIDE it, and both
 * halves are load-bearing. The role is the contract: the confirmation was a `Popconfirm`, whose tooltip
 * primitive hardcodes `role="tooltip"`, so a regression to it leaves this query with nothing to find.
 * The scoping is required because the accept carries the mapset's own row-24 legend text and therefore
 * shares its accessible name with the trigger beside the record and with the legend's own control -- an
 * unscoped query would find one of those and press a control that OPENS the prompt rather than one that
 * accepts it.
 * @returns {Promise<HTMLElement>} The dialog's accept.
 * @throws {Error} If no confirmation is open, which means the fifth key raised none.
 */
async function fraudAcceptControl(): Promise<HTMLElement> {
  const dialog = await screen.findByRole('dialog');
  return within(dialog).getByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK05 });
}

/**
 * Waits for the fraud confirmation to leave the document, ending its leave animation by hand.
 *
 * ⚠️ Assumptions: the closing ANIMATION is ended here, and that is an accommodation for the test DOM
 * rather than a statement about the screen. The dialog primitive parks its markup only once the leave
 * animation reports finishing and it listens for a native end event on the panel; jsdom applies the
 * class that starts the animation but runs none and fires nothing, so the panel would sit in
 * `ant-zoom-leave-active` and a closed-state assertion would describe an animation rather than a screen.
 * The event fired is `transitionend` rather than `animationend` because jsdom exposes no
 * `AnimationEvent`, which makes the library settle on a vendor-prefixed animation name Testing Library
 * does not emit; both events reach the one handler, which does not inspect the type.
 *
 * Assumptions: the event is fired on every retry, because the library accepts an end event only once its
 * own step queue has reached the active step and that step is scheduled through
 * `requestAnimationFrame`. Re-firing costs nothing once the panel has gone.
 * @returns {Promise<void>} Resolves once no dialog is in the document.
 */
async function waitForTheFraudConfirmationToClose(): Promise<void> {
  await waitFor(
    /**
     * Ends the leave animation if one is still running, then asserts the confirmation has gone.
     * @returns {void} Nothing; the expectation throws until the dialog is unreachable.
     */
    function theConfirmationIsClosed(): void {
      const leaving = screen.queryByRole('dialog');

      if (leaving !== null) {
        fireEvent.transitionEnd(leaving);
      }

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    },
  );
}

/**
 * Answers the opening read and the re-read with different renderings.
 *
 * ⚠️ Purpose: a successful fraud write is followed by a re-read, and the screen now refuses to paint a
 * write's confirmation over a row that CONTRADICTS it -- a report confirmed above a row still reading
 * unmarked is a success announced beside the value it denies. A fixture answering every read with one
 * rendering therefore describes a service that ignores its own contract:
 * `services/authorization-service/src/main/resources/openapi/authorization-api.yaml` L2881 to L2895
 * composes `fraudMark` from the PERSISTENT status character, so a stored mark cannot come back absent.
 *
 * Assumptions: the opening read is queued as a single answer and the applied rendering becomes the
 * standing one, so the ordering holds however many reads follow and no case has to know when the first
 * one settled.
 * @param {PendingAuthDetailScreen} opening - The rendering the first read answers with.
 * @param {PendingAuthDetailScreen} applied - The rendering every read after it answers with.
 * @returns {void} Nothing; the answers are queued on the mock as a side effect.
 */
function readsThen(opening: PendingAuthDetailScreen, applied: PendingAuthDetailScreen): void {
  vi.mocked(getPendingAuthorizationScreen)
    .mockResolvedValueOnce(opening)
    .mockResolvedValue(applied);
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
  readsThen(DETAIL, NEXT_DETAIL);
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
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(2);
  /*
   * WHY : Assumptions: the SEVERITY is asserted alongside the sentence, through the band's own live
   *       region and the design system's rendered variant. `COPAUS1C.cbl` L531 to L538 reaches this
   *       sentence only from the `STATUS-OK` arm after a syncpoint, so it reports a completed write --
   *       and `ui/src/layout/MessageBand.tsx` renders a completed write as a polite `status` region in
   *       the success variant while every refusal on this screen is an assertive `alert`. Asserting the
   *       text alone would pass with the confirmation painted as a failure, which is the one thing an
   *       operator reads the band's colour for.
   */
  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  expect(within(band).getByRole('status').className).toContain('ant-alert-success');
  expect(within(band).queryByRole('alert')).toBeNull();
}

/**
 * Asserts a confirmation opened on one authorization cannot write against the one PF8 steps to.
 *
 * ⚠️ Refactoring Rationale: this case exists because the step changes only the route PARAMETER, so
 * the component survives it while everything it renders is replaced. A confirmation held as a bare
 * boolean therefore stayed open across the step and composed its request from whatever was current by
 * the time it was confirmed -- the authorization that ARRIVED, in the direction that authorization's
 * own mark implied. Both halves are exercised here: {@link NEXT_DETAIL} is a different selector AND
 * carries the opposite mark, so a retargeted write would be visible as either the wrong address or the
 * wrong direction.
 *
 * Assumptions: the confirmation is attempted after the step rather than assumed unavailable, and the
 * write assertions are what the case turns on -- nothing may be written against the authorization that
 * arrived, whatever the prompt chose to do about its own visibility.
 * @returns {Promise<void>} Resolves once the step has completed and the confirmation been attempted.
 */
async function refusesAConfirmationAfterTheEighthKeySteps(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockImplementation(readAddressed);
  /*
   * WHY : Assumptions: the write spy is given an answer even though no write is expected, so a
   *       regression fails on the expectation below rather than on an unhandled rejection from the
   *       screen awaiting an unstubbed spy -- the second reports the same fault as a stack trace in
   *       production code, which reads as a screen defect rather than as the assertion it is.
   */
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: FRAUD_MESSAGES.ADD_SUCCESS,
  });
  vi.mocked(getNextPendingAuthorization).mockResolvedValue({
    authorization: NEXT_ROW,
    endOfData: false,
    message: null,
  });
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(DETAIL.transactionId)).toBeInTheDocument();

  await userEvent.keyboard('{F5}');
  expect(await fraudAcceptControl()).toBeInTheDocument();

  await userEvent.keyboard('{F8}');
  expect(await screen.findByText(NEXT_DETAIL.transactionId)).toBeInTheDocument();

  await confirmIfStillOffered();

  assertNothingWrittenAgainstTheNextAuthorization();
  /*
   * WHY : Assumptions: the delivered screen closes the prompt rather than keeping it aimed at the
   *       authorization it was opened on, so nothing is written at all -- and that is asserted as the
   *       behaviour on top of the invariant above. An open confirmation names ONE authorization to a
   *       reviewer, and the record it names has left the glass, so holding the question open over a
   *       record nobody can read would be a second way to get consent wrong.
   */
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenLastCalledWith(NEXT_SELECTOR);
}

/**
 * Asserts a confirmation cannot survive a read that replaces the record it was opened over.
 *
 * ⚠️ Assumptions: the mark flips WITHOUT the selector changing, which is the second, quieter half of
 * the same defect: the transition is derived from the rendered mark, so a re-read of the very same
 * authorization -- another reviewer's write, or this screen's own Enter -- is enough to invert the
 * direction under an open prompt. A confirmation read as "mark this as fraud" would then submit a
 * removal, against the right record.
 *
 * Assumptions: the read is withheld rather than delayed, so the screen is genuinely mid-read while the
 * case presses keys at it; and the fifth key is exercised in that state too, because a control that
 * could open a prompt with no record on the glass would be capturing a target from nothing.
 * @returns {Promise<void>} Resolves once the withheld read has completed and been probed.
 */
async function refusesAConfirmationAcrossAReadThatReplacesTheRecord(): Promise<void> {
  const reread = deferRead();
  const marked: PendingAuthDetailScreen = { ...DETAIL, fraudMark: NEXT_DETAIL.fraudMark };
  // Assumptions: the write spy answers for the reason the step case records -- a regression must fail
  //   on the expectation, not on an unhandled rejection inside the screen.
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'UPDATED',
    message: FRAUD_MESSAGES.UPDT_SUCCESS,
  });
  vi.mocked(getPendingAuthorizationScreen)
    .mockResolvedValueOnce(DETAIL)
    .mockReturnValueOnce(reread.promise);
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(DETAIL.transactionId)).toBeInTheDocument();

  await userEvent.keyboard('{F5}');
  expect(await fraudAcceptControl()).toBeInTheDocument();

  /*
   * WHY : Assumptions: the re-read is started with an UNMAPPED key rather than with Enter, and the
   *       difference is only about what the keystroke can collide with. `COPAUS1C.cbl` L194 to L196
   *       re-runs the enter path for any key it does not admit, so this reaches the same
   *       `PROCESS-ENTER-KEY` read; Enter itself is claimed by whichever control holds focus, and an
   *       open confirmation may hold it, which would make the keystroke a confirmation rather than a
   *       read and the case would prove nothing about either.
   */
  await userEvent.keyboard('{F4}');
  expect(screen.queryByText(DETAIL.transactionId)).toBeNull();
  /*
   * WHY : ⚠️ Refactoring Rationale: the absence is measured on the DIALOG and was measured on the
   *       accept's accessible name. Two things changed and both matter: the accept now carries the
   *       mapset's fifth-key legend, which the trigger beside the record also carries, so a name query
   *       would find the trigger and report a prompt that is not open; and the dialog primitive parks
   *       its markup only once its leave animation reports finishing, which
   *       {@link waitForTheFraudConfirmationToClose} drives in a test DOM that runs no animations.
   */
  await waitForTheFraudConfirmationToClose();
  await userEvent.keyboard('{F5}');
  expect(screen.queryByRole('dialog')).toBeNull();

  reread.release(marked);
  expect(await screen.findByText(marked.fraudMark)).toBeInTheDocument();

  await confirmIfStillOffered();

  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
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
  readsThen({ ...DETAIL, fraudMark: `${FRAUD_REPORTED}-01/02/25` }, DETAIL);
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
 * Asserts this screen reserves NO row-22 information line, because its mapset declares none.
 *
 * ⚠️ Purpose: the shell carries a row-22 channel beside the row-23 message band, and a screen opts into
 * it by publishing `message.information`. This screen deliberately does not, and "deliberately omitted"
 * and "forgotten" are indistinguishable without a case saying which. `bms/COPAU01.bms` runs merchant
 * city, state and zip at `POS=(21,...)` (L257 to L282), then `ERRMSG` at `POS=(23,1)` (L284 to L287) and
 * the key legend at `POS=(24,1)` (L288 to L292) -- row 22 is empty on the terminal, so reserving a line
 * for it here would paint a band the reference never paints.
 *
 * Assumptions: the row-23 band is asserted PRESENT in the same case. Absence alone would also be
 * satisfied by a screen that published nothing at all to the shell, which is a different and worse
 * defect -- the sibling summary screen's own row-22 case proves the channel works when a mapset does
 * declare the field.
 * @returns {Promise<void>} Resolves once both bands have been inspected.
 */
async function reservesNoInformationLine(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(DETAIL);
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  expect(
    screen.queryByTestId(INFORMATION_BAND_TEST_ID),
    'COPAU01 paints nothing on row 22, so no line is reserved for it',
  ).toBeNull();
  expect(
    screen.getByTestId(MESSAGE_BAND_TEST_ID),
    'while row 23 is declared and is reserved on every turn',
  ).toBeInTheDocument();
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
      {/*
        ⚠️ Assumptions: the shell is mounted here TOO, and the dead end is still expected to render with
        no frame around it. The screen publishes an empty key list for this state and no header or band
        at all, and `PfKeyBar` renders `null` for an empty list -- so the shell contributes nothing, and
        the one control carrying the third key's label is the exit inside the bounded result. That is
        what makes the unqualified query below unambiguous, and it is the property this mount asserts
        that a bare one could not.
      */}
      <AppShell>
        <Routes>
          <Route path="/authorizations/detail" element={<AuthDetailScreen />} />
          <Route
            path={AUTHORIZATION_SUMMARY_ROUTE}
            element={<div>{`${ARRIVED} ${AUTHORIZATION_SUMMARY_ROUTE}`}</div>}
          />
        </Routes>
      </AppShell>
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
  readsThen(DETAIL, NEXT_DETAIL);
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

  await userEvent.click(await fraudAcceptControl());
  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
}

/**
 * ⚠️ Asserts the declining choice takes focus on EVERY opening of the fraud prompt, not just the first.
 *
 * ⚠️ Purpose: close a browser-measured asymmetry. Sampling `document.activeElement` every 16ms across
 * 463 samples found the prompt correct on a FIRST opening -- focus on `Cancel` at 0ms, at 900ms and at
 * 1500ms -- and wrong on a RE-opening, where it stayed on the destructive trigger for about 360ms and
 * then settled on the dialogue's own container element. The cause is that `autoFocus` is a MOUNT-time
 * platform attribute and the design system keeps a dismissed dialogue mounted at `display:none`, so a
 * second opening re-shows a control that never left and nothing re-applies the attribute. The screen
 * now destroys the surface on withdrawal, which restores the mount.
 *
 * ⚠️ Assumptions: the SECOND opening is what carries the property, so it is asserted after a genuine
 * withdrawal-and-settle rather than after a state reset. {@link waitForTheFraudConfirmationToClose}
 * ends the leave animation by hand, which is also what lets the destroyed surface actually leave --
 * the flag reaches the library as the leave motion's `removeOnLeave`, so removal waits for an animation
 * jsdom never runs on its own.
 *
 * ⚠️ Assumptions: the check is `toHaveFocus` on the DECLINING control rather than "not the accept".
 * The measured failure settled on the dialogue's container element, which is neither control, so an
 * assertion phrased as a negative about the accept would have passed against the defect.
 *
 * Assumptions: the accept is asserted to be reachable but unpressed on both openings, so a case that
 * moved focus by removing the accept from the surface could not satisfy this.
 *
 * ⚠️ Assumptions: each opening is a POINTER press on the trigger and NOT the fifth key, and that choice
 * is what makes this case falsifiable. A negative control proved it: with the destroy flag removed the
 * key-opened form of this case still PASSED, because a key press moves no focus and the withdrawal that
 * precedes the second opening is itself a click on `Cancel` -- so the focus was still on `Cancel` from
 * the gesture that dismissed the prompt, and the assertion was satisfied without the surface having
 * placed it. A pointer press moves the focus onto the trigger first, which is what the browser did when
 * it measured the defect, so a re-opening that fails to place the focus now leaves it on the trigger and
 * the assertion reports that. With the flag removed, this form fails on the second opening.
 * @returns {Promise<void>} Resolves once both openings have been asserted.
 */
async function focusesTheDecliningChoiceOnEveryOpening(): Promise<void> {
  readsThen(DETAIL, NEXT_DETAIL);
  render(renderScreen(SELECTOR));
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();

  for (const opening of ['first', 'second']) {
    const trigger = screen.getAllByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK05 })[0];
    expect(trigger, 'a control must exist to raise the prompt from').toBeDefined();
    await userEvent.click(trigger as HTMLElement);
    const decline = await screen.findByRole('button', { name: /^Cancel$/u });

    expect(decline, `the ${opening} opening must focus the answer that walks away`).toHaveFocus();
    expect(
      await fraudAcceptControl(),
      `and the ${opening} opening must still offer the accept, unpressed`,
    ).toBeInTheDocument();
    expect(
      vi.mocked(setAuthorizationFraudState),
      `no opening may write -- the ${opening} included`,
    ).not.toHaveBeenCalled();

    await userEvent.click(decline);
    await waitForTheFraudConfirmationToClose();
  }
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
  it('reserves no information line', reservesNoInformationLine);
  it('guards every fraud entry point behind one confirmation', guardsEveryFraudEntryPoint);
  it('focuses the declining choice on every opening', focusesTheDecliningChoiceOnEveryOpening);
  it('reports the confirmation for each transition', reportsTheConfirmationForEachTransition);
  it(
    'refuses a confirmation after the eighth key steps away',
    refusesAConfirmationAfterTheEighthKeySteps,
  );
  it(
    'refuses a confirmation across a read that replaces the record',
    refusesAConfirmationAcrossAReadThatReplacesTheRecord,
  );
}

describe('pending-authorization detail screen', authDetailCases);
