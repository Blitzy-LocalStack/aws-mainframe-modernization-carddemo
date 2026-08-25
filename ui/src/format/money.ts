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

/*
 * WHY : Assumptions: the sign tokens are imported rather than declared here because they are design
 *       decisions, and every design decision in this tree lives in one module so that one set of
 *       contrast assertions can reach all of them. The import is one-directional -- the theme module
 *       imports nothing from this one -- so it introduces no cycle.
 */
import { MONEY_SIGN_TEXT_TOKENS } from '../theme/tokens';
import type { AntdTokenName } from '../theme/tokens';

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
 * One monetary edit mask, as a `PICTURE` clause the baseline actually declares.
 *
 * Purpose: the reference does NOT render money one way, and that is a measured fact rather than an
 * inconsistency to be tidied away. Three distinct pictures are declared across the tree and a fourth
 * screen family prints the record field unedited. A single global mask would therefore make the SPA
 * disagree with the terminal on two screen families in order to agree with itself, which Rule T9
 * forbids — structure may change, observable behaviour may not. This type is what lets one
 * implementation serve all of them.
 *
 * Assumptions: `grouped` and `zeroFilled` are the two independent behaviours a `PICTURE` character
 * selects, and they are carried as flags rather than by parsing {@link MoneyPicture.picture} at run
 * time. `Z` suppresses a leading zero to a blank; `9` prints it. A parser over the picture string
 * would be the more general answer and is rejected: there is a small fixed set of pictures, all of them
 * fixed by the baseline, and a parser would be a second thing to get right with no second caller to
 * serve. The picture string is still carried verbatim so a reader can check the flags against the
 * source.
 *
 * Assumptions: the two OPTIONAL members below are optional so that a picture declared before they
 * existed keeps its exact rendering by omitting them. They exist because two behaviours a `PICTURE`
 * selects are not expressible as a single flag per picture -- a picture may suppress only SOME of its
 * integer positions, and its sign position may be a `+` that always prints or a `-` that blanks for a
 * non-negative value.
 */
export interface MoneyPicture {
  /** The `PICTURE` clause verbatim, exactly as the mapset or program declares it. */
  readonly picture: string;
  /** Integer digit positions the picture provides. */
  readonly integerPositions: number;
  /** Decimal digit positions the picture provides, always emitted. */
  readonly decimalPositions: number;
  /** Whether the picture inserts comma group separators, as `ZZZ,ZZZ,ZZZ` does. */
  readonly grouped: boolean;
  /** Whether leading zeroes print, as `9` positions do, rather than suppressing to blanks. */
  readonly zeroFilled: boolean;
  /** Total character width of a value that fits the picture, separators and sign included. */
  readonly width: number;
  /**
   * How many of the leading integer positions suppress a zero to a blank; all of them when omitted.
   *
   * Assumptions: omitted means "every position suppresses", which is what the three `+ZZZ...` and
   * `+999...` pictures declared before this member existed, so omitting it reproduces their rendering
   * exactly. It is present because a picture may mix the two: `-zzzz9.99` has four `z` positions and a
   * final `9`, so an amount of zero prints `0` in the units position where an all-`Z` picture prints a
   * blank. Without this member that picture would render a settled balance as a field of spaces.
   *
   * Alternatives Considered: parsing {@link MoneyPicture.picture} to count the `Z` run, which needs no
   * member at all and cannot fall out of step with the string. Rejected on the reasoning already
   * recorded for `grouped` and `zeroFilled` -- there are four fixed pictures, and a parser would be a
   * second thing to get right with no second caller to serve. Declaring it beside them keeps one
   * mechanism rather than two.
   */
  readonly suppressedIntegerPositions?: number;
  /**
   * Whether the sign position prints `+` for a non-negative value; `true` when omitted.
   *
   * Assumptions: this distinguishes a `+` picture from a single-`-` picture, which are two different
   * COBOL insertion characters and not a stylistic choice. A leading `+` prints a character for every
   * value -- `+` when non-negative, `-` when negative. A single leading `-` prints `-` when negative and
   * a BLANK otherwise. Omitting the member gives the `+` behaviour, which is what all three pictures
   * declared before it existed genuinely declare, so their rendering is untouched.
   *
   * Assumptions: the blank is a real position and not an absence, so the rendered width is the same
   * either way -- which is what keeps a column of authorization amounts aligned whatever their signs.
   */
  readonly signsNonNegative?: boolean;
  /** Where the picture was measured, so the declaration can be checked against the baseline. */
  readonly source: string;
}

