/**
 * @file The application's single Ant Design theme object.
 *
 * Purpose
 * -------
 * `ui/src/theme/tokens.ts` decides which named design-system token carries each
 * measured BMS design value, and records the evidence and the rejected
 * alternative behind every one of those decisions. This module is the other half
 * of that bridge: it fixes the theme-level settings that govern how the design
 * system derives rendered values from those names, and it is consumed by exactly
 * one caller.
 *
 * Upstream and downstream
 * -----------------------
 * - Upstream: `ui/src/theme/tokens.ts`, the token bridge and its audit record,
 *   and `docs/architecture/design-token-reference.md`, the normative record of
 *   the `antd@6.5.2` seed, map and alias token surface, read from the published
 *   package's own declaration files rather than from documentation prose.
 * - Downstream: `ui/src/App.tsx`, and nothing else. That module mounts the only
 *   `ConfigProvider` in the application and hands it the object exported here.
 *
 * Why this module writes down almost no design value
 * --------------------------------------------------
 * `tokens.ts` deliberately records token *names* and never colour, spacing or
 * typography *values*, so for all but one decision there is no authored value
 * here to bind. The design-token reference states the consequence of filling that
 * gap in anyway: a theme entry that merely restates a value the algorithm would
 * have produced pins that one value while everything computed alongside it keeps
 * moving. The per-component block below is therefore present and empty, and the
 * value block holds exactly two entries — the pair that separates the
 * informational colour role from the primary one, which is the single design
 * decision a token name cannot carry, because the library ships both roles with
 * the same seed value. Each block states its reasoning at the point of use, so
 * neither the emptiness nor the two exceptions can be read as accidental.
 *
 * Design decisions
 * ----------------
 * Refactoring Rationale: the theme is a separate module from the component that
 * mounts it, rather than an object literal inline in that component. Under
 * CSS-variable theming a value written into a component does not merely duplicate
 * a token, it silently opts that component out of the theme, so a later change to
 * the token leaves that one component behind and nothing fails. Keeping the
 * object here, in a module that contains no markup, means a design value cannot
 * be added next to the markup that consumes it without changing file. That
 * boundary is what makes "one place decides what the application looks like"
 * checkable rather than aspirational.
 *
 * Assumptions: `ThemeConfig` is an external contract belonging to one exactly
 * pinned major version. A property renamed or relocated by a future major would
 * not fail this module loudly, because an unrecognised property on a theme object
 * is simply never read, so the breakage would be silent and found by eye. That is
 * why `ui/package.json` pins the dependency exactly rather than by range, and why
 * every property set below names the version whose documented behaviour it
 * relies on.
 *
 * Assumptions: no React 19 compatibility shim is imported, because version 6 of
 * the design system removed the one version 5 required. The published package
 * declares React and React DOM as peer dependencies at 18 or later and depends on
 * neither directly, which the pinned React 19 runtime satisfies as published.
 * Reinstating that shim would add a package whose only job is to reconcile two
 * versions of the same ecosystem, and it is named here so that its absence reads
 * as a decision rather than as an oversight.
 */

import { theme } from 'antd';
import type { ThemeConfig } from 'antd';

import { BMS_SEED_PALETTE_ANCHORS } from './tokens';

/**
 * The theme handed to the application's only `ConfigProvider`.
 *
 * It governs five things: the algorithm that derives the map and alias token
 * layers from the seed layer, the CSS-variable scope those layers are emitted
 * into, the class-name shape of the emitted style, whether style is generated at
 * run time at all, and the one pair of seed values that separates the
 * informational colour role from the primary one. It governs no other individual
 * token value, for the reason recorded in the module header above.
 *
 * Trade-offs: this is a documented object literal rather than a factory function.
 * A factory was considered and rejected because nothing here is parameterised.
 * Every setting is a single fixed decision about the design system, taken once for
 * the whole application, so a factory would add a call site and a signature to
 * document without adding a choice that any caller is entitled to make
 * differently.
 */
/**
 * The two seed entries this application sets, named so the derived palette can be read from them.
 *
 * Assumptions: these are lifted out of the theme literal rather than written inline because
 * {@link SOLID_SURFACE_TOKENS} below has to resolve the palette these seeds produce, and resolving
 * it from a second copy of the same two values would let the copies drift. The reasoning for each
 * entry is recorded at the `token` member that consumes this constant.
 */
const SEED_OVERRIDES = {
  colorInfo: theme.defaultSeed[BMS_SEED_PALETTE_ANCHORS.TURQUOISE],
  colorLink: theme.defaultSeed[BMS_SEED_PALETTE_ANCHORS.BLUE],
} as const;

/**
 * The primary ramp this application's seeds actually produce, resolved once.
 *
 * Assumptions: the palette is read from the design system's own accessor rather than written as
 * colour literals, so the three shades below stay whatever the pinned version derives from the
 * measured blue anchor. Reading it here is not circular: none of the three members consumed from it
 * is itself derived from a component override.
 *
 * Assumptions: this is the WHOLE resolved token set and not only the blue ramp, and the name
 * records its first use rather than its extent. Constants below read non-ramp members from it — the
 * base text value, the preset red steps, the focus line width, the small control height — for the
 * same reason the ramp is read here: an override has to be a VALUE, the module's rule is that no
 * value is authored, and resolving one from the library's own accessor is how both hold at once.
 */
const PRIMARY_RAMP = theme.getDesignToken({ token: SEED_OVERRIDES });

