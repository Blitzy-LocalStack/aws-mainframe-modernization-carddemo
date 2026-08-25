/**
 * @file Proves the administrative menu screen transcribes `app/cbl/COADM01C.cbl` and its mapset
 * `app/bms/COADM01.bms` (map `COADM1A`): the one field an operator may type into, the six options it
 * paints, the routes those options enter, the two keys it binds, and the sentences it answers with.
 *
 * Purpose
 * -------
 * `ui/src/screens/admin/index.tsx` is the migration target of CICS transaction `CA00`, mounted by
 * `ui/src/router.tsx` at `/admin`. The unit under test is that module's single default export.
 *
 * ⚠️ This file is the only verification this screen gets, and that is a property of the runner rather
 * than a choice made here. `tests/README.md` section 1.1 records it in as many words: the online
 * `CO*` programs "cannot run end-to-end without a CICS runtime (absent on the runner); only their
 * extractable field-validation logic is unit-tested". The COBOL parity oracle under `tests/` is
 * therefore the oracle for the BATCH chain alone -- **no golden master exists for `COADM01C`** -- so
 * every expectation below is measured directly out of the reference source and cited to the file and
 * line it was read from.
 *
 * Parameters
 * ----------
 * Not applicable. A test module is an entry point the runner invokes with no arguments; the inputs
 * each case needs are the seeded session it establishes and the entry it types.
 *
 * Returns
 * -------
 * Not applicable. Cases report through assertions rather than through a value.
 *
 * Exceptions or errors
 * --------------------
 * A failed expectation, an absent element reported by a Testing Library query, and a seeding failure
 * reported by `seedSession` all propagate and fail the case that raised them. Nothing is caught here:
 * a swallowed failure is a green case that verified nothing, which on a screen with no golden master
 * is the one outcome worth preventing.
 *
 * Documentation obligation, double-anchored
 * ----------------------------------------
 * Two independent sources impose the same obligation on this file and they agree, so what follows
 * extends an established house convention rather than introducing one. Rule 1 "Explainability" (the
 * project's only user-specified rule) requires a docstring stating purpose, parameters, returns and
 * exceptions on every function and module entry point, and inline comments that justify a decision
 * through Alternatives Considered, Refactoring Rationale, Assumption or Trade-off rather than
 * restating the code. `tests/README.md` section 12 imposes the identical requirement on "every new
 * test, fixture builder, helper, mock, and runner routine" and calls it "a hard review gate".
 * `docs/CODE_DOCUMENTATION_STANDARD.md` is the written form both are held to.
 *
 * ⚠️ Four expectations a reader may arrive with are wrong for this tree, and each is settled against
 * the repository rather than against the assumption
 * ---------------------------------------------------------------------------------------------
 * 1. The runner injects `describe`, `it` and `expect`, yet they are still IMPORTED by name below.
 *    `ui/tsconfig.json` keeps `"types": []`, so no ambient test declaration is reachable and a file
 *    that omitted the import would fail `tsc --noEmit` on the symbol it omitted. `ui/vitest.config.ts`
 *    records the pairing deliberately: the injection governs run time, the empty `types` list governs
 *    the compiler, and the imports are what satisfy the compiler.
 * 2. AAP section 0.4.1.4 designates an antd `Menu` for this route, and the delivered screen renders
 *    `Typography.Text` lines instead. That is argued in the screen's own Alternatives Considered
 *    block and it is the mapset's decision: `OPTN001`-`OPTN012` at `app/bms/COADM01.bms` L80-L139 are
 *    `ATTRB=(ASKIP,FSET,NORM)`, and `ASKIP` makes them protected and unselectable -- the terminal
 *    takes the selection ONLY through the separate `NUM` control at L145. So this file asserts the
 *    stronger fidelity contract the mapset states: the option lines carry no interactive role, and the
 *    screen offers exactly one control that accepts input.
 * 3. There is no per-field error surface to assert, and its absence is measured rather than assumed.
 *    `app/cpy/CSSETATY.cpy` -- the templated highlight that moves `DFHRED` into a field's colour
 *    subfield and `'*'` into its output subfield at L24 -- is `COPY`ed by exactly one program in the
 *    baseline, `app/cbl/COACTUPC.cbl`; `COADM01C`'s own `COPY` list at L50-L61 does not include it,
 *    and its mapset declares one message field, `ERRMSG` at L154. This screen's whole error channel is
 *    therefore the row-23 band, and the case below asserts that -- together with the absence of the
 *    field-level state and of the blank marker, which is what a regression adding one would trip.
 * 4. Administrative options 3, 4 and 6 do not enter a parameterised path. `ui/src/routes/programRoutes.ts`
 *    resolves `COUSR02C` and `COUSR03C` to the user browse because their screens are addressable only
 *    per record while a menu option carries no selection, and `COTRTUPC` to the add sentinel for the
 *    same reason. Every path this file expects is IMPORTED from `ui/src/router.tsx` or derived from its
 *    published route table, so no path text is retyped here and the parameter names those patterns use
 *    are whichever ones that module declares.
 *
 * Assumptions: every expectation is the constant the screen, the router or the catalog PUBLISHES, never
 * a retyped sentence. A retyped string agrees with a screen that has drifted provided the test drifted
 * with it, which is exactly the failure Transformation Rule T8 exists to prevent -- so the catalog is
 * asserted against the DOM and the test asserts nothing of its own about wording.
 */

import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import type { ReactElement } from 'react';
import {
  Route,
  RouterProvider,
  Routes,
  createMemoryRouter,
  matchPath,
  useLocation,
} from 'react-router';
import { describe, expect, it } from 'vitest';

import { discardRetainedOutcomes, retainOutcomeAcrossNavigation } from '../api/client';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import type { CicsAid } from '../layout/usePfKeys';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  ADMIN_MENU_OPTIONS,
  ADMIN_MENU_OPTION_COUNT,
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MAIN_MENU_OPTIONS,
  MENU_OPTION_DECLARED_WIDTH,
  MESSAGE_TEMPLATES,
  SHARED_MESSAGES,
  formatMessageTemplate,
  normaliseMessageBandValue,
} from '../messages/messages';
import type { AdminMenuOption } from '../messages/messages';
import { USER_UPDATE_ROUTE_TEMPLATE } from '../routes/navigation';
import {
  CARD_DEMO_ROUTES,
  REF_TYPE_LIST_PATH,
  ROUTE_TABLE,
  USER_ADD_PATH,
  USER_LIST_PATH,
} from '../router';
import type { RouteTableEntry } from '../router';
import AdminMenuScreen, {
  ADMIN_MENU_DESTINATIONS,
  ADMIN_MENU_KEY_LABELS,
  ADMIN_MENU_OPTION_WIDTH,
  ADMIN_MENU_PROGRAM_NAME,
  ADMIN_MENU_PROMPT,
  ADMIN_MENU_SUBTITLE,
  composeAdminOptionLine,
} from '../screens/admin';
import type { UserUpdateSaveHandover } from '../screens/userUpdate';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  expectMaxLength,
  expectVerbatimMessage,
  pressPfKey,
  renderInAppShell,
  renderWithProviders,
  seedSession,
  shellLandmark,
} from './setup';

/*
 * WHY : Assumptions: the two paths below are DERIVED from the shipped route table by the program each
 *       row cites, not written out. `ui/src/router.tsx` publishes `ROUTE_TABLE` precisely so the
 *       reachability graph can be checked against the reference's own option copybooks, which name
 *       programs rather than paths, and deriving from it means this file cannot hold a path the router
 *       does not mount. The sign-on path is reached the same way because it is the destination
 *       `app/cbl/COADM01C.cbl` L100-L102 names for PF3, by moving `'COSGN00C'` into
 *       `CDEMO-TO-PROGRAM`.
 */

/** Program name the reference's sign-off transfers to, from `app/cbl/COADM01C.cbl` L101. */
const SIGN_ON_PROGRAM_NAME = 'COSGN00C';

/**
 * Guard class `ui/src/router.tsx` reserves for exactly the six options of `app/cpy/COADM02Y.cpy`.
 *
 * Assumptions: `/admin` is deliberately NOT in this class -- its own row is `authenticated` -- so this
 * constant selects the six DESTINATIONS and never the menu that lists them. That asymmetry is the
 * router's documented decision and is asserted below rather than worked around.
 */
const ADMINISTRATIVE_ACCESS = 'administrative';

/**
 * Segment `ui/src/router.tsx` mounts the transaction-type ADD form at, within its dynamic route.
 *
 * Assumptions: this is a sentinel rather than a record key, which is why administrative option 6 can
 * name it at all: `app/cpy/COADM02Y.cpy` L52 gives that option the maintenance program and a menu
 * option carries no type code, so the only address it can supply is the one that means "new".
 */
const REFERENCE_TYPE_ADD_SENTINEL = 'new';

/** Test identifier the arrival probe carries, so a route change is observable without a screen. */
const ARRIVAL_TEST_ID = 'admin-menu-arrival-probe';

/**
 * Width the symbolic map declares for the option control, from `OPTIONI PIC X(2)` at
 * `app/cpy-bms/COADM01.CPY` L132.
 *
 * Assumptions: this is read from the SYMBOLIC MAP and is deliberately a second declaration of the same
 * width the screen publishes from the mapset's `LENGTH=2` at `app/bms/COADM01.bms` L148. The two are
 * independent statements of one contract -- BMS generates the copybook from the mapset, so a mapset
 * edited without regenerating would make them disagree -- and the case below asserts they agree
 * instead of trusting either alone.
 */
const OPTION_SYMBOLIC_MAP_WIDTH = 2;

/**
 * Characters the row-24 legend field declares, from `LENGTH=23` at `app/bms/COADM01.bms` L160.
 *
 * Assumptions: this is what lets the two legend descriptors be checked against the mapset without
 * retyping its `INITIAL=` sentence. The field paints `'ENTER=Continue  F3=Exit'` at L162 -- two
 * descriptors separated by two spaces -- so the two published labels plus that separator must occupy
 * exactly this many characters, and any drift in either label breaks the arithmetic.
 */
