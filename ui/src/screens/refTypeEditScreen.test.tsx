/**
 * @file Proves the transaction-type maintenance screen transcribes `COTRTUPC`: its validation
 * sentences, its six-stage machine, which function keys each stage offers, and the three writes it
 * performs.
 *
 * Purpose
 * -------
 * `ui/src/screens/refTypeEdit/index.tsx` replaces
 * `app/app-transaction-type-db2/cbl/COTRTUPC.cbl`, which is the destination the sibling list screen's
 * PF2 and administrative option 6 both name and which nothing was mounted at. The reference cannot run
 * without a CICS runtime, so these cases are the only verification the screen receives.
 *
 * What the two forms of case prove
 * --------------------------------
 * Assumptions: the validation sentences and the stage-to-key table are asserted through the exported
 * pure functions, and the stage TRANSITIONS through a rendered tree. The pure cases pin what the
 * reference decides -- the composed sentences and the availability paragraph -- where a rendered case
 * could not tell a transcribed rule from a coincidence of the form; the rendered cases then prove the
 * keys reach those decisions and that each write is issued with what the glass shows.
 *
 * Assumptions: every expectation is the CONSTANT the catalog publishes rather than a retyped sentence,
 * for the reason the card screen tests record. The transport module is mocked so each case controls what
 * the service answers; the screen's own request shapes are then asserted against the spies.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the card screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../api/client';
import {
  createTransactionType,
  deleteTransactionType,
  getTransactionType,
  replaceTransactionType,
} from '../api/reference';
import type { TransactionType } from '../api/reference';
import type { ApiError } from '../api/types';
import {
  FIELD_VALIDATION_SUFFIXES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
} from '../messages/messages';
import { ADMIN_MENU_ROUTE } from '../routes/navigation';
import {
  REF_TYPE_EDIT_FIELD_LABELS,
  REF_TYPE_EDIT_ROUTE,
  REF_TYPE_NEW_SENTINEL,
  RefTypeEditScreen,
  TYPE_CODE_WIDTH,
  DESCRIPTION_WIDTH,
  stageKeyAvailability,
  validateDescription,
  validateTypeCode,
  writeFailureMessage,
} from './refTypeEdit';

/**
 * Builds the mocked surface of the reference transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The four transport functions this screen calls, each a fresh spy.
 */
function mockReferenceTransportModule(): Record<string, unknown> {
  return {
    getTransactionType: vi.fn(),
    createTransactionType: vi.fn(),
    replaceTransactionType: vi.fn(),
    deleteTransactionType: vi.fn(),
  };
}

vi.mock('../api/reference', mockReferenceTransportModule);

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: the two field labels carry INTERIOR padding, which is why this is needed here at all --
 * the mapset writes `'Transaction Type  :'` and `'Description       :'` so both colons land in the same
 * column of the character grid, and the screen renders them verbatim under Transformation Rule T8. The
 * DOM collapses those runs on display, so the expectation is collapsed too rather than the label being
 * trimmed at its declaration, which would make the test the source of truth instead of the mapset.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/** Sentences and prompts this program declares, from the single catalog that owns them. */
const EDIT_STATUS = STATUS_MESSAGES.COTRTUPC;

/** Text a probe route renders so an exit can be observed. */
const ARRIVED = 'ARRIVED';

/** One stored row, at the widths the mapset and the table definition both declare. */
const STORED: TransactionType = {
  typeCd: '05',
  description: 'PAYMENT REVERSAL',
  version: 3,
};

/**
 * Renders the screen at a concrete maintenance path with a probe at the exit destination.
 * @param {string} typeCd - Path segment the route parameter receives.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(typeCd: string): ReactElement {
  return (
    <MemoryRouter initialEntries={[`/reference/transaction-types/${typeCd}`]}>
      <Routes>
        <Route path={REF_TYPE_EDIT_ROUTE} element={<RefTypeEditScreen />} />
        <Route path={ADMIN_MENU_ROUTE} element={<div>{`${ARRIVED} ${ADMIN_MENU_ROUTE}`}</div>} />
      </Routes>
    </MemoryRouter>
  );
}

/** Answers the read with the stored row. */
function serveStoredRow(): void {
  vi.mocked(getTransactionType).mockResolvedValue(STORED);
}

