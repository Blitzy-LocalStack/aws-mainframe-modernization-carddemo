/**
 * @file Component tests for the pending-authorization summary in `ui/src/screens/authSummary/index.tsx`.
 *
 * Purpose
 * -------
 * Assert the properties a review found this screen had lost: that a short account identifier is refused
 * locally in the source's own words rather than sent for the service to refuse, that the filter control
 * states its refusal programmatically as well as visually, that the two unlabelled address values are
 * named for assistive technology, that each row carries the mapset's own one-character
 * selection field, that the eight-column
 * table has a narrow-screen policy, that the record panel takes the shared responsive column policy, and
 * that route changes go through the shared navigation seam rather than a second copy of it.
 *
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, matching
 * `ui/src/screens/accountView/accountView.test.tsx` and `ui/src/screens/accountUpdate/accountUpdate.test.tsx`.
 * These cases are about what the SCREEN does with an outcome, so the shortest honest seam is the function
 * the screen calls.
 *
 * Assumptions: two properties are asserted against the module's SOURCE rather than its rendered output,
 * and both are properties of the code by nature: that this screen consumes the shared column policy
 * instead of restating a column count, and that it holds no second copy of the navigation fallback. A
 * rendered assertion cannot distinguish either — antd resolves a responsive column object itself, and two
 * identical navigation policies produce identical navigations — so the check is made where the difference
 * exists. The sibling module is located with `join(import.meta.dirname, ...)` and NOT with
 * `new URL('./index.tsx', import.meta.url)`: Vite rewrites that exact syntax as its asset-reference
 * pattern at transform time, yielding an `http:` URL that `readFileSync` rejects.
 *
 * Assumptions: every callback below is a NAMED function declaration rather than an inline arrow, for the
 * reason the sibling suites record: `ui/eslint.config.js` selects a function expression in every position
 * so an inline callback owes its own JSDoc block, and Prettier moves a block comment that follows an
 * argument comma onto the preceding literal, detaching it from what it documents.
 */

import { ConfigProvider } from 'antd';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';

import { listPendingAuthorizations } from '../../api/authorization';
import { ApiRequestError } from '../../api/client';
import type {
  ApiError,
  FieldError,
  PendingAuthListItem,
  PendingAuthSummary,
} from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import {
  PERSISTENT_FAILURE_REPORT_IT,
  SHARED_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
} from '../../messages/messages';
import { fieldErrorId } from '../../layout/fieldHelp';
import { cardDemoTheme } from '../../theme/antdTheme';
import {
  AUTH_SUMMARY_COLUMN_HEADERS,
  AUTH_SUMMARY_FIELD_WIDTHS,
  AUTH_SUMMARY_HIDDEN_LABELS,
  AUTH_SUMMARY_SELECTION_CODE,
  AuthSummaryScreen,
  accountIdRefusal,
  describeListingFailure,
  selectionCellLabel,
} from './index';

/**
 * Builds the mocked surface of the authorization transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The transport functions this screen can reach, each a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    listPendingAuthorizations: vi.fn(),
    readPendingAuthorization: vi.fn(),
    markAuthorizationFraud: vi.fn(),
  };
}

vi.mock('../../api/authorization', mockAuthorizationTransportModule);

/** Identifier of the account filter control, as the screen declares it. */
const ACCOUNT_ID_CONTROL_ID = 'auth-summary-account-id';

/** An account identifier of exactly the declared eleven-digit width. */
const ELEVEN_DIGIT_ACCOUNT_ID = '00000000011';

/** An account identifier of nine digits — numeric, and narrower than the field the source pads to. */
const SHORT_ACCOUNT_ID = '000000011';

/** How many rows one page of the source's mapset paints. */
const PAGE_ROWS = 3;

/**
 * Builds the account summary panel the listing returns.
 * @returns {PendingAuthSummary} The summary for the screen to render.
 */
function summary(): PendingAuthSummary {
  return {
    accountId: ELEVEN_DIGIT_ACCOUNT_ID,
    customerName: 'ADA LOVELACE',
    customerId: '000000011',
    addressLine1: '1 ANALYTICAL WAY',
    addressLine2: 'SUITE 1843',
    authStatus: 'Y',
    phoneNumber1: '(212)5550101',
    approvedAuthCnt: 3,
    declinedAuthCnt: 1,
    creditLimit: '5000.00',
    cashLimit: '1000.00',
    creditBalance: '250.00',
    cashBalance: '0.00',
    approvedAuthAmt: '250.00',
    declinedAuthAmt: '10.00',
    accountStatus1: 'AA',
    accountStatus2: 'BB',
    accountStatus3: 'CC',
    accountStatus4: 'DD',
    accountStatus5: 'EE',
  };
}

