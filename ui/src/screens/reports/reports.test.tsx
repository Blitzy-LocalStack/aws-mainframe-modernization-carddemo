/**
 * @file Component tests for the report screen in `ui/src/screens/reports/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the half of this screen a review found missing: that a submitted run is FOLLOWED. The screen
 * started a run and rendered its name, and the two operations that answer what became of it --
 * `readReportExecution` and `collectReportArtifact` -- were published by `ui/src/api/reporting.ts` and
 * called from nowhere in the application, so a submitted report could be neither tracked nor obtained.
 * These cases assert the run's state is read and re-read while it is going, that each terminal outcome
 * is explained, that a succeeded run's document reaches the browser, that a failure discloses nothing
 * internal, and that the reading stops -- on a settled run, on an abandoned screen, and at its own
 * bound.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, matching
 * `ui/src/screens/transactionAdd/transactionAdd.test.tsx`. These cases are about what the SCREEN does
 * with a status, so the shortest honest seam is the function the screen calls.
 *
 * Assumptions: every turn is driven through the KEYBOARD -- the confirmation character is keyed and
 * Enter is dispatched -- rather than through the confirmation dialogue the submit button opens. Two
 * reasons, and neither is a shortcut. The original terminal was keyboard-only, so this is the reference's
 * own path and `usePfKeys` is what carries it; and the dialogue is a popup with entry motion of its own,
 * which the three cases below that mock the clock would have to advance before they could reach its
 * confirm control. `ui/src/screens/transactionAdd/transactionAdd.test.tsx` covers the clicked
 * confirmation on the screen that has both, so the dialogue path is asserted somewhere.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for the
 * reason the sibling suites record: `ui/eslint.config.js` selects a function expression in every
 * position so an inline callback owes its own JSDoc block, and Prettier moves a block comment that
 * follows an argument comma onto the preceding literal, detaching it from what it documents.
 */

import { ConfigProvider } from 'antd';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { ApiRequestError } from '../../api/client';
import {
  collectReportArtifact,
  readReportExecution,
  submitTransactionReport,
} from '../../api/reporting';
import type * as ReportingModule from '../../api/reporting';
import type { ReportExecutionStatus, ReportSubmissionOutcome } from '../../api/reporting';
import { recordServerDate, resetServerClock } from '../../api/serverClock';
import type { ApiError } from '../../api/types';
import { AppShell, SHELL_CONTENT_ELEMENT_ID } from '../../layout/AppShell';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import {
  MESSAGE_TEMPLATES,
  PERSISTENT_FAILURE_REPORT_IT,
  REPORTS_CAPTIONS,
  REPORT_RUN_MESSAGES,
  REPORT_TYPE_PROMPTS,
  formatMessageTemplate,
} from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';
import { REPORT_TYPE_NAMES, ReportsScreen } from './index';
import type { ExecutionState } from './index';

/**
 * Builds the transport double this screen reaches.
 *
 * Assumptions: the factory is a named declaration passed to `vi.mock`, because the call is hoisted above
 * the imports and a factory held in a `const` would be in its temporal dead zone at registration time.
 *
 * Assumptions: all eight of the module's operations are declared, not the three this screen calls. The
 * mock replaces the whole module, so an operation omitted here is `undefined` rather than absent -- and a
 * later edit that reaches one would fail as a type error at the call site instead of as a confusing
 * runtime message from inside a screen.
 * @returns {Promise<Record<string, unknown>>} The real module with every transport operation replaced
 *   by a fresh spy.
 */
async function mockReportingTransportModule(): Promise<Record<string, unknown>> {
  /*
   * WHY : Refactoring Rationale: the substitution is PARTIAL -- every transport call this file drives is
   *       a spy, but the module's non-transport exports are the real ones, where the factory previously
   *       enumerated only the eight operations. Enumerating is what broke: the screen also imports
   *       `newSubmissionKey` to mint the idempotency key it submits under, and an export a factory omits
   *       does not fall through to the real module, it is simply absent -- so the call threw inside the
   *       submit handler, no run was ever started, and every case that follows a run failed on the state
   *       it was waiting to paint rather than on the omission that prevented it.
   * WHY : Alternatives Considered: adding `newSubmissionKey` to the enumeration as a ninth spy. Rejected
   *       because it leaves the same trap armed for the next export the screen reaches for, and because
   *       a spy returning `undefined` would submit an absent key -- the real function is what makes the
   *       submitted key satisfy the contract these cases assert against.
   */
  const actual = await vi.importActual<typeof ReportingModule>('../../api/reporting');
  return {
    ...actual,
    submitTransactionReport: vi.fn(),
    readReportExecution: vi.fn(),
    collectReportArtifact: vi.fn(),
    listTransactionReportLines: vi.fn(),
    readTransactionReportTotals: vi.fn(),
    generateStatement: vi.fn(),
    listStatementTransactions: vi.fn(),
    collectArtifact: vi.fn(),
  };
}

vi.mock('../../api/reporting', mockReportingTransportModule);

/** An HTTP `Date` header value, which is what the production anchor is parsed from. */
const OBSERVED_DATE_HEADER = 'Tue, 18 Jul 2023 10:11:12 GMT';

/** The run the service reports for the first submission of a case. */
const FIRST_RUN = 'carddemo-transaction-report-2b7f41c0';

/** The run the service reports for a second submission, so a superseded one is distinguishable. */
const SECOND_RUN = 'carddemo-transaction-report-9d0ac35e';

/**
 * The report-type token the run carries.
 *
 * Assumptions: this is the CONTRACT's token and not the screen's caption or the reference's report name.
 * The published enumeration admits `daily`, `monthly`, `yearly` and `custom`, and the collect operation
 * is addressed by one of those -- the caption `Monthly (Current Month)` and the name `Monthly` are the
 * operator's and the reference's vocabularies respectively, and neither is a valid coordinate.
 */
const RUN_REPORT_TYPE = 'monthly';

/** Inclusive lower bound the run covered, as the service recorded it. */
const RUN_START_DATE = '2022-06-01';

/** Inclusive upper bound the run covered, as the service recorded it. */
const RUN_END_DATE = '2022-06-30';

/** Where the service says a succeeded run's document is, which is present only while it is stored. */
const RUN_RESULT_URI =
  '/api/v1/reports/transaction/artifact?reportType=monthly&startDate=2022-06-01&endDate=2022-06-30';

