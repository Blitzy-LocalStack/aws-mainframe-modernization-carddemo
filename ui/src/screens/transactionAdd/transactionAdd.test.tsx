/**
 * @file Component tests for the transaction add screen in `ui/src/screens/transactionAdd/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the properties a review found this screen had lost across its copy key press and its
 * confirmation: that the copy validates only the KEY, that it paints all eleven copied values, that it
 * then enters the ordinary confirmation, that a keyed answer and a clicked one reach one dispatcher and
 * submit the same turn against the copied values, that every control a refusal can name is described
 * programmatically, and that the legend's controls reflect a turn in flight rather than accepting a
 * press and dropping it.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, matching
 * `ui/src/screens/accountUpdate/accountUpdate.test.tsx`. These cases are about what the SCREEN does with
 * an outcome, so the shortest honest seam is the function the screen calls — and the seam is where the
 * defect lived: the screen called the copy-and-write operation where the reference reads.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for the
 * reason the sibling suites record: `ui/eslint.config.js` selects a function expression in every position
 * so an inline callback owes its own JSDoc block, and Prettier moves a block comment that follows an
 * argument comma onto the preceding literal, detaching it from what it documents.
 */

import { ConfigProvider } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { ApiRequestError } from '../../api/client';
import { addTransaction, copyLastTransaction } from '../../api/transactions';
import type {
  CopiedTransactionData,
  TransactionAddOutcome,
  TransactionCreateRequest,
} from '../../api/transactions';
import type { ApiError } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import { fieldHintId } from '../../layout/fieldHelp';
import { PROGRAM_MESSAGES, SHARED_MESSAGES } from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';
import {
  TRANSACTION_ADD_FIELD_LABELS,
  TRANSACTION_ADD_FORMAT_HINTS,
  TRANSACTION_ADD_KEY_LABELS,
  TransactionAddScreen,
} from './index';

/**
 * Builds the transport double this screen reaches.
 *
 * Assumptions: the factory is a named declaration passed to `vi.mock`, because the call is hoisted above
 * the imports and a factory held in a `const` would be in its temporal dead zone at registration time.
 * @returns {Record<string, unknown>} The transport functions this screen can reach, each a fresh spy.
 */
function mockTransactionTransportModule(): Record<string, unknown> {
  return {
    addTransaction: vi.fn(),
    copyLastTransaction: vi.fn(),
    listTransactions: vi.fn(),
    viewTransaction: vi.fn(),
    payAccountBalanceInFull: vi.fn(),
  };
}

vi.mock('../../api/transactions', mockTransactionTransportModule);

const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;

/** An account identifier of exactly the declared width, so the key edit accepts it. */
const VALID_ACCOUNT_ID = '00000000011';

/** HTTP status the service answers when there is no row to copy. */
const NOT_FOUND_STATUS = 404;

/** HTTP status the service answers when a read failed for a reason the caller cannot correct. */
const SERVER_ERROR_STATUS = 500;

/**
 * The MASKED rendering the service publishes for the card the cross-reference resolved.
 *
 * ⚠️ Assumptions: every withheld answer this suite stubs reports it, because the service resolves the pair
 * on every turn -- `app/cbl/COTRN02C.cbl` L166 performs `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm as
 * L473 does for the copy arm. What the screen does with it has changed: the reference repaints its card
 * FIELD from the resolved number, and the migrated preview publishes only this masked rendering, so the
 * control is left as the operator keyed it and the masked value is shown as protected text instead.
 *
 * ⚠️ Refactoring Rationale: the constant used to hold the sixteen digits, which is what the preview
 * published. A review found that a non-administrative preview disclosed a whole primary account number to
 * the browser, and AAP section 0.4.1.9 masks it everywhere but the administrative card-detail read.
 */
const RESOLVED_CARD_NUMBER_MASKED = '************1111';

/** The opaque binding the preview publishes so a confirming turn can name the same resolved card. */
// The value is deliberately low-entropy; `ui/src/api/transactions.test.ts` records why.
const CONFIRMATION_TOKEN = 'v2.aaaaaaaaaaaaaaaa.notarealsealedvalue';

