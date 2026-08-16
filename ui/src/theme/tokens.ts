/**
 * @file The measured BMS-to-Ant-Design token bridge: the single place a CardDemo design
 * value is written down.
 *
 * Purpose
 * -------
 * Every colour, typography, spacing, radius, elevation and motion value the SPA
 * renders resolves to a named Ant Design token declared here. Screens, layout
 * components and `ui/src/theme/antdTheme.ts` import those names and never write a
 * design value of their own, which is what makes the zero-hardcoded-values rule
 * enforceable by one reviewable module rather than by discipline spread across every
 * screen. Under CSS-variable theming a literal does not merely duplicate a token — it
 * opts that component out of the theme silently, so a later token change leaves it
 * behind and nothing fails.
 *
 * This module is also the machine-readable half of the token audit. A snap recorded
 * without the alternative it beat is unauditable, so every snapped entry in
 * {@link BMS_SOURCE_HISTOGRAM} carries its measured source value, its measured
 * frequency and the rejected alternative AS DATA, and
 * {@link BMS_MEASURED_FIELD_COUNTS} carries the field-population figures so a test
 * can assert them.
 *
 * `docs/architecture/design-token-reference.md` is the prose half: it holds the full
 * per-value derivation, the reconciliation that proves the counts, the exhaustive
 * mapping table and the commands that re-measure the baseline. Trade-offs: the counts
 * therefore exist in two places and can drift. Accepted because only one of the two
 * can be asserted by a test, and that is this one.
 *
 * Provenance (reference-only; `app/**` is never modified): there is no design
 * specification behind the 3270 screens, so the baseline IS the design source and
 * every figure here is a measured count from the `COLOR`, `HILIGHT` and `ATTRB`
 * operands of `app/bms/*.bms`, the four extension mapsets, `app/cpy/CSSETATY.cpy` and
 * the `DFH*` colour constants in the online programs.
 *
 * Assumptions: every count names the population it was measured over — the base 17
 * mapsets or all 21 — because the two differ in RANK and not only in magnitude, and
 * one `COLOR` operand exists in only one of them. Conflating them reports seven
 * colours as exhaustive where the baseline uses eight.
 *
 * Design decisions
 * ----------------
 * Assumptions: the token names are an external contract of one exactly pinned package
 * version, so they are constrained at compile time against that version's own
 * declarations through {@link AntdTokenName} rather than written as free strings. A
 * token renamed in a future major version would not otherwise fail a build — a theme
 * object simply carries a property the library no longer reads — so the breakage would
 * be SILENT. Binding the names to `keyof GlobalToken` converts that into a compile
 * error, which is why every constant here is typed.
 *
 * Assumptions: the bridge is meaningful only because a colour reaches a field through
 * a predictable subfield. Every in-program colour move targets a symbolic-map field
 * whose name ends in `C`, the colour attribute subfield, while the data travels through
 * the `O` subfield and the protection attribute through the `A` one. That convention is
 * the external contract a colour-token map depends on; {@link FIELD_ERROR_TOKENS} is
 * the one place the baseline writes both a colour and a value.
 *
 * Trade-offs: this module records token NAMES, never colour values, swatches or
 * previews. A value would be faster to read and is deliberately absent, because the
 * name is what is normative — a recorded value silently disagrees with the theme the
 * moment the token behind it moves, and it cannot be diffed against the library the
 * way a name can.
 *
 * Assumptions: design values only. Every user-visible string belongs to
 * `ui/src/messages/messages.ts`, the title band to `ui/src/layout/ScreenHeader.tsx`,
 * the message band to `ui/src/layout/MessageBand.tsx`, function-key semantics to
 * `ui/src/layout/PfKeyBar.tsx` and `usePfKeys.ts`, theme assembly to
 * `ui/src/theme/antdTheme.ts`, provider instantiation to `ui/src/App.tsx` and
 * dependency pins to `ui/package.json`.
 */

import type { GlobalToken } from 'antd';

/**
 * Name of any token the design system's theme accepts.
 *
 * Alternatives Considered: this name type is derived from the library's own
 * declarations rather than declared as a hand-written string union. The direct
 * type-level choice was importing `AliasToken`, which is the type the theme's
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
 * Assumptions: the resolution kind is recorded on every entry rather than
 * inferred from the value, because a reader auditing this bridge needs to distinguish a value the
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
export type TokenResolutionKind = 'exact' | 'snap' | 'inherit' | 'structural' | 'additive';

/**
 * The mapset population a measured count was taken over.
 *
 * Assumptions: every count names the population it was taken over, because the
 * two populations disagree on more than magnitude. Once the 4
 * extension mapsets are included, `COLOR=NEUTRAL` (90) overtakes `COLOR=GREEN`
 * (84), so a claim about the palette's rank order is true of one population and
 * false of the other; and `COLOR=PINK` exists only in the wider one. A figure
 * quoted without its population is therefore not merely imprecise, it is
 * unverifiable.
 *
 * - `base17` — the 17 mapsets under `app/bms`, 902 `DFHMDF` definitions.
 * - `all21` — those plus the 4 extension mapsets, 1166 `DFHMDF` definitions.
 */
export type MeasurementPopulation = 'base17' | 'all21';

/**
 * One measured source attribute and the design-system decision made for it.
 *
 * Assumptions: the rejected alternative is carried as data rather than as prose
 * alone, because a snap is auditable only when a consumer can inspect both the
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
 * Assumptions: counts remain in their source wording because different gaps
 * count different units—mapsets, fields, operands,
 * or no source analogue—so coercing all six into one numeric unit would make
 * unlike measurements appear comparable. The population and unit therefore
 * remain explicit in `measuredCount`.
 */
export interface DesignGap {
  /**
   * Stable identifier used by the AAP and the design-token reference.
   *
   * Refactoring Rationale: the union carried `G1` to `G6` because those are the
   * six gaps the migration plan enumerates. Two further mismatches were then
   * MEASURED in a browser against the delivered screens rather than derived from
   * the mapsets, and both were recorded only as inline markers at the single
   * element where each was observed - which put a design-system decision in a
   * screen instead of in this bridge, and left the register claiming to be
   * complete while two entries lived elsewhere. They are admitted here as `G7`
   * and `G8` so the register is the whole answer again.
   */
  readonly id: 'G1' | 'G2' | 'G3' | 'G4' | 'G5' | 'G6' | 'G7' | 'G8';
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
  BLUE: 'colorPrimary',
  /*
   * Alternatives Considered: a bespoke turquoise token. Ant Design exposes no
   * turquoise semantic, and a literal hue would bypass the CSS-variable theme.
   *
   * Refactoring Rationale: the token name resolves the ROLE, and a second
   * decision is required to stop the role rendering as blue. At the pinned
   * version the informational and primary colour seeds hold the same value, so
   * these two distinct names derived one rendered colour and the 157 turquoise
   * field definitions were indistinguishable from the 384 blue ones — the same
   * collapse this module rejects `colorPrimary` for on the PINK entry below.
   * The separation is made once, in the seed layer, from the palette anchor
   * named in {@link BMS_SEED_PALETTE_ANCHORS} and applied by
   * `ui/src/theme/antdTheme.ts`. It is recorded here so a reader of this entry
   * alone does not conclude the two roles still collapse.
   */
  TURQUOISE: 'colorInfo',
  /*
   * Trade-offs: a bespoke editable-input colour token was rejected because 65
   * of 76 base green fields are inputs whose affordance is already represented
   * structurally by the Input border. `colorSuccess` remains the only
   * green-family semantic and preserves the AAP binding without applying green
   * text to those inputs.
   */
  GREEN: 'colorSuccess',
  /*
   * Alternatives Considered: `colorText`. It was rejected because neutral is a
   * de-emphasis role in the baseline, while `colorText` is the base text role.
   */
  NEUTRAL: 'colorTextSecondary',
  YELLOW: 'colorWarning',
  /*
   * Assumptions: `colorText` is also the fallback for the 240 base fields with
   * no `COLOR=` operand. The seven explicit colours total 662; 662 + 240 = 902,
   * and explicit DEFAULT 38 + absent operand 240 = 278 fields on this token.
   */
  DEFAULT: 'colorText',
  RED: 'colorError',
  /*
   * Alternatives Considered: `pink`, `colorPrimary`, and `colorInfo`. The
   * palette anchor `pink` carries no component semantic. In COPAU01, PINK marks
   * card number, authorization date, authorization time, and response code—the
   * composite key plus its response—while TURQUOISE marks their labels and the
   * adjacent AUTHRSN value returns to BLUE. `colorPrimary` would collapse PINK
   * into BLUE, and `colorInfo` would collapse each value into its label.
   */
  PINK: 'colorTextHeading',
} as const satisfies Record<
  'BLUE' | 'TURQUOISE' | 'GREEN' | 'NEUTRAL' | 'YELLOW' | 'DEFAULT' | 'RED' | 'PINK',
  AntdTokenName
