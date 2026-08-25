/**
 * @file Component test proving the account-view screen paints a header band.
 *
 * Purpose
 * -------
 * This screen painted NO header band at all before the shell was mounted as the authenticated
 * layout route: it rendered neither `ScreenHeader` nor an import of it, so five of the six header
 * slots the mapset declares had no source and the sixth -- the instant -- was never read, because
 * the screen called no clock hook either. It now supplies its identity and its instant through the
 * shell slot instead, and this file asserts the outcome that arrangement is supposed to produce.
 *
 * Why the header is asserted through the shell rather than in isolation
 * --------------------------------------------------------------------
 * Assumptions: the screen is rendered as a CHILD of the real `AppShell`, with no header props of its
 * own, because that is the only arrangement in which the header can appear. Rendering the screen
 * alone would prove nothing about the delegation, and rendering `ScreenHeader` directly would prove
 * only that `ScreenHeader` works -- which its own file already covers. Every value asserted below
 * can therefore have reached the DOM by exactly one route: the screen published it and the shell
 * painted it.
 *
 * Trade-offs: only the transport module is substituted. The server-clock hook is left real, matching
 * `ui/src/screens/cardScreenShell.test.tsx`, so the date and time slots render whatever a screen
 * with no clock response yet renders -- their PROMPTS are asserted rather than their values, since a
 * value asserted here would be asserting the clock and not the header.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { AppShell } from '../../layout/AppShell';
import { HEADER_PROMPT_LABELS } from '../../layout/ScreenHeader';
import { APP_ORGANISATION_TITLE_DISPLAY, APP_TITLE_DISPLAY } from '../../messages/messages';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The one transport function this screen imports, as a spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return { readAccountView: vi.fn() };
}

vi.mock('../../api/accounts', mockAccountTransportModule);

/**
 * Renders the real account-view screen as a child of the real shell.
 *
 * Assumptions: NO header props are passed to the screen, which is the point of the case. The screen
 * is reached at its own address so the arrangement matches the route table.
 * @returns {Promise<void>} Resolves once the screen module has been imported and mounted.
 */
