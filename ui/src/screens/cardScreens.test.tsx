/**
 * @file Proves the three card screens render their screen-level outcome through the
 * shared message band rather than through a component of their own.
 *
 * Purpose
 * -------
 * These cases assert one structural property per screen and nothing about the
 * screen's data: that the band element is present in BOTH states, and that a
 * failure reaches it as text. The property matters because the band is the one
 * place the message contract of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`
 * (`app/cpy/CVCRD01Y.cpy` L28-L29) is enforced, the one place the per-mapset
 * display width is applied -- 80 characters on `COCRDSL` and `COCRDUP`, 78 on the
 * other nineteen, asserted in `ui/src/layout/MessageBand.test.tsx` -- and the one
 * place the always-reserved row-23 space is guaranteed. A screen that rendered its
 * own alert would satisfy every visible expectation while silently opting out of
 * all three.
 *
 * Refactoring Rationale: the assertion is written against
 * `MESSAGE_BAND_TEST_ID` rather than against the message text or an alert role.
 * The band is deliberately contentless when empty -- it exposes no text, no role
 * and no accessible name -- so a text or role query can only ever observe the
 * populated state and could not distinguish "reserved space with no message"
 * from "no band at all". That is precisely the regression these cases exist to
 * catch, which is why the component exports a stable identifier for it.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it`,
 * `afterEach` and `waitFor` is a NAMED declaration rather than an inline arrow.
 * Two constraints meet here and only this shape satisfies both, and it is the
 * same shape `ui/src/routes/cards.test.ts` records: `ui/eslint.config.js`
 * selects a function expression in every position, so an inline callback needs
 * its own JSDoc block, and Prettier moves a block comment that follows an
 * argument comma onto the preceding string literal, which detaches the block
 * from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getCard, listCards, lookupCard, updateCard } from '../api/cards';
import type { CardDetail, CardSummary, PageResponse } from '../api/cards';
import { AppShell } from '../layout/AppShell';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { RECORD_VIEW_BREAKPOINT, RECORD_VIEW_COLUMNS } from '../layout/recordLayout';
import { SHARED_MESSAGES, STATUS_MESSAGES } from '../messages/messages';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from '../routes/cards';
import { BREAKPOINT_TOKENS } from '../theme/tokens';
import { CARD_DETAIL_FIELD_LABELS, CardDetailScreen, formatCardExpiry } from './cardDetail';
import { CARD_LIST_ENTRY_CONTROL_LABELS, CARD_LIST_LABELS, CardListScreen } from './cardList';
import { CardUpdateScreen } from './cardUpdate';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: this is a hoisted function DECLARATION rather than an inline
 * factory, and that is what makes it usable at all. Vitest lifts every
 * `vi.mock` call above the imports, so a factory held in a `const` would be in
 * its temporal dead zone at registration time; a function declaration is
 * hoisted with its binding initialised, and its body is not evaluated until the
 * mocked module is first imported.
 * @returns {Record<string, unknown>} The three transport functions, each a
 *   fresh spy whose behaviour an individual case sets.
 */
