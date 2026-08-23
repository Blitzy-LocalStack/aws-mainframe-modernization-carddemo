/**
 * @file Component tests for the transaction-reports screen, `ui/src/screens/reports/index.tsx`.
 *
 * Purpose
 * -------
 * Assert that the migrated screen keeps the observable contract of BMS mapset `CORPT00` (map
 * `CORPT0A`) and program `app/cbl/CORPT00C.cbl`, mounted by `ui/src/router.tsx` at `/reports`. Five
 * properties are held here, each of which the reference fixes and none of which any other suite in
 * this package covers:
 *
 * 1. The keyable surface. Ten controls at the widths `app/cpy-bms/CORPT00.CPY` declares, one initial
 *    cursor for the map's one `IC` attribute, and alphabetic input refused by the six fields carrying
 *    the `NUM` attribute -- six of the seven `NUM` occurrences across the seventeen base mapsets.
 * 2. The report-type choice. Three separate one-character mapset fields become one `Radio.Group`, so
 *    the mutual exclusion the program resolves procedurally becomes structural.
 * 3. The sentences. All nineteen strings the program declares, plus the shared invalid-key sentence
 *    and the confirmation prompt it composes, carried character for character from the catalog.
 * 4. The submission. The transport beneath the submit action changed completely -- a write to the
 *    `JOBS` transient data queue became a call to a managed orchestrator -- and the operator-facing
 *    sentence did not.
 * 5. The keys, the route and the per-field marks. Enter and PF3 and nothing else; PF3 to the main
 *    menu; and a refusal that marks the one control it names.
 *
 * ⚠️ Why this file is the only verification this screen's field surface gets
 * ------------------------------------------------------------------------
 * `tests/README.md` section 1.1 records, verbatim, that "**Online `CO*` CICS programs** cannot run
 * end-to-end without a CICS runtime (absent on the runner); only their extractable field-validation
 * logic is unit-tested." The COBOL suite is the parity oracle for the BATCH chain, and it holds no
 * golden master for `CORPT00C` -- there is nothing to diff this screen against. Every expectation
 * below is therefore anchored to a cited line of the reference source rather than to a recorded run,
 * and the citations are machine-checked where the catalog publishes them (see
 * {@link theProgramSentencesCarryTheirSourceLines}).
 *
 * Parameters, returns and exceptions (module analogues)
 * ----------------------------------------------------
 * Parameters: none. The module takes no arguments; its inputs are the mocked reporting transport
 * declared by {@link mockReportingTransportModule} and the anchored server instant
 * {@link OBSERVED_DATE_HEADER} supplies.
 * Returns: nothing. Its product is the registered cases, which the runner collects.
 * Exceptions: a case throws whenever an expectation fails, which is how the runner reports it. The
 * helpers additionally throw on a broken precondition -- see {@link submittedRequest} and
 * {@link menuRouteForTheReferenceProgram} -- so a missing precondition is named rather than surfacing
 * several lines later as an unsatisfiable query.
 *
 * Documentation obligation
 * ------------------------
 * The single user-specified rule, Rule 1 "Explainability", requires a docstring on every function and
 * module entry point stating purpose, parameters, returns and exceptions, and requires each non-obvious
 * decision to record at least one of `Alternatives Considered:`, `Refactoring Rationale:`,
 * `Assumptions:` or `Trade-offs:`. `tests/README.md` section 12 imposes the identical obligation on
 * "every new test, fixture builder, helper, mock, and runner routine" and calls it "a hard review
 * gate". The two AGREE, so this file extends an established house convention rather than importing a
 * new one, and it follows `docs/CODE_DOCUMENTATION_STANDARD.md` for the label vocabulary.
 *
 * Assumptions: those four labels are written in the PLURAL and that is not a stylistic preference --
 * `config/rule1/rule1_gate.py` declares exactly those four spellings canonical and rejects
 * "parenthesised, emphasis-wrapped, colon-dropped and singular variants", and the gate runs in CI.
 * Every rationale below is therefore in that form, and this paragraph names them in it too so the file
 * does not appear to sanction a spelling it avoids.
 *
 * Assumptions: rationale comments here are `// WHY :` and never a statement-level `// WHAT:`. The same
 * gate permits a `WHAT:` comment only inside a file's leading header block, on the ground that a
 * statement-level one restates the statement it sits above -- which is the first pattern Rule 1
 * forbids. What each expectation DOES is already legible from the expectation; what it is anchored to
 * is not, so that is what the comments carry.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for
 * the two reasons the sibling screen suites record. `ui/eslint.config.js` configures
 * `jsdoc/require-jsdoc` with `publicOnly: false` and the selector `* > ArrowFunctionExpression`, so a
 * function expression in ANY position owes its own block; and Prettier moves a block comment that
 * follows an argument comma onto the preceding expression, detaching it from what it documents.
 *
 * Assumptions: `describe`, `it`, `expect` and `vi` are IMPORTED by name even though
 * `ui/vitest.config.ts` sets `globals: true`. That option installs them at run time only;
 * `ui/tsconfig.json` sets `"types": []`, which suppresses automatic inclusion of every `@types`
 * package including the runner's own globals, so an omitted import fails `tsc --noEmit` on the symbol
 * it omitted. That file's own header states the obligation, and all of this package's sibling suites
 * satisfy it the same way.
 */

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/*
 * WHY : ⚠️ Assumptions: the server clock is reached DIRECTLY, and this is the one import in the file
 *       that is not part of the screen's own public surface. It is required rather than convenient:
 *       `ui/src/screens/reports/index.tsx` withholds the monthly and yearly selectors while
 *       `useServerInstant()` reports nothing, so without an anchor two of the three report types
 *       render disabled, a click on either is a silent no-op, and every case about the selector group
 *       or the initial cursor would fail on a control that was never operable rather than on the
 *       behaviour under test. Measured, not assumed: a probe of this screen with no anchor reports
 *       `monthly=true yearly=true` for the disabled flag and leaves focus on `body`.
 * WHY : Alternatives Considered: (1) seeding a session through `seedSession` in `./setup` and letting
 *       the sign-on response anchor the clock. Rejected on measurement -- the recording transport
 *       answers with no headers at all, so the anchor stays absent and the selectors stay disabled.
 *       (2) Asserting only the custom type, which needs no clock. Rejected because the mutual
 *       exclusion in {@link admitsExactlyOneReportTypeAtATime} cannot be demonstrated with one
 *       selectable option -- proving that choosing a second CLEARS the first needs two.
 * WHY : Assumptions: this is the established mechanism in this tree rather than a new one. All three
 *       sibling reports suites, `ui/src/screens/transactionList/headerInstant.test.tsx` and
 *       `ui/src/api/serverClock.test.ts` anchor the same way, and `serverClock.ts` publishes
 *       `recordServerDate` and `resetServerClock` for exactly this purpose.
 */
import { recordServerDate, resetServerClock } from '../api/serverClock';
import { newSubmissionKey, readReportExecution, submitTransactionReport } from '../api/reporting';
import type * as ReportingModule from '../api/reporting';
import type {
  ReportExecutionStatus,
  ReportRequest,
  ReportSubmissionOutcome,
} from '../api/reporting';
import { ApiRequestError } from '../api/client';
import type { ApiError, FieldError } from '../api/types';
import { AppShell } from '../layout/AppShell';
import {
  MESSAGE_BAND_CONTENT_WIDTH,
  MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH,
} from '../layout/MessageBand';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import type { CicsAid } from '../layout/usePfKeys';
import {
  COMMON_MESSAGES,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  PROGRAM_SOURCE_FILES,
  REPORTS_CAPTIONS,
  REPORTS_KEY_LABELS,
  REPORTS_TITLE,
  REPORT_TYPE_PROMPTS,
  formatMessageTemplate,
} from '../messages/messages';
import { ROUTE_TABLE } from '../router';
import ReportsScreen, {
  CONFIRM_WIDTH,
  DATE_PART_WIDTHS,
  REPORTS_PROGRAM_NAME,
  REPORTS_TRANSACTION_ID,
  REPORT_TYPES,
  REPORT_TYPE_NAMES,
} from '../screens/reports';
import { apiError, expectMaxLength, expectVerbatimMessage, fieldError, pressPfKey } from './setup';

/**
 * Builds the transport double the screen reaches for.
 *
 * ⚠️ Assumptions: the substitution is PARTIAL -- the real module is spread in first and only the four
 * transport operations are replaced. Enumerating instead of spreading is a trap the sibling suite
 * documents having fallen into: the screen also imports `newSubmissionKey` to mint the idempotency
 * key it submits under, an export a factory omits is `undefined` rather than falling through to the
 * real module, so the call threw inside the submit handler and every case failed on the state it was
 * waiting for instead of on the omission that prevented it.
 *
 * Assumptions: a named function DECLARATION, not a factory held in a `const`. Vitest hoists every
 * `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Promise<Record<string, unknown>>} The real module with its four transport operations
 *   replaced by fresh spies.
 */
async function mockReportingTransportModule(): Promise<Record<string, unknown>> {
  const actual = await vi.importActual<typeof ReportingModule>('../api/reporting');
  return {
    ...actual,
    submitTransactionReport: vi.fn(),
    readReportExecution: vi.fn(),
    collectReportArtifact: vi.fn(),
    collectArtifact: vi.fn(),
  };
}

vi.mock('../api/reporting', mockReportingTransportModule);

/** Sentences and labels this program declares, from the single catalog that owns them. */
const REPORT_MESSAGES = PROGRAM_MESSAGES.CORPT00C;

/** The baseline line each of those sentences is emitted from, published beside the text. */
const REPORT_MESSAGE_LINES = PROGRAM_MESSAGE_SOURCES.CORPT00C;

/**
 * An HTTP `Date` header value, which is the form the production anchor is parsed from.
 *
 * Assumptions: an RFC 1123 date string rather than an ISO instant, because `recordServerDate` parses
 * exactly what a response's `Date` header carries. The date itself is arbitrary and no expectation
 * below depends on it -- the two presets resolve their period service-side, so this file never asserts
 * a computed range.
 */
const OBSERVED_DATE_HEADER = 'Tue, 18 Jul 2023 10:11:12 GMT';

/** The run identity the mocked service reports, so a started submission is recognisable. */
const STARTED_RUN_NAME = 'carddemo-transaction-report-4f1c9ae2';

/**
 * The six part values one custom range is keyed from.
 *
 * Assumptions: the members are typed `string` rather than left to literal inference, because three cases
 * spread {@link VALID_RANGE} and replace one part with a deliberately bad value. Under `as const` alone
 * every member would carry its own literal type and the replacement would be rejected by the compiler,
 * which would push each of those cases into restating all six values.
 */
interface CustomRangeEntry {
  /** Start bound's month, two characters. */
  readonly startMonth: string;
  /** Start bound's day, two characters. */
  readonly startDay: string;
  /** Start bound's year, four characters. */
  readonly startYear: string;
  /** End bound's month, two characters. */
  readonly endMonth: string;
  /** End bound's day, two characters. */
  readonly endDay: string;
  /** End bound's year, four characters. */
  readonly endYear: string;
}

/**
 * A calendar range every part of which passes the reference's edits.
 *
 * Assumptions: July 2022 is used because it is the month this system's own reference data is dated to --
 * `app/jcl/INTCALC.jcl` L22 injects the business date as `PARM='2022071800'` -- so a fixture reads as a
 * value from the baseline rather than as today. No expectation depends on the month being July; the
 * cases that assert a transmitted bound compose the expected string from these same members.
 */
const VALID_RANGE: CustomRangeEntry = {
  startMonth: '07',
  startDay: '01',
  startYear: '2022',
  endMonth: '07',
  endDay: '31',
  endYear: '2022',
};

/**
 * One of the fourteen date sentences, with the reference line that emits it.
 *
 * Assumptions: the sentence and its line are both READ FROM THE CATALOG rather than retyped here, so
 * the table cannot drift from the module that owns the text. Retyping was the alternative and it is
 * rejected for a reason that is easy to miss: a paraphrase in the screen and the same paraphrase in
 * the test agree with each other, so the case passes green while the fidelity it exists to protect is
 * already gone. `expectedLine` is the ONE literal, and it is what turns the citation into an
 * assertion.
 */
interface DateSentenceCase {
  /** The sentence, from the catalog. */
  readonly sentence: string;
  /** The baseline lines the catalog publishes for it. */
  readonly sourceLines: readonly number[];
  /** The line this file cites, which the case checks the catalog against. */
  readonly expectedLine: number;
  /** The date part the sentence names, as the reference capitalises it. */
  readonly partWord: string;
}

