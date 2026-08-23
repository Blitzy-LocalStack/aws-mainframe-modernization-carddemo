/**
 * @file Component test for the CardDemo main-menu screen at the `/menu` route -- the migration
 * target of BMS mapset `app/bms/COMEN01.bms` (map `COMEN1A`, `DFHMDI ... SIZE=(24,80)`), its
 * symbolic map `app/cpy-bms/COMEN01.CPY`, the option table `app/cpy/COMEN02Y.cpy` and the CICS
 * program `app/cbl/COMEN01C.cbl`. The subject is the single default-exported component of
 * `ui/src/screens/menu/index.tsx`.
 *
 * Purpose
 * -------
 * WHAT: hold the four contracts this screen carries across from the terminal -- the three attribute
 * contracts of its single unprotected option field, the eleven verbatim option lines with the route
 * each transfers to, the two attention identifiers the program dispatches on, and the verbatim
 * sentences it paints -- so that losing any of them fails here.
 *
 * ⚠️ This module is the ONLY automated verification this screen gets, and that is a property of the
 * runner rather than a choice. `tests/README.md` section 1.1 states it verbatim: "Online `CO*` CICS
 * programs cannot run end-to-end without a CICS runtime (absent on the runner); only their
 * extractable field-validation logic is unit-tested." That suite is the parity oracle for the BATCH
 * programs, so **no golden master exists for `COMEN01C` and none can be produced here**. Every
 * expectation below is therefore anchored either to a cited line of the reference source or to
 * `ui/src/messages/messages.ts`, which transcribes it -- never to a recorded run of this screen.
 *
 * Module parameters, returns and exceptions
 * ----------------------------------------
 * A test entry point takes no arguments and exports nothing, so Rule 1's Parameters, Returns and
 * Exceptions elements are answered at module scope rather than omitted. Its inputs are the frozen
 * option catalog, the published route table and the shared harness it imports; its return is the one
 * registered `describe` block; it raises nothing of its own -- an assertion failure is raised by the
 * runner, which is this module's intended and only failure channel.
 *
 * Documentation obligation, and why it is double-anchored
 * -----------------------------------------------------
 * Rule 1 (Explainability) requires a docstring on every function and module entry point stating
 * purpose, parameters and returns, and an inline comment giving the WHY behind every non-obvious
 * choice. `tests/README.md` section 12 imposes the identical obligation on "every new test, fixture
 * builder, helper, mock, and runner routine" and calls it "a hard review gate". The two agree
 * completely, so this file extends an established house convention rather than importing a new one,
 * and it conforms to `docs/CODE_DOCUMENTATION_STANDARD.md`. Every literal below that came from a
 * copybook, a mapset or a program carries an adjacent citation of that file and line, because an
 * uncited literal in a parity test is a magic number.
 *
 * Three authoring instructions were overridden by evidence, and each is recorded here so the
 * departure is auditable rather than silent
 * ----------------------------------------------------------------------------------------------
 * Refactoring Rationale: the brief for this file asked for the SINGULAR rationale labels
 * `Assumption:` and `Trade-off:`. This tree's own Rule 1 gate, `config/rule1/rule1_gate.py`, defines
 * the canonical set as `Assumptions`, `Trade-offs`, `Alternatives Considered` and
 * `Refactoring Rationale` and carries an explicit detector that REJECTS the singular stems; the gate
 * runs in `.github/workflows/services-ci.yml` and passes repository-wide today. Writing the singular
 * form would have introduced the tree's first label violation, so the plural forms are used
 * throughout.
 *
 * Refactoring Rationale: the brief also forbade importing `describe`, `it` and `expect` from
 * `vitest`, on the ground that `ui/vitest.config.ts` sets `globals: true`. That option governs the
 * RUNNER; the COMPILER is governed by `ui/tsconfig.json`, which sets `"types": []` and states in
 * prose that the empty list "is what obliges a test to import `describe`, `it` and `expect` from
 * 'vitest' by name -- with nothing declared ambiently, an omitted import fails to compile on the
 * symbol it omitted". That was verified rather than assumed: a probe file without the import
 * produced `TS2593: Cannot find name 'describe'` and `TS2304: Cannot find name 'expect'`, and all
 * other test modules in this package import the same three names. Since the brief also requires
 * `tsc --noEmit` to pass with zero errors, the two instructions cannot both be honoured; the
 * machine-checkable one wins.
 *
 * Refactoring Rationale: the brief tabulated options 4, 5 and 7 as transferring to `/cards/:num`,
 * `/cards/:num/edit` and `/transactions/:id`. They do not, and asserting that would have put the
 * literal text `:num` in the address bar. `ui/src/routes/programRoutes.ts` resolves all three to the
 * owning BROWSE, because a menu option carries no record selector and the published contract keys one
 * card by an opaque service-minted selector -- its registered `D-CARD-SELECTOR` divergence. The
 * assertions below therefore read the destination from `MAIN_MENU_DESTINATIONS`, the delivery's single
 * owner of that mapping, and additionally hold the invariant that actually matters: every one of the
 * eleven destinations is a concrete, selector-free address.
 */

