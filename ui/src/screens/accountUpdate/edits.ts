/**
 * @file The account-update screen's own field edits, transcribed from `app/cbl/COACTUPC.cbl`.
 *
 * Purpose
 * -------
 * Re-express `1200-EDIT-MAP-INPUTS` (L1429-L1678) as one pure function over the screen's form values:
 * twenty-four field edits in the reference's own order, its first-message-wins precedence, the
 * per-field refusal flags that drive the red highlight and the blank marker, and the state-and-ZIP
 * cross-field edit that runs last.
 *
 * Why this exists
 * ---------------
 * Refactoring Rationale: the screen previously ran NO edits of its own. Enter performed change
 * detection and moved straight to the confirmation turn, so every refusal appeared one turn later, on
 * the F5 write turn, out of the reference's order and with the operator having already confirmed
 * values the screen had not checked. The argument recorded for that was that re-implementing the edits
 * "would put one rule in two places". It is the wrong reading of a two-place rule: the reference ALSO
 * has the rule in two places -- the screen edits on Enter at L1429 and the service validates again
 * before writing -- and that redundancy is the point, because the screen's copy exists to refuse a
 * turn before it costs a write and to place the cursor on the offending field. The service remains the
 * final authority: nothing here is trusted by the write, and a refusal the service raises still
 * reaches the band through the response's own field errors.
 *
 * What is here and what is not
 * ----------------------------
 * Assumptions: every edit that needs no reference data is here and is complete. Three checks need data
 * the browser does not hold and are NOT reproduced locally: the fifty-one-entry state-code list and the
 * two-hundred-and-forty-entry state-and-ZIP-prefix list that `app/cpy/CSLKPCDY.cpy` compiles into the
 * program, and the North American general-purpose area-code list the phone edit consults. The first two
 * are supplied to this module as ALREADY-RESOLVED answers -- see {@link ReferenceAnswers} -- because the
 * screen can ask the reference service for exactly the two keys it needs. The third is left to the
 * service, which owns the same list in `us_phone_area_codes` and refuses a bad code on the write turn;
 * shipping two hundred codes into the browser to move one refusal one turn earlier is not a trade this
 * screen needs to make, and the structural half of the phone edit -- supplied, three digits, non-zero
 * -- is reproduced here in full.
 *
 * Assumptions: this module holds no user-visible string of its own. Every label comes from
 * `ACCOUNT_UPDATE_FIELD_LABELS` and every suffix from `FIELD_VALIDATION_SUFFIXES`, joined by
 * `formatFieldValidationMessage`, which is the same `STRING FUNCTION TRIM(...) <suffix>` the reference
 * performs at each refusal site.
 */

import type { FieldError } from '../../api/types';
import {
  ACCOUNT_UPDATE_FIELD_LABELS,
  FIELD_VALIDATION_SUFFIXES,
  MESSAGE_TEMPLATES,
  formatMessageTemplate,
  formatFieldValidationMessage,
} from '../../messages/messages';

import type { AccountUpdateFieldName, AccountUpdateFormValues } from './index';

/**
 * The two lookup answers this module cannot compute, resolved by the caller.
 *
 * Assumptions: each member is three-valued and the third value is load-bearing. `true` and `false` are
 * the reference's own outcomes; `null` means the answer could not be obtained -- the service was
 * unreachable, or the caller did not ask because the field failed its structural edit first, which is
 * the `IF FLG-ALPHA-ISVALID` guard at `app/cbl/COACTUPC.cbl` L1601 and the
 * `IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID` guard at L1665-L1667. An unobtainable answer must not
 * refuse the turn: the service validates the same two keys before it writes, so the cost of not knowing
 * here is one turn's delay and never a wrong refusal.
 */
export interface ReferenceAnswers {
  /** Whether the state code is one the reference data holds, or `null` when unknown. */
  readonly stateCodeIsKnown: boolean | null;
  /** Whether the state and the ZIP's first two digits combine, or `null` when unknown. */
  readonly stateZipCombinationIsKnown: boolean | null;
}

/**
 * The outcome of one pass of the edit chain.
 *
 * Assumptions: the refusals are ORDERED, in the reference's own execution order, and the order is what
 * carries the first-message-wins rule -- `refusals[0]` is the sentence row 23 shows and the field the
 * cursor goes to. Every refusal is also reported, not just the first, because the reference colours
 * every refused field: `3300-SETUP-SCREEN-ATTRS` reads each field's own flag independently of which one
 * claimed the message.
 */
export interface EditChainOutcome {
  /** Refusals in execution order; empty when the chain accepted every field. */
  readonly refusals: readonly FieldError[];
  /** The sentence to show, or `null` when nothing was refused. */
  readonly message: string | null;
  /** Whether the state code passed its structural edit, which gates the state-code lookup. */
  readonly stateCodeStructurallyValid: boolean;
  /** Whether the ZIP passed its structural edit, which with the above gates the combination lookup. */
  readonly zipCodeStructurallyValid: boolean;
}

