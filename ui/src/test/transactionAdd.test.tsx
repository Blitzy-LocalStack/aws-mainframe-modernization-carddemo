/**
 * @file Component contract for the transaction capture screen, the migrated form of BMS mapset
 * `COTRN02` / map `COTRN2A` and program `app/cbl/COTRN02C.cbl`, mounted at `/transactions/new`.
 *
 * Purpose
 * -------
 * `ui/src/screens/transactionAdd/index.tsx` replaces the densest field-validation chain in the
 * baseline: 61 `DFHMDF` definitions, fourteen editable controls and thirty user-visible sentences.
 * This module asserts the six contracts that survive translation and can be read back off a rendered
 * tree -- the declared field widths, the single initial-cursor field, the verbatim sentence census, the
 * fixed-point money representation, the four-key function legend with its confirmation gate, and the
 * per-field refusal marking -- together with the two negatives that are easiest to get wrong: that the
 * route is NOT administrative, and that no key outside the reference's own four is bound.
 *
 * Parameters (module analogue)
 * ----------------------------
 * None. Vitest loads this file through the `include` list in `ui/vitest.config.ts` and passes it
 * nothing. Every input it needs is either a constant published by a module it imports or a fixture
 * declared below, so a case's inputs are visible in the case.
 *
 * Returns (module analogue)
 * -------------------------
 * Nothing. The module's product is the pass or fail verdict of the cases it registers.
 *
 * Exceptions (module analogue)
 * ----------------------------
 * Nothing is thrown at module scope. Inside a case, a failed expectation throws through Vitest and a
 * missing element throws through Testing Library; both are the reported failure rather than an error
 * to handle. The locator helpers below throw deliberately, and each says so on its own block, so a
 * renamed control fails loudly on the name instead of silently exercising nothing.
 *
 * Why this file is the only verification this screen gets
 * ------------------------------------------------------
 * The COBOL parity oracle under `tests/` cannot reach it. That suite records the reason in as many
 * words: the online `CO*` programs use the CICS command-level API and "cannot run end-to-end without a
 * CICS runtime (absent on the runner); only their extractable field-validation logic is unit-tested"
 * (`tests/README.md` section 1.1). **There is therefore no golden master for `COTRN02C`** -- the oracle
 * covers the batch chain and nothing here. Where a sibling batch program can be compared byte-for-byte
 * against a recorded output, this screen can only be compared against the source it was transcribed
 * from, which is why every number and sentence below carries a file-and-line citation rather than a
 * value someone chose.
 *
 * Two documentation obligations govern this file and they agree
 * ------------------------------------------------------------
 * Rule 1 (Explainability) requires a docstring on every function, class and module entry point stating
 * purpose, parameters, returns and exceptions, and requires every non-obvious decision to carry an
 * inline comment justifying it under one of four named categories. `tests/README.md` section 12
 * imposes the identical obligation on "every new test, fixture builder, helper, mock, and runner
 * routine" and calls it "a hard review gate". The two are the same requirement stated twice, so this
 * file EXTENDS an established house convention rather than importing a foreign one.
 *
 * Assumptions: the four rationale labels are written in the PLURAL form -- `Assumptions:`,
 * `Trade-offs:`, `Alternatives Considered:`, `Refactoring Rationale:` -- and no statement-level
 * `WHAT` label appears anywhere. Both follow `docs/CODE_DOCUMENTATION_STANDARD.md`, whose "one
 * permitted written form" section states that the singular abbreviations are "not permitted", and both
 * are machine-checked by `config/rule1/rule1_gate.py`, which reports a singular label and a
 * statement-level restatement as violations. A file written the other way reads fine and fails the
 * gate.
 *
 * Assumptions: `describe`, `it`, `expect` and `vi` are IMPORTED by name rather than taken as ambient
 * globals, and that is a compile-time requirement rather than a style preference. `ui/tsconfig.json`
 * keeps `"types": []`, which suppresses every ambient `@types` declaration so that no screen can reach
 * a test API; with nothing declared, an omitted import fails `tsc --noEmit` on the symbol it omitted.
 * `ui/vitest.config.ts` sets `globals: true` for the separate reason recorded there, so the injection
 * exists at run time while the declaration does not exist at compile time.
 *
 * Assumptions: the transport module is mocked and no network endpoint or credential appears anywhere in
 * this file. No request-interception library -- Mock Service Worker or any equivalent -- is declared in
 * `ui/package.json`, so `vi.mock` on the one module the screen calls is the seam. That is also the
 * stronger seam here, because several cases assert the exact body the screen SENT and a network-level
 * double would only let them assert the wire bytes.
 */

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

/*
 * WHY : Assumptions: `ApiRequestError` is reached through the contract of the transport module this
 *       file mocks. `ui/src/api/transactions.ts` documents, on both operations, that it throws "An
 *       `ApiRequestError` from `./client`" for every transport failure, carrying the normalised
 *       problem document -- so the class is part of that operation's declared surface rather than an
 *       unrelated import.
 *       Alternatives Considered: rejecting with a plain object carrying a `status` member. Rejected
 *       because `screenMessageForFailure` reaches every one of its eight refusal sentences only through
 *       `isApiRequestError`, which is an `instanceof` test: a lookalike takes the unnameable-failure
 *       arm instead, so all six lookup cases below would assert the wrong sentence and pass.
 */
import { ApiRequestError } from '../api/client';
import { addTransaction, copyLastTransaction } from '../api/transactions';
import type { TransactionAddOutcome } from '../api/transactions';
import type { ApiError, FieldError } from '../api/types';
import { CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import type { CicsAid } from '../layout/usePfKeys';
import {
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MESSAGE_BAND,
  MESSAGE_BAND_BY_MAPSET,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
} from '../messages/messages';
import { ROUTE_TABLE, TRANSACTION_ADD_PATH } from '../router';
import type { RouteTableEntry } from '../router';
import {
  TRANSACTION_ADD_CONFIRM_DOMAIN_HINT,
  TRANSACTION_ADD_FIELD_LABELS,
  TRANSACTION_ADD_FIELD_WIDTHS,
  TRANSACTION_ADD_KEY_LABELS,
  TRANSACTION_ADD_MAPSET,
  TRANSACTION_ADD_PROGRAM_NAME,
  TRANSACTION_ADD_TRANSACTION_ID,
  TransactionAddScreen,
} from '../screens/transactionAdd';
import type { TransactionAddField, TransactionAddValues } from '../screens/transactionAdd';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  fieldError,
  pressPfKey,
  renderInAppShell,
  seedSession,
} from './setup';

/**
 * Builds the mocked surface of the ledger transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory held in a `const`. Vitest
 * lifts every `vi.mock` call above the imports, so a `const` factory would still be in its temporal
 * dead zone when the registration runs.
 *
 * Assumptions: exactly the two operations this screen calls are replaced, so a screen that reached for
 * a third would fail on an undefined member rather than silently receive a stub.
 * @returns {Record<string, unknown>} The two transport operations, each a fresh spy.
 */
function mockLedgerTransportModule(): Record<string, unknown> {
  return {
    addTransaction: vi.fn(),
    copyLastTransaction: vi.fn(),
  };
}

vi.mock('../api/transactions', mockLedgerTransportModule);

/** The twenty-seven sentences this program declares, from the one catalog that owns them. */
const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;

/**
 * One editable field of the mapset, with the width its symbolic map declares and where it declares it.
 *
 * Assumptions: `symbolicName` and `copybookLine` are carried beside the width so each row is a
 * CITATION rather than a number someone chose. A bare width table would agree with the screen's own
 * constant table by construction and prove only that the two files were copied from each other; a row
 * naming `ACTIDINI` at `app/cpy-bms/COTRN02.CPY` L60 can be checked against the copybook by a reader
 * with no access to either file's history.
 */
interface DeclaredField {
  /** Field name the screen and the service contract both use. */
  readonly field: TransactionAddField;
  /** COBOL data-name in the `01 COTRN2AI` symbolic map. */
  readonly symbolicName: string;
  /** Width from the field's `PIC X(n)` clause. */
  readonly declaredWidth: number;
  /** One-based line of that declaration in `app/cpy-bms/COTRN02.CPY`. */
  readonly copybookLine: number;
}

/*
 * WHY : Assumptions: every width below was read from the SYMBOLIC MAP `01 COTRN2AI` in
 *       `app/cpy-bms/COTRN02.CPY` and never from the 350-byte ledger record in `app/cpy/CVTRA05Y.cpy`,
 *       and the two disagree in six places -- the map is narrower at all six. The map is what governs a
 *       control's `maxLength` because it is the constraint the operator actually met: the terminal
 *       refused the sixty-first description character, so a browser accepting the record's hundred
 *       would let an operator key a value the screen it replaces could not hold.
 * WHY : Assumptions: the rows are in the map's own declaration order, which is also the mapset's
 *       ascending row-and-column order and therefore the order an operator tabbed through the fields.
 *       Keeping that order lets this one table serve as both the width census and the fill sequence, so
 *       a field added to the screen cannot be filled by one and missed by the other.
 */

/** The fourteen editable fields, their declared widths and their declaring lines. */
const DECLARED_FIELDS = [
  { field: 'accountId', symbolicName: 'ACTIDINI', declaredWidth: 11, copybookLine: 60 },
  { field: 'cardNumber', symbolicName: 'CARDNINI', declaredWidth: 16, copybookLine: 66 },
  { field: 'typeCode', symbolicName: 'TTYPCDI', declaredWidth: 2, copybookLine: 72 },
  { field: 'categoryCode', symbolicName: 'TCATCDI', declaredWidth: 4, copybookLine: 78 },
  { field: 'source', symbolicName: 'TRNSRCI', declaredWidth: 10, copybookLine: 84 },
  { field: 'description', symbolicName: 'TDESCI', declaredWidth: 60, copybookLine: 90 },
  { field: 'amount', symbolicName: 'TRNAMTI', declaredWidth: 12, copybookLine: 96 },
  { field: 'originDate', symbolicName: 'TORIGDTI', declaredWidth: 10, copybookLine: 102 },
  { field: 'processDate', symbolicName: 'TPROCDTI', declaredWidth: 10, copybookLine: 108 },
  { field: 'merchantId', symbolicName: 'MIDI', declaredWidth: 9, copybookLine: 114 },
  { field: 'merchantName', symbolicName: 'MNAMEI', declaredWidth: 30, copybookLine: 120 },
  { field: 'merchantCity', symbolicName: 'MCITYI', declaredWidth: 25, copybookLine: 126 },
  { field: 'merchantZip', symbolicName: 'MZIPI', declaredWidth: 10, copybookLine: 132 },
  { field: 'confirmation', symbolicName: 'CONFIRMI', declaredWidth: 1, copybookLine: 138 },
] as const satisfies readonly DeclaredField[];

/**
 * The six protected header-band fields the same symbolic map declares, with their widths.
 *
 * Assumptions: these are recorded rather than asserted against a rendered element, because the band is
 * DELEGATED. `useShellSlot` hands the transaction identifier and the program name to the one `AppShell`
 * that `ui/src/App.tsx` mounts, and the shell owns the title band's own rendering and its own tests --
 * so a case here reading the band's markup would assert another module's contract. What this file
 * checks is the half the screen supplies: that the two values it publishes fit the fields they land in.
 */
