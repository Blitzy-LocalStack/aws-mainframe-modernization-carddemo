/**
 * @file Proves the three card screens compose the shared shell in full and render only baseline text.
 *
 * Purpose
 * -------
 * Two obligations are asserted here, and both are obligations
 * `docs/adr/ADR-006-api-and-ui.md` states in *Downstream obligations this decision creates*: that
 * every screen composes the screen header, the message band and the key bar with the key-binding
 * hook, and that every user-visible string is taken verbatim from the source. The companion file
 * `ui/src/screens/cardScreens.test.tsx` covers the band alone, in both of its states; this file
 * covers the other two shell elements, the key semantics behind them, and the text.
 *
 * Assumptions: the expectations are written as the CONSTANT the screen renders rather than as a
 * retyped string, which is the whole point of the exercise. A test that repeated the sentence by hand
 * would pass for a screen that had drifted, provided the test drifted with it -- and the drift these
 * cases exist to catch is a character or a trailing space, not a visibly different sentence.
 *
 * Assumptions: a rendered expectation is normalised with {@link collapse} before it is compared,
 * because the catalog and the mapsets hold fixed-width values -- interior runs of spaces that align a
 * column and trailing spaces that pad a field -- while the testing library normalises the DOM text it
 * matches against. Normalising the expectation the same way is what lets the constant stay the source
 * of truth instead of being trimmed at the point of declaration.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the
 * two reasons `ui/src/screens/cardScreens.test.tsx` records: `ui/eslint.config.js` requires a
 * documentation block on a function expression in any position, and Prettier moves a block comment
 * that follows an argument comma onto the preceding literal, which detaches it from the function it
 * documents.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getCard, listCards, lookupCard, updateCard } from '../api/cards';
import type { CardDetail, CardSummary, PageResponse } from '../api/cards';
import { AppShell } from '../layout/AppShell';

import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { PROGRAM_MESSAGES, SHARED_MESSAGES, STATUS_MESSAGES } from '../messages/messages';
import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from '../routes/cards';
import {
  CARD_DETAIL_FIELD_LABELS,
  CARD_DETAIL_PROGRAM_NAME,
  CARD_DETAIL_TITLE,
  CARD_DETAIL_TRANSACTION_ID,
  CardDetailScreen,
} from './cardDetail';
import {
  CARD_LIST_LABELS,
  CARD_LIST_PROGRAM_NAME,
  CARD_LIST_ROW_ACTION_CODES,
  CARD_LIST_TITLE,
  CARD_LIST_TRANSACTION_ID,
  CardListScreen,
} from './cardList';
import {
  CARD_UPDATE_PROGRAM_NAME,
  CARD_UPDATE_TITLE,
  CARD_UPDATE_TRANSACTION_ID,
  CardUpdateScreen,
} from './cardUpdate';
import type { ApiError } from '../api/types';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The four transport functions, each a fresh spy.
 */
function mockCardTransportModule(): Record<string, unknown> {
  return {
    listCards: vi.fn(),
    getCard: vi.fn(),
    updateCard: vi.fn(),
    lookupCard: vi.fn(),
  };
}

vi.mock('../api/cards', mockCardTransportModule);

/**
 * Synthetic selector with the published length and alphabet that seals nothing.
 *
 * Assumptions: a card is addressed by an opaque selector, so a concrete route needs one of those and
 * not a card number; a number here would fail the screens' own route guard.
 */
const CARD_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-0000000000000';

/** One masked browse row, with the two members the contract publishes beside the selector. */
const ROW: CardSummary = {
  key: CARD_SELECTOR,
  displayCardNumber: '************0011',
  accountId: '00000000011',
  activeStatus: 'Y',
};

/** One populated page with no further page in either direction. */
const ONE_ROW_PAGE: PageResponse<CardSummary> = {
  items: [ROW],
  firstKey: null,
  lastKey: null,
  hasNext: false,
};

/** One card detail, whose masked rendering is what a non-administrative read returns. */
/**
 * Builds a rejection carrying a complete problem document, as the transport raises one.
 *
 * Assumptions: every member the published `ApiError` declares is supplied, because a partial document
 * describes a response no service can send and would let a structural narrowing pass that the real
 * shape fails.
 * @param {number} status - The HTTP status the failure carries.
 * @returns {{ problem: ApiError }} A rejection value of the shape the client raises.
 */
function aRefusal(status: number): { readonly problem: ApiError } {
  return {
    problem: {
      code: 'CARD0001',
      secondaryCode: '',
      message: null,
      severity: 'WARNING',
      subsystem: 'APPLICATION',
      status,
      correlationId: 'correlation-0001',
      path: '/api/v1/cards',
      timestamp: '2026-01-01T00:00:00Z',
      fieldErrors: [],
      abend: null,
    },
  };
}

