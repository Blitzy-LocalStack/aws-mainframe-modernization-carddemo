/**
 * @file Component tests for the account-view screen's privacy, read sequencing and money rendering.
 *
 * Purpose
 * -------
 * Cover the three behaviours a review found defective on this screen, each of which is invisible in
 * the type system and in a single-turn test:
 *
 * - the account identifier a sibling screen hands over must not reach the address, because the request
 *   line is copied into access logs by infrastructure neither layer can redact;
 * - a read already in flight must stop being able to paint once the screen is no longer about the
 *   identifier that read was for;
 * - the five monetary values must be rendered through the baseline's `+ZZZ,ZZZ,ZZZ.99` edit mask
 *   rather than as the raw wire text.
 *
 * How the races are driven
 * ------------------------
 * Assumptions: the transport is substituted with DEFERRED promises the case resolves by hand, rather
 * than with resolved ones and a wait. A race is an ORDERING, so the ordering has to be the thing the
 * test controls: each case starts a read, performs the turn that should invalidate it, and only then
 * releases the response. Resolving first and asserting afterwards would pass against the defective
 * code, because the defect is not that the response is wrong -- it is that a correct response for a
 * superseded question is applied.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AppShell } from '../../layout/AppShell';
import type { AccountDetail, CustomerDetail } from '../../api/accounts';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The one transport function this screen imports, as a spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return { readAccountView: vi.fn() };
}

vi.mock('../../api/accounts', mockAccountTransportModule);

/** An account whose five amounts exercise the mask's sign, suppression and separator branches. */
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

/** Eleven digits, the only width the screen's own edit accepts. */
const ACCOUNT_ID = '00000000011';

/** A second valid identifier, used where a case has to change the key mid-flight. */
const OTHER_ACCOUNT_ID = '00000000022';

/**
 * Builds the read result the transport publishes for a successful view.
 * @param {string} accountId - Identifier the response echoes back.
 * @returns {object} A result carrying the account, its customer and both message channels.
 */
function readResult(accountId: string): {
  account: {
    accountId: string;
    account: AccountDetail;
    customer: CustomerDetail;
    informationMessage: string | null;
    returnMessage: string | null;
  };
  revision: string | null;
} {
  return {
    account: {
      accountId,
      account: ACCOUNT,
      customer: CUSTOMER,
      informationMessage: null,
      returnMessage: null,
    },
    revision: null,
  };
}

/** A promise the case resolves by hand, with its resolver captured. */
interface Deferred {
  /** The promise handed to the screen. */
  readonly promise: Promise<ReturnType<typeof readResult>>;
  /** Releases the response. */
  readonly release: (value: ReturnType<typeof readResult>) => void;
}

/**
 * Creates a promise whose resolution the case controls.
 *
 * Assumptions: `let release!:` uses definite-assignment rather than a no-op initialiser, because the
 * executor runs synchronously so the binding is always assigned before this returns.
 * @returns {Deferred} The promise and the function that resolves it.
 */
function deferred(): Deferred {
  let release!: (value: ReturnType<typeof readResult>) => void;
  const promise = new Promise<ReturnType<typeof readResult>>(
    /**
     * Captures the resolver.
     * @param {(value: ReturnType<typeof readResult>) => void} resolve - The promise's resolver.
     * @returns {void} Nothing.
     */
    (resolve: (value: ReturnType<typeof readResult>) => void): void => {
      release = resolve;
    },
  );
  return { promise, release };
}

/**
 * Renders the screen inside the shell and, when an identifier is supplied, TYPES it into the filter.
 *
 * ⚠️ Refactoring Rationale: the identifier used to be handed over as router STATE on the initial entry,
 * because the screen read a handoff member to seed its filter control. That seed is WITHDRAWN -- the
 * screen now starts blank in every arrival, which is the reference's own first-entry state at
 * `app/cbl/COACTVWC.cbl` L462-L463, and `CC-ACCT-ID` is written from the RECEIVED map field at L632
 * and from nowhere else, so no carried selection ever reached that field on the terminal either. Every
 * case below that needed a populated field needed it in order to take a TURN, so the helper types the
 * value the way an operator does. What it must NOT do is put the value in the address: the assertions
 * that the search string stays empty and the digits never appear in the href are the reason this
 * helper exists rather than each case rendering for itself.
 * @param {string | undefined} typedAccountId - Identifier to type into the filter, or nothing.
 * @returns {Promise<void>} Resolves once the screen is mounted and the value, if any, is entered.
 */
async function renderScreen(typedAccountId?: string): Promise<void> {
  const { AccountViewScreen } = await import('./index');

  render(
    <MemoryRouter initialEntries={[{ pathname: '/account/view' }]}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/account/view" element={<AccountViewScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  if (typedAccountId !== undefined) {
    await userEvent.setup().type(filterControl(), typedAccountId);
  }
}