>;

/**
 * One text-grade resolution: the role, the shade its own ramp offered, and what was chosen.
 *
 * Purpose: make the accessibility half of the colour bridge auditable AS DATA, the way
 * {@link BmsSourceMeasurement} makes the hue half auditable. A row records the contrast the
 * in-family shade actually measures, so a reader can see that a snap was forced by the palette
 * rather than chosen by taste.
 *
 * Assumptions: every ratio is measured against {@link TEXT_CONTRAST_SURFACE} and no other
 * background, which is why {@link SURFACE_TOKENS} exists — the shell paints its three zones on
 * that one surface so a single measurement is the whole answer for screen text. A row therefore
 * carries one number rather than one number per zone.
 */
export interface TextGradeResolution {
  /** BMS colour operand this row resolves for text, spelled as {@link BMS_COLOR_TOKENS} keys it. */
  readonly role: keyof typeof BMS_COLOR_TOKENS;
  /** Darkest shade the role's OWN semantic ramp publishes as a token name. */
  readonly inFamilyToken: AntdTokenName;
  /** Contrast that in-family shade measures against {@link TEXT_CONTRAST_SURFACE}. */
  readonly inFamilyRatio: number;
  /** Token selected for TEXT in this role. */
  readonly token: AntdTokenName;
  /** Contrast the selected token measures against {@link TEXT_CONTRAST_SURFACE}. */
  readonly ratio: number;
  /** Whether the hue family survived, or the role snapped out of it to reach the threshold. */
  readonly resolution: Extract<TokenResolutionKind, 'exact' | 'snap'>;
}

/**
 * Contrast threshold every text pairing in this tree is required to reach.
 *
 * Assumptions: 4.5 is WCAG 2.1 AA for NORMAL text, and normal is the only size the bridge may
 * assume. The 3:1 large-text allowance is deliberately not used: the source screens are label and
 * value pairs at one terminal cell height, so no measured BMS text role is large text, and
 * claiming the weaker threshold would let a role pass on a size the screens do not render it at.
 */
export const TEXT_CONTRAST_THRESHOLD = 4.5;

/**
 * Surface every ratio in {@link BMS_TEXT_CONTRAST_AUDIT} is measured against.
 *
 * Assumptions: named as the token rather than as a colour value, for the same reason nothing else
 * here carries a value. `ui/src/layout/AppShell.tsx` paints the header, body and legend zones with
 * {@link SURFACE_TOKENS}.screen, so this is the background text actually renders on — the design
 * system's own default would instead have put screen text on the dark header fill and the layout
 * fill, which is the pairing that measured 4.49:1 and 3.76:1 and failed.
 */
export const TEXT_CONTRAST_SURFACE: AntdTokenName = 'colorBgContainer';

/**
 * Surfaces the shell paints its zones with, so text contrast has one measurable background.
 *
 * Refactoring Rationale: the shell used to leave `Layout.Header` and `Layout` on the design
 * system's own defaults — a dark navy header fill and a grey layout fill. That made the SAME text
 * token measure three different ratios depending on which zone it appeared in, and the header
 * pairing measured 4.49:1 against a 4.5:1 requirement: a failure a token map cannot fix, because
 * the deficient half was the background. Naming one surface for all three zones is what lets
 * {@link BMS_TEXT_COLOR_TOKENS} be verified once and hold everywhere.
 *
 * Trade-offs: the dark header band is given up. It is antd's default rather than a measured source
 * value — the 3270 screens paint no such band, they paint coloured text on one uniform display —
 * so nothing transcribed is lost, and what is gained is that every screen's text sits on the
 * surface its contrast was measured against.
 */
export const SURFACE_TOKENS = {
  /** Surface the shell's header, body and legend zones are painted with. */
  screen: 'colorBgContainer',
  /**
   * Surface the shell's title band is painted on, against the layout surface beneath it.
   *
   * Assumptions: this is the SAME token as `screen`, and the duplication is deliberate rather than
   * redundant -- it records that the band was measured against the same surface every other zone is,
   * so the contrast audit's one surface covers it too. A future change that gave the band its own
   * background would change this entry and leave the audit's surface visible as the thing to re-measure.
   */
  titleBand: 'colorBgContainer',
} as const satisfies Record<'screen' | 'titleBand', AntdTokenName>;

/**
 * Token each BMS colour role resolves to when it is painted as TEXT.
 *
 * Purpose: the companion to {@link BMS_COLOR_TOKENS}. That map answers "which semantic role does
 * this source colour carry"; this one answers "which shade of that role may be read as text on
 * {@link TEXT_CONTRAST_SURFACE}". Both are needed because the design system's semantic colours are
 * mid-ramp palette anchors intended for fills, borders and icons, and only two of the eight reach
 * the AA threshold for normal text at all.
 *
 * Refactoring Rationale: screens used to paint text straight from {@link BMS_COLOR_TOKENS}, which
 * shipped measured accessibility failures — the informational role at 2.21:1 for every label and
 * prompt, and the primary role at 4.10:1 for every hint and value. Documenting those ratios was
 * the previous resolution and it is withdrawn: a recorded measurement does not make text readable.
 * The hue mapping in {@link BMS_COLOR_TOKENS} is unchanged and still governs fills, borders and
 * icons, so the AAP's measured BLUE-to-primary and TURQUOISE-to-informational bridge stands; this
 * map only decides which shade of it text is allowed to use.
 *
 * Assumptions: two roles keep their hue family and six do not, and which is which was measured
 * rather than chosen — see {@link BMS_TEXT_CONTRAST_AUDIT} for the per-role numbers. The primary
 * and error ramps each publish a text-grade shade that clears the threshold; the informational,
 * success and warning ramps publish nothing darker than 3.55:1, 3.46:1 and 2.87:1, so a role on
 * one of those ramps cannot be text in its own hue at this version at all.
 *
 * Alternatives Considered: three. Darkening the seeds so the ramps would derive AA shades — this
 * would move every fill, border and background tint derived from them to defend a text case, and
 * the value to seed with could only be a literal, which this module admits nowhere. Overriding the
 * derived text shades directly in the theme — same literal problem, and it pins one shade of a
 * ten-step ramp while the other nine keep deriving from a value it contradicts. Claiming the
 * large-text 3:1 allowance — rejected because no measured BMS text role is large text.
 */
