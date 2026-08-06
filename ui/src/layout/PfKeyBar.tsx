/**
 * The persistent, visible function-key legend: the on-screen half of the
 * CardDemo PF-key contract.
 *
 * Purpose
 * -------
 * Every one of the 17 base BMS mapsets paints a legend on terminal row 24 -
 * `ENTER=Sign-on  F3=Exit` on the sign-on screen, `F3=Exit F7=Backward
 * F8=Forward` on the card list, and so on. This component renders that legend as
 * real controls, so an action the 3270 offered only to someone who already knew
 * which function key to press is now also discoverable by looking at the screen.
 *
 * The behavioural half of the same contract lives in
 * `ui/src/layout/usePfKeys.ts`, which owns the attention-identifier (AID) domain,
 * the browser-key normalisation ported from `app/cpy/CSSTRPFY.cpy:L21-L78`, and
 * dispatch. This module imports that vocabulary and re-declares none of it: it
 * renders {@link PfKeyBinding} values and forwards activation to the caller's
 * dispatch function. It installs no keyboard listener of its own.
 *
 * Provenance
 * ----------
 * All figures below are measured from the baseline, which is reference-only and
 * never modified:
 *
 * - `app/bms/*.bms` - the 17 row-24 legend fields, one per base mapset, read by
 *   rejoining BMS continuation lines rather than by grepping single lines.
 * - `app/cpy/CSSTRPFY.cpy` and `app/cpy/CVCRD01Y.cpy:L3-L19` - the normalised
 *   key domain, imported here through `usePfKeys.ts`.
 * - `app/cpy-bms/COCRDSL.CPY:L108,L200` - `FKEYSI`/`FKEYSO PIC X(75)`, the widest
 *   of the three on-screen legend fields the baseline actually declares.
 * - `app/cbl/COACTUPC.cbl:L905-L916` and `:L3566-L3581` - how a screen validates
 *   a key against its own state, and how it reveals a legend that was painted
 *   dark.
 *
 * What this module owns, and what it does not
 * -------------------------------------------
 * `ui/src/messages/messages.ts` is the single owner of every user-visible string
 * in this tree with one stated exception: at its lines 169-176 and 187-191 it
 * excludes the function-key legends and assigns them here, on the grounds that a
 * legend is a *rendering* of the key bindings rather than their definition and
 * that duplicating the rendered form in the catalog would create a second place
 * for the two to disagree. This module therefore owns the three uniform legend
 * labels in {@link UNIFORM_PF_KEY_LABELS} and nothing else textual; every label
 * that varies per screen arrives through {@link PfKeyBarProps.keys}.
 *
 * Design values come from `ui/src/theme/tokens.ts` by name and are turned into CSS
 * custom-property REFERENCES through the design system's own runtime token hook, so
 * every value this module writes into a style stays on the theme's variable surface
 * rather than being frozen at render. This module does not instantiate the theme
 * provider - `ui/src/App.tsx` is the sole injection point - and it writes no
 * literal colour, spacing or radius, and no resolved one either.
 */

import { Button, Flex, theme } from "antd";
import type { ReactElement } from "react";

import { BMS_COLOR_TOKENS, SPACING_TOKENS } from "../theme/tokens";
import { KEYBOARD_KEY_TO_AID, PF_KEY_ALIASES } from "./usePfKeys";
import type { CicsAid, PfKeyBinding } from "./usePfKeys";

/**
 * Semantic colour role measured on a mapset's row-24 legend field.
 *
 * `COLOR=YELLOW` carries the legend on 15 of the 17 base mapsets;
 * `COLOR=TURQUOISE` carries it on the remaining two, `app/bms/COACTVW.bms` and
 * `app/bms/COCRDLI.bms`. Those are the only two operands the population contains,
 * so the union is closed rather than merely current.
 *
 * Assumptions: the two names are constrained to keys of
 * {@link BMS_COLOR_TOKENS} rather than written as a free string union, so that
 * renaming or dropping either entry in the token bridge fails this module's
 * compilation instead of leaving a prop whose value no longer resolves to a
 * token. This mirrors the defensive typing the bridge applies to itself.
 */
export type PfKeyLegendColor = Extract<
  keyof typeof BMS_COLOR_TOKENS,
  "YELLOW" | "TURQUOISE"
