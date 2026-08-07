import { Button, Descriptions, Flex, Result, Spin, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { getCard } from '../../api/cards';
import type { CardDetail } from '../../api/cards';
import { MessageBand } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import { STATUS_MESSAGES } from '../../messages/messages';
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
 * Renders one card addressed by the opaque selector in its route.
 *
 * Assumptions: the route parameter is validated before any request is issued, so a card number or a
 * masked rendering pasted into the address bar produces this screen's own invalid-link state rather
 * than an HTTP 400 -- and, more importantly, is never interpolated into a request line.
 * @returns {ReactElement} The card detail screen or a bounded invalid-link result.
 */
export function CardDetailScreen(): ReactElement {
  const navigate = useNavigate();
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
        /** Reports a retrieval failure using the source program's own not-found sentence. */
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
  if (loading) {
    return <Spin size="large" />;
  }

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the header band is composed here rather than once in the application shell,
       * because both of its values are this screen's own -- the transaction identifier and the
       * program name the 3270 screen painted in rows 1 and 2. `ScreenHeaderProps` requires both, so
       * shell-level composition was never reachable from this interface.
       */}
      <ScreenHeader
        transactionId={CARD_DETAIL_TRANSACTION_ID}
        programName={CARD_DETAIL_PROGRAM_NAME}
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
       * asserts. And it bypassed the 75-character content contract of
       * CCARD-ERROR-MSG / CCARD-RETURN-MSG (app/cpy/CVCRD01Y.cpy L28-L29) that
       * the band is the single enforcement point for.
       * Assumptions: no `severity` is passed. The band defaults to "error",
       * which is the only appearance the source field ever had -- COLOR=RED on
       * 21 of 21 mapsets -- and passing it explicitly would restate a default
       * this screen has no reason to vary.
       */}
      <MessageBand message={error} />
      {card === null ? null : (
        <Descriptions bordered column={2}>
          {/*
           * Assumptions: the five labels and their order are the mapset's, read top to bottom from
           * `app/bms/COCRDSL.bms` -- account number, card number, name on card, active flag, expiry
           * date. An earlier revision of this screen labelled them `Card`, `Account`, `Embossed
           * name`, `Expiration` and `Status`, none of which the source screen paints.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.accountNumber}>
            {card.accountId}
          </Descriptions.Item>
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.cardNumber}>
            {card.displayCardNumber}
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
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.expiryDate}>
            {card.expirationDate}
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