/**
 * Computes a picture's rendered width from its own positions.
 *
 * Assumptions: derived rather than declared per picture, because a width written by hand beside a
 * position count is a second place for the same fact to be wrong — and the one existing width in
 * this module was verified against an independent source precisely because that risk is real. The
 * separator count is `floor((integerPositions - 1) / 3)`, which is how many group boundaries fall
 * strictly inside the integer field.
 * @param {number} integerPositions - Integer digit positions the picture provides.
 * @param {number} decimalPositions - Decimal digit positions the picture provides.
 * @param {boolean} grouped - Whether the picture inserts comma separators.
 * @returns {number} The total rendered width, counting the sign, separators and decimal point.
 */
function pictureWidth(
  integerPositions: number,
  decimalPositions: number,
  grouped: boolean,
): number {
  const separators = grouped ? Math.floor((integerPositions - 1) / GROUP_SIZE) : 0;
  return 1 + integerPositions + separators + 1 + decimalPositions;
}

/**
 * Every monetary picture the baseline declares, keyed by the screen family that declares it.
 *
 * Assumptions: the authorization entry is measured from a PROGRAM and the other three from a mapset or
 * a program edit field, and that difference is why it was missed. The authorization mapsets carry no
 * `PICOUT` on any amount — `app/app-authorization-ims-db2-mq/bms/COPAU00.bms` declares `APPRAMT`,
 * `DECLAMT` and `PAMT001`–`PAMT005` with a length and a `COLOR=BLUE` operand and nothing else, and
 * `COPAU01.bms` does the same for `AUTHAMT`. Reading the mapsets alone therefore suggests those screens
 * print unedited text; reading the programs shows they do not, because the edit fields sit in
 * `WORKING-STORAGE` and the amounts are moved through them. The mask is real, it is just declared one
 * layer further in.
 *
 * Alternatives Considered: collapsing them all into the widest, which is what an audit reporting
 * "four mutually incompatible renderings" asks for. Rejected on the authority order this tree works
 * to: the pictures are measured source values and each belongs to a named screen family, so
 * unifying them would make several screen families disagree with the terminal to make the SPA agree
 * with itself. What the finding is RIGHT about is the part that has no source authority — that the
 * renderings were painted in one colour with no sign semantics, which
 * {@link renderMoney} fixes for all of them at once.
 */