const DECLARED_HEADER_BAND_FIELDS = [
  { symbolicName: 'TRNNAMEI', declaredWidth: 4, copybookLine: 24 },
  { symbolicName: 'TITLE01I', declaredWidth: 40, copybookLine: 30 },
  { symbolicName: 'CURDATEI', declaredWidth: 8, copybookLine: 36 },
  { symbolicName: 'PGMNAMEI', declaredWidth: 8, copybookLine: 42 },
  { symbolicName: 'TITLE02I', declaredWidth: 40, copybookLine: 48 },
  { symbolicName: 'CURTIMEI', declaredWidth: 8, copybookLine: 54 },
] as const;

/**
 * Width of `ERRMSGI`, declared `PIC X(78)` at `app/cpy-bms/COTRN02.CPY` L144 and `LENGTH=78` at
 * `app/bms/COTRN02.bms` L293-L296.
 *
 * Assumptions: this is the DISPLAY field's width and it is deliberately NOT reconciled with the
 * seventy-five characters of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` at `app/cpy/CVCRD01Y.cpy`
 * L28-L30. Both numbers are real and they measure different stages: seventy-five is the content limit
 * for any sentence that crosses the shared work area, seventy-eight is the region it is finally moved
 * into, and COBOL pads the difference with spaces. "Correcting" either to the other would clip three
 * characters from every message or overstate the content limit by three.
 */
const ERROR_MESSAGE_DISPLAY_WIDTH = 78;

/** One-based line of the `IC` attribute in `app/bms/COTRN02.bms`, which no other field carries. */
const INITIAL_CURSOR_ATTRIBUTE_LINE = 85;

/**
 * Declared length of the row-24 legend literal at `app/bms/COTRN02.bms` L297-L302.
 *
 * Assumptions: the four labels the screen publishes rejoin to exactly this many characters when
 * separated by the two spaces the source uses, which is what lets this file check the legend against
 * the mapset WITHOUT retyping the literal. Retyping it would put a second copy of a source string in
 * the tree, and a paraphrase in the screen plus the same paraphrase here would agree while both drifted
 * from the mapset.
 */
const LEGEND_DECLARED_LENGTH = 53;

/** The separator the row-24 literal puts between its key descriptors, measured from the same lines. */
const LEGEND_SEGMENT_SEPARATOR = '  ';

/** An account identifier filled to its declared eleven digits, as `ACTIDINI` requires. */
const ACCOUNT_ID = '00000000011';

/** A card number filled to its declared sixteen digits, as `CARDNINI` requires. */
const CARD_NUMBER = '4111111111111111';

/**
 * The card number as the service publishes it: reduced to its last four digits.
 *
 * Assumptions: the reduced form is the only form this screen ever receives. AAP section 0.4.1.9
 * reduces a primary account number everywhere except the administrative card-detail endpoint, and a
 * capture screen is not that endpoint, so the sixteen digits never reach this browser to be reduced
 * here. The case that reads the confirmation summary asserts both halves of that: the reduced form is
 * rendered, and the sixteen-digit form appears nowhere in the document.
 */
const RESOLVED_CARD_MASKED = '************1111';

/** The opaque binding a preview publishes so a confirming turn names the same resolved card. */
const CONFIRMATION_TOKEN = 'v2.aaaaaaaaaaaaaaaa.notarealsealedvalue';

/**
 * The amount as the operator keys it: the twelve-character edit mask, with a LEADING MINUS.
 *
 * ⚠️ Trade-offs: money is a `string` here and at every hop, and the reason is exactness rather than
 * tidiness. `app/cpy/CVTRA05Y.cpy` declares `TRAN-AMT PIC S9(09)V99` -- an exact fixed-point value --
 * which the target carries as `NUMERIC(12,2)` in PostgreSQL and `BigDecimal` at scale 2 with
 * `RoundingMode.HALF_UP` in Java, and transports as a JSON STRING. A JSON number is parsed into an
 * IEEE-754 double by essentially every client, and a double cannot represent every two-decimal value,
 * so routing money through one silently changes cents. This is the highest-risk area in the migration
 * precisely because the failure is silent: it produces plausible numbers that are wrong, on a screen
 * that captures financial transactions. No amount in this file is put through a numeric conversion, a
 * decimal-place formatter, a unary plus or any arithmetic operator; every one is compared as text.
 *
 * Assumptions: the sign is MANDATORY and the mask is twelve characters -- a sign, eight integer digits,
 * a point and two decimals -- which is `WS-TRAN-AMT-E PIC +99999999.99` at `app/cbl/COTRN02C.cbl` L59
 * and the `(-99999999.99)` hint the mapset paints at `app/bms/COTRN02.bms` L208-L212. A negative value
 * is therefore an ORDINARY value here, not an edge case, and one case asserts it survives the round
 * trip with its sign.
 */
const KEYED_NEGATIVE_AMOUNT = '-00001234.56';

/** The same amount in the monetary form the service contract declares, still a string. */
const WIRE_NEGATIVE_AMOUNT = '-1234.56';

/**
 * A complete capture that passes every rule the browser can check, so a case can break exactly one.
 *
 * Assumptions: every numeric field is filled to its FULL declared width, because `isNumericField`
 * applies the reference's `IS NOT NUMERIC` test to the padded field rather than to the keyed
 * characters -- `app/cbl/COTRN02C.cbl` L197 refuses a partly-keyed account identifier exactly as it
 * refuses a letter, since a 3270 `RECEIVE MAP` pads the remainder with spaces and a space is not
 * numeric. A fixture keying `11` into the eleven-character account field would be refused for a reason
 * no case intended.
 *
 * Assumptions: the card number is BLANK while the account identifier is filled, because the reference's
 * ordered `EVALUATE TRUE` at L195-L230 resolves by account whenever the account field carries anything
 * and overwrites the keyed card number from the cross-reference at L209. A fixture filling both would
 * make every case a card case that happened to be addressed by account.
 *
 * Assumptions: the confirmation is blank, so an unmodified fixture submits the reference's PREVIEW turn
 * rather than a write. Every case that writes supplies the answer explicitly, which keeps a write
 * visible in the case that performs it.
 */
const A_COMPLETE_CAPTURE: TransactionAddValues = {
  accountId: ACCOUNT_ID,
  cardNumber: '',
  typeCode: '01',
  categoryCode: '0001',
  source: 'POS TERM',
  description: 'A MERCHANDISE PURCHASE',
  amount: KEYED_NEGATIVE_AMOUNT,
  originDate: '2024-03-14',
  processDate: '2024-03-15',
  merchantId: '000123456',
  merchantName: 'A MERCHANT NAME',
  merchantCity: 'A MERCHANT CITY',
  merchantZip: '30301',
  confirmation: '',
};

/**
 * Builds the 200 answer an unconfirmed turn receives: the normalised amount and the resolved pair.
 *
 * Assumptions: `copied` is `null` because an ordinary capture copies nothing. The copy-last action is
 * the only turn whose answer carries the eleven recalled values, and it has its own cases; a default
 * carrying them would make every preview here look like a copy.
 * @param {string} amount - The amount as the service normalised it, in its own monetary form.
 * @returns {TransactionAddOutcome} The withheld-write outcome.
 */
function previewOutcome(amount: string): TransactionAddOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      amount,
      written: false,
      returnMessage: null,
      resolvedAccountId: ACCOUNT_ID,
      resolvedCardNumberMasked: RESOLVED_CARD_MASKED,
      confirmationToken: CONFIRMATION_TOKEN,
      copied: null,
    },
  };
}

/**
 * Builds the 201 answer a confirmed turn receives.
 *
 * Assumptions: the identifier is the SERVICE'S. The baseline reads the highest key and increments it at
 * `app/cbl/COTRN02C.cbl` L444 and L449 with nothing holding a lock across the two statements, so the
 * migrated form assigns it server-side and no client generates one.
 * @param {string} amount - The amount as the service normalised it, in its own monetary form.
 * @returns {TransactionAddOutcome} The written outcome.
 */
function createdOutcome(amount: string): TransactionAddOutcome {
  return {
    outcome: 'CREATED',
    created: {
      transactionId: '0000000000683581',
      amount,
      returnMessage: 'Transaction added successfully.  Your Tran ID is 0000000000683581.',
    },
  };
}

/**
 * Builds the normalised failure the transport raises for one refused turn.
 *
 * Assumptions: a real `ApiRequestError` and not a lookalike, for the reason recorded on its import: the
 * screen's failure classification reaches its refusal sentences only through an `instanceof` test.
 * @param {number} status - HTTP status the service answered with.
 * @param {readonly FieldError[]} fieldErrors - Per-field refusals the problem document attributes, in
 *   the order the service marked them, which is the order the screen marks its controls in.
 * @returns {ApiRequestError} The failure a rejected promise carries.
 */
function transportFailure(
  status: number,
  fieldErrors: readonly FieldError[] = [],
): ApiRequestError {
  const problem: ApiError = apiError({ status, fieldErrors });
  return new ApiRequestError('PROBLEM', status, problem, `refused with ${String(status)}`);
}

/**
 * Renders the screen inside the one shell the application mounts, at its own route.
 *
 * ⚠️ Assumptions: the shell is REQUIRED rather than convenient. This screen delegates its title band,
 * its row-23 message line and its row-24 key legend to the single `AppShell` that `ui/src/App.tsx`
 * mounts -- a screen painting them too would render two of each -- so neither the message band nor the
 * function-key legend that most cases below read exists when the screen is rendered bare.
 *
 * Assumptions: `routePath` is passed as well as `initialEntries` so the subject is mounted through the
 * shell's outlet at the pattern `ui/src/router.tsx` declares, which is the composition the application
 * actually builds.
 * @returns {Promise<Awaited<ReturnType<typeof renderInAppShell>>>} The render result, with the
 *   keyboard and pointer operator attached.
 */
async function mountCaptureScreen(): Promise<Awaited<ReturnType<typeof renderInAppShell>>> {
  return await renderInAppShell(<TransactionAddScreen />, {
    initialEntries: [TRANSACTION_ADD_PATH],
    routePath: TRANSACTION_ADD_PATH,
  });
}

/**
 * Locates one editable control by the label literal the mapset paints beside it.
 *
 * Assumptions: the lookup is by LABEL and not by test identifier, because the label is the mapset's own
 * `INITIAL=` literal and is therefore the thing a golden-master reading of this screen would compare.
 * A test identifier would keep passing after a label was reworded.
 * @param {TransactionAddField} field - Field whose control is wanted.
 * @returns {HTMLInputElement} The control.
 * @throws {Error} If no control carries that label, which Testing Library raises, or if the element
 *   found is not an input -- either of which means the screen no longer renders the field.
 */
function control(field: TransactionAddField): HTMLInputElement {
  const found = screen.getByLabelText(TRANSACTION_ADD_FIELD_LABELS[field]);
  if (!(found instanceof HTMLInputElement)) {
    throw new Error(`the control for ${field} is not an input element`);
  }
  return found;
}

