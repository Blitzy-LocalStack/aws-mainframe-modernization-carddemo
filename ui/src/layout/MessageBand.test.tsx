/**
 * @file Proves the message band is sized to the DISPLAY width of the mapset it stands in,
 * and that its channel and severity resolve to the measured BMS field they stand for.
 *
 * Purpose
 * -------
 * Two contracts are pinned here, in two `describe` groups. The first is the band's WIDTH,
 * described immediately below. The second is the pair of properties a mapset declaring two
 * message lines depends on: that each line is separately addressable, and that a line's
 * severity resolves to the colour token the BMS bridge maps its declared `COLOR=` to. They
 * share a file because they are one component's rendering contract, and separating them
 * would put two suites over one module with no boundary between what they own.
 *
 * The baseline message contract has two halves. `CCARD-ERROR-MSG` and
 * `CCARD-RETURN-MSG` (`app/cpy/CVCRD01Y.cpy` L28-L29) are `PIC X(75)`, which bounds
 * what a message crossing the shared work area may CARRY; the `ERRMSGI`/`ERRMSGO`
 * display field is what a message is RENDERED in, and it is 78 characters on 19 of
 * the 21 mapsets and 80 on `COCRDSL` and `COCRDUP`. These cases assert the second
 * half, because it is the half that decides whether a message is clipped: 14 online
 * programs compose straight into an 80-byte `WS-MESSAGE` buffer and move it to the
 * map without passing through the work area, so a band sized to 75 would clip
 * characters the terminal itself rendered.
 *
 * Refactoring Rationale: the assertion reads the inline `maxInlineSize` off the band
 * element rather than a computed style. jsdom does not resolve `ch` against a font,
 * so a computed width would come back as the literal string or as an empty value
 * depending on the property; the inline declaration is the contract this component
 * actually writes, and it is the one thing a regression would change.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { render, renderHook, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { BMS_COLOR_TOKENS, BMS_TEXT_COLOR_TOKENS } from '../theme/tokens';
import {
  INFORMATION_BAND_TEST_ID,
  MESSAGE_BAND_CONTENT_WIDTH,
  MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH,
  MESSAGE_BAND_TEST_ID,
  MESSAGE_BAND_TEST_IDS,
  MessageBand,
} from './MessageBand';
import { theme } from 'antd';

/** A message short enough that no case here depends on truncation behaviour. */
const SHORT_MESSAGE = 'Wrong Password. Try again ...';

/**
 * Returns the character width the rendered band's inline style caps it at.
 * @returns {string} The `max-inline-size` declaration written on the band element.
 */
function bandMaxInlineSize(): string {
  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  return band.style.maxInlineSize;
}

/**
 * The band is sized to the 78-character default when no mapset is named.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function default78(): void {
  render(<MessageBand message={SHORT_MESSAGE} />);

  expect(MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH).toBe(78);
  expect(bandMaxInlineSize()).toBe('78ch');
}

/**
 * A mapset whose display field is 78 characters is sized to 78.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function standard78(): void {
  render(<MessageBand mapset="COTRN00" message={SHORT_MESSAGE} />);

  expect(bandMaxInlineSize()).toBe('78ch');
}

/**
 * `COCRDSL` declares an 80-character display field and is sized to 80.
 *
 * Assumptions: both 80-character mapsets are asserted rather than one standing in for the other.
 * They are an exhaustive two-entry exception with no rule behind it -- COCRDSL and COCRDUP simply
 * declare a wider field -- so a table that lost one entry would still satisfy a single-case
 * assertion.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function cardDetail80(): void {
  render(<MessageBand mapset="COCRDSL" message={SHORT_MESSAGE} />);

  expect(bandMaxInlineSize()).toBe('80ch');
}

/**
 * `COCRDUP` declares an 80-character display field and is sized to 80.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function cardUpdate80(): void {
  render(<MessageBand mapset="COCRDUP" message={SHORT_MESSAGE} />);

  expect(bandMaxInlineSize()).toBe('80ch');
}

/**
 * The band is never sized to the 75-character work area the message travels through.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function notWorkArea(): void {
  render(<MessageBand mapset="COCRDSL" message={SHORT_MESSAGE} />);

  expect(MESSAGE_BAND_CONTENT_WIDTH).toBe(75);
  expect(bandMaxInlineSize()).not.toBe(`${MESSAGE_BAND_CONTENT_WIDTH}ch`);
}

/**
 * The reserved empty band carries the same width as the populated one.
 *
 * Assumptions: the empty band is asserted too, because it is the branch that reserves row 23 and it
 * renders a different element. A width applied only to the populated branch would leave the reserved
 * space a different size from the message that replaces it, which is the layout shift the band exists
 * to prevent.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function emptyBand(): void {
  render(<MessageBand mapset="COCRDUP" />);

  expect(bandMaxInlineSize()).toBe('80ch');
}

/**
 * The two message LINES a mapset can declare answer to two different identifiers.
 *
 * ⚠️ Refactoring Rationale: both lines used to render the same `data-testid`, so a screen composing
 * the shell's row-23 line and its own row-20 line put two elements under one name. Nothing about the
 * rendering was wrong -- `app/bms/COACTUP.bms` declares INFOMSG on row 22 (L480) and ERRMSG on row
 * 23 (L489), so two lines is what the mapset asks for -- but a singular query threw on the pair, so
 * no test could name one line, and "exactly one row-23 band" was not an assertable property.
 *
 * Assumptions: the default is the MESSAGE line, so every existing caller keeps the identifier it
 * had; only a caller that opts in with `line="information"` moves. That is what makes this a naming
 * change rather than a behavioural one for the twenty-one screens that render a single band.
 * @returns {void} Nothing; the case asserts on the rendered bands.
 */
