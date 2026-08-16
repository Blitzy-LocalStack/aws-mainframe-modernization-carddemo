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
 */

import { Button, Flex, Input, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { listCards, lookupCard } from '../../api/cards';
import type { CardSummary, PageDirection, PageResponse } from '../../api/cards';
import { useShellSlot } from '../../layout/AppShell';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import { PROGRAM_MESSAGES, SHARED_MESSAGES, STATUS_MESSAGES } from '../../messages/messages';
import { cardDetailPath, cardEditPath, isCardNumber } from '../../routes/cards';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import type { CardListQuery } from '../../api/types';
import { VISUALLY_HIDDEN_STYLE, fieldAriaProps, fieldErrorId } from '../../layout/fieldHelp';
import { usePagedQuery } from '../../hooks/usePagedQuery';
import { ScreenTitle } from '../../layout/ScreenTitle';

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

/**
 * Sentence shown when a page could not be read at all.
 *
 * Assumptions: this string is NEW, and it is the one message on this screen with no baseline
 * counterpart that may be carried across. The source composes `WS-FILE-ERROR-MESSAGE`
 * (`app/cbl/COCRDLIC.cbl` L153-L172, moved to the message field at L1254), which appends the internal
 * file name and the CICS response and reason codes -- the same class of detail the message catalog's
 * redaction register withholds at 28 other sites, and for the same reason: an internal identifier
 * must not reach a browser, a log line or a bug report. The replacement therefore names the
 * correlation identifier the operator already has instead of the file the request touched.
 */
export const CARD_LIST_PAGE_UNAVAILABLE =
  'Card data is temporarily unavailable. Use the request correlation identifier from the response when contacting support.';

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

/** Declared width of the account-number filter field, `ACCTSID` at `app/bms/COCRDLI.bms` L89 to L93. */
const ACCOUNT_FILTER_WIDTH = 11;

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
 * Assumptions: seven, from `05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7` at
 * `app/cbl/COCRDLIC.cbl` L177 to L178, which is also the arity of its row array. The paging hook
 * requires this rather than defaulting it, because the five browses it serves declare five different
 * arities and a default would render another screen's.
 */
export const CARD_LIST_PAGE_SIZE = 7;

/** Width of the account filter field, from `CC-ACCT-ID PIC X(11)` and `ACCTSIDI` on the mapset. */
export const CARD_LIST_ACCOUNT_FILTER_WIDTH = 11;

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
 * Assumptions: the DIRECTION is spread on the same condition as the cursor. `listCards` refuses a
 * direction supplied without a cursor -- the combination every contract answers with a 400 keyed on the
 * direction -- so the opening read must not name one.
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
 * @returns {ReactElement} The card list screen.
 */
export function CardListScreen(): ReactElement {
  const navigate = useNavigate();
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  const [error, setError] = useState<string | null>(null);

  /*
   * WHY : ⚠️ Refactoring Rationale: which FILTER a refusal blames is held as state beside the sentence,
   *       where the sentence alone was held before. A browser run reported the consequence: a refused
   *       filter marked no control at all -- no `aria-invalid`, no description, no error border -- so an
   *       operator who returned focus to the field was told nothing, and the sentence existed only in the
   *       shared band. The 3270 does not have that gap: `app/cpy/CSSETATY.cpy` moves `DFHRED` into the
   *       FIELD's attribute byte as well as writing the message line, so the field itself carries the
   *       refusal. This is the target's spelling of that attribute.
   * WHY : Assumptions: it names the field rather than holding a per-field message map, because the two
   *       edits are exclusive -- `2210-EDIT-ACCOUNT` refuses and returns before `2220-EDIT-CARD` runs,
   *       which is why the account refusal wins when both filters are wrong -- so at most one field is
   *       ever marked and a map would model a state this screen cannot reach.
   */
  const [refusedFilter, setRefusedFilter] = useState<FilterFieldName | null>(null);
  const [cardNumber, setCardNumber] = useState('');
  const [accountFilter, setAccountFilter] = useState('');
  // WHY : Assumptions: the ENTRY and the APPLIED narrowing are separate pieces of state, because the
  //       reference separates them too: `CC-ACCT-ID` is the received map field and `CDEMO-ACCT-ID` is
  //       what `2210-EDIT-ACCOUNT` moves into the carried area once the edit has passed
  //       (`app/cbl/COCRDLIC.cbl` L1027). Reading the page from the entry directly would re-narrow the
  //       browse on every keystroke, and a cursor sealed under one narrowing addresses nothing under
  //       another.
  const [appliedAccountId, setAppliedAccountId] = useState('');
  const [resolving, setResolving] = useState(false);
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
    setRefusedFilter(null);

    if (!isAccountFilterAbsent(accountFilter) && !isAccountFilterWellFormed(accountFilter)) {
      setError(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER);
      /*
       * WHY : Refactoring Rationale: the refused FIELD is recorded beside the sentence, which the
       *       version this replaced did not do. The band states what is wrong and this marks WHICH of
       *       the two entry boxes it is about, so that box carries the error styling and its
       *       description is announced with it; a message naming "the account filter" beside two
       *       unmarked boxes leaves a screen-reader user to guess.
       */
      setRefusedFilter('accountNumber');
      return;
    }

    if (cardNumber !== '' && !enteredNumberIsAddressable) {
      /*
       * WHY : Refactoring Rationale: this is the LIST screen's own refusal, not the detail screen's.
       *       An earlier revision rendered `Card number if supplied must be a 16 digit number`, which
       *       is what `app/cbl/COCRDSLC.cbl` L149 and `app/cbl/COCRDUPC.cbl` L194 declare; this
       *       program declares the upper-case filter form at `app/cbl/COCRDLIC.cbl` L1058 instead.
       *       The two are near-duplicates, which is exactly why the wrong one read as correct in
       *       review -- and why the catalog holds both rather than folding them together.
       */
      setError(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER);
      setRefusedFilter('cardNumber');
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
      browse.reset();
    }
  }

  /**
   * Exchanges the entered card number for a selector and navigates to the requested screen.
   *
   * Assumptions: the number is exchanged rather than being interpolated into the route, because a
   * request line reaches browser history, referrer headers and every intermediary's access log while a
   * request body reaches none of them. The exchange is one extra round trip and it is the whole reason
   * no primary account number appears in any card URL.
   * @param {(selector: string) => string} buildPath - Builds the destination route from the selector the
   *   lookup returns; either the detail route or the update route.
   */
  function openEnteredCard(buildPath: (selector: string) => string): void {
    if (!enteredNumberIsAddressable) {
      setError(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER);
      setRefusedFilter('cardNumber');
      return;
    }
    setResolving(true);
    setError(null);
    setRefusedFilter(null);
    lookupCard(cardNumber).then(
      /**
       * Navigates to the resolved card.
       * @param {object} answer - The lookup answer.
       * @param {string} answer.selector - The opaque selector addressing the entered card.
       */
      (answer) => {
        setResolving(false);
        navigateSafely(navigate, buildPath(answer.key));
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

    if (!browse.hasPrev) {
      setError(CARD_LIST_PAGING_MESSAGES.NO_PREVIOUS_PAGES_TO_DISPLAY);
      return;
    }
    browse.prevPage();
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
      setError(CARD_LIST_PAGING_MESSAGES.NO_MORE_PAGES_TO_DISPLAY);
      return;
    }
    browse.nextPage();
  }

  /*
   * WHY : Assumptions: exactly the four attention identifiers `app/cbl/COCRDLIC.cbl` L371-L374 admits
   *       are bound, and only three of them carry a label -- Enter's is empty because this mapset
   *       paints no Enter legend, which `usePfKeys` treats as a keyboard-only handler.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Re-reads the list under the current filter, which is the source program's Enter arm: it
         * performs `9000-READ-FORWARD` and re-sends the map (`app/cbl/COCRDLIC.cbl` L565-L578).
         */
        onInvoke: () => {
          applyFilters();
        },
        label: CARD_LIST_KEY_LABELS.ENTER,
      },
      PFK03: {
        /**
         * Returns to the main menu, which is where the source program transfers on PF3
         * (`app/cbl/COCRDLIC.cbl` L390-L399, moving `LIT-MENUPGM` into the next-program field).
         */
        onInvoke: () => {
          navigateSafely(navigate, MAIN_MENU_ROUTE);
        },
        label: CARD_LIST_KEY_LABELS.PFK03,
      },
      PFK07: {
        /** Pages backward, or reports that this is already the first page. */
        onInvoke: pageBackward,
        label: CARD_LIST_KEY_LABELS.PFK07,
      },
      PFK08: {
        /** Pages forward, or reports that no further page exists. */
        onInvoke: pageForward,
        label: CARD_LIST_KEY_LABELS.PFK08,
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
          applyFilters();
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
  const bandMessage =
    error ??
    (browse.isFailed
      ? CARD_LIST_PAGE_UNAVAILABLE
      : CARD_LIST_STATUS_MESSAGES.WS_INFORM_REC_ACTIONS.text);

  const bandSeverity = error === null && !browse.isFailed ? 'info' : 'error';

  useShellSlot({
    screen: { transactionId: CARD_LIST_TRANSACTION_ID, programName: CARD_LIST_PROGRAM_NAME },
    now: paintedAt,
    message: { text: bandMessage, severity: bandSeverity },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
      legendColor: 'TURQUOISE',
    },
  });

  /*
   * WHY : Refactoring Rationale: FOUR columns, and an earlier revision of this screen rendered the
   *       embossed name and the expiration date as well. The browse row has no such values to fill
   *       them from - the baseline list row is an eleven-character account number, a
   *       sixteen-character card number and a one-character status, declared at
   *       app/cbl/COCRDLIC.cbl:258-260, and card-api.yaml publishes exactly those three - so those
   *       two columns rendered blank for every row. The account column takes their place because the
   *       baseline row does carry it and this screen had dropped it.
   *       Refactoring Rationale: THREE columns now, not four. The fourth was a per-row actions column
   *       whose buttons were built from an opaque selector the contract no longer publishes per row;
   *       reaching one card is the card-number entry above.
   */
  const columns: TableColumnsType<CardSummary> = [
    // WHY : Refactoring Rationale: the four columns are now headed and ORDERED as the mapset paints
    //       them - the selection column first, then the account number, the card number and the
    //       active flag (app/bms/COCRDLI.bms L111, L115, L119, L123). An earlier revision headed them
    //       `Card`, `Account`, `Status` and `Actions`, none of which the source screen paints, and
    //       put the row controls last. The order is part of what a returning operator reads.
    {
      title: CARD_LIST_LABELS.selectColumn.trim(),
      key: 'actions',
      /**
       * Renders the two per-row controls, each labelled with the selection code the source screen
       * accepts for that action and addressed by that row's own selector.
       * @param {CardSummary} row - The row the controls act on.
       * @returns {ReactElement} A detail control and an update control for that row.
       */
      render: (row: CardSummary): ReactElement => (
        <Space size="small">
          <Button
            onClick={
              /** Opens this row's card detail. */
              () => {
                navigateSafely(navigate, cardDetailPath(row.key));
              }
            }
            size="small"
          >
            {CARD_LIST_ROW_ACTION_CODES.detail}
          </Button>
          <Button
            onClick={
              /** Opens this row's card update form. */
              () => {
                navigateSafely(navigate, cardEditPath(row.key));
              }
            }
            size="small"
          >
            {CARD_LIST_ROW_ACTION_CODES.update}
          </Button>
        </Space>
      ),
    },
    // WHY : Refactoring Rationale: the browse row carries the account number and
    //       not the embossed name or the expiration date, because those two are
    //       members of the card DETAIL and the contract's browse row is the three
    //       values the 3270 list displayed per row. Columns bound to absent
    //       members render as empty cells rather than failing, so the two that
    //       were bound here showed nothing; the account number is what the
    //       baseline row actually carried alongside the card number and status.
    {
      title: CARD_LIST_LABELS.accountColumn,
      dataIndex: 'accountId',
    },
    {
      title: CARD_LIST_LABELS.cardColumn.trim(),
      dataIndex: 'displayCardNumber',
    },
    // WHY : Refactoring Rationale: the flag renders as the stored character. The source column is one
    //       character wide (CRDSTS1 through CRDSTS7, app/bms/COCRDLI.bms LENGTH=1) under a heading
    //       that names the domain, so `Y` and `N` are what an operator reads there. An earlier
    //       revision expanded them to `Active` and `Inactive`, two words no COBOL source holds, which
    //       also widened a one-character column eightfold.
    {
      title: CARD_LIST_LABELS.activeColumn.trim(),
      dataIndex: 'activeStatus',
    },
  ];

  return (
    <Flex vertical gap="large">
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
       */}
      <Space.Compact>
        <Typography.Text id={ACCOUNT_NUMBER_LABEL_ID}>
          {CARD_LIST_LABELS.accountNumberFilter}
        </Typography.Text>
        <Input
          allowClear
          aria-labelledby={ACCOUNT_NUMBER_LABEL_ID}
          {...fieldAriaProps(ACCOUNT_NUMBER_INPUT_ID, {
            invalid: refusedFilter === 'accountNumber',
            hasError: refusedFilter === 'accountNumber',
            hasHint: false,
          })}
          autoFocus
          id={ACCOUNT_NUMBER_INPUT_ID}
          inputMode="numeric"
          maxLength={ACCOUNT_FILTER_WIDTH}
          status={refusedFilter === 'accountNumber' ? 'error' : ''}
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
      </Space.Compact>
      {/*
       * WHY : Assumptions: the refusal sentence is repeated here VISUALLY HIDDEN rather than rendered
       *       beside the field. The shared band already shows it to a sighted operator, and the row-23
       *       field is where the source puts it, so printing it twice would add a sentence the mapset
       *       does not declare; but `aria-describedby` must point at an element that EXISTS, or the
       *       association is a dangling reference that announces nothing. This element is that target.
       * WHY : Assumptions: it is rendered only while the field is the refused one, so the description
       *       cannot outlive the refusal it describes.
       */}
      {refusedFilter === 'accountNumber' && error !== null ? (
        <span id={fieldErrorId(ACCOUNT_NUMBER_INPUT_ID)} style={VISUALLY_HIDDEN_STYLE}>
          {error}
        </span>
      ) : null}
      <Space.Compact>
        <Typography.Text id={CARD_NUMBER_LABEL_ID}>
          {CARD_LIST_LABELS.cardNumberFilter}
        </Typography.Text>
        <Input
          allowClear
          aria-labelledby={CARD_NUMBER_LABEL_ID}
          {...fieldAriaProps(CARD_NUMBER_INPUT_ID, {
            invalid: refusedFilter === 'cardNumber',
            hasError: refusedFilter === 'cardNumber',
            hasHint: false,
          })}
          id={CARD_NUMBER_INPUT_ID}
          inputMode="numeric"
          maxLength={16}
          status={refusedFilter === 'cardNumber' ? 'error' : ''}
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
        <Button
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
      </Space.Compact>
      {/*
       * WHY : Assumptions: the card filter gets the same visually-hidden description target as the
       *       account filter above, and for the same reason recorded there -- `aria-describedby` has
       *       to resolve. Two targets rather than one shared element because only one of the two
       *       fields is ever the refused one (`2220-EDIT-CARD` writes its sentence only
       *       `IF WS-ERROR-MSG-OFF`, `app/cbl/COCRDLIC.cbl` L1057), so the id must travel with the
       *       field that was refused rather than sit on a neutral element both controls point at.
       */}
      {refusedFilter === 'cardNumber' && error !== null ? (
        <span id={fieldErrorId(CARD_NUMBER_INPUT_ID)} style={VISUALLY_HIDDEN_STYLE}>
          {error}
        </span>
      ) : null}
      <Table<CardSummary>
        columns={columns}
        dataSource={browse.items}
        loading={browse.isLoading}
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