/**
 * Answers the read with the service's not-found refusal.
 *
 * Assumptions: a real `ApiRequestError` rather than a bare object with a `status`, because that is what
 * the screen's own classification tests through `isApiRequestError` -- a lookalike would take the
 * fall-through branch and prove the opposite of what the case intends.
 * @returns {void} The spy is configured for the next read.
 */
function serveNotFound(): void {
  vi.mocked(getTransactionType).mockRejectedValue(notFoundFailure());
}

/**
 * Builds one normalised failure carrying a status and an optional service sentence.
 * @param {number} status - HTTP status the service answered with.
 * @param {string | null} message - Sentence the service sent, or `null` when it sent none.
 * @returns {ApiRequestError} The normalised failure the client would raise.
 */
function failureWith(status: number, message: string | null): ApiRequestError {
  /*
   * Assumptions: every member the shape declares is supplied rather than the object being widened with a
   * cast, because this document is what the screen reads its refusal sentence out of -- a partial stand-in
   * would let a case pass against a member the real client always sends.
   */
  const problem: ApiError = {
    code: 'TEST',
    secondaryCode: '',
    message,
    severity: 'WARNING',
    subsystem: 'RELATIONAL',
    status,
    correlationId: 'test-correlation-id',
    path: '/api/v1/reference/transaction-types/05',
    timestamp: '2025-01-01T00:00:00Z',
    fieldErrors: [],
    abend: null,
  };
  return new ApiRequestError('PROBLEM', status, problem, `test failure ${String(status)}`);
}

/**
 * Builds the not-found failure a missing key produces.
 * @returns {ApiRequestError} The normalised 404.
 */
function notFoundFailure(): ApiRequestError {
  return failureWith(404, null);
}

/** Restores the document between cases. */
function resetSpies(): void {
  vi.mocked(getTransactionType).mockReset();
  vi.mocked(createTransactionType).mockReset();
  vi.mocked(replaceTransactionType).mockReset();
  vi.mocked(deleteTransactionType).mockReset();
}

/**
 * Asserts each refusal sentence is composed from the catalogued label and the catalogued suffix.
 *
 * Assumptions: the expectations are BUILT the same way the screen builds them, from the two halves the
 * catalog owns, rather than written out as one string. That is the point: the reference composes these
 * sentences at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L866, L924, L943 and L961, so a test
 * holding the joined result would be a third spelling of a value neither half owns.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function composesEveryValidationSentence(): void {
  const label = PROGRAM_MESSAGES.COTRTUPC.TRAN_TYPE_CODE;
  expect(validateTypeCode('   ')).toBe(`${label}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`);
  expect(validateTypeCode('A1')).toBe(`${label}${FIELD_VALIDATION_SUFFIXES.MUST_BE_NUMERIC}`);
  expect(validateTypeCode('00')).toBe(`${label}${FIELD_VALIDATION_SUFFIXES.MUST_NOT_BE_ZERO}`);
  expect(validateTypeCode('05')).toBeNull();

  const descriptionLabel = SHARED_MESSAGES.TRANSACTION_DESC;
  expect(validateDescription('  ')).toBe(
    `${descriptionLabel}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`,
  );
  expect(validateDescription('BAD-VALUE!')).toBe(
    `${descriptionLabel}${FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY}`,
  );
  expect(validateDescription('PAYMENT REVERSAL 2')).toBeNull();
}

/**
 * Asserts the stage-to-key table is the reference's attribute paragraph.
 *
 * Assumptions: every stage is asserted rather than a representative few, because the paragraph decides
 * each one independently -- `3391-SETUP-PFKEY-ATTRS` at L1397-L1423 has a separate `EVALUATE` arm per
 * legend field -- so a table that happened to be right for two stages could be wrong for the rest.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function transcribesTheKeyAvailabilityParagraph(): void {
  expect(stageKeyAvailability('notFetched')).toEqual({
    enter: true,
    delete: false,
    save: false,
    cancel: false,
  });
  expect(stageKeyAvailability('showDetails')).toEqual({
    enter: true,
    delete: true,
    save: false,
    cancel: true,
  });
  expect(stageKeyAvailability('detailsNotFound')).toEqual({
    enter: true,
    delete: false,
    save: true,
    cancel: true,
  });
  expect(stageKeyAvailability('createNewRecord')).toEqual({
    enter: true,
    delete: false,
    save: false,
    cancel: true,
  });
  expect(stageKeyAvailability('changesOkNotConfirmed')).toEqual({
    enter: true,
    delete: false,
    save: true,
    cancel: true,
  });
  /*
   * Assumptions: this is the one stage in which Enter is DARKENED, which L1400-L1404 does only while a
   * delete is being confirmed -- so an operator cannot re-validate their way out of the confirmation.
   */
  expect(stageKeyAvailability('confirmDelete')).toEqual({
    enter: false,
    delete: true,
    save: false,
    cancel: true,
  });
}