/**
 * Flattens a translucent fill token onto an opaque surface token, yielding the same colour solid.
 *
 * ⚠️ Purpose: a translucent background on a STICKY cell lets whatever that cell overlays show through
 * it, and a browser sweep measured the consequence on the transaction browse. At a 375-pixel viewport
 * the grid's track overflows, so the pinned money column legitimately sits over the unpinned date
 * column by about 127 pixels; at rest the pinned cell is opaque and hides it, but on the row under the
 * pointer the row-hover background took over and, being six-percent black rather than a solid, the date
 * printed straight through the amount. The money column read as an unreadable pile of glyphs -- the one
 * value an operator opens that screen for. Scrolling the date out from under the pin cleaned the amount
 * and moved the same artefact onto the leading pinned cell, which is what identifies the alpha rather
 * than the pin as the cause.
 *
 * ⚠️ Assumptions: this is the step the design system itself takes and it names the result accordingly.
 * `antd/lib/input/../table/style/index.js` L197-L199 derives `colorFillSecondarySolid`,
 * `colorFillContentSolid` and `colorFillAlterSolid` as `FastColor(fill).onBackground(colorBgContainer)`,
 * and its own default `rowHoverBg` is `colorFillAlterSolid` -- a SOLID. So the defect was introduced by
 * overriding a deliberately-solid default with a translucent fill, and the remedy is to keep the fill
 * that was chosen and take its solid form, exactly as the library would have.
 *
 * ⚠️ Assumptions: the arithmetic is performed here rather than by importing `@ant-design/fast-color`,
 * which computes it inside the library. That package appears in the lock file only as a transitive of
 * the design system and is declared by nothing in this tree, so importing it would put a build on a
 * dependency no manifest states.
 *
 * Alternatives Considered: (1) writing the resulting colour as a literal. Rejected because this module's
 * standing rule is that no colour value is authored here -- every one is resolved from the library's own
 * accessor -- and a literal would silently stop tracking the fill it was derived from. (2) Forcing an
 * opaque background onto `.ant-table-cell-fix-start` and `-fix-end` in the hover state with a stylesheet
 * rule. Rejected because the tree carries no stylesheet at all, the theme being the single place design
 * values are stated, and because it would treat one symptom of the alpha rather than the alpha.
 * (3) Reverting to the library's own `colorFillAlter` default. Rejected because the block that chose the
 * six-percent fill records the measurement behind it: two-percent black composites to a 1.045:1
 * difference from the surface, which is present in the computed style and absent to the eye, and the
 * list screens are where an operator picks a record.
 *
 * Trade-offs: the flatten handles the two spellings the design system's own accessor produces for these
 * members -- `rgba(r,g,b,a)` for a fill and `#rrggbb` for a surface -- and throws on anything else
 * rather than guessing. A silently mishandled spelling would put a wrong colour behind every hovered
 * row, which is far harder to trace than a message naming the value it could not read.
 * @param {string} fill - A translucent fill token, in the `rgba(r,g,b,a)` spelling.
 * @param {string} surface - The opaque surface it sits on, in the `#rrggbb` spelling.
 * @returns {string} The composite as an opaque `#rrggbb` colour.
 * @throws {RangeError} When either argument is not in the spelling this function reads.
 */
function onOpaqueSurface(fill: string, surface: string): string {
  const fillParts = /^rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)$/u.exec(fill);
  const surfaceParts = /^#([\da-f]{2})([\da-f]{2})([\da-f]{2})$/iu.exec(surface);

  if (fillParts === null || surfaceParts === null) {
    throw new RangeError(
      `A row-hover surface must flatten an rgba() fill onto a #rrggbb surface, got ${fill} on ${surface}.`,
    );
  }

  const alpha = Number(fillParts[4]);
  const channels: string[] = [];

  for (const index of [1, 2, 3]) {
    const over = Number(fillParts[index]);
    const under = Number.parseInt(surfaceParts[index] ?? '', 16);
    const composite = Math.round(over * alpha + under * (1 - alpha));

    channels.push(composite.toString(16).padStart(2, '0'));
  }

  return `#${channels.join('')}`;
}

/**
 * Ramp shades a solid control may use behind white label text, darkest state last.
 *
 * Purpose: the design system paints a solid primary control in the ramp's own anchor with the
 * light-solid text token on top, and that pairing measures 4.10:1 against white where WCAG AA asks
 * 4.5:1 for normal text — its hover shade measures 2.99:1, worse still. These three shades measure
 * 6.16:1, 8.97:1 and 12.08:1, so every state of a solid control clears the threshold.
 *
 * Assumptions: the states get progressively DARKER, reversing the design system's own direction of
 * travel, which lightens a primary control on hover. Lightening is precisely what breaks the
 * pairing, so the direction is inverted rather than the amount reduced; darkening on interaction is
 * also the convention the rest of the ramp's text shades already follow.
 */
const SOLID_SURFACE_TOKENS = {
  /** 6.16:1 against white — the ramp's text-grade shade, the same one this tree paints as text. */
  rest: PRIMARY_RAMP.blue7,
  /** 8.97:1 against white. */
  hover: PRIMARY_RAMP.blue8,
  /** 12.08:1 against white. */
  active: PRIMARY_RAMP.blue9,
} as const;

/**
 * Shades a hyperlink takes in its three states, darkest state last.
 *
 * Purpose: the design system's link alias measures 4.104:1 against the surface the shell paints,
 * where WCAG AA asks 4.5:1 for normal text, and its hover shade measures 2.250:1 — so the state a
 * pointer reaches is the worst one, and moving through it makes a link progressively less readable.
 * These three measure 6.159:1, 8.974:1 and 12.076:1. There is exactly one consumer that needed
 * this: `ui/src/layout/AppShell.tsx` renders the sign-off control as the design system's link button
 * with no colour of its own, so it took the alias verbatim. The skip link beside it already resolved
 * its colour through the text-grade map and was never affected, which is why the two controls in one
 * row measured differently.
 *
 * Alternatives Considered: setting only the link SEED and letting the library derive the other two,
 * which is the mechanism the informational separation uses one block above and would be the
 * consistent choice. It is rejected on measurement: computed from the package's own
 * `theme.getDesignToken` accessor at the pinned version, seeding the link colour with the ramp's
 * step 7 derives a hover of `#579df2` — LIGHTER than the resting shade, so the failing direction of
 * travel survives the fix and the hover state lands near 2.6:1. The derivation lightens on hover by
 * design, and that is precisely the property being corrected, so all three states are named.
 *
 * Trade-offs: the design system's own direction of travel is inverted here exactly as it is for the
 * solid control above, and for the same reason. What is given up is the library's convention that
 * interaction lightens; what is kept is that every state of a link is readable, and that the shades
 * are all members of the one measured blue ramp the mapsets asked for.
 */
