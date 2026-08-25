/**
 * @file Component tests for the add-user screen, the migration target of BMS mapset `COUSR01` (map
 * `COUSR1A`) and program `app/cbl/COUSR01C.cbl`, mounted by `ui/src/router.tsx` at `/users/new`.
 *
 * Purpose
 * -------
 * Hold the whole observable contract of `ui/src/screens/userAdd/index.tsx` declared: the field widths
 * the mapset and the record copybook agree on, the single initial-cursor position, the two-character
 * user-type domain, every sentence the program emits, the three attention identifiers it dispatches and
 * the one its own legend advertises but it refuses, the administrative gate above the route, and the
 * per-field refusal marks the target renders in place of the terminal's colour attribute. It is also
 * the only place the credential decision on this screen is held: the mapset paints a credential control
 * and the migration withdrew it, and a screen that quietly grew one back would satisfy no other check
 * in this tree.
 *
 * ⚠️ This test is the only verification this screen gets. `tests/README.md` section 1.1 states it
 * without hedging -- the online `CO*` CICS programs cannot run end to end without a CICS runtime, which
 * is absent on the runner, so only their extractable field-validation logic is unit-tested. The COBOL
 * parity oracle under `tests/**` is a batch oracle: there is NO golden master for `COUSR01C`, no
 * recorded 3270 datastream to diff against, and no end-to-end run that would notice a lost sentence.
 * Every expectation below is therefore anchored to a measured line of the reference source or to the
 * catalog that transcribes it, because nothing downstream will catch what this file lets through.
 *
 * Parameters
 * ----------
 * Not applicable. This module declares test cases and accepts no inputs of its own. What varies between
 * cases is declared as a module-level fixture below.
 *
 * Return values
 * -------------
 * Not applicable. Each case reports through its own expectations; the module exports nothing.
 *
 * Exceptions or errors
 * --------------------
 * None are raised at module scope. Three cases hand the screen a REJECTED promise on purpose, and each
 * is consumed by the screen's own failure arm within the case that queued it, so no rejection outlives
 * a case as an unhandled one.
 *
 * Documentation obligation
 * ------------------------
 * Rule 1 (Explainability) requires a docstring on every module entry point, function and class stating
 * purpose, parameters, return values and any exceptions, plus an inline comment justifying each
 * non-obvious decision under one of `Alternatives Considered:`, `Refactoring Rationale:`,
 * `Assumptions:` or `Trade-offs:`. `tests/README.md` section 12 places the identical obligation on
 * "every new test, fixture builder, helper, mock, and runner routine" and calls it a hard review gate.
 * The two AGREE, so nothing here is a new convention -- this file extends an established house one to a
 * TypeScript screen test, in the form `docs/CODE_DOCUMENTATION_STANDARD.md` fixes.
 *
 * Assumptions: the labels are written PLURAL and a statement-level restatement comment appears nowhere
 * below the block you are reading. `config/rule1/rule1_gate.py` treats `Assumption:` and `Trade-off:` as
 * prohibited singular variants, and it permits a restatement label only inside a file's leading header
 * block; `docs/CODE_DOCUMENTATION_STANDARD.md` states the same at its label section. That gate passes
 * repository-wide, so either form would be a regression this file introduced rather than a style
 * preference it expressed.
 */

/*
 * Assumptions: every test API is imported from 'vitest' by NAME rather than taken from an ambient
 *   global. `ui/vitest.config.ts` does set `globals: true`, but `ui/tsconfig.json` sets `"types": []`,
 *   which suppresses automatic inclusion of every installed `@types` package -- so nothing declares
 *   `describe`, `it`, `expect` or `vi` to the compiler and an omitted import fails `tsc --noEmit` on
 *   the symbol it omitted. That file states the obligation in those words at its `types` entry, and all
 *   sixteen sibling screen tests are written this way.
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../api/auth';
import type { ApiError, CreateUserRequest, CreatedUserResponse, UserType } from '../api/types';

/**
 * Stands in for the one identity write this screen issues.
 *
 * ⚠️ Assumptions: the spy is TYPED with the transport's own signature rather than left as a bare
 * `vi.fn()`, and that is what lets {@link submittedRequest} return a `CreateUserRequest` with no cast
 * anywhere in this file. `ui/src/api/types.ts` declares `userType` on that interface as the `'A' | 'U'`
 * union, so a screen that widened the value to `string` could not satisfy this signature and the failure
 * would be a compile error rather than a runtime string comparison a widened screen would still pass.
 * Alternatives Considered: an untyped spy read through `mock.calls[0][0]` and cast to the request type.
 * Rejected because such a cast compiles against any shape at all, which moves the domain guarantee out
 * of the type system exactly where the type system is the cheaper of the two checks.
 *
 * Assumptions: no default implementation is installed. Every case queues the answer it needs, so a case
 * that forgot to queue one fails rather than passing against whatever the previous case installed.
 */
const createUserMock = vi.fn<(request: CreateUserRequest) => Promise<CreatedUserResponse>>();

vi.mock(
  '../api/auth',
  /**
   * Replaces the create this screen issues while leaving every other export of the module intact.
   *
   * Assumptions: a PARTIAL substitution, never a bare replacement. The screen reads
   * `USER_ID_MAX_LENGTH` from this module for the identifier control's `maxLength`, and
   * `ui/src/hooks/useAuth.ts` -- which `ui/src/layout/AppShell.tsx` and `ui/src/routes/guards.tsx` both
   * reach -- reads `signOn`, `refreshTokens`, `answerSignOnChallenge` and `signOut` from it. A bare
   * replacement would size the identifier control `undefined` and leave the identity store holding
   * undefined operations, so the failures would report the substitution rather than the screen. It also
   * keeps the real sign-on path intact, which is what lets the identity helper establish a session by
   * driving a genuine exchange rather than by asserting a group.
   * @returns {Promise<typeof AuthModule>} The real module with the create substituted.
   */
  async (): Promise<typeof AuthModule> => {
    const actual = await vi.importActual<typeof AuthModule>('../api/auth');
    return { ...actual, createUser: createUserMock };
  },
);

/*
 * Assumptions: every module that can reach the substituted transport is imported DYNAMICALLY, below the
 *   factory. Vitest hoists `vi.mock` above the import block, so a static import would run the factory
 *   before `createUserMock` is initialised and fail on a temporal-dead-zone access naming a line in
 *   this file. The reach is not obvious for the two layout modules and was established rather than
 *   assumed: `ui/src/layout/AppShell.tsx` imports `ui/src/hooks/useAuth.ts`, which imports
 *   `../api/auth`, and `ui/src/routes/guards.tsx` imports the same hook.
 */
const {
  default: UserAddScreen,
  USER_ADD_CREDENTIAL_TEST_ID,
  USER_ADD_FIELD_WIDTHS,
} = await import('../screens/userAdd');
const { ApiRequestError } = await import('../api/client');
const { AppShell } = await import('../layout/AppShell');
const { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS, UNIFORM_PF_KEY_LABELS } =
  await import('../layout/PfKeyBar');
const { MESSAGE_BAND_TEST_ID } = await import('../layout/MessageBand');
const { USER_ADD_PATH } = await import('../router');
const { RequireAdmin, SIGN_ON_ROUTE } = await import('../routes/guards');
const { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } = await import('../hooks/useAuth');

/*
 * Assumptions: these four are imported STATICALLY because none of them can reach a transport.
 *   `ui/src/messages/messages.ts` has no imports at all, `ui/src/routes/navigation.ts` has only a type
 *   import, `ui/src/theme/tokens.ts` imports one antd type, and `ui/src/layout/fieldHelp.tsx` imports
 *   React and antd. Making them dynamic would suggest a dependency edge that does not exist and would
 *   put four more names behind a `const` a reader has to scroll past.
 */
import { BUSY_ANNOUNCEMENT_TEST_ID, fieldErrorId } from '../layout/fieldHelp';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  ADMIN_MENU_OPTIONS,
  COMMON_MESSAGES,
  CREDENTIAL_HANDOVER_MESSAGES,
  INVALID_KEY_PRESSED,
  MENU_OPTION_DECLARED_WIDTH,
  MESSAGE_BAND,
  MESSAGE_BAND_BY_MAPSET,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  USER_ADD_CAPTION,
  USER_ADD_FIELD_HINTS,
  USER_ADD_FIELD_LABELS,
  USER_ADD_KEY_LABELS,
} from '../messages/messages';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE } from '../routes/navigation';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import { apiError, conflictProblem, fieldError, pressPfKey, seedSession } from './setup';

/** Text a probe route paints, so a navigation away from the screen is observable. */
const ARRIVED = 'ARRIVED';

/**
 * The identifier the created row carries, at its declared eight-character width.
 *
 * Assumptions: eight characters exactly, because `SEC-USR-ID PIC X(08)` at `app/cpy/CSUSR01Y.cpy` L18
 * is the stored key's width and `USERIDI PIC X(8)` at `app/cpy-bms/COUSR01.CPY` L72 is the control's. A
 * shorter fixture would pass every case here while never exercising the width constraint against a
 * value that actually fills the field.
 */
const NEW_USER_ID = 'USER0009';

/** Given name to submit, inside the twenty characters `FNAMEI PIC X(20)` declares at L60. */
const NEW_FIRST_NAME = 'GRACE';

/** Family name to submit, inside the twenty characters `LNAMEI PIC X(20)` declares at L66. */
const NEW_LAST_NAME = 'HOPPER';

/**
 * The one-time credential the substituted service answers with.
 *
 * ⚠️ Assumptions: the value is deliberately NOT credential-shaped. It is the word "synthetic" plus a
 * label naming this file, so neither a repository secret scan nor a reader can mistake it for a real
 * credential. AAP section 0.9.1 makes "no secrets committed to the repository" a non-negotiable
 * constraint, and a plausible-looking password string in a committed test is exactly the pattern such a
 * scan exists to flag.
 *
 * Assumptions: it is still a value the cases can search the whole document and both Web Storage areas
 * for, which is the only property they need from it -- every negative assertion below looks for this
 * exact string, so an obviously fake value proves the same guarantee a realistic one would.
 */
const SYNTHETIC_ONE_TIME_CREDENTIAL = 'synthetic-not-a-credential-userAdd-test';

/**
 * The body the substituted create answers a stored write with.
 *
 * ⚠️ Assumptions: the shape is typed `CreatedUserResponse` rather than declared loosely, so the compiler
 * is what proves the response carries no credential member other than the one-time handover.
 * `ui/src/api/types.ts` declares that interface as `UserResponse` plus `credentialSecretName` and
 * `oneTimeCredential`, and `ui/tsconfig.json` sets `exactOptionalPropertyTypes` alongside `strict`, so
 * an excess property on an object literal of a declared type is a compile error -- a `password` added
 * here would fail `tsc --noEmit` rather than needing a runtime assertion somebody could delete.
 */
const CREATED_USER: CreatedUserResponse = {
  userId: NEW_USER_ID,
  firstName: NEW_FIRST_NAME,
  lastName: NEW_LAST_NAME,
  userType: 'A',
  cognitoSub: '00000000-0000-4000-8000-000000000009',
  credentialSecretName: `carddemo/dev/user/${NEW_USER_ID}`,
  oneTimeCredential: SYNTHETIC_ONE_TIME_CREDENTIAL,
};

/**
 * How one control is addressed and how wide the reference declares it.
 *
 * Assumptions: the label and the width are carried TOGETHER because the label is how a case reaches the
 * control and the width is what it then asserts about it, and holding them apart is what lets a table
 * assert one field's width against another field. The label is the accessible name, so it is the
 * mapset's own text with its padding trimmed -- `USER_ADD_FIELD_LABELS.userType` carries a trailing
 * space, which the mapset paints at `LENGTH=11` over ten characters of text, and an accessible-name
 * query does not match it.
 */
interface ControlUnderTest {
  /** Accessible name the control is queried by, trimmed of the mapset's padding. */
  readonly label: string;
  /** Width the symbolic map and the record copybook agree on, asserted as `maxLength`. */
  readonly declaredWidth: number;
}

/*
 * WHY : Assumptions: FOUR entries, and the fifth control the mapset paints is deliberately absent. Every
 *       width below is corroborated by two independent declarations that AGREE, which is transformation
 *       rule T1 -- the copybook is normative -- visible in one screen: `FNAMEI PIC X(20)` at
 *       `app/cpy-bms/COUSR01.CPY` L60 against `05 SEC-USR-FNAME PIC X(20).` at `app/cpy/CSUSR01Y.cpy`
 *       L19; `LNAMEI PIC X(20)` at L66 against `SEC-USR-LNAME PIC X(20)` at L20; `USERIDI PIC X(8)` at
 *       L72 against `SEC-USR-ID PIC X(08)` at L18, which `CDEMO-USER-ID PIC X(08)`
 *       (`app/cpy/COCOM01Y.cpy` L25) agrees with a third time; and `USRTYPEI PIC X(1)` at L84 against
 *       `SEC-USR-TYPE PIC X(01)` at L22. The agreement is the point: a width taken from the map alone
 *       could drift from the row it is stored in, and one taken from the record alone could drift from
 *       the field an operator types into.
 * WHY : Assumptions: these are the TERMINAL's own field widths -- a 3270 refused a keystroke past the end
 *       of a field with no message at all -- so `maxLength` is the faithful browser equivalent rather
 *       than a convenience, and each number below is a citation rather than a chosen bound.
 */