import { screen, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';

import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { CICS_AIDS } from '../layout/usePfKeys';
import type { CicsAid } from '../layout/usePfKeys';
import {
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MAIN_MENU_HEADINGS,
  MAIN_MENU_KEY_LABELS,
  MAIN_MENU_OPTIONS,
  MAIN_MENU_OPTION_COUNT,
  MENU_OPTION_DECLARED_WIDTH,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../messages/messages';
import type { MainMenuOption } from '../messages/messages';
import { ROUTE_TABLE } from '../router';
import MainMenuScreen, {
  MAIN_MENU_DESTINATIONS,
  MAIN_MENU_OPTION_WIDTH,
  MAIN_MENU_PROGRAM_NAME,
} from '../screens/menu';
import {
  expectMaxLength,
  expectVerbatimMessage,
  pressPfKey,
  renderInAppShell,
  seedSession,
  shellLandmark,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * Declared width of the entry control, from `app/cpy-bms/COMEN01.CPY` L132 (`OPTIONI PIC X(2)`).
 *
 * Assumptions: the width is stated here as a literal with its citation and the screen's own constant
 * is then checked AGAINST it, rather than the screen's constant simply being reused for the
 * `maxLength` assertion. Reusing it would make the case tautological -- the field would be compared
 * with whatever the screen currently declares and would pass after an edit to either -- whereas this
 * arrangement fails if the screen ever stops agreeing with the symbolic map. `app/bms/COMEN01.bms`
 * L148 declares the same width as `LENGTH=2` on the `OPTION` field, so the mapset and its generated
 * copybook corroborate each other.
 */
const OPTION_FIELD_DECLARED_WIDTH = 2;

/**
 * Display width of the option number, from `app/cpy/COMEN02Y.cpy` L95 (`CDEMO-MENU-OPT-NUM PIC 9(02)`).
 *
 * Assumptions: this is deliberately a SECOND constant even though it also equals two. That one is the
 * capacity of the entry control; this is the rendered width of a display numeric that
 * `app/cbl/COMEN01C.cbl` L269 emits with `STRING ... DELIMITED BY SIZE`, which takes all of its
 * characters. Folding them together would tie an accepted width to a painted width and let a change
 * to either move the other unnoticed.
 */
const OPTION_NUMBER_DIGITS = 2;

/**
 * Reference program name of the sign-on screen, from `app/cbl/COMEN01C.cbl` L97 and L199.
 *
 * Assumptions: the name is taken from the program's own two `MOVE 'COSGN00C'` statements rather than
 * from the route table's ordering, because it is the PF3 arm's declared destination -- L97 sets it on
 * the function-key path and L199 defaults it -- and the route it maps to is then resolved through the
 * published table below.
 */
const SIGN_ON_PROGRAM_NAME = 'COSGN00C';

/**
 * Attention identifiers this program dispatches on, from `app/cbl/COMEN01C.cbl` L93-L103.
 *
 * Assumptions: exactly two, because the `EVALUATE EIBAID` there has precisely three arms --
 * `WHEN DFHENTER` at L94, `WHEN DFHPF3` at L96 and `WHEN OTHER` at L99 -- and the third reports an
 * error rather than acting. The row-24 legend at `app/bms/COMEN01.bms` L162 paints the same two and
 * no others, so the mapset and the program agree on the size of this set.
 */
const BOUND_AIDS: readonly CicsAid[] = ['ENTER', 'PFK03'];

/**
 * Identifiers that exist in the AID domain but have no browser key at all.
 *
 * Assumptions: `ui/src/layout/usePfKeys.ts` records that measured usage of these three across all 21
 * online programs is zero and that inventing a web key for them would create behaviour absent from
 * the source application, so `pressPfKey` refuses them. They are excluded from the unbound-key sweep
 * below because no keystroke can raise them, not because this screen treats them specially -- the
 * Clear, PA1 and PA2 contract is owned by the shared PF-key suite rather than by this screen.
 */
const AIDS_WITHOUT_A_BROWSER_KEY: readonly CicsAid[] = ['CLEAR', 'PA1', 'PA2'];

/** Test identifier the location probe publishes the router's current address under. */
const LOCATION_PROBE_TEST_ID = 'main-menu-location-probe';

/**
 * Program-name prefix marking an option the reference reports as not yet delivered.
 *
 * Assumptions: five characters, because `app/cbl/COMEN01C.cbl` L169 tests
 * `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'` -- a reference-substring comparison against the
 * first five positions of the `PIC X(08)` program name, so a name such as `DUMMY01` matches while a
 * real program name never does.
 */
const COMING_SOON_PROGRAM_PREFIX = 'DUMMY';

/**
 * Every identifier a keystroke can raise that this screen binds no handler for.
 *
 * Assumptions: the set is DERIVED by subtraction from the published AID domain rather than listed,
 * so an identifier added to `CICS_AIDS` is swept by this suite in the same commit that adds it. A
 * hand-written list would silently stop covering the new one, which is the shape of gap that leaves
 * a `WHEN OTHER` arm untested.
 */
const UNBOUND_PRESSABLE_AIDS: readonly CicsAid[] = CICS_AIDS.filter(
  /**
   * Keeps an identifier that is pressable and unbound on this screen.
   * @param {CicsAid} aid - One member of the published AID domain.
   * @returns {boolean} `true` when a keystroke can raise it and this screen binds no handler.
   */
  (aid: CicsAid): boolean => !BOUND_AIDS.includes(aid) && !AIDS_WITHOUT_A_BROWSER_KEY.includes(aid),
);

/**
 * Resolves the address the application registers for one reference program.
 *
 * Assumptions: the lookup goes through `ROUTE_TABLE` in `ui/src/router.tsx`, which pairs each of the
 * twenty-one online programs with the path that replaces it. Reading the address from there rather
 * than spelling `/menu` and `/signon` here means this file holds no second copy of a route: a path
 * renamed in the router moves this suite with it, and a suite carrying its own copy would keep
 * asserting the old one.
 * @param {string} program - Reference program name, as the option copybooks spell it.
 * @returns {string} The path pattern registered for that program.
 * @throws {Error} If the route table registers no path for the program. It throws rather than
 *   returning a fallback, because a silently absent address would make every navigation assertion
 *   below compare one empty string with another and pass.
 */
function addressForProgram(program: string): string {
  const entry = ROUTE_TABLE.find(
    /**
     * Keeps the route-table entry standing for one program.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One published route-table entry.
     * @returns {boolean} `true` when the entry replaces that program.
     */
    (candidate: (typeof ROUTE_TABLE)[number]): boolean => candidate.program === program,
  );

  if (entry === undefined) {
    throw new Error(`ui/src/router.tsx registers no route for program ${program}`);
  }

  return entry.path;
}

/** Address the main menu is mounted at, resolved from the published route table. */
const MAIN_MENU_ADDRESS = addressForProgram(MAIN_MENU_PROGRAM_NAME);

/** Address this screen's exit key returns to, resolved from the published route table. */
const SIGN_ON_ADDRESS = addressForProgram(SIGN_ON_PROGRAM_NAME);

/**
 * Reports the address this delivery enters one option's target program at.
 *
 * Assumptions: the mapping is read from `MAIN_MENU_DESTINATIONS`, which the screen derives from
 * `ui/src/routes/programRoutes.ts`, because that module is the delivery's single owner of the
 * program-to-route resolution. A table restated here would be a second owner, and the two would
 * agree only until one was corrected.
 * @param {MainMenuOption} option - One entry of the transcribed option table.
 * @returns {string} The route the option transfers to.
 * @throws {Error} If the option's program resolves to no route. The throw is itself part of the
 *   contract: `app/cpy/COMEN02Y.cpy` populates eleven options and AAP section 0.1.3.1 requires the
 *   reachability graph to survive, so an unreachable option is a parity failure rather than a case to
 *   skip.
 */
function destinationFor(option: MainMenuOption): string {
  const destination = MAIN_MENU_DESTINATIONS[option.programName];

  if (destination === undefined || destination === null) {
    throw new Error(
      `main-menu option ${String(option.optionNumber)} (${option.programName}) resolves to no route`,
    );
  }

  return destination;
}

/**
 * Composes the line the reference paints for one option.
 *
 * Assumptions: the line is composed through the catalog's own `MENU_OPTION_LINE` template rather than
 * typed out, so the expectation is built by the same code path the screen renders through. A retyped
 * line would assert that the screen agrees with THIS FILE, which a shared paraphrase satisfies while
 * fidelity is lost; composing it asserts that the screen agrees with the catalog, whose entries carry
 * their `app/cpy/COMEN02Y.cpy` line numbers.
 * @param {MainMenuOption} option - One entry of the transcribed option table.
 * @returns {string} The `NN. Name` line, with the option name's declared padding intact.
 */
function optionLine(option: MainMenuOption): string {
  return formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_LINE, {
    // WHY : Assumptions: the number is zero-filled to two characters because `app/cbl/COMEN01C.cbl`
    //       L269 emits `CDEMO-MENU-OPT-NUM` -- a `PIC 9(02)` display numeric -- with
    //       `STRING ... DELIMITED BY SIZE`, which transfers both of its characters. A terminal
    //       therefore paints `01. ` through `09. `, not `1. ` through `9. `.
    'CDEMO-MENU-OPT-NUM': String(option.optionNumber).padStart(OPTION_NUMBER_DIGITS, '0'),
    'CDEMO-MENU-OPT-NAME': option.name,
  });
}

/**
 * Locates the rendered row for one option.
 *
 * ⚠️ Assumptions: the query drops the option name's TRAILING padding and the row's raw content is
 * asserted separately by the caller, because the two differ and both matter. `app/cpy/COMEN02Y.cpy`
 * pads every name to the 35 characters of its `PIC X(35)` clause, and Testing Library normalises an
 * element's text before matching, so a query carrying that padding matches nothing at all. Trimming
 * it away in the query alone would leave the padding unasserted, which is why the caller compares
 * `textContent` against the untrimmed line.
 * @param {MainMenuOption} option - One entry of the transcribed option table.
 * @returns {HTMLElement} The element holding that option's composed line.
 * @throws {Error} If no element carries the line, which Testing Library raises.
 */
function optionRow(option: MainMenuOption): HTMLElement {
  return screen.getByText(optionLine(option).trimEnd());
}

/**
 * Locates the screen's single unprotected entry control.
 *
 * Assumptions: the control is found by its accessible name, which is the row-20 prompt the mapset
 * paints at `app/bms/COMEN01.bms` L144. The screen associates the two with `aria-labelledby` because
 * the terminal identified this field by its position at row 20 and a position names nothing to a
 * screen reader, so the prompt is the only durable handle the control has.
 * @returns {HTMLElement} The option entry control.
 * @throws {Error} If no control carries that label, which Testing Library raises.
 */
function optionField(): HTMLElement {
  return screen.getByLabelText(MAIN_MENU_HEADINGS.OPTION_PROMPT);
}

/**
 * Locates one control in the shell's row-24 function-key legend.
 *
 * ⚠️ Assumptions: the control is resolved INSIDE the legend region rather than across the document,
 * because two controls carry the `ENTER=Continue` label. The screen renders its own submit control
 * beside the option field so a pointer operator can act on the entry, and it deliberately reuses the
 * mapset's wording so no new phrasing is introduced -- so a document-wide query by name matches two
 * elements and fails on the ambiguity rather than on anything a case asserts. The legend is a named
 * navigation landmark, so scoping to it names the control by its role in the frame rather than by DOM
 * order, which a later layout change would silently invalidate.
 * @param {string} label - Legend label of the wanted control, from the message catalog.
 * @returns {HTMLElement} That legend control.
 * @throws {Error} If the legend carries no such control, which Testing Library raises.
 */
function legendControl(label: string): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });

  return within(legend).getByRole('button', { name: label });
}