function mockCardTransportModule(): Record<string, unknown> {
  return {
    listCards: vi.fn(),
    getCard: vi.fn(),
    updateCard: vi.fn(),
    lookupCard: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client
 * beneath it. These cases assert how a screen PRESENTS an outcome, so the
 * shortest honest seam is the function the screen calls; going through the axios
 * client would additionally exercise the interceptor chain, which has its own
 * concerns and its own failure modes and would make a presentation regression
 * indistinguishable from a transport one.
 */
vi.mock('../api/cards', mockCardTransportModule);

// Assumptions: a card is addressed by an opaque SELECTOR, so a concrete route needs one of those and
// not a card number. This literal is synthetic: it has the published length and alphabet, so it
// satisfies the route guard, and it seals nothing, so it addresses no real card. Using a number here
// would make every route in this file fail its own guard.
const CARD_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-0000000000000';

const EMPTY_PAGE: PageResponse<CardSummary> = {
  items: [],
  firstKey: null,
  lastKey: null,
  hasNext: false,
};

/*
 * Assumptions: the display card number is already masked to its last four
 * digits, because that is what the service returns for a non-administrative
 * read. A fixture carrying an unmasked number would put a PAN-shaped literal in
 * a test file for no assertion's benefit.
 */
const CARD: CardDetail = {
  key: CARD_SELECTOR,
  displayCardNumber: '************0011',
  accountId: '00000000011',
  embossedName: 'PAUL BUCK',
  expirationDate: '2023-01-20',
  activeStatus: 'Y',
  version: 1,
};

/**
 * Renders one screen at a concrete path inside an in-memory router.
 *
 * Assumptions: `MemoryRouter` rather than the application's own
 * `CardDemoRouter`, which wraps `BrowserRouter`. A memory router lets a case
 * mount one screen at one path without navigating a jsdom `history`, and it
 * keeps a case from depending on the whole route table -- so a route added or
 * renamed later cannot break an assertion about a message band.
 *
 * Refactoring Rationale: the single `AppShell` is inside the tree because the band
 * these cases assert on is painted BY it. Each screen delegates its row-23 message
 * through `useShellSlot` rather than composing a band of its own, mirroring
 * `ui/src/App.tsx`, so a bare screen would render no band at all and every case
 * below would fail for the wrong reason.
 * @param {string} path - Initial location for the router.
 * @param {string} routePattern - Route pattern the element is mounted at.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderAt(path: string, routePattern: string, element: ReactElement): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AppShell>
        <Routes>
          <Route path={routePattern} element={element} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Asserts at least one band element exists, which one must in both states.
 * @returns {void} Nothing; throws when no band is present.
 */
function expectBandPresent(): void {
  // WHY : Assumptions: the SINGULAR query is used, so this also asserts that exactly ONE element
  //       carries the row-23 identifier. It could not while a screen's own information line shared
  //       that identifier, which is why this read `getAllByTestId(...).length > 0` before.
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toBeInTheDocument();
}

/**
 * Returns the SHELL's message band -- the row-23 error line.
 *
 * ⚠️ Refactoring Rationale: this used to find the band STRUCTURALLY, by filtering the matches for
 * the one that is not a descendant of the shell's content element. That workaround existed only
 * because a screen's own information line carried the same `data-testid`, so `getByTestId` threw on
 * two matches. `ui/src/layout/MessageBand.tsx` now names the two lines apart -- the row-22 line
 * takes `INFORMATION_BAND_TEST_ID` -- so the row-23 line can be asked for directly, and asking for
 * it singularly asserts the shell's single-band contract at the same time.
 * @returns {HTMLElement} The band the shell paints.
 */
function shellBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns the information bands a screen renders inside its own body.
 *
 * ⚠️ Refactoring Rationale: this too selected by containment rather than by name, for the same
 * reason `shellBand` did. It now asks for the row-22 identifier directly. A query rather than a get
 * is used because a screen legitimately renders none: only the five mapsets declaring two message
 * fields have this line.
 * @returns {readonly HTMLElement[]} Every information band a screen body painted.
 */
function bodyBands(): readonly HTMLElement[] {
  return screen.queryAllByTestId(INFORMATION_BAND_TEST_ID);
}

/**
 * Waits for a rendered alert and asserts the SHELL's band contains it.
 *
 * Assumptions: the shell's band is the one asserted on, because the row-23 field is where every
 * one of these screens sends a failure -- an informational body band carrying the failure instead
 * would be the row-20 field reporting an error, which is not what either field is for.
 * @returns {Promise<void>} Resolves once the alert is inside the shell's band.
 */
async function expectFailureInsideBand(): Promise<void> {
  const alert = await screen.findByRole('alert');
  expect(shellBand()).toContainElement(alert);
}

/**
 * Restores the module mocks between cases so a rejection cannot leak forward.
 * @returns {void} Nothing.
 */
function resetTransportMocks(): void {
  vi.mocked(listCards).mockReset();
  vi.mocked(getCard).mockReset();
  vi.mocked(updateCard).mockReset();
  vi.mocked(lookupCard).mockReset();
}

/**
 * Asserts the browse screen reserves the band on a successful load.
 * @returns {Promise<void>} Resolves once the loaded page has settled.
 */
async function listReservesBandWhenThereIsNoMessage(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);

  await waitFor(expectBandPresent);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
}

/**
 * Asserts the browse screen routes a failed load into the band.
 * @returns {Promise<void>} Resolves once the failure has been rendered.
 */
async function listSendsFailureToTheBand(): Promise<void> {
  vi.mocked(listCards).mockRejectedValue(new Error('transport'));

  renderAt('/cards', '/cards', <CardListScreen />);

  await expectFailureInsideBand();
}

/**
 * Asserts the detail screen reserves an empty ERROR band on a successful read.
 *
 * Refactoring Rationale: the assertion names the shell's band, where it used to take the only band
 * in the document. The screen now also paints the mapset's row-20 informational field, so "the
 * band" is ambiguous; what this case is about is that a successful read puts nothing in the row-23
 * ERROR field, which is the property the reserved-space contract is for.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function detailReservesBandWhenThereIsNoMessage(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await screen.findByText(CARD.embossedName);
  expect(shellBand()).toBeEmptyDOMElement();
}

/**
 * A successful retrieval confirms itself through the mapset's own informational field.
 *
 * Assumptions: the sentence is `FOUND-CARDS-FOR-ACCOUNT`, `'   Displaying requested details'`,
 * taken from the catalog rather than written here -- three leading blanks included, because
 * `app/cbl/COCRDSLC.cbl` L129-L130 declares them and Rule T8 carries a user-visible string across
 * character for character. The program sets it on both of its normal file responses, L754 and L795.
 *
 * Assumptions: the assertion is that the sentence is in a band INSIDE the body, not merely
 * somewhere in the document. Row 20 is a message field, and a screen that put the text in an
 * ordinary paragraph would opt out of the reserved height and the severity appearance the field
 * carried -- `ATTRB=(PROT) COLOR=NEUTRAL` at `app/bms/COCRDSL.bms` L139-L143.
 * @returns {Promise<void>} Resolves once the record and its information line have rendered.
 */
async function detailConfirmsASuccessfulRead(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await screen.findByText(CARD.embossedName);

  const carriers = bodyBands().filter(
    /**
     * Reports whether one body band carries the confirmation sentence.
     * @param {HTMLElement} band - One rendered body band.
     * @returns {boolean} `true` when this band holds the sentence.
     */
    (band: HTMLElement): boolean =>
      (band.textContent ?? '').includes(
        STATUS_MESSAGES.COCRDSLC.FOUND_CARDS_FOR_ACCOUNT.text.trim(),
      ),
  );
  expect(carriers).toHaveLength(1);
}

/**
 * With no record retrieved the informational field falls back to the program's prompt.
 *
 * Assumptions: this is the fallback `1200-SETUP-SCREEN-VARS` applies unconditionally at
 * `app/cbl/COCRDSLC.cbl` L490-L491 -- `IF WS-NO-INFO-MESSAGE / SET WS-PROMPT-FOR-INPUT TO TRUE` --
 * so the field is never blank on a sent map, and the two arms are the two states it can be in
 * rather than one arm plus an absence.
 *
 * Assumptions: the confirmation sentence must be ABSENT here, which is the half that discriminates
 * a derivation keyed on the retrieved record from one keyed on the absence of an error. A failed
 * read has no record and an error, and only the record decides this line.
 * @returns {Promise<void>} Resolves once the failed read has settled.
 */
async function detailPromptsWhenNoRecordWasRetrieved(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await screen.findByRole('alert');

  const bodyText = bodyBands()
    .map(
      /**
       * Reads one body band's text.
       * @param {HTMLElement} band - One rendered body band.
       * @returns {string} That band's text.
       */
      (band: HTMLElement): string => band.textContent ?? '',
    )
    .join(' ');
  expect(bodyText).toContain(STATUS_MESSAGES.COCRDSLC.WS_PROMPT_FOR_INPUT.text);
  expect(bodyText).not.toContain(STATUS_MESSAGES.COCRDSLC.FOUND_CARDS_FOR_ACCOUNT.text.trim());
}

/**
 * A failed re-read withdraws the confirmation, because the information line describes the TURN.
 *
 * ⚠️ Refactoring Rationale: this case exists because a negative control found the two candidate
 * derivations indistinguishable. Keying the line on the retrieved record alone -- `card === null ?
 * prompt : confirmation` -- passes every other case in this file, and it confirms a retrieval the
 * current turn did not perform: this screen retains the record it is displaying when a re-read fails,
 * so a record-keyed line keeps saying `Displaying requested details` over a failure.
 *
 * Assumptions: the source reports per turn, and the mechanism is `WORKING-STORAGE` lifetime rather
 * than an explicit reset. `WS-INFO-MSG` is declared with no `VALUE` clause at `app/cbl/COCRDSLC.cbl`
 * L126, CICS gives each pseudo-conversational turn a fresh copy, and only a normal file response sets
 * `FOUND-CARDS-FOR-ACCOUNT` (L754 and L795) -- so a turn whose read failed reaches the L490-L491
 * fallback with the field blank and paints the prompt, however the previous turn ended.
 *
 * Assumptions: the record is asserted STILL PRESENT alongside the prompt, because that is the
 * registered divergence: the terminal's `SEND MAP ... ERASE` cleared the fields and this screen does
 * not. Asserting it here keeps the divergence visible rather than incidental.
 * @returns {Promise<void>} Resolves once the failed re-read has settled.
 */
async function detailWithdrawsTheConfirmationOnAFailedReread(): Promise<void> {
  vi.mocked(getCard).mockResolvedValueOnce(CARD).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);
  await screen.findByText(CARD.embossedName);

  await userEvent.keyboard('{Enter}');
  await screen.findByRole('alert');

  const bodyText = bodyBands()
    .map(
      /**
       * Reads one body band's text.
       * @param {HTMLElement} band - One rendered body band.
       * @returns {string} That band's text.
       */
      (band: HTMLElement): string => band.textContent ?? '',
    )
    .join(' ');
  expect(bodyText).toContain(STATUS_MESSAGES.COCRDSLC.WS_PROMPT_FOR_INPUT.text);
  expect(bodyText).not.toContain(STATUS_MESSAGES.COCRDSLC.FOUND_CARDS_FOR_ACCOUNT.text.trim());
  expect(screen.getByText(CARD.embossedName)).toBeInTheDocument();
}