export const BMS_TEXT_COLOR_TOKENS = {
  /** 6.16:1 — the primary ramp's own text-grade shade, so BLUE text keeps its hue. */
  BLUE: 'colorPrimaryTextActive',
  /** Snap: the cyan ramp publishes nothing above 3.55:1, and labels are its measured role. */
  TURQUOISE: 'colorTextLabel',
  /** Snap: the green ramp stops at 3.46:1, and its measured role is an input affordance, not text. */
  GREEN: 'colorText',
  /** 6.98:1 — already a text token, so the de-emphasis role is unchanged. */
  NEUTRAL: 'colorTextSecondary',
  /** Snap: the gold ramp stops at 2.87:1, the worst of the three, so legend text takes base text. */
  YELLOW: 'colorText',
  /** 16.56:1 — the base text role, unchanged. */
  DEFAULT: 'colorText',
  /** 4.62:1 — the error ramp's own text-grade shade, so a refusal keeps its red. */
  RED: 'colorErrorTextActive',
  /** 16.56:1 — a heading text token already, so record-identity emphasis is unchanged. */
  PINK: 'colorTextHeading',
} as const satisfies Record<keyof typeof BMS_COLOR_TOKENS, AntdTokenName>;

/**
 * Measured text-grade resolution for all eight colour roles.
 *
 * Purpose: the machine-readable record behind {@link BMS_TEXT_COLOR_TOKENS}, so a test can assert
 * that every role reaches {@link TEXT_CONTRAST_THRESHOLD} and that each snap names the in-family
 * shade it beat. Ratios were computed from the pinned package's own `theme.getDesignToken`
 * accessor under this application's theme — the separated informational seed included — against
 * {@link TEXT_CONTRAST_SURFACE}.
 *
 * Assumptions: `inFamilyToken` is the DARKEST shade each ramp publishes as an alias name at this
 * version, which is the `…TextActive` member for the five semantic ramps and the role's own token
 * for the three that are already text roles. Recording a shade the library does not publish would
 * describe an option this tree never had.
 */
export const BMS_TEXT_CONTRAST_AUDIT = [
  {
    role: 'BLUE',
    inFamilyToken: 'colorPrimaryTextActive',
    inFamilyRatio: 6.159,
    token: 'colorPrimaryTextActive',
    ratio: 6.159,
    resolution: 'exact',
  },
  {
    role: 'TURQUOISE',
    inFamilyToken: 'colorInfoTextActive',
    inFamilyRatio: 3.549,
    token: 'colorTextLabel',
    ratio: 6.978,
    resolution: 'snap',
  },
  {
    role: 'GREEN',
    inFamilyToken: 'colorSuccessTextActive',
    inFamilyRatio: 3.463,
    token: 'colorText',
    ratio: 16.558,
    resolution: 'snap',
  },
  {
    role: 'NEUTRAL',
    inFamilyToken: 'colorTextSecondary',
    inFamilyRatio: 6.978,
    token: 'colorTextSecondary',
    ratio: 6.978,
    resolution: 'exact',
  },
  {
    role: 'YELLOW',
    inFamilyToken: 'colorWarningTextActive',
    inFamilyRatio: 2.867,
    token: 'colorText',
    ratio: 16.558,
    resolution: 'snap',
  },
  {
    role: 'DEFAULT',
    inFamilyToken: 'colorText',
    inFamilyRatio: 16.558,
    token: 'colorText',
    ratio: 16.558,
    resolution: 'exact',
  },
  {
    role: 'RED',
    inFamilyToken: 'colorErrorTextActive',
    inFamilyRatio: 4.618,
    token: 'colorErrorTextActive',
    ratio: 4.618,
    resolution: 'exact',
  },
  {
    role: 'PINK',
    inFamilyToken: 'colorTextHeading',
    inFamilyRatio: 16.558,
    token: 'colorTextHeading',
    ratio: 16.558,
    resolution: 'exact',
  },
] as const satisfies readonly TextGradeResolution[];

/**
 * Preset palette anchor whose hue each separated semantic colour seed is taken
 * from.
 *
 * Purpose: the informational and primary semantic tokens ship with the same seed
 * value, so `TURQUOISE` and `BLUE` are two names for one rendered colour until
 * the informational seed is separated. This map records which of the design
 * system's OWN palette anchors carries each of those two hues, and
 * `ui/src/theme/antdTheme.ts` reads the anchor's value out of the library's
 * default seed: the turquoise anchor supplies the separated informational seed,
 * and the blue anchor is the reference the link seed is pinned to so that it does
 * not follow the informational role away from the library's default. Exactly
 * these two entries exist; every other {@link BMS_COLOR_TOKENS} entry already
 * derives a distinct value and needs no anchor.
 *
 * Assumptions: each anchor is a token NAME and never a colour value, so the
 * names-not-values invariant survives here — `blue` and `cyan` are settable tokens of
 * the seed layer, so the hue is read from the library at run time. A hex literal for
 * turquoise has no recorded origin, cannot be diffed against the library, and would
 * keep its value through a palette change while everything around it moved. The names
 * are constrained to {@link AntdTokenName} so a palette renamed by a future major
 * version fails compilation rather than silently supplying `undefined`.
 *
 * Alternatives Considered: an anchor for every one of the eight measured colours, for
 * symmetry. Rejected because seven already resolve to distinct derived values, so
 * those entries would restate what the library supplies and pin seven palettes to
 * defend one distinction.
 */
export const BMS_SEED_PALETTE_ANCHORS = {
  /** Anchor behind `colorPrimary`, kept as the reference the link role follows. */
  BLUE: 'blue',
  /** Nearest anchor to 3270 turquoise, and the system's only cyan-family hue. */
  TURQUOISE: 'cyan',
} as const satisfies Record<'BLUE' | 'TURQUOISE', AntdTokenName>;

/**
 * Tokens for the five colour constants that executable programs move at run time.
 *
 * Assumptions: executable moves are the primary count, because a commented statement
 * cannot repaint a field, and every target name ends in `C`, the symbolic-map colour
 * subfield. Both `DFHBLUE` moves are extension-only resets in `COTRTLIC.cbl`, which is
 * why an `app/cbl`-only count misses the fifth entry.
 * `docs/architecture/design-token-reference.md` carries the per-constant tallies.
 *
 * Alternatives Considered: entries for `DFHYELLO`, `DFHTURQ` and `DFHPINK`. Omitted
 * because exhaustive repository-wide measurement across `.cbl` and `.cpy` found zero
 * executable or textual moves for all three.
 */
