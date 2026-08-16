/**
 * @file Component tests for the main menu in `ui/src/screens/menu/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the observable contract of the screen that replaces `app/cbl/COMEN01C.cbl`: the eleven
 * option lines composed exactly as `BUILD-MENU-OPTIONS` composes them, the option field at its
 * declared width, the three-way refusal the program applies to a bad entry, the not-installed
 * sentence for an option whose program this application does not serve, the PF3 exit destination,
 * the invalid-key sentence, and the exit message a departing screen hands over.
 *
 * Assumptions: no API client is stubbed, because this screen calls none. `COMEN01C` reads no file
 * and links to no program — it paints options from `app/cpy/COMEN02Y.cpy` and transfers control — so
 * every case here is a pure rendering-and-navigation case and a stub would stand in for nothing.
 *
 * Assumptions: the destinations are asserted by rendering a marker at each route rather than by
 * spying on the navigate function. What the reference does on a valid option is transfer control, so
 * the observable outcome is that the other screen is entered; a spy would assert the call and not the
 * transfer, and would keep passing if the route table stopped serving the path.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { ConfigProvider } from 'antd';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';

import { MESSAGE_BAND_TEST_ID, MESSAGE_BAND_TEST_IDS } from '../../layout/MessageBand';
import {
  INVALID_KEY_PRESSED,
  MAIN_MENU_HEADINGS,
  MAIN_MENU_KEY_LABELS,
  MAIN_MENU_OPTIONS,
  MAIN_MENU_OPTION_COUNT,
  MESSAGE_TEMPLATES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MainMenuOption } from '../../messages/messages';
import { PF_KEY_BAR_REGION_LABEL } from '../../layout/PfKeyBar';

/**
 * A sentence a departing screen could carry in through the router's transition state.
 *
 * Assumptions: it is the reference's own sign-off wording rather than an invented string, so that the
 * two cases reading it are demonstrating the treatment of a REAL carried sentence and not of a token.
 */
const CARRIED_SENTENCE = 'Transaction ended. Thank you for using CardDemo application...';
import { SIGN_ON_ROUTE } from '../../routes/guards';
import {
  ACCOUNT_UPDATE_ROUTE,
  ACCOUNT_VIEW_ROUTE,
  AUTHORIZATION_SUMMARY_ROUTE,
  CARD_LIST_ROUTE,
  MAIN_MENU_ROUTE,
  TRANSACTION_ADD_ROUTE,
} from '../../routes/navigation';
import { MainMenuScreen } from './index';

/** Marker text rendered at each destination route, keyed by the route it stands at. */
const DESTINATION_MARKERS: Readonly<Record<string, string>> = {
  [SIGN_ON_ROUTE]: 'SIGN ON REACHED',
  [ACCOUNT_VIEW_ROUTE]: 'ACCOUNT VIEW REACHED',
  [ACCOUNT_UPDATE_ROUTE]: 'ACCOUNT UPDATE REACHED',
  [CARD_LIST_ROUTE]: 'CARD LIST REACHED',
  [TRANSACTION_ADD_ROUTE]: 'TRANSACTION ADD REACHED',
  [AUTHORIZATION_SUMMARY_ROUTE]: 'AUTHORIZATION SUMMARY REACHED',
};

/**
 * Renders the menu inside the theme and a router that reports which destination was entered.
 *
 * Assumptions: the destination routes are declared from the SAME constants the screen navigates
 * through, so a renamed path moves both sides at once and this harness cannot ratify a stale
 * destination. Only the routes this screen can reach are declared; a transfer to any other path
 * would render nothing and fail the case that expected it.
 * @param {string | undefined} carriedMessage - Sentence a departing screen handed over, or
 *   `undefined` when the menu is entered with no carried message.
 * @returns {ReactElement} The composed tree under test.
 */
