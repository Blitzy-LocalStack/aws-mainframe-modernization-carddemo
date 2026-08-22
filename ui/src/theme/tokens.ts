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
 * mid-ramp palette anchors intended for fills, borders and icons: measured against that surface,
 * five of the eight roles fail the AA threshold for normal text through their hue token - blue at
 * 4.104:1, turquoise 2.205:1, green 2.265:1, yellow 1.900:1 and red 3.268:1 - and only the three
 * whose hue token is ALREADY a neutral text token clear it.
 *
 * ⚠️ Refactoring Rationale: this paragraph said "only two of the eight" and the paragraph below said
 * two roles keep their hue family while six do not. Both counts were wrong in the same direction and
 * are corrected from the audit itself: three operands clear the threshold and five roles keep their
 * family. The numbers are asserted rather than restated - `ui/src/theme/contrast.test.ts` derives the
 * divergence role by role from these two maps, so a count in prose can no longer disagree with the
 * data beside it.
 *
 * Refactoring Rationale: screens used to paint text straight from {@link BMS_COLOR_TOKENS}, which
 * shipped measured accessibility failures — the informational role at 2.21:1 for every label and
 * prompt, and the primary role at 4.10:1 for every hint and value. Documenting those ratios was
 * the previous resolution and it is withdrawn: a recorded measurement does not make text readable.
 * The hue mapping in {@link BMS_COLOR_TOKENS} is unchanged and still governs fills, borders and
 * icons, so the AAP's measured BLUE-to-primary and TURQUOISE-to-informational bridge stands; this
 * map only decides which shade of it text is allowed to use.
 *
 * Assumptions: five roles keep their hue family and three do not, and which is which was measured
 * rather than chosen — see {@link BMS_TEXT_CONTRAST_AUDIT} for the per-role numbers. The primary
 * and error ramps each publish a text-grade shade that clears the threshold, and the neutral,
 * default and pink roles were already text tokens; the informational, success and warning ramps
 * publish nothing darker than 3.549:1, 3.463:1 and 2.867:1, so a role on one of those three ramps
 * cannot be text in its own hue at this version at all.
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