/** Lowest century the reference accepts, `88 LAST-CENTURY VALUE 19` at `app/cpy/CSUTLDWY.cpy` L10. */
const EARLIEST_CENTURY = 19;

/** Highest century the reference accepts, `88 THIS-CENTURY VALUE 20` at `app/cpy/CSUTLDWY.cpy` L9. */
const LATEST_CENTURY = 20;

/** Months with thirty-one days, `88 WS-31-DAY-MONTH` at `app/cpy/CSUTLDWY.cpy` L21-L23. */
const THIRTY_ONE_DAY_MONTHS: readonly number[] = [1, 3, 5, 7, 8, 10, 12];

/** February, `88 WS-FEBRUARY VALUE 2` at `app/cpy/CSUTLDWY.cpy` L24. */
const FEBRUARY = 2;

/** Lowest accepted credit score, `88 FICO-RANGE-IS-VALID VALUES 300` at `app/cbl/COACTUPC.cbl` L848. */
const LOWEST_CREDIT_SCORE = 300;

/** Highest accepted credit score, the upper bound of the same `88` level. */
const HIGHEST_CREDIT_SCORE = 850;

/** Declared width of each national-identifier part, from the three `MOVE`s at L2439, L2467 and L2479. */
const IDENTIFIER_PART_WIDTHS = { part1: 3, part2: 2, part3: 4 } as const;

/** Declared width of a telephone area or prefix code. */
const TELEPHONE_CODE_WIDTH = 3;

/** Declared width of a telephone line number. */
const TELEPHONE_LINE_WIDTH = 4;

/** Number of leading ZIP digits the state combination is keyed on, `ZIP(1:2)` at L2540. */
const ZIP_PREFIX_WIDTH = 2;

/**
 * National-identifier first parts the reference refuses outright.
 *
 * Assumptions: the set is `88 INVALID-SSN-PART1 VALUES 0, 666, 900 THROUGH 999` at
 * `app/cbl/COACTUPC.cbl` L121, compared as NUMBERS there because the field is redefined `PIC 9(3)`.
 * It is compared as a number here too, so `000` and `0` reach the same branch as the reference's
 * numeric comparison does.
 * @param {number} value - The part's numeric value.
 * @returns {boolean} `true` when the reference refuses this value.
 */
function isRefusedIdentifierPart(value: number): boolean {
  return value === 0 || value === 666 || (value >= 900 && value <= 999);
}

/**
 * Reports whether a year is a leap year by the reference's own arithmetic.
 *
 * Assumptions: the rule is transcribed from `app/cpy/CSUTLDPY.cpy` L235-L246 exactly, INCLUDING its
 * unusual shape: it divides by 400 when the two-digit year part is zero and by 4 otherwise. That is not
 * the textbook rule written differently -- it is the textbook rule arrived at from the other side, and
 * it gives the same answers over the two centuries the year edit admits. 2000 divides by 400 and is a
 * leap year; 1900 does not and is not; 2024 divides by 4 and is. Rewriting it as the familiar
 * three-clause rule would agree on every year this screen can hold and would stop being a transcription.
 * @param {number} year - The four-digit year.
 * @returns {boolean} `true` when February has twenty-nine days in that year.
 */
function isLeapYear(year: number): boolean {
  const divisor = year % 100 === 0 ? 400 : 4;
  return year % divisor === 0;
}

/**
 * Builds one refusal against a named control.
 * @param {AccountUpdateFieldName} field - The control the refusal marks.
 * @param {string} label - The reference's own name for the field, from the catalogue.
 * @param {string} suffix - The refusal suffix, from the catalogue.
 * @param {'NOT_OK' | 'BLANK'} state - Which of the reference's two flags this refusal sets.
 * @returns {FieldError} The refusal, shaped as the contract's own field error so the screen's existing
 *   indexing, marking and cursor code consumes it unchanged.
 */
function refusal(
  field: AccountUpdateFieldName,
  label: string,
  suffix: string,
  state: 'NOT_OK' | 'BLANK',
): FieldError {
  return { field, state, message: formatFieldValidationMessage(label, suffix) };
}

/**
 * Applies `1215-EDIT-MANDATORY` (L1824-L1852): the value must not be blank.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the field.
 * @param {string} value - The entry as the control holds it.
 * @returns {FieldError | null} The refusal, or `null` when the value may be accepted.
 */
function editMandatory(
  field: AccountUpdateFieldName,
  label: string,
  value: string,
): FieldError | null {
  return value.trim() === ''
    ? refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED, 'BLANK')
    : null;
}

/**
 * Applies `1220-EDIT-YESNO` (L1856-L1894): blank, then a `Y`/`N` domain test.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the field.
 * @param {string} value - The entry as the control holds it.
 * @returns {FieldError | null} The refusal, or `null` when the value may be accepted.
 */
