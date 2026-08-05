/**
 * The measured BMS-to-Ant-Design token bridge: the single place a CardDemo
 * design value is written down.
 *
 * Purpose
 * -------
 * Every colour, typography, spacing, radius, elevation and motion value the SPA
 * renders resolves to a named Ant Design token declared in this module. Screens,
 * layout components and `ui/src/theme/antdTheme.ts` import those names from here
 * and never write a design value of their own, which is what makes the
 * zero-hardcoded-values rule enforceable by one reviewable module rather than by
 * discipline spread across 21 screen implementations. Under CSS-variable theming
 * a literal value does not merely duplicate a token — it opts that component out
 * of the theme silently, so a later token change leaves it behind and nothing
 * fails.
 *
 * This module is also the **audit record** for the token snaps. A snap recorded
 * without the alternative it beat is unauditable: a later reader cannot tell
 * whether turquoise became an informational token by reasoning or by accident.
 * Every snapped entry in {@link BMS_SOURCE_HISTOGRAM} therefore carries its
 * measured source value, its measured frequency, and the alternative that was
 * rejected — as data, not only as prose — so the audit is machine-readable.
 *
 * Provenance
 * ----------
 * There is no design specification behind the 3270 screens to appeal to, so the
 * baseline itself is the design source and every figure below is a **measured
 * count** taken from it, never an estimate. The 3270 presentation layer carries
 * real presentational semantics in machine-readable form — the `COLOR`, `HILIGHT`
 * and `ATTRB` operands of each `DFHMDF` field definition — and those operands
 * were counted exhaustively. Sources, all reference-only and never modified:
 *
 * - `app/bms/*.bms` — the 17 base mapsets
 * - `app/app-authorization-ims-db2-mq/bms/COPAU00.bms`, `COPAU01.bms` and
 *   `app/app-transaction-type-db2/bms/COTRTLI.bms`, `COTRTUP.bms` — the 4
 *   extension mapsets
 * - `app/cpy/CSSETATY.cpy` — the field-error highlight template
 * - `app/cbl/CO*.cbl` and the extension screen programs — the `DFH*` colour
 *   constants moved into a field's colour attribute at run time
 *
 * Two populations, never conflated
 * --------------------------------
 * Every count in this module names the population it was measured over, because
 * the two do not merely differ in magnitude — they differ in **rank**, and one
 * `COLOR` operand exists in only one of them:
 *
 * - **Base 17** — the 17 mapsets under `app/bms`: **902** `DFHMDF` definitions.
 * - **All 21** — those plus the 4 extension mapsets: **1166** `DFHMDF`
 *   definitions (`COPAU00` 104, `COTRTLI` 81, `COPAU01` 54, `COTRTUP` 25, so
 *   902 + 264 = 1166).
 *
 * `SIZE=(24,80)` appears in all 21, once each. Field density runs from 24
 * (`COBIL00`) to 128 (`COACTUP`).
 *
 * The reconciliation that proves the measurement
 * ----------------------------------------------
 * Over the base 17, the colour histogram sums to exactly the number of fields
 * carrying a colour operand, and that figure plus the fields carrying none
 * accounts for every field in the population:
 *
 * ```text
 * 289 + 127 + 76 + 60 + 55 + 38 + 17 = 662  fields WITH a COLOR= operand
 *                               902 - 662 = 240  fields WITH NONE
 *                                     662 + 240 = 902  total DFHMDF
 * ```
 *
 * Assumptions: that subtraction is arithmetic rather than coincidence only
 * because **no `DFHMDF` definition in any of the 21 mapsets carries more than one
 * `COLOR=` operand** — checked across both populations, not assumed. Were even one
 * field to carry two, operand occurrences would exceed coloured fields and the
 * 240 would be wrong. The same check over all 21 gives 1166 = 881 + 285. See
 * {@link BMS_MEASURED_FIELD_COUNTS}, which carries these figures as data so a
 * test can assert them rather than a reader having to trust them.
 *
 * Three findings a careful reader will otherwise get wrong
 * -------------------------------------------------------
 * 1. **The literal string `ATTRB=BRT` occurs zero times.** `BRT` is only ever an
 *    operand inside a parenthesised list, so grepping the literal returns nothing
 *    and invites the conclusion that the attribute is unused. It is not: see
 *    {@link BMS_SOURCE_HISTOGRAM}, and grep `ATTRB=(ASKIP,BRT)` and
 *    `ATTRB=(ASKIP,BRT,FSET)` instead.
 * 2. **`COLOR=PINK` is the eighth colour and is invisible to an `app/bms`-only
 *    measurement.** All 4 occurrences are in an extension mapset, so a scope
 *    restricted to the base 17 yields seven colours and reports them as
 *    exhaustive when the baseline uses eight.
 * 3. **`COLOR=GREEN` is the editable-input affordance colour, not a success
 *    colour.** 65 of its 76 base-17 fields are `UNPROT` inputs. Its mapping is a
 *    documented snap, not the exact match it first appears to be.
 *
 * What this module does not own
 * -----------------------------
 * Design values only. Every user-visible string belongs to
 * `ui/src/messages/messages.ts`; the screen title band to
 * `ui/src/layout/ScreenHeader.tsx`; the message band to
 * `ui/src/layout/MessageBand.tsx`; function-key semantics to
 * `ui/src/layout/PfKeyBar.tsx` and `ui/src/layout/usePfKeys.ts`; assembling the
 * theme object to `ui/src/theme/antdTheme.ts`; instantiating the configuration
 * provider to `ui/src/App.tsx`; and dependency pins to `ui/package.json`.
 * Duplicating any of those here would create a second source of truth.
 *
 * WHY (non-obvious design decisions)
 * ----------------------------------
 * Assumptions: the token names below are an external contract of one exactly
 * pinned package version, and they are constrained at compile time against that
 * version's own declarations through {@link AntdTokenName} rather than written as
 * free strings. A token renamed or relocated in a future major version would not
 * otherwise fail a build — a theme object simply carries a property the library
 * no longer reads — so the failure would be **silent** and found by eye. Binding
 * the names to `keyof GlobalToken` converts that class of silent breakage into a
 * compile error, which is why every constant here is typed rather than plain.
 *
 * Assumptions: the bridge is meaningful only because a colour reaches a field
 * through a predictable subfield. All in-program colour moves in the repository
 * target a symbolic-map field whose name ends in `C` — the colour attribute
 * subfield — while the field's data travels through the `O` suffixed subfield and
 * its protection attribute through the `A` suffixed one. That three-subfield
 * convention is the external contract a colour-token map depends on; see
 * {@link FIELD_ERROR_TOKENS} for the one place the baseline writes both a colour
 * and a value.
 *
 * Trade-offs: this module records **token names**, never colour values, swatches
 * or rendered previews. A value would be faster to read and is deliberately
 * absent, because the token name is the thing that is normative — a recorded
 * value silently disagrees with the theme the moment the token behind it moves,
 * and it cannot be diffed against the library the way a name can.
 *
 * Trade-offs: the measured counts are duplicated between this module and
 * `docs/architecture/design-token-reference.md`, which is the prose mirror of the
 * same bridge. Two copies can drift, and the cost is accepted because this module
 * is the machine-readable side: the figures here are typed data a test can assert
 * against, whereas a Markdown table can only be read. Where the two disagree, one
 * of them is defective; the counts here were re-derived directly from the
 * baseline rather than copied across.
 */

