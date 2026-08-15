/**
 * @file The transaction add screen, migrated from `app/cbl/COTRN02C.cbl` and its mapset
 * `app/bms/COTRN02.bms` (61 `DFHMDF` fields, of which 21 are named), reached at `/transactions/new`.
 *
 * Purpose
 * -------
 * Capture one new transaction against an account or a card and write it behind an explicit
 * confirmation. It replaces CICS transaction CT02, and it publishes the field labels, the field
 * widths, the format hints and the legend parts this screen paints, because `ui/src/messages/messages.ts`
 * deliberately excludes BMS static text and assigns a screen's own mapset literals to the screen.
 *
 * Validation order is observable
 * ------------------------------
 * Assumptions: the reference program runs five sequential `EVALUATE TRUE` blocks, two date-utility
 * calls and one trailing `IF`, and every one of those blocks is first-match-wins with a single message
 * and a single cursor position. The order is therefore part of the contract rather than an
 * implementation detail, which is why this screen runs its own short-circuiting chain instead of
 * declaring Ant Design `Form` rules -- see {@link keyFieldFailure} and {@link dataFieldFailure}.
 *
 * Where the server's half of the chain arrives
 * --------------------------------------------
 * Assumptions: two of the reference's steps cannot run in a browser. The amount is re-rendered through
 * the `+99999999.99` edit mask after conversion (`app/cbl/COTRN02C.cbl` L383-L386), and each date is
 * evaluated by `CSUTLDTC` (L389-L427), whose migrated equivalent is
 * `com.carddemo.common.validation.DateEditValidator`. Both arrive on the UNCONFIRMED turn: a submission
 * whose confirmation is not an accepted 'Y' answer is sent as a preview, which the contract answers 200
 * with nothing written, and the reference's own sequence is the same -- it validates and normalises
 * first and evaluates the confirmation character afterwards.
 *
 * Money
 * -----
 * Assumptions: the amount is a string on every path in this file. Transformation rule T3 forbids
 * `float` and `double` in the money path, and JavaScript has one numeric type which is an IEEE-754
 * double, so `Number`, `parseFloat` and arithmetic on the amount are all absent by construction.
 */

import { Button, Flex, Form, Input, Popconfirm, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
// Assumptions: routing comes from `react-router`, never from `react-router-dom`, which is the import
// most React code reaches for and is deliberately ABSENT from `ui/package.json`. No 8.x of the
// companion package exists: it is a thin shim that depends on `react-router@7`, so importing it would
// silently pin routing a major version behind the one this tree declares. `ui/eslint.config.js` makes
// the choice enforceable rather than conventional by listing it under `no-restricted-imports`, so the
// build fails rather than resolving two routers.
import { useNavigate } from 'react-router';

import { isApiRequestError } from '../../api/client';
import { addTransaction, copyLastTransaction } from '../../api/transactions';
import type { TransactionAddOutcome, TransactionCreateRequest } from '../../api/transactions';
import type { ApiError, FieldValidationState } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar, UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
  padToDeclaredWidth,
} from '../../messages/messages';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_COLOR_TOKENS, FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/*
 * WHY : Assumptions: every sentence this screen renders is imported rather than retyped, because
 *       `ui/src/messages/messages.ts` is the single owner of baseline message text and carries each
 *       string's declared width and originating line beside it. Retyping one would put a second copy in
 *       the tree with nothing to compare it against, and the failure mode is a single character -- a
 *       dropped ellipsis dot or a doubled space -- which reads as correct in review and registers as a
 *       golden-master parity failure. Twenty-seven of the sentences are this program's own; five are
 *       shared with other programs and are filed under the shared group for that reason.
 */
const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;

/** CICS transaction identifier this screen replaces, from `WS-TRANID` at `app/cbl/COTRN02C.cbl` L37. */
export const TRANSACTION_ADD_TRANSACTION_ID = 'CT02';

/** Source program name, from `WS-PGMNAME` at `app/cbl/COTRN02C.cbl` L36. */
export const TRANSACTION_ADD_PROGRAM_NAME = 'COTRN02C';

/**
 * Mapset this screen stands in, which selects the message band's display width.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` records `COTRN02` at 78 characters, matching
 * `ERRMSG ... LENGTH=78` at `app/bms/COTRN02.bms` L293-L296. The band is sized from the mapset rather
 * than from the route because the route is a target shape this migration chose while the mapset is the
 * reference identity the width is a property of.
 */
export const TRANSACTION_ADD_MAPSET = 'COTRN02';

/**
 * Screen title, verbatim from the row-4 heading at `app/bms/COTRN02.bms` L75-L79.
 *
 * Assumptions: the field is `ATTRB=(ASKIP,BRT)`, and brightness resolves to font weight rather than to
 * colour through `TYPOGRAPHY_TOKENS.brightEmphasis` -- all 37 bright fields in the base mapsets already
 * carry a colour, so expressing brightness as colour would collide on every one of them.
 */
export const TRANSACTION_ADD_TITLE = 'Add Transaction';

/**
 * The thirteen field labels and the confirmation prompt, verbatim from `app/bms/COTRN02.bms`.
 *
 * Assumptions: each value is the mapset's `INITIAL=` literal character for character, colon included,
 * because transformation rule T8 carries user-visible text across unchanged and the screen tests
 * assert each label byte-for-byte. The hash in `Card #:` is a literal character standing for the word
 * "number" and is not a substitution marker.
 */
export const TRANSACTION_ADD_FIELD_LABELS = {
  /** `app/bms/COTRN02.bms` L80-L84. */
  accountId: 'Enter Acct #:',
  /** `app/bms/COTRN02.bms` L99-L103. */
  cardNumber: 'Card #:',
  /** `app/bms/COTRN02.bms` L117-L121. */
  typeCode: 'Type CD:',
  /** `app/bms/COTRN02.bms` L130-L134. */
  categoryCode: 'Category CD:',
  /** `app/bms/COTRN02.bms` L143-L147. */
  source: 'Source:',
  /** `app/bms/COTRN02.bms` L156-L160. */
  description: 'Description:',
  /** `app/bms/COTRN02.bms` L169-L173. */
  amount: 'Amount:',
  /** `app/bms/COTRN02.bms` L182-L186. */
  originDate: 'Orig Date:',
  /** `app/bms/COTRN02.bms` L195-L199. */
  processDate: 'Proc Date:',
  /** `app/bms/COTRN02.bms` L223-L227. */
  merchantId: 'Merchant ID:',
  /** `app/bms/COTRN02.bms` L236-L240. */
  merchantName: 'Merchant Name:',
  /** `app/bms/COTRN02.bms` L249-L253. */
  merchantCity: 'Merchant City:',
  /** `app/bms/COTRN02.bms` L262-L266. */
  merchantZip: 'Merchant Zip:',
  /** `app/bms/COTRN02.bms` L275-L280, declared `LENGTH=55`; the space before the colon is in the source. */
  confirmation: 'You are about to add this transaction. Please confirm :',
} as const;

/*
 * WHY : Assumptions: every width below is the SYMBOLIC MAP's declaration in
 *       `app/cpy-bms/COTRN02.CPY`, never the 350-byte record's in `app/cpy/CVTRA05Y.cpy`, and the two
 *       disagree in six places. The map is narrower at all six: description 60 against
 *       `TRAN-DESC PIC X(100)` (L9), merchant name 30 against `PIC X(50)` (L12), merchant city 25
 *       against `PIC X(50)` (L13), and both dates 10 against the 26-character timestamps
 *       `TRAN-ORIG-TS` and `TRAN-PROC-TS` (L16-L17) -- the map captures a date where the record stores
 *       an instant. The amount is the sixth and the subtlest: the field is 12 characters and the edit
 *       mask `WS-TRAN-AMT-E PIC +99999999.99` (`app/cbl/COTRN02C.cbl` L59) spends them on a sign, EIGHT
 *       integer digits, a point and two decimals, while the record declares
 *       `TRAN-AMT PIC S9(09)V99` (L10) with NINE integer digits. The map width is what governs here
 *       because it is the constraint the operator actually meets: the terminal refused the 61st
 *       description character, so a browser that accepted 100 would let an operator key a value the
 *       screen it replaces could not hold, and the service would then either refuse it or store text no
 *       3270 turn could have produced. The consequence is recorded rather than silently resolved: the
 *       ninth integer digit of the record is unreachable from this screen in the baseline too.
 */

/**
 * Declared width of each editable field, from the symbolic map `COTRN2AI` in `app/cpy-bms/COTRN02.CPY`.
 *
 * Assumptions: these are the `maxLength` values, so a field cannot accept more characters than the
 * 3270 field held. ADR-006 states that an input's maximum length is its copybook picture width.
 */
export const TRANSACTION_ADD_FIELD_WIDTHS = {
  /** `ACTIDINI PIC X(11)`, `app/cpy-bms/COTRN02.CPY` L60. */
  accountId: 11,
  /** `CARDNINI PIC X(16)`, `app/cpy-bms/COTRN02.CPY` L66. */
  cardNumber: 16,
  /** `TTYPCDI PIC X(2)`, `app/cpy-bms/COTRN02.CPY` L72. */
  typeCode: 2,
  /** `TCATCDI PIC X(4)`, `app/cpy-bms/COTRN02.CPY` L78. */
  categoryCode: 4,
  /** `TRNSRCI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L84. */
  source: 10,
  /** `TDESCI PIC X(60)`, `app/cpy-bms/COTRN02.CPY` L90. */
  description: 60,
  /** `TRNAMTI PIC X(12)`, `app/cpy-bms/COTRN02.CPY` L96. */
  amount: 12,
  /** `TORIGDTI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L102. */
  originDate: 10,
  /** `TPROCDTI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L108. */
  processDate: 10,
  /** `MIDI PIC X(9)`, `app/cpy-bms/COTRN02.CPY` L114. */
  merchantId: 9,
  /** `MNAMEI PIC X(30)`, `app/cpy-bms/COTRN02.CPY` L120. */
  merchantName: 30,
  /** `MCITYI PIC X(25)`, `app/cpy-bms/COTRN02.CPY` L126. */
  merchantCity: 25,
  /** `MZIPI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L132. */
  merchantZip: 10,
  /** `CONFIRMI PIC X(1)`, `app/cpy-bms/COTRN02.CPY` L138. */
  confirmation: 1,
} as const;

/**
 * The three protected format hints the mapset paints on row 15, verbatim.
 *
 * Assumptions: all three are `COLOR=BLUE` `ATTRB=(ASKIP,NORM)` fields sitting directly beneath the
 * inputs they describe (`app/bms/COTRN02.bms` L208-L222), so they are guidance and never editable
 * values. The parentheses are part of each literal.
 */
export const TRANSACTION_ADD_FORMAT_HINTS = {
  /** `app/bms/COTRN02.bms` L208-L212, `LENGTH=14`. */
  amount: '(-99999999.99)',
  /** `app/bms/COTRN02.bms` L213-L217, `LENGTH=12`. */
  originDate: '(YYYY-MM-DD)',
  /** `app/bms/COTRN02.bms` L218-L222, `LENGTH=12`. */
  processDate: '(YYYY-MM-DD)',
} as const;

/**
 * The word the mapset paints between the two key fields, verbatim from `app/bms/COTRN02.bms` L94-L98.
 *
 * Assumptions: it is a `COLOR=NEUTRAL` `LENGTH=4` field, and it carries real meaning rather than
 * decoration -- it states that the account identifier and the card number are alternatives, which is
 * exactly what the ordered `EVALUATE TRUE` at `app/cbl/COTRN02C.cbl` L195-L230 implements.
 */