const LEGEND_FIELD_WIDTH = 23;

/** Separator the row-24 legend places between descriptors, from `app/bms/COADM01.bms` L162. */
const LEGEND_DESCRIPTOR_SEPARATOR = '  ';

/**
 * Resolves the path `ui/src/router.tsx` mounts one reference program at.
 *
 * Assumptions: the lookup is by PROGRAM because that is what the reference's option copybooks store and
 * what `EXEC CICS XCTL PROGRAM(...)` dispatches on, so a row found this way is directly comparable
 * with `app/cpy/COADM02Y.cpy`. Deriving the path also keeps this file free of route text that could
 * drift from the router's own.
 * @param {string} programName - Reference program name, as `ROUTE_TABLE` cites it.
 * @returns {string} The path pattern that row declares.
 * @throws {Error} If no row cites that program, which would mean the reachability graph no longer
 *   covers a program this file depends on and is worth failing loudly rather than defaulting.
 */
function pathForProgram(programName: string): string {
  const row = ROUTE_TABLE.find(
    /**
     * Matches the row that cites one program.
     * @param {RouteTableEntry} candidate - One published row of the reachability graph.
     * @returns {boolean} `true` when the row cites the wanted program.
     */
    (candidate: RouteTableEntry): boolean => candidate.program === programName,
  );
  if (row === undefined) {
    throw new Error(`ui/src/router.tsx publishes no route for program ${programName}`);
  }
  return row.path;
}

/** Path the administrative menu itself is mounted at, derived from its own program name. */
const ADMIN_MENU_PATH = pathForProgram(ADMIN_MENU_PROGRAM_NAME);

/** Path the reference's PF3 transfers to, derived from the program it names. */
const SIGN_ON_PATH = pathForProgram(SIGN_ON_PROGRAM_NAME);

/**
 * The six paths `ui/src/router.tsx` places behind the administrative guard.
 *
 * Assumptions: this is filtered from the published table rather than listed, so it is whatever the
 * router actually gates. That matters for the closure assertion below: a seventh administrative path
 * added without a corresponding option, or an option pointed at an ungated path, both show up as a
 * mismatch instead of passing against a list this file maintained by hand.
 */
const ADMINISTRATIVE_PATHS: readonly string[] = ROUTE_TABLE.filter(
  /**
   * Selects rows the router places behind the administrative guard.
   * @param {RouteTableEntry} row - One published row of the reachability graph.
   * @returns {boolean} `true` when the row is administratively gated.
   */
  (row: RouteTableEntry): boolean => row.access === ADMINISTRATIVE_ACCESS,
).map(
  /**
   * Reduces a row to the path it declares.
   * @param {RouteTableEntry} row - One administratively gated row.
   * @returns {string} The path pattern.
   */
  (row: RouteTableEntry): string => row.path,
);

/**
 * Renders the address the router currently holds, so a dispatch is observable without a screen.
 *
 * Alternatives Considered: mounting the real destination screens instead. Rejected for the cases that
 * use this probe, because each of those screens is loaded through `React.lazy` and issues its own
 * requests, so a case asserting only that option 5 CHANGES THE ROUTE would additionally depend on the
 * transaction-type browse painting -- and would report a transport failure there as a routing defect
 * here. The two cases that genuinely need the delivered guard mount the shipped route objects instead.
 * @returns {ReactElement} An element carrying the current pathname under {@link ARRIVAL_TEST_ID}.
 */
function ArrivalProbe(): ReactElement {
  const { pathname } = useLocation();
  return <div data-testid={ARRIVAL_TEST_ID}>{pathname}</div>;
}

/**
 * Mounts one probe route.
 *
 * Assumptions: the pattern is passed through unchanged, so a parameterised administrative path such as
 * the user-update route matches by the router's own rules and this file never has to know which
 * parameter name that pattern uses.
 * @param {string} path - Route pattern to mount the probe at.
 * @returns {ReactElement} The route element.
 */
function probeRoute(path: string): ReactElement {
  return <Route key={path} path={path} element={<ArrivalProbe />} />;
}

/**
 * Builds a route tree holding the screen under test and a probe at every address it can reach.
 *
 * Assumptions: the six administratively gated patterns are mounted UNGUARDED here, and that is the
 * point of this tree rather than a gap in it. These cases assert the dispatch -- that entering option
 * n enters route n, which is Transformation Rule T5 turning `EXEC CICS XCTL` into a client route
 * change -- and mounting the guard would mean a refusal and a routing defect produced the same
 * observation. The guard is asserted separately, against the shipped route objects.
 * @returns {ReactElement} The route tree, rooted at the administrative menu.
 */
function adminMenuAmongProbes(): ReactElement {
  return (
    <Routes>
      <Route path={ADMIN_MENU_PATH} element={<AdminMenuScreen />} />
      {[...ADMINISTRATIVE_PATHS, SIGN_ON_PATH].map(probeRoute)}
    </Routes>
  );
}

/**
 * Reports the address the probe last rendered.
 * @returns {string} The pathname the router holds.
 * @throws {Error} Raised by Testing Library when no probe is mounted, which means the dispatch did
 *   not leave the menu at all.
 */
function arrivedAt(): string {
  return screen.getByTestId(ARRIVAL_TEST_ID).textContent ?? '';
}

/**
 * Locates the screen's one editable control.
 *
 * Assumptions: the query is by ROLE and is deliberately unqualified, so it doubles as the assertion
 * that there is exactly one. `app/bms/COADM01.bms` declares 28 `DFHMDF` fields of which exactly one
 * is unprotected -- `OPTION` at L145, `ATTRB=(FSET,IC,NORM,NUM,UNPROT)` -- and Testing Library's
 * `getByRole` fails on a second match, so a screen that grew a second input fails here by name.
 * @returns {HTMLElement} The option entry control.
 * @throws {Error} Raised by Testing Library when the control is absent or no longer unique.
 */
function optionControl(): HTMLElement {
  return screen.getByRole('textbox');
}

/**
 * Reports the shell's row-23 message band.
 * @returns {HTMLElement} The band element, populated or reserved.
 * @throws {Error} Raised by Testing Library when no band is mounted, which means the subject was
 *   rendered without the shell that owns that zone.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Reports the legend controls the row-24 zone currently paints, in render order.
 *
 * Assumptions: the controls are read from inside the legend's own landmark rather than from the whole
 * document, because the shell also paints a sign-off control of its own and a document-wide button
 * query would count it as a function key.
 * @returns {readonly string[]} The accessible name of each legend control.
 * @throws {Error} Raised by Testing Library when the legend landmark is absent.
 */
function legendDescriptors(): readonly string[] {
  const legend = within(shellLandmark('keyLegend')).getByRole('navigation', {
    name: PF_KEY_BAR_REGION_LABEL,
  });
  return within(legend)
    .getAllByRole('button')
    .map(
      /**
       * Reduces one legend control to the descriptor it paints.
       * @param {HTMLElement} control - One rendered legend control.
       * @returns {string} Its rendered text.
       */
      (control: HTMLElement): string => control.textContent ?? '',
    );
}

/**
 * Dispatches one attention identifier as a real key press.
 *
 * ⚠️ Alternatives Considered: clicking the legend control instead, everywhere. Rejected outright,
 * because the 3270 original had no pointer -- the whole PF-key contract is a KEYBOARD contract -- so a
 * suite that only clicked would stay green while every keyboard binding in the application was broken,
 * and the click would be exercising the bar rather than the binding. The two paths are therefore
 * driven separately and asserted to agree; {@link dispatchByLegendControl} is the other half.
 * @param {UserEvent} user - The operator from a render helper's result.
 * @param {CicsAid} aid - The attention identifier to raise.
 * @returns {Promise<void>} Resolves once the key event has been dispatched and settled.
 */
async function dispatchByKeyboard(user: UserEvent, aid: CicsAid): Promise<void> {
  await pressPfKey(user, aid);
}

/**
 * Dispatches one attention identifier by activating the legend control that advertises it.
 *
 * Assumptions: the control is found by the descriptor the screen publishes for that key, so this
 * cannot drift from the legend the mapset paints without the lookup failing.
 * @param {UserEvent} user - The operator from a render helper's result.
 * @param {string} descriptor - The legend text the control paints, from `ADMIN_MENU_KEY_LABELS`.
 * @returns {Promise<void>} Resolves once the activation has been dispatched and settled.
 * @throws {Error} Raised by Testing Library when no legend control paints that descriptor.
 */
async function dispatchByLegendControl(user: UserEvent, descriptor: string): Promise<void> {
  const legend = within(shellLandmark('keyLegend')).getByRole('navigation', {
    name: PF_KEY_BAR_REGION_LABEL,
  });
  await user.click(within(legend).getByRole('button', { name: descriptor }));
}

/**
 * Types an option entry and submits it with Enter.
 *
 * Assumptions: the control is cleared first, so a case may submit several entries against one render
 * without the previous one's digits surviving into the next -- the reference re-sends the whole map on
 * a refused turn and echoes the normalised value back into the field
 * (`app/cbl/COADM01C.cbl` L129), so the field is populated rather than empty when a second entry
 * begins.
 *
 * Assumptions: Enter arrives as a genuine key event on the focused control, which is the reference's
 * own `DFHENTER` arm at `app/cbl/COADM01C.cbl` L98. Both the control's own submit handler and the
 * screen's key dispatcher observe it, because `ui/src/layout/usePfKeys.ts` deliberately keeps a text
 * input out of `ENTER_ACTIVATED_TARGET_SELECTOR` -- Enter in a text field IS the 3270 submit gesture.
 * The two arrive at one function, so the duplication changes nothing observable and reproducing it is
 * more faithful than suppressing it.
 * @param {UserEvent} user - The operator from a render helper's result.
 * @param {string} entry - What the operator types into the two-position option control.
 * @returns {Promise<void>} Resolves once the entry has been submitted and settled.
 * @throws {Error} Raised by Testing Library when the option control is absent or not unique.
 */
