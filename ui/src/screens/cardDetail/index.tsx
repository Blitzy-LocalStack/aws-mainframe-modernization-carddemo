import {
  Alert,
  Button,
  Descriptions,
  Flex,
  Result,
  Spin,
  Typography,
} from "antd";
import { useEffect, useState } from "react";
import type { ReactElement } from "react";
import { useNavigate, useParams } from "react-router";

import { getCard } from "../../api/cards";
import type { CardDetail } from "../../api/cards";
import { cardEditPath, isOpaqueCardId } from "../../routes/cards";
import { navigationHandler } from "../../routes/navigation";

/**
 * Renders one card selected exclusively by a server-issued opaque identifier.
 * @returns {ReactElement} The card detail screen or a bounded invalid-link result.
 */
export function CardDetailScreen(): ReactElement {
  const navigate = useNavigate();
  const { opaqueCardId: routeIdentifier } = useParams<{
    opaqueCardId: string;
  }>();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const opaqueCardId =
    routeIdentifier !== undefined && isOpaqueCardId(routeIdentifier)
      ? routeIdentifier
      : null;

  useEffect(
    /**
     * Fetches the selected record whenever the validated selector changes, and
     * settles the loading state without a request when the selector is rejected.
     */
    () => {
      if (opaqueCardId === null) {
        setLoading(false);
        return;
      }
      setLoading(true);
      setError(null);
      getCard(opaqueCardId).then(
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
            "Card detail is temporarily unavailable. No card identifier was included in this diagnostic.",
          );
          setLoading(false);
        },
      );
    },
    [opaqueCardId],
  );

  if (opaqueCardId === null) {
    return (
      <Result
        status="error"
        title="Invalid card link"
        subTitle="The card selector is missing or malformed. Return to the card list and select the record again."
        extra={
          <Button onClick={navigationHandler(navigate, "/cards")}>
            Back to cards
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
      <Typography.Title level={2}>Card detail</Typography.Title>
      {error === null ? null : <Alert type="error" message={error} showIcon />}
      {card === null ? null : (
        <Descriptions bordered column={2}>
          <Descriptions.Item label="Card">
            {card.displayCardNumber}
          </Descriptions.Item>
          <Descriptions.Item label="Account">
            {card.accountId}
          </Descriptions.Item>
          <Descriptions.Item label="Embossed name">
            {card.embossedName}
          </Descriptions.Item>
          <Descriptions.Item label="Expiration">
            {card.expirationDate}
          </Descriptions.Item>
          <Descriptions.Item label="Status">
            {card.activeStatus === "Y" ? "Active" : "Inactive"}
          </Descriptions.Item>
        </Descriptions>
      )}
      <Flex gap="small">
        <Button onClick={navigationHandler(navigate, "/cards")}>Back</Button>
        <Button
          type="primary"
          disabled={card === null}
          onClick={navigationHandler(navigate, cardEditPath(opaqueCardId))}
        >
          Edit
        </Button>
      </Flex>
    </Flex>
  );
}
