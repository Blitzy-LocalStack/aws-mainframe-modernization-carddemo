/**
 * @file Proves which side owns a report's range: the service for the two presets, the operator for custom.
 *
 * Purpose
 * -------
 * `ReportExecutionService.resolveRange` dispatches a monthly or yearly report name to
 * `resolveMonthlyRange()` or `resolveYearlyRange()`, and both derive their pair from
 * `LocalDate.now(clock)` without reading the submitted request -- while `resolveCustomRange` reads both
 * submitted bounds. This screen used to compute the two preset ranges from its own paint instant and
 * transmit them, so a preset run was described by one range and bounded by another whenever the two
 * clocks disagreed. These two cases pin the contract that replaced it: a preset submission names neither
 * bound, and a caller-supplied submission names both, exactly as keyed.
 *
 * Assumptions: the property is asserted on the request the screen HANDS to the client, which is where the
 * omission either happens or does not. `ui/src/api/reporting.test.ts` asserts the other half over the
 * dispatched HTTP body -- that the client invents no bound of its own on the way out -- so the two files
 * together cover the screen's decision and the transport's fidelity to it without either duplicating the
 * other.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record: `ui/eslint.config.js` requires a documentation block on a
 * function expression in any position, and Prettier moves a block comment that follows an argument comma
 * onto the preceding literal, which detaches it from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type * as ReportingModule from '../../api/reporting';
import { submitTransactionReport } from '../../api/reporting';
import type { ReportRequest, ReportSubmissionOutcome } from '../../api/reporting';
import { REPORTS_CAPTIONS, REPORTS_KEY_LABELS, REPORT_TYPE_PROMPTS } from '../../messages/messages';
import { recordServerDate, resetServerClock } from '../../api/serverClock';
import { ReportsScreen } from './index';

/**
 * Builds the mocked surface of the reporting transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 *
 * Assumptions: only the submission operation is stubbed, because it is the only one this screen calls --
 * the document is assembled asynchronously and this screen never reads or renders it.
 * @returns {Promise<Record<string, unknown>>} The real module with the submission replaced by a fresh spy.
 */
async function mockReportingTransportModule(): Promise<Record<string, unknown>> {
  /*
   * WHY : Refactoring Rationale: the substitution is PARTIAL rather than a single-key object. The screen
   *       imports `newSubmissionKey`, `readReportExecution` and `collectReportArtifact` from this module
   *       besides the submission, and an export the factory does not name is absent rather than real --
   *       so a one-key factory made the screen throw on first render instead of refusing the submission
   *       these cases are about.
   */
  const actual = await vi.importActual<typeof ReportingModule>('../../api/reporting');
  return {
    ...actual,
    submitTransactionReport: vi.fn(),
    /*
     * WHY : Assumptions: the two follow-up operations are spied even though no case here asserts on them,
     *       because the screen starts following a run as soon as one is started. Left real they reach
     *       `getApiClient`, which refuses without a runtime configuration and turns a passing case into an
     *       unhandled rejection from a `useEffect`. Only the non-transport exports -- the key minter and
     *       the contract-operation table -- are the real ones.
     * WHY : Assumptions: both answer a promise that never SETTLES rather than `undefined` or a resolved
     *       status. `undefined` is what a bare `vi.fn()` answers, and the screen chains `.then` on the
     *       result the moment a run starts, so a bare spy raised `Cannot read properties of undefined`
     *       from inside an effect -- an uncaught exception that fails the run while every assertion
     *       passes. A RESOLVED status would settle instead, painting run state outside `act` for cases
     *       that assert nothing about it; a pending promise leaves the follow-up genuinely inert, which
     *       is the state these two cases describe.
     */
    readReportExecution: vi.fn(
      /**
       * Answers the status read with a promise that never settles.
       * @returns {Promise<never>} The shared pending promise.
       */
      () => PENDING_FOREVER,
    ),
    collectReportArtifact: vi.fn(
      /**
       * Answers the document collection with a promise that never settles.
       * @returns {Promise<never>} The shared pending promise.
       */
      () => PENDING_FOREVER,
    ),
  };
}

vi.mock('../../api/reporting', mockReportingTransportModule);

