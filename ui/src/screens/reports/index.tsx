/**
 * @file Transaction-report submission screen, mounted at `/reports`.
 *
 * Purpose
 * -------
 * The browser form standing in for mapset `CORPT00` (map `CORPT0A`, `DFHMDI ... SIZE=(24,80)`) and the
 * program that drives it, `app/cbl/CORPT00C.cbl`. An operator chooses one of three report types, supplies
 * a date range when the type is the caller-supplied one, confirms, and the screen starts an asynchronous
 * report run. The 42 `DFHMDF` definitions of the mapset -- 17 named fields and 25 anonymous ones -- reduce
 * to one `Radio.Group`, two date bounds, one confirmation character and one submit action.
 *
 * This screen holds no session state
 * ----------------------------------
 * Refactoring Rationale: the reference is pseudo-conversational and carries `CDEMO-PGM-CONTEXT` with
 * `88 CDEMO-PGM-ENTER VALUE 0` / `88 CDEMO-PGM-REENTER VALUE 1` across every turn, branching on it at
 * `app/cbl/CORPT00C.cbl` L177. That discriminator has NO counterpart here and no flag emulates it, per AAP
 * section 0.7.1: a stateless handler that renders from validation and response state has no
 * first-entry-versus-later-turn distinction to draw. Navigation is the router's history, identity is the
 * validated token, and the selection context is the request itself. The corollary is visible in this
 * module: nothing records that a turn has happened before, and the message band is a function of the
 * current validation outcome alone.
 *
 * The report is never rendered here
 * ---------------------------------
 * Assumptions: submission STARTS a run and resolves to a handle, never to the document. The reference
 * reaches the same asynchrony through a queue -- `SUBMIT-JOB-TO-INTRDR` at L462 writes 80-byte
 * job-control records with `EXEC CICS WRITEQ TD QUEUE ('JOBS')` at L517, and `app/csd/CARDDEMO.CSD`
 * L499-L505 defines that queue, described as 'SUBMIT JOBS FROM CICS', mapping it to the internal reader
 * through `DDNAME(INREADER)` with `RECORDSIZE(80) RECORDFORMAT(FIXED)`. The submitted job runs
 * `EXEC PROC=TRANREPT` (`app/jcl/TRANREPT.jcl`), so the operator was returned to the screen at once and
 * the document was produced elsewhere. The printed artifact is 133 columns wide and its amount bands
 * carry COBOL edit masks whose sign character differs between the detail line and the three total lines;
 * `ui/src/api/reporting.ts` declines to reproduce any of it, and so does this module. No synchronous
 * download is offered.
 *
 * Where the shell's three bands come from
 * ---------------------------------------
 * Assumptions: rows 1-3 (`TRNNAME`, `TITLE01`, `TITLE02`, `PGMNAME`, `CURDATE`, `CURTIME`), row 23
 * (`ERRMSG`) and row 24 (the key legend) are delegated to the shell through `useShellSlot`, which is the
 * one channel `ui/src/layout/AppShell.tsx` publishes for them. This module therefore composes no
 * `ScreenHeader`, no row-23 `MessageBand` and no `PfKeyBar` of its own; doing so would render a second
 * live region for one message.
 */

import {
  Button,
  DatePicker,
  Flex,
  Form,
  Input,
  Popconfirm,
  Radio,
  Spin,
  Typography,
  theme,
} from 'antd';
import type { InputRef, RadioChangeEvent } from 'antd';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
// Assumptions: routing comes from `react-router`, never from `react-router-dom`, which is deliberately
// ABSENT from `ui/package.json`. No 8.x of the companion package exists -- it is a thin shim depending on
// `react-router@7`, so importing it would silently pin routing a major version behind the one this tree
// declares. `ui/eslint.config.js` lists it under `no-restricted-imports`, so the build fails rather than
// resolving two routers.
import { useNavigate } from 'react-router';