export const MONEY_PICTURES = {
  /**
   * `+ZZZ,ZZZ,ZZZ.99` — the grouped, zero-suppressed picture the account screens use.
   *
   * Assumptions: this is the DEFAULT picture for the module, because it is the only one declared in
   * two independent places — five `PICOUT` operands in the account-view mapset and the account-update
   * program's own edit field — and because it is the picture every existing consumer of
   * {@link applyMoneyEditMask} already renders.
   */
  accountGrouped: {
    picture: '+ZZZ,ZZZ,ZZZ.99',
    integerPositions: MONEY_MASK_INTEGER_POSITIONS,
    decimalPositions: MONEY_MASK_DECIMAL_POSITIONS,
    grouped: true,
    zeroFilled: false,
    width: pictureWidth(MONEY_MASK_INTEGER_POSITIONS, MONEY_MASK_DECIMAL_POSITIONS, true),
    source:
      'app/bms/COACTVW.bms L120, L141, L162, L174, L195 (PICOUT); app/cbl/COACTUPC.cbl L371 (WS-EDIT-CURRENCY-9-2-F)',
  },
  /**
   * `+9999999999.99` — the ungrouped, zero-filled picture bill payment prints its balance in.
   *
   * Assumptions: ten integer positions rather than nine, which is what makes this picture able to
   * show a balance the account picture cannot. `ACCT-CURR-BAL` is `PIC S9(10)V99`, so bill payment is
   * the one screen whose declared picture matches the record's own magnitude.
   */
  billPayBalance: {
    picture: '+9999999999.99',
    integerPositions: 10,
    decimalPositions: MONEY_MASK_DECIMAL_POSITIONS,
    grouped: false,
    zeroFilled: true,
    width: pictureWidth(10, MONEY_MASK_DECIMAL_POSITIONS, false),
    source: 'app/cbl/COBIL00C.cbl L56 (WS-CURR-BAL)',
  },
  /**
   * `+99999999.99` — the ungrouped, zero-filled picture transaction amounts print in.
   *
   * Assumptions: eight integer positions, and this is the most widely declared of the three — the
   * bill-payment transaction amount, the report total, and the transaction list, detail and add
   * screens all declare it. It is the picture behind the `+00001234.56` an audit reads off the
   * transaction screens.
   */
  transactionAmount: {
    picture: '+99999999.99',
    integerPositions: 8,
    decimalPositions: MONEY_MASK_DECIMAL_POSITIONS,
    grouped: false,
    zeroFilled: true,
    width: pictureWidth(8, MONEY_MASK_DECIMAL_POSITIONS, false),
    source:
      'app/cbl/COBIL00C.cbl L55; app/cbl/CORPT00C.cbl L77; app/cbl/COTRN00C.cbl L56; app/cbl/COTRN01C.cbl L49; app/cbl/COTRN02C.cbl L53, L59',
  },
  /**
   * `-zzzz9.99` — the nine-character, ungrouped, blank-suppressed picture the authorization summary
   * prints its narrow amounts in.
   *
   * Assumptions: measured from the program and not from the mapset, because the authorization mapsets
   * declare no `PICOUT` on any amount field at all — so unlike the account screens, the mask here lives
   * entirely in the program's own edit field. `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L57
   * declares `05 WS-DISPLAY-AMT9 PIC -zzzz9.99.` and moves four amounts through it at L782 to L799:
   * the cash limit, the cash balance, the approved total and the declined total.
   *
   * Assumptions: FIVE integer positions, which is what makes this picture narrower than every other one
   * in this map and is the reason it could not be served by an existing entry. `-` plus `zzzz9` plus
   * `.99` is nine characters, and {@link pictureWidth} reaches the same nine from the positions alone.
   *
   * Assumptions: `suppressedIntegerPositions` is four rather than five, because the units position is a
   * `9` and prints its digit. A settled balance therefore renders as four blanks, a `0` and `.00`, which
   * is what the terminal shows — an all-`Z` picture would show a field of spaces and no zero at all.
   *
   * Assumptions: `signsNonNegative` is false, because the picture's sign character is a single `-`
   * rather than a `+`. A non-negative amount prints a blank in that position, so the leading character
   * of a positive authorization amount is a space and not a plus.
   *
   * Trade-offs: the twelve-character sibling of this picture — `-zzzzzzz9.99`, declared at
   * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` L56 and
   * `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` L52 — is deliberately NOT added here. Its
   * width, its eight integer positions and its ungrouped form are already served by
   * {@link MONEY_PICTURES.transactionAmount}, whose `+99999999.99` differs from it only in the two
   * respects this entry's two members express, and that substitution is an accepted divergence the
   * authorization screens already record. Adding a fifth entry would give two amounts on ONE screen two
   * different sign conventions, which is worse than the divergence it would remove.
   */
  authorizationSummaryAmount: {
    picture: '-zzzz9.99',
    integerPositions: 5,
    decimalPositions: MONEY_MASK_DECIMAL_POSITIONS,
    grouped: false,
    zeroFilled: false,
    width: pictureWidth(5, MONEY_MASK_DECIMAL_POSITIONS, false),
    suppressedIntegerPositions: 4,
    signsNonNegative: false,
    source:
      'app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl L57 (WS-DISPLAY-AMT9), applied at L782-L799',
  },
} as const satisfies Record<string, MoneyPicture>;

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
 * Assumptions: the picture is a PARAMETER with the account picture as its default, so every existing
 * call site keeps its exact rendering and only a caller that names a different picture gets one. The
 * alternative was a function per picture, which would have put the zero-suppression and grouping
 * logic in three places to vary two flags; and the alternative to BOTH was one global picture, which
 * {@link MONEY_PICTURES} records the refusal of.
 * @param {string} wireAmount - Money as the service published it: optional sign, integer digits, a
 *   decimal point and exactly two decimal digits.
 * @param {MoneyPicture} [picture] - The measured picture to render through; defaults to the
 *   account screens' `+ZZZ,ZZZ,ZZZ.99`.
 * @returns {string} The amount rendered through the mask, or the input unchanged when it does not
 *   match the wire contract.
 */