function namesTheTwoLinesApart(): void {
  render(
    <>
      <MessageBand mapset="COACTUP" message={SHORT_MESSAGE} />
      <MessageBand line="information" mapset="COACTUP" message={SHORT_MESSAGE} />
    </>,
  );

  expect(screen.getAllByTestId(MESSAGE_BAND_TEST_ID)).toHaveLength(1);
  expect(screen.getAllByTestId(INFORMATION_BAND_TEST_ID)).toHaveLength(1);
}

/**
 * An information line keeps its own identifier in the RESERVED state as well as the populated one.
 *
 * Assumptions: this is the branch that would silently break the pairing. The component returns a
 * different element when it has no message, so an identifier resolved separately in the two returns
 * could name the information line while populated and the message line while empty -- and the empty
 * state is exactly when nothing on screen would reveal it.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function keepsItsLineWhileReserved(): void {
  render(<MessageBand line="information" mapset="COACTUP" />);

  expect(screen.getByTestId(INFORMATION_BAND_TEST_ID)).toBeEmptyDOMElement();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_ID)).not.toBeInTheDocument();
}

/**
 * Groups the display-width cases.
 *
 * Assumptions: every case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback. `jsdoc/require-jsdoc` is configured with `publicOnly: false`, so it selects a function
 * expression in every position -- and a JSDoc block written above an inline callback is moved by
 * Prettier onto the preceding string literal, which detaches it from the function it documents.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function messageBandDisplayWidth(): void {
  it('renders at the 78-character width when no mapset is named', default78);
  it('renders at 78 characters for a standard mapset', standard78);
  it('renders at 80 characters on COCRDSL', cardDetail80);
  it('renders at 80 characters on COCRDUP', cardUpdate80);
  it('never sizes the band to the 75-character work area', notWorkArea);
  it('applies the same width to the reserved empty band', emptyBand);
  it('names the information line and the message line apart', namesTheTwoLinesApart);
  it('keeps the information identifier while the line is reserved', keepsItsLineWhileReserved);
}

describe('message band display width', messageBandDisplayWidth);

/*
 * WHY : Refactoring Rationale: the cases below were added with the second CHANNEL and the fourth
 *       SEVERITY, and neither existed while every screen painted one band. The account-view mapset
 *       declares two independent message lines -- `INFOMSG` at `POS=(22,23)` `COLOR=NEUTRAL` and
 *       `ERRMSG` at `POS=(23,1)` `COLOR=RED` -- and a screen rendering both produced two elements
 *       carrying ONE `data-testid`, so a case asking for "the band" got whichever came first and no
 *       case could address the other. The row-22 line was additionally rendered with the
 *       informational severity, which the token bridge resolves to `colorInfo`; that is the
 *       TURQUOISE hue, not the NEUTRAL one the field declares, and it is precisely the substitution
 *       the bridge's G3 note exists to prevent.
 */

/**
 * One of the design-system token names the BMS colour bridge maps a measured `COLOR=` value to.
 *
 * Assumptions: the alias is the union of the bridge's OWN eight values rather than the whole token
 * vocabulary, and the narrowing is load-bearing rather than tidiness. The theme's reference table is
 * typed by each token's declared VALUE type, so indexing it with the full token name admits every
 * numeric, boolean and component-token member and the read stops being a `string`. All eight bridge
 * entries name a colour, so this union reads as text by construction.
 */
type BmsColourToken =
  | (typeof BMS_COLOR_TOKENS)[keyof typeof BMS_COLOR_TOKENS]
  /*
   * WHY : Assumptions: the union admits the TEXT-grade names as well as the operand names, because the band
   *       resolves every severity at the text grade -- five of the eight semantic ramps publish no shade
   *       reaching the 4.5:1 AA minimum for normal text. Both families are colour tokens, so the narrowing
   *       this alias exists for still holds; admitting only the operand names left the cases unable to name
   *       the value the component actually paints.
   */
  | (typeof BMS_TEXT_COLOR_TOKENS)[keyof typeof BMS_TEXT_COLOR_TOKENS];

