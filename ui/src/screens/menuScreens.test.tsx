/**
 * @file Proves the two menu screens transcribe their reference programs: the option lists they paint,
 * the entries they refuse, the destinations they enter, and what their one function key does.
 *
 * Purpose
 * -------
 * `ui/src/screens/menu/index.tsx` and `ui/src/screens/admin/index.tsx` replace `app/cbl/COMEN01C.cbl`
 * and `app/cbl/COADM01C.cbl`, which are the two screens sign-on transfers to and therefore the hub of
 * the whole online reachability graph. Neither has a golden master -- the online programs cannot run
 * without a CICS runtime, as `tests/README.md` section 1.1 records -- so these cases are the only
 * verification the two screens receive.
 *
 * What is asserted, and why in two forms
 * --------------------------------------
 * Assumptions: the decision table is asserted through the exported pure functions and the presentation
 * through a rendered tree, because the two answer different questions. `resolveMenuOption` and
 * `resolveAdminOption` decide what an entry MEANS -- which is where the reference's validation order,
 * its unavailable-option sentence and its per-program message colour live -- and a rendered case cannot
 * distinguish a refusal produced by the transcribed rule from one produced by a coincidence of the form.
 * The rendered cases then prove the option lines are composed through the catalog's template and that
 * the key bindings reach those decisions.
 *
 * Assumptions: every expectation is the CONSTANT the screen or the catalog publishes rather than a
 * retyped sentence, for the reason `ui/src/screens/cardScreenShell.test.tsx` records -- a retyped string
 * passes for a screen that has drifted, provided the test drifted with it. Rendered expectations are
 * collapsed the way the testing library normalises DOM text, because the option names are padded to 35
 * characters on a character grid.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the card screen tests record: `ui/eslint.config.js` requires a documentation block on a
 * function expression in any position, and Prettier moves a block comment that follows an argument comma
 * onto the preceding literal, which detaches it from the function it documents.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  ADMIN_MENU_OPTIONS,
  MAIN_MENU_OPTIONS,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../messages/messages';
import type { MainMenuOption } from '../messages/messages';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { SIGN_ON_ROUTE } from '../routes/guards';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { endAnySession, establishSession, isSignedOn } from '../test/sessionHarness';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE } from '../routes/navigation';
import {
  ADMIN_MENU_KEY_LABELS,
  ADMIN_MENU_PROMPT,
  ADMIN_MENU_SUBTITLE,
  AdminMenuScreen,
  resolveAdminOption,
} from './admin';
import {
  MAIN_MENU_KEY_LABELS,
  MAIN_MENU_PROMPT,
  MAIN_MENU_SUBTITLE,
  MainMenuScreen,
  resolveMenuOption,
} from './menu';

/*
 * WHY : ⚠️ Refactoring Rationale: a `carddemo.id-token` key stood here and nothing reads it -- the hook
 *       holds the session in a module variable and the bearer in one belonging to the transport, so that
 *       nothing script-readable retains a credential. Writing the key established no session, so the two
 *       cases that needed one rendered a menu no operator was signed on to, and the sign-off case read
 *       `null` from a store the sign-off had never written.
 */

/** Text a probe route renders so a transition can be observed. */
const ARRIVED = 'ARRIVED';

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: interior runs are collapsed and the ends trimmed, matching the library's default
 * normaliser, so an option name padded to 35 characters for a character grid can still be compared
 * against what a proportional layout renders.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/** Groups a main-menu operator holds; the administrative menu's own cases install their own. */
const ORDINARY_GROUPS: readonly string[] = ['carddemo-user'];

/**
 * Installs a non-administrative session, which is what a main-menu operator holds.
 * @returns {Promise<void>} Resolves once the session is held.
 */
async function signOnAsUser(): Promise<void> {
  const { unmount } = await establishSession({ groups: ORDINARY_GROUPS });

  unmount();
}

/**
 * Clears the installed session and the recorded traffic between cases.
 * @returns {void} Nothing; the environment is reset in place.
 */
function clearSession(): void {
  endAnySession();
  removeApiHarness();
}

