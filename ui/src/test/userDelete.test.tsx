/**
 * @file Component test for the delete-user screen, the migration target of BMS mapset `COUSR03` /
 * map `COUSR3A` and program `app/cbl/COUSR03C.cbl` (transaction `CU03`), mounted by
 * `ui/src/router.tsx` at `/users/:id/delete`.
 *
 * Purpose
 * -------
 * Holds `ui/src/screens/userDelete/index.tsx` to the reference it replaces on the six properties that
 * can be measured from the baseline sources: the one-enterable-field shape its mapset declares, the
 * two-stage fetch-then-delete workflow its program encodes, the seven sentences it MOVEs verbatim, the
 * five attention identifiers it binds against the four its legend advertises, the administrative
 * authority its route carries, and the field-level refusal its templated highlight copybook draws.
 *
 * ⚠️ Why this file carries more weight than a screen test usually does
 * -------------------------------------------------------------------
 * This is the ONLY automated verification this screen gets, and the screen performs the one
 * irreversible action in the application. `tests/README.md` section 1.1 states the limitation in its
 * own words: "Online `CO*` CICS programs cannot run end-to-end without a CICS runtime (absent on the
 * runner); only their extractable field-validation logic is unit-tested." The repository's COBOL suite
 * is a parity oracle for the BATCH chain only -- its golden masters cover posting, interest, statements
 * and reports -- so there is no golden master for `COUSR03C` and none can be produced on this runner.
 * A regression in the delete flow is therefore caught here or nowhere, and what it would destroy is a
 * user record with no undo.
 *
 * Module contract, in the terms Rule 1 asks for
 * ---------------------------------------------
 * Parameters: none -- a test module is an entry point the runner invokes, and every input a case needs
 * is built inside it by a documented fixture builder below. Returns: nothing; each case reports through
 * its assertions. Exceptions: a failing expectation, which is how the runner records a defect; the
 * fixture builders' own documented throws are noted on each.
 *
 * Assumptions: every user-visible sentence is asserted by importing it from
 * `ui/src/messages/messages.ts`, never by retyping it. Alternatives Considered: retyping the sentences
 * as literals here, which reads more directly at the assertion site. Rejected because it makes the
 * suite self-confirming: a paraphrase written into the screen and the same paraphrase written into the
 * test agree with each other, every case passes, and the byte-for-byte fidelity AAP transformation rule
 * T8 requires is gone with nothing to report it. Asserting through the catalog tests the screen against
 * the transcription instead of against this file.
 *
 * Assumptions: this module extends an established house convention rather than introducing one. Rule 1
 * (Explainability) requires a docstring stating purpose, parameters and returns on every function and
 * module entry point, and an inline comment giving the WHY of every non-obvious decision under one of
 * four named categories. `tests/README.md` section 12 imposes the identical obligation on "every new
 * test, fixture builder, helper, mock, and runner routine" and calls it "a hard review gate". The two
 * agree completely, so nothing here is resolved between them; the form follows
 * `docs/CODE_DOCUMENTATION_STANDARD.md`, whose TypeScript section additionally obliges a `{Type}` tag
 * on every `@param` and `@returns`.
 *
 * Reference-only provenance: `app/bms/COUSR03.bms`, `app/cpy-bms/COUSR03.CPY`, `app/cbl/COUSR03C.cbl`,
 * `app/cbl/COUSR02C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/cpy/CVCRD01Y.cpy`, `app/cpy/COCOM01Y.cpy`,
 * `app/cpy/COADM02Y.cpy`, `app/cpy/CSMSG01Y.cpy`, `app/cpy/CSSETATY.cpy` and `app/cpy/CSSTRPFY.cpy`
 * are read as the specification and are never modified.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { Route, RouterProvider, Routes, createMemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../api/auth';
import { USER_ID_MAX_LENGTH, deleteUser, getUser } from '../api/auth';
import { ApiRequestError } from '../api/client';
import type { ApiError, UserResponse } from '../api/types';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PRIMARY_ACTION_AIDS, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  COMMON_MESSAGES,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  USER_DELETE_CAPTION,
  USER_DELETE_FIELD_LABELS,
  USER_DELETE_KEY_LABELS,
  USER_DELETE_USER_TYPE_HINT,
  formatMessageTemplate,
} from '../messages/messages';
import { CARD_DEMO_ROUTES, ROUTE_TABLE, USER_DELETE_PATH } from '../router';
import UserDeleteScreen, {
  USER_DELETE_FIELD_WIDTHS,
  USER_DELETE_PROGRAM_NAME,
  USER_DELETE_TRANSACTION_ID,
} from '../screens/userDelete';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  pressPfKey,
  renderInAppShell,
  renderWithProviders,
  seedSession,
  shellLandmark,
} from './setup';
import type { HarnessRenderResult } from './setup';

/*
 * Alternatives Considered: `ApiRequestError` is imported from `ui/src/api/client.ts`, which this
 * file's dependency list does not name, and the alternative was to omit it and leave two required
 * assertions unwritten. Rejected: the screen reaches its not-found arm ONLY through
 * `isApiRequestError`, which is an `instanceof` check, so a plain `Error` and a hand-built object
 * literal both fall through to the stage message and the `'User ID NOT found...'` sentences at
 * `app/cbl/COUSR03C.cbl` L289 and L325 would go unverified on the one screen whose action cannot be
 * undone. The module is reached transitively by the sanctioned surface rather than newly introduced --
 * `ui/src/screens/userDelete/index.tsx` imports `isApiRequestError` from it and `ui/src/api/types.ts`
 * declares the `ApiError` document it carries -- and the same import for the same reason is the
 * established idiom in `ui/src/screens/accountUpdateMarker.test.tsx` L43.
 */

/**
 * Replaces the two record operations of the auth transport, leaving every other export real.
 *
 * ⚠️ Assumptions: this is a PARTIAL mock built on `vi.importActual`, and the partiality is
 * load-bearing rather than tidiness. `seedSession` establishes an operator by driving the real sign-on
 * exchange through the recording transport, so a factory that replaced the module wholesale would
 * leave `signOn` returning `undefined` and every session -- and with it the administrative-authority
 * cases, which are the reason this file can assert that a non-administrator never reaches the
 * transport -- would fail to arrange. Keeping the module real except for the two operations under test
 * also keeps `USER_ID_MAX_LENGTH` the value the service actually judges a submission against, so the
 * width assertion below reads the real contract instead of a fixture of it.
 *
 * Assumptions: a hoisted function DECLARATION rather than a factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would still be in its temporal dead
 * zone when the registration runs, which fails on the symbol's name and never mentions the hoisting.
 * @returns {Promise<typeof AuthModule>} The real module with the read and the delete replaced by spies.
 */
async function mockAuthTransportModule(): Promise<typeof AuthModule> {
  const actual = await vi.importActual<typeof AuthModule>('../api/auth');
  return {
    ...actual,
    /**
     * Stands in for the record read.
     * @returns {Promise<UserResponse>} Whatever the case configured.
     */
    getUser: vi.fn(),
    /**
     * Stands in for the record deletion.
     * @returns {Promise<void>} Whatever the case configured.
     */
    deleteUser: vi.fn(),
  };
}

vi.mock('../api/auth', mockAuthTransportModule);

/**
 * Identifier of the administered row, at the eight characters its key declares.
 *
 * Assumptions: eight characters exactly, because `05 SEC-USR-ID PIC X(08).` at
 * `app/cpy/CSUSR01Y.cpy` L18 and `USRIDIN`'s `LENGTH=8` at `app/bms/COUSR03.bms` L85-L89 agree on the
 * width. A fixture wider than the key would be silently truncated by the control under test, which
 * would make a width defect look like a passing case.
 */
const ADMINISTERED_USER_ID = 'USER0001';

/**
 * The row the read answers with, at the widths the mapset and the record layout agree on.
 *
 * ⚠️ Assumptions: FIVE members and no credential among them, which is the published contract rather
 * than a redaction applied here. `UserResponse` in `ui/src/api/types.ts` extends `UserSummary`'s four
 * members with `cognitoSub` alone, and `app/cpy-bms/COUSR03.CPY` declares eleven field families of
 * which none is a credential family -- unlike its `COUSR01` and `COUSR02` siblings, which each declare
 * one. The reference stores `05 SEC-USR-PWD PIC X(08).` in plain text at `app/cpy/CSUSR01Y.cpy` L21
 * and AAP sections 0.5.1.2 and 0.7.8 decline parity on it, so `auth.users` carries no such column at
 * all and there is nothing for this fixture to withhold.
 *
 * Assumptions: `'ADA'` and `'LOVELACE'` sit inside the twenty characters `FNAME` and `LNAME` declare
 * at L103-L107 and L116-L120, and `'U'` is one of the two characters `USRTYPE` admits at L130-L134 --
 * so a value here can never be the reason an assertion about a width fails.
 */
