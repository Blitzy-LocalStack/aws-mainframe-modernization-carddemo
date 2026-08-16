/**
 * @file Component and unit tests for the card detail screen in `ui/src/screens/cardDetail/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the two behaviours the screen re-expresses from `app/cbl/COCRDSLC.cbl` that cannot be read off
 * the rendering alone: the field-edit chain `2200-EDIT-MAP-INPUTS` performs, with its branch order, its
 * first-message-wins precedence, its both-blank override and its cursor rule; and the status-selected
 * sentence a failed read reports, which replaced a single sentence covering every outcome.
 *
 * Assumptions: the two edit paragraphs are exercised through the exported pure function rather than
 * through the rendering, because the reference's precedence is a property of the CHAIN -- which field
 * claims the message -- and a rendering shows only its result. The component cases then assert that the
 * chain is actually wired to the Enter turn, which the unit cases cannot show.
 *
 * Assumptions: every card number and account number below is fabricated. `4111111111111111` is the
 * reserved test-number prefix, and the account identifiers are sequential digits.
 */

import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as CardsModule from '../../api/cards';
import type { CardDetail } from '../../api/types';
import type { ApiError } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import {
  ACCESS_DENIED_ADMIN_ONLY,
  CARD_DETAIL_INVALID_LINK_GUIDANCE,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
} from '../../messages/messages';
import { CARD_SELECTOR_LENGTH } from '../../routes/cards';
import { cardDemoTheme } from '../../theme/antdTheme';

const getCardMock = vi.fn();
const lookupCardMock = vi.fn();

// Assumptions: the card client is mocked rather than the HTTP client, because these cases are about
// what the SCREEN does with each outcome -- which sentence it shows, which address it enters -- and
// not about how a request is serialised. The client's own conformance is asserted where it is defined.
vi.mock(
  '../../api/cards',
  /**
   * Replaces the two read operations this screen performs, leaving every other export intact.
   * @returns {Promise<typeof CardsModule>} The real module with the two reads stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof CardsModule>('../../api/cards');
    return {
      ...actual,
      /**
       * Stands in for the selector-addressed read.
       * @returns {Promise<unknown>} Whatever the test configured.
       */
      getCard: getCardMock,
      /**
       * Stands in for the card-number resolution.
       * @returns {Promise<unknown>} Whatever the test configured.
       */
      lookupCard: lookupCardMock,
    };
  },
);

const { CardDetailScreen, describeRetrievalFailure, editCardSearchInputs } =
  await import('./index');

const MESSAGES = STATUS_MESSAGES.COCRDSLC;

/**
 * A selector of the published shape, standing in for one a card response minted.
 *
 * Assumptions: it is exactly `CARD_SELECTOR_LENGTH` URL-safe characters, and the length is imported
 * rather than written as a literal. `ui/src/routes/cards.ts` refuses any other length outright, so a
 * hand-counted string would make this suite fail on a contract change for a reason unrelated to the
 * behaviour under test -- which is exactly what a first draft of this file did.
 */
const A_SELECTOR = 'A'.repeat(CARD_SELECTOR_LENGTH);

/** A card number of the declared sixteen-digit width. */
const A_CARD_NUMBER = '4111111111111111';

/** An account number of the declared eleven-digit width. */
const AN_ACCOUNT_NUMBER = '00000000123';

/**
 * Builds the record the two stubbed reads answer with.
 *
 * Assumptions: `displayCardNumber` is the rendered form the contract publishes -- twelve asterisks
 * then four digits -- because that is the only rendering an ordinary read may return, and the screen is
 * asserted to display it unchanged.
 * @param {string} key - The selector the record is addressed by.
 * @returns {CardDetail} A complete record, so no case depends on an absent member.
 */
function aCard(key: string): CardDetail {
  return {
    key,
    displayCardNumber: '************1111',
    accountId: AN_ACCOUNT_NUMBER,
    activeStatus: 'Y',
    embossedName: 'A CARDHOLDER',
    expirationDate: '2029-01-31',
    version: 1,
  };
}

