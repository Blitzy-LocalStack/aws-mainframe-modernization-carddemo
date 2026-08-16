/**
 * @file Component tests for the two account screens: `ui/src/screens/accountView/index.tsx` and
 * `ui/src/screens/accountUpdate/index.tsx`.
 *
 * Purpose
 * -------
 * Cover the contract each screen presents to an operator: the opening state and whether a request
 * runs on mount, the filter field at its declared width, the information and refusal sentences
 * verbatim from the message catalog, the function-key legend the mapset paints, a successful read
 * rendering the record, a refused read reporting the reference's own sentence, and the navigation the
 * exit key performs.
 *
 * Assumptions: whether the opening state issues a request is asserted explicitly on both screens,
 * because it is the property most easily broken by an ordinary-looking change and the least visible
 * once broken. Neither reference transaction reads anything on entry -- each paints its prompt and
 * waits for a key -- so a screen that fetched on mount would issue a request for an account nobody
 * asked for, and with an empty filter at that.
 *
 * Assumptions: every catalogued literal is compared after collapsing its whitespace. The catalog
 * carries each sentence and label byte-exact, including the interior and trailing pad its fixed-width
 * field declared, and Testing Library normalises the DOM side but takes the expected string as given
 * -- so a padded literal never matches its own rendering. Collapsing applies one normalisation to
 * both sides while keeping the catalog as the single source of the text.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { isConflictFailure, readAccountView, updateAccount } from '../api/accounts';
import type { AccountViewResponse } from '../api/accounts';
import { AppShell } from '../layout/AppShell';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
/*
 * WHY : ⚠️ Refactoring Rationale: the account-view labels and its one key label are imported from the
 *       message CATALOG, and they used to be imported from the screen module as
 *       `ACCOUNT_BLOCK_FIELD_LABELS`, `CUSTOMER_BLOCK_FIELD_LABELS` and `ACCOUNT_VIEW_KEY_LABELS`. The
 *       screen no longer declares them: every user-visible string in this tree is catalogued in one
 *       place, and those three groups were the last declared beside the component that renders them.
 *       The member spellings change with the move -- the catalog uses `ACCOUNT_NUMBER` where the local
 *       group used `accountNumber` -- so the reads below are renamed rather than merely re-pointed.
 */
import {
  ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS,
  ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS,
  ACCOUNT_VIEW_KEY_LABELS,
  STATUS_MESSAGES,
} from '../messages/messages';
import { MAIN_MENU_ROUTE } from '../routes/navigation';
import { cardDemoTheme } from '../theme/antdTheme';
import {
  ACCOUNT_VIEW_PROGRAM_NAME,
  ACCOUNT_VIEW_TRANSACTION_ID,
  AccountViewScreen,
} from './accountView';
import {
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED,
  ACCOUNT_UPDATE_FIELD_WIDTHS,
  ACCOUNT_UPDATE_KEY_LABELS,
  ACCOUNT_UPDATE_PROGRAM_NAME,
  ACCOUNT_UPDATE_TRANSACTION_ID,
  AccountUpdateScreen,
  fieldDomId,
} from './accountUpdate';

const VIEW_MESSAGES = STATUS_MESSAGES.COACTVWC;
const UPDATE_MESSAGES = STATUS_MESSAGES.COACTUPC;

const ACCOUNT_ID = '00000000011';
const ACCOUNT_ID_DECLARED_WIDTH = 11;
const MENU_MARKER = 'MAIN MENU REACHED';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: this is a hoisted function DECLARATION rather than an inline factory, which is what
 * makes it usable at all -- Vitest lifts every `vi.mock` call above the imports, so a factory held in
 * a `const` would be in its temporal dead zone at registration time.
 *
 * Assumptions: `isConflictFailure` is stubbed alongside the two operations rather than left real,
 * because the update screen classifies a rejection through it and a real implementation would inspect
 * an axios error this suite does not construct. Each case that cares sets its answer.
 * @returns {Record<string, unknown>} The transport surface, each member a fresh spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    readAccountView: vi.fn(),
    updateAccount: vi.fn(),
    isConflictFailure: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it. These cases
 * assert what a SCREEN does with an outcome, so the shortest honest seam is the function the screen
 * calls; going through the client would additionally exercise the interceptor chain and make a
 * presentation regression indistinguishable from a transport one.
 */
