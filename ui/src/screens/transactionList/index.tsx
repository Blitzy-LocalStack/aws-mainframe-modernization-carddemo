/**
 * @file Paged transaction browse at `/transactions`, carrying `app/bms/COTRN00.bms` map `COTRN0A`
 * and the behaviour of `app/cbl/COTRN00C.cbl`.
 *
 * What this module renders
 * -----------------------
 * One page of at most ten transactions, an optional starting-identifier filter, a single-select
 * column that opens one transaction, the ordinal of the page on display, and the mapset's own
 * selection prompt. The title band, the row-23 message line and the row-24 key legend are
 * delegated to the shell rather than composed here.
 *
 * Trade-offs: the source paints 89 `DFHMDF` fields at absolute character positions on a fixed
 * 24x80 grid -- 59 named and 30 anonymous literals -- and this module reproduces none of that
 * positioning. It is design gap G1, taken deliberately: a browser cannot make character-cell
 * positioning responsive, and pinning 89 fields to columns would fight the assistive-technology
 * reading order rather than serve it. What IS preserved is everything the positioning encoded --
 * the field grouping, the reading order, the tab order, the declared widths of the data columns and
 * the column order the mapset paints. Two consequences of the gap are visible and are recorded at
 * the render sites that would otherwise look incomplete: row 9's five dashed rules (`'---'` through
 * `'------------'`) are not rendered, because `Table` draws that rule itself and painting the
 * dashes would put a second one on screen as literal text; and the blank filler rows that
 * `INITIALIZE-TRAN-DATA` leaves behind on a short final page (`COTRN00C.cbl` L450 onward blanks all
 * ten row families before repopulating) are not rendered either, because `Table` renders exactly
 * the rows the service delivered.
 *
 * Assumptions: the browse position is a keyset cursor in the source already, so this is a
 * one-to-one mapping and not an approximation. `CDEMO-CT00-TRNID-FIRST` is set from row 1's
 * `TRAN-ID` at L393 and `CDEMO-CT00-TRNID-LAST` from row 10's at L439; a backward step reads from
 * the first (L236-L239) and a forward step from the last (L259-L262). Those two fields are
 * literally `PageResponse.firstKey` and `PageResponse.lastKey`, which is why no page index is ever
 * sent to the service.
 *
 * Assumptions: this screen decides every sentence it shows. `usePagedQuery` emits no user-visible
 * text and coerces no numbers by design, so all eight of the source's messages, both painted prompts
 * and every formatting decision below are this screen's concern rather than the hook's. Deciding is
 * not spelling: every one of those strings is resolved from `ui/src/messages/messages.ts` under
 * transformation rule T8, and none of them is written as a literal in this module.
 */

