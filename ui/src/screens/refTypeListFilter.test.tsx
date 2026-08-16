/**
 * @file Proves the transaction-type list reports a filtered read that matched nothing as the reference's
 * own input error rather than as an abend.
 *
 * Purpose
 * -------
 * `ui/src/screens/refTypeList/index.tsx` replaces
 * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`. Its `1290-CROSS-EDITS` paragraph at L1241-L1266 runs
 * only when a filter was supplied, and on a zero count it raises
 * `'No Records found for these filter conditions'`, marks each SUPPLIED filter in error and sets
 * `FLG-PROTECT-SELECT-ROWS-YES` -- on a screen that is otherwise intact.
 *
 * What these cases exist to prevent
 * ---------------------------------
 * ⚠️ Assumptions: `TransactionTypeService.requireFilterMatchesSomething` transcribes that paragraph
 * faithfully as an INPUT ERROR, so it raises a `ClientInputException` and the transport answers HTTP 400
 * -- which a keyset browse can only report as a failed read. The screen's own filtered-empty predicate
 * required `!browse.isFailed`, so it excluded the only route the condition actually arrives by, and every
 * filtered miss fell through to the abend arm and told an operator `UNEXPECTED ABEND OCCURRED.` The
 * gentlest outcome the reference has was reported as its harshest.
 *
 * Assumptions: the cases assert BOTH halves -- the verbatim sentence appearing AND the abend sentence not
 * appearing -- because a screen that rendered both would satisfy a one-sided assertion while still
 * telling the operator something had gone catastrophically wrong.
 *
 * Assumptions: the transport module is mocked so each case controls what the service answers, and the
 * verbatim sentences are read from the catalog rather than retyped, for the reason the sibling screen
 * tests record.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';

import { AppShell } from '../layout/AppShell';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../api/client';
import { listTransactionTypes } from '../api/reference';
import type { ApiError, FieldError, PageResponse, TransactionType } from '../api/types';
import { PROGRAM_MESSAGES, SHARED_MESSAGES, STATUS_MESSAGES } from '../messages/messages';
import RefTypeListScreen, { REF_TYPE_LIST_LABELS, isFilteredEmptyRefusal } from './refTypeList';

/**
 * Builds the mocked surface of the reference transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The three transport functions this screen calls, each a fresh spy.
 */
function mockReferenceTransportModule(): Record<string, unknown> {
  return {
    listTransactionTypes: vi.fn(),
    replaceTransactionType: vi.fn(),
    deleteTransactionType: vi.fn(),
  };
}

vi.mock('../api/reference', mockReferenceTransportModule);

/** Sentences this program declares, from the single catalog that owns them. */
const LIST_MESSAGES = PROGRAM_MESSAGES.COTRTLIC;

/** Status sentences this program declares, from the same catalog. */
const LIST_STATUS = STATUS_MESSAGES.COTRTLIC;

/** The verbatim refusal a filtered read that matched nothing raises. */
const FILTER_REFUSAL = LIST_MESSAGES.NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS;

/** The service's own name for the type-code filter, which is not this screen's control name. */
const SERVICE_TYPE_CODE_FIELD = 'typeCode';

/** The service's own name for the description filter. */
const SERVICE_DESCRIPTION_FIELD = 'description';

/**
 * Builds one problem document at a status with the supplied per-field entries.
 *
 * Assumptions: every member the shape declares is supplied rather than the object being widened with a
 * cast, because this document is what the screen reads its branch out of -- a partial stand-in would let
 * a case pass against a member the real client always sends.
 * @param {number} status - HTTP status the service answered with.
 * @param {readonly FieldError[]} fieldErrors - The per-field entries the document carries.
 * @returns {ApiError} The normalised problem document.
 */
function problem(status: number, fieldErrors: readonly FieldError[]): ApiError {
  return {
    code: 'CARDDEMO-0400',
    secondaryCode: '',
    message: FILTER_REFUSAL,
    severity: 'WARNING',
    subsystem: 'RELATIONAL',
    status,
    correlationId: 'test-correlation-id',
    path: '/api/v1/reference/transaction-types',
    timestamp: '2025-01-01T00:00:00Z',
    fieldErrors,
    abend: null,
  };
}

/**
 * Builds one per-field entry naming a service filter field.
 * @param {string} field - The service's field name.
 * @returns {FieldError} The entry the document carries for it.
 */
function entryFor(field: string): FieldError {
  return { field, state: 'NOT_OK', message: FILTER_REFUSAL };
}