import type { GlobalToken } from "antd";

/**
 * Name of any token the design system's theme accepts.
 *
 * WHY this is derived from the library rather than declared as a string union
 * Alternatives Considered: importing `AliasToken`, which is the type the theme's
 * `token` property is declared against and the direct type-level choice. It is rejected
 * on availability, twice over: the package does not re-export `AliasToken` from
 * its root, and reaching it through a deep submodule path is refused by the
 * `no-restricted-imports` rule in `ui/eslint.config.js`, which requires design
 * system imports to come from the package root so the CSS-variables theme
 * reaches them uniformly. `GlobalToken` is root-exported and, because the alias
 * token type transitively extends the map and seed token types, its key set
 * covers all three token layers — verified by compiling every name used in this
 * module against it. A hand-written string union was also considered and
 * rejected: it would restate the library's surface and could drift from it
 * without failing anything.
 */
export type AntdTokenName = keyof GlobalToken;

/**
 * How a measured baseline design value came to rest on its token.
 *
 * WHY the resolution kind is recorded per entry rather than inferred
 * Assumptions: a reader auditing this bridge needs to distinguish a value the
 * system expresses natively from one that was moved onto the nearest token whose
 * role matches, because only the latter carries a judgement that can be
 * disagreed with. Recording the kind makes that distinction queryable instead of
 * requiring the reader to re-derive it from the prose.
 *
 * - `exact` — the system expresses the same role under its own name.
 * - `snap` — no token matches the source value's name, so it resolves onto the
 *   nearest token whose role matches. Every snap names a rejected alternative.
 * - `inherit` — the source declines to specify, so the field falls through to
 *   the base token.
 * - `structural` — the affordance is carried by a component's own rendering, so
 *   no token is required. Recorded so the absence does not read as an oversight.
 * - `additive` — the baseline has no such vocabulary at all, so the value is new
 *   rather than mapped.
 */
