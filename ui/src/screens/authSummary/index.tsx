/**
 * @file The pending-authorization summary screen, migrated from
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` and its mapset
 * `app/app-authorization-ims-db2-mq/bms/COPAU00.bms` (map `COPAU0A`), reached at route
 * `/authorizations`.
 *
 * Purpose
 * -------
 * Answers one account's pending authorizations. The mapset carries 104 `DFHMDF` field definitions --
 * 62 named and 42 anonymous, counted directly from the source -- which resolve into three distinct
 * regions rather than the single list a route named `/authorizations` suggests: an account-scoped
 * search entry, a fourteen-field account summary panel, and a five-row authorization table whose
 * selection column opens the detail screen. Every measurement quoted below was taken from the two
 * source files named above; both are reference-only baseline that this migration reads and never
 * edits.
 *
 * Exports and failures
 * --------------------
 * The module publishes {@link AuthSummaryScreen} under its NAME ONLY -- the default export it once
 * carried alongside was withdrawn, for the reason recorded at the foot of this file -- together with the
 * two builders {@link buildAuthSummaryDescriptions} and {@link buildPendingAuthColumns} and the verbatim
 * constants transcribed from the mapset. `ui/src/router.tsx` republishes the named export under the
 * `default` key that `React.lazy` requires, so the lazy route reaches it through that adapter. It
 * declares no wire shape of its own and reads no module-level input. Nothing here throws: the one
 * failure source is the listing request, which the shared client normalises into an `ApiRequestError`
 * -- the CLASSIFIED failure, carrying its problem document beside the transport judgements -- that
 * {@link describeListingFailure} turns into a message-band sentence.
 *
 * Where this screen's text comes from, and why it is not all in one place
 * ---------------------------------------------------------------------
 * Assumptions: the two halves of this screen's text have two different owners, and the boundary is
 * drawn by `ui/src/messages/messages.ts` itself. That catalog owns every string the baseline holds as
 * a copybook constant or a program literal, and its own file overview excludes "the static text
 * PAINTED BY THE BMS MAPS", assigning `app/bms/*.bms` to `ui/src/screens/**`. So the five sentences
 * this screen can move into its message field are imported from the catalog, while the sub-title, the
 * twelve panel labels, the eight column headings, the row-22 prompt and the row-24 key legend are
 * transcribed here from `COPAU00.bms` with the line that declares each one. Adding the painted text
 * to the catalog would breach the boundary that catalog documents; inlining the program literals here
 * would breach Transformation Rule T8's single owner for them.
 *
 * The three regions are `Descriptions` plus `Table`, not one list
 * -------------------------------------------------------------
 * Assumptions: this mapset genuinely paints a detail panel AND a paged list, so the screen composes
 * both primitives. Rows 5 to 12 carry the search entry and fourteen account fields at fifteen named
 * positions; rows 14 to 20 carry a heading row, a rule row and five row-families of eight fields
 * each. Treating the route as a bare list -- which its name invites -- would drop the entire upper
 * region, which is over half the painted screen and every figure an operator reviews an
 * authorization against.
 *
 * Layout deviates from the character grid on purpose
 * -------------------------------------------------
 * Trade-offs: `DFHMDI SIZE=(24,80)` at `COPAU00.bms` L26 to L28 positions all 104 fields absolutely,
 * and none of that positioning is reproduced. This is design gap **G1**, carried in
 * {@link DESIGN_GAPS} rather than restated here: field grouping, reading order and tab order are
 * preserved, pixel-for-character placement is not. The cost is that no measurement of this screen
 * against a 3270 emulator will match; what is bought is a layout that reflows and that a screen
 * reader can traverse, neither of which absolute character positions permit. All layout therefore
 * goes through `Descriptions`, `Table`, `Flex` and `Space`, and no raw element carries bespoke
 * geometry.
 *
 * The title band is the shell's, and this screen now says so executably
 * --------------------------------------------------------------------
 * Refactoring Rationale: the two `PIC X(40)` title constants painted on rows 1 and 2 are deliberately
 * not rendered here, and until this revision that statement was prose with nothing behind it. No
 * screen in the tree called `useShellSlot`, so the band this screen documented as belonging to the
 * shell was painted by nobody and rows 1 and 2 were simply blank. The screen now DELEGATES its
 * identity -- {@link AUTH_SUMMARY_TRANSACTION_ID} and {@link AUTH_SUMMARY_PROGRAM_NAME} -- together
 * with a server-derived paint instant, so `ui/src/layout/AppShell.tsx` renders the band above the
 * outlet with this screen's own `Tran:` and `Prog:` values in it.
 *
 * ⚠️ Refactoring Rationale: ALL THREE persistent zones are delegated, where this said only the
 * header was. The screen delegated its message and its legend in the same revision that mounted the
 * shell -- the `useShellSlot` call passes `screen`, `now`, `message` and `pfKeys`, and the body's last
 * element is the mapset's row-22 selection prompt -- but this paragraph kept the earlier arrangement's
 * reasoning, so a reader was told to expect two locally composed bands that are not there. The width
 * and the legend colour it argued from are both carried across the delegation: the band receives this
 * mapset's name so it applies the mapset's own width, and the legend takes the yellow this mapset paints
 * because that colour is the slot's default.
 *
 * Assumptions: nothing is composed twice. `useShellSlot` renders a zone only when it is delegated, so
 * the reason for withdrawing the two local bands was precisely that keeping them beside a mounted shell
 * would paint a second live region and a second legend.
 */

import { Descriptions, Flex, Form, Input, Table, Typography, theme } from 'antd';
import type { DescriptionsProps, InputRef, TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { listPendingAuthorizations } from '../../api/authorization';
import type {
  ApiError,
  FieldError,
  FieldValidationState,
  MatchStatus,
  PageResponse,
  PendingAuthListItem,
  PendingAuthListQuery,
  PendingAuthSummary,
} from '../../api/types';
import { useShellSlot } from '../../layout/AppShell';

import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyRejection } from '../../layout/usePfKeys';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import type { PageBoundary, PagedQueryRequest } from '../../hooks/usePagedQuery';
import { useServerInstant } from '../../hooks/useServerInstant';
import { MONEY_PICTURES, renderMoney } from '../../format/money';
import type { MoneyPicture, RenderedMoney } from '../../format/money';
import {
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
} from '../../messages/messages';
import {
  AUTHORIZATION_SUMMARY_ROUTE,
  MAIN_MENU_ROUTE,
  navigateSafely,
} from '../../routes/navigation';
import {
  claimRetainedOutcome,
  isApiRequestError,
  isTransientFailure,
  subscribeToRetainedOutcomes,
  withoutConcurrentDuplicate,
} from '../../api/client';
import type { FraudTransitionHandover } from '../authDetail';
import {
  BMS_TEXT_COLOR_TOKENS,
  DESIGN_GAPS,
  TARGET_SIZE_AA_MINIMUM,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';
import {
  RECORD_VIEW_COLUMNS,
  copybookFieldWidthStyle,
  monetaryRecordCellStyle,
} from '../../layout/recordLayout';
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  VISUALLY_HIDDEN_STYLE,
  busyAnnouncement,
  busyProps,
  fieldAriaProps,
  fieldErrorHelp,
  fieldRefusalRendering,
} from '../../layout/fieldHelp';
import { ScreenTitle } from '../../layout/ScreenTitle';

/**
 * The `var(--…)` reference form of the design tokens, as antd's theme hook publishes it.
 *
 * Assumptions: derived from the hook rather than written out, so the two builders below take exactly
 * what `theme.useToken()` yields and no separate declaration can drift from it. antd types this map
 * as `GlobalToken`, so a colour token indexes to a string and a weight token to a number, which is
 * why both are assigned straight into `CSSProperties` without a cast.
 */
type AntdCssVariables = ReturnType<typeof theme.useToken>['cssVar'];

/** Transaction identifier this screen answers to, `WS-CICS-TRANID` at `COPAUS0C.cbl` L36. */
export const AUTH_SUMMARY_TRANSACTION_ID = 'CPVS';

/** Baseline program this screen carries across, `WS-PGM-AUTH-SMRY` at `COPAUS0C.cbl` L33. */
export const AUTH_SUMMARY_PROGRAM_NAME = 'COPAUS0C';

/**
 * Mapset whose message-field width the band is sized to.
 *
 * Assumptions: this is the key `MESSAGE_BAND_BY_MAPSET` holds for `COPAU0A`, and passing it is what
 * reconciles the two widths this screen has to satisfy at once -- see the note at
 * {@link AUTH_SUMMARY_MESSAGE_SEVERITY}.
 */
export const AUTH_SUMMARY_MAPSET = 'COPAU00';

/**
 * Sub-title painted on row 3, `COPAU00.bms` L75 to L78, `LENGTH=19`, `COLOR=NEUTRAL`.
 *
 * Assumptions: this is the screen's OWN heading and is not one of the two `PIC X(40)` title-band
 * constants. Those are `CCDA-TITLE01` and `CCDA-TITLE02`, painted on rows 1 and 2 by `TITLE01` and
 * `TITLE02` (L38 and L61), which belong to the shared header band and are owned by the message
 * catalog. Rendering either here would duplicate the band.
 */
export const AUTH_SUMMARY_SUBTITLE = 'View Authorizations';

/**
 * The painted labels, verbatim from the `INITIAL=` operand that declares each one.
 *
 * Assumptions: transcribed character for character including the trailing colons and the interior
 * spacing, because Transformation Rule T8 carries user-visible strings across unchanged and two of
 * these differ from each other in exactly that spacing. `approvalCount` is `'Approval # : '` with a
 * space BEFORE its colon (L131) while `declineCount` is `'Decline #:'` with none (L138); they sit
 * side by side on row 9, so the difference is visible and is the baseline's own.
 */
export const AUTH_SUMMARY_LABELS = {
  /** `COPAU00.bms` L79 to L83, `LENGTH=15`, `COLOR=TURQUOISE`. */
  searchAccountId: 'Search Acct Id:',
  /** `COPAU00.bms` L92 to L95, `LENGTH=6`, `COLOR=DEFAULT`. */
  name: 'Name: ',
  /** `COPAU00.bms` L100 to L102, `LENGTH=13`. */
  customerId: 'Customer Id: ',
  /** `COPAU00.bms` L111 to L113, `LENGTH=13`. */
  accountStatus: 'Acct Status: ',
  /** `COPAU00.bms` L122 to L124, `LENGTH=3`. */
  phone: 'PH:',
  /** `COPAU00.bms` L129 to L131, `LENGTH=13`. */
  approvalCount: 'Approval # : ',
  /** `COPAU00.bms` L136 to L138, `LENGTH=10`. */
  declineCount: 'Decline #:',
  /** `COPAU00.bms` L143 to L146, `LENGTH=11`, `COLOR=DEFAULT`. */
  creditLimit: 'Credit Lim:',
  /** `COPAU00.bms` L152 to L155, `LENGTH=9`, `COLOR=DEFAULT`. */
  cashLimit: 'Cash Lim:',
  /** `COPAU00.bms` L161 to L164, `LENGTH=9`, `COLOR=DEFAULT`. */
  approvedAmount: 'Appr Amt:',
  /** `COPAU00.bms` L170 to L173, `LENGTH=11`, `COLOR=DEFAULT`. */
  creditBalance: 'Credit Bal:',
  /** `COPAU00.bms` L179 to L182, `LENGTH=9`, `COLOR=DEFAULT`. */
  cashBalance: 'Cash Bal:',
  /** `COPAU00.bms` L188 to L191, `LENGTH=9`, `COLOR=DEFAULT`. */
  declinedAmount: 'Decl Amt:',
} as const;

/*
 * WHY : Assumptions: these two names are held SEPARATELY from the catalog above and not added to it,
 *       because that catalog is a transcription -- every member carries the mapset line its literal comes
 *       from -- and these two literals appear in no mapset. They are accessible names for the two fields
 *       the mapset labels with nothing, they never reach the screen, and mixing them into a transcription
 *       would make the next reader unable to tell which of its members the source actually paints.
 */

/*
 * WHY : Assumptions: the value is `'max-content'` and not a pixel figure, and it is declared once here so
 *       the table's narrow-screen behaviour is stated in one place rather than inline among its props.
 *       `max-content` asks the layout for the width the eight fixed-width columns actually need, which is
 *       the only figure that cannot drift from the column definitions; the full reasoning, including the
 *       stacked-representation alternative that was rejected, is recorded at the render site.
 */

/** Horizontal scroll policy for the eight-column authorization table. */
export const AUTH_SUMMARY_TABLE_SCROLL = { x: 'max-content' } as const;

/**
 * Accessible names for the values the mapset paints with no label of their own.
 *
 * ⚠️ Refactoring Rationale: the five account-status slots and the authorization-status flag were added
 * to this map because all six now render inside ONE labelled cell, and a run of six one- and
 * two-character codes under a single caption is unreadable to an assistive technology without a name
 * per code. The mapset labels none of them individually -- it paints one `ACCSTAT` position -- so each
 * name is composed from the segment's own field data name and the ordinal the source itself numbers the
 * slot by, exactly as the two address names are. None of them reaches the screen.
 */
export const AUTH_SUMMARY_HIDDEN_LABELS = {
  /** Names `ADDR001`, `COPAU00.bms` L107 to L110. */
  addressLine1: 'Address line 1',
  /** Names `ADDR002`, `COPAU00.bms` L118 to L121. */
  addressLine2: 'Address line 2',
  /** Names `PA-AUTH-STATUS`, `CIPAUSMY.cpy` L21. */
  authStatus: 'Authorization status',
  /** Names `PA-ACCOUNT-STATUS(1)`, `CIPAUSMY.cpy` L22. */
  accountStatus1: 'Account status 1',
  /** Names `PA-ACCOUNT-STATUS(2)`, `CIPAUSMY.cpy` L22. */
  accountStatus2: 'Account status 2',
  /** Names `PA-ACCOUNT-STATUS(3)`, `CIPAUSMY.cpy` L22. */
  accountStatus3: 'Account status 3',
  /** Names `PA-ACCOUNT-STATUS(4)`, `CIPAUSMY.cpy` L22. */
  accountStatus4: 'Account status 4',
  /** Names `PA-ACCOUNT-STATUS(5)`, `CIPAUSMY.cpy` L22. */
  accountStatus5: 'Account status 5',
} as const;

/**
 * The eight column headings painted on row 14, verbatim including their padding spaces.
 *
 * Trade-offs: the leading and trailing spaces are KEPT rather than trimmed, and that is a decision
 * about which contract wins where the two disagree. Each heading is declared at a `LENGTH` that
 * includes its padding -- `' Transaction ID '` is sixteen characters at L204 to L206 and
 * `'   Amount   '` is twelve at L234 to L236 -- so the padding is part of the declared literal under
 * Rule T8. The cost is that the strings are not what a reader would type; the benefit is that a test
 * asserting the declared literal byte for byte finds it, and HTML collapses the padding on render so
 * the visible result is identical either way.
 */
export const AUTH_SUMMARY_COLUMN_HEADERS = {
  /** `COPAU00.bms` L197 to L201, `LENGTH=3`. */
  selection: 'Sel',
  /** `COPAU00.bms` L202 to L206, `LENGTH=16`. */
  transactionId: ' Transaction ID ',
  /** `COPAU00.bms` L207 to L211, `LENGTH=8`. */
  date: '  Date  ',
  /** `COPAU00.bms` L212 to L216, `LENGTH=8`. */
  time: '  Time  ',
  /** `COPAU00.bms` L217 to L221, `LENGTH=5`. */
  type: 'Type ',
  /** `COPAU00.bms` L222 to L226, `LENGTH=3`. */
  approval: 'A/D',
  /** `COPAU00.bms` L227 to L231, `LENGTH=3`. */
  status: 'STS',
  /** `COPAU00.bms` L232 to L236, `LENGTH=12`. */
  amount: '   Amount   ',
} as const;

/**
 * The row-22 instruction, `COPAU00.bms` L497 to L502, `LENGTH=52`, `ATTRB=(ASKIP,BRT)`.
 *
 * Assumptions: the source writes this literal as `'Type ''S'' to View Authorization details from the
 * list'`, and the doubled apostrophes are COBOL's escape for a single one inside an
 * apostrophe-delimited literal -- not part of the text. The rendered sentence therefore carries one
 * apostrophe on each side of the S and is 52 characters, matching the declared `LENGTH`. Re-escaping
 * it here would render four apostrophes and overrun that width by two.
 */
export const AUTH_SUMMARY_SELECTION_PROMPT = "Type 'S' to View Authorization details from the list";

/**
 * The four key labels painted on row 24, split from one 48-character legend.
 *
 * Assumptions: `COPAU00.bms` L507 to L512 paints `'ENTER=Continue  F3=Back  F7=Backward
 * F8=Forward'` as a single `LENGTH=48` field with two spaces between each pair, and `PfKeyBar`
 * renders one control per key, so the legend is carried as its four constituent labels. The two
 * uniform ones are taken from `UNIFORM_PF_KEY_LABELS` rather than re-typed, because that module owns
 * the spelling every mapset shares and a second copy here could drift from it; both were checked
 * against this mapset's own legend before being reused.
 *
 * Assumptions: exactly four keys, because `COPAUS0C.cbl` L224 to L250 evaluates exactly four
 * attention identifiers -- `DFHENTER`, `DFHPF3`, `DFHPF7` and `DFHPF8`. PF4, PF5 and PF12 reach the
 * `WHEN OTHER` arm on this screen and are refused, so binding one would add an action the source
 * does not have.
 */
export const AUTH_SUMMARY_KEY_LABELS = {
  ENTER: 'ENTER=Continue',
  PFK03: 'F3=Back',
  PFK07: UNIFORM_PF_KEY_LABELS.PFK07,
  PFK08: UNIFORM_PF_KEY_LABELS.PFK08,
} as const satisfies Readonly<{
  /*
   * Assumptions: the constraint names the four LITERALS rather than `string`, which turns the reuse
   * above into a checked claim instead of a hopeful one. Both shared labels are byte-identical to this
   * mapset's own legend today, and the whole reason to import them is that the spelling is shared -- so
   * if `UNIFORM_PF_KEY_LABELS` ever changes one, this declaration stops compiling and the divergence
   * from `COPAU00.bms` L507 to L512 surfaces at the build rather than on the screen.
   */
  ENTER: 'ENTER=Continue';
  PFK03: 'F3=Back';
  PFK07: 'F7=Backward';
  PFK08: 'F8=Forward';
}>;

/**
 * Declared character widths of the fields whose width is part of their contract.
 *
 * Assumptions: the three monetary widths are RECORDED distinctly at 12, 9 and 10 because that is what
 * the mapset declares, and the record is worth keeping even where the rendering no longer uses it. The
 * segment declares every one of `PA-CREDIT-LIMIT`, `PA-CASH-LIMIT`, `PA-CREDIT-BALANCE`,
 * `PA-CASH-BALANCE`, `PA-APPROVED-AUTH-AMT` and `PA-DECLINED-AUTH-AMT` as `PIC S9(09)V99 COMP-3`
 * (`CIPAUSMY.cpy` L23 to L26 and L29 to L30), so the differing widths cannot be precision -- they are
 * the map's presentation contract, and the program proves it by editing the same precision through two
 * different masks: `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` renders twelve characters into `CREDLIM` and
 * `CREDBAL` while `WS-DISPLAY-AMT9 PIC -zzzz9.99` renders nine into `CASHLIM`, `CASHBAL`, `APPRAMT`
 * and `DECLAMT` (`COPAUS0C.cbl` L56 to L57, applied at L780 to L799).
 *
 * ⚠️ Refactoring Rationale: the three monetary widths no longer SIZE anything, and the reversal is
 * recorded here rather than left to be discovered. This entry used to close by warning that collapsing
 * them to one "would silently re-lay out four of the six columns", and that warning turned on the word
 * silently. Every amount now renders through one declared picture twelve characters wide -- see
 * {@link AUTH_SUMMARY_MONEY_PICTURE} for why that picture and no other -- so a nine-character box would
 * either grow to hold a twelve-character value, making the declaration inert, or clip a figure. One
 * accurate measure replaces three that each fit some cells and not others, the re-layout is stated in
 * two places instead of being silent, and the declared widths stay here as the contract they are.
 */
export const AUTH_SUMMARY_FIELD_WIDTHS = {
  /** `ACCTID`, `COPAU00.bms` L84 to L88; `ACCTIDI PIC X(11)` in the symbolic map. */
  accountId: 11,
  /** `CNAME`, L96 to L99. */
  customerName: 25,
  /** `CUSTID`, L103 to L106. */
  customerId: 9,
  /** `ADDR001` and `ADDR002`, L107 to L110 and L118 to L121. */
  addressLine: 25,
  /** `ACCSTAT`, L114 to L117. */
  accountStatus: 1,
  /** `PHONE1`, L125 to L128. */
  phoneNumber: 13,
  /** `APPRCNT` and `DECLCNT`, L132 to L135 and L139 to L142. */
  count: 3,
  /** `CREDLIM` and `CREDBAL`, L147 to L151 and L174 to L178. */
  moneyWide: 12,
  /** `CASHLIM` and `CASHBAL`, L156 to L160 and L183 to L187. */
  moneyNarrow: 9,
  /** `APPRAMT` and `DECLAMT`, L165 to L169 and L192 to L196. */
  moneyMedium: 10,
  /** `SEL0001` to `SEL0005`, L277, L321, L365, L409, L488. */
  selection: 1,
  /** `TRNID01` to `TRNID05`, carrying `PA-TRANSACTION-ID PIC X(15)`. */
  rowTransactionId: 16,
  /** `PDATE01` to `PDATE05`, rendered `mm/dd/yy` by `COPAUS0C.cbl` L531 to L534. */
  rowDate: 8,
  /** `PTIME01` to `PTIME05`, rendered `hh:mm:ss` by `COPAUS0C.cbl` L527 to L529. */
  rowTime: 8,
  /** `PTYPE01` to `PTYPE05`, carrying `PA-AUTH-TYPE PIC X(04)`. */
  rowType: 4,
  /** `PAPRV01` to `PAPRV05`, carrying the derived `A`/`D` of `COPAUS0C.cbl` L536 to L540. */
  rowApproval: 1,
  /** `PSTAT01` to `PSTAT05`, carrying `PA-MATCH-STATUS PIC X(01)`. */
  rowStatus: 1,
  /** `PAMT001` to `PAMT005`, holding the twelve-character edit of `PA-APPROVED-AMT`. */
  rowAmount: 12,
} as const;

/**
 * Rows one page of authorizations holds.
 *
 * Assumptions: five, corroborated three independent ways in the baseline rather than chosen. The
 * mapset paints five row-families `SEL0001` through `SEL0005` on rows 16 to 20; the commarea
 * extension declares `CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES` at `COPAUS0C.cbl` L126, so
 * exactly five row keys survive a turn; and `PROCESS-PAGE-FORWARD` loops `UNTIL WS-IDX > 5` at L424.
 * A fourth check confirms nothing else on the screen is selectable: the mapset declares exactly six
 * `ATTRB=(FSET,NORM,UNPROT)` fields and they are the account entry plus those five selectors.
 */