const CONTROLS_UNDER_TEST: readonly ControlUnderTest[] = [
  { label: USER_ADD_FIELD_LABELS.firstName, declaredWidth: 20 },
  { label: USER_ADD_FIELD_LABELS.lastName, declaredWidth: 20 },
  { label: USER_ADD_FIELD_LABELS.userId, declaredWidth: 8 },
  { label: USER_ADD_FIELD_LABELS.userType.trim(), declaredWidth: 1 },
];

/**
 * One recorded baseline site, as the message catalog's provenance index carries it.
 *
 * Assumptions: the shape is declared here rather than imported because the catalog exposes its
 * provenance entries only through `as const` literals, whose types are narrower than this and assignable
 * to it. Declaring the wider shape is what lets one predicate read an entry from either index.
 */
interface RecordedSite {
  /** Repository-relative path of the reference file. */
  readonly file: string;
  /** Lines of that file which emit the sentence. */
  readonly lines: readonly number[];
}

/**
 * Reproduces the pathless gate route the application declares above its six administrative paths.
 *
 * Assumptions: the guard wraps an `Outlet` rather than the screen directly, which is the shape
 * `ui/src/router.tsx` builds -- one guard over a subtree of six paths, not six guards. Wrapping the
 * screen element instead would test a composition the router never assembles, and it is the subtree form
 * that makes the refusal a property of the ROUTE rather than of the screen.
 * @returns {ReactElement} The administrative gate over the route's outlet.
 */
function AdministrativeSubtree(): ReactElement {
  return (
    <RequireAdmin>
      <Outlet />
    </RequireAdmin>
  );
}

/**
 * Renders the screen inside the frame it is mounted in, optionally behind the administrative gate.
 *
 * Purpose
 * -------
 * `ui/src/router.tsx` mounts this screen as a child of a pathless layout route rendering `AppShell`,
 * itself inside a pathless route rendering `RequireAdmin` over an `Outlet`. The screen composes none of
 * the three persistent zones -- it delegates the title band, the row-23 message line and the row-24
 * legend to the shell through `useShellSlot` -- so a bare render paints no legend and no band at all,
 * and every key or message query then fails on a screen that is in fact correct.
 *
 * Assumptions: the operator is created BEFORE the render, which is the ordering
 * `@testing-library/user-event` documents: setup installs the clipboard and pointer state the instance
 * uses, and doing it after a render has already dispatched events puts the instance behind the document
 * it is driving. Returning it is also what gives the cases a genuine `UserEvent` to hand to
 * {@link pressPfKey}, which the library's direct API is not.
 *
 * Assumptions: the gate is OPTIONAL and off by default. Most cases here are about the screen rather than
 * about who may reach it, and mounting the gate for those would make every one of them arrange a session
 * first -- turning a field-width assertion into an assertion about the sign-on exchange as well. The
 * gating cases turn it on, which is the arrangement the route table actually builds.
 *
 * Assumptions: three probe routes are declared and no single key should reach more than one. The
 * administrative menu probe is where the reference's PF3 arm goes -- `app/cbl/COUSR01C.cbl` L93-L95
 * moves `'COADM01C'` into `CDEMO-TO-PROGRAM` -- the main-menu probe is the destination most PF3 arms in
 * this application use and this one must NOT, and the sign-on probe exists so that a session end has
 * somewhere observable to land, which is the only way to show that PF12 does not end the session.
 * Alternatives Considered: the render helpers in `ui/src/test/setup.ts`, which mount exactly one route.
 * Rejected because a navigation assertion needs a destination to arrive AT, and with one route the only
 * observable outcome is that the screen disappeared -- which a crashed render produces too.
 * @param {boolean} [guarded] - Whether to mount the administrative gate above the screen, as the route
 *   table does; omitted, the screen is rendered without it.
 * @returns {UserEvent} The keyboard and pointer operator to drive the rendered screen with.
 */
function renderUserAdd(guarded: boolean = false): UserEvent {
  const user = userEvent.setup();
  const screenElement = <UserAddScreen />;

  render(
    <MemoryRouter initialEntries={[USER_ADD_PATH]}>
      <Routes>
        <Route element={<AppShell />}>
          {guarded ? (
            <Route element={<AdministrativeSubtree />}>
              <Route path={USER_ADD_PATH} element={screenElement} />
            </Route>
          ) : (
            <Route path={USER_ADD_PATH} element={screenElement} />
          )}
          <Route path={ADMIN_MENU_ROUTE} element={<div>{`${ARRIVED} ${ADMIN_MENU_ROUTE}`}</div>} />
          <Route path={MAIN_MENU_ROUTE} element={<div>{`${ARRIVED} ${MAIN_MENU_ROUTE}`}</div>} />
          <Route path={SIGN_ON_ROUTE} element={<div>{`${ARRIVED} ${SIGN_ON_ROUTE}`}</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  return user;
}

/**
 * Returns one control by the mapset label the screen binds to it.
 * @param {string} label - Verbatim label text, trimmed of the mapset's declared padding.
 * @returns {HTMLElement} The control that label names.
 * @throws {Error} If no control carries that accessible name, which Testing Library raises. Absence
 *   usually means the label constant and the screen's own `htmlFor` binding have diverged.
 */
function control(label: string): HTMLElement {
  return screen.getByLabelText(label);
}

/**
 * Returns one row-24 legend control by the caption the frame paints on it.
 *
 * Assumptions: the control is found INSIDE the legend region rather than by name across the document,
 * because the screen's own body also renders controls -- the credential handover surface carries a copy
 * control and a dismissal -- so a document-wide query would become ambiguous the moment a caption were
 * shared.
 * @param {string} label - The verbatim row-24 caption the control renders.
 * @returns {HTMLElement} The legend control carrying that caption.
 * @throws {Error} If the legend published no control with that caption, which Testing Library raises.
 */
function legendControl(label: string): HTMLElement {
  return within(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).getByRole(
    'button',
    { name: label },
  );
}

/**
 * Wraps one problem document in the failure class the transport raises, so the screen classifies it.
 *
 * Assumptions: the failure is built from the transport's OWN class rather than from a hand-rolled object
 * carrying a `problem` member. `ui/src/api/client.ts` narrows on the class with `isApiRequestError` and
 * `isConflictFailure`, so a look-alike object is dropped by that check and every refusal case would then
 * pass against a screen that had rendered the catch-all sentence instead of the specific one.
 *
 * Assumptions: the kind is `'PROBLEM'` for every refusal built here, because that is the kind the client
 * mints when a service answered with a problem document, and the two cases that need the other kinds
 * reject with something else entirely rather than mislabelling this one.
 * @param {ApiError} problem - The normalised problem document the refusal is to carry.
 * @returns {InstanceType<typeof ApiRequestError>} The failure to reject the create with.
 */
function failureCarrying(problem: ApiError): InstanceType<typeof ApiRequestError> {
  return new ApiRequestError(
    'PROBLEM',
    problem.status,
    problem,
    `PROBLEM ${String(problem.status)} ${problem.code}`,
  );
}

/**
 * Types a complete, valid set of values into the four controls, in the mapset's reading order.
 *
 * Assumptions: the order is the mapset's own -- down rows 8, 11 and 14, left to right within each --
 * which is also the order `app/cbl/COUSR01C.cbl` L117-L151 tests the fields in. Filling them in any
 * other order would leave the form equally valid, so the order is not load-bearing for the outcome; it
 * is written this way so a reader comparing this helper with the cascade sees one sequence rather than
 * two.
 * @param {UserEvent} user - The operator driving the rendered screen.
 * @param {UserType} userType - Which of the two domain characters to submit.
 * @returns {Promise<void>} Resolves once all four values have been entered.
 */
async function typeAValidUser(user: UserEvent, userType: UserType): Promise<void> {
  await user.type(control(USER_ADD_FIELD_LABELS.firstName), NEW_FIRST_NAME);
  await user.type(control(USER_ADD_FIELD_LABELS.lastName), NEW_LAST_NAME);
  await user.type(control(USER_ADD_FIELD_LABELS.userId), NEW_USER_ID);
  await user.type(control(USER_ADD_FIELD_LABELS.userType.trim()), userType);
}

/**
 * Fills every control except the one named, so the cascade stops on exactly that field.
 *
 * Assumptions: the values used are the same fixtures the valid path uses, so a refusal turn and a stored
 * write differ in exactly one control. A helper that also varied the other values would leave a failure
 * ambiguous between the blank field and the substituted value.
 * @param {UserEvent} user - The operator driving the rendered screen.
 * @param {string} exceptLabel - Accessible name of the control to leave blank.
 * @returns {Promise<void>} Resolves once the other three controls carry values.
 */
async function fillEveryControlExcept(user: UserEvent, exceptLabel: string): Promise<void> {
  const values: readonly (readonly [string, string])[] = [
    [USER_ADD_FIELD_LABELS.firstName, NEW_FIRST_NAME],
    [USER_ADD_FIELD_LABELS.lastName, NEW_LAST_NAME],
    [USER_ADD_FIELD_LABELS.userId, NEW_USER_ID],
    [USER_ADD_FIELD_LABELS.userType.trim(), 'A'],
  ];

  for (const [label, value] of values) {
    if (label === exceptLabel) {
      continue;
    }
    await user.type(control(label), value);
  }
}

/**
 * Reads back the single request the substituted create received.
 *
 * Assumptions: the return type comes from the spy's own declared signature, so no cast appears here and
 * the domain guarantee on `userType` is carried by the compiler -- see the note on {@link createUserMock}
 * for why that matters more than a runtime comparison would.
 * @returns {CreateUserRequest} The body the screen submitted.
 * @throws {Error} If the create was not called exactly once, which means the case is asserting against a
 *   submission that did not happen, or happened twice.
 */
function submittedRequest(): CreateUserRequest {
  const calls = createUserMock.mock.calls;
  const [firstCall, ...rest] = calls;

  if (firstCall === undefined || rest.length > 0) {
    throw new Error(`expected exactly one create, observed ${String(calls.length)}`);
  }

  const [body] = firstCall;

  return body;
}

/**
 * Reports every string value Web Storage currently holds, across both areas.
 *
 * Purpose
 * -------
 * `ui/src/hooks/useAuth.ts` records that NOTHING in the application writes, reads or removes browser
 * storage, and that the four `carddemo.*` session-storage keys it once maintained are gone -- which is
 * why there is no storage-key constant to read a value back from. So the credential assertions below
 * scan BOTH areas in full rather than one named key.
 *
 * Assumptions: scanning every key is stronger than checking one, and stronger in the direction that
 * matters. A screen that wrote the credential under some other key would satisfy a single-key check
 * completely, and that is the more likely defect: nobody reintroduces a removed constant, they add a new
 * one.
 * @returns {readonly string[]} Every value held in `localStorage` and `sessionStorage`, in no particular
 *   order.
 */
function everythingInWebStorage(): readonly string[] {
  const held: string[] = [];

  for (const area of [window.localStorage, window.sessionStorage]) {
    for (let index = 0; index < area.length; index += 1) {
      const key = area.key(index);

      if (key === null) {
        continue;
      }

      held.push(area.getItem(key) ?? '');
    }
  }

  return held;
}

/**
 * Asserts the credential appears in no control, no storage area and no unexpected rendered position.
 *
 * ⚠️ Assumptions: the document is searched by TEXT CONTENT as well as control by control, because the
 * failures being excluded are of two different kinds. The value reaching the glass a second time -- in
 * the acknowledgement sentence, or in a summary of what was created -- is a text failure; the value
 * being echoed into an input is not, because a control's value is not part of its text content. A single
 * query would miss one of the two.
 *
 * Assumptions: the caller states whether the handover surface is standing, because while it IS standing
 * the credential is legitimately on the glass exactly once -- that surface is the whole point of the
 * value. What must never happen, standing or not, is a second copy: in a form control or in Web Storage.
 * @param {boolean} handoverStanding - Whether the handover surface is currently displayed, in which case
 *   exactly one on-screen occurrence is expected instead of none.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function expectTheCredentialIsNotRetained(handoverStanding: boolean): void {
  for (const entry of CONTROLS_UNDER_TEST) {
    expect(control(entry.label)).not.toHaveValue(SYNTHETIC_ONE_TIME_CREDENTIAL);
  }

  expect(everythingInWebStorage()).not.toContain(SYNTHETIC_ONE_TIME_CREDENTIAL);
  expect(screen.queryAllByText(SYNTHETIC_ONE_TIME_CREDENTIAL)).toHaveLength(
    handoverStanding ? 1 : 0,
  );
}

/**
 * Asserts one catalogued sentence is recorded as emitted by this program at one measured line.
 *
 * Assumptions: the provenance is CHECKED rather than trusted, so a line number written in a case is
 * verified against the one the catalog records for the same sentence. A table of line numbers that
 * nothing verifies is a comment wearing the costume of an assertion.
 * @param {readonly RecordedSite[]} provenance - Recorded sites for the sentence, from the catalog index.
 * @param {number} referenceLine - Line of `app/cbl/COUSR01C.cbl` the case claims emits it.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function expectRecordedAtCousr01c(
  provenance: readonly RecordedSite[],
  referenceLine: number,
): void {
  const cited = provenance.some(
    /**
     * Reports whether one recorded site names this program at the expected line.
     * @param {RecordedSite} entry - One recorded site.
     * @returns {boolean} `true` when the entry names `COUSR01C` at the expected line.
     */
    (entry: RecordedSite): boolean =>
      entry.file === 'app/cbl/COUSR01C.cbl' && entry.lines.includes(referenceLine),
  );

  expect(cited).toBe(true);
}

