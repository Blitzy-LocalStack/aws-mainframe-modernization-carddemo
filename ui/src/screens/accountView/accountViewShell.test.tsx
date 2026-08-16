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
