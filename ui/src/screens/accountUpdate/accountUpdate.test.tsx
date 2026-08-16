/**
 * @file Component tests for the account update screen in `ui/src/screens/accountUpdate/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the properties a review found this screen had lost or invented across its two turns: that the
 * Enter turn validates through the service before it announces validation, that the information and
 * message channels stand independently, that the advertised save key and the pointer control reach one
 * confirmation and one write, that the blank marker is a decoration rather than data, that typed
 * characters survive, that the two protected identifiers are shown as captions and cleared from state
 * once a submission settles, and that every control a refusal can name is reachable and described.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, matching
 * `ui/src/screens/accountView/accountView.test.tsx`. These cases are about what the SCREEN does with an
 * outcome, so the shortest honest seam is the function the screen calls.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for
 * the reason the sibling suites record: `ui/eslint.config.js` selects a function expression in every
 * position so an inline callback owes its own JSDoc block, and Prettier moves a block comment that
 * follows an argument comma onto the preceding literal, detaching it from what it documents.
 */

import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { readAccountView, updateAccount, validateAccountUpdate } from '../../api/accounts';
import type { AccountViewResponse } from '../../api/accounts';
import { UNPOPULATED_CUSTOMER } from '../accountView/index';
import { ApiRequestError } from '../../api/client';
import type {
  AccountUpdateResponse,
  AccountUpdateValidationResponse,
  ApiError,
  FieldError,
} from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import { fieldErrorId } from '../../layout/fieldHelp';
import { STATUS_MESSAGES } from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';
import { FIELD_ERROR_TOKENS } from '../../theme/tokens';
import {
  ACCOUNT_UPDATE_KEY_LABELS,
  ADDRESS_LINE_2_NAME,
  AccountUpdateScreen,
  fieldDomId,
  governmentIdentifierCaption,
  nationalIdentifierCaption,
} from './index';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone
 * at registration time.
 * @returns {Record<string, unknown>} The transport functions this screen can reach, each a fresh spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    readAccountView: vi.fn(),
    updateAccount: vi.fn(),
    validateAccountUpdate: vi.fn(),
    listAccountCardCrossReferences: vi.fn(),
    /**
     * Reports whether a failure is the concurrency refusal, as the real module's predicate does.
     * @param {unknown} failure - The rejection under classification.
     * @returns {boolean} `true` when the refusal carries the conflict status.
     */
    isConflictFailure: (failure: unknown): boolean =>
      failure instanceof ApiRequestError && failure.status === CONFLICT_STATUS,
  };
}

vi.mock('../../api/accounts', mockAccountTransportModule);

/** Transport status of the optimistic-concurrency refusal the screen discriminates. */
const CONFLICT_STATUS = 409;

const MESSAGES = STATUS_MESSAGES.COACTUPC;

/** An account identifier of exactly the declared width, so a read is attempted. */
const VALID_ACCOUNT_ID = '00000000011';

/** The fixed marker the service publishes in place of either protected identifier. */
const REDACTION_MARKER = '[REDACTED]';

/**
 * Builds a composed account view whose values are recognisable in an assertion.
 * @returns {AccountViewResponse} The response for the screen to seed its form from.
 */
function accountView(): AccountViewResponse {
  return {
    accountId: VALID_ACCOUNT_ID,
    account: {
      activeStatus: 'Y',
      openDate: '2020-01-01',
      creditLimit: '5000.00',
      expirationDate: '2026-01-01',
      cashCreditLimit: '1000.00',
      reissueDate: '2024-01-01',
      currentBalance: '250.00',
      currentCycleCredit: '0.00',
      groupId: 'ZEROBAL',
      currentCycleDebit: '0.00',
    },
    customer: {
      customerId: '000000011',
      ssnMasked: REDACTION_MARKER,
      dateOfBirth: '1980-01-01',
      ficoCreditScore: '750',
      firstName: 'ADA',
      middleName: null,
      lastName: 'LOVELACE',
      addressLine1: '1 ANALYTICAL WAY',
      stateCode: 'NY',
      addressLine2: null,
      zipCode: '10001',
      city: 'NEW YORK',
      countryCode: 'USA',
      phoneNumber1: '(212)5550101',
      governmentIssuedIdMasked: REDACTION_MARKER,
      phoneNumber2: null,
      eftAccountId: '00000000000',
      primaryCardHolderIndicator: 'Y',
    },
    informationMessage: null,
    returnMessage: null,
  };
}