function editYesNo(field: AccountUpdateFieldName, label: string, value: string): FieldError | null {
  const entry = value.trim();
  if (entry === '') {
    return refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED, 'BLANK');
  }
  // WHY : Assumptions: the domain is upper-case `Y` and `N` only, because the reference tests
  //       `88 FLG-YES-NO-ISVALID VALUES 'Y','N'` against the field as received and performs no case
  //       folding anywhere on this path. A lower-case entry is refused, which is what the terminal did.
  return entry === 'Y' || entry === 'N'
    ? null
    : refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_Y_OR_N, 'NOT_OK');
}

/**
 * Applies `1225-EDIT-ALPHA-REQD` (L1898-L1951): blank, then letters and spaces only.
 *
 * Assumptions: the alphabet test is the reference's `INSPECT ... CONVERTING` trick read plainly -- it
 * converts every letter to a space and refuses the value if anything but spaces is left, which accepts
 * letters and interior spaces and refuses digits and punctuation. A regular expression over the same
 * alphabet is the same predicate stated directly.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the field.
 * @param {string} value - The entry as the control holds it.
 * @returns {FieldError | null} The refusal, or `null` when the value may be accepted.
 */
function editAlphaRequired(
  field: AccountUpdateFieldName,
  label: string,
  value: string,
): FieldError | null {
  if (value.trim() === '') {
    return refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED, 'BLANK');
  }
  return /^[A-Za-z ]+$/u.test(value)
    ? null
    : refusal(field, label, FIELD_VALIDATION_SUFFIXES.CAN_HAVE_ALPHABETS_ONLY, 'NOT_OK');
}

/**
 * Applies `1235-EDIT-ALPHA-OPT` (L2012-L2057): blank is accepted, otherwise letters and spaces only.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the field.
 * @param {string} value - The entry as the control holds it.
 * @returns {FieldError | null} The refusal, or `null` when the value may be accepted.
 */
function editAlphaOptional(
  field: AccountUpdateFieldName,
  label: string,
  value: string,
): FieldError | null {
  if (value.trim() === '') {
    return null;
  }
  return /^[A-Za-z ]+$/u.test(value)
    ? null
    : refusal(field, label, FIELD_VALIDATION_SUFFIXES.CAN_HAVE_ALPHABETS_ONLY, 'NOT_OK');
}

/**
 * Applies `1245-EDIT-NUM-REQD` (L2109-L2176): blank, then all-numeric, then non-zero.
 *
 * Assumptions: the three branches are in the reference's order and the order is observable -- a blank
 * field reports "must be supplied", a field holding letters reports "must be all numeric", and a field
 * holding only zeros reports "must not be zero". Reordering them would change which sentence an
 * operator sees for a value that fails two tests at once.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the field.
 * @param {string} value - The entry as the control holds it.
 * @returns {FieldError | null} The refusal, or `null` when the value may be accepted.
 */
function editNumericRequired(
  field: AccountUpdateFieldName,
  label: string,
  value: string,
): FieldError | null {
  const entry = value.trim();
  if (entry === '') {
    return refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED, 'BLANK');
  }
  if (!/^[0-9]+$/u.test(entry)) {
    return refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_ALL_NUMERIC, 'NOT_OK');
  }
  return Number(entry) === 0
    ? refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_NOT_BE_ZERO, 'NOT_OK')
    : null;
}

/**
 * Applies `1250-EDIT-SIGNED-9V2` (L2180-L2221): blank, then a signed two-decimal numeric test.
 *
 * Assumptions: the numeric test stands in for `FUNCTION TEST-NUMVAL-C`, which accepts an optional sign
 * and an optional decimal part. The pattern admits a leading `+` or `-` and at most two decimal places,
 * which is the receiving field's own `PIC S9(10)V99` shape -- a third decimal place would be truncated
 * on the way into the record rather than rejected, so refusing it here is the honest reading of a field
 * that cannot hold it.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the field.
 * @param {string} value - The entry as the control holds it.
 * @returns {FieldError | null} The refusal, or `null` when the value may be accepted.
 */
function editSignedAmount(
  field: AccountUpdateFieldName,
  label: string,
  value: string,
): FieldError | null {
  const entry = value.trim();
  if (entry === '') {
    return refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED, 'BLANK');
  }
  return /^[+-]?[0-9]+(?:\.[0-9]{1,2})?$/u.test(entry)
    ? null
    : refusal(field, label, FIELD_VALIDATION_SUFFIXES.IS_NOT_VALID, 'NOT_OK');
}

/** The three controls one split date is entered through. */
interface DateControls {
  /** Control holding the four-digit year. */
  readonly year: AccountUpdateFieldName;
  /** Control holding the two-digit month. */
  readonly month: AccountUpdateFieldName;
  /** Control holding the two-digit day. */
  readonly day: AccountUpdateFieldName;
}

