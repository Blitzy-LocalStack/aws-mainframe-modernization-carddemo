/**
 * @file Component tests for the transaction-type list and maintenance screen,
 * `ui/src/screens/refTypeList/index.tsx` — the browser replacement for BMS mapset
 * `COTRTLI` and program `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`, mounted by
 * `ui/src/router.tsx` at `/reference/transaction-types`.
 *
 * Purpose
 * -------
 * This file is the ONLY automated verification this screen receives, and that is a
 * property of the runner rather than a choice. `tests/README.md` §1.1 states it in
 * terms this screen inherits twice over: "Online `CO*` CICS programs cannot run
 * end-to-end without a CICS runtime (absent on the runner); only their extractable
 * field-validation logic is unit-tested." `COTRTLIC` additionally needs Db2, which the
 * runner also lacks, so no golden master exists for it and none can be produced here —
 * while AAP §0.9.4 names the three decoupled extensions among the user's
 * acceptance-criteria flows, and this is the Db2 reference-data one. Every invariant
 * below is therefore asserted against the baseline source directly: the mapset for field
 * widths and legend text, the program for row arity, key set, action-code domain and
 * message text, and `ui/src/messages/messages.ts` for the strings themselves.
 *
 * Parameters (module analogue)
 * ---------------------------
 * None. The module exports nothing and takes nothing. Its inputs are the frozen baseline
 * measurements cited at each assertion, the message catalog it imports rather than
 * retypes, and the fixtures each case builds from the shared builders in `./setup`.
 *
 * Returns (module analogue)
 * ------------------------
 * Nothing. Loading it registers seven suites with the runner; the outcome is the suite
 * result.
 *
 * Exceptions (module analogue)
 * ---------------------------
 * Every failure surfaces as a failed assertion. Two helpers raise deliberately:
 * `pressPfKey` from `./setup` throws for an attention identifier with no browser key,
 * and `seedSession` throws when the sign-on exchange it drives does not establish a
 * session. Neither is caught here — either is a broken arrangement rather than a
 * finding about the screen.
 *
 * The documentation obligation, and why it is double-anchored
 * ----------------------------------------------------------
 * User Rule 1 (Explainability) requires a docstring on every function and module entry
 * point stating purpose, parameters, returns and exceptions, and an inline comment
 * justifying every non-obvious decision under one of its four named categories.
 * `tests/README.md` §12 imposes the identical obligation on "every new test, fixture
 * builder, helper, mock, and runner routine" and calls it "a hard review gate". The two
 * agree, so this file extends an established house convention rather than importing a
 * foreign one, and it conforms to `docs/CODE_DOCUMENTATION_STANDARD.md` §TypeScript —
 * which is why every `@param` and `@returns` below carries its `{Type}` even though the
 * signature already states it.
 *
 * Three conventions here are fixed by this repository's own machine gates rather than by
 * preference, and each is recorded because the intuitive alternative fails a gate:
 *
 * - The test API is IMPORTED from `vitest`. `ui/tsconfig.json` sets `"types": []` and
 *   documents that the empty list "is what obliges a test to import `describe`, `it` and
 *   `expect` from 'vitest' by name -- with nothing declared ambiently, an omitted import
 *   fails to compile on the symbol it omitted". `ui/vitest.config.ts` does set
 *   `globals: true`, but an injected global is not a DECLARED one, so `tsc --noEmit`
 *   rejects the bare form. Both sibling suites — `ui/src/test/usePagedQuery.test.ts` and
 *   `ui/src/router.test.tsx` — import the same way.
 * - Rationale labels are written in the four canonical PLURAL forms. `config/rule1/rule1_gate.py`
 *   admits only `Assumptions:`, `Alternatives Considered:`, `Refactoring Rationale:` and
 *   `Trade-offs:`, and its `singular` detector reports a singular stem followed by a colon
 *   as a violation. That gate runs in `.github/workflows/services-ci.yml` and
 *   `infra-ci.yml`.
 * - Cases are named function declarations handed to `it`, and every arrow function
 *   carries a block. `ui/eslint.config.js` sets `jsdoc/require-jsdoc` with
 *   `publicOnly: false` and a `* > ArrowFunctionExpression` context, so an undocumented
 *   inline callback fails `eslint . --max-warnings=0`, which `ui-ci.yml` gates on.
 *
 * Scope, and what deliberately sits elsewhere
 * -------------------------------------------
 * `app/**`, `tests/**`, `scripts/**` and `samples/**` are reference-only (AAP §0.2.2):
 * read at every citation below, never written. Two neighbouring contracts are owned by
 * other suites and are not re-asserted here: the PF13–PF24 aliasing and the
 * Clear/PA1/PA2 no-binding contract belong to the shared hook's own tests, and the route
 * table plus the `RequireAdmin` admit/refuse decision belong to `ui/src/router.test.tsx`,
 * which exercises them against a STUB screen. What this file adds to the second is the
 * half a stub cannot show — that a refused non-administrator never reaches the reference
 * service at all.
 */

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { matchPath, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import type { UserEvent } from '@testing-library/user-event';

import { ApiRequestError, isConflictFailure, isTransientFailure } from '../api/client';
import {
  deleteTransactionType,
  listTransactionTypes,
  replaceTransactionType,
} from '../api/reference';
import * as authModule from '../hooks/useAuth';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP, COGNITO_GROUPS_CLAIM } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_IDS } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  ACCESS_DENIED_NOT_AUTHORIZED,
  FIELD_VALIDATION_SUFFIXES,
  FIELD_VALIDATION_SUFFIX_SOURCES,
  INVALID_KEY_PRESSED,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  REDACTED_DIAGNOSTICS,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  normaliseMessageBandValue,
} from '../messages/messages';
import { REF_TYPE_EDIT_PATH, REF_TYPE_LIST_PATH } from '../router';
import { RequireAdmin } from '../routes/guards';
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  BLANK_FIELD_MARKER_TEST_ID,
  BUSY_ANNOUNCEMENT_TEST_ID,
} from '../layout/fieldHelp';
import { FIELD_ERROR_TOKENS, TARGET_SIZE_AA_MINIMUM } from '../theme/tokens';
import RefTypeListScreen, {
  ACTION_CODE_LENGTH,
  DESCRIPTION_LENGTH,
  REF_TYPE_ADD_ROUTE,
  REF_TYPE_LIST_KEY_LABELS,
  REF_TYPE_LIST_LABELS,
  REF_TYPE_PAGE_SIZE,
  REF_TYPE_ROW_ACTION_CODES,
  TYPE_CODE_LENGTH,
} from '../screens/refTypeList';
import type { ApiError, PageResponse, TransactionType } from '../api/types';
import type { RedactedDiagnostic } from '../messages/messages';
import type { CicsAid } from '../layout/usePfKeys';
import {
  LEADING_CURSOR,
  TRAILING_CURSOR,
  apiError,
  conflictProblem,
  expectMaxLength,
  fieldError,
  pageResponse,
  pressPfKey,
  renderInAppShell,
  seedSession,
} from './setup';

// WHY : Assumptions: the reference client is replaced wholesale rather than intercepted at the
//       transport, because `msw` is absent from this package and no case here is about HTTP. The
//       three operations named are exactly the three
//       `ui/src/screens/refTypeList/index.tsx` imports, so a partial double is complete for this
//       subject; the module's other twenty operations belong to screens this file does not mount.
//       Alternatives Considered: the recording transport in `ui/src/test/apiHarness.ts`, which the
//       session arrangement installs anyway. Rejected because it answers by URL, so a case
//       asserting "the reference service was never called" would be asserting about a queue rather
//       than about a function, and the refusal cases would have to encode a problem document as a
//       wire body instead of rejecting with the normalised failure a screen actually catches.
vi.mock(
  '../api/reference',
  /**
   * Substitutes the reference service with the three operations this screen calls.
   * @returns {{ listTransactionTypes: unknown, replaceTransactionType: unknown, deleteTransactionType: unknown }}
   *   The substituted module shape, each operation a fresh spy.
   */
  () => ({
    listTransactionTypes: vi.fn(),
    replaceTransactionType: vi.fn(),
    deleteTransactionType: vi.fn(),
  }),
);

/**
 * Row arity one page of this browse carries.
 *
 * WHY : Assumptions: seven, and it is measured twice rather than chosen once.
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L60 declares
 *       `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`, which drives the row loops, and
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms` declares exactly seven action cells,
 *       `TRTSEL1` at L133 through `TRTSEL7` at L259. The screen's own
 *       `REF_TYPE_PAGE_SIZE` is compared against this constant below rather than trusted, so a
 *       screen that quietly widened its page fails here instead of paging differently from the
 *       reference.
 */
const MEASURED_PAGE_SIZE = 7;

/**
 * Type code the fixtures use for the first row, and the row most cases act on.
 *
 * WHY : Assumptions: two digits, because `TR_TYPE CHAR(2)` in
 *       `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 and `TRTYPE LENGTH=2` at
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L89 both fix the width, and the program's
 *       filter edit at L1116 requires the filter form to be two DIGITS. A fixture keyed with
 *       letters would pass every assertion here while describing a row the service cannot hold.
 */
const FIRST_TYPE_CODE = '01';

/** Type code the fixtures use for the second row. */
const SECOND_TYPE_CODE = '02';

/**
 * Description the second fixture row stores, before any edit.
 *
 * WHY : Assumptions: upper-case letters and no punctuation, because the program's description edit
 *       admits only `LIT-ALL-ALPHA-FROM-X` and `LIT-NUMBERS` — the alphabet and digit constants at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L68, L70 and L72 — plus the blank the
 *       trim ignores. A stored value outside that domain would make the unchanged-row cases fail on
 *       the validator rather than on the branch they are about.
 */
const SECOND_DESCRIPTION = 'PAYMENT';

/**
 * A status the transport classifies as a transient condition.
 *
 * Assumptions: 503 is chosen from `ui/src/api/client.ts`'s own transient set — 408, 429, 502, 503 and
 * 504 — rather than 500, which that module deliberately excludes. The exclusion is what keeps the
 * fault case's abend sentence reachable, so picking 500 here would make the two cases assert the same
 * arm and one of them would pass for the wrong reason.
 */
const TRANSIENT_GATEWAY_STATUS = 503;

/**
 * The status a failure carries when no service answered at all.
 *
 * Assumptions: zero is the transport's own marker for that condition, not an absent value — it
 * synthesises a problem document and leaves the status there, because a dropped connection has no HTTP
 * status to report.
 */
const NO_TRANSPORT_STATUS = 0;

/**
 * Builds one page of transaction-type rows, keyed with sequential two-digit codes.
 *
 * WHY : Assumptions: the `version` member is present on every row and is not decoration.
 *       `TransactionTypeReplaceRequest` in `ui/src/api/types.ts` requires it, so the save cases
 *       assert the screen replays the version it was GIVEN rather than one it invented — which is
 *       how the optimistic-concurrency refusal becomes reachable at all.
 * @param {number} count - How many rows the page carries.
 * @returns {readonly TransactionType[]} The rows, `01` upward, each with a distinct description.
 */
function refTypeRows(count: number): readonly TransactionType[] {
  const rows: TransactionType[] = [];
  for (let ordinal = 1; ordinal <= count; ordinal += 1) {
    rows.push({
      typeCd: ordinal < 10 ? `0${String(ordinal)}` : String(ordinal),
      description: `DESCRIPTION ${String(ordinal)}`,
      version: ordinal,
    });
  }
  return rows;
}

/**
 * The two-row page most non-paging cases act on.
 * @returns {readonly TransactionType[]} Row `01` and row `02`, the second carrying
 *   {@link SECOND_DESCRIPTION} so the unchanged-value branch is reachable.
 */
function twoRowPage(): readonly TransactionType[] {
  return [
    { typeCd: FIRST_TYPE_CODE, description: 'PURCHASE', version: 1 },
    { typeCd: SECOND_TYPE_CODE, description: SECOND_DESCRIPTION, version: 3 },
  ];
}

/**
 * Reports the reference browse spy, typed as the operation it stands in for.
 * @returns {ReturnType<typeof vi.mocked<typeof listTransactionTypes>>} The browse spy.
 */
function browseSpy(): ReturnType<typeof vi.mocked<typeof listTransactionTypes>> {
  return vi.mocked(listTransactionTypes);
}

/**
 * Reports the reference replace spy, typed as the operation it stands in for.
 * @returns {ReturnType<typeof vi.mocked<typeof replaceTransactionType>>} The replace spy.
 */
function replaceSpy(): ReturnType<typeof vi.mocked<typeof replaceTransactionType>> {
  return vi.mocked(replaceTransactionType);
}

/**
 * Reports the reference delete spy, typed as the operation it stands in for.
 * @returns {ReturnType<typeof vi.mocked<typeof deleteTransactionType>>} The delete spy.
 */
function deleteSpy(): ReturnType<typeof vi.mocked<typeof deleteTransactionType>> {
  return vi.mocked(deleteTransactionType);
}

/**
 * Builds the normalised failure a screen catches, in the shape the shared client raises.
 *
 * WHY : Assumptions: an `ApiRequestError` instance and not a bare object, because
 *       `isConflictFailure` in `ui/src/api/client.ts` narrows on the CLASS before reading the
 *       status. A plain object with `status: 409` is not a conflict as far as the screen is
 *       concerned, so a fixture shaped that way would send every refusal down the generic arm and
 *       the conflict cases would pass for the wrong reason.
 * @param {number} status - HTTP status the failure carries.
 * @param {ApiError} problem - The problem document the transport normalised.
 * @param {string} diagnostic - Developer-facing text, never rendered.
 * @returns {ApiRequestError} The failure, ready to reject a spy with.
 */
function refusal(status: number, problem: ApiError, diagnostic: string): ApiRequestError {
  return new ApiRequestError('PROBLEM', status, problem, diagnostic);
}

/**
 * The five sentences this screen publishes on the advisory line rather than the outcome line.
 *
 * WHY : ⚠️ Assumptions: the five are named ONE BY ONE here rather than derived, and that is the whole
 *       value of the list. `ui/src/screens/refTypeList/index.tsx` decides the line by reading each
 *       catalogue entry's `field` member, so a test that re-ran the same lookup would assert that the
 *       screen agrees with itself and would follow a mistake in the catalogue without complaint. These
 *       five are the entries `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1504-L1579 sends to
 *       `INFOMSGO` — the standing action prompt at L1512, the two arming prompts at L1520 and L1528,
 *       and the two completion sentences at L1536 and L1544 — and `COTRTLI.bms` L294-L299 declares
 *       that field `POS=(21,19) LENGTH=45 COLOR=NEUTRAL`, one row above the `ERRMSG` line every other
 *       sentence uses. Alternatives Considered: asserting only the outcome line and ignoring which
 *       line an advisory reached. Rejected because it is precisely the distinction the mapset draws.
 */
const ADVISORY_LINE_SENTENCES: readonly string[] = [
  STATUS_MESSAGES.COTRTLIC.WS_INFORM_REC_ACTIONS.text,
  STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text,
  STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE.text,
  STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text,
  STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE_SUCCESS.text,
];

/**
 * Locates the shell's row-23 outcome band.
 * @returns {HTMLElement} The band element.
 * @throws {Error} If no band is rendered, which Testing Library raises. Absence means the subject
 *   was mounted outside the shell, since the screen delegates its message through `useShellSlot`.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_IDS.error);
}

/**
 * Locates the shell's advisory line, the `INFOMSG` field's channel.
 * @returns {HTMLElement} The advisory band element.
 * @throws {Error} If the screen published no advisory member at all, in which case the shell renders
 *   no second band and Testing Library raises. That is a finding: `COTRTLI.bms` L294-L299 declares
 *   the field on every send, so the line is reserved on every turn.
 */
function advisoryBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_IDS.information);
}

/**
 * Locates the row grid.
 *
 * ⚠ Refactoring Rationale: every case that scoped a query to the grid used to reach it with an
 * unscoped `getByRole('table')`, and that became ambiguous the moment the confirmation became a
 * `Modal`: the dialogue names the row it would remove through a `Descriptions`, which the design system
 * renders as a second `table` element. The ambiguity is not confined to the moment the dialogue is
 * open, either — the primitive keeps its markup mounted through a leave animation that jsdom never
 * finishes — so a case asserting on the grid immediately after accepting a delete found two tables and
 * failed on the query rather than on the screen. Scoping through the grid's own container names which
 * one is meant while keeping the assertion a ROLE query.
 * @returns {HTMLElement} The grid's table element.
 * @throws {Error} If no grid is rendered at all, which means the screen did not mount.
 */
function grid(): HTMLElement {
  const container = document.querySelector<HTMLElement>('.ant-table');

  if (container === null) {
    throw new Error('the screen rendered no row grid');
  }

  return within(container).getByRole('table');
}

/**
 * Locates the grid's empty-body placeholder.
 *
 * WHY : Assumptions: the placeholder is reached by its design-system class rather than by its text,
 *       because the point of every assertion that uses it is WHICH text it holds — a text query would
 *       have to name the answer to find the element that carries it, and would pass identically if the
 *       screen rendered that sentence anywhere else on the glass.
 * @returns {HTMLElement} The placeholder cell the grid paints in place of rows.
 * @throws {Error} If the grid has rows, in which case no placeholder exists and this is the wrong
 *   assertion for the case.
 */
function gridPlaceholder(): HTMLElement {
  const placeholder = document.querySelector<HTMLElement>('.ant-table-placeholder');

  if (placeholder === null) {
    throw new Error('the grid rendered no empty placeholder, so it is not empty');
  }

  return placeholder;
}

/**
 * Asserts the empty grid names the reason it has no rows, and never claims emptiness instead.
 *
 * WHY : ⚠️ Assumptions: the absent string is asserted alongside the present one, because the defect was
 *       an ADDITIONAL sentence rather than a missing one. Measured on a refused visit: row 23 read the
 *       refusal and the grid body underneath it read the design system's default `No data`, so the
 *       screen said "you may not see this list" and "this list is empty" at once and the second was the
 *       larger of the two. Asserting only the reason would pass with both on the glass.
 * @param {string} expected - The catalogued sentence the outcome line carries on this turn.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function expectGridPlaceholderExplains(expected: string): void {
  expect(gridPlaceholder()).toHaveTextContent(normaliseMessageBandValue(expected), {
    normalizeWhitespace: false,
  });
  expect(gridPlaceholder().textContent ?? '').not.toContain('No data');
}

/**
 * Picks the line a catalogued sentence is expected on.
 *
 * WHY : Assumptions: membership of {@link ADVISORY_LINE_SENTENCES} decides, not severity. The screen's
 *       `'info'` and `'success'` severities do not partition the same way — `WS_MESG_NO_RECORDS_FOUND`
 *       is informational in tone and is an OUTCOME the program sends to `ERRMSGO` — so a severity test
 *       would put three sentences on the wrong line.
 * @param {string} expected - The catalogued sentence, imported and never retyped.
 * @returns {HTMLElement} The band that sentence belongs on.
 */
function bandFor(expected: string): HTMLElement {
  return ADVISORY_LINE_SENTENCES.includes(expected) ? advisoryBand() : messageBand();
}

/**
 * Asserts the sentence's OWN line carries it.
 *
 * WHY : ⚠️ Refactoring Rationale: the line is chosen by {@link bandFor} where this helper used to read
 *       the outcome band unconditionally. Reading one band was correct while the screen published one,
 *       and it silently stopped proving anything the moment the advisory member arrived: the five
 *       advisory sentences moved to the `INFOMSG` channel and every assertion about them would have
 *       failed against an empty outcome line, which is a true failure with a misleading message. Going
 *       through the selector asserts the stronger property — the sentence is on the line the mapset
 *       declares for it — and a sentence published to the wrong channel now fails here rather than
 *       passing on whichever band happens to hold it.
 *
 * WHY : Assumptions: the expectation is passed through `normaliseMessageBandValue`, the SCREEN's own
 *       normaliser, rather than compared raw or trimmed here. That function strips `LOW-VALUES` and
 *       trims, so a catalog entry carrying baseline padding — `'Record not found. Deleted by others ? '`
 *       at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1864 keeps a trailing space — renders
 *       without it. Trimming in this file instead would assert the same thing while hiding which
 *       component owns the decision, and would put a `.trim()` in a test whose subject is verbatim
 *       text. Alternatives Considered: comparing against the raw entry. Rejected because it fails on
 *       the two padded entries for a reason that is not a defect.
 * @param {string} expected - The catalogued sentence, imported and never retyped.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function expectBandShows(expected: string): void {
  expect(bandFor(expected)).toHaveTextContent(normaliseMessageBandValue(expected), {
    normalizeWhitespace: false,
  });
}

/**
 * Locates the type-code filter control.
 * @returns {HTMLElement} The control the mapset paints as `TRTYPE` at `COTRTLI.bms` L89.
 */
function typeFilter(): HTMLElement {
  return screen.getByLabelText(REF_TYPE_LIST_LABELS.typeFilter);
}

/**
 * Locates the description filter control.
 * @returns {HTMLElement} The control the mapset paints as `TRDESC` at `COTRTLI.bms` L101.
 */
function descriptionFilter(): HTMLElement {
  return screen.getByLabelText(REF_TYPE_LIST_LABELS.descriptionFilter);
}

