/**
 * @file The single persistent application shell - the browser replacement for the fixed
 * 24-by-80 terminal frame that every CardDemo mapset paints around its content.
 *
 * Purpose
 * -------
 * Compose the four zones that are shared by every migrated screen and owned by none of
 * them, in the one order the baseline always showed them: the title band, the screen
 * body, the message line and the function-key legend. It is a route element, so the body
 * zone is react-router's `Outlet` rather than a `children` prop, and the other three
 * zones are the three shared shell elements that AAP section 0.4.4 assigns here -
 * `ui/src/layout/ScreenHeader.tsx`, `ui/src/layout/MessageBand.tsx` and
 * `ui/src/layout/PfKeyBar.tsx` - with function-key semantics taken from
 * `ui/src/layout/usePfKeys.ts`.
 *
 * Provenance
 * ----------
 * The zone map below was measured across the whole reference baseline rather than read
 * off one mapset. `app/**` is reference-only and was never modified.
 *
 * | Baseline rows | Zone            | Region              | Component      |
 * | ------------- | --------------- | ------------------- | -------------- |
 * | 1-3           | title band      | `Layout.Header`     | `ScreenHeader` |
 * | 4-22          | screen body     | `Layout.Content`    | `Outlet`       |
 * | 23            | message line    | between the two     | `MessageBand`  |
 * | 24            | key legend      | `Layout.Footer`     | `PfKeyBar`     |
 *
 * Assumptions: the frame is uniform, and that is a measurement rather than an
 * inference. `SIZE=(24,80)` occurs on 17 of 17 base mapsets, a field at `POS=(23,`
 * occurs on 17 of 17, and a field at `POS=(24,` occurs on 17 of 17 - counted across
 * `app/bms/*.bms`. Because no mapset deviates, the frame is a property of the
 * application rather than of any screen, so it is authored once here. The widest single
 * example is `app/bms/COSGN00.bms`: header rows at L29-L93, the `ERRMSG` message field
 * at L197-L200 (`ATTRB=(ASKIP,BRT,FSET)`, `COLOR=RED`, `POS=(23,1)`) and the legend at
 * L201-L205 (`COLOR=YELLOW`, `POS=(24,1)`).
 *
 * Design gap G1: what is preserved and what is surrendered
 * -------------------------------------------------------
 * Alternatives Considered: reproducing the absolute character grid was evaluated first
 * and rejected. Every one of the 902 base fields carries an absolute `POS=(row,column)`
 * inside a `SIZE=(24,80)` cell matrix, so a literal translation is expressible - a
 * fixed 80-column monospaced canvas with each field placed at its measured cell. It
 * loses on two independent grounds. It is hostile to assistive technology, because
 * position, not markup, would carry the association between a prompt and its value, so
 * a screen reader would announce 24 rows of unrelated text. And it cannot reflow: a
 * viewport narrower than 80 characters can only clip or scale, and both destroy the
 * alignment that was the grid's whole justification.
 *
 * Trade-offs: what is surrendered is pixel-for-character positioning - no element here
 * sits at a cell coordinate, and the shell declares no fixed column count. What is
 * preserved is everything the grid actually conveyed: zone ordering, top to bottom,
 * exactly as measured; field grouping, in that fields the mapset placed together in one
 * zone stay together in that zone's component rather than being scattered across the
 * frame; reading order within each zone; and a tab order that follows the visual order,
 * because the DOM order below IS the visual order and no positive `tabIndex` overrides
 * it. Keyboard order is a fidelity requirement and not a courtesy here - the 3270
 * original had no pointer at all. This is design gap G1, recorded as an
 * intentional deviation in `ui/src/theme/tokens.ts` under `DESIGN_GAPS`, and this module
 * is where the deviation is actually taken.
 *
 * Statelessness: what this shell must never carry
 * ----------------------------------------------
 * Refactoring Rationale: the baseline is pseudo-conversational, so every scrap of
 * continuity between screen turns travelled in one passed structure -
 * `app/cpy/COCOM01Y.cpy` L19-L44, echoed back by the terminal on each turn. This shell
 * reproduces none of it, because that single structure decomposes into four different
 * target mechanisms and not one of the four is a shell concern:
 *
 * - `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-TO-TRANID`, `CDEMO-TO-PROGRAM`,
 *   `CDEMO-LAST-MAP` and `CDEMO-LAST-MAPSET` become client-side router history, owned by
 *   `ui/src/router.tsx`. There is deliberately no "next program" field anywhere here.
 * - `CDEMO-USER-ID` and `CDEMO-USER-TYPE` become signed token claims, owned by
 *   `ui/src/hooks/useAuth.ts`.
 * - `CDEMO-ACCT-ID`, `CDEMO-CARD-NUM` and `CDEMO-CUST-ID` become request path and query
 *   parameters, owned by the screens.
 * - `CDEMO-PGM-CONTEXT`, with its `88 CDEMO-PGM-ENTER VALUE 0` and
 *   `88 CDEMO-PGM-REENTER VALUE 1`, disappears outright. A stateless handler that
 *   answers with a per-field error array has no first-entry-versus-re-entry distinction
 *   left to draw, so the discriminator has nothing to discriminate. The coupling this
 *   severs is real: `app/cpy/CSSETATY.cpy` L18-L27 gates the red field highlight on that
 *   very flag, so in the target the highlight is driven by a response body instead.
 *
 * Assumptions: identity is read from `useAuth()` and from nowhere else, and this is a
 * security property rather than a tidier data flow. In the baseline the communication
 * area was storage the client echoed back, so a client that composed its own area could
 * assert its own `CDEMO-USER-TYPE` and claim administrator authority. The migrated
 * equivalent is a signed group claim the client cannot mint, so no user type is accepted
 * as a prop, from storage, or through the slot API below. Route gating stays in
 * `ui/src/router.tsx`; this shell may adapt its own chrome to a group but is never the
 * authorization boundary.
 *
 * Boundary
 * --------
 * Assumptions: this module owns no route table, performs no data fetching and imports
 * nothing from `ui/src/api/**`, not even transitively. It instantiates no
 * `ConfigProvider` - `ui/src/App.tsx` is the sole theming injection point - and it holds
 * no user-visible string of its own beyond the additive chrome recorded at
 * {@link SKIP_TO_CONTENT_LABEL}, because `ui/src/messages/messages.ts` owns the rest.
 */

import { Col, Flex, Layout, Row, Typography, theme } from 'antd';
import { useCallback, useLayoutEffect, useState, useSyncExternalStore } from 'react';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { Outlet } from 'react-router';