/**
 * Applies the date edits of `app/cpy/CSUTLDPY.cpy` to one split date.
 *
 * Assumptions: the paragraph order is the copybook's -- `EDIT-YEAR-CCYY` (L23), `EDIT-MONTH` (L83),
 * `EDIT-DAY` (L147), then the combination checks of `EDIT-DAY-MONTH-YEAR` (L200) -- and each paragraph
 * accumulates its own refusal and falls through to the next rather than exiting the whole date. That is
 * why a date with a bad year AND a bad month reports the year, and why both controls are marked. The
 * combination checks run only when the three parts are individually acceptable, because they read the
 * numeric values the earlier paragraphs computed.
 *
 * Assumptions: the `CSUTLDTC` call at L290-L318 is NOT reproduced. It is the copybook's own backstop
 * for "a bad date that passed all the edits above", and its refusal text interpolates a severity and a
 * message number from a language-environment service that has no browser counterpart. The service
 * carries the equivalent check, and the four preceding paragraphs already reject every date this
 * screen's controls can hold.
 * @param {DateControls} controls - The three controls the date is entered through.
 * @param {string} label - The reference's name for the date.
 * @param {AccountUpdateFormValues} values - The screen's current form values.
 * @returns {readonly FieldError[]} The refusals in the copybook's own order.
 */
function editSplitDate(
  controls: DateControls,
  label: string,
  values: AccountUpdateFormValues,
): readonly FieldError[] {
  const found: FieldError[] = [];
  const year = values[controls.year].trim();
  const month = values[controls.month].trim();
  const day = values[controls.day].trim();

  if (year === '') {
    found.push(
      refusal(controls.year, label, FIELD_VALIDATION_SUFFIXES.YEAR_MUST_BE_SUPPLIED, 'BLANK'),
    );
  } else if (!/^[0-9]{4}$/u.test(year)) {
    found.push(
      refusal(controls.year, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_4_DIGIT_NUMBER, 'NOT_OK'),
    );
  } else {
    const century = Number(year.slice(0, 2));
    if (century !== EARLIEST_CENTURY && century !== LATEST_CENTURY) {
      found.push(
        refusal(controls.year, label, FIELD_VALIDATION_SUFFIXES.CENTURY_IS_NOT_VALID, 'NOT_OK'),
      );
    }
  }

  if (month === '') {
    found.push(
      refusal(controls.month, label, FIELD_VALIDATION_SUFFIXES.MONTH_MUST_BE_SUPPLIED, 'BLANK'),
    );
  } else if (!/^[0-9]{1,2}$/u.test(month) || Number(month) < 1 || Number(month) > 12) {
    found.push(
      refusal(
        controls.month,
        label,
        FIELD_VALIDATION_SUFFIXES.MONTH_MUST_BE_A_NUMBER_BETWEEN_1_AND_12,
        'NOT_OK',
      ),
    );
  }

  if (day === '') {
    found.push(
      refusal(controls.day, label, FIELD_VALIDATION_SUFFIXES.DAY_MUST_BE_SUPPLIED, 'BLANK'),
    );
  } else if (!/^[0-9]{1,2}$/u.test(day) || Number(day) < 1 || Number(day) > 31) {
    found.push(
      refusal(
        controls.day,
        label,
        FIELD_VALIDATION_SUFFIXES.DAY_MUST_BE_A_NUMBER_BETWEEN_1_AND_31,
        'NOT_OK',
      ),
    );
  }

  if (found.length > 0) {
    return found;
  }

  const monthNumber = Number(month);
  const dayNumber = Number(day);
  const yearNumber = Number(year);

  if (dayNumber === 31 && !THIRTY_ONE_DAY_MONTHS.includes(monthNumber)) {
    /*
     * WHY : Assumptions: the combination refusals mark the DAY and the MONTH together, and the leap-year
     *       one marks the year as well, because that is what the copybook sets -- L206-L207, L220-L221
     *       and L247-L249 each set more than one flag. The first of the pair is reported first so the
     *       cursor lands on the day, which is the control the operator most likely mistyped.
     */
    return [
      refusal(
        controls.day,
        label,
        FIELD_VALIDATION_SUFFIXES.CANNOT_HAVE_31_DAYS_IN_THIS_MONTH,
        'NOT_OK',
      ),
      refusal(
        controls.month,
        label,
        FIELD_VALIDATION_SUFFIXES.CANNOT_HAVE_31_DAYS_IN_THIS_MONTH,
        'NOT_OK',
      ),
    ];
  }
  if (monthNumber === FEBRUARY && dayNumber === 30) {
    return [
      refusal(
        controls.day,
        label,
        FIELD_VALIDATION_SUFFIXES.CANNOT_HAVE_30_DAYS_IN_THIS_MONTH,
        'NOT_OK',
      ),
      refusal(
        controls.month,
        label,
        FIELD_VALIDATION_SUFFIXES.CANNOT_HAVE_30_DAYS_IN_THIS_MONTH,
        'NOT_OK',
      ),
    ];
  }
  if (monthNumber === FEBRUARY && dayNumber === 29 && !isLeapYear(yearNumber)) {
    const suffix = FIELD_VALIDATION_SUFFIXES.NOT_A_LEAP_YEAR_CANNOT_HAVE_29_DAYS_IN_THIS_MONTH;
    return [
      refusal(controls.day, label, suffix, 'NOT_OK'),
      refusal(controls.month, label, suffix, 'NOT_OK'),
      refusal(controls.year, label, suffix, 'NOT_OK'),
    ];
  }

  return found;
}

