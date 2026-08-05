/**
 * The application's single Ant Design theme object.
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
 * Why this module writes down no design value
 * -------------------------------------------
 * `tokens.ts` deliberately records token *names* and never colour, spacing or
 * typography *values*, so there is no authored value here to bind. The
 * design-token reference states the consequence of filling that gap in anyway:
 * the bridge reads the derived token layers rather than setting them, and a theme
 * entry that merely restates a value the algorithm would have produced anyway
 * pins that one value while everything computed alongside it keeps moving. Both
 * value blocks below are therefore present and empty. That is a recorded decision
 * rather than unfinished work, and each block carries its reasoning at the point
 * of use so the emptiness cannot be read as an omission.
 *
 * WHY (non-obvious design decisions)
 * ----------------------------------
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

import { theme } from "antd";
import type { ThemeConfig } from "antd";

/**
 * The theme handed to the application's only `ConfigProvider`.
 *
 * It governs four things and deliberately not a fifth: the algorithm that derives
 * the map and alias token layers from the seed layer, the CSS-variable scope
 * those layers are emitted into, the class-name shape of the emitted style, and
 * whether style is generated at run time at all. It governs no individual token
 * value, for the reason recorded in the module header above.
 *
 * Trade-offs: this is a documented object literal rather than a factory function.
 * A factory was considered and rejected because nothing here is parameterised.
 * Every setting is a single fixed decision about the design system, taken once for
 * the whole application, so a factory would add a call site and a signature to
 * document without adding a choice that any caller is entitled to make
 * differently.
 */
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
   * changing no colour and no font family, so the audit record in `tokens.ts`
   * would no longer describe what the theme produces.
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
  cssVar: { key: "carddemo" },

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
   * Assumptions: this is empty by decision, and empty is not the same as absent.
   * `tokens.ts` resolves every measured design value to a named token and records
   * no value for any of them, so there is nothing here to bind. The design-token
   * reference names the failure mode that filling it in would produce: a theme
   * entry restating a value the algorithm would have derived anyway, which pins
   * that one value while the values computed alongside it keep moving. The seed
   * layer is where an override belongs when one is genuinely needed, and the three
   * roles the bridge resolves onto derived tokens — secondary text, heading text
   * and strong weight — need none, because they are consumed by name and the
   * derived defaults already carry them.
   *
   * Assumptions: the fixed-pitch requirement is met by the default rather than by
   * an override, which is why it is recorded here as reasoning and not as an
   * entry. Column alignment was free on the source terminal because its character
   * cell grid is monospaced, and the baseline leans on that: 27 field definitions
   * carry right-justification and 2 more carry it with zero-fill, and 5 money
   * fields in `app/bms/COACTVW.bms` — at lines 120, 141, 162, 174 and 195, on the
   * credit limit, cash credit limit, current balance and the two cycle totals —
   * carry a decimal edit mask as well as right-justification. In a proportional
   * face those 29 columns no longer line up on the decimal point. The token the
   * bridge assigns to that data already resolves to a monospaced stack at the
   * pinned version, and neither algorithm considered above alters it, so setting
   * it would restate what the library supplies. Money also reaches the browser as
   * a decimal string rather than as a number, so that no client can route it
   * through a binary floating-point value; it therefore arrives as text and is
   * rendered as text, and a monospaced face is what allows a column of such text
   * to be read as a column.
   *
   * Trade-offs: one consequence of leaving the seed layer alone is recorded here
   * rather than left to be discovered. At the pinned version the primary and
   * informational colour seeds hold the same value, so the 289 field definitions
   * the source marks blue and the 127 it marks turquoise resolve to one rendered
   * colour, even though the bridge keeps them on two distinct token names.
   * Separating them would mean giving the informational seed a turquoise value,
   * which is exactly the bespoke turquoise token the design-token reference
   * considered and rejected for carrying a literal colour value. The coincidence
   * is therefore recorded as an auditable consequence of that decision rather
   * than quietly reversed here, and because the two roles keep distinct names, a
   * later decision to separate them changes one recorded value rather than 127
   * field definitions.
   */
  token: {},

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
   */
  components: {},
};