export type TokenResolutionKind =
  "exact" | "snap" | "inherit" | "structural" | "additive";

/**
 * The mapset population a measured count was taken over.
 *
 * WHY every count names its population
 * Assumptions: the two populations disagree on more than magnitude. Once the 4
 * extension mapsets are included, `COLOR=NEUTRAL` (90) overtakes `COLOR=GREEN`
 * (84), so a claim about the palette's rank order is true of one population and
 * false of the other; and `COLOR=PINK` exists only in the wider one. A figure
 * quoted without its population is therefore not merely imprecise, it is
 * unverifiable.
 *
 * - `base17` — the 17 mapsets under `app/bms`, 902 `DFHMDF` definitions.
 * - `all21` — those plus the 4 extension mapsets, 1166 `DFHMDF` definitions.
 */
export type MeasurementPopulation = "base17" | "all21";

/**
 * One measured source attribute and the design-system decision made for it.
 *
 * WHY the rejected alternative is data rather than prose alone
 * Assumptions: a snap is auditable only when a consumer can inspect both the
 * selected token and the candidates that were deliberately not selected.
 * Keeping the alternatives on every row lets validation assert that no snap
 * silently loses its rationale.
 */
export interface BmsSourceMeasurement {
  /** The literal BMS operand or the explicitly recorded absence of one. */
  readonly sourceValue: string;
  /** Occurrence totals keyed by the population in which each count was made. */
  readonly counts: Readonly<Record<MeasurementPopulation, number>>;
  /** The Ant Design token selected for the source value, or no token when structural. */
  readonly token: AntdTokenName | null;
  /** The relationship between the source value and the selected token. */
  readonly resolution: TokenResolutionKind;
  /** The role established by inspecting the fields that carry the source value. */
  readonly measuredRole: string;
  /** Concrete alternatives rejected during resolution; empty only when no snap occurred. */
  readonly rejectedAlternative: readonly string[];
}

/**
 * One documented mismatch between the BMS design language and Ant Design.
 *
 * WHY counts remain in their source wording
 * Assumptions: different gaps count different units—mapsets, fields, operands,
 * or no source analogue—so coercing all six into one numeric unit would make
 * unlike measurements appear comparable. The population and unit therefore
 * remain explicit in `measuredCount`.
 */