/**
 * Matcher options that compare text EXACTLY, without whitespace normalisation.
 *
 * Assumptions: the default normaliser collapses interior whitespace runs, which is precisely what the
 * edit mask emits -- `-      1,234.56` would be compared as `- 1,234.56`, so the PAD WIDTH would go
 * unasserted and a mask that grouped correctly but padded to the wrong column would pass. An identity
 * normaliser keeps the assertion on the exact fifteen characters the picture specifies.
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
 * Reads the screen's account filter control.
 *
 * Assumptions: the element is returned without a type assertion, because the testing library already
 * types a role query as `HTMLElement` and the matchers used against it need nothing narrower.
 * @returns {HTMLElement} The filter input.
 */
function filterControl(): HTMLElement {
  return screen.getByRole('textbox');
}

/**
 * Resets the transport spy so no case inherits another's queued response.
 * @returns {Promise<void>} Resolves once the spy is reset.
 */
async function resetTransport(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  vi.mocked(readAccountView).mockReset();
}

/**
 * An identifier the operator types reaches the field and never the address.
 *
 * ⚠️ Refactoring Rationale: this asserted that a handed-over identifier PRE-FILLED the field, and the
 * seed it asserted is withdrawn -- so the first half now asserts the value the operator typed, which is
 * the only way this field is ever populated. The second and third halves are unchanged and are the
 * point: an eleven-digit account identifier must not reach the request line, because a query string
 * lands in the browser's history, in the `Referer` header of every same-origin subresource and in the
 * load balancer's access log, none of which the screen can reach afterwards. Those two assertions fail
 * for the query-string spelling this replaced and for any future one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function prefillsFromStateWithoutTouchingTheAddress(): Promise<void> {
  await renderScreen(ACCOUNT_ID);

  expect(filterControl()).toHaveValue(ACCOUNT_ID);
  expect(window.location.search).toBe('');
  expect(window.location.href).not.toContain(ACCOUNT_ID);
}

/**
 * A screen reached with no handoff starts with an empty field.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function startsEmptyWithoutAHandoff(): Promise<void> {
  await renderScreen();

  expect(filterControl()).toHaveValue('');
}

/**
 * A handoff member carrying an identifier is ignored entirely, whatever its shape.
 *
 * ⚠️ Refactoring Rationale: this asserted a NARROWING guard -- that a handoff member of the wrong shape
 * did not reach the control -- and the guard is now unnecessary because no handoff member is read at
 * all. The case is kept and strengthened rather than withdrawn: the state below carries a member of the
 * wrong shape AND the case is registered beside one that hands over a well-formed identifier, so
 * between them they assert that the control starts blank whatever a caller puts in navigation state.
 * That is the property the withdrawal has to hold, and re-introducing a seed of either kind fails it.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function ignoresAHandoffThatIsNotText(): Promise<void> {
  const { AccountViewScreen } = await import('./index');
  render(
    <MemoryRouter
      initialEntries={[{ pathname: '/account/view', state: { accountId: { nested: true } } }]}
    >
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/account/view" element={<AccountViewScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  expect(filterControl()).toHaveValue('');
}

/**
 * A handoff carrying a WELL-FORMED identifier is ignored just as completely.
 *
 * Assumptions: this is the companion to the case above and the pair is what pins the withdrawal. A
 * narrowing guard would admit this one and refuse the malformed one, so a case asserting only the
 * malformed shape would stay green if the seed were restored. The reference's own first-entry state is
 * a blank field -- `IF EIBCALEN = 0` at `app/cbl/COACTVWC.cbl` L462-L463 -- and its `CC-ACCT-ID` is
 * written from the received map field at L632 and from nowhere else, so nothing carried in ever
 * populates it.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function ignoresAWellFormedHandoff(): Promise<void> {
  const { AccountViewScreen } = await import('./index');
  render(
    <MemoryRouter
      initialEntries={[{ pathname: '/account/view', state: { accountId: ACCOUNT_ID } }]}
    >
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/account/view" element={<AccountViewScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  expect(filterControl()).toHaveValue('');
}

/**
 * A response for a superseded identifier cannot paint after the key was edited.
 *
 * Assumptions: the edit is a single keystroke, which is the smallest turn that changes what the
 * screen is about. Under the defect this case renders the first account's record while the field
 * shows the second identifier.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsAReadSupersededByAnEdit(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const first = deferred();
  vi.mocked(readAccountView).mockReturnValueOnce(first.promise);

  await renderScreen(ACCOUNT_ID);
  const user = userEvent.setup();
  await user.click(filterControl());
  await user.keyboard('{Enter}');

  await user.clear(filterControl());
  await user.type(filterControl(), OTHER_ACCOUNT_ID);

  await act(
    /**
     * Releases the superseded response.
     * @returns {Promise<void>} Resolves once the microtask queue has drained.
     */
    async (): Promise<void> => {
      first.release(readResult(ACCOUNT_ID));
      await first.promise;
    },
  );

  expect(filterControl()).toHaveValue(OTHER_ACCOUNT_ID);
  expect(screen.queryByText(CUSTOMER.lastName)).not.toBeInTheDocument();
}

