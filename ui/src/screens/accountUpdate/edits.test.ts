/**
 * @file Unit tests for the account-update screen's field edits in `ui/src/screens/accountUpdate/edits.ts`.
 *
 * Purpose
 * -------
 * Pin the three properties of `1200-EDIT-MAP-INPUTS` that a rendering cannot show: the ORDER the
 * twenty-four edits run in, which decides the single sentence row 23 carries and the control the cursor
 * lands on; the per-field refusal flags, which decide the red highlight and the blank marker; and the
 * cross-field state-and-ZIP edit, which runs last and marks two controls at once.
 *
 * Assumptions: the chain is exercised directly rather than through the rendered screen. Order is a
 * property of the chain, and a rendering shows only its result -- a case that typed into controls could
 * pass with the edits in any order as long as the same sentence came out for the value it chose.
 *
 * Assumptions: every identifier below is fabricated. The account and customer numbers are sequential
 * digits and the national-identifier parts are chosen to exercise the reference's own refused set.
 */

import { describe, expect, it } from 'vitest';

import type { FieldError } from '../../api/types';
import {
  ACCOUNT_UPDATE_FIELD_LABELS,
  FIELD_VALIDATION_SUFFIXES,
  MESSAGE_TEMPLATES,
  formatFieldValidationMessage,
  formatMessageTemplate,
} from '../../messages/messages';

import { editAccountUpdateInputs, stateZipLookupKey } from './edits';
import type { ReferenceAnswers } from './edits';
import { blankFormValues } from './index';
import type { AccountUpdateFormValues } from './index';

const LABELS = ACCOUNT_UPDATE_FIELD_LABELS;
const SUFFIXES = FIELD_VALIDATION_SUFFIXES;

/** Both lookup answers resolved affirmatively, so only the structural edits can refuse anything. */
const LOOKUPS_SATISFIED: ReferenceAnswers = {
  stateCodeIsKnown: true,
  stateZipCombinationIsKnown: true,
};

/** Neither lookup answered, which is how an unreachable reference service presents. */
const LOOKUPS_UNKNOWN: ReferenceAnswers = {
  stateCodeIsKnown: null,
  stateZipCombinationIsKnown: null,
};

/** A fixed date, so no case depends on the day it runs. */
const TODAY = new Date(Date.UTC(2026, 7, 15));

/**
 * Form values that pass every edit, as a base each case departs from in one field.
 *
 * Assumptions: it is built from the screen's own `blankFormValues` and then filled, rather than written
 * out as an object literal. The value type has forty-odd members and a literal would stop compiling the
 * moment one is added -- which is the right failure for the screen and the wrong one for this file,
 * where an added member should simply arrive blank and be caught by whichever case covers it.
 * @returns {AccountUpdateFormValues} A complete, acceptable set of entries.
 */
function acceptableValues(): AccountUpdateFormValues {
  return {
    ...blankFormValues(),
    accountId: '00000000123',
    activeStatus: 'Y',
    openDateYear: '2020',
    openDateMonth: '01',
    openDateDay: '15',
    creditLimit: '5000.00',
    expirationDateYear: '2029',
    expirationDateMonth: '12',
    expirationDateDay: '31',
    cashCreditLimit: '1000.00',
    reissueDateYear: '2025',
    reissueDateMonth: '06',
    reissueDateDay: '30',
    currentBalance: '-250.75',
    currentCycleCredit: '0.00',
    currentCycleDebit: '0.00',
    ssnPart1: '123',
    ssnPart2: '45',
    ssnPart3: '6789',
    dateOfBirthYear: '1980',
    dateOfBirthMonth: '02',
    dateOfBirthDay: '29',
    ficoCreditScore: '700',
    firstName: 'ANN',
    middleName: '',
    lastName: 'BROWN',
    addressLine1: '1 EXAMPLE WAY',
    stateCode: 'NY',
    zipCode: '10001',
    city: 'NEW YORK',
    countryCode: 'USA',
    phone1AreaCode: '212',
    phone1Prefix: '555',
    phone1LineNumber: '0100',
    phone2AreaCode: '',
    phone2Prefix: '',
    phone2LineNumber: '',
    eftAccountId: '1234567890',
    primaryCardHolderIndicator: 'Y',
  };
}