export function applyMoneyEditMask(
  wireAmount: string,
  picture: MoneyPicture = MONEY_PICTURES.accountGrouped,
): string {
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
   * WHY : Assumptions: the sign position always emits a character, because the picture's leading sign
   *       is a FIXED position rather than a conditional one. WHICH character it emits for a
   *       non-negative value depends on the insertion character the picture declares: a `+` prints
   *       `+`, and a single `-` prints a BLANK -- a real position, so the width is the same either
   *       way and a column stays aligned across mixed signs. A negative value prints `-` under both.
   *       A negative zero cannot arrive: the pattern above captures the sign separately from the
   *       digits, and a scale-two zero is published as `0.00`.
   * WHY : Assumptions: the member is read with `?? true` rather than being required, so a picture that
   *       omits it keeps the `+` behaviour every picture had before it existed. That is what makes the
   *       three account, bill-payment and transaction renderings provably untouched by this branch.
   */
  const nonNegativeSign = (picture.signsNonNegative ?? true) ? '+' : ' ';
  const signCharacter = sign === '-' ? '-' : nonNegativeSign;

  /*
   * WHY : Assumptions: a zero-filled picture takes a separate, much shorter path, and it is taken
   *       FIRST because it shares nothing with the suppressed one. A `9` position prints its digit
   *       whatever it is, so there is no significance scan to do and no separator to strand; the
   *       whole rendering is a left pad with zeroes to the declared position count. Padding rather
   *       than slicing preserves the no-truncation trade-off recorded above for this picture too: a
   *       value wider than the field widens the rendering instead of losing a high-order digit.
   */
  if (picture.zeroFilled) {
    return `${signCharacter}${integerDigits.padStart(picture.integerPositions, '0')}.${decimalDigits}`;
  }

  /*
   * WHY : Assumptions: leading zeroes are removed before grouping rather than after, so that the
   *       comma positions are computed from the significant digits alone. An all-zero integer part
   *       reduces to the empty string, which is the blank integer field the `Z` positions produce.
   * WHY : Assumptions: suppression stops at the picture's MANDATORY positions rather than consuming
   *       every zero, because a picture may end its integer field with a `9` -- `-zzzz9.99` does. Those
   *       positions print their digit whatever it is, so a value of zero keeps its units digit and
   *       renders `0` where an all-`Z` picture renders a blank. A picture that omits
   *       `suppressedIntegerPositions` has none of them, so `mandatoryPositions` is zero and the whole
   *       leading run is stripped exactly as it was before this branch existed.
   *       Alternatives Considered: padding the stripped run back up to the mandatory count with `'0'`,
   *       which reaches the same string for a zero value. Rejected because it would compose a digit
   *       rather than keep one, and the two differ the moment a mandatory count above one is declared:
   *       taking the digits from the ORIGINAL run keeps whatever the value actually had in those
   *       positions, where padding would emit zeroes it never carried.
   */
  const mandatoryPositions =
    picture.integerPositions - (picture.suppressedIntegerPositions ?? picture.integerPositions);
  const strippedDigits = integerDigits.replace(/^0+/u, '');
  const significantDigits =
    strippedDigits.length >= mandatoryPositions
      ? strippedDigits
      : integerDigits.slice(integerDigits.length - mandatoryPositions);
  const groupedDigits = picture.grouped ? group(significantDigits) : significantDigits;
  const grouped = significantDigits === '' ? '' : groupedDigits;

  /*
   * WHY : Assumptions: the integer field is padded to the width the mask's own positions occupy --
   *       the nine digits plus the two separators -- so that decimal points line up down a column
   *       when the values are rendered in the fixed-pitch font the mapset's `JUSTIFY=(RIGHT)` fields
   *       relied on. A value wider than the field is left as it is rather than truncated, per the
   *       trade-off recorded above, so this pads and never cuts.
   * WHY : Assumptions: the field width is computed from the PICTURE's own positions rather than from
   *       the module constants, so a suppressed picture with a different position count pads to its
   *       own width. The separator term repeats the arithmetic in `pictureWidth` rather than calling
   *       it, because that function returns the TOTAL width including the sign and the decimals and
   *       this needs the integer field alone.
   */
  const integerFieldWidth =
    picture.integerPositions +
    (picture.grouped ? Math.floor((picture.integerPositions - 1) / GROUP_SIZE) : 0);
  const integerField =
    grouped.length >= integerFieldWidth ? grouped : grouped.padStart(integerFieldWidth, ' ');

  return `${signCharacter}${integerField}.${decimalDigits}`;
}

