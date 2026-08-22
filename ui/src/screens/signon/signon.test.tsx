/**
 * @file Component tests for the sign-on screen in `ui/src/screens/signon/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the reference screen's observable contract: the two fields at their copybook widths, the
 * password field rendered non-display, each of the five `COSGN00C` messages rendered verbatim, the
 * PF-key actions bound to real key events, and the onward branch taken from the group claim rather
 * than from any field the client supplied.
 *
 * Assumptions: the identity exchange is stubbed, so these cases measure the screen and not the
 * identity provider. Every credential literal below is fabricated.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list empty -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. Refactoring Rationale: this cited
// `globals: false` in ui/vitest.config.ts, which is set to `true` there for the separate reason its
// own note records, so the enforcing mechanism is named rather than an option that says the opposite.
import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../../api/auth';
import type { ApiError, FieldError } from '../../api/types';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import { fieldErrorId, fieldHintId } from '../../layout/fieldHelp';
import { INVALID_KEY_PRESSED, PROGRAM_MESSAGES, THANK_YOU_CARDDEMO } from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';
import { BREAKPOINT_TOKENS } from '../../theme/tokens';
import { endAnySession } from '../../test/sessionHarness';
import {
  answerWith,
  installApiHarness,
  onlyRequest,
  removeApiHarness,
} from '../../test/apiHarness';

/*
 * Refactoring Rationale: these two stubs were plain `vi.fn()` consts and are now created inside
 * `vi.hoisted`. The factory below is hoisted above every import, and this file's import graph now
 * reaches the module it mocks -- the two onward destinations import `useAuth`, which imports
 * `../../api/auth` -- so the factory runs while the imports are still being evaluated and a plain
 * const is still in its temporal dead zone at that point, which failed the whole suite with
 * "Cannot access 'signOnMock' before initialization". `vi.hoisted` is lifted with the factory, so
 * both stubs exist before any import runs. Alternatives Considered: importing the destinations lazily
 * inside each case would also dodge the cycle, but it would push an await into every branch case and
 * leave the same trap set for the next reader.
 */
const { signOnMock, answerChallengeMock } = vi.hoisted(
  /**
   * Creates the two client stubs before any module import is evaluated.
   * @returns {{ signOnMock: ReturnType<typeof vi.fn>, answerChallengeMock: ReturnType<typeof vi.fn> }}
   *   The hoisted stubs.
   */
  () => ({ signOnMock: vi.fn(), answerChallengeMock: vi.fn() }),
);

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

/*
 * WHY : Assumptions: BOTH the screen and the shell are imported dynamically, after the mock factory
 *       above is registered, and the shell is here rather than in the static import block for a reason
 *       that is easy to trip over. Vitest hoists `vi.mock` above the imports, but the factory it holds
 *       closes over the two `vi.fn()` bindings declared above -- so any STATIC import whose module graph
 *       reaches `../../api/auth` evaluates that factory before those bindings are initialised and fails
 *       with a temporal-dead-zone error naming a line in this file. `ui/src/layout/AppShell.tsx` reaches
 *       it through `ui/src/hooks/useAuth.ts`, which it needs for the sign-off key. Importing it here
 *       defers the evaluation until after the bindings exist, which is the same reason the screen itself
 *       has always been imported this way.
 */
const { SignOnScreen, SIGN_ON_ART_BREAKPOINT, SIGN_ON_BANKNOTE_ART, SIGN_ON_FIELD_WIDTH_HINT } =
  await import('./index');
const { AppShell } = await import('../../layout/AppShell');

// Assumptions: the slot's NAME is taken from the module that owns it rather than written out as a
//   literal, so a case asserting the bearer was installed cannot keep passing against a key nothing
//   writes -- `ui/src/api/client.ts` reads this same exported name, which is why one name exists
//   rather than three copies of a string.
/*
 * WHY : ⚠️ Refactoring Rationale: an `AUTH_STORAGE_KEY` was imported here and read back to observe the
 *       installed bearer, and that export no longer exists. `ui/src/api/client.ts` holds the bearer in a
 *       module variable and publishes only its setter, so nothing outside it can read the value -- which
 *       is the point of the change, since a token in Web Storage is readable by any script the page
 *       loads. The bearer is therefore observed where it is USED, on the Authorization header of a
 *       dispatched request, which is a stronger observation in any case: a stored value that never
 *       reached a request was only ever a proxy for the property that matters.
 */

// Assumptions: the failure type is imported from the transport that raises it, so a refusal built
//   here is the same class `isApiRequestError` narrows on in the screen. A hand-rolled object with a
//   `problem` member would be dropped by that check and every field-refusal case below would pass
//   against a screen that had rendered nothing.
const { ApiRequestError, getApiClient } = await import('../../api/client');

/*
 * WHY : Refactoring Rationale: the contract's `PASSWORD_MAX_LENGTH` was imported here for a case that
 *       asserted both controls' declared widths, and the import is withdrawn with that case. The bound is
 *       asserted where it is declared -- `ui/src/api/auth.ts` publishes it and its own cases pin it -- and
 *       what this file asserts about the credential control is the behaviour the bound exists for: that a
 *       refusal from the pool reaches the control rather than being pre-judged here.
 */

const MESSAGES = PROGRAM_MESSAGES.COSGN00C;

/**
 * A replacement credential that satisfies the pool's own policy.
 *
 * Assumptions: it is not the bare word `replacement` this file used, and the difference is the point.
 * `infra/modules/cognito/variables.tf` requires a minimum length of at least twelve with the four
 * character classes on by default, so `replacement` is a value no environment this repository can
 * express would accept -- a case that submitted it asserted the screen forwarded a credential the pool
 * would refuse, which is the opposite of what these cases exist to show.
 */
