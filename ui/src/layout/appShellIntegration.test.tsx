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
import { isValidElement } from 'react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MemoryRouter } from 'react-router';
import type { RouteObject } from 'react-router';

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
import { CARD_DEMO_ROUTES } from '../router';
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
 * WHY : Assumptions: a session is established through the exchange, never by writing a storage key.
 *       `ui/src/hooks/useAuth.ts` reads no key at all -- the session is module state and the bearer is
 *       module state behind a setter, so nothing script-readable retains a credential. A case that
 *       wrote `carddemo.id-token` would establish nothing and would render the ANONYMOUS frame, so the
 *       sign-off case would fail and the key-dispatch case would pass for the wrong reason: the
 *       screen's own handler is installed either way.
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
 * Mounts the shell around a child inside a router, which is where the shell always runs.
 *
 * Assumptions: a router is required around the shell, because the shell reads the current history
 * entry so that it can clear the sign-off surface when the operator navigates away from the entry they
 * signed off at. A surface that never cleared would leave every route beneath this layout unrendered,
 * the sign-on route included. Wrapping here rather than making the routing read optional keeps one code
 * path in the shell: `ui/src/router.tsx` mounts it as a layout route, so a router is present in the
 * application by construction, and the `children` form these cases use is a test affordance rather than
 * a second deployment shape.
 *
 * Assumptions: `MemoryRouter` rather than `BrowserRouter`, because the cases assert nothing about the
 * address and a memory history needs no `jsdom` navigation. One entry is supplied explicitly so the
 * entry key these cases run under is the router's initial one.
 * @param {ReactElement} child - The screen to mount inside the frame.
 * @returns {ReactElement} The child inside the shell inside a router.
 */
function framed(child: ReactElement): ReactElement {
  return (
    <MemoryRouter initialEntries={['/framed']}>
      <AppShell>{child}</AppShell>
    </MemoryRouter>
  );
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

  render(framed(<PublishingScreen onKey={onKey} />));

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
  render(framed(<SilentScreen />));

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

  render(framed(<PublishingScreen onKey={onKey} />));

  await user.keyboard('{F12}');

  expect(onKey).toHaveBeenCalledTimes(1);
  expect(screen.queryByTestId(SHELL_SIGN_OFF_TEST_ID)).not.toBeInTheDocument();
  expect(legendControlNames()).toStrictEqual([PUBLISHED_LEGEND]);
}

/**
 * The shell offers sign-off as a rendered control and claims no function key for it.
 *
 * WHY : Assumptions: the shell claims NO function key, so the two properties asserted here are that
 *       the legend region is absent for a screen delegating no keys -- the shell contributes no entry
 *       to it -- and that the rendered control ends the session. `SHELL_SIGN_OFF_LABEL` records why a
 *       key of the shell's own was rejected: its stand-down condition, "no screen has delegated a
 *       legend", is true of no delivered screen, so such a key would be unreachable in the application
 *       and reachable only from a test.
 * WHY : Assumptions: the F12 press is still performed, and it is asserted to do NOTHING. That is the
 *       half that could regress silently -- a shell that re-installed a document listener would end the
 *       session from a key the screen beneath it may bind, which is the collision the design removed.
 * @returns {Promise<void>} Resolves once the session has been ended from the control.
 */
async function offersSignOffOnlyWhenUnclaimed(): Promise<void> {
  await signOn();
  const user = userEvent.setup();

  render(framed(<SilentScreen />));

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
 * WHY : Assumptions: mounting the shell ABOVE the screens puts a publisher inside its own subscriber,
 * which is the one shape that could turn a per-render value into an unbounded loop - publish, notify,
 * shell re-renders, screen re-renders beneath it, reads a later instant, publish again. It does not loop,
 * and the reason is precise rather than incidental: the child element is built once and not rebuilt per
 * shell render -- in production it is a route element created at module scope in `ui/src/router.tsx`, and
 * in this case it is the element the harness passes as `children` -- so when the shell re-renders React
 * sees a referentially identical element with identical props and bails out of that subtree entirely.
 * That bailout is load bearing and invisible in the source, so this case pins it. A change that rebuilt
 * the child's props on each shell render - cloning the element, or spreading it into a new one to add a
 * prop - would break the bailout, and the failure would appear as a hung browser rather than as a wrong
 * value.
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

  render(framed(<ClockPublishingScreen />));

  expect(screen.getByText('CLOCK BODY')).toBeInTheDocument();
  expect(screen.getByText(PUBLISHED_TRANSACTION_ID)).toBeInTheDocument();
  expect(clockScreenRenders).toBeLessThanOrEqual(6);
}

/**
 * One census of the shell mounts in a route subtree: how many there are, and how many are nested.
 *
 * Assumptions: the nested count is carried separately from the total because the two failures mean
 * opposite things. Two mounts are CORRECT when they are siblings -- one frame per access branch -- and
 * are the double-frame defect when one is inside the other, so a bare count cannot tell them apart.
 */