export const AUTH_SUMMARY_PAGE_SIZE = 5;

/**
 * The selection character the source accepts, `COPAUS0C.cbl` L314.
 *
 * Assumptions: the uppercase form is the canonical one because the source tests it first, and L315
 * adds a second `WHEN 's'` arm for the lowercase. {@link resolveSelectionAction} therefore accepts
 * both, and this constant is what the selection control supplies.
 */
export const AUTH_SUMMARY_SELECTION_CODE = 'S';

/**
 * The empty set of selection-cell entries, which is the state every painted page starts in.
 *
 * Assumptions: a frozen module-level constant rather than a fresh object literal at each of the three
 * clearing sites, so the three cannot come to disagree about what "nothing selected" is, and so React
 * is handed the same reference each time -- clearing an already-clear page then costs no render.
 */
const NO_SELECTION_ENTRIES: Readonly<Record<string, string>> = Object.freeze({});

/**
 * Character columns a selection cell RESERVES, which is one more than it admits.
 *
 * ⚠️ Purpose: keep the typed character visible. A browser measurement of the IDENTICAL control on the
 * user browse -- `ui/src/screens/userList/index.tsx` renders the same one-character `SEL` cell -- found
 * it twenty-four pixels wide with a `clientWidth` of twenty-two and the design system's own eleven-pixel
 * padding on each side, giving a CONTENT BOX of zero pixels against a measured glyph advance of 9.078
 * pixels for the character the operator is instructed to type. The value was genuinely stored and the
 * caret genuinely at position one; the operator saw nothing. Reserving the character's column alone does
 * not fix it either -- one column plus that padding leaves about eight pixels against a 9.078-pixel
 * glyph -- so the reservation is the character AND its caret.
 *
 * ⚠️ Assumptions: the extra column is for the CARET specifically. A 3270 cursor was a block occupying
 * the character cell itself, so one declared column was all the terminal ever needed; a browser draws
 * its caret BETWEEN character positions, so a content box of exactly one column leaves caret and glyph
 * competing for the same space and the glyph scrolls out of view.
 *
 * Assumptions: this is a DISPLAY reservation and changes nothing about what the field ACCEPTS.
 * `AUTH_SUMMARY_FIELD_WIDTHS.selection` remains the `maxLength`, so a second character still cannot be
 * typed, and the two figures are kept apart precisely so a reader cannot mistake the reservation for a
 * relaxation of the mapset's declared `LENGTH=1`.
 */
export const SELECTION_CELL_RESERVED_COLUMNS = AUTH_SUMMARY_FIELD_WIDTHS.selection + 1;

/**
 * The ordinal the paging hook reports while the FIRST page of a set is on display.
 *
 * Assumptions: declared here rather than imported because `ui/src/hooks/usePagedQuery.ts` keeps its own
 * `FIRST_PAGE_NUMBER` module-private (L128) and publishes the ordinal itself on
 * `UsePagedQueryResult.pageNumber`. One is the hook's documented starting ordinal, and the value is
 * named here so the resubmission guard below reads as a statement about the page on display rather
 * than as a comparison against a bare literal.
 *
 * ⚠️ Assumptions: the hook initialises the ordinal to this value BEFORE any read has settled (L922), so
 * this test alone cannot distinguish "showing page one" from "has never read". The guard therefore pairs
 * it with the screen's own answered fact, which is the arrived summary.
 */
const AUTH_SUMMARY_FIRST_PAGE = 1;

/**
 * Composes the key one account search is collapsed under.
 *
 * Purpose: ⚠️ browser validation counted three activations of the Enter control on an UNCHANGED account
 * identifier taking `POST /api/v1/authorizations/search` from one request to four, and three further
 * activations dispatched inside a single millisecond taking it to seven -- one identical request per
 * press, with no coalescing of any kind on this screen's own dispatch. This is the key that collapses
 * them.
 *
 * Assumptions: the key is the METHOD AND TARGET plus the account, which is the composition
 * `withoutConcurrentDuplicate` documents for itself in `ui/src/api/client.ts` -- so two searches of the
 * same account collapse and two searches of different accounts do not. Keeping the account IN the key is
 * what preserves the correction path: an operator who mistyped an identifier and retypes it while the
 * first read is outstanding composes a different key and is not made to wait for a read they no longer
 * want.
 *
 * Assumptions: this key cannot collide with the paging hook's own. That hook composes
 * `BROWSE {identity}.{epoch} {direction} {cursor}` behind the prefix at `usePagedQuery.ts` L139, and
 * every key here begins with the HTTP method, so the two namespaces are disjoint by construction rather
 * than by coincidence.
 * @param {string} accountId - The account identifier the search is scoped to.
 * @returns {string} The key this screen's search dispatch is collapsed under.
 */
export function authSummarySearchKey(accountId: string): string {
  return `POST ${AUTHORIZATION_SUMMARY_ROUTE}#search ${accountId}`;
}

/**
 * Absorbs a settled browse turn, whose outcome is observed through the browse itself.
 *
 * Assumptions: a named no-op rather than a `void` discard, because `ui/eslint.config.js` sets
 * `no-floating-promises` with `ignoreVoid: false` -- so the three dispatch sites below must supply
 * handlers, and the same shape is what `usePagedQuery.ts` L1479 uses for its own opening read. Making
 * the calling handlers `async` is not available either: they are void-returning event handlers, which
 * `no-misused-promises` with `checksVoidReturn` refuses.
 *
 * Assumptions: BOTH handlers are supplied at every call site. The turn's outcome -- page, boundary,
 * failure -- reaches this screen through the browse's own members, and the hook's contract states that
 * its promise never rejects, so there is nothing here to do with either settlement. Passing this as the
 * rejection handler as well is what stops a later change inside the hook turning these sites into
 * unhandled rejections silently.
 * @returns {void} Nothing; the turn's outcome is read from the browse.
 */
function ignoreSettledBrowseTurn(): void {
  /*
   * Assumptions: deliberately empty, and empty is the whole implementation. See the block above for why
   * the handler exists at all; putting a log line here would report every settled page turn on every
   * paged screen, which is noise rather than a monitoring hook.
   */
}

/*
 * WHY : ⚠️ Refactoring Rationale: a row's control is named for the ACTION it performs, where it used to be
 *       named `'S <transaction id>'` -- the selection character followed by the identifier. That name was
 *       the terminal's INPUT, not a description: on the 3270 an operator types `S` into the selector, so
 *       `S` is what the field would contain, and a control announced as "S 0000000123456789" tells a
 *       screen-reader user the letter to type into a field that does not exist in a browser while saying
 *       nothing about what choosing the row does. The selection character remains what the screen sends
 *       for the turn -- `resolveSelectionAction` still tests it -- and it is no longer what the control
 *       is called.
 */

/**
 * Builds the accessible name of one row's selection cell.
 *
 * ⚠️ Refactoring Rationale: the name is the COLUMN HEADING paired with the row's identifier, where it
 * used to be the action-oriented `Select authorization <id>`. The note above records why the
 * action-oriented form replaced the terminal's literal `'S <id>'`, and its reasoning turned on one
 * premise: that the letter `S` names "a field that does not exist in a browser". That premise is no
 * longer true. The field exists -- `SEL0001` is `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1` at
 * `COPAU00.bms` L277 to L282, an unprotected one-character entry, and the screen now renders it as one
 * -- so the control's name has to say WHICH field it is, exactly as `ui/src/screens/userList/index.tsx`
 * names its own `SEL` cells `Sel <userId>`. What the field is FOR is carried by the mapset's own row-22
 * sentence, which the shell paints on every turn, so the name does not have to carry it too.
 *
 * Assumptions: the heading is trimmed. It is stored padded because the mapset declares it that way and
 * Rule T8 keeps the declared literal intact, but an accessible name is read aloud rather than laid out
 * on a character grid, and leading blanks in a name are noise.
 * @param {string} transactionId - The acquirer's transaction identifier, which names the row.
 * @returns {string} The name of that row's selection cell.
 */
export function selectionCellLabel(transactionId: string): string {
  return `${AUTH_SUMMARY_COLUMN_HEADERS.selection.trim()} ${transactionId}`;
}

/**
 * Builds the identifier of one row's selection cell.
 *
 * ⚠️ Purpose: this exists because the browser said the cells had no identity. DevTools reported "A form
 * field element should have an id or name attribute" against exactly five nodes on this screen, and five
 * is the number of rows a page of this listing paints -- these cells were the only form controls in the
 * application carrying neither. The two sibling browse screens already publish one per row:
 * `ui/src/screens/userList/index.tsx` composes `user-list-action-<userId>` and
 * `ui/src/screens/refTypeList/index.tsx` composes `ref-type-list-action-<typeCd>`, and neither appears in
 * the browser's report. This screen was the outlier rather than the pattern.
 *
 * Assumptions: the transaction identifier is what distinguishes the rows, so it is what distinguishes
 * the cells. It is the listing's own key -- the same value `selectionCellLabel` puts in the accessible
 * name and the same value the chosen row's detail path carries -- so two cells on one page cannot
 * collide, and the identifier of a given row's cell is stable across a re-render.
 *
 * Trade-offs: an identifier is published even though nothing in this screen looks the cell up by one
 * today. That is accepted because the value of having it is not internal: an identifier is what lets the
 * platform treat the control as a real named field, and the two sibling screens have already paid the
 * same small cost for the same reason.
 * @param {string} transactionId - The acquirer's transaction identifier, which names the row.
 * @returns {string} A control identifier unique to that row's cell.
 */
export function selectionCellId(transactionId: string): string {
  return `auth-summary-action-${transactionId}`;
}

/**
 * The row a page's selection cells name, and the character typed beside it.
 *
 * Assumptions: two members and no more. The source carries exactly this pair across the turn --
 * `CDEMO-CPVS-PAU-SEL-FLG` holds the character and `CDEMO-CPVS-PAU-SELECTED` holds the selected key
 * (`COPAUS0C.cbl` L290 to L308) -- and it evaluates the character only once both are non-blank at
 * L311. Keeping them together is what lets that guard be one test here as well.
 */
export interface AuthRowSelection {
  /** The sealed selector of the winning row, or `null` when no cell carries an entry. */
  readonly key: string | null;
  /** The character typed beside that row, or the empty string when none was. */
  readonly flag: string;
}

/**
 * Reduces a page's selection cells to the single selection the source's ordered evaluation expresses.
 *
 * ⚠️ Purpose: the FIRST cell carrying an entry, in display order, wins, and every later entry is
 * IGNORED without being reported. `PROCESS-ENTER-KEY` is one `EVALUATE TRUE` whose five arms test
 * `SEL0001I` through `SEL0005I` in that order (`COPAUS0C.cbl` L288 to L308); COBOL ends an `EVALUATE`
 * at its first matching arm, so a page carrying entries beside rows two and four acts on row two and
 * never inspects row four.
 *
 * ⚠️ Assumptions: this is deliberately NOT the multi-selection refusal the transaction-type browse
 * uses. `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` counts its marked rows and answers `'Please
 * select only 1 action'` for more than one; `COPAUS0C` keeps no such count and declares no such
 * sentence, so refusing a second entry here would invent a message the reference cannot emit and would
 * add a refusal where it silently proceeds.
 *
 * ⚠️ Assumptions: a blank cell is not a selection, and the entry is TRIMMED before the test. Every arm
 * reads `NOT = SPACES AND LOW-VALUES`, so a cell holding a space is skipped exactly as an untouched one
 * is -- and a browser control can hold a typed space where the terminal's field held its `INITIAL=' '`.
 *
 * ⚠️ Assumptions: the character is returned AS TYPED rather than coerced to the accepted one. The
 * source moves whatever the operator typed into the flag and only then evaluates it, answering
 * `'Invalid selection. Valid value is S'` for anything but `S` or `s` (L315 to L330). Coercing here
 * would make that arm unreachable, which is precisely what the radio control this replaced did.
 * @param {readonly PendingAuthListItem[]} rows - The page's rows, in the order they are displayed.
 * @param {Readonly<Record<string, string>>} entries - Cell entries, keyed by the row's sealed selector.
 * @returns {AuthRowSelection} The winning row and its character, or `null` and the empty string when no
 *   cell carries an entry.
 */
export function reduceAuthRowSelection(
  rows: readonly PendingAuthListItem[],
  entries: Readonly<Record<string, string>>,
): AuthRowSelection {
  for (const row of rows) {
    const typed = (entries[row.key] ?? '').trim();
    if (typed !== '') {
      return { key: row.key, flag: typed };
    }
  }
  return { key: null, flag: '' };
}

/**
 * Route the back key returns to.
 *
 * Assumptions: the main menu, because `COPAUS0C.cbl` L235 to L238 moves `WS-PGM-MENU` -- declared
 * `'COMEN01C'` at L35 -- into the next-program field before transferring. `RETURN-TO-PREV-SCREEN` at
 * L664 to L677 substitutes `'COSGN00C'` only when that field is blank, which cannot occur on this arm
 * because the same arm sets it.
 *
 * ⚠️ Refactoring Rationale: the path is now the routing tree's own `MAIN_MENU_ROUTE` rather than a second
 * `'/menu'` literal, and the sentence that used to stand here -- that the shared module "is not among
 * this screen's declared dependencies" -- is gone with the duplicate navigation helper it justified. One
 * literal in two modules is one rename away from a screen whose back key reaches a route that no longer
 * exists, and the failure would be invisible until an operator pressed PF3.
 */
export const AUTH_SUMMARY_BACK_ROUTE: typeof MAIN_MENU_ROUTE = MAIN_MENU_ROUTE;

/**
 * Severity every sentence on this screen is shown at.
 *
 * Assumptions: always `error`, and constant rather than derived, because this mapset can express
 * nothing else. It paints exactly ONE message field -- `ERRMSG` at `COPAU00.bms` L503 to L506 --
 * declared `COLOR=RED` with `ATTRB=(ASKIP,BRT,FSET)`, and `SEND-PAULST-SCREEN` moves `WS-MESSAGE`
 * into it unconditionally at `COPAUS0C.cbl` L692. Both boundary sentences and both validation
 * refusals reach the operator through that one red field, so a severity distinction here would be an
 * invention. The mapset reinforces it: `DFHMSD CTRL=(ALARM,FREEKB)` at L19 sounds the terminal alarm
 * on every send, which is what makes the band's `alert` role faithful rather than alarming.
 *
 * Trade-offs: this screen's message field is 78 characters wide (`ERRMSGI PIC X(78)` in the symbolic
 * map, `LENGTH=78` at L505) while the shared work area `CCARD-ERROR-MSG` is 75
 * (`MESSAGE_BAND.workAreaWidth`, from `app/cpy/CVCRD01Y.cpy` L28 to L30). Both are honoured rather
 * than one being chosen: {@link AUTH_SUMMARY_MAPSET} is passed to the band, which sizes the rendered
 * field to this mapset's own 78 through `MESSAGE_BAND_BY_MAPSET`, while every sentence put into it
 * comes from the catalog and is inside the 75-character work area. Nothing is truncated, and nothing
 * is composed here that could exceed either -- see {@link describeListingFailure} for why the one
 * family of sentences long enough to overrun 75 is not rendered at all.
 */
export const AUTH_SUMMARY_MESSAGE_SEVERITY: MessageBandSeverity = 'error';

/**
 * The registered design deviation this screen's layout is governed by.
 *
 * Assumptions: taken from the theme bridge's own register rather than restated as prose here, so the
 * description, the measured field count and the agreed resolution have exactly one home and this
 * screen cites it instead of paraphrasing it. The annotation pins the identifier: the register is a
 * readonly tuple, so this declaration stops compiling if its first entry ceases to be **G1**, which is
 * what keeps the citation from silently pointing at a different gap.
 */
export const AUTH_SUMMARY_LAYOUT_GAP: { readonly id: 'G1'; readonly resolution: string } =
  DESIGN_GAPS[0];

/** Identifier the account-entry control is named by, for the label association below. */
const ACCOUNT_ID_LABEL_ID = 'auth-summary-account-id-label';

/** Identifier of the account-entry control itself, so its label can name it. */
const ACCOUNT_ID_INPUT_ID = 'auth-summary-account-id';

/*
 * WHY : ⚠️ Refactoring Rationale: the pattern requires exactly the declared field width, where it used to
 *       accept one digit or more. The count is spelled from `AUTH_SUMMARY_FIELD_WIDTHS.accountId` rather
 *       than written as `{11}`, so the local test and the control's `maxLength` cannot come to disagree
 *       about the width both take from `ACCTIDI PIC X(11)`. It is built once at module scope rather than
 *       per keystroke because a `RegExp` constructed from a template is compiled on every construction.
 * WHY : Assumptions: an exact width is COBOL's `IS NUMERIC` on this field, not a stricter rule added on
 *       top of it. The terminal delivers the field space-padded to eleven, so anything shorter carries
 *       spaces into the numeric test and fails it there; the reasoning is recorded in full at
 *       `classifyAccountIdEntry`.
 */

/** Matches an entry of exactly the declared width, all digits — COBOL's `IS NUMERIC` on the padded field. */
const ELEVEN_DIGITS = new RegExp(`^[0-9]{${String(AUTH_SUMMARY_FIELD_WIDTHS.accountId)}}$`, 'u');

/**
 * Builds the detail route for one authorization.
 *
 * Assumptions: `key` is the sealed selector the listing put on that row and it is passed through
 * verbatim -- never parsed, split or reassembled. It stands for a composite row key: `CIPAUDTY.cpy`
 * L19 declares `PA-AUTHORIZATION-KEY` as a group of two stored components, which the target holds as
 * two integer columns, so any structure a caller inferred from the token's bytes would be inference
 * about an encoding this screen does not own. It is the target of the baseline's
 * `CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES` at `COPAUS0C.cbl` L126.
 * @param {string} key - The row's opaque sealed selector, taken from a listing row.
 * @returns {string} The path of that authorization's detail screen.
 */
export function authorizationDetailPath(key: string): string {
  return `/authorizations/${encodeURIComponent(key)}`;
}

/*
 * WHY : ⚠️ Refactoring Rationale: both route changes on this screen now go through `navigateSafely` from
 *       `ui/src/routes/navigation.ts`, where a local `navigateAssured` used to hold a byte-for-byte copy
 *       of the same policy -- take the router's returned promise and, if it rejects because a transition
 *       was interrupted or blocked, fall back to a document-level navigation the router cannot interrupt.
 *       The copy's own comment recorded the duplication and declined to remove it on the ground that the
 *       shared module was not among this screen's declared dependencies; that ground was wrong, because
 *       a route change is not this screen's concern to own and the routing tree already publishes the
 *       decision. Two copies of a fallback policy is two places for it to drift, and the drift would show
 *       up as a key press that appears dead on one screen and works on another.
 * WHY : Assumptions: the shared seam is behaviourally identical for the two call sites here, which is
 *       what makes the swap safe rather than merely tidier. It widens the accepted destination from a
 *       string to the router's own `To`, and it resolves the fallback target from a `To` object's
 *       pathname -- both call sites below pass a string, so they take exactly the path the copy took.
 */

/**
 * One sentence bound for the message band, paired with the appearance it is shown in.
 *
 * Assumptions: the band is deliberately presentational -- its props are a nullable string and a
 * severity, and it accepts no problem document -- so reducing a failure to this pair is this screen's
 * work rather than the band's. That split is what keeps a response body from reaching a rendering
 * component.
 */
interface ScreenNotice {
  readonly message: string;
  readonly severity: MessageBandSeverity;
}

/**
 * Name the detail screen's fraud outcome is retained under, and this screen collects.
 *
 * ⚠️ Purpose: the detail screen's PF3 is not refused while a fraud write is outstanding, so a reviewer
 * who confirms a transition and returns here unmounts the screen that was going to report it. Its
 * continuation then hands the sentence over instead of painting it into a discarded component, and this
 * is the name it hands it over under.
 *
 * Assumptions: COMPOSED from the routing tree's own `AUTHORIZATION_SUMMARY_ROUTE` rather than written
 * out, and the value import from the detail module is deliberately NOT taken -- every screen is mounted
 * through `lazy()` in `ui/src/router.tsx`, so importing a constant from that module would fold its chunk
 * into this one. Only the TYPE crosses, which is erased at build time. This mirrors the arrangement
 * `ui/src/screens/userList/index.tsx` L463 already uses for the user-update hand-over, so the two pairs
 * of screens agree on one mechanism.
 */
const AUTH_FRAUD_TRANSITION_CLAIM = `${AUTHORIZATION_SUMMARY_ROUTE}#fraud`;

/**
 * Classifies the account entry against the source program's two refusals, in its order.
 *
 * Assumptions: the two tests are ORDERED and the first match wins, matching
 * `COPAUS0C.cbl` L264 to L281 exactly: blank is tested before numeric, so a blank entry is reported as
 * blank and never as non-numeric. The blank test accepts an all-space entry because the source tests
 * `= SPACES OR LOW-VALUES` against a fixed 11-byte field, where an operator who typed only spaces and
 * one who typed nothing arrive identically.
 *
 * ⚠️ Assumptions: the numeric test requires EXACTLY eleven digits, and the width is part of that one
 * test rather than a separate rule -- because it is part of the source's one test too. `IS NOT NUMERIC`
 * runs over `ACCTIDI PIC X(11)` as CICS delivers it, which is left-justified and SPACE-PADDED to eleven,
 * so a ten-digit entry arrives as ten digits and one space and fails the test. The terminal therefore
 * answers a short entry with `'Acct Id must be Numeric ...'`, exactly as it answers one carrying a letter.
 *
 * ⚠️ Refactoring Rationale: this used to accept ANY non-blank digit run, on the ground that "a browser
 * control delivers exactly what was typed with no padding, so the width contract is carried by
 * `maxLength` on the control instead". That reasoning is withdrawn: `maxLength` caps a control at eleven
 * characters and says nothing about ten, so a ten-digit entry passed the classification and was SENT --
 * where the source refuses it locally and reads nothing. The consequence was a round trip the reference
 * never makes, answered by whatever the service says about an identifier of the wrong width, in place of
 * the one verbatim sentence the operator should have seen. Padding the entry to eleven and re-testing
 * would be the literal transcription and is not used, because it would report a short entry through a
 * value the operator did not type; requiring the exact width states the same rule directly.
 *
 * Assumptions: the test runs over the supplied characters and never over a parsed number. Parsing would
 * additionally accept `1e3`, a sign and a decimal point, none of which is numeric to COBOL.
 * @param {string} entry - The account identifier as entered, unpadded.
 * @returns {FieldValidationState | null} `'BLANK'` for an empty or all-space entry, `'NOT_OK'` for one
 *   that is not exactly {@link AUTH_SUMMARY_FIELD_WIDTHS.accountId} digits, or `null` when the entry is
 *   usable.
 */