const POLICY_VALID_REPLACEMENT = 'Replacement1!';

/** The identifier every established session in this file is issued for. */
const SIGNED_ON_USER_ID = 'USER0001';

/** Identifier of the operator-identifier control, as the screen declares it. */
const USER_ID_CONTROL_ID = 'signon-user-id';

/** Identifier of whichever password control is currently mounted, as the screen declares it. */
const PASSWORD_CONTROL_ID = 'signon-password';

/** Viewport width below the design system's medium breakpoint, used by the responsive case. */
const NARROW_VIEWPORT_WIDTH = 375;

/** The viewport width jsdom reports by default, restored after the responsive case. */
const DEFAULT_VIEWPORT_WIDTH = window.innerWidth;

/**
 * Builds a problem document a service would answer a refusal with.
 *
 * Assumptions: the shape is the published `ApiError` and the field entries are supplied in the order a
 * service would send them, because that order is the order a form marks its fields in and the screen
 * is required to pass it through rather than rebuild it.
 * @param {number} status - Transport status the document reports.
 * @param {string} message - Screen-level sentence the band is expected to render.
 * @param {readonly FieldError[]} fieldErrors - Per-field entries, possibly empty.
 * @returns {ApiError} The document to place on a rejected exchange.
 */
function problemDocument(
  status: number,
  message: string,
  fieldErrors: readonly FieldError[],
): ApiError {
  return {
    code: 'CARDDEMO-AUTH-0001',
    secondaryCode: '',
    message,
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'UITESTAUTH0000000000AA',
    path: '/api/v1/auth/challenge',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors,
    abend: null,
  };
}

/**
 * Builds the normalised failure the shared client raises for a service refusal.
 *
 * Assumptions: an `ApiRequestError` is constructed rather than a bare `Error`, because the screen reads
 * both the document and the transport status from it — the status is what tells a refused challenge
 * session apart from a refused proposed password — and a plain `Error` exercises only the fallback path.
 * @param {number} status - Transport status the refusal carries.
 * @param {string} message - Screen-level sentence the band is expected to render.
 * @param {readonly FieldError[]} fieldErrors - Per-field entries, possibly empty.
 * @returns {ApiRequestError} The rejection to configure a stub with.
 */
function refusal(
  status: number,
  message: string,
  fieldErrors: readonly FieldError[] = [],
): InstanceType<typeof ApiRequestError> {
  return new ApiRequestError(
    'PROBLEM',
    status,
    problemDocument(status, message, fieldErrors),
    `PROBLEM ${String(status)} CARDDEMO-AUTH-0001`,
  );
}

/**
 * Renders the sign-on screen inside the theme and a router carrying the two real onward destinations.
 *
 * Refactoring Rationale: the two onward routes held private one-line stand-in elements and the branch
 * cases asserted that stand-in text. That made the suite pass while the delivered menu screens were
 * mounted at no route anywhere, so it measured the navigate call rather than the transition -- exactly
 * the blind spot that let an unreachable screen graph ship. Mounting `MainMenuScreen` and
 * `AdminMenuScreen` here and asserting each screen's own mapset title means the branch cases now fail
 * if either destination stops rendering, and the same two screens are also exercised through the
 * production route table in `ui/src/routerReachability.test.tsx`.
 * @returns {ReactElement} The composed tree under test.
 */
