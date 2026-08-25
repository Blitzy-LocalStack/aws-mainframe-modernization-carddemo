/**
 * @file Component tests for the authentication and authorisation guards in
 * `ui/src/routes/guards.tsx`.
 *
 * Purpose
 * -------
 * Assert what each guard RENDERS in each of its states: a caller with no token is redirected to
 * sign-on, a caller holding one is admitted, and a caller outside the administrative group receives
 * the refusal message verbatim from the catalog.
 *
 * Assumptions: the guards decide rendering only, never permission. Every service independently
 * validates the token, so a case here proves the screen an operator sees and proves nothing about
 * data access -- which is why no case below asserts that a request was refused.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { ReactElement, ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { SIGN_ON_BOUNCE_REASON, signOnBounceState } from './navigation';

/**
 * The guarded address the bounce cases attempt.
 *
 * Assumptions: a PARAMETERISED path, because that is the class the bounce has to be able to carry --
 * `inApplicationRoute`'s parameterless set would refuse it -- and it is the exact address QA measured
 * the lost destination on.
 */
const ATTEMPTED_PATH = '/users/USER0100/delete';

import { ACCESS_DENIED_ADMIN_ONLY } from '../messages/messages';

// Assumptions: the catalog constant is asserted TRIMMED, and the trim belongs to the assertion rather
// than to the screen. The baseline field is fixed-width, so the constant carries the trailing blanks
// that padding produced, and the guard renders it verbatim to preserve that contract; the DOM then
// collapses trailing whitespace for display and Testing Library matches on the normalized text. The
// constant is still the source of the expectation, so a change to the baseline wording fails here.
const ACCESS_DENIED_TEXT = ACCESS_DENIED_ADMIN_ONLY.trim();

/**
 * Transaction identifier the refusal's title band must carry.
 *
 * Assumptions: retyped from `app/cbl/COMEN01C.cbl` L37 (`WS-TRANID VALUE 'CM00'`) rather than imported
 * from the guard, so the expectation is independent of the value under test. Importing the constant
 * would make the case agree with whatever the guard delegates, including the administrative program it
 * must never name.
 */
const REFUSAL_TRANSACTION_ID = 'CM00';

/**
 * Program name the refusal's title band must carry, from `app/cbl/COMEN01C.cbl` L36.
 *
 * Assumptions: retyped for the same reason {@link REFUSAL_TRANSACTION_ID} is.
 */
const REFUSAL_PROGRAM_NAME = 'COMEN01C';
import { ACCESS_DENIED_HEADING, MAIN_MENU_HEADINGS } from '../messages/messages';
import { APP_SHELL_TEST_ID, AppShell } from '../layout/AppShell';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { RequireAdmin, RequireSignOn } from './guards';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { SESSION_USER_ID, endAnySession, establishSession } from '../test/sessionHarness';

/*
 * WHY : ⚠️ Refactoring Rationale: each case below used to arrange its caller by WRITING an identity
 *       token into `sessionStorage` under `carddemo.id-token`. That key no longer exists -- a review
 *       found every credential this application held sitting in script-readable Web Storage, and the
 *       session moved into memory behind one installer that validates the whole token set -- so a
 *       session can now be arranged only by performing an exchange, which `establishSession` does.
 * WHY : Trade-offs: the arrangement costs a rendered hook and an answered request per case, where it
 *       used to cost one `setItem`. What it buys is that the caller each case guards is a caller the
 *       application could actually produce: a seeded token set was never validated, so a case could
 *       assert on authority carried by a set the real sign-on path refuses outright.
 */

/**
 * Renders a guarded subtree at `/protected`, with sign-on reachable at its own route.
 * @param {ReactNode} guard - The guard element wrapping the protected screen.
 * @returns {ReactElement} The composed tree under test.
 */
