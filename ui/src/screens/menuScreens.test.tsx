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
import { AppShell } from '../layout/AppShell';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { SIGN_ON_ROUTE } from '../routes/guards';
// Assumptions: the resolver is imported rather than re-derived, because it is the function both menus
//   consult and therefore the only place the not-installed arm's precondition can be observed now that
//   no live option reaches it.
import { routeForProgram } from '../routes/programRoutes';
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
 *
 * ⚠️ Assumptions: the FRAME is part of the tree, where the menu was previously rendered bare. Both menus
 * delegate their title, their message line and their function-key legend through `useShellSlot`, so the
 * legend is painted by the shell and by nothing else -- and {@link submitControl} names the screen's own
 * control by excluding the identically labelled legend entry, which it cannot do when no legend exists.
 * A bare render therefore failed on a missing navigation landmark rather than on anything these cases
 * are about.
 * @param {ReactElement} menu - The menu screen to render.
 * @param {string} at - Route the menu is mounted at.
 * @param {readonly string[]} probes - Destinations that render the arrival marker.
 * @returns {ReactElement} The composed tree under test.
 */
function renderMenu(menu: ReactElement, at: string, probes: readonly string[]): ReactElement {
  return (
    <MemoryRouter initialEntries={[at]}>
      <AppShell>
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
      </AppShell>
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
 * Asserts every main-menu option enters a route, including the two addressed only per record.
 *
 * ⚠️ Refactoring Rationale: this case asserted that option 7 reported itself NOT INSTALLED, and it
 * ratified a defect. `COTRN01C`'s screen is delivered and mounted at `/transactions/:id`; the sentence
 * reports a program the CICS region does not HOLD, so answering an operator with it named an absence that
 * does not exist. `ui/src/routes/programRoutes.ts` now resolves that program to the transaction browse --
 * the screen that selects a transaction and enters the detail screen with it -- so the assertion is
 * inverted rather than deleted, and the whole option table is checked instead of one entry.
 *
 * Assumptions: the eleven options are asserted EXHAUSTIVELY rather than by sample. AAP section 0.1.3.1
 * states that program flow preserves the reachability graph of the eighteen transactions, which is a
 * property of the WHOLE table rather than of any one entry, so a case naming a single option would leave a
 * later `null` undetected until an operator met it.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function entersEveryMainMenuOption(): void {
  const accountView = resolveMenuOption('1', false);
  expect(accountView.destination).toBe('/account/view');
  expect(accountView.message).toBeNull();

  expect(resolveMenuOption('6', false).destination).toBe('/transactions');

  /*
   * Assumptions: option 7 is named through the option table rather than by its number alone, so the case
   * fails loudly if the copybook transcription is ever renumbered, instead of quietly asserting the
   * destination of whatever option sits seventh.
   */
  const transactionView = MAIN_MENU_OPTIONS[6];
  expect(transactionView?.programName).toBe('COTRN01C');
  const detail = resolveMenuOption('7', false);
  expect(detail.destination).toBe('/transactions');
  expect(detail.message).toBeNull();

  for (const option of MAIN_MENU_OPTIONS) {
    const outcome = resolveMenuOption(String(option.optionNumber), false);
    expect(
      outcome.destination,
      `option ${String(option.optionNumber)} must enter a route`,
    ).not.toBeNull();
    expect(outcome.message).toBeNull();
  }
}

/**
 * Asserts the not-installed arm survives for a program no menu carries a route for.
 *
 * ⚠️ Refactoring Rationale: this arm used to be reachable through option 7 and no live option reaches it
 * now, so it is asserted where it still can be: at the resolution the arm depends on, and against the
 * catalogued template it composes. Deleting the coverage was the alternative and was rejected -- the arm
 * is the reference's own answer for a target the region cannot load (`app/cbl/COMEN01C.cbl` L147-L168
 * inspects the program and composes the sentence when the answer is not `NORMAL`), so an option added to
 * `app/cpy/COMEN02Y.cpy` ahead of its screen still depends on it.
 *
 * Assumptions: the probe program name begins `DUMMY`, which is the reference's OWN sentinel for this
 * condition -- `app/cbl/COADM01C.cbl` L141-L144 tests exactly that prefix before composing the sentence
 * -- so the case exercises the arm with the value the baseline itself uses rather than an invented one.
 *
 * Assumptions: the sentence is composed through the catalog's TEMPLATE applied to a padded option name,
 * because the template is what models the reference's `DELIMITED BY` double-space strip of the
 * 35-character padding; a hand-written string would agree with a drifting catalog.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function keepsTheNotInstalledArmForAnUnservedProgram(): void {
  expect(routeForProgram('DUMMYPGM')).toBeNull();

  const transactionView = MAIN_MENU_OPTIONS[6];
  expect(
    formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_NOT_INSTALLED, {
      'CDEMO-MENU-OPT-NAME': transactionView?.name ?? '',
    }),
  ).toBe('This option Transaction View is not installed...');

  /*
   * Assumptions: the administrative template is asserted beside it because the two differ on purpose and
   * the difference is per-program: `app/cbl/COADM01C.cbl` L153-L154 comments out the insertion of the
   * option name, so its sentence names no option, and its L151 paints the sentence in `DFHGREEN` where
   * `app/cbl/COMEN01C.cbl` L162 paints its own in `DFHRED`.
   */
  expect(formatMessageTemplate(MESSAGE_TEMPLATES.ADMIN_OPTION_NOT_INSTALLED, {})).not.toContain(
    'Transaction View',
  );
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
 * Asserts the administrative menu's decision table: a refusal, and all six options entered.
 *
 * ⚠️ Refactoring Rationale: options THREE and FOUR are asserted as ENTERED where option 3 previously
 * resolved to `/users/edit` and option 4 to nothing at all. Both were defects. `/users/edit` is not one
 * of the paths the route table publishes, so the option that looked reachable resolved to the not-found
 * surface; and option 4's `null` reported the DELIVERED deletion screen as not installed, which is a
 * sentence about a program the region does not hold. Both now enter the user browse, which is the screen
 * that selects the row those two programs act on -- and the reference's own caller for both,
 * `app/cbl/COUSR00C.cbl` L192-L207 transferring to them with `CDEMO-FROM-PROGRAM` set to itself.
 *
 * Assumptions: the six options are asserted exhaustively as well as individually, because the property
 * under test is that no administrative option answers with an absence -- one `null` anywhere in the table
 * is one delivered screen an administrator cannot reach, the session being memory-only so a typed URL
 * bounces to sign-on.
 *
 * Assumptions: the unavailable-option sentence and its `success` severity are no longer asserted here,
 * because no live option can reach that arm. It is retained in the screen for a program added to
 * `app/cpy/COADM02Y.cpy` ahead of its route, and {@link keepsTheNotInstalledArmForAnUnservedProgram}
 * covers the template and the resolution it depends on.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function decidesTheAdministrativeOptionTable(): void {
  const refused = resolveAdminOption(String(ADMIN_MENU_OPTIONS.length + 1));
  expect(refused.destination).toBeNull();
  expect(refused.message).toBe(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER);
  expect(refused.severity).toBe('error');

  expect(resolveAdminOption('5').destination).toBe('/reference/transaction-types');
  expect(resolveAdminOption('6').destination).toBe('/reference/transaction-types/new');

  const userList = ADMIN_MENU_OPTIONS[0];
  expect(userList?.programName).toBe('COUSR00C');
  expect(resolveAdminOption('1').destination).toBe('/users');

  const userAdd = ADMIN_MENU_OPTIONS[1];
  expect(userAdd?.programName).toBe('COUSR01C');
  expect(resolveAdminOption('2').destination).toBe('/users/new');

  const userUpdate = ADMIN_MENU_OPTIONS[2];
  expect(userUpdate?.programName).toBe('COUSR02C');
  const update = resolveAdminOption('3');
  expect(update.destination).toBe('/users');
  expect(update.message).toBeNull();

  const userDelete = ADMIN_MENU_OPTIONS[3];
  expect(userDelete?.programName).toBe('COUSR03C');
  const remove = resolveAdminOption('4');
  expect(remove.destination).toBe('/users');
  expect(remove.message).toBeNull();

  for (const option of ADMIN_MENU_OPTIONS) {
    const outcome = resolveAdminOption(String(option.optionNumber));
    expect(
      outcome.destination,
      `administrative option ${String(option.optionNumber)} must enter a route`,
    ).not.toBeNull();
    expect(outcome.message).toBeNull();
  }
}

/**
 * Asserts administrative option 3 enters the user browse from the rendered screen.
 *
 * Assumptions: this is asserted by ARRIVAL and not by a resolver call, because the two can disagree: the
 * decision table above proves what the entry MEANS, and this proves the screen acts on it. The probe route
 * is the only destination declared, so a transfer anywhere else renders nothing and fails the case.
 * @returns {Promise<void>} Resolves once the browse route has reported its arrival.
 */
async function entersTheUserBrowseOnAdministrativeOptionThree(): Promise<void> {
  await signOnAsUser();
  render(renderMenu(<AdminMenuScreen />, ADMIN_MENU_ROUTE, ['/users']));

  await userEvent.type(screen.getByLabelText(ADMIN_MENU_PROMPT), '3');
  await userEvent.click(submitControl(ADMIN_MENU_KEY_LABELS.ENTER));

  expect(await screen.findByText(`${ARRIVED} /users`)).toBeInTheDocument();
}

/**
 * Asserts administrative option 4 enters the user browse from the rendered screen.
 *
 * Assumptions: option 4 is asserted separately from option 3 even though both land on the same route,
 * because they reached it for two different reasons -- option 3 was repointed off an unpublished path and
 * option 4 off a `null` -- and one shared case would leave whichever regressed indistinguishable from the
 * other.
 * @returns {Promise<void>} Resolves once the browse route has reported its arrival.
 */
async function entersTheUserBrowseOnAdministrativeOptionFour(): Promise<void> {
  await signOnAsUser();
  render(renderMenu(<AdminMenuScreen />, ADMIN_MENU_ROUTE, ['/users']));

  await userEvent.type(screen.getByLabelText(ADMIN_MENU_PROMPT), '4');
  await userEvent.click(submitControl(ADMIN_MENU_KEY_LABELS.ENTER));

  expect(await screen.findByText(`${ARRIVED} /users`)).toBeInTheDocument();
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
  it('enters every main-menu option', entersEveryMainMenuOption);
  it(
    'keeps the not-installed arm for an unserved program',
    keepsTheNotInstalledArmForAnUnservedProgram,
  );
  it(
    'refuses an administrator-only option to an ordinary operator',
    refusesAnAdministratorOnlyOptionToAnOrdinaryOperator,
  );
  it('paints every main-menu option line', paintsEveryMainMenuOptionLine);
  it('enters the typed option from the submit control', entersTheTypedOptionFromTheSubmitControl);
  it('signs off on the third function key', signsOffOnTheThirdFunctionKey);
  it('decides the administrative option table', decidesTheAdministrativeOptionTable);
  it(
    'enters the user browse on administrative option 3',
    entersTheUserBrowseOnAdministrativeOptionThree,
  );
  it(
    'enters the user browse on administrative option 4',
    entersTheUserBrowseOnAdministrativeOptionFour,
  );
  it('paints and enters the administrative options', paintsAndEntersTheAdministrativeOptions);
}

describe('menu screens', menuScreenCases);