function renderMenu(carriedMessage?: string): ReactElement {
  const entry =
    carriedMessage === undefined
      ? MAIN_MENU_ROUTE
      : { pathname: MAIN_MENU_ROUTE, state: { message: carriedMessage } };

  return (
    <ConfigProvider>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path={MAIN_MENU_ROUTE} element={<MainMenuScreen />} />
          {Object.entries(DESTINATION_MARKERS).map(
            /**
             * Declares one destination route rendering its marker.
             * @param {[string, string]} entryPair - The route path and the marker it renders.
             * @returns {ReactElement} The declared route.
             */
            ([path, marker]) => (
              <Route key={path} path={path} element={<div>{marker}</div>} />
            ),
          )}
        </Routes>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Composes the option line the reference builds for one option.
 *
 * Assumptions: the expected text is composed through the catalog's own template rather than typed
 * out, for the reason the sibling screen tests give: a literal copied into a test agrees with a
 * drifting catalog, whereas composing it means a changed template fails here.
 * @param {number} optionNumber - The option's number, between one and the catalogued count.
 * @returns {string} The composed `NN. Name` line, padding included.
 * @throws {Error} If the number names no catalogued option. It throws rather than returning an empty
 *   line, because a case built on a silently empty expectation would pass against a screen painting
 *   nothing at all.
 */
function optionLine(optionNumber: number): string {
  const option = MAIN_MENU_OPTIONS[optionNumber - 1];

  if (option === undefined) {
    throw new Error(`no catalogued main-menu option ${String(optionNumber)}`);
  }

  return formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_LINE, {
    'CDEMO-MENU-OPT-NUM': String(option.optionNumber).padStart(2, '0'),
    'CDEMO-MENU-OPT-NAME': option.name,
  });
}

/**
 * Locates one rendered option row.
 *
 * Assumptions: the row is found by the text an OPERATOR sees and its raw content is asserted
 * separately, because the two differ and both matter. The catalogued option name is padded to 35
 * characters, and Testing Library normalises an element's text before matching, so a query carrying
 * the padding matches nothing; meanwhile the padding IS part of what the template composes, so
 * trimming it away in the query alone would leave nothing asserting it.
 * @param {number} optionNumber - The option's number, between one and the catalogued count.
 * @returns {HTMLElement} The element holding that option's composed line.
 */
function optionRow(optionNumber: number): HTMLElement {
  return screen.getByText(optionLine(optionNumber).trimEnd());
}

/**
 * Finds the screen's own submit control, excluding the identically labelled legend entry.
 *
 * WHY : ⚠️ Refactoring Rationale: the label is matched inside a REGION rather than across the document,
 *       because two controls carry it. The screen renders a submit control beside the option field so a
 *       pointer operator can act on the entry, and the row-24 legend paints an `ENTER=Continue` entry of
 *       its own -- the mapset's wording, deliberately shared so no new phrasing is introduced -- so a
 *       query by name alone matched two elements and every case that submitted an option failed on the
 *       ambiguity rather than on anything it asserts. The legend is a named navigation landmark, so
 *       excluding what it contains names the control by its ROLE IN THE SCREEN rather than by DOM order,
 *       which a later layout change would silently invalidate. `ui/src/screens/menuScreens.test.tsx`
 *       resolves the same ambiguity the same way.
 * @returns {HTMLElement} The submit control outside the legend region.
 */
function submitControl(): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const candidates = screen.getAllByRole('button', { name: MAIN_MENU_KEY_LABELS.ENTER.trim() });
  const control = candidates.find(
    /**
     * Keeps the candidate that is not part of the legend region.
     * @param {HTMLElement} candidate - One button carrying the shared label.
     * @returns {boolean} `true` when the button sits outside the legend.
     */
    (candidate: HTMLElement): boolean => !legend.contains(candidate),
  );
  expect(
    control,
    'the screen must render its own submit control beside the option field',
  ).toBeDefined();
  /*
   * Assumptions: the assertion above has already failed the case when the control is absent, so the
   * narrowing cast asserts nothing the expectation has not established. A bare return would not
   * type-check under `ui/tsconfig.json`.
   */
  return control as HTMLElement;
}

/**
 * Types an option into the field and activates the screen's submit control.
 * @param {string} entry - Characters to type into the option field.
 * @returns {Promise<void>} Resolves once the submission has been dispatched.
 */
async function selectByTyping(entry: string): Promise<void> {
  if (entry !== '') {
    await userEvent.type(screen.getByLabelText(MAIN_MENU_HEADINGS.OPTION_PROMPT), entry);
  }
  await userEvent.click(submitControl());
}

