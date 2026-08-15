/**
 * @file The card detail screen, migrated from `app/cbl/COCRDSLC.cbl` and its mapset
 * `app/bms/COCRDSL.bms` (31 `DFHMDF` fields), reached at the card detail route.
 *
 * Purpose
 * -------
 * Render one card as a read-only record view and offer the single transition the reference screen
 * offers, into the update screen. It replaces CICS transaction CCDL, which `app/csd/CARDDEMO.CSD`
 * L347-L348 binds to that program, and it publishes the field labels and guidance strings the
 * screen tests assert against.
 *
 * Mandatory-versus-optional selector
 * ----------------------------------
 * Assumptions: this screen's selector is MANDATORY where the browse screen's narrowings are
 * optional, and the difference is read off the reference rather than decided here: `COCRDSLC`
 * pre-sets its edit flags to NOT-OK at its L648 and L688, while `COCRDLIC` pre-sets its own to
 * BLANK. A selector that is absent or not of the published shape therefore renders the guidance
 * result rather than issuing a request.
 *
 * Composition
 * -----------
 * Assumptions: the record is rendered with antd `Descriptions` rather than a form, because four of
 * the six data fields the mapset paints are read-only -- `CRDNAME` carries no `ATTRB` at
 * `app/bms/COCRDSL.bms` L107 and `CRDSTCD`, `EXPMON` and `EXPYEAR` are `ATTRB=(ASKIP)` at L116, L126
 * and L133 -- so a form would offer an editing affordance the transaction does not have. Only the two
 * search fields are `UNPROT`, and they are not inputs here for the reason below.
 *
 * Where the two search fields went
 * --------------------------------
 * Refactoring Rationale: the mapset's two editable fields, `ACCTSID` at `app/bms/COCRDSL.bms`
 * L84-L88 and `CARDSID` at L96-L100, are NOT reproduced as inputs on this screen, and the values they
 * gathered arrive as the route selector instead. Those fields exist in the reference because a
 * pseudo-conversational transaction had nowhere else to keep a selection: `COCRDSLC` gathers them
 * into `CC-ACCT-ID` and `CC-CARD-NUM` and carries them across the screen turn in the communication
 * area. A stateless target has a better place for a selection than a pair of fields the user retypes,
 * and it is the one the reference itself prefers when it has a choice -- arriving from the browse
 * screen the program skips the fields entirely and reads immediately, because "SELECTION CRITERIA
 * ALREADY VALIDATED" (`app/cbl/COCRDSLC.cbl` L336-L344). The migration makes that the only path:
 * selection context becomes a REST path parameter, so a card is addressed rather than searched for.
 * Assumptions: the criteria-gathering behaviour is preserved, and preserved once, on the browse
 * screen at `/cards`, which owns the account and card filter fields together with the two
 * `..._FILTER_IF_SUPPLIED_MUST_BE_A_..._DIGIT_NUMBER` sentences and the first-error-wins precedence
 * those edits carry. Reproducing them here as well would put two independent implementations of one
 * validation chain in the tree, and would let a user reach a card by a route selector on one turn and
 * by a retyped number on the next.
 */

import { Button, Descriptions, Flex, Result, Spin, Typography, theme } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { getCard } from '../../api/cards';
import type { CardDetail } from '../../api/cards';
import { MessageBand } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import { STATUS_MESSAGES } from '../../messages/messages';
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';
import { cardEditPath, isCardSelector } from '../../routes/cards';
import { navigateSafely, navigationHandler } from '../../routes/navigation';

/** Screen-level messages this screen renders, taken verbatim from the catalog keyed by its program. */
const CARD_DETAIL_MESSAGES = STATUS_MESSAGES.COCRDSLC;

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L347-L348 defines it. */
export const CARD_DETAIL_TRANSACTION_ID = 'CCDL';

/** Source program name, rendered in the header band exactly as the 3270 screen did. */
export const CARD_DETAIL_PROGRAM_NAME = 'COCRDSLC';

/**
 * Screen title, verbatim from the mapset's own title field at `app/bms/COCRDSL.bms` L78.
 *
 * Assumptions: this lives in the screen module rather than in the message catalog because that is the
 * ownership boundary the catalog itself draws: it excludes every `INITIAL=` literal a `.bms` file
 * paints and records that "a screen's own field labels belong to that screen under
 * `ui/src/screens/**`". A field label is positional -- it is meaningless apart from the control it
 * sits beside -- so centralising it would separate it from the only thing that gives it meaning.
 */
