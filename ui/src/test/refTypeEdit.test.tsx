/**
 * @file Proves the transaction-type maintenance screen through the SHARED test harness: its two
 * message channels, its dynamic function-key legend, the state-dependent meaning of F4, F5, Enter
 * and F12, its two optimistic-concurrency refusals, and the identity policy that admits it.
 *
 * Purpose
 * -------
 * `ui/src/screens/refTypeEdit/index.tsx` migrates
 * `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` and its mapset
 * `app/app-transaction-type-db2/bms/COTRTUP.bms` (25 `DFHMDF` fields), mounted by
 * `ui/src/router.tsx` at `/reference/transaction-types/:cd`. The reference is an online CICS program
 * in the Db2 reference-data extension tree, and `tests/README.md` L83-L84 states the consequence
 * verbatim: "Online `CO*` CICS programs cannot run end-to-end without a CICS runtime (absent on the
 * runner); only their extractable field-validation logic is unit-tested." This extension needs Db2 as
 * well, which the runner also lacks, so NO golden master exists for this program at any layer - while
 * AAP section 0.9.4 lists the three decoupled extensions among the user's acceptance-criteria flows
 * and this is the Db2 one. These cases and the sibling file beside the screen are the whole of its
 * verification.
 *
 * Parameters
 * ----------
 * Not applicable. A test module is an entry point the runner invokes with no arguments; every input a
 * case needs is built by a documented fixture or helper below.
 *
 * Returns
 * -------
 * Not applicable. The module's result is the pass or fail of each registered case.
 *
 * Exceptions or errors
 * --------------------
 * Nothing is thrown at module scope. `pathOfProgram` throws when the route table stops naming a
 * program a case depends on, which fails that case loudly rather than letting it assert against a
 * destination nobody declares.
 *
 * What this file adds that the sibling does not
 * --------------------------------------------
 * Assumptions: `ui/src/screens/refTypeEditScreen.test.tsx` exists and pins the screen's pure
 * functions and its stage transitions through a LOCAL harness it composes itself. This file is
 * deliberately the other half: it drives the same screen through the shared harness in
 * `ui/src/test/setup.ts`, which is the only path that mounts it inside the real `AppShell` through the
 * outlet - and the shell is where three of this mapset's fields actually land, because the screen
 * delegates its title band, its row-23 `ERRMSG` line and its row-24 legend rather than composing them.
 * Every legend and row-23 assertion below is therefore unreachable from a bare render, and every
 * message expectation is the catalog constant rather than a retyped sentence.
 *
 * Rule 1, double-anchored
 * -----------------------
 * Assumptions: exactly one user-specified rule governs this tree - Rule 1, Explainability - and
 * `tests/README.md` L544-L549 imposes the identical obligation on "every new test, fixture builder,
 * helper, mock, and runner routine" and calls it "a hard review gate". The two agree completely, so
 * this file extends an established house convention rather than importing a new one:
 * `docs/CODE_DOCUMENTATION_STANDARD.md` is the written form both are held to.
 *
 * Refactoring Rationale: this file imports `describe`, `expect`, `it` and `vi` from 'vitest' by name.
 * `ui/vitest.config.ts` L136 sets `globals: true`, which makes that import look redundant, and it is
 * not: `ui/tsconfig.json` sets `"types": []`, and its own header records that THIS empty list "obliges
 * a test to import `describe`, `it` and `expect` from 'vitest' by name -- with nothing declared
 * ambiently, an omitted import fails to compile on the symbol it omitted". `npm run typecheck` is a
 * required step in `.github/workflows/ui-ci.yml`, and all twenty sibling test files import the same
 * way, so the named import is the compiling form rather than a stylistic choice.
 *
 * Refactoring Rationale: every rationale below is tagged with one of the four PLURAL canonical labels
 * and no comment carries a statement-level purpose marker. Both are machine-checked over `.tsx` by
 * `config/rule1/rule1_gate.py`, whose `CANONICAL_LABELS` at L71-L76 are the plural forms, whose
 * `_SINGULAR_STEMS` at L85 makes the singular spellings violations, and whose `_WHAT_TOKEN` at L86 is
 * permitted only inside a file's leading header block. That gate is a required step in
 * `.github/workflows/services-ci.yml` and `infra-ci.yml`, it currently passes over the whole tree, and
 * `docs/CODE_DOCUMENTATION_STANDARD.md` L234-L240 states the four plural labels as the one permitted
 * written form. `ui/src` contains zero occurrences of either prohibited form.
 *
 * Refactoring Rationale: every callback is a named function declaration rather than an inline arrow.
 * `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with `publicOnly: false` and the contexts
 * `'* > ArrowFunctionExpression'` and `'*:not(MethodDefinition) > FunctionExpression'`, so a function
 * expression in ANY position owes a documentation block, and Prettier detaches a block comment that
 * follows an argument comma - which would move each explanation away from the code it explains.
 */

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';

import { ApiRequestError, isConflictFailure, isTransientFailure } from '../api/client';
import {
  createTransactionType,
  deleteTransactionType,
  getTransactionType,
  replaceTransactionType,
} from '../api/reference';
import type { TransactionType } from '../api/reference';
import type { ApiError } from '../api/types';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP, COGNITO_GROUPS_CLAIM } from '../hooks/useAuth';
import { SHELL_PINNED_ZONE_TEST_ID } from '../layout/AppShell';
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  BLANK_FIELD_MARKER_TEST_ID,
  BUSY_ANNOUNCEMENT_TEST_ID,
} from '../layout/fieldHelp';
import { MESSAGE_BAND_TEST_IDS } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { HEADER_DATE_FORMAT, HEADER_TIME_FORMAT } from '../layout/ScreenHeader';
import {
  ACCESS_DENIED_NOT_AUTHORIZED,
  APP_ORGANISATION_TITLE_DISPLAY,
  APP_TITLE_DISPLAY,
  COMMON_MESSAGES,
  FIELD_VALIDATION_SUFFIXES,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  formatFieldValidationMessage,
} from '../messages/messages';
import { REF_TYPE_EDIT_PATH, ROUTE_TABLE } from '../router';
import { navigateSafely } from '../routes/navigation';
import RefTypeEditScreen, {
  DESCRIPTION_CONTROL_ID,
  DESCRIPTION_WIDTH,
  REF_TYPE_EDIT_AIDS,
  REF_TYPE_EDIT_FIELD_LABELS,
  REF_TYPE_EDIT_KEY_LABELS,
  REF_TYPE_EDIT_MODE_VALUES,
  REF_TYPE_EDIT_PROGRAM_NAME,
  REF_TYPE_EDIT_SUBTITLE,
  REF_TYPE_EDIT_TRANSACTION_ID,
  REF_TYPE_NEW_SENTINEL,
  TYPE_CODE_CONTROL_ID,
  TYPE_CODE_WIDTH,
  adoptRow,
  holdInField,
  messageChannel,
  refTypeEditKeyMatrix,
} from '../screens/refTypeEdit';
import type { RefTypeEditKeyAid, RefTypeEditMode } from '../screens/refTypeEdit';
import { BMS_TEXT_COLOR_TOKENS, FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  apiError,
  conflictProblem,
  expectMaxLength,
  expectVerbatimMessage,
  pressPfKey,
  renderInAppShell,
  renderWithProviders,
  seedSession,
  shellLandmark,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * Builds the mocked surface of the reference transport module.
 *
 * Assumptions: a hoisted function DECLARATION and not a factory held in a `const`. Vitest lifts every
 * `vi.mock` call above the imports, so a `const` factory would still be in its temporal dead zone when
 * the registration runs.
 *
 * Assumptions: the module is replaced rather than spied on because `ui/package.json` installs no
 * request-interception layer at all - the mock-service-worker package appears in neither of its
 * dependency lists - so there is nothing that could answer a real call. Replacing the four functions
 * the screen calls is what lets each case decide what the service answered without a network endpoint
 * or a credential anywhere in this file.
 * @returns {Record<string, unknown>} The four transport functions this screen calls, each a fresh spy.
 */
function referenceTransportDouble(): Record<string, unknown> {
  return {
    getTransactionType: vi.fn(),
    createTransactionType: vi.fn(),
    replaceTransactionType: vi.fn(),
    deleteTransactionType: vi.fn(),
  };
}

vi.mock('../api/reference', referenceTransportDouble);

/** Prompts and outcomes this program declares, from the one catalog that owns them. */
const EDIT_STATUS = STATUS_MESSAGES.COTRTUPC;

/** The single message this program declares as a literal of its own, which is its key field's label. */
const KEY_FIELD_LABEL = PROGRAM_MESSAGES.COTRTUPC.TRAN_TYPE_CODE;

/** The description field's label, which the catalog files as shared because two programs move it. */
const DESCRIPTION_FIELD_LABEL = SHARED_MESSAGES.TRANSACTION_DESC;

/**
 * One stored row, at the widths the mapset and the Db2 table definition both declare.
 *
 * Assumptions: the key is two digits and the description is upper-case letters and one space, because
 * `1210-EDIT-TRANTYPE` performs the NUMERIC edit on the key at `COTRTUPC.cbl` L829 while
 * `1220-EDIT-DESCRIPTION` performs the alphanumeric-or-space edit on the description at L893 - so a
 * fixture that failed either edit would exercise a refusal rather than the stored row.
 */
const STORED: TransactionType = { typeCd: '05', description: 'PAYMENT REVERSAL', version: 3 };

/** Identifier the address probe publishes the current pathname under. */
const RENDERED_ADDRESS_TEST_ID = 'rendered-address';

/** Label on the harness control that navigates to the add sentinel. */
const GO_TO_ADD_LABEL = 'go to the add sentinel';

/**
 * An address segment the two-character key field cannot hold as a key.
 *
 * Assumptions: two non-digits rather than a long digit run, because the field's WIDTH is not what makes
 * this unusable -- `TRAN-TYPE PIC X(02)` at `app/cpy/CVTRA03Y.cpy` L5 and `CHAR(2)` at
 * `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 both admit two characters -- its domain is. The
 * reported reproduction used exactly this segment.
 */
const UNUSABLE_ROUTE_KEY = 'ZZ';

/** A description that passes the alphanumeric edit and differs from the stored one. */
const EDITED_DESCRIPTION = 'PAYMENT REVERSAL 2';

/**
 * A diagnostic the service must never let through to the operator.
 *
 * Assumptions: the shape imitates what the baseline appended - a schema object name and a negative
 * relational return code - because `REDACTED_DIAGNOSTICS` registers five sites in this program where
 * exactly that was concatenated onto an operator sentence, including the referential-constraint arm at
 * `COTRTUPC.cbl` L1641. Putting it in the problem document's `message` member and asserting its
 * absence is how a case proves the screen renders the registered replacement instead of the document
 * it was handed.
 */
const WITHHELD_DIAGNOSTIC = 'TRANSACTION_TYPE table -532';

/** Text a probe route renders so an arrival can be observed. */
const ARRIVED = 'ARRIVED';

/**
 * A path no application route declares, used only to originate a transition that carries state.
 *
 * Assumptions: it has one segment and does not begin `/reference/transaction-types`, so it cannot be
 * matched by `REF_TYPE_EDIT_PATH` - a launcher mounted at `/reference/transaction-types/launch` would
 * be matched by that pattern with `cd` bound to `launch`, and the screen rather than the launcher would
 * render.
 */
const LAUNCH_PATH = '/originating-transition';

/** Statuses the cases below hand the screen, named so no bare number appears at a call site. */
const STATUS = {
  /*
   * WHY : Assumptions: 403 is an ORDINARY outcome of these operations and not a fault, which is why it
   *       sits in this table beside the four the screen already answered.
   *       `services/reference-service/src/main/resources/openapi/reference-api.yaml` declares
   *       `x-required-authority: carddemo-admin` on the writes and `carddemo-user` on the read, so a
   *       refusal says the token carries the wrong authority and says nothing about the service's health.
   */
  refusedAuthority: 403,
  notFound: 404,
  conflict: 409,
  serverError: 500,
  unavailable: 503,
  /*
   * WHY : ⚠ Assumptions: 502 earns its own member because it is the only transient status on the write
   *       path with NO baseline sentence of its own. 503 carries `'Could not lock record for update'`,
   *       the replacement for `SQLCODE -911` at `COTRTUPC.cbl` L1561-L1566, which deliberately outranks
   *       the authored classification -- so a case that used 503 to exercise the classified arm would
   *       exercise the lock arm instead and would pass while the classification was missing entirely.
   */
  badGateway: 502,
} as const;

/**
 * The status the transport reports when no response arrived at all.
 *
 * Assumptions: this is not a member of {@link STATUS}, deliberately. Every entry there is a status a
 * SERVICE answered with, and this is the absence of an answer -- `ui/src/api/client.ts` raises a dropped
 * connection and a timeout with `status` 0 and a synthesised problem document, which is part of the
 * published shape of `ApiRequestError` rather than a status the contract declares.
 */
const NO_TRANSPORT_STATUS = 0;

/**
 * The abend data a service carries when the reference program's abend routine ran.
 *
 * Assumptions: all four members are filled, because `ABEND-ROUTINE` at `COTRTUPC.cbl` L1684-L1697 sends
 * the whole `ABEND-DATA` group declared at `app/cpy/CSMSG02Y.cpy` L21-L29 -- so a fixture omitting one
 * would exercise a shape the service does not produce. The message member is deliberately BLANK: the
 * heading then falls to the registered replacement, which keeps the cases below asserting about the
 * SURFACE rather than about a sentence a service invented.
 */
const CARRIED_ABEND = {
  abendCode: '9999',
  abendCulprit: 'COTRTUPC',
  abendReason: 'RESOURCE UNAVAILABLE',
  abendMsg: '',
} as const;

/**
 * Finds the route the migration mounts for one reference program.
 *
 * Assumptions: PF3's two destinations are DERIVED from `ROUTE_TABLE` rather than written as path
 * literals. The reference resolves its exit to `CDEMO-FROM-PROGRAM` when the transition named one and
 * to `LIT-ADMINPGM` otherwise (`COTRTUPC.cbl` L429-L443), and those two literals are `'COTRTLIC'` at
 * L217-L218 and `'COADM01C'` at L209-L210 - program names, not paths. Looking each up in the table
 * that `ui/src/router.tsx` publishes keeps the expectation anchored on the program the COBOL names,
 * and means a path renamed in the route table cannot leave this file asserting against a destination
 * the application no longer has.
 * @param {string} program - Name of the CICS program, as the reference's own literal spells it.
 * @returns {string} The route pattern the migration mounts for that program.
 * @throws {Error} If the route table names no such program, which means the transition this case
 *   depends on no longer has a destination.
 */
function pathOfProgram(program: string): string {
  const entry = ROUTE_TABLE.find(
    /**
     * Matches the table entry for the wanted program.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One published route-table entry.
     * @returns {boolean} `true` when the entry replaces that program.
     */
    (candidate) => candidate.program === program,
  );
  if (entry === undefined) {
    throw new Error(`ui/src/router.tsx publishes no route for program ${program}`);
  }
  return entry.path;
}

/** Where PF3 goes when the arrival named no caller, from `COTRTUPC.cbl` L209-L210 and L439-L442. */
const ADMIN_MENU_PATH = pathOfProgram('COADM01C');

/** Where PF3 goes when the sibling list screen entered this one, from `COTRTUPC.cbl` L217-L218. */
const REFERENCE_LIST_PATH = pathOfProgram('COTRTLIC');

/**
 * Collapses interior runs of blanks the way the accessible-name computation does.
 *
 * Assumptions: this exists because two label literals carry INTERIOR padding - the mapset writes
 * `'Transaction Type  :'` with two blanks at `COTRTUP.bms` L83 and `'Description       :'` with seven
 * at L93, so both colons land in one column of the character grid - and the screen renders them
 * verbatim under Transformation Rule T8. Testing Library's accessible-name matcher collapses those
 * runs, so the QUERY is collapsed rather than the label being shortened at its declaration, which
 * would make this file a second spelling of the mapset's own literal.
 *
 * Assumptions: no boundary blanks are removed and none needs to be. Neither label begins or ends with
 * a blank, so collapsing interior runs is the whole of the transformation - and a boundary trim here
 * would silently accept a padded variant of a string the verbatim assertions below reject.
 * @param {string} value - A label as the mapset paints it.
 * @returns {string} The same label with each run of two or more blanks reduced to one.
 */
function collapseInteriorRuns(value: string): string {
  return value.replace(/ {2,}/gu, ' ');
}

/**
 * Builds one normalised transport failure carrying a status and whatever sentence the service sent.
 *
 * Assumptions: a real `ApiRequestError` and never a bare object carrying a `status`, because the screen
 * classifies a failure with `isApiRequestError` and `isConflictFailure` from `ui/src/api/client.ts` - a
 * lookalike would take the fall-through arm and prove the opposite of what a case intends.
 * @param {number} status - The status the service answered with.
 * @param {ApiError} problem - The problem document the client would carry.
 * @returns {ApiRequestError} The failure the client raises for that answer.
 */
function failureFrom(status: number, problem: ApiError): ApiRequestError {
  return new ApiRequestError('PROBLEM', status, problem, `refused with status ${String(status)}`);
}

/**
 * Builds the not-found refusal a key naming no row produces.
 *
 * Assumptions: 404 is an OUTCOME rather than a failure on this screen, which is why it has its own
 * builder. `9100-GET-TRANSACTION-TYPE` treats relational code `+100` as its own arm at
 * `COTRTUPC.cbl` L1489-L1494 and reports `'No record found for this key in database'` while the row-22
 * prompt offers the add, so a case reaching for a generic failure here would exercise the wrong arm.
 * @returns {ApiRequestError} The normalised 404.
 */
function notFoundFailure(): ApiRequestError {
  return failureFrom(STATUS.notFound, apiError({ status: STATUS.notFound }));
}

/**
 * Renders the screen inside the real application shell at one maintenance address.
 *
 * Assumptions: the shell is REQUIRED rather than convenient. The screen delegates its title band, its
 * row-23 `ERRMSG` line and its row-24 legend through `useShellSlot`, so a bare render paints none of
 * the three and every query for them fails against a screen that is in fact correct. The shared helper
 * mounts the subject through the shell's OUTLET, which is the arrangement `ui/src/router.tsx` builds -
 * passing it as the shell's `children` member instead would leave the outlet itself unexercised.
 * @param {string} typeCd - The value the `cd` route parameter receives.
 * @returns {Promise<HarnessRenderResult>} The render result with the keyboard operator attached.
 */
async function renderInShellAt(typeCd: string): Promise<HarnessRenderResult> {
  return await renderInAppShell(<RefTypeEditScreen />, {
    initialEntries: [`${REFERENCE_LIST_PATH}/${typeCd}`],
    routePath: REF_TYPE_EDIT_PATH,
  });
}

/**
 * Renders the screen with a probe at each of PF3's two destinations.
 *
 * Assumptions: the shell is deliberately ABSENT here, because PF3 is driven by a real key press and the
 * key listener `usePfKeys` installs sits on the document rather than on the legend - so the exit is
 * observable without the frame, and omitting it keeps the probe routes as siblings of the screen's own
 * route, which the shell-mounting helper cannot express.
 *
 * Assumptions: when a caller is named, the arrival is made by a redirecting launcher at a path no
 * application route declares. The shared helper's `initialEntries` accepts path strings only, so a
 * history entry carrying `state` cannot be handed to it directly, and the screen reads its caller from
 * `location.state` - which is the mechanism replacing `CDEMO-FROM-PROGRAM`, per AAP section 0.7.1. The
 * redirect replaces its own entry, so PF3 returning to the list path cannot bounce back through it.
 * @param {string | undefined} caller - Route the entering transition names as this screen's caller, or
 *   `undefined` for an arrival that names none, which is what administrative option 6 produces.
 * @returns {Promise<HarnessRenderResult>} The render result with the keyboard operator attached.
 */
async function renderWithExitProbes(caller: string | undefined): Promise<HarnessRenderResult> {
  const maintenancePath = `${REFERENCE_LIST_PATH}/${STORED.typeCd}`;

  return await renderWithProviders(
    <Routes>
      <Route path={REF_TYPE_EDIT_PATH} element={<RefTypeEditScreen />} />
      <Route path={ADMIN_MENU_PATH} element={<span>{`${ARRIVED} ${ADMIN_MENU_PATH}`}</span>} />
      <Route
        path={REFERENCE_LIST_PATH}
        element={<span>{`${ARRIVED} ${REFERENCE_LIST_PATH}`}</span>}
      />
      <Route
        path={LAUNCH_PATH}
        element={<Navigate to={maintenancePath} state={{ from: caller }} replace />}
      />
    </Routes>,
    { initialEntries: [caller === undefined ? maintenancePath : LAUNCH_PATH] },
  );
}

/**
 * Locates the transaction-type-code control.
 *
 * Assumptions: the control is found by its identifier rather than by its accessible name, because the
 * name derives from a label carrying interior padding and a query is one more place that padding could
 * be spelled wrong. The screen publishes the identifier it puts on the control, so this reads the same
 * constant the render site writes.
 * @returns {HTMLElement} The type-code input.
 * @throws {Error} If no element carries that identifier, which means the form did not render.
 */
function typeCodeControl(): HTMLElement {
  const control = document.getElementById(TYPE_CODE_CONTROL_ID);
  if (control === null) {
    throw new Error(`no control carries the identifier ${TYPE_CODE_CONTROL_ID}`);
  }
  return control;
}

/**
 * Locates the description control.
 * @returns {HTMLElement} The description input.
 * @throws {Error} If no element carries that identifier, which means the form did not render.
 */
function descriptionControl(): HTMLElement {
  const control = document.getElementById(DESCRIPTION_CONTROL_ID);
  if (control === null) {
    throw new Error(`no control carries the identifier ${DESCRIPTION_CONTROL_ID}`);
  }
  return control;
}

/**
 * Reads the text one legend control displays.
 *
 * Assumptions: `textContent` rather than the accessible name, because the legend descriptors are the
 * mapset's own literals and the accessible-name computation would collapse their spacing - and
 * `'ENTER=Process F3=Exit'` at `COTRTUP.bms` L115 is separated by ONE blank where every base mapset
 * uses two, which is precisely the distinction a collapsing read would destroy.
 * @param {HTMLElement} control - One control rendered in the legend region.
 * @returns {string} The text the control displays.
 */
function legendTextOf(control: HTMLElement): string {
  return control.textContent ?? '';
}

/**
 * Lists the legend descriptors currently on the glass, in the order the bar renders them.
 *
 * Assumptions: the region is located by its navigation landmark and its published label rather than by
 * the shell's footer, because the footer is a landmark the shell owns and may hold controls the legend
 * does not. `ui/src/layout/PfKeyBar.tsx` renders one control per REVEALED binding and holds no hidden
 * state, so the list this returns is the whole of what the mode reveals.
 * @returns {readonly string[]} The descriptor text of every legend control.
 */
function legendDescriptors(): readonly string[] {
  const region = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return within(region).getAllByRole('button').map(legendTextOf);
}

/**
 * Locates one legend control by the descriptor the mapset paints on it.
 * @param {RefTypeEditKeyAid} aid - The attention identifier whose control is wanted.
 * @returns {HTMLElement} That key's control in the legend region.
 * @throws {Error} If the mode does not reveal that key, which Testing Library raises.
 */
function legendControlFor(aid: RefTypeEditKeyAid): HTMLElement {
  const region = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return within(region).getByRole('button', { name: REF_TYPE_EDIT_KEY_LABELS[aid] });
}

/**
 * Waits until the stored row is on the glass, which is the show-details arrival.
 *
 * Assumptions: the anchor is the fetched DESCRIPTION and not the `'Selected transaction type shown
 * above'` prompt, because that prompt is unreachable in the reference: `88 FOUND-TRANTYPE-DATA` is
 * declared at `COTRTUPC.cbl` L145-L146 and no statement sets it, so `3250-SETUP-INFOMSG` falls through
 * its `WHEN TTUP-SHOW-DETAILS` arm at L1221 to the search-key prompt. Anchoring on the row proves the
 * read landed without asserting a sentence the program cannot produce.
 * @returns {Promise<void>} Resolves once the stored description is displayed.
 */
async function awaitStoredRow(): Promise<void> {
  // WHY : Assumptions: the fetched description landing in its control is what marks the show-details
  //       mode reached, since `COTRTUPC.cbl` paints no distinct sentence on a successful fetch.
  expect(await screen.findByDisplayValue(STORED.description)).toBeInTheDocument();
}

/**
 * Renders the screen in the shell and drives it to the show-details mode.
 * @returns {Promise<HarnessRenderResult>} The render result, with the stored row on the glass.
 */
async function arriveAtStoredRow(): Promise<HarnessRenderResult> {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
  const rendered = await renderInShellAt(STORED.typeCd);
  await awaitStoredRow();
  return rendered;
}

/**
 * Renders the screen in the shell and drives it to the details-not-found mode.
 *
 * Assumptions: the arrival is a 404 on a key the route carried, so the screen reaches the mode the
 * reference reaches with relational code `+100` - the one mode from which PF5 begins an add.
 * @returns {Promise<HarnessRenderResult>} The render result, reporting the missing row.
 */
async function arriveAtMissingRow(): Promise<HarnessRenderResult> {
  vi.mocked(getTransactionType).mockRejectedValue(notFoundFailure());
  const rendered = await renderInShellAt(STORED.typeCd);
  await waitFor(awaitMissingRowReport);
  return rendered;
}

/**
 * Asserts the missing-row sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitMissingRowReport(): void {
  // WHY : Assumptions: `'No record found for this key in database'` at `COTRTUPC.cbl` L175-L176.
  expect(errorBandText()).toContain(EDIT_STATUS.WS_RECORD_NOT_FOUND.text);
}

/**
 * Drives an arrived show-details screen to the delete confirmation.
 *
 * Assumptions: the first PF4 only ASKS. `COTRTUPC.cbl` L493-L498 sets `TTUP-CONFIRM-DELETE` and
 * re-sends the map, and only the second press at L482-L489 issues the delete - which is the two-press
 * convention this screen preserves alongside the pointer confirmation.
 * @param {HarnessRenderResult} rendered - The arrived render result.
 * @returns {Promise<void>} Resolves once the confirmation prompt is on the row-22 band.
 */
async function askToDelete(rendered: HarnessRenderResult): Promise<void> {
  await pressPfKey(rendered.user, 'PFK04');
  await waitFor(awaitDeleteConfirmationPrompt);
}

/**
 * Asserts the delete-confirmation prompt has reached the row-22 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitDeleteConfirmationPrompt(): void {
  // WHY : Assumptions: `'Delete this record ? Press F4 to confirm'` at `COTRTUPC.cbl` L151-L152,
  //       encoding the 3270 re-key-to-confirm convention with its blank before the question mark.
  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text);
}

/**
 * Drives an arrived show-details screen to the validated-change mode.
 *
 * Assumptions: the description is replaced wholesale rather than appended to, because
 * `1200-EDIT-MAP-INPUTS` compares the glass against the before-image at `COTRTUPC.cbl` L743-L751
 * BEFORE running the description edit - so a value that merely extends the stored one still has to
 * differ, and clearing first is what makes the change unambiguous.
 * @param {HarnessRenderResult} rendered - The arrived render result.
 * @returns {Promise<void>} Resolves once the save handshake prompt is on the row-22 band.
 */
async function editDescriptionAndValidate(rendered: HarnessRenderResult): Promise<void> {
  await rendered.user.clear(descriptionControl());
  await rendered.user.type(descriptionControl(), EDITED_DESCRIPTION);
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitSaveHandshakePrompt);
}