const STORED_USER: UserResponse = {
  userId: ADMINISTERED_USER_ID,
  firstName: 'ADA',
  lastName: 'LOVELACE',
  userType: 'U',
  cognitoSub: '00000000-0000-4000-8000-000000000001',
};

/**
 * Builds the address the route table opens this screen at, for one identifier.
 * @param {string} userId - Identifier to place in the `:id` segment.
 * @returns {string} The concrete address, matching `USER_DELETE_PATH`.
 */
function deleteAddressFor(userId: string): string {
  return `/users/${userId}/delete`;
}

/**
 * Reports the read spy, typed as a spy rather than as the declared transport function.
 * @returns {ReturnType<typeof vi.mocked<typeof getUser>>} The read spy.
 */
function readSpy(): ReturnType<typeof vi.mocked<typeof getUser>> {
  return vi.mocked(getUser);
}

/**
 * Reports the deletion spy, typed as a spy rather than as the declared transport function.
 * @returns {ReturnType<typeof vi.mocked<typeof deleteUser>>} The deletion spy.
 */
function deleteSpy(): ReturnType<typeof vi.mocked<typeof deleteUser>> {
  return vi.mocked(deleteUser);
}

/**
 * Builds the failure the transport raises for a status-carrying refusal.
 *
 * Assumptions: a real `ApiRequestError` instance and never a shaped literal, because the screen
 * discriminates with `isApiRequestError`, which is an `instanceof` test -- so only an instance reaches
 * the status-driven arms of `describeUserDeleteFailure`. The problem document is built by the shared
 * `apiError` builder rather than restated member by member, so this file holds no copy of the
 * eleven-member problem shape.
 * @param {number} status - HTTP status the refusal carries.
 * @returns {ApiRequestError} The failure the transport would have thrown.
 */