interface ShellMountCensus {
  /** Shell mounts found anywhere in the subtree. */
  readonly mounts: number;

  /** Shell mounts that have another shell mount as an ancestor. */
  readonly nested: number;
}

/**
 * Counts the shell mounts in a route subtree and how many of them sit inside another one.
 * @param {readonly RouteObject[]} routes - Route objects to walk.
 * @param {boolean} hasShellAncestor - Whether an ancestor of these routes already mounts the shell.
 * @returns {ShellMountCensus} The mount count and the nested-mount count for this subtree.
 */
function countShellMounts(
  routes: readonly RouteObject[],
  hasShellAncestor: boolean,
): ShellMountCensus {
  let mounts = 0;
  let nested = 0;

  for (const route of routes) {
    const mountsShell = isValidElement(route.element) && route.element.type === AppShell;
    if (mountsShell) {
      mounts += 1;
      if (hasShellAncestor) {
        nested += 1;
      }
    }

    const below = countShellMounts(route.children ?? [], hasShellAncestor || mountsShell);
    mounts += below.mounts;
    nested += below.nested;
  }

  return { mounts, nested };
}

/**
 * The application mounts one shell per access branch, and never one inside another.
 *
 * WHY : Assumptions: the composition is read from the SOURCE rather than rendered. Rendering `App`
 * mounts the whole lazy route tree and the runtime configuration reader with it, so the case would
 * depend on a dozen stubs and its failure would not name this property. The mount count and the
 * provider are syntactic facts about two files, so the two files are read.
 *
 * WHY : ⚠️ Refactoring Rationale: the count required exactly ONE mount and required `App.tsx` to
 * render `<CardDemoRouter />`. Both expectations are replaced rather than relaxed. The route table now
 * mounts the shell TWICE on purpose -- once for the public sign-on branch, once for the guarded
 * subtree -- because sign-on must be framed (it delegates its title band, its row-23 message and its
 * row-24 legend) while sitting outside the guard, and a sibling branch is what gives it a frame
 * without putting anything above it that could demand a credential. And `App.tsx` renders a
 * `RouterProvider` over a router object, which is the composition the frozen route specification
 * names; requiring the old component name here is what previously argued that file into keeping the
 * element form.
 *
 * WHY : Assumptions: what this case must still make impossible is the defect it was written for -- a
 * shell inside a shell, which framed every guarded screen twice with two banners, two contentinfo
 * landmarks and both copies reading the one publication a screen makes. Two mounts are safe if and
 * only if they are SIBLINGS, so the count is asserted in the text and the nesting is asserted over the
 * exported route objects, where "inside" is a fact about the tree rather than about indentation.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function mountsOneShellPerAccessBranch(): void {
  const appSource = readFileSync(join(import.meta.dirname, '..', 'App.tsx'), 'utf8');
  const routerSource = readFileSync(join(import.meta.dirname, '..', 'router.tsx'), 'utf8');

  /*
   * WHY : Assumptions: the router's mounts are matched as `element: <AppShell />` -- the self-closing,
   *       outlet form in an `element` position -- while `App.tsx` is checked for `<AppShell` in ANY
   *       form. That asymmetry is deliberate: the children form is the shape that silently disables the
   *       outlet, so the file that must not mount the shell is checked for both spellings while the
   *       file that must is checked for the one spelling that works as a layout route.
   */
  const routerMounts = routerSource.match(/element: <AppShell \/>/gu) ?? [];
  expect(routerMounts, 'router.tsx must mount AppShell once per access branch').toHaveLength(2);
  expect(appSource, 'App.tsx must not mount a shell').not.toContain('<AppShell');
  /*
   * Assumptions: the provider is required exactly once. A second one over the same router object would
   * subscribe twice to one history and render the matched route twice, which is this defect's
   * composition-level form.
   */
  const providerMounts = appSource.match(/<RouterProvider/gu) ?? [];
  expect(providerMounts, 'App.tsx must render exactly one RouterProvider').toHaveLength(1);
  // Assumptions: the replaced frame is asserted ABSENT as well. `App.tsx` previously composed its own
  //   `Layout.Header` and `Layout.Footer`, and leaving either in place beside the shell would put two
  //   banners and two contentinfo landmarks in one document.
  expect(appSource, 'App.tsx must not compose a second frame').not.toContain('<Layout.Header>');
  expect(appSource, 'App.tsx must not compose a second frame').not.toContain('<Layout.Footer>');

  const census = countShellMounts(CARD_DEMO_ROUTES, false);
  expect(census.mounts, 'the route tree must mount one shell per access branch').toBe(2);
  expect(
    census.nested,
    'no shell mount may sit inside another, or both would frame one screen',
  ).toBe(0);
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
  render(framed(<PublishingScreen onKey={vi.fn()} />));

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
 * The frame exposes one of each landmark, and states none of them invalidly.
 *
 * Purpose: an accessibility audit reported a `<header role="region">` nested inside the design
 * system's own `<header>`. Per ARIA in HTML a `header` permits only `group`, `none`, `presentation`
 * and `doc-footnote`, so that combination is invalid and axe-core reports it - while the OUTCOME the
 * combination was reaching for, one region named by the application title, was correct and has to
 * survive the fix.
 *
 * Assumptions: the census and the prohibition are asserted together, because either alone admits the
 * other's defect. Dropping the role would satisfy a prohibition and lose the landmark on the three
 * screens that compose the band inside their own body; keeping the role satisfies the census and
 * leaves the invalid combination in place.
 *
 * Assumptions: the prohibition is written over every `<header>` in the document rather than over the
 * band alone, so it also catches the same combination arriving on a different header later.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function exposesOneOfEachLandmarkWithNoInvalidRole(): void {
  render(framed(<PublishingScreen onKey={vi.fn()} />));

  expect(screen.getAllByRole('banner'), 'the frame must expose one banner').toHaveLength(1);
  expect(screen.getAllByRole('main'), 'the frame must expose one content region').toHaveLength(1);
  expect(screen.getAllByRole('contentinfo'), 'the frame must expose one footer').toHaveLength(1);
  expect(
    screen.getAllByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL }),
    'the frame must expose one key legend',
  ).toHaveLength(1);
  expect(
    screen.getAllByRole('region', { name: APP_TITLE_DISPLAY }),
    'the title band must remain one region named by the application title',
  ).toHaveLength(1);

  for (const header of document.querySelectorAll('header')) {
    expect(
      header.getAttribute('role'),
      'a header element may not carry a role: the allowed set excludes every role this frame wants',
    ).toBeNull();
  }
}