import { useAuth } from '../hooks/useAuth';
import { THANK_YOU_CARDDEMO } from '../messages/messages';
import type { MapsetName } from '../messages/messages';
import { BREAKPOINT_TOKENS, SPACING_TOKENS } from '../theme/tokens';
import type { AntdTokenName } from '../theme/tokens';
import { MessageBand } from './MessageBand';
import type { MessageBandSeverity } from './MessageBand';
import { PfKeyBar } from './PfKeyBar';
import type { PfKeyLegendColor } from './PfKeyBar';
import { ScreenHeader } from './ScreenHeader';
import { usePfKeys } from './usePfKeys';
import type { CicsAid, PfKeyBinding, PfKeyHandlerMap } from './usePfKeys';

/*
 * Assumptions: `Outlet` is imported from `react-router` and never from
 * `react-router-dom`, and the distinction is load-bearing rather than stylistic. The
 * companion package has no 8.x release at all: its newest publication is a thin shim
 * that depends on `react-router@7.18.1`, so importing it would silently pin routing a
 * major version behind the version `ui/package.json` actually declares, and would place
 * a second copy of the router in the bundle whose provider context is not the one
 * `ui/src/router.tsx` renders - making every hook read from it return `undefined` at run
 * time rather than failing at build time. `ui/package.json` therefore omits the
 * companion entirely and `ui/eslint.config.js` bans it by name, so this import is the
 * one form that both resolves and lints.
 */

/**
 * Stable `data-testid` on the shell's outermost element.
 *
 * Assumptions: a data attribute rather than a role query, because the outermost element
 * is antd's `Layout`, which renders a plain `div` and therefore exposes no landmark of
 * its own to select on. The three regions inside it are individually addressable by
 * their landmark roles; only the frame as a whole needs this.
 */
export const APP_SHELL_TEST_ID = 'app-shell';

/**
 * `id` of the shell's content region, and the skip link's target.
 *
 * Assumptions: the identifier is a module constant rather than a `useId` value because a
 * skip link needs a fragment target whose spelling is knowable to whoever writes the
 * link, and both ends of that pair are in this file. `useId` is correct for an
 * association a component makes with itself - `ScreenHeader` uses it for
 * `aria-labelledby` - but it produces a value that changes per mount, which a fragment
 * reference cannot use.
 */
export const SHELL_CONTENT_ELEMENT_ID = 'carddemo-shell-content';

/**
 * Visible label of the skip-to-content link.
 *
 * Refactoring Rationale: this string is declared here rather than in
 * `ui/src/messages/messages.ts`, and the split is the catalog's own rather than a gap in
 * its coverage. That module states its boundary at L150-L185: text painted by a `.bms`
 * map belongs to the component that renders it, which is why
 * `ui/src/layout/ScreenHeader.tsx` declares the four status-line prompts and
 * `ui/src/layout/PfKeyBar.tsx` declares the uniform legend labels, while copybook
 * constants and program literals stay in the catalog. This label is neither: no mapset
 * paints it, because a 3270 terminal had no scrollable viewport to skip past. It is
 * additive chrome introduced by the browser target, so the rule that places
 * mapset-derived text with its renderer places invented chrome there too, and the
 * catalog stays exactly what it claims to be - the owner of every string carried ACROSS
 * from the baseline.
 *
 * Trade-offs: the risk accepted is that a reader grepping the catalog for this sentence
 * finds nothing. It is contained the way the same risk is contained for the header
 * prompts and the key legends - by citing the delegation at the symbol itself, so a
 * future editor who moves it has to read why it is here first.
 */
export const SKIP_TO_CONTENT_LABEL = 'Skip to screen content';

/**
 * Attention identifier the shell binds to sign-off when it owns the function keys.
 *
 * Assumptions: PF12 rather than PF3, on measured usage. Across the online programs PF3
 * is overwhelmingly "back to the previous screen" and is bound by 14 of them, so a shell
 * that claimed it would fight the commonest screen binding in the application. PF12 is
 * the key the menu programs use to end a session, and `usePfKeys` already maps it to the
 * `cancel` action by default, which is the nearest published action to ending a session.
 * The sign-on screen is the one place PF3 itself signs off - `app/cbl/COSGN00C.cbl` L88 -
 * and that screen binds PF3 for the purpose itself rather than delegating to the shell.
 */
export const SHELL_SIGN_OFF_AID: CicsAid = 'PFK12';

/**
 * Legend text the shell paints for its own sign-off key.
 *
 * Assumptions: the `Fnn=Verb` shape is the baseline's own legend grammar, measured on
 * row 24 of all 17 mapsets - `ENTER=Sign-on  F3=Exit` on `app/bms/COSGN00.bms` L205 is
 * the canonical example - so the shell's added key reads as one of the same family
 * rather than as a foreign control.
 */
export const SHELL_SIGN_OFF_LEGEND = 'F12=Sign off';

/**
 * Stable `data-testid` on the sign-off surface that replaces the frame after sign-off.
 *
 * Assumptions: the surface deliberately carries no landmark and no heading, for the
 * reason given at {@link AppShell} - it stands in for a cleared terminal screen - so a
 * data attribute is the only stable handle a test can hold it by.
 */
export const SHELL_SIGN_OFF_TEST_ID = 'shell-sign-off';

/**
 * The two responsive breakpoints this shell reflows at, named as design-system tokens.
 *
 * Purpose: record which declared token each responsive `Col` prop below corresponds to,
 * so the reflow behaviour design gap G1 calls for traces to a named token rather than to
 * an undocumented choice of props. `md` is `screenMD` and `lg` is `screenLG`.
 *
 * Assumptions: exporting documentation-bearing data is an established pattern in this
 * tree rather than an invention here - `ui/src/layout/ScreenHeader.tsx` exports
 * `RETIRED_HEADER_FIELDS` for the same reason, so that a decision can be asserted by a
 * test instead of only being asserted in prose.
 *
 * Trade-offs: antd's grid takes `md` and `lg` as component props and offers no way to
 * pass a token name, so this constant documents the correspondence rather than supplying
 * it. The cost is that the link is verified by review and by the assertion this constant
 * makes possible, not by the compiler; the alternative of writing raw pixel widths into
 * a media query would trace to nothing at all and would opt the shell out of the theme.
 */
export const SHELL_BREAKPOINT_TOKENS = {
  medium: BREAKPOINT_TOKENS.medium,
  large: BREAKPOINT_TOKENS.large,
} as const satisfies Record<'medium' | 'large', AntdTokenName>;