/**
 * Drops a mapset literal's trailing pad, exactly where the screen drops it.
 *
 * WHY : Assumptions: the pad is dropped by pattern rather than by a general trim, and only from the
 *       END, because the two are not the same operation on baseline text: several sentences in this
 *       program carry LEADING spaces that are content — `' must be supplied.'` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1198 — so a helper that trimmed both ends
 *       would be usable on a message and would silently destroy one. This one is for labels, and its
 *       single purpose is to reproduce the one place `ui/src/screens/refTypeList/index.tsx` drops the
 *       four trailing spaces of `LENGTH=10 INITIAL='Select    '` at
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L108-L111: where the literal becomes an
 *       accessible name or a column heading, because a name announced with trailing blanks is read
 *       out with them. The padded form itself is asserted intact elsewhere.
 * @param {string} label - A mapset literal, padded as the baseline declares it.
 * @returns {string} The literal without its trailing pad.
 */
function unpaddedLabel(label: string): string {
  return label.replace(/ +$/u, '');
}

/**
 * Locates one row's action cell.
 *
 * WHY : Assumptions: the accessible name is the column heading TRIMMED plus the row's type code,
 *       which is how `ui/src/screens/refTypeList/index.tsx` composes it. The heading literal itself
 *       carries four trailing spaces — `LENGTH=10 INITIAL='Select    '` at `COTRTLI.bms` L108-L111 —
 *       and a name announced with them would be read out as padding, so the padded literal is
 *       preserved in the label constant and trimmed only at this one point of use.
 * @param {string} typeCd - The row's two-character type code.
 * @returns {HTMLElement} That row's one-character action input.
 */
function actionCell(typeCd: string): HTMLElement {
  return screen.getByLabelText(`${unpaddedLabel(REF_TYPE_LIST_LABELS.selectColumn)} ${typeCd}`);
}

/**
 * Locates one row's description editor, which exists only while that row is the pending update.
 * @param {string} typeCd - The row's two-character type code.
 * @returns {HTMLElement} That row's fifty-character description input.
 * @throws {Error} If the row is not the pending update, which Testing Library raises: the cell
 *   renders as static text until an update action has been accepted for it.
 */
function descriptionEditor(typeCd: string): HTMLElement {
  return screen.getByLabelText(`${REF_TYPE_LIST_LABELS.descriptionColumn} ${typeCd}`);
}

/**
 * Reports the controls the row-24 function-key legend renders, in order.
 *
 * WHY : Assumptions: the legend is reached by its landmark rather than by a test identifier,
 *       because `ui/src/layout/PfKeyBar.tsx` renders a `nav` named by
 *       `PF_KEY_BAR_REGION_LABEL` and the shell renders other controls of its own — a sign-off
 *       control in the title band among them. An unscoped button query would collect those too and
 *       an exact-legend assertion would then be measuring the whole frame.
 * @returns {readonly HTMLElement[]} The legend's buttons.
 */
function legendButtons(): readonly HTMLElement[] {
  return within(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).getAllByRole(
    'button',
  );
}

/**
 * Reports the visible text of each legend control, in order.
 * @returns {readonly string[]} One label per rendered control.
 */
function legendLabels(): readonly string[] {
  return legendButtons().map(
    /**
     * Reads one control's label.
     * @param {HTMLElement} control - One legend button.
     * @returns {string} Its text, or the empty string when it carries none.
     */
    (control: HTMLElement): string => control.textContent ?? '',
  );
}

/**
 * Reports the rendered grid's body rows.
 *
 * WHY : Assumptions: the header row is excluded by querying `rowgroup` rather than by subtracting
 *       one from a row count. antd renders the head and the body as separate row groups, so
 *       selecting the body group states the intent; an arithmetic adjustment would silently start
 *       counting a summary row if one were ever added.
 * @returns {readonly HTMLElement[]} The body rows currently rendered.
 * @throws {Error} If the grid rendered no row group at all, which means the subject painted no table
 *   rather than an empty one.
 */
function gridRows(): readonly HTMLElement[] {
  const groups = within(grid()).getAllByRole('rowgroup');
  const body = groups[groups.length - 1];
  if (body === undefined) {
    throw new Error('the grid rendered no row group, so it has no body to read rows from');
  }
  return within(body).queryAllByRole('row');
}

/**
 * A probe that publishes the address the router currently holds.
 *
 * WHY : Assumptions: navigation is observed through the router's own location rather than by spying
 *       on `useNavigate`, because the screen reaches its siblings with `navigateSafely`, and what
 *       the reference does at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L591 and L630 is
 *       transfer CONTROL — under transformation rule T5 that is an address change, so the address is
 *       the observable. Alternatives Considered: mocking `react-router`. Rejected because the memory
 *       router is the mechanism under test on those two turns, and replacing it would leave the
 *       assertion measuring the mock.
 * @returns {ReactElement} A marker carrying the current pathname.
 */
function AddressProbe(): ReactElement {
  const location = useLocation();
  return <output data-testid="rendered-address">{location.pathname}</output>;
}

/**
 * Reports the address the router currently holds, as {@link AddressProbe} published it.
 * @returns {string} The current pathname.
 */
function renderedAddress(): string {
  return screen.getByTestId('rendered-address').textContent ?? '';
}

/**
 * Signs a case on as an administrator and mounts the screen inside the real shell.
 *
 * WHY : Assumptions: the session is established through the identity helper and NOTHING else. Both
 *       halves matter. `ui/src/hooks/useAuth.ts` publishes `CARDDEMO_ADMIN_GROUP` and
 *       `COGNITO_GROUPS_CLAIM` but deliberately publishes no group setter and there is no context
 *       provider in this package, so the only way to authority is a token carrying the claim —
 *       which is the migrated form of the baseline's `CDEMO-USER-TYPE PIC X(01)` at
 *       `app/cpy/COCOM01Y.cpy` L26 with its `'A'`/`'U'` conditions at L27-L28. Refactoring
 *       Rationale: in the baseline that byte travelled in the COMMAREA, storage the terminal echoed
 *       back, so a client could assert its own type; in the target the claim is signed and a test
 *       that granted itself authority would be exercising a path production does not have (AAP
 *       §0.7.1).
 *
 * WHY : Assumptions: the shell is reproduced rather than skipped, because this screen paints no
 *       message line and no key legend of its own — it publishes both through `useShellSlot`, so a
 *       bare render of the subject has neither, and every message and legend assertion below would
 *       be asserting about a tree the router never builds.
 *
 * WHY : Assumptions: the browse answer is queued BEFORE the render and the helper waits for the
 *       first row, so each case starts from a settled first page. The runner restores mocks between
 *       cases, so an unqueued spy would resolve `undefined` and the screen would report a failure
 *       instead of a page.
 * @param {readonly TransactionType[]} rows - Rows the opening read answers with.
 * @param {boolean} [hasNext] - Whether the envelope reports a further page, defaulting to `false`.
 * @returns {Promise<UserEvent>} The operator that drives the mounted screen.
 * @throws {Error} If the sign-on exchange does not establish a session, which `seedSession` raises.
 */
async function mountAsAdministrator(
  rows: readonly TransactionType[],
  hasNext = false,
): Promise<UserEvent> {
  browseSpy().mockResolvedValue(pageResponse<TransactionType>(rows, { hasNext }));
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  const { user } = await renderInAppShell(<RefTypeListScreen />, {
    initialEntries: [REF_TYPE_LIST_PATH],
    routePath: REF_TYPE_LIST_PATH,
  });
  await waitFor(
    /**
     * Asserts the opening read has been answered and its rows painted.
     * @returns {void} Nothing; throws until the browse has settled.
     */
    (): void => {
      expect(browseSpy()).toHaveBeenCalled();
    },
  );
  if (rows.length > 0) {
    await screen.findByText(rows[0]?.description ?? '');
  }
  return user;
}

/**
 * Mounts the screen beside an address probe, for the two cases whose subject is navigation.
 *
 * WHY : Assumptions: no route PATTERN is supplied, so the harness mounts the pair under its
 *       catch-all. That is deliberate and it is the only arrangement in which these two cases can
 *       assert anything: mounted under `/reference/transaction-types` alone, a navigation to
 *       `/admin` matches no route, the subtree unmounts and the probe goes with it — leaving a
 *       Testing Library "unable to find element" failure that names the probe rather than the
 *       address. The screen reads no route parameter, so a catch-all costs it nothing.
 * @param {readonly TransactionType[]} rows - Rows the opening read answers with.
 * @returns {Promise<UserEvent>} The operator that drives the mounted screen.
 * @throws {Error} If the sign-on exchange does not establish a session, which `seedSession` raises.
 */
async function mountWithAddressProbe(rows: readonly TransactionType[]): Promise<UserEvent> {
  browseSpy().mockResolvedValue(pageResponse<TransactionType>(rows));
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  const { user } = await renderInAppShell(
    <>
      <RefTypeListScreen />
      <AddressProbe />
    </>,
    { initialEntries: [REF_TYPE_LIST_PATH] },
  );
  await screen.findByText(rows[0]?.description ?? '');
  return user;
}

/**
 * Enters one action code against one row and transmits the turn with ENTER.
 *
 * WHY : Assumptions: ENTER is raised as a real key event even though focus sits in a text input,
 *       because `ENTER_ACTIVATED_TARGET_SELECTOR` in `ui/src/layout/usePfKeys.ts` claims ENTER only
 *       for controls that activate on it — buttons, links, selects, text areas and submit inputs — so
 *       a one-character text cell does not swallow it. That reproduces the terminal, where ENTER
 *       transmitted the screen from wherever the cursor was.
 * @param {UserEvent} user - The operator driving the screen.
 * @param {string} typeCd - The row to mark.
 * @param {string} code - The action code to type into that row's cell.
 * @returns {Promise<void>} Resolves once the turn has been transmitted and settled.
 */
async function markRowAndTransmit(user: UserEvent, typeCd: string, code: string): Promise<void> {
  await user.type(actionCell(typeCd), code);
  await pressPfKey(user, 'ENTER');
}

/**
 * Asserts the screen's declared widths are the ones the mapset and the table declare.
 *
 * WHY : Assumptions: the exported constants are compared against the measured values rather than
 *       read as the source of truth, because they are transcriptions. `TYPE_CODE_LENGTH` restates
 *       `TRTYPE LENGTH=2` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L89 and
 *       `TR_TYPE CHAR(2)` at `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2; `DESCRIPTION_LENGTH`
 *       restates `TRDESC LENGTH=50` at L101 and `TR_DESCRIPTION VARCHAR(50)` at L3;
 *       `ACTION_CODE_LENGTH` restates `TRTSEL1 LENGTH=1` at L133 and `WS-EDIT-SELECT PIC X(1)` at
 *       `COTRTLIC.cbl` L181. A transcription that drifted would otherwise agree with itself.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function transcribesTheDeclaredWidths(): void {
  expect(TYPE_CODE_LENGTH).toBe(2);
  expect(DESCRIPTION_LENGTH).toBe(50);
  expect(ACTION_CODE_LENGTH).toBe(1);
}

/**
 * Asserts every rendered control refuses more characters than its field declares.
 * @returns {Promise<void>} Resolves once every width has been checked.
 */
async function holdsEveryControlToItsDeclaredWidth(): Promise<void> {
  await mountAsAdministrator(refTypeRows(MEASURED_PAGE_SIZE));

  expectMaxLength(typeFilter(), TYPE_CODE_LENGTH);
  expectMaxLength(descriptionFilter(), DESCRIPTION_LENGTH);

  // WHY : Assumptions: all seven cells are checked and not a sample of them, because the seven are
  //       seven separate `DFHMDF` definitions in the mapset — `TRTSEL1` at L133 through `TRTSEL7` at
  //       L259 — rather than one repeated control, so seven widths can drift independently.
  for (const row of refTypeRows(MEASURED_PAGE_SIZE)) {
    expectMaxLength(actionCell(row.typeCd), ACTION_CODE_LENGTH);
  }
}

/**
 * Asserts the initial cursor sits on the type filter and on nothing else.
 *
 * WHY : Assumptions: the mapset carries exactly ONE `IC` attribute, at
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L89 on `TRTYPE`, so exactly one control may
 *       open focused. The assertion is written as one positive plus a negative sweep over the other
 *       inputs rather than by counting an `autofocus` attribute, because React applies `autoFocus`
 *       by calling `focus()` and leaves no attribute behind to count.
 * @returns {Promise<void>} Resolves once the opening focus has been checked.
 */
async function opensWithTheOneInitialCursorOnTheTypeFilter(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  expect(typeFilter()).toHaveFocus();
  expect(descriptionFilter()).not.toHaveFocus();
  for (const row of twoRowPage()) {
    expect(actionCell(row.typeCd)).not.toHaveFocus();
  }
}

/**
 * Asserts the screen renders no concealed field, because the mapset declares none.
 *
 * WHY : Assumptions: `app/app-transaction-type-db2/bms/COTRTLI.bms` carries ZERO `DRK` attributes,
 *       measured across all 81 of its `DFHMDF` definitions, so nothing on this screen is
 *       non-display. Gap G2 in AAP §0.3.4 — the sign-on screen's masked entry — has no analogue
 *       here, and a masked control appearing on a reference-data screen would be a control the
 *       reference never painted. Alternatives Considered: asserting `queryByLabelText` finds no
 *       password field. Rejected because a concealed control with no accessible name would pass
 *       that, and the concealment is a property of the element rather than of its name.
 * @returns {Promise<void>} Resolves once the sweep has completed.
 */
async function rendersNoConcealedField(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  expect(document.body.querySelectorAll('input[type="password"]')).toHaveLength(0);
}

/**
 * Asserts a filter that is not two digits is refused with the program's own sentence.
 *
 * WHY : Assumptions: the digit rule is asserted even though the mapset carries no `NUM` attribute on
 *       `TRTYPE`, because the two are independent enforcement layers and the PROGRAM is the stricter
 *       of them: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1112-L1118 sets the input-error
 *       flag and moves the upper-case sentence when the supplied filter is not a two-digit number,
 *       whatever the terminal allowed to be typed. A screen that trusted the field attribute alone
 *       would accept a turn the reference refuses.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesAFilterThatIsNotTwoDigits(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await user.type(typeFilter(), '1');
  await pressPfKey(user, 'ENTER');

  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER);
  expect(typeFilter()).toHaveAttribute('aria-invalid', 'true');

  // WHY : Assumptions: the refused turn issues no second read. `1220-EDIT-TYPECD` at
  //       `COTRTLIC.cbl` L1112-L1118 branches to its exit before the browse paragraphs run, so a
  //       screen that queried anyway would be reaching the database on input the reference rejected
  //       locally.
  expect(browseSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts a two-digit filter reaches the service as a filter rather than as a position.
 * @returns {Promise<void>} Resolves once the query has been asserted.
 */
async function carriesATwoDigitFilterIntoTheQuery(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await user.type(typeFilter(), '99');
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Asserts the filtered read has been issued.
     * @returns {void} Nothing; throws until a second read is recorded.
     */
    (): void => {
      expect(browseSpy()).toHaveBeenCalledTimes(2);
    },
  );
  expect(browseSpy()).toHaveBeenLastCalledWith({ typeCode: '99' });
}

/**
 * Asserts the three column headings render and the terminal's rule characters do not.
 *
 * WHY : Trade-offs: the mapset draws its own borders as literal fields — `'------'` at
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L123, `'-----'` at L127 and a fifty-character
 *       run at L131 — and none of them is reproduced. Rejected alternative: painting the dash
 *       literals as text inside the grid. It would fight the component rather than match the
 *       terminal, because antd's `Table` draws its own heading rule and cell borders, so the result
 *       is a doubled rule and a screen reader announcing fifty hyphens. This is gap G1 territory in
 *       AAP §0.3.4: reading order, grouping and heading text are preserved; character-cell drawing
 *       is not.
 *
 * WHY : Assumptions: the action heading is compared against the TRIMMED literal, because that is
 *       what the screen renders, while the four trailing spaces of `LENGTH=10 INITIAL='Select    '`
 *       at L108-L111 are asserted to survive in the exported constant. Both halves are the contract:
 *       the padding is inside the baseline literal rather than a consequence of the field width, so
 *       it is transcribed, and it is dropped only where it would be announced.
 * @returns {Promise<void>} Resolves once the headings have been asserted.
 */
async function rendersTheColumnHeadingsWithoutTheTerminalRules(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  const headings = within(grid())
    .getAllByRole('columnheader')
    .map(
      /**
       * Reads one heading's text.
       * @param {HTMLElement} heading - One column heading cell.
       * @returns {string} Its text, or the empty string when it carries none.
       */
      (heading: HTMLElement): string => heading.textContent ?? '',
    );

  expect(headings).toStrictEqual([
    unpaddedLabel(REF_TYPE_LIST_LABELS.selectColumn),
    REF_TYPE_LIST_LABELS.typeColumn,
    REF_TYPE_LIST_LABELS.descriptionColumn,
  ]);
  expect(REF_TYPE_LIST_LABELS.selectColumn).toMatch(/^Select {4}$/u);
  expect(document.body.textContent ?? '').not.toMatch(/-{5}/u);
}

/**
 * Asserts a row's description is edited in place and that editing navigates nowhere.
 *
 * WHY : Refactoring Rationale: this is the only inline-edit grid in the application, and the shape
 *       is measured rather than chosen. Every other list screen marks a row and transfers control to
 *       a detail program, but this mapset paints fourteen editable row cells across seven rows —
 *       `TRTTYP1`-`TRTTYP7` and `TRTYPD1`-`TRTYPD7` at
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L133-L279 — and `COTRTLIC.cbl` reads them
 *       back on the same turn. Re-expressing that as select-and-navigate would lose function rather
 *       than merely restyle it, so the assertion pairs "the editor exists in the row" with "the
 *       address did not change".
 *
 * WHY : Assumptions: the editor appears only AFTER an update action is accepted, which is the
 *       reference's own two-step. The mapset declares the row cells `ATTRB=(FSET,NORM,PROT)` at
 *       L133 and L266 — protected as painted — and the program's per-row attribute pass at
 *       L1329-L1373 unprotects the marked row, so a cell that was editable before the action code
 *       was accepted would be more permissive than the terminal.
 * @returns {Promise<void>} Resolves once the in-place edit has been asserted.
 */
async function editsARowDescriptionInPlaceWithoutNavigating(): Promise<void> {
  const user = await mountWithAddressProbe(twoRowPage());

  expect(
    screen.queryByLabelText(`${REF_TYPE_LIST_LABELS.descriptionColumn} ${SECOND_TYPE_CODE}`),
  ).toBeNull();

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);

  const editor = descriptionEditor(SECOND_TYPE_CODE);
  expectMaxLength(editor, DESCRIPTION_LENGTH);
  await user.type(editor, ' RETAIL');

  expect(descriptionEditor(SECOND_TYPE_CODE)).toHaveValue(`${SECOND_DESCRIPTION} RETAIL`);
  expect(renderedAddress()).toBe(REF_TYPE_LIST_PATH);
  expect(gridRows()).toHaveLength(twoRowPage().length);
}

/**
 * Reports the element carrying one row's type code.
 * @param {string} typeCd - The row's two-character type code.
 * @returns {HTMLElement} The element the type-code column renders for that row.
 * @throws {Error} If the code is not rendered, which Testing Library raises.
 */
function typeCodeCell(typeCd: string): HTMLElement {
  return within(grid()).getByText(typeCd);
}

/**
 * A promise a case holds open, so a control can be observed while its request is outstanding.
 */
interface HeldCall<T> {
  /** The promise to hand the transport spy. */
  readonly promise: Promise<T>;

  /** Settles it, so the screen sees the call complete. */
  readonly settle: (value: T) => void;
}

/**
 * Builds a promise the case settles, rather than one the transport settles immediately.
 *
 * Assumptions: a held promise is the only way to observe a mid-request state at all. A spy that resolves
 * on the microtask queue has already settled by the time any assertion runs, so `aria-busy` would be
 * gone before it could be read and the case would pass against a screen that never set it.
 * @returns {HeldCall<T>} The promise and its resolver.
 */
function heldCall<T>(): HeldCall<T> {
  /** Resolver of the promise below, replaced the moment the executor runs. */
  let capture: (value: T) => void = refuseEarlySettle;

  /**
   * Records the promise's resolver so the case can reach it.
   * @param {(value: T) => void} resolve - The resolver the promise supplies.
   * @returns {void} Nothing; the resolver is recorded above.
   */
  function captureResolver(resolve: (value: T) => void): void {
    capture = resolve;
  }

  /**
   * Settles the promise with the value given.
   * @param {T} value - What the held call answers with.
   * @returns {void} Nothing; the promise settles.
   */
  function settle(value: T): void {
    capture(value);
  }

  return { promise: new Promise<T>(captureResolver), settle };
}

/**
 * Stands in for the resolver until the executor supplies the real one.
 * @returns {void} Nothing; it is never reached, because a promise executor runs synchronously.
 * @throws {Error} If a case settles a held call before its resolver was captured.
 */
function refuseEarlySettle(): void {
  throw new Error('the held call was settled before its resolver was captured');
}