/**
 * Resolves the catalog key one shared sentence is declared under.
 *
 * Assumptions: the lookup is by VALUE rather than by a key passed alongside the sentence, so a case's
 * table cannot pair one sentence with another sentence's provenance. The shared group's values are
 * distinct, which is what makes the inversion unambiguous.
 * @param {string} sentence - The catalogued sentence to locate.
 * @returns {readonly RecordedSite[]} Recorded sites for that sentence.
 * @throws {Error} If the sentence is not a shared-group value, which means it was retyped somewhere
 *   rather than read from the catalog.
 */
function sharedSentenceProvenance(sentence: string): readonly RecordedSite[] {
  for (const [key, value] of Object.entries(SHARED_MESSAGES)) {
    if (value === sentence) {
      const sources: Readonly<Record<string, readonly RecordedSite[]>> = SHARED_MESSAGE_SOURCES;
      return sources[key] ?? [];
    }
  }

  throw new Error(`'${sentence}' is not a SHARED_MESSAGES value, so it has no recorded provenance`);
}

/**
 * Unmounts the tree a case has finished with, so its second render queries a single document.
 *
 * Assumptions: this is not a duplicate of the automatic teardown. `ui/src/test/setup.ts` registers an
 * `afterEach` that unmounts and discards the session, and that runs BETWEEN cases; a case that renders
 * twice would otherwise have two screens in one document and every `getBy*` query would report an
 * ambiguous match.
 *
 * ⚠️ Refactoring Rationale: it is reached from THREE cases that render exactly twice, and it used to be
 * reached from cases that rendered up to SIX times. That shape failed, and the failure is worth recording
 * because it is not the one a reader would predict. A case doing six render-and-type cycles exceeded the
 * 60000ms budget `ui/vitest.config.ts` sets, and a timed-out case does not stop the `user.type` promise
 * chain it was in the middle of -- so the aborted case went on dispatching keystrokes into the NEXT
 * case's freshly rendered document. The symptom was a first-name control holding two fixtures
 * interleaved character by character, reported as a focus assertion failing in a case that was correct.
 * Splitting the multi-render cases removes the amplifier as well as the timeout: no case now renders
 * more than twice, so the framework's own per-case teardown does almost all of the work.
 *
 * Trade-offs: the split moves each measured set's membership from a loop into a registration loop over a
 * declared constant, which is one indirection more to read. It is taken because per-case budgets and
 * per-case teardown are what keep a slow runner from turning one expensive case into two failures, and
 * because a failure now names the key or the field rather than the case that contained all of them.
 * @returns {void} Nothing; no tree rendered by this file remains mounted.
 */
function unmountBetweenIterations(): void {
  cleanup();
}

/**
 * Every control declares the width the mapset and the record copybook agree on.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function everyControlDeclaresItsCopybookWidth(): void {
  renderUserAdd();

  for (const entry of CONTROLS_UNDER_TEST) {
    /*
     * WHY : Assumptions: the attribute is compared as TEXT because that is what the DOM holds -- an
     *       attribute value is a string, so `8` and `'8'` name the same attribute. The width comes from
     *       the table above, whose header comment cites both `app/cpy-bms/COUSR01.CPY` and
     *       `app/cpy/CSUSR01Y.cpy` for every entry, so a failure here names a divergence from a measured
     *       declaration rather than from an unexplained number.
     */
    expect(control(entry.label)).toHaveAttribute('maxlength', String(entry.declaredWidth));
  }

  /*
   * WHY : Assumptions: the screen's own published width table is asserted against the same four numbers,
   *       so the two halves of the contract are pinned separately. The rendered attribute could be right
   *       while the published constant drifted -- `USER_ADD_FIELD_WIDTHS` is importable by any sibling --
   *       and the identifier's entry is the one most exposed to that, because the screen takes it from
   *       `USER_ID_MAX_LENGTH` in `ui/src/api/auth.ts` rather than from a literal.
   */
  expect(USER_ADD_FIELD_WIDTHS.firstName).toBe(20);
  expect(USER_ADD_FIELD_WIDTHS.lastName).toBe(20);
  expect(USER_ADD_FIELD_WIDTHS.userId).toBe(8);
  expect(USER_ADD_FIELD_WIDTHS.userType).toBe(1);
}

/*
 * ---------------------------------------------------------------------------
 * ⚠️ Declared width measured in the record's own units, and sized to it
 * ---------------------------------------------------------------------------
 */

/**
 * A composed accent: ONE code point, one UTF-16 unit, TWO UTF-8 bytes.
 *
 * Assumptions: this specimen exists because it is the case where the three readings of "width" that a
 * `PIC X(n)` field could have diverge in the direction that LOSES data -- a value `maxLength` admits
 * and the twenty-byte record cannot hold.
 */
const COMPOSED_ACCENT = '\u00E9';

/** The same accent decomposed: TWO code points, THREE bytes, and identical on the glass. */
const DECOMPOSED_ACCENT = 'e\u0301';

/** A party popper: ONE code point, TWO UTF-16 units, FOUR UTF-8 bytes. */
const ASTRAL_CHARACTER = '\u{1F389}';

/**
 * Pastes one value into one control, which is how an over-capacity entry actually arrives.
 *
 * ⚠️ Assumptions: a PASTE rather than a run of keystrokes, and the difference is what the cases below
 * measure. Typing reaches the clamp once per character, so the last admitted character is the only one
 * the clamp ever has to drop; a paste hands it the whole value at once, which is the arrival the
 * measured defect was reported from and the one where a naive implementation could split a surrogate
 * pair. Every specimen below is within the control's `maxLength` counted in UTF-16 units, so no case
 * depends on how the test DOM enforces that attribute -- only on what the screen does with what it is
 * given.
 * @param {UserEvent} user - The interaction driver the render returned.
 * @param {string} label - Label of the control to paste into.
 * @param {string} value - The value to paste.
 * @returns {Promise<void>} Resolves once the paste has been applied.
 */
async function pasteInto(user: UserEvent, label: string, value: string): Promise<void> {
  await user.click(control(label));
  await user.paste(value);
}

/**
 * A value that exactly fills a declared width is admitted whole.
 *
 * Purpose: establish the other side of the clamp. A measure that refused a value AT its declared width
 * would be the same defect in the opposite direction, and a twenty-character surname is the ordinary
 * case rather than an edge one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function admitsAValueThatFillsItsDeclaredWidth(): Promise<void> {
  const user = renderUserAdd();
  const filled = 'A'.repeat(USER_ADD_FIELD_WIDTHS.lastName);

  await pasteInto(user, USER_ADD_FIELD_LABELS.lastName, filled);

  expect(
    control(USER_ADD_FIELD_LABELS.lastName),
    'a value at the declared width must survive intact in all three readings of that width',
  ).toHaveValue(filled);
}

/**
 * Capacity is measured in the record's BYTES, not in the control's UTF-16 code units.
 *
 * ⚠️ Purpose: this is the defect. `SEC-USR-LNAME PIC X(20)` (`app/cpy/CSUSR01Y.cpy` L20) is twenty
 * BYTES on the record, and `maxLength` counts UTF-16 code units -- so twenty composed accents are
 * twenty code units the attribute admits and forty bytes the record cannot hold. Before the clamp the
 * control accepted all twenty and the overflow was discovered by whatever refused it downstream.
 *
 * Assumptions: the expected survivor count is COMPUTED from the specimen's own byte cost rather than
 * written as ten, so the case states the rule instead of a number and would still be right if the
 * declared width changed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function measuresCapacityInBytesRatherThanCodeUnits(): Promise<void> {
  const user = renderUserAdd();
  const declared = USER_ADD_FIELD_WIDTHS.lastName;
  const bytesPerCharacter = new TextEncoder().encode(COMPOSED_ACCENT).length;
  const admitted = Math.floor(declared / bytesPerCharacter);

  expect(
    COMPOSED_ACCENT.repeat(declared).length,
    'the specimen must be one the maxLength attribute admits, or the case proves nothing',
  ).toBeLessThanOrEqual(declared);

  await pasteInto(user, USER_ADD_FIELD_LABELS.lastName, COMPOSED_ACCENT.repeat(declared));

  expect(
    control(USER_ADD_FIELD_LABELS.lastName),
    'a twenty-byte field must hold ten two-byte characters and not twenty of them',
  ).toHaveValue(COMPOSED_ACCENT.repeat(admitted));
}

/**
 * An astral character is kept or dropped WHOLE, never cut into a lone surrogate.
 *
 * ⚠️ Purpose: cover the failure a byte-arithmetic implementation would produce. Each specimen is one
 * code point, two UTF-16 units and four bytes, so a clamp that walked UTF-16 units could stop halfway
 * through one and leave an unpaired surrogate -- a value no byte measure can make sense of and one the
 * transport would encode as a replacement character.
 *
 * Assumptions: the survivors are counted as CODE POINTS through the string iterator, because
 * `String.prototype.length` is the very measure under test and asserting with it would beg the question.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsAnAstralCharacterWhole(): Promise<void> {
  const user = renderUserAdd();
  const declared = USER_ADD_FIELD_WIDTHS.firstName;
  const bytesPerCharacter = new TextEncoder().encode(ASTRAL_CHARACTER).length;
  const admitted = Math.floor(declared / bytesPerCharacter);
  const pasted = ASTRAL_CHARACTER.repeat(admitted + 3);

  expect(
    pasted.length,
    'the specimen must be one the maxLength attribute admits, or the case proves nothing',
  ).toBeLessThanOrEqual(declared);

  await pasteInto(user, USER_ADD_FIELD_LABELS.firstName, pasted);

  /*
   * WHY : Assumptions: the value is read from the DOM PROPERTY rather than from the attribute, which is
   *       the idiom `src/test/cardList.test.tsx` L1593 already uses for the same need. A controlled
   *       input's authoritative value is its property; the attribute is only the initial one, so reading
   *       it would tie this case to a rendering detail rather than to what the operator can see.
   */
  const held = (control(USER_ADD_FIELD_LABELS.firstName) as HTMLInputElement).value;

  expect([...held], 'the field must hold whole characters up to its byte capacity').toHaveLength(
    admitted,
  );
  expect(held, 'and every one of them must be the character that was pasted').toBe(
    ASTRAL_CHARACTER.repeat(admitted),
  );
}

/**
 * Two spellings of one accent converge, so a name has one form on the wire.
 *
 * ⚠️ Purpose: the composed and decomposed spellings are indistinguishable on the glass and differ byte
 * for byte, which on a system whose keys are compared as characters means two records an operator
 * cannot tell apart and a search that finds one of them. Composing at the point the value is captured
 * removes the ambiguity at the last boundary where it can still be removed.
 *
 * Assumptions: NFC and not NFD, for the reason `normaliseForWire` records -- it is the shorter form for
 * Latin text, so it is the form that fits the most letters into a declared width.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function normalisesTwoSpellingsOfOneAccent(): Promise<void> {
  const user = renderUserAdd();

  expect(
    [...DECOMPOSED_ACCENT],
    'the specimen must genuinely be the longer spelling, or nothing is being normalised',
  ).toHaveLength(2);

  await pasteInto(user, USER_ADD_FIELD_LABELS.firstName, DECOMPOSED_ACCENT);

  expect(
    control(USER_ADD_FIELD_LABELS.firstName),
    'the captured value must carry the one canonical spelling',
  ).toHaveValue(COMPOSED_ACCENT);
}

/**
 * Every control is sized to the character width its copybook declares.
 *
 * ⚠️ Purpose: regress the measured geometry. An eight-character identifier input rendered 1172 pixels
 * wide and the blank-field asterisk this screen paints at the field's right-hand edge landed at x≈1211,
 * roughly 1150 pixels from the value it qualifies -- and the one-position user type rendered at that
 * same full width, so nothing about a control said how much it would take.
 *
 * ⚠️ Assumptions: the DECLARATION is asserted and not a rendered pixel width, because the test DOM
 * performs no layout -- every box in it measures zero, so a width assertion would pass on the broken
 * value too. What can be checked here is that each control carries a maximum measure stated in the
 * field's own character units. The pixel outcome was measured in a browser; this case exists to stop the
 * declaration being removed.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function sizesEveryControlToItsDeclaredWidth(): void {
  renderUserAdd();

  for (const entry of CONTROLS_UNDER_TEST) {
    const measure = control(entry.label).style.maxInlineSize;

    expect(measure, `${entry.label} must declare a maximum measure of its own`).not.toBe('');
    expect(measure, `${entry.label} must be capped at its own declared width`).toContain(
      `${String(entry.declaredWidth)}ch`,
    );
    expect(
      control(entry.label).style.inlineSize,
      `${entry.label} must still shrink inside a narrow viewport`,
    ).toBe('100%');
  }
}

/**
 * Exactly one control takes the initial cursor, matching the mapset's single `IC` operand.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function exactlyOneControlTakesTheInitialCursor(): void {
  renderUserAdd();

  /*
   * WHY : Assumptions: `app/bms/COUSR01.bms` carries exactly ONE `IC` operand, on `FNAME` at L84
   *       (`ATTRB=(FSET,IC,NORM,UNPROT)`), and a 3270 map has one initial cursor position by
   *       construction. `app/cbl/COUSR01C.cbl` agrees from the program side four separate times, moving
   *       `-1` into `FNAMEL` on first entry (L86), on the cascade's fall-through arm (L149), after a
   *       clear (L289) and on the generic write failure (L272). A second `autoFocus` in a browser is not
   *       a second cursor but a race between two controls for the same one, resolved by document order --
   *       so the COUNT is the assertion, not merely which control ends up focused.
   */
  const focused = CONTROLS_UNDER_TEST.filter(
    /**
     * Reports whether one control holds the document's focus.
     * @param {ControlUnderTest} entry - One control under test.
     * @returns {boolean} `true` when that control is the focused element.
     */
    (entry: ControlUnderTest): boolean => control(entry.label) === document.activeElement,
  );

  expect(focused).toHaveLength(1);
  expect(control(USER_ADD_FIELD_LABELS.firstName)).toHaveFocus();
}

