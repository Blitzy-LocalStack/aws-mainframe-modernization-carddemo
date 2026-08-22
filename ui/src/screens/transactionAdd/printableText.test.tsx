/**
 * @file Proves the transaction add screen admits only printable US-ASCII into its five free-text
 * controls, leaves every other field to its own composition rule, adopts a stored value verbatim, and
 * renders the service's own refusal beneath the control it names.
 *
 * Purpose
 * -------
 * A review found that `source`, `description`, `merchantName`, `merchantCity` and `merchantZip` — the
 * `PIC X` spans an external producer authors — carried a width limit and no character-set limit at all,
 * on a path that ends in two fixed-width sinks with no escaping mechanism: the plain-text statement's
 * 80-column bands and the transaction report's 133-column records. A carriage return stored in one of
 * those spans becomes a second record no reader can tell from a real one, and a code point above `0x7E`
 * cannot be encoded US-ASCII at all, so it is stored on one turn and poisons a batch run on a later one.
 * The domain is closed in three places at once — the request record, the service guard and this screen —
 * and these cases are the screen's half.
 *
 * Assumptions: the domain is printable US-ASCII, code points `0x20` to `0x7E` inclusive, and it is
 * DERIVED rather than chosen: `com.carddemo.common.codec.FixedWidthCodec` encodes US-ASCII and refuses
 * what its charset cannot round-trip, so everything above the span is unencodable, and everything below
 * it -- together with `0x7F` -- encodes cleanly and is copied verbatim into a record that cannot escape
 * it. `services/transaction-service/src/main/resources/openapi/transaction-api.yaml` publishes the same
 * span as `^[\x20-\x7E]*$` on `TransactionSource`, `TransactionDescription`, `MerchantName`,
 * `MerchantCity` and `MerchantZip`, so the control and the operation admit one set.
 *
 * Assumptions: this is a parity FIX and not the divergence rule T9 would have to document. The
 * reference's only writer into these spans is a 3270 field -- `TRNSRC` at `app/bms/COTRN02.bms` L148 and
 * its four siblings -- on a single-byte code page, so a control character and a supplementary code point
 * were never accepted values on this screen; the browser removed a bound the terminal enforced
 * physically, and these cases put it back.
 *
 * Assumptions: every callback is a NAMED function declaration rather than an inline arrow, for the two
 * reasons the sibling suites in this directory record -- `ui/eslint.config.js` requires a documentation
 * block on a function expression in any position, and Prettier moves a block comment that follows an
 * argument comma onto the preceding literal, detaching it from what it documents.
 */

import { ConfigProvider } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../api/client';
import { addTransaction, copyLastTransaction } from '../../api/transactions';
import type { CopiedTransactionData, TransactionAddOutcome } from '../../api/transactions';
import type { ApiError } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { MESSAGE_BAND_TEST_ID } from '../../layout/MessageBand';
import { PROGRAM_MESSAGES } from '../../messages/messages';
import { cardDemoTheme } from '../../theme/antdTheme';
import {
  TRANSACTION_ADD_FIELD_LABELS,
  TRANSACTION_ADD_KEY_LABELS,
  TransactionAddScreen,
  paintCopiedValues,
  retainPrintableText,
  toEditMaskAmount,
} from './index';
import type { TransactionAddValues } from './index';

/**
 * Builds the mocked surface of the ledger transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory held in a `const`, because
 * Vitest lifts every `vi.mock` call above the imports and a `const` factory would be in its temporal
 * dead zone at registration time.
 * @returns {Record<string, unknown>} The transport functions this screen can reach, each a fresh spy.
 */
function mockLedgerTransportModule(): Record<string, unknown> {
  return {
    addTransaction: vi.fn(),
    copyLastTransaction: vi.fn(),
  };
}

vi.mock('../../api/transactions', mockLedgerTransportModule);

/** Sentences this program declares, from the single catalog that owns them. */
const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;

/** An account identifier at its declared eleven-digit width. */
const ACCOUNT_ID = '00000000011';

/** The masked rendering the service publishes for the card the cross-reference resolved. */
const RESOLVED_CARD_MASKED = '************1111';

/** The opaque binding the preview publishes so a confirming turn can name the same resolved card. */
// The value is deliberately low-entropy; `ui/src/api/transactions.test.ts` records why.
const CONFIRMATION_TOKEN = 'v2.aaaaaaaaaaaaaaaa.notarealsealedvalue';