/**
 * Asserts a control states, while it waits, that the request it submitted is still outstanding.
 *
 * ⚠ WHY : Refactoring Rationale: a review measured `aria-busy` on 6 of 13 busy states across this
 *       application and on no control this screen renders. Both halves of that mattered here. The
 *       browse's own progress reached the grid's spinner alone, so the filter an operator had just
 *       submitted from said nothing -- which is what produces a second and third identical submission.
 *       And a commit reached nothing at all: the action cell went inert through `disabled` without ever
 *       stating WHY it had, so an operator using a screen reader heard a control stop answering.
 *
 *       Assumptions: the ABSENCE of the attribute is asserted for the idle state rather than the string
 *       `'false'`, because `aria-busy` defaults to false when absent and `busyProps` returns an empty
 *       object when idle instead of writing an attribute on every settle.
 *
 *       Assumptions: both states are asserted for each control -- present while held, gone once settled
 *       -- so a screen that set the member and never cleared it fails as surely as one that never set it.
 * @returns {Promise<void>} Resolves once both controls have been observed in both states.
 */
async function statesAnOutstandingRequestOnTheControlThatSubmittedIt(): Promise<void> {
  const heldBrowse = heldCall<PageResponse<TransactionType>>();
  const user = await mountAsAdministrator(twoRowPage());

  expect(typeFilter().hasAttribute('aria-busy')).toBe(false);

  browseSpy().mockReturnValueOnce(heldBrowse.promise);
  await user.type(typeFilter(), FIRST_TYPE_CODE);
  await pressPfKey(user, 'ENTER');
  await waitFor(expectTypeFilterBusy);

  heldBrowse.settle(pageResponse<TransactionType>(twoRowPage()));
  await waitFor(expectTypeFilterIdle);

  const heldSave = heldCall<TransactionType>();
  replaceSpy().mockReturnValueOnce(heldSave.promise);
  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(descriptionEditor(SECOND_TYPE_CODE), ' RETAIL');
  await pressPfKey(user, 'ENTER');
  // Assumptions: the save is raised as the attention identifier `PFK10`, because F10 has no browser key
  //   and the harness refuses a press of one -- the same route every other save case on this screen takes.
  await pressPfKey(user, 'PFK10');
  await waitFor(expectActionCellBusy);

  heldSave.settle({
    typeCd: SECOND_TYPE_CODE,
    description: `${SECOND_DESCRIPTION} RETAIL`,
    version: 4,
  });
  await waitFor(expectActionCellIdle);
}

/**
 * Asserts the type filter reports a browse outstanding.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectTypeFilterBusy(): void {
  expect(typeFilter().getAttribute('aria-busy')).toBe('true');
  expectProgressAnnounced();
}

/**
 * Asserts the screen SAYS a request is outstanding, in words, on its own live region.
 *
 * ⚠ WHY : Purpose: `aria-busy` states that a control cannot be used and says nothing about why or for
 *       how long. The catalogue now carries an authored operator sentence for exactly this — the
 *       progress statement — and this screen previously carried a comment claiming no such sentence
 *       existed, which was true when it was written and is not now.
 *
 *       Assumptions: the region is asserted through the shared helper's test identifier rather than by
 *       reading the sentence off the glass, because the element is visually hidden: a text query would
 *       find it and would equally find a copy rendered visibly, which is not what a polite live region
 *       is for.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectProgressAnnounced(): void {
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);
}

/**
 * Asserts the type filter reports no browse outstanding.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectTypeFilterIdle(): void {
  expect(typeFilter().hasAttribute('aria-busy')).toBe(false);
  expectProgressSilent();
}

/**
 * Asserts the live region falls silent once nothing is outstanding.
 *
 * Assumptions: the region stays MOUNTED and empties, rather than unmounting. A live region that appears
 * with its content is not reliably announced — assistive technology has to have observed the region
 * before the text arrives in it — which is why the shared helper renders the element unconditionally.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectProgressSilent(): void {
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
}

/**
 * Asserts the first row's action cell reports a write outstanding.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectActionCellBusy(): void {
  expect(actionCell(FIRST_TYPE_CODE).getAttribute('aria-busy')).toBe('true');
  expectProgressAnnounced();
}

/**
 * Asserts the first row's action cell reports no write outstanding.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectActionCellIdle(): void {
  expect(actionCell(FIRST_TYPE_CODE).hasAttribute('aria-busy')).toBe(false);
  expectProgressSilent();
}

/**
 * Asserts every control on this screen is sized from the width its own field declares.
 *
 * ⚠ WHY : Refactoring Rationale: none of the four controls carried a measure, so each took whatever its
 *       container gave it. A rendering review measured the consequence across this application as
 *       one- and two-character inputs rendered 201 pixels wide; here it put the TWO-character type
 *       filter and the FIFTY-character description filter side by side at identical size, so the pair
 *       said nothing about the difference between them, and it is the type filter the browse is
 *       submitted from.
 *
 *       Assumptions: three controls take a CEILING and one takes a FLOOR, and the asymmetry is the
 *       point rather than an inconsistency. `copybookFieldWidthStyle` publishes a maximum from a
 *       declared width, which is what a field wider than its data needs; the one-character action cell
 *       needs the opposite, because its share of a narrow row can fall below the design system's own
 *       padding and collapse the content box while the value stays stored and unreadable. Spreading
 *       both onto one control would set a maximum of one column and a minimum of one column plus
 *       padding on the same box.
 *
 *       Assumptions: the expressions are read off the inline `style` rather than compared against
 *       computed pixels, because jsdom performs no layout and the terms are `calc()` over a custom
 *       property the design system scopes to component classes. What the case can prove is that the
 *       DECLARED width reached the control in character units, which is the transcription being
 *       regressed.
 *
 *       Assumptions: the row editor's measure is read from the affix wrapper and not from the input.
 *       With a suffix always present -- see the marker-slot case -- the design system puts the root
 *       `style` on the wrapper, which is the bordered box a width applies to.
 * @returns {Promise<void>} Resolves once all four measures have been asserted.
 */
async function sizesEachControlFromItsDeclaredWidth(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  expect(
    typeFilter().style.maxInlineSize,
    'the two-character filter must be capped at two characters',
  ).toContain(`${String(TYPE_CODE_LENGTH)}ch`);
  expect(
    descriptionFilter().style.maxInlineSize,
    'the fifty-character filter must be capped at fifty characters',
  ).toContain(`${String(DESCRIPTION_LENGTH)}ch`);

  const cell = actionCell(FIRST_TYPE_CODE);
  expect(
    cell.style.minInlineSize,
    'the action cell must reserve a floor rather than take a ceiling',
  ).toContain(`${String(ACTION_CODE_LENGTH)}ch`);
  expect(
    cell.style.minInlineSize,
    'the AA target-size floor must survive an unresolved padding token',
  ).toContain(`${String(TARGET_SIZE_AA_MINIMUM)}px`);
  expect(cell.style.maxInlineSize, 'a floor and a ceiling must not both be set').toBe('');

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  const wrapper = descriptionEditor(SECOND_TYPE_CODE).closest('.ant-input-affix-wrapper');
  if (!(wrapper instanceof HTMLElement)) {
    throw new Error('the row editor rendered without the affix wrapper its suffix slot requires');
  }
  /*
   * WHY : ⚠️ Refactoring Rationale: the expectation allows for the MARKER SLOT, where it used to assert
   *       the declared width alone. The row editor holds its suffix element on every turn -- the reason
   *       is recorded on `MARKER_SLOT_UNOCCUPIED` in the screen -- so the design system sizes the affix
   *       WRAPPER, whose space the value and the marker then share. A box measured for the value alone
   *       clips the value, which a browser review measured on a sibling screen's two-character key as a
   *       record identity rendering as one glyph and a sliver.
   * WHY : Assumptions: the allowance is the marker's own characters plus one, which is the contract
   *       `copybookFieldWidthStyle` documents -- the marker's glyphs, and one further cell because `ch`
   *       on the wrapper resolves in the theme's proportional face while the value renders in the wider
   *       fixed-pitch one. It is stated exactly rather than as a bound, so a measure that reserved a
   *       comfortable surplus instead of the slot would still fail.
   */
  expect(
    wrapper.style.maxInlineSize,
    'the row editor must be capped at the width its copybook field declares plus its marker slot',
  ).toContain(`${String(DESCRIPTION_LENGTH + BLANK_FIELD_MARKER_CHARACTERS + 1)}ch`);
}

/**
 * Asserts a row offers itself as actionable without taking a click away from a control inside it.
 *
 * ⚠ WHY : Purpose: this case pins BOTH halves of the row affordance, because each half was a measured
 *       defect and the second was introduced by the fix for the first.
 *
 *       The absence: a browser pass measured `cursor: auto` on these rows at rest and hovered, on a
 *       browse whose whole purpose is choosing a row to act on. Nothing said a row could be acted on.
 *
 *       The over-reach: giving the row a click handler made it swallow clicks aimed at the controls
 *       INSIDE it, because a click bubbles. Measured before the guard: the operator clicked into the
 *       description of type `02`, typed ` RETAIL` onto `PAYMENT`, and the field still read `PAYMENT` --
 *       `document.activeElement` was `ref-type-list-action-02`, so the keystrokes went into the
 *       one-character cell that decides whether the row is updated or deleted.
 *
 *       Assumptions: the static-content click is aimed at the TYPE CODE cell, which
 *       `2200-SETUP-ARRAY-ATTRIBS` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1329-L1373 never
 *       unprotects -- so it is the one cell in the row guaranteed to carry no control of its own, on
 *       every turn.
 *
 *       Assumptions: focus is asserted through `document.activeElement` and not through a style,
 *       because where the cursor IS is the property that matters; the reference places the cursor with
 *       `CURSOR` on the map it re-sends and nothing about that is visual.
 * @returns {Promise<void>} Resolves once both halves have been asserted.
 */
async function offersTheRowWithoutTakingAControlsClick(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  const [firstRow] = gridRows();
  if (firstRow === undefined) {
    throw new Error('the grid rendered no rows, so it has no row affordance to assert');
  }
  expect(firstRow).toHaveStyle({ cursor: 'pointer' });

  // The static half: a click on the protected type code puts the cursor in that row's action cell.
  await user.click(typeCodeCell(SECOND_TYPE_CODE));
  expect(document.activeElement).toBe(actionCell(SECOND_TYPE_CODE));

  // The guarded half: a click on a control inside the row is left to that control.
  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  const editor = descriptionEditor(SECOND_TYPE_CODE);
  await user.click(editor);
  expect(document.activeElement).toBe(editor);

  await user.type(editor, ' RETAIL');
  expect(descriptionEditor(SECOND_TYPE_CODE)).toHaveValue(`${SECOND_DESCRIPTION} RETAIL`);
}

/**
 * Asserts a row's type code is never editable, whatever action is pending against the row.
 *
 * WHY : Assumptions: only TWO of a row's three cells are ever unprotected, and the third is the type
 *       code. `2200-SETUP-ARRAY-ATTRIBS` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`
 *       L1329-L1373 protects the description by default at L1337, unprotects the action cell at
 *       L1371, and unprotects the description at L1364-L1365 for the row marked for update — and it
 *       never once touches the type code's own attribute byte, only its colour at L1351 and L1359.
 *       That is why the key is rendered as text: it addresses the row, so an editable key would let an
 *       operator retarget a pending write by typing over it.
 * @returns {Promise<void>} Resolves once the cell has been asserted static.
 */
async function keepsTheRowTypeCodeStatic(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  expect(typeCodeCell(SECOND_TYPE_CODE).tagName).not.toBe('INPUT');

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);

  expect(typeCodeCell(SECOND_TYPE_CODE).tagName).not.toBe('INPUT');
  expect(descriptionEditor(SECOND_TYPE_CODE).tagName).toBe('INPUT');
}

/**
 * Asserts the row awaiting a decision is the one the screen de-emphasises, and no other.
 *
 * WHY : Assumptions: the reference's own word for the row a prompt is about is `HIGHLIGHTED` — it
 *       appears in four of its sentences, at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L242,
 *       L244, L246 and L248 — and the mechanism behind that word is a colour change on the marked
 *       row alone: `2200-SETUP-ARRAY-ATTRIBS` moves `DFHNEUTR` into that row's type and description
 *       colour attributes at L1351-L1352 for a delete and L1359 for an update, leaving every other
 *       row as painted. So the assertion is a comparison BETWEEN rows rather than against a colour:
 *       the marked row must differ from an unmarked one while a request is pending, and must match it
 *       again once nothing is pending. Trade-offs: comparing rather than naming a value costs the case
 *       the ability to say which colour was applied, and buys the prohibition on literal design values
 *       — every colour on this screen resolves through the theme tokens in `ui/src/theme/tokens.ts`,
 *       so a test naming one would be the only place in the package that hard-codes it.
 * @returns {Promise<void>} Resolves once the de-emphasis has been asserted row-local.
 */
async function deEmphasisesOnlyTheRowAwaitingADecision(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  const unmarkedColour = typeCodeCell(FIRST_TYPE_CODE).style.color;
  expect(typeCodeCell(SECOND_TYPE_CODE).style.color).toBe(unmarkedColour);

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);

  expect(typeCodeCell(SECOND_TYPE_CODE).style.color).not.toBe(unmarkedColour);
  expect(typeCodeCell(FIRST_TYPE_CODE).style.color).toBe(unmarkedColour);
}

/**
 * Registers the field-constraint and composition cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function fieldConstraintCases(): void {
  it('transcribes the widths the mapset and the table declare', transcribesTheDeclaredWidths);
  it('holds every control to its declared width', holdsEveryControlToItsDeclaredWidth);
  it(
    'opens with the one initial cursor on the type filter',
    opensWithTheOneInitialCursorOnTheTypeFilter,
  );
  it('renders no concealed field', rendersNoConcealedField);
  it('refuses a type filter that is not two digits', refusesAFilterThatIsNotTwoDigits);
  it('carries a two-digit filter into the query', carriesATwoDigitFilterIntoTheQuery);
  it(
    'renders the three column headings without the terminal rules',
    rendersTheColumnHeadingsWithoutTheTerminalRules,
  );
  it(
    'edits a row description in place without navigating',
    editsARowDescriptionInPlaceWithoutNavigating,
  );
  it("offers the row without taking a control's click", offersTheRowWithoutTakingAControlsClick);
  it('sizes each control from its declared width', sizesEachControlFromItsDeclaredWidth);
  it(
    'states an outstanding request on the control that submitted it',
    statesAnOutstandingRequestOnTheControlThatSubmittedIt,
  );
  it('keeps the row type code static', keepsTheRowTypeCodeStatic);
  it('de-emphasises only the row awaiting a decision', deEmphasisesOnlyTheRowAwaitingADecision);
}

describe('transaction-type list field constraints', fieldConstraintCases);

/**
 * Reads the page ordinal the screen paints beside its heading.
 * @param {number} ordinal - The ordinal expected on the row-4 line.
 * @returns {HTMLElement} The element carrying the row-4 page prefix and that ordinal.
 * @throws {Error} If that ordinal is not painted, which Testing Library raises.
 */
function pageOrdinal(ordinal: number): HTMLElement {
  return screen.getByText(`${REF_TYPE_LIST_LABELS.pagePrefix}${String(ordinal)}`);
}

/**
 * Asserts the screen declares the row arity the program and the mapset agree on.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function declaresTheMeasuredRowArity(): void {
  expect(REF_TYPE_PAGE_SIZE).toBe(MEASURED_PAGE_SIZE);
}

/**
 * Asserts a full page paints seven rows, the seventh among them and no eighth.
 *
 * WHY : Assumptions: seven is asserted as an exact count rather than a lower bound, because it is the
 *       reference's own arity — `WS-MAX-SCREEN-LINES … VALUE 7` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L60, with seven action cells in the mapset —
 *       and a page painting eight rows would put a row on screen the terminal had no line for.
 * @returns {Promise<void>} Resolves once the row count has been asserted.
 */
async function paintsSevenRowsForAFullPage(): Promise<void> {
  const rows = refTypeRows(MEASURED_PAGE_SIZE);
  await mountAsAdministrator(rows);

  expect(gridRows()).toHaveLength(MEASURED_PAGE_SIZE);
  expect(screen.getByText(rows[MEASURED_PAGE_SIZE - 1]?.description ?? '')).toBeInTheDocument();
  expect(screen.queryByText('DESCRIPTION 8')).toBeNull();
}

/**
 * Asserts a short page paints only the rows it carries.
 * @returns {Promise<void>} Resolves once the row count has been asserted.
 */
async function paintsFewerRowsForAShortPage(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  expect(gridRows()).toHaveLength(twoRowPage().length);
}

/**
 * Asserts the component's own offset pagination is switched off and a cursor drives paging instead.
 *
 * WHY : Trade-offs: `pagination={false}` costs the screen the component's ready-made page control
 *       and is taken deliberately (AAP §0.3.2). Offset pagination re-reads by position, so under a
 *       concurrent insert or delete it SKIPS and REPEATS rows — observable behaviour the reference's
 *       browse does not have, because `COTRTLIC` pages by cursor position rather than by row number.
 *       The prop is not readable from the rendered tree, so the assertion pairs the absence of the
 *       control with the presence of a replayed cursor on the forward step; either alone would admit
 *       an offset browse with the control hidden.
 * @returns {Promise<void>} Resolves once both halves have been asserted.
 */
async function switchesOffTheComponentsOffsetPagination(): Promise<void> {
  const user = await mountAsAdministrator(refTypeRows(MEASURED_PAGE_SIZE), true);

  expect(document.body.querySelectorAll('.ant-pagination')).toHaveLength(0);

  await pressPfKey(user, 'PFK08');

  await waitFor(
    /**
     * Asserts the forward step has been issued.
     * @returns {void} Nothing; throws until the second read is recorded.
     */
    (): void => {
      expect(browseSpy()).toHaveBeenCalledTimes(2);
    },
  );
  expect(browseSpy()).toHaveBeenLastCalledWith({ cursor: TRAILING_CURSOR, direction: 'next' });
}

/**
 * Asserts the forward and backward steps replay the two sealed cursors in the right directions.
 *
 * WHY : Assumptions: this maps one-to-one onto the reference rather than approximating it, because
 *       the reference already pages by cursor: `COTRTLIC` declares two named Db2 cursors and steps
 *       through them, fetching from `C-TR-TYPE-FORWARD` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1686, closing it at L1712, and reporting a
 *       failed fetch of `C-TR-TYPE-BACKWARD` at L1784. Forward reads strictly after the trailing
 *       position ascending and backward strictly before the leading position descending, which is
 *       what a keyset browse expresses (AAP §0.7.4).
 * @returns {Promise<void>} Resolves once both steps have been asserted.
 */
async function replaysTheSealedCursorInBothDirections(): Promise<void> {
  const user = await mountAsAdministrator(refTypeRows(MEASURED_PAGE_SIZE), true);

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Asserts the forward step reached the service.
     * @returns {void} Nothing; throws until it has.
     */
    (): void => {
      expect(browseSpy()).toHaveBeenLastCalledWith({ cursor: TRAILING_CURSOR, direction: 'next' });
    },
  );

  await pressPfKey(user, 'PFK07');
  await waitFor(
    /**
     * Asserts the backward step reached the service.
     * @returns {void} Nothing; throws until it has.
     */
    (): void => {
      expect(browseSpy()).toHaveBeenLastCalledWith({
        cursor: LEADING_CURSOR,
        direction: 'previous',
      });
    },
  );
}

/**
 * Asserts the envelope this screen is handed carries four members and no position data.
 *
 * WHY : Assumptions: the four are asserted by enumerating the built envelope's own keys, so the case
 *       fails if a fifth is ever invented. `ui/src/api/types.ts` declares `PageResponse<T>` with
 *       `items`, `firstKey`, `lastKey` and `hasNext` and nothing else, and no service sends more:
 *       backward availability is the CLIENT's own derivation in `ui/src/hooks/usePagedQuery.ts` and
 *       the page ordinal is the client's own count. A fixture carrying `hasPrev` or a row total would
 *       let a screen test pass against a body the server never sends, which is the one fixture
 *       defect no screen assertion catches.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function readsOnlyTheFourEnvelopeMembers(): void {
  const envelope = pageResponse<TransactionType>(twoRowPage(), { hasNext: true });

  expect(Object.keys(envelope).sort()).toStrictEqual(['firstKey', 'hasNext', 'items', 'lastKey']);
  for (const absent of ['hasPrev', 'pageNumber', 'pageSize', 'totalElements', 'totalPages']) {
    expect(Object.hasOwn(envelope, absent)).toBe(false);
  }
}

/**
 * Asserts the page ordinal is painted, is never the paging basis, and never reaches the service.
 *
 * WHY : Trade-offs: this screen carries the application's only page-number field —
 *       `PAGENO LENGTH=3` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L82 — and it is kept
 *       because an operator sees it and transformation rule T8 protects what an operator sees. It is
 *       rendered as TEXT rather than as a three-character input, and that follows the mapset rather
 *       than simplifying it: the field carries no `ATTRB` operand at all, so it takes the BMS default
 *       of a protected, autoskip field, and the program only ever writes to it. A three-character
 *       input would accept a page number an operator typed, which is exactly the paging-by-position
 *       behaviour the rest of this case rules out. The
 *       navigation underneath it is nonetheless keyset, and the rejected alternative is the one that
 *       looks faithful: wiring the backward and forward steps to the ordinal because the field
 *       exists. It would page by position, and paging by position under a concurrent insert skips
 *       and repeats rows.
 *
 * WHY : Assumptions: the reference derives the displayed number FROM cursor position rather than the
 *       other way round — `COTRTLIC` steps its two declared cursors and counts pages as it goes — so
 *       the number is an output of the browse. The proof that this screen agrees is that the second
 *       page's own envelope decides both boundaries while the ordinal reads 2: the forward step is
 *       refused on `hasNext` and the backward step is allowed on the derived availability, and the
 *       query that goes out carries a sealed cursor rather than the number.
 * @returns {Promise<void>} Resolves once the ordinal has been asserted cosmetic.
 */