/**
 * Applies `EDIT-DATE-OF-BIRTH` (`app/cpy/CSUTLDPY.cpy` L336-L361): the date must be strictly past.
 *
 * Assumptions: the comparison is strict, so today itself is refused -- the copybook tests
 * `IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY` and takes the refusal branch on equality. It is
 * also compared as a DAY and not an instant, which is why both sides are reduced to their date parts:
 * comparing instants would make the outcome depend on the time of day.
 * @param {DateControls} controls - The three controls the date of birth is entered through.
 * @param {string} label - The reference's name for the date.
 * @param {AccountUpdateFormValues} values - The screen's current form values.
 * @param {Date} today - The current date, supplied so the caller decides which clock is authoritative.
 * @returns {readonly FieldError[]} The refusals, which mark all three controls when the date is future.
 */
function editDateOfBirthIsPast(
  controls: DateControls,
  label: string,
  values: AccountUpdateFormValues,
  today: Date,
): readonly FieldError[] {
  const entered = Date.UTC(
    Number(values[controls.year]),
    Number(values[controls.month]) - 1,
    Number(values[controls.day]),
  );
  const current = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  if (current > entered) {
    return [];
  }
  const suffix = FIELD_VALIDATION_SUFFIXES.CANNOT_BE_IN_THE_FUTURE;
  return [
    refusal(controls.day, label, suffix, 'NOT_OK'),
    refusal(controls.month, label, suffix, 'NOT_OK'),
    refusal(controls.year, label, suffix, 'NOT_OK'),
  ];
}

/** The three controls one telephone number is entered through. */
interface TelephoneControls {
  /** Control holding the three-digit area code. */
  readonly areaCode: AccountUpdateFieldName;
  /** Control holding the three-digit prefix code. */
  readonly prefix: AccountUpdateFieldName;
  /** Control holding the four-digit line number. */
  readonly lineNumber: AccountUpdateFieldName;
}

/**
 * Applies one part of `1260-EDIT-US-PHONE-NUM` (L2244-L2427): supplied, right width, non-zero.
 * @param {AccountUpdateFieldName} field - The control being edited.
 * @param {string} label - The reference's name for the whole telephone number.
 * @param {string} value - The part as the control holds it.
 * @param {number} width - The part's declared width.
 * @param {object} suffixes - The three suffixes this part uses, which differ per part because each
 *   names itself in its own sentence.
 * @param {string} suffixes.blank - Sentence for a part that carried no value.
 * @param {string} suffixes.malformed - Sentence for a part that is not a number of the right width.
 * @param {string} suffixes.zero - Sentence for a part whose value is zero.
 * @returns {FieldError | null} The refusal, or `null` when the part may be accepted.
 */
function editTelephonePart(
  field: AccountUpdateFieldName,
  label: string,
  value: string,
  width: number,
  suffixes: { readonly blank: string; readonly malformed: string; readonly zero: string },
): FieldError | null {
  const entry = value.trim();
  if (entry === '') {
    return refusal(field, label, suffixes.blank, 'BLANK');
  }
  if (!new RegExp(`^[0-9]{${String(width)}}$`, 'u').test(entry)) {
    return refusal(field, label, suffixes.malformed, 'NOT_OK');
  }
  return Number(entry) === 0 ? refusal(field, label, suffixes.zero, 'NOT_OK') : null;
}

/**
 * Applies `1260-EDIT-US-PHONE-NUM` to one telephone number.
 *
 * Assumptions: a wholly empty number is ACCEPTED, because the reference says so in as many words --
 * "Not mandatory to enter a phone number" at L2231, which sets the valid flag and exits when all three
 * parts are blank. A partly filled number is refused part by part.
 *
 * Assumptions: the three parts ACCUMULATE their refusals rather than short-circuiting, which is the one
 * structural difference between this paragraph and its siblings: each part's failure branch ends in
 * `GO TO` the NEXT part's paragraph rather than the exit, so all three are always edited. Every refused
 * part is therefore marked, and the first still owns the message.
 *
 * Assumptions: the area code's membership of the North American general-purpose set (L2293 onward) is
 * NOT checked here, for the reason the module note gives. Its three structural checks are.
 * @param {TelephoneControls} controls - The three controls the number is entered through.
 * @param {string} label - The reference's name for the number.
 * @param {AccountUpdateFormValues} values - The screen's current form values.
 * @returns {readonly FieldError[]} The refusals in the paragraph's own order.
 */