/**
 * The five controls whose characters an operator authors with no other composition rule.
 *
 * Assumptions: exactly these five and no others, which is the same scope the screen filters. Every other
 * editable field already has a composition authority -- the two keys, the type, the category and the
 * merchant identifier are numeric, the amount carries the `+99999999.99` edit mask, the two dates carry
 * the `YYYY-MM-DD` mask and the confirmation is one letter -- so a filter on those would be a second
 * authority over a value one rule already decides.
 */
const FREE_TEXT_FIELDS = [
  'source',
  'description',
  'merchantName',
  'merchantCity',
  'merchantZip',
] as const;

/**
 * One value carrying every class of inadmissible character, bracketed by two that are admissible.
 *
 * Assumptions: the seven interior code points are the five the finding names plus two more that share
 * their two failure modes. `\r` and `\n` are the plain-text injection vector: both encode cleanly as
 * US-ASCII and end a record in every line-oriented sink. `\t` and `\u0000` encode cleanly too and are
 * copied into a fixed-width column where they occupy a position and print as nothing. `\u007F` is the
 * same case at the top of the C0 span. `é` is unencodable US-ASCII in one UTF-16 unit and `𝒜` is
 * unencodable in a surrogate pair, which is the case a naive per-unit filter would miss.
 *
 * Assumptions: the whole literal is ten UTF-16 units, which is the narrowest of the five declared widths
 * -- `TRNSRCI PIC X(10)` and `MZIPI PIC X(10)` in `app/cpy-bms/COTRN02.CPY` -- so no case below is
 * observing `maxLength` truncation where it means to observe the filter.
 */
const EVERY_INADMISSIBLE_CHARACTER = 'A\r\n\t\u0000\u007Fé𝒜B';

/** What {@link EVERY_INADMISSIBLE_CHARACTER} leaves once the domain has been applied. */
const ONLY_THE_ADMISSIBLE_CHARACTERS = 'AB';

/**
 * The sentence transaction-service publishes when a description leaves the domain.
 *
 * Assumptions: this is a literal rather than a catalog reading, and the difference is the point.
 * `ui/src/messages/messages.ts` owns the sentences the REFERENCE words, and this one has no reference
 * counterpart -- `app/cbl/COTRN02C.cbl` has no such refusal, because its terminal made the value
 * unkeyable -- so the sentence is authored by the service and arrives in the response body. It is
 * `TransactionAddRequest.DESCRIPTION_NOT_PRINTABLE` verbatim, and the screen's own
 * `resolveApiFieldErrors` renders a response's own sentence for every refusal but the two calendar
 * verdicts.
 */
const SERVICE_DESCRIPTION_REFUSAL = 'Description must be printable text...';

/** A stored description carrying the line break that would open a record in a plain-text sink. */
const STORED_INJECTED_DESCRIPTION = 'FUEL\r\nPURCHASE';

/** A stored description carrying a control character a text input keeps, so a control can display it. */
const STORED_TABBED_DESCRIPTION = 'FUEL\tPURCHASE';

/** Values the screen holds before a copy lands, used by the pure adoption case. */
const BLANK: TransactionAddValues = {
  accountId: ACCOUNT_ID,
  cardNumber: '',
  typeCode: '',
  categoryCode: '',
  source: '',
  description: '',
  amount: '',
  originDate: '',
  processDate: '',
  merchantId: '',
  merchantName: '',
  merchantCity: '',
  merchantZip: '',
  confirmation: '',
};

/**
 * Builds the eleven copied columns the service publishes, with the description a caller chooses.
 *
 * Assumptions: every other member is an ordinary in-domain value, so a case asserting on the description
 * is observing the one span it varies.
 * @param {string} description - The stored narrative, which a legacy row may carry out of domain.
 * @returns {CopiedTransactionData} The copied columns and the keys the service resolved.
 */
function copiedValues(description: string): CopiedTransactionData {
  return {
    sourceTransactionId: '0000000000683580',
    typeCode: '07',
    categoryCode: '0042',
    source: 'POS TERM',
    description,
    merchantId: '000123456',
    merchantName: 'COPIED MERCHANT',
    merchantCity: 'COPIED CITY',
    merchantZip: '30301',
    originDate: '2024-03-14',
    processDate: '2024-03-15',
  };
}

