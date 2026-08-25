/**
 * @file Component tests for the account-update screen's correction path, turn sequencing and display.
 *
 * Purpose
 * -------
 * Cover the six behaviours a review found defective on this screen. Every one of them is invisible in
 * the type system, and five of the six are invisible in a single-turn test because they are about what
 * happens on the SECOND turn or while a first one is still outstanding:
 *
 * - a refused-blank field must stay correctable: the reference's asterisk marker may not become part of
 *   the control's value, and editing a refused field must clear its refusal;
 * - Enter must obtain an authoritative verdict before offering the save key, rather than promising
 *   `Changes validated.Press F5 to save` on the strength of two local checks;
 * - the account filter must require exactly eleven digits, which is the only width the reference reads;
 * - the five amounts must render through the baseline's `+ZZZ,ZZZ,ZZZ.99` edit mask and must be
 *   submitted with that decoration removed, because the mask is painted into an EDITABLE control here;
 * - the two protected identifiers must show their stored masked state beside their blank replacement
 *   controls, so an operator can see what is on file before deciding to replace it;
 * - an outcome for a superseded turn must not be applied, and the keys that start a turn must stand
 *   down while one is outstanding.
 *
 * How the races are driven
 * ------------------------
 * Assumptions: the transport is substituted with DEFERRED promises the case resolves by hand. A race is
 * an ORDERING, so the ordering has to be what the test controls: a case starts a turn, performs the
 * action that should supersede it, and only then releases the response. Resolving first and asserting
 * afterwards would pass against the defective code, because the defect is not that the response is
 * wrong -- it is that a correct response for a superseded question is applied.
 *
 * Why the screen is mounted inside the shell
 * -----------------------------------------
 * Assumptions: `ui/src/layout/AppShell.tsx` is the authenticated layout route and this screen delegates
 * its title band and its key legend to it, so rendering the screen alone would leave the delegated
 * zones unpainted and the legend buttons absent. The shell is mounted for the same reason
 * `ui/src/screens/accountView/accountViewTurns.test.tsx` mounts it.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AppShell } from '../../layout/AppShell';
import { PF_KEY_BAR_REGION_LABEL } from '../../layout/PfKeyBar';
import type {
  AccountDetail,
  AccountUpdateValidationResponse,
  CustomerDetail,
  SensitiveAccountUpdateRequest,
} from '../../api/accounts';
import type { FieldError } from '../../api/types';
import { FIELD_ERROR_TOKENS } from '../../theme/tokens';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time. `isConflictFailure` is stubbed to a constant rather than spied on, because no case
 * here drives a precondition conflict and a spy returning `undefined` would be coerced to the same
 * answer with no record of why.
 * @returns {Record<string, unknown>} The four transport members this screen imports.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    readAccountView: vi.fn(),
    updateAccount: vi.fn(),
    validateAccountUpdate: vi.fn(),
    /**
     * Reports that no failure is a precondition conflict.
     * @returns {boolean} Always `false`; no case here drives a conflict.
     */
    isConflictFailure: (): boolean => false,
  };
}

vi.mock('../../api/accounts', mockAccountTransportModule);

/**
 * The five stored amounts, chosen so the mask's sign, suppression and separator branches all run.
 *
 * Assumptions: `currentBalance` is negative and `currentCycleCredit` is zero, because those are the two
 * values whose masked rendering differs most from the wire text -- a leading sign that is not `+`, and
 * an integer part suppressed to blanks. A table of positive four-figure amounts would render correctly
 * under a mask that never suppressed anything.
 */
const ACCOUNT: AccountDetail = {
  activeStatus: 'Y',
  openDate: '2020-01-01',
  creditLimit: '5000.00',
  expirationDate: '2026-01-01',
  cashCreditLimit: '250.00',
  reissueDate: '2024-01-01',
  currentBalance: '-1234.56',
  currentCycleCredit: '0.00',
  groupId: 'DEFAULT',
  currentCycleDebit: '999999999.99',
};

/** A customer with every declared member present, so no render path is skipped. */
const CUSTOMER: CustomerDetail = {
  customerId: '000000011',
  ssnMasked: '***-**-6789',
  dateOfBirth: '1980-05-05',
  ficoCreditScore: '750',
  firstName: 'PAUL',
  middleName: null,
  lastName: 'BUCK',
  addressLine1: '1 MAIN ST',
  stateCode: 'NY',
  addressLine2: null,
  zipCode: '10001',
  city: 'NEW YORK',
  countryCode: 'USA',
  phoneNumber1: '(212)555-0100',
  governmentIssuedIdMasked: '*****4321',
  phoneNumber2: null,
  eftAccountId: '12345678901',
  primaryCardHolderIndicator: 'Y',
};

/** Eleven digits, the only width the reference's own edit accepts. */
const ACCOUNT_ID = '00000000011';

/** Ten digits: a width the screen must refuse locally, without issuing a read. */
const TOO_SHORT_ACCOUNT_ID = '0000000001';

/** The masked renderings the five amounts must display, exact to all fifteen positions. */
const MASKED_AMOUNTS = {
  creditLimit: '+      5,000.00',
  cashCreditLimit: '+        250.00',
  currentBalance: '-      1,234.56',
  currentCycleCredit: '+           .00',
  currentCycleDebit: '+999,999,999.99',
} as const;

/** The read result the transport publishes for a successful fetch. */
/**
 * The sentence the service latches when the account was located and its customer was not.
 *
 * Assumptions: it is `DID-NOT-FIND-CUST-IN-CUSTDAT` at `app/cbl/COACTUPC.cbl` L501 verbatim, which is
 * what `AccountViewService` publishes on that arm, so a case asserting it is asserting the reference's
 * own wording rather than one invented for the target.
 */
const CUSTOMER_MISS_SENTENCE = 'Did not find associated customer in master file';

const READ_RESULT = {
  account: {
    accountId: ACCOUNT_ID,
    account: ACCOUNT,
    customer: CUSTOMER,
    informationMessage: null,
    returnMessage: null,
  },
  revision: 'W/"7"',
} as const;