/**
 * Asserts a version conflict is reported with the reference's own sentence and anything else with its
 * fallback.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function reportsAConflictWithTheReferenceSentence(): void {
  expect(writeFailureMessage(failureWith(409, 'service wording'))).toBe(
    EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text,
  );
  expect(writeFailureMessage(failureWith(500, null))).toBe(EDIT_STATUS.INFORM_FAILURE.text);
  expect(writeFailureMessage(new Error('not from the client'))).toBe(
    EDIT_STATUS.INFORM_FAILURE.text,
  );
}

/**
 * Asserts entering at the add sentinel prompts for a key and issues no read.
 *
 * Assumptions: the absence of a read is the assertion that matters. The sentinel is a route segment
 * rather than a key, so a screen that passed it through would ask the service for a transaction type
 * named `new` -- which is not a two-character code and could only ever answer not-found.
 * @returns {Promise<void>} Resolves once the prompt is on the glass.
 */
async function promptsForAKeyAtTheAddSentinel(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));

  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();
  expect(vi.mocked(getTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the two controls carry the widths the mapset and the table definition both declare.
 * @returns {Promise<void>} Resolves once both controls have been measured.
 */
async function carriesTheDeclaredFieldWidths(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));

  const key = await screen.findByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.typeCode));
  expect(key).toHaveAttribute('maxlength', String(TYPE_CODE_WIDTH));
  const description = screen.getByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.description));
  expect(description).toHaveAttribute('maxlength', String(DESCRIPTION_WIDTH));
}

/**
 * Asserts a read that finds a row reports the reference's found sentence.
 * @returns {Promise<void>} Resolves once the sentence is on the glass.
 */
async function reportsAFoundRow(): Promise<void> {
  serveStoredRow();
  render(renderScreen(STORED.typeCd));

  expect(await screen.findByText(EDIT_STATUS.FOUND_TRANTYPE_DATA.text)).toBeInTheDocument();
  expect(vi.mocked(getTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
  expect(screen.getByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.description))).toHaveValue(
    STORED.description,
  );
}

/**
 * Asserts an unchanged description is refused before any write, with the reference's own sentence.
 *
 * Assumptions: this ordering is the reference's. `1000-PROCESS-INPUTS` sets
 * `'No change detected with respect to values fetched.'` and leaves the paragraph before any edit runs,
 * so it is a validation outcome rather than something only the service could answer -- which is why the
 * case also asserts that no write was issued.
 * @returns {Promise<void>} Resolves once the sentence is on the glass.
 */
async function refusesAnUnchangedDescription(): Promise<void> {
  serveStoredRow();
  render(renderScreen(STORED.typeCd));
  expect(await screen.findByText(EDIT_STATUS.FOUND_TRANTYPE_DATA.text)).toBeInTheDocument();

  await userEvent.keyboard('{Enter}');

  expect(await screen.findByText(EDIT_STATUS.NO_CHANGES_DETECTED.text)).toBeInTheDocument();
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts a changed description is validated, then saved with the version the service issued.
 *
 * Assumptions: the issued VERSION is asserted, not merely that a write happened. It is what makes the
 * reference's own before-image comparison expressible natively -- the service refuses a stale version
 * with a conflict -- so a write that omitted it would silently overwrite a concurrent change.
 * @returns {Promise<void>} Resolves once the success sentence is on the glass.
 */
async function savesAValidatedChangeWithTheIssuedVersion(): Promise<void> {
  serveStoredRow();
  const changed = 'PAYMENT REVERSAL 2';
  vi.mocked(replaceTransactionType).mockResolvedValue({
    ...STORED,
    description: changed,
    version: STORED.version + 1,
  });
  render(renderScreen(STORED.typeCd));
  expect(await screen.findByText(EDIT_STATUS.FOUND_TRANTYPE_DATA.text)).toBeInTheDocument();

  const description = screen.getByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.description));
  await userEvent.clear(description);
  await userEvent.type(description, changed);
  await userEvent.keyboard('{Enter}');

  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text)).toBeInTheDocument();
  expect(vi.mocked(replaceTransactionType)).not.toHaveBeenCalled();

  await userEvent.keyboard('{F5}');

  expect(await screen.findByText(EDIT_STATUS.CONFIRM_UPDATE_SUCCESS.text)).toBeInTheDocument();
  expect(vi.mocked(replaceTransactionType)).toHaveBeenCalledWith(STORED.typeCd, {
    description: changed,
    version: STORED.version,
  });
}