export const TRANSACTION_ADD_ALTERNATIVE_KEY_LABEL = '(or)';

/**
 * The domain hint painted beside the confirmation field, verbatim from `app/bms/COTRN02.bms` L288-L292.
 */
export const TRANSACTION_ADD_CONFIRM_DOMAIN_HINT = '(Y/N)';

/** Width of the row-8 rule, declared `LENGTH=70` at `app/bms/COTRN02.bms` L111-L116. */
export const TRANSACTION_ADD_RULE_WIDTH = 70;

/**
 * The row-8 separator, seventy hyphens, dividing the key fields from the transaction fields.
 *
 * Alternatives Considered: an Ant Design `Divider`, which is the idiomatic component for a section
 * rule and was rejected here. The mapset paints this as a text field whose declared width is 70 of the
 * terminal's 80 columns, so a `Divider` would span the container instead and would silently drop a
 * literal the screen tests read. It is built by repetition rather than typed out so that its length is
 * exact by construction and cannot be miscounted by an editor.
 */
export const TRANSACTION_ADD_RULE = '-'.repeat(TRANSACTION_ADD_RULE_WIDTH);

/*
 * WHY : Assumptions: only PF4's wording is imported. `UNIFORM_PF_KEY_LABELS` holds the three legends
 *       whose text is identical across every mapset that paints them, and `F4=Clear` is one of them, so
 *       importing it keeps this screen from becoming a second place that spelling could drift. ENTER,
 *       PF3 and PF5 are declared here because their wording is per-screen: PF3 reads `Back` here and
 *       `Exit` on nine other mapsets, and PF5 reads `Copy Last Tran.` here where other screens paint
 *       `Save` or `Delete`.
 * WHY : Assumptions: PF5 is bound to copying the last transaction and NOT to saving, and the legend is
 *       what settles it. AAP section 0.3.3 records a uniform PF5=save reading measured across the
 *       online programs, and this screen contradicts it in two independent places -- the row-24 legend
 *       at `app/bms/COTRN02.bms` L297-L302 paints `F5=Copy Last Tran.`, and the dispatch at
 *       `app/cbl/COTRN02C.cbl` L146-L147 performs `COPY-LAST-TRAN-DATA` for `DFHPF5`. A screen's own
 *       legend and its own dispatch outrank a convention derived from other screens; binding a save to
 *       PF5 here would write a transaction on a key the operator was told copies one.
 */

/**
 * Legend labels for the four keys this screen paints, split by who owns each spelling.
 *
 * Assumptions: the row-24 field is one literal, `ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.`
 * (`app/bms/COTRN02.bms` L297-L302, declared `LENGTH=53`), and the two spaces between each segment are
 * in the source. Splitting it into four labels is what lets each one sit on the control that performs
 * it, and the parts still join back to the declared 53 characters.
 */
export const TRANSACTION_ADD_KEY_LABELS = {
  ENTER: 'ENTER=Continue',
  PFK03: 'F3=Back',
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
  PFK05: 'F5=Copy Last Tran.',
} as const;

/** One editable field of the transaction add screen, named as the service contract names it. */
export type TransactionAddField = keyof typeof TRANSACTION_ADD_FIELD_WIDTHS;

/** The value of every editable field, each held as the text the operator keyed. */
export type TransactionAddValues = Record<TransactionAddField, string>;

/**
 * One field the screen is reporting a refusal against.
 *
 * Assumptions: `state` distinguishes a blank field from an otherwise-refused one because
 * `app/cpy/CSSETATY.cpy` L17-L27 draws that distinction: it moves the error colour in for either, and
 * nests the additional `MOVE '*'` inside the blank test alone.
 */
export interface TransactionAddFieldError {
  /** Field the refusal names. */
  readonly field: TransactionAddField;
  /** Verbatim sentence to render beneath the control. */
  readonly message: string;
  /** Whether the field was blank, which alone earns the asterisk marker. */
  readonly state: FieldValidationState;
}

/**
 * Every field blank, which is the state `INITIALIZE-ALL-FIELDS` leaves the screen in.
 *
 * Assumptions: `app/cbl/COTRN02C.cbl` L762-L779 moves spaces into all thirteen data fields, both key
 * fields and the confirmation, and additionally sets the account field's cursor. That paragraph is
 * reached from two places -- the PF4 arm through `CLEAR-CURRENT-SCREEN` (L754-L757) and the successful
 * write (L725) -- so both of this screen's clears are the same operation and share this one value.
 */
const BLANK_VALUES: TransactionAddValues = {
  accountId: '',
  cardNumber: '',
  typeCode: '',
  categoryCode: '',
  source: '',
  description: '',
  amount: '',
  originDate: '',
  processDate: '',
  merchantId: '',
  merchantName: '',
  merchantCity: '',
  merchantZip: '',
  confirmation: '',
};

/** Matches a run of ASCII digits and nothing else, used for the COBOL numeric class test. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** Matches a monetary amount the service may return: an optional sign, integral digits and two decimals. */
const SERVICE_MONEY = /^-?[0-9]+\.[0-9]{2}$/u;

/** Matches an integral-and-fractional pair whose digits are all zero, so the edit mask emits a plus. */
const ALL_ZERO_DIGITS = /^0+$/u;

/** Integer digits the `+99999999.99` edit mask holds, from `WS-TRAN-AMT-E` at `app/cbl/COTRN02C.cbl` L59. */
const AMOUNT_MASK_INTEGER_DIGITS = 8;

/** Fractional digits the same mask holds. */
const AMOUNT_MASK_FRACTION_DIGITS = 2;

/** Digits of a card number a masked rendering keeps, matching the reduction the services perform. */
const CARD_NUMBER_VISIBLE_DIGITS = 4;

/**
 * Reports whether a fixed-width field satisfies COBOL's numeric class test.
 *
 * Assumptions: the test is applied to the WHOLE declared field and not to the characters an operator
 * happened to key, which is why the width is a parameter. A 3270 `RECEIVE MAP` moves the transmitted
 * characters in left-justified and pads the remainder of the field with spaces, and a space is not a
 * numeric character, so `IF ACTIDINI OF COTRN2AI IS NOT NUMERIC` at `app/cbl/COTRN02C.cbl` L197 refuses
 * a partly-keyed account identifier exactly as it refuses a letter. Reproducing that means a numeric
 * field is refused unless it is filled to its declared width with digits.
 *
 * Alternatives Considered: testing only the keyed characters -- `/^[0-9]*$/` against the raw value --
 * which is the reflexive reading of "must be numeric" and is what a browser form usually enforces.
 * Rejected because it accepts a value the reference refuses: `123` keyed into the eleven-character
 * account field would pass here and then be sent as an account identifier the service must reject,
 * moving a refusal the operator used to see immediately to the far side of a round trip.
 * @param {string} value - Characters the operator keyed, unpadded.
 * @param {number} declaredWidth - The field's `PICTURE` width from the symbolic map.
 * @returns {boolean} `true` when the field, padded to its declared width, is composed only of digits.
 */
export function isNumericField(value: string, declaredWidth: number): boolean {
  return DIGITS_ONLY.test(padToDeclaredWidth(value, declaredWidth));
}

/**
 * Reports whether a field is blank, which is the reference's `= SPACES OR LOW-VALUES` test.
 *
 * Assumptions: both figurative constants collapse to one browser condition. `LOW-VALUES` is the state
 * `MOVE LOW-VALUES TO COTRN2AO` (`app/cbl/COTRN02C.cbl` L122) leaves an untransmitted field in and
 * `SPACES` is the state a cleared one holds, and neither has a counterpart in a DOM input whose value is
 * simply the empty string or whitespace.
 * @param {string} value - Characters the operator keyed.
 * @returns {boolean} `true` when the field carries no non-blank character.
 */
export function isBlankField(value: string): boolean {
  return value.trim() === '';
}

/**
 * Reports whether the amount has the shape the reference's twelve-character predicate accepts.
 *
 * Assumptions: the four `WHEN` branches at `app/cbl/COTRN02C.cbl` L340-L343 are reference-modified with
 * COBOL's `(offset:length)` form, so `TRNAMTI(2:8)` spans positions two THROUGH NINE rather than two
 * through eight. Read that way the predicate covers all twelve characters with no gap -- position one
 * is the sign, two to nine are the eight integer digits, ten is the point and eleven and twelve are the
 * decimals -- which is exactly the width the `+99999999.99` edit mask spends. The offsets below are
 * therefore the reference's own, converted to zero-based slices, and nothing is tightened or relaxed.
 *
 * Trade-offs: the sign is MANDATORY, so `100.00` is refused where `+100.00` is accepted, and that
 * strictness is the reference's rather than a choice made here -- `TRNAMTI(1:1) NOT EQUAL '-' AND '+'`
 * admits no third character. The hint the mapset paints beneath the field, `(-99999999.99)`, is what
 * tells the operator so.
 * @param {string} value - Characters the operator keyed into the amount field.
 * @returns {boolean} `true` when the padded field is a sign, eight digits, a point and two digits.
 */
export function hasBaselineAmountShape(value: string): boolean {
  const field = padToDeclaredWidth(value, TRANSACTION_ADD_FIELD_WIDTHS.amount);
  const sign = field.slice(0, 1);
  const integerDigits = field.slice(1, 1 + AMOUNT_MASK_INTEGER_DIGITS);
  const point = field.slice(9, 10);
  const fractionDigits = field.slice(10, 10 + AMOUNT_MASK_FRACTION_DIGITS);

  if (sign !== '-' && sign !== '+') {
    return false;
  }
  if (!DIGITS_ONLY.test(integerDigits)) {
    return false;
  }
  if (point !== '.') {
    return false;
  }
  return DIGITS_ONLY.test(fractionDigits);
}

/*
 * WHY : Refactoring Rationale: ONE predicate serves both dates, where the reference carries two
 *       `EVALUATE TRUE` blocks -- `app/cbl/COTRN02C.cbl` L353-L366 for the originating date and
 *       L368-L381 for the processing date. The two blocks are character-identical apart from the field
 *       they read and the sentence they raise, so a second copy of the predicate would be a second
 *       place for one rule to drift while the messages, which genuinely differ, stay separate at the
 *       two call sites. What is preserved is the ORDER: the originating date is checked before the
 *       processing date, because the blocks run in that sequence and each ends the turn.
 */

/**
 * Reports whether a date field has the shape the reference's ten-character predicate accepts.
 *
 * Assumptions: this checks SHAPE and not validity. The reference performs both -- five reference-
 * modified branches for the shape, then a `CALL 'CSUTLDTC'` for the calendar -- and only the first can
 * run in a browser, so `2024-02-31` passes here and is refused by the service's date evaluator, whose
 * verdict arrives as a field error on this same field.
 * @param {string} value - Characters the operator keyed into a date field.
 * @returns {boolean} `true` when the padded field is four digits, a hyphen, two digits, a hyphen and
 *   two digits.
 */
export function hasBaselineIsoDateShape(value: string): boolean {
  const field = padToDeclaredWidth(value, TRANSACTION_ADD_FIELD_WIDTHS.originDate);
  const year = field.slice(0, 4);
  const firstSeparator = field.slice(4, 5);
  const month = field.slice(5, 7);
  const secondSeparator = field.slice(7, 8);
  const day = field.slice(8, 10);

  if (!DIGITS_ONLY.test(year)) {
    return false;
  }
  if (firstSeparator !== '-') {
    return false;
  }
  if (!DIGITS_ONLY.test(month)) {
    return false;
  }
  if (secondSeparator !== '-') {
    return false;
  }
  return DIGITS_ONLY.test(day);
}

