/**
 * @file Contrast regression tests for the colour decisions design gap G7 records.
 *
 * Purpose
 * -------
 * Recompute, from the installed design system and this tree's own theme, the contrast claims
 * `ui/src/theme/tokens.ts` makes - so that a claim is measured on every run rather than trusted.
 * Three kinds of claim are asserted here, and each fails differently if the bridge drifts:
 *
 * 1. The SURFACE decision. `ui/src/layout/AppShell.tsx` paints its header, body and legend zones
 *    with one surface instead of leaving the design system's dark `Layout.Header` fill in place.
 *    The measurement that retired that fill is asserted, together with the measurement that makes
 *    the decision load-bearing: the text-grade blue the frame now paints does NOT clear the minimum
 *    on that dark fill, so a reintroduced dark zone would reopen the gap rather than inherit its fix.
 * 2. The DIVERGENCE between the hue map and the text map. `BMS_TEXT_COLOR_TOKENS` names a different
 *    token from `BMS_COLOR_TOKENS` for exactly the roles whose operand fails as text, and that
 *    correspondence is asserted role by role rather than described.
 * 3. The three measured IMPOSSIBILITIES. No cyan-family, success-family or warning-family token the
 *    system derives reaches the minimum as text, which is why three roles snapped out of their own
 *    hue families. All three families are enumerated exhaustively, because the claim is that none
 *    of their members clears the bar.
 *
 * ⚠️ Refactoring Rationale: every case here measured against an `ACCESSIBLE_TEXT_TOKENS` map that
 * resolved three colours PER SURFACE - dark chrome, layout grey, container - and that map is
 * withdrawn as superseded. Two of its four entries named a surface the application no longer paints,
 * and no component ever imported it: this file was its only consumer, so the substitutions it
 * documented were proved against backgrounds nothing rendered. The successor mechanism is
 * `BMS_TEXT_COLOR_TOKENS` measured once against `TEXT_CONTRAST_SURFACE`, and the ratios the
 * withdrawn map proved are proved here again against the surface the frame actually paints. Nothing
 * measured is dropped except the one case that justified the map's SHAPE - that no single blue
 * served both surfaces - which has no subject left once there is one surface.
 *
 * Assumptions: `ui/src/theme/textContrast.test.ts` asserts the whole text map, role by role, and the
 * overlap with this file is deliberate rather than duplication. That file is the requirement - every
 * role clears the threshold; this file is the EVIDENCE for the gap register - which pairings failed,
 * on which surface, and what that forced. A reader auditing G7 needs the failures, and a failure is
 * not something the requirement file can hold.
 *
 * Assumptions: the ratios are computed from `theme.getDesignToken` under this tree's own theme rather
 * than from a browser, so a case measures exactly the values the application renders with - including
 * the two seed overrides `ui/src/theme/antdTheme.ts` applies. A browser measurement is what discovered
 * these gaps and is not reproducible in a unit test; the arithmetic below is, and it agrees with the
 * browser figures to two decimal places.
 *
 * Assumptions: the alpha-composited case is handled rather than assumed away. Three of the text-grade
 * tokens are translucent blacks - `rgba(0,0,0,0.65)` and `rgba(0,0,0,0.88)` at the pinned version - so
 * their rendered colour depends on the surface beneath them, and a helper that read only hex values
 * would silently compute nothing for the tokens most of the tree paints prose in.
 */

import { theme } from 'antd';
import { describe, expect, it } from 'vitest';

import packageManifest from '../../package.json';
import { cardDemoTheme } from './antdTheme';
import {
  ALERT_TINT_SURFACES,
  BMS_COLOR_TOKENS,
  BMS_SEED_PALETTE_ANCHORS,
  BMS_TEXT_COLOR_TOKENS,
  CONTRAST_MEASURED_AT_ANTD_VERSION,
  CONTRAST_REFERENCE_SURFACES,
  TEXT_CONTRAST_SURFACE,
  WCAG_AA_NORMAL_TEXT_MINIMUM,
} from './tokens';
import type { AntdTokenName } from './tokens';

/**
 * One colour with its alpha, in the 0-255 and 0-1 ranges the CSS forms use.
 *
 * Assumptions: alpha is carried rather than dropped, because a translucent text colour has no
 * contrast of its own - only the colour it composites to over a given surface does.
 */