async function renderAccountViewInsideShell(): Promise<void> {
  const { AccountViewScreen } = await import('./index');
  render(
    <MemoryRouter initialEntries={['/account/view']}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/account/view" element={<AccountViewScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * The screen supplies all six header slots the mapset declares, through the shell.
 *
 * Assumptions: the two identity values are asserted from the screen's own exported constants rather
 * than from literals, so a change to either constant fails here instead of drifting silently. The
 * four remaining slots are asserted by their prompt spelling, which is what the mapset paints
 * beside each value.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsAllSixHeaderSlotsThroughTheShell(): Promise<void> {
  const { ACCOUNT_VIEW_PROGRAM_NAME, ACCOUNT_VIEW_TRANSACTION_ID } = await import('./index');

  await renderAccountViewInsideShell();

  const header = screen.getByRole('banner');
  expect(within(header).getByText(ACCOUNT_VIEW_TRANSACTION_ID)).toBeInTheDocument();
  expect(within(header).getByText(ACCOUNT_VIEW_PROGRAM_NAME)).toBeInTheDocument();
  expect(within(header).getByText(APP_ORGANISATION_TITLE_DISPLAY)).toBeInTheDocument();
  expect(within(header).getByText(APP_TITLE_DISPLAY)).toBeInTheDocument();
  expect(within(header).getByText(HEADER_PROMPT_LABELS.date)).toBeInTheDocument();
  expect(within(header).getByText(HEADER_PROMPT_LABELS.time)).toBeInTheDocument();
}

/**
 * The screen reads no account until an operator supplies a key.
 *
 * Assumptions: this is asserted alongside the header because it is what makes the header assertion
 * above meaningful. If the screen issued a read on mount, the header could have been painted during
 * a response-driven re-render rather than on the first paint, and the case would no longer be
 * proving that the delegation holds from the start.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function readsNothingBeforeAKeyIsSupplied(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');

  await renderAccountViewInsideShell();

  expect(vi.mocked(readAccountView)).not.toHaveBeenCalled();
}

/**
 * Registers the header-composition cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function accountViewHeaderCases(): void {
  it('paints all six header slots through the shell', paintsAllSixHeaderSlotsThroughTheShell);
  it('reads no account before a key is supplied', readsNothingBeforeAKeyIsSupplied);
}

describe('AccountViewScreen header band', accountViewHeaderCases);

/**
 * The row-22 advisory is painted by the FRAME and not inside the scrolling content.
 *
 * ⚠️ Purpose: this screen composed its own row-22 band as the last child of its body, and a browser
 * measurement of that arrangement is what withdrew it: the band's rect top was 1270.39 in an
 * 860-pixel viewport, so the operator's acknowledgement sentence was painted 410 pixels below the
 * fold while the frame's own row-22 zone stood reserved and empty. The property that fails when the
 * body-composed band returns is the ANCESTRY, so the ancestry is what is asserted -- the band's
 * nearest `main` is `null` and its parent is the frame's pinned zone.
 *
 * Assumptions: geometry is not asserted, and cannot be. jsdom performs no layout, so every rectangle
 * it reports is zero; the structural claim is the strongest one available here and is also the one
 * that discriminates, because a band inside `main` scrolls whatever its measured rectangle happens to
 * be on a given viewport.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheRow22LineOutsideTheScrollingContent(): Promise<void> {
  const { INFORMATION_BAND_TEST_ID } = await import('../../layout/MessageBand');
  const { SHELL_PINNED_ZONE_TEST_ID } = await import('../../layout/AppShell');

  await renderAccountViewInsideShell();

  const band = screen.getByTestId(INFORMATION_BAND_TEST_ID);
  expect(band.closest('main')).toBeNull();
  expect(band.parentElement).toBe(screen.getByTestId(SHELL_PINNED_ZONE_TEST_ID));
}

/**
 * Exactly one row-22 band exists on the document.
 *
 * Assumptions: the count is asserted rather than mere presence, because the failure this guards is a
 * DUPLICATE rather than an absence. The mapset declares one such field -- `INFOMSG` at `POS=(22,23)`
 * -- and a screen that both publishes the slot and paints its own band renders two live regions for
 * one sentence, which is the state this screen was in for two revisions.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function paintsExactlyOneRow22Band(): Promise<void> {
  const { INFORMATION_BAND_TEST_ID } = await import('../../layout/MessageBand');

  await renderAccountViewInsideShell();

  expect(screen.queryAllByTestId(INFORMATION_BAND_TEST_ID)).toHaveLength(1);
}

/**
 * The row-22 line carries the reference's opening prompt before any account has been read.
 *
 * Assumptions: the sentence is read from the catalog rather than written as a literal, and the state
 * asserted is the PRE-READ one, because that is the turn on which the reference guarantees this
 * sentence: `app/cbl/COACTVWC.cbl` L462-L463 sets `WS-PROMPT-FOR-INPUT` when no data was passed and
 * L528-L530 sets it again whenever the information field is empty, so the prompt is the line's floor
 * rather than merely its first value. A band that is present but empty on this turn would reserve the
 * row and tell the operator nothing.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function statesTheOpeningPromptOnRow22(): Promise<void> {
  const { INFORMATION_BAND_TEST_ID } = await import('../../layout/MessageBand');
  const { STATUS_MESSAGES } = await import('../../messages/messages');

  await renderAccountViewInsideShell();

  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(
    STATUS_MESSAGES.COACTVWC.WS_PROMPT_FOR_INPUT.text,
  );
}

/**
 * Row 22 is painted above row 23, which is the order the mapset declares.
 *
 * Assumptions: the order is read from `compareDocumentPosition` rather than from a rendered geometry,
 * for the reason recorded above -- jsdom lays nothing out. Document order is what the frame controls
 * and what a screen reader follows, and it is the property the two adjacent mapset rows fix:
 * `INFOMSG` at `POS=(22,23)` precedes `ERRMSG` at `POS=(23,1)`.
 *
 * Assumptions: the row-23 band is addressed by its own test identifier rather than by text, because it
 * carries none on this turn -- the screen publishes a null error sentence until something is refused --
 * and the frame renders the zone regardless so the row stays reserved.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function paintsRow22AboveRow23(): Promise<void> {
  const { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } =
    await import('../../layout/MessageBand');

  await renderAccountViewInsideShell();

  const information = screen.getByTestId(INFORMATION_BAND_TEST_ID);
  const refusal = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  // Node.DOCUMENT_POSITION_FOLLOWING is 4: the compared node comes after the reference node.
  expect(information.compareDocumentPosition(refusal) & Node.DOCUMENT_POSITION_FOLLOWING).toBe(
    Node.DOCUMENT_POSITION_FOLLOWING,
  );
}

/**
 * Registers the row-22 delegation cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function accountViewInformationBandCases(): void {
  it(
    'paints the row-22 line outside the scrolling content',
    paintsTheRow22LineOutsideTheScrollingContent,
  );
  it('paints exactly one row-22 band', paintsExactlyOneRow22Band);
  it('states the opening prompt on row 22', statesTheOpeningPromptOnRow22);
  it('paints row 22 above row 23', paintsRow22AboveRow23);
}

describe('AccountViewScreen information band', accountViewInformationBandCases);
