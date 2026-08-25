/**
 * @file The user browse screen, migrated from `app/cbl/COUSR00C.cbl` and its mapset
 * `app/bms/COUSR00.bms` (map `COUSR0A`, 89 `DFHMDF` fields), reached at route `/users`.
 *
 * Purpose
 * -------
 * Render one keyset-paged page of the security file's user rows with the reference screen's own
 * search field, its per-row action cell and its four-key workflow, and publish the label, action-code
 * and reducer constants the screen tests assert against. It replaces CICS transaction CU00, which
 * `app/csd/CARDDEMO.CSD` L449-L450 binds to that program, and it exists because decision D6 replaces
 * all 21 online screens with a browser SPA rather than leaving the 3270 datastream in place.
 *
 * Paging contract
 * ---------------
 * Assumptions: the reference's browse state is ALREADY a keyset cursor rather than an approximation of
 * one. `CDEMO-CU00-INFO` carries `USRID-FIRST X(08)`, `USRID-LAST X(08)` and
 * `NEXT-PAGE-FLG X(01)` with `88 NEXT-PAGE-YES VALUE 'Y'`, which map one for one onto the
 * `firstKey`, `lastKey` and `hasNext` members of the four-member envelope in `ui/src/api/types.ts`;
 * and `PROCESS-PAGE-FORWARD` sets the flag by reading one record BEYOND the ten the screen shows. The
 * translation is therefore exact, so any drift toward offset paging would be a regression rather than
 * a simplification.
 *
 * Assumptions: the backward step is decided by a screen ORDINAL and not by any member of the
 * envelope, because that is where the reference decides it -- `PROCESS-PF7-KEY` refuses with
 * `You are already at the top of the page...` purely on `CDEMO-CU00-PAGE-NUM > 1` and issues no
 * probe read to answer the question. That ordinal now lives in `ui/src/hooks/usePagedQuery.ts`, which
 * publishes it as `hasPrev`, per AAP section 0.7.1 moving navigation state client-side.
 *
 * Positioning contract
 * --------------------
 * ⚠️ Assumptions: the search field is a browse POSITIONING key and not a substring search, which is why
 * this section is not headed "narrowing" -- it does not reduce a set, it chooses where reading begins.
 * The reference moves `USRIDINI` into `SEC-USR-ID` and hands it to `STARTBR ... RIDFLD(SEC-USR-ID)`,
 * which opens the browse AT OR AFTER the identifier typed; a blank entry becomes `LOW-VALUES` and opens
 * at the start of the file.
 *
 * ⚠️ Refactoring Rationale: that positioning is pushed into the REQUEST, as the `startUserId` member of
 * `UserListQuery`, and it used to be applied client-side to the page this screen had already been
 * handed. The consequence of the client-side form was not a rounding error: the delivered page is ten
 * rows, so an identifier sorting past the tenth stored row narrowed that page to NOTHING and the
 * operator saw an empty table where the terminal positioned directly at the key. Only the server can
 * read past the page the client holds, so positioning is the server's work.
 *
 * ⚠️ Assumptions: positioning is INCLUSIVE and belongs to the OPENING read alone. The row the operator
 * types is the first row of the page -- the reference skips its stepping `READNEXT` on the enter turn,
 * guarded at L288, so the row the `STARTBR` landed on is the row at the top of the screen -- and an
 * identifier no row carries positions on the next one rather than refusing, which is why the reference
 * answers an unmatched seek with a boundary sentence at L603 and not a not-found. Once a page is held,
 * F7 and F8 continue from the cursors that page returned and the identifier is not sent again: the
 * service refuses a position and a cursor together, because a request stating two positions cannot say
 * which was meant, and one reference turn was a seek or a page move but never both.
 *
 * Alternatives Considered, and why each is worse than a request parameter: (1) minting a cursor here
 * from the typed identifier, rejected because a cursor is an opaque token the service SEALS --
 * including the direction it was issued for -- so a fabricated one is refused with HTTP 400 rather than
 * answered, and `types.ts` states the client neither parses, compares nor constructs one; (2) paging
 * forward repeatedly until the key is reached, rejected because it turns one keystroke into an
 * unbounded number of requests where the reference issues a single `STARTBR`; (3) keeping the
 * client-side filter as well as sending the parameter, rejected because the server has already
 * positioned the page, so a second comparison here could only remove rows the service deliberately
 * returned.
 *
 * Disclosure
 * ----------
 * ⚠️ Refactoring Rationale: no credential appears on this screen and none may be added. The baseline
 * record carries `SEC-USR-PWD PIC X(08)`, an eight-character PLAINTEXT password, at
 * `app/cpy/CSUSR01Y.cpy` L21, and
 * `app/cbl/COSGN00C.cbl` L211-L256 compares it directly; AAP section 0.7.8 declines parity with that
 * one behaviour deliberately, so `UserSummary` declares four members with no credential among them.
 * There is consequently nothing here to omit rather than a column withheld.
 *
 * Assumptions: `SEC-USR-FILLER PIC X(23)` is dropped. It is padding to the eighty-byte record rather
 * than data, and transformation rule T1 records each such drop at the point it happens.
 */

import { Flex, Form, Input, Table, Typography, theme } from 'antd';
import type { GlobalToken, InputRef, TableColumnsType } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { USER_ID_MAX_LENGTH, listUsers } from '../../api/auth';
import { claimRetainedOutcome } from '../../api/client';
/*
 * WHY : Assumptions: this import is TYPE-ONLY and stays type-only. `verbatimModuleSyntax` is on, so an
 *       `import type` is erased before the bundler sees it and creates no dependency between the two
 *       chunks -- which matters because every screen is mounted through `lazy()` in
 *       `ui/src/router.tsx`, and a VALUE import from the update screen would fold its chunk into this
 *       one and charge every operator who opens the browse for code they may never reach. The shape is
 *       worth sharing this way rather than restating: two independent declarations of the same two
 *       members could drift, and a drifted handover would be read as a sentence that is not there.
 */
import type { UserUpdateSaveHandover } from '../userUpdate';
import type { ApiError, PageResponse, UserSummary } from '../../api/types';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import type { PageBoundary, PagedQueryRequest } from '../../hooks/usePagedQuery';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { busyAnnouncement, fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
} from '../../messages/messages';
import {
  ADMIN_MENU_ROUTE,
  USER_LIST_ROUTE,
  USER_UPDATE_ROUTE_TEMPLATE,
  navigateSafely,
} from '../../routes/navigation';
/*
 * WHY : Assumptions: only the TEXT-grade colour map and the typography map are read here, and
 *       `SPACING_TOKENS` deliberately is not. Every gap on this screen is expressed through a named
 *       step on `Flex`'s own `gap` prop -- `"large"` and `"middle"` -- which resolves on the antd
 *       spacing scale inside the component, so naming a margin token as well would state the same
 *       decision twice and invite the two to disagree. `BMS_TEXT_COLOR_TOKENS` is taken rather than
 *       `BMS_COLOR_TOKENS` because the two values this screen colours are runs of PROSE the operator
 *       reads: that module measures a text-grade shade per role against the shell's surface, and the
 *       role map alone would have put NEUTRAL prose at a ratio below the 4.5:1 minimum.
 */
import {
  BMS_TEXT_COLOR_TOKENS,
  TARGET_SIZE_AA_MINIMUM,
  TYPOGRAPHY_TOKENS,
  characterCellColumnMeasure,
} from '../../theme/tokens';

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L449-L450 defines it. */
export const USER_LIST_TRANSACTION_ID = 'CU00';

/** Source program name, rendered in the shell's header band exactly as the 3270 screen did. */
export const USER_LIST_PROGRAM_NAME = 'COUSR00C';

/**
 * Mapset this screen stands in for, which fixes the message band's rendered display width.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` in `ui/src/messages/messages.ts` records `COUSR00` as an
 * eight-and-seventy-character band, matching `ERRMSG ... LENGTH=78 POS=(23,1)` in the mapset. The name
 * is passed to the shell rather than the width, so one catalog entry stays the single source of it.
 */
export const USER_LIST_MAPSET = 'COUSR00';

/**
 * Route this screen occupies, which administrative menu options 1, 3 and 4 transfer to.
 *
 * ⚠️ Refactoring Rationale: the value is TAKEN from `ui/src/routes/navigation.ts` where it was a
 * literal here. That module owns the closed set of routes a screen may name as the origin it was
 * entered from, and this browse is now such an origin -- it transfers to the update and deletion
 * screens, whose exit keys read that set -- so a second literal would let this screen hand over a
 * path the set refuses. The alias keeps the name this module publishes, which its two path builders
 * and the route tests read.
 */
export const USER_LIST_PATH: string = USER_LIST_ROUTE;

/**
 * Rows one page of this browse holds.
 *
 * Assumptions: ten, established three independent ways rather than assumed. The mapset paints ten row
 * families `SEL0001` through `SEL0010` at rows 10 to 19; `app/cbl/COUSR00C.cbl` declares
 * `02 USER-REC OCCURS 10 TIMES` under `01 WS-USER-DATA`; and its read loop runs
 * `PERFORM UNTIL WS-IDX >= 11`. `ui/src/api/auth.ts` records the same arity from the service's side
 * and states that the value is never sent as a request member, which is why this constant governs how
 * many row positions the table renders and nothing about the request.
 */
export const USER_LIST_PAGE_SIZE = 10;

/**
 * Characters the action cell of a row accepts, and what each requests.
 *
 * `app/cbl/COUSR00C.cbl` L189-L216 evaluates the marked cell and transfers to `COUSR02C` for `'U'` or
 * `'u'` and to `COUSR03C` for `'D'` or `'d'`, answering anything else with the sentence
 * {@link PROGRAM_MESSAGES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D}.
 *
 * ⚠️ Assumptions: this domain is `'U'` and `'D'`, and it must NOT be shared with the card browse even
 * though the two screens are otherwise the same construct. `app/cbl/COCRDLIC.cbl` L77-L79 declares
 * `88 SELECT-OK VALUES 'S', 'U'` -- `'S'` for detail and `'U'` for update -- so the letter `'U'` means
 * update on both screens while `'S'` does not exist here and `'D'` does not exist there. The two also
 * page at different arities, ten here against seven there. A shared action-code helper would therefore
 * compile, type-check and silently accept a code on the screen that has no such action.
 */
export const USER_LIST_ROW_ACTION_CODES = {
  /** Transfers to the user maintenance screen, replacing `XCTL PROGRAM('COUSR02C')`. */
  update: 'U',
  /** Transfers to the user deletion screen, replacing `XCTL PROGRAM('COUSR03C')`. */
  delete: 'D',
} as const;

/** One character the action cell admits, as {@link USER_LIST_ROW_ACTION_CODES} declares them. */
export type UserListRowActionCode =
  (typeof USER_LIST_ROW_ACTION_CODES)[keyof typeof USER_LIST_ROW_ACTION_CODES];

/**
 * Width of one action cell, from `SEL0001 ... LENGTH=1` in `app/bms/COUSR00.bms`.
 *
 * Assumptions: the cell is one character on the terminal and one character here, so a second letter
 * cannot be typed into it at all rather than being typed and then refused.
 */
export const USER_LIST_ACTION_CELL_LENGTH = 1;

/**
 * Character columns an action cell RESERVES, which is one more than it admits.
 *
 * ⚠️ Purpose: keep the typed character visible. A browser measurement of the delivered screen found this
 * control 24 pixels wide with a `clientWidth` of 22 and the design system's own 11-pixel padding on each
 * side, giving a CONTENT BOX of 0.00 pixels against a measured glyph advance of 9.078 pixels for the
 * `U` an operator is instructed to type: a pixel scan of the whole control returned zero ink while the
 * value was genuinely stored and the caret genuinely at position 1. The operator typed, saw nothing, and
 * had no way to tell whether the keystroke had registered.
 *
 * ⚠️ Assumptions: the extra column is for the CARET and not padding for its own sake. A 3270 cursor was a
 * block that occupied the character cell itself, so one declared column was all the terminal ever needed;
 * a browser draws its caret BETWEEN character positions, so a content box of exactly one column leaves
 * the caret and the glyph competing for the same space and the glyph scrolls out of view -- which is the
 * measured `scrollWidth` 22 to 31 with `scrollLeft` 8 that the same pass recorded.
 *
 * Assumptions: this is a DISPLAY reservation and changes nothing about what the field accepts.
 * {@link USER_LIST_ACTION_CELL_LENGTH} remains the `maxLength`, so a second character still cannot be
 * typed, and the two constants are kept apart precisely so a reader cannot mistake the reservation for a
 * relaxation of the declared width.
 */