/**
 * A promise that never settles, used for the follow-up reads these cases deliberately leave inert.
 *
 * Assumptions: declared once at module scope so every spy shares one instance, which keeps the
 * factory free of per-call allocation and makes the intent -- nothing ever comes back -- explicit.
 */
const PENDING_FOREVER = new Promise<never>(
  /**
   * Never resolves and never rejects, so a follow-up read stays inert.
   * @returns {void} Nothing; the executor deliberately does nothing.
   */
  () => undefined,
);

/** An HTTP `Date` header value, which is what the production anchor is parsed from. */
const OBSERVED_DATE_HEADER = 'Tue, 18 Jul 2023 10:11:12 GMT';

/**
 * The answer a started run gives, carrying the bounds the SERVICE resolved.
 *
 * Assumptions: the handle's two bounds are a month the screen never computed, which is what makes the
 * fixture describe the delivered contract rather than the withdrawn one: the run reports the range it was
 * actually bounded by, and that is the only range either side can quote afterwards.
 * @returns {ReportSubmissionOutcome} A started outcome with its handle and its sentence.
 */
function startedRun(): ReportSubmissionOutcome {
  return {
    outcome: 'STARTED',
    message: 'Monthly report submitted for printing ...',
    submission: {
      executionName: 'carddemo-transaction-report-2022-07-18T22-10-31Z',
      reportName: 'Monthly',
      shortName: 'MONTHLY',
      longName: 'Monthly Transaction Report',
      startDate: '2022-07-01',
      endDate: '2022-07-31',
      submittedAt: '2022-07-18 22:10:31.000000',
    },
  };
}

/** Discards the transport stub so no case inherits another's recorded calls. */
function resetTransport(): void {
  vi.mocked(submitTransactionReport).mockReset();
  resetServerClock();
}

/**
 * Mounts the screen on its own route inside an in-memory router.
 *
 * Assumptions: no shell is composed, and none is needed. This screen delegates its three bands through
 * `useShellSlot`, which publishes to a module-scoped store whether or not a shell is subscribed, and
 * nothing these cases assert is painted by a band.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderReportsScreen(): void {
  /*
   * WHY : Assumptions: an observed server instant is recorded BEFORE the tree mounts, because the two
   *       preset selectors are withheld while none exists -- the provenance gate
   *       `ui/src/screens/reports/refusalAnnouncement.test.tsx` holds in both directions. Without an
   *       anchor the monthly radio renders disabled, a click on it is a silent no-op, and a case about
   *       WHICH BOUNDS a preset submits would fail on a submission that never happened rather than on the
   *       bounds it carried. The anchor is what the screen paints its business date from, so recording
   *       one is the deployed state: reaching this screen at all takes a signed-on session, and every
   *       response before it carried a `Date` header.
   */
  recordServerDate(OBSERVED_DATE_HEADER);
  render(
    <MemoryRouter initialEntries={['/reports']}>
      <ReportsScreen />
    </MemoryRouter>,
  );
}

/**
 * Reads the one request the screen handed to the client.
 * @returns {ReportRequest} The submitted request.
 * @throws {Error} If no submission was made, which the call-count assertion normally reports first; the
 *   throw exists so the returned value is sound rather than asserted non-null.
 */
function submittedRequest(): ReportRequest {
  const calls = vi.mocked(submitTransactionReport).mock.calls;
  expect(calls).toHaveLength(1);
  const first = calls[0];
  // Assumptions: narrowed explicitly rather than asserted non-null, because tsconfig's strict set
  //   includes noUncheckedIndexedAccess and a non-null assertion is refused by the lint gate.
  if (first === undefined) {
    throw new Error('the screen must have submitted exactly one report request');
  }
  return first[0];
}

/**
 * Selects a report type by the caption the mapset paints beside its selector.
 *
 * Assumptions: the caption is clicked rather than the radio input, because the caption is inside the
 * control's own label -- which is how an operator selects it -- and because a role query carrying a
 * `name` option costs seconds per candidate once the whole antd stylesheet is mounted, a cost the
 * sibling card tests measured and recorded.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @param {string} caption - The verbatim selector caption to choose.
 * @returns {Promise<void>} Resolves once the selection has been applied.
 */
