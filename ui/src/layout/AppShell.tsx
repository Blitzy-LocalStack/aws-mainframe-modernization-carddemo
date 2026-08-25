/**
 * @file The single persistent application shell - the browser replacement for the fixed
 * 24-by-80 terminal frame that every CardDemo mapset paints around its content.
 *
 * Purpose
 * -------
 * Compose the four zones that are shared by every migrated screen and owned by none of
 * them, in the one order the baseline always showed them: the title band, the screen
 * body, the message line and the function-key legend. The other three zones are the three
 * shared shell elements that AAP section 0.4.4 assigns here -
 * `ui/src/layout/ScreenHeader.tsx`, `ui/src/layout/MessageBand.tsx` and
 * `ui/src/layout/PfKeyBar.tsx`. The bindings the legend renders are a screen's own, built
 * by `ui/src/layout/usePfKeys.ts` at that screen and delegated here; this shell calls that
 * hook nowhere, and installs no keyboard listener of its own, for the reason recorded at
 * {@link SHELL_SIGN_OFF_LABEL}.
 *
 * Where it is mounted, and how a screen reaches it
 * -----------------------------------------------
 * One model, stated once, and every other statement in this file is held to it.
 *
 * - `ui/src/router.tsx` mounts this component EXACTLY ONCE, as the element of the OUTERMOST
 *   layout route of the tree it publishes as `CARD_DEMO_ROUTES`, and nowhere else.
 * - That layout route wraps BOTH the sign-on route and the guarded subtree. `RequireSignOn`
 *   is reached through a PATHLESS route nested INSIDE this one, so the frame is above the
 *   guard rather than beneath it: sign-on is framed and unguarded, and every other screen is
 *   framed and guarded. The administrative screens sit behind a further nested guard inside
 *   that, so they are framed, guarded and group-checked.
 * - Two routes are deliberately left OUTSIDE the frame: the root redirect and the not-found
 *   screen. Neither stands for a mapset, so neither gets a 24-row terminal frame.
 * - `ui/src/App.tsx` composes NO frame. It renders the single `ConfigProvider` and, inside
 *   it, the single `RouterProvider` built from that route tree - nothing else.
 * - The production mount therefore reaches the body zone through react-router's `Outlet`.
 *   The `children` prop below is the FALLBACK, kept so a test can render the frame around
 *   one screen without standing up a router.
 *
 * ⚠ Refactoring Rationale: this section replaces two sections that contradicted each other
 * and the router. One said the shell was mounted "inside `RequireSignOn`", which inverts the
 * nesting - the guard is inside the shell route, which is what lets sign-on be framed at all,
 * and a reader who trusted the old sentence would look for the frame in the wrong subtree.
 * The other said `ui/src/App.tsx` renders `<AppShell><CardDemoRouter /></AppShell>`; that
 * component no longer exists and `App.tsx` mounts no shell.
 *
 * ⚠ Refactoring Rationale: BOTH mounts existed for a time - one above the router taking
 * `children`, and one layout route inside it - and the two composed. `{children ?? <Outlet />}`
 * means the outer mount never reaches its outlet, so the inner layout route rendered the
 * screen through its own and every guarded screen was framed TWICE: two banners, two message
 * lines, two legends and two skip links, with both frames reading the one publication. The
 * layout route is the mount that survives, because it is the only one of the two that can
 * frame some routes and not others - the root redirect and the not-found screen are
 * deliberately outside the frame, which a mount above the whole table cannot express.
 *
 * Alternatives Considered: mounting above the route table, which reads as the simpler shape
 * and keeps the frame a property of the application rather than of any route. Rejected on the
 * two facts above: it cannot leave a route unframed, and while it is composed with a layout
 * route it double-frames silently rather than failing.
 *
 * Refactoring Rationale: an earlier revision of this paragraph described the component as
 * "a route element", which it was not - nothing imported it, `ui/src/App.tsx` built a
 * second frame out of generic `Layout` primitives, and every screen either composed its own
 * chrome or, in two cases, omitted it on the stated ground that this shell supplied it. Both
 * halves of the integration now exist: the single mount above, and a `useShellSlot` call in
 * every one of the twenty-one delivered screens. A statement about integration is only ever
 * as true as the call sites, so the specific facts are named here rather than asserted in the
 * abstract -- and `ui/src/layout/screenHeaderClock.test.tsx` asserts them over a population it
 * discovers from the filesystem, so the count above cannot quietly stop being the whole of it.
 *
 * Provenance
 * ----------
 * The zone map below was measured across the whole reference baseline rather than read
 * off one mapset. `app/**` is reference-only and was never modified.
 *
 * | Baseline rows | Zone            | Region              | Component      |
 * | ------------- | --------------- | ------------------- | -------------- |
 * | 1-3           | title band      | `Layout.Header`     | `ScreenHeader` |
 * | 4-22          | screen body     | `Layout.Content`    | `children`     |
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
 * Assumptions: this module owns no route table and performs no data fetching of its own.
 * It instantiates no `ConfigProvider` - `ui/src/App.tsx` is the sole theming injection
 * point - and it holds no user-visible string of its own beyond the additive chrome
 * recorded at {@link SKIP_TO_CONTENT_LABEL}, because `ui/src/messages/messages.ts` owns
 * the rest.
 *
 * ⚠️ Refactoring Rationale: this paragraph claimed the module "imports nothing from
 * `ui/src/api/**`, not even transitively", and that was FALSE in the revision that stated
 * it. The import list below includes `../hooks/useAuth`, which imports `../api/auth` and
 * `../api/client` - so the transitive edge existed, and the sentence most likely to be
 * trusted was the one asserting it did not. The claim is corrected rather than the import
 * removed: the shell needs the session to decide whether to offer sign-off and to end it,
 * and reading identity through the same hook every other consumer uses is what keeps one
 * module the owner of that fact.
 *
 * Assumptions: what the shell actually holds is a dependency on the SESSION and not on any
 * resource client. The distinction is the one worth stating, because it is what the
 * original sentence was reaching for: this module calls no endpoint, holds no query, and
 * names no path. Its whole use of `useAuth` is two members - `signedOn`, which conditions
 * the rendered sign-off control, and `signOut`, which ends the session - and the transitive reach into
 * `ui/src/api/**` is `useAuth`'s own, because that hook exchanges credentials.
 *
 * Assumptions: the paint instant is nonetheless threaded IN through the slot rather than
 * read here, and that decision SURVIVES the correction above even though its original
 * reasoning does not. `ShellSlot.now` records the reason as avoiding a transitive API
 * dependency, and that reason no longer distinguishes anything now that `useAuth` supplies
 * one. What still holds is ownership: the instant belongs to the screen, because a screen
 * knows when it painted and the frame does not, and `ui/src/hooks/useServerInstant.ts`
 * would otherwise be called once here for every screen beneath rather than once per screen.
 * Reading it here would also make the frame re-render on a clock change that no mounted
 * screen had asked for.
 */

import { Button, Col, Flex, Layout, Row, Typography, theme } from 'antd';
import { useCallback, useLayoutEffect, useState, useSyncExternalStore } from 'react';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';

import { useAuth } from '../hooks/useAuth';
// WHY : Assumptions: the sign-off control's label is the LOCAL SHELL_SIGN_OFF_LABEL below and not the
//       message catalog's SIGN_OFF_CONTROL_LABEL, which carries the same six characters. Both were
//       authored for this one control; the local declaration is kept because the label is additive
//       browser chrome rather than a string transcribed from a mapset, which is the distinction the
//       catalog exists to draw -- and it is declared beside SKIP_TO_CONTENT_LABEL for the reason
//       recorded there. The catalog's own entry stays where it is: it is exported, documented and
//       reachable, and removing a published constant is not this file's decision to make.
import { MAIN_MENU_HEADINGS, SIGN_ON_SUBMIT_LABEL, THANK_YOU_CARDDEMO } from '../messages/messages';
import type { MapsetName } from '../messages/messages';
// WHY : Assumptions: the address is read from `../routes/navigation`, which imports nothing but
//       react-router types, and NOT from `../routes/guards`, which re-exports it. The guards module
//       imports this file, so importing it back would close a cycle; the constant's own doc block in
//       `navigation.ts` records why the declaration lives there.
import {
  MAIN_MENU_ROUTE,
  SIGN_OFF_ACKNOWLEDGED_REASON,
  SIGN_ON_ROUTE,
  navigateSafely,
  signOnEntryReason,
} from '../routes/navigation';
import {
  BMS_TEXT_COLOR_TOKENS,
  BREAKPOINT_TOKENS,
  SPACING_TOKENS,
  SURFACE_TOKENS,
} from '../theme/tokens';
import type { AntdTokenName } from '../theme/tokens';
import { MessageBand } from './MessageBand';
import type { MessageBandSeverity } from './MessageBand';
import { PfKeyBar } from './PfKeyBar';
import type { PfKeyLegendColor } from './PfKeyBar';
import { AppTitleHeading, ScreenHeader } from './ScreenHeader';
import type { CicsAid, PfKeyBinding } from './usePfKeys';

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
 * Stable `data-testid` on the pinned zone that carries the row-22, row-23 and row-24 lines.
 *
 * Assumptions: a data attribute rather than a landmark query, because the zone is a layout
 * primitive that deliberately carries NO role - adding one would put a fourth landmark in a
 * frame whose landmark census is asserted elsewhere. It still needs a handle: the pinning
 * that keeps rows 23 and 24 on the glass is declared on this element and nowhere else, so a
 * regression to in-flow siblings is only observable by inspecting it.
 */