export const USER_LIST_ACTION_CELL_RESERVED_COLUMNS = USER_LIST_ACTION_CELL_LENGTH + 1;

/**
 * Pointer affordance for a table row that can be acted on.
 *
 * ⚠️ Purpose: say under the pointer that a row is actionable. A browser pass measured `cursor: auto` on
 * these rows both at rest and hovered, on a browse whose entire purpose is choosing a row -- so nothing
 * about a row indicated it could be acted on, and the only clue was the one-character control in its
 * leading column, which the same pass found invisible.
 *
 * Assumptions: `pointer` is a structural interaction keyword rather than a design value, so it resolves
 * to no design token and needs none -- the same standing `auto`, `none` and `inherit` have. It is
 * declared once so every row takes the same one, and it matches the idiom the authorization browse
 * already uses for its own rows.
 */
const ROW_AFFORDANCE_STYLE: CSSProperties = { cursor: 'pointer' };

/**
 * The positions from which a backward step has nothing to answer with.
 *
 * WHY : ⚠️ Refactoring Rationale: the two paging guards read a NAMED position where they used to read
 *       `browse.hasPrev` and `browse.hasNext`. The members are exactly equivalent -- the browse hook
 *       derives all five positions from them -- so which sentence appears when has not changed. What
 *       changes is that the dead end is ENUMERATED instead of falling out of two false flags: a browse
 *       with no rows and no page on either side satisfied `!hasPrev` and `!hasNext` at once and neither
 *       guard said so, which is the state a reader had to reconstruct.
 *       Assumptions: the two sets are written out rather than derived from one another, because they are
 *       not complements -- `INTERIOR` is in neither and `EMPTY` and `ONLY` are in both -- so an
 *       expression relating them would be longer than the enumeration and harder to check against the
 *       hook's own table. The reference-type browse states the same two sets for the same reason, and
 *       they are restated here rather than imported: every screen is mounted through `lazy()` in
 *       `ui/src/router.tsx`, so a value import from another screen would fold its chunk into this one.
 */
const BACKWARD_EXHAUSTED: readonly PageBoundary[] = Object.freeze(['EMPTY', 'ONLY', 'FIRST']);

/**
 * The positions from which a forward step has nothing to answer with.
 *
 * Assumptions: the mirror of {@link BACKWARD_EXHAUSTED}, with `LAST` in place of `FIRST`.
 */
const FORWARD_EXHAUSTED: readonly PageBoundary[] = Object.freeze(['EMPTY', 'ONLY', 'LAST']);

/**
 * Discards a paging turn's settlement, for the two key handlers that cannot observe it.
 *
 * Purpose: `ui/src/hooks/usePagedQuery.ts` now returns a promise from `prevPage` and `nextPage` so a
 * caller that needs to sequence on a turn can. These two callers do not: both are `void`-returning key
 * handlers, and the page they asked for arrives through the hook's own result on a later render.
 *
 * ⚠️ Refactoring Rationale: the two call sites used to be bare statements, which became floating
 * promises the moment those members stopped returning `void`. `ui/eslint.config.js` configures
 * `no-floating-promises` with `ignoreVoid: false`, so the `void` discard is not available either, and
 * making the handlers `async` is worse than unavailable -- `no-misused-promises` with `checksVoidReturn`
 * rejects a promise-returning function where a `void` one is expected, which is exactly what
 * `PfKeyHandlerEntry.onInvoke` declares. Settling with a named no-op on both arms is the shape
 * `usePagedQuery.ts` itself uses for its own opening read, so this screen states the same thing that
 * module states rather than inventing a second discipline for it.
 *
 * Assumptions: discarding is CORRECT here and not merely permitted. Every outcome of a page turn is
 * already applied through the hook's reducer -- the rows, the cursors, the position and any failure --
 * so there is nothing at these two call sites left to act on. The rejection arm is supplied for the
 * same reason the hook supplies its own: a handler that exists cannot become an unhandled rejection
 * that a later change to the hook would otherwise introduce here silently.
 * @returns {void} Nothing; the turn's outcome has already been recorded by the browse hook.
 */
function ignoreSettledPageTurn(): void {
  // Assumptions: an empty body is the whole implementation and is deliberate rather than unfinished.
  //   Logging here would emit a line for every ordinary page turn an operator makes.
}

/**
 * Gives an action cell a content box wide enough to show the character typed into it.
 *
 * Purpose: turn the proportional column width from a size into a CEILING for this one column. Every
 * other column on this browse carries prose or a code that shrinks gracefully, so a percentage share is
 * the whole answer for them; this one carries a single character inside a bordered control whose own
 * padding does not shrink, so at a phone width the share resolved to less than the padding and the
 * content box collapsed to nothing while the value stayed stored and unreadable.
 *
 * ⚠️ Assumptions: the floor is stated as `min-inline-size` rather than as a wider column share, and the
 * difference matters. A wider share would take its width from the two twenty-character NAME columns at
 * every viewport, undoing the relative emphasis the mapset gives them; a minimum takes width only when
 * the share falls below what the control needs, which at the measured 1440-pixel viewport is never.
 *
 * ⚠️ Assumptions: the character term is in `ch` and the padding term is read from the design system, for
 * the reasons `copybookFieldWidthStyle` in `ui/src/layout/recordLayout.ts` records for the same pair:
 * `ch` is the browser's nearest equivalent of a character column, and controls are border-box so the
 * padding has to be added rather than absorbed. This function is deliberately NOT that helper: that one
 * publishes a maximum from a declared width and this one publishes a minimum from a reserved width, and
 * spreading both onto one control would set a maximum of one column and a minimum of two on one box.
 *
 * ⚠️ Assumptions: {@link TARGET_SIZE_AA_MINIMUM} is stated as an explicit alternative inside `max()`
 * instead of being left to fall out of the arithmetic. The two terms happen to clear it at the pinned
 * theme, but that is a property of a token value rather than of this expression, and an accessibility
 * floor that holds only while a token keeps its current value is not a floor. `max()` also degrades the
 * way the rest of this tree does: if the padding custom property fails to resolve -- it is scoped to
 * component class scopes, not to the document root -- the `calc()` term is invalid at computed-value
 * time and the AA figure remains as the operative minimum, so the control can never return to a
 * zero-width content box.
 *
 * Trade-offs: a pixel figure is spelled here, which the zero-hardcoded-values rule otherwise forbids. It
 * is admitted because it is not a design value: it is a WCAG success-criterion threshold, held in
 * `ui/src/theme/tokens.ts` with its criterion attached, and no token on any of the system's scales
 * expresses a conformance floor.
 * @param {GlobalToken} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`.
 * @returns {CSSProperties} The minimum measure to spread onto the action cell's control.
 */
export function actionCellWidthStyle(cssVar: GlobalToken): CSSProperties {
  return {
    minInlineSize: `max(${String(TARGET_SIZE_AA_MINIMUM)}px, calc(${String(
      USER_LIST_ACTION_CELL_RESERVED_COLUMNS,
    )}ch + 2 * ${String(cssVar.controlPaddingHorizontal)}))`,
  };
}

/**
 * Screen sub-title, verbatim from the mapset's own row-4 field.
 *
 * Assumptions: mapset `INITIAL=` text lives in the screen module and not in the message catalog, which
 * is the boundary the catalog draws for itself -- it carries text a COBOL PROGRAM holds and records
 * that a screen's own painted labels belong with the screen that renders them. Source:
 * `app/bms/COUSR00.bms` `ATTRB=(ASKIP,BRT) COLOR=NEUTRAL LENGTH=10 POS=(4,35)`.
 */
export const USER_LIST_TITLE = 'List Users';

/**
 * Every static label this screen paints, verbatim from `app/bms/COUSR00.bms` with its padding intact.
 *
 * ⚠️ Assumptions: the padding IS part of each value and none of it may be discarded. `userIdColumn`
 * carries one TRAILING space and the two name headings are padded on both sides, because the mapset
 * sizes each heading to the twenty-character column beneath it -- `LENGTH=20` on both. The values are
 * therefore stored padded and only the rendering trims, so a reader comparing this file with the
 * mapset finds the same bytes.
 *
 * Assumptions: these are declared here rather than imported because the catalog's harvest does not
 * hold them. It holds the seven sentences `COUSR00C` MOVEs into `WS-MESSAGE`, all of which this screen
 * imports; a `DFHMDF INITIAL=` operand is painted by the map rather than moved by the program, so it
 * falls outside that harvest by the catalog's own stated rule.
 */
export const USER_LIST_LABELS = {
  /** `app/bms/COUSR00.bms` `COLOR=TURQUOISE LENGTH=5 POS=(4,65)` -- prompt for the page indicator. */
  pageIndicator: 'Page:',
  /** `app/bms/COUSR00.bms` `COLOR=TURQUOISE LENGTH=15 POS=(6,5)` -- label of the search field. */
  searchUserId: 'Search User ID:',
  /** `app/bms/COUSR00.bms` `COLOR=NEUTRAL LENGTH=3 POS=(8,5)` -- heading of the action column. */
  selColumn: 'Sel',
  /** `app/bms/COUSR00.bms` `COLOR=NEUTRAL LENGTH=8 POS=(8,12)` -- one trailing space, deliberately. */
  userIdColumn: 'User ID ',
  /** `app/bms/COUSR00.bms` `COLOR=NEUTRAL LENGTH=20 POS=(8,24)` -- padded to its column width. */
  firstNameColumn: '     First Name     ',
  /** `app/bms/COUSR00.bms` `COLOR=NEUTRAL LENGTH=20 POS=(8,48)` -- padded to its column width. */
  lastNameColumn: '     Last Name      ',
  /** `app/bms/COUSR00.bms` `COLOR=NEUTRAL LENGTH=4 POS=(8,72)` -- heading of the user-type column. */
  typeColumn: 'Type',
  /**
   * `app/bms/COUSR00.bms` `ATTRB=(ASKIP,BRT) COLOR=NEUTRAL LENGTH=56 POS=(21,12)`.
   *
   * Assumptions: the mapset source reads `Type ''U'' to Update or ''D'' to Delete a User from the
   * list`, and the doubled apostrophes are BMS literal escaping rather than content -- a terminal
   * displays one apostrophe. Reproducing the doubled form would be a transcription defect that this
   * tree's byte-exactness rule would then protect, which is the same hazard
   * `decodeBmsLegendText` exists for on the ampersand.
   */
  rowActionPrompt: "Type 'U' to Update or 'D' to Delete a User from the list",
} as const;

/**
 * Function-key legend labels, split from this mapset's own row-24 literal.
 *
 * Assumptions: `app/bms/COUSR00.bms` `COLOR=YELLOW LENGTH=48 POS=(24,1)` paints
 * `ENTER=Continue  F3=Back  F7=Backward  F8=Forward` -- two spaces between each pair -- and names
 * exactly the four keys `app/cbl/COUSR00C.cbl` L121-L133 dispatches. There is no PF4, PF5 or PF12 on
 * this screen, so no binding is registered for one.
 *
 * Refactoring Rationale: the backward and forward labels are taken from {@link UNIFORM_PF_KEY_LABELS}
 * rather than restated. `F7=Backward` and `F8=Forward` in this mapset are byte-identical to the shared
 * pair, so a second transcription would only create a way for the two to disagree. `ENTER=Continue`
 * and `F3=Back` are local because neither key's wording is uniform across the seventeen measured
 * legends -- that module records ENTER painted six different ways and PF3 painted three.
 */