async function submitOption(user: UserEvent, entry: string): Promise<void> {
  const control = optionControl();
  await user.clear(control);
  if (entry !== '') {
    await user.type(control, entry);
  }
  await dispatchByKeyboard(user, 'ENTER');
}

/**
 * Signs on and renders the screen inside the application shell, at its own route.
 *
 * Assumptions: the shell is mounted rather than the screen rendered bare, because this screen delegates
 * all three persistent zones to it -- the title band, the row-23 message band and the row-24 key
 * legend -- through `useShellSlot`. A bare render paints none of them, so a case asserting a message
 * or a legend against a bare tree would be asserting against a composition the application never
 * builds.
 * @param {readonly string[]} groups - Group names the session's identity token is to carry.
 * @returns {Promise<{ user: UserEvent; isSignedOn: () => boolean; unmount: () => void }>} The
 *   operator, a live reading of whether a session is still held, and the teardown a case calls before
 *   mounting a second shell -- two shells at once would publish two of every landmark.
 * @throws {Error} If the sign-on exchange did not establish a session, which `seedSession` reports.
 */
async function renderInShellAs(
  groups: readonly string[],
): Promise<{ user: UserEvent; isSignedOn: () => boolean; unmount: () => void }> {
  const session = await seedSession({ groups });
  const { user, unmount } = await renderInAppShell(<AdminMenuScreen />, {
    initialEntries: [ADMIN_MENU_PATH],
    routePath: ADMIN_MENU_PATH,
  });
  return {
    user,
    /**
     * Reads whether the seeded session is still held.
     * @returns {boolean} `true` while a session is held.
     */
    isSignedOn: (): boolean => session.result.current.signedOn,
    unmount,
  };
}

/**
 * Signs on and renders the screen among arrival probes, so a dispatch is observable as an address.
 * @param {readonly string[]} groups - Group names the session's identity token is to carry.
 * @returns {Promise<{ user: UserEvent; unmount: () => void }>} The operator, and the teardown a case
 *   calls between entries so each option is entered from a fresh menu.
 * @throws {Error} If the sign-on exchange did not establish a session, which `seedSession` reports.
 */
async function renderAmongProbesAs(
  groups: readonly string[],
): Promise<{ user: UserEvent; unmount: () => void }> {
  await seedSession({ groups });
  const { user, unmount } = await renderWithProviders(adminMenuAmongProbes(), {
    initialEntries: [ADMIN_MENU_PATH],
  });
  return { user, unmount };
}

/**
 * Signs on and opens the SHIPPED route objects at the administrative menu.
 *
 * ⚠️ Assumptions: the delivered array is mounted, not a tree assembled here, because these two cases
 * are the ones whose subject IS the guard. `ui/src/router.tsx` nests the six administrative paths
 * inside `RequireAdmin`, and a tree this file built would assert against a guard this file placed --
 * which cannot fail when the router stops placing one. The array is spread because it is published
 * `readonly` while the factory declares a mutable parameter, and the copy also keeps a case from
 * mutating the tree every other case reads.
 *
 * Trade-offs: every screen in that array is loaded through `React.lazy`, so these cases pay a module
 * transform the probe-based cases do not. Accepted because the delivered guard cannot be observed any
 * other way, and `ui/src/test/setup.ts` raises the asynchronous query budget for exactly this reason.
 * @param {readonly string[]} groups - Group names the session's identity token is to carry.
 * @returns {Promise<{ user: UserEvent; addressNow: () => string }>} The operator, and a live reading
 *   of the address the router holds.
 * @throws {Error} If the sign-on exchange did not establish a session, which `seedSession` reports.
 */
async function openShippedRoutesAs(
  groups: readonly string[],
): Promise<{ user: UserEvent; addressNow: () => string }> {
  await seedSession({ groups });
  const user = userEvent.setup();
  const router = createMemoryRouter([...CARD_DEMO_ROUTES], {
    initialEntries: [ADMIN_MENU_PATH],
  });
  render(<RouterProvider router={router} />);
  return {
    user,
    /**
     * Reads the address the router currently holds.
     * @returns {string} The pathname.
     */
    addressNow: (): string => router.state.location.pathname,
  };
}

/**
 * Reports the route each administrative option is declared to enter, in option order.
 * @returns {readonly (readonly [number, string | null])[]} One pair per option: its number and the
 *   route its target program resolves to.
 */
function declaredDestinations(): readonly (readonly [number, string | null])[] {
  return ADMIN_MENU_OPTIONS.map(
    /**
     * Pairs one option with the route its target program resolves to.
     * @param {AdminMenuOption} option - One transcribed administrative option.
     * @returns {readonly [number, string | null]} The option number and its declared route.
     */
    (option: AdminMenuOption): readonly [number, string | null] => [
      option.optionNumber,
      ADMIN_MENU_DESTINATIONS[option.programName] ?? null,
    ],
  );
}

/**
 * Asserts that the screen offers exactly one control an operator may type into.
 *
 * ⚠️ Assumptions: "exactly one" is the mapset's own arithmetic and not a simplification.
 * `app/bms/COADM01.bms` declares 28 `DFHMDF` fields; 27 of them carry `ASKIP` or are the anonymous
 * spacer, and exactly one -- `OPTION` at L145 -- carries `UNPROT`. The option LINES are among the
 * protected ones, `ATTRB=(ASKIP,FSET,NORM)` at L80-L139, which is why they are asserted to expose no
 * interactive role: `ASKIP` means the terminal would not let a cursor rest on them, so an activatable
 * menu item here would offer a second way to choose an option that the reference never offered.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function offersTheMapsetsOneUnprotectedField(): Promise<void> {
  await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  expect(screen.getAllByRole('textbox')).toHaveLength(1);
  // WHY : Assumptions: the prompt is the mapset's own 25-character field at `app/bms/COADM01.bms`
  //       L140-L144, and it is asserted through the constant the screen publishes so its trailing
  //       space before the colon -- part of the declared 25 -- cannot be tidied away by either side.
  expect(expectVerbatimMessage(ADMIN_MENU_PROMPT)).toBeInTheDocument();
  expect(optionControl()).toHaveAttribute('aria-labelledby', expect.any(String));

  // WHY : Assumptions: the option lines are queried for the roles a `Menu` or a list of `Button` items
  //       would have introduced, because those are the two shapes AAP section 0.4.1.4 and the design
  //       system's component mapping suggest and the mapset's `ASKIP` attribute forbids. Asserting
  //       their absence is what makes the screen's documented deviation from that mapping falsifiable.
  const optionLine = expectVerbatimMessage(composeAdminOptionLine(ADMIN_MENU_OPTIONS[0]).trimEnd());
  expect(within(shellLandmark('screenBody')).queryAllByRole('menuitem')).toHaveLength(0);
  expect(optionLine.closest('button')).toBeNull();
  expect(optionLine.closest('a')).toBeNull();
}

/**
 * Asserts the option field is held to the width the mapset and its symbolic map both declare.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function holdsTheOptionFieldToItsDeclaredWidth(): Promise<void> {
  const { user } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  // WHY : Assumptions: the two declarations are asserted to AGREE before either is trusted. The screen
  //       publishes `ADMIN_MENU_OPTION_WIDTH` from `LENGTH=2` at `app/bms/COADM01.bms` L148, while
  //       `OPTION_SYMBOLIC_MAP_WIDTH` is read from `OPTIONI PIC X(2)` at `app/cpy-bms/COADM01.CPY`
  //       L132. BMS generates the copybook from the mapset, so a mapset edited without regenerating
  //       would make them disagree -- and a case that asserted only one would not notice.
  expect(ADMIN_MENU_OPTION_WIDTH).toBe(OPTION_SYMBOLIC_MAP_WIDTH);
  expectMaxLength(optionControl(), OPTION_SYMBOLIC_MAP_WIDTH);

  // WHY : Assumptions: the terminal enforced the width in hardware -- a `PIC X(2)` field took two
  //       characters and the third keystroke did nothing -- so the third digit must be REFUSED rather
  //       than accepted and truncated later, which is a different observable behaviour.
  await user.type(optionControl(), '123');
  expect(optionControl()).toHaveValue('12');
}

/**
 * Asserts the field refuses everything but digits, which is the mapset's `NUM` attribute.
 *
 * Assumptions: `NUM` is a narrow contract rather than a general one, which is why it is worth its own
 * case: of the 17 base mapsets only `COADM01`, `COMEN01` and `CORPT00` carry the attribute at all. On
 * the terminal a letter could not be keyed into the field, so the reference has no message for one --
 * the entry is refused, not reported.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function refusesEveryNonDigitInTheNumericField(): Promise<void> {
  const { user } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  await user.type(optionControl(), 'A7b');
  // WHY : Assumptions: `ATTRB=(FSET,IC,NORM,NUM,UNPROT)` at `app/bms/COADM01.bms` L145 admits digits
  //       only, so the two letters leave no trace and the one digit survives where it was typed.
  expect(optionControl()).toHaveValue('7');

  await user.clear(optionControl());
  await user.type(optionControl(), '-.');
  expect(optionControl()).toHaveValue('');
  expect(messageBand()).toHaveTextContent('');
}

/**
 * Asserts the cursor arrives in the option field, which is the mapset's single `IC` attribute.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function placesTheArrivalCursorInTheOptionField(): Promise<void> {
  await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  // WHY : Assumptions: `IC` appears once in this mapset, on `OPTION` at `app/bms/COADM01.bms` L145,
  //       so the screen has exactly one initial-cursor field.
  // WHY : Alternatives Considered: asserting the focused element alone. Rejected because it cannot
  //       distinguish one initial-cursor field from two -- a second autofocused input would steal the
  //       focus and satisfy a lone focus check -- so the pair of assertions is what makes "exactly
  //       one" checkable.
  expect(optionControl()).toHaveFocus();
  expect(screen.getAllByRole('textbox')).toHaveLength(1);
}

/**
 * Asserts the copybook's live option count is six, not the four it superseded.
 *
 * ⚠️ Assumptions: `app/cpy/COADM02Y.cpy` holds TWO declarations of `CDEMO-ADMIN-OPT-COUNT`. L21 says
 * `VALUE 4` and is COMMENTED OUT; L22 says `VALUE 6` and is live. Four is the superseded value rather
 * than a competing one -- the `Option added for Db2 V1 release` markers at L45 and L54 bracket the two
 * entries that raised it -- so reading the commented line would drop two options the copybook, the
 * mapset and this menu all still carry. The record's own arity is wider still, `OCCURS 9 TIMES` at
 * L56, and is terminal-grid capacity rather than a count of options.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function countsSixAdministrativeOptions(): void {
  expect(ADMIN_MENU_OPTION_COUNT).toBe(6);
  expect(ADMIN_MENU_OPTIONS).toHaveLength(ADMIN_MENU_OPTION_COUNT);
}

/**
 * Reports the user type one main-menu option restricts itself to.
 *
 * Assumptions: this exists as a named function rather than an inline callback for the two reasons the
 * sibling screen suites record -- `ui/eslint.config.js` requires a documentation block on a function
 * expression in any position, and Prettier relocates a block comment that follows an argument comma
 * onto the preceding literal, detaching it from the function it documents.
 * @param {(typeof MAIN_MENU_OPTIONS)[number]} option - One transcribed main-menu option.
 * @returns {'A' | 'U'} The option's `CDEMO-MENU-OPT-USRTYPE` value.
 */