import { isApiRequestError } from '../../api/client';
import { submitTransactionReport } from '../../api/reporting';
import type { ReportRequest, ReportSubmission, ReportSubmissionOutcome } from '../../api/reporting';
import type { ApiError } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { fieldAriaProps, fieldErrorHelp, fieldHintId } from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import {
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_TEXT_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/*
 * WHY : Assumptions: every sentence this screen paints on row 23 is imported rather than retyped, because
 *       `ui/src/messages/messages.ts` is the single owner of baseline message text and carries each
 *       string's originating line beside it. Retyping one would put a second copy in the tree with nothing
 *       to compare it against, and the failure mode is a single character -- a dropped ellipsis dot or a
 *       doubled space -- which reads as correct in review and registers as a golden-master parity failure.
 *       All nineteen of this program's sentences live under its own group.
 */
const REPORT_MESSAGES = PROGRAM_MESSAGES.CORPT00C;

/** CICS transaction identifier this screen replaces, from `WS-TRANID` at `app/cbl/CORPT00C.cbl` L38. */
export const REPORTS_TRANSACTION_ID = 'CR00';

/** Source program name, from `WS-PGMNAME` at `app/cbl/CORPT00C.cbl` L37. */
export const REPORTS_PROGRAM_NAME = 'CORPT00C';

/**
 * Mapset this screen stands in, which selects the message band's rendered display width.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` records `CORPT00` at 78 characters, matching
 * `ERRMSG ... LENGTH=78` at `app/bms/CORPT00.bms` L218-L221 and `ERRMSGI PIC X(78)` at
 * `app/cpy-bms/CORPT00.CPY` L120. That is NOT the 75 of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` at
 * `app/cpy/CVCRD01Y.cpy` L28-L29, which the band carries as its own constant for the screens whose text
 * comes through the shared work area -- this program moves `WS-MESSAGE` straight into `ERRMSGO` at
 * `app/cbl/CORPT00C.cbl` L560 and never touches the shared field. The width is therefore taken from the
 * mapset table rather than written as a number here, so the two contracts cannot be confused.
 */
export const REPORTS_MAPSET = 'CORPT00';

/**
 * Screen title, verbatim from the row-4 heading at `app/bms/CORPT00.bms` L75-L79.
 *
 * Assumptions: the field is `LENGTH=19 COLOR=NEUTRAL ATTRB=(ASKIP,BRT)`, and brightness resolves to font
 * weight through `TYPOGRAPHY_TOKENS.brightEmphasis` rather than to a colour, because the field already
 * carries a colour of its own -- expressing brightness as colour would collide with it.
 */
export const REPORTS_TITLE = 'Transaction Reports';

/**
 * The three report types, in the order the reference evaluates them.
 *
 * Assumptions: the order is load-bearing rather than cosmetic. `app/cbl/CORPT00C.cbl` L212-L256 is a
 * single `EVALUATE TRUE` testing monthly at L213, yearly at L239 and custom at L256, so the FIRST
 * non-blank mark wins and the operator's own reading order down rows 7, 9 and 11 is the precedence order.
 */
export const REPORT_TYPES = ['monthly', 'yearly', 'custom'] as const;

/** One of the three mutually exclusive report types the mapset offers. */
export type ReportType = (typeof REPORT_TYPES)[number];

/**
 * The three selector prompts, verbatim from their `INITIAL=` literals in `app/bms/CORPT00.bms`.
 *
 * ⚠️ Assumptions: these are the full 23-character captions the operator reads and are NOT the report
 * names. Each is a separate `LENGTH=23 COLOR=TURQUOISE ATTRB=(ASKIP,BRT)` field sitting beside its
 * one-character selector, while {@link REPORT_TYPE_NAMES} below holds the bare words the program moves
 * into `WS-REPORT-NAME` and interpolates into two of its sentences. Both sets exist in the baseline and
 * neither is derivable from the other, so conflating them would either put `Monthly (Current Month)`
 * inside a message the reference spells `Monthly`, or strip the caption an operator uses to choose.
 */
export const REPORT_TYPE_PROMPTS: Readonly<Record<ReportType, string>> = {
  /** `app/bms/CORPT00.bms` L89-L93. */
  monthly: 'Monthly (Current Month)',
  /** `app/bms/CORPT00.bms` L103-L107. */
  yearly: 'Yearly (Current Year)',
  /** `app/bms/CORPT00.bms` L117-L121. */
  custom: 'Custom (Date Range)',
};

/**
 * The bare report names the program interpolates into its confirm and acknowledgement sentences.
 *
 * Assumptions: each is the literal moved into `WS-REPORT-NAME PIC X(10)` -- `'Monthly'` at
 * `app/cbl/CORPT00C.cbl` L214, `'Yearly'` at L240 and `'Custom'` at L433 -- and both sentences take it
 * `DELIMITED BY SPACE`, so the trailing blanks of the ten-character field never reach the operator. The
 * values are imported from the message catalog for the two the catalog records rather than retyped.
 */
export const REPORT_TYPE_NAMES: Readonly<Record<ReportType, string>> = {
  monthly: REPORT_MESSAGES.MONTHLY,
  yearly: REPORT_MESSAGES.YEARLY,
  custom: REPORT_MESSAGES.CUSTOM,
};

/** The two date bounds the custom report reads, in the mapset's own row order (13 then 14). */
export const DATE_BOUNDS = ['start', 'end'] as const;

/** Either end of the inclusive processing-date range a custom report is run over. */
export type DateBound = (typeof DATE_BOUNDS)[number];

/** The three parts each bound is keyed as, in the mapset's own column order. */
export const DATE_PARTS = ['month', 'day', 'year'] as const;

/** One of the three fields a single date bound is split across on the terminal. */
export type DatePart = (typeof DATE_PARTS)[number];

/**
 * Declared width of each date part, from the symbolic map `CORPT0AI` in `app/cpy-bms/CORPT00.CPY`.
 *
 * Assumptions: these are the `maxLength` values, so a part cannot accept more characters than the 3270
 * field held. ADR-006 states that an input's maximum length is its copybook picture width, and the six
 * fields are `SDTMMI PIC X(2)` L78, `SDTDDI PIC X(2)` L84, `SDTYYYYI PIC X(4)` L90, `EDTMMI PIC X(2)`
 * L96, `EDTDDI PIC X(2)` L102 and `EDTYYYYI PIC X(4)` L108.
 */
export const DATE_PART_WIDTHS: Readonly<Record<DatePart, number>> = {
  month: 2,
  day: 2,
  year: 4,
};

/**
 * Declared width of the confirmation field, `CONFIRMI PIC X(1)` at `app/cpy-bms/CORPT00.CPY` L114.
 *
 * Assumptions: one character exactly, which is what makes the reference's `WHEN OTHER` arm at
 * `app/cbl/CORPT00C.cbl` L484-L493 quote a single keystroke back at the operator.
 */
export const CONFIRM_WIDTH = 1;

/**
 * The captions and hints the mapset paints around the two date bounds and the confirmation, verbatim.
 *
 * ⚠️ Assumptions: `endDate` carries TWO leading spaces. The literal at `app/bms/CORPT00.bms` L161-L165 is
 * `'  End Date :'`, declared `LENGTH=12` exactly as `'Start Date :'` at L122-L126 is, and the two spaces
 * are the mapset's own right-alignment of the shorter caption against the longer one. Trimming them would
 * be a one-character-class edit to text the screen tests read byte for byte.
 *
 * Trade-offs: the two spaces are carried into the DOM verbatim and are then COLLAPSED by HTML's default
 * whitespace handling, so the caption reads `End Date :` on screen while the string still measures twelve
 * characters. That is deliberate rather than overlooked: the spaces are character-cell alignment on a
 * fixed-pitch 24x80 grid, which is exactly what AAP gap G1 declines to reproduce, and forcing them to
 * paint with a preformatted whitespace rule would indent this caption against its sibling in a
 * proportional face instead of aligning the two colons. The verbatim-text guarantee is about the string
 * the screen carries, and that is preserved; the terminal's column arithmetic is not.
 *
 * Assumptions: `confirmation` also ends with a space. It is a `LENGTH=59 COLOR=TURQUOISE` field written
 * as a BMS continuation across L200-L205, and the trailing blank separated the sentence from the
 * one-character input that followed it on the same row.
 */
export const REPORTS_CAPTIONS = {
  /** `app/bms/CORPT00.bms` L122-L126, `LENGTH=12 COLOR=TURQUOISE`. */
  startDate: 'Start Date :',
  /** `app/bms/CORPT00.bms` L161-L165, `LENGTH=12 COLOR=TURQUOISE`; the two leading spaces are in source. */
  endDate: '  End Date :',
  /** `app/bms/CORPT00.bms` L157-L160 and L196-L199, `LENGTH=12 COLOR=BLUE`; painted once per bound. */
  dateFormatHint: '(MM/DD/YYYY)',
  /** `app/bms/CORPT00.bms` L200-L205, `LENGTH=59`; the trailing space is in source. */
  confirmation: 'The Report will be submitted for printing. Please confirm: ',
  /** `app/bms/CORPT00.bms` L213-L217, `LENGTH=5 COLOR=NEUTRAL`. */
  confirmDomainHint: '(Y/N)',
} as const;

/**
 * The separator the mapset paints between the parts of a date, verbatim.
 *
 * Assumptions: four `LENGTH=1 COLOR=BLUE` fields carry it, two per bound -- `app/bms/CORPT00.bms` L133-L137
 * and L144-L148 on row 13, L172-L176 and L183-L187 on row 14. It is rendered twice per bound here rather
 * than four times as a set, because a bound is rendered once and the separator belongs to it.
 */
export const DATE_PART_SEPARATOR = '/';

/**
 * Legend labels for the two keys this screen paints, split from the row-24 literal.
 *
 * ⚠️ Assumptions: the row-24 field is ONE literal, `'ENTER=Continue  F3=Back'`, declared `LENGTH=23
 * COLOR=YELLOW` at `app/bms/CORPT00.bms` L222-L226, and the two spaces between the segments are in the
 * source. Splitting it into two labels is what lets each sit on the control that performs it, and the
 * parts still join back to the declared 23 characters.
 *
 * Assumptions: there are exactly TWO entries because the legend advertises exactly two keys and the
 * dispatch honours exactly two: `EVALUATE EIBAID` at `app/cbl/CORPT00C.cbl` L184-L195 handles `DFHENTER`
 * and `DFHPF3` and sends every other attention identifier to `CCDA-MSG-INVALID-KEY`. `F4=Clear` is NOT
 * imported from the uniform legend set here, because this mapset does not paint it and this program does
 * not dispatch it.
 */
export const REPORTS_KEY_LABELS = {
  ENTER: 'ENTER=Continue',
  PFK03: 'F3=Back',
} as const;

/*
 * WHY : Assumptions: nothing is rendered for the six anonymous zero-length fields at (7,12), (9,12),
 *       (11,12), (13,44), (14,44) and (19,68) -- `app/bms/CORPT00.bms` L86, L100, L114, L155, L194 and
 *       L211. A `DFHMDF` with `LENGTH=0` emits an attribute byte that terminates the preceding
 *       unprotected field so the hardware knows where operator input stops; it displays nothing and holds
 *       nothing. They are counted in the mapset's 42 definitions and have no UI analogue whatsoever, so
 *       their absence here is deliberate and is recorded so a later reader does not take it for six
 *       dropped fields.
 * WHY : Assumptions: `HILIGHT=UNDERLINE` needs no token. It appears seven times on this mapset -- on the
 *       three selectors, the six date parts and the confirmation -- and it marks a field as accepting
 *       input. Ant Design expresses that affordance structurally through the control's own border, so
 *       there is nothing to map. This is AAP design gap G4, recorded rather than left as an unexplained
 *       absence.
 * WHY : Assumptions: no `'*'` blank marker is rendered anywhere on this screen, and the reference is what
 *       settles it. `app/cpy/CSSETATY.cpy` L17-L27 is the templated highlight that moves `DFHRED` into a
 *       field's colour and additionally moves a literal asterisk into a BLANK one, and this program's
 *       `COPY` list at `app/cbl/CORPT00C.cbl` L138-L149 does not include it. Screens that do copy it show
 *       the marker; inventing one here would add a character this map never displayed.
 */

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

/**
 * Matches every character a 3270 numeric-only field would have refused, for stripping on entry.
 *
 * Assumptions: the accepted set is the digits, the minus sign and the period -- the character set a field
 * declared `ATTRB=(...,NUM,...)` admits on the terminal -- and not the digits alone. All six date parts
 * carry that attribute (`app/bms/CORPT00.bms` L127, L138, L149, L166, L177, L188), and reproducing the
 * attribute's real character set rather than a stricter guess is what keeps the reference's `IS NOT NUMERIC`
 * arms reachable instead of silently unreachable.
 */
const NON_NUMERIC_FIELD_CHARACTERS = /[^0-9.-]/gu;

/** The calendar mask both `DatePicker` controls display and parse, from `REPORTS_CAPTIONS`. */
const DATE_PICKER_FORMAT = 'MM/DD/YYYY';

/**
 * The interchange form both bounds travel in, and the mask the reference validates them against.
 *
 * Assumptions: `app/cbl/CORPT00C.cbl` L72 declares `WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'` and
 * passes it to `CSUTLDTC` at L389 and L409, while L60-L71 build `WS-START-DATE` and `WS-END-DATE` as
 * year-month-day groups joined by hyphens. The baseline therefore already normalises the six keyed parts
 * into this exact form before it validates or transmits them, which is why composing it here is a port
 * rather than an invention.
 */
const ISO_DATE_FORMAT = 'YYYY-MM-DD';

/**
 * The two answers the reference accepts as consent, from `app/cbl/CORPT00C.cbl` L478.
 *
 * Assumptions: an explicit PAIR rather than a case-folded comparison, because the reference writes
 * `= 'Y' OR 'y'` and admits no other spelling -- a full-width or accented letter that folds to `y` is not
 * consent there and is not consent here.
 */
const CONFIRMING_ANSWERS = ['Y', 'y'] as const;

/** The two answers the reference accepts as refusal, from `app/cbl/CORPT00C.cbl` L480. */
const DECLINING_ANSWERS = ['N', 'n'] as const;

/**
 * The consenting character this screen writes and relays.
 *
 * Assumptions: named from the tuple's first element rather than retyped, so the value the confirmation
 * dialogue writes into the field and the value the request carries cannot drift apart. It is also the mark
 * written into the chosen report-type member, matching the reference's own test that a selector is merely
 * not blank.
 */
const CONSENTING_ANSWER: string = CONFIRMING_ANSWERS[0];

/** Identifier of one control this screen can report a refusal against. */
export type ReportsField = `${DateBound}-${DatePart}` | 'reportType' | 'confirm';

/** The three keyed parts of one date bound, each held as the characters the operator entered. */
export type DatePartValues = Record<DatePart, string>;

/** Both date bounds, each split into the three parts the mapset collects them in. */
export type DateRangeValues = Record<DateBound, DatePartValues>;

/** One control the screen is reporting a refusal against, with the sentence to render beneath it. */
export interface ReportsFieldError {
  /** Control the refusal names. */
  readonly field: ReportsField;
  /** Verbatim sentence from the message catalog. */
  readonly message: string;
}

/** An inclusive processing-date range, both bounds already in the interchange form. */
export interface ReportDateRange {
  /** Lower bound, inclusive, as `YYYY-MM-DD`. */
  readonly startDate: string;
  /** Upper bound, inclusive, as `YYYY-MM-DD`. */
  readonly endDate: string;
}

/**
 * Composes the empty, range and calendar values a bound is refused with.
 *
 * Assumptions: every entry below is a NAMED reference to one catalog constant and nothing is built by
 * joining a prefix to a suffix. The start and end families differ only by the words `Start Date - ` and
 * `End Date - `, which makes a manufacturing helper look attractive and is exactly why one is refused: the
 * fourteen sentences are fourteen separate baseline literals, and a generator would let a change to the
 * shared part silently rewrite seven strings that a golden-master comparison reads byte for byte.
 */
const DATE_REFUSAL_MESSAGES: Readonly<
  Record<
    DateBound,
    { readonly empty: DatePartValues; readonly range: DatePartValues; readonly calendar: string }
  >
> = {
  start: {
    empty: {
      month: REPORT_MESSAGES.START_DATE_MONTH_CAN_NOT_BE_EMPTY,
      day: REPORT_MESSAGES.START_DATE_DAY_CAN_NOT_BE_EMPTY,
      year: REPORT_MESSAGES.START_DATE_YEAR_CAN_NOT_BE_EMPTY,
    },
    range: {
      month: REPORT_MESSAGES.START_DATE_NOT_A_VALID_MONTH,
      day: REPORT_MESSAGES.START_DATE_NOT_A_VALID_DAY,
      year: REPORT_MESSAGES.START_DATE_NOT_A_VALID_YEAR,
    },
    calendar: REPORT_MESSAGES.START_DATE_NOT_A_VALID_DATE,
  },
  end: {
    empty: {
      month: REPORT_MESSAGES.END_DATE_MONTH_CAN_NOT_BE_EMPTY,
      day: REPORT_MESSAGES.END_DATE_DAY_CAN_NOT_BE_EMPTY,
      year: REPORT_MESSAGES.END_DATE_YEAR_CAN_NOT_BE_EMPTY,
    },
    range: {
      month: REPORT_MESSAGES.END_DATE_NOT_A_VALID_MONTH,
      day: REPORT_MESSAGES.END_DATE_NOT_A_VALID_DAY,
      year: REPORT_MESSAGES.END_DATE_NOT_A_VALID_YEAR,
    },
    calendar: REPORT_MESSAGES.END_DATE_NOT_A_VALID_DATE,
  },
};

/**
 * Highest value the reference accepts for a month, compared as characters at `app/cbl/CORPT00C.cbl` L330.
 *
 * Assumptions: the reference writes `SDTMMI > '12'`, a CHARACTER comparison on a `PIC X(2)` field, and
 * this constant is the numeric reading of it. The two orderings coincide here and only because the field
 * is exactly two characters wide after normalisation: over the fixed-width digit strings `'00'` through
 * `'99'` the lexical and numeric orders are identical. A wider field would break that equivalence, which
 * is why the width is asserted by {@link DATE_PART_WIDTHS} rather than assumed.
 */
const HIGHEST_MONTH = 12;

/** Highest value the reference accepts for a day, from the character comparison at L339 and L365. */
const HIGHEST_DAY = 31;

/** Lowest value the reference's calendar validator accepts for a month or a day, both being one-based. */
const LOWEST_MONTH_OR_DAY = 1;

/** Days in each month for a non-leap year, indexed from January, used by {@link daysInMonth}. */
const DAYS_PER_MONTH: readonly number[] = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

/** Day count February carries in a leap year. */
const LEAP_FEBRUARY_DAYS = 29;

/** One-based ordinal of February, the one month whose length depends on the year. */
const FEBRUARY = 2;

/**
 * Reports whether a year is a leap year under the proleptic Gregorian rule.
 *
 * Assumptions: the rule is spelled out rather than delegated to a date library, and the reason is a
 * measured artefact rather than a preference. `dayjs('0001-01-01')` resolves to `1901-01-01`, because the
 * underlying `Date` maps a year below 100 into the twentieth century, so any well-formedness test routed
 * through a parsed date misreports every year the four-character `SDTYYYY` field can hold below 0100. The
 * reference has no such window: `CSUTLDTC` calls `CEEDAYS`, which applies the Gregorian rule over its
 * whole supported span and answers an out-of-span year with a feedback code the caller tolerates.
 * @param {number} year - Four-digit year to classify.
 * @returns {boolean} `true` when February carries twenty-nine days in that year.
 */
function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

/**
 * Reports how many days a month carries in a given year.
 * @param {number} year - Four-digit year, which decides February's length.
 * @param {number} month - One-based month ordinal, which the caller has already range-checked.
 * @returns {number} Day count for that month, or zero when the month ordinal is outside one to twelve.
 */
function daysInMonth(year: number, month: number): number {
  if (month < LOWEST_MONTH_OR_DAY || month > HIGHEST_MONTH) {
    return 0;
  }
  if (month === FEBRUARY && isLeapYear(year)) {
    return LEAP_FEBRUARY_DAYS;
  }
  return DAYS_PER_MONTH[month - 1] ?? 0;
}

/**
 * Normalises one keyed date part the way the reference does before it range-checks it.
 *
 * Purpose: the port of `app/cbl/CORPT00C.cbl` L305-L327, where each part is passed through
 * `FUNCTION NUMVAL-C` into a `PIC 99` or `PIC 9999` item and moved straight back into the map field. The
 * observable effect is a zero-pad to the declared width, so a keyed `7` becomes `07` and a keyed `7` in the
 * year field becomes `0007`, and it happens BEFORE the range comparison rather than after it.
 *
 * Assumptions: a value that is not a well-formed run of digits is returned UNCHANGED rather than coerced
 * to zero, and that is what keeps the reference's own `IS NOT NUMERIC` arms reachable. `NUMVAL-C` is
 * specified over numeric-character strings and its result is undefined on anything else, which is
 * precisely why the reference tests `IS NOT NUMERIC` after calling it. Coercing such a value to `0` would
 * pad it to `00`, which IS numeric and is not greater than `12`, so a malformed month would slip past the
 * check the reference placed there and be reported later as a calendar failure under a different sentence.
 *
 * Alternatives Considered: returning zero for an unparseable value, matching the behaviour GnuCOBOL
 * happens to exhibit. Rejected because it relies on an implementation detail the standard leaves
 * undefined, and because it would make two of the fourteen sentences -- both `Not a valid Year` arms,
 * whose only predicate is `IS NOT NUMERIC` -- unreachable from the user interface.
 * @param {string} raw - Characters the operator entered, which may carry surrounding blanks.
 * @param {number} width - Declared width of the field from {@link DATE_PART_WIDTHS}, the pad target.
 * @returns {string} The value zero-padded to `width` when it is a run of digits, otherwise the trimmed
 *   value unchanged so that the caller's numeric test still refuses it.
 */
export function normaliseDatePart(raw: string, width: number): string {
  const trimmed = raw.trim();
  if (!DIGITS_ONLY.test(trimmed)) {
    return trimmed;
  }
  return trimmed.padStart(width, '0');
}

/**
 * Reports whether a normalised part satisfies COBOL's `IS NUMERIC` on a `PIC X` field.
 * @param {string} value - Normalised part to test.
 * @returns {boolean} `true` when every character is a decimal digit and at least one is present.
 */
function isNumericPart(value: string): boolean {
  return DIGITS_ONLY.test(value);
}

/**
 * Reports whether a part was left empty, which is COBOL's `= SPACES OR LOW-VALUES` test.
 *
 * Assumptions: an all-blank entry counts as empty as well as a zero-length one, because the map field
 * arrives space-filled -- `SDTMM ... INITIAL='  '` at `app/bms/CORPT00.bms` L127-L132 -- so `SPACES` is
 * the state a field the operator never touched is actually in.
 * @param {string} raw - Characters the operator entered.
 * @returns {boolean} `true` when the field holds nothing but blanks.
 */
function isEmptyPart(raw: string): boolean {
  return raw.trim().length === 0;
}

/**
 * Joins three normalised parts into the ten-character interchange form both bounds travel in.
 *
 * Purpose: the single boundary at which this screen's split-part contract ends. The terminal collected a
 * date as three fields because a 3270 field could not carry a mask; `ui/src/api/reporting.ts` takes each
 * bound as one calendar value; this function is the only place the two representations meet.
 *
 * Assumptions: the target form is a port and not an invention. `app/cbl/CORPT00C.cbl` L60-L71 declares
 * `WS-START-DATE` and `WS-END-DATE` as a four-character year, a hyphen, a two-character month, a hyphen
 * and a two-character day, L381-L386 moves the six keyed parts into exactly those subfields, and L72
 * declares `WS-DATE-FORMAT VALUE 'YYYY-MM-DD'` which L389 hands to the calendar validator. The reference
 * therefore already normalises into this form before it validates or transmits, so composing it here
 * reproduces a step the baseline performs rather than adding one.
 * @param {DatePartValues} parts - The three normalised parts of one bound, already zero-padded.
 * @returns {string} The bound as `YYYY-MM-DD`, assembled in the reference's own field order.
 */
export function composeIsoDate(parts: DatePartValues): string {
  const year = parts.year.padStart(DATE_PART_WIDTHS.year, '0');
  const month = parts.month.padStart(DATE_PART_WIDTHS.month, '0');
  const day = parts.day.padStart(DATE_PART_WIDTHS.day, '0');
  return `${year}-${month}-${day}`;
}

/**
 * Reports whether three normalised parts name a date that exists on the calendar.
 *
 * Purpose: the port of the two `CALL 'CSUTLDTC'` invocations at `app/cbl/CORPT00C.cbl` L392-L394 and
 * L412-L414, together with the acceptance rule the caller applies to their result.
 *
 * ⚠️ Assumptions: the reference treats a NON-ZERO severity as an error only when the accompanying message
 * number is not `2513` -- L396-L399 and L416-L419. That number decodes: `app/cbl/CSUTLDTC.cbl` L66
 * declares `88 FC-UNSUPP-RANGE VALUE X'000309D159C3C5C5'`, whose condition identifier is severity 3 and
 * message number `0x09D1`, which is 2513. So the one non-zero outcome the reference forgives is
 * `CEEDAYS` reporting a WELL-FORMED date that falls outside its supported span, while the outcomes it
 * refuses -- insufficient data, bad date value, invalid month, non-numeric data -- all describe a date
 * that does not exist. This function therefore tests EXISTENCE and says nothing about span, which
 * reproduces both halves of that rule at once: an out-of-span but well-formed date passes here exactly as
 * it passes there.
 *
 * Alternatives Considered: a round-trip through `dayjs`, comparing the reparsed value with the composed
 * one. It detects every malformed case correctly -- measured on the thirty-first of February, month zero,
 * month thirteen and day zero -- and was rejected on the span half of the rule: `dayjs('0001-01-01')`
 * resolves to `1901-01-01`, so a well-formed year below 0100 would fail the comparison and be reported as
 * a bad date, which is the one case the reference explicitly forgives.
 * @param {DatePartValues} parts - The three normalised parts of one bound.
 * @returns {boolean} `true` when the parts name a real calendar date.
 */
function isExistingCalendarDate(parts: DatePartValues): boolean {
  if (!isNumericPart(parts.year) || !isNumericPart(parts.month) || !isNumericPart(parts.day)) {
    return false;
  }
  const year = Number.parseInt(parts.year, 10);
  const month = Number.parseInt(parts.month, 10);
  const day = Number.parseInt(parts.day, 10);
  return day >= LOWEST_MONTH_OR_DAY && day <= daysInMonth(year, month);
}

/**
 * Resolves the inclusive range a monthly report covers: the first and last day of the current month.
 *
 * Purpose: the port of `app/cbl/CORPT00C.cbl` L214-L238.
 *
 * ⚠️ Assumptions: `endOf('month')` is used in place of the reference's four-statement arithmetic, and the
 * two were MEASURED equivalent rather than assumed so. The reference moves `1` into the day, adds `1` to
 * the month, rolls the year and resets the month to `1` when the month then exceeds twelve (L223-L228),
 * and finally takes `FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(...) - 1)` (L229-L230) -- the last
 * day of the original month reached by stepping back one day from the first of the next. Both forms were
 * evaluated over a thirty-one-day month, a thirty-day month, February in a leap year, February in a
 * non-leap year and December, and agreed on every one, including the December case where the reference's
 * month-thirteen branch is the only thing that keeps the year correct.
 *
 * Assumptions: the instant is a PARAMETER rather than read from the clock inside this function, so the
 * screen supplies a server-anchored value and a test can supply a fixed one. The reference reads
 * `FUNCTION CURRENT-DATE` at L215, which is the region's clock; anchoring on the server is the target's
 * equivalent of that and is what stops two operators in different time zones resolving different months.
 * @param {Dayjs} now - Instant the current month is taken from.
 * @returns {ReportDateRange} The first and last day of that month, both as `YYYY-MM-DD`.
 */
export function computeMonthlyRange(now: Dayjs): ReportDateRange {
  return {
    startDate: now.startOf('month').format(ISO_DATE_FORMAT),
    endDate: now.endOf('month').format(ISO_DATE_FORMAT),
  };
}

/**
 * Resolves the inclusive range a yearly report covers: the first and last day of the current year.
 *
 * Purpose: the port of `app/cbl/CORPT00C.cbl` L240-L255, which moves the current year into both bounds,
 * `'01'` into the start month and start day (L245-L246), then `'12'` into the end month and `'31'` into
 * the end day (L250-L251). Those are literals rather than arithmetic, so no month-length question arises.
 *
 * Assumptions: the instant is a parameter for the same reason it is in {@link computeMonthlyRange}.
 * @param {Dayjs} now - Instant the current year is taken from.
 * @returns {ReportDateRange} The first and last day of that year, both as `YYYY-MM-DD`.
 */
export function computeYearlyRange(now: Dayjs): ReportDateRange {
  return {
    startDate: now.startOf('year').format(ISO_DATE_FORMAT),
    endDate: now.endOf('year').format(ISO_DATE_FORMAT),
  };
}

/** Outcome of putting a custom report's six keyed parts through the reference's three edit families. */
export interface CustomRangeValidation {
  /** Every control to mark, in the order the reference flags them. */
  readonly fieldErrors: readonly ReportsFieldError[];
  /** Sentence for the message band, or `null` when the range is acceptable. */
  readonly message: string | null;
  /** Control the cursor is placed on, or `null` when there is nothing to correct. */
  readonly focus: ReportsField | null;
  /** The composed bounds, present only when every edit passed. */
  readonly range: ReportDateRange | null;
}

/**
 * Puts a custom report's six keyed parts through the reference's three edit families, in order.
 *
 * Purpose: the port of `app/cbl/CORPT00C.cbl` L258-L426 -- the empty family, the range family and the two
 * calendar checks -- preserving the DIFFERENT precedence each family has.
 *
 * ⚠️ Trade-offs: the two families behave differently on purpose, because the reference expresses them with
 * two different constructs and the difference is observable. L258-L303 is a single `EVALUATE TRUE` whose
 * arms are the six emptiness tests, and an `EVALUATE` short-circuits: the FIRST empty field wins, the
 * remaining five are never tested, and one sentence is shown. L329-L379 is six independent sequential
 * `IF` statements, so every failing field is flagged and each one overwrites `WS-MESSAGE`, which leaves
 * the LAST failure as the sentence on the band. Collapsing the two into one shape would change what an
 * operator sees for a form with several bad parts.
 *
 * ⚠️ Assumptions: the flag-all reading of the range family is the STRUCTURAL one, and the qualification is
 * recorded rather than glossed. Each arm ends in `PERFORM SEND-TRNRPT-SCREEN`, and that paragraph ends
 * with `GO TO RETURN-TO-CICS` at L580, which issues `EXEC CICS RETURN` -- so on the terminal the task
 * actually ends at the first failing range check and the later ones never run. A stateless handler has no
 * task to end, and flagging every failing part is a superset of that behaviour in information terms: the
 * same sentence family, the same per-field marks, and nothing hidden that the terminal would have shown on
 * a later turn. The alternative -- stopping at the first range failure to mirror the transfer of control
 * -- would make the operator resubmit once per bad part to discover them all.
 *
 * Assumptions: normalisation runs BETWEEN the two families, never before the first, because the reference
 * places it at L305-L327 -- after the emptiness `EVALUATE` closes at L303 and before the first range `IF`
 * at L329. The order matters: normalising first would turn a blank field into `00` and defeat every one of
 * the six emptiness tests.
 * @param {DateRangeValues} values - The six parts exactly as the operator keyed them, untrimmed.
 * @returns {CustomRangeValidation} The marks to render, the sentence to paint, where to put the cursor,
 *   and the composed bounds when every edit passed.
 */
export function validateCustomRange(values: DateRangeValues): CustomRangeValidation {
  for (const bound of DATE_BOUNDS) {
    for (const part of DATE_PARTS) {
      if (isEmptyPart(values[bound][part])) {
        const message = DATE_REFUSAL_MESSAGES[bound].empty[part];
        return {
          fieldErrors: [{ field: `${bound}-${part}`, message }],
          message,
          focus: `${bound}-${part}`,
          range: null,
        };
      }
    }
  }

  const normalised: DateRangeValues = {
    start: normaliseBound(values.start),
    end: normaliseBound(values.end),
  };

  const rangeFailures: ReportsFieldError[] = [];
  let rangeMessage: string | null = null;
  let rangeFocus: ReportsField | null = null;

  for (const bound of DATE_BOUNDS) {
    for (const part of DATE_PARTS) {
      if (!isPartWithinRange(normalised[bound][part], part)) {
        const message = DATE_REFUSAL_MESSAGES[bound].range[part];
        rangeFailures.push({ field: `${bound}-${part}`, message });
        /*
         * WHY : Assumptions: the sentence and the cursor are OVERWRITTEN on every failure rather than kept
         *       from the first, which is what the sequential `IF` arms do -- each moves its own text into
         *       `WS-MESSAGE` and its own `-1` into a field's length, so the last arm to run owns both. The
         *       marks accumulate because each arm sets a different field; the sentence does not because
         *       every arm sets the same one.
         */
        rangeMessage = message;
        rangeFocus = `${bound}-${part}`;
      }
    }
  }

  if (rangeMessage !== null) {
    return { fieldErrors: rangeFailures, message: rangeMessage, focus: rangeFocus, range: null };
  }

  const calendarFailures: ReportsFieldError[] = [];
  let calendarMessage: string | null = null;
  let calendarFocus: ReportsField | null = null;

  for (const bound of DATE_BOUNDS) {
    if (!isExistingCalendarDate(normalised[bound])) {
      const message = DATE_REFUSAL_MESSAGES[bound].calendar;
      /*
       * WHY : Assumptions: the cursor goes to the MONTH part of the offending bound and not to the part
       *       that is actually wrong, because that is where the reference puts it -- L403 moves `-1` into
       *       `SDTMML` and L423 into `EDTMML`. The validator it calls reports on the date as a whole and
       *       names no part, so the month is the only position the reference can offer and the operator
       *       re-keys the bound from its start.
       */
      calendarFailures.push({ field: `${bound}-month`, message });
      calendarMessage = message;
      calendarFocus = `${bound}-month`;
    }
  }

  if (calendarMessage !== null) {
    return {
      fieldErrors: calendarFailures,
      message: calendarMessage,
      focus: calendarFocus,
      range: null,
    };
  }

  return {
    fieldErrors: [],
    message: null,
    focus: null,
    range: {
      startDate: composeIsoDate(normalised.start),
      endDate: composeIsoDate(normalised.end),
    },
  };
}

/**
 * Normalises all three parts of one bound to their declared widths.
 * @param {DatePartValues} parts - The three parts of one bound as the operator keyed them.
 * @returns {DatePartValues} The same three parts, each zero-padded when it is a run of digits.
 */
function normaliseBound(parts: DatePartValues): DatePartValues {
  return {
    month: normaliseDatePart(parts.month, DATE_PART_WIDTHS.month),
    day: normaliseDatePart(parts.day, DATE_PART_WIDTHS.day),
    year: normaliseDatePart(parts.year, DATE_PART_WIDTHS.year),
  };
}

/**
 * Reports whether a normalised part satisfies its own range arm in the reference.
 *
 * Assumptions: the three arms are not symmetrical and are transcribed as they stand. A month fails when it
 * is not numeric or exceeds twelve (`app/cbl/CORPT00C.cbl` L329-L330 and L355-L356); a day fails when it
 * is not numeric or exceeds thirty-one (L338-L339 and L364-L365); a year fails on the numeric test ALONE
 * (L347 and L373), because no upper bound is meaningful for a four-digit field. Note what none of the
 * three tests: a lower bound. Both `00` values pass here and are caught by the calendar family instead,
 * which is the reference's own division of labour and the reason {@link isExistingCalendarDate} has to
 * reject a zero month and a zero day rather than assume the range family already did.
 * @param {string} value - Normalised part to test.
 * @param {DatePart} part - Which of the three arms applies.
 * @returns {boolean} `true` when the part passes its arm.
 */
function isPartWithinRange(value: string, part: DatePart): boolean {
  if (!isNumericPart(value)) {
    return false;
  }
  if (part === 'month') {
    return Number.parseInt(value, 10) <= HIGHEST_MONTH;
  }
  if (part === 'day') {
    return Number.parseInt(value, 10) <= HIGHEST_DAY;
  }
  return true;
}

/**
 * What the operator's confirmation keystroke means, discriminated so no arm can be read as another.
 *
 * Assumptions: FOUR arms and not three. The reference's `SUBMIT-JOB-TO-INTRDR` answers a blank field, a
 * consent, a refusal and anything else differently, and the fourth is the one a reader is most likely to
 * lose because it is the `WHEN OTHER` of an inner `EVALUATE`.
 */
export type ConfirmationOutcome =
  | { readonly outcome: 'UNANSWERED'; readonly message: string }
  | { readonly outcome: 'CONFIRMED' }
  | { readonly outcome: 'DECLINED' }
  | { readonly outcome: 'UNRECOGNISED'; readonly message: string };

/**
 * Builds a predicate testing one accepted answer against what the operator keyed.
 *
 * Assumptions: a predicate factory rather than `Array.includes`, because both answer sets are `as const`
 * tuples of literal types and `includes` on one of those refuses an arbitrary `string` argument outright.
 * Widening the tuples to `readonly string[]` would accept the call and give up the literal types that make
 * {@link CONSENTING_ANSWER} a known character rather than a possibly-undefined index read.
 * @param {string} answer - Character the operator keyed.
 * @returns {(accepted: string) => boolean} Predicate reporting whether one accepted answer equals it.
 */
function matchesAnswer(answer: string): (accepted: string) => boolean {
  /**
   * Compares one accepted answer with the keyed character.
   * @param {string} accepted - One member of an accepted-answer tuple.
   * @returns {boolean} `true` when the keyed character is exactly that answer.
   */
  return function isThisAnswer(accepted: string): boolean {
    return accepted === answer;
  };
}

/**
 * Interprets the confirmation character against the reference's four-way branch.
 *
 * Purpose: the port of `app/cbl/CORPT00C.cbl` L462-L510.
 *
 * Assumptions: a blank answer is NOT a refusal. L464-L474 tests the field for `SPACES OR LOW-VALUES`
 * first, composes the prompt naming the report and returns to the screen with the cursor on the
 * confirmation field, so an operator who has not answered is asked again rather than told they cancelled.
 * The three-arm `EVALUATE` at L477-L494 is reached only once the field holds something.
 *
 * ⚠️ Assumptions: the unrecognised arm exists and quotes the keystroke back. L484-L493 composes a double
 * quote, the character `DELIMITED BY SPACE`, and the closing sentence, so keying `X` produces
 * `"X" is not a valid value to confirm...`. It is the least visible of the four outcomes in the source and
 * the easiest to drop; without it any character other than the four accepted ones would silently submit or
 * silently do nothing.
 *
 * Assumptions: both letters of each answer are accepted in either case because the reference tests
 * `= 'Y' OR 'y'` at L478 and `= 'N' OR 'n'` at L480 -- an explicit pair, not a case-folded comparison, so
 * no other spelling is admitted.
 * @param {string} answer - The single character in the confirmation field, as keyed.
 * @param {string} reportName - The bare report name to interpolate, one of {@link REPORT_TYPE_NAMES}.
 * @returns {ConfirmationOutcome} Which of the four arms applies, carrying the sentence for the two that
 *   have one.
 * @throws {Error} From `formatMessageTemplate` if a template's named value is not supplied, which cannot
 *   arise here because both calls below supply every name their template declares.
 */
export function evaluateConfirmation(answer: string, reportName: string): ConfirmationOutcome {
  if (answer.trim().length === 0) {
    return {
      outcome: 'UNANSWERED',
      message: formatMessageTemplate(MESSAGE_TEMPLATES.PLEASE_CONFIRM_TO_PRINT_REPORT, {
        'WS-REPORT-NAME': reportName,
      }),
    };
  }
  if (CONFIRMING_ANSWERS.some(matchesAnswer(answer))) {
    return { outcome: 'CONFIRMED' };
  }
  if (DECLINING_ANSWERS.some(matchesAnswer(answer))) {
    return { outcome: 'DECLINED' };
  }
  return {
    outcome: 'UNRECOGNISED',
    message: formatMessageTemplate(MESSAGE_TEMPLATES.NOT_A_VALID_VALUE_TO_CONFIRM, {
      CONFIRMI: answer,
    }),
  };
}

/** Which control each field name a service refusal can carry maps onto. */
const SERVICE_FIELD_TO_CONTROL: Readonly<Record<string, ReportsField>> = {
  monthly: 'reportType',
  yearly: 'reportType',
  custom: 'reportType',
  startDate: 'start-month',
  endDate: 'end-month',
  confirm: 'confirm',
};

/** What a refused submission leaves the screen holding: a sentence, marks and a cursor position. */
export interface SubmissionRefusal {
  /** Sentence for the message band. */
  readonly message: string;
  /** Controls to mark, which is empty when the refusal named no property. */
  readonly fieldErrors: readonly ReportsFieldError[];
  /** Control the cursor is placed on. */
  readonly focus: ReportsField;
}

/**
 * Turns a refused submission into the sentence, the marks and the cursor position the screen renders.
 *
 * Purpose: the port of the failure arm of `WIRTE-JOBSUB-TDQ` at `app/cbl/CORPT00C.cbl` L525-L535, extended
 * to spend the structured detail the target carries and the terminal never had.
 *
 * Assumptions: this screen OWNS the mapping from a problem document to per-control marks, because
 * `ui/src/layout/MessageBand.tsx` is deliberately presentational -- it takes a nullable sentence and a
 * severity and has no `ApiError` in its props at all. Nothing upstream will distribute `fieldErrors` onto
 * controls, so a screen that did not do it here would drop every one of them.
 *
 * Assumptions: the band sentence is the reference's own `'Unable to Write TDQ (JOBS)...'` (L531) and never
 * the service's `message`, and the cursor goes to the report-type selector because L533 moves `-1` into
 * `MONTHLYL`. The service's own text is not shown for a reason that outlives this one call: it is not a
 * baseline literal, so painting it on row 23 would put a sentence on the parity surface that no golden
 * master contains. The structured `fieldErrors` ARE used, because they name controls rather than compose
 * prose, and each carries its own already-verbatim sentence beneath the control it names.
 *
 * Trade-offs: a refusal naming a property this screen does not render contributes no mark, and the band
 * sentence alone reports it. The alternative -- inventing a control to hang it on -- would tell an operator
 * that a field they can see is wrong when the service named one they cannot.
 * @param {unknown} failure - The value the submission rejected with, of any shape.
 * @returns {SubmissionRefusal} The sentence, the marks and the cursor position to apply.
 */
export function mapSubmissionFailure(failure: unknown): SubmissionRefusal {
  const message = REPORT_MESSAGES.UNABLE_TO_WRITE_TDQ_JOBS;
  if (!isApiRequestError(failure)) {
    return { message, fieldErrors: [], focus: 'reportType' };
  }

  const problem: ApiError = failure.problem;
  const fieldErrors: ReportsFieldError[] = [];
  for (const entry of problem.fieldErrors) {
    const control = SERVICE_FIELD_TO_CONTROL[entry.field];
    if (control !== undefined) {
      fieldErrors.push({ field: control, message: entry.message });
    }
  }
  return { message, fieldErrors, focus: 'reportType' };
}

/** The empty state both bounds start in, matching the space-filled map fields the mapset declares. */
const EMPTY_RANGE: DateRangeValues = {
  start: { month: '', day: '', year: '' },
  end: { month: '', day: '', year: '' },
};

/**
 * Sets the one report-type mark the chosen type corresponds to, leaving the other two absent.
 *
 * Assumptions: written as a branch rather than a computed key, and `exactOptionalPropertyTypes` is why. The
 * three members are optional, so the compiler distinguishes an ABSENT member from one present with the
 * value `undefined`, and a computed key would produce an index signature that satisfies neither reading.
 * Returning one of three concrete objects keeps the two unset marks genuinely absent from the serialised
 * body, which is what the reference's `= SPACES OR LOW-VALUES` test reads.
 *
 * Assumptions: the mark's value is the consenting character rather than a boolean, because the map field is
 * `PIC X(1)` and the reference tests only that it is not blank -- `app/cbl/CORPT00C.cbl` L213, L239 and
 * L256 -- so any non-blank character selects and the value carried is a character.
 * @param {ReportType} chosen - Report type the operator selected.
 * @returns {Pick<ReportRequest, ReportType>} An object carrying exactly one of the three marks.
 */
function reportTypeMarks(chosen: ReportType): Pick<ReportRequest, ReportType> {
  if (chosen === 'monthly') {
    return { monthly: CONSENTING_ANSWER };
  }
  if (chosen === 'yearly') {
    return { yearly: CONSENTING_ANSWER };
  }
  return { custom: CONSENTING_ANSWER };
}

/**
 * The transaction-report submission screen.
 *
 * Purpose: renders the report-type choice, the custom date range, the confirmation and the submit action,
 * runs the reference's edit chain over what the operator entered, and starts one asynchronous report run.
 * Delegates the title band, the message line and the key legend to the shell.
 *
 * Assumptions: no group claim is read and no route guard is applied. `/reports` is not one of the six
 * admin-only routes -- the admin option table `app/cpy/COADM02Y.cpy` holds the user screens and the two
 * reference screens, and this program is main-menu option 9 with `CDEMO-MENU-OPT-USRTYPE = 'U'` -- so any
 * authenticated operator reaches it and the router owns the authentication check.
 * @returns {ReactElement} The screen's own content region, without the three delegated bands.
 */
export function ReportsScreen(): ReactElement {
  /*
   * WHY : ⚠️ Refactoring Rationale: `cssVar` is destructured, NOT `token`. Both members of
   *       `theme.useToken()` are the same `GlobalToken` shape, so the two are interchangeable to the
   *       compiler and the difference is only visible in the emitted DOM: `token` holds the RESOLVED
   *       value, so an inline style reads `color: rgba(0, 0, 0, 0.65)`, while `cssVar` holds
   *       `var(--ant-color-text-secondary)`. This screen read `token` and was measured against the
   *       rendered page as the only module in the tree emitting resolved literals -- every other screen
   *       and every layout module reads `cssVar` -- which failed AAP section 0.3.2's zero-hardcoded-values
   *       rule on inspection even though the painted colour was correct, because the value no longer
   *       traced to a token in the artifact that shipped.
   * WHY : Trade-offs: the two also differ at run time, which is the reason `ui/src/layout/AppShell.tsx`
   *       states for the same choice. A resolved value is frozen at the render that read it, so a theme
   *       change repaints only what antd's own stylesheet owns and leaves these inline colours behind;
   *       a `var()` reference keeps following the theme with no re-render. Nothing is given up in
   *       exchange -- the shape, the token names and the type are identical.
   */
  const { cssVar } = theme.useToken();
  const idPrefix = `${useId()}-`;
  const navigate = useNavigate();
  const paintedAt = useServerInstant();

  const [reportType, setReportType] = useState<ReportType | null>(null);
  const [range, setRange] = useState<DateRangeValues>(EMPTY_RANGE);
  const [confirmAnswer, setConfirmAnswer] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly ReportsFieldError[]>([]);
  const [submission, setSubmission] = useState<ReportSubmission | null>(null);
  const [busy, setBusy] = useState(false);

  /*
   * WHY : Assumptions: the in-flight state is held in a ref AS WELL AS in state, and the two answer
   *       different questions. The state value drives the rendered busy affordance; the ref is what a
   *       handler reads to decide whether a turn is already running, because React state set earlier in the
   *       same task is not readable within it. A 3270 keyboard locks until the region replies, so the
   *       reference needs no guard at all; without one here a doubled Enter would start two report runs
   *       where the operator asked for one.
   */
  const inFlight = useRef(false);

  const reportTypeRef = useRef<HTMLDivElement | null>(null);
  const confirmRef = useRef<InputRef | null>(null);
  const partRefs = useRef<Partial<Record<ReportsField, InputRef | null>>>({});

  /*
   * WHY : ⚠️ Refactoring Rationale: the cursor move is RECORDED here and applied by the effect below,
   *       rather than performed immediately. This was measured, not theorised: every control on the screen
   *       carries `disabled={busy}` while a submission is in flight, and a disabled element cannot hold or
   *       receive focus, so the browser had already moved focus to the document body when the submission
   *       started. Both asynchronous outcomes then called this function inside the promise handler, in the
   *       same batch as `setBusy(false)` -- so at the instant the immediate `focus()` ran the control was
   *       still disabled in the committed DOM and the call was a silent no-op. The cursor ended on `body`
   *       where `app/cbl/CORPT00C.cbl` puts it on the monthly field, on the acknowledgement path at L452
   *       and L635 and on the failure path at L533.
   * WHY : Alternatives Considered: dropping `disabled={busy}` so nothing is ever unfocusable, which was
   *       rejected because the disabled window is what makes a double submission impossible; and a
   *       `setTimeout` or `requestAnimationFrame` after the handler, rejected because both make the cursor
   *       position depend on a timer rather than on the render that caused it, which reintroduces the same
   *       defect non-deterministically on a slow commit. Recording the request and applying it after the
   *       commit is also the idiom the delivered sibling screens use -- see
   *       `ui/src/screens/transactionAdd/index.tsx` and `ui/src/screens/userUpdate/index.tsx`.
   */
  const [pendingFocus, setPendingFocus] = useState<ReportsField | null>(null);

  /**
   * Records the cursor move that mirrors the reference's `MOVE -1 TO <field>L`.
   *
   * Assumptions: the destination is decided before the screen carrying it is painted, which is what the
   * reference does too -- it moves `-1` into the field's length member and only then performs
   * `SEND-TRNRPT-SCREEN`. The control may not even be mounted yet when the decision is taken, because the
   * date block exists only while the custom type is selected.
   * @param {ReportsField} field - Control the cursor must land on.
   * @returns {void} Completion is the recorded request; the effect below applies it after the commit.
   */
  function focusField(field: ReportsField): void {
    setPendingFocus(field);
  }

  useEffect(
    /**
     * Applies a recorded cursor move once the render that caused it has been committed.
     *
     * Assumptions: focusing the report-type group reaches the FIRST radio rather than the group wrapper,
     * because a wrapper is not a focusable control. The reference's `MOVE -1 TO MONTHLYL` names the monthly
     * field specifically, and monthly is the first option, so querying the group for its first input lands
     * on exactly the field the reference names -- and keeps doing so without a second ref per option.
     * @returns {void} Completion is the focused control. A control that is not currently rendered is a
     *   no-op, and the request is cleared either way so it can never be replayed on a later render.
     */
    function applyPendingFocus(): void {
      if (pendingFocus === null) {
        return;
      }
      if (pendingFocus === 'reportType') {
        reportTypeRef.current?.querySelector('input')?.focus();
      } else if (pendingFocus === 'confirm') {
        confirmRef.current?.focus();
      } else {
        partRefs.current[pendingFocus]?.focus();
      }
      setPendingFocus(null);
    },
    [pendingFocus],
  );

  /**
   * Clears every input and the message, the port of `INITIALIZE-ALL-FIELDS`.
   *
   * Purpose: reproduces `app/cbl/CORPT00C.cbl` L633-L646, which moves `-1` into `MONTHLYL` and then
   * `INITIALIZE`s all ten input fields together with `WS-MESSAGE`.
   *
   * ⚠️ Assumptions: `WS-MESSAGE` is in that `INITIALIZE` list, so the paragraph clears the SENTENCE as well
   * as the fields, and the cursor lands on the report-type selector. Every caller depends on that: the
   * refusal path relies on it to leave the band empty, and the success path relies on it to leave the band
   * empty before composing its own sentence into it.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function initialiseAllFields(): void {
    setReportType(null);
    setRange(EMPTY_RANGE);
    setConfirmAnswer('');
    setMessage(null);
    setSeverity('error');
    setFieldErrors([]);
    setSubmission(null);
    focusField('reportType');
  }

  /**
   * Paints one refusal: the sentence in red, the marks on the named controls, the cursor on one of them.
   * @param {string} refusal - Verbatim sentence for the message band.
   * @param {readonly ReportsFieldError[]} marks - Controls to mark beneath, which may be empty.
   * @param {ReportsField} focus - Control to place the cursor on.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function reportRefusal(
    refusal: string,
    marks: readonly ReportsFieldError[],
    focus: ReportsField,
  ): void {
    setMessage(refusal);
    /*
     * WHY : Assumptions: the severity is `error` for every refusal because the mapset declares the row-23
     *       field `COLOR=RED` (`app/bms/CORPT00.bms` L218-L219). Exactly one path overrides it -- the
     *       acknowledgement, which moves `DFHGREEN` into the field's colour attribute at
     *       `app/cbl/CORPT00C.cbl` L448 -- so one field carries two severities and this is the default one.
     */
    setSeverity('error');
    setFieldErrors(marks);
    setSubmission(null);
    focusField(focus);
  }

  /**
   * Resolves the range a chosen report type covers, running the edit chain only for the custom type.
   *
   * Assumptions: the two preset types resolve their own bounds and are never edit-checked, because the
   * reference computes them from the clock rather than reading them from the screen -- `app/cbl/CORPT00C.cbl`
   * L214-L238 and L240-L255 -- and its whole edit chain sits inside the custom arm at L256-L436.
   *
   * ⚠️ Assumptions: both preset ranges are resolved HERE and transmitted, although `ReportRequest` declares
   * each bound optional and the service can resolve them itself. The reference resolves them on the
   * presentation side and puts the results in the submitted job control, so transmitting them is the port.
   * It also removes a real hazard the service-side default carries: a monthly report submitted seconds
   * either side of midnight on the last day of a month would otherwise resolve to two different ranges, and
   * `ui/src/api/reporting.ts` records that a bound is supplied by the caller precisely so that two runs of
   * one report over one range produce the same document.
   * @param {ReportType} chosen - Report type the operator selected.
   * @returns {ReportDateRange | null} The bounds to submit, or `null` when a refusal was painted instead.
   */
  function resolveRange(chosen: ReportType): ReportDateRange | null {
    if (chosen === 'monthly') {
      return computeMonthlyRange(dayjs(paintedAt));
    }
    if (chosen === 'yearly') {
      return computeYearlyRange(dayjs(paintedAt));
    }

    const validation = validateCustomRange(range);
    if (validation.range === null) {
      reportRefusal(
        validation.message ?? REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT,
        validation.fieldErrors,
        validation.focus ?? 'start-month',
      );
      return null;
    }
    return validation.range;
  }

  /**
   * Starts one report run and renders whichever of the three outcomes the service reports.
   * @param {ReportType} chosen - Report type being submitted, which names the acknowledgement's report.
   * @param {ReportDateRange} bounds - Bounds the run is submitted over.
   * @param {string} answer - The confirmation character to relay, which is always a consenting one.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function startReportRun(chosen: ReportType, bounds: ReportDateRange, answer: string): void {
    /*
     * WHY : Assumptions: exactly one of the three marks is set. `ReportRequest` keeps all three as separate
     *       members because the reference resolves a double mark by precedence rather than refusing it --
     *       its `EVALUATE TRUE` takes the first non-blank arm -- and a single `Radio.Group` makes a double
     *       mark unreachable from here, so this client never exercises that precedence rule.
     * WHY : Assumptions: the transaction identifier and program name are sent because `ReportRequest`
     *       mirrors the symbolic map field for field, and those two are the map's own `TRNNAME` and
     *       `PGMNAME`. The remaining header members are omitted rather than filled with a clock reading:
     *       `CURDATE` and `CURTIME` are painted by the shell from a server-anchored instant, so a second
     *       copy taken here would be a browser clock value competing with it.
     */
    const request: ReportRequest = {
      transactionName: REPORTS_TRANSACTION_ID,
      programName: REPORTS_PROGRAM_NAME,
      ...reportTypeMarks(chosen),
      startDate: bounds.startDate,
      endDate: bounds.endDate,
      confirm: answer,
    };

    inFlight.current = true;
    setBusy(true);
    setMessage(null);
    setFieldErrors([]);

    submitTransactionReport(request).then(
      /**
       * Renders the outcome the service reported for this submission.
       * @param {ReportSubmissionOutcome} outcome - Which of the three arms the service answered with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (outcome: ReportSubmissionOutcome): void => {
        inFlight.current = false;
        setBusy(false);

        if (outcome.outcome === 'STARTED') {
          /*
           * WHY : Assumptions: the fields are cleared BEFORE the acknowledgement is composed, and the order
           *       is the reference's -- L447 performs `INITIALIZE-ALL-FIELDS` and only then L449-L452 builds
           *       the sentence -- so the operator is left on an empty form carrying the identity of the run
           *       they just started.
           * WHY : Assumptions: the severity flips to `success`. L448 moves `DFHGREEN` into `ERRMSGC`, which
           *       is the only place this program overrides the field's declared `COLOR=RED`, so this one
           *       sentence is green and every other sentence this screen shows is red. That is why the band
           *       takes a severity discriminator rather than deriving a colour from the mapset alone.
           * WHY : Assumptions: the sentence is composed by the catalog template rather than concatenated
           *       here. Its second literal is `' report submitted for printing ...'`, which carries a
           *       LEADING space and a space before the ellipsis; both are in the baseline `STRING` at
           *       L449-L452, and building the sentence at this call site is the one place either could be
           *       silently normalised away.
           */
          const started = outcome.submission;
          initialiseAllFields();
          setMessage(
            formatMessageTemplate(MESSAGE_TEMPLATES.REPORT_SUBMITTED_FOR_PRINTING, {
              'WS-REPORT-NAME': REPORT_TYPE_NAMES[chosen],
            }),
          );
          setSeverity('success');
          setSubmission(started);
          return;
        }

        /*
         * WHY : Assumptions: the other two arms are handled although this screen only ever sends a
         *       consenting answer, so neither should arrive. They are handled rather than ignored because
         *       the confirmation is evaluated on BOTH sides -- here, and again by the service, which
         *       answers a blank answer with the reference's own prompt and a refusal with no sentence at
         *       all. Mapping them onto the same observable states this screen already produces means a
         *       service that disagrees with this client degrades to the reference's behaviour instead of
         *       leaving the operator on a form that silently did nothing.
         */
        if (outcome.outcome === 'UNANSWERED') {
          reportRefusal(
            outcome.message,
            [{ field: 'confirm', message: outcome.message }],
            'confirm',
          );
          return;
        }
        initialiseAllFields();
      },
      /**
       * Renders a refused submission on the reference's own failure path.
       * @param {unknown} failure - The value the submission rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        inFlight.current = false;
        setBusy(false);
        const refusal = mapSubmissionFailure(failure);
        reportRefusal(refusal.message, refusal.fieldErrors, refusal.focus);
      },
    );
  }

  /**
   * Runs one turn: the report-type check, the range resolution, the confirmation, then the submission.
   *
   * Purpose: the port of `PROCESS-ENTER-KEY` at `app/cbl/CORPT00C.cbl` L208-L456 together with the
   * confirmation branch of `SUBMIT-JOB-TO-INTRDR` it funnels into.
   *
   * ⚠️ Assumptions: the confirmation is required for ALL THREE report types, not just the custom one. Each
   * of the three arms of the outer `EVALUATE` ends in `PERFORM SUBMIT-JOB-TO-INTRDR` -- L238 for monthly,
   * L255 for yearly and L435 for custom -- and that paragraph opens with the blank-confirmation test. A
   * reading that submitted the two preset types immediately would drop a whole turn the operator performs.
   *
   * Assumptions: the confirmation is evaluated on this side rather than by relaying the raw character and
   * reading the service's answer. Three of the four outcomes start nothing, and one of them -- an
   * unrecognised character -- is answered by the published contract with HTTP 400, which the shared client
   * raises as a rejection. Surfacing a field-level prompt through an exception path is worse than deciding
   * it here, and deciding it here is also what the reference does: `SUBMIT-JOB-TO-INTRDR` settles all four
   * outcomes before it writes the first record to the queue.
   * @param {string} answer - The confirmation character this turn is answering with.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function runTurn(answer: string): void {
    if (inFlight.current) {
      return;
    }

    /*
     * WHY : Assumptions: an unselected type is refused with the reference's own sentence and the cursor
     *       returns to the monthly selector -- L437-L442, the `WHEN OTHER` arm of the outer `EVALUATE`,
     *       which is reached when none of the three marks is set.
     */
    if (reportType === null) {
      reportRefusal(REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT, [], 'reportType');
      return;
    }

    const bounds = resolveRange(reportType);
    if (bounds === null) {
      return;
    }

    const confirmation = evaluateConfirmation(answer, REPORT_TYPE_NAMES[reportType]);
    if (confirmation.outcome === 'UNANSWERED' || confirmation.outcome === 'UNRECOGNISED') {
      reportRefusal(
        confirmation.message,
        [{ field: 'confirm', message: confirmation.message }],
        'confirm',
      );
      return;
    }
    if (confirmation.outcome === 'DECLINED') {
      /*
       * WHY : Refactoring Rationale: a refusal clears the WHOLE form and shows NO sentence, which is the
       *       one outcome a naive port gets wrong in two directions at once. L480-L483 performs
       *       `INITIALIZE-ALL-FIELDS` -- which clears the ten inputs AND `WS-MESSAGE` -- and then sets the
       *       error flag purely to suppress the acknowledgement block at L445. So the screen comes back
       *       blank and silent: leaving the form populated would be wrong, and leaving the previous
       *       sentence on the band would be equally wrong.
       */
      initialiseAllFields();
      return;
    }

    startReportRun(reportType, bounds, answer);
  }

  /**
   * Submits the turn using the confirmation character currently in the field.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function submitTurn(): void {
    runTurn(confirmAnswer);
  }

  /**
   * Submits the turn as though the operator had keyed a consenting answer.
   *
   * Assumptions: the confirmation dialogue's accept action writes the consenting character into the field
   * before submitting, so both confirmation surfaces submit the SAME turn and the field always shows what
   * was submitted. The character path is retained alongside the dialogue because the dialogue can only
   * express consent and refusal, and two of the reference's four outcomes -- a blank answer and an
   * unrecognised one -- are reachable only by keying the field.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function confirmAndSubmit(): void {
    setConfirmAnswer(CONSENTING_ANSWER);
    runTurn(CONSENTING_ANSWER);
  }

  /**
   * Returns to the main menu, the target form of the reference's `DFHPF3` transfer.
   *
   * Assumptions: `app/cbl/CORPT00C.cbl` L187-L189 moves `'COMEN01C'` into `CDEMO-TO-PROGRAM` and performs
   * `RETURN-TO-PREV-SCREEN`, which issues `EXEC CICS XCTL`. Transformation rule T5 maps `XCTL` to a client
   * route change, and no communication area travels with it because the target carries no session struct.
   * @returns {void} Completion is represented by the route change.
   */
  function returnToMenu(): void {
    navigateSafely(navigate, MAIN_MENU_ROUTE);
  }

  /**
   * Builds the change handler for one date part, enforcing the mapset's `NUM` attribute.
   *
   * ⚠️ Assumptions: the filter keeps the digits, the minus sign and the period, and drops everything else.
   * That is the character set a 3270 numeric-only field accepts, and matching it exactly is what keeps the
   * reference's own `IS NOT NUMERIC` arms reachable: a field that admitted digits ALONE could never hold a
   * non-numeric value, which would make both `Not a valid Year` sentences -- whose only predicate is that
   * test -- unreachable from the user interface, and would quietly delete the very rule the reference wrote.
   * Alphabetic input is still rejected outright, which is the behaviour the `NUM` attribute is there for.
   *
   * Alternatives Considered: `InputNumber`, which is antd's numeric control and was rejected on two counts.
   * It renders spinner affordances for a two-character calendar part that has no meaningful increment, and
   * it coerces its value to a number, which would erase the leading zero that {@link normaliseDatePart}
   * exists to produce and would make a partially keyed part indistinguishable from an empty one.
   * @param {DateBound} bound - Which bound the part belongs to.
   * @param {DatePart} part - Which of the three parts is changing.
   * @returns {(event: ChangeEvent<HTMLInputElement>) => void} Handler recording the filtered value.
   */
  function datePartHandler(
    bound: DateBound,
    part: DatePart,
  ): (event: ChangeEvent<HTMLInputElement>) => void {
    /**
     * Records one keyed date part after removing characters the 3270 field would have refused.
     * @param {ChangeEvent<HTMLInputElement>} event - Change event carrying the control's new value.
     * @returns {void} Completion is represented by the screen's own state.
     */
    return function recordDatePart(event: ChangeEvent<HTMLInputElement>): void {
      const accepted = event.target.value.replace(NON_NUMERIC_FIELD_CHARACTERS, '');
      setRange(
        /**
         * Replaces the one part that changed, leaving the other five untouched.
         * @param {DateRangeValues} current - The six parts as they stand.
         * @returns {DateRangeValues} The same six with this part replaced.
         */
        (current: DateRangeValues): DateRangeValues => ({
          ...current,
          [bound]: { ...current[bound], [part]: accepted },
        }),
      );
    };
  }

  /**
   * Builds the ref callback that registers one date part's control for cursor placement.
   * @param {ReportsField} field - Control identity to register under.
   * @returns {(control: InputRef | null) => void} Ref callback recording the control.
   */
  function registerPart(field: ReportsField): (control: InputRef | null) => void {
    /**
     * Records the mounted control, or forgets it on unmount.
     * @param {InputRef | null} control - The control, or `null` as it unmounts.
     * @returns {void} Completion is represented by the recorded reference.
     */
    return function recordPart(control: InputRef | null): void {
      partRefs.current[field] = control;
    };
  }

  /**
   * Adopts a date picked from the calendar into the three parts of one bound.
   *
   * ⚠️ Trade-offs: the calendar WRITES the split parts rather than replacing them as the value the screen
   * holds, and that is what lets one screen satisfy two requirements that pull against each other. A single
   * date control is far easier to use than three boxes, but it cannot express a part-level failure -- there
   * is no way to pick a date that has a day and no month -- so a screen built on the control alone could
   * never produce eleven of the reference's fourteen date sentences. Keeping the parts as the value and the
   * calendar as a way of filling them preserves every one of the fourteen while still offering the control.
   * @param {DateBound} bound - Which bound the picked date fills.
   * @returns {(picked: Dayjs | null) => void} Handler writing the three parts, or clearing them.
   */
  function datePickerHandler(bound: DateBound): (picked: Dayjs | null) => void {
    /**
     * Writes a picked date into the bound's three parts, or clears them when the picker is emptied.
     * @param {Dayjs | null} picked - The chosen date, or `null` when the control was cleared.
     * @returns {void} Completion is represented by the screen's own state.
     */
    return function adoptPickedDate(picked: Dayjs | null): void {
      const parts: DatePartValues =
        picked === null
          ? { month: '', day: '', year: '' }
          : {
              month: picked.format('MM'),
              day: picked.format('DD'),
              year: picked.format('YYYY'),
            };
      setRange(
        /**
         * Replaces one bound's three parts with the picked date's.
         * @param {DateRangeValues} current - The six parts as they stand.
         * @returns {DateRangeValues} The same six with this bound replaced.
         */
        (current: DateRangeValues): DateRangeValues => ({ ...current, [bound]: parts }),
      );
    };
  }

  /**
   * Records the chosen report type, discarding any refusal the previous choice produced.
   * @param {ReportType} chosen - Report type the operator selected.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function selectReportType(chosen: ReportType): void {
    setReportType(chosen);
    setFieldErrors([]);
    setMessage(null);
  }

  /**
   * Records the confirmation character, keeping only the first one the reference's field could hold.
   * @param {ChangeEvent<HTMLInputElement>} event - Change event carrying the control's new value.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function recordConfirmAnswer(event: ChangeEvent<HTMLInputElement>): void {
    setConfirmAnswer(event.target.value.slice(0, CONFIRM_WIDTH));
  }

  /*
   * WHY : Assumptions: exactly TWO handlers are registered, because the reference dispatches exactly two.
   *       `EVALUATE EIBAID` at `app/cbl/CORPT00C.cbl` L184-L195 has an arm for `DFHENTER` and an arm for
   *       `DFHPF3`, and its `WHEN OTHER` reports the invalid-key sentence. Registering a third here would
   *       advertise a key the row-24 legend does not paint and the program does not honour.
   * WHY : Assumptions: the invalid-key sentence is NOT emitted by this screen. `usePfKeys` owns it -- it
   *       holds the catalog's `INVALID_KEY_PRESSED` text and reports any recognised attention identifier
   *       that this map leaves unregistered -- and it also owns the PF13-to-PF01 aliasing that
   *       `app/cpy/CSSTRPFY.cpy` L54-L77 defines. Both are consumed rather than reimplemented, which is why
   *       this program's own `COPY` list omits that copybook's target and this module omits both mechanisms.
   */
  const pfKeyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: submitTurn, label: REPORTS_KEY_LABELS.ENTER },
    PFK03: { onInvoke: returnToMenu, label: REPORTS_KEY_LABELS.PFK03 },
  };

  /**
   * Paints the invalid-key sentence `usePfKeys` reports, on the reference's own terms.
   *
   * Assumptions: the text arrives from the hook rather than being composed here, because the hook holds the
   * catalog constant and this screen must not become a second source of it. The cursor returns to the
   * report-type selector because `app/cbl/CORPT00C.cbl` L192 moves `-1` into `MONTHLYL` on that arm.
   * @param {PfKeyRejection} rejection - The recognised-but-unavailable key the hook reported.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function reportInvalidKey(rejection: PfKeyRejection): void {
    reportRefusal(rejection.message, [], 'reportType');
  }

  const { bindings, invoke } = usePfKeys(pfKeyHandlers, {
    enabled: !busy,
    onInvalidKey: reportInvalidKey,
    restoreFocusRef: reportTypeRef,
  });

  useShellSlot({
    screen: { transactionId: REPORTS_TRANSACTION_ID, programName: REPORTS_PROGRAM_NAME },
    ...(paintedAt === undefined ? {} : { now: paintedAt }),
    message: { text: message, severity, mapset: REPORTS_MAPSET },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  /*
   * WHY : Refactoring Rationale: every colour below resolves through `BMS_TEXT_COLOR_TOKENS` rather than
   *       through the hue map `BMS_COLOR_TOKENS`. The hue map answers "which token is this BMS colour",
   *       which is the right question for a fill or a border and the wrong one for text: several of its
   *       answers are brand hues that fail the contrast threshold as body text on the container background.
   *       The text map answers "which token carries this BMS colour as READABLE text", so `TURQUOISE`
   *       becomes the label token rather than the raw informational hue.
   */
  const captionStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const hintStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE] };
  const neutralStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };
  const titleStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const fixedPitchStyle: CSSProperties = { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] };

  /**
   * Finds the refusal naming one control, if the last turn produced one for it.
   * @param {ReportsField} field - Control to look up.
   * @returns {ReportsFieldError | undefined} The refusal, or `undefined` when the control is unmarked.
   */
  function refusalFor(field: ReportsField): ReportsFieldError | undefined {
    return fieldErrors.find(
      /**
       * Selects the refusal naming this control.
       * @param {ReportsFieldError} entry - One refusal from the last turn.
       * @returns {boolean} `true` when the refusal names this control.
       */
      (entry: ReportsFieldError): boolean => entry.field === field,
    );
  }

  /**
   * Renders one date part as a digit-restricted control at its declared width.
   *
   * Assumptions: each part is labelled for assistive technology even though the mapset paints no per-part
   * caption -- it paints one caption per bound and separates the parts with slashes. A sighted operator
   * reads the position; an operator using a screen reader would otherwise hear three unnamed boxes, so the
   * bound's caption and the part's name are combined into an accessible name that carries the same
   * information the layout carries visually.
   * @param {DateBound} bound - Which bound the part belongs to.
   * @param {DatePart} part - Which of the three parts to render.
   * @returns {ReactElement} The labelled control, marked when the last turn refused it.
   */
  function renderDatePart(bound: DateBound, part: DatePart): ReactElement {
    const field: ReportsField = `${bound}-${part}`;
    const refusal = refusalFor(field);
    const controlId = `${idPrefix}${field}`;
    const accessibleName = `${REPORTS_CAPTIONS[bound === 'start' ? 'startDate' : 'endDate'].trim()} ${part}`;

    return (
      <Form.Item
        htmlFor={controlId}
        {...(refusal === undefined
          ? {}
          : { validateStatus: 'error' as const, help: fieldErrorHelp(controlId, refusal.message) })}
      >
        <Input
          id={controlId}
          ref={registerPart(field)}
          aria-label={accessibleName}
          value={range[bound][part]}
          maxLength={DATE_PART_WIDTHS[part]}
          onChange={datePartHandler(bound, part)}
          disabled={busy}
          inputMode="numeric"
          style={fixedPitchStyle}
          {...fieldAriaProps(controlId, {
            invalid: refusal !== undefined,
            hasError: refusal !== undefined,
            hasHint: true,
            hintId: fieldHintId(`${idPrefix}${bound}`),
          })}
        />
      </Form.Item>
    );
  }

  /**
   * Renders one date bound: its caption, its three parts, the format hint and the calendar control.
   *
   * Assumptions: the three parts appear in month, day, year order separated by the mapset's own slash, which
   * is the order and the punctuation `app/bms/CORPT00.bms` paints on rows 13 and 14 -- the two `'/'` fields
   * per row at L133, L144 and L172, L183 -- and it is why the format hint reads `(MM/DD/YYYY)` while the
   * value transmitted is year-first. The screen shows the operator's order and transmits the contract's.
   * @param {DateBound} bound - Which bound to render.
   * @returns {ReactElement} The caption, the three parts, the hint and the calendar control on one row.
   */
  function renderDateBound(bound: DateBound): ReactElement {
    const caption = REPORTS_CAPTIONS[bound === 'start' ? 'startDate' : 'endDate'];
    const parts = range[bound];
    /*
     * WHY : Assumptions: the calendar shows a value only when all three parts together name a real date.
     *       A partially keyed bound has no date to show, and handing the control a half-formed value would
     *       either display a date the operator did not enter or force the control into an invalid state it
     *       has no way to render.
     */
    const composed = normaliseBound(parts);
    const picked = isExistingCalendarDate(composed) ? dayjs(composeIsoDate(composed)) : null;

    return (
      <Flex key={bound} align="flex-start" gap="small" wrap>
        <Typography.Text style={captionStyle}>{caption}</Typography.Text>
        {renderDatePart(bound, 'month')}
        <Typography.Text aria-hidden="true" style={hintStyle}>
          {DATE_PART_SEPARATOR}
        </Typography.Text>
        {renderDatePart(bound, 'day')}
        <Typography.Text aria-hidden="true" style={hintStyle}>
          {DATE_PART_SEPARATOR}
        </Typography.Text>
        {renderDatePart(bound, 'year')}
        <Typography.Text id={fieldHintId(`${idPrefix}${bound}`)} style={hintStyle}>
          {REPORTS_CAPTIONS.dateFormatHint}
        </Typography.Text>
        {/*
         * WHY : Assumptions: the calendar control is given an `id` even though nothing labels it by
         *       `htmlFor` -- its accessible name comes from `aria-label` above. Measured reason: antd's
         *       picker renders an input carrying neither `id` nor `name`, and those two were the only
         *       controls on the rendered page without either, which Chrome reports as an autofill
         *       advisory against this screen. The six date parts and the confirmation already carry
         *       `useId`-derived ids, so naming these two the same way removes the advisory and leaves
         *       the whole form addressable by one convention.
         */}
        <DatePicker
          id={`${idPrefix}${bound}-calendar`}
          aria-label={`${caption.trim()} ${REPORTS_CAPTIONS.dateFormatHint}`}
          value={picked}
          format={DATE_PICKER_FORMAT}
          onChange={datePickerHandler(bound)}
          disabled={busy}
          allowClear
        />
      </Flex>
    );
  }

  /**
   * Renders one report-type option with its verbatim caption.
   *
   * Assumptions: only the first option carries `autoFocus`, because `MONTHLY` is the one field on this
   * mapset declared with the `IC` attribute -- `ATTRB=(FSET,IC,NORM,UNPROT)` at `app/bms/CORPT00.bms` L80 --
   * and `IC` places the initial cursor. There is exactly one such field on the map and therefore exactly one
   * `autoFocus` on this screen.
   * @param {ReportType} option - Report type to render.
   * @returns {ReactElement} The radio control carrying that type's caption.
   */
  function renderReportTypeOption(option: ReportType): ReactElement {
    return (
      <Radio key={option} value={option} autoFocus={option === REPORT_TYPES[0]}>
        <Typography.Text style={captionStyle}>{REPORT_TYPE_PROMPTS[option]}</Typography.Text>
      </Radio>
    );
  }

  /**
   * Records the report type the operator chose from the group.
   *
   * Assumptions: the event's `target.value` is declared OPTIONAL by antd and is therefore narrowed rather
   * than read directly. The group is uncontrolled only in the sense that antd owns the event shape; a value
   * outside the three types cannot arise from the three options rendered above, so the narrowing is a
   * contract check at the boundary rather than a branch the operator can reach.
   * @param {RadioChangeEvent} event - Change event antd raises when an option is selected.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function handleReportTypeChange(event: RadioChangeEvent): void {
    const chosen: unknown = event.target.value;
    if (isReportType(chosen)) {
      selectReportType(chosen);
    }
  }

  /*
   * WHY : Trade-offs: the mapset's absolute geometry is NOT reproduced, and this is AAP gap G1 taken
   *       deliberately. `DFHMDI ... SIZE=(24,80)` fixes a 24-row by 80-column character grid and all 42
   *       field definitions carry an absolute `POS=(row,column)`, so a faithful rendering would need
   *       character cells at fixed coordinates. What is preserved is what survives translation: the
   *       GROUPING, the READING ORDER and the TAB ORDER -- report type, then the start bound, then the end
   *       bound, then the confirmation, then the actions, which is rows 7 through 19 in ascending order and
   *       the order an operator tabbed through them. What is given up is pixel-for-character positioning,
   *       which no browser can hold across viewport widths and which would be hostile to an operator using
   *       magnification or a screen reader.
   * WHY : Alternatives Considered: `Row` and `Col` with a `gutter`, antd's idiomatic form grid. Rejected
   *       because `gutter` takes a PIXEL NUMBER and AAP section 0.3.2 admits only values that resolve to a
   *       design token, so the idiomatic choice would put a hardcoded spacing literal on every row. `Flex`
   *       takes antd's semantic sizes, which resolve through the theme's spacing scale.
   */
  return (
    <Flex vertical gap="large">
      <ScreenTitle style={titleStyle}>{REPORTS_TITLE}</ScreenTitle>
      {/*
       * Assumptions: the busy affordance wraps the form rather than replacing it, so the fields an operator
       * just filled stay visible while the run is being started. The reference has no such state at all --
       * a 3270 keyboard simply locks -- so a spinner over the unchanged form is the closest available
       * analogue, and it is paired with a disabled submit so a second turn cannot begin.
       */}
      <Spin spinning={busy}>
        <Form layout="vertical">
          {/*
           * Assumptions: the three selectors are ONE `Radio.Group` and not three independent controls.
           * Refactoring Rationale: the mapset declares three separate one-character fields, and the program
           * resolves them with a first-match `EVALUATE TRUE` (`app/cbl/CORPT00C.cbl` L212-L256) that
           * silently prefers monthly when two are marked. Three independent inputs would let an operator
           * reach that ambiguous state and then be surprised by which report ran; a radio group makes the
           * mutual exclusion the reference merely resolves into something the operator cannot express.
           */}
          <Form.Item
            {...(refusalFor('reportType') === undefined
              ? {}
              : {
                  validateStatus: 'error' as const,
                  help: fieldErrorHelp(
                    `${idPrefix}reportType`,
                    refusalFor('reportType')?.message ?? '',
                  ),
                })}
          >
            <div ref={reportTypeRef}>
              <Radio.Group
                value={reportType}
                onChange={handleReportTypeChange}
                disabled={busy}
                aria-label={REPORTS_TITLE}
              >
                <Flex vertical gap="middle">
                  {REPORT_TYPES.map(renderReportTypeOption)}
                </Flex>
              </Radio.Group>
            </div>
          </Form.Item>

          {/*
           * Assumptions: the two bounds are rendered only for the custom type, because the reference reads
           * them only in the custom arm and derives them itself for the other two. The block is mounted and
           * unmounted rather than disabled, so the tab order of the confirmation and the actions below is
           * unaffected by its presence -- an operator choosing monthly tabs from the selector straight to
           * the confirmation, exactly as they would on a terminal where the date fields sat unused.
           */}
          {reportType === 'custom' ? (
            <Flex vertical gap="small">
              {DATE_BOUNDS.map(renderDateBound)}
            </Flex>
          ) : null}

          {/*
           * Assumptions: the caption keeps its trailing space and the domain hint stays a separate element,
           * because the mapset paints them as two fields -- a `LENGTH=59` sentence and a `LENGTH=5` hint --
           * with the one-character input between them on row 19.
           */}
          <Flex align="flex-start" gap="small" wrap>
            <Typography.Text style={captionStyle}>{REPORTS_CAPTIONS.confirmation}</Typography.Text>
            <Form.Item
              htmlFor={`${idPrefix}confirm`}
              {...(refusalFor('confirm') === undefined
                ? {}
                : {
                    validateStatus: 'error' as const,
                    help: fieldErrorHelp(
                      `${idPrefix}confirm`,
                      refusalFor('confirm')?.message ?? '',
                    ),
                  })}
            >
              <Input
                id={`${idPrefix}confirm`}
                ref={confirmRef}
                aria-label={REPORTS_CAPTIONS.confirmation.trim()}
                value={confirmAnswer}
                maxLength={CONFIRM_WIDTH}
                onChange={recordConfirmAnswer}
                disabled={busy}
                style={fixedPitchStyle}
                {...fieldAriaProps(`${idPrefix}confirm`, {
                  invalid: refusalFor('confirm') !== undefined,
                  hasError: refusalFor('confirm') !== undefined,
                  hasHint: true,
                  hintId: fieldHintId(`${idPrefix}confirm`),
                })}
              />
            </Form.Item>
            <Typography.Text id={fieldHintId(`${idPrefix}confirm`)} style={neutralStyle}>
              {REPORTS_CAPTIONS.confirmDomainHint}
            </Typography.Text>
          </Flex>

          {/*
           * Assumptions: the two actions are the two keys the row-24 legend advertises and nothing more.
           * They duplicate what `usePfKeys` binds to the keyboard rather than replacing it: the original
           * was keyboard-only, so the keys remain the primary path and these controls make the same two
           * actions discoverable to an operator who never learned them.
           * Trade-offs: the submit action is wrapped in a confirmation dialogue, which AAP section 0.3.2
           * maps the 3270 re-key-to-confirm convention onto. The one-character field above is retained
           * beside it because a dialogue can express only consent and refusal, while the reference
           * distinguishes four answers -- and the two it cannot express, a blank answer and an unrecognised
           * character, are reachable only by keying the field and pressing Enter.
           */}
          <Flex gap="small" wrap>
            <Popconfirm
              title={REPORTS_CAPTIONS.confirmation.trim()}
              description={REPORTS_CAPTIONS.confirmDomainHint}
              onConfirm={confirmAndSubmit}
              disabled={busy}
            >
              <Button type="primary" disabled={busy}>
                {REPORTS_KEY_LABELS.ENTER}
              </Button>
            </Popconfirm>
            <Button onClick={returnToMenu} disabled={busy}>
              {REPORTS_KEY_LABELS.PFK03}
            </Button>
          </Flex>
        </Form>
      </Spin>

      {/*
       * Assumptions: the started run's identity is surfaced and its document is not. The submission resolves
       * to a handle -- `executionName`, the value `readReportExecution` is addressed by -- because the
       * artifact is assembled asynchronously and is 133 columns of fixed-width text with COBOL edit masks
       * that a golden-master comparison reads byte for byte. Rendering the handle lets an operator quote the
       * run; rendering the document here would make this a second renderer of a parity artifact.
       */}
      {submission === null ? null : (
        <Typography.Text style={fixedPitchStyle}>{submission.executionName}</Typography.Text>
      )}
    </Flex>
  );
}

/**
 * Narrows an unknown radio value to one of the three report types.
 *
 * Assumptions: the group's value arrives typed as `unknown` from antd's change event, and it is narrowed
 * rather than asserted so that a value outside the three -- which only a defect could produce -- is ignored
 * instead of being stored as a fourth report type the rest of this module has no branch for.
 * @param {unknown} value - Value carried by the radio group's change event.
 * @returns {boolean} `true` when the value is one of the three report types.
 */
function isReportType(value: unknown): value is ReportType {
  return REPORT_TYPES.some(
    /**
     * Compares one known report type with the incoming value.
     * @param {ReportType} known - One of the three report types.
     * @returns {boolean} `true` when the incoming value is exactly that type.
     */
    (known: ReportType): boolean => known === value,
  );
}

/*
 * WHY : Assumptions: the default export exists because `ui/src/router.tsx` reaches every screen through
 *       `React.lazy`, which accepts only a module whose `default` is the component. The named export is kept
 *       beside it so that the pure helpers above and the component itself can be imported directly by a
 *       test without going through the lazy boundary.
 */
export default ReportsScreen;