export interface DesignGap {
  /** Stable identifier used by the AAP and the design-token reference. */
  readonly id: "G1" | "G2" | "G3" | "G4" | "G5" | "G6";
  /** Short statement of the capability mismatch. */
  readonly description: string;
  /** Original BMS value or the recorded absence of a source analogue. */
  readonly sourceValue: string;
  /** Count text naming both the measured population and the unit counted. */
  readonly measuredCount: string;
  /** Complete target treatment, including what is preserved or intentionally omitted. */
  readonly resolution: string;
}

/**
 * Ant Design token selected for every `COLOR=` operand found in the 21 mapsets.
 *
 * Purpose: this is the lookup consumed by screen and layout code; the measured
 * evidence and rejected alternatives remain in {@link BMS_SOURCE_HISTOGRAM}.
 */
export const BMS_COLOR_TOKENS = {
  BLUE: "colorPrimary",
  /*
   * Alternatives Considered: a bespoke turquoise token. Ant Design exposes no
   * turquoise semantic, and a literal hue would bypass the CSS-variable theme.
   */
  TURQUOISE: "colorInfo",
  /*
   * Trade-offs: a bespoke editable-input colour token was rejected because 65
   * of 76 base green fields are inputs whose affordance is already represented
   * structurally by the Input border. `colorSuccess` remains the only
   * green-family semantic and preserves the AAP binding without applying green
   * text to those inputs.
   */
  GREEN: "colorSuccess",
  /*
   * Alternatives Considered: `colorText`. It was rejected because neutral is a
   * de-emphasis role in the baseline, while `colorText` is the base text role.
   */
  NEUTRAL: "colorTextSecondary",
  YELLOW: "colorWarning",
  /*
   * Assumptions: `colorText` is also the fallback for the 240 base fields with
   * no `COLOR=` operand. The seven explicit colours total 662; 662 + 240 = 902,
   * and explicit DEFAULT 38 + absent operand 240 = 278 fields on this token.
   */
  DEFAULT: "colorText",
  RED: "colorError",
  /*
   * Alternatives Considered: `pink`, `colorPrimary`, and `colorInfo`. The
   * palette anchor `pink` carries no component semantic. In COPAU01, PINK marks
   * card number, authorization date, authorization time, and response code—the
   * composite key plus its response—while TURQUOISE marks their labels and the
   * adjacent AUTHRSN value returns to BLUE. `colorPrimary` would collapse PINK
   * into BLUE, and `colorInfo` would collapse each value into its label.
   */
  PINK: "colorTextHeading",
} as const satisfies Record<
  | "BLUE"
  | "TURQUOISE"
  | "GREEN"
  | "NEUTRAL"
  | "YELLOW"
  | "DEFAULT"
  | "RED"
  | "PINK",
  AntdTokenName
>;

/**
 * Tokens for the five colour constants that executable programs move at run time.
 *
 * Assumptions: executable use is the primary count because commented statements
 * cannot repaint a field. `DFHRED` has 38 executable moves and one additional
 * textual move in the column-7 comment at `app/cbl/COACTUPC.cbl:3201`; the
 * resulting totals are 65 executable and 66 textual colour moves. Every target
 * name ends in `C`, the symbolic-map colour subfield.
 * `DFHGREEN` has 10 moves, `DFHNEUTR` 9, `DFHDFCOL` 6, and `DFHBLUE` 2
 * under either counting method. Both `DFHBLUE` moves are extension-only resets
 * in `COTRTLIC.cbl`, which is why an `app/cbl`-only count misses the fifth map
 * entry.
 *
 * Alternatives Considered: entries for `DFHYELLO`, `DFHTURQ`, and `DFHPINK`.
 * They are omitted because exhaustive repository-wide measurement across
 * `.cbl` and `.cpy` files found zero executable or textual moves for all three.
 */