/** When the run started, in the 26-character form the contract publishes. */
const RUN_STARTED_AT = '2022-06-30 23:05:00.000000';

/** When the run stopped, in the same form. */
const RUN_STOPPED_AT = '2022-06-30 23:07:42.000000';

/** The handle the browser is handed the collected bytes through. */
const OBJECT_URL = 'blob:carddemo/report-1';

/** Milliseconds between two automatic status reads, matching the screen's own interval. */
const POLL_INTERVAL_MS = 5000;

/**
 * How many automatic reads the screen makes before it stops and says so.
 *
 * Assumptions: this repeats the screen's own bound rather than importing it, because the constant is
 * private to the module. A case that read it from the module would assert the screen agrees with itself;
 * stating it here asserts it agrees with the documented five minutes.
 */
const AUTOMATIC_READ_BUDGET = 60;

/**
 * Returns the stub standing in for the submission.
 * @returns {MockedFunction<typeof submitTransactionReport>} The mocked transport function.
 */
function submitStub(): MockedFunction<typeof submitTransactionReport> {
  return vi.mocked(submitTransactionReport);
}

/**
 * Returns the stub standing in for the status read.
 * @returns {MockedFunction<typeof readReportExecution>} The mocked transport function.
 */
function statusStub(): MockedFunction<typeof readReportExecution> {
  return vi.mocked(readReportExecution);
}

/**
 * Returns the stub standing in for the document collection.
 * @returns {MockedFunction<typeof collectReportArtifact>} The mocked transport function.
 */
function collectStub(): MockedFunction<typeof collectReportArtifact> {
  return vi.mocked(collectReportArtifact);
}

/**
 * Builds the started outcome one submission answers with.
 * @param {string} executionName - The run the service says it started.
 * @returns {ReportSubmissionOutcome} The started arm, carrying the submission handle.
 */
function startedRun(executionName: string): ReportSubmissionOutcome {
  return {
    outcome: 'STARTED',
    message: 'Monthly report submitted for printing ...',
    submission: {
      executionName,
      reportName: REPORT_TYPE_NAMES.monthly,
      shortName: 'MONTHLY',
      longName: 'Monthly transaction report',
      startDate: RUN_START_DATE,
      endDate: RUN_END_DATE,
      submittedAt: RUN_STARTED_AT,
    },
  };
}

/**
 * Builds one status answer.
 *
 * Assumptions: every member the published shape declares is supplied, including the two a screen never
 * renders, because the type is the contract's own and a partial fixture would describe a body no service
 * sends.
 * @param {ExecutionState} state - The state the service reports.
 * @param {string} executionName - The run the status is for.
 * @param {boolean} stored - Whether the run's document is still in the store.
 * @returns {ReportExecutionStatus} The status body.
 */
function statusOf(
  state: ExecutionState,
  executionName: string,
  stored: boolean,
): ReportExecutionStatus {
  const settled = state !== 'RUNNING' && state !== 'PENDING_REDRIVE';
  return {
    executionName,
    status: state,
    startedAt: RUN_STARTED_AT,
    stoppedAt: settled ? RUN_STOPPED_AT : null,
    reportType: RUN_REPORT_TYPE,
    startDate: RUN_START_DATE,
    endDate: RUN_END_DATE,
    resultUri: stored ? RUN_RESULT_URI : null,
    resultGeneratedAt: stored ? RUN_STOPPED_AT : null,
  };
}

/**
 * Builds a succeeded status for a run this screen did not start, so it carries no coordinates.
 *
 * Assumptions: the location is PRESENT and the three coordinates are absent, which is the combination the
 * nightly schedule produces. It is the one arm that separates the screen's four-condition guard from a
 * two-condition one, because every other absence is caught by the state or the location alone.
 * @returns {ReportExecutionStatus} A succeeded status with a stored document and no coordinates.
 */
function succeededWithoutCoordinates(): ReportExecutionStatus {
  return {
    executionName: FIRST_RUN,
    status: 'SUCCEEDED',
    startedAt: RUN_STARTED_AT,
    stoppedAt: RUN_STOPPED_AT,
    reportType: null,
    startDate: null,
    endDate: null,
    resultUri: RUN_RESULT_URI,
    resultGeneratedAt: RUN_STOPPED_AT,
  };
}

/** The status the collect operation answers when there is no document at those coordinates. */
const DOCUMENT_ABSENT_STATUS = 404;

/**
 * Builds the rejection the shared client raises for one refused collection.
 *
 * Assumptions: every member the published problem shape declares is supplied, including the two a screen
 * never renders, because the type is the contract's own and a partial fixture would describe a body no
 * service sends.
 * @param {number} status - The transport status the service answered with.
 * @returns {ApiRequestError} The rejection the transport module raises.
 */
function collectionRefusal(status: number): ApiRequestError {
  const problem: ApiError = {
    code: 'CARDDEMO-0404',
    secondaryCode: '',
    message: 'No report document exists for those coordinates',
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'CD0000000000000000000009',
    path: '/api/v1/reports/transaction/artifact',
    timestamp: '2022-06-30T23:10:00.000000Z',
    fieldErrors: [],
    abend: null,
  };
  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`);
}

/**
 * Builds a status-read rejection whose condition may pass and whose request is safe to repeat.
 *
 * Purpose: drive the refreshable half of the screen's remedy selection with a REAL normalised failure.
 * The screen narrows the cause through `isRepeatableFailure`, which reads a member off an
 * `ApiRequestError` instance, so a hand-rolled object or a bare `Error` cannot reach that arm at all.
 *
 * Assumptions: the fifth constructor argument is supplied explicitly rather than left to the default.
 * `ui/src/api/client.ts` documents that default as reporting a transient condition and NEVER a safe
 * repeat — because it is reached with no request described — so relying on it would have produced a
 * failure that takes the persistent arm and would have made this case assert the opposite of its name.
 *
 * Assumptions: 503 is the status chosen because it is the canonical shape of the pair this arm needs on a
 * read: the condition may clear on its own and re-sending a `GET` is safe, which is exactly the state in
 * which "Refresh to try again" is a true statement.
 * @returns {ApiRequestError} The rejection the status transport raises for a momentary outage.
 */
function momentaryStatusOutage(): ApiRequestError {
  const status = 503;
  const problem: ApiError = {
    code: 'CARDDEMO-0503',
    secondaryCode: '',
    message: 'The reporting service is not accepting requests',
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'CD0000000000000000000010',
    path: '/api/v1/reports/transaction/executions',
    timestamp: '2022-06-30T23:12:00.000000Z',
    fieldErrors: [],
    abend: null,
  };
  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`, {
    transient: true,
    repeatable: true,
  });
}