export function classifyAccountIdEntry(entry: string): FieldValidationState | null {
  if (entry.trim() === '') {
    return 'BLANK';
  }
  /*
   * WHY : Refactoring Rationale: the width test and the digit test are ONE pattern, {@link ELEVEN_DIGITS},
   *       rather than a digits-only pattern combined with a length comparison. Two revisions independently
   *       added the width requirement to a base that had only the digit test -- one by building the width
   *       into the pattern from `AUTH_SUMMARY_FIELD_WIDTHS.accountId`, one by comparing `entry.length`
   *       against the same constant -- and the merged file kept the second body beside the first's
   *       declaration, so it referenced a pattern that no longer existed. They are the same rule and the
   *       pattern is the form kept: it takes the declared width from the same constant, so neither
   *       spelling can drift from the field, and it leaves exactly one thing to read at the call site.
   */
  return ELEVEN_DIGITS.test(entry) ? null : 'NOT_OK';
}

/**
 * Selects the verbatim refusal for a classified account entry.
 *
 * Assumptions: both sentences come from the message catalog and neither is written here, because both
 * are program literals `MOVE`d into the message field -- `'Please enter Acct Id...'` at
 * `COPAUS0C.cbl` L269 and `'Acct Id must be Numeric ...'` at L278, the latter carrying a space before
 * its ellipsis that the catalog preserves.
 * @param {FieldValidationState} state - The classification {@link classifyAccountIdEntry} produced.
 * @returns {string} The verbatim sentence the source program shows for that state.
 */
export function accountIdRefusal(state: FieldValidationState): string {
  return state === 'BLANK'
    ? PROGRAM_MESSAGES.COPAUS0C.PLEASE_ENTER_ACCT_ID
    : PROGRAM_MESSAGES.COPAUS0C.ACCT_ID_MUST_BE_NUMERIC;
}

/**
 * What the source program does with a selection character once one is present.
 *
 * Assumptions: three outcomes and no more, because the source's `EVALUATE` has three reachable ends --
 * transfer to the detail program, the refusal sentence, and the surrounding `IF` not being entered at
 * all when either the flag or the key is blank (`COPAUS0C.cbl` L310 to L332).
 */
export type SelectionAction = 'open' | 'invalid' | 'none';

/**
 * Decides what an ENTER turn does about the row selection.
 *
 * Assumptions: BOTH the selection character and the row key must be present before either outcome is
 * reachable, which is the source's own compound guard at `COPAUS0C.cbl` L310 to L312 -- it requires
 * `CDEMO-CPVS-PAU-SEL-FLG` and `CDEMO-CPVS-PAU-SELECTED` to be neither spaces nor low-values. A
 * selection whose row has left the page therefore does nothing rather than opening the wrong record.
 *
 * Assumptions: `'S'` AND `'s'` are both accepted, which is two `WHEN` arms in the source rather than
 * one -- `WHEN 'S'` at `COPAUS0C.cbl` L314 followed immediately by `WHEN 's'` at L315, both falling
 * into the same transfer. Accepting only the uppercase form would refuse an entry the baseline
 * accepts, and the lowercase arm is easy to miss because it carries no body of its own.
 * @param {string} flag - The selection character standing against the chosen row.
 * @param {string | null} key - That row's sealed selector, or `null` when no row is chosen.
 * @returns {SelectionAction} `'open'` to reach the detail screen, `'invalid'` to refuse the character,
 *   or `'none'` when there is nothing to act on.
 */
export function resolveSelectionAction(flag: string, key: string | null): SelectionAction {
  if (flag.trim() === '' || key === null || key === '') {
    return 'none';
  }
  return flag === 'S' || flag === 's' ? 'open' : 'invalid';
}

/**
 * The status this operation does not declare, kept as a named value because it is reported as a fault.
 *
 * Assumptions: named rather than left as a bare literal at the branch, so the test below reads as the
 * contract statement it is: the listing has no not-found outcome, so this status can only mean the
 * request reached something other than the operation.
 */
const UNDECLARED_NOT_FOUND_STATUS = 404;

/**
 * Reduces a listing failure to the sentence the target shows for it.
 *
 * Trade-offs: the source composes ten diagnostics that end in a CICS response code or an IMS status
 * code, and NONE of them is reproduced -- `'Resp:'`, `'Reas:'` and `'Code:'` appear nowhere in this
 * screen. Those values have no target analogue to fabricate one from: `DFHRESP` is a CICS translator
 * value and `DIBSTAT` an IMS DL/I status, and the migrated service runs on neither. The withheld
 * detail is not merely dropped either -- `REDACTED_DIAGNOSTICS` in the message catalog registers all
 * ten sites (`COPAUS0C.cbl` L476, L509, L836, L851, L886, L901, L937, L952, L988 and L1022), names
 * the verbatim baseline sentence shown in place of each, and records that the suppressed value goes to
 * a server-side structured log keyed by the correlation identifier. So the compromise accepted is a
 * less specific sentence on screen in exchange for not disclosing an internal value to a browser and
 * not inventing a code; the specific detail remains recoverable, through `correlationId` on the
 * problem document.
 *
 * ⚠️ Refactoring Rationale: this takes the CLASSIFIED failure and no longer the bare problem document,
 * and the status FAMILY no longer selects the sentence. The arrangement it replaces sent every status
 * at or above 500 to the abend replacement, which made `502`, `503` and `504` -- the three service
 * statuses `TRANSIENT_STATUSES` in `ui/src/api/client.ts` L223 declares as conditions that may clear
 * on their own -- indistinguishable from a `500` that will not clear. An operator was told an outage
 * was an abend and given no reason to press Enter again. The judgement is now read off the failure
 * itself through `isTransientFailure`, which the client derives ONCE from the kind and that closed
 * status list, so this screen holds no second copy of it to drift from.
 *
 * Assumptions: the register's grounding is preserved rather than abandoned by that change, because the
 * service SENDS the register's replacement. `REDACTED_DIAGNOSTICS` maps `COPAUS0C.cbl` L476 and L509 to
 * `UNEXPECTED_ABEND_OCCURRED`, the service puts that sentence in `message`, and the verbatim arm below
 * renders it -- so the sentence an operator sees at those two sites is unchanged, and it arrives from
 * the one place authorised to author it instead of being substituted here on a status guess.
 *
 * Assumptions: reading `message` first is safe SPECIFICALLY because a failure no service described
 * carries none. `synthesisedProblem` in `ui/src/api/client.ts` sets `message: null` for every
 * `TIMEOUT`, every `NETWORK` failure and every response whose body was not a problem document -- which
 * is what a proxy's HTML `500` becomes, earning the `CARDDEMO-UI-BODY` code. So the verbatim arm can
 * only ever render a sentence a service authored, and a gateway's own text cannot reach the band
 * through it. The member is tested for being ABSENT as well as blank, because the contract declares it
 * nullable and testing only the trimmed length would dereference a null.
 *
 * ⚠️ Assumptions: a 404 is checked BEFORE the verbatim arm, and that order is the whole point of
 * keeping the branch. This operation DECLARES NO 404:
 * `services/authorization-service/src/main/resources/openapi/authorization-api.yaml` states on the 200
 * that an account with no summary row answers 200 with zero counts, an empty item array and absent
 * cursors, and that "This operation therefore has no 404" -- following the baseline, which RENDERS the
 * absence rather than reporting it, moving zero into all six aggregate positions at `COPAUS0C.cbl`
 * L800-L807 and skipping the browse at L354-L356. A 404 arriving anyway is a misroute, so whatever
 * answered is not this service and its body is a proxy's text rather than an authored operator
 * sentence; falling through to it would put words on the message band that nothing in this migration
 * wrote. It therefore takes the unexpected-condition sentence, and it is not mapped to
 * `ACCOUNT_ID_NOT_FOUND`, which would make a routing fault look like a business answer.
 *
 * Trade-offs: NO repeat control is painted, so `isRepeatableFailure` gates nothing here, and that is a
 * decision rather than an omission. It would answer false for every failure of this operation whatever
 * happened -- the listing is a `POST` and carries no `Idempotency-Key`, and `REPEATABLE_METHODS` at
 * `ui/src/api/client.ts` L235 admits only `get`, `head` and `options` -- so a control behind it would
 * never appear. The operator's repeat is the key this mapset already paints, `ENTER=Continue` at
 * `COPAU00.bms` L511, and the resubmission guard deliberately does NOT gate that key on the predicate
 * either: a `500` is neither transient nor repeatable, and gating would refuse a second press on
 * exactly the failure most likely to need one.
 * @param {unknown} failure - The classified failure the browse published, or `null` when nothing
 *   failed. Typed `unknown` rather than `ApiRequestError | null` for the reason
 *   {@link isApiRequestError} exists: a caught value's provenance is not guaranteed by its binding, and
 *   narrowing here is what lets the unclassified case have a sentence of its own.
 * @returns {ScreenNotice | null} The sentence and appearance to show, or `null` when there is no
 *   failure to report.
 */
export function describeListingFailure(failure: unknown): ScreenNotice | null {
  if (failure === null || failure === undefined) {
    return null;
  }
  /*
   * WHY : Assumptions: an unclassified value keeps the abend replacement, and it is the one arm that
   *       still uses it unconditionally. Something reached this screen as a failure and the transport
   *       module could not describe it, which is the closest target analogue of the baseline's own
   *       unexpected-condition arm; the two authored sentences below both describe a request that
   *       reached a classifier, so applying either to a value that did not would assert more than is
   *       known.
   */
  if (!isApiRequestError(failure)) {
    return {
      message: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
      severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
    };
  }
  if (failure.status === UNDECLARED_NOT_FOUND_STATUS) {
    return {
      message: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
      severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
    };
  }
  const reported = failure.problem.message;
  if (reported !== null && reported.trim() !== '') {
    return { message: reported, severity: AUTH_SUMMARY_MESSAGE_SEVERITY };
  }
  /*
   * WHY : Assumptions: both sentences are inside the 75-character work area this screen's message
   *       field is filled from, so neither can overrun the band -- the catalog width-checks them where
   *       it declares them, which is why nothing is measured again here.
   */
  return {
    message: isTransientFailure(failure)
      ? TRANSIENT_FAILURE_TRY_AGAIN
      : PERSISTENT_FAILURE_REPORT_IT,
    severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
  };
}

/**
 * Finds the field-level refusal, if any, that the service raised against the account entry.
 *
 * Assumptions: `fieldErrors` is always present and is empty rather than absent when a failure names
 * no field, as `ApiError` documents, so its length is the meaningful test and not its presence. The
 * member name is matched case-insensitively because the contract's scope member is `accountId` while a
 * refusal may name the same datum in the casing its own validator used; matching one spelling exactly
 * would silently drop the highlight and leave the field unmarked.
 * @param {ApiError | null} error - The normalised problem document, or `null` when nothing failed.
 * @returns {FieldError | null} The whole field refusal -- its state decides the blank marker and its
 *   message is shown beneath the control -- or `null` when the failure named no account field.
 */
export function accountIdFieldError(error: ApiError | null): FieldError | null {
  if (error === null) {
    return null;
  }
  for (const candidate of error.fieldErrors) {
    if (candidate.field.toLowerCase() === 'accountid') {
      return candidate;
    }
  }
  return null;
}

/**
 * Renders one occurrence count the way the source program's edit field renders it.
 *
 * Assumptions: the two counts are INTEGERS and not amounts, so they are formatted rather than passed
 * through as exact text. `CIPAUSMY.cpy` L27 to L28 declares `PA-APPROVED-AUTH-CNT` and
 * `PA-DECLINED-AUTH-CNT` as `PIC S9(04) COMP` -- binary halfwords -- and the contract types both as
 * numbers for that reason, while every monetary member beside them is a string. Styling either as
 * money, or passing an amount through this function, would get exactly one of those two cases wrong.
 *
 * Assumptions: three digits, zero-filled, and truncated from the low order, because that is what the
 * source's `MOVE` does. `COPAUS0C.cbl` L788 to L791 moves the halfword into
 * `WS-DISPLAY-COUNT PIC 9(03)` (declared L58) and thence to the `LENGTH=3` map field, and an unsigned
 * three-digit display field takes the absolute value and discards any higher-order digit. Rendering
 * the raw number instead would show `7` where the terminal shows `007`, and would overrun a
 * three-character column once a count passed 999.
 * @param {number} value - The occurrence count as the contract delivers it.
 * @returns {string} The count in the three-character zero-filled form the map field carries.
 */
export function formatAuthCount(value: number): string {
  const digits = Math.abs(Math.trunc(value)) % 1000;
  return String(digits).padStart(AUTH_SUMMARY_FIELD_WIDTHS.count, '0');
}

/**
 * One entry of the account summary panel, as antd's items form of `Descriptions` declares it.
 *
 * Assumptions: derived from `DescriptionsProps` rather than imported by name, because antd 6 re-exports
 * `DescriptionsProps` from the package root but not `DescriptionsItemType`, and this screen imports
 * from the package root only so that the CSS-variables theme applies. Deriving it keeps the builder's
 * return type tied to the version installed.
 */
type AuthSummaryDescriptionItem = NonNullable<DescriptionsProps['items']>[number];

/**
 * Substitutes empty text for an absent value.
 *
 * Assumptions: every descriptive member of the summary is nullable in the contract, and an absent one
 * must render as nothing rather than as the word `null`. The source screen leaves such a field blank,
 * so empty text is the faithful rendering as well as the safe one.
 * @param {string | null} value - The member as the contract delivers it.
 * @returns {string} The value, or empty text when the contract sent none.
 */
function displayText(value: string | null): string {
  return value ?? '';
}

/**
 * The positions from which a backward step has nothing to answer with.
 *
 * ⚠️ Refactoring Rationale: the two paging arms below read a NAMED position where they used to read
 * `browse.hasPrev` and `browse.hasNext` directly. The two are exactly equivalent -- the hook derives all
 * five positions from those two members -- so which sentence appears when has not changed. What changes
 * is that the DEAD END is now enumerated instead of falling out of two false flags: a browse with no
 * rows and no page on either side satisfied `!hasPrev` and `!hasNext` at once and neither guard said so.
 * `ui/src/hooks/usePagedQuery.ts` records that five screens had five idioms for this, states that a
 * screen showing a boundary sentence is to branch on the published position, and this is that branch.
 * `ui/src/screens/refTypeList/index.tsx` L632 and `ui/src/screens/userList/index.tsx` adopt the same two
 * sets, so a sixth screen has one shape to copy rather than five.
 *
 * Assumptions: the two sets are written out rather than derived from one another, because they are not
 * complements -- `INTERIOR` is in neither and `EMPTY` and `ONLY` are in both -- so an expression
 * relating them would be longer than the enumeration and harder to check against the hook's own table.
 */
const BACKWARD_EXHAUSTED: readonly PageBoundary[] = Object.freeze(['EMPTY', 'ONLY', 'FIRST']);

/**
 * The positions from which a forward step has nothing to answer with.
 *
 * Assumptions: the mirror of {@link BACKWARD_EXHAUSTED} with `LAST` in place of `FIRST`. `EMPTY` is in
 * both, so a dead-end browse answers either key with that key's own boundary sentence -- which is what
 * the reference does, since `COPAUS0C.cbl` L380 to L384 and L408 to L411 re-send the screen with the
 * matching sentence and never test for a record count first.
 */
const FORWARD_EXHAUSTED: readonly PageBoundary[] = Object.freeze(['EMPTY', 'ONLY', 'LAST']);

/**
 * The edit mask every amount on this screen is rendered through.
 *
 * ⚠️ Purpose: browser validation counted four mutually incompatible money renderings across the
 * application and named this screen's as the bare one -- `5000.00` with no sign, no padding and no
 * column. This constant is what puts these amounts on the application's single money authority,
 * `ui/src/format/money.ts`, instead of printing the wire string.
 *
 * ⚠️ Assumptions: the picture is CHOSEN rather than inherited, and the choice is forced by a property
 * these mapsets have and no other money surface does: `COPAU00.bms` declares NO `PICOUT` on any of its
 * seven amount fields -- `CREDLIM` L147 to L151 through `DECLAMT` L192 to L196, and `PAMT001` in the
 * table -- so there is no mapset mask to transcribe. The mask lives in the PROGRAM instead:
 * `COPAUS0C.cbl` L56 declares `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` and applies it to `CREDLIM` and
 * `CREDBAL` at L780 to L799, and `COPAUS1C.cbl` L52 declares `WS-AUTH-AMT PIC -zzzzzzz9.99` for the
 * authorization amount. Twelve characters, eight integer positions, ungrouped. Of the three pictures
 * `MONEY_PICTURES` declares, `transactionAmount` is `+99999999.99` -- eight integer positions,
 * ungrouped, width twelve -- which matches on all three counts; `accountGrouped` is fifteen wide, nine
 * integer positions and grouped, and `billPayBalance` is ten integer positions. So this is the one
 * declared picture the reference justifies.
 *
 * ⚠️ Trade-offs: two divergences from the program's own mask are accepted, and both are properties of
 * the shared picture rather than of this screen. The declared picture zero-FILLS where `-zzzzzzz9.99`
 * zero-SUPPRESSES, so `5000.00` renders `+00005000.00` where the terminal shows `     5000.00`; and it
 * prints `+` on a positive where the terminal prints a blank. Reproducing the suppression here would
 * mean a fourth rendering on a screen the finding exists because of, so the shared picture wins and
 * the divergence is recorded rather than hidden. Both are visible-form differences only -- the digits
 * and the sign are the service's own, and `applyMoneyEditMask` PRESERVES a value too wide for its
 * picture rather than truncating it, so no magnitude can be lost.
 *
 * ⚠️ Refactoring Rationale: the four NARROW panel fields no longer take this picture, and the note that
 * stood here is withdrawn as SATISFIED rather than as wrong. It read that `COPAUS0C.cbl` L57 declares a
 * second mask, `WS-DISPLAY-AMT9 PIC -zzzz9.99`, that `MONEY_PICTURES` had no counterpart, that adding
 * one belonged in `ui/src/format/money.ts` beside the others rather than here, and that until it existed
 * eight integer positions was a superset of five so no magnitude could be lost. The counterpart now
 * exists -- `MONEY_PICTURES.authorizationSummaryAmount`, citing that same program line -- so the
 * widening it described is over and the four fields render at their own declared measure through
 * {@link AUTH_SUMMARY_NARROW_MONEY_PICTURE}.
 */
const AUTH_SUMMARY_MONEY_PICTURE = MONEY_PICTURES.transactionAmount;

/**
 * The edit mask the four NARROW panel amounts are rendered through.
 *
 * ⚠️ Purpose: the reference edits one precision through TWO masks, and the difference is visible. Every
 * one of the six panel amounts is `PIC S9(09)V99 COMP-3` in the segment (`CIPAUSMY.cpy` L23 to L26 and
 * L29 to L30), so the differing widths are presentation and not precision: `COPAUS0C.cbl` L56 declares
 * `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` and L57 declares `WS-DISPLAY-AMT9 PIC -zzzz9.99`, and L780 to
 * L799 moves the credit limit and the credit balance through the wide one while moving the CASH LIMIT,
 * the CASH BALANCE, the APPROVED TOTAL and the DECLINED TOTAL through the narrow one. Rendering all six
 * at twelve characters made four of them a column the terminal never painted.
 *
 * ⚠️ Assumptions: `MONEY_PICTURES.authorizationSummaryAmount` is the entry to use and not a fourth
 * literal, because that entry cites this exact program line as its source and declares the three
 * properties that distinguish the mask -- five integer positions, four of them suppressed so the units
 * digit still prints, and a `-` sign character that leaves a blank on a non-negative. Those last two are
 * what make it a closer transcription than the wide picture is of its own mask, and the wide picture's
 * two accepted divergences above therefore do NOT apply to these four fields.
 *
 * ⚠️ Trade-offs: two amounts on one screen now carry two sign conventions -- a blank on a non-negative
 * narrow amount against a `+` on a non-negative wide one. `ui/src/format/money.ts` records that exact
 * consequence as the reason it declined to add a fifth entry for the wide mask, and it is accepted for
 * the same reason in reverse: the reference itself paints those two conventions side by side, because
 * `-zzzzzzz9.99` and `-zzzz9.99` differ only in width and the `+` is an artefact of the shared wide
 * picture rather than of the source. Reproducing one faithfully is better than making both wrong for
 * symmetry, and the digits are the service's own in either case.
 */
const AUTH_SUMMARY_NARROW_MONEY_PICTURE = MONEY_PICTURES.authorizationSummaryAmount;

/**
 * Builds the style for a monetary cell.
 *
 * ⚠️ Assumptions: the amount arrives as exact decimal TEXT and is masked as text, never parsed. The
 * underlying field is packed decimal -- `PIC S9(09)V99 COMP-3` at `CIPAUSMY.cpy` L23 to L30 -- and any
 * pass through a JavaScript number would put it through an IEEE-754 binary64 double, which cannot
 * represent most scale-two fractions exactly, so a cent the service computed could render as a
 * different cent. The failure would be the worst kind available on a screen of credit limits and
 * balances: a plausible figure rather than an error.
 *
 * ⚠️ Refactoring Rationale: the measure is the PICTURE's width and no longer the map field's declared
 * `LENGTH`. Every amount now renders at exactly twelve characters, so one measure is the accurate one
 * and three would each be wrong for some cell -- a nine-character box holding a twelve-character
 * masked value would either grow, making the declaration inert, or clip a figure. Browser validation
 * of the previous arrangement measured the three declared widths producing three different left edges,
 * which is the alignment the source's own two masks produce and the finding read as six unaligned
 * amounts; with one picture the alignment question does not arise.
 *
 * ⚠️ Assumptions: `whiteSpace` comes from the renderer and is not chosen here, because the mask's
 * leading pad characters ARE the column. Without `pre` the browser collapses them and every amount
 * starts at its first significant digit, which is the defect this whole change exists to remove.
 *
 * ⚠️ Assumptions: the colour is the token the RENDERER returns, for all three sign cases, and it is
 * taken unconditionally. `MONEY_SIGN_TEXT_TOKENS` in `ui/src/theme/tokens.ts` is the application's one
 * authority for money hue, and `ui/src/screens/accountView/index.tsx` L1050 and
 * `ui/src/screens/billPay/index.tsx` L1594 both resolve it the same unconditional way -- so an amount
 * on this screen now paints the same hue as the same sign of the same magnitude on either of those,
 * which is precisely the cross-screen agreement the money finding measured the absence of.
 *
 * ⚠️ Trade-offs: the mapset's own `COLOR=BLUE` operand is therefore NOT honoured on the seven
 * amount fields -- `CREDLIM` at `COPAU00.bms` L147 to L151 through `DECLAMT` at L192 to L196 all
 * declare it, and a positive amount now resolves to `DEFAULT` instead. This is a knowing divergence,
 * and it is the narrower of the two available ones. Honouring the operand would put a fourth money hue
 * on the glass and would make an ordinary positive balance here differ from the identical balance on
 * the account view, which is the defect. The operand itself is not lost: every NON-money value on this
 * screen still resolves through `BMS_TEXT_COLOR_TOKENS.BLUE`, so the mapset's colour vocabulary is
 * intact everywhere it is not competing with the money authority. Note also that the sign map's own
 * rationale grounds its positive entry in `COACTVW`'s money fields carrying no `COLOR=` operand -- a
 * condition this mapset does not share -- so the divergence is recorded here rather than left to be
 * inferred from a constant whose stated premise does not hold on this screen.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @param {RenderedMoney} rendered - The masked amount, its sign and its colour token.
 * @returns {CSSProperties} The style for that amount's cell.
 */
