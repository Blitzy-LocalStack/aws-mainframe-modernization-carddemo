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

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { theme } from 'antd';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { useState } from 'react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { STATUS_MESSAGES, THANK_YOU_CARDDEMO } from '../messages/messages';
import { navigateSafely } from '../routes/navigation';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { endAnySession, establishSession } from '../test/sessionHarness';
import { ADDITIVE_LAYOUT_VALUES, BREAKPOINT_TOKENS } from '../theme/tokens';
import {
  APP_SHELL_TEST_ID,
  AppShell,
  SHELL_CONTENT_ELEMENT_ID,
  SHELL_PINNED_ZONE_TEST_ID,
  SHELL_MAIN_MENU_CROSSING_TEST_ID,
  SHELL_SIGN_OFF_CONTINUE_TEST_ID,
  SHELL_SIGN_OFF_CONTROL_TEST_ID,
  SHELL_SIGN_OFF_LABEL,
  SHELL_SIGN_OFF_TEST_ID,
  SKIP_TO_CONTENT_LABEL,
  useShellSlot,
} from './AppShell';
import type { ShellInformationSlot } from './AppShell';
import { APP_TITLE_DISPLAY, MAIN_MENU_HEADINGS } from '../messages/messages';
import { MAIN_MENU_ROUTE, SIGN_ON_ROUTE } from '../routes/navigation';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from './MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from './PfKeyBar';
import { HEADER_PROMPT_LABELS } from './ScreenHeader';
import { APP_TITLE_HEADING_LEVEL } from './ScreenTitle';
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
 * Groups an administrative session carries, for the one control that renders only for one.
 *
 * Assumptions: the literal matches `ui/src/hooks/useAuth.ts`'s own admin group name, which is the
 * migration of `SEC-USR-TYPE` value `'A'`. It is spelled here rather than imported for the same reason
 * {@link ORDINARY_GROUPS} is: this file establishes sessions through the identity harness and a claim
 * value is data the harness is given, not a module contract under test.
 */
const ADMINISTRATIVE_GROUPS: readonly string[] = ['carddemo-admin'];

/**
 * Establishes an ADMINISTRATIVE session, then discards the harness that established it.
 * @returns {Promise<void>} Resolves once the session is held and the harness is unmounted.
 */
async function signOnAsAdministrator(): Promise<void> {
  const { unmount } = await establishSession({ groups: ADMINISTRATIVE_GROUPS });
  unmount();
}

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

/** Handle on the probe that reports how the design system's large-screen bound serialises. */
const BOUND_PROBE_TEST_ID = 'shell-bound-probe';

/**
 * Renders the design system's own large-screen bound into a style, so a case can compare against it.
 *
 * Purpose: the body column's maximum measure is a TOKEN, and a case that wrote its resolved width out
 * as a literal would be asserting today's theme rather than the decision. This probe reads the same
 * register `AppShell` reads, through the same hook, and declares it on the same property -- so the
 * comparison is against whatever the bridge nominates as "large", and an invented breakpoint cannot
 * satisfy it.
 *
 * Assumptions: the value is compared after REACT has serialised it rather than as a raw token. The
 * breakpoint family is the one part of the register that resolves to a number rather than to a
 * `var(--…)` reference - `ui/src/layout/AppShell.tsx` records why - so React appends the unit, and
 * declaring it here reproduces that step instead of guessing at it.
 * @returns {ReactElement} An empty element carrying the bound on its own inline style.
 */
function BoundProbe(): ReactElement {
  const { cssVar } = theme.useToken();

  return (
    <span
      data-testid={BOUND_PROBE_TEST_ID}
      style={{ maxInlineSize: cssVar[BREAKPOINT_TOKENS.large] }}
    />
  );
}

/** Row-22 advisory text, standing for the standing guidance `INFOMSG` carries on every turn. */
const ADVISORY_TEXT = 'ENTER OR UPDATE ID OF ACCOUNT TO DISPLAY';

/** Row-23 outcome text, standing for what `ERRMSG` carries after the turn just taken. */
const OUTCOME_TEXT = 'ACCOUNT NOT FOUND';

/**
 * A nested screen that delegates BOTH message channels, standing for a two-line mapset.
 *
 * Assumptions: it stands in for `COACTVW`, which is one of the five mapsets that declare a row-22
 * field, so the two bands it produces are the two the reference declares rather than an invented
 * pair. It also delegates a legend, because the pinned zone holds all three lines and a case that
 * inspected the zone without one could not show the legend is inside it.
 * @returns {ReactElement} The nested screen's own body.
 */