/**
 * The skip link renders differently when hovered and when pressed.
 *
 * Purpose: an inline colour outranks a stylesheet rule, so the contrast-safe hue this frame declares
 * on the skip link also displaced the design system's hover and active colours - and the design system
 * ships no hover underline to fall back on. Measured consequence: on all fifteen rendered screens the
 * link's hover and active renderings were byte-identical to its resting one, so focus was the only
 * state a pointer user could perceive.
 *
 * Assumptions: the RESTING declaration is asserted to survive the fix, because the obvious remedy -
 * deleting the override so the component's own states return - drops the resting colour to the link
 * anchor, which measures 4.10:1 against the surface this frame paints where AA asks 4.5:1. Restoring
 * a state by breaking the contrast would be the same defect moved.
 * @returns {Promise<void>} Resolves once the three renderings have been compared.
 */
async function rendersTheSkipLinkDifferentlyWhenHoveredAndPressed(): Promise<void> {
  const user = userEvent.setup();
  render(framed(<SilentScreen />));

  const link = screen.getByRole('link', { name: SKIP_TO_CONTENT_LABEL });
  const resting = link.getAttribute('style') ?? '';

  expect(resting, 'the resting rendering must keep naming the text-grade colour').toContain(
    `--ant-${KEBAB_CASED_TEXT_TOKENS.BLUE}`,
  );

  await user.hover(link);
  const hovered = link.getAttribute('style') ?? '';
  expect(hovered, 'hover must be perceptible, not byte-identical to rest').not.toBe(resting);
  /*
   * Assumptions: the hover rendering is required to keep the SAME colour and change a non-colour
   * channel instead. Every lighter step of this ramp measures worse than the resting shade against the
   * surface the frame paints, so the design system's own hover direction is the one direction this
   * element cannot take, and an underline costs no contrast at all.
   */
  expect(hovered, 'hover must not trade contrast for perceptibility').toContain(
    `--ant-${KEBAB_CASED_TEXT_TOKENS.BLUE}`,
  );

  await user.pointer({ keys: '[MouseLeft>]', target: link });
  const pressed = link.getAttribute('style') ?? '';
  expect(pressed, 'the pressed rendering must differ from the hovered one').not.toBe(hovered);
  await user.pointer({ keys: '[/MouseLeft]', target: link });
}

/**
 * Registers the shell-integration cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function shellIntegrationCases(): void {
  it(
    'exposes one of each landmark with no invalid role',
    exposesOneOfEachLandmarkWithNoInvalidRole,
  );
  it(
    'renders the skip link differently when hovered and pressed',
    rendersTheSkipLinkDifferentlyWhenHoveredAndPressed,
  );
  it(
    'paints its own sentences through the text-grade map',
    paintsItsOwnSentencesThroughTheTextGradeMap,
  );
  it('is mounted once per access branch by the application', mountsOneShellPerAccessBranch);
  it('paints every zone a screen delegates', paintsEveryDelegatedZone);
  it('paints no zone that was not delegated', paintsNoZoneThatWasNotDelegated);
  it('dispatches one key press exactly once', dispatchesOneKeyPressOnce);
  it('offers sign-off only when no screen claims the keys', offersSignOffOnlyWhenUnclaimed);
  it('settles after a screen publishes a paint-time clock', settlesAfterPublishingAPaintTimeClock);
}

describe('AppShell integration', shellIntegrationCases);