/**
 * Reports the address the router currently holds.
 * @returns {string} The current pathname, as the probe rendered beside the screen published it.
 * @throws {Error} If the probe is absent, which Testing Library raises.
 */
function currentAddress(): string {
  return screen.getByTestId(LOCATION_PROBE_TEST_ID).textContent ?? '';
}

/**
 * Publishes the router's current address so a case can observe where an option transferred to.
 *
 * Assumptions: the pathname is read with `useLocation` and rendered as text, which is the only way a
 * memory router's history is observable -- there is no URL bar in the runner to read. It is a probe
 * rendered BESIDE the subject rather than a spy on `useNavigate`, so what a case asserts is the
 * address the router actually settled on rather than the argument the screen passed to a mock.
 * @returns {ReactElement} An element carrying the current pathname.
 */
function LocationProbe(): ReactElement {
  const { pathname } = useLocation();

  return <div data-testid={LOCATION_PROBE_TEST_ID}>{pathname}</div>;
}

/**
 * Signs a case on with a chosen set of group claims.
 *
 * ⚠️ Refactoring Rationale: authority is established by minting a token and driving the real sign-on,
 * never by assignment, and the reason is a security property rather than tidiness. In the baseline
 * `CDEMO-USER-TYPE PIC X(01)` with its `'A'` and `'U'` condition names lived in the `DFHCOMMAREA`
 * (`app/cpy/COCOM01Y.cpy` L26-L28) -- storage the client echoed back on every turn, so a client
 * could in principle assert its own user type. In the target the type is a signed `cognito:groups`
 * claim and the client cannot assert anything (AAP section 0.7.1). `ui/src/hooks/useAuth.ts`
 * enforces that by publishing NO setter for the groups and by holding its session in module scope
 * with no React context provider anywhere, so a test cannot grant itself administrator either. This
 * helper is therefore the only path there is, and that is the point.
 *
 * Assumptions: the group names are IMPORTED as constants rather than retyped, so a renamed group
 * moves the production reader and this suite together.
 * @param {readonly string[]} groups - Group names the identity token is to carry.
 * @returns {Promise<void>} Resolves once the session is established and observable.
 * @throws {Error} If the exchange established no session, which the session harness reports.
 */
