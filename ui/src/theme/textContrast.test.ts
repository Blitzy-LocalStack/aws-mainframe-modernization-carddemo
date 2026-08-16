/**
 * @file Pins the text-grade half of the colour bridge to a measured contrast threshold.
 *
 * Purpose
 * -------
 * `ui/src/theme/tokens.ts` records, as data, which design-system token each BMS colour role
 * resolves to when it is painted as text, and what that pairing measures. This file computes those
 * ratios from the pinned package's own token accessor and fails when any of them falls below
 * {@link TEXT_CONTRAST_THRESHOLD}, so the record cannot drift away from what the theme actually
 * produces.
 *
 * WHY this file exists — Refactoring Rationale: the previous arrangement painted text straight from
 * the hue map, whose entries are mid-ramp fill anchors, and shipped two measured accessibility
 * failures for every screen: 2.21:1 for the informational role that carries labels and prompts, and
 * 4.10:1 for the primary role that carries hints and values. Both ratios were recorded in comments
 * and both were accepted, which is precisely the failure this file closes — a documented
 * measurement is not a readable colour, and a comment cannot fail a build. The threshold is
 * asserted rather than described, so a future token change, a seed change or a theme algorithm
 * change that pushes a role back below it breaks here instead of on a screen.
 *
 * Assumptions: the ratios are computed against the ONE surface the shell paints its three zones
 * with, which is why `SURFACE_TOKENS` exists. Measuring against the design system's own defaults
 * would measure three different backgrounds — a dark header fill, a grey layout fill and a white
 * container — for the same token, which is how the header pairing came to measure 4.49:1 while the
 * body measured 4.10:1 for one declaration.
 *
 * Assumptions: every callback is a named declaration rather than an inline arrow, because
 * `ui/eslint.config.js` requires a documentation block on a function expression in any position.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { theme } from 'antd';
import { describe, expect, it } from 'vitest';

import { cardDemoTheme } from './antdTheme';
import {
  BMS_TEXT_COLOR_TOKENS,
  BMS_TEXT_CONTRAST_AUDIT,
  SURFACE_TOKENS,
  TEXT_CONTRAST_SURFACE,
  TEXT_CONTRAST_THRESHOLD,
} from './tokens';
import type { AntdTokenName } from './tokens';

/**
 * The application's resolved token values, derived by the design system's own algorithm.
 *
 * Assumptions: the application's theme object is passed rather than the library's defaults, so the
 * separated informational seed is included. Measuring the defaults would measure a palette this
 * application does not render.
 *
 * Assumptions: the result is spread into a string-keyed view rather than read through its own type.
 * The accessor publishes the ALIAS layer, while `AntdTokenName` spans the alias layer and the
 * per-component layer together, so indexing the accessor's type with a token name is a type error
 * for every component key even though no component key is ever looked up here. Spreading is
 * preferred to a direct assertion on the accessor's result because it produces a plain object whose
 * index signature is genuine rather than asserted, and the values are stringified at the point of
 * use, which is where the shape actually matters.
 */
const resolved = { ...theme.getDesignToken(cardDemoTheme) } as Readonly<Record<string, unknown>>;

/**
 * The token a solid control paints its label in.
 *
 * Assumptions: this is the design system's own name for the foreground of a filled control, so
 * measuring against it measures what the component renders rather than an assumed white.
 */
const BMS_LIGHT_SOLID_TEXT_TOKEN: AntdTokenName = 'colorTextLightSolid';

/**
 * Matches a typography `type` prop naming one of the three hue anchors that fail as text.
 *
 * Assumptions: `secondary` is deliberately absent from the alternation — it resolves to a neutral
 * text token the audit already measures above the threshold, so forbidding it would forbid a
 * conforming spelling.
 */
