/**
 * @file Proves the blank marker is rendered BESIDE the government-issued identifier and INSIDE every
 * other control, so a refused field can never submit a deletion instruction the operator did not give.
 *
 * Purpose
 * -------
 * `app/cpy/CSSETATY.cpy` L18 to L27 marks a refused field two ways: it moves the error colour into the
 * field's colour subfield when the validation flag is not-OK OR blank, and moves a literal asterisk into
 * the field's OUTPUT subfield only when it is blank. `ui/src/screens/accountUpdate/index.tsx` reproduces
 * the asterisk, and for most fields writing it into the control's value is exactly right -- a blank field
 * is what the state means, and the reference reads the marker back as an unfilled field.
 *
 * What these cases exist to prevent
 * ---------------------------------
 * ⚠️ Assumptions: one field breaks that equivalence.
 * `services/account-service/src/main/java/com/carddemo/account/mapper/CustomerMapper.java` declares
 * `IDENTIFIER_REMOVAL_MARKER = "*"` and `governmentIdentifierUpdate` tests it FIRST, answering
 * `ProtectedValueUpdate.clear()` -- so a submitted asterisk on that one member DELETES the stored
 * ciphertext, where the shared never-supplied test would have preserved it. Written into the value, a
 * blank refusal on that field would leave the marker in the control for the operator's next submit to
 * carry, and `updateRequestFrom` sends the member whenever it is not empty. These cases hold the two
 * halves apart: the marker still appears for the refused field, and the value it would have been written
 * into stays empty.
 *
 * Assumptions: the hazard is also asserted on the request builder directly, not only on the rendering.
 * A rendering-only case would pass against a screen that rendered the marker beside the control AND
 * separately seeded the value, and a builder-only case would pass against a screen that never marked the
 * field at all. Neither alone distinguishes the state being prevented from a working one.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';

import { AppShell } from '../layout/AppShell';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { readAccountView, updateAccount, validateAccountUpdate } from '../api/accounts';
import type { AccountViewResponse } from '../api/accounts';
import { ApiRequestError } from '../api/client';
import type { ApiError, FieldError } from '../api/types';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { ACCOUNT_UPDATE_FIELD_LABELS, FIELD_VALIDATION_SUFFIXES } from '../messages/messages';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED,
  ACCOUNT_UPDATE_KEY_LABELS,
  AccountUpdateScreen,
  blankFormValues,
  fieldDomId,
  updateRequestFrom,
} from './accountUpdate';

/*
 * WHY : ⚠️ Refactoring Rationale: `validateAccountUpdate` is stubbed too, and it was not. The screen's
 *       ENTER turn now calls the contract's own no-write validation operation --
 *       `POST /api/v1/accounts/update/validate`, the reference's `2000-DECIDE-ACTION` show-details arm --
 *       and only that turn's verdict registers the `F5=Save` key. Left unstubbed the call reached the real
 *       transport, rejected with no configured base URL, and the action never advanced to
 *       `CHANGES_OK_NOT_CONFIRMED`; every case in this file then failed on a MISSING SAVE KEY rather than
 *       on the marker placement it exists to assert.
 */
/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 *
 * Assumptions: `isConflictFailure` is stubbed alongside the two calls because the screen imports it from
 * this same module and `classifySaveRejection` consults it before anything else. Every refusal these
 * cases arrange answers 400, which the real predicate would also report as not a conflict, so the stub
 * left at its default falsy answer reproduces the real classification rather than bypassing it.
 * @returns {Record<string, unknown>} The two transport functions plus the predicate the screen imports
 *   from the same module, which a bare pair of spies would leave undefined.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    readAccountView: vi.fn(),
    validateAccountUpdate: vi.fn(),
    updateAccount: vi.fn(),
    isConflictFailure: vi.fn(),
  };
}

vi.mock('../api/accounts', mockAccountTransportModule);