async function signOnWithGroups(groups: readonly string[]): Promise<void> {
  await seedSession({ groups });
}

/**
 * Mounts the menu inside the real application shell, with a probe reporting the router's address.
 *
 * ⚠️ Assumptions: the subject is mounted INSIDE `AppShell`, and it has to be. The screen delegates
 * its rows 1-3 title band, its row-23 message line and its row-24 key legend to the shell through
 * `useShellSlot`, so a bare mount would leave all three rendered by nothing and every message and
 * legend assertion below unsatisfiable. `ui/src/App.tsx` mounts the shell around the router in
 * production, so this is the composition the application actually builds.
 *
 * ⚠️ Alternatives Considered: naming `MAIN_MENU_ADDRESS` as the child route pattern, which is what a
 * faithful route declaration would do. Rejected because the moment an option transfers, no declared
 * pattern matches the new address, the whole tree unmounts and the probe that would report where it
 * went unmounts with it -- leaving a case able to observe only that the menu is gone, not which
 * screen it reached. Omitting the pattern gives the harness's catch-all child instead, so the subject
 * survives the transfer and the probe can be read. The cost accepted is that the menu stays mounted
 * after a transfer, which no case asserts against.
 * @returns {Promise<HarnessRenderResult>} The render result and the operator that drives it.
 */
async function mountMenu(): Promise<HarnessRenderResult> {
  return await renderInAppShell(
    <>
      <MainMenuScreen />
      <LocationProbe />
    </>,
    { initialEntries: [MAIN_MENU_ADDRESS] },
  );
}

/**
 * Types one option number into the entry control and raises the Enter attention identifier.
 *
 * ⚠️ Alternatives Considered: clicking the screen's submit control instead. Rejected as the default
 * because the contract is a KEYBOARD contract -- the 3270 original was keyboard-only, so a suite that
 * only ever clicked would stay green while every keyboard binding in the application was broken. The
 * click path is not left untested either; it is asserted alongside the keystroke in the parity cases
 * below, which is the arrangement that catches a break in either one.
 *
 * Assumptions: the number is typed zero-filled to two digits, which is the form
 * `app/cbl/COMEN01C.cbl` L117-L125 normalises any entry to and echoes back, so the typed text is what
 * a terminal would have redisplayed.
 * @param {UserEvent} user - The operator from the render result.
 * @param {number} optionNumber - Option number to select.
 * @returns {Promise<void>} Resolves once the entry has been typed and the key dispatched.
 */
async function selectOption(user: UserEvent, optionNumber: number): Promise<void> {
  await user.type(optionField(), String(optionNumber).padStart(OPTION_NUMBER_DIGITS, '0'));
  await pressPfKey(user, 'ENTER');
}

/**
 * Asserts one catalogued sentence is painted in the shell's row-23 message band.
 *
 * ⚠️ Assumptions: TRAILING padding is dropped before the query, and only trailing. Several catalogued
 * sentences are fixed-width -- `CCDA-MSG-INVALID-KEY` is `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20 --
 * and Testing Library trims the boundary whitespace of an element's text before matching, so a query
 * carrying the padding matches nothing. The padding is not left unasserted: the declared width is
 * checked against the catalog's own `declaredWidth` member in the invalid-key case below. Internal
 * whitespace is preserved, because `expectVerbatimMessage` disables whitespace collapsing -- which
 * matters for strings whose doubled spaces are content rather than formatting.
 *
 * Assumptions: the sentence is additionally required to sit INSIDE the band rather than merely
 * somewhere in the document. `app/cbl/COMEN01C.cbl` L213 moves every one of this program's messages
 * into `ERRMSGO`, the row-23 field, so a sentence rendered anywhere else would be a different screen
 * from the one the mapset declares.
 * @param {string} expected - The catalogued sentence, taken from the message catalog and not retyped.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 * @throws {Error} If no element carries the sentence, which Testing Library raises.
 */
function expectBandMessage(expected: string): void {
  const painted = expectVerbatimMessage(expected.trimEnd());

  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).contains(painted)).toBe(true);
}

