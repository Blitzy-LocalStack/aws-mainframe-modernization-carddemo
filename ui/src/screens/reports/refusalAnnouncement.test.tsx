/**
 * @file Proves the report-type selector reports its own refusal programmatically, and that the two
 * clock-derived report types are withheld until a server instant has been observed.
 *
 * Purpose
 * -------
 * `app/cbl/CORPT00C.cbl` L437-L442 answers an unselected report type by painting
 * `Select a report type to print report...` on row 23 and moving `-1` into `MONTHLYL` -- so the
 * reference associates the refusal with the selector, and the mechanism it uses is the cursor. A
 * browser reproduces the cursor move directly, but it also has a channel the terminal did not:
 * assistive software resolves a control's validity and its description from the control, not from where
 * the caret rests. These cases hold that channel populated, and they hold it populated on the ONE turn
 * it exists for -- the refused submit -- because the wiring that carries it is keyed off a field mark
 * and was previously never given one.
 *
 * The second concern is provenance rather than presentation. The monthly and yearly ranges are derived
 * from an instant, and `dayjs(undefined)` does not fail: it silently answers with the BROWSER's clock,
 * which would make a report's range depend on whose machine submitted it. The two cases below assert
 * both halves of the resolution -- the selectors are withheld while no server instant exists, and they
 * become operable once one is observed.
 *
 * Parameters
 * ----------
 * Not applicable. This module declares test cases and takes no inputs of its own.
 *
 * Return values
 * -------------
 * Not applicable. Each case reports through its expectations.
 *
 * Exceptions or errors
 * --------------------
 * None are raised here. The submission transport is substituted and no case lets a request reach it.
 *
 * Assumptions: the server clock is driven through its own published functions rather than by faking
 * `Date`. `ui/src/api/serverClock.ts` holds the anchor at module scope and publishes `recordServerDate`
 * and `resetServerClock` for exactly this reason, so a case can produce the unanchored state
 * deliberately -- a state a deployed build reaches only before its first response and cannot return to.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ReportingModule from '../../api/reporting';

/** Stands in for the one submission this screen issues. */
const submitTransactionReportMock = vi.fn();

