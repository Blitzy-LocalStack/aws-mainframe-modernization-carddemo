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
 * The report is never rendered here, and the run is followed rather than assumed
 * -------------------------------------------------------------------------------
 * Assumptions: submission STARTS a run and resolves to a handle, never to the document. The reference
 * reaches the same asynchrony through a queue -- `SUBMIT-JOB-TO-INTRDR` at L462 writes 80-byte
 * job-control records with `EXEC CICS WRITEQ TD QUEUE ('JOBS')` at L517, and `app/csd/CARDDEMO.CSD`
 * L499-L505 defines that queue, described as 'SUBMIT JOBS FROM CICS', mapping it to the internal reader
 * through `DDNAME(INREADER)` with `RECORDSIZE(80) RECORDFORMAT(FIXED)`. The submitted job runs
 * `EXEC PROC=TRANREPT` (`app/jcl/TRANREPT.jcl`), so the operator was returned to the screen at once and
 * the document was produced elsewhere. The printed artifact is 133 columns wide and its amount bands
 * carry COBOL edit masks whose sign character differs between the detail line and the three total lines;
 * `ui/src/api/reporting.ts` declines to reproduce any of it, and so does this module -- the bytes are
 * handed to the browser undecoded and are never displayed on this screen.
 *
 * Refactoring Rationale: ⚠️ what the handle is FOR was previously left unspent. The screen rendered the
 * run's name and stopped there, so a report an operator had submitted could be neither followed nor
 * obtained from anywhere in the application, and the two operations that answer both questions --
 * `readReportExecution` and `collectReportArtifact` -- were published by the client and called from
 * nowhere. The queue the reference wrote to reported nothing back at all, so following a run is a
 * documented improvement rather than a port; the screen now reads the run's status until it settles,
 * offers a read on demand, explains each terminal outcome, and hands over the document of a run that
 * succeeded.
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

import { isApiRequestError, isRepeatableFailure } from '../../api/client';
import {
  collectReportArtifact,
  newSubmissionKey,
  readReportExecution,
  submitTransactionReport,
} from '../../api/reporting';
import type {
  ReportExecutionStatus,
  ReportRequest,
  ReportSubmission,
  ReportSubmissionOutcome,
} from '../../api/reporting';
import type { ApiError } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import {
  WRAPPED_BUSY_REGION_PROPS,
  busyAnnouncement,
  fieldAriaProps,
  fieldErrorHelp,
  fieldHintId,
} from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import {
  MESSAGE_TEMPLATES,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REPORTS_CAPTIONS,
  REPORTS_KEY_LABELS,
  REPORTS_TITLE,
  REPORT_RUN_MESSAGES,
  REPORT_TYPE_PROMPTS,
  REQUEST_IN_PROGRESS,
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

/*
 * WHY : Refactoring Rationale: the four groups of painted text this screen used to transcribe -- the
 *       row-4 heading, the three selector captions, the five captions and hints around the date bounds
 *       and the confirmation, and the two halves of the row-24 legend -- are imported above from
 *       `ui/src/messages/messages.ts`, which AAP section 0.2.1.5 makes the owner of every string a
 *       screen renders. Each entry's mapset line now sits beside the value there, indexed by
 *       `REPORTS_PAINTED_TEXT_SOURCES` against the file `REPORTS_MAPSET_SOURCE_FILE` names, so the
 *       citations are not duplicated here.
 * WHY : Assumptions: nothing about the values changed in the move, and the three fragile ones are why
 *       that is worth stating: `endDate` still carries its two LEADING spaces, `confirmation` still ends
 *       with a space, and the legend is still split from one 23-character literal. Transformation rule
 *       T8 compares these byte for byte, so a screen that re-typed any of them would put a second copy
 *       in the tree with nothing to compare it against.
 * WHY : Assumptions: brightness on the row-4 heading is still resolved HERE, as font weight through
 *       `TYPOGRAPHY_TOKENS.brightEmphasis` rather than as a colour, because the field declares
 *       `COLOR=NEUTRAL` of its own and expressing brightness as colour would collide with it. That is a
 *       rendering decision rather than text, which is why it stays in this module while the string does
 *       not.
 */

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
 * The two report types whose bounds come from a clock reading rather than from the map.
 *
 * Assumptions: the reference reads `FUNCTION CURRENT-DATE` in exactly these two arms --
 * `app/cbl/CORPT00C.cbl` L215 for monthly and L241 for yearly -- and reads the six keyed date parts in
 * the third. Naming the pair once is what lets the selector that offers them and the resolver that
 * computes them agree on which types depend on a clock at all; the alternative was the same two
 * literals written at both sites, where a change to one would silently offer a range the other refuses.
 *
 * Assumptions: the member type is widened to `ReportType` rather than left as the literal pair, so that
 * a membership test may be asked about any of the three types. A `readonly ['monthly', 'yearly']` tuple
 * types its own `includes` parameter as that pair, which would reject the very question being asked.
 */
const CLOCK_DERIVED_REPORT_TYPES: readonly ReportType[] = ['monthly', 'yearly'];

/**
 * The bare report names the program interpolates into its confirm and acknowledgement sentences.
 *
 * Assumptions: each is the literal moved into `WS-REPORT-NAME PIC X(10)` -- `'Monthly'` at
 * `app/cbl/CORPT00C.cbl` L214, `'Yearly'` at L240 and `'Custom'` at L433 -- and both sentences take it
 * `DELIMITED BY SPACE`, so the trailing blanks of the ten-character field never reach the operator. The
 * values are imported from the message catalog for the two the catalog records rather than retyped.
 *
 * ⚠️ Assumptions: these bare words are NOT the selector captions, and the two sets now sit in different
 * files, which makes the distinction easier to lose rather than harder. `REPORT_TYPE_PROMPTS` holds the
 * 23-character captions an operator reads beside each selector; both sets exist in the baseline and
 * neither is derivable from the other, so substituting one would either put `Monthly (Current Month)`
 * inside a message the reference spells `Monthly` or strip the caption an operator chooses by.
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
 * Declared width of a date bound's caption cell, in character columns.
 *
 * ⚠️ Assumptions: twelve, and BOTH captions are twelve. `app/bms/CORPT00.bms` L122-L126 declares the start
 * caption `LENGTH=12, POS=(13,15), INITIAL='Start Date :'` and L162-L166 declares the end caption
 * `LENGTH=12, POS=(14,15), INITIAL='  End Date :'` -- identical length, identical starting column, and the
 * end caption is right-aligned into that cell by TWO LEADING SPACES rather than by being shorter. So on the
 * terminal both rows' first input begins in column 29, which is what put the two rows' slashes, inputs and
 * hints on the same vertical lines.
 *
 * ⚠️ Refactoring Rationale: this constant exists because HTML does not reproduce that on its own. A text node
 * collapses a leading run of spaces, so `'  End Date :'` painted as ordinary text is ten characters wide
 * against the start caption's twelve; the row is a flex line sized by its caption, so every element after
 * the caption inherited the two-character difference. A review measured it as six to seven pixels at every
 * width the row fits -- the first inputs at x143 against x137, the slashes at x352/x575 against x346/x569,
 * the calendar controls at x905 against x898. Declaring the cell's measure and preserving its spaces is what
 * makes the two rows start at one x again.
 *
 * Trade-offs: this is a data measure and not a design value -- it is the mapset's own `LENGTH`, in the same
 * class as {@link DATE_PART_WIDTHS} -- so it is expressed in `ch`, the advance measure of the font's zero
 * glyph, which is the browser's nearest equivalent of a character column and the unit
 * `copybookFieldWidthStyle` already uses for the same purpose. A pixel measure would be a design value this
 * screen is not allowed to hold, and would have to be recomputed for every theme.
 */
export const BOUND_CAPTION_WIDTH = 12;

/**
 * The separator the mapset paints between the parts of a date, verbatim.
 *
 * Assumptions: four `LENGTH=1 COLOR=BLUE` fields carry it, two per bound -- `app/bms/CORPT00.bms` L133-L137
 * and L144-L148 on row 13, L172-L176 and L183-L187 on row 14. It is rendered twice per bound here rather
 * than four times as a set, because a bound is rendered once and the separator belongs to it.
 */
export const DATE_PART_SEPARATOR = '/';

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

/**
 * The mark every refusal of the report-type selector carries.
 *
 * Purpose
 * -------
 * `app/cbl/CORPT00C.cbl` L437-L442 answers an unselected type by painting the sentence on row 23 and
 * moving `-1` into `MONTHLYL` -- so the reference ASSOCIATES the refusal with the selector, and it does
 * so by putting the cursor there. A browser reproduces the cursor move directly but has a second,
 * non-visual channel the terminal did not: assistive software resolves a control's validity and its
 * description from the control itself, not from where the caret happens to rest. This mark is what
 * populates that channel.
 *
 * ⚠️ Refactoring Rationale: both selector refusals used to pass an EMPTY array, and that made the
 * control's whole accessibility wiring inert. `refusalFor('reportType')` returns `undefined` for an
 * empty array, and the render keys `validateStatus`, `help` and `fieldAriaProps` off exactly that
 * value -- so `aria-invalid` and `aria-describedby` were both omitted on the one turn they exist for,
 * and an operator on the group after a refused submit was told neither that it was invalid nor why.
 * Measured in a browser before the change: after submitting with nothing selected, the element
 * carrying `role="radiogroup"` held no `aria-invalid` and no `aria-describedby`, and the identifier the
 * helper composes for the description resolved to no element at all. The wiring was correct; nothing
 * ever switched it on.
 *
 * Assumptions: the sentence is the SAME one the message line carries, not a second wording. AAP rule
 * T8 carries user-visible strings verbatim and the catalog holds exactly one string for this
 * condition, so composing a per-field variant would put text on the screen that no line of the
 * reference holds.
 *
 * Assumptions: one shared constant rather than a literal at each site, because the two sites answer the
 * SAME condition -- no usable report type -- and a mark that named a different field at one of them
 * would move the description onto a control the cursor never reaches.
 */
const SELECTOR_REFUSAL_MARKS: readonly ReportsFieldError[] = [
  { field: 'reportType', message: REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT },
];

/** An inclusive processing-date range, both bounds already in the interchange form. */
export interface ReportDateRange {
  /** Lower bound, inclusive, as `YYYY-MM-DD`. */
  readonly startDate: string;
  /** Upper bound, inclusive, as `YYYY-MM-DD`. */
  readonly endDate: string;
}

/**
 * What a submission carries for its two bounds, which is BOTH of them or NEITHER.
 *
 * Assumptions: this is derived from `ReportRequest` with `Pick` rather than declared independently, so
 * the two member names and their optionality are the published contract's and cannot drift from it. It
 * is deliberately weaker than {@link ReportDateRange}: that type states a resolved range, both members
 * present, and is what the custom arm's edit chain returns, whereas this states what goes on the wire --
 * and a preset puts nothing there, because `ReportExecutionService` resolves a preset's period from its
 * own clock and never reads the request's bounds on that path.
 */
type SubmittedRange = Pick<ReportRequest, 'startDate' | 'endDate'>;

/**
 * The empty range a preset submits, frozen so no caller can add a bound to the shared value.
 *
 * Alternatives Considered: returning a fresh `{}` from each preset arm, which would need no constant.
 * Rejected because a named sentinel makes "this submission deliberately carries no bounds" readable at
 * the two call sites, where a bare literal reads as an oversight.
 */
const NO_SUBMITTED_RANGE: SubmittedRange = Object.freeze({});

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

/**
 * Lowest year the reference's calendar validator accepts, a year of zero being outside every era.
 *
 * ⚠️ Assumptions: one, and the figure comes from the reference's own feedback-code table rather than from a
 * general view about calendars. `app/cbl/CSUTLDTC.cbl` L70 declares
 * `88 FC-YEAR-IN-ERA-ZERO VALUE X'000309D959C3C5C5'`, whose condition identifier carries severity 3 and
 * message number `0x09D9` -- 2521 -- and L145-L146 renders it as `'YearInEra is 0 '`. `CORPT00C` forgives
 * exactly ONE non-zero outcome: L397 and L419 both read `IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'`, and 2513
 * is `FC-UNSUPP-RANGE` at L66. So 2521 is not forgiven, and a year of zero takes the refusal arm with
 * `'Start Date - Not a valid date...'` at L398 or `'End Date - Not a valid date...'` at L420.
 *
 * ⚠️ Assumptions: the year edits BEFORE that call cannot catch it, which is why the check has to live in the
 * calendar family. The blank test at L273 and L294 passes -- `'0000'` is not spaces -- and the only other
 * year edit is `IS NOT NUMERIC` at L347 and L373, which `'0000'` also passes. `isPartWithinRange` reproduces
 * that faithfully by applying no lower bound to a year, so `'0000'` reaches the calendar test exactly as it
 * reaches `CEEDAYS` there.
 *
 * Trade-offs: this refuses a year the four-character field can physically hold, which is the point -- the
 * field's width and the calendar's domain are different constraints, and the reference enforces both.
 */
const LOWEST_YEAR_IN_ERA = 1;

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
 * whole supported span and answers an out-of-span year with a feedback code the caller tolerates. The
 * same measurement governs the DISPLAY side of the screen for the same reason, at
 * {@link calendarValueFor}: what the library cannot represent is not shown rather than shown wrongly.
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
 *
 * Assumptions: this predicate is therefore NECESSARY but not sufficient for the calendar affordance, which
 * has to satisfy a second condition this function deliberately does not test -- that the year survives the
 * date library unchanged. {@link calendarValueFor} applies both, and the division is what keeps a year the
 * reference accepts from being refused here merely because a browser control cannot draw it.
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
  /*
   * WHY : ⚠️ Refactoring Rationale: the year is now floored at {@link LOWEST_YEAR_IN_ERA}, and its absence
   *       was a genuine parity gap rather than a theoretical one -- a keyed `0000` passed every edit on
   *       this screen and was composed into a submitted request, where the reference refuses it. See that
   *       constant for the feedback code and the two message numbers that decide it.
   * WHY : Assumptions: the floor is applied to the year ALONE and the existing day comparison is left to
   *       carry the month and day floors, because `daysInMonth` already answers zero for a month outside
   *       one to twelve and the `day >= LOWEST_MONTH_OR_DAY` test already rejects a zero day. Adding a
   *       second month test here would duplicate a rule that is already expressed once.
   */
  if (year < LOWEST_YEAR_IN_ERA) {
    return false;
  }
  return day >= LOWEST_MONTH_OR_DAY && day <= daysInMonth(year, month);
}

