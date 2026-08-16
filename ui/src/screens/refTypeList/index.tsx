/**
 * @file Transaction-type maintenance list: the browser replacement for BMS mapset `COTRTLI`
 * map `CTRTLIA` and the program that drives it,
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`.
 *
 * What this screen is
 * -------------------
 * The reference screen paints 81 `DFHMDF` fields on a fixed 24x80 terminal: two optional
 * search filters, a seven-row grid of transaction types whose descriptions are editable in
 * place, a separate row-19 entry area, two message lines and a five-key legend. An operator
 * types `U` or `D` beside exactly one row, presses ENTER to have the request validated and
 * echoed back as a confirmation prompt, then presses PF10 to commit it. This module
 * reproduces that behaviour as one route component mounted at `/reference/transaction-types`.
 *
 * Provenance
 * ----------
 * Every figure and every string below was measured from the baseline, which is
 * reference-only and never modified:
 *
 * - `app/app-transaction-type-db2/bms/COTRTLI.bms` - the field inventory: the row-4 heading
 *   at L75-L83, the two filters at L84-L105, the column headings at L108-L119, the seven
 *   data-row families at L133-L279, the distinct row-19 area at L280-L298, the two message
 *   fields at L301-L311 and the five legend fields at L312-L336.
 * - `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` - the behaviour: the row arity at L60,
 *   the action-code domain at L183-L188, the accepted key set at L575-L581, the PF10
 *   confirmation gate at L666-L678, the selection reducer at L1017-L1052, the filter edits
 *   at L1096-L1137, the field validator at L1186-L1231, the zero-row cross-edit at
 *   L1251-L1266, the per-row attribute pass at L1329-L1373, the message precedence at
 *   L1504-L1555 and the three data paragraphs at L1801-L1935.
 * - `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` - `TR_TYPE CHAR(2)` and
 *   `TR_DESCRIPTION VARCHAR(50)`, which corroborate the two field widths independently of
 *   the mapset.
 * - `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` L6-L7 - the `ON DELETE RESTRICT`
 *   foreign key. That constraint is the sole reason this screen has a conflict branch at
 *   all, and the reason that branch keeps its request pending rather than discarding it.
 * - `app/cpy/CSSETATY.cpy` L17-L27 - the field-error template, whose red highlight and
 *   literal `*` blank marker this screen renders through the design system.
 *
 * What this module owns, and what it does not
 * -------------------------------------------
 * It owns this screen's behaviour and the static labels the mapset paints as `INITIAL=`
 * operands. It owns no vocabulary that a shared module already publishes: the
 * attention-identifier domain and the PF13-PF24 aliasing come from
 * `ui/src/layout/usePfKeys.ts`, the keyset browse from `ui/src/hooks/usePagedQuery.ts`,
 * every sentence the program itself emits from `ui/src/messages/messages.ts`, and every
 * design value from the theme `ui/src/App.tsx` injects. It re-declares none of them.
 *
 * It also does not guard its own route. `/reference/transaction-types` is one of the
 * administrator-only routes `ui/src/routes/guards.tsx` already gates on the
 * `carddemo-admin` group claim, so this component assumes it is only ever rendered for an
 * administrator and renders no access-denied state of its own.
 */