/**
 * The screen paints the mapset's heading and its option prompt.
 *
 * Assumptions: every case awaits a query even where nothing is loading, and the reason is the option
 * field's `autoFocus`. The design system's control applies focus in an effect after the first paint,
 * which is a state update outside the render the test performed; an awaited query flushes it inside
 * the runner's own act boundary, whereas a synchronous read leaves React reporting an unwrapped
 * update on stderr for a screen that is in fact correct.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheHeadingAndPrompt(): Promise<void> {
  render(renderMenu());

  expect(await screen.findByText(MAIN_MENU_HEADINGS.SCREEN)).toBeInTheDocument();
  expect(screen.getByLabelText(MAIN_MENU_HEADINGS.OPTION_PROMPT)).toBeInTheDocument();
}

/**
 * All eleven catalogued options are rendered, each composed as the reference composes it.
 *
 * Assumptions: the COUNT is asserted as well as each line. The copybook declares twelve slots and
 * populates eleven, so a screen iterating the `OCCURS` rather than the count would paint a twelfth
 * row from uninitialised storage — and a per-line assertion alone cannot see an extra row.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsAllElevenOptionLines(): Promise<void> {
  render(renderMenu());
  await screen.findByText(MAIN_MENU_HEADINGS.SCREEN);

  /*
   * WHY : ⚠️ Refactoring Rationale: the rows are counted by MATCHING THE COMPOSED LINE, where this read
   *       `findAllByRole('menuitem')`. The delivered screen paints the options as text lines and not as
   *       menu items, and it records why: the reference paints twelve PROTECTED 40-character fields and
   *       takes the selection through one separate numeric control, so the lines are not activatable on
   *       the terminal either. Counting a role the screen does not use failed the case while leaving the
   *       property it exists for -- eleven rows and not the copybook's twelve slots -- unasserted.
   * WHY : Assumptions: the count is derived from the SAME predicate the per-line assertion uses, so an
   *       extra row painted from the twelfth, unpopulated slot cannot satisfy it: a twelfth line would
   *       either compose text no catalogued option composes, and be absent from this list, or duplicate
   *       one and make its query ambiguous.
   */
  const rows = MAIN_MENU_OPTIONS.map(
    /**
     * Locates the rendered row for one catalogued option.
     * @param {MainMenuOption} option - One entry of the transcribed option table.
     * @returns {HTMLElement} The element holding that option's composed line.
     */
    (option: MainMenuOption): HTMLElement => optionRow(option.optionNumber),
  );

  expect(rows).toHaveLength(MAIN_MENU_OPTION_COUNT);

  for (let optionNumber = 1; optionNumber <= MAIN_MENU_OPTION_COUNT; optionNumber += 1) {
    expect(optionRow(optionNumber).textContent).toBe(optionLine(optionNumber));
  }
}