/**
 * Identity of the screen currently occupying the shell's content region.
 *
 * Assumptions: both members are the values the baseline painted into the header's own
 * slots - the CICS transaction identifier and the program name - so the shell passes them
 * straight through to `ScreenHeader` without interpreting either. It never derives them
 * from the current route, because a route is a shape this migration chose while these two
 * are the reference identities the header is a display of; deriving them would make a
 * renamed route silently change what the band reports.
 */
export interface ShellScreenIdentity {
  /** Transaction identifier for the header's `Tran:` slot, from the mapset's `TRNNAME`. */
  readonly transactionId: string;
  /** Program name for the header's `Prog:` slot, from the mapset's `PGMNAME`. */
  readonly programName: string;
}

/**
 * The screen-level message the shell paints on the row-23 line.
 *
 * Assumptions: the members mirror `MessageBandProps` exactly rather than reshaping it, so
 * the shell adds no second opinion about what a message means. `text` is spelled `text`
 * because `message` would read as a nested message object once it sits inside a slot.
 */
export interface ShellMessageSlot {
  /** Message text, or `null`/`undefined` when the screen has none to show. */
  readonly text?: string | null | undefined;
  /** Severity governing the alert variant, colour and ARIA role. Defaults to `"error"`. */
  readonly severity?: MessageBandSeverity | undefined;
  /** Mapset the screen stands in for, which fixes the band's rendered display width. */
  readonly mapset?: MapsetName | undefined;
}

/**
 * The function-key legend and dispatcher the shell paints on the row-24 line.
 *
 * Assumptions: a screen supplies bindings it has ALREADY resolved through its own
 * `usePfKeys` call, together with that call's `invoke`. The shell renders them and
 * forwards activations back; it never re-derives a binding, because `usePfKeys` owns the
 * mapping from attention identifier to action, label and enabled state.
 */
export interface ShellPfKeySlot {
  /** Resolved bindings, normally the `bindings` member of a `usePfKeys` result. */
  readonly keys: readonly PfKeyBinding[];
  /** Dispatcher for an activated key, normally the `invoke` member of the same result. */
  readonly onInvoke: (aid: CicsAid) => void;
  /** Legend colour, matching the mapset's row-24 `COLOR=`. Defaults to `"YELLOW"`. */
  readonly legendColor?: PfKeyLegendColor | undefined;
  /** Accessible name for the legend region, when a screen needs a more specific one. */
  readonly regionLabel?: string | undefined;
}

/**
 * Everything a screen may delegate to the shell for the duration of its mount.
 *
 * Every member is optional, and absence is meaningful: the shell renders a zone's band
 * if and only if that zone has been delegated to it. See {@link useShellSlot} for why
 * that is the contract and what it prevents.
 */
export interface ShellSlot {
  /** Identity for the title band. Omit to leave the header zone unpainted. */
  readonly screen?: ShellScreenIdentity | undefined;
  /** Message for the row-23 line. Omit to leave the message zone unpainted. */
  readonly message?: ShellMessageSlot | undefined;
  /** Legend and dispatcher for the row-24 line. Omit to leave the footer unpainted. */
  readonly pfKeys?: ShellPfKeySlot | undefined;
  /**
   * Server-anchored instant the title band renders its date and time from.
   *
   * Assumptions: the instant is threaded IN rather than read here, and the reason is a
   * dependency boundary rather than convenience. The hook that supplies it,
   * `ui/src/hooks/useServerInstant.ts`, reads `ui/src/api/serverClock.ts`, so calling it
   * would give this shell a transitive dependency on the API layer that it is required
   * not to have. Omitting the value is legitimate and `ScreenHeader` degrades to the
   * browser clock, which that module records as a registered divergence.
   */
  readonly now?: Date | undefined;
}

/*
 * Alternatives Considered: how a screen hands its header identity, message and key
 * legend UP to a shell that renders above it. Four mechanisms were weighed, and the
 * constraint that decides between them is that this application has no React context
 * provider anywhere by design - `ui/src/hooks/useAuth.ts` is deliberately self-contained
 * behind a module-scoped store read through `useSyncExternalStore`, and introducing a
 * provider here would make this shell the first, for a concern strictly less important
 * than identity.
 *
 * A context provider was rejected on exactly that ground. Threading props through the
 * route was rejected because a layout route is mounted once for every screen beneath it,
 * so per-route props cannot vary as the outlet changes, and the message in particular is
 * live rather than static - it changes while one screen stays mounted. A render prop was
 * rejected because it inverts the composition the router requires: a route element is
 * constructed by the router with no arguments, so there is nowhere to pass a function in.
 * What remains is the mechanism `useAuth` already proves in this tree - a module-scoped
 * store published to by a hook and subscribed to with `useSyncExternalStore` - so the
 * pattern a reader has already met once is the pattern used again rather than a second
 * idiom for the same job.
 *
 * Trade-offs: the cost is that the store is module scope, so one shell instance is
 * assumed per document. That matches the frame it stands for exactly - a terminal had one
 * screen - and it is what makes the delegation observable from above without a provider.
 * The cost is bounded by {@link publishShellSlot} releasing only the publication it
 * actually owns, so an unmounting screen can never blank a newer screen's band.
 */

/** The slot state meaning "no screen has delegated any zone", shared to keep identity stable. */
const EMPTY_SLOT: ShellSlot = Object.freeze({});

/*
 * Refactoring Rationale: the store keeps TWO references to what is conceptually one
 * value, and collapsing them into one is a re-render loop rather than a simplification.
 * A screen renders inside this shell, so a shell re-render re-renders the screen; a screen
 * that rebuilds its `onInvoke` closure each render - which every screen calling
 * `usePfKeys` does - would then publish a slot that differs from the last one by function
 * identity alone, the shell would see a change, re-render, and the cycle would not
 * terminate. Splitting the value fixes the cause rather than damping the symptom:
 * `publishedSlot` always holds the newest callbacks so dispatch is never stale, while
 * `renderSlot` is replaced only when something a reader could SEE has changed, so
 * `useSyncExternalStore` is handed a referentially stable snapshot and re-renders exactly
 * as often as the rendered output actually differs.
 *
 * Assumptions: this mirrors how `ui/src/layout/usePfKeys.ts` already separates its
 * listener from the callbacks it dispatches into - it holds current handlers in a ref so
 * the installed listener stays stable - so the two modules resolve the same hazard the
 * same way.
 */

/** Newest published slot, including callback identities, read at dispatch time. */
let publishedSlot: ShellSlot = EMPTY_SLOT;