async function paintsThePageOrdinalWithoutPagingOnIt(): Promise<void> {
  const user = await mountAsAdministrator(refTypeRows(MEASURED_PAGE_SIZE), true);

  expect(pageOrdinal(1)).toBeInTheDocument();

  // WHY : Assumptions: the second page is answered with `hasNext: false`, so the envelope and the
  //       ordinal deliberately disagree about whether there is more to see. That disagreement is the
  //       measurement: a screen paging on the ordinal would offer a third page, and one paging on
  //       the envelope refuses.
  browseSpy().mockResolvedValue(
    pageResponse<TransactionType>(refTypeRows(MEASURED_PAGE_SIZE), { hasNext: false }),
  );
  await pressPfKey(user, 'PFK08');
  await screen.findByText(`${REF_TYPE_LIST_LABELS.pagePrefix}2`);

  const readsAfterStepping = browseSpy().mock.calls.length;
  await pressPfKey(user, 'PFK08');
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_MORE_RECORDS.text);
  expect(browseSpy()).toHaveBeenCalledTimes(readsAfterStepping);

  for (const call of browseSpy().mock.calls) {
    const query = call[0] ?? {};
    expect(Object.keys(query).sort()).not.toContain('page');
    expect(Object.keys(query).sort()).not.toContain('offset');
    expect(Object.keys(query).sort()).not.toContain('pageNumber');
  }
}

/**
 * Asserts the backward step is refused on the first page, whatever the ordinal reads.
 *
 * WHY : Assumptions: the refusal is the reference's own — `WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE`
 *       moves `'No previous pages to display'` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1532-L1535 — and it is a message rather
 *       than a silent no-op, so a screen that merely disabled the control would drop a
 *       user-visible sentence.
 * @returns {Promise<void>} Resolves once the boundary has been asserted.
 */
async function refusesTheBackwardStepOnTheFirstPage(): Promise<void> {
  const user = await mountAsAdministrator(refTypeRows(MEASURED_PAGE_SIZE), true);

  await pressPfKey(user, 'PFK07');

  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY);
  expect(browseSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts the two forward-boundary sentences arrive in the reference's order and stay distinct.
 *
 * WHY : Assumptions: two different sentences for two consecutive presses is the reference's own
 *       behaviour, not a quirk of this screen. `COTRTLIC.cbl` L1536-L1540 answers the first press at
 *       the end of the browse with `'No more pages to display'` only when the last page has ALREADY
 *       been shown, and L1541 onward answers the first such press with
 *       `'No more pages for these search conditions'`; the last-page flag is set on the PF8 turn at
 *       L657-L661 and reset on any other key. Collapsing the pair would destroy one of the two
 *       strings transformation rule T8 protects.
 * @returns {Promise<void>} Resolves once both sentences have been asserted in order.
 */
async function distinguishesTheTwoForwardBoundarySentences(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await pressPfKey(user, 'PFK08');
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_MORE_RECORDS.text);

  await pressPfKey(user, 'PFK08');
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_MORE_PAGES_TO_DISPLAY);

  expect(browseSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts neither paging control is withdrawn at a boundary, on the same turn the sentence appears.
 *
 * WHY : ⚠️ Assumptions: this is the invariant a greyed key would break, and it is asserted on the SAME
 *       turn as the sentence rather than on a turn of its own, because the two are one behaviour: the
 *       reference answers a paging key at the end of the browse and re-sends the screen with a message
 *       -- `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1532-L1535 for the backward key and
 *       L1536-L1540 with L1541 onward for the forward one. It never refuses the key.
 *
 *       Assumptions: withdrawing one would not merely lose the sentence, it would REPLACE it.
 *       `ui/src/layout/usePfKeys.ts` answers a disabled binding through its unmapped-key path, so a
 *       backward key greyed on the opening page reports an invalid key exactly where the reference
 *       reports `'No previous pages to display'`.
 *
 *       Assumptions: the controls are found by the mapset's own legend text -- `COTRTLI.bms` declares
 *       the five descriptors this screen publishes -- so the case asserts about what an operator can
 *       actually reach rather than about a binding object.
 * @returns {Promise<void>} Resolves once both controls have been asserted live at their boundary.
 */
async function keepsBothPagingControlsLiveAtABoundary(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  const backward = screen.getByRole('button', { name: REF_TYPE_LIST_KEY_LABELS.PFK07 });
  const forward = screen.getByRole('button', { name: REF_TYPE_LIST_KEY_LABELS.PFK08 });

  // WHY : Assumptions: a single-page browse is at BOTH boundaries at once -- the hook names that
  //       position `ONLY` -- so one arrangement exercises both controls.
  await pressPfKey(user, 'PFK07');
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY);
  expect(backward).toBeEnabled();
  expect(forward).toBeEnabled();

  await pressPfKey(user, 'PFK08');
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_MORE_RECORDS.text);
  expect(backward).toBeEnabled();
  expect(forward).toBeEnabled();

  // WHY : Assumptions: the pointer path is exercised LAST and needs no band reset, because the forward
  //       press immediately above left a DIFFERENT sentence on the line -- so the backward sentence
  //       reappearing can only be this click's work. A legend button that is enabled and answers
  //       nothing is the same defect wearing a different mask, which is why reaching it by pointer is
  //       asserted and not inferred from the key path.
  await user.click(backward);
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY);

  expect(browseSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Registers the keyset-browse cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function keysetBrowseCases(): void {
  it('declares the row arity the program and the mapset agree on', declaresTheMeasuredRowArity);
  it('paints seven rows for a full page', paintsSevenRowsForAFullPage);
  it('paints fewer rows for a short page', paintsFewerRowsForAShortPage);
  it(
    "switches off the component's own offset pagination",
    switchesOffTheComponentsOffsetPagination,
  );
  it('replays the sealed cursor in both directions', replaysTheSealedCursorInBothDirections);
  it('reads only the four envelope members', readsOnlyTheFourEnvelopeMembers);
  it('paints the page ordinal without paging on it', paintsThePageOrdinalWithoutPagingOnIt);
  it('refuses the backward step on the first page', refusesTheBackwardStepOnTheFirstPage);
  it(
    'distinguishes the two forward-boundary sentences',
    distinguishesTheTwoForwardBoundarySentences,
  );
  it('keeps both paging controls live at a boundary', keepsBothPagingControlsLiveAtABoundary);
}

describe('transaction-type list keyset browse', keysetBrowseCases);

/**
 * One sentence the program holds as an `88`-level condition on a message field.
 *
 * WHY : Assumptions: the shape mirrors the catalog's own entry rather than flattening it to a string,
 *       because the declared field WIDTH is part of what is being checked: a sentence longer than the
 *       field it is moved into is truncated on the terminal, so the pair is the contract rather than
 *       the text alone.
 */
interface StatusSentenceCase {
  /** The catalog key, which is the baseline condition name. */
  readonly key: string;
  /** The entry as the catalog publishes it. */
  readonly entry: { readonly text: string; readonly declaredWidth: number; readonly line: number };
  /** The line this file measured the literal at, independently of the catalog. */
  readonly measuredLine: number;
}

/**
 * One sentence the program moves as a literal, which the catalog holds as a bare string.
 */
interface LiteralSentenceCase {
  /** The catalog key. */
  readonly key: string;
  /** The sentence as the catalog publishes it. */
  readonly text: string;
  /** The line this file measured the literal at. */
  readonly measuredLine: number;
  /** The lines the catalog's own provenance index records for it. */
  readonly recordedLines: readonly number[];
}

/**
 * The nine action and outcome sentences, each paired with the line it was measured at.
 *
 * WHY : Assumptions: a table rather than nine hand-written cases, because the assertion is identical
 *       for every row and the differences that matter — the key, the sentence and the line — are
 *       exactly what a table states at the call site. The nine are the `WS-INFO-MSG` prompts at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L240, L242, L244, L246 and L248 and the
 *       `WS-RETURN-MSG` outcomes at L252, L258, L260 and L262. Alternatives Considered: twenty-odd
 *       near-duplicate cases, which is what this replaces; they diverge under maintenance and a
 *       missing one is invisible.
 */
const ACTION_AND_OUTCOME_SENTENCES: readonly StatusSentenceCase[] = [
  {
    key: 'WS_INFORM_REC_ACTIONS',
    entry: STATUS_MESSAGES.COTRTLIC.WS_INFORM_REC_ACTIONS,
    measuredLine: 240,
  },
  { key: 'WS_INFORM_DELETE', entry: STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE, measuredLine: 242 },
  { key: 'WS_INFORM_UPDATE', entry: STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE, measuredLine: 244 },
  {
    key: 'WS_INFORM_DELETE_SUCCESS',
    entry: STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS,
    measuredLine: 246,
  },
  {
    key: 'WS_INFORM_UPDATE_SUCCESS',
    entry: STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE_SUCCESS,
    measuredLine: 248,
  },
  { key: 'WS_EXIT_MESSAGE', entry: STATUS_MESSAGES.COTRTLIC.WS_EXIT_MESSAGE, measuredLine: 252 },
  {
    key: 'WS_MESG_MORE_THAN_1_ACTION',
    entry: STATUS_MESSAGES.COTRTLIC.WS_MESG_MORE_THAN_1_ACTION,
    measuredLine: 258,
  },
  {
    key: 'WS_MESG_INVALID_ACTION_CODE',
    entry: STATUS_MESSAGES.COTRTLIC.WS_MESG_INVALID_ACTION_CODE,
    measuredLine: 260,
  },
  {
    key: 'WS_MESG_NO_CHANGES_DETECTED',
    entry: STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_CHANGES_DETECTED,
    measuredLine: 262,
  },
];

/**
 * The two empty-result and paging-boundary sentences the program holds as conditions.
 *
 * WHY : Assumptions: these two are separated from the three literal ones below only because the
 *       baseline declares them differently — L254 and L256 are `88`-level conditions on
 *       `WS-RETURN-MSG` while L1264, L1534 and L1539 are `MOVE`d literals — and not because they
 *       are a different kind of message. All five are asserted mutually distinct in one case, which
 *       is where the grouping is deliberately ignored.
 */
const CONDITIONED_BOUNDARY_SENTENCES: readonly StatusSentenceCase[] = [
  {
    key: 'WS_MESG_NO_RECORDS_FOUND',
    entry: STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_RECORDS_FOUND,
    measuredLine: 254,
  },
  {
    key: 'WS_MESG_NO_MORE_RECORDS',
    entry: STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_MORE_RECORDS,
    measuredLine: 256,
  },
];

/**
 * The three empty-result and paging-boundary sentences the program moves as literals.
 */
const LITERAL_BOUNDARY_SENTENCES: readonly LiteralSentenceCase[] = [
  {
    key: 'NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS',
    text: PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
    measuredLine: 1264,
    recordedLines: PROGRAM_MESSAGE_SOURCES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
  },
  {
    key: 'NO_PREVIOUS_PAGES_TO_DISPLAY',
    text: PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY,
    measuredLine: 1534,
    recordedLines: PROGRAM_MESSAGE_SOURCES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY,
  },
  {
    key: 'NO_MORE_PAGES_TO_DISPLAY',
    text: PROGRAM_MESSAGES.COTRTLIC.NO_MORE_PAGES_TO_DISPLAY,
    measuredLine: 1539,
    recordedLines: PROGRAM_MESSAGE_SOURCES.COTRTLIC.NO_MORE_PAGES_TO_DISPLAY,
  },
];

/**
 * The five empty-result and paging-boundary sentences, as one set.
 * @returns {readonly string[]} The five sentences, in baseline line order.
 */
function everyBoundarySentence(): readonly string[] {
  return [
    STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_RECORDS_FOUND.text,
    STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_MORE_RECORDS.text,
    PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
    PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY,
    PROGRAM_MESSAGES.COTRTLIC.NO_MORE_PAGES_TO_DISPLAY,
  ];
}

/**
 * Asserts one conditioned sentence is provenanced to the line it was measured at and fits its field.
 * @param {StatusSentenceCase} sentence - One table row.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function assertsConditionedSentence(sentence: StatusSentenceCase): void {
  expect(sentence.entry.line).toBe(sentence.measuredLine);
  expect(sentence.entry.text.length).toBeGreaterThan(0);
  // WHY : Assumptions: the field width is an upper bound rather than an equality, because the
  //       baseline moves a shorter literal into a wider field and pads the remainder — the terminal
  //       clears the rest of the field — so a sentence shorter than its width is normal and one
  //       longer than it could never have been displayed in full. The two widths in play are the
  //       program's own `WS-INFO-MSG PIC X(45)` and `WS-RETURN-MSG PIC X(75)`, the second of which is
  //       the shared content contract `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` declare as `PIC X(75)`
  //       at `app/cpy/CVCRD01Y.cpy` L28-L29.
  expect(sentence.entry.text.length).toBeLessThanOrEqual(sentence.entry.declaredWidth);
}

/**
 * Asserts one literal sentence carries the provenance the catalog records for it.
 * @param {LiteralSentenceCase} sentence - One table row.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function assertsLiteralSentence(sentence: LiteralSentenceCase): void {
  expect(sentence.text.length).toBeGreaterThan(0);
  expect(sentence.recordedLines).toContain(sentence.measuredLine);
}

/**
 * Asserts all five empty-result and boundary sentences stay distinct, case included.
 *
 * WHY : Alternatives Considered: collapsing the five onto one "no results" sentence. It is the
 *       reflex simplification and it would pass any smoke test, which is precisely the danger:
 *       transformation rule T8 carries every user-visible string across character for character, so
 *       the collapse would silently destroy four of them. The sharpest pair is L254 against L1264 —
 *       the same program, one writing `records` and the other `Records` — so the set is compared
 *       both as written and lower-cased, because a case-insensitive de-duplication would keep one
 *       and drop the other.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function keepsTheFiveBoundarySentencesDistinct(): void {
  const sentences = everyBoundarySentence();

  expect(new Set(sentences).size).toBe(sentences.length);
  expect(
    new Set(
      sentences.map(
        /**
         * Lower-cases one sentence for the case-insensitive comparison.
         * @param {string} sentence - One boundary sentence.
         * @returns {string} The sentence in lower case.
         */
        (sentence: string): string => sentence.toLowerCase(),
      ),
    ).size,
  ).toBe(sentences.length);

  expect(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_RECORDS_FOUND.text).toMatch(/^No records\b/u);
  expect(PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS).toMatch(
    /^No Records\b/u,
  );
}

/**
 * Asserts every whitespace and punctuation anomaly in this program's sentences survives.
 *
 * WHY : Assumptions: each anomaly is asserted with a pattern over the IMPORTED sentence rather than
 *       by comparing against a retyped copy, and that is what makes the case meaningful: a retyped
 *       expectation carrying the same mistake as the screen would pass. Every pattern below names
 *       the baseline line it was measured from, and none of them trims — the padding IS the
 *       transcription.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function preservesEveryWhitespaceAnomaly(): void {
  // WHY : Assumptions: L242 puts a space BEFORE its question mark, which a formatter would remove
  //       and which no other screen's prompt has.
  expect(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text).toMatch(/ \?/u);

  // WHY : Assumptions: L246 has NO space after its full stop and L252, in the same program, does.
  //       The pair is asserted together because normalising either one would look like tidying.
  expect(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text).toMatch(/\.[A-Za-z]/u);
  expect(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text).not.toMatch(/\. /u);
  expect(STATUS_MESSAGES.COTRTLIC.WS_EXIT_MESSAGE.text).toMatch(/\. [A-Za-z]/u);

  // WHY : Assumptions: L248 ends without punctuation, unlike L262 which ends with a full stop.
  expect(STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE_SUCCESS.text).not.toMatch(/[.?!]$/u);
  expect(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_CHANGES_DETECTED.text).toMatch(/\.$/u);

  // WHY : Assumptions: L258 uses the DIGIT one rather than the word, so a rewording that spelled it
  //       out would read naturally and still be wrong.
  expect(STATUS_MESSAGES.COTRTLIC.WS_MESG_MORE_THAN_1_ACTION.text).toMatch(/ 1 /u);
  expect(STATUS_MESSAGES.COTRTLIC.WS_MESG_MORE_THAN_1_ACTION.text).not.toMatch(/\bone\b/iu);

  // WHY : Assumptions: L1116 is entirely upper case and has no space after its comma. Both are
  //       properties of the literal, not of the field it is moved into.
  const filterRefusal =
    PROGRAM_MESSAGES.COTRTLIC.TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER;
  expect(filterRefusal).toBe(filterRefusal.toUpperCase());
  expect(filterRefusal).toMatch(/,[A-Z]/u);

  // WHY : Assumptions: L1864 carries a space before its question mark AND a trailing space. The
  //       trailing one separated the sentence from the `SQLCODE` the baseline appended; the catalog
  //       transcribes it because the invariant is the source bytes rather than the bytes the target
  //       happens to need.
  expect(PROGRAM_MESSAGES.COTRTLIC.RECORD_NOT_FOUND_DELETED_BY_OTHERS).toMatch(/ \? $/u);

  // WHY : Assumptions: the two composed fragments carry LEADING spaces, because the baseline
  //       `STRING`s a trimmed field name in front of each — `' must be supplied.'` at L1198 and
  //       `' can have numbers or alphabets only.'` at L1225 — so the space is the separator.
  expect(FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED).toMatch(/^ /u);
  expect(FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY).toMatch(/^ /u);
  expect(FIELD_VALIDATION_SUFFIX_SOURCES.MUST_BE_SUPPLIED).toContainEqual({
    file: 'app/app-transaction-type-db2/cbl/COTRTLIC.cbl',
    lines: [1198],
  });
  expect(FIELD_VALIDATION_SUFFIX_SOURCES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY).toContainEqual({
    file: 'app/app-transaction-type-db2/cbl/COTRTLIC.cbl',
    lines: [1225],
  });

  // WHY : Assumptions: L1919 ends with a COLON, because the baseline appended the offending child
  //       rows after it. Dropping the colon would leave a sentence that reads finished when it is an
  //       introduction.
  expect(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST).toMatch(/:$/u);
}

/**
 * Asserts the three renderings of the exit sentence remain three separate entries.
 *
 * WHY : Assumptions: one idea, three literals, and the catalog holds all three. This program writes
 *       `'PF03 pressed. Exiting'` with a space and no padding at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L252; `app/cbl/COACTVWC.cbl` L120 writes it
 *       with no space and trailing padding; `app/cbl/COCRDLIC.cbl` L120 writes it entirely upper
 *       case. Any de-duplication that compared case-insensitively or trimmed would keep one and
 *       change what the other two screens render.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function keepsTheThreeExitSentencesDistinct(): void {
  const renderings = [
    STATUS_MESSAGES.COTRTLIC.WS_EXIT_MESSAGE.text,
    STATUS_MESSAGES.COACTVWC.WS_EXIT_MESSAGE.text,
    STATUS_MESSAGES.COCRDLIC.WS_EXIT_MESSAGE.text,
  ];

  expect(new Set(renderings).size).toBe(renderings.length);
  expect(STATUS_MESSAGES.COTRTLIC.WS_EXIT_MESSAGE.text).toMatch(/\. Exiting$/u);
  expect(STATUS_MESSAGES.COACTVWC.WS_EXIT_MESSAGE.text).toMatch(/\.Exiting {2}/u);
  expect(STATUS_MESSAGES.COCRDLIC.WS_EXIT_MESSAGE.text).toBe(
    STATUS_MESSAGES.COCRDLIC.WS_EXIT_MESSAGE.text.toUpperCase(),
  );
}

/**
 * Registers the message-catalog cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function messageCatalogCases(): void {
  it.each(ACTION_AND_OUTCOME_SENTENCES)(
    '$key is provenanced to line $measuredLine and fits its field',
    assertsConditionedSentence,
  );
  it.each(CONDITIONED_BOUNDARY_SENTENCES)(
    '$key is provenanced to line $measuredLine and fits its field',
    assertsConditionedSentence,
  );
  it.each(LITERAL_BOUNDARY_SENTENCES)(
    '$key is provenanced to line $measuredLine',
    assertsLiteralSentence,
  );
  it('keeps the five boundary sentences distinct', keepsTheFiveBoundarySentencesDistinct);
  it('preserves every whitespace anomaly', preservesEveryWhitespaceAnomaly);
  it('keeps the three exit sentences distinct', keepsTheThreeExitSentencesDistinct);
}

describe('transaction-type list message catalog', messageCatalogCases);

/**
 * The attention identifiers this screen neither advertises nor treats specially.
 *
 * WHY : Assumptions: the list is the complement of the six the reference admits at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L575-L581, narrowed to those a browser can
 *       raise. `CLEAR`, `PA1` and `PA2` are deliberately absent: `ui/src/layout/usePfKeys.ts` records
 *       that measured usage of the three across all 21 online programs is zero and gives them no web
 *       key, so `pressPfKey` throws for them by design and their contract belongs to that hook's own
 *       suite rather than to this screen.
 */
const UNADVERTISED_KEYS: readonly CicsAid[] = [
  'PFK01',
  'PFK04',
  'PFK05',
  'PFK06',
  'PFK09',
  'PFK11',
  'PFK12',
];

/**
 * Asserts an idle page carries the action prompt the reference paints on entry.
 * @returns {Promise<void>} Resolves once the prompt has been asserted.
 */
async function paintsTheActionPromptOnAnIdlePage(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_REC_ACTIONS.text);
}

/**
 * Asserts an unfiltered browse that returns nothing paints the search-condition sentence.
 *
 * WHY : Assumptions: this is the L254 sentence and not the L1264 one, and the difference is the
 *       FILTER rather than the emptiness. `1290-CROSS-EDITS` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1260-L1266 moves the capital-R sentence only
 *       once a filter has been applied, so an empty unfiltered page keeps the lower-case one. The two
 *       are asserted in separate cases for exactly that reason.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function paintsTheEmptySearchSentenceWithNoFilter(): Promise<void> {
  await mountAsAdministrator([]);

  await screen.findAllByText(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_RECORDS_FOUND.text);
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_RECORDS_FOUND.text);
  expectGridPlaceholderExplains(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_RECORDS_FOUND.text);
}

/**
 * Asserts a filtered browse that returns nothing paints the capital-R filter sentence.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function paintsTheFilteredEmptySentence(): Promise<void> {
  const user = await mountAsAdministrator([]);

  await user.type(typeFilter(), '99');
  await pressPfKey(user, 'ENTER');

  await screen.findAllByText(
    PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
  );
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS);
  expectGridPlaceholderExplains(
    PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
  );
}

/**
 * Asserts a service refusal of the filter reaches both the band and the refused control.
 *
 * WHY : Assumptions: the sentence appears TWICE — once on the row-23 line and once as the control's
 *       own help — so the case queries all matches rather than one. That is the field-error contract
 *       of `app/cpy/CSSETATY.cpy` L18-L27 expressed through the design system: the copybook moved
 *       `DFHRED` into the field's colour attribute AND left the message on the message line, so a
 *       screen that showed only one of the two would be dropping half of the template.
 * @returns {Promise<void>} Resolves once both surfaces have been asserted.
 */
async function paintsAServiceFilterRefusalOnTheControl(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  browseSpy().mockRejectedValue(
    refusal(
      400,
      apiError({
        fieldErrors: [
          fieldError(
            'typeCode',
            PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
          ),
        ],
      }),
      'the service refused the filter',
    ),
  );

  await user.type(typeFilter(), '77');
  await pressPfKey(user, 'ENTER');

  await screen.findAllByText(
    PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
  );
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS);
  expect(typeFilter()).toHaveAttribute('aria-invalid', 'true');
}

/**
 * Asserts an accepted delete action paints the confirm prompt, spaced question mark included.
 * @returns {Promise<void>} Resolves once the prompt has been asserted.
 */
async function paintsTheDeleteConfirmPrompt(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text);
}

/**
 * Reports the destructive confirmation, as a consumer of the accessibility tree finds it.
 *
 * ⚠ Refactoring Rationale: this used to be a geometry arrangement, and its retirement is the point of
 * the change rather than a loss of coverage. The confirmation was an anchored `Popconfirm`, measured
 * mounting at `inset: -1000vh auto auto -1000vw` -- a rectangle around (-12800, -9000) -- because its
 * child was an antd `Form.Item`, which `ui/node_modules/antd/lib/form/FormItem/index.js` declares as a
 * plain function component with no `forwardRef` and no `nativeElement`, so `getDOM` at
 * `@rc-component/util/lib/Dom/findDOMNode.js` L18-L26 resolved no anchor and the guard at
 * `@rc-component/trigger/lib/hooks/useAlign.js` L102 turned every alignment attempt back. Proving that
 * an anchor HAD been resolved needed a fake `getBoundingClientRect` and a fake viewport installed for
 * the duration of the case, because jsdom performs no layout and `isVisible` is false for every
 * element in it -- so without the patch a resolved anchor and a null one were indistinguishable.
 *
 * The confirmation is now a `Modal`, which is anchored to the VIEWPORT and reads no anchor at all, so
 * there is no alignment left to fail and nothing for that arrangement to distinguish. Keeping it would
 * have left roughly 190 lines of library-internal scaffolding asserting a property the primitive
 * cannot lose. What replaces it is the property the migration was FOR: the surface is a dialog in the
 * accessibility tree, which the previous primitive could not be at any geometry --
 * `@rc-component/tooltip/lib/Popup.js` hardcodes `role="tooltip"` with no prop that overrides it.
 *
 * ⚠ Assumptions: the surface is queried by ROLE and by its accessible NAME, and both halves are
 * load-bearing. The role is what a surface regressing to a tooltip would lose, so every case in this
 * group would fail rather than silently passing against a tooltip; and the name is the reference's own
 * catalogued prompt, so the query also holds the dialogue to being titled with verbatim text.
 * @returns {HTMLElement} The open confirmation dialog.
 */
function confirmationDialog(): HTMLElement {
  return screen.getByRole('dialog', { name: STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text });
}

/**
 * Asserts the armed delete is presented as a dialog that names the row it would remove.
 *
 * ⚠ Assumptions: six properties are asserted together because the confirmation is safe only when all
 * six hold, and the first three are what the previous primitive could not provide at any position. It
 * is a `dialog` in the accessibility tree rather than a tooltip; it is MODAL, so the page behind it is
 * not reachable while the question stands; it is titled with the reference's own prompt. It NAMES the
 * row under the mapset's own column headings at `app/app-transaction-type-db2/bms/COTRTLI.bms`
 * L108-L119, rather than saying `HIGHLIGHTED row` and leaving an operator who armed the wrong cell no
 * way to check. Focus lands on the DECLINING control, so an unread Enter withdraws. And nothing has
 * been deleted at the point the question is asked.
 *
 * Assumptions: the prompt is additionally asserted to appear more than once, which is what proves the
 * dialogue painted it rather than the case passing on the band's copy alone. The band carries one copy
 * on row 21 -- it is a `WS-INFO-MSG` sentence -- and the dialogue's title carries the other.
 *
 * Assumptions: the accept is recognised by its DANGER styling and not by its label. AAP §0.3.2 maps a
 * destructive confirmation onto a solid dangerous accept, so the styling is the contract; a label
 * comparison would also pass for an accept styled like an ordinary save, which is the one thing this
 * control must not look like.
 * @returns {Promise<void>} Resolves once the confirmation has been asserted.
 */
async function presentsTheArmedDeleteAsADialogNamingItsRow(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);

  const dialog = confirmationDialog();

  expect(dialog).toHaveAttribute('aria-modal', 'true');
  expect(
    screen.getAllByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text).length,
  ).toBeGreaterThan(1);

  const named = within(dialog);

  expect(named.getByText(REF_TYPE_LIST_LABELS.typeColumn)).toBeInTheDocument();
  expect(named.getByText(FIRST_TYPE_CODE)).toBeInTheDocument();
  expect(named.getByText(REF_TYPE_LIST_LABELS.descriptionColumn)).toBeInTheDocument();
  expect(named.getByText('PURCHASE')).toBeInTheDocument();

  const { commit, abandon } = confirmationControls();

  expect(abandon).toHaveFocus();
  expect(commit.classList.contains('ant-btn-dangerous')).toBe(true);
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * Asserts an edited row awaiting a save paints the save prompt.
 *
 * WHY : Assumptions: the prompt follows the EDIT rather than the action code, and the ordering is the
 *       reference's. `2500-SETUP-MESSAGE` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`
 *       L1523-L1531 sets the update prompt only `IF WS-NO-INFO-MESSAGE`, and the description edit at
 *       L1060-L1076 has already claimed the message line with the no-change sentence when the value
 *       is untouched. So the first transmit of an untouched row reports no change and the prompt
 *       appears once a real edit has been made.
 * @returns {Promise<void>} Resolves once the prompt has been asserted.
 */
async function paintsTheSavePromptAfterAnEdit(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(descriptionEditor(SECOND_TYPE_CODE), ' RETAIL');
  await pressPfKey(user, 'ENTER');

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE.text);
}