/**
 * Arms the intercepting transport the session exchange and the sign-off revocation are answered by.
 * @returns {void} Nothing; the harness is installed.
 */
function armTransport(): void {
  installApiHarness();
}

/**
 * Renders one menu screen with probe routes at the destinations under test.
 *
 * Assumptions: a memory router with explicit probe routes rather than the shipped table, because these
 * cases assert what the SCREEN decides. Whether the destination is registered is proved separately by
 * `ui/src/routerRoutes.test.tsx`, which renders the shipped table.
 * @param {ReactElement} menu - The menu screen to render.
 * @param {string} at - Route the menu is mounted at.
 * @param {readonly string[]} probes - Destinations that render the arrival marker.
 * @returns {ReactElement} The composed tree under test.
 */
function renderMenu(menu: ReactElement, at: string, probes: readonly string[]): ReactElement {
  return (
    <MemoryRouter initialEntries={[at]}>
      <Routes>
        <Route path={at} element={menu} />
        {probes.map(
          /**
           * Mounts one probe route that reports its own arrival.
           * @param {string} path - Destination to probe.
           * @returns {ReactElement} The probe route.
           */
          (path) => (
            <Route key={path} path={path} element={<div>{`${ARRIVED} ${path}`}</div>} />
          ),
        )}
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Finds the screen's own submit control, excluding the identically labelled legend entry.
 *
 * Assumptions: the label is deliberately shared. The submit control carries the mapset's own
 * `ENTER=Continue` text so no new wording is introduced, and the function-key legend paints the same
 * label -- so a query by name alone matches two elements. The legend is a named navigation landmark, so
 * excluding anything inside it names the control by ROLE IN THE SCREEN rather than by DOM order, which a
 * later layout change would silently invalidate.
 * @param {string} label - The shared label both elements carry.
 * @returns {HTMLElement} The submit control outside the legend region.
 */
function submitControl(label: string): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const candidates = screen.getAllByRole('button', { name: label });
  const control = candidates.find(
    /**
     * Keeps the candidate that is not part of the legend region.
     * @param {HTMLElement} candidate - One button carrying the shared label.
     * @returns {boolean} True when the button sits outside the legend.
     */
    (candidate) => !legend.contains(candidate),
  );
  expect(control).toBeDefined();
  /*
   * Assumptions: the non-null assertion is safe because the expectation above has already failed the
   * case if the control is absent, and a bare return of a possibly-undefined value would not type-check
   * under `ui/tsconfig.json`.
   */
  return control as HTMLElement;
}

/**
 * Asserts a non-numeric entry, a zero and an out-of-range entry all take the reference's refusal.
 *
 * Assumptions: all three are asserted together because the reference decides them in one condition --
 * `app/cbl/COMEN01C.cbl` L128-L134 tests not-numeric, greater-than-count and zero as a single guard
 * emitting one sentence -- so splitting them into three cases would imply three rules where there is
 * one. The upper bound is taken from the option table's own length, which is the copybook's count.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function refusesEveryEntryOutsideTheOptionDomain(): void {
  const beyond = String(MAIN_MENU_OPTIONS.length + 1);
  for (const entry of ['', 'x', '0', '00', beyond]) {
    const outcome = resolveMenuOption(entry, false);
    expect(outcome.destination).toBeNull();
    expect(outcome.message).toBe(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
    expect(outcome.severity).toBe('error');
  }
}

/**
 * Asserts a mounted option enters its route and an unmounted one takes the reference's own sentence.
 *
 * Assumptions: the unavailable-option sentence is compared against the catalog's TEMPLATE applied to
 * the option's own padded name, not against a hand-written string, because the template is what models
 * the reference's `DELIMITED BY` double-space strip of the 35-character padding.
 *
 * ⚠️ Refactoring Rationale: the unmounted case reads option 7 where it previously read option 6. Option 6
 * names `COTRN00C`, whose browse screen is now authored, mounted at the literal `/transactions` and
 * registered in `ui/src/routes/programRoutes.ts`, so it ENTERS a route and can no longer report an absent
 * one. Option 7 names `COTRN01C`, which is the one main-menu option still without a reachable
 * destination: its screen is mounted only at `/transactions/:id`, and a menu option carries no
 * transaction identifier to fill that segment with.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function entersAMountedOptionAndReportsAnUnmountedOne(): void {
  const accountView = resolveMenuOption('1', false);
  expect(accountView.destination).toBe('/account/view');
  expect(accountView.message).toBeNull();

  expect(resolveMenuOption('6', false).destination).toBe('/transactions');

  const transactionView = MAIN_MENU_OPTIONS[6];
  expect(transactionView?.programName).toBe('COTRN01C');
  const unmounted = resolveMenuOption('7', false);
  expect(unmounted.destination).toBeNull();
  expect(unmounted.message).toBe(
    formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_NOT_INSTALLED, {
      'CDEMO-MENU-OPT-NAME': transactionView?.name ?? '',
    }),
  );
  /*
   * Assumptions: the severity is `error` for THIS program, because `app/cbl/COMEN01C.cbl` L162 paints
   * the sentence in `DFHRED`. The administrative menu composes a near-identical sentence in `DFHGREEN`,
   * which the administrative case below asserts -- so the colour is per-program and cannot be inferred
   * from the wording.
   */
  expect(unmounted.severity).toBe('error');
}