/**
 * The screen is composed from design-system components, with no raw control or heading element.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theScreenUsesDesignSystemComponents(): void {
  renderUserAdd();

  /*
   * WHY : Assumptions: the prohibition is checked STRUCTURALLY -- every text control in the screen's body
   *       must be an antd `Input`, which brands its rendered element with the library's own class. AAP
   *       section 0.3.2 states the rule as "library components over raw HTML", and a raw control is both
   *       the easiest violation to introduce and invisible to every other assertion in this file, because
   *       a bare input carries a label and a `maxLength` just as well.
   *       Alternatives Considered: asserting the absence of a raw element by tag name. Rejected because
   *       antd renders real `input`, `button` and heading elements underneath its components, so a tag
   *       name cannot distinguish a compliant tree from a bare one; the class the library applies can.
   */
  const body = screen.getByRole('main');

  for (const field of within(body).getAllByRole('textbox')) {
    expect(field.className).toContain('ant-input');
  }

  /*
   * WHY : Assumptions: the caption is asserted as a HEADING role rather than as text, because
   *       `ui/src/layout/ScreenTitle.tsx` renders it through antd's `Typography.Title` at a fixed rank
   *       and a raw heading element would satisfy a text query identically. The text comes from
   *       `USER_ADD_CAPTION` in the catalog rather than being retyped here, and the catalog transcribes
   *       it from `app/bms/COUSR01.bms` L75-L79, painted with `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL`
   *       over `LENGTH=9`.
   */
  const caption = within(body).getByRole('heading', { name: USER_ADD_CAPTION });
  expect(caption.className).toContain('ant-typography');

  /*
   * WHY : Assumptions: the two hints the mapset paints beside a control are asserted here because one of
   *       them is the only advertisement of the user-type domain an operator gets --
   *       `app/cbl/COUSR01C.cbl` L142 tests that field for blank and carries no domain check at all, so
   *       with the hint gone the two-character domain would be undiscoverable. `(8 Char)` is painted at
   *       L116-L120 and `(A=Admin, U=User)` at L146-L150, both `COLOR=BLUE`.
   */
  expect(within(body).getByText(USER_ADD_FIELD_HINTS.userId)).toBeVisible();
  expect(within(body).getByText(USER_ADD_FIELD_HINTS.userType)).toBeVisible();
}

/**
 * The message band's content and display widths are the two different numbers the baseline declares.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theBandCarriesBothDeclaredWidths(): void {
  /*
   * WHY : ⚠️ Assumptions: SEVENTY-FIVE and SEVENTY-EIGHT are both correct and neither is a typo for the
   *       other. `ERRMSGI PIC X(78)` at `app/cpy-bms/COUSR01.CPY` L90 is the DISPLAY field this mapset
   *       paints on row 23, matching `LENGTH=78` at `app/bms/COUSR01.bms` L151-L154; the CONTENT that
   *       crosses the shared work area is bounded at 75 by `CCARD-ERROR-MSG PIC X(75)` and
   *       `CCARD-RETURN-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28-L30. The narrower number is recorded
   *       rather than "corrected" to the wider one: a sentence composed at 78 would be truncated at 75 on
   *       the way through the work area, so treating the display width as the content limit would licence
   *       a message the baseline could not carry.
   */
  expect(MESSAGE_BAND.workAreaWidth).toBe(75);
  expect(MESSAGE_BAND.sources.workArea.file).toBe('app/cpy/CVCRD01Y.cpy');
  expect(MESSAGE_BAND.sources.workArea.lines).toStrictEqual([28, 29, 30]);
  expect(MESSAGE_BAND_BY_MAPSET.COUSR01.displayWidth).toBe(78);

  /*
   * WHY : Assumptions: the map NAME is asserted alongside the widths because this screen stands in for
   *       `COUSR1A` specifically -- the map `app/cbl/COUSR01C.cbl` re-sends at L191 and L204 -- and no
   *       mapset in this application shares a string with the map it defines.
   */
  expect(MESSAGE_BAND_BY_MAPSET.COUSR01.map).toBe('COUSR1A');
}

/*
 * WHY : Assumptions: `'A'` and `'U'` are the WHOLE domain, from `10 CDEMO-USER-TYPE PIC X(01)` at
 *       `app/cpy/COCOM01Y.cpy` L26 with its two condition names `88 CDEMO-USRTYP-ADMIN VALUE 'A'` at L27
 *       and `88 CDEMO-USRTYP-USER VALUE 'U'` at L28. `ui/src/api/types.ts` encodes it as
 *       `UserType = 'A' | 'U'` and AAP section 0.4.1.3 puts a `CHECK (user_type IN ('A','U'))` on
 *       `auth.users`, so the two-value domain is declared at three independent layers and the cases
 *       built from this constant pin the one an operator reaches.
 * WHY : Refactoring Rationale: the domain is a CONSTANT the case registration iterates rather than two
 *       hand-written cases, and this shape is load-bearing twice over. It keeps the set's membership in
 *       code, so a third character admitted to the union produces a case rather than being silently
 *       untested; and it gives each character its OWN case, which the alternative -- one case looping
 *       over both -- does not. That alternative shipped first and is withdrawn: see the note on
 *       {@link unmountBetweenIterations} for the failure it produced.
 */
const USER_TYPE_DOMAIN: readonly UserType[] = ['A', 'U'];

/**
 * Builds the case that submits one domain character and inspects the body it produced.
 * @param {UserType} userType - The domain character this case submits.
 * @returns {() => Promise<void>} The case body for that character.
 */
function domainCharacterCase(userType: UserType): () => Promise<void> {
  /**
   * Submits the character and asserts the body carries it and names no authorization group.
   * @returns {Promise<void>} Resolves once the submission has been observed.
   */
  return async function theDomainCharacterIsAccepted(): Promise<void> {
    createUserMock.mockResolvedValueOnce({ ...CREATED_USER, userType });
    const user = renderUserAdd();
    await typeAValidUser(user, userType);
    await user.keyboard('{Enter}');

    await waitFor(
      /**
       * Waits until the create has been dispatched for this domain character.
       * @returns {void} Nothing; the assertion resolves the wait.
       */
      (): void => {
        expect(createUserMock).toHaveBeenCalledTimes(1);
      },
    );

    const request = submittedRequest();
    expect(request.userType).toBe(userType);
    expect(request.userId).toBe(NEW_USER_ID);

    /*
     * WHY : ⚠️ Refactoring Rationale: the request is asserted to carry the CHARACTER and no group name,
     *       where a screen might reasonably be thought to resolve the authority itself. AAP section
     *       0.4.1.9 maps `'A'` to the Cognito group `carddemo-admin` and `'U'` to `carddemo-user`, and
     *       that mapping is a SERVER-side consequence of the stored user type -- the identity provider
     *       assigns the group when it provisions the account. A client that named a group would be
     *       asserting an authority it cannot grant. `CreateUserRequest` does not declare the property at
     *       all, so this assertion holds the boundary where the type system cannot reach: on the
     *       serialised body rather than on its declared shape.
     */
    const serialised = JSON.stringify(request);
    expect(serialised).not.toContain(CARDDEMO_ADMIN_GROUP);
    expect(serialised).not.toContain(CARDDEMO_USER_GROUP);
  };
}

/**
 * A character outside the two-value domain never reaches the control or the service.
 * @returns {Promise<void>} Resolves once the refusal and the case fold have been observed.
 */
async function anOutOfDomainCharacterIsRefused(): Promise<void> {
  const user = renderUserAdd();
  const userTypeControl = control(USER_ADD_FIELD_LABELS.userType.trim());

  /*
   * WHY : Assumptions: `'X'` is refused at the KEYSTROKE and the control is left empty, which is the same
   *       affordance `maxLength` already gives this field -- a 3270 refused a character past the end of a
   *       field with no message, and this refuses a character outside the domain the hint beside it
   *       advertises, also with no message. No client-side refusal SENTENCE is asserted, because none
   *       exists: `app/cbl/COUSR01C.cbl` has no domain arm for this field, so inventing one would put
   *       text on the glass that no catalog entry accounts for.
   */
  await user.type(userTypeControl, 'X');
  expect(userTypeControl).toHaveValue('');

  /*
   * WHY : Assumptions: the lower-case form is NORMALISED UP rather than refused, because the reference
   *       stored whatever single character the field held and an operator working in upper case was a
   *       convention of the device rather than of the data. The stored domain is upper case
   *       (`app/cpy/COCOM01Y.cpy` L27-L28), so folding the case admits the operator's intent without
   *       admitting a value outside the domain.
   * WHY : Assumptions: this is asserted BEFORE the refusal turn below rather than after it, and the
   *       ordering is load-bearing on a detail of the design system. A blank refusal renders the marker
   *       as an antd `Input` `suffix`, which REWRAPS the control and mounts a new element -- so a handle
   *       taken before the refusal is detached afterwards, and typing into it silently does nothing.
   *       Alternatives Considered: re-querying the control after the refusal, which also works. Rejected
   *       because it reads as an unexplained second lookup of the same field, where doing the
   *       keystroke-level assertions together says plainly that they are one concern.
   */
  await user.type(userTypeControl, 'a');
  expect(userTypeControl).toHaveValue('A');
  await user.clear(userTypeControl);

  /*
   * WHY : Assumptions: pressing Enter with the field refused raises the field's own CATALOGUED blank
   *       sentence and nothing else, because an empty user type is blank by the cascade's own test and the
   *       cascade is what refuses it -- `app/cbl/COUSR01C.cbl` L142-L144. So the observable outcome of an
   *       out-of-domain keystroke is indistinguishable from never having typed, which is exactly what
   *       "refused at the keystroke" has to mean.
   */
  await fillEveryControlExcept(user, USER_ADD_FIELD_LABELS.userType.trim());
  await user.keyboard('{Enter}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY);
  expect(createUserMock).not.toHaveBeenCalled();
}

/**
 * The screen paints no credential control, which is registered divergence D-USER-ADD-NO-CREDENTIAL-CONTROL.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function noCredentialControlIsPainted(): void {
  renderUserAdd();

  /*
   * WHY : ⚠️ Refactoring Rationale: the mapset paints a credential control and this screen deliberately
   *       does NOT, and that inversion is the single most important assertion in this file. The mapset
   *       declares `PASSWD` at `app/bms/COUSR01.bms` L126 with `ATTRB=(DRK,FSET,UNPROT)` -- the only
   *       field in this mapset carrying that combination, and one of just three across all seventeen base
   *       mapsets, the others being `COSGN00.bms` and `COUSR02.bms` -- over `PASSWDI PIC X(8)` at
   *       `app/cpy-bms/COUSR01.CPY` L78, and `app/cbl/COUSR01C.cbl` L136-L138 refuses a blank one.
   *       `CreateUserRequest` in `ui/src/api/types.ts` declares four members and no credential, so the
   *       control that once stood here was typed, masked, validated and then dropped: a control that
   *       refuses to be left blank and changes nothing when filled tells an operator it sets the new
   *       account's password, and it does not. It is withdrawn under registered divergence
   *       `D-USER-ADD-NO-CREDENTIAL-CONTROL`, with the service generating the credential instead under
   *       `D-RUNTIME-CREDENTIAL-HANDOVER`.
   *       Alternatives Considered: keeping the control and omitting the value from the request, which is
   *       what shipped first. Rejected because an operator who trusts the control hands over a value that
   *       was never stored -- worse than the control's absence, not better.
   *       Alternatives Considered: keeping the control and adding the member to the request. Rejected
   *       because it reinstates the baseline's own defect: `05 SEC-USR-PWD PIC X(08).` at
   *       `app/cpy/CSUSR01Y.cpy` L21 stores an eight-character password in PLAIN TEXT and
   *       `app/cbl/COSGN00C.cbl` L211-L256 compares it directly. AAP sections 0.5.1.2 and 0.7.8 make this
   *       the one place the migration explicitly declines parity: `auth.users` has no password column at
   *       all and the row keeps only a `cognito_sub` reference, so porting the column faithfully would be
   *       reproducing a defect rather than migrating a feature.
   *       Trade-offs: AAP gap G2 -- masking dots accepted as strictly better feedback than the terminal's
   *       blank `DRK` echo -- therefore no longer applies to THIS screen at all. It still applies to
   *       `app/bms/COSGN00.bms` L175, which is the application's one remaining credential input, and that
   *       is where the masked control and its `visibilityToggle` are asserted.
   */
  const body = screen.getByRole('main');
  expect(within(body).queryAllByLabelText(/password/i)).toHaveLength(0);
  expect(body.querySelectorAll('input[type="password"]')).toHaveLength(0);

  /*
   * WHY : Assumptions: the control COUNT is asserted, not merely the absence of one that looks like a
   *       credential. Four controls is the whole form, so a fifth of any kind means either a reinstated
   *       credential control or a field the four-member request cannot carry.
   */
  expect(within(body).getAllByRole('textbox')).toHaveLength(4);
  expect(CONTROLS_UNDER_TEST).toHaveLength(4);
}