/**
 * Reads one control's current value.
 *
 * Assumptions: the return type is `string` unconditionally, which is the DOM's own contract for an
 * input value and is the property the money cases depend on -- there is no code path here through which
 * an amount could become a number.
 * @param {TransactionAddField} field - Field to read.
 * @returns {string} The characters the control currently holds.
 */
function valueOf(field: TransactionAddField): string {
  return control(field).value;
}

/**
 * Reads the row-23 message band the shell paints for this screen.
 * @returns {HTMLElement} The band element.
 * @throws {Error} If no band is rendered, which Testing Library raises. Absence means the subject was
 *   mounted without the shell around it.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Waits until the message band carries one sentence, and reports nothing else.
 *
 * Assumptions: the comparison is `toHaveTextContent` against the TRIMMED sentence rather than an exact
 * text match. Several catalogued strings are padded to a declared `PIC X` width -- the invalid-key
 * sentence is forty-eight characters inside a `PIC X(50)` field -- and the DOM collapses trailing
 * whitespace on display, so trimming compares the content the operator reads while leaving the
 * catalog's own value untouched.
 * @param {string} sentence - The catalogued sentence, taken from the message catalog and never retyped.
 * @returns {Promise<void>} Resolves once the band carries it.
 */
async function expectBandSentence(sentence: string): Promise<void> {
  await waitFor(
    /**
     * Asserts the band's text once it has settled.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand()).toHaveTextContent(sentence.trim());
    },
  );
}

/**
 * Locates the legend control that raises one attention identifier.
 *
 * ⚠️ Assumptions: the control is found by its `aria-keyshortcuts` attribute inside the legend's own
 * navigation landmark, and both halves matter. `PfKeyBar` publishes the browser key each control stands
 * for in that attribute, so matching it identifies the control by the KEY it raises rather than by the
 * label beside it -- which is what lets a case assert that a label and its key belong together instead
 * of assuming it. Scoping to the landmark is what keeps the query off the form's own submit control,
 * which carries the screen title rather than a key label.
 * @param {string} browserKey - The keyboard key the control advertises, such as `Enter` or `F5`.
 * @returns {HTMLElement} The legend control.
 * @throws {Error} If the legend renders no control for that key, so a key silently dropped from the
 *   bindings fails here rather than exercising nothing.
 */
function legendControl(browserKey: string): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const found = legend.querySelector(`button[aria-keyshortcuts="${browserKey}"]`);
  if (!(found instanceof HTMLElement)) {
    throw new Error(`the function-key legend renders no control for ${browserKey}`);
  }
  return found;
}

/**
 * Lists every control the legend renders, in the order it renders them.
 * @returns {readonly HTMLButtonElement[]} The legend's controls.
 * @throws {Error} If the legend landmark is absent, which Testing Library raises.
 */
function legendControls(): readonly HTMLButtonElement[] {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return Array.from(legend.querySelectorAll('button'));
}

/**
 * Locates the form's single confirmation trigger.
 *
 * ⚠️ Assumptions: the control is located by being the only one inside the `<form>`, and NOT by its
 * accessible name. Querying `getByRole('button', { name })` fails intermittently here for a reason
 * worth recording: antd renders a loading indicator inside the button while a turn settles, that
 * indicator carries `aria-label="loading"`, and the accessible-name algorithm concatenates it with the
 * label -- so the name is `loading Add Transaction` for as long as the leave animation runs. Counting
 * the form's controls is stable and additionally asserts the property this screen wants: the form
 * offers exactly ONE way to commit.
 * @returns {HTMLElement} The confirmation trigger.
 * @throws {Error} If the form is absent or renders a number of controls other than one.
 */
function confirmationTrigger(): HTMLElement {
  const form = document.querySelector('form');
  if (form === null) {
    throw new Error('the capture form is not rendered');
  }
  const buttons = within(form).getAllByRole('button');
  const only = buttons[0];
  if (buttons.length !== 1 || only === undefined) {
    throw new Error(`the capture form renders ${String(buttons.length)} controls, expected one`);
  }
  return only;
}

/**
 * Reports the `Form.Item` wrapping one control, which carries the refusal state antd renders.
 * @param {TransactionAddField} field - Field whose item is wanted.
 * @returns {HTMLElement} The form item.
 * @throws {Error} If the control is not inside a form item, which means the screen stopped rendering
 *   the field through the shared field renderer and its refusal state is no longer expressible.
 */
function formItem(field: TransactionAddField): HTMLElement {
  const item = control(field).closest('.ant-form-item');
  if (!(item instanceof HTMLElement)) {
    throw new Error(`the control for ${field} is not inside a form item`);
  }
  return item;
}

/**
 * Reports whether one field is currently marked as refused.
 *
 * Assumptions: two independent signals are read and both must agree -- `aria-invalid` on the control
 * and the error class antd puts on the item. The first is what an operator using assistive technology
 * hears and the second is what a sighted operator sees, and the migrated form of
 * `app/cpy/CSSETATY.cpy` owes both: that copybook moves `DFHRED` into the field's colour attribute,
 * which is a purely visual signal the 3270 had no accessible counterpart for.
 * @param {TransactionAddField} field - Field to inspect.
 * @returns {boolean} `true` when the field is marked refused by both signals.
 */
function isMarkedRefused(field: TransactionAddField): boolean {
  return (
    control(field).getAttribute('aria-invalid') === 'true' &&
    formItem(field).classList.contains('ant-form-item-has-error')
  );
}

/**
 * Converts an antd token name into the CSS custom-property segment it resolves to.
 *
 * Assumptions: antd 6 themes through CSS variables, so a token reaches the DOM as a `var(--...)`
 * reference whose name is the token's own identifier in kebab case. Deriving the segment from
 * `ui/src/theme/tokens.ts` rather than writing the property name out is what keeps this file free of a
 * literal design value while still asserting that the amount resolves through the intended token.
 * @param {string} tokenName - The antd token identifier, as the theme bridge records it.
 * @returns {string} The kebab-case segment of the custom property it becomes.
 */
function cssVariableSegment(tokenName: string): string {
  /**
   * Replaces one capital with its hyphenated lower-case form.
   * @param {string} upper - The matched capital letter.
   * @returns {string} The replacement.
   */
  function hyphenate(upper: string): string {
    return `-${upper.toLowerCase()}`;
  }
  return tokenName.replace(/[A-Z]/gu, hyphenate);
}

/**
 * One turn the browser itself refuses, and the sentence the reference raises for it.
 *
 * Assumptions: `blank` is carried separately from the sentence because `app/cpy/CSSETATY.cpy` draws
 * that distinction and the target owes it: L18-L22 moves the error colour in for a not-OK OR a blank
 * field, and L23-L25 nests an additional literal asterisk inside the blank test alone. A row therefore
 * states which of the two conditions it produces rather than leaving a case to guess.
 */
interface RefusedTurn {
  /** Members of the complete capture this row replaces, which is what makes the turn refusable. */
  readonly overrides: Partial<TransactionAddValues>;
  /** Field the refusal names, which is where the reference moves the cursor. */
  readonly field: TransactionAddField;
  /** The verbatim sentence, read from the catalog and never retyped. */
  readonly sentence: string;
  /** One-based line of the `MOVE` that raises it in `app/cbl/COTRN02C.cbl`. */
  readonly programLine: number;
  /** Whether the refused field is blank, which alone earns the asterisk marker. */
  readonly blank: boolean;
}

/*
 * WHY : Alternatives Considered: each sentence below is READ FROM THE CATALOG and none is retyped as a
 *       literal, and the alternative is worth naming because it looks harmless. A retyped sentence
 *       asserts that the screen agrees with THIS FILE, which a paraphrase in both places satisfies --
 *       so a reworded message would keep every case green while transformation rule T8's
 *       character-for-character guarantee was already broken. Passing the catalog entry asserts that the
 *       screen agrees with the CATALOG, which is the one place the baseline's strings live.
 * WHY : Assumptions: five details of these strings are content rather than accident and are preserved by
 *       reading them from the catalog rather than by re-keying them. `can NOT be empty` and `NOT found`
 *       capitalise NOT and are never normalised to "cannot" or "not"; `Unable to lookup Card # in XREF
 *       file...` carries a literal hash standing for the word "number"; `Unable to lookup Acct in XREF
 *       AIX file...` abbreviates "Account" and names the alternate index -- a mainframe term surviving
 *       into text an operator reads, which is preserved rather than modernised; `Invalid value. Valid
 *       values are (Y/N)...` puts parentheses round the domain and a full stop after "value"; and both
 *       format sentences carry a literal mask, `-99999999.99` and `YYYY-MM-DD`, which are user-visible
 *       specifications rather than descriptions of one.
 * WHY : Assumptions: the row order is the reference's own evaluation order -- the two key fields, then
 *       the eleven blank tests, then the two numeric tests, the amount shape, the two date shapes and
 *       LAST the merchant identifier. That last position is deliberate: the merchant test is not part of
 *       the numeric `EVALUATE` at L322-L337 but a standalone `IF` at L430-L436 placed after both
 *       date-utility calls, so grouping it with the other numeric tests would report it earlier than the
 *       reference does.
 */

/** The three refusals the key-field paragraph raises, from `app/cbl/COTRN02C.cbl` L195-L230. */
const KEY_FIELD_REFUSALS = [
  {
    overrides: { accountId: '0000000001A' },
    field: 'accountId',
    sentence: ADD_MESSAGES.ACCOUNT_ID_MUST_BE_NUMERIC,
    programLine: 199,
    blank: false,
  },
  {
    overrides: { accountId: '', cardNumber: '411111111111111A' },
    field: 'cardNumber',
    sentence: ADD_MESSAGES.CARD_NUMBER_MUST_BE_NUMERIC,
    programLine: 213,
    blank: false,
  },
  {
    overrides: { accountId: '', cardNumber: '' },
    field: 'accountId',
    sentence: ADD_MESSAGES.ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED,
    programLine: 226,
    blank: true,
  },
] as const satisfies readonly RefusedTurn[];

/** The eleven emptiness refusals, in the reference's order, from L254 through L314. */
const BLANK_FIELD_REFUSALS = [
  {
    overrides: { typeCode: '' },
    field: 'typeCode',
    sentence: ADD_MESSAGES.TYPE_CD_CAN_NOT_BE_EMPTY,
    programLine: 254,
    blank: true,
  },
  {
    overrides: { categoryCode: '' },
    field: 'categoryCode',
    sentence: ADD_MESSAGES.CATEGORY_CD_CAN_NOT_BE_EMPTY,
    programLine: 260,
    blank: true,
  },
  {
    overrides: { source: '' },
    field: 'source',
    sentence: ADD_MESSAGES.SOURCE_CAN_NOT_BE_EMPTY,
    programLine: 266,
    blank: true,
  },
  {
    overrides: { description: '' },
    field: 'description',
    sentence: ADD_MESSAGES.DESCRIPTION_CAN_NOT_BE_EMPTY,
    programLine: 272,
    blank: true,
  },
  {
    overrides: { amount: '' },
    field: 'amount',
    sentence: ADD_MESSAGES.AMOUNT_CAN_NOT_BE_EMPTY,
    programLine: 278,
    blank: true,
  },
  {
    overrides: { originDate: '' },
    field: 'originDate',
    sentence: ADD_MESSAGES.ORIG_DATE_CAN_NOT_BE_EMPTY,
    programLine: 284,
    blank: true,
  },
  {
    overrides: { processDate: '' },
    field: 'processDate',
    sentence: ADD_MESSAGES.PROC_DATE_CAN_NOT_BE_EMPTY,
    programLine: 290,
    blank: true,
  },
  {
    overrides: { merchantId: '' },
    field: 'merchantId',
    sentence: ADD_MESSAGES.MERCHANT_ID_CAN_NOT_BE_EMPTY,
    programLine: 296,
    blank: true,
  },
  {
    overrides: { merchantName: '' },
    field: 'merchantName',
    sentence: ADD_MESSAGES.MERCHANT_NAME_CAN_NOT_BE_EMPTY,
    programLine: 302,
    blank: true,
  },
  {
    overrides: { merchantCity: '' },
    field: 'merchantCity',
    sentence: ADD_MESSAGES.MERCHANT_CITY_CAN_NOT_BE_EMPTY,
    programLine: 308,
    blank: true,
  },
  {
    overrides: { merchantZip: '' },
    field: 'merchantZip',
    sentence: ADD_MESSAGES.MERCHANT_ZIP_CAN_NOT_BE_EMPTY,
    programLine: 314,
    blank: true,
  },
] as const satisfies readonly RefusedTurn[];

/** The six shape refusals: two numeric domains, the amount mask, both date masks, the merchant. */
const SHAPE_REFUSALS = [
  {
    overrides: { typeCode: 'AB' },
    field: 'typeCode',
    sentence: ADD_MESSAGES.TYPE_CD_MUST_BE_NUMERIC,
    programLine: 325,
    blank: false,
  },
  {
    overrides: { categoryCode: '00A1' },
    field: 'categoryCode',
    sentence: ADD_MESSAGES.CATEGORY_CD_MUST_BE_NUMERIC,
    programLine: 331,
    blank: false,
  },
  {
    /*
     * WHY : Assumptions: this value is refused because it carries NO SIGN, not because its digits are
     *       wrong, and the strictness is the reference's. `TRNAMTI(1:1) NOT EQUAL '-' AND '+'` at
     *       `app/cbl/COTRN02C.cbl` L340 admits no third character in the first position, which is why
     *       the mapset paints the hint `(-99999999.99)` beneath the field to tell the operator so.
     */
    overrides: { amount: '1234.56' },
    field: 'amount',
    sentence: ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99,
    programLine: 345,
    blank: false,
  },
  {
    overrides: { originDate: '03/14/2024' },
    field: 'originDate',
    sentence: ADD_MESSAGES.ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD,
    programLine: 360,
    blank: false,
  },
  {
    overrides: { processDate: '2024/03/15' },
    field: 'processDate',
    sentence: ADD_MESSAGES.PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD,
    programLine: 375,
    blank: false,
  },
  {
    overrides: { merchantId: '00012345A' },
    field: 'merchantId',
    sentence: ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC,
    programLine: 432,
    blank: false,
  },
] as const satisfies readonly RefusedTurn[];

/**
 * One calendar verdict the SERVICE reaches and the screen only renders.
 *
 * ⚠️ Assumptions: the date-edit rules are NOT re-implemented here and no case below computes whether a
 * date is real. `app/cbl/COTRN02C.cbl` calls the date utility `CSUTLDTC` at L393 for the originating
 * date and L413 for the processing date, and the migrated equivalent is
 * `com.carddemo.common.validation.DateEditValidator`, server-side. The browser can only check SHAPE --
 * `hasBaselineIsoDateShape` accepts `2024-02-31` because four digits, a hyphen, two digits, a hyphen and
 * two digits is all the reference's own reference-modified branches check -- so the calendar verdict
 * arrives as a field error on the response and this file asserts that the screen SURFACES it. Deciding
 * the verdict here would test a rule that lives in another language in another process.
 */
interface SurfacedDateVerdict {
  /** Field the service attributes the refusal to. */
  readonly field: TransactionAddField;
  /** Value keyed: shaped correctly, so only the calendar can refuse it. */
  readonly keyed: string;
  /** The verbatim sentence, from the catalog. */
  readonly sentence: string;
  /** One-based line of the `MOVE` that raises it in `app/cbl/COTRN02C.cbl`. */
  readonly programLine: number;
  /** One-based line of the `CALL 'CSUTLDTC'` whose verdict raises it. */
  readonly utilityCallLine: number;
}

/** The two calendar verdicts, surfaced from the response rather than computed here. */
const SURFACED_DATE_VERDICTS = [
  {
    field: 'originDate',
    keyed: '2024-02-31',
    sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE,
    programLine: 401,
    utilityCallLine: 393,
  },
  {
    field: 'processDate',
    keyed: '2024-02-30',
    sentence: ADD_MESSAGES.PROC_DATE_NOT_A_VALID_DATE,
    programLine: 421,
    utilityCallLine: 413,
  },
] as const satisfies readonly SurfacedDateVerdict[];

/**
 * One refusal reached only through a service round trip, and which turn reaches it.
 *
 * Assumptions: the status alone does not select the sentence, so each row states the turn as well. The
 * reference raises eight sentences across four failure sites and one HTTP status stands in front of more
 * than one of them: the account-flavoured and card-flavoured pairs are chosen by which key addressed the
 * submission, which is the same discrimination the reference makes by having read either `CXACAIX` or
 * `CCXREF`, and the two transaction-browse sentences are reachable only from the copy-last turn, which is
 * the migrated form of the `STARTBR`/`READPREV` pair at `app/cbl/COTRN02C.cbl` L475-L478.
 */
interface LookupRefusal {
  /** Members of the complete capture this row replaces, which selects the addressing key. */
  readonly overrides: Partial<TransactionAddValues>;
  /** Whether the turn is the copy-last action, whose reference site is the transaction browse. */
  readonly copying: boolean;
  /** HTTP status the service answers with. */
  readonly status: number;
  /** The verbatim sentence, from the catalog. */
  readonly sentence: string;
  /** One-based line of the `MOVE` that raises it in `app/cbl/COTRN02C.cbl`. */
  readonly programLine: number;
}

/** The six refusals the two cross-reference reads and the transaction browse raise. */
const LOOKUP_REFUSALS = [
  {
    overrides: {},
    copying: false,
    status: 404,
    sentence: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
    programLine: 593,
  },
  {
    overrides: {},
    copying: false,
    status: 500,
    sentence: ADD_MESSAGES.UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE,
    programLine: 600,
  },
  {
    overrides: { accountId: '', cardNumber: CARD_NUMBER },
    copying: false,
    status: 404,
    sentence: ADD_MESSAGES.CARD_NUMBER_NOT_FOUND,
    programLine: 626,
  },
  {
    overrides: { accountId: '', cardNumber: CARD_NUMBER },
    copying: false,
    status: 500,
    sentence: ADD_MESSAGES.UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE,
    programLine: 633,
  },
  {
    overrides: {},
    copying: true,
    status: 404,
    sentence: SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND,
    programLine: 657,
  },
  {
    overrides: {},
    copying: true,
    status: 500,
    sentence: SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION,
    programLine: 664,
  },
] as const satisfies readonly LookupRefusal[];

/**
 * One sentence a withheld write produces, chosen by the confirmation character.
 *
 * Assumptions: a DECLINING answer and a BLANK one produce the SAME sentence, and the grouping is the
 * reference's: `app/cbl/COTRN02C.cbl` L173-L176 puts `'N'`, `'n'`, `SPACES` and `LOW-VALUES` in one arm
 * reached by fall-through, so all four ask for another turn. Only a character that is none of those six
 * spellings reaches the `WHEN OTHER` arm at L182 and earns the invalid-value sentence, which is why
 * declining can never be reported as an invalid value.
 */
interface ConfirmationSentence {
  /** The character keyed into `CONFIRMI`. */
  readonly keyed: string;
  /** The verbatim sentence, from the catalog. */
  readonly sentence: string;
  /** One-based line of the `MOVE` that raises it in `app/cbl/COTRN02C.cbl`. */
  readonly programLine: number;
}

/** The two sentences the confirmation character selects. */
const CONFIRMATION_SENTENCES = [
  { keyed: '', sentence: ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION, programLine: 178 },
  {
    keyed: 'Q',
    sentence: SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N,
    programLine: 184,
  },
] as const satisfies readonly ConfirmationSentence[];

/*
 * WHY : Assumptions: the two answers the confirmation modal offers are DERIVED from the mapset's own
 *       domain hint rather than retyped. `app/bms/COTRN02.bms` L288-L292 paints `(Y/N)` beside the
 *       single-character field, the screen publishes that literal, and splitting it yields the two
 *       characters the modal's controls stand for -- so this file names them without introducing a
 *       second copy of a source string, and a hint reworded in the mapset would fail here rather than
 *       leaving a stale pair of labels that still matched.
 */

/** The two answers the confirmation domain admits, in the order the mapset's hint lists them. */
const CONFIRMATION_DOMAIN = TRANSACTION_ADD_CONFIRM_DOMAIN_HINT.replace(/[()]/gu, '').split('/');

/** The answer that writes, which is the first member of the mapset's own domain hint. */
const CONFIRMING_ANSWER = CONFIRMATION_DOMAIN[0] ?? '';

/** The answer that declines, which is the second member of the same hint. */
const DECLINING_ANSWER = CONFIRMATION_DOMAIN[1] ?? '';

/**
 * One function-key descriptor: the browser key that raises it and the label painted on its control.
 *
 * Assumptions: the shape is named rather than written inline at each use, because two lists are compared
 * against each other -- what the legend PAINTS and what the screen DECLARES -- and a shared name is what
 * makes the comparison exact rather than structural coincidence.
 */
interface KeyDescriptor {
  /** The keyboard key the control advertises, such as `Enter` or `F5`. */
  readonly browserKey: string;
  /** The descriptor the row-24 literal paints for that key. */
  readonly label: string;
}

/** The four keys this screen binds, paired with the browser key each one is raised by. */
const BOUND_KEYS = [
  { aid: 'ENTER', browserKey: 'Enter', label: TRANSACTION_ADD_KEY_LABELS.ENTER },
  { aid: 'PFK03', browserKey: 'F3', label: TRANSACTION_ADD_KEY_LABELS.PFK03 },
  { aid: 'PFK04', browserKey: 'F4', label: TRANSACTION_ADD_KEY_LABELS.PFK04 },
  { aid: 'PFK05', browserKey: 'F5', label: TRANSACTION_ADD_KEY_LABELS.PFK05 },
] as const satisfies readonly { aid: CicsAid; browserKey: string; label: string }[];

/*
 * WHY : Assumptions: exactly these three keys are checked as UNBOUND, and the choice is measured rather
 *       than arbitrary. `app/cbl/COTRN02C.cbl` L133-L152 evaluates `EIBAID` with arms for `DFHENTER`,
 *       `DFHPF3`, `DFHPF4` and `DFHPF5` and a `WHEN OTHER` that refuses everything else, so the paging
 *       and cancel keys other screens bind -- PF7, PF8 and PF12 -- are precisely the ones a reader would
 *       expect to work here and which the reference refuses.
 * WHY : Assumptions: `CLEAR`, `PA1` and `PA2` are deliberately ABSENT from this list even though they are
 *       equally unbound. They are shared-hook behaviour: `ui/src/layout/usePfKeys.ts` records that no
 *       browser key raises them at all, so a case reaching for one would be testing the hook's own
 *       domain rather than this screen's dispatch. The alias fold of PF13-PF24 onto PF01-PF12 is left out
 *       for the same reason.
 */

/** Three keys other screens bind and this one refuses, each raised by its own browser key. */
const UNBOUND_KEYS = [
  { aid: 'PFK07', browserKey: 'F7' },
  { aid: 'PFK08', browserKey: 'F8' },
  { aid: 'PFK12', browserKey: 'F12' },
] as const satisfies readonly { aid: CicsAid; browserKey: string }[];

/**
 * The members a capture body may carry, which is the whole of the service contract's request shape.
 *
 * ⚠️ Assumptions: this list is asserted as an EXACT set rather than checked member by member, and that
 * is what makes it a disclosure check as well as a shape check. `ui/src/api/types.ts` declares no
 * card-verification member anywhere in the transaction contract, and the reference record's own
 * verification column is never returned by any endpoint under AAP section 0.4.1.9 -- so the property
 * worth asserting is that the screen sends NOTHING beyond these members, which a member-wise check
 * cannot express.
 *
 * Assumptions: `confirmation` is absent because the fixture's answer is blank and the request builder
 * omits the member unless the keyed character is one the contract admits. The case that writes asserts
 * its presence separately.
 */
const CAPTURE_BODY_MEMBERS = [
  'accountId',
  'amount',
  'categoryCode',
  'description',
  'merchantCity',
  'merchantId',
  'merchantName',
  'merchantZip',
  'originDate',
  'processDate',
  'source',
  'typeCode',
];

/**
 * A capture with every one of the fourteen controls carrying a value, for the clear case.
 *
 * Assumptions: both key fields are filled here even though the reference resolves by account whenever
 * the account field carries anything, because this fixture is never submitted -- the point is that all
 * fourteen controls hold something, so an incomplete clear is visible on whichever control it missed.
 */
const A_FULLY_KEYED_CAPTURE: TransactionAddValues = {
  ...A_COMPLETE_CAPTURE,
  cardNumber: CARD_NUMBER,
  confirmation: DECLINING_ANSWER,
};

/**
 * Places one value in one control, in a single change event, and does nothing when it is already there.
 *
 * ⚠️ Trade-offs: one change event per control rather than keystroke-level typing, and the choice is
 * measured rather than stylistic. Every control is React-controlled, so one keystroke re-renders a form
 * of fourteen `Form.Item`s -- filling the complete fixture is roughly ninety keystrokes, and the cases
 * below fill it many times over. The subject of those cases is the validation chain and the sentence it
 * raises, not the typing, so one event per control exercises exactly what they are about. Every case
 * whose subject IS the interaction -- all four function-key dispatches and both confirmation answers --
 * goes through the keystroke-level and pointer-level operator instead.
 *
 * Assumptions: a no-op write is SKIPPED, and the guard is a cost measure rather than a correctness one.
 * The control is controlled, so a DOM value equal to the wanted value means the state already holds it;
 * writing anyway would produce a fresh values object and re-render the whole form for no change, which
 * measured at roughly half a second each on this runner.
 * @param {TransactionAddField} field - Field to set.
 * @param {string} value - Value to place in it.
 * @returns {void} Completion is the updated control.
 */
function setField(field: TransactionAddField, value: string): void {
  if (control(field).value === value) {
    return;
  }
  fireEvent.change(control(field), { target: { value } });
}

/**
 * Places a whole capture on the screen, control by control.
 *
 * Assumptions: the order is `DECLARED_FIELDS` order, which is the symbolic map's declaration order and
 * therefore the order an operator tabbed through the fields. Iterating that one table is also what makes
 * a field added to the screen impossible to fill without also citing its width.
 * @param {TransactionAddValues} values - The values to place, field by field.
 * @returns {void} Completion is the updated controls.
 */
function fillCapture(values: TransactionAddValues): void {
  for (const declared of DECLARED_FIELDS) {
    setField(declared.field, values[declared.field]);
  }
}

/**
 * Replaces on the screen exactly the fields one table row overrides, and nothing else.
 * @param {Partial<TransactionAddValues>} overrides - The row's replacements.
 * @returns {void} Completion is the updated controls.
 */
function applyOverrides(overrides: Partial<TransactionAddValues>): void {
  for (const declared of DECLARED_FIELDS) {
    const replacement = overrides[declared.field];
    if (replacement !== undefined) {
      setField(declared.field, replacement);
    }
  }
}

/**
 * Puts the complete capture's own values back into the fields one row overrode.
 *
 * Assumptions: only the overridden fields are rewritten, so walking a table costs two change events per
 * row instead of a whole refill. The refusals a row produces are left standing rather than cleared,
 * because the next turn replaces them: the screen sets the refusal list to the single failure it found,
 * so no residue from one row can satisfy the next row's assertion -- and each row's sentence differs
 * from every other row's, so a turn that failed to run leaves the previous sentence on the band and the
 * next assertion fails on it.
 * @param {Partial<TransactionAddValues>} overrides - The row's replacements, whose fields are restored.
 * @returns {void} Completion is the updated controls.
 */
function restoreOverriddenFields(overrides: Partial<TransactionAddValues>): void {
  for (const declared of DECLARED_FIELDS) {
    if (overrides[declared.field] !== undefined) {
      setField(declared.field, A_COMPLETE_CAPTURE[declared.field]);
    }
  }
}

/**
 * Mounts the screen and puts a complete, browser-valid capture on it.
 *
 * ⚠️ Trade-offs: the tables below walk their rows from ONE mount rather than mounting per row, and the
 * reason is a measured cost with a named mechanism. Mounting this screen inside the shell is expensive,
 * and the FIRST change event after a mount is an order of magnitude dearer per control than every later
 * one, because antd generates this form's styles and remounts an `Input` subtree on first paint. A row
 * that mounts and fills afresh therefore pays both costs, while a row that reuses an already-filled
 * screen pays neither. What is given up is that a failing row ends its table's walk, so a second
 * regression in the same table stays hidden until the first is fixed; the row's own identity is folded
 * into the assertion so the failure still names the source line it came from.
 * @returns {Promise<Awaited<ReturnType<typeof renderInAppShell>>>} The render result, with the operator.
 */
async function mountWithACompleteCapture(): Promise<Awaited<ReturnType<typeof renderInAppShell>>> {
  const rendered = await mountCaptureScreen();
  fillCapture(A_COMPLETE_CAPTURE);
  return rendered;
}

/**
 * Asserts the capture operation has been dispatched exactly once.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function captureDispatchedOnce(): void {
  expect(addTransaction).toHaveBeenCalledTimes(1);
}

/**
 * Asserts the capture operation has been dispatched exactly twice, once per activation path.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function captureDispatchedTwice(): void {
  expect(addTransaction).toHaveBeenCalledTimes(2);
}

/**
 * Asserts the copy operation has been dispatched exactly once.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function copyDispatchedOnce(): void {
  expect(copyLastTransaction).toHaveBeenCalledTimes(1);
}

/**
 * Asserts the copy operation has been dispatched exactly twice, once per activation path.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function copyDispatchedTwice(): void {
  expect(copyLastTransaction).toHaveBeenCalledTimes(2);
}

/**
 * Asserts the capture screen is no longer mounted, which is what a route change looks like from here.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function captureScreenIsGone(): void {
  expect(screen.queryByLabelText(TRANSACTION_ADD_FIELD_LABELS.accountId)).toBeNull();
}

/**
 * Asserts every one of the fourteen controls is empty.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function everyControlIsEmpty(): void {
  for (const declared of DECLARED_FIELDS) {
    expect(valueOf(declared.field)).toBe('');
  }
}

/**
 * Asserts every one of the fourteen controls carries something.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function everyControlCarriesAValue(): void {
  for (const declared of DECLARED_FIELDS) {
    expect(valueOf(declared.field)).not.toBe('');
  }
}

/**
 * Asserts one row of a browser-side refusal table produced everything the reference produces.
 *
 * Assumptions: the four observations are compared as ONE object carrying the row's source line, so a
 * failure names the line of `app/cbl/COTRN02C.cbl` the row came from rather than reporting a bare
 * `true` against `false`. That is what keeps a consolidated walk diagnosable.
 *
 * Assumptions: the reference does four things for one refusal and all four are checked. It moves the
 * sentence into the message field, which reaches row 23; it moves the error colour into the field's own
 * attribute, which is `app/cpy/CSSETATY.cpy` L18-L22; it moves a literal asterisk into the field when
 * that field is BLANK, which is L23-L25 nested inside the blank test; and it re-sends the screen without
 * reading a file, so nothing leaves the browser -- which the caller asserts once for the whole table.
 *
 * ⚠️ Refactoring Rationale: the marking is asserted from the turn's own outcome, with no notion of a
 * first turn against a re-entry. The copybook gates its `MOVE DFHRED` on `CDEMO-PGM-REENTER` at L20 -- a
 * counter carried in the pseudo-conversational session struct -- and AAP section 0.7.1 removes that
 * struct entirely, so the discriminator has no target equivalent and the marking is driven purely by
 * what the turn produced.
 * @param {RefusedTurn} row - The row that was just exercised.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function assertRefusalRendered(row: RefusedTurn): void {
  const rendered = formItem(row.field).textContent ?? '';
  expect({
    programLine: row.programLine,
    marked: isMarkedRefused(row.field),
    carriesTheSentence: rendered.includes(row.sentence),
    carriesTheBlankMarker: rendered.includes(FIELD_ERROR_TOKENS.blankMarker),
  }).toEqual({
    programLine: row.programLine,
    marked: true,
    carriesTheSentence: true,
    carriesTheBlankMarker: row.blank,
  });
}

/**
 * Walks one table of refusals the browser raises without dispatching anything.
 * @param {readonly RefusedTurn[]} rows - The table to walk, in the reference's own evaluation order.
 * @returns {Promise<void>} Resolves once every row has been checked.
 */
async function walkBrowserRefusals(rows: readonly RefusedTurn[]): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  for (const row of rows) {
    applyOverrides(row.overrides);

    await pressPfKey(user, 'ENTER');

    await expectBandSentence(row.sentence);
    assertRefusalRendered(row);
    restoreOverriddenFields(row.overrides);
  }

  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Walks the two calendar verdicts, asserting each is SURFACED rather than recomputed here.
 *
 * ⚠️ Assumptions: the date-edit rules are NOT re-implemented and no assertion below decides whether a
 * date is real. `app/cbl/COTRN02C.cbl` calls the date utility `CSUTLDTC` at L393 for the originating date
 * and L413 for the processing date, and the migrated equivalent is
 * `com.carddemo.common.validation.DateEditValidator`, server-side. The browser can only check SHAPE, so
 * `2024-02-31` reaches the service and the verdict comes back as a field error.
 *
 * Assumptions: the request having been DISPATCHED is asserted first, and that is the half of this walk
 * that proves the delegation. A shaped date the browser refused would put the same sentence on the same
 * field with no round trip at all, so without it the walk would pass against a screen that had copied
 * the calendar rules into the browser -- which is exactly what must not happen, because a second copy is
 * a second place for the leap-year handling to drift.
 * @returns {Promise<void>} Resolves once both verdicts have been checked.
 */
async function walkSurfacedDateVerdicts(): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  for (const verdict of SURFACED_DATE_VERDICTS) {
    vi.mocked(addTransaction).mockClear();
    vi.mocked(addTransaction).mockRejectedValue(
      transportFailure(400, [fieldError(verdict.field, verdict.sentence)]),
    );
    setField(verdict.field, verdict.keyed);

    await pressPfKey(user, 'ENTER');

    await waitFor(captureDispatchedOnce);
    await expectBandSentence(verdict.sentence);
    expect({ field: verdict.field, marked: isMarkedRefused(verdict.field) }).toEqual({
      field: verdict.field,
      marked: true,
    });
    setField(verdict.field, A_COMPLETE_CAPTURE[verdict.field]);
  }
}