vi.mock('../api/accounts', mockAccountTransportModule);

/**
 * Collapses runs of whitespace the way Testing Library's default normaliser does.
 * @param {string} value - Catalogued literal carrying its declared pad.
 * @returns {string} The same text with whitespace runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Returns the legend region the shared key bar paints.
 * @returns {HTMLElement} The legend region.
 */
function legendRegion(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads the legend's controls as collapsed labels, in painted order.
 *
 * Refactoring Rationale: the reading is a named function rather than an inline `map` at each call
 * site. `ui/eslint.config.js` selects a function expression in every position with
 * `publicOnly: false`, so each inline callback would owe its own JSDoc block -- and three call sites
 * would each carry a block documenting the same one-line projection.
 * @returns {readonly string[]} The collapsed label of every control the legend paints.
 */
function legendLabels(): readonly string[] {
  return Array.from(legendRegion().querySelectorAll('button')).map(collapsedTextOf);
}

/**
 * Reads one element's text with its whitespace collapsed.
 * @param {Element} element - Element whose text is read.
 * @returns {string} The collapsed text.
 */
function collapsedTextOf(element: Element): string {
  return collapse(element.textContent ?? '');
}

/**
 * A composed account view, populated so every rendered member is distinguishable.
 *
 * Assumptions: the two sensitive members arrive ALREADY masked, because that is what the service
 * returns -- the mapping layer masks the national identifier and the government-issued reference
 * before they leave the service. A fixture carrying unmasked values would put identifier-shaped
 * literals in a test file for no assertion's benefit, and would assert a shape the transport never
 * produces.
 * @returns {AccountViewResponse} The composed read a successful lookup returns.
 */
function accountView(): AccountViewResponse {
  return {
    accountId: ACCOUNT_ID,
    account: {
      activeStatus: 'Y',
      openDate: '2015-03-01',
      creditLimit: '5000.00',
      expirationDate: '2026-03-01',
      cashCreditLimit: '1500.00',
      reissueDate: '2023-03-01',
      currentBalance: '1234.56',
      currentCycleCredit: '250.00',
      groupId: 'ZEROAPR',
      currentCycleDebit: '75.25',
    },
    customer: {
      customerId: '000000011',
      ssnMasked: '***-**-6789',
      dateOfBirth: '1980-07-04',
      ficoCreditScore: '742',
      firstName: 'PAUL',
      middleName: 'T',
      lastName: 'BUCK',
      addressLine1: '742 EVERGREEN TERRACE',
      addressLine2: 'APT 4B',
      stateCode: 'IL',
      zipCode: '60007',
      city: 'ELK GROVE',
      countryCode: 'USA',
      phoneNumber1: '(312)5550101',
      governmentIssuedIdMasked: '****4321',
      phoneNumber2: '(312)5550202',
      eftAccountId: '00000000012345678901',
      primaryCardHolderIndicator: 'Y',
    },
    informationMessage: null,
    returnMessage: null,
  };
}

/**
 * Renders one screen at a path, with the main menu mounted so navigation is observable.
 *
 * Assumptions: the menu destination is a marker element rather than the real menu screen. What these
 * cases establish is that the exit key LEAVES for the menu route; that the menu itself renders is
 * established where the menu screens and the route table are tested, and mounting it here would pull
 * a second screen's data requirements into an account case.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderScreen(element: ReactElement): void {
  /*
   * WHY : ⚠️ Refactoring Rationale: the `AppShell` is INSIDE this tree, where this helper rendered the
   *       screen bare and a second helper existed for the one screen that needed the frame. Both account
   *       screens now DELEGATE their title band, their row-23 message line and their key legend to the
   *       one shell `ui/src/App.tsx` mounts, publishing them through `useShellSlot`, so a bare screen
   *       paints none of the three and every case reading the band or the legend failed for the wrong
   *       reason -- reporting a missing element where the sentence was in fact correct. The two helpers
   *       therefore collapse into this one, which is the production shape.
   * WHY : Assumptions: the frame is given unconfigured, matching the route table: it binds no function
   *       key of its own while a screen has published keys, so its sign-off control cannot contend with
   *       a screen's own bindings.
   */
  render(
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/account']}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/account" element={element} />
            <Route path={MAIN_MENU_ROUTE} element={<div>{MENU_MARKER}</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ConfigProvider>,
  );
}

/**
 * Reads the row-22 informational band the view mapset declares.
 *
 * ⚠️ Refactoring Rationale: this used to take the FIRST of two elements carrying `MESSAGE_BAND_TEST_ID`
 * and assert there were exactly two, because both of this mapset's message lines were painted by the
 * screen under one handle -- so a single-element query was ambiguous by construction and indexing was
 * the only way to keep the ORDER under test. `ui/src/layout/MessageBand.tsx` now names the two lines
 * apart: the row-22 line takes `INFORMATION_BAND_TEST_ID` and the row-23 line keeps
 * `MESSAGE_BAND_TEST_ID`, and the row-23 line is delegated to the shell. Asking for the row-22 handle
 * singularly is therefore both unambiguous AND a stronger assertion than the count it replaces -- it
 * fails if a screen ever paints two informational lines, which an index would silently accept.
 * @returns {Promise<HTMLElement>} The informational band.
 */
async function informationBand(): Promise<HTMLElement> {
  return screen.findByTestId(INFORMATION_BAND_TEST_ID);
}

/**
 * Reads the account filter control, which both screens label with the mapset's own caption.
 * @param {string} label - The painted caption for the filter.
 * @returns {HTMLElement} The filter control.
 */
function filterControl(label: string): HTMLElement {
  return screen.getByLabelText(collapse(label));
}

/**
 * Builds a predicate matching a control whose collapsed label equals the wanted one.
 * @param {string} wanted - The collapsed label to match.
 * @returns {(element: Element) => boolean} The predicate.
 */
function matchesCollapsedLabel(wanted: string): (element: Element) => boolean {
  return matches;

  /**
   * Reports whether this element carries the wanted label.
   * @param {Element} element - Candidate control.
   * @returns {boolean} Whether the label matches.
   */
  function matches(element: Element): boolean {
    return collapsedTextOf(element) === wanted;
  }
}

/**
 * Builds an assertion that the legend paints a control with the wanted label.
 * @param {string} wanted - The collapsed label the legend must paint.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertLegendPaints(wanted: string): () => void {
  return assertPainted;

  /**
   * Asserts the label is among those the legend paints.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertPainted(): void {
    expect(legendLabels(), `the legend must paint ${wanted}`).toContain(wanted);
  }
}

/**
 * Activates a legend control by its painted label.
 * @param {string} label - The legend label as the mapset paints it.
 * @returns {Promise<void>} Resolves once the control has been activated.
 */
async function pressLegendKey(label: string): Promise<void> {
  const wanted = collapse(label);
  await waitFor(assertLegendPaints(wanted));
  const [control] = Array.from(legendRegion().querySelectorAll('button')).filter(
    matchesCollapsedLabel(wanted),
  );
  await userEvent.click(control as HTMLElement);
}

/**
 * Resets the transport spies so no case inherits another's answer.
 * @returns {void} Nothing.
 */
function resetTransport(): void {
  vi.mocked(readAccountView).mockReset();
  vi.mocked(updateAccount).mockReset();
  vi.mocked(isConflictFailure).mockReset();
  vi.mocked(isConflictFailure).mockReturnValue(false);
}

/**
 * The view screen opens on its prompt, with an empty filter and no request issued.
 * @returns {Promise<void>} Resolves once the opening state has been asserted.
 */
async function theViewScreenOpensOnItsPromptWithoutReading(): Promise<void> {
  renderScreen(<AccountViewScreen />);

  expect(await informationBand()).toHaveTextContent(
    collapse(VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text),
  );
  expect(filterControl(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER)).toHaveValue('');
  expect(readAccountView).not.toHaveBeenCalled();
}

/**
 * The view screen's filter carries the eleven-character width three reference declarations fix.
 * @returns {Promise<void>} Resolves once the width has been asserted.
 */
async function theViewFilterCarriesItsDeclaredWidth(): Promise<void> {
  renderScreen(<AccountViewScreen />);

  await informationBand();
  expect(filterControl(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER)).toHaveAttribute(
    'maxLength',
    String(ACCOUNT_ID_DECLARED_WIDTH),
  );
}

/**
 * The view screen paints its identity and its single legend key, and advertises no other.
 *
 * Assumptions: the ENTER control is asserted ABSENT. `app/bms/COACTVW.bms` paints `'  F3=Exit '` as
 * the whole legend even though the program admits ENTER, so a legend that advertised ENTER would paint
 * a key the terminal did not -- and it would do so plausibly, which is why it is worth an assertion.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theViewScreenPaintsOneLegendKeyOnly(): Promise<void> {
  renderScreen(<AccountViewScreen />);

  expect(await screen.findByText(ACCOUNT_VIEW_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(ACCOUNT_VIEW_PROGRAM_NAME)).toBeInTheDocument();

  expect(legendLabels()).toEqual([collapse(ACCOUNT_VIEW_KEY_LABELS.PFK03)]);
}

/**
 * A successful read renders both blocks and states the reference's own information sentence.
 *
 * Assumptions: one value from each block is asserted rather than all twenty-nine, and the two chosen
 * are the masked ones. They are the values whose CORRECTNESS is a policy decision rather than a
 * transcription: an unmasked national identifier or government reference reaching the screen is the
 * regression worth a case, and the surrounding fields are established by the mapping tests that own
 * them.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function aSuccessfulReadRendersBothBlocks(): Promise<void> {
  vi.mocked(readAccountView).mockResolvedValue({ account: accountView(), revision: 'W/"1"' });

  renderScreen(<AccountViewScreen />);

  await userEvent.type(filterControl(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER), ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits until the composed read has been rendered.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByText('***-**-6789')).toBeInTheDocument();
    },
  );
  expect(readAccountView).toHaveBeenCalledWith(ACCOUNT_ID);
  expect(screen.getByText('****4321')).toBeInTheDocument();
  expect(
    screen.getByText(collapse(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.CUSTOMER_ID)),
  ).toBeInTheDocument();
  /*
   * WHY : ⚠️ Refactoring Rationale: the informational line is asserted to hold the PROMPT and not
   *       `WS_INFORM_OUTPUT`. That sentence -- `Displaying details of given Account` -- is declared at
   *       `app/cbl/COACTVWC.cbl` L115-L116 as an `88`-level value and `SET` nowhere in the program,
   *       while L528-L530 restores the prompt whenever the field is empty, so the reference's
   *       information line is the prompt in every state including a successful read. The divergence that
   *       showed it is withdrawn and its register entry, D-12, now records the withdrawal; the ABSENCE
   *       is asserted beside the presence so a reinstatement fails here.
   */
  expect(await informationBand()).toHaveTextContent(
    collapse(VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text),
  );
  expect(screen.queryByText(collapse(VIEW_MESSAGES.WS_INFORM_OUTPUT.text))).not.toBeInTheDocument();
}