const HUE_ANCHOR_AS_TEXT = /type=(?:"|')(?:warning|danger|success)(?:"|')/u;

/** Number of decimal places the recorded ratios are stated to. */
const RECORDED_PRECISION = 3;

/**
 * Tolerance allowed between a recorded ratio and the computed one.
 *
 * Assumptions: the recorded figures are rounded to {@link RECORDED_PRECISION} places, so an exact
 * equality test would fail on rounding alone. Half a unit in the last recorded place is the widest
 * disagreement rounding can produce, which makes this tolerance the rounding error and nothing
 * more — a genuine drift is orders of magnitude larger.
 */
const RATIO_TOLERANCE = 0.5 * 10 ** -RECORDED_PRECISION;

/** One colour channel triple with its alpha, in the order CSS states them. */
type Channels = readonly [number, number, number, number];

/**
 * Parses the three CSS colour forms the design system emits into channels.
 *
 * Assumptions: only `#rgb`, `#rrggbb` and `rgb()`/`rgba()` are handled, because those are the only
 * forms the pinned version's token values take — the neutral text tokens are `rgba()` with an alpha
 * and every semantic shade is a six-digit hexadecimal. A fourth form would be a change in the
 * library's output that this test should refuse rather than silently approximate.
 * @param {string} value - A resolved token value.
 * @returns {Channels} Red, green and blue in 0-255, and alpha in 0-1.
 * @throws {Error} If the value is not one of the three handled forms, because a colour that cannot
 *   be parsed cannot be asserted about and must not be reported as passing.
 */
function channelsOf(value: string): Channels {
  const shortHex = /^#([0-9a-f])([0-9a-f])([0-9a-f])$/iu.exec(value);
  if (shortHex !== null) {
    const [, red = '', green = '', blue = ''] = shortHex;
    return [
      Number.parseInt(`${red}${red}`, 16),
      Number.parseInt(`${green}${green}`, 16),
      Number.parseInt(`${blue}${blue}`, 16),
      1,
    ];
  }

  const longHex = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/iu.exec(value);
  if (longHex !== null) {
    const [, red = '', green = '', blue = ''] = longHex;
    return [Number.parseInt(red, 16), Number.parseInt(green, 16), Number.parseInt(blue, 16), 1];
  }

  const functional = /^rgba?\(([^)]+)\)$/iu.exec(value);
  if (functional !== null) {
    const parts = (functional[1] ?? '').split(',').map(Number);
    const [red = 0, green = 0, blue = 0, alpha = 1] = parts;
    return [red, green, blue, alpha];
  }

  throw new Error(`Unsupported colour form: ${value}`);
}

/**
 * Converts one 0-255 channel to its linear-light value.
 * @param {number} channel - Channel value in 0-255.
 * @returns {number} The linearised channel in 0-1.
 */
function linearise(channel: number): number {
  const unit = channel / 255;
  return unit <= 0.03928 ? unit / 12.92 : ((unit + 0.055) / 1.055) ** 2.4;
}

/**
 * Computes relative luminance for an opaque colour.
 * @param {Channels} channels - Opaque channels; alpha is ignored.
 * @returns {number} Relative luminance as WCAG defines it.
 */
function luminanceOf(channels: Channels): number {
  return (
    0.2126 * linearise(channels[0]) +
    0.7152 * linearise(channels[1]) +
    0.0722 * linearise(channels[2])
  );
}

/**
 * Contrast ratio between one token painted as text and one token painted as its background.
 *
 * Assumptions: a translucent foreground is composited over the background before the ratio is
 * taken, because the neutral text tokens the bridge snaps to are `rgba()` values with an alpha of
 * 0.65 or 0.88. Taking the ratio without compositing would treat 65% black as pure black and
 * overstate every snapped role.
 * @param {AntdTokenName} foreground - Token name painted as text.
 * @param {AntdTokenName} background - Token name painted as the surface behind it.
 * @returns {number} The WCAG contrast ratio, at least 1 and at most 21.
 */
function contrastRatio(foreground: AntdTokenName, background: AntdTokenName): number {
  return ratioBetween(String(resolved[foreground]), String(resolved[background]));
}

/**
 * Computes the WCAG contrast ratio between two resolved colour VALUES.
 *
 * Assumptions: this takes values where {@link contrastRatio} takes token names, because a
 * per-component override is a colour the theme object states directly and has no alias name to look
 * up. The arithmetic lives here and {@link contrastRatio} delegates to it, so the two entry points
 * cannot measure the same pairing differently.
 * @param {string} foreground - The text colour, which may carry an alpha channel.
 * @param {string} background - The surface behind it, which must be opaque.
 * @returns {number} The ratio, at least 1.
 */