function renderGuarded(guard: ReactNode): ReactElement {
  return (
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route path="/protected" element={guard} />
        <Route path="/signon" element={<div>SIGN ON SCREEN</div>} />
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Renders a guarded subtree at `/protected` INSIDE the application frame.
 *
 * Assumptions: this harness exists alongside {@link renderGuarded} rather than replacing it, because
 * the two answer different questions. The bare harness proves the guard's DECISION -- which of the three
 * outcomes it returns -- and would be satisfied by a refusal that painted nothing else. This one proves
 * what the operator KEEPS while being refused, which is only observable with the frame mounted, because
 * the message row and the key legend are zones the refusal delegates rather than renders.
 * @param {ReactNode} guard - The guard element wrapping the protected screen.
 * @returns {ReactElement} The composed tree under test, inside the frame.
 */
function renderGuardedInFrame(guard: ReactNode): ReactElement {
  return (
    <MemoryRouter initialEntries={['/protected']}>
      <AppShell>
        <Routes>
          <Route path="/protected" element={guard} />
          <Route path="/signon" element={<div>SIGN ON SCREEN</div>} />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/**
 * Discards any held session and installs the request harness the arrangement answers itself from.
 *
 * Assumptions: the harness is needed even though no case here dispatches a request of its own,
 * because arranging a signed-on caller performs a real sign-on exchange and that exchange has to be
 * answered locally.
 * @returns {void} Nothing; no session is held and the harness is installed.
 */
function clearSessionAndInstallHarness(): void {
  endAnySession();
  installApiHarness();
}

/**
 * Discards the session and removes the harness, so no later file inherits either.
 * @returns {void} Nothing; no session is held and the harness is removed.
 */
function clearSessionAndRemoveHarness(): void {
  removeApiHarness();
  endAnySession();
}

/**
 * An unauthenticated caller is sent to sign-on and never sees the protected screen.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function redirectsAnUnauthenticatedCallerToSignOn(): void {
  render(renderGuarded(<RequireSignOn>{<div>PROTECTED</div>}</RequireSignOn>));

  expect(screen.getByText('SIGN ON SCREEN')).toBeInTheDocument();
  expect(screen.queryByText('PROTECTED')).not.toBeInTheDocument();
}

/**
 * A caller holding an established session reaches the protected screen.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function admitsACallerHoldingAToken(): Promise<void> {
  await establishSession({ groups: ['carddemo-user'] });

  render(renderGuarded(<RequireSignOn>{<div>PROTECTED</div>}</RequireSignOn>));

  expect(screen.getByText('PROTECTED')).toBeInTheDocument();
}

/**
 * An unauthenticated caller is sent to sign-on from an administrative route too.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function redirectsAnUnauthenticatedCallerAwayFromAnAdministrativeRoute(): void {
  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.getByText('SIGN ON SCREEN')).toBeInTheDocument();
}

/**
 * An authenticated non-administrator is refused rather than redirected.
 *
 * Assumptions: an authenticated non-administrator is REFUSED rather than redirected, and the
 * distinction is the point. They are signed on correctly, so sending them to sign-on would invite
 * them to fix something that is not broken.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function refusesAnAuthenticatedCallerWithoutTheAdministrativeGroup(): Promise<void> {
  await establishSession({ groups: ['carddemo-user'] });

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  expect(screen.queryByText('SIGN ON SCREEN')).not.toBeInTheDocument();
  expect(screen.getByText(ACCESS_DENIED_TEXT)).toBeInTheDocument();
}

/**
 * Renders a guarded subtree at a caller-chosen address, with sign-on reporting the state it received.
 *
 * Assumptions: the sign-on stand-in reads its own location state and paints it, rather than the case
 * inspecting the router's internals. What the delivered screen will do is exactly that -- read
 * `useLocation().state` -- so asserting on rendered output measures the contract the screen consumes
 * instead of a private detail of how the redirect was performed.
 * @param {ReactNode} guard - The guard element wrapping the protected screen.
 * @param {string} entry - Address the caller attempts.
 * @returns {ReactElement} The composed tree under test.
 */
function renderGuardedAt(guard: ReactNode, entry: string): ReactElement {
  return (
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/users/:id/delete" element={guard} />
        <Route path="/protected" element={guard} />
        <Route path="/signon" element={<BounceStateProbe />} />
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Paints the bounce state sign-on was entered with, so a case can assert on it.
 * @returns {ReactElement} One node per member the reader admitted.
 */
function BounceStateProbe(): ReactElement {
  const { reason, attempted } = signOnBounceState(useLocation().state);

  return (
    <div>
      <div>SIGN ON SCREEN</div>
      <div>{`reason:${reason ?? 'none'}`}</div>
      <div>{`attempted:${attempted ?? 'none'}`}</div>
    </div>
  );
}

/**
 * A bounce from a guarded route hands sign-on the reason and the attempted destination.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and it covers the half of the reload bounce that was
 * measurably absent. Because the session is held in memory only, a reload of any of the nineteen guarded
 * routes ends it and this guard redirects -- and the redirect carried nothing, so the operator arrived
 * with an EMPTY message band and no way to tell being thrown out of `/users/USER0100/delete` from a first
 * visit, with the destination gone from every carrier including the browser's back control.
 *
 * Assumptions: the attempted route asserted is a PARAMETERISED one, deliberately. The parameterless set
 * `inApplicationRoute` guards would have refused it, which is why the bounce validates path SHAPE
 * instead -- and the deletion screen is the exact address QA measured the loss on.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function bounceCarriesTheReasonAndTheAttemptedDestination(): void {
  render(renderGuardedAt(<RequireSignOn>{<div>PROTECTED</div>}</RequireSignOn>, ATTEMPTED_PATH));

  expect(screen.getByText('SIGN ON SCREEN')).toBeInTheDocument();
  expect(screen.getByText(`reason:${SIGN_ON_BOUNCE_REASON}`)).toBeInTheDocument();
  expect(screen.getByText(`attempted:${ATTEMPTED_PATH}`)).toBeInTheDocument();
}

/**
 * The administrative guard's unauthenticated branch carries the same bounce state.
 *
 * Assumptions: asserted separately rather than assumed from the case above, because the two guards are
 * two functions and the shared helper between them is one an edit could drop from either.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theAdministrativeGuardBouncesWithTheSameState(): void {
  render(renderGuardedAt(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>, ATTEMPTED_PATH));

  expect(screen.getByText(`reason:${SIGN_ON_BOUNCE_REASON}`)).toBeInTheDocument();
  expect(screen.getByText(`attempted:${ATTEMPTED_PATH}`)).toBeInTheDocument();
}

/**
 * A destination in an unagreed shape is discarded while the reason survives.
 *
 * Purpose: fix the failure DIRECTION of the reader. A protocol-relative value such as `//evil.example`
 * is a legal relative reference that `navigate` hands to `pushState`, and the browser resolves it off
 * this origin -- so an operator who signed on would be carried out of the application by a transition
 * they believe resumes their own screen. The reader must drop it.
 *
 * Assumptions: the reason is required to SURVIVE the rejection, which is the other half of the contract.
 * Dropping both would return the operator to the silent arrival this whole carrier exists to end.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function anOffApplicationDestinationIsDiscardedButTheReasonSurvives(): void {
  render(
    <MemoryRouter
      initialEntries={[
        {
          pathname: '/signon',
          state: { reason: SIGN_ON_BOUNCE_REASON, attempted: '//evil.example' },
        },
      ]}
    >
      <Routes>
        <Route path="/signon" element={<BounceStateProbe />} />
      </Routes>
    </MemoryRouter>,
  );

  expect(screen.getByText('attempted:none')).toBeInTheDocument();
  expect(screen.getByText(`reason:${SIGN_ON_BOUNCE_REASON}`)).toBeInTheDocument();
}

/**
 * The refusal keeps the frame, carries a real heading, and offers a way back.
 *
 * ⚠️ Refactoring Rationale: this case is NEW, and each expectation names a measured defect of the antd
 * `Result status="403"` it replaces. That surface was a full-page takeover, so the frame -- its title
 * band, its row-23 message line and its row-24 `nav[aria-label="Function keys"]` legend -- measured
 * absent, and with the session held only in memory a reload could not recover it, leaving the browser's
 * own back control as the only route out. Its heading was a `div.ant-result-title` rather than a heading
 * element, so an operator navigating by heading found none. And it carried a decorative illustration on
 * a system AAP section 0.4.4 requires to be imagery-free.
 *
 * Assumptions: the sentence is asserted at BOTH widths on purpose. The query is given the trimmed form
 * because Testing Library normalises the text it reads, and the matched element's own `textContent` is
 * then compared against the UNTRIMMED constant -- which is what proves the fixed-width field's trailing
 * blank survived Rule T8's crossing rather than being trimmed away by the message band's normaliser.
 *
 * Assumptions: `getAllByText` is used for the count rather than `getByText`, because `getByText` throws
 * on a second match and a thrown query reports "multiple elements" rather than the number of them. One
 * copy is required: several suites locate this sentence with `findByText`, so a second would fail them.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function refusalKeepsTheFrameAndOffersAWayBack(): Promise<void> {
  await establishSession({ groups: ['carddemo-user'] });

  render(renderGuardedInFrame(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();

  const refusals = screen.getAllByText(ACCESS_DENIED_TEXT);
  expect(refusals).toHaveLength(1);
  expect(refusals[0]?.textContent).toBe(ACCESS_DENIED_ADMIN_ONLY);

  expect(screen.getByRole('heading', { name: ACCESS_DENIED_HEADING })).toBeInTheDocument();
  expect(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toBeInTheDocument();

  /*
   * WHY : ⚠️ Refactoring Rationale: the title band is asserted PRESENT, and this expectation is a
   *       correction rather than an addition. The refusal first delegated no screen identity at all, on
   *       the reasoning that it replaces no program -- and a browser measurement of the running
   *       application showed what that cost: `ScreenHeader` renders only when a screen delegates, so
   *       the header carried nothing but the skip link and the sign-off control, the document had no
   *       `h1`, and the date and time fields were absent. Three of the four zones AAP section 0.4.4
   *       makes persistent were still missing from the surface built to stop destroying them.
   * WHY : Assumptions: the identity asserted is the MAIN MENU's, because that is whose screen the
   *       reference paints this sentence on -- `app/cbl/COMEN01C.cbl` L136-L143 moves it to
   *       `WS-MESSAGE` and re-sends its own menu, so the operator never leaves `CM00`. Asserting the
   *       VALUES rather than merely the band's presence is what catches the other plausible mistake:
   *       delegating the administrative program the caller was refused, which would report them into a
   *       transaction that never ran.
   */
  expect(screen.getByText(REFUSAL_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(REFUSAL_PROGRAM_NAME)).toBeInTheDocument();
  expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /F3=Back/u })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: MAIN_MENU_HEADINGS.SCREEN })).toBeInTheDocument();

  /*
   * WHY : Assumptions: the illustration is asserted absent by its CLASS rather than by looking for an
   *       `img`, because antd's status result draws its artwork as inline `svg` inside
   *       `.ant-result-icon` and no `img` element was ever present. Naming the class is what makes the
   *       expectation fail if the result component is reintroduced.
   */
  expect(document.querySelectorAll('.ant-result')).toHaveLength(0);
  expect(document.querySelectorAll('.ant-result-icon')).toHaveLength(0);
}

/**
 * A claim carrying the administrative group reaches the administrative screen.
 * @returns {Promise<void>} Resolves once the assertion holds.
 */
async function admitsACallerWhoseClaimCarriesTheAdministrativeGroup(): Promise<void> {
  await establishSession({ groups: ['carddemo-admin', 'carddemo-user'] });

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.getByText('ADMIN ONLY')).toBeInTheDocument();
}