/**
 * The answer a committed write returns, in the shape the write operation publishes.
 *
 * Assumptions: it is a SEPARATE fixture from the read's answer rather than a reuse of it, because the
 * two operations publish different shapes -- the write's answer additionally carries the refused-field
 * array and the two message lines it reports itself through -- so a case releasing the read's answer
 * from the write's promise would be typing a response the transport cannot produce.
 *
 * Assumptions: the revision ADVANCES, because a committed write is what makes the one the read handed
 * back stale. Echoing the read's revision would leave the fixture unable to show that.
 */
const WRITE_RESULT = {
  account: {
    accountId: ACCOUNT_ID,
    informationMessage: null,
    returnMessage: null,
    fieldErrors: [],
    account: ACCOUNT,
    customer: CUSTOMER,
  },
  revision: 'W/"8"',
} as const;

/**
 * A verdict that accepts the submission, which is what advances the screen to the save key.
 *
 * Assumptions: the shape carries exactly the FOUR members the published contract declares. The service
 * record additionally derives a confirmable predicate from the two flags, but that accessor is not
 * serialised, so a fixture declaring it would not be a response the transport can produce.
 */
const ACCEPTING_VERDICT: AccountUpdateValidationResponse = {
  inputError: false,
  noChangesFound: false,
  message: null,
  fieldErrors: [],
};

/** The refusal a blank credit limit draws, in the shape the service publishes it. */
/**
 * The character the reference writes into a blank-refused field.
 *
 * Assumptions: taken from the theme's own token rather than written as `'*'`, so a case cannot pass
 * against a screen painting a different character from the one `app/cpy/CSSETATY.cpy` L23-L26 moves into
 * the field.
 */
const BLANK_MARKER = FIELD_ERROR_TOKENS.blankMarker;

const BLANK_CREDIT_LIMIT: FieldError = {
  field: 'creditLimit',
  state: 'BLANK',
  message: 'Credit Limit must be supplied.',
};

/** A verdict that refuses the submission and names one blank field. */
const REFUSING_VERDICT: AccountUpdateValidationResponse = {
  inputError: true,
  noChangesFound: false,
  message: 'Please correct the field marked.',
  fieldErrors: [BLANK_CREDIT_LIMIT],
};

/** The refusal an unusable cash limit draws, which marks the field without the blank marker. */
const UNUSABLE_CASH_LIMIT: FieldError = {
  field: 'cashCreditLimit',
  state: 'NOT_OK',
  message: 'Cash credit limit is not valid.',
};

/**
 * The refused cash limit, chosen so that re-masking it would visibly change it.
 *
 * Assumptions: this value matters and an arbitrary one will not do. It is a WELL-FORMED amount, so the
 * mask would reformat it to `+           .00` -- which is what makes the echo assertion able to fail. An
 * unparseable entry such as `12.3.4` passes through the mask unchanged, so a case built on one would be
 * satisfied by an implementation that re-masked every field and ignored the per-field flag entirely.
 * That was measured: with `12.3.4`, perturbing the flag test to re-mask everything left this case green.
 */
const REFUSED_CASH_LIMIT_ENTRY = '0.00';

/** A verdict that refuses one amount and accepts every other field. */
const REFUSES_ONE_AMOUNT: AccountUpdateValidationResponse = {
  inputError: true,
  noChangesFound: false,
  message: 'Please correct the field marked.',
  fieldErrors: [UNUSABLE_CASH_LIMIT],
};

/** A promise whose resolution and rejection the case controls, for any published shape. */
interface Deferred<T> {
  /** The promise handed to the screen. */
  readonly promise: Promise<T>;
  /** Releases the response. */
  readonly release: (value: T) => void;
  /** Fails the request. */
  readonly refuse: (reason: unknown) => void;
}

/**
 * Creates a promise whose settlement the case controls.
 *
 * Assumptions: `let` bindings use definite assignment rather than a no-op initialiser, because a
 * promise executor runs synchronously so both are assigned before this returns.
 * @returns {Deferred<T>} The promise and the two functions that settle it.
 */
function deferred<T>(): Deferred<T> {
  let release!: (value: T) => void;
  let refuse!: (reason: unknown) => void;
  const promise = new Promise<T>(
    /**
     * Captures both settlement functions.
     * @param {(value: T) => void} resolve - The promise's resolver.
     * @param {(reason: unknown) => void} reject - The promise's rejecter.
     * @returns {void} Nothing.
     */
    (resolve: (value: T) => void, reject: (reason: unknown) => void): void => {
      release = resolve;
      refuse = reject;
    },
  );
  return { promise, release, refuse };
}

/**
 * Matcher options that compare text EXACTLY, without whitespace normalisation.
 *
 * Assumptions: the default normaliser collapses interior whitespace runs, which is precisely what the
 * edit mask emits -- `+      5,000.00` would be compared as `+ 5,000.00`, so the PAD WIDTH would go
 * unasserted and a mask that grouped correctly but padded to the wrong column would pass.
 */
const EXACT_TEXT = {
  /**
   * Returns the text unchanged.
   * @param {string} value - The candidate element's text content.
   * @returns {string} That same text, uncollapsed and untrimmed.
   */
  normalizer: (value: string): string => value,
};

/**
 * Renders the screen inside the shell at its own route.
 * @returns {Promise<void>} Resolves once the screen is mounted.
 */