/**
 * The information line is derived from BOTH the record and the settled read.
 *
 * ⚠️ Refactoring Rationale: this is asserted on the module's own text, and the reason is that one half
 * of the condition guards a state the screen cannot currently reach. A negative control measured it:
 * dropping the `card !== null` conjunct leaves every DOM case in this file green, because the only
 * state the two forms disagree about -- no record and no failure -- is behind the loading branch, which
 * returns a bare spinner and renders no band at all.
 *
 * Assumptions: the conjunct is kept rather than deleted as dead, because it is what makes the
 * expression state the rule instead of a proxy for it. `error === null` alone reads as "nothing has
 * failed, so a retrieval is confirmed", which is true only while an unrelated early return happens to
 * hide the pre-read state -- and the account view keeps its chrome mounted through the equivalent
 * state, so that early return is a decision a later revision may reverse.
 *
 * Alternatives Considered: making the state reachable in a case by mounting the screen with the read
 * left unsettled. Rejected because the assertion would then be about the spinner branch rather than
 * about the derivation, and it would pass for a screen that had no information line at all.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function detailDerivesTheInformationLineFromBoth(): void {
  const source = readFileSync(join(import.meta.dirname, 'cardDetail', 'index.tsx'), 'utf8');

  expect(source).toContain('card !== null && error === null');
}

/**
 * The detail record panel takes the shared column policy rather than restating a column count.
 *
 * Assumptions: the policy is asserted as a VALUE and as the module's own text, not by measuring a
 * rendered table. antd resolves the responsive object itself, so a DOM measurement would assert
 * antd's behaviour rather than this screen's decision, and the literal `column={2}` this block was
 * authored with renders identically to the policy at the jsdom default width -- which is exactly
 * why a rendered measurement could not tell the two apart.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function detailTakesTheSharedColumnPolicy(): void {
  const source = readFileSync(join(import.meta.dirname, 'cardDetail', 'index.tsx'), 'utf8');

  expect(source).toContain('column={RECORD_VIEW_COLUMNS}');
  expect(source).not.toContain('column={2}');
  expect(RECORD_VIEW_COLUMNS.xs).toBe(1);
  expect(RECORD_VIEW_COLUMNS.sm).toBe(1);
  expect(RECORD_VIEW_COLUMNS.md).toBe(2);
  expect(RECORD_VIEW_BREAKPOINT).toBe(BREAKPOINT_TOKENS.medium);
}

/**
 * Asserts the detail screen routes a failed read into the band.
 * @returns {Promise<void>} Resolves once the failure has been rendered.
 */