/**
 * A group claim in an unagreed shape carries no groups, so the administrative surface stays closed.
 *
 * Assumptions: the failure direction matters: a decoder that returned every group on a claim it could
 * not read would open the surface to any token that carried one.
 *
 * Refactoring Rationale: ⚠️ this case used to seed the literal string `'not-a-token'` as the identity
 * token, and it can no longer do so — `installTokens` refuses a set whose identity token yields no
 * subject at all, so a wholly undecodable token cannot establish a session in the first place. That is
 * a STRONGER guarantee than this case asserted and it is covered where it is enforced, so the case is
 * reframed onto the weaker condition that survives: a token that decodes, names the right operator, and
 * carries its group claim in a shape this application never agreed with the pool. A lone group
 * serialised as a bare string is the realistic form, and coercing it would let that shape decide an
 * authority question.
 * @returns {Promise<void>} Resolves once the assertions hold.
 */
async function treatsAnUndecodableTokenAsCarryingNoGroups(): Promise<void> {
  const claims = JSON.stringify({
    'cognito:username': SESSION_USER_ID,
    'cognito:groups': 'carddemo-admin',
  });
  await establishSession({ idToken: `header.${btoa(claims)}.signature` });

  render(renderGuarded(<RequireAdmin>{<div>ADMIN ONLY</div>}</RequireAdmin>));

  expect(screen.queryByText('ADMIN ONLY')).not.toBeInTheDocument();
  expect(screen.getByText(ACCESS_DENIED_TEXT)).toBeInTheDocument();
}