const CARD: CardDetail = {
  ...ROW,
  embossedName: 'PAUL BUCK',
  expirationDate: '2023-01-20',
  version: 1,
};

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: interior runs are collapsed and the ends are trimmed, matching the library's default
 * text normaliser exactly, so a value the mapset pads for a character grid can still be compared
 * against what a proportional layout renders.
 * @param {string} value - A catalog or mapset value, padding included.
 * @returns {string} The value with interior whitespace runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.trim().replace(/\s+/gu, ' ');
}

/**
 * Timeout for the one case whose accessible-name role queries dominate its cost.
 *
 * Assumptions: 45 seconds is roughly four times the 10.4s the case measures in isolation, which
 * leaves room for the parallel load a full run adds without being so large that a genuine hang
 * would stall a build rather than fail it.
 */
const SLOW_ROLE_QUERY_TIMEOUT_MS = 45000;

/**
 * Renders one screen at a concrete path inside an in-memory router, wrapped in the shared shell.
 *
 * ⚠️ Refactoring Rationale: the screen is mounted inside a LAYOUT route rendering `AppShell`, where it
 * used to be mounted directly under `Routes`. That mirrors `ui/src/router.tsx`, which now nests every
 * authenticated screen inside one such layout route, and it is what these cases need in order to keep
 * asserting anything: the three card screens no longer paint a title band or a key legend themselves,
 * they DELEGATE both to the shell through `useShellSlot`. Rendered without the shell they publish a
 * delegation nothing subscribes to, so the band and the legend are simply absent and every assertion
 * about them fails against correct code.
 *
 * Assumptions: this is the one place the composition is expressed, which is why the change is one edit
 * rather than one per case. Every case below reaches the document through this helper.
 *
 * Assumptions: the shell is given NO props, so each case exercises the delegation path rather than the
 * override path. `AppShell` prefers its own props over a screen's delegation, so passing the band here
 * would assert the test's values instead of the screen's -- which is the one thing these cases exist to
 * check.
 * @param {string} path - Initial location for the router.
 * @param {string} routePattern - Route pattern the element is mounted at.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderAt(path: string, routePattern: string, element: ReactElement): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path={routePattern} element={element} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Reads the accessible names of the controls the key bar paints, in the order it paints them.
 * @returns {readonly string[]} One name per rendered legend control.
 */
function legendControlNames(): readonly string[] {
  const bar = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return within(bar)
    .getAllByRole('button')
    .map(
      /**
       * Reads one control's accessible name.
       * @param {HTMLElement} control - One legend control.
       * @returns {string} Its accessible name, whitespace-normalised.
       */
      (control: HTMLElement): string => collapse(control.textContent ?? ''),
    );
}

/**
 * Asserts the header band names this screen's transaction and program.
 * @param {string} transactionId - Identifier the screen declares for itself.
 * @param {string} programName - Source program name the screen declares for itself.
 * @returns {void} Nothing; throws when either slot is absent.
 */