async function detailSendsFailureToTheBand(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await expectFailureInsideBand();
}

/**
 * Asserts the update screen reserves an empty band once the form has loaded.
 * @returns {Promise<void>} Resolves once the form has been rendered.
 */
async function updateReservesBandWhenThereIsNoMessage(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);

  expect(await screen.findByTestId(MESSAGE_BAND_TEST_ID)).toBeEmptyDOMElement();
}

/**
 * Asserts the update screen routes a failed load into the band.
 * @returns {Promise<void>} Resolves once the failure has been rendered.
 */
async function updateSendsFailureToTheBand(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);

  await expectFailureInsideBand();
}

/**
 * Collapses a transcribed label to the form an accessible-name query matches.
 *
 * ⚠️ Assumptions: this exists because the mapset labels carry INTERNAL runs of blanks used as column
 * padding -- `'Account Number    :'` at `app/bms/COCRDLI.bms` L88 pads to the field width -- and
 * Testing Library's default normalizer collapses runs of whitespace in the element text before
 * comparing. Passing the transcribed literal straight to a name query therefore never matches, and
 * the failure looks like a missing label rather than a whitespace mismatch.
 *
 * Alternatives Considered: trimming the padding out of `CARD_LIST_LABELS` itself, so no query needed
 * a transform. Rejected because Rule T8 carries a user-visible string across character for character
 * and the padded form is what the terminal painted; the collapse belongs in the query, which is where
 * the normalizer is.
 * @param {string} label - One transcribed mapset label.
 * @returns {string} The label with each run of whitespace collapsed to one blank.
 */
function accessibleLabel(label: string): string {
  return label.replace(/\s+/gu, ' ');
}

/**
 * The detail screen offers NO editable control beside a record the address named.
 *
 * ⚠️ Refactoring Rationale: this case read `queryAllByRole('textbox')).toEqual([])` -- that the screen
 * renders no text entry at all -- to pin one half of a claim its header used to make: that the mapset's
 * two editable fields, `ACCTSID` at `app/bms/COCRDSL.bms` L84-L88 and `CARDSID` at L96-L100, are not
 * reproduced there and the browse screen gathers both instead. That claim was superseded. The two fields
 * ARE reproduced, because the reference has two arrivals and paints them on both -- `1300-SETUP-SCREEN-ATTRS`
 * chooses `DFHBMPRF` or `DFHBMFSE` for them at `app/cbl/COCRDSLC.cbl` L505-L512 -- and the criteria-gathering
 * chain those fields drive is a different chain from the browse screen's, refusing an unsupplied value where
 * the browse screen accepts one.
 *
 * Refactoring Rationale: what this case now asserts is the property that survives the change and is the
 * one it existed to protect: on the selector arrival the record view offers no way to EDIT anything. Both
 * controls are present and both are disabled, which is what `DFHBMPRF` means, and no third control exists.
 * The browse cases below still prove the workflow the header points at; the rendering of the two fields on
 * each arrival is asserted in `ui/src/screens/cardDetail/cardDetail.test.tsx`, beside the edit chain.
 * @returns {Promise<void>} Resolves once the record has rendered.
 */