/**
 * Builds the failure the shared client raises for the service's filtered-empty refusal.
 * @param {readonly FieldError[]} fieldErrors - The filters the service found supplied.
 * @returns {ApiRequestError} The normalised failure a browse would receive.
 */
function refusal(fieldErrors: readonly FieldError[]): ApiRequestError {
  return new ApiRequestError(
    'PROBLEM',
    400,
    problem(400, fieldErrors),
    'filtered read matched nothing',
  );
}

/** An empty page, for the unfiltered read the screen issues on mount. */
const EMPTY_PAGE: PageResponse<TransactionType> = {
  items: [],
  firstKey: null,
  lastKey: null,
  hasNext: false,
};

/** One stored row, so the opening read delivers something and the grid is live. */
const STORED_PAGE: PageResponse<TransactionType> = {
  items: [{ typeCd: '01', description: 'PURCHASE', version: 1 }],
  firstKey: '01',
  lastKey: '01',
  hasNext: false,
};

/**
 * Renders the screen inside a router, which `useNavigate` requires.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(): ReactElement {
  return (
    <MemoryRouter initialEntries={['/reference/transaction-types']}>
      {/*
        WHY : ⚠️ Refactoring Rationale: the screen is rendered INSIDE `AppShell`, where it was rendered
              bare. The screen delegates its title band, its row-23 message line and its row-24 legend to
              the one shell that `ui/src/router.tsx` mounts as a layout route -- it composes none of the
              three itself -- so a bare render produced a screen with no legend and no band, and every
              query for either failed on a screen that is in fact correct. The children form is used
              rather than a layout route because it is the shape that needs no second route level, and
              `AppShell` renders `children ?? <Outlet />`, so both forms paint the same frame.
      */}
      <AppShell>
        <RefTypeListScreen />
      </AppShell>
    </MemoryRouter>
  );
}

/** Restores the spies between cases. */
function resetSpies(): void {
  vi.mocked(listTransactionTypes).mockReset();
}

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: needed because two of this mapset's own literals carry padding -- the select-column
 * heading is `'Select    '` at `LENGTH=10` over a six-character word -- and the DOM collapses it on
 * display. Collapsing the expectation keeps the mapset as the source of truth.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Applies a type-code filter and waits for the refused read to be reported.
 * @param {ReturnType<typeof userEvent.setup>} operator - The interaction driver.
 * @param {string} value - The filter entry to type.
 * @returns {Promise<void>} Resolves once the second read has been issued.
 */