/**
 * All eleven options are painted, in the copybook's order, with their padding intact.
 *
 * Assumptions: the order is asserted by the position of each line within the screen body's text
 * rather than by comparing element positions, because the reference's own ordering property is
 * positional -- `app/cbl/COMEN01C.cbl` L264-L265 walks the table from 1 by 1 and L274-L301 assigns
 * each iteration to the next `OPTN0nn` field, which the mapset places on consecutive rows 6 to 17.
 * A strictly increasing index reproduces exactly that claim and, because a line that is absent
 * yields no index, it asserts presence in the same pass.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsAllElevenOptionsInCopybookOrder(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  // WHY : Assumptions: the bound is `MAIN_MENU_OPTION_COUNT`, the catalog's transcription of
  //       `CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 11` at `app/cpy/COMEN02Y.cpy` L21, and NOT the length
  //       of the array. The two agree at eleven but mean different things: the `REDEFINES` at L93-L98
  //       carves the table as `OCCURS 12 TIMES` while only eleven slots are populated, so the count is
  //       the bound the program tests and the arity is the storage it was cut from.
  expect(MAIN_MENU_OPTIONS).toHaveLength(MAIN_MENU_OPTION_COUNT);

  const painted = shellLandmark('screenBody').textContent ?? '';
  let previous = -1;

  for (const option of MAIN_MENU_OPTIONS) {
    const at = painted.indexOf(optionLine(option));

    expect(
      at,
      `option ${String(option.optionNumber)} must be painted after option ${String(option.optionNumber - 1)}`,
    ).toBeGreaterThan(previous);
    previous = at;
  }
}

/**
 * Each option row carries its number zero-filled and its name padded to the declared width.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function padsEveryOptionRowToItsDeclaredWidths(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  for (const option of MAIN_MENU_OPTIONS) {
    // WHY : Assumptions: the raw `textContent` is compared rather than a normalised query result,
    //       because this is the assertion that holds the padding. `app/cpy/COMEN02Y.cpy` pads every
    //       name to the 35 characters of its `PIC X(35)` clause and `app/cbl/COMEN01C.cbl` L271 emits
    //       it `DELIMITED BY SIZE`, so all 35 characters reach the field; a normalised comparison would
    //       accept a screen that trimmed them.
    expect(optionRow(option).textContent).toBe(optionLine(option));
    expect(option.name).toHaveLength(MENU_OPTION_DECLARED_WIDTH);
    expect(
      optionLine(option).startsWith(
        `${String(option.optionNumber).padStart(OPTION_NUMBER_DIGITS, '0')}. `,
      ),
    ).toBe(true);
  }
}

/**
 * The entry control refuses a third character, as its two-position field does.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function boundsTheEntryControlToItsDeclaredWidth(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const { user } = await mountMenu();

  // WHY : Assumptions: the screen's own width constant is checked against the width read from
  //       `app/cpy-bms/COMEN01.CPY` L132 before it is trusted, so this case cannot ratify a screen
  //       that has drifted from the symbolic map. See the constant's own note for why the literal is
  //       restated here rather than the screen's value simply being reused.
  expect(MAIN_MENU_OPTION_WIDTH).toBe(OPTION_FIELD_DECLARED_WIDTH);
  expectMaxLength(optionField(), OPTION_FIELD_DECLARED_WIDTH);

  await user.type(optionField(), '123');

  // WHY : Assumptions: the third character is dropped rather than replacing the first, which is what
  //       a 3270 did with a field already full -- the keystroke did nothing. `maxLength` reproduces
  //       that, so the value holds the FIRST two characters typed.
  expect(optionField()).toHaveValue('12');
}

/**
 * The entry control keeps only digits, which is what its `NUM` attribute admits.
 *
 * Assumptions: `NUM` is a real and narrow contract rather than decoration -- only three of the
 * twenty-one mapsets declare it anywhere (`COADM01`, `COMEN01` and `CORPT00`) -- so a screen that
 * accepted letters here would be accepting input the terminal's own hardware refused.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsOnlyDigitsInTheEntryControl(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const { user } = await mountMenu();

  await user.type(optionField(), 'a1b');

  // WHY : Assumptions: the non-digits are discarded as they are typed rather than refused on submit,
  //       because `app/bms/COMEN01.bms` L145 sets `NUM` on the field itself -- an attribute of the
  //       field, enforced before the program ever runs, not a validation the program performs.
  expect(optionField()).toHaveValue('1');
}

/**
 * The cursor starts in the screen's one unprotected field, which is what `IC` names.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function placesTheCursorInTheSingleUnprotectedField(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  // WHY : Assumptions: exactly one entry control exists on this screen, and that is measured rather
  //       than assumed: of the 28 `DFHMDF` definitions in `app/bms/COMEN01.bms`, the `OPTION` field at
  //       L145 is the only one carrying `UNPROT` -- the twelve option rows at L80-L139 are all `ASKIP`,
  //       so a terminal operator could not type into them. One control is therefore the whole
  //       population that `IC` could have selected from.
  expect(screen.getAllByRole('textbox')).toHaveLength(1);

  // WHY : Assumptions: the assertion identifies the ACTIVE element rather than counting focused ones,
  //       because a document has exactly one active element by definition -- so naming which element
  //       it is says everything the `IC` attribute claims. `app/bms/COMEN01.bms` L145 sets `IC` on this
  //       one field, so an operator can type an option number without first reaching for a pointer.
  expect(optionField()).toHaveFocus();
}

/**
 * The entry is a design-system control rather than a raw element, and it is editable.
 *
 * ⚠️ Assumptions: this screen carries NO per-field error decoration, and that is the reference's
 * arrangement rather than an omission. `app/cbl/COMEN01C.cbl` COPYs nine books at L50-L61 and
 * `CSSETATY` -- the templated red-highlight-and-asterisk copybook -- is NOT among them, so the program
 * has no field-level error mechanism at all: every refusal it raises goes to `WS-MESSAGE` and from
 * there to the row-23 `ERRMSG` field at L213. Asserting a `Form.Item` error decoration here would
 * therefore be asserting a design the reference does not have, which is why the refusal cases below
 * assert the message band instead.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersTheEntryAsADesignSystemControl(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  const field = optionField();

  // WHY : Assumptions: the design-system class is the observable evidence that the control came from
  //       antd's `Input` rather than being a raw `<input>`, which AAP section 0.3.2 forbids -- a
  //       component reached through the package root participates in the CSS-variables theme
  //       `ui/src/theme/antdTheme.ts` injects, and a hand-rolled element does not.
  expect(field.className).toContain('ant-input');
  expect(field).toBeEnabled();

  // WHY : Assumptions: the field is asserted writable because `app/bms/COMEN01.bms` L145 declares it
  //       `UNPROT` with `FSET`. A control rendered read-only would paint identically and would leave
  //       the screen with no way to receive an option at all.
  expect(field).not.toHaveAttribute('readonly');
}

/**
 * Every option enters the screen its target program is registered at.
 *
 * Assumptions: each option is exercised in its own mount, because a transfer changes the address and a
 * second entry typed into an already-transferred tree would be typed into a different screen. The
 * mount is torn down before the next one so the document holds one subject at a time and a query
 * cannot match two.
 *
 * Trade-offs: eleven mounts make this the most expensive case in the file, and the cost is accepted
 * rather than reduced by sampling two or three options. The option-to-program pairs are independent
 * data -- a wrong route for option 9 is not implied by a right one for option 1 -- so a sample would
 * leave most of the transfer graph unverified while reading as though it covered it.
 * @returns {Promise<void>} Resolves once every option has been entered.
 */
async function entersTheScreenEveryOptionNames(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);

  for (const option of MAIN_MENU_OPTIONS) {
    const { user, unmount } = await mountMenu();

    expect(currentAddress()).toBe(MAIN_MENU_ADDRESS);
    await selectOption(user, option.optionNumber);

    // WHY : Assumptions: the expected address is resolved through `MAIN_MENU_DESTINATIONS`, so this
    //       asserts the transfer the delivery declares for that program rather than a route spelled
    //       here. The reference dispatches on the program name too -- `app/cbl/COMEN01C.cbl` L185-L186
    //       transfers with `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))` -- which Transformation
    //       Rule T5 maps to exactly this client-side route change.
    expect(
      currentAddress(),
      `option ${String(option.optionNumber)} (${option.programName}) must enter its screen`,
    ).toBe(destinationFor(option));

    unmount();
  }
}