async function detailRendersNoSearchFields(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await screen.findByText(CARD.embossedName);

  const controls = screen.queryAllByRole('textbox');
  expect(controls).toHaveLength(2);
  controls.forEach(
    /**
     * Asserts one rendered control accepts no input on this arrival.
     * @param {HTMLElement} control - One rendered text control.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (control: HTMLElement): void => {
      expect(control).toBeDisabled();
    },
  );
}

/**
 * Returns the browse screen's account-number filter control.
 *
 * Assumptions: it is located by its accessible NAME rather than by an id, because the name is the
 * property the restored control has to have -- an unnamed input would satisfy an id query and be
 * unusable by a screen reader. The name comes from `CARD_LIST_LABELS.accountNumberFilter`, which is
 * the label `app/bms/COCRDLI.bms` L88 paints.
 * @returns {Promise<HTMLElement>} The rendered account-number filter control.
 */
async function accountFilterControl(): Promise<HTMLElement> {
  return screen.findByLabelText(accessibleLabel(CARD_LIST_LABELS.accountNumberFilter));
}

/**
 * Returns the browse screen's card-number filter control.
 * @returns {Promise<HTMLElement>} The rendered card-number filter control.
 */
async function cardFilterControl(): Promise<HTMLElement> {
  return screen.findByLabelText(accessibleLabel(CARD_LIST_LABELS.cardNumberFilter));
}

/**
 * Presses the browse screen's filter control.
 * @returns {Promise<void>} Resolves once the click has been dispatched.
 */
async function pressFilter(): Promise<void> {
  await userEvent.click(
    screen.getByRole('button', { name: CARD_LIST_ENTRY_CONTROL_LABELS.filter }),
  );
}

/**
 * The browse screen renders an account-number filter, and it narrows the read.
 *
 * ⚠️ Refactoring Rationale: this is the case that fails if the control is removed again. The screen
 * accepted `accountId` in `CardListQuery` and never produced one, so the account narrowing the
 * reference offers unconditionally -- `2210-EDIT-ACCOUNT` at `app/cbl/COCRDLIC.cbl` L1003-L1030 --
 * was reachable from no screen in the tree, while the card detail screen's own header documented
 * that this screen owned it.
 *
 * Assumptions: the narrowing is asserted on the ARGUMENT the transport received, not on the rendered
 * rows. A control that gathered a value and did not put it in the query would render exactly the
 * same rows, so only the call discriminates.
 * @returns {Promise<void>} Resolves once the narrowed read has been observed.
 */
async function listNarrowsByAccount(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);
  await waitFor(expectBandPresent);

  await userEvent.type(await accountFilterControl(), '00000000011');
  await pressFilter();

  await waitFor(
    /**
     * Asserts the last read carried the entered account and no cursor.
     * @returns {void} Nothing; throws until the narrowed call has been made.
     */
    (): void => {
      expect(vi.mocked(listCards)).toHaveBeenLastCalledWith({ accountId: '00000000011' });
    },
  );
}

/**
 * A supplied account filter of the wrong width is refused with the program's own sentence.
 *
 * Assumptions: the refusal must also make NO request. `2210-EDIT-ACCOUNT` sets its error and
 * `2200-EDIT-INPUTS` does not go on to read, so a screen that refused in the band and read anyway
 * would show a sentence over rows it had just fetched.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function listRefusesAShortAccountFilter(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);
  await waitFor(expectBandPresent);
  const openingCalls = vi.mocked(listCards).mock.calls.length;

  await userEvent.type(await accountFilterControl(), '123');
  await pressFilter();

  // WHY : Refactoring Rationale: the query is scoped to the row-23 BAND rather than to the whole
  //       document. The refused filter now also carries a visually-hidden copy of the sentence as
  //       the target of its `aria-describedby`, without which that association would be a dangling
  //       reference -- so an unscoped query matches two elements. Scoping is also the stronger
  //       assertion: it pins the sentence to the field the mapset declares for it.
  await waitFor(
    /**
     * Waits for the account refusal to reach the row-23 band.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(shellBand()).toHaveTextContent(
        SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
      );
    },
  );
  expect(vi.mocked(listCards).mock.calls).toHaveLength(openingCalls);
}

/**
 * When both filters are wrong the operator reads the ACCOUNT sentence, and reads it alone.
 *
 * ⚠️ Assumptions: this is first-error-wins, and it is a measured property of the source rather than a
 * preference: `2200-EDIT-INPUTS` performs the account edit and then the card edit
 * (`app/cbl/COCRDLIC.cbl` L989-L996), and `2220-EDIT-CARD` writes its own sentence only
 * `IF WS-ERROR-MSG-OFF` at L1057. A screen that edited the card first, or that reported whichever
 * refusal it computed last, would show the second sentence on a turn the reference reports with the
 * first -- and both sentences exist and are near-identical, so the wrong one reads as correct.
 * @returns {Promise<void>} Resolves once the single refusal has been rendered.
 */
async function listPrefersTheAccountRefusal(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);
  await waitFor(expectBandPresent);

  await userEvent.type(await accountFilterControl(), '123');
  await userEvent.type(await cardFilterControl(), '4444');
  await pressFilter();

  // WHY : Refactoring Rationale: the query is scoped to the row-23 BAND rather than to the whole
  //       document. The refused filter now also carries a visually-hidden copy of the sentence as
  //       the target of its `aria-describedby`, without which that association would be a dangling
  //       reference -- so an unscoped query matches two elements. Scoping is also the stronger
  //       assertion: it pins the sentence to the field the mapset declares for it.
  await waitFor(
    /**
     * Waits for the account refusal, and for the card refusal to be absent, in the row-23 band.
     * @returns {void} Nothing; the assertions carry the outcome.
     */
    (): void => {
      expect(shellBand()).toHaveTextContent(
        SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
      );
    },
  );
  expect(document.body).not.toHaveTextContent(
    SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
  );
}