export const DFH_RUNTIME_COLOR_TOKENS = {
  DFHRED: 'colorError',
  DFHGREEN: 'colorSuccess',
  DFHNEUTR: 'colorTextSecondary',
  DFHDFCOL: 'colorText',
  DFHBLUE: 'colorPrimary',
} as const satisfies Record<
  'DFHRED' | 'DFHGREEN' | 'DFHNEUTR' | 'DFHDFCOL' | 'DFHBLUE',
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
    sourceValue: 'COLOR=BLUE',
    counts: { base17: 289, all21: 384 },
    token: 'colorPrimary',
    resolution: 'exact',
    measuredRole: 'Dominant label and frame colour.',
    rejectedAlternative: [],
  },
  {
    sourceValue: 'COLOR=TURQUOISE',
    counts: { base17: 127, all21: 157 },
    token: 'colorInfo',
    resolution: 'snap',
    measuredRole: 'Informational labels and values.',
    rejectedAlternative: ['A bespoke turquoise semantic token backed by a literal hue.'],
  },
  {
    sourceValue: 'COLOR=GREEN',
    counts: { base17: 76, all21: 84 },
    token: 'colorSuccess',
    resolution: 'snap',
    measuredRole:
      'Editable-input affordance: 65 of 76 base fields are UNPROT and 63 are underlined.',
    rejectedAlternative: ['A bespoke editable-input colour token.'],
  },
  {
    sourceValue: 'COLOR=NEUTRAL',
    counts: { base17: 60, all21: 90 },
    token: 'colorTextSecondary',
    resolution: 'snap',
    measuredRole: 'De-emphasised text; it overtakes green only in the all-21 population.',
    rejectedAlternative: ['colorText, the non-de-emphasised base text role.'],
  },
  {
    sourceValue: 'COLOR=YELLOW',
    counts: { base17: 55, all21: 70 },
    token: 'colorWarning',
    resolution: 'exact',
    measuredRole: 'Warning and function-key legend emphasis.',
    rejectedAlternative: [],
  },
  {
    sourceValue: 'COLOR=DEFAULT',
    counts: { base17: 38, all21: 69 },
    token: 'colorText',
    resolution: 'inherit',
    measuredRole: 'Explicit request for the default text colour.',
    rejectedAlternative: [],
  },
  {
    sourceValue: 'COLOR operand absent',
    counts: { base17: 240, all21: 285 },
    token: 'colorText',
    resolution: 'inherit',
    measuredRole: 'Unspecified colour, concentrated in editable account and card fields.',
    rejectedAlternative: [],
  },
  {
    sourceValue: 'COLOR=RED',
    counts: { base17: 17, all21: 23 },
    token: 'colorError',
    resolution: 'exact',
    measuredRole:
      'Error emphasis; all 17 base red fields are BRT, while 21 of 23 are BRT across all 21.',
    rejectedAlternative: [],
  },
  {
    sourceValue: 'COLOR=PINK',
    counts: { base17: 0, all21: 4 },
    token: 'colorTextHeading',
    resolution: 'snap',
    measuredRole:
      'Record-identity emphasis for the authorization composite key plus its response code.',
    rejectedAlternative: [
      'pink, a palette anchor with no component semantic.',
      'colorPrimary, already assigned to BLUE and used by the adjacent ordinary value.',
      'colorInfo, already assigned to the TURQUOISE labels on the same rows.',
    ],
  },
  {
    sourceValue: 'ATTRB list member BRT',
    counts: { base17: 37, all21: 43 },
    token: 'fontWeightStrong',
    resolution: 'snap',
    measuredRole: 'Brightness orthogonal to colour: every bright field also has a colour operand.',
    rejectedAlternative: [
      'A brighter colour token, which would overwrite RED, NEUTRAL, or TURQUOISE.',
    ],
  },
  {
    sourceValue: 'HILIGHT=UNDERLINE',
    counts: { base17: 158, all21: 175 },
    token: null,
    resolution: 'structural',
    measuredRole: 'Editable-field affordance carried by the Input border.',
    rejectedAlternative: ['A dedicated underline token duplicating the component border.'],
  },
  {
    sourceValue: 'HILIGHT=OFF',
    counts: { base17: 35, all21: 54 },
    token: null,
    resolution: 'structural',
    measuredRole: 'Explicit no-highlight state requiring no target token.',
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
  fixedPitchData: 'fontFamilyCode',
  /*
   * Alternatives Considered: a larger heading level such as
   * `fontSizeHeading1`. It was rejected because the source title occupies one
   * of 24 rows, while a larger heading consumes extra vertical space on
   * screens whose field count reaches 128.
   */
  screenTitleSize: 'fontSizeHeading4',
  screenTitleLineHeight: 'lineHeightHeading4',
  /*
   * Alternatives Considered: expressing BRT with a brighter colour. Every one
   * of the 37 base bright fields already has COLOR—RED 17, NEUTRAL 13, or
   * TURQUOISE 7—so colour would collide on every field. Weight preserves both
   * axes; the all-21 figures remain orthogonal at RED 21, NEUTRAL 15, and
   * TURQUOISE 7.
   */
  brightEmphasis: 'fontWeightStrong',
} as const satisfies Record<
  'fixedPitchData' | 'screenTitleSize' | 'screenTitleLineHeight' | 'brightEmphasis',
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
  sectionGapLarge: 'marginLG',
  sectionGapMedium: 'marginMD',
  sectionGapCompact: 'marginXS',
  controlPaddingLarge: 'paddingLG',
  controlPaddingCompact: 'paddingSM',
} as const satisfies Record<
  | 'sectionGapLarge'
  | 'sectionGapMedium'
  | 'sectionGapCompact'
  | 'controlPaddingLarge'
  | 'controlPaddingCompact',
  AntdTokenName
>;

/**
 * Token names for BMS colours rendered as PROSE TEXT, and the surface that text sits on.
 *
 * Purpose
 * -------
 * {@link BMS_COLOR_TOKENS} resolves each measured `COLOR=` operand to the semantic ROLE it
 * carries. This map answers a second, narrower question the role cannot: which shade of that
 * role's ramp is legible when the colour is used for a run of text the operator has to READ,
 * rather than for a component state, a border or a fill. Two entries exist, because a review
 * measured two pairings in the delivered tree that fell below the WCAG AA 4.5:1 minimum for
 * normal text, and both were shade-selection problems rather than role-selection ones.
 *
 * What was measured, and what each entry changes
 * ---------------------------------------------
 * Every figure below is a contrast ratio computed from the values the pinned package derives
 * through its own `theme.getDesignToken` accessor under the theme in `antdTheme.ts`:
 *
 * - The shell's title band painted `colorPrimary` `#1677ff` on `Layout.Header`'s default
 *   `headerBg` `#001529`, measured at **4.49:1** across twelve nodes -- the six prompt and value
 *   pairs `ScreenHeader` renders -- plus the skip link. The same/*
 * WHY : Refactoring Rationale: a SECOND BMS_TEXT_COLOR_TOKENS stood here declaring only BLUE and
 *       TURQUOISE, and it is withdrawn in favour of the eight-role map above. Two declarations of one
 *       exported name is not something the module system tolerates, and the choice between them is
 *       settled by evidence rather than recency: BMS_TEXT_CONTRAST_AUDIT records a measured text-grade
 *       resolution for all EIGHT roles, and src/theme/textContrast.test.ts asserts the map against that
 *       audit -- so a two-role map fails the audit for the six roles it omits, and a screen painting
 *       RED or NEUTRAL prose had no entry to read at all.
 * WHY : Assumptions: the withdrawn version's TURQUOISE value is not carried over. It named the cyan
 *       ramp at step 8; the audit measures the retained snap, colorTextLabel, at 6.978:1 against the
 *       same surface, which clears the 4.5:1 AA threshold with more margin than the 6.10:1 the
 *       withdrawn entry claimed. Keeping the audited value is what keeps the assertion and the map
 *       describing one decision.
 */

/*
 * WHY : Refactoring Rationale: a SECOND SURFACE_TOKENS stood here carrying a `titleBand` key, and it is
 *       folded into the map above rather than kept beside it -- one exported name cannot be declared
 *       twice, and the two were describing the same decision from two directions. Both keys are
 *       retained there: `screen`, which AppShell paints all three shell zones with, and `titleBand`,
 *       which the title band reads. They resolve to the same token deliberately, and that is the point
 *       the withdrawn block was making: the band is not a separately coloured surface, so a reader who
 *       finds only one of the two keys should not conclude the other zone is unspecified.
 */

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
  panelRadius: 'borderRadiusLG',
  overlayElevation: 'boxShadowSecondary',
  fastMotion: 'motionDurationFast',
  standardMotion: 'motionDurationMid',
} as const satisfies Record<
  'panelRadius' | 'overlayElevation' | 'fastMotion' | 'standardMotion',
  AntdTokenName