/**
 * Time allowed for one case that renders this screen and drives a turn through it.
 *
 * ⚠️ Assumptions: the allowance is scoped to the five cases that RENDER, and it is a property of this
 * mapset rather than of the work being timed. `app/bms/COACTUP.bms` declares 128 fields, every one of
 * which is a React-controlled design-system control, so a single turn commits a render of the whole form
 * -- measured at roughly one and a half seconds for the three-turn drive on its own, and observed
 * crossing the runner's five-second default while the rest of the suite occupied the same cores. The
 * drive is already reduced to one change event per field and one click per key for exactly this reason;
 * what remains is the render itself, which cannot be reduced without asserting against a screen that is
 * not this one.
 *
 * Alternatives Considered: raising `testTimeout` in `ui/vitest.config.ts`. Rejected for the reason
 * `ui/documentationGate.test.ts` records for its own allowance -- it would loosen the bound for every
 * other screen test, and a screen test that hangs should still fail in five seconds. The cost belongs to
 * these cases and the allowance is scoped to them.
 *
 * Trade-offs: the value sits well above the measured cost rather than close to it, because a timeout
 * tuned tight to one machine fails on a loaded runner for no reason a reader could act on. A genuinely
 * hung case still fails, just later.
 */
const RENDERED_CASE_TIMEOUT_MS = 30_000;

/** The identifier the read is arranged for, at the eleven digits the filter edit requires. */
const ACCOUNT_ID = '00000000011';

/** The entity tag the read answers with, which the write submits as its precondition. */
const REVISION = '1';

/** The account region the read answers with, at the scale the money contract carries. */
const ACCOUNT = {
  activeStatus: 'Y',
  openDate: '2020-01-01',
  creditLimit: '5000.00',
  expirationDate: '2030-01-01',
  cashCreditLimit: '1000.00',
  reissueDate: '2025-01-01',
  currentBalance: '250.00',
  currentCycleCredit: '0.00',
  groupId: 'DEFAULT',
  currentCycleDebit: '0.00',
} as const;

/**
 * The customer region the read answers with.
 *
 * Assumptions: both protected members arrive MASKED and neither seeds its control -- `formValuesFrom`
 * seeds the three national-identifier parts and the government-issued identifier as empty strings,
 * because a masked value is not a value the operator may resubmit. That is what makes the government
 * identifier's control empty on arrival, and therefore what makes a marker written into it the only
 * content it would carry.
 */
const CUSTOMER = {
  customerId: '000000001',
  ssnMasked: '***-**-6789',
  dateOfBirth: '1980-05-06',
  ficoCreditScore: '700',
  firstName: 'ADA',
  middleName: null,
  lastName: 'LOVELACE',
  addressLine1: '1 ANALYTICAL WAY',
  stateCode: 'NY',
  addressLine2: null,
  zipCode: '10001',
  city: 'NEW YORK',
  countryCode: 'USA',
  phoneNumber1: '(212)555-0100',
  governmentIssuedIdMasked: '****4321',
  phoneNumber2: null,
  eftAccountId: '12345678901',
  primaryCardHolderIndicator: 'Y',
} as const;

/** The pair the read answers with, in the shape the view operation publishes. */
const VIEW: AccountViewResponse = {
  accountId: ACCOUNT_ID,
  account: ACCOUNT,
  customer: CUSTOMER,
  informationMessage: null,
  returnMessage: null,
};

/**
 * The name token a refusal on the government-issued identifier would carry.
 *
 * ⚠️ Assumptions: this token is NOT drawn from `ui/src/messages/messages.ts`, and the absence is the
 * point rather than an oversight. The catalog carries a name token for every field the reference can
 * refuse, and it carries none for this one because `1200-EDIT-MAP-INPUTS` never edits it -- which is why
 * the collision this file closes is latent today. The mapset's painted label supplies the token instead,
 * trimmed of the padding the terminal used to align it, so the sentence reads as the reference's own
 * composition -- name token followed by ` must be supplied.` -- without a sentence being invented in the
 * catalog for a refusal the baseline does not emit.
 */
const GOVERNMENT_ID_REFUSAL = `${ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.governmentIssuedId
  .replace(/\s*:\s*$/u, '')
  .trimEnd()}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;

/** The sentence a refusal on the first name carries, composed the way the reference composes it. */
const FIRST_NAME_REFUSAL = `${ACCOUNT_UPDATE_FIELD_LABELS.FIRST_NAME}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;