/**
 * A refused read reports the reference's own read-failure sentence, never the thrown message.
 *
 * Assumptions: the expected sentence is `XREF_READ_ERROR` and not the not-found one, because the
 * rejection carries NO problem document -- the screen's own note records that a document-less
 * rejection is reported with the read-failure sentence, since the client's `RangeError` text is
 * developer-facing and the account identifier is a value this migration keeps out of any durable
 * diagnostic. Asserting the not-found sentence here would pin the wrong branch and would pass only if
 * the screen stopped distinguishing the two.
 *
 * Assumptions: the absence of the thrown message is asserted as well as the presence of the catalogued
 * one. That is the property that matters: relaying a thrown message is the one route by which an
 * identifier or an internal detail could reach the operator's screen and any log that captures it.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aRefusedReadReportsTheReferenceSentence(): Promise<void> {
  vi.mocked(readAccountView).mockRejectedValue(new Error('transport'));

  renderScreen(<AccountViewScreen />);

  await userEvent.type(filterControl(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER), ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  const alert = await screen.findByRole('alert');
  await waitFor(assertAlertStates(alert, collapse(VIEW_MESSAGES.XREF_READ_ERROR.text)));
  expect(alert.textContent).not.toContain('transport');
}

/**
 * Builds an assertion that an alert has settled on the wanted sentence.
 * @param {HTMLElement} alert - The alert whose text is read.
 * @param {string} wanted - The collapsed sentence the alert must carry.
 * @returns {() => void} The assertion, suitable for `waitFor`.
 */