export const DFH_RUNTIME_COLOR_TOKENS = {
  DFHRED: "colorError",
  DFHGREEN: "colorSuccess",
  DFHNEUTR: "colorTextSecondary",
  DFHDFCOL: "colorText",
  DFHBLUE: "colorPrimary",
} as const satisfies Record<
  "DFHRED" | "DFHGREEN" | "DFHNEUTR" | "DFHDFCOL" | "DFHBLUE",
  AntdTokenName
>;

/**
 * Exhaustive measured BMS attribute histogram and its token resolutions.
 *
 * Purpose: this array is the machine-readable G3 audit record. Every row keeps
 * the original operand, both population counts, the selected token, the
 * resolution kind, the measured role, and any rejected alternative together.
 *
 * Assumptions: `ATTRB=BRT` is not a source spelling and occurs zero times.
 * `BRT` appears only inside `ATTRB=(ASKIP,BRT)` and
 * `ATTRB=(ASKIP,BRT,FSET)`, which produce the 37 and 43 counts below.
 */
export const BMS_SOURCE_HISTOGRAM = [
  {
    sourceValue: "COLOR=BLUE",
    counts: { base17: 289, all21: 384 },
    token: "colorPrimary",
    resolution: "exact",
    measuredRole: "Dominant label and frame colour.",
    rejectedAlternative: [],
  },
  {
    sourceValue: "COLOR=TURQUOISE",
    counts: { base17: 127, all21: 157 },
    token: "colorInfo",
    resolution: "snap",
    measuredRole: "Informational labels and values.",
    rejectedAlternative: [
      "A bespoke turquoise semantic token backed by a literal hue.",
    ],
  },
  {
    sourceValue: "COLOR=GREEN",
    counts: { base17: 76, all21: 84 },
    token: "colorSuccess",
    resolution: "snap",
    measuredRole:
      "Editable-input affordance: 65 of 76 base fields are UNPROT and 63 are underlined.",
    rejectedAlternative: ["A bespoke editable-input colour token."],
  },
  {
    sourceValue: "COLOR=NEUTRAL",
    counts: { base17: 60, all21: 90 },
    token: "colorTextSecondary",
    resolution: "snap",
    measuredRole:
      "De-emphasised text; it overtakes green only in the all-21 population.",
    rejectedAlternative: ["colorText, the non-de-emphasised base text role."],
  },
  {
    sourceValue: "COLOR=YELLOW",
    counts: { base17: 55, all21: 70 },
    token: "colorWarning",
    resolution: "exact",
    measuredRole: "Warning and function-key legend emphasis.",
    rejectedAlternative: [],
  },
  {
    sourceValue: "COLOR=DEFAULT",
    counts: { base17: 38, all21: 69 },
    token: "colorText",
    resolution: "inherit",
    measuredRole: "Explicit request for the default text colour.",
    rejectedAlternative: [],
  },
  {
    sourceValue: "COLOR operand absent",
    counts: { base17: 240, all21: 285 },
    token: "colorText",
    resolution: "inherit",
    measuredRole:
      "Unspecified colour, concentrated in editable account and card fields.",
    rejectedAlternative: [],
  },
  {
    sourceValue: "COLOR=RED",
    counts: { base17: 17, all21: 23 },
    token: "colorError",
    resolution: "exact",
    measuredRole:
      "Error emphasis; all 17 base red fields are BRT, while 21 of 23 are BRT across all 21.",
    rejectedAlternative: [],
  },
  {
    sourceValue: "COLOR=PINK",
    counts: { base17: 0, all21: 4 },
    token: "colorTextHeading",
    resolution: "snap",
    measuredRole:
      "Record-identity emphasis for the authorization composite key plus its response code.",
    rejectedAlternative: [
      "pink, a palette anchor with no component semantic.",
      "colorPrimary, already assigned to BLUE and used by the adjacent ordinary value.",
      "colorInfo, already assigned to the TURQUOISE labels on the same rows.",
    ],
  },
  {
    sourceValue: "ATTRB list member BRT",
    counts: { base17: 37, all21: 43 },
    token: "fontWeightStrong",
    resolution: "snap",
    measuredRole:
      "Brightness orthogonal to colour: every bright field also has a colour operand.",
    rejectedAlternative: [
      "A brighter colour token, which would overwrite RED, NEUTRAL, or TURQUOISE.",
    ],
  },
  {
    sourceValue: "HILIGHT=UNDERLINE",
    counts: { base17: 158, all21: 175 },
    token: null,
    resolution: "structural",
    measuredRole: "Editable-field affordance carried by the Input border.",
    rejectedAlternative: [
      "A dedicated underline token duplicating the component border.",
    ],
  },
  {
    sourceValue: "HILIGHT=OFF",
    counts: { base17: 35, all21: 54 },
    token: null,
    resolution: "structural",
    measuredRole: "Explicit no-highlight state requiring no target token.",
    rejectedAlternative: [],
  },
] as const satisfies readonly BmsSourceMeasurement[];