/**
 * Walks the six refusals reached only through a service round trip.
 *
 * Assumptions: each row re-arms the operation it exercises and clears both call histories first, so a
 * row's dispatch count is its own. The armed rejection is replaced rather than removed, because the
 * screen's classification reads the status off the failure it caught.
 * @returns {Promise<void>} Resolves once every row has been checked.
 */
async function walkLookupRefusals(): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  for (const row of LOOKUP_REFUSALS) {
    vi.mocked(addTransaction).mockClear();
    vi.mocked(copyLastTransaction).mockClear();
    const dispatched = row.copying ? copyLastTransaction : addTransaction;
    vi.mocked(dispatched).mockRejectedValue(transportFailure(row.status));
    applyOverrides(row.overrides);

    await pressPfKey(user, row.copying ? 'PFK05' : 'ENTER');

    await waitFor(row.copying ? copyDispatchedOnce : captureDispatchedOnce);
    await expectBandSentence(row.sentence);
    restoreOverriddenFields(row.overrides);
  }
}

/**
 * Walks the two sentences a withheld write produces, chosen by the confirmation character.
 * @returns {Promise<void>} Resolves once both sentences have been checked.
 */
async function walkConfirmationSentences(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  for (const answer of CONFIRMATION_SENTENCES) {
    setField('confirmation', answer.keyed);

    await pressPfKey(user, 'ENTER');

    await expectBandSentence(answer.sentence);
  }
}