/**
 * Builds the answer either turn returns, carrying only the members a caller reads from it.
 * @param {string | null} returnMessage - The aggregate sentence the turn latched.
 * @param {readonly FieldError[]} fieldErrors - The per-field entries the answer names.
 * @returns {AccountUpdateResponse} The response for the screen to act on.
 */
function updateAnswer(
  returnMessage: string | null,
  fieldErrors: readonly FieldError[],
): AccountUpdateResponse {
  const view = accountView();

  return {
    accountId: VALID_ACCOUNT_ID,
    informationMessage: null,
    returnMessage,
    fieldErrors,
    account: view.account,
    /*
     * WHY : Assumptions: the write answer's customer member is REQUIRED where the view answer's is
     *       nullable, so the fixture substitutes the blank projection rather than widening the type.
     *       `AccountUpdateResponse` declares it non-null because the write echoes the row it wrote and
     *       there is always one; a fixture that passed the nullable value through would describe a
     *       response the service cannot send.
     */
    customer: view.customer ?? UNPOPULATED_CUSTOMER,
  };
}

/**
 * Builds the verdict the VALIDATION turn returns, which is a different shape from the write's answer.
 *
 * ⚠️ Refactoring Rationale: the validation stub was programmed with `updateAnswer`, the write's own
 * answer builder, and the two shapes are not interchangeable. `AccountUpdateValidationResponse` carries
 * `message`, `inputError` and `noChangesFound` -- the reference's `WS-RETURN-MSG`, its `INPUT-ERROR`
 * condition and its "no change" arm at `app/cbl/COACTUPC.cbl` L2584-L2591 -- and carries no account or
 * customer at all, because the checking turn writes nothing. Programming the stub with the write's shape
 * left every case below asserting against a verdict the service does not return, and in particular left
 * `inputError` and `noChangesFound` absent, so a screen that read either would have seen `undefined`.
 * @param {string | null} message - The sentence the verdict latched, or nothing.
 * @param {readonly FieldError[]} fieldErrors - The per-field entries the verdict names.
 * @param {boolean} inputError - Whether any submitted value was refused.
 * @param {boolean} noChangesFound - Whether the submission matched the stored row everywhere compared.
 * @returns {AccountUpdateValidationResponse} The verdict for the screen to act on.
 */
function validationAnswer(
  message: string | null,
  fieldErrors: readonly FieldError[],
  inputError = false,
  noChangesFound = false,
): AccountUpdateValidationResponse {
  return { fieldErrors, message, inputError, noChangesFound };
}

/** Transport status the account read answers when the identifier names no row. */
const NOT_FOUND_STATUS = 404;

/**
 * The read refusal the reference states for an identifier that names no row.
 *
 * Assumptions: taken from the catalog rather than written inline, so the case cannot pass
 * against a sentence the screen never renders.
 */
const ACCOUNT_NOT_FOUND = MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT.text;

/**
 * Builds the normalised failure the shared client raises for a refused request.
 * @param {number} status - Transport status the refusal carries.
 * @param {string} message - Screen-level sentence the message channel is expected to render.
 * @param {readonly FieldError[]} fieldErrors - Per-field entries the document names.
 * @returns {ApiRequestError} The rejection to configure a stub with.
 */
