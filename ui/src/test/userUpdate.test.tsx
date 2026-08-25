/**
 * @file Component tests for the CardDemo user update screen, the migration target of BMS mapset
 * `COUSR02` / map `COUSR2A` and program `app/cbl/COUSR02C.cbl`, mounted at `/users/:id/edit`.
 *
 * Purpose
 * -------
 * WHAT: this module holds the behavioural contract of one screen -- its field widths, its two-turn
 * fetch-then-save workflow, its five function keys, the ten sentences it paints, its administrative
 * gating, its route parameter and its field refusals -- expressed as executable assertions against
 * `ui/src/screens/userUpdate/index.tsx`.
 *
 * WHY : this screen carries the single most anomalous key semantic in the application. On sixteen of
 * the seventeen base mapsets PF3 goes back and writes nothing; on this one it SAVES. Its row-24 legend
 * paints `F3=Save&&Exit` (`app/bms/COUSR02.bms` L163) and its dispatch arm performs `UPDATE-USER-INFO`
 * before `RETURN-TO-PREV-SCREEN` (`app/cbl/COUSR02C.cbl` L111-L119) with no error test between the two.
 * Demoting that key to a plain "Back" for consistency with its siblings would be a silent data-loss
 * defect, so the assertion that PF3 writes is the highest-value one in this file.
 *
 * Module contract, in the terms Rule 1 asks a module entry point to state
 * ----------------------------------------------------------------------
 * Parameters: none. A Vitest module is invoked by the runner, not by a caller, so its inputs are its
 * ambient collaborators: the jsdom document and the two browser shims that `ui/src/test/setup.ts`
 * installs, the shared render/press/session/assertion helpers that same module publishes, the verbatim
 * message catalog in `ui/src/messages/messages.ts`, and the two identity operations replaced below.
 * Returns: nothing. Its product is the runner's verdict.
 * Exceptions: none are raised deliberately. Every failing expectation surfaces as a Vitest assertion
 * error; the render helpers additionally throw when a route pattern cannot match the address they are
 * asked to open, which is a harness misuse rather than a screen failure.
 *
 * Why this file is the only verification this screen gets
 * ------------------------------------------------------
 * `tests/README.md` section 1.1 states it plainly: "Online `CO*` CICS programs cannot run end-to-end
 * without a CICS runtime (absent on the runner); only their extractable field-validation logic is
 * unit-tested." The repository's COBOL suite is the parity oracle for the BATCH pipeline only, so
 * **there is no golden master for `COUSR02C`** and no run of the reference program to diff against.
 * Every expectation below is therefore anchored to a measured line of the immutable baseline rather
 * than to a recorded output, and each one cites the file and line it was measured from.
 *
 * Rule 1, double-anchored
 * -----------------------
 * The project declares exactly one rule, **Rule 1: Explainability**, and `tests/README.md` section 12
 * L544-L549 imposes the identical obligation on "every new test, fixture builder, helper, mock, and
 * runner routine", calling it "a hard review gate". The two AGREE -- purpose, parameters, returns and
 * exceptions in a docstring; inline comments that explain why in one of four named categories -- so
 * this file extends an established house convention rather than importing a new one. It conforms to
 * `docs/CODE_DOCUMENTATION_STANDARD.md`, which resolves the one point on which the two documents could
 * be read differently: the rationale labels are the PLURAL forms `Assumptions:`, `Trade-offs:`,
 * `Alternatives Considered:` and `Refactoring Rationale:`, and a `WHAT:` comment is permitted only
 * inside this leading header block.
 *
 * Assumptions: that label form is not a preference. `config/rule1/rule1_gate.py` is a repository-wide,
 * build-failing gate that governs `.tsx`; its `CANONICAL_LABELS` are the four plural forms, its
 * `_SINGULAR_STEMS` (`Assumption`, `Trade-off`) are reported violations, and `iter_what_violations`
 * rejects a `WHAT:` comment below the header block. Rule 1's own text uses the plural at its lines
 * 31-34. The gate passes on this tree today and this file keeps it passing.
 *
 * Four places where the specification for this file and the landed code disagree
 * -----------------------------------------------------------------------------
 * Assumptions: the repository is authoritative where the two differ, which is the rule AAP section
 * 0.1.1.1 sets out for exactly this situation. Each resolution is recorded here because a reader
 * checking this file against its brief will otherwise read each one as an omission.
 *
 * 1. The screen publishes NO default export. `ui/src/screens/userUpdate/index.tsx` withdrew it
 *    deliberately, citing AAP section 0.6.2.1's named-import discipline, so the subject is imported by
 *    name as `UserUpdateScreen`.
 * 2. The screen renders NO credential control -- registered divergence D-10. The brief asks for an
 *    `Input.Password` with `visibilityToggle={false}`; there is no such control, because the value it
 *    would collect has nowhere to go. `UpdateUserRequest` is sealed at three members and `auth.users`
 *    carries no password column at all. The cases below therefore assert the ABSENCE of the control,
 *    which is the stronger form of the same requirement: a control that cannot exist cannot leak.
 * 3. The rationale labels are plural and `WHAT:` is header-only, per the gate named above.
 * 4. The test APIs are IMPORTED from `vitest` rather than taken from ambient globals.
 *    `ui/vitest.config.ts` does set `globals: true`, but `ui/tsconfig.json` L106-L117 keeps its `types`
 *    array empty on purpose, so nothing is declared ambiently and `tsc --noEmit` -- which CI runs over
 *    this file -- fails on any symbol not imported. `ui/src/test/setup.ts` L95-L104 records the same
 *    obligation from the other side.
 *
 * Assumptions: no credential, endpoint or token value appears anywhere below. AAP section 0.9.1 makes
 * "no secrets committed to the repository" non-negotiable, and this screen has no credential surface to
 * fixture in any case, so the point is kept by construction rather than by care.
 */

import { screen, waitFor, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { Route, Routes, useNavigate } from 'react-router';
/*
 * WHY : Assumptions: `StrictMode` is imported as a VALUE, and it is the instrument one case below needs
 *       rather than a convenience. React's strict mode double-invokes every effect of the tree beneath
 *       it, which is the same shape as the suspend-and-resume replay that made this screen read one row
 *       twice in a production bundle -- a cleanup followed by a second run of an unchanged effect. It is
 *       the only way to reproduce that replay deterministically in a test, since suspending an
 *       already-mounted tree on demand would need a second lazily-loaded component and a promise the
 *       case controlled, which would put the mechanism under test outside this screen.
 */
import { StrictMode } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';

import type * as AuthModule from '../api/auth';
import type { UpdateUserRequest, UserResponse, UserType } from '../api/types';

/*
 * WHY : Assumptions: the two spies are built inside `vi.hoisted`, which the runner evaluates before any
 *       import in this file. The alternative that reads more naturally -- a module-scoped
 *       `const getUserMock = vi.fn()` closed over by the factory below -- is a temporal-dead-zone fault
 *       waiting on an import order: `vi.mock` is itself hoisted above the imports, so a static import of
 *       anything that transitively reaches `../api/auth` runs the factory before that `const` is
 *       initialised and the whole file fails to load with "Cannot access before initialization" before a
 *       single case runs. `ui/src/screens/userUpdate/index.tsx` reaches it directly and
 *       `ui/src/hooks/useAuth.ts` reaches it too, so the hazard is real for both of the static imports
 *       below rather than hypothetical.
 *       Alternatives Considered: the sibling pattern in `ui/src/screens/userUpdate/userUpdate.test.tsx`,
 *       which keeps the module-scoped consts and moves every affected import to a top-level dynamic
 *       `await import()`. Rejected here only because `vi.hoisted` removes the hazard at its source
 *       instead of working around it, which keeps every import in this file in one static block a reader
 *       can scan; the sibling's shape remains correct and the two are interchangeable.
 */
/**
 * The two identity operations this screen issues, as spies.
 *
 * Assumptions: both are typed against the real signatures in `ui/src/api/auth.ts` rather than left as
 * bare `Mock` values, so `mockResolvedValue` is checked against `UserResponse` and a fixture missing a
 * member -- or carrying one the contract does not declare, a credential above all -- fails to compile
 * here rather than reaching a case as an untyped object.
 */
interface IdentityTransportSpies {
  /** Stands in for the keyed user read the fetch turn issues. */
  readonly getUser: Mock<(userId: string) => Promise<UserResponse>>;
  /** Stands in for the update the save turns issue. */
  readonly updateUser: Mock<(userId: string, request: UpdateUserRequest) => Promise<UserResponse>>;
}

const identityTransport = vi.hoisted(
  /**
   * Builds the two identity operations this screen issues, as spies, before any import is evaluated.
   * @returns {IdentityTransportSpies} The keyed read and the update, each as an unconfigured spy whose
   *   answer every case supplies for itself.
   */
  (): IdentityTransportSpies => ({
    getUser: vi.fn<(userId: string) => Promise<UserResponse>>(),
    updateUser: vi.fn<(userId: string, request: UpdateUserRequest) => Promise<UserResponse>>(),
  }),
);

/*
 * WHY : Assumptions: the substitution is PARTIAL and every other export of the module stays real. A
 *       wholesale replacement is the shorter spelling and it breaks two collaborators at once:
 *       `ui/src/hooks/useAuth.ts` imports `signOn`, `refreshTokens`, `signOut`,
 *       `answerSignOnChallenge` and `SIGN_ON_AUTHENTICATED` from this same module, and the session
 *       helper in `./setup` establishes a session by driving that real sign-on exchange. Replacing them
 *       with `undefined` would make the administrative-gating cases below fail on the substitution
 *       rather than on the guard.
 * WHY : Assumptions: the TRANSPORT module is mocked rather than the HTTP client beneath it, because
 *       what these cases assert is which operation the screen calls and what it sends -- properties of
 *       the screen. Mocking the client instead would make a request-shape regression
 *       indistinguishable from an interceptor one.
 */
vi.mock(
  '../api/auth',
  /**
   * Replaces the keyed read and the update while leaving every other export of the module intact.
   * @returns {Promise<typeof AuthModule>} The real module with `getUser` and `updateUser` substituted.
   */
  async (): Promise<typeof AuthModule> => {
    const actual = await vi.importActual<typeof AuthModule>('../api/auth');

    return {
      ...actual,
      getUser: identityTransport.getUser,
      updateUser: identityTransport.updateUser,
    };
  },
);

/*
 * WHY : ⚠️ Assumptions: FIVE of the modules imported below sit outside this file's declared dependency
 *       set, and each is reached because an assertion this file is required to make cannot be made
 *       without it. They are named here so the additions are auditable rather than incidental, and every
 *       one is an existing unchanged module of this same package -- no path is invented and nothing is
 *       re-declared locally.
 *       `../api/client` supplies `ApiRequestError`. The screen narrows every rejection through
 *       `isApiRequestError` before it reads a status or a problem document, so a hand-rolled failure is
 *       dropped by that guard and the refusal cases silently exercise the catch-all arm instead of the
 *       arm they name. This was measured: the 404 case asserted the not-found sentence and read the
 *       generic lookup sentence until the real class was used.
 *       `../layout/AppShell` is needed by the two hand-composed route trees. The screen delegates its
 *       title band, row-23 message line and row-24 legend to the shell, so a tree without it has no band
 *       and no legend for the navigation and guard cases to assert on.
 *       `../routes/guards` supplies `RequireAdmin`, which IS the administrative gate. Asserting the
 *       gating without it would assert a copy of the rule rather than the rule.
 *       `../routes/navigation` supplies `ADMIN_MENU_ROUTE`, the destination this screen's PF3 and PF12
 *       arms transfer to. `ui/src/router.tsx` declares the route table but the constant lives here, and
 *       writing `'/admin'` as a literal instead would let the two drift apart unnoticed.
 *       `../layout/fieldHelp` supplies `fieldErrorId`, the derivation that names the element carrying a
 *       refusal's help text. Recomputing that identifier here would encode a private convention of the
 *       module that owns it.
 *       Alternatives Considered: declaring local stand-ins for all five to keep the import set to the
 *       declared list. Rejected because every one of them would replace the application's own behaviour
 *       with a restatement of it -- a guard that is not the guard, a route constant that cannot drift
 *       with the router, an error class the screen's own type guard rejects -- which is the failure mode
 *       dependency verification exists to prevent.
 */
import { USER_ID_MAX_LENGTH } from '../api/auth';
import { ApiRequestError, claimRetainedOutcome } from '../api/client';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP, COGNITO_GROUPS_CLAIM } from '../hooks/useAuth';
import { AppShell } from '../layout/AppShell';
import { MESSAGE_BAND_CONTENT_WIDTH, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import { fieldErrorId } from '../layout/fieldHelp';
import type { CicsAid } from '../layout/usePfKeys';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  ACCESS_DENIED_HEADING,
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MESSAGE_BAND_BY_MAPSET,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  formatMessageTemplate,
} from '../messages/messages';
import {
  ADMIN_MENU_ROUTE,
  MAIN_MENU_ROUTE,
  USER_LIST_ROUTE,
  USER_UPDATE_ROUTE_TEMPLATE,
  navigateSafely,
} from '../routes/navigation';
import { RequireAdmin } from '../routes/guards';
import {
  USER_UPDATE_CAPTION,
  USER_UPDATE_FIELD_HINTS,
  USER_UPDATE_FIELD_LABELS,
  USER_UPDATE_FIELD_WIDTHS,
  USER_UPDATE_KEY_LABELS,
  USER_UPDATE_MAPSET,
  UserUpdateScreen,
} from '../screens/userUpdate';
import { BMS_TEXT_COLOR_TOKENS, FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
  renderWithProviders,
  seedSession,
} from './setup';

/**
 * Route pattern this screen occupies, spelled exactly as `ui/src/router.tsx` declares it.
 *
 * Assumptions: the parameter is named `id`. `ui/src/screens/userUpdate/index.tsx` reads
 * `useParams().id`, and a pattern spelling it anything else resolves to `undefined` SILENTLY -- the
 * screen would then sit on its manual-entry arm and every routed case would assert against the wrong
 * turn while appearing to exercise the right one.
 */
const USER_UPDATE_ROUTE = '/users/:id/edit';

/**
 * Converts an antd token name into the CSS custom-property segment it resolves to.
 *
 * Assumptions: the design system themes through CSS variables at this version, so a token reaches the
 * DOM as a `var(--...)` reference whose name is the token identifier in kebab case. Deriving the segment
 * from `ui/src/theme/tokens.ts` rather than writing the property out is what lets a case assert which
 * ROLE a rendered value resolves through while holding no design literal of its own.
 * @param {string} tokenName - The antd token identifier, as the theme bridge records it.
 * @returns {string} The kebab-case segment of the custom property it becomes.
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

/** The eight-character identifier the routed cases administer. */
const EDITED_USER_ID = 'USER0001';

/*
 * WHY : Assumptions: this row is the shape `UserResponse` declares and NOTHING MORE -- five members,
 *       none of them a credential. That is the fixture's whole point on this screen: a read-then-edit
 *       implementation seeds its form from whatever the read returned, so a fixture carrying a sixth
 *       `password` member is the one input that could make a credential appear in a control while every
 *       visible expectation still passed. The reference does exactly that at `app/cbl/COUSR02C.cbl`
 *       L169, `MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI`, reading the plaintext
 *       `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21.
 * WHY : Assumptions: every value here is synthetic by construction and none is a credential, so
 *       nothing here can be mistaken for a secret. `cognitoSub` is a nil-flavoured version-4 identifier
 *       rather than one an identity provider ever minted.
 */
const STORED_ROW: UserResponse = {
  userId: EDITED_USER_ID,
  firstName: 'ADA',
  lastName: 'LOVELACE',
  userType: 'U',
  cognitoSub: '00000000-0000-4000-8000-000000000001',
};

/** The first name a case types over the stored one, which is the change that makes a save legitimate. */
const EDITED_FIRST_NAME = 'GRACE';

/** The row as the service reports it once the edited first name has been committed. */
const SAVED_ROW: UserResponse = { ...STORED_ROW, firstName: EDITED_FIRST_NAME };

/** Sentences this screen's own program contributes, from the catalog that owns every string it paints. */
const UPDATE_MESSAGES = PROGRAM_MESSAGES.COUSR02C;

/** Baseline lines that emit each of those two sentences, for the citations the cases carry. */
const UPDATE_MESSAGE_LINES = PROGRAM_MESSAGE_SOURCES.COUSR02C;

/**
 * The four controls this screen renders, in the order the reference's cascade tests them.
 *
 * Assumptions: FOUR and not five. `app/cbl/COUSR02C.cbl` L179-L213 is one `EVALUATE TRUE` testing the
 * identifier (L180), the first name (L186), the last name (L192), the credential (L198) and the user
 * type (L204); the credential's arm has no counterpart here, which is registered divergence D-10 and is
 * asserted directly by {@link describe}'s credential group below rather than left as a gap in this list.
 */
const RENDERED_FIELDS = ['userId', 'firstName', 'lastName', 'userType'] as const;

/**
 * A composed accent: ONE code point, one UTF-16 unit, TWO UTF-8 bytes.
 *
 * Assumptions: this specimen exists because it is the case where the three readings of "width" a
 * `PIC X(n)` field could have diverge in the direction that LOSES data -- a value `maxLength` admits and
 * the twenty-byte record cannot hold.
 */