function expectHeaderIdentifies(transactionId: string, programName: string): void {
  expect(screen.getByText(transactionId)).toBeInTheDocument();
  expect(screen.getByText(programName)).toBeInTheDocument();
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
 * Asserts the browse screen composes the header, its mapset title and its own three-key legend.
 *
 * Assumptions: the legend has THREE controls and not four. `app/bms/COCRDLI.bms` paints
 * `F3=Exit F7=Backward F8=Forward` and no Enter legend, even though the program admits Enter as a
 * fourth valid attention identifier, so Enter is bound with an empty label and must paint nothing.
 * @returns {Promise<void>} Resolves once the first page has settled.
 */
async function listComposesTheShell(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(ONE_ROW_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);

  await waitFor(
    /**
     * Waits for the header band to identify this screen.
     * @returns {void} Nothing; throws until both slots are present.
     */
    () => {
      expectHeaderIdentifies(CARD_LIST_TRANSACTION_ID, CARD_LIST_PROGRAM_NAME);
    },
  );
  expect(screen.getByRole('heading', { name: CARD_LIST_TITLE })).toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual(['F3=Exit', 'F7=Backward', 'F8=Forward']);
}

/**
 * Asserts the browse screen's headings, row controls and flag are the mapset's own values.
 * @returns {Promise<void>} Resolves once the row has been rendered.
 */
async function listRendersMapsetColumnsAndCodes(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(ONE_ROW_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);

  const table = await screen.findByRole('table');
  const headings = within(table)
    .getAllByRole('columnheader')
    .map(
      /**
       * Reads one column heading.
       * @param {HTMLElement} heading - One heading cell.
       * @returns {string} Its normalised text.
       */
      (heading: HTMLElement): string => collapse(heading.textContent ?? ''),
    );
  expect(headings).toStrictEqual([
    collapse(CARD_LIST_LABELS.selectColumn),
    collapse(CARD_LIST_LABELS.accountColumn),
    collapse(CARD_LIST_LABELS.cardColumn),
    collapse(CARD_LIST_LABELS.activeColumn),
  ]);
  /*
   * WHY : ⚠️ Refactoring Rationale: the two row controls are found by reading the buttons' own text
   *       instead of through `getByRole('button', { name })`, and the assertion is unchanged -- both
   *       forms require one control labelled `S` and one labelled `U` inside this table. The
   *       role-and-name form is what made this case unfinishable: measured on a four-core runner it cost
   *       roughly two minutes against a 45-second `testTimeout`, because the `name` option makes
   *       `dom-accessibility-api` compute an accessible name per candidate, and each computation calls
   *       `getComputedStyle(element, '::before')` -- a pseudo-element form jsdom does not implement, so
   *       every call is routed through its virtual console. That is the same
   *       `Not implemented: Window's getComputedStyle() method: with pseudo-elements` notice the run
   *       prints, and the same defect `ui/src/screens/cardList/browseNarrowing.test.tsx` records at its
   *       own control-count assertion. Reading `textContent` needs no accessible name at all.
   * WHY : Alternatives Considered: raising this file's `testTimeout` past two minutes. Rejected because
   *       it would keep a two-minute case in the suite and describe the cost as expected rather than
   *       removing it, and because the property under test is the mapset's own codes rather than the
   *       accessible-name algorithm's agreement with them.
   * WHY : Assumptions: the cost was independently measured a second way, and both measurements agree
   *       that the `name` option is what has to go. Inside this screen mounted in the shell,
   *       `within(table).getByRole('button', { name: 'S' })` takes 20.4s and the same query for `'U'`
   *       takes 40.1s, so the pair alone exceeds the 45s budget; the identical query against a BARE antd
   *       table carrying the same two buttons takes 1.1s, and `getByText` against that table takes 2ms.
   *       The cost therefore scales with the CSS volume antd injects once the whole screen and shell are
   *       mounted, not with the number of buttons.
   * WHY : Trade-offs: the labels are read from `tbody` BUTTONS rather than by text anywhere in the table,
   *       which is what keeps the assertion's subject exact. A plain text query would also have been
   *       fast -- `'S'` and `'U'` are each the entire text of their control and collide with nothing else
   *       here, since the headings read `Select`, `Account Number`, `Card Number` and `Active` and the
   *       row's own values are an account number, a masked card number and a status flag -- but it would
   *       no longer assert that the code is carried by an interactive control, and this case is where
   *       the mapset's action codes are checked to REACH the row.
   */
  const rowControlLabels = Array.from(table.querySelectorAll('tbody button')).map(
    /**
     * Reads one rendered row control's visible label.
     * @param {Element} control - One button rendered inside the table body.
     * @returns {string} The control's text, trimmed.
     */
    (control: Element): string => (control.textContent ?? '').trim(),
  );

  expect(rowControlLabels).toContain(CARD_LIST_ROW_ACTION_CODES.detail);
  expect(rowControlLabels).toContain(CARD_LIST_ROW_ACTION_CODES.update);
  expect(within(table).getByText(ROW.activeStatus)).toBeInTheDocument();
  expect(screen.getByLabelText(collapse(CARD_LIST_LABELS.cardNumberFilter))).toBeInTheDocument();
}

/**
 * Asserts the browse screen carries the source's own informational sentence when nothing failed.
 *
 * Assumptions: an informational message renders with the status role rather than the alert role, so
 * its presence does not contradict the companion file's assertion that a successful load raises no
 * alert.
 * @returns {Promise<void>} Resolves once the page has settled.
 */
async function listRendersTheSourceInformationalSentence(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(ONE_ROW_PAGE);

  renderAt('/cards', '/cards', <CardListScreen />);

  expect(
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDLIC.WS_INFORM_REC_ACTIONS.text)),
  ).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
}

/**
 * Asserts the two paging keys report the source's own refusals rather than being disabled.
 *
 * Assumptions: the keys are exercised through the legend controls, which dispatch through the same
 * validation path a real key press takes, so a control that ran a different handler from its key
 * would fail here.
 * @returns {Promise<void>} Resolves once both refusals have been rendered.
 */