/**
 * Builds one listed authorization whose identifier is recognisable in an assertion.
 * @param {number} ordinal - Which row this is, used to make every value distinct.
 * @returns {PendingAuthListItem} One row for the table.
 */
function listItem(ordinal: number): PendingAuthListItem {
  const suffix = String(ordinal).padStart(2, '0');

  return {
    key: `SEALED-KEY-${suffix}`,
    transactionId: `TRAN00000000${suffix}`,
    authOrigDate: `07/1${suffix.slice(-1)}/22`,
    authOrigTime: '10:11:12',
    authType: 'PURC',
    approvalStatus: 'A',
    matchStatus: 'P',
    amount: '  1234.56',
    cardNum: '************1111',
  };
}

/**
 * Builds the answer the listing returns, carrying only the members the screen reads.
 * @returns {object} The response shape the screen destructures.
 */
function listing(): {
  readonly summary: PendingAuthSummary;
  readonly screenMessage: string | null;
  readonly page: {
    readonly items: readonly PendingAuthListItem[];
    readonly firstKey: string | null;
    readonly lastKey: string | null;
    readonly hasNext: boolean;
    readonly hasPrevious: boolean;
  };
} {
  const items = Array.from(
    { length: PAGE_ROWS },
    /**
     * Builds the row at one position of the page.
     * @param {unknown} _unused - The array slot's value, which is always `undefined` here.
     * @param {number} index - The zero-based position being filled.
     * @returns {PendingAuthListItem} That position's row.
     */
    (_unused: unknown, index: number): PendingAuthListItem => listItem(index + 1),
  );

  return {
    summary: summary(),
    screenMessage: null,
    page: {
      items,
      firstKey: items[0]?.key ?? null,
      lastKey: items[items.length - 1]?.key ?? null,
      hasNext: false,
      hasPrevious: false,
    },
  };
}

/**
 * Builds the normalised failure the shared client raises for a refused request.
 * @param {readonly FieldError[]} fieldErrors - Per-field entries the document names.
 * @returns {ApiRequestError} The rejection to configure the stub with.
 */
function refusal(fieldErrors: readonly FieldError[]): ApiRequestError {
  const problem: ApiError = {
    code: 'CARDDEMO-PAUS-0001',
    secondaryCode: '',
    message: 'Please correct the highlighted fields',
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId: 'UITESTAUTH000000000AA',
    path: '/api/v1/authorizations',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors,
    abend: null,
  };

  return new ApiRequestError('PROBLEM', 400, problem, 'PROBLEM 400');
}

/**
 * Renders the screen inside the theme, the one shell and a router.
 * @returns {ReactElement} The composed tree under test.
 */