function AdvisoryScreen(): ReactElement {
  useShellSlot({
    screen: IDENTITY,
    message: {
      text: OUTCOME_TEXT,
      severity: 'error',
      mapset: 'COACTVW',
      information: { text: ADVISORY_TEXT },
    },
    pfKeys: { keys: LEGEND, onInvoke: legendInvoked },
    now: PAINTED_AT,
  });
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
          {/*
            Assumptions: the sign-on address is mounted here for the same reason it is mounted in the
            single-entry fixture -- sign-off replaces the address with it, so an unmounted route would
            leave the router matching nothing and this case would observe an empty document rather
            than the acknowledgement it is about to navigate away from.
          */}
          <Route path={SIGN_ON_ROUTE} element={<SignOnStandIn />} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

/** Test handle for the probe that reports the router's current address. */
const ADDRESS_PROBE_TEST_ID = 'address-probe';

/** Body the stand-in sign-on route renders, so its arrival is observable. */
const SIGN_ON_STANDIN_BODY = 'sign-on stand-in';

/**
 * Reports the router's current address, so a case can assert what sign-off did to it.
 *
 * Assumptions: the probe sits OUTSIDE the `Routes` element, so it reports the address whether the
 * shell is rendering its frame, its acknowledgement surface, or nothing at all. Reading the address
 * from inside a route would make the assertion depend on the very rendering under test.
 * @returns {ReactElement} A node carrying the current pathname as its text.
 */
function AddressProbe(): ReactElement {
  const { pathname } = useLocation();
  return <span data-testid={ADDRESS_PROBE_TEST_ID}>{pathname}</span>;
}

/**
 * Stands in for the delivered sign-on screen, which this file does not mount.
 *
 * ⚠️ Refactoring Rationale: the fixtures below did not mount this route at all, and did not need to
 * while sign-off replaced the frame WITHOUT moving the address. Sign-off now replaces the address with
 * `SIGN_ON_ROUTE`, so a fixture without the route matches nothing there and the shell renders neither
 * its frame nor its acknowledgement -- which is what the two cases below were failing on, for a reason
 * that had nothing to do with the property either one asserts. A stand-in rather than the real screen
 * because this file tests the frame, and the delivered screen carries a form, a catalogue and a client.
 * @returns {ReactElement} A minimal body proving the route matched.
 */
function SignOnStandIn(): ReactElement {
  return <p>{SIGN_ON_STANDIN_BODY}</p>;
}

/**
 * Renders the shell as a layout route with one nested screen beneath its outlet.
 * @param {ReactElement} nested - The screen mounted under the outlet.
 * @returns {ReactElement} The composed tree under test.
 */
function renderShell(nested: ReactElement): ReactElement {
  return (
    <MemoryRouter initialEntries={['/nested']}>
      <AddressProbe />
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/nested" element={nested} />
          <Route path={SIGN_ON_ROUTE} element={<SignOnStandIn />} />
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
   * WHY : ⚠️ Refactoring Rationale: the two assertions below did not exist and each pins a defect this
   *       surface used to carry. The address went on naming the screen the operator had left, so the
   *       location described a screen that was no longer mounted -- measured on the running application
   *       as `/admin` with nothing administrative rendered. And the surface held NO interactive element
   *       at all: a document-wide query for `a, button, input, [role=button], [tabindex]` returned an
   *       empty list, so an operator had no in-page way onward. Sign-off now replaces the address with
   *       the sign-on route and the surface carries one primary control back to it.
   */
  expect(screen.getByTestId(ADDRESS_PROBE_TEST_ID).textContent).toBe(SIGN_ON_ROUTE);
  expect(screen.getByTestId(SHELL_SIGN_OFF_CONTINUE_TEST_ID)).toBeInTheDocument();
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
 * Asserts the frame offers an administrator a persistent crossing to the ordinary main menu.
 *
 * ⚠️ Purpose: without it the two roles sat on DISJOINT navigation graphs. An administrator reached eight
 * routes through the interface and none of the thirteen ordinary business screens: the administrative
 * menu lists only the six options of `app/cpy/COADM02Y.cpy`, and PF3 there signs the operator off
 * rather than stepping back, because `app/cbl/COADM01C.cbl` L100-L102 returns to sign-on. The reference
 * operator was not stranded by that only because a terminal could key transaction `CM00` directly.
 *
 * Assumptions: BOTH directions are asserted -- present for an administrator, absent for an ordinary
 * operator -- because a control that rendered for everyone would advertise the administrative crossing
 * to an operator whose own menu already reaches every screen they may enter. The destination is checked
 * through the address probe rather than by rendering the menu, since this file mounts the frame and not
 * the delivered screens.
 * @returns {Promise<void>} Resolves once both directions have been observed.
 */
async function offersAnAdministratorACrossingToTheMainMenu(): Promise<void> {
  await signOnAsAdministrator();
  render(renderShell(<SelfComposingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const crossing = screen.getByTestId(SHELL_MAIN_MENU_CROSSING_TEST_ID);
  expect(crossing).toHaveAccessibleName(MAIN_MENU_HEADINGS.SCREEN);

  await userEvent.click(crossing);
  expect(screen.getByTestId(ADDRESS_PROBE_TEST_ID).textContent).toBe(MAIN_MENU_ROUTE);
}

/**
 * Asserts an ordinary operator is offered no administrative crossing.
 * @returns {Promise<void>} Resolves once the absence has been observed.
 */
async function offersAnOrdinaryOperatorNoCrossing(): Promise<void> {
  await signOn();
  render(renderShell(<SelfComposingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  expect(screen.queryByTestId(SHELL_MAIN_MENU_CROSSING_TEST_ID)).toBeNull();
  // Assumptions: the sign-off control IS expected, so the absence above is the crossing being withheld
  //   rather than the whole chrome row failing to render.
  expect(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID)).toBeInTheDocument();
}

/**
 * Asserts the acknowledgement's own control returns the operator to the framed sign-on screen.
 *
 * ⚠️ Purpose: this is the property whose absence was the defect. The acknowledgement replaced the frame
 * and offered nothing to activate, so the only ways onward were browser Back and retyping an address --
 * neither of which is an in-page affordance, and the first of which is the one an operator reaches for
 * least willingly after being told a session has ended. The reference relies on a terminal operator
 * keying a new transaction against a cleared display; a browser has no equivalent, so the surface has
 * to supply one.
 *
 * Assumptions: the FRAME coming back is asserted alongside the surface going away, because dropping the
 * surface without restoring the frame would leave the operator on an empty document -- and the sign-on
 * route is mounted inside this shell, so a correct return renders chrome and a screen together. The
 * address is asserted unchanged, since the control moves within one address rather than to a new one.
 * @returns {Promise<void>} Resolves once the framed sign-on route is on the glass.
 */
async function leavesTheSignedOffSurfaceFromItsOwnControl(): Promise<void> {
  await signOn();
  render(renderShell(<SelfComposingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  await userEvent.click(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID));
  expect(await screen.findByTestId(SHELL_SIGN_OFF_TEST_ID)).toBeInTheDocument();

  await userEvent.click(screen.getByTestId(SHELL_SIGN_OFF_CONTINUE_TEST_ID));

  expect(await screen.findByText(SIGN_ON_STANDIN_BODY)).toBeInTheDocument();
  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.queryByTestId(SHELL_SIGN_OFF_TEST_ID)).toBeNull();
  expect(screen.getByTestId(ADDRESS_PROBE_TEST_ID).textContent).toBe(SIGN_ON_ROUTE);
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

/**
 * Asserts rows 23 and 24 are pinned to the bottom of the viewport in one zone.
 *
 * Purpose: this is the assertion for the frame's largest measured defect. The message line and the
 * key legend were ordinary in-flow siblings of the content region, so any screen taller than the
 * viewport pushed them off the bottom of it -- measured below the fold on 8 of 21 screens at 768 and
 * above and on 13 at 375, and 2,379 pixels below the fold on `/account/update` at 375. The reference
 * cannot reach that state: rows 23 and 24 exist on all 17 base mapsets and a 24-row display does not
 * scroll, so an operator could always see the outcome of a turn and the keys for the next one.
 *
 * Assumptions: the declaration is inspected through the inline style rather than through a computed
 * geometry, because jsdom performs no layout at all -- every rectangle it reports is zero, so a
 * scroll position cannot be simulated and the only observable fact is WHAT WAS DECLARED. What is
 * being ruled out is the absence of the declaration, which is exactly what an inline style shows.
 *
 * Assumptions: the surface is asserted alongside the position, because a sticky element overlaps
 * whatever is above it in flow -- so a transparent pinned zone would show the screen's own content
 * sliding through the message the operator is trying to read, which is a different defect rather
 * than the same one.
 *
 * Assumptions: both bands and the legend are asserted to be INSIDE the zone, because pinning a
 * wrapper that did not contain them would satisfy the style assertions and fix nothing.
 * @returns {Promise<void>} Resolves once the pinned zone and its three lines have been inspected.
 */
async function pinsTheMessageLineAndTheLegendToTheViewportBottom(): Promise<void> {
  await signOn();
  render(renderShell(<AdvisoryScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const zone = screen.getByTestId(SHELL_PINNED_ZONE_TEST_ID);

  expect(zone.style.position).toBe('sticky');
  expect(zone.style.insetBlockEnd).toBe('0px');
  expect(zone.style.background).not.toBe('');
  expect(zone.style.zIndex).not.toBe('');

  expect(zone).toContainElement(screen.getByTestId(INFORMATION_BAND_TEST_ID));
  expect(zone).toContainElement(screen.getByTestId(MESSAGE_BAND_TEST_ID));
  expect(zone).toContainElement(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL }));
}

/**
 * Asserts the header, the body, BOTH message lines and the legend share one left edge.
 *
 * Purpose: the frame had three different left edges. Measured across all 17 reachable routes, the
 * design system insets its own header and footer by a fixed 50 pixels, the content region had no
 * inset at all and the message line had none either -- so at 375, 576 and 768 the body ran edge to
 * edge with input borders flush against the literal viewport edge while the legend sat 50 pixels in,
 * at 992 the body was inset LESS (41.3) than the legend (50), and the row-23 band started at x=0 on
 * every route and visibly hung off the left edge of a frame whose heading sat at x=53.
 *
 * Assumptions: the case asserts the zones AGREE rather than asserting a particular width, and the
 * difference matters -- the width is a spacing token the theme is free to move, while the agreement
 * is the fidelity property the fixed 80-column grid had for free. So the header's declaration is
 * read first and everything else is compared to it.
 *
 * Assumptions: the two BANDS are reached through their common parent rather than through their own
 * styles, because the inset is declared once on the pinned zone that holds them. That is the fix
 * being pinned: an earlier revision of this case compared the header, the body and the footer only,
 * so it passed while both bands still began at x=0 -- the very defect it was written for. Asserting
 * that each band is a descendant of the padded zone is what closes that hole.
 *
 * Assumptions: the legend is asserted to declare NO horizontal inset of its own. It sits inside the
 * padded zone, so a second declaration there would inset it by two gutters and put a fourth left
 * edge back in the frame; `0px` is what displacing the design system's own `50px` looks like.
 * @returns {Promise<void>} Resolves once every zone's inset has been compared.
 */
async function givesEveryZoneOneHorizontalInset(): Promise<void> {
  await signOn();
  render(renderShell(<AdvisoryScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const header = screen.getByRole('banner');
  const inset = header.style.paddingInline;

  /*
   * Assumptions: the header's own declaration is asserted non-empty FIRST, because equal empty
   * strings below would otherwise pass for a frame that declared no inset anywhere -- which is the
   * measured defect rather than the fix for it.
   */
  expect(inset).not.toBe('');

  const content = document.getElementById(SHELL_CONTENT_ELEMENT_ID);
  expect(content).not.toBeNull();
  expect(content?.style.paddingInline).toBe(inset);

  const zone = screen.getByTestId(SHELL_PINNED_ZONE_TEST_ID);
  expect(zone.style.paddingInline).toBe(inset);
  expect(zone).toContainElement(screen.getByTestId(INFORMATION_BAND_TEST_ID));
  expect(zone).toContainElement(screen.getByTestId(MESSAGE_BAND_TEST_ID));

  const legend = screen.getByRole('contentinfo');
  expect(zone).toContainElement(legend);
  expect(legend.style.paddingInline).toBe('0px');
}

/**
 * Asserts the body column is bounded and centred rather than proportional.
 *
 * Purpose: the column was `span={24} lg={22}`, which fails two ways at once. A proportion has no
 * upper bound, so at roughly 1848 pixels the content stayed anchored at x=78 with the right half of
 * the display empty; and a proportional inset grows with the viewport, so the body's left edge walked
 * from 41.3 at 992 to 66.7 at 1600 while the legend's inset stayed fixed -- two left edges that
 * agreed at no width.
 *
 * Assumptions: the bound is asserted to be the design system's LARGE SCREEN token, read from the
 * theme register rather than written out here, so a case cannot pass against an invented breakpoint.
 * The token resolves to a number and React appends the unit, which is why the expected value is
 * composed rather than compared as a raw token reference.
 * @returns {Promise<void>} Resolves once the bound and the centring have been inspected.
 */
async function boundsAndCentresTheBodyColumn(): Promise<void> {
  await signOn();
  render(renderShell(<AdvisoryScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const body = screen.getByText(SCREEN_BODY);
  const column = body.closest('.ant-col');
  expect(column).not.toBeNull();

  /*
   * Assumptions: the probe is rendered in its own tree rather than inside the frame, because the
   * value it reports is a property of the theme rather than of this frame - and mounting it under
   * the outlet would add an element to the very DOM the surrounding cases inspect.
   */
  const { unmount } = render(<BoundProbe />);
  const bound = screen.getByTestId(BOUND_PROBE_TEST_ID).style.maxInlineSize;
  unmount();

  expect(bound).not.toBe('');
  expect((column as HTMLElement).style.maxInlineSize).toBe(bound);
  expect((column as HTMLElement).style.inlineSize).toBe('100%');

  /*
   * Assumptions: the centring is asserted through the ROW's own class rather than through a style,
   * because the row does the centring with the design system's own `justify` prop -- so a raw
   * element carrying layout CSS would be the regression, and the class is what proves it is not.
   */
  const row = column?.closest('.ant-row');
  expect(row).not.toBeNull();
  expect(row?.className).toContain('ant-row-center');
}

/**
 * Asserts the row-22 advisory line is painted by the frame, above the row-23 line.
 *
 * Purpose: the frame offered ONE message channel and the reference declares two, so the five screens
 * whose mapset carries `INFOMSG` had nowhere to put it and rendered a second band inside their own
 * body. That was measured: on `/account/update` and `/reference/transaction-types/:cd` the frame's
 * row-23 band stood empty at its reserved height while the screen's real advisory painted roughly
 * 200 pixels higher up the page, so an operator read two message zones that the terminal showed as
 * two adjacent rows. `app/bms/COACTVW.bms` L356-L368 declares `INFOMSG` at `POS=(22,23)` immediately
 * above `ERRMSG` at `POS=(23,1)`.
 *
 * Assumptions: the ORDER is asserted and not merely the presence of both, because a channel painted
 * below the outcome it advises about would reproduce the same reading-order defect in one zone
 * instead of two.
 * @returns {Promise<void>} Resolves once both lines and their order have been observed.
 */
async function paintsBothMessageChannelsInMapsetOrder(): Promise<void> {
  await signOn();
  render(renderShell(<AdvisoryScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const advisory = screen.getByTestId(INFORMATION_BAND_TEST_ID);
  const outcome = screen.getByTestId(MESSAGE_BAND_TEST_ID);

  expect(screen.getByText(ADVISORY_TEXT)).toBeInTheDocument();
  expect(screen.getByText(OUTCOME_TEXT)).toBeInTheDocument();

  /*
   * Assumptions: document order is read with `compareDocumentPosition` rather than by comparing
   * rendered coordinates, because jsdom lays nothing out -- and document order is the property that
   * governs both the visual order of two block siblings and the order they are announced in.
   */
  expect(advisory.compareDocumentPosition(outcome)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
}

/** Handle on the control that advances the advisory screen from one row-22 sentence to the next. */
const ADVISORY_ADVANCE_TEST_ID = 'advisory-advance';

/**
 * The two successive row-22 sentences a real write produces, in the order the program moves them.
 *
 * ⚠️ Assumptions: both are transcribed from the catalog rather than invented, and they are the
 * genuine consecutive pair — `app/cbl/COACTUPC.cbl` moves `Changes validated.Press F5 to save` into
 * `WS-INFO-MSG` at L473 and `Changes committed to database` into the same field at L475, so the
 * second replaces the first across one turn. That is what makes them the right fixture: the
 * comparison this case is about is only reached when the row-23 line is UNCHANGED, and this is the
 * sequence where that happens on the most consequential line in the application.
 */
const ADVISORY_SEQUENCE: readonly [string, string] = [
  STATUS_MESSAGES.COACTUPC.PROMPT_FOR_CONFIRMATION.text,
  STATUS_MESSAGES.COACTUPC.CONFIRM_UPDATE_SUCCESS.text,
];

/** Severity the advisory rests at before it is raised, named so the two cases cannot drift. */
const RESTING_SEVERITY = 'neutral' as const;

/**
 * Severity the advisory is raised to, which changes the band's variant, colour and severity icon.
 *
 * ⚠️ Assumptions: `success` and not `info`, and the reason is mechanical rather than aesthetic:
 * `ui/src/layout/MessageBand.tsx` maps BOTH `neutral` and `info` to the design system's
 * informational variant and to the polite live-region role, so a `neutral` to `info` pairing changes
 * the published slot and nothing observable in the DOM — a case built on it could not tell an adopted
 * publication from a discarded one. `success` resolves to its own variant and its own icon, so the
 * adoption is visible.
 *
 * Assumptions: the pairing is a real one rather than a contrivance. The row-22 field carries both
 * standing prompts and successful outcomes in the reference — `app/cbl/COACTUPC.cbl` moves
 * `Enter or update id of account to update` and `Changes committed to database` into the same
 * `WS-INFO-MSG` — so a screen raising that channel's severity while the sentence stands is a shape
 * the field itself has.
 */
const ADVANCED_SEVERITY = 'success' as const;

/** Which single row-22 change the advancing screen makes when its control is pressed. */
interface AdvisoryAdvancingScreenProps {
  /**
   * `'text'` advances the sentence, `'severity'` keeps the sentence and raises its severity, and
   * `'withdrawal'` publishes no advisory at all where one stood.
   */
  readonly advancing: 'text' | 'severity' | 'withdrawal';
}

/**
 * The row-22 advisory one probe state publishes, or nothing when that state withdraws it.
 *
 * ⚠️ Assumptions: exactly ONE member differs between a probe's two states, and which one is this
 * function's whole purpose. The comparison these cases exercise is a conjunction over the nested
 * members plus an absent-versus-present test at its head, so a state change that moved two members
 * at once would be adopted on either one alone and would leave the other untested — which is the gap
 * a single probe advancing text and severity together would silently carry.
 *
 * Assumptions: the resting sentence is the same in all three modes, so the withdrawal case starts
 * from the state the other two start from. That makes the three cases a partition of the transitions
 * the channel has rather than three unrelated fixtures.
 * @param {AdvisoryAdvancingScreenProps['advancing']} advancing - Which change this probe makes.
 * @param {boolean} advanced - Whether the probe's control has been pressed yet.
 * @returns {ShellInformationSlot | undefined} The advisory to publish, or `undefined` to publish none.
 */
function advisoryFor(
  advancing: AdvisoryAdvancingScreenProps['advancing'],
  advanced: boolean,
): ShellInformationSlot | undefined {
  if (advancing === 'text') {
    return { text: advanced ? ADVISORY_SEQUENCE[1] : ADVISORY_SEQUENCE[0] };
  }
  if (advancing === 'severity') {
    return {
      text: ADVISORY_SEQUENCE[0],
      severity: advanced ? ADVANCED_SEVERITY : RESTING_SEVERITY,
    };
  }
  return advanced ? undefined : { text: ADVISORY_SEQUENCE[0] };
}

/**
 * A nested screen that advances ONLY its row-22 advisory, leaving its row-23 line as it was.
 *
 * Purpose
 * -------
 * This is the exact shape the shell's message comparison used to discard. The comparison decides
 * whether a newly published slot is adopted, and while it read only the row-23 members a screen that
 * changed nothing else could not reach the frame at all — so the operator went on reading the
 * previous advisory. A screen that changed both lines would hide the defect, because the row-23
 * change alone would carry the row-22 one into the frame with it.
 *
 * Assumptions: the row-23 line is held CONSTANT rather than omitted. Omitting it would leave the
 * comparison with two absent outer texts, which is also equal, but it would no longer be the measured
 * situation — the account-update screen paints an outcome and an advisory together, and the point is
 * that one advances while the other does not.
 * @param {object} props0 - The nested screen's props.
 * @param {AdvisoryAdvancingScreenProps['advancing']} props0.advancing - Which member of the advisory
 *   this screen advances when its control is pressed, so one case can cover the text changing and
 *   another the severity changing without a second component.
 * @returns {ReactElement} The nested screen's body, with the control that advances the advisory.
 */
function AdvisoryAdvancingScreen({ advancing }: AdvisoryAdvancingScreenProps): ReactElement {
  const [advanced, setAdvanced] = useState(false);

  /**
   * Moves the screen to the second state of whichever row-22 member it is advancing.
   * @returns {void} Nothing; the state update is the observable effect.
   */
  function advanceAdvisory(): void {
    setAdvanced(true);
  }

  /*
   * WHY : Assumptions: exactly ONE member differs between the two states, and which one is the
   *       prop's whole purpose. The comparison this screen exists to exercise is a conjunction, so a
   *       state change that moved both members would be adopted on either conjunct alone and would
   *       leave the other untested -- which is the gap a probe advancing text and severity together
   *       would silently carry.
   */
  const information = advisoryFor(advancing, advanced);

  /*
   * WHY : Assumptions: the nested member is spread conditionally rather than assigned as
   *       `information: undefined`, because `ui/tsconfig.json` sets `exactOptionalPropertyTypes` and
   *       under it an explicitly undefined member is a DIFFERENT value from an omitted one. The
   *       frame's comparison reads exactly that difference at its head, so the withdrawal case has to
   *       publish a genuinely absent member to be the situation it claims to be.
   */
  useShellSlot({
    screen: IDENTITY,
    message: {
      text: OUTCOME_TEXT,
      severity: 'error',
      mapset: 'COACTVW',
      ...(information === undefined ? {} : { information }),
    },
    now: PAINTED_AT,
  });

  return (
    <div>
      <button data-testid={ADVISORY_ADVANCE_TEST_ID} onClick={advanceAdvisory} type="button">
        {SCREEN_BODY}
      </button>
    </div>
  );
}

/**
 * Asserts a NEW row-22 advisory replaces the rendered one even when row 23 has not changed.
 *
 * ⚠️ Purpose: this is the case that fails if the shell's message comparison omits the nested row-22
 * members. The absence of the superseded sentence is asserted as well as the presence of the new one,
 * because a frame that painted both would be a different defect with the same first half — and it is
 * the STALE line that an operator misreads, not the missing one.
 *
 * Assumptions: the row-23 line is asserted unchanged at the end, which is what proves the case
 * reached the comparison rather than sidestepping it. If the outcome had changed too, the adoption
 * would be explained by that change and the row-22 comparison would still be untested.
 * @returns {Promise<void>} Resolves once the advisory has been observed to advance.
 */
async function replacesTheAdvisoryLineWhenOnlyTheAdvisoryChanges(): Promise<void> {
  await signOn();
  const user = userEvent.setup();
  render(renderShell(<AdvisoryAdvancingScreen advancing="text" />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(ADVISORY_SEQUENCE[0]);

  await user.click(screen.getByTestId(ADVISORY_ADVANCE_TEST_ID));

  await waitFor(
    /**
     * Waits for the frame to adopt the newly published advisory.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(ADVISORY_SEQUENCE[1]);
    },
  );

  expect(screen.queryByText(ADVISORY_SEQUENCE[0])).toBeNull();
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(OUTCOME_TEXT);
}

/**
 * The four classes the design system's alert uses to name the severity it was given.
 *
 * ⚠️ Assumptions: the set is enumerated rather than matched by prefix, because the same element also
 * carries a STYLE variant from the same `ant-alert-` prefix — the first run of this case reported
 * `ant-alert-info ant-alert-outlined` — and `outlined` names how the alert is drawn rather than what
 * it is saying. Enumerating the four severity names is what makes the reading unambiguous, and it
 * also means a future package version that adds another style variant cannot silently widen it.
 */
const ADVISORY_VARIANT_CLASSES: readonly string[] = [
  'ant-alert-error',
  'ant-alert-info',
  'ant-alert-success',
  'ant-alert-warning',
];

/**
 * The design system's severity class on one band, which is the observable its severity resolves to.
 *
 * Assumptions: the class is read off the band element or off the alert within it, because which of
 * the two carries it is the band module's business and not this case's. Searching both is what keeps
 * the assertion about the severity rather than about the band's internal element nesting.
 * @param {HTMLElement} band - The rendered band, located by its channel's test identifier.
 * @returns {string} The single severity class found, or the empty string when the band carries none.
 * @throws {Error} When the band carries more than one severity class, which would make the answer
 * ambiguous rather than merely absent.
 */
function advisoryVariantClass(band: HTMLElement): string {
  const alert = band.classList.contains('ant-alert') ? band : band.querySelector('.ant-alert');
  const found = Array.from(alert?.classList ?? []).filter(
    /**
     * Keeps the classes that name a severity.
     * @param {string} name - One class on the alert element.
     * @returns {boolean} `true` for a severity class and `false` for every other class.
     */
    (name: string): boolean => ADVISORY_VARIANT_CLASSES.includes(name),
  );

  if (found.length > 1) {
    throw new Error(`band carries ${String(found.length)} severity classes: ${found.join(' ')}`);
  }

  return found[0] ?? '';
}

/**
 * Asserts a raised row-22 SEVERITY reaches the frame even when the sentence itself is unchanged.
 *
 * ⚠️ Purpose: severity is the second of the two nested members the frame's message comparison reads,
 * and it needs its own case for a mechanical reason — the comparison is a conjunction, so a case that
 * advanced the sentence would be satisfied by the text conjunct alone and would stay green with the
 * severity conjunct removed. This one holds the sentence and the row-23 line constant so that the
 * severity conjunct is the only thing that can carry the publication through.
 *
 * ⚠️ Assumptions: severity is worth comparing rather than tolerable to miss, because
 * `ui/src/layout/MessageBand.tsx` resolves the band's variant, its text colour, its severity icon and
 * its ARIA role from it — four channels, two of them non-visual — so a discarded severity change is a
 * line whose announced and painted character both stay behind what the screen published.
 *
 * Assumptions: the assertion is on the design system's own variant CLASS rather than on a colour,
 * because every colour in this band resolves to a CSS custom property that jsdom does not compute,
 * while the class is a real attribute this runner can read. See {@link ADVANCED_SEVERITY} for why
 * this particular pairing is the one that produces an observable difference at all.
 * @returns {Promise<void>} Resolves once the raised severity has been observed on the band.
 */
async function raisesTheAdvisorySeverityWhenOnlyTheSeverityChanges(): Promise<void> {
  await signOn();
  const user = userEvent.setup();
  render(renderShell(<AdvisoryAdvancingScreen advancing="severity" />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const resting = screen.getByTestId(INFORMATION_BAND_TEST_ID);

  expect(resting).toHaveTextContent(ADVISORY_SEQUENCE[0]);
  expect(advisoryVariantClass(resting)).toBe('ant-alert-info');

  await user.click(screen.getByTestId(ADVISORY_ADVANCE_TEST_ID));

  await waitFor(
    /**
     * Waits for the frame to adopt the raised severity.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(advisoryVariantClass(screen.getByTestId(INFORMATION_BAND_TEST_ID))).toBe(
        'ant-alert-success',
      );
    },
  );

  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(ADVISORY_SEQUENCE[0]);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(OUTCOME_TEXT);
}

/**
 * Asserts a WITHDRAWN row-22 advisory leaves the frame, when row 23 is unchanged behind it.
 *
 * ⚠️ Purpose: this is the third and last transition the channel has, and it is the worst of the three
 * to get wrong. The other two leave a wrong sentence on the line; this one leaves a sentence that has
 * been retracted, so an operator reads `Changes validated.Press F5 to save` on a screen that is no
 * longer offering to save. It is also the transition the other two cannot cover: they both publish an
 * advisory on each side, so both are settled by the member-by-member conjuncts, while this one is
 * settled by the absent-versus-present test at the comparison's head — a different branch of the same
 * function.
 *
 * Assumptions: the whole band is asserted gone rather than merely its text, because the channel's
 * absence is meant to remove the line and not to paint an empty one. `MessageBand` decides that, and
 * a case asserting only the text would pass against a band that still reserved a blank row on a
 * 24-row frame where every row is accounted for.
 *
 * Assumptions: the row-23 line is asserted still painted afterwards, which is what proves the
 * withdrawal was scoped to the row it names. A change that discarded the whole message slot would
 * satisfy the first half of this case and fail here.
 * @returns {Promise<void>} Resolves once the advisory has been observed to leave the frame.
 */
async function withdrawsTheAdvisoryLineWhenTheScreenPublishesNone(): Promise<void> {
  await signOn();
  const user = userEvent.setup();
  render(renderShell(<AdvisoryAdvancingScreen advancing="withdrawal" />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toHaveTextContent(ADVISORY_SEQUENCE[0]);

  await user.click(screen.getByTestId(ADVISORY_ADVANCE_TEST_ID));

  await waitFor(
    /**
     * Waits for the frame to drop the retracted advisory.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.queryByTestId(INFORMATION_BAND_TEST_ID)).toBeNull();
    },
  );

  expect(screen.queryByText(ADVISORY_SEQUENCE[0])).toBeNull();
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(OUTCOME_TEXT);
}

/** Row-24 text of the key the turn-taking screen delegates, from `app/bms/COACTUP.bms:L498`. */
const TURN_KEY_LABEL = 'F5=Save';

/**
 * A nested screen that marks ONE delegated key busy once that key has been activated.
 *
 * Purpose
 * -------
 * The subject is the delegation path, not the bar. `ui/src/layout/PfKeyBar.tsx` paints the in-flight
 * affordance from a descriptor member, and a unit test of the bar proves it does; what a unit test
 * cannot show is whether a CHANGE to that member ever reaches the bar when the descriptor arrives
 * through the shell. It does not arrive by prop: the shell holds the last published slot and
 * re-renders only when a comparison says the new one differs, so a member the comparison ignores is
 * a member whose change is silently discarded — and eighteen of the 21 screens publish their legend
 * this way, so the affordance would be invisible on all of them while the bar's own tests passed.
 *
 * Assumptions: the key is PF5 with a mutating classification, because that is the shape the writing
 * screens have — `app/bms/COACTUP.bms:L498` paints `F5=Save` — and because it exercises the risk
 * member on the same path as the busy member, so one case proves both survive delegation.
 * @returns {ReactElement} The nested screen's own body.
 */
function TurnTakingScreen(): ReactElement {
  const [inFlight, setInFlight] = useState(false);

  /**
   * Marks the writing key's own turn as started.
   * @param {CicsAid} aid - The AID the shell's dispatcher forwarded.
   * @returns {void} Nothing; the state update is the observable effect.
   */
  function startTurn(aid: CicsAid): void {
    if (aid === 'PFK05') {
      setInFlight(true);
    }
  }

  useShellSlot({
    screen: IDENTITY,
    pfKeys: {
      keys: [
        {
          aid: 'PFK05',
          action: 'save',
          busy: inFlight,
          enabled: true,
          label: TURN_KEY_LABEL,
          risk: 'mutating',
        },
      ],
      onInvoke: startTurn,
    },
    now: PAINTED_AT,
  });
  return <div>{SCREEN_BODY}</div>;
}

/**
 * Asserts a busy change on a DELEGATED legend reaches the rendered footer control.
 *
 * ⚠️ Purpose: this is the case that fails if the shell's binding comparison omits a member the bar
 * renders. The comparison exists so that a screen re-rendering for its own reasons does not force the
 * frame to re-render with it, and it therefore has to enumerate every member that is visible — a
 * member left out of it is not merely compared loosely, it is discarded, because the shell keeps the
 * snapshot it already holds and the screen has no other way to reach the footer.
 *
 * Assumptions: the emphasis is asserted on the same control in the same render, so the case also
 * proves the risk member survives delegation. The two members were added together and travel the
 * same path, and asserting only one would leave the other's transport unproven.
 *
 * Assumptions: the control is located inside the legend landmark rather than by name alone, because
 * this screen publishes its legend through the frame and a control found anywhere else would mean the
 * screen had composed one of its own — which is the defect the delegation contract exists to prevent.
 * @returns {Promise<void>} Resolves once the idle and in-flight footer states have been observed.
 */
async function repaintsADelegatedLegendWhenAKeyReportsItsTurnInFlight(): Promise<void> {
  await signOn();
  const user = userEvent.setup();
  render(renderShell(<TurnTakingScreen />));
  expect(await screen.findByText(SCREEN_BODY)).toBeInTheDocument();

  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const saveKey = within(legend).getByRole('button', { name: TURN_KEY_LABEL });

  expect(saveKey).toHaveClass('ant-btn-primary');
  expect(saveKey).not.toHaveClass('ant-btn-dangerous');
  expect(saveKey).toHaveAttribute('aria-busy', 'false');

  await user.click(saveKey);

  await waitFor(
    /**
     * Waits for the frame to repaint from the screen's re-publication.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(saveKey).toHaveAttribute('aria-busy', 'true');
    },
  );

  expect(saveKey).toHaveClass('ant-btn-loading');
  expect(saveKey).toBeEnabled();
}

/**
 * The brand heading is painted even when no screen delegates an identity.
 *
 * Purpose: pin the rank-one heading to the FRAME rather than to a screen publication. A browser
 * measurement of the not-found surface, which delegates nothing by design, read its outline as
 * `['H4:Screen not available']` -- a document whose highest heading was rank four.
 * @returns {void} Nothing; the assertions carry the result.
 *
 * Assumptions: the transaction identity stays withheld, and that is asserted alongside rather than
 * assumed. The two are separable -- the brand names the application, the status line names the
 * reference program -- and a fix that painted both would have given a mistyped address a
 * transaction identifier no program in `app/**` paints for it.
 */
function paintsTheBrandHeadingWithoutAScreenIdentity(): void {
  render(renderShell(<SelfComposingScreen />));

  expect(
    screen.getByRole('heading', { level: APP_TITLE_HEADING_LEVEL, name: APP_TITLE_DISPLAY }),
  ).toBeInTheDocument();
  expect(screen.queryByText(HEADER_PROMPT_LABELS.transaction)).toBeNull();
  expect(screen.queryByText(HEADER_PROMPT_LABELS.program)).toBeNull();
}

/** Registers the shell cases. */
function appShellCases(): void {
  beforeEach(clearSession);
  beforeEach(armTransport);
  afterEach(clearSession);

  it('renders a nested screen through the outlet', rendersANestedScreenThroughTheOutlet);
  it(
    'paints the brand heading without a screen identity',
    paintsTheBrandHeadingWithoutAScreenIdentity,
  );
  it(
    'paints no zone for a screen that delegates nothing',
    paintsNoZoneForAScreenThatDelegatesNothing,
  );
  it('paints every delegated zone', paintsEveryDelegatedZone);
  it('forwards a delegated key activation', forwardsADelegatedKeyActivation);
  it('offers a skip link to a focusable content region', offersASkipLinkToAFocusableContentRegion);
  it(
    'offers an administrator a crossing to the main menu',
    offersAnAdministratorACrossingToTheMainMenu,
  );
  it('offers an ordinary operator no crossing', offersAnOrdinaryOperatorNoCrossing);
  it('signs off from the rendered control', signsOffFromTheRenderedControl);
  it(
    'leaves the signed-off surface from its own control',
    leavesTheSignedOffSurfaceFromItsOwnControl,
  );
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
  it(
    'pins the message line and the legend to the viewport bottom',
    pinsTheMessageLineAndTheLegendToTheViewportBottom,
  );
  it('gives every zone one horizontal inset', givesEveryZoneOneHorizontalInset);
  it('bounds and centres the body column', boundsAndCentresTheBodyColumn);
  it('paints both message channels in mapset order', paintsBothMessageChannelsInMapsetOrder);
  it(
    'repaints a delegated legend when a key reports its turn in flight',
    repaintsADelegatedLegendWhenAKeyReportsItsTurnInFlight,
  );
  it(
    'replaces the advisory line when only the advisory changes',
    replacesTheAdvisoryLineWhenOnlyTheAdvisoryChanges,
  );
  it(
    'raises the advisory severity when only the severity changes',
    raisesTheAdvisorySeverityWhenOnlyTheSeverityChanges,
  );
  it(
    'withdraws the advisory line when the screen publishes none',
    withdrawsTheAdvisoryLineWhenTheScreenPublishesNone,
  );
}

describe('application shell', appShellCases);