/**
 * Asserts an untouched row reports no change and writes nothing.
 * @returns {Promise<void>} Resolves once the sentence and the silence have been asserted.
 */
async function reportsNoChangeWithoutWriting(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_CHANGES_DETECTED.text);
  expect(replaceSpy()).not.toHaveBeenCalled();
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * Asserts an action code outside the two-letter domain is refused, and refused per cell.
 *
 * WHY : Assumptions: the domain is exactly `'D'` and `'U'` —
 *       `88 SELECT-OK VALUES 'D', 'U'` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L183 — and
 *       this screen's refusal sentence is its own. The vocabulary differs across the four list
 *       screens that have one: `app/cbl/COTRN00C.cbl` L199 names a single valid value,
 *       `app/cbl/COUSR00C.cbl` L212 names two, `app/cbl/COCRDLIC.cbl` refuses in upper case, and this
 *       program pairs the L240 prompt with the L260 refusal. Substituting any of the others would
 *       change what an operator reads.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesAnActionCodeOutsideTheDomain(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, FIRST_TYPE_CODE, 'X');

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_INVALID_ACTION_CODE.text);
  expect(actionCell(FIRST_TYPE_CODE)).toHaveAttribute('aria-invalid', 'true');
  // WHY : Assumptions: the sibling row is asserted CLEAN, which is what proves the mapping is
  //       per-cell rather than per-table. `app/cpy/CSSETATY.cpy` L18-L27 reddens one named field, and
  //       the program's per-row error array at L190-L195 keeps one flag per row.
  expect(actionCell(SECOND_TYPE_CODE)).not.toHaveAttribute('aria-invalid');
}

/**
 * Asserts two marked rows are refused with the count sentence and both rows are flagged.
 *
 * WHY : Assumptions: both contributing rows carry the error and not merely the surplus one, because
 *       `COTRTLIC.cbl` L1024-L1027 sets the per-row error flag inside the arm that matched a VALID
 *       code, so an operator sees which choices are in conflict rather than a count with nothing
 *       indicated. This multi-row guard has no analogue on the other list screens.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesMoreThanOneAction(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await user.type(actionCell(FIRST_TYPE_CODE), REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(actionCell(SECOND_TYPE_CODE), REF_TYPE_ROW_ACTION_CODES.delete);
  await pressPfKey(user, 'ENTER');

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_MORE_THAN_1_ACTION.text);
  expect(actionCell(FIRST_TYPE_CODE)).toHaveAttribute('aria-invalid', 'true');
  expect(actionCell(SECOND_TYPE_CODE)).toHaveAttribute('aria-invalid', 'true');
  expect(replaceSpy()).not.toHaveBeenCalled();
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * Asserts both composed validation fragments surface with their leading space intact.
 *
 * WHY : Assumptions: the composition is asserted whole rather than fragment by fragment, because the
 *       baseline builds it that way: `1240-EDIT-ALPHANUM-REQD` `STRING`s the trimmed field name at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1194-L1200 and L1222-L1228 in front of the
 *       suffix, and the field name for this control is `'Transaction Desc'`, moved at L1083. Asserting
 *       the joined sentence is what proves the leading space survived the join rather than being
 *       trimmed at either end of it.
 * @returns {Promise<void>} Resolves once both refusals have been asserted.
 */
async function composesBothValidationFragments(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);

  await user.clear(descriptionEditor(SECOND_TYPE_CODE));
  await pressPfKey(user, 'ENTER');
  expectBandShows(
    `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`,
  );

  // WHY : Assumptions: the blank case additionally paints the literal asterisk. `app/cpy/CSSETATY.cpy`
  //       L24 moves `'*'` into the field only when the flag is BLANK, so the marker distinguishes an
  //       emptied field from a merely rejected one, and `FIELD_ERROR_TOKENS.blankMarker` is where the
  //       target holds that character.
  expect(descriptionEditor(SECOND_TYPE_CODE).closest('.ant-input-affix-wrapper')).toHaveTextContent(
    FIELD_ERROR_TOKENS.blankMarker,
  );

  await user.type(descriptionEditor(SECOND_TYPE_CODE), 'RETAIL*GOODS');
  await pressPfKey(user, 'ENTER');
  expectBandShows(
    `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY}`,
  );
}

/**
 * Asserts the editor survives a correction intact and is marked by the turn rather than by the draft.
 *
 * ⚠ WHY : Refactoring Rationale: two defects in one arrangement, and one of them was invisible.
 *
 *       The MARKER was driven by whatever the cell held at paint time, so it appeared the instant the
 *       operator emptied the field and before any key had been pressed. The reference writes it while
 *       re-sending the map after its own edit has run -- `2300-SCREEN-ARRAY-INIT` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1412-L1415, under `IF CHANGES-HAVE-OCCURRED`
 *       and gated on `FLG-ROW-DESCRIPTION-BLANK`, a flag L1194 sets during the edit. So the marker
 *       states a fact about the turn just taken, which an operator mid-keystroke has not taken yet.
 *
 *       The CONTROL was replaced on the first keystroke after a refusal, because the marker was a
 *       conditional `suffix` and `@rc-component/input/lib/BaseInput.js` wraps the input in a `<span>`
 *       only while an affix is present -- the defect antd warns about at `antd/lib/input/Input.js`
 *       L116. This screen never showed it, because the editor carries `autoFocus` and React focused
 *       the replacement as it mounted, so the keystrokes landed on a different element than the one
 *       they started on. Identity is therefore asserted here and not merely the value: a value check
 *       alone passes against the defect.
 * @returns {Promise<void>} Resolves once both properties have been asserted.
 */