/**
 * The six blank-part sentences, in the order the reference tests them.
 *
 * Assumptions: a TABLE rather than six near-identical cases, because the six differ only in which bound
 * and which part they name -- and six hand-written near-duplicates is where one drops a character
 * nobody notices. The reference emits them from `app/cbl/CORPT00C.cbl` L261 through L296 in exactly
 * this order, which is start month, day, year then end month, day, year.
 */
const BLANK_PART_SENTENCES: readonly DateSentenceCase[] = [
  {
    sentence: REPORT_MESSAGES.START_DATE_MONTH_CAN_NOT_BE_EMPTY,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_MONTH_CAN_NOT_BE_EMPTY,
    expectedLine: 261,
    partWord: 'Month',
  },
  {
    sentence: REPORT_MESSAGES.START_DATE_DAY_CAN_NOT_BE_EMPTY,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_DAY_CAN_NOT_BE_EMPTY,
    expectedLine: 268,
    partWord: 'Day',
  },
  {
    sentence: REPORT_MESSAGES.START_DATE_YEAR_CAN_NOT_BE_EMPTY,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_YEAR_CAN_NOT_BE_EMPTY,
    expectedLine: 275,
    partWord: 'Year',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_MONTH_CAN_NOT_BE_EMPTY,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_MONTH_CAN_NOT_BE_EMPTY,
    expectedLine: 282,
    partWord: 'Month',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_DAY_CAN_NOT_BE_EMPTY,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_DAY_CAN_NOT_BE_EMPTY,
    expectedLine: 289,
    partWord: 'Day',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_YEAR_CAN_NOT_BE_EMPTY,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_YEAR_CAN_NOT_BE_EMPTY,
    expectedLine: 296,
    partWord: 'Year',
  },
] as const;

/**
 * The six out-of-range sentences, in the order the reference tests them.
 *
 * Assumptions: these are a SEPARATE family from the blank six and are not merged with them, because the
 * reference runs them at a different point -- the emptiness `EVALUATE` closes at L303 and the first
 * range test opens at L329, with the zero-padding normalisation in between -- and because the two
 * families capitalise differently, which is the detail {@link keepsTheTwoCapitalisationsTheProgramUses}
 * exists to pin.
 */
const INVALID_PART_SENTENCES: readonly DateSentenceCase[] = [
  {
    sentence: REPORT_MESSAGES.START_DATE_NOT_A_VALID_MONTH,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_NOT_A_VALID_MONTH,
    expectedLine: 331,
    partWord: 'Month',
  },
  {
    sentence: REPORT_MESSAGES.START_DATE_NOT_A_VALID_DAY,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_NOT_A_VALID_DAY,
    expectedLine: 340,
    partWord: 'Day',
  },
  {
    sentence: REPORT_MESSAGES.START_DATE_NOT_A_VALID_YEAR,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_NOT_A_VALID_YEAR,
    expectedLine: 348,
    partWord: 'Year',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_NOT_A_VALID_MONTH,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_NOT_A_VALID_MONTH,
    expectedLine: 357,
    partWord: 'Month',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_NOT_A_VALID_DAY,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_NOT_A_VALID_DAY,
    expectedLine: 366,
    partWord: 'Day',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_NOT_A_VALID_YEAR,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_NOT_A_VALID_YEAR,
    expectedLine: 374,
    partWord: 'Year',
  },
] as const;

/**
 * The two whole-date sentences the reference's calendar validator produces.
 *
 * ⚠️ Assumptions: these two are the surfaced RESULT of a delegated check and this file asserts nothing
 * about how the check is made. `app/cbl/CORPT00C.cbl` calls `CSUTLDTC` at L392 for the start bound and
 * L412 for the end bound, and in the target that logic is
 * `com.carddemo.common.validation.DateEditValidator` -- so the leap-year, span and severity rules
 * belong to that unit's own tests. What belongs HERE is that the client renders the sentence the check
 * produced, which is why `partWord` is the lowercase `'date'` rather than a part name.
 */
const WHOLE_DATE_SENTENCES: readonly DateSentenceCase[] = [
  {
    sentence: REPORT_MESSAGES.START_DATE_NOT_A_VALID_DATE,
    sourceLines: REPORT_MESSAGE_LINES.START_DATE_NOT_A_VALID_DATE,
    expectedLine: 400,
    partWord: 'date',
  },
  {
    sentence: REPORT_MESSAGES.END_DATE_NOT_A_VALID_DATE,
    sourceLines: REPORT_MESSAGE_LINES.END_DATE_NOT_A_VALID_DATE,
    expectedLine: 420,
    partWord: 'date',
  },
] as const;

/**
 * Anchors an observed server instant, which the two preset selectors are gated on.
 *
 * Assumptions: this runs before every case rather than in the two that strictly need it, because the
 * anchored state is the DEPLOYED state -- reaching this screen at all takes a signed-on session, and
 * `ui/src/api/client.ts` re-anchors from every response including failures, so a case that ran
 * unanchored would be asserting against a window the application only occupies before its first
 * response has been seen.
 * @returns {void} Nothing; the module-scoped anchor is set.
 */
function anchorObservedServerInstant(): void {
  recordServerDate(OBSERVED_DATE_HEADER);
}

/**
 * Discards the anchored instant so no later file inherits it.
 *
 * ⚠️ Assumptions: this is NOT a duplicate of the runner's own `clearMocks` and `restoreMocks`, which
 * `ui/vitest.config.ts` enables. Those reset MOCKS -- a spy's recorded calls and its implementation.
 * The anchor is a module-scoped variable in `ui/src/api/serverClock.ts` and is not a mock, so nothing
 * the runner does reaches it, and a surviving anchor would silently enable the two preset selectors in
 * a file that meant to observe them withheld.
 * @returns {void} Nothing; the anchor is cleared.
 */
function discardObservedServerInstant(): void {
  resetServerClock();
}

/**
 * Renders the screen inside the one shell the application mounts, on its own route.
 *
 * Purpose: the screen composes none of the three shared bands itself -- it publishes its title, its
 * row-23 sentence and its row-24 legend through `useShellSlot` -- so a bare render produces a screen
 * with no message band and no key legend, and every query for either fails against a screen that is in
 * fact correct.
 *
 * Assumptions: a sibling route for the main menu is mounted so the PF3 transfer has somewhere to land.
 * `app/cbl/CORPT00C.cbl` L188 moves `'COMEN01C'` into `CDEMO-TO-PROGRAM` and performs
 * `RETURN-TO-PREV-SCREEN`, which issues `EXEC CICS XCTL`; transformation rule T5 maps that to a client
 * route change, so the destination has to exist for the change to be observable rather than a
 * navigation into an empty document.
 *
 * Alternatives Considered: `renderInAppShell` from `./setup`, which supplies the same providers in one
 * call. Rejected here only because it mounts a SINGLE child route, and two of the cases below need a
 * second route to observe the transfer arriving; the helper's own route-pattern guard would then have
 * nothing to check. The provider composition it performs -- the design-system theme and a memory
 * router around the shell -- is reproduced faithfully below.
 * @returns {ReactElement} The tree to render.
 */