/**
 * Runs the chain over the acceptable values with one or more fields replaced.
 * @param {Partial<AccountUpdateFormValues>} overrides - The fields to change.
 * @param {ReferenceAnswers} answers - The two lookup answers to supply.
 * @returns {readonly FieldError[]} The refusals in the chain's own order.
 */
function refusalsFor(
  overrides: Partial<AccountUpdateFormValues>,
  answers: ReferenceAnswers = LOOKUPS_SATISFIED,
): readonly FieldError[] {
  return editAccountUpdateInputs({ ...acceptableValues(), ...overrides }, answers, TODAY).refusals;
}

/**
 * Runs the chain and returns the sentence it reports.
 * @param {Partial<AccountUpdateFormValues>} overrides - The fields to change.
 * @param {ReferenceAnswers} answers - The two lookup answers to supply.
 * @returns {string | null} The reported sentence, or `null` when nothing was refused.
 */
function messageFor(
  overrides: Partial<AccountUpdateFormValues>,
  answers: ReferenceAnswers = LOOKUPS_SATISFIED,
): string | null {
  return editAccountUpdateInputs({ ...acceptableValues(), ...overrides }, answers, TODAY).message;
}

/**
 * Names the control one refusal marks.
 *
 * Assumptions: this is a named function rather than an inline arrow at each of the seven call sites,
 * because `ui/eslint.config.js` requires a JSDoc block on every function including those handed to
 * `Array.prototype.map` -- seven documented arrows would be seven copies of one sentence.
 * @param {FieldError} entry - One refusal from the chain.
 * @returns {string} The name of the control the refusal marks.
 */
function fieldNameOf(entry: FieldError): string {
  return entry.field;
}

/**
 * Asserts a complete, acceptable submission is refused nowhere.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function acceptsACompleteSubmission(): void {
  const outcome = editAccountUpdateInputs(acceptableValues(), LOOKUPS_SATISFIED, TODAY);

  expect(outcome.refusals).toStrictEqual([]);
  expect(outcome.message).toBeNull();
  expect(outcome.stateCodeStructurallyValid).toBe(true);
  expect(outcome.zipCodeStructurallyValid).toBe(true);
}

/**
 * Asserts the chain reports the FIRST refusal in the reference's order, not the last or the worst.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheFirstRefusalInSourceOrder(): void {
  /*
   * WHY : Assumptions: three fields are broken at once, spanning the first, the middle and the last of
   *       the twenty-four steps -- the account status is step 1, the first name is step 13 and the
   *       primary-card-holder indicator is step 24. The reference guards every `MOVE` to its message
   *       field with `IF WS-RETURN-MSG-OFF`, so the earliest step owns the sentence. A chain that ran
   *       the steps in any other order, or that reported the last refusal, would answer differently
   *       here while answering identically for any single broken field.
   */
  const outcome = editAccountUpdateInputs(
    { ...acceptableValues(), activeStatus: 'X', firstName: '123', primaryCardHolderIndicator: 'X' },
    LOOKUPS_SATISFIED,
    TODAY,
  );

  expect(outcome.message).toBe(
    formatFieldValidationMessage(LABELS.ACCOUNT_STATUS, SUFFIXES.MUST_BE_Y_OR_N),
  );
  expect(outcome.refusals.map(fieldNameOf)).toStrictEqual([
    'activeStatus',
    'firstName',
    'primaryCardHolderIndicator',
  ]);
}