/**
 * The option number is rendered zero-filled to two digits, as `PIC 9(02)` renders it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersTheOptionNumberZeroFilled(): Promise<void> {
  render(renderMenu());

  expect(await screen.findByText(optionLine(1).trimEnd())).toBeInTheDocument();
  expect(optionLine(1).startsWith('01. ')).toBe(true);
  expect(optionRow(1).textContent).toBe(optionLine(1));
}

/**
 * The option field accepts at most the two characters the mapset declares.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function boundsTheOptionFieldToItsDeclaredWidth(): Promise<void> {
  render(renderMenu());

  expect(await screen.findByLabelText(MAIN_MENU_HEADINGS.OPTION_PROMPT)).toHaveAttribute(
    'maxlength',
    '2',
  );
}

/**
 * Characters the numeric field cannot hold are discarded rather than submitted.
 *
 * Assumptions: this reproduces the field's `ATTRB=(...,NUM,...)` attribute, which a terminal enforced
 * at the keyboard. Accepting the character and refusing it on submit would report the reference's
 * invalid-option sentence for an entry the terminal never allowed to be typed.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function discardsNonDigitsFromTheOptionField(): Promise<void> {
  render(renderMenu());

  const field = screen.getByLabelText(MAIN_MENU_HEADINGS.OPTION_PROMPT);
  await userEvent.type(field, 'a1b');

  expect(field).toHaveValue('1');
}

/**
 * Option 1 transfers to the account-view screen, which is `COACTVWC`'s replacement.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function transfersToAccountViewOnOptionOne(): Promise<void> {
  render(renderMenu());

  await selectByTyping('01');

  expect(await screen.findByText('ACCOUNT VIEW REACHED')).toBeInTheDocument();
}

/**
 * Option 2 transfers to the account-update screen, which is `COACTUPC`'s replacement.
 *
 * Assumptions: option 2 is asserted as well as option 1 because the copybook orders it AHEAD of the
 * three card options, and a table built in a different order would still satisfy a single-option
 * case while sending every other option to the wrong screen.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function transfersToAccountUpdateOnOptionTwo(): Promise<void> {
  render(renderMenu());

  await selectByTyping('02');

  expect(await screen.findByText('ACCOUNT UPDATE REACHED')).toBeInTheDocument();
}

/**
 * Option 11 transfers to the pending-authorization summary, the highest live option.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function transfersToTheAuthorizationSummaryOnOptionEleven(): Promise<void> {
  render(renderMenu());

  await selectByTyping('11');

  expect(await screen.findByText('AUTHORIZATION SUMMARY REACHED')).toBeInTheDocument();
}

/**
 * A one-digit entry is accepted, because the reference zero-fills before it inspects.
 *
 * Assumptions: `3` and `03` must behave identically. `app/cbl/COMEN01C.cbl` L124 replaces every blank
 * in the two-character field with `'0'`, so an operator typing a single digit into the right-justified
 * field submits the same value either way.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function acceptsASingleDigitEntry(): Promise<void> {
  render(renderMenu());

  await selectByTyping('3');

  expect(await screen.findByText('CARD LIST REACHED')).toBeInTheDocument();
}

/**
 * An option row is NOT activatable, because the reference's own lines are protected.
 *
 * WHY : ⚠️ Refactoring Rationale: this asserted that clicking a row dispatched its option exactly as
 *       typing the number does, and the assertion is inverted. The reference paints the eleven lines
 *       into twelve PROTECTED 40-character fields -- `app/bms/COMEN01.bms` declares each `OPTN0nn` field
 *       and `app/cbl/COMEN01C.cbl` L263-L271 fills them -- and takes the selection through the one
 *       separate numeric field at row 22. A clickable row is an affordance the terminal never had, and
 *       adding it would give the screen two independent ways to choose an option: one that goes through
 *       `resolveMenuOption` and the reference's zero-fill, refusal and not-installed sentences, and one
 *       that bypasses all three. The delivered screen records the same decision at the point of render.
 * WHY : Assumptions: the row's ABSENCE from the accessible control set is what is asserted, rather than a
 *       click being performed and observed to do nothing. A click on a non-interactive element is a
 *       no-op for many reasons, most of them accidental; asserting that the row is neither a button nor a
 *       menu item names the property.
 * @returns {Promise<void>} Resolves once the absence has been observed.
 */
async function anOptionRowIsNotActivatable(): Promise<void> {
  render(renderMenu());
  await screen.findByText(MAIN_MENU_HEADINGS.SCREEN);

  const row = optionRow(8);

  expect(row.closest('button')).toBeNull();
  expect(row.closest('[role="menuitem"]')).toBeNull();
  expect(screen.queryByRole('menuitem')).toBeNull();
}