export const USER_LIST_KEY_LABELS = {
  /** Applies the search field and any marked action cell, then re-reads the opening page. */
  ENTER: 'ENTER=Continue',
  /** Returns to the administrative menu, replacing `XCTL PROGRAM('COADM01C')`. */
  PFK03: 'F3=Back',
  /** Pages backward, from the shared pair. */
  PFK07: UNIFORM_PF_KEY_LABELS.PFK07,
  /** Pages forward, from the shared pair. */
  PFK08: UNIFORM_PF_KEY_LABELS.PFK08,
} as const;

/**
 * Identifier of the search control, used to bind its label and any refusal text to it.
 *
 * Assumptions: a module constant rather than a per-instance value is safe here because the router
 * mounts this screen at one path and a terminal showed one screen, so two instances cannot coexist in
 * one document.
 */
const SEARCH_USER_ID_INPUT_ID = 'user-list-search-user-id';

/**
 * Identifier of the search control's visible label, which the control resolves as its name.
 *
 * Assumptions: the label is bound by `aria-labelledby` rather than by a `<label for>` pairing, because
 * the visible label is a `Typography.Text` -- the design system's component for a protected label --
 * and this tree renders no raw `label` element. The reference identified the field by POSITION on a
 * fixed grid, which design gap G1 gives up, so an explicit name is what replaces that position.
 */
const SEARCH_USER_ID_LABEL_ID = 'user-list-search-user-id-label';

/**
 * Builds the identifier of one row's action cell.
 * @param {string} userId - The row's user identifier, which is unique across the page.
 * @returns {string} A control identifier unique to that row's cell.
 */
function actionCellId(userId: string): string {
  return `user-list-action-${userId}`;
}

/**
 * Builds the path of the user maintenance screen for one operator.
 *
 * Assumptions: the identifier travels in the PATH rather than in a carried session field, which is
 * what makes the request self-describing and therefore independently authorizable. AAP section 0.7.1
 * replaces `CDEMO-CU00-USR-SELECTED` with exactly this, and `ui/src/router.tsx` mounts the receiving
 * screen at `/users/:id/edit`, whose selector segment this builder always supplies.
 * @param {string} userId - Identifier of the selected operator, from that row rather than from state.
 * @returns {string} The concrete route the update screen is entered at.
 */
export function userEditPath(userId: string): string {
  return `${USER_LIST_PATH}/${encodeURIComponent(userId)}/edit`;
}

/**
 * Builds the path of the user deletion screen for one operator.
 *
 * Assumptions: `COUSR03C` is a separate deliverable of AAP section 0.5.1.10, mounted at
 * `/users/:id/delete`. This screen's own contract is to transfer there, exactly as
 * `app/cbl/COUSR00C.cbl` L200-L211 transfers to that program unconditionally; it has no
 * target-unavailable arm of its own, unlike `app/cbl/COADM01C.cbl` L146-L156, so inventing one here
 * would add behaviour the reference does not have.
 * @param {string} userId - Identifier of the selected operator, from that row rather than from state.
 * @returns {string} The concrete route the deletion screen is entered at.
 */
export function userDeletePath(userId: string): string {
  return `${USER_LIST_PATH}/${encodeURIComponent(userId)}/delete`;
}

/** One message the row-23 band paints, with the severity the reference painted it in. */
interface ScreenBand {
  /** The sentence to paint, or `null` when the screen has nothing to say. */
  readonly text: string | null;
  /** Severity governing the band's variant, colour and ARIA role. */
  readonly severity: MessageBandSeverity;
}

/** Nothing to paint; the state every screen turn starts from. */
const EMPTY_BAND: ScreenBand = { text: null, severity: 'error' };

/**
 * Name under which the update screen leaves the sentence a `F3=Save&&Exit` published.
 *
 * ⚠️ Assumptions: this string is COMPOSED from the shared route template rather than written out, and
 * the update screen composes the same name the same way. That screen retains the outcome and this one
 * collects it, so the two are in different modules and the name is the only thing they agree on; taking
 * the route half from `ui/src/routes/navigation.ts` is what stops that half from drifting. The suffix is
 * inert -- a mismatch leaves an outcome uncollected rather than mis-routed -- and the paired cases in
 * `ui/src/test/userList.test.tsx` and `ui/src/test/userUpdate.test.tsx` fail on exactly that.
 */
const USER_UPDATE_SAVE_CLAIM = `${USER_UPDATE_ROUTE_TEMPLATE}#saved`;

/**
 * What the page's action cells, taken together, request.
 *
 * Assumptions: at most ONE row is ever selected, because the reference's selection scan is an ordered
 * `EVALUATE` whose first matching arm ends it.
 */
export interface UserRowSelection {
  /** Identifier of the row whose cell was marked, or `null` when no cell carries an entry. */
  readonly userId: string | null;
  /** The action that cell requests, or `null` when the entry is blank or not one of the two codes. */
  readonly code: UserListRowActionCode | null;
  /** Verbatim refusal for an entry that is neither code, or `null` when there is nothing to refuse. */
  readonly message: string | null;
}

/** No cell carries an entry, so the turn requests no row action. */
const NO_ROW_SELECTION: UserRowSelection = { userId: null, code: null, message: null };

/**
 * Resolves one action-cell entry to the code it names.
 *
 * Assumptions: the comparison is case-INSENSITIVE, because the reference's own arms are. L190-L191
 * of `app/cbl/COUSR00C.cbl` reads `WHEN 'U' WHEN 'u'` and L200-L201 reads `WHEN 'D' WHEN 'd'`, so a
 * lower-case entry is accepted there and is accepted here. Upper-casing the entry before comparing
 * expresses the same two pairs of arms without repeating each letter twice.
 * @param {string} entry - The cell entry as the operator typed it, already trimmed of blanks.
 * @returns {UserListRowActionCode | null} The code the entry names, or `null` when it names neither.
 */
export function toUserListRowActionCode(entry: string): UserListRowActionCode | null {
  const upper = entry.toUpperCase();

  if (upper === USER_LIST_ROW_ACTION_CODES.update) {
    return USER_LIST_ROW_ACTION_CODES.update;
  }

  if (upper === USER_LIST_ROW_ACTION_CODES.delete) {
    return USER_LIST_ROW_ACTION_CODES.delete;
  }

  return null;
}

/**
 * Reduces the action cells of one page to the single row action they express.
 *
 * ⚠️ Assumptions: the FIRST marked row in display order wins and every later entry is IGNORED, without
 * being reported. `PROCESS-ENTER-KEY` at `app/cbl/COUSR00C.cbl` L149-L184 is one `EVALUATE TRUE` whose
 * ten arms test `SEL0001I` through `SEL0010I` in that order; COBOL ends an `EVALUATE` at its first
 * matching arm, so a page carrying entries beside rows 2 and 5 acts on row 2 and never inspects row 5.
 *
 * ⚠️ Assumptions: this is deliberately NOT the multi-selection refusal the transaction-type browse
 * uses. `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` counts its marked rows and answers
 * `'Please select only 1 action'` for more than one; `COUSR00C` keeps no such count and declares no
 * such sentence, so refusing a second entry here would invent a message the reference cannot emit and
 * would add a refusal where it silently proceeds.
 *
 * Assumptions: a blank cell is not a selection. The arms test `NOT = SPACES AND LOW-VALUES`, so an
 * untouched cell is skipped rather than refused, and a page with no entry at all reaches the
 * `WHEN OTHER` arm that clears both carried fields.
 *
 * Assumptions: the refusal is raised only once a cell HAS been marked. L186-L188 guards the code
 * evaluation on both carried fields being non-blank, so an empty page never produces the sentence.
 * @param {readonly UserSummary[]} rows - The page's rows, in the order they are displayed.
 * @param {Readonly<Record<string, string>>} codes - Cell entries, keyed by the row's user identifier.
 * @returns {UserRowSelection} The selected identifier and code, or the verbatim refusal when the
 *   winning entry is neither code. No exception is raised: an unusable entry is reported through the
 *   `message` member so the caller can paint it, which is what the reference does with `WS-MESSAGE`.
 */
export function reduceUserRowSelection(
  rows: readonly UserSummary[],
  codes: Readonly<Record<string, string>>,
): UserRowSelection {
  for (const row of rows) {
    const entry = (codes[row.userId] ?? '').trim();

    if (entry === '') {
      continue;
    }

    const code = toUserListRowActionCode(entry);

    // WHY : Assumptions: the loop returns on the first marked cell in BOTH outcomes, valid or not,
    //       rather than continuing to look for a usable one. That is what makes this the reference's
    //       `EVALUATE`: its arm matched on the entry being non-blank, so the code test that follows
    //       has only ever one entry to judge and a later usable entry is never reached.
    return code === null
      ? {
          userId: row.userId,
          code: null,
          message: PROGRAM_MESSAGES.COUSR00C.INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D,
        }
      : { userId: row.userId, code, message: null };
  }

  return NO_ROW_SELECTION;
}

/**
 * Chooses the sentence a failed listing is reported with.
 *
 * ⚠️ Assumptions: `MessageBand` accepts a string and a severity and deliberately refuses an
 * `ApiError`, so mapping the document onto a sentence is this screen's work rather than the band's.
 * The reference makes the same choice: `1000-STARTBR-USER-SEC-FILE`, `READNEXT` and `READPREV` each
 * answer any response other than the two they name with the single sentence
 * {@link SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER}, at L610, L644 and L678 -- one sentence for every
 * unexpected condition rather than a per-status vocabulary the reference does not have.
 *
 * Assumptions: `isFailed` is consulted as well as `error`, because two failure modes carry no document
 * at all -- a reader that rejected with something other than the normalised client failure, and the
 * browse hook's own refusal of a page longer than the screen declared room for.
 * `ui/src/hooks/usePagedQuery.ts` states that distinction, and a browse that kept displaying stale
 * rows in either case would be the silent broken view the flag exists to prevent.
 *
 * ⚠️ Alternatives Considered: the message catalogue now publishes two AUTHORED failure sentences --
 * `TRANSIENT_FAILURE_TRY_AGAIN` for a condition that may clear and `PERSISTENT_FAILURE_REPORT_IT` for
 * one that will not -- selected on the classification `ui/src/api/client.ts` already computes. Neither
 * is taken here, and the reason is Rule T8 rather than inertia: this screen's failure sentence HAS a
 * mainframe source, so substituting an authored one would replace a verbatim operator-facing string
 * with a better-worded invention. The authored pair is for screens and states the reference never had a
 * sentence for -- which is why the busy announcement above does take one.
 * @param {boolean} isFailed - Whether the most recent read ended in refusal or failure.
 * @param {ApiError | null} error - The problem document the service returned, when there was one.
 * @returns {ScreenBand} The verbatim listing-failure sentence at error severity, or the empty band
 *   when the read succeeded.
 */
function listingFailureBand(isFailed: boolean, error: ApiError | null): ScreenBand {
  return isFailed || error !== null
    ? { text: SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER, severity: 'error' }
    : EMPTY_BAND;
}