const COMPOSED_ACCENT = '\u00E9';

/** The same accent decomposed: TWO code points, THREE bytes, and identical on the glass. */
const DECOMPOSED_ACCENT = 'e\u0301';

/** A party popper: ONE code point, TWO UTF-16 units, FOUR UTF-8 bytes. */
const ASTRAL_CHARACTER = '\u{1F389}';

/**
 * Returns the row-24 key legend the shell paints for this screen.
 *
 * Assumptions: the legend is found as a NAVIGATION landmark by its own region name rather than by
 * querying buttons unscoped, because `ui/src/layout/AppShell.tsx` paints its own sign-off control
 * outside the bar -- an unscoped `getAllByRole('button')` would collect that control too and every
 * count below would be one too high.
 * @returns {HTMLElement} The legend landmark `ui/src/layout/PfKeyBar.tsx` renders.
 */
function legend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Returns one legend control by its rendered label.
 * @param {string} label - The control's decoded legend text, from {@link USER_UPDATE_KEY_LABELS}.
 * @returns {HTMLElement} That control.
 */
function keyButton(label: string): HTMLElement {
  return within(legend()).getByRole('button', { name: label });
}

/**
 * Returns the row-23 message band the shell paints for this screen.
 * @returns {HTMLElement} The band element.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns one of the four rendered controls by its mapset label.
 *
 * Assumptions: the label is TRIMMED for the query alone, and the untrimmed value is asserted separately
 * by {@link describe}'s first group. Testing Library's default normaliser collapses and trims the text
 * it compares, so `'User Type: '` -- whose trailing space is the eleventh character of an
 * `INITIAL='User Type: '` painted at `LENGTH=11` (`app/bms/COUSR02.bms` L140-L144) -- can never match a
 * normalised DOM string. Trimming here loses nothing, because the character it drops is asserted where
 * the constant itself is asserted rather than being relied upon through a query.
 *
 * Assumptions: the control is located by LABEL rather than by identifier, because
 * `ui/src/screens/userUpdate/index.tsx` derives every control identifier per instance through `useId`.
 * A test that queried by identifier would encode a React-internal counter and break on any reordering.
 * @param {(typeof RENDERED_FIELDS)[number]} field - Which control to return.
 * @returns {HTMLInputElement} The input element that label is bound to.
 */
function control(field: (typeof RENDERED_FIELDS)[number]): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>(USER_UPDATE_FIELD_LABELS[field].trim());
}

/**
 * Mounts the screen at its own route, inside the real application shell.
 *
 * Assumptions: `renderInAppShell` is used rather than a bare render, and the reason is structural. This
 * screen composes none of its three persistent zones: it publishes the title band, the row-23 message
 * line and the row-24 legend through `useShellSlot`, and `ui/src/layout/AppShell.tsx` -- mounted as a
 * layout route -- paints them. A bare render therefore produces a screen with no band and no legend, on
 * which every message and key assertion below fails against a screen that is in fact correct.
 * @param {string} [address] - The concrete address to open, defaulting to the administered row's.
 * @returns {Promise<Awaited<ReturnType<typeof renderInAppShell>>>} The render result and its operator.
 */
async function mountScreen(
  address: string = `/users/${EDITED_USER_ID}/edit`,
): Promise<Awaited<ReturnType<typeof renderInAppShell>>> {
  return await renderInAppShell(<UserUpdateScreen />, {
    routePath: USER_UPDATE_ROUTE,
    initialEntries: [address],
  });
}

/** Text the administrative-menu stand-in paints, chosen so no catalogued sentence can collide with it. */
const ADMIN_MENU_PROBE_TEXT = 'administrative menu reached';

/**
 * Renders a stand-in for the administrative menu, so a transfer to it is observable.
 *
 * Assumptions: a probe element rather than the real `ui/src/screens/admin/index.tsx`, because what the
 * navigation cases assert is the DESTINATION the key chose, not what that destination renders. Mounting
 * the real screen would pull its own reads into the case and make a transfer assertion fail for a
 * reason belonging to another screen.
 * @returns {ReactElement} A marker the navigation cases query for.
 */
function AdminMenuProbe(): ReactElement {
  return <p>{ADMIN_MENU_PROBE_TEXT}</p>;
}

/**
 * Mounts the screen and an administrative-menu stand-in under one shell, so a transfer is observable.
 *
 * Assumptions: the two routes are declared here rather than through `renderInAppShell`, whose options
 * carry a single route pattern by design. A transfer needs two mounted routes -- the origin and the
 * destination -- so this composes the route tree itself and hands it to the provider-only helper, which
 * is the same memory router and the same theme provider the single-route helper uses.
 * @returns {Promise<Awaited<ReturnType<typeof renderWithProviders>>>} The render result and its operator.
 */
async function mountScreenWithDestination(): Promise<
  Awaited<ReturnType<typeof renderWithProviders>>
> {
  return await renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path={USER_UPDATE_ROUTE} element={<UserUpdateScreen />} />
        <Route path={ADMIN_MENU_ROUTE} element={<AdminMenuProbe />} />
      </Route>
    </Routes>,
    { initialEntries: [`/users/${EDITED_USER_ID}/edit`] },
  );
}

/**
 * Waits until the routed read has seeded the three editable controls from the stored row.
 * @returns {Promise<void>} Resolves once the stored first name has reached its control.
 */
async function waitForTheRowToLand(): Promise<void> {
  await waitFor(
    /**
     * Re-reads the first-name control until the row's value has been applied to it.
     * @returns {void} Nothing; throws until the value is present.
     */
    () => {
      expect(control('firstName')).toHaveValue(STORED_ROW.firstName);
    },
  );
}

/**
 * Drives the screen's two-turn workflow: read the row, then change one value.
 *
 * Refactoring Rationale: the two turns are driven as two separate operator actions rather than as one
 * form submission, because in the reference they ARE two terminal turns. CICS is pseudo-conversational:
 * the task ends at every screen turn and `DFHCOMMAREA` carries the continuity, which is why
 * `app/cbl/COUSR02C.cbl` reads on its Enter arm (L109-L110) and writes on a later PF5 or PF3 arm
 * (L122-L123, L111-L119). AAP section 0.7.1 establishes that the re-entry discriminator
 * `CDEMO-PGM-CONTEXT` (`app/cpy/COCOM01Y.cpy` L29-L31) disappears ENTIRELY in the target, so the
 * two-turn user experience is preserved as fidelity while the mechanism underneath it is ordinary
 * client state. This helper exists so that fidelity is exercised the way an operator meets it.
 * @param {Awaited<ReturnType<typeof renderInAppShell>>['user']} user - The operator driving the screen.
 * @returns {Promise<void>} Resolves once the row has landed and the first name has been changed.
 */
async function fetchThenEdit(
  user: Awaited<ReturnType<typeof renderInAppShell>>['user'],
): Promise<void> {
  await waitForTheRowToLand();
  await user.clear(control('firstName'));
  await user.type(control('firstName'), EDITED_FIRST_NAME);
}

/**
 * Builds the normalised transport failure a refused request rejects with.
 *
 * ⚠️ Assumptions: the REAL `ApiRequestError` class is constructed rather than an object shaped like one.
 * `ui/src/screens/userUpdate/index.tsx` narrows every rejection through `isApiRequestError` before it
 * reads a problem document or a status, and that guard tests the class -- so a hand-rolled
 * `{ status, problem }` is dropped by it and the screen falls to its catch-all arm. The case then asserts
 * against a path it did not mean to exercise, and it fails naming the wrong sentence, which is precisely
 * the confusion this helper exists to remove.
 * @param {number} status - HTTP status the service answered with, selecting the screen's own arm.
 * @returns {ApiRequestError} The failure, carrying a problem document with no field entries.
 */
function refusedWith(status: number): ApiRequestError {
  return new ApiRequestError(
    'PROBLEM',
    status,
    apiError({ status, fieldErrors: [] }),
    `the service answered ${String(status)}`,
  );
}

/*
 * WHY : ⚠️ Assumptions: the mounts and key presses below are NOT wrapped in an explicit `act` scope, and
 *       the omission is a measured decision rather than an oversight. Each of them emits "An update to
 *       UserUpdateScreen inside a test was not wrapped in act(...)" on standard error, because the
 *       screen's mount effect issues its read during the commit, the spy answers with an
 *       already-resolved promise, and the continuation runs on the microtask boundary that awaiting the
 *       ASYNCHRONOUS render helper creates -- before any act scope opens. Containment was implemented
 *       and measured: wrapping the mounts removed the 37 unwrapped-update blocks and introduced 28 of
 *       "The current testing environment is not configured to support act(...)"; adding
 *       `IS_REACT_ACT_ENVIRONMENT` to answer that produced 154 of the same, because
 *       `@testing-library/react` clears the flag for the duration of its own async wrapper and an
 *       explicit scope nested around `render`, `user-event` or `waitFor` therefore runs with it unset.
 *       Two noise channels are worse than one, so the containment is withdrawn.
 * WHY : ⚠️ Assumptions: the warning is a pre-existing property of this package rather than of this file.
 *       Measured over a full suite run at the time of writing, 212 of them are emitted across 12 other
 *       test files, and the root cause is common to all of them: `renderInAppShell` and
 *       `renderWithProviders` in `ui/src/test/setup.ts` are `async` -- deliberately, for the module
 *       identity and evaluation-order reasons that module records -- so awaiting either yields a
 *       microtask boundary the sibling files avoid only because they call `render` synchronously and let
 *       the following `waitFor` open the scope. A fix therefore belongs in that harness, as a
 *       synchronous entry point or an internally act-contained one, and not in a caller. It is recorded
 *       here rather than absorbed, because a caller-side workaround is what made it worse.
 * WHY : Assumptions: no assertion depends on the difference. React applies the update either way -- the
 *       warning reports the absence of a scope, not a lost update -- and all forty cases below observe
 *       the applied state through `waitFor`, which retries until it is there.
 */

/**
 * Reports every attention identifier this screen binds, with the label the mapset paints for it.
 *
 * Assumptions: FIVE entries, which is the largest key set of any base screen and is measured from the
 * program's own dispatch rather than inferred from the legend alone. `app/cbl/COUSR02C.cbl` L108-L131
 * is one `EVALUATE EIBAID` with arms for `DFHENTER` (L109), `DFHPF3` (L111), `DFHPF4` (L120), `DFHPF5`
 * (L122) and `DFHPF12` (L124), plus a `WHEN OTHER` at L127. The legend at `app/bms/COUSR02.bms`
 * L159-L164 paints exactly those five and no others, so the two sources corroborate each other.
 * @returns {ReadonlyArray<{ aid: CicsAid; label: string }>} The five bindings, in legend order.
 */
function boundKeys(): ReadonlyArray<{ aid: CicsAid; label: string }> {
  return [
    { aid: 'ENTER', label: USER_UPDATE_KEY_LABELS.ENTER },
    { aid: 'PFK03', label: USER_UPDATE_KEY_LABELS.PFK03 },
    { aid: 'PFK04', label: USER_UPDATE_KEY_LABELS.PFK04 },
    { aid: 'PFK05', label: USER_UPDATE_KEY_LABELS.PFK05 },
    { aid: 'PFK12', label: USER_UPDATE_KEY_LABELS.PFK12 },
  ];
}