/**
 * Every option resolves to a concrete address that needs no record selector.
 *
 * ⚠️ Assumptions: this is the invariant that replaces the brief's tabulated
 * `/cards/:num`-style destinations for options 4, 5 and 7. A menu option carries no record
 * identifier -- the operator typed two digits and nothing else -- so a parameterised pattern could not
 * be addressed from here at all, and navigating to one would put the literal text of the parameter in
 * the address. `ui/src/routes/programRoutes.ts` therefore resolves those three to the owning browse,
 * where the selector is minted, and the property worth holding is that no destination still needs
 * one.
 *
 * Assumptions: the case is SYNCHRONOUS and mounts nothing, because the property is a property of the
 * resolution table rather than of a render -- every destination is decided before any component
 * exists. Mounting the screen to assert it would make the case slower and would couple a statement
 * about the route map to the success of a render it does not depend on.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function resolvesEveryOptionToASelectorFreeAddress(): void {
  for (const option of MAIN_MENU_OPTIONS) {
    const destination = destinationFor(option);

    expect(destination.startsWith('/')).toBe(true);
    expect(
      destination,
      `option ${String(option.optionNumber)} must not require a record selector`,
    ).not.toContain(':');
  }
}

/**
 * A non-administrator sees every option and can enter transaction add.
 *
 * ⚠️ Assumptions: `app/cpy/COMEN02Y.cpy` L69 holds a COMMENTED-OUT variant of option 8 reading
 * `'Transaction Add (Admin Only)       '`, and it is a live trap: it is exactly 35 characters, so it
 * is indistinguishable from the real label by length and is excluded on the strength of its column-7
 * comment marker alone. The LIVE label is L70's `'Transaction Add                    '` with no
 * suffix, and the option's user type at L72 is `'U'`. An implementation written from L69 would gate
 * this route behind the administrator group and break parity for every ordinary operator, so the
 * reachability is asserted explicitly rather than left to follow from the sweep above.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function letsANonAdministratorEnterTransactionAdd(): Promise<void> {
  const transactionAdd = MAIN_MENU_OPTIONS.find(
    /**
     * Keeps the option whose target program is the transaction-add screen.
     * @param {MainMenuOption} option - One entry of the transcribed option table.
     * @returns {boolean} `true` for the `COTRN02C` entry.
     */
    (option: MainMenuOption): boolean => option.programName === 'COTRN02C',
  );

  expect(transactionAdd, 'the catalog must carry the transaction-add option').toBeDefined();
  // WHY : Assumptions: the narrowing guard is a throw rather than a non-null assertion, because
  //       `ui/tsconfig.json` runs with `strict` and `noUncheckedIndexedAccess` and the expectation
  //       above does not narrow the type for the compiler. A throw states the same requirement in a
  //       form the compiler accepts and keeps the failure legible if the catalog ever drops the entry.
  if (transactionAdd === undefined) {
    throw new Error('app/cpy/COMEN02Y.cpy L70 declares COTRN02C, but the catalog carries no entry');
  }

  expect(transactionAdd.name).toBe(MAIN_MENU_OPTIONS[7]?.name);
  expect(transactionAdd.name).not.toContain('Admin Only');
  expect(transactionAdd.userType).toBe('U');

  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const { user } = await mountMenu();

  expect(screen.queryByText(CARDDEMO_ADMIN_GROUP)).toBeNull();
  expect(optionRow(transactionAdd)).toBeInTheDocument();

  await selectOption(user, transactionAdd.optionNumber);

  expect(currentAddress()).toBe(destinationFor(transactionAdd));
}

/**
 * No main-menu option is administrator-only, so an ordinary operator is refused nothing.
 *
 * Assumptions: this is checked over the whole table rather than for option 8 alone, because the
 * copybook sets `PIC X(01) VALUE 'U'` on all eleven entries -- at L29, L35, L41, L47, L53, L59, L65,
 * L72, L78, L84 and L90 -- and not one is `'A'`. Administrator gating in this system belongs to the
 * six options of `app/cpy/COADM02Y.cpy`, reached from the admin menu, which a different suite owns.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function offersNoAdministratorOnlyOption(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  for (const option of MAIN_MENU_OPTIONS) {
    expect(
      option.userType,
      `option ${String(option.optionNumber)} must stay an ordinary-operator option`,
    ).toBe('U');
    expect(optionRow(option)).toBeInTheDocument();
  }
}

/**
 * The legend paints exactly the two keys the mapset's row-24 literal names.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsExactlyTheTwoLegendKeysTheMapsetCarries(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });

  // WHY : Assumptions: exactly two controls, because `app/bms/COMEN01.bms` L158-L162 paints one
  //       literal, `'ENTER=Continue  F3=Exit'`, at `LENGTH=23` -- and the arithmetic closes it against
  //       the declared width, 14 + 2 + 7 = 23, which leaves the two spaces as the separator rather
  //       than part of either label. A third control would mean a key this program does not dispatch.
  expect(within(legend).getAllByRole('button')).toHaveLength(BOUND_AIDS.length);
  expect(legendControl(MAIN_MENU_KEY_LABELS.ENTER)).toBeInTheDocument();
  expect(legendControl(MAIN_MENU_KEY_LABELS.PFK03)).toBeInTheDocument();
}

/**
 * Enter selects the typed option, whether raised from the keyboard or from the legend control.
 *
 * ⚠️ Alternatives Considered: asserting only the legend click, which is the cheaper case to write.
 * Rejected because it inverts the contract: the 3270 original was keyboard-only, so the binding is
 * the primary surface and the control is the addition -- a click-only case stays green while every
 * keyboard binding in the application is broken. Asserting only the keystroke was rejected for the
 * mirror-image reason, since a pointer operator has no other way to act on the entry. Both paths must
 * reach the same screen, so both are driven here and compared against one expectation.
 * @returns {Promise<void>} Resolves once both paths have been driven.
 */