function moneyCellStyle(tokens: AntdCssVariables, rendered: RenderedMoney): CSSProperties {
  /*
   * WHY : ⚠️ Refactoring Rationale: the resolved reference is narrowed by a `typeof` TEST, where it was
   *       narrowed by calling `String` on it. `renderMoney` publishes its token as the whole
   *       `AntdTokenName` surface, so indexing the theme's map with it yields the union of every token
   *       value the library declares -- numeric durations, radii and heights among them -- which no CSS
   *       `color` accepts. `String` looked like the narrowing that cannot be wrong, but it is the one
   *       that cannot FAIL: applied to a member that was not a string it would emit
   *       `color: [object Object]` and paint nothing, which is exactly why
   *       `@typescript-eslint/no-base-to-string` refuses it. The test proves the member is a string
   *       before it is used and DROPS the declaration when it is not, so a mis-typed token name loses a
   *       hue rather than poisoning the whole style. Adopted from
   *       `ui/src/screens/billPay/index.tsx` L1685 to L1705, which reached the same conclusion from the
   *       same rule and records the same reasoning at its own call site.
   * WHY : Assumptions: at run time this is the same value it always was. Every name the renderer can
   *       return addresses a colour token, and a resolved reference is already the string
   *       `var(--ant-...)`, so the test passes on every reachable input and no painted hue changes.
   * WHY : Alternatives Considered: a type assertion on the indexed access, which the checker accepts
   *       silently. Rejected because it asserts precisely the thing that would be false in the failing
   *       case, putting `[object Object]` back with the diagnostic removed.
   */
  const colourReference = tokens[rendered.colorToken];
  const colour = typeof colourReference === 'string' ? colourReference : null;
  return {
    /*
     * WHY : Assumptions: the member is SPREAD conditionally rather than set to `undefined`, because
     *       `ui/tsconfig.json` sets `exactOptionalPropertyTypes`, under which an explicit `undefined` is
     *       not assignable to an optional property. Omitting it leaves the cell inheriting the
     *       surrounding text colour, which is the safe direction for a value whose hue could not be
     *       resolved.
     */
    ...(colour === null ? {} : { color: colour }),
    // WHY : Refactoring Rationale: `display` is set to `inline-block` because `Typography.Text`
    //       renders a `span`, and CSS applies neither `min-inline-size` nor `text-align` to a
    //       non-replaced INLINE box -- such a box is sized by its content. Browser validation of
    //       this screen confirmed the consequence: with the span left inline, three amounts
    //       carrying three different declared widths all measured the same 58.81px because all
    //       three happened to be seven characters long, and `text-align: end` had no effect at
    //       all, so every amount began at the same left edge instead of right-aligning into a
    //       fixed column. Making the box `inline-block` is what turns the two declarations below
    //       from inert into the blank-suppressed, right-aligned fixed-width column the source's
    //       `-zzzzzzz9.99` and `-zzzz9.99` edit masks produce on the terminal. Alternatives
    //       Considered: `display: block`, rejected because it would force each amount onto its own
    //       line inside a `Descriptions` cell that the map paints as one line; and dropping the
    //       width and alignment as unachievable, rejected because that would abandon the column
    //       geometry the mapset declares rather than reproduce it. `inline-block` is a structural
    //       layout keyword rather than a design value, so it resolves to no design token and needs
    //       none -- the same standing as the `ch` width below.
    display: 'inline-block',
    fontFamily: tokens[TYPOGRAPHY_TOKENS.fixedPitchData],
    /*
     * WHY : ⚠️ Refactoring Rationale: the measure is read from the amount's OWN picture, where it used to
     *       be the module-wide one. The screen renders two pictures now -- twelve characters for the
     *       credit figures and the row amount, nine for the four cash and total figures -- so a single
     *       measure would put a nine-character value in a twelve-character box and reinstate exactly
     *       the column the terminal does not paint. `renderMoney` returns the picture it used on
     *       `RenderedMoney.picture`, so the box and the mask cannot disagree by construction.
     */
    minInlineSize: `${String(rendered.picture.width)}ch`,
    textAlign: 'end',
    whiteSpace: rendered.whiteSpace,
  };
}

/**
 * Renders one amount through the screen's edit mask.
 *
 * Purpose: one call site for every amount on the screen -- six in the panel and one per table row --
 * so the pad preservation, the sign colour, the fixed-pitch token and the column geometry cannot
 * diverge between them. Divergence between amounts on one screen is what the money finding measured.
 *
 * ⚠️ Assumptions: the PICTURE is a parameter and is the only thing that varies between call sites,
 * because the reference varies exactly that and nothing else -- `COPAUS0C.cbl` L780 to L799 moves six
 * amounts of one precision through two edit fields. Defaulting it would let a new amount be added at the
 * wrong measure by omission, which is how all six came to share one picture in the first place.
 * @param {string} wireAmount - The amount as the contract sent it: exact decimal text.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @param {MoneyPicture} picture - The edit mask the reference applies to THIS amount.
 * @returns {ReactElement} The masked amount, right-aligned in its column.
 */
function moneyValue(
  wireAmount: string,
  tokens: AntdCssVariables,
  picture: MoneyPicture,
): ReactElement {
  const rendered = renderMoney(wireAmount, picture);
  return (
    <Typography.Text style={moneyCellStyle(tokens, rendered)}>{rendered.text}</Typography.Text>
  );
}

/**
 * Builds the style for a non-monetary value cell.
 *
 * Assumptions: every value field in the panel region is painted `COLOR=BLUE` -- `CNAME` at
 * `COPAU00.bms` L96 to L99 through `DECLAMT` at L192 to L196 -- which the theme bridge resolves to
 * `colorPrimary`, the dominant label and frame role across the whole mapset population. Long text is
 * allowed to break within a word because the address and name fields are 25 characters of free text
 * that a narrow viewport would otherwise push out of its cell.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {CSSProperties} The style for a plain value cell.
 */
function valueCellStyle(tokens: AntdCssVariables): CSSProperties {
  return { color: tokens[BMS_TEXT_COLOR_TOKENS.BLUE], overflowWrap: 'break-word' };
}

/**
 * Builds the holder block: the customer's name and the two lines of one postal address.
 *
 * ⚠️ Purpose: this is the finding this function exists for. The three values were three separate
 * bordered entries, and two of them had no caption to put in their header cell -- so browser validation
 * measured two empty grey label cells at every one of the six widths, and, below the medium breakpoint,
 * the two halves of one postal address separated by an unrelated `Acct Status` row that reflowed
 * between them. A bordered cell whose header is blank presents as a value belonging to nothing, and an
 * address split by a foreign row presents as two addresses.
 *
 * ⚠️ Assumptions: the mapset paints these three as ONE labelled block, and that is why they become one
 * cell rather than three tidier ones. `COPAU00.bms` L92 to L95 puts the caption `Name: ` at column 3 of
 * row 6 and then paints `CNAME` at row 6, `ADDR001` at row 7 and `ADDR002` at row 8 -- all three at
 * COLUMN 10, under the one caption, with the next caption `PH:` not appearing until row 9. The two
 * address fields have no caption in the source because they are continuation lines of the field above
 * them, so giving each its own entry was inventing a structure the mapset does not have.
 *
 * ⚠️ Assumptions: the two hidden names remain, and they remain because the position that identified
 * these lines in the source does not survive. Design gap G1 gives up the character grid, so "the line
 * below the name" is not a property the delivered screen has at every width and was never a property an
 * assistive technology could use at any width. Inside a captioned cell a hidden name is safe -- it
 * names a value within a cell a visible caption already heads -- which is exactly what it was not when
 * it stood as a bordered entry's whole label.
 *
 * Trade-offs: the panel's reading order becomes the mapset's LEFT column as a block -- name, address,
 * address -- followed by the right-hand values, where it used to interleave them as the character grid
 * does. That is accepted: an interleaved order is only meaningful beside the coordinates that produced
 * it, and at one column per row the interleaving is what put a status code between two address lines.
 * @param {PendingAuthSummary} summary - The account summary the listing returned.
 * @param {CSSProperties} style - The panel's plain value style.
 * @returns {ReactElement} The holder block, ready for `Descriptions`.
 */
function holderBlockCell(summary: PendingAuthSummary, style: CSSProperties): ReactElement {
  return (
    <Flex vertical>
      <Typography.Text style={style}>{displayText(summary.customerName)}</Typography.Text>
      <Typography.Text style={style}>
        <Typography.Text style={VISUALLY_HIDDEN_STYLE}>
          {AUTH_SUMMARY_HIDDEN_LABELS.addressLine1}
        </Typography.Text>
        {displayText(summary.addressLine1)}
      </Typography.Text>
      <Typography.Text style={style}>
        <Typography.Text style={VISUALLY_HIDDEN_STYLE}>
          {AUTH_SUMMARY_HIDDEN_LABELS.addressLine2}
        </Typography.Text>
        {displayText(summary.addressLine2)}
      </Typography.Text>
    </Flex>
  );
}

/**
 * Renders one status code with a name that reaches the accessibility tree and not the screen.
 *
 * Assumptions: the name is nested INSIDE the value's own element rather than placed beside it, so the
 * pair is announced as one unit and a reader moving code by code is told which segment member each code
 * came from. `VISUALLY_HIDDEN_STYLE` is the project's one mechanism for that, already used by the two
 * address lines in the same panel.
 * @param {string} name - The accessible name, from {@link AUTH_SUMMARY_HIDDEN_LABELS}.
 * @param {string | null} code - The status code the segment carried, or `null` when it carried none.
 * @param {CSSProperties} style - The panel's plain value style.
 * @returns {ReactElement} The named code, ready to sit inside the status cell.
 */
function statusSlot(name: string, code: string | null, style: CSSProperties): ReactElement {
  return (
    <Typography.Text style={style}>
      <Typography.Text style={VISUALLY_HIDDEN_STYLE}>{name}</Typography.Text>
      {displayText(code)}
    </Typography.Text>
  );
}

/**
 * Builds the status cell: the authorization-status flag and all five account-status slots.
 *
 * ⚠️ Purpose: six members the service returns used to reach no field at all. `PA-AUTH-STATUS` was
 * rendered under the `Acct Status: ` caption as though it were the account status, and
 * `PA-ACCOUNT-STATUS`, five slots wide, was rendered nowhere -- so a summary carrying five populated
 * status codes displayed none of them and displayed a DIFFERENT member in the position an operator would
 * read them from. Browser validation recorded exactly that: five slots returned and never shown, under a
 * caption showing something else.
 *
 * ⚠️ Refactoring Rationale: the reasoning that produced the old arrangement argued from the 3270 field
 * width -- `ACCSTAT` is `LENGTH=1` at `COPAU00.bms` L114 to L117, `PA-ACCOUNT-STATUS` is `PIC X(02)`
 * five times at `CIPAUSMY.cpy` L22, so the five slots "would widen a one-character field to ten" -- and
 * concluded that `PA-AUTH-STATUS PIC X(01)` at L21 was "the only member whose width the field can hold".
 * Two facts retire that conclusion. `COPAUS0C.cbl` populates NEITHER member: a search of the whole
 * program for `ACCSTAT` returns no hit, so the reference leaves the field blank and the old binding was
 * itself an addition, not a transcription. And the width argument appeals to a character-cell budget that
 * design gap **G1** has already surrendered -- this panel reflows to one column below the medium
 * breakpoint, so no cell in it holds a fixed count of character positions at every width.
 *
 * ⚠️ Assumptions: the mapset's own caption is the ONLY text this cell puts on screen. Five discrete
 * rows, each with its own visible caption, would have required inventing five captions -- `Acct Status
 * 1:` through `Acct Status 5:` -- that no baseline source paints, which transformation rule T8
 * forecloses. So the five slots are five discrete, individually named values inside the one cell the
 * mapset labels, and their names are carried by {@link AUTH_SUMMARY_HIDDEN_LABELS}.
 *
 * Assumptions: the six values are written as six explicit siblings rather than mapped from a collection,
 * for the reason the panel builder records -- the arity of exactly five is a property of the contract and
 * of the target schema's five columns, and gathering them would invite a caller to iterate a length no
 * declaration supports. Static siblings also need no React key, so nothing here fabricates an index.
 *
 * Alternatives Considered: giving `PA-AUTH-STATUS` a `Descriptions` entry of its own with a hidden
 * label. Rejected because a bordered entry whose header cell is empty renders as a grey box with no text
 * in it -- the defect browser validation reported against the two address lines -- so a hidden label is
 * only safe where it names a value INSIDE a cell that a visible caption already heads.
 * @param {PendingAuthSummary} summary - The account summary the listing returned.
 * @param {CSSProperties} style - The panel's plain value style.
 * @returns {ReactElement} The status cell, ready for `Descriptions`.
 */
function accountStatusCell(summary: PendingAuthSummary, style: CSSProperties): ReactElement {
  /*
   * WHY : ⚠️ Refactoring Rationale: the five ACCOUNT-status slots come first and the authorization-status
   *       flag last, where the flag used to lead. The caption is `Acct Status: `, so the member it names
   *       is what an operator reads first; leading with `PA-AUTH-STATUS` reproduced the substitution this
   *       cell was rebuilt to end -- the first value under the caption was still the one that does not
   *       belong to it, and only the hidden names said otherwise. Ordering the slots first also puts
   *       them in their own declared order, `PA-ACCOUNT-STATUS` OCCURS 1 through 5 at
   *       `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` L22.
   * WHY : Assumptions: the flag is KEPT rather than moved out or dropped. It is a member the service
   *       returns, and a returned member reaching no field is the defect this cell exists to fix; it has
   *       its own hidden name, so nothing about it reads as an account-status slot.
   */
  return (
    <Flex gap="small" wrap>
      {statusSlot(AUTH_SUMMARY_HIDDEN_LABELS.accountStatus1, summary.accountStatus1, style)}
      {statusSlot(AUTH_SUMMARY_HIDDEN_LABELS.accountStatus2, summary.accountStatus2, style)}
      {statusSlot(AUTH_SUMMARY_HIDDEN_LABELS.accountStatus3, summary.accountStatus3, style)}
      {statusSlot(AUTH_SUMMARY_HIDDEN_LABELS.accountStatus4, summary.accountStatus4, style)}
      {statusSlot(AUTH_SUMMARY_HIDDEN_LABELS.accountStatus5, summary.accountStatus5, style)}
      {statusSlot(AUTH_SUMMARY_HIDDEN_LABELS.authStatus, summary.authStatus, style)}
    </Flex>
  );
}

/**
 * Builds the twelve entries of the account summary panel, in the map's own reading order.
 *
 * Assumptions: twelve entries, one per CAPTION the mapset paints in rows 6 to 12, ordered as the map
 * paints them -- name and customer identifier on row 6, account status on row 7, telephone and the two
 * counts on row 9, the three limit-and-amount fields on row 11 and the three balance-and-amount fields
 * on row 12. Reading order is preserved even though absolute position is not, which is the half of
 * design gap **G1** that is kept.
 *
 * ⚠️ Refactoring Rationale: the panel carries twelve entries and not fourteen because the two address
 * lines are no longer entries of their own -- they are rendered inside the name entry by
 * {@link holderBlockCell}, which is where the mapset puts them. Two successive attempts got this wrong
 * in two different ways and both are recorded, because the second looks like a fix. Giving them no label
 * left two bordered cells with empty header cells, announced as values with no name. Giving them a
 * hidden label kept the empty grey header cell on the glass, which is the defect browser validation then
 * measured at all six widths, and left the address split by whatever reflowed between its halves. The
 * entry count is a count of the mapset's captions, and `ADDR001` at L107 and `ADDR002` at L118 have no
 * preceding `INITIAL=` field, so they were never entitled to entries.
 *
 * ⚠️ Refactoring Rationale: the `'Acct Status: '` entry is built by {@link accountStatusCell} and
 * carries all six status members the segment declares, where it used to carry `authStatus` alone. The
 * reasoning it replaces, and the two facts that retire that reasoning, are recorded on that function.
 * Assumptions: the five `PA-ACCOUNT-STATUS` slots remain five DISCRETE members of the contract, never
 * an array -- the arity of exactly five is enforced by the target schema as five columns, so gathering
 * them would admit a sixth and invite a caller to iterate a length no declaration supports.
 * @param {PendingAuthSummary} summary - The account summary the listing returned.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {AuthSummaryDescriptionItem[]} The panel entries, ready for `Descriptions`.
 */
export function buildAuthSummaryDescriptions(
  summary: PendingAuthSummary,
  tokens: AntdCssVariables,
): AuthSummaryDescriptionItem[] {
  const value = valueCellStyle(tokens);
  return [
    {
      key: 'customerName',
      label: AUTH_SUMMARY_LABELS.name,
      children: holderBlockCell(summary, value),
    },
    {
      key: 'customerId',
      label: AUTH_SUMMARY_LABELS.customerId,
      children: <Typography.Text style={value}>{summary.customerId}</Typography.Text>,
    },
    {
      key: 'accountStatus',
      label: AUTH_SUMMARY_LABELS.accountStatus,
      children: accountStatusCell(summary, value),
    },
    {
      key: 'phoneNumber1',
      label: AUTH_SUMMARY_LABELS.phone,
      children: (
        <Typography.Text style={value}>{displayText(summary.phoneNumber1)}</Typography.Text>
      ),
    },
    {
      key: 'approvedAuthCnt',
      label: AUTH_SUMMARY_LABELS.approvalCount,
      children: (
        <Typography.Text style={value}>{formatAuthCount(summary.approvedAuthCnt)}</Typography.Text>
      ),
    },
    {
      key: 'declinedAuthCnt',
      label: AUTH_SUMMARY_LABELS.declineCount,
      children: (
        <Typography.Text style={value}>{formatAuthCount(summary.declinedAuthCnt)}</Typography.Text>
      ),
    },
    /*
     * WHY : ⚠️ Refactoring Rationale: the six monetary entries below gain a CELL style, and only they do.
     *       Browser measurement found every amount in a rendered column sharing one leading edge and
     *       splitting into two trailing edges 25.203125px apart, at 1280, 768 and 375 alike -- the value
     *       cell aligns to `start`, so an `inline-block` amount sits flush left and its 12ch or 9ch box
     *       width decides where its trailing edge falls. Anchoring the cell's content to its trailing
     *       edge brings the decimal points of a column into line, which is the property the mapset
     *       delivers by giving each of its money columns one constant length. The reasoning, the
     *       measurement and the three rejected alternatives are recorded once at
     *       `ui/src/layout/recordLayout.ts` rather than repeated per entry.
     * WHY : ⚠️ Assumptions: it is applied per entry rather than as the panel's own `styles.content`,
     *       because the panel also holds the holder block, the account-status list and the phone
     *       number. Trailing-edge alignment is right for a column of figures and wrong for two lines of
     *       a postal address, so a root-level style would fix six cells by disfiguring six others.
     */
    {
      key: 'creditLimit',
      label: AUTH_SUMMARY_LABELS.creditLimit,
      children: moneyValue(summary.creditLimit, tokens, AUTH_SUMMARY_MONEY_PICTURE),
      styles: { content: monetaryRecordCellStyle() },
    },
    {
      key: 'cashLimit',
      label: AUTH_SUMMARY_LABELS.cashLimit,
      children: moneyValue(summary.cashLimit, tokens, AUTH_SUMMARY_NARROW_MONEY_PICTURE),
      styles: { content: monetaryRecordCellStyle() },
    },
    {
      key: 'approvedAuthAmt',
      label: AUTH_SUMMARY_LABELS.approvedAmount,
      children: moneyValue(summary.approvedAuthAmt, tokens, AUTH_SUMMARY_NARROW_MONEY_PICTURE),
      styles: { content: monetaryRecordCellStyle() },
    },
    {
      key: 'creditBalance',
      label: AUTH_SUMMARY_LABELS.creditBalance,
      children: moneyValue(summary.creditBalance, tokens, AUTH_SUMMARY_MONEY_PICTURE),
      styles: { content: monetaryRecordCellStyle() },
    },
    {
      key: 'cashBalance',
      label: AUTH_SUMMARY_LABELS.cashBalance,
      children: moneyValue(summary.cashBalance, tokens, AUTH_SUMMARY_NARROW_MONEY_PICTURE),
      styles: { content: monetaryRecordCellStyle() },
    },
    {
      key: 'declinedAuthAmt',
      label: AUTH_SUMMARY_LABELS.declinedAmount,
      children: moneyValue(summary.declinedAuthAmt, tokens, AUTH_SUMMARY_NARROW_MONEY_PICTURE),
      styles: { content: monetaryRecordCellStyle() },
    },
  ];
}

/** Characters the acquirer-supplied date and time members carry when they carry a value at all. */
const STORED_DATE_TIME_LENGTH = 6;

/**
 * Composes the six stored date characters into the form the source screen displays.
 *
 * Assumptions: the composition is the CLIENT's to perform, which the contract states outright -- the
 * member is declared `maxLength: 6` and described as "the six stored characters in year, month, day
 * order", adding that the reference screen's month-first rendering "is a rendering and is performed by
 * the client". So the service sends `yymmdd` and this function is what makes it readable.
 *
 * Assumptions: the separator is a SOLIDUS, and the copybook decides that rather than the contract's
 * prose. `COPAUS0C.cbl` L531 to L534 slices the stored value into `WS-CURDATE-YY`, `WS-CURDATE-MM` and
 * `WS-CURDATE-DD` and then moves the group `WS-CURDATE-MM-DD-YY`, which `app/cpy/CSDAT01Y.cpy` L30 to
 * L35 declares as month, `FILLER PIC X(01) VALUE '/'`, day, the same filler again, and year -- eight
 * characters, matching the `LENGTH=8` of `PDATE01` and the `INITIAL='mm/dd/yy'` the mapset paints on
 * its own date field at L47 to L51. The OpenAPI description of this member says "separated by hyphens",
 * which disagrees; the copybook is followed because the COBOL baseline is this migration's behavioural
 * oracle and a hyphen would render a form no terminal ever displayed. The discrepancy is named here so
 * a reader meeting the contract first is not left thinking this is a defect.
 *
 * Trade-offs: a value that is not exactly six characters is passed through UNCHANGED rather than
 * sliced. The contract's `maxLength` admits a shorter one, and slicing that by fixed offsets would
 * compose a plausible but wrong date -- four characters would silently yield a day of `00`. Showing
 * the stored characters instead is visibly odd, which is the safer failure for a financial record.
 * @param {string | null} stored - The six stored characters in year, month, day order, or `null`.
 * @returns {string} The month-first rendering, the stored characters when they are not six, or empty
 *   text when the row carried none.
 */