/**
 * Re-renders an amount through the reference's `+99999999.99` edit mask.
 *
 * Assumptions: the reference converts the keyed amount and writes the converted value BACK into the
 * screen field -- `COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI)`, `MOVE WS-TRAN-AMT-N TO
 * WS-TRAN-AMT-E`, `MOVE WS-TRAN-AMT-E TO TRNAMTI` at `app/cbl/COTRN02C.cbl` L383-L386 -- so the operator
 * sees the canonical form after a turn rather than what they keyed. Two observable transformations come
 * out of that mask and both are reproduced: the integer part is zero-filled to eight digits, and the
 * sign is a PLUS for any value that is not negative, so a keyed `-00000000.00` is redisplayed as
 * `+00000000.00` because zero is not negative.
 *
 * Assumptions: the conversion is performed on TEXT, digit by digit, and never through a numeric type.
 * Transformation rule T3 forbids `float` and `double` in the money path, and JavaScript's only numeric
 * type is an IEEE-754 double, so `Number('0.1')` cannot be part of a money conversion that has to
 * agree with a `NUMERIC(12,2)` column to the cent.
 *
 * Assumptions: two input shapes are accepted because two callers need it. The screen's own twelve-
 * character field arrives as a sign, eight digits, a point and two decimals; the service's normalised
 * `Money` string arrives as an optional minus, one to ten integral digits, a point and two decimals --
 * unsigned when positive and unpadded -- so the mask is what brings the two into one rendering.
 * @param {string} value - Either the screen's shaped twelve-character amount or a `Money` string.
 * @returns {string | null} The value rendered through the mask, or `null` when it is neither shape or
 *   when its integer part needs more than the eight digits the mask holds -- which the service's own
 *   ninth digit can reach even though this screen's field cannot key it.
 */
export function toEditMaskAmount(value: string): string | null {
  const trimmed = value.trim();
  const shaped = hasBaselineAmountShape(value);
  const money = SERVICE_MONEY.test(trimmed);

  if (!shaped && !money) {
    return null;
  }

  const negative = trimmed.startsWith('-');
  const unsigned = shaped || negative || trimmed.startsWith('+') ? trimmed.slice(1) : trimmed;
  const [integerPart = '', fractionPart = ''] = unsigned.split('.');

  if (integerPart.length > AMOUNT_MASK_INTEGER_DIGITS) {
    return null;
  }

  const integerDigits = integerPart.padStart(AMOUNT_MASK_INTEGER_DIGITS, '0');
  const sign = ALL_ZERO_DIGITS.test(`${integerDigits}${fractionPart}`) || !negative ? '+' : '-';
  return `${sign}${integerDigits}.${fractionPart}`;
}

/**
 * Normalises a keyed key field to its declared width the way the reference's numeric `MOVE` does.
 *
 * Assumptions: the reference does not merely read the key field, it rewrites it. `MOVE WS-ACCT-ID-N TO
 * XREF-ACCT-ID, ACTIDINI` at `app/cbl/COTRN02C.cbl` L206-L207 moves a `PIC 9(11)` item into the
 * eleven-character screen field, and a numeric-to-alphanumeric `MOVE` of that kind is zero-filled, so
 * an operator who keys `00000000123` and one who keys the same digits after a conversion both see the
 * padded form. The card branch does the same with `WS-CARD-NUM-N PIC 9(16)` at L220-L221.
 * @param {string} value - Digits the operator keyed, already known to satisfy the numeric class test.
 * @param {number} declaredWidth - The field's `PICTURE` width.
 * @returns {string} The digits left-padded with zeros to the declared width.
 */
export function toZeroFilledKey(value: string, declaredWidth: number): string {
  return value.trim().padStart(declaredWidth, '0');
}

/**
 * Reduces a card number to the rendering the services publish: twelve asterisks then the last four digits.
 *
 * Assumptions: AAP section 0.4.1.9 masks a primary account number everywhere it is displayed except on
 * the administrative card-detail endpoint, and this screen is not that endpoint. The asterisk count is
 * fixed at twelve rather than derived from the value's length so that the rendering matches the shape
 * `ui/src/api/transactions.ts` checks a response against, which is the shape a reader of either module
 * recognises.
 *
 * Trade-offs: this is applied to the confirmation summary and NOT to the card-number input. Masking a
 * field the operator is editing would hide what they keyed from them and make a correction impossible,
 * and the reference paints that field as an ordinary unprotected input.
 * @param {string} value - A card number as keyed or as resolved.
 * @returns {string} The masked rendering, or the value unchanged when it is too short to reduce.
 */
export function maskCardNumber(value: string): string {
  const digits = value.trim();
  if (digits.length <= CARD_NUMBER_VISIBLE_DIGITS) {
    return digits;
  }
  const hidden = '*'.repeat(TRANSACTION_ADD_FIELD_WIDTHS.cardNumber - CARD_NUMBER_VISIBLE_DIGITS);
  return `${hidden}${digits.slice(-CARD_NUMBER_VISIBLE_DIGITS)}`;
}

/*
 * WHY : Assumptions: the eleven blank checks are an ORDERED TABLE and the order is the contract. The
 *       reference expresses them as eleven `WHEN` branches of one `EVALUATE TRUE` at
 *       `app/cbl/COTRN02C.cbl` L251-L320, and an `EVALUATE TRUE` selects the FIRST matching branch, so
 *       a submission with three blank fields names exactly one of them -- whichever comes first in this
 *       sequence. The order is therefore observable to an operator correcting a form field by field, and
 *       the screen tests assert it, so the sequence below is transcribed rather than sorted into the
 *       order the fields are laid out in or the order the contract declares them.
 * WHY : Alternatives Considered: Ant Design's `Form` `rules` with `required`, which is the idiomatic way
 *       to express a mandatory field and is not used anywhere in this screen's validation. Rejected
 *       because the form validates every rule it holds and renders every failure at once, which would
 *       show eleven refusals where the reference shows one, and would leave the screen with no single
 *       sentence to place in the message band and no single field to move the cursor to. A short-
 *       circuiting chain is what reproduces one message, one marker and one cursor position.
 */

/**
 * The eleven fields the reference tests for emptiness, in its own order, with the sentence each raises.
 *
 * Assumptions: the two key fields are absent from this table on purpose. Their emptiness is not an error
 * on its own -- either one satisfies the other's absence -- so it is decided by {@link keyFieldFailure}
 * before this table is read, exactly as `VALIDATE-INPUT-KEY-FIELDS` runs before
 * `VALIDATE-INPUT-DATA-FIELDS` at `app/cbl/COTRN02C.cbl` L166-L167.
 */
const BLANK_CHECK_SEQUENCE: readonly (readonly [TransactionAddField, string])[] = [
  ['typeCode', ADD_MESSAGES.TYPE_CD_CAN_NOT_BE_EMPTY],
  ['categoryCode', ADD_MESSAGES.CATEGORY_CD_CAN_NOT_BE_EMPTY],
  ['source', ADD_MESSAGES.SOURCE_CAN_NOT_BE_EMPTY],
  ['description', ADD_MESSAGES.DESCRIPTION_CAN_NOT_BE_EMPTY],
  ['amount', ADD_MESSAGES.AMOUNT_CAN_NOT_BE_EMPTY],
  ['originDate', ADD_MESSAGES.ORIG_DATE_CAN_NOT_BE_EMPTY],
  ['processDate', ADD_MESSAGES.PROC_DATE_CAN_NOT_BE_EMPTY],
  ['merchantId', ADD_MESSAGES.MERCHANT_ID_CAN_NOT_BE_EMPTY],
  ['merchantName', ADD_MESSAGES.MERCHANT_NAME_CAN_NOT_BE_EMPTY],
  ['merchantCity', ADD_MESSAGES.MERCHANT_CITY_CAN_NOT_BE_EMPTY],
  ['merchantZip', ADD_MESSAGES.MERCHANT_ZIP_CAN_NOT_BE_EMPTY],
];

/**
 * Reports which key field a submission is addressed by, or the failure that stops it.
 *
 * Assumptions: the account identifier takes PRECEDENCE over the card number, and that is the ordered
 * `EVALUATE TRUE` at `app/cbl/COTRN02C.cbl` L195-L230 rather than a preference chosen here: its first
 * branch fires whenever the account field carries anything, so a submission naming both is resolved by
 * the account and the keyed card number is overwritten from the cross-reference at L209. Both branches
 * write a field back -- the account branch fills the card number and the card branch fills the account
 * identifier -- so neither key is merely read.
 *
 * Refactoring Rationale: the cross-reference resolution itself is the SERVICE's and is not attempted
 * here. The reference reads `CXACAIX` or `CCXREF` in this paragraph, and the migrated equivalent is one
 * request that carries whichever key was supplied and resolves the other while it posts, so the browser
 * makes one round trip where a client-side lookup would make two and would need a second service's
 * client this screen does not depend on. The four sentences that resolution can raise are surfaced from
 * the response by {@link screenMessageForFailure}, and which of the account-flavoured or card-flavoured
 * pair applies is decided by the key this function reports.
 * @param {TransactionAddValues} values - Current field values as the operator keyed them.
 * @returns {{ failure: TransactionAddFieldError } | { key: 'accountId' | 'cardNumber' }} The refusal
 *   that stops the submission, or which of the two keys addresses it.
 */
export function keyFieldFailure(
  values: TransactionAddValues,
): { failure: TransactionAddFieldError } | { key: 'accountId' | 'cardNumber' } {
  if (!isBlankField(values.accountId)) {
    if (!isNumericField(values.accountId, TRANSACTION_ADD_FIELD_WIDTHS.accountId)) {
      return {
        failure: {
          field: 'accountId',
          message: ADD_MESSAGES.ACCOUNT_ID_MUST_BE_NUMERIC,
          state: 'NOT_OK',
        },
      };
    }
    return { key: 'accountId' };
  }

  if (!isBlankField(values.cardNumber)) {
    if (!isNumericField(values.cardNumber, TRANSACTION_ADD_FIELD_WIDTHS.cardNumber)) {
      return {
        failure: {
          field: 'cardNumber',
          message: ADD_MESSAGES.CARD_NUMBER_MUST_BE_NUMERIC,
          state: 'NOT_OK',
        },
      };
    }
    return { key: 'cardNumber' };
  }

  /*
   * WHY : Assumptions: the refusal names the ACCOUNT field even though neither key was supplied, and the
   *       state is blank rather than not-OK. The reference's `WHEN OTHER` branch moves -1 into
   *       `ACTIDINL` at `app/cbl/COTRN02C.cbl` L228, which is how a pseudo-conversational screen places
   *       the cursor, so the account field is where an operator is sent to correct this -- and it is
   *       genuinely empty, which is the condition `app/cpy/CSSETATY.cpy` L23-L26 nests the asterisk
   *       marker inside.
   */
  return {
    failure: {
      field: 'accountId',
      message: ADD_MESSAGES.ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED,
      state: 'BLANK',
    },
  };
}