async function chooseReportType(
  user: ReturnType<typeof userEvent.setup>,
  caption: string,
): Promise<void> {
  await user.click(screen.getByText(caption));
}

/**
 * Confirms the turn through the overlay the submit control opens, which is how a run starts.
 *
 * Assumptions: the accept control is matched on the library's default name, because the screen leaves it
 * defaulted deliberately -- labelling it would put a second control of the legend's own name on the
 * document.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @returns {Promise<void>} Resolves once the submission has been issued.
 */
async function submitAndConfirm(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByText(REPORTS_KEY_LABELS.ENTER));
  await user.click(await screen.findByText('OK'));
  await waitFor(
    /**
     * Waits for the single submission to be issued.
     * @returns {void} Nothing; throws until the client has been called once.
     */
    () => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );
}

/**
 * Asserts a monthly submission names NEITHER bound, leaving the range to the service's clock.
 *
 * Purpose: this is the case the withdrawn preset computation cannot pass -- it produced both members from
 * the paint instant, so both would be present and both would then be discarded service-side.
 *
 * Assumptions: absence is asserted by KEY rather than by comparing each member with `undefined`, because
 * `JSON.stringify` drops an explicit `undefined` on the way to the wire and a value comparison therefore
 * cannot distinguish an omitted member from one present and empty.
 * @returns {Promise<void>} Resolves once the submission has been inspected.
 */
async function omitsBothBoundsForTheMonthlyPreset(): Promise<void> {
  vi.mocked(submitTransactionReport).mockResolvedValue(startedRun());
  const user = userEvent.setup();

  renderReportsScreen();
  await chooseReportType(user, REPORT_TYPE_PROMPTS.monthly);
  await submitAndConfirm(user);

  const request = submittedRequest();
  expect(Object.keys(request)).not.toContain('startDate');
  expect(Object.keys(request)).not.toContain('endDate');
  expect(request.monthly).toBe('Y');
  expect(request.confirm).toBe('Y');
}

/**
 * Asserts a caller-supplied range travels as keyed, both bounds present in the interchange form.
 *
 * Purpose: the other side of the same contract, and the reason the omission above is a statement about
 * the preset arms rather than about this screen. `resolveCustomRange` reads both submitted bounds, so
 * withholding them here would leave a custom run with no range at all.
 *
 * Assumptions: the six parts are addressed by their accessible names, which the screen composes from each
 * bound's own caption, and the expected values are the year-first interchange form even though the
 * operator keys month, day and year -- the screen shows the operator's order and transmits the
 * contract's.
 * @returns {Promise<void>} Resolves once the submission has been inspected.
 */
async function sendsBothBoundsForTheCustomType(): Promise<void> {
  vi.mocked(submitTransactionReport).mockResolvedValue(startedRun());
  const user = userEvent.setup();

  renderReportsScreen();
  await chooseReportType(user, REPORT_TYPE_PROMPTS.custom);

  const startCaption = REPORTS_CAPTIONS.startDate.trim();
  const endCaption = REPORTS_CAPTIONS.endDate.trim();
  await user.type(screen.getByLabelText(`${startCaption} month`), '07');
  await user.type(screen.getByLabelText(`${startCaption} day`), '01');
  await user.type(screen.getByLabelText(`${startCaption} year`), '2022');
  await user.type(screen.getByLabelText(`${endCaption} month`), '07');
  await user.type(screen.getByLabelText(`${endCaption} day`), '31');
  await user.type(screen.getByLabelText(`${endCaption} year`), '2022');

  await submitAndConfirm(user);

  const request = submittedRequest();
  expect(request.startDate).toBe('2022-07-01');
  expect(request.endDate).toBe('2022-07-31');
  expect(request.custom).toBe('Y');
}

/**
 * Groups the range-authority cases for this screen.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function reportRangeAuthority(): void {
  afterEach(resetTransport);
  it('omits both bounds for the monthly preset', omitsBothBoundsForTheMonthlyPreset);
  it('sends both bounds for the custom type', sendsBothBoundsForTheCustomType);
}

describe('report range authority', reportRangeAuthority);