function userTypeOf(option: (typeof MAIN_MENU_OPTIONS)[number]): 'A' | 'U' {
  return option.userType;
}

/**
 * Asserts an administrative option carries no user type, where a main-menu option does.
 *
 * ⚠️ Assumptions: this asymmetry is the reference's and is not tidied away. `app/cpy/COADM02Y.cpy`
 * L55-L59 declares its entry as number, name and program name and stops there, while
 * `app/cpy/COMEN02Y.cpy` L93-L98 ends its entry with `CDEMO-MENU-OPT-USRTYPE PIC X(01)`. The reason is
 * structural rather than accidental: reaching the administrative menu already implies administrative
 * authority, so a per-option type would be redundant once the route guard has run. Adding one to make
 * the two shapes symmetrical would invent a field the baseline does not have.
 *
 * Assumptions: the contrast is completed from the other side. All eleven main-menu options carry user
 * type `'U'` -- `app/cpy/COMEN02Y.cpy` L29 through L90, not one of them `'A'` -- so administrative
 * gating applies to this menu's destinations and nowhere else in the reference.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function carriesNoUserTypeOnAnAdministrativeOption(): void {
  expect(
    ADMIN_MENU_OPTIONS.every(
      /**
       * Reports whether one administrative option omits a user-type member.
       * @param {AdminMenuOption} option - One transcribed administrative option.
       * @returns {boolean} `true` when the option carries no user type.
       */
      (option: AdminMenuOption): boolean => !Object.hasOwn(option, 'userType'),
    ),
  ).toBe(true);

  expect(MAIN_MENU_OPTIONS).toHaveLength(11);
  /*
   * WHY : Assumptions: the contrast is asserted as the SET of user types the main menu carries, rather
   *       than as a search for an administrative one. The two say the same thing about the data and
   *       only this one compiles: the catalog declares its options `as const`, so every `userType` has
   *       the literal type `'U'` and TypeScript rejects a comparison against `'A'` as provably false --
   *       `error TS2367`, which is the compiler making the same measurement this case does. Collecting
   *       the distinct values keeps the assertion runtime-checkable while leaving the compiler's proof
   *       intact, and it still fails the moment an `'A'` is introduced.
   */
  expect([...new Set(MAIN_MENU_OPTIONS.map(userTypeOf))]).toEqual(['U']);
}

/**
 * Asserts all six option lines are painted, in order, exactly as the catalog holds them.
 *
 * Assumptions: each line is compared against `composeAdminOptionLine`, which composes it through the
 * catalog's own template -- `app/cbl/COADM01C.cbl` L236-L238 `STRING`s the two-digit number, `'. '`
 * and the 35-character name -- so the expectation is the reference's composition rather than a
 * sentence retyped six times. The trailing padding is trimmed for the QUERY only, because Testing
 * Library trims the boundary whitespace markup introduces; the untrimmed form is then asserted against
 * the element's own text so the `PIC X(35)` padding is proved intact in the DOM.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function paintsEverySixOptionLineInOrder(): Promise<void> {
  await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  const painted: string[] = [];
  for (const option of ADMIN_MENU_OPTIONS) {
    const composed = composeAdminOptionLine(option);
    const line = expectVerbatimMessage(composed.trimEnd());
    // WHY : Assumptions: the name's declared width is `PIC X(35)` at `app/cpy/COADM02Y.cpy` L58, so
    //       the padding is content the catalog stores rather than formatting a renderer may drop.
    expect(option.name).toHaveLength(MENU_OPTION_DECLARED_WIDTH);
    expect(line.textContent).toBe(composed);
    painted.push(line.textContent ?? '');
  }

  // WHY : Assumptions: `BUILD-MENU-OPTIONS` at `app/cbl/COADM01C.cbl` L231-L239 fills the slots in
  //       ascending index order, so display ORDER is part of the contract and not merely the set.
  expect(painted).toEqual(
    ADMIN_MENU_OPTIONS.map(
      /**
       * Composes the line one option is expected to paint.
       * @param {AdminMenuOption} option - One transcribed administrative option.
       * @returns {string} The composed line.
       */
      (option: AdminMenuOption): string => composeAdminOptionLine(option),
    ),
  );
}

/**
 * Asserts entering option n enters route n, for every one of the six, with no off-by-one.
 *
 * Assumptions: this is Transformation Rule T5 applied to this screen --
 * `EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))` at `app/cbl/COADM01C.cbl` L145-L148
 * becomes a client route change -- so the property under test is the whole chain from the typed digits
 * through the subscript to the address, which is where an off-by-one would live. The six arrivals are
 * collected and compared in ONE assertion so a failure names the option that diverged rather than
 * merely the case.
 *
 * Assumptions: each option is entered from a FRESH menu, because a successful entry leaves the menu --
 * the screen unmounts with the route change -- so a second entry against the same render would have no
 * control to type into.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function entersTheRouteEveryOptionNames(): Promise<void> {
  const arrivals: (readonly [number, string])[] = [];

  for (const option of ADMIN_MENU_OPTIONS) {
    const { user, unmount } = await renderAmongProbesAs([CARDDEMO_ADMIN_GROUP]);
    await submitOption(user, String(option.optionNumber));
    arrivals.push([option.optionNumber, arrivedAt()]);
    unmount();
  }

  expect(arrivals).toEqual(declaredDestinations());
}

/**
 * Pins each option's destination to a path constant the ROUTER publishes.
 *
 * ⚠️ Assumptions: this is the half of the destination contract that does not read the screen's own map,
 * so the two cases together cannot both be satisfied by one wrong table. The values are
 * `ui/src/router.tsx`'s own `USER_LIST_PATH`, `USER_ADD_PATH` and `REF_TYPE_LIST_PATH`, and no path
 * text is written here.
 *
 * ⚠️ Assumptions: options 3 and 4 land on the user BROWSE rather than on a parameterised path, and that
 * is one rule rather than two workarounds. `ui/src/routes/programRoutes.ts` resolves `COUSR02C` and
 * `COUSR03C` there because their screens are addressable only per record while a menu option carries no
 * selection -- which is exactly the state the reference's own first turn paints, since
 * `app/cbl/COUSR02C.cbl` L99-L104 and `app/cbl/COUSR03C.cbl` L99-L104 pre-fill the identifier only when
 * their selection carrier arrives non-blank. The browse is the reference's own caller for both:
 * `app/cbl/COUSR00C.cbl` L192-L207 transfers to them from there. Option 6 lands on the add sentinel for
 * the same reason -- `app/cpy/COADM02Y.cpy` L52 names the maintenance program and a menu option carries
 * no type code.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function pinsEveryDestinationToTheRoutersOwnConstants(): void {
  expect(declaredDestinations()).toEqual([
    [1, USER_LIST_PATH],
    [2, USER_ADD_PATH],
    [3, USER_LIST_PATH],
    [4, USER_LIST_PATH],
    [5, REF_TYPE_LIST_PATH],
    [6, `${REF_TYPE_LIST_PATH}/${REFERENCE_TYPE_ADD_SENTINEL}`],
  ]);
}

/**
 * Asserts every destination this menu dispatches to is administratively gated, and nothing else is.
 *
 * ⚠️ Assumptions: the closure is checked with react-router's OWN matcher rather than by comparing
 * strings, because three of the six gated rows are patterns -- a concrete address such as
 * `/reference/transaction-types/new` is gated by the pattern that matches it, and a string comparison
 * would report that as ungated. This is the property that makes "the six administrative options are the
 * complete set of admin-gated flows" checkable: an option pointed at an ungated path fails here, and so
 * does a gated path no option reaches, because the reverse direction is asserted too.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function dispatchesOnlyToAdministrativelyGatedPaths(): void {
  expect(ADMINISTRATIVE_PATHS).toHaveLength(ADMIN_MENU_OPTION_COUNT + 1);

  for (const [optionNumber, destination] of declaredDestinations()) {
    expect(destination).not.toBeNull();
    const gatedBy = ADMINISTRATIVE_PATHS.filter(
      /**
       * Reports whether one gated pattern matches this option's destination.
       * @param {string} pattern - One administratively gated route pattern.
       * @returns {boolean} `true` when the pattern matches the destination address.
       */
      (pattern: string): boolean => matchPath(pattern, destination ?? '') !== null,
    );
    expect({ optionNumber, gated: gatedBy.length }).toEqual({ optionNumber, gated: 1 });
  }

  // WHY : Refactoring Rationale: this assertion was inverted. It previously required the menu itself to
  //       be `authenticated` rather than `administrative`, and defended that asymmetry as deliberate.
  //       The reference refutes it: `app/cbl/COSGN00C.cbl` L230-L240 transfers control to `COADM01C`
  //       only for an operator whose `SEC-USR-TYPE` is `'A'`, so the menu is as administrative as the
  //       six options it lists, and the arity above is those six plus the menu that lists them. Leaving
  //       the menu on the ordinary class rendered its complete option list -- and a live option
  //       dispatcher -- to an ordinary operator, deferring the refusal to whichever screen they picked.
  //       Assumptions: `ADMINISTRATIVE_PATHS` is derived from the router's own table, so this pins the
  //       shipped access class rather than a restatement of it.
  expect(ADMINISTRATIVE_PATHS).toContain(ADMIN_MENU_PATH);
}