async function applyTypeFilter(
  operator: ReturnType<typeof userEvent.setup>,
  value: string,
): Promise<void> {
  await operator.type(screen.getByLabelText(collapse(REF_TYPE_LIST_LABELS.typeFilter)), value);
  await operator.keyboard('{Enter}');
  await waitFor(
    /**
     * Waits for the filtered read to have been issued.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(listTransactionTypes).toHaveBeenCalledTimes(2);
    },
  );
}

/**
 * Proves a filtered miss renders the verbatim refusal and never the abend sentence.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsAFilteredMissAsAnInputError(): Promise<void> {
  vi.mocked(listTransactionTypes)
    .mockResolvedValueOnce(STORED_PAGE)
    .mockRejectedValueOnce(refusal([entryFor(SERVICE_TYPE_CODE_FIELD)]));
  const operator = userEvent.setup();
  render(renderScreen());
  await waitFor(
    /**
     * Waits for the opening read to have delivered its row.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByText('PURCHASE')).toBeInTheDocument();
    },
  );

  await applyTypeFilter(operator, '99');

  /*
   * Assumptions: the sentence is expected TWICE -- once in the message band and once beneath the filter
   * control it marks -- because L1251-L1265 both raises the sentence and marks the supplied filter. A
   * single-element query would fail on the duplicate rather than on the behaviour.
   */
  expect(await screen.findAllByText(FILTER_REFUSAL)).toHaveLength(2);
  expect(screen.queryByText(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED)).not.toBeInTheDocument();
}

/**
 * Proves BOTH filters are marked when the service reports both as supplied.
 *
 * Assumptions: the sentence is expected three times -- the band plus one mark per filter -- and the count
 * is what distinguishes marking both from marking one. L1251-L1265 marks each SUPPLIED filter, so a
 * submission naming both must mark both.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksBothSuppliedFilters(): Promise<void> {
  vi.mocked(listTransactionTypes)
    .mockResolvedValueOnce(STORED_PAGE)
    .mockRejectedValueOnce(
      refusal([entryFor(SERVICE_TYPE_CODE_FIELD), entryFor(SERVICE_DESCRIPTION_FIELD)]),
    );
  const operator = userEvent.setup();
  render(renderScreen());
  await waitFor(
    /**
     * Waits for the opening read to have delivered its row.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByText('PURCHASE')).toBeInTheDocument();
    },
  );

  await operator.type(
    screen.getByLabelText(collapse(REF_TYPE_LIST_LABELS.descriptionFilter)),
    'NOTHING MATCHES',
  );
  await applyTypeFilter(operator, '99');

  expect(await screen.findAllByText(FILTER_REFUSAL)).toHaveLength(3);
  expect(screen.queryByText(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED)).not.toBeInTheDocument();
}

/**
 * Proves a genuine failure still reports the abend sentence, so the branch has not swallowed faults.
 *
 * ⚠️ Assumptions: this is the control for the two cases above. A predicate relaxed by treating every 400
 * as a filtered miss would satisfy both of them and would hide a malformed cursor, an over-long page and
 * a refused direction -- every other 400 a browse can receive. The document here carries a per-field
 * entry naming a field that is NOT a filter, which is the closest a real fault comes to the refusal.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function stillReportsAGenuineFailureAsAnAbend(): Promise<void> {
  vi.mocked(listTransactionTypes)
    .mockResolvedValueOnce(STORED_PAGE)
    .mockRejectedValueOnce(refusal([entryFor('cursor')]));
  const operator = userEvent.setup();
  render(renderScreen());
  await waitFor(
    /**
     * Waits for the opening read to have delivered its row.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByText('PURCHASE')).toBeInTheDocument();
    },
  );

  await applyTypeFilter(operator, '99');

  expect(await screen.findByText(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED)).toBeInTheDocument();
  expect(screen.queryByText(FILTER_REFUSAL)).not.toBeInTheDocument();
}

/**
 * Proves an UNFILTERED empty read keeps its own, differently-worded sentence.
 *
 * Assumptions: the two empty-result sentences are never merged. L1702-L1704 emits
 * `'No records found for this search condition.'` for an unfiltered first page while L1251-L1265 emits
 * `'No Records found for these filter conditions'` for a filtered miss; they differ in capitalisation and
 * in the trailing full stop as well as in trigger.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anUnfilteredEmptyReadKeepsItsOwnSentence(): Promise<void> {
  vi.mocked(listTransactionTypes).mockResolvedValue(EMPTY_PAGE);
  render(renderScreen());

  expect(await screen.findByText(LIST_STATUS.WS_MESG_NO_RECORDS_FOUND.text)).toBeInTheDocument();
  expect(screen.queryByText(FILTER_REFUSAL)).not.toBeInTheDocument();
  expect(screen.queryByText(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED)).not.toBeInTheDocument();
}

/**
 * Asserts the predicate recognises the refusal and nothing else.
 *
 * Assumptions: the four negative inputs are each a way the predicate could over-match -- a null document,
 * a non-400 status, a 400 attributing itself to no field, and a 400 attributing itself to a field that is
 * not a filter. Together they pin that only the service's own filtered-empty refusal is admitted.
 * @returns {void} Nothing; the case asserts.
 */
function recognisesOnlyTheFilteredEmptyRefusal(): void {
  expect(isFilteredEmptyRefusal(problem(400, [entryFor(SERVICE_TYPE_CODE_FIELD)]))).toBe(true);
  expect(isFilteredEmptyRefusal(problem(400, [entryFor(SERVICE_DESCRIPTION_FIELD)]))).toBe(true);

  expect(isFilteredEmptyRefusal(null)).toBe(false);
  expect(isFilteredEmptyRefusal(problem(500, [entryFor(SERVICE_TYPE_CODE_FIELD)]))).toBe(false);
  expect(isFilteredEmptyRefusal(problem(400, []))).toBe(false);
  expect(isFilteredEmptyRefusal(problem(400, [entryFor('cursor')]))).toBe(false);
}

/** Registers the filtered-empty cases. */
function filteredEmptyCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('reports a filtered miss as the reference input error', reportsAFilteredMissAsAnInputError);
  it('marks both filters when the service reports both', marksBothSuppliedFilters);
  it('still reports a genuine failure as an abend', stillReportsAGenuineFailureAsAnAbend);
  it('keeps the unfiltered empty sentence distinct', anUnfilteredEmptyReadKeepsItsOwnSentence);
}

describe('the transaction-type list reports a filtered miss, not an abend', filteredEmptyCases);

/** Registers the pure predicate case. */
function pureFilterCases(): void {
  it('recognises only the service refusal', recognisesOnlyTheFilteredEmptyRefusal);
}

describe('the filtered-empty refusal predicate', pureFilterCases);
