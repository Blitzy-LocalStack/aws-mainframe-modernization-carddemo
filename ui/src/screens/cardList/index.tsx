import { Alert, Button, Flex, Space, Table, Typography } from "antd";
import type { TableColumnsType } from "antd";
import { useEffect, useState } from "react";
import type { ReactElement } from "react";
import { useNavigate } from "react-router";

import { listCards } from "../../api/cards";
import type { CardSummary, PageResponse } from "../../api/cards";
import { cardDetailPath, cardEditPath } from "../../routes/cards";
import { navigateSafely } from "../../routes/navigation";

/**
 * Renders the keyset-paged card browse without placing PAN in a link target.
 * @returns {ReactElement} The card list screen.
 */
export function CardListScreen(): ReactElement {
  const navigate = useNavigate();
  const [page, setPage] = useState<PageResponse<CardSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /**
   * Loads one page and converts all transport failures into a non-sensitive band.
   * @param {string} [cursor] - Optional sealed service cursor.
   * @param {"next" | "previous"} direction - Browse direction relative to the cursor.
   */
  function loadPage(
    cursor?: string,
    direction: "next" | "previous" = "next",
  ): void {
    setLoading(true);
    setError(null);
    listCards(cursor, direction).then(
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
        setError(
          "Card data is temporarily unavailable. Use the request correlation identifier from the response when contacting support.",
        );
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
   * Opens the selected detail route using only its opaque selector.
   * @param {CardSummary} card - Selected card row.
   */
  function openCard(card: CardSummary): void {
    navigateSafely(navigate, cardDetailPath(card.opaqueCardId));
  }

  /**
   * Opens the selected update route using only its opaque selector.
   * @param {CardSummary} card - Selected card row.
   */
  function editCard(card: CardSummary): void {
    navigateSafely(navigate, cardEditPath(card.opaqueCardId));
  }

  const columns: TableColumnsType<CardSummary> = [
    {
      title: "Card",
      dataIndex: "displayCardNumber",
    },
    {
      title: "Embossed name",
      dataIndex: "embossedName",
    },
    {
      title: "Expiration",
      dataIndex: "expirationDate",
    },
    {
      title: "Status",
      dataIndex: "activeStatus",
      /**
       * Renders the active-status flag as the word the 3270 screen showed.
       * @param {CardSummary["activeStatus"]} status - The single-character flag as stored.
       * @returns {string} `Active` for `Y` and `Inactive` for anything else, matching the
       *   baseline's own two-valued display of CARD-ACTIVE-STATUS.
       */
      render: (status: CardSummary["activeStatus"]): string =>
        status === "Y" ? "Active" : "Inactive",
    },
    {
      title: "Actions",
      key: "actions",
      /**
       * Renders the per-row actions, which carry only the row's opaque selector.
       * @param {unknown} _value - The cell value, unused: this column has no data index, so
       *   the actions are derived from the row rather than from a field.
       * @param {CardSummary} card - The row the buttons act on.
       * @returns {ReactElement} The view and edit controls for that row.
       */
      render: (_value: unknown, card: CardSummary): ReactElement => (
        <Space>
          <Button
            onClick={
              /** Opens the row's detail route from its opaque selector. */
              () => {
                openCard(card);
              }
            }
          >
            View
          </Button>
          <Button
            onClick={
              /** Opens the row's update route from its opaque selector. */
              () => {
                editCard(card);
              }
            }
          >
            Edit
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Flex vertical gap="large">
      <Typography.Title level={2}>Cards</Typography.Title>
      {error === null ? null : <Alert type="error" message={error} showIcon />}
      <Table<CardSummary>
        columns={columns}
        dataSource={page?.items ?? []}
        loading={loading}
        pagination={false}
        rowKey="opaqueCardId"
      />
      <Flex justify="space-between">
        <Button
          disabled={
            loading || page?.firstKey === null || page?.firstKey === undefined
          }
          onClick={
            /**
             * Pages backward from the current page's first key, which is the
             * READPREV equivalent; the guard repeats the disabled condition so a
             * keyboard activation cannot page with an absent cursor.
             */
            () => {
              if (page?.firstKey !== null && page?.firstKey !== undefined) {
                loadPage(page.firstKey, "previous");
              }
            }
          }
        >
          Previous
        </Button>
        <Button
          disabled={
            loading ||
            page?.hasNext !== true ||
            page.lastKey === null ||
            page.lastKey === undefined
          }
          onClick={
            /**
             * Pages forward from the current page's last key, which is the READNEXT
             * equivalent; the guard repeats the disabled condition so a keyboard
             * activation cannot page with an absent cursor.
             */
            () => {
              if (page?.lastKey !== null && page?.lastKey !== undefined) {
                loadPage(page.lastKey, "next");
              }
            }
          }
        >
          Next
        </Button>
      </Flex>
    </Flex>
  );
}