/**
 * Asserts an administrator dispatching an option from this menu reaches the guarded screen.
 *
 * Assumptions: the SHIPPED route objects are mounted, so the guard in the assertion is the one the
 * application deploys. Option 1 is chosen because `app/cpy/COADM02Y.cpy` L28 gives it `COUSR00C`, whose
 * route needs no selection, so the arrival is unambiguous.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function letsAnAdministratorEnterAGatedOption(): Promise<void> {
  const { user, addressNow } = await openShippedRoutesAs([CARDDEMO_ADMIN_GROUP]);
  expect(await screen.findByText(ADMIN_MENU_SUBTITLE)).toBeInTheDocument();

  await submitOption(user, '1');

  expect(addressNow()).toBe(USER_LIST_PATH);
  // WHY : Alternatives Considered: asserting the address alone. Rejected because a guard that
  //       rendered its refusal here would ALSO have changed the address, so the address cannot tell
  //       admittance from refusal; the sentence has to be asserted absent as well.
  expect(screen.queryByText(ACCESS_DENIED_ADMIN_ONLY.trim())).toBeNull();
}

/**
 * Asserts an ordinary operator dispatching the same option is refused, in the baseline's own words.
 *
 * ⚠️ Assumptions: the ordinary operator DOES reach the menu, and that is `ui/src/router.tsx`'s recorded
 * decision rather than a hole. The menu's row is `authenticated`, so a signed-on non-administrator can
 * render a list of six static labels; every option it dispatches to is one of the six gated paths, so
 * each destination refuses them in turn. The reference is stricter -- `app/cbl/COSGN00C.cbl` L230-L240
 * never puts `COADM01C` in front of a `'U'` operator -- and this case asserts the residual the target
 * accepts instead of pretending it away.
 *
 * ⚠️ Assumptions: the sentence is taken from the catalog and is trimmed for the QUERY ONLY, never for
 * the comparison. It is `'No access - Admin Only option... '` and the trailing space is part of the
 * value because the field it comes from is fixed width. Testing Library's default normaliser trims the
 * boundary whitespace markup introduces, so the query has to be given the trimmed form -- but the
 * element's own text is then asserted against the UNTRIMMED catalog value, which is what proves the
 * refusal surface renders the padding verbatim. That measurement is the reason the two surfaces on this
 * screen are asserted differently: the guard's result renders its subtitle as given, while
 * `ui/src/layout/MessageBand.tsx` deliberately passes its text through the catalog's own
 * `normaliseMessageBandValue` so its emptiness test and its renderer cannot disagree about what "empty"
 * means.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function refusesAnOrdinaryOperatorTheSameOption(): Promise<void> {
  const { addressNow } = await openShippedRoutesAs([CARDDEMO_USER_GROUP]);

  const refusal = await screen.findByText(ACCESS_DENIED_ADMIN_ONLY.trim());

  // WHY : Refactoring Rationale: this case used to render the menu to an ordinary operator, submit an
  //       option, and assert the refusal at the option's DESTINATION. That sequence only existed
  //       because the menu route was gated as `authenticated`, so the operator saw the whole option
  //       list first and met the refusal one screen later. The menu is now `administrative`, which is
  //       what `app/cbl/COSGN00C.cbl` L230-L240 describes, so the refusal lands on ARRIVAL and there is
  //       no option field to submit into. Asserting arrival is the stronger property: it proves no
  //       administrative content reached the operator at all.
  expect(screen.queryByText(ADMIN_MENU_SUBTITLE)).toBeNull();

  // WHY : Assumptions: the address is the ADMIN MENU, not an option's destination, so this refusal is
  //       the guard rendering in place on the route that was actually requested. That is what
  //       distinguishes a gated route from a dead one; the two would otherwise look identical.
  expect(addressNow()).toBe(ADMIN_MENU_PATH);
  expect(ACCESS_DENIED_ADMIN_ONLY.endsWith(' ')).toBe(true);
  expect(refusal.textContent).toBe(ACCESS_DENIED_ADMIN_ONLY);
}

/**
 * Asserts the client is given no lever on its own authority.
 *
 * ⚠️⚠️ Refactoring Rationale: this is the migration's single largest security change on this screen and
 * the reason it is asserted rather than assumed. In the baseline the operator's authority travelled in
 * the communication area: `CDEMO-USER-TYPE PIC X(01)` at `app/cpy/COCOM01Y.cpy` L26, with its `88`
 * values `'A'` at L27 and `'U'` at L28, is storage the TERMINAL echoed back on every turn, so a client
 * could in principle assert its own user type. In the target the `cognito:groups` claim is SIGNED and
 * the client cannot assert anything -- AAP section 0.7.1 records this as a genuine security improvement
 * rather than a port -- and the `'A'`/`'U'` domain maps onto the two group names imported here as
 * constants.
 *
 * ⚠️ Assumptions: a test cannot grant itself administrative authority either, and the assertion below is
 * what keeps that true. `ui/src/hooks/useAuth.ts` holds its session in module scope behind
 * `useSyncExternalStore` with NO React context provider anywhere in the application, and it publishes no
 * setter for the groups, the user type or the derived administrative flag -- so the only lever is the
 * token, which is why every case here establishes its session through the identity helper that mints one
 * and drives the real sign-on. Enumerating the published functions is the falsifiable form of "no
 * setter": adding one would fail this case by name.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function publishesNoLeverOnItsOwnAuthority(): Promise<void> {
  const ordinary = await seedSession({ groups: [CARDDEMO_USER_GROUP] });

  expect(ordinary.result.current.groups).toEqual([CARDDEMO_USER_GROUP]);
  expect(ordinary.result.current.isAdmin).toBe(false);

  const publishedFunctions = Object.entries(ordinary.result.current)
    .filter(
      /**
       * Selects the published members that are callable.
       * @param {readonly [string, unknown]} entry - One member of the hook's published reading.
       * @returns {boolean} `true` when the member is a function.
       */
      (entry: readonly [string, unknown]): boolean => typeof entry[1] === 'function',
    )
    .map(
      /**
       * Reduces a member entry to its name.
       * @param {readonly [string, unknown]} entry - One callable member of the reading.
       * @returns {string} The member's name.
       */
      (entry: readonly [string, unknown]): string => entry[0],
    )
    .sort();

  expect(publishedFunctions).toEqual(['answerChallenge', 'signIn', 'signOut']);
}

/**
 * Asserts the row-24 legend paints exactly the two descriptors the mapset declares.
 *
 * ⚠️ Assumptions: the mapset's `INITIAL='ENTER=Continue  F3=Exit'` at `app/bms/COADM01.bms` L162 is
 * checked WITHOUT retyping it, by arithmetic against the field's own `LENGTH=23` at L160: the two
 * descriptors the screen publishes, joined by the two-space separator the field uses, must occupy
 * exactly 23 characters. Any drift in either label breaks the sum, so the check is as strict as a
 * string comparison while leaving the sentence single-sourced.
 *
 * Assumptions: "and no more" is asserted from the other side as well, with `UNIFORM_PF_KEY_LABELS.PFK04`
 * -- a descriptor other mapsets do paint. `app/cbl/COADM01C.cbl` L97-L107 admits `DFHENTER` and
 * `DFHPF3` only, so a fourth-function-key control here would be behaviour the reference does not have.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function paintsExactlyTheTwoLegendDescriptors(): Promise<void> {
  await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  expect(legendDescriptors()).toEqual([ADMIN_MENU_KEY_LABELS.ENTER, ADMIN_MENU_KEY_LABELS.PFK03]);
  expect(legendDescriptors().join(LEGEND_DESCRIPTOR_SEPARATOR).length).toBe(LEGEND_FIELD_WIDTH);
  expect(legendDescriptors()).not.toContain(UNIFORM_PF_KEY_LABELS.PFK04);
}

/**
 * Asserts Enter enters the typed option from a real key press and from the submit control alike.
 *
 * ⚠️ Alternatives Considered: asserting only the control's click, which is the cheaper of the two.
 * Rejected because the contract is a KEYBOARD contract -- the 3270 had no pointer, so `DFHENTER` at
 * `app/cbl/COADM01C.cbl` L98 is a key -- and a click-only case stays green while the key binding is
 * broken. Both paths are driven and the arrivals compared, so the case fails if either regresses or if
 * the two ever diverge.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function entersTheOptionFromKeyAndControlAlike(): Promise<void> {
  const keyboard = await renderAmongProbesAs([CARDDEMO_ADMIN_GROUP]);
  await submitOption(keyboard.user, '1');
  const arrivedByKey = arrivedAt();
  keyboard.unmount();

  const pointer = await renderAmongProbesAs([CARDDEMO_ADMIN_GROUP]);
  await pointer.user.type(optionControl(), '1');
  await pointer.user.click(screen.getByRole('button', { name: ADMIN_MENU_KEY_LABELS.ENTER }));
  const arrivedByControl = arrivedAt();

  expect(arrivedByKey).toBe(USER_LIST_PATH);
  expect(arrivedByControl).toBe(arrivedByKey);
}

/**
 * Asserts PF3 signs off from a real key press and from the legend control alike.
 *
 * Assumptions: signing off means BOTH a route change and a discarded session, because
 * `app/cbl/COADM01C.cbl` L100-L102 moves `'COSGN00C'` into `CDEMO-TO-PROGRAM` and
 * `RETURN-TO-SIGNON-SCREEN` at L163-L170 transfers there with NO `COMMAREA` clause -- so the next
 * program starts with no identity at all. The route is observed in the probe tree, where an address is
 * visible, and the session in the shell tree, where the legend control exists to be clicked; the two
 * halves together cover both paths and both outcomes.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function signsOffOnPfThreeFromKeyAndControlAlike(): Promise<void> {
  const keyboard = await renderAmongProbesAs([CARDDEMO_ADMIN_GROUP]);
  await pressPfKey(keyboard.user, 'PFK03');
  expect(arrivedAt()).toBe(SIGN_ON_PATH);
  keyboard.unmount();

  /*
   * WHY : ⚠️ Assumptions: this half emits a `No routes matched location "/signon"` notice on the console
   *       and the notice is EXPECTED rather than a defect being tolerated. The shell helper mounts the
   *       screen under its own route and nothing else, so the sign-on route this dispatch navigates to
   *       has no element in that one-route tree. Trade-offs: the honest composition is kept and the
   *       notice accepted. Mounting the screen under a wildcard would silence it, and
   *       `ui/src/test/setup.ts` records why that is worse -- a splat is not the route the application
   *       matches, so relative resolution inside the subject would differ from production. The route
   *       change itself is already proved by the keyboard half above, where a probe exists to observe
   *       it; what this half exists to prove is that the LEGEND CONTROL reaches the same dispatch, and
   *       the discarded session is the outcome that is observable without a second route.
   */
  const pointer = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);
  expect(pointer.isSignedOn()).toBe(true);
  await dispatchByLegendControl(pointer.user, ADMIN_MENU_KEY_LABELS.PFK03);
  expect(pointer.isSignedOn()).toBe(false);
}