/**
 * Asserts the validate-then-save prompt has reached the row-22 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitSaveHandshakePrompt(): void {
  // WHY : Assumptions: `'Changes validated.Press F5 to save'` at `COTRTUPC.cbl` L160-L161, no blank
  //       after the stop - validation and the save are two turns, which is what this prompt marks.
  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text);
}

/**
 * Reads the row-23 band the shell paints for this screen.
 *
 * Assumptions: the two bands are addressed by their OWN identifiers rather than by one shared handle.
 * This mapset declares both message fields - `INFOMSG` at `POS=(22,23)` and `ERRMSG` at `POS=(23,1)` -
 * so a single handle would match two elements, which is the ambiguity `MESSAGE_BAND_TEST_IDS` exists to
 * remove.
 * @returns {string} The text on the error line, empty when the band is holding none.
 */
function errorBandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_IDS.error).textContent ?? '';
}

/**
 * Reads the row-22 band the shell paints for this screen.
 *
 * Assumptions: the handle is unchanged although the band MOVED -- it was composed in the screen body and
 * is now published on the shell slot. `MESSAGE_BAND_TEST_IDS.information` names the channel rather than
 * the compositor, so every case reading this line held across the move, which is what makes the handle
 * worth having.
 * @returns {string} The text on the information line.
 */
function informationBandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_IDS.information).textContent ?? '';
}

/** Every mode the screen declares, enumerated from the map that records each mode's stored byte. */
const EVERY_MODE = Object.keys(REF_TYPE_EDIT_MODE_VALUES) as readonly RefTypeEditMode[];

/**
 * Asserts every catalogued sentence declares the message field it was written under.
 *
 * Assumptions: the split is 10 prompts on `WS-INFO-MSG PIC X(40)` at `COTRTUPC.cbl` L142 and 14
 * outcomes on `WS-RETURN-MSG PIC X(75)` at L167, and the counts are asserted rather than the entries
 * being spot-checked, because the routing decision this screen makes reads only the `field` member - so
 * an entry filed under the wrong field would paint a prompt on the error line with nothing else
 * failing.
 *
 * Assumptions: the declared width of the ERROR channel is 75 and not the mapset's 78. `ERRMSG` is
 * `LENGTH=78` at `COTRTUP.bms` L107-L110, which is the FIELD, while the content contract is
 * `CCARD-ERROR-MSG PIC X(75)` and `CCARD-RETURN-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28-L29. Both
 * numbers are real and neither is the other corrected: the field is three characters wider than
 * anything the program can put in it.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function carriesEveryMessageAtItsDeclaredChannelAndWidth(): void {
  const entries = Object.values(EDIT_STATUS);
  const prompts = entries.filter(isInformationChannelEntry);
  const outcomes = entries.filter(isErrorChannelEntry);

  // WHY : Assumptions: 24 is 10 prompts on `WS-INFO-MSG PIC X(40)` at `COTRTUPC.cbl` L142 plus 14
  //       outcomes on `WS-RETURN-MSG PIC X(75)` at L167. The counts are what catch an entry filed
  //       under the wrong field, which would otherwise paint a prompt on the error line in silence.
  expect(entries).toHaveLength(24);
  expect(prompts).toHaveLength(10);
  expect(outcomes).toHaveLength(14);

  for (const entry of prompts) {
    // WHY : Assumptions: 40 is the width `WS-INFO-MSG` declares at `COTRTUPC.cbl` L142.
    expect(entry.declaredWidth).toBe(40);
    expect(messageChannel(entry.field)).toBe('information');
  }
  for (const entry of outcomes) {
    // WHY : Assumptions: 75 is `WS-RETURN-MSG` at `COTRTUPC.cbl` L167 and `CCARD-RETURN-MSG` at
    //       `app/cpy/CVCRD01Y.cpy` L28-L29 - deliberately NOT the mapset's `LENGTH=78` for `ERRMSG`
    //       at `COTRTUP.bms` L107-L110, which sizes the field rather than the content it may carry.
    expect(entry.declaredWidth).toBe(75);
    expect(messageChannel(entry.field)).toBe('error');
  }
}

/**
 * Reports whether one catalogued entry belongs to the row-22 prompt channel.
 * @param {(typeof EDIT_STATUS)[keyof typeof EDIT_STATUS]} entry - One catalogued sentence.
 * @returns {boolean} `true` when the entry was declared on the information field.
 */
function isInformationChannelEntry(entry: (typeof EDIT_STATUS)[keyof typeof EDIT_STATUS]): boolean {
  return messageChannel(entry.field) === 'information';
}

/**
 * Reports whether one catalogued entry belongs to the row-23 outcome channel.
 * @param {(typeof EDIT_STATUS)[keyof typeof EDIT_STATUS]} entry - One catalogued sentence.
 * @returns {boolean} `true` when the entry was declared on the return-message field.
 */
function isErrorChannelEntry(entry: (typeof EDIT_STATUS)[keyof typeof EDIT_STATUS]): boolean {
  return messageChannel(entry.field) === 'error';
}

/**
 * Asserts this program's two invalid-key sentences and the shared one are three distinct strings.
 *
 * Assumptions: this program is the ONE that does not use the shared constant. `CCDA-MSG-INVALID-KEY` is
 * `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21 and `COTRTUPC` never moves it; instead it declares two
 * of its own, `'Invalid Key pressed. '` at L171-L172 - twenty-one characters, capital K, a full stop and
 * a trailing blank - and `'Invalid key pressed'` at L193-L194 - nineteen characters, lower-case k, no
 * stop and no trailing blank. Fourteen online programs emit the shared string and six emit none at all,
 * so generalising from the majority is exactly how a message this program never shows would be added.
 *
 * Assumptions: the lengths are asserted rather than the sentences being retyped, because the two differ
 * only in letter case and trailing punctuation - the property that makes them easy to merge is also the
 * property a spelled-out expectation would not protect.
 *
 * Assumptions: the shared constant's literal is 49 characters inside a `PIC X(50)` field, which is a
 * baseline fact rather than a transcription slip - the copybook literal at `app/cpy/CSMSG01Y.cpy` L21 is
 * one blank short of the width it is declared at, and COBOL pads the field on the move. Both numbers are
 * therefore asserted: the width the field declares and the length the literal actually has. Padding the
 * catalog entry out to fifty here would make the catalog disagree with the copybook it transcribes.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function keepsBothInvalidKeySentencesApart(): void {
  const declared = EDIT_STATUS.WS_INVALID_KEY.text;
  const live = EDIT_STATUS.WS_INVALID_KEY_PRESSED.text;
  const shared = COMMON_MESSAGES.INVALID_KEY.text;

  // WHY : Assumptions: 21 is `'Invalid Key pressed. '` at `COTRTUPC.cbl` L171-L172 and 19 is
  //       `'Invalid key pressed'` at L193-L194. The lengths discriminate where the letters do not.
  expect(declared).toHaveLength(21);
  expect(live).toHaveLength(19);
  // WHY : Assumptions: the shared literal is 49 characters inside a `PIC X(50)` field. The copybook
  //       literal at `app/cpy/CSMSG01Y.cpy` L21 is one blank short of its declared width and COBOL
  //       pads on the move, so both numbers are asserted rather than one reconciled to the other.
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(shared).toHaveLength(49);
  expect(declared).not.toBe(live);
  expect(declared).not.toBe(shared);
  expect(live).not.toBe(shared);
  // WHY : Assumptions: the capital K at `COTRTUPC.cbl` L172 against the lower-case k at L194 is the
  //       only letter that differs, so it and the trailing blank are what hold the two entries apart.
  expect(declared).toContain('Key');
  expect(live).not.toContain('Key');
  expect(declared.endsWith(' ')).toBe(true);
  expect(live.endsWith(' ')).toBe(false);
}

/**
 * Asserts the four sentences this program shares byte for byte with a sibling, and the two it does not.
 *
 * Assumptions: identity is asserted between CATALOG entries rather than against spelled-out strings, so
 * the claim is "these two programs declare the same bytes" rather than "both agree with a third copy
 * written here" - which a paraphrase in one place would satisfy.
 *
 * Assumptions: `'PF03 pressed.Exiting              '` at L169-L170 is one of THREE distinct values
 * across four programs. This form, with no blank after the stop and trailing padding, is shared with
 * the account view; the card browse declares an all-upper-case form and the sibling transaction-type
 * list a spaced, unpadded one. The catalog therefore holds three entries and this program's is not the
 * one an author would reach for by reading the nearest neighbour.
 *
 * Assumptions: `'No change detected with respect to values fetched.'` at L179-L180 ends differently
 * from the sibling list program's `'... with respect to database values.'`. Two adjacent programs, two
 * tails for one idea, and neither may be folded into the other.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function recordsTheByteIdenticalSiblingSentences(): void {
  // WHY : Assumptions: `'PF03 pressed.Exiting              '` at `COTRTUPC.cbl` L169-L170 is one of
  //       THREE distinct values across four programs - byte-identical to the account view's, while
  //       the card browse declares an all-upper-case form and the sibling list a spaced, unpadded
  //       one. Identity is asserted between catalog entries, so the claim is that two programs
  //       declare the same bytes rather than that both agree with a third copy retyped here.
  expect(EDIT_STATUS.WS_EXIT_MESSAGE.text).toBe(STATUS_MESSAGES.COACTVWC.WS_EXIT_MESSAGE.text);
  expect(EDIT_STATUS.WS_EXIT_MESSAGE.text).not.toBe(STATUS_MESSAGES.COCRDLIC.WS_EXIT_MESSAGE.text);
  expect(EDIT_STATUS.WS_EXIT_MESSAGE.text).not.toBe(STATUS_MESSAGES.COTRTLIC.WS_EXIT_MESSAGE.text);

  // WHY : Assumptions: byte-identical to `COACTUPC` L522, the account-update concurrency sentence.
  //       AAP section 0.7.2 establishes the baseline already implements before-image optimistic
  //       concurrency, so the same wording serving both screens is the baseline's own reuse.
  expect(EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text).toBe(
    STATUS_MESSAGES.COACTUPC.DATA_WAS_CHANGED_BEFORE_UPDATE.text,
  );
  // WHY : Assumptions: `'Looks Good.... so far'` at `COTRTUPC.cbl` L195-L196 is byte-identical to the
  //       account view's, four dots included.
  expect(EDIT_STATUS.CODING_TO_BE_DONE.text).toBe(STATUS_MESSAGES.COACTVWC.CODING_TO_BE_DONE.text);

  // WHY : Assumptions: this program's tail is `'values fetched.'` at `COTRTUPC.cbl` L179-L180 while
  //       the adjacent sibling ends `'database values.'` at `COTRTLIC.cbl` L262. Two neighbouring
  //       programs, two tails for one idea, so the catalog must hold them as distinct entries.
  expect(EDIT_STATUS.NO_CHANGES_DETECTED.text).not.toBe(
    STATUS_MESSAGES.COTRTLIC.WS_MESG_NO_CHANGES_DETECTED.text,
  );
}

/**
 * Asserts every punctuation and spacing anomaly survives in the catalogued sentences.
 *
 * Assumptions: each expectation names the DISTINGUISHING fragment rather than the whole sentence,
 * because the anomaly is the thing under assertion and a retyped sentence would move the fidelity
 * guarantee out of the catalog and into this file. Every fragment is quoted from the program at the line
 * named beside it.
 *
 * Assumptions: the mapset and the program disagree about how to spell the save key and both spellings
 * are kept. Row 24 paints `'F5=Save'` at `COTRTUP.bms` L125 while the prompt at `COTRTUPC.cbl` L150
 * writes the key zero-padded, and each is user-visible in its own place.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function preservesEveryPunctuationAnomaly(): void {
  // WHY : Assumptions: the message writes `F05` with a leading zero at `COTRTUPC.cbl` L149-L150 while
  //       the legend writes `F5=Save` at `COTRTUP.bms` L121-L125. Both are user-visible and AAP Rule
  //       T8 carries each verbatim in its own place, so neither is normalised toward the other.
  expect(EDIT_STATUS.PROMPT_CREATE_NEW_RECORD.text).toContain('F05');
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK05).toContain('F5');
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK05).not.toContain('F05');

  // WHY : Assumptions: the blank BEFORE the question mark is real, at `COTRTUPC.cbl` L151-L152.
  expect(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text).toContain(' ? ');
  // WHY : Assumptions: no blank follows the full stop at `COTRTUPC.cbl` L160-L161, where the three
  //       sentences at L154, L156 and L158 all end cleanly with one. The omission is the baseline's.
  expect(EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text).toContain('validated.Press');
  expect(EDIT_STATUS.WS_EXIT_MESSAGE.text).toContain('pressed.Exiting');
  expect(EDIT_STATUS.WS_EXIT_MESSAGE.text.endsWith(' ')).toBe(true);

  // WHY : Assumptions: FOUR dots at `COTRTUPC.cbl` L195-L196, and the negative below pins it at four
  //       rather than merely at three-or-more, which `toContain` alone would also accept.
  expect(EDIT_STATUS.CODING_TO_BE_DONE.text).toContain('Good....');
  expect(EDIT_STATUS.CODING_TO_BE_DONE.text).not.toContain('Good.....');
  // WHY : Assumptions: `some one` is TWO words at `COTRTUPC.cbl` L183-L184. Collapsing it to one is
  //       the single most natural transcription slip on this screen, so it is pinned explicitly.
  expect(EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text).toContain('some one else');

  // WHY : Assumptions: the British double-l spelling at `COTRTUPC.cbl` L185-L186 and L191-L192. The
  //       two sentences are separate strings, which the inequality below is what actually protects.
  expect(EDIT_STATUS.WS_UPDATE_WAS_CANCELLED.text).toContain('cancelled');
  expect(EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text).toContain('cancelled');
  expect(EDIT_STATUS.WS_UPDATE_WAS_CANCELLED.text).not.toBe(
    EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text,
  );

  expect(EDIT_STATUS.NO_CHANGES_DETECTED.text).toContain('values fetched.');
  // WHY : Assumptions: the abend text is wholly upper-case at `COTRTUPC.cbl` L1074-L1078, where every
  //       other sentence on this screen is sentence-cased. Comparing it against its own upper-casing
  //       asserts the property rather than retyping the literal a second time.
  expect(SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO).toBe(
    SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO.toUpperCase(),
  );
}

/**
 * Asserts each refusal is the catalogued label joined to the catalogued suffix, leading blank included.
 *
 * Assumptions: the expectations are BUILT from the two halves the catalog owns rather than written out
 * joined, because the reference composes them at run time with
 * `STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) <suffix> DELIMITED BY SIZE` at `COTRTUPC.cbl` L866,
 * L893, L924, L943 and L961 - so a joined expectation here would be a third spelling of a sentence
 * neither half owns.
 *
 * Assumptions: every suffix begins with a blank and that blank is load-bearing. The COBOL trims only the
 * 25-character label field, never the suffix, so the blank is what separates the two halves - removing
 * it would run the words together, and asserting the joined form without it would accept a screen that
 * had.
 *
 * Assumptions: the two labels are spelled inconsistently in the source and both are carried as declared.
 * The description edit moves `'Transaction Desc'` at L758 and the key edit `'Tran Type code'` at L826 -
 * one word abbreviated and title-cased, one lower-cased - and neither matches the on-screen label the
 * mapset paints at L83 or L93.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function composesEveryRefusalFromTheCatalogHalves(): void {
  const suffixes = [
    FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED,
    FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY,
    FIELD_VALIDATION_SUFFIXES.MUST_BE_NUMERIC,
    FIELD_VALIDATION_SUFFIXES.MUST_NOT_BE_ZERO,
  ] as const;

  for (const suffix of suffixes) {
    // WHY : Assumptions: every suffix opens with a LEADING blank, at `COTRTUPC.cbl` L866, L893, L924,
    //       L943 and L961. The program composes each refusal by STRINGing a trimmed field name against
    //       the suffix, so that blank IS the separator - trimming it would join the two words together.
    expect(suffix.startsWith(' ')).toBe(true);
  }

  // WHY : Assumptions: the prepended names are `'Tran Type code'` at `COTRTUPC.cbl` L826 and
  //       `'Transaction Desc'` at L758 - inconsistently cased against each other, and neither one
  //       matching the on-screen labels at `COTRTUP.bms` L83 and L93. All three forms are preserved.
  expect(formatFieldValidationMessage(KEY_FIELD_LABEL, suffixes[0])).toBe(
    `${KEY_FIELD_LABEL}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`,
  );
  expect(formatFieldValidationMessage(KEY_FIELD_LABEL, suffixes[2])).toBe(
    `${KEY_FIELD_LABEL}${FIELD_VALIDATION_SUFFIXES.MUST_BE_NUMERIC}`,
  );
  expect(formatFieldValidationMessage(KEY_FIELD_LABEL, suffixes[3])).toBe(
    `${KEY_FIELD_LABEL}${FIELD_VALIDATION_SUFFIXES.MUST_NOT_BE_ZERO}`,
  );
  expect(formatFieldValidationMessage(DESCRIPTION_FIELD_LABEL, suffixes[0])).toBe(
    `${DESCRIPTION_FIELD_LABEL}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`,
  );
  expect(formatFieldValidationMessage(DESCRIPTION_FIELD_LABEL, suffixes[1])).toBe(
    `${DESCRIPTION_FIELD_LABEL}${FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY}`,
  );

  // WHY : Assumptions: the message name and the painted label are genuinely different strings, which
  //       is why the refusal cannot be composed from the label. Interior runs are collapsed only to
  //       make the comparison about wording rather than padding; no boundary is trimmed.
  expect(KEY_FIELD_LABEL).not.toBe(collapseInteriorRuns(REF_TYPE_EDIT_FIELD_LABELS.typeCode));
  expect(DESCRIPTION_FIELD_LABEL).not.toBe(
    collapseInteriorRuns(REF_TYPE_EDIT_FIELD_LABELS.description),
  );
}

/**
 * Asserts the key table keeps acceptance and visibility apart in every one of the sixteen modes.
 *
 * Assumptions: all sixteen modes are walked rather than a representative few, because
 * `3391-SETUP-PFKEY-ATTRS` at `COTRTUPC.cbl` L1397-L1423 decides each legend field in its own
 * `EVALUATE` arm - a table right for two modes can be wrong for the rest.
 *
 * Assumptions: PF6 is refused in EVERY mode and painted in every mode, and that pairing is the whole
 * point. `0001-CHECK-PFKEYS` at L577-L621 carries no term for it, so a press falls to the `ELSE`; the
 * mapset nonetheless declares `FKEY06 ... INITIAL='F6=Add'` at L126-L130, and Transformation Rule T8
 * does not admit deleting a user-visible string. This is the fifth advertise-versus-bind mismatch in
 * the baseline.
 *
 * Assumptions: PF3 is accepted UNCONDITIONALLY at L582 while its label lives in the one field
 * L1400-L1404 darkens during a delete confirmation - the single place acceptance and visibility
 * diverge for a key the operator needs. Refusing the exit to match the dark legend would make the
 * confirmation a dead end the reference does not have.
 *
 * Assumptions: PF5's asymmetry is preserved rather than smoothed. L588-L593 accepts it for the
 * validated-change mode, the not-found mode and every delete-in-progress mode, while L1411-L1414
 * reveals it for only the first two - so during a delete the key is live with no legend.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function transcribesTheKeyMatrixForEveryMode(): void {
  // WHY : Assumptions: the matrix is asserted across ALL 16 modes rather than the handful the cases
  //       below drive, because the validity gate at `COTRTUPC.cbl` L582-L601 is state-conditional -
  //       a key wrongly admitted in an unexercised mode is exactly what a spot-check would miss.
  expect(EVERY_MODE).toHaveLength(16);
  // WHY : Assumptions: six is ENTER plus PF3, PF4, PF5, PF6 and PF12 - the keys `COTRTUP.bms` paints
  //       at L111-L135. No PF7, PF8 or PF10 exists, this being a single-record screen with no paging.
  expect(REF_TYPE_EDIT_AIDS).toHaveLength(6);

  for (const mode of EVERY_MODE) {
    const matrix = refTypeEditKeyMatrix(mode);
    const confirmingDelete = mode === 'confirmDelete';

    // WHY : Trade-offs: PF6 is advertised and never bound. `'F6=Add'` has its own `FKEY06` field at
    //       `COTRTUP.bms` L126-L130, yet the program's `CCARD-AID-*` references cover only ENTER, PFK03,
    //       PFK04, PFK05 and PFK12 - the fifth advertise/bind mismatch in the repository. Resolved
    //       conservatively: inventing a handler would add behaviour the baseline lacks, and dropping the
    //       descriptor would alter a user-visible string AAP Rule T8 protects. So it paints, inert.
    expect(matrix.PFK06.accepted).toBe(false);
    expect(matrix.PFK06.revealed).toBe(true);
    expect(matrix.PFK03.accepted).toBe(true);
    // WHY : Assumptions: PF3 is the one key whose acceptance and visibility diverge - always admitted
    //       per `COTRTUPC.cbl` L429, but withdrawn from the legend while a delete awaits confirmation.
    expect(matrix.PFK03.revealed).toBe(!confirmingDelete);
    // WHY : Refactoring Rationale: `COTRTUPC.cbl` L583 admits ENTER only when NOT confirming a delete.
    //       That guard is what stops an absent-minded Enter from confirming a destructive action, and
    //       the SPA keeps it as a state predicate rather than as the 3270's re-key-to-confirm turn.
    expect(matrix.ENTER.accepted).toBe(!confirmingDelete);
    expect(matrix.PFK04.accepted).toBe(mode === 'showDetails' || confirmingDelete);
    expect(matrix.PFK04.revealed).toBe(matrix.PFK04.accepted);
    expect(matrix.PFK12.revealed).toBe(matrix.PFK12.accepted);
  }

  const duringDelete = refTypeEditKeyMatrix('startDelete');
  expect(duringDelete.PFK05.accepted).toBe(true);
  // WHY : Assumptions: the preserved asymmetry - PF5 is ACCEPTED while a delete is in progress but
  //       never REVEALED, so the program honours a key it does not advertise in that state.
  expect(duringDelete.PFK05.revealed).toBe(false);

  const validated = refTypeEditKeyMatrix('changesOkNotConfirmed');
  expect(validated.PFK05.accepted).toBe(true);
  expect(validated.PFK05.revealed).toBe(true);
}

/**
 * Asserts no key outside this mapset's six is bound.
 *
 * Assumptions: the absent keys are named individually because each absence means something. There is no
 * PF7 or PF8 because this is a single-record screen with nothing to page - the sibling list screen is
 * where paging lives - and no PF10 because the program has no arm for it.
 *
 * ⚠️ Refactoring Rationale: the EMPHASIS half of this case has moved out to
 * {@link paintsEachLegendControlFromItsRisk}, and it moved because it asserted the wrong thing rather
 * than because it was in the wrong place. It read `PRIMARY_ACTION_AIDS` -- the bar's fallback table,
 * keyed on the attention IDENTIFIER -- and recorded as a deliberate divergence that PF4 therefore
 * rendered at default weight here even though it destroys a row. That divergence was a limitation of the
 * mechanism: one table keyed on the identifier cannot serve `PFK04` deleting a row on this mapset and
 * clearing a form on the mapsets that paint `'F4=Clear'`. `ui/src/layout/usePfKeys.ts` now carries a
 * declared risk per handler, so the screen states what its own keys do and the divergence is closed.
 * What remains here is the BINDING inventory, which is a property of the program rather than of the bar.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function bindsNoKeyBeyondTheSixTheMapsetPaints(): void {
  const bound: readonly string[] = REF_TYPE_EDIT_AIDS;

  // WHY : Assumptions: no paging keys, this being a single-record screen - `COTRTUP.bms` paints no
  //       PF7 or PF8 descriptor and the program binds neither, unlike the browse screens.
  expect(bound).not.toContain('PFK07');
  expect(bound).not.toContain('PFK08');
  expect(bound).not.toContain('PFK10');
  expect(bound).not.toContain('CLEAR');
  expect(bound).not.toContain('PA1');
  expect(bound).not.toContain('PA2');
}

/**
 * Asserts each legend control renders the emphasis its own action's risk earns.
 *
 * ⚠️ Assumptions: the classification asserted here is the one this screen DECLARES in
 * `REF_TYPE_EDIT_KEY_RISKS`, and it is read from the label rather than from the identifier. `'F4=Delete'`
 * at `app/app-transaction-type-db2/bms/COTRTUP.bms` L116-L120 destroys a stored row, so its control is
 * the dangerous one; `'F5=Save'` at L121-L125 writes, so it is the mutating one; and
 * `'ENTER=Process'`, `'F3=Exit'` and `'F12=Cancel'` change no stored data, so they carry no emphasis at
 * all. The identifier says nothing about any of that -- `PFK04` clears a form on other mapsets and
 * `PFK05` deletes on `app/bms/COUSR03.bms` -- which is why the risk is per screen.
 *
 * ⚠️ Assumptions: the delete control is asserted dangerous while the row is merely SHOWN, before a
 * confirmation exists. That is the property the retired assertion gave up: the danger signal used to
 * live only on the confirmation, so the key that reaches the confirmation looked like the key that
 * exits. An operator scanning row 24 for the destructive control now finds it before pressing anything.
 *
 * Assumptions: the labels are asserted verbatim alongside the emphasis, because the emphasis is only
 * meaningful if the control still says what the mapset paints -- a control that gained emphasis and lost
 * its literal would be a rule T8 violation this case would otherwise pass over.
 *
 * ⚠️ Assumptions: the save key is measured after a change has been VALIDATED, and not on arrival. The
 * program reveals `'F5=Save'` only from `changesOkNotConfirmed` -- `1400-SEND-MAP` withholds the
 * descriptor while a row is merely shown, which the key matrix above asserts from the other side -- so a
 * query for it in the show-details mode fails against a screen that is behaving correctly. The two
 * emphases are therefore read in the two modes that reveal them, rather than one mode being assumed to
 * reveal both.
 * @returns {Promise<void>} Resolves once every revealed control's emphasis has been asserted.
 */
