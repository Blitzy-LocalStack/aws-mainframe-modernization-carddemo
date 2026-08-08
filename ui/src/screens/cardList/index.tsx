import { Button, Flex, Input, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { listCards, lookupCard } from '../../api/cards';
import type { CardSummary, PageDirection, PageResponse } from '../../api/cards';
import { MessageBand } from '../../layout/MessageBand';
import { PfKeyBar, UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import { PROGRAM_MESSAGES, SHARED_MESSAGES, STATUS_MESSAGES } from '../../messages/messages';
import { cardDetailPath, cardEditPath, isCardNumber } from '../../routes/cards';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';

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
  const [page, setPage] = useState<PageResponse<CardSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cardNumber, setCardNumber] = useState('');
  const [resolving, setResolving] = useState(false);

  const enteredNumberIsAddressable = isCardNumber(cardNumber);

  /*
   * WHY : Assumptions: the source screen has TWO message fields and this tree provides one band, so
   *       the two collapse onto it with the error field taking precedence. `INFOMSG` is a 45-character
   *       `COLOR=NEUTRAL` field at row 20 (`app/bms/COCRDLI.bms` L324-L328) and `ERRMSG` a
   *       78-character `COLOR=RED` field at row 23 (L330-L334); `1400-SETUP-MESSAGE`
   *       (`app/cbl/COCRDLIC.cbl` L895-L925) populates one or the other on most turns and can set
   *       both. The error is the one an operator must act on, so it wins when both would be present.
   * WHY : Alternatives Considered: rendering a second, informational line of its own beside the band.
   *       Rejected because the band exists to reserve its space at all times -- that is the browser
   *       form of row 23 always existing -- and a second line that appeared and disappeared would
   *       move the table beneath it, reintroducing the exact shift the band was composed to prevent.
   *       The band's severity prop already carries the neutral appearance, so one element serves both
   *       fields without a colour or a width being written here.
   * WHY : Assumptions: the informational sentence is the source's `WS-INFORM-REC-ACTIONS`, which
   *       `1400-SETUP-MESSAGE` sets whenever a page is displayed. It is what defines the `S` and `U`
   *       codes on the row controls, so it is not decoration -- it is the legend for them.
   */
  const bandMessage = error ?? CARD_LIST_STATUS_MESSAGES.WS_INFORM_REC_ACTIONS.text;

  const bandSeverity = error === null ? 'info' : 'error';

  /**
   * Loads one page and converts all transport failures into a non-sensitive band.
   * @param {string} [cursor] - Optional sealed service cursor.
   * @param {PageDirection} direction - Browse direction relative to the cursor.
   */
  function loadPage(cursor?: string, direction: PageDirection = 'next'): void {
    setLoading(true);
    setError(null);
    listCards({ cursor, direction }).then(
      /**
       * Publishes the retrieved page, including its sealed cursors.
       * @param {PageResponse<CardSummary>} nextPage - The page the service returned.
       */
      (nextPage) => {
        setPage(nextPage);
        setLoading(false);
      },
      /**
       * Reports a transport failure in the message band, naming the correlation
       * identifier rather than any card data, so nothing about a row is disclosed.
       */
      () => {
        setError(CARD_LIST_PAGE_UNAVAILABLE);
        setLoading(false);
      },
    );
  }

  useEffect(
    /** Loads the first page once, on mount, with no cursor. */
    () => {
      loadPage();
    },
    [],
  );

  /**
   * Acts on the entered card number, or clears the narrowing when it is blank.
   *
   * Assumptions: a partially typed number is not sent anywhere. The contract refuses any width but
   * sixteen, so acting on four digits would answer HTTP 400 rather than anything useful.
   *
   * Refactoring Rationale: an entered number RESOLVES to its one card rather than narrowing the page,
   * and the contract is what settles that: `card-api.yaml` declares no `cardNumber` query parameter, so
   * `listCards` has nowhere to put one -- and it has nowhere to put one because a query string is
   * written verbatim into the load balancer's mandatory access log, which is the same reason the number
   * left every path. `CardApiContractTest.noRequestLineCanCarryACardNumber` fails if either is
   * reintroduced.
   *
   * Assumptions: resolving loses nothing the baseline did. `CARDSIDI PIC X(16)` at
   * `app/cpy-bms/COCRDLI.CPY` L72 narrowed the list by card number, and the card number is the unique
   * primary key, so that narrowing could only ever yield ONE row -- which is the row this opens. The
   * divergence is registered as D-CARD-SELECTOR.
   */
  function applyCardNumberFilter(): void {
    if (cardNumber === '') {
      loadPage();
      return;
    }
    if (!enteredNumberIsAddressable) {
      /*
       * WHY : Refactoring Rationale: this is the LIST screen's own refusal, not the detail screen's.
       *       An earlier revision rendered `Card number if supplied must be a 16 digit number`, which
       *       is what `app/cbl/COCRDSLC.cbl` L149 and `app/cbl/COCRDUPC.cbl` L194 declare; this
       *       program declares the upper-case filter form at `app/cbl/COCRDLIC.cbl` L1058 instead.
       *       The two are near-duplicates, which is exactly why the wrong one read as correct in
       *       review -- and why the catalog holds both rather than folding them together.
       */
      setError(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER);
      return;
    }
    openEnteredCard(cardDetailPath);
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
      return;
    }
    setResolving(true);
    setError(null);
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
   * @returns {void} Completion is represented by the screen's own state.
   */
  function pageBackward(): void {
    if (page?.firstKey === null || page?.firstKey === undefined) {
      setError(CARD_LIST_PAGING_MESSAGES.NO_PREVIOUS_PAGES_TO_DISPLAY);
      return;
    }
    loadPage(page.firstKey, 'previous');
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
    if (page?.hasNext !== true || page.lastKey === null || page.lastKey === undefined) {
      setError(CARD_LIST_PAGING_MESSAGES.NO_MORE_PAGES_TO_DISPLAY);
      return;
    }
    loadPage(page.lastKey, 'next');
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
          applyCardNumberFilter();
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
          applyCardNumberFilter();
        }
      },
    },
  );

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
      <ScreenHeader
        transactionId={CARD_LIST_TRANSACTION_ID}
        programName={CARD_LIST_PROGRAM_NAME}
        now={paintedAt}
      />
      <Typography.Title level={3}>{CARD_LIST_TITLE}</Typography.Title>
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
      <Space.Compact>
        <Typography.Text id={CARD_NUMBER_LABEL_ID}>
          {CARD_LIST_LABELS.cardNumberFilter}
        </Typography.Text>
        <Input
          allowClear
          aria-labelledby={CARD_NUMBER_LABEL_ID}
          inputMode="numeric"
          maxLength={16}
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
              applyCardNumberFilter();
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
       * Refactoring Rationale: the band replaces a conditional raw antd Alert.
       * On this screen specifically the reserved space matters most of the
       * three: a failed page load previously shifted the whole table and the
       * controls beneath it upward, so the control an operator was about to
       * press moved under the cursor. Row 23 of app/bms/CCRDLIA never moved, and
       * MessageBand is where that invariant is expressed and tested.
       * Refactoring Rationale: the severity is now passed rather than defaulted.
       * This note previously recorded that the default "error" appearance was
       * taken, which held while the band carried only failures; it carries the
       * source's informational sentence too, and rendering that in the error
       * colour would misreport a normal page as a fault. The derivation and the
       * reason the source's two message fields collapse onto one band are
       * recorded at `bandMessage` above.
       * Assumptions: every transport failure this screen surfaces is already
       * reduced to non-sensitive text before it reaches here, so the band
       * receives text and a severity and learns nothing about the response --
       * which is the split the band's own props documentation requires.
       */}
      <MessageBand message={bandMessage} severity={bandSeverity} />
      <Table<CardSummary>
        columns={columns}
        dataSource={page?.items ?? []}
        loading={loading}
        pagination={false}
        rowKey={
          /*
           * WHY : Assumptions: the key is the account paired with the masked rendering, because no
           *       single member of a row is unique on its own. The masked rendering keeps only four
           *       digits, so two cards on different accounts can render identically, and an account
           *       holds more than one card -- the pair is what the contract's own row projection
           *       makes distinct. A row key is React's reconciliation identity and never travels in a
           *       request, so pairing two published members costs nothing and discloses nothing.
           */
          /**
           * Derives a row's reconciliation identity from the two members that distinguish it.
           * @param {CardSummary} row - One browse row.
           * @returns {string} The account paired with the masked rendering.
           */
          (row: CardSummary): string => `${row.accountId}:${row.displayCardNumber}`
        }
      />
      {/*
       * Refactoring Rationale: the bespoke `Previous` and `Next` controls are gone, and the key bar
       * below carries their actions as F7 and F8. Two things are recovered by the move. The source
       * screen has exactly one paging affordance -- the legend on row 24 -- so a second pair beside
       * the table was an addition, and a pair whose availability test read one envelope member while
       * its handler guard read another was how an earlier revision came to disable a control whose
       * cursor was present. The bindings above hold one predicate each, and both the key press and
       * the bar's button run it through the same dispatch path.
       * Assumptions: neither key is bound disabled, which is deliberate rather than an omission. The
       * source refuses neither PF7 nor PF8: the unavailable arms move a sentence into the error field
       * and re-read the current page, so an operator at either end of the browse gets a message.
       * Disabling the key would replace that message with silence.
       * Assumptions: `legendColor` is passed here and on no other screen in this tree, because
       * `app/bms/COCRDLI.bms` L336 paints this legend `COLOR=TURQUOISE` -- one of only two mapsets
       * that depart from the 15-of-17 yellow majority the bar defaults to.
       */}
      <PfKeyBar keys={bindings} onInvoke={invoke} legendColor="TURQUOISE" />
    </Flex>
  );
}
