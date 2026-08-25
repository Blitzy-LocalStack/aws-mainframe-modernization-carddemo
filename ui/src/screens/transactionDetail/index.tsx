/**
 * @file The transaction detail screen, migrated from `app/cbl/COTRN01C.cbl` and its mapset
 * `app/bms/COTRN01.bms` (map `COTRN1A`, 56 `DFHMDF` fields -- 21 named, 35 anonymous), reached at
 * `/transactions/:id` and at `/transactions/view`.
 *
 * Purpose
 * -------
 * Look one transaction up by its sixteen-character identifier and render it as a read-only record
 * view. It replaces CICS transaction `CT01`, which `app/csd/CARDDEMO.CSD` L429-L430 binds to that
 * program, and it publishes the screen widths and the assembled key-legend text the screen tests
 * assert against. The painted TEXT it renders -- the caption, the lookup label and the thirteen field
 * labels -- is published by `ui/src/messages/messages.ts` instead, and imported here.
 *
 * Composition
 * ------------
 * Assumptions: the record is rendered with antd `Descriptions` and NOT with a form, because all
 * thirteen data fields the mapset paints are `ATTRB=(ASKIP,NORM)` -- skip-protected output --
 * `app/bms/COTRN01.bms` L105-L256. The single `UNPROT` field on the map is the lookup key `TRNIDIN`
 * at L85-L90, and it is rendered as a form of its own above the record, so the record view offers no
 * editing affordance the transaction does not have. The editable transaction path is
 * `/transactions/new` (`app/cbl/COTRN02C.cbl`).
 *
 * Two arrivals, one screen
 * ------------------------
 * Refactoring Rationale: the reference has TWO first entries and this screen reproduces both.
 * `app/cbl/COTRN01C.cbl` L103-L108 tests `CDEMO-CT01-TRN-SELECTED` against `SPACES AND LOW-VALUES`
 * and, when it carries a value, moves it into `TRNIDIN` and performs `PROCESS-ENTER-KEY` at once;
 * when it does not, L109 simply sends the empty map and waits. The producer of that carrier is the
 * browse screen -- `app/cbl/COTRN00C.cbl` L183-L195 writes the selection flag and the identifier and
 * then transfers control. The COMMAREA overlay that carried it does not travel here: the identifier
 * arrives as an explicit path parameter instead, which makes the request self-describing and
 * independently authorizable (AAP section 0.7.1). A route carrying an identifier is therefore the
 * first arrival, and one carrying none is the second.
 *
 * ⚠️ Assumptions: the second arrival has an address of its own, `/transactions/view`, and did not
 * always. This screen was for a time mounted only at `/transactions/:id`, so the selector-free arrival
 * it already implemented was unreachable from anywhere -- main-menu option 7 had no path it could name
 * and answered the reference's not-installed sentence for a screen the delivery carries. The screen
 * itself needed no change: `ui/src/router.tsx` declares the second path onto the same component and
 * `ui/src/routes/programRoutes.ts` resolves `COTRN01C` to it. ⚠️ The selector-free arrival's defining
 * property -- that it issues NO read -- is asserted by `opens a framed screen at every keyless entry
 * route` in `ui/src/routerReachability.test.tsx`, whose keyless sweep visits `/transactions/view`
 * alongside the two card entry routes and requires `dispatchedRequests()` to be empty at each. The
 * citation this replaces named
 * `ui/src/screens/transactionDetail/selectorFreeArrival.test.tsx`, which does not exist and never did:
 * that directory holds only `index.tsx` and `rendering.test.ts`. A citation to a file that is not there
 * is worse than none, because a reader takes it as evidence the property is covered and stops looking --
 * so it is repointed at the case that genuinely covers it rather than deleted.
 *
 * What this screen does NOT do
 * ----------------------------
 * Assumptions: there is no write of any kind -- no confirmation control, no submit, no delete. The
 * reference reads the record with `EXEC CICS READ ... UPDATE` at `app/cbl/COTRN01C.cbl` L269-L278 on
 * a view-only screen, and the target performs a plain read: whether a row is locked or versioned is
 * `transaction-service`'s concern, so no lock and no version is modelled here.
 *
 * Assumptions: rows 1-3, row 23 and row 24 are NOT painted here. `ui/src/layout/AppShell.tsx` owns
 * the title band, the row-23 message line and the row-24 key legend, and this screen delegates all
 * three through `useShellSlot`; what stays here is everything the mapset paints between rows 4 and
 * 20 -- the caption, the lookup field, the rule and the thirteen-field record.
 */

import { Descriptions, Divider, Flex, Form, Input, Spin, Typography, theme } from 'antd';
import type { DescriptionsProps, InputRef } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';