>;

/**
 * Legend labels for the three keys whose wording is uniform across every mapset
 * that uses them.
 *
 * `F4=Clear`, `F7=Backward` and `F8=Forward` are byte-identical everywhere they
 * appear in the 17 measured legends, so a screen that binds PF4, PF7 or PF8 can
 * take its label from here instead of restating it.
 *
 * Alternatives Considered: supplying a default for all seven keys the shared
 * shell exposes. It is rejected on the measured evidence, because the other four
 * are not uniform and a default would therefore be silently wrong on most
 * screens rather than merely unhelpful. ENTER is painted `Sign-on`, `Continue`,
 * `Process`, `Search Cards`, `Fetch` and `Add User`; PF3 is painted `Exit` on
 * seven screens, `Back` on nine and `Save&Exit` on `app/bms/COUSR02.bms`; PF5 is
 * painted `Save`, `Browse Tran.`, `Copy Last Tran.` and `Delete`; PF12 is painted
 * `Cancel` on three screens and `Exit` on `app/bms/COUSR01.bms`. A wrong label on
 * a control that performs a write - PF5 reading `Save` where the screen means
 * `Delete` - is worse than no default at all, which is why the omission is
 * deliberate and is recorded here rather than left as an apparent gap.
 */
export const UNIFORM_PF_KEY_LABELS = Object.freeze({
  PFK04: "F4=Clear",
  PFK07: "F7=Backward",
  PFK08: "F8=Forward",
}) satisfies Readonly<Partial<Record<CicsAid, string>>>;

/**
 * AIDs rendered with the design system's primary button emphasis.
 *
 * Assumptions: this pairing is fixed by the migration plan's design-system
 * section, which maps the action keys to `Button` with `type="primary"` for ENTER
 * and PF5 and `type="default"` for PF3, PF4 and PF12. It is not inferred from the
 * baseline's own attributes: the 3270 expressed emphasis with `ATTRB=BRT`, which
 * this tree resolves to font weight rather than to colour, so terminal brightness
 * and button emphasis are independent decisions and only the latter is settled
 * here. PF7 and PF8 are absent because the plan maps page navigation separately
 * and specifies no emphasis for it, which leaves them on the default.
 */
export const PRIMARY_ACTION_AIDS: readonly CicsAid[] = Object.freeze([
  "ENTER",
  "PFK05",
]);

/**
 * Accessible name for the legend's navigation landmark.
 *
 * Assumptions: the baseline has no counterpart to name. Row 24 is a bare text
 * field at a fixed screen position, and position is what identified it to a
 * terminal operator; a landmark needs a name instead. The string is held here
 * rather than in the message catalog because it is not baseline text being
 * carried across - it is new, and the catalog excludes strings that no COBOL
 * source holds.
 */
export const PF_KEY_BAR_REGION_LABEL = "Function keys";

/**
 * Props accepted by {@link PfKeyBar}.
 *
 * Assumptions: `keys` is an array of per-screen descriptors rather than a fixed
 * set this component decides for itself, because the measured legends differ on
 * every axis a fixed bar would have to hard-code - which keys appear, in what
 * wording, and in what quantity. `app/bms/COACTVW.bms` paints one key;
 * `app/bms/COUSR02.bms` paints five, and paints PF3 as `Save&Exit`, which
 * contradicts the otherwise-reliable reading that PF3 means "go back". A bar that
 * assumed a shape could not express that screen at all, so the shape is the
 * caller's to state. This is the central design decision in this module.
 */