function reportsTree(): ReactElement {
  return (
    <MemoryRouter initialEntries={[reportsRouteForTheReferenceProgram()]}>
      <AppShell>
        <Routes>
          <Route path={reportsRouteForTheReferenceProgram()} element={<ReportsScreen />} />
          <Route path={menuRouteForTheReferenceProgram()} element={<LandedRouteProbe />} />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/** Test identifier of the probe that reports which address the router settled on. */
const LANDED_ROUTE_TEST_ID = 'landed-route';

/**
 * Reports the address the router is currently showing, so a transfer is observable.
 *
 * Assumptions: the pathname is rendered rather than asserted through a spy on `useNavigate`, because
 * what rule T5 promises is that the OPERATOR arrives somewhere -- a spy would confirm the screen asked
 * to navigate while a broken route table left them nowhere.
 * @returns {ReactElement} An element carrying the settled pathname.
 */
function LandedRouteProbe(): ReactElement {
  const { pathname } = useLocation();
  return <span data-testid={LANDED_ROUTE_TEST_ID}>{pathname}</span>;
}

/**
 * Resolves one screen's route from the router's own table, by the program it replaces.
 *
 * ⚠️ Assumptions: the path is LOOKED UP by program name rather than written as a literal, so the route
 * a case navigates to is the one the application actually mounts. `ui/src/router.tsx` publishes
 * `ROUTE_TABLE` as "the reachability graph as data" with one row per reference program, which makes
 * `'CORPT00C'` and `'COMEN01C'` -- both of them names taken straight from the COBOL -- the stable way
 * to name a route. A literal would keep passing after the route moved.
 * @param {string} program - Name of the CICS program the route replaces.
 * @returns {string} The route path.
 * @throws {Error} If the table holds no row for that program, which is a router regression rather
 *   than a failed expectation and would otherwise report as every query in the case failing at once.
 */
function routeForProgram(program: string): string {
  const entry = ROUTE_TABLE.find(
    /**
     * Selects the row naming this program.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One row of the reachability graph.
     * @returns {boolean} `true` when the row replaces this program.
     */
    (candidate: (typeof ROUTE_TABLE)[number]): boolean => candidate.program === program,
  );
  if (entry === undefined) {
    throw new Error(`ui/src/router.tsx publishes no route for the program ${program}`);
  }
  return entry.path;
}

/**
 * The route this screen is mounted at, resolved from the router's table.
 * @returns {string} The reports route.
 * @throws {Error} If the table holds no row for `CORPT00C`.
 */
function reportsRouteForTheReferenceProgram(): string {
  return routeForProgram(REPORTS_PROGRAM_NAME);
}

/**
 * The route the PF3 transfer lands on, resolved from the router's table.
 *
 * Assumptions: `'COMEN01C'` is the program `app/cbl/CORPT00C.cbl` L188 names, so resolving the path
 * from it ties this file's navigation expectation to the reference's own transfer target rather than
 * to a path spelling.
 * @returns {string} The main-menu route.
 * @throws {Error} If the table holds no row for `COMEN01C`.
 */
function menuRouteForTheReferenceProgram(): string {
  return routeForProgram('COMEN01C');
}

/**
 * Mirrors the accessible name the screen composes from a mapset caption.
 *
 * ⚠️ Assumptions: the screen builds each control's accessible name as the bound's caption with its
 * character-cell padding removed, joined to the part name -- `REPORTS_CAPTIONS.endDate` is
 * `'  End Date :'`, whose two leading spaces are the mapset's right-alignment of the shorter caption
 * against the longer one. Querying by that name therefore requires the same removal, and doing it in
 * ONE named place is what keeps the removal from being sprinkled through the cases.
 *
 * ⚠️ Trade-offs: this is the ONLY place in this file where padding is stripped from a catalogued
 * string, and it is deliberately confined to CAPTIONS. The confirmation PREFIX at
 * `app/cbl/CORPT00C.cbl` L466 is never passed through here and never stripped anywhere -- its
 * trailing space is content rather than padding, because the report name is concatenated onto it, and
 * {@link keepsTheTrailingSpaceOnTheConfirmationPrefix} asserts it with that space intact.
 * @param {string} caption - A caption from the message catalog, possibly carrying cell padding.
 * @returns {string} The caption as the screen spells it in an accessible name.
 */
function accessibleCaption(caption: string): string {
  return caption.trim();
}

/**
 * Locates one of the six split date-part controls by the accessible name the screen gives it.
 * @param {string} caption - The bound's caption, from the message catalog.
 * @param {string} part - The part name, one of `month`, `day` or `year`.
 * @returns {HTMLElement} The control.
 * @throws {Error} If no control carries that name, which Testing Library raises.
 */
function datePartControl(caption: string, part: string): HTMLElement {
  return screen.getByLabelText(`${accessibleCaption(caption)} ${part}`);
}

/**
 * Locates the one-character confirmation control.
 * @returns {HTMLElement} The control.
 * @throws {Error} If it is absent, which Testing Library raises.
 */
function confirmationControl(): HTMLElement {
  return screen.getByLabelText(accessibleCaption(REPORTS_CAPTIONS.confirmation));
}

/**
 * Reads the sentence the shell's row-23 band is painting.
 * @returns {string} The band's text, empty when it is painting nothing.
 */
function bandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/**
 * Returns the row-24 legend region the shell renders from this screen's key descriptors.
 * @returns {HTMLElement} The legend's navigation landmark.
 * @throws {Error} If the legend is absent, which Testing Library raises.
 */
function keyLegend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Selects one report type by the caption the mapset paints beside its selector.
 *
 * Assumptions: the CAPTION is clicked rather than the radio input, because the caption sits inside the
 * control's own label, which is how an operator selects it. A role query carrying a `name` option also
 * costs seconds per candidate once the whole design-system stylesheet is mounted, a cost the sibling
 * card suites measured and recorded.
 * @param {ReturnType<typeof userEvent.setup>} operator - The interaction driver for this case.
 * @param {string} caption - The verbatim selector caption to choose.
 * @returns {Promise<void>} Resolves once the selection has been applied.
 */
async function chooseReportType(
  operator: ReturnType<typeof userEvent.setup>,
  caption: string,
): Promise<void> {
  await operator.click(screen.getByText(caption));
}

/**
 * Fills one date part with a value, as a single change rather than a keystroke at a time.
 *
 * ⚠️ Trade-offs: `fireEvent.change` is used here and `userEvent.type` is NOT, and the exchange is
 * deliberate and measured. Typing dispatches the full pointer-and-keyboard sequence per character
 * against a tree with the whole design-system stylesheet mounted, which measured at roughly fifteen to
 * twenty seconds per case across the eleven cases that fill a range -- around six minutes for this file
 * -- against the runner's sixty-second per-case ceiling. What is given up is per-keystroke fidelity,
 * and that is affordable HERE precisely because it is asserted elsewhere: keystroke-level filtering is
 * the whole subject of {@link refusesAlphabeticInputInAllSixNumericParts}, which keeps typing character
 * by character. Every other case treats a filled field as a precondition, and a field filled by one
 * change event holds exactly the value a field filled by three keystrokes holds.
 *
 * Assumptions: a change event is enough because the screen's part handler reads `event.target.value` and
 * filters it, rather than accumulating per keypress -- so one event carrying the whole value goes
 * through the same filter the keystrokes would have.
 * @param {string} caption - The bound's caption, from the message catalog.
 * @param {string} part - The part name, one of `month`, `day` or `year`.
 * @param {string} value - The value to place in the field.
 * @returns {void} Nothing; the control holds the value.
 */
function fillDatePart(caption: string, part: string, value: string): void {
  fireEvent.change(datePartControl(caption, part), { target: { value } });
}

/**
 * Fills all six parts of a custom range.
 *
 * Assumptions: the parts are filled in the mapset's own reading order -- start month, day, year then end
 * month, day, year, which is rows 13 and 14 left to right -- so a reader comparing this against the map
 * follows the same order the operator did.
 * @param {CustomRangeEntry} range - The six part values to place.
 * @returns {void} Nothing; all six controls hold their values.
 */
function fillCustomRange(range: CustomRangeEntry): void {
  const start = REPORTS_CAPTIONS.startDate;
  const end = REPORTS_CAPTIONS.endDate;
  fillDatePart(start, 'month', range.startMonth);
  fillDatePart(start, 'day', range.startDay);
  fillDatePart(start, 'year', range.startYear);
  fillDatePart(end, 'month', range.endMonth);
  fillDatePart(end, 'day', range.endDay);
  fillDatePart(end, 'year', range.endYear);
}

/**
 * Answers the confirmation field with a consenting character and dispatches Enter.
 *
 * ⚠️ Assumptions: the SUBMIT is driven through a real key event rather than through the confirmation
 * dialogue the submit control opens. The terminal was keyboard-only, so Enter is the reference's own
 * path and `usePfKeys` is what carries it; {@link dispatchesTheSameTwoActionsFromTheLegendControls}
 * covers the pointer path separately, so neither surface is left unasserted.
 *
 * Assumptions: the confirmation CHARACTER is placed with a change event while the key press stays a
 * real one, and the asymmetry is the point. What has to be genuine is the key event, because the
 * binding under test is the hook's; placing one character in a field is a precondition, and
 * {@link fillDatePart} records why a precondition is not paid for keystroke by keystroke here.
 * @param {ReturnType<typeof userEvent.setup>} operator - The interaction driver, whose key events the
 *   hook's binding is exercised through.
 * @param {string} answer - The character to place in the confirmation field.
 * @returns {Promise<void>} Resolves once the turn has been dispatched.
 */
async function answerAndSubmit(
  operator: ReturnType<typeof userEvent.setup>,
  answer: string,
): Promise<void> {
  fireEvent.change(confirmationControl(), { target: { value: answer } });
  await pressPfKey(operator, 'ENTER');
}

/**
 * Builds the answer the mocked service returns for a started run.
 *
 * ⚠️ Assumptions: the submission carries `executionName` and NO orchestration handle in full.
 * `ui/src/api/types.ts` records that the `executionArn` member was WITHDRAWN from the published
 * contract -- the status operation is addressed by name, and an ARN carries the account identifier and
 * region of the deployment that ran the report. Building the fixture from the published shape is what
 * lets {@link namesNoOrchestrationDetailAnywhere} assert the absence honestly rather than against a
 * fixture that never had one.
 * @returns {ReportSubmissionOutcome} A started-run outcome.
 */
function startedRun(): ReportSubmissionOutcome {
  return {
    outcome: 'STARTED',
    message: formatMessageTemplate(MESSAGE_TEMPLATES.REPORT_SUBMITTED_FOR_PRINTING, {
      'WS-REPORT-NAME': REPORT_TYPE_NAMES.custom,
    }),
    submission: {
      executionName: STARTED_RUN_NAME,
      reportName: REPORT_TYPE_NAMES.custom,
      shortName: REPORT_TYPE_NAMES.custom,
      longName: REPORT_TYPE_NAMES.custom,
      startDate: `${VALID_RANGE.startYear}-${VALID_RANGE.startMonth}-${VALID_RANGE.startDay}`,
      endDate: `${VALID_RANGE.endYear}-${VALID_RANGE.endMonth}-${VALID_RANGE.endDay}`,
      submittedAt: '2022-07-18 22:10:31.000000',
    },
  };
}

/**
 * Builds the settled status the mocked service reports for the started run.
 *
 * ⚠️ Assumptions: the status is SUCCEEDED rather than RUNNING, and the choice is load-bearing rather
 * than cosmetic. The screen follows a started run on a timer and stops once the run has settled, so a
 * RUNNING status leaves a poll scheduled past the end of the case -- and because the runner resets
 * every mock between cases, that poll then calls a stub with no implementation and rejects with a type
 * error from inside the screen's effect. A settled status ends the loop on its first read.
 *
 * Assumptions: both result members are null, so no document is offered. `collectableCoordinates`
 * requires a stored location before it will surface a collect control, and a case about the field
 * surface or the key bindings has no business installing the browser download facilities that control
 * would need -- those belong to the run-lifecycle suite that owns them,
 * `ui/src/screens/reports/reports.test.tsx`.
 * @returns {ReportExecutionStatus} A settled run carrying no document.
 */
function succeededRun(): ReportExecutionStatus {
  return {
    executionName: STARTED_RUN_NAME,
    status: 'SUCCEEDED',
    startedAt: '2022-07-18 22:10:31.000000',
    stoppedAt: '2022-07-18 22:11:02.000000',
    reportType: 'custom',
    startDate: `${VALID_RANGE.startYear}-${VALID_RANGE.startMonth}-${VALID_RANGE.startDay}`,
    endDate: `${VALID_RANGE.endYear}-${VALID_RANGE.endMonth}-${VALID_RANGE.endDay}`,
    resultUri: null,
    resultGeneratedAt: null,
  };
}

/**
 * Arranges a submission that starts a run and a status read that settles it.
 *
 * ⚠️ Assumptions: BOTH operations are arranged together, because starting a run is not the end of what
 * the screen does -- it immediately begins following the run it started. Arranging only the submission
 * leaves the follow-up call unstubbed, and the resulting rejection inside a React effect unmounts the
 * whole tree, so a LATER assertion in the same case fails against an empty document and names the
 * element it could not find rather than the missing stub. That is precisely how this pairing was
 * found, and it is why the two are set in one named place rather than at each call site.
 * @returns {void} Nothing; the two transport stubs are armed.
 */
function arrangeStartedRun(): void {
  vi.mocked(submitTransactionReport).mockResolvedValue(startedRun());
  vi.mocked(readReportExecution).mockResolvedValue(succeededRun());
}

/**
 * Builds the failure the shared client raises for a service refusal carrying per-field entries.
 *
 * ⚠️ Assumptions: a real `ApiRequestError` instance is required rather than any object of the same
 * shape, because `ui/src/api/client.ts` publishes `isApiRequestError` as an `instanceof` test and the
 * screen's refusal mapping branches on it -- a look-alike would take the no-document branch and the
 * per-field entries would never be read. That is why this file reaches the client module for the class
 * while taking the problem document itself from the `apiError` and `fieldError` builders in `./setup`,
 * which is the same division the sibling account, card and reference suites use.
 * @param {readonly FieldError[]} fieldErrors - The per-field entries the document carries.
 * @returns {ApiRequestError} The failure a refused submission would reject with.
 */
function refusedSubmission(fieldErrors: readonly FieldError[]): ApiRequestError {
  const problem: ApiError = apiError({ status: 400, fieldErrors });
  return new ApiRequestError('PROBLEM', 400, problem, 'the service refused the submission');
}

/**
 * Reads the one request the screen handed to the transport.
 * @returns {ReportRequest} The submitted request body.
 * @throws {Error} If the screen submitted nothing, so the returned value is sound rather than
 *   asserted non-null -- `noUncheckedIndexedAccess` is on and the lint gate refuses a non-null
 *   assertion.
 */
function submittedRequest(): ReportRequest {
  const [firstCall] = vi.mocked(submitTransactionReport).mock.calls;
  if (firstCall === undefined) {
    throw new Error('the screen submitted no report request');
  }
  return firstCall[0];
}

/**
 * Reads every string the rendered document currently paints.
 *
 * Assumptions: `textContent` of the whole body rather than a query, because the two cases that use this
 * assert an ABSENCE -- that no orchestration detail and no cardholder value appears ANYWHERE -- and an
 * absence checked by a query is only an absence in the place the query looked.
 * @returns {string} Everything the document renders as text.
 */
function renderedText(): string {
  return window.document.body.textContent ?? '';
}

/**
 * Asserts the ten keyable controls carry the widths the symbolic map declares.
 *
 * Purpose: the 3270 terminal enforced a field's width in hardware -- a `PIC X(2)` field took two
 * characters and the third keystroke did nothing -- and `maxLength` is where that constraint survives.
 *
 * Assumptions: the widths are read from `DATE_PART_WIDTHS` and `CONFIRM_WIDTH`, which the screen
 * publishes and documents against the symbolic map, rather than being retyped here. The map declares
 * `SDTMMI PIC X(2)` at `app/cpy-bms/CORPT00.CPY` L78, `SDTDDI PIC X(2)` L84, `SDTYYYYI PIC X(4)` L90,
 * `EDTMMI PIC X(2)` L96, `EDTDDI PIC X(2)` L102, `EDTYYYYI PIC X(4)` L108 and `CONFIRMI PIC X(1)`
 * L114 -- so the expected values below are those seven declarations reached through the one module
 * that owns them.
 * @returns {Promise<void>} Resolves once every width has been asserted.
 */
async function holdsEveryControlToItsDeclaredWidth(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  const start = REPORTS_CAPTIONS.startDate;
  const end = REPORTS_CAPTIONS.endDate;
  // WHY : Assumptions: month and day are two characters and year is four, on BOTH bounds. The mapset
  //       declares the six symmetrically, so an asymmetric expectation here would be asserting a
  //       layout the source does not have.
  expectMaxLength(datePartControl(start, 'month'), DATE_PART_WIDTHS.month);
  expectMaxLength(datePartControl(start, 'day'), DATE_PART_WIDTHS.day);
  expectMaxLength(datePartControl(start, 'year'), DATE_PART_WIDTHS.year);
  expectMaxLength(datePartControl(end, 'month'), DATE_PART_WIDTHS.month);
  expectMaxLength(datePartControl(end, 'day'), DATE_PART_WIDTHS.day);
  expectMaxLength(datePartControl(end, 'year'), DATE_PART_WIDTHS.year);
  expectMaxLength(confirmationControl(), CONFIRM_WIDTH);

  /*
   * WHY : Assumptions: the keyable surface is TEN controls -- three selectors, six date parts and the
   *       confirmation -- which is the count of named UNPROTECTED fields on the map. `CORPT00.bms`
   *       carries 42 `DFHMDF` definitions in total, of which the remainder are the six header-band
   *       fields, the row-23 message field, the row-24 legend and the painted captions, separators and
   *       hints that no operator keys into.
   */
  expect(screen.getAllByRole('radio')).toHaveLength(REPORT_TYPES.length);
}

/**
 * Asserts the initial cursor lands on the one field the mapset marks with `IC`, and on nothing else.
 *
 * ⚠️ Assumptions: the assertion is on FOCUS rather than on an `autofocus` attribute, and that is a
 * property of React rather than a choice made here -- React implements the `autoFocus` prop by calling
 * `focus()` after mount and emits no attribute, which a probe of this screen confirmed reports
 * `autofocus=false` on all three selectors. Asserting the attribute would therefore pass on a screen
 * that had lost the prop entirely.
 *
 * Assumptions: the expected recipient is the FIRST report type. `app/bms/CORPT00.bms` L80 declares
 * `MONTHLY DFHMDF ATTRB=(FSET,IC,NORM,UNPROT)`, and `IC` places the initial cursor; that is the map's
 * ONLY `IC` occurrence out of its 42 field definitions, so exactly one control may take it.
 * @returns {void} Nothing; the case asserts.
 * @throws {Error} If the screen rendered no selectors at all, which is a composition failure
 *   rather than a failed expectation, and is named as such instead of surfacing as a read of
 *   `undefined`.
 */
function placesTheInitialCursorOnTheOneIcField(): void {
  render(reportsTree());

  const selectors = screen.getAllByRole('radio');
  const [firstSelector] = selectors;
  if (firstSelector === undefined) {
    throw new Error('the screen rendered no report-type selectors');
  }
  expect(firstSelector).toHaveFocus();

  /*
   * WHY : Assumptions: the remaining selectors are asserted UNFOCUSED individually rather than the
   *       focused one merely being asserted present, because "exactly one initial cursor" is a
   *       statement about the others as much as about the first. A second `autoFocus` on the map would
   *       be invisible to a one-sided assertion -- the last one to mount would win and the case would
   *       still pass.
   */
  for (const other of selectors.slice(1)) {
    expect(other).not.toHaveFocus();
  }
}

/**
 * Asserts the calendar affordance and the six split parts coexist, each in its own date format.
 *
 * ⚠️ Purpose: reconciling one antd `DatePicker` against three separately-attributed mapset fields per
 * bound is the presentational deviation this screen has to make honestly, and the screen makes it by
 * rendering BOTH: the six keyable parts the reference declares, plus a calendar control beside them.
 *
 * Assumptions: the two carry DIFFERENT formats and both are the source's own. The picker displays
 * `(MM/DD/YYYY)`, which is the literal the mapset paints once per bound as a `LENGTH=12 COLOR=BLUE`
 * hint at `POS=(13,46)` and `POS=(14,46)`; the value transmitted is year-first, which is
 * `app/cbl/CORPT00C.cbl` L72 declaring `WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'` and handing it to
 * the calendar validator at L389. The screen shows the operator's order and transmits the contract's,
 * so asserting only one of the two would report the other as a defect.
 *
 * Assumptions: this is design gap G1 taken deliberately. Field grouping, reading order and tab order
 * are preserved; the fixed 24-by-80 character geometry is not, so nothing below asserts a pixel offset
 * or a character coordinate.
 * @returns {Promise<void>} Resolves once both formats have been asserted.
 */
async function reconcilesTheCalendarControlWithTheSixSplitParts(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  /*
   * WHY : Assumptions: the calendar control is found by the accessible name the screen composes from
   *       the bound's caption and the mapset's own format hint. It carries no `maxLength`, which is
   *       what distinguishes it from the three width-constrained parts beside it and is why the
   *       reconciliation needs asserting rather than assuming.
   */
  for (const caption of [REPORTS_CAPTIONS.startDate, REPORTS_CAPTIONS.endDate]) {
    const calendar = screen.getByLabelText(
      `${accessibleCaption(caption)} ${REPORTS_CAPTIONS.dateFormatHint}`,
    );
    expect(calendar).not.toHaveAttribute('maxlength');
  }

  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');
  await waitFor(
    /**
     * Waits for the one submission to have been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );

  const request = submittedRequest();
  // WHY : Assumptions: the transmitted bound is asserted against the ISO SHAPE rather than against a
  //       recomputed string, because the property under test here is the FORMAT the two halves settle
  //       on -- four digits, hyphen, two, hyphen, two -- per `app/cbl/CORPT00C.cbl` L72. Which bounds
  //       a preset omits is a different contract and is owned by
  //       `ui/src/screens/reports/rangeAuthority.test.tsx`.
  const isoDate = /^\d{4}-\d{2}-\d{2}$/u;
  expect(request.startDate).toMatch(isoDate);
  expect(request.endDate).toMatch(isoDate);
  expect(request.startDate).toBe(
    `${VALID_RANGE.startYear}-${VALID_RANGE.startMonth}-${VALID_RANGE.startDay}`,
  );
}

/**
 * Asserts all six numeric-attribute parts refuse alphabetic input.
 *
 * ⚠️ Assumptions: the six are exactly the fields `app/bms/CORPT00.bms` declares
 * `ATTRB=(FSET,NORM,NUM,UNPROT)` -- `SDTMM` L127, `SDTDD` L138, `SDTYYYY` L149, `EDTMM` L166,
 * `EDTDD` L177 and `EDTYYYY` L188 -- which is six of the seven `NUM` occurrences across all seventeen
 * base mapsets, and the map's whole `NUM` population.
 *
 * ⚠️ Assumptions: the assertion is that ALPHABETIC input is refused, NOT that the field admits digits
 * alone. The screen's filter deliberately also keeps the sign and the decimal point, because a field
 * admitting digits only could never hold a non-numeric value -- which would make the reference's own
 * `IS NOT NUMERIC` arms, and therefore both `Not a valid Year` sentences whose sole predicate is that
 * test, unreachable from the user interface. Asserting digits-only here would demand the screen delete
 * a rule the reference wrote.
 * @returns {Promise<void>} Resolves once every part has refused its letters.
 */
async function refusesAlphabeticInputInAllSixNumericParts(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  const numericParts = [
    { caption: REPORTS_CAPTIONS.startDate, part: 'month', mapsetLine: 127 },
    { caption: REPORTS_CAPTIONS.startDate, part: 'day', mapsetLine: 138 },
    { caption: REPORTS_CAPTIONS.startDate, part: 'year', mapsetLine: 149 },
    { caption: REPORTS_CAPTIONS.endDate, part: 'month', mapsetLine: 166 },
    { caption: REPORTS_CAPTIONS.endDate, part: 'day', mapsetLine: 177 },
    { caption: REPORTS_CAPTIONS.endDate, part: 'year', mapsetLine: 188 },
  ] as const;

  // WHY : Assumptions: six entries, matching the six `NUM` declarations counted above; the `mapsetLine`
  //       member exists so each expectation carries its own citation rather than sharing one comment.
  expect(numericParts).toHaveLength(6);

  for (const { caption, part } of numericParts) {
    const control = datePartControl(caption, part);

    /*
     * WHY : Assumptions: real per-character typing is used here and nowhere else in this file, because
     *       per-character filtering IS this case's subject -- a single change event would exercise the
     *       filter but not the keystroke path the `NUM` attribute governs. Two characters carry the
     *       whole per-keystroke contract: the letter must be refused and the digit kept, leaving `'1'`.
     * WHY : Trade-offs: the run is two characters rather than three, and the third character's property
     *       -- that a letter arriving AFTER a digit is dropped too, so the filter reads the whole value
     *       rather than a prefix -- is asserted below by a change event instead, which costs nothing.
     *       Measured reason for splitting them: six fields at three real keystrokes each put this case
     *       at 34.9s against the runner's 60s ceiling on a contended host, and `ui/vitest.config.ts`
     *       records that this suite's failures under load present as timeouts rather than as assertion
     *       errors. Trading three simulated keystrokes for one change event keeps both properties and
     *       removes the headroom problem.
     */
    await operator.type(control, 'a1');
    expect(control).toHaveValue('1');

    fireEvent.change(control, { target: { value: 'a1b' } });
    expect(control).toHaveValue('1');
  }
}

/**
 * Records the two distinct widths the message line is declared with, and asserts they differ.
 *
 * ⚠️ Purpose: this screen's message line has TWO declared widths and they are not the same number.
 * `app/bms/CORPT00.bms` declares the field itself `LENGTH=78` at L220, matched by
 * `ERRMSGI PIC X(78)` at `app/cpy-bms/CORPT00.CPY` L120; the CONTENT the programs move into it is
 * declared 75, as `CCARD-ERROR-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28 and `CCARD-RETURN-MSG PIC
 * X(75)` at L29.
 *
 * ⚠️ Trade-offs: both numbers are recorded and NEITHER is corrected to the other. The temptation is to
 * treat 75 as a mistake for 78 and normalise it; that would discard a real distinction -- the field is
 * three characters wider than anything written into it -- which two different copybooks state
 * independently. This case asserts the two published constants hold those two values and are distinct,
 * which is the smallest assertion that makes an accidental normalisation fail.
 *
 * Assumptions: this is a statement about the two DECLARED contracts and not about how wide the band
 * renders. `ui/src/layout/MessageBand.tsx` owns its own rendering and its own suite; the numbers are
 * reached through the constants it publishes so that neither this file nor that one holds a literal.
 * @returns {void} Nothing; the case asserts.
 */
function keepsTheContentWidthDistinctFromTheFieldWidth(): void {
  expect(MESSAGE_BAND_CONTENT_WIDTH).toBe(75);
  expect(MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH).toBe(78);
  expect(MESSAGE_BAND_CONTENT_WIDTH).not.toBe(MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH);
}

/**
 * Asserts the header band names this screen's transaction and program, as the map's own fields do.
 *
 * Assumptions: the two values are the map's `TRNNAME` and `PGMNAME` fields --
 * `TRNNAMEI PIC X(4)` at `app/cpy-bms/CORPT00.CPY` L24 and `PGMNAMEI PIC X(8)` at L42 -- and the
 * screen publishes them to the shell through `useShellSlot` rather than painting them itself, so they
 * are asserted inside the shell's title band.
 *
 * Assumptions: the four remaining header fields -- the two `X(40)` title lines and the `X(8)` date and
 * time stamps -- are NOT asserted here. They are painted identically on all seventeen mapsets from the
 * shared title copybook and the server-anchored instant, so they belong to the shell's own suite; this
 * case asserts only the two values that are this screen's.
 * @returns {void} Nothing; the case asserts.
 */
function namesItsOwnTransactionAndProgramInTheHeaderBand(): void {
  render(reportsTree());
  const banner = screen.getByRole('banner');
  expect(banner).toHaveTextContent(REPORTS_TRANSACTION_ID);
  expect(banner).toHaveTextContent(REPORTS_PROGRAM_NAME);
  // WHY : Assumptions: the row-4 heading is this map's own `LENGTH=19` literal at L79-L80, carried by
  //       `REPORTS_TITLE`, and it is asserted through the catalog rather than retyped.
  expect(screen.getByText(REPORTS_TITLE)).toBeInTheDocument();
}

/** Registers the cases about the keyable field surface. */
function fieldSurfaceCases(): void {
  beforeEach(anchorObservedServerInstant);
  afterEach(discardObservedServerInstant);

  it('holds every control to its declared copybook width', holdsEveryControlToItsDeclaredWidth);
  it('places the initial cursor on the one IC field', placesTheInitialCursorOnTheOneIcField);
  it(
    'reconciles the calendar control with the six split parts',
    reconcilesTheCalendarControlWithTheSixSplitParts,
  );
  it('refuses alphabetic input in all six NUM parts', refusesAlphabeticInputInAllSixNumericParts);
  it(
    'keeps the 75-character content width distinct',
    keepsTheContentWidthDistinctFromTheFieldWidth,
  );
  it('names its own transaction and program', namesItsOwnTransactionAndProgramInTheHeaderBand);
}

describe("the transaction-reports screen renders the mapset's keyable surface", fieldSurfaceCases);

/**
 * Asserts one report type at a time, and that choosing a second clears the first.
 *
 * ⚠️ Refactoring Rationale: the mapset declares the choice as THREE separate one-character fields --
 * `MONTHLYI PIC X(1)` at `app/cpy-bms/CORPT00.CPY` L60, `YEARLYI` L66 and `CUSTOMI` L72 -- each toggled
 * by typing a character into it, and the program resolves them with a first-match `EVALUATE TRUE` at
 * `app/cbl/CORPT00C.cbl` L212-L256 that silently prefers monthly when two are marked. Rendering three
 * independent inputs would let an operator reach that ambiguous state and then be surprised by which
 * report ran. One `Radio.Group` makes the exclusion STRUCTURAL rather than procedural, which cannot
 * change observable behaviour because the ambiguous state is one the reference only ever resolves away.
 *
 * ⚠️ Assumptions: the deselection half is what this case exists for, and it needs two SELECTABLE
 * options -- which is why the anchored instant matters. Asserting only that a chosen option is checked
 * would pass on three independent checkboxes.
 * @returns {Promise<void>} Resolves once the exclusion has been asserted in both directions.
 */
async function admitsExactlyOneReportTypeAtATime(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());

  // WHY : Assumptions: one group, not three controls. The group element is what carries the
  //       `radiogroup` role, so its presence is the structural claim being made.
  expect(screen.getAllByRole('radiogroup')).toHaveLength(1);
  const selectors = screen.getAllByRole('radio');
  expect(selectors).toHaveLength(REPORT_TYPES.length);

  await chooseReportType(operator, REPORT_TYPE_PROMPTS.monthly);
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.monthly })).toBeChecked();
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.yearly })).not.toBeChecked();
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.custom })).not.toBeChecked();

  await chooseReportType(operator, REPORT_TYPE_PROMPTS.yearly);
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.yearly })).toBeChecked();
  // WHY : Assumptions: this is the assertion that distinguishes a radio group from three checkboxes --
  //       the previously chosen option must now be CLEAR, which no amount of independent marking would
  //       produce.
  expect(screen.getByRole('radio', { name: REPORT_TYPE_PROMPTS.monthly })).not.toBeChecked();
}