/**
 * An all-zeroes account entry counts as not supplied, and clears the narrowing.
 *
 * Assumptions: three spellings mean "not supplied" and this asserts the least obvious of them.
 * `2210-EDIT-ACCOUNT` exits blank on `LOW-VALUES OR SPACES OR CC-ACCT-ID-N EQUAL ZEROS`
 * (`app/cbl/COCRDLIC.cbl` L1003-L1030), so eleven zeroes are numeric and of the right width and
 * still are not a narrowing -- a pattern test alone would accept them and read account zero.
 * @returns {Promise<void>} Resolves once the unnarrowed read has been observed.
 */
async function listTreatsZeroesAsNoAccountFilter(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);
  await waitFor(expectBandPresent);

  await userEvent.type(await accountFilterControl(), '00000000000');
  await pressFilter();

  await waitFor(
    /**
     * Asserts the read carried no narrowing at all.
     * @returns {void} Nothing; throws until the unnarrowed call has been made.
     */
    (): void => {
      expect(vi.mocked(listCards)).toHaveBeenLastCalledWith({});
    },
  );
  expect(document.body).not.toHaveTextContent(
    SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
  );
}

/**
 * The cursor lands on the account filter, which is the mapset's initial-cursor field.
 *
 * Assumptions: exactly one field on this map carries `IC` -- `ACCTSID` at `app/bms/COCRDLI.bms` L89
 * is `ATTRB=(FSET,IC,NORM,UNPROT)` while `CARDSID` at L101 is `ATTRB=(FSET,NORM,UNPROT)` -- so this
 * asserts both halves: the account control has focus and the card control does not.
 * @returns {Promise<void>} Resolves once the opening focus has been observed.
 */
async function listOpensTheCursorOnTheAccountFilter(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);
  await waitFor(expectBandPresent);

  expect(document.activeElement).toBe(await accountFilterControl());
  expect(document.activeElement).not.toBe(await cardFilterControl());
}

/**
 * Asserts one control is programmatically associated with a refusal sentence.
 *
 * Assumptions: the association is followed to its TARGET rather than merely read off the control.
 * `aria-describedby` naming an element that does not exist announces nothing at all, and it is
 * indistinguishable from a correct association by inspection of the control alone -- which is the
 * failure this helper exists to catch.
 * @param {HTMLElement} control - The control the refusal belongs to.
 * @param {string} sentence - The refusal sentence the description must carry.
 * @returns {void} Nothing; throws when the association is missing or dangling.
 */
function expectDescribedByRefusal(control: HTMLElement, sentence: string): void {
  expect(control).toHaveAttribute('aria-invalid', 'true');
  const target = control.getAttribute('aria-describedby');
  expect(target).not.toBeNull();
  expect(document.getElementById(target ?? '')).toHaveTextContent(sentence);
}

/**
 * Asserts one control carries no refusal marking at all.
 * @param {HTMLElement} control - The control that must be unmarked.
 * @returns {void} Nothing; throws when the control is marked.
 */
function expectUnmarked(control: HTMLElement): void {
  expect(control).not.toHaveAttribute('aria-invalid');
  expect(control).not.toHaveAttribute('aria-describedby');
}