/**
 * The three cases a monetary value falls into, which the rendering has to tell apart.
 *
 * Assumptions: `zero` is a case of its own rather than a member of `positive`, because the baseline's
 * sign position prints `+` for it and an operator reading a column of balances needs a settled
 * account to look different from a credit one. The three names are the keys of
 * `MONEY_SIGN_TEXT_TOKENS` in `ui/src/theme/tokens.ts`, so the two modules cannot name the cases
 * differently.
 */
export type MoneySign = 'positive' | 'negative' | 'zero';

/**
 * Classifies a wire money value as positive, negative or zero.
 *
 * Assumptions: classified from the CHARACTERS, like everything else in this module. A leading `-`
 * with any non-zero digit is negative; an all-zero digit string is zero whatever its sign; anything
 * else is positive. Comparing against `0` numerically was the alternative and is rejected for the
 * reason the whole module exists: it would route an exact decimal through a double, and a value
 * beyond a double's exact range could then classify wrongly.
 *
 * Assumptions: a value that does not match the wire contract classifies as `positive` rather than
 * throwing, which keeps this consistent with {@link applyMoneyEditMask}'s pass-through discipline —
 * the unreadable value is still shown, in the colour the ordinary case uses, so nothing about it
 * asserts a meaning that was not established.
 * @param {string} wireAmount - Money as the service published it.
 * @returns {MoneySign} Which of the three cases the value falls into.
 */
export function classifyMoneySign(wireAmount: string): MoneySign {
  const parsed = WIRE_MONEY_PATTERN.exec(wireAmount);
  if (parsed === null) {
    return 'positive';
  }
  const [, sign = '', integerDigits = '', decimalDigits = ''] = parsed;
  if (/^0+$/u.test(integerDigits) && /^0+$/u.test(decimalDigits)) {
    return 'zero';
  }
  return sign === '-' ? 'negative' : 'positive';
}

/**
 * Resolves a sign case to the design token its text is painted in.
 *
 * Assumptions: the map lives in `ui/src/theme/tokens.ts` beside every other colour decision, and this
 * function only reads it, so the colour question is answered in one place and the rendering question
 * in another. Declaring the three tokens here instead was the alternative and is rejected: it would
 * put design values in a formatter, where no contrast assertion looks.
 * @param {MoneySign} sign - The case the value falls into.
 * @returns {AntdTokenName} Name of the design token the value's text takes.
 */
export function moneySignTextToken(sign: MoneySign): AntdTokenName {
  return MONEY_SIGN_TEXT_TOKENS[sign];
}