/**
 * Character cells each browse column occupies across row 8 of the mapset, and the row's total span.
 *
 * Purpose: gives {@link buildUserListColumns} a measured width per column so the table's own content
 * sizing cannot decide them.
 *
 * Assumptions: a column's extent is measured from its own heading position to the NEXT heading's
 * position, and the last column takes its heading's declared length. `app/bms/COUSR00.bms` heads row 8
 * at `POS=(8,5)`, `(8,12)`, `(8,24)`, `(8,48)` and `(8,72)` with `'Type'` at `LENGTH=4`, so the extents
 * are 12-5, 24-12, 48-24, 72-48 and 4 -- and the row spans columns 5 through 75, which is their sum.
 * Measuring extent rather than heading length is what makes the five shares sum to the row: the
 * headings alone total 55 cells and would leave the inter-column gutters unattributed, shrinking the
 * two name columns against the terminal's own proportions.
 *
 * ⚠️ Refactoring Rationale: these widths were ADDED after a browser measurement, not designed in. The
 * table was first left to size its own columns from content, and the one-character action column then
 * rendered about 700px wide -- wider than either twenty-character name column -- because an
 * unconstrained `Input` reports a full-width intrinsic size however few characters it accepts. That is
 * the exact defect {@link characterCellColumnMeasure} was written for, so this reuses that helper
 * rather than introducing a second treatment of it. The lengths it returns are design gap G1 artefacts
 * derived from a countable mapset measurement, which is why a rendered length may be spelled there and
 * nowhere else.
 *
 * ⚠️ Refactoring Rationale: these counts were first applied through the PROPORTIONAL sibling helper,
 * `characterCellWidthShare`, which divides a column's cells by the row's and applies the result to a
 * total that includes the grid's padding. A browser pass measured what that costs the narrowest
 * column: `userType` spans 4 of 71 cells, so it drew 5.63% of a 718.63-pixel table -- 40.48 pixels --
 * and the design system's 16 pixels of padding on each edge left roughly 8 pixels of content box. Its
 * own four-character heading therefore laid out ONE CHARACTER PER LINE, standing the header row 120
 * pixels tall, at every width from 375 to 1920. The counts were right and the way they were applied
 * was wrong: a column has two paddings whatever it spans, so the padding must be reserved per column
 * rather than shared out in proportion to characters. The absolute helper does that and its five
 * results still sum to exactly the measure {@link userListTableMeasure} declares.
 */
const USER_LIST_COLUMN_CELLS = Object.freeze({
  /** `'Sel'` heads column 5 and the identifier heads 12, so the action column spans seven cells. */
  sel: 7,
  /** `'User ID '` heads column 12 and the first name heads 24. */
  userId: 12,
  /** `'     First Name     '` heads column 24 and the last name heads 48. */
  firstName: 24,
  /** `'     Last Name      '` heads column 48 and the type heads 72. */
  lastName: 24,
  /** `'Type'` is the last heading, so it takes its own `LENGTH=4`. */
  userType: 4,
  /** Columns 5 through 75 -- the sum of the five extents above. */
  rowSpan: 71,
});

/** Columns the grid lays out, which fixes how many times the design system adds its cell padding. */
const USER_LIST_COLUMN_COUNT = 5;

/**
 * The one theme member {@link userListTableMeasure} reads, in the form the theme actually supplies it.
 *
 * ⚠️ Assumptions: `padding` is `number | string` and not `GlobalToken['padding']`, which is
 * `number` alone. That narrower declaration is true of the RESOLVED token; what the screen passes is
 * the CSS-VARIABLE map from `theme.useToken()`, whose members hold `var(--ant-padding)` references at
 * runtime while keeping the resolved token's compile-time type. `String()` at the use site is what
 * bridges the two, and the union is what lets a case state either form -- a literal length for the
 * arithmetic, and the reference the application resolves for the rendered assertion.
 *
 * Alternatives Considered: (1) `cssVar: GlobalToken`, the form the sibling transaction-type browse's
 * own `tableMinimumMeasure` takes. Rejected here because a case that states the padding then has to
 * write `{ padding: '8px' } as GlobalToken`, a conversion between types 493 members apart that the
 * compiler refuses outright; keeping it would need a second cast through `unknown`, which suppresses
 * the check rather than satisfying it. (2) `Pick<GlobalToken, 'padding'>`, which is narrow but not
 * true -- it pins `padding` to `number` and so refuses the CSS length the variable map actually holds.
 */
export interface TableMeasureTokens {
  /** The design system's cell padding, resolved or as the CSS-variable reference the map carries. */
  readonly padding: number | string;
}

/**
 * Least measure this grid may be laid out at, in the mapset's own character cells.
 *
 * Purpose: give {@link USER_LIST_COLUMN_CELLS}' proportional shares a floor to resolve against, so a
 * narrow viewport scrolls the grid INSIDE its own region instead of pushing the whole page sideways.
 *
 * ⚠️ Refactoring Rationale: the grid declared no horizontal extent at all, and a browser pass at a
 * 375-pixel viewport measured the consequence as page-level pan rather than as a squeezed column:
 * `document.documentElement.scrollWidth` was **398** against a `clientWidth` of **375**, and
 * `window.scrollTo(50, 0)` genuinely moved the document to `window.scrollX` **23**. The `Type` heading
 * was clipped to `Typ` and its values sat half outside the viewport. The same pass established that
 * this was NOT the grid's own scroller overflowing: the table measured 374.25 pixels inside a
 * 327-pixel `.ant-table-content` whose computed `overflow-x` was `visible`, and every ancestor up to
 * `BODY` reported the same 398 against 375 -- so with nothing declaring a scrolling region, the
 * overflow was paid for by the page.
 *
 * ⚠️ Assumptions: declaring the extent is what CREATES the region, and the mechanism is the design
 * system's own rather than a style added here. In the pinned package
 * `ui/node_modules/@rc-component/table/lib/Table.js` L259-L272 turns a declared horizontal extent into
 * `overflow-x: auto` on the `-content` element and `width: <extent>; min-width: 100%` on the inner
 * table, and L556-L575 is where both are applied. `min-width: 100%` is why nothing changes above the
 * floor: at any viewport wide enough the grid still fills its container exactly as it does today.
 *
 * ⚠️ Assumptions: the floor is the mapset's own row geometry and not a chosen breakpoint. The five
 * columns span 71 character cells of the 80-column display -- {@link USER_LIST_COLUMN_CELLS} counts
 * them off `app/bms/COUSR00.bms` row 8 -- and the design system adds its cell padding on both sides of
 * each of the {@link USER_LIST_COLUMN_COUNT} columns, which `antd/lib/table/style/index.js` derives
 * from the `padding` token. `ch` is the browser's nearest equivalent of a character column, the same
 * equivalence `copybookFieldWidthStyle` in `ui/src/layout/recordLayout.ts` records.
 *
 * ⚠️ Assumptions: this is the same treatment the transaction-type browse already carries -- its own
 * `tableMinimumMeasure` states the identical derivation over its three columns -- and the two are
 * stated separately rather than shared for the reason {@link BACKWARD_EXHAUSTED} records: every screen
 * is mounted through `lazy()` in `ui/src/router.tsx`, so a value import from another screen would fold
 * that screen's chunk into this one. The measurement each states is its own mapset's, so there is no
 * single value to share anyway -- 71 cells over five columns here against 69 over three there.
 *
 * Alternatives Considered: (1) letting the page keep the overflow and relying on the operator panning.
 * Rejected on the measurement itself -- a horizontally panned page moves the shell's title band, its
 * row-23 message and its row-24 legend out from under the operator, so the cost of reading one column
 * is losing three persistent zones. (2) Dropping the `Type` column at narrow widths. Rejected because
 * `SEC-USR-TYPE` is one of the four members `UserSummary` declares and the mapset heads it at
 * `POS=(8,72)` on every turn; a column that disappears is a column an operator cannot learn. (3)
 * Truncating the headings with an ellipsis, which a fixed layout does support. Rejected because the
 * clipped `Typ` the pass measured is exactly that outcome, and it reads as a complete heading that
 * happens to be short.
 *
 * Trade-offs: below the floor the grid scrolls sideways, which the terminal never did because it was
 * exactly 80 columns wide. Design gap G1 already records that a fixed character grid can only scale or
 * clip; scrolling ONE region is the least lossy of those, and it is what the finding asks for -- it
 * names page-level pan as the defect and an internal scroller as the accepted pattern.
 * ⚠️ Assumptions: the parameter is declared as {@link TableMeasureTokens} -- the one token member
 * the measure reads -- rather than as the whole `GlobalToken` the screen happens to hold. The screen
 * passes the full token, which satisfies it, and the narrowing is what lets a case state the padding
 * directly instead of casting a one-member stub across a 493-member type.
 * @param {TableMeasureTokens} cssVar - The theme's CSS-variable reference map, from
 *   `theme.useToken()`; only its `padding` member is read.
 * @returns {string} The measure, as a CSS length expression the design system applies to the grid.
 */
export function userListTableMeasure(cssVar: TableMeasureTokens): string {
  return `calc(${String(USER_LIST_COLUMN_CELLS.rowSpan)}ch + ${String(
    USER_LIST_COLUMN_COUNT * 2,
  )} * ${String(cssVar.padding)})`;
}

/** What {@link buildUserListColumns} needs from the screen to describe one page's columns. */
export interface UserListColumnOptions {
  /**
   * Renders one row's action cell, which the screen owns because the cell is a live control.
   * @param {UserSummary} row - The row the cell acts on.
   * @returns {ReactElement} The one-character entry control for that row.
   */
  readonly renderActionCell: (row: UserSummary) => ReactElement;
  /**
   * Style applying the fixed-pitch token to the columns whose alignment the terminal guaranteed.
   *
   * Assumptions: it is threaded IN rather than read here so this builder stays a pure function of its
   * arguments and can be asserted without a design-system provider around it.
   */
  readonly fixedPitchStyle: CSSProperties;
  /**
   * The design system's per-edge cell padding, which every column reserves on top of its characters.
   *
   * ⚠️ Assumptions: it is the SAME member {@link userListTableMeasure} reads, and that is what keeps
   * the two in agreement. That function declares the grid's least measure as `71ch + 10 * padding`
   * and each column here claims `cells * ch + 2 * padding`; summing the five columns reproduces the
   * declared total exactly, so a change to either has to be a change to both to stay consistent.
   * Threading it in rather than reading the theme keeps this builder assertable with a literal
   * length and no provider around it, which is the same reason the fixed-pitch style is threaded.
   */
  readonly cellPadding: TableMeasureTokens['padding'];
}

/**
 * Describes the five columns of the user browse, headed and ordered as the mapset paints them.
 *
 * Assumptions: FIVE columns and this order -- the action cell, then the identifier, the first name, the
 * last name and the type. `app/bms/COUSR00.bms` paints its headings at columns 5, 12, 24, 48 and 72 of
 * row 8, and each row family repeats that order at `POS=(1n,6)`, `(1n,12)`, `(1n,24)`, `(1n,48)` and
 * `(1n,73)`. The order is part of what a returning operator reads, so it is reproduced rather than
 * rearranged.
 *
 * Assumptions: the four data columns bind the four members `UserSummary` declares and no others. There
 * is no credential column for the reason the module overview records, and no `FILLER` column because
 * `SEC-USR-FILLER PIC X(23)` is record padding.
 *
 * ⚠️ Trade-offs: this is where design gap G1 lands. The mapset positions all 89 of its fields at
 * absolute character coordinates on a fixed 24-by-80 grid, and a table's row-and-column grouping
 * replaces every one of them. Reproducing character positioning in a browser was rejected on two
 * counts rather than one: it is hostile to assistive technology, which reads structure rather than
 * position, and it cannot reflow at any viewport. What is preserved is what carried the meaning --
 * field grouping, reading order and tab order; what is given up is pixel-for-character placement. The
 * deviation is the AAP's own, implemented centrally in `ui/src/layout/AppShell.tsx`; this is its local
 * consequence.
 *
 * ⚠️ Assumptions: the headings are TRIMMED for display even though {@link USER_LIST_LABELS} stores them
 * padded. The padding sizes each heading to a fixed character column that no longer exists, so
 * rendering it would show leading and trailing whitespace inside a table header; the padded value is
 * kept in the constant so the transcription still matches the mapset byte for byte.
 * @param {UserListColumnOptions} options - The row-action renderer, the fixed-pitch style and the
 *   design system's cell padding.
 * @param {(row: UserSummary) => ReactElement} options.renderActionCell - Renders one row's action cell.
 * @param {CSSProperties} options.fixedPitchStyle - Applies the fixed-pitch token to aligned columns.
 * @param {number | string} options.cellPadding - Per-edge cell padding each column reserves on top of
 *   its characters, so the five widths sum to the measure {@link userListTableMeasure} declares.
 * @returns {TableColumnsType<UserSummary>} The five column descriptors, in the mapset's own order.
 */
