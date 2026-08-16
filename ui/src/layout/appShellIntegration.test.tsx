/**
 * @file Proves the delivered application frames every screen with exactly one shell, and that one key
 * press reaches exactly one handler.
 *
 * Purpose
 * -------
 * `ui/src/layout/screenHeaderClock.test.tsx` asserts the delegation at the SOURCE level - that each
 * screen calls `useShellSlot` and composes no chrome of its own. This file asserts the two properties
 * that only exist once something is rendered: that a delegated zone actually paints in the frame, and
 * that the shell's own function key stands down while a screen has published one.
 *
 * WHY this file exists — Refactoring Rationale: the shell shipped once with no consumers at all. It
 * was authored complete, with a publication store, a stand-down condition and a sign-off surface, and
 * nothing imported it: `ui/src/App.tsx` built a second frame from generic `Layout` primitives, and two
 * screens omitted their own title band on the stated ground that this shell supplied it, so on those
 * screens the band was absent from the rendered application. Mounting it is only half the fix. The
 * other half is the keyboard: `usePfKeys` installs a listener per call site on the shared document, so
 * a shell that bound its own `PFK12` beside a screen that binds `PFK12` as `cancel` would make one key
 * press both cancel an edit and end the session. The stand-down condition prevents that, and the case
 * below is what proves the condition holds rather than merely being written down.
 *
 * WHY : Assumptions: the cases drive the shell with a purpose-built publishing child rather than with a
 * real screen. A real screen needs its router, its authentication state and its API responses stubbed
 * to mount at all, so a failure would rarely be about the frame; a child that publishes a known slot
 * makes each assertion name exactly the property it tests. The three delivered screens' own composition
 * is covered end to end in `ui/src/screens/cardScreenShell.test.tsx`, which now renders them inside this
 * shell.
 *
 * WHY : Assumptions: every callback is a named declaration rather than an inline arrow, because
 * `ui/eslint.config.js` requires a documentation block on a function expression in any position.
 */

import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MESSAGE_BAND_TEST_ID } from './MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from './PfKeyBar';
import { usePfKeys } from './usePfKeys';
import type { CicsAid } from './usePfKeys';
import {
  APP_SHELL_TEST_ID,
  AppShell,
  SHELL_SIGN_OFF_CONTROL_TEST_ID,
  SHELL_SIGN_OFF_LABEL,
  SHELL_SIGN_OFF_TEST_ID,
  SKIP_TO_CONTENT_LABEL,
  useShellSlot,
} from './AppShell';
import { APP_ORGANISATION_TITLE_DISPLAY, APP_TITLE_DISPLAY } from '../messages/messages';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { endAnySession, establishSession } from '../test/sessionHarness';
import { BMS_TEXT_COLOR_TOKENS } from '../theme/tokens';

/**
 * The two text-grade token names this frame's own sentences must name, in the design system's
 * custom-property spelling.
 *
 * Assumptions: the spelling is derived from the token name rather than written twice, so a token
 * rename cannot leave this expectation pointing at a property nothing emits. The design system
 * publishes each alias as a kebab-cased custom property under one prefix.
 */
const KEBAB_CASED_TEXT_TOKENS = {
  BLUE: kebabCased(BMS_TEXT_COLOR_TOKENS.BLUE),
  YELLOW: kebabCased(BMS_TEXT_COLOR_TOKENS.YELLOW),
} as const;

/*
 * WHY : ⚠️ Refactoring Rationale: a `carddemo.id-token` key stood here and `ui/src/hooks/useAuth.ts`
 *       reads no key at all -- the session is module state and the bearer is module state behind a
 *       setter, so nothing script-readable retains a credential. Writing the key established nothing,
 *       so the two cases that needed a session rendered the ANONYMOUS frame: one asserted the sign-off
 *       surface and failed, and the other's key-dispatch assertion held only because the screen's own
 *       handler is installed either way. Sessions are now established through the exchange.
 */

/** Groups every session in this file carries; the frame's chrome does not vary by authority. */
const ORDINARY_GROUPS: readonly string[] = ['carddemo-user'];