import { Col, Flex, Form, Input, Popconfirm, Row, Space, Table, Typography, theme } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { isConflictFailure } from '../../api/client';
import {
  deleteTransactionType,
  listTransactionTypes,
  replaceTransactionType,
} from '../../api/reference';
import type {
  ApiError,
  FieldError,
  PageResponse,
  ReferenceListQuery,
  TransactionType,
} from '../../api/types';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { CICS_AIDS, usePfKeys } from '../../layout/usePfKeys';
import type { CicsAid, PfKeyHandlerMap } from '../../layout/usePfKeys';
import {
  FIELD_VALIDATION_SUFFIXES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
} from '../../messages/messages';
import { ADMIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import {
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  TYPOGRAPHY_TOKENS,
  characterCellWidthShare,
} from '../../theme/tokens';
import { fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import { ScreenTitle } from '../../layout/ScreenTitle';

/**
 * Sentences this screen's own program emits, from the single catalog that owns them.
 */
const LIST_MESSAGES = PROGRAM_MESSAGES.COTRTLIC;

/**
 * Status and prompt sentences this screen's program emits, each carrying its declared
 * width and originating line.
 */
const LIST_STATUS = STATUS_MESSAGES.COTRTLIC;

/**
 * Sentences the maintenance screen's program emits that this screen reuses.
 *
 * Assumptions: `ui/src/messages/messages.ts` assigns three of this screen's Db2 failure
 * texts to these entries rather than transcribing the baseline's own. The baseline decorated
 * each failure with the SQL code and the formatted diagnostic text through
 * `9999-FORMAT-DB2-MESSAGE`, and the catalog records those literals as redacted precisely
 * because reproducing them would publish a database diagnostic to a browser. These are the
 * replacements it names, so using them is what keeps this screen inside both the
 * verbatim-text rule and the prohibition on leaking a status code.
 */
const MAINTENANCE_STATUS = STATUS_MESSAGES.COTRTUPC;

/**
 * Transaction identifier the reference program paints on row 1.
 *
 * Assumptions: `LIT-THISTRANID` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L44.
 */
export const REF_TYPE_LIST_TRANSACTION_ID = 'CTLI';

/**
 * Program name the reference screen paints on row 2.
 *
 * Assumptions: `LIT-THISPGM` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L43.
 */
export const REF_TYPE_LIST_PROGRAM_NAME = 'COTRTLIC';

/**
 * Mapset whose message-band width the shared band applies to this screen.
 *
 * Assumptions: `ui/src/messages/messages.ts` records `COTRTLI` with a display width of 78,
 * matching `ERRMSG LENGTH=78` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L308-L311.
 */
const REF_TYPE_LIST_MAPSET = 'COTRTLI';

/*
 * WHY : Assumptions: the code is named rather than written at the comparison, because it identifies a
 *       CLASS of answer this screen has to tell apart from a fault. `ApiError.CODE_VALIDATION` in
 *       `services/common-lib` declares it, and every service raises it through `ClientInputException`,
 *       so it is the one code that means "the caller can correct this by typing".
 * WHY : Alternatives Considered: branching on `browse.error.status === 400` instead. Rejected because a
 *       status is coarser than the code: several distinct refusals share 400, and the error model's own
 *       four-digit codes are what distinguish them.
 */

/** The problem code every service raises for input a caller can correct. */
const VALIDATION_ERROR_CODE = 'CARDDEMO-0400';

/*
 * WHY : Assumptions: these are the SERVICE's response-field identities and not this screen's control
 *       identifiers. `TransactionTypeService.FIELD_TYPE_CODE` and `FIELD_DESCRIPTION` declare them as
 *       `typeCode` and `description`, which are the request properties the two filters travel in, and
 *       the error model keys a field error by the property its value arrived in. Mapping them to control
 *       ids happens where the controls are rendered, so a rename on either side stays local.
 */

/** The request-property names the service reports filter refusals against. */
const SERVICE_FILTER_FIELDS = Object.freeze({
  typeCode: 'typeCode',
  description: 'description',
});

/**
 * Rows one page of this browse holds.
 *
 * Assumptions: seven, corroborated four independent ways in
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` - the constant
 * `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7` at L60, the selection array
 * `WS-EDIT-SELECT PIC X(1) OCCURS 7 TIMES` at L178-L182, the parallel error array at
 * L190-L194, and the seven `TRTSEL1`-`TRTSEL7` field families at
 * `app/app-transaction-type-db2/bms/COTRTLI.bms` L133-L279. The row-19 area at L280-L298 is
 * excluded from the count because it is three separately named fields rather than an eighth
 * element of those arrays, which is also why {@link RefTypeListScreen} renders it outside
 * the grid.
 */
export const REF_TYPE_PAGE_SIZE = 7;

/**
 * Characters the two-character type code admits.
 *
 * Assumptions: `TRTYPE LENGTH=2` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L89-L93
 * and `TR_TYPE CHAR(2) NOT NULL` in `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2. Two
 * sources agreeing is why this is a constant rather than a literal at the call site.
 */
export const TYPE_CODE_LENGTH = 2;

/**
 * Characters the description admits, as filter text and as an edited value.
 *
 * Assumptions: `TRDESC LENGTH=50` and `TRTYPD1`-`TRTYPD7 LENGTH=50` at
 * `app/app-transaction-type-db2/bms/COTRTLI.bms` L101-L105 and L147-L277, corroborated by
 * `TR_DESCRIPTION VARCHAR(50) NOT NULL` in `app/app-transaction-type-db2/ddl/TRNTYPE.ddl`
 * L3, and passed as the edit length at `COTRTLIC.cbl` L1085.
 */
export const DESCRIPTION_LENGTH = 50;

/**
 * Characters the per-row action cell admits.
 *
 * Assumptions: `TRTSEL1`-`TRTSEL7 LENGTH=1` at
 * `app/app-transaction-type-db2/bms/COTRTLI.bms` L133-L263, matching
 * `WS-EDIT-SELECT PIC X(1)` at `COTRTLIC.cbl` L181.
 */
export const ACTION_CODE_LENGTH = 1;

/**
 * One of the two action codes a row may carry.
 *
 * Assumptions: a screen-local literal union, deliberately not exported as a shared type. The
 * reasoning is recorded on {@link REF_TYPE_ROW_ACTION_CODES}.
 */
type RowActionCode = 'D' | 'U';

/**
 * The two action codes this screen accepts, and nothing else.
 *
 * Assumptions: `88 SELECT-OK VALUES 'D', 'U'` with
 * `88 DELETE-REQUESTED-ON VALUE 'D'` and `88 UPDATE-REQUESTED-ON VALUE 'U'` at
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L183-L185.
 *
 * Alternatives Considered: importing a shared row-action helper from the card list, which
 * looks like the same construct and is not. `app/cbl/COCRDLIC.cbl` L77-L79 declares
 * `88 SELECT-OK VALUES 'S', 'U'` - a VIEW code where this screen has a DELETE code -
 * so the two domains overlap in one letter and disagree in the other. A shared helper would
 * have to admit `S`, `U` and `D` together, which would make `S` silently acceptable here
 * and `D` silently acceptable there, and the reference rejects each with
 * `'Action code selected is invalid'`. The domains are therefore declared per screen and
 * this one is deliberately not exported for reuse.
 */
export const REF_TYPE_ROW_ACTION_CODES = Object.freeze({
  delete: 'D',
  update: 'U',
} as const) satisfies Readonly<Record<string, RowActionCode>>;

/**
 * Narrows an action-cell entry to one of the two accepted codes.
 *
 * ⚠️ Refactoring Rationale: the comparison is BYTE-EXACT and the caller no longer folds case. It
 * did, on the ground that a browser control does not upper-case for free and a lower-case `d` plainly
 * means delete -- but the reference does not agree, and the difference is observable behaviour rather
 * than presentation. `88 SELECT-OK VALUES 'D', 'U'` at
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L183 tests the raw `PIC X(1)` byte, so a lower-case
 * entry falls to the `WHEN OTHER` arm and is refused with `'Action code selected is invalid'` moved at
 * L1038. Folding case made this screen accept a turn the reference refuses, which is a functional-parity
 * divergence, and no entry in `docs/architecture/cobol-to-service-traceability.md` registers one.
 *
 * Assumptions: the entry is still TRIMMED by the caller, and trimming is not case folding. A 3270 field
 * delivers unentered positions as spaces or low values -- `88 SELECT-BLANK VALUES ' ', LOW-VALUES` at
 * L186-L188 is a distinct arm from `WHEN OTHER` and does not refuse -- so treating surrounding blanks as
 * "nothing typed" reproduces the reference, while accepting a different byte would not.
 *
 * Trade-offs: an operator with caps lock off now sees the reference's refusal instead of a silent
 * promotion of their keystroke. That is the intended exchange: the sentence names the fault and the
 * entry is one character to retype, whereas the accepted lower-case byte made the browser and the
 * mainframe disagree about the same page for the same input.
 *
 * Trade-offs: this exists so the two codes are recognised without a type assertion anywhere.
 * Returning the frozen constants rather than the argument is what makes the narrowing sound to
 * the compiler, at the cost of two comparisons instead of a cast.
 * @param {string} entry - A trimmed action-cell entry, in the case it was typed.
 * @returns {RowActionCode | null} The recognised code, or `null` for a blank or invalid entry.
 */
function toRowActionCode(entry: string): RowActionCode | null {
  if (entry === REF_TYPE_ROW_ACTION_CODES.delete) {
    return REF_TYPE_ROW_ACTION_CODES.delete;
  }
  if (entry === REF_TYPE_ROW_ACTION_CODES.update) {
    return REF_TYPE_ROW_ACTION_CODES.update;
  }
  return null;
}

/**
 * Static text the mapset paints as `INITIAL=` operands, reproduced byte for byte.
 *
 * Assumptions: these live here rather than in `ui/src/messages/messages.ts` because that
 * module's remit is the text a PROGRAM emits into `WS-INFO-MSG` or `WS-RETURN-MSG`, and
 * every entry below is instead a constant painted by the MAPSET and never assigned at run
 * time. Its own overview draws that line for the function-key legends and assigns them to
 * the presentation layer for the same reason; these labels follow that precedent, and each
 * one cites the mapset line it was copied from so the two can be re-diffed.
 *
 * Assumptions: `selectColumn` really does carry four trailing spaces.
 * `app/app-transaction-type-db2/bms/COTRTLI.bms` L108-L111 declares
 * `LENGTH=10 INITIAL='Select    '` over a six-character word, and the padding is inside the
 * literal rather than a consequence of the field width. It is preserved here and trimmed
 * only where it is used as an accessible name, because a name with trailing blanks is
 * announced with them.
 */
export const REF_TYPE_LIST_LABELS = Object.freeze({
  /** Row-4 heading; `COLOR=NEUTRAL LENGTH=25` at `COTRTLI.bms` L75-L78. */
  heading: 'Maintain Transaction Type',
  /** Row-4 page-number prefix; `LENGTH=5` at `COTRTLI.bms` L79-L81. */
  pagePrefix: 'Page ',
  /** Row-6 filter prompt; `COLOR=TURQUOISE LENGTH=12` at `COTRTLI.bms` L84-L88. */
  typeFilter: 'Type Filter:',
  /** Row-8 filter prompt; `COLOR=TURQUOISE LENGTH=19` at `COTRTLI.bms` L96-L100. */
  descriptionFilter: 'Description Filter:',
  /** Row-10 heading; `COLOR=NEUTRAL LENGTH=10` at `COTRTLI.bms` L108-L111. */
  selectColumn: 'Select    ',
  /** Row-10 heading; `COLOR=NEUTRAL LENGTH=4` at `COTRTLI.bms` L112-L115. */
  typeColumn: 'Type',
  /** Row-10 heading; `COLOR=NEUTRAL LENGTH=11` at `COTRTLI.bms` L116-L119. */
  descriptionColumn: 'Description',
});

/**
 * The five legend labels the mapset paints on row 24, byte for byte.
 *
 * Assumptions: five discrete `DFHMDF` fields rather than one legend string -
 * `BUTNF02` through `BUTNF10` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L312-L336,
 * every one `COLOR=TURQUOISE ATTRB=(ASKIP,NORM)`.
 *
 * Assumptions: `PFK07` and `PFK08` are stated here even though
 * `ui/src/layout/PfKeyBar.ts` publishes defaults for both. Those defaults are
 * `F7=Backward` and `F8=Forward`, measured from the 17 base mapsets; this extension mapset
 * paints `F7=Page Up` and `F8=Page Dn` instead, so taking the defaults would render two
 * labels this screen does not have. The override is the whole reason these two entries
 * exist.
 */
export const REF_TYPE_LIST_KEY_LABELS = Object.freeze({
  /** `BUTNF02 LENGTH=7 INITIAL='F2=Add'` at `COTRTLI.bms` L312-L316. */
  PFK02: 'F2=Add',
  /** `BUTNF03 LENGTH=7 INITIAL='F3=Exit'` at `COTRTLI.bms` L317-L321. */
  PFK03: 'F3=Exit',
  /** `BUTNF07 LENGTH=10 INITIAL='F7=Page Up'` at `COTRTLI.bms` L322-L326. */
  PFK07: 'F7=Page Up',
  /** `BUTNF08 LENGTH=10 INITIAL='F8=Page Dn'` at `COTRTLI.bms` L327-L331. */
  PFK08: 'F8=Page Dn',
  /** `BUTNF10 LENGTH=8 INITIAL='F10=Save'` at `COTRTLI.bms` L332-L336. */
  PFK10: 'F10=Save',
});

/**
 * Route this screen sends an operator to when they ask to add a type.
 *
 * Assumptions: the reference transfers control rather than inserting a row in the grid.
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L630-L652 moves `LIT-ADDTPGM` - declared
 * `'COTRTUPC'` at L50 - into `CDEMO-TO-PROGRAM` and issues `EXEC CICS XCTL`, which under
 * transformation rule T5 is a client-side route change to the sibling maintenance screen.
 *
 * Assumptions: the final segment is a sentinel rather than a code, because the reference
 * arrives at the maintenance program with `CDEMO-PGM-ENTER` set at L635 and no key, and
 * that program then prompts for one. A three-character sentinel cannot be mistaken for a
 * real key: `TR_TYPE` is `CHAR(2)` in `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2, so
 * no code can ever be `new`.
 */
export const REF_TYPE_ADD_ROUTE = '/reference/transaction-types/new';

/**
 * Identifier tying the type-filter prompt to its control as an accessible name.
 *
 * Assumptions: the mapset identified the prompt by position - `POS=(6,30)` immediately left
 * of `POS=(6,44)` - and position names nothing to a screen reader, so the prompt is
 * associated explicitly instead.
 */
const TYPE_FILTER_LABEL_ID = 'ref-type-list-type-filter-label';

/** Identifier tying the description-filter prompt to its control, as above. */
const DESCRIPTION_FILTER_LABEL_ID = 'ref-type-list-description-filter-label';

/** Identifier of the type-filter control itself, so its prompt can point a `for` at it. */
const TYPE_FILTER_INPUT_ID = 'ref-type-list-type-filter';

/** Identifier of the description-filter control itself, as above. */
const DESCRIPTION_FILTER_INPUT_ID = 'ref-type-list-description-filter';

/*
 * WHY : ⚠️ Assumptions: the row editor needs an identifier of its own because its refusal has to be
 *       ANNOUNCED against it, and the editor had none at all -- so `help` rendered a sentence beside a
 *       control with no association to it, which a screen reader reaching the input never reads. The id
 *       is derived from the row key rather than fixed, because the grid renders up to seven rows and a
 *       fixed id would repeat; only one is ever an editor at a time, but a duplicate id in the document
 *       makes `aria-describedby` resolve to whichever came first.
 * WHY : Assumptions: the key is safe to interpolate. `TransactionType.typeCd` is a two-character code the
 *       contract constrains to digits, so it contributes no character an attribute selector would treat
 *       specially.
 */

/**
 * Builds the identifier of the action cell beside one row.
 *
 * ⚠️ Refactoring Rationale: the action cells had NO identifiers, and the grid renders up to
 * seven of them. A refused turn coloured every contributing cell red and put one sentence on the
 * message band, so which of the seven was at fault was carried by colour alone -- unavailable to a
 * screen reader and to an operator who cannot distinguish the hue. Deriving the identifier from the
 * row key gives each cell something its refusal text can be attached to, exactly as the description
 * editor beside it already had.
 *
 * Assumptions: derived from the row key rather than from the row's ORDINAL, because the seven grid
 * positions hold different rows after a page turn while a `typeCd` names the same record on every page.
 * An ordinal-keyed identifier would move a refusal onto whatever row later occupied that position.
 * @param {string} typeCd - Key of the row whose action cell is being addressed.
 * @returns {string} The identifier that cell renders with.
 */
function actionCellId(typeCd: string): string {
  return `ref-type-list-action-${typeCd}`;
}

/**
 * Builds the identifier of the description editor open on one row.
 * @param {string} typeCd - Key of the row whose editor is open.
 * @returns {string} The identifier that editor renders with.
 */
function descriptionEditorId(typeCd: string): string {
  return `ref-type-list-description-${typeCd}`;
}

/**
 * Character cells the mapset reserves for each per-row column, and for the row they span.
 *
 * These are MEASUREMENTS of the source contract, not design values: each is a count of
 * character cells read straight off the `POS` and `LENGTH` operands at
 * `app/app-transaction-type-db2/bms/COTRTLI.bms` L133-L279. The action field sits at column 6
 * and the key field at column 17, so the action column owns cells 6-16, eleven of them; the
 * key field sits at column 17 and the description at column 25, so the key column owns cells
 * 17-24, eight of them; and the description is `LENGTH=50` from column 25, so it owns cells
 * 25-74. Eleven plus eight plus fifty is the sixty-nine cells the three span together.
 *
 * Assumptions: the same three counts drive the row-19 add fields, so those line up beneath
 * the columns they mirror exactly as the mapset paints them -- row 19 repeats the columns of
 * rows 12-18.
 */
const COLUMN_CELL_COUNTS = Object.freeze({
  /** Cells 6-16 of the row, carrying the one-character action field. */
  action: 11,
  /** Cells 25-74 of the row, carrying the fifty-character description field. */
  description: 50,
  /** Cells 17-24 of the row, carrying the two-character key field. */
  typeCd: 8,
  /** Cells the three columns span together, which the three shares are taken over. */
  span: 69,
} as const);

/**
 * Share of the row width each of the three per-row columns occupies.
 *
 * Refactoring Rationale: the three values were percentage LITERALS here, under a local
 * exemption arguing that a contract-derived proportion is not a design value. The premise was
 * right and the placement was not: a rendered CSS length may not be spelled in a screen, so the
 * derivation moved to `characterCellWidthShare` in `ui/src/theme/tokens.ts`, which is where the
 * resolutions of design gap G1 live. What remains here is the measurement -- counts of character
 * cells anyone can check against the mapset -- and the length is computed from it.
 *
 * Alternatives Considered: letting the design system size the columns from their content, which
 * is what it does when no width is given. Rejected on measurement -- it gave the one-character
 * action column 1101px and the fifty-character description only 511px, because an unconstrained
 * `Input` reports a full-width intrinsic size regardless of how few characters it accepts. That
 * inverts the source's proportions and puts the widest control on the narrowest field. Deriving
 * the shares from the mapset's own geometry keeps the reading order and the relative emphasis an
 * operator is used to, without reintroducing the absolute character positioning that design gap
 * G1 deliberately gives up.
 */
const COLUMN_WIDTH_SHARES = Object.freeze({
  /** Eleven of sixty-nine cells, for the one-character action field. */
  action: characterCellWidthShare(COLUMN_CELL_COUNTS.action, COLUMN_CELL_COUNTS.span),
  /** Fifty of sixty-nine cells, for the fifty-character description field. */
  description: characterCellWidthShare(COLUMN_CELL_COUNTS.description, COLUMN_CELL_COUNTS.span),
  /** Eight of sixty-nine cells, for the two-character key field. */
  typeCd: characterCellWidthShare(COLUMN_CELL_COUNTS.typeCd, COLUMN_CELL_COUNTS.span),
} as const);

/**
 * Severity and text of one message-band state.
 *
 * Assumptions: the band is presentational and is handed text plus a severity, never a
 * failure object - `ui/src/layout/MessageBand.tsx` states that split in its own props
 * documentation. Reducing a failure to these two members is therefore this screen's job and
 * is done once, in {@link describeFailure}.
 *
 * Assumptions: severity follows WHICH of the reference's two message fields a sentence
 * belongs to, which is a mechanical rule rather than a judgement call. The reference declares
 * exactly two: `WS-INFO-MSG PIC X(45)` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`
 * L236-L248, whose five sentences are sent to `INFOMSG` - `COLOR=NEUTRAL` at
 * `app/app-transaction-type-db2/bms/COTRTLI.bms` L301-L305 - and `WS-RETURN-MSG PIC X(75)` at
 * L249-L262, every value of which L1557 moves into `ERRMSGO`, the `COLOR=RED` field at
 * L308-L311. So a `WS-RETURN-MSG` sentence was read in red and a `WS-INFO-MSG` sentence in
 * neutral, and the mapping here is `WS-RETURN-MSG` to `'error'` and `WS-INFO-MSG` to `'info'`.
 *
 * Trade-offs: that makes the paging refusals and the two empty-result sentences render as
 * errors even though none of them sets `INPUT-ERROR` - they are set in `2500-SETUP-MESSAGE`
 * during the send, after the dispatch has already chosen its arm. The alternative was to map
 * them to `'info'` on the grounds that a boundary is not a fault, which is how an earlier
 * revision of this file read and is arguably the kinder reading. It was rejected because a
 * visible property of the source is authoritative over a semantic preference: an operator saw
 * these sentences in red, and re-colouring them would be an undocumented behavioural change.
 * The two success sentences are the single deliberate refinement - both belong to the neutral
 * field, but both are success confirmations and the band publishes a `'success'` severity, so
 * they take it.
 */
interface BandMessage {
  /** Text to display, already reduced to something safe to publish. */
  readonly text: string;
  /** How the band should present it. */
  readonly severity: MessageBandSeverity;
}

/**
 * The request an operator has asked for and not yet confirmed.
 *
 * Refactoring Rationale: the reference holds this in the passed communication area -
 * `WS-CA-DELETE-FLAG` and `WS-CA-UPDATE-FLAG` at
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L411-L418 - because a CICS task ends at
 * every screen turn and nothing else survives it. Here it is ordinary component state.
 * AAP section 0.7.1 withdraws that server-held structure generally: a value the client
 * echoes back is a value the client can assert, and the re-entry discriminator that gated
 * it disappears entirely. It is deliberately not persisted to storage or to a cookie
 * either, because a confirmation that outlives the page an operator was looking at is a
 * confirmation they never gave.
 *
 * Refactoring Rationale: this records WHICH action was asked for against WHICH row and
 * deliberately carries no description and no version. The reference re-receives the whole map
 * on every turn - `1100-RECEIVE-SCREEN` at L937-L953 copies `TRTYPDI(I)` into
 * `WS-ROW-TR-DESC-IN(I)` each time - and `9200-UPDATE-RECORD` at L1841-L1844 then writes
 * `WS-ROW-TR-DESC-IN(I-SELECTED)`, so what gets committed is what stood on the screen at the
 * moment of the commit, never a value captured on an earlier turn. An earlier revision of
 * this file DID capture the description here, and a browser run proved the consequence: an
 * operator who typed a new description and pressed the advertised save key had their edit
 * silently discarded and was told the row was updated. Carrying no payload at all makes that
 * class of fault unrepresentable rather than merely fixed.
 */
interface PendingAction {
  /** Which action was requested. */
  readonly code: RowActionCode;
  /** Key of the row it was requested against. */
  readonly typeCd: string;
}

/**
 * Outcome of reducing the action cells of one page.
 *
 * Assumptions: the members mirror the reference's own flags so the two can be compared -
 * `I-SELECTED` at `COTRTLIC.cbl` L200, `WS-ACTIONS-REQUESTED` with
 * `88 WS-MORETHAN1ACTION VALUES 2 THRU 7` at L203-L207,
 * `WS-EDIT-SELECT-ERROR-FLAGS` at L190-L194 and `WS-BAD-SELECTION-ACTION` at L130-L132.
 */
interface ActionSelection {
  /** Key of the selected row, or `null` when no cell carries a code. */
  readonly typeCd: string | null;
  /** Code that row carries, or `null` when none is selected or it is not a valid code. */
  readonly code: RowActionCode | null;
  /** Keys of rows whose cell is in error, for the per-row highlight. */
  readonly errorKeys: readonly string[];
  /** Whether any cell was rejected, which suppresses the emphasis and the commit. */
  readonly badActions: boolean;
  /** Message the reduction produced, or `null` when every cell was acceptable. */
  readonly message: string | null;
}

/**
 * Per-field error text for the two filters and the edited description.
 *
 * Assumptions: these are separate from {@link BandMessage} because the reference keeps them
 * separate. `app/cpy/CSSETATY.cpy` L17-L27 colours the FIELD and writes a blank marker into it,
 * while the sentence goes to `WS-RETURN-MSG`; the target renders the former through
 * `Form.Item` and only the latter through the band.
 */
interface FieldErrors {
  /** Error on the type filter, or `null`. */
  readonly typeFilter: string | null;
  /** Error on the description filter, or `null`. */
  readonly descriptionFilter: string | null;
  /** Error on the edited description, or `null`. */
  readonly description: string | null;
}

/** No field is in error; the state each validation pass starts from. */
const NO_FIELD_ERRORS: FieldErrors = Object.freeze({
  typeFilter: null,
  descriptionFilter: null,
  description: null,
});

/** The filter values a browse is currently reading under. */
interface AppliedFilters {
  /** Type code narrowing the browse, or the empty string for no narrowing. */
  readonly typeCode: string;
  /** Description text narrowing the browse, or the empty string for no narrowing. */
  readonly description: string;
}

/** Neither filter is applied; the state the browse opens under. */
const NO_FILTERS: AppliedFilters = Object.freeze({ typeCode: '', description: '' });

/*
 * WHY : ⚠️ Refactoring Rationale: the collapse of a zero type filter to "no filter" is stated ONCE,
 *       here, and applied where the entry becomes an APPLIED filter. It was previously nowhere: the
 *       entry `'00'` was carried into `appliedFilters` and sent as `typeCode: '00'`, which made three
 *       derived things wrong at once -- the query narrowed to a code no seeded row carries, the
 *       browse reset key changed when nothing about the narrowing had, and `filteredEmpty` reported a
 *       genuinely empty unfiltered table as a filter that matched nothing, marking a control the
 *       operator had effectively left blank.
 * WHY : Assumptions: this is the reference's own rule and not a convenience. `1220-EDIT-TYPECD` at
 *       `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1101-L1107 exits without setting a filter on
 *       `LOW-VALUES`, `SPACES` **or `ZEROS`**, so the terminal treated a zeroed entry as no narrowing
 *       rather than as a narrowing that happened to be numeric. The service reaches the same place
 *       independently: `TransactionTypeRepository.typeCodeFilter` returns `null` for an all-zero
 *       value, and its own rationale records why -- a caller that omitted the collapse "would narrow a
 *       walk to the code 00 ... a wrong answer that looks like a correct one".
 * WHY : Assumptions: EVERY character being a zero is the test, not equality with `'00'`, matching the
 *       service member exactly. The control carries `maxLength={TYPE_CODE_LENGTH}` so `'00'` is the
 *       only such value that can be typed here, but agreeing with the server's wider family costs one
 *       comparison and removes any chance of the two rules disagreeing about a value.
 * WHY : Alternatives Considered: collapsing inside `buildTransactionTypeQuery` alone, which would fix
 *       the request and leave the other two consumers reading the uncollapsed state. Rejected because
 *       the defect is that `appliedFilters` HOLDS a value that is not a narrowing; normalising at the
 *       boundary where it is stored makes every reader correct without any of them knowing the rule.
 */

/**
 * Reduces a type-filter entry to the value it narrows the browse by.
 * @param {string} entry - The type-filter entry as typed, already trimmed.
 * @returns {string} The narrowing code, or the empty string when the entry means no narrowing.
 */
export function canonicalTypeFilter(entry: string): string {
  return /^0+$/u.test(entry) ? '' : entry;
}

/**
 * Composes the query for one page of transaction types from a cursor and the applied
 * filters.
 *
 * Assumptions: a blank filter is omitted from the query rather than sent as an empty string.
 * The reference treats blank as "no narrowing" - `1220-EDIT-TYPECD` at
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1101-L1107 exits on `LOW-VALUES`,
 * `SPACES` or `ZEROS`, and its `9100-CHECK-FILTERS` predicate at L1807-L1814 tests the
 * FLAG rather than the value, so an unset filter contributes no clause at all. Sending an
 * empty string would instead ask the service to match one.
 *
 * Assumptions: `direction` is omitted whenever there is no cursor. `listTransactionTypes`
 * throws `RangeError` when handed a direction with nothing to step from, which is the
 * opening read's shape exactly, so the two members travel together or not at all.
 *
 * Trade-offs: the object is built by conditional assignment rather than by spreading
 * possibly-undefined members. `ui/tsconfig.json` sets `exactOptionalPropertyTypes`, under
 * which an explicit `undefined` is not the same as an absent property, so the terser
 * spread form would not compile against `ReferenceListQuery`.
 * @param {string | null} cursor - Sealed cursor to read from, or `null` for the opening read.
 * @param {'next' | 'previous'} direction - Direction the cursor was sealed for; ignored when
 *   `cursor` is `null`.
 * @param {AppliedFilters} filters - The filter values the browse is reading under.
 * @returns {ReferenceListQuery} Criteria for one page, carrying only the members that apply.
 */
export function buildTransactionTypeQuery(
  cursor: string | null,
  direction: 'next' | 'previous',
  filters: AppliedFilters,
): ReferenceListQuery {
  const query: {
    typeCode?: string;
    description?: string;
    cursor?: string;
    direction?: 'next' | 'previous';
  } = {};

  if (filters.typeCode !== '') {
    query.typeCode = filters.typeCode;
  }
  if (filters.description !== '') {
    query.description = filters.description;
  }
  if (cursor !== null) {
    query.cursor = cursor;
    query.direction = direction;
  }

  return query;
}

/**
 * The two field names the service attributes a filtered-empty refusal to.
 *
 * Assumptions: these are the SERVICE's names, not this screen's control names.
 * `TransactionTypeService.FIELD_TYPE_CODE` is `"typeCode"` and `FIELD_DESCRIPTION` is
 * `"description"`, while the controls here are `typeFilter` and `descriptionFilter` -- so the
 * mapping between them is explicit rather than an assumed match. A lookup keyed on the control
 * name would find nothing and the refusal would go unrecognised.
 */
/**
 * HTTP status the service answers a filtered read that matched nothing.
 *
 * Assumptions: 400 rather than 404, and the difference is the reference's.
 * `TransactionTypeService.requireFilterMatchesSomething` raises a `ClientInputException`, which the
 * shared advice answers 400, because L1241-L1266 treats the condition as an input error against the
 * filter the operator supplied rather than as a missing resource.
 */
const BAD_REQUEST_STATUS = 400;

/*
 * WHY : Refactoring Rationale: a second `SERVICE_FILTER_FIELDS` declaration stood here, identical in
 *       content to the frozen one above and differing only in whether it was frozen. Two declarations of
 *       one fact are two places for a service-side rename to be applied to only one, so the frozen one
 *       is kept -- freezing is what stops a reader mutating a table every refusal path reads.
 */

/**
 * Reports whether a browse failure is the service's own filtered-empty refusal.
 *
 * ⚠️ Purpose: this exists because the reference does NOT treat a filtered miss as a failure and the
 * migrated service does. `1290-CROSS-EDITS` at
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1241-L1266 runs a zero-count filtered read as an
 * INPUT ERROR: it raises `'No Records found for these filter conditions'`, marks each supplied filter
 * and protects the action column, all on a screen that is otherwise intact.
 * `TransactionTypeService.requireFilterMatchesSomething` transcribes exactly that, and because it
 * raises a `ClientInputException` the transport answers HTTP 400 -- which a keyset browse can only
 * report as a failed read. So the one condition the reference handles most gracefully arrived at this
 * screen as its harshest outcome, and an operator whose filter simply matched nothing was told
 * `UNEXPECTED ABEND OCCURRED.`
 *
 * Assumptions: the recognition is made on the STATUS and the field attribution, never on the sentence.
 * Matching the sentence would make this screen's most important branch depend on a string comparison
 * against text the service composes; the status and the attributed field are what the operation
 * contracts to send -- 400 with one per-field entry per filter it found supplied. The attribution is
 * what separates this refusal from every OTHER 400 the browse can receive: a malformed cursor, an
 * over-long page or a refused direction carries no entry naming a filter, so none of them is mistaken
 * for a filtered miss and each still reports as the fault it is.
 *
 * Alternatives Considered: having the service answer 200 with an empty page and the sentence in a
 * message member, so no failure would arise at all. Rejected because it would make a filtered miss
 * indistinguishable from an unfiltered one at the transport, and the two carry DIFFERENT verbatim
 * sentences and different screen states in the reference -- L1702-L1704's
 * `'No records found for this search condition.'` leaves the action column live, while L1251-L1265's
 * refusal protects it. Collapsing them would lose that distinction for every client, to spare this one
 * screen a branch.
 * @param {ApiError | null} failure - The problem document from the most recent browse failure, or
 *   `null` when the failure carried none.
 * @returns {boolean} Whether this failure is the filtered-empty refusal rather than a real fault.
 */
export function isFilteredEmptyRefusal(failure: ApiError | null): boolean {
  if (failure === null || failure.status !== BAD_REQUEST_STATUS) {
    return false;
  }

  return failure.fieldErrors.some(
    /**
     * Reports whether one entry names a filter field the service validated.
     * @param {FieldError} entry - One per-field entry of the problem document.
     * @returns {boolean} Whether it names either filter.
     */
    (entry: FieldError): boolean =>
      entry.field === SERVICE_FILTER_FIELDS.typeCode ||
      entry.field === SERVICE_FILTER_FIELDS.description,
  );
}

/**
 * Validates the type-code filter.
 *
 * Assumptions: blank is valid and means "no narrowing", so this returns `null` for it -
 * `1220-EDIT-TYPECD` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1101-L1107 exits
 * without setting an error on `LOW-VALUES`, `SPACES` or `ZEROS`. Anything supplied must be
 * numeric, per the `IS NOT NUMERIC` test at L1111, and the message is the one moved at
 * L1116.
 
 *
 * Assumptions: ⚠️ `'00'` is ACCEPTED here and collapsed to "no narrowing" by the caller, not
 * forwarded. Two earlier revisions of this note were each wrong in turn. The first said `'00'` reaches
 * the numeric arm and passes it; it never reaches that arm, because L1103's third disjunct is
 * `OR WS-IN-TYPE-CD EQUAL ZEROS`, which is true of a two-character field holding `'00'`, so the
 * reference exits at L1104-L1106 having set `FLG-TYPEFILTER-BLANK` -- `'00'` MEANS "no narrowing"
 * there, exactly as blank does. The second said the client need not reproduce that collapse because
 * the service performs it; the service does perform it -- `TransactionTypeRepository.typeCodeFilter`
 * maps an all-zeros code to no filter -- but leaving it to the service was itself found to be a
 * defect, because `appliedFilters` then HELD a value that narrows nothing, and three derived readings
 * went wrong with it. {@link canonicalTypeFilter} now performs the collapse at the boundary where the
 * entry becomes an applied filter, and its own note records the three readings. What THIS function
 * must do with `'00'` is accept it, which it does; deciding what it means is not its job.
 *
 * Assumptions: a supplied entry must be EXACTLY TWO digits, so a single digit is refused, and
 * the reference refuses it through the same one test. Its field is two characters wide from end
 * to end -- `TRTYPE` is `LENGTH=2` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L89-L93,
 * `TRTYPEI` is `PIC X(2)` at `app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy` L66, and L937
 * moves it into `WS-IN-TYPE-CD PIC X(02)` (L310) -- and the attribute list carries no `NUM`, so
 * the terminal neither right-justifies nor zero-fills. A single typed digit therefore reaches
 * `1220-EDIT-TYPECD` as a digit and a BLANK, and `IF WS-IN-TYPE-CD IS NOT NUMERIC` at L1111 is
 * true of it, so the reference raises the sentence below. Its own comment at L1109-L1110 names
 * both conditions -- "Not numeric" and "Not 2 characters" -- over that single test, which is the
 * comment's point: the padding is what folds the width check into the numeric one.
 * `WS-IN-TYPE-CD-N REDEFINES WS-IN-TYPE-CD PIC 9(02)` at L312 corroborates the intent from the
 * other side.
 *
 * Assumptions: ⚠️ Refactoring Rationale: this admitted any run of digits and therefore accepted a
 * one-digit filter, on the stated ground that `maxLength` bounds the entry from above and the
 * reference paired both conditions under one message. That was right about a LONGER entry and
 * silently wrong about a SHORTER one: a browser control holds `'1'` where the terminal held
 * `'1 '`, so the padding that makes the reference refuse it does not exist here and the width has
 * to be tested explicitly. The published contract agrees from the service side -- its
 * `TransactionTypeCodeFilter` schema declares `minLength: 2`, `maxLength: 2` and
 * `pattern: '^[0-9]{2}$'`, and both list request records declare
 * `TYPE_CODE_PATTERN = "[0-9]{2}"` -- so the previous rule sent a request the service was bound
 * to refuse, answering with a generic validation problem instead of the reference's own sentence.
 * @param {string} value - The filter entry as typed, before trimming.
 * @returns {string | null} The verbatim error sentence, or `null` when the entry is
 *   acceptable.
 */
export function validateTypeFilter(value: string): string | null {
  const entry = value.trim();

  if (entry === '') {
    return null;
  }

  // WHY: Assumptions: the test is a positive character-class match of an exact width rather than
  //      `Number.isNaN(Number(entry))`. `Number('')`, `Number(' 1')` and `Number('1e1')` are all
  //      numbers to JavaScript, and none of the three is two digit characters, whereas COBOL's
  //      `IS NOT NUMERIC` on a two-character display field accepts exactly that and nothing else.
  //      The anchored, counted class match is what reproduces that domain.
  if (!/^\d{2}$/.test(entry)) {
    return LIST_MESSAGES.TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER;
  }

  return null;
}

/**
 * Validates an edited row description, and reports when it was not edited at all.
 *
 * Assumptions: the unchanged test comes first and short-circuits the rest.
 * `1211-EDIT-ARRAY-DESC` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1064-L1076
 * compares the entry with the stored value on both case-insensitive trimmed equality and
 * trimmed length, and on a match sets `WS-MESG-NO-CHANGES-DETECTED` and leaves the paragraph
 * before any edit runs. So "no change detected" is a validation outcome on this screen and
 * not only an answer the service can give.
 *
 * Assumptions: the two error sentences are composed the way the reference composes them, by
 * joining the field label to a suffix - `STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)` with
 * `' must be supplied.'` at L1196-L1201 and with
 * `' can have numbers or alphabets only.'` at L1223-L1228, over the label
 * `'Transaction Desc'` moved at L1083. Both halves come from the catalog, so the joined
 * result is verbatim without this module holding either literal.
 *
 * Assumptions: the permitted characters are letters, digits and the space.
 * `1240-EDIT-ALPHANUM-REQD` at L1208-L1217 converts every character in
 * `LIT-ALL-ALPHANUM-FROM-X` - the 26 upper-case, 26 lower-case and 10 digit characters
 * declared at L65-L72 - to spaces and then requires the remainder to be empty, so a space
 * survives by being what everything else becomes.
 * @param {string} entry - The description as edited.
 * @param {string} stored - The description as last read from the service.
 * @returns {string | null} The verbatim sentence describing the outcome, or `null` when the
 *   entry is a valid change.
 */
export function validateRowDescription(entry: string, stored: string): string | null {
  const trimmedEntry = entry.trim();
  const trimmedStored = stored.trim();

  if (
    trimmedEntry.toUpperCase() === trimmedStored.toUpperCase() &&
    trimmedEntry.length === trimmedStored.length
  ) {
    return LIST_STATUS.WS_MESG_NO_CHANGES_DETECTED.text;
  }

  if (trimmedEntry === '') {
    return `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;
  }

  if (!/^[0-9A-Za-z ]+$/.test(entry.slice(0, DESCRIPTION_LENGTH))) {
    return `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY}`;
  }

  return null;
}

/**
 * Reduces the action cells of one page to the single request they express.
 *
 * Assumptions: exactly one action across the page. `WS-ACTIONS-REQUESTED` at
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L203-L207 carries
 * `88 WS-ONLY-1-ACTION VALUE 1` beside `88 WS-MORETHAN1ACTION VALUES 2 THRU 7`, and the
 * reducer at L1049-L1052 turns the second into an input error carrying
 * `'Please select only 1 action'`.
 *
 * Assumptions: the retained row is the lowest-numbered selected one. `1210-EDIT-ARRAY` at
 * L1017-L1023 walks `FROM WS-MAX-SCREEN-LINES BY -1 UNTIL I = 0` and moves `I` into
 * `I-SELECTED` on every match, so the last write wins and the last write is the lowest
 * index. Walking the rows in display order and keeping the first match is the same
 * selection, expressed the way it reads.
 *
 * Assumptions: a blank cell is not an error. `88 SELECT-BLANK VALUES ' ', LOW-VALUES` at
 * L186-L188 is a distinct arm from `WHEN OTHER`, and only the latter rejects - with
 * `'Action code selected is invalid'` moved at L1038.
 *
 * Trade-offs: the message is chosen by the same precedence the reference applies rather than
 * by concatenating both. An invalid character sets its sentence inside the loop at L1038 and
 * the count test at L1051 overwrites it afterwards, so when a page carries both faults the
 * count message is what an operator sees; that ordering is reproduced here rather than
 * reporting a list.
 * @param {readonly TransactionType[]} rows - The page's rows, in display order.
 * @param {Readonly<Record<string, string>>} codes - Action cell entries, keyed by row key.
 * @returns {ActionSelection} The selected row and code, the rows to highlight, whether any
 *   cell was rejected, and the sentence to display.
 */
export function reduceActionSelection(
  rows: readonly TransactionType[],
  codes: Readonly<Record<string, string>>,
): ActionSelection {
  const errorKeys: string[] = [];
  let selectedKey: string | null = null;
  let selectedCode: RowActionCode | null = null;
  let validActions = 0;
  let invalidMessage: string | null = null;

  for (const row of rows) {
    const entry = (codes[row.typeCd] ?? '').trim();

    if (entry === '') {
      continue;
    }

    const code = toRowActionCode(entry);

    if (code !== null) {
      validActions += 1;
      if (selectedKey === null) {
        selectedKey = row.typeCd;
        selectedCode = code;
      }
      continue;
    }

    errorKeys.push(row.typeCd);
    invalidMessage = LIST_STATUS.WS_MESG_INVALID_ACTION_CODE.text;
  }

  if (validActions > 1) {
    // WHY: Assumptions: every selected row is highlighted, not merely the surplus ones.
    //      L1024-L1027 sets `WS-ROW-TRTSELECT-ERROR(I)` inside the arm that matched a VALID
    //      code, so each contributing row is marked and an operator can see which choices
    //      are in conflict rather than being told a count with nothing indicated.
    for (const row of rows) {
      if (toRowActionCode((codes[row.typeCd] ?? '').trim()) !== null) {
        errorKeys.push(row.typeCd);
      }
    }

    return {
      typeCd: null,
      code: null,
      errorKeys,
      badActions: true,
      message: LIST_STATUS.WS_MESG_MORE_THAN_1_ACTION.text,
    };
  }

  if (invalidMessage !== null) {
    return { typeCd: null, code: null, errorKeys, badActions: true, message: invalidMessage };
  }

  return { typeCd: selectedKey, code: selectedCode, errorKeys, badActions: false, message: null };
}

/**
 * Reduces a failed service call to the sentence this screen is allowed to display.
 *
 * Refactoring Rationale: the baseline routed every non-zero SQL code through
 * `9999-FORMAT-DB2-MESSAGE`, which appended the code and the formatted diagnostic text to a
 * short action label. That decoration is exactly what must not reach a browser, and
 * `ui/src/messages/messages.ts` records those literals as redacted with named replacements
 * for each condition. This function maps to those replacements, so nothing here carries a
 * status, a SQL code or any part of a response body.
 *
 * Refactoring Rationale: the conflict is the one branch whose sentence survives verbatim.
 * `9300-DELETE-RECORD` at `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1914-L1925 tests
 * `SQLCODE = -532` - the refusal raised by the `ON DELETE RESTRICT` foreign key in
 * `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` L6-L7 - and moves
 * `'Please delete associated child records first:'`. That is an instruction to the operator
 * rather than a diagnostic, so it is preserved as written; the catalog holds it unredacted
 * for the same reason.
 *
 * Assumptions: the conflict is recognised with `isConflictFailure` from `ui/src/api/client.ts`
 * rather than by reading a status. That client's interceptor has already normalised every
 * failure, and `deleteTransactionType` documents 409 as an expected outcome to be
 * distinguished with that predicate, so comparing a number here would duplicate a decision
 * the client layer owns.
 * @param {unknown} failure - Whatever the call rejected with.
 * @param {RowActionCode} code - Which action was being committed, which selects between the
 *   update and delete replacement sentences.
 * @returns {BandMessage} Text safe to publish, with the severity to publish it at.
 */
export function describeFailure(failure: unknown, code: RowActionCode): BandMessage {
  if (isConflictFailure(failure)) {
    // WHY: Refactoring Rationale: an update conflict and a delete conflict are different
    //      refusals and the reference words them differently. A stale version is
    //      `SQLCODE = -911` at L1870-L1878, whose replacement is the maintenance screen's
    //      "changed by someone else" sentence; a referenced parent row is `-532` at L1914.
    //      Collapsing both onto one sentence would tell an operator to delete child records
    //      that do not exist.
    return code === REF_TYPE_ROW_ACTION_CODES.delete
      ? {
          text: SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST,
          severity: 'error',
        }
      : { text: MAINTENANCE_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text, severity: 'error' };
  }

  return code === REF_TYPE_ROW_ACTION_CODES.delete
    ? { text: MAINTENANCE_STATUS.RECORD_DELETE_FAILED.text, severity: 'error' }
    : { text: MAINTENANCE_STATUS.TABLE_UPDATE_FAILED.text, severity: 'error' };
}

/**
 * Transaction-type maintenance list screen.
 *
 * Renders the row-4 heading and page number, the two optional filters, the seven-row grid
 * whose action cells accept `D` or `U` and whose description is editable in place for the row
 * being updated, the separate row-19 area, the shared message band and the five-key legend.
 * ENTER validates and echoes a confirmation prompt; PF10 commits it; PF2 leaves for the
 * maintenance screen; PF3 returns to the administrator menu; PF7 and PF8 page the browse.
 *
 * Assumptions: no props. The screen takes its narrowing from operator entry and its rows from
 * the service, and it is mounted as a route element rather than composed by a parent, so
 * there is nothing for a caller to pass. It also assumes the administrator guard in
 * `ui/src/routes/guards.tsx` has already run, and therefore renders no access-denied state.
 * @returns {ReactElement} The composed screen.
 */
export default function RefTypeListScreen(): ReactElement {
  /*
   * WHY : Refactoring Rationale: every text colour in this module resolves through
   *       `BMS_TEXT_COLOR_TOKENS` and not through the hue map `BMS_COLOR_TOKENS`. The measured source
   *       roles and the bridge that assigns each `COLOR=` operand its semantic role are unchanged;
   *       what changed is that the hue map's entries are mid-ramp FILL anchors, and read as text the
   *       turquoise role measures 2.205:1 and the blue role 4.104:1 against the surface the shell
   *       paints, where WCAG AA asks 4.5:1 for normal text. `ui/src/theme/tokens.ts` records, per
   *       role, the in-family shade that was measured and the text-grade token that replaced it.
   */
  const navigate = useNavigate();
  const paintedAt = useServerInstant();

  // WHY: Refactoring Rationale: `cssVar` is taken rather than `token`. The two halves of the
  //      design system's token hook differ in kind, not merely in spelling -- `token` holds
  //      values resolved at render while `cssVar` holds `var(--...)` references to the same
  //      names. Version 6 themes through CSS variables, so writing a resolved value would
  //      freeze it and a later theme change would not reach it. `ui/src/layout/PfKeyBar.tsx`
  //      records the same decision for the same reason.
  const { cssVar } = theme.useToken();

  const [typeFilterDraft, setTypeFilterDraft] = useState('');
  const [descriptionFilterDraft, setDescriptionFilterDraft] = useState('');
  const [appliedFilters, setAppliedFilters] = useState<AppliedFilters>(NO_FILTERS);
  const [actionCodes, setActionCodes] = useState<Readonly<Record<string, string>>>({});
  const [descriptionDrafts, setDescriptionDrafts] = useState<Readonly<Record<string, string>>>({});
  const [committedRows, setCommittedRows] = useState<Readonly<Record<string, TransactionType>>>({});
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [updateCompleted, setUpdateCompleted] = useState(false);
  const [band, setBand] = useState<BandMessage | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>(NO_FIELD_ERRORS);
  const [rowErrorKeys, setRowErrorKeys] = useState<readonly string[]>([]);
  /*
   * WHY : ⚠️ Assumptions: the sentence that marked the rows is held BESIDE the keys, so each
   *       marked cell can point at it. The keys alone were enough while the marking was a colour, and
   *       they are not enough for a description: `aria-describedby` has to name an element that holds
   *       text, and the only text a refused turn produces is the one sentence the band shows. Holding it
   *       here rather than reading the band back keeps the two independent -- the band is cleared by
   *       every turn and by the shell's own delegation, while a marked cell must describe itself for as
   *       long as it stays marked.
   */
  const [rowErrorMessage, setRowErrorMessage] = useState<string | null>(null);
  const [protectSelectRows, setProtectSelectRows] = useState(false);
  const [lastPageShown, setLastPageShown] = useState(false);
  const [committing, setCommitting] = useState(false);

  const fetchPage = useCallback(
    /**
     * Reads one page of transaction types under the filters currently applied.
     * @param {object} request - The browse step the paging hook is taking.
     * @param {string | null} request.cursor - Sealed cursor, or `null` for the opening read.
     * @param {'next' | 'previous'} request.direction - Direction that cursor was sealed for.
     * @returns {Promise<PageResponse<TransactionType>>} One bounded page.
     * @throws {Error} The normalised failure from `ui/src/api/client.ts`, which the hook
     *   surfaces through its own failure state rather than this function handling it.
     */
    async (request: {
      cursor: string | null;
      direction: 'next' | 'previous';
    }): Promise<PageResponse<TransactionType>> =>
      listTransactionTypes(
        buildTransactionTypeQuery(request.cursor, request.direction, appliedFilters),
      ),
    [appliedFilters],
  );

  // WHY: Assumptions: the restart key is composed from the APPLIED filters, and changing it is
  //      what returns the browse to its first page. `1220-EDIT-TYPECD-EXIT` and
  //      `1230-EDIT-DESC-EXIT` both run `INITIALIZE WS-CA-PAGING-VARIABLES` on a changed
  //      filter -- L1134 and L1172 of
  //      `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` -- because a cursor sealed under one
  //      narrowing addresses nothing under another.
  // WHY: Alternatives Considered: calling the hook's imperative `reset` from the filter
  //      handler. Rejected because the hook publishes `resetKey` for exactly this and states
  //      that a change to it restarts the browse, so the declarative form cannot fall out of
  //      step with the applied value the way a forgotten call could. The unit separator joins
  //      the two halves so that a code ending in the text of a description cannot collide with
  //      a different pair.
  const browse = usePagedQuery<TransactionType>({
    pageSize: REF_TYPE_PAGE_SIZE,
    fetchPage,
    resetKey: `${appliedFilters.typeCode}\u001f${appliedFilters.description}`,
  });

  // WHY: Assumptions: a locally committed row is merged over the browse's copy rather than the
  //      page being re-read. `2300-SCREEN-ARRAY-INIT` L1412-L1418 sends the value the operator
  //      TYPED back to the screen after a successful update, and re-reading would instead
  //      return the browse to its first page and lose the operator's position. Merging the
  //      entity the service returned keeps the row where it is, shows the stored value, and
  //      refreshes the version so a second update is not refused as stale.
  const rows: readonly TransactionType[] = useMemo(
    /**
     * Merges locally committed rows over the page the browse delivered.
     * @returns {readonly TransactionType[]} The page as it should display.
     */
    (): readonly TransactionType[] =>
      browse.items.map(
        /**
         * Replaces a browse row with its locally committed successor, when there is one.
         * @param {TransactionType} row - The row as the browse delivered it.
         * @returns {TransactionType} The committed row if this key was updated, else the row.
         */
        (row: TransactionType): TransactionType => committedRows[row.typeCd] ?? row,
      ),
    [browse.items, committedRows],
  );

  const selection = useMemo(
    /**
     * Reduces the current action cells to the single request they express.
     * @returns {ActionSelection} The live reduction, recomputed when a cell or the page changes.
     */
    (): ActionSelection => reduceActionSelection(rows, actionCodes),
    [rows, actionCodes],
  );

  /**
   * Clears everything that belongs to one screen turn rather than to the browse.
   *
   * Assumptions: the reference rebuilds all of this on every turn -- `2100-SCREEN-INIT` L1320
   * blanks the informational field, and the per-row error array and protect flag are set only
   * by the edits that run after a receive. Carrying any of them into the next turn would show
   * an operator a highlight for a fault they have already corrected, which is the same reason
   * AAP section 0.7.1 removes the re-entry discriminator that `app/cpy/CSSETATY.cpy` L20 gated
   * the highlight on.
   * @returns {void} State is updated in place.
   */
  function clearTurnState(): void {
    setBand(null);
    setFieldErrors(NO_FIELD_ERRORS);
    setRowErrorKeys([]);
    setRowErrorMessage(null);
    setProtectSelectRows(false);
  }

  /**
   * Abandons any unconfirmed request and the edits that belonged to it.
   * @returns {void} State is updated in place.
   */
  function clearPendingAction(): void {
    setPendingAction(null);
    setUpdateCompleted(false);
  }

  /**
   * Validates the entries, applies a changed filter, and turns a selected action into a
   * confirmation prompt.
   *
   * This is the ENTER path, and every key the reference does not accept arrives here too:
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L585-L587 coerces any unrecognised
   * attention identifier to ENTER, and L666-L678 downgrades PF10 to it whenever the
   * confirmation gate is shut.
   * @returns {void} State is updated in place; nothing is returned and nothing is thrown.
   */
  function submit(): void {
    setLastPageShown(false);
    clearTurnState();

    // WHY: Assumptions: the entry is canonicalised BEFORE it is compared with the applied state and
    //      before it is stored, so `appliedFilters` never holds a value that is not a narrowing. Doing
    //      it after the comparison would report a filter change on the turn `''` became `'00'`, and
    //      re-read the same rows under a different reset key for no reason.
    const typeEntry = canonicalTypeFilter(typeFilterDraft.trim());
    const descriptionEntry = descriptionFilterDraft.trim();
    const typeFilterError = validateTypeFilter(typeFilterDraft);

    if (typeFilterError !== null) {
      // WHY: Assumptions: a rejected type filter disables the WHOLE action column, not just
      //      the filter. L1112-L1118 sets `FLG-PROTECT-SELECT-ROWS-YES` alongside the field
      //      error and leaves the paragraph, and the attribute pass at L1339-L1341 then moves
      //      `DFHBMPRO` into every row's action cell. The rows on display were read under the
      //      previous narrowing, so acting on one of them would commit against a page the
      //      entry no longer describes.
      setFieldErrors({ ...NO_FIELD_ERRORS, typeFilter: typeFilterError });
      setProtectSelectRows(true);
      setBand({ text: typeFilterError, severity: 'error' });
      clearPendingAction();
      return;
    }

    const filtersChanged =
      typeEntry !== appliedFilters.typeCode || descriptionEntry !== appliedFilters.description;

    if (filtersChanged) {
      // WHY: Assumptions: a changed filter discards every action cell before any of them is
      //      reduced. L991-L994 runs `INITIALIZE WS-EDIT-SELECT-FLAGS` and jumps past the
      //      array edit entirely, because a code typed beside a row of the previous result set
      //      names a row that may not be in the next one.
      setActionCodes({});
      setDescriptionDrafts({});
      setCommittedRows({});
      clearPendingAction();
      setAppliedFilters({ typeCode: typeEntry, description: descriptionEntry });
      return;
    }

    if (selection.message !== null) {
      setRowErrorKeys(selection.errorKeys);
      setRowErrorMessage(selection.message);
      setBand({ text: selection.message, severity: 'error' });
      clearPendingAction();
      return;
    }

    if (selection.typeCd === null || selection.code === null) {
      clearPendingAction();
      return;
    }

    const selectedRow = rows.find(
      /**
       * Finds the row the reduction selected.
       * @param {TransactionType} row - A row of the current page.
       * @returns {boolean} Whether this row carries the selected key.
       */
      (row: TransactionType): boolean => row.typeCd === selection.typeCd,
    );

    if (selectedRow === undefined) {
      clearPendingAction();
      return;
    }

    if (selection.code === REF_TYPE_ROW_ACTION_CODES.update) {
      const edited = descriptionDrafts[selectedRow.typeCd] ?? selectedRow.description;
      const descriptionError = validateRowDescription(edited, selectedRow.description);

      if (descriptionError !== null) {
        // WHY: Assumptions: the first fault of a turn is the only one shown. Both composed
        //      sentences are written under `IF WS-RETURN-MSG-OFF` -- L1195 and L1222 -- so a
        //      later edit cannot overwrite an earlier message. The single band plus a single
        //      per-field error reproduces that; concatenating faults or listing them would
        //      not.
        setFieldErrors({ ...NO_FIELD_ERRORS, description: descriptionError });
        setBand({ text: descriptionError, severity: 'error' });
        // WHY: Assumptions: the request stays pending through a failed edit so the cell stays
        //      open for correction. L1356-L1368 keeps the description unprotected and merely
        //      adds the red attribute while the row's validity flag is unset, rather than
        //      closing the editor.
        setPendingAction({
          code: REF_TYPE_ROW_ACTION_CODES.update,
          typeCd: selectedRow.typeCd,
        });
        setUpdateCompleted(false);
        return;
      }

      setPendingAction({
        code: REF_TYPE_ROW_ACTION_CODES.update,
        typeCd: selectedRow.typeCd,
      });
      setUpdateCompleted(false);
      setBand({ text: LIST_STATUS.WS_INFORM_UPDATE.text, severity: 'info' });
      return;
    }

    setPendingAction({
      code: REF_TYPE_ROW_ACTION_CODES.delete,
      typeCd: selectedRow.typeCd,
    });
    setUpdateCompleted(false);
    setBand({ text: LIST_STATUS.WS_INFORM_DELETE.text, severity: 'info' });
  }

  /**
   * Commits the pending request, or re-validates instead when the confirmation is no longer
   * the one the operator was shown.
   *
   * Assumptions: the gate is the conjunction the reference tests at
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L666-L678 -- a request must be pending AND
   * neither filter may have been retyped AND the selected row must be the same one. Failing any
   * arm, PF10 becomes ENTER at L674 and everything is validated afresh. That is what makes the
   * confirmation a confirmation: it can only commit the request an operator was actually shown,
   * so editing a filter or moving the action code cancels it rather than silently committing
   * something else.
   *
   * ⚠️ Refactoring Rationale: the type filter is compared through {@link canonicalTypeFilter},
   * where the raw trimmed draft was compared against the applied value. The two are not the same thing
   * for one entry an operator can legitimately type: a draft of `'00'` collapses to `''` on the way into
   * `appliedFilters`, so the raw comparison held `'00' === ''` and the gate could NEVER open while that
   * entry stood. PF10 fell to `submit()` on every press, which re-armed the same request and reported
   * the same "press PF10 to confirm" sentence, so a delete or an update was unreachable for as long as
   * the operator left a zeroed filter in place -- and the reference has no such state, because it
   * collapses zeros to blank before its own gate ever runs.
   *
   * Assumptions: the DESCRIPTION filter is compared on its trimmed draft with no canonicalisation,
   * because it has none to apply -- `appliedFilters.description` stores exactly the trimmed entry. The
   * asymmetry is in the filters and not in this gate.
   * @returns {Promise<void>} Resolves once the request has settled and the screen has been
   *   updated. Failures are reduced to a band message rather than propagating.
   */
  async function commit(): Promise<void> {
    const request = pendingAction;

    const gateOpen =
      request !== null &&
      canonicalTypeFilter(typeFilterDraft.trim()) === appliedFilters.typeCode &&
      descriptionFilterDraft.trim() === appliedFilters.description &&
      selection.typeCd === request.typeCd &&
      selection.code === request.code &&
      !selection.badActions;

    if (request === null || !gateOpen) {
      submit();
      return;
    }

    setLastPageShown(false);
    clearTurnState();

    // WHY: Refactoring Rationale: the description is re-read from the OPEN EDITOR here rather
    //      than taken from the pending request, and it is re-validated before anything is
    //      written. That is the reference's own order of operations on the confirming turn:
    //      `1100-RECEIVE-SCREEN` re-receives the map at L937-L953, the edit paragraphs run
    //      before the PF10 gate at L666-L678, the dispatch at L698 tests `WHEN INPUT-ERROR`
    //      FIRST and returns without writing at L699-L720, and only then does L854-L859 reach
    //      `9200-UPDATE-RECORD`, which commits `WS-ROW-TR-DESC-IN(I-SELECTED)` at L1841 -- the
    //      entry received on THIS turn. Reading a snapshot instead loses any edit made after
    //      the request was armed, which a browser run demonstrated: the edit vanished and the
    //      screen still reported success. Trade-offs: this re-validates on a turn the operator
    //      may consider already validated, which costs a little repeated work; it is the price
    //      of never writing an entry that was never checked.
    let updatePayload: { readonly description: string; readonly version: number } | null = null;

    if (request.code === REF_TYPE_ROW_ACTION_CODES.update) {
      const target = rows.find(
        /**
         * Finds the row the pending update names, as it stands on the page now.
         * @param {TransactionType} row - A row of the current page.
         * @returns {boolean} Whether this row carries the pending key.
         */
        (row: TransactionType): boolean => row.typeCd === request.typeCd,
      );

      // WHY: Assumptions: a request whose row has left the page is re-validated rather than
      //      committed. The gate above compares the pending key with the reduced selection, so
      //      reaching here without the row means the page itself moved underneath it, and
      //      L674 turns an unconfirmable PF10 into ENTER rather than guessing.
      if (target === undefined) {
        submit();
        return;
      }

      const edited = descriptionDrafts[target.typeCd] ?? target.description;
      const outcome = validateRowDescription(edited, target.description);

      // WHY: Assumptions: an unchanged description does NOT block the write, whereas a blank
      //      or non-alphanumeric one does. `1211-EDIT-ARRAY-DESC` at L1072 sets
      //      `WS-MESG-NO-CHANGES-DETECTED`, which is a MESSAGE selector and not `INPUT-ERROR`
      //      -- only L1078's `FLG-ROW-DESCRIPTION-NOT-OK` and the edit at L1086 raise the
      //      input error that the dispatch at L699 turns back at the door. So the two outcomes
      //      this validator can report are separated here by which of them the reference would
      //      have let through.
      if (outcome !== null && outcome !== LIST_STATUS.WS_MESG_NO_CHANGES_DETECTED.text) {
        setFieldErrors({ ...NO_FIELD_ERRORS, description: outcome });
        setBand({ text: outcome, severity: 'error' });
        setPendingAction({
          code: REF_TYPE_ROW_ACTION_CODES.update,
          typeCd: target.typeCd,
        });
        setUpdateCompleted(false);
        return;
      }

      // WHY: Assumptions: the stored value is trimmed. L1841 wraps the entry in
      //      `FUNCTION TRIM` before moving it into the host variable, and L949 has already
      //      trimmed it once on receipt, so trailing blanks are padding to the field width
      //      rather than part of the description.
      updatePayload = { description: edited.trim(), version: target.version };
    }

    setCommitting(true);

    try {
      // WHY: Assumptions: no payload identifies the delete path, because a payload is only
      //      built on the update path above. Delete needs the key alone -- L1898-L1902 matches
      //      `WHERE TR_TYPE = :DCL-TR-TYPE` and sets no column.
      if (updatePayload === null) {
        await deleteTransactionType(request.typeCd);

        // WHY: Assumptions: the row's action cell is blanked on success. L1391-L1398 sets
        //      `SELECT-BLANK(I)` when the delete completed, rather than leaving the `D` in
        //      place where a second ENTER would request it again against a row that has gone.
        setActionCodes({});
        setPendingAction(null);
        setUpdateCompleted(false);
        setBand({ text: LIST_STATUS.WS_INFORM_DELETE_SUCCESS.text, severity: 'success' });

        // WHY: Alternatives Considered: merging the deletion locally, as the update path
        //      merges its result. Rejected because the row no longer exists, so the page has
        //      one fewer member and the cursor that addressed it addresses nothing -- and the
        //      seventh slot would stay blank until something re-read. Re-reading is the only
        //      display that can be correct here. Trade-offs: it returns the browse to its
        //      first page, which the reference does not do -- L1391-L1398 blanks the action
        //      cell and leaves the operator where they were. That is registered as
        //      divergence **D-13** in `docs/architecture/cobol-to-service-traceability.md`
        //      section 7.2, which records why the position cannot simply be restored: this
        //      browse is KEYSET-paginated, so a page is addressed by the cursor of the row at
        //      its edge, and a delete can remove exactly that row -- leaving the cursor that
        //      identified the page addressing nothing. A screen that guessed a neighbouring
        //      cursor would be inventing a browse position rather than restoring one.
        browse.reset();
        return;
      }

      const stored = await replaceTransactionType(request.typeCd, updatePayload);

      setCommittedRows(
        /**
         * Records the stored row so the grid shows it without re-reading the page.
         * @param {Readonly<Record<string, TransactionType>>} current - Rows committed so far.
         * @returns {Readonly<Record<string, TransactionType>>} Those rows plus this one.
         */
        (
          current: Readonly<Record<string, TransactionType>>,
        ): Readonly<Record<string, TransactionType>> => ({ ...current, [stored.typeCd]: stored }),
      );
      setDescriptionDrafts(
        /**
         * Drops the draft now that the stored row carries the value.
         * @param {Readonly<Record<string, string>>} current - Drafts held so far.
         * @returns {Readonly<Record<string, string>>} Those drafts without this row's.
         */
        (current: Readonly<Record<string, string>>): Readonly<Record<string, string>> => {
          const next = { ...current };
          delete next[stored.typeCd];
          return next;
        },
      );
      setActionCodes({});
      setPendingAction(null);

      // WHY: Assumptions: the description closes again once the update has completed.
      //      L1360-L1362 moves the cursor back to the action cell and restores the protected
      //      attribute when `FLG-UPDATE-COMPLETED` is set, so the cell an operator has just
      //      saved stops being an editor until they ask for it again.
      setUpdateCompleted(true);
      setBand({ text: LIST_STATUS.WS_INFORM_UPDATE_SUCCESS.text, severity: 'success' });
    } catch (failure: unknown) {
      // WHY: Refactoring Rationale: the request is left pending on EVERY failure, not only on
      //      the referential-integrity refusal. `CA-DELETE-SUCCEEDED` and
      //      `CA-UPDATE-SUCCEEDED` are both `VALUE LOW-VALUES` at L411-L418, so the flag is
      //      cleared only by the arm that succeeded; the delete conflict re-asserts it
      //      explicitly at L1915 and all three update failure arms do the same at L1862,
      //      L1871 and L1881. Preserving it is what lets an operator delete the child rows and
      //      press PF10 again without re-selecting -- which is the entire point of the
      //      `ON DELETE RESTRICT` message.
      setBand(describeFailure(failure, request.code));
    } finally {
      setCommitting(false);
    }
  }

  /**
   * Steps the browse back one page, or reports that there is nothing behind it.
   *
   * Assumptions: the boundary sentence is this screen's to emit.
   * `ui/src/hooks/usePagedQuery.ts` documents a step at a boundary as a no-op that emits no
   * text, and the reference answers the same step with
   * `'No previous pages to display'` at `COTRTLIC.cbl` L1532-L1535 under
   * `CCARD-AID-PFK07 AND CA-FIRST-PAGE`.
   * @returns {void} State is updated in place.
   */
  function pageBackward(): void {
    setLastPageShown(false);
    clearTurnState();

    if (!browse.hasPrev) {
      setBand({ text: LIST_MESSAGES.NO_PREVIOUS_PAGES_TO_DISPLAY, severity: 'error' });
      return;
    }

    setActionCodes({});
    setDescriptionDrafts({});
    clearPendingAction();
    browse.prevPage();
  }

  /**
   * Steps the browse forward one page, or reports that there is nothing beyond it.
   *
   * Assumptions: the two exhaustion sentences are different and both are reachable. The
   * forward read emits `'No more pages for these search conditions'` when it runs out while
   * PF8 was the key pressed -- `COTRTLIC.cbl` L1677-L1680 and L1698-L1701 -- and the send path
   * emits `'No more pages to display'` only once the last page has already been shown, at
   * L1536-L1540 over `CA-LAST-PAGE-SHOWN`. L1546-L1549 is what sets that flag, so the first
   * press at the end produces the former and a second press produces the latter. L657-L661
   * clears the flag on any key that is not PF8, which is why every other handler here lowers
   * it.
   * @returns {void} State is updated in place.
   */
  function pageForward(): void {
    clearTurnState();

    if (!browse.hasNext) {
      if (lastPageShown) {
        setBand({ text: LIST_MESSAGES.NO_MORE_PAGES_TO_DISPLAY, severity: 'error' });
        return;
      }
      setLastPageShown(true);
      setBand({ text: LIST_STATUS.WS_MESG_NO_MORE_RECORDS.text, severity: 'error' });
      return;
    }

    setActionCodes({});
    setDescriptionDrafts({});
    clearPendingAction();
    browse.nextPage();
  }

  /**
   * Leaves for the maintenance screen so a new transaction type can be added.
   *
   * Assumptions: adding is a transfer of control, not an insertion into this grid.
   * `COTRTLIC.cbl` L630-L652 transfers to `COTRTUPC` with `CDEMO-PGM-ENTER` set at L635 -- a
   * fresh entry -- so no unconfirmed request travels with it and none is left behind here
   * either.
   * @returns {void} Navigation is performed as a side effect.
   */
  function openAddScreen(): void {
    setLastPageShown(false);
    clearTurnState();
    setActionCodes({});
    setDescriptionDrafts({});
    clearPendingAction();
    navigateSafely(navigate, REF_TYPE_ADD_ROUTE);
  }

  /**
   * Returns to the administrator menu.
   *
   * Assumptions: the administrator menu is the destination rather than the main menu.
   * `COTRTLIC.cbl` L600-L603 moves `LIT-ADMINPGM` -- declared `'COADM01C'` at L47 -- into
   * `CDEMO-TO-PROGRAM` whenever the screen was not reached from somewhere else, and this route
   * is reachable only from that menu because `ui/src/routes/guards.tsx` admits administrators
   * alone.
   *
   * Assumptions: ⚠️ NO MESSAGE is painted, and the reference paints none here either. Its PF3 arm
   * is L591-L625: it resolves the destination, sets the session flags, issues `EXEC CICS SYNCPOINT`
   * at L616-L618 and `EXEC CICS XCTL` at L620-L623, and touches no message field at any point.
   * `WS-EXIT-MESSAGE` -- `'PF03 pressed. Exiting'`, declared at L251-L252 -- is set at exactly ONE
   * site in the whole program, L642, which is inside the PF2 ADD-transfer arm at L630-L652.
   *
   * Assumptions: ⚠️ Refactoring Rationale: this painted that sentence on PF3, which the reference
   * does not do -- and the sentence is not merely misplaced, it is one the baseline never displays
   * ANYWHERE. `WS-RETURN-MSG` is declared at L249 in WORKING-STORAGE, well above the `LINKAGE
   * SECTION` at L492, so it does not travel in the shared structure; the only statement that copies
   * it to the map is L1557 (`MOVE WS-RETURN-MSG TO ERRMSGO OF CTRTLIAO`) inside the send path, and
   * the PF2 arm that sets it transfers control at L648-L651 without performing that path, which
   * releases this program's working storage. So the assignment at L642 is dead, and moving the
   * sentence to the add arm would not have made it faithful -- it would have shown an operator a
   * string the terminal never showed. Alternatives Considered: painting it on PF2 instead of
   * removing it, and keeping it on PF3 as a courtesy. Both rejected: the first invents a display
   * the baseline lacks, the second attributes a sentence to a key that sets none, and in either
   * case the operator is leaving the screen so the band is replaced by the destination's own
   * before it can be read. The catalog keeps the entry, because the entry records a declared `88`
   * value and its line rather than a claim that this screen renders it.
   * @returns {void} Navigation is performed as a side effect.
   */
  function exitScreen(): void {
    /*
     * WHY : ⚠️ Refactoring Rationale: the write of `LIST_STATUS.WS_EXIT_MESSAGE.text` --
     *       `'PF03 pressed.Exiting'` -- that stood here has been REMOVED, and it was wrong in three
     *       independent ways rather than merely unobservable. It could not render: React batches the
     *       write with the transition, so the route unmounts before any paint, which is the same defect
     *       already removed from `ui/src/screens/accountView/index.tsx`. It was on the wrong KEY: the
     *       only `SET WS-EXIT-MESSAGE TO TRUE` in `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` is at
     *       L642, inside the **PF2** arm that transfers to the add screen, and the PF3 arm at L596-L612
     *       moves navigation fields and transfers control while emitting no message at all. And even on
     *       PF2 the reference does not display it: `WS-EXIT-MESSAGE` is an `88`-level of
     *       `WS-RETURN-MSG`, the only moves into `CCARD-ERROR-MSG` are at L703 and L883 -- both AFTER
     *       the transfer blocks -- and `COTRTUPC.cbl` never reads an inbound `CCARD-ERROR-MSG`, only
     *       writing its own at its L560 and L632. So the sentence reaches no screen in the reference
     *       either.
     * WHY : Alternatives Considered: carrying it to the destination through router state so it could be
     *       announced there. Rejected because it would make this screen the ONLY one that announces an
     *       exit, showing an operator a sentence the terminal never showed them -- an invention, not a
     *       restoration. The message constant stays in the catalog: it is a transcribed literal, and the
     *       catalog records every one of them whether or not a screen displays it.
     */
    setLastPageShown(false);
    navigateSafely(navigate, ADMIN_MENU_ROUTE);
  }

  /**
   * Dispatches the confirmation key without returning its promise to the key handler.
   *
   * Trade-offs: the rejection is swallowed here rather than propagated, because
   * {@link commit} already reduces every failure to a band message and the key-handler
   * contract is synchronous. Re-throwing would reach the application error boundary and
   * replace a screen carrying a usable message with a blank one.
   * @returns {void} The commit runs to completion in the background.
   */
  function requestCommit(): void {
    // WHY: Assumptions: a rejection handler is attached rather than the promise being discarded
    //      with `void`. {@link commit} reduces every failure of the call itself, but it also
    //      re-validates synchronously when the gate is shut, so a fault raised on that path
    //      would reject here with nobody watching. Reducing it through the same mapper keeps the
    //      screen showing a usable sentence instead of surfacing a raw rejection.
    commit().catch(
      /**
       * Reports a fault the commit could not reduce itself.
       * @param {unknown} failure - Whatever the commit rejected with.
       * @returns {void} The band is updated in place.
       */
      (failure: unknown): void => {
        setCommitting(false);
        setBand(describeFailure(failure, pendingAction?.code ?? REF_TYPE_ROW_ACTION_CODES.update));
      },
    );
  }

  /**
   * Wraps a turn so it runs only while no write is in flight.
   *
   * ⚠️ Refactoring Rationale: this guard exists because the delete confirmation's OWN handlers are
   *       reached by pointer without going through the key hook, so the `disabled` declarations below
   *       cannot cover them. The reachable case is CANCEL: it carries no loading state, so it stayed
   *       clickable during a commit, and cancelling mid-write cleared the pending request while the
   *       DELETE was outstanding -- after which the settlement wrote its success sentence for a request
   *       the operator had just withdrawn. The duplicate WRITE the review names arrived by the other
   *       route, PF10, which carried no `disabled` declaration at all and is fixed below.
   * WHY : Assumptions: the KEY handlers are guarded by `disabled: committing` instead of by this wrapper,
   *       and that is not an inconsistency. `ui/src/layout/usePfKeys.ts` evaluates the predicate before
   *       dispatching (L626-L629) and `ui/src/layout/PfKeyBar.tsx` renders `disabled={!binding.enabled}`
   *       and routes its clicks back through the same dispatch, so ONE declaration makes the key
   *       unavailable to the keyboard, unavailable to the pointer, and visibly unavailable in the bar.
   *       Wrapping them here as well would add a second mechanism that could only ever agree with the
   *       first, and a handler that returns silently behind a live-looking key is precisely the defect
   *       finding N3 names on the other screens.
   * WHY : Assumptions: the exclusion has to be written at all because the reference gets it for free. A
   *       CICS task holds the terminal until it returns, so no second attention identifier can arrive
   *       mid-transaction; a browser has no such serialisation and has to reproduce it.
   * @param {() => void} turn - The handler to run when no write is in flight.
   * @returns {() => void} A handler that runs `turn` only while the screen is idle.
   */
  function whenIdle(turn: () => void): () => void {
    /**
     * Runs the wrapped turn unless a write is in flight.
     * @returns {void} Nothing; the turn is skipped while committing.
     */
    return (): void => {
      if (committing) {
        return;
      }
      turn();
    };
  }

  /**
   * Builds the key handlers, and with them the legend descriptors the bar renders.
   *
   * Assumptions: six keys are accepted and the rest behave as ENTER.
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L574-L583 admits ENTER, PF2, PF3, PF7, PF8
   * and -- only while a request is pending -- PF10, then L585-L587 coerces every other
   * attention identifier to ENTER rather than reporting it. Filling the remaining identifiers
   * with the submit handler is what reproduces that coercion; leaving them unbound would make
   * the shared hook report an invalid key, and this program emits no such sentence.
   *
   * Assumptions: PF2 and PF10 are real bindings on this screen even though the migration plan's
   * measured key set lists only ENTER, PF3, PF4, PF5, PF7, PF8 and PF12. That set is drawn from
   * the 17 base mapsets and this is an extension mapset, which paints `F2=Add` and `F10=Save`
   * at `app/app-transaction-type-db2/bms/COTRTLI.bms` L312-L316 and L332-L336. PF10 -- not PF5
   * -- is therefore both the save key and the delete confirmation here, and PF5 is not bound at
   * all. `ui/src/layout/usePfKeys.ts` anticipates exactly this: its
   * `DEFAULT_PF_KEY_ACTIONS` omits PF2 and PF10 so that a screen can bind them without
   * inheriting a misleading global meaning, which is why the semantic action for each is stated
   * here rather than defaulted.
   *
   * Assumptions: ENTER carries no label. The mapset paints five legend fields and none of them
   * is an ENTER legend, and the shared bar drops a binding whose label is empty, so ENTER stays
   * a keyboard-only handler and exactly five controls render.
   *
   * Refactoring Rationale: neither paging key is bound `disabled`, which looks like an omission
   * and is not. The reference accepts both keys at the ends of the browse and answers with a
   * sentence -- `'No previous pages to display'` at L1534 and the pair of exhaustion sentences
   * described on {@link pageForward} -- so refusing the key would replace three measured
   * messages with silence and leave them unreachable. The availability an operator needs is
   * carried by those messages, exactly as the reference carries it.
   * @returns {PfKeyHandlerMap} Handlers for every attention identifier this screen answers.
   */
  function buildPfKeyHandlers(): PfKeyHandlerMap {
    const handlers: Partial<
      Record<CicsAid, { onInvoke: () => void; disabled?: boolean; label?: string }>
    > = {};
    const accepted: readonly CicsAid[] = ['ENTER', 'PFK02', 'PFK03', 'PFK07', 'PFK08', 'PFK10'];

    for (const aid of CICS_AIDS) {
      if (!accepted.includes(aid)) {
        handlers[aid] = { onInvoke: submit, disabled: committing };
      }
    }

    return {
      ...handlers,
      ENTER: { onInvoke: submit, action: 'submit', disabled: committing },
      PFK02: {
        onInvoke: openAddScreen,
        action: 'screen-defined',
        disabled: committing,
        label: REF_TYPE_LIST_KEY_LABELS.PFK02,
      },
      PFK03: {
        onInvoke: exitScreen,
        action: 'back',
        disabled: committing,
        label: REF_TYPE_LIST_KEY_LABELS.PFK03,
      },
      PFK07: {
        onInvoke: pageBackward,
        action: 'page-backward',
        disabled: committing,
        label: REF_TYPE_LIST_KEY_LABELS.PFK07,
      },
      PFK08: {
        onInvoke: pageForward,
        action: 'page-forward',
        disabled: committing,
        label: REF_TYPE_LIST_KEY_LABELS.PFK08,
      },
      // WHY: Assumptions: the semantic action is `save` because that is what PF10 does here,
      //      and the shared bar decides button emphasis from the identifier rather than from
      //      this action. Trade-offs: PF10 therefore renders with default emphasis where PF5
      //      would render as primary on other screens. That is accepted rather than worked
      //      around: `ui/src/layout/PfKeyBar.tsx` owns the emphasis rule and documents its
      //      pairing, so changing it belongs in that module and not in a screen reaching past
      //      its contract.
      PFK10: {
        onInvoke: requestCommit,
        action: 'save',
        disabled: committing,
        label: REF_TYPE_LIST_KEY_LABELS.PFK10,
      },
    };
  }

  const { bindings, invoke } = usePfKeys(buildPfKeyHandlers());

  /*
   * WHY : ⚠️ Refactoring Rationale: an identity-and-legend-only `useShellSlot` call stood here and is
   *       withdrawn in favour of the single COMPLETE publication further down, which additionally
   *       delegates the row-23 message. Both were correct about the delegation itself -- the shell is
   *       mounted once as the authenticated layout route, so a screen that painted its own title band
   *       and legend would show two of each -- but two calls from one component publish twice per
   *       render and only the LAST survives, so the earlier one contributed nothing except the
   *       appearance that this screen disagreed with itself about who paints row 23. The reason the
   *       message is delegated rather than kept local is recorded at the surviving call: the band this
   *       screen used to paint sat inside its own field area, one row above where the mapset declares
   *       the line.
   */
  // WHY: Assumptions: a filtered search that matched nothing is a distinct condition from an
  //      unfiltered one, and it is derived here rather than stored. `1290-CROSS-EDITS` L1241-
  //      L1266 runs only when a filter was supplied, and on a zero count it raises an input
  //      error, marks each SUPPLIED filter in error and sets `FLG-PROTECT-SELECT-ROWS-YES`.
  //      Deriving all three from the delivered page keeps them correct without writing state
  //      during a render, which React forbids.
  // WHY: ⚠️ Refactoring Rationale: the refusal is reached BY EITHER ROUTE, and admitting the
  //      second is the whole of this correction. This predicate previously required
  //      `!browse.isFailed`, which meant it could only ever fire for a filtered read the service
  //      answered 200 with an empty page. But the migrated service does not answer that way:
  //      `TransactionTypeService.requireFilterMatchesSomething` transcribes L1241-L1266
  //      faithfully as an input error, so it raises a `ClientInputException` and the transport
  //      answers 400 -- which a keyset browse reports as a FAILED read. The gate therefore
  //      excluded the only route the condition actually arrives by, and every filtered miss fell
  //      through to the abend arm below and told an operator `UNEXPECTED ABEND OCCURRED.` when
  //      the reference would have marked their filter and left the screen intact. The empty-page
  //      route is retained beside it rather than replaced, because it is what an unfiltered read
  //      and any future 200-answering variant would take, and a predicate that recognised only
  //      the refusal would break the moment the service stopped raising one.
  const filteredEmptyRefused = isFilteredEmptyRefusal(browse.error);

  const filteredEmpty =
    !browse.isLoading &&
    (filteredEmptyRefused || (!browse.isFailed && rows.length === 0)) &&
    (appliedFilters.typeCode !== '' || appliedFilters.description !== '');

  /*
   * WHY : ⚠️ Refactoring Rationale: the filtered-no-match refusal is read from what the SERVICE said, and
   *       this derivation was lost while both of its consumers survived. The service does not answer a
   *       filter that matches nothing with rows: `TransactionTypeService.requireFilterMatchesSomething`
   *       throws `ClientInputException` with code `CARDDEMO-0400`, the verbatim sentence and a
   *       `fieldErrors` entry naming each SUPPLIED filter, which the transport answers 400 -- so a keyset
   *       browse reports it as a failed read.
   * WHY : Assumptions: the refusal is identified by its CODE and by the presence of per-field entries
   *       rather than by matching its sentence. `ui/src/hooks/usePagedQuery.ts` synthesises a document
   *       with an EMPTY field-error array for a timeout or a network fault, so a named filter field is
   *       what separates a service refusal from a transport failure, and a sentence comparison would
   *       break the moment either side re-worded it.
   * WHY : Assumptions: the field names are the service's response-field identities, `typeCode` and
   *       `description`, which `TransactionTypeService.FIELD_TYPE_CODE` and `FIELD_DESCRIPTION` declare
   *       and the published contract carries. They are NOT control identifiers, because a field error is
   *       keyed by the request property the value arrived in.
   */
  const filterRefusal =
    browse.error !== null &&
    browse.error.code === VALIDATION_ERROR_CODE &&
    browse.error.fieldErrors.length > 0
      ? browse.error
      : null;

  /**
   * Reports whether the service refused a named filter on the read now on display.
   * @param {string} field - The service's response-field identity for one filter.
   * @returns {boolean} Whether the refusal named that filter.
   */
  function refusedFilter(field: string): boolean {
    return (
      filterRefusal?.fieldErrors.some(
        /**
         * Reports whether one entry names the filter asked about.
         * @param {FieldError} entry - One per-field entry from the refusal.
         * @returns {boolean} Whether this entry is that filter's.
         */
        (entry: FieldError): boolean => entry.field === field,
      ) === true
    );
  }

  /*
   * WHY : Refactoring Rationale: the flag is named for the REFUSAL and no longer for an empty result,
   *       because the two stopped coinciding once the refusal was read from the service. A refused read
   *       leaves the previous rows on display -- `ui/src/hooks/usePagedQuery.ts` keeps the rows, both
   *       cursors and the ordinal on a failure "so the operator keeps the page in front of them" -- so
   *       the table is generally NOT empty here, and a name saying it was would mislead the next reader
   *       into deriving an empty state from it.
   * WHY : Assumptions: this one flag protects the action column, which is the reference's own coupling.
   *       `1290-CROSS-EDITS` raises the input error, marks each supplied filter AND sets
   *       `FLG-PROTECT-SELECT-ROWS-YES` together at L1252 to L1259, and the attribute pass at L1339 to
   *       L1341 then protects every row's action cell. Protection matters most in exactly the case the
   *       rows are retained: they were read under a narrowing the entry no longer describes, so acting
   *       on one would commit against a page that is no longer the answer to the question on screen.
   */
  const filterRefused = filterRefusal !== null;

  /*
   * WHY : Assumptions: each control is marked only when the refusal NAMED it, rather than whenever the
   *       screen holds a narrowing for it. That is the reference's own rule -- L1253 to L1259 are two
   *       guarded assignments, so a filter the operator left blank is never marked -- and it is also
   *       what the service publishes, since `requireFilterMatchesSomething` adds a field only for a
   *       filter that survived the collapse to "no filter". Deriving it from the applied state instead
   *       would mark a zeroed type entry, which is not a narrowing at all.
   */
  const typeFilterError =
    fieldErrors.typeFilter ??
    (refusedFilter(SERVICE_FILTER_FIELDS.typeCode)
      ? LIST_MESSAGES.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS
      : null);

  const descriptionFilterError =
    fieldErrors.descriptionFilter ??
    (refusedFilter(SERVICE_FILTER_FIELDS.description)
      ? LIST_MESSAGES.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS
      : null);

  const actionCellsDisabled = protectSelectRows || filterRefused || committing;

  /**
   * Chooses the sentence the message band carries when nothing else has set one.
   *
   * Assumptions: the precedence is the reference's own, from `2500-SETUP-MESSAGE` L1504-L1555.
   * A sentence set during this turn wins; a failed read is reported next; an empty result is
   * reported with one of TWO different sentences depending on whether a filter was supplied;
   * and otherwise the standing instruction is shown, which L1550-L1552 reaches whenever no
   * informational message has been chosen.
   *
   * Assumptions: the two empty-result sentences are kept apart deliberately. L1702-L1704 emits
   * `'No records found for this search condition.'` when the FIRST page of an unfiltered read
   * comes back with no rows, while L1251-L1265 emits
   * `'No Records found for these filter conditions'` when a supplied filter matched nothing --
   * and that one also disables the action column and marks the filters. They differ in
   * capitalisation and in the trailing full stop as well as in trigger, so merging them would
   * break the verbatim guarantee twice over.
   *
   * Assumptions: a failed read reports the redacted replacement rather than the baseline's
   * decorated text. `ui/src/messages/messages.ts` records `'Error reading TRANSACTION_TYPE
   * table '` -- emitted at L1826 through `9999-FORMAT-DB2-MESSAGE` -- as redacted because it
   * arrived with the SQL code and diagnostic text attached, and names this replacement.
   * @returns {BandMessage} The band's text and severity.
   */
  function deriveBand(): BandMessage {
    if (band !== null) {
      return band;
    }

    // WHY: ⚠️ Assumptions: the filtered-empty refusal is tested BEFORE the failure arm, and the
    //      order is what makes the correction work. A 400 whose document names a filter is not a
    //      fault -- it is L1251-L1265's input error, which the reference reports with its own
    //      sentence on an otherwise intact screen -- so reaching the abend arm with it would
    //      report the reference's gentlest outcome as its harshest. Every other failure still
    //      reaches that arm, because no other 400 the browse can receive attributes itself to a
    //      filter field.
    if (filteredEmpty) {
      return {
        text: LIST_MESSAGES.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS,
        severity: 'error',
      };
    }

    if (browse.isFailed) {
      return { text: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED, severity: 'error' };
    }

    if (browse.isLoading) {
      return { text: '', severity: 'info' };
    }

    // WHY: Assumptions: this arm is now reached only by an UNFILTERED empty read, because the
    //      filtered one is answered above. Its sentence differs from that one in capitalisation and
    //      in the trailing full stop as well as in trigger -- L1702-L1704 against L1251-L1265 --
    //      and the two are deliberately never merged.
    if (rows.length === 0) {
      return { text: LIST_STATUS.WS_MESG_NO_RECORDS_FOUND.text, severity: 'error' };
    }

    return { text: LIST_STATUS.WS_INFORM_REC_ACTIONS.text, severity: 'info' };
  }

  const displayedBand = deriveBand();

  /*
   * WHY : Refactoring Rationale: the title band, the row-23 message line and the row-24 legend are
   *       DELEGATED to the one `AppShell` that `ui/src/App.tsx` mounts, where this screen composed all
   *       three itself. Per-screen composition is what the tree did before the shell was wired in, and
   *       keeping it afterwards would render a second title band, a second message line and a second
   *       named legend region on this screen. Everything the mapset paints between rows 4 and 21 -- the
   *       heading and page ordinal, the two filters, the grid and the add row -- stays here.
   * WHY : ⚠️ Assumptions: delegating `pfKeys` hands the shell the bindings to RENDER and leaves
   *       this screen owning the keyboard; an activation of a rendered legend control is forwarded
   *       straight back to `invoke`. The claim that stood here is withdrawn in its second half: it is
   *       true that `usePfKeys` installs one document listener per call site, but the shell installs NONE
   *       of them -- it offers sign-off as a rendered control, for the reason recorded at
   *       `SHELL_SIGN_OFF_LABEL`. There is nothing to make stand down, so the publication buys the
   *       painted legend rather than sole ownership of the keyboard.
   * WHY : Assumptions: `legendColor` IS delegated, because all five legend fields carry
   *       `COLOR=TURQUOISE` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L312-L336, whereas the
   *       slot defaults to the yellow that 15 of the 17 base mapsets use.
   */
  useShellSlot({
    screen: {
      transactionId: REF_TYPE_LIST_TRANSACTION_ID,
      programName: REF_TYPE_LIST_PROGRAM_NAME,
    },
    now: paintedAt,
    message: {
      text: displayedBand.text,
      severity: displayedBand.severity,
      mapset: REF_TYPE_LIST_MAPSET,
    },
    pfKeys: { keys: bindings, onInvoke: invoke, legendColor: 'TURQUOISE' },
  });

  /**
   * Reports whether a row's description is open for editing.
   *
   * Assumptions: this is the single most easily mistaken rule on the screen. The mapset
   * declares `TRTYPD1`-`TRTYPD7` as `UNPROT` at
   * `app/app-transaction-type-db2/bms/COTRTLI.bms` L147-L277, but the program overrides that on
   * every send: `2200-SETUP-ARRAY-ATTRIBS` L1337 moves `DFHBMPRF` into every row's description
   * attribute, making them all protected, and only L1365 moves `DFHBMFSE` back -- for the one
   * row carrying `U`, guarded by `WS-ONLY-1-VALID-ACTION` and `FLG-BAD-ACTIONS-SELECTED-NO`,
   * and only while `FLG-UPDATE-COMPLETED` is unset. So the grid is not a spreadsheet: exactly
   * one cell is ever editable, and only after ENTER has accepted the request. Keying it on the
   * pending request rather than on the live cell entry is what defers it to ENTER, since a
   * pending request exists only once {@link submit} has validated one.
   * @param {TransactionType} row - The row being rendered.
   * @returns {boolean} Whether this row's description should render as an editor.
   */
  function isDescriptionEditable(row: TransactionType): boolean {
    return (
      pendingAction !== null &&
      pendingAction.code === REF_TYPE_ROW_ACTION_CODES.update &&
      pendingAction.typeCd === row.typeCd &&
      !updateCompleted
    );
  }

  /**
   * Reports whether a row is the one awaiting confirmation, and so carries the emphasis.
   *
   * Assumptions: emphasis is applied to both the key and the description of the awaiting row.
   * L1351-L1352 moves `DFHNEUTR` into `TRTTYPC(I)` and `TRTYPDC(I)` together for a pending
   * delete, and L1359 does the same to the key for a pending update -- which is what the
   * reference's own sentences mean when they say `HIGHLIGHTED row`.
   * @param {TransactionType} row - The row being rendered.
   * @returns {boolean} Whether this row is awaiting confirmation.
   */
  function isAwaitingConfirmation(row: TransactionType): boolean {
    return pendingAction !== null && pendingAction.typeCd === row.typeCd;
  }

  /**
   * Renders one row's action cell, wrapping it in the destructive confirmation when that row is
   * the one awaiting a delete.
   *
   * Assumptions: a one-character text entry, not a radio or a checkbox. The cell accepts two
   * different letters and rejects a third with `'Action code selected is invalid'` at
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` L1034-L1038, and a control that can only
   * offer valid choices would make that measured message unreachable. `TRTSEL1`-`TRTSEL7` are
   * `LENGTH=1` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L133-L263, which is where the
   * width comes from.
   *
   * Assumptions: the cell is disabled for an empty row and whenever the protect flag is raised.
   * L1339-L1341 moves `DFHBMPRO` into the action attribute when the row is `LOW-VALUES` or
   * `FLG-PROTECT-SELECT-ROWS-YES` is set, and L1371 moves `DFHBMFSE` in for a populated row --
   * so availability follows the row and the flag, not the operator.
   *
   * Assumptions: the confirmation is the design system's `Popconfirm` anchored to this cell,
   * with `okType="danger"`. The migration plan maps the terminal's re-key-to-confirm convention
   * onto that component, and anchoring it to the row it acts on is what makes the reference's
   * `Delete HIGHLIGHTED row ?` refer to something visible. Confirming calls the same
   * {@link commit} PF10 dispatches, so the two routes cannot diverge.
   * Assumptions: the cell is addressed by its row's KEY rather than by the whole row, because
   * the key is all it needs -- the entry, the error flag, the accessible name and the pending
   * comparison are every one of them looked up by it. The column is bound to `typeCd` for the
   * same reason: a render function receives the column's BOUND VALUE as its first argument and
   * the row only as its second, so binding this column to a member the row does not carry would
   * hand the renderer `undefined`.
   * @param {string} typeCd - Key of the row being rendered.
   * @returns {ReactElement} The cell's control.
   */
  function renderActionCell(typeCd: string): ReactElement {
    const inError = rowErrorKeys.includes(typeCd);
    const controlId = actionCellId(typeCd);
    /*
     * WHY : ⚠️ Assumptions: the cell is described only while it is BOTH marked and a sentence is
     *       held, and both halves are needed. A marked cell with no sentence would point
     *       `aria-describedby` at an element this render does not emit, which is a dangling reference
     *       some assistive technologies announce as nothing and others skip -- the exact failure
     *       `fieldAriaProps` documents and asks its caller to state.
     */
    const refusal = inError ? rowErrorMessage : null;

    const cell = (
      <Form.Item
        help={refusal === null ? undefined : fieldErrorHelp(controlId, refusal)}
        /*
         * WHY : Assumptions: `noStyle` while there is nothing to explain, so an unmarked cell keeps the
         *       grid's own row height and no form-item spacing is introduced into a table cell. The
         *       styled form is taken only when the item has help text to lay out, which is the same
         *       arrangement the description editor beside it uses.
         */
        noStyle={refusal === null}
        validateStatus={refusal === null ? '' : 'error'}
      >
        <Input
          {...fieldAriaProps(controlId, {
            hasError: refusal !== null,
            hasHint: false,
            invalid: inError,
          })}
          aria-label={`${REF_TYPE_LIST_LABELS.selectColumn.trim()} ${typeCd}`}
          disabled={actionCellsDisabled}
          id={controlId}
          maxLength={ACTION_CODE_LENGTH}
          onChange={
            /**
             * Records the action code typed beside this row.
             * @param {object} event - The change event the design system forwards.
             * @param {object} event.target - The control the event came from.
             * @param {string} event.target.value - The entry as it now stands.
             * @returns {void} State is updated in place.
             */
            (event: { target: { value: string } }): void => {
              const { value } = event.target;
              setActionCodes(
                /**
                 * Replaces this row's entry, leaving the other rows' entries alone.
                 * @param {Readonly<Record<string, string>>} current - Entries so far.
                 * @returns {Readonly<Record<string, string>>} Entries with this row's replaced.
                 */
                (current: Readonly<Record<string, string>>): Readonly<Record<string, string>> => ({
                  ...current,
                  [typeCd]: value,
                }),
              );
            }
          }
          status={inError ? 'error' : ''}
          value={actionCodes[typeCd] ?? ''}
        />
      </Form.Item>
    );

    if (pendingAction === null || pendingAction.code !== REF_TYPE_ROW_ACTION_CODES.delete) {
      return cell;
    }

    if (pendingAction.typeCd !== typeCd) {
      return cell;
    }

    return (
      // WHY: Trade-offs: `okType="danger"` is kept exactly as the migration plan's component
      //      mapping prescribes, even though this design-system major renders it as an OUTLINED
      //      dangerous button -- measured in a browser as red text and a red border on a white
      //      ground rather than a solid red fill. A solid fill would take `danger` and `primary`
      //      passed through the button's own props, which is a heavier visual treatment than the
      //      mapping asks for; design-system compliance outranks that preference. The affordance
      //      still does not rest on colour alone, which is what accessibility requires here: the
      //      title is the reference's own `Delete HIGHLIGHTED row ?` sentence and the row it acts
      //      on is de-emphasised at the same time, so the destructive sense is carried by text
      //      and by position as well as by the error colour.
      <Popconfirm
        // WHY: Assumptions: the OK control is DISABLED as well as loading, and the CANCEL control is
        //      what the guard below is actually for. Measured in this suite: antd's `Button` returns
        //      early from its own click handler while `loading` is set, so the OK button was already
        //      inert during a commit -- `disabled` adds nothing to that except making the
        //      unavailability explicit to assistive technology, which a busy spinner does not state.
        //      Cancel carries no loading state, so it stayed clickable: cancelling mid-write cleared
        //      the pending request while the DELETE was outstanding, and the settlement then wrote its
        //      success sentence for a request the operator had just withdrawn. `whenIdle` on both
        //      handlers is what closes that, and it does not depend on a component internal that could
        //      change.
        okButtonProps={{ disabled: committing, loading: committing }}
        okType="danger"
        onConfirm={whenIdle(requestCommit)}
        onCancel={whenIdle(clearPendingAction)}
        open
        title={LIST_STATUS.WS_INFORM_DELETE.text}
      >
        {cell}
      </Popconfirm>
    );
  }

  /**
   * Renders one row's description cell, as an editor for the row being updated and as text
   * everywhere else.
   *
   * Assumptions: the blank marker is rendered beside the entry rather than written into it.
   * `2300-SCREEN-ARRAY-INIT` L1412-L1415 moves `LIT-ASTERISK` into the description field itself
   * when the edit found it blank, which on a terminal is a marker the operator types over.
   * Writing it into a live-bound control instead would make `*` the value the next save
   * submitted, so it is rendered as an adornment carrying the field-error colour the theme
   * bridge names. Trade-offs: the marker is beside the entry rather than in it -- the only
   * departure from `app/cpy/CSSETATY.cpy` L23-L25 -- and it is taken because the alternative
   * corrupts data.
   * @param {TransactionType} row - The row being rendered.
   * @param {string} stored - The description the column is bound to, which is the row's stored
   *   value and the fallback the editor opens with.
   * @returns {ReactElement} The cell's content.
   */
  function renderDescriptionCell(row: TransactionType, stored: string): ReactElement {
    if (!isDescriptionEditable(row)) {
      return (
        <Typography.Text
          style={
            isAwaitingConfirmation(row)
              ? { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] }
              : { color: cssVar[BMS_TEXT_COLOR_TOKENS.DEFAULT] }
          }
        >
          {stored}
        </Typography.Text>
      );
    }

    const entry = descriptionDrafts[row.typeCd] ?? stored;
    const blank = entry.trim() === '';
    const editorId = descriptionEditorId(row.typeCd);

    return (
      <Form.Item
        help={
          fieldErrors.description === null
            ? undefined
            : fieldErrorHelp(editorId, fieldErrors.description)
        }
        noStyle={fieldErrors.description === null}
        validateStatus={fieldErrors.description === null ? '' : 'error'}
      >
        <Input
          {...fieldAriaProps(editorId, {
            hasError: fieldErrors.description !== null,
            hasHint: false,
            invalid: fieldErrors.description !== null,
          })}
          aria-label={`${REF_TYPE_LIST_LABELS.descriptionColumn} ${row.typeCd}`}
          autoFocus
          id={editorId}
          maxLength={DESCRIPTION_LENGTH}
          onChange={
            /**
             * Records the edited description for this row.
             * @param {object} event - The change event the design system forwards.
             * @param {object} event.target - The control the event came from.
             * @param {string} event.target.value - The entry as it now stands.
             * @returns {void} State is updated in place.
             */
            (event: { target: { value: string } }): void => {
              const { value } = event.target;
              setDescriptionDrafts(
                /**
                 * Replaces this row's draft, leaving other rows' drafts alone.
                 * @param {Readonly<Record<string, string>>} current - Drafts so far.
                 * @returns {Readonly<Record<string, string>>} Drafts with this row's replaced.
                 */
                (current: Readonly<Record<string, string>>): Readonly<Record<string, string>> => ({
                  ...current,
                  [row.typeCd]: value,
                }),
              );
            }
          }
          status={fieldErrors.description === null ? '' : 'error'}
          suffix={
            blank ? (
              /*
               * WHY : Refactoring Rationale: the marker is `aria-hidden`, which it was not. It is a
               *       DECORATION standing in for the `'*'` the terminal wrote into the field itself
               *       (`2300-SCREEN-ARRAY-INIT` L1412-L1415), and it sits inside the input's own suffix
               *       -- so without this a reader announced a lone asterisk as part of the control it
               *       had just been told was invalid, saying the same thing twice in a form the
               *       operator cannot act on. The refusal reaches them through `aria-describedby`
               *       above, in words. This matches the treatment
               *       `ui/src/screens/accountUpdate/index.tsx` applies to the same marker.
               */
              <Typography.Text
                aria-hidden="true"
                style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.RED] }}
              >
                {FIELD_ERROR_TOKENS.blankMarker}
              </Typography.Text>
            ) : null
          }
          value={entry}
        />
      </Form.Item>
    );
  }

  /**
   * Builds the grid's three columns.
   *
   * Assumptions: three columns and no more, matching the three field families the mapset paints
   * per data row -- the one-character action cell, the two-character key and the fifty-character
   * description at `app/app-transaction-type-db2/bms/COTRTLI.bms` L133-L279. Their headings are
   * the row-10 literals at L108-L119.
   *
   * Assumptions: the key column is never editable while the description is. `TRTTYP1`-`TRTTYP7`
   * carry `PROT` and are additionally left protected by every arm of the attribute pass, whereas
   * the description is re-opened at L1365 for the row being updated. The asymmetry is the point:
   * the key is the primary key of `CARDDEMO.TRANSACTION_TYPE` per
   * `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L4, and the update statement at L1846-L1850
   * sets only the description while matching on the key, so an editable key would have nothing
   * to write through.
   *
   * Trade-offs: three grouped columns replace 81 absolutely positioned fields, which is design
   * gap G1 in the migration plan. Field grouping, reading order and tab order are preserved;
   * pixel-for-character positioning is not, because reproducing a fixed character grid in a
   * browser cannot be made responsive and would be hostile to assistive technology.
   *
   * Assumptions: the headings are trimmed for display even though `selectColumn` is stored with
   * its four trailing spaces. The untrimmed literal remains available on
   * {@link REF_TYPE_LIST_LABELS} so the byte-exact form can still be asserted, while a heading
   * used as an accessible name must not carry trailing blanks, since they are announced.
   * @returns {TableColumnsType<TransactionType>} The column definitions, in display order.
   */
  function buildColumns(): TableColumnsType<TransactionType> {
    return [
      {
        dataIndex: 'typeCd',
        key: 'action',
        render:
          /**
           * Renders the row's action cell.
           * @param {string} typeCd - Key of the row this cell belongs to.
           * @returns {ReactElement} The cell's control.
           */
          (typeCd: string): ReactElement => renderActionCell(typeCd),
        title: REF_TYPE_LIST_LABELS.selectColumn.trim(),
        width: COLUMN_WIDTH_SHARES.action,
      },
      {
        dataIndex: 'typeCd',
        key: 'typeCd',
        // WHY: Assumptions: the key renders in the theme's fixed-pitch family, which the token
        //      bridge names `fixedPitchData`. A two-character code read down a column only lines
        //      up in a monospaced face, which is the alignment the terminal had for free.
        render:
          /**
           * Renders the row's key as protected, fixed-pitch text.
           * @param {string} typeCd - The two-character type code.
           * @param {TransactionType} row - The row being rendered.
           * @returns {ReactElement} The key, never as an editable control.
           */
          (typeCd: string, row: TransactionType): ReactElement => (
            <Typography.Text
              style={{
                color: isAwaitingConfirmation(row)
                  ? cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL]
                  : cssVar[BMS_TEXT_COLOR_TOKENS.DEFAULT],
                fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
              }}
            >
              {typeCd}
            </Typography.Text>
          ),
        title: REF_TYPE_LIST_LABELS.typeColumn,
        width: COLUMN_WIDTH_SHARES.typeCd,
      },
      {
        dataIndex: 'description',
        key: 'description',
        render:
          /**
           * Renders the row's description, editable only for the row being updated.
           * @param {string} description - The stored description this column is bound to.
           * @param {TransactionType} row - The row being rendered.
           * @returns {ReactElement} The cell's content.
           */
          (description: string, row: TransactionType): ReactElement =>
            renderDescriptionCell(row, description),
        title: REF_TYPE_LIST_LABELS.descriptionColumn,
        width: COLUMN_WIDTH_SHARES.description,
      },
    ];
  }

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the heading and the page number share row 4 in the mapset -- the heading at
       * `POS=(4,28)` and `Page ` with `PAGENO` at `POS=(4,70)` and `POS=(4,76)` -- so they are
       * composed as one band here rather than stacked. Both carry `COLOR=NEUTRAL`.
       * Assumptions: the page number is the browse's display-only ordinal. The keyset envelope
       * publishes no total and no page index, because a keyset browse does not know how many
       * pages follow it, exactly as `WS-CA-SCREEN-NUM` at L403 counts the pages an operator has
       * walked rather than the pages that exist.
       */}
      <Flex align="baseline" gap="middle" justify="space-between" wrap>
        <ScreenTitle style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] }}>
          {REF_TYPE_LIST_LABELS.heading}
        </ScreenTitle>
        <Typography.Text style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] }}>
          {`${REF_TYPE_LIST_LABELS.pagePrefix}${String(browse.pageNumber)}`}
        </Typography.Text>
      </Flex>
      {/*
       * Assumptions: both prompts are associated with their controls by identifier rather than
       * by adjacency. The mapset identified each by position alone -- `POS=(6,30)` beside
       * `POS=(6,44)` and `POS=(8,4)` beside `POS=(8,25)` -- and position names nothing to a
       * screen reader.
       * Assumptions: only the type filter takes focus on arrival. `IC` appears exactly ONCE in
       * the whole mapset, on `TRTYPE` at L89, so a second autofocus would be an invention.
       */}
      {/*
       * WHY : Refactoring Rationale: both prompts previously rendered in the turquoise hue token
       *       and measured #13c2c2 on #ffffff = 2.2:1, below the WCAG AA minimum of 4.5:1 for
       *       body text. That shortfall was flagged here and shipped, on the reasoning that the
       *       token is shared and re-deciding it in one screen would fork the bridge. The
       *       reasoning about WHERE to fix it was right and the decision to ship was wrong: the
       *       remedy was applied in `ui/src/theme/tokens.ts`, once, for every screen, by
       *       publishing a text-grade companion to the hue map. These prompts still carry the
       *       measured `COLOR=TURQUOISE` role from
       *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L86 and L98; they now resolve it
       *       through `BMS_TEXT_COLOR_TOKENS`, which measures 6.978:1 against the surface the
       *       shell paints, and `ui/src/theme/textContrast.test.ts` fails the build if any role
       *       falls back below the threshold. Colour was never the sole carrier of meaning here
       *       either -- each prompt is also the control's accessible name through
       *       `aria-labelledby` and its `for` association.
       */}
      <Flex gap="large" wrap>
        {/*
         * WHY: Assumptions: the design system's automatic label punctuation is switched off on
         *      both filters because the mapset's literals ALREADY carry it. `INITIAL='Type
         *      Filter:'` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L88 and
         *      `INITIAL='Description Filter:'` at L100 both end in a colon, and the framework
         *      appends a second one of its own through a `label::after` rule -- measured in a
         *      browser as `Type Filter::` and `Description Filter::`.
         *      Alternatives Considered: trimming the colon off the two label constants instead.
         *      Rejected because those constants are byte-exact transcriptions of `INITIAL=`
         *      operands under transformation rule T8, and editing one to work around a framework
         *      default would break the verbatim contract that the whole message and label
         *      inventory rests on. Suppressing the framework's addition keeps the source literal
         *      intact and renders it exactly once.
         */}
        {/*
         * WHY : ⚠️ Refactoring Rationale: the refusal is rendered through `fieldErrorHelp` and the control
         *       carries `fieldAriaProps`, where the sentence was passed as a bare string. The design
         *       system generates an `aria-describedby` only for a control it MANAGES -- a `Form.Item`
         *       with a `name`, inside a `Form` -- and these two filters are controlled inputs outside
         *       one, so the help text rendered visibly and was announced to nobody: an operator using a
         *       screen reader heard the field's name and nothing about why it had been refused.
         * WHY : Alternatives Considered: converting the two filters into named `Form` fields so the
         *       framework would wire them. Rejected because the entries are read by the ENTER turn from
         *       component state and a form would introduce a submit path of its own, competing with the
         *       key handler that already owns ENTER on this screen -- the same reason
         *       `ui/src/screens/cardList/index.tsx` records for its own filters.
         */}
        <Form.Item
          colon={false}
          help={
            typeFilterError === null
              ? undefined
              : fieldErrorHelp(TYPE_FILTER_INPUT_ID, typeFilterError)
          }
          htmlFor={TYPE_FILTER_INPUT_ID}
          label={
            <Typography.Text
              id={TYPE_FILTER_LABEL_ID}
              style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] }}
            >
              {REF_TYPE_LIST_LABELS.typeFilter}
            </Typography.Text>
          }
          validateStatus={typeFilterError === null ? '' : 'error'}
        >
          <Space.Compact>
            <Input
              {...fieldAriaProps(TYPE_FILTER_INPUT_ID, {
                hasError: typeFilterError !== null,
                hasHint: false,
                invalid: typeFilterError !== null,
              })}
              aria-labelledby={TYPE_FILTER_LABEL_ID}
              autoFocus
              id={TYPE_FILTER_INPUT_ID}
              inputMode="numeric"
              maxLength={TYPE_CODE_LENGTH}
              onChange={
                /**
                 * Records the type-code filter entry.
                 * @param {object} event - The change event the design system forwards.
                 * @param {object} event.target - The control the event came from.
                 * @param {string} event.target.value - The entry as it now stands.
                 * @returns {void} State is updated in place.
                 */
                (event: { target: { value: string } }): void => {
                  setTypeFilterDraft(event.target.value);
                }
              }
              status={typeFilterError === null ? '' : 'error'}
              value={typeFilterDraft}
            />
          </Space.Compact>
        </Form.Item>
        <Form.Item
          colon={false}
          help={
            descriptionFilterError === null
              ? undefined
              : fieldErrorHelp(DESCRIPTION_FILTER_INPUT_ID, descriptionFilterError)
          }
          htmlFor={DESCRIPTION_FILTER_INPUT_ID}
          label={
            <Typography.Text
              id={DESCRIPTION_FILTER_LABEL_ID}
              style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] }}
            >
              {REF_TYPE_LIST_LABELS.descriptionFilter}
            </Typography.Text>
          }
          validateStatus={descriptionFilterError === null ? '' : 'error'}
        >
          <Input
            {...fieldAriaProps(DESCRIPTION_FILTER_INPUT_ID, {
              hasError: descriptionFilterError !== null,
              hasHint: false,
              invalid: descriptionFilterError !== null,
            })}
            aria-labelledby={DESCRIPTION_FILTER_LABEL_ID}
            id={DESCRIPTION_FILTER_INPUT_ID}
            maxLength={DESCRIPTION_LENGTH}
            onChange={
              /**
               * Records the description filter entry.
               * @param {object} event - The change event the design system forwards.
               * @param {object} event.target - The control the event came from.
               * @param {string} event.target.value - The entry as it now stands.
               * @returns {void} State is updated in place.
               */
              (event: { target: { value: string } }): void => {
                setDescriptionFilterDraft(event.target.value);
              }
            }
            status={descriptionFilterError === null ? '' : 'error'}
            value={descriptionFilterDraft}
          />
        </Form.Item>
      </Flex>
      {/*
       * WHY: Alternatives Considered: the design system's built-in pager, which counts rows from
       *      the start of the set. Rejected because it does not survive concurrent insertion --
       *      a row inserted ahead of the reading position pushes one row onto the following page
       *      and repeats another -- whereas the reference addresses a page by the key it starts
       *      from, carrying `WS-CA-FIRST-TR-CODE` and `WS-CA-LAST-TR-CODE` at L398-L401. Keyset
       *      paging is therefore a faithful mapping rather than an approximation, and the
       *      built-in pager is switched off so the two cannot both be active.
       */}
      <Table<TransactionType>
        columns={buildColumns()}
        dataSource={rows}
        loading={browse.isLoading}
        pagination={false}
        rowKey={
          /**
           * Identifies a row by its primary key.
           * @param {TransactionType} row - One browse row.
           * @returns {string} The two-character type code, unique per
           *   `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L4.
           */
          (row: TransactionType): string => row.typeCd
        }
        /*
         * WHY: Assumptions: the column shares on {@link COLUMN_WIDTH_SHARES} are only binding
         *      under a fixed layout. Left on the automatic layout a browser treats a column
         *      width as a minimum and lets content grow it, which is how the one-character
         *      action column came to measure wider than the fifty-character description. Fixing
         *      the layout makes the mapset-derived shares authoritative.
         */
        tableLayout="fixed"
      />
      {/*
       * WHY: Assumptions: the row-19 area is a SEPARATE element and never an eighth grid row.
       *      `TRTSELA`, `TRTTYPA` and `TRTDSCA` at
       *      `app/app-transaction-type-db2/bms/COTRTLI.bms` L280-L298 are three independently
       *      named fields outside the seven `TRTSEL1`-`TRTSEL7` families, and all three carry
       *      `PROT` -- so the reference paints them and never accepts input through them. Adding
       *      them to the grid would make the page eight rows and break the arity that L60, the
       *      two `OCCURS 7` arrays and the seven field families all agree on. The row is rendered as
       *      STATIC space at the grid's own column shares; the route to actually add a type is F2,
       *      which transfers to the maintenance screen.
       *      Assumptions: the identically named `TRTSELA`/`TRTTYPA`/`TRTYPDA` in
       *      `COTRTLIC.cbl` L433-L475 are NOT these fields -- they are the attribute bytes of
       *      the seven grid rows, redefined over the symbolic map. Conflating the two is the
       *      trap this comment exists to close.
       */}
      {/*
       * WHY: Alternatives Considered: laying the three fields out with the flow container the
       *      other groups on this screen use. Rejected on measurement -- because a design-system
       *      `Input` fills its container, all three came out the full width of the page and
       *      stacked vertically, which reads as three unrelated boxes rather than as one row and
       *      loses the alignment with the columns above that the mapset gives them by painting
       *      them at the SAME columns as rows 12-18: `POS=(19,6)`, `POS=(19,17)` and
       *      `POS=(19,25)` at `app/app-transaction-type-db2/bms/COTRTLI.bms` L280-L298 repeat
       *      the grid's own column positions exactly. Sharing the grid's mapset-derived column
       *      shares restores both the single line and that vertical alignment.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the three fields are STATIC cells and were `disabled`
       *       `Input`s carrying accessible names and permanently empty values. Two things were wrong with
       *       that. Against the mapset: all three carry `HILIGHT=OFF` at
       *       `app/app-transaction-type-db2/bms/COTRTLI.bms` L280-L298, where the grid's own selection
       *       fields carry `HILIGHT=UNDERLINE` -- and design gap G4 records that the underline is exactly
       *       what an `Input` border stands in for, so painting a border here drew an affordance the
       *       terminal deliberately did not. Against the operator: three named but permanently
       *       unavailable controls entered the accessibility tree, so a screen reader announced three
       *       empty dimmed fields with nothing to do, on a row the reference never accepted input
       *       through -- all three are `PROT`.
       * WHY : Alternatives Considered: omitting the row entirely, which the finding also admits.
       *       Rejected because the mapset paints it and a later reader comparing the two would find a
       *       declared row missing with nothing to say why; keeping it as space preserves the structure
       *       and the column alignment with rows 12-18 while removing what was wrong with it.
       * WHY : Assumptions: the row's height comes from `controlHeight`, the same token the grid's own
       *       controls take theirs from, so the space it occupies matches one grid row rather than being
       *       a chosen number. Empty cells alone would collapse to nothing and the painted row would
       *       vanish.
       */}
      <Row style={{ minBlockSize: cssVar.controlHeight }}>
        <Col flex={COLUMN_WIDTH_SHARES.action} />
        <Col flex={COLUMN_WIDTH_SHARES.typeCd} />
        <Col flex={COLUMN_WIDTH_SHARES.description} />
      </Row>
      {/*
       * Refactoring Rationale: the key legend that used to close this body, and the message line above
       * the grid, are delegated to the shell in the `useShellSlot` call above. One delegated band still
       * carries BOTH of the mapset's message fields: `INFOMSG` on row 21 and `ERRMSG` on row 23 are
       * separate fields, but the program blanks the informational one at the start of every send (L1320)
       * and its message precedence at L1504-L1555 chooses one sentence per turn, so a single line shows
       * what an operator would have read. The 75-character content contract is still enforced from the
       * mapset name, which travels with the delegation.
       */}
    </Flex>
  );
}
