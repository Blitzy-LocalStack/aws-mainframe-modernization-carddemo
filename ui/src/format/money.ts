/**
 * @file The baseline's monetary edit mask, applied to wire money text without arithmetic.
 *
 * Purpose
 * -------
 * Render an amount the way the reference renders it: `+ZZZ,ZZZ,ZZZ.99`, a fifteen-character picture
 * that emits a sign, suppresses leading zeroes to blanks, groups thousands with commas and always
 * shows two decimal places. Screens receive money as text and previously painted that text raw, so
 * `-1234.56` reached the operator where the terminal showed `-      1,234.56`.
 *
 * Where the mask comes from
 * -------------------------
 * Assumptions: the mask is a MEASURED source value, not a convention adopted here. It reaches the
 * account-view screen through the mapset rather than through the program: `app/bms/COACTVW.bms`
 * declares `PICOUT='+ZZZ,ZZZ,ZZZ.99'` on exactly five fields (L120, L141, L162, L174, L195), and the
 * generated symbolic map declares those same five as `PIC +ZZZ,ZZZ,ZZZ.99` -- `ACRDLIMO` at
 * `app/cpy-bms/COACTVW.CPY` L302, `ACSHLIMO` L314, `ACURBALO` L326, `ACRCYCRO` L332 and `ACRCYDBO`
 * L344. The program therefore moves the raw record field in and the mask is applied for it, which is
 * exactly what `app/cbl/COACTVWC.cbl` L475 does -- `MOVE ACCT-CURR-BAL TO ACURBALO OF CACTVWAO` --
 * and it is why that program's own `EDIT-FIELD-9-2 PIC +ZZZ,ZZZ,ZZZ.99` sits COMMENTED OUT at L69:
 * a program whose map edits for it needs no edit field of its own.
 *
 * Refactoring Rationale: the account-view screen previously stated that "the service already
 * applies" this mask. It does not and cannot. Money crosses the boundary through
 * `services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java`, which writes
 * `Money.toPlainString()`, so the wire carries `-1234.56` and never a grouped or signed rendering.
 * The mask had no implementation anywhere, in either layer, while the prose in the one place a
 * reviewer would look asserted that another layer owned it.
 *
 * Assumptions: the two account screens reach the same rendering by DIFFERENT baseline mechanisms, so
 * this module is shared rather than duplicated per screen. The update screen's mapset carries no
 * `PICOUT` at all -- `app/cpy-bms/COACTUP.CPY` declares `ACRDLIMO PIC X(15)` at L416 and `ACURBALO
 * PIC X(15)` at L464 -- and `app/cbl/COACTUPC.cbl` L371 declares `WS-EDIT-CURRENCY-9-2-F PIC
 * +ZZZ,ZZZ,ZZZ.99` and moves the edited result into that unedited field. One mask, applied by the map
 * on one screen and by the program on the other, which is why one implementation serves both.
 *
 * Why no arithmetic appears below
 * ------------------------------
 * Assumptions: every operation here is on CHARACTERS. Nothing calls `Number`, `parseFloat`,
 * `toFixed`, `Intl.NumberFormat` or an arithmetic operator on a monetary value, because the wire
 * representation is text precisely so that no client routes an exact decimal through an IEEE-754
 * double, and a formatter that parsed first would reintroduce the conversion the contract exists to
 * prevent. Grouping is inserted by slicing the digit string; zero suppression is a scan over it.
 */

/**
 * Count of integer digit positions the mask provides.
 *
 * Assumptions: nine, counted from the picture itself -- `ZZZ,ZZZ,ZZZ` is three groups of three. This
 * is NOT the width of the record field it renders, and the difference is load-bearing rather than
 * incidental; see {@link applyMoneyEditMask} for what happens to a value that needs more.
 */
export const MONEY_MASK_INTEGER_POSITIONS = 9;

/** Count of decimal digit positions the mask provides, always emitted. */
export const MONEY_MASK_DECIMAL_POSITIONS = 2;