/**
 * Converts a camel-cased token name to the custom-property spelling the design system emits.
 * @param {string} tokenName - An alias token name, camel-cased.
 * @returns {string} The same name kebab-cased, without the vendor prefix.
 */
function kebabCased(tokenName: string): string {
  // WHY : Assumptions: this walks the name rather than passing a replacer callback to `replace`.
  //       `ui/eslint.config.js` requires a documentation block on a function expression in ANY
  //       position, so an inline arrow here would need its own block for two characters of work.
  let spelling = '';
  for (const letter of tokenName) {
    const lowered = letter.toLowerCase();
    spelling += letter === lowered ? letter : `-${lowered}`;
  }
  return spelling;
}

/** Transaction identifier the publishing child delegates, distinct from any real screen's. */
const PUBLISHED_TRANSACTION_ID = 'ZZ01';

/** Program name the publishing child delegates. */
const PUBLISHED_PROGRAM_NAME = 'ZTESTPGM';

/** Sentence the publishing child delegates to the row-23 line. */
const PUBLISHED_MESSAGE = 'Looks Good.... so far';

/** Legend text the publishing child delegates for its own cancel key. */
const PUBLISHED_LEGEND = 'F12=Cancel';

/**
 * Establishes a real session so the frame renders its authenticated chrome.
 *
 * Assumptions: the exchange is driven through `ui/src/test/sessionHarness.ts` rather than reproduced
 * here, so the identity token's claim NAMES are declared in one place. A local builder is what put the
 * operator in a `sub` claim in a sibling file while the hook read `cognito:username`, which established
 * nothing and reported nothing.
 * @returns {Promise<void>} Resolves once the session is held.
 */
async function signOn(): Promise<void> {
  const { unmount } = await establishSession({ groups: ORDINARY_GROUPS });

  unmount();
}

/**
 * Arms the intercepting transport and clears any session, so no case inherits another's.
 * @returns {void} Nothing; the environment is reset in place.
 */
function resetSessionAndTransport(): void {
  endAnySession();
  removeApiHarness();
  installApiHarness();
}

beforeEach(resetSessionAndTransport);
afterEach(resetSessionAndTransport);

/**
 * A child that delegates all four zones and records every key it is asked to run.
 * @param {object} props - The component's inputs.
 * @param {(aid: CicsAid) => void} props.onKey - Called once per dispatched attention identifier.
 * @returns {ReactElement} A marker element; the zones are painted by the shell above.
 */
function PublishingScreen({ onKey }: { readonly onKey: (aid: CicsAid) => void }): ReactElement {
  const { bindings, invoke } = usePfKeys({
    PFK12: {
      /**
       * Records that this screen's own cancel key ran.
       * @returns {void} Nothing; the recording is the observable effect.
       */
      onInvoke: (): void => {
        onKey('PFK12');
      },
      label: PUBLISHED_LEGEND,
    },
  });

  useShellSlot({
    screen: { transactionId: PUBLISHED_TRANSACTION_ID, programName: PUBLISHED_PROGRAM_NAME },
    message: { text: PUBLISHED_MESSAGE, severity: 'info' },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  return <div>SCREEN BODY</div>;
}

/**
 * A child that delegates nothing, standing in for a screen with no chrome of its own.
 * @returns {ReactElement} A marker element.
 */
function SilentScreen(): ReactElement {
  return <div>SILENT BODY</div>;
}

/** Renders counted by {@link ClockPublishingScreen}, reset by each case that reads it. */
let clockScreenRenders = 0;

/**
 * A child that publishes a fresh paint-time instant on every render, as the real screens do.
 *
 * WHY : Assumptions: the instant is read during render from the wall clock, which is what
 * `ui/src/hooks/useServerInstant.ts` does once an anchor exists - it returns a new `Date` computed from
 * a monotonic reading, so consecutive renders answer different milliseconds. Reproducing that here is
 * the whole point of the case: the shell renders its publisher, so a slot comparison that treated a new
 * millisecond as a visible change would notify on every commit and re-render without end.
 * @returns {ReactElement} A marker element; the header is painted by the shell above.
 */
function ClockPublishingScreen(): ReactElement {
  clockScreenRenders += 1;

  useShellSlot({
    screen: { transactionId: PUBLISHED_TRANSACTION_ID, programName: PUBLISHED_PROGRAM_NAME },
    now: new Date(),
  });

  return <div>CLOCK BODY</div>;
}

/**
 * Reads the accessible names of the controls the delegated legend paints.
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
       * @returns {string} Its text content, trimmed.
       */
      (control: HTMLElement): string => (control.textContent ?? '').trim(),
    );
}