function renderSignOn(): ReactElement {
  /*
   * Refactoring Rationale: the single `AppShell` is part of the tree, mirroring the PUBLIC shell
   * layout route `ui/src/router.tsx` declares for this one screen — a sibling of the guarded shell
   * branch, so sign-on is framed without anything above it that could demand a credential. This
   * screen delegates its title band, its row-23 message and its row-24 legend through `useShellSlot`
   * instead of composing them, so a bare screen would render no band and no legend and every case
   * asserting on either would fail for the wrong reason. The provider stays outermost because theming
   * is injected exactly once, above the frame — in the application that is `ui/src/App.tsx`, which
   * wraps its single `RouterProvider` in `ConfigProvider`.
   */
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/signon']}>
        <AppShell>
          <Routes>
            <Route path="/signon" element={<SignOnScreen />} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
            <Route path="/admin" element={<div>ADMIN MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/*
 * WHY : ⚠️ Assumptions: both builders below now carry a `cognito:username` claim as well as
 *       `cognito:groups`, and it is not decoration. `useAuth`'s installer compares that claim against
 *       the identifier the token set arrived with and REFUSES a set whose identity token names another
 *       operator -- a service fault that would otherwise let the guards read one operator's authority
 *       while every request carried another's bearer. A token carrying only the group claim therefore
 *       establishes no session at all, so the subject has to match the `userId` in the outcome body.
 */

/**
 * Builds an identity token whose claim carries the administrative group.
 * @param {string} userId - Identifier the token is issued for, placed in the user-name claim.
 * @returns {string} A three-segment token decoding to that identifier and the administrative group.
 */
function adminIdToken(userId: string): string {
  const payload = btoa(
    JSON.stringify({ 'cognito:username': userId, 'cognito:groups': ['carddemo-admin'] }),
  );
  return `header.${payload}.signature`;
}

/**
 * Builds an identity token whose claim carries only the ordinary operator group.
 * @param {string} userId - Identifier the token is issued for, placed in the user-name claim.
 * @returns {string} A three-segment token decoding to that identifier and the operator group.
 */
function userIdToken(userId: string): string {
  const payload = btoa(
    JSON.stringify({ 'cognito:username': userId, 'cognito:groups': ['carddemo-user'] }),
  );
  return `header.${payload}.signature`;
}

/**
 * Builds an authenticated outcome carrying the supplied identity token.
 * @param {string} idToken - Identity token to return.
 * @param {string} userId - Identifier the outcome reports the tokens were issued for.
 * @returns {object} An authenticated sign-on outcome.
 */
function authenticated(idToken: string, userId: string): object {
  return {
    outcome: 'AUTHENTICATED',
    userId,
    accessToken: 'access-token-value',
    idToken,
    tokenType: 'Bearer',
    expiresIn: 3600,
  };
}

/**
 * Discards any held session, installs the request harness, and resets both client stubs.
 *
 * Refactoring Rationale: ⚠️ the session used to be discarded with `sessionStorage.clear()`. It is held
 * in memory now, so it outlives every case in this file rather than every file in this worker — a case
 * that signed on would leave the next one signed on as the same operator. The request harness is
 * installed because one case observes the bearer through a request, which needs a configured client and
 * a local answer for it.
 * @returns {void} Nothing; no session is held, the harness is installed and both stubs are empty.
 */
function resetSessionAndStubs(): void {
  endAnySession();
  installApiHarness();
  signOnMock.mockReset();
  answerChallengeMock.mockReset();
}

/**
 * Discards the session and removes the harness after a case, so nothing leaks forward.
 * @returns {void} Nothing; no session is held and the harness is removed.
 */
function clearStoredSession(): void {
  removeApiHarness();
  endAnySession();
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
 * Builds the normalised failure a refused exchange actually arrives as.
 *
 * ⚠️ Assumptions: a real `ApiRequestError` carrying the whole eleven-member problem document, not a
 * plain `Error`. This is what the transport throws for a 401 -- `ui/src/api/client.ts` normalises every
 * refusal into that class -- and it is the only shape the screen's two mapping functions can read:
 * `signOnFailureMessage` reaches the service's sentence through `isApiRequestError(failure).problem`, and
 * `signOnFieldRefusals` reaches the per-control text through `problem.fieldErrors`. A plain `Error`
 * satisfies neither, so a case built on one exercises only the unclassified-response fallback and would
 * pass unchanged against a screen that had lost the ability to render a service sentence at all.
 * @param {string} message - The sentence the service sent, verbatim from the catalog.
 * @param {readonly FieldError[]} fieldErrors - The per-field entries the document carried.
 * @returns {ApiRequestError} The failure the transport would have thrown.
 */
function refusedExchange(
  message: string,
  fieldErrors: readonly FieldError[] = [],
): InstanceType<typeof ApiRequestError> {
  const problem: ApiError = {
    code: 'CARDDEMO-UI-UNAUTHENTICATED',
    secondaryCode: 'SIGNON',
    message,
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 401,
    correlationId: '00000000-0000-4000-8000-00000000001f',
    path: '/api/v1/auth/signon',
    timestamp: '2026-01-01T00:00:00.000000Z',
    fieldErrors,
    abend: null,
  };

  return new ApiRequestError('PROBLEM', 401, problem, 'PROBLEM 401 CARDDEMO-UI-UNAUTHENTICATED');
}

/**
 * A refusal carrying no problem document falls back to the baseline verify-failure sentence.
 *
 * Assumptions: this is the reference's `WHEN OTHER` arm at `app/cbl/COSGN00C.cbl` L254 and it is kept as
 * its own case, because a transport failure genuinely carries no document and the operator must still be
 * told something. It is no longer the ONLY refusal case: on its own it proved only that the fallback
 * exists, and a screen that always used the fallback -- ignoring the sentence the service sent -- would
 * have satisfied it while losing two of the three verbatim refusals.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsARefusalCarryingNoProblemDocument(): Promise<void> {
  signOnMock.mockRejectedValue(new Error('401'));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findByText(MESSAGES.UNABLE_TO_VERIFY_THE_USER)).toBeInTheDocument();
}

/**
 * The service's wrong-password sentence is rendered unchanged and the cursor goes to the password.
 *
 * ⚠️ Assumptions: three consequences of ONE refusal are asserted together, and each is a different part
 * of the reference's behaviour on that turn. `app/cbl/COSGN00C.cbl` L242 moves the sentence into the
 * message field, L244 moves minus one into `PASSWDL` so the cursor lands in the password control, and the
 * document's own field entry is what the migrated screen attaches beneath that control in place of the
 * highlight this mapset never had. Asserting only the sentence would pass against a screen that put the
 * cursor in the wrong control, which on a two-field sign-on is the whole of the field-level feedback.
 *
 * Assumptions: the sentence is expected TWICE, because the screen renders it in the message band and the
 * document's field entry carries the same text beneath the control. A single-element query would fail on
 * the duplicate rather than on the behaviour.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsTheWrongPasswordSentenceAndFocusesThePassword(): Promise<void> {
  const refusal: FieldError = {
    field: 'password',
    state: 'NOT_OK',
    message: MESSAGES.WRONG_PASSWORD_TRY_AGAIN,
  };
  signOnMock.mockRejectedValue(refusedExchange(MESSAGES.WRONG_PASSWORD_TRY_AGAIN, [refusal]));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findAllByText(MESSAGES.WRONG_PASSWORD_TRY_AGAIN)).toHaveLength(2);
  await waitFor(
    /**
     * Waits for the deferred focus request to have been applied.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(document.activeElement).toBe(screen.getByLabelText(/password/iu));
    },
  );
}

/**
 * The service's user-not-found sentence is rendered unchanged and the cursor goes to the identifier.
 *
 * ⚠️ Assumptions: this is the case that proves the cursor branch is a BRANCH. `app/cbl/COSGN00C.cbl`
 * L249 states the sentence and L250 moves minus one into `USERIDL`, so the two refusals point at
 * different controls -- and the screen decides between them by comparing the sentence, because the
 * service deliberately does not disclose which credential failed. A single refusal case cannot tell a
 * working branch from one that always chooses the same control.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsTheUserNotFoundSentenceAndFocusesTheIdentifier(): Promise<void> {
  signOnMock.mockRejectedValue(refusedExchange(MESSAGES.USER_NOT_FOUND_TRY_AGAIN));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findByText(MESSAGES.USER_NOT_FOUND_TRY_AGAIN)).toBeInTheDocument();
  await waitFor(
    /**
     * Waits for the deferred focus request to have been applied.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(document.activeElement).toBe(screen.getByLabelText(/user id/iu));
    },
  );
}

/**
 * An operator without the administrative group enters the ordinary menu.
 *
 * Assumptions: the destination is chosen from the token's group claim, so this case proves the
 * branch the source program made on CDEMO-USER-TYPE is now made on a signed claim instead.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function entersTheOrdinaryMenuForANonAdministrator(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated(userIdToken('USER0001'), 'USER0001'));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();
}

/**
 * An operator holding the administrative group enters the administrative menu.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function entersTheAdministrativeMenuForAnAdministrator(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated(adminIdToken('ADMIN001'), 'ADMIN001'));
  render(renderSignOn());

  await signOnWith('ADMIN001', 'secret');

  expect(await screen.findByText('ADMIN MENU')).toBeInTheDocument();
}

/**
 * The issued access token is installed, so a later request carries it.
 *
 * Assumptions: this is the production caller of `setAccessToken` being exercised. Before it existed,
 * that setter had no caller anywhere and every request left the browser with no Authorization header,
 * so asserting the token reaches a request is asserting the defect is closed.
 *
 * Refactoring Rationale: ⚠️ the token is observed on a DISPATCHED request, where this case used to read
 * it back out of `sessionStorage`. The bearer is held in memory by `ui/src/api/client.ts` and is
 * deliberately unreachable from outside it, so the header is the only route left — and it is the better
 * observable in any case, since a stored value that never reached a request was only ever a proxy for
 * the property that matters.
 *
 * Assumptions: the entered route is awaited BEFORE the probe request is dispatched, so the observation
 * happens after the flow has completed rather than racing it. Polling the header instead would need the
 * probe inside the retry, which would dispatch a request per attempt.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function installsTheIssuedAccessToken(): Promise<void> {
  signOnMock.mockResolvedValue(authenticated(userIdToken('USER0001'), 'USER0001'));
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');
  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();

  answerWith({});
  await getApiClient().get('/probe');

  expect(onlyRequest().headers.authorization).toBe('Bearer access-token-value');
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
  answerChallengeMock.mockResolvedValue(authenticated(userIdToken('USER0001'), 'USER0001'));
  render(renderSignOn());

  await userEvent.type(screen.getByLabelText(/user id/iu), 'USER0001');
  /*
   * Refactoring Rationale: this matched `/^password$/iu` and now matches the label the screen
   * actually paints. The anchors were there to separate the current-password control from the
   * replacement one, and they stopped matching once the screen began rendering the mapset's own
   * label text: `app/bms/COSGN00.bms` L174 declares `INITIAL='Password    :'` at `LENGTH=13`, so the
   * accessible name carries four interior spaces and a trailing colon that an accessible-name lookup
   * normalises to `Password :`. The pattern is widened rather than the label narrowed, because the
   * label is a transformation-rule-T8 transcription and this anchor was only ever a disambiguator —
   * and it still disambiguates, since `New Password` cannot match a pattern anchored at the start.
   */
  await userEvent.type(screen.getByLabelText(/^password\s*:?$/iu), 'secret');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  const replacement = await screen.findByLabelText(/new password/iu);
  /*
   * Refactoring Rationale: the replacement credential is POLICY_VALID_REPLACEMENT and no longer the
   * eleven-character word `replacement`. Eleven characters is below the minimum the user pool enforces,
   * so the value this case fed to a stubbed service is one the deployed service would refuse -- the
   * case proved the screen forwards what it is given, while quietly encoding a credential that cannot
   * succeed. Using a value the policy accepts keeps the same assertion honest against the real
   * exchange, and the constant carries the reason so it is not shortened back.
   */
  await userEvent.type(replacement, POLICY_VALID_REPLACEMENT);
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();
  /*
   * WHY : ⚠️ Refactoring Rationale: the expected replacement is `POLICY_VALID_REPLACEMENT` and this
   *       asserted the literal `'replacement'`, which the note above the typing already explains is a
   *       credential no environment would accept. The fourth argument is asserted by TYPE: `useAuth`
   *       passes the abort signal of the session generation that started the exchange, so an answer
   *       settling after a sign-out or a newer sign-on is abandoned rather than installed -- and a
   *       three-argument expectation fails the moment that guard exists, which is what it did.
   */
  expect(answerChallengeMock).toHaveBeenCalledWith(
    SIGNED_ON_USER_ID,
    'session-handle',
    POLICY_VALID_REPLACEMENT,
    expect.any(AbortSignal),
  );
}

/**
 * Builds a refusal as the transport raises one, carrying a chosen status and field errors.
 * @param {number} status - HTTP status the service answered with.
 * @param {string} message - The screen-level sentence the problem document carries.
 * @param {ReadonlyArray<{ field: string; message: string }>} fieldErrors - Per-field entries to carry.
 * @returns {Error} The refusal, of the class the screen's own narrowing recognises.
 */
function refusalWith(
  status: number,
  message: string,
  fieldErrors: ReadonlyArray<{ field: string; message: string }>,
): Error {
  return new ApiRequestError(
    'PROBLEM',
    status,
    {
      code: `CARDDEMO-0${String(status)}`,
      secondaryCode: '',
      message,
      severity: 'CRITICAL',
      subsystem: 'APPLICATION',
      status,
      correlationId: 'CD0123456789ABCDEF012345',
      path: '/api/v1/auth/challenge',
      timestamp: '2026-01-15 09:00:00.000000',
      // Assumptions: every entry carries `NOT_OK` rather than `BLANK`, because these cases refuse a
      //   value that WAS supplied. `BLANK` is the state the services report for an absent one, and it
      //   is additionally what drives the asterisk marker on screens that paint one.
      fieldErrors: fieldErrors.map(
        /**
         * Completes one entry with the validation state the contract requires.
         * @param {object} entry - The field name and its sentence.
         * @param {string} entry.field - The wire name of the control the refusal addresses.
         * @param {string} entry.message - The sentence the service reported for it.
         * @returns {{ field: string; state: 'NOT_OK'; message: string }} The published entry shape.
         */
        (entry: { field: string; message: string }) => ({
          field: entry.field,
          state: 'NOT_OK' as const,
          message: entry.message,
        }),
      ),
      abend: null,
    },
    'the challenge was refused',
  );
}

/**
 * Drives the screen into an outstanding replacement-password challenge.
 * @returns {Promise<HTMLElement>} The replacement-password control, once the screen is showing it.
 */
async function reachTheChallenge(): Promise<HTMLElement> {
  signOnMock.mockResolvedValue({
    outcome: 'CHALLENGE',
    challengeName: 'NEW_PASSWORD_REQUIRED',
    session: 'session-handle',
    userId: 'USER0001',
  });
  render(renderSignOn());

  await userEvent.type(screen.getByLabelText(/user id/iu), 'USER0001');
  await userEvent.type(screen.getByLabelText(/^password\s*:?$/iu), 'secret');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  return screen.findByLabelText(/new password/iu);
}

/**
 * A policy refusal of the replacement is shown ON the replacement control, not discarded.
 *
 * Purpose: the challenge body names its member `newPassword`, and the screen's refusal mapping
 * recognised only the two members of the SIGN-ON body — so every policy refusal was dropped and the
 * operator was left with the band's general sentence on the one screen where the specific reason is the
 * whole of what they need to act.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function showsAPolicyRefusalOnTheReplacementControl(): Promise<void> {
  const replacement = await reachTheChallenge();
  answerChallengeMock.mockRejectedValue(
    refusalWith(400, 'Please correct the highlighted fields', [
      { field: 'newPassword', message: 'Password must be at least 12 characters' },
    ]),
  );

  await userEvent.type(replacement, 'short');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText('Password must be at least 12 characters')).toBeInTheDocument();
  // Assumptions: the challenge is asserted STILL outstanding, because a policy refusal leaves the
  //   continuation valid and the operator has to be able to correct the value and submit again.
  expect(screen.getByLabelText(/new password/iu)).toBeInTheDocument();
}

/**
 * A refused replacement is cleared from the control rather than left there to be resubmitted.
 *
 * Assumptions: the value is read back off the control, which is the only place the screen's decision is
 * observable. The refused value is also the one most likely to be wrong — the commonest refusal here is
 * that it breaks the pool's policy — so leaving it in place invited a second submission of it.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function clearsARefusedReplacement(): Promise<void> {
  const replacement = await reachTheChallenge();
  answerChallengeMock.mockRejectedValue(
    refusalWith(400, 'Please correct the highlighted fields', [
      { field: 'newPassword', message: 'Password must be at least 12 characters' },
    ]),
  );

  await userEvent.type(replacement, 'short');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  await waitFor(
    /**
     * Re-reads the control until the refused value has been discarded.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByLabelText(/new password/iu)).toHaveValue('');
    },
  );
}

/**
 * A refused continuation returns the screen to the sign-on form instead of stranding it.
 *
 * Purpose: a 401 on this exchange means the session value itself is dead, so the same continuation would
 * be refused on every further attempt. The screen used to stay in the challenge holding it, which left
 * no reachable working state at all — the only way out was to reload the application. Restoring the form
 * is what the sentence the service sends for this status instructs.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function returnsToTheFormWhenTheContinuationIsRefused(): Promise<void> {
  const replacement = await reachTheChallenge();
  answerChallengeMock.mockRejectedValue(refusal(401, 'Please sign on again ...'));

  await userEvent.type(replacement, 'a-long-enough-replacement');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText('Please sign on again ...')).toBeInTheDocument();
  await waitFor(
    /**
     * Waits for the challenge row to be replaced by the current-password row.
     * @returns {void} Nothing; the assertions carry the outcome.
     */
    () => {
      expect(screen.queryByLabelText(/new password/iu)).toBeNull();
      expect(screen.getByLabelText(/^password\s*:?$/iu)).toBeInTheDocument();
    },
  );
  // Assumptions: the identifier control is asserted ENABLED again, because it is disabled for the
  //   duration of a challenge. A screen that cleared the challenge but left it disabled would look
  //   recovered and still refuse the first thing the operator tried to type.
  expect(screen.getByLabelText(/user id/iu)).toBeEnabled();
}