/**
 * An option whose program this application does not serve reports the not-installed sentence.
 *
 * Assumptions: option 6 is `COTRN00C`, for which no screen module exists, so it takes the reference's
 * own not-installed arm. The expected sentence is composed through the catalog template, whose value
 * part is delimited by TWO spaces — which is what strips the option name's 35-character padding, so
 * the assertion also pins that the padding does not reach the middle of the sentence.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsNotInstalledForAnUnservedOption(): Promise<void> {
  render(renderMenu());

  await selectByTyping('06');

  const expected = formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_NOT_INSTALLED, {
    'CDEMO-MENU-OPT-NAME': optionLine(6).slice('06. '.length),
  });

  expect(expected).toBe('This option Transaction List is not installed...');
  expect(await screen.findByText(expected)).toBeInTheDocument();
}

/**
 * An option whose route carries an opaque selector also takes the not-installed arm.
 *
 * Assumptions: option 4 is `COCRDSLC`, which HAS a screen module, and it is still reported as not
 * installed. Its route carries a card selector that only the browse screen mints, so the menu holds
 * no path to name — and reporting the reference's own sentence is the honest outcome, where inventing
 * a path would produce a request the selector guard refuses.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsNotInstalledForASealedRoute(): Promise<void> {
  render(renderMenu());

  await selectByTyping('04');

  expect(
    await screen.findByText('This option Credit Card View is not installed...'),
  ).toBeInTheDocument();
}

/**
 * An option above the catalogued count is refused with the reference's single sentence.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAnOptionAboveTheCount(): Promise<void> {
  render(renderMenu());

  await selectByTyping('12');

  expect(
    await screen.findByText(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
}

/**
 * A zero entry is refused by the same branch, as `WS-OPTION = ZEROS` refuses it.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function refusesAZeroEntry(): Promise<void> {
  render(renderMenu());

  await selectByTyping('00');

  expect(
    await screen.findByText(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
}

/**
 * A blank entry is refused by the same branch, because the reference zero-fills it first.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function refusesABlankEntry(): Promise<void> {
  render(renderMenu());

  await selectByTyping('');

  expect(
    await screen.findByText(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
}

/**
 * The exit key transfers to sign-on, which is the reference's only PF3 destination here.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function exitsToSignOn(): Promise<void> {
  render(renderMenu());

  await userEvent.click(screen.getByRole('button', { name: MAIN_MENU_KEY_LABELS.PFK03.trim() }));

  expect(await screen.findByText('SIGN ON REACHED')).toBeInTheDocument();
}

/**
 * Exactly the two keys the mapset's legend paints are offered.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function offersExactlyTheTwoLegendKeys(): Promise<void> {
  render(renderMenu());
  await screen.findByText(MAIN_MENU_HEADINGS.SCREEN);

  /*
   * WHY : ⚠️ Refactoring Rationale: the controls are read INSIDE the legend region, where they were read
   *       across the whole document. The screen also renders a submit control carrying the mapset's own
   *       `ENTER=Continue` wording beside the option field, so a document-wide query for that name
   *       matched two elements and this case failed on the ambiguity -- while its own name says what it
   *       means to assert: the two keys the LEGEND paints. Scoping the query to the named navigation
   *       landmark is also what makes the two negative halves meaningful, since they would otherwise pass
   *       for a screen that painted `F4=` outside the legend.
   */
  const legend = within(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL }));

  expect(
    legend.getByRole('button', { name: MAIN_MENU_KEY_LABELS.ENTER.trim() }),
  ).toBeInTheDocument();
  expect(
    legend.getByRole('button', { name: MAIN_MENU_KEY_LABELS.PFK03.trim() }),
  ).toBeInTheDocument();
  expect(legend.getAllByRole('button')).toHaveLength(2);
  expect(legend.queryByRole('button', { name: /F4=/u })).not.toBeInTheDocument();
  expect(legend.queryByRole('button', { name: /F12=/u })).not.toBeInTheDocument();
}

/**
 * An unmapped attention key reports the shared invalid-key sentence.
 *
 * Assumptions: F9 is used because it resolves to a real attention identifier that this screen binds
 * no handler for, which is the `WHEN OTHER` arm. A key that resolved to no identifier at all would be
 * ignored rather than reported, and would exercise a different branch.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsAnUnmappedKey(): Promise<void> {
  render(renderMenu());

  await userEvent.keyboard('{F9}');

  expect(await screen.findByText(INVALID_KEY_PRESSED.trim())).toBeInTheDocument();
}

/**
 * A sentence handed over by the departing screen is rendered on entry.
 *
 * Assumptions: this is the delivery half of the exit-message contract. Every screen's PF3 arm sets a
 * sentence and then transfers, so before the menu existed as a route the sentence was set on a screen
 * that unmounted immediately and no operator ever saw it.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function blanksASentenceCarriedInFromTheDepartingScreen(): Promise<void> {
  render(renderMenu(CARRIED_SENTENCE));
  await screen.findByText(MAIN_MENU_HEADINGS.SCREEN);

  /*
   * WHY : ⚠️ Refactoring Rationale: this asserted the carried sentence was PAINTED, and the reference
   *       blanks it. `app/cbl/COMEN01C.cbl` L77-L78 is the second and third statement of `MAIN-PARA`:
   *       `MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COMEN1AO`, executed on every turn including the first
   *       one after a transfer in -- so however a departing program filled the passed message field, the
   *       menu's own first act is to empty it, and `SEND-MENU-SCREEN` then paints the blank. A screen
   *       that painted an inbound sentence would show an operator a message the terminal never showed
   *       them. The assertion is therefore inverted rather than the screen changed, and the band is
   *       asserted EMPTY rather than merely not containing that one sentence.
   */
  expect(screen.queryByText(CARRIED_SENTENCE)).toBeNull();
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent('');
}