/**
 * Asserts a turn with no report type marked is refused in the reference's own words.
 *
 * Assumptions: the sentence is `app/cbl/CORPT00C.cbl` L438, reached by the `WHEN OTHER` arm of the outer
 * `EVALUATE` at L437-L442 -- the arm taken when none of the three marks is set.
 *
 * Assumptions: the case also asserts NOTHING was submitted, because a screen that painted the refusal
 * and dispatched anyway would satisfy a message-only assertion while starting a report run the operator
 * never asked for.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesATurnWithNoReportTypeMarked(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());

  await answerAndSubmit(operator, 'Y');
  await waitFor(
    /**
     * Waits for the refusal to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT);
    },
  );
  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();
}

/**
 * Asserts the three selector captions are the mapset's, and are not the program's report names.
 *
 * ⚠️ Assumptions: this screen carries TWO sets of three strings and neither is derivable from the other.
 * The CAPTIONS an operator reads beside each selector are `LENGTH=23 COLOR=TURQUOISE` fields the mapset
 * paints at `POS=(7,15)`, `POS=(9,15)` and `POS=(11,15)`; the report NAMES the program moves into
 * `WS-REPORT-NAME` and interpolates into two of its sentences are the bare words at
 * `app/cbl/CORPT00C.cbl` L214, L240 and L433. Conflating them would either put
 * `Monthly (Current Month)` inside a sentence the reference spells `Monthly`, or strip the caption an
 * operator chooses by -- so the case asserts the captions render AND that the two sets differ.
 * @returns {void} Nothing; the case asserts.
 */