interface ParsedColour {
  /** Red channel, 0-255. */
  readonly red: number;
  /** Green channel, 0-255. */
  readonly green: number;
  /** Blue channel, 0-255. */
  readonly blue: number;
  /** Opacity, 0-1, where 1 is fully opaque. */
  readonly alpha: number;
}

/** Matches the three-digit, six-digit and eight-digit hexadecimal CSS colour forms. */
const HEX_COLOUR = /^#(?<digits>[0-9a-f]{3,8})$/iu;

/** Matches the `rgb()` and `rgba()` CSS colour forms the design system emits for translucent text. */
const RGB_COLOUR =
  /^rgba?\(\s*(?<red>[0-9.]+)\s*,\s*(?<green>[0-9.]+)\s*,\s*(?<blue>[0-9.]+)\s*(?:,\s*(?<alpha>[0-9.]+)\s*)?\)$/iu;

/**
 * The resolved design tokens the application renders with.
 *
 * Assumptions: resolved ONCE for the whole file, from the same theme object `ui/src/App.tsx` installs,
 * so every case below measures the shipped palette. Passing the theme rather than an empty object
 * matters: `antdTheme.ts` separates the informational seed from the primary one, which is precisely
 * the change that made the turquoise role a distinct - and much lighter - colour.
 *
 * Assumptions: held as a `Map` keyed by name rather than as the returned object, because the token
 * NAME type this tree uses spans the global surface - alias tokens plus the per-component maps - while
 * the accessor returns the alias layer alone, so indexing the object with a global name does not type.
 * Entering it into a map keyed by string keeps the lookup honest about what it may not find, which the
 * accessor below turns into a named failure.
 */
const resolvedTokens = new Map<string, unknown>(
  Object.entries(theme.getDesignToken(cardDemoTheme)),
);

/**
 * Reads one design token's value as a CSS colour string.
 *
 * @param {AntdTokenName} name - Token to read from the resolved theme.
 * @returns {string} The token's value.
 * @throws {TypeError} If the token does not resolve to a string, which would mean the name is not a
 *   colour token and the caller has asserted contrast against a size or a duration.
 */
function colourToken(name: AntdTokenName): string {
  const value = resolvedTokens.get(name);
  if (typeof value !== 'string') {
    throw new TypeError(`${name} does not resolve to a colour, so it has no contrast to measure.`);
  }
  return value;
}

/**
 * Parses a CSS colour string into channels and alpha.
 *
 * Assumptions: only the forms the design system actually emits are accepted - three-, six- and
 * eight-digit hex, and `rgb()`/`rgba()`. A named colour or a `color-mix()` would throw rather than
 * being guessed at, because a silent fallback here would report a contrast figure for a colour that
 * was never measured.
 * @param {string} value - CSS colour string from a design token.
 * @returns {ParsedColour} The parsed channels and alpha.
 * @throws {TypeError} If the string is in none of the accepted forms.
 */
function parseColour(value: string): ParsedColour {
  const hex = HEX_COLOUR.exec(value.trim());
  if (hex?.groups?.digits !== undefined) {
    const digits = hex.groups.digits;
    /*
     * Assumptions: the three-digit form is expanded by doubling each digit, which is what CSS itself
     * specifies - `#fff` is `#ffffff` and not `#0f0f0f`. The design system emits it for white and
     * black, so the expansion is load-bearing rather than defensive.
     */
    const expanded =
      digits.length === 3
        ? digits
            .split('')
            .map(
              /**
               * Doubles one hexadecimal digit of the short form.
               * @param {string} digit - The digit to double.
               * @returns {string} The two-character channel.
               */
              (digit: string): string => `${digit}${digit}`,
            )
            .join('')
        : digits;
    return {
      red: Number.parseInt(expanded.slice(0, 2), 16),
      green: Number.parseInt(expanded.slice(2, 4), 16),
      blue: Number.parseInt(expanded.slice(4, 6), 16),
      alpha: expanded.length === 8 ? Number.parseInt(expanded.slice(6, 8), 16) / 255 : 1,
    };
  }

  const rgb = RGB_COLOUR.exec(value.trim());
  if (rgb?.groups !== undefined) {
    const { red, green, blue, alpha } = rgb.groups;
    return {
      red: Number(red),
      green: Number(green),
      blue: Number(blue),
      alpha: alpha === undefined ? 1 : Number(alpha),
    };
  }

  throw new TypeError(`${value} is not a colour form this measurement understands.`);
}

