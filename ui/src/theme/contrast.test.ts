/**
 * @file Contrast regression tests for the two colour pairs design gap G7 records.
 *
 * Purpose
 * -------
 * Recompute, from the installed design system and this tree's own theme, the contrast ratios that
 * `ui/src/theme/tokens.ts` claims - so that the claim is measured on every run rather than trusted.
 * Two pairs are asserted to clear the WCAG AA minimum for normal text, and the two operand tokens they
 * replace are asserted to FALL SHORT of it, because a substitution whose reason has quietly gone away
 * should be retired rather than left in place.
 *
 * Refactoring Rationale: this file exists because both shortfalls were recorded as prose markers at
 * the elements where they were measured, and prose cannot fail. A browser audit had put the header
 * band at 4.49:1 and the reference-type prompts at 2.2:1; both markers stated the numbers, named the
 * module where the remedy belonged, and left the colours as they were. Measuring them here is what
 * makes the remedy hold: a palette change, a seed change or a token rename that reopens either gap
 * fails a test instead of shipping.
 *
 * Assumptions: the ratios are computed from `theme.getDesignToken` under this tree's own theme rather
 * than from a browser, so a case measures exactly the values the application renders with - including
 * the two seed overrides `ui/src/theme/antdTheme.ts` applies. A browser measurement is what discovered
 * both gaps and is not reproducible in a unit test; the arithmetic below is, and it agrees with the
 * browser figures to two decimal places.
 *
 * Assumptions: the alpha-composited case is handled rather than assumed away. The prompt substitute is
 * a translucent black - `rgba(0,0,0,0.65)` at the pinned version - so its rendered colour depends on
 * the surface beneath it, and a helper that read only hex values would silently compute nothing for
 * the very token this file exists to check.
 */

import { theme } from 'antd';
import { describe, expect, it } from 'vitest';