/**
 * Builds a problem document carrying one status, as the transport delivers one.
 *
 * Assumptions: every member the published `ApiError` declares is supplied, because a partial document
 * describes a response the service cannot send and would let a narrowing pass that the real shape
 * fails.
 * @param {number} status - The HTTP status the failure carries.
 * @param {string | null} message - The service's own sentence, or `null` when it sent none.
 * @returns {{ problem: ApiError }} A rejection value of the shape the client raises.
 */
function refusal(status: number, message: string | null): { readonly problem: ApiError } {
  return {
    problem: {
      code: 'CARD0001',
      // WHY : Assumptions: the secondary code is a string and not null, because `ApiError` declares it
      //       required -- the contract carries an empty string for "no secondary code" rather than an
      //       absent member, and a document that omitted it would not be one the service can send.
      secondaryCode: '',
      message,
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

/**
 * Renders the screen at an address, with the two onward destinations stubbed.
 *
 * ⚠️ Refactoring Rationale: the `AppShell` is INSIDE the tree, where these cases first rendered the
 * screen bare. The screen delegates its row-23 message line, its title band and its key legend to the
 * one shell `ui/src/App.tsx` mounts, publishing them through `useShellSlot`, so a bare screen paints no
 * band at all and every case reading `bandText()` would fail for the wrong reason -- reporting a missing
 * element where the sentence is in fact correct. The shell is mounted with no session because none of
 * these cases signs on; it renders its frame regardless and adds only its own sign-off control.
 * @param {string} address - The address to enter the router at.
 * @returns {ReactElement} The tree to render.
 */
function renderAt(address: string): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={[address]}>
        <AppShell>
          <Routes>
            <Route path="/cards/:cardKey" element={<CardDetailScreen />} />
            <Route path="/cards/:cardKey/edit" element={<div>CARD UPDATE</div>} />
            <Route path="/cards" element={<div>CARD BROWSE</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Reads the sentence the message band currently holds.
 * @returns {string} The band's text, which is the empty string when it holds none.
 */
function bandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/**
 * Asserts that the edit chain reports the both-blank override rather than either field prompt.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheBothBlankOverrideRatherThanEitherFieldPrompt(): void {
  /*
   * WHY : Assumptions: this asserts the ONE exception to first-message-wins in the paragraph. The
   *       account edit claims the field with `Account number not provided` at
   *       `app/cbl/COCRDSLC.cbl` L654-L657, the card edit is then blocked by `IF WS-RETURN-MSG-OFF`,
   *       and the cross-field edit at L637-L639 replaces the whole thing UNCONDITIONALLY. So the
   *       sentence an operator sees for an empty screen is neither field's own.
   */
  const outcome = editCardSearchInputs('', '');

  expect(outcome.message).toBe(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text);
  expect(outcome.account).toBe('BLANK');
  expect(outcome.card).toBe('BLANK');
  expect(outcome.cursor).toBe('account');
  expect(outcome.accountId).toBeNull();
  expect(outcome.cardNumber).toBeNull();
}

/**
 * Asserts that the edit chain treats the blank marker it wrote as no input on the next turn.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function treatsTheBlankMarkerItWroteAsNoInputOnTheNextTurn(): void {
  /*
   * WHY : Assumptions: this closes the round trip the reference opens -- L543 and L549 write `'*'`
   *       into the two fields and L615 and L622 read it back, so an operator pressing Enter twice on
   *       an empty screen must be told the same thing twice rather than that the marker is a
   *       malformed number.
   */
  expect(editCardSearchInputs('*', '*').message).toBe(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text);
}

/**
 * Asserts that the edit chain prompts for the account alone when only the card was supplied.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function promptsForTheAccountAloneWhenOnlyTheCardWasSupplied(): void {
  const outcome = editCardSearchInputs('', A_CARD_NUMBER);

  expect(outcome.message).toBe(MESSAGES.WS_PROMPT_FOR_ACCT.text);
  expect(outcome.account).toBe('BLANK');
  expect(outcome.card).toBe('ISVALID');
  expect(outcome.cursor).toBe('account');
}

/**
 * Asserts that the edit chain prompts for the card alone when only the account was supplied.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function promptsForTheCardAloneWhenOnlyTheAccountWasSupplied(): void {
  /*
   * WHY : Assumptions: an account-only turn is REFUSED, and that is the reference's behaviour rather
   *       than a restriction added here. `2220-EDIT-CARD` sets `INPUT-ERROR` when its field is blank
   *       (L692-L697), and `9000-READ-DATA` is reached only when no input error was set (L360-L366),
   *       so the by-account read at L779 -- which is never performed from anywhere -- could not be
   *       reached even if it were.
   */
  const outcome = editCardSearchInputs(AN_ACCOUNT_NUMBER, '');

  expect(outcome.message).toBe(MESSAGES.WS_PROMPT_FOR_CARD.text);
  expect(outcome.account).toBe('ISVALID');
  expect(outcome.card).toBe('BLANK');
  expect(outcome.cursor).toBe('card');
  expect(outcome.cardNumber).toBeNull();
}

/**
 * Asserts that the edit chain refuses an account number narrower than its declared width.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesAnAccountNumberNarrowerThanItsDeclaredWidth(): void {
  const outcome = editCardSearchInputs('123', A_CARD_NUMBER);

  expect(outcome.message).toBe(
    SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
  );
  expect(outcome.account).toBe('NOT_OK');
  expect(outcome.cursor).toBe('account');
}

/**
 * Asserts that the edit chain refuses a card number narrower than its declared width.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesACardNumberNarrowerThanItsDeclaredWidth(): void {
  const outcome = editCardSearchInputs(AN_ACCOUNT_NUMBER, '4111');

  expect(outcome.message).toBe(
    SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
  );
  expect(outcome.card).toBe('NOT_OK');
  expect(outcome.cursor).toBe('card');
}

/**
 * Asserts that the edit chain lets the account edit claim the message when both fields are malformed.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function letsTheAccountEditClaimTheMessageWhenBothFieldsAreMalformed(): void {
  /*
   * WHY : Assumptions: this is the first-wins precedence itself, and it is only observable when BOTH
   *       fields are refused -- the account edit runs first at L630-L631 and every `MOVE` to the
   *       message field in the card edit is guarded by `IF WS-RETURN-MSG-OFF` at L709.
   */
  const outcome = editCardSearchInputs('123', '4111');

  expect(outcome.message).toBe(
    SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
  );
  expect(outcome.account).toBe('NOT_OK');
  expect(outcome.card).toBe('NOT_OK');
}