/**
 * Asserts a key naming no row reaches the add path and creates the row.
 * @returns {Promise<void>} Resolves once the success sentence is on the glass.
 */
async function addsARowThroughTheNotFoundStage(): Promise<void> {
  serveNotFound();
  const added: TransactionType = { typeCd: '07', description: 'NEW TYPE', version: 1 };
  vi.mocked(createTransactionType).mockResolvedValue(added);
  render(renderScreen(added.typeCd));

  expect(await screen.findByText(EDIT_STATUS.WS_RECORD_NOT_FOUND.text)).toBeInTheDocument();

  await userEvent.keyboard('{F5}');
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_NEWDATA.text)).toBeInTheDocument();

  await userEvent.type(
    screen.getByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.description)),
    added.description,
  );
  await userEvent.keyboard('{Enter}');
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text)).toBeInTheDocument();

  await userEvent.keyboard('{F5}');
  expect(await screen.findByText(EDIT_STATUS.CONFIRM_UPDATE_SUCCESS.text)).toBeInTheDocument();
  expect(vi.mocked(createTransactionType)).toHaveBeenCalledWith({
    typeCd: added.typeCd,
    description: added.description,
  });
}

/**
 * Asserts a delete takes two presses of the same key and only the second one writes.
 *
 * Assumptions: both presses go to the SAME key, because the reference binds them to one -- PF4 with the
 * row shown asks, PF4 with the confirmation pending deletes. Asserting that the first press issued no
 * write is what distinguishes the transcribed confirmation from a delete that merely happened to work.
 * @returns {Promise<void>} Resolves once the delete has been reported.
 */
async function deletesOnlyOnTheSecondConfirmation(): Promise<void> {
  serveStoredRow();
  vi.mocked(deleteTransactionType).mockResolvedValue(undefined);
  render(renderScreen(STORED.typeCd));
  expect(await screen.findByText(EDIT_STATUS.FOUND_TRANTYPE_DATA.text)).toBeInTheDocument();

  await userEvent.keyboard('{F4}');
  expect(await screen.findByText(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text)).toBeInTheDocument();
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();

  await userEvent.keyboard('{F4}');
  expect(await screen.findByText(EDIT_STATUS.CONFIRM_DELETE_SUCCESS.text)).toBeInTheDocument();
  expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
}

/**
 * Asserts a refused delete reports the service's own sentence when it carries one.
 *
 * Assumptions: the sentence is the service's rather than the reference's for this one case, because the
 * refusal is the `ON DELETE RESTRICT` foreign key `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` L6-L7
 * declares -- and the service's sentence is the one that names the child records the operator has to
 * remove first, which the reference's generic `'Delete of record failed'` does not.
 * @returns {Promise<void>} Resolves once the refusal is on the glass.
 */
async function reportsARestrictedDeleteWithTheServiceSentence(): Promise<void> {
  const reported = 'Transaction type has categories referencing it';
  serveStoredRow();
  vi.mocked(deleteTransactionType).mockRejectedValue(failureWith(409, reported));
  render(renderScreen(STORED.typeCd));
  expect(await screen.findByText(EDIT_STATUS.FOUND_TRANTYPE_DATA.text)).toBeInTheDocument();

  await userEvent.keyboard('{F4}');
  await userEvent.keyboard('{F4}');

  expect(await screen.findByText(reported)).toBeInTheDocument();
}

/**
 * Asserts a cancel from the delete confirmation reports the cancellation and keeps the row.
 * @returns {Promise<void>} Resolves once the cancellation is on the glass.
 */