function renderAuthSummary(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/authorizations']}>
        <AppShell>
          <Routes>
            <Route path="/authorizations" element={<AuthSummaryScreen />} />
            <Route path="/authorizations/:key" element={<div>AUTHORIZATION DETAIL</div>} />
            <Route path="/menu" element={<div>ORDINARY MENU</div>} />
          </Routes>
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Returns the stub standing in for the listing.
 * @returns {MockedFunction<typeof listPendingAuthorizations>} The mocked transport function.
 */
function listStub(): MockedFunction<typeof listPendingAuthorizations> {
  return vi.mocked(listPendingAuthorizations);
}

/** Clears the transport stub so no case inherits another's queued outcome. */
function resetTransport(): void {
  listStub().mockReset();
}

/**
 * Returns the account filter control.
 * @returns {HTMLInputElement} The rendered control.
 */
function filterControl(): HTMLInputElement {
  const element = document.getElementById(ACCOUNT_ID_CONTROL_ID);
  expect(element).not.toBeNull();

  return element as HTMLInputElement;
}

/**
 * Reads this module's own source, for the two properties that exist only in the code.
 * @returns {string} The module source.
 */
function moduleSource(): string {
  return readFileSync(join(import.meta.dirname, 'index.tsx'), 'utf8');
}

/**
 * Types an account identifier into the filter and submits the turn.
 * @param {string} entry - The identifier to type.
 * @returns {Promise<void>} Resolves once the turn has been submitted.
 */
async function submitFilter(entry: string): Promise<void> {
  render(renderAuthSummary());
  await userEvent.type(filterControl(), entry);
  await userEvent.keyboard('{Enter}');
}

/**
 * Reads a page so the panel and the table are populated.
 * @returns {Promise<void>} Resolves once the first row is on screen.
 */
async function fetchPage(): Promise<void> {
  listStub().mockResolvedValue(listing());
  await submitFilter(ELEVEN_DIGIT_ACCOUNT_ID);
  await screen.findByText(listItem(1).transactionId);
}

/**
 * Builds a listing answer this case settles itself, so a read can be held in flight across a turn.
 * @returns {{ promise: Promise<ReturnType<typeof listing>>; settle: () => void }} The held answer and
 *   the function that delivers it.
 */
function heldListing(): {
  readonly promise: Promise<ReturnType<typeof listing>>;
  readonly settle: () => void;
} {
  /**
   * Stands in until the promise's executor has run, so the binding is never read unset.
   * @returns {never} Never returns; a call means the case settled before the read was armed.
   * @throws {Error} Always, for that reason.
   */
  function notYetArmed(): never {
    throw new Error('the held listing was settled before it was armed');
  }

  let deliver: (answer: ReturnType<typeof listing>) => void = notYetArmed;
  const promise = new Promise<ReturnType<typeof listing>>(
    /**
     * Captures the answering route without taking it.
     * @param {(answer: ReturnType<typeof listing>) => void} resolve - Answers the held read.
     * @returns {void} Nothing; the read is held until the case answers it.
     */
    function holdItOpen(resolve): void {
      deliver = resolve;
    },
  );

  return {
    promise,
    /**
     * Answers the held read with a full page for the account it was opened under.
     * @returns {void} Nothing; the awaiting caller resumes.
     */
    settle(): void {
      deliver(listing());
    },
  };
}

/**
 * A read still in flight cannot repaint the account panel after the scope has been refused away.
 *
 * ⚠️ Purpose: the panel above the table carries the account holder's NAME, both address lines,
 * the phone number and four money totals, and this screen writes it from inside the read rather than
 * from the page envelope the paging hook guards. A turn whose entry is blank or non-numeric clears the
 * scope and the panel, and it used to leave the outstanding read admissible -- so that read settled and
 * put the previous account holder's details back above an emptied entry field, attributing them to
 * nothing the operator could see.
 *
 * Assumptions: the verdict is read off the CUSTOMER NAME and the address line, because those two come
 * only from the summary the read writes. The rows are not asserted: the paging hook discards a
 * superseded page on its own, so a table assertion would pass with the defect fully present.
 *
 * Assumptions: the second turn is REFUSED rather than scoped to another account, because the refusal path
 * is the one that lacked the withdrawal -- the scope-change path had it -- and a case that scoped to a
 * second account would exercise the half that already worked.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function doesNotRepaintARefusedScopeFromAReadInFlight(): Promise<void> {
  const held = heldListing();

  listStub().mockReturnValueOnce(held.promise);

  render(renderAuthSummary());
  await userEvent.type(filterControl(), ELEVEN_DIGIT_ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  expect(
    listStub(),
    'the first read must be outstanding for the ordering to be under test',
  ).toHaveBeenCalledTimes(1);

  await userEvent.clear(filterControl());
  await userEvent.type(filterControl(), SHORT_ACCOUNT_ID);
  await userEvent.keyboard('{Enter}');

  expect(screen.getByText(accountIdRefusal('NOT_OK'))).toBeInTheDocument();

  await act(
    /**
     * Delivers the answer to the read the refused turn left outstanding.
     * @returns {Promise<void>} Resolves once its continuation has run.
     */
    async (): Promise<void> => {
      held.settle();
      await Promise.resolve();
    },
  );

  const cleared = summary();

  /*
   * Assumptions: the two values are read through `String(...)` because the published summary types them
   *   as nullable -- a row may carry no name and no address -- and a matcher will not take `null`. The
   *   fixture supplies both, so the conversion narrows a type without weakening the assertion.
   */
  expect(
    screen.queryByText(String(cleared.customerName)),
    'the refused turn cleared the panel, so the account holder must not reappear',
  ).toBeNull();
  expect(
    screen.queryByText(String(cleared.addressLine1)),
    'nor may the address the same summary carried',
  ).toBeNull();
  expect(
    listStub(),
    'a refused scope issues no read of its own, so the count must not have moved',
  ).toHaveBeenCalledTimes(1);
}

/**
 * Builds the classified failure the browse publishes, from a status and the sentence a service sent.
 *
 * Assumptions: the classified failure is built rather than the bare problem document, because that is
 * what `usePagedQuery` publishes on `failure` and what the screen now reads. Passing a document alone
 * would exercise only the unclassified arm, leaving the three arms that decide between a service
 * sentence, a momentary outage and a permanent fault untested while every case still passed.
 *
 * Assumptions: the remedy is left to the constructor's default, which derives it from the kind and the
 * status through the transport module's own `remedyFor`. Supplying one here would let this file assert a
 * transient judgement the client does not actually reach for the status under test.
 * @param {number} status - Status the answer carried.
 * @param {string | null} message - Sentence the service sent, or `null` when it sent none -- which is
 *   what {@link ApiRequestError} carries for a timeout, a network failure and any body that was not a
 *   problem document.
 * @returns {ApiRequestError} The failure the transport module would raise.
 */