/**
 * The blank-credential sentence is still catalogued with both baseline sites, and is emitted by nothing.
 * @returns {Promise<void>} Resolves once the sentence has been shown absent from the one turn that
 *   could raise it.
 */
async function theCredentialSentenceIsCataloguedAndUnemitted(): Promise<void> {
  /*
   * WHY : ⚠️ Assumptions: the sentence is asserted VERBATIM even though this application emits it
   *       nowhere, and both halves of that are deliberate. Transformation rule T8 carries user-visible
   *       strings across character for character, and `'Password can NOT be empty...'` is what
   *       `app/cbl/COUSR01C.cbl` L138 holds -- with `NOT` capitalised, which is not a typo and is not
   *       normalised to "cannot". The catalog keeps the entry as the record of the two baseline sites,
   *       L138 here and `app/cbl/COUSR02C.cbl` L200, because that catalog is a record of the baseline's
   *       strings rather than an index of the currently reachable ones.
   */
  expect(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY).toBe('Password can NOT be empty...');
  expectRecordedAtCousr01c(
    sharedSentenceProvenance(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY),
    138,
  );

  /*
   * WHY : Assumptions: the sentence is shown unemitted by submitting an EMPTY form, which is the only turn
   *       that could raise it -- the reference's cascade reaches its credential arm only when the three
   *       fields before it carry values, so an empty form stops at the first name and a fully-typed form
   *       never reaches a blank credential at all. Asserting on the empty turn proves the arm is GONE
   *       rather than merely unreached.
   */
  const user = renderUserAdd();
  await user.keyboard('{Enter}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY);
  expect(band).not.toHaveTextContent(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY);
  expect(screen.queryByText(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY)).toBeNull();
  expect(createUserMock).not.toHaveBeenCalled();
}

/**
 * The handover surface appears BELOW the form, so nothing already on the glass moves when it arrives.
 *
 * ⚠️ Purpose: regress a measured layout shift. Rendered between the caption and the form, this surface's
 * insertion moved everything below it by roughly 150 pixels -- and everything below it is the whole
 * form, including the first-name control that the same turn has just placed the cursor on
 * (`app/cbl/COUSR01C.cbl` L289 moves `-1` into `FNAMEL`). The one control the operator was about to type
 * into slid out from under the cursor at the moment the write completed. Rendered last, the surface
 * grows the column downward and no element already painted changes position.
 *
 * ⚠️ Assumptions: DOCUMENT ORDER is what is asserted, not a pixel offset, and this is the strongest
 * available check rather than a weaker substitute for one. The test DOM performs no layout, so no
 * position can be measured in it; but a surface that follows every other element in the flow cannot
 * displace any of them, whatever those positions turn out to be. The pixel figure came from a browser.
 *
 * Assumptions: the cursor is asserted too, because the two halves are what make the shift harmful rather
 * than merely untidy -- a surface inserted above a control nothing is focused on would move a control
 * the operator is not using. L289 is why one always is.
 * @returns {Promise<void>} Resolves once the order and the cursor have been asserted.
 */
async function theHandoverSurfaceDisplacesNothingAlreadyPainted(): Promise<void> {
  createUserMock.mockResolvedValueOnce(CREATED_USER);
  const user = renderUserAdd();
  await typeAValidUser(user, 'A');
  await user.keyboard('{Enter}');

  const handover = await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID);
  const form = control(USER_ADD_FIELD_LABELS.firstName).closest('form');

  expect(
    form,
    'the four controls must sit inside a form for the order to mean anything',
  ).not.toBeNull();
  expect(
    Boolean((form?.compareDocumentPosition(handover) ?? 0) & Node.DOCUMENT_POSITION_FOLLOWING),
    'the handover surface must follow the form, so its arrival displaces no control',
  ).toBe(true);

  expect(
    control(USER_ADD_FIELD_LABELS.firstName),
    'and the cursor the success path places is on a control the surface cannot have moved',
  ).toHaveFocus();
}

/**
 * The service-generated credential is handed over once and retained nowhere.
 * @returns {Promise<void>} Resolves once the handover, its contents and its dismissal are observed.
 */
async function theGeneratedCredentialIsHandedOverOnce(): Promise<void> {
  createUserMock.mockResolvedValueOnce(CREATED_USER);
  const user = renderUserAdd();
  await typeAValidUser(user, 'A');
  await user.keyboard('{Enter}');

  const handover = await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID);

  /*
   * WHY : Assumptions: the value is asserted to be on the glass EXACTLY ONCE while the surface stands. It
   *       reaches this client in one response and exists nowhere else the operator can reach -- the
   *       account is provisioned in its force-change state with delivery suppressed, so nobody is mailed
   *       it -- which is why it is displayed at all. A second occurrence would mean a copy the dismissal
   *       below cannot remove.
   */
  expect(within(handover).getByText(SYNTHETIC_ONE_TIME_CREDENTIAL)).toBeVisible();
  expectTheCredentialIsNotRetained(true);

  /*
   * WHY : ⚠️ Assumptions: the archive locator is asserted ABSENT from the glass. `CreatedUserResponse`
   *       carries `credentialSecretName`, and reading that entry needs the secret store's read grant and
   *       the key's -- neither of which a browser session holds -- so rendering it would offer an action
   *       the operator cannot take while still disclosing where the live value is kept. That is the
   *       opposite of the narrowing AAP section 0.7.8 applies to every other sensitive field on the glass.
   */
  expect(screen.queryByText(CREATED_USER.credentialSecretName)).toBeNull();

  /*
   * WHY : Assumptions: the acknowledgement names the identifier the RESPONSE returned rather than the one
   *       typed, because the service canonicalises the key, and the sentence is the one
   *       `app/cbl/COUSR01C.cbl` L255-L258 composes with a `STRING` statement. It is asserted through the
   *       band's text content rather than by rebuilding the template here, so this case pins that the
   *       acknowledgement names the created row without becoming a second implementation of the
   *       composition.
   */
  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(NEW_USER_ID);
  expect(band).not.toHaveTextContent(SYNTHETIC_ONE_TIME_CREDENTIAL);

  /*
   * WHY : Assumptions: the four controls are asserted BLANK, because the stored-write arm performs
   *       `INITIALIZE-ALL-FIELDS` first (L252) and only then composes the acknowledgement (L255-L258) --
   *       so the message is overwritten rather than cleared, and the ORDER is what makes that observable.
   *       This is also the assertion that would fail if a credential were ever echoed into a control.
   */
  for (const entry of CONTROLS_UNDER_TEST) {
    expect(control(entry.label)).toHaveValue('');
  }

  /*
   * WHY : Assumptions: dismissal is IRREVERSIBLE from this screen and the acknowledgement SURVIVES it.
   *       The value has no other reachable copy, so the control's label states the operator's own
   *       completion rather than promising only to hide something; the sentence names a row that still
   *       exists, so clearing it as well would make a dismissal read as though the create had been
   *       undone.
   * WHY : Assumptions: the control's label comes from `CREDENTIAL_HANDOVER_MESSAGES` and is not retyped
   *       here, even though this surface has no counterpart in the reference application and its strings
   *       are therefore AUTHORED rather than transcribed. The catalog owns every string the screen may
   *       paint regardless of provenance, so a label reworded there fails this case rather than leaving
   *       a query silently matching nothing.
   */
  await user.click(
    within(handover).getByRole('button', { name: CREDENTIAL_HANDOVER_MESSAGES.DISMISS_CONTROL }),
  );

  await waitFor(
    /**
     * Waits until the handover surface has been removed from the document.
     * @returns {void} Nothing; the assertion resolves the wait.
     */
    (): void => {
      expect(screen.queryByTestId(USER_ADD_CREDENTIAL_TEST_ID)).toBeNull();
    },
  );

  expectTheCredentialIsNotRetained(false);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(NEW_USER_ID);
}

/**
 * A duplicate identifier is refused with the baseline's own sentence, misspelling intact.
 * @returns {Promise<void>} Resolves once the sentence, the field mark and the retained values are seen.
 */