export const CARD_DETAIL_TITLE = 'View Credit Card Detail';

/**
 * The five field labels this screen paints, verbatim from `app/bms/COCRDSL.bms`.
 *
 * Assumptions: the interior runs of spaces and the trailing space on two of them are part of the
 * value, not formatting. The mapset pads each label to a fixed cell width so the colons line up down
 * the column, and the transcription rule for this tree is byte-exact; a renderer may collapse the
 * runs visually, but nothing here may discard them.
 */
export const CARD_DETAIL_FIELD_LABELS = {
  /** `app/bms/COCRDSL.bms` L83. */
  accountNumber: 'Account Number    :',
  /** `app/bms/COCRDSL.bms` L95. */
  cardNumber: 'Card Number       :',
  /** `app/bms/COCRDSL.bms` L106. */
  nameOnCard: 'Name on card      :',
  /** `app/bms/COCRDSL.bms` L115. */
  cardActive: 'Card Active Y/N   : ',
  /** `app/bms/COCRDSL.bms` L125. */
  expiryDate: 'Expiry Date       : ',
} as const;

/**
 * Function-key legend labels, split from this mapset's own legend literal.
 *
 * Assumptions: `app/bms/COCRDSL.bms` L147-L152 paints exactly `ENTER=Search Cards  F3=Exit` in one
 * `COLOR=YELLOW` field, and `app/cbl/COCRDSLC.cbl` L292-L293 admits exactly those two attention
 * identifiers, so the painted legend and the accepted key set agree and these two are the whole
 * contract. No third key is bound: the uniform legend labels were NOT assumed here, because two of
 * the three sibling card mapsets paint different key sets and one of them paints two legend fields.
 */
const CARD_DETAIL_KEY_LABELS = {
  ENTER: 'ENTER=Search Cards',
  PFK03: 'F3=Exit',
} as const;

/**
 * Label of the control that opens the update screen for the card on display.
 *
 * Assumptions: this string is NEW and is held here rather than in the message catalog, on the rule the
 * catalog states for itself -- it "excludes strings that no COBOL source holds". The source screen has
 * no counterpart to carry across: `app/bms/COCRDSL.bms` paints no such key, and the baseline reaches
 * the update program by typing `U` beside a row on the list screen. The control is retained because
 * removing it would leave a user looking at a card unable to edit it without returning to the list.
 */
export const CARD_DETAIL_EDIT_CONTROL_LABEL = 'Edit';

/**
 * Guidance shown beside the refused-link result, which is likewise a new string.
 *
 * Assumptions: the result's heading is the baseline's own `No input received`, which is what
 * `app/cbl/COCRDSLC.cbl` L143 declares for a turn that carried no usable search key -- and an absent
 * or malformed selector is exactly that. Only the guidance below it is new, because the selector is a
 * target construct (see **D-CARD-SELECTOR** in the divergence register) and the baseline has
 * no sentence about a value it never had.
 */
export const CARD_DETAIL_INVALID_LINK_GUIDANCE =
  'Return to the card list and select the record again.';

/**
 * Splits the stored ten-character expiry text into the two parts the mapset paints.
 *
 * Assumptions: the named groups are the reference's own regrouping of the same bytes.
 * `app/cbl/COCRDSLC.cbl` L84 declares `CARD-EXPIRAION-DATE-X PIC X(10)` and L85-L92 redefine it as
 * `CARD-EXPIRY-YEAR PIC X(4)`, a one-byte FILLER, `CARD-EXPIRY-MONTH PIC X(2)`, a second FILLER and
 * `CARD-EXPIRY-DAY PIC X(2)`, so the day is matched here only to establish that the whole value has
 * the shape those offsets assume -- it is never read.
 */
const STORED_EXPIRY_TEXT = /^(?<year>[0-9]{4})-(?<month>[0-9]{2})-(?<day>[0-9]{2})$/u;