function refusalWithStatus(status: number): ApiRequestError {
  const problem: ApiError = apiError({ status });
  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`);
}

/**
 * Builds the failure the transport raises when no status is available.
 *
 * Assumptions: a plain `Error` is the faithful stand-in for the reference's `WHEN OTHER` arms at
 * `app/cbl/COUSR03C.cbl` L293 and L329, which are reached for any response the program did not
 * enumerate. It is deliberately NOT an `ApiRequestError`: the screen's `describeUserDeleteFailure`
 * falls through to the per-stage sentence for anything that is not one, and the per-stage sentence is
 * exactly what those two arms MOVE.
 * @returns {Error} A failure carrying no recognisable problem document.
 */
function unrecognisedFailure(): Error {
  return new Error('the transport failed in a way the contract does not describe');
}

/**
 * Renders the screen with no row selected, which is the reference's unselected first turn.
 *
 * Assumptions: no `routePath` is supplied, so the harness mounts the subject without a parameterised
 * route and `useParams` yields no `id`. That reproduces `app/cbl/COUSR03C.cbl` L99-L104, where the
 * program reads its selection carrier only when it is populated and otherwise paints an empty screen
 * for the operator to type a key into. The shell is mounted around it because the legend and the
 * message band are delegated zones -- `useShellSlot` publishes them and only `AppShell` paints them --
 * so a subject rendered bare would have neither to assert on.
 * @returns {Promise<HarnessRenderResult>} The rendered screen and its operator.
 */
async function renderUnselected(): Promise<HarnessRenderResult> {
  return await renderInAppShell(<UserDeleteScreen />, {
    initialEntries: ['/users/delete'],
  });
}

/**
 * Renders the screen at its real route with a row selected, which auto-fetches that row.
 *
 * Assumptions: supplying the route pattern is what makes `useParams` yield an `id`, and the screen
 * fetches on mount when it does. That is the migrated form of `app/cbl/COUSR03C.cbl` L101-L103, where
 * a populated selection carrier is moved into the key field and `PROCESS-ENTER-KEY` is performed
 * immediately -- so arriving from the browse displays the row without a keystroke.
 * @param {string} [userId] - Identifier to select, defaulting to the administered row.
 * @returns {Promise<HarnessRenderResult>} The rendered screen and its operator.
 */
async function renderSelected(userId: string = ADMINISTERED_USER_ID): Promise<HarnessRenderResult> {
  return await renderInAppShell(<UserDeleteScreen />, {
    routePath: USER_DELETE_PATH,
    initialEntries: [deleteAddressFor(userId)],
  });
}

/**
 * Reports the screen's single enterable control, asserting on the way that it is the only one.
 *
 * ⚠️ Assumptions: the count is asserted here rather than in one case, because every case that types
 * into this screen depends on it. `USRIDIN` at `app/bms/COUSR03.bms` L85 is the mapset's only
 * `ATTRB=(FSET,IC,NORM,UNPROT)` field; `FNAME`, `LNAME` and `USRTYPE` are each
 * `ATTRB=(ASKIP,FSET,NORM)` at L103, L116 and L130, which skips them and makes them output only. A
 * second text box would mean one of those three had been rendered as a control.
 * @returns {HTMLElement} The identifier control.
 * @throws {Error} If the screen paints no text box or more than one, naming the count found.
 */
function fetchKeyControl(): HTMLElement {
  const controls = screen.getAllByRole('textbox');
  const [only] = controls;
  if (only === undefined || controls.length !== 1) {
    throw new Error(
      `expected exactly one enterable control, the mapset's only UNPROT field, but found ${String(
        controls.length,
      )}`,
    );
  }
  return only;
}

/**
 * Reports the record view the three protected fields are displayed in.
 *
 * Assumptions: located by the table role, because antd renders `Descriptions` as a `<table>` when it
 * is `bordered`. AAP section 0.4.1.4 maps this route to `Descriptions` plus `Popconfirm`, and the
 * mapset's three `ASKIP` fields are why: a record being shown for confirmation is not a form.
 * @returns {HTMLElement} The record view.
 */
function recordView(): HTMLElement {
  return screen.getByRole('table');
}

/**
 * Reports the row-23 message band the shell paints on this screen's behalf.
 *
 * Assumptions: located by the identifier `ui/src/layout/MessageBand.tsx` publishes rather than by a
 * role, because that module assigns `alert` or `status` according to the severity of what it holds --
 * so a role query would have to know the severity, which is the screen's data rather than the frame's
 * structure.
 *
 * ⚠️ Assumptions: this band carries TWO different declared widths and they are both real, so neither is
 * a correction of the other. The mapset's `ERRMSG` field is `LENGTH=78` at `app/bms/COUSR03.bms`
 * L140-L143 and `app/cpy-bms/COUSR03.CPY` L84 declares `ERRMSGI PIC X(78)` to match -- that is the
 * FIELD the terminal reserved on row 23. The CONTENT contract is 75, because the strings the band
 * carries arrive through `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`, declared `PIC X(75)` at
 * `app/cpy/CVCRD01Y.cpy` L28-L30. A 75-character message in a 78-character field is not a discrepancy;
 * it is a message with three characters of slack. Both numbers are recorded and neither is normalised
 * to the other, because "correcting" 75 to 78 would widen a message contract the reference does not
 * widen, and no case here asserts either width -- the band's sizing is the frame's own contract and is
 * covered by that module's suite, not restated per screen.
 * @returns {HTMLElement} The message band.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Waits until the fetched row is on display, which is the state the delete stage requires.
 *
 * Assumptions: the awaited condition is the first name appearing inside the RECORD VIEW rather than
 * anywhere in the document, so a case cannot proceed on a value that happens to be painted somewhere
 * else.
 * @returns {Promise<void>} Resolves once the row is displayed.
 */
async function waitForTheRowOnDisplay(): Promise<void> {
  await waitFor(
    /**
     * Asserts the fetched first name is inside the record view.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theFirstNameIsDisplayed(): void {
      expect(within(recordView()).getByText(STORED_USER.firstName)).toBeInTheDocument();
    },
  );
}

/**
 * Arranges a successful read, renders the selected row and waits for it to be displayed.
 * @returns {Promise<HarnessRenderResult>} The rendered screen with the row on display.
 */
async function renderWithTheRowFetched(): Promise<HarnessRenderResult> {
  readSpy().mockResolvedValue(STORED_USER);
  const rendered = await renderSelected();
  await waitForTheRowOnDisplay();
  return rendered;
}

/**
 * Waits until a sentence is on the message band, character for character.
 *
 * Assumptions: the wait is required because every sentence this screen bands is set from a settled
 * promise or a state update, so the first render after an action does not carry it yet.
 * @param {string} expected - The catalogued sentence, taken from the message catalog.
 * @returns {Promise<void>} Resolves once the band carries exactly that sentence.
 */
async function waitForBandedMessage(expected: string): Promise<void> {
  await waitFor(
    /**
     * Asserts the band carries the expected sentence.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theBandCarriesIt(): void {
      expect(within(messageBand()).getByText(expected)).toBeInTheDocument();
    },
  );
}

/**
 * The one enterable control is the mapset's only unprotected field, at its declared width.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function paintsOneEnterableControlAtItsDeclaredWidth(): Promise<void> {
  await renderUnselected();

  const control = fetchKeyControl();

  /*
   * Assumptions: the width is asserted as `USER_DELETE_FIELD_WIDTHS.userId` rather than as the
   * literal eight, and that constant reads `USER_ID_MAX_LENGTH` from the transport module. Three
   * independent sources agree on the number -- `USRIDINI PIC X(8)` at `app/cpy-bms/COUSR03.CPY` L60,
   * `LENGTH=8` at `app/bms/COUSR03.bms` L85-L89 and `05 SEC-USR-ID PIC X(08).` at
   * `app/cpy/CSUSR01Y.cpy` L18 -- and the constant is the one the service actually judges a
   * submission against, so asserting through it means the control cannot admit a value the transport
   * would refuse.
   */
  expectMaxLength(control, USER_DELETE_FIELD_WIDTHS.userId);
  expect(USER_DELETE_FIELD_WIDTHS.userId).toBe(USER_ID_MAX_LENGTH);

  /*
   * Assumptions: exactly one control carries `autoFocus`, matching the single `IC` operand in the
   * whole mapset at `app/bms/COUSR03.bms` L85. A terminal had one cursor, so a second initial-focus
   * control would not be a second cursor but a race between two of them for the same one.
   */
  expect(control).toHaveFocus();
}

/**
 * The three protected fields are displayed as a record, never as controls.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function displaysTheProtectedFieldsAsARecordAndNotAsControls(): Promise<void> {
  await renderWithTheRowFetched();

  const view = recordView();

  /*
   * ⚠️ Assumptions: the authority for a field's editability is the mapset's `ATTRB` operand and NEVER
   * the `…I` suffix in the symbolic map. BMS generates an `I`/`O` pair for every field regardless of
   * its protection, so `app/cpy-bms/COUSR03.CPY` declares `FNAMEI`, `LNAMEI` and `USRTYPEI` at L66,
   * L72 and L78 exactly as it declares the enterable `USRIDINI` at L60 -- reading the copybook alone
   * would suggest four editable inputs and produce a form where the reference gives a read-back. The
   * mapset settles it: `ASKIP` at L103, L116 and L130 skips all three, and only `USRIDIN` at L85 is
   * `UNPROT`.
   */
  expect(within(view).queryAllByRole('textbox')).toHaveLength(0);

  expect(within(view).getByText(USER_DELETE_FIELD_LABELS.firstName)).toBeInTheDocument();
  expect(within(view).getByText(USER_DELETE_FIELD_LABELS.lastName)).toBeInTheDocument();

  /*
   * Assumptions: the user-type label alone is matched with its right-hand padding removed, because the
   * mapset declares `INITIAL='User Type: '` at `app/bms/COUSR03.bms` L125-L129 as `LENGTH=11` for ten
   * visible characters -- so its trailing space is field padding, and the DOM collapses a trailing run
   * for display. The catalog entry stays the source of the expectation, so a change to the wording
   * still fails here; only the padding is discounted, and only on the one label that carries any. The
   * two names above are matched whole precisely because they carry none.
   */
  expect(within(view).getByText(USER_DELETE_FIELD_LABELS.userType.trimEnd())).toBeInTheDocument();
  expect(USER_DELETE_FIELD_LABELS.userType).toMatch(/ $/);

  expect(within(view).getByText(STORED_USER.firstName)).toBeInTheDocument();
  expect(within(view).getByText(STORED_USER.lastName)).toBeInTheDocument();
  expect(within(view).getByText(STORED_USER.userType)).toBeInTheDocument();

  /*
   * Assumptions: the domain hint is the only place the `'A'`/`'U'` vocabulary is named to an operator
   * on this screen. `app/cbl/COUSR03C.cbl` performs no domain check on the user type anywhere -- it
   * reads the character from the record at L167 and displays it -- so without the mapset's own
   * `'(A=Admin, U=User)'` literal at L135-L139 the single character would be undecodable.
   */
  expect(within(view).getByText(USER_DELETE_USER_TYPE_HINT)).toBeInTheDocument();
}

/**
 * Neither the enterable control nor the record view carries a literal design value.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function resolvesTheMapsetColourRolesThroughTheTokenBridge(): Promise<void> {
  await renderWithTheRowFetched();

  /*
   * Assumptions: the enterable field is `COLOR=GREEN` at `app/bms/COUSR03.bms` L86 while the three
   * protected fields are `COLOR=BLUE` at L104, L117 and L131. AAP section 0.3.3 measured those two
   * operands across all seventeen mapsets and maps green to the success role (76 occurrences) and
   * blue to the primary role (289 occurrences, the dominant label and frame colour). A success token
   * on a plain identifier field is a role TENSION in the source rather than in the mapping -- the
   * reference used colour to mark where typing was allowed, not to signal an outcome -- and it is
   * recorded here rather than resolved, because resolving it would mean overriding a measured
   * attribute. What this case asserts is the consequence that matters: the values resolve through
   * `ui/src/theme/tokens.ts`, so no colour literal reaches the rendered style.
   */
  const styled = `${fetchKeyControl().getAttribute('style') ?? ''}${
    recordView().getAttribute('style') ?? ''
  }`;
  expect(styled).not.toMatch(/#[0-9a-f]{3}/i);
  expect(styled).not.toMatch(/rgba?\(/i);

  /*
   * Assumptions: `HILIGHT=UNDERLINE` is set on all four data fields -- L87, L105, L118 and L132 --
   * yet three of them are protected, so on those three there is genuinely nothing to draw. That is
   * AAP gap G4: the underline was a terminal's only way to show where a field began and ended, and
   * the design system expresses the same affordance structurally through the `Input` border, so the
   * absence of an underline token is deliberate and not an oversight. Asserted as the absence of a
   * text-decoration declaration on the record view, which is where a mistaken one would land.
   */
  expect(recordView().getAttribute('style') ?? '').not.toMatch(/text-decoration/i);
}

/**
 * The screen's own identity reaches the header band the shell paints for it.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function delegatesItsIdentityToTheHeaderBand(): Promise<void> {
  await renderUnselected();

  const band = shellLandmark('titleBand');

  /*
   * Assumptions: only the SCREEN-OWNED half of the band is asserted here -- the transaction and
   * program identifiers this module publishes, which `app/cbl/COUSR03C.cbl` moves at L249 and L250
   * from its own `WS-TRANID` at L37 and `WS-PGMNAME` at L36. The three shared `X(40)` title constants
   * that `app/cpy/COTTL01Y.cpy` supplies to all seventeen mapsets are deliberately NOT asserted here;
   * they belong to the shell's own suite, and restating them per screen would put twenty-one copies of
   * one contract in twenty-one files.
   */
  expect(within(band).getByText(USER_DELETE_TRANSACTION_ID)).toBeInTheDocument();
  expect(within(band).getByText(USER_DELETE_PROGRAM_NAME)).toBeInTheDocument();
}

/**
 * The row-4 caption is the mapset's own literal.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function paintsTheMapsetCaptionVerbatim(): Promise<void> {
  await renderUnselected();

  /*
   * Assumptions: `'Delete User'` is the `INITIAL` operand of the row-4 field at
   * `app/bms/COUSR03.bms` L75-L79, whose `LENGTH=11` matches the string exactly, and it is asserted
   * through the catalog so this file holds no copy of it.
   */
  expect(expectVerbatimMessage(USER_DELETE_CAPTION)).toBeInTheDocument();
}

/**
 * Registers the cases covering what the mapset declares this screen may contain.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function mapsetShapeCases(): void {
  it(
    'paints one enterable control at its declared width, holding the only cursor',
    paintsOneEnterableControlAtItsDeclaredWidth,
  );
  it(
    'displays first name, last name and user type as a record rather than as controls',
    displaysTheProtectedFieldsAsARecordAndNotAsControls,
  );
  it(
    'resolves the mapset colour roles through the token bridge, with no literal value',
    resolvesTheMapsetColourRolesThroughTheTokenBridge,
  );
  it(
    'delegates its transaction and program identity to the header band',
    delegatesItsIdentityToTheHeaderBand,
  );
  it('paints the row-4 caption verbatim', paintsTheMapsetCaptionVerbatim);
}

describe('delete-user screen: the one-enterable-field shape its mapset declares', mapsetShapeCases);

/**
 * Reports the confirmation's accepting control.
 *
 * Assumptions: the accepting and dismissing controls carry the design system's own default wording
 * because the screen sets neither `okText` nor `cancelText`, and that is correct rather than an
 * omission: the reference had no pointer and therefore no button labels to carry across, so these two
 * strings are additive and are deliberately absent from `ui/src/messages/messages.ts`, which holds only
 * strings a COBOL source holds.
 * @returns {HTMLElement} The control that performs the deletion.
 */
function confirmationAcceptControl(): HTMLElement {
  return screen.getByRole('button', { name: 'OK' });
}

/**
 * Reports the confirmation's dismissing control.
 * @returns {HTMLElement} The control that closes the confirmation without deleting.
 */
function confirmationDismissControl(): HTMLElement {
  return screen.getByRole('button', { name: 'Cancel' });
}

/**
 * Waits until the destructive confirmation is open.
 *
 * Assumptions: the awaited condition is the accepting control existing, because antd mounts the
 * confirmation's content only once it opens -- so its absence and a closed confirmation are the same
 * observation.
 * @returns {Promise<void>} Resolves once the confirmation is open.
 */
async function waitForTheConfirmation(): Promise<void> {
  await waitFor(
    /**
     * Asserts the confirmation's accepting control is present.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theConfirmationIsOpen(): void {
      expect(confirmationAcceptControl()).toBeInTheDocument();
    },
  );
}

/**
 * Enter reads the row and never deletes it.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function enterFetchesTheRowAndNeverDeletesIt(): Promise<void> {
  readSpy().mockResolvedValue(STORED_USER);
  const { user } = await renderUnselected();

  await user.type(fetchKeyControl(), ADMINISTERED_USER_ID);
  await pressPfKey(user, 'ENTER');

  await waitForTheRowOnDisplay();

  /*
   * Assumptions: the read is called with the typed identifier and nothing else, which is the migrated
   * form of `app/cbl/COUSR03C.cbl` L160-L161 -- the program moves the key field into `SEC-USR-ID` and
   * performs `READ-USER-SEC-FILE`, whose `EXEC CICS READ` at L269-L278 is a keyed read and not a
   * delete.
   */
  expect(readSpy()).toHaveBeenCalledTimes(1);
  expect(readSpy()).toHaveBeenCalledWith(ADMINISTERED_USER_ID);

  /*
   * ⚠️ Assumptions: the reference reaches `DELETE-USER-SEC-FILE` only from the `DFHPF5` arm at L121-L122
   * and never from the `DFHENTER` arm at L109-L110, so Enter cannot destroy a record. This is the
   * assertion the whole two-stage design exists to protect: conflating the read with the commit would
   * delete a row the operator asked only to look at, and on this screen there is no undo.
   */
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * A successful read invites the second, different keystroke.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function bandsTheAwaitingConfirmationPromptAfterAFetch(): Promise<void> {
  await renderWithTheRowFetched();

  /*
   * ⚠️ Assumptions: the sentence carries a SPACE before its ellipsis --
   * `'Press PF5 key to delete this user ...'` at `app/cbl/COUSR03C.cbl` L283 -- where every other
   * sentence in the program runs the ellipsis straight on. It is asserted through the catalog and
   * through `expectVerbatimMessage`, whose non-collapsing normaliser is what makes the space
   * observable: Testing Library's default matcher collapses whitespace runs, so a screen that emitted
   * the sentence without the space would satisfy an ordinary `getByText`. A formatter, a lint autofix
   * or an author normalising "inconsistent" spacing would delete it silently, and this is the
   * assertion that refuses.
   */
  await waitForBandedMessage(PROGRAM_MESSAGES.COUSR03C.PRESS_PF5_KEY_TO_DELETE_THIS_USER);
  expect(PROGRAM_MESSAGES.COUSR03C.PRESS_PF5_KEY_TO_DELETE_THIS_USER).toContain(' ...');
}

/**
 * PF5 opens the destructive confirmation before anything is deleted.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function pf5OpensTheDangerConfirmationBeforeDeleting(): Promise<void> {
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK05');
  await waitForTheConfirmation();

  /*
   * ⚠️ Assumptions: nothing is deleted by opening the confirmation. AAP section 0.3.2 assigns
   * `Popconfirm` with `okType="danger"` to "confirmation before destructive action (delete user)", and
   * the emphasis is what tells an operator which of the two controls destroys the row -- so the danger
   * class is asserted rather than merely the presence of a dialog.
   */
  expect(deleteSpy()).not.toHaveBeenCalled();
  expect(confirmationAcceptControl()).toHaveClass('ant-btn-dangerous');

  /*
   * Refactoring Rationale: this confirmation REPLACES the 3270 re-key-to-confirm convention, which is
   * what the reference's Enter-then-PF5 shape is. In the baseline the two turns exist because CICS is
   * pseudo-conversational: the task ends at each screen turn and `DFHCOMMAREA` carries the continuity,
   * with `CDEMO-PGM-CONTEXT` at `app/cpy/COCOM01Y.cpy` L29-L31 discriminating a first entry from a
   * re-entry. AAP section 0.7.1 removes that discriminator entirely, so the two-step USER EXPERIENCE is
   * preserved as fidelity while the MECHANISM becomes ordinary client state -- which is why the prompt
   * the confirmation carries is the reference's own awaiting-confirmation sentence rather than a newly
   * written question.
   */
  expect(
    within(screen.getByRole('tooltip')).getByText(
      PROGRAM_MESSAGES.COUSR03C.PRESS_PF5_KEY_TO_DELETE_THIS_USER,
    ),
  ).toBeInTheDocument();
}

/**
 * Dismissing the confirmation deletes nothing.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function dismissingTheConfirmationDeletesNothing(): Promise<void> {
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK05');
  await waitForTheConfirmation();
  await user.click(confirmationDismissControl());

  /*
   * Assumptions: a dismissal is the operator declining, and the reference has no arm that deletes
   * without the confirming keystroke -- `DELETE-USER-SEC-FILE` is performed only from
   * `DELETE-USER-INFO` at L191, which the `DFHPF5` arm alone reaches. The row also stays on display,
   * because declining to delete is not declining to look.
   */
  expect(deleteSpy()).not.toHaveBeenCalled();
  expect(within(recordView()).getByText(STORED_USER.firstName)).toBeInTheDocument();
}

/**
 * Accepting the confirmation deletes exactly once, and says so in the reference's own words.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function acceptingTheConfirmationDeletesExactlyOnce(): Promise<void> {
  deleteSpy().mockResolvedValue(undefined);
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK05');
  await waitForTheConfirmation();
  await user.click(confirmationAcceptControl());

  await waitFor(
    /**
     * Asserts the deletion has been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theDeletionWasIssued(): void {
      expect(deleteSpy()).toHaveBeenCalled();
    },
  );

  /*
   * ⚠️ Assumptions: EXACTLY once, and the count is the assertion rather than a detail of it. A double
   * invocation is a real defect class on any mutating path and an unrecoverable one here: the second
   * call would answer not-found against a row the first call had already destroyed, so the operator
   * would read `'User ID NOT found...'` after a deletion that in fact succeeded and would reasonably
   * conclude it had not.
   */
  expect(deleteSpy()).toHaveBeenCalledTimes(1);

  /*
   * Assumptions: the second argument is the transport's own confirmation flag --
   * `deleteUser(userId, confirmed: true)` in `ui/src/api/auth.ts` types it as the literal `true`, so
   * the contract cannot be satisfied by an unconfirmed call at all.
   */
  expect(deleteSpy()).toHaveBeenCalledWith(ADMINISTERED_USER_ID, true);

  /*
   * Assumptions: the success sentence is COMPOSED rather than fixed, and it is the one message on this
   * screen the reference builds at run time: `app/cbl/COUSR03C.cbl` L318-L321 STRINGs `'User '`, then
   * `SEC-USR-ID` delimited by the first space, then `' has been deleted ...'`. It is asserted through
   * the catalog's template and its formatter so the delimiting behaviour is the catalog's rather than
   * this file's.
   */
  await waitForBandedMessage(
    formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_DELETED, {
      'SEC-USR-ID': ADMINISTERED_USER_ID,
    }),
  );
}

/**
 * The fetched row is carried between the two turns by client state alone.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function carriesTheFetchedRowWithNoServerSideSessionField(): Promise<void> {
  deleteSpy().mockResolvedValue(undefined);
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK05');
  await waitForTheConfirmation();
  await user.click(confirmationAcceptControl());

  await waitFor(
    /**
     * Asserts the deletion has been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theDeletionWasIssued(): void {
      expect(deleteSpy()).toHaveBeenCalledTimes(1);
    },
  );

  /*
   * ⚠️ Assumptions: the second turn re-reads NOTHING. The reference's `DELETE-USER-INFO` at L188-L192
   * performs `READ-USER-SEC-FILE` again before deleting, because a pseudo-conversational task has no
   * memory between turns and had to re-establish the record under a `RIDFLD` for update. The migrated
   * screen holds the row in component state instead, so exactly one read is issued across both turns
   * -- and that single count is the evidence that no session field, server-side or otherwise, is
   * carrying the record between them. AAP section 0.7.1 decomposes the `DFHCOMMAREA` into client
   * history, signed claims and request parameters, and this is the case that shows it did.
   */
  expect(readSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Registers the cases covering the read-then-confirm-then-delete sequence.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function twoStageWorkflowCases(): void {
  it('reads the row on Enter and never deletes it', enterFetchesTheRowAndNeverDeletesIt);
  it(
    'bands the awaiting-confirmation prompt, space before the ellipsis intact',
    bandsTheAwaitingConfirmationPromptAfterAFetch,
  );
  it(
    'opens a danger confirmation on PF5 before deleting anything',
    pf5OpensTheDangerConfirmationBeforeDeleting,
  );
  it('deletes nothing when the confirmation is dismissed', dismissingTheConfirmationDeletesNothing);
  it(
    'deletes exactly once when the confirmation is accepted',
    acceptingTheConfirmationDeletesExactlyOnce,
  );
  it(
    'carries the fetched row without re-reading it',
    carriesTheFetchedRowWithNoServerSideSessionField,
  );
}

describe('delete-user screen: the two-stage fetch-then-delete workflow', twoStageWorkflowCases);

/**
 * A blank key refused on the read stage reads the reference's own sentence.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function refusesABlankKeyOnTheReadStage(): Promise<void> {
  const { user } = await renderUnselected();

  await pressPfKey(user, 'ENTER');

  /*
   * Assumptions: this is the sentence `app/cbl/COUSR03C.cbl` MOVEs at L147, from the blank arm of
   * `PROCESS-ENTER-KEY` at L145-L150. Its capitalised `NOT` is content and is preserved by asserting
   * through the catalog rather than by retyping, which is where a well-meant `'cannot'` would enter.
   */
  await waitForBandedMessage(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY);
  expect(readSpy()).not.toHaveBeenCalled();
}

/**
 * A blank key refused on the delete stage reads the same sentence, from the second site.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function refusesABlankKeyOnTheDeleteStage(): Promise<void> {
  const { user } = await renderUnselected();

  /*
   * ⚠️ Assumptions: the KEY path reaches the delete stage with a blank key even though the pointer
   * affordance does not -- the confirmation's trigger is disabled until a row has been displayed,
   * while `PFK05` carries only a `busy` guard. That asymmetry is deliberate and is the fidelity-bearing
   * half: the reference reaches `DELETE-USER-INFO` from any state and answers for a blank key at
   * L177-L182, and the terminal had no pointer at all, so the keyboard is the path that must keep
   * behaving as the source does.
   */
  await pressPfKey(user, 'PFK05');

  /*
   * ⚠️ Assumptions: `app/cbl/COUSR03C.cbl` L179 MOVEs a sentence byte-identical to its own L147, so
   * the catalog holds ONE entry with both sites recorded against it -- `SHARED_MESSAGE_SOURCES` lists
   * `COUSR03C` lines `[147, 179]` under a single key. Both trigger points are asserted, and they are
   * asserted against that one entry rather than against two copies, because two entries would let a
   * correction reach one site and silently miss the other.
   */
  await waitForBandedMessage(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY);
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * A read that finds no row reads the reference's not-found sentence.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function reportsAReadThatFindsNoRow(): Promise<void> {
  readSpy().mockRejectedValue(refusalWithStatus(404));
  await renderSelected();

  /*
   * Assumptions: `app/cbl/COUSR03C.cbl` L289 MOVEs this sentence from the `DFHRESP(NOTFND)` arm of
   * `READ-USER-SEC-FILE` at L287-L292, and the target reaches the same arm on HTTP 404. Its `NOT` is
   * capitalised in the source and is preserved through the catalog.
   */
  await waitForBandedMessage(SHARED_MESSAGES.USER_ID_NOT_FOUND);
}

/**
 * A read that fails for any other reason reads the read-stage sentence.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function reportsAReadThatFailsOtherwise(): Promise<void> {
  readSpy().mockRejectedValue(unrecognisedFailure());
  await renderSelected();

  /*
   * Assumptions: this is the `WHEN OTHER` arm at `app/cbl/COUSR03C.cbl` L293-L299, which MOVEs
   * `'Unable to lookup User...'` at L296 for any response the program did not enumerate. The catalog
   * records the same string against `COUSR00C` and `COUSR02C` as well, so it is a shared entry with
   * three sites and this screen's is one of them.
   */
  await waitForBandedMessage(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER);
}

/**
 * A deletion that finds no row reads the not-found sentence from its second site.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function reportsADeletionThatFindsNoRow(): Promise<void> {
  deleteSpy().mockRejectedValue(refusalWithStatus(404));
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK05');
  await waitForTheConfirmation();
  await user.click(confirmationAcceptControl());

  /*
   * Assumptions: `app/cbl/COUSR03C.cbl` L325 MOVEs the same sentence as its own L289, from the
   * `DFHRESP(NOTFND)` arm of `DELETE-USER-SEC-FILE` at L323-L328 -- the second of this program's two
   * duplicate-string pairs. One catalog entry, both sites asserted, for the reason recorded on the
   * blank-key pair above.
   */
  await waitForBandedMessage(SHARED_MESSAGES.USER_ID_NOT_FOUND);
}

/**
 * A deletion that fails otherwise reads the sentence the reference actually shows, wording and all.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function reportsADeletionThatFailsOtherwise(): Promise<void> {
  deleteSpy().mockRejectedValue(unrecognisedFailure());
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK05');
  await waitForTheConfirmation();
  await user.click(confirmationAcceptControl());

  /*
   * ⚠️⚠️ Assumptions: the sentence on the DELETE failure path says `'Unable to Update User...'` --
   * `app/cbl/COUSR03C.cbl` L332, in the `WHEN OTHER` arm of `DELETE-USER-SEC-FILE` at L329-L335. The
   * reference reuses its update-failure wording for a deletion, which is semantically wrong and is
   * nevertheless what an operator reads, so AAP transformation rule T8 carries it across
   * character-for-character and the word `Update` is preserved on a path that deletes.
   * Trade-offs: this is the single most likely string on this screen to be "corrected" to
   * `'Unable to Delete User...'`, and correcting it here would be a behavioural divergence requiring
   * registration in `docs/architecture/cobol-to-service-traceability.md`. A test is not the place to
   * introduce one, so the wrong-sounding wording is asserted deliberately.
   * Assumptions: the catalog holds ONE entry for it, with `COUSR02C` L386 -- where the identical
   * string reports a genuine update -- recorded against the same key. The two screens therefore share
   * a single transcription, which is why this case asserts through the shared entry rather than a
   * delete-specific one.
   */
  await waitForBandedMessage(SHARED_MESSAGES.UNABLE_TO_UPDATE_USER);
  expect(SHARED_MESSAGES.UNABLE_TO_UPDATE_USER).toContain('Update');
}

/**
 * A key the program does not bind reads the shared unmapped-key sentence.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function reportsAKeyTheProgramDoesNotBind(): Promise<void> {
  const { user } = await renderUnselected();

  /*
   * Assumptions: PF7 is chosen because this program binds no handler for it -- its `EVALUATE EIBAID`
   * at `app/cbl/COUSR03C.cbl` L108-L130 enumerates Enter, PF3, PF4, PF5 and PF12 and nothing else --
   * so it falls to the `WHEN OTHER` arm at L126-L129. It is a SCREEN-specific choice and not the
   * shared aliasing contract: the PF13-to-PF24 collapse and the unbound Clear, PA1 and PA2 identifiers
   * are properties of `ui/src/layout/usePfKeys.ts` and are asserted by that module's own suite, not
   * restated here.
   */
  await pressPfKey(user, 'PFK07');

  /*
   * Assumptions: the sentence is `CCDA-MSG-INVALID-KEY`, which L128 MOVEs and which
   * `app/cpy/CSMSG01Y.cpy` L20-L21 declares as `PIC X(50)`. The declared width is asserted separately
   * from the content because the two are different contracts -- the width is the field the terminal
   * reserved, the content is what fills it -- and the band is matched on the content with its
   * right-hand padding removed, since the padding is the declaration and not the message.
   */
  await waitForBandedMessage(COMMON_MESSAGES.INVALID_KEY.text.trimEnd());
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
}

/**
 * The reference's own dataset name never reaches the browser.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function neverSurfacesTheReferenceDatasetName(): Promise<void> {
  await renderWithTheRowFetched();

  /*
   * Assumptions: `app/cbl/COUSR03C.cbl` L39 declares `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '` --
   * two trailing spaces inside an eight-character field -- and passes it as the `DATASET` operand of
   * every file command at L270, L308. It is a server-side resource name and never screen content: the
   * mapset paints no field for it, and the target replaces the VSAM file with the `auth.users` table,
   * so a name appearing in the browser would be an information leak about storage rather than a
   * carried-across string. Asserted as an absence, with the stem matched so that the padded and
   * unpadded forms are both caught.
   */
  expect(screen.queryByText(/USRSEC/)).not.toBeInTheDocument();
}