>;

/**
 * The one authored LENGTH the documented G1 resolution needs, and the only one in this module.
 *
 * Purpose
 * -------
 * G1 retires the fixed 24-by-80 character grid but keeps one thing that grid gave for free: the key
 * legend sat on row 24, at the bottom edge of the display, on every screen. Reproducing that
 * requires the frame to be at least as tall as the viewport, and no Ant Design token can express
 * it -- the scales cover colour, spacing, radius, typography, motion and breakpoints, and none of
 * them means "as tall as the window". Measured without it, the legend floated directly beneath a
 * short screen's content with the rest of the window blank.
 *
 * Refactoring Rationale: ⚠️ the length was previously written as a literal inside
 * `ui/src/layout/AppShell.tsx`, carrying a flag that named it as the one unresolved value in the
 * tree. A review named that a violation of the zero-hardcoded-values rule, and it was right for a
 * reason the flag itself missed: the rule's purpose is that every design value has ONE owner, and
 * a value that no token can express still has an owner -- this module, which is where every other
 * design decision is recorded with its evidence. Moving it here does not make it a token and does
 * not pretend to; it gives the one value that cannot be a token the same single home, the same
 * documented rationale and the same review surface as the ones that can.
 *
 * Assumptions: `dvh` and not `vh`. A mobile browser's dynamic toolbar makes `vh` overshoot the
 * visible area, which pushes the legend out of sight on exactly the narrow viewports the G1
 * responsive reflow exists to serve; `dvh` tracks the visible height as that toolbar moves.
 *
 * Assumptions: a MINIMUM and never a fixed height. A screen whose content exceeds the viewport --
 * the 128-field account-update form does at every width -- must grow and scroll; a fixed height
 * would clip it, which is the one failure mode the fixed grid had and G1 exists to remove.
 *
 * Trade-offs: a document that keeps the user agent's default `body { margin: 8px }` becomes 16px
 * taller than its window, so the page scrolls by that much. `ui/index.html` links no stylesheet by
 * a decision it documents, so no reset absorbs it. Subtracting a literal 16px here was rejected:
 * it would encode one document's margin into a value any document may consume, and it would be
 * wrong the moment a reset arrived. A `body` margin reset at whichever layer serves the document
 * removes it entirely.
 */
export const LAYOUT_METRICS = {
  /** Minimum height of the application frame: one viewport, so the key legend reaches the foot. */
  frameMinBlockSize: '100dvh',
} as const satisfies Record<'frameMinBlockSize', string>;

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
  medium: 'screenMD',
  large: 'screenLG',
} as const satisfies Record<'medium' | 'large', AntdTokenName>;

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
  /*
   * Refactoring Rationale: this was `colorError`, the mid-ramp error anchor, and it is now the
   * ramp's text-grade shade through {@link BMS_TEXT_COLOR_TOKENS}. The value this token paints is
   * always TEXT — the `'*'` marker and the refusal sentences beside a field — and the anchor
   * measures 3.27:1 against {@link TEXT_CONTRAST_SURFACE} where AA asks 4.5:1, while the
   * text-grade shade measures 4.62:1. The role is unchanged: red still means refused, and the hue
   * family survives, so nothing the `CSSETATY` contract asserts is given up.
   */
  errorColor: BMS_TEXT_COLOR_TOKENS.RED,
  blankMarker: '*',
} as const satisfies {
  readonly errorColor: AntdTokenName;
  readonly blankMarker: '*';
};

/**
 * Contrast ratio WCAG 2.1 asks of normal-sized body text against its background.
 *
 * Purpose: give the four contrast decisions below and the regression assertions in
 * `ui/src/theme/contrast.test.ts` one threshold to be measured against, rather
 * than repeating the number at each site that has to clear it.
 *
 * Assumptions: the normal-text figure is used and the 3:1 large-text allowance is
 * not, because no text this bridge colours qualifies. The allowance needs 24px, or
 * 18.66px at bold weight; the design system's base size is 14px and the largest
 * heading these screens paint is the `fontSizeHeading4` snap recorded in
 * {@link TYPOGRAPHY_TOKENS}, which is smaller than either figure at the pinned
 * version. Quoting 3:1 anywhere here would therefore be quoting an exemption the
 * text does not qualify for.
 */
export const WCAG_AA_NORMAL_TEXT_MINIMUM = 4.5;

/**
 * The three background colours the delivered screens actually paint text onto.
 *
 * Purpose: supply the measured pairs behind {@link ACCESSIBLE_TEXT_TOKENS} and
 * behind design gap G7, so a contrast claim in this tree can be recomputed rather
 * than trusted.
 *
 * Refactoring Rationale: this map carried two surfaces and a browser audit proved
 * that incomplete in a way that mattered. `documentBody` is `colorBgContainer`,
 * which is what the ONE screen outside the shell paints on - sign-on - while every
 * screen inside the shell paints on `colorBgLayout`, one step darker, because
 * `ui/src/layout/AppShell.tsx` mounts an antd `Layout` whose own fill that is.
 * Measuring a shell-mounted node against the container surface therefore overstated
 * every ratio slightly - the turquoise prompt substitute was recorded here at 7.01:1
 * and measures 6.759:1 where it actually paints - and understated nothing, so no
 * substitution chosen against it was wrong. The third entry closes the gap so a
 * future ratio is computed against the surface the node really has.
 *
 * Assumptions: these two hex values are AUDIT DATA and are never applied as a
 * style anywhere - nothing in `ui/src` reads them into a `background` or a
 * `color`. That is what keeps them consistent with the rule that every rendered
 * value resolves to a token: a measurement of what the design system produces is
 * not itself a design value, in the same way {@link BMS_SOURCE_HISTOGRAM} records
 * source operands it never renders.
 *
 * Assumptions: `darkChrome` is not this project's choice of colour. It is the
 * design system's own `Layout.headerBg` component-token default, read from the
 * installed package at `ui/node_modules/antd/lib/layout/style/index.js` where
 * `prepareComponentToken` returns `headerBg: '#001529'`. It is recorded here
 * rather than imported because `ui/eslint.config.js` bans every `antd/lib/*` deep
 * path - a component reached that way misses the CSS-variable theme - and a lint
 * ban is not worth breaking for one number.
 *
 * Trade-offs: a recorded measurement can go stale when the library moves, which a
 * literal in a screen would too, silently. That risk is closed mechanically rather
 * than by vigilance: {@link CONTRAST_MEASURED_AT_ANTD_VERSION} pins the version
 * these were taken at, and the contrast test asserts that pin still equals the
 * version `ui/package.json` declares, so an upgrade fails a test that names
 * re-measurement as the fix instead of quietly invalidating the ratios.
 */
export const CONTRAST_REFERENCE_SURFACES = {
  /** The design system's own dark chrome fill, behind `Layout.Header` content. */
  darkChrome: '#001529',
  /** The container surface, `colorBgContainer`: sign-on, cards, tables, buttons. */
  documentBody: '#ffffff',
  /** The shell's own `colorBgLayout` fill, behind every screen mounted in the frame. */
  shellSurface: '#f5f5f5',
} as const satisfies Record<'darkChrome' | 'documentBody' | 'shellSurface', string>;

/**
 * Design-system version {@link CONTRAST_REFERENCE_SURFACES} was measured at.
 *
 * Purpose: turn the staleness risk of a recorded measurement into a failing test.
 * `ui/src/theme/contrast.test.ts` compares this against the `antd` entry in
 * `ui/package.json`, so the surfaces cannot outlive the version they describe.
 */