/**
 * Groups the cases that hold every rendered control to the width, label and cursor
 * behaviour its mapset field declares.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function fieldConstraintCases(): void {
  /**
   * Confirms each rendered control bounds keystrokes at the width its mapset field declares.
   * @returns {Promise<void>} Resolves once all four widths have been asserted.
   */
  async function boundsEveryControlAtItsDeclaredWidth(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: `USRIDINI PIC X(8)` at `app/cpy-bms/COUSR02.CPY` L60, painted `LENGTH=8` at
    //       `app/bms/COUSR02.bms` L85-L89 and corroborated by `05 SEC-USR-ID PIC X(08).` at
    //       `app/cpy/CSUSR01Y.cpy` L18. The screen takes the bound from `USER_ID_MAX_LENGTH`, so the
    //       contract constant is asserted to equal the measured width rather than the number being
    //       written twice.
    expect(USER_ID_MAX_LENGTH).toBe(8);
    expectMaxLength(control('userId'), 8);

    // WHY : Assumptions: `FNAMEI PIC X(20)` at `app/cpy-bms/COUSR02.CPY` L66, painted `LENGTH=20` at
    //       `app/bms/COUSR02.bms` L103-L107 and corroborated by `05 SEC-USR-FNAME PIC X(20).` at
    //       `app/cpy/CSUSR01Y.cpy` L19.
    expectMaxLength(control('firstName'), 20);

    // WHY : Assumptions: `LNAMEI PIC X(20)` at `app/cpy-bms/COUSR02.CPY` L72, painted `LENGTH=20` at
    //       `app/bms/COUSR02.bms` L116-L120 and corroborated by `05 SEC-USR-LNAME PIC X(20).` at
    //       `app/cpy/CSUSR01Y.cpy` L20.
    expectMaxLength(control('lastName'), 20);

    // WHY : Assumptions: `USRTYPEI PIC X(1)` at `app/cpy-bms/COUSR02.CPY` L84, painted `LENGTH=1` at
    //       `app/bms/COUSR02.bms` L145-L149 and corroborated by `05 SEC-USR-TYPE PIC X(01).` at
    //       `app/cpy/CSUSR01Y.cpy` L22.
    expectMaxLength(control('userType'), 1);

    // WHY : Assumptions: the four widths the screen publishes are asserted as a whole as well as one by
    //       one, because a control silently dropped from the form would pass every individual query
    //       above by never being asked about.
    expect(USER_UPDATE_FIELD_WIDTHS).toEqual({
      userId: 8,
      firstName: 20,
      lastName: 20,
      userType: 1,
    });
  }

  /**
   * Pastes one value into one control, which is how an over-capacity entry actually arrives.
   *
   * ⚠️ Assumptions: a PASTE rather than a run of keystrokes, and the difference is what the three cases
   * below measure. Typing reaches the clamp once per character, so the last admitted character is the
   * only one it ever has to drop; a paste hands it the whole value at once, which is the arrival the
   * measured defect was reported from and the one where a naive implementation could split a surrogate
   * pair. Every specimen below is within the control's `maxLength` counted in UTF-16 units, so no case
   * depends on how the test DOM enforces that attribute -- only on what the screen does with what it is
   * handed.
   * @param {UserEvent} user - The interaction driver the mount returned.
   * @param {(typeof RENDERED_FIELDS)[number]} field - Which control to paste into.
   * @param {string} value - The value to paste.
   * @returns {Promise<void>} Resolves once the paste has been applied.
   */
  async function pasteInto(
    user: UserEvent,
    field: (typeof RENDERED_FIELDS)[number],
    value: string,
  ): Promise<void> {
    await user.clear(control(field));
    await user.click(control(field));
    await user.paste(value);
  }

  /**
   * Confirms a value that exactly fills a declared width is admitted whole.
   *
   * Purpose: establish the other side of the clamp. A measure that refused a value AT its declared
   * width would be the same defect in the opposite direction, and a twenty-character surname is the
   * ordinary case rather than an edge one.
   * @returns {Promise<void>} Resolves once the assertion has run.
   */
  async function admitsAValueThatFillsItsDeclaredWidth(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();
    const filled = 'A'.repeat(USER_UPDATE_FIELD_WIDTHS.lastName);

    await pasteInto(user, 'lastName', filled);

    expect(
      control('lastName'),
      'a value at the declared width must survive intact in all three readings of that width',
    ).toHaveValue(filled);
  }

  /**
   * Confirms capacity is measured in the record's BYTES, not in the control's UTF-16 code units.
   *
   * ⚠️ Purpose: this is the defect. `SEC-USR-LNAME PIC X(20)` (`app/cpy/CSUSR01Y.cpy` L20) is twenty
   * BYTES on the record and `maxLength` counts UTF-16 code units, so twenty composed accents are twenty
   * code units the attribute admits and forty bytes the record cannot hold. Before the clamp the
   * control accepted all twenty and the overflow was discovered by whatever refused it downstream.
   *
   * Assumptions: the survivor count is COMPUTED from the specimen's own byte cost rather than written
   * as ten, so the case states the rule instead of a number.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function measuresCapacityInBytesRatherThanCodeUnits(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();
    const declared = USER_UPDATE_FIELD_WIDTHS.lastName;
    const admitted = Math.floor(declared / new TextEncoder().encode(COMPOSED_ACCENT).length);

    expect(
      COMPOSED_ACCENT.repeat(declared).length,
      'the specimen must be one the maxLength attribute admits, or the case proves nothing',
    ).toBeLessThanOrEqual(declared);

    await pasteInto(user, 'lastName', COMPOSED_ACCENT.repeat(declared));

    expect(
      control('lastName'),
      'a twenty-byte field must hold ten two-byte characters and not twenty of them',
    ).toHaveValue(COMPOSED_ACCENT.repeat(admitted));
  }

  /**
   * Confirms the identifier the read is issued on is measured the same way, and never half a character.
   *
   * ⚠️ Purpose: on this screen the clamped field is the KEY. Its declared width is eight in both units
   * (`SEC-USR-ID PIC X(08)`, `app/cpy/CSUSR01Y.cpy` L18), and a key silently truncated at eight UTF-16
   * units would be a DIFFERENT key from the one typed -- so the turn it opens would read a record the
   * operator never named. Each specimen here is one code point, two UTF-16 units and four bytes, which
   * also covers the failure a byte-arithmetic implementation would produce: a clamp walking UTF-16 units
   * could stop halfway through one and leave an unpaired surrogate.
   *
   * Assumptions: the survivors are counted as CODE POINTS through the string iterator, because
   * `String.prototype.length` is the measure under test and asserting with it would beg the question.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function keepsAnAstralCharacterWholeInTheKey(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();
    const declared = USER_UPDATE_FIELD_WIDTHS.userId;
    const admitted = Math.floor(declared / new TextEncoder().encode(ASTRAL_CHARACTER).length);
    const pasted = ASTRAL_CHARACTER.repeat(admitted + 2);

    expect(
      pasted.length,
      'the specimen must be one the maxLength attribute admits, or the case proves nothing',
    ).toBeLessThanOrEqual(declared);

    await pasteInto(user, 'userId', pasted);

    const held = control('userId').value;

    expect([...held], 'the key must hold whole characters up to its byte capacity').toHaveLength(
      admitted,
    );
    expect(held, 'and every one of them must be the character that was pasted').toBe(
      ASTRAL_CHARACTER.repeat(admitted),
    );
  }

  /**
   * Confirms two spellings of one accent converge, so an edited name has one form on the wire.
   *
   * ⚠️ Purpose: the composed and decomposed spellings are indistinguishable on the glass and differ byte
   * for byte, which on a system whose keys are compared as characters means two records an operator
   * cannot tell apart and a search that finds one of them. Composing where the value is captured removes
   * the ambiguity at the last boundary where it can still be removed.
   * @returns {Promise<void>} Resolves once the assertions have run.
   */
  async function normalisesTwoSpellingsOfOneAccent(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();

    expect(
      [...DECOMPOSED_ACCENT],
      'the specimen must genuinely be the longer spelling, or nothing is being normalised',
    ).toHaveLength(2);

    await pasteInto(user, 'firstName', DECOMPOSED_ACCENT);

    expect(
      control('firstName'),
      'the captured value must carry the one canonical spelling',
    ).toHaveValue(COMPOSED_ACCENT);
  }

  /**
   * Confirms every control is sized to the character width its copybook declares.
   *
   * ⚠️ Purpose: regress the measured geometry. An eight-character identifier input rendered 1172 pixels
   * wide and the blank-field asterisk this screen paints at the field's right-hand edge landed at
   * x≈1211, roughly 1150 pixels from the value it qualifies -- and the one-position user type rendered
   * at that same full width, so nothing about a control said how much it would take.
   *
   * ⚠️ Assumptions: the DECLARATION is asserted and not a rendered pixel width, because the test DOM
   * performs no layout -- every box in it measures zero, so a width assertion would pass on the broken
   * value too. What can be checked here is that each control carries a maximum measure stated in the
   * field's own character units. The pixel outcome was measured in a browser; this case exists to stop
   * the declaration being removed.
   * @returns {Promise<void>} Resolves once all four controls have been asserted.
   */
  async function sizesEveryControlToItsDeclaredWidth(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    for (const field of RENDERED_FIELDS) {
      const declared = USER_UPDATE_FIELD_WIDTHS[field];
      const measure = control(field).style.maxInlineSize;

      expect(measure, `${field} must declare a maximum measure of its own`).not.toBe('');
      expect(measure, `${field} must be capped at its own declared width`).toContain(
        `${String(declared)}ch`,
      );
      expect(
        control(field).style.inlineSize,
        `${field} must still shrink inside a narrow viewport`,
      ).toBe('100%');
    }
  }

  /**
   * Confirms each of the four labels is carried character-for-character, trailing space included.
   * @returns {void} Nothing; the four published constants are compared to the mapset's own literals.
   */
  function carriesEveryLabelCharacterForCharacter(): void {
    // WHY : Assumptions: AAP Transformation Rule T8 requires a user-visible string to survive
    //       character-for-character, and the fourth label is the one where that bites. The mapset paints
    //       `INITIAL='User Type: '` at `LENGTH=11` (`app/bms/COUSR02.bms` L140-L144) where the visible
    //       text is ten characters, so the eleventh is a space the terminal painted -- and it is part of
    //       the value. Trimming it in the published constant would be a silent edit to a user-visible
    //       string, and no label QUERY can catch that because every query normaliser trims first, which
    //       is exactly why this assertion exists separately from the queries above.
    expect(USER_UPDATE_FIELD_LABELS.userId).toBe('Enter User ID:');
    expect(USER_UPDATE_FIELD_LABELS.firstName).toBe('First Name:');
    expect(USER_UPDATE_FIELD_LABELS.lastName).toBe('Last Name:');
    expect(USER_UPDATE_FIELD_LABELS.userType).toBe('User Type: ');
    expect(USER_UPDATE_FIELD_LABELS.userType).toHaveLength(11);
  }

  /**
   * Confirms the identifier control is named as the search key the reference reads, not as an editor.
   * @returns {Promise<void>} Resolves once the label has been asserted.
   */
  async function namesTheIdentifierControlAsTheSearchKey(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();

    // WHY : Assumptions: this mapset's identifier field is `USRIDIN`, NOT the `USERIDI` that
    //       `app/cpy-bms/COUSR01.CPY` declares on the user-add screen -- the two screens spell it
    //       differently on purpose. This one is the browse's own search key, the same `USRIDINI` name
    //       `app/cpy-bms/COUSR00.CPY` uses, and `app/bms/COUSR02.bms` L80-L84 labels it
    //       `INITIAL='Enter User ID:'` -- an instruction to type a key to look up, where an editor's
    //       label would name the value being edited. That single field serves BOTH roles here, which is
    //       why the route parameter and the control have to be reconciled rather than treated as two
    //       inputs; the routing cases below do that.
    expect(USER_UPDATE_FIELD_LABELS.userId).toBe('Enter User ID:');
    expect(control('userId')).toBeInTheDocument();
  }

  /**
   * Confirms exactly one control claims the initial cursor, as the mapset's single `IC` operand does.
   * @returns {Promise<void>} Resolves once the count has been asserted.
   */
  async function placesExactlyOneInitialCursor(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: `app/bms/COUSR02.bms` carries exactly ONE `IC` operand, on `USRIDIN` at L85
    //       (`ATTRB=(FSET,IC,NORM,UNPROT)`), and a 3270 map has one initial cursor position by
    //       construction. `app/cbl/COUSR02C.cbl` agrees from the program side: L98 moves `-1` into
    //       `USRIDINL` on first entry before anything else is decided. A second `autoFocus` in a browser
    //       is not a second cursor -- it is a race between two controls for the one cursor -- so the
    //       count is asserted rather than only the winner.
    const focused = Array.from(document.querySelectorAll('input')).filter(
      /**
       * Reports whether one input currently holds the document's focus.
       * @param {HTMLInputElement} candidate - One rendered input.
       * @returns {boolean} `true` when it is the active element.
       */
      (candidate: HTMLInputElement): boolean => document.activeElement === candidate,
    );

    expect(focused).toHaveLength(1);
    expect(focused[0]).toBe(control('userId'));
  }

  /**
   * Confirms the user-type control admits only the two characters the domain declares.
   * @returns {Promise<void>} Resolves once both admitted and refused characters have been asserted.
   */
  async function admitsOnlyTheTwoUserTypeCharacters(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: the domain is `'A'` and `'U'` and nothing else, from the `88` condition names
    //       `CDEMO-USRTYP-ADMIN VALUE 'A'` and `CDEMO-USRTYP-USER VALUE 'U'` at
    //       `app/cpy/COCOM01Y.cpy` L26-L28, carried into the target as the `UserType = 'A' | 'U'` union
    //       in `ui/src/api/types.ts` and as `CHECK (user_type IN ('A','U'))` in the migrated schema.
    //       The two values below are typed as that union WITHOUT a cast, so a widening of the union
    //       would fail the compiler here rather than reaching a request body.
    const admitted: readonly UserType[] = ['A', 'U'];

    for (const value of admitted) {
      await user.clear(control('userType'));
      await user.type(control('userType'), value);
      expect(control('userType')).toHaveValue(value);
    }

    // WHY : Assumptions: a character outside the domain leaves the control as it was, with no message.
    //       `app/cbl/COUSR02C.cbl` has NO domain check for this field -- L204 tests it for blank alone --
    //       so the refusal is not transcribed from the program; it is the same affordance the declared
    //       `LENGTH=1` already provides, which is a 3270 refusing a keystroke silently.
    await user.clear(control('userType'));
    await user.type(control('userType'), 'X');
    expect(control('userType')).toHaveValue('');
  }

  /**
   * Confirms the caption, the one hint and the header band the shell paints are all present verbatim.
   * @returns {Promise<void>} Resolves once each has been asserted.
   */
  async function paintsTheCaptionHintAndHeaderBand(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();

    // WHY : Assumptions: the caption is the mapset's own row-4 body field, `INITIAL='Update User'` at
    //       `LENGTH=11` with `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL` (`app/bms/COUSR02.bms` L75-L79).
    //       It sits below the two 40-character title fields on rows 1 and 2, which belong to the shell.
    expect(screen.getByText(USER_UPDATE_CAPTION)).toBeInTheDocument();

    // WHY : Assumptions: the hint is `INITIAL='(A=Admin, U=User)'` at `LENGTH=17`
    //       (`app/bms/COUSR02.bms` L150-L154). It is the ONLY place the domain is advertised to an
    //       operator, since the program carries no domain check to produce a message.
    expect(screen.getByText(USER_UPDATE_FIELD_HINTS.userType)).toBeInTheDocument();

    // WHY : Assumptions: the header band is the shell's, painted from the identity this screen
    //       delegates -- `WS-TRANID PIC X(04) VALUE 'CU02'` at `app/cbl/COUSR02C.cbl` L37 and
    //       `WS-PGMNAME PIC X(08) VALUE 'COUSR02C'` at L36, which the reference moves into `TRNNAMEO`
    //       and `PGMNAMEO` in `POPULATE-HEADER-INFO` at L302-L303. The band's own six fields --
    //       `TRNNAMEI X(4)`, `TITLE01I X(40)`, `CURDATEI X(8)`, `PGMNAMEI X(8)`, `TITLE02I X(40)` and
    //       `CURTIMEI X(8)` at `app/cpy-bms/COUSR02.CPY` L24-L54 -- are owned and covered by the shell's
    //       own tests, so what is asserted here is that this screen reaches it with its own identity.
    const titleBand = screen.getByRole('banner');
    expect(titleBand).toHaveTextContent('CU02');
    expect(titleBand).toHaveTextContent('COUSR02C');
  }

  /**
   * Records the two distinct message widths this mapset carries, without asserting either as a size.
   * @returns {void} Nothing; the distinction is asserted against the catalog rather than the layout.
   */
  function keepsTheMessageWidthsDistinct(): void {
    // WHY : Assumptions: TWO widths exist and neither is a correction of the other. `ERRMSG` is declared
    //       `LENGTH=78` at `app/bms/COUSR02.bms` L155-L158 and `ERRMSGI PIC X(78)` at
    //       `app/cpy-bms/COUSR02.CPY` L90 -- that is what the terminal PAINTS. The CONTENT contract is
    //       75, from `CCARD-ERROR-MSG PIC X(75)` and `CCARD-RETURN-MSG PIC X(75)` at
    //       `app/cpy/CVCRD01Y.cpy` L28-L29 -- that is what a message crossing the pseudo-conversational
    //       boundary may CARRY. Normalising 75 up to 78 would silently widen the carry contract, and
    //       normalising 78 down to 75 would shrink the painted field, so both are recorded.
    //       Assumptions: this case asserts the two CONSTANTS and deliberately makes no assertion about
    //       rendered geometry -- AAP gap G1 gives up pixel-for-character positioning, so a computed
    //       width or a character offset is not a property this tree preserves.
    expect(MESSAGE_BAND_BY_MAPSET[USER_UPDATE_MAPSET].displayWidth).toBe(78);
    expect(MESSAGE_BAND_CONTENT_WIDTH).toBe(75);
    expect(MESSAGE_BAND_BY_MAPSET[USER_UPDATE_MAPSET].map).toBe('COUSR2A');
  }

  it(
    'bounds every rendered control at the width its mapset field declares',
    boundsEveryControlAtItsDeclaredWidth,
  );
  it(
    'carries every field label character-for-character, trailing space included',
    carriesEveryLabelCharacterForCharacter,
  );
  it(
    'labels the identifier control as the search key, spelled USRIDIN and not USERIDI',
    namesTheIdentifierControlAsTheSearchKey,
  );
  it(
    'places exactly one initial cursor, matching the mapset single IC operand',
    placesExactlyOneInitialCursor,
  );
  it(
    'admits only the two user-type characters the domain declares',
    admitsOnlyTheTwoUserTypeCharacters,
  );
  it(
    'paints the row-4 caption, the one hint and its own header identity',
    paintsTheCaptionHintAndHeaderBand,
  );
  it(
    'keeps the painted 78-character band and the 75-character content contract distinct',
    keepsTheMessageWidthsDistinct,
  );
  it('admits a value that fills its declared width', admitsAValueThatFillsItsDeclaredWidth);
  it(
    'measures capacity in the record bytes rather than in code units',
    measuresCapacityInBytesRatherThanCodeUnits,
  );
  it('keeps an astral character whole in the key', keepsAnAstralCharacterWholeInTheKey);
  it('normalises two spellings of one accent', normalisesTwoSpellingsOfOneAccent);
  it('sizes every control to its declared width', sizesEveryControlToItsDeclaredWidth);
}

describe('field constraints measured from the mapset and the record layout', fieldConstraintCases);