function failureWith(status: number, message: string | null): ApiRequestError {
  const problem: ApiError = {
    code: 'CARDDEMO-PAUS-0002',
    secondaryCode: '',
    message,
    severity: 'CRITICAL',
    subsystem: 'APPLICATION',
    status,
    correlationId: 'UITESTAUTH000000000BB',
    path: '/api/v1/authorizations',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors: [],
    abend: null,
  };
  return new ApiRequestError('PROBLEM', status, problem, `test failure ${String(status)}`);
}

/**
 * A 404 is reported as an unexpected condition and never as an account that does not exist.
 *
 * ⚠️ Purpose: this operation declares NO 404 -- an account with no summary row answers 200 with
 * zero counts and an empty page, which the contract states on the 200 itself -- so the mapping that
 * turned a 404 into "Account ID NOT found..." described an outcome the service cannot produce. A 404
 * arriving anyway means the request reached something other than the operation, and the sentence has to
 * say so rather than making a routing fault look like a business answer.
 *
 * Assumptions: the answer's OWN sentence is asserted absent as well, and the failure is given one for
 * that assertion to bite on. Whatever produced an undeclared 404 is not this service, so its body is a
 * proxy's text, and the branch has to be ordered ahead of the verbatim arm to keep it off the band --
 * which is exactly what fails if the two are transposed.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function reportsAnUndeclared404AsAnUnexpectedCondition(): void {
  const notice = describeListingFailure(failureWith(404, 'Not Found'));

  expect(notice?.message).toBe(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  expect(
    notice?.message,
    'a status the operation does not declare is not an account-not-found answer',
  ).not.toBe(SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND);
  expect(notice?.message, 'and a proxy sentence is not shown to the operator').not.toBe(
    'Not Found',
  );
}

/**
 * A momentary service outage says it may clear, and a permanent fault says it will not.
 *
 * ⚠️ Purpose: this is the distinction the previous arrangement could not draw. Every status at or
 * above 500 took the abend replacement, so `503` -- which `TRANSIENT_STATUSES` in
 * `ui/src/api/client.ts` declares a condition that may clear on its own -- was reported identically to
 * a `500` that will not. An operator was told an outage was an abend and given no reason to press Enter
 * again.
 *
 * Assumptions: BOTH sentences are asserted in one case, because the property under test is that the two
 * statuses differ. A case asserting only the transient arm would pass against a screen that showed the
 * momentary sentence for every failure, which is the same defect with the opposite sign.
 *
 * Assumptions: neither failure carries a sentence, which is what a bodiless answer and a proxy's HTML
 * both produce -- the client synthesises a document with `message: null` for them. That is the only
 * state in which the classification decides the wording, so it is the state this case supplies.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function distinguishesAMomentaryOutageFromAPermanentFault(): void {
  expect(
    describeListingFailure(failureWith(503, null))?.message,
    'a status the transport module classifies as transient invites another attempt',
  ).toBe(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(
    describeListingFailure(failureWith(500, null))?.message,
    'and one it does not asks for the failure to be reported instead',
  ).toBe(PERSISTENT_FAILURE_REPORT_IT);
}

/**
 * A service fault the service described is shown in the service's own words.
 *
 * Assumptions: a 500 is used deliberately, because it is the status the previous arrangement replaced
 * unconditionally. The diagnostics register maps `COPAUS0C.cbl` L476 and L509 to
 * `UNEXPECTED_ABEND_OCCURRED`, and the service sends that sentence in `message` -- so rendering the
 * document verbatim shows the register's own replacement rather than substituting for it, and the
 * sentence reaches the operator from the one place authorised to author it.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function showsAServiceFaultInTheServicesOwnWords(): void {
  expect(
    describeListingFailure(failureWith(500, SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED))?.message,
  ).toBe(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
}

/**
 * A failure the transport module could not classify keeps the unexpected-condition sentence.
 *
 * Assumptions: a plain `Error` stands for the whole family, because the property is that the value was
 * NOT narrowable and not which unnarrowable value it was. Both authored sentences describe a request
 * that reached a classifier, so applying either to a value that did not would assert more than is
 * known -- and the abend replacement is the closest target analogue of the baseline's own
 * unexpected-condition arm.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function keepsTheAbendSentenceForAnUnclassifiedFailure(): void {
  const notice = describeListingFailure(new Error('something no service described'));

  expect(notice?.message).toBe(SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED);
  expect(notice?.message, 'an unclassified value is not reported as a momentary outage').not.toBe(
    TRANSIENT_FAILURE_TRY_AGAIN,
  );
  expect(
    notice?.message,
    'nor is its own developer sentence put on the operator message band',
  ).not.toBe('something no service described');
}

/**
 * A refusal the service authored is shown in its own words.
 *
 * Assumptions: a 400 is used, because that is a status this operation DOES declare -- so its body is the
 * service's own sentence and showing it unchanged is Transformation Rule T8 rather than a fall-through.
 * @returns {void} Completion of the case; the assertions are its effect.
 */