function paintsTheMapsetCaptionsAndNotTheReportNames(): void {
  render(reportsTree());

  for (const caption of Object.values(REPORT_TYPE_PROMPTS)) {
    expect(expectVerbatimMessage(caption)).toBeInTheDocument();
  }

  // WHY : Assumptions: `'Monthly'` at L214 is a PREFIX of `'Monthly (Current Month)'`, so equality is
  //       the discriminating comparison rather than containment -- a screen that had substituted the
  //       bare name for the caption would still satisfy a containment check in one direction.
  expect(REPORT_TYPE_NAMES.monthly).not.toBe(REPORT_TYPE_PROMPTS.monthly);
  expect(REPORT_TYPE_NAMES.yearly).not.toBe(REPORT_TYPE_PROMPTS.yearly);
  expect(REPORT_TYPE_NAMES.custom).not.toBe(REPORT_TYPE_PROMPTS.custom);
}

/**
 * Asserts the date range is present for the custom type and absent for a preset.
 *
 * Assumptions: the reference reads the six date parts only in the custom arm and derives the period
 * itself for the other two, so a preset has no range to key. The screen mounts and unmounts the block
 * rather than disabling it, which keeps the tab order of the confirmation and the actions below
 * unaffected -- an operator choosing monthly tabs from the selector straight to the confirmation,
 * exactly as they would on a terminal where the date fields sat unused.
 * @returns {Promise<void>} Resolves once both states have been asserted.
 */
async function mountsTheDateRangeOnlyForTheCustomType(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());

  await chooseReportType(operator, REPORT_TYPE_PROMPTS.monthly);
  expect(
    screen.queryByLabelText(`${accessibleCaption(REPORTS_CAPTIONS.startDate)} month`),
  ).not.toBeInTheDocument();

  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  expect(datePartControl(REPORTS_CAPTIONS.startDate, 'month')).toBeInTheDocument();
  expect(datePartControl(REPORTS_CAPTIONS.endDate, 'year')).toBeInTheDocument();
}

/** Registers the cases about the report-type choice. */
function reportTypeSelectionCases(): void {
  beforeEach(anchorObservedServerInstant);
  afterEach(discardObservedServerInstant);

  it('admits exactly one report type at a time', admitsExactlyOneReportTypeAtATime);
  it('refuses a turn with no report type marked', refusesATurnWithNoReportTypeMarked);
  it(
    'paints the mapset captions, not the report names',
    paintsTheMapsetCaptionsAndNotTheReportNames,
  );
  it('mounts the date range only for the custom type', mountsTheDateRangeOnlyForTheCustomType);
}

describe('the report-type choice is one mutually exclusive group', reportTypeSelectionCases);

/**
 * Asserts every sentence this program declares carries the baseline line the file cites for it.
 *
 * ⚠️ Purpose: this is what turns the citations throughout this file from comments into assertions.
 * `ui/src/messages/messages.ts` publishes `PROGRAM_MESSAGE_SOURCES` as the line each string is emitted
 * from, tied to the text group key by key through a mapped `satisfies` type -- so comparing the
 * published line with the line this file cites fails if either moves.
 *
 * Assumptions: all fourteen date sentences are checked here, across the three families, plus the
 * selection refusal and the submission failure. That is every sentence any case below quotes, so no
 * quoted line number in this file is uncorroborated.
 *
 * Alternatives Considered: reading `app/cbl/CORPT00C.cbl` from disk and comparing the line directly,
 * which would be the strongest possible check. Rejected because this project is type-checked with
 * `"types": []`, so the file-system module is unavailable to a browser-project source file, and because
 * `app/**` is reference-only -- the catalog is the sanctioned single place its text and provenance are
 * carried into this tree.
 * @returns {void} Nothing; the case asserts.
 */
function theProgramSentencesCarryTheirSourceLines(): void {
  const allDateSentences = [
    ...BLANK_PART_SENTENCES,
    ...INVALID_PART_SENTENCES,
    ...WHOLE_DATE_SENTENCES,
  ];
  // WHY : Assumptions: fourteen date sentences -- six blank, six out-of-range and two whole-date --
  //       which is the count `app/cbl/CORPT00C.cbl` emits between L261 and L420.
  expect(allDateSentences).toHaveLength(14);

  for (const entry of allDateSentences) {
    expect(entry.sourceLines).toEqual([entry.expectedLine]);
  }

  // WHY : Assumptions: the two remaining sentences this file quotes are the selection refusal at L438
  //       and the submission failure at L531.
  expect(REPORT_MESSAGE_LINES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT).toEqual([438]);
  expect(REPORT_MESSAGE_LINES.UNABLE_TO_WRITE_TDQ_JOBS).toEqual([531]);
  // WHY : Assumptions: the catalog attributes all of the above to this program's own file, so a group
  //       renamed or re-pointed would fail here rather than silently supply another program's text.
  expect(PROGRAM_SOURCE_FILES.CORPT00C).toBe('app/cbl/CORPT00C.cbl');
}

/**
 * Asserts the separator between a bound's name and its part keeps a space on either side.
 *
 * ⚠️ Assumptions: the reference writes `'Start Date - Month can NOT be empty...'`, not
 * `'Start Date-Month ...'`. All fourteen date sentences share the form, and a lost space is exactly the
 * class of edit that reads as correct in review -- so the separator is asserted with its spaces, and
 * the unspaced form is asserted absent so a normalising edit cannot pass in either direction.
 * @returns {void} Nothing; the case asserts.
 */
function keepsTheSpacedSeparatorInEveryDateSentence(): void {
  const spacedSeparator = ' - ';
  for (const entry of [
    ...BLANK_PART_SENTENCES,
    ...INVALID_PART_SENTENCES,
    ...WHOLE_DATE_SENTENCES,
  ]) {
    expect(entry.sentence).toContain(spacedSeparator);
    // WHY : Assumptions: the sentence begins with the bound's name and the separator follows it
    //       immediately, so the spaced form must occur at a known offset rather than anywhere -- a
    //       sentence that had gained a second hyphen elsewhere would satisfy containment alone.
    expect(entry.sentence.indexOf(spacedSeparator)).toBeGreaterThan(0);
  }
}

/**
 * Asserts the two capitalisations this program uses are both preserved, and are not reconciled.
 *
 * ⚠️ Assumptions: `app/cbl/CORPT00C.cbl` capitalises differently in two of its families and both
 * spellings are the reference's. The six blank-part sentences capitalise NOT --
 * `'can NOT be empty'`, not `'cannot'` -- at L261 through L296. The six out-of-range sentences
 * capitalise the PART NAME -- `'Not a valid Month'`, `'Day'`, `'Year'` -- at L331 through L374, while
 * the two whole-date sentences lowercase the word `date` at L400 and L420. Two different
 * capitalisations of the same construction, in one program.
 *
 * ⚠️ Trade-offs: a tidier system would pick one and normalise, and transformation rule T8 forecloses
 * that -- user-visible strings cross character for character even where the inconsistency is the
 * source's. Normalising would be a behavioural divergence requiring registration in
 * `docs/architecture/cobol-to-service-traceability.md`, and the tidiness bought would not be worth a
 * golden-master entry.
 * @returns {void} Nothing; the case asserts.
 */
function keepsTheTwoCapitalisationsTheProgramUses(): void {
  for (const entry of BLANK_PART_SENTENCES) {
    expect(entry.sentence).toContain('can NOT be empty');
    // WHY : Assumptions: the lower-case contraction is asserted ABSENT, because containment of the
    //       capitalised form alone would still pass on a sentence carrying both.
    expect(entry.sentence).not.toContain('cannot');
    expect(entry.sentence).toContain(entry.partWord);
  }

  for (const entry of INVALID_PART_SENTENCES) {
    expect(entry.sentence).toContain(`Not a valid ${entry.partWord}`);
    // WHY : Assumptions: the part name is capitalised in THIS family, so its lower-case spelling must
    //       not appear -- which is the half of the contract that distinguishes it from the two
    //       whole-date sentences below.
    expect(entry.sentence).not.toContain(`Not a valid ${entry.partWord.toLowerCase()}`);
  }

  for (const entry of WHOLE_DATE_SENTENCES) {
    // WHY : Assumptions: `date` is LOWER CASE here, at L400 and L420, which is the opposite of the
    //       family above and is the exact detail a normalising edit would erase.
    expect(entry.sentence).toContain('Not a valid date');
    expect(entry.sentence).not.toContain('Not a valid Date');
    expect(entry.partWord).toBe('date');
  }
}