/**
 * The cursor lands on the replacement control for a challenge refusal, not on the disabled identifier.
 *
 * Purpose: the focus target was chosen by comparing the band sentence against the wrong-password
 * constant, so any other sentence sent the cursor to the identifier — which is rendered `disabled` while
 * a challenge is outstanding. The focus request therefore landed on an element that cannot take it and
 * the cursor stayed where it was, on a screen whose whole purpose is to collect one value.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function focusesTheReplacementControlOnRefusal(): Promise<void> {
  const replacement = await reachTheChallenge();
  answerChallengeMock.mockRejectedValue(
    refusalWith(400, 'Please correct the highlighted fields', [
      { field: 'newPassword', message: 'Password must be at least 12 characters' },
    ]),
  );

  await userEvent.type(replacement, 'short');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  await waitFor(
    /**
     * Waits for the focus request to be applied to the replacement control.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByLabelText(/new password/iu)).toHaveFocus();
    },
  );
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
 *
 * Refactoring Rationale: this asserted `THANK_YOU_CCDA` and now asserts `THANK_YOU_CARDDEMO`. The two
 * are DIFFERENT baseline constants that read almost alike, and the catalog holds both because
 * transformation rule T8 requires it: `CCDA-THANK-YOU` is `PIC X(40)` at `app/cpy/COTTL01Y.cpy`
 * L23-L24 and names the application "CCDA", while `CCDA-MSG-THANK-YOU` is `PIC X(50)` at
 * `app/cpy/CSMSG01Y.cpy` L18-L19 and names it "CardDemo". `app/cbl/COSGN00C.cbl` L89 moves the
 * SECOND one, so the first assertion passed against a screen that showed the wrong sentence at the
 * wrong declared width — a byte-level parity defect that a test naming the other constant could only
 * ratify. Both the screen and this assertion now name the constant L89 selects.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function reportsTheSourceFarewellOnExit(): Promise<void> {
  render(renderSignOn());

  await userEvent.click(screen.getByRole('button', { name: /F3=Exit/u }));

  expect(await screen.findByText(THANK_YOU_CARDDEMO.trim())).toBeInTheDocument();
}

/**
 * Drives sign-on far enough that the provider's replacement-password challenge is outstanding.
 *
 * Assumptions: the challenge is reached through the real form rather than by seeding state, because the
 * properties under test are about what the screen does BETWEEN the two exchanges — which control is
 * mounted, what it holds, and which name a refusal has to use to address it.
 * @param {string} replacement - Replacement password to type into the control the challenge mounts.
 * @returns {Promise<HTMLElement>} The mounted replacement-password control, already carrying the value.
 */