/**
 * The frame paints every zone a screen delegates to it.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function paintsEveryDelegatedZone(): void {
  const onKey = vi.fn();

  render(
    <AppShell>
      <PublishingScreen onKey={onKey} />
    </AppShell>,
  );

  expect(screen.getByTestId(APP_SHELL_TEST_ID)).toBeInTheDocument();
  expect(screen.getByText(PUBLISHED_TRANSACTION_ID)).toBeInTheDocument();
  expect(screen.getByText(PUBLISHED_PROGRAM_NAME)).toBeInTheDocument();
  expect(screen.getByText('SCREEN BODY')).toBeInTheDocument();
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(PUBLISHED_MESSAGE);
  expect(legendControlNames()).toStrictEqual([PUBLISHED_LEGEND]);
}

/**
 * The frame paints no zone a screen has not delegated.
 *
 * Assumptions: this is the property that let the shell be mounted above screens at all - a zone is
 * painted if and only if it was delegated, so a screen that publishes nothing is unaffected by the
 * frame rather than given a blank band and an empty legend region.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function paintsNoZoneThatWasNotDelegated(): void {
  render(
    <AppShell>
      <SilentScreen />
    </AppShell>,
  );

  expect(screen.getByText('SILENT BODY')).toBeInTheDocument();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_ID)).not.toBeInTheDocument();
  expect(
    screen.queryByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL }),
  ).not.toBeInTheDocument();
}

/**
 * One key press reaches the screen's handler exactly once, and never the shell's.
 *
 * Assumptions: `PFK12` is the identifier under test because it is the one both owners claim - the
 * account-update, card-update and user-update screens bind it as cancel or exit, and the shell binds
 * it as sign-off. A dispatch count of one is therefore the whole assertion, and the absence of the
 * sign-off surface is what proves WHICH owner ran.
 * @returns {Promise<void>} Resolves once the key press has been dispatched.
 */
async function dispatchesOneKeyPressOnce(): Promise<void> {
  await signOn();
  const onKey = vi.fn();
  const user = userEvent.setup();

  render(
    <AppShell>
      <PublishingScreen onKey={onKey} />
    </AppShell>,
  );

  await user.keyboard('{F12}');

  expect(onKey).toHaveBeenCalledTimes(1);
  expect(screen.queryByTestId(SHELL_SIGN_OFF_TEST_ID)).not.toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual([PUBLISHED_LEGEND]);
}

/**
 * The shell offers sign-off as a rendered control and claims no function key for it.
 *
 * WHY : ⚠️ Refactoring Rationale: this asserted that the shell PUBLISHED a `F12=Cancel`-shaped legend
 *       entry of its own when no screen had claimed the keys, and that pressing F12 then ended the
 *       session. The shell claims no key: `SHELL_SIGN_OFF_LABEL` records why, and the stand-down
 *       condition the key design needed -- "no screen has delegated a legend" -- is true of no delivered
 *       screen, so the key would have been unreachable in the application and reachable only here.
 *       Two properties of the surviving design are asserted in its place, and together they are strictly
 *       stronger than the pair they replace: the legend region is ABSENT for a screen that delegates no
 *       keys, so the shell contributes no entry to it; and the rendered control ends the session, which
 *       is the action the withdrawn key stood for.
 * WHY : Assumptions: the F12 press is still performed, and it is asserted to do NOTHING. That is the
 *       half that could regress silently -- a shell that re-installed a document listener would end the
 *       session from a key the screen beneath it may bind, which is the collision the design removed.
 * @returns {Promise<void>} Resolves once the session has been ended from the control.
 */