async function entersTheOptionFromTheKeyAndFromTheLegend(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);

  const first = MAIN_MENU_OPTIONS[0];
  if (first === undefined) {
    throw new Error('app/cpy/COMEN02Y.cpy L27 declares option 1, but the catalog carries no entry');
  }

  const expected = destinationFor(first);
  const typed = String(first.optionNumber).padStart(OPTION_NUMBER_DIGITS, '0');

  const keyboard = await mountMenu();
  await selectOption(keyboard.user, first.optionNumber);
  expect(currentAddress()).toBe(expected);
  keyboard.unmount();

  const pointer = await mountMenu();
  await pointer.user.type(optionField(), typed);
  await pointer.user.click(legendControl(MAIN_MENU_KEY_LABELS.ENTER));

  expect(currentAddress()).toBe(expected);
}

/**
 * The exit key returns to sign-on, whether raised from the keyboard or from the legend control.
 *
 * Assumptions: the destination is sign-on and the session ends with it, because
 * `app/cbl/COMEN01C.cbl` L196-L203 transfers to `COSGN00C` with `XCTL PROGRAM(CDEMO-TO-PROGRAM)` and
 * NO `COMMAREA` clause -- the one transfer in the estate that omits it -- so the next program starts
 * with `EIBCALEN = 0` and no identity at all. A route change that left the token in place would be a
 * different behaviour wearing the same address.
 * @returns {Promise<void>} Resolves once both paths have been driven.
 */
async function exitsToSignOnFromTheKeyAndFromTheLegend(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);

  const keyboard = await mountMenu();
  await pressPfKey(keyboard.user, 'PFK03');
  expect(currentAddress()).toBe(SIGN_ON_ADDRESS);
  keyboard.unmount();

  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const pointer = await mountMenu();
  await pointer.user.click(legendControl(MAIN_MENU_KEY_LABELS.PFK03));

  expect(currentAddress()).toBe(SIGN_ON_ADDRESS);
}

/**
 * Every pressable key this screen does not bind reports the shared invalid-key sentence.
 *
 * Assumptions: the sweep asserts the `WHEN OTHER` arm of `app/cbl/COMEN01C.cbl` L99-L102 for the whole
 * unbound domain rather than for one sampled key, because the arm is reached by every identifier the
 * program does not name and a single sample would leave the rest unverified. The address is asserted
 * unchanged alongside the sentence, since the failure this guards against is a stray binding that
 * navigates as well as reporting.
 *
 * Trade-offs: one mount per identifier is spent so that a stray binding cannot hide behind a
 * neighbour. Pressing every key into a single mount would be cheaper, but the first unbound key
 * paints the sentence and it then stays painted, so every later key in that mount would satisfy the
 * assertion whether it reported anything or not.
 * @returns {Promise<void>} Resolves once every unbound key has been pressed.
 */
async function reportsEveryUnboundAttentionKey(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);

  // WHY : Assumptions: the width is asserted against the catalog's own `declaredWidth` member, which
  //       is where the `PIC X(50)` clause of `CCDA-MSG-INVALID-KEY` at `app/cpy/CSMSG01Y.cpy` L20-L21
  //       is recorded. This is the assertion that holds the fixed width, because the query below
  //       necessarily drops the trailing padding.
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(INVALID_KEY_PRESSED.length).toBeLessThanOrEqual(COMMON_MESSAGES.INVALID_KEY.declaredWidth);

  for (const aid of UNBOUND_PRESSABLE_AIDS) {
    const { user, unmount } = await mountMenu();

    await pressPfKey(user, aid);

    expectBandMessage(INVALID_KEY_PRESSED);
    expect(currentAddress(), `${aid} must not transfer anywhere`).toBe(MAIN_MENU_ADDRESS);

    unmount();
  }
}