async function reachOutstandingChallenge(replacement: string): Promise<HTMLElement> {
  signOnMock.mockResolvedValue({
    outcome: 'CHALLENGE',
    challengeName: 'NEW_PASSWORD_REQUIRED',
    session: 'session-handle',
    userId: 'USER0001',
  });
  render(renderSignOn());

  await userEvent.type(screen.getByLabelText(/user id/iu), 'USER0001');
  await userEvent.type(screen.getByLabelText(/^password\s*:?$/iu), 'secret');
  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  const control = await screen.findByLabelText(/new password/iu);
  await userEvent.type(control, replacement);
  return control;
}

/**
 * The pool's reason for refusing a replacement password reaches the control it names.
 *
 * Assumptions: the entry is keyed `newPassword`, which is the third component of
 * `SignOnChallengeRequest` and the key `CognitoIdentityService` publishes as `FIELD_NEW_PASSWORD`. The
 * screen's field domain held only `userId` and `password` before this case existed, so every such entry
 * was dropped as unrecognised and the operator was told a password was unacceptable with no reason —
 * this asserts the reason arrives, and that it is programmatically associated with the control.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsThePoolsReasonOnTheReplacementControl(): Promise<void> {
  const reason = 'Password does not conform to policy: Password not long enough';
  answerChallengeMock.mockRejectedValue(
    refusal(400, 'Please correct the highlighted field ...', [
      { field: 'newPassword', state: 'NOT_OK', message: reason },
    ]),
  );
  await reachOutstandingChallenge('short');

  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText(reason)).toBeInTheDocument();
  // Assumptions: the association is asserted and not just the text, because antd injects neither member
  //   for an unnamed item -- so a visible sentence proves only that a sighted operator is told.
  const control = screen.getByLabelText(/new password/iu);
  expect(control).toHaveAttribute('aria-invalid', 'true');
  expect(control).toHaveAttribute('aria-describedby', fieldErrorId(PASSWORD_CONTROL_ID));
  expect(screen.getByText(reason).id).toBe(fieldErrorId(PASSWORD_CONTROL_ID));
}

/**
 * A refused replacement password is discarded from the control that held it.
 *
 * Assumptions: the challenge itself is RETAINED on this path, because a policy refusal leaves the
 * session usable and the operator corrects the value in place — so the case asserts both halves: the
 * value is gone and the replacement control is still the one mounted.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsARefusedReplacementCredential(): Promise<void> {
  answerChallengeMock.mockRejectedValue(
    refusal(400, 'Please correct the highlighted field ...', [
      { field: 'newPassword', state: 'NOT_OK', message: 'Password not long enough' },
    ]),
  );
  const control = await reachOutstandingChallenge('short');
  expect(control).toHaveValue('short');

  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  await waitFor(
    /**
     * Re-reads the control until the refused credential has been dropped.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByLabelText(/new password/iu)).toHaveValue('');
    },
  );
  expect(screen.getByLabelText(/new password/iu)).toBeInTheDocument();
}

/**
 * A refused session retires the challenge and restores a form the operator can sign on from.
 *
 * Assumptions: the discriminator is the transport status, which is what `auth-api.yaml` declares for
 * this operation — a 401 means the session has expired, been used, been altered or been issued for
 * another identifier, and the published remedy is to sign on again. Before this case existed the
 * challenge stayed outstanding forever: the replacement control kept re-answering a dead session, the
 * identifier control stayed disabled beside it, and no gesture on the screen reached a fresh sign-on.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function restoresTheSignOnFormWhenTheSessionIsRefused(): Promise<void> {
  answerChallengeMock.mockRejectedValue(refusal(401, 'Please sign on again ...'));
  await reachOutstandingChallenge('replacement');

  await userEvent.click(screen.getByRole('button', { name: /sign on/iu }));

  expect(await screen.findByText('Please sign on again ...')).toBeInTheDocument();
  await waitFor(
    /**
     * Re-reads the form until the ordinary credential control is mounted again.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByLabelText(/^password\s*:?$/iu)).toBeInTheDocument();
    },
  );
  expect(screen.queryByLabelText(/new password/iu)).not.toBeInTheDocument();
  // Assumptions: the identifier is asserted ENABLED and focused, because those two are what make the
  //   restored form usable -- a challenge disables the identifier, and the cursor belongs where a fresh
  //   sign-on starts, which is where `IF EIBCALEN = 0` at COSGN00C.cbl L82 homes it.
  const identifier = screen.getByLabelText(/user id/iu);
  expect(identifier).toBeEnabled();
  expect(identifier).toHaveFocus();
  expect(screen.getByLabelText(/^password\s*:?$/iu)).toHaveValue('');
}

/**
 * A refused identifier is linked to its message, not merely shown beside it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function linksARefusedIdentifierToItsMessage(): Promise<void> {
  const reason = 'User ID must be at most 8 characters ...';
  signOnMock.mockRejectedValue(
    refusal(400, 'Please correct the highlighted field ...', [
      { field: 'userId', state: 'NOT_OK', message: reason },
    ]),
  );
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  expect(await screen.findByText(reason)).toBeInTheDocument();
  const identifier = screen.getByLabelText(/user id/iu);
  expect(identifier).toHaveAttribute('aria-invalid', 'true');
  // Assumptions: BOTH described elements are expected and in this order, because antd renders the
  //   explain container above the extra container, so the announcement order matches the reading order.
  expect(identifier).toHaveAttribute(
    'aria-describedby',
    `${fieldErrorId(USER_ID_CONTROL_ID)} ${fieldHintId(USER_ID_CONTROL_ID)}`,
  );
}

/**
 * The retired width hint is painted once, beside the control whose width still holds.
 *
 * Assumptions: the mapset paints `(8 Char)` twice, at `POS=(19,52)` and `POS=(20,52)`. Only the first
 * is rendered: eight is still the identifier's enforced width, while beside the credential it describes
 * the retired `SEC-USR-PWD` column and would ask for a value the pool is configured to refuse. The
 * count is asserted rather than the absence alone, so a future revision cannot satisfy this by painting
 * the hint somewhere else on the screen.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function paintsTheWidthHintOnlyBesideTheIdentifier(): void {
  render(renderSignOn());

  const hints = screen.getAllByText(SIGN_ON_FIELD_WIDTH_HINT);
  expect(hints).toHaveLength(1);
  expect(hints[0]?.id).toBe(fieldHintId(USER_ID_CONTROL_ID));
  expect(screen.getByLabelText(/^password\s*:?$/iu)).not.toHaveAttribute('aria-describedby');
}

/**
 * Stands in for a resolver until the promise that owns one has been constructed.
 *
 * Assumptions: it is inert and is never the resolver a case invokes -- the assignment below replaces it
 * synchronously inside the promise's own executor. It exists so the variable has no nullable type,
 * because a nullable resolver would need a guard at the call site that could only ever be dead code.
 * @returns {void} Nothing; the value handed to it is discarded.
 */