/**
 * Groups the cases that hold the screen to the reference two-turn fetch-then-save workflow.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function twoTurnWorkflowCases(): void {
  /**
   * Confirms Enter READS and does not write, which is the distinction this screen is easiest to lose.
   * @returns {Promise<void>} Resolves once the read has been observed and the write ruled out.
   */
  async function enterFetchesAndNeverWrites(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen('/users/OTHER001/edit');
    await waitFor(
      /**
       * Waits for the routed read to settle before the typed one is issued.
       * @returns {void} Nothing; throws until the routed read has been recorded.
       */
      () => {
        expect(identityTransport.getUser).toHaveBeenCalledWith('OTHER001');
      },
    );

    identityTransport.getUser.mockClear();
    await user.clear(control('userId'));
    await user.type(control('userId'), EDITED_USER_ID);
    await pressPfKey(user, 'ENTER');

    // WHY : Assumptions: Enter is `Fetch` and not `Submit`, from the legend's own first descriptor
    //       `ENTER=Fetch` (`app/bms/COUSR02.bms` L163) and from the dispatch arm at
    //       `app/cbl/COUSR02C.cbl` L109-L110, which performs `PROCESS-ENTER-KEY` -- a paragraph whose
    //       only file operation is `READ-USER-SEC-FILE` (L163). Every other screen in this application
    //       submits on Enter, which is exactly why the negative half below matters more than the
    //       positive one: conflating the fetch with a submit is the most likely implementation error on
    //       this screen, and it would pass any assertion that only checked that "something happened".
    await waitFor(
      /**
       * Waits until the typed identifier has been read.
       * @returns {void} Nothing; throws until the read has been recorded.
       */
      () => {
        expect(identityTransport.getUser).toHaveBeenCalledWith(EDITED_USER_ID);
      },
    );
    expect(identityTransport.updateUser).not.toHaveBeenCalled();
  }

  /**
   * Confirms a completed read seeds the three editable controls from the row the service returned.
   * @returns {Promise<void>} Resolves once all three values have been asserted.
   */
  async function seedsTheEditableControlsFromTheRow(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: THREE controls are seeded, not four. `app/cbl/COUSR02C.cbl` L167-L170 moves the
    //       first name, the last name, the credential and the user type into the map; the credential's
    //       move at L169 has no counterpart because this screen renders no credential control --
    //       registered divergence D-10, asserted directly further below. The identifier is not seeded
    //       from the row either: it is the key the read was issued FOR and stays as the route or the
    //       operator supplied it.
    expect(control('firstName')).toHaveValue(STORED_ROW.firstName);
    expect(control('lastName')).toHaveValue(STORED_ROW.lastName);
    expect(control('userType')).toHaveValue(STORED_ROW.userType);
    expect(control('userId')).toHaveValue(EDITED_USER_ID);
  }

  /**
   * Confirms the read's own sentence prompts the second turn, with its space before the ellipsis intact.
   * @returns {Promise<void>} Resolves once the sentence has been matched verbatim.
   */
  async function promptsTheSaveAfterAReadWithTheSpaceBeforeTheEllipsis(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : ⚠️ Assumptions: there is a SPACE before the ellipsis and it is load-bearing. The literal at
    //       `app/cbl/COUSR02C.cbl` L336 is `'Press PF5 key to save your updates ...'` -- every other
    //       sentence this program emits runs the ellipsis straight on with no preceding space, so a
    //       formatter, a lint autofix or an author normalising "inconsistent" whitespace would delete it
    //       silently and AAP Rule T8's character-for-character guarantee would be gone with it. The
    //       length assertion below is what makes that specific character falsifiable: a collapsed
    //       variant is one character shorter and would otherwise still read correctly to a human.
    //       Assumptions: `expectVerbatimMessage` is used rather than a plain text query because its
    //       normaliser is configured `collapseWhitespace: false`, so the run of whitespace is compared
    //       rather than folded away -- a default query would match the collapsed variant too.
    expect(UPDATE_MESSAGES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES).toBe(
      'Press PF5 key to save your updates ...',
    );
    expect(UPDATE_MESSAGES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES).toContain(' ...');
    expect(UPDATE_MESSAGE_LINES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES).toEqual([336]);
    const sentence = expectVerbatimMessage(UPDATE_MESSAGES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES);

    expect(sentence).toBeInTheDocument();

    // WHY : ⚠️ Assumptions: the sentence resolves through the NEUTRAL role and not the informational
    //       one, which is the distinction `MOVE DFHNEUTR TO ERRMSGC` at `app/cbl/COUSR02C.cbl` L338
    //       makes -- L241 moves `DFHRED` for a refusal and L371 moves `DFHGREEN` for the
    //       acknowledgement, so the program uses three colours on one field and each must map to its
    //       own severity. `ui/src/theme/tokens.ts` resolves NEUTRAL to `colorTextSecondary` and
    //       TURQUOISE to `colorTextLabel`; publishing this line at the `'info'` severity painted it in
    //       the informational hue, which is the substitution the token bridge's G3 note exists to
    //       prevent and which a rendering review recorded on this route as info-coloured content in
    //       the outcome band. Asserted through the token name, so the case holds no colour literal.
    expect(sentence.getAttribute('style') ?? '').toContain(
      cssVariableSegment(BMS_TEXT_COLOR_TOKENS.NEUTRAL),
    );
    expect(sentence.getAttribute('style') ?? '').not.toContain(
      cssVariableSegment(BMS_TEXT_COLOR_TOKENS.TURQUOISE),
    );
  }

  /**
   * Confirms a save that changes nothing is refused and issues no write.
   * @returns {Promise<void>} Resolves once the refusal has been matched and the write ruled out.
   */
  async function refusesASaveThatChangesNothing(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();

    await pressPfKey(user, 'PFK05');

    // WHY : ⚠️ Assumptions: this sentence ALSO carries a space before its ellipsis --
    //       `'Please modify to update ...'` at `app/cbl/COUSR02C.cbl` L239 -- and it is the second of the
    //       program's only two such literals. The same deletion hazard applies, so the same explicit
    //       assertions are made rather than relying on the rendered text alone.
    expect(UPDATE_MESSAGES.PLEASE_MODIFY_TO_UPDATE).toBe('Please modify to update ...');
    expect(UPDATE_MESSAGES.PLEASE_MODIFY_TO_UPDATE).toContain(' ...');
    expect(UPDATE_MESSAGE_LINES.PLEASE_MODIFY_TO_UPDATE).toEqual([239]);

    await waitFor(
      /**
       * Waits until the refusal has replaced the read's prompt in the band.
       * @returns {void} Nothing; throws until the refusal is present.
       */
      () => {
        expect(expectVerbatimMessage(UPDATE_MESSAGES.PLEASE_MODIFY_TO_UPDATE)).toBeInTheDocument();
      },
    );

    // WHY : Assumptions: the write is NOT issued, which is the whole point of the outcome.
    //       `app/cbl/COUSR02C.cbl` L236-L243 performs `UPDATE-USER-SEC-FILE` only when
    //       `USR-MODIFIED-YES` was set by one of its four comparisons; with nothing changed it takes the
    //       `ELSE` at L238 and reaches no `EXEC CICS REWRITE` at all.
    expect(identityTransport.updateUser).not.toHaveBeenCalled();
  }

  /**
   * Confirms a genuine change is written exactly once, carrying exactly the three declared members.
   * @returns {Promise<void>} Resolves once the single write and its body have been asserted.
   */
  async function writesOnceAfterAGenuineChange(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');

    await waitFor(
      /**
       * Waits until the write has been issued.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );

    // WHY : Assumptions: the identifier is the ADDRESS and the body carries three members.
    //       `UpdateUserRequest` in `ui/src/api/types.ts` declares `firstName`, `lastName` and `userType`
    //       and nothing else, and `toEqual` on the whole body is what makes a fourth member fail --
    //       asserting the three individually would pass while a credential rode along beside them.
    expect(identityTransport.updateUser).toHaveBeenCalledWith(EDITED_USER_ID, {
      firstName: EDITED_FIRST_NAME,
      lastName: STORED_ROW.lastName,
      userType: STORED_ROW.userType,
    });

    // WHY : Assumptions: the acknowledgement is COMPOSED from the catalog template rather than compared
    //       to a retyped sentence, because the reference composes it: the `STRING` at
    //       `app/cbl/COUSR02C.cbl` L372-L374 concatenates `'User '`, the key `DELIMITED BY SPACE` and
    //       `' has been updated ...'`. Composing it here applies the same delimiter rule, so the leading
    //       literal and the space before this ellipsis cannot be lost to a retyped string either.
    await waitFor(
      /**
       * Waits until the composed acknowledgement has reached the band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(
          expectVerbatimMessage(
            formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
              'SEC-USR-ID': SAVED_ROW.userId,
            }),
          ),
        ).toBeInTheDocument();
      },
    );
  }

  /**
   * Confirms the second turn re-reads the row rather than carrying it in any server-side session field.
   * @returns {Promise<void>} Resolves once the re-read and the absence of stored state are asserted.
   */
  async function carriesNoSessionFieldBetweenTheTwoTurns(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');
    await waitFor(
      /**
       * Waits until the write has been issued, by which point the save's own re-read has happened.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );

    // WHY : Refactoring Rationale: in the reference the two turns are bridged by `DFHCOMMAREA`, which
    //       CICS echoes to the terminal and back because the task ends between them -- and the save arm
    //       nonetheless RE-READS the row at `app/cbl/COUSR02C.cbl` L216-L217 before comparing at L219 to
    //       L231. AAP section 0.7.1 removes that structure entirely: the re-entry discriminator
    //       `CDEMO-PGM-CONTEXT` (`app/cpy/COCOM01Y.cpy` L29-L31) has no target at all, so continuity is
    //       ordinary component state. TWO reads is the observable proof of that: a second read means the
    //       save fetched the row it compares against instead of trusting anything carried between turns.
    expect(identityTransport.getUser).toHaveBeenCalledTimes(2);

    // WHY : Assumptions: nothing is written to web storage by either turn, which is the other half of
    //       "no session field". `ui/src/hooks/useAuth.ts` withdrew its `AUTH_STORAGE_KEY` constant and
    //       its token reader deliberately -- the bearer is now held in a module variable inside
    //       `ui/src/api/client.ts`, which publishes only its setter -- so there is no key left to read
    //       back and the honest assertion is that both stores stay empty.
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  }

  it('fetches on Enter and never writes on it', enterFetchesAndNeverWrites);
  it(
    'seeds the three editable controls from the row the read returned',
    seedsTheEditableControlsFromTheRow,
  );
  it(
    'prompts the save with L336, space before the ellipsis intact',
    promptsTheSaveAfterAReadWithTheSpaceBeforeTheEllipsis,
  );
  it(
    'refuses a save that changes nothing with L239 and issues no write',
    refusesASaveThatChangesNothing,
  );
  it(
    'writes exactly once after a genuine change, with exactly three members',
    writesOnceAfterAGenuineChange,
  );
  it(
    're-reads on the save turn, carrying no session field between the two turns',
    carriesNoSessionFieldBetweenTheTwoTurns,
  );
}

describe('the two-turn fetch-then-save workflow', twoTurnWorkflowCases);

/**
 * Groups the cases that hold every sentence this screen paints to the verbatim catalog.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function messageTextCases(): void {
  /*
   * WHY : Alternatives Considered: retyping each sentence as a literal in this file, which is the
   *       shorter spelling and is rejected outright. A retyped literal proves only that this file and
   *       the screen agree; if the screen paraphrased a sentence and this file paraphrased it the same
   *       way, every case here would pass while AAP Rule T8's character-for-character fidelity to the
   *       baseline was already lost. Asserting through `ui/src/messages/messages.ts` -- the one module
   *       that transcribes the COBOL literals and records the line each was measured from -- means a
   *       drift has to happen in the catalog, where the provenance index makes it visible.
   */

  /**
   * Confirms the blank-field cascade's four sentences are the catalogued ones, at their measured lines.
   * @returns {void} Nothing; the four constants and their provenance are asserted.
   */
  function carriesTheBlankFieldCascadeVerbatim(): void {
    // WHY : Assumptions: `NOT` is capitalised in every one of these and is NOT normalised. The literals
    //       are `'User ID can NOT be empty...'` (`app/cbl/COUSR02C.cbl` L148 and L182),
    //       `'First Name can NOT be empty...'` (L188), `'Last Name can NOT be empty...'` (L194) and
    //       `'User Type can NOT be empty...'` (L206). Each runs its ellipsis straight on, with NO
    //       preceding space -- the opposite of L239 and L336 -- which is why both spellings are asserted
    //       in this file rather than one being taken as the program's house style.
    expect(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY).toBe('User ID can NOT be empty...');
    expect(SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY).toBe('First Name can NOT be empty...');
    expect(SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY).toBe('Last Name can NOT be empty...');
    expect(SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY).toBe('User Type can NOT be empty...');
    expect(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY).not.toContain(' ...');

    // WHY : ⚠️ Assumptions: ONE catalog entry carries BOTH of this program's emission sites for the
    //       identifier's sentence -- L148 on the fetch arm and L182 on the save arm -- because the two
    //       literals are byte-identical. The catalog is keyed by distinct STRING and records every site
    //       in its provenance, so it must never accumulate a near-duplicate entry per site; the
    //       assertion below is what would fail if it did.
    const identifierSites = SHARED_MESSAGE_SOURCES.USER_ID_CAN_NOT_BE_EMPTY.filter(
      /**
       * Selects the provenance entry belonging to this screen's own program.
       * @param {object} entry - One provenance record from the catalog's own index.
       * @param {string} entry.file - Repository-relative path of the program the record names.
       * @returns {boolean} `true` when the record names `COUSR02C`.
       */
      (entry: { file: string }): boolean => entry.file === 'app/cbl/COUSR02C.cbl',
    );

    expect(identifierSites).toHaveLength(1);
    expect(identifierSites[0]?.lines).toEqual([148, 182]);
  }

  /**
   * Confirms the two lookup-failure sentences keep their differing capitalisation and their two sites.
   * @returns {void} Nothing; the constants and their provenance are asserted.
   */
  function keepsTheTwoFailureSentencesDistinct(): void {
    // WHY : ⚠️ Assumptions: the two verbs are capitalised DIFFERENTLY and neither is normalised.
    //       `app/cbl/COUSR02C.cbl` L349 reads `'Unable to lookup User...'` with a lower-case "lookup"
    //       and L386 reads `'Unable to Update User...'` with a capital "Update". They are two literals
    //       in one program written by one author, so the inconsistency is real rather than a
    //       transcription slip -- and "correcting" either would be a silent edit to a user-visible
    //       string.
    expect(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER).toBe('Unable to lookup User...');
    expect(SHARED_MESSAGES.UNABLE_TO_UPDATE_USER).toBe('Unable to Update User...');

    // WHY : Assumptions: the not-found sentence is the SAME string on both file operations -- the read's
    //       `DFHRESP(NOTFND)` arm at L342 and the write's at L379 -- so it too is one catalog entry with
    //       two recorded lines, and the same no-near-duplicates property is asserted for it.
    expect(SHARED_MESSAGES.USER_ID_NOT_FOUND).toBe('User ID NOT found...');

    const notFoundSites = SHARED_MESSAGE_SOURCES.USER_ID_NOT_FOUND.filter(
      /**
       * Selects the provenance entry belonging to this screen's own program.
       * @param {object} entry - One provenance record from the catalog's own index.
       * @param {string} entry.file - Repository-relative path of the program the record names.
       * @returns {boolean} `true` when the record names `COUSR02C`.
       */
      (entry: { file: string }): boolean => entry.file === 'app/cbl/COUSR02C.cbl',
    );

    expect(notFoundSites).toHaveLength(1);
    expect(notFoundSites[0]?.lines).toEqual([342, 379]);
  }

  /**
   * Confirms a refused read paints the reference's own read-side sentence.
   * @returns {Promise<void>} Resolves once the sentence has been matched.
   */
  async function paintsTheReadFailureSentence(): Promise<void> {
    // WHY : Assumptions: a 500 is used rather than a 404 because the two reach DIFFERENT arms of the
    //       reference's `EVALUATE WS-RESP-CD` -- 404 is the `DFHRESP(NOTFND)` arm at
    //       `app/cbl/COUSR02C.cbl` L340-L345 and anything else is the `WHEN OTHER` arm at L346-L352.
    //       This case covers the second; the one after it covers the first.
    identityTransport.getUser.mockRejectedValue(refusedWith(500));
    await mountScreen();

    await waitFor(
      /**
       * Waits until the read's failure sentence reaches the band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER);
      },
    );
  }

  /**
   * Confirms a read answered 404 paints the not-found sentence rather than the generic failure.
   * @returns {Promise<void>} Resolves once the sentence has been matched.
   */
  async function paintsTheNotFoundSentenceOnAMissingRow(): Promise<void> {
    identityTransport.getUser.mockRejectedValue(refusedWith(404));
    await mountScreen();

    // WHY : Assumptions: 404 is the target's expression of the reference's `DFHRESP(NOTFND)` arm at
    //       `app/cbl/COUSR02C.cbl` L340-L345, which is a DIFFERENT sentence from the `WHEN OTHER` arm at
    //       L346-L352. Both arms exist in one `EVALUATE`, so a screen that collapsed them would tell an
    //       operator a lookup had failed when in fact no such user exists.
    await waitFor(
      /**
       * Waits until the not-found sentence reaches the band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.USER_ID_NOT_FOUND);
      },
    );
  }

  /**
   * Confirms a refused write paints the write-side sentence with its capital "Update".
   * @returns {Promise<void>} Resolves once the sentence has been matched.
   */
  async function paintsTheWriteFailureSentence(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockRejectedValue(refusedWith(500));
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');

    // WHY : Assumptions: the write's `WHEN OTHER` arm has its OWN sentence -- L386 -- distinct from the
    //       read's at L349, so the stage a failure came out of selects which one is painted. A screen
    //       using one sentence for both would be indistinguishable here from a correct one if only the
    //       read path were exercised, which is why both stages are covered.
    await waitFor(
      /**
       * Waits until the write's failure sentence reaches the band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.UNABLE_TO_UPDATE_USER);
      },
    );
  }

  /**
   * Confirms an unmapped key raises the shared invalid-key sentence at its declared 50-character width.
   * @returns {Promise<void>} Resolves once the sentence has been matched.
   */
  async function raisesTheInvalidKeySentence(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: PF9 is genuinely unmapped on this screen. Its `EVALUATE EIBAID`
    //       (`app/cbl/COUSR02C.cbl` L108-L131) names five attention identifiers and answers everything
    //       else through `WHEN OTHER` at L127-L130, which moves `CCDA-MSG-INVALID-KEY` at L129. This
    //       screen is therefore one of the emitting programs rather than one that leaves the key silent.
    await pressPfKey(user, 'PFK09');

    // WHY : Assumptions: the FIELD is `PIC X(50)` and the LITERAL is 49 characters, and the two are not
    //       the same number. `app/cpy/CSMSG01Y.cpy` L20-L21 declares
    //       `CCDA-MSG-INVALID-KEY PIC X(50) VALUE 'Invalid key pressed. Please see below...         '`;
    //       the literal's nine trailing blanks leave it one short of the declared width, which COBOL
    //       pads on the `MOVE`. The declared width and the transcribed text are therefore asserted
    //       SEPARATELY -- asserting the text is fifty characters long would demand a tenth blank the
    //       copybook does not write, and asserting only the text would let the declared width drift.
    expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
    expect(COMMON_MESSAGES.INVALID_KEY.source).toEqual({
      file: 'app/cpy/CSMSG01Y.cpy',
      lines: [21],
    });
    expect(INVALID_KEY_PRESSED).toBe('Invalid key pressed. Please see below...         ');
    expect(INVALID_KEY_PRESSED.length).toBeLessThanOrEqual(
      COMMON_MESSAGES.INVALID_KEY.declaredWidth,
    );

    await waitFor(
      /**
       * Waits until the invalid-key sentence reaches the band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(messageBand()).toHaveTextContent(INVALID_KEY_PRESSED.trim());
      },
    );
  }

  /**
   * Confirms the owned VSAM file name never surfaces in the browser.
   * @returns {Promise<void>} Resolves once its absence has been asserted.
   */
  async function neverSurfacesTheOwnedFileName(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: `WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '` at `app/cbl/COUSR02C.cbl` L39 --
    //       two trailing blanks padding the eight-character field -- is a SERVER-SIDE resource name and
    //       has no business reaching a browser. The reference passes it as the `DATASET` operand of its
    //       `EXEC CICS READ` (L323) and `EXEC CICS REWRITE` (L361) and never paints it, so the faithful
    //       target behaviour is its absence. In the migrated system it does not even name a real
    //       resource: AAP section 0.5.1.2 replaces the file with the `auth.users` table, so surfacing it
    //       would disclose an implementation detail that is no longer true.
    expect(document.body.textContent).not.toContain('USRSEC');
  }

  it(
    'carries the blank-field cascade verbatim, with one catalog entry per distinct string',
    carriesTheBlankFieldCascadeVerbatim,
  );
  it(
    'keeps the lookup and update failure sentences distinct, capitalisation included',
    keepsTheTwoFailureSentencesDistinct,
  );
  it('paints the read failure sentence from L349', paintsTheReadFailureSentence);
  it(
    'paints the not-found sentence from L342 on a missing row',
    paintsTheNotFoundSentenceOnAMissingRow,
  );
  it('paints the write failure sentence from L386', paintsTheWriteFailureSentence);
  it(
    'raises the X(50) invalid-key sentence for a key it does not bind',
    raisesTheInvalidKeySentence,
  );
  it('never surfaces the owned USRSEC file name in the browser', neverSurfacesTheOwnedFileName);
}

describe('message text, verbatim from the catalog', messageTextCases);

/**
 * Groups the cases that hold the five bound keys to their measured legend and semantics.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function functionKeyCases(): void {
  /**
   * Confirms the legend paints exactly the five measured descriptors, with a single ampersand on PF3.
   * @returns {Promise<void>} Resolves once all five have been asserted.
   */
  async function paintsTheFiveMeasuredDescriptors(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : ⚠️ Assumptions: the rendered PF3 descriptor carries ONE ampersand, not two. The mapset writes
    //       `F3=Save&&Exit` (`app/bms/COUSR02.bms` L163) because BMS source is assembler macro source, in
    //       which `&` opens a variable symbol -- so the doubled ampersand is the ESCAPE for one literal
    //       ampersand and the terminal displays `F3=Save&Exit`. Copying the mapset's spelling straight
    //       through into the SPA would render a doubled ampersand and break the string, which is a defect
    //       no reader would question because the source really does say `&&`.
    expect(USER_UPDATE_KEY_LABELS.PFK03).toBe('F3=Save&Exit');
    expect(USER_UPDATE_KEY_LABELS.PFK03).not.toContain('&&');

    // WHY : Assumptions: the five descriptors reconstruct the field's rendered text exactly. The legend is
    //       painted at `LENGTH=58` across the continuation at `app/bms/COUSR02.bms` L163-L164 as
    //       `ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel`, with two spaces between every
    //       pair including before `F12`. The tail descriptor is `F12=Cancel` -- recovered from the
    //       continuation line rather than guessed, since a legend truncated at the line break would have
    //       left PF12 unlabelled.
    expect(USER_UPDATE_KEY_LABELS.ENTER).toBe('ENTER=Fetch');
    expect(USER_UPDATE_KEY_LABELS.PFK04).toBe('F4=Clear');
    expect(USER_UPDATE_KEY_LABELS.PFK05).toBe('F5=Save');
    expect(USER_UPDATE_KEY_LABELS.PFK12).toBe('F12=Cancel');

    for (const { label } of boundKeys()) {
      expect(keyButton(label)).toBeInTheDocument();
    }

    // WHY : Assumptions: FIVE controls and no more, which is the largest key set of any base screen. A
    //       sixth would mean a key the reference's `EVALUATE EIBAID` (L108-L131) does not answer, so it
    //       would be a key whose own legend promises an action the program treats as invalid.
    expect(within(legend()).getAllByRole('button')).toHaveLength(5);
  }

  /**
   * ⚠️ Confirms emphasis follows what each key WRITES, so both saves read as the screen's actions.
   *
   * ⚠️ Purpose: close a measured defect. A rendering pass recorded this bar carrying TWO primary-blue
   * controls at once -- `ENTER=Fetch` and `F5=Save` -- while `F3=Save&&Exit`, a key that performs
   * `PUT /api/v1/auth/users/{id}` before it leaves, was the visually WEAKEST of the three. So the
   * emphasis distinguished neither the two writing keys nor the reading one.
   *
   * ⚠️ Refactoring Rationale: this case used to assert the opposite and therefore PINNED the defect. It
   * required `PRIMARY_ACTION_AIDS` membership to decide the class, and recorded PF3's weak paint as
   * "deliberate rather than an oversight" with a warning not to "fix the mapping for this screen". That
   * warning was right about the mechanism -- an AID-keyed table cannot be changed for one screen,
   * because `PFK03` is `F3=Back` on nine other mapsets -- and wrong about the conclusion. The screen now
   * declares what each key does and the table is left untouched for every screen that has not.
   *
   * ⚠️ Assumptions: the expected class per key is derived from the mapset's own legend, not from the
   * AID. `app/bms/COUSR02.bms` L163-L164 paints `ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save
   * F12=Cancel`, and only the two `Save` arms reach `UPDATE-USER-INFO` (`app/cbl/COUSR02C.cbl` L112 and
   * L121) -- so exactly two controls are primary and three are not.
   * @returns {Promise<void>} Resolves once every control's emphasis has been asserted.
   */
  async function appliesTheEmphasisMappingByKey(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    /*
     * ⚠️ Assumptions: the AID fallback is asserted UNCHANGED, which is what makes the assertions below
     * evidence that the declared risk overrode it rather than merely agreed with it. It still lists
     * `ENTER`, so a fetch key rendering non-primary can only be the screen's own classification taking
     * effect; and it still omits `PFK03`, so a save-and-exit rendering primary can only be the same.
     */
    expect(PRIMARY_ACTION_AIDS).toEqual(['ENTER', 'PFK05']);

    /** The two keys whose arms reach `UPDATE-USER-INFO`, and therefore carry the primary emphasis. */
    const mutatingLabels: readonly string[] = [
      USER_UPDATE_KEY_LABELS.PFK03,
      USER_UPDATE_KEY_LABELS.PFK05,
    ];

    for (const { label } of boundKeys()) {
      const writes = mutatingLabels.includes(label);
      const expectedClass = writes ? 'ant-btn-primary' : 'ant-btn-default';

      expect(keyButton(label).className, label).toContain(expectedClass);

      /*
       * ⚠️ Assumptions: neither save is asserted as DANGEROUS, and that is the classification and not an
       * omission. `'mutating'` and `'destructive'` resolve to different paints on purpose -- an operator
       * can come straight back and edit this row again, so a save must read as the screen's primary
       * action and not as its irreversible one. Asserting the absence is what keeps a well-meant
       * escalation to `'destructive'` from passing here.
       */
      expect(keyButton(label).className, label).not.toContain('ant-btn-dangerous');
    }
  }

  /**
   * Confirms PF3 writes and only then leaves, which is this screen's documented Save&Exit semantic.
   * @returns {Promise<void>} Resolves once the write and the transfer have both been observed.
   */
  async function pf3SavesAndThenExits(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreenWithDestination();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK03');

    /*
     * WHY : ⚠️ Trade-offs: this is the highest-value assertion in this file, and what it buys is the
     *       prevention of a SILENT DATA-LOSS defect. On sixteen of the seventeen base mapsets PF3 is
     *       "Back" or "Exit" and writes nothing, so the reflex reading of this key is overwhelmingly
     *       well supported -- and wrong here. This mapset paints `F3=Save&&Exit` at
     *       `app/bms/COUSR02.bms` L163 and `app/cbl/COUSR02C.cbl` L111-L119 performs `UPDATE-USER-INFO`
     *       and only then `RETURN-TO-PREV-SCREEN`.
     *       Alternatives Considered: making PF3 a plain "Back" for consistency with its sixteen
     *       siblings. Rejected because consistency is the wrong instinct here: an administrator who
     *       edited three fields and pressed PF3 expecting the documented Save&Exit would lose every
     *       edit, with no message and nothing failing anywhere. The legend on the glass is the contract,
     *       and it promises a write.
     *       Assumptions: the two halves are asserted in ORDER -- the write first, the transfer second --
     *       because the reference's `EXEC CICS REWRITE` is synchronous and completes before its
     *       `EXEC CICS XCTL` at L258-L261. A screen that navigated first and let the request continue
     *       would satisfy a test that only checked both happened, while racing the write against an
     *       unmount.
     */
    await waitFor(
      /**
       * Waits until the write has been issued, before any transfer is asserted.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );
    expect(identityTransport.updateUser).toHaveBeenCalledWith(EDITED_USER_ID, {
      firstName: EDITED_FIRST_NAME,
      lastName: STORED_ROW.lastName,
      userType: STORED_ROW.userType,
    });

    await waitFor(
      /**
       * Waits until the transfer has replaced this screen with the destination stand-in.
       * @returns {void} Nothing; throws until the destination is on the document.
       */
      () => {
        expect(screen.getByText(ADMIN_MENU_PROBE_TEXT)).toBeInTheDocument();
      },
    );
  }

  /**
   * Confirms PF12 leaves without writing, which is the reference's own unconditional cancel arm.
   * @returns {Promise<void>} Resolves once the transfer has been observed and the write ruled out.
   */
  async function pf12CancelsWithoutWriting(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreenWithDestination();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK12');

    // WHY : Assumptions: PF12 and PF3 are DIFFERENT arms and are asserted separately for that reason.
    //       `app/cbl/COUSR02C.cbl` L124-L125 moves `'COADM01C'` into `CDEMO-TO-PROGRAM` and transfers
    //       with no `UPDATE-USER-INFO` at all, where L111-L119 performs the update first. Its legend
    //       descriptor says `F12=Cancel`, so discarding the edits is what the operator was promised --
    //       and a screen that saved on cancel would be the mirror-image defect of one that discarded on
    //       PF3.
    await waitFor(
      /**
       * Waits until the transfer has replaced this screen with the destination stand-in.
       * @returns {void} Nothing; throws until the destination is on the document.
       */
      () => {
        expect(screen.getByText(ADMIN_MENU_PROBE_TEXT)).toBeInTheDocument();
      },
    );
    expect(identityTransport.updateUser).not.toHaveBeenCalled();
  }

  /**
   * Confirms PF5 writes and stays on the screen, unlike PF3 which writes and leaves.
   * @returns {Promise<void>} Resolves once the write and the continued presence are asserted.
   */
  async function pf5SavesWithoutLeaving(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreenWithDestination();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');

    await waitFor(
      /**
       * Waits until the write has been issued.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );

    // WHY : Assumptions: the screen is still mounted, which is what separates PF5 from PF3. The
    //       reference's PF5 arm at `app/cbl/COUSR02C.cbl` L122-L123 performs `UPDATE-USER-INFO` and
    //       nothing else -- no `RETURN-TO-PREV-SCREEN` follows it -- so the map is re-sent and the
    //       operator stays put. Asserting the write alone would not distinguish the two keys.
    expect(control('firstName')).toBeInTheDocument();
    expect(screen.queryByText(ADMIN_MENU_PROBE_TEXT)).not.toBeInTheDocument();
  }

  /**
   * Confirms PF4 empties every control the screen renders, and issues nothing.
   * @returns {Promise<void>} Resolves once all four controls are empty.
   */
  async function pf4ClearsEveryControl(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK04');

    // WHY : Assumptions: ALL FOUR rendered controls are emptied, the identifier included.
    //       `INITIALIZE-ALL-FIELDS` at `app/cbl/COUSR02C.cbl` L403-L411 moves `SPACES` into `USRIDINI`,
    //       `FNAMEI`, `LNAMEI`, `PASSWDI`, `USRTYPEI` and `WS-MESSAGE` in one statement and places the
    //       cursor back on the identifier at L405. Five of those six targets have a counterpart here;
    //       the credential's does not, because the control does not exist -- registered divergence D-10.
    //       Clearing a credential control was security-relevant rather than cosmetic, and the absence of
    //       the control is a stronger guarantee than clearing one, since a value never collected cannot
    //       be left behind.
    await waitFor(
      /**
       * Waits until every rendered control has been emptied.
       * @returns {void} Nothing; throws until all four are empty.
       */
      () => {
        for (const field of RENDERED_FIELDS) {
          expect(control(field)).toHaveValue('');
        }
      },
    );
    expect(identityTransport.updateUser).not.toHaveBeenCalled();

    /*
     * WHY : ⚠️ Assumptions: the CURSOR is asserted as well as the values, and it is the half that makes
     *       clearing the identifier usable rather than obstructive. `INITIALIZE-ALL-FIELDS` moves `-1`
     *       into `USRIDINL` at `app/cbl/COUSR02C.cbl` L405 in the same statement group that blanks the
     *       fields, which is the 3270 way of placing the cursor, so the reference empties the key and
     *       then puts the operator on it ready to type the next one. A rendering review filed the
     *       cleared key as a defect on the grounds that F4 "clears all four fields including the key
     *       field"; the reference does exactly that, deliberately, and this assertion records the
     *       affordance that goes with it so the pair cannot be split by a later change that spares the
     *       key.
     */
    expect(
      control('userId'),
      'L405 places the cursor back on the identifier the clear has just emptied',
    ).toHaveFocus();
  }

  /**
   * Confirms a clicked legend control dispatches exactly what its key press dispatches.
   * @returns {Promise<void>} Resolves once the clicked PF5 has produced the same single write.
   */
  async function aClickedControlDispatchesWhatItsKeyDoes(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK05));

    /*
     * WHY : Alternatives Considered: asserting only the rendered controls, which is the shape a
     *       browser-first test naturally takes. Rejected because the 3270 original had no pointer at all:
     *       the KEYBOARD is the fidelity-bearing path, and `app/cpy/CSSTRPFY.cpy` L21-L78 exists solely
     *       to normalise an attention identifier into a named flag. Asserting only clicks would leave the
     *       original workflow -- the one every existing operator uses -- entirely unverified.
     *       Alternatives Considered: asserting only key presses, since those are the faithful path.
     *       Rejected too, because the legend controls are additive affordances this migration adds and an
     *       additive control that dispatches something DIFFERENT from its key is worse than no control:
     *       the two would diverge silently, and on this screen one of the two paths writes. So both are
     *       driven, and every other case in this group uses the key path with this one pinning the
     *       equivalence.
     */
    await waitFor(
      /**
       * Waits until the clicked control has issued the write.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );
    expect(identityTransport.updateUser).toHaveBeenCalledWith(EDITED_USER_ID, {
      firstName: EDITED_FIRST_NAME,
      lastName: STORED_ROW.lastName,
      userType: STORED_ROW.userType,
    });
  }

  /**
   * Confirms a clicked PF3 control also writes and then leaves, matching its key press.
   * @returns {Promise<void>} Resolves once the write and the transfer have both been observed.
   */
  async function aClickedPf3AlsoSavesAndExits(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreenWithDestination();
    await fetchThenEdit(user);

    await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK03));

    // WHY : Assumptions: the anomalous key is checked on BOTH activation paths rather than only on the
    //       keyboard, because it is the one control in the application whose click commits a write. If
    //       the two paths ever diverged, this is the key on which the divergence would cost an
    //       administrator their edits.
    await waitFor(
      /**
       * Waits until the clicked control has issued the write.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );
    await waitFor(
      /**
       * Waits until the transfer has replaced this screen with the destination stand-in.
       * @returns {void} Nothing; throws until the destination is on the document.
       */
      () => {
        expect(screen.getByText(ADMIN_MENU_PROBE_TEXT)).toBeInTheDocument();
      },
    );
  }

  it(
    'paints the five measured legend descriptors, PF3 with a single ampersand',
    paintsTheFiveMeasuredDescriptors,
  );
  it(
    'applies the primary emphasis to the two keys that write and the default to the three that do not',
    appliesTheEmphasisMappingByKey,
  );
  it('saves and only then exits on PF3, which no other screen does', pf3SavesAndThenExits);
  it('cancels on PF12 without writing anything', pf12CancelsWithoutWriting);
  it('saves on PF5 without leaving the screen', pf5SavesWithoutLeaving);
  it('clears every rendered control on PF4', pf4ClearsEveryControl);
  it(
    'dispatches from a clicked legend control exactly what its key press dispatches',
    aClickedControlDispatchesWhatItsKeyDoes,
  );
  it(
    'saves and exits from a clicked PF3 control as well as from the key',
    aClickedPf3AlsoSavesAndExits,
  );
}

describe('the five keys, and the one screen where PF3 commits', functionKeyCases);

/**
 * Groups the cases that hold the declined credential control to its registered absence.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function credentialAbsenceCases(): void {
  /*
   * WHY : ⚠️ Refactoring Rationale: the reference collects a credential here and this screen does not,
   *       which is registered divergence D-10 and is THE ONE PLACE this migration explicitly declines
   *       parity. `app/bms/COUSR02.bms` paints the control at L125-L139 -- a `Password:` label, an
   *       `ATTRB=(DRK,FSET,UNPROT)` non-display input at `LENGTH=8` (L130) and an `(8 Char)` hint --
   *       and `app/cbl/COUSR02C.cbl` refuses it when blank (L198-L203), pre-fills it from the stored
   *       plaintext value (`MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI`, L169, reading
   *       `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21), compares it (L227-L230) and writes
   *       it. `app/cbl/COSGN00C.cbl` then compares that same stored plaintext directly against what an
   *       operator typed at sign-on. None of it has a target: AAP sections 0.5.1.2 and 0.7.8 move
   *       identity to a managed user pool so that `auth.users` carries no password column at all, and
   *       `UpdateUserRequest` is sealed at three members.
   * WHY : ⚠️ Assumptions: these cases assert ABSENCE, and that is the only shape that can catch the
   *       defect they exist for. An earlier revision of this screen DID render the control, refused it
   *       when blank, and then discarded the eight characters an administrator typed without sending
   *       them -- and every positive expectation of that arrangement passed, because a control appeared,
   *       a refusal appeared and a write succeeded. What it actually did was tell an administrator, by
   *       every affordance a form has, that a password had been changed when nothing had changed
   *       anywhere. Only an assertion about what is NOT rendered distinguishes the two.
   * WHY : ⚠️ Trade-offs: AAP gap G2 -- rendering the 3270 `DRK` attribute as an `Input.Password`, whose
   *       dots differ from a truly blank field -- was taken deliberately on this screen and is now moot
   *       here, because the masking protected a value that went nowhere. G2 still stands unchanged on
   *       `app/bms/COSGN00.bms` and `app/bms/COUSR01.bms`, the two remaining genuine credential
   *       controls, because a credential those screens collect is one they send.
   */

  /**
   * Confirms no credential control, label or hint is rendered anywhere on the screen.
   * @returns {Promise<void>} Resolves once every credential affordance has been ruled out.
   */
  async function rendersNoCredentialAffordance(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: the masked-input check is made on the DOM rather than on the component tree,
    //       because what matters is that no browser control can hold or autofill a credential -- a
    //       password manager fills by input type, not by React element. Querying `input[type="password"]`
    //       is therefore the assertion that actually covers the exposure.
    expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
    expect(screen.queryByLabelText('Password:')).not.toBeInTheDocument();
    expect(screen.queryByText('Password:')).not.toBeInTheDocument();
    expect(screen.queryByText('(8 Char)')).not.toBeInTheDocument();

    // WHY : Assumptions: exactly four inputs, all of them text. A count is what catches a control
    //       reintroduced under a different label, which the three negative queries above would miss.
    const inputs = Array.from(document.querySelectorAll('input'));
    expect(inputs).toHaveLength(4);
    for (const input of inputs) {
      expect(input.type).toBe('text');
    }
  }

  /**
   * Confirms the read's response shape carries no credential and the form stays empty of one.
   * @returns {Promise<void>} Resolves once the fixture and the seeded form have been asserted.
   */
  async function seedsNoCredentialFromTheRead(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : ⚠️ Assumptions: the hazard unique to the UPDATE path is the read. A naive read-then-edit
    //       implementation seeds its form from whatever the response carried, which is exactly what the
    //       reference does at L169 -- so the fixture is asserted to carry NO credential member, closing
    //       the input through which one could arrive. The five members below are the whole of
    //       `UserResponse`: `UserSummary`'s four plus `cognitoSub`.
    expect(Object.keys(STORED_ROW).sort()).toEqual([
      'cognitoSub',
      'firstName',
      'lastName',
      'userId',
      'userType',
    ]);
    expect(STORED_ROW).not.toHaveProperty('password');

    // WHY : Assumptions: nothing reaches web storage either, so no credential can be left behind for a
    //       later script on the page to read. `ui/src/api/client.ts` holds the bearer in a module
    //       variable and publishes only its setter, which is why there is no storage key to inspect and
    //       an empty store is the honest assertion.
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  }

  /**
   * Confirms a save with the credential's own blank refusal absent still reaches the next control's.
   * @returns {Promise<void>} Resolves once the user-type refusal has been observed.
   */
  async function reachesTheUserTypeRefusalWithNoCredentialArmAhead(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();
    await user.clear(control('userType'));

    await pressPfKey(user, 'PFK05');

    // WHY : Assumptions: dropping the credential's arm does NOT reorder the arms that remain.
    //       `app/cbl/COUSR02C.cbl` L179-L213 is one `EVALUATE TRUE` whose arms are independent tests
    //       reached in sequence, so removing the fourth (L198-L203) leaves the first three reachable
    //       exactly as before and moves the user type's arm from fifth position to fourth without
    //       changing which submissions reach it. This case is what proves that: with only the user type
    //       blank, its own sentence is raised rather than the credential's -- which is the outcome an
    //       implementation that kept a hidden credential check would get wrong.
    await waitFor(
      /**
       * Waits until the user-type refusal reaches the band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY);
      },
    );

    // WHY : Assumptions: the credential's sentence is still IN the catalog and must never be painted by
    //       this screen. `app/cbl/COUSR01C.cbl` L138 and `ui/src/screens/signon` raise the same literal
    //       for a credential they genuinely submit, so the catalog rightly keeps one entry recording
    //       both sites -- `app/cbl/COUSR02C.cbl` L200 among them. What would be wrong is this screen
    //       emitting it, which is what is ruled out here.
    expect(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY).toBe('Password can NOT be empty...');
    expect(document.body.textContent).not.toContain(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY);
    expect(identityTransport.updateUser).not.toHaveBeenCalled();
  }

  it('renders no credential control, label, hint or masked input', rendersNoCredentialAffordance);
  it(
    'seeds no credential from the read, and leaves web storage empty',
    seedsNoCredentialFromTheRead,
  );
  it(
    'reaches the user-type refusal with no credential arm ahead of it',
    reachesTheUserTypeRefusalWithNoCredentialArmAhead,
  );
}

describe('the credential control the migration declines to carry', credentialAbsenceCases);

/**
 * Groups the cases that hold the screen to the signed group claim as its only authority.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function administrativeAuthorityCases(): void {
  /*
   * WHY : ⚠️ Refactoring Rationale: in the reference the operator's authority travelled in
   *       `CDEMO-USER-TYPE` (`app/cpy/COCOM01Y.cpy` L26-L28) inside `DFHCOMMAREA` -- storage the CLIENT
   *       echoes back between turns -- so a client could in principle assert its own user type. AAP
   *       section 0.7.1 replaces that with a SIGNED `cognito:groups` claim the client cannot forge. The
   *       negative cases below matter more on this screen than on any read screen, because this screen
   *       can change a user's type from `'U'` to `'A'`: it is a privilege-escalation surface, so the
   *       question of who may reach it is the question of who may mint an administrator.
   * WHY : ⚠️ Assumptions: a session is established ONLY through the identity helper in `./setup`, which
   *       drives the same sign-on exchange a screen drives. `ui/src/hooks/useAuth.ts` publishes the two
   *       group names and the claim name but deliberately publishes NO group setter, and there is no auth
   *       context provider anywhere in this tree -- so a case genuinely cannot grant itself
   *       administrative authority, which is the property being relied on rather than merely observed.
   */

  /**
   * Mounts the screen behind its real route guard, so the guard decides whether it renders.
   * @returns {Promise<Awaited<ReturnType<typeof renderWithProviders>>>} The render result.
   */
  async function mountGuardedScreen(): Promise<Awaited<ReturnType<typeof renderWithProviders>>> {
    return await renderWithProviders(
      <Routes>
        <Route element={<AppShell />}>
          <Route
            path={USER_UPDATE_ROUTE}
            element={
              <RequireAdmin>
                <UserUpdateScreen />
              </RequireAdmin>
            }
          />
        </Route>
      </Routes>,
      { initialEntries: [`/users/${EDITED_USER_ID}/edit`] },
    );
  }

  /**
   * Confirms an administrator reaches the screen and can commit a change through it.
   * @returns {Promise<void>} Resolves once the write has been observed.
   */
  async function anAdministratorRendersAndSaves(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
    const { user } = await mountGuardedScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');

    // WHY : Assumptions: the positive case is asserted alongside the negative one because a guard that
    //       refused EVERYONE would satisfy the refusal case perfectly. `app/cpy/COADM02Y.cpy` L38 names
    //       this screen's administrative option `'User Update (Security)             '` at `PIC X(35)`,
    //       and the option table's `OCCURS 9 TIMES` entry (L52-L55) carries NO user-type field -- because
    //       in the reference, reaching the administrative menu at all IS the authorisation.
    await waitFor(
      /**
       * Waits until the administrator's write has been issued.
       * @returns {void} Nothing; throws until the write has been recorded.
       */
      () => {
        expect(identityTransport.updateUser).toHaveBeenCalledTimes(1);
      },
    );
  }

  /**
   * Confirms a signed-on non-administrator is refused and no identity request is ever issued.
   * @returns {Promise<void>} Resolves once the refusal and the silence have been asserted.
   */
  async function aNonAdministratorIsRefusedAndNothingIsRequested(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await seedSession({ groups: [CARDDEMO_USER_GROUP] });
    await mountGuardedScreen();

    // WHY : Assumptions: the refusal surface is asserted through the catalog rather than a retyped
    //       string, and the subtitle's TRAILING SPACE is part of the value --
    //       `'No access - Admin Only option... '` is what `app/cbl/COMEN01C.cbl` L140 moves. It is
    //       matched with `toContain` against the document text rather than through a text query, because
    //       every Testing Library normaliser trims before comparing and would therefore match a variant
    //       with the space already lost.
    expect(ACCESS_DENIED_ADMIN_ONLY).toBe('No access - Admin Only option... ');
    expect(await screen.findByText(ACCESS_DENIED_HEADING)).toBeInTheDocument();
    expect(document.body.textContent).toContain(ACCESS_DENIED_ADMIN_ONLY);

    // WHY : ⚠️ Assumptions: the transport is never reached at all, which is stronger than the screen not
    //       rendering. A guard that mounted the screen and then hid it would still have let its mount
    //       effect issue the read for a row this operator may not see, so the absence of the request is
    //       the property that actually protects the record.
    expect(identityTransport.getUser).not.toHaveBeenCalled();
    expect(identityTransport.updateUser).not.toHaveBeenCalled();
    expect(screen.queryByText(USER_UPDATE_CAPTION)).not.toBeInTheDocument();
  }

  /**
   * Confirms a client-side value cannot promote a non-administrator into an administrator.
   * @returns {Promise<void>} Resolves once the refusal has survived the attempt.
   */
  async function noClientSideValueGrantsAdministrativeAuthority(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);

    /*
     * WHY : ⚠️ Assumptions: the escalation attempt is planted BEFORE the session and the mount, which is
     *       the order a hostile client would achieve it in -- write the group claim into
     *       browser-reachable storage under the name the application itself uses, then let the
     *       application start. Planting it afterwards and forcing a re-render was tried and is the wrong
     *       shape here: the harness wraps the subject in its own router and theme provider, so a
     *       `rerender` of the bare route tree drops that wrapper and the case fails on
     *       `useRoutes() may be used only in the context of a <Router>` -- a harness artefact that says
     *       nothing about the guard.
     * WHY : ⚠️ Assumptions: both web stores are planted, because the claim name is the only thing an
     *       attacker has to guess and neither store is more privileged than the other. It has no effect
     *       in either: `ui/src/hooks/useAuth.ts` derives authority from the groups inside the SIGNED
     *       identity token and from nothing else, and storage is not an input to that decision.
     */
    window.localStorage.setItem(COGNITO_GROUPS_CLAIM, CARDDEMO_ADMIN_GROUP);
    window.sessionStorage.setItem(COGNITO_GROUPS_CLAIM, CARDDEMO_ADMIN_GROUP);

    try {
      const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });

      // WHY : Assumptions: the hook's own reading is asserted as well as the rendered refusal, because
      //       the reading is where the decision is MADE. A guard that happened to refuse for some other
      //       reason would satisfy the rendered half while the authority itself had been elevated.
      expect(session.result.current.isAdmin).toBe(false);

      await mountGuardedScreen();

      expect(await screen.findByText(ACCESS_DENIED_HEADING)).toBeInTheDocument();
      expect(screen.queryByText(USER_UPDATE_CAPTION)).not.toBeInTheDocument();
      expect(identityTransport.getUser).not.toHaveBeenCalled();
    } finally {
      /*
       * WHY : Assumptions: the planted keys are removed in a `finally` so a failing expectation above
       *       cannot leak them into the next case. The shared teardown in `ui/src/test/setup.ts`
       *       discards the SESSION a case seeded, which is not the same thing as clearing a storage key
       *       this case wrote by hand.
       */
      window.localStorage.removeItem(COGNITO_GROUPS_CLAIM);
      window.sessionStorage.removeItem(COGNITO_GROUPS_CLAIM);
    }
  }

  it('renders for an administrator and commits a change', anAdministratorRendersAndSaves);
  it(
    'refuses a signed-on non-administrator and issues no identity request',
    aNonAdministratorIsRefusedAndNothingIsRequested,
  );
  it('cannot be promoted by any client-side value', noClientSideValueGrantsAdministrativeAuthority);
}

describe('administrative authority', administrativeAuthorityCases);

/**
 * Groups the cases that hold the route parameter and the exit destination to the reference.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function routeAndDestinationCases(): void {
  /**
   * Confirms the route's `id` segment is the identifier that reaches the transport.
   * @returns {Promise<void>} Resolves once the read has been observed for the routed value.
   */
  async function theRouteParameterReachesTheTransport(): Promise<void> {
    identityTransport.getUser.mockResolvedValue({ ...STORED_ROW, userId: 'ROUTED01' });
    await mountScreen('/users/ROUTED01/edit');

    // WHY : Assumptions: the parameter is named `id`, which is the name `ui/src/router.tsx` declares for
    //       `/users/:id/edit`. It is per-route rather than global -- the two card routes both spell
    //       theirs `cardKey` -- so the two spellings are not interchangeable and a mismatch resolves to
    //       `undefined` SILENTLY, leaving the screen on its manual-entry arm with nothing failing.
    await waitFor(
      /**
       * Waits until the routed identifier has been read.
       * @returns {void} Nothing; throws until the read has been recorded.
       */
      () => {
        expect(identityTransport.getUser).toHaveBeenCalledWith('ROUTED01');
      },
    );

    // WHY : ⚠️ Assumptions: the route parameter and the on-screen `USRIDINI` field are ONE value, not
    //       two. This mapset has no separate identifier field for the record -- the same
    //       `USRIDINI PIC X(8)` (`app/cpy-bms/COUSR02.CPY` L60) serves as both the search key and the
    //       key the save is addressed to -- so the two have to be reconciled somewhere. This screen
    //       pre-populates the control FROM the parameter, which is the arm the reference reaches at
    //       `app/cbl/COUSR02C.cbl` L99-L104 when its selection carrier arrives non-blank. AAP section
    //       0.7.1 is what makes the parameter the right carrier: selection context becomes a REQUEST
    //       PARAMETER and never a session field.
    await waitFor(
      /**
       * Waits until the routed identifier has been applied to the control.
       * @returns {void} Nothing; throws until the value is present.
       */
      () => {
        expect(control('userId')).toHaveValue('ROUTED01');
      },
    );
    expect(window.sessionStorage.length).toBe(0);
  }

  /**
   * Confirms a typed identifier supersedes the routed one on the next fetch.
   * @returns {Promise<void>} Resolves once the typed identifier has been read.
   */
  async function aTypedIdentifierSupersedesTheRoutedOne(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen('/users/ROUTED01/edit');
    await waitFor(
      /**
       * Waits for the routed read so the typed one is unambiguously the second.
       * @returns {void} Nothing; throws until the routed read has been recorded.
       */
      () => {
        expect(identityTransport.getUser).toHaveBeenCalledWith('ROUTED01');
      },
    );

    await user.clear(control('userId'));
    await user.type(control('userId'), 'TYPED001');
    await pressPfKey(user, 'ENTER');

    // WHY : Assumptions: the control remains a live search key after the routed arrival rather than
    //       becoming read-only, which is the behaviour the reference's Enter arm has unconditionally --
    //       `PROCESS-ENTER-KEY` reads whatever `USRIDINI` holds (L162) with no test for how it got there.
    //       An implementation that froze the field once the route supplied a value would look correct on
    //       every routed case and would remove the screen's own lookup.
    await waitFor(
      /**
       * Waits until the typed identifier has been read.
       * @returns {void} Nothing; throws until the read has been recorded.
       */
      () => {
        expect(identityTransport.getUser).toHaveBeenCalledWith('TYPED001');
      },
    );
  }

  /**
   * Confirms the exit destination is the administrative menu and not the general one.
   * @returns {Promise<void>} Resolves once the destination has been reached.
   */
  async function exitsToTheAdministrativeMenu(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreenWithDestination();
    await waitForTheRowToLand();

    await pressPfKey(user, 'PFK12');

    // WHY : Assumptions: the destination is `/admin` and NOT `/menu`, because this screen is reached from
    //       the administrative menu. `app/cbl/COUSR02C.cbl` names `'COADM01C'` explicitly at L114 as the
    //       PF3 fallback and again at L125 as the PF12 destination, and `COADM01C` is the administrative
    //       menu program -- `COMEN01C` is the general one and is named nowhere in this program. AAP
    //       Transformation Rule T5 makes `EXEC CICS XCTL` a client-side route change, so the transfer at
    //       L258-L261 becomes this navigation.
    expect(ADMIN_MENU_ROUTE).toBe('/admin');
    await waitFor(
      /**
       * Waits until the administrative-menu stand-in has replaced this screen.
       * @returns {void} Nothing; throws until the destination is on the document.
       */
      () => {
        expect(screen.getByText(ADMIN_MENU_PROBE_TEXT)).toBeInTheDocument();
      },
    );
  }

  it(
    'carries the id route parameter through to the transport and into the identifier control',
    theRouteParameterReachesTheTransport,
  );
  it(
    'lets a typed identifier supersede the routed one on the next fetch',
    aTypedIdentifierSupersedesTheRoutedOne,
  );
  it('exits to the administrative menu rather than the general one', exitsToTheAdministrativeMenu);
}

describe('the route parameter and where the screen goes', routeAndDestinationCases);

/**
 * Groups the cases that hold field refusals and non-disclosure to the copybook contract.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function fieldRefusalCases(): void {
  /**
   * Confirms a refusal naming one control marks that control and leaves the others unmarked.
   * @returns {Promise<void>} Resolves once the marked and unmarked controls have been asserted.
   */
  async function marksOnlyTheControlARefusalNames(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockRejectedValue(
      new ApiRequestError(
        'PROBLEM',
        400,
        apiError({
          status: 400,
          fieldErrors: [fieldError('lastName', 'Last Name can NOT be empty...', 'NOT_OK')],
        }),
        'the service refused one field',
      ),
    );
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');

    // WHY : Refactoring Rationale: `app/cpy/CSSETATY.cpy` L17-L27 moves `DFHRED` into a field's colour
    //       attribute when its validation flag is not-OK or blank, and the target renders that as
    //       `Form.Item validateStatus="error"` with help text. The copybook gates the highlight on
    //       `CDEMO-PGM-REENTER` (`app/cpy/COCOM01Y.cpy` L29-L31), but AAP section 0.7.1 removes that
    //       discriminator ENTIRELY -- so the highlight here is driven by the response body alone and
    //       there is no turn count to remember.
    const lastName = control('lastName');
    await waitFor(
      /**
       * Waits until the refused control has been marked invalid and given its help text.
       * @returns {void} Nothing; throws until the marking is present.
       */
      () => {
        expect(lastName).toHaveAttribute('aria-invalid', 'true');
      },
    );
    expect(document.getElementById(fieldErrorId(lastName.id))).toHaveTextContent(
      'Last Name can NOT be empty...',
    );

    // WHY : ⚠️ Assumptions: a control the refusal does NOT name carries no error state, which is the half
    //       of the contract an implementation that marked the whole form would fail. The copybook's
    //       template is instantiated per field -- `(TESTVAR1)`, `(SCRNVAR2)` and `(MAPNAME3)` are
    //       per-field substitution placeholders -- so the highlight was always one field's property and
    //       never the screen's.
    expect(control('firstName')).not.toHaveAttribute('aria-invalid', 'true');
    expect(control('userId')).not.toHaveAttribute('aria-invalid', 'true');
    expect(control('userType')).not.toHaveAttribute('aria-invalid', 'true');
  }

  /**
   * Confirms a blank field additionally carries the copybook's literal asterisk marker.
   * @returns {Promise<void>} Resolves once the marker has been observed beside the blank control.
   */
  async function marksABlankFieldWithTheLiteralAsterisk(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    const { user } = await mountScreen();
    await waitForTheRowToLand();
    await user.clear(control('userId'));

    /*
     * WHY : Assumptions: the read count is captured BEFORE the Enter turn rather than compared against
     *       zero, because the routed arrival has already issued one read of its own. Comparing against
     *       zero would fail on that first read and say nothing about the turn under test.
     */
    const readsBeforeTheBlankTurn = identityTransport.getUser.mock.calls.length;

    await pressPfKey(user, 'ENTER');

    // WHY : Assumptions: the asterisk is the BLANK arm's own extra marking and not part of the general
    //       highlight. `app/cpy/CSSETATY.cpy` colours the field red for either condition (L21-L22) and
    //       then, only when the flag is `BLANK`, additionally moves the literal `'*'` into the field
    //       itself (L23-L25). The two arms are distinct in the copybook, so the marker is asserted for
    //       the blank case specifically rather than for any refusal.
    expect(FIELD_ERROR_TOKENS.blankMarker).toBe('*');
    const identifier = control('userId');
    await waitFor(
      /**
       * Waits until the blank identifier has been marked and the asterisk rendered beside it.
       * @returns {void} Nothing; throws until both are present.
       */
      () => {
        expect(identifier).toHaveAttribute('aria-invalid', 'true');
      },
    );
    expect(messageBand()).toHaveTextContent(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY);
    expect(screen.getByText(FIELD_ERROR_TOKENS.blankMarker)).toBeInTheDocument();

    // WHY : Assumptions: the blank identifier is refused WITHOUT issuing a request, matching
    //       `app/cbl/COUSR02C.cbl` L146-L151, which raises the sentence, places the cursor and never
    //       reaches `READ-USER-SEC-FILE` at all. The count is compared to the one captured above, so what
    //       is asserted is that this turn added no read rather than that none has ever happened.
    expect(identityTransport.getUser.mock.calls).toHaveLength(readsBeforeTheBlankTurn);
    expect(identityTransport.updateUser).not.toHaveBeenCalled();
  }

  /**
   * Confirms no cardholder identifier of any kind is disclosed by this screen.
   * @returns {Promise<void>} Resolves once the document has been checked.
   */
  async function disclosesNoCardholderIdentifiers(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    await mountScreen();
    await waitForTheRowToLand();

    // WHY : Assumptions: this screen's record is `app/cpy/CSUSR01Y.cpy`'s `SEC-USER-DATA` -- an
    //       identifier, two names, a credential and a type -- so it holds no cardholder data at all and
    //       the correct assertion is that none appears. The labels checked for are the ones AAP section
    //       0.7.8 requires to be masked, encrypted or withheld wherever they DO appear -- the primary
    //       account number, the national identifier and the government-issued identifier -- plus the card
    //       verification value, which that section withholds from every endpoint in the migrated system
    //       unconditionally, so no screen may render it under any label.
    const rendered = document.body.textContent ?? '';
    for (const forbidden of [
      'Card Number',
      'SSN',
      'Social Security',
      'Government',
      'Verification',
    ]) {
      expect(rendered).not.toContain(forbidden);
    }

    // WHY : Assumptions: the digit check is a second, independent guard on the same property. The three
    //       withheld values are all long digit runs -- a sixteen-digit account number, a nine-digit
    //       national identifier -- so a run of nine or more digits anywhere on this screen would mean one
    //       of them had arrived under a label this list does not name.
    expect(rendered).not.toMatch(/\d{9,}/u);
  }

  /**
   * Confirms a refusal naming TWO controls marks both, describes each separately and focuses the first.
   *
   * ⚠️ Purpose: this screen and `/users/new` are the delivery's reference implementation of the
   * conforming-400 treatment, and other surfaces are being brought up to match them, so the
   * multi-offender case is pinned rather than inferred from the single-offender one above. It cannot be
   * inferred: a screen that applied only the array's first entry, or that pointed both marked controls
   * at one shared help element, satisfies every assertion in that case and still drops one refusal.
   *
   * ⚠️ Assumptions: the cursor goes to the array's FIRST entry, which is this screen's published rule --
   * `ui/src/screens/userUpdate/index.tsx` takes `fieldErrors[0]` as the control to focus. The fixture
   * therefore names the controls in an order that is NOT their painted order, so a screen that focused
   * by field position rather than array position fails here where an in-order fixture would pass.
   * @returns {Promise<void>} Resolves once both marks, both descriptions and the cursor are observed.
   */
  async function marksBothControlsATwoFieldRefusalNames(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockRejectedValue(
      new ApiRequestError(
        'PROBLEM',
        400,
        apiError({
          status: 400,
          fieldErrors: [
            fieldError('userType', 'User Type can NOT be empty...', 'NOT_OK'),
            fieldError('firstName', 'First Name can NOT be empty...', 'NOT_OK'),
          ],
        }),
        'the service refused two fields',
      ),
    );
    const { user } = await mountScreen();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');

    const userType = control('userType');
    const firstName = control('firstName');

    await waitFor(
      /**
       * Waits until both refused controls have been marked.
       * @returns {void} Nothing; throws until both marks are present.
       */
      () => {
        expect(userType).toHaveAttribute('aria-invalid', 'true');
        expect(firstName).toHaveAttribute('aria-invalid', 'true');
      },
    );

    expect(userType, 'the cursor goes to the refusal the array names first').toHaveFocus();

    // WHY : ⚠️ Assumptions: each control is asserted to carry its OWN sentence through its OWN
    //       `aria-describedby`, and the two identifiers are asserted DISTINCT -- which is the assertion
    //       that catches the plausible failure. A screen binding both controls to one help element would
    //       announce the first-name refusal on the user-type control and satisfy everything else here.
    //       `app/cpy/CSSETATY.cpy` L17-L26 is templated per field for exactly this reason: one
    //       substitution per control, never one statement shared between two.
    const userTypeHelpId = fieldErrorId(userType.id);
    const firstNameHelpId = fieldErrorId(firstName.id);

    expect(userTypeHelpId).not.toBe(firstNameHelpId);
    expect(userType.getAttribute('aria-describedby') ?? '').toContain(userTypeHelpId);
    expect(firstName.getAttribute('aria-describedby') ?? '').toContain(firstNameHelpId);
    expect(document.getElementById(userTypeHelpId)).toHaveTextContent(
      'User Type can NOT be empty...',
    );
    expect(document.getElementById(firstNameHelpId)).toHaveTextContent(
      'First Name can NOT be empty...',
    );

    // WHY : Assumptions: the two controls the array does not name stay clean, so marking is still
    //       per-field once more than one field is named. A screen that marked the whole form as soon as a
    //       second refusal arrived would satisfy the positive half of this case entirely.
    expect(control('lastName')).not.toHaveAttribute('aria-invalid', 'true');
    expect(control('userId')).not.toHaveAttribute('aria-invalid', 'true');
  }

  it(
    'marks only the control a refusal names, and leaves the others unmarked',
    marksOnlyTheControlARefusalNames,
  );
  it('marks both controls a two-field refusal names', marksBothControlsATwoFieldRefusalNames);
  it('adds the copybook literal asterisk to a blank field', marksABlankFieldWithTheLiteralAsterisk);
  it('discloses no cardholder identifier of any kind', disclosesNoCardholderIdentifiers);
}

describe('field refusals and what the screen never discloses', fieldRefusalCases);

/**
 * A second stored row, so a genuine route change is distinguishable from a replayed effect.
 *
 * Assumptions: a DIFFERENT identifier and different names, because the property under test is which row
 * reached the controls. Two rows sharing a first name would let a case pass while the screen displayed
 * the wrong one.
 */
const OTHER_ROW: UserResponse = {
  userId: 'USER0002',
  firstName: 'JEAN',
  lastName: 'BARTIK',
  userType: 'A',
  cognitoSub: '00000000-0000-4000-8000-000000000002',
};

/**
 * Answers a keyed read from the two rows these cases administer.
 *
 * Assumptions: an implementation keyed on the identifier rather than a single resolved value, because a
 * case that navigates from one row to another has to be able to tell which read landed. A single
 * `mockResolvedValue` answers both reads with one row, which would make a wrong-row regression invisible.
 * Assumptions: it is a PLAIN function returning a resolved promise rather than an `async` one, because
 * it awaits nothing and `@typescript-eslint/require-await` rejects the `async` form. The returned type
 * is unchanged, which is what the transport stub it stands in for declares.
 * @param {string} userId - Identifier the screen asked for.
 * @returns {Promise<UserResponse>} The row that identifier names.
 * @throws {Error} If a case asks for an identifier neither row carries, which is a fault in the case
 *   rather than in the screen and is surfaced rather than answered with a substitute row.
 */
function rowFor(userId: string): Promise<UserResponse> {
  if (userId === STORED_ROW.userId) {
    return Promise.resolve(STORED_ROW);
  }
  if (userId === OTHER_ROW.userId) {
    return Promise.resolve(OTHER_ROW);
  }
  throw new Error(`no fixture row for ${userId}`);
}

/** Label of the probe control that moves the route from one administered row to the other. */
const ROUTE_JUMP_LABEL = 'open the other row';

/**
 * Renders a control that moves the router to the second administered row.
 *
 * Assumptions: the jump is driven by a rendered control rather than by re-rendering with a different
 * initial entry, because a fresh render is a fresh MOUNT -- which is the one case that would pass
 * whether the screen guarded a replay or not. A navigation keeps the component mounted and changes only
 * the route parameter, which is what the effect under test reacts to.
 * @returns {ReactElement} The probe control.
 */
function RouteJumpProbe(): ReactElement {
  const navigate = useNavigate();

  return (
    <button
      type="button"
      onClick={
        /**
         * Moves the route to the second row.
         *
         * Assumptions: the transition goes through `navigateSafely` rather than through `navigate`
         * directly, because `NavigateFunction` answers `void | Promise<void>` in the pinned router and
         * the promise arm is a floating one at a bare call site. That helper is the tree's own seam for
         * exactly this -- it settles the promise and falls back to a document navigation on rejection --
         * so the probes navigate the way every screen does.
         * @returns {void} Completion is the requested route transition.
         */
        (): void => {
          navigateSafely(navigate, `/users/${OTHER_ROW.userId}/edit`);
        }
      }
    >
      {ROUTE_JUMP_LABEL}
    </button>
  );
}

/**
 * Groups the cases holding the routed read to exactly one request per identifier asked for.
 *
 * ⚠️ Purpose: these pin the defect a browser sweep of the production bundle measured -- two
 * `GET /api/v1/auth/users/{id}` requests, with two distinct correlation identifiers, for ONE arrival at
 * `/users/:id/edit`. The screen is mounted through `lazy()` under a `Suspense` boundary in
 * `ui/src/router.tsx`, and React runs a mounted tree's effect cleanups and then its effects again when
 * that tree suspends and resumes, so the read effect ran twice for one unchanged route.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function routedReadCases(): void {
  /**
   * Confirms a replayed effect reads the row once and still lands it on the controls.
   * @returns {Promise<void>} Resolves once the row is on the controls and the read has been counted.
   */
  async function readsOneRowPerRequestedIdentifier(): Promise<void> {
    identityTransport.getUser.mockImplementation(rowFor);

    /*
     * WHY : Assumptions: the strict-mode wrapper is INSIDE the route element rather than around the
     *       whole harness, because what has to be double-invoked is this screen's effects. Wrapping the
     *       harness would also double-invoke the shell's, which is another module's contract to keep.
     */
    await renderInAppShell(
      <StrictMode>
        <UserUpdateScreen />
      </StrictMode>,
      { routePath: USER_UPDATE_ROUTE, initialEntries: [`/users/${EDITED_USER_ID}/edit`] },
    );
    await waitForTheRowToLand();

    // WHY : Assumptions: BOTH halves are asserted in one case, and neither is sufficient alone. A
    //       screen that read once and displayed nothing would satisfy the count while being broken
    //       worse than the duplicate was, because the row it was mounted to edit would never appear --
    //       which is precisely what suppressing the second read would have caused had the unmount
    //       invalidation stayed. Waiting for the row above is the first half; the count is the second.
    expect(
      identityTransport.getUser.mock.calls,
      'one arrival at the route reads the row it names exactly once',
    ).toEqual([[EDITED_USER_ID]]);
  }

  /**
   * Confirms a route change to another row still issues that row's read.
   * @returns {Promise<void>} Resolves once the second row is on the controls.
   */
  async function readsAgainWhenTheRouteNamesAnotherRow(): Promise<void> {
    identityTransport.getUser.mockImplementation(rowFor);
    const { user } = await renderWithProviders(
      <>
        <RouteJumpProbe />
        <Routes>
          <Route element={<AppShell />}>
            <Route path={USER_UPDATE_ROUTE} element={<UserUpdateScreen />} />
          </Route>
        </Routes>
      </>,
      { initialEntries: [`/users/${EDITED_USER_ID}/edit`] },
    );
    await waitForTheRowToLand();

    await user.click(screen.getByRole('button', { name: ROUTE_JUMP_LABEL }));

    await waitFor(
      /**
       * Re-reads the first-name control until the second row has been applied to it.
       * @returns {void} Nothing; throws until the second row's first name is present.
       */
      () => {
        expect(control('firstName')).toHaveValue(OTHER_ROW.firstName);
      },
    );
    // WHY : Assumptions: the two calls are compared as a SEQUENCE rather than counted, because the
    //       order is the property: a guard that remembered only "some identifier has been read" would
    //       leave the second row unread and the first row's values under the second row's identifier,
    //       which is the state that makes the next save write one operator's values onto another's.
    expect(identityTransport.getUser.mock.calls).toEqual([[EDITED_USER_ID], [OTHER_ROW.userId]]);
  }

  /**
   * Confirms the Enter key still re-reads the row the route already delivered.
   * @returns {Promise<void>} Resolves once the second read has been observed.
   */
  async function readsAgainWhenTheOperatorAsksForTheSameRow(): Promise<void> {
    identityTransport.getUser.mockImplementation(rowFor);
    const { user } = await mountScreen();
    await waitForTheRowToLand();

    await pressPfKey(user, 'ENTER');

    // WHY : Assumptions: the guard suppresses a read caused by the EFFECT running again, and nothing
    //       else. `app/cbl/COUSR02C.cbl` L108-L110 dispatches Enter to `PROCESS-ENTER-KEY` on every
    //       turn, so an operator asking for the row again is answered by reading it again -- and the
    //       save arm re-reads for the same reason at L215-L217. A guard that covered those would refuse
    //       a read the reference performs.
    await waitFor(
      /**
       * Waits until the Enter turn has issued its own read.
       * @returns {void} Nothing; throws until the second read has been recorded.
       */
      () => {
        expect(identityTransport.getUser.mock.calls).toEqual([[EDITED_USER_ID], [EDITED_USER_ID]]);
      },
    );
  }

  it('reads the routed row exactly once per arrival', readsOneRowPerRequestedIdentifier);
  it('reads the other row when the route names it', readsAgainWhenTheRouteNamesAnotherRow);
  it('re-reads the same row when Enter asks for it', readsAgainWhenTheOperatorAsksForTheSameRow);
}

describe('the routed read issues one request per identifier asked for', routedReadCases);

/**
 * Name the save handover is retained under, composed the way both screens compose it.
 *
 * Assumptions: the route half comes from `ui/src/routes/navigation.ts` and the suffix is written out,
 * which is exactly how `ui/src/screens/userUpdate/index.tsx` and `ui/src/screens/userList/index.tsx`
 * each compose it. That is deliberate: the two screens agree on this name without importing each other,
 * so the agreement is what a case has to be able to fail on.
 */
const USER_UPDATE_SAVE_CLAIM = `${USER_UPDATE_ROUTE_TEMPLATE}#saved`;

/** The two members a collected handover carries, as this file asserts against them. */
interface CollectedHandover {
  /** The sentence the save published on its own band before it transferred. */
  readonly message: string;
  /** The tone it published that sentence with. */
  readonly severity: string;
}

/**
 * Collects whatever the exit key left behind, failing the case when it left nothing.
 *
 * Assumptions: the absence of an outcome is reported as a FAILED ASSERTION here rather than returned as
 * `undefined` for a caller to check, because "nothing was handed over" is the defect every case in this
 * group exists to catch and the message should say so where it happened.
 * @returns {CollectedHandover} The sentence and tone the update screen retained.
 * @throws {Error} When no outcome is held under the claim, or one is held that did not complete.
 */
function collectHandover(): CollectedHandover {
  const held = claimRetainedOutcome<CollectedHandover>(USER_UPDATE_SAVE_CLAIM);

  expect(
    held,
    'the exit key must leave its own sentence for the screen it transfers to',
  ).toBeDefined();
  if (held === undefined || held.settled !== 'COMPLETED') {
    throw new Error('no completed save handover was retained');
  }

  return held.value;
}

/** Marker naming the browse stand-in, so an arrival back at the list is observable. */
const BROWSE_PROBE_TEXT = 'user browse reached';

/** Label of the control that opens a row the way the browse opens one. */
const OPEN_FROM_BROWSE_LABEL = 'open the row from the browse';

/**
 * A browse stand-in that opens a row and hands its own route over as the caller origin.
 *
 * ⚠️ Purpose: the origin is the property that decides where `F3=Save&&Exit` lands, and it travels in
 * router STATE rather than in the path -- which is exactly the channel `navigateSafely` writes it to and
 * `ui/src/screens/userList/index.tsx` fills on a row action. The provider helper this file mounts
 * through accepts history entries as plain strings, so state cannot be attached to an initial entry; a
 * rendered control that navigates is the only way to arrange the arrival the browse actually produces.
 *
 * Assumptions: a stand-in and not the real browse, because what is under test is what the update screen
 * does with an origin it was given. The real list would issue its own read and a failure there would
 * present as a failure here.
 * @returns {ReactElement} The marker and the control that opens the row.
 */
function UserBrowseProbe(): ReactElement {
  const navigate = useNavigate();

  return (
    <>
      <p>{BROWSE_PROBE_TEXT}</p>
      <button
        type="button"
        onClick={
          /**
           * Opens the administered row carrying this route as the caller origin.
           *
           * Assumptions: through `navigateSafely` for the reason {@link RouteJumpProbe} records, and it
           * is the same helper `ui/src/screens/userList/index.tsx` uses on the row action this stands in
           * for -- so the origin reaches router state by the same route it does in the application.
           * @returns {void} Completion is the requested route transition.
           */
          (): void => {
            navigateSafely(navigate, `/users/${EDITED_USER_ID}/edit`, { from: USER_LIST_ROUTE });
          }
        }
      >
        {OPEN_FROM_BROWSE_LABEL}
      </button>
    </>
  );
}

/**
 * Mounts the screen as the browse opens it, so its back arm returns to the browse rather than the menu.
 *
 * Assumptions: the row is opened by TAKING the browse's action rather than by starting the history at
 * the edit path, because the origin is what the case is about. Starting at the edit path models a direct
 * arrival, which is a different destination and a different outcome -- and both are asserted, by this
 * helper and by {@link mountScreenWithDestination} respectively.
 * @returns {Promise<Awaited<ReturnType<typeof renderWithProviders>>>} The render result and its operator.
 */
async function mountScreenFromTheBrowse(): Promise<
  Awaited<ReturnType<typeof renderWithProviders>>
> {
  const mounted = await renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path={USER_LIST_ROUTE} element={<UserBrowseProbe />} />
        <Route path={USER_UPDATE_ROUTE} element={<UserUpdateScreen />} />
        <Route path={ADMIN_MENU_ROUTE} element={<AdminMenuProbe />} />
      </Route>
    </Routes>,
    { initialEntries: [USER_LIST_ROUTE] },
  );

  await mounted.user.click(screen.getByRole('button', { name: OPEN_FROM_BROWSE_LABEL }));

  return mounted;
}

/** Marker naming the main-menu stand-in, so an arrival at a NON-collecting destination is observable. */
const MAIN_MENU_PROBE_TEXT = 'main menu reached';

/** Label of the control that opens a row while naming the main menu as the caller origin. */
const OPEN_FROM_MAIN_MENU_LABEL = 'open the row from the main menu';

/**
 * A stand-in for a caller that is an in-application route but collects no save outcome.
 *
 * ⚠️ Purpose: keep the retention guard's CONDITIONALITY provable now that both of this key's ordinary
 * destinations collect. `originDestination` in `ui/src/screens/userUpdate/index.tsx` answers
 * `inApplicationRoute(...) ?? ADMIN_MENU_ROUTE`, and `navigableRoutes()` in
 * `ui/src/routes/navigation.ts` admits every route this application has -- so an origin naming the main
 * menu is admitted, reached, and collects nothing. Without a case on that arm, a change that retained
 * unconditionally would pass every remaining case in this group while leaving a durable claim for a
 * screen that never takes it.
 *
 * Assumptions: the main menu is used rather than an invented route BECAUSE it is admitted. An origin
 * outside the closed set is rejected by `inApplicationRoute` and falls back to the administrative menu,
 * which now collects -- so a forged unknown origin could not exercise this arm at all.
 * @returns {ReactElement} The marker and the control that opens the row.
 */
function MainMenuOriginProbe(): ReactElement {
  const navigate = useNavigate();

  return (
    <>
      <p>{MAIN_MENU_PROBE_TEXT}</p>
      <button
        type="button"
        onClick={
          /**
           * Opens the administered row naming the main menu as the caller origin.
           *
           * Assumptions: through `navigateSafely` for the reason {@link RouteJumpProbe} records.
           * @returns {void} Completion is the requested route transition.
           */
          (): void => {
            navigateSafely(navigate, `/users/${EDITED_USER_ID}/edit`, { from: MAIN_MENU_ROUTE });
          }
        }
      >
        {OPEN_FROM_MAIN_MENU_LABEL}
      </button>
    </>
  );
}

/**
 * Mounts the screen as a caller that collects nothing would open it.
 *
 * Assumptions: the row is opened by TAKING the control rather than by starting the history at the edit
 * path, for the reason {@link UserBrowseProbe} records -- the origin travels in router state and the
 * provider helper accepts history entries as plain strings, so a rendered navigation is the only way to
 * arrange it.
 * @returns {Promise<Awaited<ReturnType<typeof renderWithProviders>>>} The render result and its operator.
 */
async function mountScreenFromANonCollectingCaller(): Promise<
  Awaited<ReturnType<typeof renderWithProviders>>
> {
  const mounted = await renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path={MAIN_MENU_ROUTE} element={<MainMenuOriginProbe />} />
        <Route path={USER_UPDATE_ROUTE} element={<UserUpdateScreen />} />
        <Route path={ADMIN_MENU_ROUTE} element={<AdminMenuProbe />} />
      </Route>
    </Routes>,
    { initialEntries: [MAIN_MENU_ROUTE] },
  );

  await mounted.user.click(screen.getByRole('button', { name: OPEN_FROM_MAIN_MENU_LABEL }));

  return mounted;
}

/**
 * Waits until the back arm has landed on the browse stand-in.
 * @returns {Promise<void>} Resolves once the browse marker is on the document.
 */
async function waitForTheBrowseToBeReached(): Promise<void> {
  await waitFor(
    /**
     * Re-queries for the browse marker until the transfer has happened.
     * @returns {void} Nothing; throws until the destination is mounted.
     */
    (): void => {
      expect(screen.getByText(BROWSE_PROBE_TEXT)).toBeInTheDocument();
    },
  );
}

/**
 * Groups the cases holding `F3=Save&&Exit` to handing its outcome on rather than discarding it.
 *
 * ⚠️ Purpose: a browser sweep measured `PUT /api/v1/auth/users/{id}` returning `200` followed
 * immediately by a route change whose band was EMPTY -- the write happened and the operator was told
 * nothing, on the one key whose own legend at `app/bms/COUSR02.bms` L163 promises `F3=Save&&Exit`. The
 * reference does send its outcome on this path (`app/cbl/COUSR02C.cbl` L370-L377 composes the green
 * sentence and performs `SEND-USRUPD-SCREEN`); what discards it there is the `EXEC CICS XCTL` at
 * L258-L261 overwriting the terminal, which is a delivery artefact and not a decision.
 * @returns {void} Nothing; registering the cases is the whole of its effect.
 */
function saveHandoverCases(): void {
  /**
   * Confirms a committed write hands the composed acknowledgement over at success severity.
   * @returns {Promise<void>} Resolves once the retained sentence has been asserted.
   */
  async function handsTheCommittedSentenceOver(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    /*
     * WHY : Assumptions: the row is opened FROM THE BROWSE, because the hand-over is retained only for a
     *       destination that collects it and the browse is that destination. A case that arrived directly
     *       would leave for the administrative menu, which collects nothing -- and the case immediately
     *       below asserts that nothing is left behind on that path, so the two together pin both arms of
     *       the reference's own destination choice at `app/cbl/COUSR02C.cbl` L113-L118.
     */
    const { user } = await mountScreenFromTheBrowse();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK03');

    await waitForTheBrowseToBeReached();

    // WHY : Assumptions: the sentence is COMPOSED from the template rather than retyped, for the reason
    //       the band case above records -- the reference's `STRING` at L372-L374 concatenates `'User '`,
    //       the key `DELIMITED BY SPACE` and `' has been updated ...'`, and composing it here applies
    //       the same delimiter rule. Comparing against a retyped literal would let a lost space pass.
    expect(collectHandover()).toEqual({
      message: formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
        'SEC-USR-ID': SAVED_ROW.userId,
      }),
      severity: 'success',
    });
  }

  /**
   * Confirms a REFUSED write hands its refusal over too, at error severity.
   * @returns {Promise<void>} Resolves once the retained refusal has been asserted.
   */
  async function handsARefusedWriteOver(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockRejectedValue(refusedWith(500));
    const { user } = await mountScreenFromTheBrowse();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK03');

    await waitForTheBrowseToBeReached();

    // WHY : Assumptions: the refusal matters MORE than the acknowledgement, not less. No `ERR-FLG` test
    //       sits between `UPDATE-USER-INFO` and `RETURN-TO-PREV-SCREEN` at L112 and L119, so this key
    //       transfers whether the write committed or was refused -- and an operator who leaves on a
    //       refused write, believing they saved, is the one the sentence is most needed by.
    expect(collectHandover()).toEqual({
      message: SHARED_MESSAGES.UNABLE_TO_UPDATE_USER,
      severity: 'error',
    });
  }

  /**
   * Confirms the save key that STAYS on the screen hands nothing over.
   * @returns {Promise<void>} Resolves once the absence of a handover has been asserted.
   */
  async function leavesNothingBehindWhenItDoesNotLeave(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreenWithDestination();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK05');
    await waitFor(
      /**
       * Waits until the acknowledgement is on this screen's own band.
       * @returns {void} Nothing; throws until the sentence is present.
       */
      () => {
        expect(messageBand()).toHaveTextContent(SAVED_ROW.userId);
      },
    );

    // WHY : Assumptions: retaining on PF5 would be a defect and not merely redundant. The outcome is
    //       collected by whichever screen mounts next and is delivered exactly once, so an uncollected
    //       one left here would surface on the operator's next arrival at the browse and report a write
    //       they were already told about -- which they cannot tell apart from a second write.
    expect(
      claimRetainedOutcome(USER_UPDATE_SAVE_CLAIM),
      'a save that stays on the screen has already shown its outcome',
    ).toBeUndefined();
  }

  /**
   * ⚠️ Confirms a DEEP-LINKED arrival hands its sentence to the administrative menu it leaves for.
   *
   * ⚠️ Purpose: this is the arm the finding was reported about, and it is the arm that was open. An
   * operator who reaches this route by its address names no calling program, so
   * `app/cbl/COUSR02C.cbl` L113-L118 sends this key to `'COADM01C'` -- and until the administrative menu
   * carried a collector, the retention guard admitted the browse alone and this operator saw a
   * `200`-returning write followed by an empty band. The guard now admits both destinations, so the
   * sentence survives the transfer on the path a deep link takes.
   *
   * Assumptions: the arrival is modelled by starting the history at the edit path with NO origin in its
   * state, which is what a deep link produces and what selects the reference's own fallback.
   * @returns {Promise<void>} Resolves once the retained sentence has been asserted.
   */
  async function handsTheSentenceToTheAdministrativeMenu(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    const { user } = await mountScreenWithDestination();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK03');
    await waitFor(
      /**
       * Waits until the administrative menu has been reached.
       * @returns {void} Nothing; throws until the destination is mounted.
       */
      (): void => {
        expect(screen.getByText(ADMIN_MENU_PROBE_TEXT)).toBeInTheDocument();
      },
    );

    // WHY : Assumptions: the sentence is composed from the template rather than retyped, for the reason
    //       the browse case above records -- the reference's `STRING` at L372-L374 concatenates `'User '`,
    //       the key `DELIMITED BY SPACE` and `' has been updated ...'`.
    expect(collectHandover()).toEqual({
      message: formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
        'SEC-USR-ID': SAVED_ROW.userId,
      }),
      severity: 'success',
    });
  }

  /**
   * Confirms nothing is left behind when the destination has no collector.
   * @returns {Promise<void>} Resolves once the absence of a retained outcome has been asserted.
   */
  async function retainsNothingForADestinationThatCannotCollect(): Promise<void> {
    identityTransport.getUser.mockResolvedValue(STORED_ROW);
    identityTransport.updateUser.mockResolvedValue(SAVED_ROW);
    /*
     * WHY : ⚠️ Assumptions: the destination is reached through an ADMITTED origin that collects nothing,
     *       for the reason {@link MainMenuOriginProbe} records. Both of this key's ordinary destinations
     *       now collect, so a deep link no longer exercises this arm -- and an origin outside the closed
     *       set falls back to the administrative menu, which collects too. The main menu is the only
     *       shape left that reaches a non-collecting destination, which is exactly why the guard is still
     *       written as an enumeration of collectors rather than as an approval of every destination.
     */
    const { user } = await mountScreenFromANonCollectingCaller();
    await fetchThenEdit(user);

    await pressPfKey(user, 'PFK03');
    await waitFor(
      /**
       * Waits until the main-menu stand-in has been reached again.
       * @returns {void} Nothing; throws until the destination is mounted.
       */
      (): void => {
        expect(screen.getByText(MAIN_MENU_PROBE_TEXT)).toBeInTheDocument();
      },
    );

    // WHY : ⚠️ Assumptions: this is the case that keeps the hand-over from becoming a WORSE defect than
    //       the silence it fixes. A retained outcome is held until somebody takes it, so one retained for
    //       a screen that never collects would be taken by the next screen that does -- the browse -- and
    //       an operator arriving there minutes later would read an acknowledgement of a write they were
    //       never shown, indistinguishable from one describing what they just did.
    expect(
      claimRetainedOutcome(USER_UPDATE_SAVE_CLAIM),
      'an outcome no screen will collect must not be left in the store',
    ).toBeUndefined();
  }

  it('hands the committed sentence to the screen it transfers to', handsTheCommittedSentenceOver);
  it('hands a refused write over as well', handsARefusedWriteOver);
  it('hands nothing over when it does not leave', leavesNothingBehindWhenItDoesNotLeave);
  it('hands the sentence to the administrative menu too', handsTheSentenceToTheAdministrativeMenu);
  it(
    'leaves nothing behind for a destination that cannot collect it',
    retainsNothingForADestinationThatCannotCollect,
  );
}

describe('the exit key carries its own outcome across the transfer', saveHandoverCases);