/**
 * Total character width of a masked value that fits the picture.
 *
 * Assumptions: fifteen, which is the sign, the nine integer positions, the two group separators, the
 * decimal point and the two decimal positions -- `1 + 9 + 2 + 1 + 2`. It matches the `PIC X(15)`
 * width the update mapset declares for the same amounts, which is the independent confirmation that
 * the picture has been read correctly.
 */
export const MONEY_MASK_WIDTH =
  1 + MONEY_MASK_INTEGER_POSITIONS + 2 + 1 + MONEY_MASK_DECIMAL_POSITIONS;

/**
 * Shape the wire guarantees for a monetary value.
 *
 * Assumptions: an optional sign, at least one integer digit, and a decimal point with exactly two
 * digits. That is what `Money.toPlainString()` emits for a scale-two value, so this pattern
 * describes the contract rather than imposing a new one.
 */
const WIRE_MONEY_PATTERN = /^(-?)([0-9]+)\.([0-9]{2})$/u;

/** Digits per group between the mask's comma separators. */
const GROUP_SIZE = 3;

/**
 * Inserts the mask's comma separators into a run of integer digits.
 *
 * Assumptions: groups are measured from the RIGHT, which is what makes the separator positions in
 * `ZZZ,ZZZ,ZZZ` fall after the thousands and the millions regardless of how many digits the value
 * actually has. The slicing walks backwards for the same reason.
 * @param {string} digits - Integer digits with no sign, no separators and no leading zeroes.
 * @returns {string} The same digits with a comma before every third digit from the right.
 */
function group(digits: string): string {
  const groups: string[] = [];
  let end = digits.length;
  while (end > GROUP_SIZE) {
    groups.unshift(digits.slice(end - GROUP_SIZE, end));
    end -= GROUP_SIZE;
  }
  groups.unshift(digits.slice(0, end));
  return groups.join(',');
}

/**
 * Renders a wire money string through the baseline's `+ZZZ,ZZZ,ZZZ.99` edit mask.
 *
 * Assumptions: zero suppression blanks the leading zeroes AND any separator that falls inside the
 * suppressed run, which is what a `Z` picture character does. The implementation achieves that by
 * grouping only the SIGNIFICANT digits and then padding the result on the left with spaces, rather
 * than by grouping all nine positions and replacing characters afterwards -- the two produce the same
 * string, and the first cannot leave a stranded comma. An amount of zero therefore renders with a
 * blank integer field and a visible `.00`, because the decimal point and the `9` positions are not
 * suppressible.
 *
 * Trade-offs: a value whose integer part needs MORE than the mask's nine positions is rendered in
 * full, widening past {@link MONEY_MASK_WIDTH}, rather than being truncated. The reference truncates
 * it: the record field holds ten integer digits -- `ACCT-CURR-BAL PIC S9(10)V99` at
 * `app/cpy/CVACT01Y.cpy` L7, bounded server-side by `Money.MAX_MAGNITUDE` of `9999999999.99` -- and a
 * decimal-aligned `MOVE` into a nine-position edited field discards the high-order digit, so a
 * balance of `1234567890.12` would display as `+234,567,890.12` on the terminal. Reproducing that was
 * the alternative and is rejected: it would put a materially WRONG amount in front of an operator,
 * understating a balance by a billion with nothing on the screen to indicate it had happened, and a
 * display defect in the reference is not a behaviour worth transcribing faithfully. Widening keeps
 * every digit visible and keeps the column alignment the fixed-pitch font provides for the values
 * that do fit. Registered as `D-MONEY-MASK-NO-TRUNCATION`.
 *
 * Trade-offs: a value that does not match the wire contract is returned UNCHANGED rather than
 * rejected. Throwing was the alternative, and it is what the transport validators in `ui/src/api`
 * do for a contract violation, but those run at the boundary where refusing a response is the
 * correct outcome. This runs during render, on a value the boundary already accepted, so refusing
 * here would replace a correct-but-unformatted amount with a blank screen. Returning the text keeps
 * the AMOUNT truthful in the one case where its presentation cannot be, and the behaviour is pinned
 * by a test rather than left as an accident of control flow.
 * @param {string} wireAmount - Money as the service published it: optional sign, integer digits, a
 *   decimal point and exactly two decimal digits.
 * @returns {string} The amount rendered through the mask, or the input unchanged when it does not
 *   match the wire contract.
 */