/*
 * WHY : ⚠️ Refactoring Rationale: TWO withdrawn duplicate declarations are recorded here, and this
 *       block documents no symbol of its own -- it is a plain comment rather than a JSDoc block
 *       because the JSDoc block that stood here documented nothing, having been left behind when the
 *       second declaration it belonged to was withdrawn. Its prose ended mid-sentence, at "The
 *       same", which is how a reader could tell it had been severed from whatever followed; the
 *       measurement it was reaching for is the 4.49:1 dark-chrome pairing, and that measurement is
 *       recorded once, where it is still consumed, at {@link CONTRAST_REFERENCE_SURFACES}.
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

/*
 * WHY : ⚠️ Refactoring Rationale: a LAYOUT_METRICS map stood here holding `frameMinBlockSize:
 *       '100dvh'`, and it is withdrawn because it was the SECOND registry for one value --
 *       {@link ADDITIVE_LAYOUT_VALUES} below holds the same `100dvh` under
 *       `viewportMinimumHeight`. Neither had a consumer anywhere in `ui/src`, so the duplication
 *       was invisible to the compiler and to every test: two records of one decision, each
 *       claiming a component read it, and no component did.
 * WHY : Assumptions: the surviving record is the one below, and the choice between the two names
 *       is not arbitrary. `frameMinBlockSize` describes the element it was expected to size, and
 *       that element turned out to be the wrong one -- the value sizes the DOCUMENT's mount point
 *       in `ui/index.html`, not the frame this module's consumers render.
 *       `viewportMinimumHeight` describes the VALUE, which stays true whichever layer declares
 *       it. The reasoning both blocks carried is preserved there in one place: why `dvh` rather
 *       than `vh`, why a minimum rather than a fixed height, and why the user agent's default
 *       `body` margin had to be reset rather than subtracted.
 */

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
 * Purpose: give the regression assertions in `ui/src/theme/contrast.test.ts` one
 * threshold to be measured against, rather than repeating the number at each site
 * that has to clear it.
 *
 * Assumptions: this is the same number as {@link TEXT_CONTRAST_THRESHOLD} and the two
 * are deliberately not merged. That one is the threshold every entry of
 * {@link BMS_TEXT_COLOR_TOKENS} is REQUIRED to clear, asserted per role in
 * `ui/src/theme/textContrast.test.ts`; this one is the threshold the G7 evidence set is
 * measured against, including the pairings that FAIL it and are recorded as the reason a
 * role snapped. Collapsing them would tie a requirement and a historical measurement to
 * one symbol, so a change to either would silently restate the other.
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
 * The one background colour this tree measures text against that no token in it names.
 *
 * Purpose: supply the surface behind the measurement that RETIRED the design system's
 * default header fill, so design gap G7's central claim - that the frame had to paint
 * its own surface rather than resolve text per surface - is recomputed on every run
 * rather than trusted. `ui/src/theme/contrast.test.ts` reads it.
 *
 * ⚠️ Refactoring Rationale: this map held THREE entries and holds one, because the other
 * two were hex duplicates of tokens the tests can resolve live. `documentBody`
 * `#ffffff` is what `colorBgContainer` resolves to and `shellSurface` `#f5f5f5` is what
 * `colorBgLayout` resolves to, so a ratio measured against either literal was a ratio
 * measured against a copy of a token value - which is the drift this module exists to
 * prevent, not an instance of preventing it. Both are gone and the cases that used them
 * now resolve {@link TEXT_CONTRAST_SURFACE} from the theme, which is also the surface
 * `ui/src/layout/AppShell.tsx` actually paints all three of its zones with.
 *
 * Assumptions: `colorBgLayout` needed no replacement entry of its own, because the frame
 * no longer paints it anywhere. The shell states its own fill on the outer `Layout`, on
 * `Layout.Header` and on `Layout.Footer`, and leaves `Layout.Content` transparent over
 * that same fill - see {@link SURFACE_TOKENS} - so the layout grey the design system
 * would otherwise show is behind no text on any screen.
 *
 * Assumptions: this hex is AUDIT DATA and is never applied as a style - nothing in
 * `ui/src` reads it into a `background` or a `color`. That is what keeps it consistent
 * with the rule that every rendered value resolves to a token: a measurement of what the
 * design system produces is not itself a design value, in the same way
 * {@link BMS_SOURCE_HISTOGRAM} records source operands it never renders.
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
 * this was taken at, and the contrast test asserts that pin still equals the
 * version `ui/package.json` declares, so an upgrade fails a test that names
 * re-measurement as the fix instead of quietly invalidating the ratios.
 */
export const CONTRAST_REFERENCE_SURFACES = {
  /** The design system's own dark chrome fill, the default `Layout.Header` fill the shell replaces. */
  darkChrome: '#001529',
} as const satisfies Record<'darkChrome', string>;

/**
 * Design-system version {@link CONTRAST_REFERENCE_SURFACES} was measured at.
 *
 * Purpose: turn the staleness risk of a recorded measurement into a failing test.
 * `ui/src/theme/contrast.test.ts` compares this against the `antd` entry in
 * `ui/package.json`, so the recorded fill cannot outlive the version it describes.
 */
export const CONTRAST_MEASURED_AT_ANTD_VERSION = '6.5.2';