/**
 * Each refused filter is associated with ITS OWN control, and only that control is marked.
 *
 * ⚠️ Assumptions: the band alone is not the association the reference makes. `app/cpy/CSSETATY.cpy`
 * L17-L27 moves `DFHRED` into the refused FIELD's own attribute byte in addition to writing the
 * sentence on the message row, so the terminal marks the field as well as reporting it; AAP Rule T7
 * carries that per-field marking across as a structured field error. `aria-invalid` plus a
 * RESOLVABLE `aria-describedby` is the web spelling of that attribute byte, and for an operator who
 * cannot see the band it is the only part that is perceivable.
 *
 * ⚠️ Assumptions: both halves discriminate. The refused control must be marked, and the other must
 * NOT be -- marking both would contradict the first-error-wins order the source enforces, where
 * `2220-EDIT-CARD` writes its sentence only `IF WS-ERROR-MSG-OFF` (`app/cbl/COCRDLIC.cbl` L1057),
 * so at most one of the two fields is ever the refused one.
 * @returns {Promise<void>} Resolves once both refusals have been observed.
 */
async function listAssociatesEachRefusalWithItsControl(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(EMPTY_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);
  await waitFor(expectBandPresent);
  const account = await accountFilterControl();
  const card = await cardFilterControl();

  await userEvent.type(account, '123');
  await pressFilter();

  await waitFor(
    /**
     * Waits for the account control to carry its own refusal.
     * @returns {void} Nothing; the assertions carry the outcome.
     */
    (): void => {
      expectDescribedByRefusal(
        account,
        SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
      );
    },
  );
  expectUnmarked(card);

  await userEvent.clear(account);
  await userEvent.type(card, '4444');
  await pressFilter();

  await waitFor(
    /**
     * Waits for the marking to move to the card control, which is now the refused field.
     * @returns {void} Nothing; the assertions carry the outcome.
     */
    (): void => {
      expectDescribedByRefusal(
        card,
        SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
      );
    },
  );
  expectUnmarked(account);
}

/**
 * A screen with two message fields renders each under its OWN identifier, exactly once.
 *
 * ⚠️ Refactoring Rationale: both lines used to carry `MESSAGE_BAND_TEST_ID`, which made this
 * property unassertable -- a singular query threw on the two matches, so every test in the tree had
 * to select the row-23 line structurally, and "the band" could not be named. The two-SURFACE design
 * is itself correct and is what the mapset declares: `app/bms/COCRDSL.bms` L139-L143 places the
 * informational field on row 20 and the error field on row 23. Only the shared identifier was wrong.
 *
 * Assumptions: the counts are asserted as well as the contents, because the reserved-space contract
 * is per line. Two elements answering to the row-23 identifier would reserve two rows where the map
 * declares one, and the excess would be invisible until a sentence landed in it.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function detailNamesItsTwoMessageSurfacesApart(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await screen.findByText(CARD.embossedName);

  expect(screen.getAllByTestId(MESSAGE_BAND_TEST_ID)).toHaveLength(1);
  expect(screen.getAllByTestId(INFORMATION_BAND_TEST_ID)).toHaveLength(1);
  expect(shellBand()).toBeEmptyDOMElement();
  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(
    STATUS_MESSAGES.COCRDSLC.FOUND_CARDS_FOR_ACCOUNT.text.trim(),
  );
}

/**
 * Registers every case in this file: the per-screen band cases, the browse filter cases, the
 * per-field refusal association and the two-surface naming contract.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function cardScreenBandCases(): void {
  afterEach(resetTransportMocks);

  it(
    'reserves the band on the card list when there is no message',
    listReservesBandWhenThereIsNoMessage,
  );
  it('renders a card list failure inside the band', listSendsFailureToTheBand);
  it(
    'reserves the band on the card detail when there is no message',
    detailReservesBandWhenThereIsNoMessage,
  );
  it('renders a card detail failure inside the band', detailSendsFailureToTheBand);
  it(
    'reserves the band on the card update when there is no message',
    updateReservesBandWhenThereIsNoMessage,
  );
  it('renders a card update failure inside the band', updateSendsFailureToTheBand);
  it('confirms a successful card read in its own information line', detailConfirmsASuccessfulRead);
  it('prompts for criteria when no card was retrieved', detailPromptsWhenNoRecordWasRetrieved);
  it(
    'withdraws the confirmation when a re-read fails',
    detailWithdrawsTheConfirmationOnAFailedReread,
  );
  it('takes the shared record column policy on the card detail', detailTakesTheSharedColumnPolicy);
  it(
    'derives the information line from the record and the read',
    detailDerivesTheInformationLineFromBoth,
  );
  it('narrows the card browse by account number', listNarrowsByAccount);
  it('refuses an account filter that is not eleven digits', listRefusesAShortAccountFilter);
  it('reports the account refusal alone when both filters are wrong', listPrefersTheAccountRefusal);
  it('treats an all-zeroes account entry as no filter', listTreatsZeroesAsNoAccountFilter);
  it('opens the cursor on the account filter', listOpensTheCursorOnTheAccountFilter);
  it('offers no editable control on the card detail', detailRendersNoSearchFields);
  it(
    'associates each browse filter refusal with its own control',
    listAssociatesEachRefusalWithItsControl,
  );
  it(
    'names the two message surfaces on the card detail apart',
    detailNamesItsTwoMessageSurfacesApart,
  );
}

/**
 * Reads the rendered expiry out of the detail screen's description list.
 *
 * Assumptions: the value is located through its own label rather than by text, because the rendering
 * under test is a bare `MM/YYYY` string that would also match a value on another row if one ever came
 * to hold the same digits. `Descriptions.Item` renders the label and the value as siblings under one
 * row, so the label's cell is the stable anchor.
 * @returns {Promise<string>} The text content of the expiry value cell.
 */