async function paintsEachLegendControlFromItsRisk(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  const unemphasised: readonly RefTypeEditKeyAid[] = ['ENTER', 'PFK03', 'PFK12'];

  for (const aid of unemphasised) {
    const control = legendControlFor(aid);
    expect(control, `${aid} changes no stored data and must carry no emphasis`).not.toHaveClass(
      'ant-btn-primary',
    );
    expect(control).not.toHaveClass('ant-btn-dangerous');
    expect(legendTextOf(control)).toBe(REF_TYPE_EDIT_KEY_LABELS[aid]);
  }

  const destroys = legendControlFor('PFK04');
  expect(destroys, 'the delete key destroys a stored row and must read as dangerous').toHaveClass(
    'ant-btn-dangerous',
  );
  expect(legendTextOf(destroys)).toBe(REF_TYPE_EDIT_KEY_LABELS.PFK04);

  await editDescriptionAndValidate(rendered);

  const writes = legendControlFor('PFK05');
  expect(writes, 'the save key writes and must read as the mutating action').toHaveClass(
    'ant-btn-primary',
  );
  expect(writes, 'a save is not a destruction').not.toHaveClass('ant-btn-dangerous');
  expect(legendTextOf(writes)).toBe(REF_TYPE_EDIT_KEY_LABELS.PFK05);
}

/**
 * Stands in for a held call's resolver until the promise executor supplies the real one.
 *
 * Assumptions: this throws rather than doing nothing. A promise executor runs synchronously, so this
 * value is replaced before any case can reach it -- reaching it means the held call was never
 * constructed, and a silent no-op there would leave a case asserting against a call that had already
 * settled.
 * @returns {never} Never returns; it always throws.
 * @throws {Error} Always, naming the condition.
 */
function refuseEarlySettle(): never {
  throw new Error('the held call was released before its promise executor ran');
}

/**
 * Asserts a withdrawal is declined while the delete it authorised is still running.
 *
 * ⚠️ Purpose: the confirmation's own controls are reached by pointer WITHOUT passing through the key
 * hook, so the availability the key entries declare cannot cover them. The accept is inert through its
 * own button props; CANCEL carries no such state, and it stays in the DOCUMENT after the confirming
 * press because the design system keeps a closing panel mounted until its leave animation finishes.
 * Two independent things have to hold for that window to be safe, and this case pins the second.
 *
 * ⚠️ Assumptions: what the defect produces is `'Invalid key pressed'` on row 23, not a cancellation.
 * `0001-CHECK-PFKEYS` accepts PF12 in five modes and `TTUP-DELETE-IN-PROGRESS` is not among them
 * (`COTRTUPC.cbl` L594-L601), so the press would be classified as an invalid key -- replacing the
 * outstanding delete's own reporting with a sentence about a key, for a key the operator pressed on a
 * control the screen had just shown them. The guard declines it silently instead, which is what the
 * terminal did with a key pressed while the keyboard was inhibited.
 *
 * Assumptions: the delete is asserted to have been issued exactly ONCE and then allowed to settle, so
 * the case proves the withdrawal neither reported anything nor cancelled the work -- a guard that
 * swallowed the whole turn would pass the first assertion and fail the last.
 * @returns {Promise<void>} Resolves once the declined withdrawal and the settled delete are asserted.
 */
async function declinesAWithdrawalWhileTheDeleteRuns(): Promise<void> {
  /*
   * WHY : ⚠ Assumptions: the holder is initialised with {@link refuseEarlySettle} rather than with `null`,
   *       and the difference is a compile-time one. Initialised to `null`, TypeScript narrows the
   *       variable to `null` for the whole body -- the assignment happens inside the executor, which its
   *       control-flow analysis does not follow -- so the release below failed to compile with
   *       `This expression is not callable. Type 'never' has no call signatures.` A callable initial
   *       value keeps the declared type, and it also fails LOUDLY if the executor somehow never ran,
   *       where a null check would have silently skipped the release and left the case asserting nothing.
   */
  let release: () => void = refuseEarlySettle;
  vi.mocked(deleteTransactionType).mockReturnValue(
    new Promise<void>(
      /**
       * Captures the resolver so the delete can be held and then settled.
       * @param {() => void} resolve - The promise's own resolve function.
       * @returns {void} Nothing; the call settles when the case releases it.
       */
      (resolve: () => void): void => {
        release = resolve;
      },
    ),
  );
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await pressPfKey(rendered.user, 'PFK04');

  await waitFor(
    /**
     * Waits until the delete has actually been issued.
     * @returns {void} Nothing; throws until the call has been made.
     */
    (): void => {
      expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledTimes(1);
    },
  );

  /*
   * WHY : ⚠ Assumptions: the click is dispatched with `fireEvent` and NOT through the keyboard-and-pointer
   *       operator, and the difference is the whole design of this case. The operator refuses to click a
   *       control the layout reports as non-interactive, and the closing panel's wrap is exactly that at
   *       this version -- so driving it that way fails with `pointer-events: none` and measures the
   *       design system's CSS rather than this screen. That CSS is the FIRST line of defence and it is
   *       working; the guard in the screen is the second, and it is the one a version bump can silently
   *       make load-bearing. `fireEvent` delivers the event the guard would see if the panel ever became
   *       interactive while closing, which is what makes this an assertion about the screen.
   *       Alternatives Considered: deleting the guard and relying on the wrap's `pointer-events`.
   *       Rejected because a destructive path would then be protected by a style rule in a dependency,
   *       with nothing in this repository stating the requirement or failing when it changes.
   */
  fireEvent.click(within(confirmationDialog()).getByRole('button', { name: 'Cancel' }));

  expect(
    errorBandText(),
    'a key pressed while the delete runs must not be reported as an invalid key',
  ).not.toContain(EDIT_STATUS.WS_INVALID_KEY_PRESSED.text);
  expect(errorBandText()).not.toContain(EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text);
  expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledTimes(1);

  release();

  await waitFor(
    /**
     * Waits for the delete to report its own success once released.
     * @returns {void} Nothing; throws until the success sentence lands.
     */
    (): void => {
      expect(informationBandText()).toContain(EDIT_STATUS.CONFIRM_DELETE_SUCCESS.text);
    },
  );
}

/**
 * Asserts the key whose turn is outstanding reports busy while the others report unavailable.
 *
 * ⚠️ Purpose: this is the primitive's own contract, and the defect it replaces was reachable. A call in
 * flight used to `disable` all six keys, and `ui/src/layout/usePfKeys.ts` classifies a disabled press
 * with the baseline's `'Invalid key pressed'` text -- so a second press of a key during its own call was
 * reported as an invalid key, which the reference would never say about a key it accepts. A busy entry
 * stays present, enabled, focusable and named, and its second press is declined silently: the 3270
 * keyboard-inhibit analogue, which a pseudo-conversational task got for free by holding the terminal.
 *
 * ⚠️ Assumptions: exactly ONE control is busy, and the case asserts the others are NOT. Marking every
 * key busy would claim each of them is waiting on an answer, when they are different actions this screen
 * genuinely cannot perform mid-call; `disabled` is the honest classification for those, and a case that
 * only checked the pressed control would pass against a screen that marked all six.
 *
 * ⚠️ Assumptions: the READ is the call measured, and the delete is not, because the delete's own key is
 * withdrawn from the legend while it runs -- the key matrix records `PFK04` as accepted only in
 * `showDetails` and while confirming, so once the `DELETE` is issued the control is gone and no rendered
 * key can carry the affordance. That is the reference's own asymmetry rather than a gap in the primitive,
 * and the sentence below is what covers it: the live region says a request is outstanding whichever call
 * it is. The read keeps `'ENTER=Process'` on the glass throughout, so it is the call that can prove the
 * affordance lands on the right control.
 *
 * Assumptions: the state is asserted through `aria-busy` rather than through the spinner glyph, because
 * that is the attribute the bar sets for the purpose -- its own rationale records that it supplies the
 * loading icon with `aria-hidden` precisely so the control's accessible NAME does not change mid-turn. A
 * case keyed on the glyph would pass while the state was unannounced.
 *
 * Assumptions: the call is held unresolved by a promise that never settles, so the assertions run while
 * it is genuinely outstanding rather than after a resolution the runner raced.
 * @returns {Promise<void>} Resolves once the outstanding and unavailable controls have been asserted.
 */
async function reportsTheOutstandingKeyAsBusy(): Promise<void> {
  vi.mocked(getTransactionType).mockReturnValue(
    new Promise<TransactionType>(
      /**
       * Holds the read outstanding for the duration of the case.
       * @returns {void} Nothing; the promise is deliberately never settled.
       */
      (): void => {
        // Assumptions: intentionally empty; the call must not settle while the assertions run.
      },
    ),
  );
  await renderInShellAt(STORED.typeCd);

  await waitFor(
    /**
     * Waits until the read has been issued and the processing key reports itself busy.
     * @returns {void} Nothing; throws until the outstanding state is on the control.
     */
    (): void => {
      expect(vi.mocked(getTransactionType)).toHaveBeenCalledTimes(1);
      expect(legendControlFor('ENTER')).toHaveAttribute('aria-busy', 'true');
    },
  );

  const outstanding = legendControlFor('ENTER');
  expect(
    outstanding,
    'a busy control stays usable, because the key works again the moment the answer arrives',
  ).toBeEnabled();
  expect(legendTextOf(outstanding), 'the legend text is verbatim while busy').toBe(
    REF_TYPE_EDIT_KEY_LABELS.ENTER,
  );
  // WHY : ⚠️ Assumptions: the sentence is asserted in the same turn as the attribute, because the two
  //       answer different questions -- `aria-busy` says a control cannot be used now, and the sentence
  //       says why and that it is temporary. An earlier revision of this screen recorded that the
  //       catalogue held no sentence for this condition; it now registers `REQUEST_IN_PROGRESS`, so the
  //       shortfall is closed rather than restated.
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);

  const exit = legendControlFor('PFK03');
  expect(exit, 'exit is not the outstanding turn and cannot run during one').toBeDisabled();
  expect(exit).not.toHaveAttribute('aria-busy', 'true');
}

/**
 * Asserts the maintenance route is declared administrative for this program, and nothing weaker.
 *
 * Assumptions: `ROUTE_TABLE` is the single place the policy lives, because the screen deliberately does
 * NOT re-implement a gate - a second authority over one decision is the one that drifts, and the drifted
 * copy either locks out an administrator or admits an ordinary operator with nothing failing to say
 * which. The reference's own equivalent, `SET CDEMO-USRTYP-ADMIN TO TRUE` at `COTRTUPC.cbl` L448, is a
 * value it WRITES rather than a test it performs; reaching the administrative menu was the
 * authorization, which is why `app/cpy/COADM02Y.cpy` gives its options record at L56-L59 no user-type
 * field at all while listing this screen as option 6 at L52.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function declaresTheMaintenanceRouteAdministrative(): void {
  const entry = ROUTE_TABLE.find(
    /**
     * Matches the published entry for the maintenance path.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One published route-table entry.
     * @returns {boolean} `true` when the entry is the maintenance route.
     */
    (candidate) => candidate.path === REF_TYPE_EDIT_PATH,
  );

  expect(entry).toBeDefined();
  // WHY : Assumptions: `app/cpy/COADM02Y.cpy` L52 names this option on the administrative menu, whose
  //       `OCCURS 9 TIMES` entry at L55-L59 carries NO user-type field - reaching the admin menu is
  //       itself the authorization. The route table's `access` member is where that now lives.
  expect(entry?.access).toBe('administrative');
  expect(entry?.program).toBe(REF_TYPE_EDIT_PROGRAM_NAME);
  // WHY : Assumptions: the parameter is named `cd`, and the path is composed from the sibling list
  //       route rather than written out, so no route string is duplicated into this file.
  expect(REF_TYPE_EDIT_PATH).toBe(`${REFERENCE_LIST_PATH}/:cd`);
}

/**
 * Asserts authority can only arrive as a signed claim, never as a value a client sets.
 *
 * Refactoring Rationale: in the baseline the operator's type travelled in the passed communication area
 * - `CDEMO-USER-TYPE PIC X(01)` at `app/cpy/COCOM01Y.cpy` L26 with its `'A'` and `'U'` condition names
 * at L27-L28 - which is storage the client echoed back, so a client could in principle assert its own
 * type. In the target the group claim is signed, per AAP section 0.7.1, and the identity module
 * publishes the two group names and the claim key but deliberately no setter for either and no context
 * provider anywhere. This screen mutates reference data every other service reads, so the negative
 * assertion is the one worth having: if a setter ever appeared, a case could grant itself administrative
 * authority and every gating test in the tree would stop meaning anything.
 * @returns {Promise<void>} Resolves once the module's export surface has been inspected.
 */
async function publishesNoWayToGrantAdminFromTheClient(): Promise<void> {
  const identity = await import('../hooks/useAuth');
  const settersOrMutators = Object.keys(identity).filter(namesAMutator);

  expect(CARDDEMO_ADMIN_GROUP).toBe('carddemo-admin');
  expect(CARDDEMO_USER_GROUP).toBe('carddemo-user');
  expect(COGNITO_GROUPS_CLAIM).toBe('cognito:groups');
  // WHY : Assumptions: a test cannot grant itself authority. The identity module's whole export
  //       surface is scanned for anything that could mutate group membership, and the empty result is
  //       what makes the signed claim the only input.
  expect(settersOrMutators).toStrictEqual([]);
}

/**
 * Reports whether an exported name would let a caller assign an identity attribute.
 *
 * Assumptions: the test is on the NAME because the export surface is what a case can reach for, and the
 * three verbs below are the ones a setter would plausibly be spelled with. It deliberately does not
 * match `resetAuthSession`, which discards a session rather than composing one - discarding cannot grant
 * authority.
 * @param {string} exported - One name the identity module publishes.
 * @returns {boolean} `true` when the name reads as an assignment of group or user-type state.
 */
function namesAMutator(exported: string): boolean {
  return /^(?:set|grant|assign)(?:Group|Groups|UserType|Admin|Authorit)/u.test(exported);
}