/**
 * Asserts every control refuses more characters than its symbolic map declares.
 *
 * Assumptions: two comparisons are made and they answer different questions. The first compares the
 * screen's own width table against the widths measured here from `app/cpy-bms/COTRN02.CPY`, which is
 * what catches a transcription error in the screen; the second reads `maxlength` back off the rendered
 * control, which is what catches a control built without the table. Making only the second comparison
 * would pass against a screen whose table was wrong in the same way as the DOM it produced.
 * @returns {Promise<void>} Resolves once every field has been checked.
 */
async function holdsEveryDeclaredWidth(): Promise<void> {
  await mountCaptureScreen();

  for (const declared of DECLARED_FIELDS) {
    expect({
      symbolicName: declared.symbolicName,
      width: TRANSACTION_ADD_FIELD_WIDTHS[declared.field],
    }).toEqual({ symbolicName: declared.symbolicName, width: declared.declaredWidth });
    expectMaxLength(control(declared.field), declared.declaredWidth);
  }
}

/**
 * Records the header-band widths and both message widths the mapset and the work area declare.
 *
 * Assumptions: no screen is mounted, because nothing here is rendered by this screen. The header band is
 * DELEGATED to the one shell the application mounts, which owns its rendering and its own tests, so a
 * case reading the band's markup would assert another module's contract. What is checked instead is the
 * half the screen supplies: that the transaction identifier and the program name fit the fields they are
 * moved into -- `TRNNAMEI PIC X(4)` and `PGMNAMEI PIC X(8)` -- because a value wider than its field is
 * truncated by COBOL and is the one header defect a screen can cause.
 *
 * ⚠️ Assumptions: seventy-five and seventy-eight are BOTH recorded and neither is reconciled to the
 * other. They measure different stages of one message's journey -- the shared work area's content limit
 * at `app/cpy/CVCRD01Y.cpy` L28-L30 and the display region at `app/cpy-bms/COTRN02.CPY` L144 -- and the
 * catalog carries both for that reason. A reader who "corrects" the work-area width to the display width
 * overstates the content limit by three characters on every sentence this screen shows.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function recordsTheDeclaredBandWidths(): void {
  const transactionIdField = DECLARED_HEADER_BAND_FIELDS[0];
  const programNameField = DECLARED_HEADER_BAND_FIELDS[3];
  expect(DECLARED_HEADER_BAND_FIELDS).toHaveLength(6);
  expect(transactionIdField?.symbolicName).toBe('TRNNAMEI');
  expect(programNameField?.symbolicName).toBe('PGMNAMEI');
  expect(TRANSACTION_ADD_TRANSACTION_ID.length).toBe(transactionIdField?.declaredWidth);
  expect(TRANSACTION_ADD_PROGRAM_NAME.length).toBe(programNameField?.declaredWidth);

  expect(MESSAGE_BAND.workAreaWidth).toBe(75);
  expect(MESSAGE_BAND_BY_MAPSET[TRANSACTION_ADD_MAPSET].displayWidth).toBe(
    ERROR_MESSAGE_DISPLAY_WIDTH,
  );
  expect(MESSAGE_BAND_BY_MAPSET[TRANSACTION_ADD_MAPSET].map).toBe('COTRN2A');
  expect(MESSAGE_BAND.workAreaWidth).not.toBe(ERROR_MESSAGE_DISPLAY_WIDTH);
}

/**
 * Asserts the cursor opens in the one field the mapset gives the initial-cursor attribute.
 *
 * ⚠️ Assumptions: React expresses `autoFocus` by focusing the element after mount rather than by
 * emitting an attribute, so this reads `document.activeElement` and not a rendered `autofocus`. That
 * also makes the assertion a genuine count rather than a spot check: React focuses in mount order, so a
 * SECOND control carrying the attribute would take the focus away from this one -- the account field is
 * the first control the screen renders. The field holding focus therefore proves the attribute appears
 * once, which is what `app/bms/COTRN02.bms` declares: one `IC`, on `ACTIDIN` at L85, and on nothing else
 * among the mapset's 61 field definitions.
 * @returns {Promise<void>} Resolves once the cursor position has been checked.
 */