const LINK_TEXT_TOKENS = {
  /** 6.16:1 against white — the same shade the text-grade map paints a blue field in. */
  rest: PRIMARY_RAMP.blue7,
  /** 8.97:1 against white. */
  hover: PRIMARY_RAMP.blue8,
  /** 12.08:1 against white. */
  active: PRIMARY_RAMP.blue9,
} as const;

/**
 * Shades a destructive control takes in its three states, darkest state last.
 *
 * Purpose: the design system paints its dangerous variant in the error ramp's own anchor, which
 * measures 3.268:1 against the surface the shell paints, and LIGHTENS it on hover to 2.562:1. Both
 * are below AA for normal text, and the direction means the two destructive controls in this
 * application — the user delete and the reference-type delete — become harder to read at the moment
 * the pointer is on them. These three measure 5.571:1, 7.748:1 and 10.718:1, so a destructive
 * control is readable at rest and DARKENS through hover to active.
 *
 * Assumptions: the same three shades also fix the solid destructive variant, where the label is
 * white on the shade rather than the shade on white. The design system's anchor gives white-on-red
 * at 3.268:1 there too, because the pairing is symmetric against a mid-luminance fill, so raising
 * the shade raises both readings at once and no separate override is needed for the solid form.
 *
 * Alternatives Considered: the error ramp's own `…TextActive` alias, which is the shade
 * `ui/src/theme/tokens.ts` reached for first when the same problem appeared in text. Rejected
 * because it publishes one shade and this needs three that get monotonically darker, and the alias
 * has no hover or active companion — the ramp's hover and active aliases are the LIGHTER ones this
 * override exists to replace. The preset red palette publishes the three consecutive steps the
 * correction needs from one family.
 */
const DESTRUCTIVE_SURFACE_TOKENS = {
  /** 5.57:1 against white — the same shade the text-grade map paints a red field in. */
  rest: PRIMARY_RAMP.red7,
  /** 7.75:1 against white. */
  hover: PRIMARY_RAMP.red8,
  /** 10.72:1 against white. */
  active: PRIMARY_RAMP.red9,
} as const;

/**
 * The focus treatment shared by every control that can hold the caret or the keyboard.
 *
 * Purpose: the design system suppresses the browser's own outline on its text input — its outlined
 * variant sets `outline: 0` explicitly at `ui/node_modules/antd/es/input/style/variants.js` — and
 * replaces it with a one-pixel border recolour plus a two-pixel ring at ten percent opacity. The
 * ring is very close to invisible, and the border moves only from `#4096ff` to `#1677ff`: two
 * shades whose luminances are near enough that the states are hard to tell apart, and which differ
 * mostly in one channel, so no greyscale or colour-blind rendering separates them at all. That is
 * the whole focus affordance on every input, picker and number field in the application.
 *
 * Assumptions: this does NOT reopen the `G4` resolution recorded in `ui/src/theme/tokens.ts`, and
 * the distinction matters because that entry is what stopped an earlier reader from theming the
 * input at all. `G4` is about the EDITABLE-FIELD affordance — the 175 `HILIGHT=UNDERLINE` operands,
 * resolved structurally because the component's resting border already carries them — and nothing
 * here changes a resting border. Focus is a state the source display has no vocabulary for: the
 * mapsets express the caret with a single `ATTRB=IC` operand per screen and the terminal drew no
 * ring at all. So this is additive in the sense `ADDITIVE_TOKENS` uses, not a re-decision.
 *
 * Assumptions: the indicator is perceptible WITHOUT hue, which is the property the border recolour
 * alone did not have. Two independent channels carry it. The ring grows from the design system's
 * two-pixel `controlOutlineWidth` to its three-pixel `lineWidthFocus`, a size change no colour
 * model can flatten; and the border moves to the ramp's step 8, whose contrast against the surface
 * is 8.974:1 against the hover shade's 2.990:1. Stated precisely, because both numbers get quoted:
 * that is a threefold separation in CONTRAST, and the two borders' raw relative luminances are
 * 0.0670 and 0.3012, a 4.5-fold separation in luminance itself. Either figure survives greyscale,
 * which is the property being claimed. Both were confirmed in a browser at 1280 pixels, which
 * reported the focused border as rgb(0, 62, 179) and the hovered one as rgb(64, 150, 255).
 *
 * Alternatives Considered: three. Restoring the browser's native outline by clearing the
 * suppression — not reachable, because the suppression is inside the library's own style function
 * and no token switches it off. Leaving the ring translucent and only darkening the border —
 * rejected because it keeps the whole affordance on one channel, and hue is the channel a screenshot
 * comparison and a colour-blind operator both lose. Using the ramp's step 9 for the ring so focus
 * would be the darkest thing on the screen — rejected as heavier than the job needs: a step-6 ring
 * already clears the 3:1 the focus-appearance criterion asks of an indicator against its
 * surroundings, at 4.104:1, and a near-black halo on a form of 128 fields reads as an error rather
 * than as a caret.
 */
const FOCUS_TREATMENT_TOKENS = {
  /** Border under the pointer: the design system's own hover shade, 2.99:1, stated so the states can be compared. */
  hoverBorder: PRIMARY_RAMP.colorPrimaryHover,
  /** Border while focused: 8.97:1, threefold in contrast and 4.5-fold in luminance versus hover. */
  focusBorder: PRIMARY_RAMP.blue8,
  /**
   * Ring while focused: three pixels of the ramp's step 6 at 4.10:1 against the surface.
   *
   * Assumptions: composed from two resolved values rather than authored, so neither the width nor
   * the colour is a literal — the width is the design system's own focus line width and the colour
   * is a step of the measured blue ramp. A shadow is the only shape the component exposes for this,
   * because its outline is suppressed, so the value has to be a shadow string; what the rule
   * forbids is an authored design VALUE, and there is none here.
   */
  focusRing: `0 0 0 ${String(PRIMARY_RAMP.lineWidthFocus)}px ${PRIMARY_RAMP.blue6}`,
} as const;