import packageManifest from '../../package.json';
import { cardDemoTheme } from './antdTheme';
import {
  ACCESSIBLE_TEXT_TOKENS,
  BMS_COLOR_TOKENS,
  CONTRAST_MEASURED_AT_ANTD_VERSION,
  CONTRAST_REFERENCE_SURFACES,
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
 * Every cyan-family token the design system derives, which is the family G7 rules out for text.
 *
 * Assumptions: the whole family is listed rather than sampled, because the claim being asserted is
 * that NONE of them reaches the minimum. A sample would leave the claim resting on the ones nobody
 * checked.
 */
/**
 * Every warning-family token the design system derives, which is the family G7 rules out for the
 * title strings on a light surface.
 *
 * Assumptions: listed exhaustively for the same reason as the cyan family - the claim is that NONE
 * of them reaches the minimum, so a sample would leave the claim resting on the unchecked members.
 * `colorWarningOutline` is included even though it is a shadow colour rather than a text colour,
 * because excluding a member by judgement is what turns an exhaustive census back into a sample.
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
 * The substituted blue clears the minimum on the design system's dark chrome.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function clearsTheMinimumForTheHeaderBand(): void {
  const ratio = tokenContrast(
    ACCESSIBLE_TEXT_TOKENS.BLUE_ON_DARK_CHROME,
    CONTRAST_REFERENCE_SURFACES.darkChrome,
  );

  expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The operand blue still falls short on that surface, which is why the substitute exists.
 *
 * Assumptions: asserted as a SHORTFALL deliberately. If a future palette lifts the operand token past
 * the minimum, this case fails and the right response is to retire the substitution rather than to
 * relax the assertion - a substitution whose reason has gone away is a divergence with no
 * justification left.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function keepsTheReasonTheHeaderSubstituteExists(): void {
  const ratio = tokenContrast(BMS_COLOR_TOKENS.BLUE, CONTRAST_REFERENCE_SURFACES.darkChrome);

  expect(ratio).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The substituted prompt colour clears the minimum on the document surface.
 *
 * Assumptions: this token is translucent, so the case also exercises the compositing above - a
 * measurement that ignored alpha would report the contrast of pure black and pass for the wrong
 * reason.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function clearsTheMinimumForAPromptOnTheBody(): void {
  const ratio = tokenContrast(
    ACCESSIBLE_TEXT_TOKENS.TURQUOISE_ON_BODY,
    CONTRAST_REFERENCE_SURFACES.documentBody,
  );

  expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * No cyan-family token reaches the minimum as text on the document surface.
 *
 * Assumptions: this is the measurement that forced the prompt substitute to leave the hue family, so
 * it is asserted rather than only argued in the gap register. If the informational seed ever moves
 * dark enough for its own ramp to carry text, this case fails and the substitution should be revisited
 * in favour of keeping the turquoise hue.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rulesOutTheWholeCyanFamilyForBodyText(): void {
  for (const name of CYAN_FAMILY_TOKENS) {
    expect(tokenContrast(name, CONTRAST_REFERENCE_SURFACES.documentBody)).toBeLessThan(
      WCAG_AA_NORMAL_TEXT_MINIMUM,
    );
  }
}

/**
 * The substituted blue clears the minimum on the shell's own light surface too.
 *
 * Assumptions: asserted against `shellSurface` rather than `documentBody` because that is the fill
 * the band is actually painted on when a screen renders it in its own body - the shell mounts an antd
 * `Layout`, whose `colorBgLayout` sits one step darker than the container surface. Measuring against
 * the lighter of the two would overstate the ratio and pass for the wrong reason.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function clearsTheMinimumForTheBandOnTheShellSurface(): void {
  const ratio = tokenContrast(
    ACCESSIBLE_TEXT_TOKENS.BLUE_ON_BODY,
    CONTRAST_REFERENCE_SURFACES.shellSurface,
  );

  expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The operand blue still falls short on the shell surface, which is why that substitute exists.
 *
 * Assumptions: a shortfall assertion, for the reason given at the dark-chrome case. It also records
 * that the two surfaces failed independently: the same operand token misses the minimum on both, so
 * neither substitution is redundant with the other.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function keepsTheReasonTheBodySubstituteExists(): void {
  const ratio = tokenContrast(BMS_COLOR_TOKENS.BLUE, CONTRAST_REFERENCE_SURFACES.shellSurface);

  expect(ratio).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * Neither accessible blue serves the other surface, which is why the lookup takes a surface.
 *
 * Assumptions: this is the case that justifies the shape of the API rather than a colour choice. If a
 * future palette produced one step of the ramp that cleared the minimum on both fills, this case
 * fails and the right response is to collapse the two entries into one and drop the parameter, not to
 * relax the assertion.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rulesOutASingleAccessibleBlueForBothSurfaces(): void {
  expect(
    tokenContrast(
      ACCESSIBLE_TEXT_TOKENS.BLUE_ON_DARK_CHROME,
      CONTRAST_REFERENCE_SURFACES.shellSurface,
    ),
  ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    tokenContrast(ACCESSIBLE_TEXT_TOKENS.BLUE_ON_BODY, CONTRAST_REFERENCE_SURFACES.darkChrome),
  ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The substituted title colour clears the minimum on the shell surface, and the operand does not.
 *
 * Assumptions: both halves are asserted in one case because they are one decision - the substitute is
 * only justified while the operand falls short. Only the normal-text threshold is used, for the reason
 * {@link WCAG_AA_NORMAL_TEXT_MINIMUM} records: the largest string here is a 20px semibold heading,
 * which reaches neither the 24px nor the 18.66px-bold bar the 3:1 allowance requires, and an
 * accessibility audit of the rendered page classified that same element as normal weight and failed it
 * at 4.5:1. The classification would not change the outcome either way - the operand measures 1.74:1,
 * below the relaxed allowance as well - so nothing here rests on which bar applies.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function clearsTheMinimumForTheTitleStringsOnTheShellSurface(): void {
  expect(
    tokenContrast(ACCESSIBLE_TEXT_TOKENS.TITLE_ON_BODY, CONTRAST_REFERENCE_SURFACES.shellSurface),
  ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
  expect(
    tokenContrast(BMS_COLOR_TOKENS.YELLOW, CONTRAST_REFERENCE_SURFACES.shellSurface),
  ).toBeLessThan(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * The title operand needs no substitute on the dark chrome, where it clears the minimum.
 *
 * Assumptions: asserted so the asymmetry in the bridge is verified rather than only described. Blue is
 * substituted on both surfaces and yellow on one, and this is the measurement that makes the second
 * half of that sentence true.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function keepsTheTitleOperandOnTheDarkChrome(): void {
  const ratio = tokenContrast(BMS_COLOR_TOKENS.YELLOW, CONTRAST_REFERENCE_SURFACES.darkChrome);

  expect(ratio).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT_MINIMUM);
}

/**
 * No warning-family token reaches the minimum as text on the shell surface.
 *
 * Assumptions: this is the measurement that forced the title substitute to leave the gold hue, so it
 * is asserted rather than only argued in the gap register - the same treatment the cyan family gets.
 * If the warning seed ever moves dark enough for its own ramp to carry text, this case fails and the
 * substitution should be revisited in favour of keeping the yellow.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function rulesOutTheWholeWarningFamilyForTitleText(): void {
  for (const name of WARNING_FAMILY_TOKENS) {
    expect(tokenContrast(name, CONTRAST_REFERENCE_SURFACES.shellSurface)).toBeLessThan(
      WCAG_AA_NORMAL_TEXT_MINIMUM,
    );
  }
}

/**
 * The recorded surfaces still describe the installed design system.
 *
 * Assumptions: the dark chrome value is the library's own `Layout.headerBg` default, recorded in the
 * bridge because `ui/eslint.config.js` bans the deep import that would read it. This case is what
 * stops that record outliving the version it was taken at: a version bump fails here, and
 * re-measuring the default is then part of the upgrade rather than an omission discovered later.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function pinsTheMeasurementToTheInstalledVersion(): void {
  expect(packageManifest.dependencies.antd).toBe(CONTRAST_MEASURED_AT_ANTD_VERSION);
}

/**
 * Registers the eleven contrast cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function contrastCases(): void {
  it('clears the minimum for the header band', clearsTheMinimumForTheHeaderBand);
  it('keeps the reason the header substitute exists', keepsTheReasonTheHeaderSubstituteExists);
  it(
    'clears the minimum for the band on the shell surface',
    clearsTheMinimumForTheBandOnTheShellSurface,
  );
  it('keeps the reason the body substitute exists', keepsTheReasonTheBodySubstituteExists);
  it(
    'rules out a single accessible blue for both surfaces',
    rulesOutASingleAccessibleBlueForBothSurfaces,
  );
  it(
    'clears the minimum for the title strings on the shell surface',
    clearsTheMinimumForTheTitleStringsOnTheShellSurface,
  );
  it('keeps the title operand on the dark chrome', keepsTheTitleOperandOnTheDarkChrome);
  it(
    'rules out the whole warning family for title text',
    rulesOutTheWholeWarningFamilyForTitleText,
  );
  it('clears the minimum for a prompt on the body', clearsTheMinimumForAPromptOnTheBody);
  it('rules out the whole cyan family for body text', rulesOutTheWholeCyanFamilyForBodyText);
  it('pins the measurement to the installed version', pinsTheMeasurementToTheInstalledVersion);
}

describe('the contrast decisions design gap G7 records still hold', contrastCases);