/**
 * No stale record is shown after a local refusal.
 *
 * Assumptions: this asserts the OUTCOME an operator can observe -- a refused turn shows no record --
 * and deliberately does not claim to prove which invalidation produced it. Emptying the field is an
 * edit, so the edit-path invalidation has already fired by the time the refusal runs; a negative test
 * that removes only the refusal-path call therefore leaves this case passing. That is recorded here
 * rather than hidden because the alternative was to describe this as proof of the refusal path, which
 * it is not. Reaching the refusal branch with a live read and no preceding edit is not constructible:
 * a read starts only from a valid field, so the field must change before it can be refused, and every
 * change to it is an edit. The refusal-path call is kept for the reason given at its own site, and its
 * observability is stated there.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsAReadSupersededByALocalRefusal(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  const first = deferred();
  vi.mocked(readAccountView).mockReturnValueOnce(first.promise);

  await renderScreen(ACCOUNT_ID);
  const user = userEvent.setup();
  await user.click(filterControl());
  await user.keyboard('{Enter}');

  await user.clear(filterControl());
  await user.keyboard('{Enter}');

  await act(
    /**
     * Releases the superseded response.
     * @returns {Promise<void>} Resolves once the microtask queue has drained.
     */
    async (): Promise<void> => {
      first.release(readResult(ACCOUNT_ID));
      await first.promise;
    },
  );

  expect(screen.queryByText(CUSTOMER.lastName)).not.toBeInTheDocument();
}

/**
 * The five monetary values are rendered through the baseline edit mask.
 *
 * Assumptions: the expectations are the exact masked strings, and they are matched inside the record
 * region rather than anywhere in the document. Under the defect the raw wire text appears instead --
 * `-1234.56` rather than `-      1,234.56` -- so asserting the masked form both proves the mask ran
 * and proves the raw form is gone.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersMoneyThroughTheEditMask(): Promise<void> {
  const { readAccountView } = await import('../../api/accounts');
  vi.mocked(readAccountView).mockResolvedValue(readResult(ACCOUNT_ID));

  await renderScreen(ACCOUNT_ID);
  const user = userEvent.setup();
  await user.click(filterControl());
  await user.keyboard('{Enter}');

  expect(await screen.findByText(CUSTOMER.lastName)).toBeInTheDocument();

  const main = screen.getByRole('main');
  expect(within(main).getByText('-      1,234.56', EXACT_TEXT)).toBeInTheDocument();
  expect(within(main).getByText('+      5,000.00', EXACT_TEXT)).toBeInTheDocument();
  expect(within(main).getByText('+        250.00', EXACT_TEXT)).toBeInTheDocument();
  expect(within(main).getByText('+           .00', EXACT_TEXT)).toBeInTheDocument();
  expect(within(main).getByText('+999,999,999.99', EXACT_TEXT)).toBeInTheDocument();
  expect(within(main).queryByText('-1234.56')).not.toBeInTheDocument();
  expect(within(main).queryByText('0.00')).not.toBeInTheDocument();
}

/**
 * Registers the privacy cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function handoffPrivacyCases(): void {
  beforeEach(resetTransport);
  afterEach(resetTransport);

  it('keeps a typed identifier out of the address', prefillsFromStateWithoutTouchingTheAddress);
  it('starts empty when no identifier was handed over', startsEmptyWithoutAHandoff);
  it('ignores a handoff member that is not text', ignoresAHandoffThatIsNotText);
  it('ignores a well-formed handoff member too', ignoresAWellFormedHandoff);
}

/**
 * Registers the read-sequencing cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function readSequencingCases(): void {
  beforeEach(resetTransport);
  afterEach(resetTransport);

  it('discards a read superseded by an edit', discardsAReadSupersededByAnEdit);
  it('shows no stale record after a local refusal', discardsAReadSupersededByALocalRefusal);
}

/**
 * Registers the money-rendering case.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function moneyRenderingCases(): void {
  beforeEach(resetTransport);
  afterEach(resetTransport);

  it('renders every amount through the baseline edit mask', rendersMoneyThroughTheEditMask);
}

describe('AccountViewScreen identifier handoff', handoffPrivacyCases);
describe('AccountViewScreen read sequencing', readSequencingCases);
describe('AccountViewScreen money rendering', moneyRenderingCases);