/**
 * Every global token this application overrides, seeds first.
 *
 * Assumptions: the seed entries are kept in their own constant above rather than merged into this
 * one, because {@link PRIMARY_RAMP} resolves the library's palette FROM them and must not resolve
 * it from the alias corrections that are themselves read out of that palette. Separating the two
 * keeps the resolution one-directional; merging them would be circular.
 *
 * Assumptions: the reasoning for each entry lives at the `token` member of the theme below rather
 * than here, so a reader arrives at it through the theme object they were looking at. This constant
 * exists only so the theme member stays one expression.
 */
const TOKEN_OVERRIDES = {
  ...SEED_OVERRIDES,
  colorLink: LINK_TEXT_TOKENS.rest,
  colorLinkHover: LINK_TEXT_TOKENS.hover,
  colorLinkActive: LINK_TEXT_TOKENS.active,
  colorTextDescription: PRIMARY_RAMP.colorTextSecondary,
} as const;

export const cardDemoTheme: ThemeConfig = {
  /*
   * Alternatives Considered: `theme.compactAlgorithm` and `theme.darkAlgorithm`
   * were both evaluated against this one and both rejected on measured grounds.
   *
   * Compact is the serious candidate, because the source screens are dense. The
   * account-update mapset defines 128 field definitions and account-view 100,
   * both laid out on a 24-row terminal where each field occupied a single
   * character cell, and compact shrinks the control height and the whole padding
   * scale so that a form of that size occupies fewer scroll heights. It is
   * rejected for what it does to the spacing scale the bridge has already
   * resolved against. Computed from the package's own `theme.getDesignToken`
   * accessor at the pinned version, compact moves `marginLG` from 24 to 16,
   * `marginMD` from 20 to 16, `marginXS` from 8 to 4, `paddingLG` from 24 to 16,
   * `paddingSM` from 12 to 8, `fontSizeHeading4` from 20 to 16 and
   * `lineHeightHeading4` from 1.4 to 1.5 — so `marginLG` and `marginMD` become
   * the same value. `tokens.ts` records those two as two separate snap
   * decisions, a large and a medium section gap taken from the blank-row gaps of
   * the 24-row grid, and compact erases the distinction between them: a
   * three-step gap hierarchy collapses to two steps. All seven of those tokens
   * are snaps whose recorded rationale is the nearest step on the system's own
   * scale, and compact moves that scale out from under all seven at once while
   * changing no colour and no font family, leaving the record in `tokens.ts`
   * describing something the theme does not produce.
   *
   * Trade-offs: the density that made compact attractive is answered instead by
   * the recorded G1 resolution — a responsive layout, with two-column description
   * lists for detail views and tables for lists — which is a layout decision
   * confined to the screens that need it. Compressing the global scale would
   * apply that compression to all 21 screens, including the 24-field bill-payment
   * screen, where there is no density problem to solve.
   *
   * Dark is rejected on evidence rather than on preference. The measured bridge
   * resolves each source colour to a semantic role and not to a background
   * polarity, and no requirement in the migration asks for a dark surface.
   * Selecting it would change the derived value of every one of the 662
   * colour-carrying field definitions on an instruction that appears nowhere in
   * the source.
   */
  algorithm: theme.defaultAlgorithm,

  /*
   * Assumptions: supplying any theme object at all puts this application into
   * CSS-variable mode at the pinned version, because the provider materialises a
   * `cssVar` descriptor whenever a theme is passed. The decision here is
   * therefore not whether to use CSS variables but which scope key they are
   * emitted under. Left unset, that key falls back to a value derived from
   * React's generated identifier, which depends on where the provider sits in the
   * element tree and so changes whenever the tree above it changes; the library
   * additionally warns when the key is missing. Naming it fixes the emitted
   * scope, which keeps the custom properties this theme produces stable from one
   * render to the next and attributable to this module. Attributable through the
   * scope rather than through the property names: the key becomes the class
   * selector the token declarations are written under, and the marker attribute
   * on each generated style element, while the names themselves keep the
   * library's prefix for the reason given immediately below.
   *
   * Trade-offs: `prefix` is deliberately left unset so that it keeps tracking the
   * component class-name prefix, which is what it defaults to. Setting it
   * independently would give the emitted custom properties one namespace and the
   * class names that read them another, so anyone tracing a value between the two
   * would have to know both. The cost accepted is that the properties carry the
   * library's default prefix rather than this application's name.
   */
  cssVar: { key: 'carddemo' },

  /*
   * Trade-offs: this equals the library's own default at the pinned version and
   * is stated anyway, for the reason `ui/.prettierrc` gives for stating its own
   * defaults: an unset option is not a decision deferred, it is a decision moved
   * somewhere unreviewable, and a default that changes in a future major version
   * would then change the emitted selector shape with no edit here to show it.
   *
   * Alternatives Considered: `false`, which the library documents as available
   * when an application contains only one copy of the design system — and this
   * one does, since `ui/package-lock.json` resolves exactly one. It is rejected
   * because the hash suffix is what stops a second copy's styles, arriving
   * through some future transitive dependency, from overriding these. Giving that
   * up buys a smaller stylesheet and pays for it with a failure that would
   * surface as a wrong colour on a screen rather than as a build error.
   */
  hashed: true,

  /*
   * Assumptions: this equals the default and is stated because the option is new
   * at this major version and enabling it would break the application silently
   * rather than loudly. Zero-runtime mode stops the library generating style at
   * run time and requires a stylesheet to be imported instead, and this tree
   * imports none: `ui/src/main.tsx` imports no stylesheet, no module under
   * `ui/src` imports one, and `ui/vite.config.ts` records the matching decision to
   * configure no stylesheet preprocessing precisely because the theme is applied
   * once here. Enabling this without adding that import would type-check, build
   * and serve an unstyled application.
   */
  zeroRuntime: false,

  /*
   * Assumptions: this block holds exactly the overrides a token NAME cannot
   * carry, and is otherwise empty by decision — empty is not the same as absent.
   * `tokens.ts` resolves every measured design value to a named token and records
   * no value for any of them, so with two exceptions there is nothing here to
   * bind. The design-token reference names the failure mode that filling it in
   * wholesale would produce: a theme entry restating a value the algorithm would
   * have derived anyway, which pins that one value while the values computed
   * alongside it keep moving. The three roles the bridge resolves onto derived
   * tokens — secondary text, heading text and strong weight — are therefore
   * absent, because they are consumed by name and the derived defaults already
   * carry them.
   *
   * Assumptions: the fixed-pitch requirement is met by the default rather than by
   * an override, which is why it is recorded here as reasoning and not as an
   * entry. Column alignment was free on the source terminal because its character
   * cell grid is monospaced, and the baseline leans on that: 29 field definitions
   * carry right-justification, five of them money fields in `app/bms/COACTVW.bms`
   * carrying a decimal edit mask as well. In a proportional face those columns stop
   * lining up on the decimal point. The token the
   * bridge assigns to that data already resolves to a monospaced stack at the
   * pinned version, and neither algorithm considered above alters it, so setting
   * it would restate what the library supplies. Money also reaches the browser as
   * a decimal string rather than as a number, so that no client can route it
   * through a binary floating-point value; it therefore arrives as text and is
   * rendered as text, and a monospaced face is what allows a column of such text
   * to be read as a column.
   *
   * Refactoring Rationale: the two entries below exist because two token names
   * would otherwise derive one rendered colour, which the bridge cannot fix by
   * naming alone. At the pinned version the library's own seed sets the primary and
   * the informational colour to the same value, so the field definitions the source
   * marks blue and those it marks turquoise resolve identically even though
   * `tokens.ts` keeps them on two distinct names. Accepting that collapse is
   * inconsistent with the gap register, which states that the turquoise original is
   * preserved and rejects the same collapse outright for the four pink fields on the
   * grounds that "collapsing them erases exactly the distinction being migrated" --
   * accepting it for 157 fields while refusing it for 4 is indefensible. The seed
   * layer is where the separation belongs, because the design-token reference's own
   * rule is that the bridge sets the seed layer wherever a seed token expresses the
   * role, precisely so that everything derived from it moves together.
   *
   * Alternatives Considered: two, and both are rejected. A bespoke turquoise hex
   * value, which is the literal the design-token reference forbids — it would have
   * no recorded
   * origin, could not be diffed against the library, and would survive a palette
   * change while everything around it moved. And overriding the derived
   * informational shades directly instead of the seed, which pins one shade of a
   * ten-step ramp while the other nine keep deriving from a value that shade
   * contradicts. What is used instead is the design system's OWN cyan palette
   * anchor, read out of the library's default seed through the name recorded in
   * `BMS_SEED_PALETTE_ANCHORS`: it is the system's only cyan-family hue, it is a
   * settable seed token rather than a literal, and reading it here means the hue
   * is still the library's to change.
   *
   * Assumptions: the informational separation does not stay inside the
   * informational role on its own, and the second entry is what confines it.
   * Computed from the package's own `theme.getDesignToken` accessor at the pinned
   * version, the first entry alone moves 13 derived tokens: the informational
   * colour and its nine-shade ramp, which is the intent, plus the link colour and
   * its hover and active shades, which is not — the link colour derives from the
   * informational seed whenever its own seed is left empty. Nothing in the 21
   * mapsets is a hyperlink, so there is no measured source value asking for a
   * turquoise link; pinning the link seed to the blue anchor holds the link colour
   * exactly where the library's default put it and brings the total movement down
   * to the 10 tokens of the informational ramp. Verified in the same computation:
   * the primary, base text and secondary text colours do not move.
   *
   * Refactoring Rationale: the informational separation makes the informational
   * colour measurably WEAKER as text — 2.21:1 against white where the blue it used
   * to share measured 4.10:1 — and an earlier revision of this comment accepted
   * that on the ground that every semantic colour the library ships is a mid-ramp
   * anchor rather than a text shade. The measurement was right and the conclusion
   * was wrong: both numbers are below the 4.5:1 WCAG AA threshold for normal text,
   * so every label, prompt, hint and value a screen painted from either name was
   * unreadable to the AA standard, and recording the ratio did not make it
   * readable. The seed separation is KEPT, because the hue distinction between the
   * 157 turquoise field definitions and the 384 blue ones is a real measured design
   * value and collapsing it is what the gap register rejects. What changed is that
   * text no longer resolves through the hue map at all: `BMS_TEXT_COLOR_TOKENS` in
   * `ui/src/theme/tokens.ts` resolves each role to a shade that reaches the
   * threshold on the one surface the shell paints, and `BMS_TEXT_CONTRAST_AUDIT`
   * records the in-family ratio each role was snapped away from. So these two
   * entries now govern fills, borders, backgrounds and icons — where 2.21:1 as a
   * text figure is not a claim about anything — and no consumer paints either name
   * as text.
   *
   * ⚠️ Refactoring Rationale: the message band is where this is easiest to get wrong,
   * and this paragraph had it wrong. It said the band "paints its sentence in the
   * neutral text-grade token", and it does not: `ui/src/layout/MessageBand.tsx`
   * resolves each severity through `BMS_TEXT_COLOR_TOKENS`, so the error severity took
   * that map's red role. Two of the three severities happen to resolve to neutral text
   * tokens, which is why the claim looked true, but the error one resolved to the error
   * ramp's text-grade alias and measured 4.224:1 against `colorErrorBg` — the exact
   * shortfall this paragraph quoted while asserting it had been avoided. The correction
   * is in the map rather than here: the red role now resolves to the preset red palette's
   * step 7, which measures 5.571:1 on the screen surface and 5.097:1 on the error tint, so
   * the band keeps its red AND clears the threshold on the surface it is painted on.
   * `ALERT_TINT_TEXT_CONTRAST_AUDIT` in `ui/src/theme/tokens.ts` records all four pairings
   * and `ui/src/theme/textContrast.test.ts` asserts each one, which is what turns this from
   * a claim into a measurement. Severity is still carried by the alert's type, its icon and
   * its ARIA role as well as by the colour, so it never rests on hue alone.
   *
   * Refactoring Rationale: the two link entries and the description entry below are the
   * three overrides a token NAME cannot carry for the same reason the seed entries above
   * cannot — a screen consuming the right name still receives the library's failing value,
   * because the failing value IS what that name resolves to. `LINK_TEXT_TOKENS` records the
   * link measurements and the rejected seed-only alternative. The description entry is the
   * design system's own de-emphasis default, and it is reached by more than one component:
   * the typography secondary variant, the form's extra and help text, the result subtitle,
   * the card meta description and the empty-state description all resolve their colour to
   * it. Measured against the surface the shell paints it reaches 3.352:1, so every
   * parenthesised hint, every field explanation and every empty-state sentence rendered
   * through any of those components was below AA — including the two standing explanations
   * on the account screens that tell an operator why the national identifier and the
   * government-issued identifier render blank, which made the least readable text on the
   * screen the text explaining its most surprising behaviour. It is set to the value the
   * secondary text token resolves to, 6.978:1.
   *
   * Alternatives Considered: scoping the description override to the typography component
   * alone, which is where the hints are. Rejected because the same value reaches five other
   * components in this tree and each of them renders explanatory prose an operator has to
   * read; fixing one and leaving five would make the shortfall harder to find, not smaller.
   * Also considered: repainting each hint at its call site from the text-grade map, which is
   * what `HINT_TEXT_TOKENS` in `ui/src/theme/tokens.ts` publishes for the screens that
   * choose to. Rejected as the ONLY measure, because it leaves the design system's default
   * failing underneath and the next component to render a description inherits the failure
   * again. Both are done: the default is raised here, and the hint roles are published there
   * so a screen can name the operand its mapset actually carries.
   *
   * Trade-offs: the description grade and the secondary grade now resolve to the same value,
   * so one step of the design system's four-step de-emphasis scale is collapsed into the one
   * above it. The hierarchy that matters survives — base text at 16.558:1, then these two at
   * 6.978:1, then the placeholder and disabled grades at 1.834:1, all verified from the
   * package's own accessor — and what is given up is a distinction that was only expressible
   * below the readability threshold.
   */
  token: TOKEN_OVERRIDES,

  /*
   * Assumptions: this is empty by decision. Version 6 of the design system was
   * selected partly because it themes through CSS variables by default, which
   * means the global token layers reach every component as inherited custom
   * properties, and a per-component override is not the mechanism by which a
   * component receives the theme. An entry here is therefore a deviation, and has
   * to earn its place by naming something a global token cannot carry. Nothing
   * across the 21 screens does. The components most likely to want one were each
   * considered, and the two whose reasoning is easiest to get wrong are recorded
   * below so that a later reader does not add an entry on the assumption that the
   * question was never asked.
   *
   * Assumptions: the list components need nothing. Their paging is performed on
   * the server against the same key columns the source browse used, so the
   * built-in pager is switched off at each call site rather than themed here, and
   * theming a pager that is never rendered would style nothing. Offset paging was
   * rejected outright for those screens because under concurrent inserts it skips
   * and repeats rows, which changes observable behaviour that paging by key does
   * not.
   *
   * ⚠️ Refactoring Rationale: this paragraph said the text input "needs nothing
   * either, and its border is the reason", citing the recorded G4 resolution — that
   * the 175 `HILIGHT=UNDERLINE` operands are carried structurally by the component's
   * own border, so overriding it would reopen a closed gap. The premise is right and
   * the conclusion was too wide. G4 is about the EDITABLE-FIELD affordance, which is
   * a RESTING property, and the entries below change no resting border at all: they
   * change the FOCUS state, which the source display has no vocabulary for and which
   * the design system leaves in a condition no operator can see. The reasoning and the
   * measurements are at {@link FOCUS_TREATMENT_TOKENS}; the resting border is untouched,
   * so G4 stands exactly as recorded.
   *
   * Assumptions: three components are named and they are the three this tree renders
   * that read the shared text-input token — the input itself, the number field on the
   * transaction and bill-payment forms, and the date picker on the report form. Counted
   * across `ui/src/screens`, `ui/src/layout` and `ui/src/routes`, the number field
   * appears 5 times and the picker 3, and the select component appears 0 times, which is
   * why it has no entry. Naming them separately is required rather than tidy: each is a
   * separate component namespace, and an override on one does not reach the others even
   * though all three resolve the same token names.
   *
   * Refactoring Rationale: this member is no longer empty, and the single entry
   * below earns its place on the terms this comment sets - it names something a
   * global token cannot carry. The button is the one component that paints TEXT on
   * the primary ramp's own anchor instead of on the surface the shell paints, so
   * the text-grade map in `ui/src/theme/tokens.ts` cannot reach it: that map
   * resolves a text colour against one measured surface, and here the surface is
   * itself the thing that has to move. Moving the global `colorPrimary` instead was
   * rejected outright - it is the measured anchor for 289 blue field definitions
   * and governs fills, borders and icons that no measurement asked to change - so
   * the correction is confined to the component whose foreground was failing.
   */
  components: {
    /*
     * Assumptions: three states are named rather than one. A component-scoped seed
     * does not re-derive the hover and active shades the way a global seed does, so
     * overriding the resting colour alone would leave the design system's own hover
     * shade in place and a solid control would go from a readable 6.16:1 at rest to
     * 2.99:1 under the pointer - a worse pairing than the one being fixed, reachable
     * by hovering.
     * Trade-offs: the design system's default lightens a primary control on hover and
     * this darkens it. The direction is given up because it is the direction that
     * breaks the pairing; the shades are all members of the same measured blue ramp,
     * so the family the mapsets asked for is unchanged.
     *
     * Assumptions: the outlined variant needs no entry of its own even though it is the
     * most numerous button in the application, because the design system derives its
     * hover and active foregrounds from the primary hover and active names above -
     * `defaultHoverColor` and `defaultHoverBorderColor` from the first,
     * `defaultActiveColor` and `defaultActiveBorderColor` from the second, at
     * `ui/node_modules/antd/es/button/style/token.js`. So the three entries already
     * govern it, which is why its hover measures 8.974:1 rather than the library's
     * 2.990:1.
     *
     * Refactoring Rationale: the focus entry is the one that makes the states ORDERED.
     * The design system composes every button's focus outline from one shared helper -
     * `genFocusStyle`, called at `ui/node_modules/antd/es/button/style/index.js` L63 -
     * which reads `colorPrimaryBorder`, and that name resolves to 1.736:1 against the
     * surface the shell paints. With the hover entries above at 8.974:1, focus was more
     * than five times weaker than hover on every button in the application: the state a
     * keyboard operator depends on was the faintest one, and the state a pointer
     * operator gets for free was the strongest. Setting the ring to the base text value
     * makes it 16.558:1, stronger than any hover any variant can reach, which is the
     * ordering a focus indicator has to have.
     *
     * Assumptions: the ring is HUE-NEUTRAL by choice and not by omission. A blue ring on
     * a destructive control asserts the primary role on a control whose whole point is
     * that it is not primary, and the design system publishes no per-variant focus token
     * to fix that with - see the `G9` entry of `DESIGN_GAPS`. A neutral ring asserts no
     * role, so it contradicts none. Alternatives Considered: the ramp's step 9 at
     * 12.076:1, which would keep the family; rejected because it is both weaker than the
     * neutral value and still blue, so it loses on both counts.
     *
     * Assumptions: this name is safe to scope here because nothing else in this tree
     * reads it. Within the button styles `colorPrimaryBorder` is used in exactly one
     * other place, as the active background of the filled and text variants at
     * `ui/node_modules/antd/es/button/style/variant.js` L146, and this application
     * renders neither - measured across `ui/src/screens`, `ui/src/layout` and
     * `ui/src/routes`, there is no `variant=` prop and no `type="text"` button anywhere.
     * Scoping it to the button also leaves the global name untouched for the other
     * components that read it.
     *
     * Refactoring Rationale: the three destructive entries correct the one variant whose
     * contrast the design system's own direction of travel made WORSE. Measured against
     * the surface the shell paints, the dangerous outlined variant rested at 3.268:1 and
     * fell to 2.562:1 under the pointer, so the two destructive controls in this
     * application - the user delete and the reference-type delete - were least readable
     * at the moment of the gesture. `DESTRUCTIVE_SURFACE_TOKENS` carries the measurements
     * and the rejected alternative; the entries are scoped to the button so the error
     * hue is unchanged everywhere it means something other than a control, which is most
     * places: the alert tints, the field-error contract and the fraud status values all
     * resolve the global names.
     */
    Button: {
      colorPrimary: SOLID_SURFACE_TOKENS.rest,
      colorPrimaryHover: SOLID_SURFACE_TOKENS.hover,
      colorPrimaryActive: SOLID_SURFACE_TOKENS.active,
      colorPrimaryBorder: PRIMARY_RAMP.colorText,
      colorError: DESTRUCTIVE_SURFACE_TOKENS.rest,
      colorErrorHover: DESTRUCTIVE_SURFACE_TOKENS.hover,
      colorErrorActive: DESTRUCTIVE_SURFACE_TOKENS.active,
    },

    /*
     * Assumptions: identical to the two entries below it and deliberately not factored
     * into a shared spread. The three components resolve the same token names but are
     * three independent namespaces, and a shared object would read as though one
     * decision governed all three when in fact each has to be stated for its own
     * component to receive it. Writing them out is what makes the requirement visible.
     */
    Input: {
      hoverBorderColor: FOCUS_TREATMENT_TOKENS.hoverBorder,
      activeBorderColor: FOCUS_TREATMENT_TOKENS.focusBorder,
      activeShadow: FOCUS_TREATMENT_TOKENS.focusRing,
    },
    InputNumber: {
      hoverBorderColor: FOCUS_TREATMENT_TOKENS.hoverBorder,
      activeBorderColor: FOCUS_TREATMENT_TOKENS.focusBorder,
      activeShadow: FOCUS_TREATMENT_TOKENS.focusRing,
    },
    DatePicker: {
      hoverBorderColor: FOCUS_TREATMENT_TOKENS.hoverBorder,
      activeBorderColor: FOCUS_TREATMENT_TOKENS.focusBorder,
      activeShadow: FOCUS_TREATMENT_TOKENS.focusRing,
    },

    /*
     * Refactoring Rationale: the row-interaction entries exist because the design
     * system's resting row hover is `colorFillAlter`, black at two percent, which
     * composites to a 1.045:1 difference from the surface beneath it - a tint that is
     * present in the computed style and absent to the eye. The list screens are where
     * an operator picks a record, so the row under the pointer has to be identifiable.
     * The secondary fill is black at six percent, three times the ink, and is the
     * darkest tint on the design system's fill scale that still reads as a highlight
     * rather than as a selected state.
     *
     * Trade-offs: a background tint alone cannot reach the 3:1 the non-text-contrast
     * criterion asks of a state indicator, and no tint on this scale can - a tint that
     * did would be a dark grey band. So this entry is the perceptibility half only. The
     * conformant half is the pointer cursor and the row's own focus affordance, and both
     * are properties of the row element rather than of the theme: they are set through
     * the table's row-level props at each list screen's call site, which is where the
     * question of whether a row is activatable at all is also answered.
     *
     * Assumptions: the two selected-state entries restate the design system's own
     * defaults and are stated anyway, for the reason `hashed` above is stated: the
     * hover entry moves one member of a three-member set, and leaving the other two
     * implicit would make a future reader check the package to find out whether the
     * ordering hover < selected < selected-hover still holds. Named, it is checkable
     * here and asserted in `ui/src/theme/textContrast.test.ts`.
     *
     * ⚠️ Refactoring Rationale: the hover entry is the secondary fill FLATTENED onto
     * the container surface, not the fill itself, because the fill is translucent and a
     * translucent background on a sticky cell stops that cell hiding what it overlays.
     * A browser sweep measured this on the transaction browse at a 375-pixel viewport,
     * where the track needs 454 pixels in 327 and the pinned money column therefore
     * sits over the unpinned date column by 127.47 pixels. At rest the pinned cell
     * computes an opaque white and the operator sees only the amount; on the row under
     * the pointer this entry took over and the date printed through it, so the money
     * column - the one value that screen exists to show - read as a pile of overstruck
     * glyphs. Three observations name the alpha rather than the pin as the cause:
     * scrolling the date out from under the pin cleaned the amount and moved the same
     * artefact onto the leading pinned cell; an intermediate scroll damaged it
     * partially, in proportion to how much the pin covered; and at 768, where the pin
     * overlaps nothing, the identical background is clean. The design system had
     * already answered this - `antd/lib/table/style/index.js` L197-L199 derives the
     * `*Solid` fills and L210 makes `colorFillAlterSolid` its own `rowHoverBg` default,
     * so the defect was introduced by replacing a deliberately-solid default with its
     * translucent source.
     *
     * ⚠️ Assumptions: flattening preserves the perceptibility decision above rather
     * than reopening it. The composite of this fill over this surface has the same
     * appearance as the fill drawn on that surface - that is what compositing means -
     * so the three-times-the-ink choice, and the hover < selected < selected-hover
     * ordering the test asserts, both carry over unchanged. Only the behaviour over a
     * surface that is NOT the container changes, which is precisely the case that was
     * broken.
     */
    Table: {
      rowHoverBg: onOpaqueSurface(PRIMARY_RAMP.colorFillSecondary, PRIMARY_RAMP.colorBgContainer),
      rowSelectedBg: PRIMARY_RAMP.controlItemBgActive,
      rowSelectedHoverBg: PRIMARY_RAMP.controlItemBgActiveHover,
    },

    /*
     * Refactoring Rationale: the radio is the smallest control in the application and
     * the only one that fell below the pointer-target floor. The design system derives
     * its circle from `fontSizeLG`, 16 pixels, and the native input inside it is
     * absolutely inset within that circle's one-pixel border - see
     * `ui/node_modules/antd/es/radio/style/index.js` - so the element that receives the
     * click measures 14 by 14. On the authorization list the selector column carries no
     * label text beside it, so the wrapper does not grow to compensate and the whole
     * target is that 14-pixel square. Deriving the circle from the small control height
     * instead makes it 24, which is exactly the AA floor recorded as
     * `TARGET_SIZE_AA_MINIMUM` in `ui/src/theme/tokens.ts`.
     *
     * Assumptions: the dot has to move with the circle or the proportion breaks. The
     * design system derives the dot by subtracting a fixed inset from the circle, so a
     * larger circle yields a dot that fills two thirds of it rather than a half; halving
     * the circle keeps the ratio the default produced. Both values are computed from a
     * resolved token rather than authored, which is why neither is a literal.
     *
     * Alternatives Considered: raising the global control height to the enhanced 44-pixel
     * figure, which is what an audit measuring against WCAG 2.5.5 asks for.
     * `CONTROL_SCALE_DECISION` in `ui/src/theme/tokens.ts` records the refusal and its
     * grounds: 2.5.5 is a AAA criterion, the AA criterion is 2.5.8 at 24 pixels, the
     * design system's 32-pixel control height already clears it, and the AAP mandates
     * that scale. Also considered: leaving the radio alone on the argument that 2.5.8's
     * spacing exception may cover a small target with clearance around it. Rejected
     * because the exception is a judgement about the rendered layout of each list screen
     * and this is a one-token change that removes the need to make it.
     */
    Radio: {
      radioSize: PRIMARY_RAMP.controlHeightSM,
      dotSize: PRIMARY_RAMP.controlHeightSM / 2,
    },
  },
};

