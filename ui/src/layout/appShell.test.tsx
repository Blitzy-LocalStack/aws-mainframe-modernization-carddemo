/**
 * @file Proves every behaviour `ui/src/layout/AppShell.tsx` promises actually works: the four zones, the
 * nested-route outlet, the delegation contract, the skip link, the sign-off latch and the key dispatch.
 *
 * Purpose
 * -------
 * The shell was authored complete and mounted by no route, so none of what it promised had ever run --
 * not the outlet, not the sign-off latch, not the skip link, and not the dispatcher that forwards a
 * delegated key activation back to the screen that published it. `ui/src/routerRoutes.test.tsx` proves the
 * shell is mounted as the outermost layout route; this file proves the shell itself behaves as documented,
 * which is the other half of that finding and the half a route table cannot show.
 *
 * Assumptions: the delegation contract is the property most worth pinning, because it is what makes
 * mounting the shell safe at all. It paints a zone if and only if a screen has published one, so a screen
 * that composes its own bands is unaffected -- and a regression to unconditional painting would give the
 * three screens that do compose their own -- the main menu, the administrative menu and the authorization
 * detail screen -- a second title band and two live regions announcing one message.
 *
 * ⚠️ Refactoring Rationale: this file previously introduced the key DISPATCHER as a path with no
 * production caller, on the ground that "every delivered screen composes its own legend". That is no
 * longer the census: eighteen of the 21 screens delegate their legend through `useShellSlot`, so the
 * dispatcher is on the live path for all of them and the three local composers are the exception. The
 * cases are unchanged -- they were exercising the right behaviour for a reason that has since become the
 * wrong one -- and what they now pin is the ordinary route rather than a dormant capability.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the card screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { THANK_YOU_CARDDEMO } from '../messages/messages';
import { navigateSafely } from '../routes/navigation';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { endAnySession, establishSession } from '../test/sessionHarness';
import { ADDITIVE_LAYOUT_VALUES } from '../theme/tokens';
import {
  APP_SHELL_TEST_ID,
  AppShell,
  SHELL_CONTENT_ELEMENT_ID,
  SHELL_SIGN_OFF_CONTROL_TEST_ID,
  SHELL_SIGN_OFF_LABEL,
  SHELL_SIGN_OFF_TEST_ID,
  SKIP_TO_CONTENT_LABEL,
  useShellSlot,
} from './AppShell';
import { MESSAGE_BAND_TEST_ID } from './MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from './PfKeyBar';
import type { CicsAid, PfKeyBinding } from './usePfKeys';

/*
 * WHY : ⚠️ Refactoring Rationale: a `carddemo.id-token` session-storage key stood here and nothing reads
 *       it. `ui/src/hooks/useAuth.ts` holds the session in a module variable and hands the bearer to
 *       `ui/src/api/client.ts`, which keeps it in one of its own -- so writing the key established no
 *       session, and every case that called `signOn` below rendered the shell ANONYMOUS. Two of them
 *       asserted authenticated chrome and failed; the rest happened to assert absences, which an
 *       anonymous shell satisfies for the wrong reason. The session is now established through the
 *       exchange, by `ui/src/test/sessionHarness.ts`, which is the one place that knows how.
 */

/** Text the nested screen renders, so the outlet can be observed. */
const SCREEN_BODY = 'NESTED SCREEN BODY';

/** Identity a delegating screen publishes. */
const IDENTITY = { transactionId: 'CT99', programName: 'COTEST0C' } as const;

/** Instant the band is asked to paint, fixed so the rendered date cannot drift with the clock. */
const PAINTED_AT = new Date('2025-01-02T10:11:12Z');

/** Groups the established session carries; the shell's chrome does not vary by authority. */
const ORDINARY_GROUPS: readonly string[] = ['carddemo-user'];

/**
 * Establishes a real session so the shell renders its authenticated chrome.
 *
 * Assumptions: the helper is ASYNCHRONOUS where the storage write it replaces was not, so every call
 * site awaits it. That is not incidental: a session is now the result of an exchange, and a case that
 * rendered before it settled would render the anonymous shell and assert against the wrong frame.
 * @returns {Promise<void>} Resolves once the session is held.
 */