/**
 * Asserts an unmapped attention key is reported with the reference's own sentence.
 *
 * ⚠️ Alternatives Considered: retyping the sentence into this file. Rejected because a paraphrase in the
 * screen and the same paraphrase here would AGREE -- the case would pass while fidelity was lost -- so
 * the value is taken from the catalog, whose entry cites `app/cpy/CSMSG01Y.cpy` L21 and carries the
 * `PIC X(50)` declared width the copybook gives it at L20.
 *
 * Assumptions: `PFK04` is the representative unmapped key because it is a key other mapsets genuinely
 * bind, so it is the realistic mistake rather than an invented one. `app/cbl/COADM01C.cbl` L103-L106 is
 * its `WHEN OTHER` arm, which moves `CCDA-MSG-INVALID-KEY` into the message field at L105 and re-sends
 * the map -- the screen stays put, which is why the option control is asserted still present. The
 * PF13-PF24 aliasing and the deliberately keyless `CLEAR`, `PA1` and `PA2` are `usePfKeys`'s own
 * contract and are asserted where that hook is tested, not here.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function reportsAnUnmappedKeyWithTheReferencesSentence(): Promise<void> {
  const { user } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  await pressPfKey(user, 'PFK04');

  /*
   * WHY : Assumptions: the catalog entry keeps its `PIC X(50)` padding, which is asserted here and NOT
   *       against the DOM. `app/cpy/CSMSG01Y.cpy` L20 declares the field 50 characters wide and its
   *       literal at L21 is 49, so the stored value ends in spaces -- and that is where Transformation
   *       Rule T8's character-for-character guarantee lives.
   * WHY : ⚠️ Assumptions: the DOM carries the NORMALISED form, and the difference is a published
   *       contract rather than a loss. `ui/src/layout/MessageBand.tsx` passes its text through the
   *       catalog's own `normaliseMessageBandValue`, which strips the `LOW-VALUES` sentinel and trims,
   *       precisely so its emptiness test and its renderer cannot disagree about what "empty" means --
   *       an all-spaces field is the reference's own off-condition. Asserting the DOM against that same
   *       published function is therefore byte-exact in both directions, where a hand-written `.trim()`
   *       here would be this file's guess at what the band does.
   */
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.text).toBe(INVALID_KEY_PRESSED);
  expect(INVALID_KEY_PRESSED.endsWith(' ')).toBe(true);
  expect(messageBand().textContent).toBe(normaliseMessageBandValue(INVALID_KEY_PRESSED));
  expect(optionControl()).toBeInTheDocument();
}

/**
 * Asserts an out-of-range, zero or blank entry is refused with the reference's sentence and echoed back.
 *
 * Assumptions: the three refusals are one branch, not three. `app/cbl/COADM01C.cbl` L131-L133 tests not
 * numeric, greater than `CDEMO-ADMIN-OPT-COUNT`, and equal to zeros in one condition and answers all of
 * them with the single sentence at L135, so the case covers each arm of that condition rather than
 * inventing a message per arm.
 *
 * Assumptions: the echo is part of the contract. L129 moves the `PIC 9(02)` value into the map's output
 * field BEFORE the refusal test at L131, so a terminal operator who typed `7` sees `07` on the turn that
 * follows, and an untouched field reads as `'00'` because `INSPECT ... REPLACING ALL ' ' BY '0'` at L127
 * fills it -- which is the value L133's zero test then refuses.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function refusesEveryEntryOutsideTheOptionDomain(): Promise<void> {
  const { user } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  // WHY : Alternatives Considered: an arbitrary large entry such as `99`. Rejected because one past
  //       the live `CDEMO-ADMIN-OPT-COUNT` of six at `app/cpy/COADM02Y.cpy` L22 is the boundary the
  //       count actually decides, and `99` would still pass against a count of seven.
  await submitOption(user, String(ADMIN_MENU_OPTION_COUNT + 1));
  expect(
    expectVerbatimMessage(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
  expect(optionControl()).toHaveValue('07');

  await submitOption(user, '0');
  expect(
    expectVerbatimMessage(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
  expect(optionControl()).toHaveValue('00');

  await submitOption(user, '');
  expect(
    expectVerbatimMessage(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
  expect(optionControl()).toHaveValue('00');
}

/**
 * Asserts the screen's whole error channel is the row-23 band, with no field-level state.
 *
 * ⚠️ Refactoring Rationale: the templated field highlight this repository carries for other screens does
 * NOT apply here, and the absence is measured rather than assumed. `app/cpy/CSSETATY.cpy` moves `DFHRED`
 * into a field's colour subfield and the literal `'*'` into its output subfield at L24, and exactly one
 * program in the baseline `COPY`s it -- `app/cbl/COACTUPC.cbl`. `COADM01C`'s own `COPY` list at L50-L61
 * does not, and its mapset declares one message field, `ERRMSG` at `app/bms/COADM01.bms` L154. So the
 * refusal belongs in the band and nowhere else, and this case asserts the absence of the field-level
 * state and of the blank marker precisely so that adding either without a source would fail.
 *
 * ⚠️ Refactoring Rationale: the reference additionally gates that highlight on `CDEMO-PGM-REENTER`
 * (`app/cpy/COCOM01Y.cpy` L29-L31, tested at `app/cpy/CSSETATY.cpy` L20), and AAP section 0.7.1
 * establishes that the re-entry discriminator disappears ENTIRELY in the target: a stateless handler has
 * no first-entry-versus-re-entry distinction to make, so what is displayed follows from the last action
 * alone. There is therefore no re-entry flag for this case to arrange or assert, and the message appears
 * on the first refused turn rather than on the second.
 *
 * Assumptions: no colour is written here and none is read. `FIELD_ERROR_TOKENS.errorColor` is a token
 * NAME rather than a value, so the check that the control carries no literal colour is expressed as the
 * absence of a hexadecimal or `rgb()` value in its inline style -- which is AAP section 0.3.2's
 * zero-hardcoded-values rule stated as an assertion.
 * @returns {Promise<void>} Resolves once the case has asserted.
 */