async function listReportsThePagingRefusals(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(ONE_ROW_PAGE);
  const user = userEvent.setup();

  renderAt('/cards', '/cards', <CardListScreen />);
  await screen.findByRole('table');

  await user.click(screen.getByRole('button', { name: 'F7=Backward' }));
  expect(
    await screen.findByText(collapse(PROGRAM_MESSAGES.COCRDLIC.NO_PREVIOUS_PAGES_TO_DISPLAY)),
  ).toBeInTheDocument();

  /*
   * WHY : ⚠️ Refactoring Rationale: the FIRST forward key now expects `NO MORE RECORDS TO SHOW` and only
   *       the SECOND expects `NO MORE PAGES TO DISPLAY`, where this expected the pages sentence
   *       immediately. The old expectation described a screen that could not emit the records sentence at
   *       all -- it was a catalog entry keyed to this program with no reachable path -- and the reference
   *       distinguishes the two by a flag carried BETWEEN turns.
   * WHY : ⚠️ Assumptions: the distinguishing flag is `WS-CA-LAST-PAGE-DISPLAYED`, and it lives in the
   *       carried communication area rather than in working storage (`app/cbl/COCRDLIC.cbl` L239 to
   *       L241), so it survives a turn. It is set NOT-SHOWN by every attention identifier that is not PF8
   *       (L410 to L414), which the PF7 above therefore does. The pages sentence at L905 to L909 requires
   *       `CA-LAST-PAGE-SHOWN`, which is set only at L915 by an EARLIER exhausted PF8 -- so the first
   *       exhausted forward key cannot reach it, and what it reaches instead is the read's own
   *       `IF WS-ERROR-MSG-OFF MOVE 'NO MORE RECORDS TO SHOW'` at L1238 to L1240. The second forward key
   *       then finds the flag set and the pages sentence overwrites it.
   * WHY : Assumptions: this now exercises all THREE of the program's paging refusals rather than two,
   *       which is what the case name promises, and none of the three is a disabled key.
   */
  await user.click(screen.getByRole('button', { name: 'F8=Forward' }));
  expect(
    await screen.findByText(collapse(PROGRAM_MESSAGES.COCRDLIC.NO_MORE_RECORDS_TO_SHOW)),
  ).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'F8=Forward' }));
  expect(
    await screen.findByText(collapse(PROGRAM_MESSAGES.COCRDLIC.NO_MORE_PAGES_TO_DISPLAY)),
  ).toBeInTheDocument();
}

/**
 * Asserts the browse screen refuses a partial entry with ITS OWN filter sentence.
 *
 * Assumptions: this is the assertion that distinguishes the two near-duplicate refusals. The list
 * program declares the upper-case filter form while the detail and update programs declare a
 * mixed-case one, and an earlier revision of this screen rendered the wrong one -- which read as
 * correct in review precisely because the two say the same thing.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function listRefusesAPartialEntryWithItsOwnSentence(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(ONE_ROW_PAGE);
  const user = userEvent.setup();

  renderAt('/cards', '/cards', <CardListScreen />);
  await screen.findByRole('table');

  await user.type(screen.getByLabelText(collapse(CARD_LIST_LABELS.cardNumberFilter)), '4444');
  await user.click(screen.getByRole('button', { name: 'Filter' }));

  // WHY : Refactoring Rationale: the sentence is asserted on the row-23 BAND rather than on the whole
  //       document. The refused filter now also carries a visually-hidden copy of the sentence as the
  //       target of its `aria-describedby`, without which that association would be a dangling
  //       reference -- so an unscoped by-text query matches two elements and throws. Naming the band
  //       is also the stronger assertion: it pins the sentence to the surface the mapset declares for
  //       it (`app/bms/COCRDLI.bms` places ERRMSG on row 23) rather than merely somewhere on screen.
  await waitFor(
    /**
     * Waits for the list screen's own filter refusal to reach the row-23 band.
     * @returns {void} Nothing; throws until the band carries the sentence.
     */
    () => {
      expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(
        collapse(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER),
      );
    },
  );
  expect(document.body).not.toHaveTextContent(
    collapse(STATUS_MESSAGES.COCRDSLC.SEARCHED_CARD_NOT_NUMERIC.text),
  );
}

/**
 * Asserts the detail screen composes the shell and paints its five mapset labels.
 * @returns {Promise<void>} Resolves once the record has been rendered.
 */
async function detailComposesTheShellAndMapsetLabels(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  await waitFor(
    /**
     * Waits for the header band to identify this screen.
     * @returns {void} Nothing; throws until both slots are present.
     */
    () => {
      expectHeaderIdentifies(CARD_DETAIL_TRANSACTION_ID, CARD_DETAIL_PROGRAM_NAME);
    },
  );
  expect(screen.getByRole('heading', { name: CARD_DETAIL_TITLE })).toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual(['ENTER=Search Cards', 'F3=Exit']);
  /*
   * WHY : Refactoring Rationale: the account and card labels are expected TWICE and the other three
   *       once, where every label was expected once before. The mapset paints its two `UNPROT` fields
   *       -- `ACCTSID` and `CARDSID` -- and the screen now renders them as controls as well as
   *       rendering the same two values on the record, so each of those two labels names a control and
   *       a record row. Counting them is what keeps this case able to fail: a single `getByText` would
   *       have thrown on the duplicate, and relaxing it to `getAllByText` without a count would have
   *       passed whether the control was rendered or not.
   */
  const labelOccurrences: Record<string, number> = {
    accountNumber: 2,
    cardNumber: 2,
    nameOnCard: 1,
    cardActive: 1,
    expiryDate: 1,
  };
  for (const [field, label] of Object.entries(CARD_DETAIL_FIELD_LABELS)) {
    expect(screen.getAllByText(collapse(label)), `${field} label occurrences`).toHaveLength(
      labelOccurrences[field] ?? 0,
    );
  }
  expect(screen.getByText(CARD.activeStatus)).toBeInTheDocument();
}