/**
 * Registers the route-guard cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function routeGuardCases(): void {
  it('redirects an unauthenticated caller to sign-on', redirectsAnUnauthenticatedCallerToSignOn);
  it('admits a caller holding a token', admitsACallerHoldingAToken);
  it(
    'redirects an unauthenticated caller away from an administrative route',
    redirectsAnUnauthenticatedCallerAwayFromAnAdministrativeRoute,
  );
  it(
    'refuses an authenticated caller without the administrative group',
    refusesAnAuthenticatedCallerWithoutTheAdministrativeGroup,
  );
  it('keeps the frame and offers a way back while refusing', refusalKeepsTheFrameAndOffersAWayBack);
  it(
    'hands sign-on the reason and the attempted destination',
    bounceCarriesTheReasonAndTheAttemptedDestination,
  );
  it(
    'bounces from an administrative route with the same state',
    theAdministrativeGuardBouncesWithTheSameState,
  );
  it(
    'discards an off-application destination and keeps the reason',
    anOffApplicationDestinationIsDiscardedButTheReasonSurvives,
  );
  it(
    'admits a caller whose claim carries the administrative group',
    admitsACallerWhoseClaimCarriesTheAdministrativeGroup,
  );
  it(
    'treats a group claim in an unagreed shape as carrying no groups',
    treatsAnUndecodableTokenAsCarryingNoGroups,
  );
}

beforeEach(clearSessionAndInstallHarness);

afterEach(clearSessionAndRemoveHarness);

describe('route guards', routeGuardCases);
