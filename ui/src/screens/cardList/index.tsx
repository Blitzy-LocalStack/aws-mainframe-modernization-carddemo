import { Button, Flex, Input, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { listCards } from '../../api/cards';
import type { CardSummary, PageDirection, PageResponse } from '../../api/cards';
import { MessageBand } from '../../layout/MessageBand';
import { cardDetailPath, cardEditPath, isCardNumber } from '../../routes/cards';
import { navigateSafely } from '../../routes/navigation';

/**
 * Renders the keyset-paged card browse and the card-number entry that reaches one card.
 *
 * Refactoring Rationale: a card is reached by ENTERING its number rather than by clicking a row, and
 * an intermediate revision of this screen carried per-row View and Edit buttons built from an opaque
 * selector published on each row. The contract addresses a card by its number and publishes no
 * addressable value per row, so those buttons have nothing to build a route from. The entry field
 * below is the baseline's own workflow: its list screen carries a card-number filter field, CARDSIDI
 * at app/cpy-bms/COCRDLI.CPY:72, and its detail and update screens are entered by typing into it. The
 * same value narrows the list and opens one card, which is why one input serves both.
 * @returns {ReactElement} The card list screen.
 */
export function CardListScreen(): ReactElement {
  const navigate = useNavigate();
  const [page, setPage] = useState<PageResponse<CardSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cardNumber, setCardNumber] = useState('');

  const enteredNumberIsAddressable = isCardNumber(cardNumber);

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
        setError(
          'Card data is temporarily unavailable. Use the request correlation identifier from the response when contacting support.',
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
   * Narrows the browse to the entered card number, or clears the narrowing when it is blank.
   *
   * Assumptions: a partially typed number is not sent. The contract refuses any width but sixteen,
   * so filtering on four digits would answer HTTP 400 rather than a narrowed page.
   */
  function applyCardNumberFilter(): void {
    if (cardNumber === '') {
      loadPage();
      return;
    }
    if (!enteredNumberIsAddressable) {
      setError('Card number if supplied must be a 16 digit number');
      return;
    }
    setLoading(true);
    setError(null);
    listCards({ cardNumber }).then(
      /**
       * Publishes the narrowed page.
       * @param {PageResponse<CardSummary>} nextPage - The page the service returned.
       */
      (nextPage) => {
        setPage(nextPage);
        setLoading(false);
      },
      /** Reports a transport failure without disclosing anything about a row. */
      () => {
        setError(
          'Card data is temporarily unavailable. Use the request correlation identifier from the response when contacting support.',
        );
        setLoading(false);
      },
    );
  }

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
    {
      title: 'Card',
      dataIndex: 'displayCardNumber',
    },
    // WHY : Refactoring Rationale: the browse row carries the account number and
    //       not the embossed name or the expiration date, because those two are
    //       members of the card DETAIL and the contract's browse row is the three
    //       values the 3270 list displayed per row. Columns bound to absent
    //       members render as empty cells rather than failing, so the two that
    //       were bound here showed nothing; the account number is what the
    //       baseline row actually carried alongside the card number and status.
    {
      title: 'Account',
      dataIndex: 'accountId',
    },
    {
      title: 'Status',
      dataIndex: 'activeStatus',
      /**
       * Renders the active-status flag as the word the 3270 screen showed.
       * @param {CardSummary["activeStatus"]} status - The single-character flag as stored.
       * @returns {string} `Active` for `Y` and `Inactive` for anything else, matching the
       *   baseline's own two-valued display of CARD-ACTIVE-STATUS.
       */
      render: (status: CardSummary['activeStatus']): string =>
        status === 'Y' ? 'Active' : 'Inactive',
    },
  ];

  return (
    <Flex vertical gap="large">
      <Typography.Title level={2}>Cards</Typography.Title>
      {/*
        WHY : Assumptions: ONE input serves both narrowing the browse and reaching a single card,
              because the baseline screen's card-number field served both too -- an operator typed a
              number to narrow the list and typed one to open a card. Two separate inputs for one
              value would let them disagree, so an operator could narrow to one card and open
              another.
        WHY : Trade-offs: the two navigation controls are disabled until the entry is exactly
              sixteen digits, whereas the filter control accepts a blank entry as "no narrowing".
              The asymmetry is deliberate: a route cannot be built from a partial number at all,
              while a blank filter is a meaningful request. The cost is that the three controls
              beside one input do not enable together.
      */}
      <Space.Compact>
        <Input
          allowClear
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
          placeholder="Card number"
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
          Filter
        </Button>
        <Button
          disabled={!enteredNumberIsAddressable}
          onClick={
            /** Opens the entered card's detail route. */
            () => {
              navigateSafely(navigate, cardDetailPath(cardNumber));
            }
          }
        >
          View
        </Button>
        <Button
          disabled={!enteredNumberIsAddressable}
          onClick={
            /** Opens the entered card's update route. */
            () => {
              navigateSafely(navigate, cardEditPath(cardNumber));
            }
          }
        >
          Edit
        </Button>
      </Space.Compact>
      {/*
       * Refactoring Rationale: the band replaces a conditional raw antd Alert.
       * On this screen specifically the reserved space matters most of the
       * three: a failed page load previously shifted the whole table and the
       * paging buttons upward, so the button an operator was about to press
       * moved under the cursor. Row 23 of app/bms/CCRDLIA never moved, and
       * MessageBand is where that invariant is expressed and tested.
       * Assumptions: the default "error" severity is taken. Every transport
       * failure this screen surfaces is already reduced to non-sensitive text
       * by `loadPage`, so the band receives text and a severity and learns
       * nothing about the response -- which is the split the band's own props
       * documentation requires.
       */}
      <MessageBand message={error} />
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
        WHY : Refactoring Rationale: both controls page from the response's boundary members,
              firstKey and lastKey, because those are the whole of the envelope the services
              publish. An earlier revision of this screen paged from nextCursor and prevCursor,
              which the four-member envelope does not carry: the shared Java record declares
              items, firstKey, lastKey and hasNext and nothing else, so those two arrived
              undefined on every page and both controls were permanently disabled. The contract
              accepts one input named cursor and seals the direction into each token, so sending
              a boundary member under that name is both accepted and unambiguous.
        WHY : Assumptions: each control's disabled test and its handler guard read the SAME
              member, and the duplication is deliberate. The disabled test is what a pointer
              user sees; the guard is what narrows a nullable string to a string for the call
              and is what a keyboard activation of a control rendered before the state settled
              would meet. Reading two different members here is precisely how the earlier
              revision came to disable a control whose cursor was present.
      */}
      <Flex justify="space-between">
        {/*
          WHY : Assumptions: the backward control is bound to firstKey, which is both the
                identity of this page's first row and the position a backward request is issued
                from. The two are one value in this envelope by design - the service seals the
                direction into the token, so the same value cannot be replayed in the wrong
                direction - which is why one member can carry both roles without ambiguity.
        */}
        <Button
          disabled={loading || page?.firstKey === null || page?.firstKey === undefined}
          onClick={
            /**
             * Pages backward from the leading boundary the current page reports, which is the
             * READPREV equivalent; the guard repeats the availability condition so a keyboard
             * activation cannot page with an absent cursor.
             *
             * Assumptions: availability is read from `firstKey` rather than from a separate flag,
             * because the four-member envelope the services publish carries no `hasPrev`. A page
             * whose leading boundary is null has no backward position to send, which is exactly the
             * state that must disable this control.
             */
            () => {
              if (page?.firstKey !== null && page?.firstKey !== undefined) {
                loadPage(page.firstKey, 'previous');
              }
            }
          }
        >
          Previous
        </Button>
        <Button
          disabled={loading || page?.hasNext !== true}
          onClick={
            /**
             * Pages forward from the position the current page reports, which is the READNEXT
             * equivalent; the guard repeats the availability condition so a keyboard activation
             * cannot page with an absent cursor.
             */
            () => {
              if (page?.lastKey !== null && page?.lastKey !== undefined) {
                loadPage(page.lastKey, 'next');
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