/**
 * Builds the withheld preview a copy turn answers with.
 * @param {string} description - The stored narrative the copy carries onto the glass.
 * @returns {TransactionAddOutcome} The 200 outcome carrying the copied record.
 */
function copiedPreview(description: string): TransactionAddOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      amount: '1234.56',
      written: false,
      returnMessage: null,
      resolvedAccountId: ACCOUNT_ID,
      resolvedCardNumberMasked: RESOLVED_CARD_MASKED,
      confirmationToken: CONFIRMATION_TOKEN,
      copied: copiedValues(description),
    },
  };
}

/**
 * Builds the problem document a refused capture arrives with.
 *
 * Assumptions: every member the published shape declares is supplied, including the two this screen never
 * renders, because the type is the contract's own and a partial fixture would describe a body no service
 * sends. The severity is `WARNING` for the same reason: the shared handler grades every 4xx that way, so
 * a fixture claiming a higher severity would describe a refusal the service does not publish.
 * @param {string} field - The member the service attributed the refusal to.
 * @param {string} message - The sentence the service published for it.
 * @returns {ApiError} The normalised problem document.
 */
function problem(field: string, message: string): ApiError {
  return {
    code: 'CARDDEMO-0400',
    secondaryCode: '',
    message,
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId: 'CD0000000000000000000001',
    path: '/api/v1/transactions',
    timestamp: '2026-01-15T00:00:00.000000Z',
    fieldErrors: [{ field, state: 'NOT_OK', message }],
    abend: null,
  };
}

/**
 * Builds the rejection a refused capture arrives as.
 * @param {string} field - The member the service attributed the refusal to.
 * @param {string} message - The sentence the service published for it.
 * @returns {ApiRequestError} The rejection the transport module raises.
 */
function refusal(field: string, message: string): ApiRequestError {
  return new ApiRequestError('PROBLEM', 400, problem(field, message), 'PROBLEM 400');
}

/**
 * Renders the screen inside the one shell the application mounts.
 *
 * Assumptions: the real `AppShell` is mounted rather than stubbed, because the sentence a refused turn
 * puts on row 23 is painted by the shell's message band from what this screen publishes -- a stub would
 * assert this screen's intent instead of the delivered rendering.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={['/transactions/new']}>
        <AppShell>
          <TransactionAddScreen />
        </AppShell>
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * Builds the interaction driver with the inter-keystroke delay suppressed.
 *
 * Assumptions: `delay: null` is load-bearing rather than a speed preference, for the reason
 * `transactionAddCopy.test.tsx` measures: a case that fills twelve React-controlled controls runs well
 * inside the per-case budget alone and past it under the full parallel suite.
 * @returns {ReturnType<typeof userEvent.setup>} The configured driver.
 */
function operatorDriver(): ReturnType<typeof userEvent.setup> {
  return userEvent.setup({ delay: null });
}

/** Clears the transport spies so no case inherits another's queued outcome. */
function resetSpies(): void {
  vi.mocked(addTransaction).mockReset();
  vi.mocked(copyLastTransaction).mockReset();
}

/**
 * Returns one control by the label the mapset declares for it.
 *
 * Assumptions: the control is found by its transcribed LABEL rather than by an identifier, because the
 * identifier is derived per instance from `useId` and is not computable from outside. Finding it by label
 * also asserts the label association an operator using assistive technology depends on.
 * @param {keyof typeof TRANSACTION_ADD_FIELD_LABELS} field - The field whose control is wanted.
 * @returns {HTMLElement} The rendered control.
 */
function controlFor(field: keyof typeof TRANSACTION_ADD_FIELD_LABELS): HTMLElement {
  return screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS[field]);
}

/**
 * Sets one control's whole value in a single change event, which is what a paste delivers.
 *
 * Assumptions: one change event per control rather than keystroke-level typing, matching the sibling
 * suite's measured reason, and it is also the honest shape for the vector under test: an inadmissible
 * character reaches a browser field by being pasted far more often than by being typed.
 * @param {keyof typeof TRANSACTION_ADD_FIELD_LABELS} field - The field to set.
 * @param {string} value - The value to place in the control.
 * @returns {void} Completion is the updated control.
 */
function setField(field: keyof typeof TRANSACTION_ADD_FIELD_LABELS, value: string): void {
  fireEvent.change(controlFor(field), { target: { value } });
}

/**
 * Reads one control's current value.
 * @param {keyof typeof TRANSACTION_ADD_FIELD_LABELS} field - The field to read.
 * @returns {string} The value the control currently holds.
 */