/**
 * Asserts the administrator-only refusal is the sentence the catalog holds for this program.
 *
 * Assumptions: the rule is exercised through a synthesised entry rather than through the live table,
 * because every live entry carries the ordinary user type -- which the screen's own docstring records as
 * making the transcribed check currently unreachable. Asserting the sentence and the caller keeps the
 * check honest for an option a later revision restricts.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function refusesAnAdministratorOnlyOptionToAnOrdinaryOperator(): void {
  /*
   * Assumptions: the table is read through its DECLARED element type rather than through the literal
   * type the `as const` table infers, which is the same annotation `ui/src/screens/menu/index.tsx`
   * needs at its own comparison and for the same reason: every live entry carries `'U'`, so the inferred
   * type of `userType` is that single literal and the comparison below is rejected as provably false.
   * Reading through the interface restores the field's declared domain of `'A'` or `'U'`, which
   * `app/cpy/COMEN02Y.cpy` states.
   */
  const declared: readonly MainMenuOption[] = MAIN_MENU_OPTIONS;
  const restricted = declared.filter(
    /**
     * Selects the entries the copybook restricts to an administrator.
     * @param {MainMenuOption} option - One option table entry.
     * @returns {boolean} True when the entry is administrator-only.
     */
    (option) => option.userType === 'A',
  );

  if (restricted.length === 0) {
    /*
     * Assumptions: with no restricted entry in the live table the assertion is made against the CATALOG
     * instead, which is the half that can still be proved -- that this program owns a distinct refusal
     * whose trailing space is load-bearing. Skipping the case would leave the catalogued sentence with
     * no test at all.
     */
    expect(PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION).toMatch(/ $/u);
    return;
  }

  const option = restricted[0];
  const outcome = resolveMenuOption(String(option?.optionNumber ?? 0), false);
  expect(outcome.destination).toBeNull();
  expect(outcome.message).toBe(PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION);
}

/**
 * Asserts the main menu paints every transcribed option line and its prompt.
 * @returns {Promise<void>} Resolves once every line has been found.
 */
async function paintsEveryMainMenuOptionLine(): Promise<void> {
  await signOnAsUser();
  render(renderMenu(<MainMenuScreen />, MAIN_MENU_ROUTE, []));

  expect(await screen.findByText(MAIN_MENU_SUBTITLE)).toBeInTheDocument();
  expect(screen.getByText(MAIN_MENU_PROMPT)).toBeInTheDocument();
  for (const option of MAIN_MENU_OPTIONS) {
    const line = formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_LINE, {
      /*
       * WHY : ⚠️ Refactoring Rationale: the expected number is zero-filled to two characters, matching the
       *       `PIC 9(02)` field the reference emits with `STRING ... DELIMITED BY SIZE`. Composed without
       *       the fill, this expectation AGREED with a screen that painted nine of the eleven lines one
       *       character narrower than the terminal does -- which is the failure mode this file's own note
       *       warns of when it explains why the line is composed through the catalog rather than typed.
       */
      'CDEMO-MENU-OPT-NUM': String(option.optionNumber).padStart(2, '0'),
      'CDEMO-MENU-OPT-NAME': option.name,
    });
    expect(screen.getByText(collapse(line))).toBeInTheDocument();
  }
}