async function opensWithTheCursorInTheSingleInitialField(): Promise<void> {
  await mountCaptureScreen();

  expect(INITIAL_CURSOR_ATTRIBUTE_LINE).toBe(85);
  expect(document.activeElement).toBe(control('accountId'));
}

/**
 * Asserts the amount is rendered through the fixed-pitch token rather than a literal face.
 *
 * Assumptions: the assertion is that the inline style is a CSS-VARIABLE REFERENCE whose name is the
 * token's own, not that it equals some font stack. antd 6 themes through CSS variables, so a token
 * reaches the DOM as `var(--…)`; comparing against a resolved value would copy today's theme into this
 * file and would keep passing after the theme moved.
 *
 * Assumptions: money is rendered in the fixed-pitch face because the terminal's was, and the reason is
 * functional rather than decorative -- a proportional face puts the sign, the eight integer digits and
 * the two decimals in different columns from one capture to the next, so an operator scanning a column of
 * amounts loses the alignment the mask exists to provide.
 * @returns {Promise<void>} Resolves once the style has been checked.
 */
async function rendersTheAmountThroughTheFixedPitchToken(): Promise<void> {
  await mountCaptureScreen();

  const rendered = control('amount').style.fontFamily;
  expect(rendered.startsWith('var(--')).toBe(true);
  expect(rendered).toContain(cssVariableSegment(TYPOGRAPHY_TOKENS.fixedPitchData));
}

/**
 * Asserts the shared unmapped-key sentence answers every key this screen does not bind.
 *
 * Assumptions: the clear key is pressed BETWEEN the three probes, and it is load-bearing rather than
 * tidy. The sentence stays on the band until something replaces it, so without clearing, the second and
 * third probes would pass against a screen that had bound them -- they would be reading the first
 * probe's message. Clearing also exercises the reference's own `INITIALIZE-ALL-FIELDS`, which lists the
 * message field among the items it blanks.
 * @returns {Promise<void>} Resolves once every unbound key has been checked.
 */
async function refusesEveryKeyItDoesNotBind(): Promise<void> {
  const { user } = await mountCaptureScreen();

  // Assumptions: fifty is the DECLARED width of the field this sentence is moved out of, not a count of
  // its characters -- `CCDA-MSG-INVALID-KEY PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21, which
  // `app/cbl/COTRN02C.cbl` L150 moves into the message area on its `WHEN OTHER` arm. The sentence itself
  // is shorter and COBOL pads the remainder with spaces, so the width is asserted from the catalog
  // rather than measured from the string, which would report the padding-free length instead.
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);

  for (const unbound of UNBOUND_KEYS) {
    await pressPfKey(user, unbound.aid);
    await expectBandSentence(INVALID_KEY_PRESSED);

    await pressPfKey(user, 'PFK04');
    await waitFor(bandCarriesNoUnmappedKeySentence);
  }

  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Asserts the clear key has removed the unmapped-key sentence from the band.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function bandCarriesNoUnmappedKeySentence(): void {
  expect(messageBand()).not.toHaveTextContent(INVALID_KEY_PRESSED.trim());
}

/**
 * Asserts a response marks only the fields it names, and never the whole screen.
 *
 * ⚠️ Assumptions: the negative half is the point of this case. A screen that painted every control as
 * refused whenever any refusal arrived would satisfy an assertion that the named field is marked, and
 * would lose the whole of what `app/cpy/CSSETATY.cpy` expresses -- that template is applied per field,
 * with the field's own flag deciding. Asserting that two unnamed controls are CLEAN is what proves the
 * mapping is per-field rather than screen-wide.
 * @returns {Promise<void>} Resolves once the marking has been checked.
 */
async function marksOnlyTheFieldsTheResponseNames(): Promise<void> {
  const refused = ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99;
  vi.mocked(addTransaction).mockRejectedValue(
    transportFailure(400, [fieldError('amount', refused)]),
  );
  const { user } = await mountWithACompleteCapture();

  await pressPfKey(user, 'ENTER');

  await expectBandSentence(refused);
  const marked = formItem('amount').textContent ?? '';
  expect(isMarkedRefused('amount')).toBe(true);
  expect(marked).toContain(refused);
  /*
   * WHY : Assumptions: no asterisk is expected here because the refusal is not-OK rather than blank --
   *       the amount carries a value the service declined. `app/cpy/CSSETATY.cpy` nests its `MOVE '*'`
   *       inside the blank test at L23-L25, so the marker distinguishes an empty field from a rejected
   *       one and a screen showing it on both would erase that distinction.
   */
  expect(marked).not.toContain(FIELD_ERROR_TOKENS.blankMarker);
  expect(isMarkedRefused('source')).toBe(false);
  expect(isMarkedRefused('originDate')).toBe(false);
}