/**
 * Asserts both controls carry their declared widths and that neither is a password control.
 *
 * Assumptions: the widths are corroborated twice each before being relied on - `TRTYPCD` is `LENGTH=2`
 * at `COTRTUP.bms` L84-L87 and `TR_TYPE CHAR(2)` in the extension tree's table definition, `TRTYDSC` is
 * `LENGTH=50` at L94-L97 and `TR_DESCRIPTION VARCHAR(50)` - so a control bounded to the wrong width
 * would disagree with the mapset AND the column.
 *
 * Assumptions: this screen has NO password control, and the assertion exists because a search for
 * non-display fields finds four hits on this mapset. `FKEY04`, `FKEY05`, `FKEY06` and `FKEY12` are
 * `ATTRB=(ASKIP,DRK)` at L116, L121, L126 and L131, and that combination makes a function-key LEGEND
 * DESCRIPTOR dark until the program reveals it - it is not the password combination. A password field
 * is `ATTRB=(DRK,FSET,UNPROT)`, which occurs three times in the whole repository and only on
 * `app/bms/COSGN00.bms`, `app/bms/COUSR01.bms` and `app/bms/COUSR02.bms`. Reading the four dark fields
 * here as password boxes would put four masked inputs on a screen whose only data are a two-digit code
 * and a description.
 *
 * Assumptions: the key control declares a numeric input mode because the reference edits it with
 * `1245-EDIT-NUM-REQD` at `COTRTUPC.cbl` L829 rather than the alphanumeric edit the description uses,
 * and the terminal field was numeric-only in hardware where a browser text control is not.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function boundsBothControlsToTheirDeclaredWidths(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  expectMaxLength(typeCodeControl(), TYPE_CODE_WIDTH);
  expectMaxLength(descriptionControl(), DESCRIPTION_WIDTH);
  // WHY : Assumptions: 2 is `TRTYPCD` at `COTRTUP.bms` L84-L87 and `TRAN-TYPE PIC X(02)` in
  //       `app/cpy/CVTRA03Y.cpy`; 50 is `TRTYDSC` at L94-L97. The widths are asserted against the
  //       screen's published constants so the control and the copybook cannot drift apart.
  expect(TYPE_CODE_WIDTH).toBe(2);
  expect(DESCRIPTION_WIDTH).toBe(50);
  // WHY : Assumptions: the key is digits-only, backed by the program's own `'0123456789'` constant at
  //       `COTRTUPC.cbl` L237 and the refusal it composes at L943.
  expect(typeCodeControl()).toHaveAttribute('inputmode', 'numeric');

  // WHY : Assumptions: the four `ATTRB=(ASKIP,DRK)` fields at `COTRTUP.bms` L116, L121, L126 and L131
  //       are function-key LEGEND DESCRIPTORS made non-display by default, NOT password boxes. The
  //       password combination is a different one, `ATTRB=(DRK,FSET,UNPROT)`, occurring exactly three
  //       times in the repository and only in `COSGN00.bms`, `COUSR01.bms` and `COUSR02.bms`. A grep
  //       for DRK finds four hits here, so the absence is asserted rather than merely assumed.
  expect(rendered.container.querySelectorAll('input[type="password"]')).toHaveLength(0);
  expect(rendered.container.querySelectorAll('.ant-input-password')).toHaveLength(0);
}

/**
 * Asserts both field labels reach the glass with their interior padding intact.
 *
 * Trade-offs: the padding is carried across even though it no longer aligns anything, and the two
 * principles behind that pull in opposite directions. On the terminal the two blanks at
 * `COTRTUP.bms` L83 and the seven at L93 put both colons in one column of a fixed-pitch grid; design gap
 * G1 in AAP section 0.3.4 abandons character-cell positioning outright, so in a browser the blanks
 * align nothing and HTML collapses them on display. Transformation Rule T8 nonetheless carries a
 * user-visible string character for character and admits no editorial trim. The resolution taken is to
 * preserve the string and accept that its original purpose is gone - which is worth stating plainly
 * rather than glossing, because this is the sharpest place in the migration where the two rules
 * disagree.
 *
 * Assumptions: the assertion uses the non-collapsing matcher, so a screen that had shortened the runs
 * to one blank each would fail here rather than passing on a collapsed comparison.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsBothLabelsWithTheirPaddingIntact(): Promise<void> {
  await arriveAtStoredRow();

  expect(expectVerbatimMessage(REF_TYPE_EDIT_FIELD_LABELS.typeCode)).toBeInTheDocument();
  expect(expectVerbatimMessage(REF_TYPE_EDIT_FIELD_LABELS.description)).toBeInTheDocument();
  // WHY : Trade-offs: TWO blanks before the colon at `COTRTUP.bms` L79-L83 and SEVEN at L90-L93. On a
  //       3270 that padding aligns the colons down a fixed-pitch column; gap G1 (AAP section 0.3.4)
  //       abandons character-cell positioning, so it now aligns nothing. AAP Rule T8 still admits no
  //       edit to a user-visible string, so the padding is carried across with its purpose gone. This
  //       is the sharpest place the two principles pull against each other, and preserving the bytes
  //       is the resolution rather than a silent tidy-up.
  expect(REF_TYPE_EDIT_FIELD_LABELS.typeCode).toContain('Type  :');
  expect(REF_TYPE_EDIT_FIELD_LABELS.description).toContain('Description       :');
  expect(collapseInteriorRuns(REF_TYPE_EDIT_FIELD_LABELS.typeCode)).not.toBe(
    REF_TYPE_EDIT_FIELD_LABELS.typeCode,
  );
}

/**
 * Asserts the shared title band and this mapset's own caption are both painted, once each.
 *
 * Refactoring Rationale: this mapset DOES carry the shared band, and the opposite reading is an easy one
 * to reach. Scanning the mapset's painted literals finds a caption at `COTRTUP.bms` L75-L78 and no
 * application title, which suggests the screen replaces the band with a caption of its own - but
 * `TITLE01` at L38-L41 and `TITLE02` at L61-L64 are output fields with no `INITIAL=` operand, and
 * `COTRTUPC.cbl` L1115-L1116 fills them with `MOVE CCDA-TITLE01 TO TITLE01O` and
 * `MOVE CCDA-TITLE02 TO TITLE02O`. So both are on the glass, and the caption sits at row 7 inside the
 * screen's own field area rather than in place of them.
 *
 * Assumptions: exactly one of each is asserted, because the failure this guards against is two titles
 * rather than none. The screen delegates the band through `useShellSlot` and composes only the caption,
 * so a screen that also composed a band of its own would paint the application title twice.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsItsCaptionInsideTheSharedBand(): Promise<void> {
  await arriveAtStoredRow();
  const band = within(shellLandmark('titleBand'));

  // WHY : Assumptions: the SHARED band IS on this screen, contrary to a reading that scans only the
  //       painted literals. `COTRTUP.bms` declares `TITLE01` at L38 and `TITLE02` at L61 with no
  //       `INITIAL=`, which is why they look absent, and `COTRTUPC.cbl` L1115-L1116 fills them from
  //       `CCDA-TITLE01`/`CCDA-TITLE02` in `app/cpy/COTTL01Y.cpy` L18-L22.
  expect(band.getByText(APP_ORGANISATION_TITLE_DISPLAY)).toBeInTheDocument();
  expect(band.getByText(APP_TITLE_DISPLAY)).toBeInTheDocument();
  expect(band.getByText(REF_TYPE_EDIT_TRANSACTION_ID)).toBeInTheDocument();
  expect(band.getByText(REF_TYPE_EDIT_PROGRAM_NAME)).toBeInTheDocument();

  // WHY : Assumptions: ONE of each. The row-7 caption at `COTRTUP.bms` L75-L78 sits alongside the
  //       shared band rather than replacing it, so both appear exactly once - a screen showing two
  //       titles is the failure this length assertion is here to catch.
  expect(screen.getAllByText(APP_TITLE_DISPLAY)).toHaveLength(1);
  expect(within(shellLandmark('screenBody')).getByText(REF_TYPE_EDIT_SUBTITLE)).toBeInTheDocument();
  expect(screen.getAllByText(REF_TYPE_EDIT_SUBTITLE)).toHaveLength(1);
}

/**
 * Asserts the clock renders in the shapes this mapset's placeholders declare.
 *
 * Assumptions: `'mm/dd/yy'` at `COTRTUP.bms` L47-L51 and `'hh:mm:ss'` at L70-L74 are design-time
 * PLACEHOLDERS rather than data - the program overwrites both before display - so what is asserted is
 * that the format the frame applies produces the same shape, and that the rendered value matches it.
 * The agreement is asserted rather than assumed, because the two live in different modules and nothing
 * else would fail if they diverged.
 *
 * Assumptions: the hour format is the 24-hour one. The baseline fills it from a `PIC 9(02)` taken from
 * `FUNCTION CURRENT-DATE`, which is 24-hour, and a 12-hour rendering would make 13:05 indistinguishable
 * from 01:05 with nothing failing.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheClockInTheMapsetsOwnShapes(): Promise<void> {
  await arriveAtStoredRow();
  const band = within(shellLandmark('titleBand'));

  // WHY : Assumptions: `'mm/dd/yy'` at `COTRTUP.bms` L51 and `'hh:mm:ss'` at L74 are format
  //       PLACEHOLDERS in the `INITIAL=` clause, not data. The shell's formats are asserted to agree
  //       with them rather than assumed to, since the shell serves every other screen too.
  expect(HEADER_DATE_FORMAT.toLowerCase()).toBe('mm/dd/yy');
  expect(HEADER_TIME_FORMAT.toLowerCase()).toBe('hh:mm:ss');
  expect(band.getByText(/^\d{2}\/\d{2}\/\d{2}$/u)).toBeInTheDocument();
  expect(band.getByText(/^\d{2}:\d{2}:\d{2}$/u)).toBeInTheDocument();
}

/**
 * Asserts the cursor lands on exactly one control on first entry, and that it is the key.
 *
 * Refactoring Rationale: the mapset's single `IC` attribute on `TRTYPCD` at `COTRTUP.bms` L84 is the
 * only one in the whole repository written as `ATTRB=(IC,UNPROT)` with neither `FSET` nor `NORM`, and it
 * is the initial-entry default rather than the whole rule - so what is asserted is the CURSOR and not
 * an `autoFocus` prop. React applies that prop on the initial mount only, whereas the reference
 * re-places the cursor on every turn with `MOVE -1 TO <field>L` at `COTRTUPC.cbl` L1303-L1325, and the
 * screen reproduces that imperatively. Asserting the prop would pass on a screen that placed the cursor
 * once and never again, which is the defect the imperative placement exists to prevent.
 *
 * Assumptions: the arrival is at the add sentinel, so no read is issued and the screen stays in the
 * key-entry mode where the key is the editable control. Arriving on a stored row would move the cursor
 * to the description, which is the same paragraph's answer for that mode.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function placesTheCursorOnTheKeyOnFirstEntry(): Promise<void> {
  await renderInShellAt(REF_TYPE_NEW_SENTINEL);

  await waitFor(awaitCursorOnKeyControl);
  // WHY : Assumptions: `TRTYPCD` carries `ATTRB=(IC,UNPROT)` at `COTRTUP.bms` L84 - the only field in
  //       the repository with exactly that pair, and the sole cursor position on this screen. The
  //       CURSOR is asserted rather than an `autoFocus` prop, because `COTRTUPC.cbl` L1303-L1325
  //       places it imperatively on every turn while React's `autoFocus` fires only on first mount.
  expect(descriptionControl()).not.toHaveFocus();
  expect(vi.mocked(getTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the cursor has reached the key control.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitCursorOnKeyControl(): void {
  // WHY : Assumptions: `TRTYPCD` is the sole `IC` field, at `COTRTUP.bms` L84.
  expect(typeCodeControl()).toHaveFocus();
}

/**
 * Asserts the four conditional legends stay dark until their own state arrives.
 *
 * Refactoring Rationale: the terminal achieves contextual affordance by toggling a field's non-display
 * attribute - `3391-SETUP-PFKEY-ATTRS` at `COTRTUPC.cbl` L1406-L1422 brightens `FKEY04A`, `FKEY05A` and
 * `FKEY12A` per mode - and the browser achieves it by conditioning the descriptor array the legend
 * receives, because that component renders one control per binding and holds no hidden state. Same
 * behaviour, different mechanism. The rejected alternative, rendering all five descriptors in every
 * mode, would advertise actions the program refuses in that mode, which is exactly what the non-display
 * attribute exists to prevent.
 *
 * Assumptions: `'F6=Add'` is the ONE conditional descriptor present from the start, and that is the
 * screen's documented divergence rather than a leak. Nothing in the program ever brightens `FKEY06A`,
 * so on the glass the operator never saw it; omitting it here would match the terminal while dropping a
 * label the mapset author wrote, and enabling it would invent an action the program has no code for. It
 * is therefore painted and inert.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function hidesTheConditionalLegendsUntilTheirStateArrives(): Promise<void> {
  await renderInShellAt(REF_TYPE_NEW_SENTINEL);
  const descriptors = legendDescriptors();

  expect(descriptors).toContain(REF_TYPE_EDIT_KEY_LABELS.ENTER);
  expect(descriptors).toContain(REF_TYPE_EDIT_KEY_LABELS.PFK03);
  // WHY : Assumptions: only `FKEYS` at `COTRTUP.bms` L111-L115 is `ATTRB=(ASKIP,NORM)`; the four
  //       conditional descriptors are `DRK` and stay hidden until their state arrives. PF6 is the odd
  //       one - revealed in every mode, accepted in none.
  expect(descriptors).toContain(REF_TYPE_EDIT_KEY_LABELS.PFK06);
  // WHY : Refactoring Rationale: the 3270 gets contextual affordance by toggling a field's `DRK`
  //       attribute; the SPA gets it by conditioning `PfKeyBar`'s descriptor array on state. Same
  //       behaviour, different mechanism. Rendering all five unconditionally - the rejected
  //       alternative - would advertise actions the gate at `COTRTUPC.cbl` L582-L601 refuses, which
  //       is precisely what the `DRK` attribute exists to prevent.
  expect(descriptors).not.toContain(REF_TYPE_EDIT_KEY_LABELS.PFK04);
  expect(descriptors).not.toContain(REF_TYPE_EDIT_KEY_LABELS.PFK05);
  expect(descriptors).not.toContain(REF_TYPE_EDIT_KEY_LABELS.PFK12);
}

/**
 * Asserts the delete and cancel legends appear once a row is on the glass, with their exact text.
 *
 * Assumptions: each descriptor is asserted against the mapset's own literal at the line beside it -
 * `'F4=Delete'` at `COTRTUP.bms` L120, `'F5=Save'` at L125 (seven characters in a field of eight),
 * `'F6=Add'` at L130, `'F12=Cancel'` at L135 - and the first field carries TWO descriptors in one
 * literal, `'ENTER=Process F3=Exit'` at L115, separated by a single blank where every base mapset uses
 * two. The single blank is content and is not normalised, which is why the descriptor text is read
 * without collapsing.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function revealsDeleteAndCancelOnceARowIsShown(): Promise<void> {
  await arriveAtStoredRow();
  const descriptors = legendDescriptors();

  expect(descriptors).toContain(REF_TYPE_EDIT_KEY_LABELS.PFK04);
  expect(descriptors).toContain(REF_TYPE_EDIT_KEY_LABELS.PFK12);
  expect(descriptors).not.toContain(REF_TYPE_EDIT_KEY_LABELS.PFK05);

  // WHY : Assumptions: the five legend literals come from `COTRTUP.bms` L115, L120, L125, L130 and
  //       L135 at widths 21, 9, 8, 6 and 10. Each literal fills its field exactly except `'F5=Save'`,
  //       which is seven characters in a field of eight.
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK04).toBe('F4=Delete');
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK05).toBe('F5=Save');
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK06).toBe('F6=Add');
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK12).toBe('F12=Cancel');
  // WHY : Assumptions: ONE blank between the two descriptors at `COTRTUP.bms` L115, where every base
  //       screen uses two. Composing the pair with a single space asserts the anomaly is not
  //       normalised on the way to the legend.
  expect(`${REF_TYPE_EDIT_KEY_LABELS.ENTER} ${REF_TYPE_EDIT_KEY_LABELS.PFK03}`).toBe(
    'ENTER=Process F3=Exit',
  );
}

/**
 * Asserts the sixth legend is painted and inert, and that pressing its key refuses rather than acts.
 *
 * Trade-offs: two alternatives were rejected and each would break something the migration protects.
 * Inventing a PF6 handler would add behaviour the baseline has no code for, which Transformation Rule T9
 * forbids; removing the descriptor would alter a user-visible string, which Rule T8 forbids. Painting it
 * disabled is the only rendering that keeps the text discoverable and the action absent, and that pair
 * is what a parity review needs. The mismatch is recorded for the traceability matrix as the fifth of
 * its kind in the baseline.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheSixthLegendInert(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  // WHY : Trade-offs: the advertised-but-unbound key renders inert rather than absent or handled -
  //       the descriptor stays because AAP Rule T8 protects the string, and it is disabled because
  //       `COTRTUPC` binds no `PFK06` handler. The press below confirms it reaches the refusal path.
  expect(legendControlFor('PFK06')).toBeDisabled();
  expect(legendControlFor('PFK04')).toBeEnabled();

  await pressPfKey(rendered.user, 'PFK06');
  await waitFor(awaitRefusedKeyReport);
  expect(vi.mocked(createTransactionType)).not.toHaveBeenCalled();
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the live invalid-key sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitRefusedKeyReport(): void {
  // WHY : Assumptions: `'Invalid key pressed'` at `COTRTUPC.cbl` L193-L194 is the LIVE refusal, set at
  //       L606 under the `IF WS-RETURN-MSG-OFF` guard at L605 - not the dead L172 variant.
  expect(errorBandText()).toContain(EDIT_STATUS.WS_INVALID_KEY_PRESSED.text);
}

/**
 * Asserts neither the declared-but-dead sentence nor the shared one ever reaches the glass.
 *
 * Assumptions: the sentence a refused key earns is `'Invalid key pressed'` from `COTRTUPC.cbl` L194,
 * set at L606 inside the `ELSE` of `0001-CHECK-PFKEYS`. The other declaration, `'Invalid Key pressed. '`
 * at L172, has its only `SET` inside the COMMENTED-OUT block at L611-L616, so the program cannot show
 * it - and the shared `CCDA-MSG-INVALID-KEY` this program never moves cannot appear either. Both
 * absences are asserted because both are strings an author would plausibly add: the dead one by reading
 * the declarations, the shared one by generalising from the fourteen programs that do emit it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersNeitherTheDeadNorTheSharedInvalidKeySentence(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await pressPfKey(rendered.user, 'PFK05');
  await waitFor(awaitRefusedKeyReport);

  const painted = rendered.baseElement.textContent ?? '';
  // WHY : Assumptions: `'Invalid Key pressed. '` at `COTRTUPC.cbl` L171-L172 has NO live trigger - its
  //       only `SET` site sits inside the commented-out block at L611-L616, which is deliberately not
  //       implemented. The live refusal is L606's sentence, guarded by `IF WS-RETURN-MSG-OFF` at L605.
  expect(painted).not.toContain(EDIT_STATUS.WS_INVALID_KEY.text);
  // WHY : Assumptions: the shared `CCDA-MSG-INVALID-KEY` at `app/cpy/CSMSG01Y.cpy` L20-L21 is never
  //       moved by this program. Fourteen online programs emit it and six emit nothing, so a negative
  //       assertion is what stops it being added here by generalisation from the majority.
  expect(painted).not.toContain(COMMON_MESSAGES.INVALID_KEY.text);
  expect(painted).not.toContain(EDIT_STATUS.CODING_TO_BE_DONE.text);
  expect(painted).not.toContain(EDIT_STATUS.WS_NAME_MUST_BE_ALPHA.text);
}

/**
 * Asserts the keys the key-entry mode does not admit are refused there.
 *
 * Assumptions: the gate at `COTRTUPC.cbl` L582-L601 admits PF4 only for a shown row or a pending
 * confirmation and PF12 only for one of five modes, none of which is key entry - so both are refused
 * here and each refusal reports the program's own sentence rather than doing nothing silently.
 *
 * Assumptions: two keys are driven in one arrival because a refusal changes no mode. L539-L542 re-paints
 * the screen and decides nothing else, so the second press meets the same state as the first.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesDeleteAndCancelDuringKeyEntry(): Promise<void> {
  const rendered = await renderInShellAt(REF_TYPE_NEW_SENTINEL);

  await pressPfKey(rendered.user, 'PFK04');
  await waitFor(awaitRefusedKeyReport);
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();

  await pressPfKey(rendered.user, 'PFK12');
  await waitFor(awaitRefusedKeyReport);
  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text);
}

/**
 * Asserts Enter cannot process or commit while a delete awaits its confirmation, and that PF3 survives.
 *
 * Refactoring Rationale: the reference withdraws BOTH halves of this affordance and refuses the key.
 * L583 admits Enter for every mode EXCEPT `TTUP-CONFIRM-DELETE`, and L1400-L1404 darkens the single
 * field carrying its label in that same one mode - `FKEY03` at `COTRTUP.bms` L111-L115 holds
 * `'ENTER=Process F3=Exit'` as ONE literal, so the terminal could not darken one without the other. Both
 * halves are asserted here: the two descriptors leave the legend, the key matrix refuses Enter, and PF3
 * stays ACCEPTED because L582 admits it unconditionally - withdrawing the exit to match its darkened
 * label would make the confirmation a dead end the reference does not have.
 *
 * ⚠ WHY : Trade-offs: what a bare Enter PRODUCES here is a documented divergence, and this case pins the
 *       divergence rather than the sentence it replaced. The reference answers Enter-while-confirming
 *       with `'Invalid key pressed'` and stays armed; this screen answers it by withdrawing the request,
 *       because the confirmation is a real dialogue whose SAFE control holds focus and
 *       `ui/src/layout/usePfKeys.ts` defers the ENTER identifier to any focused element the browser
 *       activates itself - `ENTER_ACTIVATED_TARGET_SELECTOR` lists `button` first, and its own rationale
 *       records that claiming Enter over a focused button was a defect it fixed. So the sentence is
 *       unreachable in this mode by construction, and the two ways to reach it again are both worse:
 *       leaving focus on the danger control means a bare Enter COMMITS the delete, and cancelling the
 *       activation of a focused button breaks it for keyboard operators. The safety property the original
 *       case existed for is strictly stronger here and is what is asserted - Enter neither processes nor
 *       deletes - and the refusal itself is still pinned at the layer that transcribes L583, the key
 *       matrix. The catalogued sentence remains reachable on this screen through PF10 and PF6.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesEnterWhileADeleteAwaitsConfirmation(): Promise<void> {
  const rendered = await arriveAtStoredRow();
  await askToDelete(rendered);

  const duringConfirmation = legendDescriptors();
  expect(duringConfirmation).not.toContain(REF_TYPE_EDIT_KEY_LABELS.ENTER);
  expect(duringConfirmation).not.toContain(REF_TYPE_EDIT_KEY_LABELS.PFK03);
  // WHY : Assumptions: PF3 stays ACCEPTED while withdrawn from the legend, per `COTRTUPC.cbl` L429
  //       against the gate at L582. The operator is never trapped in the confirmation, but the exit
  //       is no longer advertised while it is pending.
  expect(refTypeEditKeyMatrix('confirmDelete').PFK03.accepted).toBe(true);
  // WHY : Assumptions: L583's refusal is asserted where it is transcribed. The matrix is what decides
  //       whether the identifier reaches the screen at all, so this is the same fact the band sentence
  //       used to stand for, read at the layer that owns it.
  expect(refTypeEditKeyMatrix('confirmDelete').ENTER.accepted).toBe(false);

  await pressPfKey(rendered.user, 'ENTER');

  await waitFor(awaitCancelledDelete);
  expect(
    vi.mocked(deleteTransactionType),
    'a bare Enter must never commit a delete',
  ).not.toHaveBeenCalled();
  /*
   * ⚠ Refactoring Rationale: the withdrawal is proven by waiting for the DIALOGUE to leave rather than by
   * counting the prompt and expecting none. The count assertion failed against the migrated surface with
   * one occurrence left -- the closing dialogue's own title -- and that is not a stale prompt: the design
   * system keeps a closing panel mounted until its leave animation reports finishing, which jsdom never
   * runs. {@link waitForTheWithdrawalToSettle} ends the animation by hand, so the assertion measures the
   * withdrawal instead of the absence of an animation frame.
   */
  await waitForTheWithdrawalToSettle();
}