/*
 * WHY : ⚠️ Refactoring Rationale: an ACCESSIBLE_TEXT_TOKENS map stood here resolving COLOR=BLUE,
 *       COLOR=TURQUOISE and COLOR=YELLOW to a different shade PER SURFACE -- BLUE_ON_DARK_CHROME,
 *       BLUE_ON_BODY, TURQUOISE_ON_BODY and TITLE_ON_BODY -- and it is withdrawn as SUPERSEDED
 *       rather than wired into a consumer. It answered a question the rendered application no longer
 *       asks. It existed because one text token measured a different ratio in each of three zones:
 *       the design system's dark `Layout.Header` fill, the layout grey behind a screen body, and the
 *       container surface. `ui/src/layout/AppShell.tsx` now paints ALL THREE of its zones with
 *       {@link SURFACE_TOKENS}.screen, so there is one surface behind screen text and one
 *       measurement is the whole answer -- which is what {@link BMS_TEXT_COLOR_TOKENS} and
 *       {@link BMS_TEXT_CONTRAST_AUDIT} record, per role, against {@link TEXT_CONTRAST_SURFACE}.
 *       That map is imported by the shell, the message band, the title band and every screen that
 *       paints prose; the withdrawn one was imported by one test and by no component at all, so it
 *       was a documented resolution nothing resolved.
 * WHY : Assumptions: withdrawing beats wiring because two of the four entries named a surface nothing
 *       paints. BLUE_ON_DARK_CHROME (colorPrimaryHover) corrected blue on `headerBg` #001529, the
 *       fill the shell replaced; TITLE_ON_BODY (colorTextHeading) corrected the two title strings on
 *       colorBgLayout, which the frame no longer shows anywhere. Wiring them into styles would have
 *       reinstated the per-surface distinction the one-surface decision removed, and each consumer
 *       would then be resolving its colour against a background it is not painted on -- worse than an
 *       unconsumed map, because it would read as coverage.
 * WHY : Assumptions: the two entries that were right survive by VALUE under the live map, so no
 *       measurement is given up in the exchange. TURQUOISE_ON_BODY named colorTextLabel and the
 *       audit's TURQUOISE row names colorTextLabel; BLUE_ON_BODY named colorPrimaryActive and the
 *       audit's BLUE row names colorPrimaryTextActive, which the pinned version resolves to the same
 *       #0958d9. The live names are the ones kept because they are text-role names rather than
 *       interaction-state names, which is what the elements reading them actually paint.
 * WHY : Assumptions: the measured IMPOSSIBILITIES that forced two roles out of their own hue families
 *       are retained rather than discarded with the map, because they are what makes those snaps
 *       auditable instead of arbitrary. No cyan-family token the system derives reaches the threshold
 *       as text -- the darkest, colorInfoTextActive, measures 3.549:1 -- and no warning-family token
 *       does either, the darkest being colorWarningTextActive at 2.867:1. Both censuses are
 *       exhaustive rather than sampled, because the claim is that NONE of them clears the bar, and
 *       both are asserted in `ui/src/theme/contrast.test.ts` against the surface the frame paints.
 *       The per-role shortfall figures the browser audit of the built bundle produced are recorded in
 *       the G7 entry of {@link DESIGN_GAPS}.
 * WHY : Alternatives Considered: four ways of keeping the turquoise hue for prompt text, all rejected
 *       with the numbers that reject them. Re-seeding the informational colour dark enough for text
 *       (a cyan-8 seed measures 6.09:1) moves every fill, border and alert tint derived from that
 *       seed to defend a text case, and contradicts the decision `ui/src/theme/antdTheme.ts` records
 *       at its own `token` block. Putting a distinct source operand onto the primary ramp collapses
 *       the measured TURQUOISE-versus-BLUE distinction, which {@link BMS_COLOR_TOKENS} rejects by
 *       name at its PINK entry. Bold weight plus the darkest cyan does not reach the size the 3:1
 *       large-text allowance requires at a 14px base. And a tinted background from the same ramp
 *       measures worse rather than better: #08979c on colorInfoBg is 3.39:1.
 * WHY : Assumptions: `ui/src/layout/MessageBand.tsx` sits outside this reasoning and always did. Its
 *       severities paint on the design system's own alert tints rather than on the screen surface, so
 *       the pairing is measured separately -- `ui/src/theme/textContrast.test.ts` asserts the band's
 *       sentence against colorErrorBg, colorSuccessBg and colorInfoBg -- and the band renders a
 *       per-severity icon and ARIA role beside the colour, so severity never rests on hue alone.
 */