/**
 * Field totals that close each mapset population without double-counting.
 *
 * Purpose: validation can assert that every field belongs to exactly one of the
 * explicit-colour and absent-colour partitions and that neither population
 * contains a field with two colour operands.
 */
export const BMS_MEASURED_FIELD_COUNTS = {
  base17: {
    mapsets: 17,
    fieldDefinitions: 902,
    fieldsWithColorOperand: 662,
    fieldsWithoutColorOperand: 240,
    fieldsWithMultipleColorOperands: 0,
    sizeDeclarations: 17,
  },
  all21: {
    mapsets: 21,
    fieldDefinitions: 1166,
    fieldsWithColorOperand: 881,
    fieldsWithoutColorOperand: 285,
    fieldsWithMultipleColorOperands: 0,
    sizeDeclarations: 21,
  },
} as const;

/**
 * Typography token names assigned to fixed-pitch data, titles, and BRT emphasis.
 *
 * Purpose: consumers select a semantic use from this group without repeating an
 * Ant Design token name or re-deriving a BMS attribute decision.
 */
export const TYPOGRAPHY_TOKENS = {
  /*
   * Assumptions: the 3270 cell grid supplied fixed-width alignment without an
   * explicit font choice. The baseline contains 29 right-justified numeric
   * fields and 5 `PICOUT='+ZZZ,ZZZ,ZZZ.99'` money fields, all 5 in COACTVW;
   * the 4 extension mapsets add no JUSTIFY operands. A proportional face would
   * no longer keep those columns and decimal positions aligned.
   */
  fixedPitchData: "fontFamilyCode",
  /*
   * Alternatives Considered: a larger heading level such as
   * `fontSizeHeading1`. It was rejected because the source title occupies one
   * of 24 rows, while a larger heading consumes extra vertical space on
   * screens whose field count reaches 128.
   */
  screenTitleSize: "fontSizeHeading4",
  screenTitleLineHeight: "lineHeightHeading4",
  /*
   * Alternatives Considered: expressing BRT with a brighter colour. Every one
   * of the 37 base bright fields already has COLOR—RED 17, NEUTRAL 13, or
   * TURQUOISE 7—so colour would collide on every field. Weight preserves both
   * axes; the all-21 figures remain orthogonal at RED 21, NEUTRAL 15, and
   * TURQUOISE 7.
   */
  brightEmphasis: "fontWeightStrong",
} as const satisfies Record<
  | "fixedPitchData"
  | "screenTitleSize"
  | "screenTitleLineHeight"
  | "brightEmphasis",
  AntdTokenName
>;

/**
 * Spacing token names snapped from blank-row gaps and control interiors.
 *
 * Purpose: layout code stays on the Ant Design spacing scale rather than
 * reconstructing fixed terminal-cell geometry.
 *
 * Alternatives Considered: multiplying blank rows by a cell line height for
 * section gaps. That would reintroduce the fixed metric removed by G1 and
 * produce literal off-scale values. Deriving control padding from the source
 * column distance between a label and field was also rejected because that
 * distance varies with label length and describes absolute position, not
 * padding.
 */