/** Referentially stable snapshot for rendering, replaced only on a visible difference. */
let renderSlot: ShellSlot = EMPTY_SLOT;

/**
 * Owner of the publication currently in force, or `null` when nothing is delegated.
 *
 * Assumptions: a release must be able to tell "my publication is still current" from "a
 * newer screen has replaced it". During a route change React mounts the incoming screen
 * before unmounting the outgoing one, so the outgoing release runs AFTER the incoming
 * publish; without this ownership check that release would blank the new screen's bands.
 */
let activeOwner: object | null = null;

/** Subscribers to re-run when the render snapshot changes. */
const listeners = new Set<() => void>();

/**
 * Reports whether two screen identities would paint the same title band.
 * @param {ShellScreenIdentity | undefined} previous - Identity currently rendered.
 * @param {ShellScreenIdentity | undefined} next - Identity just published.
 * @returns {boolean} `true` when both slots paint identical header text.
 */
function areScreensEquivalent(
  previous: ShellScreenIdentity | undefined,
  next: ShellScreenIdentity | undefined,
): boolean {
  if (previous === undefined || next === undefined) {
    return previous === next;
  }
  return previous.transactionId === next.transactionId && previous.programName === next.programName;
}

/**
 * Reports whether two message slots would paint the same row-23 line.
 * @param {ShellMessageSlot | undefined} previous - Message currently rendered.
 * @param {ShellMessageSlot | undefined} next - Message just published.
 * @returns {boolean} `true` when text, severity and mapset all match.
 */
function areMessagesEquivalent(
  previous: ShellMessageSlot | undefined,
  next: ShellMessageSlot | undefined,
): boolean {
  if (previous === undefined || next === undefined) {
    return previous === next;
  }
  return (
    previous.text === next.text &&
    previous.severity === next.severity &&
    previous.mapset === next.mapset
  );
}

/**
 * Reports whether two binding lists would paint the same row-24 legend.
 *
 * Assumptions: bindings are compared field by field rather than by array identity,
 * because `usePfKeys` rebuilds its list on every render - `createBindings` is called in
 * the return statement - so array identity changes constantly while the rendered legend
 * usually does not.
 * @param {readonly PfKeyBinding[]} previous - Bindings currently rendered.
 * @param {readonly PfKeyBinding[]} next - Bindings just published.
 * @returns {boolean} `true` when both lists render identical controls in identical order.
 */
function areBindingListsEquivalent(
  previous: readonly PfKeyBinding[],
  next: readonly PfKeyBinding[],
): boolean {
  if (previous.length !== next.length) {
    return false;
  }
  for (let index = 0; index < previous.length; index += 1) {
    const before = previous[index];
    const after = next[index];
    // Assumptions: `noUncheckedIndexedAccess` in `ui/tsconfig.json` types both reads as
    // possibly absent even though the length check above rules that out, so the pair is
    // narrowed rather than asserted. A non-null assertion would restate what the length
    // check only implies, and would keep compiling if that check were ever removed.
    if (before === undefined || after === undefined) {
      return before === after;
    }
    if (
      before.aid !== after.aid ||
      before.action !== after.action ||
      before.label !== after.label ||
      before.enabled !== after.enabled
    ) {
      return false;
    }
  }
  return true;
}

/**
 * Reports whether two function-key slots would paint the same legend, ignoring callbacks.
 *
 * Assumptions: `onInvoke` is excluded from the comparison deliberately. It is not
 * rendered, so a new closure cannot change what a reader sees, and including it would
 * make every screen render a visible change - which is precisely the loop the split store
 * above exists to prevent. Dispatch stays correct because {@link dispatchShellPfKey}
 * reads the newest callback from `publishedSlot` at the moment a key is activated.
 * @param {ShellPfKeySlot | undefined} previous - Key slot currently rendered.
 * @param {ShellPfKeySlot | undefined} next - Key slot just published.
 * @returns {boolean} `true` when both slots render an identical legend.
 */
function arePfKeySlotsEquivalent(
  previous: ShellPfKeySlot | undefined,
  next: ShellPfKeySlot | undefined,
): boolean {
  if (previous === undefined || next === undefined) {
    return previous === next;
  }
  return (
    previous.legendColor === next.legendColor &&
    previous.regionLabel === next.regionLabel &&
    areBindingListsEquivalent(previous.keys, next.keys)
  );
}

/**
 * Reports whether two instants would paint the same header date and time.
 *
 * Assumptions: compared by elapsed milliseconds rather than by reference, because a caller
 * that derives the instant per render yields a fresh `Date` each time for the same moment.
 * @param {Date | undefined} previous - Instant currently rendered.
 * @param {Date | undefined} next - Instant just published.
 * @returns {boolean} `true` when both represent the same moment, or both are absent.
 */
function areInstantsEquivalent(previous: Date | undefined, next: Date | undefined): boolean {
  if (previous === undefined || next === undefined) {
    return previous === next;
  }
  return previous.getTime() === next.getTime();
}

/**
 * Reports whether a newly published slot would render identically to the current snapshot.
 * @param {ShellSlot} previous - The snapshot currently rendered.
 * @param {ShellSlot} next - The slot just published.
 * @returns {boolean} `true` when re-rendering would produce identical output.
 */
function areSlotsRenderEquivalent(previous: ShellSlot, next: ShellSlot): boolean {
  return (
    areScreensEquivalent(previous.screen, next.screen) &&
    areMessagesEquivalent(previous.message, next.message) &&
    arePfKeySlotsEquivalent(previous.pfKeys, next.pfKeys) &&
    areInstantsEquivalent(previous.now, next.now)
  );
}

/**
 * Runs every subscriber so each rendering shell reads the new snapshot.
 * @returns {void} Nothing; subscribers are invoked for their effect.
 */
function notifyShellListeners(): void {
  for (const listener of listeners) {
    listener();
  }
}

/**
 * Registers a subscriber for slot changes.
 * @param {() => void} listener - Callback run whenever the render snapshot is replaced.
 * @returns {() => void} Releases this subscription.
 */
function subscribeToShellSlot(listener: () => void): () => void {
  /**
   * Stops delivering slot changes to this subscriber.
   * @returns {void} Nothing; the subscriber is removed from the set.
   */
  function unsubscribeFromShellSlot(): void {
    listeners.delete(listener);
  }

  listeners.add(listener);
  return unsubscribeFromShellSlot;
}

/**
 * Returns the referentially stable slot snapshot for rendering.
 * @returns {ShellSlot} The current snapshot, shared until a visible difference replaces it.
 */