function ratioBetween(foreground: string, background: string): number {
  const behind = channelsOf(background);
  const front = channelsOf(foreground);
  const composited: Channels = [
    front[0] * front[3] + behind[0] * (1 - front[3]),
    front[1] * front[3] + behind[1] * (1 - front[3]),
    front[2] * front[3] + behind[2] * (1 - front[3]),
    1,
  ];
  const lighter = Math.max(luminanceOf(composited), luminanceOf(behind));
  const darker = Math.min(luminanceOf(composited), luminanceOf(behind));
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Asserts every colour role reaches the AA threshold when painted as text.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyRoleReachesTheThreshold(): void {
  for (const entry of BMS_TEXT_CONTRAST_AUDIT) {
    const ratio = contrastRatio(entry.token, TEXT_CONTRAST_SURFACE);
    expect(
      ratio,
      `${entry.role} text must reach WCAG AA on the screen surface`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST_THRESHOLD);
  }
}

/**
 * Asserts the recorded ratios are the ratios the theme actually produces.
 * @returns {void} Nothing; assertions raise on failure.
 */
function recordedRatiosMatchTheTheme(): void {
  for (const entry of BMS_TEXT_CONTRAST_AUDIT) {
    expect(
      contrastRatio(entry.token, TEXT_CONTRAST_SURFACE),
      `${entry.role} recorded ratio must match the theme`,
    ).toBeCloseTo(entry.ratio, RECORDED_PRECISION - 1);
    expect(
      Math.abs(contrastRatio(entry.inFamilyToken, TEXT_CONTRAST_SURFACE) - entry.inFamilyRatio),
      `${entry.role} recorded in-family ratio must match the theme`,
    ).toBeLessThan(RATIO_TOLERANCE * 10);
  }
}

/**
 * Asserts the audit and the consumed map agree, entry for entry.
 *
 * Assumptions: the map is what screens import and the audit is what a reader trusts, so a
 * disagreement between them would leave the evidence describing a colour nothing renders.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theAuditDescribesTheMap(): void {
  const roles = BMS_TEXT_CONTRAST_AUDIT.map(
    /**
     * Reads one audit row's role.
     * @param {(typeof BMS_TEXT_CONTRAST_AUDIT)[number]} entry - One audit row.
     * @returns {string} The role that row resolves.
     */
    (entry: (typeof BMS_TEXT_CONTRAST_AUDIT)[number]): string => entry.role,
  );
  expect(roles.sort()).toStrictEqual(Object.keys(BMS_TEXT_COLOR_TOKENS).sort());

  for (const entry of BMS_TEXT_CONTRAST_AUDIT) {
    expect(BMS_TEXT_COLOR_TOKENS[entry.role], `${entry.role} map entry must match the audit`).toBe(
      entry.token,
    );
    if (entry.resolution === 'exact') {
      expect(entry.inFamilyToken, `${entry.role} kept its hue family`).toBe(entry.token);
    } else {
      expect(
        entry.inFamilyRatio,
        `${entry.role} snapped, so its in-family shade must be the reason`,
      ).toBeLessThan(TEXT_CONTRAST_THRESHOLD);
    }
  }
}