/**
 * Asserts that the edit chain treats an all-zeroes account number as no input rather than as malformed.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function treatsAnAllZeroesAccountNumberAsNoInputRatherThanAsMalformed(): void {
  /*
   * WHY : Assumptions: the all-zeroes case is the THIRD leg of the not-supplied test at L651-L653,
   *       not a separate test after it, so it reports the prompt and not the width sentence. Getting
   *       this wrong is invisible until an operator types eleven zeros.
   */
  const outcome = editCardSearchInputs('0'.repeat(11), A_CARD_NUMBER);

  expect(outcome.account).toBe('BLANK');
  expect(outcome.message).toBe(MESSAGES.WS_PROMPT_FOR_ACCT.text);
}

/**
 * Asserts that the edit chain treats an all-zeroes card number as no input rather than as malformed.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function treatsAnAllZeroesCardNumberAsNoInputRatherThanAsMalformed(): void {
  const outcome = editCardSearchInputs(AN_ACCOUNT_NUMBER, '0'.repeat(16));

  expect(outcome.card).toBe('BLANK');
  expect(outcome.message).toBe(MESSAGES.WS_PROMPT_FOR_CARD.text);
}

/**
 * Asserts that the edit chain accepts a complete pair and reports no sentence.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function acceptsACompletePairAndReportsNoSentence(): void {
  const outcome = editCardSearchInputs(AN_ACCOUNT_NUMBER, A_CARD_NUMBER);

  expect(outcome.message).toBeNull();
  expect(outcome.account).toBe('ISVALID');
  expect(outcome.card).toBe('ISVALID');
  expect(outcome.accountId).toBe(AN_ACCOUNT_NUMBER);
  expect(outcome.cardNumber).toBe(A_CARD_NUMBER);
  // WHY : Assumptions: the cursor returns to the account field on an accepted turn, which is the
  //       `WHEN OTHER` arm of the cursor `EVALUATE` at L522-L523 -- that field carries the mapset's
  //       single `IC` attribute, so it holds the cursor whatever happened on the previous turn.
  expect(outcome.cursor).toBe('account');
}

/**
 * Asserts that the failure mapping reports the not-found sentence for a 404 and for nothing else.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheNotFoundSentenceForA404AndForNothingElse(): void {
  expect(describeRetrievalFailure(refusal(404, null))).toBe(
    MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text,
  );
  for (const status of [400, 403, 500, 503]) {
    expect(describeRetrievalFailure(refusal(status, null))).not.toBe(
      MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text,
    );
  }
}

/**
 * Asserts that the failure mapping reports nothing for a refused session, leaving the transport to end it.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsNothingForARefusedSessionLeavingTheTransportToEndIt(): void {
  /*
   * WHY : Assumptions: `null` is the assertion, not an oversight. The transport ends the session on a
   *       401 answered to a bearer-carrying request, so the route guard replaces this screen within
   *       the same paint; a sentence here would either flash for one frame or imply the card is
   *       missing when the session is.
   */
  expect(describeRetrievalFailure(refusal(401, 'Unauthorized'))).toBeNull();
}