/**
 * Asserts money stays exact fixed point from the control to the wire and back.
 *
 * ⚠️ Trade-offs: the type is asserted as well as the value, and both are needed. A value assertion alone
 * would pass against a body carrying the NUMBER -1234.56, and a number is exactly what must never appear
 * here: a JSON number is parsed into an IEEE-754 double by essentially every client, and a double cannot
 * represent every two-decimal value, so routing money through one silently changes cents. That is why
 * `NUMERIC(12,2)` in PostgreSQL, `BigDecimal` at scale 2 with `HALF_UP` in Java and a JSON string on the
 * wire are one decision rather than three. Nothing in this case coerces the value.
 *
 * Assumptions: the body's member set is compared EXACTLY, which makes this a disclosure check as well:
 * the screen sends the members the contract declares and nothing else.
 *
 * Assumptions: the displayed value is re-read AFTER the answer lands, because the screen re-renders the
 * amount through its own mask from the service's monetary form -- so this asserts the round trip
 * preserves the leading minus rather than merely that the control accepted it. A screen that dropped the
 * sign would turn a credit into a debit of the same magnitude, which no field-level rule would report.
 * @returns {Promise<void>} Resolves once the dispatched body and the displayed value are checked.
 */
async function keepsMoneyInExactFixedPoint(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();
  expect(typeof valueOf('amount')).toBe('string');
  expect(valueOf('amount')).toBe(KEYED_NEGATIVE_AMOUNT);

  await pressPfKey(user, 'ENTER');

  await waitFor(captureDispatchedOnce);
  const body = vi.mocked(addTransaction).mock.calls[0]?.[0];
  expect(body).toBeDefined();
  expect(typeof body?.amount).toBe('string');
  expect(body?.amount).toBe(WIRE_NEGATIVE_AMOUNT);
  expect(Object.keys(body ?? {}).sort()).toEqual(CAPTURE_BODY_MEMBERS);

  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(typeof valueOf('amount')).toBe('string');
  expect(valueOf('amount')).toBe(KEYED_NEGATIVE_AMOUNT);
}

/**
 * Asserts the legend paints exactly the four descriptors the row-24 literal declares.
 *
 * ⚠️ Assumptions: the labels are checked by REJOINING them to the literal's declared length rather than
 * by comparing them with a retyped copy of it. `app/bms/COTRN02.bms` L297-L302 holds one 53-character
 * literal that the BMS continuation splits mid-word, and the screen splits it into four labels so each
 * can sit on the control that performs it. Rejoining with the two spaces the source uses and checking the
 * total is what proves the split is lossless, without putting a second copy of a source string here.
 * @returns {Promise<void>} Resolves once the legend has been checked.
 */
async function paintsTheFourDeclaredKeyDescriptors(): Promise<void> {
  await mountCaptureScreen();

  /**
   * Reads one rendered legend control as the key it advertises and the label it paints.
   * @param {HTMLButtonElement} button - The rendered control.
   * @returns {KeyDescriptor} The pair.
   */
  function readPainted(button: HTMLButtonElement): KeyDescriptor {
    return {
      browserKey: button.getAttribute('aria-keyshortcuts') ?? '',
      label: button.textContent ?? '',
    };
  }

  /**
   * Reads one declared binding as the same pair, so the two lists compare directly.
   * @param {KeyDescriptor} bound - The declared binding.
   * @returns {KeyDescriptor} The pair, with the binding's attention identifier dropped.
   */
  function readDeclared(bound: KeyDescriptor): KeyDescriptor {
    return { browserKey: bound.browserKey, label: bound.label };
  }

  /**
   * Reports one binding's label, for rejoining the row-24 literal.
   * @param {KeyDescriptor} bound - The declared binding.
   * @returns {string} Its label.
   */
  function labelOf(bound: KeyDescriptor): string {
    return bound.label;
  }

  expect(legendControls().map(readPainted)).toEqual(BOUND_KEYS.map(readDeclared));
  expect(BOUND_KEYS.map(labelOf).join(LEGEND_SEGMENT_SEPARATOR)).toHaveLength(
    LEGEND_DECLARED_LENGTH,
  );
}

/**
 * Asserts each legend control carries the emphasis the design-system mapping assigns it.
 *
 * Assumptions: the emphasis is read as the variant CLASS antd emits, which is the only DOM-observable
 * form it takes -- jsdom has no layout engine, so no computed colour or weight is available to compare. A
 * class name is not a design value, so reading one puts no literal colour or spacing in this file.
 *
 * Assumptions: the expected set is `PRIMARY_ACTION_AIDS` rather than a list written here, so the pairing
 * is asserted against the one module that owns it. AAP section 0.3.2 maps the action keys to
 * `type="primary"` for Enter and PF5 and `default` for the rest.
 * @returns {Promise<void>} Resolves once every control has been checked.
 */
async function rendersTheDeclaredKeyEmphasis(): Promise<void> {
  await mountCaptureScreen();

  for (const bound of BOUND_KEYS) {
    const rendered = legendControl(bound.browserKey);
    const expectsPrimary = PRIMARY_ACTION_AIDS.includes(bound.aid);
    expect({
      aid: bound.aid,
      primary: rendered.classList.contains('ant-btn-primary'),
      standard: rendered.classList.contains('ant-btn-default'),
    }).toEqual({ aid: bound.aid, primary: expectsPrimary, standard: !expectsPrimary });
  }
}

/*
 * WHY : ⚠️ Alternatives Considered: asserting only the legend click, which is the shorter case and is
 *       what a pointer-driven suite would write. Rejected because the contract being migrated is a
 *       KEYBOARD contract -- the 3270 had no pointer, and every one of the four descriptors on row 24
 *       names a physical key -- so a case that only clicks passes in full while every keyboard binding in
 *       the application is broken. Asserting only the key press has the mirror flaw: the legend is the
 *       only way a browser operator reaching for a mouse can invoke the same action, and a control wired
 *       to nothing would go unnoticed. Both paths are therefore exercised in each of the four cases
 *       below, and they must agree because `PfKeyBar` routes its clicks through the very `invoke` a key
 *       press reaches.
 * WHY : Trade-offs: three of the four cases exercise both paths from ONE mount, counting the dispatches
 *       cumulatively, for the cost reason recorded on `mountWithACompleteCapture`. The back key cannot:
 *       its action unmounts the screen, so its second path needs a second mount, and that case is cheap
 *       because it needs no capture keyed at all.
 */

/**
 * Asserts Enter submits the capture, from the keyboard and from the legend control.
 * @returns {Promise<void>} Resolves once both paths have been checked.
 */
async function submitsTheCaptureFromBothPaths(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  await pressPfKey(user, 'ENTER');
  await waitFor(captureDispatchedOnce);

  await user.click(legendControl('Enter'));
  await waitFor(captureDispatchedTwice);
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Asserts the back key leaves the capture screen and writes nothing on the way out.
 *
 * Assumptions: the screen being GONE is what a route change looks like from here. The reference moves
 * `'COMEN01C'` into the target program when no caller was recorded and returns to the caller otherwise
 * (`app/cbl/COTRN02C.cbl` L137-L142), and the migrated form is a navigation -- so the observable is that
 * the subject's own controls are no longer mounted.
 * @returns {Promise<void>} Resolves once both paths have been checked.
 */
async function leavesTheScreenFromBothPaths(): Promise<void> {
  const byKeyboard = await mountCaptureScreen();
  await pressPfKey(byKeyboard.user, 'PFK03');
  await waitFor(captureScreenIsGone);
  byKeyboard.unmount();

  const byLegend = await mountCaptureScreen();
  await byLegend.user.click(legendControl('F3'));
  await waitFor(captureScreenIsGone);

  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
}

/**
 * Asserts the clear key empties all fourteen controls, from the keyboard and from the legend control.
 *
 * Assumptions: the pre-condition is asserted before each pass as well as the post-condition after it,
 * because a case that only checked emptiness afterwards would pass against a screen that never accepted
 * the values at all.
 * @returns {Promise<void>} Resolves once both paths have been checked.
 */
async function clearsEveryControlFromBothPaths(): Promise<void> {
  const { user } = await mountCaptureScreen();

  fillCapture(A_FULLY_KEYED_CAPTURE);
  everyControlCarriesAValue();
  await pressPfKey(user, 'PFK04');
  await waitFor(everyControlIsEmpty);

  fillCapture(A_FULLY_KEYED_CAPTURE);
  everyControlCarriesAValue();
  await user.click(legendControl('F4'));
  await waitFor(everyControlIsEmpty);

  expect(addTransaction).not.toHaveBeenCalled();
}

/**
 * Asserts the copy key recalls the last stored transaction rather than saving the screen.
 *
 * ⚠️ Assumptions: PF5 on THIS mapset means copy and not save, and the screen's own two sources settle it
 * against the convention measured across the other screens. The row-24 legend at `app/bms/COTRN02.bms`
 * L297-L302 paints `F5=Copy Last Tran.`, and the dispatch at `app/cbl/COTRN02C.cbl` L146-L147 performs
 * `COPY-LAST-TRAN-DATA` for `DFHPF5`. AAP section 0.3.3 records a uniform PF5-saves reading taken across
 * the online programs, and a screen's own legend and its own dispatch outrank a convention derived from
 * other screens -- binding a save here would write a transaction on the key an operator was told copies
 * one. That is why `PfKeyBar` takes a per-screen descriptor array rather than deciding the labels itself.
 *
 * Assumptions: only the key field is keyed, because the copy paragraph validates the KEY fields alone at
 * L473 and then fills the eleven data fields at L481-L492. A body carrying more than the key would
 * describe a request the service does not declare, so the body is compared for exact equality.
 * @returns {Promise<void>} Resolves once both paths have been checked.
 */
async function recallsTheLastTransactionFromBothPaths(): Promise<void> {
  vi.mocked(copyLastTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountCaptureScreen();
  setField('accountId', ACCOUNT_ID);

  await pressPfKey(user, 'PFK05');
  await waitFor(copyDispatchedOnce);
  expect(vi.mocked(copyLastTransaction).mock.calls[0]?.[0]).toEqual({ accountId: ACCOUNT_ID });

  await user.click(legendControl('F5'));
  await waitFor(copyDispatchedTwice);
  expect(addTransaction).not.toHaveBeenCalled();
}

/**
 * Asserts the confirmation surface is raised before anything is written, and gates the write.
 *
 * ⚠️ Refactoring Rationale: the modal replaces the 3270's re-key-to-confirm convention, which exists in
 * the reference only because a terminal had no modal to raise -- a whole screen turn plus a keyed
 * character was the cheapest confirmation it could express. What must survive the replacement is the
 * GATE: the reference writes nothing until `CONFIRMI` carries a confirming answer, so the surface that
 * asks the question must not itself commit anything.
 *
 * ⚠️ Assumptions: a DECLINED confirmation still reaches the service, and asserting otherwise would
 * assert against the reference. `app/cbl/COTRN02C.cbl` L173-L176 answers a declining or blank character
 * by re-sending the screen with `Confirm to add this transaction...`, and the migrated turn is the one
 * that resolves the key pair and re-renders the amount, so the request is the mechanism by which the
 * operator is asked again. What the gate guarantees is narrower and is what this case asserts: no
 * dispatch carries a confirming answer until one is given, so nothing is written.
 *
 * Assumptions: the two answer controls are located by the characters the mapset's own `(Y/N)` hint names,
 * derived from the constant the screen publishes rather than retyped.
 *
 * Assumptions: the armed answer is replaced before the confirming half, because a confirmed turn receives
 * the WRITTEN outcome -- the contract makes 201 the written status and 200 the withheld one.
 * @returns {Promise<void>} Resolves once the gate has been checked in both directions.
 */
async function gatesTheWriteOnAConfirmingAnswer(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  await user.click(confirmationTrigger());

  expect(await screen.findByRole('button', { name: CONFIRMING_ANSWER })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: DECLINING_ANSWER })).toBeInTheDocument();
  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();

  await user.click(screen.getByRole('button', { name: DECLINING_ANSWER }));

  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  for (const call of vi.mocked(addTransaction).mock.calls) {
    expect(call[0]?.confirmation).not.toBe(CONFIRMING_ANSWER);
  }

  vi.mocked(addTransaction).mockClear();
  vi.mocked(addTransaction).mockResolvedValue(createdOutcome(WIRE_NEGATIVE_AMOUNT));

  await user.click(confirmationTrigger());
  await user.click(await screen.findByRole('button', { name: CONFIRMING_ANSWER }));

  await waitFor(captureDispatchedOnce);
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]?.confirmation).toBe(CONFIRMING_ANSWER);
}