export function buildUserListColumns({
  renderActionCell,
  fixedPitchStyle,
  cellPadding,
}: UserListColumnOptions): TableColumnsType<UserSummary> {
  return [
    {
      title: USER_LIST_LABELS.selColumn.trim(),
      key: 'sel',
      width: characterCellColumnMeasure(USER_LIST_COLUMN_CELLS.sel, cellPadding),
      render: renderActionCell,
    },
    // WHY : Assumptions: the identifier and the type render fixed-pitch and the two names do not.
    //       `app/cpy/CSUSR01Y.cpy` declares `SEC-USR-ID PIC X(08)` and `SEC-USR-TYPE PIC X(01)`, both
    //       fixed-width codes an operator scans down a column, while the names are free prose at
    //       `PIC X(20)` whose left edge already aligns. Applying a monospaced face to prose would
    //       widen it for no alignment gain.
    {
      title: USER_LIST_LABELS.userIdColumn.trim(),
      dataIndex: 'userId',
      width: characterCellColumnMeasure(USER_LIST_COLUMN_CELLS.userId, cellPadding),
      /**
       * Renders the identifier in the fixed-pitch face so the column aligns as the terminal's did.
       * @param {string} userId - The row's eight-character identifier.
       * @returns {ReactElement} The identifier as fixed-pitch text.
       */
      render: (userId: string): ReactElement => (
        <Typography.Text style={fixedPitchStyle}>{userId}</Typography.Text>
      ),
    },
    {
      title: USER_LIST_LABELS.firstNameColumn.trim(),
      dataIndex: 'firstName',
      width: characterCellColumnMeasure(USER_LIST_COLUMN_CELLS.firstName, cellPadding),
    },
    {
      title: USER_LIST_LABELS.lastNameColumn.trim(),
      dataIndex: 'lastName',
      width: characterCellColumnMeasure(USER_LIST_COLUMN_CELLS.lastName, cellPadding),
    },
    // WHY : ⚠️ Assumptions: the type is DISPLAY ONLY and no decision may be taken from it. It renders
    //       the `'A'`/`'U'` domain of `SEC-USR-TYPE` (`app/cpy/CSUSR01Y.cpy` L22) as the stored
    //       character, and authority in the target derives solely from the signed `cognito:groups`
    //       claim -- which is why `ui/src/hooks/useAuth.ts` exposes no setter for a group or a user
    //       type. The baseline's own weakness was exactly the opposite arrangement: `CDEMO-USER-TYPE`
    //       travelled in a COMMAREA the client echoed back, so a client could assert its own type.
    //       Branching an authorization decision on a value that came out of this table would restore
    //       the defect AAP section 0.7.1 removes.
    {
      title: USER_LIST_LABELS.typeColumn.trim(),
      dataIndex: 'userType',
      width: characterCellColumnMeasure(USER_LIST_COLUMN_CELLS.userType, cellPadding),
      /**
       * Renders the stored type character in the fixed-pitch face.
       * @param {string} userType - The row's one-character type, `'A'` or `'U'`.
       * @returns {ReactElement} The type character as fixed-pitch text.
       */
      render: (userType: string): ReactElement => (
        <Typography.Text style={fixedPitchStyle}>{userType}</Typography.Text>
      ),
    },
  ];
}

/**
 * Reads one page of users: positioned at the applied key on the opening read, by cursor thereafter.
 *
 * ⚠️ Assumptions: the two positions are sent on DIFFERENT reads and never together. A `null` cursor is
 * the opening read and carries the applied key, if there is one; every later read carries the cursor
 * the previous page returned and no key, because the service refuses the pair and because the key has
 * already done its work -- the page it opened is what the cursor now continues from.
 *
 * Assumptions: no page, offset, size or limit is expressible on either read. `UserListQuery` declares
 * one position, one cursor and one direction; the service fixes the arity at ten and settles
 * further-page availability from a read of one row beyond the page.
 *
 * Assumptions: a blank applied key is sent as an ABSENT member rather than as an empty string, even
 * though the contract admits the empty form and gives it the same meaning. The reference reaches the
 * same read by not filling its search field at all, so an absent member is the closer transcription,
 * and it keeps the opening request byte-identical to the one a screen with no search field would send.
 * @param {PagedQueryRequest} request - The position to read from and the direction to read in.
 * @param {string} appliedKey - The identifier the operator applied with Enter, already trimmed, or the
 *   empty string when the browse opens at the start of the set.
 * @returns {Promise<PageResponse<UserSummary>>} One bounded page of user rows with its two cursors.
 * @throws {Error} The normalised `ApiRequestError` the shared client raises -- 400 for a key outside
 *   the identifier domain, 401 for an absent or expired token and 403 for an authenticated caller
 *   outside the administrative group. The browse hook catches it and publishes it as its `error`,
 *   which this screen reports as {@link SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER}.
 */
function fetchUserPage(
  request: PagedQueryRequest,
  appliedKey: string,
): Promise<PageResponse<UserSummary>> {
  if (request.cursor !== null) {
    return listUsers({ cursor: request.cursor, direction: request.direction });
  }

  return appliedKey === '' ? listUsers() : listUsers({ startUserId: appliedKey });
}

/**
 * The user browse screen: one keyset-paged page of the security file, with the reference's own
 * search field, per-row action cell and four-key workflow.
 *
 * ⚠️ Refactoring Rationale: this screen does NOT check the administrative group. `ui/src/router.tsx`
 * mounts it inside the `AdminSubtree` layout route, which wraps every administrative path in
 * `RequireAdmin` and owns the verbatim access-denied text. A second check here would be a duplicated
 * authorization decision, and two copies of one decision are how the two come to disagree -- the copy
 * that is not exercised by the guard's own tests being the one that drifts. `useAuth` is therefore not
 * imported at all, which is the strongest form the decision can take.
 *
 * Assumptions: there is no first-entry-versus-re-entry distinction, and nothing replaces one. The
 * reference splits on `IF EIBCALEN = 0` and on `CDEMO-PGM-CONTEXT`, whose
 * `88 CDEMO-PGM-REENTER VALUE 1` gates the field highlight at `app/cpy/CSSETATY.cpy` L18-L27; AAP
 * section 0.7.1 removes that discriminator entirely, so a mounted component simply reads its first
 * page and error presentation is driven by the response rather than by a remembered turn count.
 * @returns {ReactElement} The screen's content region: sub-title, search field, page indicator, the
 *   ten-row table and the row-action prompt. The header, message and key legend are delegated to
 *   `AppShell` and are not rendered here.
 */