/**
 * Registers the cases covering every sentence this screen's program moves.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function verbatimSentenceCases(): void {
  it('refuses a blank key on the read stage', refusesABlankKeyOnTheReadStage);
  it(
    'refuses a blank key on the delete stage, from the same catalog entry',
    refusesABlankKeyOnTheDeleteStage,
  );
  it('reports a read that finds no row', reportsAReadThatFindsNoRow);
  it('reports a read that fails for any other reason', reportsAReadThatFailsOtherwise);
  it('reports a deletion that finds no row', reportsADeletionThatFindsNoRow);
  it(
    'reports a deletion that fails otherwise, preserving the reference\'s "Update" wording',
    reportsADeletionThatFailsOtherwise,
  );
  it('reports a key the program does not bind', reportsAKeyTheProgramDoesNotBind);
  it('never surfaces the reference dataset name', neverSurfacesTheReferenceDatasetName);
}

describe('delete-user screen: the sentences its program moves, verbatim', verbatimSentenceCases);

/**
 * The four descriptors the mapset's row-24 literal advertises, in the order it paints them.
 *
 * ⚠️ Assumptions: FOUR and not five. `app/bms/COUSR03.bms` L148 paints one complete, un-continued
 * literal -- `ENTER=Fetch  F3=Back  F4=Clear  F5=Delete` -- in the `LENGTH=58` field at L144-L147,
 * with two spaces between each pair. Three of the four come from the catalog's per-mapset group and
 * `F4=Clear` from `UNIFORM_PF_KEY_LABELS`, which owns the wording that is byte-identical across every
 * measured legend; assembling them here rather than retyping the literal keeps this file free of a
 * fifth transcription of a mapset string.
 */