/**
 * Runs the data-field chain and reports the first refusal, in the reference's own order.
 *
 * Assumptions: the sequence is eleven blank tests, then two numeric tests, then the amount shape, then
 * the originating-date shape, then the processing-date shape, and LAST the merchant-identifier numeric
 * test. That last position is the reference's and is preserved deliberately: the merchant test is not
 * part of the numeric `EVALUATE` at `app/cbl/COTRN02C.cbl` L322-L337 but a standalone `IF` at L430-L436
 * placed after BOTH date-utility calls, so it is the final check before the confirmation character is
 * read. Grouping it with the other two numeric tests would report it earlier than the reference does.
 *
 * Trade-offs: two of the reference's steps are missing from this chain and neither can be added here.
 * The amount's canonical re-render (L383-L386) and the two `CSUTLDTC` calendar evaluations (L389-L427)
 * belong to the service, so they arrive with a response rather than before the request. The consequence
 * is bounded and is stated rather than hidden: for a submission that BOTH names an unreal date such as
 * `2024-02-31` and carries a non-numeric merchant identifier, the reference reports the date and this
 * screen reports the merchant identifier, because the calendar verdict needs a round trip the browser
 * cannot make and the merchant test is the last thing checkable without one. Every other combination
 * reports the same field as the reference.
 * @param {TransactionAddValues} values - Current field values as the operator keyed them.
 * @returns {TransactionAddFieldError | null} The first refusal, or `null` when every checkable rule
 *   passes.
 */
export function dataFieldFailure(values: TransactionAddValues): TransactionAddFieldError | null {
  for (const [field, message] of BLANK_CHECK_SEQUENCE) {
    if (isBlankField(values[field])) {
      return { field, message, state: 'BLANK' };
    }
  }

  if (!isNumericField(values.typeCode, TRANSACTION_ADD_FIELD_WIDTHS.typeCode)) {
    return { field: 'typeCode', message: ADD_MESSAGES.TYPE_CD_MUST_BE_NUMERIC, state: 'NOT_OK' };
  }
  if (!isNumericField(values.categoryCode, TRANSACTION_ADD_FIELD_WIDTHS.categoryCode)) {
    return {
      field: 'categoryCode',
      message: ADD_MESSAGES.CATEGORY_CD_MUST_BE_NUMERIC,
      state: 'NOT_OK',
    };
  }

  /*
   * WHY : Assumptions: the sentence is the reference's format string verbatim,
   *       `Amount should be in format -99999999.99`, and it is neither localised nor reworded. It reads
   *       as guidance rather than as a refusal, and that is precisely why it must not be replaced by a
   *       locale-aware hint: the string IS the contract -- it states the sign, the eight integer digits,
   *       the point and the two decimals that `hasBaselineAmountShape` tests for -- and a golden-master
   *       comparison reads it character for character.
   */
  if (!hasBaselineAmountShape(values.amount)) {
    return {
      field: 'amount',
      message: ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99,
      state: 'NOT_OK',
    };
  }

  if (!hasBaselineIsoDateShape(values.originDate)) {
    return {
      field: 'originDate',
      message: ADD_MESSAGES.ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD,
      state: 'NOT_OK',
    };
  }
  if (!hasBaselineIsoDateShape(values.processDate)) {
    return {
      field: 'processDate',
      message: ADD_MESSAGES.PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD,
      state: 'NOT_OK',
    };
  }

  if (!isNumericField(values.merchantId, TRANSACTION_ADD_FIELD_WIDTHS.merchantId)) {
    return {
      field: 'merchantId',
      message: ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC,
      state: 'NOT_OK',
    };
  }

  return null;
}

/** Leading zeros the wire form drops, kept only where a digit follows so `0.00` survives. */
const LEADING_ZEROS = /^0+(?=[0-9])/u;

/** The four confirmation letters the contract admits on the wire, in both cases. */
const WIRE_CONFIRMATION = /^[YyNn]$/u;

/**
 * Converts the screen's edit-mask amount into the form the service contract accepts.
 *
 * Assumptions: the display form and the wire form are DIFFERENT and both are constrained. The screen
 * holds `+99999999.99` -- a mandatory sign and eight zero-filled integer digits -- while
 * `transaction-api.yaml` declares the amount as `^-?[0-9]{1,9}\.[0-9]{2}$`, which admits no plus sign
 * and no leading zeros. Sending the displayed value unchanged would be refused by the contract before
 * the service ever read it, so this conversion is required rather than cosmetic.
 *
 * Assumptions: the conversion runs through {@link toEditMaskAmount} first so that both callers agree on
 * one canonical value, which is also what makes negative zero collapse: that function emits a plus for
 * any all-zero amount, so `-00000000.00` reaches the wire as `0.00` rather than as `-0.00`.
 * @param {string} value - The amount as the operator keyed it, or as the mask displays it.
 * @returns {string | null} The contract's monetary form, or `null` when the value is not an amount this
 *   screen can express.
 */
export function toWireAmount(value: string): string | null {
  const masked = toEditMaskAmount(value);
  if (masked === null) {
    return null;
  }

  const negative = masked.startsWith('-');
  const [integerDigits = '', fractionDigits = ''] = masked.slice(1).split('.');
  const significantDigits = integerDigits.replace(LEADING_ZEROS, '');
  return `${negative ? '-' : ''}${significantDigits}.${fractionDigits}`;
}

/**
 * Builds the submission body from the screen's values and the key that addresses it.
 *
 * Assumptions: exactly ONE key member is sent, never both, and the contract requires precisely that --
 * `TransactionCreateRequest` declares both members optional under a pair of `required` alternatives, and
 * the service's own request record states that "whichever key is supplied, the other is a lookup result
 * rather than an input". Sending both would assert a pairing the browser has not verified and that the
 * cross-reference is the only authority on.
 *
 * Assumptions: the key is zero-filled to its declared width because the contract demands the full width
 * -- `^[0-9]{11}$` for an account identifier and `^[0-9]{16}$` for a card number -- and because the
 * reference's numeric `MOVE` back into the screen field produces the same padded value.
 *
 * Trade-offs: the descriptive fields are trimmed and the fixed-width padding a 3270 turn carried is not
 * reproduced. A trailing blank in a `PIC X(60)` description is padding rather than data, which is why
 * AAP section 0.4.1.3 maps descriptive character fields to `VARCHAR`, and sending the padding would
 * store bytes that differ from the same value keyed on a narrower field.
 * @param {TransactionAddValues} values - Current field values, already past {@link dataFieldFailure}.
 * @param {'accountId' | 'cardNumber'} key - Which key field addresses the submission.
 * @param {string} confirmation - The confirmation character as keyed; sent only when it is one of the
 *   four letters the contract admits, and omitted otherwise so an unconfirmed turn is spelled by absence.
 * @returns {TransactionCreateRequest | null} The body to submit, or `null` when the amount cannot be
 *   expressed on the wire, which the validation chain has already excluded.
 */
export function buildCreateRequest(
  values: TransactionAddValues,
  key: 'accountId' | 'cardNumber',
  confirmation: string,
): TransactionCreateRequest | null {
  const amount = toWireAmount(values.amount);
  if (amount === null) {
    return null;
  }

  const fields = {
    typeCode: values.typeCode.trim(),
    categoryCode: values.categoryCode.trim(),
    source: values.source.trim(),
    description: values.description.trim(),
    amount,
    originDate: values.originDate.trim(),
    processDate: values.processDate.trim(),
    merchantId: values.merchantId.trim(),
    merchantName: values.merchantName.trim(),
    merchantCity: values.merchantCity.trim(),
    merchantZip: values.merchantZip.trim(),
  };

  const addressed: TransactionCreateRequest =
    key === 'accountId'
      ? {
          ...fields,
          accountId: toZeroFilledKey(values.accountId, TRANSACTION_ADD_FIELD_WIDTHS.accountId),
        }
      : {
          ...fields,
          cardNumber: toZeroFilledKey(values.cardNumber, TRANSACTION_ADD_FIELD_WIDTHS.cardNumber),
        };

  const keyed = confirmation.trim();
  return WIRE_CONFIRMATION.test(keyed) ? { ...addressed, confirmation: keyed } : addressed;
}

/**
 * Reports whether a name the service attributed a refusal to is one of this screen's fields.
 *
 * Assumptions: the lookup is restricted to the table's OWN properties, and the restriction is
 * load-bearing rather than defensive style. The width table is a plain object, so it still inherits from
 * `Object.prototype`, and a bare index would resolve `"constructor"` and `"toString"` to functions and
 * `"__proto__"` to an object -- so a response naming one of those would otherwise narrow to a field this
 * screen would then try to focus.
 * @param {string} name - Field name as the service's problem document spells it.
 * @returns {boolean} `true` when the name is one of the fourteen fields, narrowing it to that union.
 */
export function isTransactionAddField(name: string): name is TransactionAddField {
  return Object.hasOwn(TRANSACTION_ADD_FIELD_WIDTHS, name);
}

/**
 * The refusals whose sentence this screen words itself rather than reading from the response.
 *
 * Assumptions: only the two date fields appear. Every other refusal the service can raise on this
 * request is a shape or class rule that {@link dataFieldFailure} already applied before dispatch, so
 * its sentence is one this screen chose; the calendar verdict is the one rule that needs the server,
 * because a real Gregorian calendar cannot be consulted from the shape alone -- `2022-02-30` satisfies
 * `hasBaselineIsoDateShape` and is still not a date. `app/cbl/COTRN02C.cbl` L389-L427 calls `CSUTLDTC`
 * for exactly that and words the outcome itself at L401 and L421.
 *
 * Assumptions: the calendar rule is NOT reimplemented here, and that is deliberate rather than an
 * omission, because the reference's rule is not "is this a date" -- it is narrower and stranger than
 * that. L389-L427 refuses a date only when the utility's severity is not `'0000'` AND its message
 * number is not `'2513'`, so one specific complaint is tolerated and its date accepted. That exemption
 * is a property of the utility's own return codes, not of the calendar, so a browser-side reimplementation
 * would have to hard-code a code it cannot observe and would drift the moment the utility changed.
 * `com.carddemo.common.validation.DateEditValidator` owns the rule including the exemption, which is
 * why this screen sends the dates and renders the verdict rather than second-guessing it.
 * Trade-offs: an unreal date therefore costs one round trip where a shape error costs none. That is
 * accepted because the alternative -- a second, approximate copy of the tolerance living in the SPA --
 * could reject a date the reference accepts, which is a parity failure rather than a latency cost.
 */
const CATALOG_OWNED_VERDICTS: Partial<Record<TransactionAddField, string>> = {
  originDate: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE,
  processDate: ADD_MESSAGES.PROC_DATE_NOT_A_VALID_DATE,
};

/**
 * Maps a problem document's per-field entries onto this screen's controls.
 *
 * Assumptions: entries naming something this screen does not render are DROPPED rather than rendered
 * loose, because there is no control to attach them to; the document's own sentence still reaches the
 * message band, so nothing is lost silently.
 *
 * Trade-offs: the array is kept whole where the reference has at most one. The service accumulates its
 * violations in one pass -- its request record documents that it "accumulates" where "the reference
 * short-circuits" -- so a response can legitimately name several fields, and discarding all but the
 * first would hide refusals the operator has to fix. The band still carries exactly one sentence, which
 * is the reference's observable behaviour, and the first entry is the one the cursor is moved to.
 *
 * Assumptions: a refusal naming either date field is rendered with THIS tree's catalog sentence rather
 * than the one the response carried, and the substitution is safe because only one refusal can reach
 * here. `app/cbl/COTRN02C.cbl` refuses a malformed date at L353-L381 and an unreal one at L389-L427, and
 * the shape half of that pair is already spent: {@link hasBaselineIsoDateShape} enforces exactly the
 * pattern the service's own contract declares, so a request that was dispatched at all cannot be
 * refused for its shape. What survives is the calendar verdict, whose two sentences the reference words
 * at L401 and L421. Pinning them locally makes `ui/src/messages/messages.ts` the sole owner of every
 * sentence this screen can paint -- the module states that role for itself -- so a reworded service
 * cannot change what the operator reads. The two strings agree today, which is why this changes no
 * observable text; it removes the dependency on their continuing to agree.
 * @param {ApiError} problem - The normalised problem document carried by a rejected request.
 * @returns {readonly TransactionAddFieldError[]} One entry per refusal this screen can render.
 */