export default function UserListScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: design values are read as CSS VARIABLE references rather than resolved numbers,
   *       so every one of them still resolves through the single `ConfigProvider` in `ui/src/App.tsx`
   *       and this screen holds no literal colour, spacing or font value of its own.
   */
  const { cssVar } = theme.useToken();
  const now = useServerInstant();

  const [searchEntry, setSearchEntry] = useState('');
  const [appliedBrowseKey, setAppliedBrowseKey] = useState('');
  const [actionCodes, setActionCodes] = useState<Readonly<Record<string, string>>>({});
  const [band, setBand] = useState<ScreenBand>(EMPTY_BAND);

  /*
   * WHY : Assumptions: the search control's DOM node is captured so an invalid key can return the
   *       cursor to it. `app/cbl/COUSR00C.cbl` L126 moves `-1` into `USRIDINL` on its `WHEN OTHER`
   *       arm, which is the 3270 way of placing the cursor, and `usePfKeys` publishes
   *       `restoreFocusRef` for exactly that arm. This is NOT `autoFocus`: it moves the cursor in
   *       response to an action, where `autoFocus` would claim focus on mount.
   */
  const searchFocusRef = useRef<HTMLInputElement | null>(null);

  /*
   * WHY : ⚠️ Purpose: hold each row's action control by identifier, so a click anywhere in a row can put
   *       the cursor where that row is acted on. The reference had no need of this -- a 3270 operator
   *       moved the cursor with the tab key onto a field whose position they could see -- but design gap
   *       G1 surrenders exactly that position, so the pointer needs a way of saying which field a row
   *       click belongs to.
   * WHY : Assumptions: a mutable map keyed by the record's own key, not an index. The identifier is the
   *       `USRSEC` cluster key (`app/cpy/CSUSR01Y.cpy` `SEC-USR-ID PIC X(08)`) and is already this
   *       table's `rowKey`, so an entry cannot be re-associated with a different operator when a page
   *       turns -- which an index-keyed map would do on every page move.
   * WHY : Alternatives Considered: resolving the control with `document.getElementById` from the
   *       identifier {@link actionCellId} already composes. Rejected because it reaches outside the
   *       component's own tree for a node the component rendered, which would also find a stale node
   *       during the render pass that replaces a page.
   */
  const actionCellRefs = useRef<Map<string, HTMLInputElement>>(new Map());

  const readPage = useCallback(
    /**
     * Reads one page, carrying the applied positioning key on the opening read.
     *
     * ⚠️ Assumptions: the reader closes over the applied key rather than the key being handed to the
     * hook as a criterion, because the hook is deliberately ignorant of what a screen's criteria ARE
     * -- `ui/src/hooks/usePagedQuery.ts` states that its restart value is named in a dependency list
     * and read nowhere in its body. So the key travels in the reader and the same value travels again
     * as `resetKey` below, which is what makes the restart and the request agree by construction.
     *
     * ⚠️ Assumptions: the closure is SAFE against the reader being called with a stale key, and the
     * hook's own ordering is why. It holds the reader in a ref refreshed by an effect with no
     * dependency list, declared BEFORE the effect that restarts on `resetKey`; React runs a
     * component's effects in declaration order, so on the render that applies a new key the ref
     * already carries this closure by the time the restart runs.
     * @param {PagedQueryRequest} request - The position to read from and the direction to read in.
     * @returns {Promise<PageResponse<UserSummary>>} One bounded page with its two cursors.
     */
    (request: PagedQueryRequest): Promise<PageResponse<UserSummary>> =>
      fetchUserPage(request, appliedBrowseKey),
    [appliedBrowseKey],
  );

  const browse = usePagedQuery<UserSummary>({
    // WHY : Assumptions: ten row positions, from the mapset's ten `SEL0001`-`SEL0010` families at rows
    //       10 to 19 and corroborated by `02 USER-REC OCCURS 10 TIMES` in the program. The hook
    //       supplies no default for this deliberately, because the five migrated browses page at five
    //       different arities and a default would be wrong on most of them while still type-checking.
    pageSize: USER_LIST_PAGE_SIZE,
    fetchPage: readPage,
    /*
     * WHY : Alternatives Considered: calling the hook's imperative `reset` from the search handler.
     *       Rejected because the hook publishes `resetKey` for precisely this and states that a change
     *       to it restarts the browse, so the declarative form cannot fall out of step with the applied
     *       value the way a forgotten call could. The reference restarts on the same event:
     *       `PROCESS-ENTER-KEY` runs `MOVE 0 TO CDEMO-CU00-PAGE-NUM` at L227 immediately before paging
     *       forward, because a position established under one key addresses nothing under another.
     */
    resetKey: appliedBrowseKey,
  });

  const beginTurn = useCallback(
    /**
     * Clears everything that belongs to one screen turn rather than to the browse.
     *
     * Assumptions: every turn of the reference begins `MOVE SPACES TO WS-MESSAGE ERRMSGO`, so a
     * sentence never outlives the turn that raised it. Calling this first in each handler reproduces
     * that, and it is what stops a paging refusal from still being on the glass after a later action
     * has succeeded.
     * @returns {void} The band is returned to its empty state.
     */
    (): void => {
      setBand(EMPTY_BAND);
    },
    [],
  );

  useEffect(
    /**
     * Paints the sentence a save on the update screen published just before it transferred here.
     *
     * ⚠️ Purpose: this is the reading end of the gap a browser sweep measured on `F3=Save&&Exit`. That
     * key writes and transfers in one turn, so the band it publishes is discarded by its own unmount --
     * `PUT` returning `200` followed by an arrival whose band was empty. The reference has the same
     * shape and loses it the same way: `UPDATE-USER-SEC-FILE` composes the green sentence and performs
     * `SEND-USRUPD-SCREEN` at `app/cbl/COUSR02C.cbl` L370-L377, and the `EXEC CICS XCTL` at L258-L261
     * then overwrites the terminal with the destination's map. Collecting it here is that sentence
     * reaching the reader the program addressed it to.
     *
     * Assumptions: the sentence is published as this turn's band, so it obeys the same lifetime as any
     * other sentence this screen raises -- `beginTurn` clears it on the operator's next key, which is
     * the reference's `MOVE SPACES TO WS-MESSAGE` at the head of each turn. It is deliberately NOT given
     * a longer life than a locally-raised one: an outcome of the previous screen must not still be on
     * the glass after the operator has acted on this one.
     *
     * Assumptions: collecting on mount is sufficient here, and that is a property of the retaining side
     * rather than an assumption about timing. The update screen retains and then navigates in one
     * synchronous handler, so the outcome exists before this screen is mounted at all.
     * Alternatives Considered: also subscribing through `subscribeToRetainedOutcomes`, which
     * `ui/src/api/client.ts` provides for an outcome that lands 7 to 12 ms AFTER the destination
     * mounted. Rejected because no path retains under this claim after a navigation has been issued, so
     * the listener could never fire -- and a subscription that cannot fire is a line a later reader
     * would have to disprove.
     *
     * Assumptions: collection REMOVES the outcome, which is what makes running this on every mount
     * safe. An operator who returns to the browse a second time is not shown a write they were already
     * told about.
     * @returns {void} Completion is represented by the screen's own band state.
     */
    (): void => {
      const handed = claimRetainedOutcome<UserUpdateSaveHandover>(USER_UPDATE_SAVE_CLAIM);

      /*
       * WHY : Assumptions: the `FAILED` arm is declined rather than rendered, and the guard is here
       *       because the retained type admits it -- not because this pair produces it. The update
       *       screen reduces every failure to one of its own sentences before it hands anything over, so
       *       it always retains `COMPLETED` and the tone carries success or refusal. A `FAILED` outcome
       *       carries a raised error and no sentence, and painting one would put a framework or
       *       transport string where the reference paints a program's own.
       */
      if (handed === undefined || handed.settled !== 'COMPLETED') {
        return;
      }

      setBand({ text: handed.value.message, severity: handed.value.severity });
    },
    [],
  );

  const selection = useMemo(
    /**
     * Reduces the live action cells to the single row action they express.
     *
     * ⚠️ Refactoring Rationale: the reduction reads the page AS DELIVERED, where it read a
     * client-filtered view of it. The positioning key now reaches the service, so the delivered page
     * already begins at the applied key and a second comparison here could only drop rows the service
     * deliberately returned -- which is what previously emptied the table for any key sorting past the
     * tenth stored row. The reference reduces exactly what its map displays, and this is that.
     * @returns {UserRowSelection} The reduction, recomputed when a cell entry or the page changes.
     */
    (): UserRowSelection => reduceUserRowSelection(browse.items, actionCodes),
    [browse.items, actionCodes],
  );

  const handleEnter = useCallback(
    /**
     * Applies the turn the Enter key requests: act on a marked row, else reposition the browse.
     *
     * Assumptions: the row action is settled BEFORE the browse is repositioned, which is the
     * reference's own order. `PROCESS-ENTER-KEY` evaluates the selection and transfers control at
     * L189-L216, and only a turn that transferred nowhere falls through to the key read at L218 and the
     * restart at L227 -- so a marked row leaves the screen and never re-reads a page it abandons.
     *
     * Assumptions: the search field is CLEARED once the key has been applied, and only then. L231-L233
     * moves a space into `USRIDINO` under `IF NOT ERR-FLG-ON`, so the entry survives a failed turn and
     * is consumed by a successful one. The applied key is held separately from the entry for that
     * reason: clearing the control must not move the browse off the page it just opened, and the
     * applied value is what the reader sends and what restarts the browse.
     * @returns {void} Completion is a navigation, a painted sentence or a restarted browse.
     */
    (): void => {
      beginTurn();

      if (selection.message !== null) {
        setBand({ text: selection.message, severity: 'error' });
        return;
      }

      if (selection.userId !== null && selection.code !== null) {
        // WHY : Assumptions: the identifier comes from the SELECTED ROW and never from a client-held
        //       session field. The reference carried it in `CDEMO-CU00-USR-SELECTED`, which AAP
        //       section 0.7.1 replaces with a path parameter so the receiving request is
        //       self-describing and can be authorized on its own terms rather than on a value the
        //       client asserted.
        /*
         * WHY : ⚠️ Refactoring Rationale: the transition hands this browse over as the destination's
         *       ORIGIN, where it previously handed over nothing. `app/cbl/COUSR00C.cbl` L193-L194 and
         *       L203-L204 move this transaction and program into `CDEMO-FROM-TRANID` and
         *       `CDEMO-FROM-PROGRAM` on both arms immediately before the `XCTL`, and `COUSR03C.cbl`
         *       L111-L118 returns to whatever that carrier names on PF3 -- so the reference returns an
         *       administrator to this browse and this delivery returned them to the administrative
         *       menu, because the destination read a carrier nobody wrote.
         * WHY : Assumptions: the origin is this browse's parameterless route rather than the address
         *       the operator arrived at, because the destination validates the claim against a closed
         *       set of parameterless routes and this screen's search key lives in its state rather
         *       than in its address.
         */
        navigateSafely(
          navigate,
          selection.code === USER_LIST_ROW_ACTION_CODES.update
            ? userEditPath(selection.userId)
            : userDeletePath(selection.userId),
          { from: USER_LIST_ROUTE },
        );
        return;
      }

      setAppliedBrowseKey(searchEntry.trim());
      setSearchEntry('');
    },
    [beginTurn, navigate, searchEntry, selection],
  );

  const handleBack = useCallback(
    /**
     * Returns to the administrative menu, replacing `XCTL PROGRAM('COADM01C')`.
     * @returns {void} Completion is represented by the router transition.
     */
    (): void => {
      beginTurn();
      navigateSafely(navigate, ADMIN_MENU_ROUTE);
    },
    [beginTurn, navigate],
  );

  const handlePageBackward = useCallback(
    /**
     * Pages backward, or refuses at the opening page with the reference's own sentence.
     *
     * ⚠️ Assumptions: the refusal sentence here is the "already at" one and is NOT interchangeable
     * with the "have reached" one raised when a backward read exhausts the file.
     * `app/cbl/COUSR00C.cbl` holds five DISTINCT paging sentences -- L251, L273, L603, L637 and L671
     * -- and the browse hook emits none of them, so choosing the right one per condition is entirely
     * this screen's work. Merging any two would lose text that transformation rule T8 requires to be
     * carried across character for character.
     * @returns {void} Completion is represented by a painted sentence or a backward step.
     */
    (): void => {
      beginTurn();

      if (BACKWARD_EXHAUSTED.includes(browse.boundary)) {
        setBand({
          text: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
          severity: 'error',
        });
        return;
      }

      /*
       * WHY : Assumptions: the turn is SETTLED with a named no-op rather than left as a bare
       *       statement, because the hook now answers with a promise. See
       *       {@link ignoreSettledPageTurn} for why discarding is correct here and why neither `void`
       *       nor an `async` handler is available as the discard.
       */
      browse.prevPage().then(ignoreSettledPageTurn, ignoreSettledPageTurn);
    },
    [beginTurn, browse],
  );

  const handlePageForward = useCallback(
    /**
     * Pages forward, or refuses when the envelope reports no further page.
     *
     * Assumptions: the refusal is decided by the browse's published POSITION and never by counting the
     * rows received. That position is derived from the envelope's further-page indicator, which the
     * service settles from a read of one row beyond the page -- which is how `COUSR00C` settles
     * `NEXT-PAGE-YES` too, and `PROCESS-PF8-KEY` refuses on the flag alone at L270-L273. A count of the
     * rows received cannot answer it, because a full page and a full last page hold the same number.
     * @returns {void} Completion is represented by a painted sentence or a forward step.
     */
    (): void => {
      beginTurn();

      if (FORWARD_EXHAUSTED.includes(browse.boundary)) {
        setBand({
          text: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
          severity: 'error',
        });
        return;
      }

      /*
       * WHY : Assumptions: settled with the same named no-op as the backward arm, for the reason
       *       {@link ignoreSettledPageTurn} records. The two arms are written identically so a reader
       *       comparing them cannot mistake a difference in discipline for a difference in behaviour.
       */
      browse.nextPage().then(ignoreSettledPageTurn, ignoreSettledPageTurn);
    },
    [beginTurn, browse],
  );

  /*
   * WHY : Assumptions: EXACTLY four bindings, because `app/cbl/COUSR00C.cbl` L121-L133 dispatches
   *       exactly four attention identifiers -- Enter, PF3, PF7 and PF8 -- and answers everything else
   *       through its `WHEN OTHER` arm. Registering PF4, PF5 or PF12 would put a key on the legend that
   *       the reference screen does not offer.
   * WHY : ⚠️ Refactoring Rationale: NO binding here carries a `disabled` predicate, and the two paging
   *       keys used to. Greying them read as the "`disabled` bound to `hasNext`/`hasPrev`" mapping AAP
   *       section 0.3.2 gives a page-navigation pair, and it cost a verbatim sentence: the reference
   *       refuses no paging key. `PROCESS-PF7-KEY` at L248-L254 and `PROCESS-PF8-KEY` at L271-L277 each
   *       dispatch the key, move their own sentence into `WS-MESSAGE` and re-send the map, so the
   *       operator is always told which end they are at in the program's own words. A withdrawn key
   *       cannot say anything, and `ui/src/layout/usePfKeys.ts` answers a disabled binding through the
   *       unmapped-key path -- so the previous form put "Invalid key pressed" where the reference puts
   *       "You are already at the top of the page...". `ui/src/hooks/usePagedQuery.ts` records the same
   *       finding across all five browse screens and states the rule this now follows: a screen CHOOSES
   *       A SENTENCE from the published position and never disables a key.
   * WHY : Assumptions: nothing needs disabling to stay SAFE, because a step from an exhausted end is
   *       already a documented no-op -- both handlers above answer the boundary and return before they
   *       reach the hook's paging call.
   * WHY : Alternatives Considered: keeping the greying and ALSO answering the refusal in
   *       {@link usePfKeys}' invalid-key path, which is what this screen did. It produced the right
   *       sentence for a keyboard operator and still left a pointer operator facing a control that
   *       could not be pressed, and it split one decision -- which sentence a boundary raises -- across
   *       a binding predicate and a rejection handler that had to agree about which keys were greyed.
   * WHY : ⚠️ Refactoring Rationale: all four bindings declare `risk: 'read-only'`, which is what the
   *       legend's emphasis is now painted from. It used to come from a frozen attention-identifier list
   *       that lists `ENTER` -- so this browse rendered its Enter key in primary blue, the same paint the
   *       sibling maintenance screens give a key that WRITES, and the same paint a measured pass found on
   *       `F5=Delete`. Nothing on this screen writes: `app/bms/COUSR00.bms` L457 paints
   *       `ENTER=Continue  F3=Back  F7=Backward  F8=Forward`, and `app/cbl/COUSR00C.cbl` L121-L133
   *       dispatches those four to a row transfer, a transfer back and two browse repositions. A bar with
   *       no primary control is therefore the accurate rendering of a screen with no action to take, and
   *       it is what lets an emphasised control elsewhere in the application still mean something.
   * WHY : ⚠️ Assumptions: NO binding declares `busy`, and the omission is a decision rather than an
   *       oversight. `busy` is a per-KEY statement that this key's own turn is outstanding, and the only
   *       in-flight signal this screen has is the browse's single `isLoading` flag, which cannot tell one
   *       direction's turn from the other's. Declaring it on both paging keys would therefore decline a
   *       press of the OPPOSITE direction while a turn ran -- and `ui/src/hooks/usePagedQuery.ts`
   *       coalesces only IDENTICAL in-flight turns precisely so an operator who overshoots can step back
   *       immediately. Suppressing that would be a behaviour regression dressed as an affordance.
   * WHY : Alternatives Considered: declaring `busy` on Enter alone, whose turn is distinguishable. It was
   *       rejected because Enter's turn is a route transfer or a browse restart, and the restart's
   *       in-flight window is the same `isLoading` the two paging keys share -- so the declaration would
   *       announce Enter busy during a page turn it did not start. The in-flight affordance for this
   *       screen is carried where it is unambiguous instead: `Table loading` renders the design system's
   *       own spinner over the rows being replaced.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: handleEnter, label: USER_LIST_KEY_LABELS.ENTER, risk: 'read-only' },
    PFK03: { onInvoke: handleBack, label: USER_LIST_KEY_LABELS.PFK03, risk: 'read-only' },
    PFK07: { onInvoke: handlePageBackward, label: USER_LIST_KEY_LABELS.PFK07, risk: 'read-only' },
    PFK08: { onInvoke: handlePageForward, label: USER_LIST_KEY_LABELS.PFK08, risk: 'read-only' },
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /*
     * WHY : Assumptions: `restoreFocusRef` IS supplied here, unlike on the user maintenance screen.
     *       This program's `WHEN OTHER` arm moves `-1` into `USRIDINL` at L126 before sending the map,
     *       so the reference does place the cursor on the search field for an unrecognised key; the
     *       maintenance program's equivalent arm moves no such value, and that screen therefore omits
     *       this option. The difference is the source's, not a preference.
     */
    restoreFocusRef: searchFocusRef,
    /**
     * Answers a key this screen does not bind with the reference's invalid-key sentence.
     *
     * ⚠️ Refactoring Rationale: this used to branch on a `'disabled'` refusal and compose a boundary
     * sentence for it, because the two paging keys were greyed at the ends of the browse and the hook
     * answers a greyed binding through this path rather than through the binding's own handler. Neither
     * key is greyed now -- the reference refuses no paging key, and the binding block above records why
     * -- so a refusal can no longer be a boundary and the branch is gone rather than left standing over
     * a state that cannot arise. The two boundary sentences are raised where the two conditions are
     * actually known, in {@link handlePageBackward} and {@link handlePageForward}, which is also where
     * the reference raises them.
     *
     * ⚠️ Assumptions: every refusal reaching here is therefore an UNMAPPED key, which is exactly the
     * `WHEN OTHER` arm at `app/cbl/COUSR00C.cbl` L124-L133: it moves the invalid-key sentence, places
     * the cursor on the search field and re-sends the map. Both halves are honoured -- the sentence
     * below and the `restoreFocusRef` above.
     *
     * Assumptions: the rejection payload is not read, and the parameter is therefore not declared. One
     * refusal reason is now reachable and one sentence answers it, so reading the payload could only
     * re-derive a constant this screen already holds; the hook's option type accepts a handler that
     * takes fewer arguments than it supplies.
     * @returns {void} Completion is represented by the screen's own band state.
     */
    onInvalidKey: (): void => {
      /*
       * WHY : Assumptions: the sentence is read from the catalog rather than from the rejection the hook
       *       composed, so this screen keeps one source for every string it paints. It is the same
       *       `CCDA-MSG-INVALID-KEY` value the program moves at L128, declared at
       *       `app/cpy/CSMSG01Y.cpy`; the hook publishes it too, so this is a second reader of one
       *       constant rather than a second transcription.
       */
      setBand({ text: INVALID_KEY_PRESSED, severity: 'error' });
    },
  });

  /*
   * WHY : Assumptions: a listing failure OUTRANKS whatever the turn had to say, because a sentence
   *       about paging or a selection describes a page the operator is no longer looking at. The
   *       reference reaches the same outcome by construction: its read paragraphs move the failure
   *       sentence into `WS-MESSAGE` after the key handler has written to it, so the last write wins
   *       and the last write is the read's.
   */
  const paintedBand: ScreenBand =
    band.text === null ? listingFailureBand(browse.isFailed, browse.error) : band;

  /*
   * WHY : Refactoring Rationale: the header band, the row-23 message and the row-24 legend are
   *       DELEGATED to `AppShell` rather than composed here, which is the contract every screen in this
   *       tree follows. Composing them locally would render a second live region and a second key
   *       legend on top of the ones the frame already paints, and `useShellSlot` renders a zone if and
   *       only if it has been delegated -- so publication is the signal, and withholding is how a
   *       screen declines a zone.
   */
  useShellSlot({
    screen: {
      transactionId: USER_LIST_TRANSACTION_ID,
      programName: USER_LIST_PROGRAM_NAME,
    },
    message: {
      text: paintedBand.text,
      severity: paintedBand.severity,
      mapset: USER_LIST_MAPSET,
    },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
      // WHY : Assumptions: the legend is YELLOW, from `COLOR=YELLOW` on the row-24 field at
      //       `POS=(24,1)`. It is stated rather than defaulted so a reader of this call sees the
      //       measured operand instead of inferring it from the shell's fallback.
      legendColor: 'YELLOW',
    },
    /*
     * WHY : Assumptions: the instant is SERVER-anchored rather than read from the browser clock. The
     *       reference read one region clock for every terminal, so two operators looking at one record
     *       could not read two different dates across midnight; `ScreenHeader` degrades to the browser
     *       clock when this is absent, which is a divergence that module records.
     */
    ...(now === undefined ? {} : { now }),
  });

  const fixedPitchStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  const actionCellStyle = actionCellWidthStyle(cssVar);

  /*
   * WHY : Assumptions: `ATTRB=BRT` on the row-21 prompt resolves to WEIGHT and not to a colour. All 37
   *       bright fields in the base mapsets already carry a `COLOR=` operand -- this one is
   *       `COLOR=NEUTRAL` -- so expressing brightness as colour would collide with the operand on every
   *       one of them, while weight keeps the two axes independent.
   */
  const brightNeutralStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };

  /*
   * WHY : Assumptions: the two prompts on row 4 and row 6 are `COLOR=TURQUOISE`, which the bridge
   *       resolves to the informational role. The TEXT-grade shade is taken rather than the role token
   *       because both are runs of prose: `BMS_TEXT_COLOR_TOKENS` records that the cyan ramp publishes
   *       nothing above 3.55:1 against the shell's surface, so the role token alone would put a label
   *       the operator has to read below the 4.5:1 minimum, and it snaps to the audited label shade.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: the no-wrap was ADDED after a browser measurement showed this label
   *       breaking across two lines at 1440x900, which the mapset does not do -- `Search User ID:` is
   *       ONE `LENGTH=15` field on row 6 (`app/bms/COUSR00.bms` `POS=(6,5)`), so a break puts a line of
   *       text where the reference has none and pushes the field it names off its own row. Design gap G1
   *       surrenders absolute character POSITIONING and undertakes to preserve grouping and reading
   *       order, and a label separated from its control by a line break gives up the grouping too.
   * WHY : Alternatives Considered: widening the label's container, or removing `wrap` from the row that
   *       holds it. Both were rejected because each fixes the symptom at one viewport and reintroduces
   *       it at a narrower one -- the row must still be free to wrap AS A WHOLE, moving the indicator
   *       below the field, which is the reflow G1 undertakes to keep. Pinning only this label keeps that
   *       freedom while making the label itself indivisible, which is what the mapset declares it to be.
   */
  const turquoiseLabelStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE],
    whiteSpace: 'nowrap',
  };

  /**
   * Renders one row's action cell: the one-character entry the reference's `SEL` field accepts.
   *
   * ⚠️ Alternatives Considered: a `Radio` or a `Checkbox`, which is the generic mapping AAP section
   * 0.3.2 gives a "selection marker column". Rejected because this cell is not a marker: it carries
   * WHICH of two actions is requested, `'U'` for update or `'D'` for delete, and a radio can express
   * only that a row is chosen. A radio plus a separate action control, or a per-row select, would each
   * add a control the mapset does not paint. The field's own attributes settle it -- `SEL0001` through
   * `SEL0010` are `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1`, an UNPROTECTED one-character entry field,
   * which the same section maps to `Input` inside `Form.Item` with `maxLength` set from the declared
   * width. That is also the shape the transaction-type browse uses for its own one-character action
   * cell, so a reader meets one idiom rather than two.
   *
   * ⚠️ Assumptions: the cell is labelled for assistive technology because the terminal identified it by
   * POSITION, and position is exactly what design gap G1 gives up. The visible heading is three
   * characters, so the accessible name pairs it with the row's identifier.
   * @param {UserSummary} row - The row the cell acts on.
   * @returns {ReactElement} The one-character action entry for that row.
   */
  function renderActionCell(row: UserSummary): ReactElement {
    const controlId = actionCellId(row.userId);
    /*
     * WHY : Assumptions: the cell is marked in error only while it is BOTH the selected row and the
     *       selection carries a refusal, and both halves are needed. Marking every row would highlight
     *       nine cells the operator never touched; marking a selected row with no refusal would point
     *       `aria-describedby` at an element this render does not emit.
     */
    const refusal = selection.userId === row.userId ? selection.message : null;

    return (
      <Form.Item
        help={refusal === null ? undefined : fieldErrorHelp(controlId, refusal)}
        /*
         * WHY : Assumptions: `noStyle` while there is nothing to explain, so an unmarked cell keeps the
         *       table's own row height and no form-item spacing is introduced into a grid cell. The
         *       laid-out form is taken only when there is help text to place.
         */
        noStyle={refusal === null}
        validateStatus={refusal === null ? '' : 'error'}
      >
        <Input
          {...fieldAriaProps(controlId, {
            hasError: refusal !== null,
            hasHint: false,
            invalid: refusal !== null,
          })}
          aria-label={`${USER_LIST_LABELS.selColumn.trim()} ${row.userId}`}
          id={controlId}
          maxLength={USER_LIST_ACTION_CELL_LENGTH}
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
                 * Replaces this row's entry, leaving every other row's entry alone.
                 * @param {Readonly<Record<string, string>>} current - Entries so far.
                 * @returns {Readonly<Record<string, string>>} Entries with this row's replaced.
                 */
                (current: Readonly<Record<string, string>>): Readonly<Record<string, string>> => ({
                  ...current,
                  [row.userId]: value,
                }),
              );
            }
          }
          ref={
            /**
             * Records this row's control so a row click can place the cursor in it.
             * @param {InputRef | null} instance - The control instance, or `null` on unmount.
             * @returns {void} The node is registered, or its entry removed on unmount.
             */
            (instance: InputRef | null): void => {
              const node = instance?.input ?? null;

              /*
               * WHY : Assumptions: the entry is DELETED on unmount rather than left holding `null`,
               *       because the map outlives a page: the ten rows of the page just left would
               *       otherwise stay in it forever, and a click on a row whose identifier happened to
               *       repeat would resolve to a detached node.
               */
              if (node === null) {
                actionCellRefs.current.delete(row.userId);
                return;
              }

              actionCellRefs.current.set(row.userId, node);
            }
          }
          status={refusal === null ? '' : 'error'}
          /*
           * WHY : ⚠️ Assumptions: the measure is spread onto the DESIGN-SYSTEM CONTROL and not onto a
           *       wrapper, because the padding custom property the expression reads resolves on
           *       `.ant-input` and returns the empty string on an arbitrary element -- the same measured
           *       constraint `ui/src/layout/recordLayout.ts` records for the declared-width ceiling.
           */
          style={actionCellStyle}
          value={actionCodes[row.userId] ?? ''}
        />
      </Form.Item>
    );
  }

  const columns = buildUserListColumns({
    renderActionCell,
    fixedPitchStyle,
    cellPadding: cssVar.padding,
  });

  return (
    /*
     * WHY : Assumptions: layout is composed from the design system's own primitives and carries no
     *       bespoke CSS on a raw element. `gap` takes a named step on the antd spacing scale rather than
     *       a length, which is what keeps the "zero hardcoded values" rule true for this screen -- the
     *       only literals anywhere in it are the token NAMES it reads through `cssVar`.
     */
    <Flex vertical gap="large">
      <ScreenTitle>{USER_LIST_TITLE}</ScreenTitle>
      {/*
       * WHY : ⚠️ Refactoring Rationale: the screen ANNOUNCES its outstanding turn, where it previously
       *       only showed one. A review found `aria-busy` on no button anywhere and no live region
       *       naming the wait, so an operator who could not see the in-flight affordance had nothing at
       *       all: the controls stayed reachable, the request was in flight, and the screen said nothing
       *       about it. `busyAnnouncement` renders one visually hidden `role="status"` region -- always
       *       mounted, empty while idle, because a live region has to be in the accessibility tree
       *       BEFORE its content changes for the first change to be announced.
       * WHY : Assumptions: the sentence is `REQUEST_IN_PROGRESS` from the message catalogue, which is
       *       AUTHORED rather than transcribed, and taking an authored sentence here does not weaken
       *       Rule T8. The reference has no equivalent to carry: a 3270 turn simply locked the keyboard,
       *       so there is no mapset literal this could be displacing. Every sentence on this screen that
       *       DOES have a mainframe source is still that source's, verbatim.
       */}
      {busyAnnouncement(browse.isLoading ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * WHY : Assumptions: the search field and the page indicator share a row because the mapset paints
       *       them on rows 6 and 4 of one header zone, above the list and below the title band. Their
       *       character columns -- 5 and 21 for the field, 65 and 71 for the indicator -- are given up
       *       with the rest of gap G1; their grouping and reading order are not.
       */}
      <Flex align="center" gap="middle" justify="space-between" wrap>
        {/*
         * WHY : ⚠️ Refactoring Rationale: the label is a `Typography.Text` carrying an identifier that
         *       the control resolves through `aria-labelledby`, where it was the `label` prop of a
         *       `Form.Item`. antd derives a label's `htmlFor` from the FORM and FIELD names, so a
         *       `Form.Item` used for its layout alone -- with no `name` and no `<Form>` above it, which
         *       is how this tree uses it -- renders a label bound to nothing: the visible text was
         *       there and the control had no accessible name, which a query by label proved. The two
         *       card filters beside this screen already use this idiom, so the fix is the house pattern
         *       rather than a new one. Keyboard operability is a FIDELITY requirement here because the
         *       3270 original was keyboard-only, so an unnamed control is a parity defect and not a
         *       cosmetic one.
         * WHY : Assumptions: no `<Form>` element wraps this control, and none is needed. Enter is
         *       dispatched by `usePfKeys` from wherever the cursor sits, which is what the terminal did
         *       -- the attention identifier was read from the keyboard and not from a submit button --
         *       so a form's own submit path would be a second, divergent route to one action.
         */}
        <Flex align="center" gap="small">
          <Typography.Text id={SEARCH_USER_ID_LABEL_ID} style={turquoiseLabelStyle}>
            {USER_LIST_LABELS.searchUserId}
          </Typography.Text>
          {/*
           * WHY : ⚠️ Assumptions: this control receives NO `autoFocus`, and the omission is fidelity
           *       rather than an oversight -- state it or a reviewer comparing this screen with
           *       `signon` or `accountUpdate` will read it as a defect. `app/bms/COUSR00.bms` carries
           *       no `IC` attribute anywhere; grepped across the whole mapset, the only textual match
           *       is the Apache licence URL. This is one of only four screens in the migration with
           *       no initial-cursor field. The program's repeated `MOVE -1 TO USRIDINL OF COUSR0AI` is
           *       3270 cursor PLACEMENT on a later turn, not an `IC` attribute on the map, and it is
           *       honoured through `restoreFocusRef` above rather than by claiming focus on mount.
           * WHY : Assumptions: `maxLength` is eight, and it is the same eight twice over --
           *       `USRIDIN ... LENGTH=8` in the mapset and `SEC-USR-ID PIC X(08)` at
           *       `app/cpy/CSUSR01Y.cpy`. It is read from `USER_ID_MAX_LENGTH` in
           *       `ui/src/api/auth.ts` rather than written as a literal, so the control and the
           *       contract cannot come to disagree about the width.
           * WHY : Assumptions: `HILIGHT=UNDERLINE` on this field needs no token. The underline marked
           *       an input on a terminal that had no other way to show one; the `Input` component's
           *       own border carries that affordance structurally, which is design gap G4 -- recorded
           *       so a later reader does not mistake the absent token for an omission.
           */}
          <Input
            aria-labelledby={SEARCH_USER_ID_LABEL_ID}
            {...fieldAriaProps(SEARCH_USER_ID_INPUT_ID, {
              hasError: false,
              hasHint: false,
              invalid: false,
            })}
            id={SEARCH_USER_ID_INPUT_ID}
            maxLength={USER_ID_MAX_LENGTH}
            onChange={
              /**
               * Records the browse key as it is typed, without applying it.
               * @param {object} event - The change event the design system forwards.
               * @param {object} event.target - The control the event came from.
               * @param {string} event.target.value - The entry as it now stands.
               * @returns {void} State is updated in place.
               */
              (event: { target: { value: string } }): void => {
                setSearchEntry(event.target.value);
              }
            }
            ref={
              /**
               * Captures the control's DOM node so an unrecognised key can return the cursor to it.
               * @param {InputRef | null} instance - The control instance, or `null` on unmount.
               * @returns {void} The node is recorded on the ref the key hook reads.
               */
              (instance: InputRef | null): void => {
                searchFocusRef.current = instance?.input ?? null;
              }
            }
            style={fixedPitchStyle}
            value={searchEntry}
          />
        </Flex>
        {/*
         * WHY : ⚠️ Assumptions: the indicator renders the browse's DISPLAY-ONLY ordinal and is never
         *       turned back into a request. `PAGENUM ... LENGTH=8 POS=(4,71)` exists on the mapset and
         *       `ui/src/api/auth.ts` records that it never positions the browse, so publishing it as a
         *       request member would invent positioning semantics the baseline never had.
         */}
        <Typography.Text style={fixedPitchStyle}>
          {`${USER_LIST_LABELS.pageIndicator} ${String(browse.pageNumber)}`}
        </Typography.Text>
      </Flex>
      {/*
       * WHY : ⚠️ Alternatives Considered: antd's own built-in pagination, which is what `pagination`
       *       defaults to. Rejected because it is OFFSET paging: under concurrent inserts an offset
       *       skips rows and repeats rows, which a browse-by-key does not, so adopting it would change
       *       observable behaviour that the golden masters fix. The reference pages by key -- it carries
       *       a first-key and last-key pair and discovers one record beyond the ten it shows -- so
       *       keyset paging is transcription here and offset paging would be a regression dressed as a
       *       simplification. F7 and F8 are the controls, bound to the envelope's own availability.
       */}
      <Table
        columns={columns}
        dataSource={browse.items}
        loading={browse.isLoading}
        /*
         * WHY : ⚠️ Purpose: `onRow` gives each row the affordance it did not have. A browser pass
         *       measured `cursor: auto` on these rows both at rest and hovered, on a browse whose whole
         *       purpose is choosing rows to act on -- so nothing about a row said it could be acted on,
         *       and the only clue was the one-character control in its leading column, which the same
         *       pass measured with a zero-width content box.
         * WHY : ⚠️ Assumptions: a row click places the CURSOR in that row's action cell and types
         *       nothing into it. The reference separates choosing a row from acting on it and the
         *       separation is load-bearing: `PROCESS-ENTER-KEY` reads the action characters and only
         *       then transfers control (`app/cbl/COUSR00C.cbl` L288 onward), so a mis-aimed click costs
         *       a cursor move and never a navigation or a deletion. Writing `'U'` into the cell on a
         *       click would put the operator one Enter away from an update they did not ask for, and
         *       writing `'D'` one Enter away from a deletion.
         * WHY : ⚠️ Assumptions: no `tabIndex` is put on the row, and the absence is deliberate. Each row
         *       already carries a focusable, named control in its leading column, so the keyboard route
         *       through the rows exists -- ten tab stops, each announcing `Sel` with the row's own
         *       identifier -- and a focusable row would double every one of those stops with an element
         *       that has no accessible name. This matches the treatment the authorization browse
         *       records for the same measurement.
         */
        onRow={
          /**
           * Makes a row's whole area put the cursor in that row's action cell.
           * @param {UserSummary} row - The user the row lists.
           * @returns {{ onClick: () => void; style: CSSProperties }} The row's handler and style.
           */
          (row: UserSummary): { onClick: () => void; style: CSSProperties } => ({
            /**
             * Places the cursor in the clicked row's action cell, changing no value.
             * @returns {void} Nothing; focus moves as a side effect.
             */
            onClick: (): void => {
              actionCellRefs.current.get(row.userId)?.focus();
            },
            style: ROW_AFFORDANCE_STYLE,
          })
        }
        pagination={false}
        /*
         * WHY : Assumptions: the row key is the user identifier, which `app/cpy/CSUSR01Y.cpy` makes the
         *       record's own key -- `SEC-USR-ID PIC X(08)` is the `USRSEC` cluster key -- so it is
         *       unique within a page by construction and the action cells can be keyed by it. Using the
         *       row index instead would re-associate every typed entry with a different operator the
         *       moment a page turned.
         */
        /*
         * WHY : ⚠️ Refactoring Rationale: NO `size` is declared, and `size="small"` used to be. A
         *       rendering comparison across the four browse screens found this one alone rendering
         *       `ant-table-small` with 8-pixel cell padding while the card, transaction and
         *       reference-type browses all took the design system's default 16. Nothing about this
         *       mapset asks for the tighter scale -- `app/bms/COUSR00.bms` gives its ten row families
         *       the same one row each that every other browse mapset gives its rows -- so the override
         *       was density chosen for this screen and nowhere else, and three screens against one
         *       settles which of the two is the tree's idiom.
         * WHY : Alternatives Considered: keeping `small` and changing the other three, which would have
         *       been the same reconciliation in the opposite direction. Rejected on two counts: those
         *       three files belong to other work and this one does not, and the tighter scale reduces
         *       the pointer target of the action cell in every row, which is the measurement
         *       {@link actionCellWidthStyle} exists to protect.
         * WHY : Trade-offs: ten rows at the larger padding are taller than ten at the smaller, so more
         *       of the browse sits below the fold on a short viewport. That is accepted because the
         *       shell already scrolls its content region -- design gap G1 gave up the fixed 24-row
         *       display precisely so content could grow -- and because a row an operator has to scroll
         *       to is better than a row whose action cell they cannot aim at.
         */
        rowKey="userId"
        /*
         * WHY : ⚠️ Purpose: give the grid its OWN horizontal scrolling region, so a viewport narrower
         *       than the mapset's own row scrolls the grid rather than the page. {@link
         *       userListTableMeasure} records the measurement this answers -- 398 pixels of document
         *       against a 375-pixel viewport, with `window.scrollX` reaching 23 -- and why declaring
         *       the extent is what creates the region.
         * WHY : ⚠️ Assumptions: `tableLayout` is stated EXPLICITLY and must be, because the design
         *       system would not infer it here. `@rc-component/table/lib/Table.js` L426-L442 falls
         *       back to `'fixed'` only for a pinned column, a pinned header, a sticky grid or an
         *       ellipsised column, and this grid has none of the four -- so with the extent declared
         *       and the layout left to infer, it would resolve to `'auto'`. Under an automatic layout a
         *       declared column width is a MINIMUM the content may grow, which is the exact defect
         *       {@link USER_LIST_COLUMN_CELLS} records: the one-character action column measured about
         *       700 pixels because an unconstrained `Input` reports a full-width intrinsic size. Fixing
         *       the layout is what keeps the five mapset-derived shares authoritative once an extent
         *       exists for them to resolve against.
         */
        scroll={{ x: userListTableMeasure(cssVar) }}
        tableLayout="fixed"
      />
      {/*
       * WHY : Assumptions: the prompt is painted on every turn rather than shown only when a cell is in
       *       error, because `POS=(21,12)` is a static `INITIAL=` field on the map and a map is sent
       *       whole. It is what defines the two codes to an operator, which is why the one-character
       *       controls above can be labelled with a code rather than with a word.
       */}
      <Typography.Text style={brightNeutralStyle}>
        {USER_LIST_LABELS.rowActionPrompt}
      </Typography.Text>
    </Flex>
  );
}