const ADVERTISED_KEY_DESCRIPTORS: readonly string[] = [
  USER_DELETE_KEY_LABELS.ENTER,
  USER_DELETE_KEY_LABELS.PFK03,
  UNIFORM_PF_KEY_LABELS.PFK04,
  USER_DELETE_KEY_LABELS.PFK05,
];

/**
 * Reports the address the administrative menu occupies, read from the sanctioned route table.
 *
 * Assumptions: derived by finding the entry whose program is `COADM01C` rather than written as a
 * literal, so the destination this file asserts on is the one the application's own table declares. A
 * literal would keep passing after the table moved the menu.
 * @returns {string} The administrative menu's path pattern.
 * @throws {Error} If the route table declares no entry for the administrative menu program.
 */
function adminMenuAddress(): string {
  const entry = ROUTE_TABLE.find(
    /**
     * Matches the route table entry standing in for the administrative menu program.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One entry of the route table.
     * @returns {boolean} True when the entry replaces `COADM01C`.
     */
    function replacesTheAdminMenuProgram(candidate: (typeof ROUTE_TABLE)[number]): boolean {
      return candidate.program === 'COADM01C';
    },
  );
  if (entry === undefined) {
    throw new Error(
      'the route table declares no entry for COADM01C, so no back destination exists',
    );
  }
  return entry.path;
}