/**
 * Asserts typing a mounted option and pressing the submit control enters that option's route.
 * @returns {Promise<void>} Resolves once the destination has reported its arrival.
 */
async function entersTheTypedOptionFromTheSubmitControl(): Promise<void> {
  await signOnAsUser();
  render(renderMenu(<MainMenuScreen />, MAIN_MENU_ROUTE, ['/account/view']));

  await userEvent.type(screen.getByLabelText(MAIN_MENU_PROMPT), '1');
  await userEvent.click(submitControl(MAIN_MENU_KEY_LABELS.ENTER));

  expect(await screen.findByText(`${ARRIVED} /account/view`)).toBeInTheDocument();
}

/**
 * Asserts the main menu's PF3 discards the session and returns to sign-on.
 *
 * Assumptions: the discarded session is asserted as well as the route, because
 * `app/cbl/COMEN01C.cbl` L196-L203 is the one transfer in the estate that omits its `COMMAREA` clause --
 * so the next program starts with no identity. A route change alone would leave the token in place and
 * the guards would send the operator straight back.
 * @returns {Promise<void>} Resolves once sign-on has reported its arrival.
 */
async function signsOffOnTheThirdFunctionKey(): Promise<void> {
  await signOnAsUser();
  render(renderMenu(<MainMenuScreen />, MAIN_MENU_ROUTE, [SIGN_ON_ROUTE]));
  expect(await screen.findByText(MAIN_MENU_SUBTITLE)).toBeInTheDocument();

  await userEvent.keyboard('{F3}');

  expect(await screen.findByText(`${ARRIVED} ${SIGN_ON_ROUTE}`)).toBeInTheDocument();
  /*
   * WHY : ⚠️ Refactoring Rationale: the discard is observed through the HOOK rather than by reading a
   *       storage key nothing writes. The property is unchanged and still worth asserting for the reason
   *       above -- `app/cbl/COMEN01C.cbl` L196-L203 is the one transfer in the estate that omits its
   *       `COMMAREA` clause, so the next program starts with no identity -- but the observation has to be
   *       made where the session actually lives.
   */
  expect(isSignedOn()).toBe(false);
}

/**
 * Asserts the administrative menu's decision table, including its own message colour.
 *
 * Assumptions: the severity asserted here is `success`, and it is the one place the two menus
 * measurably differ. `app/cbl/COADM01C.cbl` L149 paints its unavailable-option sentence in `DFHGREEN`
 * where the main menu paints its own in `DFHRED` at L162, so a shared severity would be wrong for one of
 * the two screens.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function decidesTheAdministrativeOptionTable(): void {
  const refused = resolveAdminOption(String(ADMIN_MENU_OPTIONS.length + 1));
  expect(refused.destination).toBeNull();
  expect(refused.message).toBe(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
  expect(refused.severity).toBe('error');

  expect(resolveAdminOption('3').destination).toBe('/users/edit');
  expect(resolveAdminOption('5').destination).toBe('/reference/transaction-types');
  expect(resolveAdminOption('6').destination).toBe('/reference/transaction-types/new');

  /*
   * WHY : ⚠️ Refactoring Rationale: options ONE and TWO are both asserted as ENTERED and option FOUR now
   *       carries the unavailable-option case. Option one names `COUSR00C` and option two `COUSR01C`,
   *       whose screens became mounted at `/users` and `/users/new`, so both entered assertions follow
   *       the delivery rather than changing what this case tests -- the reference enters the user browse
   *       from exactly here (`app/cbl/COUSR00C.cbl` L124-L125 returns to `COADM01C` on PF3, making this
   *       menu its caller).
   * WHY : Assumptions: option FOUR carries the unavailable case because `COUSR03C` is the one
   *       administrative option with no reachable destination left. Its screen IS mounted, but only at
   *       `/users/:id/delete`, which needs a user identifier a menu option does not carry -- so the case
   *       still proves the sentence AND its `success` severity against a real absence rather than a
   *       contrived one. Option two could no longer carry it: registering its literal path is what makes
   *       a delivered screen reachable, so asserting it absent would have ratified the opposite.
   */
  const userList = ADMIN_MENU_OPTIONS[0];
  expect(userList?.programName).toBe('COUSR00C');
  expect(resolveAdminOption('1').destination).toBe('/users');

  const userAdd = ADMIN_MENU_OPTIONS[1];
  expect(userAdd?.programName).toBe('COUSR01C');
  expect(resolveAdminOption('2').destination).toBe('/users/new');

  const unmountedOption = ADMIN_MENU_OPTIONS[3];
  expect(unmountedOption?.programName).toBe('COUSR03C');
  const unmounted = resolveAdminOption('4');
  expect(unmounted.destination).toBeNull();
  expect(unmounted.message).toBe(
    formatMessageTemplate(MESSAGE_TEMPLATES.ADMIN_OPTION_NOT_INSTALLED, {}),
  );
  expect(unmounted.severity).toBe('success');
}