/**
 * Resolves the CSS-variable reference form of one design-system token name.
 *
 * Assumptions: the reference is read from the LIVE theme through the same hook the component reads,
 * rather than composed here from the token name. antd's variable-naming scheme is the component's
 * dependency and not this suite's, so deriving `--ant-color-text-secondary` from `colorTextSecondary`
 * in a test would put a second description of that scheme in the tree and would agree with the
 * component only for as long as both happened to spell it the same way.
 *
 * Assumptions: only the reference is resolvable, never the colour. jsdom has no layout engine and no
 * stylesheet cascade for these variables, so the assertions below compare declarations.
 * @param {BmsColourToken} token - A design-system token name, as the BMS colour bridge holds it.
 * @returns {string} The `var(--…)` declaration the component writes for that token.
 */
function tokenReference(token: BmsColourToken): string {
  const { result, unmount } = renderHook(
    /**
     * Reads the theme's variable-reference table.
     * @returns {object} The reference form of every token name, keyed by token name.
     */
    () => theme.useToken().cssVar,
  );

  const reference = result.current[token];

  // Assumptions: the probe is unmounted before the case renders the band, so the queries below run
  //   against one container. Testing Library appends every render to the document body, and a probe
  //   left mounted would put a second tree in front of `screen`.
  unmount();

  return reference;
}

/**
 * Reads the colour declaration the band wrote on its message text.
 * @param {string} message - The rendered message, used to locate the text element.
 * @returns {string} The `color` declaration on the message text element.
 */
function messageColour(message: string): string {
  return screen.getByText(message).style.color;
}

/**
 * A band with no channel carries the historical handle, so existing suites keep addressing it.
 *
 * Assumptions: the alias is asserted as WELL as the default, because four suites import
 * `MESSAGE_BAND_TEST_ID` by that name. A channel table that renamed the error entry would leave
 * those suites failing as "missing element" rather than as the contract change it would be.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function defaultChannelIsTheErrorLine(): void {
  render(<MessageBand mapset="COMEN01" message={SHORT_MESSAGE} />);

  expect(MESSAGE_BAND_TEST_ID).toBe(MESSAGE_BAND_TEST_IDS.error);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_IDS.error)).toBeInTheDocument();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_IDS.information)).not.toBeInTheDocument();
}

/**
 * A band on the information channel carries its own handle and not the error one.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function informationChannelHasItsOwnHandle(): void {
  render(<MessageBand channel="information" mapset="COACTVW" message={SHORT_MESSAGE} />);

  expect(screen.getByTestId(MESSAGE_BAND_TEST_IDS.information)).toBeInTheDocument();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_IDS.error)).not.toBeInTheDocument();
}

/**
 * The two bands one mapset declares are separately addressable when both are rendered.
 *
 * Assumptions: the elements are compared for IDENTITY rather than merely both being found. Two
 * handles that resolved to the same element would satisfy a presence-only assertion while leaving
 * the defect in place, and a duplicated handle makes Testing Library's own single-element query
 * throw, which reads as a broken test rather than as a broken contract.
 * @returns {void} Nothing; the case asserts on the rendered bands.
 */
function bothChannelsAreSeparatelyAddressable(): void {
  render(
    <>
      <MessageBand
        channel="information"
        mapset="COACTVW"
        message="Enter or update id of account to display"
        severity="neutral"
      />
      <MessageBand mapset="COACTVW" message={SHORT_MESSAGE} />
    </>,
  );

  const information = screen.getByTestId(MESSAGE_BAND_TEST_IDS.information);
  const error = screen.getByTestId(MESSAGE_BAND_TEST_IDS.error);

  expect(information).not.toBe(error);
  expect(information).toHaveTextContent('Enter or update id of account to display');
  expect(error).toHaveTextContent(SHORT_MESSAGE);
}

/**
 * The reserved empty band carries the channel handle too, on either channel.
 *
 * Assumptions: the empty state is asserted per channel, because that is the branch that reserves the
 * row and it renders a different element. A screen painting two lines reserves two rows, and a handle
 * present only on the populated branch would make the reserved half of that pair unassertable.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function theEmptyBandCarriesItsChannelHandle(): void {
  render(<MessageBand channel="information" mapset="COACTVW" />);

  const band = screen.getByTestId(MESSAGE_BAND_TEST_IDS.information);

  expect(band).toBeInTheDocument();
  expect(band).toHaveAttribute('aria-hidden', 'true');
}

/**
 * The neutral severity paints the token the BMS bridge maps `COLOR=NEUTRAL` to.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function neutralPaintsTheNeutralToken(): void {
  render(<MessageBand mapset="COACTVW" message={SHORT_MESSAGE} severity="neutral" />);

  expect(BMS_COLOR_TOKENS.NEUTRAL).toBe('colorTextSecondary');
  expect(messageColour(SHORT_MESSAGE)).toBe(tokenReference(BMS_COLOR_TOKENS.NEUTRAL));
}

/**
 * The neutral severity does NOT paint the informational token.
 *
 * Assumptions: the negative half is a case of its own, because the informational severity is what
 * the account-view screen used for its row-22 line and the two are one character apart at the call
 * site. Asserting only the positive half would pass for a component that mapped both severities onto
 * the same token.
 * @returns {void} Nothing; the case asserts on the rendered bands.
 */