/**
 * Asserts that the failure mapping reports the authority refusal for a 403.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheAuthorityRefusalForA403(): void {
  expect(describeRetrievalFailure(refusal(403, null))).toBe(ACCESS_DENIED_ADMIN_ONLY);
}

/**
 * Asserts that the failure mapping reports the card-file read error for a service fault and for a write window.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheCardFileReadErrorForAServiceFaultAndForAWriteWindow(): void {
  expect(describeRetrievalFailure(refusal(500, null))).toBe(MESSAGES.XREF_READ_ERROR.text);
  expect(describeRetrievalFailure(refusal(503, null))).toBe(MESSAGES.XREF_READ_ERROR.text);
}

/**
 * Asserts that the failure mapping shows the service's own sentence for a refused request that carried one.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function showsTheServiceSOwnSentenceForARefusedRequestThatCarriedOne(): void {
  expect(describeRetrievalFailure(refusal(400, 'Selector cannot be opened'))).toBe(
    'Selector cannot be opened',
  );
}

/**
 * Asserts that the failure mapping falls back to the abend sentence for a refusal that carried no sentence.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function fallsBackToTheAbendSentenceForARefusalThatCarriedNoSentence(): void {
  expect(describeRetrievalFailure(refusal(400, null))).toBe(
    SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  );
}

/**
 * Asserts that the failure mapping falls back to the abend sentence for a rejection carrying no problem document.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function fallsBackToTheAbendSentenceForARejectionCarryingNoProblemDocument(): void {
  /*
   * WHY : Assumptions: a `RangeError` is the shape the client raises for its own argument and
   *       response checks, so this arm is reached by a malformed response rather than by a refusal --
   *       which is why it reports an abend and not a card that does not exist.
   */
  expect(describeRetrievalFailure(new RangeError('malformed response'))).toBe(
    SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  );
}

/**
 * Asserts that the screen paints both search fields protected beside a record the address named.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function paintsBothSearchFieldsProtectedBesideARecordTheAddressNamed(): Promise<void> {
  getCardMock.mockResolvedValue(aCard(A_SELECTOR));

  render(renderAt(`/cards/${A_SELECTOR}`));

  const account = await screen.findByLabelText(/Account Number/u);
  const cardNumber = screen.getByLabelText(/Card Number/u);

  /*
   * WHY : Assumptions: both controls are DISABLED on this arrival, which is
   *       `1300-SETUP-SCREEN-ATTRS` moving `DFHBMPRF` into both fields when the caller was the browse
   *       program (`app/cbl/COCRDSLC.cbl` L505-L509). Rendering them editable here would offer a
   *       second way to change the record on display that the reference refuses.
   */
  expect(account).toBeDisabled();
  expect(cardNumber).toBeDisabled();
  expect(account).toHaveValue(AN_ACCOUNT_NUMBER);
  expect(cardNumber).toHaveValue('************1111');
  expect(account).toHaveAttribute('maxlength', '11');
  expect(cardNumber).toHaveAttribute('maxlength', '16');
}