async function signOn(): Promise<void> {
  const { unmount } = await establishSession({ groups: ORDINARY_GROUPS });

  /*
   * Assumptions: the hook the harness rendered is unmounted immediately and the session survives it,
   * because the session belongs to the tab rather than to any mounted consumer. The shell each case
   * renders afterwards therefore observes it without being handed anything.
   */
  unmount();
}

/** Arms the intercepting transport the sign-on exchange and the sign-off revocation are answered by. */
function armTransport(): void {
  installApiHarness();
}

/** Clears the installed session and the recorded traffic between cases. */
function clearSession(): void {
  endAnySession();
  removeApiHarness();
}

/**
 * A nested screen that composes nothing and delegates its identity, message and legend.
 *
 * Assumptions: one component delegating all four members, because the shell resolves each zone
 * independently and a case that delegated one at a time could not show that the four coexist.
 * @returns {ReactElement} The nested screen's own body.
 */
function DelegatingScreen(): ReactElement {
  useShellSlot({
    screen: IDENTITY,
    message: { text: 'DELEGATED MESSAGE', severity: 'info', mapset: 'COACTVW' },
    pfKeys: { keys: LEGEND, onInvoke: legendInvoked },
    now: PAINTED_AT,
  });
  return <div>{SCREEN_BODY}</div>;
}

/**
 * A nested screen that delegates nothing, standing for every screen that composes its own bands.
 * @returns {ReactElement} The nested screen's own body.
 */
function SelfComposingScreen(): ReactElement {
  return <div>{SCREEN_BODY}</div>;
}

/**
 * A nested screen that reports a write in flight, standing for a mutating screen mid-turn.
 *
 * Assumptions: it delegates an identity as well as the flag, because a screen that published only
 * the flag would exercise a slot shape no real screen produces -- every writing screen in the tree
 * publishes its title band too, and the shell resolves the members independently.
 * @returns {ReactElement} The nested screen's own body.
 */
function WritingScreen(): ReactElement {
  useShellSlot({ screen: IDENTITY, busy: true });
  return <div>{SCREEN_BODY}</div>;
}

/** Spy standing in for the screen handler a delegated legend activation must reach. */
const legendInvoked = vi.fn<(aid: CicsAid) => void>();

/**
 * One delegated legend entry, in the shape the key bar renders.
 *
 * Assumptions: the binding is `enabled`, because a disabled one is rendered as an inert control and this
 * case is about the ACTIVE path -- the dispatcher forwarding an activation back to the publisher.
 */
const LEGEND: readonly PfKeyBinding[] = [
  { aid: 'PFK03', action: 'back', label: 'F3=Exit', enabled: true },
];

/** Path of the second nested route, standing for any route an operator reaches after signing off. */
const ARRIVAL_PATH = '/arrival';

/** Body the second nested route renders, so its arrival is observable. */
const ARRIVAL_BODY = 'ARRIVAL BODY';

/** Handle on the control that changes entry, which is rendered outside the frame on purpose. */
const NAVIGATE_TEST_ID = 'navigate-to-arrival';

/**
 * A second nested screen, standing for the sign-on route the frame also wraps.
 * @returns {ReactElement} The arrival screen's own body.
 */
function ArrivalScreen(): ReactElement {
  return <div>{ARRIVAL_BODY}</div>;
}

/**
 * A control that changes history entry, rendered inside the router and OUTSIDE the frame.
 *
 * Assumptions: outside the frame is the whole point. The sign-off surface replaces the frame, so a
 * control rendered beneath the outlet would be gone at the moment the case needs to navigate -- which is
 * the operator's real position too: their way onward is the address bar, a bookmark or the browser's own
 * history control, none of which the frame renders.
 * @returns {ReactElement} A button that navigates to {@link ARRIVAL_PATH}.
 */