/**
 * The ten copied members the service publishes, and the row they came from.
 *
 * Assumptions: every value differs from anything this screen could have had keyed, so an assertion that
 * finds one in a control can only have got it from the copy.
 *
 * ⚠️ Refactoring Rationale: there is no `amount` member here, and its absence is the contract's rather
 * than an omission. `app/cbl/COTRN02C.cbl` L481 renders the stored figure through
 * `WS-TRAN-AMT-E PIC +99999999.99` and L485 moves that edited rendering, which is the same value the
 * preview's own amount member carries -- so transaction-service publishes it once, on the preview, and a
 * copied-record member would publish one figure twice in one body. {@link copiedOutcome} supplies it in
 * the place the service does, which is what keeps the painted amount observable.
 * @returns {CopiedTransactionData} The ten values the stored row carries, plus the resolved keys.
 */
function copiedValues(): CopiedTransactionData {
  return {
    sourceTransactionId: '0000000000683580',
    typeCode: '02',
    categoryCode: '0002',
    source: 'ATM TERM',
    description: 'FUEL PURCHASE',
    merchantId: '987654321',
    merchantName: 'FUEL STOP',
    merchantCity: 'TACOMA',
    merchantZip: '98402',
    originDate: '2026-01-10',
    processDate: '2026-01-11',
  };
}

/**
 * The whole answer a copy turn receives: the preview, and what it copied.
 *
 * Assumptions: the amount is deliberately a value the screen's own edit mask renders differently from the
 * wire form -- `42.75` becomes `+00000042.75` -- so the re-render is observable rather than inferred.
 *
 * Assumptions: `written` is false and the sentence is null, which is the withheld arm. A copy pressed with
 * a blank confirmation copies and asks; the arm that also writes is reached by keying the answer first,
 * which the confirming cases exercise.
 * @returns {TransactionAddOutcome} The withheld outcome carrying the copied record.
 */
function copiedOutcome(): TransactionAddOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      amount: '42.75',
      written: false,
      returnMessage: null,
      resolvedAccountId: VALID_ACCOUNT_ID,
      resolvedCardNumberMasked: RESOLVED_CARD_NUMBER_MASKED,
      confirmationToken: CONFIRMATION_TOKEN,
      copied: copiedValues(),
    },
  };
}

/** The amount the copied wire value becomes once the screen's own mask has rendered it. */
const PAINTED_AMOUNT = '+00000042.75';

/**
 * Renders the screen inside the one shell the application mounts, on its own route.
 *
 * Assumptions: the real `AppShell` is mounted rather than stubbed, because the sentences under assertion
 * are painted by the shell's message band from what this screen publishes — a stub would assert this
 * screen's intent instead of the delivered rendering.
 * @returns {ReactElement} The tree to render.
 */
function renderTransactionAdd(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/transactions/new']}>
        <AppShell>
          <Routes>
            <Route path="/transactions/new" element={<TransactionAddScreen />} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Returns the stub standing in for the copy-last operation.
 *
 * ⚠️ Refactoring Rationale: this stands in for ONE operation, where a read-only lookup was stubbed
 * separately. The service publishes a single operation that copies, validates and answers with the
 * preview carrying what it copied -- the reference's own fall-through at `app/cbl/COTRN02C.cbl` L495 --
 * so a lookup stub described a call this screen never makes.
 * @returns {MockedFunction<typeof copyLastTransaction>} The mocked transport function.
 */
function copyStub(): MockedFunction<typeof copyLastTransaction> {
  return vi.mocked(copyLastTransaction);
}

/**
 * Returns the stub standing in for the capture.
 * @returns {MockedFunction<typeof addTransaction>} The mocked transport function.
 */
function captureStub(): MockedFunction<typeof addTransaction> {
  return vi.mocked(addTransaction);
}

/** Clears every transport stub so no case inherits another's queued outcome. */
function resetTransport(): void {
  copyStub().mockReset();
  captureStub().mockReset();
}

/**
 * Returns one control by the label the mapset declares for it.
 *
 * Assumptions: the control is found by its transcribed LABEL rather than by an identifier, because the
 * identifier is derived per instance from `useId` and is not computable from outside. Finding it by label
 * also asserts the label association itself, which is the thing an operator using assistive technology
 * depends on.
 * @param {keyof typeof TRANSACTION_ADD_FIELD_LABELS} field - The field whose control is wanted.
 * @returns {HTMLInputElement} The rendered control.
 */
function controlFor(field: keyof typeof TRANSACTION_ADD_FIELD_LABELS): HTMLInputElement {
  return screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS[field]);
}