async function keepsItsWholeErrorChannelInTheMessageBand(): Promise<void> {
  const { user } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  await submitOption(user, String(ADMIN_MENU_OPTION_COUNT + 1));

  const band = messageBand();
  expect(band).toHaveTextContent(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
  expect(within(band).getByRole('alert')).toBeInTheDocument();

  const control = optionControl();
  expect(control).not.toHaveAttribute('aria-invalid');
  expect(control.className).not.toContain('status-error');
  // WHY : Alternatives Considered: a retyped asterisk. Rejected because the blank marker is the one
  //       piece of `CSSETATY` a reader might expect to find here, so its absence is asserted through
  //       the token module's own constant and cannot drift from what that module publishes.
  expect(screen.queryByText(FIELD_ERROR_TOKENS.blankMarker)).toBeNull();
  expect(typeof FIELD_ERROR_TOKENS.errorColor).toBe('string');

  const inlineStyle = control.getAttribute('style') ?? '';
  expect(inlineStyle).not.toContain('#');
  expect(inlineStyle).not.toContain('rgb(');
  expect(control.style.color).toBe('');
}

/**
 * Registers every case in this file, in the order the reference is read: field, options, destinations,
 * authority, keys, then messages.
 *
 * Assumptions: one flat suite rather than nested groups, matching the sibling screen suites in this
 * package, so a reported failure names the behaviour directly instead of a path through group titles.
 * @returns {void} Nothing; registration is the effect.
 */
function adminMenuScreenCases(): void {
  it(
    'offers the mapset\u2019s one unprotected field and no selectable option line',
    offersTheMapsetsOneUnprotectedField,
  );
  it(
    'holds the option field to the width the mapset and its symbolic map both declare',
    holdsTheOptionFieldToItsDeclaredWidth,
  );
  it(
    'refuses every non-digit in the numeric-only option field',
    refusesEveryNonDigitInTheNumericField,
  );
  it('places the arrival cursor in the option field', placesTheArrivalCursorInTheOptionField);
  it(
    'counts six administrative options, not the four the copybook superseded',
    countsSixAdministrativeOptions,
  );
  it(
    'carries no user type on an administrative option, unlike the main menu',
    carriesNoUserTypeOnAnAdministrativeOption,
  );
  it(
    'paints all six option lines in order, verbatim from the catalog',
    paintsEverySixOptionLineInOrder,
  );
  it('enters the route every administrative option names', entersTheRouteEveryOptionNames);
  it(
    'pins every destination to the router\u2019s own path constants',
    pinsEveryDestinationToTheRoutersOwnConstants,
  );
  it('dispatches only to administratively gated paths', dispatchesOnlyToAdministrativelyGatedPaths);
  it(
    'lets an administrator enter a gated option from the menu',
    letsAnAdministratorEnterAGatedOption,
  );
  it('refuses an ordinary operator the same option', refusesAnOrdinaryOperatorTheSameOption);
  it('publishes no lever on its own authority', publishesNoLeverOnItsOwnAuthority);
  it(
    'paints exactly the two legend descriptors the mapset declares',
    paintsExactlyTheTwoLegendDescriptors,
  );
  it(
    'enters the option from a real key and from the submit control alike',
    entersTheOptionFromKeyAndControlAlike,
  );
  it(
    'signs off on PF3 from a real key and from the legend control alike',
    signsOffOnPfThreeFromKeyAndControlAlike,
  );
  it(
    'reports an unmapped key with the reference\u2019s own sentence',
    reportsAnUnmappedKeyWithTheReferencesSentence,
  );
  it('refuses every entry outside the option domain', refusesEveryEntryOutsideTheOptionDomain);
  it(
    'keeps its whole error channel in the row-23 message band',
    keepsItsWholeErrorChannelInTheMessageBand,
  );
}

/**
 * Asserts the option row can wrap while the prompt and the field keep their declared measures.
 *
 * Purpose: the regression guard for the row that could not wrap. Browser measurement of the rendered
 * screen recorded it surviving a 375 px viewport by 6.52 px, reaching exactly 0.00 px of clearance at
 * 360 and, below that, pushing the submit control out of the viewport while squeezing the two-character
 * field to 24.00 px.
 *
 * ⚠️ Assumptions: this asserts the three STYLE properties that decide the outcome rather than measuring
 * a rendered width, and that is a property of the runner rather than a weaker test. `jsdom` performs no
 * layout, so every rectangle is zero and no flex line is ever computed; a width assertion here would
 * pass whatever the styles said. The three are jointly sufficient and individually necessary: the row
 * must permit wrapping, or the trailing control leaves the viewport; the prompt must refuse to break, or
 * the unbroken 25-column field of `app/bms/COADM01.bms` L140-L144 renders on two lines; and the field
 * must refuse to shrink, or the row's overflow is taken out of the two columns
 * `app/bms/COADM01.bms` L145-L149 declares before it is taken out of anything else.
 *
 * Assumptions: the row is reached from the field rather than by a test identifier, because it is a
 * layout element with no name of its own and adding one would put a hook in the screen that only this
 * case uses.
 * @returns {Promise<void>} Resolves once the three properties have been asserted.
 */
async function letsTheOptionRowWrapWithoutBreakingThePromptOrTheField(): Promise<void> {
  const { unmount } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  const field = optionControl();
  const prompt = screen.getByText(ADMIN_MENU_PROMPT);
  const row = field.parentElement;
  expect(row, 'the option field must sit inside a row element').not.toBeNull();

  /*
   * WHY : ⚠️ Assumptions: the row's wrapping is asserted through the design system's own CLASS and NOT
   *       through an inline style, because that is where the library puts it. `genClsWrap` in
   *       `node_modules/antd/es/flex/utils.js` emits `${prefixCls}-wrap-${wrap}` and maps `true` onto
   *       the keyword `wrap`, while `Flex` copies only `flex` and `gap` into the style object
   *       (`node_modules/antd/es/flex/index.js` L42-L56) -- so an unwrapping row carries NO class and NO
   *       inline value and falls back to the CSS initial `nowrap` with nothing on the element to read.
   *       An inline-style assertion therefore passes against the defect, which was measured on the twin
   *       before this form was adopted.
   */
  expect(
    (row as HTMLElement).className,
    'the option row must permit wrapping so the submit control drops instead of leaving the viewport',
  ).toContain('ant-flex-wrap-wrap');
  expect(
    prompt.style.whiteSpace,
    'the 25-column prompt must stay on the one line the mapset paints it on',
  ).toBe('nowrap');
  expect(
    field.style.flexShrink,
    'the option field must keep its declared two columns rather than absorbing the row overflow',
  ).toBe('0');

  unmount();
}

/**
 * Asserts the screen's own submit control announces exactly the catalogued label.
 *
 * Purpose: the regression guard for the decorative glyph that distinguishes this control from the
 * identically labelled legend entry. `ui/src/layout/PfKeyBar.tsx` renders each action key as a real
 * primary `Button`, so without the glyph the screen showed two pixel-identical primary controls about
 * 330 px apart -- and with an UNHIDDEN glyph the control announced two things.
 *
 * ⚠️ Assumptions: every `@ant-design/icons` export renders `role="img"` with `aria-label` set to the
 * icon's own name -- `node_modules/@ant-design/icons/es/components/AntdIcon.js` L48-L50 -- so an
 * unhidden glyph CONTRIBUTES that name to the button's. Omitting `aria-hidden` was measured failing
 * eighteen cases across this screen's suites in one run, every one of them a query that names the
 * control by its label, so this case fails on the name rather than on a click that no longer resolves.
 *
 * Assumptions: the control is separated from the legend entry by landmark membership rather than by
 * document order, because the shared label is deliberate and a DOM-order query would survive a layout
 * change that inverted the two.
 * @returns {Promise<void>} Resolves once the name has been asserted.
 */
async function announcesOnlyTheCataloguedLabelOnItsOwnControl(): Promise<void> {
  const { unmount } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const named = screen.getAllByRole('button', { name: ADMIN_MENU_KEY_LABELS.ENTER });
  const own = named.find(
    /**
     * Keeps the candidate that is not part of the legend region.
     * @param {HTMLElement} candidate - One button carrying the shared label.
     * @returns {boolean} True when the button sits outside the legend.
     */
    (candidate) => !legend.contains(candidate),
  );
  expect(own, 'the screen must offer its own submit control outside the legend').toBeDefined();
  expect(
    (own as HTMLElement).textContent,
    'the glyph must not join the label the control announces',
  ).toBe(ADMIN_MENU_KEY_LABELS.ENTER);

  unmount();
}

/**
 * Asserts the two ENTER controls share one name and are still told apart by a screen reader.
 *
 * ⚠️ Purpose: this case is NEW and it is the guard on the keep-both decision recorded at the call site.
 * Keeping two controls for one action is defensible -- `app/bms/COADM01.bms` L158-L162 declares the
 * row-24 legend `ATTRB=(ASKIP,NORM)`, a protected literal, so the terminal had NO operable `ENTER`
 * control and both browser controls are additions -- but only while the two do not present as one
 * control announced twice. The case above proves the glyph stays out of the NAME; this one proves that a
 * screen reader moving through the two tab stops hears a difference, which nothing measured before.
 *
 * ⚠️ Assumptions: the shared NAME is asserted rather than a difference in it. WCAG 2.2 SC 3.2.4 asks one
 * name for one function and these two invoke the identical function, so a case demanding different names
 * would demand a violation. The difference lives in the DESCRIPTION instead.
 *
 * Assumptions: the description asserted is the catalogued option prompt, already on the glass labelling
 * the field, so Rule T8 is satisfied without inventing a second string for this screen.
 * @returns {Promise<void>} Resolves once both controls have been inspected.
 */
async function tellsTheTwoEnterControlsApartWithoutRenamingEither(): Promise<void> {
  const { unmount } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  const label = ADMIN_MENU_KEY_LABELS.ENTER;
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const named = screen.getAllByRole('button', { name: label });

  expect(named, 'the screen paints exactly two controls carrying the shared label').toHaveLength(2);

  const own = named.find(
    /**
     * Keeps the candidate outside the legend region.
     * @param {HTMLElement} candidate - One button carrying the shared label.
     * @returns {boolean} True when the button sits outside the legend.
     */
    (candidate) => !legend.contains(candidate),
  );
  const inLegend = named.find(
    /**
     * Keeps the candidate inside the legend region.
     * @param {HTMLElement} candidate - One button carrying the shared label.
     * @returns {boolean} True when the button sits inside the legend.
     */
    (candidate) => legend.contains(candidate),
  );
  expect(own, 'the screen must offer its own submit control outside the legend').toBeDefined();
  expect(inLegend, 'the legend must offer the same key').toBeDefined();

  /*
   * WHY : Assumptions: the description is resolved through the referenced element's own text rather than
   *       compared as an attribute value, because that is what an assistive technology does -- an
   *       `aria-describedby` naming an element that does not exist reads as no description at all and
   *       would otherwise pass an attribute comparison.
   */
  const describedBy = (own as HTMLElement).getAttribute('aria-describedby') ?? '';
  const description = describedBy === '' ? null : document.getElementById(describedBy);
  expect(
    description,
    'the screen control must be described by an element that exists',
  ).not.toBeNull();
  expect(
    (description as HTMLElement).textContent,
    'the screen control must be described by the catalogued option prompt',
  ).toBe(ADMIN_MENU_PROMPT);

  expect(
    (inLegend as HTMLElement).getAttribute('aria-describedby'),
    'the legend copy must carry no description, so the two do not read alike',
  ).toBeNull();
  expect(
    (inLegend as HTMLElement).getAttribute('aria-keyshortcuts'),
    'the legend copy must announce the key it stands for',
  ).not.toBeNull();

  /*
   * WHY : Assumptions: the glyph is asserted ABSENT from the accessibility tree rather than asserted to
   *       carry `aria-hidden`, because absence is the property that matters and it holds however the
   *       hiding is expressed. `@ant-design/icons` renders `role="img"` with the icon's own name as its
   *       label, so a queryable image inside this control is exactly the second announcement the
   *       decorative glyph must not make.
   */
  expect(
    within(own as HTMLElement).queryByRole('img'),
    'the decorative glyph must not appear in the accessibility tree',
  ).toBeNull();

  unmount();
}

/**
 * Name the user-update screen retains its save outcome under, composed the way all three sites compose it.
 *
 * Assumptions: the claim is COMPOSED here from the same shared route template the screens compose it
 * from, rather than copied as a literal, because that is the only half of the name the three call sites
 * can be held to. A literal here would go on agreeing with itself after the route moved, which is the
 * one drift this case exists to catch.
 */
const USER_UPDATE_SAVE_CLAIM = `${USER_UPDATE_ROUTE_TEMPLATE}#saved`;

/**
 * The sentence a committed user update publishes, composed from the catalog rather than written out.
 *
 * Assumptions: `app/cbl/COUSR02C.cbl` L372-L374 composes it with a `STRING ... DELIMITED BY SPACE` over
 * the identifier, which is what the catalog template models. Writing the composed form as a literal here
 * would let this case pass while the catalog's own spelling changed.
 * @returns {string} The committed-write sentence for the operator these cases hand over.
 */
function committedUpdateSentence(): string {
  return formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
    'SEC-USR-ID': 'USER0100',
  });
}