async function keepsTheEditorMountedAcrossACorrection(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.clear(descriptionEditor(SECOND_TYPE_CODE));

  // WHY : Assumptions: asserted BEFORE the key press, which is the half of this the reference is
  //       explicit about -- an emptied cell that has not been transmitted has earned nothing yet.
  expect(screen.queryByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeNull();

  await pressPfKey(user, 'ENTER');
  expectBandShows(
    `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`,
  );
  expect(screen.getByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeInTheDocument();

  const armed = descriptionEditor(SECOND_TYPE_CODE);
  const correction = 'RETAIL PURCHASE';
  await user.type(armed, correction);

  expect(descriptionEditor(SECOND_TYPE_CODE)).toBe(armed);
  expect(armed).toBeInTheDocument();
  expect(armed).toHaveValue(correction);
}

/**
 * Asserts the no-changes turn is reported on the message line and attributed to no field.
 *
 * ⚠ WHY : Refactoring Rationale: measured before this was separated -- arming an update without editing
 *       the description reported `WS-MESG-NO-CHANGES-DETECTED` AND came back with the editor carrying
 *       `aria-invalid="true"` and an error description pointing at that same sentence. So a screen
 *       reader announced a fifty-character field, holding exactly the value the service had given it,
 *       as invalid; and the only route out of the marking was to change data the operator had not set
 *       out to change.
 *
 *       Assumptions: the reference draws this line itself, which is why the case asserts both halves
 *       rather than just the absence. `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1072 sets a
 *       MESSAGE selector for the no-changes turn while L1078 and L1086 set `INPUT-ERROR`, and only the
 *       latter reaches the row flag L1366-L1368 reddens. Reported, not refused.
 *
 *       Assumptions: `aria-describedby` is asserted absent as well as `aria-invalid`, because
 *       `fieldAriaProps` in `ui/src/layout/fieldHelp.tsx` emits them from separate answers and a field
 *       described by an error it is not marked for would still announce the sentence twice.
 * @returns {Promise<void>} Resolves once both halves have been asserted.
 */
async function reportsNoChangeWithoutMarkingTheField(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_CHANGES_DETECTED.text);

  const editor = descriptionEditor(SECOND_TYPE_CODE);
  expect(editor).toHaveValue(SECOND_DESCRIPTION);
  expect(editor).not.toHaveAttribute('aria-invalid');
  expect(editor).not.toHaveAttribute('aria-describedby');
  expect(screen.queryByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeNull();
}

/**
 * Asserts this screen never paints the shared invalid-key sentence, whatever key is pressed.
 *
 * WHY : Assumptions: `COTRTLIC` is one of only six programs that never move `CCDA-MSG-INVALID-KEY`,
 *       alongside `COACTVWC`, `COACTUPC`, `COCRDLIC`, `COCRDSLC` and `COCRDUPC`; its substitute for
 *       an unwanted key is the L260 action-code refusal. Fourteen other programs DO emit it, so an
 *       author generalising from the majority would add a sentence this screen's reference never
 *       shows — which is why this is asserted negatively and over every browser-raisable key rather
 *       than left implicit.
 *
 * WHY : Assumptions: the mechanism is that the screen registers a handler for EVERY member of the
 *       attention-identifier domain, so `usePfKeys` has nothing left to report as unmapped —
 *       `reportRejectedAid` in `ui/src/layout/usePfKeys.ts` is the only producer of that sentence and
 *       it fires only for an unmapped or disabled entry. That mirrors the reference, which rewrites
 *       any key outside its accepted set to ENTER at L585-L587 rather than complaining about it.
 * @returns {Promise<void>} Resolves once every key has been pressed and the sentence remains absent.
 */
async function neverPaintsTheInvalidKeySentence(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  for (const aid of UNADVERTISED_KEYS) {
    await pressPfKey(user, aid);
    expect(screen.queryByText(INVALID_KEY_PRESSED)).toBeNull();
  }

  await pressPfKey(user, 'ENTER');
  expect(screen.queryByText(INVALID_KEY_PRESSED)).toBeNull();
  expect(document.body.textContent ?? '').not.toContain(
    normaliseMessageBandValue(INVALID_KEY_PRESSED),
  );
}

/**
 * Asserts no schema object name and no validation alphabet reaches the operator.
 *
 * WHY : Assumptions: three classes of baseline constant are deliberately not user-visible text. The
 *       physical table name `'TRANSACTION_TYPE '` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L57 names a database object; the alphabet and
 *       digit constants at L68, L70 and L72 are validation DATA that the composed L1225 refusal
 *       describes in prose; and the edit variable name at L1083 is a field label that surfaces only
 *       inside that composed sentence. Rendering any of them raw would leak either a schema detail or
 *       an implementation table into the browser.
 * @returns {Promise<void>} Resolves once the sweep has completed.
 */
async function leaksNoSchemaObjectOrValidationAlphabet(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  const rendered = document.body.textContent ?? '';
  expect(rendered).not.toContain('TRANSACTION_TYPE');
  expect(rendered).not.toContain('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  expect(rendered).not.toContain('abcdefghijklmnopqrstuvwxyz');
  expect(rendered).not.toContain('0123456789');
}

/**
 * Mounts the screen with the opening browse rejected, so a failure arm is what paints the band.
 *
 * Assumptions: the browse is rejected BEFORE the render rather than after it, because the opening read
 * is issued by the screen's own arrival effect -- rejecting it afterwards would paint the success arm
 * first and assert against whichever arm happened to win.
 * ⚠️ Assumptions: the document's `message` member defaults to EMPTY, and every case that does not name
 * one is asserting the sentence this screen chooses for the condition. The member is a parameter because
 * one case asserts the opposite property -- that a service's own sentence is preferred inside the two
 * classified arms -- and building that document by hand would duplicate the rest of this helper.
 * @param {number} status - HTTP status the rejection carries, on the failure and in its document.
 * @param {string | null} supplied - Sentence the service's own document carries, or `null` for none.
 * @returns {Promise<void>} Resolves once the browse has been answered.
 */
async function mountWithRefusedBrowse(
  status: number,
  supplied: string | null = null,
): Promise<void> {
  browseSpy().mockRejectedValue(
    refusal(
      status,
      apiError({ status, message: supplied }),
      `the service answered ${String(status)} to the browse`,
    ),
  );
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  await renderInAppShell(<RefTypeListScreen />, {
    initialEntries: [REF_TYPE_LIST_PATH],
    routePath: REF_TYPE_LIST_PATH,
  });
  await waitFor(
    /**
     * Asserts the opening read has been answered.
     * @returns {void} Nothing; throws until the browse has settled.
     */
    (): void => {
      expect(browseSpy()).toHaveBeenCalled();
    },
  );
}

/**
 * Asserts an authority refusal of the browse reads as a refusal and not as a task termination.
 *
 * WHY : Assumptions: the abend sentence is asserted ABSENT as well as the refusal sentence present,
 *       because the defect was not a missing sentence -- it was the wrong one winning. Every browse
 *       failure except the filtered-empty refusal reported `'UNEXPECTED ABEND OCCURRED.'`, so an
 *       operator whose token carried no CardDemo group was told the system had failed. That sentence
 *       is the registered replacement for the Db2 diagnostic
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1690-L1725 composes when the cursor itself
 *       fails, and it belongs to that condition alone: a refusal leaves the data intact and leaves the
 *       operator an action, and a fault does neither.
 *
 *       Assumptions: the sentence asserted is the catalogue's authored refusal rather than whatever
 *       prose the response carried. Alternatives Considered: echoing the document's own `message`.
 *       Rejected because an operator sentence in this application is catalogued -- rule T8 -- and the
 *       service's phrasing is not in the catalogue, so echoing it would put uncatalogued text on the
 *       glass and would read differently here than on every other screen that answers the same
 *       refusal.
 * @returns {Promise<void>} Resolves once both halves have been asserted.
 */
async function reportsAnAuthorityRefusalAsARefusal(): Promise<void> {
  await mountWithRefusedBrowse(403);

  await screen.findAllByText(ACCESS_DENIED_NOT_AUTHORIZED);
  expectBandShows(ACCESS_DENIED_NOT_AUTHORIZED);
  expectGridPlaceholderExplains(ACCESS_DENIED_NOT_AUTHORIZED);
  expect(document.body.textContent ?? '').not.toContain(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
}

/**
 * Asserts a genuine service fault still reports the registered abend replacement.
 *
 * WHY : Assumptions: this is the other half of the refusal case and exists so the correction cannot be
 *       made by deleting the fault arm. A five-hundred is the condition L1690-L1725 composes its
 *       diagnostic for, so its registered replacement is what the operator must see -- and the case
 *       additionally sweeps the glass for the diagnostic's own decorations, which is the invariant that
 *       registration exists to hold.
 * @returns {Promise<void>} Resolves once the fault arm has been asserted.
 */
async function reportsAServiceFaultAsAFault(): Promise<void> {
  await mountWithRefusedBrowse(500);

  await screen.findAllByText(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  expectBandShows(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  expectGridPlaceholderExplains(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);

  const rendered = document.body.textContent ?? '';
  expect(rendered).not.toContain(ACCESS_DENIED_NOT_AUTHORIZED);
  expect(rendered).not.toContain('SQLCODE');
}

/**
 * Asserts a service's own sentence is preferred over the authored classification on the browse path.
 *
 * ⚠️ Assumptions: this is the browse-path half of the same property the commit path asserts in
 * `shows the service's own sentence for a classified failure`, and both halves are needed because the
 * two failures travel through different selectors -- the browse reads the document the paging hook
 * publishes, and a commit reads the rejection the call raised. A case on one path would leave the other
 * arm unexercised.
 *
 * Assumptions: the sentence appears in BOTH places the screen explains a failed read -- the outcome line
 * and the empty grid -- because a supplied sentence that reached only one of them would leave the other
 * carrying a sentence the screen chose for a condition the service had already named.
 * @returns {Promise<void>} Resolves once the supplied sentence has been asserted.
 */
async function showsTheServicesOwnSentenceForARefusedBrowse(): Promise<void> {
  const supplied = 'The reference service is restarting.';
  await mountWithRefusedBrowse(TRANSIENT_GATEWAY_STATUS, supplied);

  await screen.findAllByText(supplied);
  expectBandShows(supplied);
  expectGridPlaceholderExplains(supplied);
  expect(
    document.body.textContent ?? '',
    'a sentence the service supplied replaces the authored classification rather than joining it',
  ).not.toContain(TRANSIENT_FAILURE_TRY_AGAIN);
}

/**
 * Asserts a transient condition is reported as one, rather than as a task termination.
 *
 * ⚠ WHY : Purpose: every browse failure except the two named above reported
 *       `'UNEXPECTED ABEND OCCURRED.'`, which is the registered replacement for the Db2 diagnostic
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1690-L1725 composes when the cursor itself
 *       fails. A gateway that was briefly unavailable is not that condition: the data is intact, the
 *       operator's remedy is to press the key again, and the abend sentence tells them the opposite.
 *
 *       Assumptions: the branch is taken on the failure's own `transient` judgement through
 *       `isTransientFailure` rather than on a status list written here. `ui/src/api/client.ts` owns
 *       that judgement — 408, 429, 502, 503 and 504 plus every timeout — and it deliberately EXCLUDES
 *       500, which is why the fault case above still reports the abend sentence. Duplicating the list
 *       in the screen would let the two drift.
 * @returns {Promise<void>} Resolves once the transient arm has been asserted.
 */
async function reportsATransientConditionAsTransient(): Promise<void> {
  await mountWithRefusedBrowse(TRANSIENT_GATEWAY_STATUS);

  await screen.findAllByText(TRANSIENT_FAILURE_TRY_AGAIN);
  expectBandShows(TRANSIENT_FAILURE_TRY_AGAIN);
  expectGridPlaceholderExplains(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(document.body.textContent ?? '').not.toContain(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
}

/**
 * Asserts a request that never reached a service is reported as the persistent condition it is.
 *
 * ⚠ WHY : Assumptions: the failure is built with the `NETWORK` kind and NO HTTP status, because that
 *       is the only shape the transport gives a dropped connection — `ui/src/api/client.ts` synthesises
 *       a problem document for it and leaves the status at zero, since no service answered. A screen
 *       that keyed this arm on a status would have nothing to key on.
 *
 *       Assumptions: the sentence is the persistent one and NOT the transient one, even though a
 *       dropped connection often clears. The judgement belongs to the transport and it declines to call
 *       this transient, so the screen tells the operator to report it rather than promising that
 *       pressing the key again will work.
 * @returns {Promise<void>} Resolves once the persistent arm has been asserted.
 */
async function reportsAnUnreachableServiceAsPersistent(): Promise<void> {
  browseSpy().mockRejectedValue(
    new ApiRequestError(
      'NETWORK',
      NO_TRANSPORT_STATUS,
      apiError({ status: NO_TRANSPORT_STATUS, message: null }),
      'the browse received no response at all',
    ),
  );
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  await renderInAppShell(<RefTypeListScreen />, {
    initialEntries: [REF_TYPE_LIST_PATH],
    routePath: REF_TYPE_LIST_PATH,
  });

  await screen.findAllByText(PERSISTENT_FAILURE_REPORT_IT);
  expectBandShows(PERSISTENT_FAILURE_REPORT_IT);
  expectGridPlaceholderExplains(PERSISTENT_FAILURE_REPORT_IT);
  expect(document.body.textContent ?? '').not.toContain(TRANSIENT_FAILURE_TRY_AGAIN);
}

/**
 * Registers the rendered-message cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function renderedMessageCases(): void {
  it('reports an authority refusal as a refusal', reportsAnAuthorityRefusalAsARefusal);
  it('reports a service fault as a fault', reportsAServiceFaultAsAFault);
  it('reports a transient condition as transient', reportsATransientConditionAsTransient);
  it(
    "shows the service's own sentence for a refused browse",
    showsTheServicesOwnSentenceForARefusedBrowse,
  );
  it('reports an unreachable service as persistent', reportsAnUnreachableServiceAsPersistent);
  it('paints the action prompt on an idle page', paintsTheActionPromptOnAnIdlePage);
  it('paints the empty search sentence with no filter', paintsTheEmptySearchSentenceWithNoFilter);
  it('paints the filtered-empty sentence', paintsTheFilteredEmptySentence);
  it('paints a service filter refusal on the control', paintsAServiceFilterRefusalOnTheControl);
  it('paints the delete confirm prompt', paintsTheDeleteConfirmPrompt);
  it(
    'presents the armed delete as a dialog naming its row',
    presentsTheArmedDeleteAsADialogNamingItsRow,
  );
  it('paints the save prompt after an edit', paintsTheSavePromptAfterAnEdit);
  it('reports no change without writing', reportsNoChangeWithoutWriting);
  it('refuses an action code outside the domain', refusesAnActionCodeOutsideTheDomain);
  it('refuses more than one action', refusesMoreThanOneAction);
  it('composes both validation fragments', composesBothValidationFragments);
  it('keeps the editor mounted across a correction', keepsTheEditorMountedAcrossACorrection);
  it('reports no change without marking the field', reportsNoChangeWithoutMarkingTheField);
  it('never paints the invalid-key sentence', neverPaintsTheInvalidKeySentence);
  it('leaks no schema object name or validation alphabet', leaksNoSchemaObjectOrValidationAlphabet);
}

describe('transaction-type list rendered messages', renderedMessageCases);

/**
 * Accessible name of the dialogue's committing control.
 *
 * Assumptions: this is the design system's stock accept label. The screen deliberately does not
 * relabel it with the confirming key's legend, and the reason is recorded on
 * {@link confirmationControls}.
 */
const CONFIRMATION_ACCEPT_NAME = 'OK';

/** Accessible name of the dialogue's declining control, which is the design system's stock label. */
const CONFIRMATION_DECLINE_NAME = 'Cancel';

/**
 * The two controls the destructive-action confirmation offers.
 */
interface ConfirmationControls {
  /** The control that commits the delete, which must be the dangerous one. */
  readonly commit: HTMLElement;
  /** The control that abandons it. */
  readonly abandon: HTMLElement;
}

/**
 * Locates the open confirmation's two decisions, and asserts the committing one is the dangerous one.
 *
 * ⚠ Refactoring Rationale: both controls are now selected by ACCESSIBLE NAME inside the dialog, where
 * this read every button on the surface and told the two apart by danger styling alone. That rule was
 * exact for a `Popconfirm`, which renders precisely two buttons; a `Modal` renders a THIRD -- the
 * close control in its header, named `Close` -- and it is not dangerous, so "the first control without
 * danger styling" would have resolved the abandoning control to the header's cross. Every case that
 * dismisses through `abandon` would then have been exercising a different route from the one an
 * operator uses, and would have passed.
 *
 * ⚠ Assumptions: the danger styling is still ASSERTED even though it is no longer the locator, and
 * that separation is deliberate. AAP §0.3.2 maps a destructive confirmation onto a solid dangerous
 * accept, so the styling remains the contract; selecting by name and then asserting the styling keeps
 * both facts checked, where selecting BY the styling silently conflated "the control I mean" with "the
 * control looks right" and could not fail on the second.
 *
 * Assumptions: the two names are the design system's own defaults, which is correct here rather than
 * an omission. The reference had no pointer and therefore no accept or cancel button to transcribe,
 * and the confirming key's legend is `F10=Save` at `app/app-transaction-type-db2/bms/COTRTLI.bms`
 * L332-L336 -- so borrowing the legend, which is what the migrated user-delete dialogue does with its
 * own `F5=Delete`, would label a destructive accept "Save" here.
 * @returns {ConfirmationControls} The committing and abandoning controls.
 * @throws {Error} If no confirmation is open, or if its committing control is not the dangerous one —
 *   either means the destructive action is no longer gated the way the design system requires.
 */
function confirmationControls(): ConfirmationControls {
  const named = within(confirmationDialog());
  const commit = named.getByRole('button', { name: CONFIRMATION_ACCEPT_NAME });
  const abandon = named.getByRole('button', { name: CONFIRMATION_DECLINE_NAME });

  if (!commit.classList.contains('ant-btn-dangerous')) {
    throw new Error('the confirmation must offer its committing control as the dangerous one');
  }

  return { commit, abandon };
}

/**
 * Both of the confirmation's controls describe themselves with the row being destroyed.
 *
 * ⚠️ Purpose: the dialogue names the row in its body, and a browser pass measured focus landing on the
 * DECLINING control the instant it opens. A description carried only by the surface as a whole is
 * therefore never read: the operator is placed on a button called `Cancel` under a title that says a
 * highlighted row will be deleted, and at that moment nothing says WHICH row. Naming the record on both
 * controls closes that, and it is the arrangement `ui/src/screens/cardUpdate/index.tsx` and
 * `ui/src/screens/authDetail/index.tsx` already use -- this screen was the outlier.
 *
 * ⚠️ Assumptions: the reference is RESOLVED through the document and its text inspected, not compared to
 * a constant. A dangling `aria-describedby` promises a description and delivers silence, which is worse
 * than none at all, so what is asserted is that the identifier names an element that exists and that the
 * element holds the row's own type code. An attribute-equality check would pass for a reference pointing
 * at nothing.
 *
 * ⚠️ Assumptions: BOTH controls are checked. They are configured through separate props, so wiring one
 * and not the other is a one-line mistake that leaves precisely the case that matters silent -- the
 * control focus is actually on.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function describesBothChoicesWithTheRowBeingDestroyed(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);

  const { commit, abandon } = confirmationControls();
  const undescribed: string[] = [];

  for (const control of [abandon, commit]) {
    const reference = control.getAttribute('aria-describedby') ?? '';
    const described = reference === '' ? null : document.getElementById(reference);

    if (described === null || !(described.textContent ?? '').includes(FIRST_TYPE_CODE)) {
      undescribed.push(control.textContent ?? '');
    }
  }

  expect(
    undescribed,
    'both choices must name the row, and the identifier must resolve to an element that holds it',
  ).toEqual([]);
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * Asserts a refused delete surfaces the child-records instruction and nothing from the database.
 *
 * WHY : Refactoring Rationale: the invariant stays in the DATABASE and only its presentation moves.
 *       `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` L6-L7 declares the foreign key
 *       `ON DELETE RESTRICT`, which AAP §0.4.1.3 preserves on `transaction_categories`, and AAP
 *       §0.5.1.6 requires that refusal to reach the client as an HTTP 409 rather than as a database
 *       error. The rejected alternative is to look for child rows in application code before
 *       deleting: it reads more helpfully and introduces a race the constraint does not have, because
 *       a category can be inserted between the check and the delete.
 *
 * WHY : Assumptions: the branch is keyed on the typed status through `isConflictFailure` rather than
 *       on the text of the message, which the case asserts directly on the fixture. A screen matching
 *       on wording would break the moment a sentence was reworded, and would treat any 500 carrying
 *       similar prose as a restrict refusal.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function surfacesTheRestrictRefusalAsAConflict(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  const refused = refusal(
    409,
    conflictProblem(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST),
    'the transaction type is still referenced by category rows',
  );
  deleteSpy().mockRejectedValue(refused);

  expect(isConflictFailure(refused)).toBe(true);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await screen.findByText(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST);
  expectBandShows(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST);
  expect(deleteSpy()).toHaveBeenCalledTimes(1);
  expect(deleteSpy()).toHaveBeenCalledWith(FIRST_TYPE_CODE);

  // WHY : Assumptions: the diagnostic the failure carries is developer-facing and must not reach the
  //       operator. The baseline appended the SQL code and the formatted diagnostic text to every one
  //       of this program's database messages through `9999-FORMAT-DB2-MESSAGE`, and
  //       `REDACTED_DIAGNOSTICS` registers those compositions precisely so the target shows a sentence
  //       instead of a status value.
  const rendered = document.body.textContent ?? '';
  expect(rendered).not.toContain('SQLCODE');
  expect(rendered).not.toContain('SQLSTATE');
  expect(rendered).not.toContain('-532');
}

/**
 * Asserts a delete that never reached the table is not reported as a delete the table refused.
 *
 * ⚠️ Purpose: every failed commit on this screen used to report `'Record delete failed'` or
 * `'Update of record failed'`, and both are the registered replacements for the diagnostics
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` composes when Db2 itself refuses the work. A throttle,
 * a gateway failure, a timeout or a dropped connection did not get that far, so either sentence sends the
 * operator -- and whoever they call -- to look for a database failure that never happened.
 *
 * ⚠️ Assumptions: the classification is read through `isTransientFailure` and not by comparing statuses,
 * because `ui/src/api/client.ts` is where the set is defined -- 408, 429, 502, 503 and 504 beside a
 * timeout -- and a screen re-deriving it would drift from it silently. 500 is deliberately NOT in that
 * set, which is what keeps the Db2 replacements reachable for the condition they were transcribed for.
 *
 * Assumptions: the replaced sentence is asserted ABSENT as well as the new one present, because the
 * defect is a MISREPORT rather than a missing sentence -- a case that only looked for the new text would
 * pass against a band carrying both.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function reportsATransientDeleteAsAConditionThatMayClear(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  const refused = refusal(
    503,
    apiError({ status: 503, message: null }),
    'the reference service answered 503 to the delete',
  );
  deleteSpy().mockRejectedValue(refused);
  expect(isTransientFailure(refused)).toBe(true);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await screen.findByText(TRANSIENT_FAILURE_TRY_AGAIN);
  expectBandShows(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(
    messageBand(),
    'a delete that never reached the table must not be reported as one the table refused',
  ).not.toHaveTextContent(STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text);
}

/**
 * Asserts a delete that reached nothing at all is reported as a request that did not complete.
 *
 * ⚠️ Assumptions: a dropped connection is the one failure on this path whose OUTCOME is genuinely
 * unknown -- the transport classifies it as `NETWORK` with no status and `transient` false, so it is
 * neither a condition to wait out nor a refusal the table issued, and the row may or may not be gone.
 * `'That request did not complete. Report it if it happens again.'` invites the re-read that answers it,
 * where `'Record delete failed'` invites a second attempt at destroying a row that may already have
 * been destroyed.
 *
 * Assumptions: the two sentences it must not borrow are both asserted absent, for the reason the case
 * above states.
 *
 *       Trade-offs: the honest fix for the ambiguity itself is an idempotency key on the delete, which
 *       lives in the service contract this change may not edit; it is reported to the dispatcher instead.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function reportsADeleteThatReachedNothingAsIncomplete(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  deleteSpy().mockRejectedValue(
    new ApiRequestError(
      'NETWORK',
      NO_TRANSPORT_STATUS,
      apiError({ status: NO_TRANSPORT_STATUS, message: null }),
      'no response reached the browser',
    ),
  );

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await screen.findByText(PERSISTENT_FAILURE_REPORT_IT);
  expectBandShows(PERSISTENT_FAILURE_REPORT_IT);
  expect(messageBand()).not.toHaveTextContent(STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text);
  expect(messageBand()).not.toHaveTextContent(TRANSIENT_FAILURE_TRY_AGAIN);
}

/**
 * Asserts a sentence the service supplied is shown verbatim in place of the authored classification.
 *
 * ⚠️ Assumptions: within the two classified arms the service's own sentence is the better one, because
 * both authored sentences classify a TRANSPORT outcome and say nothing about what was asked -- a service
 * that named the condition said more, and it is rendered exactly as received rather than paraphrased.
 *
 * ⚠️ Assumptions: the preference is scoped to those two arms and this case is what fixes the scope from
 * the outside. The control case `still reports a genuine failure as an abend` in
 * `ui/src/screens/refTypeListFilter.test.tsx` proves the opposite requirement for a 400 -- the
 * filtered-empty refusal is recognised by the document's FIELD ENTRIES and not by its prose -- and
 * measured: preferring prose ahead of that classification made that case report the filter refusal for a
 * genuine fault. So the two cases together pin both halves: prose wins inside the classified arms and
 * loses outside them.
 *
 * Assumptions: the authored sentence is asserted absent, so the case cannot pass against a band showing
 * both.
 * @returns {Promise<void>} Resolves once the supplied sentence has been asserted.
 */
async function showsTheServicesOwnSentenceForAClassifiedFailure(): Promise<void> {
  const supplied = 'The reference service is restarting.';
  const user = await mountAsAdministrator(twoRowPage());
  deleteSpy().mockRejectedValue(
    refusal(
      503,
      apiError({ status: 503, message: supplied }),
      'the reference service answered 503 with its own sentence',
    ),
  );

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await screen.findByText(supplied);
  expectBandShows(supplied);
  expect(
    messageBand(),
    'a sentence the service supplied replaces the authored classification rather than joining it',
  ).not.toHaveTextContent(TRANSIENT_FAILURE_TRY_AGAIN);
}

/**
 * Asserts the Escape key withdraws an armed row delete instead of leaving the confirmation inert.
 *
 * ⚠ WHY : Purpose: Escape did nothing at all against an armed delete. The confirmation stayed on the
 *       glass, which is safe, but an operator reaching for the key every other dialogue in the
 *       application answers got no response from the one guarding a destructive write -- and a control
 *       that ignores the standard withdrawal gesture teaches the operator to reach for something else,
 *       which on this screen is the confirming key.
 *
 *       ⚠️ Refactoring Rationale: this paragraph recorded that Escape does NOT reach `onCancel` -- the
 *       portal detected it and routed it through `onOpenChange`, which the anchored `Popconfirm` this
 *       surface used to be turned into a withdrawal -- and that is no longer the mechanism. A `Modal`
 *       calls `onCancel` for BOTH routes (`ui/node_modules/@rc-component/dialog/lib/Dialog/index.js`
 *       answers its own Escape), so the key and the declining control now provably arrive at ONE
 *       handler. The case is kept as two cases regardless, because they are the two routes an OPERATOR
 *       takes and a future primitive could split them again.
 *
 *       Assumptions: the row is asserted to be still LISTED afterwards, because a withdrawal that
 *       removed the row would be the contradiction this file's tombstone case exists to prevent, read
 *       the other way round.
 * @returns {Promise<void>} Resolves once the withdrawal has been asserted.
 */
async function withdrawsTheArmedDeleteOnEscape(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  deleteSpy().mockResolvedValue(undefined);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  expect(confirmationControls().abandon).toBeInTheDocument();

  await user.keyboard('{Escape}');

  await waitForTheConfirmationToClose();
  expect(deleteSpy(), 'Escape must not commit the delete it withdraws').not.toHaveBeenCalled();
  expect(
    within(grid()).getByText(FIRST_TYPE_CODE),
    'a withdrawn delete must leave the row exactly where it was',
  ).toBeInTheDocument();

  /*
   * ⚠ Assumptions: the two RESIDUES are asserted gone, because dismissing the dialogue and disarming
   * the request were separate operations and only the first was measured. With the dialogue withdrawn,
   * the row's action cell still held the typed `D` and the advisory line still read
   * `'Delete HIGHLIGHTED row ? Press F10 to confirm'` — a standing instruction to press the confirming
   * key, over a row that was provably no longer armed. The screen's state and its sentence have to
   * agree, and the sentence is the half an operator acts on.
   *
   * Assumptions: the advisory line is asserted to carry the STANDING prompt rather than to be empty.
   * `2500-SETUP-MESSAGE` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1504-L1512 sends
   * `'Type U to update, D to delete any record'` on every turn that has nothing more specific to say,
   * so a blank line here would be a divergence rather than a withdrawal.
   */
  expect(
    actionCell(FIRST_TYPE_CODE),
    'the typed action character must be withdrawn with the request it armed',
  ).toHaveValue('');
  expect(
    advisoryBand().textContent ?? '',
    'the confirmation prompt must not outlive the confirmation',
  ).not.toContain(normaliseMessageBandValue(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text));
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_REC_ACTIONS.text);

  // The confirming key finds nothing armed, so it cannot complete the withdrawn request.
  await pressPfKey(user, 'PFK10');

  expect(
    deleteSpy(),
    'a delete withdrawn by Escape must not be committed by the next press of the confirming key',
  ).not.toHaveBeenCalled();
}

/**
 * Waits until the destructive confirmation has left the accessibility tree.
 *
 * ⚠ Refactoring Rationale: this replaces a one-line assertion that no `Cancel` button was on the
 * glass, and both halves of the replacement are forced by the migration to a `Modal`. The old form
 * would now pass against a dialog that was still open, because a `Modal` renders a third control named
 * `Close` and the old query named only one of the two it did render; and it would fail against a
 * dialog that HAS closed, because the primitive parks its markup only once its leave animation reports
 * finishing and jsdom never runs one.
 *
 * ⚠ Assumptions: the leave animation is ended BY HAND, on every retry, and the event fired is
 * `transitionend` rather than `animationend`. The animation library listens for a native end event on
 * the panel (`@rc-component/motion/es/hooks/useDomMotionEvents.js` L21-L22) and resolves its own event
 * names by probing for a constructor and a style property (`util/motion.js` `getVendorPrefixes`):
 * jsdom exposes no `AnimationEvent`, so the animation name it settles on is the vendor-prefixed
 * `webkitAnimationEnd` that Testing Library cannot emit, while `TransitionEvent` does exist and keeps
 * the transition name unprefixed. Both names reach the one handler, which does not inspect the event's
 * type. Firing on every retry rather than once is required because the library discards an end event
 * that arrives before its own `requestAnimationFrame`-scheduled active step (`useStatus.js` gates on
 * `activeRef.current`); re-firing costs nothing once the panel has gone, since the query returns null.
 * @returns {Promise<void>} Resolves once the confirmation is no longer reachable.
 */
async function waitForTheConfirmationToClose(): Promise<void> {
  await waitFor(
    /**
     * Ends the leave animation if one is still running, then asserts the dialogue has gone.
     * @returns {void} Nothing; the expectation throws until the dialogue is unreachable.
     */
    function theConfirmationIsClosed(): void {
      const leaving = screen.queryByRole('dialog', {
        name: STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text,
      });

      if (leaving !== null) {
        fireEvent.transitionEnd(leaving);
      }

      expect(
        screen.queryByRole('dialog', { name: STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE.text }),
      ).not.toBeInTheDocument();
    },
  );
}

/**
 * Asserts abandoning the confirmation deletes nothing, and leaves nothing armed behind it.
 *
 * ⚠ WHY : Assumptions: the SECOND half is asserted because the first half alone is also true of a
 *       cancel that merely dismisses the overlay. The destructive review requires a cancel to be
 *       genuinely safe, and the way that fails in practice is a request left armed: the next press of
 *       the confirming key then commits with no confirmation on the glass at all. Here the overlay's
 *       `open` is derived from the pending request and cancelling clears it, so PF10 afterwards finds
 *       nothing to confirm and re-arms instead -- which is `COTRTLIC.cbl` L674's own answer to an
 *       unconfirmable PF10, turning it back into ENTER rather than guessing.
 * @returns {Promise<void>} Resolves once the silence and the disarming have been asserted.
 */
async function deletesNothingWhenTheConfirmationIsAbandoned(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  deleteSpy().mockResolvedValue(undefined);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().abandon);

  expect(deleteSpy()).not.toHaveBeenCalled();

  await pressPfKey(user, 'PFK10');

  expect(
    deleteSpy(),
    'a cancelled delete must not be committed by the next press of the confirming key',
  ).not.toHaveBeenCalled();
}

/**
 * Asserts a write in flight withdraws the exit key and cannot be committed a second time.
 *
 * ⚠ WHY : Purpose: this pins the fact two other decisions rest on, so neither can quietly stop being
 *       true. The destructive review requires that a bare key press cannot commit twice, and the
 *       reason this screen retains no outcome for a later `claimRetainedOutcome` is that an operator
 *       cannot LEAVE while a write is outstanding -- every entry in the key map is built with
 *       `disabled: committing`. If the exit key ever became live during a commit, the screen would
 *       unmount with a write in flight and its outcome would reach nobody, which is precisely the
 *       case the retained-outcome mechanism in `ui/src/api/client.ts` exists for.
 *
 *       Assumptions: the delete is HELD, because both properties only exist while it is outstanding.
 *
 *       Assumptions: the exit key is asserted by the ADDRESS rather than by a spy, matching the two
 *       transfer cases already in this file -- what `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`
 *       L591 and L630 do is transfer control, which rule T5 maps to an address change.
 * @returns {Promise<void>} Resolves once both properties have been asserted and the write settled.
 */
async function withholdsEveryKeyWhileTheWriteIsOutstanding(): Promise<void> {
  const user = await mountWithAddressProbe(twoRowPage());
  const heldDelete = heldCall<void>();
  deleteSpy().mockReturnValueOnce(heldDelete.promise);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await pressPfKey(user, 'PFK03');
  expect(renderedAddress(), 'the exit key must not leave a screen with a write in flight').toBe(
    REF_TYPE_LIST_PATH,
  );

  await pressPfKey(user, 'PFK10');
  expect(
    deleteSpy(),
    'the confirmation key must not issue a second deletion of the same row',
  ).toHaveBeenCalledTimes(1);

  heldDelete.settle(undefined);
  await screen.findByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text);
}

/**
 * Asserts a withdrawal is declined while the delete it authorised is still running.
 *
 * ⚠️ Purpose: the dialogue's own controls are reached by POINTER and never pass through the key hook,
 * so the `disabled: committing` declarations the key map makes cannot cover them. The accept is inert
 * through its own button props; CANCEL carries no loading state, so without a guard it stayed live for
 * the whole duration of the `DELETE` it had just authorised -- and accepting it there clears the pending
 * request while the write is in flight, after which the settlement writes
 * `'Transaction Type deleted successfully'` for a request the operator had just withdrawn. Escape reaches
 * the same handler, so it is the same window.
 *
 * ⚠️ Assumptions: the property asserted is that the ARMED REQUEST survives -- the row keeps the typed
 * action character it was armed with -- and NOT that the dialogue is still on the glass. Measured: with
 * the guard removed, the dialogue is still found either way, because a withdrawal starts the design
 * system's leave animation and jsdom never fires the transition that ends it, so the closing panel stays
 * in the document for the rest of the case. A presence assertion therefore passes against the defect,
 * which is why the case reads the state the withdrawal would have cleared instead: `withdrawPendingAction`
 * drops the row's typed code, so the code still being there is proof the withdrawal was declined.
 *
 * ⚠️ Assumptions: declining is SILENT rather than reported. The reference has no sentence for a key
 * pressed while the task holds the terminal -- it could not happen -- and inventing one would report an
 * operator's own confirming press back to them as a mistake.
 *
 * Assumptions: the delete is HELD and then settled, so the case proves the guard declines the withdrawal
 * WITHOUT swallowing the turn: a guard that discarded the outstanding work would satisfy the first half
 * and fail the success sentence at the end.
 * @returns {Promise<void>} Resolves once the declined withdrawal and the settled delete are asserted.
 */
async function declinesAWithdrawalWhileTheDeleteRuns(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  const heldDelete = heldCall<void>();
  deleteSpy().mockReturnValueOnce(heldDelete.promise);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await waitFor(
    /**
     * Waits until the delete has actually been issued.
     * @returns {void} Nothing; throws until the call has been made.
     */
    (): void => {
      expect(deleteSpy()).toHaveBeenCalledTimes(1);
    },
  );

  /*
   * WHY : ⚠ Assumptions: the declining control is queried directly rather than through
   *       {@link confirmationControls}, which reads BOTH controls and would fail here for a reason
   *       unrelated to this case. Measured: while the accept is loading, the design system's stock
   *       button renders a spinner whose own accessible name joins the label, so the accept's name is
   *       `'loading OK'` for the duration of the call rather than `'OK'`. That is the dependency's
   *       rendering and not this screen's -- `ui/src/layout/PfKeyBar.tsx` hides its own loading glyph
   *       from the accessibility tree for exactly this reason, and the stock button does not -- so the
   *       case works around it rather than asserting it.
   */
  await user.click(
    within(confirmationDialog()).getByRole('button', { name: CONFIRMATION_DECLINE_NAME }),
  );

  expect(
    actionCell(FIRST_TYPE_CODE),
    'a withdrawal arriving after the delete was issued must be declined, not honoured',
  ).toHaveValue(REF_TYPE_ROW_ACTION_CODES.delete);
  expect(deleteSpy()).toHaveBeenCalledTimes(1);

  heldDelete.settle(undefined);

  await screen.findByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text);
}

/**
 * Asserts the deleted row leaves the grid the moment its success is announced, and returns if delivered.
 *
 * ⚠ WHY : Purpose: this is the destructive review's "no success is announced while a contradicting
 *       value is displayed", on the one path of this screen that could break it. A delete answers 204
 *       and the screen then calls `browse.reset()`, but `usePagedQuery` documents at its own `reset`
 *       that the rows on display are RETAINED while that refresh is outstanding -- correct for a
 *       refresh of the same query, and wrong for a delete. Measured against that behaviour, the row
 *       the operator had just destroyed stayed listed underneath
 *       `'Transaction Type deleted successfully'` for the whole duration of the re-read.
 *
 *       Assumptions: the refresh is HELD rather than allowed to settle, because the contradiction
 *       exists only while it is outstanding. A case that let it resolve would assert the state after
 *       the window rather than inside it, and would pass against the defect.
 *
 *       Assumptions: the second half asserts the row COMES BACK when a delivered page carries it,
 *       which is the mirror hazard the suppression introduces -- a key suppressed after the service
 *       has said it exists would hide a code deleted and then created again through the maintenance
 *       screen. A page the service composed is the authority, so it retires the suppression outright.
 * @returns {Promise<void>} Resolves once both halves have been asserted.
 */
async function keepsTheDeletedRowOffTheGridWhileItsSuccessStands(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  deleteSpy().mockResolvedValue(undefined);

  const heldRefresh = heldCall<PageResponse<TransactionType>>();
  browseSpy().mockReturnValueOnce(heldRefresh.promise);

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await screen.findByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text);
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text);

  // Inside the window: the success stands, the refresh has not landed, and the row is already gone.
  expect(
    within(grid()).queryByText(SECOND_TYPE_CODE),
    'the deleted row must not be listed while its deletion is reported as successful',
  ).toBeNull();
  expect(
    within(grid()).getByText(FIRST_TYPE_CODE),
    'the rest of the retained page must stay on display',
  ).toBeInTheDocument();

  // A delivered page is the authority: it retires the suppression, whatever it carries.
  heldRefresh.settle(pageResponse<TransactionType>(twoRowPage()));
  await waitFor(expectTheDeletedKeyListedAgain);
}

/**
 * Asserts the previously suppressed key is listed once a delivered page carries it.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectTheDeletedKeyListedAgain(): void {
  expect(within(grid()).getByText(SECOND_TYPE_CODE)).toBeInTheDocument();
}

/**
 * Asserts a confirmed delete calls the service exactly once and reports the outcome sentence.
 *
 * WHY : Assumptions: "exactly once" is the assertion rather than "at least once", because the
 *       reference's two-step — mark the row, then press the save key to confirm — exists to make a
 *       destructive action deliberate, and a confirmation that fired twice would delete on a
 *       double-press. The two-step survives here as the action code plus the confirmation, and the
 *       outcome sentence at L246 is the reference's own, missing space after its full stop included.
 * @returns {Promise<void>} Resolves once the single call and its outcome have been asserted.
 */
async function deletesOnceWhenTheConfirmationIsAccepted(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  deleteSpy().mockResolvedValue(undefined);

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);

  await screen.findByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text);
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_DELETE_SUCCESS.text);
  expect(deleteSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts a genuine save calls the service once with the edited value and the version it was given.
 * @returns {Promise<void>} Resolves once the single call and its outcome have been asserted.
 */
async function savesOnceWithTheEditedDescription(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  const edited = `${SECOND_DESCRIPTION} RETAIL`;
  replaceSpy().mockResolvedValue({ typeCd: SECOND_TYPE_CODE, description: edited, version: 4 });

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(descriptionEditor(SECOND_TYPE_CODE), ' RETAIL');
  await pressPfKey(user, 'PFK10');

  await screen.findByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE_SUCCESS.text);
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE_SUCCESS.text);
  expect(replaceSpy()).toHaveBeenCalledTimes(1);
  // WHY : Assumptions: the version replayed is the one the row arrived with, which is what makes the
  //       optimistic-concurrency refusal reachable at all. It is the migrated form of the before-image
  //       comparison the baseline performs across the pseudo-conversational gap (AAP §0.7.2); a screen
  //       inventing a version would silently win every race.
  expect(replaceSpy()).toHaveBeenCalledWith(SECOND_TYPE_CODE, { description: edited, version: 3 });
}