async function renderScreen(): Promise<void> {
  const { AccountUpdateScreen } = await import('./index');

  render(
    <MemoryRouter initialEntries={[{ pathname: '/account/update' }]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/account/update" element={<AccountUpdateScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Reads one of the screen's controls by the identifier the field renderer assigns it.
 *
 * Assumptions: the control is located by DOM identifier rather than by label, because this mapset
 * paints one shared label over the three parts of each split group -- so a label query would be
 * ambiguous for eleven of the forty controls and would need a different lookup per field.
 * @param {string} field - The form member's name, as the screen's own identifier helper spells it.
 * @returns {HTMLInputElement} That field's control.
 * @throws {Error} When no control carries that identifier, which is a rename this test must report.
 */
function control(field: string): HTMLInputElement {
  const element = document.getElementById(`carddemo-account-update-${field}`);
  if (element === null) {
    throw new Error(`No control is rendered for the field named ${field}.`);
  }
  return element as HTMLInputElement;
}

/**
 * Reports whether the reference's blank marker is rendered beside one field's control.
 *
 * Assumptions: the marker is looked for inside the control's own affix wrapper rather than anywhere on
 * the screen, because a bare asterisk is not a unique string in a form of forty fields.
 *
 * Assumptions: this is asserted INSTEAD of the refusal sentence's absence, and the reason is a real
 * measurement rather than a preference. The design system animates its help text OUT: the moment the
 * refusal is cleared, React unmounts it and `rc-motion` keeps the node in the tree carrying
 * `ant-form-show-help-item-leave` and `height: 0px` until a transition end that jsdom never fires. So a
 * `not.toBeInTheDocument()` assertion on the sentence fails against CORRECT code -- it was measured
 * doing exactly that -- while reporting a defect that is not there. The marker has no leave animation,
 * so it unmounts synchronously, and it is rendered from the same `state === 'BLANK'` condition the
 * sentence is; it is therefore a faithful and stable witness for the same state.
 * @param {string} field - The form member's name.
 * @returns {boolean} `true` when the marker is present beside that control.
 */
function hasBlankMarker(field: string): boolean {
  const wrapper = control(field).closest('.ant-input-affix-wrapper');
  if (wrapper === null) {
    return false;
  }
  return (wrapper.querySelector('.ant-input-suffix')?.textContent ?? '') === '*';
}

/**
 * Types an account identifier into the filter and presses Enter.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @param {string} accountId - The identifier to type.
 * @returns {Promise<void>} Resolves once the turn has been dispatched.
 */
async function submitFilter(
  user: ReturnType<typeof userEvent.setup>,
  accountId: string,
): Promise<void> {
  await user.click(control('accountId'));
  await user.type(control('accountId'), accountId);
  await user.keyboard('{Enter}');
}

/**
 * Reads an account and waits for the form to be seeded from it.
 *
 * Assumptions: the read is resolved through `act` so every state change it causes is flushed before the
 * case continues, which is what lets a later assertion read the seeded form synchronously.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @returns {Promise<void>} Resolves once the record is on screen.
 */
async function readOneAccount(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  vi.mocked(readAccountView).mockResolvedValueOnce(READ_RESULT);

  await submitFilter(user, ACCOUNT_ID);
  await act(
    /**
     * Drains the microtask queue so the seeded form is committed.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Dispatches a read whose answer carries no entity tag, which is the customer-master miss.
 *
 * ⚠️ Assumptions: the fixture is the one arm the service answers WITHOUT a revision -- the account row
 * was located and the customer master holds no matching row, so `customer` is null and the response
 * carries the reference's own miss sentence. It is the answer this screen used to seed an editable form
 * from, and this helper exists so both cases below arrange it identically.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @param {string} accountId - The identifier to submit.
 * @returns {Promise<void>} Resolves once the answer has been applied.
 */
async function readAnAccountWithNoCustomer(
  user: ReturnType<typeof userEvent.setup>,
  accountId: string,
): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  vi.mocked(readAccountView).mockResolvedValueOnce({
    account: {
      accountId,
      account: ACCOUNT,
      customer: null,
      informationMessage: null,
      returnMessage: CUSTOMER_MISS_SENTENCE,
    },
    revision: null,
  });

  await submitFilter(user, accountId);
  await act(
    /**
     * Drains the microtask queue so the refusal is committed.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Resets every transport spy so no case inherits another's queued response.
 * @returns {Promise<void>} Resolves once the spies are reset.
 */
async function resetTransport(): Promise<void> {
  const { readAccountView, updateAccount, validateAccountUpdate } =
    await import('../../api/accounts');
  vi.mocked(readAccountView).mockReset();
  vi.mocked(updateAccount).mockReset();
  vi.mocked(validateAccountUpdate).mockReset();
}

/**
 * A blank refusal marks the field, and the marker never becomes the SUBMITTED value.
 *
 * WHY : ⚠️ Refactoring Rationale: the marker is asserted IN the control's displayed value and beside it
 *       only for the one field that must not carry it, where this case asserted the typed text survived
 *       on screen and the marker sat in an affix. The reference decides between the two:
 *       `app/cpy/CSSETATY.cpy` L23-L26 moves `'*'` into the field's own OUTPUT subfield, so the terminal
 *       shows the asterisk IN the field and the previous content is gone from the glass -- and
 *       `MARKER_BEARING_FIELDS` in the screen records the single exception, the government identifier,
 *       where a submitted asterisk would instruct the service to delete a stored value.
 * WHY : Assumptions: what this case really guards is that the marker is a rendering and not data, and
 *       that is now asserted DIRECTLY rather than inferred from the control's text: a second submission
 *       is made and the payload it carries is read. The displayed asterisk is computed for the render
 *       while the form's own value is untouched, so the second turn submits exactly what the first did.
 *       That is the assertion the note below the defect was reaching for -- "the submitted value silently
 *       acquired a character the screen never showed" -- and reading the payload states it outright.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksABlankFieldWithoutSubmittingTheMarker(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  vi.mocked(validateAccountUpdate).mockResolvedValue(REFUSING_VERDICT);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('creditLimit'));
  await user.type(control('creditLimit'), '1');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  expect(screen.getByText(BLANK_CREDIT_LIMIT.message)).toBeInTheDocument();
  expect(control('creditLimit')).toHaveValue(BLANK_MARKER);
  expect(hasBlankMarker('creditLimit')).toBe(false);

  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the second submission is dispatched.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  const submissions = vi.mocked(validateAccountUpdate).mock.calls;
  expect(submissions.length).toBeGreaterThan(1);
  const resubmitted = submissions[submissions.length - 1]?.[0] as { creditLimit?: string };
  /*
   * Assumptions: the resubmitted member is compared against the value the FORM holds rather than against
   * the two characters typed, because a completed turn re-masks the five money fields -- the reference
   * re-sends each with its `PICOUT` edit applied. What matters here is that it carries no marker: the
   * asterisk on the glass is a rendering of a refusal and never a value the screen would send.
   */
  expect(resubmitted.creditLimit).not.toContain(BLANK_MARKER);
  expect(resubmitted.creditLimit).toBe('1.00');
}

/**
 * Editing a refused field clears its refusal and records exactly what was typed over the marker.
 *
 * WHY : ⚠️ Refactoring Rationale: the expected value is `'9'` where it was `'19'`, and the difference is
 *       the marker's position rather than a lost keystroke. The refused field displays `'*'` -- the
 *       reference writes the asterisk into the field's own OUTPUT subfield at
 *       `app/cpy/CSSETATY.cpy` L23-L26 -- so the character the operator types lands where the asterisk
 *       was, exactly as it does on a terminal whose cursor sits at the start of the field. `'19'` is what
 *       a design that painted the marker BESIDE the control would produce, and `'*9'` is what a design
 *       that never cleared the refusal would produce; neither is the delivered one.
 * WHY : Assumptions: the marker's ABSENCE afterwards is what makes the case about correction rather than
 *       about coercion. The screen drops a field's refusal on its first edit, precisely so a marked field
 *       is correctable, and this asserts that the drop happened rather than that the digit filter removed
 *       an asterisk.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function clearsARefusalWhenTheFieldIsCorrected(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  vi.mocked(validateAccountUpdate).mockResolvedValueOnce(REFUSING_VERDICT);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('creditLimit'));
  await user.type(control('creditLimit'), '1');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
  await user.type(control('creditLimit'), '9');

  expect(control('creditLimit')).toHaveValue('9');
  expect(control('creditLimit').value).not.toContain(BLANK_MARKER);
}

/**
 * Enter obtains a verdict from the service and does not advance when it refuses.
 *
 * Assumptions: the absence of the save key is what proves the screen did not advance, because the save
 * key is registered only in the confirmation action. Asserting the refusal sentence alone would not: the
 * defect reported no sentence AND advanced, so a case that only checked for the advance prompt's absence
 * would be satisfied by any refusal handling at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function doesNotAdvanceWhenTheServiceRefuses(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  vi.mocked(validateAccountUpdate).mockResolvedValueOnce(REFUSING_VERDICT);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('city'));
  await user.type(control('city'), 'ALBANY');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  const { ACCOUNT_UPDATE_KEY_LABELS } = await import('./index');
  expect(vi.mocked(validateAccountUpdate)).toHaveBeenCalledTimes(1);
  expect(
    screen.queryByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK05 }),
  ).not.toBeInTheDocument();
}

/**
 * Enter advances to the confirmation only once the service has accepted the submission.
 *
 * Assumptions: the save key's PRESENCE is the assertion, and the validation call is asserted alongside
 * it. Under the defect the key appeared with no call at all, so the pair distinguishes advancing on a
 * verdict from advancing on nothing.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function advancesOnlyAfterTheServiceAccepts(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  vi.mocked(validateAccountUpdate).mockResolvedValueOnce(ACCEPTING_VERDICT);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('city'));
  await user.type(control('city'), 'ALBANY');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  const { ACCOUNT_UPDATE_KEY_LABELS } = await import('./index');
  expect(vi.mocked(validateAccountUpdate)).toHaveBeenCalledTimes(1);
  expect(
    screen.getAllByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK05 }).length,
  ).toBeGreaterThan(0);
}

/**
 * A validation request that cannot be answered leaves the screen where it was.
 *
 * Assumptions: an unanswered question is not a passed validation, so the save key must stay absent. This
 * is the one arm of the three that a resolved-promise test cannot reach at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function doesNotAdvanceWhenTheValidationTurnFails(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  const attempt = deferred<AccountUpdateValidationResponse>();
  vi.mocked(validateAccountUpdate).mockReturnValueOnce(attempt.promise);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('city'));
  await user.type(control('city'), 'ALBANY');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Fails the validation request.
     * @returns {Promise<void>} Resolves once the rejection has been handled.
     */
    async (): Promise<void> => {
      attempt.refuse(new Error('gateway unreachable'));
      await attempt.promise.catch(
        /**
         * Absorbs the rejection so the test runner does not see it as unhandled.
         * @returns {void} Nothing.
         */
        (): void => undefined,
      );
    },
  );

  const { ACCOUNT_UPDATE_KEY_LABELS } = await import('./index');
  expect(
    screen.queryByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK05 }),
  ).not.toBeInTheDocument();
}

/**
 * A ten-digit account identifier is refused locally, with no read issued.
 *
 * Assumptions: the assertion is on the TRANSPORT and not on the refusal sentence, because the defect
 * accepted the short key and read with it -- so what changed is whether a request goes out, and only a
 * call-count assertion sees that.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAKeyShorterThanElevenDigits(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const user = userEvent.setup();
  await renderScreen();

  await submitFilter(user, TOO_SHORT_ACCOUNT_ID);

  expect(vi.mocked(readAccountView)).not.toHaveBeenCalled();
}

/**
 * An eleven-digit account identifier is read.
 *
 * Assumptions: this is the companion the previous case needs. A local edit that refused EVERY key would
 * satisfy the refusal assertion perfectly, so the accepted width has to be asserted too.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function readsAKeyOfExactlyElevenDigits(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  expect(vi.mocked(readAccountView)).toHaveBeenCalledWith(ACCOUNT_ID);
}

/**
 * All five amounts render through the baseline's edit mask, to the exact declared width.
 *
 * Assumptions: every one of the five is asserted rather than a representative, because the mask has
 * per-value branches -- sign, zero suppression and separator placement -- and the branch that a single
 * positive four-figure amount exercises is the one least likely to be wrong.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersTheFiveAmountsThroughTheEditMask(): Promise<void> {
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  expect(control('creditLimit')).toHaveValue(MASKED_AMOUNTS.creditLimit);
  expect(control('cashCreditLimit')).toHaveValue(MASKED_AMOUNTS.cashCreditLimit);
  expect(control('currentBalance')).toHaveValue(MASKED_AMOUNTS.currentBalance);
  expect(control('currentCycleCredit')).toHaveValue(MASKED_AMOUNTS.currentCycleCredit);
  expect(control('currentCycleDebit')).toHaveValue(MASKED_AMOUNTS.currentCycleDebit);
}

/**
 * The mask is removed before an amount is submitted.
 *
 * Assumptions: this is the half the mask itself makes necessary and the review did not name. The mask is
 * painted into an EDITABLE control here, so masked text is also SUBMITTED text -- and the service's own
 * parser does not tolerate the interior blanks zero suppression produces. Without this the mask would
 * turn every untouched amount into a value the service refuses.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function submitsAmountsWithTheMaskRemoved(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  vi.mocked(validateAccountUpdate).mockResolvedValueOnce(ACCEPTING_VERDICT);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('city'));
  await user.type(control('city'), 'ALBANY');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  const submitted = vi.mocked(validateAccountUpdate).mock
    .calls[0]?.[0] as SensitiveAccountUpdateRequest;
  expect(submitted.creditLimit).toBe(ACCOUNT.creditLimit);
  expect(submitted.currentCycleCredit).toBe(ACCOUNT.currentCycleCredit);
  expect(submitted.currentBalance).toBe(ACCOUNT.currentBalance);
}

/**
 * A refusal turn re-masks the amounts it accepted and echoes the one it refused exactly as typed.
 *
 * Assumptions: this is the baseline's own redisplay rule and it is per-field rather than per-screen.
 * `3203-SHOW-UPDATED-VALUES` at `app/cbl/COACTUPC.cbl` L2874 onward moves each amount through the edit
 * field when that amount's validation flag says it was accepted, and moves the operator's own text back
 * unchanged when it does not -- so the value being corrected is the value that was typed, while every
 * value that passed returns to its formatted form.
 *
 * Assumptions: BOTH directions are asserted in one case on purpose. A screen that re-masked everything
 * would satisfy an accepted-only assertion, and a screen that re-masked nothing would satisfy a
 * refused-only one; only the pair distinguishes the per-field rule from either uniform alternative.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function remasksAcceptedAmountsAndEchoesRefusedOnes(): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  vi.mocked(validateAccountUpdate).mockResolvedValueOnce(REFUSES_ONE_AMOUNT);
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  await user.clear(control('creditLimit'));
  await user.type(control('creditLimit'), '7500.00');
  await user.clear(control('cashCreditLimit'));
  await user.type(control('cashCreditLimit'), REFUSED_CASH_LIMIT_ENTRY);
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  expect(control('creditLimit')).toHaveValue('+      7,500.00');
  expect(control('cashCreditLimit')).toHaveValue(REFUSED_CASH_LIMIT_ENTRY);
}

/**
 * The two protected identifiers show their stored masked state beside blank replacement controls.
 *
 * Assumptions: BOTH halves are asserted. That the masked renderings appear is the fix; that the four
 * controls remain EMPTY is what keeps the fix from having introduced a worse defect, because a masked
 * value seeded into the control would be submitted and then stored as the identifier.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function showsTheStoredIdentifiersBesideBlankControls(): Promise<void> {
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  expect(screen.getByText(CUSTOMER.ssnMasked, EXACT_TEXT)).toBeInTheDocument();
  expect(screen.getByText(CUSTOMER.governmentIssuedIdMasked, EXACT_TEXT)).toBeInTheDocument();
  expect(control('ssnPart1')).toHaveValue('');
  expect(control('ssnPart2')).toHaveValue('');
  expect(control('ssnPart3')).toHaveValue('');
  expect(control('governmentIssuedId')).toHaveValue('');
}

/**
 * Nothing is captioned before a record has been read.
 *
 * Assumptions: this distinguishes not-yet-looked from on-file-and-blank. A caption naming an empty value
 * would assert the identifier on file is blank, which is a different and false claim.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function captionsNothingBeforeARecordIsRead(): Promise<void> {
  const { ACCOUNT_UPDATE_STORED_STATE_LABEL } = await import('./index');
  await renderScreen();

  expect(screen.queryByText(ACCOUNT_UPDATE_STORED_STATE_LABEL)).not.toBeInTheDocument();
  expect(screen.queryByText(CUSTOMER.ssnMasked, EXACT_TEXT)).not.toBeInTheDocument();
}

/**
 * A read superseded by a newer read cannot seed the form.
 *
 * Assumptions: the newer read is released FIRST and the older one afterwards, which is the ordering that
 * produces the defect. Under it the form carries the first account's record while the field shows the
 * second identifier -- and because the form's values are what a later save submits, the next write would
 * have carried the wrong record's values.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsAReadSupersededByANewerRead(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const first = deferred<typeof READ_RESULT>();
  vi.mocked(readAccountView).mockReturnValueOnce(first.promise);
  const user = userEvent.setup();
  await renderScreen();

  await submitFilter(user, ACCOUNT_ID);
  /*
   * Assumptions: the edit is typed into the ALREADY-FOCUSED control rather than clicked into, and the
   * reason was measured rather than assumed. While a read is outstanding the screen's form sits inside a
   * `Spin`, which the design system renders with `pointer-events: none` -- so `user.type`, which begins
   * with a pointer press, is refused outright. That is a real second mitigation of this race and it is
   * recorded here, but it is not the guard under test: `pointer-events` does not stop keyboard entry, so
   * an operator whose focus is already in the field can still edit mid-flight. Typing without the
   * pointer step is what reaches the ordering this case is about.
   *
   * Assumptions: the edit REMOVES a digit rather than adding one, which was also measured. The control
   * carries `maxLength` eleven from the mapset's own field width, so an eleven-digit filter is already
   * full and a twelfth keystroke is discarded by the control before any handler sees it -- a case built
   * that way passes against the defect because no edit ever happens. A backspace is the smallest edit
   * that a full field actually accepts.
   */
  await user.keyboard('{Backspace}');
  expect(control('accountId')).toHaveValue(ACCOUNT_ID.slice(0, -1));

  await act(
    /**
     * Releases the superseded response.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      first.release(READ_RESULT);
      await first.promise;
    },
  );

  expect(control('lastName')).toHaveValue('');
  expect(control('creditLimit')).toHaveValue('');
}

/**
 * The key that owns an outstanding request reports it, stays reachable and starts no second one.
 *
 * ⚠️ Refactoring Rationale: this case asserted the Enter key DISABLED while its own read was
 * outstanding, and now asserts it busy. The two are different statements to an operator -- disabled
 * says the action is unavailable, busy says the action they just took is running -- and the shared key
 * primitive at `ui/src/layout/usePfKeys.ts` gives the second: a busy key stays present, enabled,
 * focusable and named, and declines a press silently, which is the terminal's input-inhibit. The
 * protection the old assertion stood for is asserted directly instead, as the absence of a second
 * request.
 *
 * ⚠️ Assumptions: the second press is driven BOTH ways, and both are needed for different reasons. The
 * keyboard press reaches the screen's Enter arm through the form's own submit -- an Enter inside a
 * focused field is claimed by that field, so the key hook returns before its handler lookup and never
 * sees it -- so it exercises the arm's own guard. The pointer press reaches the legend control, so it
 * exercises the design system's refusal of a click on a loading button. A case driving only one of them
 * would leave the other path open.
 *
 * Assumptions: F3 is asserted still reachable in the same breath, because standing every key down would
 * satisfy every other assertion here and would trap an operator on a slow request.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsTheOutstandingTurnOnTheKeyThatOwnsIt(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const first = deferred<typeof READ_RESULT>();
  vi.mocked(readAccountView).mockReturnValueOnce(first.promise);
  const { ACCOUNT_UPDATE_KEY_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await submitFilter(user, ACCOUNT_ID);

  /*
   * Assumptions: the legend is located as a NAVIGATION landmark by its own region name, because
   * `ui/src/layout/PfKeyBar.tsx` renders it as a `nav` with `aria-label` rather than as a toolbar, and
   * because the shell paints its own controls outside it. Querying the buttons unscoped would find the
   * screen's inline save control as well once that appears.
   */
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const enterKey = within(legend).getByRole('button', {
    name: ACCOUNT_UPDATE_KEY_LABELS.ENTER,
  });
  expect(enterKey).toBeEnabled();
  expect(enterKey).toHaveAttribute('aria-busy', 'true');
  expect(
    within(legend).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK03 }),
  ).toBeEnabled();

  await user.keyboard('{Enter}');
  await user.click(enterKey);

  expect(vi.mocked(readAccountView)).toHaveBeenCalledTimes(1);

  await act(
    /**
     * Releases the outstanding response so the case leaves nothing in flight.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      first.release(READ_RESULT);
      await first.promise;
    },
  );
}

/**
 * A read whose answer carries no revision refuses, states the miss, and presents no editable record.
 *
 * ⚠️ Purpose: this is the case whose absence let a defect ship. The service withholds the entity tag
 * on exactly one arm -- the account located, the customer master holding no matching row -- and the
 * screen seeded forty editable fields from it. `saveEdits` needs a revision to form its precondition, so
 * the operator's only reachable outcome was a refusal, and the refusal was `No input received` on a form
 * they had just filled in. The reference does not present that state at all:
 * `9400-GETCUSTDATA-BYCUST` sets `INPUT-ERROR` on the miss and `9000-READ-ACCT` exits before
 * `9500-STORE-FETCHED-DATA` at `app/cbl/COACTUPC.cbl` L3636.
 *
 * Assumptions: THREE properties are asserted, because each is separately losable -- the response's own
 * sentence is stated, the record's own values are absent from the form, and the typed key is retained so
 * the operator can correct one digit. Asserting only the sentence would pass against a screen that stated
 * it and seeded the form anyway, which is the defect wearing a message.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAReadThatCarriesNoRevision(): Promise<void> {
  const { nationalIdentifierCaption } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();

  await readAnAccountWithNoCustomer(user, ACCOUNT_ID);

  expect(await screen.findByText(CUSTOMER_MISS_SENTENCE)).toBeInTheDocument();
  expect(control('accountId')).toHaveValue(ACCOUNT_ID);
  expect(control('creditLimit')).toHaveValue('');
  expect(screen.queryByText(CUSTOMER.ssnMasked, EXACT_TEXT)).not.toBeInTheDocument();
  expect(screen.queryByText(nationalIdentifierCaption(CUSTOMER.ssnMasked))).not.toBeInTheDocument();
}

/**
 * A refused read retires the masked identifiers of the account read before it.
 *
 * ⚠️ Purpose: the two masked markers are held in TWO pieces of state -- one feeds the slot beside each
 * control, the other the caption below it -- and a refused read cleared only the first. So a good read
 * followed by a refused one left the previous customer's markers captioning a blank form under a
 * different account number, which is the attribution the clearing exists to prevent.
 *
 * Assumptions: the case reads a real record FIRST, so there is something to leak; a case that refused on
 * the opening turn would pass against the defect, because the state it fails to clear would never have
 * been set.
 *
 * Assumptions: the second read is reached by the CANCEL key rather than by re-keying the filter, and the
 * screen's own faithfulness is why. In the show-details action the filter is protected -- `accountId` is
 * one of the three fields `3300-SETUP-SCREEN-ATTRS` never unprotects -- so an operator cannot type a new
 * key there at all, and the reference's own second read is exactly this one: the PF12 arm at
 * `app/cbl/COACTUPC.cbl` L2572 to L2580 performs `9000-READ-ACCT` again rather than restoring its
 * snapshot, which is what makes a customer row deleted while the operator was typing reachable.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function retiresTheMaskedIdentifiersOfTheAccountReadBefore(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const { nationalIdentifierCaption, governmentIdentifierCaption } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();
  await readOneAccount(user);

  /*
   * WHY : ⚠️ Assumptions: the CAPTIONS are what this case asserts on, not the bare marker, and the
   *       distinction is the whole of the defect. The marker appears twice on this screen -- in the slot
   *       beside each control, fed by `storedIdentifiers`, and inside the caption below it, fed by
   *       `protectedValues` -- and only the first was being cleared. An assertion on the bare marker
   *       matches the slot and passes while the caption still stands, which is the defect surviving its
   *       own test; the caption text can only come from the holder that was leaking.
   */
  const ssnCaption = nationalIdentifierCaption(CUSTOMER.ssnMasked);
  const governmentCaption = governmentIdentifierCaption(CUSTOMER.governmentIssuedIdMasked);
  expect(screen.getByText(ssnCaption)).toBeInTheDocument();
  expect(screen.getByText(governmentCaption)).toBeInTheDocument();

  vi.mocked(readAccountView).mockResolvedValueOnce({
    account: {
      accountId: ACCOUNT_ID,
      account: ACCOUNT,
      customer: null,
      informationMessage: null,
      returnMessage: CUSTOMER_MISS_SENTENCE,
    },
    revision: null,
  });
  await user.keyboard('{F12}');
  await act(
    /**
     * Drains the microtask queue so the refusal is committed.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  expect(await screen.findByText(CUSTOMER_MISS_SENTENCE)).toBeInTheDocument();
  expect(screen.queryByText(ssnCaption)).not.toBeInTheDocument();
  expect(screen.queryByText(governmentCaption)).not.toBeInTheDocument();
  expect(screen.queryByText(CUSTOMER.ssnMasked, EXACT_TEXT)).not.toBeInTheDocument();
  expect(screen.queryByText(CUSTOMER.governmentIssuedIdMasked, EXACT_TEXT)).not.toBeInTheDocument();
}

/**
 * Drives the screen to the validated action, where all four keys are live and labelled.
 *
 * Assumptions: it goes through the real two turns rather than seeding state -- a read, an edit, and an
 * Enter the service accepts -- because the action is what paints the cancel and save legends, and a case
 * that reached it any other way would prove nothing about the turn that gets there.
 * @param {ReturnType<typeof userEvent.setup>} user - The interaction driver for this case.
 * @returns {Promise<void>} Resolves with the screen in the validated action.
 */
async function reachTheValidatedAction(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const { validateAccountUpdate } = await import('../../api/accounts');
  await readOneAccount(user);
  vi.mocked(validateAccountUpdate).mockResolvedValueOnce(ACCEPTING_VERDICT);

  await user.clear(control('city'));
  await user.type(control('city'), 'ALBANY');
  await user.keyboard('{Enter}');
  await act(
    /**
     * Drains the microtask queue so the verdict is applied.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Reads one legend control by its painted label.
 * @param {string} label - The label the legend paints on the wanted control.
 * @returns {HTMLElement} That control.
 */
function legendKey(label: string): HTMLElement {
  return within(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).getByRole(
    'button',
    { name: label },
  );
}

/**
 * The cancel key reports its own re-read, and the processing key does not report it.
 *
 * ⚠️ Purpose: this is the case the per-key ownership exists for. Two keys reach one reader on this
 * screen -- Enter's fetch and F12's cancel both perform the reference's `9000-READ-ACCT` -- so a busy
 * affordance driven by the in-flight flag alone would spin whichever control the screen happened to
 * name, and the operator would watch the wrong one work.
 *
 * ⚠️ Assumptions: the two halves are asserted TOGETHER and the second half is what discriminates. A
 * screen that reported every key busy would satisfy the first assertion, and a screen that had simply
 * kept the old blanket `disabled` would satisfy the second; only the pair pins the ownership.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsTheCancelReReadOnTheCancelKeyAlone(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const { ACCOUNT_UPDATE_KEY_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();
  await reachTheValidatedAction(user);

  const pending = deferred<typeof READ_RESULT>();
  vi.mocked(readAccountView).mockReturnValueOnce(pending.promise);
  await user.keyboard('{F12}');

  const cancelKey = legendKey(ACCOUNT_UPDATE_KEY_LABELS.PFK12);
  expect(cancelKey).toBeEnabled();
  expect(cancelKey).toHaveAttribute('aria-busy', 'true');

  const enterKey = legendKey(ACCOUNT_UPDATE_KEY_LABELS.ENTER);
  expect(enterKey).toBeDisabled();
  /*
   * WHY : Assumptions: the negative half is asserted as the attribute's VALUE and not as its absence,
   *       because the legend states busyness on every control it paints -- an idle one carries
   *       `aria-busy="false"` rather than nothing. Asserting absence would fail against a screen that
   *       reports ownership perfectly, which is the opposite of what this case is for.
   */
  expect(enterKey).toHaveAttribute('aria-busy', 'false');

  await act(
    /**
     * Releases the outstanding response so the case leaves nothing in flight.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      pending.release(READ_RESULT);
      await pending.promise;
    },
  );
}

/**
 * The save key reports its own write, and the other keys stand down while it runs.
 *
 * ⚠️ Assumptions: the write is the one turn on this screen an operator most needs told about, and it is
 * the turn the old treatment hid: the control they pressed to commit went grey along with every other
 * key, so the screen said only that nothing was available. It now says that this control is working.
 *
 * Assumptions: the write is reached through the confirmation the screen requires -- the key opens the
 * prompt and the prompt's own accept issues the request -- because that is the only path to a write here
 * and a case that bypassed it would be asserting about a flow the screen does not have.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsTheWriteOnTheSaveKey(): Promise<void> {
  const { updateAccount } = await import('../../api/accounts');
  const { ACCOUNT_UPDATE_KEY_LABELS } = await import('./index');
  const user = userEvent.setup();
  await renderScreen();
  await reachTheValidatedAction(user);

  const pending = deferred<typeof WRITE_RESULT>();
  vi.mocked(updateAccount).mockReturnValueOnce(pending.promise);

  await user.keyboard('{F5}');
  const bubble = await screen.findByRole('tooltip');
  await user.click(within(bubble).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK05 }));

  const saveKey = legendKey(ACCOUNT_UPDATE_KEY_LABELS.PFK05);
  expect(saveKey).toBeEnabled();
  expect(saveKey).toHaveAttribute('aria-busy', 'true');
  expect(legendKey(ACCOUNT_UPDATE_KEY_LABELS.ENTER)).toBeDisabled();
  expect(legendKey(ACCOUNT_UPDATE_KEY_LABELS.PFK03)).toBeDisabled();

  await act(
    /**
     * Releases the outstanding write so the case leaves nothing in flight.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      pending.release(WRITE_RESULT);
      await pending.promise;
    },
  );
}

/**
 * An unregistered attention key starts no second turn while one is outstanding.
 *
 * ⚠️ Purpose: this screen COERCES an unregistered key into its Enter arm -- `app/cbl/COACTUPC.cbl`
 * L905 to L916 sets its invalid flag and then `SET CCARD-AID-ENTER TO TRUE`, so the reference reaches
 * the same arm -- and the coercion calls that arm directly rather than dispatching the identifier. It
 * therefore passes through none of the key descriptor's gates, which made it the one remaining way back
 * into a turn the screen had already declined to start.
 *
 * ⚠️ Assumptions: F7 is pressed because this screen registers no handler for it, so the hook classifies
 * it `unmapped` and the screen's own coercion runs. A registered key would be declined by the hook
 * itself and would prove nothing about the coercion.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anUnregisteredKeyStartsNoSecondTurn(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const pending = deferred<typeof READ_RESULT>();
  vi.mocked(readAccountView).mockReturnValueOnce(pending.promise);
  const user = userEvent.setup();
  await renderScreen();

  await submitFilter(user, ACCOUNT_ID);
  await user.keyboard('{F7}');

  expect(vi.mocked(readAccountView)).toHaveBeenCalledTimes(1);

  await act(
    /**
     * Releases the outstanding response so the case leaves nothing in flight.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      pending.release(READ_RESULT);
      await pending.promise;
    },
  );
}

/**
 * The live region carries the outstanding-request sentence for exactly as long as a turn is in flight.
 *
 * ⚠️ Purpose: every other sign this screen gives that it is working is visual -- an overlay across the
 * form and a spinner on one key -- so an operator who cannot see either had the screen go silent for
 * the length of the request and then speak only its answer.
 *
 * ⚠️ Assumptions: the region is asserted PRESENT and empty before the turn, which is not a formality.
 * `ui/src/layout/fieldHelp.tsx` records that a live region has to be in the accessibility tree before
 * its text changes for the change to be announced, so a region that were mounted only while busy would
 * announce nothing on the first turn.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function announcesTheOutstandingRequestWhileItRuns(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const { BUSY_ANNOUNCEMENT_TEST_ID } = await import('../../layout/fieldHelp');
  const { REQUEST_IN_PROGRESS } = await import('../../messages/messages');
  const pending = deferred<typeof READ_RESULT>();
  vi.mocked(readAccountView).mockReturnValueOnce(pending.promise);
  const user = userEvent.setup();
  await renderScreen();

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toBeEmptyDOMElement();

  await submitFilter(user, ACCOUNT_ID);

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);

  await act(
    /**
     * Releases the outstanding response so the announcement retires.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      pending.release(READ_RESULT);
      await pending.promise;
    },
  );

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toBeEmptyDOMElement();
}

/**
 * Registers every case, and resets the transport between them.
 * @returns {void} Nothing; the registrations are the effect.
 */
function accountUpdateTurnCases(): void {
  beforeEach(resetTransport);
  afterEach(
    /**
     * Clears any spy state a case installed.
     * @returns {void} Nothing.
     */
    (): void => {
      vi.restoreAllMocks();
    },
  );

  it(
    'marks a blank field without submitting the marker',
    marksABlankFieldWithoutSubmittingTheMarker,
  );
  it('clears a refusal when the field is corrected', clearsARefusalWhenTheFieldIsCorrected);
  it('does not advance when the service refuses', doesNotAdvanceWhenTheServiceRefuses);
  it('advances only after the service accepts', advancesOnlyAfterTheServiceAccepts);
  it('does not advance when the validation turn fails', doesNotAdvanceWhenTheValidationTurnFails);
  it('refuses a key shorter than eleven digits', refusesAKeyShorterThanElevenDigits);
  it('reads a key of exactly eleven digits', readsAKeyOfExactlyElevenDigits);
  it('renders the five amounts through the edit mask', rendersTheFiveAmountsThroughTheEditMask);
  it('submits amounts with the mask removed', submitsAmountsWithTheMaskRemoved);
  it(
    'remasks accepted amounts and echoes refused ones',
    remasksAcceptedAmountsAndEchoesRefusedOnes,
  );
  it(
    'shows the stored identifiers beside blank controls',
    showsTheStoredIdentifiersBesideBlankControls,
  );
  it('captions nothing before a record is read', captionsNothingBeforeARecordIsRead);
  it('discards a read superseded by a newer read', discardsAReadSupersededByANewerRead);
  it(
    'reports an outstanding turn on the key that owns it and starts no second one',
    reportsTheOutstandingTurnOnTheKeyThatOwnsIt,
  );
  it('reports a cancel re-read on the cancel key alone', reportsTheCancelReReadOnTheCancelKeyAlone);
  it('reports an outstanding write on the save key', reportsTheWriteOnTheSaveKey);
  it('starts no second turn from an unregistered key', anUnregisteredKeyStartsNoSecondTurn);
  it(
    'announces the outstanding request for as long as it runs',
    announcesTheOutstandingRequestWhileItRuns,
  );
  it('refuses a read that carries no revision', refusesAReadThatCarriesNoRevision);
  it(
    'retires the masked identifiers of the account read before',
    retiresTheMaskedIdentifiersOfTheAccountReadBefore,
  );
}

describe('account update screen turns', accountUpdateTurnCases);