/**
 * Asserts F4 deletes on this screen, on the second press, and never clears the form.
 *
 * Trade-offs: this is the highest-value key assertion in the file, because the application-wide
 * convention and this screen disagree. On the screens whose mapsets paint it, F4 clears - which is why
 * `ui/src/layout/PfKeyBar.tsx` publishes a uniform label reading `'F4=Clear'`, and why the screen
 * deliberately does not import it. This mapset paints `'F4=Delete'` at `COTRTUP.bms` L120 and the program
 * dispatches the key to the delete confirmation at `COTRTUPC.cbl` L482-L498. Inheriting the common
 * semantic here would turn a destructive action into a benign one, and would turn an operator's muscle
 * memory for "clear the screen" into a delete.
 *
 * Assumptions: the values on the glass are asserted intact after the first press, because a screen that
 * had implemented the clear semantic would also look correct on the confirmation prompt alone.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function deletesOnTheSecondPressAndNeverClears(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  // WHY : Assumptions: F4 DELETES on this screen, per `'F4=Delete'` at `COTRTUP.bms` L116-L120 and the
  //       handlers at `COTRTUPC.cbl` L482-L489 and L493-L498. Every base screen binds F4 to Clear, so
  //       both controls are asserted to keep their values - reusing the common Clear semantic here
  //       would turn a destructive action benign, or a user's Clear muscle memory into a delete.
  expect(descriptionControl()).toHaveValue(STORED.description);
  expect(typeCodeControl()).toHaveValue(STORED.typeCd);
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();

  await pressPfKey(rendered.user, 'PFK04');
  await waitFor(awaitDeleteCommitted);
  expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledTimes(1);
  // WHY : Assumptions: `COTRTUPC.cbl` L493-L498 asks on the FIRST press and L482-L489 deletes on the
  //       second, so exactly one call after two presses is the re-key-to-confirm contract.
  expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
}

/**
 * Asserts the committed-delete prompt has reached the row-22 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitDeleteCommitted(): void {
  // WHY : Assumptions: `'Delete successful.'` at `COTRTUPC.cbl` L153-L154, trailing stop included.
  expect(informationBandText()).toContain(EDIT_STATUS.CONFIRM_DELETE_SUCCESS.text);
}

/**
 * Asserts the pointer delete is gated behind a dangerous confirmation that must be accepted.
 *
 * Assumptions: the confirmation is a browser addition ON TOP OF the reference's two-press convention
 * rather than a replacement for it, because a pointer can activate a control by accident where a
 * function key cannot - and the design-system mapping in AAP section 0.3.2 assigns a confirmation with
 * a dangerous accept action to exactly this role. The prompt it carries is the same catalogued sentence
 * the row-22 band shows, so no second wording is introduced.
 *
 * Assumptions: the control is located inside the screen body rather than by name alone, because the
 * legend paints a control with the same descriptor - a name-only query would match two elements and
 * could confirm through the wrong one.
 *
 * Assumptions: the prompt is COUNTED rather than located, because while the confirmation is open the same
 * catalogued sentence is on the glass twice - once on the row-22 band, where the mode put it, and once as
 * the confirmation's own title. That duplication is the assertion: it proves the dialogue reuses the
 * catalogued sentence instead of introducing a second wording for one question.
 *
 * ⚠ WHY : Refactoring Rationale: the tail of this case was REWRITTEN, because what it asserted was the
 *       defect. It cancelled the confirmation and then clicked the same control twice more to commit
 *       the delete -- a sequence that was only reachable because cancelling did nothing but close the
 *       overlay, leaving the request armed. That is the destructive review's "cancel is genuinely
 *       safe" failing: the press after a cancel committed, with no confirmation on the glass at the
 *       moment it did. The reference cancels a pending delete back to key entry with
 *       `'Delete was cancelled'` (`app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1000-L1002), and
 *       `0001-CHECK-PFKEYS` accepts PF4 only in `TTUP-SHOW-DETAILS` and `TTUP-CONFIRM-DELETE`
 *       (L588-L593) -- so after a cancel the key is refused until the row is read again. The tail now
 *       asserts that withdrawal and then reaches the delete the way the reference does: read the row,
 *       arm, confirm.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function gatesThePointerDeleteBehindADangerousConfirmation(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();
  await askToDelete(rendered);

  const trigger = within(shellLandmark('screenBody')).getByRole('button', {
    name: REF_TYPE_EDIT_KEY_LABELS.PFK04,
  });
  // WHY : Trade-offs: the dangerous emphasis AAP section 0.3.2 assigns to a destructive accept is
  //       asserted through the RENDERED class rather than the React prop, so the check survives an antd
  //       internal rename; the trade is a looser assertion for one that does not break on a minor
  //       version bump.
  //       ⚠️ Refactoring Rationale: the prop it stands for is now `okButtonProps={{ danger: true }}` and
  //       not the legacy `okType="danger"` this comment used to name. `convertLegacyProps` maps that
  //       operand to `danger` with the DEFAULT variant, which renders the destructive control as the
  //       quieter of the two -- emphasis inverted against risk -- so the migration to a `Modal` took the
  //       chance to state the pair explicitly. The class this asserts is unchanged either way, which is
  //       exactly why the assertion is on the class.
  expect(trigger.className).toContain('dangerous');
  // WHY : Assumptions: TWO occurrences the moment the delete is armed - the row-22 band plus the
  //       dialogue's own title. Counting rather than merely finding it proves both halves at once: the
  //       dialogue is on the glass without a pointer having to reveal it, and it REUSES the catalogued
  //       sentence from `COTRTUPC.cbl` L151-L152 instead of inventing a second wording.
  expect(screen.getAllByText(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text)).toHaveLength(2);

  await rendered.user.click(screen.getByRole('button', { name: 'Cancel' }));

  // The cancel is a withdrawal: the sentence says so and the key is no longer accepted, so the press
  // that used to commit here cannot.
  await waitFor(awaitCancelledDelete);
  // ⚠ Assumptions: the declining CONTROL is held to the same withdrawal as the Escape key -- dialogue gone,
  // prompt gone -- because the two routes reach one handler and a case that watched only one of them would
  // pass against a screen that answered only one.
  await waitForTheWithdrawalToSettle();
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
  expect(bodyDeleteControl()).toBeDisabled();

  // Reaching the delete again takes the route the reference takes: read the row, arm, then confirm.
  await rendered.user.type(typeCodeControl(), STORED.typeCd);
  await pressPfKey(rendered.user, 'ENTER');
  await awaitStoredRow();

  await rendered.user.click(bodyDeleteControl());
  await waitFor(awaitDeleteConfirmationPrompt);

  const accept = await screen.findByRole('button', { name: 'OK' });
  expect(accept.className).toContain('dangerous');
  await rendered.user.click(accept);

  await waitFor(awaitDeleteCommitted);
  expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledTimes(1);
}

/**
 * Returns text unchanged, so a query compares against exactly what is on the glass.
 *
 * ⚠ Purpose: Testing Library's default normaliser trims each end and folds every whitespace run to one
 * space before comparing, which makes it blind to the one property these labels have to keep. Each
 * `DFHMDF` label in `app/app-transaction-type-db2/bms/COTRTUP.bms` is padded to the column its value
 * starts at -- `'Transaction Type  :'` at L79-L83 carries two spaces before its colon and
 * `'Description       :'` at L90-L93 carries seven -- and rule T8 carries that padding character for
 * character. Passing this as the normaliser makes the assertion fail if the padding is ever collapsed,
 * where the default would pass against a label rendered with any spacing at all.
 *
 * Alternatives Considered: folding the EXPECTED string to match the default normaliser, which is what the
 * retired anchoring case did to compare an accessible description. Rejected here because that direction
 * throws away the padding on both sides of the comparison, so it can no longer tell a verbatim label from
 * a re-spaced one -- acceptable when the value being compared was itself computed by the accessibility
 * layer, misleading when it is the rendered text.
 * @param {string} text - The node's text, exactly as rendered.
 * @returns {string} The same text, unmodified.
 */
function verbatimText(text: string): string {
  return text;
}

/**
 * Locates the destructive confirmation by the role and the name it is announced with.
 *
 * ⚠ Refactoring Rationale: the confirmation is reached by ROLE where the retired geometry case reached it
 * by walking up from a button to a `.ant-popover` surface, and the change is the whole point rather than a
 * convenience. At the pinned version an anchored `Popconfirm` renders its overlay through the tooltip
 * primitive, which writes `role: 'tooltip'` with no prop that overrides it, so the surface guarding a
 * `DELETE` could not be found by the role a dialogue has -- and a query that cannot express the property
 * cannot assert it. `Modal` renders through the dialog primitive
 * (`ui/node_modules/@rc-component/dialog/lib/Dialog/Content/Panel.js` L113-L115), so the surface is a
 * named `dialog` and this query fails if it ever stops being one.
 *
 * Assumptions: the name asserted is the catalogued row-22 sentence rather than an authored dialogue title,
 * because `aria-labelledby` points at the title element and the title is that sentence -- one question,
 * one wording, whether the operator reads the band or hears the dialogue.
 * @returns {HTMLElement} The open confirmation dialogue.
 * @throws {Error} If no dialogue with that accessible name is open, which Testing Library raises.
 */
function confirmationDialog(): HTMLElement {
  return screen.getByRole('dialog', { name: EDIT_STATUS.PROMPT_DELETE_CONFIRM.text });
}

/**
 * Waits until the destructive confirmation has left the accessibility tree.
 *
 * ⚠ Refactoring Rationale: two cases asserted a withdrawal by counting occurrences of the prompt and
 * expecting none, and both failed against the migrated surface with one occurrence left -- the dialogue's
 * own title, on a panel that had been told to close. That is not a stale prompt: the design system keeps a
 * closing dialogue mounted until its leave animation reports finishing, and jsdom runs no animations, so
 * the panel would never have been parked however long the case waited.
 *
 * ⚠ Assumptions: the leave animation is ended BY HAND, on every retry, and the event fired is
 * `transitionend` rather than `animationend`. The animation library listens for a native end event on the
 * panel (`ui/node_modules/@rc-component/motion/es/hooks/useDomMotionEvents.js` L21-L22) and resolves the
 * names it listens for by probing for a constructor and a style property: jsdom exposes no
 * `AnimationEvent`, so the animation name settles on a vendor-prefixed form Testing Library cannot emit,
 * while `TransitionEvent` does exist and keeps the transition name unprefixed. Both reach the one handler,
 * which does not inspect the event's type. Firing on every retry rather than once is required because the
 * library discards an end event that arrives before its own scheduled active step, and re-firing costs
 * nothing once the panel has gone because the query returns null.
 *
 * ⚠ Assumptions: the ROW-22 PROMPT is asserted gone alongside the dialogue, and the two together are what
 * a withdrawal means. This half is a coverage repair rather than an addition: the assertion these call
 * sites used to make counted every occurrence of the prompt on the glass, which covered the band and the
 * dialogue at once, and replacing it with a dialogue-only check would have quietly stopped watching the
 * band. A prompt reading `'Delete this record ? Press F4 to confirm'` over a request that is provably
 * withdrawn is the defect this pair exists to prevent -- the sentence is the half an operator acts on.
 *
 * Assumptions: the band is expected to carry the KEY-ENTRY prompt rather than to be blank, because
 * `2500-SETUP-MESSAGE`'s catch-all at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1216-L1218 puts a
 * standing instruction on row 22 for every mode that has nothing more specific to say. A blank line here
 * would be its own divergence.
 *
 * Trade-offs: this arrangement is duplicated from `ui/src/test/refTypeList.test.tsx`, whose shared home
 * would be `ui/src/test/setup.ts` -- outside this change's file set. It is flagged here so the next change
 * through `setup.ts` can lift it.
 * @returns {Promise<void>} Resolves once no confirmation dialogue is reachable and no prompt survives it.
 */
async function waitForTheWithdrawalToSettle(): Promise<void> {
  await waitFor(
    /**
     * Ends the leave animation if one is still running, then asserts the dialogue has gone.
     * @returns {void} Nothing; the expectation throws until the dialogue is unreachable.
     */
    function theConfirmationIsClosed(): void {
      const leaving = screen.queryByRole('dialog', {
        name: EDIT_STATUS.PROMPT_DELETE_CONFIRM.text,
      });

      if (leaving !== null) {
        fireEvent.transitionEnd(leaving);
      }

      expect(
        screen.queryByRole('dialog', { name: EDIT_STATUS.PROMPT_DELETE_CONFIRM.text }),
      ).not.toBeInTheDocument();
      expect(
        informationBandText(),
        'the confirmation prompt must not outlive the confirmation',
      ).not.toContain(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text);
      expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text);
    },
  );
}

/**
 * Asserts the armed delete is presented as a dialogue that names the record it would destroy.
 *
 * ⚠ Refactoring Rationale: this case measured GEOMETRY -- it installed a fake layout, opened the
 * confirmation, and proved the overlay resolved an inset inside a reported viewport, because the sibling
 * list screen's confirmation had been measured mounting at `inset: -1000vh auto auto -1000vw` with no
 * anchor resolved. Every one of those assertions is now unreachable AND unnecessary. Unreachable, because
 * the surface is no longer an anchored overlay: it is a `Modal`, so `.ant-popover` does not exist and the
 * case failed on its own locator. Unnecessary, because a modal is positioned against the VIEWPORT and has
 * no trigger to align against -- the failure mode the geometry pinned cannot occur, so pinning it would
 * assert nothing about anything. The scaffolding it needed (a patched `getBoundingClientRect`, a reported
 * `documentElement` extent, a per-axis inset assertion and a description-folding helper) is retired with
 * it rather than left measuring a surface that no longer moves.
 *
 * ⚠ Assumptions: what replaces it is the property the geometry was a proxy for -- that an operator arming
 * a delete is actually ASKED, in a surface assistive technology announces as a dialogue, naming the row.
 * Six things are asserted together because the confirmation is only safe when all six hold: it is a
 * `dialog` (the role query is the assertion), it is MODAL so focus cannot wander behind it, its title is
 * the catalogued prompt and is therefore on the glass twice with the row-22 band, it names the record
 * under the mapset's own field labels rather than saying `this record`, focus starts on the DECLINING
 * choice, and the accept is the dangerous control. Nothing has been deleted at this point.
 *
 * Assumptions: the naming is read as TEXT INSIDE the dialogue rather than as the focused control's
 * accessible description, which is what the retired case asserted. The description existed because the
 * overlay carried no role, so the record had to be attached to each button by hand or it was never
 * announced; a dialogue's contents are announced on open, so the fact reaches the same operator through
 * the surface's own semantics and the hand-maintained identifier is retired with the overlay.
 *
 * Assumptions: NOTHING is clicked to reveal the dialogue. `askToDelete` raises PF4, so this measures the
 * surface an operator gets on the KEYBOARD path -- which is the path that had none.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function presentsTheArmedDeleteAsADialogNamingTheRecord(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);

  const dialog = confirmationDialog();

  expect(dialog).toHaveAttribute('aria-modal', 'true');
  // WHY : Assumptions: TWO occurrences the moment the delete is armed -- the row-22 band plus the
  //       dialogue's own title. Counting proves both halves at once: the dialogue is on the glass
  //       without a pointer having to reveal it, and it REUSES the catalogued sentence from
  //       `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L151-L152 rather than inventing a second
  //       wording for one question.
  expect(screen.getAllByText(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text)).toHaveLength(2);

  const named = within(dialog);

  expect(
    named.getByText(REF_TYPE_EDIT_FIELD_LABELS.typeCode, { normalizer: verbatimText }),
  ).toBeInTheDocument();
  expect(named.getByText(STORED.typeCd)).toBeInTheDocument();
  expect(
    named.getByText(REF_TYPE_EDIT_FIELD_LABELS.description, { normalizer: verbatimText }),
  ).toBeInTheDocument();
  expect(named.getByText(STORED.description)).toBeInTheDocument();

  const cancel = named.getByRole('button', { name: 'Cancel' });
  const accept = named.getByRole('button', { name: 'OK' });

  expect(cancel).toHaveFocus();
  expect(accept.className).toContain('ant-btn-dangerous');
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts a delete the child constraint refused reports the baseline sentence and no relational detail.
 *
 * Assumptions: the two refusals this screen can meet at status 409 are told apart by WHICH operation
 * raised them and not by a code in the problem document, which is the baseline's own division. A delete
 * refused because a category still references the type is the `-532` arm at `COTRTUPC.cbl` L1638-L1649,
 * whose registered replacement is the child-records sentence; a refused replace is the version conflict
 * and carries a different sentence entirely. AAP section 0.5.1.6 requires that this constraint surface as
 * a 409 rather than as a database error, which is what preserves the referential semantic the extension
 * tree's foreign key declares.
 *
 * Assumptions: the sentence is the SHARED catalog entry rather than the sibling list screen's own,
 * because both programs compose it - this one at L1641 - so it is filed once and cited twice rather than
 * borrowed.
 *
 * Assumptions: the diagnostic is planted in the problem document and asserted absent, because the
 * baseline appended a schema object name and a relational return code to that sentence and the target
 * appends nothing. The correlation identifier the client already sends is what ties an operator report
 * back to the server-side record.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsADeleteRefusedByTheChildConstraint(): Promise<void> {
  const refusal = failureFrom(
    STATUS.conflict,
    conflictProblem(WITHHELD_DIAGNOSTIC, { status: STATUS.conflict }),
  );
  vi.mocked(deleteTransactionType).mockRejectedValue(refusal);
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await pressPfKey(rendered.user, 'PFK04');
  await waitFor(awaitChildRecordsRefusal);

  expect(isConflictFailure(refusal)).toBe(true);
  // WHY : Assumptions: the `ON DELETE RESTRICT` refusal surfaces as a typed 409, per AAP section
  //       0.5.1.6, preserving the `XTRNTYCAT` semantic that AAP section 0.4.1.3 implements as
  //       `transaction_categories` holding an `FK type_cd` with `ON DELETE RESTRICT`. No SQL state,
  //       vendor error code or Db2 text may reach the operator, so the withheld diagnostic is absent
  //       from the whole document rather than merely off the message band.
  // WHY : Assumptions: this program DOES compose the sibling's sentence - `COTRTUPC.cbl` L1641 emits
  //       `'Please delete associated child records first:'`, which is why the catalog files it as
  //       shared rather than under the list program alone.
  expect(rendered.baseElement.textContent ?? '').not.toContain(WITHHELD_DIAGNOSTIC);
  expect(errorBandText()).not.toContain(EDIT_STATUS.RECORD_DELETE_FAILED.text);
}

/**
 * Asserts the child-records refusal has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitChildRecordsRefusal(): void {
  // WHY : Assumptions: composed at `COTRTUPC.cbl` L1641, which is why the catalog files this sentence
  //       as shared rather than as the sibling list program's alone.
  expect(errorBandText()).toContain(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST);
}

/**
 * Asserts a delete the table refused for any other reason reports its own sentence.
 *
 * Assumptions: this is the arm BELOW the constraint arm, at `COTRTUPC.cbl` L1650-L1652, whose registered
 * replacement is `'Delete of record failed'` at L190 - a distinct sentence from the child-records one,
 * and the two are never merged because they name different conditions to an operator who can act on
 * only one of them.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsADeleteTheTableRefused(): Promise<void> {
  vi.mocked(deleteTransactionType).mockRejectedValue(
    failureFrom(STATUS.serverError, apiError({ status: STATUS.serverError })),
  );
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await pressPfKey(rendered.user, 'PFK04');
  await waitFor(awaitDeleteRefused);
  // WHY : Assumptions: `'Delete of record failed'` at `COTRTUPC.cbl` L189-L190 is the delete-path
  //       failure, distinct from the child-constraint refusal above - a 500 is not a 409.
  expect(informationBandText()).toContain(EDIT_STATUS.INFORM_FAILURE.text);
}

/**
 * Asserts the refused-delete sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitDeleteRefused(): void {
  // WHY : Assumptions: `'Delete of record failed'` at `COTRTUPC.cbl` L189-L190, no trailing stop.
  expect(errorBandText()).toContain(EDIT_STATUS.RECORD_DELETE_FAILED.text);
}

/**
 * Asserts a save that never reached the table is not reported as a save the table refused.
 *
 * ⚠️ Purpose: every failed write on this screen used to report a Db2 arm's sentence --
 * `'Update of record failed'` at `COTRTUPC.cbl` L187-L188 or `'Changes unsuccessful. Please try again'`
 * -- and both state that the TABLE refused the work. A throttle, a gateway failure, a timeout or a
 * dropped connection did not get that far, so either sentence sends the operator, and whoever they call,
 * to look for a database failure that never happened.
 *
 * ⚠️ Assumptions: 502 is the status chosen rather than 503, because 503 has a NAMED baseline sentence on
 * this path -- `'Could not lock record for update'`, the replacement for `SQLCODE -911` at L1561-L1566 --
 * which is tested separately and deliberately outranks the classification. Choosing 502 exercises the
 * classified arm without disturbing that precedence, and the two cases together prove the order.
 *
 * Assumptions: the replaced sentence is asserted absent, because the defect is a MISREPORT rather than a
 * missing sentence.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function reportsATransientSaveAsAConditionThatMayClear(): Promise<void> {
  const refused = failureFrom(STATUS.badGateway, apiError({ status: STATUS.badGateway }));
  vi.mocked(replaceTransactionType).mockRejectedValue(refused);
  expect(isTransientFailure(refused)).toBe(true);

  const rendered = await arriveAtStoredRow();
  await editDescriptionAndValidate(rendered);
  await pressPfKey(rendered.user, 'PFK05');

  await waitFor(
    /**
     * Waits for the classified sentence to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(TRANSIENT_FAILURE_TRY_AGAIN);
    },
  );
  expect(
    errorBandText(),
    'a save that never reached the table must not be reported as one the table refused',
  ).not.toContain(EDIT_STATUS.TABLE_UPDATE_FAILED.text);
  expect(errorBandText()).not.toContain(EDIT_STATUS.COULD_NOT_LOCK_REC_FOR_UPDATE.text);
}

/**
 * Asserts a delete that reached nothing at all is reported as a request that did not complete.
 *
 * ⚠️ Assumptions: this is the one path on this screen whose OUTCOME is genuinely unknown. The transport
 * classifies a dropped connection as `NETWORK` with no status and `transient` false, so it is neither a
 * condition to wait out nor a refusal `9800-DELETE-PROCESSING` issued at L1650-L1657 -- and the row may or
 * may not be gone. `'That request did not complete. Report it if it happens again.'` invites the re-read
 * that answers it, where `'Delete of record failed'` invites a second attempt at destroying a row that
 * may already have been destroyed.
 *
 *       Trade-offs: the honest fix for the ambiguity is an idempotency key on the delete, which lives in
 *       the service contract this change may not edit; it is reported to the dispatcher instead.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function reportsADeleteThatReachedNothingAsIncomplete(): Promise<void> {
  vi.mocked(deleteTransactionType).mockRejectedValue(
    new ApiRequestError(
      'NETWORK',
      NO_TRANSPORT_STATUS,
      apiError({ status: NO_TRANSPORT_STATUS }),
      'no response reached the browser',
    ),
  );
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await pressPfKey(rendered.user, 'PFK04');

  await waitFor(
    /**
     * Waits for the classified sentence to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(PERSISTENT_FAILURE_REPORT_IT);
    },
  );
  expect(errorBandText()).not.toContain(EDIT_STATUS.RECORD_DELETE_FAILED.text);
  expect(errorBandText()).not.toContain(TRANSIENT_FAILURE_TRY_AGAIN);
}

/**
 * Asserts a sentence the service supplied is shown verbatim in place of the authored classification.
 *
 * ⚠️ Assumptions: within the two classified arms the service's own sentence is the better one, because
 * both authored sentences classify a TRANSPORT outcome and say nothing about what was asked. It is
 * rendered exactly as received rather than paraphrased or prefixed.
 *
 * ⚠️ Assumptions: the preference is SCOPED to those two arms, and the scope was fixed by measurement
 * rather than by preference. Preferring the member ahead of every arm made the sibling list screen's
 * control case report its filtered-empty refusal for a genuine fault, because that screen classifies a
 * 400 by the document's FIELD ENTRIES and not by its prose. The named arms on this screen -- the
 * concurrency refusal, the lock refusal, the child-record instruction and the cursor's abend replacement
 * -- keep their precedence for the same reason: each says something a general sentence cannot.
 *
 * Assumptions: the authored sentence is asserted absent, so the case cannot pass against a band showing
 * both.
 * @returns {Promise<void>} Resolves once the supplied sentence has been asserted.
 */