async function offersSignOffOnlyWhenUnclaimed(): Promise<void> {
  await signOn();
  const user = userEvent.setup();

  render(
    <AppShell>
      <SilentScreen />
    </AppShell>,
  );

  expect(
    screen.queryByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL }),
  ).not.toBeInTheDocument();
  expect(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID)).toHaveTextContent(
    SHELL_SIGN_OFF_LABEL,
  );

  await user.keyboard('{F12}');

  expect(screen.queryByTestId(SHELL_SIGN_OFF_TEST_ID)).not.toBeInTheDocument();
  expect(screen.getByText('SILENT BODY')).toBeInTheDocument();

  await user.click(screen.getByTestId(SHELL_SIGN_OFF_CONTROL_TEST_ID));

  expect(screen.getByTestId(SHELL_SIGN_OFF_TEST_ID)).toBeInTheDocument();
  expect(screen.queryByText('SILENT BODY')).not.toBeInTheDocument();
}

/**
 * A paint-time clock reading settles instead of re-rendering without end.
 *
 * WHY : Refactoring Rationale: mounting the shell ABOVE the screens puts a publisher inside its own
 * subscriber, which is the one shape that could turn a per-render value into an unbounded loop - publish,
 * notify, shell re-renders, screen re-renders beneath it, reads a later instant, publish again. It does
 * not loop today, and the reason is precise rather than incidental: `children` is an element built by
 * `ui/src/App.tsx`, which does not re-render, so when the shell re-renders React sees a referentially
 * identical element with identical props and bails out of that subtree entirely. That bailout is load
 * bearing and invisible in the source, so this case pins it. A change that rebuilt the child's props on
 * each shell render - cloning the element, or spreading it into a new one to add a prop - would break the
 * bailout, and the failure would appear as a hung browser rather than as a wrong value.
 *
 * Assumptions: the published instant is read from the wall clock on every render, which is what
 * `useServerInstant` does once anchored, so the value genuinely differs each pass. The bound is generous
 * rather than exact: React commits the initial render, the layout effect publishes and the shell reads
 * the new snapshot, so a handful of passes is normal, while unbounded growth exceeds any small bound at
 * once.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function settlesAfterPublishingAPaintTimeClock(): void {
  clockScreenRenders = 0;

  render(
    <AppShell>
      <ClockPublishingScreen />
    </AppShell>,
  );

  expect(screen.getByText('CLOCK BODY')).toBeInTheDocument();
  expect(screen.getByText(PUBLISHED_TRANSACTION_ID)).toBeInTheDocument();
  expect(clockScreenRenders).toBeLessThanOrEqual(6);
}

/**
 * The application mounts the shell exactly once, wrapping the route tree.
 *
 * WHY : Assumptions: read from the source rather than rendered. Rendering `App` mounts the whole lazy
 * route tree and the runtime configuration reader with it, so the case would depend on a dozen stubs
 * and its failure would not name this property. The property itself is syntactic - one mount site, and
 * the router inside it - so reading the module is both narrower and more direct.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function mountsTheShellExactlyOnce(): void {
  const appSource = readFileSync(join(import.meta.dirname, '..', 'App.tsx'), 'utf8');
  const routerSource = readFileSync(join(import.meta.dirname, '..', 'router.tsx'), 'utf8');

  /*
   * WHY : ⚠️ Refactoring Rationale: the single mount is asserted of the ROUTE TABLE, where this case
   *       asserted it of `App.tsx` and additionally asserted the exact text
   *       `<AppShell><CardDemoRouter /></AppShell>`. Both mounts existed at once, which is the state
   *       this case was written to make impossible and the one it could not see: it read one file, found
   *       its one mount, and said nothing about the layout route in the other. A shell given `children`
   *       never reaches its outlet, so the two nested mounts framed every guarded screen twice -- two
   *       `app-shell` regions, two banners, two contentinfo landmarks, and both reading the one
   *       publication a screen makes. The layout route is the mount that survives, for the reason
   *       `ui/src/router.tsx` records, so the count is taken there and `App.tsx` is asserted to mount
   *       none.
   * WHY : Assumptions: the router's mount is matched as `<AppShell />` -- the self-closing, outlet form
   *       -- and `App.tsx` is checked for `<AppShell` in ANY form. That asymmetry is deliberate: the
   *       children form is the shape that silently disables the outlet, so the file that must not mount
   *       the shell is checked for both spellings while the file that must is checked for the one
   *       spelling that works as a layout route.
   */
  const routerMounts = routerSource.match(/<AppShell \/>/gu) ?? [];
  expect(routerMounts, 'router.tsx must mount AppShell exactly once').toHaveLength(1);
  expect(routerSource, 'the shell must be a layout route, so it renders the outlet').toContain(
    '<Route element={<AppShell />}>',
  );
  expect(appSource, 'App.tsx must not mount a second shell').not.toContain('<AppShell');
  expect(appSource, 'App.tsx must render the route tree').toContain('<CardDemoRouter />');
  // Assumptions: the replaced frame is asserted ABSENT as well. `App.tsx` previously composed its own
  //   `Layout.Header` and `Layout.Footer`, and leaving either in place beside the shell would put two
  //   banners and two contentinfo landmarks in one document.
  expect(appSource, 'App.tsx must not compose a second frame').not.toContain('<Layout.Header>');
  expect(appSource, 'App.tsx must not compose a second frame').not.toContain('<Layout.Footer>');
}

