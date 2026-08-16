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
 */
const PRIMARY_RAMP = theme.getDesignToken({ token: SEED_OVERRIDES });

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
   * Assumptions: the message band is where this is easiest to get wrong, so it is
   * named. It renders the design system's alert, whose informational, success and
   * error backgrounds are themselves tinted, and no shade of the matching ramp
   * reaches 4.5:1 against its own tint — the darkest are 3.25:1, 3.17:1 and
   * 4.22:1. `ui/src/layout/MessageBand.tsx` therefore paints its sentence in the
   * neutral text-grade token and carries severity through the alert's type, its
   * icon and its ARIA role, which is the component's own default treatment.
   */
  token: SEED_OVERRIDES,

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
   * Assumptions: the text input needs nothing either, and its border is the
   * reason. The source marks an editable field by underlining it, 158 times
   * across the 17 base mapsets and 175 times across all 21, and the recorded G4
   * resolution is that no token is required because the input component's own
   * border already carries that affordance structurally. Overriding that border
   * here would replace a structural resolution with a themed one and reopen a gap
   * the bridge has already closed.
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
     */
    Button: {
      colorPrimary: SOLID_SURFACE_TOKENS.rest,
      colorPrimaryHover: SOLID_SURFACE_TOKENS.hover,
      colorPrimaryActive: SOLID_SURFACE_TOKENS.active,
    },
  },
};