export const CONTRAST_MEASURED_AT_ANTD_VERSION = '6.5.2';

/**
 * Token each measured BMS colour resolves to where it carries TEXT that would
 * otherwise fall below {@link WCAG_AA_NORMAL_TEXT_MINIMUM}.
 *
 * Purpose: keep the accessible substitution in this bridge, applied once for every
 * screen, instead of at the elements where the shortfall happens to have been
 * measured. {@link BMS_COLOR_TOKENS} stays the resolution of the source operand
 * itself and is unchanged; this map is consulted only by code that paints that
 * operand as body text.
 *
 * Refactoring Rationale: the first two entries replace an inline marker that recorded
 * a shortfall and declined to fix it - one in `ui/src/layout/AppShell.tsx` and one in
 * `ui/src/screens/refTypeList/index.tsx` - each stating that the remedy belonged in
 * this module and applied once for all screens. Both were right about where it
 * belonged, so the remedy is here, and both markers are gone rather than left
 * beside a value they no longer describe.
 *
 * Refactoring Rationale: the last two entries exist because fixing the title band on
 * ONE of its two surfaces left the other measurably worse than the shortfall that was
 * reported. `ui/src/layout/ScreenHeader.tsx` renders the same band either delegated
 * into the shell's dark chrome or in a screen's own body, and a browser audit of the
 * built bundle put the body mount at 3.76:1 for the blue slots and 1.74:1 for the two
 * title strings - the second failing even the 3:1 allowance that its 20px semibold
 * heading would qualify for. An accessibility audit of the same page named exactly
 * those ten nodes, and named none on the delegated mount. Both surfaces are therefore
 * resolved here, because a substitution that depends on where a shared component
 * happens to be mounted is a property of the bridge, not of the component.
 *
 * Measured with the design system's own `theme.getDesignToken` accessor under this
 * tree's token overrides, against {@link CONTRAST_REFERENCE_SURFACES}, and confirmed
 * against the rendered bundle by sampling painted pixels:
 *
 * | Role and surface                  | Operand token  | Ratio  | Substitute           | Ratio   |
 * | --------------------------------- | -------------- | ------ | -------------------- | ------- |
 * | `COLOR=BLUE` on dark chrome       | `colorPrimary` | 4.49:1 | `colorPrimaryHover`  | 6.17:1  |
 * | `COLOR=TURQUOISE` on the body     | `colorInfo`    | 2.21:1 | `colorTextLabel`     | 6.76:1  |
 * | `COLOR=BLUE` on the shell surface | `colorPrimary` | 3.76:1 | `colorPrimaryActive` | 5.65:1  |
 * | `COLOR=YELLOW` on the shell face  | `colorWarning` | 1.74:1 | `colorTextHeading`   | 15.97:1 |
 *
 * Assumptions: `COLOR=YELLOW` needs no substitute on the dark chrome, where the same
 * `colorWarning` operand measures 9.70:1, and `COLOR=BLUE` needs a DIFFERENT one on
 * each surface rather than one that serves both. The two directions are opposite: a
 * dark fill wants a lighter blue and a light fill wants a darker one, and no single
 * step of the ramp clears 4.5:1 on both - `colorPrimaryHover` measures 2.74:1 on the
 * shell surface and `colorPrimaryActive` measures 2.99:1 on the chrome. A single
 * "accessible blue" would therefore have had to fail somewhere, which is why the
 * surface is a parameter of the lookup and not a detail the caller may omit.
 *
 * Trade-offs: `colorPrimaryActive` is the substitute for blue on the light surface
 * even though this very entry rejects that token for TURQUOISE a few lines below. The
 * two cases are not the same case. There, the objection is that a distinct source
 * operand would land on the primary ramp and become indistinguishable from the 384
 * blue field definitions; here the source operand IS `COLOR=BLUE`, so the darkest
 * step of its own ramp keeps blue reading as blue and collapses nothing. It is also
 * the only token in the primary and link families that clears 4.5:1 on this surface
 * at all - every lighter step measures between 1.03:1 and 3.76:1.
 *
 * Assumptions: the blue substitute stays inside the primary ramp, four steps
 * lighter, so the operand's own hue family survives the correction - the band still
 * renders blue on navy, one shade brighter. Its token name carries an interaction
 * state it is not being used for, which is the cost of the design system exposing
 * ramp steps under interaction names; the alternative of a lighter blue literal has
 * no origin and would opt the band out of the theme.
 *
 * Trade-offs: the turquoise substitute does NOT stay in the cyan family, and that is
 * a measured impossibility rather than a preference. Every cyan-family token the
 * system derives was computed against the document surface and the best of them -
 * `colorInfoActive` and `colorInfoTextActive`, both `#08979c` - reaches 3.55:1,
 * still short of 4.5:1; the base `colorInfo` reaches 2.21:1 and the remaining seven
 * steps are lighter still. So the turquoise HUE cannot carry normal body text at AA
 * in this palette at all, and the choice is between an accessible colour and a
 * faithful one.
 *
 * Alternatives Considered: four, all rejected with the numbers that reject them.
 * Re-seeding the informational colour dark enough for text (a cyan-8 seed measures
 * 6.09:1) would move the whole informational ramp, including the message band's
 * informational variant, and would contradict the decision `ui/src/theme/antdTheme.ts`
 * records at its `token` block - where the 2.21:1 informational text figure is stated
 * and accepted for a role that is not text. `colorPrimaryActive` (`#0958d9`, 6.16:1)
 * keeps a cool accent but puts a distinct source operand onto the primary ramp, which
 * is exactly the collapse the `PINK` entry of {@link BMS_COLOR_TOKENS} rejects by
 * name. Bold weight plus the darkest cyan was rejected because 14px bold is not the
 * large text the 3:1 allowance requires. And a tinted background from the same ramp
 * makes it worse, not better: `#08979c` on `colorInfoBg` measures 3.39:1.
 *
 * Assumptions: `colorTextLabel` is the substitute because every turquoise TEXT node
 * in the delivery is a field prompt - two filter prompts on the reference-type
 * browse, the account search prompt on the authorization browse, the sign-on prompts
 * and the transaction-capture labels - and this is the one token whose declared role
 * IS prompt text while measuring 7.01:1. At the pinned version its value coincides
 * with the `NEUTRAL` snap, so on a screen carrying both operands the hue distinction
 * is surrendered and the distinction survives on the typographic axis instead: on
 * the reference-type browse the neutral nodes are a `Typography.Title` and the
 * turquoise nodes are normal-weight prompts, so size and weight still separate them,
 * exactly as design gap G3 separates `ATTRB=BRT` by weight rather than by colour.
 * Both originals are retained in {@link BMS_SOURCE_HISTOGRAM} either way.
 *
 * Trade-offs: the title substitute leaves the gold hue behind on the light surface,
 * and as with turquoise that is a measured impossibility rather than a preference.
 * Every token the warning family derives was computed against the shell surface and
 * the darkest of them - `colorWarningActive` and `colorWarningTextActive`, both
 * `#d48806` - reaches 2.63:1; the operand itself reaches 1.74:1 and the remaining
 * nine steps are lighter still. Reaching 4.5:1 on this surface requires the eighth
 * step of the gold ramp, `#874d00` at 6.23:1, which is a dark brown that no longer
 * reads as the mapsets' yellow and is a palette entry rather than a semantic token,
 * so adopting it would trade one kind of infidelity for another AND leave the theme
 * surface. The gold therefore survives where it is legible - the delegated mount, at
 * 9.70:1 - and yields to a text token where it is not.
 *
 * Assumptions: `colorTextHeading` is the substitute because both nodes it applies to
 * ARE headings in the delivered markup - `TITLE02` is the band's `Typography.Title`
 * and `TITLE01` is the attribution line rendered beside it - so this is the one token
 * whose declared role matches what the elements are, at 15.97:1. The BMS distinction
 * the substitution surrenders is recovered on the typographic axis exactly as design
 * gap G3 recovers `ATTRB=BRT`: on the light surface the title reads as the largest,
 * heaviest text in the band while the prompts stay normal-weight blue, so the centre
 * column is still the element that is not a prompt.
 *
 * Alternatives Considered: two structural fixes that would have removed the shortfall
 * without substituting anything, both rejected. Giving the band its own dark fill on
 * the light-surface mount would make one strip of eight screens look like chrome that
 * is not chrome, and would introduce a background decision where the design system
 * already has one. Delegating the band on all ten authored screens so that it only
 * ever paints on chrome is the more attractive of the two and is where this shell is
 * heading, but it would move the band out of seven screens' own render trees at a
 * point where those screens' tests assert it there, and the review that asked for the
 * delegation explicitly accepted a screen rendering this band directly as an equal
 * alternative. Correcting the colour is the change that fits inside what was asked.
 *
 * Assumptions: `ui/src/layout/MessageBand.tsx` deliberately does NOT consult this
 * map. Its informational variant paints the same token on the design system's own
 * informational alert background rather than on the document surface, and that pair
 * is measured, accepted and argued in `antdTheme.ts` on grounds that hold there and
 * not here: the band renders a per-severity icon and a per-severity ARIA role beside
 * the colour, so severity never rests on hue alone.
 */