/**
 * Asserts a stale update is refused with the concurrency sentence rather than the restrict one.
 *
 * WHY : Assumptions: an update conflict and a delete conflict share the status and NOT the sentence,
 *       and the two must not be merged. A stale row is `SQLCODE -911` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1874, whose registered replacement is the
 *       maintenance screen's "changed by some one else" sentence; a referenced parent row is `-532` at
 *       L1914-L1919. Telling an operator to delete child records that do not exist is the failure this
 *       separation prevents.
 * @returns {Promise<void>} Resolves once the concurrency sentence has been asserted.
 */
async function refusesAStaleUpdateWithTheConcurrencySentence(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  replaceSpy().mockRejectedValue(
    refusal(
      409,
      conflictProblem(STATUS_MESSAGES.COTRTUPC.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
      'the stored version no longer matches the version supplied',
    ),
  );

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(descriptionEditor(SECOND_TYPE_CODE), ' RETAIL');
  await pressPfKey(user, 'PFK10');

  await screen.findByText(STATUS_MESSAGES.COTRTUPC.DATA_WAS_CHANGED_BEFORE_UPDATE.text);
  expectBandShows(STATUS_MESSAGES.COTRTUPC.DATA_WAS_CHANGED_BEFORE_UPDATE.text);
  expect(
    screen.queryByText(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST),
  ).toBeNull();
}

/**
 * Asserts a server failure on each write reports that write's own generic sentence.
 *
 * WHY : Assumptions: the two sentences are the registered replacements for the two baseline
 *       compositions that only complete once a SQL code is appended — `'Update failed with'` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1883 and the delete equivalent at L1929 —
 *       so the target says which operation failed and never says with what. A five-hundred is used
 *       rather than a four-oh-four deliberately: the reference words a vanished row differently, and
 *       that wording is the catalog's `RECORD_NOT_FOUND_DELETED_BY_OTHERS` from L1864 rather than
 *       either of these two.
 * @returns {Promise<void>} Resolves once both generic sentences have been asserted.
 */
async function reportsAServerFailurePerOperation(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  const serverFailure = refusal(
    500,
    apiError({ code: 'CARDDEMO-0500', status: 500, message: null }),
    'the service failed while writing',
  );
  replaceSpy().mockRejectedValue(serverFailure);
  deleteSpy().mockRejectedValue(serverFailure);

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(descriptionEditor(SECOND_TYPE_CODE), ' RETAIL');
  await pressPfKey(user, 'PFK10');
  await screen.findByText(STATUS_MESSAGES.COTRTUPC.TABLE_UPDATE_FAILED.text);

  await user.clear(actionCell(SECOND_TYPE_CODE));
  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);
  await user.click(confirmationControls().commit);
  await screen.findByText(STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text);

  const rendered = document.body.textContent ?? '';
  expect(rendered).not.toContain('SQLCODE');
  expect(rendered).not.toContain('Deadlock');
}

/**
 * Asserts every Db2 diagnostic this program composes is registered with a replacement.
 *
 * WHY : Assumptions: this is the reconciliation between transformation rule T8 and the prohibition on
 *       leaking a database diagnostic, and it is asserted rather than assumed because the two pull in
 *       opposite directions. Ten of this program's sixteen literals exist only to carry a cursor name,
 *       a table name or a SQL code to the terminal, so `ui/src/messages/messages.ts` registers each of
 *       them in `REDACTED_DIAGNOSTICS` with the verbatim target sentence shown instead — the register
 *       is where the divergence is documented, which is what keeps it a documented divergence rather
 *       than a silent one. The lines asserted are the ones this file measured: the cursor names at
 *       L1686 and L1712, the backward fetch at L1784, the table name at L1826, the deadlock at L1874,
 *       and the two composed failures at L1883 and L1929.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function registersEveryDb2DiagnosticThisProgramComposes(): void {
  const registered: readonly RedactedDiagnostic[] = REDACTED_DIAGNOSTICS.filter(
    /**
     * Selects the entries belonging to this program.
     * @param {RedactedDiagnostic} entry - One register entry.
     * @returns {boolean} Whether it belongs to the transaction-type list program.
     */
    (entry: RedactedDiagnostic): boolean => entry.program === 'COTRTLIC',
  );

  expect(registered.length).toBeGreaterThan(0);
  for (const measuredLine of [1686, 1712, 1784, 1826, 1874, 1883, 1929]) {
    const entry = registered.find(
      /**
       * Recognises the entry covering one measured line.
       * @param {RedactedDiagnostic} candidate - One register entry.
       * @returns {boolean} Whether it covers that line.
       */
      (candidate: RedactedDiagnostic): boolean => candidate.lines.includes(measuredLine),
    );
    expect(
      entry,
      `no redaction is registered for COTRTLIC line ${String(measuredLine)}`,
    ).toBeDefined();
    expect(entry?.replacement.length ?? 0).toBeGreaterThan(0);
  }
}

/**
 * Registers the refused-write cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function refusedWriteCases(): void {
  it('surfaces the restrict refusal as a conflict', surfacesTheRestrictRefusalAsAConflict);
  it(
    'describes both choices with the row being destroyed',
    describesBothChoicesWithTheRowBeingDestroyed,
  );
  it(
    'deletes nothing when the confirmation is abandoned',
    deletesNothingWhenTheConfirmationIsAbandoned,
  );
  it('withdraws the armed delete on Escape', withdrawsTheArmedDeleteOnEscape);
  it('deletes once when the confirmation is accepted', deletesOnceWhenTheConfirmationIsAccepted);
  it(
    'keeps the deleted row off the grid while its success stands',
    keepsTheDeletedRowOffTheGridWhileItsSuccessStands,
  );
  it(
    'withholds every key while the write is outstanding',
    withholdsEveryKeyWhileTheWriteIsOutstanding,
  );
  it('declines a withdrawal while the delete runs', declinesAWithdrawalWhileTheDeleteRuns);
  it(
    'reports a transient delete as a condition that may clear',
    reportsATransientDeleteAsAConditionThatMayClear,
  );
  it(
    'reports a delete that reached nothing as incomplete',
    reportsADeleteThatReachedNothingAsIncomplete,
  );
  it(
    "shows the service's own sentence for a classified failure",
    showsTheServicesOwnSentenceForAClassifiedFailure,
  );
  it('saves once with the edited description', savesOnceWithTheEditedDescription);
  it(
    'refuses a stale update with the concurrency sentence',
    refusesAStaleUpdateWithTheConcurrencySentence,
  );
  it('reports a server failure per operation', reportsAServerFailurePerOperation);
  it(
    'registers every Db2 diagnostic this program composes',
    registersEveryDb2DiagnosticThisProgramComposes,
  );
}

describe('transaction-type list refused writes', refusedWriteCases);

/**
 * Reports the keyboard shortcut each legend control advertises.
 * @returns {readonly string[]} One shortcut per rendered control, in render order.
 */
function legendShortcuts(): readonly string[] {
  return legendButtons().map(
    /**
     * Reads one control's advertised shortcut.
     * @param {HTMLElement} control - One legend button.
     * @returns {string} Its `aria-keyshortcuts`, or the empty string when it carries none.
     */
    (control: HTMLElement): string => control.getAttribute('aria-keyshortcuts') ?? '',
  );
}

/**
 * Locates one legend control by the label the mapset paints on it.
 * @param {string} label - The measured legend literal.
 * @returns {HTMLElement} That control.
 * @throws {Error} If the legend does not advertise that label.
 */
function legendControl(label: string): HTMLElement {
  const control = legendButtons().find(
    /**
     * Recognises the control carrying one label.
     * @param {HTMLElement} candidate - One legend button.
     * @returns {boolean} Whether its text is that label.
     */
    (candidate: HTMLElement): boolean => candidate.textContent === label,
  );
  if (control === undefined) {
    throw new Error(`the legend does not advertise '${label}'`);
  }
  return control;
}