function showsAnAuthoredRefusalInItsOwnWords(): void {
  expect(
    describeListingFailure(failureWith(400, 'Please correct the highlighted fields'))?.message,
  ).toBe('Please correct the highlighted fields');
}

/**
 * A numeric identifier narrower than the declared field is refused locally, in the source's words.
 *
 * Assumptions: BOTH halves are asserted — the sentence appears AND no request was issued — because the
 * defect was that a short entry passed local validation and was sent, so a case that only checked the
 * sentence would pass against a screen that showed the service's refusal one round trip later.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesAShortIdentifierLocally(): Promise<void> {
  await submitFilter(SHORT_ACCOUNT_ID);

  await waitFor(
    /**
     * Waits for the numeric refusal the program moves into its message field.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.getByText(accountIdRefusal('NOT_OK'))).toBeInTheDocument();
    },
  );
  expect(listStub()).not.toHaveBeenCalled();
}

/**
 * An identifier of exactly the declared width is accepted and reaches the transport.
 *
 * Assumptions: this is the other side of the width rule, and it is asserted because a fix that refused
 * everything would satisfy the refusal case on its own.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function acceptsTheDeclaredWidth(): Promise<void> {
  await fetchPage();

  expect(listStub()).toHaveBeenCalledWith({ accountId: ELEVEN_DIGIT_ACCOUNT_ID });
}

/**
 * The filter states a service refusal programmatically as well as visually.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function linksTheFilterRefusalToTheControl(): Promise<void> {
  listStub().mockRejectedValue(
    refusal([{ field: 'accountId', state: 'NOT_OK', message: 'Acct Id must be Numeric ...' }]),
  );

  await submitFilter(ELEVEN_DIGIT_ACCOUNT_ID);

  await waitFor(
    /**
     * Waits for the refusal to reach the control the document named.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(filterControl()).toHaveAttribute('aria-invalid', 'true');
    },
  );
  const help = document.getElementById(fieldErrorId(ACCOUNT_ID_CONTROL_ID));
  expect(help).not.toBeNull();
  expect(help?.textContent).toBe('Acct Id must be Numeric ...');
  expect(filterControl().getAttribute('aria-describedby')).toContain(
    fieldErrorId(ACCOUNT_ID_CONTROL_ID),
  );
}

/**
 * Both address values carry a name, and no panel entry is left nameless.
 *
 * Assumptions: the assertion is that NO label cell in the panel is empty, rather than that the two
 * address cells hold particular text. An empty header cell is the defect — a value announced with no
 * name — so asserting its absence catches any entry that loses its name, not only these two.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function namesBothAddressLines(): Promise<void> {
  await fetchPage();

  expect(screen.getByText(AUTH_SUMMARY_HIDDEN_LABELS.addressLine1)).toBeInTheDocument();
  expect(screen.getByText(AUTH_SUMMARY_HIDDEN_LABELS.addressLine2)).toBeInTheDocument();
  const panel = document.querySelector('.ant-descriptions');
  expect(panel).not.toBeNull();
  const nameless = Array.from(panel?.querySelectorAll('.ant-descriptions-item-label') ?? []).filter(
    /**
     * Keeps a label cell that carries no text.
     * @param {Element} cell - One label cell of the panel.
     * @returns {boolean} `true` when the cell is empty.
     */
    (cell: Element): boolean => (cell.textContent ?? '').trim() === '',
  );
  expect(nameless).toEqual([]);
}

