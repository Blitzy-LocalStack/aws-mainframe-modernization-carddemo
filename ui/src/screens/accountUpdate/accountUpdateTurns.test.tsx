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
 * The keys that start a turn stand down while a request is outstanding.
 *
 * Assumptions: Enter is asserted disabled and F3 asserted still reachable, together. Disabling every key
 * would satisfy the first assertion and would trap an operator on a slow request, which is the failure
 * the F3 exemption exists to avoid.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function standsTheTurnKeysDownWhileARequestIsOutstanding(): Promise<void> {
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
  expect(
    within(legend).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.ENTER }),
  ).toBeDisabled();
  expect(
    within(legend).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK03 }),
  ).toBeEnabled();

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
    'stands the turn keys down while a request is outstanding',
    standsTheTurnKeysDownWhileARequestIsOutstanding,
  );
}

describe('account update screen turns', accountUpdateTurnCases);