/**
 * Reads the sentence the shell's message band is currently painting.
 * @returns {string} The band's text, empty when it is painting nothing.
 */
function bandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/**
 * Builds the problem document a refused or failed read arrives with.
 *
 * Assumptions: every member the published shape declares is supplied, including the two a screen never
 * renders — the secondary code and the path — because the type is the contract's own and a partial
 * fixture would describe a body no service sends.
 * @param {string} message - The aggregate sentence the service published.
 * @param {number} status - The transport status the service answered with.
 * @returns {ApiError} The normalised problem document.
 */
function problem(message: string, status: number): ApiError {
  return {
    code: 'CARDDEMO-0500',
    secondaryCode: '',
    message,
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'CD0000000000000000000001',
    path: '/api/v1/transactions/copy-last/lookup',
    timestamp: '2026-01-15T00:00:00.000000Z',
    fieldErrors: [],
    abend: null,
  };
}

/**
 * Builds the rejection a failed read arrives as.
 * @param {string} message - The aggregate sentence the service published.
 * @param {number} status - The transport status the service answered with.
 * @returns {ApiRequestError} The rejection the transport module raises.
 */
function readFailure(message: string, status: number): ApiRequestError {
  return new ApiRequestError(
    'PROBLEM',
    status,
    problem(message, status),
    `PROBLEM ${String(status)}`,
  );
}

/**
 * Renders the screen and keys a valid account identifier, leaving every data field blank.
 *
 * Assumptions: the form is left EMPTY apart from the key, because that is the state an operator presses
 * the copy key in — the key exists to fill the fields — and it is the state the previous shape of this
 * screen refused.
 * @returns {Promise<void>} Resolves once the key has been keyed.
 */
async function keyTheAccountOnly(): Promise<void> {
  render(renderTransactionAdd());
  await userEvent.type(controlFor('accountId'), VALID_ACCOUNT_ID);
}

beforeEach(resetTransport);
afterEach(vi.restoreAllMocks);