export interface PfKeyBarProps {
  /**
   * Ordered descriptors for the keys this screen paints, normally the `bindings`
   * member of `usePfKeys`' result.
   *
   * Assumptions: the array is rendered in the order given, with no sorting. All
   * 17 measured legends already read in ascending AID order - ENTER first, then
   * PF3, PF4, PF5, PF7, PF8, PF12 - and `usePfKeys` builds its bindings by
   * walking that same order, so the supplied order is the legend's reading order
   * and re-sorting could only diverge from it. Tab order therefore matches the
   * legend by construction rather than by a separate rule.
   */
  readonly keys: readonly PfKeyBinding[];
  /**
   * Receives the AID a user activated by clicking a key in this bar.
   *
   * Assumptions: `usePfKeys`' `invoke` is the intended argument. It validates the
   * AID against the screen's live handler map and disabled predicates before
   * dispatching, which is the same path a real keydown takes, so a click and the
   * corresponding key press cannot diverge. Its `boolean` result is accepted and
   * discarded here because a `void` return type admits it; this component has no
   * use for the outcome, and the parameter type stays permissive enough for a
   * caller that dispatches some other way.
   * @param {CicsAid} aid - Canonical AID whose control was activated.
   * @returns {void} Completion is represented by the caller's own side effects.
   * @throws {unknown} Caller-owned dispatch failures propagate to the
   * application error boundary.
   */
  readonly onInvoke: (aid: CicsAid) => void;
  /**
   * Measured colour role of this screen's legend field; defaults to the
   * 15-of-17 majority.
   */
  readonly legendColor?: PfKeyLegendColor;
  /**
   * Accessible name for the landmark; defaults to
   * {@link PF_KEY_BAR_REGION_LABEL}.
   */
  readonly regionLabel?: string;
}

/**
 * Inverts the browser-key lookup so a rendered control can advertise the key that
 * activates it.
 *
 * Alternatives Considered: writing a second, hand-maintained AID-to-key table in
 * this module. It is rejected because two tables describing one relationship can
 * disagree, and the disagreement would be silent - a control would advertise a
 * shortcut that dispatches nothing. Deriving the inverse from the forward table
 * `usePfKeys` already exports makes divergence impossible.
 *
 * Assumptions: the forward table maps 25 browser keys onto 13 AIDs, and the
 * arithmetic is written out because the figure is not readable from the table's
 * shape - `KEYBOARD_KEY_TO_AID` lists 13 entries literally (`Enter` plus `F1`
 * through `F12`) and then spreads `PF_KEY_ALIASES`, whose 12 entries (`F13`
 * through `F24`) are what make 25. Counting only the visible entries yields 24 and
 * is wrong by exactly the spread. The 13 AIDs are `ENTER` and `PFK01` through
 * `PFK12`; the remaining three members of `CicsAid` - `CLEAR`, `PA1` and `PA2` -
 * have no browser key at all, which is why this function's return type is
 * `Partial`. The 12 aliases exist because `app/cpy/CSSTRPFY.cpy:L54-L77` folds
 * PF13-PF24 back onto PF1-PF12, so the inverse is ambiguous: 25 keys cannot each
 * be the canonical key of one of 13 AIDs. Excluding exactly the 12 alias entries
 * leaves 13 keys for 13 AIDs, one apiece. Advertising `F15` for PF3 would be
 * technically true and useless, since the legend the baseline paints reads `F3`.
 *
 * @returns {Readonly<Partial<Record<CicsAid, string>>>} Canonical browser key per
 * AID, omitting the AIDs that have no web key at all.
 */
function buildAidToCanonicalBrowserKey(): Readonly<
  Partial<Record<CicsAid, string>>
> {
  const inverse: Partial<Record<CicsAid, string>> = {};

  for (const [browserKey, aid] of Object.entries(KEYBOARD_KEY_TO_AID)) {
    if (browserKey in PF_KEY_ALIASES) {
      continue;
    }

    inverse[aid] ??= browserKey;
  }

  return Object.freeze(inverse);
}

/**
 * Canonical browser key per AID, computed once because the forward table it
 * inverts is frozen at module scope and cannot change.
 */
const AID_TO_CANONICAL_BROWSER_KEY = buildAidToCanonicalBrowserKey();

/**
 * Converts a legend copied straight out of a mapset into the text it renders as.
 *
 * Assumptions: BMS source is assembler macro source, in which `&` opens a
 * variable symbol, so a literal ampersand is written `&&`. `app/bms/COUSR02.bms`
 * is the only mapset affected: its row-24 field holds
 * `ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel`, and the terminal
 * displays one ampersand, not two. The doubling is source-level escaping rather
 * than content, so reproducing it would not be fidelity - it would be a
 * transcription defect that this tree's byte-exactness rule would then protect.
 *
 * Trade-offs: this exists as a function rather than as a note telling screen
 * authors to type the single-ampersand form. The instruction to carry baseline
 * text across verbatim actively invites copying the `&&` form out of the mapset,
 * so the hazard is real and a comment cannot catch it; a decoder can be applied
 * and tested. The cost is one call a screen may forget, which is why the
 * single-ampersand form is also valid input: the function is idempotent, so
 * applying it to already-decoded text is safe.
 *
 * @param {string} mapsetInitial - Legend text as the mapset's `INITIAL=` operand
 * holds it, or already-decoded text.
 * @returns {string} The legend as the screen displays it, with each escaped
 * ampersand pair reduced to one ampersand.
 */