/**
 * The one layout value the browser target needs that no Ant Design token can express.
 *
 * Purpose: give that value a single recorded home with its evidence, so the
 * design-system rule - every rendered value traces to this bridge - keeps holding for
 * the one value that cannot be a token. This is a RECORD and not a source of style:
 * the declaration itself is in `ui/index.html`, for the reason below.
 *
 * ⚠️ Refactoring Rationale: no component consumes this, and that is the accurate
 * position rather than a gap. The value began as a literal `minHeight: '100dvh'` inside
 * `ui/src/layout/AppShell.tsx`; it was registered here so the component would stop
 * spelling a design value; and it then stopped being a component's value at all. A
 * browser measurement showed the frame sized to exactly one viewport inside a document
 * keeping the user agent's default `body { margin: 8px }` scrolled permanently by 16px,
 * so BOTH halves - the margin reset and the viewport sizing - moved to the layer that
 * owns them. `ui/index.html` sets `body { margin: 0 }` and `#root { min-height: 100dvh }`,
 * and the design system's own `.ant-layout { flex: auto }` then stretches the frame to
 * fill that mount point. The fidelity property the declaration existed for is preserved
 * by that arrangement: the row-24 key legend still sits at the bottom edge of the
 * display.
 *
 * Assumptions: the DOCUMENT is the right owner, and this is not a matter of taste. The
 * value sizes the mount point React renders into, which is outside every component's
 * tree - no component may reach `#root`, and a component that sized itself to the
 * viewport would be making a claim about a document it does not own, wrong for any
 * document that mounts it inside other content. Two layers cannot both declare it
 * without one of them being redundant, so the component declares nothing.
 *
 * Assumptions: this record is held to that declaration mechanically rather than by
 * review. `ui/src/layout/appShell.test.tsx` reads `ui/index.html` and asserts the
 * document declares exactly this value on the mount point, that it still resets the
 * body margin - the other half of the measured 16px defect - and that the shell itself
 * declares no block size of its own. A register that agreed with nothing would be the
 * same defect in a new place.
 *
 * Assumptions: the value is unresolvable rather than merely unmapped, which is why it is
 * registered instead of snapped. The system's scales cover colour, spacing, radius,
 * typography, motion and breakpoints; none of them can express "as tall as the window",
 * and the breakpoint scale is the nearest miss - it says where a layout should reflow,
 * never how tall it should be. Recorded as design gap G8 in {@link DESIGN_GAPS}.
 *
 * Assumptions: `dvh` and not `vh`. A mobile browser's collapsing toolbar makes `vh`
 * describe a viewport taller than the visible one, which would push the shell's
 * function-key legend below the fold on exactly the narrow viewports the responsive
 * reflow of design gap G1 exists to serve. `dvh` tracks the visible viewport, so the
 * legend stays at the bottom edge of the display the way row 24 always did.
 *
 * Assumptions: a MINIMUM and never a fixed height. A screen whose content exceeds the
 * viewport - the 128-field account-update form does at every width - must grow and
 * scroll; a fixed height would clip it, which is the one failure mode the fixed
 * character grid had and design gap G1 exists to remove.
 */