export function formatAuthOrigDate(stored: string | null): string {
  if (stored === null) {
    return '';
  }
  if (stored.length !== STORED_DATE_TIME_LENGTH) {
    return stored;
  }
  return `${stored.slice(2, 4)}/${stored.slice(4, 6)}/${stored.slice(0, 2)}`;
}

/**
 * Composes the six stored time characters into the form the source screen displays.
 *
 * Assumptions: colon-separated hour, minute and second, because the source builds it by slicing into a
 * field that already holds the separators. `COPAUS0C.cbl` L527 to L529 moves the stored value's three
 * pairs into positions 1, 4 and 7 of `WS-AUTH-TIME PIC X(08) VALUE '00:00:00'` (declared L60), leaving
 * the colons at positions 3 and 6 untouched -- eight characters, matching the `LENGTH=8` of `PTIME01`.
 * The stored order is already hour, minute, second, so unlike the date this is a separator insertion
 * and not a reordering.
 *
 * Trade-offs: a value that is not exactly six characters is passed through unchanged, for the reason
 * recorded on {@link formatAuthOrigDate}.
 * @param {string | null} stored - The six stored characters in hour, minute, second order, or `null`.
 * @returns {string} The colon-separated rendering, the stored characters when they are not six, or
 *   empty text when the row carried none.
 */
export function formatAuthOrigTime(stored: string | null): string {
  if (stored === null) {
    return '';
  }
  if (stored.length !== STORED_DATE_TIME_LENGTH) {
    return stored;
  }
  return `${stored.slice(0, 2)}:${stored.slice(2, 4)}:${stored.slice(4, 6)}`;
}

/**
 * Pointer affordance for a selectable table row.
 *
 * Assumptions: `pointer` is a structural interaction keyword rather than a design value, so it resolves
 * to no design token and needs none -- the same standing the `inline-block` and `ch` measures in this
 * module already have. It is declared once here so every row takes the same one.
 */
const ROW_AFFORDANCE_STYLE: CSSProperties = { cursor: 'pointer' };

/**
 * Builds the pointer-target measure of one row's selection cell.
 *
 * ⚠️ Purpose: an accessibility audit measured the control this replaced at fourteen pixels square at
 * every width and named it the smallest control in the application. That floor is preserved through the
 * change of control: an antd `Input` takes the theme's own `controlHeight` vertically, which already
 * clears the minimum, but its inline measure is whatever its container gives it -- and a one-character
 * field in a three-character column would be narrower than the mark it replaced if nothing stated
 * otherwise.
 *
 * ⚠️ Assumptions: the floor is AA's twenty-four and NOT the audit's forty-four.
 * `CONTROL_SCALE_DECISION` in `ui/src/theme/tokens.ts` records that decision with both criteria
 * attached -- twenty-four is 2.5.8 Target Size (Minimum) at AA, forty-four is 2.5.5 Target Size
 * (Enhanced) at AAA, recorded as considered and declined -- so honouring forty-four here would reopen a
 * settled decision from one screen and would make this control larger than every themed control beside
 * it.
 *
 * ⚠️ Assumptions: the character term is {@link SELECTION_CELL_RESERVED_COLUMNS} and the padding term is
 * the design system's own horizontal control padding, so the expression reserves what the control
 * actually consumes rather than a figure chosen to look right. This is the composition
 * `actionCellWidthStyle` in `ui/src/screens/userList/index.tsx` uses for the identical `SEL` cell -- one
 * idiom for one control across two screens, and the measurement behind the caret column was taken on
 * that screen's instance of it.
 *
 * ⚠️ Assumptions: the conformance floor is stated as an explicit alternative inside `max()` rather than
 * left to fall out of the arithmetic. The two terms clear it at the pinned theme, but that is a property
 * of a token value rather than of this expression, and a floor that holds only while a token keeps its
 * value is not a floor. `max()` also degrades safely: the padding custom property is scoped to component
 * class scopes rather than to the document root, so if it fails to resolve the `calc()` term is invalid
 * at computed-value time and the AA figure remains operative -- the control can never return to a
 * zero-width content box.
 *
 * ⚠️ Trade-offs: the reserved measure can exceed the content box its own column reserves. The selection
 * column reserves three character columns because its heading `Sel` is three characters wide
 * ({@link AUTH_SUMMARY_COLUMN_CHARACTERS}), and the table spends the global padding token on each cell,
 * so the cell's content box is those three columns while this control asks for two columns plus its own
 * control padding. The table already carries a horizontal scroll extent for its eight columns, so the
 * excess is spent there rather than clipping anything -- and the alternative, widening the column to
 * absorb the control's padding, would reserve a measure no field or heading in the contract declares.
 *
 * Assumptions: the conformance figure is a plain number rather than a token reference because it is a
 * CONFORMANCE threshold and not a design value -- it comes from a success criterion, so a theme change
 * must not move it. `TARGET_SIZE_AA_MINIMUM` is where it is declared, and this reads it.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {CSSProperties} The measure to spread onto the cell's own control.
 */
function selectionCellStyle(tokens: AntdCssVariables): CSSProperties {
  return {
    minInlineSize: `max(${String(TARGET_SIZE_AA_MINIMUM)}px, calc(${String(
      SELECTION_CELL_RESERVED_COLUMNS,
    )}ch + 2 * ${String(tokens.controlPaddingHorizontal)}))`,
  };
}

/**
 * Declared character width of each of the eight table columns.
 *
 * ⚠️ Purpose: browser validation measured this table at maximum internal scroll and found the pinned
 * leading block overlaying the column beside it with nothing reserved between them -- the `Date`
 * heading rendered as the single letter `e` and an originating time of `09:16:44` rendered as `16:44`,
 * which is the worst failure available in a table of authorization times because a clipped time still
 * reads as a whole one. The finding's own suggested remedy was declared column widths, and these are
 * they: with a width on every column the layout reserves each one instead of measuring it from
 * whatever text happens to be present, so the scroll extent is the sum of the contract's own widths.
 *
 * ⚠️ Assumptions: each entry is the LARGER of the heading's declared length and the datum's, because
 * a column has to hold both and the mapset does not always make them equal. `Sel` is three characters
 * over a one-character selector, `Type ` is five over a four-character type, and `A/D` and `STS` are
 * three each over single characters -- so sizing from the data alone would clip four of the eight
 * headings, and sizing from the headings alone would under-reserve none but would still be a second
 * rule to remember. Both figures are read from the two catalogs above rather than restated, so a
 * heading or a width corrected against the mapset changes the column in the same edit.
 */
const AUTH_SUMMARY_COLUMN_CHARACTERS = {
  /** `Sel` at three characters over `SEL0001`'s one. */
  selection: Math.max(
    AUTH_SUMMARY_COLUMN_HEADERS.selection.length,
    AUTH_SUMMARY_FIELD_WIDTHS.selection,
  ),
  /** Heading and datum agree at sixteen. */
  transactionId: Math.max(
    AUTH_SUMMARY_COLUMN_HEADERS.transactionId.length,
    AUTH_SUMMARY_FIELD_WIDTHS.rowTransactionId,
  ),
  /** Heading and datum agree at eight. */
  date: Math.max(AUTH_SUMMARY_COLUMN_HEADERS.date.length, AUTH_SUMMARY_FIELD_WIDTHS.rowDate),
  /** Heading and datum agree at eight. */
  time: Math.max(AUTH_SUMMARY_COLUMN_HEADERS.time.length, AUTH_SUMMARY_FIELD_WIDTHS.rowTime),
  /** `Type ` at five over a four-character type. */
  type: Math.max(AUTH_SUMMARY_COLUMN_HEADERS.type.length, AUTH_SUMMARY_FIELD_WIDTHS.rowType),
  /** `A/D` at three over a single character. */
  approval: Math.max(
    AUTH_SUMMARY_COLUMN_HEADERS.approval.length,
    AUTH_SUMMARY_FIELD_WIDTHS.rowApproval,
  ),
  /** `STS` at three over a single character. */
  status: Math.max(AUTH_SUMMARY_COLUMN_HEADERS.status.length, AUTH_SUMMARY_FIELD_WIDTHS.rowStatus),
  /** Heading and the edit mask agree at twelve. */
  amount: Math.max(AUTH_SUMMARY_COLUMN_HEADERS.amount.length, AUTH_SUMMARY_MONEY_PICTURE.width),
} as const;

/**
 * Builds the CSS length that reserves one column's declared characters plus its cell padding.
 *
 * ⚠️ Assumptions: the padding term is `2 * padding` because that is what the design system's own table
 * spends on a cell: antd 6 derives the table's `cellPaddingInline` from the global `padding` token, so
 * naming the global token here reserves exactly what the component consumes without hardcoding a
 * figure. Reserving the characters alone would leave every column short by its own padding and clip
 * the very headings this exists to protect.
 *
 * Assumptions: the character term is in `ch`, which is a DATA contract rather than a design value --
 * the same standing the amount cells and `MessageBand` give their own `ch` measures -- so it resolves
 * to no design token and needs none.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {(characters: number) => string} A function from declared characters to a CSS length.
 */
function columnWidth(tokens: AntdCssVariables): (characters: number) => string {
  /**
   * Turns one column's declared character width into the CSS length that column is given.
   *
   * Assumptions: a closure over the theme rather than a second parameter, so a column declaration at a
   * call site names only what the MAPSET declares -- its width in characters -- and cannot pass the
   * wrong theme by mistake. The padding term is the component's, read once above.
   * @param {number} characters - The width the mapset declares for that column, in characters.
   * @returns {string} The CSS length reserving those characters plus the cell padding antd spends.
   */
  return (characters: number): string =>
    `calc(${String(characters)}ch + 2 * ${String(tokens.padding)})`;
}

/**
 * Builds the eight columns of the authorization table, in the order the mapset paints them.
 *
 * Assumptions: eight columns and one heading each, taken verbatim from row 14 of the mapset, ordered
 * selection, transaction identifier, date, time, type, approved-or-declined, match status and amount --
 * `COPAU00.bms` L197 through L236. The order is part of what a returning operator reads, so it is the
 * map's rather than a rearrangement.
 *
 * Assumptions: row 15 of the mapset is NOT rendered. It paints seven dashed rules -- `'---'`,
 * `'----------------'` and so on at L237 through L276 -- whose entire purpose is to draw a rule under
 * the headings on a character terminal that has no borders. `Table` draws that rule itself, so
 * rendering the dashes would put a second one on screen as literal text.
 *
 * Assumptions: the fixed-pitch token is applied to every data column rather than to the amount alone,
 * because all seven carry fixed-width coded values -- a 15-character transaction identifier, two
 * eight-character composed stamps, a four-character type and three single characters -- and the token
 * mapping assigns `fontFamilyCode` to "fixed-pitch money and identifier columns". A proportional font
 * would let digits of different widths break the column alignment the terminal had.
 *
 * ⚠️ Refactoring Rationale: the leading column's control is INJECTED rather than composed here, and the
 * `Radio.Group` that used to own the selection is gone. The mapset settles which control belongs there:
 * `SEL0001` through `SEL0005` are `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1` with `COLOR=GREEN` and
 * `HILIGHT=UNDERLINE` (`COPAU00.bms` L277 to L282 and the four repeats), which is an unprotected
 * one-character ENTRY field -- so the screen's own row-22 sentence, `Type 'S' to View Authorization
 * details from the list`, is an instruction the control can now actually obey. A radio asked the
 * operator to click while the sentence told them to type, and it additionally made the source's
 * `'Invalid selection. Valid value is S'` arm (L327 to L330) unreachable, because a radio can only ever
 * supply the accepted character.
 *
 * Assumptions: the cell renderer is a PARAMETER because it needs the screen's own entry state, its
 * change handler and its per-row control registry, none of which belong in a column builder. This is
 * the shape `buildUserListColumns` in `ui/src/screens/userList/index.tsx` uses for the identical `SEL`
 * cell, so a reader meets one idiom rather than two.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @param {(row: PendingAuthListItem) => ReactElement} renderSelectionCell - Renders one row's
 *   one-character selection entry.
 * @returns {TableColumnsType<PendingAuthListItem>} The table columns, ready for `Table`.
 */
export function buildPendingAuthColumns(
  tokens: AntdCssVariables,
  renderSelectionCell: (row: PendingAuthListItem) => ReactElement,
): TableColumnsType<PendingAuthListItem> {
  const code: CSSProperties = {
    color: tokens[BMS_TEXT_COLOR_TOKENS.BLUE],
    fontFamily: tokens[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  const width = columnWidth(tokens);
  return [
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.selection,
      key: 'selection',
      fixed: 'left',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.selection),
      /**
       * Renders one row's selection entry, through the renderer the screen supplied.
       *
       * Assumptions: a control is rendered per DELIVERED row, and the row is selectable at all only
       * because it carries data. The source makes that structural: `INITIALIZE-AUTH-DATA` sets
       * `DFHBMPRO` on all five selectors before a page is built (`COPAUS0C.cbl` L611 to L661) and
       * `POPULATE-AUTH-LIST` sets `DFHBMUNP` on a selector only as it fills that row (L554, L566, L578,
       * L590, L602) -- so an empty row's selector is protected. Rendering per delivered row reproduces
       * that without a guard, because a row that was not delivered has no control.
       * @param {PendingAuthListItem} row - The authorization the cell acts on.
       * @returns {ReactElement} That row's selection entry.
       */
      render: (row: PendingAuthListItem): ReactElement => renderSelectionCell(row),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.transactionId,
      key: 'transactionId',
      fixed: 'left',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.transactionId),
      /**
       * Renders the acquirer's transaction identifier.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The identifier in the fixed-pitch token.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Typography.Text style={code}>{row.transactionId}</Typography.Text>
      ),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.date,
      key: 'authOrigDate',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.date),
      /**
       * Renders the originating date in the source screen's month-first form.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The composed date in the fixed-pitch token.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Typography.Text style={code}>{formatAuthOrigDate(row.authOrigDate)}</Typography.Text>
      ),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.time,
      key: 'authOrigTime',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.time),
      /**
       * Renders the originating time in the source screen's colon-separated form.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The composed time in the fixed-pitch token.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Typography.Text style={code}>{formatAuthOrigTime(row.authOrigTime)}</Typography.Text>
      ),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.type,
      key: 'authType',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.type),
      /**
       * Renders the authorization type code.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The four-character type, or empty text when the row carried none.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Typography.Text style={code}>{displayText(row.authType)}</Typography.Text>
      ),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.approval,
      key: 'approvalStatus',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.approval),
      /**
       * Renders the approved-or-declined character.
       *
       * Assumptions: the member is already the single derived character and is not re-derived here.
       * The source computes it at `COPAUS0C.cbl` L536 to L540, moving `'A'` when
       * `PA-AUTH-RESP-CODE = '00'` and `'D'` otherwise, and the service performs that derivation so
       * both its callers agree on it.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The single approval character in the fixed-pitch token.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Typography.Text style={code}>{row.approvalStatus}</Typography.Text>
      ),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.status,
      key: 'matchStatus',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.status),
      /**
       * Renders the match status character.
       *
       * Assumptions: the domain is CLOSED at four characters and is rendered as the stored character
       * rather than expanded to a word. `CIPAUDTY.cpy` L45 declares `PA-MATCH-STATUS PIC X(01)` with
       * exactly four condition names -- `'P'` pending at L46, `'D'` declined at L47, `'E'` pending and
       * expired at L48 and `'M'` matched at L49 -- and the map column is one character wide under a
       * heading that names the domain, so the stored character is what an operator reads there.
       * Expanding it would widen a one-character column and would put text on screen that no baseline
       * source declares.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The single status character in the fixed-pitch token.
       */
      render: (row: PendingAuthListItem): ReactElement => {
        const status: MatchStatus = row.matchStatus;
        return <Typography.Text style={code}>{status}</Typography.Text>;
      },
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.amount,
      key: 'amount',
      width: width(AUTH_SUMMARY_COLUMN_CHARACTERS.amount),
      /**
       * Renders the approved amount as exact decimal text.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The amount, right-aligned at the map's declared twelve characters.
       */
      render: (row: PendingAuthListItem): ReactElement =>
        moneyValue(row.amount, tokens, AUTH_SUMMARY_MONEY_PICTURE),
    },
  ];
}

/*
 * WHY : Refactoring Rationale: a frozen `SHELL_IDENTITY_SLOT` constant stood here for an identity-only
 *       publication, and both are withdrawn in favour of the single complete slot in the component body.
 *       Its reason for delegating the identity ALONE was that this mapset paints its own message field
 *       and its own legend, so delegating those would duplicate them -- and that is resolved by moving
 *       them rather than by withholding the slot: the row-23 line and the row-24 legend ARE delegated
 *       and the body's own copies are withdrawn with them, so exactly one element paints each row.
 */

/**
 * The pending-authorization summary screen.
 *
 * Purpose
 * -------
 * Scopes to one account, shows that account's summary panel and one page of five pending
 * authorizations, and opens a chosen authorization's detail screen. Carries the four attention
 * identifiers `COPAUS0C.cbl` L224 to L250 admits and no others.
 *
 * This screen holds NO session state
 * ---------------------------------
 * Assumptions: the baseline is pseudo-conversational and keeps its continuity in a passed commarea,
 * and none of that survives. Identity arrives in the validated token rather than in a field the client
 * echoes back, the selected authorization travels as a path parameter rather than in
 * `CDEMO-CPVS-PAU-SELECTED`, the next program is a client route rather than `CDEMO-TO-PROGRAM`, and the
 * re-entry discriminator `CDEMO-PGM-CONTEXT` -- with its `CDEMO-PGM-ENTER` and `CDEMO-PGM-REENTER`
 * condition names -- has no counterpart at all. A stateless handler answering with a field-error array
 * has no first-entry-versus-re-entry distinction to make, which is why the field highlight below is
 * driven purely by the response and by this turn's own validation rather than by a remembered turn
 * count as `app/cpy/CSSETATY.cpy` gates it.
 *
 * Chrome is part composed and part delegated
 * -----------------------------------------
 * Assumptions: this screen composes no chrome of its own and DELEGATES all three persistent zones to
 * the frame. `ui/src/layout/AppShell.tsx` is the frame, `ui/src/router.tsx` mounts it as the layout
 * route, and it paints each zone only for a screen that has published one -- so the one `useShellSlot`
 * call below carries the title band this mapset omits, the row-23 message line and the row-24 legend
 * together, and the body renders the mapset's own regions and nothing else.
 *
 * Refactoring Rationale: this section previously stated that `ui/src/layout/` held no shell component
 * and concluded that every screen must therefore compose all of its own chrome. The first half was
 * wrong -- `AppShell.tsx` sits in that directory and publishes `AppShell`, `useShellSlot` and
 * `publishShellSlot` -- and the second half was acted on selectively: the message band and the key
 * legend were composed here, and the title band was omitted on the separate ground that the two
 * `PIC X(40)` title constants belong to the band rather than to this mapset. Both grounds were
 * defensible and their combination was not, because with the shell mounted by no route the omitted band
 * was painted by nobody: rows 1 and 2 of this screen -- `TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`,
 * `TITLE02` and `CURTIME` -- rendered nothing at all. The remedy went further than the band: with the
 * shell mounted, the two locally composed bands would have doubled the shell's own, so they were
 * withdrawn and delegated with it.
 *
 * Assumptions: the title constants still belong to the band rather than to this module, which is why
 * the band is delegated rather than composed. What this mapset contributes to the heading is the row-3
 * sub-title, which is rendered below and stays here.
 * @returns {ReactElement} The composed screen.
 */