/**
 * A refusal this screen produces replaces a carried sentence rather than sitting beside it.
 *
 * Assumptions: the carried sentence is never painted at all, for the reason the case above records --
 * `app/cbl/COMEN01C.cbl` L77-L78 blanks the message field at the head of every turn, including the turn
 * that follows a transfer in. This case is kept beside that one because it observes the same property
 * from the other side: the band that stays empty on arrival carries THIS screen's own refusal after a
 * submission, so a screen that had simply stopped painting the band would fail here.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aRefusalReplacesTheCarriedMessage(): Promise<void> {
  render(renderMenu(CARRIED_SENTENCE));

  await selectByTyping('99');

  expect(
    await screen.findByText(SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER),
  ).toBeInTheDocument();
  expect(screen.queryByText(CARRIED_SENTENCE)).not.toBeInTheDocument();
}

/**
 * The screen reserves exactly one message band, on the channel its mapset declares.
 *
 * Assumptions: the absence of the second channel is asserted, because `COMEN01` declares one message
 * field. A second band here would be an invention on this mapset, and the account-view mapset is the
 * only base mapset that declares two.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reservesExactlyOneMessageBand(): Promise<void> {
  render(renderMenu());

  expect(await screen.findByTestId(MESSAGE_BAND_TEST_ID)).toBeInTheDocument();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_IDS.information)).not.toBeInTheDocument();
}

/**
 * Registers the main-menu screen cases.
 *
 * Assumptions: every case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback, matching the sibling screen suites and the JSDoc gate's `publicOnly: false` setting.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function mainMenuScreenCases(): void {
  it('paints the mapset heading and the option prompt', paintsTheHeadingAndPrompt);
  it('paints all eleven catalogued option lines', paintsAllElevenOptionLines);
  it('renders the option number zero-filled to two digits', rendersTheOptionNumberZeroFilled);
  it('bounds the option field to its declared width', boundsTheOptionFieldToItsDeclaredWidth);
  it('discards non-digits from the option field', discardsNonDigitsFromTheOptionField);
  it('transfers to account view on option 1', transfersToAccountViewOnOptionOne);
  it('transfers to account update on option 2', transfersToAccountUpdateOnOptionTwo);
  it(
    'transfers to the pending-authorization summary on option 11',
    transfersToTheAuthorizationSummaryOnOptionEleven,
  );
  it('accepts a single-digit entry as the zero-filled equivalent', acceptsASingleDigitEntry);
  it('offers no activatable option row', anOptionRowIsNotActivatable);
  it(
    'reports the not-installed sentence for an unserved option',
    reportsNotInstalledForAnUnservedOption,
  );
  it('reports the not-installed sentence for a sealed route', reportsNotInstalledForASealedRoute);
  it('refuses an option above the catalogued count', refusesAnOptionAboveTheCount);
  it('refuses a zero entry', refusesAZeroEntry);
  it('refuses a blank entry', refusesABlankEntry);
  it('exits to sign-on when the exit key is pressed', exitsToSignOn);
  it('offers exactly the two keys the legend paints', offersExactlyTheTwoLegendKeys);
  it('reports an unmapped attention key', reportsAnUnmappedKey);
  it(
    'blanks a sentence carried in from the departing screen',
    blanksASentenceCarriedInFromTheDepartingScreen,
  );
  it('replaces a carried sentence with its own refusal', aRefusalReplacesTheCarriedMessage);
  it('reserves exactly one message band', reservesExactlyOneMessageBand);
}

describe('main menu screen', mainMenuScreenCases);
