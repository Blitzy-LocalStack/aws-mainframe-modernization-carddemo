// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../../api/auth';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import { PROGRAM_MESSAGES, THANK_YOU_CCDA } from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';

const signOnMock = vi.fn();
const answerChallengeMock = vi.fn();

// Assumptions: the auth client is mocked rather than the HTTP client, because these cases are about
// what the SCREEN does with each outcome -- which message it shows, which route it enters -- and not
// about how the request is serialized. The client's own contract conformance is asserted where the
// client is defined.
vi.mock(
  '../../api/auth',
  /**
   * Replaces the two network operations while leaving every other export intact.
   * @returns {Promise<typeof AuthModule>} The real module with the two operations stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof AuthModule>('../../api/auth');
    return {
      ...actual,
      /**
       * Stands in for the sign-on operation.
       * @returns {Promise<unknown>} Whatever the test configured.
       */
      signOn: signOnMock,
      /**
       * Stands in for the challenge-answer operation.
       * @returns {Promise<unknown>} Whatever the test configured.
       */
      answerSignOnChallenge: answerChallengeMock,
    };
  },
);

const { SignOnScreen } = await import('./index');

const MESSAGES = PROGRAM_MESSAGES.COSGN00C;

/**
 * Renders the sign-on screen inside the theme and a router that reports the entered route.
 * @returns {ReactElement} The composed tree under test.
 */
function renderSignOn(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/signon']}>
        <Routes>
          <Route path="/signon" element={<SignOnScreen />} />
          <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          <Route path="/admin" element={<div>ADMIN MENU</div>} />
        </Routes>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Builds an identity token whose claim carries the administrative group.
 * @returns {string} A three-segment token decoding to the administrative group.
 */
function adminIdToken(): string {
  const payload = btoa(JSON.stringify({ 'cognito:groups': ['carddemo-admin'] }));
  return `header.${payload}.signature`;
}

/**
 * Builds an identity token whose claim carries only the ordinary operator group.
 * @returns {string} A three-segment token decoding to the ordinary operator group.
 */
function userIdToken(): string {
  const payload = btoa(JSON.stringify({ 'cognito:groups': ['carddemo-user'] }));
  return `header.${payload}.signature`;
}

/**
 * Builds an authenticated outcome carrying the supplied identity token.
 * @param {string} idToken - Identity token to return.
 * @returns {object} An authenticated sign-on outcome.
 */
function authenticated(idToken: string): object {
  return {
    outcome: 'AUTHENTICATED',
    userId: 'USER0001',
    accessToken: 'access-token-value',
    idToken,
    tokenType: 'Bearer',
    expiresIn: 3600,
  };
}

/**
 * Clears the retained session and both client stubs so no case inherits another's state.
 * @returns {void} Nothing; storage and the stubs are reset in place.
 */
function resetSessionAndStubs(): void {
  sessionStorage.clear();
  signOnMock.mockReset();
  answerChallengeMock.mockReset();
}

/**
 * Clears the retained session after a case, so a stored token cannot leak forward.
 * @returns {void} Nothing; storage is cleared in place.
 */
function clearStoredSession(): void {
  sessionStorage.clear();
}

/**
 * Types a credential pair into the two visible fields and submits the form.
 * @param {string} userId - Operator identifier to type.
 * @param {string} password - Password to type into the masked field.
 * @returns {Promise<void>} Resolves once the submission has been dispatched.
 */
async function signOnWith(userId: string, password: string): Promise<void> {
  await userEvent.type(screen.getByLabelText(/user id/iu), userId);
  await userEvent.type(screen.getByLabelText(/password/iu), password);
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));
}