/**
 * Theme a destructive control is wrapped in so its focus ring carries the error hue.
 *
 * Purpose: the focus ring in {@link cardDemoTheme} is deliberately hue-neutral, because the design
 * system derives ONE outline for every button variant — its button style calls the shared focus
 * helper once, at `ui/node_modules/antd/es/button/style/index.js` L63, and that helper reads a
 * single token — so a coloured global ring would put the primary hue around a control whose whole
 * point is that it is not primary. That leaves one gap: a destructive control's focus then asserts
 * no danger at all, while its resting and hovered states both do. This theme closes it for exactly
 * those controls, without moving the ring for the other 73 buttons in the application.
 *
 * Usage: wrap the destructive control — not the screen — in a nested provider.
 *
 * ```tsx
 * <ConfigProvider theme={destructiveFocusTheme}>
 *   <Popconfirm okType="danger" onConfirm={remove}>
 *     <Button danger>Delete</Button>
 *   </Popconfirm>
 * </ConfigProvider>
 * ```
 *
 * Assumptions: a nested provider MERGES rather than replaces, so this object needs to carry only
 * the one token it changes. Verified against the pinned package: `theme.inherit` defaults to true,
 * and `ui/node_modules/antd/es/config-provider/hooks/useTheme.js` spreads each component's
 * overrides over the parent's per component name. So a control inside this provider keeps every
 * other decision {@link cardDemoTheme} makes — the algorithm, the CSS-variable scope, both seeds,
 * the three destructive shades, the control scale — and changes only its ring.
 *
 * Assumptions: the ring takes the error ramp's darkest published step rather than its resting
 * shade, because a focus indicator has to be at least as strong as the hover state it competes
 * with. The destructive hover measures 7.748:1 against the painted surface and this measures
 * 10.718:1, so the ordering rest → hover → focus is strictly increasing. It also clears the 3:1
 * the non-text-contrast criterion asks of an indicator by a wide margin.
 *
 * Alternatives Considered: three. A `dangerous`-specific focus token — does not exist at the pinned
 * version, which is what the `G9` entry of `DESIGN_GAPS` records; the component's token surface
 * publishes per-variant base, hover and active colours and no per-variant focus. A CSS rule
 * targeting the dangerous class — rejected because this tree imports no stylesheet at all and the
 * theme is its single styling layer. Leaving the ring neutral everywhere and recording the
 * semantic loss — rejected because a nested provider reaches it with no new mechanism: the
 * application already installs one provider, and this is the same component with two fewer tokens.
 *
 * Trade-offs: adoption is per call site rather than global, so a destructive control added later
 * without the wrapper gets the neutral ring rather than the red one. That is the cost of the
 * design system deriving one ring for all variants, and it fails safe — the neutral ring is the
 * stronger of the two at 16.558:1, so an unwrapped control is under-labelled, never invisible.
 */
export const destructiveFocusTheme: ThemeConfig = {
  components: {
    Button: {
      colorPrimaryBorder: DESTRUCTIVE_SURFACE_TOKENS.active,
    },
  },
};