/** The sentence a refusal on the first part of the national identifier carries. */
const SSN_PART_REFUSAL = `${ACCOUNT_UPDATE_FIELD_LABELS.SSN_FIRST_3_CHARS}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;

/**
 * Builds the normalised failure a 400 carrying one blank field refusal arrives as.
 *
 * Assumptions: the whole eleven-member problem document is supplied and the failure is a real
 * `ApiRequestError`, not a plain `Error`. `classifySaveRejection` reaches its field-refusal arm only
 * through `isApiRequestError`, and the marking is driven entirely by `problem.fieldErrors`, so a
 * short-cut failure would exercise the generic fallback and mark nothing.
 * @param {string} field - The request member the service named.
 * @param {string} message - The sentence the service sent for it.
 * @returns {ApiRequestError} The failure the transport would have thrown.
 */
function blankRefusal(field: string, message: string): ApiRequestError {
  const fieldError: FieldError = { field, state: 'BLANK', message };
  const problem: ApiError = {
    code: 'CARDDEMO-0400',
    secondaryCode: 'ACCT-UPDATE',
    message,
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId: '00000000-0000-4000-8000-0000000000ff',
    path: '/api/v1/accounts/update',
    timestamp: '2026-01-01T00:00:00.000000Z',
    fieldErrors: [fieldError],
    abend: null,
  };

  return new ApiRequestError('PROBLEM', 400, problem, 'PROBLEM 400 CARDDEMO-0400');
}

/** Restores the spies between cases. */
function resetSpies(): void {
  vi.mocked(readAccountView).mockReset();
  vi.mocked(validateAccountUpdate).mockReset();
  vi.mocked(updateAccount).mockReset();
}

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: the mapset pads its labels to the width the terminal painted -- the government
 * identifier's is `LENGTH=30` with four interior and one trailing space -- and the screen renders them
 * verbatim under Transformation Rule T8. The DOM collapses those runs on display, so the expectation is
 * collapsed too rather than the label being trimmed at its declaration, which would make this file the
 * source of truth instead of the mapset.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Returns the legend control that invokes one function key.
 *
 * Assumptions: the control is looked up INSIDE the function-key region rather than by label alone,
 * because `PfKeyBar` renders a `nav` with an accessible name -- role `navigation`, not `region` -- and
 * this screen additionally renders the save label a second time, on the confirmation control. A global
 * label lookup would find both.
 * @param {string} label - The legend label, verbatim from the mapset's row-24 literal.
 * @returns {HTMLElement} The legend control bearing that label.
 * @throws {Error} If the region holds no control with that label, so a renamed label fails loudly
 *   rather than silently exercising nothing.
 */
function legendControl(label: string): HTMLElement {
  const region = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const found = Array.from(region.querySelectorAll('button')).find(
    /**
     * Reports whether one control's text is the label sought, with interior runs collapsed.
     * @param {HTMLButtonElement} control - Candidate control.
     * @returns {boolean} Whether its text matches.
     */
    (control: HTMLButtonElement): boolean => collapse(control.textContent ?? '') === label,
  );
  if (found === undefined) {
    throw new Error(`no function-key control labelled ${label}`);
  }
  return found;
}

/**
 * Returns the control one form member is edited through.
 * @param {string} label - The mapset's painted label for it.
 * @returns {HTMLElement} The control bearing that label.
 */
function control(label: string): HTMLElement {
  return screen.getByLabelText(collapse(label));
}

/**
 * Sets one control's whole value in a single change.
 *
 * ⚠️ Assumptions: the value is set with ONE change event rather than typed keystroke by keystroke, and the
 * reason is the size of this particular form. Every control is React-controlled and the mapset declares
 * 128 fields, so a keystroke-level driver re-renders the whole form once per character and an eleven-digit
 * identifier alone costs eleven full renders -- enough to carry these cases past the runner's per-case
 * limit under a parallel suite. The subject of every case here is what the screen RENDERS once a refusal
 * has arrived, not how the entry was produced, so collapsing the entry to one change removes cost from
 * something none of them asserts.
 *
 * Trade-offs: a case whose subject IS the interaction must keep the keystroke-level driver, because a
 * single change event bypasses the per-character coercion this screen applies to its numeric fields. That
 * coercion is asserted by the module's own function-level cases against `digitsOnly`, so nothing here is
 * left unexercised by the trade.
 * @param {string} label - The mapset's painted label for the control.
 * @param {string} value - The whole value to set.
 * @returns {void} Completion is the change event having been dispatched.
 */
function setField(label: string, value: string): void {
  fireEvent.change(control(label), { target: { value } });
}

/**
 * Activates one function key from its legend control.
 *
 * ⚠️ Assumptions: the activation is a plain click event rather than the pointer sequence a simulated
 * operator produces, for the same reason {@link setField} sets a whole value at once. Each turn this file
 * drives re-renders a form of 128 controls, and the pointer sequence costs several of those renders per
 * activation on top of the one the turn itself causes -- enough that a three-turn drive intermittently
 * crossed the runner's per-case limit when the suite ran in parallel, while passing in a third of the
 * limit on its own. The subject of every case here is what the screen RENDERS once a refusal has arrived,
 * so the cost belongs to nothing any of them asserts.
 *
 * Trade-offs: a click event skips the hover, focus and pointer-down steps a real activation performs, so
 * a case whose subject IS the activation must keep the simulated operator. None here is: the function-key
 * dispatch itself is asserted by `ui/src/layout/usePfKeys` and by the sibling screens' own key cases.
 * @param {string} label - The legend label, verbatim from the mapset's row-24 literal.
 * @returns {void} Completion is the handler having been invoked.
 */
function activate(label: string): void {
  fireEvent.click(legendControl(label));
}

/**
 * Renders the screen and drives the opening read, leaving it on the show-details turn.
 *
 * Assumptions: the read is driven through the filter and the Enter key rather than injected, because the
 * screen fetches nothing on mount -- `1210-EDIT-ACCOUNT` is the one edit the reference performs before a
 * read, and the not-fetched action is the only one that unprotects the filter.
 * @returns {Promise<void>} Resolves once the read has seeded the form.
 */
async function renderAndFetch(): Promise<void> {
  vi.mocked(readAccountView).mockResolvedValue({ account: VIEW, revision: REVISION });
  render(
    <MemoryRouter initialEntries={['/account/update']}>
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
        <AccountUpdateScreen />
      </AppShell>
    </MemoryRouter>,
  );

  setField(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.accountId, ACCOUNT_ID);
  activate(ACCOUNT_UPDATE_KEY_LABELS.ENTER);
  await waitFor(
    /**
     * Waits for the read to have seeded the form.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(control(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.firstName)).toHaveValue(
        CUSTOMER.firstName,
      );
    },
  );
}

/**
 * Drives the screen to the turn a refused write lands on, with the refusal already arranged.
 *
 * Assumptions: the whole three-turn sequence is driven rather than the refusal being injected into
 * state, because the field marking is a property of the SAVE path -- read, validate, confirm, refuse --
 * and reaching it any other way would assert a rendering the screen cannot actually arrive at. The write
 * key is registered only in the confirmation action, which is why the validating turn cannot be skipped.
 * @param {ApiRequestError} refusal - The failure the write is arranged to reject with.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function driveToRefusedWrite(refusal: ApiRequestError): Promise<void> {
  await renderAndFetch();

  setField(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.lastName, 'BABBAGE');
  /*
   * Assumptions: the validating turn answers a clean verdict, which is what advances the action to
   * `CHANGES_OK_NOT_CONFIRMED` and therefore what registers the save key the next line presses. The
   * verdict is awaited before the press, because the key does not exist until it has been applied.
   */
  vi.mocked(validateAccountUpdate).mockResolvedValue({
    fieldErrors: [],
    message: null,
    inputError: false,
    noChangesFound: false,
  });
  activate(ACCOUNT_UPDATE_KEY_LABELS.ENTER);
  await waitFor(
    /**
     * Waits for the validating turn to have registered the save key.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(validateAccountUpdate).toHaveBeenCalledTimes(1);
    },
  );
  await waitFor(
    /**
     * Waits for the save key to appear in the legend.
     * @returns {void} Nothing; the lookup throws until it holds.
     */
    (): void => {
      legendControl(ACCOUNT_UPDATE_KEY_LABELS.PFK05);
    },
  );

  vi.mocked(updateAccount).mockRejectedValue(refusal);
  /*
   * WHY : ⚠️ Refactoring Rationale: the save key OPENS the confirmation and the approval inside it is what
   *       writes, where this file pressed the key and expected the write. Both entry points now lead
   *       through one gate: the key used to write directly while the pointer control beside the form asked
   *       for confirmation, so the keyboard bypassed a gate the pointer could not. Pressing the key alone
   *       therefore left `updateAccount` uncalled and every case here failed on the write count rather than
   *       on the marker placement it exists to assert.
   * WHY : Assumptions: the approval is found INSIDE the open gate rather than by its label, because the
   *       label is the mapset's own `F5=Save` and three controls carry it at once -- the approval, the
   *       trigger it hangs off, and the legend entry. `ui/src/screens/accountUpdate/accountUpdate.test.tsx`
   *       resolves the same ambiguity the same way.
   */
  activate(ACCOUNT_UPDATE_KEY_LABELS.PFK05);
  const gate = await waitFor(
    /**
     * Waits for the confirmation gate to open.
     * @returns {HTMLElement} The gate's own container.
     */
    (): HTMLElement => {
      const found = document.querySelector('.ant-popconfirm');
      expect(found, 'the save key must open the confirmation gate').not.toBeNull();

      return found as HTMLElement;
    },
  );
  const approval = Array.from(gate.querySelectorAll('button')).find(
    /**
     * Keeps the control inside the gate whose text is the save legend.
     * @param {HTMLButtonElement} candidate - One control inside the gate.
     * @returns {boolean} `true` when it is the approval.
     */
    (candidate: HTMLButtonElement): boolean =>
      (candidate.textContent ?? '').includes(ACCOUNT_UPDATE_KEY_LABELS.PFK05),
  );
  expect(approval, 'the gate must offer the approval control').toBeDefined();
  fireEvent.click(approval as HTMLElement);
  await waitFor(
    /**
     * Waits for the refusal to have been dispatched and rendered.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(updateAccount).toHaveBeenCalledTimes(1);
    },
  );
  await screen.findAllByText(refusal.problem.message ?? '');
}

/**
 * Proves a refused government identifier is marked without the marker entering its value.
 *
 * ⚠️ Assumptions: three things are asserted together because each alone admits the defect. The value
 * being empty alone would pass against a screen that marked nothing; the marker being present alone would
 * pass against a screen that also seeded the value; and the marker being INSIDE the control's own affix
 * wrapper is what keeps the reference's placement -- an asterisk floated somewhere else on the row would
 * satisfy a bare text query while no longer reading as belonging to the field.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksTheGovernmentIdentifierBesideItsControl(): Promise<void> {
  await driveToRefusedWrite(blankRefusal('governmentIssuedId', GOVERNMENT_ID_REFUSAL));

  const refused = control(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.governmentIssuedId);
  expect(refused).toHaveValue('');

  const wrapper = refused.closest('.ant-input-affix-wrapper');
  expect(wrapper).not.toBeNull();
  expect(wrapper?.textContent).toContain(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * Proves the marker's placement leaves the control reachable by the identifier the cursor idiom uses.
 *
 * ⚠️ Assumptions: rendering the marker as an affix changes the control's rendered root from a bare input
 * to a wrapping element, and the screen's cursor effect finds its target with
 * `document.getElementById(fieldDomId(field))` -- the browser's form of `MOVE -1 TO <field>L`. If the
 * design system put the stated identifier on the wrapper instead of the input, that lookup would return a
 * non-focusable element and the reference's cursor placement would be lost silently on every field that
 * ever gains an affix. So the identifier is asserted to resolve to the INPUT, and focusing it asserted to
 * take.
 *
 * ⚠️ Assumptions: what is NOT asserted is that the screen's own effect focuses this field, because it
 * deliberately does not. `2000-DECIDE-ACTION`'s cursor table at `app/cbl/COACTUPC.cbl` L3009 to L3167 has
 * no arm for `ACSGOVTL` -- the program declares no validation flag for that field and never refuses it --
 * so `CURSOR_ORDER` omits it and a refusal naming it alone places no cursor at all. That omission is
 * faithful and is the same fact that makes the marker collision latent, which is why the reachability of
 * the control is asserted directly rather than through an effect the reference gives it no reason to run.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsTheStatedIdentifierOnTheInputBesideTheMarker(): Promise<void> {
  await driveToRefusedWrite(blankRefusal('governmentIssuedId', GOVERNMENT_ID_REFUSAL));

  const located = document.getElementById(fieldDomId('governmentIssuedId'));
  expect(located).toBe(control(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.governmentIssuedId));
  expect(located?.tagName).toBe('INPUT');
}

/**
 * Proves the marker-bearing control keeps its affix element when nothing is refused.
 *
 * ⚠️ Assumptions: the element is asserted present in the UNREFUSED state, which is the only way to catch
 * the failure mode the placement introduces. The design system warns that adding or removing an affix
 * while a control is focused makes it lose focus, because the affix changes the control's rendered root --
 * so an affix that appeared with the refusal would move the cursor off the field the operator was
 * correcting, on the one field the screen renders this way. Holding the element in both states and
 * changing only its text is what avoids that, and only a case that looks at the state with NO refusal can
 * tell the two implementations apart.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function keepsTheAffixElementWhenNothingIsRefused(): Promise<void> {
  await renderAndFetch();

  const unrefused = control(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.governmentIssuedId);
  const wrapper = unrefused.closest('.ant-input-affix-wrapper');
  expect(wrapper).not.toBeNull();
  expect(wrapper?.textContent).not.toContain(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * Proves the marker is written INTO the value for an ordinary field.
 *
 * Assumptions: the first name is the ordinary case and the choice is deliberate -- it is an editable
 * field the reference refuses when blank, and its mapping carries no marker meaning at all, so writing
 * the asterisk into its value is exactly the reference's own behaviour and must not be lost while the one
 * exception is being handled.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function writesTheMarkerIntoAnOrdinaryFieldValue(): Promise<void> {
  await driveToRefusedWrite(blankRefusal('firstName', FIRST_NAME_REFUSAL));

  expect(control(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.firstName)).toHaveValue(
    FIELD_ERROR_TOKENS.blankMarker,
  );
}

/**
 * Proves a national-identifier part is an ordinary field despite also being protected.
 *
 * ⚠️ Assumptions: this is the case that keeps the exception NARROW. `CustomerMapper` gives the national
 * identifier only two outcomes -- preserve when never supplied, replace otherwise -- so the marker folds
 * into never-supplied there exactly as the withdrawn rationale claimed, and treating every protected
 * member as an exception would drop the reference's in-field marker from three controls that never needed
 * it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function writesTheMarkerIntoANationalIdentifierPart(): Promise<void> {
  await driveToRefusedWrite(blankRefusal('ssnPart1', SSN_PART_REFUSAL));

  expect(control(ACCOUNT_UPDATE_FIELD_LABELS.SSN_FIRST_3_CHARS)).toHaveValue(
    FIELD_ERROR_TOKENS.blankMarker,
  );
}

/**
 * Proves a marker left in the government identifier WOULD be submitted.
 *
 * ⚠️ Assumptions: this is the hazard itself, asserted on the builder rather than inferred. The member is
 * spread in whenever the operator typed something, and the marker is something, so the request carries it
 * -- and the mapper's first test then reads it as an instruction to clear a stored value. Without this
 * case the rendering cases would look like a style preference rather than the closure of a data-loss
 * path.
 * @returns {void} Completion is the assertion.
 */
function sendsAMarkerLeftInTheGovernmentIdentifier(): void {
  const submitted = updateRequestFrom({
    ...blankFormValues(),
    governmentIssuedId: FIELD_ERROR_TOKENS.blankMarker,
  });

  expect(submitted.governmentIssuedId).toBe(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * Proves an untouched government identifier is omitted from the request entirely.
 *
 * Assumptions: the ABSENCE of the member is asserted, not an empty value, because
 * `exactOptionalPropertyTypes` makes the two different states and only the absent one means preserve to
 * the service. This is the state the rendering now guarantees a blank refusal leaves behind.
 * @returns {void} Completion is the assertion.
 */
function omitsAnUntouchedGovernmentIdentifier(): void {
  const submitted = updateRequestFrom(blankFormValues());

  expect('governmentIssuedId' in submitted).toBe(false);
}

/** Registers the cases that assert where the blank marker is rendered. */
function markerPlacementCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it(
    'marks a refused government identifier beside its control, not in its value',
    marksTheGovernmentIdentifierBesideItsControl,
    RENDERED_CASE_TIMEOUT_MS,
  );
  it(
    'keeps the stated identifier on the input beside the marker',
    keepsTheStatedIdentifierOnTheInputBesideTheMarker,
    RENDERED_CASE_TIMEOUT_MS,
  );
  it(
    'keeps the affix element when nothing is refused',
    keepsTheAffixElementWhenNothingIsRefused,
    RENDERED_CASE_TIMEOUT_MS,
  );
  it(
    'writes the marker into an ordinary field value',
    writesTheMarkerIntoAnOrdinaryFieldValue,
    RENDERED_CASE_TIMEOUT_MS,
  );
  it(
    'writes the marker into a national identifier part',
    writesTheMarkerIntoANationalIdentifierPart,
    RENDERED_CASE_TIMEOUT_MS,
  );
}

/** Registers the cases that assert what a marker left in a value would submit. */
function markerSubmissionCases(): void {
  it(
    'would submit a marker left in the government identifier',
    sendsAMarkerLeftInTheGovernmentIdentifier,
  );
  it('omits an untouched government identifier', omitsAnUntouchedGovernmentIdentifier);
}

describe('the account update blank marker placement', markerPlacementCases);
describe('the account update request and the removal marker', markerSubmissionCases);