function getShellSlotSnapshot(): ShellSlot {
  return renderSlot;
}

/**
 * Dispatches an activated function key to the newest published handler.
 *
 * Assumptions: this function is module scope so its identity never changes, which is what
 * lets it be handed to `PfKeyBar` as a prop without contributing a render-to-render
 * difference. It reads `publishedSlot` at call time rather than closing over a slot, so a
 * key pressed after a screen rebuilt its dispatcher reaches the new one.
 * @param {CicsAid} aid - Normalised attention identifier the operator activated.
 * @returns {void} Nothing; the delegating screen's own dispatcher decides the outcome.
 */
function dispatchShellPfKey(aid: CicsAid): void {
  publishedSlot.pfKeys?.onInvoke(aid);
}

/**
 * Records one owner's slot as the delegation in force.
 * @param {object} owner - Opaque identity of the publisher, used to scope its release.
 * @param {ShellSlot} slot - Zones that owner delegates to the shell.
 * @returns {void} Nothing; subscribers are notified only when the rendered output differs.
 */
function publishShellSlotFor(owner: object, slot: ShellSlot): void {
  activeOwner = owner;
  publishedSlot = slot;

  if (!areSlotsRenderEquivalent(renderSlot, slot)) {
    renderSlot = Object.freeze({ ...slot });
    notifyShellListeners();
  }
}

/**
 * Withdraws one owner's delegation, leaving a newer owner's publication alone.
 * @param {object} owner - Identity that was passed to {@link publishShellSlotFor}.
 * @returns {void} Nothing; a superseded owner's release is a no-op.
 */
function releaseShellSlotFor(owner: object): void {
  if (activeOwner !== owner) {
    return;
  }
  activeOwner = null;
  publishedSlot = EMPTY_SLOT;
  if (renderSlot !== EMPTY_SLOT) {
    renderSlot = EMPTY_SLOT;
    notifyShellListeners();
  }
}

/**
 * Creates the opaque per-mount identity a delegating component publishes under.
 * @returns {object} A fresh identity, distinct from every other component's.
 */
function createSlotOwner(): object {
  return {};
}

/**
 * Publishes a slot imperatively and returns its release.
 *
 * Purpose: the non-hook half of the delegation API, used by {@link useShellSlot} and
 * available to a test that needs to drive the shell without rendering a screen.
 *
 * Assumptions: publishing is idempotent. Re-publishing an equivalent slot updates the
 * callbacks and notifies nobody, so a caller may publish on every commit without having
 * to memoise anything - which is what makes the hook below safe to call with an object
 * literal.
 * @param {ShellSlot} slot - Zones the caller delegates to the shell for now.
 * @returns {() => void} Releases this publication, restoring the empty slot only if no
 *   newer publication has replaced it.
 */
export function publishShellSlot(slot: ShellSlot): () => void {
  const owner = {};
  publishShellSlotFor(owner, slot);

  /**
   * Withdraws this publication when it is still the one in force.
   * @returns {void} Nothing; a superseded publication is left untouched.
   */
  function releaseShellSlot(): void {
    releaseShellSlotFor(owner);
  }

  return releaseShellSlot;
}

/**
 * Delegates one or more shell zones to the shell for as long as the caller is mounted.
 *
 * Purpose: the hook a screen calls to have the shell paint its title band, message line
 * or key legend, instead of rendering those bands itself. Omit a member to keep that zone
 * unpainted; the shell renders a band if and only if it has been delegated one.
 *
 * Assumptions: the delegation contract is opt-in for a concrete reason. Every screen
 * currently delivered under `ui/src/screens/**` composes its own `MessageBand` and
 * `PfKeyBar`, and most compose their own `ScreenHeader`, so a shell that painted those
 * bands unconditionally would render a second message line and a second legend on every
 * such screen - two live regions announcing one message, and a duplicate `message-band`
 * test handle where callers reasonably expect one. Making publication the signal means a
 * screen that owns its bands is unaffected by this shell, while a screen that omits a band
 * on the stated ground that the shell supplies it - `ui/src/screens/accountView/index.tsx`
 * documents exactly that for the header - gets one by delegating.
 *
 * Trade-offs: publication happens on commit rather than during render, because writing to
 * a module store from a render body would publish from renders React may discard. The cost
 * is that the shell's first paint of a newly mounted screen has no band yet, which is why
 * a layout effect is used rather than a passive one - it runs after the DOM is mutated but
 * before the browser paints, so the delegated band is never visibly missing for a frame.
 * @param {ShellSlot} slot - Zones to delegate. May be a fresh object literal each render.
 * @returns {void} Nothing; the delegation is withdrawn when the caller unmounts.
 */
export function useShellSlot(slot: ShellSlot): void {
  const { screen, message, pfKeys, now } = slot;
  const [owner] = useState(createSlotOwner);

  /*
   * Refactoring Rationale: publishing and releasing are TWO effects, and combining them
   * into one with a cleanup was the first shape written here and is a defect. React runs
   * an effect's cleanup before its next run, so a caller passing a fresh object literal -
   * the shape this hook explicitly supports - would release on every render and then
   * immediately re-publish. Each release resets the snapshot to empty and notifies, so the
   * bands would blank and repaint on every commit, and because this shell renders the
   * screen that publishes to it, that notification re-renders the publisher and the cycle
   * sustains itself. Splitting the concerns removes the reset from the render path
   * entirely: publication tracks every commit, withdrawal happens once, at unmount.
   */

  useLayoutEffect(
    /**
     * Republishes the caller's current values on every commit.
     *
     * Assumptions: no dependency array, deliberately. The publisher is idempotent and
     * notifies only on a visible difference, so running it each commit costs one structural
     * comparison and guarantees the dispatcher held for the key legend is never a stale
     * closure. A dependency array over object members would skip commits in which only a
     * callback changed, which is exactly the case that must not be skipped.
     * @returns {void} Nothing; the publication is withdrawn by the effect below.
     */
    function publishDelegatedZones(): void {
      publishShellSlotFor(owner, { screen, message, pfKeys, now });
    },
  );

  useLayoutEffect(
    /**
     * Withdraws this caller's delegation when it unmounts.
     * @returns {() => void} Cleanup that releases this owner's publication.
     */
    function trackDelegationLifetime(): () => void {
      /**
       * Releases the delegation this component owns.
       * @returns {void} Nothing; a superseded delegation is left in place.
       */
      function releaseDelegatedZones(): void {
        releaseShellSlotFor(owner);
      }

      return releaseDelegatedZones;
    },
    [owner],
  );
}