/**
 * Asserts the detail screen's Enter key re-reads the record, which is its source program's Enter arm.
 * @returns {Promise<void>} Resolves once the second read has been issued.
 */
async function detailEnterRereadsTheRecord(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  const user = userEvent.setup();

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);
  await screen.findByRole('heading', { name: CARD_DETAIL_TITLE });
  expect(vi.mocked(getCard)).toHaveBeenCalledTimes(1);

  await user.click(screen.getByRole('button', { name: 'ENTER=Search Cards' }));

  await waitFor(
    /**
     * Waits for the second read to be issued.
     * @returns {void} Nothing; throws until the reader has run twice.
     */
    () => {
      expect(vi.mocked(getCard)).toHaveBeenCalledTimes(2);
    },
  );
}

/**
 * Asserts the detail screen reports an absent card with its source program's own not-found sentence.
 *
 * Refactoring Rationale: this case rejected with a bare `Error` and expected the not-found sentence,
 * which encoded the very laxity it looked like it was guarding -- the screen answered EVERY rejection
 * with that one sentence, so an expired session, a caller outside the required group and a service
 * fault were all reported to the operator as a card that does not exist. The reference does branch,
 * taking `DFHRESP(NOTFND)` at `app/cbl/COCRDSLC.cbl` L755-L761 and composing something else for any
 * other file response at L762-L771. The case now supplies the 404 the sentence belongs to, and its
 * sibling covers what a rejection carrying no status reports instead.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function detailReportsTheSourceReadFailure(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(aRefusal(404));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  expect(
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDSLC.DID_NOT_FIND_ACCTCARD_COMBO.text)),
  ).toBeInTheDocument();
}

/**
 * Asserts a rejection carrying no status is NOT reported as an absent card.
 *
 * Assumptions: a bare `Error` is what a transport fault or a malformed response settles with, and it
 * carries no status to branch on -- so the screen reports the application's abend sentence rather than
 * asserting a cause it has not established. This is the half of the branch the case above cannot show.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function detailWithholdsTheNotFoundSentenceFromAStatuslessFailure(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  expect(
    await screen.findByText(collapse(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED)),
  ).toBeInTheDocument();
  expect(
    screen.queryByText(collapse(STATUS_MESSAGES.COCRDSLC.DID_NOT_FIND_ACCTCARD_COMBO.text)),
  ).not.toBeInTheDocument();
}

/**
 * Asserts the update screen composes the shell and paints only its always-visible legend field.
 *
 * Assumptions: TWO controls, not four. `app/bms/COCRDUP.bms` paints `ENTER=Process F3=Exit` in a
 * visible field and `F5=Save F12=Cancel` in a non-display one, and the program un-darkens the second
 * only once edits have been validated -- so before that turn the save and cancel keys must paint
 * nothing at all.
 * @returns {Promise<void>} Resolves once the form has been seeded.
 */
async function updateComposesTheShellWithTheHiddenLegendWithheld(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);

  await waitFor(
    /**
     * Waits for the header band to identify this screen.
     * @returns {void} Nothing; throws until both slots are present.
     */
    () => {
      expectHeaderIdentifies(CARD_UPDATE_TRANSACTION_ID, CARD_UPDATE_PROGRAM_NAME);
    },
  );
  expect(screen.getByRole('heading', { name: CARD_UPDATE_TITLE })).toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual(['ENTER=Process', 'F3=Exit']);
}

/**
 * Asserts a changed submission reaches the source's confirmation turn rather than writing.
 * @returns {Promise<void>} Resolves once the prompt and the revealed legend are present.
 */
async function updateEnterAsksForConfirmationWithoutWriting(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  const user = userEvent.setup();

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);
  await screen.findByRole('heading', { name: CARD_UPDATE_TITLE });

  await user.clear(screen.getByDisplayValue(CARD.embossedName));
  await user.type(screen.getByRole('textbox', { name: /Name on card/u }), 'PAULA BUCK');
  await user.click(screen.getByRole('button', { name: 'ENTER=Process' }));

  expect(
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDUPC.PROMPT_FOR_CONFIRMATION.text)),
  ).toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual(['ENTER=Process', 'F3=Exit', 'F5=Save', 'F12=Cancel']);
  expect(vi.mocked(updateCard)).not.toHaveBeenCalled();
}