/**
 * Asserts a blank date part surfaces its own sentence, on the band and beneath the control.
 *
 * Assumptions: the case leaves the START MONTH blank and keys the other five parts, because the
 * reference tests the six in order and stops at the first failure -- `validateCustomRange` reproduces
 * that, so a case that blanked several parts would be asserting the ordering rather than the sentence.
 * The other five sentences are held to their text and their source line by
 * {@link theProgramSentencesCarryTheirSourceLines} and
 * {@link keepsTheSpacedSeparatorInEveryDateSentence}, which is where the whole family is covered
 * without six near-duplicate renders.
 *
 * Assumptions: the sentence is expected TWICE -- once on the row-23 band and once as the control's help
 * text -- because the reference both moves the literal into `WS-MESSAGE` and marks the field, and the
 * screen has one sentence to render in both places. A single-element query would fail on the duplicate
 * rather than on the behaviour.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function surfacesTheBlankPartSentence(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  const start = REPORTS_CAPTIONS.startDate;
  const end = REPORTS_CAPTIONS.endDate;
  fillDatePart(start, 'day', VALID_RANGE.startDay);
  fillDatePart(start, 'year', VALID_RANGE.startYear);
  fillDatePart(end, 'month', VALID_RANGE.endMonth);
  fillDatePart(end, 'day', VALID_RANGE.endDay);
  fillDatePart(end, 'year', VALID_RANGE.endYear);
  await answerAndSubmit(operator, 'Y');

  const expected = REPORT_MESSAGES.START_DATE_MONTH_CAN_NOT_BE_EMPTY;
  await waitFor(
    /**
     * Waits for the blank-part refusal to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(expected);
    },
  );
  expect(screen.getAllByText(expected)).toHaveLength(2);
  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();
}

/**
 * Asserts an out-of-range date part surfaces its own sentence.
 *
 * Assumptions: the start month is keyed `13`, which is one past the highest value the reference accepts
 * -- it compares `SDTMMI > '12'` as characters on a `PIC X(2)` field at L330 -- so this exercises the
 * range family rather than the emptiness family that precedes it.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function surfacesTheOutOfRangePartSentence(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  fillCustomRange({ ...VALID_RANGE, startMonth: '13' });
  await answerAndSubmit(operator, 'Y');

  const expected = REPORT_MESSAGES.START_DATE_NOT_A_VALID_MONTH;
  await waitFor(
    /**
     * Waits for the range refusal to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(expected);
    },
  );
  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();
}

/**
 * Asserts the whole-date sentence the reference delegates to `CSUTLDTC` is surfaced.
 *
 * ⚠️ Assumptions: the case asserts the SURFACED SENTENCE and deliberately re-implements none of the
 * date-edit rules. `app/cbl/CORPT00C.cbl` hands each bound to `CSUTLDTC` at L392 and L412 and reads
 * back a severity and a message number; in the target that logic is
 * `com.carddemo.common.validation.DateEditValidator`, whose leap-year handling, supported-span
 * forgiveness and severity rubric are its own unit's contract. What this file owns is the client half:
 * that the sentence the check produced reaches the operator.
 *
 * Assumptions: the thirty-first of February is used because it passes every PART-level edit -- month 02
 * is within one to twelve and day 31 is within one to thirty-one -- so it can only be caught by the
 * whole-date check, which is what makes this case exercise L400 rather than one of the range sentences.
 * @returns {Promise<void>} Resolves once the delegated refusal has been asserted.
 */
async function surfacesTheDelegatedWholeDateSentence(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  fillCustomRange({ ...VALID_RANGE, startMonth: '02', startDay: '31' });
  await answerAndSubmit(operator, 'Y');

  const expected = REPORT_MESSAGES.START_DATE_NOT_A_VALID_DATE;
  await waitFor(
    /**
     * Waits for the whole-date refusal to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(expected);
    },
  );
  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();
}

/**
 * Asserts the confirmation prompt keeps its trailing space and concatenates the report name onto it.
 *
 * ⚠️ Purpose: `app/cbl/CORPT00C.cbl` L466 declares the literal `'Please confirm to print the '` and it
 * ENDS WITH A SPACE. That space is not formatting -- it is the separator between the prefix and the
 * report name the program moves in after it, so the composed sentence reads
 * `Please confirm to print the Custom report...`. It is the single most easily lost character on this
 * screen: any trim applied to the prefix, anywhere, silently deletes the space and joins the two words,
 * and the result still reads plausibly.
 *
 * ⚠️ Assumptions: the prefix is asserted through the catalog's own template PART, so what is checked is
 * the stored literal rather than a rendering of it. The composed sentence is then asserted separately,
 * which is what proves the space is doing its job rather than merely being present.
 *
 * Assumptions: a BLANK confirmation is what produces this prompt. L464-L474 tests the field for
 * `SPACES OR LOW-VALUES` first and returns to the screen asking again, so an operator who has not
 * answered is prompted rather than told they cancelled -- and nothing is submitted.
 * @returns {Promise<void>} Resolves once the prefix and the composed sentence have been asserted.
 */
async function keepsTheTrailingSpaceOnTheConfirmationPrefix(): Promise<void> {
  const [prefixPart] = MESSAGE_TEMPLATES.PLEASE_CONFIRM_TO_PRINT_REPORT.parts;
  if (prefixPart === undefined || !('literal' in prefixPart)) {
    throw new Error('the confirmation template no longer opens with a literal part');
  }
  const prefix: string = prefixPart.literal;

  /*
   * WHY : Assumptions: the final character is compared to a space EXPLICITLY rather than the prefix
   *       being compared with a trimmed copy of itself. The explicit form states which character is
   *       load-bearing, and it fails with a message naming the space rather than reporting two strings
   *       that look identical in a diff.
   */
  expect(prefix.endsWith(' ')).toBe(true);
  expect(prefix.charAt(prefix.length - 1)).toBe(' ');
  // WHY : Assumptions: the template's own provenance is L466 for the prefix, with the interpolation and
  //       the closing literal on L468 and L469.
  expect(MESSAGE_TEMPLATES.PLEASE_CONFIRM_TO_PRINT_REPORT.source.lines).toEqual([466, 468, 469]);

  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await pressPfKey(operator, 'ENTER');

  const composed = formatMessageTemplate(MESSAGE_TEMPLATES.PLEASE_CONFIRM_TO_PRINT_REPORT, {
    'WS-REPORT-NAME': REPORT_TYPE_NAMES.custom,
  });
  await waitFor(
    /**
     * Waits for the confirmation prompt to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(composed);
    },
  );
  /*
   * WHY : Assumptions: the composed sentence must START WITH the prefix including its space, which is
   *       the assertion that fails if the space were dropped at either end of the join -- the prefix
   *       would then be `...the` and the sentence `...theCustom report...`, and a containment check on
   *       the report name alone would still pass.
   */
  expect(composed.startsWith(prefix)).toBe(true);
  expect(composed).toBe(`${prefix}${REPORT_TYPE_NAMES.custom} report...`);
  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();
}

/**
 * Asserts an unmapped key reports the fifty-character shared sentence.
 *
 * Assumptions: `app/cbl/CORPT00C.cbl` L193 moves `CCDA-MSG-INVALID-KEY` into `WS-MESSAGE` on the
 * `WHEN OTHER` arm of its `EVALUATE EIBAID`, so this screen is one of the programs that emits it. The
 * sentence itself is declared `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21, and the catalog carries
 * both the padded text and the declared width.
 *
 * ⚠️ Assumptions: the declared-width padding is removed for the comparison because the DOM collapses
 * trailing whitespace on display, and the padding is the FIELD's rather than the sentence's. This is
 * the one other place in this file where padding is stripped, and it is stripped from a fixed-width
 * constant whose own `declaredWidth` member is asserted alongside -- unlike the confirmation prefix
 * above, whose trailing space is content and is never removed.
 *
 * Assumptions: F4 is the key pressed because it is the nearest unmapped key -- the uniform legend on
 * other mapsets binds it to Clear, and this mapset paints no such control, which is the point.
 * @returns {Promise<void>} Resolves once the shared sentence has been asserted.
 */
async function reportsAnUnmappedKeyWithTheSharedSentence(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());

  await operator.keyboard('{F4}');

  const invalidKey = COMMON_MESSAGES.INVALID_KEY;
  await waitFor(
    /**
     * Waits for the shared invalid-key sentence to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(invalidKey.text.replace(/ +$/u, ''));
    },
  );
  expect(invalidKey.declaredWidth).toBe(50);
  expect(invalidKey.source.file).toBe('app/cpy/CSMSG01Y.cpy');
  expect(invalidKey.source.lines).toEqual([21]);
}

/** Registers the cases about sentence fidelity. */
function sentenceFidelityCases(): void {
  beforeEach(anchorObservedServerInstant);
  afterEach(discardObservedServerInstant);

  it('carries every sentence with its source line', theProgramSentencesCarryTheirSourceLines);
  it(
    'keeps the spaced separator in every date sentence',
    keepsTheSpacedSeparatorInEveryDateSentence,
  );
  it('keeps both capitalisations the program uses', keepsTheTwoCapitalisationsTheProgramUses);
  it('surfaces the blank-part sentence', surfacesTheBlankPartSentence);
  it('surfaces the out-of-range part sentence', surfacesTheOutOfRangePartSentence);
  it('surfaces the delegated whole-date sentence', surfacesTheDelegatedWholeDateSentence);
  it(
    'keeps the trailing space on the confirmation prefix',
    keepsTheTrailingSpaceOnTheConfirmationPrefix,
  );
  it('reports an unmapped key with the shared sentence', reportsAnUnmappedKeyWithTheSharedSentence);
}

describe("every sentence is the reference's own, character for character", sentenceFidelityCases);

/**
 * Asserts a submitted report reaches the service through the reporting client and nothing else.
 *
 * ⚠️ Refactoring Rationale: the transport beneath this action changed completely. The baseline submits
 * the report by WRITING JOB-CONTROL TEXT to the `JOBS` transient data queue, which
 * `app/csd/CARDDEMO.CSD` defines at L499-L505 with `DDNAME(INREADER)` at L501 -- an internal reader
 * that runs whatever it is handed. The target replaces that with `reporting-service` calling a managed
 * orchestrator's start-execution operation. The client is deliberately IGNORANT of which: it calls one
 * published operation and reads one published outcome, which is exactly what makes the substitution
 * invisible to the operator.
 *
 * Assumptions: the request body is asserted to carry only members `ReportRequest` declares, because the
 * screen mirrors the symbolic map field for field and any extra member would be a transport detail
 * leaking into a contract that has no room for one.
 * @returns {Promise<void>} Resolves once the submission has been inspected.
 */
async function startsTheRunThroughTheReportingClientAlone(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the single submission to be issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );

  const request = submittedRequest();
  /*
   * WHY : Assumptions: the two header members the request echoes are the map's own `TRNNAME` and
   *       `PGMNAME` -- `TRNNAMEI PIC X(4)` at `app/cpy-bms/CORPT00.CPY` L24 and `PGMNAMEI PIC X(8)` at
   *       L42 -- and the screen publishes both as constants, so they are compared against those rather
   *       than against retyped values.
   */
  expect(request.transactionName).toBe(REPORTS_TRANSACTION_ID);
  expect(request.programName).toBe(REPORTS_PROGRAM_NAME);
  /*
   * WHY : Assumptions: the mark carries the CONSENTING CHARACTER rather than a boolean, because the map
   *       field is `CUSTOMI PIC X(1)` at L72 and the reference tests only that it is not blank -- at
   *       L213, L239 and L256 -- so any non-blank character selects and what travels is a character.
   */
  expect(request.custom).toBe('Y');
  expect(request.confirm).toBe('Y');

  const declaredMembers = [
    'transactionName',
    'title01',
    'currentDate',
    'programName',
    'title02',
    'currentTime',
    'monthly',
    'yearly',
    'custom',
    'startDate',
    'endDate',
    'confirm',
    'errorMessage',
  ];
  for (const member of Object.keys(request)) {
    expect(declaredMembers).toContain(member);
  }
}

/**
 * Asserts the client is called once per confirmation, not once per keystroke or render.
 *
 * Assumptions: the case keys twelve characters across seven controls before confirming once, so a screen
 * that submitted on change -- or that re-submitted on each re-render the keystrokes caused -- would
 * report a count far above one and the failure would name the count rather than the mechanism.
 *
 * Assumptions: a 3270 keyboard locks until the region replies, and the screen reproduces that with an
 * in-flight guard, so a second Enter dispatched before the first answer must add no second call. That
 * half is asserted here because it is the same property viewed from the other side -- one confirmation,
 * one run -- and a doubled submission would start two report runs and leave two sets of output objects
 * with nothing to say which is current.
 * @returns {Promise<void>} Resolves once the call count has been asserted.
 */
async function callsTheClientOncePerConfirmation(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);

  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();

  await answerAndSubmit(operator, 'Y');
  await waitFor(
    /**
     * Waits for the confirmed turn's single submission.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );

  /*
   * WHY : Assumptions: the submission identity is minted once per SUBMISSION and reused across its
   *       attempts, so the second argument is a stable key rather than a fresh value per dispatch. It
   *       is asserted non-empty rather than compared with a fixture, because the real
   *       `newSubmissionKey` is left unmocked -- the factory spreads the actual module -- and its
   *       format is that function's own contract.
   */
  const [firstCall] = vi.mocked(submitTransactionReport).mock.calls;
  if (firstCall === undefined) {
    throw new Error('the screen submitted no report request');
  }
  expect(typeof firstCall[1]).toBe('string');
  expect(String(firstCall[1]).length).toBeGreaterThan(0);
  expect(typeof newSubmissionKey()).toBe('string');
}

/**
 * Asserts the reference's own failure sentence survives the change of transport.
 *
 * ⚠️ Trade-offs: the sentence `'Unable to Write TDQ (JOBS)...'` at `app/cbl/CORPT00C.cbl` L531 NAMES A
 * MECHANISM THAT NO LONGER EXISTS. There is no transient data queue in the target and no `JOBS`
 * destination; the write it describes is now a call to a managed orchestrator. Preserving the wording
 * is a deliberate fidelity choice over a clearer modern one, because transformation rule T8 carries
 * user-visible strings across character for character and admits no improvement of message text -- any
 * rewording would be a behavioural divergence requiring registration in
 * `docs/architecture/cobol-to-service-traceability.md`. Written down because it otherwise reads as an
 * oversight to the next person who sees a mainframe term in a browser.
 *
 * Assumptions: the band carries the REFERENCE's sentence and never the service's own `message`. The
 * screen's refusal mapping is explicit about this: a service sentence is not a baseline literal, so
 * painting it on row 23 would put text on the parity surface that no golden master contains.
 * @returns {Promise<void>} Resolves once the failure sentence has been asserted.
 */
async function keepsTheReferenceFailureSentence(): Promise<void> {
  const serviceOwnWording = 'the orchestrator rejected the execution';
  vi.mocked(submitTransactionReport).mockRejectedValue(
    new ApiRequestError(
      'PROBLEM',
      500,
      apiError({ status: 500, message: serviceOwnWording }),
      serviceOwnWording,
    ),
  );
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the reference's failure sentence to reach the row-23 band.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(bandText()).toBe(REPORT_MESSAGES.UNABLE_TO_WRITE_TDQ_JOBS);
    },
  );
  expect(renderedText()).not.toContain(serviceOwnWording);
}

/**
 * Asserts no orchestration or mainframe transport detail reaches the rendered page or the payload.
 *
 * ⚠️ Assumptions: three distinct kinds of leak are checked, because they arrive by three different
 * routes. A resource identifier in full would come from the submission body -- and
 * `ui/src/api/types.ts` records that the `executionArn` member was WITHDRAWN from the published
 * contract precisely because it carries the account identifier and region of the deployment that ran
 * the report. A queue or state-machine name would come from a client that knew its transport. And a
 * mainframe dataset qualifier would come from the job-control text the reference builds at
 * `app/cbl/CORPT00C.cbl` L84-L90, which names a procedure library that has no meaning in a browser --
 * if it ever appeared here it would be a finding rather than a feature.
 *
 * Assumptions: the dataset check is a QUALIFIER pattern rather than a full name, which is both stronger
 * and cleaner: it catches any dataset from that high-level qualifier, not just the one the reference
 * happens to name, and it keeps a mainframe dataset name out of this file's own source.
 * @returns {Promise<void>} Resolves once every absence has been asserted.
 */
async function namesNoOrchestrationDetailAnywhere(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the run to have been started, so the run region is painted.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );

  const painted = renderedText();
  const payload = JSON.stringify(submittedRequest());
  const forbidden = [
    // WHY : Assumptions: the prefix every resource identifier in full begins with. It is checked rather
    //       than the withdrawn member's name, because the harm is the VALUE reaching a browser -- it
    //       carries the deploying account and region -- so a reinstated handle under any member name
    //       would be caught.
    /arn:aws:/iu,
    // WHY : Assumptions: the two queue-flavour markers, which a client aware of its transport would
    //       carry -- a FIFO suffix or the queue service's own name.
    /\.fifo\b/iu,
    /\bsqs\b/iu,
    // WHY : Assumptions: the orchestrator's own service name and its start operation.
    /\bstate[- ]?machine\b/iu,
    /\bstartexecution\b/iu,
    // WHY : Assumptions: the high-level qualifier of the reference's mainframe datasets, from the
    //       job-control text at `app/cbl/CORPT00C.cbl` L84-L90.
    /\bAWS\.M2\./iu,
  ];
  for (const pattern of forbidden) {
    expect(painted).not.toMatch(pattern);
    expect(payload).not.toMatch(pattern);
  }

  /*
   * WHY : Assumptions: the started outcome's own submission member set is checked directly, so a future
   *       contract that reintroduced the withdrawn handle would fail here rather than only showing up
   *       as rendered text if some screen happened to paint it. The arm is NARROWED rather than
   *       asserted, because `ReportSubmissionOutcome` is a three-way union discriminated on `outcome`
   *       and only the started arm carries a submission at all.
   */
  const outcome = startedRun();
  if (outcome.outcome !== 'STARTED') {
    throw new Error('the started-run fixture no longer reports the started outcome');
  }
  expect(Object.keys(outcome.submission)).not.toContain('executionArn');
}