function fieldValue(field: keyof typeof TRANSACTION_ADD_FIELD_LABELS): string {
  const control = controlFor(field);
  return control instanceof HTMLInputElement ? control.value : '';
}

/**
 * Reads the sentence the shell's message band is currently painting.
 * @returns {string} The band's text, empty when it is painting nothing.
 */
function bandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/**
 * Builds the whole admitted domain, one character per code point in the span.
 *
 * Assumptions: the string is CONSTRUCTED from the two endpoints rather than typed out, so the case
 * asserts the span the filter declares instead of a transcription of it -- a typed literal could omit a
 * character and the case would still pass.
 * @returns {string} Every code point from `0x20` to `0x7E` inclusive, in order.
 */
function everyAdmissibleCharacter(): string {
  const characters: string[] = [];
  for (let code = 0x20; code <= 0x7e; code += 1) {
    characters.push(String.fromCodePoint(code));
  }
  return characters.join('');
}

/**
 * Fills every data control with an in-domain value, so a case can vary exactly one of them.
 *
 * Assumptions: the amount is supplied in the screen's own `+99999999.99` mask and the dates in the
 * `YYYY-MM-DD` shape, because the client-side chain at `dataFieldFailure` refuses either otherwise and
 * the case would then be observing the wrong refusal.
 * @returns {void} Completion is the filled form.
 */
function fillEveryDataField(): void {
  setField('accountId', ACCOUNT_ID);
  setField('typeCode', '07');
  setField('categoryCode', '0042');
  setField('source', 'POS TERM');
  setField('description', 'KEYED PURCHASE');
  setField('amount', '+00000042.00');
  setField('originDate', '2024-03-14');
  setField('processDate', '2024-03-15');
  setField('merchantId', '000123456');
  setField('merchantName', 'KEYED MERCHANT');
  setField('merchantCity', 'KEYED CITY');
  setField('merchantZip', '30301');
}

/**
 * Proves the filter drops every class of inadmissible character, including a surrogate pair.
 *
 * Assumptions: the supplementary code point is asserted separately as well as inside the mixed literal,
 * because a filter written over UTF-16 units rather than code points would leave a lone surrogate behind
 * -- a value that is neither the character the producer sent nor encodable at all.
 * @returns {void} Nothing; the case asserts.
 */
function dropsEveryInadmissibleCharacter(): void {
  expect(retainPrintableText(EVERY_INADMISSIBLE_CHARACTER)).toBe(ONLY_THE_ADMISSIBLE_CHARACTERS);
  expect(retainPrintableText('FUEL\rPURCHASE')).toBe('FUELPURCHASE');
  expect(retainPrintableText('FUEL\nPURCHASE')).toBe('FUELPURCHASE');
  expect(retainPrintableText('FUEL\tPURCHASE')).toBe('FUELPURCHASE');
  expect(retainPrintableText('FUEL\u0000PURCHASE')).toBe('FUELPURCHASE');
  expect(retainPrintableText('FUEL\u007FPURCHASE')).toBe('FUELPURCHASE');
  expect(retainPrintableText('CAFÉ')).toBe('CAF');
  expect(retainPrintableText('𝒜')).toBe('');
  expect(retainPrintableText('\r\n\t\u0000\u007Fé𝒜')).toBe('');
}

/**
 * Proves the filter narrows nothing a producer legitimately sends.
 *
 * Assumptions: the whole span is asserted unchanged, and the punctuation a merchant name actually
 * carries -- the ampersand, apostrophe, hyphen, period, comma and solidus -- is asserted again by name,
 * because those six are the characters a tighter alphanumeric domain would have silently removed from a
 * name like `O'BRIEN & SONS`.
 *
 * Assumptions: the space is asserted INSIDE the domain, unlike the neighbouring identifier domain
 * `UserService.ADDRESSABLE_USER_ID` declares, which admits letters and digits only. A user identifier is
 * delimited by white space and addressed as a URI path segment, so it can contain neither; these five
 * spans are prose and routinely do, and the reference's own source value is `POS TERM`.
 *
 * Refactoring Rationale: this named `UserService.CANONICAL_USER_ID` and said that domain "begins at
 * `0x21`". Both halves went stale when the identifier expression was renamed and narrowed to
 * `[A-Za-z0-9]+`, so it no longer begins at a byte value at all. The contrast is restated on the
 * narrower domain rather than dropped, because the space being outside the identifier domain and inside
 * this one is the property this case exists to pin, and it holds more strongly than before. The same
 * correction is made at the declaration this file mirrors, in `TransactionAddRequest.java`, so the two
 * cannot disagree about what the neighbouring domain admits.
 * @returns {void} Nothing; the case asserts.
 */