/**
 * Asserts the twenty-four steps run in the reference's order across the whole paragraph.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function runsTheTwentyFourStepsInSourceOrder(): void {
  /*
   * WHY : Assumptions: this is the order assertion in its strongest available form -- every field is
   *       broken at once, so the refusal sequence IS the execution order and any transposition of two
   *       steps fails the case. The expected list is read straight down `1200-EDIT-MAP-INPUTS`
   *       (`app/cbl/COACTUPC.cbl` L1463-L1668). Dates and telephone numbers contribute one entry per
   *       part because each part is a control of its own here, and the national identifier contributes
   *       three for the same reason.
   */
  const broken: Partial<AccountUpdateFormValues> = {
    activeStatus: '',
    openDateYear: '',
    creditLimit: '',
    expirationDateYear: '',
    cashCreditLimit: '',
    reissueDateYear: '',
    currentBalance: '',
    currentCycleCredit: '',
    currentCycleDebit: '',
    ssnPart1: '',
    ssnPart2: '',
    ssnPart3: '',
    dateOfBirthYear: '',
    ficoCreditScore: '',
    firstName: '',
    middleName: '1',
    lastName: '',
    addressLine1: '',
    stateCode: '',
    zipCode: '',
    city: '',
    countryCode: '',
    phone1AreaCode: '',
    phone2AreaCode: '999',
    phone2Prefix: '',
    eftAccountId: '',
    primaryCardHolderIndicator: '',
  };

  expect(refusalsFor(broken).map(fieldNameOf)).toStrictEqual([
    'activeStatus',
    'openDateYear',
    'creditLimit',
    'expirationDateYear',
    'cashCreditLimit',
    'reissueDateYear',
    'currentBalance',
    'currentCycleCredit',
    'currentCycleDebit',
    'ssnPart1',
    'ssnPart2',
    'ssnPart3',
    'dateOfBirthYear',
    'ficoCreditScore',
    'firstName',
    'middleName',
    'lastName',
    'addressLine1',
    'stateCode',
    'zipCode',
    'city',
    'countryCode',
    'phone1AreaCode',
    'phone2Prefix',
    'phone2LineNumber',
    'eftAccountId',
    'primaryCardHolderIndicator',
  ]);
}

/**
 * Asserts a blank field is flagged BLANK and a malformed one NOT_OK.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function separatesTheBlankFlagFromTheMalformedFlag(): void {
  /*
   * WHY : Assumptions: the two flags are asserted separately because they drive different renderings.
   *       `app/cpy/CSSETATY.cpy` L17-L27 colours a not-ok field red and additionally writes a literal
   *       `'*'` into a BLANK one, so collapsing the two would silently drop the marker.
   */
  const blank = refusalsFor({ firstName: '' })[0];
  const malformed = refusalsFor({ firstName: '123' })[0];

  expect(blank?.state).toBe('BLANK');
  expect(blank?.message).toBe(
    formatFieldValidationMessage(LABELS.FIRST_NAME, SUFFIXES.MUST_BE_SUPPLIED),
  );
  expect(malformed?.state).toBe('NOT_OK');
  expect(malformed?.message).toBe(
    formatFieldValidationMessage(LABELS.FIRST_NAME, SUFFIXES.CAN_HAVE_ALPHABETS_ONLY),
  );
}

/**
 * Asserts the numeric edit reports its three branches in the reference's order.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheNumericBranchesInSourceOrder(): void {
  expect(messageFor({ eftAccountId: '' })).toBe(
    formatFieldValidationMessage(LABELS.EFT_ACCOUNT_ID, SUFFIXES.MUST_BE_SUPPLIED),
  );
  expect(messageFor({ eftAccountId: '12345ABCDE' })).toBe(
    formatFieldValidationMessage(LABELS.EFT_ACCOUNT_ID, SUFFIXES.MUST_BE_ALL_NUMERIC),
  );
  expect(messageFor({ eftAccountId: '0000000000' })).toBe(
    formatFieldValidationMessage(LABELS.EFT_ACCOUNT_ID, SUFFIXES.MUST_NOT_BE_ZERO),
  );
}

/**
 * Asserts an optional name accepts blank and still refuses digits.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function acceptsABlankOptionalName(): void {
  expect(refusalsFor({ middleName: '' })).toStrictEqual([]);
  expect(messageFor({ middleName: 'A1' })).toBe(
    formatFieldValidationMessage(LABELS.MIDDLE_NAME, SUFFIXES.CAN_HAVE_ALPHABETS_ONLY),
  );
}

/**
 * Asserts the date edits report year, month and day in the copybook's order.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsTheDatePartsInCopybookOrder(): void {
  const outcome = refusalsFor({ openDateYear: '', openDateMonth: '', openDateDay: '' });

  expect(outcome.map(fieldNameOf)).toStrictEqual(['openDateYear', 'openDateMonth', 'openDateDay']);
  expect(outcome[0]?.message).toBe(
    formatFieldValidationMessage(LABELS.OPEN_DATE, SUFFIXES.YEAR_MUST_BE_SUPPLIED),
  );
  expect(outcome[1]?.message).toBe(
    formatFieldValidationMessage(LABELS.OPEN_DATE, SUFFIXES.MONTH_MUST_BE_SUPPLIED),
  );
  expect(outcome[2]?.message).toBe(
    formatFieldValidationMessage(LABELS.OPEN_DATE, SUFFIXES.DAY_MUST_BE_SUPPLIED),
  );
}

/**
 * Asserts the century test admits exactly the two centuries the copybook admits.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function admitsOnlyTheNineteenthAndTwentiethCenturies(): void {
  /*
   * WHY : Assumptions: 2100 is refused and 1900 is accepted, which is the copybook saying so in its own
   *       comment -- "being unable to imagine COBOL in the 2100s / We code only 19 and 20 as valid
   *       century values" at `app/cpy/CSUTLDPY.cpy` L59-L62. A range check written from intuition would
   *       admit 2100 and diverge.
   */
  expect(refusalsFor({ openDateYear: '1900' })).toStrictEqual([]);
  expect(messageFor({ openDateYear: '2100' })).toBe(
    formatFieldValidationMessage(LABELS.OPEN_DATE, SUFFIXES.CENTURY_IS_NOT_VALID),
  );
}