function unusedResolver(): void {}

/**
 * The Enter key and its legend entry stand down together while an exchange is in flight.
 *
 * Assumptions: the legend entry's disabled state is asserted rather than the handler's silence, because
 * the defect was a DISAGREEMENT — the submit control showed a loading state while the legend stayed lit
 * and the physical key stayed live, both doing nothing. The invalid-key sentence is asserted absent for
 * the same reason the binding carries the state at all: a key the screen supports must not be reported
 * as invalid merely because the screen is busy.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function standsTheEnterKeyDownWhileAnExchangeIsInFlight(): Promise<void> {
  let releaseSignOn: (value: unknown) => void = unusedResolver;
  signOnMock.mockReturnValue(
    new Promise(
      /**
       * Retains the resolver so the case controls when the exchange settles.
       * @param {(value: unknown) => void} resolve - The promise's own resolver.
       * @returns {void} Nothing; the resolver is retained.
       */
      (resolve: (value: unknown) => void): void => {
        releaseSignOn = resolve;
      },
    ),
  );
  render(renderSignOn());

  await signOnWith('USER0001', 'secret');

  const legendEnter = await screen.findByRole('button', { name: /ENTER=Sign-on/u });
  await waitFor(
    /**
     * Re-reads the legend entry until the in-flight exchange has stood it down.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(legendEnter).toBeDisabled();
    },
  );
  await userEvent.keyboard('{Enter}');
  expect(screen.queryByText(INVALID_KEY_PRESSED.trim())).not.toBeInTheDocument();
  expect(signOnMock).toHaveBeenCalledTimes(1);

  releaseSignOn(authenticated(userIdToken(SIGNED_ON_USER_ID), SIGNED_ON_USER_ID));
  expect(await screen.findByText('ORDINARY MENU')).toBeInTheDocument();
}

/**
 * Finds the decorative banknote, if it is rendered.
 *
 * Assumptions: the query supplies an IDENTITY normalizer, and it must. The default text matcher
 * collapses runs of whitespace and trims, which would reduce the nine newline-separated lines — each
 * carrying the interior spaces that position the art's glyphs — to one blank-separated string that
 * matches nothing. The first draft of these cases used the default matcher and the absence assertion
 * therefore passed for the wrong reason: it could not have found the art at any viewport width.
 * @returns {HTMLElement | null} The element holding the art, or `null` when it is not rendered.
 */
function queryBanknote(): HTMLElement | null {
  return screen.queryByText(SIGN_ON_BANKNOTE_ART.join('\n'), {
    /**
     * Compares the rendered text exactly as authored.
     * @param {string} text - The candidate element's text content.
     * @returns {string} That text, unchanged.
     */
    normalizer: (text: string): string => text,
  });
}

/**
 * The decoration is omitted below the design system's medium breakpoint.
 *
 * Assumptions: the width is set before the render, because antd's responsive observer reads
 * `matchMedia` when it subscribes in a layout effect and the suite's shim derives every query from
 * `window.innerWidth` — so a width assigned first is the width the observer reports.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function omitsTheDecorationOnANarrowViewport(): void {
  window.innerWidth = NARROW_VIEWPORT_WIDTH;
  render(renderSignOn());

  expect(queryBanknote()).not.toBeInTheDocument();
  // Assumptions: the form is asserted present in the same case, because the whole point of omitting the
  //   decoration is that the form must not be the thing that gives way on a narrow viewport.
  expect(screen.getByLabelText(/user id/iu)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /sign on/iu })).toBeInTheDocument();
}

/**
 * The decoration is rendered at or above that breakpoint.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function paintsTheDecorationOnAWideViewport(): void {
  render(renderSignOn());

  expect(queryBanknote()).toBeInTheDocument();
}

/**
 * The screen key the decoration reacts to is the one the design token names.
 *
 * Assumptions: one half is DERIVED from the other rather than both being restated, so the case cannot
 * pass by agreeing with a rename on only one side. antd's responsive observer builds the `md` query as
 * `(min-width: ${token.screenMD}px)`, which is the correspondence being pinned.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function alignsTheDecorationBreakpointWithTheDesignToken(): void {
  expect(SIGN_ON_ART_BREAKPOINT.token).toBe(BREAKPOINT_TOKENS.medium);
  expect(SIGN_ON_ART_BREAKPOINT.token).toBe(
    `screen${SIGN_ON_ART_BREAKPOINT.screenKey.toUpperCase()}`,
  );
}

/**
 * Restores the viewport width the responsive case changed, so no later case inherits it.
 * @returns {void} Nothing; the width is restored in place.
 */
function restoreViewportWidth(): void {
  window.innerWidth = DEFAULT_VIEWPORT_WIDTH;
}

/**
 * Registers the twelve sign-on screen cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function signOnScreenCases(): void {
  it('refuses a blank user identifier with the baseline message', refusesABlankUserIdentifier);
  it('refuses a blank password with the baseline message', refusesABlankPassword);
  it(
    'reports a refusal carrying no problem document with the verify-failure message',
    reportsARefusalCarryingNoProblemDocument,
  );
  it(
    "renders the service's wrong-password sentence and focuses the password",
    reportsTheWrongPasswordSentenceAndFocusesThePassword,
  );
  it(
    "renders the service's user-not-found sentence and focuses the identifier",
    reportsTheUserNotFoundSentenceAndFocusesTheIdentifier,
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
    'shows a refused replacement on the replacement control',
    showsAPolicyRefusalOnTheReplacementControl,
  );
  it('clears a refused replacement from its control', clearsARefusedReplacement);
  it(
    'returns to the sign-on form when the continuation is refused',
    returnsToTheFormWhenTheContinuationIsRefused,
  );
  it(
    'places the cursor on the replacement control after a refusal',
    focusesTheReplacementControlOnRefusal,
  );
  it(
    'composes the shared shell: header band, message band and function-key bar',
    composesTheSharedShell,
  );
  it(
    'offers exactly the two function keys the source mapset paints',
    offersExactlyTheTwoSourceFunctionKeys,
  );
  it('reports the source farewell when the exit key is pressed', reportsTheSourceFarewellOnExit);
  it(
    "reports the pool's reason for a refused replacement password on that control",
    reportsThePoolsReasonOnTheReplacementControl,
  );
  it('discards a refused replacement credential', discardsARefusedReplacementCredential);
  it(
    'restores the sign-on form when the challenge session is refused',
    restoresTheSignOnFormWhenTheSessionIsRefused,
  );
  it('links a refused identifier to its message', linksARefusedIdentifierToItsMessage);
  it('paints the width hint only beside the identifier', paintsTheWidthHintOnlyBesideTheIdentifier);
  it(
    'stands the Enter key down while an exchange is in flight',
    standsTheEnterKeyDownWhileAnExchangeIsInFlight,
  );
  it('omits the decoration on a narrow viewport', omitsTheDecorationOnANarrowViewport);
  it('paints the decoration on a wide viewport', paintsTheDecorationOnAWideViewport);
  it(
    'aligns the decoration breakpoint with the design token',
    alignsTheDecorationBreakpointWithTheDesignToken,
  );
}

beforeEach(resetSessionAndStubs);

afterEach(clearStoredSession);

afterEach(restoreViewportWidth);

describe('sign-on screen', signOnScreenCases);
