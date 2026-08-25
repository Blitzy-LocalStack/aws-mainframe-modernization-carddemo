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
import { matchPath, MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../api/client';
import { AppShell } from '../layout/AppShell';
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
import { ADMIN_MENU_ROUTE, REFERENCE_TYPE_LIST_ROUTE } from '../routes/navigation';
import {
  REF_TYPE_EDIT_FIELD_LABELS,
  REF_TYPE_EDIT_KEY_LABELS,
  REF_TYPE_EDIT_ROUTE,
  REF_TYPE_NEW_SENTINEL,
  RefTypeEditScreen,
  TYPE_CODE_WIDTH,
  DESCRIPTION_WIDTH,
  refTypeEditKeyMatrix,
  stageKeyAvailability,
  validateDescription,
  validateTypeCode,
  writeFailureMessage,
} from './refTypeEdit';
import type { RefTypeEditMode } from './refTypeEdit';

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

/**
 * Every mode the screen declares, so a claim about "all modes" is checked against all of them.
 *
 * Assumptions: the sixteen names are the sixteen `88`-level states at `COTRTUPC.cbl` L298-L327, listed
 * here rather than imported as a runtime array because the screen models them as a TYPE -- a union has no
 * runtime members to enumerate, and adding an array to production code purely for a test would put a
 * second list of the states beside the type.
 */
const REF_TYPE_EDIT_MODES: readonly RefTypeEditMode[] = [
  'notFetched',
  'invalidSearchKeys',
  'detailsNotFound',
  'showDetails',
  'createNewRecord',
  'reviewNewRecord',
  'confirmDelete',
  'startDelete',
  'deleteDone',
  'deleteFailed',
  'changesNotOk',
  'changesOkNotConfirmed',
  'changesOkayedLockError',
  'changesOkayedButFailed',
  'changesOkayedAndDone',
  'changesBackedOut',
];

/** One stored row, at the widths the mapset and the table definition both declare. */
const STORED: TransactionType = {
  typeCd: '05',
  description: 'PAYMENT REVERSAL',
  version: 3,
};

/**
 * Renders the screen at a concrete maintenance path with probes at both exit destinations.
 *
 * Assumptions: the caller an entering transition names is optional, because both arms of the exit
 * resolution are reachable in the delivered tree and each needs one arrival to exercise it. The list
 * screen's add transfer names this screen's caller (`ui/src/screens/refTypeList/index.tsx`), while
 * administrative option 6 names none because the menu IS the fallback -- so `undefined` here is not a
 * degenerate case but the second live entry path.
 *
 * Assumptions: omitting the caller produces a router entry with NO state member at all rather than one
 * carrying an empty object, so the fallback arm is exercised against the arrival it actually answers:
 * a menu transfer, a typed address, a bookmark or a reload.
 * @param {string} typeCd - Path segment the route parameter receives.
 * @param {string} [origin] - Route the entering transition named as this screen's caller, if any.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(typeCd: string, origin?: string): ReactElement {
  const pathname = `/reference/transaction-types/${typeCd}`;

  return (
    <MemoryRouter
      initialEntries={[origin === undefined ? pathname : { pathname, state: { from: origin } }]}
    >
      {/*
        WHY : Assumptions: the screen is rendered INSIDE `AppShell`, because it composes none of the
              three frame zones itself: it delegates its title band, its row-23 message line and its
              row-24 legend to the one shell `ui/src/router.tsx` mounts as a layout route. Rendered
              bare it would paint no legend and no band, and every query for either would fail on a
              screen that is in fact correct. The children form is used rather than a second route
              level because `AppShell` renders `children ?? <Outlet />`, so both forms paint the same
              frame and this one needs no extra nesting.
      */}
      <AppShell>
        <Routes>
          <Route path={REF_TYPE_EDIT_ROUTE} element={<RefTypeEditScreen />} />
          <Route path={ADMIN_MENU_ROUTE} element={<div>{`${ARRIVED} ${ADMIN_MENU_ROUTE}`}</div>} />
          <Route
            path={REFERENCE_TYPE_LIST_ROUTE}
            element={<div>{`${ARRIVED} ${REFERENCE_TYPE_LIST_ROUTE}`}</div>}
          />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/**
 * Asserts the route pattern this screen publishes spells its parameter `cd`.
 *
 * Refactoring Rationale: the parameter was spelled `typeCd`, which is the TRANSPORT property name --
 * `ui/src/api/types.ts` declares `TransactionType.typeCd` -- while `ui/src/api/reference.ts` L62
 * described the route as `/reference/transaction-types/:cd`. Two names for one value in one URL is what
 * the correction removes, and the spelling is load-bearing rather than cosmetic: `useParams` resolves an
 * unmatched name to `undefined` with no diagnostic, so a route and a screen that disagree leave this
 * screen prompting for a key the address already carried, with nothing failing to say so.
 * @returns {void} Nothing; failure is reported by the expectation.
 */
