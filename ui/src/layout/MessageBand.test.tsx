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
  defaultMessageBandSeverity,
  messageBandChannelForLine,
  messageBandSeverityForApiSeverity,
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

/*
 * WHY : Refactoring Rationale: the third group covers what an accessibility and rendering review found
 *       the band doing to a LISTENER and to a reader of a clipped sentence, which the two groups above
 *       do not touch: the severity icon announced under its machine name inside the live region and
 *       ahead of the message; the service severity never reaching the band at all, so every fault
 *       rendered as a rejection; and an over-length sentence whose tail no attribute on the element
 *       offered back. They are one group because they are one element's announcement contract.
 */

/** A sentence long enough to stand for the 119-character message a review measured as clipped. */
const CLIPPED_MESSAGE =
  'Card data is temporarily unavailable. Use the request correlation identifier from the response when reporting this.';

/** Laid-out inline size of the clipped text element, standing for the measured 566 pixels. */
const CLIPPED_CLIENT_WIDTH = 566;

/** Content width of the same element, standing for the measured 747 pixels. */
const CLIPPED_SCROLL_WIDTH = 747;

/**
 * Runs one case with the message text element reporting geometry that is genuinely clipped.
 *
 * Purpose: the band decides whether to offer a reveal by MEASURING the rendered element — scroll width
 * against client width — and jsdom performs no layout, so both are zero and every message reads as
 * unclipped. A case about the clipped state therefore has to supply the geometry the browser would.
 *
 * Assumptions: the two figures are the ones a review measured on a real screen (`scrollWidth 747 >
 * clientWidth 566`) rather than arbitrary numbers, so the case stands for an observed rendering.
 *
 * Assumptions: the properties are defined on the HTML element prototype and REMOVED afterwards, not
 * left in place. jsdom implements both on `Element.prototype`, so defining them one level down shadows
 * the implementation for the duration and deleting restores it exactly — assigning the original
 * descriptor back would be wrong, because there is no own descriptor at this level to restore.
 * @param {() => void} run - The case body, executed while the clipped geometry is in force.
 * @returns {void} Nothing; the geometry is removed before returning, including on failure.
 */
function withClippedGeometry(run: () => void): void {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    /**
     * Reports the laid-out inline size of the element.
     * @returns {number} The measured client width of a clipped message.
     */
    get(): number {
      return CLIPPED_CLIENT_WIDTH;
    },
  });
  Object.defineProperty(HTMLElement.prototype, 'scrollWidth', {
    configurable: true,
    /**
     * Reports the content width of the element.
     * @returns {number} The measured scroll width of a clipped message.
     */
    get(): number {
      return CLIPPED_SCROLL_WIDTH;
    },
  });

  try {
    run();
  } finally {
    // Assumptions: `delete` rather than a re-definition, so the jsdom implementation one prototype up
    //   is what answers again — a stub left behind would make every later case in this file measure
    //   every message as clipped.
    delete (HTMLElement.prototype as { clientWidth?: number }).clientWidth;
    delete (HTMLElement.prototype as { scrollWidth?: number }).scrollWidth;
  }
}

/**
 * The severity icon is drawn but hidden, so the live region announces the sentence and nothing else.
 *
 * Purpose: an accessibility review found the band's icon exposed under its own machine name — `image
 * "close-circle"` on a rejection and `image "info-circle"` on an advisory line — announced inside the
 * live region and BEFORE the message, so a listener heard the name of a drawing before hearing what had
 * happened.
 *
 * Assumptions: the case asserts the glyph is STILL RENDERED as well as hidden, because removing it is
 * the other way to silence the announcement and it is the wrong way: the sentence is painted in the
 * base text grade for contrast reasons, so the icon is the second visual channel severity travels on.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function hidesTheSeverityIconFromAssistiveTechnology(): void {
  render(<MessageBand mapset="COMEN01" message={SHORT_MESSAGE} />);

  const alert = screen.getByRole('alert');
  const icon = alert.querySelector('.anticon');

  expect(icon, 'severity must still travel on a second visual channel').not.toBeNull();
  expect(icon?.getAttribute('aria-hidden')).toBe('true');
  expect(
    screen.queryByRole('img'),
    'no decorative glyph may be announced inside the live region',
  ).not.toBeInTheDocument();
}

/**
 * The band exposes exactly one live region, and it is the alert rather than the wrapper.
 *
 * Purpose: a review observed that the band element itself carries neither `role` nor `aria-live`, so
 * announcement depends entirely on the nested alert. That is deliberate and this case pins it as such:
 * a live region on the wrapper would nest a second one around the first and announce one message twice.
 * The property worth guarding is the COUNT, so it is asserted directly.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function keepsOneLiveRegionPerBand(): void {
  render(<MessageBand mapset="COMEN01" message={SHORT_MESSAGE} />);

  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  const alert = screen.getByRole('alert');

  expect(band.getAttribute('role'), 'the wrapper must not announce').toBeNull();
  expect(band.getAttribute('aria-live'), 'the wrapper must not be a live region').toBeNull();
  expect(band).toContainElement(alert);
  expect(document.querySelectorAll('[aria-live]')).toHaveLength(0);
}

/**
 * Every service severity maps onto exactly one band appearance.
 *
 * Purpose: a review found the `severity` member of the problem document completely inert — a WARNING and
 * a CRITICAL fault rendered byte-identically, and no fault in any mode ever produced anything but the
 * rejection appearance, because nothing joined the two vocabularies.
 *
 * Assumptions: WARNING and CRITICAL are asserted to be THE SAME, which is the observation the review
 * reported as the defect. It is faithful: the row-23 field is `COLOR=RED` on 21 of 21 mapsets, so the
 * reference has one appearance for an unsuccessful turn and a fourth would be an invention. What the
 * case pins as fixed is the other half — that the quiet tiers no longer render as rejections.
 * @returns {void} Nothing; the case asserts on the helper's answers.
 */