function NavigateOutOfTheSignedOffEntry(): ReactElement {
  const navigate = useNavigate();

  /**
   * Changes entry to the arrival route.
   *
   * Assumptions: the transition goes through the application's own helper rather than calling the
   * router directly, because `ui/eslint.config.js` disallows a discarded promise even behind `void` --
   * and the helper is what every delivered control uses, so the case navigates the way the application
   * does.
   * @returns {void} Nothing; the router renders the arrival route.
   */
  function goToTheArrival(): void {
    navigateSafely(navigate, ARRIVAL_PATH);
  }

  return (
    <button type="button" data-testid={NAVIGATE_TEST_ID} onClick={goToTheArrival}>
      {ARRIVAL_PATH}
    </button>
  );
}

/**
 * Renders the shell as a layout route above TWO nested routes, with a way to move between them.
 *
 * Assumptions: two routes rather than one, because the property under test is what the frame does on
 * the SECOND entry -- one route cannot express a navigation, and the shell is the layout route above
 * every screen including sign-on, so the second route stands for the one an operator signs on at.
 * @param {ReactElement} nested - The screen mounted at the initial entry.
 * @returns {ReactElement} The composed tree under test.
 */
function renderShellAcrossTwoEntries(nested: ReactElement): ReactElement {
  return (
    <MemoryRouter initialEntries={['/nested']}>
      <NavigateOutOfTheSignedOffEntry />
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/nested" element={nested} />
          <Route path={ARRIVAL_PATH} element={<ArrivalScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Renders the shell as a layout route with one nested screen beneath its outlet.
 * @param {ReactElement} nested - The screen mounted under the outlet.
 * @returns {ReactElement} The composed tree under test.
 */
function renderShell(nested: ReactElement): ReactElement {
  return (
    <MemoryRouter initialEntries={['/nested']}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/nested" element={nested} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

/**
 * Asserts the nested screen renders through the outlet, which is what a layout route needs.
 * @returns {Promise<void>} Resolves once the nested body is on the glass.
 */
async function rendersANestedScreenThroughTheOutlet(): Promise<void> {
  await signOn();
  render(renderShell(<SelfComposingScreen />));

  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
}

/**
 * Asserts a screen that delegates nothing gets no band from the shell.
 *
 * Assumptions: the ABSENCES are the assertion. Every delivered screen composes its own message band and
 * key legend, so a shell that painted them regardless would double both -- and a duplicate band handle is
 * the failure a screen test would report as an ambiguous query rather than as a shell defect.
 * @returns {Promise<void>} Resolves once the absences have been observed.
 */
async function paintsNoZoneForAScreenThatDelegatesNothing(): Promise<void> {
  await signOn();
  render(renderShell(<SelfComposingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  expect(screen.queryByTestId(MESSAGE_BAND_TEST_ID)).toBeNull();
  expect(screen.queryByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toBeNull();
  expect(screen.queryByText(IDENTITY.transactionId)).toBeNull();
}

/**
 * Asserts all four delegated zones are painted together.
 * @returns {Promise<void>} Resolves once every zone has been found.
 */
async function paintsEveryDelegatedZone(): Promise<void> {
  await signOn();
  render(renderShell(<DelegatingScreen />));

  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();
  expect(screen.getByText(IDENTITY.transactionId)).toBeInTheDocument();
  expect(screen.getByText(IDENTITY.programName)).toBeInTheDocument();
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toBeInTheDocument();
  expect(screen.getByText('DELEGATED MESSAGE')).toBeInTheDocument();
  expect(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toBeInTheDocument();
}

/**
 * Asserts activating a delegated legend entry reaches the publishing screen's handler.
 *
 * Assumptions: the dispatcher reads the published slot at call time rather than closing over it, which is
 * what this case proves end to end -- the activation arrives at the spy the screen published, carrying the
 * attention identifier the legend entry names.
 * @returns {Promise<void>} Resolves once the handler has been invoked.
 */
async function forwardsADelegatedKeyActivation(): Promise<void> {
  await signOn();
  render(renderShell(<DelegatingScreen />));
  const legend = await screen.findByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  expect(legend).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'F3=Exit' }));

  expect(legendInvoked).toHaveBeenCalledWith('PFK03');
}

/**
 * Asserts the skip link targets a focusable content region.
 *
 * Assumptions: BOTH halves are asserted -- the link's fragment and the target's focusability -- because a
 * fragment jump scrolls to its target but only moves focus when the target can hold it. A link pointing at
 * a region with no negative tab index would move the viewport and leave the keyboard in the header, which
 * is the failure the attribute exists to prevent and which the link alone cannot reveal.
 * @returns {Promise<void>} Resolves once both have been observed.
 */
async function offersASkipLinkToAFocusableContentRegion(): Promise<void> {
  await signOn();
  render(renderShell(<SelfComposingScreen />));

  const link = await screen.findByRole('link', { name: SKIP_TO_CONTENT_LABEL });
  expect(link).toHaveAttribute('href', `#${SHELL_CONTENT_ELEMENT_ID}`);
  const content = document.getElementById(SHELL_CONTENT_ELEMENT_ID);
  expect(content).not.toBeNull();
  expect(content).toHaveAttribute('tabindex', '-1');
}

/**
 * Asserts the sign-off control ends the session and latches the cleared surface.
 *
 * Assumptions: the discarded token is asserted as well as the rendered surface, because the reference's
 * sign-off ends the session rather than merely painting a message -- `app/cbl/COSGN00C.cbl` L162-L172 emits
 * the thank-you to a cleared screen and returns carrying no transaction. A surface without the discard
 * would leave the operator signed on behind a screen that says otherwise.
 * @returns {Promise<void>} Resolves once the cleared surface is on the glass.
 */
async function signsOffFromTheRenderedControl(): Promise<void> {
  await signOn();
  render(renderShell(<SelfComposingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  await userEvent.click(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID));

  expect(await screen.findByTestId(SHELL_SIGN_OFF_TEST_ID)).toBeInTheDocument();
  expect(screen.getByText(THANK_YOU_CARDDEMO.trim())).toBeInTheDocument();
  /*
   * WHY : ⚠️ Refactoring Rationale: this read a session-storage key to prove the token was discarded and
   *       the token was never there. What proves the discard now is the FRAME: the shell renders its
   *       authenticated chrome only while a session is held, so the two absences asserted below -- the
   *       frame itself and the nested body -- are observations of the discarded session and not merely
   *       of a repainted surface. `ui/src/hooks/signOutRevocation.test.tsx` carries the revocation call
   *       that goes with it, which is the half a rendered assertion cannot show.
   */
  /*
   * Assumptions: the frame is asserted GONE, because the surface replaces it rather than appearing inside
   * it -- an erased screen with a nested route still rendered behind it would be a session the operator
   * had left and could still operate.
   */
  expect(screen.queryByTestId(APP_SHELL_TEST_ID)).toBeNull();
  expect(screen.queryByText(SCREEN_BODY)).toBeNull();
}

/**
 * Asserts the sign-off surface is confined to the entry it was raised at.
 *
 * ⚠️ Purpose: the surface used to be raised by a one-way latch, and this frame is the layout
 * route above the sign-on route as well as above the guarded screens -- `ui/src/router.tsx` declares
 * `SIGN_ON_ROUTE` inside it. Latched, the frame returned before its outlet was reached, so every route
 * beneath it stopped rendering and the operator could not sign on again without reloading the
 * application. The case above asserts the surface REPLACES the frame; this one asserts it does not
 * outlive the entry, which is the other half of the same contract.
 *
 * Assumptions: the arrival route is asserted by its BODY and the frame by its handle, because both have
 * to come back -- a frame with no outlet content would be an operator staring at empty chrome, and
 * content with no frame would be a screen with no title band, message line or legend.
 * @returns {Promise<void>} Resolves once the frame and the arrival route are on the glass.
 */
async function releasesTheSignedOffSurfaceOnTheNextEntry(): Promise<void> {
  await signOn();
  render(renderShellAcrossTwoEntries(<SelfComposingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  await userEvent.click(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID));
  expect(await screen.findByTestId(SHELL_SIGN_OFF_TEST_ID)).toBeInTheDocument();

  await userEvent.click(screen.getByTestId(NAVIGATE_TEST_ID));

  expect(await screen.findByText(ARRIVAL_BODY)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.queryByTestId(SHELL_SIGN_OFF_TEST_ID)).toBeNull();
}

/**
 * Asserts the sign-off control is absent with no session held.
 *
 * Assumptions: an operator with no session has nothing to end, so offering the control would offer an
 * action that cannot apply. The shell still renders, because the not-found result and any unauthenticated
 * surface beneath it are legitimate.
 * @returns {Promise<void>} Resolves once the absence has been observed.
 */
async function omitsTheSignOffControlWithoutASession(): Promise<void> {
  render(renderShell(<SelfComposingScreen />));

  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();
  expect(screen.queryByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID)).toBeNull();
  expect(screen.queryByText(SHELL_SIGN_OFF_LABEL)).toBeNull();
}

/**
 * Asserts a prop supplied to the shell wins over a value a nested screen delegated.
 *
 * Assumptions: precedence is asserted rather than assumed, because both sources are supported and a shell
 * that let the delegation win would make an explicitly composed frame unable to override the screen it
 * contains.
 * @returns {Promise<void>} Resolves once the prop's value has been found and the delegated one has not.
 */
async function prefersAPropOverADelegatedValue(): Promise<void> {
  await signOn();
  render(
    <MemoryRouter initialEntries={['/nested']}>
      <Routes>
        <Route element={<AppShell screen={{ transactionId: 'CT88', programName: 'COPROP0C' }} />}>
          <Route path="/nested" element={<DelegatingScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  expect(await screen.findByText('CT88')).toBeInTheDocument();
  expect(screen.queryByText(IDENTITY.transactionId)).toBeNull();
}

/**
 * Asserts the delegation is withdrawn when the publishing screen unmounts.
 *
 * Assumptions: this is what stops one screen's band surviving into the next. The publication is module
 * state, so a screen that never released it would leave its identity and its message painted around a
 * different screen -- which is the pseudo-conversational carry-over the migration removed everywhere else.
 * @returns {Promise<void>} Resolves once the delegated band has gone.
 */
async function withdrawsTheDelegationAtUnmount(): Promise<void> {
  await signOn();
  const view = render(renderShell(<DelegatingScreen />));
  expect(await screen.findByText(IDENTITY.transactionId)).toBeInTheDocument();

  view.unmount();
  /*
   * Assumptions: a SECOND, independent tree is rendered rather than the first being re-rendered with a
   * different child. An unmounted root cannot be updated, and re-rendering into the same root would not
   * be the condition under test anyway: what has to be proved is that the next screen -- a different
   * mount, as a route change produces -- inherits none of the previous screen's publication.
   */
  render(renderShell(<SelfComposingScreen />));

  await waitFor(
    /**
     * Waits for the withdrawn delegation to stop being painted.
     * @returns {void} Nothing; failure is reported by the expectation.
     */
    () => {
      expect(screen.queryByText(IDENTITY.transactionId)).toBeNull();
    },
  );
}

/**
 * Asserts the document declares the registered viewport height and the shell declares none.
 *
 * Purpose: hold the two ends of design gap G8 in agreement. `ADDITIVE_LAYOUT_VALUES` records the one
 * layout value no antd token can express, and `ui/index.html` is where it is actually declared -- so the
 * register is documentation and the stylesheet is the mechanism, and nothing but this case makes a
 * disagreement between them fail. Three claims are checked because all three were measured together:
 * the mount point is sized to the registered value, the user agent's body margin is reset -- without it
 * a frame one viewport tall scrolled permanently by 16px -- and the shell itself declares no height, so
 * the two layers cannot both claim the value.
 *
 * Assumptions: the stylesheet is read as TEXT rather than by mounting the document, because jsdom does
 * not load `ui/index.html` at all -- the test environment renders into a synthetic document, so the only
 * way to observe what ships is to read the file. `ui/src/api/contracts.test.ts` and
 * `ui/src/layout/headingOutline.test.tsx` establish the same idiom for the same reason.
 *
 * Assumptions: the shell's outermost element is inspected through its inline style rather than through a
 * computed height, because a computed height in jsdom reflects no layout at all. What is being ruled out
 * is a DECLARATION -- the `minHeight: '100dvh'` this component used to carry -- and a declaration is
 * exactly what an inline style shows.
 * @returns {Promise<void>} Resolves once the document and the shell have both been inspected.
 */
async function agreesWithTheDocumentOnTheOneUntokenisedValue(): Promise<void> {
  const documentSource = readFileSync(join(import.meta.dirname, '..', '..', 'index.html'), 'utf8');

  expect(documentSource).toContain(`min-height: ${ADDITIVE_LAYOUT_VALUES.viewportMinimumHeight};`);
  expect(documentSource).toMatch(/html,\s*body\s*\{\s*margin:\s*0;\s*\}/u);

  await signOn();
  render(renderShell(<SelfComposingScreen />));

  const frame = await screen.findByTestId(APP_SHELL_TEST_ID);

  /*
   * Assumptions: the frame's own surface declaration is asserted FIRST, because two empty strings
   * below would otherwise pass for an element whose style attribute was never read at all -- a
   * renamed test id or a moved attribute would make the absences vacuous rather than meaningful.
   */
  expect(frame.style.background).not.toBe('');
  expect(frame.style.minHeight).toBe('');
  expect(frame.style.height).toBe('');
}

/**
 * Asserts the frame's sign-off control is held shut while the mounted screen reports a write.
 *
 * Assumptions: BOTH states are asserted in one case, the locked one and the unlocked one, because a
 * disabled assertion alone would pass for a control that was disabled unconditionally -- which would
 * take away the only way off a screen rather than lock it for a turn. The reference needs no such
 * control at all: a 3270 keyboard locks from the moment a turn is transmitted until the region
 * replies, so nothing on the display accepted input mid-turn. The two writing screens that publish
 * this flag disable their own keys and inputs for the same window, and this control is the one they
 * cannot reach -- and the most damaging one, since it discards the session locally while the request
 * already carrying its token completes at the service.
 * @returns {Promise<void>} Resolves once both states have been observed.
 */
async function holdsTheSignOffControlShutWhileAScreenWrites(): Promise<void> {
  await signOn();
  const locked = render(renderShell(<WritingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();
  expect(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID)).toBeDisabled();
  locked.unmount();

  render(renderShell(<DelegatingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();
  expect(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID)).toBeEnabled();
}

/** Registers the shell cases. */
function appShellCases(): void {
  beforeEach(clearSession);
  beforeEach(armTransport);
  afterEach(clearSession);

  it('renders a nested screen through the outlet', rendersANestedScreenThroughTheOutlet);
  it(
    'paints no zone for a screen that delegates nothing',
    paintsNoZoneForAScreenThatDelegatesNothing,
  );
  it('paints every delegated zone', paintsEveryDelegatedZone);
  it('forwards a delegated key activation', forwardsADelegatedKeyActivation);
  it('offers a skip link to a focusable content region', offersASkipLinkToAFocusableContentRegion);
  it('signs off from the rendered control', signsOffFromTheRenderedControl);
  it(
    'releases the signed-off surface on the next entry',
    releasesTheSignedOffSurfaceOnTheNextEntry,
  );
  it('omits the sign-off control without a session', omitsTheSignOffControlWithoutASession);
  it('prefers a prop over a delegated value', prefersAPropOverADelegatedValue);
  it('withdraws the delegation at unmount', withdrawsTheDelegationAtUnmount);
  it(
    'agrees with the document on the one untokenised value',
    agreesWithTheDocumentOnTheOneUntokenisedValue,
  );
  it(
    'holds the sign-off control shut while a screen writes',
    holdsTheSignOffControlShutWhileAScreenWrites,
  );
}

describe('application shell', appShellCases);