import { MASKED_CARD_NUMBER } from '../../api/masking';
import { viewTransaction } from '../../api/transactions';
import type { TransactionDetail } from '../../api/transactions';
import type { ApiError, FieldError, FieldValidationState } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
// Assumptions: `busyAnnouncement` is imported from the shared helper rather than a hidden span being
//   composed here, because the helper is the one place that decides the region's shape -- always
//   mounted, `role="status"`, empty when idle -- and a locally composed one would drift from it.
import { busyAnnouncement, fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { RECORD_VIEW_COLUMNS } from '../../layout/recordLayout';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
/*
 * WHY : Refactoring Rationale: the painted text of map `COTRN1A` -- the row-4 caption, the lookup
 *       label and the thirteen data-field labels -- is IMPORTED from the catalog, where this module
 *       used to transcribe it beside the controls that name it. The ownership boundary this file
 *       previously cited has moved: the catalog now carries `INITIAL=` literals as well as emitted
 *       messages, keyed by originating mapset and with the transcribed line numbers recorded beside
 *       each entry, which is what makes transformation rule T8 checkable in ONE module rather than in
 *       twenty-one screens.
 * WHY : Assumptions: the catalog's member keys are the `TransactionDetail` member names this screen
 *       already used, so the declarations move and no render site does.
 * WHY : Alternatives Considered: keeping the local groups and asserting them equal to the catalog's in
 *       a test. Rejected because two copies that agree today are still two copies to correct, and such
 *       a test reports that a pair disagrees without saying which side is right.
 */
import {
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  TRANSACTION_DETAIL_FIELD_LABELS,
  TRANSACTION_DETAIL_KEY_LABELS as CATALOG_KEY_LABELS,
  TRANSACTION_DETAIL_LOOKUP_LABEL,
  TRANSACTION_DETAIL_TITLE,
} from '../../messages/messages';
import {
  MAIN_MENU_ROUTE,
  TRANSACTION_LIST_ROUTE,
  inApplicationRoute,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import {
  BMS_COLOR_TOKENS,
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';

/*
 * WHY : ⚠️ Alternatives Considered: `ui/src/hooks/usePagedQuery.ts` is deliberately NOT imported, and
 *       its absence is worth a note because every other screen that reads a list does import it. This
 *       screen performs a SINGLE-RECORD KEYED READ -- `app/cbl/COTRN01C.cbl` L269-L296 issues one
 *       `EXEC CICS READ` on `TRAN-ID` and has no `STARTBR`/`READNEXT`/`READPREV`/`ENDBR` anywhere, so
 *       there is no browse cursor to carry and no page to turn. That hook exists for the five screens
 *       that do browse -- the card list, the transaction list, the user list, the pending-authorization
 *       summary and the reference-type list -- where it binds a keyset envelope to PF7 and PF8. Using
 *       it here would demand a `firstKey`/`lastKey`/`hasNext` envelope this contract never returns and
 *       would imply paging keys the mapset's row-24 legend does not paint: the legend is exactly
 *       `ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran.`, with no PF7 and no PF8.
 */

/** CICS transaction identifier this screen replaces, from `WS-TRANID` at `COTRN01C.cbl` L37. */
export const TRANSACTION_DETAIL_TRANSACTION_ID = 'CT01';

/** Source program name, from `WS-PGMNAME` at `app/cbl/COTRN01C.cbl` L36. */
export const TRANSACTION_DETAIL_PROGRAM_NAME = 'COTRN01C';

/**
 * Mapset this screen stands in, which is the identity a message band sizes itself from.
 *
 * Assumptions: named once here rather than written as a literal at the delegation site.
 * `app/cpy-bms/COTRN01.CPY` L144 declares this mapset's `ERRMSGI` as `PIC X(78)`, which is the
 * 19-of-21 majority width `ui/src/messages/messages.ts` records -- so the NAME is what carries the
 * width, and a screen never restates the number. Two spellings of one identity would be two places for
 * that table to be consulted from.
 */
export const TRANSACTION_DETAIL_MAPSET = 'COTRN01';

/** One labelled data field of the record view. */
type TransactionDetailField = keyof typeof TRANSACTION_DETAIL_FIELD_LABELS;

/**
 * Declared SCREEN width of each data field, from the mapset's own `LENGTH=` operands.
 *
 * Trade-offs: four of these are NARROWER than the record field behind them -- `TDESC` is `LENGTH=60`
 * against `TRAN-DESC PIC X(100)` (`app/cpy/CVTRA05Y.cpy` L9), `MNAME` is 30 against `X(50)` (L12),
 * `MCITY` is 25 against `X(50)` (L13), and both timestamp fields are 10 against `X(26)` (L16-L17) --
 * so a COBOL `MOVE` into them truncates on the right. This table RECORDS those widths and the render
 * site does not cut a received value to them, because a browser has no fixed cell to overflow and
 * discarding characters the service sent would misreport the record while looking correct. The two
 * timestamps are the one exception, and they are handled by {@link transactionDatePortion}, where
 * the narrowing is the reference's whole intent rather than a side effect of the field width.
 *
 * Assumptions: exported so the screen tests can assert a rendered value against the width the
 * terminal declared for it without restating the figure.
 */
export const TRANSACTION_DETAIL_SCREEN_WIDTHS = {
  transactionId: 16,
  cardNumber: 16,
  typeCode: 2,
  categoryCode: 4,
  source: 10,
  description: 60,
  amount: 12,
  originTimestamp: 10,
  processTimestamp: 10,
  merchantId: 9,
  merchantName: 30,
  merchantCity: 25,
  merchantZip: 10,
} as const satisfies Readonly<Record<TransactionDetailField, number>>;

/**
 * Declared width of the lookup control, `TRNIDIN LENGTH=16` at `app/bms/COTRN01.bms` L85-L90.
 *
 * Assumptions: three independent declarations agree on sixteen -- that `LENGTH=`, `TRNIDINI PIC
 * X(16)` at `app/cpy-bms/COTRN01.CPY` L60, and `TRAN-ID PIC X(16)` at `app/cpy/CVTRA05Y.cpy` L5 --
 * and `viewTransaction` reads the detail by that same sixteen-character identifier. The control
 * therefore cannot hold a value the service would reject for width.
 */
export const TRANSACTION_ID_ENTRY_WIDTH = 16;

/**
 * Function-key legend labels, assembled from the two modules that own this mapset's row-24 parts.
 *
 * Assumptions: `app/bms/COTRN01.bms` L263-L268 paints exactly `ENTER=Fetch  F3=Back  F4=Clear
 * F5=Browse Tran.` in one 47-character `COLOR=YELLOW` field, and `app/cbl/COTRN01C.cbl` L112-L127
 * admits exactly those four attention identifiers, so the painted legend and the accepted key set
 * agree and these four are the whole contract. PF7, PF8 and PF12 are absent from both and are
 * therefore not bound.
 *
 * Assumptions: `F4=Clear` comes from {@link UNIFORM_PF_KEY_LABELS} and the other three from the
 * message catalog, and that split is the boundary those two modules draw rather than an
 * inconsistency. `F4=Clear` is byte-identical across every measured legend that binds PF4, so
 * `ui/src/layout/PfKeyBar.tsx` owns it and the catalog deliberately carries no entry for it;
 * `ENTER=Fetch` and `F5=Browse Tran.` appear on no other mapset and `F3=Back` is one of three
 * competing PF3 spellings, so the catalog carries all three as this mapset's own text. Restating
 * either side here would put a second spelling of a verbatim constant in the tree with nothing
 * keeping the two equal.
 */
export const TRANSACTION_DETAIL_KEY_LABELS = {
  ENTER: CATALOG_KEY_LABELS.ENTER,
  PFK03: CATALOG_KEY_LABELS.PFK03,
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
  PFK05: CATALOG_KEY_LABELS.PFK05,
} as const;

/**
 * The row-24 legend exactly as the mapset paints it, reassembled from the four labels above.
 *
 * Assumptions: the groups are joined by TWO spaces and the whole is 47 characters, which is the
 * `LENGTH=47` the field declares at `app/bms/COTRN01.bms` L263-L268. Single-spacing the groups would
 * produce 44 characters and break the verbatim contract, so the separator is stated here once and
 * the reassembly is asserted by the screen tests rather than trusted.
 */
export const TRANSACTION_DETAIL_LEGEND = [
  TRANSACTION_DETAIL_KEY_LABELS.ENTER,
  TRANSACTION_DETAIL_KEY_LABELS.PFK03,
  TRANSACTION_DETAIL_KEY_LABELS.PFK04,
  TRANSACTION_DETAIL_KEY_LABELS.PFK05,
].join('  ');

/*
 * WHY : Assumptions: the browse route this screen's PF5 transfers to is IMPORTED from
 *       `ui/src/routes/navigation.ts` rather than declared here. `app/cbl/COTRN01C.cbl` L125-L127 moves
 *       `'COTRN00C'` into `CDEMO-TO-PROGRAM` and transfers, and `COTRN00C` is the transaction LIST
 *       program -- so PF5 here is navigation and not a save, notwithstanding that PF5 means save on most
 *       other CardDemo screens; the painted legend `F5=Browse Tran.` is the independent confirmation.
 * WHY : ⚠️ Refactoring Rationale: this module used to declare and export its own
 *       `TRANSACTION_LIST_ROUTE = '/transactions'`. That was a second spelling of a path the navigation
 *       module now owns -- the browse is an admissible ORIGIN, so its route had to be declared there for
 *       `inApplicationRoute` to admit it -- and two spellings of one path is one place for PF5's
 *       destination to drift silently to a route the table does not serve.
 */

/** Screen-level message this screen owns, verbatim from `app/cbl/COTRN01C.cbl` L149. */
const TRANSACTION_DETAIL_MESSAGES = PROGRAM_MESSAGES.COTRN01C;

/** Identifier tying the lookup control to its own label and to its refusal text. */
const TRANSACTION_ID_FIELD_ID = 'transaction-detail-tran-id';

/** Number of leading characters of a twenty-six-character timestamp the terminal displays. */
const TIMESTAMP_DATE_WIDTH = 10;

/** Integer positions the `PIC +99999999.99` amount mask declares at `app/cbl/COTRN01C.cbl` L49. */
const AMOUNT_MASK_INTEGER_POSITIONS = 8;

/** HTTP status the transaction contract answers when no row carries the identifier. */
const NOT_FOUND_STATUS = 404;

/**
 * Wire shape the transaction contract publishes an amount in.
 *
 * Assumptions: an optional minus sign, one to nine integer digits, a point and exactly two decimals --
 * the shape `ui/src/api/transactions.ts` validates its amounts against, which is `TRAN-AMT PIC
 * S9(09)V99` at `app/cpy/CVTRA05Y.cpy` L10 published as a string. A leading `+` is not admitted
 * because the service does not send one; the mask ADDS the sign position rather than echoing it.
 */
const WIRE_AMOUNT_PATTERN = /^(?<sign>-?)(?<integer>[0-9]+)\.(?<fraction>[0-9]{2})$/u;

/**
 * Renders a wire money string through the reference's `PIC +99999999.99` amount mask.
 *
 * `app/cbl/COTRN01C.cbl` L49 declares `WS-TRAN-AMT PIC +99999999.99` and L177 and L183 move
 * `TRAN-AMT` through it into the twelve-character `TRNAMT` field. That picture means an ALWAYS-PRESENT
 * sign, eight integer positions and two decimals -- and the integer positions are `9` rather than `Z`,
 * so they are zero-FILLED and never blanked. `+00000123.45` is what the terminal painted for one
 * hundred and twenty-three dollars and forty-five cents, and that is what this reproduces.
 *
 * Trade-offs: the value is handled as TEXT from end to end -- matched, sliced and padded -- and never
 * converted to a number. A JSON number is parsed into an IEEE-754 double by most clients, so routing
 * an amount through one would lose exactness at the boundary the operator actually reads; and the
 * monospaced face this is rendered in is what keeps the sign, the digits and the decimal point
 * aligned down the value column the way the 3270 cell grid did for free. Concretely no `Number`,
 * `parseFloat`, `parseInt`, unary `+`, `toFixed` or `Math` call appears in this function, which makes
 * the prohibition checkable by one search rather than by reading the body.
 *
 * ⚠️ Trade-offs: a value needing MORE than the mask's eight integer positions is rendered in FULL
 * rather than truncated, and that is a deliberate divergence from the reference. The record field
 * holds NINE integer digits (`TRAN-AMT PIC S9(09)V99`, `app/cpy/CVTRA05Y.cpy` L10) while the display
 * picture holds eight, so a decimal-aligned COBOL `MOVE` silently discards the high-order digit and
 * `123456789.99` paints as `+23456789.99` on the terminal -- understating the amount by a hundred
 * million with nothing on the screen to say so. Reproducing that was the alternative and is rejected:
 * AAP section 0.7.3 makes money exact at every hop and Rule T9 forbids shipping a behavioural change
 * that is not documented, and a display that misreports money is not a behaviour worth transcribing
 * faithfully. `ui/src/format/money.ts` reached the same conclusion for the `+ZZZ,ZZZ,ZZZ.99` mask and
 * registered it as `D-MONEY-MASK-NO-TRUNCATION`; this one belongs in
 * `docs/architecture/cobol-to-service-traceability.md` beside it.
 *
 * Trade-offs: a value that does not match the wire contract is returned UNCHANGED rather than
 * rejected. Throwing is what the transport validators do, and it is right there, at the boundary where
 * refusing a response is the correct outcome. This runs during render, on a value that boundary
 * already accepted, so refusing here would replace a correct-but-unformatted amount with a blank
 * screen -- and the amount stays truthful in the one case where its presentation cannot be.
 * @param {string} wireAmount - The amount exactly as `TransactionDetail.amount` carries it: an
 *   optional minus sign, integer digits, a decimal point and two decimal digits.
 * @returns {string} The amount rendered through the mask -- a `+` or `-` sign, at least eight
 *   zero-filled integer digits, a point and two decimals -- or the argument unchanged when it does not
 *   match the wire contract.
 */
export function formatTransactionAmount(wireAmount: string): string {
  const matched = WIRE_AMOUNT_PATTERN.exec(wireAmount);
  if (matched === null) {
    return wireAmount;
  }

  /*
   * WHY : Assumptions: all three groups are read through optional chaining and tested before use
   *       because `noUncheckedIndexedAccess` in ui/tsconfig.json types a named capture as
   *       `string | undefined`. Every group is mandatory in the pattern, so a successful match
   *       populates all three and the guard below is unreachable -- it is written rather than
   *       asserted away because a non-null assertion would throw at render time if the pattern were
   *       ever edited to make a group optional, whereas this returns the unformatted amount, which a
   *       test catches.
   */
  const sign = matched.groups?.sign;
  const integer = matched.groups?.integer;
  const fraction = matched.groups?.fraction;
  if (sign === undefined || integer === undefined || fraction === undefined) {
    return wireAmount;
  }

  /*
   * WHY : Assumptions: the sign position always emits a character because the picture's leading `+`
   *       is a FIXED position and not a conditional one -- it prints `+` for a non-negative value and
   *       `-` for a negative one. A negative zero cannot arrive: the pattern captures the sign apart
   *       from the digits and a scale-two zero is published as `0.00`.
   */
  const signCharacter = sign === '-' ? '-' : '+';

  /*
   * WHY : Assumptions: leading zeroes are stripped BEFORE padding rather than the wire digits being
   *       padded as they arrive. The two differ on a value the service happens to publish with its
   *       own leading zeroes: padding first would leave a ten-character integer field where the mask
   *       declares eight, which would read as the no-truncation divergence above on a value that does
   *       not need it. Stripping first makes the width a function of the MAGNITUDE, which is what the
   *       picture describes.
   * WHY : Trade-offs: `padStart` is used rather than a slice, so the field grows past eight positions
   *       instead of losing a digit. That is the divergence recorded above, expressed in one call.
   */
  const magnitude = integer.replace(/^0+/u, '');
  const integerField = magnitude.padStart(AMOUNT_MASK_INTEGER_POSITIONS, '0');

  return `${signCharacter}${integerField}.${fraction}`;
}

/**
 * Extracts the ten characters of a twenty-six-character timestamp the terminal displayed.
 *
 * `TRAN-ORIG-TS` and `TRAN-PROC-TS` are both `PIC X(26)` at `app/cpy/CVTRA05Y.cpy` L16 and L17,
 * carrying `'YYYY-MM-DD HH:MM:SS.mmmmmm'`. `app/cbl/COTRN01C.cbl` L185 and L186 move each into a
 * `PIC X(10)` map field, and a COBOL alphanumeric `MOVE` truncates on the RIGHT -- so the ten
 * characters the terminal painted are the leading `YYYY-MM-DD` date portion.
 *
 * Assumptions: the visible text is obtained by SLICING the string the service sent, and no
 * `Date` and no `dayjs` is constructed. The contract carries microsecond precision and a JavaScript
 * `Date` resolves to milliseconds, so a round trip through one would silently discard the last three
 * digits; there is no zone to interpret either, since reading the stored value as UTC or as local are
 * both assertions it does not make. The complete twenty-six characters stay available losslessly at
 * the render site, which passes them as the element's `title`, so nothing is lost even though the
 * visible text matches the terminal.
 *
 * Assumptions: an absent value answers the empty string rather than the word `null`.
 * `TransactionDetail.processTimestamp` is nullable in the contract because a transaction that has been
 * accepted but not yet posted genuinely carries no processing timestamp, and the terminal showed that
 * field blank -- `app/cbl/COTRN01C.cbl` L159-L171 blanks all thirteen fields before the read, so a
 * blank stored value stayed blank.
 * @param {string | null} timestamp - The stored timestamp exactly as the contract carries it, or
 *   `null` when the record holds none.
 * @returns {string} The leading ten characters -- the `YYYY-MM-DD` date portion -- or the empty
 *   string when the record carries no timestamp.
 */
export function transactionDatePortion(timestamp: string | null): string {
  return timestamp === null ? '' : timestamp.slice(0, TIMESTAMP_DATE_WIDTH);
}

/**
 * Presents a card number the service has already reduced, and withholds anything else.
 *
 * ⚠️ Refactoring Rationale: this function neither unmasks NOR re-masks, and until this revision it did
 * re-mask while its own docstring said it did not. It carried a local `/^\*+[0-9]{4}$/u` and, on a
 * miss, sliced the last four characters and prefixed a run of asterisks -- so a value the recognition
 * test rejected was reduced HERE, in the browser, holding the unreduced value first. Two things were
 * wrong with that. The local pattern was more permissive than the contract's, which
 * `ui/src/api/masking.ts` states as exactly twelve asterisks and four digits, and a mask pattern that
 * drifts in the permissive direction is the one failure mode that module exists to prevent. And a
 * client-side mask is the thing that module explicitly forbids: "a helper that masked a value
 * client-side would invite a caller to hold the unmasked one first, which is the exposure the
 * service-side mask exists to remove."
 *
 * Assumptions: the recognition test is `MASKED_CARD_NUMBER` imported from that module rather than a
 * copy. The earlier note argued a separate copy made this a second, independent opinion; the review
 * that produced this revision rejected that reasoning, and rightly -- an independent opinion is only
 * worth having if it is at least as strict, and this one was looser, so what it actually bought was a
 * screen that would render a partial mask its four sibling clients refuse.
 *
 * Assumptions: an unrecognised value is WITHHELD rather than reduced. `viewTransaction` in
 * `ui/src/api/transactions.ts` tests the same pattern and throws a `RangeError` before this screen
 * sees the response, and {@link BLANK_TRANSACTION_RECORD} is the only other source of this field, so
 * the branch is unreachable from either. It is implemented as a withholding rather than left out
 * because the cost of the two outcomes is not symmetric: an empty field is a rendering an operator
 * reports, whereas a whole account number reaches the DOM, the accessibility tree, a screenshot and
 * every bug report that carries them. AAP section 0.4.1.9 permits an unmasked number on the
 * administrative card-detail endpoint alone, which this screen does not call.
 *
 * Assumptions: the blank of the cleared record takes the same branch and renders blank, which is what
 * the mapset paints -- `app/cbl/COTRN01C.cbl` L309-L326 moves `SPACES` into `CARDNUMI` on PF4 and on
 * every failed lookup, so a blank value beside its standing label is the reference's cleared state.
 * @param {string} cardNumber - The card number as `TransactionDetail.cardNumber` carries it, expected
 *   to be the contract's reduced rendering or, on a cleared screen, blank.
 * @returns {string} The argument unchanged when it is the contract's reduced rendering, and the empty
 *   string for every other value including a blank one.
 */
export function presentMaskedCardNumber(cardNumber: string): string {
  return MASKED_CARD_NUMBER.test(cardNumber) ? cardNumber : '';
}

/**
 * How the lookup control was refused on the last turn.
 *
 * Assumptions: the state is the contract's own `FieldValidationState` rather than a scheme invented
 * here, because the two values decide different renderings. `app/cpy/CSSETATY.cpy` L21 moves `DFHRED`
 * into a refused field's colour for both, and L24 ADDITIONALLY writes a literal `'*'` into the field
 * when the refusal is that it was left blank -- so collapsing `BLANK` into `NOT_OK` would lose the
 * marker while collapsing it the other way would lose the distinction the copybook draws.
 */
interface LookupRefusal {
  /** Which of the copybook's two refusal states the control is in. */
  readonly state: FieldValidationState;
  /** The sentence the refusal reports, verbatim from the catalog or from the service. */
  readonly message: string;
}

/** Props spread onto the lookup control's `Form.Item` when the field was refused. */
interface FieldRefusalProps {
  /** Present only when the field is refused; `Form.Item` renders its error treatment. */
  readonly validateStatus?: 'error';
  /** Present only when the field is refused; carries the sentence and the `aria-describedby` target. */
  readonly help?: ReactElement;
}

/**
 * Maps the lookup control's refusal state onto the design system's field-error treatment.
 *
 * Assumptions: the props are SPREAD rather than passed as possibly-`undefined` values, because
 * `exactOptionalPropertyTypes` is enabled in ui/tsconfig.json and antd declares both members without
 * an explicit `undefined` arm -- so handing either one `undefined` fails to compile. Returning an
 * empty object is the form that expresses absence under that setting.
 *
 * Assumptions: the sentence is repeated beneath the control as well as in the row-23 band, and the
 * duplication is deliberate. The band is a screen-level live region an operator reads once, whereas
 * the `help` element is what {@link fieldAriaProps}' `aria-describedby` points at -- so without it a
 * screen reader focusing the control would be told there is a description and find nothing.
 * @param {LookupRefusal | null} refusal - How the control was refused, or `null` when it was accepted
 *   or no turn has been taken.
 * @returns {FieldRefusalProps} Props to spread onto the control's `Form.Item`; empty when the control
 *   carries no refusal.
 */
function refusalPropsFor(refusal: LookupRefusal | null): FieldRefusalProps {
  return refusal === null
    ? {}
    : {
        validateStatus: 'error',
        help: fieldErrorHelp(TRANSACTION_ID_FIELD_ID, refusal.message),
      };
}

/**
 * Narrows a rejected read to the normalised problem document the API client raises.
 *
 * Alternatives Considered: `isApiRequestError` from `ui/src/api/client.ts`, which is the obvious
 * candidate and is rejected for the two reasons the account view and card detail screens record for
 * the same narrowing -- it is an `instanceof` check, so it answers `false` for the hand-assembled
 * response doubles the harness under `ui/src/test/**` builds, and `client.ts` is not among this
 * module's declared dependencies whereas `ui/src/api/types.ts` is.
 *
 * Assumptions: the document is recognised by two required members of `ApiError` -- a `fieldErrors`
 * array and a numeric `status` -- rather than by a discriminant, because the type declares none. The
 * error class republishes the document under a `problem` member, so both the wrapper and a bare
 * document are accepted.
 * @param {unknown} reason - The value the rejected read settled with.
 * @returns {ApiError | null} The problem document, or `null` when the rejection carried none -- a
 *   `RangeError` from the client's own response checks, or any other thrown value.
 */
function problemFrom(reason: unknown): ApiError | null {
  if (typeof reason !== 'object' || reason === null) {
    return null;
  }
  const candidate = (reason as { readonly problem?: unknown }).problem ?? reason;
  if (typeof candidate !== 'object' || candidate === null) {
    return null;
  }
  const { fieldErrors, status } = candidate as {
    readonly fieldErrors?: unknown;
    readonly status?: unknown;
  };
  return Array.isArray(fieldErrors) && typeof status === 'number' ? (candidate as ApiError) : null;
}

/**
 * Chooses the sentence a failed lookup reports, from the status the failure carries.
 *
 * Assumptions: the two arms are the reference's own. `app/cbl/COTRN01C.cbl` L280-L296 evaluates the
 * file response and takes exactly two paths -- `DFHRESP(NOTFND)` composes
 * `'Transaction ID NOT found...'` at L285, and a single `WHEN OTHER` arm composes
 * `'Unable to lookup Transaction...'` at L292 for every other response there is. HTTP 404 is the
 * migrated form of the first and every other outcome falls to the second, which is why there is no
 * third arm: adding one would report an outcome the reference has no sentence for.
 *
 * Assumptions: a rejection carrying NO problem document also takes the second arm. That covers the
 * `RangeError` `viewTransaction` raises when the service returns an unreduced card number, which is a
 * lookup that did not produce a usable record -- exactly what the sentence says. The correlation
 * identifier on the document remains the way to recover the suppressed detail server-side; the
 * reference's own `DISPLAY 'RESP:' ... 'REAS:'` at L290 writes its equivalent to the job log rather
 * than to the terminal, so withholding it from the operator is transcription and not redaction added
 * here.
 * @param {unknown} reason - The value the read rejected with.
 * @returns {string} The verbatim sentence to show in the row-23 band.
 */
export function describeLookupFailure(reason: unknown): string {
  const problem = problemFrom(reason);
  return problem !== null && problem.status === NOT_FOUND_STATUS
    ? SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND
    : SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION;
}

/**
 * Selects the per-field refusal a failed lookup carries for the one control this screen has.
 *
 * Assumptions: the FIRST field error is taken whatever it names, rather than the array being searched
 * for a particular field name. This screen paints one input, so every field-level refusal the service
 * can report about this request is about that input; matching on a name would additionally couple this
 * screen to the service's spelling of its path parameter, which `ui/src/api/transactions.ts` records
 * as a contract detail a screen's own route is free to differ from.
 * @param {unknown} reason - The value the read rejected with.
 * @returns {LookupRefusal | null} The refusal to render on the control, or `null` when the failure
 *   carried no per-field error.
 */
function fieldRefusalFrom(reason: unknown): LookupRefusal | null {
  const problem = problemFrom(reason);
  const reported: FieldError | undefined = problem?.fieldErrors[0];
  return reported === undefined ? null : { state: reported.state, message: reported.message };
}

/** The four screen actions this mapset's row-24 legend binds a key to. */
export interface TransactionDetailKeyActions {
  /** Runs the lookup, which is what `DFHENTER` does at `app/cbl/COTRN01C.cbl` L113-L114. */
  readonly onFetch: () => void;
  /** Returns to the caller, which is what `DFHPF3` does at `app/cbl/COTRN01C.cbl` L115-L122. */
  readonly onBack: () => void;
  /** Clears the screen, which is what `DFHPF4` does at `app/cbl/COTRN01C.cbl` L123-L124. */
  readonly onClear: () => void;
  /** Opens the browse, which is what `DFHPF5` does at `app/cbl/COTRN01C.cbl` L125-L127. */
  readonly onBrowse: () => void;
}

/**
 * Builds the handler map this screen registers with `usePfKeys`.
 *
 * Assumptions: the map is keyed by CICS attention identifier, so the PF13-to-PF24 folding that
 * `app/cpy/CSSTRPFY.cpy` L54-L77 performs is applied once by the hook rather than by this screen --
 * which matters here even though `COTRN01C` does not copy that book: it evaluates raw `EIBAID` at
 * L112-L132, and the hook's normalisation is what makes a terminal emulator's PF15 reach the PF3 arm
 * the way a real 3270 would.
 *
 * ⚠️ Assumptions: PF5's `action` is overridden to `screen-defined`, and the override is load-bearing
 * rather than cosmetic. `DEFAULT_PF_KEY_ACTIONS` in `ui/src/layout/usePfKeys.ts` maps PF5 to `save`
 * because that is what it means on most CardDemo screens, and on THIS screen it is navigation:
 * `app/cbl/COTRN01C.cbl` L125-L127 moves `'COTRN00C'` into `CDEMO-TO-PROGRAM` and transfers, and the
 * mapset paints the key `F5=Browse Tran.` rather than `F5=Save`. Leaving the default would label a
 * navigation as a write in every consumer of the semantic action. The button EMPHASIS is unaffected,
 * because `PfKeyBar` decides that from `PRIMARY_ACTION_AIDS` -- the AID -- and not from the action, so
 * PF5 keeps the primary treatment the design-system mapping gives it.
 *
 * Assumptions: exactly four keys are bound and no fifth. PF7, PF8 and PF12 appear neither in the
 * row-24 legend nor in the program's `EVALUATE`, so binding one would add a key the reference answers
 * with its invalid-key message.
 * @param {TransactionDetailKeyActions} actions - The four screen actions, supplied by the component so
 *   a click on a legend button and a press of the key run the same function.
 * @returns {PfKeyHandlerMap} The handler map, with each entry carrying the mapset's verbatim label.
 */
export function buildTransactionDetailKeyHandlers(
  actions: TransactionDetailKeyActions,
): PfKeyHandlerMap {
  /*
   * WHY : ⚠️ Refactoring Rationale: every entry below declares `risk: 'read-only'`, and the reason it
   *       has to be declared rather than left to the fallback is PF5. `PfKeyBar`'s
   *       `PRIMARY_ACTION_AIDS` fallback emphasises `ENTER` and `PFK05` on every screen, which is a
   *       reading derived from the mapsets where PF5 saves -- and on this one it does not.
   *       `app/bms/COTRN01.bms` L267 paints `F5=Browse Tran.`, and `app/cbl/COTRN01C.cbl` transfers to
   *       the browse for `DFHPF5`, so the key NAVIGATES. Emphasising it as a primary action told the
   *       operator this screen had a committing control, and it has none at all.
   * WHY : ⚠️ Assumptions: the classification is taken from what each LABEL says the action does and
   *       never from which attention identifier carries it, which is the whole point of the taxonomy --
   *       the same `PFK05` is delete, save and browse on three different mapsets. `ENTER=Fetch` reads,
   *       `F3=Back` and `F5=Browse Tran.` navigate, and `F4=Clear` blanks controls locally. Not one of
   *       the four reaches a write, which is consistent with what this module's header records: this
   *       screen has no confirmation control, no submit and no delete.
   * WHY : Assumptions: declaring `read-only` on all four leaves the legend with no primary control,
   *       which is the intended consequence rather than an oversight. Emphasis under this taxonomy
   *       signals CONSEQUENCE and not importance, so a screen that changes nothing paints nothing
   *       prominent -- and an operator who learns that a solid control commits keeps that reading
   *       across every screen in the tree.
   */
  return {
    ENTER: {
      /**
       * Runs the lookup for whatever the control currently holds.
       * @returns {void} Nothing; the outcome is published through the screen's own state.
       */
      onInvoke: (): void => {
        actions.onFetch();
      },
      label: TRANSACTION_DETAIL_KEY_LABELS.ENTER,
      risk: 'read-only',
    },
    PFK03: {
      /**
       * Returns to the route that handed this screen over, or to the main menu when none did.
       * @returns {void} Nothing; the transition is performed as a side effect on the router.
       */
      onInvoke: (): void => {
        actions.onBack();
      },
      label: TRANSACTION_DETAIL_KEY_LABELS.PFK03,
      risk: 'read-only',
    },
    PFK04: {
      /**
       * Blanks the lookup control, the thirteen displayed values and the message line.
       * @returns {void} Nothing; the outcome is published through the screen's own state.
       */
      onInvoke: (): void => {
        actions.onClear();
      },
      label: TRANSACTION_DETAIL_KEY_LABELS.PFK04,
      risk: 'read-only',
    },
    PFK05: {
      /**
       * Opens the transaction browse, which is a navigation and not a save.
       * @returns {void} Nothing; the transition is performed as a side effect on the router.
       */
      onInvoke: (): void => {
        actions.onBrowse();
      },
      action: 'screen-defined',
      label: TRANSACTION_DETAIL_KEY_LABELS.PFK05,
      risk: 'read-only',
    },
  };
}

/**
 * One item of the record view.
 *
 * Assumptions: derived from `DescriptionsProps` rather than imported by name, because antd 6
 * re-exports `DescriptionsProps` from the package root but not `DescriptionsItemType`, and this
 * module imports from the package root only so that the CSS-variables theme applies. Deriving it
 * keeps the builder's return type tied to the version installed.
 */
type TransactionDetailDescriptionItem = NonNullable<DescriptionsProps['items']>[number];

/** Token-resolved styles the record view paints its labels and values with. */
interface RecordItemStyles {
  /** Applied to every label, carrying the mapset's `COLOR=TURQUOISE` text role. */
  readonly labelStyle: CSSProperties;
  /** Applied to the four proportional values, carrying `COLOR=BLUE` plus word breaking. */
  readonly freeTextValueStyle: CSSProperties;
  /** Applied to the eight fixed-width values, carrying `COLOR=BLUE` plus the code face. */
  readonly fixedPitchValueStyle: CSSProperties;
}

/**
 * Renders one data field's label, verbatim from the mapset.
 * @param {TransactionDetailField} field - The record member the label sits beside.
 * @param {CSSProperties} labelStyle - The token-resolved turquoise text style all labels share.
 * @returns {ReactElement} The label element, carrying the literal exactly as `app/bms/COTRN01.bms`
 *   paints it, trailing colon included.
 */
function recordLabel(field: TransactionDetailField, labelStyle: CSSProperties): ReactElement {
  return (
    <Typography.Text style={labelStyle}>{TRANSACTION_DETAIL_FIELD_LABELS[field]}</Typography.Text>
  );
}

/**
 * Renders a timestamp the way the terminal painted it, without losing the precision behind it.
 *
 * Assumptions: the VISIBLE text is the ten-character date portion, because a COBOL alphanumeric
 * `MOVE` of a `PIC X(26)` field into a `PIC X(10)` one truncates on the right
 * (`app/cbl/COTRN01C.cbl` L185-L186). The complete twenty-six characters are attached as the
 * element's `title` so the microsecond precision the contract carries stays reachable in the UI --
 * the terminal simply had no field wide enough to show it, which is a constraint of the 3270 rather
 * than a decision to discard the value.
 *
 * Assumptions: an absent timestamp carries NO `title` at all rather than an empty one, because
 * `exactOptionalPropertyTypes` distinguishes an omitted property from an explicitly `undefined` one
 * and an empty tooltip would render as an empty box on hover.
 *
 * ⚠️ Refactoring Rationale: absent means `null` OR blank, and the test used to be `=== null` alone.
 * That was a real gap once {@link BLANK_TRANSACTION_RECORD} began feeding this function, because
 * `Timestamp26` is a plain `string` alias (`ui/src/api/types.ts` L360) and so the blank record's
 * `originTimestamp` has to be `''` rather than `null` to stay assignable. A `''` therefore took the
 * title branch and emitted `title=""` on the cleared screen -- invisible in Chrome, but a direct
 * contradiction of the paragraph above, which is the kind of comment-versus-code divergence that
 * misleads the next reader. Widening the test to cover a blank string makes the documented intent and
 * the behaviour agree, and it holds for any blank the service might send, not just for the constant
 * that exposed it.
 * @param {string | null} timestamp - The stored timestamp as the contract carries it, or `null` when
 *   the record holds none.
 * @param {CSSProperties} valueStyle - The token-resolved fixed-pitch value style.
 * @returns {ReactElement} The date-portion element, with the full timestamp as its title when the
 *   record carries one.
 */
function timestampValue(timestamp: string | null, valueStyle: CSSProperties): ReactElement {
  const absent = timestamp === null || timestamp.trim().length === 0;
  return (
    <Typography.Text style={valueStyle} {...(absent ? {} : { title: timestamp })}>
      {transactionDatePortion(timestamp)}
    </Typography.Text>
  );
}

/**
 * The thirteen carried fields with every value blank, used whenever no record is displayed.
 *
 * ⚠️ Refactoring Rationale: this is the migrated form of `INITIALIZE-ALL-FIELDS` at
 * `app/cbl/COTRN01C.cbl` L309-L326, which is reached by PF4 and by every failed lookup. That
 * paragraph `MOVE SPACES` into the thirteen DATA fields -- `TRNIDI` through `MZIPI` -- and into the
 * lookup field and the message, and it touches the thirteen LABELS not at all. It cannot: the labels
 * are `INITIAL=` literals in the mapset itself (`app/bms/COTRN01.bms` L104 `INITIAL='Transaction
 * ID:'`, L117 `Card Number:`, L179 `Amount:` and the ten others), so the terminal repaints them on
 * every send of the map whatever the program did to the data. Cleared therefore means thirteen
 * labels standing with thirteen blank values, and it means the same on first entry without a
 * selection, because the map's data fields also carry `INITIAL=' '`.
 *
 * Alternatives Considered: rendering nothing at all while there is no record, which is what this
 * screen did until runtime validation compared it against the terminal. It is the obvious React
 * shape and it is wrong twice over. It loses the thirteen labels, which the baseline never removes;
 * and it answers a refused lookup with an empty body region, which reads as "nothing happened"
 * exactly when the operator needs to see that the previous record is gone -- the 3270 left the
 * labelled grid in place, so a blank value column was unmistakably a cleared record rather than an
 * unresponsive screen. Keeping the grid mounted also holds the body's height steady across a
 * fetch/clear cycle instead of collapsing and reflowing everything below it.
 *
 * Assumptions: blank values need no special-casing anywhere downstream, which was verified against
 * each helper rather than assumed. {@link formatTransactionAmount} finds no match in an empty string
 * and returns it unchanged, so a blank amount stays blank instead of becoming `+00000000.00` -- a
 * formatted zero would assert a value the record does not carry. {@link transactionDatePortion}
 * slices an empty string to an empty string, and {@link presentMaskedCardNumber} answers the empty
 * string for a value that is not the contract's reduced rendering, which a blank is not. All three
 * therefore render a space-filled field as the mapset paints it.
 */
const BLANK_TRANSACTION_RECORD: TransactionDetail = {
  transactionId: '',
  typeCode: '',
  categoryCode: '',
  source: '',
  description: '',
  amount: '',
  merchantId: '',
  merchantName: '',
  merchantCity: '',
  merchantZip: '',
  cardNumber: '',
  originTimestamp: '',
  processTimestamp: null,
  returnMessage: null,
};

/**
 * Builds the thirteen record-view items in the mapset's own reading order.
 *
 * Assumptions: the order and the grouping are the mapset's rows read top to bottom -- row 10 pairs the
 * transaction identifier with the card number, row 12 carries the type code, the category code and the
 * source, row 14 the description alone, row 16 the amount and the two dates, row 18 the merchant
 * identifier and name, and row 20 the merchant city and postal code. Within each row the fields appear
 * in ascending `col` order, which is the order an operator tabbed through them.
 *
 * ⚠️ Assumptions: three items close their own row with `span: 'filled'` -- the source, the description
 * and the processing timestamp -- and the three are chosen so that NO mapset row bleeds into the next.
 * Left to the default every item spans one column, and at the two-column width that pairs the source
 * with the description (mapset rows 12 and 14) and the processing timestamp with the merchant
 * identifier (rows 16 and 18), which puts two fields the terminal painted on different rows side by
 * side and reads as though they belonged together. Closing the row after the last field of each
 * odd-length mapset row removes both bleeds and, as a consequence, gives the description the full width
 * its hundred stored characters need -- it is the one field the mapset gives a row to itself
 * (`LENGTH=60` at `POS=(14,19)`).
 *
 * ⚠️ Alternatives Considered: a numeric span taken from `RECORD_VIEW_COLUMNS`, which is what this
 * builder was authored with and is a defect. `getCalcRows` in antd's own `descriptions/hooks/useRow`
 * admits an item only while the running count stays within the merged column, so a span of two arriving
 * after the single-column source made the count three against a column of two -- which antd reports as
 * `Sum of column span in a line not match column`, then CLAMPS the span back to the one remaining
 * column. The explicit span therefore achieved nothing except a console warning on every render that
 * showed a record. `'filled'` is the operand for this intent: it closes the row and antd expands the
 * item to the columns left over, so the width is derived from the table's own responsive column count
 * rather than restated beside it.
 * @param {TransactionDetail} record - The transaction the service returned.
 * @param {RecordItemStyles} styles - Token-resolved label and value styles.
 * @returns {TransactionDetailDescriptionItem[]} The thirteen items, in the mapset's reading order.
 */
function buildRecordItems(
  record: TransactionDetail,
  styles: RecordItemStyles,
): TransactionDetailDescriptionItem[] {
  const { labelStyle, freeTextValueStyle, fixedPitchValueStyle } = styles;

  return [
    {
      key: 'transactionId',
      label: recordLabel('transactionId', labelStyle),
      children: (
        <Typography.Text style={fixedPitchValueStyle}>{record.transactionId}</Typography.Text>
      ),
    },
    {
      key: 'cardNumber',
      label: recordLabel('cardNumber', labelStyle),
      children: (
        <Typography.Text style={fixedPitchValueStyle}>
          {presentMaskedCardNumber(record.cardNumber)}
        </Typography.Text>
      ),
    },
    {
      key: 'typeCode',
      label: recordLabel('typeCode', labelStyle),
      children: <Typography.Text style={fixedPitchValueStyle}>{record.typeCode}</Typography.Text>,
    },
    {
      key: 'categoryCode',
      label: recordLabel('categoryCode', labelStyle),
      children: (
        <Typography.Text style={fixedPitchValueStyle}>{record.categoryCode}</Typography.Text>
      ),
    },
    {
      key: 'source',
      label: recordLabel('source', labelStyle),
      span: 'filled',
      children: <Typography.Text style={freeTextValueStyle}>{record.source}</Typography.Text>,
    },
    {
      key: 'description',
      label: recordLabel('description', labelStyle),
      span: 'filled',
      children: <Typography.Text style={freeTextValueStyle}>{record.description}</Typography.Text>,
    },
    {
      key: 'amount',
      label: recordLabel('amount', labelStyle),
      children: (
        <Typography.Text style={fixedPitchValueStyle}>
          {formatTransactionAmount(record.amount)}
        </Typography.Text>
      ),
    },
    {
      key: 'originTimestamp',
      label: recordLabel('originTimestamp', labelStyle),
      children: timestampValue(record.originTimestamp, fixedPitchValueStyle),
    },
    {
      key: 'processTimestamp',
      label: recordLabel('processTimestamp', labelStyle),
      span: 'filled',
      children: timestampValue(record.processTimestamp, fixedPitchValueStyle),
    },
    {
      key: 'merchantId',
      label: recordLabel('merchantId', labelStyle),
      children: <Typography.Text style={fixedPitchValueStyle}>{record.merchantId}</Typography.Text>,
    },
    {
      key: 'merchantName',
      label: recordLabel('merchantName', labelStyle),
      children: <Typography.Text style={freeTextValueStyle}>{record.merchantName}</Typography.Text>,
    },
    {
      key: 'merchantCity',
      label: recordLabel('merchantCity', labelStyle),
      children: <Typography.Text style={freeTextValueStyle}>{record.merchantCity}</Typography.Text>,
    },
    {
      key: 'merchantZip',
      label: recordLabel('merchantZip', labelStyle),
      /*
       * WHY : ⚠️ Refactoring Rationale: the postal code renders in the same face as the city it
       *       stands beside, where it previously took the code face. The two are one row at the
       *       two-column width and one address in the record, and the mapset gives them identical
       *       attributes -- the rationale is recorded in full on `fixedPitchValueStyle` below.
       */
      children: <Typography.Text style={freeTextValueStyle}>{record.merchantZip}</Typography.Text>,
    },
  ];
}

/**
 * Renders one transaction addressed by the identifier in its route, or an empty lookup form.
 *
 * The screen takes NO props: everything it needs arrives from the router -- the identifier as a path
 * parameter and the departing screen's origin as history state -- which is what lets one component
 * serve both of the reference's first entries.
 *
 * Error paths it surfaces, all four verbatim from the catalog: an empty or blank lookup entry reports
 * `Tran ID can NOT be empty...` and issues no request at all; a lookup answered HTTP 404 reports
 * `Transaction ID NOT found...`; every other failure, normalised to an `ApiError` problem document or
 * carrying none at all, reports `Unable to lookup Transaction...`; and a recognised but unbound key
 * reports the invalid-key sentence the `usePfKeys` rejection carries. A per-field refusal is
 * additionally rendered on the lookup control itself.
 * @returns {ReactElement} The rows 4-to-20 body of map `COTRN1A` -- the caption, the lookup field, the
 *   rule and, once a record has been read, the thirteen-field record view -- with the title band, the
 *   row-23 message line and the row-24 legend delegated to the shell.
 */
export function TransactionDetailScreen(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  /*
   * WHY : Assumptions: the parameter is read as `id` because `ui/src/router.tsx` fixes the segment
   *       spellings and this screen is mounted at `/transactions/:id`. React Router resolves a
   *       parameter by NAME, so reading any other spelling -- `transactionId`, `tranId` -- would
   *       silently yield `undefined` and turn every deep link into the empty-form arrival, with
   *       nothing failing to say so.
   * WHY : ⚠️ Assumptions: `undefined` is a FIRST-CLASS answer here and not only the symptom of a
   *       misspelling, because the same component is also mounted at the selector-free
   *       `/transactions/view` for main-menu option 7, which carries no selection. Both arrivals are
   *       the reference's own: `app/cbl/COTRN01C.cbl` L103-L108 reads its selection carrier and L109
   *       paints the empty map when it is blank. The two are distinguished by this value alone -- the
   *       effect below reads on a present one and does nothing at all on an absent one -- so nothing
   *       else in the screen needs to know which route it arrived on.
   */
  const { id } = useParams<{ id: string }>();
  /*
   * WHY : Assumptions: design values are read as token NAMES from `ui/src/theme/tokens.ts` and
   *       resolved through `cssVar`, never through the sibling `token` member of the same hook.
   *       `cssVar` returns the reference form -- `var(--ant-color-primary)` -- so every element keeps
   *       following the theme antd 6 installs through CSS variables, whereas `token` returns a
   *       RESOLVED value and would bake today's palette into this screen and silently opt it out of
   *       later theme changes.
   */
  const { cssVar } = theme.useToken();
  const paintedAt = useServerInstant();
  const lookupControl = useRef<InputRef>(null);

  /*
   * WHY : Assumptions: the control is seeded from the route parameter on the FIRST render rather than
   *       only by the effect below, so the very first paint already shows the identifier the address
   *       names. That mirrors `app/cbl/COTRN01C.cbl` L105-L106, which moves the selection carrier into
   *       `TRNIDINI` BEFORE performing the read, so the sent map never showed an empty key beside a
   *       populated record.
   */
  const [entry, setEntry] = useState<string>(id ?? '');
  const [record, setRecord] = useState<TransactionDetail | null>(null);
  /*
   * WHY : ⚠️ Refactoring Rationale: the sentence is held WITHOUT a severity beside it, where this
   *       screen previously carried a second `MessageBandSeverity` state and named a severity on every
   *       arm. A rendering review measured the consequence: the successful arm named `'info'`, so a
   *       reply the service sent alongside a retrieved record was painted as an informational alert
   *       INSIDE the row-23 band -- and across the application the same class of event drew four
   *       different severities on four screens because each one decided for itself.
   * WHY : ⚠️ Assumptions: the severity is a property of the CHANNEL and not of the sentence, so it is
   *       resolved by the band from {@link MESSAGE_BAND_CHANNELS} rather than named here.
   *       `app/bms/COTRN01.bms` L259-L262 declares this mapset's one message field as
   *       `ERRMSG ... COLOR=RED ... POS=(23,1)`, and `app/cbl/COTRN01C.cbl` L217 is the single
   *       `MOVE WS-MESSAGE TO ERRMSGO` every arm reaches -- so on the terminal there is exactly one
   *       line and exactly one colour, whatever the arm. `defaultMessageBandSeverity` in
   *       `ui/src/layout/MessageBand.tsx` reads that same `COLOR=` operand, which is why omitting the
   *       member reproduces the mapset instead of restating it.
   * WHY : Alternatives Considered: keeping the state and assigning the channel's own default on every
   *       arm. Rejected because it leaves the screen able to name a severity the mapset does not
   *       declare -- which is the defect being removed -- whereas withdrawing the member makes that
   *       unstateable here at all.
   * WHY : ⚠️ Alternatives Considered: taking the severity from the problem document through
   *       `messageBandSeverityForApiSeverity`, which is the correspondence
   *       `ui/src/layout/MessageBand.tsx` publishes and is the obvious candidate on the failing arm.
   *       Rejected on what it would do to a refusal's colour: that helper maps `LOG` to `neutral`, so
   *       a service answering with the quietest tier would paint `'Unable to lookup Transaction...'`
   *       -- a sentence `app/cbl/COTRN01C.cbl` L292 puts into `WS-MESSAGE`, and therefore into a field
   *       declared `COLOR=RED` -- in a colour the mapset never gives it. It would also make this the
   *       one screen whose refusal colour depends on a transport field, which is the per-screen
   *       divergence being removed. That helper is for a screen whose band renders a document's own
   *       message; this screen renders the reference's four sentences and the view's own message line,
   *       and all five belong to the one line the mapset paints red.
   * WHY : Assumptions: the normalised `ApiError` is still reduced to a SENTENCE here rather than handed
   *       onward, because `ui/src/layout/MessageBand.tsx` is deliberately presentational: it accepts a
   *       nullable string, imports nothing from `ui/src/api/**`, and so cannot accept an `ApiError` at
   *       all. Per-FIELD errors do not travel this channel -- they go to the lookup control's own
   *       `Form.Item` through {@link refusalPropsFor}, mirroring `app/cpy/CSSETATY.cpy` L17-L27, which
   *       colours the FIELD rather than writing the message line.
   */
  const [message, setMessage] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<LookupRefusal | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  /*
   * WHY : Trade-offs: an issue counter discriminates a settled read from a superseded one, rather than
   *       an `AbortController` cancelling the request. The counter is chosen because what has to be
   *       prevented is a STALE WRITE to this screen's state -- a slow first read landing after the
   *       operator has already looked something else up -- and abandoning the response achieves that
   *       whether or not the socket was closed. Aborting would additionally save the bytes, at the
   *       cost of routing a cancellation signal through a typed client whose read operation accepts
   *       none, which would mean widening a transport contract to serve a rendering concern.
   */
  const issuedReads = useRef<number>(0);

  const focusLookup = useCallback(
    /**
     * Returns the cursor to the lookup control.
     *
     * Assumptions: this is the migrated form of `MOVE -1 TO TRNIDINL`, which
     * `app/cbl/COTRN01C.cbl` performs on every arm that reports a refusal -- L151, L154, L287, L294
     * and L311 -- because a 3270 cursor position is what told the operator which field to correct.
     * @returns {void} Nothing; the focus move is a side effect on the document.
     */
    (): void => {
      lookupControl.current?.focus();
    },
    [],
  );

  const runLookup = useCallback(
    /**
     * Runs one turn of `PROCESS-ENTER-KEY` (`app/cbl/COTRN01C.cbl` L144-L192).
     *
     * Assumptions: a blank entry is refused WITHOUT a request and, crucially, WITHOUT blanking the
     * thirteen displayed values. The reference sets its error flag at L148 and the pre-read blanking
     * at L159-L171 sits inside `IF NOT ERR-FLG-ON` at L158, so a record already on the terminal
     * survived a blank-key turn. Blanking it here would discard a record the refusal says nothing
     * about.
     *
     * Assumptions: a non-blank entry blanks all thirteen values BEFORE the read and leaves them blank
     * when the read fails, which is the same two conditionals read the other way -- L158 admits the
     * blanking and L176 refuses the population when the read set the flag.
     * @param {string} rawEntry - The lookup control's text, as typed or as taken from the route.
     * @returns {void} Nothing; every outcome is published through this screen's own state.
     */
    (rawEntry: string): void => {
      /*
       * WHY : Assumptions: the entry is trimmed before the emptiness test because the reference tests
       *       `TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES` at L147, and a 3270 field of sixteen
       *       blanks IS `SPACES` -- so a browser control holding only whitespace has to take the same
       *       arm. The TRIMMED value is also what is sent, since a fixed-width field's padding is
       *       padding and not part of the key.
       */
      const candidate = rawEntry.trim();

      if (candidate === '') {
        const refusalMessage = TRANSACTION_DETAIL_MESSAGES.TRAN_ID_CAN_NOT_BE_EMPTY;
        setRefusal({ state: 'BLANK', message: refusalMessage });
        setMessage(refusalMessage);
        focusLookup();
        return;
      }

      setRefusal(null);
      setRecord(null);
      setMessage(null);
      setLoading(true);

      const issued = issuedReads.current + 1;
      issuedReads.current = issued;

      viewTransaction(candidate).then(
        /**
         * Publishes a retrieved record, unless a later turn has superseded this read.
         * @param {TransactionDetail} detail - The transaction the service returned, with every amount
         *   a string, both timestamps in their twenty-six-character form and the card number reduced.
         * @returns {void} Nothing; the record is published through this screen's own state.
         */
        (detail: TransactionDetail): void => {
          if (issuedReads.current !== issued) {
            return;
          }
          setRecord(detail);
          /*
           * WHY : Assumptions: the service's own `returnMessage` is shown when it sends one, and it
           *       is `null` on the ordinary path -- so the band stays blank exactly as the terminal's
           *       did. `app/cbl/COTRN01C.cbl` L91 moves `SPACES` into `WS-MESSAGE` on every entry and
           *       no arm of a successful read sets it, so a blank row 23 is the faithful rendering;
           *       the member exists because it is the migrated carrier of that field, and discarding
           *       a sentence the service authored for the operator would lose information the
           *       reference had a channel for.
           * WHY : ⚠️ Refactoring Rationale: no severity is named on this arm, where it previously named
           *       `'info'`. That was a decision by TONE -- a sentence arriving with a retrieved record
           *       reads mild -- and `MESSAGE_BAND_CHANNELS` records that the test is TENSE: row 23
           *       carries the outcome of the turn just taken, which a reply about the read just
           *       performed is. Naming `'info'` did not move the sentence to another line, because
           *       this mapset has only one; it painted the one line in a colour the mapset does not
           *       declare, which is the informational-looking alert the review found in the ERROR
           *       band.
           * WHY : Alternatives Considered: publishing it through the shell's `information` channel, so
           *       the sentence would leave the error band altogether. Rejected on the mapset:
           *       `app/bms/COTRN01.bms` declares no row-22 field at all -- `INFOMSG` appears in five
           *       of the twenty-one mapsets (`COACTUP`, `COACTVW`, `COCRDLI`, `COCRDSL`, `COCRDUP`)
           *       and not in this one -- and `ShellMessageSlot.information` in
           *       `ui/src/layout/AppShell.tsx` states that the member's absence means exactly that.
           *       Delegating it here would paint a terminal row this screen never had.
           * WHY : Alternatives Considered: discarding the member on this arm, which is the strictest
           *       reading of a reference that sets no success message. Rejected because
           *       `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`
           *       L2473-L2479 declares the member as the view screen's own message line and records
           *       that null is merely the ORDINARY value, so a non-null one is a sentence the service
           *       chose to send an operator; dropping it would suppress it with nothing recording that
           *       it had been sent.
           */
          setMessage(detail.returnMessage);
          setLoading(false);
        },
        /**
         * Reports a failed lookup, unless a later turn has superseded this read.
         * @param {unknown} reason - The value the read rejected with: an error carrying the normalised
         *   problem document, or a `RangeError` from the client's own response checks.
         * @returns {void} Nothing; the refusal is published through this screen's own state.
         */
        (reason: unknown): void => {
          if (issuedReads.current !== issued) {
            return;
          }
          setRecord(null);
          setRefusal(fieldRefusalFrom(reason));
          setMessage(describeLookupFailure(reason));
          setLoading(false);
          focusLookup();
        },
      );
    },
    [focusLookup],
  );

  useEffect(
    /**
     * Runs the deep-link arrival: pre-fills the control from the route and reads at once.
     *
     * Refactoring Rationale: this replaces the positional COMMAREA overlay the reference used to pass
     * a selection between two programs. `app/cbl/COTRN00C.cbl` L183-L195 wrote the selection flag and
     * `CDEMO-CT00-TRN-SELECTED` and transferred control, and `app/cbl/COTRN01C.cbl` L103-L108 read
     * `CDEMO-CT01-TRN-SELECTED` back out of the same storage and performed the read. The identifier
     * travels as an explicit path parameter instead, which makes the request self-describing and
     * independently authorizable rather than trusting storage the client echoed back (AAP section
     * 0.7.1).
     *
     * Assumptions: an ABSENT parameter does nothing at all, which is the reference's other first
     * entry -- L109 sends the empty map and waits for a key. Clearing state here instead would blank a
     * record an operator had just looked up by hand on the parameterless route.
     * @returns {void} Nothing; the read publishes through this screen's own state.
     */
    (): void => {
      if (id === undefined) {
        return;
      }
      setEntry(id);
      runLookup(id);
    },
    [id, runLookup],
  );

  const handleFetch = useCallback(
    /**
     * Runs the lookup for whatever the control currently holds.
     * @returns {void} Nothing; the outcome is published through this screen's own state.
     */
    (): void => {
      runLookup(entry);
    },
    [entry, runLookup],
  );

  const handleClear = useCallback(
    /**
     * Runs `CLEAR-CURRENT-SCREEN` (`app/cbl/COTRN01C.cbl` L301-L304 into L309-L326).
     *
     * Assumptions: all sixteen things the reference blanks are blanked -- the lookup key, the thirteen
     * displayed values and `WS-MESSAGE` -- and the cursor returns to the key field, which is the
     * `MOVE -1 TO TRNIDINL` at L311. The field refusal goes with the message because the two are one
     * rendering of one refusal, and `INITIALIZE-ALL-FIELDS` leaves no refused field behind it.
     *
     * Assumptions: the issue counter is advanced, so a read still in flight cannot land on a screen
     * the operator has just cleared. The reference cannot have this problem -- a CICS turn is
     * synchronous -- so this is a consequence of the transport rather than a transcription.
     * @returns {void} Nothing; the outcome is published through this screen's own state.
     */
    (): void => {
      issuedReads.current += 1;
      setEntry('');
      setRecord(null);
      setMessage(null);
      setRefusal(null);
      setLoading(false);
      focusLookup();
    },
    [focusLookup],
  );

  /*
   * WHY : Assumptions: PF3's destination is the ORIGIN the departing screen handed over, falling back
   *       to the main menu -- which is exactly the branch at `app/cbl/COTRN01C.cbl` L115-L122: it
   *       moves `'COMEN01C'` into `CDEMO-TO-PROGRAM` when `CDEMO-FROM-PROGRAM` is blank and otherwise
   *       returns to that program.
   * WHY : Alternatives Considered: the browser's own history, through `navigate(-1)`. Rejected for the
   *       reason `ui/src/routes/navigation.ts` records on `ScreenTransitionState.from`: a history
   *       entry is not a NAMED origin and need not even belong to this application, so going back one
   *       entry can leave the application entirely, and a screen reached by typing its address has no
   *       previous entry at all. The handed-over origin is validated against the route table by
   *       `inApplicationRoute`, so an origin that is not a route this application serves falls to the
   *       menu rather than being navigated to.
   * WHY : Assumptions: the browse is the producer of that origin, which is what makes both arms of
   *       this expression reachable. `ui/src/screens/transactionList/index.tsx` hands over
   *       `TRANSACTION_LIST_ROUTE` on the arm that opens a selected row -- the target of
   *       `app/cbl/COTRN00C.cbl` L190 and L191, which move `WS-TRANID` and `WS-PGMNAME` into the
   *       reference's own `CDEMO-FROM-*` carriers before transferring -- and that route is one of the
   *       parameterless application routes `inApplicationRoute` admits. A deep link, a reload and the
   *       parameterless route carry no origin at all and take the fallback, which is the same arm the
   *       reference takes when `CDEMO-FROM-PROGRAM` is blank.
   */
  const backDestination =
    inApplicationRoute(screenTransitionState(location.state).from) ?? MAIN_MENU_ROUTE;

  const handleBack = useCallback(
    /**
     * Returns to the route that handed this screen over, or to the main menu when none did.
     * @returns {void} Nothing; the transition is performed as a side effect on the router.
     */
    (): void => {
      navigateSafely(navigate, backDestination);
    },
    [navigate, backDestination],
  );

  const handleBrowse = useCallback(
    /**
     * Opens the transaction browse, which is what PF5 means on this screen.
     * @returns {void} Nothing; the transition is performed as a side effect on the router.
     */
    (): void => {
      navigateSafely(navigate, TRANSACTION_LIST_ROUTE);
    },
    [navigate],
  );

  const { bindings, invoke } = usePfKeys(
    buildTransactionDetailKeyHandlers({
      onFetch: handleFetch,
      onBack: handleBack,
      onClear: handleClear,
      onBrowse: handleBrowse,
    }),
    {
      /**
       * Reports a recognised key this screen does not bind.
       *
       * Assumptions: the sentence is taken from the rejection PAYLOAD rather than composed here,
       * because `ui/src/layout/usePfKeys.ts` carries the byte-preserved `CCDA-MSG-INVALID-KEY` on it
       * -- `'Invalid key pressed. Please see below...         '` at `app/cpy/CSMSG01Y.cpy` L20-L21,
       * blank-padded to its declared `PIC X(50)`. Writing the string again here would be a second copy
       * of a verbatim constant, which is what the catalog exists to prevent. What the screen still
       * owns is the CHANNEL it goes to, which is the reference's own arrangement: L129-L131 sets the
       * flag and moves the message into `WS-MESSAGE`, the same field every other refusal on this
       * screen uses.
       *
       * ⚠️ Assumptions: the payload's `severity` member is deliberately NOT read, and reading it would
       * be indistinguishable from not reading it. `ui/src/layout/usePfKeys.ts` L149-L150 declares it as
       * the literal type `'error'` and L489 is the one site that constructs it, so it can hold no other
       * value -- and that value is what `MESSAGE_BAND_CHANNELS` in `ui/src/layout/MessageBand.tsx` already
       * resolves for row 23. What
       * withdrawing the read buys is that this screen no longer holds a severity at all, so no arm of
       * it can paint the row-23 line in a colour `app/bms/COTRN01.bms` L260 does not declare.
       * @param {{ message: string; severity: 'error' }} rejection - The invalid-key payload, carrying
       *   the verbatim sentence and the severity a screen-level status renderer expects.
       * @returns {void} Nothing; the sentence is published through this screen's own state.
       */
      onInvalidKey: (rejection): void => {
        setMessage(rejection.message);
        focusLookup();
      },
    },
  );

  /*
   * WHY : Refactoring Rationale: the title band, the row-23 message line and the row-24 legend are
   *       DELEGATED to the one `AppShell` that `ui/src/App.tsx` mounts, rather than composed here.
   *       Per-screen composition is what this tree did before the shell was wired in; keeping it
   *       afterwards would render a second title band, a second live message region and a second named
   *       legend landmark on this screen.
   * WHY : Assumptions: the delegation is made UNCONDITIONALLY and above the early return below,
   *       because a hook called after an early return changes hook order between renders -- which
   *       React reports as a broken component rather than as a missing band. The header identifiers
   *       are the program's own, `WS-TRANID` and `WS-PGMNAME` at `app/cbl/COTRN01C.cbl` L36-L37, which
   *       is what `POPULATE-HEADER-INFO` moves into the map at L249-L250.
   * WHY : Assumptions: no `legendColor` is delegated. `app/bms/COTRN01.bms` L264 paints this screen's
   *       row-24 field `COLOR=YELLOW`, which is the slot's own default and the 15-of-17 majority, so
   *       stating it would restate a default.
   */
  useShellSlot({
    screen: {
      transactionId: TRANSACTION_DETAIL_TRANSACTION_ID,
      programName: TRANSACTION_DETAIL_PROGRAM_NAME,
    },
    now: paintedAt,
    /*
     * WHY : ⚠️ Assumptions: NO `severity` is delegated and no `line` either, so the band resolves both
     *       from `MESSAGE_BAND_CHANNELS` -- the row-23 `error` channel, whose default severity is the
     *       `COLOR=RED` this mapset declares at `app/bms/COTRN01.bms` L260. Naming either one would
     *       restate a default, and naming a severity is what let this screen paint an informational
     *       alert in the outcome band.
     */
    message: { text: message, mapset: TRANSACTION_DETAIL_MAPSET },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  /*
   * WHY : Assumptions: every colour below is the mapset's own measured role, resolved through
   *       `BMS_TEXT_COLOR_TOKENS` and not through the hue map `BMS_COLOR_TOKENS`. The roles are
   *       `COLOR=NEUTRAL` on the row-4 caption (`app/bms/COTRN01.bms` L76), `COLOR=TURQUOISE` on the
   *       lookup label and the thirteen data labels (L81 and the twelve like it), `COLOR=GREEN` on the
   *       lookup control (L86) and `COLOR=BLUE` on the thirteen values (L106 and the twelve like it).
   *       The hue map's entries are FILL-grade anchors: read as text the turquoise one measures
   *       2.205:1 and the blue one 4.104:1 against the surface the shell paints, where WCAG AA asks
   *       4.5:1 -- so the text-grade bridge is what keeps the hue family while clearing the threshold.
   *       `ui/src/theme/tokens.ts` records the per-role measurement.
   * WHY : Assumptions: `ATTRB=BRT` on the caption (L75) is carried by WEIGHT and not by a colour,
   *       which is what keeps brightness and colour independent in the target the way the mapset has
   *       them independent -- every one of the mapset's bright fields already carries a colour of its
   *       own, so expressing brightness as a colour would collide on all of them.
   */
  const captionStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const labelStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const valueStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE] };
  /*
   * WHY : Assumptions: the fixed-pitch face is applied to the eight COLUMNAR values and withheld from
   *       the five free-text ones, which is the distinction `TYPOGRAPHY_TOKENS.fixedPitchData` records.
   *       The 3270 cell grid aligned every column for free; a proportional face gives digits different
   *       advance widths, so the sixteen-character identifier and masked card number, the two- and
   *       four-character codes, the twelve-character signed amount, the two ten-character dates and
   *       the nine-digit merchant identifier stop lining up down the value column.
   * WHY : ⚠️ Refactoring Rationale: the discriminator is whether a value is COLUMNAR -- an identifier,
   *       a code, an amount or a timestamp, which an operator scans against the like value above and
   *       below it -- and not whether its `PICTURE` has a fixed width. Every field in a COBOL record
   *       has a fixed width, so the width reading admits all thirteen and settles nothing. The
   *       measured consequence of the width reading was the merchant's postal code in the code face
   *       beside the merchant's city in the body face: at the two-column width those two are the two
   *       cells of ONE row, and a browser review measured them side by side holding identical text in
   *       two typefaces. The mapset draws no such distinction -- `app/bms/COTRN01.bms` L240-L243 and
   *       L252-L255 give `MCITY` and `MZIP` the same `ATTRB=(ASKIP,NORM)` and the same `COLOR=BLUE`,
   *       differing only in `LENGTH` and `POS` -- and neither is a column: a city and a postal code
   *       are the two halves of one address, read together and compared with nothing.
   * WHY : Assumptions: `ui/src/screens/accountView/index.tsx` is the precedent rather than a second
   *       opinion. It renders its own postal code with `monetary: false` (L992-L995) and records at
   *       L1137-L1139 that the code face is "applied only to monetary values so the decimal points
   *       line up down the column" -- the same columnar test, reached independently on the record
   *       screen that has five money fields to align.
   * WHY : Alternatives Considered: moving the merchant CITY into the code face instead, which would
   *       also remove the split. Rejected because it takes the wrong half: `TRAN-MERCHANT-CITY` is
   *       `PIC X(50)` proportional text and the mapset paints only 25 of it, so the code face would
   *       make a truncated place name wider and no better aligned.
   * WHY : Alternatives Considered: setting the face on the `Descriptions` component so every value
   *       inherited it. Rejected because it would put the hundred-character description in the code
   *       face as well, which aligns nothing and reads worse than the body face at that length.
   */
  const fixedPitchValueStyle: CSSProperties = {
    ...valueStyle,
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  /*
   * WHY : Assumptions: the free-text values break inside a word rather than overflow their cell. Four
   *       of the five arrive WIDER than the field the terminal painted -- the description is `X(100)`
   *       in a `LENGTH=60` field and the merchant name and city are `X(50)` in fields of 30 and 25 --
   *       and a bordered table cell has no fixed character width to spill out of gracefully. Breaking
   *       is preferred to truncating for the reason recorded on
   *       {@link TRANSACTION_DETAIL_SCREEN_WIDTHS}: every character the service sent stays readable.
   * WHY : Trade-offs: the fifth, the postal code, cannot reach the breaking rule -- `X(10)` in a
   *       `LENGTH=10` field at `app/bms/COTRN01.bms` L254 is exactly the width the terminal painted,
   *       so it has nothing to overflow. It shares this style anyway rather than taking a third style
   *       object of its own, because a third object would exist solely to withhold a declaration that
   *       is inert on the one field it applies to, and would give a reader a third policy to
   *       reconcile against a mapset that declares only two.
   */
  const freeTextValueStyle: CSSProperties = { ...valueStyle, overflowWrap: 'break-word' };
  /*
   * WHY : Assumptions: the lookup control is GREEN per `app/bms/COTRN01.bms` L86 and is rendered in
   *       the code face because it holds a sixteen-character fixed-width key. `HILIGHT=UNDERLINE` at
   *       L87 gets no token at all: that is documented gap G4 in the `DESIGN_GAPS` register of
   *       `ui/src/theme/tokens.ts`, because a terminal underline was its only way to show a field
   *       accepts input and the antd `Input` border already carries that affordance structurally.
   */
  const lookupControlStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.GREEN],
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  /*
   * WHY : Assumptions: the rule's colour is set through the LOGICAL edge, `border-block-start-color`,
   *       rather than `border-top-color`. The design-system invariants require logical properties
   *       throughout and antd draws a horizontal `Divider` with `border-block-start`, so naming the
   *       logical edge overrides the colour the component already sets instead of adding a second,
   *       physical declaration that would agree with it only in a left-to-right document. The role is
   *       the mapset's own `COLOR=NEUTRAL` at `app/bms/COTRN01.bms` L95, and it resolves through the
   *       hue map here rather than the text bridge because a border is not text.
   */
  const ruleStyle: CSSProperties = { borderBlockStartColor: cssVar[BMS_COLOR_TOKENS.NEUTRAL] };
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the caption is rendered by `ScreenTitle`, which supplies the heading RANK and the
       * size from one bridge entry for every screen, while this screen supplies the colour and weight
       * its own mapset measures. That split is what stops a screen restating the size in order to set
       * its colour.
       */}
      <ScreenTitle style={captionStyle}>{TRANSACTION_DETAIL_TITLE}</ScreenTitle>
      {/*
       * WHY : Assumptions: `Form` wraps the single control purely so it can carry the design system's
       *       own field-error treatment, and it has `component="div"` so no `<form>` element is
       *       emitted. There is no submit here: the turn is taken by Enter through the key bindings,
       *       which is the only way the reference takes one, and a nested submit button would offer a
       *       second control for one action.
       */}
      <Form layout="vertical" component="div">
        <Form.Item
          label={
            <Typography.Text style={labelStyle}>{TRANSACTION_DETAIL_LOOKUP_LABEL}</Typography.Text>
          }
          htmlFor={TRANSACTION_ID_FIELD_ID}
          {...refusalPropsFor(refusal)}
        >
          {/*
           * WHY : Assumptions: `autoFocus` is on this control and on NO other element in this module,
           *       because `TRNIDIN` carries the mapset's single `IC` attribute at
           *       `app/bms/COTRN01.bms` L85 -- the one field the 3270 placed the cursor in on a sent
           *       map. Later turns move the cursor through the ref instead, which is what
           *       `focusLookup` above does for the reference's `MOVE -1 TO TRNIDINL` arms.
           * WHY : Alternatives Considered: `InputNumber` with `stringMode`. Rejected on two grounds:
           *       the receiving field is `TRAN-ID PIC X(16)`, a CHARACTER key whose leading zeroes are
           *       part of it, and a numeric control cannot hold the literal `'*'` marker the blank
           *       refusal renders in the field. A text control needs no `stringMode` to stay clear of
           *       an IEEE-754 double, because it never holds a number at all.
           * WHY : Assumptions: the `'*'` marker rides in the control's `suffix` when the refusal is
           *       BLANK, which is the direct analogue of `app/cpy/CSSETATY.cpy` L24 moving a literal
           *       `'*'` into the field itself. It is `aria-hidden` because the same refusal is already
           *       announced by the `help` element the `aria-describedby` above points at, and reading
           *       an asterisk aloud after the sentence adds nothing. The copybook gates its highlight
           *       on `CDEMO-PGM-REENTER` at L18-L20; the target has no such flag (AAP section 0.7.1),
           *       so the treatment is driven purely by the current refusal state.
           */}
          <Input
            id={TRANSACTION_ID_FIELD_ID}
            ref={lookupControl}
            autoFocus
            value={entry}
            maxLength={TRANSACTION_ID_ENTRY_WIDTH}
            style={lookupControlStyle}
            {...fieldAriaProps(TRANSACTION_ID_FIELD_ID, {
              invalid: refusal !== null,
              hasError: refusal !== null,
              hasHint: false,
            })}
            {...(refusal?.state === 'BLANK'
              ? {
                  suffix: (
                    <Typography.Text aria-hidden="true" style={blankMarkerStyle}>
                      {FIELD_ERROR_TOKENS.blankMarker}
                    </Typography.Text>
                  ),
                }
              : {})}
            onChange={
              /**
               * Records the identifier the operator typed or pasted.
               * @param {ChangeEvent<HTMLInputElement>} event - Change event whose target value is the
               *   control's new text, already capped at the field's declared width by `maxLength`.
               * @returns {void} Nothing; the entry is published through this screen's own state.
               */
              (event: ChangeEvent<HTMLInputElement>): void => {
                setEntry(event.target.value);
              }
            }
          />
        </Form.Item>
      </Form>
      {/*
       * WHY : Refactoring Rationale: the mapset's row-8 rule is a `Divider` and NOT its literal
       *       seventy hyphens (`app/bms/COTRN01.bms` L94-L99). A run of hyphens is a terminal's only
       *       way to draw a horizontal line, so the characters are the IMPLEMENTATION of a separator
       *       rather than content -- carrying them across verbatim would put seventy hyphens into the
       *       accessibility tree for a screen reader to spell out, and would not reflow at any width
       *       narrower than seventy monospace columns. `Divider` is the semantic separator the design
       *       system provides and it needs no `aria-hidden`, because it already carries the separator
       *       role the hyphens were standing in for.
       * WHY : Alternatives Considered: a styled raw `div` with a border. Rejected because it would
       *       violate the library-components-over-raw-HTML rule for an element antd already provides,
       *       and would leave the separator with no role for assistive technology.
       */}
      <Divider style={ruleStyle} />
      {/*
       * WHY : Alternatives Considered: replacing the whole body with a spinner while a read is in
       *       flight, which is what `ui/src/screens/cardDetail/index.tsx` does. Rejected HERE because
       *       this screen's refusal arms all end by returning the cursor to the lookup control, and a
       *       body-level early return unmounts that control -- so the focus move would run against a
       *       detached ref and be lost on exactly the turns the reference is most careful about
       *       (`MOVE -1 TO TRNIDINL` at L287 and L294). Confining the spinner to the record region
       *       keeps the control mounted and, incidentally, keeps the caption and the key field from
       *       shifting when a record arrives.
       * WHY : Assumptions: the spinner is ADDITIVE and has no counterpart in the reference at all -- a
       *       3270 holds the previous map until the next one arrives, so there is no painted loading
       *       state to be faithful to. It is introduced because a browser read is asynchronous, which
       *       is a property of the transport rather than of the screen.
       */}
      {loading ? <Spin size="large" /> : null}
      {/*
       * WHY : ⚠️ Purpose: the spinner above is a VISUAL statement only, and this is the same statement
       *       made to an operator who cannot see it. antd's `Spin` carries no accessible name of its
       *       own and announces nothing, so a screen reader on this turn was told the record region
       *       had emptied and nothing about why or for how long.
       * WHY : Assumptions: the region is rendered on EVERY turn and holds the empty string while idle,
       *       which is what `busyAnnouncement` produces for an undefined announcement. A live region
       *       has to be in the accessibility tree before its content changes for the change to be
       *       announced at all, so rendering it only while loading would lose the one transition that
       *       matters.
       * WHY : Assumptions: the sentence is `REQUEST_IN_PROGRESS` from the catalog and not composed
       *       here. It has no reference source -- a 3270 locks the keyboard and says nothing, so there
       *       is no verbatim wording to carry across -- and the catalog is where an authored sentence
       *       is registered and width-checked so it cannot differ between two screens saying the same
       *       thing.
       */}
      {busyAnnouncement(loading ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * WHY : Trade-offs: the record is laid out by GROUPING and not by the mapset's absolute
       *       coordinates, which is documented gap G1 in the `DESIGN_GAPS` register of
       *       `ui/src/theme/tokens.ts`. All 56 `DFHMDF` definitions on this map carry an absolute
       *       `POS=(row,column)` on a fixed 24-by-80 character grid -- `TRNID` at `POS=(10,22)`,
       *       `TRNAMT` at `POS=(16,14)`, `MZIP` at `POS=(20,67)` -- and none of that survives. What is
       *       given up is character-cell fidelity; what is KEPT is field grouping, reading order and
       *       tab order, which are the properties an operator actually navigates by. Each item below
       *       sits in its mapset row's order and the rows follow in ascending row number, so the
       *       reading order is the one the terminal presented. Reproducing the grid was rejected on two
       *       specific grounds: absolute positioning cannot reflow, so the layout would break at any
       *       viewport narrower than eighty monospace columns, and a grid of positioned cells gives a
       *       screen reader no label-to-value association, whereas `Descriptions` emits each pair as a
       *       row a reader announces together.
       * WHY : Assumptions: the column count comes from `RECORD_VIEW_COLUMNS` rather than the literal
       *       `2` the AAP names, because that module states the policy once for every record screen --
       *       one column below the design system's medium breakpoint and two from it upward. Two-up at
       *       every width is what it exists to prevent: at a phone width the eight fixed-pitch values
       *       here are rendered in a face whose advance width cannot shrink, so a bordered two-column
       *       table pushes past the viewport instead of reflowing. The WIDE case is two columns, which
       *       is the mapset's own shape -- its label and value columns sit side by side down the body
       *       zone at the distinct `col` values the `POS=` operands show.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the grid is rendered UNCONDITIONALLY and falls back to
       *       {@link BLANK_TRANSACTION_RECORD}, where it was previously suppressed entirely while
       *       `record` was null. Runtime validation against the mapset found the suppression to be a
       *       parity defect rather than a simplification: the thirteen labels are `INITIAL=` literals
       *       in `app/bms/COTRN01.bms`, so the terminal paints them on first entry and keeps painting
       *       them after PF4 and after a refused lookup, while `INITIALIZE-ALL-FIELDS` at
       *       `app/cbl/COTRN01C.cbl` L309-L326 blanks the thirteen VALUES alone. Unmounting removed
       *       label and value together, which the baseline never does.
       * WHY : Trade-offs: this costs thirteen empty rows on a screen an operator has not yet queried,
       *       and it buys the cleared state being legible as cleared. The alternative -- an empty body
       *       region -- is indistinguishable from a screen that failed to respond, which matters most
       *       on precisely the paths that reach it: PF4, a not-found identifier and a lookup failure.
       *       It also keeps the body's height stable across a fetch, so the message band and the key
       *       legend below do not jump as a record arrives or is discarded.
       */}
      <Descriptions
        bordered
        column={RECORD_VIEW_COLUMNS}
        items={buildRecordItems(record ?? BLANK_TRANSACTION_RECORD, {
          labelStyle,
          freeTextValueStyle,
          fixedPitchValueStyle,
        })}
      />
    </Flex>
  );
}

/*
 * WHY : Assumptions: the default export exists ALONGSIDE the named one, and both are deliberate.
 *       `ui/src/router.tsx` loads a screen through `React.lazy`, which accepts only a module whose
 *       `default` key is the component -- a screen publishing only a named export needs an adapter
 *       wrapper at every mount site, and that file records that nine of its screens avoid the wrapper
 *       precisely by publishing a default. The named export is kept because the screen tests under
 *       `ui/src/test/**` and `ui/src/screens/**` import screens by name, and because a named symbol is
 *       what a stack trace and a React DevTools tree show.
 */
export default TransactionDetailScreen;