/**
 * Registers the transaction-add cases.
 *
 * Assumptions: a NAMED registrar rather than an inline callback, matching the sibling suites, because the
 * lint configuration requires a JSDoc block on every function expression -- including the one a test
 * registrar receives.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function transactionAddCases(): void {
  /**
   * The copy key validates the KEY alone, so it works on the empty form it exists to fill.
   *
   * Assumptions: the assertion is that the LOOKUP was issued and that no data-field sentence was
   * published, which together pin the order at `app/cbl/COTRN02C.cbl` L473. The previous shape ran the
   * whole data chain first and answered `Type CD can NOT be empty...`, so this case fails against it.
   * @returns {Promise<void>} Resolves once the copy has been requested.
   */
  async function theCopyKeyValidatesTheKeyAlone(): Promise<void> {
    copyStub().mockResolvedValue(copiedOutcome());
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the read-only lookup has been issued.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(copyStub()).toHaveBeenCalledWith({ accountId: VALID_ACCOUNT_ID });
      },
    );
    expect(bandText()).not.toContain(ADD_MESSAGES.TYPE_CD_CAN_NOT_BE_EMPTY);
  }

  it('validates only the key fields before copying, per line 473', theCopyKeyValidatesTheKeyAlone);

  /**
   * The copy key paints all ELEVEN copied values into the form.
   *
   * Assumptions: all eleven are asserted, and so is what happens to the three the copy block itself leaves
   * alone. The amount is asserted in the screen's own mask, since `app/cbl/COTRN02C.cbl` L481 renders the
   * record's value through `WS-TRAN-AMT-E` before moving it at L485.
   *
   * ⚠️ Refactoring Rationale: the CARD field is asserted to hold the RESOLVED card rather than to be
   * unchanged. The copy block moves nothing into it, but the copy paragraph performs
   * `VALIDATE-INPUT-KEY-FIELDS` at L473 before it reads, and that paragraph's account arm moves the
   * cross-reference's card number into `CARDNINI` at L209 -- after which the screen is re-sent. Asserting
   * it blank described a screen the reference never sends, and it would have admitted a browser that showed
   * an operator one card while the service wrote another.
   * @returns {Promise<void>} Resolves once the values have been painted.
   */
  async function theCopyKeyPaintsTheElevenValues(): Promise<void> {
    copyStub().mockResolvedValue(copiedOutcome());
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the copied type code has been painted into its control.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(controlFor('typeCode')).toHaveValue('02');
      },
    );
    expect(controlFor('categoryCode')).toHaveValue('0002');
    expect(controlFor('source')).toHaveValue('ATM TERM');
    expect(controlFor('description')).toHaveValue('FUEL PURCHASE');
    expect(controlFor('amount')).toHaveValue(PAINTED_AMOUNT);
    expect(controlFor('merchantId')).toHaveValue('987654321');
    expect(controlFor('merchantName')).toHaveValue('FUEL STOP');
    expect(controlFor('merchantCity')).toHaveValue('TACOMA');
    expect(controlFor('merchantZip')).toHaveValue('98402');
    expect(controlFor('originDate')).toHaveValue('2026-01-10');
    expect(controlFor('processDate')).toHaveValue('2026-01-11');

    expect(controlFor('accountId')).toHaveValue(VALID_ACCOUNT_ID);
    /*
     * WHY : ⚠️ Assumptions: the card control is asserted to stay BLANK, where it used to be asserted to
     *       hold the resolved number. The resolved card now arrives masked, and a masked rendering is not a
     *       value this screen may put into a numeric key field -- the next turn's own pattern refuses it --
     *       so the control keeps what the operator keyed and the resolved card is rendered as protected text
     *       on the confirmation surface, which `transactionAddTurns.test.tsx` asserts.
     */
    expect(controlFor('cardNumber')).toHaveValue('');
    expect(controlFor('confirmation')).toHaveValue('');
  }

  it('paints the eleven copied values, per lines 481 to 492', theCopyKeyPaintsTheElevenValues);

  /**
   * The copy key then enters the ordinary confirmation, as line 495 does.
   *
   * ⚠️ Refactoring Rationale: the fall-through is asserted on the COPY request and its answer, where a
   * second capture call was asserted before. `PERFORM PROCESS-ENTER-KEY` at `app/cbl/COTRN02C.cbl` L495 is
   * inside the paragraph the key press performs, so it is one turn of the terminal and it is one operation
   * here: the copy request carries the confirmation exactly as the field holds it, the service validates
   * the copied capture, and the answer is the confirmation prompt. Asserting a second call would assert a
   * round trip the reference does not make and the service does not publish.
   *
   * Assumptions: the request is asserted to carry NO confirmation member, because the field is blank on
   * this turn -- absence is how an unconfirmed turn is spelled -- and the prompt is asserted to be the
   * reference's own sentence for that answer, which together pin the fall-through.
   * @returns {Promise<void>} Resolves once the confirmation prompt has been published.
   */
  async function theCopyKeyEntersTheOrdinaryConfirmation(): Promise<void> {
    copyStub().mockResolvedValue(copiedOutcome());
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the confirmation prompt has reached the band.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(bandText()).toContain(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
      },
    );

    expect(copyStub()).toHaveBeenCalledTimes(1);
    expect(copyStub()).toHaveBeenCalledWith({ accountId: VALID_ACCOUNT_ID });
    expect(captureStub()).not.toHaveBeenCalled();
    expect(controlFor('amount')).toHaveValue(PAINTED_AMOUNT);
    expect(controlFor('description')).toHaveValue('FUEL PURCHASE');
  }

  it(
    'falls through into the confirmation turn with the copied values, per line 495',
    theCopyKeyEntersTheOrdinaryConfirmation,
  );

  /**
   * The copy key with neither key supplied refuses locally and reads nothing.
   *
   * Assumptions: the sentence and the absence of a request are both asserted, because the reference
   * refuses this in the paragraph the key press performs — L227's sentence with the cursor at L228 — and a
   * screen that sent the blank key would take a service refusal for a value it can judge itself.
   * @returns {Promise<void>} Resolves once the refusal has been published.
   */
  async function theCopyKeyWithNoKeyRefusesLocally(): Promise<void> {
    render(renderTransactionAdd());

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the key refusal has reached the band.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(bandText()).toContain(ADD_MESSAGES.ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED);
      },
    );
    expect(copyStub()).not.toHaveBeenCalled();
  }

  it(
    'refuses a copy with neither key and reads nothing, per line 227',
    theCopyKeyWithNoKeyRefusesLocally,
  );

  /**
   * A failed copy read is reported in the reference's own words and paints nothing.
   *
   * Assumptions: the sentence asserted is the BROWSE failure at L664 and L693 and not the write's, because
   * the turn's work is the read of the row to copy. A screen reporting `Unable to Add Transaction...` here
   * would name an operation this turn never attempted.
   * @returns {Promise<void>} Resolves once the failure has been reported.
   */
  async function aFailedCopyReadIsReportedAsTheBrowseFailure(): Promise<void> {
    copyStub().mockRejectedValue(
      readFailure(SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION, SERVER_ERROR_STATUS),
    );
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the browse failure has reached the band.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(bandText()).toContain(SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION);
      },
    );
    expect(controlFor('typeCode')).toHaveValue('');
    expect(captureStub()).not.toHaveBeenCalled();
  }

  it(
    'reports a failed copy read as the browse failure, per lines 664 and 693',
    aFailedCopyReadIsReportedAsTheBrowseFailure,
  );

  /**
   * An empty ledger is reported as the reference's not-found sentence for the row to copy.
   *
   * Assumptions: the 404 carrying no field error is the empty-table case, which the copy contract states
   * explicitly, so the sentence is `Transaction ID NOT found...` from L655 to L660 rather than either
   * key-flavoured one.
   * @returns {Promise<void>} Resolves once the failure has been reported.
   */
  async function anEmptyLedgerIsReportedAsTheNotFoundSentence(): Promise<void> {
    copyStub().mockRejectedValue(
      readFailure(SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND, NOT_FOUND_STATUS),
    );
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the not-found sentence has reached the band.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(bandText()).toContain(SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND);
      },
    );
  }

  it(
    'reports an empty ledger as the not-found sentence, per lines 655 to 660',
    anEmptyLedgerIsReportedAsTheNotFoundSentence,
  );

  /**
   * A confirming answer keyed after a copy writes the COPIED values through the capture.
   *
   * Assumptions: this is the case the review's fourth critical finding named. The previous shape cleared
   * its copy state when the confirmation character was typed and then submitted the copy operation with
   * the values as they stood BEFORE the copy, so the record written was not the one the operator saw. The
   * assertion reads the capture call's body, which must carry the copied values and the confirming
   * answer. It is the FIRST capture call, because the copy turn before it went through the copy operation
   * rather than through the capture.
   * @returns {Promise<void>} Resolves once the write has been requested.
   */
  async function aKeyedConfirmationWritesTheCopiedValues(): Promise<void> {
    copyStub().mockResolvedValue(copiedOutcome());
    await keyTheAccountOnly();
    await userEvent.keyboard('{F5}');
    await waitFor(
      /**
       * Waits until the copy has painted and the prompt has been published.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(controlFor('typeCode')).toHaveValue('02');
      },
    );

    captureStub().mockResolvedValue({
      outcome: 'CREATED',
      created: {
        transactionId: '0000000000000009',
        amount: '42.75',
        returnMessage: 'Transaction added successfully.  Your Tran ID is 0000000000000009.',
      },
    });
    await userEvent.type(controlFor('confirmation'), 'Y');
    await userEvent.keyboard('{Enter}');

    await waitFor(
      /**
       * Waits until the confirming capture has been issued.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(captureStub()).toHaveBeenCalledTimes(1);
      },
    );
    const written = captureStub().mock.calls[0]?.[0] as TransactionCreateRequest;
    expect(written.confirmation).toBe('Y');
    expect(written.typeCode).toBe('02');
    expect(written.description).toBe('FUEL PURCHASE');
    expect(written.merchantId).toBe('987654321');
  }

  it('writes the copied values on a keyed confirmation', aKeyedConfirmationWritesTheCopiedValues);

  /**
   * The pointer confirmation and the keyed one submit the SAME turn.
   *
   * Assumptions: the two are compared body for body AND the screen is asserted to show the answer it
   * submitted, because the defect was that they diverged: one recorded the answer and submitted, the other
   * submitted whatever the field held. The confirmation control is deliberately CLEARED before the pointer
   * surface is used, so a path that submitted the field rather than recording its own answer leaves the
   * screen displaying a blank confirmation for a turn it sent as `Y` -- which is the operator being shown
   * something other than what they committed.
   * @returns {Promise<void>} Resolves once both surfaces have submitted.
   */
  async function thePointerAndKeyedConfirmationsSubmitTheSameTurn(): Promise<void> {
    copyStub().mockResolvedValue(copiedOutcome());
    /*
     * WHY : Assumptions: the CAPTURE answers the withheld arm on both of this case's confirming turns, so
     *       neither writes and the second turn is submitted from the same painted screen as the first.
     *       Answering `CREATED` would clear the form after the keyed turn, and the pointer turn would then
     *       compare a blank submission against a populated one.
     */
    captureStub().mockResolvedValue({
      outcome: 'PREVIEWED',
      preview: {
        amount: '42.75',
        written: false,
        returnMessage: null,
        resolvedAccountId: VALID_ACCOUNT_ID,
        resolvedCardNumberMasked: RESOLVED_CARD_NUMBER_MASKED,
        confirmationToken: CONFIRMATION_TOKEN,
        copied: null,
      },
    });
    await keyTheAccountOnly();
    await userEvent.keyboard('{F5}');
    await waitFor(
      /**
       * Waits until the copy has painted.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(controlFor('typeCode')).toHaveValue('02');
      },
    );

    await userEvent.type(controlFor('confirmation'), 'Y');
    await userEvent.keyboard('{Enter}');
    await waitFor(
      /**
       * Waits until the keyed confirmation has been submitted.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(captureStub()).toHaveBeenCalledTimes(1);
      },
    );
    const keyed = captureStub().mock.calls[0]?.[0] as TransactionCreateRequest;

    await userEvent.clear(controlFor('confirmation'));
    await userEvent.click(screen.getByRole('button', { name: /add transaction/iu }));
    await userEvent.click(await screen.findByRole('button', { name: 'Y' }));
    await waitFor(
      /**
       * Waits until the pointer confirmation has been submitted.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(captureStub()).toHaveBeenCalledTimes(2);
      },
    );
    const clicked = captureStub().mock.calls[1]?.[0] as TransactionCreateRequest;

    expect(clicked).toEqual(keyed);
    expect(controlFor('confirmation')).toHaveValue('Y');
  }

  it(
    'submits one turn whether the confirmation is keyed or clicked',
    thePointerAndKeyedConfirmationsSubmitTheSameTurn,
  );

  /**
   * A refusal is bound to its control programmatically, not merely printed beside it.
   *
   * Assumptions: the control is asserted to be marked invalid AND to point at an element that EXISTS and
   * carries the sentence. `Form.Item` positions its help text visually and gives it no identifier, so
   * without the binding an operator using a screen reader heard the label and the value and never the
   * reason the value was refused.
   * @returns {Promise<void>} Resolves once the refusal has been published.
   */
  async function aRefusalIsBoundToItsControl(): Promise<void> {
    await keyTheAccountOnly();

    await userEvent.keyboard('{Enter}');

    const control = await waitFor(
      /**
       * Waits until the type-code control has been marked invalid.
       * @returns {HTMLInputElement} The refused control.
       */
      (): HTMLInputElement => {
        const candidate = controlFor('typeCode');
        expect(candidate).toHaveAttribute('aria-invalid', 'true');
        return candidate;
      },
    );

    const described = control.getAttribute('aria-describedby');
    expect(described).not.toBeNull();
    const target = document.getElementById((described ?? '').split(' ')[0] ?? '');
    expect(target).not.toBeNull();
    expect(target?.textContent).toBe(ADD_MESSAGES.TYPE_CD_CAN_NOT_BE_EMPTY);
  }

  it('binds a refusal to the control it names', aRefusalIsBoundToItsControl);

  /**
   * A format hint is bound to the control it describes.
   *
   * Assumptions: the hint is DESCRIBED and not folded into the accessible name, for the reason the shared
   * renderer records — the name is the transcribed label, and appending the hint would repeat it in every
   * announcement including a bare list of form fields.
   * @returns {Promise<void>} Resolves once the screen has rendered.
   */
  async function aFormatHintIsBoundToItsControl(): Promise<void> {
    render(renderTransactionAdd());

    const control = controlFor('amount');
    const described = control.getAttribute('aria-describedby') ?? '';
    expect(described).toContain(fieldHintId(control.id));
    expect(document.getElementById(fieldHintId(control.id))?.textContent).toBe(
      TRANSACTION_ADD_FORMAT_HINTS.amount,
    );

    return Promise.resolve();
  }

  it('binds each format hint to its control', aFormatHintIsBoundToItsControl);

  /**
   * The legend's controls report a turn in flight rather than accepting a press and dropping it.
   *
   * Assumptions: the settlement is withheld deliberately, so the assertion observes the in-flight state
   * itself. The previous shape left all four legend controls enabled while the form's own controls were
   * disabled, so an operator pressing Enter again saw nothing happen and no reason why.
   * @returns {Promise<void>} Resolves once the in-flight state has been observed.
   */
  async function theLegendReflectsATurnInFlight(): Promise<void> {
    copyStub().mockReturnValue(new Promise<TransactionAddOutcome>(holdOpen));
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');

    await waitFor(
      /**
       * Waits until the legend's copy control reports the turn.
       * @returns {void} Nothing; the assertion is the wait's condition.
       */
      (): void => {
        expect(
          screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.PFK05 }),
        ).toBeDisabled();
      },
    );
    expect(screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.ENTER })).toBeDisabled();
    expect(screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.PFK03 })).toBeDisabled();
    expect(screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.PFK04 })).toBeDisabled();
  }

  it('disables the legend while a turn is in flight', theLegendReflectsATurnInFlight);

  /**
   * Two presses arriving before the first settles submit exactly ONE turn.
   *
   * Assumptions: the guard is asserted through the SUBMISSION count rather than through the disabled
   * attribute, because the two are independent: a handler closing over a stale `busy` would accept the
   * second press whatever the control looked like, and this capture writes money.
   * @returns {Promise<void>} Resolves once both presses have been made.
   */
  async function twoPressesSubmitOneTurn(): Promise<void> {
    copyStub().mockReturnValue(new Promise<TransactionAddOutcome>(holdOpen));
    await keyTheAccountOnly();

    await userEvent.keyboard('{F5}');
    await userEvent.keyboard('{F5}');

    expect(copyStub()).toHaveBeenCalledTimes(1);
  }

  it('submits one turn for two presses', twoPressesSubmitOneTurn);
}

describe(
  'the transaction add screen: the copy key press and the confirmation',
  transactionAddCases,
);

/**
 * Holds a promise open so a case can observe the in-flight state.
 *
 * Assumptions: a named declaration rather than an inline executor, because the lint configuration requires
 * a JSDoc block on every function expression including a promise executor.
 * @returns {void} Nothing; the promise is never settled, and the case ends before it would matter.
 */
function holdOpen(): void {
  return undefined;
}