/**
 * Props accepted by {@link AppShell}.
 *
 * Assumptions: every member is optional, because the shell's normal source of zone
 * content is the delegation store rather than its own props. These exist for the two
 * cases the store cannot serve: a mount site that wants a fixed frame regardless of which
 * screen is inside it, and a test that renders the shell in isolation. A prop takes
 * precedence over a delegated value, so an explicit frame is never overwritten by whatever
 * happens to be mounted beneath it.
 *
 * Assumptions: there is deliberately no user type, group, or role member. Identity reaches
 * this component only from `useAuth()`, for the security reason argued in the file
 * overview - a prop would be an assertion the caller could make, which is the defect the
 * signed claim exists to close.
 */
export interface AppShellProps {
  /**
   * Body content, used instead of the routed outlet.
   *
   * Assumptions: the shell is a route element, so the body region is normally
   * react-router's `Outlet` and this member is left unset. It exists so the frame can be
   * rendered and asserted without standing up a router, and a value here replaces the
   * outlet rather than sitting beside it.
   */
  readonly children?: ReactNode | undefined;
  /** Title-band identity, overriding any delegated by the mounted screen. */
  readonly screen?: ShellScreenIdentity | undefined;
  /** Row-23 message, overriding any delegated by the mounted screen. */
  readonly message?: ShellMessageSlot | undefined;
  /** Row-24 legend, overriding any delegated by the mounted screen. */
  readonly pfKeys?: ShellPfKeySlot | undefined;
  /** Server-anchored instant for the title band, overriding any delegated value. */
  readonly now?: Date | undefined;
}

/**
 * Renders the persistent four-zone frame around whichever screen is mounted.
 *
 * Purpose: paint the title band, the screen body, the message line and the function-key
 * legend in the order the baseline always showed them, so that all 21 migrated screens
 * inherit one frame instead of each rebuilding it. Zone content comes from the mounted
 * screen's delegation - see {@link useShellSlot} - or from this component's own props,
 * which win where both are present.
 *
 * Assumptions: no `ConfigProvider` is instantiated here, and this is the one decision in
 * the file whose violation would be invisible. `ui/src/App.tsx` is the sole theming
 * injection point, and antd 6 resolves a provider's tokens into CSS variables scoped to
 * the subtree it wraps, with the nearest provider winning. A second provider here would
 * therefore not merely duplicate the first - it would let two subtrees render the same
 * token at two different values while every file still traced its values to a token, so
 * the zero-hardcoded-values guarantee would hold file by file and fail in the rendered
 * application.
 *
 * Assumptions: the landmark structure comes from antd's own elements rather than from
 * explicit `role` attributes, verified against the installed package rather than assumed.
 * `Layout` renders a `div`, `Layout.Header` a `header`, `Layout.Content` a `main` and
 * `Layout.Footer` a `footer`. Because the enclosing `Layout` is a generic `div` and not a
 * sectioning element, the `header` maps to `banner` and the `footer` to `contentinfo`
 * instead of degrading to generic - the failure mode `ui/src/layout/ScreenHeader.tsx`
 * documents for a `header` nested inside `section`, `article`, `aside` or `nav`. Adding
 * redundant roles would restate what the markup already exposes.
 * @param {AppShellProps} props - Optional frame overrides and body content.
 * @returns {ReactElement} The four-zone frame, or the sign-off surface once the operator
 *   has signed off through the shell's own function key.
 */