export const SHELL_PINNED_ZONE_TEST_ID = 'shell-pinned-zone';

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
 * Visible label of the shell's sign-off control.
 *
 * Refactoring Rationale: the shell's sign-off used to be a FUNCTION KEY - `PFK12`, legended
 * `F12=Sign off` - and it is a rendered control now because the key form could not survive
 * this shell being mounted. `usePfKeys` installs one document listener per call site, and all
 * 21 screen modules call it, so a shell that bound a key of its own would sit alongside the
 * mounted screen's listener and both would receive every keypress. FOUR screens bind PF12 as
 * `Cancel` - measured at `ui/src/screens/accountUpdate/index.tsx`,
 * `ui/src/screens/cardUpdate/index.tsx`, `ui/src/screens/refTypeEdit/index.tsx` and
 * `ui/src/screens/userUpdate/index.tsx` - so on those screens one keypress would have BOTH
 * cancelled the operator's edit and ended the session. The file already warned about that
 * hazard at the binding site; what it could not do was avoid it, because the shell's own
 * stand-down condition - "no screen has delegated a key legend" - is never satisfied here.
 *
 * ⚠ Assumptions: that condition is decided per screen, and the census does NOT split - all 21
 * screens delegate a legend, so the shell would stand down on every one of them and offer no
 * sign-off anywhere. That is the arm the census actually selects, and it is not the behaviour
 * wanted: it removes the exit from every screen an operator spends the session on. The opposite
 * arm - the shell binding its own key because some screen owns its legend - would reintroduce a
 * second document listener on exactly the screens that already installed one, and it is
 * unreachable only because no screen owns its legend today, which is not a property the shell
 * can rely on staying true. A rendered control needs neither arm, and it is the only form that
 * is available identically on all 21 screens.
 *
 * Assumptions: nothing of the baseline is lost by this, because the shell's PF12 was never a
 * baseline behaviour. Sign-off in the reference application belongs to the MENU programs:
 * `app/cbl/COMEN01C.cbl` L196-L203 and `app/cbl/COADM01C.cbl` transfer to the sign-on program
 * on PF3 with no `COMMAREA` clause, and `app/cbl/COSGN00C.cbl` L88 does the same. Those are
 * screen bindings, and `ui/src/screens/menu/index.tsx` and `ui/src/screens/admin/index.tsx`
 * carry them as such. What remains here is an always-available way out of any screen, which is
 * additive browser chrome of exactly the kind {@link SKIP_TO_CONTENT_LABEL} already is - so it
 * is declared here beside that label rather than in `ui/src/messages/messages.ts`, for the
 * reason recorded there.
 *
 * Trade-offs: the cost is that this one action is reachable by pointer and by Tab rather than
 * by a single function key. It is bounded: the control is a `Button`, so Enter and Space
 * activate it once focused, and the keyboard route the baseline actually offered - PF3 on the
 * menu screens - is unaffected.
 */
export const SHELL_SIGN_OFF_LABEL = 'Sign off';

/**
 * Stable `data-testid` on the shell's sign-off control.
 *
 * Assumptions: a data attribute in addition to the accessible name, because the label above is
 * additive chrome rather than a catalogued baseline string, so a case that must not depend on
 * its exact wording has a handle that does not.
 */
export const SHELL_SIGN_OFF_CONTROL_TEST_ID = 'shell-sign-off-control';

/**
 * Stable `data-testid` on the sign-off surface that replaces the frame at the entry signed off at.
 *
 * Assumptions: the surface deliberately carries no landmark and no heading, for the
 * reason given at {@link AppShell} - it stands in for a cleared terminal screen - so a
 * data attribute is the only stable handle a test can hold it by.
 */
export const SHELL_SIGN_OFF_TEST_ID = 'shell-sign-off';

/**
 * Test handle for the control that leaves the sign-off acknowledgement for the sign-on screen.
 *
 * Assumptions: the surface needs a handle of its own because the acknowledgement replaces the frame,
 * so none of the frame's handles resolve while it is shown and a query for the control cannot be
 * scoped by them.
 */
export const SHELL_SIGN_OFF_CONTINUE_TEST_ID = 'shell-sign-off-continue';

/**
 * Test handle for the chrome control that crosses from the administrative surface to the main menu.
 *
 * Assumptions: the control is chrome rather than a menu option, so it carries its own handle instead
 * of being found among the options of whichever menu is mounted. See its render site for why it is not
 * a seventh administrative option.
 */
export const SHELL_MAIN_MENU_CROSSING_TEST_ID = 'shell-main-menu-crossing';

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
  /**
   * The row-22 INFORMATIONAL line, for the five mapsets that declare one above row 23.
   *
   * Refactoring Rationale: this member exists because the frame previously offered ONE
   * channel and the reference declares TWO, so the five screens whose mapset carries
   * `INFOMSG` had nowhere to put it and rendered a second band inside their own body
   * instead. That is measurable and was measured: on `/account/update` and
   * `/reference/transaction-types/:cd` the frame's row-23 band stands empty at its reserved
   * height while the screen's real advisory paints roughly 200 pixels higher up the page,
   * so an operator reads two message zones that the terminal showed as two adjacent rows.
   * Publishing the second line here puts both bands back where `app/bms/COACTVW.bms`
   * L356-L368 puts them - row 22 immediately above row 23, in the same pinned zone.
   *
   * Assumptions: the two channels are distinguished by WHAT THEY CARRY and not by severity.
   * `INFOMSG` is `ATTRB=(PROT) COLOR=NEUTRAL` and carries standing guidance the operator did
   * not ask for - `Enter or update id of account to display` on every turn of the account
   * view - while `ERRMSG` is `COLOR=RED ATTRB=(ASKIP,BRT,FSET)` and carries the OUTCOME of
   * the turn just taken. So advisory text belongs here and an outcome belongs in `text`,
   * whatever colour either one ends up painted in.
   *
   * Assumptions: the member is optional and its absence means the mapset declares no row-22
   * field, which is true of 16 of the 21. A screen that omits it gets exactly the frame it
   * got before this member existed, so nothing has to opt out.
   */
  readonly information?: ShellInformationSlot | undefined;
}

/**
 * The row-22 informational line a screen delegates to the frame.
 *
 * Assumptions: it carries no `mapset` of its own. The display width is a property of the
 * MAPSET rather than of the line, and a screen stands in for exactly one mapset, so the
 * width already published on {@link ShellMessageSlot.mapset} governs both bands. A second
 * mapset member here would let one screen claim two widths.
 */
export interface ShellInformationSlot {
  /** Advisory text, or `null`/`undefined` when the screen has none to show. */
  readonly text?: string | null | undefined;
  /**
   * Severity governing the alert variant, colour and ARIA role. Defaults to `"neutral"`.
   *
   * Assumptions: the default is `"neutral"` and not the row-23 default of `"error"`, because
   * `INFOMSG` is declared `COLOR=NEUTRAL` on the mapsets that have it, which the token bridge
   * resolves to `colorTextSecondary`. A screen naming nothing therefore gets the colour its
   * own mapset declares rather than the colour the other line declares.
   */
  readonly severity?: MessageBandSeverity | undefined;
}

/*
 * WHY : ⚠️ Refactoring Rationale: there is no `DEFAULT_INFORMATION_SEVERITY` constant here any more,
 *       and the measurement it carried has not been dropped - it has moved to the module that owns the
 *       band. `defaultMessageBandSeverity` in `ui/src/layout/MessageBand.tsx` answers it from
 *       `MESSAGE_BAND_CHANNELS`, the table that records what each of the two BMS message lines carries
 *       and which `COLOR=` operand it declares: `COLOR=NEUTRAL` for row 22 and `COLOR=RED` for row 23.
 *       Holding a copy of the row-22 half here made the frame and the band two authorities on one
 *       decision, and only one of them was beside the row-23 default it has to differ from.
 */

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
   * question of OWNERSHIP rather than convenience. ⚠️ Refactoring Rationale: this note
   * argued instead that reading the instant here "would give this shell a transitive
   * dependency on the API layer that it is required not to have", and that argument does
   * not survive measurement -- the shell already imports `../hooks/useAuth`, which imports
   * `../api/auth` and `../api/client`, so the dependency it claimed to be avoiding is
   * present by another route. The decision is unchanged and its reason is restated: a
   * SCREEN knows when it painted and the frame does not, so the instant belongs to the
   * screen; reading it here would also call `ui/src/hooks/useServerInstant.ts` once for the
   * frame rather than once per screen, and would re-render the whole frame on a clock change
   * no mounted screen had asked for. Omitting the value is legitimate and `ScreenHeader`
   * degrades to the browser clock, which that module records as a registered divergence.
   */
  readonly now?: Date | undefined;
  /**
   * Whether the delegating screen has a write in flight, which holds the frame's own
   * sign-off control shut for the duration.
   *
   * Assumptions: a 3270 keyboard LOCKS from the moment a turn is transmitted until the
   * region replies, so the reference could not accept any input at all mid-turn -- not the
   * screen's own keys and not a control belonging to the surrounding chrome, because there
   * was no surrounding chrome to belong to. A browser has no such lock, and the two writing
   * screens that publish this member disable their own keys and inputs for the turn
   * (`ui/src/screens/billPay/index.tsx`, `ui/src/screens/userAdd/index.tsx`); the sign-off
   * control is the one remaining way off those screens while a payment or a user creation is
   * still committing, and it is the most damaging one, because it discards the session
   * locally while the request that already carries its token completes at the service.
   *
   * Trade-offs: the lock is published by the SCREEN rather than inferred by the frame, which
   * means a mutating screen that omits it keeps the old behaviour. Inferring it was the
   * alternative and there is nothing to infer it from -- the shell sees a message, an
   * identity and a legend, none of which distinguishes a read from a write -- so the frame
   * would have had to reach into each screen's transport state to find out. Publishing it
   * alongside the three zones the screen already delegates keeps the direction of knowledge
   * the same as every other member here.
   *
   * Assumptions: omitting the member means "not writing", so the control behaves exactly as
   * it did for every screen that does not publish it. That keeps this additive rather than a
   * change every screen has to opt out of.
   */
  readonly busy?: boolean | undefined;
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

/**
 * The empty legend passed to `PfKeyBar` when no screen has delegated its keys.
 *
 * Assumptions: one frozen instance rather than a fresh `[]` per render, for the reason
 * `ui/src/hooks/useAuth.ts` gives for its own shared empty array - a new array each render is a
 * new prop identity, which defeats any memoisation downstream of it for a value that never
 * differs.
 */