/**
 * Asserts the capture route is declared authenticated rather than administrative.
 *
 * ⚠️ Assumptions: this negative is asserted BECAUSE the baseline carries a live trap.
 * `app/cpy/COMEN02Y.cpy` L68-L70 holds the option's name twice: L69 reads
 * `'Transaction Add (Admin Only)       '` and is a COBOL COMMENT, with the asterisk in column 7, while
 * L70 reads `'Transaction Add                    '` and is the value the compiler took. The option's
 * user-type field at L72 is `'U'`, and so is every one of the eleven main-menu options' -- not one is
 * `'A'`. A reader who saw only L69 would move this route into the administrative subtree and lock out
 * exactly the operators the option exists for, on the authority of a line no compiler ever read.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function declaresTheCaptureRouteAsAuthenticated(): void {
  /**
   * Reports whether one row of the reachability graph is the capture route's.
   * @param {RouteTableEntry} entry - One row of the route table.
   * @returns {boolean} `true` for the capture route.
   */
  function isCaptureRoute(entry: RouteTableEntry): boolean {
    return entry.path === TRANSACTION_ADD_PATH;
  }

  const entry = ROUTE_TABLE.find(isCaptureRoute);
  expect(entry).toBeDefined();
  expect(entry?.access).toBe('authenticated');
  expect(entry?.access).not.toBe('administrative');
  expect(entry?.program).toBe(TRANSACTION_ADD_PROGRAM_NAME);
}

/**
 * Asserts an ordinary operator can reach the screen and capture a transaction on it.
 *
 * ⚠️ Refactoring Rationale: the identifiers the operator keyed travel in the REQUEST, and the session
 * carries none of them. The reference held them in the communication area -- `CDEMO-ACCT-ID PIC 9(11)`
 * and `CDEMO-CARD-NUM PIC 9(16)` at `app/cpy/COCOM01Y.cpy` L38 and L41 -- which is storage the terminal
 * echoed back between turns, so the selection arrived as something the client had been trusted to keep. A
 * request parameter can be authorized on the turn that uses it, which is why the negative half of this
 * case matters as much as the positive one.
 *
 * Assumptions: the session is established through the identity helper, which mints a token carrying the
 * groups and drives the real sign-on exchange. There is no provider to mount and `useAuth` publishes no
 * setter for the groups, deliberately -- authority derives from the signed `cognito:groups` claim -- so a
 * case cannot grant itself administrative rights, and this one does not need to.
 * @returns {Promise<void>} Resolves once the capture has been checked.
 */
async function letsANonAdministrativeSessionCapture(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });
  expect(session.result.current.signedOn).toBe(true);
  expect(session.result.current.isAdmin).toBe(false);

  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  await pressPfKey(user, 'ENTER');

  await waitFor(captureDispatchedOnce);
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]?.accountId).toBe(ACCOUNT_ID);
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(session.result.current).not.toHaveProperty('accountId');
  expect(session.result.current).not.toHaveProperty('cardNumber');
}

/**
 * Asserts the confirmation read-back names the resolved card in its reduced form only.
 *
 * ⚠️ Assumptions: the reduction is the SERVICE'S and is adopted verbatim; none happens in the browser.
 * AAP section 0.4.1.9 reduces a primary account number everywhere except the administrative card-detail
 * endpoint, and a capture screen is not that endpoint, so the sixteen digits never arrive here at all --
 * a stronger property than a screen that received them and hid them, and what the second assertion checks
 * by looking for them anywhere in the document.
 *
 * Assumptions: the card control's own value is not part of the document's text -- an input's value is a
 * property and not a text node -- so this assertion is about what the screen PAINTS, and the operator's
 * own keystrokes remain visible to them in the field they typed into. Reducing that would hide their own
 * entry and make a correction impossible.
 * @returns {Promise<void>} Resolves once the read-back has been checked.
 */
async function namesTheResolvedCardInReducedFormOnly(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountCaptureScreen();
  fillCapture({ ...A_COMPLETE_CAPTURE, accountId: '', cardNumber: CARD_NUMBER });

  await pressPfKey(user, 'ENTER');
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  await user.click(confirmationTrigger());
  await screen.findByRole('button', { name: CONFIRMING_ANSWER });

  expect(document.body).toHaveTextContent(RESOLVED_CARD_MASKED);
  expect(document.body.textContent ?? '').not.toContain(CARD_NUMBER);
}

/**
 * Registers the cases that read the mapset's declared constraints back off the rendered screen.
 * @returns {void} Nothing; the registration is the effect.
 */
function declaredConstraintCases(): void {
  it('holds every control to the width its symbolic map declares', holdsEveryDeclaredWidth);
  it(
    'records the header-band and message widths the mapset declares',
    recordsTheDeclaredBandWidths,
  );
  it(
    'opens with the cursor in the single initial-cursor field',
    opensWithTheCursorInTheSingleInitialField,
  );
  it('renders the amount through the fixed-pitch token', rendersTheAmountThroughTheFixedPitchToken);
}

/**
 * Registers the sentence census: every refusal this program raises, asserted through the catalog.
 * @returns {void} Nothing; the registration is the effect.
 */
function refusalCensusCases(): void {
  /**
   * Walks the three key-field refusals.
   * @returns {Promise<void>} Resolves once the table has been walked.
   */
  async function walkKeyFieldRefusals(): Promise<void> {
    await walkBrowserRefusals(KEY_FIELD_REFUSALS);
  }

  /**
   * Walks the eleven emptiness refusals.
   * @returns {Promise<void>} Resolves once the table has been walked.
   */
  async function walkBlankFieldRefusals(): Promise<void> {
    await walkBrowserRefusals(BLANK_FIELD_REFUSALS);
  }

  /**
   * Walks the six shape refusals.
   * @returns {Promise<void>} Resolves once the table has been walked.
   */
  async function walkShapeRefusals(): Promise<void> {
    await walkBrowserRefusals(SHAPE_REFUSALS);
  }

  it('refuses all three key-field turns, lines 199, 213 and 226', walkKeyFieldRefusals);
  it('refuses all eleven blank fields, lines 254 through 314', walkBlankFieldRefusals);
  it('refuses all six misshapen fields, lines 325 through 432', walkShapeRefusals);
  it('surfaces both calendar verdicts, lines 401 and 421', walkSurfacedDateVerdicts);
  it('reports all six lookup refusals, lines 593 through 664', walkLookupRefusals);
  it('reports both confirmation sentences, lines 178 and 184', walkConfirmationSentences);
  it(
    'reports the shared unmapped-key sentence for every key it refuses',
    refusesEveryKeyItDoesNotBind,
  );
  it('marks only the fields the response names', marksOnlyTheFieldsTheResponseNames);
}

/**
 * Registers the case that keeps money in exact fixed point.
 * @returns {void} Nothing; the registration is the effect.
 */
function moneyCases(): void {
  it('keeps the amount an exact fixed-point string end to end', keepsMoneyInExactFixedPoint);
}

/**
 * Registers the function-key cases, each driven from the keyboard and from the legend control.
 * @returns {void} Nothing; the registration is the effect.
 */
function functionKeyCases(): void {
  it(
    'paints the four descriptors the row-24 literal declares',
    paintsTheFourDeclaredKeyDescriptors,
  );
  it('renders the declared emphasis on each key', rendersTheDeclaredKeyEmphasis);
  it('submits the capture on Enter, by key and by legend control', submitsTheCaptureFromBothPaths);
  it(
    'leaves the screen on the back key, by key and by legend control',
    leavesTheScreenFromBothPaths,
  );
  it(
    'clears all fourteen controls on the clear key, by key and by legend',
    clearsEveryControlFromBothPaths,
  );
  it(
    'recalls the last transaction on the copy key, by key and by legend',
    recallsTheLastTransactionFromBothPaths,
  );
}

/**
 * Registers the case that holds the write behind the confirmation surface.
 * @returns {void} Nothing; the registration is the effect.
 */
function confirmationGateCases(): void {
  it(
    'raises the confirmation and writes only on a confirming answer',
    gatesTheWriteOnAConfirmingAnswer,
  );
}

/**
 * Registers the cases that assert the route is reachable by an ordinary operator.
 * @returns {void} Nothing; the registration is the effect.
 */
function ordinaryOperatorAccessCases(): void {
  it('declares the capture route as authenticated', declaresTheCaptureRouteAsAuthenticated);
  it(
    'lets a non-administrative session capture a transaction',
    letsANonAdministrativeSessionCapture,
  );
  it('names the resolved card in its reduced form only', namesTheResolvedCardInReducedFormOnly);
}

describe('the transaction capture screen holds its declared constraints', declaredConstraintCases);
describe('the transaction capture screen reports the reference refusals', refusalCensusCases);
describe('the transaction capture screen keeps money in fixed point', moneyCases);
describe('the transaction capture screen binds the four keys its legend paints', functionKeyCases);
describe(
  'the transaction capture screen gates its write on the confirmation',
  confirmationGateCases,
);
describe(
  'the transaction capture route is reachable by an ordinary operator',
  ordinaryOperatorAccessCases,
);