/**
 * Asserts the legend advertises exactly the five labels this mapset paints, in mapset order.
 *
 * WHY : Assumptions: the five are five separate `DFHMDF` fields rather than one legend string —
 *       `BUTNF02` through `BUTNF10` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L312-L336 — and
 *       two of them deliberately disagree with the vocabulary every other screen uses. This mapset
 *       paints `'F7=Page Up'` at L326 and `'F8=Page Dn'` at L331 where the seventeen base mapsets
 *       paint the wording `UNIFORM_PF_KEY_LABELS` publishes, and `'F3=Exit'` at L321 where the base
 *       screens say back. Taking the shared defaults would render two labels this screen does not
 *       have, so the divergence is asserted rather than tolerated.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function advertisesTheFiveMeasuredLegendLabels(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  expect(legendLabels()).toStrictEqual([
    REF_TYPE_LIST_KEY_LABELS.PFK02,
    REF_TYPE_LIST_KEY_LABELS.PFK03,
    REF_TYPE_LIST_KEY_LABELS.PFK07,
    REF_TYPE_LIST_KEY_LABELS.PFK08,
    REF_TYPE_LIST_KEY_LABELS.PFK10,
  ]);
  expect(REF_TYPE_LIST_KEY_LABELS.PFK07).not.toBe(UNIFORM_PF_KEY_LABELS.PFK07);
  expect(REF_TYPE_LIST_KEY_LABELS.PFK08).not.toBe(UNIFORM_PF_KEY_LABELS.PFK08);
  expect(REF_TYPE_LIST_KEY_LABELS.PFK07).toMatch(/=Page Up$/u);
  expect(REF_TYPE_LIST_KEY_LABELS.PFK08).toMatch(/=Page Dn$/u);
  expect(REF_TYPE_LIST_KEY_LABELS.PFK03).toMatch(/=Exit$/u);
}

/**
 * Asserts the legend advertises the two keys no other screen in the application uses.
 *
 * WHY : Assumptions: `PFK02` and `PFK10` appear on no other screen — every base mapset draws from
 *       ENTER, PF3, PF4, PF5, PF7, PF8 and PF12 — and the reference admits both here explicitly at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L576 and L580-L581, handling them at L630 and
 *       L666. `ui/src/layout/PfKeyBar.tsx` takes a per-screen descriptor array precisely so a screen
 *       can advertise keys outside the common set, which is why the two shortcuts are asserted on the
 *       controls rather than assumed from a shared default.
 * @returns {Promise<void>} Resolves once both keys have been asserted.
 */
async function advertisesTheTwoKeysUniqueToThisScreen(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  expect(legendShortcuts()).toStrictEqual(['F2', 'F3', 'F7', 'F8', 'F10']);
  expect(legendControl(REF_TYPE_LIST_KEY_LABELS.PFK02)).toHaveAttribute('aria-keyshortcuts', 'F2');
  expect(legendControl(REF_TYPE_LIST_KEY_LABELS.PFK10)).toHaveAttribute('aria-keyshortcuts', 'F10');
}

/**
 * Asserts each legend control renders the emphasis its own action's risk earns.
 *
 * ⚠ Refactoring Rationale: this case asserted that NO control was the primary variant, and that
 * assertion has been withdrawn rather than repaired. It was correct about the mechanism it measured —
 * the bar decided emphasis from the attention IDENTIFIER, reserving primary for ENTER and PF5, and
 * this screen advertises neither — and that mechanism cannot express this mapset: `PFK10` is the save
 * key here and a paging key elsewhere, so a table keyed on the identifier must be wrong for one of
 * them. `ui/src/layout/usePfKeys.ts` now carries a declared risk per handler and `PfKeyBar` paints
 * from it, so what the four browse and transfer keys render is still the default variant, and the one
 * key that changes stored data no longer renders identically to them.
 *
 * ⚠ Assumptions: `PFK10` is asserted TWICE, before and after a delete is armed, because the single
 * most important property of the risk primitive on this screen is that the classification follows the
 * armed request rather than the label. `F10=Save` at `app/app-transaction-type-db2/bms/COTRTLI.bms`
 * L332-L336 is verbatim on the control in both states — the legend never changes — while the key
 * removes a row in one of them and rewrites a description in the other. A case that measured only the
 * idle state would pass against a screen that painted a row deletion in the same emphasis as an edit.
 *
 * Assumptions: the four other controls are asserted to be neither primary nor dangerous. They are
 * classified `read-only` in the handler map: `F2=Add` transfers to the maintenance screen without
 * inserting anything (`app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L630-L652), `F3=Exit` leaves,
 * and the two paging keys read.
 * @returns {Promise<void>} Resolves once every variant has been asserted, in both states.
 */
async function paintsEachLegendControlFromItsRisk(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  const readOnly: readonly string[] = [
    REF_TYPE_LIST_KEY_LABELS.PFK02,
    REF_TYPE_LIST_KEY_LABELS.PFK03,
    REF_TYPE_LIST_KEY_LABELS.PFK07,
    REF_TYPE_LIST_KEY_LABELS.PFK08,
  ];

  for (const label of readOnly) {
    expect(legendControl(label), `${label} reads and must carry no emphasis`).not.toHaveClass(
      'ant-btn-primary',
    );
    expect(legendControl(label)).not.toHaveClass('ant-btn-dangerous');
  }

  const confirmingKey = legendControl(REF_TYPE_LIST_KEY_LABELS.PFK10);
  expect(confirmingKey, 'the save key is the mutating action and renders as primary').toHaveClass(
    'ant-btn-primary',
  );
  expect(
    confirmingKey,
    'with nothing armed the key saves, so it must not read as destructive',
  ).not.toHaveClass('ant-btn-dangerous');

  await markRowAndTransmit(user, FIRST_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.delete);

  const armed = legendControl(REF_TYPE_LIST_KEY_LABELS.PFK10);
  expect(armed, 'a delete is armed, so the confirming key destroys a row').toHaveClass(
    'ant-btn-dangerous',
  );
  expect(armed.textContent, 'the legend text is verbatim in both states').toBe(
    REF_TYPE_LIST_KEY_LABELS.PFK10,
  );
}

/**
 * Asserts ENTER transmits a turn while the legend advertises no ENTER descriptor.
 *
 * WHY : Trade-offs: the reference binds ENTER at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L575
 *       and rewrites other keys onto it at L586, yet its mapset paints no ENTER label among the five
 *       at L312-L336 — the fourth advertise-versus-bind mismatch in the baseline. Both repairs were
 *       rejected: adding a descriptor would invent a legend string, and transformation rule T8
 *       protects the legend as painted; unbinding ENTER would remove behaviour the reference has. So
 *       the mismatch is reproduced, and it is asserted from both sides so neither half can be
 *       "tidied" without a failure here.
 * @returns {Promise<void>} Resolves once the binding and the silence have been asserted.
 */
async function bindsEnterWithoutAdvertisingIt(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  for (const label of legendLabels()) {
    expect(label).not.toMatch(/enter/iu);
  }

  await markRowAndTransmit(user, FIRST_TYPE_CODE, 'X');

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_INVALID_ACTION_CODE.text);
}

/**
 * Asserts no clear, save-as-PF5 or cancel descriptor is advertised, and an unadvertised key acts
 * as ENTER.
 *
 * WHY : Assumptions: the reference does not merely ignore a key outside its accepted set — it
 *       REWRITES it to ENTER at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L585-L587, and again
 *       at L674 for a save key pressed with nothing pending. So the assertion is not that PF4, PF5
 *       and PF12 do nothing; it is that they do what ENTER does while advertising nothing. A screen
 *       that left them unmapped would report the shared invalid-key sentence instead, which this
 *       program never emits.
 * @returns {Promise<void>} Resolves once the rewrite has been asserted.
 */
async function rewritesAnUnadvertisedKeyToTheEnterTurn(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());

  for (const label of legendLabels()) {
    expect(label).not.toMatch(/^F(?:4|5|12)=/u);
  }

  await user.type(actionCell(FIRST_TYPE_CODE), 'X');
  await pressPfKey(user, 'PFK05');

  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_MESG_INVALID_ACTION_CODE.text);
  expect(screen.queryByText(INVALID_KEY_PRESSED)).toBeNull();
}

/**
 * Asserts the save key is refused with nothing pending and accepted once an action is pending.
 *
 * WHY : Refactoring Rationale: the save key is CONDITIONALLY valid and that condition is a genuine
 *       guard rather than an oversight. `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L575-L581
 *       admits `CCARD-AID-PFK10` only in conjunction with a requested delete or update, and L666-L678
 *       rewrites it to ENTER when the criteria have moved under the operator. That is what makes the
 *       two-step — mark a row, then save — safe: a stray save key cannot commit a request the
 *       operator has not made, and cannot commit against a page they are no longer looking at.
 * @returns {Promise<void>} Resolves once both halves of the guard have been asserted.
 */
async function honoursTheConditionalSaveKey(): Promise<void> {
  const user = await mountAsAdministrator(twoRowPage());
  replaceSpy().mockResolvedValue({
    typeCd: SECOND_TYPE_CODE,
    description: `${SECOND_DESCRIPTION} RETAIL`,
    version: 4,
  });

  await pressPfKey(user, 'PFK10');

  expect(replaceSpy()).not.toHaveBeenCalled();
  expect(deleteSpy()).not.toHaveBeenCalled();
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_REC_ACTIONS.text);

  await markRowAndTransmit(user, SECOND_TYPE_CODE, REF_TYPE_ROW_ACTION_CODES.update);
  await user.type(descriptionEditor(SECOND_TYPE_CODE), ' RETAIL');
  await pressPfKey(user, 'PFK10');

  await screen.findByText(STATUS_MESSAGES.COTRTLIC.WS_INFORM_UPDATE_SUCCESS.text);
  expect(replaceSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Asserts the keyboard and the legend control dispatch the same turn.
 *
 * WHY : Alternatives Considered: asserting the legend clicks alone, which is the shorter case and the
 *       wrong one. The 3270 original was keyboard-only, so the binding is the contract and the bar is
 *       an addition for discoverability; a suite that only clicked would pass in full while every
 *       keyboard binding in the application was broken. Asserting only the keyboard is the opposite
 *       omission — it would leave the visible control unexercised — so both are driven and their
 *       outcomes compared.
 * @returns {Promise<void>} Resolves once both dispatch paths have produced the same outcome.
 */
async function dispatchesTheSameTurnFromKeyboardAndLegend(): Promise<void> {
  const user = await mountAsAdministrator(refTypeRows(MEASURED_PAGE_SIZE), true);

  await pressPfKey(user, 'PFK07');
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY);

  await pressPfKey(user, 'ENTER');
  expectBandShows(STATUS_MESSAGES.COTRTLIC.WS_INFORM_REC_ACTIONS.text);

  await user.click(legendControl(REF_TYPE_LIST_KEY_LABELS.PFK07));
  expectBandShows(PROGRAM_MESSAGES.COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY);
}

/**
 * Registers the attention-key cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function attentionKeyCases(): void {
  it('advertises the five measured legend labels', advertisesTheFiveMeasuredLegendLabels);
  it('advertises the two keys unique to this screen', advertisesTheTwoKeysUniqueToThisScreen);
  it('paints each legend control from its risk', paintsEachLegendControlFromItsRisk);
  it('binds enter without advertising it', bindsEnterWithoutAdvertisingIt);
  it('rewrites an unadvertised key to the enter turn', rewritesAnUnadvertisedKeyToTheEnterTurn);
  it('honours the conditional save key', honoursTheConditionalSaveKey);
  it(
    'dispatches the same turn from keyboard and legend',
    dispatchesTheSameTurnFromKeyboardAndLegend,
  );
}

describe('transaction-type list attention keys', attentionKeyCases);

/**
 * Mounts the screen behind the administrative guard the route table composes around it.
 *
 * WHY : Assumptions: the guard is composed here because the SCREEN does not carry one — its own
 *       overview records that `/reference/transaction-types` is gated by `ui/src/routes/guards.tsx`
 *       through the pathless administrative layout route in `ui/src/router.tsx`, so a bare mount of
 *       the subject would render the grid for anyone and prove nothing about authority. Composing the
 *       pair reproduces the router's nesting: shell outside, guard inside, screen innermost.
 *
 * WHY : Assumptions: `ui/src/router.test.tsx` already asserts that this address admits an
 *       administrator and refuses an ordinary operator, against a STUB screen. This mount adds the
 *       half a stub cannot show — whether the REAL screen's reads are prevented rather than merely
 *       hidden — so the two suites overlap deliberately at the guard and diverge at the subject.
 * @param {readonly string[]} groups - The group claims the seeded token carries.
 * @returns {Promise<void>} Resolves once the guarded tree has rendered.
 * @throws {Error} If the sign-on exchange does not establish a session, which `seedSession` raises.
 */
async function mountBehindTheAdminGuard(groups: readonly string[]): Promise<void> {
  browseSpy().mockResolvedValue(pageResponse<TransactionType>(twoRowPage()));
  await seedSession({ groups });
  await renderInAppShell(
    <RequireAdmin>
      <RefTypeListScreen />
    </RequireAdmin>,
    { initialEntries: [REF_TYPE_LIST_PATH], routePath: REF_TYPE_LIST_PATH },
  );
}

/**
 * Asserts an administrator reaches the grid and the browse is issued.
 * @returns {Promise<void>} Resolves once the grid has been asserted.
 */
async function admitsAnAdministrator(): Promise<void> {
  await mountBehindTheAdminGuard([CARDDEMO_ADMIN_GROUP]);

  await screen.findByText(twoRowPage()[0]?.description ?? '');
  expect(browseSpy()).toHaveBeenCalledTimes(1);
  expect(grid()).toBeInTheDocument();
}

/**
 * Asserts an ordinary operator is refused and the reference service is never reached.
 *
 * WHY : Assumptions: "never reached" is the assertion that matters here, and it is why this case
 *       exists beside the route table's own. This screen EDITS reference data that every other
 *       service reads, so a guard that merely hid the grid while the browse still ran would leak the
 *       catalogue to an operator the reference never showed it to — and the reference's own admin menu
 *       records that reaching this option at all is the authorization: `app/cpy/COADM02Y.cpy` L48
 *       names it as administrative option five of the six counted at L22, and the option entry at
 *       L52-L55 carries no user-type field because the menu is only reachable by an administrator.
 * @returns {Promise<void>} Resolves once the refusal and the silence have been asserted.
 */
async function refusesAnOrdinaryOperatorWithoutReading(): Promise<void> {
  await mountBehindTheAdminGuard([CARDDEMO_USER_GROUP]);

  // WHY : Assumptions: the denial is matched inside the document's whole text rather than through a
  //       text query, because the sentence carries a trailing space —
  //       `'No access - Admin Only option... '` from `app/cbl/COMEN01C.cbl` L140 — and Testing
  //       Library's default normaliser trims the element it compares, so an exact query would fail on
  //       padding that is part of the string. Nothing here trims the expectation.
  expect(document.body.textContent ?? '').toContain(ACCESS_DENIED_ADMIN_ONLY);
  expect(screen.queryByRole('table')).toBeNull();
  expect(browseSpy()).not.toHaveBeenCalled();
  expect(replaceSpy()).not.toHaveBeenCalled();
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * Asserts authority can be reached only through the signed group claim.
 *
 * WHY : Refactoring Rationale: in the baseline the operator's type travelled in the COMMAREA —
 *       `CDEMO-USER-TYPE PIC X(01)` at `app/cpy/COCOM01Y.cpy` L26 with its `'A'` and `'U'` conditions
 *       at L27-L28 — which is storage the terminal echoed back, so a client could in principle assert
 *       its own type. In the target it is a signed claim (AAP §0.7.1), and the enforcement of that is
 *       structural: `ui/src/hooks/useAuth.ts` publishes the two group names and the claim name and
 *       deliberately publishes no setter, and this package has no context provider to substitute one.
 *       The case asserts that surface directly, because a helper that assigned a group would create a
 *       second and weaker path to authority that production does not have.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function grantsAuthorityOnlyThroughTheSignedClaim(): void {
  expect(COGNITO_GROUPS_CLAIM).toBe('cognito:groups');
  expect(CARDDEMO_ADMIN_GROUP).not.toBe(CARDDEMO_USER_GROUP);
  for (const exported of Object.keys(authModule)) {
    expect(exported).not.toMatch(/^(?:set|grant|assume|become)/u);
  }
}

/**
 * Asserts the back key transfers control to the administrative menu rather than the main one.
 *
 * WHY : Assumptions: the destination is the ADMIN menu and the distinction is measured, not assumed.
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L591-L599 moves `LIT-ADMINTRANID` into the
 *       outgoing transaction identifier when the screen was not entered from a sibling, and this
 *       screen is reached from the administrative menu — `app/cpy/COADM02Y.cpy` L48 lists it there.
 *       Under transformation rule T5 the transfer is a client-side route change, so the observable is
 *       the address.
 * @returns {Promise<void>} Resolves once the address has been asserted.
 */
async function transfersToTheAdminMenuOnTheBackKey(): Promise<void> {
  const user = await mountWithAddressProbe(twoRowPage());

  await pressPfKey(user, 'PFK03');

  await waitFor(
    /**
     * Asserts the address has become the administrative menu.
     * @returns {void} Nothing; throws until it has.
     */
    (): void => {
      expect(renderedAddress()).toBe('/admin');
    },
  );
}

/**
 * Asserts the add key transfers to the maintenance route, carrying its key as a path parameter.
 *
 * WHY : Assumptions: the destination is checked against the route PATTERN with react-router's own
 *       matcher rather than by string comparison, because what is being asserted is that the
 *       selection travels as a request parameter named `cd` — the name `ui/src/router.tsx` gives the
 *       parameter at `REF_TYPE_EDIT_PATH` — and not in a session field. AAP §0.7.1 removes the session
 *       struct entirely, so the address is the only place selection context can live.
 *
 * WHY : Assumptions: the parameter's value is a sentinel rather than a code, because the reference
 *       arrives at the maintenance program with no key and prompts for one — `COTRTLIC.cbl` L630-L652
 *       transfers control with `CDEMO-PGM-ENTER` set. A two-character `TR_TYPE` can never collide with
 *       a three-character sentinel, which is what makes the shared pattern safe for both.
 * @returns {Promise<void>} Resolves once the address and its parameter have been asserted.
 */
async function transfersToTheMaintenanceRouteOnTheAddKey(): Promise<void> {
  const user = await mountWithAddressProbe(twoRowPage());

  await pressPfKey(user, 'PFK02');

  await waitFor(
    /**
     * Asserts the address has become the maintenance route.
     * @returns {void} Nothing; throws until it has.
     */
    (): void => {
      expect(renderedAddress()).toBe(REF_TYPE_ADD_ROUTE);
    },
  );
  const matched = matchPath(REF_TYPE_EDIT_PATH, REF_TYPE_ADD_ROUTE);
  expect(matched).not.toBeNull();
  expect(matched?.params.cd).toBe('new');
}

/**
 * Asserts the legend controls transfer control exactly as their keys do.
 * @returns {Promise<void>} Resolves once the clicked transfer has been asserted.
 */
async function transfersFromTheLegendControlToo(): Promise<void> {
  const user = await mountWithAddressProbe(twoRowPage());

  await user.click(legendControl(REF_TYPE_LIST_KEY_LABELS.PFK03));

  await waitFor(
    /**
     * Asserts the clicked control transferred control.
     * @returns {void} Nothing; throws until the address changes.
     */
    (): void => {
      expect(renderedAddress()).toBe('/admin');
    },
  );
}

/**
 * Asserts this screen can render no cardholder or monetary value, because it holds none.
 *
 * WHY : Assumptions: the strongest available form of this check is the row CONTRACT rather than a
 *       text sweep, so both are asserted. `TransactionType` in `ui/src/api/types.ts` declares three
 *       members — the two-character code, the description and the optimistic-concurrency version —
 *       and `app/cpy/CVTRA03Y.cpy` declares the same two data fields, so no account number, no
 *       national identifier, no government identifier and no amount can reach this grid at all. AAP
 *       §0.7.8 masks or withholds each of those elsewhere; here the correct assertion is that none
 *       exists to mask.
 * @returns {Promise<void>} Resolves once the contract and the rendered text have been asserted.
 */
async function rendersNoCardholderOrMonetaryValue(): Promise<void> {
  await mountAsAdministrator(twoRowPage());

  const firstRow = twoRowPage()[0];
  expect(firstRow).toBeDefined();
  expect(Object.keys(firstRow ?? {}).sort()).toStrictEqual(['description', 'typeCd', 'version']);

  const rendered = document.body.textContent ?? '';
  // WHY : Assumptions: a sixteen-digit run is the shape of a primary account number —
  //       `CC-CARD-NUM PIC X(16)` at `app/cpy/CVCRD01Y.cpy` L37 — and a nine-digit run the shape of a
  //       national identifier, so their absence is checked as a pattern rather than by name. The
  //       fixture descriptions are alphabetic, so a digit run of either length could only arrive from
  //       a field this screen has no business holding.
  expect(rendered).not.toMatch(/\d{9}/u);
  expect(rendered).not.toMatch(/\d+\.\d{2}/u);
}

/**
 * Registers the authority, navigation and data-exposure cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function authorityAndNavigationCases(): void {
  it('admits an administrator', admitsAnAdministrator);
  it('refuses an ordinary operator without reading', refusesAnOrdinaryOperatorWithoutReading);
  it('grants authority only through the signed claim', grantsAuthorityOnlyThroughTheSignedClaim);
  it('transfers to the admin menu on the back key', transfersToTheAdminMenuOnTheBackKey);
  it(
    'transfers to the maintenance route on the add key',
    transfersToTheMaintenanceRouteOnTheAddKey,
  );
  it('transfers from the legend control too', transfersFromTheLegendControlToo);
  it('renders no cardholder or monetary value', rendersNoCardholderOrMonetaryValue);
}

describe('transaction-type list authority and navigation', authorityAndNavigationCases);
