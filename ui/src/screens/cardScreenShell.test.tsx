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
 * Renders one screen at a concrete path inside an in-memory router.
 * @param {string} path - Initial location for the router.
 * @param {string} routePattern - Route pattern the element is mounted at.
 * @param {ReactElement} element - The screen under test.
 * @returns {void} Nothing; the tree is rendered into the test document.
 */
function renderAt(path: string, routePattern: string, element: ReactElement): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={routePattern} element={element} />
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
  expect(
    within(table).getByRole('button', { name: CARD_LIST_ROW_ACTION_CODES.detail }),
  ).toBeInTheDocument();
  expect(
    within(table).getByRole('button', { name: CARD_LIST_ROW_ACTION_CODES.update }),
  ).toBeInTheDocument();
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

  expect(
    await screen.findByText(
      collapse(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER),
    ),
  ).toBeInTheDocument();
  expect(
    screen.queryByText(collapse(STATUS_MESSAGES.COCRDSLC.SEARCHED_CARD_NOT_NUMERIC.text)),
  ).not.toBeInTheDocument();
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
  for (const label of Object.values(CARD_DETAIL_FIELD_LABELS)) {
    expect(screen.getByText(collapse(label))).toBeInTheDocument();
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
 * Asserts the detail screen reports a failed read with its source program's own sentence.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function detailReportsTheSourceReadFailure(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(new Error('transport'));

  renderAt(`/cards/${CARD_SELECTOR}`, CARD_DETAIL_ROUTE, <CardDetailScreen />);

  expect(
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDSLC.DID_NOT_FIND_ACCTCARD_COMBO.text)),
  ).toBeInTheDocument();
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
 * Asserts the save key writes exactly once, and only after the confirmation turn.
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
 * Assumptions: the prompt must SURVIVE the read that the cancel arm triggers. The reader clears the
 * band before issuing its request and the arm states its message straight afterwards, so a reader that
 * cleared the band on arrival instead would blank a message that had already been set -- which is the
 * regression this case exists to catch, and it is invisible to any assertion made before the read
 * settles.
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
    await screen.findByText(collapse(STATUS_MESSAGES.COCRDUPC.PROMPT_FOR_CHANGES.text)),
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
  it(
    'heads the browse columns and its row controls with the mapset values',
    listRendersMapsetColumnsAndCodes,
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
