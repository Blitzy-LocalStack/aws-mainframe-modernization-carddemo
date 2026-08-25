/**
 * @file The card browse screen, migrated from `app/cbl/COCRDLIC.cbl` and its mapset
 * `app/bms/COCRDLI.bms` (72 `DFHMDF` fields), reached at route `/cards`.
 *
 * Purpose
 * -------
 * Render one keyset-paged page of cards with the reference screen's own narrowing fields, its
 * per-row selection action and its PF-key workflow, and publish the label, message and action-code
 * constants the screen tests assert against. It replaces CICS transaction CCLI, which
 * `app/csd/CARDDEMO.CSD` L357-L358 binds to that program.
 *
 * Paging contract
 * ---------------
 * Assumptions: the REQUEST is by key and never by page number. PF8 is bound to the further-page
 * indicator the service's four-member envelope reports, which is what the reference itself expresses --
 * it carries a first-key and last-key pair plus a next-page indicator in the communication area and
 * discovers one more record than fits. antd's own offset pagination is deliberately disabled: under
 * concurrent inserts an offset skips and repeats rows, which a browse-by-key does not, so using it
 * would change observable behaviour the golden masters fix.
 *
 * ⚠️ Refactoring Rationale: the browse state is now held by `ui/src/hooks/usePagedQuery.ts` and NOT by
 * this component. It was held here as five independent pieces -- the page, the screen ordinal, the
 * loading flag and the two entries -- and each delivered page moved several of them in sequence with
 * NOTHING identifying which request it came from. Two paging steps taken while the first was still
 * outstanding therefore both applied, in whichever order they settled, so PF8 followed by PF7 could
 * leave the rows of one page beside the ordinal of another; and the ordinal decides the backward
 * refusal, so the screen would then permit a step back from a page it was not displaying. The shared
 * hook holds all of it behind one reducer keyed by a started-read sequence, which discards a
 * settlement belonging to a superseded read without touching a single other member -- and that module's
 * own overview names this screen as the first of the five browses it exists to serve.
 *
 * Assumptions: PF7 is still decided by a SCREEN ORDINAL and not by any member of the envelope, because
 * that is where the reference decides it -- `WS-CA-SCREEN-NUM PIC 9(1)` at `app/cbl/COCRDLIC.cbl` L237
 * with `88 CA-FIRST-PAGE VALUE 1` at L238, incremented on PF8 at L492, decremented on PF7 at L508, and
 * tested at L902 to decide the `NO PREVIOUS PAGES TO DISPLAY` refusal at L903. No backward read is ever
 * issued to answer that question there, and none is here: the ordinal simply moved from this component
 * into the hook, which publishes it as `hasPrev`. AAP section 0.7.1 moves exactly this navigation state
 * client-side, and it remains client-side.
 *
 * Narrowing contract
 * ------------------
 * Assumptions: the browse carries TWO narrowing fields, as the mapset paints two. The account field is
 * the `CARDAIX` access path -- `2210-EDIT-ACCOUNT` at `app/cbl/COCRDLIC.cbl` L1003 to L1030 -- and the
 * card field resolves one card rather than narrowing, for the reason `applyFilters` records. The two are
 * edited in the reference's own order, account first, and the account refusal wins when both entries are
 * malformed because that arm writes the message unconditionally while the card arm writes it only while
 * no message is set.
 *
 * Assumptions: eleven ZERO digits are "not supplied" and not a malformed entry, which is the
 * reference's own reading: L1007 to L1012 treats low values, spaces AND a numeric value of zeros as an
 * absent filter and moves zeros into the carried account identifier. An entry of `00000000000` therefore
 * clears the narrowing rather than being refused.
 *
 * Disclosure
 * ----------
 * Assumptions: every card number reaching this screen is already masked by the service, and each row
 * carries an opaque selector used for navigation. Nothing here reconstructs a number, so a screen
 * capture, an edge access log and the browser history all hold the masked form only.
 *
 * Assumptions: masking to the LAST FOUR digits is AAP section 0.4.1.9's rule, which permits an unmasked
 * primary account number on the administrative card-detail endpoint and nowhere else -- a browse is not
 * that endpoint. The mask is applied by the service rather than here, so {@link CardSummary} publishes
 * `displayCardNumber` already masked and this module has no unmasked value to leak; that is the stronger
 * arrangement, because a client-side mask still ships the full number to the browser.
 *
 * Assumptions: no card verification value is rendered, requested or held. The record declares
 * `CARD-CVV-CD PIC 9(03)` at `app/cpy/CVACT02Y.cpy` L7 and the browse row never carried it -- the
 * program moves only the card number, the account and the status into the screen array at
 * `app/cbl/COCRDLIC.cbl` L1165 to L1171 -- and `ui/src/api/types.ts` declares no such member at all.
 *
 * Cursor contract
 * ---------------
 * Assumptions: the browse position is a COMPOSITE of card number and account identifier, not the card
 * number alone. Both COMMAREA cursors declare both halves -- `WS-CA-LAST-CARDKEY` as
 * `WS-CA-LAST-CARD-NUM PIC X(16)` plus `WS-CA-LAST-CARD-ACCT-ID PIC 9(11)`, and `WS-CA-FIRST-CARDKEY`
 * the same pair, at `app/cbl/COCRDLIC.cbl` L230 to L235. A single-column cursor would page incorrectly
 * wherever the second half discriminates.
 *
 * Assumptions: the account half of the record-identification restore is COMMENTED OUT in the reference,
 * at L490 to L491, L506 to L507 and L576 to L577, so the live code restores only the card number. That
 * is precisely why the target carries the full DECLARED composite instead of copying the live subset:
 * the declaration states the intended key and the commented lines show the restore was meant to use
 * both halves. The cursors themselves are opaque strings here -- sealed by the service, never parsed and
 * never constructed by this module -- so the composition is the service's to honour and this screen
 * cannot silently disagree with it.
 */

import { Button, Flex, Input, Space, Table, Typography, theme } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent, MouseEvent, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';