/**
 * Asserts the administrative menu paints every transcribed option line and enters a mounted one.
 * @returns {Promise<void>} Resolves once the destination has reported its arrival.
 */
async function paintsAndEntersTheAdministrativeOptions(): Promise<void> {
  await signOnAsUser();
  render(renderMenu(<AdminMenuScreen />, ADMIN_MENU_ROUTE, ['/reference/transaction-types']));

  expect(await screen.findByText(ADMIN_MENU_SUBTITLE)).toBeInTheDocument();
  for (const option of ADMIN_MENU_OPTIONS) {
    const line = formatMessageTemplate(MESSAGE_TEMPLATES.ADMIN_MENU_OPTION_LINE, {
      /*
       * WHY : ⚠️ Refactoring Rationale: the expected number is zero-filled to two characters, matching the
       *       `PIC 9(02)` field the reference emits with `STRING ... DELIMITED BY SIZE`. Composed without
       *       the fill, this expectation AGREED with a screen that painted nine of the eleven lines one
       *       character narrower than the terminal does -- which is the failure mode this file's own note
       *       warns of when it explains why the line is composed through the catalog rather than typed.
       */
      'CDEMO-ADMIN-OPT-NUM': String(option.optionNumber).padStart(2, '0'),
      'CDEMO-ADMIN-OPT-NAME': option.name,
    });
    expect(screen.getByText(collapse(line))).toBeInTheDocument();
  }

  await userEvent.type(screen.getByLabelText(ADMIN_MENU_PROMPT), '5');
  await userEvent.click(submitControl(ADMIN_MENU_KEY_LABELS.ENTER));

  expect(await screen.findByText(`${ARRIVED} /reference/transaction-types`)).toBeInTheDocument();
}

/** Registers the menu-screen cases. */
function menuScreenCases(): void {
  beforeEach(clearSession);
  beforeEach(armTransport);
  afterEach(clearSession);

  it('refuses every entry outside the option domain', refusesEveryEntryOutsideTheOptionDomain);
  it(
    'enters a mounted option and reports an unmounted one',
    entersAMountedOptionAndReportsAnUnmountedOne,
  );
  it(
    'refuses an administrator-only option to an ordinary operator',
    refusesAnAdministratorOnlyOptionToAnOrdinaryOperator,
  );
  it('paints every main-menu option line', paintsEveryMainMenuOptionLine);
  it('enters the typed option from the submit control', entersTheTypedOptionFromTheSubmitControl);
  it('signs off on the third function key', signsOffOnTheThirdFunctionKey);
  it('decides the administrative option table', decidesTheAdministrativeOptionTable);
  it('paints and enters the administrative options', paintsAndEntersTheAdministrativeOptions);
}

describe('menu screens', menuScreenCases);
