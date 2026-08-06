import { Button, Descriptions, Flex, Result, Spin, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { getCard } from '../../api/cards';
import type { CardDetail } from '../../api/cards';
import { MessageBand } from '../../layout/MessageBand';
import { cardEditPath, isCardNumber } from '../../routes/cards';
import { navigationHandler } from '../../routes/navigation';

/**
 * Renders one card addressed by the sixteen-digit card number in its route.
 *
 * Assumptions: the route parameter is validated before any request is issued, so a masked rendering
 * pasted into the address bar produces this screen's own not-found state rather than an HTTP 400.
 * @returns {ReactElement} The card detail screen or a bounded invalid-link result.
 */
export function CardDetailScreen(): ReactElement {
  const navigate = useNavigate();
  const { cardNumber: routeIdentifier } = useParams<{
    cardNumber: string;
  }>();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const cardNumber =
    routeIdentifier !== undefined && isCardNumber(routeIdentifier) ? routeIdentifier : null;

  useEffect(
    /**
     * Fetches the selected record whenever the validated selector changes, and
     * settles the loading state without a request when the selector is rejected.
     */
    () => {
      if (cardNumber === null) {
        setLoading(false);
        return;
      }
      setLoading(true);
      setError(null);
      getCard(cardNumber).then(
        /**
         * Publishes the retrieved record to the screen.
         * @param {CardDetail} selectedCard - The record the service returned.
         */
        (selectedCard) => {
          setCard(selectedCard);
          setLoading(false);
        },
        /**
         * Reports a retrieval failure without echoing the selector, so no card
         * identifier reaches the message band or any log that captures it.
         */
        () => {
          setError(
            'Card detail is temporarily unavailable. No card identifier was included in this diagnostic.',
          );
          setLoading(false);
        },
      );
    },
    [cardNumber],
  );

  if (cardNumber === null) {
    return (
      <Result
        status="error"
        title="Invalid card link"
        subTitle="The card selector is missing or malformed. Return to the card list and select the record again."
        extra={<Button onClick={navigationHandler(navigate, '/cards')}>Back to cards</Button>}
      />
    );
  }
  if (loading) {
    return <Spin size="large" />;
  }

  return (
    <Flex vertical gap="large">
      <Typography.Title level={2}>Card detail</Typography.Title>
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
          <Descriptions.Item label="Card">{card.displayCardNumber}</Descriptions.Item>
          <Descriptions.Item label="Account">{card.accountId}</Descriptions.Item>
          <Descriptions.Item label="Embossed name">{card.embossedName}</Descriptions.Item>
          <Descriptions.Item label="Expiration">{card.expirationDate}</Descriptions.Item>
          <Descriptions.Item label="Status">
            {card.activeStatus === 'Y' ? 'Active' : 'Inactive'}
          </Descriptions.Item>
        </Descriptions>
      )}
      <Flex gap="small">
        <Button onClick={navigationHandler(navigate, '/cards')}>Back</Button>
        <Button
          type="primary"
          disabled={card === null}
          onClick={navigationHandler(navigate, cardEditPath(cardNumber))}
        >
          Edit
        </Button>
      </Flex>
    </Flex>
  );
}