function assertAlertStates(alert: HTMLElement, wanted: string): () => void {
  return assertStated;

  /**
   * Asserts the alert carries the wanted sentence.
   * @returns {void} Nothing; the assertion carries the outcome.
   */
  function assertStated(): void {
    expect(alert).toHaveTextContent(wanted);
  }
}

/**
 * A malformed filter is refused before any request is issued.
 *
 * Assumptions: the absence of a call is the load-bearing half. The reference edits the filter before
 * it reads anything, so a screen that requested first and reported afterwards would produce the same
 * visible sentence while sending a malformed identifier to the service on every keystroke-completed
 * turn.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aMalformedFilterIsRefusedWithoutReading(): Promise<void> {
  renderScreen(<AccountViewScreen />);

  await userEvent.type(filterControl(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER), '123');
  await userEvent.keyboard('{Enter}');

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).not.toBe('');
  expect(readAccountView).not.toHaveBeenCalled();
}

/**
 * The view screen's exit key returns to the main menu.
 * @returns {Promise<void>} Resolves once the menu has been reached.
 */
async function theViewExitKeyReturnsToTheMenu(): Promise<void> {
  renderScreen(<AccountViewScreen />);

  await informationBand();
  await pressLegendKey(ACCOUNT_VIEW_KEY_LABELS.PFK03);

  expect(await screen.findByText(MENU_MARKER)).toBeInTheDocument();
}