/**
 * Asserts an unchanged submission is refused with the source's own sentence and reveals no save key.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function updateRefusesAnUnchangedSubmission(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  const user = userEvent.setup();

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);
  await screen.findByRole('heading', { name: CARD_UPDATE_TITLE });

  await user.click(screen.getByRole('button', { name: 'ENTER=Process' }));

  expect(
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDUPC.NO_CHANGES_DETECTED.text)),
  ).toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual(['ENTER=Process', 'F3=Exit']);
  expect(vi.mocked(updateCard)).not.toHaveBeenCalled();
}

/**
 * Asserts the save key writes exactly once, and only after the confirmation turn AND its overlay.
 *
 * ⚠️ Refactoring Rationale: the accept step is new, and it is asserted rather than bypassed. The save
 * key now opens a `Popconfirm` instead of writing directly, which AAP section 0.3.2 assigns as the
 * browser form of the reference's re-key-to-confirm convention and section 0.4.1.4 names in this
 * screen's composition. Asserting the write BEFORE the overlay is accepted is what keeps this case able
 * to fail: a screen that wrote on the key press alone would leave the overlay decorative, and nothing
 * else here would say so.
 *
 * Assumptions: the accept control is matched on the library's default name rather than on a legend
 * string. The screen deliberately leaves it defaulted, because labelling it `F5=Save` would put a second
 * control of that name on the document beside the legend control that opened it.
 * @returns {Promise<void>} Resolves once the write has been issued.
 */
async function updateSaveKeyWritesOnceAfterConfirmation(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  vi.mocked(updateCard).mockResolvedValue(CARD);
  const user = userEvent.setup();

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);
  await screen.findByRole('heading', { name: CARD_UPDATE_TITLE });

  await user.clear(screen.getByDisplayValue(CARD.embossedName));
  await user.type(screen.getByRole('textbox', { name: /Name on card/u }), 'PAULA BUCK');
  await user.click(screen.getByRole('button', { name: 'ENTER=Process' }));
  await screen.findByRole('button', { name: 'F5=Save' });

  await user.click(screen.getByRole('button', { name: 'F5=Save' }));

  expect(vi.mocked(updateCard)).not.toHaveBeenCalled();

  await user.click(await screen.findByRole('button', { name: 'OK' }));

  await waitFor(
    /**
     * Waits for the single write to be issued.
     * @returns {void} Nothing; throws until the writer has run once.
     */
    () => {
      expect(vi.mocked(updateCard)).toHaveBeenCalledTimes(1);
    },
  );
}

/**
 * Asserts the cancel key discards the pending edit, re-reads, and states the source's own prompt.
 *
 * Assumptions: the sentence must SURVIVE the read that the cancel arm triggers, which is the regression
 * this case exists to catch and is invisible to any assertion made before the read settles.
 *
 * ⚠️ Refactoring Rationale: the expected sentence is `FOUND_CARDS_FOR_ACCOUNT` and it was
 * `PROMPT_FOR_CHANGES`, which the reference does not state on this turn. `3250-SETUP-INFOMSG` is the
 * program's information-line `EVALUATE` and it sets `PROMPT-FOR-CHANGES` only `WHEN CCUP-CHANGES-NOT-OK`
 * (`app/cbl/COCRDUPC.cbl` L1147-L1148) -- the refusal turn. The cancel arm sets `CCUP-SHOW-DETAILS`
 * (L1010-L1012, and L494 on the entry path), whose arm is `FOUND-CARDS-FOR-ACCOUNT` at L1145-L1146. The
 * screen now derives the line from the turn rather than stating it, so the reference's own mapping
 * decides it; the previous expectation had encoded the refusal turn's sentence for a turn where nothing
 * was refused.
 * @returns {Promise<void>} Resolves once the record has been read a second time.
 */
async function updateCancelDiscardsAndRestatesThePrompt(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  const user = userEvent.setup();

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);
  await screen.findByRole('heading', { name: CARD_UPDATE_TITLE });

  await user.clear(screen.getByDisplayValue(CARD.embossedName));
  await user.type(screen.getByRole('textbox', { name: /Name on card/u }), 'PAULA BUCK');
  await user.click(screen.getByRole('button', { name: 'ENTER=Process' }));
  await screen.findByRole('button', { name: 'F12=Cancel' });

  await user.click(screen.getByRole('button', { name: 'F12=Cancel' }));

  expect(
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDUPC.FOUND_CARDS_FOR_ACCOUNT.text)),
  ).toBeInTheDocument();
  await waitFor(
    /**
     * Waits for the re-read the cancel arm issues, and for the save key to be withdrawn with it.
     * @returns {void} Nothing; throws until both hold.
     */
    () => {
      expect(vi.mocked(getCard)).toHaveBeenCalledTimes(2);
      expect(screen.queryByRole('button', { name: 'F5=Save' })).not.toBeInTheDocument();
    },
  );
  expect(vi.mocked(updateCard)).not.toHaveBeenCalled();
}