async function aDuplicateIdentifierIsRefusedVerbatim(): Promise<void> {
  /*
   * WHY : ⚠️ Assumptions: the sentence reads `'User ID already exist...'` -- "exist", NOT "exists". The
   *       subject is singular so correct English would be "exists"; `app/cbl/COUSR01C.cbl` L263 says
   *       "exist", and transformation rule T8 carries user-visible strings across character for
   *       character, so the grammatical error is PRESERVED rather than repaired. This is the single most
   *       likely string on this screen to be silently fixed by a well-meaning author or an autocorrecting
   *       editor, which is why it is asserted against the catalog AND spelled out here as a literal: the
   *       two have to agree, so a "correction" in either place fails this case instead of shipping.
   */
  expect(PROGRAM_MESSAGES.COUSR01C.USER_ID_ALREADY_EXIST).toBe('User ID already exist...');
  expect(PROGRAM_MESSAGE_SOURCES.COUSR01C.USER_ID_ALREADY_EXIST).toStrictEqual([263]);

  /*
   * WHY : Assumptions: the refusal is selected by the transport STATUS through the client's own
   *       `isConflictFailure`, not by the screen comparing a number. The reference reaches the same state
   *       from two CICS response codes that fall into one handler -- `WHEN DFHRESP(DUPKEY)` at L260 and
   *       `WHEN DFHRESP(DUPREC)` at L261 -- and answers at L263 with the cursor on the identifier at
   *       L265, so a duplicate that fell through to the generic sentence would break a message contract
   *       the reference states in one place.
   */
  createUserMock.mockRejectedValueOnce(
    failureCarrying(conflictProblem(PROGRAM_MESSAGES.COUSR01C.USER_ID_ALREADY_EXIST)),
  );
  const user = renderUserAdd();
  await typeAValidUser(user, 'U');
  await user.keyboard('{Enter}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(PROGRAM_MESSAGES.COUSR01C.USER_ID_ALREADY_EXIST);

  /*
   * WHY : Assumptions: the identifier is MARKED as well as banded, because the reference says so twice in
   *       the same arm -- it moves the sentence at L263 and moves `-1` into `USERIDL` at L265, which on a
   *       3270 is how a program points at the offending field. A duplicate arrives with an empty
   *       `fieldErrors` array legitimately, because the collision is detected by a unique constraint
   *       rather than by field validation, so a screen relying on the response alone would move the
   *       cursor to a control carrying no visible indication of why it was refused.
   * WHY : Assumptions: the four values are RETAINED because neither failing arm performs
   *       `INITIALIZE-ALL-FIELDS` -- the duplicate arm at L262-L266 sets the flag, moves the sentence and
   *       repositions the cursor, then re-sends the map with the operator's values still in it.
   *       Discarding three correct values because the fourth collided is not what that arm does.
   */
  const identifier = control(USER_ADD_FIELD_LABELS.userId);
  expect(identifier).toHaveAttribute('aria-invalid', 'true');
  expect(identifier).toHaveFocus();
  expect(control(USER_ADD_FIELD_LABELS.firstName)).toHaveValue(NEW_FIRST_NAME);
  expect(control(USER_ADD_FIELD_LABELS.lastName)).toHaveValue(NEW_LAST_NAME);

  /*
   * WHY : Assumptions: the state is the not-OK one and NOT the blank one, so the field takes the error
   *       colour and no asterisk. `app/cpy/CSSETATY.cpy` distinguishes the two -- both take the colour
   *       (L21-L22) but only the blank case additionally receives the literal `'*'` (L24) -- and a
   *       duplicate identifier is present and wrong rather than absent.
   */
  expect(screen.queryByText(FIELD_ERROR_TOKENS.blankMarker)).toBeNull();
}

/**
 * A refusal naming one control marks that control alone, from the response body and nothing else.
 * @returns {Promise<void>} Resolves once the mark, the sentence and the clean siblings are observed.
 */
async function aServiceFieldRefusalMarksOnlyTheNamedControl(): Promise<void> {
  /*
   * WHY : ⚠️ Refactoring Rationale: the mark is driven by the RESPONSE BODY alone, where the reference
   *       gated it on a remembered turn. `app/cpy/CSSETATY.cpy` L17-L25 moves the error colour into a
   *       field only when its validation flag is not-OK or blank AND `CDEMO-PGM-REENTER` is set -- a
   *       discriminator carried in the pseudo-conversational session struct at `app/cpy/COCOM01Y.cpy`
   *       L29-L31, which AAP section 0.7.1 establishes disappears entirely. A stateless handler has no
   *       turn to remember, so the per-field array in the problem document is the whole marking
   *       mechanism, and this case is what holds that true: nothing about a previous turn can influence
   *       the mark, because there is no previous turn to influence it with.
   * WHY : Assumptions: the screen performs the mapping from problem document to field mark, not the band.
   *       `ui/src/layout/MessageBand.tsx` is deliberately presentational and accepts text and a severity
   *       rather than a problem document, so a refusal that never reached a `Form.Item` would still paint
   *       its sentence on row 23 and mark nothing -- which is the defect this case sees and the band
   *       cannot.
   */
  createUserMock.mockRejectedValueOnce(
    failureCarrying(
      apiError({
        message: SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
        fieldErrors: [fieldError('lastName', SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY)],
      }),
    ),
  );
  const user = renderUserAdd();
  await typeAValidUser(user, 'U');
  await user.keyboard('{Enter}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY);

  const named = control(USER_ADD_FIELD_LABELS.lastName);
  expect(named).toHaveAttribute('aria-invalid', 'true');
  expect(named).toHaveFocus();

  const help = document.getElementById(fieldErrorId(named.id));
  expect(help).toHaveTextContent(SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY);

  /*
   * WHY : Assumptions: every control the array does NOT name is asserted clean, because a screen that
   *       marked all four on any refusal would satisfy the positive half of this case completely. The
   *       reference marks the field it names and no other, which is what the templated highlight
   *       copybook's per-field substitution means.
   */
  for (const entry of CONTROLS_UNDER_TEST) {
    if (entry.label === USER_ADD_FIELD_LABELS.lastName) {
      continue;
    }
    expect(control(entry.label)).not.toHaveAttribute('aria-invalid', 'true');
  }
}

/**
 * A refusal naming TWO controls marks both, describes each with its own sentence, and focuses the first.
 *
 * ⚠️ Purpose: this screen and `/users/:id/edit` are the reference implementation of the conforming-400
 * treatment for the whole delivery -- other surfaces are being brought up to match them -- so the
 * multi-offender case is pinned here rather than assumed to follow from the single-offender one. It does
 * not follow: a screen that marked the array's FIRST entry only, or that pointed both controls at one
 * shared help element, would satisfy every assertion in the single-offender case above and still lose
 * one of the two refusals.
 *
 * ⚠️ Assumptions: focus lands on the FIRST entry in the array and not on the last one applied, which is
 * this screen's own published rule -- `ui/src/screens/userAdd/index.tsx` takes `fieldErrors[0]` as the
 * control to focus. Order therefore has to be observable, so the two refusals are supplied in an order
 * that is NOT the order the four controls are painted in: the array names the last name first. A screen
 * that focused by field position rather than by array position would pass an in-order fixture and fail
 * this one.
 * @returns {Promise<void>} Resolves once both marks, both descriptions and the cursor are observed.
 */
async function aRefusalNamingTwoControlsMarksBoth(): Promise<void> {
  createUserMock.mockRejectedValueOnce(
    failureCarrying(
      apiError({
        message: SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
        fieldErrors: [
          fieldError('lastName', SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY),
          fieldError('firstName', SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY),
        ],
      }),
    ),
  );
  const user = renderUserAdd();
  await typeAValidUser(user, 'U');
  await user.keyboard('{Enter}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY);

  const lastName = control(USER_ADD_FIELD_LABELS.lastName);
  const firstName = control(USER_ADD_FIELD_LABELS.firstName);

  expect(lastName).toHaveAttribute('aria-invalid', 'true');
  expect(firstName).toHaveAttribute('aria-invalid', 'true');
  expect(lastName, 'the cursor goes to the refusal the array names first').toHaveFocus();

  /*
   * WHY : ⚠️ Assumptions: each control is asserted to carry its OWN sentence, reached through its OWN
   *       `aria-describedby`, and the two identifiers are asserted DISTINCT. That last assertion is the
   *       one that catches the plausible failure: a screen that bound both controls to a single help
   *       element would announce the first-name refusal on the last-name control, and every other
   *       assertion here would still hold. `app/cpy/CSSETATY.cpy` L17-L26 is templated per field for
   *       exactly this reason -- one substitution per control, never one shared statement.
   */
  const lastNameHelpId = fieldErrorId(lastName.id);
  const firstNameHelpId = fieldErrorId(firstName.id);

  expect(lastNameHelpId).not.toBe(firstNameHelpId);
  expect(lastName.getAttribute('aria-describedby') ?? '').toContain(lastNameHelpId);
  expect(firstName.getAttribute('aria-describedby') ?? '').toContain(firstNameHelpId);
  expect(document.getElementById(lastNameHelpId)).toHaveTextContent(
    SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
  );
  expect(document.getElementById(firstNameHelpId)).toHaveTextContent(
    SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY,
  );

  /*
   * WHY : Assumptions: the two controls the array does not name stay clean, so the marking is still
   *       per-field when more than one field is named. A screen that switched to marking everything once
   *       a second refusal arrived would satisfy the positive half of this case entirely.
   */
  expect(control(USER_ADD_FIELD_LABELS.userId)).not.toHaveAttribute('aria-invalid', 'true');
  expect(control(USER_ADD_FIELD_LABELS.userType.trim())).not.toHaveAttribute(
    'aria-invalid',
    'true',
  );
}

/**
 * A refusal the transport cannot classify falls back to the reference's catch-all sentence.
 * @returns {Promise<void>} Resolves once the sentence and the cursor are observed.
 */
async function anUnclassifiedRefusalUsesTheCatchAllSentence(): Promise<void> {
  /*
   * WHY : ⚠️ Assumptions: `'Unable to Add User...'` capitalises "Add", and the capital is transcribed
   *       rather than normalised. `app/cbl/COUSR01C.cbl` L270 holds it that way, while
   *       `app/cbl/COUSR00C.cbl` L610 holds `'Unable to lookup User...'` with a LOWER-case "lookup" --
   *       two sentences from two programs in the same family capitalising their verbs differently. Both
   *       are preserved as they are, because rule T8 admits no house style.
   */
  expect(PROGRAM_MESSAGES.COUSR01C.UNABLE_TO_ADD_USER).toBe('Unable to Add User...');
  expect(PROGRAM_MESSAGE_SOURCES.COUSR01C.UNABLE_TO_ADD_USER).toStrictEqual([270]);
  expect(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER).toBe('Unable to lookup User...');

  /*
   * WHY : Assumptions: the rejection is a PLAIN error rather than the transport's normalised failure, so
   *       it takes the arm the reference's `WHEN OTHER` at L267-L273 exists for -- a file operation that
   *       failed for a reason it could not name. `ui/src/api/client.ts` normalises every outcome it
   *       produces, so reaching this arm at all means the rejection came from somewhere else entirely.
   */
  createUserMock.mockRejectedValueOnce(new Error('the transport produced no problem document'));
  const user = renderUserAdd();
  await typeAValidUser(user, 'U');
  await user.keyboard('{Enter}');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(PROGRAM_MESSAGES.COUSR01C.UNABLE_TO_ADD_USER);
  expect(control(USER_ADD_FIELD_LABELS.firstName)).toHaveFocus();
}

/**
 * One arm of the reference's blank-field cascade: the control, its sentence and where it is emitted.
 *
 * Assumptions: the three members travel together because the case built from them asserts all three at
 * once, and separating them is how a table comes to pair one control with another control's sentence.
 */
interface BlankRefusalArm {
  /** Accessible name of the control the arm refuses. */
  readonly label: string;
  /** Verbatim catalogued sentence the arm raises. */
  readonly sentence: string;
  /** Line of `app/cbl/COUSR01C.cbl` that emits it, checked against the catalog's own index. */
  readonly referenceLine: number;
}

/*
 * WHY : Assumptions: the ORDER of this constant is observable behaviour rather than a list that happens
 *       to be sorted. `app/cbl/COUSR01C.cbl` L117-L151 is an `EVALUATE TRUE` whose arms are tested in
 *       sequence and which stops at the first that matches -- first name L118, last name L124,
 *       identifier L130, credential L136, user type L142 -- so exactly one sentence reaches the band per
 *       turn and WHICH one is decided by this order. The withdrawn credential arm sat between the
 *       identifier and the user type, so its removal cannot reorder the four that remain.
 * WHY : Alternatives Considered: writing each sentence out as a literal at its case. Rejected because a
 *       paraphrase in the screen and the same paraphrase in the test would agree with each other, so the
 *       case would pass while the fidelity rule T8 exists to protect was already lost -- the test would
 *       be asserting that the screen agrees with the TEST rather than with the baseline. Every sentence
 *       below is read from the catalog and every line number is checked against the catalog's own
 *       provenance index by {@link expectRecordedAtCousr01c}.
 * WHY : Refactoring Rationale: the four arms are a constant the case registration iterates rather than
 *       one case looping over them, for the reason recorded on {@link unmountBetweenIterations}: a single
 *       case rendering the screen four times over is the shape that failed, and per-arm cases keep the
 *       set's membership in code while giving each arm its own budget and its own teardown.
 */
const BLANK_REFUSAL_CASCADE: readonly BlankRefusalArm[] = [
  {
    label: USER_ADD_FIELD_LABELS.firstName,
    sentence: SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY,
    referenceLine: 120,
  },
  {
    label: USER_ADD_FIELD_LABELS.lastName,
    sentence: SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
    referenceLine: 126,
  },
  {
    label: USER_ADD_FIELD_LABELS.userId,
    sentence: SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY,
    referenceLine: 132,
  },
  {
    label: USER_ADD_FIELD_LABELS.userType.trim(),
    sentence: SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY,
    referenceLine: 144,
  },
];

/**
 * Builds the case that refuses one blank control.
 * @param {BlankRefusalArm} arm - The control, its catalogued sentence and its reference line.
 * @returns {() => Promise<void>} The case body for that arm of the cascade.
 */
function blankRefusalCase(arm: BlankRefusalArm): () => Promise<void> {
  /**
   * Leaves one control blank and asserts its sentence, its mark and its clean siblings.
   * @returns {Promise<void>} Resolves once the refusal has been observed.
   */
  return async function theBlankControlIsRefused(): Promise<void> {
    expectRecordedAtCousr01c(sharedSentenceProvenance(arm.sentence), arm.referenceLine);

    const user = renderUserAdd();
    await fillEveryControlExcept(user, arm.label);
    await user.keyboard('{Enter}');

    const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
    expect(band).toHaveTextContent(arm.sentence);

    /*
     * WHY : Assumptions: the refusal is rendered as an antd `Form.Item` error -- `aria-invalid` on the
     *       control plus help text the control's own description resolves to -- AND carries the literal
     *       asterisk. `app/cpy/CSSETATY.cpy` L17-L25 moves `DFHRED` into a field's colour attribute when
     *       its flag is not-OK or blank (L21-L22) and additionally moves a literal `'*'` into the field
     *       only in the BLANK case (L24), so the colour applies to both conditions and the marker to one.
     *       A blank refusal that lost the marker would lose the terminal's only way of pointing at an
     *       empty field.
     */
    const refused = control(arm.label);
    expect(refused).toHaveAttribute('aria-invalid', 'true');
    expect(refused).toHaveFocus();
    expect(screen.getByText(FIELD_ERROR_TOKENS.blankMarker)).toBeVisible();

    const help = document.getElementById(fieldErrorId(refused.id));
    expect(help).toHaveTextContent(arm.sentence);

    /*
     * WHY : Assumptions: every OTHER control is asserted clean, because a screen that marked all four on
     *       any refusal would satisfy the positive half of this case completely. The reference marks the
     *       one field its arm names, which is what a short-circuit cascade means.
     */
    for (const entry of CONTROLS_UNDER_TEST) {
      if (entry.label === arm.label) {
        continue;
      }
      expect(control(entry.label)).not.toHaveAttribute('aria-invalid', 'true');
    }

    expect(createUserMock).not.toHaveBeenCalled();
  };
}

/**
 * The row-24 legend reassembles into the mapset's own painted string, segment for segment.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function theLegendReassemblesTheMapsetField(): void {
  /*
   * WHY : Assumptions: the legend is asserted as the mapset's own FOUR segments, reassembled in order.
   *       `app/bms/COUSR01.bms` L155-L159 paints one 43-character `ATTRB=(ASKIP,NORM)`, `COLOR=YELLOW`
   *       field whose `INITIAL` is `'ENTER=Add User  F3=Back  F4=Clear  F12=Exit'`, with TWO spaces
   *       between each pair -- so the four captions below are its four segments and the doubled spacing
   *       is content rather than formatting.
   * WHY : Assumptions: this case renders NOTHING, because the four captions are catalog constants and the
   *       claim under test is that they reconstruct one measured mapset field. The cases below assert
   *       that the shell actually paints them; separating the two keeps a wording regression from being
   *       reported as a key-binding failure.
   */
  expect(
    [
      USER_ADD_KEY_LABELS.ENTER,
      USER_ADD_KEY_LABELS.PFK03,
      UNIFORM_PF_KEY_LABELS.PFK04,
      USER_ADD_KEY_LABELS.PFK12,
    ].join('  '),
  ).toBe('ENTER=Add User  F3=Back  F4=Clear  F12=Exit');
}

/*
 * WHY : Alternatives Considered: driving each key only through its legend control, which is the cheaper
 *       and more obvious form. Rejected because the 3270 original was keyboard-only, so the contract is a
 *       KEYBOARD contract: `ui/src/layout/usePfKeys.ts` installs a document keydown listener that a
 *       missing or disabled button would not intercept, so a case that only clicked would pass in full
 *       while every keyboard binding in the application was broken. Each of the three dispatched keys is
 *       therefore exercised twice -- once as a real key event and once as the legend control the shell
 *       paints for it -- because the two are separate paths in this tree.
 * WHY : Refactoring Rationale: the three keys are THREE cases rather than one, and this split repaired a
 *       real failure rather than tidying a long function. See the note on
 *       {@link unmountBetweenIterations}.
 */

/**
 * The committing key writes the user, whether it is pressed or its legend control is activated.
 * @returns {Promise<void>} Resolves once both paths have produced a stored write.
 */
async function theCommittingKeyWrites(): Promise<void> {
  createUserMock.mockResolvedValueOnce(CREATED_USER);
  const byKey = renderUserAdd();
  await typeAValidUser(byKey, 'A');
  await byKey.keyboard('{Enter}');
  expect(await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID)).toBeVisible();
  expect(createUserMock).toHaveBeenCalledTimes(1);

  createUserMock.mockReset();
  unmountBetweenIterations();

  createUserMock.mockResolvedValueOnce(CREATED_USER);
  const byControl = renderUserAdd();
  await typeAValidUser(byControl, 'A');
  await byControl.click(legendControl(USER_ADD_KEY_LABELS.ENTER));
  expect(await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID)).toBeVisible();
  expect(createUserMock).toHaveBeenCalledTimes(1);
}

