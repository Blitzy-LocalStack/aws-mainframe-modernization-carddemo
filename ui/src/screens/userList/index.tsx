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
 * Narrowing contract
 * ------------------
 * ⚠️ Assumptions: the search field is a browse POSITIONING key and not a substring search. The
 * reference moves `USRIDINI` into `SEC-USR-ID` and hands it to `STARTBR ... RIDFLD(SEC-USR-ID)`,
 * whose greater-or-equal positioning opens the browse AT OR AFTER the identifier typed; a blank entry
 * becomes `LOW-VALUES` and opens at the start of the file.
 *
 * ⚠️ Trade-offs: that positioning is applied to the page this client HOLDS rather than pushed into the
 * request, and the reason is a contract limit rather than a choice. `UserListQuery` in
 * `ui/src/api/types.ts` declares `cursor` and `direction` and NOTHING else, and the service agrees
 * from the other side -- `services/auth-service/src/main/java/com/carddemo/auth/api/UserController.java`
 * declares exactly two request parameters on its listing, `cursor` and `direction`. So there is no
 * positioning parameter to send. Alternatives Considered, and why each is worse: (1) sending one
 * anyway, rejected because the parameter does not exist and `ui/src/api/auth.ts` exposes no member
 * for it, so the entry would be silently dropped and the operator would see an unnarrowed page as
 * though the key had been honoured; (2) minting a cursor here from the typed identifier, rejected
 * because a cursor is an opaque token the service SEALS -- including the direction it was issued for
 * -- so a fabricated one is refused with HTTP 400 rather than answered, and `types.ts` states the
 * client neither parses, compares nor constructs one; (3) paging forward repeatedly until the key is
 * reached, rejected because it turns one keystroke into an unbounded number of requests where the
 * reference issues a single `STARTBR`. The accepted consequence is stated rather than hidden: an
 * identifier beyond the page on display narrows that page to nothing and the operator reaches the key
 * with F8, where the terminal would have positioned there directly.
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
import type { InputRef, TableColumnsType } from 'antd';
import { useCallback, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { USER_ID_MAX_LENGTH, listUsers } from '../../api/auth';
import type { ApiError, PageResponse, UserSummary } from '../../api/types';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import type { PagedQueryRequest } from '../../hooks/usePagedQuery';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import { INVALID_KEY_PRESSED, PROGRAM_MESSAGES, SHARED_MESSAGES } from '../../messages/messages';
import { ADMIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
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
  TYPOGRAPHY_TOKENS,
  characterCellWidthShare,
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

/** Route this screen occupies, which administrative menu option 1 transfers to. */
export const USER_LIST_PATH = '/users';

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
 * screen at `/users/:id/edit`.
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
 * Retains the page rows positioned at or after a browse key.
 *
 * ⚠️ Assumptions: `STARTBR` positions at-or-after rather than exactly, so the comparison is
 * greater-or-equal on the identifier and not an equality or a substring test. The reference hands the
 * typed value straight to `RIDFLD(SEC-USR-ID)` and a blank entry becomes `LOW-VALUES`, which positions
 * at the start of the file -- so a blank key retains everything.
 *
 * ⚠️ Trade-offs: this runs client-side over the delivered page for the reason the module overview
 * records at length -- the listing publishes no positioning parameter and its cursor is sealed, so
 * there is nowhere to send the key. The comparison is a plain lexical one on the eight-character
 * identifier, which is the same ordering the security file's key sequence has, so the rows retained
 * are the rows the terminal would have shown from that point within this page.
 * @param {readonly UserSummary[]} rows - The page as the browse delivered it, in key order.
 * @param {string} browseKey - The identifier typed into the search field, already trimmed.
 * @returns {readonly UserSummary[]} The rows at or after the key, or every row for a blank key.
 */
export function positionAtOrAfter(
  rows: readonly UserSummary[],
  browseKey: string,
): readonly UserSummary[] {
  if (browseKey === '') {
    return rows;
  }

  const key = browseKey.toUpperCase();

  return rows.filter(
    /**
     * Reports whether one row sorts at or after the browse key.
     * @param {UserSummary} row - A row of the delivered page.
     * @returns {boolean} `true` when the row's identifier is not before the key.
     */
    (row: UserSummary): boolean => row.userId.toUpperCase() >= key,
  );
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
 * the exact defect {@link characterCellWidthShare} was written for, so this reuses that helper rather
 * than introducing a second treatment of it. The percentages it returns are design gap G1 artefacts
 * derived from a countable mapset measurement, which is why a rendered length may be spelled there and
 * nowhere else.
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
 * @param {UserListColumnOptions} options - The row-action renderer and the fixed-pitch style.
 * @param {(row: UserSummary) => ReactElement} options.renderActionCell - Renders one row's action cell.
 * @param {CSSProperties} options.fixedPitchStyle - Applies the fixed-pitch token to aligned columns.
 * @returns {TableColumnsType<UserSummary>} The five column descriptors, in the mapset's own order.
 */
export function buildUserListColumns({
  renderActionCell,
  fixedPitchStyle,
}: UserListColumnOptions): TableColumnsType<UserSummary> {
  return [
    {
      title: USER_LIST_LABELS.selColumn.trim(),
      key: 'sel',
      width: characterCellWidthShare(USER_LIST_COLUMN_CELLS.sel, USER_LIST_COLUMN_CELLS.rowSpan),
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
      width: characterCellWidthShare(USER_LIST_COLUMN_CELLS.userId, USER_LIST_COLUMN_CELLS.rowSpan),
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
      width: characterCellWidthShare(
        USER_LIST_COLUMN_CELLS.firstName,
        USER_LIST_COLUMN_CELLS.rowSpan,
      ),
    },
    {
      title: USER_LIST_LABELS.lastNameColumn.trim(),
      dataIndex: 'lastName',
      width: characterCellWidthShare(
        USER_LIST_COLUMN_CELLS.lastName,
        USER_LIST_COLUMN_CELLS.rowSpan,
      ),
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
      width: characterCellWidthShare(
        USER_LIST_COLUMN_CELLS.userType,
        USER_LIST_COLUMN_CELLS.rowSpan,
      ),
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
 * Reads one page of users, positioned by the sealed cursor the browse hands over.
 *
 * ⚠️ Assumptions: the request carries the cursor and the direction and NOTHING else. `UserListQuery`
 * declares those two members only, so no page, offset, size or limit is expressible -- the service
 * fixes the arity at ten and settles further-page availability from a read of one row beyond the page.
 * A `null` cursor is the opening read and is sent as an ABSENT query rather than as an explicit null,
 * because `ui/src/api/auth.ts` refuses a direction whose cursor is missing rather than sending it.
 * @param {PagedQueryRequest} request - The position to read from and the direction to read in.
 * @returns {Promise<PageResponse<UserSummary>>} One bounded page of user rows with its two cursors.
 * @throws {Error} The normalised `ApiRequestError` the shared client raises -- 401 for an absent or
 *   expired token and 403 for an authenticated caller outside the administrative group. The browse
 *   hook catches it and publishes it as its `error`, which this screen reports as
 *   {@link SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER}.
 */
function fetchUserPage(request: PagedQueryRequest): Promise<PageResponse<UserSummary>> {
  return request.cursor === null
    ? listUsers()
    : listUsers({ cursor: request.cursor, direction: request.direction });
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

  const browse = usePagedQuery<UserSummary>({
    // WHY : Assumptions: ten row positions, from the mapset's ten `SEL0001`-`SEL0010` families at rows
    //       10 to 19 and corroborated by `02 USER-REC OCCURS 10 TIMES` in the program. The hook
    //       supplies no default for this deliberately, because the five migrated browses page at five
    //       different arities and a default would be wrong on most of them while still type-checking.
    pageSize: USER_LIST_PAGE_SIZE,
    fetchPage: fetchUserPage,
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

  const rows = useMemo(
    /**
     * Applies the browse positioning key to the page the service delivered.
     * @returns {readonly UserSummary[]} The rows at or after the applied key.
     */
    (): readonly UserSummary[] => positionAtOrAfter(browse.items, appliedBrowseKey),
    [browse.items, appliedBrowseKey],
  );

  const selection = useMemo(
    /**
     * Reduces the live action cells to the single row action they express.
     * @returns {UserRowSelection} The reduction, recomputed when a cell entry or the page changes.
     */
    (): UserRowSelection => reduceUserRowSelection(rows, actionCodes),
    [rows, actionCodes],
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
     * reason: clearing the control must not un-narrow the page it just produced.
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
        navigateSafely(
          navigate,
          selection.code === USER_LIST_ROW_ACTION_CODES.update
            ? userEditPath(selection.userId)
            : userDeletePath(selection.userId),
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

      if (!browse.hasPrev) {
        setBand({
          text: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
          severity: 'error',
        });
        return;
      }

      browse.prevPage();
    },
    [beginTurn, browse],
  );

  const handlePageForward = useCallback(
    /**
     * Pages forward, or refuses when the envelope reports no further page.
     *
     * Assumptions: the refusal is decided by `hasNext` and never by counting the rows received. The
     * service settles that flag from a read of one row beyond the page, which is how `COUSR00C`
     * settles `NEXT-PAGE-YES` too, and `PROCESS-PF8-KEY` refuses on the flag alone at L270-L273.
     * @returns {void} Completion is represented by a painted sentence or a forward step.
     */
    (): void => {
      beginTurn();

      if (!browse.hasNext) {
        setBand({
          text: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
          severity: 'error',
        });
        return;
      }

      browse.nextPage();
    },
    [beginTurn, browse],
  );

  /*
   * WHY : Assumptions: EXACTLY four bindings, because `app/cbl/COUSR00C.cbl` L121-L133 dispatches
   *       exactly four attention identifiers -- Enter, PF3, PF7 and PF8 -- and answers everything else
   *       through its `WHEN OTHER` arm. Registering PF4, PF5 or PF12 would put a key on the legend that
   *       the reference screen does not offer.
   * WHY : Assumptions: the two paging keys are greyed out from `hasPrev` and `hasNext` rather than from
   *       the page number. The ordinal is what the hook derives `hasPrev` FROM, so binding to the
   *       published flag keeps one derivation instead of two that could disagree.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: handleEnter, label: USER_LIST_KEY_LABELS.ENTER },
    PFK03: { onInvoke: handleBack, label: USER_LIST_KEY_LABELS.PFK03 },
    PFK07: {
      onInvoke: handlePageBackward,
      label: USER_LIST_KEY_LABELS.PFK07,
      disabled: !browse.hasPrev,
    },
    PFK08: {
      onInvoke: handlePageForward,
      label: USER_LIST_KEY_LABELS.PFK08,
      disabled: !browse.hasNext,
    },
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
     * Answers a key the hook refused: a boundary step with its own sentence, anything else with the
     * reference's invalid-key sentence.
     *
     * ⚠️ Refactoring Rationale: a `'disabled'` refusal used to return in silence here, and that was a
     * defect this screen's own tests caught. Greying the two paging keys at the boundaries is what AAP
     * section 0.3.2 asks for -- `disabled` bound to `hasPrev` and `hasNext` -- but the hook refuses a
     * disabled binding BEFORE its handler runs, so the in-handler guards below could never fire and two
     * of the five sentences the reference declares were computed nowhere. `PROCESS-PF7-KEY` at L248-L253
     * and `PROCESS-PF8-KEY` at L270-L275 both dispatch and then answer, so the answer must survive the
     * greying. Routing the refusal to the same sentence keeps both requirements: the greyed control says
     * the direction is exhausted before an attempt, and a keyboard operator who presses the key anyway
     * is told why in the reference's own words.
     * @param {PfKeyRejection} rejection - Why the key was refused, and by which attention identifier.
     * @returns {void} Completion is represented by the screen's own band state.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      if (rejection.reason === 'disabled') {
        /*
         * WHY : Assumptions: only the two paging keys are ever greyed on this screen, so the two arms
         *       below are exhaustive and a third would answer for a state that cannot arise. Enter and
         *       PF3 carry no `disabled` predicate at all -- the reference gates neither -- so a
         *       refusal reaching here can only name PF7 or PF8.
         */
        const boundary =
          rejection.aid === 'PFK07'
            ? SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE
            : SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE;

        setBand({ text: boundary, severity: 'error' });
        return;
      }

      // WHY : Assumptions: the sentence is read from the catalog rather than from the rejection
      //       payload, so this screen has one source for every sentence it paints. It is the same
      //       `CCDA-MSG-INVALID-KEY` value the program moves at L128, declared at
      //       `app/cpy/CSMSG01Y.cpy`, and the hook publishes it too -- so this is not a second
      //       transcription, only a second reader of one constant.
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
          status={refusal === null ? '' : 'error'}
          value={actionCodes[row.userId] ?? ''}
        />
      </Form.Item>
    );
  }

  const columns = buildUserListColumns({ renderActionCell, fixedPitchStyle });

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
        dataSource={rows}
        loading={browse.isLoading}
        pagination={false}
        /*
         * WHY : Assumptions: the row key is the user identifier, which `app/cpy/CSUSR01Y.cpy` makes the
         *       record's own key -- `SEC-USR-ID PIC X(08)` is the `USRSEC` cluster key -- so it is
         *       unique within a page by construction and the action cells can be keyed by it. Using the
         *       row index instead would re-associate every typed entry with a different operator the
         *       moment a page turned.
         */
        rowKey="userId"
        size="small"
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