/**
 * The update screen opens on its search prompt, with no request issued.
 * @returns {Promise<void>} Resolves once the opening state has been asserted.
 */
async function theUpdateScreenOpensOnItsSearchPromptWithoutReading(): Promise<void> {
  renderScreen(<AccountUpdateScreen />);

  /*
   * WHY : ⚠️ Refactoring Rationale: the prompt is read from the row-22 INFORMATION band and this asked
   *       the row-23 handle for it. `PROMPT-FOR-SEARCH-KEYS` is an `88`-level value of `WS-INFO-MSG` at
   *       `app/cbl/COACTUPC.cbl` L468-L469, and `3250-SETUP-INFOMSG` moves that field into `INFOMSGO` at
   *       L2979 -- the row-22 field -- never into `ERRMSGO`. The two handles were indistinguishable while
   *       both bands carried `message-band`, so the query resolved to whichever came first; now that
   *       `ui/src/layout/MessageBand.tsx` names them apart, asking the wrong one finds an empty band.
   */
  const band = await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  expect(band).toHaveTextContent(collapse(UPDATE_MESSAGES.PROMPT_FOR_SEARCH_KEYS.text));
  expect(readAccountView).not.toHaveBeenCalled();
  expect(updateAccount).not.toHaveBeenCalled();
}

/**
 * The update screen paints its identity and the four legend keys the mapset advertises.
 *
 * Assumptions: only ENTER and PF3 are asserted present on OPENING, because the save and cancel keys
 * are bound once a record has been fetched -- the reference offers nothing to save or cancel before
 * then. Asserting all four here would pin a legend the reference does not paint in this state.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theUpdateScreenPaintsItsOpeningLegend(): Promise<void> {
  renderScreen(<AccountUpdateScreen />);

  expect(await screen.findByText(ACCOUNT_UPDATE_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(ACCOUNT_UPDATE_PROGRAM_NAME)).toBeInTheDocument();

  const labels = legendLabels();
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.ENTER));
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK03));
}

/**
 * Every declared field width reaches the control that edits it.
 *
 * Assumptions: the widths are read from the exported table and applied to the controls the screen
 * renders, rather than spot-checked, because the table is the transcription of the mapset's field
 * lengths and a control that lost its bound would accept an entry the service must then refuse. The
 * sweep skips a member the screen does not render as a bounded text control -- the amounts are
 * deliberately unbounded so a malformed entry can reach the service and be reported in the
 * reference's own words.
 * @returns {Promise<void>} Resolves once every rendered control has been checked.
 */