function translatesEveryServiceSeverity(): void {
  expect(messageBandSeverityForApiSeverity('LOG')).toBe('neutral');
  expect(messageBandSeverityForApiSeverity('INFO')).toBe('info');
  expect(messageBandSeverityForApiSeverity('WARNING')).toBe('error');
  expect(messageBandSeverityForApiSeverity('CRITICAL')).toBe('error');

  expect(
    messageBandSeverityForApiSeverity('LOG'),
    'a recordable condition must not render as a rejection',
  ).not.toBe(messageBandSeverityForApiSeverity('CRITICAL'));
}

/**
 * A translated severity drives the band's own rendering, not merely the helper's answer.
 *
 * Purpose: prove the two halves compose. A mapping helper nothing renders through would leave the
 * measured defect exactly where it was, so the case passes a translated value into the band and asserts
 * the announcement and the colour that follow from it.
 * @returns {void} Nothing; the case asserts on the rendered bands.
 */
function rendersATranslatedSeverity(): void {
  const neutral = tokenReference(BMS_TEXT_COLOR_TOKENS.NEUTRAL);

  const { unmount } = render(
    <MessageBand
      mapset="COMEN01"
      message={SHORT_MESSAGE}
      severity={messageBandSeverityForApiSeverity('LOG')}
    />,
  );

  expect(screen.getByRole('status'), 'a recordable condition must announce politely').toBeVisible();
  expect(messageColour(SHORT_MESSAGE)).toBe(neutral);
  unmount();

  render(
    <MessageBand
      mapset="COMEN01"
      message={SHORT_MESSAGE}
      severity={messageBandSeverityForApiSeverity('CRITICAL')}
    />,
  );

  expect(screen.getByRole('alert'), 'a critical fault must interrupt').toBeVisible();
}

/**
 * The advisory line defaults to the colour its own field declares, not to the outcome line's.
 *
 * Purpose: the band had ONE default severity for both channels — the measured `COLOR=RED` of row 23 — so
 * a screen delegating its row-22 line without restating a severity painted standing guidance in the
 * rejection colour and announced it assertively. Row 22 is `COLOR=NEUTRAL` on every mapset that
 * declares it.
 *
 * Assumptions: the outcome line's default is asserted in the same case, because the fix would be a
 * regression if it moved that one too: `ERRMSG` is red whether it carries a refusal or the sign-off
 * acknowledgement, so the row-23 default must not follow the row-22 correction.
 * @returns {void} Nothing; the case asserts on the rendered bands.
 */
function defaultsEachChannelToItsOwnDeclaredColour(): void {
  const neutral = tokenReference(BMS_TEXT_COLOR_TOKENS.NEUTRAL);
  const red = tokenReference(BMS_TEXT_COLOR_TOKENS.RED);

  const { unmount } = render(
    <MessageBand channel="information" mapset="COACTVW" message={SHORT_MESSAGE} />,
  );

  expect(defaultMessageBandSeverity('information')).toBe('neutral');
  expect(messageColour(SHORT_MESSAGE)).toBe(neutral);
  expect(screen.getByRole('status')).toBeVisible();
  unmount();

  render(<MessageBand mapset="COACTVW" message={SHORT_MESSAGE} />);

  expect(defaultMessageBandSeverity('error')).toBe('error');
  expect(messageColour(SHORT_MESSAGE)).toBe(red);
  expect(screen.getByRole('alert')).toBeVisible();
}

/**
 * An explicitly named channel outranks the older `line` vocabulary.
 *
 * Purpose: two prop names reached this component for one concept, and the resolution let `line` win
 * over an explicit `channel` while the note beside it claimed the opposite. A caller passing both — one
 * screen does — was therefore routed by whichever name the code happened to read rather than by the
 * documented rule.
 *
 * Assumptions: both orders are asserted, because a resolution that simply preferred `information`
 * would satisfy one direction and reproduce the ambiguity in the other.
 * @returns {void} Nothing; the case asserts on the rendered bands.
 */