/**
 * Asserts the message band's sentence reaches the threshold on all three alert surfaces.
 *
 * Assumptions: `ui/src/layout/MessageBand.tsx` paints every severity in the base text role for
 * exactly this reason — its alert tints its own background per severity, and no shade of the
 * matching ramp reaches the threshold against its own tint. This case pins the consequence of that
 * decision rather than the decision itself, which is why it names the three background tokens.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theBandReachesTheThresholdOnEveryTint(): void {
  const tints: readonly AntdTokenName[] = ['colorErrorBg', 'colorSuccessBg', 'colorInfoBg'];
  for (const tint of tints) {
    expect(
      contrastRatio(BMS_TEXT_COLOR_TOKENS.DEFAULT, tint),
      `the band's sentence must reach WCAG AA on ${tint}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST_THRESHOLD);
  }
}

/**
 * Asserts the shell's surface token is the surface the ratios were measured against.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theShellPaintsTheMeasuredSurface(): void {
  expect(SURFACE_TOKENS.screen).toBe(TEXT_CONTRAST_SURFACE);
}

/**
 * Asserts a solid control's white label reaches the threshold in every state it can be seen in.
 *
 * Purpose: the design system paints a solid primary control in the ramp's own anchor and puts the
 * light-solid text token on it, a pairing that measures below the threshold — and its default hover
 * shade measures lower still, so the worst state is one the pointer reaches. `ui/src/theme/
 * antdTheme.ts` therefore overrides three component shades, and this case is what stops any one of
 * them from being widened back to a failing value.
 *
 * Assumptions: the three overrides are read from the theme object rather than restated here, so the
 * case measures what the application actually configures. Assumptions: they are measured against
 * the light-solid TEXT token rather than against literal white, because that token is what the
 * component paints the label in.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theSolidControlReachesTheThresholdInEveryState(): void {
  const overrides = cardDemoTheme.components?.Button;
  const states: readonly (readonly [string, unknown])[] = [
    ['at rest', overrides?.colorPrimary],
    ['under the pointer', overrides?.colorPrimaryHover],
    ['while pressed', overrides?.colorPrimaryActive],
  ];

  for (const [state, surface] of states) {
    expect(typeof surface, `the solid control must declare its surface ${state}`).toBe('string');
    expect(
      ratioBetween(String(resolved[BMS_LIGHT_SOLID_TEXT_TOKEN]), String(surface)),
      `a solid control's label must reach WCAG AA ${state}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST_THRESHOLD);
  }
}

/**
 * Asserts no module paints a hue anchor as text through the typography component's own type prop.
 *
 * Purpose: the hue map answers for fills, borders, backgrounds and icons, and its warning, error
 * and success anchors measure 1.90:1, 4.10:1 and 3.46:1 as text. A `type` prop naming one of them
 * bypasses the text-grade map entirely, which is exactly how the header's two title lines came to
 * be painted at the worst ratio on any screen while every token-level case here passed. A search of
 * the source for the bypass is decidable and cannot be satisfied vacuously.
 *
 * Assumptions: `type="secondary"` is NOT forbidden — it resolves to a neutral text token that the
 * audit already measures at 6.98:1, so it is inside the discipline rather than around it.
 * @returns {void} Nothing; assertions raise on failure.
 */
function noModulePaintsAHueAnchorAsText(): void {
  const roots = [
    join(import.meta.dirname, '..', 'layout'),
    join(import.meta.dirname, '..', 'screens'),
    join(import.meta.dirname, '..', 'theme'),
  ];
  const offenders: string[] = [];

  for (const root of roots) {
    for (const file of readdirSync(root, { recursive: true, encoding: 'utf8' })) {
      const path = join(root, file);
      if (!/\.tsx?$/u.test(path) || !statSync(path).isFile()) {
        continue;
      }
      for (const [index, line] of readFileSync(path, 'utf8').split('\n').entries()) {
        const code = line.replace(/\/\/.*$/u, '').replace(/^\s*\*.*$/u, '');
        if (HUE_ANCHOR_AS_TEXT.test(code)) {
          offenders.push(`${path}:${String(index + 1)}`);
        }
      }
    }
  }

  expect(
    offenders,
    'a hue anchor named through the type prop bypasses BMS_TEXT_COLOR_TOKENS and measures below' +
      ' the threshold as text -- resolve the role through the text-grade map instead',
  ).toStrictEqual([]);
}

/**
 * Registers the text-contrast cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function textContrastCases(): void {
  it('reaches WCAG AA for every colour role painted as text', everyRoleReachesTheThreshold);
  it('records the ratios the theme actually produces', recordedRatiosMatchTheTheme);
  it('keeps the audit and the consumed map in agreement', theAuditDescribesTheMap);
  it(
    'reaches WCAG AA in the message band on every severity tint',
    theBandReachesTheThresholdOnEveryTint,
  );
  it('measures against the surface the shell paints', theShellPaintsTheMeasuredSurface);
  it(
    'reaches WCAG AA on a solid control in every state',
    theSolidControlReachesTheThresholdInEveryState,
  );
  it('paints no hue anchor as text anywhere in the tree', noModulePaintsAHueAnchorAsText);
}

describe('text-grade colour bridge', textContrastCases);