/**
 * Asserts a real keyboard press reaches a binding, not only a click on the bar.
 *
 * Assumptions: this is the fidelity-bearing path. The source interface is operated entirely from the
 * keyboard, and the bar's buttons are additive, so a screen whose bindings were reachable only by
 * pointer would have composed the bar without migrating the contract behind it. `F7` is used because
 * `app/cpy/CSSTRPFY.cpy` normalises it to PF7 and the browse screen paints it.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function listPagingKeyRespondsToARealKeyPress(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(ONE_ROW_PAGE);
  const user = userEvent.setup();

  renderAt('/cards', '/cards', <CardListScreen />);
  await screen.findByRole('table');

  await user.keyboard('{F7}');

  expect(
    await screen.findByText(collapse(PROGRAM_MESSAGES.COCRDLIC.NO_PREVIOUS_PAGES_TO_DISPLAY)),
  ).toBeInTheDocument();
}

/**
 * Asserts a refused field carries the source literal exactly, trailing punctuation included.
 *
 * Assumptions: the expectation is the catalog value and nothing else. An earlier revision of the
 * screen appended a full stop to both expiry refusals, which the COBOL literals do not carry -- a
 * one-character divergence, which is the class this assertion exists for.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function updateRendersTheExactFieldRefusal(): Promise<void> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  const user = userEvent.setup();

  renderAt(`/cards/${CARD_SELECTOR}/edit`, CARD_EDIT_ROUTE, <CardUpdateScreen />);
  await screen.findByRole('heading', { name: CARD_UPDATE_TITLE });

  const monthEntry = screen.getByDisplayValue('01');
  await user.clear(monthEntry);
  await user.type(monthEntry, '13');
  await user.click(screen.getByRole('button', { name: 'ENTER=Process' }));

  const refusal = STATUS_MESSAGES.COCRDUPC.CARD_EXPIRY_MONTH_NOT_VALID.text;
  expect(await screen.findByText(collapse(refusal))).toBeInTheDocument();
  expect(screen.queryByText(`${collapse(refusal)}.`)).not.toBeInTheDocument();
}

/**
 * Asserts the browse permits a backward step only after a forward one, from its own screen ordinal.
 *
 * Assumptions: this is the substance of the four-member page envelope. Neither page below carries any
 * backward-availability member -- there is none to carry -- so the only thing that can distinguish the
 * refusal on the opening page from the accepted step on the second is the ordinal this screen holds,
 * which is the reference's own `WS-CA-SCREEN-NUM` at `app/cbl/COCRDLIC.cbl` L237. Both mocked pages
 * deliberately name a leading cursor, so a screen that gated on `firstKey` being present instead would
 * accept the backward step on the opening page and fail the first expectation here.
 *
 * Assumptions: the backward request is asserted to carry the LEADING cursor under direction
 * `previous`, because the position is what the envelope publishes for that purpose and the service
 * seals the direction into the token -- replaying it forward would be refused with HTTP 400.
 * @returns {Promise<void>} Resolves once the refusal, the forward step and the backward step have all
 *   been observed.
 */
async function listPagesBackwardOnlyAfterPagingForward(): Promise<void> {
  const openingPage: PageResponse<CardSummary> = {
    items: [ROW],
    firstKey: 'opening-leading-cursor',
    lastKey: 'opening-trailing-cursor',
    hasNext: true,
  };
  const secondPage: PageResponse<CardSummary> = {
    items: [ROW],
    firstKey: 'second-leading-cursor',
    lastKey: 'second-trailing-cursor',
    hasNext: false,
  };
  vi.mocked(listCards)
    .mockResolvedValueOnce(openingPage)
    .mockResolvedValueOnce(secondPage)
    .mockResolvedValue(openingPage);

  const user = userEvent.setup();
  renderAt('/cards', '/cards', <CardListScreen />);
  await screen.findByRole('table');

  await user.click(screen.getByRole('button', { name: 'F7=Backward' }));
  expect(
    await screen.findByText(collapse(PROGRAM_MESSAGES.COCRDLIC.NO_PREVIOUS_PAGES_TO_DISPLAY)),
  ).toBeInTheDocument();
  expect(vi.mocked(listCards)).toHaveBeenCalledTimes(1);

  await user.click(screen.getByRole('button', { name: 'F8=Forward' }));
  await waitFor(
    /**
     * Waits for the forward step to have reached the transport.
     * @returns {void} Nothing; throws until the second read has been issued.
     */
    () => {
      expect(vi.mocked(listCards)).toHaveBeenCalledTimes(2);
    },
  );
  expect(vi.mocked(listCards)).toHaveBeenLastCalledWith({
    cursor: 'opening-trailing-cursor',
    direction: 'next',
  });

  await user.click(screen.getByRole('button', { name: 'F7=Backward' }));
  await waitFor(
    /**
     * Waits for the backward step to have reached the transport.
     * @returns {void} Nothing; throws until the third read has been issued.
     */
    () => {
      expect(vi.mocked(listCards)).toHaveBeenCalledTimes(3);
    },
  );
  expect(vi.mocked(listCards)).toHaveBeenLastCalledWith({
    cursor: 'second-leading-cursor',
    direction: 'previous',
  });

  await user.click(screen.getByRole('button', { name: 'F7=Backward' }));
  expect(
    await screen.findByText(collapse(PROGRAM_MESSAGES.COCRDLIC.NO_PREVIOUS_PAGES_TO_DISPLAY)),
  ).toBeInTheDocument();
  expect(vi.mocked(listCards)).toHaveBeenCalledTimes(3);
}