async function renderedExpiry(): Promise<string> {
  /*
   * Assumptions: the label is COLLAPSED before it is queried, not merely trimmed. The catalogued label
   * is `'Expiry Date       : '` -- interior padding carried over verbatim from the mapset's fixed-width
   * caption field -- and Testing Library normalises the DOM text it compares against but takes the
   * expected string as given, so the padded literal never matches its own rendering. Collapsing runs of
   * whitespace here applies the same normalisation to both sides while keeping the catalogue as the
   * single source of the caption.
   */
  const collapsedLabel = CARD_DETAIL_FIELD_LABELS.expiryDate.replace(/\s+/gu, ' ').trim();
  const labelCell = await screen.findByText(collapsedLabel);
  const row = labelCell.closest('tr');
  expect(row, 'the expiry label must sit in a description row').not.toBeNull();
  const valueCell = row?.querySelector('.ant-descriptions-item-content');
  expect(valueCell, 'the expiry row must carry a value cell').not.toBeNull();

  return valueCell?.textContent ?? '';
}

/**
 * The helper regroups a stored ISO expiry into the two fields the mapset paints.
 *
 * Assumptions: the expected string is written out rather than composed from the input, because the
 * ORDER is the property under test -- the stored text leads with the year and the terminal leads with
 * the month, so a helper that returned `2023/01` would satisfy any assertion built by slicing the
 * input the same way the helper does.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function formatsAStoredExpiryAsMonthThenYear(): void {
  expect(formatCardExpiry('2023-01-20')).toBe('01/2023');
  expect(formatCardExpiry('1999-12-31')).toBe('12/1999');
}

/**
 * The helper passes a value of unexpected shape through unchanged.
 *
 * Assumptions: each of these is a DIFFERENT way the ten-character contract can be broken -- a value
 * truncated before the day, one with the separators removed, one carrying a time, one blank and one
 * with a non-numeric part -- because the helper's own trade-off note argues that a blind positional
 * slice would turn each of them into a plausible-looking `MM/YYYY` that a reader could not tell from
 * a correct rendering. That is the failure this case exists to detect, so a single malformed input
 * would not establish it.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function passesAnUnexpectedShapeThrough(): void {
  expect(formatCardExpiry('2023-01')).toBe('2023-01');
  expect(formatCardExpiry('20230120')).toBe('20230120');
  expect(formatCardExpiry('2023-01-20T00:00:00')).toBe('2023-01-20T00:00:00');
  expect(formatCardExpiry('')).toBe('');
  expect(formatCardExpiry('20xx-01-20')).toBe('20xx-01-20');
}

/**
 * The detail screen paints the regrouped expiry and never the stored text.
 *
 * Assumptions: the absence of the stored form is asserted as well as the presence of the rendered
 * one. Asserting only the presence would keep passing if the screen rendered BOTH -- which is exactly
 * what a partially-applied change looks like -- and the stored form showing a day is the specific
 * regression the helper was introduced to remove, since the mapset paints no field for one.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function detailPaintsTheRegroupedExpiry(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  expect(await renderedExpiry()).toBe('01/2023');
  expect(screen.queryByText(CARD.expirationDate)).not.toBeInTheDocument();
}

/**
 * The detail screen shows a malformed stored expiry unchanged rather than a wrong rendering.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function detailPaintsAMalformedExpiryUnchanged(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue({ ...CARD, expirationDate: '2023-01' });

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  expect(await renderedExpiry()).toBe('2023-01');
}

/**
 * Registers the expiry-rendering cases: two on the helper and two on the rendered value.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function cardExpiryRenderingCases(): void {
  afterEach(resetTransportMocks);

  it('regroups a stored expiry as month then year', formatsAStoredExpiryAsMonthThenYear);
  it('passes an expiry of unexpected shape through unchanged', passesAnUnexpectedShapeThrough);
  it('paints the regrouped expiry on the card detail', detailPaintsTheRegroupedExpiry);
  it('paints a malformed stored expiry unchanged', detailPaintsAMalformedExpiryUnchanged);
}

describe('card screens render outcomes through the shared message band', cardScreenBandCases);
describe(
  'the card detail screen renders the expiry the way the mapset paints it',
  cardExpiryRenderingCases,
);
