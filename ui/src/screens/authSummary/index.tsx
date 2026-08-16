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
 * failure source is the listing request, which the shared client normalises into an `ApiError` that
 * {@link describeListingFailure} turns into a message-band sentence.
 *
 * Where this screen's text comes from, and why it is not all in one place
 * ---------------------------------------------------------------------
 * Assumptions: the two halves of this screen's text have two different owners, and the boundary is
 * drawn by `ui/src/messages/messages.ts` itself. That catalog owns every string the baseline holds as
 * a copybook constant or a program literal, and its own file overview excludes "the static text
 * PAINTED BY THE BMS MAPS", assigning `app/bms/*.bms` to `ui/src/screens/**`. So the five sentences
 * this screen can move into its message field are imported from the catalog, while the sub-title, the
 * fourteen panel labels, the eight column headings, the row-22 prompt and the row-24 key legend are
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

import { Descriptions, Flex, Form, Input, Radio, Table, Typography, theme } from 'antd';
import type { DescriptionsProps, RadioChangeEvent, TableColumnsType } from 'antd';
import { useCallback, useRef, useState } from 'react';
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
import type { PagedQueryRequest } from '../../hooks/usePagedQuery';
import { useServerInstant } from '../../hooks/useServerInstant';
import { PROGRAM_MESSAGES, SHARED_MESSAGES } from '../../messages/messages';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import {
  BMS_TEXT_COLOR_TOKENS,
  DESIGN_GAPS,
  FIELD_ERROR_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';
import { RECORD_VIEW_COLUMNS } from '../../layout/recordLayout';
import { VISUALLY_HIDDEN_STYLE, fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
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

/** Accessible names for the two address lines the mapset paints with no label of their own. */
export const AUTH_SUMMARY_HIDDEN_LABELS = {
  /** Names `ADDR001`, `COPAU00.bms` L107 to L110. */
  addressLine1: 'Address line 1',
  /** Names `ADDR002`, `COPAU00.bms` L118 to L121. */
  addressLine2: 'Address line 2',
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
 * Trade-offs: the six monetary widths are kept DISTINCT at 12, 9 and 10 even though all six values
 * share one underlying precision, and unifying them would be the tempting simplification. The
 * segment declares every one of `PA-CREDIT-LIMIT`, `PA-CASH-LIMIT`, `PA-CREDIT-BALANCE`,
 * `PA-CASH-BALANCE`, `PA-APPROVED-AUTH-AMT` and `PA-DECLINED-AUTH-AMT` as `PIC S9(09)V99 COMP-3`
 * (`CIPAUSMY.cpy` L23 to L26 and L29 to L30), so the differing widths cannot be precision -- they are
 * the map's presentation contract, and the program proves it by editing the same precision through
 * two different masks: `WS-DISPLAY-AMT12 PIC -zzzzzzz9.99` renders twelve characters into `CREDLIM`
 * and `CREDBAL` while `WS-DISPLAY-AMT9 PIC -zzzz9.99` renders nine into `CASHLIM`, `CASHBAL`,
 * `APPRAMT` and `DECLAMT` (`COPAUS0C.cbl` L56 to L57, applied at L780 to L799). Two masks feeding
 * three field widths is what makes the geometry a contract of its own, so collapsing the three to one
 * would silently re-lay out four of the six columns.
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
 * Builds the accessible name of one row's selection control.
 * @param {string} transactionId - The acquirer's transaction identifier, which names the row.
 * @returns {string} The action-oriented name for that row's control.
 */
export function selectionActionLabel(transactionId: string): string {
  return `Select authorization ${transactionId}`;
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
 * Assumptions: named rather than written twice as a literal, so the branch below reads as the contract
 * statement it is: the listing has no not-found outcome, so this status can only mean the request
 * reached something other than the operation.
 */
const UNDECLARED_NOT_FOUND_STATUS = 404;

/** The lowest status the listing's own fault family starts at. */
const SERVICE_FAULT_STATUS = 500;

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
 * Assumptions: the status selects the family, since that is the only member of the problem document
 * that carries the same distinction the source's `EVALUATE WS-RESP-CD` did. A 5xx takes the abend
 * replacement the register gives eight of the ten sites, and any other refusal shows the service's own
 * sentence when it sent one -- it is authored server-side to be read, so rewording it here would be a
 * second voice for one message.
 *
 * ⚠️ Refactoring Rationale: a 404 was mapped to `ACCOUNT_ID_NOT_FOUND` and is now treated as an
 * unexpected condition, because this operation DECLARES NO 404. Its contract settles the absent account
 * twice over: `services/authorization-service/src/main/resources/openapi/authorization-api.yaml` states
 * on the 200 that an account with no summary row answers 200 with zero counts, an empty item array and
 * absent cursors, and that "This operation therefore has no 404" -- following the baseline, which
 * RENDERS the absence rather than reporting it, moving zero into all six aggregate positions at
 * `COPAUS0C.cbl` L800-L807 and skipping the browse at L354-L356. So an operator who reached this screen
 * for an account that does not exist sees an empty summary, and the mapping could not fire for the
 * reason it named. A 404 arriving anyway is a misroute -- a gateway or load-balancer rule that no longer
 * reaches this operation -- which is why it now takes the same unexpected-condition sentence a 5xx takes.
 *
 * Assumptions: an undeclared 404 does NOT fall through to the service's own sentence, and that is the
 * point of naming it. Whatever answered is not this service, so its body is a proxy's text rather than
 * an authored operator sentence, and showing it would put words on the message band that nothing in this
 * migration wrote.
 * @param {ApiError | null} error - The normalised problem document, or `null` when nothing failed.
 * @returns {ScreenNotice | null} The sentence and appearance to show, or `null` when there is no
 *   failure to report.
 */
export function describeListingFailure(error: ApiError | null): ScreenNotice | null {
  if (error === null) {
    return null;
  }
  if (error.status === UNDECLARED_NOT_FOUND_STATUS || error.status >= SERVICE_FAULT_STATUS) {
    return {
      message: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
      severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
    };
  }
  const reported = error.message ?? '';
  return {
    message: reported === '' ? SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED : reported,
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
 * Builds the style for a monetary cell at one of the map's three declared widths.
 *
 * Trade-offs: the amount is placed in `fontFamilyCode` and right-aligned inside a fixed character
 * width, and no formatting whatsoever is applied to the string. The value arrives as exact decimal
 * text because the underlying field is packed decimal (`PIC S9(09)V99 COMP-3`), and any pass through
 * a JavaScript number would put it through an IEEE-754 binary64 double, which cannot represent most
 * scale-two fractions exactly -- so a cent the service computed could render as a different cent. The
 * failure would be the worst kind available here, a plausible figure rather than an error. What is
 * given up by not reformatting is the source's edit mask, whose visible effect -- blank-suppressed and
 * right-aligned in a fixed column -- is reproduced by the alignment and the width instead of by
 * rewriting the characters.
 *
 * Assumptions: the width is expressed in `ch` units taken from the map's declared `LENGTH`, which is a
 * DATA contract and not a design value, so it resolves to no design token and needs none. This is the
 * same treatment `MessageBand` applies to its own mapset width, and it is what keeps the three
 * monetary widths distinguishable on screen.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @param {number} width - The map field's declared character width.
 * @returns {CSSProperties} The style for that amount's cell.
 */
function moneyCellStyle(tokens: AntdCssVariables, width: number): CSSProperties {
  return {
    color: tokens[BMS_TEXT_COLOR_TOKENS.BLUE],
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
    minInlineSize: `${String(width)}ch`,
    textAlign: 'end',
  };
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
 * Builds the fourteen entries of the account summary panel, in the map's own reading order.
 *
 * Assumptions: fourteen entries, one per named value field the mapset paints in rows 6 to 12, ordered
 * as the map paints them -- name and customer identifier on row 6, first address line and account
 * status on row 7, second address line on row 8, telephone and the two counts on row 9, the three
 * limit-and-amount fields on row 11 and the three balance-and-amount fields on row 12. Reading order
 * is preserved even though absolute position is not, which is the half of design gap **G1** that is
 * kept.
 *
 * ⚠️ Refactoring Rationale: the two address entries carry a label that is present in the accessibility
 * tree and absent from the screen, where they used to carry no label at all. The mapset genuinely paints
 * none -- `ADDR001` at L107 and `ADDR002` at L118 have no preceding `INITIAL=` field, unlike every other
 * value in the panel -- and a visible label would put text on screen that no baseline source declares,
 * which transformation rule T8 forecloses. What the old reasoning got wrong was the sentence that
 * followed: it held that "the association survives structurally because both sit directly beneath the
 * name they continue, exactly as painted". That is positional identification, and positional
 * identification is precisely what design gap G1 surrenders -- these entries reflow to one column below
 * the medium breakpoint, so "directly beneath" is not a property the delivered screen has at every
 * width, and it was never a property a screen reader could use at any width. Two bordered cells whose
 * header cell is empty are announced as a value with no name.
 *
 * Assumptions: the two hidden names are composed from the mapset's own field data names, `ADDR001` and
 * `ADDR002`, expressed as the address line each one is -- so the name an assistive technology reads
 * corresponds to a field a maintainer can find in `COPAU00.bms`, and nothing is invented beyond the
 * ordinal the source itself numbers them by.
 *
 * Alternatives Considered: binding the `'Acct Status: '` entry to the five `accountStatus1` through
 * `accountStatus5` members instead of to `authStatus`. Rejected on width: `ACCSTAT` is declared
 * `LENGTH=1` at `COPAU00.bms` L114 to L117, and `CIPAUSMY.cpy` L22 declares
 * `PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES` -- two characters per slot and five slots -- so binding
 * them there would widen a one-character field to ten. `PA-AUTH-STATUS PIC X(01)` at L21 is the only
 * member of the segment whose width the field can hold, so it is what the field carries.
 * Assumptions: the five slots are consequently rendered by NO field on this screen, and their absence
 * is a property of the mapset rather than an omission here -- it paints no two-character status
 * position. They remain five DISCRETE members of the contract, never an array: the arity of exactly
 * five is enforced by the target schema as five columns, so gathering them would admit a sixth and
 * invite a caller to iterate a length no declaration supports.
 * @param {PendingAuthSummary} summary - The account summary the listing returned.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {AuthSummaryDescriptionItem[]} The panel entries, ready for `Descriptions`.
 */
export function buildAuthSummaryDescriptions(
  summary: PendingAuthSummary,
  tokens: AntdCssVariables,
): AuthSummaryDescriptionItem[] {
  const value = valueCellStyle(tokens);
  const widths = AUTH_SUMMARY_FIELD_WIDTHS;
  return [
    {
      key: 'customerName',
      label: AUTH_SUMMARY_LABELS.name,
      children: (
        <Typography.Text style={value}>{displayText(summary.customerName)}</Typography.Text>
      ),
    },
    {
      key: 'customerId',
      label: AUTH_SUMMARY_LABELS.customerId,
      children: <Typography.Text style={value}>{summary.customerId}</Typography.Text>,
    },
    {
      key: 'addressLine1',
      label: (
        <Typography.Text style={VISUALLY_HIDDEN_STYLE}>
          {AUTH_SUMMARY_HIDDEN_LABELS.addressLine1}
        </Typography.Text>
      ),
      children: (
        <Typography.Text style={value}>{displayText(summary.addressLine1)}</Typography.Text>
      ),
    },
    {
      key: 'accountStatus',
      label: AUTH_SUMMARY_LABELS.accountStatus,
      children: <Typography.Text style={value}>{displayText(summary.authStatus)}</Typography.Text>,
    },
    {
      key: 'addressLine2',
      label: (
        <Typography.Text style={VISUALLY_HIDDEN_STYLE}>
          {AUTH_SUMMARY_HIDDEN_LABELS.addressLine2}
        </Typography.Text>
      ),
      children: (
        <Typography.Text style={value}>{displayText(summary.addressLine2)}</Typography.Text>
      ),
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
    {
      key: 'creditLimit',
      label: AUTH_SUMMARY_LABELS.creditLimit,
      children: (
        <Typography.Text style={moneyCellStyle(tokens, widths.moneyWide)}>
          {summary.creditLimit}
        </Typography.Text>
      ),
    },
    {
      key: 'cashLimit',
      label: AUTH_SUMMARY_LABELS.cashLimit,
      children: (
        <Typography.Text style={moneyCellStyle(tokens, widths.moneyNarrow)}>
          {summary.cashLimit}
        </Typography.Text>
      ),
    },
    {
      key: 'approvedAuthAmt',
      label: AUTH_SUMMARY_LABELS.approvedAmount,
      children: (
        <Typography.Text style={moneyCellStyle(tokens, widths.moneyMedium)}>
          {summary.approvedAuthAmt}
        </Typography.Text>
      ),
    },
    {
      key: 'creditBalance',
      label: AUTH_SUMMARY_LABELS.creditBalance,
      children: (
        <Typography.Text style={moneyCellStyle(tokens, widths.moneyWide)}>
          {summary.creditBalance}
        </Typography.Text>
      ),
    },
    {
      key: 'cashBalance',
      label: AUTH_SUMMARY_LABELS.cashBalance,
      children: (
        <Typography.Text style={moneyCellStyle(tokens, widths.moneyNarrow)}>
          {summary.cashBalance}
        </Typography.Text>
      ),
    },
    {
      key: 'declinedAuthAmt',
      label: AUTH_SUMMARY_LABELS.declinedAmount,
      children: (
        <Typography.Text style={moneyCellStyle(tokens, widths.moneyMedium)}>
          {summary.declinedAuthAmt}
        </Typography.Text>
      ),
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
 * ⚠️ Refactoring Rationale: the selection state and the selection handler are NO LONGER passed in, because
 * the five controls are now members of one `Radio.Group` mounted around the table and a group owns both.
 * Five independent radios, each holding its own `checked` and its own change handler, are five separate
 * one-of-one groups to an assistive technology: arrow keys do not move between them, the set is not
 * announced as a set, and nothing states that choosing one clears another. The source is unambiguous
 * that they ARE one set -- `PROCESS-ENTER-KEY` scans the five selectors in order and takes the first
 * carrying the selection character (`COPAUS0C.cbl` L296 to L330), which is single-select, first wins.
 *
 * Assumptions: dropping the two parameters is what makes the change enforceable rather than merely
 * present. Had they stayed, a caller could still pass a handler and re-create the per-row binding beside
 * the group's, and the two would fight over the same click.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {TableColumnsType<PendingAuthListItem>} The table columns, ready for `Table`.
 */
export function buildPendingAuthColumns(
  tokens: AntdCssVariables,
): TableColumnsType<PendingAuthListItem> {
  const code: CSSProperties = {
    color: tokens[BMS_TEXT_COLOR_TOKENS.BLUE],
    fontFamily: tokens[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  return [
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.selection,
      key: 'selection',
      fixed: 'left',
      /**
       * Renders one row's selection control.
       *
       * Assumptions: the control is bound to the row's own sealed selector, and the row is selectable
       * at all only because it carries data. The source makes that structural: `INITIALIZE-AUTH-DATA`
       * sets `DFHBMPRO` on all five selectors before a page is built (`COPAUS0C.cbl` L611 to L661) and
       * `POPULATE-AUTH-LIST` sets `DFHBMUNP` on a selector only as it fills that row (L554, L566, L578,
       * L590, L602) -- so an empty row's selector is protected. Rendering a control per delivered row
       * reproduces that without a guard, because a row that was not delivered has no control.
       * @param {PendingAuthListItem} row - The authorization the control acts on.
       * @returns {ReactElement} That row's selection control.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Radio aria-label={selectionActionLabel(row.transactionId)} value={row.key} />
      ),
    },
    {
      title: AUTH_SUMMARY_COLUMN_HEADERS.transactionId,
      key: 'transactionId',
      fixed: 'left',
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
      /**
       * Renders the approved amount as exact decimal text.
       * @param {PendingAuthListItem} row - The authorization being listed.
       * @returns {ReactElement} The amount, right-aligned at the map's declared twelve characters.
       */
      render: (row: PendingAuthListItem): ReactElement => (
        <Typography.Text style={moneyCellStyle(tokens, AUTH_SUMMARY_FIELD_WIDTHS.rowAmount)}>
          {row.amount}
        </Typography.Text>
      ),
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
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [summary, setSummary] = useState<PendingAuthSummary | null>(null);
  const [serviceMessage, setServiceMessage] = useState<string | null>(null);
  /*
   * WHY : ⚠️ Assumptions: the read generation is a REF and not state, because nothing renders from it and
   *       a settlement must be able to read it synchronously, before the next render. A state value would
   *       be read from the closure of the render that opened the read, so every settlement would compare
   *       its own number against itself and every one of them would look current.
   */
  const readGeneration = useRef(0);
  const [notice, setNotice] = useState<ScreenNotice | null>(null);

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
     * WHY : Alternatives Considered: the selection column is a `Radio`, and the two rejected options
     *       are a `Checkbox` and a one-character `Input`. A `Checkbox` is rejected because it admits
     *       a state the source cannot express: `PROCESS-ENTER-KEY` evaluates the five selectors with
     *       an ordered `EVALUATE TRUE` at L285 to L309 whose first non-blank arm wins and whose
     *       remaining arms are then unreachable, so exactly one selection is actionable per turn --
     *       single select, first wins. A `Radio` encodes that in the control itself, whereas a
     *       `Checkbox` would let an operator tick three rows and see one acted on. A one-character
     *       `Input` reproducing `SEL0001` literally -- `LENGTH=1`, `ATTRB=(FSET,NORM,UNPROT)`,
     *       `COLOR=GREEN`, `HILIGHT=UNDERLINE` at L277 to L282 -- is the closer transcription and is
     *       rejected for the same reason plus one more: the design-system mapping assigns this role
     *       to a radio or a checkbox, and a free-text column would admit two simultaneously non-blank
     *       selectors, which is the state the ordered evaluation exists to resolve.
     * WHY : Trade-offs: the consequence of that choice is that the control can only ever supply the
     *       accepted character, so the refusal arm below is the transcribed complement of the
     *       accepted one rather than a path an operator can reach through this control. It is kept,
     *       and `resolveSelectionAction` is exported, because the accepted domain is the baseline's
     *       and not the control's: the function decides the whole of it -- including the lowercase
     *       arm the source adds at L315 -- and is verifiable on its own terms.
     */
    const selectionFlag = selectedKey === null ? '' : AUTH_SUMMARY_SELECTION_CODE;
    const action = resolveSelectionAction(selectionFlag, selectedKey);
    if (action === 'open' && selectedKey !== null) {
      navigateSafely(navigate, authorizationDetailPath(selectedKey));
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
    setSelectedKey(null);
    if (accountIdEntry === scopedAccountId) {
      browse.reset();
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
   * @returns {void} Nothing; either a page arrives or the boundary sentence is shown.
   */
  function pageBackward(): void {
    setNotice(null);
    setSelectedKey(null);
    if (!browse.hasPrev) {
      setNotice({
        message: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
        severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
      });
      return;
    }
    browse.prevPage();
  }

  /**
   * Runs the source program's forward paging arm.
   *
   * Assumptions: availability is read from the envelope's own forward indicator rather than counted
   * from the rows on screen, which is how the source settles it too -- `PROCESS-PAGE-FORWARD` issues
   * one extra read beyond the five it displays and sets `NEXT-PAGE-YES` from whether that read found a
   * record, at `COPAUS0C.cbl` L445 to L452, and the PF8 arm branches on that indicator at L404. A full
   * page of five is therefore not evidence that a sixth record exists.
   * @returns {void} Nothing; either a page arrives or the boundary sentence is shown.
   */
  function pageForward(): void {
    setNotice(null);
    setSelectedKey(null);
    if (!browse.hasNext) {
      setNotice({
        message: SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
        severity: AUTH_SUMMARY_MESSAGE_SEVERITY,
      });
      return;
    }
    browse.nextPage();
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
   *       the keyset envelope rather than to a page number -- `pageBackward` and `pageForward` read
   *       `hasPrev` and `hasNext` -- so the decision is made from the cursor state exactly as
   *       required; it just decides which of two outcomes happens rather than whether the key
   *       responds at all.
   * WHY : Assumptions: the unmapped-key message is NOT re-emitted here. `usePfKeys` owns
   *       `CCDA-MSG-INVALID-KEY` and hands it back on the rejection, so this screen shows what the
   *       hook decided rather than a second copy of the same constant. That matters on this screen
   *       specifically, because unlike the card browse -- which coerces an unrecognised key into its
   *       Enter arm -- `COPAUS0C.cbl` L245 to L249 does move that message into the message field, so
   *       the rejection must be surfaced and not swallowed.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: { onInvoke: submitEntry, label: AUTH_SUMMARY_KEY_LABELS.ENTER },
      PFK03: { onInvoke: returnToMenu, label: AUTH_SUMMARY_KEY_LABELS.PFK03 },
      PFK07: { onInvoke: pageBackward, label: AUTH_SUMMARY_KEY_LABELS.PFK07 },
      PFK08: { onInvoke: pageForward, label: AUTH_SUMMARY_KEY_LABELS.PFK08 },
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
  const listingFailure = describeListingFailure(browse.error);
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
    message: {
      text: bandNotice?.message ?? null,
      severity: bandNotice?.severity ?? AUTH_SUMMARY_MESSAGE_SEVERITY,
      mapset: AUTH_SUMMARY_MAPSET,
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
          <Input
            {...fieldAriaProps(ACCOUNT_ID_INPUT_ID, {
              invalid: fieldState !== null,
              hasError: serviceFieldError !== null,
              hasHint: false,
            })}
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
            suffix={fieldState === 'BLANK' ? FIELD_ERROR_TOKENS.blankMarker : undefined}
            value={accountIdEntry}
          />
        </Form.Item>
      </Form>
      {/*
       * WHY : Assumptions: the panel is rendered only once a summary has arrived, because the source
       *       has nothing to paint before then either -- `GATHER-DETAILS` reads the account, customer
       *       and summary segment only when an account identifier is present (L349 to L357), and the
       *       map's own initial state is `LOW-VALUES` moved over the whole output area at L195. An
       *       empty bordered panel of fourteen blank cells would claim the account had been read and
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
       * WHY : ⚠️ Refactoring Rationale: the table is wrapped in ONE `Radio.Group` that owns the selection
       *       value and the change handler, where each row used to hold an independent `Radio` with its
       *       own `checked` and handler. antd's group publishes itself through context, so the controls
       *       stay exactly where the mapset paints them -- one per row in the leading column -- while
       *       becoming a single set: one tab stop, arrow keys moving between rows, and the set announced
       *       as a group named by the source's own row-22 prompt. That prompt is the natural name because
       *       it is the sentence the terminal paints to say what selecting a row does.
       * WHY : Alternatives Considered: `Table`'s built-in `rowSelection` with `type: 'radio'`, which
       *       provides the same semantics for free. Rejected because it renders its own leading selection
       *       column with its own heading, and the mapset declares that column and its `'Sel'` heading
       *       itself at `COPAU00.bms` L197 to L201 -- adopting antd's would either duplicate the column
       *       or discard the declared heading, and its control is not addressable by the row's sealed
       *       selector without re-deriving the key.
       * WHY : Assumptions: the group's `value` is `null` when nothing is chosen and antd accepts that as
       *       "no member checked", which is the state a freshly painted page is in -- `INITIALIZE-AUTH-DATA`
       *       protects all five selectors before a page is built.
       */}
      <Radio.Group
        aria-label={AUTH_SUMMARY_SELECTION_PROMPT}
        onChange={
          /**
           * Records the row whose control the operator chose.
           * @param {RadioChangeEvent} event - The change event antd forwards; its value is the row's
           *   sealed selector, because that is what each member control carries.
           * @returns {void} Nothing; the selection is recorded as a side effect.
           */
          (event: RadioChangeEvent) => {
            setSelectedKey(String(event.target.value));
          }
        }
        value={selectedKey}
      >
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
        <Table<PendingAuthListItem>
          columns={buildPendingAuthColumns(cssVar)}
          dataSource={browse.items}
          loading={browse.isLoading}
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
      </Radio.Group>
      {/*
       * Assumptions: the prompt is painted `ATTRB=(ASKIP,BRT)` at L497, and brightness is carried as
       * WEIGHT rather than as a brighter colour -- the measured resolution for all 37 bright fields in
       * the base mapset population -- so `strong` supplies `fontWeightStrong` while `COLOR=NEUTRAL`
       * keeps its own `colorTextSecondary`. Substituting a colour would overwrite the one the field
       * declares.
       */}
      <Typography.Text strong style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] }}>
        {AUTH_SUMMARY_SELECTION_PROMPT}
      </Typography.Text>
      {/*
       * Refactoring Rationale: the row-23 message line and the row-24 legend that used to close this
       * body are now delegated to the shell in the `useShellSlot` call above, so the last thing the
       * body renders is the mapset's row-22 selection prompt. The rendered order is unchanged - the
       * shell paints both lines immediately below the body region - and what is removed is the
       * duplication that mounting the shell would otherwise have produced.
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