export function decodeBmsLegendText(mapsetInitial: string): string {
  return mapsetInitial.replace(/&&/g, "&");
}

/**
 * Reports whether an AID takes the design system's primary button emphasis.
 *
 * @param {CicsAid} aid - Canonical AID being rendered.
 * @returns {boolean} `true` for the AIDs listed in {@link PRIMARY_ACTION_AIDS}.
 */
function isPrimaryActionAid(aid: CicsAid): boolean {
  return PRIMARY_ACTION_AIDS.includes(aid);
}

/**
 * Selects the descriptors that correspond to a painted legend field.
 *
 * Assumptions: a binding whose label is empty is a keyboard-only handler -
 * `usePfKeys` documents the empty string as exactly that - so it has no legend
 * field in the baseline and must contribute no control here. Rendering an unnamed
 * button for it would add a control the terminal never showed and would give it
 * no accessible name. The emptiness test trims first, so a label of only spaces
 * counts as absent too: such a label paints nothing on a terminal and would
 * render a blank, unnameable control in a browser, which is the same defect the
 * empty string is being excluded for. Trimming here does not weaken the verbatim
 * guarantee, because the untrimmed label is what reaches the DOM - this decides
 * only whether a control exists, never what it displays.
 *
 * Assumptions: the de-duplication guards a screen-authoring mistake rather than a
 * baseline shape. No AID appears twice in any of the 17 measured legends, and
 * bindings built by `usePfKeys` cannot repeat one because it walks each AID once;
 * but the prop accepts any array, and two entries sharing an AID would collide on
 * the React key and render unpredictably. Keeping the first occurrence degrades
 * that mistake to one visible control.
 *
 * @param {readonly PfKeyBinding[]} keys - Descriptors as supplied by the screen.
 * @returns {readonly PfKeyBinding[]} Labelled, AID-unique descriptors in the
 * order supplied.
 */
function selectRenderableKeys(
  keys: readonly PfKeyBinding[],
): readonly PfKeyBinding[] {
  const seen = new Set<CicsAid>();

  return keys.filter(
    /**
     * Keeps the first labelled descriptor for each AID.
     * @param {PfKeyBinding} binding - Descriptor under consideration.
     * @returns {boolean} `true` when the descriptor should be rendered.
     */
    (binding: PfKeyBinding): boolean => {
      if (binding.label.trim() === "" || seen.has(binding.aid)) {
        return false;
      }

      seen.add(binding.aid);
      return true;
    },
  );
}

/**
 * Renders the persistent function-key bar for the active screen.
 *
 * Each supplied descriptor becomes one button carrying the screen's verbatim
 * legend text, and activating it dispatches through the same validated path a
 * real key press takes.
 *
 * Trade-offs: the actions are reachable two ways at once, and the redundancy is
 * deliberate. The 3270 original had no pointer at all, so the keyboard bindings
 * in `usePfKeys` are a fidelity requirement rather than a convenience - an
 * operator who knows the workflow keeps it unchanged. The buttons exist because
 * that workflow was undiscoverable to anyone who did not already know it: nothing
 * on a terminal screen indicated that PF5 saved rather than PF6. Rendering only
 * buttons would take the established workflow away from existing users, and
 * binding only keys would leave new users with no way to find the actions, so
 * neither audience is served at the other's expense. The cost accepted is two
 * activation paths to keep in step, which is why both funnel through the single
 * `onInvoke` dispatch rather than each holding its own logic.
 *
 * Assumptions: an unavailable key is rendered disabled rather than removed,
 * because that is what the baseline does with the keys it validates.
 * `app/cbl/COACTUPC.cbl:L905-L916` sets `PFK-INVALID`, admits PF5 only while
 * `ACUP-CHANGES-OK-NOT-CONFIRMED` and PF12 only while the details have been
 * fetched, and then resolves an invalid key with `SET CCARD-AID-ENTER TO TRUE` -
 * the key is accepted and reduced to a screen refresh, never reported as an
 * error. A disabled control is the closest browser equivalent: present, labelled,
 * inert. A screen that instead needs a legend absent entirely - which
 * `app/bms/COACTUP.bms:L498-L507` and `app/bms/COCRDUP.bms:L163` do express, by
 * declaring `FKEY05`, `FKEY12` and `FKEYSC` as `ATTRB=(ASKIP,DRK)` and revealing
 * them at `app/cbl/COACTUPC.cbl:L3573-L3581` - omits the descriptor instead, so
 * both of the baseline's treatments are expressible and neither is invented here.
 *
 * @param {PfKeyBarProps} props - The component's props, destructured below.
 * @param {readonly PfKeyBinding[]} props.keys - Ordered descriptors for the keys
 * this screen paints.
 * @param {(aid: CicsAid) => void} props.onInvoke - Dispatch for the AID a user
 * activated by click.
 * @param {PfKeyLegendColor} [props.legendColor] - Measured colour role of the
 * screen's legend field.
 * @param {string} [props.regionLabel] - Accessible name for the landmark.
 * @returns {ReactElement | null} The legend landmark, or `null` when the screen
 * paints no key.
 * @throws {unknown} Dispatch errors raised by `onInvoke` propagate to the
 * application error boundary.
 */