/**
 * Renders a stored expiry the way the source screen paints it: month, a solidus, then year.
 *
 * Assumptions: the stored value is the ten-character ISO text `YYYY-MM-DD` and stays TEXT rather than
 * becoming an instant, which is the reason `ui/src/api/types.ts` gives for its own date members: the
 * expiry boundary is inclusive in the reference, where a transaction dated equal to the expiration
 * date posts and one day later is refused, so a timezone-induced day of drift would change an outcome
 * rather than a rendering.
 *
 * Assumptions: THE DAY IS DELIBERATELY DISCARDED, and that is the mapset's decision rather than a
 * simplification made here. `app/bms/COCRDSL.bms` paints exactly two data fields for this value --
 * `EXPMON LENGTH=2` at L126-L129 and `EXPYEAR LENGTH=4` at L133-L136 -- separated by a one-character
 * `INITIAL='/'` literal at L130-L132, and the program moves only `CARD-EXPIRY-MONTH` and
 * `CARD-EXPIRY-YEAR` into them at `app/cbl/COCRDSLC.cbl` L480 and L482. There is no map field for the
 * day, so rendering the stored text whole shows a component the terminal never displayed.
 *
 * Trade-offs: a value of unexpected shape is returned UNCHANGED rather than sliced at the fixed
 * offsets the reference's positional MOVE would use. Slicing blindly is the closer transcription and
 * was rejected because of what it produces on a value that is not ten-character ISO text: a
 * plausible-looking `MM/YYYY` assembled from the wrong bytes, which a reader cannot tell apart from a
 * correct rendering. Passing the value through instead keeps a contract change visible.
 * @param {string} expirationDate - Stored expiry as ten-character `YYYY-MM-DD` text, exactly as
 *   `CardDetail` carries it.
 * @returns {string} The `MM/YYYY` rendering the mapset's two fields and their separator produce
 *   together, or the argument unchanged when it is not ten-character ISO date text.
 */
export function formatCardExpiry(expirationDate: string): string {
  const matched = STORED_EXPIRY_TEXT.exec(expirationDate);
  // Assumptions: both parts are read through optional chaining and tested before use because
  //   `noUncheckedIndexedAccess` is in force in ui/tsconfig.json, which types a capture-group lookup
  //   as `string | undefined`. The test the compiler requires is also the unexpected-shape branch the
  //   trade-off above describes, so one expression discharges both.
  const year = matched?.groups?.year;
  const month = matched?.groups?.month;
  // WHY : Alternatives Considered: a guard clause of the ordinary form -- `if (...) { return
  //       expirationDate; }` followed by the composed return -- which is the shape this function would
  //       otherwise take. Rejected for a concrete and easily-missed reason: it would place a line
  //       matching `/^ {2}if \(/` ABOVE `CardDetailScreen`, and
  //       `ui/src/layout/screenHeaderClock.test.tsx` L75-L77 uses exactly that pattern to locate this
  //       module's first component-level early return, then asserts the `useServerInstant` call sits
  //       above it. Its own stated assumption is that a two-space `if (` is component-body level, so a
  //       module-level helper carrying one makes that search find this function instead of the
  //       component and fails the rules-of-hooks assertion with a message naming a hook this function
  //       does not call. A single conditional expression has no `if` to find, so the helper can stay
  //       above its call site in ordinary reading order without disturbing that contract.
  return year === undefined || month === undefined ? expirationDate : `${month}/${year}`;
}

/**
 * Renders one card addressed by the opaque selector in its route.
 *
 * Assumptions: the route parameter is validated before any request is issued, so a card number or a
 * masked rendering pasted into the address bar produces this screen's own invalid-link state rather
 * than an HTTP 400 -- and, more importantly, is never interpolated into a request line.
 * @returns {ReactElement} The card detail screen or a bounded invalid-link result.
 */