/**
 * Asserts the day-and-month combination checks, including the leap-year rule.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesImpossibleDayAndMonthCombinations(): void {
  expect(messageFor({ openDateMonth: '04', openDateDay: '31' })).toBe(
    formatFieldValidationMessage(LABELS.OPEN_DATE, SUFFIXES.CANNOT_HAVE_31_DAYS_IN_THIS_MONTH),
  );
  expect(messageFor({ openDateMonth: '02', openDateDay: '30' })).toBe(
    formatFieldValidationMessage(LABELS.OPEN_DATE, SUFFIXES.CANNOT_HAVE_30_DAYS_IN_THIS_MONTH),
  );
  /*
   * WHY : Assumptions: 1980 and 2000 are leap years and 1900 and 2019 are not, which exercises both
   *       arms of the copybook's own divisor choice at L235-L246 -- it divides by 400 when the
   *       two-digit year part is zero and by 4 otherwise. 1900 is the case that tells the two rules
   *       apart, and it is the case a naive "divisible by four" test gets wrong.
   */
  expect(
    refusalsFor({ openDateYear: '1980', openDateMonth: '02', openDateDay: '29' }),
  ).toStrictEqual([]);
  expect(
    refusalsFor({ openDateYear: '2000', openDateMonth: '02', openDateDay: '29' }),
  ).toStrictEqual([]);
  for (const year of ['1900', '2019']) {
    const outcome = refusalsFor({
      openDateYear: year,
      openDateMonth: '02',
      openDateDay: '29',
    });
    expect(outcome[0]?.message).toBe(
      formatFieldValidationMessage(
        LABELS.OPEN_DATE,
        SUFFIXES.NOT_A_LEAP_YEAR_CANNOT_HAVE_29_DAYS_IN_THIS_MONTH,
      ),
    );
    // WHY : Assumptions: all three date controls are marked, because the copybook sets the day, month
    //       AND year flags for this one refusal (L247-L249).
    expect(outcome.map(fieldNameOf)).toStrictEqual([
      'openDateDay',
      'openDateMonth',
      'openDateYear',
    ]);
  }
}

/**
 * Asserts a date of birth may not be today or later.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesADateOfBirthThatIsNotStrictlyPast(): void {
  const future = formatFieldValidationMessage(
    LABELS.DATE_OF_BIRTH,
    SUFFIXES.CANNOT_BE_IN_THE_FUTURE,
  );

  expect(
    messageFor({ dateOfBirthYear: '2030', dateOfBirthMonth: '01', dateOfBirthDay: '01' }),
  ).toBe(future);
  /*
   * WHY : Assumptions: TODAY itself is refused, because the copybook's comparison is strict --
   *       `IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY` takes the refusal branch on equality
   *       (L347-L356). An inclusive comparison would accept a birth date of today and diverge on
   *       exactly one day in every run.
   */
  expect(
    messageFor({ dateOfBirthYear: '2026', dateOfBirthMonth: '08', dateOfBirthDay: '15' }),
  ).toBe(future);
  expect(
    refusalsFor({ dateOfBirthYear: '2026', dateOfBirthMonth: '08', dateOfBirthDay: '14' }),
  ).toStrictEqual([]);
}