/**
 * Everything a screen needs to paint one monetary value consistently.
 *
 * Assumptions: this carries a token NAME and not a colour, so a screen resolves it through the
 * design system's own token accessor exactly as it resolves every other colour. Returning a hex here
 * would put a rendered design value in a formatter and put it outside the reach of the contrast
 * assertions in `ui/src/theme/textContrast.test.ts`.
 */
export interface RenderedMoney {
  /** The amount rendered through its picture, ready to paint. */
  readonly text: string;
  /** Which of the three cases the value falls into. */
  readonly sign: MoneySign;
  /** Design token the text is painted in, so the three cases are distinguishable. */
  readonly colorToken: AntdTokenName;
  /** The picture the text was rendered through, so a caller can assert the width it expects. */
  readonly picture: MoneyPicture;
  /**
   * CSS `white-space` value the rendering requires.
   *
   * ⚠️ Assumptions: `pre` is not a preference. A suppressed picture pads its integer field with
   * BLANKS, and those blanks are the whole mechanism by which decimal points line up down a column;
   * HTML collapses a run of spaces in a text node to one, so painting this text without `pre`
   * discards the alignment the mask exists to produce and makes two amounts of different magnitudes
   * appear at different offsets. The zero-filled pictures do not need it — they pad with digits —
   * and it is returned for them anyway so a screen never has to know which picture it received.
   */
  readonly whiteSpace: 'pre';
}

/**
 * Renders one monetary value with its picture and its sign semantics together.
 *
 * Purpose: this is the single entry point a screen should use. Before it existed, each screen chose
 * its own rendering and none of them expressed the sign — the same colour and weight painted a
 * credit, a debit and a settled balance, on screens that were painting an authorisation response
 * green and a fraud status red three rows away. The AAP requires the positive, negative and zero
 * renderings to be distinguishable, and a screen cannot satisfy that by choosing well: it has to be
 * one decision, made here, that every screen receives.
 *
 * Assumptions: colour is REDUNDANT here and never the sole carrier, which is what keeps this on the
 * right side of the use-of-colour criterion. Every measured picture is signed, so the leading `+` or
 * `-` is in the returned text whatever token the caller resolves.
 *
 * Alternatives Considered: returning a React element instead of a description, which would let this
 * module apply the colour itself and make adoption a one-line change at each call site. Rejected
 * because it would make a formatter import the component library and would fix the element type —
 * some of these amounts belong in a `Descriptions` item, some in a table cell and one in the value
 * of an editable control, and a fixed element serves none of them well.
 * @param {string} wireAmount - Money as the service published it.
 * @param {MoneyPicture} [picture] - The measured picture for the screen family being rendered;
 *   defaults to the account screens' `+ZZZ,ZZZ,ZZZ.99`.
 * @returns {RenderedMoney} The rendered text, its sign, the token it is painted in, the picture used
 *   and the whitespace mode the rendering requires.
 */
export function renderMoney(
  wireAmount: string,
  picture: MoneyPicture = MONEY_PICTURES.accountGrouped,
): RenderedMoney {
  const sign = classifyMoneySign(wireAmount);
  return {
    text: applyMoneyEditMask(wireAmount, picture),
    sign,
    colorToken: moneySignTextToken(sign),
    picture,
    whiteSpace: 'pre',
  };
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
  /*
   * WHY : Assumptions: leading zeroes are removed, and this is what lets the reverse read a
   *       ZERO-FILLED picture as well as a suppressed one. `+00001234.56` arrives with four leading
   *       zeroes that are picture padding rather than magnitude, and sending `00001234.56` would
   *       hand the service a form its parser has no reason to accept. Reducing an all-zero run to a
   *       single `0` rather than to nothing keeps a settled balance readable as `0.00`.
   * WHY : Trade-offs: this makes the reverse lossy with respect to WHICH picture produced the text,
   *       which is deliberate. The service is the authority on the value and has no interest in its
   *       presentation, so the reverse's job is to remove every trace of the picture rather than to
   *       record it. A caller that needs the picture back has it already -- it chose it.
   */
  const withoutPadding = stripped.replace(/^0+(?=[0-9])/u, '');
  const integerDigits = withoutPadding.length === 0 ? '0' : withoutPadding;

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