export function applyMoneyEditMask(wireAmount: string): string {
  const parsed = WIRE_MONEY_PATTERN.exec(wireAmount);
  if (parsed === null) {
    return wireAmount;
  }

  /*
   * WHY : Assumptions: each group is destructured with an empty-string DEFAULT, and the defaults are
   *       unreachable rather than meaningful. All three groups are mandatory in the pattern, so a
   *       successful match populates every one of them; the defaults exist because
   *       `ui/tsconfig.json` types a capture group as possibly absent, and the alternative was a
   *       non-null assertion. A default is preferred to an assertion because it cannot throw if the
   *       pattern is ever edited to make a group optional -- it would render a wrong-but-stable
   *       string, which a test catches, where an assertion would fail at render time.
   */
  const [, sign = '', integerDigits = '', decimalDigits = ''] = parsed;

  /*
   * WHY : Assumptions: the sign position always emits a character, because the picture's leading `+`
   *       is a FIXED position rather than a conditional one -- it prints `+` for a non-negative value
   *       and `-` for a negative one. A negative zero cannot arrive: the pattern above captures the
   *       sign separately from the digits, and a scale-two zero is published as `0.00`.
   */
  const signCharacter = sign === '-' ? '-' : '+';

  /*
   * WHY : Assumptions: leading zeroes are removed before grouping rather than after, so that the
   *       comma positions are computed from the significant digits alone. An all-zero integer part
   *       reduces to the empty string, which is the blank integer field the `Z` positions produce.
   */
  const significantDigits = integerDigits.replace(/^0+/u, '');
  const grouped = significantDigits === '' ? '' : group(significantDigits);

  /*
   * WHY : Assumptions: the integer field is padded to the width the mask's own positions occupy --
   *       the nine digits plus the two separators -- so that decimal points line up down a column
   *       when the values are rendered in the fixed-pitch font the mapset's `JUSTIFY=(RIGHT)` fields
   *       relied on. A value wider than the field is left as it is rather than truncated, per the
   *       trade-off recorded above, so this pads and never cuts.
   */
  const integerFieldWidth = MONEY_MASK_INTEGER_POSITIONS + 2;
  const integerField =
    grouped.length >= integerFieldWidth ? grouped : grouped.padStart(integerFieldWidth, ' ');

  return `${signCharacter}${integerField}.${decimalDigits}`;
}

/**
 * Shape a masked or hand-decorated amount may arrive in from an editable control.
 *
 * Assumptions: this is deliberately WIDER than what {@link applyMoneyEditMask} emits, because the
 * value it has to recognise is whatever sits in a control the operator can also type into. It admits a
 * leading or trailing sign, blanks anywhere, group separators anywhere in the integer part, and one or
 * two decimal places -- which is the same latitude the reference's own `FUNCTION TEST-NUMVAL-C` allows
 * on the field this reverses.
 */
const DECORATED_MONEY_PATTERN = /^\s*([+-]?)([0-9,\s]*)(?:\.([0-9]{1,2}))?\s*([+-]?)\s*$/u;