/**
 * Asserts the reasonableness check does not run on a date the earlier edits rejected.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function withholdsTheFutureCheckFromAMalformedDateOfBirth(): void {
  const outcome = refusalsFor({ dateOfBirthYear: 'ABCD' });

  expect(outcome).toHaveLength(1);
  expect(outcome[0]?.message).toBe(
    formatFieldValidationMessage(LABELS.DATE_OF_BIRTH, SUFFIXES.MUST_BE_4_DIGIT_NUMBER),
  );
}

/**
 * Asserts the credit-score range check runs only after its numeric edit passes.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function checksTheCreditScoreRangeOnlyOnANumericEntry(): void {
  expect(messageFor({ ficoCreditScore: 'ABC' })).toBe(
    formatFieldValidationMessage(LABELS.FICO_SCORE, SUFFIXES.MUST_BE_ALL_NUMERIC),
  );
  expect(messageFor({ ficoCreditScore: '299' })).toBe(
    formatFieldValidationMessage(LABELS.FICO_SCORE, SUFFIXES.SHOULD_BE_BETWEEN_300_AND_850),
  );
  expect(messageFor({ ficoCreditScore: '851' })).toBe(
    formatFieldValidationMessage(LABELS.FICO_SCORE, SUFFIXES.SHOULD_BE_BETWEEN_300_AND_850),
  );
  // WHY : Assumptions: both bounds are INCLUSIVE, which `88 FICO-RANGE-IS-VALID VALUES 300 THROUGH
  //       850` states -- so 300 and 850 are accepted and only 299 and 851 are not.
  expect(refusalsFor({ ficoCreditScore: '300' })).toStrictEqual([]);
  expect(refusalsFor({ ficoCreditScore: '850' })).toStrictEqual([]);
}

/**
 * Asserts each national-identifier part is refused under its own name.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function namesEachNationalIdentifierPartSeparately(): void {
  expect(messageFor({ ssnPart1: '' })).toBe(
    formatFieldValidationMessage(LABELS.SSN_FIRST_3_CHARS, SUFFIXES.MUST_BE_SUPPLIED),
  );
  expect(messageFor({ ssnPart2: '' })).toBe(
    formatFieldValidationMessage(LABELS.SSN_4TH_AND_5TH_CHARS, SUFFIXES.MUST_BE_SUPPLIED),
  );
  expect(messageFor({ ssnPart3: '' })).toBe(
    formatFieldValidationMessage(LABELS.SSN_LAST_4_CHARS, SUFFIXES.MUST_BE_SUPPLIED),
  );
  /*
   * WHY : Assumptions: the three sentences are asserted DISTINCT, and that is the property the outer
   *       label would destroy. The paragraph moves a part-specific name into the variable-name field
   *       before each part's edit (L2439, L2467 and L2479); reporting all three under the bare `SSN`
   *       label would make the three sentences identical and lose which part was wrong. A negative
   *       assertion against the substring `SSN ` cannot express this, because two of the three
   *       part-specific labels legitimately begin with those characters.
   */
  const partSentences = new Set([
    messageFor({ ssnPart1: '' }),
    messageFor({ ssnPart2: '' }),
    messageFor({ ssnPart3: '' }),
  ]);
  expect(partSentences.size).toBe(3);
  expect(partSentences).not.toContain(
    formatFieldValidationMessage(LABELS.SSN, SUFFIXES.MUST_BE_SUPPLIED),
  );
}