export function AuthSummaryScreen(): ReactElement {
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

  /*
   * WHY : ⚠️ Refactoring Rationale: this screen publishes ONE shell slot and reads the server instant
   *       ONCE, where four publications and three reads stood together. Each publication was a later
   *       generation of the same remedy -- the screen painted no title band, so nothing supplied the
   *       transaction identifier, the program name or a clock -- and every generation was kept: two
   *       identity-only publications, one adding the legend, and the complete one below. The last one
   *       wins for every member it names, so the earlier three added three hook calls and an appearance
   *       of disagreement about which zones this screen delegates.
   * WHY : Assumptions: the surviving publication is the LAST one, and it has to be: it names `bindings`
   *       and `invoke` from this screen's own `usePfKeys` call and the derived `bandNotice`, none of which
   *       exist this early. It delegates the identity, the paint instant, the row-23 message line and the
   *       key legend, and what stays in the body is the mapset's row-22 selection prompt.
   * WHY : Assumptions: the instant is read here and handed up rather than read inside the shell, because
   *       `ui/src/hooks/useServerInstant.ts` reads `ui/src/api/serverClock.ts` and the shell is required
   *       to carry no dependency on the API layer. Handing up nothing makes `ScreenHeader` fall back to
   *       the browser clock -- the divergence registered as D-7 -- so two screens in one frame would
   *       disagree about the time for no reason a reader could discover.
   */
  const paintedAt = useServerInstant();
  const { cssVar } = theme.useToken();

  const [accountIdEntry, setAccountIdEntry] = useState('');
  const [scopedAccountId, setScopedAccountId] = useState('');
  const [entryFault, setEntryFault] = useState<FieldValidationState | null>(null);
  /*
   * WHY : ⚠️ Refactoring Rationale: the selection is now the SET OF TYPED CELL ENTRIES, keyed by the
   *       row's own sealed selector, where it used to be one chosen row key. The mapset settles the
   *       shape: `SEL0001` through `SEL0005` are `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1` entry fields
   *       (`COPAU00.bms` L277 to L282 and the four repeats), and the reference reads whatever character
   *       each holds. A single chosen key cannot carry a character, so it could not reach the source's
   *       own refusal arm at `COPAUS0C.cbl` L327 -- `'Invalid selection. Valid value is S'` -- and it
   *       contradicted the row-22 sentence this screen paints, which tells the operator to TYPE.
   * WHY : Assumptions: keyed by the sealed selector rather than by row index, because an index
   *       re-associates every typed entry with a different authorization the moment a page turns.
   * WHY : Assumptions: entries survive only until the next turn. Every arm that paints a page clears
   *       them, which is `INITIALIZE-AUTH-DATA` moving `DFHBMPRO` into all five selectors before a page
   *       is built (L611 to L661).
   */
  const [selectionEntries, setSelectionEntries] = useState<Readonly<Record<string, string>>>({});
  /*
   * WHY : Assumptions: each row's control node is registered so a click anywhere in the row can place
   *       the CURSOR in that row's cell. A ref map rather than state, because focusing is an imperative
   *       act on a node and re-rendering on registration would serve nothing.
   */
  const selectionCellRefs = useRef(new Map<string, HTMLInputElement>());
  const [summary, setSummary] = useState<PendingAuthSummary | null>(null);
  const [serviceMessage, setServiceMessage] = useState<string | null>(null);
  /*
   * WHY : ⚠️ Assumptions: the read generation is a REF and not state, because nothing renders from it and
   *       a settlement must be able to read it synchronously, before the next render. A state value would
   *       be read from the closure of the render that opened the read, so every settlement would compare
   *       its own number against itself and every one of them would look current.
   */
  const readGeneration = useRef(0);
  /*
   * WHY : ⚠️ Purpose: the account a search has been DISPATCHED for and not yet settled, or `null`. This
   *       is what makes the resubmission guard and the busy affordance effective on the SAME TASK as the
   *       press. `browse.isLoading` is state and becomes true one render later, so six presses inside a
   *       single millisecond -- which browser validation measured, taking the request count from four to
   *       seven -- every one of them read `isLoading` as false.
   * WHY : Assumptions: it holds the ACCOUNT rather than a boolean, so the busy predicate can answer
   *       "is a search for the identifier now in the field outstanding" and not merely "is something
   *       outstanding". The difference is the correction path: an operator retyping a different account
   *       while the first read runs must still be able to submit it, and a boolean would refuse them.
   */
  const searchDispatched = useRef<string | null>(null);
  const [notice, setNotice] = useState<ScreenNotice | null>(null);

  useEffect(
    /**
     * Collects a fraud outcome the detail screen handed over, on mount and on every later retention.
     *
     * Assumptions: the effect runs ONCE -- its dependency list is empty -- because both mechanisms it
     * installs are independent of this screen's own state: the opening collection reads a retention that
     * already exists, and the subscription is what covers every later one. Re-running it on a state
     * change would unsubscribe and resubscribe on every turn for no gain.
     * @returns {() => void} The unsubscribe function React calls on unmount, so the registry holds no
     *   listener belonging to an unmounted screen.
     */
    () => {
      /**
       * Paints a fraud outcome the detail screen had nobody left to report.
       *
       * Assumptions: the claim is COMPARED rather than collected unconditionally, because
       * `subscribeToRetainedOutcomes` tells every listener about every retention -- a screen that collected
       * whatever had just been retained would take another pair's hand-over and paint a sentence about a
       * record it never showed.
       *
       * Assumptions: only `COMPLETED` is painted. The detail screen retains under that discriminator for
       * both a committed write and a refused one, because it has already reduced its failures to sentences
       * and records why at its own retention site; `FAILED` carries a raised error rather than a sentence,
       * and reducing one here would be a second implementation of that screen's own failure wording.
       * @param {string} claim - The claim just retained, or this screen's own on the opening check.
       * @returns {void} Nothing; the sentence is placed in the message band as a side effect.
       */
      function collect(claim: string): void {
        if (claim !== AUTH_FRAUD_TRANSITION_CLAIM) {
          return;
        }
        const handed = claimRetainedOutcome<FraudTransitionHandover>(AUTH_FRAUD_TRANSITION_CLAIM);
        if (handed === undefined || handed.settled !== 'COMPLETED') {
          return;
        }
        setNotice({ message: handed.value.text, severity: handed.value.severity });
      }

      /*
       * WHY : ⚠️ Assumptions: BOTH mechanisms are used, and neither alone is sufficient. Collecting once
       *       on mount misses the ordinary case -- `ui/src/api/client.ts` records that the measured writes
       *       landed 7 to 12 ms AFTER the navigation, so this screen is already mounted when the outcome
       *       comes to exist and a mount-only check looks too early. Subscribing alone misses the opposite
       *       case, a write that settled while the route was still changing, which is retained before any
       *       listener of this screen exists. The pair covers both, and collection REMOVES the entry, so
       *       the two cannot paint one outcome twice.
       */
      collect(AUTH_FRAUD_TRANSITION_CLAIM);
      return subscribeToRetainedOutcomes(collect);
    },
    [],
  );

  const readPage = useCallback(
    /**
     * Reads one page of authorizations, and captures the summary that arrives beside it.
     *
     * Assumptions: the operation answers ONE envelope carrying the account summary, the page and an
     * optional screen sentence, so the summary is captured here as a side effect and only the page
     * envelope is returned -- which is all the paging hook's contract accepts. Splitting the summary
     * into a second request would let the panel and the rows disagree about the account between two
     * reads, and the source reads both in one turn for exactly that reason.
     *
     * Assumptions: the cursor and the direction are OMITTED together rather than sent as undefined.
     * The client refuses a direction with no cursor by raising locally, and `exactOptionalPropertyTypes`
     * makes an explicit `undefined` a different thing from an absent member, so the opening read is
     * built as a scope-only query.
     * @param {PagedQueryRequest} request - The cursor to step from and the direction to step in; the
     *   cursor is null for the opening read.
     * @returns {Promise<PageResponse<PendingAuthListItem>>} The page envelope for the hook to hold.
     */
    async (request: PagedQueryRequest): Promise<PageResponse<PendingAuthListItem>> => {
      const query: PendingAuthListQuery =
        request.cursor === null
          ? { accountId: scopedAccountId }
          : { accountId: scopedAccountId, cursor: request.cursor, direction: request.direction };
      const generation = readGeneration.current + 1;

      readGeneration.current = generation;

      const response = await listPendingAuthorizations(query);

      /*
       * WHY : ⚠️ Refactoring Rationale: the two side effects are GUARDED, and they were not. This function
       *       is handed to the paging hook, which discards a page envelope belonging to a superseded read
       *       -- but these two writes happen HERE, before the envelope is returned, so they landed
       *       whatever the hook then decided about the rows. The consequence is a disclosure and not a
       *       stale view: the panel above the table renders the account holder's name, address and
       *       balances, so an account-A response settling after an account-B page put account A's holder
       *       and balances above account B's rows, attributed to account B.
       * WHY : Alternatives Considered: returning a compound result so the hook could apply all three
       *       together. Rejected because the hook's `fetchPage` contract is a page envelope and nothing
       *       else -- widening it would put a member on every browse for the benefit of one -- and
       *       because a generation guard is what the hook itself uses, so the two agree by construction
       *       rather than by coincidence.
       * WHY : Assumptions: the generation is compared rather than the scope. A scope comparison would
       *       miss the case that matters most: two reads under the SAME account, where the later one
       *       carries a page the earlier one's summary would still overwrite.
       */
      if (readGeneration.current === generation) {
        setSummary(response.summary);
        setServiceMessage(response.screenMessage);
        /*
         * WHY : ⚠️ Purpose: the identifier the service scoped the read to is ADOPTED into the field it
         *       was submitted from, and it used to reach no field at all -- `summary.accountId` was
         *       returned on every listing and displayed nowhere, which browser validation recorded as
         *       returned-and-never-rendered. This is the reference's own mechanism rather than an
         *       addition: `ACCTIDO` and `ACCTIDI` are the SAME map field, and `COPAUS0C.cbl` L228 to
         *       L232 moves `WS-ACCT-ID` back into `ACCTIDO` on the Enter arm before the map is sent, so
         *       the terminal repaints the entry position with the identifier the turn actually ran
         *       under. Echoing it here is what closes the gap between what an operator typed and what
         *       the service resolved -- a leading-zero or padding difference is otherwise invisible.
         * WHY : Assumptions: the updater form is used so the comparison sees the CURRENT entry text
         *       rather than the text this closure captured. A read settles after an await, and the field
         *       is editable throughout it.
         * WHY : ⚠️ Trade-offs: the echo is REFUSED when the entry no longer equals the scope this read
         *       ran under, which is the case where the operator has typed since submitting. The terminal
         *       cannot reach that case -- CICS locks the keyboard for the turn -- so there is no
         *       reference behaviour to transcribe, and overwriting an in-progress correction with a
         *       resolved identifier from a read being abandoned would destroy typing that the guard in
         *       `submitEntry` exists to let through.
         */
        setAccountIdEntry(
          /**
           * Adopts the identifier the read ran under, unless the operator has typed since submitting.
           * @param {string} entered - The entry field's CURRENT text, which the updater form supplies
           *   rather than the text this closure captured.
           * @returns {string} The service's resolved identifier when the field still holds the scope
           *   this read ran under, and the operator's own text when it does not.
           */
          (entered: string): string =>
            entered === scopedAccountId ? response.summary.accountId : entered,
        );
      }
      return response.page;
    },
    [scopedAccountId],
  );

  /*
   * WHY : Alternatives Considered: the backward step is the hook's `prevPage`, and the source's
   *       twenty-slot key stack is deliberately NOT reproduced. `COPAUS0C.cbl` L120 declares
   *       `CDEMO-CPVS-PAUKEY-PREV-PG PIC X(08) OCCURS 20 TIMES` and PF7 reads
   *       `PAUKEY-PREV-PG(page)` at L368 before running `PROCESS-PAGE-FORWARD`, which re-reads
   *       FORWARD from that remembered key -- repositioning at it rather than past it, via the
   *       `IF EIBAID = DFHPF7 AND WS-IDX = 1` arm at L425 -- and stores each page's first key back
   *       into the array when the row index reaches two, at L436 to L441. Three mappings were
   *       available. (1) Reproduce the stack client-side, pushing `firstKey` per forward page and
   *       popping per backward page. (2) Offset paging. (3) A native backward keyset read. The third
   *       is chosen, and the decisive evidence is WHY the stack exists at all: `GET-AUTHORIZATIONS`
   *       issues `EXEC DLI GNP` at L461 -- Get Next within Parent -- and IMS DL/I is forward-only,
   *       with no read-previous verb. The array is therefore an artifact of the datastore's
   *       navigation, not a business rule: it lets a forward-only database simulate a backward step
   *       by remembering where each page began. PostgreSQL has the verb the source lacked, so
   *       `prevPage` reads keyed strictly before `firstKey` in descending order and returns exactly
   *       the rows a stack pop would have returned. It is not an approximation of the stack -- it is
   *       the mechanism the stack was standing in for.
   * WHY : Trade-offs: the two questions a reader will ask about the missing array are answered rather
   *       than left open. Its BOUND: there is none, because there is no stack to bound. The source can
   *       page back at most twenty pages before the array is exhausted, so the target is strictly more
   *       capable here, and the divergence is a capability gained rather than behaviour changed. Its
   *       BOTTOM: expressed by `hasPrev`, which the hook derives as the page ordinal exceeding one with
   *       a leading cursor present -- the same test as the source's `IF CDEMO-CPVS-PAGE-NUM > 1` at
   *       L365 -- so the verbatim top-of-page sentence appears on exactly the occasions that test
   *       fails.
   * WHY : Assumptions: `PageResponse` is used exactly as declared and is not widened. Its four members
   *       are the items, the two sealed cursors and forward availability; backward availability is the
   *       caller's own ordinal by design, which is what the hook holds, so nothing here needs a member
   *       the envelope does not publish.
   */
  const browse = usePagedQuery<PendingAuthListItem>({
    pageSize: AUTH_SUMMARY_PAGE_SIZE,
    fetchPage: readPage,
    enabled: scopedAccountId !== '',
    resetKey: scopedAccountId,
  });

  /**
   * Withdraws every read outstanding under the scope being left, and clears what described it.
   *
   * ⚠️ Refactoring Rationale: this was open-coded on the scope-CHANGE path and MISSING from the
   * refusal path, and the omission is a disclosure. A turn whose account identifier is blank or
   * non-numeric clears the scope and the panel, but a `readPage` opened under the PREVIOUS account was
   * still outstanding, and its guard compares the generation it captured against a counter nothing had
   * advanced -- so it settled as current and put the previous account holder's name, address and
   * balances back above an emptied entry field, after the screen had been scoped away from that account.
   * Naming the pair means neither half can be written without the other.
   *
   * Assumptions: the generation is advanced BEFORE the state is cleared, so there is no instant at which
   * an outstanding read is still admissible and the panel is already empty. Both happen in one event
   * handler, so nothing renders between them, but the order states the intent for the next reader.
   *
   * Assumptions: the scope itself is not set here. The two callers leave the scope in different places
   * -- a refusal empties it, a change moves it to the entered account -- and folding that into this
   * helper would make it decide something only its caller knows.
   * @returns {void} Nothing; nothing outstanding can repaint and no panel describes the account left.
   */
  function withdrawScopedReads(): void {
    readGeneration.current += 1;
    setSummary(null);
    setServiceMessage(null);
  }

  /**
   * Records that the dispatched search has settled, whichever way it settled.
   *
   * Assumptions: cleared on BOTH outcomes, because the flag says a turn is outstanding and a failed
   * turn is not outstanding. Clearing only on success would leave the Enter key reported busy -- and
   * therefore silently declined by `usePfKeys` -- for the rest of the visit after one refused read,
   * which is the hazard `PfKeyHandlerEntry.busy` records for a screen that forgets to clear it.
   *
   * Assumptions: it does not compare the account it was dispatched for. Two presses that collapsed
   * share one settlement and both continuations run, so a comparison would have to decide which of two
   * identical accounts cleared the flag; there is only ever one search outstanding under this ref,
   * because the guard above declines a second one.
   * @returns {void} Nothing; the outstanding-search flag is cleared as a side effect.
   */
  function concludeSearchTurn(): void {
    searchDispatched.current = null;
  }

  /**
   * Reports whether a search for the account NOW IN THE ENTRY FIELD is still outstanding.
   *
   * Purpose: this is what the Enter key's busy affordance and its silent decline are driven from. A
   * measured double-submit on this screen produced one identical request per press with the pressed
   * control left indistinguishable from idle -- the only busy affordance in the frame was the table's
   * own spinner, which is nowhere near the entry field or the key legend.
   *
   * ⚠️ Assumptions: the test is scoped to the entered account and is NOT a bare "is a read running".
   * `usePfKeys` declines a busy key outright, so an unscoped flag would refuse the correction path this
   * screen deliberately keeps open: `src/screens/screenSelectionCarriers.test.tsx` types a DIFFERENT
   * account and presses Enter while the first read is still unsettled, and requires that second read to
   * be issued. Scoping by the field's current contents lets the correction through and declines only
   * the identical resubmission.
   *
   * Assumptions: the ref is consulted BESIDE the browse's own flag rather than instead of it. The ref
   * covers the same task as the press, which state cannot; the browse's flag covers the read the
   * scope-change path starts, which this screen does not dispatch and so never records in the ref.
   * @returns {boolean} `true` while this screen is waiting for the entered account's search.
   */
  function searchIsOutstanding(): boolean {
    if (accountIdEntry !== scopedAccountId) {
      return false;
    }
    return browse.isLoading || searchDispatched.current !== null;
  }

  /**
   * Runs the source program's Enter arm.
   *
   * Assumptions: the four steps run in the source's order and short-circuit exactly where it does,
   * following `PROCESS-ENTER-KEY` at `COPAUS0C.cbl` L261 to L338. A blank entry is refused before the
   * numeric test, so a blank is never reported as non-numeric. Either refusal clears the scope, which
   * reproduces the source moving `LOW-VALUES` into `WS-ACCT-ID` at L265 and L274 -- `GATHER-DETAILS`
   * then reads nothing because its own `IF WS-ACCT-ID NOT = LOW-VALUES` guard at L349 is not entered.
   * A selection that opens the detail screen returns immediately, because the source reaches it by
   * `EXEC CICS XCTL` at L322 to L325, which does not come back and so never runs the `GATHER-DETAILS`
   * that follows at L337. A refused selection character does fall through to that read, which is why
   * the scope is still set on that path.
   * @returns {void} Nothing; the page arrives through the paging hook.
   */
  function submitEntry(): void {
    setNotice(null);
    const fault = classifyAccountIdEntry(accountIdEntry);
    if (fault !== null) {
      setEntryFault(fault);
      setNotice({ message: accountIdRefusal(fault), severity: AUTH_SUMMARY_MESSAGE_SEVERITY });
      withdrawScopedReads();
      setScopedAccountId('');
      return;
    }
    setEntryFault(null);

    /*
     * WHY : ⚠️ Refactoring Rationale: the selection column is a one-character `Input` per row, and the
     *       note that stood here choosing a `Radio` over exactly that is withdrawn. Its decisive claim
     *       was that "the design-system mapping assigns this role to a radio or a checkbox", which
     *       misreads the mapping it cites: AAP section 0.3.2 gives a radio or checkbox to a "selection
     *       marker column", and it gives an `Input` with `maxLength` from the copybook width to an
     *       "editable field (`ATTRB=(FSET,NORM,UNPROT)`)". `SEL0001` is the second of those --
     *       `ATTRB=(FSET,NORM,UNPROT)`, `LENGTH=1`, `COLOR=GREEN`, `HILIGHT=UNDERLINE` at
     *       `COPAU00.bms` L277 to L282 -- so the mapping was pointing the other way all along.
     * WHY : ⚠️ Refactoring Rationale: its second claim, that a free-text column "would admit two
     *       simultaneously non-blank selectors, which is the state the ordered evaluation exists to
     *       resolve", is answered rather than denied: two non-blank selectors is a state the TERMINAL
     *       admits, and the ordered evaluation is the source's resolution of it, transcribed at
     *       {@link reduceAuthRowSelection}. Making the control unable to reach that state does not
     *       reproduce the resolution -- it deletes the case, and with it the reachability of the
     *       refusal at L327.
     * WHY : Assumptions: a `Checkbox` remains rejected, and for the reason the old note gave: it
     *       expresses only that a row is chosen, so it can no more carry the typed character than a
     *       radio can.
     * WHY : Assumptions: `resolveSelectionAction` stays the domain authority -- including the lowercase
     *       arm the source adds at L316 -- and is now reachable in both directions, because the control
     *       can supply a character it refuses.
     */
    const selection = reduceAuthRowSelection(browse.items, selectionEntries);
    const action = resolveSelectionAction(selection.flag, selection.key);
    if (action === 'open' && selection.key !== null) {
      navigateSafely(navigate, authorizationDetailPath(selection.key));
      return;
    }
    if (action === 'invalid') {
      setNotice({
        message: SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S,
        severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
      });
    }

    /*
     * WHY : Refactoring Rationale: on the paths that fall through to the read -- nothing selected, or
     *       a selection character the source refuses -- Enter REWINDS the browse to the first page and
     *       drops the selection, rather than leaving the operator where they had paged to. The
     *       accepted-selection path never arrives here because it returned above, which is faithful:
     *       the source leaves by `EXEC CICS XCTL` at L322 and so never reaches its own rewind either.
     *       The source is explicit about both halves of the rewind: `GATHER-DETAILS` opens with `MOVE 0 TO
     *       CDEMO-CPVS-PAGE-NUM` at `COPAUS0C.cbl` L347 before it re-reads, and it performs
     *       `INITIALIZE-AUTH-DATA` at L353, which moves `DFHBMPRO` into all five selectors and
     *       spaces into all five row families (L608 to L662). So an Enter turn on page three of a
     *       result set returns page one with nothing selected. Browser validation caught the
     *       divergence: keying Enter with an UNCHANGED account identifier left `resetKey` equal to
     *       its previous value, so the hook's restart effect had no reason to fire and the turn was
     *       inert -- no read, no rewind, the third page still on screen. Alternatives Considered:
     *       making the reset key a submission counter so every Enter changed it, rejected because
     *       the key's declared meaning is the IDENTITY of the query being browsed, and overloading
     *       it with a nonce would make an unrelated re-render indistinguishable from a new query.
     *       Calling `reset` on the unchanged path and letting the key change carry the other is the
     *       narrower fix: each path performs exactly one read, and neither double-fetches.
     */
    setSelectionEntries(NO_SELECTION_ENTRIES);
    if (accountIdEntry === scopedAccountId) {
      /*
       * WHY : ⚠️ Refactoring Rationale: an identical resubmission is COLLAPSED while a read for the
       *       same account is still outstanding, and it was not. Browser validation counted three
       *       activations of the Enter control producing three identical `POST /authorizations/search`
       *       requests, because `browse.isLoading` reached only the table's own spinner and nothing on
       *       the path that issues the read -- so an operator with no visible acknowledgement pressed
       *       again, and each press called `reset` unconditionally. The terminal cannot reach this
       *       state at all: CICS locks the keyboard for the duration of a task, so a second Enter
       *       during a turn is not delivered. Collapsing the duplicate is the closest the browser gets
       *       to that lock without taking a control away.
       * WHY : ⚠️ Assumptions: the guard is scoped to the UNCHANGED account and deliberately does not
       *       gate the whole arm. A second submission naming a DIFFERENT account must still be issued
       *       while the first is in flight, because that is how an operator corrects a mistyped
       *       identifier without waiting, and the superseded response is already discarded by the
       *       generation guard in `readPage`. Gating the arm as a whole would break that correction
       *       path -- `src/screens/screenSelectionCarriers.test.tsx` measures exactly it, issuing the
       *       second read while the first is unsettled and asserting the first cannot repaint.
       * WHY : ⚠️ Refactoring Rationale: the dispatch IS now wrapped in `withoutConcurrentDuplicate`, and
       *       the note that stood here rejecting it is withdrawn as having answered a different
       *       question. It read: "that helper resolves `Promise<void>`, and `usePagedQuery`'s
       *       `fetchPage` must resolve the page envelope, so a joining caller would hand the hook
       *       nothing where a page belongs". That is true of wrapping `fetchPage`, which is not what is
       *       wrapped: `browse.reset` is `() => Promise<void>` since the hook began returning its turns
       *       (`usePagedQuery.ts` L1400 to L1420), which is EXACTLY the `attempt` signature the helper
       *       declares. Nothing is handed to the hook at all -- the page still arrives through
       *       `fetchPage` untouched, and what collapses is this screen's DISPATCH of the turn. The
       *       second half of the old note, that the helper is "declared for DESTRUCTIVE requests", is
       *       also withdrawn: its own documentation states the key is a method and target and the guard
       *       is applied per operation, and a duplicate read is not made safe by being a read -- the
       *       measured count was seven requests for one operator intention.
       * WHY : Assumptions: this is not the only guard on the turn and does not need to be. The hook
       *       collapses identical turns internally under `BROWSE {identity}.{epoch} {direction}
       *       {cursor}`, so a duplicate would already be joined one level down. What the screen-level
       *       key adds is a collapse expressed in terms this screen can state and test -- the ACCOUNT
       *       being searched -- and, through {@link searchDispatched}, a synchronous in-flight fact the
       *       hook's private map cannot supply and the busy affordance needs.
       * WHY : Alternatives Considered: disabling the Enter binding while the read runs. Rejected for
       *       the reason recorded at this screen's `usePfKeys` call -- `usePfKeys` answers a disabled
       *       handler with `CCDA-MSG-INVALID-KEY`, so an operator pressing Enter during a read would be
       *       told the key is invalid, which is both wrong and a verbatim string used to mean something
       *       else. Reporting the key BUSY is the available alternative and is what this screen now
       *       does: `usePfKeys` declines a busy key silently, which is the terminal's input-inhibit
       *       behaviour rather than a message.
       */
      if (browse.isLoading) {
        return;
      }

      /*
       * WHY : ⚠️ Purpose: an identical resubmission of an account ALREADY ANSWERED issues no read at
       *       all, and this is the half the in-flight guard above cannot reach. Browser validation
       *       measured three presses on an unchanged account taking the request count from one to four
       *       -- each press arrived after the previous read had settled, so `isLoading` was false for
       *       every one of them and each called `reset` again.
       * WHY : ⚠️ Assumptions: three conditions together, and every one of them is load-bearing.
       *       `summary !== null` is this screen's ANSWERED fact: `readPage` sets it only from a
       *       current-generation settlement and `withdrawScopedReads` clears it on both the refusal and
       *       the scope-change paths, so it can never describe an account other than the one on
       *       display. The browse's own ordinal cannot stand in for it -- the hook initialises
       *       `pageNumber` to one BEFORE any read (`usePagedQuery.ts` L922), so the ordinal alone reads
       *       "page one" on a screen that has never read anything, and the opening turn would be
       *       declined.
       * WHY : ⚠️ Assumptions: `pageNumber === AUTH_SUMMARY_FIRST_PAGE` is what preserves the REWIND.
       *       Enter on page three of the same account must still return page one, because
       *       `GATHER-DETAILS` opens with `MOVE 0 TO CDEMO-CPVS-PAGE-NUM` at `COPAUS0C.cbl` L347 before
       *       it re-reads -- so the guard declines only the turn that would redisplay what is already
       *       displayed.
       * WHY : Assumptions: `!browse.isFailed` is what preserves the RETRY. A read that failed leaves a
       *       previously arrived summary standing, so without this test the operator's next Enter would
       *       be declined and the failure would be unrecoverable without leaving the screen.
       * WHY : ⚠️ Trade-offs: the divergence this creates is stated rather than left to be discovered.
       *       The terminal's Enter always re-read, so an authorization inserted by another operator
       *       appeared on the next Enter; here, an Enter on page one of an answered account paints the
       *       screen again without asking the service, so that insert is not picked up by THAT key. It
       *       is picked up by either paging key, both of which issue real reads, and by re-scoping the
       *       account. The exchange is deliberate: a refresh an operator did not ask for is worth less
       *       than not sending six requests for one intention, and the measured defect was the second.
       */
      if (summary !== null && !browse.isFailed && browse.pageNumber === AUTH_SUMMARY_FIRST_PAGE) {
        return;
      }

      searchDispatched.current = accountIdEntry;
      withoutConcurrentDuplicate(authSummarySearchKey(accountIdEntry), browse.reset).then(
        concludeSearchTurn,
        concludeSearchTurn,
      );
      return;
    }

    /*
     * WHY : ⚠️ Assumptions: the panel and the service sentence are CLEARED as the scope changes, and the
     *       generation is advanced with them. Without both, a turn that scopes to a second account leaves
     *       the first account's holder name, address and balances on display until the new read settles --
     *       so the panel names one account while the entry field and, moments later, the rows name
     *       another. Advancing the generation is the other half: it withdraws any read still outstanding
     *       under the previous scope, so that read cannot repaint what this clears. The source has no
     *       equivalent moment because it composes the whole screen once per turn, after its read.
     * WHY : Refactoring Rationale: the pair is performed by `withdrawScopedReads` rather than open-coded
     *       here, because writing it in one place is what left the refusal path above without it.
     */
    withdrawScopedReads();
    setScopedAccountId(accountIdEntry);
  }

  /**
   * Runs the source program's backward paging arm.
   *
   * Assumptions: the selection is cleared before the step, because the source clears all five
   * selectors and all five row-families whenever it builds a page -- `INITIALIZE-AUTH-DATA` at
   * `COPAUS0C.cbl` L608 to L662, performed from the PF7 arm at L377. Leaving a selection standing would
   * let an Enter turn on the new page open a record the operator can no longer see.
   *
   * Assumptions: the guard branches on the browse's published position, {@link BACKWARD_EXHAUSTED},
   * rather than on a backward-availability flag, so the dead-end case is named rather than inferred.
   * @returns {void} Nothing; either a page arrives or the boundary sentence is shown.
   */
  function pageBackward(): void {
    setNotice(null);
    setSelectionEntries(NO_SELECTION_ENTRIES);
    if (BACKWARD_EXHAUSTED.includes(browse.boundary)) {
      setNotice({
        message: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
        severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
      });
      return;
    }
    browse.prevPage().then(ignoreSettledBrowseTurn, ignoreSettledBrowseTurn);
  }

  /**
   * Runs the source program's forward paging arm.
   *
   * Assumptions: availability is read from the browse's own published POSITION rather than counted from
   * the rows on screen, which is how the source settles it too -- `PROCESS-PAGE-FORWARD` issues one
   * extra read beyond the five it displays and sets `NEXT-PAGE-YES` from whether that read found a
   * record, at `COPAUS0C.cbl` L445 to L452, and the PF8 arm branches on that indicator at L404. A full
   * page of five is therefore not evidence that a sixth record exists. {@link FORWARD_EXHAUSTED} names
   * the three positions from which the sentence is the answer.
   * @returns {void} Nothing; either a page arrives or the boundary sentence is shown.
   */
  function pageForward(): void {
    setNotice(null);
    setSelectionEntries(NO_SELECTION_ENTRIES);
    if (FORWARD_EXHAUSTED.includes(browse.boundary)) {
      setNotice({
        message: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
        severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
      });
      return;
    }
    browse.nextPage().then(ignoreSettledBrowseTurn, ignoreSettledBrowseTurn);
  }

  /**
   * Runs the source program's back arm.
   *
   * Assumptions: the destination is the main menu unconditionally, because the source's PF3 arm sets
   * it itself -- L235 to L238 move `WS-PGM-MENU` into the next-program field before transferring, so
   * the sign-on fallback that `RETURN-TO-PREV-SCREEN` applies to a blank field at L668 to L670 cannot
   * be reached from this key.
   * @returns {void} Nothing; navigation is performed as a side effect.
   */
  function returnToMenu(): void {
    navigateSafely(navigate, AUTH_SUMMARY_BACK_ROUTE);
  }

  /*
   * WHY : Assumptions: exactly the four attention identifiers the source admits are bound, and every
   *       one of them carries a label, so all four appear in the key bar as well as answering a real
   *       key press. `COPAUS0C.cbl` L224 to L250 evaluates `DFHENTER`, `DFHPF3`, `DFHPF7` and
   *       `DFHPF8` and sends the invalid-key message for anything else, so PF4, PF5 and PF12 are
   *       absent by transcription rather than by oversight. Binding real key presses is a fidelity
   *       requirement and not an enhancement: the 3270 original had no pointing device, so an
   *       operator who never leaves the keyboard has to reach every action.
   * WHY : Alternatives Considered: neither paging key is bound `disabled`, and disabling them on
   *       `hasPrev` and `hasNext` was the alternative. It is rejected because it would SUPPRESS the
   *       two sentences the source shows at those boundaries. `usePfKeys` answers a disabled handler
   *       by reporting a rejection carrying the invalid-key message, so a disabled PF7 on the first
   *       page would show "invalid key" where the source shows "You are already at the top of the
   *       page..." -- replacing a correct sentence with a wrong one, and losing a verbatim string
   *       Transformation Rule T8 requires. The source refuses neither key: both arms re-send the
   *       screen with a message (L380 to L384 and L408 to L411). The availability tests still bind to
   *       the keyset envelope rather than to a page number -- `pageBackward` and `pageForward` branch
   *       on the published position, {@link BACKWARD_EXHAUSTED} and {@link FORWARD_EXHAUSTED} -- so
   *       the decision is made from the cursor state exactly as required; it just decides which of two
   *       outcomes happens rather than whether the key responds at all.
   * WHY : Assumptions: the unmapped-key message is NOT re-emitted here. `usePfKeys` owns
   *       `CCDA-MSG-INVALID-KEY` and hands it back on the rejection, so this screen shows what the
   *       hook decided rather than a second copy of the same constant. That matters on this screen
   *       specifically, because unlike the card browse -- which coerces an unrecognised key into its
   *       Enter arm -- `COPAUS0C.cbl` L245 to L249 does move that message into the message field, so
   *       the rejection must be surfaced and not swallowed.
   */
  /*
   * WHY : ⚠️ Assumptions: the three keys that leave or step are stated `risk: 'read-only'`, and ENTER
   *       deliberately states NO risk. The classification follows what each key's LABEL says it does
   *       rather than which attention identifier carries it, and nothing on this screen writes at all:
   *       `COPAUS0C` issues no `EXEC DLI ISRT`, `REPL` or `DLET` and no `EXEC SQL`, so there is no
   *       mutating or destructive key here.
   * WHY : ⚠️ Trade-offs: leaving ENTER unclassified is the one place where saying nothing is the
   *       accurate statement, and it was arrived at by measurement. Declaring it read-only compiles and
   *       is true of what the key does, and it changes the paint: `pfKeyEmphasisFor` resolves a stated
   *       read-only key to `type="default"`, so the screen's submit control lost its primary emphasis
   *       and `src/test/authSummary.test.tsx` failed with "expected 'ant-btn-default' to contain
   *       'ant-btn-primary'". That emphasis is not this screen's to withdraw -- the migration plan's
   *       design-system section maps the action keys with `type="primary"` for ENTER and PF5 and
   *       `type="default"` for PF3, PF4 and PF12, which `PRIMARY_ACTION_AIDS` transcribes -- and the
   *       fallback for an unstated risk is exactly the mechanism that preserves it. So the risk channel
   *       is used where it adds a fact and left alone where it would overrule the plan.
   * WHY : ⚠️ Assumptions: the busy channel is opened on Enter, F7 and F8 -- the three keys that own a
   *       read -- and deliberately NOT on F3. A screen must stay escapable while a read is outstanding,
   *       which is the property `PfKeyHandlerEntry.busy` itself records, and F3 mutates nothing and
   *       waits for nothing.
   * WHY : ⚠️ Trade-offs: a busy key is declined SILENTLY by `usePfKeys`, so this is the one place where
   *       the scoping of {@link searchIsOutstanding} matters to a reader. Reporting Enter busy on a bare
   *       `browse.isLoading` would refuse the correction an operator makes by retyping a different
   *       account while the first read is outstanding -- the path `readPage`'s generation guard exists
   *       to make safe, and the path `src/screens/screenSelectionCarriers.test.tsx` measures. Enter is
   *       therefore busy only for a search of the identifier now in the field. The paging keys need no
   *       such scoping: a step is only ever taken from the page on display.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        onInvoke: submitEntry,
        label: AUTH_SUMMARY_KEY_LABELS.ENTER,
        busy: searchIsOutstanding,
      },
      PFK03: { onInvoke: returnToMenu, label: AUTH_SUMMARY_KEY_LABELS.PFK03, risk: 'read-only' },
      PFK07: {
        onInvoke: pageBackward,
        label: AUTH_SUMMARY_KEY_LABELS.PFK07,
        risk: 'read-only',
        busy: browse.isLoading,
      },
      PFK08: {
        onInvoke: pageForward,
        label: AUTH_SUMMARY_KEY_LABELS.PFK08,
        risk: 'read-only',
        busy: browse.isLoading,
      },
    },
    {
      onInvalidKey:
        /**
         * Shows the rejection the hook decided on, unchanged.
         * @param {PfKeyRejection} rejection - The refused identifier, the hook's own message and the
         *   appearance it chose for it.
         * @returns {void} Nothing; the sentence is placed in the message band as a side effect.
         */
        (rejection: PfKeyRejection) => {
          setNotice({ message: rejection.message, severity: rejection.severity });
        },
    },
  );

  /*
   * WHY : Assumptions: three sources can put a sentence in the one message field this mapset paints,
   *       and their precedence is decided here because the field can hold one at a time. This turn's
   *       own outcome wins, then a failed read, then the sentence the service sent -- which matches
   *       the source, where `WS-MESSAGE` is assigned by whichever arm ran last before
   *       `SEND-PAULST-SCREEN` moves it into `ERRMSGO` at L692. Every arm clears the notice before it
   *       decides, so a stale sentence cannot outlive the turn that produced it. The service's own
   *       `screenMessage` carries the same catalog strings this screen selects at a boundary, so the
   *       two cannot contradict each other: the local one appears when no read is issued, the service
   *       one when a page arrives carrying it.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: the sentence is composed from `browse.failure` and no longer from
   *       `browse.error`. The hook publishes both -- the problem document for a screen that marks
   *       fields from it, and the classified failure beside it -- and only the second carries the
   *       transport judgement `describeListingFailure` now reads, so a document alone could not
   *       distinguish a momentary outage from a permanent fault. `browse.error` is still read, once,
   *       and only for what it alone carries: the `fieldErrors` array that marks the account entry.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: a failed browse whose failure carries no classification now
   *       falls back to the unexpected-condition sentence, where it previously produced NO sentence
   *       at all. `describeListingFailure` reads `browse.failure`, and the hook populates that member
   *       only for a rejection the transport module classified; a rejection that arrives as a bare
   *       problem document populates `browse.error` and leaves `failure` null, so the describer
   *       returned null, the two later alternatives were also null on a failed read, and the band
   *       stayed empty. Measured: a listing rejected with a problem document rendered no element with
   *       role `alert` anywhere on the screen -- the operator pressed Enter, the rows did not arrive,
   *       and the screen said nothing about why.
   *
   *       Assumptions: `isFailed` is the flag that means the read did not deliver, and it is true for
   *       every rejection whatever its shape, which is why the invariant is anchored on it rather than
   *       on either payload. The invariant this states is the one the reference keeps without trying:
   *       `COPAUS0C.cbl` L692 moves a sentence into `ERRMSGO` on every path that fails to show rows,
   *       so there is no arm of that program in which the browse fails silently.
   *
   *       Alternatives Considered: widening the describer to accept the problem document as well was
   *       rejected because its whole purpose is to read the transport judgement, and a document alone
   *       cannot distinguish a momentary outage from a permanent fault -- it would have to answer with
   *       the same unclassified sentence this fallback supplies, one call deeper and less visibly.
   *       Trade-offs: the fallback is the abend sentence rather than an authored one, which says less
   *       than a classified failure would; that is honest, because in this arm nothing classified it.
   */
  const listingFailure =
    describeListingFailure(browse.failure) ??
    (browse.isFailed
      ? {
          message: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
          severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
        }
      : null);
  const serviceNotice: ScreenNotice | null =
    serviceMessage === null || serviceMessage.trim() === ''
      ? null
      : { message: serviceMessage, severity: AUTH_SUMMARY_MESSAGE_SEVERITY };
  const bandNotice = notice ?? listingFailure ?? serviceNotice;

  /*
   * WHY : Refactoring Rationale: the three persistent zones are DELEGATED to the single mounted
   *       `AppShell` instead of composed below, which is what gives this screen the title band its
   *       mapset paints at rows 1 and 2. Those six fields were previously absent from the rendered
   *       screen: this module documented that the band was the shell's to paint, and no shell was
   *       mounted and nothing published to it, so the transaction identifier, program name and clock
   *       reached no display at all.
   * WHY : ⚠️ Assumptions: the resolved `bindings` and `invoke` from this screen's own `usePfKeys`
   *       call are handed over rather than re-derived by the shell, so what is delegated is the legend to
   *       RENDER while this screen keeps the keyboard: an activation of a rendered control is forwarded
   *       straight back to `invoke`. The claim that stood here, that the shell "binds its own sign-off key
   *       only while NO screen has published one" and is made to stand down by publishing, is withdrawn.
   *       The shell installs NO keyboard listener at all and offers sign-off as a rendered control, for
   *       the reason recorded at `SHELL_SIGN_OFF_LABEL`, so this screen's listener is the only one
   *       installed either way -- publishing buys the painted legend, not the ownership.
   * WHY : Assumptions: no `legendColor` is delegated, because this mapset paints its row-24 legend
   *       `COLOR=YELLOW` at L507 to L512, which is the slot's own default and the majority across the
   *       mapset population. Stating it would suggest this screen departs from the majority when it does
   *       not.
   */
  useShellSlot({
    screen: { transactionId: AUTH_SUMMARY_TRANSACTION_ID, programName: AUTH_SUMMARY_PROGRAM_NAME },
    now: paintedAt,
    /*
     * WHY : ⚠️ Purpose: the mapset's row-22 field is now DELEGATED, and it previously reached no visible
     *       surface at all. `COPAU00.bms` L497 to L502 declares a `LENGTH=52` `COLOR=NEUTRAL` field at
     *       POS=(22,12) carrying `Type 'S' to View Authorization details from the list`, and this screen
     *       carried that string only as the selection group's `aria-label` -- so a sighted operator was
     *       never told how to open a row, on a screen whose one purpose is opening a row. The string
     *       itself was already verbatim; what was missing was a channel to put it on.
     * WHY : ⚠️ Assumptions: it goes on the `information` channel and not in `message.text`, because
     *       `MESSAGE_BAND_CHANNELS` in `ui/src/layout/MessageBand.tsx` decides that by TENSE and this
     *       sentence is standing guidance -- it is as true before the turn as after it, so it is row
     *       22's `INFOMSG` and never row 23's `ERRMSG`. Putting it in `message.text` would also make it
     *       compete with the turn's outcome for a field that holds one sentence at a time, and the
     *       outcome would overwrite the guidance on every refusal.
     * WHY : Assumptions: no severity is named, so the slot's own default applies -- `neutral`, which
     *       `defaultMessageBandSeverity` reads from the channel's recorded `COLOR=NEUTRAL`. That is
     *       exactly this field's declared operand, so restating it here would add a second authority
     *       for one measured value.
     * WHY : Alternatives Considered: publishing it only while rows are on display. Rejected because the
     *       reference paints it unconditionally -- the field carries an `INITIAL=` literal and is never
     *       written by `COPAUS0C`, so it is on the glass from the first send, including the opening turn
     *       with no account scoped. Making it conditional would withhold the instruction precisely when
     *       an operator most needs it.
     * WHY : ⚠️ Assumptions: the member is nested INSIDE `message` and not beside it, because that is
     *       where `ShellSlot` declares it -- `ShellInformationSlot` deliberately carries no `mapset` of
     *       its own, since the display width is a property of the mapset a screen stands in for and one
     *       screen must not be able to claim two widths. Publishing it as a sibling of `message` was the
     *       first attempt and rendered nothing at all: the shell paints the row-22 zone only from
     *       `message.information`, so an excess property beside `message` was silently ignored.
     */
    message: {
      text: bandNotice?.message ?? null,
      severity: bandNotice?.severity ?? AUTH_SUMMARY_MESSAGE_SEVERITY,
      mapset: AUTH_SUMMARY_MAPSET,
      information: { text: AUTH_SUMMARY_SELECTION_PROMPT },
    },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  /*
   * WHY : Assumptions: the field highlight is driven by this turn's validation first and by the
   *       response body second, and by nothing else. The baseline reaches the same appearance through
   *       the templated copybook `app/cpy/CSSETATY.cpy` L17 to L27, which moves `DFHRED` into a
   *       field's colour attribute when its validation flag is not-OK or blank and additionally writes
   *       a literal asterisk into the field in the blank case -- but it gates the whole substitution on
   *       the pseudo-conversational re-entry flag. The target has no such flag, as the note on this
   *       component records, so the highlight is a pure function of the current answer. The asterisk is
   *       preserved for the blank case, placed as the control's suffix so it appears inside the field
   *       where the copybook puts it without becoming part of the value the operator typed.
   * WHY : Trade-offs: the STATE and the beneath-the-field SENTENCE come from different places, and
   *       separating them is what stops one refusal being printed twice. A locally detected refusal
   *       reaches the operator through the message band alone, because that is the only place the
   *       source puts it -- `PROCESS-ENTER-KEY` moves the sentence into `WS-MESSAGE` and the highlight
   *       copybook moves only a colour and an asterisk into the field, never the text. Rendering it as
   *       help as well would show the same sentence in two places on a screen that paints one message
   *       field. What DOES render beneath the control is a per-field sentence the SERVICE raised, which
   *       has no other surface: the band is already carrying that response's screen-level sentence, so
   *       without the help text the field-specific one would be lost. Both halves of Rule T7 are
   *       therefore satisfied without duplication -- the field carries its state, and a field-scoped
   *       message is rendered where it belongs.
   */
  const serviceFieldError = accountIdFieldError(browse.error);
  const fieldState: FieldValidationState | null = entryFault ?? serviceFieldError?.state ?? null;

  /*
   * WHY : ⚠️ Refactoring Rationale: the refused appearance is ADOPTED from `fieldRefusalRendering` and
   *       is no longer composed here. What stood in its place was a lone `suffix` carrying
   *       `FIELD_ERROR_TOKENS.blankMarker` as a bare string, which was two defects at once. It gave the
   *       marker no `BLANK_FIELD_MARKER_TEST_ID`, so the one glyph a reader cannot otherwise
   *       distinguish from the design system's always-on required asterisk was unidentifiable in the
   *       DOM -- the same glyph standing for "this field must be filled" and for "this field was left
   *       blank on the turn just taken". And it applied no colour at all, so the `NOT_OK` state -- a
   *       non-numeric entry -- was left with antd's border alone where `app/cpy/CSSETATY.cpy` L18 to
   *       L26 moves `DFHRED` into the field's colour attribute for BOTH refused states and the
   *       asterisk for the blank one only. The helper carries that asymmetry.
   * WHY : Assumptions: `?? undefined` converts this screen's `null` for "no refusal" into the absence
   *       the helper's signature declares; `exactOptionalPropertyTypes` makes the two different states
   *       and the helper reads an absent argument as "no refusal to render".
   */
  const entryRefusal = fieldRefusalRendering(fieldState ?? undefined, cssVar);

  const selectionCellMeasure = selectionCellStyle(cssVar);

  /**
   * Renders one row's selection cell: the one-character entry the reference's `SEL` field accepts.
   *
   * ⚠️ Assumptions: the cell is LABELLED for assistive technology because the terminal identified it by
   * POSITION, and position is exactly what design gap G1 gives up. The visible heading is three
   * characters, so the accessible name pairs it with the row's own transaction identifier -- which is
   * the value the mapset paints unedited beside it (`TRNID01I PIC X(16)`), so a reader hearing the name
   * and a reader seeing the row are told the same thing.
   *
   * ⚠️ Assumptions: `maxLength` is the mapset's declared width and not a chosen limit. `SEL0001` is
   * `LENGTH=1`, and the terminal could hold exactly one character there -- so a two-character entry is
   * a state the reference cannot reach, and admitting one here would let an operator type something the
   * source's `EVALUATE` could never receive.
   *
   * Assumptions: the value is read from the entry map with an empty-string fallback rather than left
   * uncontrolled, so a page turn that clears the map visibly empties every cell. An uncontrolled input
   * would keep whatever was typed into it across the turn the reference clears.
   * @param {PendingAuthListItem} row - The authorization the cell acts on.
   * @returns {ReactElement} That row's one-character selection entry.
   */
  function renderSelectionCell(row: PendingAuthListItem): ReactElement {
    return (
      <Input
        aria-label={selectionCellLabel(row.transactionId)}
        /*
         * WHY : ⚠️ Refactoring Rationale: the cell gains an identifier because the browser found it had
         *       none. DevTools raised "A form field element should have an id or name attribute" against
         *       five nodes, which is one per row of this page, and these cells were the only controls in
         *       the application carrying neither an `id` nor a `name`. Both sibling browse screens
         *       already publish one per row and neither is reported, so this was the outlier.
         * WHY : Assumptions: the accessible NAME was never the missing piece and is unchanged. A name is
         *       what an assistive technology announces; an identifier is what makes the platform treat
         *       the control as a real field for autofill and for a `label` association. The cell had the
         *       first and lacked the second, so only the second is added.
         */
        id={selectionCellId(row.transactionId)}
        maxLength={AUTH_SUMMARY_FIELD_WIDTHS.selection}
        onChange={
          /**
           * Records the character typed beside this row, leaving every other row's entry alone.
           * @param {object} event - The change event antd forwards.
           * @param {object} event.target - The control the event came from.
           * @param {string} event.target.value - The entry as it now stands.
           * @returns {void} Nothing; the entry is recorded as a side effect.
           */
          (event: { target: { value: string } }): void => {
            const { value } = event.target;
            setSelectionEntries(
              /**
               * Replaces this row's entry, keyed by its sealed selector.
               * @param {Readonly<Record<string, string>>} current - Entries so far.
               * @returns {Readonly<Record<string, string>>} Entries with this row's replaced.
               */
              (current: Readonly<Record<string, string>>): Readonly<Record<string, string>> => ({
                ...current,
                [row.key]: value,
              }),
            );
          }
        }
        ref={
          /**
           * Records this row's control so a row click can place the cursor in it.
           * @param {InputRef | null} instance - The control instance, or `null` on unmount.
           * @returns {void} Nothing; the node is registered, or its entry removed on unmount.
           */
          (instance: InputRef | null): void => {
            const node = instance?.input ?? null;

            /*
             * WHY : Assumptions: the entry is DELETED on unmount rather than left holding `null`,
             *       because the map outlives a page: the five rows of the page just left would
             *       otherwise stay in it forever, and a click on a row whose sealed selector happened
             *       to repeat would resolve to a detached node.
             */
            if (node === null) {
              selectionCellRefs.current.delete(row.key);
              return;
            }

            selectionCellRefs.current.set(row.key, node);
          }
        }
        /*
         * WHY : ⚠️ Assumptions: the measure is spread onto the DESIGN-SYSTEM CONTROL and not onto a
         *       wrapper, because the padding custom property the expression reads resolves on
         *       `.ant-input` and returns the empty string on an arbitrary element -- the same measured
         *       constraint `ui/src/layout/recordLayout.ts` records for the declared-width ceiling.
         */
        style={selectionCellMeasure}
        value={selectionEntries[row.key] ?? ''}
      />
    );
  }

  return (
    <Flex vertical gap="large">
      {/*
       * ⚠️ Refactoring Rationale: the rank comes from `ui/src/layout/ScreenTitle.tsx` and this note
       * previously stated the defect as though it were the design: that the heading is `level={3})`
       * because "a screen's own title sits one level above the shared title band". It sits BELOW it. The
       * band is painted on rows 1 and 2 of every mapset including the four extension mapsets, and this
       * sub-title on row 4 of this one, so the band is the page's title and this is the section's -- and
       * a rank-3 caption above a rank-4 band told an operator navigating by heading the opposite. The
       * sub-title is painted `COLOR=NEUTRAL` at `COPAU00.bms` L75, which the theme bridge resolves to
       * `colorTextSecondary`; the size and line height now come from the bridge entries the shared
       * component applies rather than from the rank, which is what lets the rank be chosen for the
       * outline without moving the appearance.
       */}
      <ScreenTitle style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] }}>
        {AUTH_SUMMARY_SUBTITLE}
      </ScreenTitle>
      {/*
       * WHY : Alternatives Considered: `component={false}` renders NO form element, and the
       *       alternative was an ordinary form with `onFinish` carrying the Enter arm. It is rejected
       *       because this screen already has an Enter path: `usePfKeys` binds the identifier to
       *       `submitEntry`, and a text input inside a real form also submits it on Enter, so the arm
       *       would run twice on one key press -- issuing two reads and, on a selected row, navigating
       *       from underneath the first. Dropping the element keeps `Form.Item`'s label association and
       *       validation display, which is all this region needs from it.
       */}
      <Form component={false}>
        {/*
         * Assumptions: `colon={false}` because the label already ends in one. The mapset paints
         * `'Search Acct Id:'` at L79 to L83 with its colon inside the `INITIAL=` literal, so letting
         * antd append its own would render two.
         */}
        {/*
         * WHY : ⚠️ Refactoring Rationale: the help sentence is wrapped by `fieldErrorHelp`, which gives it a
         *       stable identifier the control points at with `aria-describedby`; it used to be passed as a
         *       bare string. antd renders `help` in a container of its own with no relationship to the
         *       input, so the sentence was on screen beside the control and absent from the control's
         *       accessible description -- a screen-reader user reached a field marked as refused with no
         *       statement of what was wrong with it. The same two helpers do this for every field on the
         *       sign-on, account-view and account-update screens, so the association is one mechanism
         *       rather than one per screen.
         * WHY : Assumptions: the spread form is used because antd's own prop types admit `help` being
         *       ABSENT and not `help` being `undefined`, and the two are different states to a component
         *       that tests for the property.
         */}
        <Form.Item
          colon={false}
          {...(serviceFieldError === null
            ? {}
            : { help: fieldErrorHelp(ACCOUNT_ID_INPUT_ID, serviceFieldError.message) })}
          htmlFor={ACCOUNT_ID_INPUT_ID}
          label={
            <Typography.Text
              id={ACCOUNT_ID_LABEL_ID}
              style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] }}
            >
              {AUTH_SUMMARY_LABELS.searchAccountId}
            </Typography.Text>
          }
          validateStatus={fieldState === null ? '' : 'error'}
        >
          {/*
           * WHY : Assumptions: NO `autoFocus`, and its absence is fidelity rather than an omission.
           *       A BMS field claims the opening cursor with the `IC` attribute, and `COPAU00.bms`
           *       carries no `IC` anywhere -- verified across all 104 field definitions, where the only
           *       occurrence of those two letters in the whole file is inside the Apache licence URL on
           *       L11. This is one of the few mapsets with no initial-cursor field, so adding one would
           *       move the cursor to a position the source screen never put it and would take focus
           *       from a screen reader's own entry point on arrival.
           * WHY : Assumptions: `maxLength` is the copybook picture width and not a chosen limit.
           *       `ACCTID` is declared `LENGTH=11` at L84 to L88 and the symbolic map declares
           *       `ACCTIDI PIC X(11)`, so eleven is the field's contract. It caps the control at eleven
           *       characters and is NOT the whole of the rule: `classifyAccountIdEntry` requires exactly
           *       eleven digits, because `IS NOT NUMERIC` runs over the space-padded field and so refuses
           *       a short entry as well as a non-numeric one. The two work together -- the control stops
           *       a twelfth character being typed, the classification stops a tenth-character entry being
           *       sent.
           * WHY : Assumptions: `HILIGHT=UNDERLINE` on that field needs no token. It is the 3270's
           *       editable-field affordance, which the `Input` border already carries structurally --
           *       registered design gap **G4** -- so inventing an underline style would draw the
           *       affordance twice.
           */}
          {/*
           * WHY : ⚠️ Refactoring Rationale: the control now STATES that a search is outstanding, and
           *       nothing on this path did. `browse.isLoading` reached the table's spinner alone, so
           *       the field an operator had just submitted from carried no indication at all -- which
           *       is what produced the three identical searches measured in a browser. `busyProps`
           *       emits `aria-busy` only while a read is running and the empty object otherwise, which
           *       is why it is spread unconditionally.
           * WHY : Alternatives Considered: `disabled` on this control while the read runs, which would
           *       be the stronger signal. Rejected because it would forbid the correction the guard in
           *       `submitEntry` deliberately allows -- retyping a different account identifier while
           *       the first read is still outstanding -- and an operator who mistyped would have to
           *       wait for a read they no longer want.
           * WHY : ⚠️ Refactoring Rationale: this control is now PAIRED with `busyAnnouncement`, and the
           *       note that stood here rejecting the pairing is withdrawn. It was accurate when written
           *       -- "that helper requires a sentence and the baseline has none to carry" -- and it is
           *       no longer true of the codebase: `ui/src/messages/messages.ts` now declares
           *       `REQUEST_IN_PROGRESS` as an AUTHORED operator sentence, registered and width-checked
           *       beside the transcribed catalog rather than mixed into it. `aria-busy` states the fact
           *       to a control an operator has already found; the announcement states it to one who is
           *       waiting and is looking nowhere in particular, which is the case the measured
           *       double-submit was in. Rule T8 is not breached because the sentence makes no claim to
           *       be the reference's: the terminal inhibited the keyboard and said nothing, so there is
           *       no mainframe string to be faithful to and the authored one is declared as authored.
           */}
          {/*
           * WHY : ⚠️ Refactoring Rationale: the control is sized from the width its own PICTURE clause
           *       declares, where it used to take the form's full measure. The consequence was not
           *       cosmetic: the blank-field marker this control raises is an antd `suffix`, so it
           *       renders at the control's RIGHT edge -- at a wide viewport that put the asterisk
           *       reporting an empty field the better part of the viewport away from the empty field,
           *       where an operator reading the entry position never looks. Sizing the control to its
           *       eleven characters brings the marker back beside the value it describes, which is
           *       where `app/cpy/CSSETATY.cpy` L24 puts it -- the copybook moves the asterisk INTO the
           *       field, so adjacency is part of the mechanism rather than a preference.
           * WHY : Assumptions: the width style is spread FIRST and the refusal style second, so a
           *       refusal's colour can never be overwritten by sizing. The two carry disjoint
           *       properties today -- measure against colour -- and the ordering keeps that
           *       independence if either helper gains a property later.
           * WHY : Assumptions: it is spread onto the `Input` itself rather than onto a wrapper, because
           *       the measure is expressed in the design system's own horizontal padding token and
           *       that custom property resolves on `.ant-input`; on a plain wrapper it resolves to
           *       nothing and the whole `calc` is discarded.
           */}
          <Input
            {...fieldAriaProps(ACCOUNT_ID_INPUT_ID, {
              invalid: fieldState !== null,
              hasError: serviceFieldError !== null,
              hasHint: false,
            })}
            {...busyProps(browse.isLoading)}
            aria-labelledby={ACCOUNT_ID_LABEL_ID}
            id={ACCOUNT_ID_INPUT_ID}
            inputMode="numeric"
            maxLength={AUTH_SUMMARY_FIELD_WIDTHS.accountId}
            onChange={
              /**
               * Records the account identifier as it is entered.
               * @param {object} event - The change event antd forwards.
               * @param {object} event.target - The control the event came from.
               * @param {string} event.target.value - The entry as it now stands.
               * @returns {void} Nothing; the entry is recorded as a side effect.
               */
              (event: { target: { value: string } }) => {
                setAccountIdEntry(event.target.value);
              }
            }
            {...(entryRefusal.suffix === undefined ? {} : { suffix: entryRefusal.suffix })}
            /*
             * WHY : ⚠️ Refactoring Rationale: the marker slot is declared to the measure, and only on the
             *       turn the marker is rendered. With a suffix present the design system sizes the affix
             *       WRAPPER, whose space the value and the slot then share, so a maximum computed for the
             *       value alone leaves the value short by whatever the slot takes -- measured on a sibling
             *       screen's two-character field as a record key that rendered as one glyph and a sliver.
             *       Assumptions: the allowance is CONDITIONAL on the very test that spreads the suffix
             *       above, so an accepted entry keeps exactly the eleven-column ceiling it has always had.
             */
            style={{
              ...copybookFieldWidthStyle(
                AUTH_SUMMARY_FIELD_WIDTHS.accountId,
                cssVar,
                entryRefusal.suffix === undefined ? 0 : BLANK_FIELD_MARKER_CHARACTERS,
              ),
              ...entryRefusal.style,
            }}
            value={accountIdEntry}
          />
        </Form.Item>
      </Form>
      {/*
       * WHY : ⚠️ Purpose: the outstanding read is ANNOUNCED, and nothing announced it. The measured
       *       defect is not that the screen was slow -- it is that an operator with no acknowledgement
       *       pressed Enter again, and again, and the request count went from one to seven. The guard in
       *       `submitEntry` now collapses those presses, and this is the other half of the same fix: a
       *       statement that the turn was received, so there is nothing to press again for.
       * WHY : ⚠️ Assumptions: it is mounted UNCONDITIONALLY and carries the empty string while idle,
       *       which is load-bearing rather than tidy. A live region has to exist before its contents
       *       change for a screen reader to announce the change; mounting the element together with the
       *       sentence would insert both at once and the announcement would be missed on exactly the
       *       occasion it is for.
       * WHY : Assumptions: it is driven by `browse.isLoading` and not by {@link searchIsOutstanding},
       *       so a paging step announces itself too -- both keys own a read, and an operator waiting on
       *       F8 is in the same position as one waiting on Enter. The ref that scopes the key's own
       *       decline is deliberately not read here: a ref changing schedules no render, so a paint
       *       driven from it would announce whatever the previous render happened to see.
       */}
      {busyAnnouncement(browse.isLoading ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * WHY : Assumptions: the panel is rendered only once a summary has arrived, because the source
       *       has nothing to paint before then either -- `GATHER-DETAILS` reads the account, customer
       *       and summary segment only when an account identifier is present (L349 to L357), and the
       *       map's own initial state is `LOW-VALUES` moved over the whole output area at L195. An
       *       empty bordered panel of twelve blank cells would claim the account had been read and
       *       had no values, which is a different statement from not having been read.
       * WHY : Assumptions: `colon={false}` for the same reason as the entry label -- all twelve
       *       labelled entries take their colon from the mapset's own `INITIAL=` literal, including
       *       the two that differ from each other in the space before it.
       */}
      {summary !== null && (
        <Descriptions
          bordered
          colon={false}
          column={RECORD_VIEW_COLUMNS}
          items={buildAuthSummaryDescriptions(summary, cssVar)}
        />
      )}
      {/*
       * WHY : Alternatives Considered: `pagination={false}`, disabling antd's own pager, because the
       *       server pages by KEY and offset paging is the alternative. Offset paging is rejected on
       *       correctness rather than taste: under concurrent inserts an offset skips and repeats rows,
       *       so an operator stepping forward can miss an authorization entirely and see another twice,
       *       whereas reading from the last key cannot. The source pages by key for the same reason,
       *       carrying `CDEMO-CPVS-PAUKEY-LAST` across the turn rather than a row number
       *       (`COPAUS0C.cbl` L121, L391 to L394). Leaving antd's pager on would additionally show a
       *       total-page count the envelope cannot supply.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the `Radio.Group` that used to wrap this table is GONE, and its
       *       reasoning is answered rather than dropped. It argued that the five selectors are one set
       *       and that a group gives them one tab stop with arrow traversal, which is true of a radio
       *       group and was a genuine improvement over five independent radios. What it could not do is
       *       obey the screen's own instruction: `COPAU00.bms` L497 paints `Type 'S' to View
       *       Authorization details from the list` and L277 to L282 declares each selector
       *       `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1` -- an unprotected one-character entry field. A
       *       radio asked the operator to click while the sentence told them to type, and it made the
       *       source's own `'Invalid selection. Valid value is S'` arm unreachable because a radio can
       *       only ever supply the accepted character. Single-select-first-wins is preserved where the
       *       source puts it, in {@link reduceAuthRowSelection}, rather than in the control.
       * WHY : ⚠️ Trade-offs: the keyboard traversal changes from one tab stop with arrow keys to five tab
       *       stops, one per row, and that is the more faithful of the two rather than a cost. A 3270
       *       moves the cursor between UNPROTECTED FIELDS on the tab key, and these five are unprotected
       *       fields -- so five stops is what the terminal had. Each stop is named `Sel <transaction
       *       id>`, so what it announces identifies the row, and `ui/src/screens/userList/index.tsx`
       *       reaches its own ten `SEL` cells exactly this way.
       * WHY : Alternatives Considered: keeping the radios and rewriting the row-22 sentence to say
       *       "choose a row". Rejected outright: that sentence is a mapset `INITIAL=` literal and Rule
       *       T8 carries user-visible strings with a mainframe source VERBATIM, so making the control
       *       agree with the sentence was the only direction available.
       * WHY : Alternatives Considered: `Table`'s built-in `rowSelection`. Rejected for the reason it was
       *       rejected before -- it renders its own leading column with its own heading, and the mapset
       *       declares that column and its `'Sel'` heading itself at L197 to L201 -- and now for a
       *       second: it carries no character, so it cannot express what a `LENGTH=1` field holds.
       */}
      <>
        {/*
         * WHY : ⚠️ Refactoring Rationale: the table scrolls horizontally and its two identifying columns
         *       are pinned, where it had no narrow-screen policy at all. Eight columns whose contents are
         *       fixed-width by contract -- a 15-character transaction identifier, two 8-character stamps,
         *       a 4-character type, two single characters and a 12-character edited amount -- cannot be
         *       narrowed by wrapping without breaking the column geometry the edit masks produce, so at a
         *       phone width the table used to push the whole page wider than the viewport and the leading
         *       columns went off-screen with it.
         * WHY : Assumptions: `x: 'max-content'` rather than a pixel width, because the sum of eight
         *       fixed-width columns is a property of the CONTENT and stating it as a number here would be
         *       a second, drifting copy of widths the column definitions already carry.
         * WHY : Assumptions: the pinned pair is the selection control and the transaction identifier --
         *       the control that acts on a row and the value that names it -- so a row remains both
         *       identifiable and selectable while its later columns are scrolled to. Pinning more would
         *       leave too little scrollable width to be worth scrolling on the narrow viewport this
         *       exists for.
         * WHY : Alternatives Considered: a stacked card representation below the medium breakpoint, which
         *       the review offered as the alternative. Rejected because it abandons the row-and-column
         *       reading order the mapset paints and that design gap G1 commits to preserving, and because
         *       it would mean two renderings of one table to keep in step.
         */}
        {/*
         * WHY : ⚠️ Purpose: `onRow` gives each row the affordance it did not have. Browser validation
         *       measured `cursor: auto` on these rows both at rest and hovered, on a table whose whole
         *       purpose is choosing a row -- so nothing about a row said it could be acted on, and a
         *       reviewer's only clue was the small control in its leading column.
         * WHY : ⚠️ Assumptions: a row click places the CURSOR in that row's selection cell and types
         *       nothing into it. The reference separates choosing a row from acting on it and the
         *       separation is load-bearing: `PROCESS-ENTER-KEY` reads the selection characters and only
         *       then transfers control (`COPAUS0C.cbl` L288 to L330), so a mis-aimed click costs a
         *       cursor move and never a navigation. Writing `'S'` into the cell on a click would put the
         *       operator one Enter away from opening a record they did not choose.
         * WHY : ⚠️ Refactoring Rationale: this used to SELECT the clicked row, which was available while
         *       the control was a radio and is not now -- a one-character field holds a character, and
         *       the only character a click could supply is the one that opens the record. Moving the
         *       cursor is what the pointer can honestly do for a typed field, and it is what
         *       `ui/src/screens/userList/index.tsx` does for the identical cell.
         * WHY : ⚠️ Assumptions: no `tabIndex` is put on the row, and its absence is deliberate rather
         *       than an omission. Each row now carries a focusable, named control in its leading column,
         *       so the keyboard route through the rows exists -- five tab stops, each announcing `Sel`
         *       with the row's own transaction identifier -- and a focusable row would double every one
         *       of those stops with an element that has no accessible name.
         */}
        <Table<PendingAuthListItem>
          columns={buildPendingAuthColumns(cssVar, renderSelectionCell)}
          dataSource={browse.items}
          loading={browse.isLoading}
          onRow={
            /**
             * Makes a row's whole area reach that row's selection cell, and say so under the pointer.
             * @param {PendingAuthListItem} row - The authorization the row lists.
             * @returns {{ onClick: () => void; style: CSSProperties }} The row's handler and style.
             */
            (row: PendingAuthListItem): { onClick: () => void; style: CSSProperties } => ({
              /**
               * Places the cursor in the clicked row's selection cell, changing no value.
               * @returns {void} Nothing; focus moves as a side effect.
               */
              onClick: (): void => {
                selectionCellRefs.current.get(row.key)?.focus();
              },
              style: ROW_AFFORDANCE_STYLE,
            })
          }
          pagination={false}
          scroll={AUTH_SUMMARY_TABLE_SCROLL}
          rowKey={
            /**
             * Uses each row's own sealed selector as its reconciliation identity.
             *
             * Assumptions: the selector is unique per row by construction, so nothing needs to be
             * composed from other members. It is the same token the detail route carries and the same
             * one the selection control binds to, which is what keeps a selection and a navigation
             * addressing the same record.
             * @param {PendingAuthListItem} row - One listed authorization.
             * @returns {string} That row's sealed selector.
             */
            (row: PendingAuthListItem): string => row.key
          }
        />
      </>
      {/*
       * WHY : ⚠️ Refactoring Rationale: the mapset's row-22 prompt is no longer COMPOSED here. It used
       *       to close the body as a `Typography.Text strong`, and the consequence is the one a
       *       cross-screen review measured on a sibling: a message line composed inside `<main>` renders
       *       below the fold at every width, some 200 pixels away from the shell's own reserved band,
       *       so the terminal's two adjacent rows became two zones at opposite ends of a scroll. The
       *       string is now delegated on `message.information` in the `useShellSlot` call above, which
       *       puts it in the row-22 band beside the row-23 one exactly as the mapset paints them.
       * WHY : Assumptions: nothing about its appearance is lost by delegating. The field is painted
       *       `ATTRB=(ASKIP,BRT) COLOR=NEUTRAL` at `COPAU00.bms` L497 to L502, and the band's own
       *       `information` channel resolves to `colorTextSecondary` with strong weight -- brightness
       *       carried as WEIGHT, the measured resolution for all 37 bright fields in the base mapset
       *       population -- so the delegated rendering reproduces both operands without this screen
       *       naming either.
       * WHY : Assumptions: the string is still this screen's `aria-label` on the selection group, and
       *       that is not a duplicate of the band. One names a control for assistive technology, the
       *       other is a painted field; `src/screens/authSummary/authSummary.test.tsx` L572 finds the
       *       group by that name, and removing it would break a contract another suite holds.
       */}
      {/*
       * Assumptions: no `legendColor` is passed, because this mapset paints its row-24 legend
       * `COLOR=YELLOW` at L507 to L512, which is the bar's own default and the majority across the
       * mapset population. Passing it explicitly would state a value that is already in force and
       * would suggest this screen departs from the majority when it does not.
       */}
    </Flex>
  );
}

/*
 * WHY : Refactoring Rationale: this module publishes the component under its NAME ONLY, and the
 *       default export that used to sit here has been removed rather than kept alongside it. The
 *       argument for publishing both was that a route could then be declared as
 *       `lazy(() => import('./screens/<name>'))` with no adapter -- but no route is declared that way
 *       anywhere, so the second key had no caller, and AAP section 0.6.2.1 fixes the import discipline
 *       for this tree as named imports with the named-to-default adapter held in `ui/src/router.tsx`.
 *       Two keys for one component also make a screen reachable by two spellings, so a reader cannot
 *       tell from an import which convention this tree follows.
 */