/**
 * Asserts that the screen paints both search fields open when the address names no card.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function paintsBothSearchFieldsOpenWhenTheAddressNamesNoCard(): Promise<void> {
  render(renderAt('/cards/not-a-selector'));

  const account = await screen.findByLabelText(/Account Number/u);

  expect(account).toBeEnabled();
  expect(screen.getByLabelText(/Card Number/u)).toBeEnabled();
  // WHY : Assumptions: no request is issued on this arrival, because there is nothing to address.
  //       The reference sends the map and returns without reaching `9000-READ-DATA` (L350-L356).
  expect(getCardMock).not.toHaveBeenCalled();
  expect(screen.getByText(CARD_DETAIL_INVALID_LINK_GUIDANCE)).toBeInTheDocument();
  expect(account).toHaveFocus();
}

/**
 * Asserts that the screen refuses an empty turn with the override sentence and marks both fields.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function refusesAnEmptyTurnWithTheOverrideSentenceAndMarksBothFields(): Promise<void> {
  render(renderAt('/cards/not-a-selector'));
  const account = await screen.findByLabelText(/Account Number/u);

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the refusal to reach the band.
     * @returns {void} Nothing; the assertion is the wait condition.
     */
    (): void => {
      expect(bandText()).toContain(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text);
    },
  );
  /*
   * WHY : Assumptions: the marker is asserted in the control's VALUE and not merely as rendered
   *       text, because the reference writes it into the field's own output subfield and reads it
   *       back on the next turn -- a marker that existed only in the rendering would leave the
   *       control holding an empty string.
   */
  expect(account).toHaveValue('*');
  expect(screen.getByLabelText(/Card Number/u)).toHaveValue('*');
  expect(account).toHaveAttribute('aria-invalid', 'true');
  expect(lookupCardMock).not.toHaveBeenCalled();
}

/**
 * Asserts that the screen resolves a typed pair to the address the record is published under.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function resolvesATypedPairToTheAddressTheRecordIsPublishedUnder(): Promise<void> {
  lookupCardMock.mockResolvedValue(aCard(A_SELECTOR));
  getCardMock.mockResolvedValue(aCard(A_SELECTOR));

  render(renderAt('/cards/not-a-selector'));
  const account = await screen.findByLabelText(/Account Number/u);

  await userEvent.type(account, AN_ACCOUNT_NUMBER);
  await userEvent.type(screen.getByLabelText(/Card Number/u), A_CARD_NUMBER);
  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the resolution to be requested.
     * @returns {void} Nothing; the assertion is the wait condition.
     */
    (): void => {
      expect(lookupCardMock).toHaveBeenCalledWith(A_CARD_NUMBER);
    },
  );
  /*
   * WHY : Assumptions: the record is reached by NAVIGATING to its selector address, so the selector
   *       read runs afterwards. That is what keeps the record on display equal to the record the
   *       address names, which a reload and a shared link both depend on.
   */
  await waitFor(
    /**
     * Waits for the selector-addressed read the navigation triggers.
     * @returns {void} Nothing; the assertion is the wait condition.
     */
    (): void => {
      expect(getCardMock).toHaveBeenCalledWith(A_SELECTOR);
    },
  );
}

/**
 * Asserts that the screen reports a failed read with the sentence its status selects.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function reportsAFailedReadWithTheSentenceItsStatusSelects(): Promise<void> {
  getCardMock.mockRejectedValue(refusal(500, null));

  render(renderAt(`/cards/${A_SELECTOR}`));

  await waitFor(
    /**
     * Waits for the service-fault sentence to reach the band.
     * @returns {void} Nothing; the assertion is the wait condition.
     */
    (): void => {
      expect(bandText()).toContain(MESSAGES.XREF_READ_ERROR.text);
    },
  );
  expect(bandText()).not.toContain(MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text);
}
/**
 * Registers the edit-chain cases.
 * @returns {void} Registration is the effect.
 */