/**
 * Asserts the reference's refused first-part values, and only those.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesTheReservedNationalIdentifierPrefixes(): void {
  const reserved = formatFieldValidationMessage(
    LABELS.SSN_FIRST_3_CHARS,
    SUFFIXES.SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999,
  );

  /*
   * WHY : Assumptions: `000` reaches the numeric edit's zero branch and not this one, because the
   *       reference runs `1245-EDIT-NUM-REQD` FIRST and that paragraph refuses a zero value at L2148
   *       before the domain check at L2446 is reached. So the sentence for `000` is "must not be zero",
   *       which is what the reference shows -- and the domain check's own `0` value is unreachable.
   */
  expect(messageFor({ ssnPart1: '000' })).toBe(
    formatFieldValidationMessage(LABELS.SSN_FIRST_3_CHARS, SUFFIXES.MUST_NOT_BE_ZERO),
  );
  expect(messageFor({ ssnPart1: '666' })).toBe(reserved);
  expect(messageFor({ ssnPart1: '900' })).toBe(reserved);
  expect(messageFor({ ssnPart1: '999' })).toBe(reserved);
  expect(refusalsFor({ ssnPart1: '899' })).toStrictEqual([]);
}

/**
 * Asserts a wholly empty telephone number is accepted and a partial one is refused part by part.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function acceptsAnEmptyTelephoneAndRefusesAPartialOne(): void {
  // WHY : Assumptions: the base values leave the second telephone number entirely blank, so the
  //       accepting case is already covered by the complete-submission case; what needs asserting is
  //       that emptying the FIRST number -- which the base fills -- is also accepted.
  expect(refusalsFor({ phone1AreaCode: '', phone1Prefix: '', phone1LineNumber: '' })).toStrictEqual(
    [],
  );

  /*
   * WHY : Assumptions: all three parts ACCUMULATE their refusals, which is the one structural
   *       difference between this paragraph and its siblings -- each part's failure branch ends in a
   *       `GO TO` the NEXT part rather than the exit (L2258, L2276 and onward), so a number with three
   *       bad parts marks three controls and the first still owns the sentence.
   */
  const outcome = refusalsFor({ phone1AreaCode: '000', phone1Prefix: 'AB', phone1LineNumber: '' });

  expect(outcome.map(fieldNameOf)).toStrictEqual([
    'phone1AreaCode',
    'phone1Prefix',
    'phone1LineNumber',
  ]);
  expect(outcome[0]?.message).toBe(
    formatFieldValidationMessage(LABELS.PHONE_NUMBER_1, SUFFIXES.AREA_CODE_CANNOT_BE_ZERO),
  );
  expect(outcome[1]?.message).toBe(
    formatFieldValidationMessage(
      LABELS.PHONE_NUMBER_1,
      SUFFIXES.PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER,
    ),
  );
  expect(outcome[2]?.message).toBe(
    formatFieldValidationMessage(LABELS.PHONE_NUMBER_1, SUFFIXES.LINE_NUMBER_CODE_MUST_BE_SUPPLIED),
  );
}

/**
 * Asserts an unknown state code is refused, and only once the structural edit has passed.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesAnUnknownStateCode(): void {
  const outcome = editAccountUpdateInputs(
    acceptableValues(),
    { stateCodeIsKnown: false, stateZipCombinationIsKnown: true },
    TODAY,
  );

  expect(outcome.message).toBe(
    formatFieldValidationMessage(LABELS.STATE, SUFFIXES.IS_NOT_A_VALID_STATE_CODE),
  );
  expect(outcome.refusals.map(fieldNameOf)).toStrictEqual(['stateCode']);

  /*
   * WHY : Assumptions: a state code that failed its OWN edit reports the structural sentence and never
   *       the lookup one, which is the `IF FLG-ALPHA-ISVALID` guard at L1601, and the outcome's
   *       structural flag is what tells the caller not to spend a request on it.
   */
  const structural = editAccountUpdateInputs(
    { ...acceptableValues(), stateCode: '1' },
    { stateCodeIsKnown: false, stateZipCombinationIsKnown: false },
    TODAY,
  );
  expect(structural.stateCodeStructurallyValid).toBe(false);
  expect(structural.message).toBe(
    formatFieldValidationMessage(LABELS.STATE, SUFFIXES.CAN_HAVE_ALPHABETS_ONLY),
  );
}