/**
 * The back key returns to the administrative menu and never to the main one.
 * @returns {Promise<void>} Resolves once both paths have arrived at the administrative menu.
 */
async function theBackKeyReturnsToTheAdministrativeMenu(): Promise<void> {
  /*
   * WHY : ⚠️ Assumptions: PF3 goes to the ADMINISTRATIVE menu and not the main one, because
   *       `app/cbl/COUSR01C.cbl` L93-L95 moves `'COADM01C'` into `CDEMO-TO-PROGRAM` and transfers control
   *       there. `COADM01C` is the administrative menu program, so `/menu` would be the wrong screen even
   *       though it is where most PF3 arms in this application land -- which is exactly why the main-menu
   *       probe is asserted UNREACHED rather than merely left out. `EXEC CICS XCTL` becomes a client-side
   *       route change under transformation rule T5, so the arrival is a rendered route.
   * WHY : Assumptions: the key is raised through `pressPfKey`, which derives the browser key by inverting
   *       the hook's own published table and round-trips it back through the hook's resolver before
   *       pressing. Writing the browser key here would tabulate the mapping a second time, and the second
   *       table is the one that drifts.
   */
  const byKey = renderUserAdd();
  await pressPfKey(byKey, 'PFK03');
  expect(await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeVisible();
  expect(screen.queryByText(`${ARRIVED} ${MAIN_MENU_ROUTE}`)).toBeNull();

  unmountBetweenIterations();

  const byControl = renderUserAdd();
  await byControl.click(legendControl(USER_ADD_KEY_LABELS.PFK03));
  expect(await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeVisible();
  expect(screen.queryByText(`${ARRIVED} ${MAIN_MENU_ROUTE}`)).toBeNull();
}

/**
 * The clear key blanks every control, including the one the cursor returns to.
 * @returns {Promise<void>} Resolves once both paths have left the form empty.
 */
async function theClearKeyBlanksEveryControl(): Promise<void> {
  /*
   * WHY : Assumptions: PF4 blanks ALL FOUR controls and the message with them, which is
   *       `INITIALIZE-ALL-FIELDS` at L287-L295 followed by a re-send -- `CLEAR-CURRENT-SCREEN` at
   *       L279-L282. Clearing the message is what distinguishes this path from the stored-write path,
   *       where the same paragraph runs and the message is then REPLACED by the acknowledgement, and L289
   *       is what puts the cursor back on the first name.
   */
  const byKey = renderUserAdd();
  await typeAValidUser(byKey, 'U');
  await pressPfKey(byKey, 'PFK04');

  for (const entry of CONTROLS_UNDER_TEST) {
    expect(control(entry.label)).toHaveValue('');
  }
  expect(control(USER_ADD_FIELD_LABELS.firstName)).toHaveFocus();
  expect(createUserMock).not.toHaveBeenCalled();

  unmountBetweenIterations();

  const byControl = renderUserAdd();
  await typeAValidUser(byControl, 'U');
  await byControl.click(legendControl(UNIFORM_PF_KEY_LABELS.PFK04));

  for (const entry of CONTROLS_UNDER_TEST) {
    expect(control(entry.label)).toHaveValue('');
  }
  expect(createUserMock).not.toHaveBeenCalled();
}

/**
 * The legend advertises an exit key the program never dispatches, and the key is refused.
 * @returns {Promise<void>} Resolves once the caption, the refusal and the intact session are observed.
 */
async function theAdvertisedExitKeyIsPaintedAndRefused(): Promise<void> {
  /*
   * WHY : ⚠️ Trade-offs: the mapset and the program DISAGREE about PF12 and both halves are observable,
   *       so the resolution has to satisfy both. `app/bms/COUSR01.bms` L155-L159 paints `F12=Exit` inside
   *       the one 43-character row-24 field, so the caption is on the glass on every turn;
   *       `app/cbl/COUSR01C.cbl`'s `EVALUATE EIBAID` at L90-L102 has arms for `DFHENTER`, `DFHPF3` and
   *       `DFHPF4` only, so `DFHPF12` reaches `WHEN OTHER`, which moves `-1` into `FNAMEL` (L100) and
   *       `CCDA-MSG-INVALID-KEY` into the message (L101). It is the second such mismatch in this
   *       repository: `COTRTUP.bms` likewise advertises `F6=Add` with a dedicated `FKEY06I PIC X(6)`
   *       field while `COTRTUPC.cbl` binds only Enter, PF3, PF4, PF5 and PF12.
   *       Alternatives Considered: INVENTING a PF12 handler so the caption is honoured. Rejected -- it
   *       adds behaviour the baseline lacks, which under AAP rule T9 is a behavioural divergence
   *       requiring a registered entry in `docs/architecture/cobol-to-service-traceability.md`, and an
   *       earlier revision of this screen did exactly that and signed the operator off on a key the
   *       reference refuses.
   *       Alternatives Considered: REMOVING the `F12=Exit` descriptor so the legend matches the dispatch.
   *       Rejected -- it edits a user-visible string that rule T8 protects, and the string is not
   *       separable: the whole legend is one field, so the caption cannot be dropped without rewriting
   *       the row. Painting the caption while binding it to the refusal is the only option that changes
   *       neither half, and it is recorded here as the assumption a traceability reader needs.
   * WHY : Assumptions: the caption is asserted PAINTED and the key REFUSED in the same case, because
   *       either assertion alone is satisfied by the wrong screen -- one that dropped the caption passes
   *       the refusal half, and one that signed the operator off passes the caption half.
   */
  const user = renderUserAdd();
  const exitControl = legendControl(USER_ADD_KEY_LABELS.PFK12);
  expect(exitControl).toBeVisible();

  const firstName = control(USER_ADD_FIELD_LABELS.firstName);
  await user.type(firstName, NEW_FIRST_NAME);
  await pressPfKey(user, 'PFK12');

  /*
   * WHY : Assumptions: the sentence is `CCDA-MSG-INVALID-KEY`, declared once as
   *       `05 CCDA-MSG-INVALID-KEY PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20 with its value on L21, and
   *       moved by `app/cbl/COUSR01C.cbl` L101. The declared width is asserted at FIFTY because that is
   *       the copybook's own field width and the trailing blanks it implies are part of the constant; the
   *       band trims them for display, which is why the comparison below trims too.
   */
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.source.file).toBe('app/cpy/CSMSG01Y.cpy');
  expect(COMMON_MESSAGES.INVALID_KEY.source.lines).toStrictEqual([21]);

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(INVALID_KEY_PRESSED.trim());

  /*
   * WHY : Assumptions: the SEVERITY is read from the announced role rather than from a class name, because
   *       `ui/src/layout/MessageBand.tsx` maps the refusal severity to `role="alert"` and the two polite
   *       ones to `role="status"`. A refusal painted as an acknowledgement would keep the sentence and
   *       lose the urgency, and the role is the difference an assistive technology acts on.
   */
  expect(within(band).getByRole('alert')).toBeInTheDocument();

  /*
   * WHY : Assumptions: BOTH probes are asserted unreached. The sign-on probe covers the specific defect an
   *       earlier revision had -- PF12 ending the session -- and the administrative probe covers the
   *       adjacent one, PF12 quietly behaving like PF3. The cursor goes to the first name and the typed
   *       value survives, because the `WHEN OTHER` arm moves `-1` into `FNAMEL` and re-sends the map it
   *       has just received rather than performing `INITIALIZE-ALL-FIELDS` as the PF4 arm does.
   */
  expect(screen.queryByText(`${ARRIVED} ${SIGN_ON_ROUTE}`)).toBeNull();
  expect(screen.queryByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeNull();
  expect(firstName).toHaveFocus();
  expect(firstName).toHaveValue(NEW_FIRST_NAME);
  expect(createUserMock).not.toHaveBeenCalled();

  /*
   * WHY : Assumptions: the legend control reaches the SAME refusal, so a reader cannot conclude the
   *       caption is a decoration wired to nothing. It is inert in the sense that matters -- it can reach
   *       no behaviour the reference lacked -- because it is bound to the screen's own invalid-key arm,
   *       which is precisely what the program does with the key.
   */
  await user.click(exitControl);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(INVALID_KEY_PRESSED.trim());
  expect(screen.queryByText(`${ARRIVED} ${SIGN_ON_ROUTE}`)).toBeNull();
  expect(screen.queryByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeNull();
}

/**
 * ⚠️ Enter takes the primary emphasis because it WRITES, and the other three take the default one.
 *
 * ⚠️ Refactoring Rationale: the outcome asserted here is unchanged and its GROUND is not. The note that
 * stood here credited `PRIMARY_ACTION_AIDS` -- the frozen attention-identifier list -- and observed that
 * this screen registers no PFK05, so Enter came out emphasised. That reasoning is a coincidence rather
 * than a rule: the same list paints `F5=Delete` on `app/bms/COUSR03.bms` L148 in benign primary blue,
 * which is the measured defect the sibling screens were repaired for. The screen now declares that
 * `ENTER=Add User` is `'mutating'` and that `F3=Back`, `F4=Clear` and `F12=Exit` are `'read-only'`, all
 * four read off `app/bms/COUSR01.bms` L159, so the emphasis rests on what the keys do. This screen is
 * the one place in the user group where the old rule and the new one agree, which is exactly why the
 * ground has to be stated -- otherwise a reader would take the agreement as evidence for the list.
 *
 * Assumptions: emphasis is asserted through the design system's own class rather than through a colour,
 * because AAP section 0.3.2 admits no literal colour value and the mapping it states is a
 * component-level one: `type="primary"` for the committing key and `default` for the rest.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function onlyEnterTakesThePrimaryEmphasis(): void {
  renderUserAdd();

  /*
   * WHY : ⚠️ Assumptions: the AID fallback is pinned UNCHANGED, and on this screen that pin carries a
   *       different weight than on its siblings. Here the fallback and the declaration agree, so the
   *       emphasis assertions below cannot by themselves tell which one produced the result. Pinning the
   *       list is what records that the twenty screens which have declared nothing still render exactly
   *       as they did, and it is what would fail if the fallback were quietly removed on the assumption
   *       that every screen had been migrated.
   */
  expect([...PRIMARY_ACTION_AIDS]).toStrictEqual(['ENTER', 'PFK05']);

  expect(legendControl(USER_ADD_KEY_LABELS.ENTER).className).toContain('ant-btn-primary');

  for (const label of [
    USER_ADD_KEY_LABELS.PFK03,
    UNIFORM_PF_KEY_LABELS.PFK04,
    USER_ADD_KEY_LABELS.PFK12,
  ]) {
    expect(legendControl(label).className, label).not.toContain('ant-btn-primary');
  }

  /*
   * WHY : ⚠️ Assumptions: the committing key is asserted NOT dangerous, and that is a classification
   *       rather than an omission. `'mutating'` and `'destructive'` resolve to different paints on
   *       purpose: creating a user is undone by deleting it from the sibling screen, so this key must
   *       read as the screen's primary action and not as an irreversible one. Asserting the absence is
   *       what keeps a well-meant escalation to `'destructive'` from passing here, and it is the
   *       distinction that lets `F5=Delete` on `app/bms/COUSR03.bms` L148 look different from this.
   */
  expect(legendControl(USER_ADD_KEY_LABELS.ENTER).className).not.toContain('ant-btn-dangerous');
}

/**
 * ⚠️ Every key pressed while the create is outstanding is declined without being WITHDRAWN.
 *
 * ⚠️ Refactoring Rationale: this screen declines all four keys for the duration of its own write, and
 * the reason is recorded on the handler map: a 3270 keyboard was LOCKED between the send and the reply,
 * so PF3 leaving or PF4 blanking the form mid-write would resolve the turn the operator's way while the
 * service resolved it its own. That DECISION is preserved exactly. What changed is the channel: the four
 * entries report `busy` where they reported `disabled`, so the controls stay present, enabled, focusable
 * and named and wear the design system's in-flight affordance instead of greying out. A withdrawn
 * control tells an operator the key does not work; a busy one tells them it has not answered yet.
 *
 * ⚠️ Assumptions: FIVE properties are asserted, because the finding a busy channel answers is that a
 * declined key must not become an unavailable one -- enabled, named, announced, dispatching nothing, and
 * saying nothing. A control that went `disabled` would satisfy the fourth and fifth alone.
 *
 * Assumptions: the write is held open with a promise this case resolves itself rather than with a timer,
 * so the in-flight window is bounded by the assertions inside it instead of by a duration.
 * @returns {Promise<void>} Resolves once the held create has been released and its surface has landed.
 */
async function declinesEveryKeyDuringTheWriteWithoutWithdrawingIt(): Promise<void> {
  /**
   * Placeholder resolver, replaced the moment the held promise hands over its own.
   *
   * Assumptions: an initialiser is supplied rather than declaring the binding possibly-undefined,
   * because the promise executor runs synchronously inside the constructor below and therefore always
   * replaces it before any code can call it.
   * @returns {void} Nothing; it is never the resolver that runs.
   */
  function releaseNothing(): void {
    // Assumptions: an empty body is the whole implementation; see the doc block above.
  }

  let releaseCreate: (created: CreatedUserResponse) => void = releaseNothing;

  createUserMock.mockReturnValueOnce(
    new Promise<CreatedUserResponse>(
      /**
       * Captures the resolver so the case controls when the create lands.
       * @param {(created: CreatedUserResponse) => void} resolve - The promise's own resolver.
       * @returns {void} Nothing; the resolver is retained for later.
       */
      (resolve: (created: CreatedUserResponse) => void): void => {
        releaseCreate = resolve;
      },
    ),
  );

  const user = renderUserAdd();
  await typeAValidUser(user, 'A');
  await user.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits until the create has been dispatched, so the window under test is genuinely open.
     * @returns {void} Nothing; the assertion resolves the wait.
     */
    (): void => {
      expect(createUserMock).toHaveBeenCalledTimes(1);
    },
  );

  const busyLabels: readonly string[] = [
    USER_ADD_KEY_LABELS.ENTER,
    USER_ADD_KEY_LABELS.PFK03,
    UNIFORM_PF_KEY_LABELS.PFK04,
    USER_ADD_KEY_LABELS.PFK12,
  ];

  for (const label of busyLabels) {
    const legend = legendControl(label);

    /*
     * WHY : ⚠️ Assumptions: the accessible name is read from the mapset-derived label rather than
     *       retyped, so a busy render that RENAMED the control would fail here. The design system's bare
     *       `loading` prop does exactly that -- it contributes the word "loading" to the name -- which is
     *       why the bar's own affordance is asserted for rather than assumed harmless.
     */
    expect(legend, label).toBeEnabled();
    expect(legend, label).toHaveAttribute('aria-busy', 'true');
    expect(legend, label).toHaveAccessibleName(label);

    legend.focus();
    expect(legend, label).toHaveFocus();
  }

  await user.keyboard('{Enter}');

  /*
   * WHY : ⚠️ Assumptions: exactly ONE create exists, so the second press was declined rather than
   *       dispatched; and the band carries no invalid-key sentence, so it was declined SILENTLY. Both
   *       halves are load-bearing and neither alone would do: the first passes for a withdrawn key, the
   *       second for a key that wrote twice. The silence is the faithful outcome -- during a locked turn
   *       the terminal accepted nothing at all, so a refusal sentence would report a condition the
   *       reference never reported.
   */
  expect(createUserMock).toHaveBeenCalledTimes(1);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).not.toHaveTextContent(
    INVALID_KEY_PRESSED.trim(),
  );

  /*
   * WHY : ⚠️ Assumptions: the wait is ANNOUNCED as well as declined, and it is asserted from inside the
   *       held window. An operator who cannot see the in-flight affordance has only this region, and a
   *       screen that rendered the affordance without announcing anything would pass every assertion
   *       above while telling a keyboard-only operator nothing -- which matters most here, where the
   *       outstanding turn is the one that CREATES the account.
   */
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);

  releaseCreate(CREATED_USER);
  await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID);

  /*
   * WHY : Assumptions: empty once the turn has landed, and still MOUNTED. A live region has to be in the
   *       accessibility tree before its content changes for the first change to be announced, so
   *       emptiness is what "no longer working" looks like and an unmounted region would be the defect.
   */
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
}

