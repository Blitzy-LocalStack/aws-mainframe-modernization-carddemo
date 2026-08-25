/**
 * @file Proves the card browse renders and sends its account narrowing, keys its rows by their own
 * selector, and cannot be corrupted by a paging response that settles out of order.
 *
 * Purpose
 * -------
 * Three properties of `app/cbl/COCRDLIC.cbl` that the browse screen either lacked or held unsafely.
 *
 * The account filter is the `CARDAIX` access path. `2210-EDIT-ACCOUNT` at L1003 to L1030 edits an
 * eleven-character field, treats eleven zeros as "not supplied", refuses anything non-numeric with a
 * sentence of its own, and carries the accepted value as the narrowing the browse reads under. The
 * screen transcribed that field's label and rendered no control for it, so the narrowing was
 * unreachable end to end.
 *
 * The row identity is the row's own sealed selector. The screen keyed rows by the account paired with
 * the masked rendering, and the masked rendering keeps four digits -- so two cards on ONE account whose
 * numbers end in the same four collide, and React reconciles two distinct rows as one.
 *
 * The paging state must move atomically. It was five independent pieces advanced in separate callbacks
 * with nothing identifying which request a settlement belonged to, so two steps taken while the first
 * was outstanding both applied.
 *
 * Refactoring Rationale: the out-of-order case is written with DEFERRED promises rather than by mocking
 * timers, because what must be asserted is the order two settlements are APPLIED in, not how long either
 * took. A timer-based arrangement asserts a duration and passes whenever the faster response happens to
 * be the earlier one, which is the arrangement that let the defect through in the first place.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it` and `waitFor` is a NAMED
 * declaration, which is the shape `ui/src/screens/cardScreens.test.tsx` records: `ui/eslint.config.js`
 * selects a function expression in every position, so an inline callback needs its own JSDoc block, and
 * Prettier moves a block comment that follows an argument comma onto the preceding string literal, which
 * detaches the block from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';

import { AppShell } from '../../layout/AppShell';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { listCards } from '../../api/cards';
import type { CardListQuery, CardSummary, PageResponse } from '../../api/cards';
import { SHARED_MESSAGES } from '../../messages/messages';
import { CARD_LIST_LABELS, CardListScreen } from './index';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The browse and lookup operations, each a fresh spy.
 */