function theRouteParameterIsSpelledCd(): void {
  expect(REF_TYPE_EDIT_ROUTE).toBe('/reference/transaction-types/:cd');

  const matched = matchPath(REF_TYPE_EDIT_ROUTE, `/reference/transaction-types/${STORED.typeCd}`);
  expect(matched?.params.cd).toBe(STORED.typeCd);
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

/**
 * Waits for a read to have landed, anchored on the row it put on the glass.
 *
 * ⚠ Refactoring Rationale: this replaces an anchor on `'Selected transaction type shown above'`, which
 * the screen no longer paints because the reference never painted it. `88 FOUND-TRANTYPE-DATA` is
 * declared at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L145-L146 and has NO set-site: the two
 * `SET` statements that look like its own, at L528 and L1488, name `FOUND-TRANTYPE-IN-TABLE` -- a
 * different flag declared at L127 recording that the SQL found a row. `3250-SETUP-INFOMSG` reaches
 * `WHEN TTUP-SHOW-DETAILS` at L1221, which carries no statements and therefore falls through to L1225
 * and sets the SEARCH-KEY prompt instead. Anchoring on the fetched description proves the read landed
 * without asserting a sentence the baseline cannot produce.
 * @returns {Promise<void>} Resolves once the stored description is on the glass.
 */
async function awaitStoredRow(): Promise<void> {
  expect(await screen.findByDisplayValue(STORED.description)).toBeInTheDocument();
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

  await awaitStoredRow();
  expect(vi.mocked(getTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
  expect(screen.getByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.description))).toHaveValue(
    STORED.description,
  );
  /*
   * Assumptions: the row-22 prompt after a successful read is the SEARCH-KEY prompt, for the reason
   * recorded at awaitStoredRow -- the shown-row arm of `3250-SETUP-INFOMSG` falls through to it. This is
   * asserted positively so the fall-through is pinned rather than merely not contradicted.
   */
  expect(screen.getByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();
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
  await awaitStoredRow();

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
  await awaitStoredRow();

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
 * Asserts a refused save withdraws the save key and that the next Enter restarts at key entry.
 *
 * ⚠ WHY : Purpose: this case exists to PIN behaviour a review read as a defect, because the reference
 *       prescribes all three parts of it and a well-meant repair would diverge from the baseline.
 *
 *       The review's reading was that a refused save on this screen "removes `F5=Save`, disables the
 *       input, and pressing `ENTER=Process` to recover silently wipes the staged edit". Each part is
 *       transcribed:
 *
 *       - The save key goes because `0001-CHECK-PFKEYS` at
 *         `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L588-L593 accepts `CCARD-AID-PFK05` for
 *         `TTUP-CHANGES-OK-NOT-CONFIRMED`, `TTUP-DETAILS-NOT-FOUND` and `TTUP-DELETE-IN-PROGRESS` and
 *         for nothing else, and `3391-SETUP-PFKEY-ATTRS` L1411-L1414 brightens its legend for the first
 *         two. A refused write is `TTUP-CHANGES-OKAYED-BUT-FAILED`, which is none of them.
 *       - The staged edit goes because L405-L419 evaluates `WHEN TTUP-CHANGES-FAILED` -- the `88` over
 *         `'L'` and `'F'` -- and sets `CDEMO-PGM-ENTER` and `TTUP-DETAILS-NOT-FETCHED`, which makes the
 *         NEXT `EVALUATE`'s arm at L469-L470 match and run `INITIALIZE WS-THIS-PROGCOMMAREA
 *         WS-MISC-STORAGE CDEMO-ACCT-ID` at L471-L473. `WS-THIS-PROGCOMMAREA` is where
 *         `TTUP-OLD-DETAILS` and `TTUP-NEW-DETAILS` live, so the before-image and the operator's typed
 *         replacement are both blanked before the map is re-sent.
 *       - It is not "silent" in any sense the terminal was: that same `INITIALIZE` blanks
 *         `WS-RETURN-MSG` too, and `3000-SEND-MAP` then paints the search-key prompt -- exactly what
 *         this screen paints.
 *
 *       Assumptions: the refusal reported before the reset is the SERVICE's own sentence, so the case
 *       asserts it as well. A repair that swallowed it would satisfy the reset half alone.
 *
 *       Trade-offs: the operator does lose a typed description to a refused save and has to retype it.
 *       That is the baseline's cost and it is preserved deliberately under rule T9: keeping the staged
 *       value would put this screen in a state the reference has no mode for, and the mode byte is what
 *       every key decision on the screen reads.
 * @returns {Promise<void>} Resolves once the refusal and the following reset have been asserted.
 */
async function restartsAtKeyEntryAfterARefusedSave(): Promise<void> {
  serveStoredRow();
  /*
   * Assumptions: the sentence asserted is the CATALOGUED one for a refused replace, not the service's
   * own text. `writeFailureMessage` answers a non-conflict, non-lock refusal with
   * `TABLE_UPDATE_FAILED`, which is the reference's `'Update of Transaction type failed'` at
   * `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1583-L1584, and the service's diagnostic is
   * deliberately withheld -- so the case asserts the sentence the screen is contracted to paint rather
   * than the one the transport happened to carry.
   */
  vi.mocked(replaceTransactionType).mockRejectedValue(failureWith(500, 'db diagnostic withheld'));
  const refusal = EDIT_STATUS.TABLE_UPDATE_FAILED.text;
  render(renderScreen(STORED.typeCd));
  await awaitStoredRow();

  const changed = 'PAYMENT REVERSAL 2';
  const description = screen.getByLabelText(collapse(REF_TYPE_EDIT_FIELD_LABELS.description));
  await userEvent.clear(description);
  await userEvent.type(description, changed);
  await userEvent.keyboard('{Enter}');
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text)).toBeInTheDocument();

  await userEvent.keyboard('{F5}');

  expect(await screen.findByText(refusal)).toBeInTheDocument();
  expect(await screen.findByText(EDIT_STATUS.INFORM_FAILURE.text)).toBeInTheDocument();
  expect(stageKeyAvailability('changesOkayedButFailed').save).toBe(false);

  await userEvent.keyboard('{Enter}');

  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();
  expect(screen.queryByDisplayValue(changed)).toBeNull();
  expect(vi.mocked(replaceTransactionType)).toHaveBeenCalledTimes(1);
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
  await awaitStoredRow();

  await userEvent.keyboard('{F4}');
  // WHY : Assumptions: the prompt is COUNTED rather than located, because while a delete is armed the
  //       catalogued sentence is on the glass TWICE -- on the mapset's row-22 band, where
  //       `COTRTUPC.cbl` L151-L152 puts it, and as the title of the confirmation, which stays open for
  //       as long as the request is armed. Two is therefore the assertion: it proves the dialogue is up
  //       and that it reuses the reference's own wording rather than inventing a second one.
  expect(await screen.findAllByText(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text)).toHaveLength(2);
  expect(vi.mocked(deleteTransactionType)).not.toHaveBeenCalled();

  await userEvent.keyboard('{F4}');
  expect(await screen.findByText(EDIT_STATUS.CONFIRM_DELETE_SUCCESS.text)).toBeInTheDocument();
  expect(vi.mocked(deleteTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
}

/**
 * Asserts a refused delete reports the baseline's own child-records sentence.
 *
 * ⚠ Refactoring Rationale: the expectation is the CATALOGUED sentence, not the service's wording. It used
 * to be the service's, which looked like the more informative choice and is the wrong one: the refusal is
 * the `ON DELETE RESTRICT` foreign key `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` L6-L7 declares, the
 * baseline reports it at `COTRTUPC.cbl` L1638-L1641 as
 * `'Please delete associated child records first:'`, and `ui/src/messages/messages.ts` registers exactly
 * that string as the replacement for that site in `REDACTED_DIAGNOSTICS`. Rendering the service's text
 * instead would let a message the target composes replace one Transformation Rule T8 carries verbatim,
 * and would put whatever diagnostic the service chose to send in front of an operator.
 *
 * Assumptions: the service DOES send a sentence in this case and it is deliberately not rendered, which
 * is why the case supplies one -- an expectation against a null-message failure would pass without
 * proving the screen prefers the catalogued string.
 * @returns {Promise<void>} Resolves once the refusal is on the glass.
 */
async function reportsARestrictedDeleteWithTheBaselineSentence(): Promise<void> {
  const reported = 'Transaction type has categories referencing it';
  serveStoredRow();
  vi.mocked(deleteTransactionType).mockRejectedValue(failureWith(409, reported));
  render(renderScreen(STORED.typeCd));
  await awaitStoredRow();

  await userEvent.keyboard('{F4}');
  await userEvent.keyboard('{F4}');

  expect(
    await screen.findByText(SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST),
  ).toBeInTheDocument();
  expect(screen.queryByText(reported)).not.toBeInTheDocument();
}

/**
 * Asserts the dead `F6=Add` legend is rendered and permanently disabled.
 *
 * ⚠ Assumptions: the mapset PAINTS five function-key legends and the program implements four. `FKEY06`
 * is declared `ATTRB=(ASKIP,DRK) ... INITIAL='F6=Add'` at `COTRTUP.bms` L126-L130, and grepping the
 * whole 1702-line program for `FKEY06`, `PFK06` and `F6=` returns zero matches -- so no arm brightens it
 * and no arm dispatches it. Both halves are asserted here because getting either wrong is the most likely
 * way this screen fails a parity review: dropping the label loses vocabulary the mapset declares, and
 * enabling the control invents an action, while the real add path is PF5 from the not-found stage.
 * @returns {Promise<void>} Resolves once the legend has been measured.
 */
async function paintsTheSixthLegendWithoutBindingIt(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();

  const sixth = screen.getByRole('button', { name: REF_TYPE_EDIT_KEY_LABELS.PFK06 });
  expect(sixth).toBeDisabled();
  expect(REF_TYPE_EDIT_KEY_LABELS.PFK06).toBe('F6=Add');
  /*
   * Assumptions: every mode is asserted rather than the initial one alone, because "permanently" is the
   * claim -- a legend disabled only where it happens to be tested would leave the other fifteen modes to
   * be re-derived by the next reader.
   */
  for (const mode of REF_TYPE_EDIT_MODES) {
    expect(refTypeEditKeyMatrix(mode).PFK06.accepted, `PF6 must stay unbound in ${mode}`).toBe(
      false,
    );
  }
}

/**
 * Asserts a cancel from the delete confirmation reports the cancellation and keeps the row.
 * @returns {Promise<void>} Resolves once the cancellation is on the glass.
 */
async function cancelsAPendingDelete(): Promise<void> {
  serveStoredRow();
  render(renderScreen(STORED.typeCd));
  await awaitStoredRow();

  await userEvent.keyboard('{F4}');
  // WHY : Assumptions: the prompt is COUNTED rather than located, because while a delete is armed the
  //       catalogued sentence is on the glass TWICE -- on the mapset's row-22 band, where
  //       `COTRTUPC.cbl` L151-L152 puts it, and as the title of the confirmation, which stays open for
  //       as long as the request is armed. Two is therefore the assertion: it proves the dialogue is up
  //       and that it reuses the reference's own wording rather than inventing a second one.
  expect(await screen.findAllByText(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text)).toHaveLength(2);
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

  /*
   * ⚠ Assumptions: the sentence is `'Invalid key pressed'` -- nineteen characters, LOWER-case k, no
   * trailing space -- and NOT `'Invalid Key pressed. '`. Both are declared, at L193-L194 and L171-L172
   * respectively, and only the first is live: the sole `SET` for the second sits inside the commented-out
   * block at L611-L616. The catalog keeps them apart, so this expectation names the live one and needs no
   * trimming to match.
   */
  expect(await screen.findByText(EDIT_STATUS.WS_INVALID_KEY_PRESSED.text)).toBeInTheDocument();
}

/**
 * Asserts the third function key leaves for the administrative menu when no caller was named.
 *
 * Assumptions: the destination is the ADMINISTRATIVE menu rather than the main menu, which
 * `COTRTUPC.cbl` L429 fixes: with no calling program recorded it transfers to `LIT-ADMINTRANID` and
 * `LIT-ADMINPGM`.
 *
 * Assumptions: this case now states the FALLBACK arm specifically, and its companion below states the
 * other. It was the only exit case while the list screen handed over no caller, and it would still
 * pass against a screen that ignored a named caller entirely -- which is why the pair exists rather
 * than this case alone.
 * @returns {Promise<void>} Resolves once the destination has reported its arrival.
 */
async function exitsToTheAdministrativeMenu(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL));
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();

  await userEvent.keyboard('{F3}');

  expect(await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE}`)).toBeInTheDocument();
}

/**
 * Asserts the third function key returns to the list screen when the list screen entered it.
 *
 * ⚠️ Purpose: this arm was unreachable. The exit resolution has always preferred a named caller, but
 * the list screen's add transfer handed none over, so an operator who pressed F2 there and F3 here
 * landed on the administrative menu -- losing the grid, the filter and the page position -- while the
 * reference returns them to the list. `COTRTUPC.cbl` L466-L468 tests `CDEMO-FROM-PROGRAM` against
 * `LIT-ADMINPGM` (`'COADM01C'`, L209-L210) and `LIT-LISTTPGM` (`'COTRTLIC'`, L217-L218), so the
 * program recognises both callers by name and this screen now honours both.
 *
 * Assumptions: the origin is supplied as router state, which is the carrier the list screen uses, and
 * it is the constant from `ui/src/routes/navigation.ts` rather than a literal -- an origin outside
 * that module's closed route set is refused by the screen, so the two have to be the same string.
 * @returns {Promise<void>} Resolves once the destination has reported its arrival.
 */
async function exitsToTheListScreenThatEnteredIt(): Promise<void> {
  render(renderScreen(REF_TYPE_NEW_SENTINEL, REFERENCE_TYPE_LIST_ROUTE));
  expect(await screen.findByText(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text)).toBeInTheDocument();

  await userEvent.keyboard('{F3}');

  expect(await screen.findByText(`${ARRIVED} ${REFERENCE_TYPE_LIST_ROUTE}`)).toBeInTheDocument();
}

/**
 * Asserts the key this screen reads is the route parameter spelled `cd`.
 *
 * ⚠️ Purpose: AAP section 0.4.1.4 fixes this screen's route as
 * `/reference/transaction-types/:cd`, and the parameter was declared and read as `typeCd`. React
 * Router resolves a parameter by NAME, and `useParams` returns `undefined` for a name the pattern does
 * not carry with no diagnostic of any kind -- so under the AAP's pattern the screen would have
 * prompted for a key its own address already supplied, on every arrival, silently.
 *
 * Assumptions: the pattern is written out as a LITERAL here rather than taken from
 * `REF_TYPE_EDIT_ROUTE`, which every other case in this file mounts. Reusing the exported constant
 * makes the pattern and the reader agree by construction, so a case built that way stays green under
 * any spelling and cannot state this property at all; the literal is what pins the spelling to the one
 * the AAP names.
 * @returns {Promise<void>} Resolves once the read has been observed.
 */
async function readsItsKeyFromTheCdRouteParameter(): Promise<void> {
  serveStoredRow();

  render(
    <MemoryRouter initialEntries={[`/reference/transaction-types/${STORED.typeCd}`]}>
      <AppShell>
        <Routes>
          <Route path="/reference/transaction-types/:cd" element={<RefTypeEditScreen />} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  );

  await waitFor(
    /**
     * Waits for the read the route parameter drives.
     * @returns {void} Nothing; failure is reported by the expectation.
     */
    () => {
      expect(vi.mocked(getTransactionType)).toHaveBeenCalledWith(STORED.typeCd);
    },
  );
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

  it('reads its key from the cd route parameter', readsItsKeyFromTheCdRouteParameter);
  it('exits to the list screen that entered it', exitsToTheListScreenThatEnteredIt);
  it('composes every validation sentence from the catalog', composesEveryValidationSentence);
  it('transcribes the key availability paragraph', transcribesTheKeyAvailabilityParagraph);
  it('reports a conflict with the reference sentence', reportsAConflictWithTheReferenceSentence);
  it('prompts for a key at the add sentinel', promptsForAKeyAtTheAddSentinel);
  it('carries the declared field widths', carriesTheDeclaredFieldWidths);
  it('reports a found row', reportsAFoundRow);
  it('refuses an unchanged description', refusesAnUnchangedDescription);
  it('saves a validated change with the issued version', savesAValidatedChangeWithTheIssuedVersion);
  it('restarts at key entry after a refused save', restartsAtKeyEntryAfterARefusedSave);
  it('adds a row through the not-found stage', addsARowThroughTheNotFoundStage);
  it('deletes only on the second confirmation', deletesOnlyOnTheSecondConfirmation);
  it(
    'reports a restricted delete with the baseline sentence',
    reportsARestrictedDeleteWithTheBaselineSentence,
  );
  it('paints the sixth legend without binding it', paintsTheSixthLegendWithoutBindingIt);
  it('cancels a pending delete', cancelsAPendingDelete);
  it('reports the unbound sixth key', reportsTheUnboundSixthKey);
  it('exits to the administrative menu', exitsToTheAdministrativeMenu);
  it('marks the refused key control', marksTheRefusedKeyControl);
  it('spells its route parameter cd', theRouteParameterIsSpelledCd);
}

describe('transaction-type maintenance screen', refTypeEditCases);