function editTelephone(
  controls: TelephoneControls,
  label: string,
  values: AccountUpdateFormValues,
): readonly FieldError[] {
  const areaCode = values[controls.areaCode].trim();
  const prefix = values[controls.prefix].trim();
  const lineNumber = values[controls.lineNumber].trim();

  if (areaCode === '' && prefix === '' && lineNumber === '') {
    return [];
  }

  const found: FieldError[] = [];
  const area = editTelephonePart(controls.areaCode, label, areaCode, TELEPHONE_CODE_WIDTH, {
    blank: FIELD_VALIDATION_SUFFIXES.AREA_CODE_MUST_BE_SUPPLIED,
    malformed: FIELD_VALIDATION_SUFFIXES.AREA_CODE_MUST_BE_A_3_DIGIT_NUMBER,
    zero: FIELD_VALIDATION_SUFFIXES.AREA_CODE_CANNOT_BE_ZERO,
  });
  if (area !== null) {
    found.push(area);
  }
  const middle = editTelephonePart(controls.prefix, label, prefix, TELEPHONE_CODE_WIDTH, {
    blank: FIELD_VALIDATION_SUFFIXES.PREFIX_CODE_MUST_BE_SUPPLIED,
    malformed: FIELD_VALIDATION_SUFFIXES.PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER,
    zero: FIELD_VALIDATION_SUFFIXES.PREFIX_CODE_CANNOT_BE_ZERO,
  });
  if (middle !== null) {
    found.push(middle);
  }
  const line = editTelephonePart(controls.lineNumber, label, lineNumber, TELEPHONE_LINE_WIDTH, {
    blank: FIELD_VALIDATION_SUFFIXES.LINE_NUMBER_CODE_MUST_BE_SUPPLIED,
    malformed: FIELD_VALIDATION_SUFFIXES.LINE_NUMBER_CODE_MUST_BE_A_4_DIGIT_NUMBER,
    zero: FIELD_VALIDATION_SUFFIXES.LINE_NUMBER_CODE_CANNOT_BE_ZERO,
  });
  if (line !== null) {
    found.push(line);
  }
  return found;
}

/**
 * Applies `1265-EDIT-US-SSN` (L2431-L2489) to the three national-identifier parts.
 *
 * Assumptions: each part runs the numeric-required edit under its OWN label -- `SSN: First 3 chars`,
 * `SSN 4th & 5th chars` and `SSN Last 4 chars`, moved into the variable-name field at L2439, L2467 and
 * L2479 -- and not under the `SSN` label the chain set before entering the paragraph. Using the outer
 * label would report all three failures with one sentence and lose which part was wrong.
 *
 * Assumptions: the paragraph does not short-circuit between parts, so all three are edited and all
 * three can be marked. The first part's domain check runs only when its numeric edit passed, which is
 * the `IF FLG-EDIT-US-SSN-PART1-ISVALID` guard at L2446.
 * @param {AccountUpdateFormValues} values - The screen's current form values.
 * @returns {readonly FieldError[]} The refusals in the paragraph's own order.
 */
function editNationalIdentifier(values: AccountUpdateFormValues): readonly FieldError[] {
  const found: FieldError[] = [];
  const first = editNumericRequired(
    'ssnPart1',
    ACCOUNT_UPDATE_FIELD_LABELS.SSN_FIRST_3_CHARS,
    values.ssnPart1,
  );
  if (first !== null) {
    found.push(first);
  } else if (
    /^[0-9]{3}$/u.test(values.ssnPart1.trim()) &&
    isRefusedIdentifierPart(Number(values.ssnPart1.trim()))
  ) {
    found.push(
      refusal(
        'ssnPart1',
        ACCOUNT_UPDATE_FIELD_LABELS.SSN_FIRST_3_CHARS,
        FIELD_VALIDATION_SUFFIXES.SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999,
        'NOT_OK',
      ),
    );
  }
  const second = editNumericRequired(
    'ssnPart2',
    ACCOUNT_UPDATE_FIELD_LABELS.SSN_4TH_AND_5TH_CHARS,
    values.ssnPart2,
  );
  if (second !== null) {
    found.push(second);
  }
  const third = editNumericRequired(
    'ssnPart3',
    ACCOUNT_UPDATE_FIELD_LABELS.SSN_LAST_4_CHARS,
    values.ssnPart3,
  );
  if (third !== null) {
    found.push(third);
  }
  // WHY : Assumptions: the declared part widths are asserted rather than assumed by the numeric edit,
  //       which tests only that the entry is all digits. The three `MOVE ... TO
  //       WS-EDIT-ALPHANUM-LENGTH` statements bound each part in the reference, and the screen's own
  //       `maxLength` bounds them here, so this guards the one case both miss -- a part shorter than
  //       its width, which is all digits and still not a national identifier part.
  const widths: readonly [AccountUpdateFieldName, string, number][] = [
    ['ssnPart1', ACCOUNT_UPDATE_FIELD_LABELS.SSN_FIRST_3_CHARS, IDENTIFIER_PART_WIDTHS.part1],
    ['ssnPart2', ACCOUNT_UPDATE_FIELD_LABELS.SSN_4TH_AND_5TH_CHARS, IDENTIFIER_PART_WIDTHS.part2],
    ['ssnPart3', ACCOUNT_UPDATE_FIELD_LABELS.SSN_LAST_4_CHARS, IDENTIFIER_PART_WIDTHS.part3],
  ];
  for (const [field, label, width] of widths) {
    const entry = values[field].trim();
    const alreadyRefused = found.some(
      /**
       * Reports whether this part already carries a refusal.
       * @param {FieldError} existing - One refusal already recorded.
       * @returns {boolean} `true` when it names this part.
       */
      (existing: FieldError): boolean => existing.field === field,
    );
    if (!alreadyRefused && entry.length !== width) {
      found.push(refusal(field, label, FIELD_VALIDATION_SUFFIXES.MUST_BE_ALL_NUMERIC, 'NOT_OK'));
    }
  }
  return found;
}