function mockCardTransportModule(): Record<string, unknown> {
  return {
    listCards: vi.fn(),
    lookupCard: vi.fn(),
    getCard: vi.fn(),
    updateCard: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these
 * cases assert what the screen SENDS and which settlement it applies -- properties of the screen rather
 * than of the interceptor chain.
 */
vi.mock('../../api/cards', mockCardTransportModule);

/**
 * How long a wait for an asynchronous condition is given, in milliseconds.
 *
 * Assumptions: this exceeds Testing Library's own one-second default deliberately. These cases mount a
 * whole screen and wait on a condition that needs a network settlement, a microtask and a React render,
 * and the suite runs 30-odd files in parallel workers under a container CPU quota -- so a wait that is
 * comfortable in isolation can exceed one second when every worker is busy. The file was observed passing
 * on its own and timing out inside a full run, which is a scheduling property and not a defect in the code
 * under test; raising the ceiling removes the flake without weakening any assertion, because a wait only
 * ever ends early on success.
 *
 * Alternatives Considered: raising Testing Library's global `asyncUtilTimeout` in `ui/src/test/setup.ts`.
 * Rejected because that module's own overview states it does exactly three things and deliberately nothing
 * else, and a global timeout would change the failure latency of every existing case to suit two files.
 */
const ASYNC_CONDITION_TIMEOUT_MS = 5000;

/** A selector literal with the published length and alphabet that seals nothing. */
const SELECTOR_A = 'fake-selector-example-not-a-real-sealed-value-000000000000a';

/** A second selector, distinct from the first, for the colliding-row case. */
const SELECTOR_B = 'fake-selector-example-not-a-real-sealed-value-000000000000b';

/**
 * Two rows on ONE account whose masked renderings are identical.
 *
 * Assumptions: this is the collision the old row key could not distinguish, and it is reachable rather
 * than contrived: an account holds several cards and a mask keeps four digits, so two of them ending in
 * the same four render the same string. Their selectors differ because each seals a different number.
 */
const COLLIDING_ROWS: readonly CardSummary[] = [
  {
    key: SELECTOR_A,
    displayCardNumber: '************0011',
    accountId: '00000000011',
    activeStatus: 'Y',
  },
  {
    key: SELECTOR_B,
    displayCardNumber: '************0011',
    accountId: '00000000011',
    activeStatus: 'N',
  },
];

/**
 * Builds one page envelope around the rows given.
 * @param {readonly CardSummary[]} items - Rows the page carries.
 * @param {boolean} hasNext - Whether the envelope reports a further page.
 * @returns {PageResponse<CardSummary>} The envelope, with boundaries taken from the rows.
 */
function pageOf(items: readonly CardSummary[], hasNext: boolean): PageResponse<CardSummary> {
  return {
    items,
    firstKey: items.length === 0 ? null : `first-${items[0]?.key ?? ''}`,
    lastKey: items.length === 0 ? null : `last-${items[items.length - 1]?.key ?? ''}`,
    hasNext,
  };
}

/** One ordinary row, used wherever the rows themselves are not the subject. */
const ONE_ROW: readonly CardSummary[] = [
  {
    key: SELECTOR_A,
    displayCardNumber: '************0011',
    accountId: '00000000011',
    activeStatus: 'Y',
  },
];

/**
 * One promise whose settlement a case controls by hand.
 *
 * Refactoring Rationale: the three cases below each held their own resolver in a `let` assigned from
 * inside a `new Promise` executor, which put an arrow function in a position `ui/eslint.config.js`
 * requires a JSDoc block on while giving it nowhere the block would survive Prettier's reflow. One named
 * helper carries the documentation once and each case reads as the two facts that matter to it: which
 * read is held, and when it is released.
 * @template T Value the promise settles with.
 */
interface Deferred<T> {
  /** The promise a case hands to the transport spy in place of a resolved value. */
  readonly promise: Promise<T>;
  /** Settles that promise with the value given, which is what releases the held read. */
  readonly settle: (value: T) => void;
}

/**
 * Builds a promise whose settlement is deferred until a case asks for it.
 *
 * Assumptions: this is how the ordering cases express "outstanding". A read that is outstanding has not
 * settled, and a promise that has not resolved is exactly that -- no timer is involved, so nothing here
 * depends on one settlement being faster than another, which is the assumption that let an out-of-order
 * defect read as correct.
 * @template T Value the promise settles with.
 * @returns {Deferred<T>} The promise and the function that settles it.
 */
function deferred<T>(): Deferred<T> {
  /** Resolver of the promise below, replaced the moment the executor runs. */
  let capture: (value: T) => void = ignoreUntilCaptured;

  /**
   * Records the promise's resolver so a case can reach it.
   * @param {(value: T) => void} resolve - The resolver the promise supplies.
   * @returns {void} Nothing; the resolver is recorded above.
   */
  function captureResolver(resolve: (value: T) => void): void {
    capture = resolve;
  }

  /**
   * Settles the promise with the value given.
   * @param {T} value - What the held read answers with.
   * @returns {void} Nothing; the promise settles.
   */
  function settle(value: T): void {
    capture(value);
  }

  return { promise: new Promise<T>(captureResolver), settle };
}

/**
 * Stands in for a resolver until the executor supplies the real one.
 *
 * Assumptions: unreachable in practice, because a promise executor runs synchronously inside the
 * constructor, so the real resolver is in place before `deferred` returns. It exists so the binding has
 * no undefined state rather than as a fallback anything relies on.
 * @returns {void} Nothing.
 */
function ignoreUntilCaptured(): void {
  return undefined;
}

/**
 * Mounts the browse screen inside an in-memory router.
 * @returns {void} Nothing; the screen is rendered into the test document.
 */
function renderBrowse(): void {
  render(
    <MemoryRouter initialEntries={['/cards']}>
      <AppShell>
        <CardListScreen />
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Collapses a mapset label's internal padding so a query matches what the DOM normaliser produces.
 * @param {string} label - Verbatim mapset label.
 * @returns {string} The label with its runs of blanks collapsed and its ends trimmed.
 */
function collapse(label: string): string {
  return label.replace(/\s+/gu, ' ').trim();
}

/**
 * Reads the account-number filter control by its mapset label.
 *
 * Assumptions: no cast is applied, because `getByLabelText` is already typed to the element the control
 * renders and `@typescript-eslint/no-unnecessary-type-assertion` refuses a cast that narrows nothing.
 * @returns {HTMLInputElement} The account entry.
 */
function accountEntry(): HTMLInputElement {
  return screen.getByLabelText(collapse(CARD_LIST_LABELS.accountNumberFilter));
}

/**
 * Reads the request body of one recorded browse call.
 * @param {number} index - Which call to read, counting from zero.
 * @returns {CardListQuery} The query that call carried.
 */
function browseQuery(index: number): CardListQuery {
  return vi.mocked(listCards).mock.calls[index]?.[0] ?? {};
}

/**
 * Restores the module mocks between cases so one case's answer cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetCardMocks(): void {
  vi.mocked(listCards).mockReset();
}

/**
 * Asserts the account control is rendered, bounded to eleven characters and numeric.
 * @returns {Promise<void>} Resolves once the opening page has settled.
 */
async function rendersTheAccountNarrowingControl(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(pageOf(ONE_ROW, false));

  renderBrowse();
  await screen.findByRole('table');

  const entry = accountEntry();

  expect(entry).toBeInTheDocument();
  expect(entry.maxLength).toBe(11);
  expect(entry.inputMode).toBe('numeric');
}

/**
 * Asserts a well-formed account entry reaches the browse as a request member.
 *
 * Assumptions: the SECOND call is the one asserted, because the opening read happens on mount under no
 * narrowing -- so a screen that ignored the entry entirely would still have made a first call, and
 * asserting the first would pass against exactly the defect this case exists to catch.
 * @returns {Promise<void>} Resolves once the narrowed read has been recorded.
 */
async function sendsTheAccountNarrowing(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(pageOf(ONE_ROW, false));
  const user = userEvent.setup();

  renderBrowse();
  await screen.findByRole('table');

  await user.type(accountEntry(), '00000000011');
  await user.click(screen.getByRole('button', { name: 'Filter' }));

  await waitFor(
    /**
     * Waits until the narrowed read has been issued.
     * @returns {void} Nothing; throws until a second call is recorded.
     */
    () => {
      expect(vi.mocked(listCards).mock.calls.length).toBeGreaterThanOrEqual(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(browseQuery(0)).toStrictEqual({});
  expect(browseQuery(1)).toStrictEqual({ accountId: '00000000011' });
}

/**
 * Asserts eleven zeros clear the narrowing rather than being refused.
 *
 * Assumptions: this is the reference's own third not-supplied condition, `CC-ACCT-ID-N EQUAL ZEROS` at
 * `app/cbl/COCRDLIC.cbl` L1009, and it is the one a reader implementing the field from its picture
 * clause alone would miss -- eleven zeros are eleven digits, so a width-and-alphabet check accepts them
 * as a narrowing and the browse then reads under an account that cannot exist.
 * @returns {Promise<void>} Resolves once the read has been recorded.
 */
async function readsElevenZerosAsNoNarrowing(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(pageOf(ONE_ROW, false));
  const user = userEvent.setup();

  renderBrowse();
  await screen.findByRole('table');

  await user.type(accountEntry(), '00000000000');
  await user.click(screen.getByRole('button', { name: 'Filter' }));

  await waitFor(
    /**
     * Waits until the turn's read has been issued.
     * @returns {void} Nothing; throws until a second call is recorded.
     */
    () => {
      expect(vi.mocked(listCards).mock.calls.length).toBeGreaterThanOrEqual(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(browseQuery(1)).toStrictEqual({});
  expect(
    screen.queryByText(
      collapse(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER),
    ),
  ).toBeNull();
}

/**
 * Asserts a malformed account entry raises the account sentence, wins over a malformed card entry, and
 * issues no request.
 *
 * Assumptions: BOTH entries are malformed on the same turn, which is what makes this a precedence
 * assertion rather than two independent ones. `2200-EDIT-INPUTS` runs the account arm first
 * (`app/cbl/COCRDLIC.cbl` L983 to L996), the account arm writes its sentence unconditionally at L1021 to
 * L1023 and the card arm writes its own only while none is set, so the account sentence is what an
 * operator reads.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function refusesAMalformedAccountFirst(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(pageOf(ONE_ROW, false));
  const user = userEvent.setup();

  renderBrowse();
  await screen.findByRole('table');

  await user.type(accountEntry(), '123');
  await user.type(screen.getByLabelText(collapse(CARD_LIST_LABELS.cardNumberFilter)), '4444');
  await user.click(screen.getByRole('button', { name: 'Filter' }));

  /*
   * WHY : ⚠️ Refactoring Rationale: the sentence is expected on TWO surfaces and the query is plural
   *       accordingly, where this read `findByText` and now finds two matches. Both are correct and the
   *       pair is the reference's own behaviour: `2210-EDIT-ACCOUNT` moves the sentence into the row-23
   *       error field AND turns the offending field red (`app/cbl/COCRDLIC.cbl` L1021-L1030 with
   *       `1300-SETUP-SCREEN-ATTRS`), so the target renders it once in the control's own accessible
   *       description -- which is what associates it with the field a screen reader is on -- and once in
   *       the delegated row-23 band. Narrowing this to one surface would either drop the association or
   *       drop the line, and the singular query would fail on whichever change restored the other.
   */
  const raised = await screen.findAllByText(
    collapse(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER),
  );

  expect(raised.length).toBeGreaterThanOrEqual(1);
  expect(document.getElementById('card-list-account-number-error')?.textContent ?? '').toContain(
    collapse(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER),
  );
  /*
   * WHY : ⚠️ Refactoring Rationale: precedence is asserted on the BAND, where this asserted the card
   *       sentence was absent from the whole document. That stronger claim was only true while the
   *       screen could mark one field, and marking one field was itself the defect: `2200-EDIT-INPUTS`
   *       performs BOTH edits unconditionally (`app/cbl/COCRDLIC.cbl` L989-L993 -- the `GO TO` at L1025
   *       leaves that paragraph's own exit at L1032, not the caller), so both filter flags can be set on
   *       one turn and the two highlight tests at L872 and L877 are INDEPENDENT `IF`s. The card field is
   *       therefore reddened too, and it now carries its own accessible description so a screen reader
   *       on that field is told what is wrong with THAT field rather than about the account.
   * WHY : Assumptions: what precedence actually means here is which single sentence reaches the row-23
   *       line, and that is the account's because its arm writes `WS-ERROR-MSG` unconditionally at L1021
   *       to L1023 while the card arm writes only `IF WS-ERROR-MSG-OFF` at L1056. Asserting the band
   *       tests exactly that, and the two field descriptions below test the other half.
   */
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '').toContain(
    collapse(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER),
  );
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '').not.toContain(
    collapse(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER),
  );
  expect(document.getElementById('card-list-card-number-error')?.textContent ?? '').toContain(
    collapse(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER),
  );
  expect(vi.mocked(listCards)).toHaveBeenCalledTimes(1);
}

/**
 * Reports whether one control label is the detail selection code.
 * @param {string} label - A rendered control's trimmed text.
 * @returns {boolean} `true` for the `'S'` code `app/cbl/COCRDLIC.cbl` L77-L79 declares.
 */
function isDetailControlLabel(label: string): boolean {
  return label === 'S';
}

/**
 * Reports whether one control label is the update selection code.
 * @param {string} label - A rendered control's trimmed text.
 * @returns {boolean} `true` for the `'U'` code `app/cbl/COCRDLIC.cbl` L77-L79 declares.
 */
function isUpdateControlLabel(label: string): boolean {
  return label === 'U';
}

/**
 * Asserts two rows whose masked renderings collide are keyed apart.
 *
 * Assumptions: the assertion reads each row's `data-row-key` attribute, which is where antd's `Table`
 * writes the value `rowKey` produced, and asserts the two are DISTINCT and are the rows' own selectors.
 * Counting the rendered rows and their controls is not enough and was tried first: React renders every
 * child of an array even when two share a key, warning rather than dropping one, so a page of two
 * colliding rows still renders two rows and four controls. What the collision destroys is reconciliation
 * IDENTITY -- which row a later render matches to which node -- and the key attribute is the only place
 * that identity is observable.
 * @returns {Promise<void>} Resolves once the page has rendered.
 */
async function keysCollidingRowsApart(): Promise<void> {
  vi.mocked(listCards).mockResolvedValue(pageOf(COLLIDING_ROWS, false));

  renderBrowse();
  const table = await screen.findByRole('table');

  await waitFor(
    /**
     * Waits until both data rows have rendered.
     * @returns {void} Nothing; throws until the flags of both rows are present.
     */
    () => {
      expect(within(table).getByText('N')).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(within(table).getByText('Y')).toBeInTheDocument();

  /*
   * WHY : ⚠️ Refactoring Rationale: the two control counts are taken by reading the buttons' own text
   *       rather than through `getAllByRole('button', { name })`, and the change is a PERFORMANCE fix
   *       with the assertion left intact -- both forms count the controls labelled `S` and `U` inside
   *       this table. The role-and-name form made this case the only one in the suite that could not
   *       finish: measured on a four-core runner it cost 70 seconds for the `S` query and a further 154
   *       seconds for the `U` query, against a 60-second `testTimeout`, so the case timed out before
   *       reaching the row-key assertion it exists for. The cost is not in the DOM walk but in the
   *       accessible-NAME computation the `name` option triggers: `dom-accessibility-api` asks
   *       `getComputedStyle(element, '::before')` for each candidate, jsdom implements no
   *       pseudo-element form of that call, and every one of those routes through its virtual console --
   *       which is also why the run prints `Not implemented: Window's getComputedStyle() method: with
   *       pseudo-elements`. Reading `textContent` needs no accessible name and completes immediately.
   * WHY : Alternatives Considered: (1) raising `testTimeout` past 224 seconds, rejected because it
   *       leaves a four-minute case in the suite and hides the cause rather than removing it;
   *       (2) narrowing the query scope further, rejected because the scope is already this one table
   *       and the cost is per-candidate rather than per-node; (3) dropping the two counts, rejected
   *       because the paragraph above records that counting the controls was a deliberate part of this
   *       case even though the row keys are what it turns on.
   * WHY : Assumptions: counting two of each is what establishes that BOTH rows rendered, so the row-key
   *       assertion below is comparing two distinct rendered rows rather than one row React reconciled
   *       twice. The count is the point here, not the role.
   * WHY : Alternatives Considered: (4) `getAllByText('S')` / `getAllByText('U')` scoped to the table,
   *       which is equally free of the accessible-name cost -- independently measured at 2ms against
   *       1.1s for the same role query on a bare table and 20s to 40s once this screen is mounted in the
   *       shell. Rejected in favour of the form below only because it is stricter: it counts rendered
   *       CONTROLS in `tbody`, so a stray text node reading `S` or `U` anywhere else in the table cannot
   *       satisfy the count.
   */
  const controlLabels = Array.from(table.querySelectorAll('tbody button')).map(
    /**
     * Reads one rendered control's visible label.
     * @param {Element} control - One button rendered inside the table body.
     * @returns {string} The control's text, trimmed.
     */
    (control: Element): string => (control.textContent ?? '').trim(),
  );

  expect(controlLabels.filter(isDetailControlLabel)).toHaveLength(2);
  expect(controlLabels.filter(isUpdateControlLabel)).toHaveLength(2);

  /*
   * WHY : ⚠️ Refactoring Rationale: the selector excludes the design system's OWN measure row, which it
   *       did not need to before. Under a declared table layout antd renders one extra `tr` carrying
   *       `ant-table-measure-row` as the first child of the body -- it holds a zero-height cell per
   *       column so the browser can report each column's resolved extent -- and that row carries no
   *       `data-row-key`, so an unfiltered walk collected a leading `null` and the comparison failed
   *       against a screen whose two data rows were keyed exactly as this case requires. The layout was
   *       declared to stop this grid painting outside the viewport at a phone width, so the measure row
   *       is a consequence of a fix rather than a regression to catch here.
   *       Assumptions: the row is excluded by its own class rather than by dropping rows with no key,
   *       because a DATA row that lost its key is precisely the defect this case exists to catch -- and
   *       a filter on the key's presence would silently absorb it.
   */
  const rowKeys = Array.from(table.querySelectorAll('tbody tr:not(.ant-table-measure-row)')).map(
    /**
     * Reads one rendered row's reconciliation key.
     * @param {Element} row - One rendered table row.
     * @returns {string | null} The key antd wrote for it.
     */
    (row: Element): string | null => row.getAttribute('data-row-key'),
  );

  expect(rowKeys).toStrictEqual([SELECTOR_A, SELECTOR_B]);
}

/**
 * Asserts a superseded paging response cannot replace the page a later request delivered.
 *
 * ⚠️ Refactoring Rationale: the two outstanding turns are a BACKWARD step and a FORWARD one, and they
 * were two forward steps. Two forward steps no longer produce two reads: `ui/src/hooks/usePagedQuery.ts`
 * coalesces identical in-flight turns, keyed on the browse identity, the restart generation, the
 * direction and the cursor, so the second click joined the first read instead of issuing one. The case
 * was then asserting nothing -- the later response it settled had never been requested, so the negative
 * assertion held whatever the hook did with the earlier one. Opposite directions are never coalesced,
 * because they carry different cursors and would answer with different rows, so a backward step followed
 * by a forward one is the smallest arrangement that still puts two genuinely different reads in flight.
 *
 * Assumptions: the SECOND of the two settles first, and applying the first afterwards is the defect
 * being guarded against: its rows would replace the ones on display while the ordinal had already moved
 * again, and the ordinal is what decides the backward refusal -- so the screen would then offer a step
 * back from a page it was not showing.
 *
 * Assumptions: the arrangement needs a page from which BOTH keys are live, which is why one forward step
 * is taken and settled before the two held reads are started. `hasPrev` is the hook's own ordinal rather
 * than anything the envelope carries, so it becomes true only once a page has been left behind.
 * @returns {Promise<void>} Resolves once both settlements have been attempted.
 */
async function discardsASupersededPagingResponse(): Promise<void> {
  const first = pageOf(ONE_ROW, true);
  const stale: readonly CardSummary[] = [
    {
      key: SELECTOR_A,
      displayCardNumber: '************7777',
      accountId: '00000000011',
      activeStatus: 'Y',
    },
  ];
  const fresh: readonly CardSummary[] = [
    {
      key: SELECTOR_B,
      displayCardNumber: '************9999',
      accountId: '00000000022',
      activeStatus: 'N',
    },
  ];

  const supersededRead = deferred<PageResponse<CardSummary>>();
  const currentRead = deferred<PageResponse<CardSummary>>();

  vi.mocked(listCards)
    .mockResolvedValueOnce(first)
    .mockResolvedValueOnce(pageOf(ONE_ROW, true))
    .mockReturnValueOnce(supersededRead.promise)
    .mockReturnValueOnce(currentRead.promise);

  const user = userEvent.setup();

  renderBrowse();
  await screen.findByRole('table');

  // WHY : Assumptions: one forward step is taken and ANSWERED first, so the browse is on a page with a
  //       page behind it and a page ahead of it -- which is what makes both of the next two keys live.
  await user.click(screen.getByRole('button', { name: 'F8=Forward' }));
  await waitFor(
    /**
     * Waits until the backward key has become live, which is the second page having arrived.
     * @returns {void} Nothing; throws until the key is enabled.
     */
    () => {
      expect(screen.getByRole('button', { name: 'F7=Backward' })).toBeEnabled();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: backward THEN forward, so the two reads carry different directions and different
  //       cursors and the hook has nothing to coalesce them on. Taken the other way round the assertions
  //       would read identically and the case would still be exercising one read.
  await user.click(screen.getByRole('button', { name: 'F7=Backward' }));
  await user.click(screen.getByRole('button', { name: 'F8=Forward' }));
  expect(vi.mocked(listCards), 'both turns must have reached the client').toHaveBeenCalledTimes(4);

  currentRead.settle(pageOf(fresh, true));
  await waitFor(
    /**
     * Waits until the later page is on display.
     * @returns {void} Nothing; throws until its row has rendered.
     */
    () => {
      expect(screen.getByText('************9999')).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  /*
   * WHY : Assumptions: the superseded settlement is released INSIDE `act`, and the assertions run after
   *       it returns. `act` flushes every update the settlement schedules before yielding, so an
   *       arrangement that did apply the stale page has applied it by the time the two assertions below
   *       run. Awaiting the promise alone is not enough and was tried first: the hook's own continuation
   *       runs in a later microtask and React may batch the render it dispatches, so the negative
   *       assertion passed against a screen that was about to show the stale rows.
   */
  await act(
    /**
     * Releases the superseded settlement and lets every update it schedules flush.
     * @returns {Promise<void>} Resolves once the settlement has been delivered.
     */
    async (): Promise<void> => {
      supersededRead.settle(pageOf(stale, true));
      await supersededRead.promise;
    },
  );

  expect(screen.getByText('************9999')).toBeInTheDocument();
  expect(screen.queryByText('************7777')).toBeNull();
}

/**
 * Asserts a turn taken while a read is outstanding issues no second read.
 *
 * Assumptions: the read is held open for the whole case, so the second click has no settled state to
 * observe -- which is exactly the window a terminal did not have, because a 3270 turn is serialised and a
 * second key could not arrive while the first was being processed.
 * @returns {Promise<void>} Resolves once both clicks have been made.
 */
async function suppressesADuplicateTurn(): Promise<void> {
  const heldRead = deferred<PageResponse<CardSummary>>();

  vi.mocked(listCards)
    .mockResolvedValueOnce(pageOf(ONE_ROW, false))
    .mockReturnValue(heldRead.promise);

  const user = userEvent.setup();

  renderBrowse();
  await screen.findByRole('table');

  await user.type(accountEntry(), '00000000011');
  await user.click(screen.getByRole('button', { name: 'Filter' }));

  await waitFor(
    /**
     * Waits until the narrowed read has been issued.
     * @returns {void} Nothing; throws until a second call is recorded.
     */
    () => {
      expect(vi.mocked(listCards)).toHaveBeenCalledTimes(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await user.click(screen.getByRole('button', { name: 'Filter' }));

  expect(vi.mocked(listCards)).toHaveBeenCalledTimes(2);

  heldRead.settle(pageOf(ONE_ROW, false));
  await heldRead.promise;
}

/**
 * The exit sentence is not written at all, not merely never seen.
 *
 * Refactoring Rationale: this case exists because a DOM assertion CANNOT discriminate the fix, and the
 * sibling suite established that by measurement rather than assumption -- `accountView.test.tsx` L297
 * records that with the removed write restored, its runtime case still passed, because React batches the
 * state write with the route transition and the screen unmounts before any paint. A browser run against
 * THIS screen measured the same write surviving 29 ms on `/menu` before the arriving screen's shared-slot
 * registration cleared it, which is short enough that no DOM assertion can catch it reliably and long
 * enough that an operator sees a sentence the reference never paints.
 *
 * Assumptions: `PF03 PRESSED.EXITING` is never transmitted by the reference, which is why the write was
 * withdrawn rather than re-plumbed. `WS-ERROR-MSG` is WORKING-STORAGE at `app/cbl/COCRDLIC.cbl` L117 and
 * an `EXEC CICS XCTL` discards it; `app/cpy/COCOM01Y.cpy` declares no message member for it to travel in;
 * the arm sets it at L396 with no `SEND MAP` before the transfer at L402; and `app/cbl/COMEN01C.cbl`
 * L79-L80 clears its own message on entry regardless.
 *
 * Assumptions: the assertion is made against the module's SOURCE and is narrow -- the catalog entry must
 * not be referenced by this screen outside a comment. The entry itself stays in
 * `ui/src/messages/messages.ts`, because transformation rule T8 keeps the transcription of every
 * `88`-level sentence complete whether or not a program reaches it; what must not exist is a screen that
 * emits it. The technique and the `join(import.meta.dirname, ...)` spelling both follow
 * `accountView.test.tsx` L320-L330, which records that `new URL('./index.tsx', import.meta.url)` does not
 * work here because Vite rewrites that exact syntax as an asset reference.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function referencesNoExitSentence(): void {
  const source = readFileSync(join(import.meta.dirname, 'index.tsx'), 'utf8');
  const referencing = source.split('\n').filter(
    /**
     * Keeps a line that reads the catalog entry rather than one that explains its absence.
     * @param {string} line - One line of the module.
     * @returns {boolean} `true` when the line references the entry outside a comment.
     */
    (line: string): boolean =>
      line.includes('WS_EXIT_MESSAGE') && !line.trimStart().startsWith('*'),
  );

  expect(referencing).toEqual([]);
}

/**
 * Registers the eight cases.
 * @returns {void} Nothing.
 */
function browseNarrowingCases(): void {
  afterEach(resetCardMocks);

  it(
    'renders the account narrowing control the mapset declares',
    rendersTheAccountNarrowingControl,
  );
  it('sends a well-formed account narrowing as a request member', sendsTheAccountNarrowing);
  it('reads eleven zeros as no narrowing at all', readsElevenZerosAsNoNarrowing);
  it('refuses a malformed account entry ahead of the card entry', refusesAMalformedAccountFirst);
  it('keys two rows with identical masked renderings apart', keysCollidingRowsApart);
  it('discards a superseded paging response', discardsASupersededPagingResponse);
  it('issues no second read for a turn taken while one is outstanding', suppressesADuplicateTurn);
  it('writes no exit sentence the reference never transmits', referencesNoExitSentence);
}

describe(
  'the card browse narrows by account and pages without racing itself',
  browseNarrowingCases,
);