/**
 * Every row carries the mapset's own one-character selection field, named for the row it belongs to.
 *
 * ⚠️ Refactoring Rationale: this case asserted a `radiogroup` containing five `radio` controls. It now
 * asserts five one-character ENTRY fields, because that is what `COPAU00.bms` declares -- `SEL0001`
 * through `SEL0005` are `ATTRB=(FSET,NORM,UNPROT) ... LENGTH=1` at L277 to L282 and the four repeats --
 * and because the screen's own row-22 sentence instructs the operator to TYPE `'S'`, which a radio can
 * never obey. The group's contribution was one tab stop with arrow traversal; five unprotected fields
 * are five tab stops, which is what the terminal's own tab key gave them.
 *
 * ⚠️ Assumptions: the declared width is asserted alongside the count, because a one-character field that
 * admits two characters would let an operator type a value the source's `EVALUATE` could never receive.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsTheOneCharacterRowSelectors(): Promise<void> {
  await fetchPage();

  const table = listingTable();
  const controls = within(table).getAllByRole('textbox');
  expect(controls).toHaveLength(PAGE_ROWS);
  expect(
    within(table).getByRole('textbox', {
      name: selectionCellLabel(listItem(1).transactionId),
    }),
  ).toBe(controls[0]);
  expect(controls[0]).toHaveAttribute('maxLength', String(AUTH_SUMMARY_FIELD_WIDTHS.selection));

  /*
   * WHY : ⚠️ Assumptions: the ABSENCE of a radio is asserted with a non-throwing query, because
   *       `getByRole` throws when nothing matches and a throwing query cannot express "there is none
   *       of these". This half is what stops the radio column returning beside the typed one: two
   *       controls for one `LENGTH=1` field would give the operator two ways to say one thing, and one
   *       of them could not carry the character the source evaluates.
   */
  expect(screen.queryAllByRole('radio')).toEqual([]);
}

/**
 * Returns the row list's table, distinguished from the record panel's.
 *
 * ⚠️ Assumptions: TWO tables render on this screen and a bare role query is therefore ambiguous. The
 * record panel above the rows is an antd `Descriptions` with `bordered`, which emits a real `<table>`
 * of its own, so the row list has to be identified by something only it has. Its selection column
 * heading is that thing -- the mapset declares the column and its heading itself at `COPAU00.bms` L197
 * to L201 -- so the identification is a painted baseline string rather than a test hook, and it is the
 * same identification `src/test/authSummary.test.tsx` makes for the same reason.
 * @returns {HTMLElement} The table carrying the listed authorizations.
 * @throws {Error} If no table carries the selection heading, which means the row list did not render.
 */
function listingTable(): HTMLElement {
  const table = screen.getAllByRole('table').find(hasSelectionHeading);
  if (table === undefined) {
    throw new Error(
      `no rendered table carries the '${AUTH_SUMMARY_COLUMN_HEADERS.selection}' heading, so the row ` +
        'list did not render; the record panel renders a table of its own and is not it',
    );
  }
  return table;
}

/**
 * Reports whether a table carries the row list's selection column heading.
 * @param {HTMLElement} candidate - A rendered table.
 * @returns {boolean} `true` when the table declares the selection column.
 */
function hasSelectionHeading(candidate: HTMLElement): boolean {
  return within(candidate).queryAllByText(AUTH_SUMMARY_COLUMN_HEADERS.selection).length > 0;
}

/**
 * Typing the selection character beside a row and pressing Enter reaches that authorization's detail.
 *
 * ⚠️ Assumptions: the character is TYPED rather than a control clicked, which is the whole of the change
 * this case guards -- `PROCESS-ENTER-KEY` reads `SEL0001I` and moves whatever it holds into the
 * selection flag (`COPAUS0C.cbl` L288 to L291), so the typed character is the input and Enter is the
 * commit. It is asserted through the destination rather than through state, so the typed cell and the
 * navigation are shown to address the SAME record.
 * @returns {Promise<void>} Resolves once the assertion has run.
 */
async function navigatesToTheChosenAuthorization(): Promise<void> {
  await fetchPage();

  await userEvent.type(
    screen.getByRole('textbox', { name: selectionCellLabel(listItem(2).transactionId) }),
    AUTH_SUMMARY_SELECTION_CODE,
  );
  await userEvent.keyboard('{Enter}');

  expect(await screen.findByText('AUTHORIZATION DETAIL')).toBeInTheDocument();
}