function takesTheChannelOverTheLegacyLine(): void {
  const { unmount } = render(
    <MessageBand channel="information" line="message" mapset="COACTVW" message={SHORT_MESSAGE} />,
  );

  expect(screen.getByTestId(MESSAGE_BAND_TEST_IDS.information)).toBeInTheDocument();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_IDS.error)).not.toBeInTheDocument();
  unmount();

  render(
    <MessageBand channel="error" line="information" mapset="COACTVW" message={SHORT_MESSAGE} />,
  );

  expect(screen.getByTestId(MESSAGE_BAND_TEST_IDS.error)).toBeInTheDocument();
  expect(screen.queryByTestId(MESSAGE_BAND_TEST_IDS.information)).not.toBeInTheDocument();

  // Assumptions: `line` still answers on its own, so the callers holding only the older name keep
  //   reaching the band they always did.
  expect(messageBandChannelForLine('information')).toBe('information');
  expect(messageBandChannelForLine('message')).toBe('error');
  expect(messageBandChannelForLine(undefined)).toBe('error');
}

/**
 * A clipped sentence is recoverable by pointer, by keyboard and in full from the DOM.
 *
 * Purpose: a review measured a 119-character message clipped at `scrollWidth 747 > clientWidth 566`,
 * read the element, found no `title`, and reported the tail as unrecoverable by hover or by assistive
 * technology. Three mechanisms answer the three audiences and the case asserts all three: the native
 * attribute a pointer reveals and an inspection finds, the tab stop that lets focus summon the
 * design-system reveal, and the untouched full string that assistive technology reads.
 *
 * Assumptions: the absence of `aria-label` is asserted deliberately rather than left unstated. It is
 * the other attribute the review named, and it is prohibited on this element: the text is a `span` with
 * no role, which maps to `generic`, where `aria-label` is ignored by several screen readers and
 * reported by axe.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function offersTheClippedSentenceToEveryAudience(): void {
  withClippedGeometry(
    /**
     * Renders a clipped band and inspects the three recovery mechanisms.
     * @returns {void} Nothing; assertions raise on failure.
     */
    () => {
      render(<MessageBand mapset="COMEN01" message={CLIPPED_MESSAGE} />);

      const text = screen.getByText(CLIPPED_MESSAGE);

      expect(text.getAttribute('title'), 'a pointer must be able to recover the tail').toBe(
        CLIPPED_MESSAGE,
      );
      expect(text.tabIndex, 'a keyboard must be able to reach the reveal').toBe(0);
      expect(text.textContent, 'the sentence is never sliced in the data').toBe(CLIPPED_MESSAGE);
      expect(
        text.getAttribute('aria-label'),
        'aria-label is prohibited on a generic element',
      ).toBeNull();
    },
  );
}

/**
 * A sentence that fits adds neither a tooltip attribute nor a tab stop.
 *
 * Purpose: the recovery affordances are offered because a sentence is clipped, so a band whose message
 * fits must add none of them — a `title` duplicating fully visible text is noise on every hover on every
 * screen, and a tab stop that reveals nothing is a defect rather than a mitigation.
 *
 * Assumptions: no geometry is supplied, so the element measures as unclipped exactly as an unclipped
 * element does — jsdom reports zero for both widths and the band reads a zero client width as "not
 * measurable" rather than as truncation.
 * @returns {void} Nothing; the case asserts on the rendered band.
 */
function addsNoRecoveryWhileTheSentenceFits(): void {
  render(<MessageBand mapset="COMEN01" message={SHORT_MESSAGE} />);

  const text = screen.getByText(SHORT_MESSAGE);

  expect(text.getAttribute('title')).toBeNull();
  expect(text.getAttribute('tabindex')).toBeNull();
}

/**
 * Groups the announcement, severity-translation and truncation-recovery cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function messageBandAnnouncementCases(): void {
  it(
    'hides the severity icon from assistive technology',
    hidesTheSeverityIconFromAssistiveTechnology,
  );
  it('keeps one live region per band', keepsOneLiveRegionPerBand);
  it('translates every service severity', translatesEveryServiceSeverity);
  it('renders a translated severity', rendersATranslatedSeverity);
  it('defaults each channel to its own declared colour', defaultsEachChannelToItsOwnDeclaredColour);
  it('takes the channel over the legacy line', takesTheChannelOverTheLegacyLine);
  it('offers a clipped sentence to every audience', offersTheClippedSentenceToEveryAudience);
  it('adds no recovery while the sentence fits', addsNoRecoveryWhileTheSentenceFits);
}

describe('message band announcement and truncation recovery', messageBandAnnouncementCases);