export function CardDetailScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: the fixed-pitch face is read as a token NAME from ui/src/theme/tokens.ts and
   *       resolved through `cssVar`, never written as a font literal. `cssVar` returns the reference
   *       form -- `var(--ant-font-family-code)` -- so the element keeps following the theme antd 6
   *       installs through CSS variables, whereas the sibling `token` member of the same hook returns
   *       a RESOLVED value and would bake today's face into this element and silently opt it out of
   *       later theme changes. `ui/src/layout/ScreenHeader.tsx` L463 reads the same token the same way
   *       for the same two fixed-width slots it paints.
   */
  const { cssVar } = theme.useToken();
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: the parameter is spelled `cardKey`, which is the name
   *       `ui/src/routes/cards.ts` L88 declares in `CARD_DETAIL_ROUTE = '/cards/:cardKey'` and which
   *       `ui/src/router.tsx` mounts this screen under. The spelling is load-bearing rather than
   *       cosmetic: `useParams` resolves an unmatched name to `undefined` without any diagnostic, so a
   *       near-miss such as `num` or `cardNumber` would compile, type-check and render this screen's
   *       invalid-link result on every visit -- a screen that is permanently empty for a reason no
   *       error reports.
   * WHY : Assumptions: what the parameter carries is an opaque SELECTOR and never a card number, so
   *       the guard below is `isCardSelector` rather than a length or digit test. `ui/src/api/cards.ts`
   *       records why the number may not travel in a path at all: a target and its query string are
   *       written into the edge access log and the browser's history before any application code runs,
   *       and neither store is reachable by anything this screen could add afterwards.
   */
  const { cardKey: routeIdentifier } = useParams<{
    cardKey: string;
  }>();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const selector =
    routeIdentifier !== undefined && isCardSelector(routeIdentifier) ? routeIdentifier : null;

  /*
   * WHY : Refactoring Rationale: the read is a named callback rather than a body inlined in the
   *       effect, because two callers now need it -- the mount effect and the Enter key. The source
   *       program has the same shape: `9000-READ-DATA` is one paragraph performed both on entry from
   *       the list screen and from the Enter arm (`app/cbl/COCRDSLC.cbl` L339-L345), so a single
   *       reader is the source's own structure rather than a convenience. `useCallback` is what keeps
   *       it usable as an effect dependency; an ordinary function would be a new value each render
   *       and would re-run the effect on every one of them.
   */
  const reload = useCallback(
    /**
     * Reads the addressed card and publishes it, or settles without a request when the selector was
     * rejected.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      if (selector === null) {
        setLoading(false);
        return;
      }
      setLoading(true);
      setError(null);
      getCard(selector).then(
        /**
         * Publishes the retrieved record to the screen.
         * @param {CardDetail} selectedCard - The record the service returned.
         */
        (selectedCard) => {
          setCard(selectedCard);
          setLoading(false);
        },
        /*
         * WHY : Trade-offs: one sentence covers every rejection, where the source program branches on
         *       the file response -- `DFHRESP(NOTFND)` sets this sentence at `app/cbl/COCRDSLC.cbl`
         *       L755-L761 while any other response composes `WS-FILE-ERROR-MESSAGE` at L762-L771. The
         *       coarser of the two is chosen deliberately. The transport module reports a rejection
         *       without its status, so naming the finer branch would assert a cause this screen has
         *       not established; and the other branch's text is the composed one, which appends the
         *       internal file name and the CICS response and reason codes and is therefore not
         *       carried across at all -- the same withholding the message catalog's redaction
         *       register applies at 28 other sites.
         *       Assumptions: the sentence names no card, so no identifier reaches the band or any log
         *       that captures it.
         */
        /**
         * Reports a retrieval failure using the source program's own not-found sentence.
         * @returns {void} Nothing; the outcome is published through the screen's own state.
         */
        () => {
          setError(CARD_DETAIL_MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text);
          setLoading(false);
        },
      );
    },
    [selector],
  );

  useEffect(
    /**
     * Reads the record once on mount and again whenever the validated selector changes.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      reload();
    },
    [reload],
  );

  /*
   * WHY : Assumptions: the handler map is keyed by CICS attention identifier, so the PF13-PF24
   *       aliasing `app/cpy/CSSTRPFY.cpy` L54-L77 performs is applied once by the hook rather than by
   *       every screen. Exactly the two identifiers `app/cbl/COCRDSLC.cbl` L292-L293 admits are
   *       bound, and both carry a label because this mapset paints both of them.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Re-reads the card on display, which is what the source program's Enter arm does: it
         * performs `9000-READ-DATA` and re-sends the same map (`app/cbl/COCRDSLC.cbl` L339-L345).
         * @returns {void} Nothing; the re-read publishes its result through the screen's own state.
         */
        onInvoke: () => {
          reload();
        },
        label: CARD_DETAIL_KEY_LABELS.ENTER,
      },
      PFK03: {
        /**
         * Returns to the caller, which the source program resolves to the list screen it was reached
         * from and otherwise to the main menu (`app/cbl/COCRDSLC.cbl` L305-L333). The browse screen
         * is this route's only caller in the delivered route table, so it is the single destination.
         * @returns {void} Nothing; the transition is performed as a side effect on the router.
         */
        onInvoke: () => {
          navigateSafely(navigate, '/cards');
        },
        label: CARD_DETAIL_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Coerces an unmapped key into a reload of the card, showing no message.
       *
       * Assumptions: an unrecognised key re-runs the Enter arm and shows NO message, because that
       * is precisely what the source program does -- `app/cbl/COCRDSLC.cbl` L291-L299 sets an
       * invalid flag and then `SET CCARD-AID-ENTER TO TRUE`, so the key press is coerced rather
       * than reported. This deliberately differs from the sign-on screen, whose `WHEN OTHER`
       * branch does move `CCDA-MSG-INVALID-KEY` into the message field; carrying that behaviour
       * here would invent a message this screen never shows. Trade-offs: only the `unmapped`
       * rejection is coerced. A `disabled` rejection cannot arise on this screen, since neither
       * binding is ever disabled, so coercing it too would be unreachable code rather than
       * fidelity.
       * @param {object} rejection - Why the key was not dispatched, whose `reason`
       *   distinguishes an unmapped key from a disabled binding.
       * @returns {void} Nothing; the coerced arm performs the reload itself.
       */
      onInvalidKey: (rejection) => {
        if (rejection.reason === 'unmapped') {
          reload();
        }
      },
    },
  );

  /*
   * WHY : Assumptions: this refusal deliberately renders WITHOUT the screen shell -- no header band,
   *       no title, no function-key bar -- and that is the reference's own shape for this class of
   *       outcome rather than an omission. `COCRDSLC` has two ways to answer: it either sends the map
   *       (`1400-SEND-SCREEN`, which paints the header rows and the legend row) or it calls
   *       `SEND-PLAIN-TEXT` at `app/cbl/COCRDSLC.cbl` L838-L848, which issues `SEND TEXT ... ERASE`
   *       and therefore clears the screen of the map entirely. An absent or malformed selector is the
   *       second case: it is the target's equivalent of the `WHEN OTHER` arm at L373-L380, reached
   *       only when a caller supplied something the screen cannot address. Rendering the full shell
   *       around it would imply a usable screen behind the message, when in fact there is no record,
   *       no valid selector and nothing the two function keys could act on.
   * WHY : Alternatives Considered: a `MessageBand` message on an otherwise-populated screen, which is
   *       how every RECOVERABLE outcome on this screen is reported. Rejected because the two are not
   *       the same kind of failure: a failed read leaves the user somewhere they can retry from,
   *       whereas an unaddressable selector leaves nothing to retry -- so `Result status="error"`,
   *       which the design system maps to the abend surface, is the honest surface, and the single
   *       control it offers is the exit the reference offers from the same dead end.
   */
  if (selector === null) {
    return (
      <Result
        status="error"
        title={CARD_DETAIL_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text}
        subTitle={CARD_DETAIL_INVALID_LINK_GUIDANCE}
        extra={
          <Button onClick={navigationHandler(navigate, '/cards')}>
            {CARD_DETAIL_KEY_LABELS.PFK03}
          </Button>
        }
      />
    );
  }
  /*
   * WHY : Alternatives Considered: keeping the header band and the message band mounted around the
   *       spinner, so only the record region swapped. Rejected as out of proportion to what it buys
   *       here. A loading state has NO counterpart in the reference at all -- a 3270 terminal holds
   *       the previous map until the next one arrives, so there is no painted state to be faithful to
   *       -- which means neither choice can be argued from the source, and the plain spinner is the
   *       one that cannot go stale: composing the band would mean rendering a header whose clock and
   *       identifiers describe a record not yet read. Trade-offs: the accepted cost is one layout
   *       shift when the record arrives and the chrome appears beneath it. That cost is bounded to
   *       this first paint only; the band that exists specifically to stop LATER shifts -- reserving
   *       its own height whether or not it holds a message -- is mounted for every subsequent state.
   */
  if (loading) {
    return <Spin size="large" />;
  }

  /*
   * WHY : Assumptions: the three FIXED-WIDTH values below are rendered in the code face and the two
   *       free-text ones are not, which is the distinction `TYPOGRAPHY_TOKENS.fixedPitchData` records:
   *       the 3270 cell grid aligned every column for free, and a proportional face gives digits
   *       different advance widths, so the eleven-digit account identifier, the twelve asterisks and
   *       four digits of the masked rendering, and the `MM/YYYY` expiry stop lining up down the value
   *       column. The embossed name and the one-character active flag are excluded deliberately -- a
   *       name is proportional text with nothing to align against, and a single character cannot
   *       misalign.
   * WHY : Alternatives Considered: setting the face on the `Descriptions` component so every value
   *       inherited it. Rejected because it would put the 50-character embossed name in the code face
   *       as well, which neither aligns anything nor matches the reference: `CRDNAME` is the one data
   *       field on this mapset that carries no numeric or fixed-width content.
   */
  const fixedPitchValueStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the header band is composed here rather than once in the application shell,
       * because both of its values are this screen's own -- the transaction identifier and the
       * program name the 3270 screen painted in rows 1 and 2. `ScreenHeaderProps` requires both, so
       * shell-level composition was never reachable from this interface.
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
        transactionId={CARD_DETAIL_TRANSACTION_ID}
        programName={CARD_DETAIL_PROGRAM_NAME}
        now={paintedAt}
      />
      {/*
       * Assumptions: level 3 sits below the band's own level-4 application heading in size but is a
       * SEPARATE field in the source -- the mapset paints its title at row 4, under the two title
       * rows the band reproduces -- so the two are not competing for one slot. Level 2 was used here
       * before the band was composed, when this was the only heading on the screen.
       */}
      <Typography.Title level={3}>{CARD_DETAIL_TITLE}</Typography.Title>
      {/*
       * Refactoring Rationale: the screen's outcome goes through MessageBand
       * rather than a raw antd Alert rendered only when `error` is non-null.
       * Two things were wrong with the conditional Alert. It reserved no space,
       * so the descriptions below jumped down the moment a retrieval failed --
       * whereas row 23 of the 3270 screen this replaces always existed whether
       * or not it held text, which is the invariant MessageBand encodes and
       * asserts. And it bypassed the message contract of CCARD-ERROR-MSG /
       * CCARD-RETURN-MSG (app/cpy/CVCRD01Y.cpy L28-L29) that the band is the
       * single enforcement point for.
       * Assumptions: no `severity` is passed. The band defaults to "error",
       * which is the only appearance the source field ever had -- COLOR=RED on
       * 21 of 21 mapsets -- and passing it explicitly would restate a default
       * this screen has no reason to vary.
       * Assumptions: the mapset IS passed, and this screen is one of the two
       * where it changes the rendering. app/cpy-bms/COCRDSL.CPY L102/L194 declare
       * ERRMSGI/ERRMSGO at PIC X(80) rather than the X(78) nineteen mapsets use,
       * so omitting the name here would render this screen's message five
       * characters narrower than the terminal did.
       *
       * Assumptions: the 80 and the band's own exported 75 are NOT in conflict,
       * and the reason is measurable in the source rather than a matter of
       * interpretation. `app/cbl/COCRDSLC.cbl` L102-L121 composes
       * `WS-FILE-ERROR-MESSAGE` from nine parts -- X(12) 'File Error: ', X(8)
       * operation, X(4) ' on ', X(9) file, X(15) ' returned RESP ', X(10)
       * response, X(7) ',RESP2 ', X(10) reason, then a trailing X(5) FILLER of
       * spaces -- which sums to exactly 80 with meaningful content ending at
       * byte 75. That is why L771 can MOVE the 80-byte group into the X(75)
       * `WS-RETURN-MSG` losing nothing but padding, and why L494 widens it back
       * to the X(80) map field. So 75 is the message CONTENT contract
       * (`CCARD-ERROR-MSG`/`CCARD-RETURN-MSG`, app/cpy/CVCRD01Y.cpy L28-L29) and
       * 80 is this screen's FIELD width; the band owns both figures through
       * `messageBandWidthForMapset`, so no width literal is declared here.
       * Alternatives Considered: declaring a local 80 constant for this screen.
       * Rejected because the band already types that lookup as `78 | 80` per
       * mapset, and a second literal would be a second declaration of one fact
       * that could later disagree with the table the band renders from.
       */}
      <MessageBand mapset="COCRDSL" message={error} />
      {/*
       * WHY : Trade-offs: the record is laid out by GROUPING and not by the mapset's absolute
       *       coordinates, which is documented gap G1 in the `DESIGN_GAPS` register of
       *       `ui/src/theme/tokens.ts`. Every field on this map carries a `POS=(row,col)` on a fixed
       *       24x80 character grid -- `ACCTSID` at `POS=(7,45)`, `CRDNAME` at `POS=(11,25)`,
       *       `EXPYEAR` at `POS=(15,30)` -- and none of that survives. What is given up is
       *       character-cell fidelity; what is kept is field grouping, reading order and tab order,
       *       which are the properties an operator actually navigates by. Reproducing the grid was
       *       rejected on two specific grounds: absolute positioning cannot reflow, so the layout
       *       would break at any viewport narrower than 80 monospace columns, and a grid of
       *       positioned cells gives a screen reader no label-to-value association, whereas
       *       `Descriptions` emits each pair as a row a reader announces together.
       * WHY : Assumptions: `column={2}` pairs the five items across two columns, and the two-column
       *       figure is the mapset's own shape rather than a preference -- the source paints its
       *       label column and its value column side by side down the body zone, at the two distinct
       *       `col` values the `POS=` operands above show.
       */}
      {card === null ? null : (
        <Descriptions bordered column={2}>
          {/*
           * Assumptions: the five labels and their order are the mapset's, read top to bottom from
           * `app/bms/COCRDSL.bms` -- account number, card number, name on card, active flag, expiry
           * date. An earlier revision of this screen labelled them `Card`, `Account`, `Embossed
           * name`, `Expiration` and `Status`, none of which the source screen paints.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.accountNumber}>
            <Typography.Text style={fixedPitchValueStyle}>{card.accountId}</Typography.Text>
          </Descriptions.Item>
          {/*
           * WHY : Assumptions: the rendering is emitted EXACTLY as the service returned it -- this
           *       screen neither masks nor unmasks. `ui/src/api/cards.ts` produces the masked form
           *       server-side and checks it on arrival against the contract's own
           *       `^\*{12}[0-9]{4}$`, and the whole sixteen-digit number is published only by
           *       `getAdminCardDetail`, a SEPARATE address under a separate authority that answers an
           *       ordinary caller with HTTP 403. Reformatting here would either undo a deliberate
           *       redaction or re-apply one to a value already redacted, and re-masking would hide a
           *       server-side rendering failure the client is positioned to report.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.cardNumber}>
            <Typography.Text style={fixedPitchValueStyle}>{card.displayCardNumber}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.nameOnCard}>
            {card.embossedName}
          </Descriptions.Item>
          {/*
           * Assumptions: the flag renders as the stored character, `Y` or `N`, which is what the
           * source screen displays -- `CRDSTCD` is a one-character field and its label names the
           * domain, `Card Active Y/N`. An earlier revision expanded it to `Active` and `Inactive`,
           * two words no COBOL source in this application holds, and the expansion also contradicted
           * the label beside it.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.cardActive}>
            {card.activeStatus}
          </Descriptions.Item>
          {/*
           * WHY : Refactoring Rationale: the stored value was rendered WHOLE here, as `2023-01-20`,
           *       and it is now put through `formatCardExpiry` to paint `01/2023`. Rendering it whole
           *       showed the day, and the terminal had no field to show a day in: the mapset paints
           *       `EXPMON LENGTH=2` and `EXPYEAR LENGTH=4` with a `'/'` literal between them
           *       (`app/bms/COCRDSL.bms` L126-L136) and the program fills only those two
           *       (`app/cbl/COCRDSLC.cbl` L480 and L482). It also showed them in the opposite order
           *       and under a different separator, so an operator reading `2023-01` off this screen
           *       would read a year where the terminal put a month.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.expiryDate}>
            <Typography.Text style={fixedPitchValueStyle}>
              {formatCardExpiry(card.expirationDate)}
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
      )}
      {/*
       * Refactoring Rationale: the bespoke `Back` control is gone. It navigated to the browse screen,
       * which is exactly what the PF3 binding below now does, and the key bar renders that binding as
       * a button of its own -- so keeping both would have put two controls for one action on the
       * screen, with two places for the destination to drift apart. The remaining control has no key
       * behind it, which is why it stays.
       */}
      <Flex gap="small">
        <Button
          type="primary"
          disabled={card === null}
          onClick={navigationHandler(navigate, cardEditPath(selector))}
        >
          {CARD_DETAIL_EDIT_CONTROL_LABEL}
        </Button>
      </Flex>
      {/*
       * Assumptions: the legend colour is left at its default. `app/bms/COCRDSL.bms` L148 paints this
       * screen's legend `COLOR=YELLOW`, which is the 15-of-17 majority the bar already defaults to,
       * so passing it explicitly would restate a default. The browse screen is one of the two
       * measured exceptions and does pass it.
       */}
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}