function keepsEveryAdmissibleCharacter(): void {
  const admissible = everyAdmissibleCharacter();

  expect(retainPrintableText(admissible)).toBe(admissible);
  expect(admissible).toHaveLength(95);
  expect(retainPrintableText("O'BRIEN & SONS, LTD. A/B - C")).toBe("O'BRIEN & SONS, LTD. A/B - C");
  expect(retainPrintableText(' ')).toBe(' ');
  expect(retainPrintableText('~')).toBe('~');
  expect(retainPrintableText('')).toBe('');
}

/**
 * Proves a pasted value reaches every one of the five free-text controls filtered.
 *
 * Assumptions: all five are exercised in one case over one render, and the results are compared as ONE
 * object so a failure names the offending field rather than the first assertion that ran.
 *
 * Assumptions: `\r` and `\n` are inside the pasted literal and are asserted absent from the control, but
 * the layer that removes them is not asserted, because two layers do. A text input's own value
 * sanitization strips both before any handler sees them, which is why the reference's terminal is not the
 * only thing that made them unkeyable -- and it is also why the API-side refusal proved by
 * `TransactionCaptureWireContractTest` is the one that matters for them: they reach the service from a
 * producer that is not this screen.
 * @returns {void} Nothing; the case asserts.
 */
function filtersEveryFreeTextControl(): void {
  render(renderScreen());

  const held: Record<string, string> = {};
  for (const field of FREE_TEXT_FIELDS) {
    setField(field, EVERY_INADMISSIBLE_CHARACTER);
    held[field] = fieldValue(field);
  }

  expect(held).toEqual({
    source: ONLY_THE_ADMISSIBLE_CHARACTERS,
    description: ONLY_THE_ADMISSIBLE_CHARACTERS,
    merchantName: ONLY_THE_ADMISSIBLE_CHARACTERS,
    merchantCity: ONLY_THE_ADMISSIBLE_CHARACTERS,
    merchantZip: ONLY_THE_ADMISSIBLE_CHARACTERS,
  });
}

/**
 * Proves a TYPED inadmissible character never appears, which is what the terminal did physically.
 *
 * Assumptions: one control is typed into rather than all five, because the change handler is shared and
 * the previous case has already pinned the scope; what this case adds is the keystroke path, where the
 * character arrives one at a time and the filter runs on each re-render.
 * @returns {Promise<void>} Resolves once the value has been typed.
 */
async function filtersATypedCharacter(): Promise<void> {
  const operator = operatorDriver();
  render(renderScreen());

  await operator.type(controlFor('description'), 'CAFÉ BAR');

  expect(fieldValue('description')).toBe('CAF BAR');
}

/**
 * Proves the filter's scope stops at the five, so each other field keeps its own single authority.
 *
 * Assumptions: the merchant identifier is the field chosen because its authority is the reference's own
 * class test at `app/cbl/COTRN02C.cbl` L430-L436, so the case can assert BOTH halves of the scope
 * decision: the control keeps what was pasted, and the transcribed sentence is still what refuses it.
 * Filtering it here would have made the reference's refusal unreachable -- the value would have become
 * `1234`, which is numeric -- and that is a parity loss the finding does not ask for.
 *
 * Assumptions: the sentence is expected TWICE, because the reference moves one string into `WS-MESSAGE`
 * and highlights the field it names, so the migrated screen paints it in the band and again beneath the
 * control.
 * @returns {Promise<void>} Resolves once the refusal has been published.
 */
async function leavesTheNumericFieldsToTheirOwnRule(): Promise<void> {
  const operator = operatorDriver();
  render(renderScreen());

  fillEveryDataField();
  setField('merchantId', '12\t34');
  await operator.click(screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.ENTER }));

  expect(fieldValue('merchantId')).toBe('12\t34');
  expect(await screen.findAllByText(ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC)).toHaveLength(2);
  expect(addTransaction).not.toHaveBeenCalled();
}