async function cancelsAPendingDelete(): Promise<void> {
  serveStoredRow();
  render(renderScreen(STORED.typeCd));
  expect(await screen.findByText(EDIT_STATUS.FOUND_TRANTYPE_DATA.text)).toBeInTheDocument();

  await userEvent.keyboard('{F4}');
  expect(await screen.findByText(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text)).toBeInTheDocument();
  await userEvent.keyboard('{F12}');

  expect(await screen.findByText(EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text)).toBeInTheDocument();
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();
}

/**
 * Asserts the sixth function key is unbound and reported, not silently accepted.
 *
 * Assumptions: this is asserted because the mapset PAINTS an `F6=Add` legend the program never tests --
 * `FKEY06` is declared non-display at `COTRTUP.bms` L127-L134 and no arm brightens it -- so the legend is
 * dead text. A screen that bound it would offer an action the reference has no code for; this case pins
 * the absence rather than leaving it to be re-derived.
 * @returns {Promise<void>} Resolves once the invalid-key sentence is on the glass.
 */
async function reportsTheUnboundSixthKey(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();

  await userEvent.keyboard('{F6}');

  expect(await screen.findByText(EDIT_STATUS.WS_INVALID_KEY.text.trim())).toBeInTheDocument();
}

/**
 * Asserts the third function key leaves for the administrative menu.
 *
 * Assumptions: the destination is the ADMINISTRATIVE menu rather than the main menu, which
 * `COTRTUPC.cbl` L429 fixes: with no calling program recorded it transfers to `LIT-ADMINTRANID` and
 * `LIT-ADMINPGM`.
 * @returns {Promise<void>} Resolves once the destination has reported its arrival.
 */
async function exitsToTheAdministrativeMenu(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();

  await userEvent.keyboard('{F3}');

  expect(await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeInTheDocument();
}

/**
 * Asserts a refused key marks the control and paints the composed sentence.
 * @returns {Promise<void>} Resolves once the sentence is on the glass.
 */
async function marksTheRefusedKeyControl(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();

  await userEvent.keyboard('{Enter}');

  const refusal = `${PROGRAM_MESSAGES.COTRTUPC.TRAN_TYPE_CODE}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;
  expect(await screen.findByText(refusal)).toBeInTheDocument();
  /*
   * Assumptions: the literal marker is asserted as well as the sentence, because the baseline's
   * templated highlight writes BOTH -- `app/cpy/CSSETATY.cpy` L23-L25 moves an asterisk into a field
   * that is blank, in addition to the colour it sets at L18-L20.
   */
  await waitFor(
    /**
     * Waits for the blank marker to appear beside the refused control.
     * @returns {void} Nothing; failure is reported by the expectation.
     */
    () => {
      expect(screen.getByText('*')).toBeInTheDocument();
    },
  );
  expect(vi.mocked(getTransactionType)).not.toHaveBeenCalled();
}

/** Registers the maintenance-screen cases. */
function refTypeEditCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('composes every validation sentence from the catalog', composesEveryValidationSentence);
  it('transcribes the key availability paragraph', transcribesTheKeyAvailabilityParagraph);
  it('reports a conflict with the reference sentence', reportsAConflictWithTheReferenceSentence);
  it('prompts for a key at the add sentinel', promptsForAKeyAtTheAddSentinel);
  it('carries the declared field widths', carriesTheDeclaredFieldWidths);
  it('reports a found row', reportsAFoundRow);
  it('refuses an unchanged description', refusesAnUnchangedDescription);
  it('saves a validated change with the issued version', savesAValidatedChangeWithTheIssuedVersion);
  it('adds a row through the not-found stage', addsARowThroughTheNotFoundStage);
  it('deletes only on the second confirmation', deletesOnlyOnTheSecondConfirmation);
  it(
    'reports a restricted delete with the service sentence',
    reportsARestrictedDeleteWithTheServiceSentence,
  );
  it('cancels a pending delete', cancelsAPendingDelete);
  it('reports the unbound sixth key', reportsTheUnboundSixthKey);
  it('exits to the administrative menu', exitsToTheAdministrativeMenu);
  it('marks the refused key control', marksTheRefusedKeyControl);
}

describe('transaction-type maintenance screen', refTypeEditCases);