/**
 * Registers every shell and text case.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function cardScreenShellCases(): void {
  afterEach(resetTransportMocks);

  it(
    'composes the header, the mapset title and the three painted keys on the browse',
    listComposesTheShell,
  );
  /*
   * WHY : ⚠️ Assumptions: this ONE case carries a per-case timeout above the suite's own, and the
   *       figure is measured rather than chosen. It is the only case here that asks Testing Library
   *       for roles by ACCESSIBLE NAME inside an antd `Table` -- two `getByRole('button', { name })`
   *       lookups and a `getAllByRole('columnheader')` -- and a role query resolves visibility for
   *       every candidate through `getComputedStyle`, which jsdom implements slowly and partially
   *       (it warns on pseudo-elements). Mounting the shared shell around the screen, which every
   *       case here now does because the screens delegate their band and legend, enlarges the tree
   *       those lookups walk: this case went from 3414ms to a consistent 10.4s while the other
   *       fifteen stayed level or got faster, several of them under 700ms.
   * WHY : Assumptions: the cost is the TEST environment's and not the application's, which was
   *       measured rather than assumed. An instrumented render counter showed `AppShell` rendering
   *       exactly TWICE across this case -- the initial mount and the one slot publication -- so
   *       there is no re-render storm to fix, and the structural equivalence check in
   *       `areSlotsRenderEquivalent` is what keeps it at two even though `usePfKeys` hands up a
   *       freshly built bindings array on every screen render.
   * WHY : Trade-offs: the allowance is given HERE rather than by raising the suite default again,
   *       so the 20s ceiling keeps applying to every other case and this outlier is visible at the
   *       one place it applies. Raising the default a second time would hide the next genuinely
   *       slow case behind an ever-larger number.
   */
  it(
    'heads the browse columns and its row controls with the mapset values',
    listRendersMapsetColumnsAndCodes,
    SLOW_ROLE_QUERY_TIMEOUT_MS,
  );
  it(
    'carries the source informational sentence on the browse',
    listRendersTheSourceInformationalSentence,
  );
  it(
    'reports both source paging refusals rather than disabling the keys',
    listReportsThePagingRefusals,
  );
  it(
    'steps backward only after stepping forward, from the screen ordinal it holds',
    listPagesBackwardOnlyAfterPagingForward,
  );
  it(
    'refuses a partial browse entry with the list screen own sentence',
    listRefusesAPartialEntryWithItsOwnSentence,
  );
  it(
    'composes the shell and the five mapset labels on the detail',
    detailComposesTheShellAndMapsetLabels,
  );
  it('re-reads the record when the detail Enter key is invoked', detailEnterRereadsTheRecord);
  it('reports a failed detail read with the source sentence', detailReportsTheSourceReadFailure);
  it(
    'withholds the not-found sentence from a statusless failure',
    detailWithholdsTheNotFoundSentenceFromAStatuslessFailure,
  );
  it(
    'withholds the non-display legend field on the update screen',
    updateComposesTheShellWithTheHiddenLegendWithheld,
  );
  it(
    'asks for confirmation without writing on the update screen',
    updateEnterAsksForConfirmationWithoutWriting,
  );
  it(
    'refuses an unchanged update submission with the source sentence',
    updateRefusesAnUnchangedSubmission,
  );
  it('writes once from the save key after confirmation', updateSaveKeyWritesOnceAfterConfirmation);
  it(
    'renders the exact source field refusal on the update screen',
    updateRendersTheExactFieldRefusal,
  );
  it(
    'discards the pending edit and restates the source prompt on cancel',
    updateCancelDiscardsAndRestatesThePrompt,
  );
  it(
    'dispatches a paging binding from a real keyboard press',
    listPagingKeyRespondsToARealKeyPress,
  );
}

describe('card screens compose the shared shell and render baseline text', cardScreenShellCases);