/**
 * Lowest year the calendar control can carry as itself, below which it is left unset.
 *
 * Assumptions: one hundred, and the boundary is a measured property of the date library the control is
 * generated over rather than a preference. `dayjs('0000-01-01')`, `dayjs('0001-02-28')` and
 * `dayjs('0099-12-31')` resolve to 1900, 1901 and 1999 respectively, because the underlying `Date`
 * constructor maps a year below one hundred into the twentieth century; `dayjs('0100-01-01')` is the
 * first four-digit year that resolves to itself. The four-character `SDTYYYY` field
 * (`app/cpy-bms/CORPT00.CPY` L90) can hold every one of those years, so the window is reachable by
 * keying rather than hypothetical.
 */
const LOWEST_DISPLAYABLE_YEAR = 100;

/**
 * Resolves the value the calendar control shows for one bound, or nothing when it cannot show it.
 *
 * Purpose: the single place the three keyed parts become a value the additive calendar affordance can
 * display, and the one place the display is allowed to disagree with the parts by being ABSENT rather
 * than by being a different date.
 *
 * ⚠️ Refactoring Rationale: this returned `dayjs(composeIsoDate(parts))` for every well-formed date,
 * which silently rewrote a keyed year below one hundred. An operator who keyed `05 / 15 / 0007` passed
 * every edit as keyed, had `0007-05-15` composed into the request, and read `05/15/1907` back off the
 * calendar box beside the fields -- so the screen showed one date, validated another and submitted a
 * third-party reader's guess at which was real. The parts are the authoritative value and the calendar is
 * an affordance for filling them (AAP gap G5, additive), so the affordance goes blank where it cannot
 * represent the value and never displays a substitute.
 *
 * Alternatives Considered: preserving the extended year by building a `Date` and calling `setFullYear`,
 * which was measured to work -- `dayjs(withFullYearSetToSeven).format('MM/DD/YYYY')` renders
 * `05/15/0007`. Rejected on the control's own parse path: antd generates its picker over dayjs with
 * `customParseFormat`, and `dayjs('05/15/0007', 'MM/DD/YYYY', true)` is INVALID while
 * `dayjs('05/15/0100', 'MM/DD/YYYY', true)` parses. The control would therefore display text it refuses
 * to accept back, and a re-parse of its own displayed text on edit or blur resolves to nothing -- which
 * risks clearing a bound the operator keyed correctly. An empty box loses no data and misstates nothing.
 *
 * Trade-offs: for a year in that window the operator sees the three parts holding what they keyed and an
 * empty calendar box beside them. That is the accepted cost: the alternative on offer was a box holding a
 * date nobody entered, and the reference paints no calendar at all, so nothing of the baseline is lost.
 * @param {DatePartValues} parts - The three parts of one bound, already normalised to their widths.
 * @returns {Dayjs | null} The date to display, or `null` when the parts name no calendar date or name one
 *   the control cannot carry without changing its year.
 */
export function calendarValueFor(parts: DatePartValues): Dayjs | null {
  if (!isExistingCalendarDate(parts)) {
    return null;
  }
  if (Number.parseInt(parts.year, 10) < LOWEST_DISPLAYABLE_YEAR) {
    return null;
  }
  return dayjs(composeIsoDate(parts));
}