async function everyDeclaredWidthReachesItsControl(): Promise<void> {
  vi.mocked(readAccountView).mockResolvedValue({ account: accountView(), revision: 'W/"1"' });

  renderScreen(<AccountUpdateScreen />);

  await userEvent.type(filterControl(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.accountId), ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits until the fetched record has populated the form.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(document.getElementById(fieldDomId('lastName'))).not.toBeNull();
    },
  );

  const checked: string[] = [];
  for (const [field, width] of Object.entries(ACCOUNT_UPDATE_FIELD_WIDTHS)) {
    const control = document.getElementById(fieldDomId(field as never));
    if (control === null) {
      continue;
    }
    const declared = control.getAttribute('maxLength');
    if (declared === null) {
      continue;
    }
    expect(declared, `${field} must carry its declared width`).toBe(String(width));
    checked.push(field);
  }
  expect(checked.length, 'the sweep must reach at least the identifier fields').toBeGreaterThan(5);
}

/**
 * A fetched record populates the form and states the change prompt.
 * @returns {Promise<void>} Resolves once the record has been fetched.
 */
async function aFetchedRecordPopulatesTheFormAndPrompt(): Promise<void> {
  vi.mocked(readAccountView).mockResolvedValue({ account: accountView(), revision: 'W/"1"' });

  renderScreen(<AccountUpdateScreen />);

  await userEvent.type(filterControl(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.accountId), ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits until the fetched surname has reached its control.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(document.getElementById(fieldDomId('lastName'))).toHaveValue('BUCK');
    },
  );
  expect(readAccountView).toHaveBeenCalledWith(ACCOUNT_ID);
  /*
   * WHY : ⚠️ Refactoring Rationale: the row-22 handle, for the same reason as the opening prompt above --
   *       `PROMPT-FOR-CHANGES` is another `88`-level value of `WS-INFO-MSG` (`app/cbl/COACTUPC.cbl`
   *       L470-L471) that `3250-SETUP-INFOMSG` sets when a record has been fetched and moves into
   *       `INFOMSGO`. The row-23 line carries only what `report` writes, which on this turn is nothing.
   */
  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(
    collapse(UPDATE_MESSAGES.PROMPT_FOR_CHANGES.text),
  );
}

/**
 * A refused fetch reports the reference's not-found sentence.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function aRefusedFetchReportsTheReferenceSentence(): Promise<void> {
  vi.mocked(readAccountView).mockRejectedValue(new Error('transport'));

  renderScreen(<AccountUpdateScreen />);

  await userEvent.type(filterControl(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.accountId), ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  const alert = await screen.findByRole('alert');
  expect(alert.textContent).not.toBe('');
  expect(alert.textContent).not.toContain('transport');
}

/**
 * The update screen's exit key returns to the main menu.
 * @returns {Promise<void>} Resolves once the menu has been reached.
 */
async function theUpdateExitKeyReturnsToTheMenu(): Promise<void> {
  renderScreen(<AccountUpdateScreen />);

  await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await pressLegendKey(ACCOUNT_UPDATE_KEY_LABELS.PFK03);

  expect(await screen.findByText(MENU_MARKER)).toBeInTheDocument();
}

/**
 * Registers the account-screen cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function accountScreenCases(): void {
  beforeEach(resetTransport);
  afterEach(resetTransport);

  it(
    'opens the view screen on its prompt without reading',
    theViewScreenOpensOnItsPromptWithoutReading,
  );
  it('bounds the view filter to its declared width', theViewFilterCarriesItsDeclaredWidth);
  it('paints one legend key on the view screen', theViewScreenPaintsOneLegendKeyOnly);
  it('renders both blocks of a successful read', aSuccessfulReadRendersBothBlocks);
  it('reports a refused read in the reference sentence', aRefusedReadReportsTheReferenceSentence);
  it('refuses a malformed filter without reading', aMalformedFilterIsRefusedWithoutReading);
  it('returns to the menu from the view screen', theViewExitKeyReturnsToTheMenu);
  it(
    'opens the update screen on its search prompt without reading',
    theUpdateScreenOpensOnItsSearchPromptWithoutReading,
  );
  it('paints the opening legend on the update screen', theUpdateScreenPaintsItsOpeningLegend);
  it('applies every declared field width to its control', everyDeclaredWidthReachesItsControl);
  it('populates the form from a fetched record', aFetchedRecordPopulatesTheFormAndPrompt);
  it('reports a refused fetch in the reference sentence', aRefusedFetchReportsTheReferenceSentence);
  it('returns to the menu from the update screen', theUpdateExitKeyReturnsToTheMenu);
}

describe('the account view and account update screens', accountScreenCases);