/**
 * Asserts the cross-field state-and-ZIP edit runs last and marks both controls.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesAnImpossibleStateAndZipCombination(): void {
  const outcome = editAccountUpdateInputs(
    acceptableValues(),
    { stateCodeIsKnown: true, stateZipCombinationIsKnown: false },
    TODAY,
  );

  const sentence = formatMessageTemplate(MESSAGE_TEMPLATES.INVALID_ZIP_CODE_FOR_STATE, {});
  expect(outcome.message).toBe(sentence);
  /*
   * WHY : Assumptions: BOTH controls are marked, which L2551-L2552 sets, and the sentence carries no
   *       field name at all -- it is a whole literal rather than a label and a suffix, which is why it
   *       comes from the template group and not from the label join.
   */
  expect(outcome.refusals.map(fieldNameOf)).toStrictEqual(['stateCode', 'zipCode']);
  expect(sentence).not.toContain(LABELS.STATE);

  // WHY : Assumptions: the cross-field edit runs LAST (L1665-L1668), so any structural refusal owns the
  //       sentence ahead of it -- here the account status, which is step 1.
  const earlier = editAccountUpdateInputs(
    { ...acceptableValues(), activeStatus: '' },
    { stateCodeIsKnown: true, stateZipCombinationIsKnown: false },
    TODAY,
  );
  expect(earlier.message).toBe(
    formatFieldValidationMessage(LABELS.ACCOUNT_STATUS, SUFFIXES.MUST_BE_SUPPLIED),
  );
}

/**
 * Asserts an unobtainable lookup answer refuses nothing.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function refusesNothingWhenALookupCouldNotBeAnswered(): void {
  /*
   * WHY : Assumptions: `null` must behave like "accepted here" and not like "absent", because the
   *       service validates the same two keys before it writes. Treating an unreachable lookup as a
   *       refusal would block a correct submission whenever the reference service was briefly
   *       unavailable, which is strictly worse than reporting the refusal one turn later.
   */
  expect(refusalsFor({}, LOOKUPS_UNKNOWN)).toStrictEqual([]);
}

/**
 * Asserts the lookup key is the state code followed by two ZIP digits.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function composesTheFourCharacterLookupKey(): void {
  expect(stateZipLookupKey('NY', '10001')).toBe('NY10');
  expect(stateZipLookupKey(' CA ', ' 90210 ')).toBe('CA90');
}

/**
 * Registers the edit-chain cases.
 * @returns {void} Registration is the effect.
 */
function editChainCases(): void {
  it('accepts a complete submission', acceptsACompleteSubmission);
  it('reports the first refusal in source order', reportsTheFirstRefusalInSourceOrder);
  it('runs the twenty-four steps in source order', runsTheTwentyFourStepsInSourceOrder);
  it('separates the blank flag from the malformed flag', separatesTheBlankFlagFromTheMalformedFlag);
  it('reports the numeric branches in source order', reportsTheNumericBranchesInSourceOrder);
  it('accepts a blank optional name', acceptsABlankOptionalName);
  it('reports the date parts in copybook order', reportsTheDatePartsInCopybookOrder);
  it(
    'admits only the nineteenth and twentieth centuries',
    admitsOnlyTheNineteenthAndTwentiethCenturies,
  );
  it('refuses impossible day and month combinations', refusesImpossibleDayAndMonthCombinations);
  it('refuses a date of birth that is not strictly past', refusesADateOfBirthThatIsNotStrictlyPast);
  it(
    'withholds the future check from a malformed date of birth',
    withholdsTheFutureCheckFromAMalformedDateOfBirth,
  );
  it(
    'checks the credit score range only on a numeric entry',
    checksTheCreditScoreRangeOnlyOnANumericEntry,
  );
  it('names each national identifier part separately', namesEachNationalIdentifierPartSeparately);
  it(
    'refuses the reserved national identifier prefixes',
    refusesTheReservedNationalIdentifierPrefixes,
  );
  it(
    'accepts an empty telephone and refuses a partial one',
    acceptsAnEmptyTelephoneAndRefusesAPartialOne,
  );
  it('refuses an unknown state code', refusesAnUnknownStateCode);
  it('refuses an impossible state and zip combination', refusesAnImpossibleStateAndZipCombination);
  it(
    'refuses nothing when a lookup could not be answered',
    refusesNothingWhenALookupCouldNotBeAnswered,
  );
  it('composes the four character lookup key', composesTheFourCharacterLookupKey);
}

describe('account update field edits', editChainCases);