/**
 * Applies `1200-EDIT-MAP-INPUTS` (L1429-L1678) to the screen's form values.
 *
 * Assumptions: the twenty-four steps below are in the reference's own order, read straight down its
 * paragraph, and the order IS the contract -- the first refusal owns the message and the cursor. The
 * account identifier is not among them: it is edited by `1210-EDIT-ACCOUNT` on the FETCH turn only
 * (L1435, reached under `IF ACUP-DETAILS-NOT-FETCHED`), which the screen performs separately through
 * its own account-filter edit.
 *
 * Assumptions: the two lookup-backed edits are placed where the reference performs them -- the
 * state-code check immediately after the state's own alpha edit (L1600-L1602), and the state-and-ZIP
 * combination as the last thing the paragraph does (L1665-L1668). Their position matters because it
 * decides whether they can own the message.
 * @param {AccountUpdateFormValues} values - The screen's current form values.
 * @param {ReferenceAnswers} answers - The two lookup answers, or nulls when they are unknown.
 * @param {Date} today - The current date, for the date-of-birth reasonableness check.
 * @returns {EditChainOutcome} The ordered refusals, the sentence to show, and the two structural flags
 *   the caller needs to decide which lookups are worth asking for.
 */
export function editAccountUpdateInputs(
  values: AccountUpdateFormValues,
  answers: ReferenceAnswers,
  today: Date,
): EditChainOutcome {
  const labels = ACCOUNT_UPDATE_FIELD_LABELS;
  const found: FieldError[] = [];

  /**
   * Records a refusal when one was raised.
   * @param {FieldError | null} raised - The refusal, or `null` when the edit accepted the field.
   * @returns {void} Completion is the recorded refusal.
   */
  const record = (raised: FieldError | null): void => {
    if (raised !== null) {
      found.push(raised);
    }
  };

  record(editYesNo('activeStatus', labels.ACCOUNT_STATUS, values.activeStatus));
  found.push(
    ...editSplitDate(
      { year: 'openDateYear', month: 'openDateMonth', day: 'openDateDay' },
      labels.OPEN_DATE,
      values,
    ),
  );
  record(editSignedAmount('creditLimit', labels.CREDIT_LIMIT, values.creditLimit));
  found.push(
    ...editSplitDate(
      { year: 'expirationDateYear', month: 'expirationDateMonth', day: 'expirationDateDay' },
      labels.EXPIRY_DATE,
      values,
    ),
  );
  record(editSignedAmount('cashCreditLimit', labels.CASH_CREDIT_LIMIT, values.cashCreditLimit));
  found.push(
    ...editSplitDate(
      { year: 'reissueDateYear', month: 'reissueDateMonth', day: 'reissueDateDay' },
      labels.REISSUE_DATE,
      values,
    ),
  );
  record(editSignedAmount('currentBalance', labels.CURRENT_BALANCE, values.currentBalance));
  record(
    editSignedAmount(
      'currentCycleCredit',
      labels.CURRENT_CYCLE_CREDIT_LIMIT,
      values.currentCycleCredit,
    ),
  );
  record(
    editSignedAmount(
      'currentCycleDebit',
      labels.CURRENT_CYCLE_DEBIT_LIMIT,
      values.currentCycleDebit,
    ),
  );
  found.push(...editNationalIdentifier(values));

  const birthControls: DateControls = {
    year: 'dateOfBirthYear',
    month: 'dateOfBirthMonth',
    day: 'dateOfBirthDay',
  };
  const birthRefusals = editSplitDate(birthControls, labels.DATE_OF_BIRTH, values);
  found.push(...birthRefusals);
  if (birthRefusals.length === 0) {
    // WHY : Assumptions: the reasonableness check runs only when the date itself was accepted, which is
    //       the `IF WS-EDIT-DT-OF-BIRTH-ISVALID` guard at L1539. Running it on a malformed date would
    //       compare a value the earlier edits already rejected and could report a future date for a
    //       date that has no year.
    found.push(...editDateOfBirthIsPast(birthControls, labels.DATE_OF_BIRTH, values, today));
  }

  const ficoRefusal = editNumericRequired(
    'ficoCreditScore',
    labels.FICO_SCORE,
    values.ficoCreditScore,
  );
  if (ficoRefusal !== null) {
    found.push(ficoRefusal);
  } else {
    // WHY : Assumptions: the range check runs only when the numeric edit passed, which is the
    //       `IF FLG-FICO-SCORE-ISVALID` guard at L1552-L1555, so a non-numeric score reports "must be
    //       all numeric" and not a range it cannot be compared against.
    const score = Number(values.ficoCreditScore.trim());
    if (score < LOWEST_CREDIT_SCORE || score > HIGHEST_CREDIT_SCORE) {
      record(
        refusal(
          'ficoCreditScore',
          labels.FICO_SCORE,
          FIELD_VALIDATION_SUFFIXES.SHOULD_BE_BETWEEN_300_AND_850,
          'NOT_OK',
        ),
      );
    }
  }

  record(editAlphaRequired('firstName', labels.FIRST_NAME, values.firstName));
  record(editAlphaOptional('middleName', labels.MIDDLE_NAME, values.middleName));
  record(editAlphaRequired('lastName', labels.LAST_NAME, values.lastName));
  record(editMandatory('addressLine1', labels.ADDRESS_LINE_1, values.addressLine1));

  const stateRefusal = editAlphaRequired('stateCode', labels.STATE, values.stateCode);
  const stateCodeStructurallyValid = stateRefusal === null;
  if (stateRefusal !== null) {
    found.push(stateRefusal);
  } else if (answers.stateCodeIsKnown === false) {
    found.push(
      refusal(
        'stateCode',
        labels.STATE,
        FIELD_VALIDATION_SUFFIXES.IS_NOT_A_VALID_STATE_CODE,
        'NOT_OK',
      ),
    );
  }

  const zipRefusal = editNumericRequired('zipCode', labels.ZIP, values.zipCode);
  const zipCodeStructurallyValid = zipRefusal === null;
  record(zipRefusal);

  record(editAlphaRequired('city', labels.CITY, values.city));
  record(editAlphaRequired('countryCode', labels.COUNTRY, values.countryCode));
  found.push(
    ...editTelephone(
      { areaCode: 'phone1AreaCode', prefix: 'phone1Prefix', lineNumber: 'phone1LineNumber' },
      labels.PHONE_NUMBER_1,
      values,
    ),
  );
  found.push(
    ...editTelephone(
      { areaCode: 'phone2AreaCode', prefix: 'phone2Prefix', lineNumber: 'phone2LineNumber' },
      labels.PHONE_NUMBER_2,
      values,
    ),
  );
  record(editNumericRequired('eftAccountId', labels.EFT_ACCOUNT_ID, values.eftAccountId));
  record(
    editYesNo(
      'primaryCardHolderIndicator',
      labels.PRIMARY_CARD_HOLDER,
      values.primaryCardHolderIndicator,
    ),
  );

  /*
   * WHY : Assumptions: the cross-field edit runs LAST and only when both its inputs passed their own
   *       edits, which is the guard at L1665-L1667. Its sentence is the one refusal in this paragraph
   *       with no field name in it -- `Invalid zip code for state` is a whole literal, not a label and a
   *       suffix -- so it is composed from the catalogue's template rather than by the label join, and
   *       it marks BOTH controls because L2551-L2552 sets both flags.
   */
  if (
    stateCodeStructurallyValid &&
    zipCodeStructurallyValid &&
    answers.stateZipCombinationIsKnown === false
  ) {
    const sentence = formatMessageTemplate(MESSAGE_TEMPLATES.INVALID_ZIP_CODE_FOR_STATE, {});
    found.push({ field: 'stateCode', state: 'NOT_OK', message: sentence });
    found.push({ field: 'zipCode', state: 'NOT_OK', message: sentence });
  }

  const first = found[0];
  return {
    refusals: found,
    message: first === undefined ? null : first.message,
    stateCodeStructurallyValid,
    zipCodeStructurallyValid,
  };
}

/**
 * Composes the reference-data key the state-and-ZIP combination is looked up by.
 *
 * Assumptions: the key is the state code followed by the ZIP's first two digits, which is the
 * `STRING ... DELIMITED BY SIZE INTO US-STATE-AND-FIRST-ZIP2` at `app/cbl/COACTUPC.cbl` L2537-L2541 --
 * a four-character composite, not two separate lookups. The reference service stores the same composite
 * as the key of `us_state_zip_prefixes`, so one request answers the whole check.
 * @param {string} stateCode - The state code as the control holds it.
 * @param {string} zipCode - The ZIP as the control holds it.
 * @returns {string} The four-character composite key.
 */
export function stateZipLookupKey(stateCode: string, zipCode: string): string {
  return stateCode.trim() + zipCode.trim().slice(0, ZIP_PREFIX_WIDTH);
}