export const SPACING_TOKENS = {
  sectionGapLarge: "marginLG",
  sectionGapMedium: "marginMD",
  sectionGapCompact: "marginXS",
  controlPaddingLarge: "paddingLG",
  controlPaddingCompact: "paddingSM",
} as const satisfies Record<
  | "sectionGapLarge"
  | "sectionGapMedium"
  | "sectionGapCompact"
  | "controlPaddingLarge"
  | "controlPaddingCompact",
  AntdTokenName
>;

/**
 * Radius, elevation, and motion token names with no BMS source analogue.
 *
 * Purpose: these additions remain theme-controlled and are explicitly
 * distinguished from measured source mappings.
 *
 * Trade-offs: the 3270 vocabulary has no radius, shadow, or transition value
 * to preserve, so these are additive rather than inferred. Omitting them would
 * leave system components with their own defaults but would hide that choice
 * from the bridge; naming the system tokens records the addition without
 * inventing a literal.
 */
export const ADDITIVE_TOKENS = {
  panelRadius: "borderRadiusLG",
  overlayElevation: "boxShadowSecondary",
  fastMotion: "motionDurationFast",
  standardMotion: "motionDurationMid",
} as const satisfies Record<
  "panelRadius" | "overlayElevation" | "fastMotion" | "standardMotion",
  AntdTokenName
>;

/**
 * Responsive breakpoint token names used by the documented G1 resolution.
 *
 * Purpose: responsive layout code consumes the design system's breakpoint
 * contract rather than deriving thresholds from the fixed 80-column grid.
 *
 * Alternatives Considered: a custom threshold calculated from 80 character
 * cells. It was rejected because glyph metrics do not define the usable width
 * of controls, labels, or tables and would create a literal outside the system
 * breakpoint scale.
 */
export const BREAKPOINT_TOKENS = {
  medium: "screenMD",
  large: "screenLG",
} as const satisfies Record<"medium" | "large", AntdTokenName>;

/**
 * Token and marker that preserve the `CSSETATY.cpy` field-error contract.
 *
 * Purpose: error rendering consumes one shared colour decision and preserves
 * the literal marker used only when a rejected field is blank.
 *
 * Refactoring Rationale: the baseline writes `DFHRED` to the `…C` colour
 * subfield and `'*'` to the distinct `…O` output-data subfield, but only when
 * `CDEMO-PGM-REENTER` is true. The target retains both observable outcomes
 * through a Form.Item error state plus the marker, while severing the re-entry
 * gate: the stateless response has no remembered first-turn/re-entry state, so
 * the response body alone drives the error state. The stray `ACSHLIM` text on
 * the source comment is recorded as an observed reference artifact and is not
 * treated as a changed baseline defect.
 */
export const FIELD_ERROR_TOKENS = {
  errorColor: "colorError",
  blankMarker: "*",
} as const satisfies {
  readonly errorColor: AntdTokenName;
  readonly blankMarker: "*";
};

/**
 * Complete G1–G6 register for mismatches between BMS and Ant Design.
 *
 * Purpose: each gap retains the measured source, the population and unit of its
 * count, and the decided treatment so an absent token or component cannot be
 * mistaken for unfinished implementation.
 *
 * Assumptions: all six gaps are resolved, none blocks the migration, none
 * requires a placeholder component, and none requires design-system-team
 * follow-up. G6 is recorded to close the evidence set, not because a missing
 * Figma artifact is a system defect.
 */