/**
 * Composites a possibly translucent colour over an opaque surface.
 *
 * Assumptions: source-over compositing, per channel, which is what a browser does for a translucent
 * text colour on an opaque background. The surface is required to be opaque and would otherwise need
 * a stack of layers, which nothing in this tree paints.
 * @param {ParsedColour} colour - The colour painted on top.
 * @param {ParsedColour} surface - The opaque colour beneath it.
 * @returns {ParsedColour} The opaque result a browser would display.
 */
function compositeOver(colour: ParsedColour, surface: ParsedColour): ParsedColour {
  return {
    red: colour.red * colour.alpha + surface.red * (1 - colour.alpha),
    green: colour.green * colour.alpha + surface.green * (1 - colour.alpha),
    blue: colour.blue * colour.alpha + surface.blue * (1 - colour.alpha),
    alpha: 1,
  };
}

/**
 * Converts one 0-255 channel to its linear-light value.
 *
 * Assumptions: the sRGB transfer function from WCAG 2.1's relative-luminance definition, threshold
 * and exponent included, rather than a squared approximation. The approximation is close enough for
 * most pairs and not for the one this file cares most about: the header pair sits 0.01 from the
 * threshold, so an approximate transfer could put it on either side.
 * @param {number} channel - Channel value, 0-255.
 * @returns {number} The linearised channel, 0-1.
 */
function linearise(channel: number): number {
  const scaled = channel / 255;
  return scaled <= 0.03928 ? scaled / 12.92 : Math.pow((scaled + 0.055) / 1.055, 2.4);
}

/**
 * Computes the WCAG relative luminance of an opaque colour.
 * @param {ParsedColour} colour - The opaque colour to measure.
 * @returns {number} Relative luminance, 0-1.
 */
function relativeLuminance(colour: ParsedColour): number {
  return (
    0.2126 * linearise(colour.red) +
    0.7152 * linearise(colour.green) +
    0.0722 * linearise(colour.blue)
  );
}

/**
 * Computes the WCAG contrast ratio between a text colour and a surface.
 * @param {string} textColour - CSS colour the text is painted in, translucent or not.
 * @param {string} surfaceColour - CSS colour of the opaque surface beneath it.
 * @returns {number} The contrast ratio, from 1 to 21.
 */