import { isTransientFailure } from '../../api/client';
import { listCards, lookupCard } from '../../api/cards';
import type { CardSummary, PageDirection, PageResponse } from '../../api/cards';
import { useShellSlot } from '../../layout/AppShell';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import {
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
} from '../../messages/messages';
import { cardDetailPath, cardEditPath, isCardNumber } from '../../routes/cards';
import {
  CARD_LIST_ROUTE,
  MAIN_MENU_ROUTE,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import type { ScreenTransitionState } from '../../routes/navigation';
import type { CardListQuery } from '../../api/types';
import {
  VISUALLY_HIDDEN_STYLE,
  busyAnnouncement,
  fieldAriaProps,
  fieldErrorId,
} from '../../layout/fieldHelp';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
import { SPACING_TOKENS, TYPOGRAPHY_TOKENS, characterCellColumnMeasure } from '../../theme/tokens';

/**
 * The `var(--…)` reference form of the design tokens, as antd's theme hook publishes it.
 *
 * Assumptions: derived from the hook rather than written out, so the column builder below takes exactly
 * what `theme.useToken()` yields and no separate declaration can drift from it. This is the spelling
 * `ui/src/screens/authSummary/index.tsx` L130 already uses for the same purpose.
 */
type AntdCssVariables = ReturnType<typeof theme.useToken>['cssVar'];

/** Paging refusals this screen renders, taken verbatim from the catalog keyed by its program. */
const CARD_LIST_PAGING_MESSAGES = PROGRAM_MESSAGES.COCRDLIC;

/** Screen-level status messages this screen renders, keyed by the same program. */
const CARD_LIST_STATUS_MESSAGES = STATUS_MESSAGES.COCRDLIC;

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L357-L358 defines it. */
export const CARD_LIST_TRANSACTION_ID = 'CCLI';

/** Source program name, rendered in the header band exactly as the 3270 screen did. */
export const CARD_LIST_PROGRAM_NAME = 'COCRDLIC';

/**
 * Screen title, verbatim from the mapset's own title field at `app/bms/COCRDLI.bms` L78.
 *
 * Assumptions: mapset text lives in the screen module and not in the message catalog, which is the
 * boundary the catalog draws for itself -- it holds no `INITIAL=` literal and records that a screen's
 * own labels belong with the screen that renders them.
 */
export const CARD_LIST_TITLE = 'List Credit Cards';

/**
 * The two filter labels and the four column headings, verbatim from `app/bms/COCRDLI.bms`.
 *
 * Assumptions: the padding is part of each value. `Select    ` carries four trailing spaces,
 * ` Card Number ` is bracketed by one space either side and `Active ` carries one trailing space,
 * because the mapset sizes each heading to the column beneath it. A renderer may collapse the runs
 * visually; nothing here may discard them, which is why the values are stored padded and only the
 * rendering trims.
 */
export const CARD_LIST_LABELS = {
  /** `app/bms/COCRDLI.bms` L88 -- the account-number filter field's label. */
  accountNumberFilter: 'Account Number    :',
  /** `app/bms/COCRDLI.bms` L100 -- the card-number filter field's label. */
  cardNumberFilter: 'Credit Card Number:',
  /** `app/bms/COCRDLI.bms` L111 -- heading of the one-character selection column. */
  selectColumn: 'Select    ',
  /** `app/bms/COCRDLI.bms` L115 -- heading of the account column. */
  accountColumn: 'Account Number',
  /** `app/bms/COCRDLI.bms` L119 -- heading of the card-number column. */
  cardColumn: ' Card Number ',
  /** `app/bms/COCRDLI.bms` L123 -- heading of the active-flag column. */
  activeColumn: 'Active ',
} as const;

/**
 * Function-key legend labels, split from this mapset's own legend literal.
 *
 * Assumptions: `app/bms/COCRDLI.bms` L335-L339 paints `  F3=Exit F7=Backward  F8=Forward` and
 * nothing else, so this screen's legend names three keys and **not** Enter -- even though
 * `app/cbl/COCRDLIC.cbl` L371-L374 admits Enter as a fourth valid attention identifier. That
 * asymmetry is the source's and is reproduced by giving the Enter binding an empty label, which
 * `usePfKeys` defines as a keyboard-only handler and `PfKeyBar` renders no control for.
 *
 * Refactoring Rationale: the backward and forward labels are taken from
 * {@link UNIFORM_PF_KEY_LABELS} rather than re-declared here. `F7=Backward` and `F8=Forward` in this
 * mapset are byte-identical to the shared pair, so declaring them again would create a second place
 * for one string to be transcribed and a silent way for the two to disagree. Only `F3=Exit` is local,
 * because PF3's wording is not uniform across the 17 measured legends.
 */
const CARD_LIST_KEY_LABELS = {
  ENTER: '',
  PFK03: 'F3=Exit',
  PFK07: UNIFORM_PF_KEY_LABELS.PFK07,
  PFK08: UNIFORM_PF_KEY_LABELS.PFK08,
} as const;

/**
 * The two characters an operator types into the selection column to act on a row.
 *
 * `app/cbl/COCRDLIC.cbl` L77-L79 declares `88 SELECT-OK VALUES 'S', 'U'`, with `'S'` requesting the
 * detail screen and `'U'` the update screen. They are used as the labels of this screen's two per-row
 * controls.
 *
 * Alternatives Considered: `View` and `Edit`, which an earlier revision of this screen rendered. They
 * are rejected because no COBOL source in this application holds either word for this action, and the
 * source explains these two codes itself: `WS-INFORM-REC-ACTIONS` reads
 * `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD` and is painted whenever a page is displayed, which is
 * why the band below carries it. A control labelled with the code the operator already knows, beside
 * the sentence that defines it, carries the source's own vocabulary rather than a substitute for it.
 *
 * Trade-offs: a one-character control name is terser than an assistive-technology user would choose,
 * and the accepted mitigation is the same one the terminal had -- the explanatory sentence is on the
 * screen at the same time, in the message band, rather than being assumed knowledge.
 */
export const CARD_LIST_ROW_ACTION_CODES = {
  detail: 'S',
  update: 'U',
} as const;

/**
 * Origin every transfer out of this screen hands the screen it transfers to.
 *
 * Assumptions: this is the migrated form of `CDEMO-FROM-TRANID` and `CDEMO-FROM-PROGRAM`
 * (`app/cpy/COCOM01Y.cpy` L23-L26), which the reference's transfer arms write before every
 * `EXEC CICS XCTL` -- `app/cbl/COCRDLIC.cbl` L520-L521 for the detail arm and L548-L549 for the update
 * arm both move `LIT-THISTRANID`/`LIT-THISPGM` in. The destination's PF3 then prefers it over its own
 * default (`app/cbl/COCRDUPC.cbl` L442-L454), so a transfer that hands over nothing loses observable
 * behaviour: an operator who reached the destination from this browse was returned somewhere they had
 * not come from.
 *
 * Refactoring Rationale: declared once here rather than written at each of the four transfer sites --
 * the two per-row controls, the Enter turn's selection arm and the typed-number controls. Four copies
 * of one handover are four places for it to drift, and the same argument
 * `ui/src/routes/navigation.ts` makes for naming its transition helper once.
 *
 * Assumptions: the origin travels in the history entry's STATE and not in the path or the query, for
 * exactly the reason no card number reaches a route on this screen -- a request target is written
 * verbatim into the load balancer's access log, the browser's history and any referrer sent onward,
 * while router state reaches none of them. The value discloses nothing in any case: it is this
 * screen's own parameterless route, so it names a screen rather than a cardholder. Carrying it as
 * state keeps ONE rule about what may appear in a request line instead of one rule per value.
 *
 * Trade-offs: state is dropped by the full-document fallback in `navigateSafely` and by a reload, so
 * the destination then takes its own documented fallback destination. That is accepted rather than
 * worked around, because the alternative is a query member -- which is the shape this migration
 * refuses -- and the cost is one extra key press on a path an operator reaches only after a failed
 * client-side transition.
 */
const BROWSE_ORIGIN: ScreenTransitionState = Object.freeze({ from: CARD_LIST_ROUTE });

/**
 * Labels of the three controls that act on the card number typed into the filter field.
 *
 * Assumptions: these three strings are NEW, and they are held here for the reason the message catalog
 * gives for excluding such strings -- it carries only text a COBOL source holds. The source screen
 * needs no such controls: its Enter key both narrows the list and, through the selection column,
 * opens a card, and Enter is unpainted on this mapset. A browser has a pointer, so an unpainted key
 * would leave those actions unreachable without one; the controls exist to close that gap and are
 * additive, exactly as the key bar's own region name is.
 */
export const CARD_LIST_ENTRY_CONTROL_LABELS = {
  filter: 'Filter',
  openDetail: 'Open detail',
  openUpdate: 'Open update',
} as const;

/*
 * WHY : Alternatives Considered: an antd `Form.Item` with `label` and `htmlFor`, which is the pattern
 *       the sign-on screen uses. It is rejected here because this entry is not a form -- it is one
 *       filter control in a compact control group -- and wrapping it in a form would give the group a
 *       submit path of its own, competing with the Enter binding the hook already installs. The label
 *       is therefore a `Typography.Text` carrying an id, and the control names it through
 *       `aria-labelledby`, which is a programmatic association in the same sense `htmlFor` is and
 *       needs no raw `label` element. `Typography.Text` cannot take `htmlFor` at all: its props do not
 *       declare it, so the compiler refuses it.
 */
/** Identifier of the label element the card-number filter control is named by. */
const CARD_NUMBER_LABEL_ID = 'card-list-card-number-label';

/** Identifier of the label element the account-number filter control is named by. */
const ACCOUNT_NUMBER_LABEL_ID = 'card-list-account-number-label';

/**
 * Which of the two filter fields a refusal blames.
 *
 * Assumptions: the two names are the fields' own, matching `CARD_LIST_LABELS`, so a reader comparing
 * this against the source edits sees the paragraph names `2210-EDIT-ACCOUNT` and `2220-EDIT-CARD`
 * reflected in the vocabulary rather than in an index.
 */
type FilterFieldName = 'accountNumber' | 'cardNumber';

/**
 * The refusal each filter field carries after the last turn, or `null` where that field was accepted.
 *
 * Assumptions: a sentence PER FIELD rather than one shared sentence, because the band and the field
 * answer different questions. The band answers "what is wrong with this turn" and carries one sentence
 * under the reference's account-first precedence (`app/cbl/COCRDLIC.cbl` L1021 unguarded against L1056
 * guarded); a field's description answers "what is wrong with THIS field" and is announced only when
 * that field has focus. Sharing one sentence between them makes the second answer wrong whenever both
 * fields are refused.
 */
type FilterRefusals = Readonly<Record<FilterFieldName, string | null>>;

/** No field refused, which is the state every turn begins in. */
const NO_FILTER_REFUSALS: FilterRefusals = { accountNumber: null, cardNumber: null };

/** Identifier of the account-number filter control itself. */
const ACCOUNT_NUMBER_INPUT_ID = 'card-list-account-number';

/*
 * WHY : Refactoring Rationale: the card-number control is given an identifier too, matching the account
 *       control beside it. It had none, and a browser run reported it: Chrome raises "A form field
 *       element should have an id or name attribute" against it, because a control with neither cannot
 *       be autofilled reliably. Its accessible NAME was never in doubt -- `aria-labelledby` resolves it
 *       -- so this is an autofill and hygiene correction rather than an accessibility one.
 * WHY : Assumptions: the two are named on the same scheme, `card-list-<field>`, so the pair reads as one
 *       filter group. Leaving one of two sibling controls without an identifier would also invite the
 *       question of what distinguished them, when nothing did.
 */

/** Identifier of the card-number filter control itself. */
const CARD_NUMBER_INPUT_ID = 'card-list-card-number';

/**
 * Declared width of one row's action field, `CRDSELn` on the mapset.
 *
 * Assumptions: one character, from `LENGTH=1` on `CRDSEL1` at `app/bms/COCRDLI.bms` L143 and
 * `CRDSEL1I PIC X(1)` at `app/cpy-bms/COCRDLI.CPY` L78. The terminal refused a second keystroke in
 * that field, and `maxLength` is how a browser control refuses it.
 */
const ROW_ACTION_WIDTH = 1;

/**
 * Key that activates a focused browse row.
 *
 * ⚠️ Assumptions: the space key and deliberately NOT Enter. Enter is this screen's turn: the mapset
 * paints no Enter on its legend and the program's `CCARD-AID-ENTER` arms are what edit the filters and
 * read the browse (`app/cbl/COCRDLIC.cbl` L517 and L545), so `usePfKeys` claims Enter document-wide and
 * `KEYBOARD_KEY_TO_AID` maps it to that verb. Binding Enter on a row would give one key two meanings on
 * one screen; the space key is claimed by no attention identifier -- `resolveAidFromKeyboardEvent`
 * (`ui/src/layout/usePfKeys.ts` L318 to L324) resolves only Enter and the function keys -- so it is free
 * and it is the key a browser already uses to activate a non-link widget.
 */
const ROW_ACTIVATION_KEY = ' ';

/**
 * Pointer affordance carried by every browse row.
 *
 * ⚠️ Refactoring Rationale: a browser measured `cursor: auto` on these rows both at rest and while
 * hovered, with no `:hover` rule of their own, so nothing about a row said it could be acted on even
 * though every row carries controls that act on it. The declaration is a cursor KEYWORD rather than a
 * token because the design system publishes no cursor token and none of its components sets one; the
 * zero-hardcoded-values rule exempts exactly this class of keyword alongside `auto` and `none`.
 *
 * Assumptions: no size floor is declared here, and the omission is deliberate rather than an oversight.
 * `min-height` does not apply to a `display: table-row` box, so a `minBlockSize` set to
 * `TARGET_SIZE_AA_MINIMUM` would be an inert declaration that reads as a guarantee. The row's pointer
 * target is its cell box, whose height is a text line plus the theme's own cell padding and is several
 * times that floor; the controls INSIDE it are the ones the floor governs, and they take their height
 * from `controlHeight` and `controlHeightSM`, the latter raised to that floor by the theme's own
 * `CONTROL_SCALE_DECISION` record in `ui/src/theme/tokens.ts` -- named in prose rather than as a link
 * target, because it is an exported VALUE and not a type, and `jsdoc/no-undefined-types` resolves a
 * link target against the type namespace alone.
 */
const ROW_AFFORDANCE_STYLE: CSSProperties = { cursor: 'pointer' };

/**
 * Discards a settled browse turn's outcome, for the three call sites that cannot observe it.
 *
 * Purpose: {@link usePagedQuery} now returns a promise from `reset`, `prevPage` and `nextPage`, where
 * all three returned nothing. Every call site here is inside a `void`-returning key handler, so the
 * promise can neither be returned nor awaited -- and `ui/eslint.config.js` configures
 * `no-floating-promises` with `ignoreVoid: false`, which withdraws the `void` discard as well. Naming
 * the discard is what is left, and it says at each site what is actually true there.
 *
 * ⚠️ Assumptions: discarding is CORRECT rather than merely permitted, because the outcome is observed
 * somewhere else on a later render. The hook applies every outcome of a turn -- the rows, the boundary
 * flags, the classified failure -- through its own reducer, so this screen reads the answer out of
 * `browse` exactly as it reads the answer to the opening page. There is nothing at the call site to
 * act on.
 *
 * ⚠️ Alternatives Considered: making the three handlers `async` and awaiting the turn. Rejected because
 * `usePfKeys` types its handlers as returning `void`, and `no-misused-promises` with `checksVoidReturn`
 * rejects a promise-returning function passed where `void` is expected -- so that trade replaces one
 * error with another. Also considered a bare `.catch(...)`: rejected because the engine resolves on
 * both outcomes, so a rejection handler alone would not satisfy the rule's requirement that the chain
 * end in a handler for the settled case.
 *
 * Assumptions: the same function is supplied for BOTH outcomes. The rejection path is unreachable by
 * construction -- the engine classifies a failure internally and resolves -- and a handler is written
 * for it anyway, so a later change inside the engine cannot turn this into an unhandled rejection
 * silently.
 * @returns {void} Nothing; the outcome has already been recorded by the hook's own reducer.
 */
function ignoreSettledTurn(): void {
  // Assumptions: an empty body is the whole implementation. Logging here would emit a line for every
  //   ordinary page turn of every browse, which is noise loud enough to bury a real diagnostic.
}

/**
 * Layout of the label beside each entry field, so the label cannot be squeezed out of shape.
 *
 * ⚠️ Assumptions: both properties here are LAYOUT KEYWORDS and not design values -- there is no colour,
 * size, spacing or radius among them -- so the zero-hardcoded-values rule is untouched. `flex: '0 0
 * auto'` refuses the label a share of the shrinking, and `whiteSpace: 'nowrap'` refuses it a line
 * break.
 *
 * ⚠️ Assumptions: refusing the break is what the mapset declares. `app/bms/COCRDLI.bms` L84 to L88
 * paints `Account Number    :` as ONE 19-character field at row 6 column 22, and L96 to L100 paints
 * `Credit Card Number:` as one 19-character field at row 7 column 22 -- one field, one row, one line
 * each. A browser measured the second of those broken into stacked one- and two-character pieces at a
 * 375-pixel width, which is not a narrower rendering of that field but a different field.
 *
 * Alternatives Considered: letting the label wrap at its word boundaries and keeping the pair on one
 * line. Rejected because the fragmenting label IS the defect being repaired: the row wraps instead, so
 * a width too narrow for both moves the ENTRY to the next line while the label stays one line, and the
 * entry keeps the `max-inline-size` ceiling its own PICTURE clause gives it either way.
 */
const FILTER_LABEL_LAYOUT: CSSProperties = { flex: '0 0 auto', whiteSpace: 'nowrap' };

/**
 * Finds the selection field belonging to one rendered browse row.
 *
 * Assumptions: the row is searched rather than an identifier being rebuilt from the row's index,
 * because the search cannot go stale. antd re-creates a cell's contents on every render, so an index
 * captured when a handler was built can name a field belonging to a row that has since been replaced by
 * a page step; the row element the event was delivered to always contains its own field, and the
 * selection column is the only column on this screen that renders one.
 * @param {HTMLElement} row - The row element an event was delivered to.
 * @returns {HTMLInputElement | null} That row's selection field, or `null` when the row renders none,
 *   which is the state a page with no rows leaves the table in.
 */
function rowSelectionField(row: HTMLElement): HTMLInputElement | null {
  return row.querySelector<HTMLInputElement>('input');
}

/**
 * Places the cursor in a row's selection field when the row itself is clicked.
 *
 * ⚠️ Purpose: give a row an outcome, which is what a pointer cursor on it promises. The outcome is the
 * reference's own: `1250-SETUP-ARRAY-ATTRIBS` repositions the cursor onto a row by moving `-1` into
 * that row's selection-field length (`app/cbl/COCRDLIC.cbl` L766 to L773 and the four blocks after it),
 * so landing in the row's own field is what the terminal did with a row the operator was directed to.
 *
 * Assumptions: a click that landed on a control inside the row is left alone. That control has its own
 * behaviour -- the two immediate controls navigate, and the field is already the focus target -- so
 * moving focus after it would either be a no-op or take focus off the thing just pressed.
 * @param {MouseEvent<HTMLElement>} event - The click, as React delivers it to the row.
 * @returns {void} Nothing; the cursor move is the effect.
 */
function focusRowSelectionOnPointer(event: MouseEvent<HTMLElement>): void {
  if (
    event.target !== event.currentTarget &&
    (event.target as HTMLElement).closest('input,button,a') !== null
  ) {
    return;
  }

  rowSelectionField(event.currentTarget)?.focus();
}

/**
 * Places the cursor in a row's selection field when a focused row is activated from the keyboard.
 *
 * Assumptions: the event is honoured only when the ROW is what carries focus, so a space typed into the
 * selection field or pressed on one of the row's controls keeps its own meaning. `event.target` is the
 * focused element and `event.currentTarget` is the row, so their identity is the test.
 *
 * Assumptions: the default action is cancelled, because space on a focusable element scrolls the page.
 * A row that both moved the cursor and scrolled it out of view would be worse than one that did
 * neither.
 * @param {KeyboardEvent<HTMLElement>} event - The key press, as React delivers it to the row.
 * @returns {void} Nothing; the cursor move is the effect.
 */
function focusRowSelectionOnKey(event: KeyboardEvent<HTMLElement>): void {
  if (event.target !== event.currentTarget || event.key !== ROW_ACTIVATION_KEY) {
    return;
  }

  event.preventDefault();
  rowSelectionField(event.currentTarget)?.focus();
}

/*
 * WHY : ⚠️ Alternatives Considered: antd's `InputNumber` for the two numeric filter fields, which is the
 *       obvious control for an all-digits entry and is REJECTED on two independent grounds. The first is
 *       fidelity: `app/cpy/CVCRD01Y.cpy` L34 to L39 declares `CC-ACCT-ID PIC X(11)` with
 *       `CC-ACCT-ID-N REDEFINES` it as `PIC 9(11)`, and `CC-CARD-NUM PIC X(16)` with
 *       `CC-CARD-NUM-N REDEFINES` it as `PIC 9(16)` -- CHARACTERS on the wire and a number only where
 *       arithmetic needs one, which is why `2210-EDIT-ACCOUNT` tests `IS NOT NUMERIC` on the character
 *       form rather than reading a number.
 * WHY : ⚠️ Trade-offs: the second ground is correctness and it is decisive. `Number.MAX_SAFE_INTEGER` is
 *       9007199254740991, which is sixteen digits, so a real sixteen-digit primary account number such
 *       as 4111111111111111 EXCEEDS it and a numeric binding loses precision silently -- the entry would
 *       round to a different card with no error anywhere. Both filters are therefore plain `Input`
 *       controls holding strings end to end, with `maxLength` supplying the width the terminal field
 *       enforced and a digits-only predicate supplying the `IS NOT NUMERIC` test. The cost accepted is
 *       that no stepper or numeric keypad affordance comes for free; `inputMode="numeric"` recovers the
 *       keypad without recovering the precision loss.
 */

/*
 * WHY : ⚠️ Trade-offs: the seven rows are grouped by a `Table` rather than positioned as the mapset's 72
 *       absolute field coordinates, which is AAP gap G1 and is a deliberate, documented deviation.
 *       `DFHMDI SIZE=(24,80)` fixes a 24-by-80 character grid and every one of the 72 `DFHMDF` entries
 *       carries an absolute `POS=(row,column)`. What is preserved is field GROUPING, reading order and
 *       tab order -- the selection field then the account number then the card number then the active
 *       flag, in that order, one group per row. What is deliberately not preserved is pixel-for-character
 *       positioning: reproducing absolute character coordinates in a browser would defeat every
 *       assistive technology that reflows content and could not respond to a viewport at all.
 */

/*
 * WHY : Assumptions: every user-visible string on this screen comes from `../../messages/messages` or
 *       from the mapset constants declared in this module, and none is written inline at a use site.
 *       That catalog is the single owner of text a COBOL source holds, which is what makes the
 *       verbatim guarantee checkable in one place instead of at every render.
 * WHY : ⚠️ Assumptions: `NO RECORDS TO SHOW` is deliberately NOT among them. It looks like an eleventh
 *       message for this program and is not one: `app/cbl/COCRDLIC.cbl` L1243 is
 *       `*               MOVE 'NO RECORDS TO SHOW'  TO WS-ERROR-MSG`, commented out with a `*` in
 *       column 7, and the live statement beneath it at L1244 sets `WS-NO-RECORDS-FOUND` instead. The
 *       reference therefore never emits that string, and rendering it would invent a message rather
 *       than migrate one. The catalog holds no entry for it either.
 * WHY : Assumptions: the composite `WS-FILE-ERROR-MESSAGE` (L153 to L171) is likewise not surfaced. It
 *       appends the internal file name and the CICS response and reason codes, which are internal
 *       identifiers that must not reach a browser. ⚠️ Refactoring Rationale: that class of failure was
 *       answered by ONE authored availability sentence, and is now answered by whichever of
 *       {@link TRANSIENT_FAILURE_TRY_AGAIN} and {@link PERSISTENT_FAILURE_REPORT_IT} the failure's own
 *       classification selects -- see the band composition below for why one sentence could not be
 *       right for both.
 */

/*
 * WHY : Assumptions: no design VALUE appears in this module -- no colour, spacing, radius, font or
 *       duration literal -- and no `ConfigProvider` is instantiated here. `ui/src/App.tsx` is the sole
 *       `ConfigProvider` and `ui/src/theme/tokens.ts` the sole source of design values, so the mapset's
 *       own operands are resolved once, centrally: `GREEN` on the two filter fields to `colorSuccess`,
 *       `TURQUOISE` on the labels and the legend to `colorInfo`, `NEUTRAL` on the headings and the
 *       informational band to `colorTextSecondary`, `DEFAULT` on the row fields to `colorText` and
 *       `RED` on the error band to `colorError`. A colour written here would be a second source of
 *       truth for one of those operands, which is exactly what the zero-hardcoded-values rule exists
 *       to prevent; the band's own severity and `Input`'s own `status` carry the two runtime colours
 *       this screen needs, `DFHNEUTR` and `DFHRED`, without either being named locally.
 */

/** Identifier stem for the per-row action fields, suffixed with the row's position. */
const ROW_ACTION_INPUT_ID_PREFIX = 'card-list-row-action-';

/**
 * Accessible name carried by every row's action field.
 *
 * Assumptions: the mapset gives these fields no label of their own -- the column heading
 * `Select    ` at `app/bms/COCRDLI.bms` L111 names the whole column and a 3270 field needs no
 * programmatic association -- so the heading's own text is used as each field's accessible name. It is
 * trimmed because the declared padding sizes a terminal column and would otherwise be announced.
 *
 * Alternatives Considered: naming each field with the row it belongs to, which would make the names
 * unique. Rejected because the only value available to distinguish them is the masked card number, and
 * putting that in an accessible name would announce a card number on a screen whose entire disclosure
 * posture is that it renders only the masked form. The column heading is what a sighted operator reads
 * above the field, so it is the honest name.
 */
const ROW_ACTION_FIELD_LABEL = CARD_LIST_LABELS.selectColumn.trim();

/*
 * WHY : ⚠️ Refactoring Rationale: the account filter is RESTORED to this screen. It is the first of the
 *       two criteria the source program edits -- `2210-EDIT-ACCOUNT` at `app/cbl/COCRDLIC.cbl` L1003 to
 *       L1030, ahead of `2220-EDIT-CARD` at L1036 -- and the label for its field was already
 *       transcribed into `CARD_LIST_LABELS.accountNumberFilter` from `app/bms/COCRDLI.bms` L88 while no
 *       control ever rendered it. The card detail screen meanwhile documented that this screen "owns the
 *       account and card filter fields together", so the workflow was described as living here and lived
 *       nowhere: an operator could not narrow the browse by account on any screen in the tree.
 * WHY : Assumptions: the narrowing is a real service capability and needs no new contract. `listCards`
 *       already accepts `accountId` and `CardListQuery` already declares it -- the criterion was
 *       reachable from the client the whole time -- so what was missing was the control and its edit,
 *       not the transport.
 * WHY : Assumptions: the width is eleven because the source field is `ACCTSIDI PIC X(11)` and the
 *       program's refusal names eleven digits. It is written as a named constant beside the card
 *       number's sixteen so the two widths read as the field contracts they are.
 */

/*
 * WHY : ⚠️ Refactoring Rationale: a module-private `ACCOUNT_FILTER_WIDTH = 11` stood here and is GONE,
 *       because {@link CARD_LIST_ACCOUNT_FILTER_WIDTH} below already held the same eleven as an EXPORTED
 *       constant and nothing consumed it. Two names for one field contract is a value that can be
 *       changed in one place and not the other, and the one a reader would reasonably trust -- the
 *       exported one, which is the published contract -- was the one no control was bound to. The
 *       control is now bound to the exported constant and the duplicate is withdrawn.
 */

/** Matches an account filter of exactly the declared width, all digits. */
/*
 * WHY : Refactoring Rationale: an `ACCOUNT_FILTER_PATTERN` regular expression stood here and is
 *       withdrawn. `isAccountFilterAbsent` and `isAccountFilterWellFormed` decide the same two
 *       questions, and they are the pair applyFilters asks; keeping the regex as well meant one of the
 *       two would be edited without the other.
 */

/*
 * WHY : Assumptions: an all-zeroes entry is NOT SUPPLIED rather than invalid, and that is the source's
 *       own rule rather than a convenience: `2210-EDIT-ACCOUNT` tests
 *       `CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES OR CC-ACCT-ID-N EQUAL ZEROS` and takes
 *       the blank exit for all three, and `2220-EDIT-CARD` tests the same three for the card number.
 *       An operator who clears a filter by typing zeros over it therefore clears the narrowing, and does
 *       not receive a refusal for a value the terminal accepted.
 */

/**
 * Reports whether a filter entry is one the source treats as not supplied.
 * @param {string} entry - The filter entry exactly as typed, unpadded.
 * @returns {boolean} `true` when the entry is empty, all spaces or all zeroes.
 */
export function isUnsuppliedFilter(entry: string): boolean {
  return entry.trim() === '' || /^0+$/u.test(entry.trim());
}

/**
 * Ordinal of the opening page, and the only value at which the backward step is refused.
 *
 * Assumptions: the value is one because the reference's condition name says one --
 * `88 CA-FIRST-PAGE VALUE 1` on `WS-CA-SCREEN-NUM` at `app/cbl/COCRDLIC.cbl` L237 to L238. It is named
 * here rather than written as a bare literal in the guard so the guard reads as the reference's own
 * test rather than as an arbitrary comparison against a number.
 */
export const CARD_LIST_FIRST_PAGE = 1;

/**
 * How many rows this screen has room for, as the reference program declares it.
 *
 * Assumptions: seven, corroborated THREE independent ways so the figure is not read off one line.
 * First, the mapset paints seven row families -- `CRDSEL1` through `CRDSEL7` at
 * `app/bms/COCRDLI.bms` L140, L162, L189, L216, L243, L270 and L297, on screen rows 11 to 17. Second,
 * the program's own array comment reads `File Data Array 28 CHARS X 7 ROWS = 196` at
 * `app/cbl/COCRDLIC.cbl` L250, over `WS-ALL-ROWS PIC X(196)` redefined as `WS-SCREEN-ROWS OCCURS 7
 * TIMES` of an eleven-character account, a sixteen-character card number and a one-character status at
 * L253 to L260, which is 28 bytes. Third, it is a named constant --
 * `05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7` at L177 to L178 -- and it is that constant the browse
 * loop compares the row counter against at L1191 to decide the page is full.
 *
 * Assumptions: the paging hook REQUIRES this rather than defaulting it, because the five browses it
 * serves declare five different arities and a default would render another screen's page size.
 */
export const CARD_LIST_PAGE_SIZE = 7;

/** Width of the account filter field, from `CC-ACCT-ID PIC X(11)` and `ACCTSIDI` on the mapset. */
export const CARD_LIST_ACCOUNT_FILTER_WIDTH = 11;

/**
 * Width of the card-number filter field, from `CC-CARD-NUM PIC X(16)` and `CARDSIDI` on the mapset.
 *
 * ⚠️ Refactoring Rationale: this constant is NEW and the card filter's `maxLength` was a bare `16`
 * before it existed, which made the comment above the account width state something untrue -- that the
 * eleven "is written as a named constant beside the card number's sixteen so the two widths read as the
 * field contracts they are". There was no such constant, so one field contract was named and documented
 * while its sibling was an unexplained literal at the point of use. Both are now named, and the claim
 * that they read alike is true rather than aspirational.
 *
 * Assumptions: sixteen, from two agreeing sources -- `CARDSID DFHMDF ... LENGTH=16` at
 * `app/bms/COCRDLI.bms` L101 and `02 CARDSIDI PIC X(16)` in the generated symbolic map at
 * `app/cpy-bms/COCRDLI.CPY` L72 -- and the program's own refusal at `app/cbl/COCRDLIC.cbl` L1058 names
 * sixteen digits.
 *
 * Assumptions: the width bounds a STRING and never a number. A sixteen-digit card number such as
 * 4111111111111111 exceeds `Number.MAX_SAFE_INTEGER` (9007199254740991), so a numeric binding would lose
 * precision silently -- which is the same reason the baseline itself carries the field as characters,
 * `CC-CARD-NUM PIC X(16)` with a `CC-CARD-NUM-N REDEFINES ... PIC 9(16)` used only for arithmetic
 * (`app/cpy/CVCRD01Y.cpy` L37 to L39).
 */
export const CARD_LIST_CARD_FILTER_WIDTH = 16;

/*
 * WHY : ⚠️ Refactoring Rationale: the screen-ordinal helper that stood here is GONE, together with the
 *       opening-page constant it tested. Both moved into `ui/src/hooks/usePagedQuery.ts`, which holds
 *       the ordinal in the same reducer as the rows and the cursors so that a delivered page moves all
 *       of them together or none of them -- the property this screen could not have while the ordinal
 *       was a separate `useState` advanced in its own callback. The hook's own `FIRST_PAGE_NUMBER` cites
 *       the same `88 CA-FIRST-PAGE VALUE 1` at `app/cbl/COCRDLIC.cbl` L237 to L238, so nothing about the
 *       reference's test is lost by the move; what is gained is that a stale settlement can no longer
 *       advance the ordinal past the rows on display.
 */

/**
 * Whether an account entry is "not supplied" in the reference's own sense.
 *
 * Assumptions: three conditions, and the third is the one that is easy to miss. `2210-EDIT-ACCOUNT` at
 * `app/cbl/COCRDLIC.cbl` L1007 to L1012 treats the field as absent when it holds LOW-VALUES, when it
 * holds SPACES, or when its numeric redefinition holds ZEROS -- so `00000000000` is an absent filter and
 * not a malformed one, and the arm moves zeros into the carried identifier and leaves without raising a
 * sentence. A browser has no low-values state, so the empty string stands for the first two and the
 * all-zeros form is tested exactly as the third.
 * @param {string} entry - The account entry as the operator left it.
 * @returns {boolean} True when the entry requests no narrowing at all.
 */
export function isAccountFilterAbsent(entry: string): boolean {
  const trimmed = entry.trim();
  return trimmed === '' || /^0+$/u.test(trimmed);
}

/**
 * Whether a supplied account entry is well formed.
 *
 * Assumptions: eleven digits exactly, which is the conjunction of the two conditions the reference's
 * comment names above its own test -- "Not numeric" and "Not 11 characters" at `app/cbl/COCRDLIC.cbl`
 * L1015 to L1016. The program can only test the first, because `CC-ACCT-ID PIC X(11)` is eleven
 * characters by construction and a terminal refused a twelfth keystroke; a browser input needs both
 * halves stated, and `maxLength` supplies the terminal's half.
 *
 * Assumptions: the same shape the service publishes, so a well-formed entry here is one the contract
 * accepts. `card-api.yaml` declares `CardPageQuery.accountId` with `pattern '^[0-9]{11}$'` and the same
 * eleven-zero reading, so this predicate refuses locally exactly what that would refuse remotely -- which
 * is what keeps the reference's own sentence on the band instead of a service problem document.
 * @param {string} entry - The account entry as the operator left it.
 * @returns {boolean} True when the entry is eleven digits.
 */
export function isAccountFilterWellFormed(entry: string): boolean {
  return /^[0-9]{11}$/u.test(entry.trim());
}

/**
 * Builds one browse request from a position and the narrowing in force.
 *
 * Assumptions: members are SPREAD IN on presence rather than assigned as `undefined`, because
 * `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- an absent member and a member holding
 * `undefined` are different types there, and only the first is a state `CardListQuery` has. The two
 * reach the service identically, since `JSON.stringify` omits a member holding `undefined`, so this is
 * the compiler being allowed to enforce that an opening read carries no cursor rather than a cursor
 * whose value is nothing.
 *
 * Assumptions: the DIRECTION is spread on the same condition as the cursor, because `listCards` refuses
 * a direction supplied without a cursor before it dispatches, so the opening read must not name one. The
 * refusal is the CLIENT's and not this contract's: `card-api.yaml` publishes the opposite, answering that
 * pair with the opening page whichever direction it named, which is what `app/cbl/COCRDLIC.cbl`
 * L444-L454 does when PF7 is pressed on the first page. `ui/src/api/client.ts` records why the guard
 * refuses it for every service regardless -- the seven contracts do not agree on this one combination,
 * and a screen must not behave differently depending on which service it is talking to.
 * @param {string | null} cursor - Sealed cursor, or `null` for the opening read.
 * @param {PageDirection} direction - Direction that cursor was sealed for.
 * @param {string} appliedAccountId - Account narrowing in force, or the empty string for none.
 * @returns {CardListQuery} The query for that step.
 */
export function buildCardListQuery(
  cursor: string | null,
  direction: PageDirection,
  appliedAccountId: string,
): CardListQuery {
  return {
    ...(cursor === null ? {} : { cursor, direction }),
    ...(appliedAccountId === '' ? {} : { accountId: appliedAccountId }),
  };
}

/**
 * The two action characters `88 SELECT-OK VALUES 'S', 'U'` admits, as a type.
 *
 * Assumptions: UPPER CASE ONLY, and that is the source's own domain rather than a simplification.
 * `app/cbl/COCRDLIC.cbl` contains no `FUNCTION UPPER-CASE` anywhere -- verified by search over all
 * 1459 lines -- so `2250-EDIT-ARRAY` compares the received byte against `'S'` and `'U'` literally and
 * a lower-case `s` falls to its `WHEN OTHER` arm at L1108. Folding case here would ACCEPT an entry the
 * reference refuses, which is a behavioural change rather than a courtesy.
 */
export type CardListActionCode = 'S' | 'U';

/**
 * Outcome of editing the seven per-row action entries, as `2250-EDIT-ARRAY` computes it.
 *
 * Assumptions: a refusal and a selection are mutually exclusive, because the reference dispatches on
 * `INPUT-ERROR` first: its `WHEN INPUT-ERROR` arm at `app/cbl/COCRDLIC.cbl` L419 re-displays the map
 * and takes `GO TO COMMON-RETURN` at L438, so the two transfer arms at L517 and L545 are never
 * reached on a turn that raised one. `selectedRow` is therefore `null` whenever `message` is set,
 * even though the reference still assigns `I-SELECTED` at L1102 on the way through.
 */
export interface CardListSelectionEdit {
  /** Index of the one row to act on within the entries supplied, or `null` when none may be. */
  readonly selectedRow: number | null;
  /** Action requested for that row, or `null` when no row may be acted on. */
  readonly action: CardListActionCode | null;
  /** The refusal to put on the message band, or `null` when the entries were acceptable. */
  readonly message: string | null;
  /** Indices of the rows to mark as errored, matching `WS-ROW-CRDSELECT-ERROR`. */
  readonly erroredRows: readonly number[];
}

/**
 * Edits the per-row action entries exactly as `2250-EDIT-ARRAY` does.
 *
 * Purpose: decide, from the characters standing in the selection column, whether one row may be
 * opened, or which refusal the band must carry and which rows must be marked. It is the transcription
 * of `app/cbl/COCRDLIC.cbl` L1073 to L1117 and is the whole reason the two selection messages in the
 * catalog are reachable at all.
 *
 * Assumptions: the TALLY is taken across every entry before any row is judged, which is the order the
 * reference uses -- `INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U'` at L1079 to L1082
 * runs over the whole seven-byte field, and only then does `IF I > +1` at L1084 decide. Judging row by
 * row and stopping at the first action would never see the second one, so the
 * more-than-one refusal could not exist.
 *
 * Assumptions: when more than one action is present, EVERY row carrying one is marked and not just
 * the last. L1088 to L1093 copies the whole flags field and replaces `'S'` and `'U'` with `'1'`, so
 * the mark lands on each of them; an operator who typed two must be shown both.
 *
 * Assumptions: the invalid-code sentence is GUARDED and the row mark is not. L1110 marks the row
 * unconditionally while L1111 writes the sentence only `IF WS-ERROR-MSG-OFF`, so a turn that already
 * raised the more-than-one refusal keeps that sentence and still marks the stray row. This asymmetry
 * is the same shape as the one between the two filter edits and is reproduced rather than smoothed.
 *
 * Assumptions: a blank entry is `' '` or unset, from `88 SELECT-BLANK VALUES ' ', LOW-VALUES` at L1106
 * read against L80 to L82. A browser has no low-values state, so the empty string stands for it.
 * @param {readonly string[]} entries - The action characters in row order, one per rendered row,
 *   each already limited to a single character by the control that collected it.
 * @returns {CardListSelectionEdit} The row to act on, or the refusal and the rows to mark.
 */
export function reduceCardListSelection(entries: readonly string[]): CardListSelectionEdit {
  const codes = entries.map(
    /**
     * Reads one entry as the single byte the terminal field held.
     * @param {string} entry - One row's action entry.
     * @returns {string} That entry with surrounding blanks removed.
     */
    (entry: string): string => entry.trim(),
  );

  /*
   * WHY : Assumptions: the tally is taken over EVERY entry before any row is judged, which is the
   *       order `INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U'` imposes at
   *       `app/cbl/COCRDLIC.cbl` L1079 to L1082 -- the whole seven-byte field is inspected and only
   *       then does `IF I > +1` at L1084 decide.
   * WHY : Alternatives Considered: judging row by row and stopping at the first action found.
   *       Rejected because it can never observe the second action, so the more-than-one refusal
   *       could not be produced at all.
   */
  const tooMany =
    codes.filter(
      /**
       * Reports whether one entry names an action.
       * @param {string} code - That row's action entry, blanks removed.
       * @returns {boolean} True when the entry is `S` or `U`.
       */
      (code: string): boolean => code === 'S' || code === 'U',
    ).length > 1;

  const erroredRows: number[] = [];
  let message: string | null = tooMany
    ? CARD_LIST_STATUS_MESSAGES.WS_MORE_THAN_1_ACTION.text
    : null;
  let selectedRow: number | null = null;
  let action: CardListActionCode | null = null;

  /*
   * WHY : Alternatives Considered: `forEach` with a callback, which is the idiom used elsewhere in
   *       this file. A plain loop is used here because the loop assigns three enclosing bindings, and
   *       assignments made inside a callback are not narrowed by the compiler at the return
   *       statement below -- so the callback form would need an assertion the loop form does not.
   */
  for (let index = 0; index < codes.length; index += 1) {
    const code = codes[index] ?? '';
    if (code === 'S' || code === 'U') {
      selectedRow = index;
      action = code;
      if (tooMany) {
        erroredRows.push(index);
      }
      continue;
    }
    if (code === '') {
      continue;
    }
    // WHY : Assumptions: a stray character marks its OWN row and no other, because L1110 writes the
    //       mark at the loop's current subscript. The sentence is withheld when one is already set,
    //       which is L1111's `IF WS-ERROR-MSG-OFF` guard -- so a turn that already raised the
    //       more-than-one refusal keeps that sentence and still marks this row.
    erroredRows.push(index);
    message ??= CARD_LIST_STATUS_MESSAGES.WS_INVALID_ACTION_CODE.text;
  }

  /*
   * WHY : Assumptions: a raised refusal suppresses the transfer entirely, for the reason
   *       `CardListSelectionEdit` records -- the reference's `WHEN INPUT-ERROR` arm returns before
   *       either transfer arm is evaluated. Returning the row anyway would open a card on the same
   *       turn that told the operator their entries were wrong.
   */
  return message === null
    ? { selectedRow, action, message: null, erroredRows: [] }
    : { selectedRow: null, action: null, message, erroredRows };
}

/**
 * Character-cell extent of each browse column, counted off the mapset's own heading row.
 *
 * Purpose: give every column a width derived from the display it was transcribed from, so a viewport
 * narrower than that row scrolls the GRID rather than squeezing the columns into each other.
 *
 * ⚠️ Refactoring Rationale: this grid declared no horizontal extent and no column widths at all, and a
 * browser pass at a 375-pixel viewport measured two consequences of that, not one. The visible one is
 * the finding's own: with four columns sharing 327 pixels, `00000000100` wrapped as `000000` / `00100`
 * and the masked card number broke across three lines -- an account number and a primary account
 * number split mid-digit-group, which for a card system is a misreading risk rather than a cosmetic
 * one. The second is that the squeezed table still did not fit: it measured 359.92 pixels inside a
 * 327-pixel `.ant-table-content` whose computed `overflow-x` was `visible`, and every one of the nine
 * ancestors up to the content region reported the same and refused `scrollLeft`, so the 8.92-pixel
 * remainder was paid for one level further out -- `#carddemo-shell-content` measured `scrollWidth` 384
 * against `clientWidth` 375 and accepted `scrollLeft` 9. Content painted outside the viewport and the
 * shell body panned sideways.
 *
 * ⚠️ Assumptions: declaring the extent is what CREATES the scrolling region, and the mechanism is the
 * design system's own. In the pinned package `ui/node_modules/@rc-component/table/lib/Table.js`
 * L259-L272 turns a declared horizontal extent into `overflow-x: auto` on the `-content` element and
 * `width: <extent>; min-width: 100%` on the inner table, applied at L556-L575. `min-width: 100%` is
 * why nothing changes above the floor: at any viewport wide enough the grid still fills its container
 * exactly as it does today, which the same pass confirmed at 768, 1280 and 1920.
 *
 * ⚠️ Assumptions: the extents are the mapset's own row geometry rather than chosen numbers, and they
 * are read the way the sibling user browse reads its own -- each heading spans from its start column
 * to the NEXT heading's start column, and the last heading takes its declared `LENGTH`. On
 * `app/bms/COCRDLI.bms` row 9 the four headings start at columns 10, 21, 45 and 66, and `'Active '`
 * carries `LENGTH=7`, which gives 11, 24, 21 and 7 cells spanning columns 10 through 72. Every data
 * field falls inside its own column's span: the selection field sits at column 14, `ACCTNO` at 22 for
 * 11 characters, `CRDNUM` at 43 for 16 and `CRDSTS` at 67 for 1.
 *
 * ⚠️ Assumptions: the selection column's 11 cells hold what that column now renders, which was checked
 * against the controls rather than assumed. It carries the row's one-character field -- itself capped
 * by `copybookFieldWidthStyle` at one character plus two paddings -- and two `size="small"` buttons
 * each labelled with a single action code, which together measure about 100 pixels against the
 * 11-cell column's ceiling of roughly 124. Had they not fitted, the honest fix would have been to
 * declare the wider extent the controls need and record the departure, not to squeeze them.
 *
 * Alternatives Considered: (1) letting the page keep the overflow. Rejected on the measurement: the
 * shell's frame is bounded to one viewport precisely so rows 22 to 24 stay put, and a body that pans
 * sideways slides the title band, the message band and the key legend out from under the operator --
 * paying three persistent zones for one column. (2) `overflow-x: hidden` on the region, which would
 * stop the pan and hide the remainder instead, making the active flag unreachable at 375 rather than
 * merely off to the side. (3) Dropping a column below a breakpoint, rejected for the reason the user
 * browse records for its own type column: a column that disappears is a column an operator cannot
 * learn.
 *
 * Trade-offs: below the floor the grid scrolls sideways, which the terminal never did because it was
 * exactly 80 columns wide. Design gap G1 already records that a fixed character grid can only scale or
 * clip; scrolling ONE region is the least lossy of those, and it is the pattern the finding names as
 * acceptable while naming page-level pan as the defect.
 */
const CARD_LIST_COLUMN_CELLS = Object.freeze({
  /** `'Select    '` heads column 10 and `'Account Number'` heads 21. */
  select: 11,
  /** `'Account Number'` heads column 21 and `' Card Number '` heads 45. */
  account: 24,
  /** `' Card Number '` heads column 45 and `'Active '` heads 66. */
  card: 21,
  /** `'Active '` is the last heading, so it takes its own `LENGTH=7`. */
  active: 7,
  /** Columns 10 through 72 -- the sum of the four extents above. */
  rowSpan: 63,
});

/** Columns the grid lays out, which fixes how many times the design system adds its cell padding. */
const CARD_LIST_COLUMN_COUNT = 4;

/**
 * Least measure this grid may be laid out at, in the mapset's own character cells.
 *
 * Purpose: give {@link CARD_LIST_COLUMN_CELLS} a floor to resolve against, so the grid owns its
 * horizontal overflow instead of handing it to the shell's content region.
 *
 * ⚠️ Assumptions: the design system adds its cell padding on both sides of each of the
 * {@link CARD_LIST_COLUMN_COUNT} columns -- `antd/lib/table/style/index.js` derives the table's
 * `cellPaddingInline` from the `padding` token -- so the total reserves it eight times while each
 * column reserves it twice. Summing the four column measures therefore reproduces this expression
 * exactly, and a change to either has to be a change to both to stay consistent.
 *
 * ⚠️ Assumptions: this measure is stated here rather than shared with the sibling browses that carry
 * the same treatment, for the reason those files record: every screen is mounted through `lazy()` in
 * `ui/src/router.tsx`, so importing a value from another screen would fold that screen's chunk into
 * this one. There is no single value to share in any case -- 63 cells over four columns here against
 * 71 over five on the user browse and 69 over three on the transaction-type browse.
 * @param {AntdCssVariables} cssVar - The theme's CSS-variable reference map, from `theme.useToken()`;
 *   only its `padding` member is read.
 * @returns {string} The measure, as a CSS length expression the design system applies to the grid.
 */
export function cardListTableMeasure(cssVar: AntdCssVariables): string {
  return `calc(${String(CARD_LIST_COLUMN_CELLS.rowSpan)}ch + ${String(
    CARD_LIST_COLUMN_COUNT * 2,
  )} * ${String(cssVar.padding)})`;
}

/**
 * Builds the four browse columns the mapset paints, in the order it paints them.
 *
 * Purpose: describe the selection column and the three data columns for the antd `Table`, so the
 * column set is one documented value rather than an anonymous array buried in the render. Extracting
 * it also makes the column order and the per-row control assertable without mounting the screen.
 *
 * Assumptions: FOUR columns in the mapset's own order -- the selection field at `app/bms/COCRDLI.bms`
 * L140, the account number at L147, the card number at L152 and the active flag at L157 -- and their
 * headings are the four `COLOR=NEUTRAL` literals at L111, L115, L119 and L123. The headings keep their
 * declared padding in {@link CARD_LIST_LABELS} and are trimmed only here, at the point of rendering.
 *
 * Assumptions: no column is bound to an embossed name, an expiration date or a card verification
 * value. The browse row is the three values `app/cbl/COCRDLIC.cbl` L258 to L260 declares, and
 * {@link CardSummary} publishes exactly those three beside the row's selector, so a column bound to
 * anything else would render empty for every row. The verification value is absent by design: the
 * record declares `CARD-CVV-CD PIC 9(03)` at `app/cpy/CVACT02Y.cpy` L7 and this screen never showed
 * it, so it reaches no request and no row model.
 * @param {object} options - Everything the columns need from the screen's own state.
 * @param {readonly string[]} options.actionEntries - Action characters in row order, one per row.
 * @param {ReadonlySet<number>} options.erroredRows - Indices of rows the last turn marked.
 * @param {boolean} options.rowsProtected - Whether every row's action field is protected, which is
 *   this screen's spelling of `FLG-PROTECT-SELECT-ROWS-YES`.
 * @param {string | null} options.refusal - The refusal sentence to describe a marked field with, or
 *   `null` when the last turn raised none.
 * @param {(index: number, entry: string) => void} options.onActionEntryChange - Records the character
 *   typed into one row's action field.
 * @param {(row: CardSummary) => void} options.onOpenDetail - Opens one row's detail immediately, for
 *   a pointer user who cannot reach the unpainted Enter key.
 * @param {(row: CardSummary) => void} options.onOpenUpdate - Opens one row's update form immediately.
 * @param {AntdCssVariables} options.tokens - The theme's CSS-variable references, from which the two
 *   identifier columns take the fixed-pitch face and the selection field takes its width ceiling.
 * @returns {TableColumnsType<CardSummary>} The four columns, selection column first.
 */
export function buildCardListColumns(options: {
  readonly actionEntries: readonly string[];
  readonly erroredRows: ReadonlySet<number>;
  readonly rowsProtected: boolean;
  readonly refusal: string | null;
  readonly onActionEntryChange: (index: number, entry: string) => void;
  readonly onOpenDetail: (row: CardSummary) => void;
  readonly onOpenUpdate: (row: CardSummary) => void;
  readonly tokens: AntdCssVariables;
}): TableColumnsType<CardSummary> {
  /*
   * WHY : ⚠️ Assumptions: the two IDENTIFIER columns are placed in the fixed-pitch face, and they were
   *       previously left on the body's proportional face. A browser run measured the gap rather than
   *       inferring it: both cells computed the antd default sans stack, and the count of monospace
   *       elements inside `.ant-table` was ZERO while the header band on the very same screen carried
   *       four. AAP section 0.3.3 maps "Fixed-pitch money and identifier columns" to `fontFamilyCode`
   *       precisely because it "preserves column alignment for numeric data", and an 11-digit account
   *       number stacked above another in a proportional face does not align digit-for-digit the way the
   *       terminal's character cells did.
   * WHY : Assumptions: the token is read through `TYPOGRAPHY_TOKENS.fixedPitchData` rather than naming
   *       `fontFamilyCode` here, so the mapping stays in `ui/src/theme/tokens.ts` where that module
   *       records the measurement behind it -- 29 right-justified numeric fields and 5
   *       `PICOUT='+ZZZ,ZZZ,ZZZ.99'` money fields in the baseline. No literal font value appears in this
   *       file, which is what keeps AAP section 0.3.2's zero-hardcoded-values rule true here.
   * WHY : ⚠️ Assumptions: the one-character ACTIVE column is deliberately NOT included. The token's
   *       stated purpose is inter-row alignment of numeric data, and a column whose every value is
   *       exactly one character (`CRDSTS1` through `CRDSTS7`, `app/bms/COCRDLI.bms` L157 `LENGTH=1`) is
   *       aligned in any face, so switching it would change the face without preserving anything.
   */
  const fixedPitch: CSSProperties = {
    fontFamily: options.tokens[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  return [
    // WHY : Assumptions: the selection column comes FIRST because the mapset paints `CRDSEL1` at
    //       column 12 and the account number at column 22 (`app/bms/COCRDLI.bms` L144, L151), and the
    //       order a returning operator reads is part of the screen.
    /*
     * WHY : ⚠️ Refactoring Rationale: the mapset's SIX hidden per-row carrier fields render NOTHING
     *       here, and this note exists because the specification mis-describes them. `CRDSTP2` through
     *       `CRDSTP7` sit at `app/bms/COCRDLI.bms` L169, L196, L223, L250, L277 and L304 -- one per list
     *       row 2 to 7, with deliberately no `CRDSTP1` -- each declared
     *       `ATTRB=(ASKIP,DRK,FSET) LENGTH=1 POS=(row,14)`. `ASKIP` means the cursor SKIPS the field, so
     *       it can never be typed into, and a search for `CRDSTP` across all 1459 lines of
     *       `app/cbl/COCRDLIC.cbl` returns ZERO hits: the program neither reads nor writes them. They
     *       are inert, carry no data, and exist only in the mapset and the generated symbolic map
     *       (`app/cpy-bms/COCRDLI.CPY` L103 to L108 and its five siblings).
     * WHY : ⚠️ Assumptions: AAP gap G2 is WRONG about these six and the correction is recorded here
     *       rather than acted on. That gap describes the `(ASKIP,DRK,FSET)` fields as "password entry on
     *       the sign-on screen" and resolves them to `Input.Password`. That resolution is right for the
     *       sign-on screen's own darkened field and wrong for these: a password field is typed into and
     *       these cannot be, and no program statement references them. Rendering them as password
     *       inputs would invent six focusable controls per page that the reference does not have, so
     *       nothing is rendered for them and no `Input.Password` appears anywhere on this screen.
     */
    {
      title: CARD_LIST_LABELS.selectColumn.trim(),
      key: 'actions',
      width: characterCellColumnMeasure(CARD_LIST_COLUMN_CELLS.select, options.tokens.padding),
      /**
       * Renders one row's action field and the two immediate controls beside it.
       * @param {CardSummary} row - The row the controls act on.
       * @param {CardSummary} _record - The same row, which antd passes a second time; unused.
       * @param {number} index - That row's position among the rendered rows.
       * @returns {ReactElement} The action field and its two pointer controls.
       */
      render: (row: CardSummary, _record: CardSummary, index: number): ReactElement => {
        /*
         * WHY : ⚠️ Refactoring Rationale: ONE uniform error presentation for all seven rows, where the
         *       reference has two. Row 1 takes `MOVE DFHBMPRF` and, when its entry is blank, also moves
         *       a literal `'*'` into the field (`app/cbl/COCRDLIC.cbl` L753 to L759); rows 2 to 7 take
         *       `MOVE DFHBMPRO` and instead move `-1` into the field's length to reposition the cursor
         *       (L766 to L773 and the four blocks after it). Both arms then move the SAME `DFHRED` into
         *       the colour attribute, so the visible refusal is identical and the divergence is in the
         *       protect byte and in a cursor mechanism a browser expresses through focus rather than
         *       through a length field. The asymmetry carries no behavioural meaning -- the paragraph's
         *       own comment at L749 reads "USE REDEFINES AND CLEAN UP REPETITIVE CODE !!", which is the
         *       author saying the repetition was unintended -- so reproducing it would reproduce a
         *       transcription artefact. It is normalised deliberately and recorded here.
         */
        const errored = options.erroredRows.has(index);
        const controlId = `${ROW_ACTION_INPUT_ID_PREFIX}${String(index)}`;
        return (
          <Space size="small">
            {/*
             * WHY : ⚠️ Assumptions: this is the row's own one-character field, `CRDSELn` at
             *       `app/bms/COCRDLI.bms` L140 with `LENGTH=1` and `CRDSELnI PIC X(1)` at
             *       `app/cpy-bms/COCRDLI.CPY` L78, so `maxLength` is one. It is what makes the
             *       reference's workflow reachable: an operator types `S` or `U` and presses Enter,
             *       and only that path can produce `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE`
             *       or `INVALID ACTION CODE`. Without a field to type into, both sentences are
             *       structurally unreachable and two catalog entries describe behaviour the screen
             *       cannot exhibit.
             * WHY : Assumptions: `disabled` carries `FLG-PROTECT-SELECT-ROWS-YES`. The reference
             *       protects every row's field when either filter edit failed -- set at
             *       `app/cbl/COCRDLIC.cbl` L1020 and L1055, cleared at L987, consumed by
             *       `1250-SETUP-ARRAY-ATTRIBS` at L748 to L831 -- and it also skips selection editing
             *       altogether on such a turn (L1075 to L1077), so an entry made then could not be
             *       acted on in any case.
             * WHY : Refactoring Rationale: the empty-row half of that same condition,
             *       `WS-EACH-CARD(n) EQUAL LOW-VALUES`, needs no test here. It protects the unfilled
             *       tail of a seven-row array on a fixed terminal; a table renders a row per delivered
             *       item, so an absent row renders no field to protect. The condition is satisfied
             *       structurally rather than by a branch that could never be false.
             * WHY : Alternatives Considered: wrapping the field in an antd `Form.Item` to carry
             *       `validateStatus` and `help`. Rejected for the reason this file already records for
             *       its filter controls -- the selection column is not a form, and a `Form.Item` per
             *       row would give each row a submit path competing with the Enter binding
             *       `usePfKeys` installs. `status` carries the identical error treatment, and
             *       `fieldAriaProps` with a described refusal carries the `help` text accessibly,
             *       which is the mechanism the two filter controls above already use.
             */}
            <Input
              {...fieldAriaProps(controlId, {
                invalid: errored,
                hasError: errored && options.refusal !== null,
                hasHint: false,
              })}
              aria-label={ROW_ACTION_FIELD_LABEL}
              disabled={options.rowsProtected}
              id={controlId}
              maxLength={ROW_ACTION_WIDTH}
              onChange={
                /**
                 * Records the character typed into this row's action field.
                 * @param {object} event - The change event antd forwards.
                 * @param {object} event.target - The input element the event came from.
                 * @param {string} event.target.value - The entry as it now stands.
                 * @returns {void} Nothing; the entry is recorded as a side effect.
                 */
                (event: { target: { value: string } }): void => {
                  options.onActionEntryChange(index, event.target.value);
                }
              }
              status={errored ? 'error' : ''}
              /*
               * WHY : ⚠️ Refactoring Rationale: the field is SIZED from the width its own PICTURE clause
               *       declares, and it was left at the control's full-width default. A browser measured
               *       the consequence rather than it being inferred: this one-character field rendered
               *       about 217 pixels wide, against about 97 for the identical one-character selector
               *       on the user browse, so one column of a four-column table carried a control more
               *       than twice the width of the data it can hold. `CRDSEL1` is `LENGTH=1` at
               *       `app/bms/COCRDLI.bms` L140 and `CRDSEL1I PIC X(1)` at `app/cpy-bms/COCRDLI.CPY`
               *       L78, which is where {@link ROW_ACTION_WIDTH} comes from and is already the
               *       `maxLength` above -- so the entry was bounded and only its rendering was not.
               * WHY : Assumptions: the helper is spread onto the CONTROL and not onto a wrapper, which
               *       `ui/src/layout/recordLayout.ts` L99 to L104 records as a measured constraint --
               *       the padding token it reads resolves on an `.ant-input` and returns the empty
               *       string on an arbitrary element, so a wrapper would drop the declaration.
               * WHY : Trade-offs: the helper yields a CEILING and keeps `inlineSize: '100%'`, so the
               *       cell may still render the field narrower at a phone width rather than forcing the
               *       table to scroll sideways. That is the same departure from the terminal's fixed
               *       character cells that design gap G1 already records for position.
               */
              style={copybookFieldWidthStyle(ROW_ACTION_WIDTH, options.tokens)}
              value={options.actionEntries[index] ?? ''}
            />
            {/*
             * WHY : ⚠️ Refactoring Rationale: this element was MISSING and its absence made the field's
             *       own accessible description a dangling reference. `fieldAriaProps` above is given
             *       `hasError`, so it emits `aria-describedby="card-list-row-action-<n>-error"`, but the
             *       only `fieldErrorId` targets this module rendered were the two FILTER controls, so no
             *       element with that id existed on the page. A browser run confirmed it rather than
             *       inferring it: with rows marked, `getElementById` for that id returned `null` and
             *       `[...document.querySelectorAll('[id$="-error"]')]` was empty. The consequence is
             *       exactly the one `ui/src/layout/fieldHelp.tsx` names at L61 and L130 as the reason it
             *       exists -- a screen-reader user on a marked row is told "invalid" and nothing about
             *       why.
             * WHY : Assumptions: the sentence is repeated here VISUALLY HIDDEN rather than beside the
             *       field, which is the identical treatment the two filter controls already use in this
             *       file. The shared band shows it to a sighted operator once, and row 23 is where the
             *       mapset puts it, so printing it per row would add seven copies of a sentence the
             *       mapset declares once. What the reference does put ON the row is the colour --
             *       `app/cpy/CSSETATY.cpy` L17-L27 moves `DFHRED` into the FIELD's attribute byte -- and
             *       `status="error"` above is that half; this element is the half an attribute byte
             *       cannot carry.
             * WHY : Assumptions: it is rendered only while this row is marked AND a sentence exists,
             *       which is the same pair of conditions `hasError` is given, so the description can
             *       neither outlive the refusal it describes nor be claimed without being rendered.
             */}
            {errored && options.refusal !== null ? (
              <span id={fieldErrorId(controlId)} style={VISUALLY_HIDDEN_STYLE}>
                {options.refusal}
              </span>
            ) : null}
            {/*
             * WHY : Trade-offs: the two immediate controls are KEPT beside the field even though the
             *       reference has no such controls, and the cost is two affordances for one action.
             *       They are kept because Enter is unpainted on this mapset -- its legend at
             *       `app/bms/COCRDLI.bms` L339 names only F3, F7 and F8, so `usePfKeys` renders no
             *       Enter control -- and a pointer-only user would otherwise have no way at all to
             *       submit a row action. This is the same accommodation AAP section 0.4.4 makes for
             *       the function keys themselves, where the bar "renders the same actions as buttons
             *       so the workflow is discoverable to new users without being taken away from
             *       existing ones". Each is labelled with the code the field accepts, so the two
             *       affordances teach one vocabulary.
             */}
            <Button
              disabled={options.rowsProtected}
              onClick={
                /**
                 * Opens this row's card detail without waiting for a turn.
                 * @returns {void} Nothing; navigation is the effect.
                 */
                (): void => {
                  options.onOpenDetail(row);
                }
              }
              size="small"
            >
              {CARD_LIST_ROW_ACTION_CODES.detail}
            </Button>
            <Button
              disabled={options.rowsProtected}
              onClick={
                /**
                 * Opens this row's card update form without waiting for a turn.
                 * @returns {void} Nothing; navigation is the effect.
                 */
                (): void => {
                  options.onOpenUpdate(row);
                }
              }
              size="small"
            >
              {CARD_LIST_ROW_ACTION_CODES.update}
            </Button>
          </Space>
        );
      },
    },
    // WHY : Assumptions: the browse row carries the account number rather than the embossed name or
    //       the expiration date, because those two are members of the card DETAIL while the list row
    //       is the three values `app/cbl/COCRDLIC.cbl` L258 to L260 declares.
    {
      title: CARD_LIST_LABELS.accountColumn,
      dataIndex: 'accountId',
      width: characterCellColumnMeasure(CARD_LIST_COLUMN_CELLS.account, options.tokens.padding),
      /**
       * Renders one row's account number in the fixed-pitch face.
       * @param {string} accountId - The row's eleven-digit account number.
       * @returns {ReactElement} The number, aligned digit-for-digit with the rows around it.
       */
      render: (accountId: string): ReactElement => (
        <Typography.Text style={fixedPitch}>{accountId}</Typography.Text>
      ),
    },
    {
      title: CARD_LIST_LABELS.cardColumn.trim(),
      dataIndex: 'displayCardNumber',
      width: characterCellColumnMeasure(CARD_LIST_COLUMN_CELLS.card, options.tokens.padding),
      /**
       * Renders one row's card number in the fixed-pitch face.
       *
       * Assumptions: the value arrives ALREADY masked to its last four digits and is rendered as
       * delivered, so no masking happens here. `ui/src/api/types.ts` publishes the row's number as
       * `displayCardNumber` for that reason, and AAP section 0.4.1.9 permits an unmasked number only on
       * the administrative detail endpoint -- which this browse is not.
       * @param {string} displayCardNumber - The row's masked card number, as the service delivered it.
       * @returns {ReactElement} The masked number in the fixed-pitch face.
       */
      render: (displayCardNumber: string): ReactElement => (
        <Typography.Text style={fixedPitch}>{displayCardNumber}</Typography.Text>
      ),
    },
    // WHY : Assumptions: the flag renders as the stored character. The source column is one character
    //       wide (`CRDSTS1` through `CRDSTS7`, `app/bms/COCRDLI.bms` L157 with `LENGTH=1`) under a
    //       heading that names the domain, so `Y` and `N` are what an operator reads there. Expanding
    //       them to `Active` and `Inactive` would widen a one-character column eightfold and would
    //       render two words no COBOL source holds.
    {
      title: CARD_LIST_LABELS.activeColumn.trim(),
      dataIndex: 'activeStatus',
      width: characterCellColumnMeasure(CARD_LIST_COLUMN_CELLS.active, options.tokens.padding),
    },
  ];
}

/**
 * Renders the keyset-paged card browse, per-row navigation, and the card-number entry that reaches one
 * card.
 *
 * Refactoring Rationale: a card is reached BOTH by acting on a row and by entering its number, and an
 * earlier revision of this screen offered only the second. That revision removed the per-row buttons
 * because the contract published no addressable value per row -- which was true of that contract and was
 * the defect rather than the constraint: a list a user cannot act on is a list that only answers "which
 * cards exist". Every row now carries an opaque selector, so the buttons have an address to build a
 * route from, and the selector discloses no card number to a history entry or an access log.
 *
 * Assumptions: the entry field is retained alongside them because it is the baseline's own workflow --
 * its list screen carries a card-number filter field, CARDSIDI at app/cpy-bms/COCRDLI.CPY:72, and its
 * detail and update screens are entered by typing into it. Entering a number now EXCHANGES it for a
 * selector through the lookup operation before navigating, so the number travels in a request body
 * rather than becoming a path segment.
 *
 * Errors: this component THROWS NOTHING and renders in every state, because a screen is the last place a
 * rejection can be turned into something an operator can read. A refused page read is surfaced by
 * {@link usePagedQuery} as `isFailed` with a normalised `ApiError` -- the problem document
 * `ui/src/api/client.ts` produces -- on `error`, and it is painted on the row-23 line as the service's
 * own sentence when the document carried one; when it did not, the sentence is selected from the
 * failure's own `transient` judgement, which the hook publishes on `failure`. The row-22 informational
 * sentence is suppressed either way,
 * so the screen never invites an operator to act on rows a failed read did not deliver. The rows, both
 * cursors and the screen ordinal already on display are LEFT INTACT by that hook
 * (`ui/src/hooks/usePagedQuery.ts` L654-L661), so a failed step forward leaves the operator on the page
 * they were reading rather than on an empty table.
 *
 * Errors: the branch is taken on `isFailed` and NOT on `error !== null`, which is deliberate rather than
 * incidental. That hook documents at L313-L322 that `error` is null for a failure whose cause was not a
 * problem document -- a reader that rejected with a bare `Error` or a string, or an answer that was
 * malformed -- so branching on the payload would render the informational sentence over a read that had
 * actually failed. `isFailed` is the flag the hook names as the one to branch on.
 *
 * Errors: none of this module's exported helpers throws either. Each is a pure function over strings and
 * arrays -- {@link isUnsuppliedFilter}, {@link isAccountFilterAbsent}, {@link isAccountFilterWellFormed},
 * {@link buildCardListQuery}, {@link reduceCardListSelection} and {@link buildCardListColumns} -- and a
 * malformed entry is REPORTED as a refusal sentence in the return value rather than raised, because the
 * reference reports every one of its own edits that way: it moves a sentence into a message field and
 * re-sends the map, and it has no abend path for a bad entry.
 * @returns {ReactElement} The card list screen.
 */
export function CardListScreen(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  /*
   * WHY : ⚠️ Assumptions: an arrival may CARRY the narrowing that was in force when this browse last
   *       transferred out, and re-establishing it is the reference's behaviour rather than a
   *       convenience. `app/cbl/COCRDLIC.cbl` L838 to L855 repaints the account filter field from the
   *       carried `CDEMO-ACCT-ID` on every entry EXCEPT a fresh one from the menu -- its `WHEN OTHER`
   *       arm at L852 to L853 moves the carried identifier into `ACCTSIDO` and sets the modified-data
   *       tag on the field. The identifier survives because the COMMAREA does: the transfer arms at
   *       L520 to L534 and L548 to L562 write it before `XCTL`, and the destination hands the same area
   *       back on its own exit arm (`app/cbl/COCRDUPC.cbl` L442 to L454). So an operator who narrowed
   *       the browse, opened a card and pressed the exit key came back to the narrowed browse.
   * WHY : Assumptions: the carrier is the history entry's STATE, read through the shared validator,
   *       and never a query member -- the same rule the origin hand-over records on
   *       {@link BROWSE_ORIGIN}, so one statement governs everything that may appear in a request line.
   *       `ui/src/routes/navigation.ts` L58 to L62 declares this exact member for this exact purpose.
   * WHY : Trade-offs: the fresh-arrival arm is the reference's `CONTINUE` at L841 to L842, reached when
   *       the entry came from the menu. Here the equivalent is simply an arrival carrying no
   *       identifier, which every menu transition is, so the two agree without this screen having to
   *       ask which screen it was reached from.
   */
  const forwardedNarrowing = screenTransitionState(location.state).accountId;
  /*
   * WHY : Assumptions: a carried identifier is VALIDATED before it is adopted, and a malformed one is
   *       dropped rather than reported. It reaches this screen through router state, which is
   *       ordinary client-controlled input, and the service's own contract refuses anything that is
   *       not eleven digits -- so adopting one unchecked would send a request the contract rejects and
   *       replace the reference's own filter sentence with a service problem document. There is no
   *       sentence to state for it either: the reference has no arm for a malformed carried
   *       identifier, because a COMMAREA field could not be malformed.
   */
  const adoptedNarrowing =
    forwardedNarrowing !== undefined && isAccountFilterWellFormed(forwardedNarrowing)
      ? forwardedNarrowing.trim()
      : '';
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: the token map is read as CSS-VARIABLE references rather than as resolved values,
   *       which is what `cssVar` yields and what antd 6 defaults to. A resolved value would be baked
   *       into an inline style at paint time, so a later theme change would not reach it; a
   *       `var(--…)` reference follows the theme `ui/src/App.tsx` configures. This is also why the
   *       screen needs no `ConfigProvider` of its own -- it consumes the one already in the tree.
   */
  const { cssVar } = theme.useToken();
  const [error, setError] = useState<string | null>(null);

  /*
   * WHY : ⚠️ Refactoring Rationale: which FILTER a refusal blames is held as state beside the sentence,
   *       where the sentence alone was held before. A browser run reported the consequence: a refused
   *       filter marked no control at all -- no `aria-invalid`, no description, no error border -- so an
   *       operator who returned focus to the field was told nothing, and the sentence existed only in the
   *       shared band. The 3270 does not have that gap: `app/cpy/CSSETATY.cpy` moves `DFHRED` into the
   *       FIELD's attribute byte as well as writing the message line, so the field itself carries the
   *       refusal. This is the target's spelling of that attribute.
   * WHY : ⚠️ Refactoring Rationale: this records a refusal for EACH field where it recorded one field
   *       name, and the single form encoded a claim about the reference that is not true. That claim
   *       was that "the
   *       two edits are exclusive -- `2210-EDIT-ACCOUNT` refuses and returns before `2220-EDIT-CARD`
   *       runs", so at most one field could ever be marked. `2200-EDIT-INPUTS` performs BOTH edits
   *       unconditionally, at `app/cbl/COCRDLIC.cbl` L989 to L993, and the `GO TO` at L1025 leaves only
   *       `2210-EDIT-ACCOUNT` itself -- it is that paragraph's own exit label at L1032, not the
   *       caller's -- so `FLG-ACCTFILTER-NOT-OK` and `FLG-CARDFILTER-NOT-OK` can both be set on one
   *       turn. The consequence is visible: the two highlight tests at L872 and L877 are INDEPENDENT
   *       `IF`s, so an operator who malforms both entries sees BOTH fields reddened while the band
   *       carries only the account sentence. Marking one field was a real loss of fidelity, because the
   *       card field would look accepted while its own edit had refused it.
   * WHY : Assumptions: the SENTENCE precedence is unaffected and stays account-first, because that is a
   *       separate mechanism -- the account arm writes `WS-ERROR-MSG` unconditionally at L1021 to L1023
   *       while the card arm writes its own only `IF WS-ERROR-MSG-OFF` at L1056. So the two halves the
   *       reference exhibits are now both reproduced: one sentence, up to two marked fields.
   * WHY : ⚠️ Refactoring Rationale: each refused field carries its OWN sentence rather than sharing the
   *       band's. A first attempt held only the list of refused field names and rendered the single band
   *       sentence into every refused field's description; an ad-hoc render proved the consequence --
   *       with both entries malformed, the CARD field's description announced
   *       `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, so a screen-reader user who focused
   *       the card field was told about the account field. The band still shows one sentence, and the
   *       precedence is still the account's; what is per-field is what the FIELD says about itself.
   */
  const [filterRefusals, setFilterRefusals] = useState<FilterRefusals>(NO_FILTER_REFUSALS);

  /**
   * Reports whether the last turn's filter edit refused one named field.
   *
   * Assumptions: each field is tested on its own, because up to TWO may be refused at once for the
   * reason recorded above -- the reference's two highlight tests are independent `IF`s.
   * @param {FilterFieldName} field - The filter field to test.
   * @returns {boolean} True when that field carries a refusal from the last turn.
   */
  function isFilterRefused(field: FilterFieldName): boolean {
    return filterRefusals[field] !== null;
  }
  const [cardNumber, setCardNumber] = useState('');
  /*
   * WHY : Assumptions: the entry field OPENS on the carried narrowing, which is what makes the
   *       repainted field of `app/cbl/COCRDLIC.cbl` L852 to L853 observable -- the reference paints the
   *       carried identifier into the field itself, not merely into the request. An initial value
   *       rather than an effect is deliberate: a route change unmounts and remounts this screen, so the
   *       first paint already knows the narrowing, and an effect would show one frame of an empty box
   *       with a narrowed list beneath it -- the exact disagreement between box and rows that the
   *       finding this addresses describes in the other direction.
   */
  const [accountFilter, setAccountFilter] = useState(adoptedNarrowing);
  // WHY : Assumptions: the ENTRY and the APPLIED narrowing are separate pieces of state, because the
  //       reference separates them too: `CC-ACCT-ID` is the received map field and `CDEMO-ACCT-ID` is
  //       what `2210-EDIT-ACCOUNT` moves into the carried area once the edit has passed
  //       (`app/cbl/COCRDLIC.cbl` L1027). Reading the page from the entry directly would re-narrow the
  //       browse on every keystroke, and a cursor sealed under one narrowing addresses nothing under
  //       another.
  // WHY : Assumptions: the APPLIED narrowing opens on the same carried value, so the opening read is
  //       narrowed rather than the box merely looking narrowed. These two are seeded from one
  //       expression for that reason: seeding only the entry would show the operator their filter over
  //       an unfiltered list, and seeding only the applied value would narrow the list beside an empty
  //       box. The reference cannot exhibit either, because one COMMAREA field feeds both.
  const [appliedAccountId, setAppliedAccountId] = useState(adoptedNarrowing);
  const [resolving, setResolving] = useState(false);
  /*
   * WHY : ⚠️ Assumptions: the action characters are held POSITIONALLY, one per rendered row, because
   *       that is what the reference holds -- `WS-EDIT-SELECT-FLAGS PIC X(7)` redefined as
   *       `WS-EDIT-SELECT OCCURS 7 TIMES` at `app/cbl/COCRDLIC.cbl` L72 to L76, inspected as one field
   *       by `2250-EDIT-ARRAY`. A map keyed by each row's selector would express the same entries, but
   *       the tally that produces the more-than-one refusal is defined over the field in row order, and
   *       the row marks it returns are subscripts into that same order.
   * WHY : Assumptions: the entries are CLEARED whenever a new page is delivered, which the reset below
   *       does through the page ordinal. A character typed against row three of one page must not
   *       survive onto row three of the next, because it would then act on a different card than the
   *       operator was looking at.
   * WHY : Assumptions: the marked rows are subscripts and not selectors, matching
   *       `WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)` at `app/cbl/COCRDLIC.cbl` L83 to L88, whose
   *       `WS-ROW-CRDSELECT-ERROR` is read per row by `1250-SETUP-ARRAY-ATTRIBS`.
   * WHY : ⚠️ Alternatives Considered: clearing the entries from an effect that watches the page
   *       ordinal. Rejected because an effect runs AFTER the render that already painted the new page,
   *       so for one frame row three of the new page would show the character typed against row three
   *       of the old one -- and a turn taken in that frame would act on a card the operator never
   *       selected. Stamping the entries with the ordinal they were typed against makes the staleness
   *       impossible to observe rather than merely brief: the entries are simply not this page's.
   */
  const [selection, setSelection] = useState<{
    readonly pageNumber: number;
    readonly entries: readonly string[];
    readonly erroredRows: readonly number[];
  }>({ pageNumber: CARD_LIST_FIRST_PAGE, entries: [], erroredRows: [] });

  /*
   * WHY : Assumptions: this is `WS-CA-LAST-PAGE-DISPLAYED` (`app/cbl/COCRDLIC.cbl` L239 to L241), whose
   *       whole purpose is to distinguish the FIRST forward key that discovers the end of the browse
   *       from a later one. The reference clears it on every attention identifier that is not PF8 --
   *       `IF CCARD-AID-PFK08 CONTINUE ELSE SET CA-LAST-PAGE-NOT-SHOWN TO TRUE` at L410 to L414 -- so
   *       the two handlers that answer the other keys clear it here for the same reason.
   */
  const [endOfBrowseReported, setEndOfBrowseReported] = useState(false);
  /*
   * WHY : Assumptions: the TYPED entry and the APPLIED narrowing are held separately, because the source
   *       separates them too -- the map field holds what an operator typed and `CDEMO-ACCT-ID` holds what
   *       the edit accepted (`app/cbl/COCRDLIC.cbl` L1026), and only the accepted value reaches the
   *       browse. Reading the browse from the typed entry would re-narrow the list on every keystroke and
   *       would send a half-typed identifier the contract refuses.
   */
  /*
   * WHY : Refactoring Rationale: an `accountNumber` state pair stood here beside `accountFilter`
   *       above, and the two were one entry box under two names -- the box was bound to this one while
   *       the narrowing that reaches the service was read off the other, so a typed account filtered
   *       nothing. The box now binds to `accountFilter`, which is the value applyFilters validates and
   *       applies, so what the operator types is what narrows the browse.
   */
  /*
   * WHY : Refactoring Rationale: a SECOND `appliedAccountId` state pair stood here. One entry box has
   *       one applied narrowing, and two pieces of state for it meant the browse's reset key and the
   *       value the query carried could disagree -- the list would reload against one narrowing while
   *       the header described another.
   */

  const enteredNumberIsAddressable = isCardNumber(cardNumber);

  const fetchPage = useCallback(
    /**
     * Reads one page of cards under the narrowing in force.
     * @param {object} request - The browse step the paging hook is taking.
     * @param {string | null} request.cursor - Sealed cursor, or `null` for the opening read.
     * @param {PageDirection} request.direction - Direction that cursor was sealed for.
     * @returns {Promise<PageResponse<CardSummary>>} One bounded page.
     * @throws {Error} The normalised failure from `ui/src/api/client.ts`, which the hook surfaces
     *   through its own failure state rather than this function handling it.
     */
    async (request: {
      cursor: string | null;
      direction: PageDirection;
    }): Promise<PageResponse<CardSummary>> =>
      listCards(buildCardListQuery(request.cursor, request.direction, appliedAccountId)),
    [appliedAccountId],
  );

  /*
   * WHY : Assumptions: the restart key is the APPLIED narrowing, so applying or clearing the account
   *       filter returns the browse to its opening page. That is the reference's own behaviour: a
   *       changed filter reaches `2210-EDIT-ACCOUNT`, which rewrites the carried identifier, and the
   *       paging variables are re-established for the new narrowing rather than a cursor sealed under
   *       the old one being replayed.
   * WHY : Alternatives Considered: calling the hook's imperative `reset` from the filter handler.
   *       Rejected because the hook publishes `resetKey` for exactly this and states that a change to it
   *       restarts the browse, so the declarative form cannot fall out of step with the applied value
   *       the way a forgotten call could.
   */
  const browse = usePagedQuery<CardSummary>({
    pageSize: CARD_LIST_PAGE_SIZE,
    fetchPage,
    resetKey: appliedAccountId,
  });

  /*
   * WHY : Assumptions: entries and marks belonging to another page read as absent, which is what makes
   *       the stamp above load-bearing rather than decorative.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: which DIRECTION a page turn is outstanding in is now tracked, so the
   *       two paging keys can be declined independently. `ui/src/hooks/usePagedQuery.ts` publishes
   *       `isLoading` for the browse as a whole and does not say which way the outstanding turn went, and
   *       one flag for both keys is the wrong shape here: it would decline `F7` because `F8` is waiting,
   *       which withdraws a key at a moment the reference does not -- a 3270 inhibits the keyboard for
   *       the turn and then releases EVERY key at once, never one key because of another.
   * WHY : ⚠️ Assumptions: a ref rather than state, because the value is read on the DISPATCH path --
   *       `ui/src/layout/usePfKeys.ts` evaluates a `busy` predicate inside `invoke`, in the same task as
   *       the key event -- and a state update scheduled by the first press is not visible to a second
   *       press in that same task. That is the measured double-submit shape: two identical reads 400 ms
   *       apart. The rendered affordance is not lost by using a ref: `isLoading` flips on dispatch and
   *       again on settlement, so a render happens on both edges and reads the current value.
   * WHY : Trade-offs: the ref is cleared in the SAME settled handler that discards the turn's outcome,
   *       so a turn that rejects clears it too. A flag left set would make the key inert, which is the
   *       hazard `usePfKeys` names for `busy`, and clearing it on both outcomes is what forecloses that.
   */
  const outstandingPageDirection = useRef<PageDirection | null>(null);

  const selectionIsCurrent = selection.pageNumber === browse.pageNumber;
  const actionEntries = selectionIsCurrent ? selection.entries : [];
  const erroredRows = selectionIsCurrent ? selection.erroredRows : [];

  /*
   * WHY : Refactoring Rationale: a `loadPage` helper stood here and is withdrawn. It drove the
   *       browse by hand -- its own loading flag, its own page state, its own screen-number
   *       arithmetic and its own error arm -- while this screen also composes usePagedQuery, so two
   *       mechanisms held the same cursor and only one of them was rendered. The hook is retained
   *       because the keyset contract lives in it: it seals the cursor it was handed, refuses a
   *       direction it has no cursor for, and is the mechanism every other browse screen uses.
   */

  /*
   * WHY : Refactoring Rationale: a `loadPageWith` helper stood here and is withdrawn. It drove the
   *       browse by hand -- its own loading flag, its own page state, its own screen-number
   *       arithmetic and its own error arm -- while this screen also composes usePagedQuery, so two
   *       mechanisms held the same cursor and only one of them was rendered. The hook is retained
   *       because the keyset contract lives in it: it seals the cursor it was handed, refuses a
   *       direction it has no cursor for, and is the mechanism every other browse screen uses.
   */

  /*
   * WHY : Refactoring Rationale: a mount effect calling the withdrawn loader stood here. usePagedQuery
   *       loads its own first page and reloads when its resetKey changes, so an effect here fetched the
   *       first page a second time on every mount.
   */

  /**
   * Edits both narrowing entries in the reference's own order and applies or resolves accordingly.
   *
   * Assumptions: the ACCOUNT entry is edited first and its refusal wins, which is the reference's
   * order and its own precedence. `2200-EDIT-INPUTS` performs `2210-EDIT-ACCOUNT` and then
   * `2220-EDIT-CARD` (`app/cbl/COCRDLIC.cbl` L983 to L996); the account arm moves its sentence into
   * `WS-ERROR-MSG` unconditionally at L1021 to L1023 while the card arm moves its own only while no
   * message is set, so an operator who malforms both entries reads the account sentence.
   *
   * Assumptions: a partially typed card number is not sent anywhere. The contract refuses any width but
   * sixteen, so acting on four digits would answer HTTP 400 rather than anything useful.
   *
   * Refactoring Rationale: an entered CARD number RESOLVES to its one card rather than narrowing the
   * page, and the contract is what settles that: `card-api.yaml` declares no `cardNumber` query
   * parameter, so `listCards` has nowhere to put one -- and it has nowhere to put one because a query
   * string is written verbatim into the load balancer's mandatory access log, which is the same reason
   * the number left every path. `CardApiContractTest.noRequestLineCanCarryACardNumber` fails if either
   * is reintroduced.
   *
   * ⚠️ Refactoring Rationale: the ACCOUNT number is different and IS sent, as a request-body member. It
   * is the `CARDAIX` access path -- the alternate index `app/jcl/CARDFILE.jcl` builds and the reference
   * browses under -- and this screen declared its label without ever rendering a control for it, so the
   * one narrowing the baseline offers on this screen was unreachable. An eleven-digit account number is
   * not a primary account number and discloses no card, and it travels in the body rather than the
   * request line for the same reason everything else on this screen does.
   *
   * Assumptions: resolving a card number loses nothing the baseline did. `CARDSIDI PIC X(16)` at
   * `app/cpy-bms/COCRDLI.CPY` L72 narrowed the list by card number, and the card number is the unique
   * primary key, so that narrowing could only ever yield ONE row -- which is the row this opens. The
   * divergence is registered as D-CARD-SELECTOR.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function applyFilters(): void {
    /*
     * WHY : ⚠️ Assumptions: a turn arriving while a read is outstanding is IGNORED, and the guard lives
     *       here rather than being expressed as a disabled control. A disabled binding reports through
     *       `usePfKeys`' invalid-key channel, and this screen's invalid-key arm coerces an unrecognised
     *       key back into this very function -- so a disabled Enter would loop. The reference needs no
     *       such guard because a terminal turn is serialised and a second key could not arrive while the
     *       first was being processed; a browser has no such serialisation, so pressing Filter twice used
     *       to issue two reads whose settlements both applied.
     */
    if (browse.isLoading || resolving) {
      return;
    }

    setError(null);
    setFilterRefusals(NO_FILTER_REFUSALS);

    /*
     * WHY : ⚠️ Assumptions: BOTH entries are edited on every turn and neither edit short-circuits the
     *       other, because `2200-EDIT-INPUTS` performs `2210-EDIT-ACCOUNT` and then `2220-EDIT-CARD`
     *       unconditionally at `app/cbl/COCRDLIC.cbl` L989 to L993. The `GO TO` inside each arm leaves
     *       that arm's own exit label and not the caller, so a refused account entry does not prevent
     *       the card entry from being judged.
     * WHY : Refactoring Rationale: the two refusals are collected first and dispatched afterwards,
     *       where an earlier revision returned from the account arm immediately. That early return
     *       marked only the account field, so on a turn where BOTH entries were malformed the card
     *       field rendered as though it had been accepted -- while the reference reddens both, through
     *       two independent `IF`s at L872 and L877.
     */
    const accountRefusal =
      !isAccountFilterAbsent(accountFilter) && !isAccountFilterWellFormed(accountFilter)
        ? SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER
        : null;
    const cardRefusal =
      cardNumber !== '' && !enteredNumberIsAddressable
        ? SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER
        : null;

    if (accountRefusal !== null || cardRefusal !== null) {
      /*
       * WHY : Assumptions: the ACCOUNT sentence wins the band whenever the account entry is one of the
       *       refused, because the account arm moves its text into `WS-ERROR-MSG` unconditionally at
       *       `app/cbl/COCRDLIC.cbl` L1021 to L1023 while the card arm moves its own only
       *       `IF WS-ERROR-MSG-OFF` at L1056. One sentence, and it is the first arm's.
       * WHY : Refactoring Rationale: the card sentence is the LIST screen's own wording and not the
       *       detail screen's. An earlier revision rendered `Card number if supplied must be a 16
       *       digit number`, which is what `app/cbl/COCRDSLC.cbl` L149 and `app/cbl/COCRDUPC.cbl` L194
       *       declare; this program declares the upper-case filter form at L1058. The two are
       *       near-duplicates, which is why the wrong one read as correct in review, and why the
       *       catalog holds both rather than folding them together.
       */
      setError(accountRefusal ?? cardRefusal);
      /*
       * WHY : Refactoring Rationale: the refused FIELDS are recorded beside the sentence, which the
       *       version this replaced did not do at all. The band states what is wrong and this marks
       *       WHICH entry boxes it is about, so each carries the error styling and its description is
       *       announced with it; a message naming "the account filter" beside two unmarked boxes
       *       leaves a screen-reader user to guess. `app/cpy/CSSETATY.cpy` L17 to L27 moves `DFHRED`
       *       into the FIELD's attribute byte as well as writing the message line, so this is the
       *       target's spelling of that attribute.
       * WHY : Assumptions: recording them also protects every row, because this screen's
       *       `rowsProtected` is derived from exactly this state -- which is the reference's own
       *       coupling, `FLG-PROTECT-SELECT-ROWS-YES` being set inside the two refusing arms at L1020
       *       and L1055.
       */
      setFilterRefusals({ accountNumber: accountRefusal, cardNumber: cardRefusal });
      return;
    }

    /*
     * WHY : Assumptions: the account narrowing is applied BEFORE a card entry is resolved, so a turn
     *       carrying both leaves the list narrowed behind the card the operator opened. The reference
     *       edits both fields on one turn too, and its account edit writes the carried identifier
     *       whatever the card field holds.
     */
    const narrowing = isAccountFilterAbsent(accountFilter) ? '' : accountFilter.trim();

    setAppliedAccountId(narrowing);

    if (cardNumber !== '') {
      openEnteredCard(cardDetailPath);
      return;
    }

    /*
     * WHY : ⚠️ Assumptions: the re-read is issued ONLY when the narrowing is unchanged, and the condition
     *       is load-bearing. A changed narrowing moves the hook's `resetKey`, which the hook documents as
     *       restarting the browse at its opening page -- so calling `reset` as well would issue a SECOND
     *       read, and it would issue it during the render that still holds the previous narrowing, so the
     *       two reads would carry different request bodies and the later-settling one would win. An
     *       unchanged narrowing moves no key, so nothing would read at all without this call, and Enter
     *       under an unchanged filter IS a read in the reference: its Enter arm performs
     *       `9000-READ-FORWARD` and re-sends the map (`app/cbl/COCRDLIC.cbl` L565 to L578).
     */
    if (narrowing === appliedAccountId) {
      browse.reset().then(ignoreSettledTurn, ignoreSettledTurn);
    }
  }

  /**
   * Records the character typed into one row's action field.
   *
   * Assumptions: the entry is stored VERBATIM, with no case folding and no filtering of the character
   * typed, because `2250-EDIT-ARRAY` is what judges it and the reference judges the byte the terminal
   * sent. Rejecting a stray character at the keystroke would remove `INVALID ACTION CODE` from the
   * screen's observable behaviour, and upper-casing one would accept an entry the reference refuses.
   * @param {number} index - Position of the row whose field was typed into.
   * @param {string} entry - The field's contents as they now stand, at most one character.
   * @returns {void} Nothing; the entry is recorded as a side effect.
   */
  function recordActionEntry(index: number, entry: string): void {
    setSelection(
      /**
       * Replaces the one position that changed, leaving every other row's entry alone.
       *
       * Assumptions: the result is stamped with the page ordinal on display, so an entry can never be
       * read against a different page's rows.
       * @param {object} current - The selection state as it stood.
       * @param {number} current.pageNumber - Page ordinal those entries were typed against.
       * @param {readonly string[]} current.entries - The entries as they stood.
       * @param {readonly number[]} current.erroredRows - Rows the last turn marked.
       * @returns {object} The selection state with this row's entry replaced and its mark cleared.
       */
      (current: {
        readonly pageNumber: number;
        readonly entries: readonly string[];
        readonly erroredRows: readonly number[];
      }): {
        readonly pageNumber: number;
        readonly entries: readonly string[];
        readonly erroredRows: readonly number[];
      } => {
        const carried = current.pageNumber === browse.pageNumber ? current.entries : [];
        return {
          pageNumber: browse.pageNumber,
          entries: Array.from(
            { length: browse.items.length },
            /**
             * Reads one row's entry, substituting the newly typed character at its own position.
             * @param {unknown} _unused - Array.from's element argument, which is always undefined here.
             * @param {number} position - The row position being filled.
             * @returns {string} That row's action entry.
             */
            (_unused: unknown, position: number): string =>
              position === index ? entry : (carried[position] ?? ''),
          ),
          /*
           * WHY : Assumptions: typing into a marked field CLEARS that row's mark, because the
           *       reference recomputes `WS-EDIT-SELECT-ERROR-FLAGS` from scratch on the next turn --
           *       `2250-EDIT-ARRAY` rebuilds it at `app/cbl/COCRDLIC.cbl` L1088 to L1093 and L1104
           *       rather than accumulating it. Leaving the mark until the next turn would show a
           *       refusal against a value the operator had already corrected.
           */
          erroredRows: current.erroredRows.filter(
            /**
             * Keeps the marks of rows other than the one just typed into.
             * @param {number} marked - A marked row's position.
             * @returns {boolean} True when that mark belongs to another row.
             */
            (marked: number): boolean => marked !== index,
          ),
        };
      },
    );
  }

  /**
   * Builds the hand-over every transfer out of this browse carries.
   *
   * Purpose: carry BOTH halves of what the reference's transfer arms write into the carried area before
   * `EXEC CICS XCTL` -- the origin, from `MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID`, and the narrowing
   * in force, from `MOVE CC-ACCT-ID TO CDEMO-ACCT-ID` (`app/cbl/COCRDLIC.cbl` L520 to L534 for the
   * detail arm and L548 to L562 for the update arm; the narrowing itself is written by
   * `2210-EDIT-ACCOUNT` at L1027). The destination hands the same area back on its exit arm, which is
   * what returns the operator to the browse they left rather than to an unnarrowed one.
   *
   * Assumptions: the APPLIED narrowing travels and not the entry, because the applied value is the one
   * the reference carries -- `CDEMO-ACCT-ID` holds what the edit accepted, and the entry field may hold
   * a half-typed identifier the edit has not seen. Handing the entry over would return an operator to a
   * browse narrowed by something the service never accepted.
   *
   * Assumptions: an absent narrowing is an ABSENT member rather than an empty string, because
   * `ui/tsconfig.json` enables `exactOptionalPropertyTypes` and because the arrival arm treats absence
   * as "this arrival carries none" -- an empty string would be a carried value that then has to be
   * distinguished from a real one at the far end.
   * @returns {ScreenTransitionState} The origin, and the narrowing when one is in force.
   */
  function transferHandover(): ScreenTransitionState {
    return {
      ...BROWSE_ORIGIN,
      ...(appliedAccountId === '' ? {} : { accountId: appliedAccountId }),
    };
  }

  /**
   * Opens one row's card detail immediately, for a pointer user.
   *
   * Assumptions: the row's own sealed selector is the address, so no card number reaches the route.
   * This is the `'S'` transfer arm's destination (`app/cbl/COCRDLIC.cbl` L526 moves `LIT-CARDDTLPGM`,
   * whose mapset `COCRDSL` is the detail screen) reached without a turn, which is the accommodation
   * recorded on the controls themselves.
   *
   * Assumptions: the transfer hands over this screen's route as the destination's caller, which is
   * the same arm's `MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID` at L520-L521. The reasoning for the
   * carrier, and for it not being a path or query member, is recorded on {@link BROWSE_ORIGIN}.
   * @param {CardSummary} row - The row whose detail to open.
   * @returns {void} Nothing; navigation is the effect.
   */
  function openRowDetail(row: CardSummary): void {
    navigateSafely(navigate, cardDetailPath(row.key), transferHandover());
  }

  /**
   * Opens one row's card update form immediately, for a pointer user.
   *
   * Assumptions: the `'U'` transfer arm's destination, `LIT-CARDUPDPGM` at `app/cbl/COCRDLIC.cbl` L554,
   * addressed by the row's sealed selector for the same disclosure reason as the detail route.
   *
   * Assumptions: this transfer hands over the origin too, and the update screen is the one that most
   * needs it -- `app/cbl/COCRDUPC.cbl` L442-L454 resolves PF3 to the recorded caller and only falls
   * back when none was recorded, so a handover-free transfer sent an operator who came from this
   * browse to the update screen's fallback instead. See {@link BROWSE_ORIGIN}.
   * @param {CardSummary} row - The row whose update form to open.
   * @returns {void} Nothing; navigation is the effect.
   */
  function openRowUpdate(row: CardSummary): void {
    navigateSafely(navigate, cardEditPath(row.key), transferHandover());
  }

  /**
   * Runs one Enter turn in the reference's own order: edit the filters, then the selection column.
   *
   * Purpose: reproduce `2200-EDIT-INPUTS` followed by the transfer arms of the main `EVALUATE`. It is
   * what makes the two selection refusals reachable and what keeps a row action a TURN rather than a
   * click, which is the workflow the 3270 screen has.
   *
   * Assumptions: the order is filters first and the selection second, from `app/cbl/COCRDLIC.cbl` L989
   * to L996, and the selection edit is SKIPPED entirely when either filter refused -- `2250-EDIT-ARRAY`
   * opens with `IF INPUT-ERROR GO TO 2250-EDIT-ARRAY-EXIT` at L1075 to L1077. That is the same turn on
   * which every row's field is protected, so an entry made against a refused filter could not have been
   * acted on in any case.
   *
   * Assumptions: a refused filter also suppresses the re-read, because the `WHEN INPUT-ERROR` arm reads
   * forward only `IF NOT FLG-ACCTFILTER-NOT-OK AND NOT FLG-CARDFILTER-NOT-OK` at L431 to L435.
   * `applyFilters` expresses that by returning before its own read.
   *
   * Assumptions: a turn carrying NO action character falls through to the narrowing and the re-read,
   * which is the `WHEN OTHER` arm at L572 to L582 -- it reads forward and re-sends the map.
   * @returns {void} Completion is represented by the screen's own state and by navigation.
   */
  function submitTurn(): void {
    if (browse.isLoading || resolving) {
      return;
    }

    // WHY : Assumptions: Enter clears the last-page flag for the reason recorded on PF7 -- the
    //       reference clears it on any attention identifier other than PF8 (L410 to L414).
    setEndOfBrowseReported(false);

    const edit = reduceCardListSelection(actionEntries.slice(0, browse.items.length));

    /*
     * WHY : Assumptions: the FILTER edit is consulted first even though the selection was reduced
     *       above, because the reference's precedence is the filters'. Reducing first costs nothing and
     *       keeps the reduction a pure function of the entries; `applyFilters` returning a refusal is
     *       what discards it, exactly as `2250-EDIT-ARRAY`'s own opening test discards its work.
     */
    const filtersRefused =
      (!isAccountFilterAbsent(accountFilter) && !isAccountFilterWellFormed(accountFilter)) ||
      (cardNumber !== '' && !enteredNumberIsAddressable);

    if (filtersRefused || edit.message === null) {
      setSelection({ pageNumber: browse.pageNumber, entries: actionEntries, erroredRows: [] });
      if (edit.selectedRow !== null && !filtersRefused) {
        const row = browse.items[edit.selectedRow];
        if (row !== undefined) {
          /*
           * WHY : ⚠️ Assumptions: BOTH of the row's identifying values travel, which is what the
           *       transfer arms do -- L531 to L534 move `WS-ROW-ACCTNO(I-SELECTED)` into
           *       `CDEMO-ACCT-ID` and `WS-ROW-CARD-NUM(I-SELECTED)` into `CDEMO-CARD-NUM`, and L559 to
           *       L562 repeat it for the update arm. They travel INSIDE the row's own sealed selector
           *       rather than as two route parameters: the selector addresses one card and the detail
           *       response republishes both members, so nothing is lost and no account number or card
           *       number reaches a request line.
           * WHY : Alternatives Considered: carrying the account number as a query parameter, which the
           *       folder brief offers as one option. Rejected because a query string is written
           *       verbatim into the load balancer's access log, which is the same reason the card
           *       number left every path on this screen, and because
           *       `CardApiContractTest.noRequestLineCanCarryACardNumber` asserts that boundary.
           * WHY : Assumptions: the ORIGIN travels with them, which is the third thing both arms move --
           *       L520-L521 and L548-L549 write `LIT-THISTRANID` and `LIT-THISPGM` into
           *       `CDEMO-FROM-TRANID` and `CDEMO-FROM-PROGRAM` beside the two identifiers. It is a
           *       parameterless route and therefore discloses nothing, but it is still handed over as
           *       state rather than in the address, for the reason {@link BROWSE_ORIGIN} records.
           */
          navigateSafely(
            navigate,
            edit.action === 'U' ? cardEditPath(row.key) : cardDetailPath(row.key),
            transferHandover(),
          );
          return;
        }
      }
      applyFilters();
      return;
    }

    /*
     * WHY : Assumptions: a selection refusal leaves the page exactly as it is and issues no read. The
     *       `WHEN INPUT-ERROR` arm re-sends the SAME map (L436 to L438), so the rows an operator was
     *       looking at stay on screen beneath the sentence and their marks.
     */
    setError(edit.message);
    setFilterRefusals(NO_FILTER_REFUSALS);
    setSelection({
      pageNumber: browse.pageNumber,
      entries: actionEntries,
      erroredRows: edit.erroredRows,
    });
  }

  /**
   * Exchanges the entered card number for a selector and navigates to the requested screen.
   *
   * Assumptions: the number is exchanged rather than being interpolated into the route, because a
   * request line reaches browser history, referrer headers and every intermediary's access log while a
   * request body reaches none of them. The exchange is one extra round trip and it is the whole reason
   * no primary account number appears in any card URL.
   *
   * Assumptions: the resolved transfer carries the same origin the two row arms carry, because the
   * destination cannot tell the two apart and must not behave differently: both arrive from this
   * browse, so both leave it by PF3. Omitting it here while supplying it on the row controls would
   * make the exit key's destination depend on which control opened the card, which no reference arm
   * does. See {@link BROWSE_ORIGIN}.
   * @param {(selector: string) => string} buildPath - Builds the destination route from the selector the
   *   lookup returns; either the detail route or the update route.
   */
  function openEnteredCard(buildPath: (selector: string) => string): void {
    if (!enteredNumberIsAddressable) {
      setError(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER);
      setFilterRefusals({
        accountNumber: null,
        cardNumber: SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
      });
      return;
    }
    setResolving(true);
    setError(null);
    setFilterRefusals(NO_FILTER_REFUSALS);
    lookupCard(cardNumber).then(
      /**
       * Navigates to the resolved card.
       * @param {object} answer - The lookup answer.
       * @param {string} answer.selector - The opaque selector addressing the entered card.
       */
      (answer) => {
        setResolving(false);
        navigateSafely(navigate, buildPath(answer.key), transferHandover());
      },
      /*
       * WHY : Assumptions: the failure text names no card and does not distinguish "no such card" from
       *       a transport fault. Distinguishing them would turn this control into an oracle that
       *       confirms whether a guessed number exists, which is the enumeration a masked rendering
       *       exists to prevent.
       */
      /** Reports an unresolved entry without confirming whether the card exists. */
      () => {
        setResolving(false);
        setError(CARD_LIST_STATUS_MESSAGES.WS_NO_RECORDS_FOUND.text);
      },
    );
  }

  /**
   * Releases the paging key whose turn has settled, whichever way that turn went.
   *
   * Assumptions: the outcome itself is discarded for the reason {@link ignoreSettledTurn} records -- the
   * hook applies every outcome through its own reducer, so there is nothing at the call site to act on
   * -- and this adds exactly one effect to that: clearing the direction so the key becomes pressable
   * again. It is supplied for BOTH outcomes, so a rejected turn cannot leave a key inert.
   * @returns {void} Nothing; the release is the effect.
   */
  function releaseOutstandingPageDirection(): void {
    outstandingPageDirection.current = null;
  }

  /**
   * Pages backward from this page's leading boundary, or reports that there is nowhere to go.
   *
   * Assumptions: the refusal is a MESSAGE and not a disabled key, because that is what the source
   * program does: `app/cbl/COCRDLIC.cbl` L901-L904 moves `NO PREVIOUS PAGES TO DISPLAY` into the
   * error field when PF7 arrives on the first page, and the arm at L444-L453 still re-reads the page.
   * PF7 is never refused outright there, so binding it disabled would remove a message an operator
   * reads.
   *
   * Assumptions: "nowhere to go" means the screen ordinal is still at the opening page. That ordinal is
   * held by the paging hook rather than being asked of the service, matching the reference, which decides
   * the same refusal from `WS-CA-SCREEN-NUM` alone.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function pageBackward(): void {
    /*
     * WHY : Refactoring Rationale: the refusal is decided by the SCREEN ORDINAL and not by any member of
     *       the page envelope, which is the reference's own arrangement: `app/cbl/COCRDLIC.cbl` L902
     *       tests `CA-FIRST-PAGE` on `WS-CA-SCREEN-NUM` and L903 moves the refusal sentence in. Two
     *       earlier arrangements were wrong in opposite directions. Gating on `firstKey` being present
     *       enabled this control on the opening page, because every page carrying rows names its own
     *       first row, and following it replaced the rows with an empty page. Gating on a
     *       service-computed backward flag made the service read backward from a page nobody asked for,
     *       and published an answer already stale by the time an operator acted on it.
     * WHY : ⚠️ Assumptions: the ordinal is now the hook's, published as `hasPrev`, which the hook
     *       documents as derived from that same ordinal AND from a leading cursor existing to read
     *       from -- the two halves this function used to test separately. The sentence stays here
     *       because the hook documents a step at a boundary as a silent no-op: it owes a screen the
     *       boundary STATE and the screen owes the operator the reference's own sentence.
     */
    setError(null);
    // WHY : Assumptions: PF7 clears the last-page flag, because the reference clears it on every
    //       attention identifier that is not PF8 (`app/cbl/COCRDLIC.cbl` L410 to L414). Without this a
    //       backward step followed by a forward one would report the pages sentence on the very first
    //       forward key that reaches the end again.
    setEndOfBrowseReported(false);

    if (!browse.hasPrev) {
      setError(CARD_LIST_PAGING_MESSAGES.NO_PREVIOUS_PAGES_TO_DISPLAY);
      return;
    }
    /*
     * WHY : Assumptions: the direction is recorded BEFORE the dispatch, synchronously, so a second
     *       press arriving in the same task already sees this key as busy. Recording it after the
     *       dispatch would leave the window the busy flag exists to close.
     */
    outstandingPageDirection.current = 'previous';
    browse.prevPage().then(releaseOutstandingPageDirection, releaseOutstandingPageDirection);
  }

  /**
   * Pages forward from this page's trailing boundary, or reports that there is nowhere to go.
   *
   * Assumptions: availability is read from `hasNext` rather than from the presence of a cursor,
   * because the envelope discovers a further page by requesting one row beyond this one. The refusal
   * sentence is the source's own, moved into the error field at `app/cbl/COCRDLIC.cbl` L905-L909.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function pageForward(): void {
    setError(null);

    if (!browse.hasNext) {
      /*
       * WHY : ⚠️ Assumptions: TWO different sentences answer an exhausted forward browse, and which one
       *       depends on whether the end was already known. `1400-SETUP-MESSAGE` moves
       *       `NO MORE PAGES TO DISPLAY` only when PF8 arrives with no next page AND
       *       `CA-LAST-PAGE-SHOWN` already true (`app/cbl/COCRDLIC.cbl` L905 to L909); the forward read
       *       itself moves `NO MORE RECORDS TO SHOW` at the moment it first hits end-of-file, at L1218
       *       to L1221 and L1238 to L1240, and L410 to L414 sets the last-page flag on any key that is
       *       not PF8. So the FIRST PF8 that finds the end reports the records sentence and a
       *       subsequent one reports the pages sentence.
       * WHY : Refactoring Rationale: only the pages sentence was rendered before, so
       *       `NO MORE RECORDS TO SHOW` -- a catalog entry keyed to this very program -- described
       *       behaviour the screen could not exhibit. The distinction is carried here by whether this
       *       page is the one that first reported no further page: the hook keeps the operator on the
       *       last page, so a second PF8 arrives with the end already known.
       * WHY : Assumptions: both sentences are guarded in the reference by `IF WS-ERROR-MSG-OFF`, and
       *       the `setError(null)` above is what makes that guard vacuous here -- nothing else can have
       *       written the band on this turn.
       */
      setError(
        endOfBrowseReported
          ? CARD_LIST_PAGING_MESSAGES.NO_MORE_PAGES_TO_DISPLAY
          : CARD_LIST_PAGING_MESSAGES.NO_MORE_RECORDS_TO_SHOW,
      );
      setEndOfBrowseReported(true);
      return;
    }
    // Assumptions: recorded before the dispatch, for the reason given on the backward key.
    outstandingPageDirection.current = 'next';
    browse.nextPage().then(releaseOutstandingPageDirection, releaseOutstandingPageDirection);
  }

  /*
   * WHY : Assumptions: exactly the four attention identifiers `app/cbl/COCRDLIC.cbl` L371-L374 admits
   *       are bound, and only three of them carry a label -- Enter's is empty because this mapset
   *       paints no Enter legend, which `usePfKeys` treats as a keyboard-only handler.
   * WHY : ⚠️ Refactoring Rationale: every key now DECLARES what its action risks, and none did.
   *       `ui/src/layout/PfKeyBar.tsx` used to derive emphasis from the attention identifier alone,
   *       through `PRIMARY_ACTION_AIDS`, and that table cannot be right across this application: the
   *       same identifier carries a browse on one mapset and a save or a delete on another, so one
   *       AID-keyed answer paints them identically. The risk is read off the LABEL -- what the key says
   *       it does -- and never off the key it is bound to.
   * WHY : ⚠️ Assumptions: ALL FOUR are `read-only`, and that is what this program is. `COCRDLIC` is a
   *       browse: `2200-EDIT-INPUTS` edits the filters and the selection array (L989-L996),
   *       `9000-READ-FORWARD` and `9100-READ-BACKWARDS` read pages, and PF3 transfers to the menu
   *       (L390-L399). There is no `WRITE`, `REWRITE` or `DELETE` anywhere in the program -- the two
   *       row action codes NAVIGATE to the detail and update screens, and the writing happens there,
   *       under that screen's own confirmation. So nothing here is `mutating`.
   * WHY : ⚠️ Assumptions: Enter's declaration is documentary, because `PfKeyBar` skips a binding whose
   *       label is blank (L473) and this mapset paints no Enter legend -- so there is no control whose
   *       emphasis it could resolve today. It is declared anyway rather than omitted: an omitted risk
   *       falls back to the AID table, which contains `ENTER`, so a future revision that gave this key
   *       a label would silently paint an acting emphasis on a browse.
   * WHY : ⚠️ Assumptions: the paging keys report busy INDEPENDENTLY, each on its own direction, and not
   *       on the browse's `isLoading`. One flag for both would decline `F7` because `F8` is waiting,
   *       which withdraws a key at a moment the reference does not; per-direction, a key declines only
   *       its OWN outstanding turn. `F3` declares no busy at all, so leaving the screen stays available
   *       while any read is in flight -- the reference never withdraws a key.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Runs one turn: edits the filters, then the selection column, then re-reads.
         *
         * Assumptions: Enter carries BOTH responsibilities on this screen, because the reference gives
         * it both -- `2200-EDIT-INPUTS` edits the filters and the selection array on the same turn
         * (`app/cbl/COCRDLIC.cbl` L989-L996), and the main `EVALUATE` then either transfers to a card
         * or performs `9000-READ-FORWARD` and re-sends the map (L517, L545, L572-L582).
         */
        onInvoke: () => {
          submitTurn();
        },
        label: CARD_LIST_KEY_LABELS.ENTER,
        risk: 'read-only',
        /*
         * WHY : Refactoring Rationale: the condition is the one `submitTurn` and `applyFilters` already
         *       test on entry, declared here as well rather than moved. Both are needed: the declaration
         *       is what makes the KEY decline silently, and the in-function test is what protects the
         *       other two ways in -- the Filter control's click, and this screen's own invalid-key arm,
         *       which coerces an unmapped key by calling `submitTurn` directly and so bypasses the key
         *       path's busy gate entirely.
         */
        busy: browse.isLoading || resolving,
      },
      PFK03: {
        /**
         * Returns to the main menu, which is where the source program transfers on PF3
         * (`app/cbl/COCRDLIC.cbl` L390-L399, moving `LIT-MENUPGM` into the next-program field).
         *
         * ⚠️ Refactoring Rationale: this wrote the exit sentence `PF03 PRESSED.EXITING` to the error
         * channel immediately before navigating, and the write has been REMOVED. The earlier note
         * defended it as travelling "in the communication area so the menu paints it", and that claim is
         * false in three independent ways, each checked against the baseline rather than reasoned about.
         * `WS-ERROR-MSG` is declared at `app/cbl/COCRDLIC.cbl` L117 inside WORKING-STORAGE, which an
         * `EXEC CICS XCTL` discards rather than carries. `app/cpy/COCOM01Y.cpy` holds NO message member
         * at all -- a search for `MSG` or `MESSAGE` across the whole copybook returns nothing -- so the
         * communication area has no field the sentence could travel in. And the arm itself puts
         * `SET WS-EXIT-MESSAGE TO TRUE` at L396 directly before the `EXEC CICS XCTL` at L402 with NO
         * `SEND MAP` between them, so the sentence is written into storage that is never transmitted and
         * is then thrown away by the transfer. The reference therefore shows nothing on exit, and
         * withdrawing the write is parity rather than a loss.
         *
         * ⚠️ Assumptions: the destination would discard it even if a carrier existed, which is worth
         * recording because it closes the last way the old claim could have been true.
         * `app/cbl/COMEN01C.cbl` L79-L80 clears its own message on entry -- `MOVE SPACES TO WS-MESSAGE,
         * ERRMSGO OF COMEN1AO` -- into a field it declares itself at L38. The menu blanks the line
         * before painting it.
         *
         * ⚠️ Refactoring Rationale: a browser run measured the defect this removes, and it was worse here
         * than the unobservable write two sibling screens withdrew for the same reason. A mutation
         * observer recorded the sentence appearing on `/menu` and being cleared 29 ms later, because the
         * band is published through the SHARED `useShellSlot` and the arriving screen registers its own
         * contribution one frame after this component unmounts. So the operator saw a 29 ms flash of a
         * sentence the reference never paints at all -- a spurious paint rather than a near-miss.
         *
         * Assumptions: the catalog entry STAYS in `ui/src/messages/messages.ts` and is simply not read
         * here, matching what `ui/src/screens/accountView/index.tsx` L1361 and
         * `ui/src/screens/refTypeList/index.tsx` already concluded at its own exit handler for the same
         * arm. ⚠️ Refactoring Rationale: this citation named a line number, which drifted the first time
         * that file's docstring grew; naming the handler instead cites something that survives an edit.
         * Transformation rule T8 keeps the transcription of every `88`-level sentence complete whether or
         * not a program reaches it; only the program decides which are written, and this one writes it
         * where it cannot be seen.
         */
        onInvoke: () => {
          navigateSafely(navigate, MAIN_MENU_ROUTE);
        },
        label: CARD_LIST_KEY_LABELS.PFK03,
        risk: 'read-only',
      },
      PFK07: {
        /** Pages backward, or reports that this is already the first page. */
        onInvoke: pageBackward,
        label: CARD_LIST_KEY_LABELS.PFK07,
        risk: 'read-only',
        /**
         * Reports whether a BACKWARD turn of this browse is outstanding.
         *
         * Assumptions: a predicate rather than a value, because `usePfKeys` evaluates it inside `invoke`
         * -- in the same task as the key event -- which is what makes a ref written by the previous
         * press effective against a second press before any render.
         * @returns {boolean} `true` while this key's own turn is in flight.
         */
        busy: (): boolean => outstandingPageDirection.current === 'previous',
      },
      PFK08: {
        /** Pages forward, or reports that no further page exists. */
        onInvoke: pageForward,
        label: CARD_LIST_KEY_LABELS.PFK08,
        risk: 'read-only',
        /**
         * Reports whether a FORWARD turn of this browse is outstanding.
         *
         * Assumptions: a predicate for the reason given on the backward key.
         * @returns {boolean} `true` while this key's own turn is in flight.
         */
        busy: (): boolean => outstandingPageDirection.current === 'next',
      },
    },
    {
      /**
       * Coerces an unrecognised key into the Enter arm, showing no message.
       *
       * Assumptions: an unrecognised key re-runs the Enter arm and shows no message, which is
       * what `app/cbl/COCRDLIC.cbl` L370-L380 does -- it sets an invalid flag and then
       * `SET CCARD-AID-ENTER TO TRUE`, coercing the key rather than reporting it. The sign-on
       * screen differs deliberately: its own `WHEN OTHER` branch does move
       * `CCDA-MSG-INVALID-KEY` into the message field, and this one has no such branch.
       * @param {object} rejection - Why the key was not dispatched, whose `reason` distinguishes an
       *   unmapped key from a disabled binding.
       * @returns {void} Nothing; the coerced arm performs the re-read itself.
       */
      onInvalidKey: (rejection) => {
        if (rejection.reason === 'unmapped') {
          submitTurn();
        }
      },
    },
  );

  /*
   * WHY : ⚠️ Refactoring Rationale: this screen DELEGATES its title band and its key legend to
   *       the shell instead of painting them itself. `ui/src/layout/AppShell.tsx` is mounted as
   *       the authenticated layout route, so the frame is painted once above the outlet rather
   *       than rebuilt per screen; a screen that also painted them would show two title bands
   *       and two legends. The message band stays local, because the shell paints a zone only
   *       when it is delegated and this screen's message is bound to controls in its own body.
   * WHY : ⚠️ Assumptions: the legend is delegated rather than dropped, so the SCREEN keeps
   *       owning the keyboard -- `bindings` and `invoke` come from this screen's own `usePfKeys`
   *       call and travel up unchanged, and an activation of a rendered legend control is
   *       forwarded straight back to `invoke`. The claim that stood here, that the shell "adds its
   *       sign-off key beside them only when this screen leaves that attention identifier free,
   *       which is decided by AID in the shell", is withdrawn: the shell installs NO keyboard
   *       listener at all and offers sign-off as a rendered control, for the reason recorded at
   *       `SHELL_SIGN_OFF_LABEL`. So there is no second listener to stand down and no AID
   *       arbitration anywhere -- this screen's bindings are the only ones on the document while it
   *       is mounted.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: the row-23 message line is delegated WITH the title band and the
   *       legend, where an earlier revision of this delegation withheld it on the stated ground that
   *       "the message band stays local ... this screen's message is bound to controls in its own
   *       body". That was true of the revision it was written for and became false when this screen
   *       stopped composing a band of its own: the sentence had nowhere left to be painted, so a
   *       browse that found no records, or a filter this screen refused, reported nothing at all. The
   *       band is the row-23 field and there is one of it per screen, so publishing it here is what
   *       keeps that field painted -- and reserved -- in every state.
   * WHY : Assumptions: the source screen has TWO message fields and the frame provides one band, so
   *       the two collapse onto it with the error field taking precedence. `INFOMSG` is a
   *       45-character `COLOR=NEUTRAL` field at row 20 (`app/bms/COCRDLI.bms` L324-L328) and `ERRMSG`
   *       a 78-character `COLOR=RED` field at row 23 (L330-L334); `1400-SETUP-MESSAGE`
   *       (`app/cbl/COCRDLIC.cbl` L895-L925) populates one or the other on most turns and can set
   *       both. The error is the one an operator must act on, so it wins when both would be present.
   * WHY : Assumptions: the informational sentence is the source's `WS-INFORM-REC-ACTIONS`, which
   *       `1400-SETUP-MESSAGE` sets whenever a page is displayed. It defines the `S` and `U` codes on
   *       the row controls, so it is the legend for them rather than decoration, and the band's
   *       severity carries the neutral appearance without a colour being written here.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: a browse FAILURE is surfaced here rather than in a per-request
   *       rejection handler this screen used to own. The paging hook holds the failure state, and holds
   *       it under the same sequence guard as the rows -- so a failure belonging to a superseded request
   *       cannot put a sentence on the band while a later page is on display, which is exactly what a
   *       local handler did. The sentence names no card and does not distinguish absence from fault.
   */
  /*
   * WHY : ⚠️ Assumptions: a settled, unfiltered-out, EMPTY opening page reports
   *       `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`, which is the reference's own condition:
   *       `IF WS-CA-SCREEN-NUM = 1 AND WS-SCRN-COUNTER = 0 SET WS-NO-RECORDS-FOUND TO TRUE` at
   *       `app/cbl/COCRDLIC.cbl` L1241 to L1245. Both halves matter -- the opening ordinal and a row
   *       count of zero -- because an empty page reached by paging forward is the end of a browse that
   *       did find records, and L1238 to L1240 answers that with a different sentence.
   * WHY : Refactoring Rationale: this sentence was reachable only from the card-number lookup's failure
   *       arm before, so a narrowing that matched nothing left the informational sentence on the band --
   *       the screen invited an operator to "TYPE S FOR DETAIL" against an empty table.
   * WHY : Assumptions: the loading and failed states are excluded so the sentence cannot appear while
   *       the first read is still outstanding, when zero rows means "not yet" rather than "none".
   */
  const noRecordsFound =
    !browse.isLoading &&
    !browse.isFailed &&
    browse.items.length === 0 &&
    browse.pageNumber === CARD_LIST_FIRST_PAGE;

  /*
   * WHY : ⚠️ Refactoring Rationale: the two message fields are published on TWO channels, where they
   *       were collapsed onto one band whose severity was switched between `info` and `error`. The
   *       collapse was recorded here as forced -- "the reference has TWO fields and the frame provides
   *       one band" -- and the frame now provides both: `ShellMessageSlot.information` is the row-22
   *       channel and exists precisely because five mapsets declare a line above row 23. So the
   *       collapse is withdrawn rather than merely re-tuned. What it produced was an informational
   *       alert painted INSIDE the row-23 error field, which is the one field the reference reserves
   *       for what the operator must act on: `1400-SETUP-MESSAGE` moves `WS-ERROR-MSG` into `ERRMSGO`
   *       and, under its own separate guard, `WS-INFO-MSG` into `INFOMSGO` with `DFHNEUTR`
   *       (`app/cbl/COCRDLIC.cbl` L924 to L929) -- two fields, two colours, populated on the SAME turn.
   *       `INFOMSG` is `COLOR=NEUTRAL` 45 characters at row 20 (`app/bms/COCRDLI.bms` L324 to L328) and
   *       `ERRMSG` is `COLOR=RED` 78 characters at row 23 (L331 to L334), the latter bounded by
   *       `CCARD-ERROR-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28.
   * WHY : Assumptions: neither channel names a severity, so each takes the appearance its own BMS field
   *       declares -- `error` for row 23 and `neutral` for row 22, which is what `MESSAGE_BAND_CHANNELS`
   *       records and `defaultMessageBandSeverity` resolves. Naming one here could only disagree with
   *       the mapset, and naming `info` for row 22 is what put a blue informational alert in a field the
   *       mapset declares red.
   */
  /*
   * WHY : ⚠️ Assumptions: a FAILED read shows the service's OWN sentence when the problem document
   *       carried one, and the authored fallback only when it did not. The fallback alone was shown for
   *       every failure, so a described refusal -- a 404, a 400, a 403 -- was reported as
   *       `Card data is temporarily unavailable`, which names a transient condition none of those is:
   *       `listCards` declares 400, 401, 403, 405, 406, 413, 415 and 500 and NO 404 at all, and states
   *       that an exhausted or entirely-filtered page answers 200 with an empty array
   *       "rather than 404, because a query that matched nothing succeeded"
   *       (`services/card-service/src/main/resources/openapi/card-api.yaml` L509 to L537). So absence
   *       never arrives as a failure here, and a failure is never absence.
   *       Alternatives Considered: classifying the status in this screen, which is what the sibling
   *       authorization summary does. Rejected here because `ui/src/api/client.ts` records at
   *       `isTransientFailure` that copying its status list into a screen is the defect that produced
   *       this finding, and the service's sentence needs no list: it was authored server-side to be
   *       read, so re-wording it here would be a second voice for one message.
   *       Trade-offs: the fallback is still reached for a failure that carried no document at all -- a
   *       network fault, or a reader that rejected with a bare `Error` -- and for those it is the only
   *       thing that can be said.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: a bodiless failure now selects between TWO sentences on the
   *       failure's own `transient` judgement, and it used to be answered by one authored sentence
   *       reading `Card data is temporarily unavailable. Report it with the correlation id.` That
   *       sentence asserts BOTH remedies at once -- wait, and report -- so it was wrong whichever the
   *       failure actually was, and `ui/src/api/client.ts` names this screen in the measured finding it
   *       records at `ApiFailureRemedy`: "a timeout, a dropped connection and a 500 indistinguishable on
   *       every screen, and a 404 presented as 'temporarily unavailable' on `/cards`". The two sentences
   *       are the catalogue's authored pair, so one voice answers this condition on every screen rather
   *       than each screen wording it again.
   * WHY : ⚠️ Assumptions: the judgement is READ from `browse.failure` through `isTransientFailure` and
   *       is not computed here. That client module owns which kinds and statuses are transient -- a
   *       timeout plus 408, 429, 502, 503 and 504 -- and it records that copying its status list into a
   *       screen is the defect that produced this very finding. It also classifies a TIMEOUT, which
   *       carries no status at all, so a status comparison here could not reach that case.
   * WHY : ⚠️ Assumptions: a `NETWORK` failure -- a dropped connection, an unresolved host -- is
   *       therefore reported as PERSISTENT and not as an outage to wait out. That is the client's own
   *       deliberate classification, argued at `remedyFor`: a request that timed out reached something,
   *       whereas one that never resolved a host fails again identically, and inviting a retry for it
   *       sends the operator into a loop with no exit. This screen adopts that judgement rather than
   *       second-guessing it, so the two cannot disagree.
   * WHY : Assumptions: NO repeat control is added to this screen, so `isRepeatableFailure` is not
   *       consulted. `ui/src/api/client.ts` permits a screen to put a repeat control behind that member
   *       "and behind nothing else", and this screen already has the reference's own repeat: `F8` and
   *       `F7` re-issue the read, and `ENTER` re-reads the page. Adding a control that duplicates a key
   *       the mapset paints would put two affordances on one action, so the member has nothing to gate.
   * WHY : Assumptions: the service's own sentence still wins over both, unchanged, because the service
   *       transcribed the same COBOL literals and is the authority on which of them applies. The pair
   *       below is the fallback for a rejection that arrived without a document.
   */
  const bandMessage =
    error ??
    (browse.isFailed
      ? (browse.error?.message ??
        (isTransientFailure(browse.failure)
          ? TRANSIENT_FAILURE_TRY_AGAIN
          : PERSISTENT_FAILURE_REPORT_IT))
      : noRecordsFound
        ? CARD_LIST_STATUS_MESSAGES.WS_NO_RECORDS_FOUND.text
        : null);

  /*
   * WHY : Assumptions: the advisory is suppressed on EXACTLY ONE condition, an empty opening page, which
   *       is the reference's own guard character for character: `1400-SETUP-MESSAGE` paints the
   *       45-character field only `IF NOT WS-NO-INFO-MESSAGE AND NOT WS-NO-RECORDS-FOUND`
   *       (`app/cbl/COCRDLIC.cbl` L926 to L930), and `WS-NO-RECORDS-FOUND` is set only for an
   *       end-of-file reached with the screen ordinal at one and the row counter at zero (L1242 to
   *       L1245). Every other turn paints it, INCLUDING a turn that also reports a fault or a refused
   *       filter -- the sentence is `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD` (L115 to L116), the
   *       legend for the row selection codes, and the reference's own `EVALUATE` reaches
   *       `SET WS-INFORM-REC-ACTIONS TO TRUE` on the file-error path through its `WHEN
   *       WS-NO-INFO-MESSAGE` arm at L917 to L919. So the two lines speak together, which is the whole
   *       reason the mapset declares two.
   *       Alternatives Considered: suppressing it whenever no rows are on display, which reads as the
   *       kinder rule and was written here first. Rejected as a divergence with no source behind it: it
   *       would withdraw the advisory on a refused FILTER, where the previous page's rows are still
   *       displayed and still selectable, and the reference leaves it painted there.
   *       Assumptions: it is therefore painted from the first commit, including while the opening read is
   *       outstanding. The reference has no read-in-flight turn to compare against -- it sends the map
   *       only after the browse -- so a target-only refinement that blanked it for that one frame would
   *       be inventing a turn, and the sentence it withheld is about to be true.
   */
  const informationMessage = noRecordsFound
    ? null
    : CARD_LIST_STATUS_MESSAGES.WS_INFORM_REC_ACTIONS.text;

  useShellSlot({
    screen: { transactionId: CARD_LIST_TRANSACTION_ID, programName: CARD_LIST_PROGRAM_NAME },
    now: paintedAt,
    message: { text: bandMessage, information: { text: informationMessage } },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
      legendColor: 'TURQUOISE',
    },
  });

  /*
   * WHY : Refactoring Rationale: the column set is built by an exported helper rather than assembled
   *       inline here, and the move is what makes the column ORDER and the per-row control assertable
   *       without mounting the screen and its shell. It also puts the selection column's contract --
   *       one character wide, protected with the rest when a filter is refused -- in one documented
   *       place instead of inside a render body.
   * WHY : Assumptions: FOUR columns, in the mapset's order. An earlier revision rendered the embossed
   *       name and the expiration date instead of the account number; the browse row has no such
   *       values to fill them from, because the list row is the three values
   *       `app/cbl/COCRDLIC.cbl` L258 to L260 declares and `card-api.yaml` publishes exactly those
   *       three, so both columns rendered blank for every row.
   * WHY : Assumptions: every row's action field is protected together, not row by row, because the
   *       reference's protect flag is a single `FLG-PROTECT-SELECT-ROWS` for the whole array
   *       (`app/cbl/COCRDLIC.cbl` L105 to L107) and `1250-SETUP-ARRAY-ATTRIBS` applies it to all seven.
   */
  const columns = buildCardListColumns({
    actionEntries,
    erroredRows: new Set(erroredRows),
    rowsProtected: isFilterRefused('accountNumber') || isFilterRefused('cardNumber'),
    refusal: error,
    onActionEntryChange: recordActionEntry,
    onOpenDetail: openRowDetail,
    onOpenUpdate: openRowUpdate,
    tokens: cssVar,
  });

  return (
    <Flex vertical gap="large">
      {/*
       * WHY : ⚠️ Refactoring Rationale: the outstanding read is now ANNOUNCED, and it was announced
       *       nowhere. The design system's own table spinner is a VISUAL signal only, and the paging
       *       keys' busy affordance is likewise a glyph, so a screen-reader operator pressing `F8` was
       *       told nothing at all between the press and the arriving page -- on a browse where a page
       *       turn changes every row, that is the one transition worth saying out loud.
       * WHY : ⚠️ Assumptions: the region is mounted on EVERY turn and is empty while nothing is
       *       outstanding, which is what makes the announcement work at all: `ui/src/layout/fieldHelp.tsx`
       *       records that a live region has to be in the accessibility tree BEFORE its content changes
       *       for the change to be announced, so a region rendered only while busy would arrive with its
       *       sentence already in place and be read by nothing. This screen has no early return, so the
       *       node is stable for the component's whole life.
       * WHY : Assumptions: `resolving` counts as outstanding as well as `isLoading`, because a typed card
       *       number is exchanged for a selector through its own lookup before the screen navigates --
       *       that is a request the operator started and is waiting on, and it is why the two keys and the
       *       Filter control are declined during it too.
       * WHY : Assumptions: the sentence is imported rather than composed. It is AUTHORED, and that is
       *       admissible because the reference has nothing to transcribe: a 3270 terminal inhibits input
       *       for the duration of a turn through the keyboard itself, so `app/cbl/COCRDLIC.cbl` never
       *       writes a working message -- there was no moment at which an operator could have read one.
       */}
      {busyAnnouncement(browse.isLoading || resolving ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * Assumptions: the header band is composed here because both of its values belong to this
       * screen -- the transaction identifier and the program name rows 1 and 2 of the 3270 screen
       * painted. `ScreenHeaderProps` requires both, so no shell could supply them.
       */}
      {/*
       * WHY : Refactoring Rationale: `now` is supplied. It was omitted here, and at every other call
       *       site, although `ScreenHeader`'s contract states that the composing caller must pass a
       *       SERVER-derived instant -- so the band fell through to `dayjs()` and displayed the
       *       BROWSER's clock. The baseline read one region clock for every terminal
       *       (`FUNCTION CURRENT-DATE` at `app/cbl/COSGN00C.cbl:179`), so the omission meant two
       *       operators looking at one record could read two different dates across midnight.
       *       Assumptions: the value comes from a hook rather than from a shell component. Both of the
       *       band's other inputs are this screen's own, as the note above records, so a shell could
       *       not render the band on this screen's behalf; the hook supplies the one value that is
       *       NOT screen-specific without inventing a component to hold it.
       */}
      <ScreenTitle>{CARD_LIST_TITLE}</ScreenTitle>
      {/*
        WHY : Assumptions: ONE input serves both narrowing the browse and reaching a single card,
              because the baseline screen's card-number field served both too -- an operator typed a
              number to narrow the list and typed one to open a card. Two separate inputs for one
              value would let them disagree, so an operator could narrow to one card and open
              another.
        WHY : Trade-offs: the two navigation controls are disabled until the entry is exactly
              sixteen digits, whereas the filter control accepts a blank entry as "no narrowing".
              The asymmetry is deliberate: a lookup cannot be issued for a partial number at all,
              while a blank filter is a meaningful request. The cost is that the three controls
              beside one input do not enable together.
        WHY : Assumptions: this entry is the path for a user who arrives holding only a number. A
              user looking at the list uses the per-row controls instead, which need no lookup
              because each row already carries its own selector.
      */}
      {/*
       * Assumptions: the entry's label is the mapset's own `Credit Card Number:` rather than the
       * placeholder text an earlier revision used. A placeholder is not a label -- it disappears the
       * moment a value is typed and is not reliably announced -- so the source's own field label is
       * rendered as one, associated with the control by id.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the account-number filter is rendered, in the position the mapset
       *       paints it -- `ACCTSID` on row 6 at `app/bms/COCRDLI.bms` L89 to L93, under the label at L88,
       *       ABOVE the card-number field on row 7 at L101 to L105 -- so the two criteria are read in the
       *       order the program edits them. Its label was already transcribed and had no control to name.
       * WHY : Assumptions: `autoFocus` is carried here and on no other control on this screen, because
       *       `ACCTSID` is the ONE field on this map declared `ATTRB=(FSET,IC,NORM,UNPROT)` (L89) -- `IC`
       *       is the initial-cursor attribute, and `CARDSID` at L101 is `ATTRB=(FSET,NORM,UNPROT)` without
       *       it. The cursor therefore opens where the terminal opened it, which is also the field the
       *       first edit reads.
       * WHY : Assumptions: it is a group of its own rather than a third control inside the card number's
       *       group, because the two fields are separate rows on the terminal and a compact group renders
       *       its members as one joined control. Joining an eleven-digit and a sixteen-digit field would
       *       read as one entry with two parts.
       * WHY : ⚠️ Refactoring Rationale: NEITHER filter carries a clear affordance any more, and the
       *       removal is a source-fidelity call rather than a sizing tweak. The mapset gives a field no
       *       in-field clear control -- `ACCTSID` and `CARDSID` are plain `UNPROT` entry fields with
       *       nothing beside them (`app/bms/COCRDLI.bms` L89 to L93 and L101 to L105) -- and the only
       *       affordances the screen paints at all are the three on its legend field,
       *       `  F3=Exit F7=Backward  F8=Forward` (L335 to L339). The program's own `EVALUATE` reaches an
       *       arm for exactly `CCARD-AID-ENTER`, `-PFK03`, `-PFK07` and `-PFK08`
       *       (`app/cbl/COCRDLIC.cbl` L439 to L562) and its `WHEN OTHER` at L572 to L582 re-reads the
       *       current page and re-sends the map, so no key clears a field either. `CSSTRPFY.cpy` L36 to
       *       L37 does normalise `DFHPF4` into an attention identifier, but this program has no arm for
       *       it -- so an operator clears one of these fields by clearing its text, and the affordance
       *       had no source to be faithful to.
       * WHY : ⚠️ Assumptions: what it DID add was measured, on two counts, which is why removing it fixes
       *       two findings at once rather than one. First, `allowClear` renders a real
       *       `<button type="button">` for the clear glyph
       *       (`@rc-component/input/lib/BaseInput.js` L66 to L76) and leaves it in the document while the
       *       entry is empty: a browser measured two such controls on this screen at 12 by 12 pixels,
       *       `visibility: hidden`, `tabIndex 0` and with no `aria-hidden`, so a keyboard operator hit
       *       two tab stops that could not be seen and fell below the 24-pixel pointer floor
       *       `TARGET_SIZE_AA_MINIMUM` records from success criterion 2.5.8. Second, the same prop
       *       CHANGES the control's shape: `hasPrefixSuffix` becomes true, the input is wrapped in an
       *       `-affix-wrapper`, and the variant class that carries the border, the padding and the
       *       control height moves from the `<input>` to that wrapper (L50 to L53 and L87 of the same
       *       file). The screen therefore rendered two different kinds of element both matching
       *       `.ant-input` -- a bare outlined control and the unstyled inner element of a wrapper -- and
       *       a browser measured them at 40 and 28 pixels. With the prop gone every `.ant-input` on this
       *       screen is the same kind of box, so their height is the theme's single `controlHeight` and
       *       no per-control override is needed to make them agree.
       *       Alternatives Considered: keeping the affordance and inflating the glyph to the pointer
       *       floor. Rejected because it would keep a control the source does not have and would leave
       *       the two heights disagreeing, so it addresses the measurement and not the cause.
       * WHY : Assumptions: both fields are sized from the widths their own PICTURE clauses declare --
       *       `ACCT-ID PIC 9(11)` and `CARD-NUM PIC X(16)`, transcribed as
       *       {@link CARD_LIST_ACCOUNT_FILTER_WIDTH} and {@link CARD_LIST_CARD_FILTER_WIDTH} and already
       *       each field's `maxLength` -- so the entry and its rendering state one width instead of two.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the label and its entry field are laid out with a `Flex` and no
       *       longer with a `Space.Compact`. A compact group exists to render its members as ONE joined
       *       control -- it collapses the gap between them to zero and joins their borders -- and a
       *       label is not a control, so the grouping made a claim about these two elements that is not
       *       true. It also made the pair a single non-wrapping row: at a 375-pixel width a browser
       *       measured the `Credit Card Number:` label fragmented into stacked one- and two-character
       *       pieces beside an entry field 166 pixels wide, because both members were being shrunk to
       *       fit a line that could not hold them.
       * WHY : Assumptions: the same three ingredients the actions row below already uses -- the legend's
       *       own `SPACING_TOKENS.sectionGapCompact` gap, `wrap`, and `align="center"` -- so the two
       *       rows of this screen agree by construction rather than by two literals. The mapset's own
       *       gap between a label and its field is three character columns (the label ends at column 40
       *       and `ACCTSID` starts at column 44, `app/bms/COCRDLI.bms` L84 to L93), which is the same
       *       small step that token names.
       * WHY : Assumptions: the label carries {@link FILTER_LABEL_LAYOUT}, so the wrapping falls on the
       *       ENTRY and never on the label's text.
       */}
      <Flex gap={cssVar[SPACING_TOKENS.sectionGapCompact]} wrap align="center">
        <Typography.Text id={ACCOUNT_NUMBER_LABEL_ID} style={FILTER_LABEL_LAYOUT}>
          {CARD_LIST_LABELS.accountNumberFilter}
        </Typography.Text>
        <Input
          aria-labelledby={ACCOUNT_NUMBER_LABEL_ID}
          {...fieldAriaProps(ACCOUNT_NUMBER_INPUT_ID, {
            invalid: isFilterRefused('accountNumber'),
            hasError: isFilterRefused('accountNumber'),
            hasHint: false,
          })}
          autoFocus
          id={ACCOUNT_NUMBER_INPUT_ID}
          inputMode="numeric"
          maxLength={CARD_LIST_ACCOUNT_FILTER_WIDTH}
          status={isFilterRefused('accountNumber') ? 'error' : ''}
          style={copybookFieldWidthStyle(CARD_LIST_ACCOUNT_FILTER_WIDTH, cssVar)}
          onChange={
            /**
             * Records the entered account number.
             * @param {object} event - The change event antd forwards.
             * @param {object} event.target - The input element the event came from.
             * @param {string} event.target.value - The entry as it now stands.
             * @returns {void} Nothing; the entry is recorded as a side effect.
             */
            (event: { target: { value: string } }): void => {
              setAccountFilter(event.target.value);
            }
          }
          value={accountFilter}
        />
      </Flex>
      {/*
       * WHY : Assumptions: the refusal sentence is repeated here VISUALLY HIDDEN rather than rendered
       *       beside the field. The shared band already shows it to a sighted operator, and the row-23
       *       field is where the source puts it, so printing it twice would add a sentence the mapset
       *       does not declare; but `aria-describedby` must point at an element that EXISTS, or the
       *       association is a dangling reference that announces nothing. This element is that target.
       * WHY : Assumptions: it is rendered only while the field is the refused one, so the description
       *       cannot outlive the refusal it describes.
       */}
      {filterRefusals.accountNumber === null ? null : (
        <span id={fieldErrorId(ACCOUNT_NUMBER_INPUT_ID)} style={VISUALLY_HIDDEN_STYLE}>
          {filterRefusals.accountNumber}
        </span>
      )}
      {/*
       * WHY : Assumptions: laid out exactly as the account row above and for the same reasons, which is
       *       why the rationale is written once there and not twice. This is the field a browser
       *       measured fragmenting.
       */}
      <Flex gap={cssVar[SPACING_TOKENS.sectionGapCompact]} wrap align="center">
        <Typography.Text id={CARD_NUMBER_LABEL_ID} style={FILTER_LABEL_LAYOUT}>
          {CARD_LIST_LABELS.cardNumberFilter}
        </Typography.Text>
        <Input
          aria-labelledby={CARD_NUMBER_LABEL_ID}
          {...fieldAriaProps(CARD_NUMBER_INPUT_ID, {
            invalid: isFilterRefused('cardNumber'),
            hasError: isFilterRefused('cardNumber'),
            hasHint: false,
          })}
          id={CARD_NUMBER_INPUT_ID}
          inputMode="numeric"
          maxLength={CARD_LIST_CARD_FILTER_WIDTH}
          status={isFilterRefused('cardNumber') ? 'error' : ''}
          style={copybookFieldWidthStyle(CARD_LIST_CARD_FILTER_WIDTH, cssVar)}
          onChange={
            /**
             * Records the entered card number.
             * @param {object} event - The change event antd forwards.
             * @param {object} event.target - The input element the event came from.
             * @param {string} event.target.value - The entry as it now stands.
             */
            (event: { target: { value: string } }) => {
              setCardNumber(event.target.value);
            }
          }
          value={cardNumber}
        />
      </Flex>
      {/*
       * WHY : ⚠️ Refactoring Rationale: the three controls are lifted OUT of the card number's compact
       *       group, where they sat as its third, fourth and fifth members. A compact group exists to
       *       render its members as one joined control, and a browser measured the result: the gaps
       *       between `Filter`, `Open detail` and `Open update` were 0 pixels, so three independent
       *       actions read as one segmented control whose segments looked like states of a single
       *       choice -- while the key legend on the very same screen separated its own controls by
       *       about 9 pixels. Two unrelated things were joined by that grouping as well: the entry
       *       field and the actions taken on it, which is why the same note above says joining two
       *       entry fields would read as one entry with two parts.
       * WHY : Assumptions: the gap is the legend's OWN token, read through
       *       `SPACING_TOKENS.sectionGapCompact` -- the same expression `ui/src/layout/PfKeyBar.tsx`
       *       L449 uses for the gap between its keys. Reading the same name is what makes the two rows
       *       of controls agree by construction rather than by two literals that happen to match, and
       *       it keeps the zero-hardcoded-values rule true here.
       * WHY : Assumptions: a `Flex` rather than a `Space`, because the row must WRAP at a phone width --
       *       three controls whose labels are `Filter`, `Open detail` and `Open update` do not fit one
       *       375-pixel line, and a non-wrapping row is how a control ends up clipped off the side of
       *       the viewport.
       */}
      <Flex gap={cssVar[SPACING_TOKENS.sectionGapCompact]} wrap align="center">
        {/*
         * WHY : ⚠️ Refactoring Rationale: this control is the screen's PRIMARY one, and every control on
         *       the screen previously carried the same neutral weight -- a browser found no
         *       primary-solid button anywhere on it, so `Filter`, which performs the browse, was
         *       pixel-indistinguishable from `F3=Exit`, which leaves it. This is the screen's Enter
         *       action: the mapset paints no Enter on its legend (`app/bms/COCRDLI.bms` L339 names only
         *       F3, F7 and F8), and the program's `CCARD-AID-ENTER` arms are the ones that edit the two
         *       filters and read the browse (`app/cbl/COCRDLIC.cbl` L517 and L545). AAP section 0.3.2
         *       maps that role to exactly this variant -- `type="primary"` for Enter and PF5, `default`
         *       for PF3, PF4 and PF12 -- so the emphasis is the design system's own and no colour,
         *       weight or border value is written here.
         * WHY : Assumptions: emphasis matches RISK, which is what keeps one primary control on this
         *       screen from contradicting the rule the mutating screens follow. This action reads a
         *       page; it writes nothing, needs no confirmation, and is undone by clearing the field
         *       and running it again. The two controls beside it navigate rather than submit, and the
         *       delegated legend keys stay neutral, so the one solid control on the screen is the one
         *       that acts on what the operator typed.
         */}
        <Button
          type="primary"
          onClick={
            /** Narrows the browse to the entered card number, or clears the narrowing. */
            () => {
              applyFilters();
            }
          }
        >
          {CARD_LIST_ENTRY_CONTROL_LABELS.filter}
        </Button>
        <Button
          disabled={!enteredNumberIsAddressable}
          loading={resolving}
          onClick={
            /** Resolves the entered number and opens that card's detail route. */
            () => {
              openEnteredCard(cardDetailPath);
            }
          }
        >
          {CARD_LIST_ENTRY_CONTROL_LABELS.openDetail}
        </Button>
        <Button
          disabled={!enteredNumberIsAddressable}
          loading={resolving}
          onClick={
            /** Resolves the entered number and opens that card's update route. */
            () => {
              openEnteredCard(cardEditPath);
            }
          }
        >
          {CARD_LIST_ENTRY_CONTROL_LABELS.openUpdate}
        </Button>
      </Flex>
      {/*
       * WHY : Assumptions: the card filter gets the same visually-hidden description target as the
       *       account filter above, and for the same reason recorded there -- `aria-describedby` has
       *       to resolve. Two targets rather than one shared element because only one of the two
       *       fields is ever the refused one (`2220-EDIT-CARD` writes its sentence only
       *       `IF WS-ERROR-MSG-OFF`, `app/cbl/COCRDLIC.cbl` L1057), so the id must travel with the
       *       field that was refused rather than sit on a neutral element both controls point at.
       */}
      {filterRefusals.cardNumber === null ? null : (
        <span id={fieldErrorId(CARD_NUMBER_INPUT_ID)} style={VISUALLY_HIDDEN_STYLE}>
          {filterRefusals.cardNumber}
        </span>
      )}
      <Table<CardSummary>
        columns={columns}
        dataSource={browse.items}
        loading={browse.isLoading}
        onRow={
          /*
           * WHY : ⚠️ Refactoring Rationale: the rows carried NO affordance of any kind, which a browser
           *       measured rather than this inferring it -- `cursor: auto` at rest and hovered, no
           *       `:hover` rule of their own (the tint is a class added on mouse enter), and no focus or
           *       active treatment at all. Every row on this screen is actionable: it carries a
           *       selection field and two immediate controls, and the reference directs an operator to a
           *       row by putting the cursor in it. So a row is given the pointer cursor that says so and
           *       a way to be reached without a pointer.
           * WHY : ⚠️ Trade-offs: `tabIndex` 0 puts each row in the tab sequence, which is seven extra
           *       stops on a full page, and that cost is accepted for what it buys: a keyboard operator
           *       can move row to row and land in the row's own field, which is the reference's cursor
           *       repositioning, instead of tabbing through three controls per row to get past one.
           *       The alternative considered was `tabIndex` -1, which keeps the row programmatically
           *       focusable and out of the tab order -- rejected because it is not reachable from the
           *       keyboard at all, which is the half of the finding that a cursor cannot answer.
           * WHY : Assumptions: no `role` is set. A `<tr>` inside a `<table>` already has the `row` role,
           *       and naming a widget role here -- `button`, or `menuitem` -- would promise an
           *       activation the row does not perform: it moves the cursor, and the turn is still the
           *       operator's to submit.
           * WHY : Assumptions: the focus ring is the user agent's. Nothing in this file or in the row's
           *       own component styling clears `outline`, so a focused row shows the browser's
           *       indicator; declaring one inline is not possible for a focus state in any case, since
           *       an inline style has no `:focus-visible` selector to attach to.
           */
          /**
           * Gives one browse row its pointer affordance and its keyboard reach.
           * @returns {object} The row attributes antd spreads onto the `<tr>`.
           */
          () => ({
            style: ROW_AFFORDANCE_STYLE,
            tabIndex: 0,
            onClick: focusRowSelectionOnPointer,
            onKeyDown: focusRowSelectionOnKey,
          })
        }
        pagination={false}
        rowKey={
          /*
           * WHY : ⚠️ Refactoring Rationale: the key is the row's own SELECTOR. It was the account paired
           *       with the masked rendering, on the reasoning that no single member of a row is unique --
           *       which was true of the two members that pair named and false of the row, because every
           *       row carries `key`, the opaque per-card selector both of its controls already address.
           *       The pair is not unique: the masked rendering keeps four digits, so two cards on ONE
           *       account that end in the same four collide, and React then reconciles two distinct rows
           *       as one -- the second row's controls would carry the first row's selector, so acting on
           *       it would open the wrong card. The selector is unique by construction because it seals
           *       one card number.
           * WHY : Assumptions: using it here discloses nothing further. It is already in the row's two
           *       control targets and in the routes they navigate to, so putting it in React's
           *       reconciliation identity -- which never travels in a request -- adds no surface.
           */
          /**
           * Derives a row's reconciliation identity from its own opaque selector.
           * @param {CardSummary} row - One browse row.
           * @returns {string} That row's sealed selector.
           */
          (row: CardSummary): string => row.key
        }
        /*
         * WHY : ⚠️ Purpose: give the grid its OWN horizontal scrolling region, so a viewport narrower
         *       than the mapset's row scrolls the grid rather than painting outside the shell's frame.
         *       {@link CARD_LIST_COLUMN_CELLS} records the two measurements this answers -- an account
         *       number wrapping as `000000` / `00100` with the masked card number broken across three
         *       lines, and an 8.92-pixel remainder the shell's content region absorbed as a sideways
         *       pan -- and why declaring the extent is what creates the region.
         * WHY : ⚠️ Assumptions: `tableLayout` is stated EXPLICITLY and must be. The design system infers
         *       `'fixed'` only for a pinned column, a pinned header, a sticky grid or an ellipsised
         *       column (`@rc-component/table/lib/Table.js` L426-L442), and this grid has none of the
         *       four -- so with the extent declared and the layout left to infer it would resolve to
         *       `'auto'`, under which a declared column width is a MINIMUM the content may grow. That
         *       is not a theoretical concern here: the selection column holds an `Input` and two
         *       buttons, and an unconstrained `Input` reports a full-width intrinsic size, which is
         *       exactly how the sibling user browse measured a one-character column at about 700
         *       pixels. Fixing the layout is what keeps the four mapset-derived extents authoritative.
         * WHY : Assumptions: no column is pinned, so nothing here can reproduce the overlay the
         *       transaction browse had to unpin a column to avoid -- with four columns and no `fixed`,
         *       the whole row scrolls as one and no cell is ever painted over another.
         */
        scroll={{ x: cardListTableMeasure(cssVar) }}
        tableLayout="fixed"
      />
      {/*
       * Refactoring Rationale: the bespoke `Previous` and `Next` controls are gone, and the delegated
       * key legend carries their actions as F7 and F8. Two things are recovered by the move. The source
       * screen has exactly one paging affordance -- the legend on row 24 -- so a second pair beside
       * the table was an addition, and a pair whose availability test read one envelope member while
       * its handler guard read another was how an earlier revision came to disable a control whose
       * cursor was present. The bindings hold one predicate each, and both the key press and the
       * legend's button run it through the same dispatch path.
       * Assumptions: neither key is bound disabled, which is deliberate rather than an omission. The
       * source refuses neither PF7 nor PF8: the unavailable arms move a sentence into the error field
       * and re-read the current page, so an operator at either end of the browse gets a message.
       * Disabling the key would replace that message with silence.
       */}
    </Flex>
  );
}

/*
 * WHY : Assumptions: the component is published BOTH ways, and both are load-bearing. The router
 *       imports it by NAME and adapts it itself -- `ui/src/router.tsx` L158 to L161 does
 *       `const module = await import('./screens/cardList'); return { default: module.CardListScreen };`
 *       -- and the screen tests import the same named symbol, so the named export cannot be withdrawn.
 *       The default alias is what lets any route loader mount the module directly as a route element
 *       without that adapter, which is the form `React.lazy` accepts unaided.
 * WHY : Assumptions: this is an alias of one component and NOT a barrel. AAP section 0.6.2.1 forbids
 *       "default-export barrels for screens" -- a module that re-exports OTHER modules' screens -- and
 *       this re-exports nothing; the helpers beside it stay named exports precisely so no caller can
 *       reach them through a default. The sibling screens `ui/src/screens/menu/index.tsx` L477 and
 *       `ui/src/screens/transactionAdd/index.tsx` L2461 publish themselves the same way.
 */
export default CardListScreen;