/** Text the probe standing in for the administrative menu renders when it is reached. */
const ADMIN_MENU_PROBE_TEXT = 'the administrative menu was reached';

/**
 * Stands in for the administrative menu, so a navigation away from this screen is observable.
 *
 * Assumptions: a probe rather than the real menu screen, because what is under test is the
 * DESTINATION this screen chooses and not what that destination renders. Mounting the real menu would
 * pull its own transport calls into these cases and make a failure there read as a failure here.
 * @returns {ReactElement} An element identifying the destination.
 */
function AdminMenuProbe(): ReactElement {
  return <div>{ADMIN_MENU_PROBE_TEXT}</div>;
}

/**
 * Renders the screen at its real route beside a probe standing in for its back destination.
 *
 * Assumptions: the harness's themed provider and memory router are reused and only the ROUTE TREE is
 * supplied here, which is what lets two routes coexist -- the shell-mounting helper accepts a single
 * child path. The shell is deliberately absent from these cases: `usePfKeys` installs its own keydown
 * listener, so every key still dispatches without the frame, and these cases assert a destination
 * rather than a painted zone.
 * @param {string} [userId] - Identifier to select, defaulting to the administered row.
 * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
 */
async function renderWithANavigationProbe(
  userId: string = ADMINISTERED_USER_ID,
): Promise<HarnessRenderResult> {
  return await renderWithProviders(
    <Routes>
      <Route path={USER_DELETE_PATH} element={<UserDeleteScreen />} />
      <Route path={adminMenuAddress()} element={<AdminMenuProbe />} />
    </Routes>,
    { initialEntries: [deleteAddressFor(userId)] },
  );
}

/**
 * Waits until the navigation probe is on screen.
 * @returns {Promise<void>} Resolves once the destination has been reached.
 */
async function waitForTheAdminMenuProbe(): Promise<void> {
  await waitFor(
    /**
     * Asserts the probe standing in for the administrative menu is rendered.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theProbeIsRendered(): void {
      expect(screen.getByText(ADMIN_MENU_PROBE_TEXT)).toBeInTheDocument();
    },
  );
}

/**
 * Reports the legend's activatable controls.
 * @returns {readonly HTMLElement[]} The controls the legend renders, in render order.
 */
function legendControls(): readonly HTMLElement[] {
  return within(shellLandmark('keyLegend')).getAllByRole('button');
}

/**
 * The legend advertises exactly the four descriptors the mapset paints, and no fifth.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function advertisesExactlyTheFourDescriptorsTheMapsetPaints(): Promise<void> {
  await renderUnselected();

  const names = legendControls().map(
    /**
     * Reports one control's accessible name.
     * @param {HTMLElement} control - A legend control.
     * @returns {string} Its trimmed text.
     */
    function accessibleNameOf(control: HTMLElement): string {
      return (control.textContent ?? '').trim();
    },
  );

  expect(names).toStrictEqual([...ADVERTISED_KEY_DESCRIPTORS]);

  /*
   * ⚠️⚠️ Trade-offs: `DFHPF12` is BOUND at `app/cbl/COUSR03C.cbl` L123-L125 and is NOT advertised by
   * the row-24 literal at `app/bms/COUSR03.bms` L148, and both halves of that mismatch are preserved
   * exactly as measured. Two alternatives were rejected. ADDING an `F12` descriptor to the legend
   * would alter a user-visible string that AAP transformation rule T8 protects character-for-character.
   * UNBINDING PF12 would remove behaviour the reference has, on the one screen where being unable to
   * leave matters most. Preserving both changes neither, at the cost of a legend that under-reports
   * its own screen by one key -- which is the reference's cost, not this migration's.
   * Assumptions: this is the exact INVERSE of the sibling add-user screen, where `COUSR01.bms`
   * advertises `F12=Exit` while `COUSR01C` binds no PF12 handler at all. Two adjacent administrative
   * screens drifting in opposite directions is strong evidence that legend text and handler tables
   * were maintained separately in the baseline, so neither may be "harmonised" against the other, and
   * the pair belongs in `docs/architecture/cobol-to-service-traceability.md` as a documented
   * divergence-free observation.
   */
  expect(names).toHaveLength(4);
  expect(names.join(' ')).not.toMatch(/F12/);
}

/**
 * The advertised controls carry the emphasis the design-system mapping fixes.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function carriesTheMappedEmphasisOnItsAdvertisedControls(): Promise<void> {
  await renderUnselected();

  /*
   * Assumptions: the pairing is asserted against `PRIMARY_ACTION_AIDS`, which `ui/src/layout/PfKeyBar.tsx`
   * publishes as the single statement of AAP section 0.3.2's mapping -- primary emphasis for Enter and
   * PF5, the default for the rest. Asserting through the constant rather than restating the two AIDs
   * means a change to the mapping is a change in one place, and it keeps this case from becoming a
   * second opinion about the design system.
   */
  expect([...PRIMARY_ACTION_AIDS]).toStrictEqual(['ENTER', 'PFK05']);

  const controls = legendControls();
  const [enterControl, backControl, clearControl, deleteControl] = controls;

  /*
   * Assumptions: the four are read positionally because the previous case has already pinned the order
   * to the mapset's own left-to-right sequence, so an index here is the mapset's position rather than
   * an arbitrary one.
   */
  expect(enterControl).toHaveClass('ant-btn-primary');
  expect(deleteControl).toHaveClass('ant-btn-primary');
  expect(backControl).toHaveClass('ant-btn-default');
  expect(clearControl).toHaveClass('ant-btn-default');
}