function editChainCases(): void {
  it(
    'reports the both-blank override rather than either field prompt',
    reportsTheBothBlankOverrideRatherThanEitherFieldPrompt,
  );
  it(
    'treats the blank marker it wrote as no input on the next turn',
    treatsTheBlankMarkerItWroteAsNoInputOnTheNextTurn,
  );
  it(
    'prompts for the account alone when only the card was supplied',
    promptsForTheAccountAloneWhenOnlyTheCardWasSupplied,
  );
  it(
    'prompts for the card alone when only the account was supplied',
    promptsForTheCardAloneWhenOnlyTheAccountWasSupplied,
  );
  it(
    'refuses an account number narrower than its declared width',
    refusesAnAccountNumberNarrowerThanItsDeclaredWidth,
  );
  it(
    'refuses a card number narrower than its declared width',
    refusesACardNumberNarrowerThanItsDeclaredWidth,
  );
  it(
    'lets the account edit claim the message when both fields are malformed',
    letsTheAccountEditClaimTheMessageWhenBothFieldsAreMalformed,
  );
  it(
    'treats an all-zeroes account number as no input rather than as malformed',
    treatsAnAllZeroesAccountNumberAsNoInputRatherThanAsMalformed,
  );
  it(
    'treats an all-zeroes card number as no input rather than as malformed',
    treatsAnAllZeroesCardNumberAsNoInputRatherThanAsMalformed,
  );
  it('accepts a complete pair and reports no sentence', acceptsACompletePairAndReportsNoSentence);
}

/**
 * Registers the status-mapping cases.
 * @returns {void} Registration is the effect.
 */
function failureMappingCases(): void {
  it(
    'reports the not-found sentence for a 404 and for nothing else',
    reportsTheNotFoundSentenceForA404AndForNothingElse,
  );
  it(
    'reports nothing for a refused session, leaving the transport to end it',
    reportsNothingForARefusedSessionLeavingTheTransportToEndIt,
  );
  it('reports the authority refusal for a 403', reportsTheAuthorityRefusalForA403);
  it(
    'reports the card-file read error for a service fault and for a write window',
    reportsTheCardFileReadErrorForAServiceFaultAndForAWriteWindow,
  );
  it(
    "shows the service's own sentence for a refused request that carried one",
    showsTheServiceSOwnSentenceForARefusedRequestThatCarriedOne,
  );
  it(
    'falls back to the abend sentence for a refusal that carried no sentence',
    fallsBackToTheAbendSentenceForARefusalThatCarriedNoSentence,
  );
  it(
    'falls back to the abend sentence for a rejection carrying no problem document',
    fallsBackToTheAbendSentenceForARejectionCarryingNoProblemDocument,
  );
}

/**
 * Registers the rendering cases.
 * @returns {void} Registration is the effect.
 */
function renderingCases(): void {
  it(
    'paints both search fields protected beside a record the address named',
    paintsBothSearchFieldsProtectedBesideARecordTheAddressNamed,
  );
  it(
    'paints both search fields open when the address names no card',
    paintsBothSearchFieldsOpenWhenTheAddressNamesNoCard,
  );
  it(
    'refuses an empty turn with the override sentence and marks both fields',
    refusesAnEmptyTurnWithTheOverrideSentenceAndMarksBothFields,
  );
  it(
    'resolves a typed pair to the address the record is published under',
    resolvesATypedPairToTheAddressTheRecordIsPublishedUnder,
  );
  it(
    'reports a failed read with the sentence its status selects',
    reportsAFailedReadWithTheSentenceItsStatusSelects,
  );
}

beforeEach(
  /**
   * Clears both stubs so no case inherits another's programming.
   * @returns {void} Completion is the reset stubs.
   */
  (): void => {
    getCardMock.mockReset();
    lookupCardMock.mockReset();
  },
);

afterEach(
  /**
   * Restores real timers and stub state after each case.
   * @returns {void} Completion is the restored state.
   */
  (): void => {
    vi.clearAllMocks();
  },
);

describe('card detail search edits', editChainCases);
describe('card detail failure mapping', failureMappingCases);
describe('card detail rendering', renderingCases);