/**
 * Asserts the screen renders no cardholder value of any kind.
 *
 * ⚠️ Assumptions: there is nothing on this screen to mask, and that is the point of asserting it. The
 * report request and the run status published by `ui/src/api/reporting.ts` declare no account number,
 * no primary account number, no national identifier, no government-issued identifier and no card
 * verification value -- the request is a report type, two date bounds and a confirmation character.
 * A regression that widened one of those contracts, or a screen that started echoing a selection
 * carried in from elsewhere, is what this case is here to catch.
 *
 * Assumptions: the check is on SHAPES rather than on field names, because a leak would arrive as a
 * rendered value and not as a label -- a long digit run for a card number, and the grouped form for a
 * national identifier.
 * @returns {Promise<void>} Resolves once the absences have been asserted.
 */
async function rendersNoCardholderValue(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the run to have been started, so every region this screen paints is present.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );

  const painted = renderedText();
  // WHY : Assumptions: eleven or more consecutive digits, which is the account identifier's own width --
  //       `PIC 9(11)` -- and below the sixteen of a card number, so the bound catches both.
  expect(painted).not.toMatch(/\d{11}/u);
  // WHY : Assumptions: the grouped three-two-four form a national identifier is written in.
  expect(painted).not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/u);
}

/** Registers the cases about the submission mechanism. */
function submissionMechanismCases(): void {
  beforeEach(anchorObservedServerInstant);
  afterEach(discardObservedServerInstant);

  it(
    'starts the run through the reporting client alone',
    startsTheRunThroughTheReportingClientAlone,
  );
  it('calls the client once per confirmation', callsTheClientOncePerConfirmation);
  it('keeps the reference failure sentence', keepsTheReferenceFailureSentence);
  it('names no orchestration detail anywhere', namesNoOrchestrationDetailAnywhere);
  it('renders no cardholder value', rendersNoCardholderValue);
}

describe('the submission mechanism changed and the sentence did not', submissionMechanismCases);

/**
 * Asserts the row-24 legend advertises exactly the two keys the reference dispatches, in order.
 *
 * Assumptions: the mapset paints the legend as ONE `LENGTH=23 COLOR=YELLOW` literal at
 * `app/bms/CORPT00.bms` L222-L226, and the two spaces between its halves are in the source: the two
 * labels measure fourteen and seven characters, and fourteen plus two plus seven is the declared
 * twenty-three. The catalog splits the literal so each half sits on the control that performs it, and
 * this case reconstitutes the arithmetic rather than retyping the literal -- which is what keeps the
 * exact separator asserted without a second copy of the text in the tree.
 *
 * Assumptions: the count is asserted as EXACTLY two. `EVALUATE EIBAID` at `app/cbl/CORPT00C.cbl`
 * L184-L195 has an arm for `DFHENTER` and an arm for `DFHPF3` and sends everything else to the shared
 * invalid-key sentence, so a third control would advertise a key the mapset does not paint and the
 * program does not honour.
 * @returns {void} Nothing; the case asserts.
 * @throws {Error} If the legend rendered fewer than its two controls, which the length
 *   expectation normally reports first; the throw exists so the two narrowed values are sound
 *   rather than asserted non-null, which `noUncheckedIndexedAccess` and the lint gate require.
 */
function advertisesExactlyTheTwoKeysTheReferenceDispatches(): void {
  render(reportsTree());

  const controls = within(keyLegend()).getAllByRole('button');
  expect(controls).toHaveLength(2);
  const [enterControl, backControl] = controls;
  if (enterControl === undefined || backControl === undefined) {
    throw new Error('the row-24 legend rendered fewer than its two controls');
  }
  expect(enterControl).toHaveTextContent(REPORTS_KEY_LABELS.ENTER);
  expect(backControl).toHaveTextContent(REPORTS_KEY_LABELS.PFK03);

  // WHY : Assumptions: the declared field width is 23 and the separator is two spaces, so the two label
  //       lengths plus two must total exactly 23 -- the arithmetic the mapset's own `LENGTH=23` at L224
  //       fixes. A label that had gained or lost a character would fail here.
  const separatorWidth = 2;
  expect(REPORTS_KEY_LABELS.ENTER).toHaveLength(14);
  expect(REPORTS_KEY_LABELS.PFK03).toHaveLength(7);
  expect(REPORTS_KEY_LABELS.ENTER.length + separatorWidth + REPORTS_KEY_LABELS.PFK03.length).toBe(
    23,
  );
}

/**
 * Asserts the two legend controls carry the emphasis the design system maps to their actions.
 *
 * Assumptions: the mapping is published as `PRIMARY_ACTION_AIDS` in `ui/src/layout/PfKeyBar.tsx`, which
 * fixes primary emphasis to Enter and PF5 and the default to every other key. This screen paints Enter
 * and PF3, so exactly one control takes the primary emphasis -- and the expectation is derived from
 * that published list rather than restated, so a change to the mapping reaches this case.
 *
 * Trade-offs: the emphasis is read from the design system's own class names, which is an implementation
 * detail of the component library rather than a public contract. It is accepted because the alternative
 * -- asserting a resolved colour -- would put a literal value in this file, which AAP section 0.3.2
 * forbids outright, and because the class is what the library's own theming acts on.
 * @returns {void} Nothing; the case asserts.
 * @throws {Error} If the legend rendered fewer than its two controls, for the same reason the
 *   case above throws rather than asserting non-null.
 */
function givesEnterThePrimaryEmphasisAndBackTheDefault(): void {
  render(reportsTree());

  const controls = within(keyLegend()).getAllByRole('button');
  const [enterControl, backControl] = controls;
  if (enterControl === undefined || backControl === undefined) {
    throw new Error('the row-24 legend rendered fewer than its two controls');
  }

  const enterAid: CicsAid = 'ENTER';
  const backAid: CicsAid = 'PFK03';
  expect(PRIMARY_ACTION_AIDS).toContain(enterAid);
  expect(PRIMARY_ACTION_AIDS).not.toContain(backAid);

  expect(enterControl.className).toContain('ant-btn-primary');
  expect(backControl.className).toContain('ant-btn-default');
}

/**
 * Asserts Enter and PF3 are bound to real keyboard events.
 *
 * ⚠️ Alternatives Considered: driving the two actions only through the legend controls, which is
 * simpler and reads the same. Rejected because clicking exercises the BAR and not the BINDING -- a case
 * that only clicks passes in full while every keyboard binding in the application is broken, and the
 * 3270 original was keyboard-only, so the key press is the fidelity-bearing path. The pointer path is
 * covered separately by {@link dispatchesTheSameTwoActionsFromTheLegendControls}, so neither is left
 * unasserted.
 *
 * Assumptions: the key for each attention identifier is derived by `pressPfKey` from the hook's own
 * published table and round-tripped back through its resolver before being pressed, so this case cannot
 * drift from the table the application dispatches on.
 * @returns {Promise<void>} Resolves once both keys have been driven.
 */