/**
 * A blank identifier is refused with the baseline sentence and no request is sent.
 *
 * Assumptions: the assertion is on the catalog constant, not on a string typed here. That is what
 * makes it a fidelity test rather than a restatement: if the catalog entry ever drifts from the
 * baseline wording, this fails, whereas a literal copied into the test would agree with the drift.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesABlankUserIdentifier(): Promise<void> {
  render(renderSignOn());

  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText(MESSAGES.PLEASE_ENTER_USER_ID)).toBeInTheDocument();
  expect(signOnMock).not.toHaveBeenCalled();
}

/**
 * A blank password is refused with the baseline sentence and no request is sent.
 *
 * Assumptions: the identifier is supplied and the password left blank, which exercises the SECOND
 * check. The order matters and is the source program's: an operator who leaves both blank is told
 * about the identifier first.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesABlankPassword(): Promise<void> {
  render(renderSignOn());

  await userEvent.type(screen.getByLabelText(/user id/iu), 'USER0001');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText(MESSAGES.PLEASE_ENTER_PASSWORD)).toBeInTheDocument();
  expect(signOnMock).not.toHaveBeenCalled();
}

/**
 * A refused credential reports the baseline verify-failure sentence.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsARefusedCredential(): Promise<void> {
  signOnMock.mockRejectedValue(new Error('401'));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findByText(MESSAGES.UNABLE_TO_VERIFY_THE_USER)).toBeInTheDocument();
}

/**
 * An operator without the administrative group enters the ordinary menu.
 *
 * Assumptions: the destination is chosen from the token's group claim, so this case proves the
 * branch the source program made on CDEMO-USER-TYPE is now made on a signed claim instead.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function entersTheOrdinaryMenuForANonAdministrator(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated(userIdToken()));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();
}

/**
 * An operator holding the administrative group enters the administrative menu.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function entersTheAdministrativeMenuForAnAdministrator(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated(adminIdToken()));
  render(renderSignOn());

  await signOnWith('ADMIN001', 'secret');

  expect(await screen.findByText('ADMIN MENU')).toBeInTheDocument();
}

/**
 * The issued access token reaches storage, so later requests can carry it.
 *
 * Assumptions: this is the production caller of `setAccessToken` being exercised. Before it
 * existed, that setter had no caller anywhere and every request left the browser with no
 * Authorization header, so asserting the token reaches storage is asserting the defect is closed.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function installsTheIssuedAccessToken(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated(userIdToken()));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  await waitFor(
    /**
     * Re-reads storage until the token has been installed.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(sessionStorage.getItem('carddemo.access-token')).toBe('access-token-value');
    },
  );
}

/**
 * A replacement-password challenge is answered and the application is then entered.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function answersAChallengeAndThenEnters(): Promise<void> {
  signOnMock.mockResolvedValue({
    outcome: 'CHALLENGE',
    challengeName: 'NEW_PASSWORD_REQUIRED',
    session: 'session-handle',
    userId: 'USER0001',
  });
  answerChallengeMock.mockResolvedValue(authenticated(userIdToken()));
  render(renderSignOn());

  await userEvent.type(screen.getByLabelText(/user id/iu), 'USER0001');
  await userEvent.type(screen.getByLabelText(/^password$/iu), 'secret');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  const replacement = await screen.findByLabelText(/new password/iu);
  await userEvent.type(replacement, 'replacement');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();
  expect(answerChallengeMock).toHaveBeenCalledWith('USER0001', 'session-handle', 'replacement');
}

/**
 * The screen composes the shared shell: header band, message band and function-key bar.
 *
 * Assumptions: the shell is asserted as composed, not merely importable. The function-key bar and
 * the header band were authored and then referenced by nothing, so a screen test that did not look
 * for them would keep passing while the shared shell stayed unreachable from any route.
 *
 * Assumptions: the band is asserted by its stable test id, not by role="alert". The band renders
 * that role only when it holds a message -- its empty state deliberately omits it so a screen
 * reader is not handed an empty live region -- and the invariant this case checks is that the
 * band's row is RESERVED whether or not there is text, exactly as row 23 of the 3270 screen was.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function composesTheSharedShell(): void {
  render(renderSignOn());

  expect(screen.getByRole('navigation', { name: /function keys/iu })).toBeInTheDocument();
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.getByText('CC00')).toBeInTheDocument();
}

/**
 * Exactly the two function keys the source mapset paints are offered.
 *
 * Assumptions: the two legend entries are asserted against the source mapset's own literal,
 * `ENTER=Sign-on  F3=Exit`, so the screen offers exactly the keys the 3270 screen painted and not
 * the uniform set some other screens use.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function offersExactlyTheTwoSourceFunctionKeys(): void {
  render(renderSignOn());

  expect(screen.getByRole('button', { name: /ENTER=Sign-on/u })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /F3=Exit/u })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /F4=Clear/u })).not.toBeInTheDocument();
}

/**
 * The exit key reports the source farewell sentence.
 *
 * Assumptions: this asserts the migration of `WHEN DFHPF3`, which moves CCDA-MSG-THANK-YOU into the
 * message field. The constant is read from the catalog so the sentence cannot drift from the source.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsTheSourceFarewellOnExit(): Promise<void> {
  render(renderSignOn());

  await userEvent.click(screen.getByRole('button', { name: /F3=Exit/u }));

  expect(await screen.findByText(THANK_YOU_CCDA.trim())).toBeInTheDocument();
}

/**
 * Registers the ten sign-on screen cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function signOnScreenCases(): void {
  it('refuses a blank user identifier with the baseline message', refusesABlankUserIdentifier);
  it('refuses a blank password with the baseline message', refusesABlankPassword);
  it(
    'reports a refused credential with the baseline verify-failure message',
    reportsARefusedCredential,
  );
  it(
    'enters the ordinary menu for an operator without the administrative group',
    entersTheOrdinaryMenuForANonAdministrator,
  );
  it(
    'enters the administrative menu for an operator holding the administrative group',
    entersTheAdministrativeMenuForAnAdministrator,
  );
  it('installs the issued access token so later requests carry it', installsTheIssuedAccessToken);
  it('answers a challenge and then enters the application', answersAChallengeAndThenEnters);
  it(
    'composes the shared shell: header band, message band and function-key bar',
    composesTheSharedShell,
  );
  it(
    'offers exactly the two function keys the source mapset paints',
    offersExactlyTheTwoSourceFunctionKeys,
  );
  it('reports the source farewell when the exit key is pressed', reportsTheSourceFarewellOnExit);
}

beforeEach(resetSessionAndStubs);

afterEach(clearStoredSession);

describe('sign-on screen', signOnScreenCases);