export function resolveApiFieldErrors(problem: ApiError): readonly TransactionAddFieldError[] {
  const resolved: TransactionAddFieldError[] = [];
  for (const entry of problem.fieldErrors) {
    if (isTransactionAddField(entry.field)) {
      resolved.push({
        field: entry.field,
        message: CATALOG_OWNED_VERDICTS[entry.field] ?? entry.message,
        state: entry.state,
      });
    }
  }
  return resolved;
}

/** Which submission a failure came out of, so the sentence names the reference's own failure site. */
export interface TransactionAddFailureContext {
  /** Key field that addressed the submission, which selects the account- or card-flavoured sentence. */
  readonly key: 'accountId' | 'cardNumber';
  /** Whether the request was the copy-last action, whose reference site is the transaction browse. */
  readonly copying: boolean;
  /** Whether the request carried a confirming answer, so its reference site is the file write. */
  readonly writing: boolean;
}

/** What a rejected submission puts on the screen: one sentence, any field refusals, and where to focus. */
export interface TransactionAddFailureReport {
  /** Verbatim sentence for the message band. */
  readonly message: string;
  /** Refusals to render beneath the controls they name. */
  readonly fieldErrors: readonly TransactionAddFieldError[];
  /** Field to move the cursor to, the analogue of the reference's `MOVE -1 TO <field>L`. */
  readonly focus: TransactionAddField;
}

/** HTTP status the service answers when no account, card or transaction carries the key. */
const NOT_FOUND_STATUS = 404;

/** HTTP status the service answers when the assigned identifier is already present. */
const CONFLICT_STATUS = 409;

/** HTTP status the service answers when it refused a field. */
const BAD_REQUEST_STATUS = 400;

/**
 * Turns a rejected request into the sentence, refusals and cursor position the reference would show.
 *
 * Assumptions: the reference raises EIGHT different sentences across four failure sites, and one HTTP
 * status can stand in front of more than one of them, so the site is reconstructed from the context
 * rather than from the status alone. The account-flavoured and card-flavoured pairs are selected by the
 * key that addressed the submission, which is the same discrimination the reference makes by having read
 * either `CXACAIX` or `CCXREF`. The two transaction-browse sentences are reachable only from the
 * copy-last action, which is the migrated form of the `STARTBR`/`READPREV` pair at
 * `app/cbl/COTRN02C.cbl` L475-L478, and `Unable to Add Transaction...` only from a confirmed write,
 * which is the `WHEN OTHER` arm of `WRITE-TRANSACT-FILE` at L742-L748.
 *
 * Assumptions: a transport failure that carries no problem document is reported with the write's own
 * sentence when the turn was writing. The operator's question at that moment is whether the transaction
 * was added, and the reference answers it with exactly that sentence when the write did not succeed for
 * a reason it cannot name.
 * @param {unknown} failure - The rejected promise's reason, which is an `ApiRequestError` for every
 *   transport failure and may be any thrown value otherwise.
 * @param {TransactionAddFailureContext} context - Which submission produced it.
 * @returns {TransactionAddFailureReport} The sentence, the field refusals and the field to focus.
 */
export function screenMessageForFailure(
  failure: unknown,
  context: TransactionAddFailureContext,
): TransactionAddFailureReport {
  const addressedByAccount = context.key === 'accountId';

  if (!isApiRequestError(failure)) {
    return {
      message: context.copying
        ? SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION
        : ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION,
      fieldErrors: [],
      focus: context.key,
    };
  }

  const fieldErrors = resolveApiFieldErrors(failure.problem);
  const firstRefusal = fieldErrors[0];
  const focus = firstRefusal === undefined ? context.key : firstRefusal.field;

  if (failure.status === CONFLICT_STATUS) {
    return { message: SHARED_MESSAGES.TRAN_ID_ALREADY_EXIST, fieldErrors, focus };
  }

  if (failure.status === NOT_FOUND_STATUS) {
    /*
     * WHY : Assumptions: an empty ledger and an unknown key are both answered 404, and the copy-last
     *       contract says so explicitly -- it answers that status "for an unknown card or account or for
     *       an empty table with no row to copy". They are told apart by whether the document attributed
     *       the refusal to a field: a refusal naming a key field is a key that does not resolve, and one
     *       naming nothing on a copy is the browse finding no row, which is the reference's
     *       `Transaction ID NOT found...` at `app/cbl/COTRN02C.cbl` L655-L660.
     */
    if (context.copying && firstRefusal === undefined) {
      return { message: SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND, fieldErrors, focus };
    }
    return {
      message: addressedByAccount
        ? SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND
        : ADD_MESSAGES.CARD_NUMBER_NOT_FOUND,
      fieldErrors,
      focus,
    };
  }

  if (failure.status === BAD_REQUEST_STATUS && firstRefusal !== undefined) {
    /*
     * WHY : Assumptions: the band carries the FIRST refusal's own sentence rather than the document's
     *       summary line, because that is what the reference puts there. Every refusal in the reference
     *       moves its field's sentence into `WS-MESSAGE` and that one string reaches row 23, so the
     *       screen-level line and the field-level line are the same text -- which is also how a
     *       golden-master comparison of the message field reads.
     */
    return { message: firstRefusal.message, fieldErrors, focus };
  }

  if (failure.status === BAD_REQUEST_STATUS) {
    return {
      message: failure.problem.message ?? ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION,
      fieldErrors,
      focus,
    };
  }

  if (context.copying) {
    return { message: SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION, fieldErrors, focus };
  }

  if (context.writing) {
    return { message: ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION, fieldErrors, focus };
  }

  /*
   * WHY : Assumptions: an unconfirmed turn that fails for a reason the service did not attribute is
   *       reported as a cross-reference lookup failure, because that turn's work IS the lookup. The
   *       reference reaches these two sentences from the `WHEN OTHER` arms of `READ-CXACAIX-FILE` at
   *       `app/cbl/COTRN02C.cbl` L597-L603 and `READ-CCXREF-FILE` at L630-L636, which are exactly the
   *       reads a preview performs, and the write's own sentence would name an operation this turn never
   *       attempted.
   */
  return {
    message: addressedByAccount
      ? ADD_MESSAGES.UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE
      : ADD_MESSAGES.UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE,
    fieldErrors,
    focus,
  };
}

/** The two answers that confirm, both cases, as `app/cbl/COTRN02C.cbl` L170-L171 accepts them. */
const CONFIRMING_ANSWER = /^[Yy]$/u;

/** The two answers that decline, both cases, as `app/cbl/COTRN02C.cbl` L173-L174 accepts them. */
const DECLINING_ANSWER = /^[Nn]$/u;

/** Answer the confirmation modal sends when its primary control is used. */
const CONFIRM_MODAL_OK = 'Y';

/** Answer the confirmation modal sends when its secondary control is used. */
const CONFIRM_MODAL_CANCEL = 'N';

/**
 * One protected hint the mapset paints beside a field, with the colour role it paints it in.
 *
 * Assumptions: the tone is carried rather than fixed because this screen paints hints in TWO measured
 * colours: the three format hints on row 15 are `COLOR=BLUE` (`app/bms/COTRN02.bms` L208-L222) and the
 * confirmation's domain hint on row 21 is `COLOR=NEUTRAL` (L288-L292). A single hint style would collapse
 * a distinction the mapset makes.
 */
interface FieldHint {
  /** The literal, verbatim from the mapset. */
  readonly text: string;
  /** Measured BMS colour role, resolved to a token through `BMS_COLOR_TOKENS`. */
  readonly tone: keyof typeof BMS_COLOR_TOKENS;
}

/** Presentation options a field needs beyond its label, width and value. */
interface FieldPresentation {
  /** Protected format hint the mapset paints beneath the control, when it paints one. */
  readonly hint?: FieldHint;
  /** Whether this is the mapset's single `IC` field, which is the only one that takes initial focus. */
  readonly initialCursor?: boolean;
  /** Whether the control accepts digits only, which selects the numeric soft keyboard. */
  readonly numeric?: boolean;
  /** Whether the value is rendered in the fixed-pitch face so its columns align. */
  readonly fixedPitch?: boolean;
}

/**
 * Renders the transaction add screen.
 *
 * Assumptions: this screen takes no props. Every value it needs is either its own state or comes from a
 * hook -- there is no route parameter, because the reference reaches this screen with nothing selected
 * (`app/cbl/COTRN02C.cbl` L115-L130 opens on an empty map and places the cursor in the account field),
 * and no selection travels in the path the way it does for the card screens.
 *
 * Assumptions: this route is deliberately NOT admin-gated. `app/cpy/COMEN02Y.cpy` L68-L72 carries the
 * text `'Transaction Add (Admin Only)       '` as a COBOL COMMENT, with the asterisk in column 7, and the
 * live value on the next line is `'Transaction Add                    '` with no such suffix; every one
 * of the eleven main-menu options declares `CDEMO-MENU-OPT-USRTYPE PIC X(01) VALUE 'U'`. Adding an
 * administrator check here would therefore withhold from ordinary operators an option the reference menu
 * offers them, on the authority of a line the compiler never read.
 * @returns {ReactElement} The screen: the shared title band, the heading, the message band, the capture
 *   form with its confirmation control, and the function-key legend.
 * @throws {unknown} Nothing is thrown from render. Every submission failure is caught and rendered --
 *   a refused field as a per-control refusal, a rejected request through
 *   {@link screenMessageForFailure}, which covers HTTP 400 for a field the service refused, 404 for a
 *   key that does not resolve or an empty ledger with no row to copy, 409 for an identifier already
 *   present, and any other status or a transport failure as the reference's own unnameable-failure
 *   sentence.
 */