/**
 * Reduces a masked or decorated amount to the plain decimal form the service parses.
 *
 * Assumptions: this exists because the mask is painted into an EDITABLE control on the account-update
 * screen -- all five amounts there are `ATTRB=(FSET,UNPROT)` at `app/bms/COACTUP.bms` L132, L170, L208,
 * L219 and L240 -- so the masked text is also the text that would be submitted. The service's parser
 * trims and strips a sign and group separators, but not the INTERIOR blanks the mask's zero suppression
 * produces, so a masked value sent verbatim would be refused as not-a-number. Reversing the decoration
 * here is what makes painting the mask into an editable field safe.
 *
 * Assumptions: the reference does the equivalent rather than something different. Its map field is
 * `PIC X(15)` free text and `1250-EDIT-SIGNED-9V2` reads it through an intrinsic that ignores the
 * decoration, so discarding blanks and separators on the way in is transcription and not invention.
 *
 * Trade-offs: a value that does not match the decorated shape is returned UNCHANGED rather than
 * rejected, which is the same discipline {@link applyMoneyEditMask} follows and for the same reason:
 * the service is the authority on what is acceptable, and it composes the operator-facing refusal. A
 * local rejection here would either duplicate that wording or invent a second one, and normalising a
 * value this cannot read would risk sending something the operator did not type.
 * @param {string} decorated - The control's text, masked, hand-typed, or anything else.
 * @returns {string} The plain decimal form -- optional minus sign, digits, a point and two decimals --
 *   or the input unchanged when it is not a decorated amount.
 */
export function stripMoneyEditMask(decorated: string): string {
  const parsed = DECORATED_MONEY_PATTERN.exec(decorated);
  if (parsed === null) {
    return decorated;
  }

  /*
   * WHY : Assumptions: the defaults are unreachable for the two mandatory groups and MEANINGFUL for the
   *       two optional ones -- a value with no decimal point supplies no third group, and a value with
   *       no sign supplies neither the first nor the fourth. Empty string is the correct reading in
   *       every one of those cases, which is why one form serves all four.
   */
  const [, leadingSign = '', integerRun = '', decimalDigits = '', trailingSign = ''] = parsed;

  /*
   * WHY : Assumptions: BOTH sign positions are read and a minus in either is honoured, because the
   *       reference's own parser accepts either position and an operator retyping an amount may put it
   *       where the terminal would have. Two minus signs cannot both be meant, so the test is for the
   *       presence of one rather than a count.
   */
  const negative = leadingSign === '-' || trailingSign === '-';
  const stripped = integerRun.replace(/[,\s]/gu, '');

  /*
   * WHY : Assumptions: an integer part carrying no digit reads as ZERO rather than as unreadable, and
   *       the mask itself is why this case has to exist: zero suppression blanks every integer position,
   *       so the mask's own rendering of zero is a sign, eleven blanks and `.00`. Refusing that would
   *       make the one value the mask cannot round-trip be the commonest one on the screen.
   * WHY : Assumptions: a hand-typed `.56` is therefore read as `0.56` as well, which is the same
   *       latitude the reference allows -- its map field is free text read through an intrinsic that
   *       accepts an absent integer part. Admitting it here keeps the browser and the reference in
   *       agreement rather than making this control stricter than the terminal it replaces.
   * WHY : Assumptions: an entirely empty input still falls through unchanged, because the pattern's
   *       groups are all optional and the empty string matches it. That is deliberate: blank is the
   *       never-supplied state the service reports with its own sentence, and turning it into `0.00`
   *       here would submit an amount where the operator supplied none.
   */
  if (stripped.length === 0 && decorated.trim().length === 0) {
    return decorated;
  }
  const integerDigits = stripped.length === 0 ? '0' : stripped;

  /*
   * WHY : Assumptions: the scale is normalised to two by PADDING only, never by cutting. The pattern
   *       admits at most two decimal places, so there is nothing to truncate; padding a single place to
   *       two is the same widening the reference performs when it moves a `9V9` value into a `9V99`
   *       field.
   */
  const scaled = decimalDigits.padEnd(MONEY_MASK_DECIMAL_POSITIONS, '0');
  const magnitude = `${integerDigits}.${scaled}`;
  return negative ? `-${magnitude}` : magnitude;
}