/**
 * Builds a collection rejection whose condition may pass and whose request is safe to repeat.
 *
 * Purpose: drive the retryable half of the collection's remedy selection with a REAL normalised failure,
 * for the reason {@link momentaryStatusOutage} records — the screen narrows through `isRepeatableFailure`,
 * which reads a member off an `ApiRequestError` instance.
 *
 * Assumptions: this is a separate builder from {@link momentaryStatusOutage} rather than a parameter on
 * it, because the two describe different endpoints and the path is what a reader checks a fixture
 * against; a shared builder carrying the executions path would describe a failure the collect operation
 * cannot raise.
 * @returns {ApiRequestError} The rejection the collect transport raises for a momentary outage.
 */
function momentaryCollectionOutage(): ApiRequestError {
  const status = 503;
  const problem: ApiError = {
    code: 'CARDDEMO-0503',
    secondaryCode: '',
    message: 'The reporting service is not accepting requests',
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'CD0000000000000000000011',
    path: '/api/v1/reports/transaction/artifact',
    timestamp: '2022-06-30T23:14:00.000000Z',
    fieldErrors: [],
    abend: null,
  };
  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`, {
    transient: true,
    repeatable: true,
  });
}

/** One status read whose answer a case supplies later. */
interface HeldRead {
  /** The promise the screen awaits. */
  readonly promise: Promise<ReportExecutionStatus>;
  /** Answers the read with one status. */
  readonly answer: (status: ReportExecutionStatus) => void;
}

/**
 * Holds one status read open so a case can answer it after a newer run has replaced its own.
 *
 * Assumptions: a named executor rather than an inline one, because the lint configuration requires a
 * JSDoc block on every function expression including a promise executor.
 * @returns {HeldRead} The promise and the function that answers it.
 * @throws {Error} If the promise executor did not run before the constructor returned, which would mean
 *   the runtime does not implement promises the way every case here assumes.
 */
function heldRead(): HeldRead {
  let resolver: ((status: ReportExecutionStatus) => void) | null = null;

  /**
   * Retains the promise's own resolve function.
   * @param {(status: ReportExecutionStatus) => void} resolve - Settles the promise.
   * @returns {void} Nothing; the resolver is retained.
   */
  function captureResolver(resolve: (status: ReportExecutionStatus) => void): void {
    resolver = resolve;
  }

  const promise = new Promise<ReportExecutionStatus>(captureResolver);
  if (resolver === null) {
    throw new Error('the promise executor did not run synchronously');
  }
  return { promise, answer: resolver };
}

/** The href and file name of every document the browser was handed during one case. */
let savedDocuments: { href: string; download: string }[] = [];

/** Every object URL the screen released during one case. */
let releasedHandles: string[] = [];

/**
 * Records the document the screen just handed the browser, instead of navigating to it.
 *
 * Assumptions: the activation is INTERCEPTED rather than allowed through. jsdom implements no navigation,
 * so a real activation would emit an unimplemented-navigation notice and assert nothing; reading the
 * anchor at activation time asserts exactly what a browser would have been asked to save.
 *
 * Assumptions: the anchor is found in the document rather than taken from the call's receiver, so this
 * needs no `this` binding. The screen appends it, activates it and removes it within one call, so it is
 * in the document at precisely the moment this runs.
 * @returns {void} Nothing; the activation is recorded.
 */
function captureDocumentActivation(): void {
  const anchor = window.document.body.querySelector<HTMLAnchorElement>('a[download]');
  if (anchor !== null) {
    savedDocuments.push({ href: anchor.href, download: anchor.download });
  }
}

/**
 * Stands in for the handle the browser would mint for a blob.
 * @returns {string} The one handle every case in this file expects.
 */
function mintObjectUrl(): string {
  return OBJECT_URL;
}

/**
 * Records a released handle.
 * @param {string} handle - The object URL the screen released.
 * @returns {void} Nothing; the release is recorded.
 */
function recordReleasedHandle(handle: string): void {
  releasedHandles.push(handle);
}

/**
 * Installs the three browser facilities jsdom omits that a download needs, and empties the recordings.
 *
 * Assumptions: `URL.createObjectURL` and `URL.revokeObjectURL` are DEFINED here rather than spied on.
 * jsdom implements neither at all -- it ships no blob-URL store -- so there is no property for a spy to
 * wrap, and a case that merely spied would fail with a type error from inside the screen.
 * @returns {void} Nothing; the facilities are installed and the recordings emptied.
 */
function installDownloadHarness(): void {
  savedDocuments = [];
  releasedHandles = [];
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: mintObjectUrl,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: recordReleasedHandle,
  });
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(captureDocumentActivation);
}

/**
 * Removes the two facilities this file defined, so no other file inherits them.
 *
 * Assumptions: the properties are DELETED rather than reset to their previous values, because they had
 * none -- jsdom defines neither -- and assigning `undefined` to a member the DOM library types as a
 * function would need a cast to compile.
 * @returns {void} Nothing; the environment is left as it was found.
 */
function removeDownloadHarness(): void {
  Reflect.deleteProperty(URL, 'createObjectURL');
  Reflect.deleteProperty(URL, 'revokeObjectURL');
}

/** Clears every transport stub so no case inherits another's queued answer. */
function resetTransport(): void {
  submitStub().mockReset();
  statusStub().mockReset();
  collectStub().mockReset();
  installDownloadHarness();
  /*
   * WHY : Assumptions: an observed server instant is recorded for every case, because the two preset
   *       selectors are WITHHELD while none exists -- the provenance gate
   *       `ui/src/screens/reports/refusalAnnouncement.test.tsx` holds in both directions. Every case in
   *       this file submits the monthly preset, so without an anchor the radio renders disabled, the
   *       click is a silent no-op, and each lifecycle assertion would fail waiting for a run that was
   *       never started -- naming the state it wanted rather than the disabled control that prevented it.
   *       Recording one is the deployed state: reaching this screen takes a signed-on session, and every
   *       response before it carried a `Date` header.
   */
  recordServerDate(OBSERVED_DATE_HEADER);
}

/** Restores the environment and every spy this file installed. */
function restoreEnvironment(): void {
  removeDownloadHarness();
  resetServerClock();
  vi.restoreAllMocks();
}

/**
 * Renders the screen inside the one shell the application mounts, on its own route.
 *
 * Assumptions: the real `AppShell` is mounted rather than stubbed, because two of the properties under
 * assertion are about the boundary between them -- that the run region is painted in the shell's content
 * zone and that the acknowledgement on row 23 is not displaced by it. A stub would assert this screen's
 * intent instead of the delivered rendering.
 * @returns {ReactElement} The tree to render.
 */
function renderReports(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/reports']}>
        <AppShell>
          <Routes>
            <Route path="/reports" element={<ReportsScreen />} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Returns the shell's content zone, which every query about the run region is scoped to.
 *
 * Assumptions: scoping is what makes those queries assert the run region is rendered as CHILD CONTENT of
 * the mounted shell rather than merely somewhere in the document. That is the composition contract this
 * screen keeps by publishing through `useShellSlot`, and a query at document scope would be satisfied by
 * a screen that had grown chrome of its own.
 * @returns {HTMLElement} The shell's content element.
 * @throws {Error} If the shell rendered no content zone, which is a composition failure rather than a
 *   missing control and would otherwise report as every query in the file failing at once.
 */
function shellBody(): HTMLElement {
  const content = window.document.getElementById(SHELL_CONTENT_ELEMENT_ID);
  if (content === null) {
    throw new Error('the shell rendered no content zone');
  }
  return content;
}

/**
 * Reads the sentence the shell's message band is currently painting.
 * @returns {string} The band's text, empty when it is painting nothing.
 */
function bandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/** The acknowledgement the band carries once a monthly run has started. */
const MONTHLY_ACKNOWLEDGEMENT = formatMessageTemplate(
  MESSAGE_TEMPLATES.REPORT_SUBMITTED_FOR_PRINTING,
  { 'WS-REPORT-NAME': REPORT_TYPE_NAMES.monthly },
);

/**
 * Keys and submits one monthly report, the way an operator at the terminal would.
 *
 * Assumptions: the monthly type needs no date keying at all -- the service resolves a preset's period
 * from its own clock and the screen submits neither bound -- which is why every case here submits a
 * monthly report. The custom range's own edits are a separate concern with their own reference sentences,
 * and putting six date parts into each of these cases would make a lifecycle assertion depend on a
 * calendar rule.
 * @returns {void} Nothing; the turn has been dispatched and its answer is in flight.
 */
function submitMonthlyReport(): void {
  fireEvent.click(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.monthly }));
  // WHY : Assumptions: the preset radio above is only selectable once a server instant has
  //       been observed, which `resetTransport` anchors -- so a case that skipped that
  //       anchoring would click a disabled control and assert against a turn that never
  //       started, which reads as a transport failure rather than a missing precondition.

  fireEvent.change(screen.getByLabelText(REPORTS_CAPTIONS.confirmation.trim()), {
    target: { value: 'Y' },
  });
  fireEvent.keyDown(window.document, { key: 'Enter' });
}

/**
 * Renders the screen, submits a monthly report and waits for the run's first state to be painted.
 * @param {string} statusLabel - The operator-facing label the first status read resolves to.
 * @returns {Promise<void>} Resolves once the label is on the screen.
 */
async function submitAndAwait(statusLabel: string): Promise<void> {
  render(renderReports());
  submitMonthlyReport();
  await screen.findByText(statusLabel);
}

/**
 * Returns the run-region control carrying one label, or `null` when it is not mounted.
 *
 * ⚠️ Assumptions: the control is found by its LABEL and then climbed to, rather than by its role and
 * accessible name. Measured reason: Ant Design keeps a `Button`'s loading indicator in the tree through
 * its leave animation, and that indicator carries `aria-label="loading"`, so the computed accessible name
 * is `loading Refresh status` for as long as the animation lasts. A role-and-name query is therefore
 * intermittently unsatisfiable for a control that is present, enabled and correctly labelled -- which
 * fails as though the control were missing and names neither the animation nor the indicator.
 * @param {string} label - The authored label the control carries.
 * @returns {HTMLElement | null} The enclosing button, or `null` when nothing carries that label.
 */
function runControl(label: string): HTMLElement | null {
  const labelled = within(shellBody()).queryByText(label);
  return labelled === null ? null : labelled.closest('button');
}

/**
 * Returns the control that reads the run's status on demand.
 * @returns {HTMLElement} The refresh control.
 * @throws {Error} If no refresh control is mounted. The control is unconditional once a run is being
 *   followed, so its absence is a failure of the region rather than a state a case asserts.
 */
function refreshControl(): HTMLElement {
  const control = runControl(REPORT_RUN_MESSAGES.REFRESH_CONTROL);
  if (control === null) {
    throw new Error('the run region offered no refresh control');
  }
  return control;
}

/**
 * Returns the control that collects the run's document, or `null` when none is offered.
 * @returns {HTMLElement | null} The download control, or `null` when it is not mounted.
 */
function downloadControl(): HTMLElement | null {
  return runControl(REPORT_RUN_MESSAGES.DOWNLOAD_CONTROL);
}

/**
 * Builds a thunk that advances the mocked clock inside an `act` scope.
 *
 * Assumptions: `advanceTimersByTimeAsync` rather than the synchronous form, because each automatic read
 * is a promise whose settlement schedules the next timer. The synchronous form runs every timer without
 * yielding to the microtask queue between them, so the second read would never be armed.
 * @param {number} milliseconds - How far to advance.
 * @returns {() => Promise<void>} A thunk that runs every timer due in that span.
 */
function advanceInside(milliseconds: number): () => Promise<void> {
  /**
   * Advances the mocked clock and lets everything it triggered settle.
   * @returns {Promise<void>} Resolves once the timers and their continuations have run.
   */
  return async function advanceTheClock(): Promise<void> {
    await vi.advanceTimersByTimeAsync(milliseconds);
  };
}

beforeEach(resetTransport);
afterEach(restoreEnvironment);

/**
 * Registers the cases covering the run this screen follows.
 *
 * Assumptions: a NAMED registrar rather than an inline callback, matching the sibling suites, because the
 * lint configuration requires a JSDoc block on every function expression -- including the one a test
 * registrar receives.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function reportRunCases(): void {
  /**
   * A started run is read at once, and its state is painted beside its reference.
   *
   * ⚠️ Assumptions: the reference is asserted as well as the state, because the reference is what an
   * operator quotes and is the value the status operation is addressed by. This is also the whole of what
   * the screen used to render, so a case that asserted only the new state could pass on a screen that had
   * dropped it.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function readsAStartedRunAtOnce(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('RUNNING', FIRST_RUN, false));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.RUNNING);

    expect(
      statusStub(),
      'the run must be read by the handle the submission answered with',
    ).toHaveBeenCalledWith(FIRST_RUN);
    expect(
      within(shellBody()).getByText(FIRST_RUN),
      'the run reference must stay on the screen beside its state',
    ).toBeInTheDocument();
    expect(
      downloadControl(),
      'a running report has produced no document, so none may be offered',
    ).toBeNull();
  }

  /**
   * The run's state is painted in the body and never on row 23.
   *
   * ⚠️ Assumptions: this is the property the screen documents and the one a later edit is most likely to
   * break, because row 23 is where every other sentence on this screen goes. The band carries this
   * program's own nineteen reference sentences; the lifecycle text is authored, so painting it there would
   * put a sentence the baseline never wrote onto a parity surface -- and would displace the
   * acknowledgement naming the run the operator had just started.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function keepsTheAcknowledgementOnTheBand(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('FAILED', FIRST_RUN, false));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.FAILED);

    expect(bandText(), 'the band must still carry the reference acknowledgement').toContain(
      MONTHLY_ACKNOWLEDGEMENT,
    );
    expect(bandText(), 'no authored lifecycle sentence may reach row 23').not.toContain(
      REPORT_RUN_MESSAGES.FAILED_DETAIL,
    );
    expect(
      within(shellBody()).getByText(REPORT_RUN_MESSAGES.FAILED_DETAIL),
      'the failure must be explained in the body instead',
    ).toBeInTheDocument();
  }

  /**
   * Builds the case asserting one terminal failure is named and explained on its own terms.
   * @param {'FAILED' | 'TIMED_OUT' | 'ABORTED'} state - The terminal state to report.
   * @param {string} detail - The sentence that state must be explained with.
   * @returns {() => Promise<void>} The case body.
   */
  function explainsTerminalFailure(
    state: 'FAILED' | 'TIMED_OUT' | 'ABORTED',
    detail: string,
  ): () => Promise<void> {
    /**
     * Submits a run, reports the terminal state and asserts what the operator is told.
     * @returns {Promise<void>} Resolves once the assertions have run.
     */
    return async function assertTerminalFailure(): Promise<void> {
      submitStub().mockResolvedValue(startedRun(FIRST_RUN));
      statusStub().mockResolvedValue(statusOf(state, FIRST_RUN, false));

      await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS[state]);

      expect(
        within(shellBody()).getByText(detail),
        'each terminal failure must carry the sentence naming what to do next',
      ).toBeInTheDocument();
      expect(
        downloadControl(),
        'a run that produced no document must offer no download',
      ).toBeNull();
    };
  }

  /**
   * The refresh control reads the status again without waiting for the interval.
   *
   * Assumptions: the second answer differs from the first, so the assertion is that the SCREEN changed
   * rather than that a call was made. A case that asserted only the call count would pass on a screen that
   * issued the read and discarded its answer.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function readsTheStatusOnDemand(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub()
      .mockResolvedValueOnce(statusOf('RUNNING', FIRST_RUN, false))
      .mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, true));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.RUNNING);
    fireEvent.click(refreshControl());

    await screen.findByText(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);
    expect(statusStub(), 'the control must issue a further read').toHaveBeenCalledTimes(2);
    expect(
      downloadControl(),
      'a succeeded run with a stored document must offer it',
    ).not.toBeNull();
  }

  /**
   * A succeeded run whose document is gone says so and offers nothing.
   *
   * ⚠️ Assumptions: this state is reachable and is not a defect. The contract publishes no location once a
   * lifecycle rule has expired what a run wrote, so a run that completed days ago reports success with
   * nothing to collect -- and a download control beside it would fail every time it was pressed.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function withholdsAnExpiredDocument(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, false));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);

    expect(
      within(shellBody()).getByText(REPORT_RUN_MESSAGES.DOCUMENT_UNAVAILABLE),
      'the operator must be told the document is not there',
    ).toBeInTheDocument();
    expect(downloadControl(), 'nothing stored means nothing to offer').toBeNull();
  }

  /**
   * A succeeded run carrying no coordinates offers nothing either.
   *
   * ⚠️ Assumptions: this is the arm that separates the screen's four-condition guard from a two-condition
   * one. A run started by the nightly schedule reports a stored document and no report type or bounds, and
   * the collect operation is addressed by exactly those three values -- so a screen that checked only the
   * state and the location would compose a request from three absent coordinates.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function withholdsADocumentWithoutCoordinates(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(succeededWithoutCoordinates());

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);

    expect(
      within(shellBody()).getByText(REPORT_RUN_MESSAGES.DOCUMENT_UNAVAILABLE),
      'a run whose coordinates are unknown cannot be collected, and the operator must be told',
    ).toBeInTheDocument();
    expect(downloadControl(), 'no coordinates means no collectable document').toBeNull();
    expect(collectStub(), 'no collection may be attempted at all').not.toHaveBeenCalled();
  }

  /**
   * A succeeded run's document is collected by the coordinates the STATUS reports and handed to the browser.
   *
   * ⚠️ Assumptions: the coordinates are asserted to be the status's, which is the whole point of the
   * assertion. The submission this screen holds also carries a range, and the two agree for every run this
   * screen starts -- so a screen composing the request from the submission would pass a weaker version of
   * this case while being wrong for a run whose recorded range differs from what was asked for.
   *
   * Assumptions: the released handle is asserted as well as the saved file, because the release is
   * deferred by a task and a screen that never released would leak a reference to the document for as long
   * as the page lived.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function handsTheDocumentToTheBrowser(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, true));
    collectStub().mockResolvedValue(new Blob(['report bytes']));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);
    const control = downloadControl();
    expect(control, 'a stored document must be offered').not.toBeNull();
    fireEvent.click(control as HTMLElement);

    await waitFor(
      /**
       * Waits until the browser has been handed one document.
       * @returns {void} Nothing; the wait ends when the recording is populated.
       */
      function aDocumentHasBeenSaved(): void {
        expect(savedDocuments, 'exactly one document must be saved').toHaveLength(1);
      },
    );

    expect(
      collectStub(),
      'the collection must be addressed by the coordinates the status reported',
    ).toHaveBeenCalledWith(RUN_REPORT_TYPE, RUN_START_DATE, RUN_END_DATE);
    expect(savedDocuments[0]?.href, 'the saved document must be the collected bytes').toBe(
      OBJECT_URL,
    );
    expect(
      savedDocuments[0]?.download,
      'the file name must carry the type and both bounds so two runs do not collide',
    ).toBe(`transaction-report-${RUN_REPORT_TYPE}-${RUN_START_DATE}-${RUN_END_DATE}.txt`);

    await waitFor(
      /**
       * Waits until the handle has been released.
       * @returns {void} Nothing; the wait ends when the recording is populated.
       */
      function theHandleHasBeenReleased(): void {
        expect(releasedHandles, 'the object URL must be released').toEqual([OBJECT_URL]);
      },
    );
  }

  /**
   * A status read that does not answer says so, discloses nothing, and names the remedy that exists.
   *
   * ⚠️ Assumptions: the assertion is that the internal cause is ABSENT from the document, not merely that
   * a sentence is present. The cause is an HTTP status or a service path; an operator can act on neither,
   * and rendering either would put deployment detail into every screenshot of this screen.
   *
   * ⚠️ Refactoring Rationale: this case now drives BOTH remedies, where it drove one. The screen used to
   * answer every unreadable status with `STATUS_READ_FAILED`, which ends "Refresh to try again" — a
   * remedy that is honest for a gateway failure and misleading for one a refresh cannot clear, because it
   * sends the operator round a loop the screen already knows is closed. The screen now selects on
   * `repeatable`, so the case asserts the selection rather than the sentence: an unclassifiable cause
   * takes the persistent sentence and a repeatable transport failure takes the refreshable one.
   *
   * Assumptions: the unclassifiable arm is a plain `Error`, which is the cause a bug inside the
   * settlement would produce, and `isRepeatableFailure` answers false for anything it cannot narrow.
   * That default is asserted deliberately: an unclassifiable failure is not evidence that retrying helps.
   *
   * Assumptions: the Refresh control is asserted present in BOTH arms. It is the only access to a run's
   * state — the execution name is the sole key the status endpoint accepts and it is shown nowhere else —
   * and the run continues on the service whatever this read did. What the selection changes is what the
   * operator is told to do, not what they are permitted to do.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function reportsAnUnreadableStatusSafely(): Promise<void> {
    const internal = 'ECONNREFUSED reporting-service.internal:8080';
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockRejectedValue(new Error(internal));

    render(renderReports());
    submitMonthlyReport();

    await screen.findByText(PERSISTENT_FAILURE_REPORT_IT);
    expect(
      window.document.body.textContent ?? '',
      'nothing internal may reach the page',
    ).not.toContain(internal);
    expect(
      screen.queryByText(REPORT_RUN_MESSAGES.STATUS_READ_FAILED),
      'a cause no refresh can clear must not offer a refresh as the remedy',
    ).toBeNull();
    expect(refreshControl(), 'the one action available must remain offered').toBeInTheDocument();
  }

  /**
   * A status read refused by a condition that may pass keeps the refreshable remedy.
   *
   * Assumptions: the failure is built as a real normalised transport failure with `repeatable` set,
   * because that member is what the screen selects on and a hand-rolled object would not narrow through
   * `isRepeatableFailure`. A 503 on a `GET` is the canonical shape: the condition may clear on its own
   * and re-sending the request is safe, which is exactly when "Refresh to try again" is true.
   *
   * ⚠️ Assumptions: the persistent sentence is asserted ABSENT here, and the refreshable one absent in the
   * sibling case above. Asserting only presence in each would pass against a screen that rendered both.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function keepsTheRefreshRemedyForAConditionThatMayPass(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockRejectedValue(momentaryStatusOutage());

    render(renderReports());
    submitMonthlyReport();

    await screen.findByText(REPORT_RUN_MESSAGES.STATUS_READ_FAILED);
    expect(
      screen.queryByText(PERSISTENT_FAILURE_REPORT_IT),
      'a condition that may pass must not be reported as one to escalate',
    ).toBeNull();
    expect(refreshControl(), 'the one action available must remain offered').toBeInTheDocument();
  }

  /**
   * A collection that does not answer says so, discloses nothing, and names the remedy that exists.
   *
   * ⚠️ Refactoring Rationale: this case now drives the remedy SELECTION, where it drove one sentence for
   * every cause. `DOCUMENT_COLLECTION_FAILED` ends "Refresh the status to retry", and the cause this case
   * has always used is the one that makes that misleading: an `AccessDenied` refusal is a permission the
   * operator does not hold, which no number of refreshes clears. The screen now selects on `repeatable`,
   * exactly as it does for an unreadable status, so the two target-side failure surfaces on this screen
   * cannot disagree about what an unrecoverable failure is called.
   *
   * Assumptions: the cause stays a plain `Error`, which is what a browser-side or programming failure
   * produces, and `isRepeatableFailure` answers false for anything it cannot narrow — so this arm is also
   * the assertion that an unclassifiable failure defaults to the escalation sentence rather than to a
   * retry.
   *
   * ⚠️ Assumptions: the internal cause is asserted ABSENT from the document, not merely that a sentence is
   * present. `AccessDenied` names a bucket and a deployment, and neither belongs in a screenshot.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function reportsAFailedCollectionSafely(): Promise<void> {
    const internal = 'AccessDenied: s3://carddemo-datasets-dev/reports';
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, true));
    collectStub().mockRejectedValue(new Error(internal));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);
    fireEvent.click(downloadControl() as HTMLElement);

    await screen.findByText(PERSISTENT_FAILURE_REPORT_IT);
    expect(
      window.document.body.textContent ?? '',
      'nothing internal may reach the page',
    ).not.toContain(internal);
    expect(
      screen.queryByText(REPORT_RUN_MESSAGES.DOCUMENT_COLLECTION_FAILED),
      'a refusal no refresh can clear must not offer a refresh as the remedy',
    ).toBeNull();
    expect(savedDocuments, 'a failed collection must save nothing').toHaveLength(0);
  }

  /**
   * A collection refused by a condition that may pass keeps the retry remedy.
   *
   * Assumptions: the failure is a real normalised transport failure carrying `repeatable`, for the
   * reason {@link momentaryStatusOutage} records — the screen narrows through `isRepeatableFailure`, which
   * reads a member off an `ApiRequestError` instance, so nothing else reaches this arm.
   *
   * ⚠️ Assumptions: the persistent sentence is asserted absent here and the retry sentence absent in the
   * case above. Asserting only presence in each would pass against a screen that rendered both.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function keepsTheRetryRemedyForACollectionThatMaySucceed(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, true));
    collectStub().mockRejectedValue(momentaryCollectionOutage());

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);
    fireEvent.click(downloadControl() as HTMLElement);

    await screen.findByText(REPORT_RUN_MESSAGES.DOCUMENT_COLLECTION_FAILED);
    expect(
      screen.queryByText(PERSISTENT_FAILURE_REPORT_IT),
      'a condition that may pass must not be reported as one to escalate',
    ).toBeNull();
    expect(savedDocuments, 'a failed collection must save nothing').toHaveLength(0);
  }

  /**
   * A collection the service answers not-found is reported as an absent document, not a failed request.
   *
   * ⚠️ Assumptions: the two sentences are distinguished because the operator's next action differs, and
   * only one of them is true for a not-found answer. `collectReportArtifact` documents 404 as the answer
   * both for a run that never happened and for one whose document a lifecycle rule has expired, so
   * neither retrying the collection nor refreshing the status recovers it -- the report has to be
   * submitted again, which is what the absent-document sentence says and the retry sentence does not.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function reportsAnAbsentDocumentAsAbsent(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, true));
    collectStub().mockRejectedValue(collectionRefusal(DOCUMENT_ABSENT_STATUS));

    await submitAndAwait(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);
    fireEvent.click(downloadControl() as HTMLElement);

    await screen.findByText(REPORT_RUN_MESSAGES.DOCUMENT_UNAVAILABLE);
    expect(
      screen.queryByText(REPORT_RUN_MESSAGES.DOCUMENT_COLLECTION_FAILED),
      'a document that is not there must not be reported as a request to retry',
    ).toBeNull();
    expect(savedDocuments, 'nothing may be saved').toHaveLength(0);
  }

  /**
   * A collection answering after a newer run has replaced it saves nothing.
   *
   * ⚠️ Assumptions: this is the one settlement a mount check cannot guard, which is why the screen guards
   * it by the RUN. The collection is started by a press and has no teardown of its own, so a screen
   * checking only that it is still mounted would write the first run's document to the operator's file
   * system while a different run is on display -- a file that looks like the run they are looking at and
   * is not.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function dropsASupersededCollection(): Promise<void> {
    let handOverTheBytes: ((bytes: Blob) => void) | null = null;

    /**
     * Retains the collection promise's own resolve function.
     * @param {(bytes: Blob) => void} resolve - Settles the collection.
     * @returns {void} Nothing; the resolver is retained.
     */
    function captureCollectionResolver(resolve: (bytes: Blob) => void): void {
      handOverTheBytes = resolve;
    }

    const heldCollection = new Promise<Blob>(captureCollectionResolver);
    if (handOverTheBytes === null) {
      throw new Error('the promise executor did not run synchronously');
    }
    const settleCollection: (bytes: Blob) => void = handOverTheBytes;

    submitStub()
      .mockResolvedValueOnce(startedRun(FIRST_RUN))
      .mockResolvedValue(startedRun(SECOND_RUN));
    statusStub()
      .mockResolvedValueOnce(statusOf('SUCCEEDED', FIRST_RUN, true))
      .mockResolvedValue(statusOf('SUCCEEDED', SECOND_RUN, true));
    collectStub().mockReturnValue(heldCollection);

    render(renderReports());
    submitMonthlyReport();
    await screen.findByText(FIRST_RUN);
    fireEvent.click(downloadControl() as HTMLElement);

    submitMonthlyReport();
    await screen.findByText(SECOND_RUN);

    settleCollection(new Blob(['report bytes']));
    await act(
      /**
       * Lets the superseded collection settle, so a screen that acted on it would have done so by now.
       * @returns {Promise<void>} Resolves once the held promise's continuations have run.
       */
      async function letTheSupersededCollectionSettle(): Promise<void> {
        await heldCollection;
      },
    );

    expect(
      savedDocuments,
      'a document collected for a superseded run must not be saved',
    ).toHaveLength(0);
    expect(
      screen.getByText(SECOND_RUN),
      'the newer run must remain the one being reported',
    ).toBeInTheDocument();
  }

  /**
   * A run still going is read again, and a run that has settled is not.
   *
   * ⚠️ Assumptions: the second half is the load-bearing half. A screen that kept reading a settled run
   * would issue a request every five seconds for as long as the tab stayed open, against a service whose
   * other reads are all operator-driven -- so the assertion that the count STOPS rising is the one that
   * would catch it.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function keepsReadingWhileTheRunIsGoing(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub()
      .mockResolvedValueOnce(statusOf('PENDING_REDRIVE', FIRST_RUN, false))
      .mockResolvedValue(statusOf('SUCCEEDED', FIRST_RUN, true));

    vi.useFakeTimers();
    try {
      render(renderReports());
      submitMonthlyReport();
      await act(advanceInside(0));

      expect(
        screen.getByText(REPORT_RUN_MESSAGES.STATUS_LABELS.PENDING_REDRIVE),
        'a redriven run is still going, so it must be reported as restarting',
      ).toBeInTheDocument();
      expect(statusStub(), 'the first read is immediate').toHaveBeenCalledTimes(1);

      await act(advanceInside(POLL_INTERVAL_MS));
      expect(statusStub(), 'a run still going must be read again').toHaveBeenCalledTimes(2);
      expect(
        screen.getByText(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED),
        'the newer state must replace the older one',
      ).toBeInTheDocument();

      await act(advanceInside(POLL_INTERVAL_MS * 3));
      expect(statusStub(), 'a settled run must not be read again').toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  }

  /**
   * Leaving the screen stops the reading.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function stopsReadingWhenTheScreenIsLeft(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('RUNNING', FIRST_RUN, false));

    vi.useFakeTimers();
    try {
      const mounted = render(renderReports());
      submitMonthlyReport();
      await act(advanceInside(0));
      expect(statusStub(), 'the run is being followed').toHaveBeenCalledTimes(1);

      mounted.unmount();
      await act(advanceInside(POLL_INTERVAL_MS * 4));

      expect(statusStub(), 'an unmounted screen must issue no further read').toHaveBeenCalledTimes(
        1,
      );
    } finally {
      vi.useRealTimers();
    }
  }

  /**
   * The automatic reading stops at its own bound, and says that it has.
   *
   * ⚠️ Assumptions: the announcement is asserted, not just the stop. A screen that quietly stopped
   * updating would leave a running state on display indefinitely, and an operator would read it as a run
   * that never finishes -- which is worse than reading nothing at all.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function stopsReadingAtItsBound(): Promise<void> {
    submitStub().mockResolvedValue(startedRun(FIRST_RUN));
    statusStub().mockResolvedValue(statusOf('RUNNING', FIRST_RUN, false));

    vi.useFakeTimers();
    try {
      render(renderReports());
      submitMonthlyReport();
      /*
       * WHY : ⚠️ Assumptions: the clock is flushed at zero BEFORE the span is advanced, and the two
       *       cannot be combined. The first read is issued without any timer, so at the moment a
       *       combined advance began there would be nothing scheduled to run -- the timer arming the
       *       second read is armed by that read's own settlement, which lands after the advance has
       *       already moved the clock past when it would have been due. The whole chain would then sit
       *       unrun and the case would report one read where sixty-one were expected, which is exactly
       *       the failure this note exists to stop a reader re-diagnosing.
       */
      await act(advanceInside(0));
      await act(advanceInside(POLL_INTERVAL_MS * (AUTOMATIC_READ_BUDGET + 2)));

      expect(
        statusStub(),
        'the immediate read plus the budget is the whole of what may be issued',
      ).toHaveBeenCalledTimes(AUTOMATIC_READ_BUDGET + 1);
      expect(
        screen.getByText(REPORT_RUN_MESSAGES.AUTOMATIC_UPDATES_STOPPED),
        'the operator must be told the screen has stopped reading',
      ).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  }

  /**
   * A read answering after a newer run has replaced it changes nothing.
   *
   * ⚠️ Assumptions: this is the case a naive implementation fails, and it fails it silently. The first
   * run's read is held open across a second submission, so its answer arrives when the screen is following
   * a different run -- and a screen without a liveness guard would paint the first run's failure under the
   * second run's reference, reporting a run as failed that is succeeding.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function dropsASupersededRead(): Promise<void> {
    const first = heldRead();
    submitStub()
      .mockResolvedValueOnce(startedRun(FIRST_RUN))
      .mockResolvedValue(startedRun(SECOND_RUN));
    statusStub()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValue(statusOf('SUCCEEDED', SECOND_RUN, true));

    render(renderReports());
    submitMonthlyReport();
    await screen.findByText(FIRST_RUN);

    submitMonthlyReport();
    await screen.findByText(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED);

    first.answer(statusOf('FAILED', FIRST_RUN, false));
    await act(
      /**
       * Lets the superseded answer settle, so a screen that adopted it would have done so by now.
       * @returns {Promise<void>} Resolves on the next macrotask.
       */
      async function letTheSupersededAnswerSettle(): Promise<void> {
        await first.promise;
      },
    );

    expect(
      screen.getByText(SECOND_RUN),
      'the newer run must remain the one being reported',
    ).toBeInTheDocument();
    expect(
      screen.getByText(REPORT_RUN_MESSAGES.STATUS_LABELS.SUCCEEDED),
      'the superseded answer must not replace the newer state',
    ).toBeInTheDocument();
    expect(
      screen.queryByText(REPORT_RUN_MESSAGES.FAILED_DETAIL),
      'the superseded failure must not be explained at all',
    ).toBeNull();
  }

  it(
    'reads a started run at once and paints its state beside its reference',
    readsAStartedRunAtOnce,
  );
  it('leaves the reference acknowledgement on row 23', keepsTheAcknowledgementOnTheBand);
  it('explains a failed run', explainsTerminalFailure('FAILED', REPORT_RUN_MESSAGES.FAILED_DETAIL));
  it(
    'explains a timed-out run',
    explainsTerminalFailure('TIMED_OUT', REPORT_RUN_MESSAGES.TIMED_OUT_DETAIL),
  );
  it(
    'explains a stopped run',
    explainsTerminalFailure('ABORTED', REPORT_RUN_MESSAGES.ABORTED_DETAIL),
  );
  it('reads the status on demand', readsTheStatusOnDemand);
  it('withholds a document that is no longer stored', withholdsAnExpiredDocument);
  it('withholds a document whose coordinates are unknown', withholdsADocumentWithoutCoordinates);
  it('hands a collected document to the browser', handsTheDocumentToTheBrowser);
  it('reports an unreadable status without disclosing why', reportsAnUnreadableStatusSafely);
  it(
    'keeps the refresh remedy for a condition that may pass',
    keepsTheRefreshRemedyForAConditionThatMayPass,
  );
  it('reports a failed collection without disclosing why', reportsAFailedCollectionSafely);
  it(
    'keeps the retry remedy for a collection that may succeed',
    keepsTheRetryRemedyForACollectionThatMaySucceed,
  );
  it(
    'reports an absent document as absent rather than as a retry',
    reportsAnAbsentDocumentAsAbsent,
  );
  it('drops a collection answering after a newer run replaced it', dropsASupersededCollection);
  it(
    'keeps reading while the run is going and stops when it settles',
    keepsReadingWhileTheRunIsGoing,
  );
  it('stops reading when the screen is left', stopsReadingWhenTheScreenIsLeft);
  it('stops reading at its own bound and says so', stopsReadingAtItsBound);
  it('drops a read answering after a newer run replaced it', dropsASupersededRead);
}

describe('the report screen: the run it submits and then follows', reportRunCases);