/**
 * Proves a copy adopts a stored value VERBATIM, out of domain and all.
 *
 * Assumptions: this is the deliberate exception to the filter and the case exists so it cannot be
 * "tidied" into consistency. A row stored before the domain was closed may carry anything the old
 * contract admitted, and silently repairing it here would put a value on the glass that is not the one
 * stored and then write that repaired value back under an operator's confirmation -- a change to a record
 * nobody asked for. Adopting it unchanged means the service refuses it and the operator is told which
 * field to fix, which is the only outcome that leaves the stored row and the operator's intent both
 * intact.
 * @returns {void} Nothing; the case asserts.
 */
function adoptsAStoredValueVerbatim(): void {
  const adopted = paintCopiedValues(
    BLANK,
    copiedValues(STORED_INJECTED_DESCRIPTION),
    {
      accountId: ACCOUNT_ID,
      cardNumberMasked: RESOLVED_CARD_MASKED,
      confirmationToken: CONFIRMATION_TOKEN,
    },
    toEditMaskAmount('1234.56'),
  );

  expect(adopted.description).toBe(STORED_INJECTED_DESCRIPTION);
  expect(retainPrintableText(adopted.description)).not.toBe(adopted.description);
}

/**
 * Proves the service's own refusal is rendered beneath the control it names.
 *
 * Assumptions: the value under refusal arrives by COPY rather than by keystroke, because that is the one
 * route by which an out-of-domain value can still reach the service from this screen -- the filter closes
 * every other -- so this case is the end of the finding's own path: a legacy row is copied, the operator
 * confirms it, and the service refuses it by field.
 *
 * Assumptions: the binding is asserted as well as the text. `Form.Item` positions its help container
 * visually and gives it no identifier, so a sentence rendered without `aria-describedby` reaches a
 * sighted operator and nobody else; asserting the described element by identifier proves the refusal is
 * attached to the description control rather than merely somewhere on the screen.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function rendersTheServiceRefusalUnderTheFieldItNames(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(copiedPreview(STORED_TABBED_DESCRIPTION));
  vi.mocked(addTransaction).mockRejectedValue(refusal('description', SERVICE_DESCRIPTION_REFUSAL));
  const operator = operatorDriver();
  render(renderScreen());

  await operator.type(controlFor('accountId'), ACCOUNT_ID);
  await operator.click(screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.PFK05 }));
  await waitFor(
    /**
     * Waits until the copied description has been painted into its control, unchanged.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    (): void => {
      expect(fieldValue('description')).toBe(STORED_TABBED_DESCRIPTION);
    },
  );

  await operator.type(controlFor('confirmation'), 'Y');
  await operator.click(screen.getByRole('button', { name: TRANSACTION_ADD_KEY_LABELS.ENTER }));

  const control = await waitFor(
    /**
     * Waits until the description control has been marked invalid.
     * @returns {HTMLElement} The refused control.
     */
    (): HTMLElement => {
      const candidate = controlFor('description');
      expect(candidate).toHaveAttribute('aria-invalid', 'true');
      return candidate;
    },
  );

  const described = control.getAttribute('aria-describedby') ?? '';
  const target = document.getElementById(described.split(' ')[0] ?? '');
  expect(target?.textContent).toBe(SERVICE_DESCRIPTION_REFUSAL);
  expect(bandText()).toContain(SERVICE_DESCRIPTION_REFUSAL);
  expect(fieldValue('description')).toBe(STORED_TABBED_DESCRIPTION);
}

/**
 * Registers the cases that exercise the filter as a function.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function domainCases(): void {
  it(
    'drops every inadmissible character, including a surrogate pair',
    dropsEveryInadmissibleCharacter,
  );
  it('keeps every character in the admitted span', keepsEveryAdmissibleCharacter);
  it('adopts a stored out-of-domain value verbatim', adoptsAStoredValueVerbatim);
}

/**
 * Registers the cases that exercise the rendered controls.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function renderedDomainCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('filters a pasted value in all five free-text controls', filtersEveryFreeTextControl);
  it('filters a typed character as the terminal did', filtersATypedCharacter);
  it('leaves the merchant identifier to its own class test', leavesTheNumericFieldsToTheirOwnRule);
  it(
    "renders the service's refusal beneath the field it names",
    rendersTheServiceRefusalUnderTheFieldItNames,
  );
}

describe('the transaction add screen admits only printable text', domainCases);

describe('the transaction add screen controls admit only printable text', renderedDomainCases);