/**
 * Every sentence the frame paints itself resolves its colour through the text-grade map.
 *
 * Purpose: the token-level cases in `ui/src/theme/textContrast.test.ts` prove each ROLE reaches WCAG
 * AA; this one proves the frame's own three sentences actually resolve through that map, which is
 * the half that was missing. The skip link and the two centre title lines each took their colour
 * from a design-system default instead — the link anchor at 4.10:1 and the warning anchor at 1.90:1
 * against the surface this frame paints — while every token-level case passed.
 *
 * Assumptions: the assertion is that each element names a custom property from the text-grade map,
 * not that it computes to a particular hexadecimal. The design system themes through CSS variables,
 * so the variable reference IS the binding under test; resolving it would test the browser's
 * cascade, which jsdom does not implement for custom properties anyway.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function paintsItsOwnSentencesThroughTheTextGradeMap(): void {
  render(
    <AppShell>
      <PublishingScreen onKey={vi.fn()} />
    </AppShell>,
  );

  const link = screen.getByRole('link', { name: SKIP_TO_CONTENT_LABEL });
  expect(link.getAttribute('style'), 'the skip link must name a text-grade colour').toContain(
    `--ant-${KEBAB_CASED_TEXT_TOKENS.BLUE}`,
  );

  for (const title of [APP_ORGANISATION_TITLE_DISPLAY, APP_TITLE_DISPLAY]) {
    expect(
      screen.getByText(title).getAttribute('style'),
      `the ${title} line must name a text-grade colour`,
    ).toContain(`--ant-${KEBAB_CASED_TEXT_TOKENS.YELLOW}`);
  }
}

/**
 * Registers the shell-integration cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function shellIntegrationCases(): void {
  it(
    'paints its own sentences through the text-grade map',
    paintsItsOwnSentencesThroughTheTextGradeMap,
  );
  it('is mounted exactly once by the application', mountsTheShellExactlyOnce);
  it('paints every zone a screen delegates', paintsEveryDelegatedZone);
  it('paints no zone that was not delegated', paintsNoZoneThatWasNotDelegated);
  it('dispatches one key press exactly once', dispatchesOneKeyPressOnce);
  it('offers sign-off only when no screen claims the keys', offersSignOffOnlyWhenUnclaimed);
  it('settles after a screen publishes a paint-time clock', settlesAfterPublishingAPaintTimeClock);
}

describe('AppShell integration', shellIntegrationCases);