/**
 * Retains a completed save outcome under the claim the update screen uses.
 *
 * Assumptions: the retention goes through the real registry rather than a stub, because the property
 * under test is that THIS screen collects what THAT registry holds -- a stubbed store would prove only
 * that the screen calls a function this case wrote.
 *
 * Assumptions: it is wrapped in `act`, because `retainOutcomeAcrossNavigation` tells its listeners
 * synchronously and a mounted collector sets state from inside that call. Outside `act` the update is
 * reported as an unwrapped one, which `ui/src/test/setup.ts` turns into a failure naming React rather
 * than naming this hand-over.
 * @param {string} message - The sentence to hand over.
 * @returns {void} Nothing; the outcome is held by the registry until it is collected.
 */
function handOverCompletedSave(message: string): void {
  act(
    /**
     * Retains the outcome inside an act scope, so the collector's own state update is flushed.
     * @returns {void} Nothing; the registry holds the outcome until it is collected.
     */
    () => {
      retainOutcomeAcrossNavigation<UserUpdateSaveHandover>(USER_UPDATE_SAVE_CLAIM, {
        settled: 'COMPLETED',
        value: { message, severity: 'success' },
      });
    },
  );
}

/**
 * Signs on, hands over a completed save, and only then renders the menu.
 *
 * ⚠️ Assumptions: the three steps are in this order because a hand-over cannot cross a session
 * boundary. `ui/src/hooks/useAuth.ts` L782 discards every retained outcome from inside
 * `discardSession`, on the recorded ground that such an outcome describes work done under the session
 * that is ending -- so a retention established before the session was seeded is thrown away by the
 * seeding itself and the case would measure the collector against an empty store. The retention has to
 * sit inside the session that produced it, which is also the only order an operator can produce.
 * @param {string} message - The sentence to hand over before the menu mounts.
 * @returns {Promise<{ unmount: () => void }>} The teardown for the rendered menu.
 * @throws {Error} If the sign-on exchange did not establish a session, which `seedSession` reports.
 */
async function renderAfterHandOver(message: string): Promise<{ unmount: () => void }> {
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });
  handOverCompletedSave(message);

  const { unmount } = await renderInAppShell(<AdminMenuScreen />, {
    initialEntries: [ADMIN_MENU_PATH],
    routePath: ADMIN_MENU_PATH,
  });
  return { unmount };
}

/**
 * Asserts a save outcome retained BEFORE this menu mounts is painted in its row-23 band.
 *
 * Purpose: the regression guard for the silent save. `app/cbl/COUSR02C.cbl` L113-L118 falls back to
 * `'COADM01C'` when no calling program is named, so an administrator who opens user maintenance by its
 * address exits to this menu -- and until this screen collected the hand-over, the write committed and
 * the band it landed on painted nothing, which is indistinguishable from a save that never happened.
 *
 * Assumptions: this arm covers the retention that settles while the route is still changing, so the
 * collector's opening `collect(CLAIM)` call is what has to answer it. The case below covers the other
 * order, which only the subscription can answer.
 * @returns {Promise<void>} Resolves once the sentence has been asserted in the band.
 */
async function paintsASaveOutcomeRetainedBeforeArrival(): Promise<void> {
  const committed = committedUpdateSentence();
  const { unmount } = await renderAfterHandOver(committed);

  const band = messageBand();
  expect(band, 'the menu must paint the sentence the update screen handed over').toHaveTextContent(
    committed,
  );
  /*
   * WHY : Assumptions: the SEVERITY is asserted through the band's role and not through a colour,
   *       because `ui/src/layout/MessageBand.tsx` resolves the role from the severity it was given and
   *       a `success` sentence is a status rather than an alert. A collector that dropped the handed-over
   *       tone and left this screen's own `'error'` default would paint the committed sentence as an
   *       alert, which is the reference painting `DFHGREEN` text in red.
   */
  expect(within(band).getByRole('status')).toBeInTheDocument();
  expect(within(band).queryByRole('alert')).toBeNull();

  unmount();
  discardRetainedOutcomes();
}

/**
 * Asserts a save outcome that lands AFTER this menu is mounted is still painted.
 *
 * Purpose: `ui/src/api/client.ts` records the measured writes settling 7 to 12 ms after the navigation,
 * so this is the ordinary order rather than the exotic one -- the menu is already mounted when the
 * outcome comes to exist. A collector that only looked on mount would look too early and this case is
 * what reports that.
 * @returns {Promise<void>} Resolves once the sentence has been asserted in the band.
 */
async function paintsASaveOutcomeThatLandsAfterArrival(): Promise<void> {
  const { unmount } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  expect(
    normaliseMessageBandValue(messageBand().textContent),
    'the band must be empty before anything is handed over',
  ).toBe('');

  const committed = committedUpdateSentence();
  handOverCompletedSave(committed);

  expect(messageBand()).toHaveTextContent(committed);

  unmount();
  discardRetainedOutcomes();
}

/**
 * Asserts the outcome is collected once, so a later arrival is not told about an earlier save.
 *
 * Purpose: collection REMOVES the claim, and that is what stops a durable hand-over being painted twice.
 * A sentence describing an earlier turn is indistinguishable from one describing this turn, so a stale
 * acknowledgement on an administrative record is worse than silence.
 * @returns {Promise<void>} Resolves once the second arrival has been asserted silent.
 */
async function collectsASaveOutcomeOnceOnly(): Promise<void> {
  const committed = committedUpdateSentence();
  const first = await renderAfterHandOver(committed);
  expect(messageBand()).toHaveTextContent(committed);
  first.unmount();

  /*
   * WHY : Assumptions: the second arrival is rendered WITHOUT seeding a second session, because seeding
   *       one would discard the store and the case would then pass whether or not collection removed the
   *       claim. Re-rendering inside the same session is what makes the emptiness below evidence about
   *       the collector rather than about the session boundary.
   */
  const second = await renderInAppShell(<AdminMenuScreen />, {
    initialEntries: [ADMIN_MENU_PATH],
    routePath: ADMIN_MENU_PATH,
  });
  expect(
    normaliseMessageBandValue(messageBand().textContent),
    'a second arrival must not be told about a save it was already told about',
  ).toBe('');

  second.unmount();
  discardRetainedOutcomes();
}

/**
 * Asserts this menu leaves another pair's hand-over alone.
 *
 * Purpose: `subscribeToRetainedOutcomes` tells every listener about every retention, so a collector that
 * took whatever had just been retained would paint a sentence about a record this screen never showed --
 * the authorization summary and the user browse both retain and collect through the same registry.
 * @returns {Promise<void>} Resolves once the band has been asserted silent.
 */
async function leavesAForeignHandOverUncollected(): Promise<void> {
  const { unmount } = await renderInShellAs([CARDDEMO_ADMIN_GROUP]);

  act(
    /**
     * Retains an outcome under a DIFFERENT claim, inside an act scope.
     *
     * Assumptions: the claim differs only in its fragment, so the case cannot pass by the collector
     * failing to recognise an obviously unrelated string -- it has to compare the whole claim.
     * @returns {void} Nothing; the registry holds an outcome this screen must not take.
     */
    () => {
      retainOutcomeAcrossNavigation<UserUpdateSaveHandover>(`${USER_UPDATE_ROUTE_TEMPLATE}#other`, {
        settled: 'COMPLETED',
        value: { message: committedUpdateSentence(), severity: 'success' },
      });
    },
  );

  expect(
    normaliseMessageBandValue(messageBand().textContent),
    'the menu must not paint an outcome retained under another claim',
  ).toBe('');

  unmount();
  discardRetainedOutcomes();
}

/** Registers the cases about the save outcome this menu collects for a screen that has left. */
function saveHandOverCases(): void {
  it(
    'paints a save outcome retained before the menu mounted',
    paintsASaveOutcomeRetainedBeforeArrival,
  );
  it(
    'paints a save outcome that lands after the menu mounted',
    paintsASaveOutcomeThatLandsAfterArrival,
  );
  it('collects a save outcome once only', collectsASaveOutcomeOnceOnly);
  it('leaves a hand-over retained under another claim alone', leavesAForeignHandOverUncollected);
}

/** Registers the cases about the option row's measure and the name its own control announces. */
function optionRowCases(): void {
  it(
    'lets the option row wrap without breaking the prompt or the field',
    letsTheOptionRowWrapWithoutBreakingThePromptOrTheField,
  );
  it(
    'announces only the catalogued label on its own control',
    announcesOnlyTheCataloguedLabelOnItsOwnControl,
  );
  it(
    'tells the two ENTER controls apart without renaming either',
    tellsTheTwoEnterControlsApartWithoutRenamingEither,
  );
}

describe('admin menu screen', adminMenuScreenCases);

describe('the option row holds its measure and its one name', optionRowCases);

describe('the administrative menu collects a save it did not perform', saveHandOverCases);