/**
 * The eight-column table pins its two identifying columns, which requires the scroll policy.
 *
 * Assumptions: the assertion is on the rendered markup rather than on the props, because antd only emits
 * its fixed-column markup when a horizontal scroll extent is also given — so the two class names below
 * prove both halves of the policy at once, and prove them as delivered rather than as configured.
 *
 * Assumptions: the class names are antd 6's LOGICAL-property spellings — `scroll-horizontal`,
 * `has-fix-start` and `cell-fix-start` — and they were read out of the rendered document rather than
 * assumed. The physical spellings a reader would expect from earlier majors (`ant-table-has-fix-left`,
 * `ant-table-cell-fix-left`) are not emitted at all, and asserting them passed for no version.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function pinsTheKeyColumns(): Promise<void> {
  await fetchPage();

  const table = document.querySelector('.ant-table');
  expect(table).not.toBeNull();
  expect(table?.className).toContain('ant-table-scroll-horizontal');
  expect(table?.className).toContain('ant-table-has-fix-start');
  expect(document.querySelectorAll('.ant-table-cell-fix-start').length).toBeGreaterThan(0);
}

/**
 * The record panel takes the shared column policy rather than restating a column count.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function takesTheSharedColumnPolicy(): void {
  const source = moduleSource();

  expect(source).toContain('column={RECORD_VIEW_COLUMNS}');
  expect(source).not.toContain('column={2}');
}

/**
 * Route changes go through the shared navigation seam, with no second copy of its fallback.
 *
 * Assumptions: the absence of the fallback CALL is what is asserted, not the absence of a name. The
 * duplicate helper's whole substance was its `window.location.assign` fallback, so a screen that still
 * held one would still hold the duplication however the function were renamed.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function usesTheSharedNavigationSeam(): void {
  const source = moduleSource();
  const assigning = source.split('\n').filter(
    /**
     * Keeps a line that performs the document-level fallback outside a comment.
     * @param {string} line - One line of the module.
     * @returns {boolean} `true` when the line calls the fallback.
     */
    (line: string): boolean =>
      line.includes('window.location.assign') && !line.trimStart().startsWith('*'),
  );

  expect(source).toContain("from '../../routes/navigation'");
  expect(assigning).toEqual([]);
}

/**
 * Captions the mapset paints beside the six monetary fields, in its own spelling.
 *
 * ⚠️ Assumptions: these are LITERALS transcribed from `app/app-authorization-ims-db2-mq/bms/COPAU00.bms`
 * L143 to L197 rather than an import of the screen's own label constants, and that is the point of them.
 * Importing the screen's constants would make the case agree with the screen by construction; spelling
 * the mapset's `INITIAL` strings here makes it agree with the ORACLE, so a caption that drifted from the
 * source would fail this case rather than travel through it.
 */
const MAPSET_MONEY_CAPTIONS = [
  'Credit Lim:',
  'Cash Lim:',
  'Appr Amt:',
  'Credit Bal:',
  'Cash Bal:',
  'Decl Amt:',
] as const;

/** A caption from the same panel that heads free text rather than a figure, as the negative control. */
const MAPSET_TELEPHONE_CAPTION = 'PH:';

/**
 * Finds the value cell of the panel entry a caption heads.
 *
 * Assumptions: the label is matched on its RAW `textContent` and the value cell is taken as its next
 * element sibling, because a bordered `Descriptions` renders each entry as an adjacent `th`/`td` pair --
 * `antd/lib/descriptions/Row.js` L143 selects `['th', 'td']` when `bordered` is set. Matching raw text
 * matters because several of this panel's captions carry a trailing space the mapset put there.
 * @param {string} caption - The caption exactly as the mapset paints it.
 * @returns {HTMLElement} The cell holding that entry's value.
 * @throws {Error} When no label carries the caption, or the one that does heads no element.
 */
function panelValueCell(caption: string): HTMLElement {
  for (const label of document.querySelectorAll('.ant-descriptions-item-label')) {
    if (label.textContent === caption) {
      const value = label.nextElementSibling;

      if (value instanceof HTMLElement) {
        return value;
      }

      throw new Error(`the panel entry headed "${caption}" heads no value cell`);
    }
  }

  throw new Error(`no panel entry is headed "${caption}"`);
}