export function PfKeyBar({
  keys,
  onInvoke,
  legendColor = "YELLOW",
  regionLabel = PF_KEY_BAR_REGION_LABEL,
}: PfKeyBarProps): ReactElement | null {
  // Assumptions: the token modules export token NAMES, not values, so a name has
  // to be resolved against the theme actually in force. This hook is the design
  // system's own accessor for that theme and reads the provider instantiated in
  // `ui/src/App.tsx`, which is why this module resolves values without
  // instantiating a second provider of its own.
  //
  // Refactoring Rationale: `cssVar` is destructured, NOT `token`, and the two are
  // not interchangeable. The hook returns both maps over the same token names, but
  // `token` holds the values the theme resolves to right now - a literal hex
  // colour, a pixel number - while `cssVar` holds `var(--…)` references to the same
  // tokens. Writing `token` into a `style` prop was what this module did, and it
  // defeats the CSS-variable surface `ui/src/theme/antdTheme.ts` switches on: the
  // resolved value is copied into the element's inline style at render, so the
  // element stops tracking the variable and a later theme change reaches every
  // component styled by class but not this one. It is a literal in every sense the
  // no-hardcoded-values rule cares about, differing from a typed hex only in who
  // typed it. `ui/src/layout/ScreenHeader.tsx` already resolved this correctly and
  // is the pattern followed here.
  // Assumptions: the mapping between the two is not guesswork. The design system's
  // own public hook builds its result as
  // `const [theme, token, hashId, cssVar] = useInternalToken()` over an internal
  // tuple returned as `[mergedTheme, realToken, hashId, token, cssVar, …]`, so the
  // public `token` is the internal `realToken` - resolved - and the public `cssVar`
  // is the internal cssVar-substituted map - references. Both are typed
  // `GlobalToken`, so nothing in the type system distinguishes them and the choice
  // has to be made deliberately rather than caught by the compiler.
  const { cssVar } = theme.useToken();
  const renderableKeys = selectRenderableKeys(keys);

  // Assumptions: no baseline screen paints an empty row-24 legend - all 17 carry
  // one - so an empty list is a degenerate case rather than a layout to
  // reproduce. It renders nothing at all instead of an empty landmark, because a
  // named landmark containing no control is an entry in a screen reader's
  // landmark list that leads nowhere.
  if (renderableKeys.length === 0) {
    return null;
  }

  return (
    // Assumptions: `component` renders this layout primitive as a `nav` element,
    // so the legend is a navigation landmark rather than a generic container,
    // without stepping outside the design system to a raw element carrying its
    // own layout CSS. `wrap` is set because the widest measured legend occupies
    // 58 of the terminal's 80 columns as five separate controls, which cannot be
    // assumed to fit one browser line at every viewport width; wrapping reflows
    // it instead of letting it overflow the shell.
    // Trade-offs: no further layout wrapper is nested inside. A single-child
    // container that adds no visual behaviour is flattened by this tree's
    // component rules, and this primitive already supplies both the gap and the
    // element, so adding a second one would deepen the DOM for nothing.
    <Flex
      component="nav"
      aria-label={regionLabel}
      // Assumptions: cross-axis centring matters only once the bar wraps, which
      // the line below allows. The alternative, leaving the default stretch,
      // makes a control on a short line grow to the tallest line's height, so
      // wrapped keys would render taller than unwrapped ones - a difference the
      // baseline cannot have, since its legend is one fixed-height row.
      align="center"
      wrap
      // Assumptions: the gap resolves through the spacing bridge's compact step
      // rather than through this primitive's own size keywords. The baseline sets
      // the distance between legend groups in character columns - row 24 starts
      // fields at columns 1, 23 and 31 on `app/bms/COACTUP.bms` - so the value is
      // a measured design decision that belongs in the bridge where it can be
      // audited, not a keyword chosen at the call site.
      // Refactoring Rationale: this passes the reference form, not the resolved
      // number, and it took reading the primitive's implementation to establish
      // that it may. Its `gap` prop is typed `LiteralUnion<SizeType,
      // CSSProperties['gap']>`, and at run time it tests the value against the
      // four preset keywords (`small`, `middle`, `medium`, `large`) and, for
      // anything that is not one of them, assigns it verbatim to the element's
      // inline `style.gap`. A `var(--…)` string is not a preset, so it is written
      // through unchanged and the browser resolves it, exactly as it does for a
      // colour. The alternative belief - that this prop needs a number - is what
      // would have left the last resolved-token read in this module; it is false
      // for the pinned version, so the exception it would have justified does not
      // exist and the module reads no resolved value at all.
      // Assumptions: the referenced token carries its unit. The design system emits
      // numeric tokens into CSS variables with a `px` suffix unless the token is on
      // its own unitless list, and that list is exactly the line-height family,
      // `opacityLoading`, `fontWeightStrong`, the two z-index tokens and
      // `opacityImage`. The compact spacing step resolves to `marginXS`, which is
      // not on it, so the variable holds `8px` - a valid `gap` length. A unitless
      // token would resolve to a bare number here and be invalid, which is why the
      // list matters rather than being incidental.
      gap={cssVar[SPACING_TOKENS.sectionGapCompact]}
      // Assumptions: this establishes the legend region's inherited text colour,
      // which is what the mapset's `COLOR=` operand set on the legend field. Its
      // scope is narrow, and measurably so: a populated bar contains no text
      // node outside a button, and each button paints its own label colour from
      // the emphasis the design-system mapping fixes for that key, so today
      // nothing actually inherits this declaration. That is a consequence of the
      // mapping outranking the source colour, not an oversight - recorded here
      // so a reader who measures the same thing does not file it as a defect.
      // Trade-offs: the accepted decision is that the design-system component
      // mapping wins over the source legend colour where they disagree, and this
      // is where they disagree. The mapset colours the whole legend field one way;
      // the mapping colours each control by what its key DOES, so ENTER and PF5
      // take the primary emphasis and the rest the default, and a button's own
      // label colour is part of that emphasis. Overriding it would put the source
      // colour back and take the emphasis distinction away, which is the more
      // informative of the two - the mapset's single colour says nothing about
      // which key writes. Carrying the value here is still correct, because the
      // measured distinction between the 15 yellow legends and the two turquoise
      // ones is a real design value, this is the one component responsible for the
      // legend, and the declaration governs any non-button text a screen adds to
      // the region; discarding it would lose the value with nowhere else to
      // record it.
      // Refactoring Rationale: this comment previously ended by noting that the
      // turquoise role resolves to an informational token which the design
      // system's default seed sets to the same value as the primary token, so the
      // two measured roles rendered identically. That is no longer true and the
      // note is withdrawn rather than softened: `ui/src/theme/antdTheme.ts` now
      // separates the informational seed from the primary one, so turquoise and
      // blue resolve to different colours and the two legend roles are visibly
      // distinct. Leaving the sentence in place would have been the more harmful
      // half of a stale comment - a reader would have trusted it and concluded the
      // `legendColor` prop cannot matter.
      // Alternatives Considered: the typography component's own `type="warning"`,
      // which would need no token lookup. It is rejected because that prop has no
      // informational member, so turquoise could not be expressed through it and
      // the two measured roles would resolve by two different mechanisms - one a
      // system keyword, one a token lookup - leaving the bridge only half
      // authoritative.
      style={{ color: cssVar[BMS_COLOR_TOKENS[legendColor]] }}
    >
      {renderableKeys.map(
        /**
         * Renders one legend entry as an activatable control.
         * @param {PfKeyBinding} binding - Descriptor for a single key.
         * @returns {ReactElement} The control for that key.
         */
        (binding: PfKeyBinding): ReactElement => (
          <Button
            // Assumptions: the AID identifies the control, not its position. The
            // alternative, the array index, would let a screen that reveals a key
            // mid-session - which the baseline does, by un-darkening a legend -
            // re-key every control after the insertion point and so discard their
            // focus and press state. The AID is safe to use because
            // selectRenderableKeys has already made it unique.
            key={binding.aid}
            // Assumptions: the design-system mapping fixes primary emphasis to
            // ENTER and PF5 and the default to the rest; see
            // PRIMARY_ACTION_AIDS.
            type={isPrimaryActionAid(binding.aid) ? "primary" : "default"}
            // Trade-offs: the two activation paths are NOT equivalent for a
            // disabled binding, and the difference is intended rather than
            // incidental. A disabled control cannot fire its click handler, so a
            // click never reaches `onInvoke` and nothing is reported. The key path
            // does reach dispatch: `usePfKeys` recognises the AID, finds the
            // handler disabled, and reports a rejection carrying the baseline's own
            // `INVALID_KEY_PRESSED` text through its invalid-key channel. So
            // pressing the key surfaces a message and clicking the greyed control
            // surfaces none.
            // Assumptions: that asymmetry is correct because only one of the two
            // channels exists in the baseline. The terminal had no pointer, so the
            // key press is the fidelity-bearing path and it must keep reporting the
            // way the source's message channel does. The button is additive, and
            // for an additive control inertness is the stronger feedback: it is
            // continuous and visible before the user commits, where a message is
            // only available after a failed attempt. Emitting a message on a click
            // that the browser already refused would also have to be synthesised,
            // since no click event fires at all.
            // Alternatives Considered: rendering the control enabled with
            // `aria-disabled` and routing its click through `onInvoke` so both
            // paths report identically. Rejected because it buys symmetry with a
            // control that looks unavailable but responds, which is a worse
            // affordance than one that is plainly inert, and because it would
            // change focus order and tab stops to fix a difference that is only
            // observable to someone deliberately comparing the two channels.
            disabled={!binding.enabled}
            // Assumptions: this is stated rather than left to the component's
            // default because the bar is rendered inside screens that are
            // themselves forms, and a control that defaulted to submit would
            // give ENTER two effects at once - the browser's implicit form
            // submission and this bar's own dispatch. Naming the non-submitting
            // type removes that ambiguity at the point it would arise.
            htmlType="button"
            // Assumptions: this advertises the real key to assistive technology
            // instead of describing it in prose, which keeps the keyboard half
            // of the contract discoverable without inventing a user-visible
            // string that no baseline source holds. The value is derived from
            // the same table `usePfKeys` dispatches on.
            aria-keyshortcuts={AID_TO_CANONICAL_BROWSER_KEY[binding.aid]}
            onClick={
              /**
               * Dispatches this key's AID through the caller's validated path.
               * @returns {void} Completion is represented by the caller's own
               * side effects.
               * @throws {unknown} Caller-owned dispatch failures propagate.
               */
              (): void => {
                onInvoke(binding.aid);
              }
            }
          >
            {/*
             * Assumptions: the label is rendered exactly as supplied, with no
             * trimming, so the descriptor carries the mapset's text byte for
             * byte - `app/bms/COACTVW.bms` paints `  F3=Exit ` with two leading
             * spaces and one trailing space, and that value survives into the
             * DOM. HTML's own whitespace collapsing renders it as the operator
             * saw it, so fidelity and appearance need no reconciling here.
             * Assumptions: the accessible name comes from this text rather than
             * from a separate label, because the legend already encodes both the
             * key and its action in one `KEY=Action` string, so an added label
             * could only restate it in words no baseline source holds.
             */}
            {binding.label}
          </Button>
        ),
      )}
    </Flex>
  );
}