const NO_LEGEND_KEYS: readonly PfKeyBinding[] = Object.freeze([]);

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
 * Reports whether two message slots would paint the same row-22 and row-23 lines.
 *
 * ⚠️ Refactoring Rationale: the nested row-22 line is part of this comparison, and it was left out
 * when {@link ShellMessageSlot.information} was added. The omission is not a loose comparison, it is
 * a DISCARD: the comparison is what decides whether the frame adopts a newly published slot at all,
 * so a slot whose row-23 half is unchanged and whose row-22 half has advanced was judged identical
 * to the one already rendered and thrown away. The operator then reads the PREVIOUS advisory beside
 * the current outcome. The account-update sequence is exactly that shape — the row-22 line advances
 * from `Changes validated.Press F5 to save` to `Changes committed to database`
 * (`app/cbl/COACTUPC.cbl` L473 and L475, both `WS-INFO-MSG`) across a turn that leaves row 23
 * untouched — so the stalest possible line is the one confirming a write.
 *
 * Assumptions: the nested slot is compared member by member rather than by object identity, for the
 * same reason the row-23 members are: `useShellSlot` is called with an object literal in every
 * screen's render body, so identity changes on every render while the rendered text usually does
 * not. Comparing by identity would make the comparison always false and defeat its purpose.
 *
 * Assumptions: `severity` is compared as well as `text`, because the band resolves colour and ARIA
 * role from it — `ui/src/layout/MessageBand.tsx` gives the band `alert` or `status` according to
 * severity — so a screen that keeps its advisory wording and raises its severity has changed what is
 * announced, not merely how it looks.
 *
 * Assumptions: an absent nested slot and a present one carrying no text are NOT collapsed here. They
 * differ in what the frame paints — the band module decides that — and this function's only job is
 * to report whether the two publications would paint the same thing, so it defers by comparing the
 * members it was given rather than normalising them first.
 * @param {ShellMessageSlot | undefined} previous - Message currently rendered.
 * @param {ShellMessageSlot | undefined} next - Message just published.
 * @returns {boolean} `true` when both lines' text, severity and the mapset all match.
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
    previous.mapset === next.mapset &&
    areInformationLinesEquivalent(previous.information, next.information)
  );
}

/**
 * Reports whether two row-22 advisory slots would paint the same line.
 *
 * Refactoring Rationale: this is a function of its own rather than two more conjuncts inside
 * {@link areMessagesEquivalent}, because the nested slot is optional and so needs the same
 * absent-versus-present handling the outer slot already has at its head. Inlining it would have put
 * a second `undefined` branch in the middle of a boolean expression, where the `previous ===
 * undefined || next === undefined` case cannot be expressed as a conjunct at all.
 * @param {ShellInformationSlot | undefined} previous - Advisory currently rendered.
 * @param {ShellInformationSlot | undefined} next - Advisory just published.
 * @returns {boolean} `true` when both slots are absent, or both carry the same text and severity.
 */