function neutralIsNotTheInformationalToken(): void {
  const informational = tokenReference(BMS_COLOR_TOKENS.TURQUOISE);

  render(<MessageBand mapset="COACTVW" message={SHORT_MESSAGE} severity="neutral" />);

  expect(informational).not.toBe(tokenReference(BMS_COLOR_TOKENS.NEUTRAL));
  expect(messageColour(SHORT_MESSAGE)).not.toBe(informational);
}

/**
 * The informational severity keeps the informational ROLE, and it keeps it distinctly.
 *
 * ⚠️ Refactoring Rationale: the assertion names `BMS_TEXT_COLOR_TOKENS.TURQUOISE` where it named
 * `BMS_COLOR_TOKENS.TURQUOISE`, and the change is a measured contrast one rather than a preference. This
 * case was written to prove that ADDING the neutral severity left the informational one alone, and that
 * property is what it still asserts; the token it resolves to moved because the informational ramp's
 * mid-anchor measures 2.21:1 as normal text where WCAG AA asks 4.5:1, so the band resolves every severity
 * at the text grade `BMS_TEXT_CONTRAST_AUDIT` records. Asserting the pre-contrast token would pin the
 * failing value.
 *
 * Assumptions: the DISTINCTNESS from the neutral severity is asserted alongside the identity, because that
 * is the half this case exists for -- a component that mapped both severities onto one token would satisfy
 * an identity assertion against either of them.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function informationalStillPaintsTheInformationalToken(): void {
  render(<MessageBand mapset="COTRN00" message={SHORT_MESSAGE} severity="info" />);

  expect(messageColour(SHORT_MESSAGE)).toBe(tokenReference(BMS_TEXT_COLOR_TOKENS.TURQUOISE));
  expect(messageColour(SHORT_MESSAGE)).not.toBe(tokenReference(BMS_TEXT_COLOR_TOKENS.NEUTRAL));
}

/**
 * A neutral band announces politely and renders the informational alert variant.
 *
 * Assumptions: the variant and the role are asserted together because the neutral severity is the
 * one value that is NOT an alert variant the design system accepts, so it is mapped to the closest
 * chrome the system offers while the text takes the measured token. A component that passed
 * `"neutral"` straight through would emit a console warning and render unstyled chrome, which no
 * colour assertion would catch.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function aNeutralBandAnnouncesPolitely(): void {
  render(<MessageBand mapset="COACTVW" message={SHORT_MESSAGE} severity="neutral" />);

  const alert = screen.getByRole('status');

  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(alert.className).toContain('ant-alert-info');
}

/**
 * An error band still interrupts, so the added severity changed nothing on the row-23 line.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function anErrorBandStillInterrupts(): void {
  render(<MessageBand mapset="COACTVW" message={SHORT_MESSAGE} />);

  const alert = screen.getByRole('alert');

  expect(screen.queryByRole('status')).not.toBeInTheDocument();
  expect(alert.className).toContain('ant-alert-error');
}

/**
 * Groups the channel and severity cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function messageBandChannelsAndSeverities(): void {
  it('carries the historical handle when no channel is named', defaultChannelIsTheErrorLine);
  it('carries a distinct handle on the information channel', informationChannelHasItsOwnHandle);
  it(
    'keeps both of a mapset\u2019s bands separately addressable',
    bothChannelsAreSeparatelyAddressable,
  );
  it('carries the channel handle on the reserved empty band', theEmptyBandCarriesItsChannelHandle);
  it('paints the neutral severity in the measured NEUTRAL token', neutralPaintsTheNeutralToken);
  it('never paints the neutral severity in the TURQUOISE token', neutralIsNotTheInformationalToken);
  it(
    'still paints the informational severity in the TURQUOISE token',
    informationalStillPaintsTheInformationalToken,
  );
  it(
    'announces a neutral band politely, in the informational variant',
    aNeutralBandAnnouncesPolitely,
  );
  it('still announces an error band assertively', anErrorBandStillInterrupts);
}

describe('message band channels and severities', messageBandChannelsAndSeverities);