/**
 * The clear key empties the identifier and the displayed record, from key and from control alike.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function clearsTheKeyAndTheDisplayedRecord(): Promise<void> {
  const { user } = await renderWithTheRowFetched();

  await pressPfKey(user, 'PFK04');

  await waitFor(
    /**
     * Asserts the identifier control and the displayed first name are both empty.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function everythingIsEmpty(): void {
      expect(fetchKeyControl()).toHaveValue('');
      expect(within(recordView()).queryByText(STORED_USER.firstName)).not.toBeInTheDocument();
    },
  );

  /*
   * Assumptions: PF4 blanks FOUR targets and not three. `CLEAR-CURRENT-SCREEN` at
   * `app/cbl/COUSR03C.cbl` L341-L344 performs `INITIALIZE-ALL-FIELDS`, which at L349-L356 moves spaces
   * to `USRIDINI`, `FNAMEI`, `LNAMEI` and `USRTYPEI` together with the message -- so the fetch key is
   * cleared along with the record it fetched, and a clear that left the key behind would be a
   * different operation.
   */
  await user.type(fetchKeyControl(), ADMINISTERED_USER_ID);
  await user.click(advertisedControl(2));

  /*
   * Alternatives Considered: asserting only the keyboard path, which is the fidelity-bearing one --
   * the 3270 had no pointer, so the key press is what the reference actually does. Rejected because
   * the legend control is the affordance a browser operator reaches for first, and a bar wired to a
   * different handler than its key would leave the visible control silently inert. Both paths are
   * exercised so that neither can regress alone.
   */
  await waitFor(
    /**
     * Asserts the pointer path cleared the identifier too.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theKeyIsEmptyAgain(): void {
      expect(fetchKeyControl()).toHaveValue('');
    },
  );
}

/**
 * The back key returns to the administrative menu, writing nothing.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function returnsToTheAdministrativeMenuOnBack(): Promise<void> {
  readSpy().mockResolvedValue(STORED_USER);
  const { user } = await renderWithANavigationProbe();

  await pressPfKey(user, 'PFK03');
  await waitForTheAdminMenuProbe();

  /*
   * Assumptions: the destination is the ADMINISTRATIVE menu and not the main menu, because this screen
   * is only reachable from there -- `app/cbl/COUSR03C.cbl` L112-L117 falls back to `'COADM01C'` when
   * no caller origin is carried, and `app/cpy/COADM02Y.cpy` L43 lists this screen as an administrative
   * option. `EXEC CICS XCTL` becomes a client-side route change under AAP transformation rule T5, so
   * the assertion is that a route was entered and not that a program was transferred to.
   * Assumptions: PF3 WRITES NOTHING here, and that is worth stating because the adjacent update screen
   * gives the same key a different meaning -- `COUSR02.bms` advertises `F3=Save&&Exit`, which commits.
   * Two neighbouring administrative screens, one key, two meanings: the semantics are per-screen and
   * must not be generalised from a sibling.
   */
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * The unadvertised key is nonetheless reachable from the keyboard, as the program has it.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function reachesTheAdministrativeMenuOnTheUnadvertisedKey(): Promise<void> {
  readSpy().mockResolvedValue(STORED_USER);
  const { user } = await renderWithANavigationProbe();

  await pressPfKey(user, 'PFK12');
  await waitForTheAdminMenuProbe();

  /*
   * ⚠️ Assumptions: PF12 works even though no control advertises it, because that is precisely what
   * `app/cbl/COUSR03C.cbl` L123-L125 does -- it moves `'COADM01C'` unconditionally and returns. The
   * 3270 original was keyboard-only, so an unadvertised binding was genuinely reachable by an operator
   * who knew it was there, and dropping it would remove a working escape from a destructive screen.
   * Assumptions: its destination is UNCONDITIONAL where PF3's is not. L124 hard-codes the menu, while
   * the PF3 arm at L112-L117 prefers a carried caller origin and only falls back to it -- so the two
   * keys agree here, where no origin was supplied, and would diverge for an operator arriving from the
   * browse.
   */
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * Reports one advertised legend control by the position the mapset paints it at.
 * @param {number} position - Zero-based index into the row-24 legend's left-to-right order.
 * @returns {HTMLElement} The control at that position.
 * @throws {Error} If the legend renders no control there, naming the position asked for.
 */
function advertisedControl(position: number): HTMLElement {
  const control = legendControls()[position];
  if (control === undefined) {
    throw new Error(`the legend renders no control at position ${String(position)}`);
  }
  return control;
}

/**
 * Each advertised control dispatches the action its key dispatches.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function dispatchesFromALegendControlAsFromItsKey(): Promise<void> {
  const { user } = await renderWithTheRowFetched();

  /*
   * Alternatives Considered: asserting the keyboard path alone, which is the fidelity-bearing one --
   * the 3270 had no pointer, so every action in the reference is a keystroke and `pressPfKey` is what
   * reproduces it. Rejected because the legend control is the affordance a browser operator reaches
   * for first, and a bar wired to a different handler than its key would leave the visible control
   * silently inert while every keyboard case still passed. Both paths are therefore exercised for each
   * advertised key: Enter and PF4 re-run their whole action, and PF5 is checked as far as the
   * confirmation it must open rather than through to the deletion, since the accepting control is the
   * only path to that and it is asserted separately.
   */
  await user.click(advertisedControl(0));
  await waitFor(
    /**
     * Asserts the pointer path issued a second read, as the Enter key does.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function aSecondReadWasIssued(): void {
      expect(readSpy()).toHaveBeenCalledTimes(2);
    },
  );

  await user.click(advertisedControl(3));
  await waitForTheConfirmation();
  expect(deleteSpy()).not.toHaveBeenCalled();
  await user.click(confirmationDismissControl());

  /*
   * Assumptions: the back control is exercised LAST, because it leaves the route -- so the screen
   * unmounts and nothing after it could be asserted. Its effect is observed as the enterable control
   * ceasing to exist, which is what a client-side route change looks like from inside the tree that
   * was replaced: `app/cbl/COUSR03C.cbl` L118 performs `RETURN-TO-PREV-SCREEN`, whose `EXEC CICS XCTL`
   * at L205-L208 transfers away and never returns to this program.
   */
  await user.click(advertisedControl(1));
  await waitFor(
    /**
     * Asserts the screen has left the route.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theScreenHasLeft(): void {
      expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    },
  );
}

/**
 * Registers the cases covering the legend and the five attention identifiers.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function attentionIdentifierCases(): void {
  it(
    'advertises exactly the four descriptors the mapset paints',
    advertisesExactlyTheFourDescriptorsTheMapsetPaints,
  );
  it(
    'carries the mapped emphasis on its advertised controls',
    carriesTheMappedEmphasisOnItsAdvertisedControls,
  );
  it('clears the key and the displayed record on PF4', clearsTheKeyAndTheDisplayedRecord);
  it(
    'dispatches from a legend control exactly as from its key',
    dispatchesFromALegendControlAsFromItsKey,
  );
  it('returns to the administrative menu on PF3', returnsToTheAdministrativeMenuOnBack);
  it(
    'reaches the administrative menu on the unadvertised PF12',
    reachesTheAdministrativeMenuOnTheUnadvertisedKey,
  );
}

describe('delete-user screen: five identifiers bound, four advertised', attentionIdentifierCases);

/**
 * The denial sentence as the document carries it, with the declared padding removed.
 *
 * Assumptions: `app/cpy/COADM02Y.cpy`-era screens declare their messages at a fixed width, and
 * `'No access - Admin Only option... '` at `app/cbl/COMEN01C.cbl` L140 carries a trailing space that is
 * padding rather than content. The guard renders the value verbatim and the DOM collapses the trailing
 * run for display, so the expectation is trimmed while the catalog entry stays the source of it -- a
 * change to the baseline wording still fails here.
 */
const ACCESS_DENIED_TEXT = ACCESS_DENIED_ADMIN_ONLY.trim();

/**
 * Renders the application's real route table at one address.
 *
 * ⚠️ Assumptions: the REAL route objects are used, so these cases traverse the actual guard, the actual
 * lazy boundary and the actual frame rather than a reconstruction of them. That is the whole point:
 * authority on this route is declared by `ui/src/router.tsx` and enforced by the `RequireAdmin` wrapper
 * it applies, so a test that rebuilt the tree could assert a policy the application does not run.
 * Trade-offs: the themed provider is deliberately absent from these two cases, because a data router
 * cannot be nested inside the harness's own memory router. It costs nothing here -- neither case
 * asserts a design value, only which of two trees renders and whether the transport was reached -- and
 * every case in this file that does assert a rendered design value uses the themed helper.
 * @param {string} address - The address to open.
 * @returns {void} Nothing; the tree is rendered into the document.
 */
function renderRealRouteTableAt(address: string): void {
  const router = createMemoryRouter([...CARD_DEMO_ROUTES], { initialEntries: [address] });
  render(<RouterProvider router={router} />);
}

/**
 * The route table declares this path administrative.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function declaresThePathAdministrative(): void {
  const entry = ROUTE_TABLE.find(
    /**
     * Matches the route table entry for the delete-user path.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One entry of the route table.
     * @returns {boolean} True when the entry is this screen's.
     */
    function isTheDeleteUserRoute(candidate: (typeof ROUTE_TABLE)[number]): boolean {
      return candidate.path === USER_DELETE_PATH;
    },
  );

  /*
   * Assumptions: `app/cpy/COADM02Y.cpy` L43 names this option `'User Delete (Security)             '`
   * in the administrative option table, whose `CDEMO-ADMIN-OPT-COUNT` is 6 at L22, and its
   * `OCCURS 9 TIMES` entry at L55-L59 carries NO user-type field -- because in the reference, reaching
   * the administrative menu at all is the authorization. The migrated form makes that explicit rather
   * than positional: the route declares its own access level, and the guard reads it.
   */
  expect(entry).toStrictEqual({
    path: USER_DELETE_PATH,
    access: 'administrative',
    program: 'COUSR03C',
  });
}

/**
 * An administrator reaches the screen and may read the row.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function admitsAnAdministrator(): Promise<void> {
  readSpy().mockResolvedValue(STORED_USER);
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });

  renderRealRouteTableAt(deleteAddressFor(ADMINISTERED_USER_ID));

  await waitFor(
    /**
     * Asserts the read reached the transport with the administered identifier.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theRowWasRead(): void {
      expect(readSpy()).toHaveBeenCalledWith(ADMINISTERED_USER_ID);
    },
  );

  expect(screen.queryByText(ACCESS_DENIED_TEXT)).not.toBeInTheDocument();
}

/**
 * A signed-on non-administrator is refused, and the transport is never reached.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function refusesANonAdministratorWithoutTouchingTheTransport(): Promise<void> {
  await seedSession({ groups: [CARDDEMO_USER_GROUP] });

  renderRealRouteTableAt(deleteAddressFor(ADMINISTERED_USER_ID));

  await waitFor(
    /**
     * Asserts the refusal is rendered.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theRefusalIsRendered(): void {
      expect(screen.getByText(ACCESS_DENIED_TEXT)).toBeInTheDocument();
    },
  );

  /*
   * ⚠️⚠️ Assumptions: NEITHER operation is reached -- not the read and not the deletion. The negative
   * assertion matters more on this screen than on any read-only one, because the operation behind the
   * guard irreversibly removes an identity: a guard that rendered a refusal while the route's own data
   * fetch had already run would leak the record it was refusing access to, and one that let the delete
   * through would destroy it.
   * Refactoring Rationale: a test cannot grant itself this authority, and the arrangement is what
   * proves the migration closed a real hole. In the reference, `CDEMO-USER-TYPE PIC X(01)` at
   * `app/cpy/COCOM01Y.cpy` L26 with its `'A'`/`'U'` condition names at L27-L28 travelled in the
   * `DFHCOMMAREA` -- storage the client echoed back -- so a client could in principle assert its own
   * type. In the target the only input is the signed `cognito:groups` claim: `ui/src/hooks/useAuth.ts`
   * publishes the two group names and the claim name but deliberately no setter, and there is no
   * context provider anywhere to wrap. So the session here is established by minting a token and
   * driving the real sign-on exchange, which is the only path to authority that exists.
   */
  expect(readSpy()).not.toHaveBeenCalled();
  expect(deleteSpy()).not.toHaveBeenCalled();
}

/**
 * The route parameter is what selects the row, and it reaches the transport.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function passesTheRouteParameterThroughToTheTransport(): Promise<void> {
  const otherUserId = 'USER0042';
  readSpy().mockResolvedValue({ ...STORED_USER, userId: otherUserId });

  await renderSelected(otherUserId);

  await waitFor(
    /**
     * Asserts the read was issued for the identifier the address carried.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theParameterReachedTheTransport(): void {
      expect(readSpy()).toHaveBeenCalledWith(otherUserId);
    },
  );

  /*
   * Assumptions: the parameter is named `id` -- `ui/src/router.tsx` declares `'/users/:id/delete'` --
   * and it also PRE-POPULATES the enterable control, which reconciles the two ways this screen can be
   * given a row. That is the migrated form of `app/cbl/COUSR03C.cbl` L101-L103, where a populated
   * selection carrier is moved into `USRIDINI` and the read performed at once, so an operator arriving
   * from the browse sees the key they selected rather than an empty field. AAP section 0.7.1 turns that
   * carrier into a request parameter and never a session field, which is why a second identifier is
   * used here: a default would pass whether the parameter flowed through or not.
   */
  expect(fetchKeyControl()).toHaveValue(otherUserId);
  expect(readSpy()).toHaveBeenCalledTimes(1);
}

