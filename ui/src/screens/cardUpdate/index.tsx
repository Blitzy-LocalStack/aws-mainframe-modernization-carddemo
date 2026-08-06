import {
  Button,
  Flex,
  Form,
  Input,
  Result,
  Select,
  Spin,
  Typography,
} from "antd";
import { useEffect, useState } from "react";
import type { ReactElement } from "react";
import { useNavigate, useParams } from "react-router";

import { getCard, updateCard } from "../../api/cards";
import type { CardDetail, CardUpdateRequest } from "../../api/cards";
import { MessageBand } from "../../layout/MessageBand";
import {
  cardDetailPath,
  isOpaqueCardId,
  requireOpaqueCardId,
} from "../../routes/cards";
import { navigateSafely, navigationHandler } from "../../routes/navigation";

interface CardFormValues {
  readonly embossedName: string;
  readonly expirationDate: string;
  readonly activeStatus: "Y" | "N";
}

const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/u;

/**
 * Renders the card update form addressed by an opaque selector rather than PAN.
 * @returns {ReactElement} The card update screen or a bounded invalid-link result.
 */
export function CardUpdateScreen(): ReactElement {
  const navigate = useNavigate();
  const { opaqueCardId: routeIdentifier } = useParams<{
    opaqueCardId: string;
  }>();
  const [form] = Form.useForm<CardFormValues>();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const opaqueCardId =
    routeIdentifier !== undefined && isOpaqueCardId(routeIdentifier)
      ? routeIdentifier
      : null;

  useEffect(
    /**
     * Loads the record the form edits whenever the validated selector changes, and
     * settles the loading state without a request when the selector is rejected.
     */
    () => {
      if (opaqueCardId === null) {
        setLoading(false);
        return;
      }

      setLoading(true);
      getCard(opaqueCardId).then(
        /**
         * Seeds the form with the three editable fields and retains the loaded
         * record, whose version carries the optimistic-lock value the save needs.
         * @param {CardDetail} selectedCard - The record the service returned.
         */
        (selectedCard) => {
          setCard(selectedCard);
          form.setFieldsValue({
            embossedName: selectedCard.embossedName,
            expirationDate: selectedCard.expirationDate,
            activeStatus: selectedCard.activeStatus,
          });
          setLoading(false);
        },
        /**
         * Reports a retrieval failure without echoing the selector, so no card
         * identifier reaches the message band or any log that captures it.
         */
        () => {
          setError(
            "Card data could not be loaded. No card identifier was included in this diagnostic.",
          );
          setLoading(false);
        },
      );
    },
    [form, opaqueCardId],
  );

  /**
   * Submits validated editable fields with the current optimistic-lock version.
   * @param {CardFormValues} values - Values validated by the Ant Design form.
   */
  function save(values: CardFormValues): void {
    if (opaqueCardId === null || card === null) {
      setError("The card update cannot be submitted without a loaded record.");
      return;
    }

    const request: CardUpdateRequest = {
      ...values,
      version: card.version,
    };
    setSaving(true);
    setError(null);
    updateCard(requireOpaqueCardId(opaqueCardId), request).then(
      /**
       * Returns to the detail screen on the selector the service echoed back,
       * rather than the one submitted, so a re-keyed record is followed correctly.
       * @param {CardDetail} updated - The record as the service stored it.
       */
      (updated) => {
        setSaving(false);
        navigateSafely(navigate, cardDetailPath(updated.opaqueCardId));
      },
      /**
       * Reports a rejected update, including the optimistic-lock case, without
       * echoing the selector or any submitted field value.
       */
      () => {
        setSaving(false);
        setError(
          "The card update was not accepted. Reload the record before retrying if another user changed it.",
        );
      },
    );
  }

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
      <Typography.Title level={2}>Update card</Typography.Title>
      {/*
       * Refactoring Rationale: the band replaces a conditional raw antd Alert.
       * On an update screen the reserved space carries a second consequence
       * beyond layout stability: the baseline's own update programs re-send the
       * SAME map with row 23 populated (app/cbl/COCRDUPC.cbl), so the form and
       * its message occupy one screen with the message line in a fixed place.
       * A band that appears and disappears would move the very fields an
       * operator is correcting mid-correction.
       * Assumptions: the default "error" severity is taken rather than varied
       * per outcome. A successful save navigates away instead of reporting
       * success here, so this band only ever carries a failure -- and when a
       * success message is later required on this screen, `severity="success"`
       * is the one prop that changes.
       */}
      <MessageBand message={error} />
      <Form<CardFormValues>
        form={form}
        layout="vertical"
        onFinish={save}
        requiredMark
      >
        <Form.Item
          label="Embossed name"
          name="embossedName"
          rules={[
            { required: true, message: "Embossed name is required." },
            {
              max: 50,
              message: "Embossed name may contain at most 50 characters.",
            },
          ]}
        >
          <Input maxLength={50} />
        </Form.Item>
        <Form.Item
          label="Expiration date"
          name="expirationDate"
          rules={[
            { required: true, message: "Expiration date is required." },
            {
              pattern: ISO_DATE_PATTERN,
              message: "Expiration date must use YYYY-MM-DD.",
            },
          ]}
        >
          <Input maxLength={10} inputMode="numeric" />
        </Form.Item>
        <Form.Item
          label="Status"
          name="activeStatus"
          rules={[{ required: true, message: "Status is required." }]}
        >
          <Select
            options={[
              { label: "Active", value: "Y" },
              { label: "Inactive", value: "N" },
            ]}
          />
        </Form.Item>
        <Flex gap="small">
          <Button
            onClick={navigationHandler(navigate, cardDetailPath(opaqueCardId))}
          >
            Cancel
          </Button>
          <Button type="primary" htmlType="submit" loading={saving}>
            Save
          </Button>
        </Flex>
      </Form>
    </Flex>
  );
}