function refusal(
  status: number,
  message: string,
  fieldErrors: readonly FieldError[],
): ApiRequestError {
  const problem: ApiError = {
    code: 'CARDDEMO-ACCT-0001',
    secondaryCode: '',
    message,
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'UITESTACCT000000000AA',
    path: '/api/v1/accounts/update',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors,
    abend: null,
  };

  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`);
}

/**
 * Renders the screen inside the theme, the one shell and a router.
 * @returns {ReactElement} The composed tree under test.
 */
function renderAccountUpdate(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/account/update']}>
        <AppShell>
          <Routes>
            <Route path="/account/update" element={<AccountUpdateScreen />} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Returns the stub standing in for the account read.
 * @returns {MockedFunction<typeof readAccountView>} The mocked transport function.
 */
function readStub(): MockedFunction<typeof readAccountView> {
  return vi.mocked(readAccountView);
}

/**
 * Returns the stub standing in for the non-writing edit check.
 * @returns {MockedFunction<typeof validateAccountUpdate>} The mocked transport function.
 */
function validateStub(): MockedFunction<typeof validateAccountUpdate> {
  return vi.mocked(validateAccountUpdate);
}

/**
 * Returns the stub standing in for the write.
 * @returns {MockedFunction<typeof updateAccount>} The mocked transport function.
 */
function writeStub(): MockedFunction<typeof updateAccount> {
  return vi.mocked(updateAccount);
}

/** Clears every transport stub so no case inherits another's queued outcome. */
function resetTransport(): void {
  readStub().mockReset();
  validateStub().mockReset();
  writeStub().mockReset();
}

/**
 * Reads a record so the screen reaches its details action, with the read already queued.
 * @returns {Promise<void>} Resolves once the form has been seeded.
 */
async function fetchRecord(): Promise<void> {
  readStub().mockResolvedValue({ account: accountView(), revision: 'W/"1"' });
  render(renderAccountUpdate());

  await userEvent.type(screen.getByLabelText(/account number/iu), VALID_ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');
  await screen.findByDisplayValue('LOVELACE');
}

/**
 * Returns one control by the identifier the screen derives for its field.
 * @param {Parameters<typeof fieldDomId>[0]} field - The form member whose control is wanted.
 * @returns {HTMLInputElement} The rendered control.
 */
function controlFor(field: Parameters<typeof fieldDomId>[0]): HTMLInputElement {
  const element = document.getElementById(fieldDomId(field));
  expect(element).not.toBeNull();

  return element as HTMLInputElement;
}

/**
 * Stands in for a resolver until the deferred check has supplied its own.
 *
 * Assumptions: a named no-op declaration rather than an inline arrow in an initialiser, so the variable
 * is never typed as possibly undefined and no assertion is needed at the call site.
 * @returns {void} Nothing; it is never expected to be called.
 */
function releaseNothing(): void {
  return undefined;
}

/**
 * Types a change into an editable control so the screen's comparison finds one.
 * @returns {Promise<void>} Resolves once the change has been recorded.
 */
async function changeCreditLimit(): Promise<void> {
  await userEvent.clear(controlFor('creditLimit'));
  await userEvent.type(controlFor('creditLimit'), '6000.00');
}

/**
 * Resolves the confirmation gate's approval control, once the gate is open.
 *
 * Assumptions: it is located INSIDE the gate rather than by its label alone, because the label is the
 * mapset's `F5=Save` legend and three controls carry it at once -- this one, the trigger it hangs off,
 * and the key legend the shell paints from the screen's bindings. Querying by name would resolve
 * whichever the document happened to hold first.
 * @returns {Promise<HTMLElement>} The approval control within the open gate.
 */
async function findConfirmationApproval(): Promise<HTMLElement> {
  const gate = await waitFor(
    /**
     * Waits for the gate to be present in the document.
     * @returns {HTMLElement} The gate's own container.
     */
    (): HTMLElement => {
      const found = document.querySelector('.ant-popconfirm');
      expect(found).not.toBeNull();

      return found as HTMLElement;
    },
  );
  const approval = Array.from(gate.querySelectorAll('button')).find(
    /**
     * Keeps the control whose text is the save legend.
     * @param {HTMLButtonElement} candidate - One control inside the gate.
     * @returns {boolean} `true` when it is the approval.
     */
    (candidate: HTMLButtonElement): boolean =>
      (candidate.textContent ?? '').includes(ACCOUNT_UPDATE_KEY_LABELS.PFK05),
  );
  expect(approval).toBeDefined();

  return approval as HTMLElement;
}

/**
 * The Enter turn validates through the service and announces validation only on its answer.
 *
 * Assumptions: the assertion is on BOTH halves — that the check was called with the submission, and
 * that the confirmation prompt appeared — because a screen that announced validation and then called
 * the service would satisfy the second alone while telling the operator the outcome before it had one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function validatesThroughTheServiceBeforeAnnouncingIt(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  validateStub().mockResolvedValue(validationAnswer('Looks Good.... so far', []));

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the confirmation prompt the reference paints once its edits have passed.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.getByText(MESSAGES.PROMPT_FOR_CONFIRMATION.text)).toBeInTheDocument();
    },
  );
  expect(validateStub()).toHaveBeenCalledTimes(1);
  expect(validateStub().mock.calls[0]?.[0]).toMatchObject({
    accountId: VALID_ACCOUNT_ID,
    creditLimit: '6000.00',
  });
  expect(writeStub()).not.toHaveBeenCalled();
}

/**
 * A refused edit check marks its field and does not reach the confirmation state.
 *
 * Assumptions: the confirmation prompt's ABSENCE is asserted alongside the marked field, because the
 * defect this replaces was announcing validation regardless of what the edits found — so a case that
 * only checked the field mark would pass against it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aRefusedCheckDoesNotAnnounceValidation(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  validateStub().mockRejectedValue(
    refusal(400, 'Credit Limit is not valid', [
      { field: 'creditLimit', state: 'NOT_OK', message: 'Credit Limit is not valid' },
    ]),
  );

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the refusal the service named to reach the control it names.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(controlFor('creditLimit')).toHaveAttribute('aria-invalid', 'true');
    },
  );
  expect(screen.queryByText(MESSAGES.PROMPT_FOR_CONFIRMATION.text)).not.toBeInTheDocument();
  expect(writeStub()).not.toHaveBeenCalled();

  const help = document.getElementById(fieldErrorId(fieldDomId('creditLimit')));
  expect(help).not.toBeNull();
  expect(help?.textContent).toBe('Credit Limit is not valid');
  expect(controlFor('creditLimit').getAttribute('aria-describedby')).toContain(
    fieldErrorId(fieldDomId('creditLimit')),
  );
}

/**
 * The information line and the message line stand at the same time.
 *
 * Assumptions: the mapset declares two independent fields — `INFOMSG` at row 22 and `ERRMSG` at row 23
 * — and the program fills them from two different working fields, so a refusal must not displace the
 * prompt that says what to do about it. This case drives a refusal and asserts both sentences: the
 * row-22 prompt `3250-SETUP-INFOMSG` selects for the refused action, and the row-23 sentence the
 * service latched.
 *
 * ⚠️ Refactoring Rationale: each sentence is located inside its OWN band rather than by a document-wide
 * text query, which was measured to be ambiguous: the refusal reaches the message channel AND the field
 * help of the control it names, so a bare text query for it found two elements and threw before it could
 * assert anything. Asserting that the two sentences sit in two DIFFERENT bands is also the stronger
 * property — it is exactly what a single shared channel cannot satisfy, whereas a query that merely
 * found both sentences somewhere in the document would pass against one band carrying one of them and a
 * field help carrying the other.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsBothChannelsAtOnce(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  validateStub().mockRejectedValue(
    refusal(400, 'Credit Limit is not valid', [
      { field: 'creditLimit', state: 'NOT_OK', message: 'Credit Limit is not valid' },
    ]),
  );

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for both channels to carry their own sentence, in two separate bands.
     * @returns {void} Nothing; the assertions carry the outcome.
     */
    (): void => {
      // WHY : Refactoring Rationale: each channel is now asked for BY NAME, where this used to
      //       collect every element carrying the row-23 identifier and expect two of them. Two
      //       elements under one identifier was itself the defect: it made the shell's single-band
      //       contract unassertable and let a query for the message line match the information
      //       line. The singular queries below are also stronger -- each throws on a second match,
      //       so this case now pins that each channel is exactly one element.
      const information = screen.getByTestId(INFORMATION_BAND_TEST_ID);
      const message = screen.getByTestId(MESSAGE_BAND_TEST_ID);
      expect(information).toHaveTextContent(MESSAGES.PROMPT_FOR_CHANGES.text);
      expect(message).toHaveTextContent('Credit Limit is not valid');
      expect(information).not.toBe(message);
    },
  );
}

/**
 * The advertised save key opens the confirmation instead of writing, and confirming writes once.
 *
 * Assumptions: the physical key is used rather than the pointer control, because the defect was that
 * the two behaved differently — the key wrote directly while the pointer opened a gate. Pressing the
 * key and asserting the gate is what discriminates that.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function routesTheSaveKeyThroughOneConfirmation(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  validateStub().mockResolvedValue(validationAnswer('Looks Good.... so far', []));
  await userEvent.keyboard('{Enter}');
  await screen.findByText(MESSAGES.PROMPT_FOR_CONFIRMATION.text);
  writeStub().mockResolvedValue({ account: updateAnswer(null, []), revision: 'W/"2"' });

  await userEvent.keyboard('{F5}');

  const confirm = await findConfirmationApproval();
  expect(writeStub()).not.toHaveBeenCalled();

  await userEvent.click(confirm);

  await waitFor(
    /**
     * Waits for exactly one write to have been issued.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(writeStub()).toHaveBeenCalledTimes(1);
    },
  );
}

/**
 * A blank refusal decorates the control without becoming its value.
 *
 * WHY : ⚠️ Refactoring Rationale: the marker is asserted IN the control's value and the operator's next
 *       keystroke asserted to overwrite it, where this required the typed text to remain on screen with
 *       the marker rendered beside it as an `aria-hidden` affix. The reference decides the position:
 *       `app/cpy/CSSETATY.cpy` L23-L26 moves `'*'` into the field's own OUTPUT subfield, so the asterisk
 *       replaces the field's content on the glass -- and `MARKER_BEARING_FIELDS` in the screen records
 *       the one field exempted from that, the government identifier, where a submitted asterisk is an
 *       instruction to delete a stored value.
 * WHY : Assumptions: the defect this case was written for is real and is asserted here in the form the
 *       delivered design admits. Writing the marker into the value did make the next keystroke produce
 *       `*Smith`, because a browser's caret sits after the displayed text rather than at the field's
 *       first column; the screen answers that by stripping a leading marker from the entry, so typing
 *       over the asterisk behaves as it does on a terminal. Asserting the overwrite is what keeps that
 *       strip from being removed later, and it is a stronger statement than the marker's position alone.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersTheBlankMarkerInsideTheRefusedControl(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  validateStub().mockRejectedValue(
    refusal(400, 'First Name must be supplied.', [
      { field: 'firstName', state: 'BLANK', message: 'First Name must be supplied.' },
    ]),
  );

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the blank refusal to reach the control it names.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(controlFor('firstName')).toHaveAttribute('aria-invalid', 'true');
    },
  );
  expect(controlFor('firstName')).toHaveValue(FIELD_ERROR_TOKENS.blankMarker);

  await userEvent.type(controlFor('firstName'), 'SMITH');

  expect(controlFor('firstName')).toHaveValue('SMITH');
}

/**
 * The two protected identifiers are shown as captions and never seeded into their controls.
 *
 * Assumptions: both halves are asserted, because seeding the marker into a control would satisfy a
 * caption assertion on its own while submitting the marker for the service to store.
 *
 * Assumptions: the two captions are asserted to say DIFFERENT things, which was measured against the
 * running service rather than assumed: a changing submission with the three national-identifier parts
 * blank is answered 400 with each part in the `BLANK` state, while a blank government-issued identifier
 * preserves the stored value. One shared sentence promising preservation is therefore false on one of
 * them, and this case is what holds the two apart.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function showsTheProtectedMarkersOutsideTheirControls(): Promise<void> {
  await fetchRecord();

  const captions = screen.getAllByText(new RegExp(`^\\${'['}REDACTED\\] `, 'u'));
  expect(captions).toHaveLength(2);
  expect(screen.getByText(nationalIdentifierCaption(REDACTION_MARKER))).toBeInTheDocument();
  expect(screen.getByText(governmentIdentifierCaption(REDACTION_MARKER))).toBeInTheDocument();
  expect(nationalIdentifierCaption(REDACTION_MARKER)).not.toBe(
    governmentIdentifierCaption(REDACTION_MARKER),
  );
  for (const control of [
    controlFor('ssnPart1'),
    controlFor('ssnPart2'),
    controlFor('ssnPart3'),
    controlFor('governmentIssuedId'),
  ]) {
    expect(control).toHaveValue('');
  }
  expect(controlFor('ssnPart1').getAttribute('aria-describedby')).toBe(captions[0]?.id);
  expect(controlFor('governmentIssuedId').getAttribute('aria-describedby')).toBe(captions[1]?.id);
}

/**
 * A typed non-digit survives in the control it was typed into.
 *
 * Assumptions: the credit score is used because the reference edits it with a numeric test whose
 * non-numeric refusal became unreachable while entry was filtered — typing `A` left the control empty,
 * so the refusal that arrived said the field was blank.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function preservesATypedNonDigit(): Promise<void> {
  await fetchRecord();

  await userEvent.clear(controlFor('ficoCreditScore'));
  await userEvent.type(controlFor('ficoCreditScore'), 'A12');

  expect(controlFor('ficoCreditScore')).toHaveValue('A12');
}

/**
 * A refused write clears every submitted identifier from state and from the mounted controls.
 *
 * Assumptions: an editable non-identifier value is asserted to SURVIVE alongside them, because a fix
 * that blanked the whole form would satisfy the clearing assertion while destroying the correction the
 * operator is being asked to make.
 *
 * Assumptions: the write is reached the way an operator reaches it — the save key OPENS the confirmation
 * gate and the approval inside it issues the write — because the gate is controlled by the screen's own
 * state and is not in the document until that key has been pressed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function clearsSubmittedIdentifiersOnARefusedWrite(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  await userEvent.type(controlFor('ssnPart1'), '123');
  await userEvent.type(controlFor('governmentIssuedId'), 'DL123456');
  validateStub().mockResolvedValue(validationAnswer('Looks Good.... so far', []));
  await userEvent.keyboard('{Enter}');
  await screen.findByText(MESSAGES.PROMPT_FOR_CONFIRMATION.text);
  writeStub().mockRejectedValue(
    refusal(400, 'Credit Limit is not valid', [
      { field: 'creditLimit', state: 'NOT_OK', message: 'Credit Limit is not valid' },
    ]),
  );
  await userEvent.keyboard('{F5}');

  await userEvent.click(await findConfirmationApproval());

  await waitFor(
    /**
     * Waits for the settlement to have blanked the four identifier members.
     * @returns {void} Nothing; the assertions carry the outcome.
     */
    (): void => {
      expect(controlFor('ssnPart1')).toHaveValue('');
      expect(controlFor('governmentIssuedId')).toHaveValue('');
    },
  );
  expect(controlFor('creditLimit')).toHaveValue('6000.00');
}

/**
 * The unlabelled second address line carries the reference's own name.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function namesTheSecondAddressLine(): Promise<void> {
  await fetchRecord();

  expect(screen.getByLabelText(ADDRESS_LINE_2_NAME)).toBe(controlFor('addressLine2'));
}

/**
 * A protected control is reachable rather than removed from the focus order.
 *
 * Assumptions: the customer identifier is used because it is the field the service can refuse and the
 * screen cannot edit, so it is the one whose refusal had no reachable target at all.
 *
 * Assumptions: BOTH halves of reachability are asserted — that the control stays in the focus order at
 * all, and that a refusal NAMING it actually lands the cursor on it. A control can satisfy the first and
 * still fail the second, because the cursor walks the mapset's paint order and a field missing from that
 * order is a field the walk cannot reach.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsProtectedControlsReachable(): Promise<void> {
  await fetchRecord();

  const protectedControl = controlFor('customerId');
  expect(protectedControl).toHaveAttribute('readonly');
  expect(protectedControl).not.toBeDisabled();

  await changeCreditLimit();
  validateStub().mockRejectedValue(
    refusal(400, 'Customer ID is not valid', [
      { field: 'customerId', state: 'NOT_OK', message: 'Customer ID is not valid' },
    ]),
  );

  await userEvent.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the cursor to reach the protected control the refusal named.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(document.activeElement).toBe(controlFor('customerId'));
    },
  );
}

/**
 * A second Enter while the edit check is in flight does not issue a second check.
 *
 * Assumptions: the check is held unresolved for the duration, because that is the only window in which
 * the property is observable — a screen whose key binding ignores its own busy state issues one call per
 * keystroke, and the reference's terminal keyboard is LOCKED between the send and the reply, so a second
 * turn cannot start.
 *
 * Assumptions: the assertion is on the CALL COUNT rather than on a visual disabled attribute, because
 * the finding is that the keyboard and the pointer disagreed: a binding can render as disabled and still
 * dispatch, and the count is what proves the binding itself refused.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesASecondEnterWhileChecking(): Promise<void> {
  await fetchRecord();
  await changeCreditLimit();
  let settle: (answer: AccountUpdateValidationResponse) => void = releaseNothing;
  validateStub().mockReturnValue(
    new Promise<AccountUpdateValidationResponse>(
      /**
       * Captures the resolver so the check can be held in flight across two keystrokes.
       * @param {(answer: AccountUpdateValidationResponse) => void} resolve - The promise's own resolver.
       * @returns {void} Nothing; the resolver is retained by the closure.
       */
      (resolve: (answer: AccountUpdateValidationResponse) => void): void => {
        settle = resolve;
      },
    ),
  );

  await userEvent.keyboard('{Enter}');
  await userEvent.keyboard('{Enter}');

  expect(validateStub()).toHaveBeenCalledTimes(1);
  settle(validationAnswer('Looks Good.... so far', []));
  await screen.findByText(MESSAGES.PROMPT_FOR_CONFIRMATION.text);
}

/**
 * An all-zeroes filter is refused and a short one is refused too.
 *
 * Assumptions: both are asserted through the ONE sentence the reference composes for them, and the
 * short value is included because the local pattern used to admit one to eleven digits while its own
 * comment claimed eleven — a value a terminal cannot send and the service refuses.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAZeroedAndAShortFilter(): Promise<void> {
  render(renderAccountUpdate());
  const filter = screen.getByLabelText(/account number/iu);

  await userEvent.type(filter, '0'.repeat(11));
  await userEvent.keyboard('{Enter}');

  expect(readStub()).not.toHaveBeenCalled();
  expect(filter).toHaveAttribute('aria-invalid', 'true');

  await userEvent.clear(filter);
  await userEvent.type(filter, '11');
  await userEvent.keyboard('{Enter}');

  expect(readStub()).not.toHaveBeenCalled();
}

/**
 * A successful read clears the refusal the previous read left on the message channel.
 *
 * ⚠️ Purpose: `app/cbl/COACTUPC.cbl` L873 to L876 clears `WS-RETURN-MSG` in `MAIN-PARA`, before a
 * task examines anything, so the row-23 line a turn paints is always the one that turn computed.
 * The screen wrote that channel only when it had something to say, so a refused lookup followed by
 * a good one left the operator looking at a freshly-loaded record beneath `Did not find this account
 * in account card xref file` -- in a band whose ARIA role asserts it.
 *
 * Assumptions: the not-found refusal is driven through the READ, not the edit check, because the
 * defect needed two reads: one that reports and one that succeeds without reporting. Asserting the
 * message channel is EMPTY after the second is what discriminates a per-turn clear from a clear
 * written into one success path.
 * @returns {Promise<void>} Resolves once both reads have settled.
 */
async function clearsTheChannelOnASuccessfulRead(): Promise<void> {
  const notFound = refusal(NOT_FOUND_STATUS, ACCOUNT_NOT_FOUND, []);
  readStub().mockRejectedValueOnce(notFound);
  render(renderAccountUpdate());

  const filter = screen.getByLabelText(/account number/iu);
  await userEvent.type(filter, VALID_ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');
  await waitFor(
    /**
     * Waits for the failed read to report on the message channel.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toHaveTextContent(ACCOUNT_NOT_FOUND);
    },
  );

  readStub().mockResolvedValue({ account: accountView(), revision: 'W/"1"' });
  await userEvent.clear(filter);
  await userEvent.type(filter, VALID_ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');
  await screen.findByDisplayValue('LOVELACE');

  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toBeEmptyDOMElement();
}

/**
 * Registers the account-update cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function accountUpdateCases(): void {
  it(
    'validates through the service before announcing validation',
    validatesThroughTheServiceBeforeAnnouncingIt,
  );
  it('does not announce validation when the check refuses', aRefusedCheckDoesNotAnnounceValidation);
  it('keeps the information and message channels at once', keepsBothChannelsAtOnce);
  it('routes the save key through one confirmation', routesTheSaveKeyThroughOneConfirmation);
  it(
    'renders the blank marker inside the refused control',
    rendersTheBlankMarkerInsideTheRefusedControl,
  );
  it(
    'shows the protected markers outside their controls',
    showsTheProtectedMarkersOutsideTheirControls,
  );
  it('preserves a typed non-digit', preservesATypedNonDigit);
  it('clears submitted identifiers on a refused write', clearsSubmittedIdentifiersOnARefusedWrite);
  it('names the second address line', namesTheSecondAddressLine);
  it('keeps protected controls reachable', keepsProtectedControlsReachable);
  it('refuses a second enter while checking', refusesASecondEnterWhileChecking);
  it('refuses a zeroed and a short filter', refusesAZeroedAndAShortFilter);
  it('clears the message channel on a successful read', clearsTheChannelOnASuccessfulRead);
}

beforeEach(resetTransport);

afterEach(resetTransport);

describe('account update screen', accountUpdateCases);