vi.mock(
  '../../api/reporting',
  /**
   * Replaces the submission while leaving every other export intact.
   *
   * Assumptions: a partial substitution, so the contract-operation table the module also publishes is
   * the real one. No case here lets a request reach the transport; the spy exists so that a case which
   * reached it accidentally would fail on the call count rather than on a network attempt.
   * @returns {Promise<typeof ReportingModule>} The real module with the submission stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof ReportingModule>('../../api/reporting');
    return { ...actual, submitTransactionReport: submitTransactionReportMock };
  },
);

/*
 * Assumptions: the screen, the shell and the clock are imported DYNAMICALLY, below the factory, for the
 *   reason `signon.test.tsx` records -- a static import is hoisted above a factory that closes over the
 *   module-scope spy and fails on a temporal-dead-zone access before any case runs.
 */
const { default: ReportsScreen } = await import('./index');
const { AppShell } = await import('../../layout/AppShell');
const { MESSAGE_BAND_TEST_ID } = await import('../../layout/MessageBand');
const { fieldErrorId } = await import('../../layout/fieldHelp');
const { recordServerDate, resetServerClock } = await import('../../api/serverClock');

/*
 * Assumptions: the catalog is imported statically because `ui/src/messages/messages.ts` has no imports
 *   at all and so cannot reach the substituted module.
 */
import {
  PROGRAM_MESSAGES,
  REPORT_TYPE_PROMPTS,
  REPORTS_KEY_LABELS,
  REPORTS_TITLE,
} from '../../messages/messages';

/** Sentences this program contributes. */
const REPORT_MESSAGES = PROGRAM_MESSAGES.CORPT00C;

/** The route this screen is mounted at. */
const REPORTS_PATH = '/reports';

/** An HTTP `Date` header value, which is what the production anchor is parsed from. */
const OBSERVED_DATE_HEADER = 'Tue, 18 Jul 2023 10:11:12 GMT';

/**
 * Renders the screen inside the shared frame at its own route.
 *
 * Assumptions: the frame is part of the tree because this screen delegates its message line and its
 * legend to the shell, so both are painted by the shell and not by the screen. A bare render would
 * expose neither, and the refusal these cases read would have nowhere to appear.
 * @returns {void} Completion is the mounted tree.
 */
function renderScreen(): void {
  render(
    <MemoryRouter initialEntries={[REPORTS_PATH]}>
      <AppShell>
        <Routes>
          <Route path={REPORTS_PATH} element={<ReportsScreen />} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Returns the element antd gives the group role, which is the one a description resolves against.
 * @returns {HTMLElement} The report-type group.
 */
function reportTypeGroup(): HTMLElement {
  return screen.getByRole('radiogroup', { name: REPORTS_TITLE });
}

/**
 * Asserts a refused submit marks the group invalid and points its description at the reason.
 *
 * Assumptions: the described element is RESOLVED rather than merely named. An `aria-describedby` naming
 * an identifier that no element carries is silently inert -- assistive software announces nothing --
 * and that is indistinguishable from a correct attribute when only the attribute is asserted. So the
 * case reads the identifier, looks the element up, and asserts on the text it holds.
 * @returns {Promise<void>} Resolves once the refusal has been observed.
 */
async function aRefusedSubmitDescribesTheSelector(): Promise<void> {
  recordServerDate(OBSERVED_DATE_HEADER);
  renderScreen();

  const group = reportTypeGroup();
  expect(group.id).not.toBe('');
  expect(group.getAttribute('aria-invalid')).toBeNull();
  expect(group.getAttribute('aria-describedby')).toBeNull();

  await userEvent.click(
    within(screen.getByRole('navigation', { name: 'Function keys' })).getByRole('button', {
      name: REPORTS_KEY_LABELS.ENTER,
    }),
  );

  const refused = reportTypeGroup();
  expect(refused.getAttribute('aria-invalid')).toBe('true');
  expect(refused.getAttribute('aria-describedby')).toBe(fieldErrorId(refused.id));

  const described = document.getElementById(fieldErrorId(refused.id));
  expect(described).not.toBeNull();
  expect(described).toHaveTextContent(REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT);

  /*
   * WHY : Assumptions: the row-23 line is asserted as well as the description, because they are two
   *       channels and the reference paints one of them. Losing the band would remove the sentence a
   *       sighted operator reads; losing the description would remove the one a screen reader reads.
   *       Both have to hold, and only asserting the pair distinguishes a fix from a relocation.
   */
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(
    REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT,
  );

  expect(submitTransactionReportMock).not.toHaveBeenCalled();
}

/**
 * Asserts the two derived report types are withheld while no server instant has been observed.
 *
 * Assumptions: the CUSTOM type stays operable in the same state, and asserting that is what makes this
 * case about provenance rather than about a screen that simply switches everything off. Its bounds are
 * keyed by the operator, so they need no clock at all -- `app/cbl/CORPT00C.cbl` reads them from the map
 * in the custom arm and derives them itself in the other two.
 * @returns {void} Nothing; the case asserts on the rendered controls.
 */
function theDerivedTypesAreWithheldWithoutAServerInstant(): void {
  resetServerClock();
  renderScreen();

  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.monthly })).toBeDisabled();
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.yearly })).toBeDisabled();
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.custom })).toBeEnabled();
}

/**
 * Asserts the same two types become operable once a server instant has been observed.
 *
 * Assumptions: this is the other half of the case above and not a duplicate of it. A screen that
 * disabled the two presets unconditionally would satisfy the withholding assertion while removing two
 * of the three reports the program offers, and only asserting the released state separates the two.
 * @returns {void} Nothing; the case asserts on the rendered controls.
 */
function theDerivedTypesAreReleasedOnceAnInstantIsObserved(): void {
  recordServerDate(OBSERVED_DATE_HEADER);
  renderScreen();

  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.monthly })).toBeEnabled();
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.yearly })).toBeEnabled();
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.custom })).toBeEnabled();
}

/** Discards the anchor and the spy so one case's state cannot reach the next. */
function resetHarness(): void {
  resetServerClock();
  submitTransactionReportMock.mockReset();
}

/** Registers the report-type selector cases. */
function reportTypeSelectorCases(): void {
  beforeEach(resetHarness);
  afterEach(resetHarness);

  it('describes the selector when a submit is refused', aRefusedSubmitDescribesTheSelector);
  it(
    'withholds the derived report types without a server instant',
    theDerivedTypesAreWithheldWithoutAServerInstant,
  );
  it(
    'releases the derived report types once an instant is observed',
    theDerivedTypesAreReleasedOnceAnInstantIsObserved,
  );
}

describe('the report-type selector', reportTypeSelectorCases);