/**
 * An option number the menu does not offer is refused with the program's one sentence.
 *
 * Assumptions: the entry used is the catalogued count plus one, so the case tracks
 * `CDEMO-MENU-OPT-COUNT` instead of naming twelve. `app/cbl/COMEN01C.cbl` L128 tests
 * `WS-OPTION > CDEMO-MENU-OPT-COUNT`, so the first refused number is whatever that count is plus one
 * -- and twelve is additionally the `OCCURS` arity, which would make a literal twelve read as a test
 * of the unpopulated slot rather than of the bound.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAnOptionAboveTheCataloguedCount(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const { user } = await mountMenu();

  await selectOption(user, MAIN_MENU_OPTION_COUNT + 1);

  expectBandMessage(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
  expect(currentAddress()).toBe(MAIN_MENU_ADDRESS);
}

/**
 * A zero entry is refused with the same single sentence.
 *
 * Assumptions: zero is a distinct arm of the reference's condition -- `app/cbl/COMEN01C.cbl` L129
 * tests `WS-OPTION = ZEROS` -- but it emits the SAME sentence as the other two arms, because L127-L134
 * is one `IF` with one `MOVE`. Telling the operator which of the three rules they broke would tell
 * them more than the terminal did.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAZeroEntry(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const { user } = await mountMenu();

  await selectOption(user, 0);

  expectBandMessage(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
  expect(currentAddress()).toBe(MAIN_MENU_ADDRESS);
}

/**
 * A blank entry is refused, and no field-level marker is painted.
 *
 * ⚠️ Assumptions: a blank field reaches the SAME refusal rather than a `'*'` marker, and the reference
 * settles this. `app/cbl/COMEN01C.cbl` L117-L120 floors its backward scan at position 1, so an
 * all-blank field yields a one-character blank prefix, L123's `INSPECT ... REPLACING ALL ' ' BY '0'`
 * turns it into `'00'`, and the `WS-OPTION = ZEROS` arm at L129 refuses it. The asterisk marker of
 * `app/cpy/CSSETATY.cpy` L24 belongs to programs that COPY that book, and L50-L61 shows this program
 * does not -- so painting one here would add a decoration the terminal never showed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesABlankEntry(): Promise<void> {
  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  const { user } = await mountMenu();

  await pressPfKey(user, 'ENTER');

  expectBandMessage(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
  expect(screen.queryByText('*')).toBeNull();
  expect(currentAddress()).toBe(MAIN_MENU_ADDRESS);
}

/**
 * The administrator refusal is carried verbatim, trailing space included, and stays unreachable.
 *
 * ⚠️ Assumptions: the sentence is asserted as a catalogued VALUE rather than as painted output,
 * because it cannot be painted from this screen: `app/cbl/COMEN01C.cbl` L136-L143 raises it only when
 * `CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'`, and no live entry is `'A'`. Both halves matter -- the
 * transcription must stay byte-exact for the day an option is restricted, and the unreachability must
 * stay true or an ordinary operator starts being refused an option the copybook grants them.
 *
 * Assumptions: the trailing space is part of the value, because the literal at L140 ends with one.
 * Trimming it reads as tidier and is a fidelity loss, which is why it is asserted rather than assumed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function holdsTheAdministratorRefusalVerbatim(): Promise<void> {
  const refusal = PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION;

  expect(refusal.endsWith(' ')).toBe(true);
  expect(refusal).toBe(refusal.trimEnd() + ' ');

  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  expect(screen.queryByText(refusal.trimEnd())).toBeNull();
}

/**
 * Both unavailable-option sentences are composed verbatim, asymmetry included, and stay unreachable.
 *
 * ⚠️ Assumptions: the two sentences are NOT unified, and the asymmetry is the reference's rather than
 * an authoring slip to be tidied. The not-installed composer at `app/cbl/COMEN01C.cbl` L163-L166
 * delimits the option name by TWO spaces -- which strips the `PIC X(35)` padding without cutting the
 * name -- and its closing literal carries a LEADING space. The coming-soon composer at L172-L175
 * delimits by a SINGLE space, so it truncates the name at its first word, and its closing literal has
 * NO leading space, so it renders as "This option Accountis coming soon ...". Transformation Rule T8
 * forbids repairing either: `app/**` is the behavioural oracle, so the slip is transcribed as it
 * stands and asserted as it stands.
 *
 * Assumptions: both are asserted as composed VALUES rather than as painted output, because neither can
 * be painted by this delivery -- every option resolves to a route, so the not-installed arm is dead,
 * and no catalogued program name begins `DUMMY`, so the coming-soon arm at L169 is dead too. The
 * transcription still has to stay byte-exact for the day an option is added ahead of its screen, and
 * the unreachability still has to stay true or an operator starts being told a delivered screen is
 * missing.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function holdsBothUnavailableOptionSentencesVerbatim(): Promise<void> {
  const first = MAIN_MENU_OPTIONS[0];
  if (first === undefined) {
    throw new Error('app/cpy/COMEN02Y.cpy L27 declares option 1, but the catalog carries no entry');
  }

  const notInstalled = formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_NOT_INSTALLED, {
    'CDEMO-MENU-OPT-NAME': first.name,
  });
  const comingSoon = formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_COMING_SOON, {
    'CDEMO-MENU-OPT-NAME': first.name,
  });

  // WHY : Assumptions: the expectation is built from the option's own name with the delimiter each
  //       composer declares, rather than from a retyped sentence, so it states the DELIMITER contract
  //       instead of restating the output. The double-space form keeps the whole name and the closing
  //       literal's leading space, so the words stay separated.
  expect(notInstalled).toBe(`This option ${first.name.trimEnd()} is not installed...`);

  // WHY : Assumptions: the single-space form truncates at the first word and the closing literal
  //       supplies no space, so the name and the next word run together. Asserting the run-together
  //       form is what keeps a future "readability fix" from silently changing operator-visible text.
  expect(comingSoon).toBe(`This option ${first.name.split(' ')[0] ?? ''}is coming soon ...`);
  expect(comingSoon).not.toContain(' is coming soon');

  for (const option of MAIN_MENU_OPTIONS) {
    // WHY : Assumptions: the prefix test is five characters against the program NAME, because
    //       `app/cbl/COMEN01C.cbl` L169 tests `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'` -- a
    //       reference-substring comparison on the `PIC X(08)` name, so `DUMMY01` would match while a
    //       real program name never does.
    expect(option.programName.startsWith(COMING_SOON_PROGRAM_PREFIX)).toBe(false);
  }

  await signOnWithGroups([CARDDEMO_USER_GROUP]);
  await mountMenu();

  expect(screen.queryByText(notInstalled)).toBeNull();
  expect(screen.queryByText(comingSoon)).toBeNull();
}

/**
 * Registers the main-menu screen cases.
 *
 * Assumptions: every case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback, which matches the sibling screen suites and keeps each case's Rule 1 block attached to a
 * declaration instead of to an argument.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function mainMenuScreenCases(): void {
  it('paints all eleven options in the copybook order', paintsAllElevenOptionsInCopybookOrder);
  it('pads every option row to its declared widths', padsEveryOptionRowToItsDeclaredWidths);
  it('bounds the entry control to its declared width', boundsTheEntryControlToItsDeclaredWidth);
  it('keeps only digits in the entry control', keepsOnlyDigitsInTheEntryControl);
  it(
    'places the cursor in the single unprotected field',
    placesTheCursorInTheSingleUnprotectedField,
  );
  it('renders the entry as a design-system control', rendersTheEntryAsADesignSystemControl);
  it('enters the screen every option names', entersTheScreenEveryOptionNames);
  it('resolves every option to a selector-free address', resolvesEveryOptionToASelectorFreeAddress);
  it('lets a non-administrator enter transaction add', letsANonAdministratorEnterTransactionAdd);
  it('offers no administrator-only option', offersNoAdministratorOnlyOption);
  it(
    'paints exactly the two legend keys the mapset carries',
    paintsExactlyTheTwoLegendKeysTheMapsetCarries,
  );
  it(
    'enters the option from the key and from the legend',
    entersTheOptionFromTheKeyAndFromTheLegend,
  );
  it('exits to sign-on from the key and from the legend', exitsToSignOnFromTheKeyAndFromTheLegend);
  it('reports every unbound attention key', reportsEveryUnboundAttentionKey);
  it('refuses an option above the catalogued count', refusesAnOptionAboveTheCataloguedCount);
  it('refuses a zero entry', refusesAZeroEntry);
  it('refuses a blank entry', refusesABlankEntry);
  it('holds the administrator refusal verbatim', holdsTheAdministratorRefusalVerbatim);
  it(
    'holds both unavailable-option sentences verbatim',
    holdsBothUnavailableOptionSentencesVerbatim,
  );
}

describe('main menu screen at /menu', mainMenuScreenCases);