/**
 * Every monetary cell anchors its content to its trailing edge, and no other cell does.
 *
 * ⚠️ Purpose: this guards a defect browser measurement found and prose could not. The six amounts sat
 * flush against their cells' LEADING edge, so their trailing edges fell wherever each amount's own box
 * width put them -- two edges 25.203125px apart in every rendered column, at 1280, 768 and 375 alike,
 * because the box widths are `ch` on a fixed-pitch face and never vary with the viewport. The mapset
 * gives each of its money columns one constant length across both rows, so on the terminal the decimal
 * points of a column line up; anchoring the cell's content to its trailing edge is what restores that
 * once the responsive reflow has folded three declared columns into two rendered ones.
 *
 * ⚠️ Assumptions: the assertion is on the CELL's inline style and not on the amount's, which is exactly
 * the distinction the defect turned on. The amount always carried `text-align: end` -- that positions
 * glyphs inside its own box and cannot move the box -- so a case asserting it would have passed
 * throughout the defect. jsdom performs no layout, so the rendered geometry itself is not observable
 * here; the reachable property is that the style arrives on the cell, and the pixels were confirmed
 * separately in a browser.
 *
 * ⚠️ Assumptions: the six are collected and compared as a LIST rather than asserted one at a time, so a
 * failure names which captions lost the anchor instead of stopping at the first. The telephone entry is
 * checked in the same case as the negative half: trailing-edge alignment is right for a column of
 * figures and wrong for free text, so a root-level style that fixed all six by disfiguring the rest
 * would fail here.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anchorsEveryAmountToItsCellsTrailingEdge(): Promise<void> {
  await fetchPage();

  const anchored: string[] = [];

  for (const caption of MAPSET_MONEY_CAPTIONS) {
    if (panelValueCell(caption).style.textAlign === 'end') {
      anchored.push(caption);
    }
  }

  expect(anchored).toEqual([...MAPSET_MONEY_CAPTIONS]);
  expect(panelValueCell(MAPSET_TELEPHONE_CAPTION).style.textAlign).toBe('');
}

/**
 * Every row's selection cell carries an identifier the document can resolve, unique to its row.
 *
 * ⚠️ Purpose: this guards a defect the browser reported and no test could see. DevTools raised "A form
 * field element should have an id or name attribute" against exactly five nodes on this screen -- one per
 * row of a page -- because these cells carried an accessible name and no identifier at all. They were
 * the only form controls in the application in that state; the two sibling browse screens already
 * publish `user-list-action-<userId>` and `ref-type-list-action-<typeCd>` and neither was reported.
 *
 * ⚠️ Assumptions: each identifier is checked by RESOLVING it through the document and comparing the
 * result to the control it came from, rather than by matching it against a pattern. That is the property
 * the platform actually needs -- an identifier that does not resolve, or resolves to something else, is
 * no better than none -- and it catches a duplicate identifier as well as an absent one, because a
 * repeated value would resolve to the first cell and fail the identity comparison for the rest.
 *
 * Assumptions: the accessible NAME is asserted alongside, so a change that supplied the identifier by
 * taking the name away would fail here rather than read as a fix.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function identifiesEveryRowSelector(): Promise<void> {
  await fetchPage();

  const unresolved: string[] = [];
  const identifiers = new Set<string>();

  for (const ordinal of [1, 2, 3]) {
    const cell = screen.getByRole('textbox', {
      name: selectionCellLabel(listItem(ordinal).transactionId),
    });

    identifiers.add(cell.id);

    if (cell.id === '' || document.getElementById(cell.id) !== cell) {
      unresolved.push(listItem(ordinal).transactionId);
    }
  }

  expect(unresolved).toEqual([]);
  expect(identifiers.size).toBe(PAGE_ROWS);
}

/** Registers every case of this suite. */
function authSummaryCases(): void {
  it('refuses a short identifier locally', refusesAShortIdentifierLocally);
  it(
    'does not repaint a refused scope from a read in flight',
    doesNotRepaintARefusedScopeFromAReadInFlight,
  );
  it(
    'reports an undeclared 404 as an unexpected condition',
    reportsAnUndeclared404AsAnUnexpectedCondition,
  );
  it(
    'distinguishes a momentary outage from a permanent fault',
    distinguishesAMomentaryOutageFromAPermanentFault,
  );
  it("shows a service fault in the service's own words", showsAServiceFaultInTheServicesOwnWords);
  it(
    'keeps the abend sentence for an unclassified failure',
    keepsTheAbendSentenceForAnUnclassifiedFailure,
  );
  it('shows an authored refusal in its own words', showsAnAuthoredRefusalInItsOwnWords);
  it('accepts the declared width', acceptsTheDeclaredWidth);
  it('links the filter refusal to the control', linksTheFilterRefusalToTheControl);
  it('names both address lines', namesBothAddressLines);
  it('paints the one-character row selectors', paintsTheOneCharacterRowSelectors);
  it('identifies every row selector', identifiesEveryRowSelector);
  it('navigates to the chosen authorization', navigatesToTheChosenAuthorization);
  it('pins the key columns', pinsTheKeyColumns);
  it('takes the shared column policy', takesTheSharedColumnPolicy);
  it("anchors every amount to its cell's trailing edge", anchorsEveryAmountToItsCellsTrailingEdge);
  it('uses the shared navigation seam', usesTheSharedNavigationSeam);
}

beforeEach(resetTransport);

afterEach(resetTransport);

describe('pending authorization summary screen', authSummaryCases);