export function AppShell(props: AppShellProps): ReactElement {
  const { children, screen, message, pfKeys, now } = props;

  /*
   * Refactoring Rationale: the token map is read here to correct a measured rendering
   * defect rather than to style anything new. `Layout.Header` ships a FIXED `height: 64px`
   * with a matching `line-height: 64px` and `overflow: visible`, which suits the
   * single-line header antd assumes. This band is two rows - `ScreenHeader` reproduces the
   * baseline's two status rows - plus the skip link, so against that fixed height the
   * second row escaped the header's own background and painted 19px BELOW it, overlapping
   * the first heading of the screen body. Browser measurement of the rendered page is what
   * exposed it; nothing in the type system or the test suite could.
   *
   * Alternatives Considered: overriding the component's height through `ConfigProvider`'s
   * component-token map, which is the design system's intended route for exactly this. It
   * is not available here: `ui/src/App.tsx` is the sole provider and a second one in this
   * file would fork the theme, which is the defect argued against above. The remaining
   * options were a literal pixel height - forbidden, and a guess at what two rows measure -
   * or letting the band size to its content, which is what is done. `cssVar` rather than
   * `token` is destructured for the reason `ui/src/layout/PfKeyBar.tsx` records: `token`
   * holds RESOLVED values, so writing one into a style attribute copies today's palette
   * into the element and silently opts it out of the CSS-variable surface antd 6 themes
   * through, while `cssVar` holds `var(--...)` references that keep following the theme.
   */
  const { cssVar } = theme.useToken();

  /*
   * Assumptions: `auto` and the two token references are the whole of this override, and
   * every one of them is admissible. `height: auto` lets the band grow to its own content,
   * and `auto` is one of the values the zero-hardcoded-values rule exempts by name.
   * `lineHeight` comes from the theme's own base line-height token, which is what neutralises
   * the inherited 64px line box - without it each row would occupy a 64px line and the band
   * would merely overflow further down. The vertical padding comes from the spacing scale
   * through `ui/src/theme/tokens.ts`, so the band breathes without a pixel literal. The
   * horizontal padding antd already applies is deliberately left alone.
   */
  const headerStyle: CSSProperties = {
    height: 'auto',
    lineHeight: cssVar.lineHeight,
    paddingBlock: cssVar[SPACING_TOKENS.controlPaddingCompact],
  };

  /*
   * Trade-offs: the frame is given a minimum height of one viewport, and the unit is a
   * literal because the design system has no token for viewport height - the token scales
   * cover colour, spacing, radius, typography and motion, none of which can express "as
   * tall as the window". The alternative was to leave the frame sized to its content, which
   * is what it did when measured: on a short screen the key legend floated directly beneath
   * the body with the rest of the window blank, where the baseline always painted the legend
   * on row 24 at the bottom edge of the display. Reproducing that position is the fidelity
   * gain; the cost is the one unresolved value below, flagged rather than hidden.
   * `dvh` rather than `vh` is used because a mobile browser's dynamic toolbar makes `vh`
   * overshoot the visible area, which would push the legend out of sight on exactly the
   * viewports the responsive reflow exists to serve.
   */
  /*
   * Trade-offs: a browser measurement showed this produces 16px of VERTICAL scroll, and the
   * cause is worth recording because it is not this declaration. A document that keeps the
   * user agent's default `body { margin: 8px }` adds 8px above and below a frame that is
   * itself exactly one viewport tall, so the page becomes 16px longer than the window.
   * `ui/index.html` links no stylesheet by a decision it documents at L145-L154 - antd 6
   * injects its own styles as CSS-in-JS, and a reset sheet would put design values in a
   * second place beside `ui/src/theme/antdTheme.ts` - so no margin reset exists to absorb
   * it. Reducing the height by a literal 16px to compensate was rejected: it would encode
   * one document's margin into a component that any document may mount, and it would be
   * wrong the moment a reset arrived. The 16px scroll is accepted as the smaller cost, and
   * a `body` margin reset at whichever layer serves the document removes it entirely.
   */
  /* BLITZY [LAYOUT]: viewport height has no Ant Design token; 100dvh emitted literally. */
  const frameStyle: CSSProperties = { minHeight: '100dvh' };

  /*
   * Assumptions: the contrast pairing below is measured, reported and deliberately NOT
   * corrected here. `Layout.Header` fills with antd's own dark header background and the
   * BMS bridge maps the mapsets' dominant `COLOR=BLUE` - 289 measured occurrences - onto
   * `colorPrimary`, so every label, value and link this band carries is that blue on that
   * navy. A browser audit measures the pair at 4.49:1 where WCAG AA asks 4.5:1 for normal
   * text: a shortfall of 0.01, across the skip link and the header's label and value spans.
   * It is left as the design system produces it because the token mapping is owned by
   * `ui/src/theme/tokens.ts` and the background by the component's own default, so lightening
   * a colour here would fork the palette at one element - the precise failure the single
   * theming injection point exists to prevent - and would put a literal colour in a file whose
   * rule is that every value resolves to a token. Raising `colorPrimary` one step in the
   * theme, or mapping the band's text to a lighter semantic token, fixes all twelve affected
   * nodes at once and is the correct place for the change.
   */
  /* BLITZY [A11Y]: colorPrimary on the header background measures 4.49:1, below WCAG AA
   * 4.5:1 for normal text. Implemented as the design system specifies; flagged for designer
   * review rather than corrected locally. */
  const delegated = useSyncExternalStore(
    subscribeToShellSlot,
    getShellSlotSnapshot,
    // Assumptions: the same reader serves as the server snapshot. It returns a module
    // value and touches no browser API, so a tree rendered without a DOM reads the empty
    // slot rather than throwing - matching how `ui/src/hooks/useAuth.ts` supplies its own
    // third argument.
    getShellSlotSnapshot,
  );
  const { signedOn, signOut } = useAuth();

  /*
   * Assumptions: this latch is NOT the session state the file overview forbids, and the
   * distinction is worth drawing because a reader auditing that prohibition will find a
   * `useState` here and has to be able to tell the two apart. What the prohibition rules
   * out is continuity that substitutes for the communication area - a remembered next
   * program, a last map, a re-entry discriminator, or any assertion about who the operator
   * is. This holds none of those: it records only that sign-off has already happened in
   * this mounted tree, so the cleared-screen surface stays rendered instead of flickering
   * back to the frame. It carries no identity, grants no authority, is never read by
   * anything but the branch below, and is discarded on reload - after which the operator is
   * anonymous because the token is gone, not because this value was remembered.
   *
   * Alternatives Considered: deriving the surface from `signedOn` alone, with no state at
   * all. Rejected because it cannot distinguish "signed off just now" from "never signed
   * on", so every unauthenticated first visit would open on a thank-you for a session that
   * never existed.
   */
  const [signedOff, setSignedOff] = useState(false);

  const activeScreen = screen ?? delegated.screen;
  const activeMessage = message ?? delegated.message;
  const activePfKeys = pfKeys ?? delegated.pfKeys;
  const activeNow = now ?? delegated.now;

  const signOffFromShell = useCallback(
    /**
     * Ends the session and replaces the frame with the sign-off surface.
     * @returns {void} Nothing; the surface is rendered on the resulting re-render.
     */
    function endSession(): void {
      signOut();
      setSignedOff(true);
    },
    [signOut],
  );

  /*
   * Refactoring Rationale: the shell binds a function key only when NO screen has
   * delegated one, and that condition is what keeps two keyboard owners from fighting.
   * `usePfKeys` installs a listener per call site on the shared document, so a shell that
   * always bound its own keys would sit alongside the mounted screen's listener and both
   * would receive every keypress - and since PF12 is `cancel` on the screens that bind it,
   * one key would both cancel a screen's edit and end the session.
   *
   * Assumptions: `enabled: false` is a SILENT stand-down and not merely a disabled legend,
   * which is what makes this safe rather than noisy. `usePfKeys` tests that flag at the top
   * of `invoke`, ahead of the unmapped-attention-identifier branch, so a disabled hook
   * returns without reporting a rejection. Standing down by passing an empty handler map
   * alone would instead take the unmapped branch and surface the baseline's
   * `Invalid key pressed` message on every function key the operator pressed.
   */
  const shellOwnsFunctionKeys = activePfKeys === undefined && signedOn;
  const shellHandlers: PfKeyHandlerMap = shellOwnsFunctionKeys
    ? { [SHELL_SIGN_OFF_AID]: { onInvoke: signOffFromShell, label: SHELL_SIGN_OFF_LEGEND } }
    : {};
  const { bindings, invoke } = usePfKeys(shellHandlers, { enabled: shellOwnsFunctionKeys });

  const legendKeys: readonly PfKeyBinding[] = activePfKeys?.keys ?? bindings;
  const legendInvoke = activePfKeys === undefined ? invoke : dispatchShellPfKey;

  if (signedOff) {
    /*
     * Assumptions: sign-off REPLACES the frame rather than showing a message inside it,
     * because the baseline's sign-off is not a band message. `app/cbl/COSGN00C.cbl` L89
     * moves `CCDA-MSG-THANK-YOU` to the message area and then L162-L172 emits it with
     * `EXEC CICS SEND TEXT ... ERASE FREEKB` followed by a bare `EXEC CICS RETURN` carrying
     * no `TRANSID` - so the map is erased, plain text is written to a cleared screen, and
     * the session genuinely ends rather than returning to a transaction. Painting the
     * thank-you into the row-23 line would keep the frame an operator has just left.
     *
     * Alternatives Considered: antd's `Result`, which is the obvious component for a
     * full-page terminal state. Rejected because the design-system mapping reserves
     * `Result` for the abend surface that renders the `ABEND-DATA` fields of
     * `app/cpy/CSMSG02Y.cpy`, and a successful sign-off is not a failure; borrowing the
     * error surface would give a normal session end the appearance of a crash. Plain text
     * in a layout primitive is also the literal translation of `SEND TEXT`.
     */
    return (
      <Flex
        data-testid={SHELL_SIGN_OFF_TEST_ID}
        vertical
        /*
         * Refactoring Rationale: the text is aligned to the START of a full-height surface,
         * and both halves of that replaced a centred surface that did not work. Centring was
         * written first as `align="center" justify="center"`, and a browser measurement showed
         * the cross-axis centring had no effect at all: with no height of its own the
         * container collapsed to its content, so there was no free space to centre within and
         * the text simply sat at the top while the props implied otherwise. Rather than make
         * the centring effective, the alignment was corrected to match the baseline. An
         * `EXEC CICS SEND TEXT ... ERASE` clears the screen and writes from its beginning, so
         * the sign-off line belongs at the top of the cleared display; centring it would have
         * been a browser flourish the terminal never performed. The frame height is reused so
         * the erased surface still occupies the whole display, as the cleared screen did.
         */
        align="start"
        style={frameStyle}
        // Assumptions: the gap is antd's semantic size name rather than a number, so it
        // resolves through the theme's spacing scale. A number here would be a pixel literal,
        // which under CSS-variable theming does not merely duplicate a token but opts the
        // element out of the theme silently.
        gap="large"
      >
        {/*
          Assumptions: the constant is rendered verbatim, trailing blanks included, because
          it is a `PIC X(50)` field whose literal is 49 characters and Transformation Rule
          T8 carries user-visible strings across character for character. HTML collapses the
          trailing whitespace on display, so fidelity costs nothing visually, and a trimmed
          copy here would be a second spelling of a catalogued string.
          Assumptions: `role="status"` announces the surface politely when it replaces the
          frame. It is not an `alert`: ending a session is the outcome the operator asked
          for, so it should not interrupt.
        */}
        <Typography.Text strong role="status">
          {THANK_YOU_CARDDEMO}
        </Typography.Text>
      </Flex>
    );
  }

  return (
    <Layout data-testid={APP_SHELL_TEST_ID} style={frameStyle}>
      <Layout.Header style={headerStyle}>
        <Flex vertical gap="small">
          {/*
            Alternatives Considered: revealing the skip link only on focus, which is the
            conventional treatment. Rejected because hiding it off-canvas needs an absolute
            offset in pixels, and this tree admits no pixel literal outside the theme - and
            design gap G1 has already surrendered absolute positioning, so reintroducing it
            for chrome the baseline never had would be the one place the shell contradicted
            its own deviation. Trade-offs: the link is therefore always visible, costing one
            line of the frame in exchange for being the first focusable element on every
            screen without any positioning at all. It is first in DOM order, so tab order
            reaches it before the title band, which is the whole point of the control.
          */}
          <Typography.Link href={`#${SHELL_CONTENT_ELEMENT_ID}`}>
            {SKIP_TO_CONTENT_LABEL}
          </Typography.Link>
          {activeScreen === undefined ? null : (
            <ScreenHeader
              transactionId={activeScreen.transactionId}
              programName={activeScreen.programName}
              now={activeNow}
            />
          )}
        </Flex>
      </Layout.Header>
      {/*
        Assumptions: the content region is focusable with `tabIndex={-1}` so the skip link
        actually moves focus. A fragment jump scrolls to its target but only moves focus
        when the target can hold it, so without this the link would move the viewport and
        leave the keyboard where it was - the next Tab would return to the header. The value
        is negative rather than positive: it makes the region programmatically focusable
        without inserting it into the tab sequence, so the DOM order below remains the tab
        order, which is what design gap G1 undertakes to preserve.
      */}
      <Layout.Content id={SHELL_CONTENT_ELEMENT_ID} tabIndex={-1}>
        {/*
          Assumptions: the body is bounded by a responsive column rather than by a fixed
          width. `span={24}` fills the row at every size and `lg` narrows it once the
          viewport passes the `screenLG` breakpoint recorded in
          {@link SHELL_BREAKPOINT_TOKENS}, which keeps line lengths readable on a wide
          display without ever clipping - the failure the fixed 80-column grid could not
          avoid. Only the two breakpoints the design bridge declares are used; no third one
          is invented here.
        */}
        <Row justify="center">
          <Col span={24} lg={22}>
            {children ?? <Outlet />}
          </Col>
        </Row>
      </Layout.Content>
      {/*
        Assumptions: the message line sits between the content and the footer, above the key
        legend, because that is the measured order - the message field is at `POS=(23,` and
        the legend at `POS=(24,` on 17 of 17 base mapsets. Both were visible at once on a
        terminal, and after a rejection an operator's eye goes to the message before the key
        list, so inverting the pair would put the explanation below the controls it explains.
        Assumptions: it is a sibling of the three regions rather than a child of the footer,
        so a screen-level message is not announced from inside `contentinfo`, which is for
        information about the document rather than the outcome of an action.
      */}
      {activeMessage === undefined ? null : (
        <MessageBand
          message={activeMessage.text}
          severity={activeMessage.severity}
          mapset={activeMessage.mapset}
        />
      )}
      <Layout.Footer>
        {/*
          Assumptions: the legend is rendered unconditionally and needs no guard of its own,
          because `PfKeyBar` returns `null` for an empty binding list rather than an empty
          landmark. So when neither a screen nor this shell has a key to offer, the footer is
          simply empty instead of announcing a named region containing no control.
          Assumptions: the two optional props are spread conditionally rather than passed as
          possibly-undefined values. `PfKeyBarProps` declares them without `| undefined`, and
          `exactOptionalPropertyTypes` in `ui/tsconfig.json` distinguishes an omitted property
          from one explicitly set to `undefined`, so passing the absent case directly is a
          type error. Spreading nothing lets the component apply its own documented defaults.
        */}
        <PfKeyBar
          keys={legendKeys}
          onInvoke={legendInvoke}
          {...(activePfKeys?.legendColor === undefined
            ? {}
            : { legendColor: activePfKeys.legendColor })}
          {...(activePfKeys?.regionLabel === undefined
            ? {}
            : { regionLabel: activePfKeys.regionLabel })}
        />
      </Layout.Footer>
    </Layout>
  );
}