async function bindsEnterAndBackToRealKeyEvents(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the keyed Enter to have dispatched the turn.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );

  await pressPfKey(operator, 'PFK03');
  await waitFor(
    /**
     * Waits for the transfer to land on the main-menu route.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByTestId(LANDED_ROUTE_TEST_ID)).toHaveTextContent(
        menuRouteForTheReferenceProgram(),
      );
    },
  );
}

/**
 * Asserts the legend controls dispatch the same two actions the keys do.
 *
 * Assumptions: the back control is exercised here rather than the submit control, because the submit
 * control opens a confirmation dialogue and this case is about the dispatch reaching the same handler
 * -- the dialogue's own path is covered where the range is asserted. The action asserted is therefore
 * the transfer, which is observable without a second surface.
 * @returns {Promise<void>} Resolves once the clicked transfer has landed.
 */
async function dispatchesTheSameTwoActionsFromTheLegendControls(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());

  const controls = within(keyLegend()).getAllByRole('button');
  const backControl = controls[1];
  if (backControl === undefined) {
    throw new Error('the row-24 legend rendered no back control');
  }
  await operator.click(backControl);

  await waitFor(
    /**
     * Waits for the clicked transfer to land on the main-menu route.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByTestId(LANDED_ROUTE_TEST_ID)).toHaveTextContent(
        menuRouteForTheReferenceProgram(),
      );
    },
  );
}

/**
 * Asserts no function key beyond Enter and PF3 does anything but report the shared refusal.
 *
 * ⚠️ Assumptions: the five keys tried are the ones the OTHER mapsets in this application bind -- PF4 to
 * Clear, PF5 to Save, PF7 and PF8 to page backward and forward, PF12 to Cancel -- so each is a key an
 * operator moving between screens would plausibly press here. This mapset paints none of them, and
 * notably paints NO CLEAR KEY at all, which is worth stating because this screen has ten inputs and is
 * the one screen where a Clear would be most useful. The legend advertises two keys and the program
 * dispatches two; `PfKeyBar` takes a per-screen descriptor array precisely because the key sets differ
 * this much across mapsets.
 *
 * Assumptions: each press must both report the shared invalid-key sentence AND leave the route and the
 * transport untouched, because a key that navigated or submitted while also painting the refusal would
 * satisfy a message-only assertion.
 * @returns {Promise<void>} Resolves once every unbound key has been tried.
 */
async function bindsNoFunctionKeyBeyondEnterAndBack(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());

  const unboundKeys = ['{F4}', '{F5}', '{F7}', '{F8}', '{F12}'] as const;
  const sharedRefusal = COMMON_MESSAGES.INVALID_KEY.text.replace(/ +$/u, '');
  const captions = Object.values(REPORT_TYPE_PROMPTS);

  for (const [attempt, key] of unboundKeys.entries()) {
    /*
     * WHY : Assumptions: the band is cleared before each key by selecting a DIFFERENT report type, and
     *       cycling through the three captions is what guarantees the type actually changes. The screen
     *       clears the sentence when a selection is RECORDED, so re-clicking the type already chosen is
     *       a no-op and would leave the previous key's refusal on the band -- which is how this loop
     *       first failed, on its second iteration, reporting the sentence it had just correctly
     *       produced as though the precondition were the defect.
     */
    const caption = captions[attempt % captions.length];
    if (caption === undefined) {
      throw new Error('the catalog published no report-type captions to cycle through');
    }
    await chooseReportType(operator, caption);
    expect(bandText()).toBe('');

    await operator.keyboard(key);
    await waitFor(
      /**
       * Waits for the shared refusal to reach the row-23 band for this key.
       * @returns {void} Nothing; the expectation throws until it holds.
       */
      (): void => {
        expect(bandText()).toBe(sharedRefusal);
      },
    );
    // WHY : Assumptions: the reports route is still the settled address, so none of the five keys
    //       navigated -- which is the half of the contract a band assertion alone would miss.
    expect(screen.queryByTestId(LANDED_ROUTE_TEST_ID)).not.toBeInTheDocument();
  }

  expect(vi.mocked(submitTransactionReport)).not.toHaveBeenCalled();
}

/** Registers the cases about the two bound keys. */
function keyBindingCases(): void {
  beforeEach(anchorObservedServerInstant);
  afterEach(discardObservedServerInstant);

  it(
    'advertises exactly the two keys the reference dispatches',
    advertisesExactlyTheTwoKeysTheReferenceDispatches,
  );
  it(
    'gives Enter the primary emphasis and back the default',
    givesEnterThePrimaryEmphasisAndBackTheDefault,
  );
  it('binds Enter and back to real key events', bindsEnterAndBackToRealKeyEvents);
  it(
    'dispatches the same two actions from the legend controls',
    dispatchesTheSameTwoActionsFromTheLegendControls,
  );
  it('binds no function key beyond Enter and back', bindsNoFunctionKeyBeyondEnterAndBack);
}

describe('the screen binds exactly the two keys the reference dispatches', keyBindingCases);

/**
 * Asserts the route the application mounts this screen at is not administratively gated.
 *
 * ⚠️ Assumptions: `app/cpy/COMEN02Y.cpy` lists `'Transaction Reports'` as main-menu option 9 at L76 with
 * `'CORPT00C'` at L77 and its user-type qualifier at L78 set to `'U'`. All eleven of that table's
 * options carry `'U'` and NOT ONE carries `'A'`, so no main-menu destination is admin-only and this one
 * is reachable by any signed-on operator. The router's own table is the target expression of that fact,
 * and it is read here rather than a claim being constructed -- `ui/src/hooks/useAuth.ts` publishes no
 * group setter and there is no context provider anywhere, so a test cannot grant itself authority in
 * any case, which is the property that keeps the signed group claim the only source of it.
 * @returns {void} Nothing; the case asserts.
 * @throws {Error} If the router publishes no route for this program, which is a router
 *   regression rather than a failed expectation about the guard.
 */
function isReachableWithoutAnAdministrativeClaim(): void {
  const entry = ROUTE_TABLE.find(
    /**
     * Selects the row this screen is mounted at.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One row of the reachability graph.
     * @returns {boolean} `true` when the row replaces this program.
     */
    (candidate: (typeof ROUTE_TABLE)[number]): boolean =>
      candidate.program === REPORTS_PROGRAM_NAME,
  );
  if (entry === undefined) {
    throw new Error(`ui/src/router.tsx publishes no route for ${REPORTS_PROGRAM_NAME}`);
  }
  expect(entry.access).toBe('authenticated');
  expect(entry.access).not.toBe('administrative');
}

/**
 * Asserts an operator with no administrative claim can key and submit a report.
 *
 * Assumptions: reachability alone is not the whole claim -- a screen could be mounted behind an
 * ordinary guard and still refuse to operate -- so this case drives a complete turn and asserts the
 * submission was issued.
 * @returns {Promise<void>} Resolves once the turn has been submitted.
 */
async function isOperableWithoutAnAdministrativeClaim(): Promise<void> {
  arrangeStartedRun();
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the ordinary operator's turn to have been submitted.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(vi.mocked(submitTransactionReport)).toHaveBeenCalledTimes(1);
    },
  );
}

/**
 * Asserts a refusal marks the one control it names and leaves every other control unmarked.
 *
 * ⚠️ Refactoring Rationale: the reference gates its field highlighting on the pseudo-conversational
 * re-entry discriminator -- the templated copybook `app/cpy/CSSETATY.cpy` L17-L27 tests
 * `CDEMO-PGM-REENTER`, declared at `app/cpy/COCOM01Y.cpy` L29-L31, before it colours anything. AAP
 * section 0.7.1 establishes that the discriminator DISAPPEARS ENTIRELY in the target, because a
 * stateless handler has no first-entry-versus-re-entry distinction to make. The mark is therefore
 * driven purely by the response body, which is why this case arranges a refusal and asserts the
 * resulting marks rather than arranging a turn count.
 *
 * ⚠️ Assumptions: this screen renders NO `'*'` marker beside a refused field, and the absence is
 * fidelity rather than omission. `app/cbl/CORPT00C.cbl` does not `COPY CSSETATY` -- its copy list at
 * L138-L149 names `COCOM01Y`, `CORPT00`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CVTRA05Y`, `DFHAID` and
 * `DFHBMSCA` and nothing else -- and the program contains no `DFHRED` move and no `MOVE '*'` anywhere.
 * It marks a field solely by moving `-1` into that field's length member, at L264, L271, L278, L285,
 * L292 and L299, which places the cursor. Twelve other screens in this tree do render the marker
 * because their own programs use that copybook; asserting one here would demand behaviour this
 * reference does not have.
 *
 * Assumptions: the unmarked half is the assertion that proves the mapping is PER FIELD rather than
 * screen-wide. A screen that marked everything on any refusal would satisfy the first expectation and
 * fail this one, which is exactly the regression worth catching.
 * @returns {Promise<void>} Resolves once the marks have been asserted.
 */
async function marksOnlyTheControlTheRefusalNames(): Promise<void> {
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);

  const start = REPORTS_CAPTIONS.startDate;
  const end = REPORTS_CAPTIONS.endDate;
  fillDatePart(start, 'day', VALID_RANGE.startDay);
  fillDatePart(start, 'year', VALID_RANGE.startYear);
  fillDatePart(end, 'month', VALID_RANGE.endMonth);
  fillDatePart(end, 'day', VALID_RANGE.endDay);
  fillDatePart(end, 'year', VALID_RANGE.endYear);
  await answerAndSubmit(operator, 'Y');

  const refused = datePartControl(start, 'month');
  await waitFor(
    /**
     * Waits for the refused control to be marked.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(refused).toHaveAttribute('aria-invalid', 'true');
    },
  );

  /*
   * WHY : Assumptions: the refusal's own sentence is reachable from the control through
   *       `aria-describedby`, which is how the design system associates a `Form.Item`'s help text with
   *       the field it explains -- so the association is asserted rather than merely the presence of
   *       the sentence somewhere on the page.
   */
  const describedBy = refused.getAttribute('aria-describedby') ?? '';
  expect(describedBy.length).toBeGreaterThan(0);

  // WHY : Assumptions: the five other date parts and the confirmation are asserted UNMARKED. The
  //       reference stops at the first failing edit, so exactly one control may carry the mark.
  for (const [caption, part] of [
    [start, 'day'],
    [start, 'year'],
    [end, 'month'],
    [end, 'day'],
    [end, 'year'],
  ] as const) {
    expect(datePartControl(caption, part)).not.toHaveAttribute('aria-invalid');
  }
  expect(confirmationControl()).not.toHaveAttribute('aria-invalid');

  /*
   * WHY : Assumptions: the design system's own error state is counted too, and exactly once. The
   *       accessible attribute and the visual state are set by two different props on the same
   *       `Form.Item`, so a screen that set one and not the other would announce a refusal it did not
   *       paint, or paint one it did not announce.
   */
  expect(window.document.querySelectorAll('.ant-form-item-has-error')).toHaveLength(1);
}

/**
 * Asserts a refusal the SERVICE reports lands on the control the screen maps it to.
 *
 * Purpose: the client-side edits produce their own marks, asserted above; this is the other half --
 * a problem document's per-field entries distributed onto controls. The screen owns that mapping,
 * because `ui/src/layout/MessageBand.tsx` is deliberately presentational and declares no `ApiError` in
 * its props at all, so nothing upstream will distribute them and a screen that did not do it here
 * would drop every one.
 *
 * Assumptions: the entry names `confirm`, which is the contract's own name for the confirmation member
 * of the request and one the screen maps to a control it renders. The band still carries the
 * reference's failure sentence rather than the entry's, which is the division the screen's refusal
 * mapping records: entries name controls, and the band carries baseline text.
 * @returns {Promise<void>} Resolves once the service-named mark has been asserted.
 */
async function distributesAServiceRefusalOntoTheControlItNames(): Promise<void> {
  vi.mocked(submitTransactionReport).mockRejectedValue(
    refusedSubmission([fieldError('confirm', REPORT_MESSAGES.UNABLE_TO_WRITE_TDQ_JOBS)]),
  );
  const operator = userEvent.setup();
  render(reportsTree());
  await chooseReportType(operator, REPORT_TYPE_PROMPTS.custom);
  fillCustomRange(VALID_RANGE);
  await answerAndSubmit(operator, 'Y');

  await waitFor(
    /**
     * Waits for the service-named control to be marked.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(confirmationControl()).toHaveAttribute('aria-invalid', 'true');
    },
  );
  expect(bandText()).toBe(REPORT_MESSAGES.UNABLE_TO_WRITE_TDQ_JOBS);
  // WHY : Assumptions: the six date parts are unmarked, because the document named one member and the
  //       screen maps one member to one control.
  expect(datePartControl(REPORTS_CAPTIONS.startDate, 'month')).not.toHaveAttribute('aria-invalid');
}

/** Registers the cases about the session, the route and per-field marks. */
function sessionRoutingAndMarkCases(): void {
  beforeEach(anchorObservedServerInstant);
  afterEach(discardObservedServerInstant);

  it('is reachable without an administrative claim', isReachableWithoutAnAdministrativeClaim);
  it('is operable without an administrative claim', isOperableWithoutAnAdministrativeClaim);
  it('marks only the control the refusal names', marksOnlyTheControlTheRefusalNames);
  it(
    'distributes a service refusal onto the control it names',
    distributesAServiceRefusalOntoTheControlItNames,
  );
}

describe('session, routing and per-field refusals', sessionRoutingAndMarkCases);