/** Outcome of putting a custom report's six keyed parts through the reference's three edit families. */
export interface CustomRangeValidation {
  /**
   * The control to mark, which is the ONE the first failing edit named, or empty when none failed.
   *
   * Assumptions: a list of at most one entry rather than a single nullable member, because
   * `reportRefusal` and the screen's `fieldErrors` state take a list -- a service refusal can name
   * several properties at once, so the list shape is the screen's own and this validation contributes
   * one element to it. The at-most-one bound is the reference's: every failing edit arm ends the task,
   * so a terminal never showed two marks from this family either.
   */
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
 * Purpose: the port of `app/cbl/CORPT00C.cbl` L258-L426 -- the emptiness family, the range family and the
 * two calendar checks -- preserving the family order and the reference's own stop-at-the-first-failure
 * behaviour within each of them.
 *
 * ⚠️ Assumptions: EVERY family stops at its first failing edit, and the reason is the same transfer of
 * control in all three. The emptiness tests are a single `EVALUATE TRUE` at L258-L303, which
 * short-circuits by construction. The range tests at L329-L379 are six independent sequential `IF`
 * statements and the two calendar tests at L387-L426 are two more, which reads as flag-them-all -- but
 * every one of those arms ends in `PERFORM SEND-TRNRPT-SCREEN`, and that paragraph ends with
 * `GO TO RETURN-TO-CICS` at L580, which issues `EXEC CICS RETURN`. The task therefore ENDS inside the
 * first failing arm and no later arm in the paragraph is ever reached: one field is marked, that field's
 * own sentence is on the band, and the cursor is on it.
 *
 * ⚠️ Refactoring Rationale: the range and calendar families accumulated every failing part, kept the LAST
 * one's sentence and put the cursor on the last one's control. That was structurally faithful to the
 * sequential `IF` shape and behaviourally wrong: for a form with two bad parts the reference marks the
 * first and quotes the first, while this marked both and quoted the second. The argument recorded for it
 * -- that flagging everything spares the operator one resubmission per bad part -- is a usability
 * preference, and AAP rule T9 admits no behavioural change on such a basis: an intentional divergence has
 * to be registered in `docs/architecture/cobol-to-service-traceability.md`, and this one was not. Marked
 * fields and message text are exactly the surface AAP rules T7 and T8 hold to the baseline, so the
 * behaviour is now the reference's and the usability idea is not pursued here.
 *
 * Assumptions: normalisation runs BETWEEN the emptiness family and the range family, never before the
 * first, because the reference places it at L305-L327 -- after the emptiness `EVALUATE` closes at L303 and
 * before the first range `IF` at L329. The order matters: normalising first would turn a blank field into
 * `00` and defeat every one of the six emptiness tests.
 * @param {DateRangeValues} values - The six parts exactly as the operator keyed them, untrimmed.
 * @returns {CustomRangeValidation} The one mark to render, the sentence to paint, where to put the cursor,
 *   and the composed bounds when every edit passed.
 */
export function validateCustomRange(values: DateRangeValues): CustomRangeValidation {
  for (const bound of DATE_BOUNDS) {
    for (const part of DATE_PARTS) {
      if (isEmptyPart(values[bound][part])) {
        return refusedRange(`${bound}-${part}`, DATE_REFUSAL_MESSAGES[bound].empty[part]);
      }
    }
  }

  const normalised: DateRangeValues = {
    start: normaliseBound(values.start),
    end: normaliseBound(values.end),
  };

  for (const bound of DATE_BOUNDS) {
    for (const part of DATE_PARTS) {
      if (!isPartWithinRange(normalised[bound][part], part)) {
        return refusedRange(`${bound}-${part}`, DATE_REFUSAL_MESSAGES[bound].range[part]);
      }
    }
  }

  for (const bound of DATE_BOUNDS) {
    if (!isExistingCalendarDate(normalised[bound])) {
      /*
       * WHY : Assumptions: the cursor goes to the MONTH part of the offending bound and not to the part
       *       that is actually wrong, because that is where the reference puts it -- L403 moves `-1` into
       *       `SDTMML` and L423 into `EDTMML`. The validator it calls reports on the date as a whole and
       *       names no part, so the month is the only position the reference can offer and the operator
       *       re-keys the bound from its start.
       */
      return refusedRange(`${bound}-month`, DATE_REFUSAL_MESSAGES[bound].calendar);
    }
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
 * Composes the outcome one failing edit produces: its own mark, its own sentence, its own cursor.
 *
 * Assumptions: the sentence on the band and the sentence beneath the control are the SAME string rather
 * than two, because the reference has only one -- it moves the literal into `WS-MESSAGE`, which
 * `SEND-TRNRPT-SCREEN` moves into `ERRMSGO` at L560, and marks the field by moving `-1` into that field's
 * length member. There is no second per-field text to carry, so both surfaces render the one sentence.
 * @param {ReportsField} field - Control the failing edit named, which is also where the cursor goes.
 * @param {string} message - Verbatim sentence from the message catalog for that edit.
 * @returns {CustomRangeValidation} The refusal, carrying no composed bounds.
 */
function refusedRange(field: ReportsField, message: string): CustomRangeValidation {
  return { fieldErrors: [{ field, message }], message, focus: field, range: null };
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

/**
 * Lowest HTTP status a service uses to report its OWN failure rather than the caller's.
 *
 * Assumptions: five hundred, mirroring `SERVER_ERROR_STATUS` in `ui/src/api/client.ts`, which mirrors the
 * division the shared `ApiError` document draws. It is spelled here rather than imported because that
 * constant is private to the client module, and this screen needs the boundary for a different decision --
 * whether a refusal settled a submission -- not for classifying a failure.
 */
const LOWEST_SERVICE_FAILURE_STATUS = 500;

/**
 * Reports whether a failed submission settled the submission, so its identity may be released.
 *
 * Purpose: decides which failures end a submission and which leave it ambiguous, which is the whole
 * mechanism behind retaining one submission key across retries.
 *
 * ⚠️ Assumptions: only a service's OWN refusal below the server-error boundary is conclusive. A problem
 * document with a 4xx status was written by the reporting handler after it had decided not to start
 * anything -- an unmarked report type, an unrecognised confirmation answer, a malformed submission key, a
 * missing or rejected token -- so the operator must change the request and the next Enter is a genuinely
 * new submission. Every other failure leaves it unknown whether the orchestrator accepted the start: the
 * shared client abandons a call at its configured timeout, a lost connection reports nothing at all, a
 * gateway can answer with a body that is not a problem document, and a 5xx or a 503 can be raised on
 * either side of the moment the run was accepted. Those keep the identity, so pressing Enter again is
 * recognised as the same submission and answered with the run that already exists.
 *
 * Alternatives Considered: releasing the identity on every failure, which is what an unconditional reset
 * in the rejection handler would do. Rejected because the timeout case is precisely the one this
 * mechanism exists for -- `ui/src/api/client.ts` bounds a call at ten seconds by default while the
 * orchestrator may well have accepted the start -- so releasing there would start a second run of the
 * same report and leave two sets of output objects with nothing to say which is current.
 *
 * Alternatives Considered: retaining the identity on every failure without exception, which is simpler
 * and errs safely for double-starts. Rejected because a corrected resubmission after a 400 would then
 * carry the previous attempt's key: with the same report type and the same bounds -- the other three
 * parts of the service's execution name -- the corrected request would be refused as a duplicate of a
 * request that never started anything.
 * @param {unknown} failure - The value the submission rejected with, of any shape.
 * @returns {boolean} `true` when the service refused this submission conclusively, so a later attempt is
 *   a new submission rather than a retry of this one.
 */
export function isDefinitiveRefusal(failure: unknown): boolean {
  return (
    isApiRequestError(failure) &&
    failure.kind === 'PROBLEM' &&
    failure.status < LOWEST_SERVICE_FAILURE_STATUS
  );
}

/** One of the six states `services/reporting-service` publishes for a report execution. */
export type ExecutionState = ReportExecutionStatus['status'];

/**
 * The two states a run is still moving through, so the screen keeps reading its status.
 *
 * ⚠️ Assumptions: `PENDING_REDRIVE` is an ACTIVE state and not a terminal one, which is the single
 * classification here a reader is most likely to get backwards. The orchestration reports it for a run
 * that has been redriven and has not yet restarted -- so it is a transition, and treating it as
 * terminal would stop the screen reading a run that is about to start producing a document. The
 * contract's own note beside the enumeration says an operator has already been told about such a run,
 * which is why it needs no sentence of its own beyond its status label.
 *
 * Assumptions: the ACTIVE set is enumerated rather than the terminal one, and the polling predicate is
 * derived from it. Both readings are expressible; this one is chosen because the two active states are
 * the closed set the screen has to keep working for, whereas a new terminal state added to the
 * contract would then default to "stop reading", which is the safe direction to default in.
 */
const ACTIVE_EXECUTION_STATES: readonly ExecutionState[] = ['RUNNING', 'PENDING_REDRIVE'];

/**
 * Reports whether a run is still going, and therefore whether its status is worth reading again.
 * @param {ExecutionState} state - State the last status read reported.
 * @returns {boolean} `true` while the run may still change state on its own.
 */
export function isExecutionActive(state: ExecutionState): boolean {
  return ACTIVE_EXECUTION_STATES.includes(state);
}

/**
 * The authored sentence each terminal FAILURE state is explained with.
 *
 * Assumptions: three sentences rather than one, because the three states differ in what an operator
 * does next. `services/reporting-service/src/main/resources/openapi/reporting-api.yaml` states the
 * distinctions are ones an operator acts on -- a timed-out run is retried, an aborted run was stopped
 * deliberately -- so one shared sentence would delete the only information the three carry that a bare
 * "it failed" does not. Each string is imported from the catalog; none is composed here.
 */
const TERMINAL_FAILURE_DETAILS: Readonly<Record<'FAILED' | 'TIMED_OUT' | 'ABORTED', string>> = {
  FAILED: REPORT_RUN_MESSAGES.FAILED_DETAIL,
  TIMED_OUT: REPORT_RUN_MESSAGES.TIMED_OUT_DETAIL,
  ABORTED: REPORT_RUN_MESSAGES.ABORTED_DETAIL,
};

/** The three coordinates the collect operation addresses one run's document by. */
export interface ReportArtifactCoordinates {
  /** The run's own report-type token, one of the four the contract publishes. */
  readonly reportType: string;
  /** Inclusive lower bound of the range the run covered. */
  readonly startDate: string;
  /** Inclusive upper bound of the range the run covered. */
  readonly endDate: string;
}

/**
 * Reports the coordinates a succeeded run's document can be collected by, or `null` when there is none.
 *
 * ⚠️ Assumptions: FOUR conditions are required and none of them is redundant. The state must be
 * `SUCCEEDED`, because the contract states that only a succeeded run can have an artifact; and the
 * result location must be present, because it is null while the run is going, after it failed AND after
 * a lifecycle rule has expired what the run wrote -- so a completed run whose document has since been
 * swept reports success with nothing to collect. The three coordinates must each be present because
 * they are null for a run started outside this surface, which the nightly schedule does.
 *
 * Assumptions: the coordinates are taken from the STATUS and not from the submission this screen holds,
 * although both carry a range. The status reports the range as the orchestration recorded it, which is
 * the range the stored document actually covers; the submission reports what was asked for. They agree
 * for every run this screen starts, and preferring the status means the collect request is composed from
 * the same values the service composed the location from.
 *
 * Alternatives Considered: following `resultUri` directly, which is exactly the collect operation's path
 * with these three values as query parameters. Rejected because the shared client's operation takes the
 * three values and builds the path from its own contract manifest -- so following the location would
 * mean either parsing a URL to recover them or bypassing the manifest, and the manifest is what keeps
 * `ui/src/api/contracts.test.ts` able to check every request path against the published document.
 *
 * Alternatives Considered: checking the report type against the four tokens the contract enumerates, and
 * the two bounds against the ISO calendar form. Deliberately NOT done, and the reason is what the
 * rejection would cost rather than what the check would gain: `collectReportArtifact` documents a 400 for
 * a token outside the published set, and the value here is the service's own recording of a run it
 * started -- so a token this client did not recognise would be one the CONTRACT had gained, and
 * withholding the download for it would report "no document" for a document that exists. The values are
 * carried as query parameters, which the transport encodes, so nothing is composed from them unchecked.
 * @param {ReportExecutionStatus | null} execution - The last status read, or `null` when none has been.
 * @returns {ReportArtifactCoordinates | null} The three coordinates, or `null` when nothing is
 *   collectable.
 */
export function collectableCoordinates(
  execution: ReportExecutionStatus | null,
): ReportArtifactCoordinates | null {
  if (execution === null || execution.status !== 'SUCCEEDED' || execution.resultUri === null) {
    return null;
  }
  const { reportType, startDate, endDate } = execution;
  if (reportType === null || startDate === null || endDate === null) {
    return null;
  }
  return { reportType, startDate, endDate };
}

/**
 * Reports the sentence explaining a run's outcome, or `null` while there is nothing to explain.
 *
 * Assumptions: an ACTIVE run and a collectable succeeded one both answer `null`, because the status
 * label beside them already says everything there is to say -- a sentence repeating "it is running"
 * would fill the region with text carrying no information the label does not.
 *
 * Assumptions: a succeeded run with nothing to collect is reported with the same sentence whether its
 * document has expired or the run was started outside this surface. The two causes differ and the
 * available action does not: the report has to be submitted again either way, and naming the cause
 * would describe the deployment's retention policy on an operator's screen.
 * @param {ReportExecutionStatus | null} execution - The last status read, or `null` when none has been.
 * @returns {string | null} The verbatim catalog sentence, or `null` when the label suffices.
 */
export function executionOutcomeDetail(execution: ReportExecutionStatus | null): string | null {
  if (execution === null) {
    return null;
  }
  if (execution.status === 'SUCCEEDED') {
    return collectableCoordinates(execution) === null
      ? REPORT_RUN_MESSAGES.DOCUMENT_UNAVAILABLE
      : null;
  }
  // Assumptions: the three failure states are named individually rather than the table being indexed
  //   with the status directly, so the compiler proves the key exists. Indexing by the six-value union
  //   would need a cast, and a cast here would compile just as happily on the day a seventh state is
  //   published and answer `undefined` for it at run time.
  if (
    execution.status === 'FAILED' ||
    execution.status === 'TIMED_OUT' ||
    execution.status === 'ABORTED'
  ) {
    return TERMINAL_FAILURE_DETAILS[execution.status];
  }
  return null;
}

/**
 * Stem of the name the collected document is written to the file system under.
 *
 * ⚠️ Assumptions: a name is composed here BECAUSE the service deliberately declines to supply one. The
 * contract states its `Content-Disposition` is `attachment` with no filename, and gives the reason: a
 * filename derived from a card or an account would put an identifier into a value the browser writes to
 * the file system. A transaction report is addressed by a type and two dates, which describe a query and
 * name no person, so composing a name from exactly those three values stays inside that reasoning rather
 * than working around it. Leaving the name to the browser is not an option worth taking: an object URL's
 * basename is an opaque identifier, so every collected report would land in the operator's downloads
 * folder under a different meaningless name.
 */
const REPORT_DOCUMENT_FILE_STEM = 'transaction-report';

/**
 * Extension the collected document is named with.
 *
 * Assumptions: `.txt` and not `.rpt` or no extension at all. The body is 133-column fixed-width text,
 * one record per line, and the extension is what decides whether an operator's machine offers to open it
 * with something that can display it. It says nothing about the media type, which the service declares as
 * an opaque attachment so that no client re-encodes a parity artifact.
 */
const REPORT_DOCUMENT_FILE_SUFFIX = '.txt';

/**
 * Composes the file name one collected report document is saved under.
 *
 * Assumptions: the three coordinates are interpolated AS THE SERVICE RECORDED THEM, unnormalised. The
 * result is carried on a `download` attribute, which names a file and is not a path -- a browser strips
 * any separator it finds rather than following it -- so the value reaches the file system as one name
 * whatever it contains. Normalising here would mean this module deciding what a report type may look
 * like, which is the contract's decision and is made at the guard in {@link collectableCoordinates}.
 * @param {ReportArtifactCoordinates} coordinates - The run's type and both range bounds.
 * @returns {string} A name carrying the three coordinates, so two runs over different ranges do not
 *   overwrite each other in the operator's downloads folder.
 */
export function reportDocumentFileName(coordinates: ReportArtifactCoordinates): string {
  return `${REPORT_DOCUMENT_FILE_STEM}-${coordinates.reportType}-${coordinates.startDate}-${coordinates.endDate}${REPORT_DOCUMENT_FILE_SUFFIX}`;
}

/**
 * Hands one collected document to the browser to save, and releases the handle it was passed through.
 *
 * ⚠️ Assumptions: the anchor is a TRANSPORT and not user interface, which is why it is a bare element in
 * a tree where AAP section 0.3.2 admits only design-system components. It is created, clicked and
 * removed inside this one call, is never part of a render, and carries no visual presence at all. The
 * `download` attribute is the only mechanism a browser offers for naming a saved file, and a
 * design-system button cannot carry it for bytes that do not exist until a request has answered.
 *
 * Alternatives Considered: holding the object URL in state and rendering a link the operator clicks a
 * second time. Rejected because it turns one action into two and leaves a live handle to the document
 * on the page for as long as the operator does not take the second one.
 *
 * ⚠️ Assumptions: the handle is released on the NEXT task rather than immediately after the click. The
 * click starts the transfer, and browsers differ on whether they have finished reading the blob by the
 * time the calling task ends -- revoking synchronously has been observed to abort the save. Deferring by
 * one task keeps the release deterministic without racing the transfer, and the release happens either
 * way, so no handle is leaked.
 * @param {Blob} bytes - The document exactly as the service wrote it, undecoded.
 * @param {ReportArtifactCoordinates} coordinates - The run's coordinates, which name the saved file.
 * @returns {void} Nothing; the browser is left to write the file.
 */
function saveReportDocument(bytes: Blob, coordinates: ReportArtifactCoordinates): void {
  const handle = URL.createObjectURL(bytes);
  const anchor = window.document.createElement('a');
  anchor.href = handle;
  anchor.download = reportDocumentFileName(coordinates);
  // Assumptions: `noopener` is set although the anchor opens nothing. It costs nothing, and it means a
  //   later edit that adds a target cannot hand the opened context a reference back to this one.
  anchor.rel = 'noopener';
  window.document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(
    /**
     * Releases the object URL once the browser has had a task to start reading it.
     * @returns {void} Nothing; the handle is revoked in place.
     */
    (): void => {
      URL.revokeObjectURL(handle);
    },
  );
}

/**
 * Gap between two automatic status reads, in milliseconds.
 *
 * Assumptions: five seconds, matching the receive wait `ui/src/api/reporting.ts` records the reference's
 * message flows using, so the one interval this application polls on is the one interval the baseline
 * already waited on. Trade-offs: a shorter gap would report a finished run sooner and multiply the reads
 * a nightly-sized report costs; a longer one would leave an operator watching an unchanging label. The
 * manual control beside it is what makes the choice non-critical -- an operator who does not want to wait
 * does not have to.
 */
const STATUS_POLL_INTERVAL_MS = 5_000;

/**
 * How many FURTHER reads one tracked run gets after its first, before the screen stops on its own.
 *
 * Assumptions: the first read is not counted, because it is not on the timer -- adopting a run reads its
 * status at once, and this bounds only what the screen goes on to schedule. So a followed run costs one
 * read plus at most this many, which is what `ui/src/screens/reports/reports.test.tsx` asserts.
 *
 * ⚠️ Assumptions: the loop is BOUNDED rather than left to run for as long as the screen is mounted, and
 * the bound is the point of it. A report over a wide date range can outlive an operator's attention, and
 * an unbounded loop would then have a forgotten tab reading a status every five seconds for as long as it
 * stayed open -- a request per five seconds per abandoned tab, against a service whose other reads are
 * operator-driven. Sixty scheduled reads is five minutes at the interval above, after which the screen
 * says so and the operator refreshes.
 *
 * Alternatives Considered: stopping when the document loses focus, and backing off exponentially. Both
 * were rejected for the same reason: they make the moment the screen stops reading depend on something an
 * operator cannot see, whereas a fixed count reaches a state the screen can and does announce.
 */
const MAX_AUTOMATIC_STATUS_READS = 60;

/**
 * The status the collect operation answers when there is no document at those coordinates.
 *
 * Assumptions: this is the one status of that operation's five this screen distinguishes, because it is
 * the only one that changes what the operator should do. `collectReportArtifact` documents it as the
 * answer both for a run that never happened and for one whose document has been expired by a lifecycle
 * rule, and neither is recoverable by trying again -- so it is reported as an absent document while every
 * other answer is reported as a request to retry.
 */
const DOCUMENT_ABSENT_STATUS = 404;

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
   * WHY : Refactoring Rationale: the run's LIFECYCLE is held apart from the submission that started
   *       it, because they are two different facts with two different lifetimes. The submission is
   *       what one turn produced and never changes again; the lifecycle is what the orchestration
   *       reports about that run and changes without the operator doing anything. This screen
   *       previously held only the first of the two and rendered the run's name, so a report an
   *       operator had submitted could not be followed and its document could not be reached from
   *       anywhere in the application -- the two operations that answer both questions were published
   *       by `ui/src/api/reporting.ts` and called from nowhere.
   * WHY : Assumptions: `busy` is NOT reused for either of the two reads below. It gates the form and
   *       disables every input while a submission is outstanding, which is the wrong behaviour for a
   *       status read -- an operator reading the status of a finished run has no reason to lose the
   *       form they are filling in for the next one.
   */
  const [execution, setExecution] = useState<ReportExecutionStatus | null>(null);
  const [runNotice, setRunNotice] = useState<string | null>(null);
  const [statusReadPending, setStatusReadPending] = useState(false);
  const [collecting, setCollecting] = useState(false);

  /*
   * WHY : Assumptions: a manual read is requested by BUMPING a counter that the following effect
   *       depends on, rather than by calling the read directly. The effect owns the timer, so a read
   *       started outside it would run alongside a scheduled one and both would settle into the same
   *       state; changing the dependency tears the timer down and starts a single fresh loop, which
   *       makes "refresh now" and "keep polling" one mechanism instead of two that have to agree.
   * WHY : Alternatives Considered: exposing the read as a callback and having the effect call it on a
   *       timer. Rejected because the callback would then need the same liveness guard the effect's
   *       teardown already provides, and a settlement arriving after a newer run replaced the tracked
   *       one would have nothing to be dropped by.
   */
  const [statusReadRequest, setStatusReadRequest] = useState(0);

  /**
   * Identity of the run being followed, or `null` when no run has been started this turn.
   *
   * Assumptions: derived from the submission rather than stored beside it, so exactly one run is
   * followed and it is always the one the screen is displaying.
   *
   * ⚠️ Refactoring Rationale: a refusal no longer clears the submission and therefore no longer stops the
   * loop -- see {@link reportRefusal} for why the two were uncoupled. The tracked run now ends only when a
   * new submission replaces it or the screen is left, so a mistyped field cannot silently strand a run
   * whose name is the only key its status can be read by. `docs/runbooks/batch-operations.md` remains the
   * route for an operator who lost a handle by leaving the screen.
   */
  const trackedRun = submission === null ? null : submission.executionName;

  /**
   * Whether the last turn stopped to ask for the confirmation and the pointer path raised the question.
   *
   * ⚠️ Assumptions: this drives the consent balloon's open state rather than the library's own trigger,
   * which is what keeps a confirmation from being offered before the edits that must precede it have run.
   * It is set from {@link runTurn}'s own answer in {@link submitFromPointer} and cleared by every other
   * path, so the balloon can only stand open over a form whose report type and range the screen has
   * already accepted.
   */
  const [confirmationAsked, setConfirmationAsked] = useState(false);

  /*
   * WHY : Assumptions: two references guard the asynchronous work, and they answer different
   *       questions. The mounted flag decides whether a settlement may touch state at all, and is
   *       needed because collecting a document is the one operation here that can outlive the
   *       operator's interest in the screen. The read counter decides whether the loop has spent its
   *       budget, and lives in a reference rather than in state because changing it must not itself
   *       cause a render -- it is incremented from inside the effect that would then re-run.
   */
  const mounted = useRef(true);
  const automaticReads = useRef(0);

  /**
   * The run the screen is currently following, as a value a settlement can read.
   *
   * ⚠️ Assumptions: this exists for the COLLECTION and not for the status read. The status read is owned
   * by an effect whose teardown disowns it, so replacing the tracked run drops its answer; a collection
   * is started by a click and has no teardown, so its only guard would otherwise be that the screen is
   * still mounted -- and a document collected for a superseded run would then be saved to the operator's
   * file system while a different run is on the screen. Comparing against this makes the guard the run
   * rather than the component.
   */
  const followedRun = useRef<string | null>(null);

  /*
   * WHY : Assumptions: the in-flight state is held in a ref AS WELL AS in state, and the two answer
   *       different questions. The state value drives the rendered busy affordance; the ref is what a
   *       handler reads to decide whether a turn is already running, because React state set earlier in the
   *       same task is not readable within it. A 3270 keyboard locks until the region replies, so the
   *       reference needs no guard at all; without one here a doubled Enter would start two report runs
   *       where the operator asked for one.
   */
  const inFlight = useRef(false);

  /*
   * WHY : ⚠️ Refactoring Rationale: one submission identity is minted per SUBMISSION and retained across
   *       its attempts, where every dispatch used to be anonymous. The reporting service deduplicates a
   *       re-sent submission by composing its orchestration execution name from the report type, both
   *       bounds and a submission key -- `ReportExecutionService.executionName` -- and it takes that key
   *       from the `Idempotency-Key` header when one arrives, falling back to a digest of the request's
   *       correlation identifier when none does. This screen sent no header and the shared client minted a
   *       fresh correlation identifier on every dispatch, so neither source was stable: an operator who
   *       pressed Enter again after the ten-second client timeout expired on a call the orchestrator had
   *       already accepted started a SECOND run of the same report, producing two sets of output objects
   *       with nothing to say which was current.
   * WHY : Assumptions: a ref rather than state, for the reason `inFlight` above is one -- the identity has
   *       to be readable and writable inside the handler that dispatches, and state set earlier in the same
   *       task is not readable within it. Nothing renders from it, so there is nothing for a re-render to
   *       carry.
   * WHY : Trade-offs: the identity is retained even when the operator edits the form between attempts,
   *       which is safe rather than merely tolerable: the service's execution name carries the report type
   *       and BOTH bounds beside the key, so a retained key cannot fold a submission over one range onto a
   *       run over another. Clearing it on every edit was the alternative, and it would break the case this
   *       exists for -- an operator who corrects nothing and simply presses Enter again after a timeout.
   */
  const submissionIdentity = useRef<string | null>(null);

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
     * Assumptions: focusing the report-type group reaches the FIRST radio rather than the element the
     * reference points at, because the element carrying `role="radiogroup"` is a container and not a
     * focusable control. The reference's `MOVE -1 TO MONTHLYL` names the monthly field specifically, and
     * monthly is the first option, so querying the group for its first input lands on exactly the field
     * the reference names -- and keeps doing so without a second ref per option.
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

  useEffect(
    /**
     * Tracks whether the screen is still mounted, so a settlement never writes state after it is gone.
     *
     * Assumptions: the flag is raised here as well as at its declaration, for the reason
     * `ui/src/hooks/usePagedQuery.ts` records of the same arrangement: a remount reuses neither the
     * reference's initial value nor the previous teardown's, so under a development double-mount the
     * first teardown lowers it and the second mount has to raise it again.
     * @returns {() => void} Teardown lowering the flag.
     */
    (): (() => void) => {
      mounted.current = true;
      return (
        /**
         * Lowers the mounted flag so an outstanding collection settles into nothing.
         * @returns {void} Nothing; the flag is lowered in place.
         */
        (): void => {
          mounted.current = false;
        }
      );
    },
    [],
  );

  useEffect(
    /**
     * Forgets the previous run's lifecycle as soon as a different run becomes the tracked one.
     *
     * ⚠️ Assumptions: this is declared BEFORE the following effect and the order is load bearing. React
     * runs a component's effects in declaration order, so on the render that adopts a new run the
     * previous run's status has already been dropped by the time the loop below performs its first
     * read -- which is what stops the old run's state being displayed under the new run's reference for
     * the length of one request.
     *
     * Assumptions: it depends on the tracked run alone, so a manual read does NOT clear the status. A
     * refresh that blanked the status and then refilled it would flicker the one value the operator
     * pressed the control to see.
     *
     * ⚠️ Assumptions: the two BUSY flags are lowered here as well, and not only the two values. Either
     * read can be outstanding when a newer run is adopted, and the settlement that would have lowered
     * the flag is then disowned -- so without this a superseded collection would leave its control
     * spinning against the new run for as long as the screen stayed open, describing work that is no
     * longer being done for the run on display.
     * @returns {void} Nothing; the derived lifecycle state is reset in place.
     */
    (): void => {
      setExecution(null);
      setRunNotice(null);
      setStatusReadPending(false);
      setCollecting(false);
      automaticReads.current = 0;
      followedRun.current = trackedRun;
    },
    [trackedRun],
  );

  useEffect(
    /**
     * Reads the tracked run's status, and keeps reading while the run is still going.
     *
     * Purpose: the half of the report lifecycle the reference has no equivalent of at all. Writing to
     * the `JOBS` transient data queue returned no identity and no outcome -- `app/csd/CARDDEMO.CSD`
     * L499-L505 defines it with `ERROROPTION(IGNORE)` -- so this is a documented improvement rather
     * than a port, and the loop exists because the target's own contract publishes a status to read.
     *
     * ⚠️ Assumptions: the loop is a CHAIN of timers and not an interval. Each read is scheduled only
     * once the previous one has settled, so a slow service cannot accumulate overlapping requests --
     * which `setInterval` would do, and which would then have several settlements racing to write one
     * status.
     *
     * Assumptions: a read that FAILS stops the chain rather than retrying. The operator is told and
     * has a control that reads again, so an automatic retry would repeat a failing request on a timer
     * while adding nothing an operator cannot do deliberately.
     * @returns {() => void} Teardown that both stops the chain and makes any outstanding settlement a
     *   no-op, so replacing the tracked run or leaving the screen cannot revive the previous run's
     *   state.
     */
    (): (() => void) => {
      let live = true;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const name = trackedRun;

      /**
       * Stops the chain and disowns whatever is outstanding.
       * @returns {void} Nothing; the flag and the timer are cleared in place.
       */
      function stopFollowing(): void {
        live = false;
        if (timer !== undefined) {
          clearTimeout(timer);
        }
      }

      if (name === null) {
        return stopFollowing;
      }

      /*
       * WHY : Assumptions: the four steps below are `const` arrows rather than the function
       *       declarations the rest of this module uses, and the reason is a compiler rule rather
       *       than a style preference. TypeScript preserves a narrowing of a `const` inside a closure
       *       CREATED AFTER the narrowing, and a `function` declaration is hoisted to the top of its
       *       block -- so declared that way, `name` would still be `string | null` inside these bodies
       *       even though the guard above has already returned for the null case, and the read would
       *       not compile. Threading the narrowed name through four signatures was the alternative;
       *       it compiles and puts the same value in four parameter lists for no gain.
       */

      /**
       * Schedules the next automatic read, or reports that automatic reads have stopped.
       * @returns {void} Nothing; either a timer is armed or the notice is painted.
       */
      const scheduleNextRead = (): void => {
        if (automaticReads.current >= MAX_AUTOMATIC_STATUS_READS) {
          setRunNotice(REPORT_RUN_MESSAGES.AUTOMATIC_UPDATES_STOPPED);
          return;
        }
        automaticReads.current += 1;
        timer = setTimeout(readStatus, STATUS_POLL_INTERVAL_MS);
      };

      /**
       * Adopts a status read, and keeps the chain going while the run is still moving.
       * @param {ReportExecutionStatus} status - What the service reports about the run.
       * @returns {void} Nothing; the run's state is replaced and the next read possibly armed.
       */
      const applyStatus = (status: ReportExecutionStatus): void => {
        if (!live) {
          return;
        }
        setStatusReadPending(false);
        setRunNotice(null);
        setExecution(status);
        if (isExecutionActive(status.status)) {
          scheduleNextRead();
        }
      };

      /**
       * Reports a status read that did not answer, without disclosing why.
       *
       * Assumptions: the cause goes to the console and the operator gets one authored sentence, which
       * is the split `ui/src/main.tsx` and `ui/src/layout/ShellContentBoundary.tsx` both apply. An
       * HTTP status, a run name and a service path are internal; the operator can act on none of them
       * and rendering any of them would put deployment detail into every screenshot of this screen.
       * @param {unknown} cause - Whatever the read rejected with.
       * @returns {void} Nothing; the notice is painted and the chain is left stopped.
       */
      const reportUnreadableStatus = (cause: unknown): void => {
        if (!live) {
          return;
        }
        console.warn('carddemo: the status of a report run could not be read', cause);
        setStatusReadPending(false);
        /*
         * WHY : ⚠️ Refactoring Rationale: the notice is now SELECTED on whether repeating this read could
         *       plausibly succeed, where one sentence covered every cause. `STATUS_READ_FAILED` ends with
         *       "Refresh to try again", and offering that remedy for a failure a refresh cannot clear --
         *       a malformed execution name, a permission the operator does not hold -- sends them round a
         *       loop the screen already knows is closed. `PERSISTENT_FAILURE_REPORT_IT` reads "That
         *       request did not complete. Report it if it happens again." and names the action that can
         *       actually change the outcome.
         * WHY : Assumptions: the split is on `repeatable` and NOT on `transient`, and the two are
         *       different questions that `ui/src/api/client.ts` L919-L949 deliberately keeps apart:
         *       `transient` describes the CONDITION and `repeatable` additionally requires the REQUEST to
         *       be safe to send again. Selecting on `transient` alone would offer a retry for a condition
         *       that may pass on a request that must not be repeated; this is a `GET`, so for this call
         *       the two coincide today, and asserting on the narrower member is what keeps the choice
         *       correct if the read ever stops being a `GET`.
         * WHY : Assumptions: a cause that is not a normalised transport failure at all -- a programming
         *       error thrown inside the settlement, say -- falls to the persistent sentence, because
         *       `isRepeatableFailure` narrows before it reads the member and answers false for anything
         *       it cannot narrow. That is the right default: an unclassifiable failure is not evidence
         *       that retrying will help.
         * WHY : Alternatives Considered: `TRANSIENT_FAILURE_TRY_AGAIN` for the repeatable arm, which is
         *       the generic half of the authored pair and the symmetrical choice beside
         *       `PERSISTENT_FAILURE_REPORT_IT`. Rejected because it reads "The service is not available
         *       at the moment. Try again shortly." and names no action available on this screen, whereas
         *       `STATUS_READ_FAILED` names the one control that exists for it -- Refresh -- and says what
         *       failed. The precedence the authored pair is published under is that a MORE SPECIFIC
         *       sentence wins: a service-supplied message is rendered verbatim over either of them, and
         *       a screen-owned sentence that is width-checked against the row-23 field and carries the
         *       remedy is specific in the same way. The generic sentence therefore has no site on this
         *       screen: its other failure surface is a submission, and that one carries the reference's
         *       own transcribed sentence under rule T8, which outranks any authored text.
         * WHY : Assumptions: the Refresh CONTROL stays available in both cases, and is deliberately not
         *       gated on this judgement. It is the only access to a run's state -- the execution name is
         *       the sole key the status endpoint accepts and it is shown nowhere else -- and the run
         *       continues on the service whatever this read did, so a later read can legitimately
         *       succeed where this one failed. What the judgement changes is what the operator is TOLD
         *       to do, not what they are permitted to do.
         */
        setRunNotice(
          isRepeatableFailure(cause)
            ? REPORT_RUN_MESSAGES.STATUS_READ_FAILED
            : PERSISTENT_FAILURE_REPORT_IT,
        );
      };

      /**
       * Reads the run's status once.
       * @returns {void} Nothing; the outcome is applied by one of the two settlements above.
       */
      const readStatus = (): void => {
        setStatusReadPending(true);
        readReportExecution(name).then(applyStatus, reportUnreadableStatus);
      };

      readStatus();
      return stopFollowing;
    },
    [trackedRun, statusReadRequest],
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
   *
   * ⚠️ Refactoring Rationale: this NO LONGER clears {@link submission}, and the omission is the fix for the
   * reported defect that clearing the form destroyed the reference of a report that was already running.
   * The reference's `INITIALIZE` list is the ten screen fields and `WS-MESSAGE` -- it cannot mention a
   * submission handle, because the baseline had none: writing to the `JOBS` queue returned no identity at
   * all. So the handle is a target-side addition, and folding it into a paragraph named for the reference's
   * field initialisation gave a form-clearing action the additional power to abandon a live run.
   *
   * ⚠️ Trade-offs: a handle therefore OUTLIVES the form that produced it, and the operator can be looking at
   * an empty form above the panel of the run they last started. That is the intended reading of this screen:
   * the form is a request builder and the panel is a monitor for one submitted run, so the two are cleared
   * by different actions. The alternative -- clearing the panel here and giving the operator no way back to
   * a run that is genuinely still executing on the service -- loses information that cannot be recovered,
   * because the execution name is the only key the status endpoint accepts and it is not shown anywhere else.
   *
   * Assumptions: the started arm of the submission settlement still calls this and then installs its own
   * handle, so the ordering there is unaffected; and a NEW submission replaces the handle outright, so no
   * caller has to clear it first to avoid displaying a stale one.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function initialiseAllFields(): void {
    setReportType(null);
    setRange(EMPTY_RANGE);
    setConfirmAnswer('');
    setMessage(null);
    setSeverity('error');
    setFieldErrors([]);
    focusField('reportType');
  }

  /**
   * Paints one refusal: the sentence in red, the marks on the named controls, the cursor on one of them.
   *
   * ⚠️ Refactoring Rationale: a refusal no longer clears {@link submission}, which is the direct fix for the
   * reported defect that a failed validation unmounted the whole execution panel and destroyed the reference
   * of an already-submitted report. A refusal is a statement about the FORM -- one field the operator has
   * still to correct -- and it says nothing whatsoever about a run the service has already accepted. Coupling
   * the two meant that mistyping a date on the next request silently abandoned the previous run, and the
   * measured consequence was a monitored run going dark for a minute with no way to recover its name.
   *
   * ⚠️ Assumptions: the panel staying mounted also keeps its status chain alive across the refusal, so the
   * gap the tester measured between two reads closes as a consequence of the same change rather than needing
   * a second one. The chain is keyed on the tracked run, and the tracked run is exactly what stops being
   * discarded here.
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
    focusField(focus);
  }

  /**
   * Resolves what a chosen report type SUBMITS, running the edit chain only for the custom type.
   *
   * Assumptions: the two preset types are never edit-checked, because the reference derives them rather
   * than reading them from the screen -- `app/cbl/CORPT00C.cbl` L214-L238 and L240-L255 -- and its whole
   * edit chain sits inside the custom arm at L256-L436.
   *
   * ⚠️ Refactoring Rationale: the two presets now submit NO bounds, where they previously computed both
   * from the anchored instant and transmitted them. Transmitting them was measurably pointless: the
   * service does not read them on a preset. `ReportExecutionService.resolveRange(request, reportName)`
   * dispatches on the report name and calls `resolveMonthlyRange()` and `resolveYearlyRange()` with NO
   * arguments, reaching `resolveCustomRange(request)` -- the only arm that reads the request's bounds --
   * for the custom type alone. So the computed pair was serialised, sent and discarded, and the two
   * clock-derived computers that produced it were dead weight behind an appearance of authority.
   *
   * ⚠️ Assumptions: the service's clock is therefore authoritative for a preset's period, which is a
   * registered divergence rather than a silent one -- `D-REPORT-PRESET-RANGE-SERVICE-CLOCK` in
   * `docs/architecture/cobol-to-service-traceability.md`. The reference resolved a preset on the
   * presentation side from the region's own clock, and the target resolves it on the service side from
   * the service's; both read one trusted clock, and neither lets the browser's clock decide. Sending a
   * client-computed pair could not have made the two agree in any case -- the service ignores it -- so
   * the previous rationale about a submission either side of midnight resolving two different ranges
   * described a guarantee the transmission never actually bought.
   *
   * Assumptions: the two presets remain WITHHELD until a server instant has been observed, which
   * {@link renderReportTypeOption} enforces on the control itself. That gate is about provenance and not
   * about this computation, so removing the computation does not release it: a preset means "the period
   * the system considers current", and the screen declines to offer one until it can paint which date
   * that is -- `ui/src/screens/reports/refusalAnnouncement.test.tsx` holds both halves of that.
   * @param {ReportType} chosen - Report type the operator selected.
   * @returns {SubmittedRange | null} The bounds to submit -- neither, for a preset -- or `null` when a
   *   refusal was painted instead.
   */
  function resolveRange(chosen: ReportType): SubmittedRange | null {
    if (chosen === 'monthly' || chosen === 'yearly') {
      return NO_SUBMITTED_RANGE;
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
   *
   * Assumptions: the submission identity is resolved here rather than in {@link runTurn}, because this is
   * the one function that dispatches -- a turn refused by the report-type check, the range edits or the
   * confirmation sends nothing, so it must not consume an identity and make the next real submission look
   * like a retry of a request that never left the browser.
   * @param {ReportType} chosen - Report type being submitted, which names the acknowledgement's report.
   * @param {SubmittedRange} submittedRange - Bounds the run is submitted over, which a preset leaves
   *   empty so the service resolves them from its own clock.
   * @param {string} answer - The confirmation character to relay, which is always a consenting one.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function startReportRun(
    chosen: ReportType,
    submittedRange: SubmittedRange,
    answer: string,
  ): void {
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
      /*
       * WHY : Assumptions: the range is SPREAD rather than assigned member by member, so that a preset
       *       omits both keys instead of sending them as `undefined`. The two are optional in
       *       `ReportRequest`, and omitting a key and sending it undefined are the same value to a
       *       reader but not the same request on the wire -- `exactOptionalPropertyTypes` is what makes
       *       the distinction expressible here, and the spread is the form that keeps it.
       */
      ...submittedRange,
      confirm: answer,
    };

    inFlight.current = true;
    setBusy(true);
    setMessage(null);
    setFieldErrors([]);

    /*
     * WHY : Assumptions: a retained identity is REUSED and only an absent one is minted, so the first
     *       attempt and every later attempt at one submission carry the same value. `newSubmissionKey`
     *       states why its shape satisfies both published domains at once -- the reporting service's
     *       submission-key domain and the narrower correlation-identifier contract the shared client
     *       filter enforces -- so this call site chooses no format of its own.
     */
    submissionIdentity.current ??= newSubmissionKey();
    const submissionKey = submissionIdentity.current;

    submitTransactionReport(request, submissionKey).then(
      /**
       * Renders the outcome the service reported for this submission.
       * @param {ReportSubmissionOutcome} outcome - Which of the three arms the service answered with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (outcome: ReportSubmissionOutcome): void => {
        inFlight.current = false;
        setBusy(false);
        /*
         * WHY : Assumptions: the identity is spent on ANY of the three answered outcomes, because each of
         *       them is the service having settled this submission -- started, declined, or asking for the
         *       confirmation again. The next Enter is then a new submission and mints a new identity, which
         *       is what keeps a legitimate rerun of the same report over the same range from being folded
         *       onto the run that just finished: the service remembers an execution name for ninety days.
         */
        submissionIdentity.current = null;

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
        /*
         * WHY : Assumptions: the identity survives an INCONCLUSIVE failure and is spent on a conclusive
         *       one, which is the whole point of retaining it -- see {@link isDefinitiveRefusal} for which
         *       failures fall on which side and why the ambiguous ones are the ones that matter.
         */
        if (isDefinitiveRefusal(failure)) {
          submissionIdentity.current = null;
        }
        const refusal = mapSubmissionFailure(failure);
        reportRefusal(refusal.message, refusal.fieldErrors, refusal.focus);
      },
    );
  }

  /**
   * Reads the tracked run's status now, without waiting for the next automatic read.
   *
   * Purpose: the operator's own way of asking what became of a run -- the action the baseline required
   * a different system for, because the queue write it performed reported nothing back.
   *
   * ⚠️ Refactoring Rationale: this NO LONGER restores the automatic budget, and that restoration was the
   * measured cause of the reported defect -- an observed 71 automatic reads against a documented bound of
   * {@link MAX_AUTOMATIC_STATUS_READS}, because each press of this control reset the count to zero and bought
   * a further sixty. The previous reasoning was that an operator pressing a control has contradicted the
   * evidence of abandonment the bound guards against. It is true of the press and false of everything after
   * it: one deliberate press cannot testify that the operator is still present sixty reads and five minutes
   * later, so re-arming turned a bounded chain into an unbounded one that any single press could extend
   * indefinitely, and made the `AUTOMATIC_UPDATES_STOPPED` notice describe a state the screen had left.
   *
   * ⚠️ Assumptions: the operator loses nothing by this, because requesting a read and scheduling further ones
   * are separate steps. Advancing the request below re-enters the status effect, which performs ONE read
   * immediately and unconditionally before it consults the budget at all -- so this control answers every
   * press even with the budget fully spent, and only the automatic continuation stays stopped. The operator
   * therefore keeps an unlimited number of reads on demand and the screen keeps a finite number on a timer,
   * which is the split the bound was written for.
   *
   * ⚠️ Alternatives Considered: granting a smaller top-up per press, and resetting the budget only while the
   * document is still being produced. Both keep the defect in a reduced form -- a total that no longer has a
   * ceiling, only a slower climb towards none -- and both make the stop notice conditional on arithmetic the
   * operator cannot see. Leaving the budget alone is the only version in which the notice, once shown, stays
   * true.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function refreshRunStatus(): void {
    if (trackedRun === null || statusReadPending) {
      return;
    }
    setStatusReadRequest(
      /**
       * Advances the read request so the following effect restarts with a fresh read.
       * @param {number} previous - Requests made so far.
       * @returns {number} The next request number.
       */
      (previous: number): number => previous + 1,
    );
  }

  /**
   * Collects the document a succeeded run produced and hands it to the browser to save.
   *
   * ⚠️ Assumptions: the coordinates are recomputed from the status HERE rather than taken from the
   * render that painted the control, so a status that changed between the paint and the press cannot
   * produce a request for a document that is no longer there. The guard is also what makes the control's
   * absence and its behaviour rest on one predicate instead of two that have to agree.
   *
   * Assumptions: nothing about the document is rendered on this screen. It is 133 columns of
   * fixed-width text whose amount bands carry COBOL edit masks that a golden-master comparison reads
   * byte for byte, so displaying it here would make this screen a second renderer of a parity artifact
   * -- which is the reason `ui/src/api/reporting.ts` hands back undecoded bytes in the first place.
   * @returns {void} Completion is represented by the screen's own state and the browser's saved file.
   */
  function collectDocument(): void {
    const coordinates = collectableCoordinates(execution);
    if (coordinates === null || collecting) {
      return;
    }

    setCollecting(true);
    setRunNotice(null);
    const requestedFor = trackedRun;

    /**
     * Reports whether this collection is still the one the screen is waiting for.
     *
     * Assumptions: BOTH conditions are required. The mount check stops a settlement writing state after
     * the screen has gone, and the run check stops a superseded settlement acting on the screen that
     * replaced it -- and only the second of the two can prevent a document being saved for a run the
     * operator has moved on from.
     * @returns {boolean} `true` when the settlement may act.
     */
    function stillWanted(): boolean {
      return mounted.current && followedRun.current === requestedFor;
    }

    collectReportArtifact(coordinates.reportType, coordinates.startDate, coordinates.endDate).then(
      /**
       * Hands the collected bytes to the browser.
       *
       * Assumptions: the save is guarded, because it is the one step here that touches the browser
       * directly. Minting an object URL, activating a transient anchor and releasing the handle are all
       * capabilities a hardened browser configuration can withhold, and a throw inside a settlement
       * would otherwise surface as an unhandled rejection with the control already un-spun -- a
       * download that silently did not happen, which is the one outcome worse than a reported failure.
       * @param {Blob} bytes - The document exactly as the service wrote it.
       * @returns {void} Completion is represented by the browser's saved file.
       */
      (bytes: Blob): void => {
        if (!stillWanted()) {
          return;
        }
        setCollecting(false);
        try {
          saveReportDocument(bytes, coordinates);
        } catch (cause: unknown) {
          console.warn('carddemo: a report document could not be handed to the browser', cause);
          setRunNotice(REPORT_RUN_MESSAGES.DOCUMENT_COLLECTION_FAILED);
        }
      },
      /**
       * Reports a collection that did not answer, without disclosing why.
       *
       * ⚠️ Assumptions: a NOT-FOUND answer is reported as an absent document rather than as a failed
       * request, and the two are genuinely different for the operator. `collectReportArtifact` documents
       * 404 as the answer both for a run that never happened and for one whose document a lifecycle rule
       * has expired -- neither of which a second attempt or a status read will recover, so the sentence
       * that says to submit the report again is the only one that is true.
       *
       * ⚠️ Refactoring Rationale: the remaining answers are now SPLIT on whether repeating the collection
       * could plausibly succeed, where one sentence covered every one of them. `DOCUMENT_COLLECTION_FAILED`
       * ends "Refresh the status to retry", and the measured case that makes the split necessary is an
       * `AccessDenied` refusal: a permission the operator does not hold is not cleared by any number of
       * refreshes, so the sentence sent them round a loop the screen already knew was closed. This is the
       * same correction the status reader `reportUnreadableStatus` carries, applied to the other target-side
       * failure surface so the two do not disagree about what an unrecoverable failure is called.
       *
       * Assumptions: the status is read through the shared failure type rather than from a property of
       * the raw cause, so a rejection that is not one of this client's -- a browser error, a programming
       * error -- falls through to the persistent sentence instead of being read for a status it never
       * had. `isRepeatableFailure` narrows before it reads its member and answers false for anything it
       * cannot narrow, which is the right default: an unclassifiable failure is no evidence that
       * retrying will help.
       *
       * Assumptions: the not-found test comes FIRST and is not folded into the split. A 404 is a
       * conclusive answer about a document rather than a failure of the request, so it must not be
       * re-described as either a retryable or a reportable failure.
       * @param {unknown} cause - Whatever the collection rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (cause: unknown): void => {
        if (!stillWanted()) {
          return;
        }
        console.warn('carddemo: a report document could not be collected', cause);
        setCollecting(false);
        if (isApiRequestError(cause) && cause.status === DOCUMENT_ABSENT_STATUS) {
          setRunNotice(REPORT_RUN_MESSAGES.DOCUMENT_UNAVAILABLE);
          return;
        }
        setRunNotice(
          isRepeatableFailure(cause)
            ? REPORT_RUN_MESSAGES.DOCUMENT_COLLECTION_FAILED
            : PERSISTENT_FAILURE_REPORT_IT,
        );
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
   *
   * ⚠️ Refactoring Rationale: the turn now REPORTS whether it stopped to ask for the confirmation, and the
   * return value exists so the pointer affordance can be driven by the edit chain instead of by a click.
   * The confirmation balloon previously opened the moment its control was pressed, which put a dialogue
   * asking the operator to consent in front of a form whose required dates were still blank and whose
   * edits had not run -- the measured consequence being a balloon covering the `End Date :` caption and
   * its first two inputs while offering to submit them. The reference cannot reach that state: all three
   * arms of the outer `EVALUATE` run their edits first and only then `PERFORM SUBMIT-JOB-TO-INTRDR`
   * (`app/cbl/CORPT00C.cbl` L238, L255 and L435), and that paragraph's blank-answer test at L464-L472 is
   * the first thing in it. So asking is a RESULT of a turn here, exactly as it is there.
   * @param {string} answer - The confirmation character this turn is answering with.
   * @returns {boolean} `true` when the turn reached the confirmation and found it unanswered, which is
   *   the one outcome that leaves the operator with a question to answer; `false` for every other
   *   outcome, including a refusal earlier in the chain, a decline, a submission and a turn dropped
   *   because one is already in flight.
   */
  function runTurn(answer: string): boolean {
    if (inFlight.current) {
      return false;
    }

    /*
     * WHY : Assumptions: an unselected type is refused with the reference's own sentence and the cursor
     *       returns to the monthly selector -- L437-L442, the `WHEN OTHER` arm of the outer `EVALUATE`,
     *       which is reached when none of the three marks is set.
     */
    if (reportType === null) {
      reportRefusal(
        REPORT_MESSAGES.SELECT_A_REPORT_TYPE_TO_PRINT_REPORT,
        SELECTOR_REFUSAL_MARKS,
        'reportType',
      );
      return false;
    }

    const submittedRange = resolveRange(reportType);
    if (submittedRange === null) {
      return false;
    }

    const confirmation = evaluateConfirmation(answer, REPORT_TYPE_NAMES[reportType]);
    if (confirmation.outcome === 'UNANSWERED' || confirmation.outcome === 'UNRECOGNISED') {
      reportRefusal(
        confirmation.message,
        [{ field: 'confirm', message: confirmation.message }],
        'confirm',
      );
      /*
       * WHY : ⚠️ Assumptions: only the UNANSWERED outcome reports back as a question, and the
       *       UNRECOGNISED one deliberately does not, although both take this same refusal. A blank
       *       answer is the reference ASKING -- L464-L472 paints `Please confirm ...` and returns for the
       *       operator to answer. An unrecognised character is the reference REJECTING an answer already
       *       given -- L478-L493 names the character back and refuses it. Offering a consent affordance
       *       on the second would answer, on the operator's behalf, a question they have already answered
       *       wrongly, and would hide the fact that what they keyed was not accepted.
       */
      return confirmation.outcome === 'UNANSWERED';
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
      return false;
    }

    startReportRun(reportType, submittedRange, answer);
    return false;
  }

  /**
   * Submits the turn using the confirmation character currently in the field.
   *
   * Purpose: the action Enter is bound to, from the keyboard and from the row-24 legend control alike.
   *
   * ⚠️ Assumptions: this closes the consent balloon rather than opening it, and the asymmetry with
   * {@link submitFromPointer} is deliberate. A keyed turn is an answer in the field's own terms, so it
   * SUPERSEDES a question the pointer path had put on the screen; and a refused keyed turn moves the
   * cursor into the confirmation field, where a balloon floating beside it would be a second surface
   * competing for an operator who is already typing into the first.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function submitTurn(): void {
    runTurn(confirmAnswer);
    setConfirmationAsked(false);
  }

  /**
   * Submits the turn from the in-content control, offering consent only if the turn asks for it.
   *
   * ⚠️ Refactoring Rationale: this control previously ran a DIFFERENT operation from the Enter key and the
   * legend control beside it -- it opened a consent balloon whose accept action wrote the consenting
   * character and submitted, while Enter submitted whatever the field held. Two controls carrying the
   * same label `ENTER=Continue` in the same primary emphasis, measured 312 pixels apart at 1600, did not
   * do the same thing. They now run the identical turn through {@link runTurn} with the identical
   * argument, so the operation is one operation whichever surface reaches it.
   *
   * ⚠️ Trade-offs: what remains particular to this control is the balloon, and it is an AFFORDANCE on a
   * shared operation rather than a second operation. It is offered here and not on the keyboard path
   * because the reference's own confirmation surface is a keyed character, so a pointer operator has no
   * equivalent of it -- AAP section 0.3.2 maps the terminal's re-key-to-confirm convention onto
   * `Popconfirm` for exactly that reason -- whereas a keyboard operator already has the field the cursor
   * has just been placed in. The alternative of raising the balloon on both paths was rejected on
   * accessibility: {@link reportRefusal} focuses the confirmation input, so a balloon raised at the same
   * moment is an unfocused floating surface asking a question the focused control is already asking.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function submitFromPointer(): void {
    setConfirmationAsked(runTurn(confirmAnswer));
  }

  /**
   * Submits the turn as though the operator had keyed a consenting answer.
   *
   * Assumptions: the confirmation dialogue's accept action writes the consenting character into the field
   * before submitting, so both confirmation surfaces submit the SAME turn and the field always shows what
   * was submitted. The character path is retained alongside the dialogue because the dialogue can only
   * express consent and refusal, and two of the reference's four outcomes -- a blank answer and an
   * unrecognised one -- are reachable only by keying the field.
   *
   * Assumptions: the balloon is closed here rather than left to `Popconfirm`'s own dismissal, because its
   * open state is controlled by this screen -- the library will not close what it did not open.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function confirmAndSubmit(): void {
    setConfirmationAsked(false);
    setConfirmAnswer(CONSENTING_ANSWER);
    runTurn(CONSENTING_ANSWER);
  }

  /**
   * Dismisses the consent balloon, leaving every keyed value exactly as it was.
   *
   * ⚠️ Assumptions: dismissing is NOT the reference's declining answer, and conflating the two would lose
   * a distinction the reference draws. Keying `N` is an ANSWER: L480-L483 performs `INITIALIZE-ALL-FIELDS`
   * and clears the whole form silently, and that path stays reachable by keying the character the
   * confirmation field's own hint publishes. Dismissing a balloon withdraws the QUESTION, so the operator
   * returns to the range they keyed with nothing lost -- which is also the only reading under which the
   * control's stock `Cancel` label is honest about what pressing it does.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function dismissConfirmation(): void {
    setConfirmationAsked(false);
  }

  /**
   * Follows the balloon's own dismissals without letting a trigger press open it.
   *
   * ⚠️ Assumptions: an opening request is IGNORED and only a closing one is honoured, which is what makes
   * the gate hold. `Popconfirm` clones its child and adds a trigger handler, so a press asks to open at
   * the same moment {@link submitFromPointer} runs the turn -- and honouring that request would restore
   * the defect exactly, opening the balloon before the edits had run. Closing requests must still be
   * honoured, because they are how Escape and an outside click dismiss it, and dropping them would trap
   * the operator in a balloon with only its two buttons as an exit.
   * @param {boolean} next - Whether the library is asking to open or to close.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function followConfirmationDismissal(next: boolean): void {
    if (!next) {
      setConfirmationAsked(false);
    }
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
      /*
       * WHY : ⚠️ Refactoring Rationale: the filtered value is now BOUNDED to the part's declared width, and
       *       the omission it corrects was reachable. `maxLength` on the control stops a person TYPING past
       *       the width, but it does not bound a value delivered in one change -- a paste, a password
       *       manager, an autofill -- and the filter above then SALVAGES digits out of whatever arrived.
       *       Measured on the deployed build: `{{7*7}}` put `7777` into a two-character month, `1e5` became
       *       `15` and `0x10` became `010`. Each of those is a value the 3270 field could not have held,
       *       accepted silently and then edited as though the operator had keyed it.
       * WHY : Assumptions: this is the same bound {@link recordConfirmAnswer} already applies with
       *       `CONFIRM_WIDTH`, so the two keyable surfaces on this screen now agree; the confirmation field
       *       having it and the six date parts not having it was an inconsistency rather than a decision.
       * WHY : ⚠️ Trade-offs: truncation is chosen over rejecting the whole entry, which is the 3270
       *       behaviour being reproduced -- a field of width n accepts n characters and the rest never
       *       arrives, rather than the field refusing what was sent. Rejecting outright would also erase a
       *       partially valid entry the operator could correct, and would make a paste of a correct date
       *       into the wrong box clear the box instead of filling it.
       * WHY : Assumptions: the truncation is applied AFTER the character filter and not before, so a
       *       payload whose leading characters are punctuation cannot consume the width and leave the
       *       digits behind it discarded -- which would make the salvage worse rather than better.
       */
      const accepted = event.target.value
        .replace(NON_NUMERIC_FIELD_CHARACTERS, '')
        .slice(0, DATE_PART_WIDTHS[part]);
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
    ENTER: {
      onInvoke: submitTurn,
      label: REPORTS_KEY_LABELS.ENTER,
      /*
       * WHY : Assumptions: `'mutating'` for the whole session, and unlike the bill-payment screen's Enter
       *       this one needs no per-turn derivation, because this map has ONE Enter arm and it writes.
       *       `app/cbl/CORPT00C.cbl` L184-L195 sends `DFHENTER` to `PROCESS-ENTER-KEY`, and all three of
       *       that paragraph's report-type arms -- monthly at L213-L237, yearly at L238-L256 and custom
       *       from L257 -- end in `PERFORM SUBMIT-JOB-TO-INTRDR`. Every other path out of the paragraph
       *       is a refusal that writes nothing, and a refusal is what the key does when it CANNOT do what
       *       the operator asked, not what it is for.
       * WHY : Alternatives Considered: `'destructive'`, on the grounds that submitting a report spends
       *       service capacity. Rejected: the risk vocabulary reserves that level for deleting a record
       *       or moving money, and `ui/src/layout/PfKeyBar.tsx` wraps a destructive control in
       *       `destructiveFocusTheme` -- a treatment that would put the strongest warning in the
       *       application on an action whose worst outcome is a report nobody reads. Submitting a run is
       *       a write, and `'mutating'` is what a write is.
       */
      risk: 'mutating',
      /*
       * WHY : Refactoring Rationale: this entry reports `busy`, and the hook-level `enabled: !busy` that
       *       used to sit below was withdrawn to let it. Those two cannot coexist:
       *       `ui/src/layout/usePfKeys.ts` returns from `invokePfKey` and from its keydown listener on
       *       `options.enabled === false` BEFORE it looks at the handler at all, so with the global gate
       *       in place a per-entry busy flag is unreachable. The gate also greyed the Enter control for
       *       the duration of a submission, which took it out of the tab order at the one moment a
       *       keyboard operator is most likely to be pressing keys, and told them the key does not work
       *       when the truth is that they were early. A busy control stays present, focusable and named
       *       and declines the press in silence, which is what the terminal's input inhibit did.
       * WHY : Assumptions: the flag is the synchronous ref rather than the `busy` render state, and it is
       *       passed as a PREDICATE so it is read at dispatch. `submitTurn` latches on the same ref for
       *       the same reason: a state update is batched, so two Enter presses in one batch would both
       *       observe the old value and two runs would be submitted for one instruction.
       */
      busy:
        /**
         * Answers whether a turn this key started is still outstanding.
         *
         * Purpose: give the legend control its busy affordance for exactly the window in which a second
         * press would submit a second run, and release it the moment the turn settles.
         * @returns {boolean} True while a turn started from this key has not yet settled.
         */
        (): boolean => inFlight.current,
    },
    PFK03: {
      onInvoke: returnToMenu,
      label: REPORTS_KEY_LABELS.PFK03,
      /*
       * WHY : Assumptions: `F3=Back` is `'read-only'` because the caption names a navigation and
       *       `app/cbl/CORPT00C.cbl` L196-L204 performs exactly that -- an `XCTL` to the main menu with
       *       no file access on the way. It keeps the DISABLED channel rather than reporting busy,
       *       because it does not own the outstanding turn: the busy channel says "the key you pressed is
       *       running", which would be a false statement about a key that is merely being withheld while
       *       a submission it has nothing to do with completes.
       * WHY : Assumptions: withholding it at all is deliberate and is what `enabled: !busy` used to do
       *       for it. A 3270 accepted no attention identifier between sending the map and receiving the
       *       reply, so the reference could not have taken PF3 mid-turn even in principle; leaving it
       *       live would let an operator leave the screen while the submission that created the run they
       *       would have monitored was still in flight.
       */
      risk: 'read-only',
      disabled: busy,
    },
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
    /*
     * WHY : Refactoring Rationale: this sink now returns without painting while a submission is in
     *       flight, and for anything other than an UNMAPPED identifier. Both guards replace what the
     *       withdrawn `enabled: !busy` option used to do for free: with the global gate in place the hook
     *       never reached this sink during a turn, so no sentence was painted for any key pressed inside
     *       the busy window. Withdrawing the gate -- which had to happen for Enter to report `busy` at
     *       all -- would otherwise have made a mid-submission F9 paint `'Invalid key pressed.'`, and a
     *       mid-submission F3 paint it too, on a screen that painted nothing for either before.
     * WHY : Assumptions: silence is the reference's behaviour and not a softening of it. A 3270 inhibited
     *       the keyboard for the duration of a task, so a key pressed inside the window never reached the
     *       program and its `WHEN OTHER` arm at `app/cbl/CORPT00C.cbl` L191-L193 never ran. The arm still
     *       runs for a key pressed when the screen is idle, which is the only state in which the
     *       reference could have run it.
     * WHY : Assumptions: the `'disabled'` reason is filtered rather than special-cased, because on this
     *       map it can arise from exactly one cause -- PF3 withheld for a submission -- and that is the
     *       same inhibited-keyboard event. Filtering by reason keeps the guard true if a later turn
     *       withholds a key for some other reason: an unavailable key is not an unrecognised one.
     */
    if (inFlight.current || rejection.reason !== 'unmapped') {
      return;
    }

    reportRefusal(rejection.message, [], 'reportType');
  }

  const { bindings, invoke } = usePfKeys(pfKeyHandlers, {
    onInvalidKey: reportInvalidKey,
    restoreFocusRef: reportTypeRef,
  });

  useShellSlot({
    screen: { transactionId: REPORTS_TRANSACTION_ID, programName: REPORTS_PROGRAM_NAME },
    ...(paintedAt === undefined ? {} : { now: paintedAt }),
    /*
     * WHY : Assumptions: the `message` slot carries NO `information` member, and the omission is
     *       measured rather than incidental. The frame's information channel reproduces the second
     *       message line some mapsets declare at row 22 -- `INFOMSG` -- and `CORPT00` declares none:
     *       `grep -n "POS=(2[0-4]" app/bms/CORPT00.bms` returns exactly two fields, `ERRMSG` at
     *       `POS=(23,1)` with `LENGTH=78` and the legend at `POS=(24,1)`, and `grep -n INFOMSG` returns
     *       nothing. This screen has one message line and reserves no row for a second.
     * WHY : Alternatives Considered: publishing `information: { text: null }` on every turn to reserve
     *       the row, which is what a screen whose mapset DOES declare row 22 must do so the row cannot
     *       appear and disappear under the operator. Rejected here because it would hold open a channel
     *       this map never spends, pushing the row-24 legend down one line on every turn relative to the
     *       terminal. The screen's own execution panel carries the run-lifecycle notices instead, and
     *       that panel is a target-side addition with no row on this map at all.
     */
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

  /*
   * WHY : ⚠️ Refactoring Rationale: the two bound captions are given the mapset's own twelve-column cell
   *       and told to KEEP their spaces, which is the whole of the fix for the two rows starting at
   *       different x positions. See {@link BOUND_CAPTION_WIDTH} for the two mapset declarations and the
   *       measured six-to-seven-pixel offset. `whiteSpace: 'pre'` is the load-bearing half: without it the
   *       end caption's two leading spaces collapse and the cell holds a ten-character string in a
   *       twelve-character box, left-aligned, which puts the colon back where it was.
   * WHY : Assumptions: `flexShrink: 0` is set because the caption sits in a flex line with six inputs, two
   *       slashes, a hint and a calendar control. A flex item's default is to shrink below its content
   *       measure when the line is over-full, and a caption that shrank would take the row's whole
   *       alignment with it at exactly the narrow widths the alignment is hardest to read at. The line
   *       already wraps, so the caption keeping its measure costs a wrap and not an overflow.
   * WHY : Trade-offs: this is the caption CELL and not the caption text, so at a narrow width the row
   *       wraps beneath a twelve-column caption rather than the caption truncating. Truncating was the
   *       alternative and it loses the operator's only identification of which bound they are keying;
   *       wrapping costs vertical space, which the screen has.
   */
  const boundCaptionStyle: CSSProperties = {
    ...captionStyle,
    inlineSize: `${String(BOUND_CAPTION_WIDTH)}ch`,
    flexShrink: 0,
    whiteSpace: 'pre',
  };

  /*
   * WHY : ⚠️ Refactoring Rationale: the six date parts and the confirmation field are sized from the
   *       character widths their own symbolic map declares, through the shared helper. A review measured
   *       all seven rendering 201 pixels wide at every viewport -- `.ant-input` is `width: 100%` and
   *       nothing bounded it -- so a two-character month and a one-character confirmation were each as
   *       wide as a fifty-character address line. Two consequences were scored separately: the row needed
   *       about 1009 pixels and fragmented to four lines at 375 with both slashes orphaned at line ends,
   *       and the widget hierarchy INVERTED, each single date part at 201 pixels standing beside a
   *       calendar control capturing a whole date at 171.
   * WHY : ⚠️ Assumptions: the styles are spread onto the antd control itself and not onto the `Form.Item`
   *       wrapping it, which is a measured constraint rather than a preference. The helper's maximum adds
   *       the design system's own `controlPaddingHorizontal`, and that custom property resolves in the
   *       control's class scope -- measured, `getPropertyValue('--ant-control-padding-horizontal')` is
   *       empty on `document.documentElement` and `12px` on an `.ant-input`. Spread onto a wrapper the
   *       `calc()` would be invalid at computed-value time and the maximum would be dropped.
   * WHY : Trade-offs: the helper returns a CEILING and keeps `inlineSize: '100%'` beneath it, so a part
   *       still shrinks rather than forcing the page to scroll sideways at a phone width. That is design
   *       gap G1's trade applied to size, which `ui/src/layout/recordLayout.ts` records.
   */
  const datePartStyles: Readonly<Record<DatePart, CSSProperties>> = {
    month: { ...fixedPitchStyle, ...copybookFieldWidthStyle(DATE_PART_WIDTHS.month, cssVar) },
    day: { ...fixedPitchStyle, ...copybookFieldWidthStyle(DATE_PART_WIDTHS.day, cssVar) },
    year: { ...fixedPitchStyle, ...copybookFieldWidthStyle(DATE_PART_WIDTHS.year, cssVar) },
  };
  const confirmFieldStyle = copybookFieldWidthStyle(CONFIRM_WIDTH, cssVar);

  /*
   * WHY : Assumptions: both are DERIVED from the last status read rather than stored beside it, so
   *       the sentence and the control can never describe a status the screen is not showing. The
   *       alternative -- setting them in the settlement that read the status -- would put two more
   *       values in state that have to be kept in step with a third, and every path that forgot one
   *       would offer a download for a run whose document had gone.
   */
  const outcomeDetail = executionOutcomeDetail(execution);
  const collectable = collectableCoordinates(execution);

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

  /*
   * WHY : Assumptions: the selector group's identifier and its refusal are read ONCE, here, and every
   *       consumer below reads these two values rather than recomposing the identifier or querying the
   *       refusal again. That is what makes the association below sound rather than coincidental: the
   *       element carrying the sentence, the `aria-invalid` state and the `aria-describedby` that names
   *       that element are all derived from the same two values, so they cannot disagree about whether a
   *       refusal exists or about which identifier carries it. The six date parts and the confirmation
   *       already read theirs this way -- see `renderDatePart` -- and this brings the group's own wiring
   *       under the same shape rather than inventing a second one.
   */
  const reportTypeControlId = `${idPrefix}reportType`;
  const reportTypeRefusal = refusalFor('reportType');

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
          style={datePartStyles[part]}
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
     * WHY : Assumptions: the calendar shows a value only when all three parts together name a real date
     *       AND the control can carry that date's year unchanged. A partially keyed bound has no date to
     *       show; a bound whose year falls below one hundred has one the control would display as a
     *       different year, and {@link calendarValueFor} records the measurement and the two rejected
     *       alternatives behind leaving the box empty for it. What the operator reads here is therefore
     *       either the date the six fields hold or nothing at all, never a third value.
     */
    const composed = normaliseBound(parts);
    const picked = calendarValueFor(composed);

    return (
      <Flex key={bound} align="flex-start" gap="small" wrap>
        <Typography.Text style={boundCaptionStyle}>{caption}</Typography.Text>
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
   *
   * ⚠️ Trade-offs: a clock-derived option is withheld until an instant is anchored, so an operator who
   * reaches this screen before any response has been observed sees monthly and yearly unavailable and the
   * custom range -- which needs no clock -- available throughout. That is a narrower loss than the
   * alternative: a preset resolved from the browser's clock produces a report whose range depends on whose
   * machine submitted it, silently, which is the determinism the two ranges are resolved on this side to
   * protect. The consequence of the withholding is that in that window the initial cursor cannot land on
   * the monthly selector, because a disabled control is not focusable -- the same consequence the busy
   * window already carries.
   *
   * Assumptions: on the normal path an anchor exists before this screen can be reached, so the withholding
   * is invisible. `ui/src/main.tsx` awaits the runtime-configuration fetch before the first render and
   * `ui/src/api/runtimeConfig.ts` anchors from that response's `Date` header ahead of any status check,
   * `ui/src/api/client.ts` re-anchors from every later response INCLUDING failures, and this screen is
   * reachable only from the main menu -- so the sign-on exchange that got the operator there has already
   * anchored the clock. What is left for the guard is the narrow case: no response at all was observed,
   * which is the fetch rejecting outright, or a response whose `Date` header was stripped in transit.
   *
   * ⚠️ Assumptions: `busy` is re-tested HERE even though the group already carries it, because antd resolves
   * a member's disabled state as `radioProps.disabled ?? groupContext.disabled` (`antd/es/radio/radio.js`).
   * That is a nullish fallback and not a disjunction, so an option passing `false` would OVERRIDE the
   * group's `true` and stay operable during a submission -- which is exactly the doubled-turn the busy
   * window exists to prevent.
   * @param {ReportType} option - Report type to render.
   * @returns {ReactElement} The radio control carrying that type's caption.
   */
  function renderReportTypeOption(option: ReportType): ReactElement {
    const unanchoredPreset = paintedAt === undefined && CLOCK_DERIVED_REPORT_TYPES.includes(option);

    return (
      <Radio
        key={option}
        value={option}
        autoFocus={option === REPORT_TYPES[0]}
        disabled={busy || unanchoredPreset}
      >
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
       * WHY : Purpose: this states IN WORDS that a turn is running, which nothing else on the screen does.
       *       The spinner below is a visual affordance with no text, and the controls it covers report only
       *       their disabled state; `REQUEST_IN_PROGRESS` reads "Working on your request. Wait for the
       *       screen to answer." and tells the operator what to do about it.
       * WHY : Assumptions: the region is mounted on EVERY turn and holds the empty string when idle, which
       *       is `busyAnnouncement`'s own contract and is load-bearing rather than tidy. A `role="status"`
       *       element inserted at the moment it acquires text is frequently not announced at all, because
       *       the assistive reader has no live region to observe until the text is already there; one
       *       present from the first render and changed in place is announced. It is visually hidden, so an
       *       always-mounted region costs nothing an operator can see.
       * WHY : Alternatives Considered: publishing the sentence onto the row-23 message line. Rejected
       *       because that line is a parity surface under rule T8 -- every sentence on it is transcribed
       *       from `app/cbl/CORPT00C.cbl` -- and on the submitting turn it holds the reference's own
       *       'Please confirm to print the ... report...'. Overwriting a transcribed sentence with an
       *       authored one would lose the prompt the operator is answering.
       */}
      {busyAnnouncement(busy ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * Assumptions: the busy affordance wraps the form rather than replacing it, so the fields an operator
       * just filled stay visible while the run is being started. The reference has no such state at all --
       * a 3270 keyboard simply locks -- so a spinner over the unchanged form is the closest available
       * analogue, and it is paired with a disabled submit so a second turn cannot begin.
       *
       * WHY : ⚠️ Refactoring Rationale: `WRAPPED_BUSY_REGION_PROPS` is spread onto the spinner, and the
       *       pairing with the announcement above is one decision rather than two. antd's `Spin` makes the
       *       element it wraps a live region of its own at the pinned version, so with the authored
       *       sentence added and nothing done here the whole form would announce alongside it -- every
       *       label, every keyed value and every hint, read out because a submission started. Suppressing
       *       the wrapper's own live behaviour leaves exactly one region announcing exactly one sentence.
       * WHY : Assumptions: suppressing it loses nothing, because what the wrapper would have announced is
       *       the form's static text and not a statement about the turn. The turn is announced by the
       *       region above, in words the operator can act on, and the controls keep their own disabled and
       *       loading states for the sighted operator.
       */}
      <Spin spinning={busy} {...WRAPPED_BUSY_REGION_PROPS}>
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
            {...(reportTypeRefusal === undefined
              ? {}
              : {
                  validateStatus: 'error' as const,
                  help: fieldErrorHelp(reportTypeControlId, reportTypeRefusal.message),
                })}
          >
            {/*
             * WHY : ⚠️ Refactoring Rationale: the identifier, the invalid state, the description and the
             *       focus reference all sit on `Radio.Group` itself, where a wrapping `div` used to hold
             *       the reference and the group carried only a name. antd renders this component as the
             *       element bearing `role="radiogroup"`, forwards its `ref` to that element, passes `id`
             *       straight through and spreads every `aria-*` prop onto it
             *       (`antd/es/radio/group.js`) -- so the members land on the element that carries the
             *       group role, which is the only element assistive technology resolves a description
             *       against. On the wrapper they would have described a generic container holding the
             *       group, and the operator would have heard the group and not the reason it was refused.
             *       Removing the wrapper follows from the same fact rather than being a separate change:
             *       the reference now reaches the same node it always meant, so the intermediate element
             *       had nothing left to hold.
             * WHY : Assumptions: the group's accessible name is an `aria-label` carrying the row-4 heading,
             *       and no `htmlFor` accompanies it. The mapset paints NO caption over the three selectors
             *       -- the nearest painted text above them is that heading at `POS=(4,30)` -- so the name
             *       is taken from the one string the source does paint there rather than invented, which
             *       rule T8 would forbid. `htmlFor` is inapplicable rather than omitted: a `for` attribute
             *       may only reference a labelable element, and the element carrying the group role is a
             *       `div`.
             */}
            <Radio.Group
              id={reportTypeControlId}
              ref={reportTypeRef}
              value={reportType}
              onChange={handleReportTypeChange}
              disabled={busy}
              aria-label={REPORTS_TITLE}
              {...fieldAriaProps(reportTypeControlId, {
                invalid: reportTypeRefusal !== undefined,
                hasError: reportTypeRefusal !== undefined,
                hasHint: false,
              })}
            >
              <Flex vertical gap="middle">
                {REPORT_TYPES.map(renderReportTypeOption)}
              </Flex>
            </Radio.Group>
          </Form.Item>

          {/*
           * WHY : ⚠️ Refactoring Rationale: the two bounds are now rendered for EVERY report type, and the
           *       conditional mount they replace is what a review measured as the in-content submit control
           *       moving about 180 pixels vertically each time `Custom (Date Range)` was selected or
           *       cleared. The map is the authority and it settles the question outright: `app/bms/
           *       CORPT00.bms` declares all six parts unconditionally on rows 13 and 14 -- `SDTMM` L127,
           *       `SDTDD` L138, `SDTYYYY` L149, `EDTMM` L167, `EDTDD` L178, `EDTYYYY` L189 -- so the
           *       terminal painted them for a monthly report exactly as for a custom one, and the
           *       confirmation stayed on row 19 whichever type was marked. Unmounting them was a target-side
           *       addition, and the shift was its cost.
           * WHY : ⚠️ Assumptions: they stay ENTERABLE rather than becoming disabled, which is the same
           *       authority carried one step further. All six are `ATTRB=(FSET,NORM,NUM,UNPROT)` on every
           *       painting of the map, and `app/cbl/CORPT00C.cbl` reads them only inside the custom arm --
           *       the monthly arm at L214-L237 and the yearly arm at L239-L254 derive their own bounds and
           *       never reference `SDTMMI` or `EDTMMI` at all. So a range keyed against a preset was
           *       ignored there and is ignored here.
           * WHY : ⚠️ Alternatives Considered: disabling them for a preset. It reads better -- it says the
           *       fields do not apply -- but a disabled control has to say WHY, which this delivery already
           *       treats as a defect when it is missing, and the sentence explaining it does not exist in
           *       `ui/src/messages/messages.ts`. Authoring one is a catalogue change outside this screen, so
           *       taking that path here would have shipped six unexplained dead controls to remove one
           *       layout shift. Reserving the block's height while leaving it unmounted was also considered
           *       and rejected: it holds about 180 pixels of empty space open for two of the three report
           *       types, which trades a shift for a permanent void.
           * WHY : Trade-offs: `resolveRange` is what makes the enterable-but-ignored state safe rather than
           *       merely faithful -- it returns the empty range for `monthly` and `yearly` without reading
           *       the six fields, so a stale keyed range cannot reach the transport under a preset, and the
           *       custom edits still run in full when custom is the marked type.
           */}
          <Flex vertical gap="small">
            {DATE_BOUNDS.map(renderDateBound)}
          </Flex>

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
                style={{ ...fixedPitchStyle, ...confirmFieldStyle }}
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
           * Refactoring Rationale: ⚠️ both controls now dispatch the SAME turn. This one ran the dialogue's
           * accept path directly and the legend's ran the keyed field, so two controls of one label and one
           * emphasis performed two operations; `submitFromPointer` and `submitTurn` both call `runTurn`
           * with the field's own value, and the dialogue has become a consequence of that turn.
           */}
          <Flex gap="small" wrap>
            {/*
             * Assumptions: `open` is CONTROLLED and the trigger is therefore inert on its own -- the turn
             * decides whether there is a question to ask, which is what stops a consent dialogue standing
             * over a form whose required dates are still blank.
             * Assumptions: `placement` is below the control and not the library's default of above it.
             * The control sits beneath the whole form, so the default opened the balloon UPWARD across the
             * `End Date :` caption and its first two inputs -- the row the operator had just keyed and
             * would need to re-read to answer the question. Opening downward puts it in the space beneath
             * the form, which no field occupies.
             * Assumptions: ⚠️ `destroyOnHidden` is set because the library otherwise keeps a dismissed
             * balloon MOUNTED and merely hides it with a class. Measured: after a dismissal its accept
             * control was still queryable in the document. A hidden control that assistive software can
             * still reach is the pattern this delivery already treats as a defect elsewhere, and here it
             * would be an accept control for a submission the operator has just withdrawn from.
             */}
            <Popconfirm
              title={REPORTS_CAPTIONS.confirmation.trim()}
              description={REPORTS_CAPTIONS.confirmDomainHint}
              /*
               * WHY : ⚠️ Refactoring Rationale: the accepting control is labelled with the reference's own
               *       consenting CHARACTER rather than the design system's stock `OK`. This screen asks
               *       its question in the mapset's language -- the balloon's title is the 59-character
               *       prompt at `app/bms/CORPT00.bms` L200-L205 and its description is that map's own
               *       `'(Y/N)'` hint at L213-L217 -- so a control captioned `OK` answered a `(Y/N)`
               *       question with a word that is in neither the map nor the program. The affirmative
               *       arm is `WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'` at `app/cbl/CORPT00C.cbl` L478, and
               *       {@link confirmAndSubmit} writes exactly that character into the one-position field
               *       before submitting -- so the caption now names the value the control supplies.
               * WHY : Assumptions: the caption reads {@link CONSENTING_ANSWER}, the screen's OWN
               *       consenting character, rather than the shared `CONFIRMATION_ANSWERS.CONFIRM` that
               *       `ui/src/screens/transactionAdd/index.tsx` L3455 labels its consent surface from.
               *       Both hold `'Y'` and that sibling establishes the vocabulary as the application's
               *       rather than this screen's invention; reading the local constant is what makes the
               *       caption and the character this balloon actually writes impossible to separate,
               *       since {@link confirmAndSubmit} writes that same constant.
               * WHY : ⚠️ Assumptions: the DISMISSING control keeps its stock caption and is deliberately
               *       NOT labelled `N`. Keying `N` is an ANSWER: `app/cbl/CORPT00C.cbl` L480-L483
               *       performs `INITIALIZE-ALL-FIELDS`, which clears all ten inputs. Dismissing this
               *       balloon withdraws the QUESTION and leaves every keyed value in place -- the
               *       behaviour {@link dismissConfirmation} implements and a browser pass measured -- so a
               *       caption of `N` would promise the reference's clearing action and not perform it.
               *       Escape resolves to the same withdrawal and cannot be an answer at all, which is the
               *       second reason the two must not share a label. The declining answer stays reachable
               *       where the reference put it: the one-position field above, whose hint publishes it.
               * WHY : Alternatives Considered: labelling the accept control `Continue`, the verb the
               *       row-24 legend uses at `app/bms/CORPT00.bms` L222-L226 (`ENTER=Continue`) and the
               *       word the affirmative arm's `CONTINUE` statement carries. Rejected because that
               *       legend caption names the KEY's action on the screen as a whole, and this control
               *       does something narrower and more specific -- it supplies one character to one field
               *       -- so naming it after the value keeps the balloon and the field describing the same
               *       act.
               */
              okText={CONSENTING_ANSWER}
              open={confirmationAsked}
              onOpenChange={followConfirmationDismissal}
              onConfirm={confirmAndSubmit}
              onCancel={dismissConfirmation}
              placement="bottom"
              destroyOnHidden
              disabled={busy}
            >
              <Button type="primary" onClick={submitFromPointer} disabled={busy}>
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
       * Refactoring Rationale: ⚠️ this region rendered the run's name and nothing else, so a submitted
       * report could be started and then neither followed nor obtained -- the two operations that answer
       * both questions were published by `ui/src/api/reporting.ts` and called from nowhere in the
       * application. It now reports the run's state, explains a terminal one, and offers the document a
       * succeeded run produced.
       * Assumptions: the document itself is still not rendered. It is 133 columns of fixed-width text
       * whose amount bands carry COBOL edit masks that a golden-master comparison reads byte for byte,
       * so it is handed to the browser as bytes and never decoded here.
       * Refactoring Rationale: ⚠️ the region is scoped to the RUN and no longer to the turn that started
       * it. It previously unmounted on any refusal, because both {@link reportRefusal} and
       * {@link initialiseAllFields} cleared the submission -- so a mistyped date on the next request
       * abandoned a run that was still executing, and the execution name it abandoned is the only key the
       * status endpoint accepts. Neither clears it now, and the region therefore survives a refusal, a
       * declined confirmation and a PF4 clear, ending only when a new submission replaces it.
       * Assumptions: `docs/runbooks/batch-operations.md` remains the route for an operator who has lost a
       * handle some other way, such as leaving the screen; it is no longer reachable by mistyping a field.
       * Assumptions: every sentence and label here is AUTHORED and comes from
       * `REPORT_RUN_MESSAGES`, because the baseline has no run state to transcribe. None of it is
       * painted on row 23: that line carries this program's own nineteen sentences, and the screen
       * already declines to put non-baseline text there for the reason recorded at
       * {@link mapSubmissionFailure}.
       */}
      {submission === null ? null : (
        <Flex vertical gap="small">
          <Typography.Text strong style={captionStyle}>
            {REPORT_RUN_MESSAGES.HEADING}
          </Typography.Text>
          <Flex align="baseline" gap="small" wrap>
            <Typography.Text style={captionStyle}>
              {REPORT_RUN_MESSAGES.REFERENCE_CAPTION}
            </Typography.Text>
            <Typography.Text style={fixedPitchStyle}>{submission.executionName}</Typography.Text>
          </Flex>
          {/*
           * Assumptions: the three lines below are one polite live region, because they change without
           * the operator doing anything -- a run moves from running to completed on the service's own
           * schedule. `polite` rather than `assertive` for the reason the message band applies the same
           * choice to row 23: an operator reading the form should not be interrupted mid-field by a
           * status the screen will still be showing a moment later.
           * Assumptions: the status line is rendered only once a status has been READ. Before the first
           * read there is nothing to report, and defaulting the label to `Running` would state as fact
           * something no answer has yet said -- the control's own busy affordance is what shows that a
           * read is outstanding.
           */}
          <Flex vertical gap="small" aria-live="polite">
            {execution === null ? null : (
              <Flex align="baseline" gap="small" wrap>
                <Typography.Text style={captionStyle}>
                  {REPORT_RUN_MESSAGES.STATUS_CAPTION}
                </Typography.Text>
                <Typography.Text style={neutralStyle}>
                  {REPORT_RUN_MESSAGES.STATUS_LABELS[execution.status]}
                </Typography.Text>
              </Flex>
            )}
            {outcomeDetail === null ? null : (
              <Typography.Text style={neutralStyle}>{outcomeDetail}</Typography.Text>
            )}
            {runNotice === null ? null : (
              <Typography.Text style={neutralStyle}>{runNotice}</Typography.Text>
            )}
          </Flex>
          {/*
           * Assumptions: these two are body controls and NOT function keys, which is the same decision
           * the key handlers above record. `app/bms/CORPT00.bms` L222-L226 paints a legend advertising
           * exactly two keys and `app/cbl/CORPT00C.cbl` L184-L195 dispatches exactly those two, so a
           * third binding would advertise a key the reference neither paints nor honours. Both actions
           * are additive to a screen the baseline had no lifecycle for, so they belong where the
           * lifecycle is rendered.
           * Assumptions: the collect control is MOUNTED only when there is something to collect, rather
           * than rendered disabled. A disabled download beside a completed run reads as a document the
           * operator is not allowed to have; its absence beside the sentence explaining why is the
           * honest rendering of a run with nothing stored.
           */}
          <Flex gap="small" wrap>
            <Button onClick={refreshRunStatus} loading={statusReadPending} disabled={busy}>
              {REPORT_RUN_MESSAGES.REFRESH_CONTROL}
            </Button>
            {collectable === null ? null : (
              <Button type="primary" onClick={collectDocument} loading={collecting} disabled={busy}>
                {REPORT_RUN_MESSAGES.DOWNLOAD_CONTROL}
              </Button>
            )}
          </Flex>
        </Flex>
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