export function TransactionAddScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: the instant is read from a SERVER-anchored hook and not from `dayjs()`. The
   *       reference re-reads one region clock on every `SEND MAP` -- `POPULATE-HEADER-INFO` runs
   *       `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` at `app/cbl/COTRN02C.cbl` L554 -- so every
   *       terminal of a region saw the same wall clock. Falling through to the browser's clock would
   *       substitute both the instant and the zone, and two operators looking at one capture could read
   *       two different dates across midnight.
   */
  const paintedAt = useServerInstant();
  /*
   * WHY : Alternatives Considered: the `token` member of this same hook, which returns RESOLVED values --
   *       `colorInfo` comes back as a literal hex string. Rejected because writing a resolved value into
   *       a `style` attribute copies today's palette into the element and takes it off the CSS-variable
   *       surface antd 6 themes through, so a later token change leaves this one screen behind with
   *       nothing failing to say so. `cssVar` returns the `var(--...)` reference form of the same names.
   *       Assumptions: the names themselves come from `ui/src/theme/tokens.ts`, so no colour, spacing or
   *       font value is written in this file, and no `ConfigProvider` is instantiated here -- the theme is
   *       injected once by `ui/src/App.tsx` and this hook reads it.
   */
  const { cssVar } = theme.useToken();

  const [values, setValues] = useState<TransactionAddValues>(BLANK_VALUES);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly TransactionAddFieldError[]>([]);
  const [busy, setBusy] = useState(false);
  /*
   * WHY : Assumptions: this records that the LAST submission was a copy, and it exists because the
   *       migrated copy action replaces the eleven data members server-side. `app/cbl/COTRN02C.cbl` L495
   *       ends `COPY-LAST-TRAN-DATA` by performing `PROCESS-ENTER-KEY`, so a copy and the confirmation
   *       that follows it are two turns of one action, and the record written on the second turn is the
   *       COPIED one rather than whatever the screen fields held before. Routing the confirming turn back
   *       through the copy operation is what preserves that, and it is the property parity depends on.
   *       Trade-offs: the flag is cleared by any field edit, which is equally faithful -- once an operator
   *       changes a field, the reference's next Enter writes the screen's values rather than re-copying.
   */
  const [copyPending, setCopyPending] = useState(false);

  /*
   * WHY : Assumptions: one ref object holding a control per field, rather than fourteen separate refs.
   *       Focus is the browser analogue of the reference's `MOVE -1 TO <field>L`, which appears at
   *       fifteen sites in `app/cbl/COTRN02C.cbl` and can name any of the fields, so the destination is
   *       data rather than a fixed choice -- a lookup keyed by field name is what lets
   *       {@link screenMessageForFailure} return a field and have the cursor follow it.
   */
  const controls = useRef<Partial<Record<TransactionAddField, InputRef | null>>>({});

  /*
   * WHY : Assumptions: control identifiers are derived from a per-instance value rather than written as
   *       constants, because the identifier's only job is to bind a label to its control and a constant
   *       would collide if the shell ever rendered this screen twice -- behind a modal, for instance --
   *       leaving the duplicate label pointing at whichever control appeared first in the document.
   */
  const idPrefix = useId();

  /*
   * WHY : Refactoring Rationale: the cursor destination is held as STATE and applied by the effect
   *       below, rather than by calling `.focus()` at the point the refusal is decided. Calling it
   *       inline is the obvious shape and it silently loses the cursor twice over, both times because
   *       the call runs before React has committed the very render that the refusal causes.
   *       First: the blank marker is an antd Input `suffix`, and adding one changes the control's root
   *       element from a bare `input` to a `span.ant-input-affix-wrapper` that wraps a NEW `input`. The
   *       old node is unmounted, so a focus applied to it before the commit is discarded and the cursor
   *       falls back to `body`. That is invisible whenever the refused field is the account field --
   *       which carries `autoFocus` and is therefore re-focused on remount -- and shows up the moment a
   *       refusal moves to any OTHER field, which is exactly the case the reference's fifteen
   *       `MOVE -1 TO <field>L` sites exist to serve.
   *       Second: every control is `disabled` while a turn is in flight, and a submission that ends in
   *       a refusal clears that flag and names the field in the same handler. React batches the two, so
   *       an inline call would run while the element is still disabled, and a disabled element cannot
   *       take focus -- the browser refuses silently, with no error to notice.
   *       Trade-offs: this costs one extra render per cursor move, which is the price of the cursor
   *       actually landing where the reference puts it. Alternatives Considered: a `setTimeout` or
   *       `requestAnimationFrame` would also outlive the commit, but both leave the cursor position
   *       depending on a timer rather than on the render that caused it, so a slow commit would
   *       reintroduce the same bug non-deterministically.
   */
  const [pendingFocus, setPendingFocus] = useState<TransactionAddField | null>(null);

  /**
   * Requests the cursor move that mirrors the reference's symbolic cursor placement.
   *
   * Assumptions: the request is recorded rather than performed, because the control that must receive
   * the cursor may not exist yet -- see the block above. `app/cbl/COTRN02C.cbl` sets `MOVE -1 TO
   * <field>L` and only then sends the map, so the reference likewise decides the destination before the
   * screen carrying it is painted.
   * @param {TransactionAddField} field - Field the refusal names.
   * @returns {void} Completion is the recorded request; the effect below applies it after the commit.
   */
  function focusField(field: TransactionAddField): void {
    setPendingFocus(field);
  }

  useEffect(
    /**
     * Applies a recorded cursor move once the render that caused it has been committed.
     * @returns {void} Completion is the focused control; a field not currently rendered is a no-op,
     *   and the request is cleared either way so it can never be replayed on a later render.
     */
    function applyPendingFocus(): void {
      if (pendingFocus === null) {
        return;
      }
      controls.current[pendingFocus]?.focus();
      setPendingFocus(null);
    },
    [pendingFocus],
  );

  /**
   * Publishes one refused field as the reference would: one sentence, one marker and one cursor move.
   * @param {TransactionAddFieldError} failure - The refusal to render.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function reportFieldFailure(failure: TransactionAddFieldError): void {
    setMessage(failure.message);
    setSeverity('error');
    setFieldErrors([failure]);
    focusField(failure.field);
  }

  /**
   * Returns the screen to the state `INITIALIZE-ALL-FIELDS` leaves it in.
   *
   * Assumptions: this clears the message as well as the fields, because L779 lists `WS-MESSAGE` among the
   * items it moves spaces into, and it moves the cursor to the account field per L764.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearScreen(): void {
    setValues(BLANK_VALUES);
    setMessage(null);
    setSeverity('error');
    setFieldErrors([]);
    setCopyPending(false);
    focusField('accountId');
  }

  /**
   * Reads the confirmation character and reports what the reference does with it.
   *
   * Assumptions: a DECLINING answer and a BLANK one produce the SAME sentence, and that grouping is the
   * reference's own -- `app/cbl/COTRN02C.cbl` L173-L176 puts `'N'`, `'n'`, `SPACES` and `LOW-VALUES` in
   * one arm reached by fall-through, so all four ask for another turn with
   * `Confirm to add this transaction...`. Only a value that is none of those six spellings reaches the
   * `WHEN OTHER` arm at L182 and earns `Invalid value. Valid values are (Y/N)...`, which is why declining
   * cannot be reported as an invalid value.
   * @param {string} answer - The confirmation character as submitted.
   * @returns {string} The verbatim sentence for a non-confirming answer.
   */
  function unconfirmedSentence(answer: string): string {
    const keyed = answer.trim();
    return keyed === '' || DECLINING_ANSWER.test(keyed)
      ? ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION
      : SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N;
  }

  /*
   * WHY : Assumptions: the whole chain runs BEFORE the confirmation character is read, on every turn,
   *       including the turn that only declines. `PROCESS-ENTER-KEY` performs
   *       `VALIDATE-INPUT-KEY-FIELDS` and `VALIDATE-INPUT-DATA-FIELDS` at `app/cbl/COTRN02C.cbl`
   *       L166-L167 and evaluates `CONFIRMI` at L169 only afterwards, so a form with a blank type code
   *       and a blank confirmation reports the type code and never mentions the confirmation.
   * WHY : Refactoring Rationale: the unconfirmed turn is SENT rather than answered locally, which an
   *       earlier shape of this screen did not do. Two of the reference's steps live on that turn and
   *       neither can run in a browser: the amount is re-rendered through the `+99999999.99` mask after
   *       conversion (L383-L386) and each date is evaluated by `CSUTLDTC` (L389-L427). Answering locally
   *       would leave the operator looking at an un-normalised amount and would defer an unreal date such
   *       as `2024-02-31` to the confirming turn, so a date the reference refuses BEFORE asking for
   *       confirmation would instead be refused after it was given. The contract answers an unconfirmed
   *       submission 200 with nothing written, which is exactly a validation turn.
   */

  /**
   * Runs one turn: the validation chain, then the submission, then the confirmation evaluation.
   *
   * Assumptions: `copying` selects the copy-last operation, whose eleven data members the service
   * replaces from the stored record. The full chain still runs for it, and that is a documented
   * divergence rather than an oversight: the reference validates only the key fields before copying
   * (L473) because the copy then fills the eleven fields itself, whereas the migrated operation is
   * declared over the same request schema with all eleven members required, so a body that omitted them
   * would be refused by the contract before the service could replace them. The alternative -- sending
   * placeholder values to satisfy the shape -- would put invented data on the wire.
   * @param {string} answer - The confirmation character to submit, which decides preview against write.
   * @param {boolean} copying - Whether to submit the copy-last operation instead of the capture.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function runTurn(answer: string, copying: boolean): void {
    /*
     * WHY : Assumptions: a turn arriving while one is in flight is dropped. A terminal turn is serialised
     *       by the hardware -- a 3270 keyboard locks until the region replies -- so the reference needs no
     *       such guard, and without one here a doubled Enter could submit the same confirmed capture
     *       twice and write two transactions where the operator asked for one.
     */
    if (busy) {
      return;
    }

    const keyed = keyFieldFailure(values);
    if ('failure' in keyed) {
      reportFieldFailure(keyed.failure);
      return;
    }

    const refusal = dataFieldFailure(values);
    if (refusal !== null) {
      reportFieldFailure(refusal);
      return;
    }

    const request = buildCreateRequest(values, keyed.key, answer);
    if (request === null) {
      /*
       * WHY : Assumptions: unreachable in practice and handled anyway. `dataFieldFailure` has already
       *       accepted the amount's shape, so the wire conversion cannot fail -- but the two checks live
       *       in different functions, and a total handler here means a future change to either one
       *       surfaces the reference's own format sentence instead of dispatching a request with no body.
       */
      reportFieldFailure({
        field: 'amount',
        message: ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99,
        state: 'NOT_OK',
      });
      return;
    }

    const writing = CONFIRMING_ANSWER.test(answer.trim());
    setBusy(true);
    setMessage(null);
    setFieldErrors([]);

    const submission = copying ? copyLastTransaction(request) : addTransaction(request);
    submission.then(
      /**
       * Renders the outcome: the written capture's acknowledgement, or the normalised preview.
       * @param {TransactionAddOutcome} outcome - Which of the two outcomes the service reported.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (outcome: TransactionAddOutcome): void => {
        setBusy(false);

        if (outcome.outcome === 'CREATED') {
          /*
           * WHY : Assumptions: the fields are cleared BEFORE the acknowledgement is composed, and the
           *       order is the reference's -- `WRITE-TRANSACT-FILE` performs `INITIALIZE-ALL-FIELDS` at
           *       `app/cbl/COTRN02C.cbl` L725 and only then builds the sentence at L728-L733, so the
           *       operator is left on an empty form with the identifier of what they just wrote.
           * WHY : Assumptions: the severity is `success` and not the band's default. L727 moves `DFHGREEN`
           *       into the message field's colour attribute, which is the only place in this program that
           *       overrides the field's declared `COLOR=RED`, so this one sentence is green and every
           *       other sentence this screen shows is red.
           * WHY : Assumptions: the sentence is composed by the catalog template and not by string
           *       concatenation here. Its two literals are `'Transaction added successfully. '` and
           *       `' Your Tran ID is '` -- the first ends with a space and the second begins with one, so
           *       the rendered text carries TWO spaces between `successfully.` and `Your`. The template
           *       holds both literals as the `STRING` statement at L728-L732 declares them, and building
           *       the sentence here would be the one place that doubled space could be silently
           *       normalised away.
           */
          setValues(BLANK_VALUES);
          setCopyPending(false);
          setFieldErrors([]);
          setMessage(
            formatMessageTemplate(MESSAGE_TEMPLATES.TRANSACTION_ADDED_SUCCESSFULLY, {
              'TRAN-ID': outcome.created.transactionId,
            }),
          );
          setSeverity('success');
          focusField('accountId');
          return;
        }

        /*
         * WHY : Assumptions: the amount is written back from the PREVIEW and re-rendered through the
         *       screen's own mask, because the two forms differ. The service answers with its `Money`
         *       form -- unsigned when positive and never zero-filled -- while the field displays
         *       `+99999999.99`, so adopting the response verbatim would replace a twelve-character
         *       display value with a shorter one the field's own predicate would then refuse.
         */
        const normalised = toEditMaskAmount(outcome.preview.amount);
        if (normalised !== null) {
          setValues(
            /**
             * Replaces the amount with its canonical rendering, leaving every other field as keyed.
             * @param {TransactionAddValues} previous - Values as they stand.
             * @returns {TransactionAddValues} The same values with the amount re-rendered.
             */
            (previous: TransactionAddValues): TransactionAddValues => ({
              ...previous,
              amount: normalised,
            }),
          );
        }

        if (writing) {
          /*
           * WHY : Assumptions: a confirming answer that came back unwritten is reported as a failed
           *       write rather than read as a preview. The contract makes 201 the written outcome and 200
           *       the unwritten one, so this combination contradicts it, and falling through to the
           *       confirmation evaluation below would tell an operator who answered `Y` that their answer
           *       was invalid. `Unable to Add Transaction...` is the reference's own sentence for a write
           *       that did not happen for a reason it cannot name.
           */
          setSeverity('error');
          setMessage(ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION);
          focusField('accountId');
          return;
        }

        setCopyPending(copying);
        setSeverity('error');
        setMessage(unconfirmedSentence(answer));
        focusField('confirmation');
      },
      /**
       * Renders a rejected submission as the reference's own sentence for that failure site.
       * @param {unknown} failure - The rejection reason, an `ApiRequestError` for a transport failure.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        setBusy(false);
        const report = screenMessageForFailure(failure, { key: keyed.key, copying, writing });
        setSeverity('error');
        setMessage(report.message);
        setFieldErrors(report.fieldErrors);
        focusField(report.focus);
      },
    );
  }

  /**
   * Submits with a confirmation the modal supplied, which is the mouse form of keying it.
   * @param {string} answer - The answer the modal stands for, one of the two the domain hint names.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function answerConfirmation(answer: string): void {
    setValues(
      /**
       * Records the answer in the confirmation field so the screen shows what was submitted.
       * @param {TransactionAddValues} previous - Values as they stand.
       * @returns {TransactionAddValues} The same values carrying the supplied answer.
       */
      (previous: TransactionAddValues): TransactionAddValues => ({
        ...previous,
        confirmation: answer,
      }),
    );
    runTurn(answer, copyPending);
  }

  /*
   * WHY : Assumptions: exactly FOUR keys are registered, and the absence of PF7, PF8 and PF12 is
   *       measured rather than assumed. `app/cbl/COTRN02C.cbl` L133-L152 evaluates `EIBAID` with arms for
   *       `DFHENTER`, `DFHPF3`, `DFHPF4` and `DFHPF5` and a `WHEN OTHER` that refuses everything else, and
   *       the row-24 legend paints those same four. Registering a paging key here would answer a keystroke
   *       the reference refuses -- and `usePfKeys` routes an unregistered key to the invalid-key channel,
   *       which is exactly the coercion the `WHEN OTHER` arm performs.
   * WHY : Assumptions: PF13 through PF24 need no handling here. `app/cpy/CSSTRPFY.cpy` L54-L77 aliases them
   *       onto PF01 through PF12, and `usePfKeys` already applies that table through `PF_KEY_ALIASES`, so
   *       re-implementing the aliasing in this screen would create a second copy of one mapping.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /**
       * Runs one turn with the confirmation as keyed, which is the reference's Enter arm.
       * @returns {void} Completion is represented by the screen's own state.
       */
      onInvoke: (): void => {
        runTurn(values.confirmation, copyPending);
      },
      label: TRANSACTION_ADD_KEY_LABELS.ENTER,
    },
    PFK03: {
      /**
       * Returns to the main menu, which is where the reference sends an operator with no recorded caller.
       *
       * Assumptions: the destination is the main menu and not the sign-on screen. `app/cbl/COTRN02C.cbl`
       * L137-L142 moves `'COMEN01C'` into the target program when the caller is blank and otherwise
       * returns to the caller, and the browser's own history is what carries the caller here -- so the
       * menu is the fallback this arm expresses.
       * @returns {void} Completion is a route change.
       */
      onInvoke: (): void => {
        navigateSafely(navigate, MAIN_MENU_ROUTE);
      },
      label: TRANSACTION_ADD_KEY_LABELS.PFK03,
    },
    PFK04: {
      /**
       * Clears every field, the confirmation and the message, which is the reference's PF4 arm.
       * @returns {void} Completion is represented by the screen's own state.
       */
      onInvoke: clearScreen,
      label: TRANSACTION_ADD_KEY_LABELS.PFK04,
    },
    PFK05: {
      /**
       * Copies the stored last transaction and falls straight through into the Enter processing.
       *
       * Assumptions: the fall-through is the reference's, not an addition. `COPY-LAST-TRAN-DATA` ends with
       * `PERFORM PROCESS-ENTER-KEY` at `app/cbl/COTRN02C.cbl` L495, so pressing this key with the
       * confirmation still blank lands on `Confirm to add this transaction...` rather than on a screen
       * that merely filled itself in.
       * @returns {void} Completion is represented by the screen's own state.
       */
      onInvoke: (): void => {
        runTurn(values.confirmation, true);
      },
      label: TRANSACTION_ADD_KEY_LABELS.PFK05,
    },
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /**
     * Reports the shared invalid-key sentence and returns the cursor to the initial-cursor field.
     *
     * Assumptions: the sentence is the shared `CCDA-MSG-INVALID-KEY`, which the `WHEN OTHER` arm moves
     * into the message at `app/cbl/COTRN02C.cbl` L150 before re-sending the screen. Its trailing spaces are
     * part of the declared `PIC X(50)` width and the catalog carries them, so the value is passed on
     * unchanged rather than trimmed.
     *
     * Assumptions: the cursor goes to the account field, which is where the reference leaves it. That arm
     * sets no field's length to -1, and `SEND-TRNADD-SCREEN` sends with `CURSOR` and no value at
     * L522-L528, so symbolic positioning falls back to the mapset's single `IC` attribute -- which
     * `app/bms/COTRN02.bms` L85 declares on the account field and nowhere else.
     * @returns {void} Completion is represented by the screen's own state.
     */
    onInvalidKey: (): void => {
      setSeverity('error');
      setMessage(INVALID_KEY_PRESSED);
      focusField('accountId');
    },
  });

  const titleStyle: CSSProperties = {
    color: cssVar[BMS_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const labelStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.TURQUOISE] };
  const neutralStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.NEUTRAL] };
  const fixedPitchStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  const ruleStyle: CSSProperties = { ...neutralStyle, ...fixedPitchStyle };
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };

  /**
   * Records one field's value, discarding any pending copy because the operator has taken over.
   *
   * Assumptions: an edit clears the copy state for the reason the state itself records -- after the
   * reference copies, the fields hold the copied values and the next Enter writes whatever the fields
   * hold, so an operator who changes one has changed what gets written. Keeping the flag would send the
   * confirming turn back through the copy operation and silently discard the edit.
   *
   * Assumptions: the refusal marker and the message are NOT cleared here. AAP section 0.7.1 removes the
   * re-entry discriminator the reference gated its highlighting on, and the replacement is that the error
   * state is driven purely by the response body -- so it changes when a turn produces a new one, exactly
   * as row 23 and the field attributes only changed on a `SEND MAP`.
   * @param {TransactionAddField} field - Field being edited.
   * @returns {(event: ChangeEvent<HTMLInputElement>) => void} Change handler for that field's control.
   */
  function changeHandler(
    field: TransactionAddField,
  ): (event: ChangeEvent<HTMLInputElement>) => void {
    /**
     * Stores the control's current value against its field.
     * @param {ChangeEvent<HTMLInputElement>} event - Change event carrying the edited value.
     * @returns {void} Completion is represented by the screen's own state.
     */
    return (event: ChangeEvent<HTMLInputElement>): void => {
      const edited = event.target.value;
      setValues(
        /**
         * Replaces one field's value, leaving the rest as they stand.
         * @param {TransactionAddValues} previous - Values as they stand.
         * @returns {TransactionAddValues} The same values carrying the edit.
         */
        (previous: TransactionAddValues): TransactionAddValues => ({
          ...previous,
          [field]: edited,
        }),
      );
      setCopyPending(false);
    };
  }

  /**
   * Registers one control so a refusal can move the cursor to it.
   * @param {TransactionAddField} field - Field the control renders.
   * @returns {(control: InputRef | null) => void} Callback ref that records the control.
   */
  function registerControl(field: TransactionAddField): (control: InputRef | null) => void {
    /**
     * Records the mounted control, or forgets it on unmount.
     * @param {InputRef | null} control - The control, or `null` while it is being detached.
     * @returns {void} Completion is the updated lookup.
     */
    return (control: InputRef | null): void => {
      controls.current[field] = control;
    };
  }

  /**
   * Renders one editable field with its label, width, refusal state and hint.
   *
   * Assumptions: every field goes through this one function, so the label association, the width, the
   * refusal rendering and the cursor registration cannot differ between fields. Fourteen hand-written
   * blocks would be fourteen chances for one of those four to be omitted, and the omission that matters
   * most is the width: it is the copybook picture and the screen tests assert every one of them.
   *
   * Assumptions: the label is bound to the control with an explicit identifier rather than left to the
   * form to infer, because these controls are managed by this screen rather than by the form store. The
   * association is what lets an operator using a screen reader hear the mapset's own label, and what lets
   * the screen tests find a control by the label literal.
   * @param {TransactionAddField} field - Field to render.
   * @param {FieldPresentation} presentation - Hint, initial-cursor, numeric and fixed-pitch options.
   * @returns {ReactElement} The labelled control, sized to share its row with its siblings.
   */
  function renderField(
    field: TransactionAddField,
    presentation: FieldPresentation = {},
  ): ReactElement {
    const refusal = fieldErrors.find(
      /**
       * Selects the refusal naming this field, if the last turn produced one.
       * @param {TransactionAddFieldError} entry - One refusal from the last turn.
       * @returns {boolean} `true` when the refusal names this field.
       */
      (entry: TransactionAddFieldError): boolean => entry.field === field,
    );
    const controlId = `${idPrefix}${field}`;

    return (
      <Flex key={field} flex="1 1 0" vertical>
        <Form.Item
          label={
            <Typography.Text style={labelStyle}>
              {TRANSACTION_ADD_FIELD_LABELS[field]}
            </Typography.Text>
          }
          htmlFor={controlId}
          {...(refusal === undefined
            ? {}
            : { validateStatus: 'error' as const, help: refusal.message })}
          {...(presentation.hint === undefined
            ? {}
            : {
                extra: (
                  <Typography.Text
                    style={{ color: cssVar[BMS_COLOR_TOKENS[presentation.hint.tone]] }}
                  >
                    {presentation.hint.text}
                  </Typography.Text>
                ),
              })}
        >
          <Input
            id={controlId}
            ref={registerControl(field)}
            value={values[field]}
            maxLength={TRANSACTION_ADD_FIELD_WIDTHS[field]}
            onChange={changeHandler(field)}
            disabled={busy}
            autoFocus={presentation.initialCursor === true}
            {...(presentation.numeric === true ? { inputMode: 'numeric' as const } : {})}
            {...(presentation.fixedPitch === true ? { style: fixedPitchStyle } : {})}
            {...(refusal?.state === 'BLANK'
              ? {
                  suffix: (
                    <Typography.Text aria-hidden="true" style={blankMarkerStyle}>
                      {FIELD_ERROR_TOKENS.blankMarker}
                    </Typography.Text>
                  ),
                }
              : {})}
          />
        </Form.Item>
      </Flex>
    );
  }

  /*
   * WHY : Trade-offs: the mapset's absolute geometry is NOT reproduced, and this is AAP gap G1 taken
   *       deliberately. `DFHMDI SIZE=(24,80)` fixes a 24-row by 80-column cell grid and all 61 field
   *       definitions carry an absolute `POS=(row,column)`, so a faithful rendering would need character
   *       cells at fixed coordinates. What is preserved is what survives translation: the GROUPING, the
   *       READING ORDER and the TAB ORDER. Each row below is one of the mapset's own rows -- 6 for the two
   *       key alternatives, 10 for the type, category and source, 12 for the description, 14 for the
   *       amount and the two dates, 16 and 18 for the merchant, 21 for the confirmation -- and the fields
   *       appear within each row in ascending column order, which is the order an operator tabbed through
   *       them. What is given up is pixel-for-character positioning, which no browser can hold across
   *       viewport widths and which would be hostile to an operator using a screen reader or magnification.
   * WHY : Alternatives Considered: `Row` and `Col` with a `gutter`, which is antd's idiomatic form grid and
   *       is what an earlier draft of this screen used. Rejected because `gutter` takes a PIXEL NUMBER, and
   *       AAP section 0.3.2 admits only values that resolve to a design token -- so the idiomatic choice
   *       would have put a hardcoded spacing literal on every row. `Flex` takes antd's semantic sizes,
   *       which resolve through the theme's spacing scale, and its `flex` prop lets each field share its
   *       row without a width literal.
   */
  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={TRANSACTION_ADD_TRANSACTION_ID}
        programName={TRANSACTION_ADD_PROGRAM_NAME}
        now={paintedAt}
      />
      {/*
       * Assumptions: heading level four rather than any other, because the token bridge maps a screen
       * title to `fontSizeHeading4` and `lineHeightHeading4`, and `Typography.Title level={4}` is the
       * component that resolves to exactly those two tokens. The field's `ATTRB=(ASKIP,BRT)` is carried by
       * the weight token rather than by a colour, which is what keeps brightness and colour independent
       * the way the mapset has them -- this heading is also `COLOR=NEUTRAL`.
       */}
      <Typography.Title level={4} style={titleStyle}>
        {TRANSACTION_ADD_TITLE}
      </Typography.Title>
      {/*
       * Assumptions: the band is placed here, above the form, which is where every authored screen in
       * this tree puts it, and it is sized from the mapset rather than from the route. The reference paints
       * its message on row 23 below the fields; the band reserves its space at all times either way, so
       * the reading order changes and the layout stability the reserved space exists for does not.
       */}
      <MessageBand message={message} severity={severity} mapset={TRANSACTION_ADD_MAPSET} />
      <Form layout="vertical">
        <Flex gap="middle" wrap align="flex-start">
          {renderField('accountId', { initialCursor: true, numeric: true, fixedPitch: true })}
          {/*
           * Assumptions: this word is NOT decorative and is therefore not hidden from assistive
           * technology, unlike the rule below. It states that the two key fields are alternatives, which
           * is the whole of the ordered branch at `app/cbl/COTRN02C.cbl` L195-L230, so an operator who
           * cannot see it needs it read to them.
           */}
          <Typography.Text style={neutralStyle}>
            {TRANSACTION_ADD_ALTERNATIVE_KEY_LABEL}
          </Typography.Text>
          {renderField('cardNumber', { numeric: true, fixedPitch: true })}
        </Flex>
        {/*
         * Assumptions: the rule is hidden from assistive technology because it is the one piece of pure
         * decoration on this screen -- seventy hyphens conveying a section boundary that the grouping
         * already conveys structurally -- and announcing it would read seventy characters aloud. It is
         * rendered in the fixed-pitch face so that its declared seventy characters occupy seventy
         * character widths, which is the only sense in which its length means anything.
         */}
        <Typography.Text aria-hidden="true" style={ruleStyle}>
          {TRANSACTION_ADD_RULE}
        </Typography.Text>
        <Flex gap="middle" wrap align="flex-start">
          {renderField('typeCode', { numeric: true, fixedPitch: true })}
          {renderField('categoryCode', { numeric: true, fixedPitch: true })}
          {renderField('source')}
        </Flex>
        <Flex gap="middle" wrap align="flex-start">
          {renderField('description')}
        </Flex>
        <Flex gap="middle" wrap align="flex-start">
          {/*
           * Assumptions: the amount carries the mapset's own format hint and is rendered in the
           * fixed-pitch face, and both follow from it being money. The hint is the reference's
           * `(-99999999.99)` at `app/bms/COTRN02.bms` L208-L212, and the face is what keeps the sign, the
           * eight integer digits and the two decimals in the same columns from one capture to the next.
           */}
          {renderField('amount', {
            hint: { text: TRANSACTION_ADD_FORMAT_HINTS.amount, tone: 'BLUE' },
            fixedPitch: true,
          })}
          {renderField('originDate', {
            hint: { text: TRANSACTION_ADD_FORMAT_HINTS.originDate, tone: 'BLUE' },
            fixedPitch: true,
          })}
          {renderField('processDate', {
            hint: { text: TRANSACTION_ADD_FORMAT_HINTS.processDate, tone: 'BLUE' },
            fixedPitch: true,
          })}
        </Flex>
        <Flex gap="middle" wrap align="flex-start">
          {renderField('merchantId', { numeric: true, fixedPitch: true })}
          {renderField('merchantName')}
        </Flex>
        <Flex gap="middle" wrap align="flex-start">
          {renderField('merchantCity')}
          {renderField('merchantZip')}
        </Flex>
        <Flex gap="middle" wrap align="flex-end">
          {/*
           * Assumptions: the single-character control is RETAINED rather than replaced by the modal, and
           * that is what keeps `Invalid value. Valid values are (Y/N)...` reachable. The modal can only
           * produce the two answers its controls stand for, so a screen with no keyed field would have no
           * way to submit a third character and the reference's `WHEN OTHER` sentence at
           * `app/cbl/COTRN02C.cbl` L182-L187 would become dead text.
           * Assumptions: the domain hint is `COLOR=NEUTRAL` at `app/bms/COTRN02.bms` L288-L292, unlike the
           * three blue format hints above, so it resolves to a different token and the difference is the
           * mapset's rather than an inconsistency here.
           */}
          {renderField('confirmation', {
            hint: { text: TRANSACTION_ADD_CONFIRM_DOMAIN_HINT, tone: 'NEUTRAL' },
          })}
          {/*
           * WHY : Refactoring Rationale: the modal replaces the re-key-to-confirm convention, which exists
           *       in the reference only because a 3270 had no modal to raise -- a whole screen turn plus a
           *       keyed character was the cheapest confirmation the terminal could express. Its two
           *       controls are labelled with the two characters the mapset's own `(Y/N)` field names, so
           *       the modal introduces no text the baseline does not hold, and its question is the
           *       reference's own row-21 prompt for the same reason: this screen has exactly one
           *       confirmation sentence and inventing a second would breach the verbatim-text rule.
           * WHY : Assumptions: both this control and the legend's ENTER control dispatch the SAME
           *       function, so the two cannot diverge. The legend control exists because the reference
           *       paints the key; this one exists because a browser operator has a mouse and the re-key
           *       convention has no mouse analogue.
           * WHY : Trade-offs: the primary emphasis is deliberate and `okType="danger"` is NOT used, though
           *       the design-system mapping pairs it with `Popconfirm` for a destructive confirmation.
           *       This action adds a record rather than removing one, and the mapset agrees: it paints the
           *       prompt `COLOR=TURQUOISE` and the field `COLOR=GREEN`, with no warning or error colour
           *       anywhere near either, so danger emphasis would contradict the measured source.
           */}
          <Popconfirm
            title={TRANSACTION_ADD_FIELD_LABELS.confirmation}
            {...(isBlankField(values.cardNumber)
              ? {}
              : {
                  /*
                   * WHY : Assumptions: the card number is MASKED to its last four digits here, and this is
                   *       the one place on this screen a card number is displayed rather than keyed. AAP
                   *       section 0.4.1.9 reduces a primary account number everywhere except the
                   *       administrative card-detail endpoint, and a confirmation modal is not that. The
                   *       card-number INPUT above is deliberately left unmasked, because masking a field an
                   *       operator is editing would hide their own keystrokes and make a correction
                   *       impossible.
                   * WHY : Assumptions: the summary is composed from labels this module already publishes and
                   *       from values already on the screen, so it introduces no string the baseline does not
                   *       hold. It exists because the modal replaces a blind re-key: the reference's
                   *       confirmation turn redisplays the whole populated map, so the operator confirming
                   *       could see what they were committing, and a modal that showed only a question would
                   *       take that away.
                   */
                  description: (
                    <Flex vertical>
                      <Typography.Text style={fixedPitchStyle}>
                        {`${TRANSACTION_ADD_FIELD_LABELS.cardNumber} ${maskCardNumber(values.cardNumber)}`}
                      </Typography.Text>
                      <Typography.Text style={fixedPitchStyle}>
                        {`${TRANSACTION_ADD_FIELD_LABELS.amount} ${values.amount}`}
                      </Typography.Text>
                    </Flex>
                  ),
                })}
            okText={CONFIRM_MODAL_OK}
            cancelText={CONFIRM_MODAL_CANCEL}
            disabled={busy}
            onConfirm={
              /**
               * Submits a confirming answer, which writes the capture.
               * @returns {void} Completion is represented by the screen's own state.
               */
              (): void => {
                answerConfirmation(CONFIRM_MODAL_OK);
              }
            }
            onCancel={
              /**
               * Submits a declining answer, which the reference answers by asking again.
               * @returns {void} Completion is represented by the screen's own state.
               */
              (): void => {
                answerConfirmation(CONFIRM_MODAL_CANCEL);
              }
            }
          >
            <Button type="primary" loading={busy}>
              {TRANSACTION_ADD_TITLE}
            </Button>
          </Popconfirm>
        </Flex>
      </Form>
      {/*
       * Assumptions: the legend colour is left at the bar's default, which `app/bms/COTRN02.bms`
       * L297-L302 confirms -- the row-24 field is `COLOR=YELLOW`, the majority the bar already defaults
       * to -- so passing it would restate a default rather than record a decision.
       */}
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

/*
 * WHY : Assumptions: this module publishes the component under BOTH keys, and each has a caller. The
 *       DEFAULT export is the shape this file's own contract fixes, and the NAMED export is the shape
 *       `ui/src/router.tsx` consumes -- its lazy loaders read a named export off the imported module and
 *       republish it under `default`, which is the only shape `React.lazy` accepts, so the three card
 *       screens are all reached that way. Publishing one alias of one component satisfies both without
 *       creating the re-export barrel the screen conventions forbid, and there is only ever one component
 *       to keep in step because the second key is the same binding rather than a second declaration.
 */
export default TransactionAddScreen;