function contrastRatio(textColour: string, surfaceColour: string): number {
  const surface = parseColour(surfaceColour);
  const text = compositeOver(parseColour(textColour), surface);
  const textLuminance = relativeLuminance(text);
  const surfaceLuminance = relativeLuminance(surface);
  const lighter = Math.max(textLuminance, surfaceLuminance);
  const darker = Math.min(textLuminance, surfaceLuminance);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Computes the contrast of one token against one reference surface.
 * @param {AntdTokenName} name - Token carrying the text colour.
 * @param {string} surfaceColour - The surface it is painted on.
 * @returns {number} The contrast ratio.
 */
function tokenContrast(name: AntdTokenName, surfaceColour: string): number {
  return contrastRatio(colourToken(name), surfaceColour);
}

/**
 * The surface every text pairing in this file is measured against.
 *
 * Assumptions: resolved from the theme rather than recorded as a hex, because it is a token -
 * `TEXT_CONTRAST_SURFACE` names `colorBgContainer`, which is what `SURFACE_TOKENS.screen` paints on
 * all three shell zones. A recorded literal for it stood in `CONTRAST_REFERENCE_SURFACES` and was
 * withdrawn: a copy of a token value is the drift the bridge exists to prevent, not an instance of
 * preventing it.
 */
const PAINTED_SURFACE = colourToken(TEXT_CONTRAST_SURFACE);

/**
 * Every warning-family token the design system derives, which is the family G7 rules out for text.
 *
 * Assumptions: listed exhaustively rather than sampled, because the claim being asserted is that
 * NONE of them reaches the minimum, so a sample would leave the claim resting on the unchecked
 * members. `colorWarningOutline` is included even though it is a shadow colour rather than a text
 * colour, because excluding a member by judgement is what turns an exhaustive census back into a
 * sample.
 */
const WARNING_FAMILY_TOKENS: readonly AntdTokenName[] = [
  'colorWarning',
  'colorWarningBg',
  'colorWarningBgHover',
  'colorWarningBorder',
  'colorWarningBorderHover',
  'colorWarningHover',
  'colorWarningActive',
  'colorWarningText',
  'colorWarningTextHover',
  'colorWarningTextActive',
  'colorWarningOutline',
];

/**
 * Every cyan-family token the design system derives, which is the other family G7 rules out.
 *
 * Assumptions: enumerated for the same reason as the warning family - the claim is that none of
 * them clears the threshold as text.
 *
 * ⚠️ Refactoring Rationale: the documentation block that belonged to this list had drifted ABOVE
 * the warning list, so the warning list carried two blocks and this one carried none. The lists
 * are unchanged; the blocks now sit on the constants they describe.
 */
const CYAN_FAMILY_TOKENS: readonly AntdTokenName[] = [
  'colorInfo',
  'colorInfoBg',
  'colorInfoBgHover',
  'colorInfoBorder',
  'colorInfoBorderHover',
  'colorInfoHover',
  'colorInfoActive',
  'colorInfoText',
  'colorInfoTextHover',
  'colorInfoTextActive',
];

/**
 * Every success-family token the design system derives, the third family G7 rules out for text.
 *
 * ⚠️ Refactoring Rationale: this list is NEW, and it is added because the register claimed more
 * than the tests proved. Design gap G7 records that three roles cannot be corrected inside their
 * own hue, and only the cyan and warning censuses were asserted - the success family was named in
 * prose with a measured best of 3.463:1 and nothing checked it, which is the same shape of defect
 * as a documented resolution with no consumer. Enumerated exhaustively for the reason the other two
 * are: the claim is that none of its members clears the threshold.
 */
const SUCCESS_FAMILY_TOKENS: readonly AntdTokenName[] = [
  'colorSuccess',
  'colorSuccessBg',
  'colorSuccessBgHover',
  'colorSuccessBorder',
  'colorSuccessBorderHover',
  'colorSuccessHover',
  'colorSuccessActive',
  'colorSuccessText',
  'colorSuccessTextHover',
  'colorSuccessTextActive',
];

/**
 * The tokens this application would have rendered with if only its two seeds were separated.
 *
 * Purpose: four of the corrections `ui/src/theme/antdTheme.ts` applies are ALIAS overrides rather
 * than seed separations, and an alias override erases the value it replaces — once it is in place,
 * the failing value is not reachable from `cardDemoTheme` at all. This resolution is that value:
 * the same two seed separations, and none of the alias or component corrections. Every case below
 * that asserts a shortfall measures it here, which is what keeps the evidence for G7 and G9 in the
 * file that holds the evidence rather than in a commit message.
 *
 * Assumptions: the two seeds are reconstructed from {@link BMS_SEED_PALETTE_ANCHORS} exactly as the
 * theme module reconstructs them, rather than imported from it — the module does not export them,
 * and it should not, because nothing but the theme itself has a reason to seed a palette. The
 * duplication is two token NAMES read out of the library's own default seed, so a palette change
 * moves both copies together and neither can drift into a literal.
 *
 * Alternatives Considered: recording the failing ratios as numbers and asserting the numbers, which
 * is what the register does in prose. Rejected because a recorded number cannot tell you whether it
 * is still the library's behaviour — it would keep asserting a 2018 shortfall through a package
 * upgrade that fixed it, and the whole point of a shortfall case is that it fails when the reason
 * for the override expires.
 */
const UNCORRECTED_TOKENS = new Map<string, unknown>(
  Object.entries(
    theme.getDesignToken({
      token: {
        colorInfo: theme.defaultSeed[BMS_SEED_PALETTE_ANCHORS.TURQUOISE],
        colorLink: theme.defaultSeed[BMS_SEED_PALETTE_ANCHORS.BLUE],
      },
    }),
  ),
);

/**
 * Reads one token's value from the uncorrected resolution.
 *
 * @param {AntdTokenName} name - Token to read from {@link UNCORRECTED_TOKENS}.
 * @returns {string} The value the design system would have supplied without the alias corrections.
 * @throws {TypeError} If the token does not resolve to a string, which would mean the caller has
 *   asked for the contrast of a size or a duration.
 */
function uncorrectedColour(name: AntdTokenName): string {
  const value = UNCORRECTED_TOKENS.get(name);
  if (typeof value !== 'string') {
    throw new TypeError(`${name} does not resolve to a colour, so it has no contrast to measure.`);
  }
  return value;
}

/**
 * Contrast one uncorrected token measures against the surface the frame paints.
 *
 * Assumptions: the SURFACE is taken from the corrected theme even though the text colour is taken
 * from the uncorrected one, and that is deliberate rather than an oversight. None of the four alias
 * corrections touches a background, so the surface is identical in both resolutions, and measuring
 * the pairing the application actually renders against is what makes the shortfall the one an
 * operator saw.
 * @param {AntdTokenName} name - Token carrying the text colour.
 * @returns {number} The contrast ratio against the painted surface.
 */
function uncorrectedContrast(name: AntdTokenName): number {
  return contrastRatio(uncorrectedColour(name), PAINTED_SURFACE);
}

/**
 * The design system's own header fill fails the mapsets' dominant colour as text.
 *
 * Purpose: this is the measurement that retired that fill. `COLOR=BLUE` is 289 of the measured
 * operands, `BMS_COLOR_TOKENS` maps it to the primary role, and on the library's `headerBg` default
 * the role's own anchor lands 0.01 below the requirement - so the deficient half of the pairing was
 * the BACKGROUND, which no token map can correct.
 *
 * Assumptions: asserted as a SHORTFALL, so a palette that lifted the pairing past the minimum fails
 * here and the surface decision gets re-argued rather than silently kept for a reason that expired.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function failsTheDominantOperandOnTheDesignSystemHeaderFill(): void {
  const ratio = tokenContrast(BMS_COLOR_TOKENS.BLUE, CONTRAST_REFERENCE_SURFACES.darkChrome);

  expect(ratio).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The one-surface decision is what makes the text map valid, not an aesthetic preference.
 *
 * Purpose: assert both halves of that. The text-grade blue the frame paints clears the minimum on
 * the surface the frame paints, and FAILS on the dark fill the frame no longer uses - so the fix is
 * a property of the surface plus the token together, and a reintroduced dark zone would reopen
 * design gap G7 rather than inherit its resolution.
 *
 * Assumptions: this case replaces the withdrawn map's "no single accessible blue serves both
 * surfaces" case, which justified resolving text per surface. That shape is gone; what survives from
 * the measurement is the warning it carries for anyone adding a second surface, which is asserted
 * here instead of described.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function bindsTheTextGradeBlueToTheSurfaceItWasMeasuredOn(): void {
  expect(tokenContrast(BMS_TEXT_COLOR_TOKENS.BLUE, PAINTED_SURFACE)).toBeGreaterThanOrEqual(
    WCAG_AA_NORMAL_TEXT_MINIMUM,
  );
  expect(
    tokenContrast(BMS_TEXT_COLOR_TOKENS.BLUE, CONTRAST_REFERENCE_SURFACES.darkChrome),
  ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * What the retired fill bought, recorded as a measurement rather than as a regret.
 *
 * Purpose: the title strings are `COLOR=YELLOW`, and the warning operand CLEARED the minimum on the
 * dark chrome while measuring 1.90:1 on the surface the frame paints - which is why `YELLOW` snaps
 * to the base text token there. Asserting it keeps the recorded trade-off honest: the gold was
 * legible on the fill that was given up, and the snap is the price of the uniform surface.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function recordsWhatTheRetiredFillBoughtForTheTitleStrings(): void {
  const ratio = tokenContrast(BMS_COLOR_TOKENS.YELLOW, CONTRAST_REFERENCE_SURFACES.darkChrome);

  expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The text map diverges from the hue map exactly where the hue map fails as text.
 *
 * Purpose: replace a described correspondence with an asserted one. For every measured role, either
 * the two maps name the same token - and then that token must clear the threshold on the painted
 * surface - or they differ, and then the hue map's operand must be the reason: it must fall short.
 * A divergence with no shortfall behind it would be an unexplained colour change, and a shortfall
 * with no divergence would be a shipped accessibility failure.
 *
 * Assumptions: the roles are read off `BMS_COLOR_TOKENS` rather than listed, so a ninth role added
 * to the bridge is covered by this case on the day it is added.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function divergesFromTheHueMapExactlyWhereItFailsAsText(): void {
  for (const role of Object.keys(BMS_COLOR_TOKENS) as (keyof typeof BMS_COLOR_TOKENS)[]) {
    const operandRatio = tokenContrast(BMS_COLOR_TOKENS[role], PAINTED_SURFACE);
    if (BMS_TEXT_COLOR_TOKENS[role] === BMS_COLOR_TOKENS[role]) {
      expect(
        operandRatio,
        `${role} reads text from its hue token, so that token must clear the threshold`,
      ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
      continue;
    }
    expect(
      operandRatio,
      `${role} names a different token for text, so its hue token must be the reason`,
    ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
    expect(
      tokenContrast(BMS_TEXT_COLOR_TOKENS[role], PAINTED_SURFACE),
      `${role} text token must clear the threshold on the surface the frame paints`,
    ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
  }
}

/**
 * No cyan-family token reaches the minimum as text on the surface the frame paints.
 *
 * Assumptions: this is the measurement that forced the turquoise prompt role out of its hue family,
 * so it is asserted rather than only argued in the gap register. If the informational seed ever
 * moves dark enough for its own ramp to carry text, this case fails and the snap should be revisited
 * in favour of keeping the turquoise hue.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rulesOutTheWholeCyanFamilyForBodyText(): void {
  for (const name of CYAN_FAMILY_TOKENS) {
    expect(
      tokenContrast(name, PAINTED_SURFACE),
      `${name} must not reach the threshold`,
    ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
  }
}

/**
 * No warning-family token reaches the minimum as text on the surface the frame paints.
 *
 * Assumptions: the same treatment the cyan family gets, for the same reason - this is what forced
 * the title role to snap to the base text token rather than to a darker gold.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rulesOutTheWholeWarningFamilyForTitleText(): void {
  for (const name of WARNING_FAMILY_TOKENS) {
    expect(
      tokenContrast(name, PAINTED_SURFACE),
      `${name} must not reach the threshold`,
    ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
  }
}

/**
 * No success-family token reaches the minimum as text on the surface the frame paints.
 *
 * Assumptions: the third census, and the one the register was asserting without evidence. It is
 * what forced the green role to snap to the base text token: its own anchor measures 2.265:1 and
 * the darkest shade its ramp publishes reaches 3.463:1, so no green in this theme can be read as
 * normal text.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rulesOutTheWholeSuccessFamilyForBodyText(): void {
  for (const name of SUCCESS_FAMILY_TOKENS) {
    expect(
      tokenContrast(name, PAINTED_SURFACE),
      `${name} must not reach the threshold`,
    ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
  }
}

/**
 * The link alias fails as text AND gets lighter under the pointer, which is why all three moved.
 *
 * Purpose: this is the evidence for the link half of design gap G7. The design system's link alias
 * is the colour `ui/src/layout/AppShell.tsx` painted its sign-off control in, because a link button
 * with no colour of its own takes it verbatim, and it fell below the minimum at rest. The second
 * assertion is the more important one: the hover shade is LIGHTER than the resting one, so the state
 * a pointer reaches is worse than the state it starts from, and that is the property that made a
 * seed-only correction useless — seeding the link darker still derives a lighter hover.
 *
 * Assumptions: asserted as shortfalls, so a package version that darkened the alias fails here and
 * the three-state override is re-argued rather than kept for a reason that expired.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function recordsTheLinkShortfallThatForcedThreeStateOverrides(): void {
  const rest = uncorrectedContrast('colorLink');
  const hover = uncorrectedContrast('colorLinkHover');

  expect(rest, 'the link alias must be the reason the resting state was overridden').toBeLessThan(
    WCAG_AA_NORMAL_TEXT_MINIMUM,
  );
  expect(hover, 'the hover alias must be the reason the hover state was overridden').toBeLessThan(
    WCAG_AA_NORMAL_TEXT_MINIMUM,
  );
  expect(
    hover,
    'the design system lightens a link on hover, which is why a seed-only fix does not work',
  ).toBeLessThan(rest);
  expect(
    tokenContrast('colorLink', PAINTED_SURFACE),
    'the shipped resting link must clear the threshold the alias missed',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    tokenContrast('colorLinkHover', PAINTED_SURFACE),
    'the shipped hover link must clear the threshold the alias missed',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The de-emphasis default fails as text, which is why every explanatory sentence was below AA.
 *
 * Purpose: the evidence for the hint and description half of G7. The design system's description
 * grade is what its typography secondary variant, its form extra and help text, its result subtitle,
 * its card meta description and its empty-state description all resolve to — so this single value
 * governed every parenthesised width hint and every standing explanation in the tree, including the
 * two that tell an operator why the national and government identifiers render blank.
 *
 * Assumptions: the secondary grade is asserted to have cleared the threshold all along, because that
 * is what makes the correction a one-value change rather than a new colour: the override sets the
 * description grade TO the secondary value, so the evidence has to show the target was already
 * sound.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function recordsTheDeEmphasisShortfallThatRaisedTheDescriptionGrade(): void {
  expect(
    uncorrectedContrast('colorTextDescription'),
    'the description grade must be the reason it was overridden',
  ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    uncorrectedContrast('colorTextSecondary'),
    'the value it was raised to must have cleared the threshold already',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    tokenContrast('colorTextDescription', PAINTED_SURFACE),
    'the shipped description grade must clear the threshold',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The error ramp's text-grade alias clears the threshold on the screen and fails on the alert tint.
 *
 * Purpose: the evidence for the message-band half of G7, and the only shortfall in this file that is
 * invisible on the surface everything else is measured against. The red role resolved to the error
 * ramp's own `…TextActive` alias, which measures above the minimum on the painted surface — so every
 * per-role assertion passed — while the band renders that role on the design system's error tint,
 * where the same alias falls below it. A pairing can only be judged on the surface it is painted on,
 * and this case is what holds that.
 *
 * Assumptions: both halves are asserted, so the case explains the move rather than only recording
 * it — the alias fails on the tint, and the shade the role was moved to clears it there.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function recordsTheAlertTintShortfallThatMovedTheRedRole(): void {
  const tint = colourToken(ALERT_TINT_SURFACES.error);

  expect(
    tokenContrast('colorErrorTextActive', PAINTED_SURFACE),
    'the alias cleared the threshold on the painted surface, which is why nothing caught it',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    tokenContrast('colorErrorTextActive', tint),
    'the alias must fail on the tint the band paints it on',
  ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    tokenContrast(BMS_TEXT_COLOR_TOKENS.RED, tint),
    'the shade the red role was moved to must clear the threshold on that tint',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The shared focus ring is fainter than every hover state, which is the evidence for design gap G9.
 *
 * Purpose: the design system composes every button's focus outline from one token, and that token is
 * a light tint. Measured against the painted surface it is barely distinguishable from the surface
 * itself, while the outlined variant's hover — governed by this tree's own primary overrides —
 * recolours label and border to a strong shade. So the state a keyboard operator depends on was the
 * faintest one on every button in the application, and a pointer operator got the strongest for
 * free. That inversion is what the hue-neutral ring corrects.
 *
 * Assumptions: the comparison is against the SHIPPED hover rather than the library's, because the
 * inversion being recorded is the one this application actually rendered — its hover overrides were
 * already in place when the ring was measured, and they are what made the gap wide rather than
 * narrow.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function recordsTheFocusRingShortfallThatForcedAHueNeutralRing(): void {
  const libraryRing = uncorrectedContrast('colorPrimaryBorder');
  const shippedHover = contrastRatio(
    String(cardDemoTheme.components?.Button?.colorPrimaryHover),
    PAINTED_SURFACE,
  );
  const shippedRing = contrastRatio(
    String(cardDemoTheme.components?.Button?.colorPrimaryBorder),
    PAINTED_SURFACE,
  );

  expect(libraryRing, 'the shared ring token must be the reason it was overridden').toBeLessThan(
    WCAG_AA_NORMAL_TEXT_MINIMUM,
  );
  expect(
    libraryRing,
    'the ring was fainter than hover, which is the inversion the override corrects',
  ).toBeLessThan(shippedHover);
  expect(
    shippedRing,
    'the shipped ring must be at least as strong as the hover state it was weaker than',
  ).toBeGreaterThanOrEqual(shippedHover);
}

/**
 * A destructive control loses contrast under the pointer by default, the other half of G9.
 *
 * Purpose: the design system paints its dangerous variant in the error ramp's mid anchor and
 * LIGHTENS it on hover, so the two destructive controls in this application — the user delete and
 * the reference-type delete — were below the minimum at rest and further below it at the moment of
 * the gesture. Both halves are asserted, because the shortfall alone would justify a darker resting
 * shade while the direction is what justifies overriding all three states.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function recordsTheDestructiveShortfallThatForcedThreeStateOverrides(): void {
  const rest = uncorrectedContrast('colorError');
  const hover = uncorrectedContrast('colorErrorHover');

  expect(rest, 'the error anchor must be the reason the resting state was overridden').toBeLessThan(
    WCAG_AA_NORMAL_TEXT_MINIMUM,
  );
  expect(
    hover,
    'the design system lightens a destructive control on hover, which is the direction corrected',
  ).toBeLessThan(rest);
  expect(
    contrastRatio(String(cardDemoTheme.components?.Button?.colorError), PAINTED_SURFACE),
    'the shipped destructive control must clear the threshold at rest',
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The recorded dark-chrome fill still describes the installed design system.
 *
 * Assumptions: that hex is the library's own `Layout.headerBg` default, recorded in the bridge
 * because `ui/eslint.config.js` bans the deep import that would read it. This case is what stops
 * the record outliving the version it was taken at: a version bump fails here, and re-measuring the
 * default is then part of the upgrade rather than an omission discovered later. Every other value
 * this file measures is resolved from the theme, so the pin protects exactly the one literal left.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function pinsTheMeasurementToTheInstalledVersion(): void {
  expect(packageManifest.dependencies.antd).toBe(CONTRAST_MEASURED_AT_ANTD_VERSION);
}

/**
 * Registers the contrast cases.
 *
 * ⚠️ Refactoring Rationale: this said "the eight contrast cases" and now says neither a count nor a
 * list, because the count was the part that went stale — five cases were added for the shortfalls
 * that forced the alias and component overrides, and a number in a doc block is a second place to
 * remember to edit. The cases themselves are the list.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function contrastCases(): void {
  it(
    'fails the dominant operand on the design system header fill',
    failsTheDominantOperandOnTheDesignSystemHeaderFill,
  );
  it(
    'binds the text-grade blue to the surface it was measured on',
    bindsTheTextGradeBlueToTheSurfaceItWasMeasuredOn,
  );
  it(
    'records what the retired fill bought for the title strings',
    recordsWhatTheRetiredFillBoughtForTheTitleStrings,
  );
  it(
    'diverges from the hue map exactly where it fails as text',
    divergesFromTheHueMapExactlyWhereItFailsAsText,
  );
  it('rules out the whole cyan family for body text', rulesOutTheWholeCyanFamilyForBodyText);
  it('rules out the whole success family for body text', rulesOutTheWholeSuccessFamilyForBodyText);
  it(
    'rules out the whole warning family for title text',
    rulesOutTheWholeWarningFamilyForTitleText,
  );
  it(
    'records the link shortfall that forced three-state overrides',
    recordsTheLinkShortfallThatForcedThreeStateOverrides,
  );
  it(
    'records the de-emphasis shortfall that raised the description grade',
    recordsTheDeEmphasisShortfallThatRaisedTheDescriptionGrade,
  );
  it(
    'records the alert-tint shortfall that moved the red role',
    recordsTheAlertTintShortfallThatMovedTheRedRole,
  );
  it(
    'records the focus-ring shortfall that forced a hue-neutral ring',
    recordsTheFocusRingShortfallThatForcedAHueNeutralRing,
  );
  it(
    'records the destructive shortfall that forced three-state overrides',
    recordsTheDestructiveShortfallThatForcedThreeStateOverrides,
  );
  it('pins the measurement to the installed version', pinsTheMeasurementToTheInstalledVersion);
}

describe('the contrast decisions design gap G7 records still hold', contrastCases);