/**
 * Nothing on the screen or in its response is a credential or a protected identifier.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function carriesNoCredentialAndNoProtectedIdentifier(): Promise<void> {
  await renderWithTheRowFetched();

  /*
   * ⚠️ Assumptions: the body paints exactly ONE input element and its type is textual, which is how a
   * masked control is excluded without naming one. `app/cpy-bms/COUSR03.CPY` declares no credential
   * field family at all and `app/bms/COUSR03.bms` sets no `DRK` attribute anywhere, so unlike the
   * sign-on, add-user and update-user screens this mapset has no non-display field to carry across --
   * the absence is the source's shape, not a redaction applied here. The reference does hold
   * `05 SEC-USR-PWD PIC X(08).` in plain text at `app/cpy/CSUSR01Y.cpy` L21, and AAP sections 0.5.1.2
   * and 0.7.8 decline parity on it: `auth.users` carries no such column, so there is nothing to render
   * and nothing to send.
   */
  const inputs = shellLandmark('screenBody').querySelectorAll('input');
  expect(inputs).toHaveLength(1);
  expect(inputs.item(0)?.getAttribute('type')).toBe('text');

  /*
   * Assumptions: the response contract is asserted as an exact key set rather than by probing for
   * absent members, because an exact set also fails when a member is ADDED. `UserResponse` declares
   * five members and no card number, national identifier, government-issued identifier or verification
   * value is among them -- `ui/src/api/types.ts` records that it declares no verification-value field
   * anywhere in the module -- so none of them can reach this screen even by accident.
   */
  expect(Object.keys(STORED_USER).sort()).toStrictEqual([
    'cognitoSub',
    'firstName',
    'lastName',
    'userId',
    'userType',
  ]);

  /*
   * Assumptions: the subject reference is a contract member the screen receives and must not PAINT --
   * it identifies an account in the identity provider and has no counterpart on the 3270 screen, whose
   * mapset paints four data fields and no fifth.
   */
  expect(screen.queryByText(STORED_USER.cognitoSub)).not.toBeInTheDocument();
}

/**
 * A refusal marks the one enterable control, and never the protected fields.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function marksOnlyTheEnterableControlOnARefusal(): Promise<void> {
  const { user } = await renderUnselected();

  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Asserts the identifier control is marked as refused.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    function theControlIsMarked(): void {
      expect(fetchKeyControl()).toHaveAttribute('aria-invalid', 'true');
    },
  );

  /*
   * Assumptions: the blank case additionally shows a literal marker, which is
   * `app/cpy/CSSETATY.cpy` L17-L27 carried across: that templated copybook moves `DFHRED` into a
   * field's colour attribute when its validation flag is not-OK or blank, and at L24 additionally
   * moves a literal `'*'` into the field when it is BLANK. The target renders the colour half as
   * `Form.Item validateStatus="error"` with help text and keeps the marker for the blank arm only, so
   * the two conditions the copybook distinguishes stay distinguishable. The marker is read from
   * `FIELD_ERROR_TOKENS` rather than written as a literal.
   */
  expect(screen.getByText(FIELD_ERROR_TOKENS.blankMarker)).toBeInTheDocument();

  /*
   * Refactoring Rationale: the reference gates that highlight on `CDEMO-PGM-REENTER`
   * (`app/cpy/COCOM01Y.cpy` L29-L31), so the field only reddened on a re-entered screen turn. AAP
   * section 0.7.1 removes the discriminator entirely, which means the marking here is driven purely by
   * the response rather than by a remembered turn count -- a stateless handler has no first-entry
   * versus re-entry distinction to make, and the styling is the better for it: the refusal shows on the
   * turn that caused it.
   */
  expect(within(recordView()).queryAllByRole('textbox')).toHaveLength(0);
  expect(recordView().querySelectorAll('.ant-form-item-has-error')).toHaveLength(0);
}

/**
 * Registers the cases covering authority, selection, data exposure and field-level refusal.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function authorityAndRefusalCases(): void {
  it('declares its path administrative in the route table', declaresThePathAdministrative);
  it('admits an administrator and reads the row', admitsAnAdministrator);
  it(
    'refuses a signed-on non-administrator without reaching the transport',
    refusesANonAdministratorWithoutTouchingTheTransport,
  );
  it(
    'passes the route parameter through to the transport',
    passesTheRouteParameterThroughToTheTransport,
  );
  it(
    'carries no credential and no protected identifier',
    carriesNoCredentialAndNoProtectedIdentifier,
  );
  it('marks only the enterable control on a refusal', marksOnlyTheEnterableControlOnARefusal);
}

describe(
  'delete-user screen: authority, selection, exposure and refusal',
  authorityAndRefusalCases,
);