export const ACCESSIBLE_TEXT_TOKENS = {
  /** `COLOR=BLUE` where it paints text on {@link CONTRAST_REFERENCE_SURFACES.darkChrome}. */
  BLUE_ON_DARK_CHROME: 'colorPrimaryHover',
  /** `COLOR=BLUE` where it paints text on {@link CONTRAST_REFERENCE_SURFACES.shellSurface}. */
  BLUE_ON_BODY: 'colorPrimaryActive',
  /** `COLOR=TURQUOISE` where it paints prompt text on either light surface. */
  TURQUOISE_ON_BODY: 'colorTextLabel',
  /** `COLOR=YELLOW` where it paints the two title strings on either light surface. */
  TITLE_ON_BODY: 'colorTextHeading',
} as const satisfies Record<
  'BLUE_ON_DARK_CHROME' | 'BLUE_ON_BODY' | 'TURQUOISE_ON_BODY' | 'TITLE_ON_BODY',
  AntdTokenName
>;

/**
 * Layout values the browser target needs and no Ant Design token can express.
 *
 * Purpose: hold the one non-token layout value the shell requires, so that the
 * design-system rule - every rendered value traces to this bridge - keeps holding
 * without the value being spelled inside a component.
 *
 * Refactoring Rationale: `viewportMinimumHeight` was a literal in
 * `ui/src/layout/AppShell.tsx` under an inline marker admitting it was one. The
 * marker was accurate and the placement was not: a value the token scales cannot
 * express is precisely what this module is for, and leaving it in the component made
 * the shell the second place a design value lived.
 *
 * Assumptions: the value is unresolvable rather than merely unmapped, and that is
 * why it is registered instead of snapped. The system's scales cover colour,
 * spacing, radius, typography, motion and breakpoints; none of them can express
 * "as tall as the window", and the breakpoint scale is the nearest miss - it says
 * where a layout should reflow, never how tall it should be. Recorded as design gap
 * G8.
 *
 * Assumptions: `dvh` and not `vh`. A mobile browser's collapsing toolbar makes `vh`
 * describe a viewport taller than the visible one, which would push the shell's
 * function-key legend below the fold on exactly the narrow viewports the responsive
 * reflow of design gap G1 exists to serve. `dvh` tracks the visible viewport, so the
 * legend stays at the bottom edge of the display the way row 24 always did.
 */
export const ADDITIVE_LAYOUT_VALUES = {
  /** Minimum height of the application frame, one visible viewport. */
  viewportMinimumHeight: '100dvh',
} as const satisfies Record<'viewportMinimumHeight', string>;

/**
 * Turns a measured span of source character cells into a proportional column width.
 *
 * Purpose: give the one class of rendered length that is neither a token nor expressible on a
 * token scale a single home - a table column whose width has to preserve the RELATIVE emphasis
 * the fixed character grid gave it. A caller supplies two MEASUREMENTS, the cells its column
 * occupies in the mapset and the cells the row spans, and receives the CSS value; no caller
 * writes a length.
 *
 * Refactoring Rationale: this exists because a screen held three percentage literals under a
 * local exemption. The reasoning at that screen was right about the values - they are counts of
 * `DFHMDF` character cells divided by the row they span, not colours or spacings anyone chose -
 * and wrong about where a rendered length may be spelled. Design gap G1 surrenders absolute
 * character POSITIONING and undertakes to preserve grouping and relative emphasis, so the
 * proportions are a G1 artefact and this module is where G1's resolutions live.
 *
 * Alternatives Considered: the design system's own 24-column grid, which is the obvious answer
 * and does not fit. `Row` and `Col` size a layout, not the columns of a `Table`, and the table
 * primitive takes a width per column; expressing 11, 8 and 50 cells as 4, 3 and 17 of 24 would
 * also round each proportion twice - once into a 24th and once back into a length - for no gain.
 * Letting the table size columns from their content was measured and rejected at the call site:
 * an unconstrained `Input` reports a full-width intrinsic size however few characters it accepts,
 * which gave a one-character column more width than a fifty-character one.
 *
 * Trade-offs: the returned value is a percentage string, so it is a CSS length that no token
 * produced. What is bought is that the length is DERIVED from a measurement a reader can check
 * against the mapset rather than asserted, and that the derivation is in one place for every
 * screen that needs it.
 * @param {number} cells - Character cells the column occupies in the source mapset.
 * @param {number} spanCells - Character cells the whole row spans in the source mapset.
 * @returns {string} The column's share of the row, as a CSS percentage rounded to two places.
 * @throws {RangeError} If either count is not a positive integer, or the column is wider than
 *   the row it sits in - each of which would mean the caller mis-read the mapset.
 */
export function characterCellWidthShare(cells: number, spanCells: number): string {
  if (!Number.isInteger(cells) || !Number.isInteger(spanCells) || cells < 1 || spanCells < 1) {
    throw new RangeError('A character-cell span must be a positive whole number of cells.');
  }
  if (cells > spanCells) {
    throw new RangeError('A column cannot occupy more character cells than the row it sits in.');
  }
  /*
   * Assumptions: two decimal places, because the three shares of the reference-type browse are
   * 11, 8 and 50 of 69 - none of which is exact in decimal - and two places keep their sum within
   * a hundredth of the row while staying readable in a rendered style attribute. Rounding to whole
   * percentages was the alternative and it loses a full cell of width on the widest column.
   */
  return `${((cells / spanCells) * 100).toFixed(2)}%`;
}