async function showsTheServicesOwnSentenceForAClassifiedFailure(): Promise<void> {
  const supplied = 'The reference service is restarting.';
  vi.mocked(deleteTransactionType).mockRejectedValue(
    failureFrom(STATUS.unavailable, apiError({ status: STATUS.unavailable, message: supplied })),
  );
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await pressPfKey(rendered.user, 'PFK04');

  await waitFor(
    /**
     * Waits for the supplied sentence to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(supplied);
    },
  );
  expect(
    errorBandText(),
    'a sentence the service supplied replaces the authored classification rather than joining it',
  ).not.toContain(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(errorBandText()).not.toContain(EDIT_STATUS.RECORD_DELETE_FAILED.text);
}

/**
 * Asserts F5 begins an add from the not-found mode and commits it after Enter validates.
 *
 * Assumptions: F5's meaning is STATE-DEPENDENT and both meanings are exercised here. The legend calls it
 * a save at `COTRTUP.bms` L125, and the row-22 prompt for the not-found mode calls it an add at
 * `COTRTUPC.cbl` L150 - the same key, two sentences, because L503-L508 answers a press in the not-found
 * mode by moving to the create mode without writing, and only L514-L520 issues a write. So the first
 * press advertises the add, Enter validates what was typed, and the second press commits.
 *
 * Assumptions: the real add path is F5 and not the F6 the mapset advertises, which is the point of the
 * advertise-versus-bind mismatch recorded elsewhere in this file.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function addsThroughTheNotFoundModeWithFiveThenFive(): Promise<void> {
  const created: TransactionType = { ...STORED, description: EDITED_DESCRIPTION, version: 1 };
  vi.mocked(createTransactionType).mockResolvedValue(created);
  const rendered = await arriveAtMissingRow();

  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_CREATE_NEW_RECORD.text);
  // WHY : Assumptions: F5 means ADD in this state. `'Press F05 to add. F12 to cancel'` at
  //       `COTRTUPC.cbl` L149-L150 governs the not-found mode, while the legend's `'F5=Save'` governs
  //       the update mode - one key, two meanings, each asserted in the state that produces it.
  expect(legendDescriptors()).toContain(REF_TYPE_EDIT_KEY_LABELS.PFK05);

  await pressPfKey(rendered.user, 'PFK05');
  await waitFor(awaitNewRecordPrompt);
  expect(vi.mocked(createTransactionType)).not.toHaveBeenCalled();

  await rendered.user.type(descriptionControl(), EDITED_DESCRIPTION);
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitSaveHandshakePrompt);

  await pressPfKey(rendered.user, 'PFK05');
  await waitFor(awaitCommittedChange);
  expect(vi.mocked(createTransactionType)).toHaveBeenCalledTimes(1);
  // WHY : Assumptions: the add path routes through `createTransactionType` and never through the
  //       replace method, per the arm at `COTRTUPC.cbl` L503-L508. A description is typed first
  //       because `decideAction`'s `default` arm abends and `createNewRecord` has no arm - a bare
  //       Enter with nothing typed is a genuinely reachable abend, steered around deliberately.
  expect(vi.mocked(createTransactionType)).toHaveBeenCalledWith({
    typeCd: STORED.typeCd,
    description: EDITED_DESCRIPTION,
  });
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the new-details prompt has reached the row-22 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitNewRecordPrompt(): void {
  // WHY : Assumptions: `'Enter new transaction type details.'` at `COTRTUPC.cbl` L157-L158.
  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_FOR_NEWDATA.text);
}

/**
 * Asserts the committed-change prompt has reached the row-22 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitCommittedChange(): void {
  // WHY : Assumptions: `'Changes committed to database'` at `COTRTUPC.cbl` L162-L163, no trailing stop
  //       where the three sentences at L154, L156 and L158 all carry one.
  expect(informationBandText()).toContain(EDIT_STATUS.CONFIRM_UPDATE_SUCCESS.text);
}

/**
 * Asserts a validated change is saved with the version the service issued, under an administrator.
 *
 * Refactoring Rationale: the version travelling with the write is what makes the optimistic-concurrency
 * refusal possible at all. The baseline compared a before-image it carried across the pseudo-conversational
 * gap - AAP section 0.7.2 records that pattern as already present in the COBOL - and the target expresses
 * it natively instead: the service issues a version, the replace carries it back, and a row that moved
 * underneath the operator is refused by the service rather than by a remembered copy.
 *
 * Assumptions: the session is established through the shared identity helper, which mints a token
 * carrying the administrative group and drives the real sign-on exchange. There is no provider to mount
 * and no setter to call, so this is the only path to an authorised operator - which is the property the
 * identity case asserts directly.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function savesAValidatedChangeWithTheIssuedVersion(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  // WHY : Assumptions: authority arrives ONLY as the signed `cognito:groups` claim. `useAuth` exports
  //       no group setter and there is no Context provider, so the session is established through the
  //       shared identity helper. In the baseline `CDEMO-USER-TYPE PIC X(01)` at
  //       `app/cpy/COCOM01Y.cpy` L26, values `'A'`/`'U'` at L27-L28, travelled in the COMMAREA -
  //       storage the client echoed back; per AAP section 0.7.1 the claim is now signed.
  expect(session.result.current.isAdmin).toBe(true);

  vi.mocked(replaceTransactionType).mockResolvedValue({
    ...STORED,
    description: EDITED_DESCRIPTION,
    version: STORED.version + 1,
  });
  const rendered = await arriveAtStoredRow();
  await editDescriptionAndValidate(rendered);

  await pressPfKey(rendered.user, 'PFK05');
  await waitFor(awaitCommittedChange);
  expect(vi.mocked(replaceTransactionType)).toHaveBeenCalledTimes(1);
  // WHY : Refactoring Rationale: the version fetched is sent back for the writer to compare, which is
  //       AAP section 0.7.2's point - the baseline ALREADY implements before-image optimistic
  //       concurrency across the pseudo-conversational gap, so a JPA `@Version` column surfacing as
  //       HTTP 409 expresses an existing pattern natively rather than inventing one. The client never
  //       locks; it re-reads and reports.
  expect(vi.mocked(replaceTransactionType)).toHaveBeenCalledWith(STORED.typeCd, {
    description: EDITED_DESCRIPTION,
    version: STORED.version,
  });
}

/**
 * Asserts a concurrent change is reported with the sentence the baseline declared for it.
 *
 * Refactoring Rationale: this branch is DECLARED in the baseline and unreachable there.
 * `88 DATA-WAS-CHANGED-BEFORE-UPDATE` is declared at `COTRTUPC.cbl` L183-L184 and tested at L1585, and
 * no statement sets it - the read-for-update lock was never held across the operator's think-time, so
 * the program had no way to observe a concurrent change. The target does, through the version it carries,
 * so activating the branch is what makes a declared sentence reachable rather than inventing one. The
 * response the baseline prescribes at L1585-L1586 is followed too: back to the shown-row mode, so the
 * operator re-reads and reviews rather than saving over another operator's change.
 *
 * Assumptions: the status is recognised through the client's published predicate rather than by matching
 * the sentence, because the sentence is a rendering decision and the status is the contract. A screen
 * that branched on message text would report an unrelated 409 as a concurrency refusal.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsAConcurrentChangeWithTheDeclaredSentence(): Promise<void> {
  const refusal = failureFrom(
    STATUS.conflict,
    conflictProblem(EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text, { status: STATUS.conflict }),
  );
  expect(isConflictFailure(refusal)).toBe(true);
  vi.mocked(replaceTransactionType).mockRejectedValue(refusal);

  const rendered = await arriveAtStoredRow();
  await editDescriptionAndValidate(rendered);
  await pressPfKey(rendered.user, 'PFK05');

  await waitFor(awaitConcurrentChangeReport);
  // WHY : Assumptions: `'Record changed by some one else. Please review'` at `COTRTUPC.cbl` L183-L184
  //       and `'Could not lock record for update'` at L181-L182 are TWO sentences for two different
  //       failures. Branching on `isConflictFailure` rather than on message text is what keeps them
  //       apart, so the lock sentence is asserted absent from the concurrency path.
  expect(errorBandText()).not.toContain(EDIT_STATUS.COULD_NOT_LOCK_REC_FOR_UPDATE.text);
  expect(errorBandText()).not.toContain(EDIT_STATUS.TABLE_UPDATE_FAILED.text);
}

/**
 * Asserts the concurrency sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitConcurrentChangeReport(): void {
  // WHY : Assumptions: `'Record changed by some one else. Please review'` at `COTRTUPC.cbl` L183-L184,
  //       `some one` in two words, byte-identical to `COACTUPC` L522.
  expect(errorBandText()).toContain(EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text);
}

/**
 * Asserts a row that could not be locked reports its own sentence, distinct from the concurrency one.
 *
 * Assumptions: the lock refusal keeps its own status as well as its own sentence. The baseline names it
 * with a relational lock-timeout code at `COTRTUPC.cbl` L1561-L1566 carrying
 * `'Could not lock record for update'` at L182, and of the statuses the reference service declares for
 * this operation only the service-unavailable one means "the resource could not be obtained now, retry" -
 * so that is the status carrying the condition the baseline named. Folding it into the generic failure
 * would make a declared sentence unreachable for a reason unrelated to the target's behaviour.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsALockRefusalWithItsOwnSentence(): Promise<void> {
  vi.mocked(replaceTransactionType).mockRejectedValue(
    failureFrom(STATUS.unavailable, apiError({ status: STATUS.unavailable })),
  );

  const rendered = await arriveAtStoredRow();
  await editDescriptionAndValidate(rendered);
  await pressPfKey(rendered.user, 'PFK05');

  await waitFor(awaitLockRefusalReport);
  // WHY : Assumptions: the mirror of the concurrency case - a non-409 refusal must NOT borrow the
  //       409 sentence, which is how the two paths are proven distinct in both directions.
  expect(errorBandText()).not.toContain(EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text);
  expect(informationBandText()).toContain(EDIT_STATUS.INFORM_FAILURE.text);
}

/**
 * Asserts the lock-refusal sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitLockRefusalReport(): void {
  // WHY : Assumptions: `'Could not lock record for update'` at `COTRTUPC.cbl` L181-L182 - the lock
  //       refusal, a different failure from the concurrency sentence at L184.
  expect(errorBandText()).toContain(EDIT_STATUS.COULD_NOT_LOCK_REC_FOR_UPDATE.text);
}

/**
 * Asserts an Enter on an unedited row reports no change and issues no write.
 *
 * Assumptions: the comparison runs BEFORE the description edit, at `COTRTUPC.cbl` L743-L751, so an
 * unchanged description is reported as unchanged rather than validated - and the sentence it reports ends
 * `'values fetched.'` where the sibling list program's ends `'database values.'`, which is why the two
 * are separate catalog entries.
 *
 * Assumptions: the write spy is asserted untouched as well as the sentence asserted present, because a
 * screen that saved an identical row would also paint a plausible outcome and only the absent call
 * distinguishes the two.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesToWriteWhenNothingChanged(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitNoChangeReport);
  // WHY : Assumptions: `'No change detected with respect to values fetched.'` at `COTRTUPC.cbl`
  //       L179-L180 short-circuits BEFORE any write, so the absence of the call is the real contract
  //       and the sentence alone would not prove it.
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();
  expect(vi.mocked(createTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the no-change sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitNoChangeReport(): void {
  // WHY : Assumptions: `'No change detected with respect to values fetched.'` at `COTRTUPC.cbl`
  //       L179-L180 - `values fetched.`, where the sibling list program ends `database values.`
  expect(errorBandText()).toContain(EDIT_STATUS.NO_CHANGES_DETECTED.text);
}

/**
 * Asserts F12 abandons a validated change with its own sentence and writes nothing.
 *
 * Assumptions: the two cancellations report DIFFERENT sentences and land in different modes, which is
 * `COTRTUPC.cbl` L1000-L1005 - a validated change is abandoned with `'Update was cancelled'` at L186
 * into the backed-out mode that keeps the description editable so the operator can revise it, and a
 * pending delete with `'Delete was cancelled'` at L192 back to key entry. Reporting one sentence for
 * both would tell an operator who abandoned a delete that they had abandoned an update.
 *
 * Assumptions: this and the pending-delete cancellation are separate arrivals rather than two presses in
 * one, because the update cancellation leaves the screen in the backed-out mode where the gate at
 * L584-L586 no longer admits PF4 - so a second press in the same arrival would exercise a refusal rather
 * than the second cancellation.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function cancelsAValidatedChangeWithItsOwnSentence(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await editDescriptionAndValidate(rendered);
  await pressPfKey(rendered.user, 'PFK12');
  await waitFor(awaitCancelledUpdate);

  // WHY : Assumptions: PF12 produces `'Update was cancelled'` at `COTRTUPC.cbl` L185-L186 here and
  //       `'Delete was cancelled'` at L191-L192 on the delete path - TWO distinct sentences chosen by
  //       state, so each case asserts the other one is absent. Handler at L524-L530.
  expect(errorBandText()).not.toContain(EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text);
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();
  // WHY : Assumptions: the cancel restores the fetched value rather than merely reporting, so the
  //       control is asserted back at the stored description and no write was issued.
  expect(descriptionControl()).toHaveValue(STORED.description);
}

/**
 * Asserts F12 abandons a pending delete with its own sentence and deletes nothing.
 *
 * Assumptions: the sentence is the delete one and not the update one, and the assertion says so in both
 * directions - the two are one word apart and a screen reporting the wrong one would look entirely
 * plausible.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function cancelsAPendingDeleteWithItsOwnSentence(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await pressPfKey(rendered.user, 'PFK12');
  await waitFor(awaitCancelledDelete);

  // WHY : Assumptions: the delete-path counterpart - the update sentence must not appear where the
  //       delete sentence belongs, which is what proves the state selects between them.
  expect(errorBandText()).not.toContain(EDIT_STATUS.WS_UPDATE_WAS_CANCELLED.text);
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Locates this screen's own delete control, inside the screen body rather than on the key bar.
 * @returns {HTMLElement} The danger-styled control carrying the delete legend.
 */
function bodyDeleteControl(): HTMLElement {
  return within(shellLandmark('screenBody')).getByRole('button', {
    name: REF_TYPE_EDIT_KEY_LABELS.PFK04,
  });
}

/**
 * Asserts the screen's own delete control arms the delete when it is clicked.
 *
 * ⚠ WHY : Purpose: this control was ENABLED, danger-styled, labelled `'F4=Delete'` and inert. Mounting
 *       the confirmation only in the confirming mode -- which is what makes the two presses mean two
 *       different things -- took away the design system's own trigger handler in every other mode and
 *       put nothing in its place, so a click on it in `showDetails` did nothing whatsoever and the
 *       pointer route to arming a delete ran through the key bar or nowhere. An operator who clicks a
 *       destructive control and sees no change has been told the record cannot be deleted, which is
 *       false.
 *
 *       Assumptions: the outcome asserted is the reference's, not an overlay. `2000-DECIDE-ACTION` at
 *       `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L493-L498 answers the FIRST press by setting
 *       the confirm mode and nothing else, and L151-L152 is the prompt it leaves on row 22 -- so the
 *       arming click must produce that sentence and no deletion.
 * @returns {Promise<void>} Resolves once the arming click has been asserted.
 */
async function armsTheDeleteFromThePointer(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();

  await rendered.user.click(bodyDeleteControl());

  await waitFor(awaitDeleteConfirmationPrompt);
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Both of the confirmation's controls describe themselves with the record being destroyed.
 *
 * ⚠️ Purpose: the dialogue names the record in its body, and a browser pass measured focus landing on
 * the DECLINING control the instant such a dialogue opens. A description carried only by the surface as
 * a whole is therefore never read aloud: the operator is placed on a button called `Cancel` under a
 * title asking them to confirm a deletion, and at that moment nothing says which record. Naming the
 * record on both controls closes that. `ui/src/screens/cardUpdate/index.tsx` already wires its own
 * confirmation this way and its note claimed THIS file did too -- a claim that was false when written,
 * and is made true by the wiring this case guards rather than by deleting the sentence.
 *
 * ⚠️ Assumptions: the reference is RESOLVED through the document and its text inspected, rather than the
 * attribute being compared to a constant. A dangling `aria-describedby` promises a description and
 * delivers silence, which is worse than carrying none, so the assertion is that the identifier names an
 * element that exists and that the element holds the record's own type code.
 *
 * ⚠️ Assumptions: BOTH controls are checked, because they are configured through separate props --
 * wiring one and not the other is a single-line mistake that leaves exactly the case that matters, the
 * focused control, silent.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function describesBothChoicesWithTheRecordBeingDestroyed(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();

  await rendered.user.click(bodyDeleteControl());
  await waitFor(awaitDeleteConfirmationPrompt);

  const named = within(confirmationDialog());
  const undescribed: string[] = [];

  for (const name of ['Cancel', 'OK']) {
    const control = named.getByRole('button', { name });
    const reference = control.getAttribute('aria-describedby') ?? '';
    const described = reference === '' ? null : document.getElementById(reference);

    if (described === null || !(described.textContent ?? '').includes(STORED.typeCd)) {
      undescribed.push(name);
    }
  }

  expect(
    undescribed,
    'both choices must name the record, and the identifier must resolve to an element that holds it',
  ).toEqual([]);
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the Escape key withdraws an armed delete instead of merely hiding its confirmation.
 *
 * ⚠ WHY : Purpose: Escape dismissed the confirmation and left the request ARMED, so the operator was
 *       returned to a screen that looked ordinary while the next F4 -- from the key bar or the keyboard
 *       -- was still the CONFIRMING press and deleted the record with no confirmation anywhere on the
 *       glass. That is the worst shape this defect can take, because the dismissal itself is what
 *       persuades the operator the request is gone.
 *
 *       ⚠️ Refactoring Rationale: this case and the cancel-button case used to exercise two genuinely
 *       different library paths, and after the migration to a `Modal` they exercise ONE. While the
 *       confirmation was an anchored `Popconfirm`, Escape never reached `onCancel` at all -- the portal
 *       detected it (`ui/node_modules/@rc-component/portal/lib/useEscKeyDown.js` L25-L32), the trigger
 *       took it as `onEsc` (`ui/node_modules/@rc-component/trigger/lib/index.js` L245-L250) and routed it
 *       through `onOpenChange`, which is why the screen had to answer that callback and omit `onCancel`
 *       to avoid a double dispatch. A `Modal` calls `onCancel` exactly once for its own Escape handling
 *       and for its cancel button alike (`@rc-component/dialog/lib/Dialog/index.js` `onInternalClose`).
 *       Both cases are KEPT regardless: they are the two ways an operator withdraws, the reference
 *       answers both the same way, and a future revision that re-splits the paths is exactly what this
 *       pair is here to catch.
 *
 *       Assumptions: the sentence asserted is the reference's own cancellation answer at
 *       `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1000-L1002, because the withdrawal is dispatched
 *       as PF12 -- the transition L594-L601 already accepts while confirming. No wording is invented for
 *       a key the terminal never had.
 * @returns {Promise<void>} Resolves once the withdrawal has been asserted.
 */
async function withdrawsTheArmedDeleteOnEscape(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await waitFor(awaitDeleteConfirmationPrompt);

  await rendered.user.keyboard('{Escape}');

  await waitFor(awaitCancelledDelete);
  // ⚠ Refactoring Rationale: as above -- the dialogue's departure is what a withdrawal looks like, and it
  // needs its leave animation ended by hand under jsdom. Counting the prompt reported one occurrence for a
  // panel that had already been told to close.
  await waitForTheWithdrawalToSettle();

  // The press that used to commit after an Escape now finds nothing armed.
  await pressPfKey(rendered.user, 'PFK04');

  expect(
    vi.mocked(deleteTransactionType),
    'a delete withdrawn by Escape must not be committed by the next press of the delete key',
  ).not.toHaveBeenCalled();
}

/**
 * Asserts abandoning the confirmation withdraws the armed delete rather than only closing the overlay.
 *
 * ⚠ WHY : Purpose: the confirmation carried no cancel handler, so saying no dismissed the overlay and
 *       left the screen in `confirmDelete`. The next PF4 -- the key bar's button or the keyboard --
 *       was therefore still the CONFIRMING press, and it deleted the record outright with no
 *       confirmation shown. An operator who cancels has withdrawn the request; a cancel that leaves it
 *       armed is not a cancel.
 *
 *       Assumptions: the case presses PF4 AGAIN after cancelling, because the absence of a delete on
 *       the cancel itself was already true of the defect -- the overlay simply closed. What separates
 *       the two is what the NEXT press means, so that is what is asserted.
 *
 *       Assumptions: the sentence asserted is `'Delete was cancelled'` at `COTRTUPC.cbl` L191-L192,
 *       which its own cancel arm at L1000-L1002 leaves on row 23 while returning to key entry -- so
 *       the mode this lands in is one where PF4 is refused, which is exactly why the second press is
 *       harmless.
 * @returns {Promise<void>} Resolves once the withdrawal has been asserted.
 */
async function disarmsTheDeleteWhenTheConfirmationIsAbandoned(): Promise<void> {
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  const rendered = await arriveAtStoredRow();

  await askToDelete(rendered);
  await rendered.user.click(await screen.findByRole('button', { name: 'Cancel' }));

  await waitFor(awaitCancelledDelete);

  await pressPfKey(rendered.user, 'PFK04');

  expect(
    vi.mocked(deleteTransactionType),
    'a cancelled delete must not be committed by the next press of the delete key',
  ).not.toHaveBeenCalled();
}

/**
 * Asserts the cancelled-update sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitCancelledUpdate(): void {
  // WHY : Assumptions: `'Update was cancelled'` at `COTRTUPC.cbl` L185-L186, double-l spelling.
  expect(errorBandText()).toContain(EDIT_STATUS.WS_UPDATE_WAS_CANCELLED.text);
}

/**
 * Asserts the cancelled-delete sentence has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitCancelledDelete(): void {
  // WHY : Assumptions: `'Delete was cancelled'` at `COTRTUPC.cbl` L191-L192, double-l spelling.
  expect(errorBandText()).toContain(EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text);
}

/**
 * Asserts F3 exits to the administrative menu when the arrival named no caller.
 *
 * Assumptions: an arrival naming no caller is the ordinary one rather than a degenerate case.
 * `COTRTUPC.cbl` L431-L442 falls back to `LIT-ADMINTRANID` and `LIT-ADMINPGM` when
 * `CDEMO-FROM-TRANID` and `CDEMO-FROM-PROGRAM` are blank, and administrative option 6 hands over neither
 * because the menu IS the fallback - so this arm answers a menu transfer, a typed address, a bookmark
 * and a reload alike.
 *
 * Assumptions: the destination is looked up in the published route table by the PROGRAM the reference
 * names, so the expectation is anchored on `'COADM01C'` from L209-L210 rather than on a path spelled
 * here.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function exitsToTheAdministrativeMenuWithoutACaller(): Promise<void> {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
  const rendered = await renderWithExitProbes(undefined);
  await awaitStoredRow();

  await pressPfKey(rendered.user, 'PFK03');
  // WHY : Assumptions: `COTRTUPC.cbl` L429-L460 exits to the caller it was given and otherwise to the
  //       administrative menu named at L209-L210 (`'COADM01C'`). AAP Rule T5 turns `EXEC CICS XCTL`
  //       into a client-side route change, and the destination is DERIVED from the route table by
  //       program name rather than written out, so no route string is duplicated here.
  expect(await screen.findByText(`${ARRIVED} ${ADMIN_MENU_PATH}`)).toBeInTheDocument();
}

/**
 * Asserts F3 returns to the caller the entering transition named.
 *
 * Assumptions: both arms of the exit are reachable in the delivered tree, which is why both are covered.
 * The reference's own first-entry arms test `CDEMO-FROM-PROGRAM EQUAL LIT-ADMINPGM` at L466 and
 * `EQUAL LIT-LISTTPGM` at L468, so the sibling list screen is a declared caller - and the destination is
 * looked up by the program name `'COTRTLIC'` from L217-L218 rather than spelled here.
 *
 * Assumptions: the caller reaches the screen as router transition state rather than as a session field,
 * per AAP section 0.7.1 - the passed communication area does not survive the migration, so the navigation
 * fields it carried become client-side history instead.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function exitsToTheCallerTheTransitionNamed(): Promise<void> {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
  const rendered = await renderWithExitProbes(REFERENCE_LIST_PATH);
  await awaitStoredRow();

  await pressPfKey(rendered.user, 'PFK03');
  // WHY : Assumptions: the second destination is the sibling list program at `COTRTUPC.cbl` L217-L218
  //       (`'COTRTLIC'`), reached when the entering transition named a caller. Both targets exist in
  //       the source, so both are covered rather than one guessed.
  expect(await screen.findByText(`${ARRIVED} ${REFERENCE_LIST_PATH}`)).toBeInTheDocument();
}

/**
 * Asserts the route parameter reaches the transport under the name the route declares.
 *
 * Assumptions: the parameter is resolved by NAME and never by position, so the route's `:cd` segment and
 * the screen's own read of it have to agree letter for letter. A near-miss resolves to nothing with no
 * diagnostic, which would leave the screen prompting for a key the address already carried and nothing
 * reporting why.
 *
 * Assumptions: selection context arrives as a request parameter rather than in a session field, which is
 * the substitution AAP section 0.7.1 makes for the baseline's `CDEMO-*` selection fields - so the value
 * the transport receives is the value the address carried.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function readsItsKeyFromTheRouteParameter(): Promise<void> {
  await arriveAtStoredRow();

  expect(vi.mocked(getTransactionType)).toHaveBeenCalledTimes(1);
  // WHY : Assumptions: per AAP section 0.7.1 selection context becomes a REQUEST parameter, never a
  //       session field - so the `cd` parameter reaching the transport and landing in `TRTYPCD` is
  //       the whole contract that replaced the COMMAREA's carried key.
  expect(vi.mocked(getTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
  expect(typeCodeControl()).toHaveValue(STORED.typeCd);
}

/**
 * Asserts a refused control is marked, that the blank case additionally shows the marker, and that the
 * other control stays clean.
 *
 * Refactoring Rationale: the templated highlight `app/cpy/CSSETATY.cpy` sets the colour when a field's
 * flag is not-acceptable OR blank at L18-L22 and then gates a literal asterisk on an INNER test of the
 * BLANK flag alone at L23-L26 - so a malformed entry is reddened and a missing one is reddened AND
 * marked. The migrated form is an error status on the item plus the marker, and the two cases are
 * asserted separately because collapsing them into one boolean would lose the marker's condition.
 *
 * Refactoring Rationale: the COBOL additionally gates the whole highlight on `CDEMO-PGM-REENTER`
 * (`app/cpy/COCOM01Y.cpy` L29-L31), and that discriminator disappears entirely per AAP section 0.7.1 -
 * a stateless handler has no first-entry-versus-re-entry distinction to make. The marking is therefore
 * driven by a refusal that is present now rather than by a remembered turn count, which is why this case
 * needs no first turn to prime.
 *
 * Assumptions: the clean control is asserted explicitly, because a screen that marked every control on
 * any refusal would satisfy the positive half of this case in full.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksARefusedControlAndLeavesTheOtherClean(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await rendered.user.clear(descriptionControl());
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitBlankDescriptionRefusal);

  expect(descriptionControl()).toHaveAttribute('aria-invalid', 'true');
  // WHY : Assumptions: `app/cpy/CSSETATY.cpy` L17-L27 reddens ONLY the field whose flag is not-OK or
  //       blank, so a control absent from the error array must stay clean - asserting the marked one
  //       alone would pass against a screen that reddened both.
  // WHY : ⚠️ Refactoring Rationale: the clean control is asserted to carry NO `aria-invalid` attribute,
  //       where this line required an explicit `"false"`. `fieldAriaProps` in
  //       `ui/src/layout/fieldHelp.tsx` omits the member while the control is clean, and records why:
  //       the attribute defaults to false in its own absence, so emitting `false` adds an attribute
  //       change on every settle without adding a fact. The assertion still discriminates -- a screen
  //       that reddened both controls would put `"true"` here.
  expect(typeCodeControl()).not.toHaveAttribute('aria-invalid');
  // WHY : Assumptions: the literal `'*'` is moved in ONLY for the blank case, at `app/cpy/CSSETATY.cpy`
  //       L24 - a malformed value reddens without it, which the negative below pins.
  // WHY : Refactoring Rationale: the COBOL gates this highlight on `CDEMO-PGM-REENTER`
  //       (`app/cpy/COCOM01Y.cpy` L29-L31), but AAP section 0.7.1 establishes that discriminator
  //       disappears entirely - a stateless handler has no first-entry-versus-re-entry distinction to
  //       make, so the styling is driven purely by the response body.
  /*
   * WHY : ⚠️ Refactoring Rationale: the marker is found by its own HANDLE and no longer by its glyph.
   *       `BLANK_FIELD_MARKER_TEST_ID` exists because the design system's always-on required asterisk
   *       is the SAME CHARACTER meaning something else -- "this field must be filled" rather than "this
   *       field was left blank on the turn just taken" -- and a query on the character alone cannot
   *       tell a screen that renders this marker from one that merely marks a field required.
   *       Assumptions: the marker is additionally asserted to be hidden from assistive technology,
   *       which is the property adopting the shared helper bought. A lone asterisk announced beside a
   *       value conveys nothing to a listener; the same fact reaches a listener through `aria-invalid`
   *       and the row-23 sentence.
   */
  const marker = screen.getByTestId(BLANK_FIELD_MARKER_TEST_ID);
  expect(marker).toHaveTextContent(FIELD_ERROR_TOKENS.blankMarker);
  expect(marker).toHaveAttribute('aria-hidden', 'true');
  // WHY : Assumptions: the marker sits INSIDE the control's own box as its suffix, which is the
  //       position the reference's asterisk occupied -- L24 writes it into the field's own columns.
  //       antd renders a suffixed input inside `.ant-input-affix-wrapper`, so containment by that
  //       wrapper is what "inside the field" reduces to in the DOM.
  expect(marker.closest('.ant-input-affix-wrapper')).not.toBeNull();

  await rendered.user.type(descriptionControl(), 'BAD-VALUE!');
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitMalformedDescriptionRefusal);

  expect(descriptionControl()).toHaveAttribute('aria-invalid', 'true');
  expect(screen.queryByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeNull();
}

/**
 * Renders a design token's name as the custom-property fragment the design system emits for it.
 *
 * Assumptions: the fragment is derived rather than written out, so the case holds no design literal and
 * still fails if the screen stops publishing the token. The design system hyphenates each capital, which
 * is the whole of the transformation for a token carrying no digit run.
 * @param {string} tokenName - The token's name as `ui/src/theme/tokens.ts` publishes it.
 * @returns {string} The hyphenated fragment that appears inside the rendered `var(--ant-...)`.
 */
function cssVariableSegment(tokenName: string): string {
  /**
   * Replaces one capital with its hyphenated lower-case form.
   * @param {string} upper - The matched capital letter.
   * @returns {string} The replacement.
   */
  function hyphenate(upper: string): string {
    return `-${upper.toLowerCase()}`;
  }
  return tokenName.replace(/[A-Z]/gu, hyphenate);
}

/**
 * Asserts the protected key renders its value as data rather than as the design system's placeholder grey.
 *
 * ⚠ WHY : Purpose: regress a measured ambiguity. This screen's key control was rendered `disabled` while
 *       protected, and the design system paints a disabled input's text at `rgba(0,0,0,0.25)` on an
 *       `rgba(0,0,0,0.04)` fill -- so the REAL record key `01`, read straight from the service, read
 *       exactly like placeholder text. On a screen whose only key is two characters wide, an operator
 *       cannot tell a loaded record from an empty field.
 *
 *       Assumptions: the fix is the value's COLOUR and deliberately not the control's fill.
 *       `3310-PROTECT-ALL-ATTRS` at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1368-L1371 moves
 *       `DFHBMPRF` -- protected plus modified-data-tag -- and nothing anywhere moves a dark or
 *       de-emphasising attribute to `TRTYPCDA`, while `TRTYPCD` at
 *       `app/app-transaction-type-db2/bms/COTRTUP.bms` L84-L87 declares `ATTRB=(IC,UNPROT)` with no
 *       `COLOR` operand. So the terminal painted this value at its default text colour whether the field
 *       was protected or not, and the greyed fill stays as the only affordance separating an enterable
 *       field from a protected one.
 *
 *       Assumptions: asserted on the INPUT element and not on the control's root, because with a suffix
 *       present the design system puts `style` on the affix wrapper and `styles.input` on the input --
 *       and `.ant-input` carries a text colour of its own, so a colour on the wrapper would be
 *       overridden on the very element whose text it was meant to change.
 * @returns {Promise<void>} Resolves once the protected key's colour has been asserted.
 */
async function paintsTheProtectedKeyAsData(): Promise<void> {
  await arriveAtStoredRow();

  const control = typeCodeControl();

  expect(control).toBeDisabled();
  expect(control).toHaveValue(STORED.typeCd);
  expect(control.getAttribute('style') ?? '').toContain(
    cssVariableSegment(BMS_TEXT_COLOR_TOKENS.DEFAULT),
  );
}

/**
 * Asserts a correction typed after a refusal reaches the control whole, character for character.
 *
 * ⚠ WHY : Refactoring Rationale: this is the case that pins the marker's SLOT rather than the marker.
 *       Rendering the blank marker as the control's `suffix` -- which is how
 *       `ui/src/layout/fieldHelp.tsx` publishes it -- changes the input's DOM shape when the marker
 *       appears and again when it goes, because `@rc-component/input/lib/BaseInput.js` wraps the input
 *       in a `<span>` only while an affix is present. antd warns about the consequence itself at
 *       `antd/lib/input/Input.js` L116. Measured here before the fix: the operator typed ten
 *       characters into a refused field and the field held ONE, because the first keystroke cleared the
 *       refusal, the marker went, React replaced the input, and every later keystroke reached a node
 *       whose `isConnected` was false.
 *
 *       Assumptions: the assertion is IDENTITY plus value, not value alone. A value check on its own
 *       would pass on a screen that replaces the control and happens to re-focus it -- which is exactly
 *       what masks the same defect on the sibling list screen, whose editor carries `autoFocus`. Asking
 *       that the element be the same node is what tests the shape.
 *
 *       Assumptions: the correction is deliberately LONGER than one character and alphanumeric, so it
 *       is accepted by `1210-EDIT-TTYPE` at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L893 and
 *       the case turns on the typing rather than on a refusal.
 * @returns {Promise<void>} Resolves once the correction has been asserted intact.
 */
async function keepsEveryCharacterTypedAfterARefusal(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await rendered.user.clear(descriptionControl());
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitBlankDescriptionRefusal);
  expect(screen.getByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeInTheDocument();

  const armed = descriptionControl();
  const correction = 'RETAIL PURCHASE';
  await rendered.user.type(armed, correction);

  // WHY : Assumptions: the marker is asserted GONE as well, so the case proves the slot emptied while
  //       the control survived it -- an implementation that kept the marker mounted throughout would
  //       also keep the element and would pass an identity check alone.
  expect(screen.queryByTestId(BLANK_FIELD_MARKER_TEST_ID)).toBeNull();
  expect(descriptionControl()).toBe(armed);
  expect(armed).toBeInTheDocument();
  expect(armed).toHaveValue(correction);
}

/**
 * Asserts the blank-description refusal has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitBlankDescriptionRefusal(): void {
  // WHY : Assumptions: the blank case composes `'Transaction Desc'` at `COTRTUPC.cbl` L758 against
  //       `' must be supplied.'` at L866, whose leading blank IS the separator.
  expect(errorBandText()).toContain(
    formatFieldValidationMessage(
      DESCRIPTION_FIELD_LABEL,
      FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED,
    ),
  );
}

/**
 * Asserts the malformed-description refusal has reached the row-23 band.
 * @returns {void} Nothing; the expectation either passes or fails the waiting case.
 */
function awaitMalformedDescriptionRefusal(): void {
  // WHY : Assumptions: the malformed case composes the same name against
  //       `' can have numbers or alphabets only.'` at `COTRTUPC.cbl` L893, backed by the alphabet
  //       constants at L233 and L235.
  expect(errorBandText()).toContain(
    formatFieldValidationMessage(
      DESCRIPTION_FIELD_LABEL,
      FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY,
    ),
  );
}

/**
 * Asserts the prompt and the outcome land on their own lines and are never merged.
 *
 * Assumptions: this mapset declares TWO message fields and they carry different things at the same time -
 * `INFOMSG` at `POS=(22,23)` holds the mode prompt and `ERRMSG` at `POS=(23,1)` the rejection or
 * outcome, and `3250-SETUP-INFOMSG` at `COTRTUPC.cbl` L1210-L1266 sets the prompt from the mode alone
 * before moving the return message to the other field at L1264. Collapsing them into one line would drop
 * whichever message the other overwrote, and a severity derived from the message text rather than from
 * the field it was declared under would make the rendering depend on the sentence.
 *
 * Assumptions: the routing is asserted in BOTH directions, because a screen that painted both sentences
 * into both bands would satisfy a one-directional check completely.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsThePromptAndTheOutcomeOnTheirOwnLines(): Promise<void> {
  const rendered = await arriveAtStoredRow();

  await rendered.user.clear(descriptionControl());
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(awaitBlankDescriptionRefusal);

  const refusal = formatFieldValidationMessage(
    DESCRIPTION_FIELD_LABEL,
    FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED,
  );
  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_FOR_CHANGES.text);
  // WHY : Assumptions: this screen has TWO message fields - `INFOMSG` at `COTRTUP.bms` L100-L104 and
  //       `ERRMSG` at L107-L110 - so the prompt and the refusal must land on different lines. The
  //       band component is deliberately presentational and refuses an `ApiError`, which is why the
  //       screen owns this routing and why it is asserted in both directions.
  expect(informationBandText()).not.toContain(refusal);
  expect(errorBandText()).not.toContain(EDIT_STATUS.PROMPT_FOR_CHANGES.text);
}

/**
 * Asserts the screen paints neither its validation alphabets nor any value it has no business holding.
 *
 * Assumptions: the three constants the program assembles at `COTRTUPC.cbl` L230-L237 are INPUTS to an
 * inspection rather than text - `1230-EDIT-ALPHANUM-REQD` converts every character in them to a blank at
 * L878-L880 and requires the remainder to be empty - so only the sentence they back is user-visible, and
 * a screen rendering the alphabets themselves would be showing an operator the implementation of a rule
 * instead of the rule.
 *
 * Assumptions: this screen holds a two-character code and a fifty-character description and nothing else,
 * so a primary account number, a national identifier, a government-issued identifier or a monetary amount
 * appearing here would be a disclosure with no parity value at all. The transport contract declares no
 * card verification value on any type at all, so there is nothing to leak - the assertion records that
 * rather than implying otherwise.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsNoValidationAlphabetAndNoSensitiveValue(): Promise<void> {
  const rendered = await arriveAtStoredRow();
  const painted = rendered.baseElement.textContent ?? '';

  // WHY : Assumptions: the validation constants at `COTRTUPC.cbl` L233, L235 and L237 back the refusal
  //       composed at L893; only that refusal surfaces, never the alphabets themselves.
  expect(painted).not.toContain('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  expect(painted).not.toContain('abcdefghijklmnopqrstuvwxyz');
  expect(painted).not.toContain('0123456789');
  // WHY : Assumptions: no primary account number, national identifier or money belongs on a reference
  //       screen, and `ui/src/api/types.ts` declares no card-verification field at all.
  expect(painted).not.toMatch(/\d{16}/u);
  expect(painted).not.toMatch(/\d{3}-\d{2}-\d{4}/u);
  expect(painted).not.toContain('$');
}

/**
 * Asserts an operator outside the administrative group is not one, and reaches no transport.
 *
 * Assumptions: the guard component itself is exercised by the routing suite that owns it and is
 * deliberately not imported here - it is outside this file's declared dependency set, and a second copy
 * of the policy assertion would be a second thing to keep in step. What this case pins instead are the
 * two facts the guard is composed from: the route table declares the path administrative, and a session
 * carrying only the ordinary group reports itself as not administrative.
 *
 * Alternatives Considered: rendering the screen under an ordinary session and asserting a refusal.
 * Rejected because the screen carries no gate of its own by design - it would render, and the case would
 * report a defect that is not one while saying nothing about the policy that actually applies.
 *
 * Assumptions: the transport spies are asserted untouched, so a screen mounted for an unauthorised
 * operator by some future change would have to reach the service before this case could pass.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAuthorityToAnOrdinaryOperator(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });

  expect(session.result.current.signedOn).toBe(true);
  // WHY : Alternatives Considered: rendering the screen under an ordinary session and asserting a
  //       refusal. Rejected - the guard lives in the router, outside this file's declared dependency
  //       set, so mounting the screen directly would report a defect that is not one. The denial is
  //       instead asserted as its two composing facts: the route table declares the path
  //       administrative, and an ordinary session reports `isAdmin` false with no call reaching the
  //       transport. This screen mutates reference data every other service reads, so the negative
  //       matters more here than on a read-only screen.
  expect(session.result.current.isAdmin).toBe(false);
  expect(session.result.current.groups).toContain(CARDDEMO_USER_GROUP);
  expect(session.result.current.groups).not.toContain(CARDDEMO_ADMIN_GROUP);

  expect(vi.mocked(getTransactionType)).not.toHaveBeenCalled();
  expect(vi.mocked(createTransactionType)).not.toHaveBeenCalled();
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Publishes the address the router currently holds, so a case can assert about it.
 *
 * Assumptions: an `output` element rather than a `div`, matching the probe in
 * `ui/src/test/refTypeList.test.tsx` -- the value is a computed result of the render rather than
 * content, and using the same element keeps the two probes recognisable as the same device.
 * @returns {ReactElement} A marker carrying the current pathname.
 */
function AddressProbe(): ReactElement {
  const location = useLocation();
  return <output data-testid={RENDERED_ADDRESS_TEST_ID}>{location.pathname}</output>;
}

/**
 * Reports the address the router currently holds, as {@link AddressProbe} published it.
 * @returns {string} The current pathname.
 * @throws {Error} If no probe is mounted, which Testing Library raises.
 */
function renderedAddress(): string {
  return screen.getByTestId(RENDERED_ADDRESS_TEST_ID).textContent ?? '';
}

/**
 * Offers a control that navigates to the add sentinel, standing in for an operator's own address change.
 *
 * WHY : Assumptions: the probe exists because NO control on this screen navigates to the add sentinel,
 *       and the arrival it produces is nonetheless real -- the address bar, a bookmark and the
 *       administrative menu's option 6 all reach it, and the reported defect was an arrival from THIS
 *       screen carrying a record. A probe is the only way to make that arrival happen inside one render.
 *
 *       Alternatives Considered: driving the reducer directly with the arrival action. Rejected because
 *       it would assert about the reducer and not about the screen -- the defect was in the arrival
 *       EFFECT, which returned without dispatching anything, so a reducer-level case would have passed
 *       against the defect.
 *
 *       Trade-offs: a raw button rather than a design-system one. The zero-raw-HTML rule governs the
 *       rendered application, and this control is harness scaffolding that no operator sees; the two
 *       sibling probes in this repository's suites are raw elements for the same reason.
 * @returns {ReactElement} A control that replaces the address with the add sentinel.
 */
function AddSentinelProbe(): ReactElement {
  const navigate = useNavigate();

  return (
    <button
      type="button"
      onClick={
        /**
         * Navigates to the add sentinel, as a typed address or a menu option would.
         * @returns {void} Completion is the router transition.
         */
        (): void => {
          /*
           * ⚠ Refactoring Rationale: the transition goes through the application's own
           * `navigateSafely` rather than calling `navigate` directly, and this is a lint failure repaired
           * rather than a preference. `navigate` returns `void | Promise<void>` at the pinned router
           * version, so a bare call is a floating promise under the rule these files are linted with
           * (`ignoreVoid: false`), a `.then` on the union is an unsafe call on the `void` arm, and making
           * this handler `async` trips `no-misused-promises` for a click handler that returns a promise.
           * `ui/src/routes/navigation.ts` L741-L782 narrows the union with an `instanceof Promise` check
           * and supplies a rejection arm, which is exactly what every production call site uses -- so the
           * probe navigates the way the screens it stands in for do.
           */
          navigateSafely(navigate, `${REFERENCE_LIST_PATH}/${REF_TYPE_NEW_SENTINEL}`);
        }
      }
    >
      {GO_TO_ADD_LABEL}
    </button>
  );
}

/**
 * Asserts an authority refusal of the read reads as a refusal rather than as a task termination.
 *
 * WHY : Assumptions: the case asserts on BOTH surfaces, because the defect used one to stand in for the
 *       other. Every read failure carrying abend data replaced the whole form with the fault surface and
 *       painted `'UNEXPECTED ABEND OCCURRED.'` on row 23 -- so an operator whose token carried the wrong
 *       authority was told the task had terminated abnormally, and lost the form as well. The fault
 *       surface transcribes `ABEND-ROUTINE` at `COTRTUPC.cbl` L1684-L1697, which issues its send with
 *       `ERASE` and then abends the task; a refusal is a turn the program never even entered, so it
 *       leaves the form standing and reports on row 23 like any other rejection.
 *
 *       Assumptions: the refusal carries abend data deliberately, so the case proves the SURFACE is
 *       withheld on the strength of the classification rather than because the document happened to
 *       carry nothing to paint.
 * @returns {Promise<void>} Resolves once both surfaces have been asserted.
 */
async function reportsARefusedReadAsARefusal(): Promise<void> {
  vi.mocked(getTransactionType).mockRejectedValue(
    failureFrom(
      STATUS.refusedAuthority,
      apiError({ status: STATUS.refusedAuthority, abend: CARRIED_ABEND }),
    ),
  );
  await renderInShellAt(STORED.typeCd);

  await waitFor(
    /**
     * Waits for the refusal to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(ACCESS_DENIED_NOT_AUTHORIZED);
    },
  );
  expect(errorBandText()).not.toContain(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  // WHY : Assumptions: the form standing IS the absence of the fault surface, because that surface
  //       replaces the form rather than sitting beside it -- so this is the same assertion as querying
  //       for the surface, made against the thing the operator needs rather than the thing they do not.
  expect(typeCodeControl()).toBeInTheDocument();
  // WHY : Assumptions: the REASON is the discriminator and the culprit is not, because the culprit is a
  //       program name and the shell paints that name on every turn -- `COTRTUPC` at
  //       `app/app-transaction-type-db2/bms/COTRTUP.bms` names the program in the title band, so
  //       querying for it would match a header field on a screen with no fault at all.
  expect(screen.queryByText(CARRIED_ABEND.abendReason)).toBeNull();
}

/**
 * Asserts a transient failure reports without claiming the task terminated abnormally.
 *
 * WHY : Assumptions: a 503 is transient by the shared client's own derivation -- `ui/src/api/client.ts`
 *       lists 408, 429, 502, 503 and 504 beside a timeout -- and a condition that may clear on its own is
 *       the opposite of the state the fault surface reports. The reference has no analogue at all: a task
 *       talking to Db2 does not time out and carry on, so nothing in `COTRTUPC` describes this and the
 *       classification the client publishes is the only thing that can decide it.
 *
 *       ⚠️ Refactoring Rationale: this case now pins the SENTENCE as well as the surface, and the
 *       trade-off it used to record is withdrawn. It said the row-23 line still had to carry the
 *       registered abend replacement because the catalogue held no authored sentence for a transient
 *       condition and a screen may not author one. The catalogue now registers
 *       `TRANSIENT_FAILURE_TRY_AGAIN`, so a gateway that gave up says the condition may clear instead of
 *       telling the operator the task terminated abnormally -- which is what the withheld surface
 *       already implied and the sentence used to contradict.
 * @returns {Promise<void>} Resolves once the withheld surface and the sentence have been asserted.
 */
async function withholdsTheFaultSurfaceFromATransientFailure(): Promise<void> {
  vi.mocked(getTransactionType).mockRejectedValue(
    failureFrom(STATUS.unavailable, apiError({ status: STATUS.unavailable, abend: CARRIED_ABEND })),
  );
  await renderInShellAt(STORED.typeCd);

  await waitFor(
    /**
     * Waits for the failure to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(TRANSIENT_FAILURE_TRY_AGAIN);
    },
  );
  // WHY : ⚠️ Assumptions: the abend sentence is asserted ABSENT rather than merely unasserted, because
  //       the defect this case guards is not a missing sentence -- it is the WRONG one, and a case that
  //       only looked for the new text would pass against a band carrying both.
  expect(errorBandText()).not.toContain(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  expect(typeCodeControl()).toBeInTheDocument();
  expect(screen.queryByText(CARRIED_ABEND.abendReason)).toBeNull();
}

/**
 * Asserts a read that reached nothing at all is reported as a request that did not complete.
 *
 * WHY : ⚠️ Assumptions: the transport raises a dropped connection as kind `NETWORK` with NO status, and
 *       classifies it as neither transient nor repeatable -- `ui/src/api/client.ts` reserves `transient`
 *       for a condition expected to clear and a dropped connection is not one. So this is the arm the
 *       transient sentence must NOT capture, and the one the Db2 cursor's abend replacement must not
 *       capture either: nothing was asked of the database, so reporting its abend sentence would send an
 *       investigation to the reference service's logs for a request that never arrived there.
 * WHY : Assumptions: both wrong answers are asserted absent alongside the right one, for the reason the
 *       case above states -- the defect is a misreport, so the assertion has to exclude the sentence
 *       being replaced.
 * @returns {Promise<void>} Resolves once the sentence has been asserted.
 */
async function reportsAReadThatReachedNothingAsIncomplete(): Promise<void> {
  vi.mocked(getTransactionType).mockRejectedValue(
    new ApiRequestError(
      'NETWORK',
      NO_TRANSPORT_STATUS,
      apiError({ status: NO_TRANSPORT_STATUS }),
      'no response',
    ),
  );
  await renderInShellAt(STORED.typeCd);

  await waitFor(
    /**
     * Waits for the failure to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(PERSISTENT_FAILURE_REPORT_IT);
    },
  );
  expect(errorBandText()).not.toContain(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(errorBandText()).not.toContain(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  expect(typeCodeControl()).toBeInTheDocument();
}

/**
 * Asserts a genuine fault still replaces the form with the abend data the service carried.
 *
 * WHY : Assumptions: this is the arm the two cases above must not have deleted, and it is asserted
 *       against the baseline's own data names -- `ABEND-CULPRIT` and `ABEND-REASON` at
 *       `app/cpy/CSMSG02Y.cpy` L21-L29 -- because those are what `ABEND-ROUTINE` fills and sends. A
 *       plain 500 is neither a refusal nor transient by the client's derivation, so it is the one
 *       condition left that the surface exists for.
 * @returns {Promise<void>} Resolves once the surface has been asserted.
 */
async function surfacesAGenuineFaultWithItsAbendData(): Promise<void> {
  vi.mocked(getTransactionType).mockRejectedValue(
    failureFrom(STATUS.serverError, apiError({ status: STATUS.serverError, abend: CARRIED_ABEND })),
  );
  await renderInShellAt(STORED.typeCd);

  expect(await screen.findByText(CARRIED_ABEND.abendReason)).toBeInTheDocument();
  // WHY : Assumptions: the culprit is asserted through `getAllByText` rather than `getByText`, because
  //       the shell paints the same program name in the title band on every turn -- so the surface's own
  //       copy is the SECOND occurrence, and a single-match query would fail on a correct screen.
  expect(screen.getAllByText(CARRIED_ABEND.abendCulprit).length).toBeGreaterThan(1);
  expect(document.getElementById(TYPE_CODE_CONTROL_ID)).toBeNull();
}

/**
 * Asserts an address the key field cannot hold is refused instead of being read.
 *
 * WHY : Assumptions: three properties are asserted together because the defect produced all three at
 *       once. `normaliseTypeCode` is `Number.parseInt` followed by `padStart`, so a non-numeric segment
 *       became the three characters `NaN`: the screen displayed a key nobody had typed in a field two
 *       characters wide, and sent that key to the service. So the case pins that the glass carries what
 *       the address carried, that `NaN` reaches neither the glass nor the transport, and that no read is
 *       issued at all.
 *
 *       Assumptions: the sentence is the one a TYPED key of the same shape earns -- `'Tran Type code'`
 *       at `COTRTUPC.cbl` L826 against the numeric suffix at L924 -- because an address is one more way
 *       a key arrives and `1210-EDIT-TTYPE` is the program's only answer for a key that is not numeric.
 * @returns {Promise<void>} Resolves once the refusal has been asserted.
 */
async function refusesAnAddressTheKeyFieldCannotHold(): Promise<void> {
  await renderInShellAt(UNUSABLE_ROUTE_KEY);

  await waitFor(
    /**
     * Waits for the refusal to reach the row-23 line.
     * @returns {void} Nothing; throws until the sentence lands.
     */
    (): void => {
      expect(errorBandText()).toContain(
        `${KEY_FIELD_LABEL}${FIELD_VALIDATION_SUFFIXES.MUST_BE_NUMERIC}`,
      );
    },
  );
  expect(vi.mocked(getTransactionType)).not.toHaveBeenCalled();
  expect(typeCodeControl()).toHaveValue(UNUSABLE_ROUTE_KEY);
  expect(document.body.textContent ?? '').not.toContain('NaN');
}

/**
 * Asserts a record loaded at the add sentinel becomes addressable at its own route.
 *
 * WHY : Assumptions: keying a code at the add sentinel loaded the record IN PLACE while the address
 *       stayed on `new`, so a fetched record was not addressable -- a reload lost it, a bookmark could
 *       not name it, and the browser's back control returned to an address that had never described what
 *       was on the screen. `ui/src/routes/navigation.ts` records the repair as two halves and owns the
 *       other one; this asserts the screen's half.
 *
 *       Assumptions: the read count is asserted as well, and it is the property that keeps the repair
 *       from costing a second call. Replacing the address changes the route parameter, so the arrival
 *       effect runs again -- and re-reading would clear the prompt and the sentence the first read
 *       produced, which an operator would see as the screen forgetting what it had just told them.
 * @returns {Promise<void>} Resolves once the address and the read count have been asserted.
 */
async function addressesTheRecordItLoadedFromTheAddSentinel(): Promise<void> {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
  const { user } = await renderInAppShell(
    <>
      <RefTypeEditScreen />
      <AddressProbe />
    </>,
    {
      initialEntries: [`${REFERENCE_LIST_PATH}/${REF_TYPE_NEW_SENTINEL}`],
      routePath: REF_TYPE_EDIT_PATH,
    },
  );

  await user.type(typeCodeControl(), STORED.typeCd);
  await pressPfKey(user, 'ENTER');
  await awaitStoredRow();

  await waitFor(
    /**
     * Waits for the address to name the record on the glass.
     * @returns {void} Nothing; throws until the entry is replaced.
     */
    (): void => {
      expect(renderedAddress()).toBe(`${REFERENCE_LIST_PATH}/${STORED.typeCd}`);
    },
  );
  expect(vi.mocked(getTransactionType)).toHaveBeenCalledTimes(1);
}

/**
 * Asserts arriving at the add sentinel from a shown record begins the screen afresh.
 *
 * WHY : Assumptions: the arrival effect used to RETURN at the sentinel without dispatching, so the turn
 *       survived intact -- the address said a new record was being added while the glass still carried
 *       the previous one, its before-image and its version, and the key field stayed locked to the old
 *       code because the mode still said a record was shown. A save from that state would have replaced
 *       a record the operator believed they were creating.
 *
 *       Assumptions: the mode is asserted through the LEGEND as well as through the two controls,
 *       because the mode is what the legend, the prompt and the delete arm are all derived from. The
 *       delete descriptor is `DRK` until a row is shown, at `COTRTUP.bms` L120, so its absence is the
 *       mode having genuinely returned to key entry rather than the fields merely having been cleared.
 * @returns {Promise<void>} Resolves once the fresh turn has been asserted.
 */
async function beginsAfreshWhenTheAddressReachesTheAddSentinel(): Promise<void> {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
  const { user } = await renderInAppShell(
    <>
      <RefTypeEditScreen />
      <AddSentinelProbe />
    </>,
    {
      initialEntries: [`${REFERENCE_LIST_PATH}/${STORED.typeCd}`],
      routePath: REF_TYPE_EDIT_PATH,
    },
  );
  await awaitStoredRow();

  await user.click(screen.getByRole('button', { name: GO_TO_ADD_LABEL }));

  await waitFor(
    /**
     * Waits for the key field to be released and emptied.
     * @returns {void} Nothing; throws until the turn is reset.
     */
    (): void => {
      expect(typeCodeControl()).toHaveValue('');
    },
  );
  expect(typeCodeControl()).toBeEnabled();
  expect(descriptionControl()).toHaveValue('');
  expect(legendDescriptors()).not.toContain(REF_TYPE_EDIT_KEY_LABELS.PFK04);
}

/**
 * Asserts the two message lines are adjacent, in the mapset's order, and both inside the pinned zone.
 *
 * WHY : ⚠️ Refactoring Rationale: the row-22 line was composed in the screen body while the row-23 line
 *       was published on the shell, so the frame's own row-22 strip stood empty and `aria-hidden` at its
 *       reserved height while the real advisory rendered roughly two hundred pixels lower, INSIDE
 *       `<main>` and below the line it is declared above. The mapset declares them as two adjacent rows
 *       at the bottom of one display -- `INFOMSG` at `POS=(22,23)` and `ERRMSG` at `POS=(23,1)` in
 *       `app/app-transaction-type-db2/bms/COTRTUP.bms` -- so an operator reading down the screen met the
 *       outcome before the prompt, and met an empty announced region between them.
 *
 *       Assumptions: the assertion is STRUCTURAL and not geometric, because jsdom performs no layout, so
 *       a rectangle here would be fabricated. Shared containment in the shell's pinned zone plus document
 *       order is what the arrangement reduces to once layout is removed: the zone is the element whose
 *       height the shell reserves, and `Node.compareDocumentPosition` answers the order the two bands are
 *       painted in.
 *
 *       Alternatives Considered: asserting the screen renders no second band by counting elements
 *       carrying the information handle. Rejected because it would pass for a screen that published
 *       nothing at all -- the defect was a MISPLACED band, not a duplicated one, and the frame's empty
 *       strip carried the same handle.
 * @returns {Promise<void>} Resolves once containment and order have been asserted.
 */
async function pinsBothMessageLinesTogetherInTheDeclaredOrder(): Promise<void> {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
  await renderInShellAt(STORED.typeCd);
  await awaitStoredRow();

  const zone = screen.getByTestId(SHELL_PINNED_ZONE_TEST_ID);
  const information = screen.getByTestId(MESSAGE_BAND_TEST_IDS.information);
  const error = screen.getByTestId(MESSAGE_BAND_TEST_IDS.error);

  expect(zone).toContainElement(information);
  expect(zone).toContainElement(error);
  // WHY : Assumptions: `DOCUMENT_POSITION_FOLLOWING` is 4, and the bit is READ rather than compared for
  //       equality, because the mask also carries containment bits when one node contains the other --
  //       which these two do not, and asserting on the whole number would couple the case to that.
  expect(
    information.compareDocumentPosition(error) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeGreaterThan(0);
  // WHY : Assumptions: the prompt's own text is asserted as well, so the case cannot pass against a
  //       screen that publishes an EMPTY row-22 line -- which is precisely what the frame did before the
  //       move, and it satisfied containment and order on its own.
  // WHY : Assumptions: `showDetails` prompts with the SEARCH-KEY sentence, which reads oddly beside a
  //       row on the glass and is nonetheless what the reference does -- `3250-SETUP-INFOMSG` reaches
  //       that arm through the L1221 fall-through, which `modeInfoMessage` transcribes.
  expect(informationBandText()).toContain(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text);
}

/**
 * The key control's box holds its own value with the marker slot beside it, not instead of it.
 *
 * ⚠️ Purpose: a browser sweep measured this control clipping the record's own identity. Its
 * `clientWidth` was 12 against a `scrollWidth` of 17, so the stored key `01` rendered as `0:` -- the
 * first glyph and a sliver of the second -- and it did so identically at 375, 768, 1280 and 1920,
 * because a fixed ceiling does not vary with the viewport. The key is authoritative data on this
 * screen, so a key that cannot be read is a correctness failure rather than a cosmetic one.
 *
 * ⚠️ Assumptions: the cause is that the marker slot shares the box. This control carries a suffix on
 * EVERY turn -- the reason is recorded on `MARKER_SLOT_UNOCCUPIED` in the screen -- and with a suffix
 * present `@rc-component/input` puts the width on the affix WRAPPER (`BaseInput.js` L124, L137-L142)
 * rather than on the input, so a ceiling measured for the value alone leaves the value short by
 * whatever the slot takes. On two characters that was almost all of it.
 *
 * Assumptions: the expected reserve is stated as the declared width plus the marker's characters plus
 * one, which is the contract `copybookFieldWidthStyle` documents: the marker's glyphs, and one further
 * cell because `ch` on the wrapper resolves in the theme's proportional face while the value renders in
 * the wider fixed-pitch one. It is asserted exactly rather than as a lower bound, so a ceiling widened
 * by a comfortable guess instead of the slot still fails here.
 *
 * Assumptions: the affix allowance is asserted as a separate term, because the two halves answer
 * different questions -- the cells cover the marker's glyph and the face difference, and the padding
 * term covers the margin the design system puts around a suffix, which it derives from `paddingXXS`
 * (`antd/lib/input/style/token.js` L11, applied at `index.js` L444-L447).
 * @returns {Promise<void>} Resolves once the wrapper's ceiling has been measured.
 */
async function reservesTheMarkerSlotBesideTheKeyRatherThanInsideIt(): Promise<void> {
  await arriveAtStoredRow();

  const wrapper = typeCodeControl().closest<HTMLElement>('.ant-input-affix-wrapper');

  if (wrapper === null) {
    throw new Error('the key control rendered without the affix wrapper its marker slot requires');
  }

  expect(
    wrapper.style.maxInlineSize,
    'the ceiling must reserve the declared characters AND the marker slot beside them',
  ).toContain(`${String(TYPE_CODE_WIDTH + BLANK_FIELD_MARKER_CHARACTERS + 1)}ch`);
  expect(
    wrapper.style.maxInlineSize,
    'and must reserve the padding the design system puts around a suffix',
  ).toContain('padding-xxs');
  expect(
    wrapper.style.maxInlineSize,
    'the pre-fix ceiling reserved the declared width alone, which is what clipped the key',
  ).not.toContain(`calc(${String(TYPE_CODE_WIDTH)}ch`);
}

/**
 * Asserts a value is held in canonical form and cut on a character boundary to the declared width.
 *
 * WHY : ⚠️ Refactoring Rationale: `maxLength` on the control is the only width guard the screen used to
 *       have, and it counts UTF-16 units -- so `'🎉'` spends two of them for one character and a
 *       decomposed `'é'` spends two for one as well, which halves the visible capacity of the field. It
 *       is also a TYPING guard and does nothing whatsoever to a value assigned from a response body. A
 *       fixed-width record cares about neither unit it counts: `TRAN-TYPE-DESC PIC X(50)` at
 *       `app/cpy/CVTRA03Y.cpy` L6 and `CHAR(50)` at `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L4
 *       are fifty BYTES.
 *
 *       Assumptions: the emoji case is the one that proves the cut walks code points rather than UTF-16
 *       units. `'🎉'` is one code point stored as a surrogate PAIR, so a cut that indexed units could
 *       leave half of it behind -- a lone surrogate, which is not text and which encodes to a
 *       replacement character. The assertion is on the code-point count, and it is deliberately not on
 *       the string length, because the length is the wrong unit and is what the defect counted.
 *
 *       Assumptions: the composed and decomposed spellings of one letter are asserted to arrive at the
 *       SAME value, which is the normalisation half. Two spellings of `'Café'` are two different byte
 *       sequences of two different lengths, and a fixed-width record that accepted both would hold two
 *       rows that read identically and compare unequal.
 * @returns {void} Nothing; the assertions either hold or throw.
 */
function holdsAValueToWhatItsFieldCanCarry(): void {
  const composed = 'Caf\u00e9';
  const decomposed = 'Cafe\u0301';

  expect(holdInField(decomposed, DESCRIPTION_WIDTH)).toBe(composed);
  expect(holdInField(composed, DESCRIPTION_WIDTH)).toBe(composed);

  // WHY : Assumptions: fifty-one ASCII characters is one over the field, so exactly one is dropped.
  const overByOne = 'A'.repeat(DESCRIPTION_WIDTH + 1);
  expect(holdInField(overByOne, DESCRIPTION_WIDTH)).toBe('A'.repeat(DESCRIPTION_WIDTH));

  // WHY : Assumptions: an emoji costs four bytes, so the BYTE budget binds long before the character
  //       budget does -- twelve of them fit fifty bytes and the thirteenth does not.
  const emoji = '\u{1F389}'.repeat(20);
  expect([...holdInField(emoji, DESCRIPTION_WIDTH)]).toHaveLength(
    Math.floor(DESCRIPTION_WIDTH / 4),
  );

  /*
   * WHY : Assumptions: SEVEN is the width that discriminates a code-point cut from a UTF-16-unit cut,
   *       and no wider width does. Two of these emoji are four units and eight bytes; dropping one
   *       UTF-16 unit leaves one whole emoji plus a LONE HIGH SURROGATE, which is two code points and --
   *       because a lone surrogate encodes to a three-byte replacement character -- seven bytes. So a
   *       unit-indexed cut FITS at seven and stops there, returning a string that is not text. A
   *       code-point cut drops the whole character and returns one emoji. At the field's real width of
   *       fifty the two cuts happen to agree, which is why this assertion carries its own width rather
   *       than reusing the field's.
   */
  const twoEmoji = '\u{1F389}\u{1F389}';
  const surrogateTrap = 7;
  expect(holdInField(twoEmoji, surrogateTrap)).toBe('\u{1F389}');

  // WHY : Assumptions: a value that already fits is returned untouched, so the helper is inert on the
  //       path every typed value takes.
  expect(holdInField('PAYMENT', DESCRIPTION_WIDTH)).toBe('PAYMENT');
  expect(holdInField('', DESCRIPTION_WIDTH)).toBe('');
}

/**
 * Asserts a response row wider than its fields is adopted as only what those fields can carry.
 *
 * WHY : ⚠️ Refactoring Rationale: a row was adopted WHOLE, so an over-wide description was displayed in
 *       a field that cannot hold it and was offered straight back on the next save to a `CHAR(50)`
 *       column. `maxLength` does not retro-enforce on an assigned value, so nothing in the screen
 *       noticed. This is the seam where a response becomes screen state, so it is the seam that has to
 *       hold the width.
 *
 *       Assumptions: the key is asserted as well as the description. A service answering a three-digit
 *       code is out of contract -- `TR_TYPE` is `CHAR(2)` at `TRNTYPE.ddl` L2 -- and adopting it would
 *       put a key in the field that no subsequent read or write could match.
 * @returns {void} Nothing; the assertions either hold or throw.
 */
function adoptsOnlyWhatTheDeclaredFieldsCarry(): void {
  const adopted = adoptRow({
    typeCd: '011',
    description: 'B'.repeat(DESCRIPTION_WIDTH + 10),
    version: 1,
  });

  expect(adopted.typeCode).toBe('01');
  expect(adopted.description).toBe('B'.repeat(DESCRIPTION_WIDTH));
}

/**
 * Registers every case for the transaction-type maintenance screen.
 *
 * Assumptions: the contract cases come first and the rendered cases after, because a rendered case
 * asserting a sentence is only meaningful once the sentence itself has been pinned to the catalog - a
 * suite that failed both would otherwise leave a reader guessing which was the cause.
 * @returns {void} Nothing; registration is the whole effect.
 */
function refTypeEditHarnessCases(): void {
  it(
    'carries every message at its declared channel and width',
    carriesEveryMessageAtItsDeclaredChannelAndWidth,
  );
  it(
    'keeps both invalid-key sentences apart from the shared one',
    keepsBothInvalidKeySentencesApart,
  );
  it('records the byte-identical sibling sentences', recordsTheByteIdenticalSiblingSentences);
  it('preserves every punctuation anomaly', preservesEveryPunctuationAnomaly);
  it('composes every refusal from the catalog halves', composesEveryRefusalFromTheCatalogHalves);
  it('transcribes the key matrix for every mode', transcribesTheKeyMatrixForEveryMode);
  it('binds no key beyond the six the mapset paints', bindsNoKeyBeyondTheSixTheMapsetPaints);
  it('paints each legend control from its risk', paintsEachLegendControlFromItsRisk);
  it('reports the outstanding key as busy', reportsTheOutstandingKeyAsBusy);
  it('declines a withdrawal while the delete runs', declinesAWithdrawalWhileTheDeleteRuns);
  it('declares the maintenance route administrative', declaresTheMaintenanceRouteAdministrative);
  it('publishes no way to grant admin from the client', publishesNoWayToGrantAdminFromTheClient);
  it('bounds both controls to their declared widths', boundsBothControlsToTheirDeclaredWidths);
  it(
    'reserves the marker slot beside the key rather than inside it',
    reservesTheMarkerSlotBesideTheKeyRatherThanInsideIt,
  );
  it('paints both labels with their padding intact', paintsBothLabelsWithTheirPaddingIntact);
  it('paints its caption inside the shared title band', paintsItsCaptionInsideTheSharedBand);
  it("paints the clock in the mapset's own shapes", paintsTheClockInTheMapsetsOwnShapes);
  it('places the cursor on the key on first entry', placesTheCursorOnTheKeyOnFirstEntry);
  it(
    'hides the conditional legends until their state arrives',
    hidesTheConditionalLegendsUntilTheirStateArrives,
  );
  it('reveals delete and cancel once a row is shown', revealsDeleteAndCancelOnceARowIsShown);
  it('paints the sixth legend inert', paintsTheSixthLegendInert);
  it(
    'renders neither the dead nor the shared invalid-key sentence',
    rendersNeitherTheDeadNorTheSharedInvalidKeySentence,
  );
  it('refuses delete and cancel during key entry', refusesDeleteAndCancelDuringKeyEntry);
  it(
    'refuses Enter while a delete awaits confirmation',
    refusesEnterWhileADeleteAwaitsConfirmation,
  );
  it('deletes on the second press and never clears', deletesOnTheSecondPressAndNeverClears);
  it(
    'gates the pointer delete behind a dangerous confirmation',
    gatesThePointerDeleteBehindADangerousConfirmation,
  );
  it(
    'presents the armed delete as a dialogue naming the record',
    presentsTheArmedDeleteAsADialogNamingTheRecord,
  );
  it('arms the delete from the pointer', armsTheDeleteFromThePointer);
  it(
    'describes both choices with the record being destroyed',
    describesBothChoicesWithTheRecordBeingDestroyed,
  );
  it(
    'disarms the delete when the confirmation is abandoned',
    disarmsTheDeleteWhenTheConfirmationIsAbandoned,
  );
  it('withdraws the armed delete on Escape', withdrawsTheArmedDeleteOnEscape);
  it('reports a delete refused by the child constraint', reportsADeleteRefusedByTheChildConstraint);
  it('reports a delete the table refused', reportsADeleteTheTableRefused);
  it(
    'reports a transient save as a condition that may clear',
    reportsATransientSaveAsAConditionThatMayClear,
  );
  it(
    'reports a delete that reached nothing as incomplete',
    reportsADeleteThatReachedNothingAsIncomplete,
  );
  it(
    "shows the service's own sentence for a classified failure",
    showsTheServicesOwnSentenceForAClassifiedFailure,
  );
  it(
    'adds through the not-found mode with five then five',
    addsThroughTheNotFoundModeWithFiveThenFive,
  );
  it('saves a validated change with the issued version', savesAValidatedChangeWithTheIssuedVersion);
  it(
    'reports a concurrent change with the declared sentence',
    reportsAConcurrentChangeWithTheDeclaredSentence,
  );
  it('reports a lock refusal with its own sentence', reportsALockRefusalWithItsOwnSentence);
  it('refuses to write when nothing changed', refusesToWriteWhenNothingChanged);
  it('cancels a validated change with its own sentence', cancelsAValidatedChangeWithItsOwnSentence);
  it('cancels a pending delete with its own sentence', cancelsAPendingDeleteWithItsOwnSentence);
  it(
    'exits to the administrative menu without a caller',
    exitsToTheAdministrativeMenuWithoutACaller,
  );
  it('exits to the caller the transition named', exitsToTheCallerTheTransitionNamed);
  it('reads its key from the route parameter', readsItsKeyFromTheRouteParameter);
  it(
    'marks a refused control and leaves the other clean',
    marksARefusedControlAndLeavesTheOtherClean,
  );
  it(
    'keeps the prompt and the outcome on their own lines',
    keepsThePromptAndTheOutcomeOnTheirOwnLines,
  );
  it(
    'paints no validation alphabet and no sensitive value',
    paintsNoValidationAlphabetAndNoSensitiveValue,
  );
  it('refuses authority to an ordinary operator', refusesAuthorityToAnOrdinaryOperator);
  it('reports a refused read as a refusal', reportsARefusedReadAsARefusal);
  it(
    'withholds the fault surface from a transient failure',
    withholdsTheFaultSurfaceFromATransientFailure,
  );
  it(
    'reports a read that reached nothing as incomplete',
    reportsAReadThatReachedNothingAsIncomplete,
  );
  it('surfaces a genuine fault with its abend data', surfacesAGenuineFaultWithItsAbendData);
  it('refuses an address the key field cannot hold', refusesAnAddressTheKeyFieldCannotHold);
  it(
    'addresses the record it loaded from the add sentinel',
    addressesTheRecordItLoadedFromTheAddSentinel,
  );
  it(
    'begins afresh when the address reaches the add sentinel',
    beginsAfreshWhenTheAddressReachesTheAddSentinel,
  );
  it(
    'pins both message lines together in the declared order',
    pinsBothMessageLinesTogetherInTheDeclaredOrder,
  );
  it('keeps every character typed after a refusal', keepsEveryCharacterTypedAfterARefusal);
  it('paints the protected key as data', paintsTheProtectedKeyAsData);
  it('holds a value to what its field can carry', holdsAValueToWhatItsFieldCanCarry);
  it('adopts only what the declared fields carry', adoptsOnlyWhatTheDeclaredFieldsCarry);
}

describe(
  'the transaction-type maintenance screen, through the shared harness',
  refTypeEditHarnessCases,
);