/**
 * An administrator reaches the screen and can submit; a signed-on non-administrator cannot.
 * @returns {Promise<void>} Resolves once both outcomes are observed.
 */
async function theRouteAdmitsOnlyAnAdministrator(): Promise<void> {
  /*
   * WHY : ⚠️ Refactoring Rationale: the session is established ONLY through the identity helper, and no
   *       case here assigns itself a group. In the baseline the operator's type travelled in
   *       `CDEMO-USER-TYPE` (`app/cpy/COCOM01Y.cpy` L26-L28) -- storage the terminal echoed back between
   *       turns, so a client could in principle assert its own type. In the target the only input is the
   *       SIGNED `cognito:groups` claim: `ui/src/hooks/useAuth.ts` publishes `CARDDEMO_ADMIN_GROUP`,
   *       `CARDDEMO_USER_GROUP` and `COGNITO_GROUPS_CLAIM` but deliberately no setter, and there is no
   *       context provider anywhere to mount. `seedSession` mints a token carrying the groups and drives
   *       the real sign-on exchange, which is the only path there is.
   *       Assumptions: the irony is worth stating -- this is the screen that CREATES privilege, so a
   *       harness able to grant itself admin would undermine the negative assertion here more than
   *       anywhere else in this tree.
   */
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP] });
  createUserMock.mockResolvedValueOnce(CREATED_USER);
  const admin = renderUserAdd(true);

  expect(await screen.findByLabelText(USER_ADD_FIELD_LABELS.firstName)).toBeVisible();
  await typeAValidUser(admin, 'A');
  await admin.keyboard('{Enter}');
  expect(await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID)).toBeVisible();
  expect(createUserMock).toHaveBeenCalledTimes(1);

  createUserMock.mockReset();
  unmountBetweenIterations();

  /*
   * WHY : Assumptions: a signed-on non-administrator is REFUSED rather than redirected, and the refusal
   *       carries the baseline's own wording -- `'No access - Admin Only option... '`, which
   *       `app/cbl/COMEN01C.cbl` L140 moves and the catalog holds with its TRAILING blank intact, because
   *       the field it came from is fixed-width. The trim belongs to the assertion rather than to the
   *       screen: the guard renders the constant verbatim and the DOM collapses the trailing space for
   *       display, so trimming here keeps the constant the source of the expectation.
   * WHY : Assumptions: the mocked create is asserted NEVER CALLED, which is the half a rendering
   *       assertion misses. A guard that refused the screen after the screen had already dispatched a
   *       request would satisfy every visible expectation in this case.
   */
  await seedSession({ groups: [CARDDEMO_USER_GROUP] });
  renderUserAdd(true);

  expect(await screen.findByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeVisible();
  expect(screen.queryByLabelText(USER_ADD_FIELD_LABELS.firstName)).toBeNull();
  expect(screen.queryByText(`${ARRIVED} ${SIGN_ON_ROUTE}`)).toBeNull();
  expect(createUserMock).not.toHaveBeenCalled();
}

/**
 * The route this screen is reached from is the administrative menu's second option.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theScreenIsTheSecondAdministrativeOption(): void {
  /*
   * WHY : Assumptions: `app/cpy/COADM02Y.cpy` L33 names this option `'User Add (Security)                '`
   *       at `PIC X(35)`, blank-padded, and the administrative menu carries SIX live options -- L22
   *       declares the count as `VALUE 6` while L21 holds a commented-out `VALUE 4`, so the count is
   *       stated rather than inferred from the array's length. That record's `OCCURS 9 TIMES` entry at
   *       L55-L59 has no user-type field at all, because reaching the administrative menu is itself the
   *       authorization -- which is why the guard above the route reads a group and this screen reads no
   *       session of its own.
   */
  const option = ADMIN_MENU_OPTIONS[1];

  expect(option?.optionNumber).toBe(2);
  expect(option?.name).toBe('User Add (Security)                ');
  expect(option?.name).toHaveLength(MENU_OPTION_DECLARED_WIDTH);
  expect(option?.programName).toBe('COUSR01C');
  expect(option?.source).toStrictEqual({ file: 'app/cpy/COADM02Y.cpy', lines: [33] });
  expect(ADMIN_MENU_OPTIONS).toHaveLength(6);
}

/**
 * No cardholder value, identity document or storage identifier is rendered on this screen.
 * @returns {Promise<void>} Resolves once the screen has been examined after a stored write.
 */
async function noSensitiveOrInternalValueIsRendered(): Promise<void> {
  createUserMock.mockResolvedValueOnce(CREATED_USER);
  const user = renderUserAdd();
  await typeAValidUser(user, 'A');
  await user.keyboard('{Enter}');
  await screen.findByTestId(USER_ADD_CREDENTIAL_TEST_ID);

  /*
   * WHY : Assumptions: this screen creates an ADMINISTERED USER and touches no cardholder record, so a
   *       primary account number, a national identifier or a government-issued identifier appearing here
   *       would be a data-exposure defect rather than a fidelity one. AAP section 0.7.8 narrows those
   *       fields to masked or encrypted treatment on the screens that legitimately show them, and none of
   *       this mapset's twelve named fields is one of them. The card verification value is not named
   *       anywhere in this file for a stronger reason: `ui/src/api/types.ts` declares no such field at
   *       all, so no shape reachable from here could carry one.
   * WHY : Assumptions: the five strings below are AUTHORED PROBES and not transcriptions, which is why
   *       they are written here rather than read from the catalog. They are asserted ABSENT, so a probe
   *       that failed to match any real label would weaken this check and never break it -- the opposite
   *       of the catalog's purpose, which is to make a positive assertion fail when a string drifts.
   *       Alternatives Considered: reading the cardholder labels out of the account and card screens'
   *       catalog groups. Rejected because it would couple this case to two unrelated screens' label
   *       sets, so a rewording there would rewrite what this case forbids here.
   */
  const rendered = document.body.textContent ?? '';

  for (const label of ['Card Number', 'SSN', 'Social Security', 'Government', 'Account Number']) {
    expect(rendered).not.toContain(label);
  }

  /*
   * WHY : Assumptions: the owned FILE name is asserted absent too. `app/cbl/COUSR01C.cbl` L39 declares
   *       `05 WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '` -- with two trailing blanks, because the field
   *       is eight characters wide over six of text -- and that is the VSAM file the target replaces with
   *       the `auth.users` table. It is a server-side resource name: the reference never painted it, this
   *       screen has no reason to, and surfacing a storage identifier in a browser would tell an operator
   *       about an implementation they cannot act on.
   */
  expect(rendered).not.toContain('USRSEC');
}

/**
 * Discards accumulated spy state and any value a case left in either Web Storage area.
 * @returns {void} Nothing; the spy holds no calls and both storage areas are empty.
 */
function resetTransportAndStorage(): void {
  createUserMock.mockReset();
  window.localStorage.clear();
  window.sessionStorage.clear();
}

/**
 * Registers every add-user case.
 *
 * Assumptions: the spy and both storage areas are reset on BOTH sides of each case rather than on one.
 * `ui/vitest.config.ts` enables `clearMocks` and `restoreMocks`, which reset a spy's recorded calls and
 * its implementation, and neither reaches Web Storage -- so a value one case wrote would otherwise be
 * visible to the next, and the credential assertions read both areas in full.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function userAddScreenCases(): void {
  beforeEach(resetTransportAndStorage);
  afterEach(resetTransportAndStorage);

  it('declares every control at its copybook width', everyControlDeclaresItsCopybookWidth);
  it('admits a value that fills its declared width', admitsAValueThatFillsItsDeclaredWidth);
  it(
    'measures capacity in bytes rather than code units',
    measuresCapacityInBytesRatherThanCodeUnits,
  );
  it('keeps an astral character whole', keepsAnAstralCharacterWhole);
  it('normalises two spellings of one accent', normalisesTwoSpellingsOfOneAccent);
  it('sizes every control to its declared width', sizesEveryControlToItsDeclaredWidth);
  it('places the initial cursor on exactly one control', exactlyOneControlTakesTheInitialCursor);
  it('composes the form from design-system components', theScreenUsesDesignSystemComponents);
  it('records both declared message-band widths', theBandCarriesBothDeclaredWidths);

  /*
   * WHY : Assumptions: the domain cases are REGISTERED FROM the measured constant rather than written
   *       out, so a third character admitted to `UserType` produces a case instead of going silently
   *       untested. The same reasoning applies to the cascade arms below.
   */
  for (const userType of USER_TYPE_DOMAIN) {
    it(`accepts the user type '${userType}'`, domainCharacterCase(userType));
  }

  it('refuses a user-type character outside the domain', anOutOfDomainCharacterIsRefused);
  it('paints no credential control', noCredentialControlIsPainted);
  it(
    'keeps the blank-credential sentence catalogued and unemitted',
    theCredentialSentenceIsCataloguedAndUnemitted,
  );
  it('hands the generated credential over once', theGeneratedCredentialIsHandedOverOnce);
  it(
    'places the handover surface where its arrival displaces nothing',
    theHandoverSurfaceDisplacesNothingAlreadyPainted,
  );
  it('refuses a duplicate identifier verbatim', aDuplicateIdentifierIsRefusedVerbatim);
  it('marks only the control a refusal names', aServiceFieldRefusalMarksOnlyTheNamedControl);
  it('marks both controls a two-field refusal names', aRefusalNamingTwoControlsMarksBoth);
  it('falls back to the catch-all sentence', anUnclassifiedRefusalUsesTheCatchAllSentence);

  for (const arm of BLANK_REFUSAL_CASCADE) {
    it(`refuses a blank ${arm.label.replace(':', '')}`, blankRefusalCase(arm));
  }

  it('reassembles the row-24 legend field', theLegendReassemblesTheMapsetField);
  it('writes the user on the committing key', theCommittingKeyWrites);
  it(
    'returns to the administrative menu on the back key',
    theBackKeyReturnsToTheAdministrativeMenu,
  );
  it('blanks every control on the clear key', theClearKeyBlanksEveryControl);
  it('paints and refuses the advertised exit key', theAdvertisedExitKeyIsPaintedAndRefused);
  it('emphasises only the committing key', onlyEnterTakesThePrimaryEmphasis);
  it(
    'declines every key during the write without withdrawing it',
    declinesEveryKeyDuringTheWriteWithoutWithdrawingIt,
  );
  it('admits only an administrator', theRouteAdmitsOnlyAnAdministrator);
  it('stands in for the second administrative option', theScreenIsTheSecondAdministrativeOption);
  it('renders no sensitive or internal value', noSensitiveOrInternalValueIsRendered);
}

describe('the add-user screen', userAddScreenCases);