function areInformationLinesEquivalent(
  previous: ShellInformationSlot | undefined,
  next: ShellInformationSlot | undefined,
): boolean {
  if (previous === undefined || next === undefined) {
    return previous === next;
  }
  return previous.text === next.text && previous.severity === next.severity;
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
    // WHY : Refactoring Rationale: `busy` and `risk` are part of this comparison because they are
    //       part of the RENDERED legend -- `ui/src/layout/PfKeyBar.tsx` paints the in-flight
    //       affordance from the first and the control's emphasis from the second. A member left out
    //       here is a member whose change never notifies a subscriber, so the shell would keep the
    //       snapshot it already had and the new affordance would reach the screen only if some other
    //       member happened to change in the same commit. That failure mode is worse than a stale
    //       label: the whole point of the busy member is to appear the instant a turn starts, and
    //       the screen that publishes it re-renders itself without re-rendering this frame, so
    //       omitting it here would leave the affordance permanently invisible on all 21 screens
    //       while every unit test of the bar in isolation kept passing.
    if (
      before.aid !== after.aid ||
      before.action !== after.action ||
      before.label !== after.label ||
      before.enabled !== after.enabled ||
      before.busy !== after.busy ||
      before.risk !== after.risk
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
 * Milliseconds in the smallest unit the title band actually displays.
 *
 * Assumptions: one second, because {@link ScreenHeader}'s `HEADER_TIME_FORMAT` is `HH:mm:ss` and its
 * `HEADER_DATE_FORMAT` is `MM/DD/YY`, so nothing finer than a second reaches the display. Truncation
 * uses the epoch value directly, which is sound for any zone: every offset in use is a whole number of
 * minutes, so a second boundary in UTC is a second boundary locally.
 */
const HEADER_INSTANT_GRANULARITY_MS = 1000;

/**
 * Reports whether two instants would paint the same header date and time.
 *
 * Refactoring Rationale: compared at the granularity the band DISPLAYS, not by exact milliseconds,
 * which an earlier revision did. `useServerInstant` is a paint-time reading of an anchored monotonic
 * clock - by design, matching `POPULATE-HEADER-INFO`, which re-read the clock on every `SEND MAP` - so
 * it answers a different millisecond on every render of the screen that publishes it. Under an
 * exact-millisecond comparison every one of those commits counted as a visible difference, so the
 * snapshot was replaced and every subscriber notified on each keystroke of a form: the whole frame -
 * title band, message line and key legend - re-rendered to display a time that formatted identically.
 * At second granularity the gate lets through only a change the operator could actually see.
 *
 * Assumptions: this is a wasted-work correction and NOT a fix for a render cycle, and the distinction is
 * worth stating because the shell renders the very screen that publishes to it, which looks like a
 * cycle. It is not one, and the reason is the element IDENTITY of the body rather than anything this
 * comparison does. In production the body is `<Outlet />`, and the element it returns is the one the
 * route context holds - built once when `ui/src/router.tsx` created the route table - so a re-render of
 * this shell does not rebuild it, and React compares a referentially identical element with identical
 * props and bails out of that subtree. In an isolated render the same holds for the `children` element
 * the caller built once. `appShellIntegration.test.tsx` bounds the screen's render count, which is what
 * would fail if a change rebuilt the body element on each shell render - cloning it, or spreading it
 * into a new one to add a prop - because that turns the wasted work into an unbounded loop.
 *
 * ⚠ Refactoring Rationale: this paragraph said `children` is "an element built by `ui/src/App.tsx`".
 * That file builds no such element and mounts no shell; the body arrives through the layout route's
 * outlet. The mechanism the paragraph relies on is unchanged, so only the source of the element is
 * corrected.
 *
 * Alternatives Considered: memoising the instant in each screen so the same `Date` is republished until
 * something else changes. Rejected because it moves a shell-internal concern into the twenty
 * delegating call sites, and because it would make the displayed time a mount-time reading rather than a
 * paint-time one, which is
 * the fidelity property the clock hook exists to provide. Excluding `now` from the comparison entirely
 * was also rejected: the band would then never repaint its clock at all.
 *
 * Trade-offs: two instants in the same second are treated as equal, so the band can lag the true second
 * by less than one render pass. That is invisible - the value it would have shown formats identically.
 * @param {Date | undefined} previous - Instant currently rendered.
 * @param {Date | undefined} next - Instant just published.
 * @returns {boolean} `true` when both would render the same date and time, or both are absent.
 */
function areInstantsEquivalent(previous: Date | undefined, next: Date | undefined): boolean {
  if (previous === undefined || next === undefined) {
    return previous === next;
  }
  return (
    Math.floor(previous.getTime() / HEADER_INSTANT_GRANULARITY_MS) ===
    Math.floor(next.getTime() / HEADER_INSTANT_GRANULARITY_MS)
  );
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
    areInstantsEquivalent(previous.now, next.now) &&
    // WHY : Assumptions: the write-in-flight flag is part of this comparison because it is part of
    //       the RENDERED output -- it decides whether the frame's sign-off control is disabled. A
    //       member left out here is a member whose change never notifies a subscriber, so the shell
    //       would keep the snapshot it already had and the lock would take effect only if some other
    //       member happened to change in the same commit. Both sides are normalised with `?? false`
    //       so an omitted member and an explicit `false` compare equal and neither produces a
    //       notification the rendered frame cannot show.
    (previous.busy ?? false) === (next.busy ?? false)
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
 * Assumptions: the delegation contract is opt-in, and every one of the twenty-one screens
 * delivered under `ui/src/screens/**` opts in - each publishes its transaction identifier,
 * program name, message and resolved key bindings here, and none composes a `ScreenHeader`,
 * a row-23 `MessageBand` or a `PfKeyBar` of its own. FIVE screens keep a band inside their body
 * -- the account view, the account update, the card detail, the card update and the reference-type
 * edit -- and each one is the INFORMATIONAL field its mapset declares separately from the row-23
 * error line this shell owns, at row 22 on the two account mapsets and row 20 on `COCRDSL`;
 * `ui/src/screens/accountView/index.tsx` documents that split at its own render site.
 *
 * Assumptions: the paint instant is the ONE member not published by every screen. Twenty screens
 * delegate it; the authorization detail does not, because its own service contract renders
 * `currentDate` and `currentTime` and the screen shows the service's values -- so delegating an
 * instant as well would paint two clocks on one screen. That single exemption is asserted to be
 * the only one, in both directions, by `ui/src/layout/screenHeaderClock.test.tsx`.
 *
 * Refactoring Rationale: opt-in is what it is for a reason worth keeping even now that every
 * screen opts in. Publication is the signal a zone is wanted, so a shell that painted its
 * bands unconditionally would render a second message line and a second legend on any screen
 * that still composed its own - two live regions announcing one message, and a duplicate
 * `message-band` test handle where a caller expects one. Keeping the zone conditional on the
 * publication is also what lets a single screen withhold one deliberately, and the two card
 * screens withhold on different grounds. Both publish an empty key list and no header WHILE A
 * READ IS IN FLIGHT: that state has no counterpart in the reference at all, so a title band
 * whose clock and identifiers described a record not yet read would be an invention. The card
 * UPDATE screen additionally withholds for an unaddressable selector, which is the state
 * `app/cbl/COCRDSLC.cbl` L838-L848 answers with `SEND TEXT ... ERASE` rather than by re-sending
 * the map; the card DETAIL screen publishes its whole slot in that state instead, because its
 * own arrival path is the map the reference SENDS to gather selection criteria.
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
  const { screen, message, pfKeys, now, busy } = slot;
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
      publishShellSlotFor(owner, { screen, message, pfKeys, now, busy });
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
   * ⚠️ Assumptions: the production mount does NOT use this member, and an earlier revision of this
   * note said it did -- that `ui/src/App.tsx` renders `<AppShell><CardDemoRouter /></AppShell>` so
   * the body region is a route tree passed here. It does not: `App.tsx` renders a single
   * `<RouterProvider router={cardDemoRouter} />` and mounts no shell, and `ui/src/router.tsx` declares
   * the frame as `element: <AppShell />` on TWO SIBLING layout routes -- one holding the public screen
   * and one the guarded subtree -- so in production the body region is react-router's `Outlet` and this
   * member is absent. The distinction is not cosmetic: the outlet form is what lets sign-on keep the
   * frame while sitting OUTSIDE the guarded subtree, and what lets the boundary in
   * `ui/src/layout/ShellContentBoundary.tsx` sit between each frame and the routed screen, neither of
   * which a `children` mount above the whole table can express. Both arrangements are
   * asserted against the source text by `ui/src/layout/appShellIntegration.test.tsx`, which requires
   * the layout-route form in the router and NO shell element in `App.tsx` at all, and counted there as
   * two sibling mounts with none nested inside another.
   *
   * Assumptions: the member is kept because a test renders the frame without standing up a router,
   * which is what keeps the shell's own suites from depending on the route table. A value here
   * replaces the outlet rather than sitting beside it.
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
  /**
   * Whether a write is in flight, overriding any value delegated by the mounted screen.
   *
   * Assumptions: this mirrors the four members above so an isolated render can exercise the
   * locked chrome without standing up a screen and a transport to produce the state. See
   * {@link ShellSlot.busy} for what the lock is for and why the screen owns the decision.
   */
  readonly busy?: boolean | undefined;
  /*
   * WHY : ⚠️ Refactoring Rationale: there is NO `ownsFunctionKeys` prop here any more, and the prop this
   *       note replaces was a correct diagnosis with a weaker remedy. It let a mount site declare whether
   *       the shell might bind its own sign-off function key, because `usePfKeys` installs one document
   *       listener per call site with no arbitration and PF12 is `Cancel` on account update, card update
   *       and user update -- so a shell that also bound PF12 would end the session on the keystroke that
   *       cancels an edit. That diagnosis is exactly right. The remedy adopted instead is that the shell
   *       installs no keyboard listener AT ALL and offers sign-off as a rendered control, which makes the
   *       collision impossible rather than avoidable: a gate has to be passed correctly at every mount
   *       site, and this one was already omitted at the production table in `ui/src/router.tsx` while two
   *       isolated test renders passed it. See {@link SHELL_SIGN_OFF_LABEL} for what the rendered control
   *       costs and what it does not.
   * WHY : Assumptions: the property the prop existed to protect is asserted from the outside rather than
   *       configured -- `routerReachability.test.tsx > production route table > paints exactly one
   *       function-key legend around a screen` renders the production table at `/menu`, one of the three
   *       screens that owns its own legend, and asserts one legend region and no `F12=Sign off` control.
   *       That holds unconditionally under this design and held only for a correctly-configured mount
   *       site under the other.
   */
}

/**
 * Renders the persistent four-zone frame around whichever screen is mounted.
 *
 * Purpose: paint the title band, the screen body, the message line and the function-key
 * legend in the order the baseline always showed them, so that all 21 migrated screens
 * inherit one frame instead of each rebuilding it. Zone content comes from the mounted
 * screen's delegation - see {@link useShellSlot} - or from this component's own props,
 * which win where both are present. A zone reached by neither stays unpainted, which is how
 * the authorization detail screen - the one screen that delegates no paint instant, because
 * its own service contract supplies the date and time it shows - avoids getting a second clock.
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
 * @returns {ReactElement} The four-zone frame, or the sign-off surface while the operator
 *   remains on the history entry they signed off at with no session held; a navigation away
 *   restores the frame, which is what lets them sign on again. See
 *   {@link SHELL_SIGN_OFF_LABEL} for why the sign-off control is not a function key.
 */
export function AppShell(props: AppShellProps): ReactElement {
  const { children, screen, message, pfKeys, now, busy } = props;

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
   * Assumptions: `auto` and the three token references are the whole of this override, and
   * every one of them is admissible. `height: auto` lets the band grow to its own content,
   * and `auto` is one of the values the zero-hardcoded-values rule exempts by name.
   * `lineHeight` comes from the theme's own base line-height token, which is what neutralises
   * the inherited 64px line box - without it each row would occupy a 64px line and the band
   * would merely overflow further down. The vertical padding comes from the spacing scale
   * through `ui/src/theme/tokens.ts`, so the band breathes without a pixel literal. The
   * horizontal padding antd already applies is deliberately left alone.
   *
   * Refactoring Rationale: the background is now stated, and stating it is an ACCESSIBILITY
   * correction rather than a styling preference. `Layout.Header` fills with the design
   * system's own dark navy by default, and the BMS bridge maps the mapsets' dominant
   * `COLOR=BLUE` - 289 measured occurrences - onto the primary role, so every label, value
   * and link this band carried measured 4.49:1 against that fill where WCAG AA asks 4.5:1
   * for normal text. An earlier revision recorded the shortfall and shipped it, on the ground
   * that the deficient half was a component default and correcting it here would fork the
   * palette. The measurement was right and the conclusion was wrong: the same text token
   * measured three different ratios in the three zones, so no token map could fix it while
   * the backgrounds disagreed. Painting all three zones with `SURFACE_TOKENS.screen` gives
   * the whole frame one measurable surface, which is what lets `BMS_TEXT_COLOR_TOKENS` be
   * verified once - at 6.16:1 for this band's text - and hold in every zone.
   *
   * Alternatives Considered: leaving the navy in place and correcting the text shade against
   * it. Rejected on the measurement above: `ui/src/theme/antdTheme.ts` rejects a dark surface
   * outright on the ground that no requirement asks for one, and the fill the design system
   * ships is `headerBg: '#001529'` -- a value no mapset declares. See {@link SURFACE_TOKENS}
   * for that rejected alternative and {@link BMS_TEXT_COLOR_TOKENS} for the measured pairs.
   *
   * Trade-offs: the dark band is given up, and nothing transcribed goes with it. It is a
   * design-system default and not a measured source value: the 3270 screens paint no header
   * fill at all, they paint coloured text on one uniform display, so a uniform surface is
   * closer to the baseline than the default was. The value written here is a token reference
   * rather than a colour, so the band still follows the theme.
   */
  /*
   * Refactoring Rationale: every zone of the frame now declares the SAME horizontal inset,
   * and it is declared here because the four zones disagreed about it before. Measured in a
   * browser across all 17 reachable routes: the design system insets its header and its
   * footer by a fixed 50 pixels of its own, the content region had no inset at all, and the
   * message line had none either - so at 375, 576 and 768 the content ran edge to edge with
   * input borders flush against the literal viewport edge while the key legend sat 50 pixels
   * in, at 992 the content column was inset LESS (41.3) than the legend (50), and the row-23
   * band started at x=0 on every route and visibly hung off the left edge of the frame
   * whenever it carried a sentence. One declaration applied to every zone is what makes the
   * four left edges one left edge, at every width, which is the property the fixed 80-column
   * grid had for free.
   *
   * Assumptions: the value is the spacing bridge's large step rather than the design system's
   * own 50, and the difference is not cosmetic - 50 is a component-stylesheet constant that
   * `ui/src/theme/tokens.ts` does not name, so matching it in the two zones that lack it
   * would have meant writing a pixel literal into three more places. Naming a token instead
   * moves all four zones onto one auditable value and keeps the zero-hardcoded-values rule
   * intact. The inset shrinks from 50 to the large spacing step, which the baseline has no
   * opinion about: the terminal's column 1 IS the display edge, so any browser gutter is
   * additive and only its uniformity is a fidelity question.
   */
  const zoneInlinePadding: CSSProperties = {
    paddingInline: cssVar[SPACING_TOKENS.controlPaddingLarge],
  };

  const headerStyle: CSSProperties = {
    height: 'auto',
    lineHeight: cssVar.lineHeight,
    paddingBlock: cssVar[SPACING_TOKENS.controlPaddingCompact],
    background: cssVar[SURFACE_TOKENS.screen],
    ...zoneInlinePadding,
  };

  /*
   * Assumptions: the body and legend zones take the same surface as the header, for the
   * reason argued there - one surface is what makes one contrast measurement the whole
   * answer. The design system fills the frame and its footer with the layout grey by
   * default, against which the same text tokens measure lower than they do here.
   */
  const zoneStyle: CSSProperties = { background: cssVar[SURFACE_TOKENS.screen] };

  /*
   * Refactoring Rationale: the frame is now BOUNDED to one viewport and clips its own overflow,
   * where it previously grew with its content. A browser measurement is what forced this: on a
   * screen taller than the display the pinned zone was found painting OVER the screen body at
   * `scrollY 0` - on the card update screen `document.elementFromPoint` at the middle of the
   * information band returned an element the band did not contain, so the "Changes committed to
   * database" confirmation was completely invisible until the operator scrolled a further 52
   * pixels, and on the authorization summary the zone covered the first row of the table.
   *
   * Assumptions: the cause is not the zone's declaration but its containing block. A sticky
   * element is shifted within its containing block to stay in the scrollport, and it OVERLAPS
   * whatever shares that space rather than displacing it. While the frame grew with the content
   * the containing block was the whole document, so the zone floated at the viewport's block end
   * for the entire scroll and there was always body content underneath it. Bounding the frame to
   * the viewport and moving the scroll INTO the content region makes the zone an ordinary flex
   * item at the end of a column exactly one viewport tall: it can no longer be shifted anywhere,
   * so there is no position from which it can overlap.
   *
   * Alternatives Considered: adding block-end padding to the content region was rejected because
   * it only clears the zone at the very END of the scroll - the measured failures were at
   * `scrollY 0`, where padding below the content is nowhere near the overlap. Making the zone
   * `position: fixed` was rejected for the same reason it fails as sticky: fixed also takes the
   * element out of flow, so it still has no reserved space. Publishing a spacer from each screen
   * was rejected outright as a lie about where the geometry lives - eighteen screens would each
   * have to know this frame's zone height.
   *
   * Trade-offs: the DOCUMENT no longer scrolls; the screen body does. That is the more faithful
   * arrangement rather than merely an acceptable one - the reference display is exactly 24 rows
   * with rows 22 to 24 fixed, and a terminal never scrolled its function-key legend off the
   * glass. It does mean a caller measuring `window.scrollY` reads zero on a long screen and must
   * read the content region's own `scrollTop` instead, which is why this is recorded here.
   */
  const frameStyle: CSSProperties = {
    ...zoneStyle,
    maxBlockSize: '100dvh',
    overflow: 'hidden',
  };

  /*
   * Assumptions: the content region carries the shared inset and the surface, and nothing
   * else. Its vertical padding is the design system's own, because the reference says nothing
   * about the distance between row 3 and row 4 that a browser could honour.
   *
   * ⚠️ Refactoring Rationale: it additionally carries the growth, the scroll and a zero block
   * minimum, which together make it the frame's one scrolling region - see {@link frameStyle}
   * for the measured defect that motivated it. `minBlockSize: 0` is not optional and is the part
   * a reader is most likely to delete as redundant: a flex item's automatic minimum size is its
   * content, so without the override this region would refuse to shrink below its own body, the
   * frame would overflow its bound, and the zone would be pushed off the display entirely -
   * trading an occluded confirmation for an unreachable legend. `1` and `0` are a flex factor
   * and a floor rather than design values, so neither resolves to a token by nature.
   */
  const contentStyle: CSSProperties = {
    ...zoneStyle,
    ...zoneInlinePadding,
    flex: 1,
    minBlockSize: 0,
    overflowY: 'auto',
  };

  /*
   * Refactoring Rationale: the body column is BOUNDED and centred, where it used to be a
   * responsive grid column - `span={24} lg={22}` - that was neither. Two measured
   * consequences: at roughly 1848 pixels the content stayed anchored at x=78 and left the
   * right half of the display empty, and because the 22-of-24 column is a PROPORTION its
   * inset grew with the viewport (41.3 at 992, 50 at 1200, 53.3 at 1280, 66.7 at 1600) while
   * the legend's inset stayed fixed - so the same frame had two different left edges that
   * only agreed at one width. A maximum measure plus centring gives a constant gutter at
   * every width above it and the full width below it.
   *
   * Assumptions: the bound is the design system's LARGE SCREEN token and not a chosen pixel
   * count, so the widest line of a migrated record view is the width the bridge already
   * nominates as the point at which a layout is "large". `ui/src/theme/tokens.ts` declares
   * exactly two breakpoints, medium and large, and no third one is invented here.
   *
   * Assumptions: this token resolves to a NUMBER rather than to a `var(--…)` reference, and
   * that is a property of the design system rather than an inconsistency here. Its theme keeps
   * the whole `screen*` family on a preserve list - they have to be usable inside media
   * queries, where a custom property cannot appear - so `cssVar.screenLG` hands back 992 and
   * React appends the unit. Every other token this file reads is a reference; this one cannot
   * be, and the difference is recorded so a reader does not "fix" it.
   *
   * Trade-offs: a bounded measure means a very wide display shows empty margins rather than a
   * stretched record view. That is the intended trade: the alternative measured above puts a
   * one-character input 723 pixels wide at 1600, which is the same defect the fixed grid
   * avoided by construction. `maxInlineSize` rather than `inlineSize` keeps every width below
   * the bound unchanged, so no narrow viewport pays for it.
   */
  const contentColumnStyle: CSSProperties = {
    maxInlineSize: cssVar[BREAKPOINT_TOKENS.large],
    inlineSize: '100%',
  };

  /*
   * Refactoring Rationale: the message line and the key legend are PINNED to the bottom of
   * the viewport, and this is the whole of the fix for the frame's largest measured defect.
   * Both were ordinary in-flow siblings, so any screen taller than the viewport pushed them
   * off the bottom of it: measured across 21 screens at six widths, the band and the legend
   * were both below the fold on 8 screens at 768 and above and on 13 at 375, and on
   * `/account/update` at 375 the legend sat 2,379 pixels below the fold. The reference cannot
   * express that state at all - rows 23 and 24 exist on every one of the 17 base mapsets and
   * a 24-row display has no scroll - so an operator could always see the outcome of a turn
   * and the keys available for the next one. AAP section 0.4.4 states the same requirement.
   *
   * Alternatives Considered: `position: fixed`, which is the more familiar way to pin a bar.
   * Rejected because a fixed element is out of flow, so it reserves no space and the last row
   * of a screen's content renders UNDERNEATH it - which trades a band the operator cannot see
   * for content they cannot see, and needs a hand-maintained spacer of exactly the pinned
   * height to avoid. `position: sticky` keeps the element in flow, so its space is reserved by
   * construction and the reservation cannot drift from the height it reserves for.
   *
   * Assumptions: sticky positioning needs a scrolling ancestor and a containing block taller
   * than itself, and both hold here: `ui/index.html` sizes the mount point to one viewport and
   * the design system's `.ant-layout { flex: auto }` stretches this frame to fill it, so the
   * document is what scrolls and this element's containing block is the frame.
   *
   * Assumptions: the surface is painted on the pinned zone itself. A sticky element overlaps
   * whatever is above it in flow while the page is scrolled, so a transparent zone would show
   * the screen's own content sliding through the band - which is why the fill is stated here
   * as well as on the footer inside it.
   *
   * Trade-offs: the stacking order is one below the design system's popup base rather than a
   * value of its own. The zone has to paint above the screen body - including a table's own
   * sticky columns, which the design system gives a low positive index - and below every
   * overlay, because a dialog or a popconfirm that opened UNDER the pinned band would be the
   * defect this fix introduced. Deriving it from the popup token in a `calc` keeps both
   * relationships true if the theme moves the token, where a literal would fix only today's.
   */
  const pinnedZoneStyle: CSSProperties = {
    ...zoneStyle,
    ...zoneInlinePadding,
    position: 'sticky',
    insetBlockEnd: 0,
    zIndex: `calc(${String(cssVar.zIndexPopupBase)} - 1)`,
  };

  /*
   * Refactoring Rationale: the shared inset is declared on the PINNED ZONE rather than on the
   * three lines it holds, and that is what finally makes the frame's left edge one edge. The
   * row-22 and row-23 bands are children of this zone and declared no inset of their own -
   * measured at x=0 on every one of the 17 reachable routes, so a populated row-23 band hung
   * off the left edge of a frame whose heading sat at x=53, whose legend sat at x=50 and whose
   * row-22 band sat at x=53. Insetting each line separately would have meant three more places
   * to keep in step with {@link zoneInlinePadding}; insetting their common parent means the
   * band, the advisory line and the legend cannot disagree by construction.
   *
   * Assumptions: the legend's own horizontal padding is therefore ZEROED rather than set to the
   * shared value, because the zone already applies it - stating it twice would inset the legend
   * by two gutters and put a fourth left edge in the frame. `0` is one of the values the
   * zero-hardcoded-values rule exempts by name, so this is not a pixel literal creeping in; the
   * design system's own `padding: 24px 50px` on `Layout.Footer` is what has to be displaced,
   * and only an explicit declaration displaces a component stylesheet.
   *
   * Refactoring Rationale: the vertical half is the compact spacing step rather than the
   * inherited 24. The zone is now pinned, so every pixel of its height is height the screen
   * body gives up on every screen, and the reference gives row 24 one text row of the
   * twenty-four.
   */
  const footerStyle: CSSProperties = {
    ...zoneStyle,
    paddingBlock: cssVar[SPACING_TOKENS.controlPaddingCompact],
    paddingInline: 0,
  };

  /*
   * Assumptions: the skip link is TEXT and therefore resolves through `BMS_TEXT_COLOR_TOKENS`
   * like every other sentence in this tree, not through the link token the design system
   * would otherwise apply. `ui/src/theme/antdTheme.ts` pins the link seed to the measured
   * blue anchor deliberately - so a turquoise informational seed cannot drag the link ramp
   * with it - and states in the same place that those seeds govern fills, borders,
   * backgrounds and icons because no consumer paints either name as text. This element was
   * the one consumer that did: the design system's link component resolves the anchor
   * itself, which measured 4.10:1 against the surface this shell paints where WCAG AA asks
   * 4.5:1 for normal text. Naming the text-grade shade here restores the invariant the theme
   * file already claims rather than moving a seed and re-deriving thirteen tokens to reach
   * one element.
   * Alternatives Considered: moving the link seed to the text-grade shade. Rejected because
   * the seed feeds a nine-shade ramp plus hover and active states that fills and borders also
   * read, so a change made for one sentence would move surfaces no measurement asked to move.
   * ⚠️ Refactoring Rationale: the trade-off recorded here said the underline still comes from the
   * component and that only the hue is overridden. The second half was true and the first was not:
   * the design system ships `linkHoverDecoration: none`, so there was no underline to inherit, and
   * because an inline colour outranks a stylesheet rule the override also displaced the component's
   * own hover and active colours. Measured consequence: on all fifteen rendered screens the link's
   * hover and active renderings were byte-identical to its resting one, so the only state a pointer
   * user could perceive was focus. The states are therefore declared here as well, since the
   * declaration that broke them is the one that has to restore them.
   */
  const [skipLinkState, setSkipLinkState] = useState<'rest' | 'hover' | 'press'>('rest');

  /*
   * Assumptions: the three states are held in React state because a CSS pseudo-class cannot be
   * expressed in an inline style, and the inline style is what carries the contrast-safe hue. The
   * handlers are pointer events rather than mouse events so a touch or pen contact reaches the same
   * three states.
   *
   * Alternatives Considered: giving the link back to the design system by deleting the override,
   * which is the smallest possible edit and was rejected on measurement. The component resolves the
   * link anchor itself, which measures 4.10:1 against the surface this shell paints where WCAG AA
   * asks 4.5:1 - a separate finding records that exact number as a violation - so restoring hover
   * by deleting the hue would trade a missing state for an unreadable resting one.
   *
   * Alternatives Considered: hovering to a LIGHTER shade of the same ramp, which is the design
   * system's own convention and what its link tokens do. Rejected because every lighter step
   * measures worse than the resting shade against this surface, so the conventional direction is
   * the one direction this element cannot take.
   *
   * Assumptions: hover therefore adds an UNDERLINE at the resting hue - a non-colour channel, so it
   * costs no contrast and is perceptible to a reader who cannot distinguish the hue at all - and
   * press moves to the base text grade, which is both the darkest token available and unmistakably
   * different from the blue. Both values are token references, and `underline` and `none` are
   * keywords rather than design values, so nothing here is a literal the theme cannot follow.
   */
  const skipLinkStyle: CSSProperties = {
    color:
      skipLinkState === 'press'
        ? cssVar[BMS_TEXT_COLOR_TOKENS.DEFAULT]
        : cssVar[BMS_TEXT_COLOR_TOKENS.BLUE],
    textDecorationLine: skipLinkState === 'rest' ? 'none' : 'underline',
  };

  /**
   * Marks the skip link hovered, so its hover rendering can differ from its resting one.
   * @returns {void} Nothing; the recorded state drives {@link skipLinkStyle}.
   */
  const skipLinkHovered = useCallback(
    /**
     * Records the pointer state this handler stands for.
     * @returns {void} Nothing; the recorded state drives {@link skipLinkStyle}.
     */
    function recordSkipLinkHovered(): void {
      setSkipLinkState('hover');
    },
    [],
  );

  /**
   * Returns the skip link to its resting rendering when the pointer leaves it.
   * @returns {void} Nothing; the recorded state drives {@link skipLinkStyle}.
   */
  const skipLinkRested = useCallback(
    /**
     * Records the pointer state this handler stands for.
     * @returns {void} Nothing; the recorded state drives {@link skipLinkStyle}.
     */
    function recordSkipLinkRested(): void {
      setSkipLinkState('rest');
    },
    [],
  );

  /**
   * Marks the skip link pressed while a pointer is held down on it.
   *
   * Assumptions: the pressed rendering is released by the hover handler on pointer up rather than by
   * a handler of its own, because a pointer that is still over the element after a release is
   * hovering it - and a release that happens elsewhere fires the leave handler instead.
   * @returns {void} Nothing; the recorded state drives {@link skipLinkStyle}.
   */
  const skipLinkPressed = useCallback(
    /**
     * Records the pointer state this handler stands for.
     * @returns {void} Nothing; the recorded state drives {@link skipLinkStyle}.
     */
    function recordSkipLinkPressed(): void {
      setSkipLinkState('press');
    },
    [],
  );

  /*
   * Assumptions: the sign-off surface takes the same fill and, unlike the frame, needs its own
   * growth declaration. The design system's layout primitive sets `flex: auto` on itself and
   * the flex primitive used here does not, so without `flex: 1` the erased surface would
   * collapse to the height of its one sentence inside a mount point sized to the viewport -
   * which is the collapse the alignment note below records having measured once already.
   * `1` is a flex factor rather than a design value, so it resolves to no token by nature.
   */
  const signOffStyle: CSSProperties = { ...zoneStyle, flex: 1 };

  /*
   * Refactoring Rationale: this component no longer sizes the frame to the viewport, and the
   * declaration it used to carry was `minHeight: '100dvh'` - the one unresolved literal in
   * the file, flagged as such. A browser measurement showed that pairing produced a permanent
   * 16px vertical scroll, because a document keeping the user agent's default `body { margin:
   * 8px }` adds 8px above and below a frame exactly one viewport tall. Both halves of that
   * defect belong to the document rather than to a component any document may mount, so both
   * moved to `ui/index.html`: it resets the margin and sizes the mount point to one viewport,
   * and the design system's own `.ant-layout { flex: auto }` then stretches this frame to
   * fill it. The fidelity property that motivated the original declaration - the key legend
   * sitting at the bottom edge of the display, as row 24 always did - is preserved by that
   * arrangement, and the literal is now at the layer that owns viewport sizing.
   */

  const delegated = useSyncExternalStore(
    subscribeToShellSlot,
    getShellSlotSnapshot,
    // Assumptions: the same reader serves as the server snapshot. It returns a module
    // value and touches no browser API, so a tree rendered without a DOM reads the empty
    // slot rather than throwing - matching how `ui/src/hooks/useAuth.ts` supplies its own
    // third argument.
    getShellSlotSnapshot,
  );
  const { isAdmin, signedOn, signOut } = useAuth();

  /*
   * Assumptions: this record is NOT the session state the file overview forbids, and the
   * distinction is worth drawing because a reader auditing that prohibition will find a
   * `useState` here and has to be able to tell the two apart. What the prohibition rules
   * out is continuity that substitutes for the communication area - a remembered next
   * program, a last map, a re-entry discriminator, or any assertion about who the operator
   * is. This holds none of those: it records only WHERE sign-off happened, so the
   * cleared-screen surface stays rendered instead of flickering back to the frame. It
   * carries no identity, grants no authority, is never read by anything but the branch
   * below, and is discarded on reload - after which the operator is anonymous because the
   * token is gone, not because this value was remembered.
   *
   * ⚠️ Refactoring Rationale: this holds the HISTORY ENTRY the acknowledgement belongs to
   * rather than a boolean, because the boolean was a one-way latch and this shell is the
   * layout route above the sign-on route as well as above the guarded subtree --
   * `ui/src/router.tsx` declares `SIGN_ON_ROUTE` inside it. Once latched, the branch below
   * returned before the outlet was reached, so every route nested under this frame stopped
   * rendering: an operator who signed off could not sign on again, because the sign-on screen
   * is one of the routes the latch was suppressing, and only a full reload cleared it.
   * Recording the entry makes the surface terminal for that one entry and no further -- the
   * next navigation is a different entry, so the frame and its outlet come back.
   *
   * ⚠️ Refactoring Rationale: the entry recorded is now the entry sign-off NAVIGATES TO,
   * where it used to be the entry sign-off happened ON. Signing off replaced the frame
   * without touching the address, so the location went on naming the screen the operator had
   * just left -- measured as `location.pathname === '/admin'` while nothing administrative was
   * mounted -- and a reload of that address bounced through the session guard to sign-on
   * anyway. Sign-off now replaces the address with `SIGN_ON_ROUTE`, which is both truthful and
   * the address the operator is actually going to next, and the acknowledgement is shown on
   * arrival there. `replace` rather than `push`, so the signed-off screen is not left in
   * history behind a discarded session.
   *
   * Assumptions: the ENTRY key is recorded rather than the pathname, because navigating to
   * the same path is a new entry with a new key while the pathname compares equal. The
   * difference is not hypothetical here: the destination IS the sign-on route, so an operator
   * who signs on, is later bounced back to sign-on, would otherwise meet an acknowledgement
   * for a session they had just established.
   *
   * Alternatives Considered: deriving the surface from `signedOn` alone, with no state at
   * all. Rejected because it cannot distinguish "signed off just now" from "never signed
   * on", so every unauthenticated first visit would open on a thank-you for a session that
   * never existed.
   */
  const [signedOffAtEntry, setSignedOffAtEntry] = useState<string | null>(null);

  /*
   * Purpose: record that a sign-off transition has been issued and has not yet arrived, so the
   * session can be held across the one render between the two and released on arrival. See the
   * refactoring rationale on `signOffFromShell` below for the measurement that made this
   * necessary.
   *
   * Assumptions: this is not session state either, for the same reason as the field above -- it
   * records that a transition is in flight and asserts nothing about who the operator is, grants
   * no authority, and is not persisted, so a reload mid-transition simply lands on sign-on with
   * whatever session the token store still holds.
   */
  const [signOffInFlight, setSignOffInFlight] = useState(false);

  /*
   * Assumptions: the location is read for its ENTRY KEY and for nothing else. This shell
   * renders no navigation of its own and reports no route identity -- each screen publishes
   * its own title band and transaction identifier through the slot -- so the pathname is
   * deliberately not consulted.
   */
  const shellLocation = useLocation();
  const currentEntry = shellLocation.key;

  /*
   * WHY : Assumptions: the state is read as a PROPERTY rather than destructured, and typed `unknown`
   *       rather than taken as the router gives it. React Router types `location.state` as `any`,
   *       because any page may write anything onto a history entry, and destructuring an `any` member
   *       spreads that `any` into this file. `signOnEntryReason` is the only reader and narrows it
   *       structurally, so nothing here ever reads a member off an untrusted value.
   */
  const historyEntryReason: string | undefined = signOnEntryReason(
    (shellLocation as { readonly state?: unknown }).state,
  );

  /*
   * Assumptions: the shell holds a navigator for exactly two moves, both of them session
   * boundaries rather than screen navigation: signing off replaces the address with the
   * sign-on route, and the acknowledgement's own control leaves that surface for the framed
   * screen at the same address. Screen-to-screen navigation stays entirely with the screens,
   * so the prohibition the file overview records -- that this frame reports no route identity
   * and renders no navigation of its own -- is unchanged.
   */
  const navigate = useNavigate();

  const activeScreen = screen ?? delegated.screen;
  const activeMessage = message ?? delegated.message;
  const activePfKeys = pfKeys ?? delegated.pfKeys;
  const activeNow = now ?? delegated.now;
  /*
   * WHY : Assumptions: the frame's own sign-off control is held shut while the mounted screen
   *       reports a write in flight, for the reason recorded on {@link ShellSlot.busy} -- the
   *       terminal's keyboard lock covered every control on the display, and this is the only
   *       control on these screens the screen itself cannot reach. `?? false` rather than a
   *       truthiness test on the member, so a screen that publishes nothing is indistinguishable
   *       from one that publishes `false` and neither is treated as locked.
   */
  const activeBusy = busy ?? delegated.busy ?? false;

  const signOffFromShell = useCallback(
    /**
     * Ends the session and replaces the frame with the sign-off surface for this entry.
     * @returns {void} Nothing; the surface is rendered on the resulting re-render, and stays
     *   until the operator navigates or a session is established.
     */
    function endSession(): void {
      /*
       * Assumptions: the navigation is issued BEFORE the entry is recorded, and the entry
       * recorded is the one the navigation produces rather than `currentEntry`. React Router
       * mints a fresh key for the replacement entry, and it is not available synchronously
       * here, so the destination is identified by its PATH and the key is captured on arrival
       * by the effect below. Recording `currentEntry` instead would key the acknowledgement to
       * the screen being left, which is the entry that is about to be replaced.
       *
       * ⚠️ Refactoring Rationale: the session is no longer discarded HERE, and the ordering is
       * load bearing. Discarding it first left the guarded screen still mounted with no session,
       * so `RequireSignOn` in `ui/src/routes/guards.tsx` re-rendered and issued a bounce of its
       * own -- measured in a browser as a second history replacement 24 ms after this one,
       * carrying `{ reason: 'session-required', attempted: '/admin' }` and minting a fresh entry
       * key. That overwrote this transition's own reason, so the entry the effect below waits for
       * never arrived and the acknowledgement never entered the DOM for a single frame; it also
       * filed a deliberate sign-off as an interrupted access attempt, which is the opposite of
       * what happened. The discard now happens on arrival, in that effect.
       *
       * Assumptions: the session therefore survives one further render of the screen being left,
       * which grants nothing -- the operator was already authorised for it -- and nothing in
       * between can reach a guarded route, because this transition is already in flight and
       * `replace` leaves no entry behind to go back to.
       */
      setSignOffInFlight(true);
      navigateSafely(
        navigate,
        SIGN_ON_ROUTE,
        { reason: SIGN_OFF_ACKNOWLEDGED_REASON },
        { replace: true },
      );
    },
    [navigate],
  );

  /*
   * Assumptions: the acknowledgement is claimed from the history entry's own state on arrival,
   * and the entry key is then recorded so the surface is bound to that one entry. Carrying the
   * signal in navigation state rather than in a component field is what makes it survive the
   * navigation that sign-off performs -- a `useState` value set before navigating would be read
   * on the destination render too, but nothing would then distinguish the destination entry from
   * any later visit to the same address.
   *
   * Trade-offs: the state stays on the history entry after it has been claimed, so a RELOAD of
   * the sign-on address re-shows the acknowledgement. That is accepted rather than scrubbed: the
   * alternative is a `replace` navigation from inside an effect purely to clear a field, which
   * mints another entry on every arrival, and the acknowledgement is correct for a reload anyway
   * because no session is held. It is never shown over a live session -- the render guard below
   * requires `!signedOn` -- and its own control leaves it in one activation.
   */
  useLayoutEffect(
    /**
     * Binds the sign-off acknowledgement to the entry the sign-off navigation arrived at.
     * @returns {void} Nothing; the entry key is recorded in place when the signal is present.
     */
    function claimSignOffAcknowledgement(): void {
      if (!signOffInFlight || historyEntryReason !== SIGN_OFF_ACKNOWLEDGED_REASON) {
        return;
      }
      /*
       * Assumptions: the three statements are one step and React batches them into a single
       * re-render, so no intermediate state is ever painted. The flag is lowered first so a
       * later arrival at this same address -- a reload, or a guard bounce to sign-on -- cannot
       * re-enter here, and the session is discarded last, on an address no guard protects.
       *
       * Assumptions: gating on the in-flight flag rather than on `signedOffAtEntry !==
       * currentEntry` is what keeps this from firing on any OTHER arrival carrying the same
       * reason, now that arriving here is what performs the discard.
       */
      setSignOffInFlight(false);
      setSignedOffAtEntry(currentEntry);
      signOut();
    },
    [currentEntry, historyEntryReason, signOffInFlight, signOut],
  );

  const crossToMainMenu = useCallback(
    /**
     * Moves an administrator from the administrative surface to the ordinary main menu.
     * @returns {void} Nothing; the router renders the main menu.
     */
    function goToMainMenu(): void {
      /*
       * Assumptions: `push` rather than `replace`, unlike the two session-boundary moves in this
       * file. Crossing between the two menus is ordinary navigation an operator may want to undo
       * with Back, whereas signing off must not leave a signed-off screen reachable behind a
       * discarded session.
       */
      navigateSafely(navigate, MAIN_MENU_ROUTE);
    },
    [navigate],
  );

  const leaveSignOffAcknowledgement = useCallback(
    /**
     * Leaves the sign-off acknowledgement for the framed sign-on screen at the same address.
     * @returns {void} Nothing; the surface is dropped on the resulting re-render.
     */
    function continueToSignOn(): void {
      /*
       * Assumptions: the navigation is issued as well as the field being cleared, and both are
       * needed. Clearing the field alone drops the surface but leaves the claimed acknowledgement
       * on the history entry, so a re-render that re-ran the claim effect would restore it; the
       * `replace` navigation to the same address mints a new entry WITHOUT the signal, which is
       * what makes leaving the surface final. `replace` rather than `push`, so activating this
       * control does not add a history step an operator would have to go back through.
       */
      setSignOffInFlight(false);
      setSignedOffAtEntry(null);
      navigateSafely(navigate, SIGN_ON_ROUTE, undefined, { replace: true });
    },
    [navigate],
  );

  /*
   * Refactoring Rationale: this shell installs NO keyboard listener of its own, where it
   * previously called `usePfKeys` with a sign-off handler whenever no screen had delegated a
   * legend. That condition is not a safe basis for binding a key: `usePfKeys` installs one
   * document listener per call site and all 21 screens call it. The condition is FALSE on every
   * one of them, because all 21 delegate a legend, so the shell would have offered no exit
   * anywhere; and were any screen to stop delegating, the shell's listener would then sit
   * beside that screen's own and both would receive every keypress. Sign-off is a rendered control instead; see {@link SHELL_SIGN_OFF_LABEL} for the
   * per-screen census, what the control costs and what it does not.
   *
   * Assumptions: the legend region is therefore driven entirely by delegation. A screen that
   * publishes its bindings has them painted here and its activations forwarded back through
   * {@link dispatchShellPfKey}; a screen that paints its own legend publishes nothing and this
   * region stays empty, because `PfKeyBar` returns `null` for an empty binding list rather than
   * an empty landmark. The dispatcher is passed unconditionally because it reads the published
   * slot at call time and is a no-op when nothing is published.
   */
  const legendKeys: readonly PfKeyBinding[] = activePfKeys?.keys ?? NO_LEGEND_KEYS;

  /*
   * Assumptions: the surface is shown only while BOTH conditions hold -- the operator is
   * still on the entry they signed off at, and no session is held. The second is not
   * redundant: a browser `back` returns to the initial entry under the key it already had,
   * and a session established after the sign-off would otherwise be described by a
   * thank-you. Reading a session that has just been discarded is safe in the same render,
   * because `signOut` publishes synchronously.
   */
  if (signedOffAtEntry === currentEntry && !signedOn) {
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
         * been a browser flourish the terminal never performed. The surface still occupies the
         * whole display, as the cleared screen did, because the document sizes the mount point
         * to one viewport and this primitive is given the flex growth to fill it.
         */
        align="start"
        style={signOffStyle}
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
        {/*
          ⚠️ Refactoring Rationale: this control did not exist. The acknowledgement rendered the
          verbatim line and nothing else, so a document-wide query for `a, button, input,
          [role=button], [tabindex]` returned an EMPTY list -- an operator had no in-page way
          onward at all and had to use browser Back or retype an address. The surface still
          replaces the frame, because `app/cbl/COSGN00C.cbl` L162-L172 sends the line with
          `EXEC CICS SEND TEXT ... ERASE FREEKB` and the cleared display is what the reference
          actually shows; what the reference then relies on is a terminal operator keying a new
          transaction, and a browser has no equivalent of that. This control is the equivalent:
          one activation returns to the framed sign-on screen at the address already showing.

          Alternatives Considered: not replacing the frame at all, and painting the line into the
          row-23 band of the sign-on screen instead. Rejected because `ERASE` is explicit in the
          reference and the cleared display is the fidelity this surface exists to carry -- and
          the band belongs to the screen that is mounted, which during a sign-off is none.

          Assumptions: the label is the catalogue's own `SIGN_ON_SUBMIT_LABEL`, which is the exact
          action this control performs, so the surface introduces no new operator-visible string.
          `type="primary"` because it is the only action on the surface; AAP section 0.3.2 gives the
          primary variant to the affirmative action of a screen, and there is nothing here to
          out-rank it.
        */}
        <Button
          data-testid={SHELL_SIGN_OFF_CONTINUE_TEST_ID}
          type="primary"
          onClick={leaveSignOffAcknowledgement}
        >
          {SIGN_ON_SUBMIT_LABEL}
        </Button>
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
          {/*
            Assumptions: the skip link and the sign-off control share one row, and both are
            additive browser chrome rather than migrated fields - so they sit together, ahead
            of the migrated title band, instead of being interleaved with it. `justify` keeps
            the sign-off control at the trailing edge without a positioned element, which
            design gap G1 forbids.
            Assumptions: the sign-off control is rendered only while a session is held, and the
            guard is load bearing rather than tidy - the sign-on route is INSIDE this frame,
            mounted as a child of the same layout route above the authentication guard, so an
            always-rendered control would offer "Sign off" to an operator who has not signed on
            yet. ⚠️ Refactoring Rationale: the reason recorded here was that "the sign-on screen
            is outside this shell entirely", which inverted the topology; the condition it
            justified is right for the other reason, so the condition stands and the reason is
            corrected. `size="small"` and `type="link"` keep it chrome rather than a primary
            action of whatever screen is mounted; both are antd variants and neither introduces
            a value of its own.
          */}
          <Flex align="center" justify="space-between" gap="small">
            <Typography.Link
              href={`#${SHELL_CONTENT_ELEMENT_ID}`}
              style={skipLinkStyle}
              onPointerEnter={skipLinkHovered}
              onPointerLeave={skipLinkRested}
              onPointerDown={skipLinkPressed}
              onPointerUp={skipLinkHovered}
            >
              {SKIP_TO_CONTENT_LABEL}
            </Typography.Link>
            <Flex align="center" gap="small">
              {/*
                ⚠️ Refactoring Rationale: this control did not exist, and its absence left the two
                roles on DISJOINT navigation graphs. An administrator reaches eight routes through the
                interface and had no in-app path to any of the thirteen ordinary business screens: the
                administrative menu lists only the six options of `app/cpy/COADM02Y.cpy`, and PF3 there
                signs the operator off rather than stepping back, because `app/cbl/COADM01C.cbl`
                L100-L102 genuinely returns to sign-on. The reference operator was not stranded by that,
                because a terminal operator could key transaction `CM00` directly; a browser offers no
                equivalent, so the crossing has to be a rendered control.

                Alternatives Considered: adding a seventh option to the administrative menu. Rejected
                because the six options ARE `app/cpy/COADM02Y.cpy` -- `ui/src/test/admin.test.tsx` pins
                the option list against that copybook in both directions -- so a seventh would be an
                invented menu entry rather than a migrated one. Placing it in the chrome keeps the
                migrated menu exactly as the reference paints it.

                Assumptions: it is rendered only for an administrator, and only while a session is
                held. An ordinary operator needs no crossing -- their own menu already reaches every
                screen they may enter, and the administrative surface refuses them -- so offering it
                would advertise a destination that answers with a refusal. The label is the main-menu
                mapset's own row-4 heading, so no operator-visible string is introduced;
                `ui/src/routes/guards.tsx` already uses that constant as the label of the same
                destination, which keeps the two crossings named identically. `type="link"` and
                `size="small"` match the sign-off control beside it: both are additive browser chrome
                rather than an action of whichever screen is mounted.
              */}
              {signedOn && isAdmin ? (
                <Button
                  data-testid={SHELL_MAIN_MENU_CROSSING_TEST_ID}
                  type="link"
                  size="small"
                  disabled={activeBusy}
                  onClick={crossToMainMenu}
                >
                  {MAIN_MENU_HEADINGS.SCREEN}
                </Button>
              ) : null}
              {signedOn ? (
                <Button
                  data-testid={SHELL_SIGN_OFF_CONTROL_TEST_ID}
                  type="link"
                  size="small"
                  disabled={activeBusy}
                  onClick={signOffFromShell}
                >
                  {SHELL_SIGN_OFF_LABEL}
                </Button>
              ) : null}
            </Flex>
          </Flex>
          {/*
            ⚠️ Refactoring Rationale: the brand heading is painted on BOTH branches now, and it used
            to be reachable only through the identity branch. A browser measurement of the not-found
            surface, which publishes no screen identity by design, read its heading outline as
            `['H4:Screen not available']` -- no rank-one heading anywhere in the document, so an
            operator navigating by heading level met a level-four heading as the highest thing on
            the page. The brand is the identity of the APPLICATION rather than of any screen, so it
            belongs on every surface the frame paints; what a surface may withhold is the
            TRANSACTION identity beside it.

            Assumptions: the withholding stays withheld. `ScreenHeader` paints the four status-line
            prompts -- transaction, program, date and time -- and nothing here supplies a substitute
            for them, so a surface that names no reference program still names none. That
            distinction is the one `ui/src/routes/guards.tsx` records: a refusal painted by a
            program in `app/**` on that program's own map delegates that program's identity, and a
            mistyped address, which no program paints, delegates nothing.
          */}
          {activeScreen === undefined ? (
            <Flex justify="center">
              <AppTitleHeading />
            </Flex>
          ) : (
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
      <Layout.Content id={SHELL_CONTENT_ELEMENT_ID} tabIndex={-1} style={contentStyle}>
        {/*
          ⚠️ Refactoring Rationale: the body is bounded by a MAXIMUM MEASURE and centred,
          where it used to be a proportional grid column - `span={24} lg={22}`. The column
          form was measured and fails two ways at once: a proportion has no upper bound, so
          at roughly 1848 pixels the content stayed anchored at x=78 with the right half of
          the display empty, and a proportional inset GROWS with the viewport, so the body's
          left edge (41.3 at 992, 66.7 at 1600) walked away from the legend's fixed inset
          and the frame had two left edges that agreed at no width. `Col` is kept - the row
          still does the centring, so no raw element carries layout CSS - and only the bound
          is added, from the `screenLG` token recorded in {@link SHELL_BREAKPOINT_TOKENS}.
          No third breakpoint is invented here.
        */}
        <Row justify="center">
          <Col span={24} style={contentColumnStyle}>
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
        Assumptions: the line is rendered only when a message SLOT exists, which is not the
        same test as whether that slot carries text. All 21 screens publish a message slot on
        every turn - an object whose `text` is `null` when the screen has nothing to say - so
        each of them reserves the row unconditionally and gets the no-layout-shift guarantee
        the band exists for.
        ⚠️ Refactoring Rationale: two earlier readings of the `undefined` arm were both wrong
        and are corrected together, because each was measured against a tree that no longer
        exists. It is NOT reached by the router's not-found page: that page is a sibling of the
        frame -- a top-level sibling of BOTH `<AppShell />` mounts in `ui/src/router.tsx`,
        outside either one -- so it is never mounted inside this shell. Nor is it reached by any screen that composes its own message zone, because
        no screen does - every one of the 21 passes a `message` object literal to
        `useShellSlot`, the five that also keep a band inside their body doing so IN ADDITION
        to delegating this row, not instead of it. What remains is the only reachable case:
        `EMPTY_SLOT` carries no `message` member, so the arm is taken whenever the frame is
        mounted with nothing published - a screen's first render pass before its
        `useLayoutEffect` publishes, which a layout effect keeps off the screen rather than
        merely brief, and any direct render of this component in a test. Rendering nothing
        there is correct rather than tolerated: reserving a row for a screen that may never
        publish one would reintroduce the shift the reservation exists to prevent.
      */}
      {/*
        ⚠️ Refactoring Rationale: the message line and the key legend are wrapped in ONE
        pinned zone, where they used to be two ordinary in-flow siblings of the content
        region. The wrapper is what carries `position: sticky` for both at once, and pinning
        them together rather than separately is deliberate: they are rows 23 and 24 of one
        display, so a legend that stuck while the band above it scrolled away would separate
        an outcome from the keys that answer it. The reasoning for sticky over fixed, for the
        surface, and for the stacking order is at {@link pinnedZoneStyle}.

        Assumptions: DOM ORDER IS UNCHANGED by the wrapper - header, content, row 22, row 23,
        legend - so the reading order and the tab order design gap G1 undertakes to preserve
        are exactly what they were. The wrapper is a layout primitive rendered as a plain
        element and carries no role, so it adds no landmark and the frame still exposes one
        banner, one main and one contentinfo.

        Assumptions: the zone is rendered unconditionally, even on the first render pass of a
        screen that has published nothing yet. Its two children each decide their own
        presence - `MessageBand` is conditional on a delegated slot and `PfKeyBar` returns
        `null` for an empty binding list - so an unpublished frame renders an empty pinned
        zone rather than a zone that appears once a screen speaks, which would move the
        content the reservation exists to hold still.
      */}
      <Flex vertical data-testid={SHELL_PINNED_ZONE_TEST_ID} style={pinnedZoneStyle}>
        {/*
          Assumptions: the row-22 line is painted ABOVE the row-23 line and inside the same
          pinned zone, which is the measured arrangement: `app/bms/COACTVW.bms` declares
          `INFOMSG` at `POS=(22,23)` and `ERRMSG` at `POS=(23,1)`, two adjacent rows at the
          bottom of one display. Rendering it here rather than leaving each screen to paint
          its own is what closes the gap measured on `/account/update` and on
          `/reference/transaction-types/:cd`, where the frame's band stood empty at its
          reserved height while the screen's real advisory sat roughly 200 pixels higher.
          Assumptions: the guard tests the SLOT and not its text, exactly as the row-23 guard
          below does, so a screen whose mapset declares row 22 reserves that row on every turn
          and gets the same no-layout-shift guarantee for it.
          Assumptions: the mapset comes from the row-23 slot because a screen stands in for one
          mapset and the display width is the mapset's property, so both bands are sized alike.
        */}
        {activeMessage?.information === undefined ? null : (
          /*
            ⚠️ Refactoring Rationale: the severity is passed STRAIGHT THROUGH, where this line used to
            substitute a shell-local `DEFAULT_INFORMATION_SEVERITY` for an absent one. The band now
            resolves an omitted severity from the channel itself - `defaultMessageBandSeverity` in
            `ui/src/layout/MessageBand.tsx` reads it from the same measured table that documents what
            each channel carries - so the constant here was a second copy of one decision, and the two
            could have drifted with nothing to fail. Deleting it leaves the row-22 default stated once,
            beside the row-23 default it has to differ from.
            Assumptions: passing an explicitly `undefined` severity is permitted rather than a type
            error, because `MessageBandProps` writes the `| undefined` arm out for exactly this case -
            a caller holding an optional value can forward it without reconstructing the props object.
          */
          <MessageBand
            channel="information"
            message={activeMessage.information.text}
            severity={activeMessage.information.severity}
            mapset={activeMessage.mapset}
          />
        )}
        {activeMessage === undefined ? null : (
          <MessageBand
            message={activeMessage.text}
            severity={activeMessage.severity}
            mapset={activeMessage.mapset}
          />
        )}
        <Layout.Footer style={footerStyle}>
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
            onInvoke={dispatchShellPfKey}
            {...(activePfKeys?.legendColor === undefined
              ? {}
              : { legendColor: activePfKeys.legendColor })}
            {...(activePfKeys?.regionLabel === undefined
              ? {}
              : { regionLabel: activePfKeys.regionLabel })}
          />
        </Layout.Footer>
      </Flex>
    </Layout>
  );
}