/**
 * Complete G1–G8 register for mismatches between BMS and Ant Design.
 *
 * Purpose: each gap retains the measured source, the population and unit of its
 * count, and the decided treatment so an absent token or component cannot be
 * mistaken for unfinished implementation.
 *
 * Assumptions: all eight gaps are resolved, none blocks the migration, none
 * requires a placeholder component, and none requires design-system-team
 * follow-up. G6 is recorded to close the evidence set, not because a missing
 * Figma artifact is a system defect.
 *
 * Refactoring Rationale: the register held six entries because six is what the
 * migration plan enumerates from the mapsets. The two added entries were measured
 * in a browser against delivered screens instead, which is why neither could have
 * been derived here: G7 is a contrast shortfall that only exists once a token is
 * painted onto a particular background, and G8 is a value the browser target needs
 * that the terminal had no equivalent of. Both had been recorded as inline markers
 * at the one element where each was observed, so the register said six and the
 * evidence set was eight.
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
    id: 'G1',
    description: 'No Ant Design equivalent of the fixed 24-by-80 grid.',
    sourceValue: 'SIZE=(24,80) with absolute POS=(row,column).',
    measuredCount: '17 of 17 base and 4 of 4 extension mapsets; 902 base and 1166 all-21 fields.',
    resolution:
      'Use responsive Layout with Descriptions for details and Table for lists, keyed to screenMD and screenLG; preserve grouping, reading order, and tab order rather than absolute character positions. One row position IS preserved, the row-24 key legend at the foot of the display, through the single authored length in LAYOUT_METRICS, because no token expresses viewport height.',
  },
  /*
   * Refactoring Rationale: the original shorthand cited the 6 protected
   * `(ASKIP,DRK,FSET)` carriers as password evidence. The measured operand
   * breakdown separates 10 protected fields from 4 unprotected password-style
   * fields, preventing protected carriers from becoming interactive controls.
   */
  {
    id: 'G2',
    description: 'DRK suppresses display, while a browser password control displays entry dots.',
    sourceValue: 'DRK across five ATTRB combinations, separated by PROT and UNPROT.',
    measuredCount:
      'Base17: 14 total, with 10 protected and 4 unprotected; all21: 18 total, with 14 protected and 4 unprotected.',
    resolution:
      'Apply Input.Password with visibilityToggle disabled only to the 4 unprotected fields; retain keystroke-registration feedback with no change to secret-value behaviour, and do not render protected carriers as password inputs.',
  },
  /*
   * Alternatives Considered: literal turquoise, neutral, green, or pink hues.
   * Literals would bypass the CSS-variable theme, while the selected semantic
   * tokens retain the measured roles and the histogram retains every original.
   */
  {
    id: 'G3',
    description:
      'No direct semantic tokens for turquoise, neutral, green-as-input, or pink identity emphasis.',
    sourceValue: 'COLOR=TURQUOISE, COLOR=NEUTRAL, COLOR=GREEN, and COLOR=PINK.',
    measuredCount: 'Base17/all21: TURQUOISE 127/157, NEUTRAL 60/90, GREEN 76/84, PINK 0/4.',
    resolution:
      'Snap to colorInfo, colorTextSecondary, colorSuccess, and colorTextHeading; retain every source value, count, role, and rejected alternative in BMS_SOURCE_HISTOGRAM. Those names govern fills, borders and icons; TEXT resolves through BMS_TEXT_COLOR_TOKENS instead, because five of the eight semantic ramps publish no shade reaching the 4.5:1 AA threshold for normal text -- the TURQUOISE mid-ramp anchor measured 2.21:1 as text -- and BMS_TEXT_CONTRAST_AUDIT records the measured ratio each role was snapped away from.',
  },
  /*
   * Alternatives Considered: a dedicated underline or no-highlight token. Both
   * were rejected because the component border already carries the editable
   * affordance and OFF explicitly requests the absence of extra highlighting.
   */
  {
    id: 'G4',
    description: 'HILIGHT operands have no theme-token equivalent.',
    sourceValue: 'HILIGHT=UNDERLINE and HILIGHT=OFF.',
    measuredCount: 'Base17/all21: UNDERLINE 158/175 and OFF 35/54.',
    resolution:
      'Carry the input affordance structurally with the Input border; add no token for UNDERLINE or the explicit OFF state.',
  },
  /*
   * Trade-offs: radius, elevation, and motion are additive because the source
   * has no corresponding vocabulary. Applying only system tokens records the
   * additions while keeping them controlled by the shared theme.
   */
  {
    id: 'G5',
    description: 'The 3270 vocabulary has no radius, elevation, or motion.',
    sourceValue: 'No BMS source analogue.',
    measuredCount: 'Not countable in either population.',
    resolution:
      'Use borderRadiusLG, boxShadowSecondary, motionDurationFast, and motionDurationMid as explicitly additive system tokens.',
  },
  /*
   * Assumptions: the exhaustive BMS attribute measurement is the available
   * design source. Inventing a Figma mapping would create evidence that was
   * never supplied and could not be verified.
   */
  {
    id: 'G6',
    description: 'No Figma design source or attachment exists.',
    sourceValue: 'No Figma file, frame, URL, or attachment was provided.',
    measuredCount: 'Not applicable to either BMS population.',
    resolution:
      'Not a system gap: the Figma-to-token mapping table is NOT APPLICABLE, and the measured BMS attributes are the authoritative design source.',
  },
  /*
   * Refactoring Rationale: two of the snapped colours fall below the WCAG AA
   * minimum for normal text on the surface they are actually painted on, and
   * neither shortfall is visible from the mapsets - it exists only once a token
   * meets a background. The cyan family cannot be corrected within its own hue at
   * all: the darkest step the system derives reaches 3.55:1. So the resolution
   * substitutes per surface in ACCESSIBLE_TEXT_TOKENS rather than re-deciding the
   * operand mapping in BMS_COLOR_TOKENS, which keeps the measured source
   * resolution intact and confines the correction to text.
   */
  {
    id: 'G7',
    description:
      'Three snapped colours measure below 4.5:1 as normal text on at least one surface they are painted on.',
    sourceValue:
      'COLOR=BLUE on both the design system dark chrome and the shell surface, COLOR=TURQUOISE on the light surfaces, and COLOR=YELLOW on the shell surface.',
    measuredCount:
      'Blue on chrome 4.49:1 across the skip link and both header rows, and 3.76:1 on the shell surface across 8 band slots; turquoise on body 2.21:1 across 6 prompt nodes on 4 screens, every cyan-family token measured, best 3.55:1; yellow on the shell surface 1.74:1 across both title strings, failing even the 3:1 large-text allowance the 20px semibold heading qualifies for, every warning-family token measured, best 2.63:1.',
    resolution:
      'Substitute per surface through ACCESSIBLE_TEXT_TOKENS: colorPrimaryHover for blue on chrome at 6.17:1, colorPrimaryActive for blue on the shell surface at 5.65:1, colorTextLabel for turquoise prompt text at 6.76:1, and colorTextHeading for the two title strings on the shell surface at 15.97:1. Yellow keeps its colorWarning operand on the dark chrome, where it measures 9.70:1. Retain every operand in BMS_SOURCE_HISTOGRAM, leave the message band informational variant alone because it pairs against its own alert background with an icon and an ARIA role, and assert every ratio - substitutes clearing the minimum and operands still falling below it - in ui/src/theme/contrast.test.ts.',
  },
  /*
   * Trade-offs: the shell needs a frame one viewport tall so the key legend sits at
   * the bottom edge of the display, as row 24 always did, and no token scale can
   * express a viewport-relative height. Registering the value here rather than
   * writing it in the shell keeps this module the single place a rendered value
   * comes from; the cost is one entry that is a value rather than a token name.
   */
  {
    id: 'G8',
    description: 'No token scale can express a viewport-relative height.',
    sourceValue: 'DFHMDI SIZE=(24,80) fills the display, with the key legend fixed on row 24.',
    measuredCount:
      'One value, consumed by one element: the application frame in ui/src/layout/AppShell.tsx.',
    resolution:
      'Register the value in ADDITIVE_LAYOUT_VALUES and consume it from there; use dvh rather than vh so a collapsing mobile toolbar cannot push the legend out of view.',
  },
] as const satisfies readonly DesignGap[];