export const DESIGN_GAPS = [
  /*
   * Refactoring Rationale: absolute grid coordinates cannot provide reliable
   * DOM reading order for assistive technology, and a fixed character grid can
   * only scale or clip rather than reflow. Responsive layout therefore
   * preserves field grouping, reading order, and tab order, but deliberately
   * does not preserve pixel-for-character positioning.
   */
  {
    id: "G1",
    description: "No Ant Design equivalent of the fixed 24-by-80 grid.",
    sourceValue: "SIZE=(24,80) with absolute POS=(row,column).",
    measuredCount:
      "17 of 17 base and 4 of 4 extension mapsets; 902 base and 1166 all-21 fields.",
    resolution:
      "Use responsive Layout with Descriptions for details and Table for lists, keyed to screenMD and screenLG; preserve grouping, reading order, and tab order rather than absolute character positions.",
  },
  /*
   * Refactoring Rationale: the original shorthand cited the 6 protected
   * `(ASKIP,DRK,FSET)` carriers as password evidence. The measured operand
   * breakdown separates 10 protected fields from 4 unprotected password-style
   * fields, preventing protected carriers from becoming interactive controls.
   */
  {
    id: "G2",
    description:
      "DRK suppresses display, while a browser password control displays entry dots.",
    sourceValue:
      "DRK across five ATTRB combinations, separated by PROT and UNPROT.",
    measuredCount:
      "Base17: 14 total, with 10 protected and 4 unprotected; all21: 18 total, with 14 protected and 4 unprotected.",
    resolution:
      "Apply Input.Password with visibilityToggle disabled only to the 4 unprotected fields; retain keystroke-registration feedback with no change to secret-value behaviour, and do not render protected carriers as password inputs.",
  },
  /*
   * Alternatives Considered: literal turquoise, neutral, green, or pink hues.
   * Literals would bypass the CSS-variable theme, while the selected semantic
   * tokens retain the measured roles and the histogram retains every original.
   */
  {
    id: "G3",
    description:
      "No direct semantic tokens for turquoise, neutral, green-as-input, or pink identity emphasis.",
    sourceValue: "COLOR=TURQUOISE, COLOR=NEUTRAL, COLOR=GREEN, and COLOR=PINK.",
    measuredCount:
      "Base17/all21: TURQUOISE 127/157, NEUTRAL 60/90, GREEN 76/84, PINK 0/4.",
    resolution:
      "Snap to colorInfo, colorTextSecondary, colorSuccess, and colorTextHeading; retain every source value, count, role, and rejected alternative in BMS_SOURCE_HISTOGRAM.",
  },
  /*
   * Alternatives Considered: a dedicated underline or no-highlight token. Both
   * were rejected because the component border already carries the editable
   * affordance and OFF explicitly requests the absence of extra highlighting.
   */
  {
    id: "G4",
    description: "HILIGHT operands have no theme-token equivalent.",
    sourceValue: "HILIGHT=UNDERLINE and HILIGHT=OFF.",
    measuredCount: "Base17/all21: UNDERLINE 158/175 and OFF 35/54.",
    resolution:
      "Carry the input affordance structurally with the Input border; add no token for UNDERLINE or the explicit OFF state.",
  },
  /*
   * Trade-offs: radius, elevation, and motion are additive because the source
   * has no corresponding vocabulary. Applying only system tokens records the
   * additions while keeping them controlled by the shared theme.
   */
  {
    id: "G5",
    description: "The 3270 vocabulary has no radius, elevation, or motion.",
    sourceValue: "No BMS source analogue.",
    measuredCount: "Not countable in either population.",
    resolution:
      "Use borderRadiusLG, boxShadowSecondary, motionDurationFast, and motionDurationMid as explicitly additive system tokens.",
  },
  /*
   * Assumptions: the exhaustive BMS attribute measurement is the available
   * design source. Inventing a Figma mapping would create evidence that was
   * never supplied and could not be verified.
   */
  {
    id: "G6",
    description: "No Figma design source or attachment exists.",
    sourceValue: "No Figma file, frame, URL, or attachment was provided.",
    measuredCount: "Not applicable to either BMS population.",
    resolution:
      "Not a system gap: the Figma-to-token mapping table is NOT APPLICABLE, and the measured BMS attributes are the authoritative design source.",
  },
] as const satisfies readonly DesignGap[];