import { Flex, Form, Input, Radio, Space, Table, Typography, theme } from 'antd';
import type { RadioChangeEvent, TableColumnsType } from 'antd';
import { useEffect, useReducer, useState } from 'react';
import type { CSSProperties, ChangeEvent, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { listTransactions } from '../../api/transactions';
import type { ApiError, PageResponse, TransactionSummary } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import type { PagedQueryRequest } from '../../hooks/usePagedQuery';
/*
 * Refactoring Rationale: the painted text of map `COTRN0A` is IMPORTED from the catalog, where this
 * module used to transcribe it beside the controls that name it. The two spellings were byte-equal on
 * the day the second was written and nothing kept them so, and the catalog is the one module a
 * reviewer checks character by character against `app/bms/COTRN00.bms` -- which is what
 * transformation rule T8 asks of user-visible text. Nothing about the values changes in the move; in
 * particular the interior padding of the five row-8 headings stays part of each string, because each
 * is a `DFHMDF INITIAL=` operand filling a declared cell width and that padding is how the mapset
 * centres a heading over its column.
 * Assumptions: the catalog's member keys are the ones this screen already used, so the declarations
 * move and no use site below does.
 * Alternatives Considered: keeping the local groups and asserting them equal to the catalog's in a
 * test. Rejected because two copies that agree are still two copies to correct, and such a test
 * reports that a pair disagrees without saying which side is right.
 */
import {
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  TRANSACTION_LIST_COLUMN_HEADERS,
  TRANSACTION_LIST_KEY_LABELS as CATALOG_KEY_LABELS,
  TRANSACTION_LIST_LABELS,
} from '../../messages/messages';
/*
 * Refactoring Rationale: the router transition goes through the shared helper rather than through
 * `navigate` directly, and the two are not interchangeable. `navigate` returns a promise under the
 * data router, so calling it bare leaves a floating rejection -- and a rejected transition strands
 * the operator on the screen they tried to leave. `navigateSafely` answers that rejection by
 * completing the move as a full document navigation, which is behaviour every other screen in this
 * tree already gets. Alternatives Considered: discarding the promise with `void`, and re-declaring
 * the catch-and-fall-back locally. The first silently drops the failure the helper exists to handle;
 * the second forks a sibling contract, so the fallback would then live in two places that can drift.
 * `MAIN_MENU_ROUTE` comes from the same module for the same reason -- this screen's back key and the
 * route table must name one value, not two copies of it.
 * Refactoring Rationale: `TRANSACTION_LIST_ROUTE` is imported for two jobs that have to agree on one
 * value. It is the ORIGIN this screen hands the transaction detail screen, which
 * `inApplicationRoute` admits only because `ui/src/routes/navigation.ts` declares it; and it is the
 * parent of every transaction's own address, which {@link transactionDetailPath} builds. A local
 * literal stood here for the second job while the routing module carried no browse route, and it
 * would now be a copy that cannot fail loudly: an origin that drifted out of the admitted set is not
 * an error, it is silently discarded, and the operator is returned to the menu instead of to the list
 * they came from.
 */
import { MAIN_MENU_ROUTE, TRANSACTION_LIST_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_TEXT_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/**
 * The `var(--...)` reference form of the design tokens, as antd's theme hook publishes it.
 *
 * Assumptions: derived from the hook rather than written out, so the builders below take exactly
 * what `theme.useToken()` yields and no separate declaration can drift from it.
 */
type AntdCssVariables = ReturnType<typeof theme.useToken>['cssVar'];

/** Transaction identifier this screen answers to, `WS-TRANID` at `COTRN00C.cbl` L37. */
export const TRANSACTION_LIST_TRANSACTION_ID = 'CT00';

/** Baseline program this screen carries across, `WS-PGMNAME` at `COTRN00C.cbl` L36. */
export const TRANSACTION_LIST_PROGRAM_NAME = 'COTRN00C';

/**
 * Mapset whose message-field width the delegated band is sized to.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` holds `COTRN00` at a display width of 78, which is the
 * width this screen's own `ERRMSG` field declares (`LENGTH=78` at `COTRN00.bms` row 23) while the
 * band still enforces the 75-character `CCARD-ERROR-MSG` work area every mapset shares. Passing
 * the mapset key is what reconciles the two widths, so neither `MessageBand` nor the shared
 * contract has to be altered to accommodate 78.
 */
export const TRANSACTION_LIST_MAPSET = 'COTRN00';

/**
 * How many rows the page holds.
 *
 * Assumptions: ten, stated three independent ways in the source and never inferred. The mapset
 * declares ten row families `SEL0001` through `SEL0010` at rows 10 to 19; the fill loop runs
 * `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10` at `COTRN00C.cbl` L290; and the read loop
 * runs `PERFORM UNTIL WS-IDX >= 11` at L296. The value is required by `usePagedQuery` rather than
 * defaulted there precisely because the five browses in this tree disagree on it.
 */
export const TRANSACTION_LIST_PAGE_SIZE = 10;

/**
 * Declared width of the starting-identifier entry field.
 *
 * Assumptions: sixteen, from `TRNIDIN ... LENGTH=16` in `app/bms/COTRN00.bms` and `TRNIDINI PIC
 * X(16)` in `app/cpy-bms/COTRN00.CPY`. The terminal cannot accept a seventeenth character, so the
 * entry control must not either -- and the underlying key is `TRAN-ID PIC X(16)` in
 * `app/cpy/CVTRA05Y.cpy`, so a longer entry could not address a record in any case.
 */
export const TRANSACTION_ID_FILTER_WIDTH = 16;

/**
 * Declared width of the description column.
 *
 * Assumptions: twenty-six, from `TDESCnn ... LENGTH=26` and `TDESCnnI PIC X(26)`. The record's own
 * field is four times that -- `TRAN-DESC PIC X(100)` -- so the mapset itself is where the
 * truncation happens and 26 is the number this screen has to reproduce.
 */
export const TRANSACTION_DESCRIPTION_WIDTH = 26;

/**
 * Declared width of the transaction-identifier column.
 *
 * Assumptions: sixteen, from `TRNIDnn ... LENGTH=16`, matching `TRAN-ID PIC X(16)` exactly, so the
 * column never truncates what it shows.
 */
export const TRANSACTION_ID_COLUMN_WIDTH = 16;

/**
 * Declared width of the date column.
 *
 * Assumptions: eight, from `TDATEnn ... LENGTH=8`, which is exactly what `mm/dd/yy` occupies.
 */
export const TRANSACTION_DATE_COLUMN_WIDTH = 8;

/**
 * Declared width of the amount column.
 *
 * Assumptions: twelve, from `TAMT00n ... LENGTH=12`, which is exactly what the edit mask `PIC
 * +99999999.99` at `COTRN00C.cbl` L56 occupies -- one sign, eight integer digits, the point and two
 * decimal digits.
 */
export const TRANSACTION_AMOUNT_COLUMN_WIDTH = 12;

/**
 * Key legend, assembled from the two sources that own the four groups the row-24 literal paints.
 *
 * Assumptions: the source paints one 48-character literal, `'ENTER=Continue  F3=Back
 * F7=Backward  F8=Forward'`, with TWO spaces between groups, and `PfKeyBar` assembles the rendered
 * legend from per-key labels -- so the screen supplies the parts and never the joined sentence.
 *
 * Assumptions: the two paging parts come from `UNIFORM_PF_KEY_LABELS` and the other two from the
 * message catalog, and that split is the ownership boundary those two modules draw rather than an
 * inconsistency. `F7=Backward` and `F8=Forward` are painted identically on every mapset that pages,
 * so `ui/src/layout/PfKeyBar.tsx` owns them and the catalog deliberately carries neither;
 * `ENTER=Continue` and `F3=Back` are this mapset's own text, so the catalog carries both. Restating
 * either pair here would put a second spelling of a verbatim constant in the tree with nothing
 * keeping the two equal.
 */
export const TRANSACTION_LIST_KEY_LABELS = {
  /** Painted `ENTER=Continue`; submits the entry field and returns to the first page. */
  ENTER: CATALOG_KEY_LABELS.ENTER,
  /** Painted `F3=Back`; the source moves `'COMEN01C'` into the transfer target at L123. */
  PFK03: CATALOG_KEY_LABELS.PFK03,
  /** Painted `F7=Backward`, shared with every other paging mapset. */
  PFK07: UNIFORM_PF_KEY_LABELS.PFK07,
  /** Painted `F8=Forward`, shared with every other paging mapset. */
  PFK08: UNIFORM_PF_KEY_LABELS.PFK08,
} as const;

/**
 * Selection character the source acts on, and the only one it accepts.
 *
 * Assumptions: `PROCESS-ENTER-KEY` dispatches on two `WHEN` arms and no more -- `WHEN 'S'` at
 * `COTRN00C.cbl` L186 and `WHEN 's'` at L187 -- so both cases open the transaction and everything
 * else falls to `WHEN OTHER` at L198. Dropping the lower-case arm is the easiest fidelity loss on
 * this screen to make and the hardest to notice.
 */
export const VIEW_SELECTION_CODES = ['S', 's'] as const;

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** Matches an exact decimal amount, optionally signed, as the wire contract publishes it. */
const EXACT_DECIMAL = /^([+-]?)([0-9]+)(?:\.([0-9]*))?$/u;

/** Matches the leading `YYYY-MM-DD` of the twenty-six-character origination stamp. */
const ISO_DATE_HEAD = /^[0-9]{4}-[0-9]{2}-[0-9]{2}/u;

/**
 * Integer digit capacity of the amount edit mask, `PIC +99999999.99` at `COTRN00C.cbl` L56.
 *
 * Assumptions: eight, and the record's own field carries NINE -- `TRAN-AMT PIC S9(09)V99` in
 * `app/cpy/CVTRA05Y.cpy`. The mask is therefore narrower than the value it edits, which is a defect
 * in the immutable baseline rather than a rule to reproduce; {@link formatTransactionAmount} records
 * which side of it this screen takes.
 */
const AMOUNT_MASK_INTEGER_DIGITS = 8;

/** Decimal digit capacity of the amount edit mask, the `.99` of `PIC +99999999.99`. */
const AMOUNT_MASK_FRACTION_DIGITS = 2;

/** HTTP status the service answers when a position or filter addresses no record at all. */
const NOT_FOUND_STATUS = 404;

/**
 * Date the source shows when it has no usable origination stamp to reformat.
 *
 * Assumptions: this is not invented. `WS-TRAN-DATE PIC X(08) VALUE '00/00/00'` at `COTRN00C.cbl`
 * L57 declares it, and the program moves the reformatted stamp OVER that value, so a row whose
 * stamp cannot be reformatted shows exactly these eight characters.
 */
export const UNRESOLVED_TRANSACTION_DATE = '00/00/00';

/**
 * Which read produced the page on display, from which an empty result takes its sentence.
 *
 * Assumptions: the source distinguishes three arrivals and gives each its own sentence, so one
 * "no rows" message cannot serve them: `STARTBR` answering `NOTFND` is `'You are at the top of the
 * page...'` at L608, `READNEXT` answering `ENDFILE` during a forward step is `'You have reached the
 * bottom of the page...'` at L642, and `READPREV` answering `ENDFILE` during a backward step is
 * `'You have reached the top of the page...'` at L676. This is the discriminator that keeps the
 * three apart.
 */
export type PageReadIntent = 'open' | 'forward' | 'backward';

/**
 * Renders one amount at the source's twelve-character edit mask.
 *
 * Purpose: reproduces `MOVE TRAN-AMT TO WS-TRAN-AMT` where `WS-TRAN-AMT PIC +99999999.99`
 * (`COTRN00C.cbl` L56, L383) -- an always-signed, zero-padded, fixed-point rendering such as
 * `+00000123.45` or `-00000123.45`.
 *
 * Trade-offs: the value is handled as TEXT from end to end and is never converted to a number.
 * JavaScript's only numeric type is an IEEE-754 binary64 double, which cannot represent most
 * scale-two decimal fractions exactly, so a single conversion here would render a different cent
 * from the one the service computed -- and it would do so as a plausible figure rather than as an
 * error, which is the worst failure available on a screen showing money. That is also why
 * `ui/src/api/types.ts` declares `TransactionSummary.amount` as a string, under transformation rule
 * T3. What is given up is the convenience of arithmetic, which this function never needs: padding
 * and sign selection are string operations.
 *
 * Assumptions: the eight-digit mask is narrower than the nine-digit `TRAN-AMT S9(09)V99` it edits,
 * so the baseline silently drops the high-order digit of any amount above 99,999,999.99 -- a
 * hundred-million transaction renders as `+00000000.00`. This function reproduces the mask for every
 * value that FITS it, which is every value the parity fixtures carry, and for a value that does not
 * fit it emits the full digits instead of truncating them. Alternatives Considered: reproducing the
 * truncation exactly, for byte-parity with the terminal. Rejected because the two failures are not
 * comparable: an amount one character wider than its column is visibly unusual, whereas a truncated
 * amount is a materially WRONG figure that looks entirely normal. This is a documented divergence of
 * the same class as the three the migration already registers, not an accident.
 * @param {string} amount - The exact decimal amount as the service published it, optionally signed
 *   and optionally carrying a fractional part.
 * @returns {string} The amount at the mask when it fits the mask, the amount with its full integer
 *   digits when it does not, or the trimmed input unchanged when it is not an exact decimal at all.
 */
export function formatTransactionAmount(amount: string): string {
  const trimmed = amount.trim();
  const parsed = EXACT_DECIMAL.exec(trimmed);

  // Assumptions: an unparseable value is passed through rather than replaced by a zero or an
  //   error marker. The contract says this member is exact decimal text, so reaching here means
  //   the contract was broken upstream; showing what actually arrived is what lets that be
  //   diagnosed, whereas substituting `+00000000.00` would present a broken payload as a real
  //   balance of zero.
  if (parsed === null) {
    return trimmed;
  }

  const [, sign = '', integerDigits = '', fractionDigits = ''] = parsed;
  const significantDigits = integerDigits.replace(/^0+/u, '');

  // Assumptions: a fraction shorter than two digits is padded and a longer one is cut rather than
  //   rounded. The wire contract is already scale two, so a third digit means the payload broke the
  //   contract; rounding it here would alter a figure the service computed and settled, and this
  //   screen is not the place that decides a cent.
  const fraction = `${fractionDigits}00`.slice(0, AMOUNT_MASK_FRACTION_DIGITS);

  // Assumptions: zero is rendered with a leading `+`, never `-`. COBOL's `+` edit character emits
  //   the sign OF THE VALUE, and zero is unsigned there, so a payload of `-0.00` still paints
  //   `+00000000.00` on the terminal.
  const isZero = significantDigits === '' && fraction === '00';
  const renderedSign = sign === '-' && !isZero ? '-' : '+';
  const integer =
    significantDigits.length > AMOUNT_MASK_INTEGER_DIGITS
      ? significantDigits
      : significantDigits.padStart(AMOUNT_MASK_INTEGER_DIGITS, '0');

  return `${renderedSign}${integer}.${fraction}`;
}

/**
 * Renders one origination stamp as the eight-character date the source shows.
 *
 * Purpose: reproduces `COTRN00C.cbl` L384 to L388, which moves `TRAN-ORIG-TS` into `WS-TIMESTAMP`,
 * takes the last two digits of the year with `WS-TIMESTAMP-DT-YYYY(3:2)`, takes the month and day
 * whole, and composes them through `WS-CURDATE-MM-DD-YY` -- a group declared in
 * `app/cpy/CSDAT01Y.cpy` L30 to L35 as month, a `'/'` filler, day, a second `'/'` filler and year.
 *
 * Assumptions: the ORIGINATION stamp, not the processing one. `TRAN-RECORD` carries both as
 * `PIC X(26)` (`app/cpy/CVTRA05Y.cpy` L16 and L17) and this column is fed from `TRAN-ORIG-TS`
 * alone. Using the processing stamp would render a date for most rows and a blank for any row not
 * yet posted, which is a different column with the same heading.
 *
 * Assumptions: the offsets come from the `WS-TIMESTAMP` group in `app/cpy/CSDAT01Y.cpy` L43 to L56,
 * which fixes the layout as four year digits, `'-'`, two month digits, `'-'`, two day digits and
 * then the time -- so the month sits at index 5, the day at 8 and the year's last two digits at 2.
 * They are read by slicing rather than by splitting on the separator, because the group's separators
 * are fixed fillers rather than a delimiter that may repeat.
 * @param {string} originTimestamp - The transaction's twenty-six-character origination stamp.
 * @returns {string} The date as `mm/dd/yy`, or {@link UNRESOLVED_TRANSACTION_DATE} when the stamp
 *   does not open with a well-formed `YYYY-MM-DD`.
 */
export function formatOriginationDate(originTimestamp: string): string {
  // Assumptions: the head is validated before it is sliced, because slicing never fails -- a short
  //   or reshaped stamp would yield a plausible-looking date composed of whatever characters
  //   happened to sit at those offsets. Falling back to the field's own declared value is what the
  //   terminal shows for a row it could not reformat, so the fallback is transcribed rather than
  //   chosen.
  if (!ISO_DATE_HEAD.test(originTimestamp)) {
    return UNRESOLVED_TRANSACTION_DATE;
  }

  const month = originTimestamp.slice(5, 7);
  const day = originTimestamp.slice(8, 10);
  const year = originTimestamp.slice(2, 4);

  return `${month}/${day}/${year}`;
}

/**
 * Cuts one description to the width the mapset paints.
 *
 * Purpose: reproduces `MOVE TRAN-DESC TO TDESCnnI` (`COTRN00C.cbl` L395 and its nine siblings),
 * where the source field is `TRAN-DESC PIC X(100)` and the target field is `TDESCnnI PIC X(26)`, so
 * COBOL's own move truncates on the right.
 *
 * Assumptions: 26 characters and no ellipsis. Alternatives Considered: an ellipsis to signal that
 * text was cut, and a wider column to avoid cutting at all. Both were rejected because they change
 * what the operator reads -- an ellipsis spends one of the 26 characters the mapset grants and a
 * wider column shows text the terminal never did, so a description whose first 26 characters
 * distinguish two transactions would distinguish them differently here than there. The full text
 * remains available on the transaction's own screen, which is where the source puts it too.
 * @param {string} description - The transaction description as the service published it.
 * @returns {string} The leading 26 characters, unchanged when the description is already shorter.
 */
export function truncateDescription(description: string): string {
  return description.slice(0, TRANSACTION_DESCRIPTION_WIDTH);
}

/**
 * Address of one transaction's own screen.
 *
 * Purpose: the target of `XCTL PROGRAM('COTRN01C')` at `COTRN00C.cbl` L188 to L194, which the
 * migration expresses as a client-side route change under transformation rule T5.
 *
 * Assumptions: the identifier is percent-encoded even though `TRAN-ID PIC X(16)` is a fixed-width
 * key, because nothing in the record's own declaration excludes a character that would end the path
 * segment early, and a filter the operator typed reaches this function unaltered.
 * @param {string} transactionId - The transaction's sixteen-character identifier.
 * @returns {string} The path of that transaction's screen, below `TRANSACTION_LIST_ROUTE`.
 */
export function transactionDetailPath(transactionId: string): string {
  return `${TRANSACTION_LIST_ROUTE}/${encodeURIComponent(transactionId)}`;
}

/**
 * Reports whether a selection character is one the source acts on.
 * @param {string | null} code - The character carried by the chosen row, or `null` when no row is
 *   chosen.
 * @returns {boolean} `true` for `'S'` and for `'s'`, and `false` for everything else including
 *   `null`.
 */
export function isViewSelectionCode(code: string | null): boolean {
  // Assumptions: widened to `readonly string[]` before the membership test because the constant is
  //   declared `as const`, so its own element type would narrow the argument to the two literals and
  //   reject exactly the third-character case this predicate exists to answer.
  const accepted: readonly string[] = VIEW_SELECTION_CODES;
  return code !== null && accepted.includes(code);
}

/**
 * The row the operator has chosen, held as the pair the source holds.
 *
 * Assumptions: the two members move together and are never set or cleared apart.
 * `PROCESS-ENTER-KEY` writes both from the same `WHEN` arm (`COTRN00C.cbl` L149 to L178), clears
 * both together in `WHEN OTHER` at L179 to L181, and then acts only when BOTH are non-blank at L183
 * to L184. Modelling them as one value is what makes that invariant unbreakable here.
 */
export interface RowSelection {
  /** Identifier of the chosen row, `CDEMO-CT00-TRN-SELECTED`, or `null` when none is chosen. */
  readonly transactionId: string | null;
  /** Selection character the chosen row carries, `CDEMO-CT00-TRN-SEL-FLG`, or `null` with none. */
  readonly code: string | null;
}

/** No row chosen, which is the state a freshly painted page is in. */
export const NO_ROW_SELECTED: RowSelection = { transactionId: null, code: null };

/** One change to the chosen row. */
export type RowSelectionAction =
  | {
      /** Records the row the operator chose. */
      readonly kind: 'choose';
      /** Identifier of the chosen row. */
      readonly transactionId: string;
      /** Selection character that row carries. */
      readonly code: string;
    }
  | {
      /** Returns to no row chosen, which is `WHEN OTHER` at `COTRN00C.cbl` L179 to L181. */
      readonly kind: 'clear';
    };

/**
 * Applies one change to the chosen row.
 *
 * Purpose: the target form of the ten-arm `EVALUATE TRUE` at `COTRN00C.cbl` L149 to L181, which
 * scans `SEL0001I` through `SEL0010I` in row order, takes the first that is neither spaces nor low
 * values, and copies that arm's character and its `TRNIDnn` together.
 *
 * Refactoring Rationale: the scan itself does not survive, and it should not. It exists because a
 * 3270 submits all ten selection fields at once, so the program has to decide which one the operator
 * meant -- and it decides by taking the first, which means at most ONE row is ever acted on per
 * submit. A `Radio` group cannot hold two chosen rows in the first place, so the browser enforces
 * structurally what the scan enforced procedurally, and reproducing the scan would be reproducing a
 * workaround for a constraint that no longer exists. Alternatives Considered: a `Checkbox` column,
 * which would let an operator mark several rows and then discover that only the topmost was
 * honoured -- permitting an interaction the source rejects.
 * @param {RowSelection} current - The chosen row before this change.
 * @param {RowSelectionAction} action - The change to apply.
 * @returns {RowSelection} The chosen row after the change; a fresh value, never the argument
 *   mutated.
 */
export function reduceRowSelection(
  current: RowSelection,
  action: RowSelectionAction,
): RowSelection {
  switch (action.kind) {
    case 'choose':
      // Assumptions: choosing REPLACES rather than adds, so the previous row is discarded without
      //   being consulted. Single select is the whole of the source's behaviour here, and it is why
      //   `current` is unread on this arm.
      return { transactionId: action.transactionId, code: action.code };
    case 'clear':
      return NO_ROW_SELECTED;
    default:
      // Assumptions: unreachable while the action union is exhaustive, and retained so that adding
      //   a member to that union is a compile error here instead of a silent fall-through -- the
      //   reducer would otherwise keep the previous selection for an action it does not understand.
      return current;
  }
}

/**
 * Chooses the sentence an empty page shows, from the read that produced it.
 *
 * Purpose: keeps the source's three "no rows" sentences apart. They are near-duplicates that look
 * like accidental drift, and they are not: each answers a different read, and an operator sees a
 * different one depending on which way they were travelling.
 *
 * Assumptions: the mapping is taken from the three handlers verbatim -- a forward read that yields
 * nothing is `READNEXT` answering `ENDFILE` at `COTRN00C.cbl` L638 to L644, a backward read that
 * yields nothing is `READPREV` answering `ENDFILE` at L672 to L678, and an opening read that yields
 * nothing is `STARTBR` answering `NOTFND` at L604 to L610. Note that the two "reached" sentences are
 * crossed relative to the direction a careless reading would assign: reading FORWARD off the end
 * reports the BOTTOM, reading BACKWARD off the start reports the TOP.
 * @param {PageReadIntent} intent - Which read produced the empty page.
 * @returns {string} That read's own sentence, verbatim from `ui/src/messages/messages.ts`.
 */
export function emptyBrowseMessage(intent: PageReadIntent): string {
  if (intent === 'forward') {
    return SHARED_MESSAGES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE;
  }
  if (intent === 'backward') {
    return SHARED_MESSAGES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE;
  }
  return SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE;
}

/** State of the browse from which a message is chosen. */
export interface BrowseMessageInput {
  /** Whether a read is outstanding. */
  readonly isLoading: boolean;
  /** Whether the most recent read ended in refusal or failure. */
  readonly isFailed: boolean;
  /** Problem document from the most recent failure, or `null` when there is none. */
  readonly error: ApiError | null;
  /** How many rows the page on display carries. */
  readonly itemCount: number;
  /** Which read produced the page on display. */
  readonly intent: PageReadIntent;
}

/**
 * Chooses the sentence the delegated message line shows for the state of the browse.
 *
 * Purpose: this screen performs the problem-document-to-sentence mapping itself, and that division
 * is deliberate. `MessageBand` takes a nullable string and a severity and accepts no `ApiError` at
 * all, so it stays purely presentational and the caller owns the decision about what a failure
 * MEANS -- which on this screen is not one decision but four, because the source answers a
 * positioning miss, a forward overrun, a backward overrun and a genuine access failure with four
 * different sentences.
 *
 * Assumptions: a 404 is the target's shape for `STARTBR` answering `NOTFND` -- the service reporting
 * that the position or filter addresses no record -- so it takes the same by-direction sentence an
 * empty page takes, and every other failure takes `'Unable to lookup transaction...'`, which the
 * source emits from all three of its own `WHEN OTHER` arms at L615, L649 and L683.
 *
 * Assumptions: the sentence is drawn from `PROGRAM_MESSAGES.COTRN00C` and NOT from
 * `SHARED_MESSAGES`, which carries a near-identical entry spelt `'Unable to lookup Transaction...'`
 * with a capital T. This program spells it lower-case at all three of its own sites; the two entries
 * exist separately in the catalog precisely because normalising either way would alter text a
 * golden-master comparison reads.
 *
 * Assumptions: nothing is said while a read is outstanding, because the source composes the whole
 * screen only after its read has finished -- there is no moment in it at which a message and a
 * pending read coexist.
 * @param {BrowseMessageInput} input - The state of the browse to choose a sentence for.
 * @returns {string | null} The sentence to show, or `null` when the state warrants none.
 */
export function resolveBrowseMessage(input: BrowseMessageInput): string | null {
  if (input.isLoading) {
    return null;
  }

  if (input.isFailed) {
    return input.error !== null && input.error.status === NOT_FOUND_STATUS
      ? emptyBrowseMessage(input.intent)
      : PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION;
  }

  if (input.itemCount === 0) {
    return emptyBrowseMessage(input.intent);
  }

  return null;
}

/**
 * Selection character a row's control supplies for the turn.
 *
 * Assumptions: the upper-case form is canonical because the source tests it first, at
 * `COTRN00C.cbl` L186, and adds the lower-case arm at L187 for an operator who typed it that way.
 * A browser control cannot type a character, so it supplies the canonical one and
 * {@link isViewSelectionCode} still accepts both -- which keeps the source's `WHEN OTHER` arm at
 * L198 reachable rather than dead.
 */
export const TRANSACTION_LIST_SELECTION_CODE = 'S';

/**
 * Builds the accessible name of one row's selection control.
 *
 * Assumptions: the control is named for the ACTION it performs and not for the character a 3270
 * operator would type into the selector. A control announced as "S 0000000123456789" would tell a
 * screen-reader user the letter to type into a field that does not exist in a browser while saying
 * nothing about what choosing the row does. This is additive -- the source has no assistive-
 * technology layer to transcribe -- so it is composed here rather than drawn from the message
 * catalog, which carries transcribed baseline text only.
 * @param {string} transactionId - The identifier that names the row.
 * @returns {string} The action-oriented accessible name for that row's control.
 */
export function selectionActionLabel(transactionId: string): string {
  return `Select transaction ${transactionId}`;
}

/**
 * Accessible name of the table, which the mapset has no field for.
 *
 * Assumptions: additive, for the same reason {@link selectionActionLabel} is. A 3270 announces
 * nothing, so there is no literal to transcribe; the name is composed from the row-4 sub-heading the
 * mapset does paint, so what a screen-reader user hears matches what a sighted operator reads.
 */
export const TRANSACTION_LIST_TABLE_LABEL = TRANSACTION_LIST_LABELS.title;

/**
 * Horizontal scroll policy for the table.
 *
 * Assumptions: `max-content` rather than a pixel width, because the sum of the five columns is a
 * property of their declared widths -- 3, 16, 8, 26 and 12 characters -- and stating a number here
 * would be a second copy of those widths that could drift from the column definitions carrying them.
 * Trade-offs: a narrow viewport scrolls the table rather than reflowing it into stacked cards. The
 * cards were considered and rejected because every column here is fixed-width by contract, so
 * reflowing them abandons the row-and-column reading order design gap G1 commits to preserving.
 */
export const TRANSACTION_LIST_TABLE_SCROLL = { x: 'max-content' } as const;

/**
 * Builds the five columns the mapset paints, in the order it paints them.
 *
 * Purpose: the target form of rows 8 to 19 of `app/bms/COTRN00.bms` -- one heading row and ten
 * identical row families of five fields each, which become five column definitions rendered once per
 * delivered row.
 *
 * Trade-offs: an antd `Table` replaces 89 absolutely positioned character fields, which is design gap
 * G1 accepted deliberately. Column order, heading text, declared widths, reading order and tab order
 * are all preserved; pixel-for-character positioning is not, because it cannot be made responsive
 * and it fights the assistive-technology reading order rather than serving it. Two visible
 * consequences follow and neither is an omission: row 9's five dashed rules are NOT rendered, because
 * `Table` draws that rule itself and the dashes would appear as a second rule in literal text; and
 * the blank filler rows `INITIALIZE-TRAN-DATA` leaves on a short final page (`COTRN00C.cbl` L450
 * onward) are NOT rendered, because a row that was not delivered has no definition here to render.
 *
 * Assumptions: the fixed-pitch token is applied to all four data columns rather than to the amount
 * alone, because every one of them carries a fixed-width coded value -- a sixteen-character
 * identifier, an eight-character date, a description cut to twenty-six and a twelve-character edited
 * amount. The token mapping assigns `fontFamilyCode` to "fixed-pitch money and identifier columns",
 * and a proportional font would let digits of differing widths break the column alignment the
 * terminal had.
 *
 * Assumptions: each column carries `minInlineSize` in `ch` units taken from the mapset's declared
 * `LENGTH`. That is a DATA contract rather than a design value, so it resolves to no design token and
 * needs none. `display: inline-block` accompanies it because `Typography.Text` renders a `span`, and
 * CSS applies neither a minimum inline size nor an alignment to a non-replaced inline box -- without
 * it both declarations are inert and every column collapses to the width of its content.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {TableColumnsType<TransactionSummary>} The five columns, ready for `Table`.
 */
export function buildTransactionListColumns(
  tokens: AntdCssVariables,
): TableColumnsType<TransactionSummary> {
  /**
   * Style shared by the data columns, in the colour and pitch the mapset declares for them.
   *
   * Assumptions: `COLOR=BLUE` on all four `TRNIDnn`, `TDATEnn`, `TDESCnn` and `TAMT00n` fields
   * resolves through the contrast-audited text mapping rather than through the raw seed colour, so
   * the value read here is the one measured against the container surface.
   *
   * Alternatives Considered: colouring the amount by sign, negatives in `colorError` against
   * positives in `colorSuccess`, which is what a reader of a modern ledger expects and what a
   * reviewer is most likely to ask for here. Rejected because the mapset gives `TAMT001` through
   * `TAMT010` an unconditional `COLOR=BLUE` with no second attribute arm for a negative value, and
   * `COTRN00C.cbl` never writes an attribute byte for an amount at all -- it moves the edited value
   * in and otherwise blanks the field -- so sign colouring would invent a visual state the source
   * does not define. Nothing is lost by declining it: the `PIC +99999999.99` mask emits an explicit
   * `+` or `-` on EVERY amount, so sign never rests on colour and survives a monochrome display,
   * a colour-blind reader and a screen reader alike.
   */
  const dataCell: CSSProperties = {
    color: tokens[BMS_TEXT_COLOR_TOKENS.BLUE],
    display: 'inline-block',
    fontFamily: tokens[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  return [
    {
      title: TRANSACTION_LIST_COLUMN_HEADERS.selection,
      key: 'selection',
      fixed: 'left',
      /**
       * Renders one row's selection control.
       *
       * Assumptions: a control exists only for a delivered row, which reproduces the source's own
       * protection without needing a guard. `INITIALIZE-TRAN-DATA` blanks all ten selector fields
       * before a page is built and `POPULATE-TRAN-DATA` fills a row only as it has data for it
       * (`COTRN00C.cbl` L290 to L292 and L381 onward), so an empty row's selector is never
       * actionable there either.
       * @param {TransactionSummary} row - The transaction the control acts on.
       * @returns {ReactElement} That row's selection control.
       */
      render: (row: TransactionSummary): ReactElement => (
        <Radio
          aria-label={selectionActionLabel(row.transactionId)}
          value={row.transactionId}
          // WHY : Assumptions: `autoFocus` appears on NO control on this screen, and its absence is
          //       fidelity rather than an oversight. `app/bms/COTRN00.bms` carries no `IC` attribute
          //       anywhere -- grepping the whole mapset for it matches only the Apache licence URL on
          //       line 11 -- so the terminal places the cursor nowhere in particular and this screen
          //       must not either. Stated here because a reviewer scanning for the initial-cursor
          //       control of a browse screen finds none and would otherwise read that as a bug.
        />
      ),
    },
    {
      title: TRANSACTION_LIST_COLUMN_HEADERS.transactionId,
      key: 'transactionId',
      fixed: 'left',
      /**
       * Renders the transaction's identifier, `TRAN-ID PIC X(16)`.
       * @param {TransactionSummary} row - The transaction being listed.
       * @returns {ReactElement} The identifier at its declared width.
       */
      render: (row: TransactionSummary): ReactElement => (
        <Typography.Text
          style={{ ...dataCell, minInlineSize: `${String(TRANSACTION_ID_COLUMN_WIDTH)}ch` }}
        >
          {row.transactionId}
        </Typography.Text>
      ),
    },
    {
      title: TRANSACTION_LIST_COLUMN_HEADERS.date,
      key: 'date',
      /**
       * Renders the origination date as the source's eight-character `mm/dd/yy`.
       * @param {TransactionSummary} row - The transaction being listed.
       * @returns {ReactElement} The reformatted origination date at its declared width.
       */
      render: (row: TransactionSummary): ReactElement => (
        <Typography.Text
          style={{ ...dataCell, minInlineSize: `${String(TRANSACTION_DATE_COLUMN_WIDTH)}ch` }}
        >
          {formatOriginationDate(row.originTimestamp)}
        </Typography.Text>
      ),
    },
    {
      title: TRANSACTION_LIST_COLUMN_HEADERS.description,
      key: 'description',
      /**
       * Renders the description cut to the twenty-six characters the mapset paints.
       * @param {TransactionSummary} row - The transaction being listed.
       * @returns {ReactElement} The truncated description at its declared width.
       */
      render: (row: TransactionSummary): ReactElement => (
        <Typography.Text
          style={{ ...dataCell, minInlineSize: `${String(TRANSACTION_DESCRIPTION_WIDTH)}ch` }}
        >
          {truncateDescription(row.description)}
        </Typography.Text>
      ),
    },
    {
      title: TRANSACTION_LIST_COLUMN_HEADERS.amount,
      key: 'amount',
      /**
       * Renders the amount at the source's twelve-character signed edit mask.
       *
       * Assumptions: right-aligned inside a fixed character width, which is how the mask's visible
       * effect is reproduced -- the mask itself is applied by
       * {@link formatTransactionAmount} and the geometry by these two declarations.
       * @param {TransactionSummary} row - The transaction being listed.
       * @returns {ReactElement} The edited amount, monospaced and right-aligned in its column.
       */
      render: (row: TransactionSummary): ReactElement => (
        <Typography.Text
          style={{
            ...dataCell,
            minInlineSize: `${String(TRANSACTION_AMOUNT_COLUMN_WIDTH)}ch`,
            textAlign: 'end',
          }}
        >
          {formatTransactionAmount(row.amount)}
        </Typography.Text>
      ),
    },
  ];
}

/**
 * Builds the reader one browse uses, closed over the starting identifier in force.
 *
 * Purpose: `usePagedQuery` deliberately imports no service client, so the operation a browse reads
 * through is injected. This factory supplies the transaction browse's own.
 *
 * Assumptions: the two members a request may carry are MUTUALLY EXCLUSIVE at the client, and this is
 * enforced rather than merely documented there. `browseQueryParameters` in
 * `ui/src/api/transactions.ts` throws a `RangeError` when a starting identifier and a cursor arrive
 * together, because a cursor already states the position to read from; and `keysetPagingMembers`
 * throws when a direction arrives with no cursor, because a direction relative to nothing addresses
 * no page. So an opening read sends the filter and NO direction, and a positioned read sends the
 * cursor and direction and NO filter. That matches the source exactly: `PROCESS-ENTER-KEY` positions
 * from the entry field at `COTRN00C.cbl` L206 to L212, while the paging paragraphs position from
 * `CDEMO-CT00-TRNID-FIRST` at L236 to L239 and `CDEMO-CT00-TRNID-LAST` at L259 to L262 and never
 * consult the entry field at all.
 *
 * Assumptions: a blank filter sends no criteria whatsoever, which is `MOVE LOW-VALUES TO TRAN-ID` at
 * L207 -- browse from the beginning of the set rather than from a key of blanks.
 * @param {string} transactionIdFilter - The starting identifier in force, or the empty string to
 *   browse from the beginning of the set.
 * @returns {(request: PagedQueryRequest) => Promise<PageResponse<TransactionSummary>>} A reader that
 *   answers one page for a position and a direction.
 */
export function createTransactionPageReader(
  transactionIdFilter: string,
): (request: PagedQueryRequest) => Promise<PageResponse<TransactionSummary>> {
  /**
   * Reads one page of transactions.
   * @param {PagedQueryRequest} request - The position to read from and the direction to read in.
   * @returns {Promise<PageResponse<TransactionSummary>>} One page of at most ten transactions, with
   *   the cursors addressing the pages either side of it.
   * @throws {RangeError} From the client, if a position ever reached it in a shape the browse
   *   contract refuses; the branches below exist so that cannot happen.
   * @throws {Error} An `ApiRequestError` from the shared client for every transport failure,
   *   carrying the normalised problem document this screen turns into a sentence.
   */
  function readTransactionPage(
    request: PagedQueryRequest,
  ): Promise<PageResponse<TransactionSummary>> {
    if (request.cursor === null) {
      return transactionIdFilter.length === 0
        ? listTransactions()
        : listTransactions({ transactionIdFilter });
    }

    return listTransactions({ cursor: request.cursor, direction: request.direction });
  }

  return readTransactionPage;
}

/** Element identity of the starting-identifier control, so its label can be associated with it. */
const FILTER_INPUT_ID = 'transaction-list-tran-id';

/** Element identity of the starting-identifier label. */
const FILTER_LABEL_ID = 'transaction-list-tran-id-label';

/** Element identity of the starting-identifier refusal text, referenced by the control. */
const FILTER_ERROR_ID = 'transaction-list-tran-id-error';

/** Element identity of the page-ordinal label. */
const PAGE_NUMBER_LABEL_ID = 'transaction-list-page-label';

/**
 * Reports whether a starting identifier is one the browse may be positioned by.
 *
 * Assumptions: exactly sixteen decimal digits, or nothing at all -- and the strict reading is
 * confirmed three independent ways rather than chosen. The source tests `IF TRNIDINI IS NUMERIC`
 * at `COTRN00C.cbl` L209, and COBOL's numeric class test on a `PIC X(16)` field is true only when
 * ALL sixteen positions hold digits, so a partly filled field fails it there too. The mapset
 * declares the field at `LENGTH=16` and the key it addresses is `TRAN-ID PIC X(16)`. And the
 * service's own contract declares the parameter as sixteen decimal digits with `minLength` and
 * `maxLength` both 16, so a shorter entry would be refused at the edge with a 400 against a field
 * the operator had been invited to fill.
 *
 * Alternatives Considered: accepting any run of digits as a partial start key, which reads as more
 * forgiving because the refusal sentence says only "must be Numeric". Rejected on the evidence
 * above: it would send a value the contract refuses, so the operator would receive a transport
 * failure in place of the field-level refusal the source gives them, and the browse would appear
 * broken rather than fussy.
 * @param {string} entry - The starting identifier as typed, already trimmed of surrounding blanks.
 * @returns {boolean} `true` when the entry is blank, or is exactly sixteen decimal digits.
 */
export function isStartingIdentifierAcceptable(entry: string): boolean {
  return (
    entry.length === 0 || (entry.length === TRANSACTION_ID_FILTER_WIDTH && DIGITS_ONLY.test(entry))
  );
}

/**
 * The `/transactions` browse screen.
 *
 * Purpose: carries `app/cbl/COTRN00C.cbl` across in full -- the optional starting-identifier entry,
 * the ten-row keyset page, the single-select column that opens one transaction, the four attention
 * identifiers the source handles, and all eight of the sentences it emits.
 *
 * Assumptions: the title band, the row-23 message line and the row-24 key legend are DELEGATED to
 * the shell through `useShellSlot` and are not composed here. Composing them locally would put a
 * second live region and a second key legend on screen, because the shell renders a zone if and only
 * if it has been delegated one.
 *
 * Assumptions: no redirect is performed for an unauthenticated caller even though the source
 * transfers to `COSGN00C` when `EIBCALEN = 0`. The router mounts every screen but sign-on behind an
 * authentication guard, so that decision is already made one level up and repeating it here would
 * give one screen a second, divergent copy of it.
 * @returns {ReactElement} The browse screen's body, with its three shell zones delegated.
 */
export default function TransactionListScreen(): ReactElement {
  const navigate = useNavigate();
  const { cssVar } = theme.useToken();

  /*
   * WHY : ⚠️ Assumptions: the paint instant is read from the shared hook and published to the shell,
   *       because `ScreenHeader` renders the browser's clock for any screen that omits it -- the
   *       fallback that module registers as divergence D-7 -- and this screen had omitted it. The
   *       source reads ONE clock for every terminal: `POPULATE-HEADER-INFO` at `COTRN00C.cbl` L567
   *       moves `FUNCTION CURRENT-DATE` at L569 into the header, and L529 performs it immediately
   *       before the `SEND MAP` that paints a page. That clock is the CICS region's own, so two
   *       operators reading one browse could not disagree about the date. A browser reading
   *       reinstates exactly that disagreement across a midnight boundary, and it does so silently --
   *       the band still paints a plausible date.
   * WHY : Assumptions: it is read during render rather than held in state, which is what makes the
   *       value a PAINT-time instant and not a mount-time one. The source re-read the clock on every
   *       send, so a value captured once when the browse opened would age visibly across the paging
   *       keys, which repaint the band without remounting the screen.
   * WHY : Alternatives Considered: letting the shell read the instant once for the whole frame, which
   *       would have needed no call here at all. `ui/src/layout/AppShell.tsx` records why it does not:
   *       a screen knows when it painted and the frame does not, and reading it there would re-render
   *       the frame on a clock change no mounted screen had asked for.
   */
  const paintedAt = useServerInstant();

  /*
   * Assumptions: the entry field is held as TWO values, not one. `draftFilter` is what the operator
   * has typed and `appliedFilter` is what the browse is actually positioned by, and they differ for
   * two reasons the source makes unavoidable. A keystroke must not re-issue a read, because the
   * source only ever positions on ENTER; and the entry field is BLANKED once a page paints -- `MOVE
   * SPACE TO TRNIDINO` at L228 and again at L325 -- while the position that produced that page has
   * to survive for the paging keys to step from. One value cannot be both cleared and retained.
   */
  const [draftFilter, setDraftFilter] = useState('');
  const [appliedFilter, setAppliedFilter] = useState('');
  const [selection, dispatchSelection] = useReducer(reduceRowSelection, NO_ROW_SELECTED);

  /*
   * Assumptions: a sentence this screen decides for itself is held apart from one derived from the
   * state of the browse, and it takes precedence for the turn that set it. The source works the same
   * way: `WS-MESSAGE` is written by whichever paragraph ran and is then sent once, so a refused key
   * press or a refused entry is what the operator reads even though the page beneath it is unchanged.
   */
  const [localMessage, setLocalMessage] = useState<string | null>(null);
  const [readIntent, setReadIntent] = useState<PageReadIntent>('open');

  const browse = usePagedQuery<TransactionSummary>({
    // WHY : Assumptions: ten, from the mapset's ten `SEL0001`-`SEL0010` row families at rows 10-19
    //       and from the two loop bounds at `COTRN00C.cbl` L290 and L296. The hook requires this
    //       rather than defaulting it because the five browses in this tree declare five different
    //       arities, so a default would silently render another screen's page size.
    pageSize: TRANSACTION_LIST_PAGE_SIZE,
    // WHY : Assumptions: composed fresh on every render, which the hook explicitly supports -- it
    //       holds the reader in a ref it refreshes each render, so the closure below always sees the
    //       current position without the paging steps being rebuilt.
    fetchPage: createTransactionPageReader(appliedFilter),
    // WHY : Assumptions: the applied position doubles as the restart value, so committing a
    //       different starting identifier reopens the browse at its first page and drops the previous
    //       criterion's rows. That is `MOVE 0 TO CDEMO-CT00-PAGE-NUM` at L224 expressed as a change
    //       of criteria rather than as an imperative step.
    resetKey: appliedFilter,
  });

  const entry = draftFilter.trim();
  const entryIsAcceptable = isStartingIdentifierAcceptable(entry);

  useEffect(
    /**
     * Blanks the entry field once a page has painted.
     *
     * Assumptions: this is `MOVE SPACE TO TRNIDINO` and it is transcribed, not invented. The source
     * clears the field at TWO sites and both are guarded: at L228 under `IF NOT ERR-FLG-ON` after
     * ENTER, and at L325 beside `MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI` in the paragraph that paints
     * a page -- which is inside `IF NOT ERR-FLG-ON` as well, and which a refused or empty read never
     * reaches because those arms send the screen earlier. So the condition is precisely a page that
     * arrived, carried rows and did not fail; the entry the operator typed is left standing whenever
     * a sentence is being shown, which is what lets them correct it.
     * @returns {void} Nothing; the field is cleared through its own state.
     */
    () => {
      if (browse.isLoading || browse.isFailed || browse.items.length === 0) {
        return;
      }
      setDraftFilter('');
    },
    [browse.isFailed, browse.isLoading, browse.items],
  );

  /**
   * Opens the chosen transaction, or refuses the choice, and then repositions at the first page.
   *
   * Assumptions: the order and the fall-through are the source's own. `PROCESS-ENTER-KEY` scans the
   * selectors first (L149 to L181) and acts on the pair only when BOTH members are non-blank (L183
   * to L184); a code of `'S'` or `'s'` transfers away to `COTRN01C` and nothing further runs, while
   * `WHEN OTHER` sets its sentence and DOES NOT send -- the `PERFORM SEND-TRNLST-SCREEN` on that arm
   * is commented out at L203 -- so execution falls through to the entry-field handling and the page
   * is repositioned with the refusal still standing. Reproducing that fall-through is what makes a
   * refused selection show its sentence over a refreshed first page rather than over a stale one.
   * @returns {void} Nothing; navigation or a repositioned browse follows.
   */
  function submitEntry(): void {
    let refusedSelection = false;

    if (selection.transactionId !== null && selection.code !== null) {
      if (isViewSelectionCode(selection.code)) {
        /*
         * WHY : Assumptions: the transition HANDS OVER this screen's own route as the origin, because
         *       the source hands over its own identity on exactly this arm: `COTRN00C.cbl` L190 and
         *       L191 move `WS-TRANID` into `CDEMO-FROM-TRANID` and `WS-PGMNAME` into
         *       `CDEMO-FROM-PROGRAM` immediately before the `XCTL` to `COTRN01C`, and the destination
         *       reads it back -- `COTRN01C.cbl` L115 to L122 returns to `CDEMO-FROM-PROGRAM` on PF3
         *       and falls back to `'COMEN01C'` only when it is blank. Sending no origin left the
         *       destination permanently on that fallback arm, so an operator who opened a transaction
         *       from this browse was returned to the main menu rather than to the page they were on.
         * WHY : Assumptions: the value is the routing module's own constant and not a literal, so it
         *       is a member of the closed set `inApplicationRoute` validates against. An origin that
         *       is not in that set is DISCARDED rather than reported, which is why a hand-written
         *       string here would reintroduce the same silent fallback while looking correct.
         * WHY : Trade-offs: nothing else travels -- not the applied filter, not the page ordinal, not
         *       the chosen row. The source carries no more than the origin and the selected
         *       identifier on this arm, and the identifier is already in the path, so a wider payload
         *       would be inventing state the reference does not hand over. The consequence is that
         *       returning here reopens the browse at its first page, which is what the reference does
         *       too: `COTRN00C` re-enters with `CDEMO-PGM-REENTER` clear and repositions from the
         *       entry field.
         * WHY : Trade-offs: `navigateSafely`'s documented full-navigation fallback drops `state`
         *       entirely, so on that path the destination's PF3 reverts to the main menu. That is
         *       accepted rather than worked around -- `ui/src/routes/navigation.ts` records the same
         *       trade for the message and pre-fill members -- because the alternative is encoding the
         *       origin into the URL, which is precisely what router state exists to avoid.
         */
        navigateSafely(navigate, transactionDetailPath(selection.transactionId), {
          from: TRANSACTION_LIST_ROUTE,
        });
        return;
      }
      refusedSelection = true;
    }

    // WHY : Assumptions: a refused entry issues NO request at all, which is why this returns before
    //       touching the browse. The source sets its error flag at L213 and every loop in
    //       `PROCESS-PAGE-FORWARD` is guarded by `ERR-FLG-OFF` (L288 and L296), so the browse is not
    //       repositioned and the page on display is left exactly as it was.
    if (!entryIsAcceptable) {
      setLocalMessage(PROGRAM_MESSAGES.COTRN00C.TRAN_ID_MUST_BE_NUMERIC);
      return;
    }

    setLocalMessage(refusedSelection ? SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S : null);
    setReadIntent('open');

    // WHY : Assumptions: the chosen row is released whenever a page is repositioned, because
    //       `INITIALIZE-TRAN-DATA` blanks all ten selector fields before a page is rebuilt
    //       (L290 to L292), so no selector survives a repaint on the terminal either.
    dispatchSelection({ kind: 'clear' });

    // WHY : Alternatives Considered: always assigning the applied position, which is one line
    //       shorter. Rejected because assigning the SAME value leaves the restart value unchanged, so
    //       the hook's restart effect would not run and pressing ENTER twice on an unchanged field
    //       would do nothing the second time -- where the source repositions on every ENTER without
    //       exception. Refreshing explicitly when the position has not moved, and letting the restart
    //       value carry it when it has, issues exactly one read either way.
    if (entry === appliedFilter) {
      browse.reset();
      return;
    }
    setAppliedFilter(entry);
  }

  /**
   * Reads the page before the one on display, or explains why it will not.
   *
   * Assumptions: the refusal is a SENTENCE and not a disabled control, matching the sibling browse
   * screens. `usePfKeys` answers a handler bound `disabled` by reporting a rejection that carries the
   * invalid-key message, so disabling this key on the first page would replace the source's own
   * `'You are already at the top of the page...'` at L248 with a sentence about an invalid key -- and
   * the source refuses the step without ever refusing the KEY. It also sends with `SEND-ERASE-NO` at
   * L249, which is a repaint of the same page with a sentence attached and not a re-read.
   * @returns {void} Nothing; a page arrives through the browse, or a sentence is shown.
   */
  function pageBackward(): void {
    if (!browse.hasPrev) {
      setLocalMessage(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE);
      return;
    }
    setLocalMessage(null);
    setReadIntent('backward');
    dispatchSelection({ kind: 'clear' });
    browse.prevPage();
  }

  /**
   * Reads the page after the one on display, or explains why it will not.
   *
   * Assumptions: gated on the envelope's own further-page flag, which the service sets by reading one
   * row beyond the page rather than by counting the rows in it -- exactly as the source sets
   * `NEXT-PAGE-YES` from an extra `READNEXT` at L305 to L312. At the boundary the source sends
   * `'You are already at the bottom of the page...'` at L270 with `SEND-ERASE-NO`, so this is a
   * sentence over the unchanged page and no request is issued.
   * @returns {void} Nothing; a page arrives through the browse, or a sentence is shown.
   */
  function pageForward(): void {
    if (!browse.hasNext) {
      setLocalMessage(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE);
      return;
    }
    setLocalMessage(null);
    setReadIntent('forward');
    dispatchSelection({ kind: 'clear' });
    browse.nextPage();
  }

  /**
   * Returns to the main menu, which is where the back key goes.
   *
   * Assumptions: the main menu, because L122 to L124 move `'COMEN01C'` into the transfer target
   * before performing `RETURN-TO-PREV-SCREEN`. Under transformation rule T5 a program transfer is a
   * client-side route change, so no state travels with it -- which is why no transition state is
   * supplied here even though the shared helper accepts one.
   * @returns {void} Nothing; the route changes.
   */
  function returnToMenu(): void {
    navigateSafely(navigate, MAIN_MENU_ROUTE);
  }

  const pfKeyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: submitEntry, label: TRANSACTION_LIST_KEY_LABELS.ENTER },
    PFK03: { onInvoke: returnToMenu, label: TRANSACTION_LIST_KEY_LABELS.PFK03 },
    PFK07: { onInvoke: pageBackward, label: TRANSACTION_LIST_KEY_LABELS.PFK07 },
    PFK08: { onInvoke: pageForward, label: TRANSACTION_LIST_KEY_LABELS.PFK08 },
  };

  // WHY : Assumptions: only these four attention identifiers are bound, because the source's
  //       `EVALUATE EIBAID` at L119 to L133 handles only ENTER, PF3, PF7 and PF8 and answers every
  //       other key with `CCDA-MSG-INVALID-KEY` from its `WHEN OTHER` arm. The refusal's TEXT is not
  //       restated here: `usePfKeys` owns it and hands it over on the rejection, and the hook also
  //       already aliases PF13-PF24 onto PF01-PF12 exactly as `app/cpy/CSSTRPFY.cpy` L66 to L78 does,
  //       so a second copy of either could only drift from the first.
  // WHY : ⚠️ Refactoring Rationale: the rejection is now SHOWN, where this screen registered no
  //       reporting callback at all. The note that stood here read "that refusal is NOT re-implemented
  //       here: `usePfKeys` already owns it", and the first half was true while the conclusion was
  //       wrong. The hook owns the message VALUE and reports it through this optional callback; it
  //       cannot paint anything, because it renders nothing. With no callback registered, the whole of
  //       `WHEN OTHER` at L129 to L132 was silently dropped: pressing PF5 on this browse moved the
  //       cursor nowhere, wrote no sentence and left the operator with no indication that the key had
  //       been read at all -- where the terminal answers with
  //       `'Invalid key pressed. Please see below...'` and repaints. This screen is one of the fourteen
  //       online programs that emit that message, so the omission was a parity defect and not a
  //       simplification.
  const pfKeys = usePfKeys(pfKeyHandlers, {
    /**
     * Paints the refusal for an attention identifier this screen does not handle.
     *
     * Assumptions: the sentence is taken from the rejection rather than imported, so the text stays
     * owned by `ui/src/layout/usePfKeys.ts` -- which resolves it from `CCDA-MSG-INVALID-KEY` in
     * `app/cpy/CSMSG01Y.cpy` L20 to L21 -- and this screen holds no second spelling of it.
     *
     * Assumptions: only an UNMAPPED identifier is reported. The hook raises the same rejection for a
     * binding that is present but disabled, and this screen disables none: its two paging keys stay
     * available at their boundaries and answer with the source's own boundary sentences instead, so a
     * `disabled` rejection here would mean the hook and this screen had come to disagree about which
     * keys exist. Reporting it as an invalid key would then hide that disagreement behind a plausible
     * message.
     * @param {PfKeyRejection} rejection - Which identifier was refused, why, and the sentence to show.
     * @returns {void} Nothing; the sentence is shown through this screen's own message state.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      if (rejection.reason === 'unmapped') {
        setLocalMessage(rejection.message);
      }
    },
  });

  const browseMessage = resolveBrowseMessage({
    isLoading: browse.isLoading,
    isFailed: browse.isFailed,
    error: browse.error,
    itemCount: browse.items.length,
    intent: readIntent,
  });
  const message = localMessage ?? browseMessage;

  /*
   * Assumptions: every sentence is shown at error severity, including the five that merely report a
   * boundary. The mapset has exactly ONE message field for all of them -- `ERRMSG` at row 23, painted
   * `COLOR=RED` -- so the terminal draws "you are already at the top of the page" in the same red as
   * a lookup failure. Grading them differently here would be a presentation decision the source does
   * not make, and it would make two screens disagree about what red means.
   */
  const messageSeverity: MessageBandSeverity = 'error';

  /*
   * WHY : ⚠️ Refactoring Rationale: the paint instant is delegated, where this screen delegated none.
   *       `app/bms/COTRN00.bms` declares `CURDATE` at L47 and `CURTIME` at L70, and `ScreenHeader`
   *       degrades to the BROWSER clock for a screen that hands it no instant -- so this one screen
   *       painted an operator-local date and time where the other nineteen paint the region's. The
   *       reference read one region clock for every terminal, which is what stops two operators looking
   *       at one record across midnight from reading two different dates.
   * WHY : Assumptions: the gap survived because the delegation contract was gated by a hand-written list
   *       of ten screens in `ui/src/layout/screenHeaderClock.test.tsx`, which this screen was not on.
   *       That gate now derives its population from the filesystem, so the class of omission is closed
   *       rather than this instance of it.
   */

  useShellSlot({
    screen: {
      transactionId: TRANSACTION_LIST_TRANSACTION_ID,
      programName: TRANSACTION_LIST_PROGRAM_NAME,
    },
    // WHY : Assumptions: the instant is published rather than left to the band's own fallback, which is
    //       the browser clock. The reasoning is at the read above; what is published here is the value
    //       the hook returned during THIS render, so the band shows the instant the page painted at
    //       whenever the client has an anchor to paint. The hook yields nothing until a response has
    //       carried one, and on that one unanchored paint the band's fallback still applies -- a
    //       publication cannot manufacture an instant the service has not yet stated. The slot member
    //       admits that absence explicitly, which is why the value is passed rather than guarded here.
    now: paintedAt,
    // WHY : Assumptions: the mapset key is passed so the band sizes itself to the 78 characters this
    //       screen's own `ERRMSG` field declares, while the band keeps enforcing the 75-character
    //       `CCARD-ERROR-MSG` work area every mapset shares. That is what reconciles the two widths
    //       without altering `MessageBand` or the shared contract to accommodate 78.
    message: { text: message, severity: messageSeverity, mapset: TRANSACTION_LIST_MAPSET },
    // WHY : Assumptions: `YELLOW` because row 24's legend literal is painted `COLOR=YELLOW`. The
    //       dispatcher is the hook's own, so a legend control and a keystroke reach the same handler
    //       and cannot come to disagree about what a key does.
    pfKeys: { keys: pfKeys.bindings, onInvoke: pfKeys.invoke, legendColor: 'YELLOW' },
  });

  return (
    <Flex vertical gap="large">
      {/*
       * WHY : ⚠️ Refactoring Rationale: the caption is rendered through `ScreenTitle`, where this
       *       screen used a bare `Typography.Title level={4}` -- the one screen CAPTION in this tree
       *       that still named its own rank. The note that stood here argued for the literal on the
       *       ground that `level={4}` matches every sibling screen's sub-heading and resolves the two
       *       measured tokens. Both halves were true and the conclusion was still wrong:
       *       `ui/src/layout/ScreenTitle.tsx` exists because `level` fuses the SEMANTIC rank with the
       *       VISUAL size and this application needs different answers for the two -- the shared band
       *       above every caption ranks third and is still sized at the fourth step -- so a screen that
       *       writes the rank itself agrees with the outline only until the outline moves, and then
       *       diverges silently, because a literal matching today's constant renders identically.
       *       Twenty screens take the rank from that module.
       * WHY : Assumptions: the two direct `Typography.Title` uses that remain in `ui/src/screens/**`
       *       are a different role: both are SECTION headings inside a screen, ranked at their own
       *       `SECTION_HEADING_LEVEL` deliberately below whatever rank a caption carries.
       * WHY : Assumptions: nothing about the rendering changes. `ScreenTitle` ranks a caption
       *       `SCREEN_TITLE_HEADING_LEVEL`, which is 4, and additionally names
       *       `TYPOGRAPHY_TOKENS.screenTitleSize` and `screenTitleLineHeight` -- `fontSizeHeading4`
       *       and `lineHeightHeading4`, the two tokens `level={4}` resolved on its own -- so the
       *       caption keeps its measured size and gains only the guarantee that it keeps it.
       * WHY : Assumptions: the colour and the zeroed margin stay with this screen and are passed
       *       through the component's `style` prop, which it spreads BEFORE its own size members so
       *       neither is displaced. `COLOR=NEUTRAL` on the row-4 literal is a per-mapset attribute
       *       and resolves to the de-emphasis TEXT token rather than to the base text colour, and
       *       `ATTRB=BRT` is carried as WEIGHT, which the heading already applies. The margin is
       *       zeroed because this caption is a flex item baseline-aligned against the page ordinal
       *       beside it, and a heading's default block margin would drop it off that baseline.
       */}
      <Flex align="baseline" gap="middle" justify="space-between" wrap>
        <ScreenTitle style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL], margin: 0 }}>
          {TRANSACTION_LIST_LABELS.title}
        </ScreenTitle>
        <Space size="small">
          <Typography.Text
            id={PAGE_NUMBER_LABEL_ID}
            strong
            style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] }}
          >
            {TRANSACTION_LIST_LABELS.pageLabel}
          </Typography.Text>
          {/*
           * WHY : Assumptions: the ordinal is DISPLAY-ONLY and is never sent anywhere. The source
           *       paints it into `PAGENUMI` at L324 while positioning strictly by the cursor pair, so
           *       treating it as an offset would invent a protocol neither side implements.
           * WHY : Alternatives Considered: a plain span carrying no role, which is what the terminal
           *       is closest to. A polite status region was chosen instead because the ordinal is the
           *       only visible confirmation that a paging key did anything, and on the terminal that
           *       confirmation arrives as a full repaint a screen reader would announce anyway. It
           *       also gives the value an addressable accessible name, so it can be asserted without
           *       a test handle.
           */}
          <Typography.Text
            aria-labelledby={PAGE_NUMBER_LABEL_ID}
            role="status"
            style={{
              color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE],
              fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
            }}
          >
            {String(browse.pageNumber)}
          </Typography.Text>
        </Space>
      </Flex>

      {/*
       * WHY : Alternatives Considered: `component={false}` renders no `form` element, which is what is
       *       wanted here -- this screen submits through the ENTER attention identifier rather than
       *       through a form submission, and a real `form` would give the browser a second, native
       *       submit path that bypasses the key dispatcher. Keeping the wrapper without the element
       *       preserves `Form.Item`'s label association and refusal rendering.
       */}
      <Form component={false}>
        {/*
         * WHY : Refactoring Rationale: the refusal text below is given the identity the control's
         *       `aria-describedby` points at, so ONE element carries it. An earlier shape rendered
         *       the sentence twice -- once as the visible help text and once in a hidden element for
         *       the description -- which put the same sentence into the accessibility tree twice and
         *       gave a screen-reader user a duplicate announcement. Passing a node rather than a bare
         *       string is what allows the identity to be attached at all.
         * WHY : Assumptions: that node carries no colour of its own. antd colours its explain region
         *       from `validateStatus`, and a colour here would override the token the design system
         *       already resolves for a field refusal.
         * WHY : Assumptions: the help and description members are attached by CONDITIONAL SPREAD
         *       rather than being passed as `undefined` when the entry is acceptable, because
         *       `exactOptionalPropertyTypes` is in force -- an optional member may be absent but may
         *       not be present holding `undefined`.
         */}
        <Form.Item
          colon={false}
          htmlFor={FILTER_INPUT_ID}
          {...(entryIsAcceptable
            ? {}
            : {
                help: (
                  <Typography.Text id={FILTER_ERROR_ID}>
                    {PROGRAM_MESSAGES.COTRN00C.TRAN_ID_MUST_BE_NUMERIC}
                  </Typography.Text>
                ),
              })}
          label={
            <Typography.Text
              id={FILTER_LABEL_ID}
              style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] }}
            >
              {TRANSACTION_LIST_LABELS.filterLabel}
            </Typography.Text>
          }
          validateStatus={entryIsAcceptable ? '' : 'error'}
        >
          {/*
           * WHY : Assumptions: `maxLength` is sixteen because `TRNIDIN` is declared `LENGTH=16` in
           *       `app/bms/COTRN00.bms` and `TRNIDINI PIC X(16)` in `app/cpy-bms/COTRN00.CPY`, and
           *       the key it addresses is `TRAN-ID PIC X(16)`. A terminal cannot accept a
           *       seventeenth character into that field, so this control must not either.
           * WHY : Assumptions: no `autoFocus`, here or anywhere else on this screen. The mapset
           *       carries no `IC` attribute at all -- the only match for it in the whole file is the
           *       Apache licence URL on line 11 -- so the terminal places the cursor nowhere in
           *       particular. This is one of four screens in the tree with no initial-cursor field,
           *       and stating it is what stops the absence reading as an oversight.
           */}
          <Input
            aria-describedby={entryIsAcceptable ? undefined : FILTER_ERROR_ID}
            aria-invalid={!entryIsAcceptable}
            aria-labelledby={FILTER_LABEL_ID}
            id={FILTER_INPUT_ID}
            inputMode="numeric"
            maxLength={TRANSACTION_ID_FILTER_WIDTH}
            onChange={
              /**
               * Records what the operator has typed, without repositioning the browse.
               *
               * Assumptions: a keystroke never issues a read, because the source positions only on
               * ENTER -- `PROCESS-ENTER-KEY` is the one paragraph that consults the entry field.
               * @param {ChangeEvent<HTMLInputElement>} event - The change event antd forwards.
               * @returns {void} Nothing; the entry is recorded as a side effect.
               */
              (event: ChangeEvent<HTMLInputElement>): void => {
                setDraftFilter(event.target.value);
              }
            }
            value={draftFilter}
          />
        </Form.Item>
      </Form>

      {/*
       * WHY : ⚠️ Refactoring Rationale: ONE `Radio.Group` owns the selection value and the change
       *       handler for the whole table, rather than each row holding an independent `Radio` with
       *       its own `checked`. Ten independent radios are ten separate one-of-one groups to an
       *       assistive technology: arrow keys do not move between them, the set is not announced as
       *       a set, and nothing states that choosing one clears another. The source is unambiguous
       *       that they ARE one set -- it scans `SEL0001I` through `SEL0010I` in row order and takes
       *       the first that is filled (L149 to L181), which is single select, first wins.
       * WHY : Alternatives Considered: a `Checkbox` column or antd's own `rowSelection` in checkbox
       *       mode. Both were rejected because they let an operator mark several rows and then
       *       discover that only one was honoured, permitting an interaction the source refuses.
       */}
      <Radio.Group
        aria-label={TRANSACTION_LIST_LABELS.selectionPrompt}
        onChange={
          /**
           * Records the row the operator chose.
           *
           * Assumptions: the control's value is the row's transaction identifier, because that is
           * what the source copies out of `TRNIDnn` alongside the selection character, and it is the
           * value the detail route is then built from -- so a choice and a navigation always address
           * the same record.
           * @param {RadioChangeEvent} event - The change event antd forwards; its value is the
           *   chosen row's transaction identifier.
           * @returns {void} Nothing; the choice is recorded as a side effect.
           */
          (event: RadioChangeEvent): void => {
            dispatchSelection({
              kind: 'choose',
              transactionId: String(event.target.value),
              code: TRANSACTION_LIST_SELECTION_CODE,
            });
          }
        }
        value={selection.transactionId}
      >
        {/*
         * WHY : Alternatives Considered: antd's own `pagination` object, which is the component's
         *       default and would be the shorter route. It is deliberately turned OFF because it
         *       positions a page by counting rows from the start of the ordering, and under
         *       concurrent insertion that skips a row into the following page and repeats another on
         *       it while a deletion drops one nobody ever sees. The source does not have that
         *       failure and neither may this screen: its browse state is ALREADY a keyset cursor --
         *       `CDEMO-CT00-TRNID-FIRST` is set from row 1's identifier at L393 and
         *       `CDEMO-CT00-TRNID-LAST` from row 10's at L439, and the paging paragraphs read
         *       strictly from those two. Keeping the pager off is what makes the mapping one-to-one
         *       rather than an approximation.
         */}
        <Table<TransactionSummary>
          aria-label={TRANSACTION_LIST_TABLE_LABEL}
          columns={buildTransactionListColumns(cssVar)}
          dataSource={browse.items}
          loading={browse.isLoading}
          pagination={false}
          scroll={TRANSACTION_LIST_TABLE_SCROLL}
          rowKey={
            /**
             * Uses each row's transaction identifier as its reconciliation identity.
             *
             * Assumptions: unique per row because it is the file's own key, `TRAN-ID PIC X(16)`, so
             * nothing has to be composed from other members. It is also the value the selection
             * control carries and the value the detail route is built from, which keeps all three
             * addressing one record.
             * @param {TransactionSummary} row - One listed transaction.
             * @returns {string} That row's transaction identifier.
             */
            (row: TransactionSummary): string => row.transactionId
          }
        />
      </Radio.Group>

      {/*
       * Assumptions: the row-21 prompt is painted `ATTRB=(ASKIP,BRT) COLOR=NEUTRAL`, and brightness
       * is carried as WEIGHT rather than as a brighter colour -- the measured resolution for every
       * bright field in the base mapset population -- so `strong` supplies the strong-weight token
       * while the neutral colour keeps its own de-emphasis token. Substituting a colour for the
       * weight would overwrite the one the field actually declares.
       */}
      <Typography.Text strong style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] }}>
        {TRANSACTION_LIST_LABELS.selectionPrompt}
      </Typography.Text>

      {/*
       * Assumptions: the body ends here. Row 23's message line and row 24's key legend are painted by
       * the shell from the delegation published above, so the last thing this body renders is the
       * mapset's own row-21 prompt. The rendered order is unchanged -- the shell paints both lines
       * immediately below this region -- and what is avoided is the duplication that composing them
       * here as well would produce.
       */}
    </Flex>
  );
}