export const ADDITIVE_LAYOUT_VALUES = {
  /** Minimum height of the document's mount point, one visible viewport, as `ui/index.html` declares it. */
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
      'Use responsive Layout with Descriptions for details and Table for lists, keyed to screenMD and screenLG; preserve grouping, reading order, and tab order rather than absolute character positions. One row position IS preserved, the row-24 key legend at the foot of the display: ui/index.html sizes the mount point to one viewport with the value G8 registers in ADDITIVE_LAYOUT_VALUES, and the design system stretches the frame to fill it, because no token expresses viewport height.',
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
      'Snap to colorInfo, colorTextSecondary, colorSuccess, and colorTextHeading; retain every source value, count, role, and rejected alternative in BMS_SOURCE_HISTOGRAM. Those names govern fills, borders and icons; TEXT resolves through BMS_TEXT_COLOR_TOKENS instead, because five of the eight roles fail the 4.5:1 AA threshold for normal text through their hue anchor -- the TURQUOISE anchor measures 2.205:1 as text -- and three of the semantic ramps those anchors sit on publish no shade that reaches it at all: informational best 3.549:1, success best 3.463:1 and warning best 2.867:1, against primary 6.159:1 and error 4.618:1 which do. BMS_TEXT_CONTRAST_AUDIT records the measured ratio each role was snapped away from.',
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
   * Refactoring Rationale: several of the hue-mapped colours fall below the WCAG AA
   * minimum for normal text, and no shortfall is visible from the mapsets - each
   * exists only once a token meets a background. THREE of them cannot be corrected
   * inside their own hue at all, and each family was measured member by member: the
   * darkest cyan step the system derives reaches 3.549:1, the darkest green step
   * 3.463:1 and the darkest gold step 2.867:1. The resolution therefore selects a
   * TEXT-GRADE shade per role in BMS_TEXT_COLOR_TOKENS rather than re-deciding the
   * operand mapping in BMS_COLOR_TOKENS, which keeps the measured source resolution
   * intact for fills, borders and icons and confines the correction to text.
   *
   * ⚠️ Refactoring Rationale: this entry described a PER-SURFACE substitution and no
   * longer does, because the second surface was removed rather than resolved against.
   * The shell painted its header on the design system's dark chrome and its body over
   * the layout grey, so one text token measured three ratios; ui/src/layout/AppShell.tsx
   * now paints all three zones with SURFACE_TOKENS.screen, which is what lets one
   * measurement per role be the whole answer. The per-surface map is withdrawn as
   * superseded and the reasoning is recorded where it stood, above ADDITIVE_LAYOUT_VALUES.
   */
  {
    id: 'G7',
    description:
      'Five of the eight hue-mapped colours measure below 4.5:1 as normal text on the surface the frame paints, and the design system default header fill fails for the dominant blue as well.',
    sourceValue:
      'COLOR=BLUE, COLOR=TURQUOISE, COLOR=GREEN, COLOR=YELLOW and COLOR=RED painted as prose text, plus COLOR=BLUE on the design system Layout.Header default fill.',
    measuredCount:
      'On the painted surface colorBgContainer: blue operand 4.104:1, turquoise 2.205:1, green 2.265:1, yellow 1.900:1 and red 3.268:1, every one below 4.5:1, while neutral 6.978:1, default 16.558:1 and pink 16.558:1 clear it; three whole families measured member by member, cyan best 3.549:1, success best 3.463:1 and warning best 2.867:1, against primary best 6.159:1 and error best 4.618:1 which do clear it. On the abandoned dark chrome #001529: blue operand 4.491:1 across the skip link and both header rows, against a 4.5:1 requirement, while the yellow operand reached 9.701:1 there and the text-grade blue reaches only 2.993:1.',
    resolution:
      'Resolve text through BMS_TEXT_COLOR_TOKENS against the one surface SURFACE_TOKENS.screen paints, with the per-role ratio and the in-family shade it beat recorded in BMS_TEXT_CONTRAST_AUDIT: blue keeps its hue at colorPrimaryTextActive 6.159:1 and red at colorErrorTextActive 4.618:1, turquoise snaps to colorTextLabel 6.978:1, and green and yellow snap to colorText 16.558:1. Retain every operand in BMS_SOURCE_HISTOGRAM, leave the message band alone because each severity pairs against its own alert tint with an icon and an ARIA role, and assert the ratios in ui/src/theme/textContrast.test.ts per role plus the three exhaustive family censuses and the retired dark-chrome pairing in ui/src/theme/contrast.test.ts.',
  },
  /*
   * Trade-offs: the display has to be one viewport tall so the key legend sits at its
   * bottom edge, as row 24 always did, and no token scale can express a
   * viewport-relative height. The cost is one register entry that is a value rather
   * than a token name.
   *
   * ⚠️ Refactoring Rationale: the element that carries the value is the DOCUMENT's
   * mount point and not the frame, which is the correction this entry records. A frame
   * sized to exactly one viewport inside a document keeping the user agent's default
   * body margin scrolled permanently by 16px, and no component can reset a margin it
   * does not own - so the sizing and the reset moved together to ui/index.html, and the
   * component declares no height at all. Compensating inside the component was rejected
   * outright: subtracting one document's margin from a component's height encodes that
   * document into every other document that mounts the component.
   */
  {
    id: 'G8',
    description: 'No token scale can express a viewport-relative height.',
    sourceValue: 'DFHMDI SIZE=(24,80) fills the display, with the key legend fixed on row 24.',
    measuredCount:
      'One value, declared once, at the document layer: ui/index.html sizes #root with it. No component in ui/src consumes it, because no component owns the mount point.',
    resolution:
      'Declare it in ui/index.html on the mount point, beside the body margin reset the same measurement required, and let the design system stretch the frame to fill that height; register the value once in ADDITIVE_LAYOUT_VALUES with its evidence, and hold the register to the document in ui/src/layout/appShell.test.tsx. Use dvh rather than vh so a collapsing mobile toolbar cannot push the legend out of view.',
  },
] as const satisfies readonly DesignGap[];
