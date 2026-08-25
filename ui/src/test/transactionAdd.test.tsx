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
// Assumptions: the acknowledgement is composed by the catalog's own TEMPLATE rather than written out
// here, because its two literals meet with a space on each side -- `'Transaction added successfully. '`
// and `' Your Tran ID is '` at `app/cbl/COTRN02C.cbl` L728-L732 -- so the rendered sentence carries TWO
// spaces the eye does not see. A literal typed into this file would silently normalise one away and the
// case would fail against a screen that was right.
// Assumptions: the busy region is located by the identifier its own helper publishes, so this case
//   cannot drift from the one place deciding the region's shape.
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import {
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MESSAGE_BAND,
  MESSAGE_BAND_BY_MAPSET,
  MESSAGE_TEMPLATES,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  formatMessageTemplate,
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
import { FIELD_ERROR_TOKENS, TARGET_SIZE_AA_MINIMUM, TYPOGRAPHY_TOKENS } from '../theme/tokens';
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
 * ⚠️ Assumptions: the confirmation is blank, so an unmodified fixture submits NOTHING at all -- a blank
 * answer raises the screen's own asking surface and issues no request, which is
 * `app/cbl/COTRN02C.cbl` L176-L181 answering `SPACES` and `LOW-VALUES` by re-displaying the screen.
 * The note this replaces claimed a blank fixture "submits the reference's PREVIEW turn", which was true
 * of the measured shape and is the defect that was removed. Every case that writes supplies the
 * affirmative explicitly, which keeps a write visible in the case that performs it.
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
 * Builds the 200 withheld-write answer: the normalised amount and the resolved pair.
 *
 * ⚠️ Assumptions: the turn that legitimately receives this is the COPY, not a blank-confirmation
 * submit. A blank answer no longer reaches the wire at all, so where this outcome is armed on
 * `addTransaction` it is a deliberately harmless answer for a call the case asserts never happens --
 * arming it means a regression that DID call the operation fails on the assertion that names the
 * defect rather than on an unhandled rejection somewhere else.
 *
 * Assumptions: `copied` is `null` because an ordinary capture copies nothing. The copy-last action is
 * the only turn whose answer carries the eleven recalled values, and it has its own cases; a default
 * carrying them would make every withheld answer here look like a copy.
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
 * ⚠️ Assumptions: `aggregate` defaults to `null`, which is the document's own default and a real value
 * rather than an absent one -- a refused turn may carry no summary line at all. It is supplied only by
 * the rows whose subject is a sentence the SERVICE selects: `transaction-api.yaml` documents the 500 on
 * the capture operation as carrying whichever of four verbatim sentences names the step that failed, and
 * the screen cannot know which step that was, so a fixture asserting one of them has to put it in the
 * document the way the service does.
 * @param {number} status - HTTP status the service answered with.
 * @param {readonly FieldError[]} fieldErrors - Per-field refusals the problem document attributes, in
 *   the order the service marked them, which is the order the screen marks its controls in.
 * @param {string | null} [aggregate] - The document's summary line, or `null` when it carries none.
 * @returns {ApiRequestError} The failure a rejected promise carries.
 */
function transportFailure(
  status: number,
  fieldErrors: readonly FieldError[] = [],
  aggregate: string | null = null,
): ApiRequestError {
  const problem: ApiError = apiError({ status, fieldErrors, message: aggregate });
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
 * Reads the antd alert variant the row-23 band currently renders, or `null` when it renders none.
 *
 * ⚠️ Purpose: an acknowledgement painted in the REFUSAL variant is a finding of its own, and it was
 * measured on this screen: the band reported a successful capture in red while the user screens
 * reported theirs in green. The sentence alone cannot see that, so the variant is read separately.
 *
 * Assumptions: the variant is read from the CLASS antd emits rather than from a prop, because a prop
 * assertion passes for a band that resolves the prop and then renders something else.
 *
 * Assumptions: the alert is looked for INSIDE the band element, because `ui/src/layout/MessageBand.tsx`
 * renders it as a child of the element carrying the band identifier -- a document-wide query would also
 * match an alert a screen had composed for itself, which is a different defect.
 * @returns {string | null} The variant suffix antd emitted, or `null` when the band holds no alert.
 */
function bandAlertVariant(): string | null {
  for (const variant of ['error', 'success', 'info']) {
    if (messageBand().querySelector(`.ant-alert-${variant}`) !== null) {
      return variant;
    }
  }
  return null;
}

/**
 * Waits until the message band's own text is EXACTLY one sentence, space for space.
 *
 * ⚠️ Purpose: {@link expectBandSentence} cannot see a doubled space and never could.
 * `toHaveTextContent` collapses runs of whitespace in the CANDIDATE before comparing, so a sentence
 * carrying two spaces matches a band painting one -- and the acknowledgement this screen composes
 * carries exactly that. `MESSAGE_TEMPLATES.TRANSACTION_ADDED_SUCCESSFULLY` joins a first literal ENDING
 * in a space to a second BEGINNING with one, transcribed from the `STRING` statement at
 * `app/cbl/COTRN02C.cbl` L728-L732, and the sentence reads as correct English without the second one --
 * so a whitespace-tidying edit anywhere between the catalog and the band would be invisible to a reader
 * and to any normalising matcher. Rule T8 makes the doubled space a contract, so it needs an assertion
 * that compares raw `textContent`.
 *
 * Assumptions: the comparison is against the TRIMMED sentence, because several catalogued strings are
 * padded to a declared `PIC X` width and the DOM drops trailing whitespace on display. Trimming removes
 * only the padding at the ends; it cannot remove an interior double space, which is the whole point.
 * @param {string} sentence - The composed sentence, taken from the catalog and never retyped.
 * @returns {Promise<void>} Resolves once the band reads exactly that.
 */
async function expectBandToRead(sentence: string): Promise<void> {
  await waitFor(
    /**
     * Asserts the band's exact text once it has settled.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(messageBand().textContent).toBe(sentence.trim());
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
  const buttons = within(captureForm()).getAllByRole('button');
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
 * Locates the capture form itself, which bounds every structural query this file makes.
 *
 * ⚠️ Assumptions: the form is the boundary rather than the document, because the screen is mounted
 * inside the real application shell and the shell renders grid columns of its own -- the title band's
 * are `ant-col-24 ant-col-md-6`. A census taken across the document would therefore report the shell's
 * layout as this screen's, which is how the first draft of {@link laysTheFieldGridOutResponsively}
 * failed: it read a shell column and judged the form by it.
 * @returns {HTMLElement} The `<form>` element the screen renders.
 * @throws {Error} If no form is rendered, which means the screen no longer composes one.
 */
function captureForm(): HTMLElement {
  const form = document.querySelector('form');
  if (!(form instanceof HTMLElement)) {
    throw new Error('the capture form is not rendered');
  }
  return form;
}

/**
 * Reports the grid column one field's control sits in, which carries the responsive span classes.
 *
 * ⚠️ Assumptions: the walk deliberately steps PAST the nearest column. antd's `Form.Item` renders its
 * label and its control inside `Col` elements of its own -- `node_modules/antd/es/form/FormItemLabel.js`
 * L90 and `FormItemInput.js` L121 -- so `control(field).closest('.ant-col')` returns the item's own
 * control column, which carries no span class at all and would make every span assertion vacuous. The
 * screen's grid column is the first `.ant-col` ABOVE the whole form item, which is what this returns.
 * @param {TransactionAddField} field - Field whose grid column is wanted.
 * @returns {HTMLElement} The `Col` the screen's own grid places that field in.
 * @throws {Error} If the field is not inside a grid column, which means the screen stopped laying its
 *   fields out through `Row`/`Col` and its arity can no longer collapse at a narrow width.
 */
function gridColumnOf(field: TransactionAddField): HTMLElement {
  const column = formItem(field).parentElement?.closest('.ant-col');
  if (!(column instanceof HTMLElement)) {
    throw new Error(`the control for ${field} is not inside a grid column`);
  }
  return column;
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
     * WHY : ⚠️ Assumptions: this value is refused for its THIRD decimal place, and it used to be
     *       `1234.56` -- refused for carrying no sign -- which is the change this row records. The
     *       reference does refuse an unsigned amount: `TRNAMTI(1:1) NOT EQUAL '-' AND '+'` at
     *       `app/cbl/COTRN02C.cbl` L340 admits no third character in the first position. But the screen
     *       now supplies the sign, because browser validation established that an operator had no
     *       reachable spelling of a positive amount and the flow AAP section 0.9.4 lists as an acceptance
     *       criterion could not be run at all. `canonicaliseKeyedAmount` carries the divergence and its
     *       reasoning; `acceptsEveryKeyedAmountSpelling` walks what is now accepted.
     * WHY : Assumptions: three decimal places is the right substitute because no normalisation can rescue
     *       it. The field is `PIC +99999999.99` -- two decimal positions -- so a third is not a
     *       presentation difference but a value the field cannot hold, and the reference refuses it at
     *       L343 by testing `TRNAMTI(10:2)`.
     */
    overrides: { amount: '1234.567' },
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
    keyed: '1500-01-01',
    sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE,
    programLine: 401,
    utilityCallLine: 393,
  },
  {
    field: 'processDate',
    keyed: '1582-10-05',
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
  /**
   * The summary line the service's own problem document carries, where the sentence is one only the
   * service can choose.
   *
   * ⚠️ Assumptions: the two cross-reference sentences are supplied HERE rather than reconstructed by
   * the screen, and that is a correction to what this table used to assert. They were once selected
   * locally from which key field was populated, on a turn that was neither writing nor copying -- the
   * unconfirmed preview, which no longer exists. Only the service knows whether a 500 came from the
   * account read at `app/cbl/COTRN02C.cbl` L600, the card read at L633 or the write at L745, and
   * `transaction-api.yaml` states that it says so in the document's `message`.
   */
  readonly aggregate: string | null;
}

/** The six refusals the two cross-reference reads and the transaction browse raise. */
const LOOKUP_REFUSALS = [
  {
    overrides: {},
    copying: false,
    status: 404,
    sentence: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
    programLine: 593,
    aggregate: null,
  },
  {
    overrides: {},
    copying: false,
    status: 500,
    sentence: ADD_MESSAGES.UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE,
    programLine: 600,
    aggregate: ADD_MESSAGES.UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE,
  },
  {
    overrides: { accountId: '', cardNumber: CARD_NUMBER },
    copying: false,
    status: 404,
    sentence: ADD_MESSAGES.CARD_NUMBER_NOT_FOUND,
    programLine: 626,
    aggregate: null,
  },
  {
    overrides: { accountId: '', cardNumber: CARD_NUMBER },
    copying: false,
    status: 500,
    sentence: ADD_MESSAGES.UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE,
    programLine: 633,
    aggregate: ADD_MESSAGES.UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE,
  },
  {
    overrides: {},
    copying: true,
    status: 404,
    sentence: SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND,
    programLine: 657,
    aggregate: null,
  },
  {
    overrides: {},
    copying: true,
    status: 500,
    sentence: SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION,
    programLine: 664,
    aggregate: null,
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
 * ⚠️ Refactoring Rationale: this set now names the CONFIRMED body, and it used to name an unconfirmed
 * one. The note it replaces recorded `confirmation` as absent "because the fixture's answer is blank and
 * the request builder omits the member unless the keyed character is one the contract admits" -- which
 * described the only body the screen used to send on Enter, and is the body browser validation caught on
 * the write endpoint with no confirmation surface ever having been visible. A capture now reaches the
 * wire only for an affirmative answer, so `confirmation` is always present on it.
 *
 * ⚠️ Assumptions: `confirmationToken` is present too, because the case using this set reaches its
 * affirmative through a preview -- the copy turn, which is the one turn that legitimately answers 200 --
 * and the screen's request builder attaches the token whenever the screen holds one. A capture confirmed
 * without a preview behind it carries eleven members rather than twelve, which is the shape
 * `stillValidatesAndSendsTheFullCaptureBody` in
 * `ui/src/screens/transactionAdd/transactionAddTurns.test.tsx` asserts.
 */
const CONFIRMED_CAPTURE_BODY_MEMBERS = [
  'accountId',
  'amount',
  'categoryCode',
  'confirmation',
  'confirmationToken',
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
 * Keys the affirmative into the confirmation control, so the next Enter dispatches in ONE turn.
 *
 * ⚠️ Purpose: the screen sends a turn ONLY for a confirming answer. A blank confirmation raises the
 * asking surface locally and issues nothing, and an answer that is neither confirming nor declining is
 * refused locally -- which is `app/cbl/COTRN02C.cbl` L176-L187, where both arms re-display the screen
 * without touching a file. So a case whose subject is what reaches the service has to give an answer
 * first, and the cheapest faithful way to give one is to key it: the reference reaches
 * `ADD-TRANSACTION` at L189-L191 from a keyed `'Y'` exactly as it does from any other route to that arm.
 *
 * ⚠️ Trade-offs: keying the answer rather than walking the surface. The surface route costs an overlay
 * open, an animation and a pointer click per dispatch, and the cases that use this helper walk tables of
 * up to six rows with two dispatches each. Their subject is the request body or the response handling,
 * not the route the answer arrived by -- which is why the route itself is asserted separately, and
 * exhaustively, by {@link submitsTheCaptureFromBothPaths}, {@link walkConfirmationSentences},
 * {@link routesEverySubmitPathThroughTheOneGate} and
 * {@link declinesFromEveryRouteWithoutARequest}.
 * @returns {void} Completion is the updated control.
 */
function keyTheAffirmative(): void {
  setField('confirmation', CONFIRMING_ANSWER);
}

/**
 * Presses Enter on a blank confirmation and answers the surface that raises, affirmatively.
 *
 * ⚠️ Purpose: this is the whole gate, walked end to end -- the route an operator who never keys into the
 * confirmation field takes. It asserts the middle of it as well as the ends: after Enter the surface
 * stands and NOTHING has been dispatched, which is the property browser validation found missing when
 * the footer legend's Enter put a `confirmation`-less body on `POST /api/v1/transactions` with no
 * surface having ever been visible.
 * @param {Awaited<ReturnType<typeof renderInAppShell>>['user']} user - The operator driving the screen.
 * @returns {Promise<void>} Resolves once the affirmative has been given.
 */
async function confirmThroughTheAsk(
  user: Awaited<ReturnType<typeof renderInAppShell>>['user'],
): Promise<void> {
  await pressPfKey(user, 'ENTER');
  await waitFor(confirmationIsRaised);
  expect(addTransaction).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: CONFIRMING_ANSWER }));
  await waitFor(confirmationIsWithdrawn);
}

/**
 * Asserts an in-flight capture is ANNOUNCED and not only disabled.
 *
 * ⚠️ Purpose: this screen reports an in-flight turn thoroughly and VISUALLY -- every control and
 * every key binding carries `disabled` while a request is outstanding -- and announced none of it. A
 * disabled control reads to an assistive technology as unavailable with no reason given, and the reason
 * is the entire message: the turn is running, so wait rather than re-key.
 *
 * ⚠️ Assumptions: the region is asserted PRESENT AND EMPTY before the turn as well as populated
 * during it. A live region must be in the accessibility tree before its content changes for the change
 * to be announced at all, so a screen rendering it only while busy would lose the transition into the
 * busy state -- the one that matters -- and a case asserting only the populated state would pass
 * against it.
 *
 * Assumptions: the answer is held so the busy window is observable at all. An already-resolved promise
 * settles in a microtask that falls between this case's own awaits, leaving no render in which the
 * screen is busy; the held promise puts the assertion inside that window and the release closes it.
 * @returns {Promise<void>} Resolves once both states have been observed.
 */
async function announcesAnOutstandingCapture(): Promise<void> {
  let release: ((outcome: TransactionAddOutcome) => void) | undefined;
  vi.mocked(addTransaction).mockReturnValueOnce(
    new Promise<TransactionAddOutcome>(
      /**
       * Captures the settler so this case controls when the turn finishes.
       * @param {(outcome: TransactionAddOutcome) => void} resolve - Settles the held capture.
       * @returns {void} Nothing; the settler is captured as a side effect.
       */
      (resolve): void => {
        release = resolve;
      },
    ),
  );
  const { user } = await mountWithACompleteCapture();

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');

  keyTheAffirmative();
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Asserts the announcement once the turn is in flight.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);
    },
  );

  release?.(createdOutcome(WIRE_NEGATIVE_AMOUNT));

  await waitFor(
    /**
     * Asserts the announcement has emptied once the turn has settled.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
    },
  );
}

/**
 * Asserts the capture operation has been dispatched exactly once.
 * @returns {void} Nothing; the expectation throws until it holds.
 */
function captureDispatchedOnce(): void {
  expect(addTransaction).toHaveBeenCalledTimes(1);
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
 * ⚠️ Assumptions: the keyed dates are REAL dates that are merely very old, and that choice is the whole
 * point of this walk. `app/cbl/COTRN02C.cbl` calls `CSUTLDTC` at L393 and L413 and refuses the date
 * unless the severity is `'0000'` or the message number is `'2513'`, and `app/cbl/CSUTLDTC.cbl` L66 and
 * L137-L138 identify `2513` as `FC-UNSUPP-RANGE` -- a real date the utility cannot compute a day number
 * for. Which dates fall in that range is a property of the utility's implementation, a browser cannot
 * know it, and a local guess at it could refuse a date the reference accepts. So the RANGE half stays at
 * `com.carddemo.common.validation.DateEditValidator` and these two rows prove it is still asked.
 *
 * ⚠️ Assumptions: an IMPOSSIBLE date is deliberately NOT used here, and this row list previously carried
 * `2024-02-31` and `2024-02-30`, which is why it no longer does. Those are refused locally now, by
 * `namesARealCalendarDate` in the screen module, because no tolerance can reach them: an impossible date draws
 * `FC-BAD-DATE-VALUE` or `FC-INVALID-MONTH` -- `app/cbl/CSUTLDTC.cbl` L64 and L67 -- never `2513`.
 * `refusesAnImpossibleCalendarDateLocally` walks that half.
 *
 * Assumptions: the request having been DISPATCHED is asserted first, and that is the half of this walk
 * that proves the delegation survived. A screen that had copied the utility's range rule into the browser
 * would put the same sentence on the same field with no round trip at all, and that copy is the one that
 * could diverge.
 * @returns {Promise<void>} Resolves once both verdicts have been checked.
 */
async function walkSurfacedDateVerdicts(): Promise<void> {
  const { user } = await mountWithACompleteCapture();
  /*
   * WHY : ⚠️ Assumptions: the affirmative is keyed, because the range verdict now arrives on the
   *       CONFIRMING turn -- the only turn this screen sends. The reference reaches it one turn earlier,
   *       at L389-L427 ahead of the confirmation, and that ordering difference is recorded as a
   *       deliberate trade-off on `requestSubmit`: no read operation in this tree resolves the key pair
   *       and mints the confirmation token, so the alternative was a `confirmation`-less body on the
   *       write endpoint before the operator had been asked anything.
   */
  keyTheAffirmative();

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
  /*
   * WHY : Assumptions: the affirmative is keyed once for the whole table, for both the capture rows and
   *       the copy rows. A copy pressed with a `'Y'` already in the field copies and writes in one turn,
   *       which is exactly what the reference's fall-through at `app/cbl/COTRN02C.cbl` L495 does with
   *       `CONFIRMI` as it stands, so keying it changes which arm the copy reaches and not whether the
   *       copy happens.
   */
  keyTheAffirmative();

  for (const row of LOOKUP_REFUSALS) {
    vi.mocked(addTransaction).mockClear();
    vi.mocked(copyLastTransaction).mockClear();
    const dispatched = row.copying ? copyLastTransaction : addTransaction;
    /*
     * WHY : Assumptions: the copy row's 500 supplies NO summary line, deliberately, so this table
     *       exercises both halves of the selection: the two capture rows read the sentence off the
     *       document the way the service supplies it, and the copy row falls through to the browse's
     *       own sentence, which is the arm reached when the document names nothing.
     */
    vi.mocked(dispatched).mockRejectedValue(transportFailure(row.status, [], row.aggregate));
    applyOverrides(row.overrides);

    await pressPfKey(user, row.copying ? 'PFK05' : 'ENTER');

    await waitFor(row.copying ? copyDispatchedOnce : captureDispatchedOnce);
    await expectBandSentence(row.sentence);
    restoreOverriddenFields(row.overrides);
  }
}

/**
 * Walks the two sentences a withheld write produces, and asserts NEITHER costs a request.
 *
 * ⚠️ Refactoring Rationale: both arms are LOCAL, and this walk used to prove the opposite. The blank arm
 * sent a `confirmation`-less capture and read its question off the answer, and the invalid arm sent the
 * same body on the ground that the service decides which non-answer it is -- so the two rows measured
 * two requests between them, on a screen whose reference makes neither. `app/cbl/COTRN02C.cbl` L176-L181
 * answers `SPACES` with three `MOVE`s and a `SEND-TRNADD-SCREEN`, and L182-L187 answers everything else
 * the same way with a different sentence; no arm of that `EVALUATE` but `'Y'`/`'y'` touches a file.
 *
 * ⚠️ Assumptions: the blank row's sentence is looked for ANYWHERE in the document rather than in the
 * band, and that is the one place these two rows differ. Blank raises the asking surface, whose title IS
 * the sentence, and the band is emptied as it opens so the same verbatim string is not on the screen
 * twice at once. The invalid row does not raise a surface -- the reference refuses rather than asks -- so
 * its sentence is in the band, on its control, with the control marked.
 * @returns {Promise<void>} Resolves once both sentences have been checked.
 */
async function walkConfirmationSentences(): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  const asking = CONFIRMATION_SENTENCES[0];
  const refusing = CONFIRMATION_SENTENCES[1];
  expect({ asking: asking?.programLine, refusing: refusing?.programLine }).toEqual({
    asking: 178,
    refusing: 184,
  });

  setField('confirmation', asking?.keyed ?? '');
  await pressPfKey(user, 'ENTER');
  await waitFor(confirmationIsRaised);
  expect(document.body).toHaveTextContent(asking?.sentence ?? '');
  await user.keyboard('{Escape}');
  await waitFor(confirmationIsWithdrawn);

  setField('confirmation', refusing?.keyed ?? '');
  await pressPfKey(user, 'ENTER');
  await expectBandSentence(refusing?.sentence ?? '');
  expect(confirmationSurfaceIsOpen()).toBe(false);
  expect(isMarkedRefused('confirmation')).toBe(true);
  /*
   * WHY : Assumptions: no asterisk. `app/cpy/CSSETATY.cpy` L23-L25 nests the `MOVE '*'` inside the blank
   *       test, and this control is not blank -- it holds a character the reference rejects, so it earns
   *       the colour and not the marker.
   */
  expect(formItem('confirmation').textContent ?? '').not.toContain(FIELD_ERROR_TOKENS.blankMarker);

  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
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
  keyTheAffirmative();

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
 * ⚠️ Assumptions: the ROUND TRIP is exercised through the copy turn and the outbound leg through the
 * capture, because those are the two turns that exist. A capture is sent only for a confirming answer
 * and is answered 201, and the screen clears on a write; the copy turn is the one that legitimately
 * answers 200 with the service's own monetary form, which the screen re-renders through its mask. So the
 * copy leg is what asserts the sign survives a service round trip -- a screen that dropped the leading
 * minus would turn a credit into a debit of the same magnitude, and no field-level rule would report it.
 * @returns {Promise<void>} Resolves once the dispatched body and the displayed value are checked.
 */
async function keepsMoneyInExactFixedPoint(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(createdOutcome(WIRE_NEGATIVE_AMOUNT));
  vi.mocked(copyLastTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();
  expect(typeof valueOf('amount')).toBe('string');
  expect(valueOf('amount')).toBe(KEYED_NEGATIVE_AMOUNT);

  await pressPfKey(user, 'PFK05');

  await waitFor(copyDispatchedOnce);
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(typeof valueOf('amount')).toBe('string');
  expect(valueOf('amount')).toBe(KEYED_NEGATIVE_AMOUNT);

  keyTheAffirmative();
  await pressPfKey(user, 'ENTER');

  await waitFor(captureDispatchedOnce);
  const body = vi.mocked(addTransaction).mock.calls[0]?.[0];
  expect(body).toBeDefined();
  expect(typeof body?.amount).toBe('string');
  expect(body?.amount).toBe(WIRE_NEGATIVE_AMOUNT);
  expect(Object.keys(body ?? {}).sort()).toEqual(CONFIRMED_CAPTURE_BODY_MEMBERS);
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
 * Asserts Enter ASKS before it writes, from the keyboard and from the legend control.
 *
 * ⚠️ Purpose: this case is the acceptance test for the measured defect. Browser validation filled all
 * twelve fields, left the in-form confirmation control EMPTY, activated ONLY the footer legend's
 * `ENTER=Continue`, and recorded `POST /api/v1/transactions` at t0+3212ms carrying a body with no
 * `confirmation` member -- while a read-only `MutationObserver` watching for the overlay reported
 * `popEverVisible === false`. The pointer control on the same filled form asked first and issued
 * nothing until answered. One screen, one action, two confirmation idioms, and only one of them asked.
 *
 * ⚠️ Assumptions: the assertion is that NOTHING is dispatched, on EITHER route, until the affirmative is
 * given, and it is asserted before the answer rather than only counted after it. A case that only
 * counted dispatches at the end would pass against a screen that sent a preview and then asked, which
 * is exactly the shape being removed -- the count would be one either way.
 *
 * ⚠️ Assumptions: BOTH routes are walked, because the defect was a property of the route. The keyboard
 * and the legend control reach the same binding through `usePfKeys`, but they reached different code
 * before the gate was unified, and a case exercising one of them is how the shipped version passed its
 * own suite.
 *
 * Assumptions: the fixture is the CREATED answer and not a preview, because a confirming turn that is
 * answered 200 contradicts the contract -- `transaction-api.yaml` makes 201 the written outcome -- and
 * the screen reports that combination as a failed write rather than as a question.
 * @returns {Promise<void>} Resolves once both paths have been checked.
 */
async function submitsTheCaptureFromBothPaths(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(createdOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  await pressPfKey(user, 'ENTER');
  await waitFor(confirmationIsRaised);
  expect(addTransaction).not.toHaveBeenCalled();
  await user.keyboard('{Escape}');
  await waitFor(confirmationIsWithdrawn);
  expect(addTransaction).not.toHaveBeenCalled();

  await user.click(legendControl('Enter'));
  await waitFor(confirmationIsRaised);
  expect(addTransaction).not.toHaveBeenCalled();

  await user.click(screen.getByRole('button', { name: CONFIRMING_ANSWER }));
  await waitFor(captureDispatchedOnce);
  /*
   * WHY : ⚠️ Assumptions: the dispatched body carries the affirmative, which is the other half of the
   *       defect. The measured request omitted the member entirely, because `buildCreateRequest`
   *       attaches it only for the four letters the contract admits -- so the service was asked to write
   *       and answered that it would rather be asked, and the operator was never asked at all.
   */
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]?.confirmation).toBe(CONFIRMING_ANSWER);
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
 * ⚠️ Assumptions: a DECLINED confirmation reaches the service NOT AT ALL, and the earlier shape of this
 * case asserted only that no dispatch carried a confirming answer -- which a dispatch carrying `'N'`
 * satisfies. Browser validation then measured exactly that dispatch: one `POST /api/v1/transactions`
 * per decline, carrying `"confirmation":"N"`, answered 200 with nothing written and reported to the
 * operator as `Unable to Add Transaction...`. The reference admits no such turn. `app/cbl/COTRN02C.cbl`
 * L173-L181 answers `'N'`, `'n'`, `SPACES` and `LOW-VALUES` by moving `Confirm to add this
 * transaction...` into `WS-MESSAGE`, moving `-1` into `CONFIRML` and performing `SEND-TRNADD-SCREEN` --
 * three moves and a send, with no file access of any kind -- and only the `'Y'`/`'y'` arm at L189-L191
 * performs `ADD-TRANSACTION`. So the assertion is absolute: zero calls.
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
  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
  /*
   * WHY : Assumptions: the confirmation control is left BLANK by the decline, not carrying the character
   *       the declining control stands for. The operator answered an overlay; they did not key into this
   *       field, and the measured shape stamped `'N'` into it -- which left the next Enter submitting an
   *       answer nobody gave. Blank and `'N'` take the same arm of the reference's `EVALUATE` at
   *       L173-L181, so nothing about the reference's behaviour requires the character to be written.
   */
  expect(valueOf('confirmation')).toBe('');

  vi.mocked(addTransaction).mockClear();
  vi.mocked(addTransaction).mockResolvedValue(createdOutcome(WIRE_NEGATIVE_AMOUNT));

  await user.click(confirmationTrigger());
  await user.click(await screen.findByRole('button', { name: CONFIRMING_ANSWER }));

  await waitFor(captureDispatchedOnce);
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]?.confirmation).toBe(CONFIRMING_ANSWER);
}

/**
 * Asserts the field grid is a responsive `Row`/`Col` grid whose arity collapses, and the rule a `Divider`.
 *
 * ⚠️ Purpose: a responsive sweep measured the arities `[2,3,1,3,2,2,1]` holding at EVERY width, because
 * each field wrapper carried `flex: 1 1 0` -- a zero flex-basis makes every column fit, so `wrap` had
 * nothing to act on. At 375 the three-up rows gave 114-pixel columns, about eight monospace characters
 * for the amount and both dates, whose declared widths are twelve, ten and ten. The same sweep measured
 * the seventy-hyphen rule spanning x54 to x640 -- about 586 pixels short of the content edge -- and
 * wrapping onto two ragged lines at 375 and 576.
 *
 * ⚠️ Assumptions: the collapse is asserted through the design system's own BREAKPOINT CLASSES rather than
 * by measuring a width, because jsdom computes no layout: every element reports a zero rectangle, so a
 * width assertion here would pass against any markup at all. `ant-col-xs-24` is the class antd emits for
 * `xs={24}`, and its presence on a field's column is exactly the statement "one field per row on a
 * phone".
 *
 * ⚠️ Assumptions: the census walks THIS SCREEN'S FOURTEEN FIELDS rather than every `.ant-col` in the
 * document, and the narrower form is the correct one rather than a weaker one. The screen is mounted
 * inside the real shell, the shell lays its own title band out on the same grid, and a document-wide
 * census read one of those columns -- `ant-col-24 ant-col-md-6` -- and failed the form for it. Iterating
 * the declared field map instead covers every column this screen owns, in a list that cannot fall behind
 * the screen: a fifteenth field appears in `TRANSACTION_ADD_FIELD_WIDTHS` the moment it is added, so it
 * is checked without this case being edited.
 *
 * Assumptions: the amount is checked for `ant-col-md-8` specifically. It is the field the sweep found
 * unreadable, and its column is the one that has to be three-up only from the medium breakpoint upward.
 *
 * Assumptions: exactly one `.ant-divider` is expected, counted INSIDE the form for the same reason the
 * column census is. The mapset paints one rule, at row 8, and a second would be a section boundary the
 * source does not have.
 * @returns {Promise<void>} Resolves once the grid classes and the rule have been checked.
 */
async function laysTheFieldGridOutResponsively(): Promise<void> {
  await mountCaptureScreen();

  for (const field of Object.keys(TRANSACTION_ADD_FIELD_WIDTHS) as TransactionAddField[]) {
    /*
     * WHY : Assumptions: the containment is evaluated HERE and compared as a boolean, rather than being
     *       expressed with `expect.stringContaining`. That matcher is typed `any`, so putting it in an
     *       object literal either propagates `any` into the comparison or needs an assertion the
     *       compiler then judges redundant -- two lint rules that cannot both be satisfied through it.
     *       The row's identity, which is what makes a walked failure diagnosable, is preserved either
     *       way.
     */
    expect({
      field,
      carriesTheMobileSpan: gridColumnOf(field).className.includes('ant-col-xs-24'),
    }).toEqual({ field, carriesTheMobileSpan: true });
  }

  expect(gridColumnOf('amount').className).toContain('ant-col-md-8');
  expect(captureForm().querySelectorAll('.ant-divider')).toHaveLength(1);
}

/**
 * Asserts a refusal the screen cannot attribute still reaches the operator, and moves no cursor.
 *
 * ⚠️ Purpose: this is the worst error-handling outcome browser validation found. A conforming 400 whose
 * two `fieldErrors` named fields belonging to another screen produced a band reading
 * `Please fix the highlighted fields` over a form with nothing highlighted --
 * `.ant-form-item-has-error` count 0, no `[aria-invalid]` anywhere, no asterisk -- and NEITHER refusal
 * sentence appeared anywhere in the document, so the reason for the rejection was unavailable through
 * any surface. The cursor was then moved to `Enter Acct #`, asserting a problem with a field the service
 * had not mentioned.
 *
 * ⚠️ Assumptions: the document carries a summary line as well as the field entry, and the case asserts the
 * band shows the FIELD's sentence and NOT the summary. That inversion is the fix: a summary line is
 * written to be read beside highlights, and with none rendered it is not merely unhelpful but false,
 * whereas the field sentence states what was actually wrong.
 *
 * Assumptions: the foreign field's sentence is taken from the catalog rather than typed here, so this
 * case introduces no user-visible string of its own; `userId` is used as the foreign field because it is
 * the one the measured envelope named.
 *
 * Assumptions: the cursor is asserted to be exactly where it was, by identity, rather than merely not on
 * the account field. Any move at all would be a move to an innocent control.
 * @returns {Promise<void>} Resolves once the band, the highlight census and the cursor are checked.
 */
async function surfacesARefusalItCannotAttribute(): Promise<void> {
  const foreignSentence = SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY;
  const summaryLine = SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO;
  vi.mocked(addTransaction).mockRejectedValue(
    new ApiRequestError(
      'PROBLEM',
      400,
      apiError({
        status: 400,
        message: summaryLine,
        fieldErrors: [fieldError('userId', foreignSentence)],
      }),
      'refused with 400',
    ),
  );
  const { user } = await mountWithACompleteCapture();
  keyTheAffirmative();
  await user.click(control('merchantId'));
  const cursorBefore = document.activeElement;

  await pressPfKey(user, 'ENTER');

  await expectBandSentence(foreignSentence);
  expect(messageBand().textContent ?? '').not.toContain(summaryLine);
  expect(document.querySelectorAll('.ant-form-item-has-error')).toHaveLength(0);
  expect(document.activeElement).toBe(cursorBefore);
}

/**
 * Asserts an answer the CLIENT could not interpret is not reported as a failed write.
 *
 * ⚠️ Purpose: `ui/src/api/client.ts` normalises every transport failure into an `ApiRequestError`
 * before it reaches a screen, so the only rejection that can arrive as something else is a resource
 * function's own contract check -- `requireTransactionAddPreview` raising `RangeError` when a 200 body
 * carries a confirmation token that does not match the shape the client enforces, for instance. That is a
 * SUCCESSFUL exchange whose answer the client refused to read, and the screen cannot know whether the
 * service wrote anything.
 *
 * ⚠️ Assumptions: reporting it with the reference's own `Unable to Add Transaction...` is WRONG and
 * that is what this case pins. Browser validation hit exactly this path -- a fixture returning a
 * non-conforming token -- and read the write's failure sentence off the band, which tells the operator a
 * write failed when the client never got far enough to know one had been attempted. The reference has no
 * arm for "the region answered with something it cannot parse": a 3270 terminal either receives a map or
 * times out. So neither of its two sentences is available, and the authored
 * {@link PERSISTENT_FAILURE_REPORT_IT} is what the operator gets.
 *
 * ⚠️ Assumptions: no control is marked and the cursor is NOT sent to the ADDRESSING KEY, which is
 * where the arm this replaces sent it. No field was refused -- the answer was unreadable, not wrong --
 * so every field the screen could move to is an innocent one, and landing the cursor on `Enter Acct #`
 * asserts a refusal the service never made. The cursor is asserted as \"not the addressing key\" rather
 * than as \"exactly where it was\", because the operator's last action was answering the surface and the
 * overlay's own withdrawal decides where focus returns to; the screen's obligation is to not move it,
 * which is what the negative names.
 * @returns {Promise<void>} Resolves once the sentence and the cursor have been checked.
 */
async function reportsAnUnreadableAnswerWithoutBlamingTheWrite(): Promise<void> {
  vi.mocked(addTransaction).mockRejectedValue(
    new RangeError('transaction add preview carries a malformed confirmation token'),
  );
  const { user } = await mountWithACompleteCapture();

  await confirmThroughTheAsk(user);

  await waitFor(captureDispatchedOnce);
  await expectBandSentence(PERSISTENT_FAILURE_REPORT_IT);
  expect(messageBand().textContent ?? '').not.toContain(ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION);
  expect(messageBand().textContent ?? '').not.toContain(
    SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION,
  );
  expect(document.querySelectorAll('.ant-form-item-has-error')).toHaveLength(0);
  expect(document.activeElement).not.toBe(control('accountId'));
}

/**
 * Asserts every refusal the response names is rendered on its control, with its accessibility wiring.
 *
 * ⚠️ Assumptions: `aria-invalid` and `aria-describedby` are asserted on the CONTROL and the sentence
 * inside the `Form.Item`, because a border colour is not a refusal. WCAG 1.4.1 forbids colour as the sole
 * carrier of information, and browser validation found this screen carrying no `[aria-invalid]` at all on
 * a refused turn -- so an operator using assistive technology had nothing.
 *
 * ⚠️ Assumptions: the asterisk is asserted PRESENT on the entry the response marked `BLANK` and ABSENT on
 * the one it marked `NOT_OK`. `app/cpy/CSSETATY.cpy` nests its `MOVE '*'` inside the blank test at
 * L23-L25, so the marker distinguishes a never-supplied field from a rejected value, and rendering it on
 * both would erase the distinction the copybook draws.
 *
 * Assumptions: the response is allowed to mark a field `BLANK` that carries a value locally, and the
 * fixture does exactly that. The service's emptiness rule and this screen's are independent -- the
 * contract declares the state as a member precisely so the screen renders what the SERVICE decided -- so
 * the rendering obligation is what is under test here, not whether the two rules agree.
 *
 * Assumptions: the cursor lands on the FIRST entry the document lists, not on the addressing key. The
 * reference moves it to the field it refused, and with several refused the first is the one an operator
 * works from.
 * @returns {Promise<void>} Resolves once both refusals, their wiring and the cursor are checked.
 */
async function rendersEveryNamedRefusalWithItsWiring(): Promise<void> {
  const blankSentence = ADD_MESSAGES.SOURCE_CAN_NOT_BE_EMPTY;
  const refusedSentence = ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC;
  vi.mocked(addTransaction).mockRejectedValue(
    transportFailure(400, [
      fieldError('source', blankSentence, 'BLANK'),
      fieldError('merchantId', refusedSentence),
    ]),
  );
  const { user } = await mountWithACompleteCapture();
  keyTheAffirmative();

  await pressPfKey(user, 'ENTER');

  await expectBandSentence(blankSentence);
  for (const [field, sentence] of [
    ['source', blankSentence],
    ['merchantId', refusedSentence],
  ] as const) {
    expect({ field, marked: isMarkedRefused(field) }).toEqual({ field, marked: true });
    expect({
      field,
      carriesTheSentence: (formItem(field).textContent ?? '').includes(sentence),
    }).toEqual({ field, carriesTheSentence: true });
    expect({ field, invalid: control(field).getAttribute('aria-invalid') }).toEqual({
      field,
      invalid: 'true',
    });
    expect({
      field,
      describedByItsOwnHelp: (control(field).getAttribute('aria-describedby') ?? '').includes(
        control(field).id,
      ),
    }).toEqual({ field, describedByItsOwnHelp: true });
  }

  expect(formItem('source').textContent ?? '').toContain(FIELD_ERROR_TOKENS.blankMarker);
  expect(formItem('merchantId').textContent ?? '').not.toContain(FIELD_ERROR_TOKENS.blankMarker);
  await waitFor(
    /** Asserts the cursor reached the first entry the document listed. */
    (): void => {
      expect(document.activeElement).toBe(control('source'));
    },
  );
}

/** The human spellings of one hundred dollars an operator would actually type. */
const KEYED_AMOUNT_SPELLINGS = ['100', '100.00', '+100.00', '00000100.00', '+00000100.00'] as const;

/** The reference's own field rendering of those spellings, through `PIC +99999999.99`. */
const CANONICAL_KEYED_AMOUNT = '+00000100.00';

/** The contract's monetary form of the same amount, which carries no sign and no leading zeros. */
const WIRE_CANONICAL_AMOUNT = '100.00';

/**
 * Asserts every human spelling of an amount is accepted and re-rendered into the field's own form.
 *
 * ⚠️ Purpose: this case exists because the flow AAP section 0.9.4 lists as an acceptance criterion could
 * not be run. Browser validation drove eighteen spellings through this control and only `-` plus exactly
 * eight digits plus `.` plus two decimals was accepted, so `100.00` and `123.45` were refused with
 * `Amount should be in format -99999999.99` and the tester concluded that no positive amount could be
 * entered at all. `hasBaselineAmountShape` does admit `'+'` -- `app/cbl/COTRN02C.cbl` L340 tests
 * `TRNAMTI(1:1) NOT EQUAL '-' AND '+'` -- so the literal claim is wrong, but `+00000100.00` is not a
 * form anybody types, and the practical effect was as reported.
 *
 * ⚠️ Assumptions: the acceptance is proved through BOTH routes, and the second is the one that would have
 * been missed. The normaliser fires on blur, and pressing Enter with the caret still in the amount fires
 * no blur at all, so a screen normalising only on blur would still refuse the operator who types and
 * reaches straight for Enter. The first leg of each row leaves the field; the second does not.
 *
 * Assumptions: the wire value is asserted as a STRING, and as the contract's own form rather than the
 * field's. The screen holds `+99999999.99` while `transaction-api.yaml` declares
 * `^-?[0-9]{1,9}\.[0-9]{2}$`, so `+00000100.00` on the screen must reach the service as `100.00`; the
 * conversion is a rule of the contract and not a cosmetic difference.
 * @returns {Promise<void>} Resolves once every spelling has been checked on both routes.
 */
async function acceptsEveryKeyedAmountSpelling(): Promise<void> {
  /*
   * WHY : ⚠️ Assumptions: the turn is armed to be REFUSED, so one filled screen serves all five rows and
   *       both of each row's legs. A written capture clears every control -- `INITIALIZE-ALL-FIELDS` at
   *       `app/cbl/COTRN02C.cbl` L725 -- and a refused turn re-displays the populated map, which is what
   *       lets the second leg key a spelling into a field that still holds one. The subject is what the
   *       control DISPLAYS and what the body CARRIES, and both are observable whichever way the turn
   *       settled.
   */
  vi.mocked(addTransaction).mockRejectedValue(transportFailure(500));
  const { user } = await mountWithACompleteCapture();
  keyTheAffirmative();

  for (const spelling of KEYED_AMOUNT_SPELLINGS) {
    vi.mocked(addTransaction).mockClear();
    /*
     * WHY : ⚠️ Assumptions: the control is FOCUSED before the value is placed in it, because a blur event
     *       is only dispatched for an element that had focus. `setField` fires a change and nothing else,
     *       so without this click the leg meant to prove the blur normaliser would prove nothing: the
     *       field would simply never be left.
     */
    await user.click(control('amount'));
    setField('amount', spelling);
    await user.click(control('merchantId'));
    expect({ spelling, displayed: valueOf('amount') }).toEqual({
      spelling,
      displayed: CANONICAL_KEYED_AMOUNT,
    });

    await pressPfKey(user, 'ENTER');
    await waitFor(captureDispatchedOnce);
    const blurred = vi.mocked(addTransaction).mock.calls[0]?.[0];
    expect({ spelling, amount: blurred?.amount }).toEqual({
      spelling,
      amount: WIRE_CANONICAL_AMOUNT,
    });
    await expectBandSentence(ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION);

    vi.mocked(addTransaction).mockClear();
    setField('amount', spelling);
    await pressPfKey(user, 'ENTER');
    await waitFor(captureDispatchedOnce);
    const unblurred = vi.mocked(addTransaction).mock.calls[0]?.[0];
    expect({ spelling, amount: unblurred?.amount }).toEqual({
      spelling,
      amount: WIRE_CANONICAL_AMOUNT,
    });
    expect({ spelling, displayed: valueOf('amount') }).toEqual({
      spelling,
      displayed: CANONICAL_KEYED_AMOUNT,
    });
  }

  setField('amount', A_COMPLETE_CAPTURE.amount);
}

/**
 * Asserts an amount that is not an amount is still refused, and refused against what was typed.
 *
 * ⚠️ Assumptions: the normaliser must not become a way to accept anything. A value it cannot read leaves
 * it UNCHANGED, so the field still holds what the operator typed and
 * `Amount should be in format -99999999.99` still names it -- which is the sentence
 * `app/cbl/COTRN02C.cbl` L346 raises. A normaliser that coerced an unreadable value into `+00000000.00`
 * would post a zero-dollar transaction for an operator who mistyped.
 *
 * Assumptions: nine integer digits are in this list. The field is `PIC +99999999.99` -- eight integer
 * positions -- so `100000000.00` needs a ninth the mask has no position for, and it must be refused
 * rather than truncated into a hundredth of itself.
 * @returns {Promise<void>} Resolves once every unreadable spelling has been checked.
 */
async function refusesAnAmountThatIsNotAnAmount(): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  for (const spelling of ['abc', '1.234', '100000000.00', '1.2.3', '-', '+', '-.']) {
    vi.mocked(addTransaction).mockClear();
    await user.click(control('amount'));
    setField('amount', spelling);
    await user.click(control('merchantId'));

    await pressPfKey(user, 'ENTER');

    await expectBandSentence(ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99);
    expect({ spelling, dispatched: vi.mocked(addTransaction).mock.calls.length }).toEqual({
      spelling,
      dispatched: 0,
    });
    expect({ spelling, displayed: valueOf('amount') }).toEqual({ spelling, displayed: spelling });
    expect({ spelling, marked: isMarkedRefused('amount') }).toEqual({ spelling, marked: true });
  }

  setField('amount', A_COMPLETE_CAPTURE.amount);
}

/** Dates whose shape is right and which name no day that exists, with the field each is keyed into. */
const IMPOSSIBLE_CALENDAR_DATES = [
  { field: 'originDate', keyed: '2022-13-45', sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE },
  { field: 'originDate', keyed: '2024-02-30', sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE },
  { field: 'originDate', keyed: '1900-02-29', sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE },
  { field: 'originDate', keyed: '2022-04-31', sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE },
  { field: 'originDate', keyed: '2022-00-10', sentence: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE },
  { field: 'processDate', keyed: '2022-13-45', sentence: ADD_MESSAGES.PROC_DATE_NOT_A_VALID_DATE },
  { field: 'processDate', keyed: '2023-02-29', sentence: ADD_MESSAGES.PROC_DATE_NOT_A_VALID_DATE },
] as const satisfies readonly {
  readonly field: TransactionAddField;
  readonly keyed: string;
  readonly sentence: string;
}[];

/**
 * Asserts a shape-valid date that names no real day is refused locally, with no request.
 *
 * ⚠️ Purpose: browser validation keyed `2022-13-45` into the originating date and observed NOTHING -- no
 * band, no marked field -- and watched the value reach the service as `"originDate":"2022-13-45"` for a
 * 200. Only the field's shape had ever been checked, and the sentence
 * `Orig Date - Not a valid date...` that `app/cbl/COTRN02C.cbl` L401 raises was unreachable from any
 * input.
 *
 * ⚠️ Assumptions: `1900-02-29` and `2023-02-29` are in the list, and they are the rows that matter most.
 * A divisible-by-four leap test accepts the first and a missing leap test accepts neither -- so between
 * them they pin the full Gregorian rule that the reference's own callable service applies, rather than
 * the shorthand.
 *
 * Assumptions: zero requests is asserted per row. The refusal is local precisely because it cannot
 * diverge from the reference, so a round trip here would be a round trip the reference does not need.
 * @returns {Promise<void>} Resolves once every impossible date has been checked.
 */
async function refusesAnImpossibleCalendarDateLocally(): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  for (const row of IMPOSSIBLE_CALENDAR_DATES) {
    vi.mocked(addTransaction).mockClear();
    setField(row.field, row.keyed);

    await pressPfKey(user, 'ENTER');

    await expectBandSentence(row.sentence);
    expect({ keyed: row.keyed, dispatched: vi.mocked(addTransaction).mock.calls.length }).toEqual({
      keyed: row.keyed,
      dispatched: 0,
    });
    expect({ keyed: row.keyed, marked: isMarkedRefused(row.field) }).toEqual({
      keyed: row.keyed,
      marked: true,
    });
    setField(row.field, A_COMPLETE_CAPTURE[row.field]);
  }
}

/**
 * Asserts a real date is NOT refused locally, so the local rule cannot have overreached.
 *
 * ⚠️ Assumptions: `2024-02-29`, `2000-02-29` and `1500-01-01` are all admitted by the local rule. The
 * first two are leap days a wrong century rule would reject; the third is a real date the reference
 * itself may refuse for being outside the utility's supported range, and it must still be DISPATCHED --
 * because `2513` is the one complaint L389-L427 tolerates, and only the service can say whether it
 * applies. A local rule that refused it would refuse a date the reference can accept.
 *
 * ⚠️ Assumptions: the turn is armed to be REFUSED, and that is what lets one filled screen serve all
 * five rows. A written capture clears every control -- the reference performs `INITIALIZE-ALL-FIELDS` at
 * `app/cbl/COTRN02C.cbl` L725 before it composes its acknowledgement -- so a row answered 201 would
 * leave the next row with nothing to send. A refused turn re-displays the populated map, which is the
 * state every other arm of the program leaves the screen in. The subject is what was SENT, and the spy
 * records that whichever way the turn settled.
 * @returns {Promise<void>} Resolves once every real date has been shown to reach the service.
 */
async function dispatchesEveryRealCalendarDate(): Promise<void> {
  vi.mocked(addTransaction).mockRejectedValue(transportFailure(500));
  const { user } = await mountWithACompleteCapture();
  keyTheAffirmative();

  for (const keyed of ['2024-02-29', '2000-02-29', '1500-01-01', '2022-12-31', '2022-01-01']) {
    vi.mocked(addTransaction).mockClear();
    setField('originDate', keyed);

    await pressPfKey(user, 'ENTER');

    await waitFor(captureDispatchedOnce);
    const body = vi.mocked(addTransaction).mock.calls[0]?.[0];
    expect({ keyed, sent: body?.originDate }).toEqual({ keyed, sent: keyed });
  }

  setField('originDate', A_COMPLETE_CAPTURE.originDate);
}

/**
 * Reports whether a confirmation surface is currently displayed.
 *
 * ⚠️ Assumptions: the surface is located by antd's own overlay class rather than by a role, and the
 * hidden and leaving states are filtered out. A `Popconfirm` keeps its overlay MOUNTED after it closes
 * and marks it `ant-popover-hidden`, so a role query would keep finding the two answer controls after a
 * withdrawal and every "the surface is gone" assertion would pass vacuously.
 * `ui/src/screens/transactionAdd/transactionAddTurns.test.tsx` reads the same class for the same reason,
 * so this is the tree's established way of asking the question rather than a second convention.
 * @returns {boolean} `true` while a confirmation surface stands.
 */
function confirmationSurfaceIsOpen(): boolean {
  return [...document.querySelectorAll('.ant-popover')].some(
    /**
     * Reports whether one overlay node is displayed rather than hidden or animating out.
     * @param {Element} node - The candidate overlay.
     * @returns {boolean} `true` when it is on screen.
     */
    (node: Element): boolean =>
      !node.className.includes('ant-popover-hidden') && !node.className.includes('-leave'),
  );
}

/** Asserts no confirmation surface stands, for use inside a `waitFor`. */
function confirmationIsWithdrawn(): void {
  expect(confirmationSurfaceIsOpen()).toBe(false);
}

/** Asserts a confirmation surface stands, for use inside a `waitFor`. */
function confirmationIsRaised(): void {
  expect(confirmationSurfaceIsOpen()).toBe(true);
}

/**
 * ⚠️ Asserts the anchor is brought onto the display BEFORE the confirmation surface is raised.
 *
 * ⚠️ Purpose: close a browser-measured defect that no request-shape or focus case can see. The overlay
 * is anchored to the in-content control at the very foot of a form long enough to overflow the screen
 * body, and a turn taken from the function-key legend needs no scrolling to reach that control -- so
 * with the body unscrolled the anchor sat at `top: 934.67` in a 900-pixel display and the overlay,
 * which the design system flips above an anchor it cannot fit below, landed at `bottom: 923`. Twenty
 * three pixels past the foot of the display, with the lower eleven pixels of BOTH answers cut off; they
 * stayed clickable by a single pixel. The same overlay raised while the anchor was in view measured
 * `bottom: 892`, fully inside, so the anchor's position is the whole of the defect.
 *
 * ⚠️ Assumptions: what is asserted is that the scroll happens in the SAME synchronous turn as the
 * request to open, and the spy records whether a surface already stood when it ran because that is the
 * observable which distinguishes it. Three negative controls established the exact boundary of the
 * claim, and the middle one narrowed it. Removing the scroll altogether fails here -- `expected [] to
 * deeply equal [ false ]`. DEFERRING it to a later task fails here too -- `expected [ true ] to deeply
 * equal [ false ]` -- and that is the hazard worth guarding, because the overlay measures its anchor on
 * the commit and a scroll arriving after the commit positions it against where the anchor used to be.
 * But merely swapping the two statements within the handler PASSES, and on inspection it should: a
 * synchronous scroll reflows immediately while a state update only schedules a render, so both orders
 * complete before React commits and before the overlay measures anything. So this case does NOT claim
 * that one line precedes the other, and an earlier draft of this paragraph did claim it and was wrong.
 *
 * ⚠️ Assumptions: this is the strongest form available in this runner and it is deliberately NOT a
 * geometry check. jsdom performs no layout, lays out no scrollport and -- as `ui/src/test/setup.ts`
 * records -- does not implement this method at all, so the rectangle the fix delivers is measurable
 * only in a real browser and is verified there. What is falsifiable here is that the screen asks, and
 * asks first.
 *
 * Assumptions: BOTH routes to the gate are exercised. The legend route is the one that carried the
 * defect, and the pointer route is asserted alongside it because the two routes running through one
 * function is this screen's own stated contract -- a fix applied to one of them would be a second
 * divergence between them, which is the class of defect this gate already had once.
 * @returns {Promise<void>} Resolves once both routes have been asserted.
 */
async function bringsTheAnchorIntoViewBeforeAsking(): Promise<void> {
  const { user } = await mountWithACompleteCapture();
  const surfaceStoodWhenScrolled: boolean[] = [];
  const scrolled = vi.spyOn(confirmationTrigger(), 'scrollIntoView').mockImplementation(
    /**
     * Records whether a surface already stood at the moment the anchor was scrolled.
     * @returns {void} Nothing; the recording is the observable.
     */
    (): void => {
      surfaceStoodWhenScrolled.push(confirmationSurfaceIsOpen());
    },
  );

  for (const route of ['legend', 'pointer']) {
    surfaceStoodWhenScrolled.length = 0;
    if (route === 'legend') {
      await user.click(legendControl('Enter'));
    } else {
      await user.click(confirmationTrigger());
    }
    await waitFor(confirmationIsRaised);

    expect(
      surfaceStoodWhenScrolled,
      `the ${route} route must bring the anchor into view exactly once, before the surface stands`,
    ).toEqual([false]);
    expect(
      addTransaction,
      `and asking must still cost no request on the ${route} route`,
    ).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    await waitFor(confirmationIsWithdrawn);
  }

  scrolled.mockRestore();
}

/**
 * Asserts the confirmation surface holds every structural term of its contract.
 *
 * ⚠️ Purpose: five separate rendering findings landed on this one surface, and none of them was a
 * behaviour a request-shape case can see. The trigger was measured SOLID PRIMARY and bottom-right,
 * making it the third emphasised control in a frame that already carries the legend's Enter and F5 and
 * putting this screen's own action on the side no other screen puts one. The overlay's two answers were
 * measured 28x22 and 26x22 device pixels, the smallest interactive controls anywhere in the
 * application. And the overlay's title was a VERBATIM duplicate of the prompt beside the confirmation
 * control, so one sentence stood on the screen twice and served as both the overlay's name and the
 * input's accessible name -- heard twice in a row by anyone listening to it.
 *
 * ⚠️ Assumptions: every term is asserted STRUCTURALLY -- a class, an inline size, a focused element,
 * two strings compared -- because jsdom performs no layout and every `getBoundingClientRect` answers
 * zero. A case written against measured geometry here would pass against any implementation at all,
 * which is worse than no case; the pixels belong to a browser pass.
 *
 * Assumptions: the trigger's leading-edge placement is asserted as the ABSENCE of an end-justification
 * on the row that holds it. antd expresses `Flex`'s `justify` as an inline `justifyContent`, so a row
 * that pushed its child to the trailing edge would carry `flex-end` there and this reads empty; that is
 * the strongest claim available without layout.
 *
 * Assumptions: the target floor is read from `ui/src/theme/tokens.ts` rather than written here. That
 * module records WCAG 2.2 AA as 24 CSS pixels and records separately why the 44-pixel AAA figure is not
 * the one applied, so pinning a literal here would put a second, unexplained figure in the tree.
 * @returns {Promise<void>} Resolves once the trigger, the two answers, the focus and the two sentences
 *   have been checked.
 */
async function holdsTheConfirmationSurfaceToItsContract(): Promise<void> {
  const { user } = await mountWithACompleteCapture();

  const trigger = confirmationTrigger();
  expect(trigger).toHaveClass('ant-btn-default');
  expect(trigger).not.toHaveClass('ant-btn-primary');
  const triggerRow = trigger.closest('.ant-flex');
  expect(triggerRow).toBeInstanceOf(HTMLElement);
  expect(triggerRow instanceof HTMLElement ? triggerRow.style.justifyContent : 'no row').toBe('');

  await user.click(trigger);
  await waitFor(confirmationIsRaised);

  const answers = [
    { name: DECLINING_ANSWER, control: screen.getByRole('button', { name: DECLINING_ANSWER }) },
    { name: CONFIRMING_ANSWER, control: screen.getByRole('button', { name: CONFIRMING_ANSWER }) },
  ];
  for (const answer of answers) {
    expect({
      name: answer.name,
      inline: answer.control.style.minInlineSize,
      block: answer.control.style.minBlockSize,
    }).toEqual({
      name: answer.name,
      inline: `${String(TARGET_SIZE_AA_MINIMUM)}px`,
      block: `${String(TARGET_SIZE_AA_MINIMUM)}px`,
    });
  }

  await waitFor(
    /**
     * Asserts the declining answer holds the cursor once the surface has settled.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(document.activeElement).toBe(answers[0]?.control);
    },
  );

  /*
   * WHY : ⚠️ Assumptions: the two sentences are compared as STRINGS as well as located in the
   *       document. Asserting only that the title is present would pass against a surface titled with
   *       the field's own prompt, which is exactly the measured defect -- the duplication is the
   *       finding, so the inequality is what has to be asserted.
   */
  expect(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION).not.toBe(
    TRANSACTION_ADD_FIELD_LABELS.confirmation,
  );
  expect(screen.getByText(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION)).toBeInTheDocument();
  expect(control('confirmation')).toHaveAccessibleName(TRANSACTION_ADD_FIELD_LABELS.confirmation);
}

/**
 * Asserts a decline issues NO request, by every route that can produce one.
 *
 * ⚠️ Purpose: this case exists because the defect it guards was live and the suite passed. Browser
 * validation measured the declining control issuing one `POST /api/v1/transactions` carrying
 * `"confirmation":"N"`, answered 200 with nothing written, and reported to the operator as
 * `Unable to Add Transaction...` -- so an operator who successfully cancelled was told the add had
 * FAILED. The oracle admits no turn at all on this arm: `app/cbl/COTRN02C.cbl` L173-L181 answers `'N'`,
 * `'n'`, `SPACES` and `LOW-VALUES` with three `MOVE`s and a `SEND-TRNADD-SCREEN`, touching no file.
 *
 * ⚠️ Assumptions: all FOUR routes are walked rather than one, because the defect was a property of a
 * route and not of the answer. A keyed `'N'` submitted with the ENTER key, the same answer submitted
 * through the legend's own ENTER control, a clicked declining control on a raised surface and an
 * `Escape` on a raised surface are four different entry points into the same arm, and a case covering
 * one of them is exactly how the shipped version passed its own suite.
 *
 * ⚠️ Assumptions: the clicked declining control is walked HERE as well as in
 * {@link gatesTheWriteOnAConfirmingAnswer}, and the overlap is deliberate rather than an oversight.
 * That case's subject is the Y-versus-N gate -- it has to reach the confirming half to be about
 * anything -- whereas this one's subject is that NO route produces a request, and the route the browser
 * actually measured issuing one was this one. Leaving it out of the case named after the defect would
 * mean the defect's own regression lived somewhere else, where a later edit narrowing that case's scope
 * would silently remove it.
 *
 * Assumptions: the confirmation control is asserted BLANK after each decline. Blank and `'N'` take the
 * same arm of the reference's `EVALUATE`, so returning the field to blank is faithful, and the measured
 * shape stamped `'N'` into a control the operator never keyed into -- which left the next Enter
 * carrying an answer nobody gave.
 * @returns {Promise<void>} Resolves once all three routes have been walked.
 */
async function declinesFromEveryRouteWithoutARequest(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  setField('confirmation', DECLINING_ANSWER);
  await pressPfKey(user, 'ENTER');
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(addTransaction).not.toHaveBeenCalled();
  expect(valueOf('confirmation')).toBe('');

  setField('confirmation', DECLINING_ANSWER);
  await user.click(legendControl('Enter'));
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(addTransaction).not.toHaveBeenCalled();
  expect(valueOf('confirmation')).toBe('');

  await user.click(confirmationTrigger());
  await waitFor(confirmationIsRaised);
  /*
   * WHY : ⚠️ Assumptions: the declining control is located by the character the mapset's own `(Y/N)`
   *       hint names, derived from the constant the screen publishes rather than retyped -- so the
   *       control this leg clicks is the one an operator sees, and a screen that relabelled it would
   *       fail here rather than pass against a stale literal.
   */
  await user.click(screen.getByRole('button', { name: DECLINING_ANSWER }));
  await waitFor(confirmationIsWithdrawn);
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
  expect(valueOf('confirmation')).toBe('');

  await user.click(confirmationTrigger());
  await waitFor(confirmationIsRaised);
  await user.keyboard('{Escape}');
  await waitFor(confirmationIsWithdrawn);
  await expectBandSentence(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
  expect(addTransaction).not.toHaveBeenCalled();
  expect(copyLastTransaction).not.toHaveBeenCalled();
  expect(valueOf('confirmation')).toBe('');
}

/**
 * Asserts every submitting surface passes the same gate, in the reference's own order.
 *
 * ⚠️ Purpose: the second half of the same defect. Browser validation measured TWO independent submitting
 * paths on this screen: the legend's Enter dispatched immediately with no confirmation surface at all,
 * while the in-content control opened one whose answers each dispatched a turn of their own. A screen
 * with two paths to a write has no gate, however correct either path looks on its own.
 *
 * ⚠️ Assumptions: the gate is proved by a REFUSABLE field rather than by counting requests on a valid
 * one, because that is the order the reference fixes. `PROCESS-ENTER-KEY` performs
 * `VALIDATE-INPUT-KEY-FIELDS` at `app/cbl/COTRN02C.cbl` L166 and `VALIDATE-INPUT-DATA-FIELDS` at L167
 * and evaluates `CONFIRMI` only at L169, and each paragraph ends its turn with `SEND-TRNADD-SCREEN`, so
 * the reference cannot reach any confirmation arm over a refusable field. A surface that opens anyway
 * asks the operator to confirm a submission the screen already knows will be refused.
 *
 * Assumptions: the merchant identifier is the field made refusable, because its rule is the LAST one
 * `dataFieldFailure` applies -- L430-L436 -- so reaching its sentence proves the whole chain ran rather
 * than that the first check short-circuited.
 *
 * Assumptions: the final leg presses Enter while the surface stands and asserts nothing was written.
 * A raised confirmation gives focus to its declining control, and the gate refuses a keystroke behind
 * an open surface, so neither route can turn a reflex keypress into a write.
 * @returns {Promise<void>} Resolves once all three surfaces and the raised-surface keystroke are checked.
 */
async function routesEverySubmitPathThroughTheOneGate(): Promise<void> {
  vi.mocked(addTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();
  setField('merchantId', 'ABC456789');

  await pressPfKey(user, 'ENTER');
  await expectBandSentence(ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC);
  expect(addTransaction).not.toHaveBeenCalled();

  await user.click(legendControl('Enter'));
  await expectBandSentence(ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC);
  expect(addTransaction).not.toHaveBeenCalled();

  await user.click(confirmationTrigger());
  await expectBandSentence(ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC);
  expect(confirmationSurfaceIsOpen()).toBe(false);
  expect(addTransaction).not.toHaveBeenCalled();

  setField('merchantId', A_COMPLETE_CAPTURE.merchantId);
  await user.click(confirmationTrigger());
  await waitFor(confirmationIsRaised);

  await pressPfKey(user, 'ENTER');
  expect(addTransaction).not.toHaveBeenCalled();
  /*
   * WHY : Assumptions: the band must NOT carry the unmapped-key sentence either. This screen binds Enter
   *       -- `app/cbl/COTRN02C.cbl` L134 has a `DFHENTER` arm -- so reporting `invalid key` for a press
   *       the screen merely declined to act on would state something the reference does not, and the
   *       shared hook moves the cursor when it reports one, which would strand the operator outside a
   *       surface that is still open.
   */
  bandCarriesNoUnmappedKeySentence();
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

  vi.mocked(addTransaction).mockResolvedValue(createdOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountWithACompleteCapture();

  /*
   * WHY : ⚠️ Assumptions: the capture is taken all the way to a WRITE, through the gate an operator who
   *       never keys into the confirmation field goes through. AAP section 0.9.4 lists "transaction add"
   *       among the flows that must work end to end, and a case that stopped at a question would not
   *       have exercised the flow at all -- which is the state this case was in while the unconfirmed
   *       turn existed, because a preview looked like a completed action from here.
   */
  await confirmThroughTheAsk(user);

  await waitFor(captureDispatchedOnce);
  expect(vi.mocked(addTransaction).mock.calls[0]?.[0]?.accountId).toBe(ACCOUNT_ID);
  const acknowledgement = formatMessageTemplate(MESSAGE_TEMPLATES.TRANSACTION_ADDED_SUCCESSFULLY, {
    'TRAN-ID': '0000000000683581',
  });
  /*
   * WHY : ⚠️ Assumptions: the acknowledgement is asserted through {@link expectBandToRead}, which
   *       compares raw `textContent`, and the doubled space is then named on its own line. The sentence
   *       joins `'Transaction added successfully. '` to `' Your Tran ID is '` -- two literals the
   *       reference's `STRING` statement at `app/cbl/COTRN02C.cbl` L728-L732 declares that way -- so the
   *       painted text carries TWO spaces where English wants one. A normalising matcher cannot tell the
   *       two apart, so an assertion using one would pass against a screen that had tidied a contract
   *       away.
   */
  await expectBandToRead(acknowledgement);
  expect(acknowledgement).toBe(
    'Transaction added successfully.  Your Tran ID is 0000000000683581.',
  );
  /*
   * WHY : ⚠️ Assumptions: the VARIANT is asserted beside the sentence, because a review found this
   *       screen reporting a successful capture through the refusal variant while the user screens
   *       reported theirs through the success one -- the same event rendered two ways in one
   *       application. The mapset gives this screen one message line and says nothing about colour, so
   *       the variant is the migrated screen's own decision, and an acknowledgement is not a refusal.
   *       The negative is asserted too: reading only for `success` would pass against a band that had
   *       somehow rendered both.
   */
  expect(bandAlertVariant()).toBe('success');
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
  /*
   * WHY : ⚠️ Assumptions: the resolved pair is obtained through the COPY turn, because that is the only
   *       turn that answers with one. A capture is sent only for a confirming answer and is answered 201
   *       with the written identifier, so the preview carrying `resolvedCardNumberMasked` reaches this
   *       screen from `POST /api/v1/transactions/copy-last` -- which is the same turn
   *       `ui/src/screens/transactionAdd/transactionAddTurns.test.tsx` reads the resolved pair from, so
   *       this is the suite's established route to it rather than a second convention.
   */
  vi.mocked(copyLastTransaction).mockResolvedValue(previewOutcome(WIRE_NEGATIVE_AMOUNT));
  const { user } = await mountCaptureScreen();
  fillCapture({ ...A_COMPLETE_CAPTURE, accountId: '', cardNumber: CARD_NUMBER });

  await pressPfKey(user, 'PFK05');
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
  it('lays the field grid out responsively, rule included', laysTheFieldGridOutResponsively);
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
  it(
    'renders every named refusal with its accessibility wiring',
    rendersEveryNamedRefusalWithItsWiring,
  );
  it('surfaces a refusal it cannot attribute, moving no cursor', surfacesARefusalItCannotAttribute);
  it(
    'reports an unreadable answer without blaming the write',
    reportsAnUnreadableAnswerWithoutBlamingTheWrite,
  );
  it(
    'refuses an impossible calendar date locally, line 401',
    refusesAnImpossibleCalendarDateLocally,
  );
  it('dispatches every real calendar date, tolerance 2513', dispatchesEveryRealCalendarDate);
}

/**
 * Registers the case that keeps money in exact fixed point.
 * @returns {void} Nothing; the registration is the effect.
 */
function moneyCases(): void {
  it('keeps the amount an exact fixed-point string end to end', keepsMoneyInExactFixedPoint);
  it('accepts every keyed spelling of an amount, line 383', acceptsEveryKeyedAmountSpelling);
  it('refuses an amount that is not an amount, line 346', refusesAnAmountThatIsNotAnAmount);
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
  it('announces an outstanding capture', announcesAnOutstandingCapture);
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
  it('holds the confirmation surface to its contract', holdsTheConfirmationSurfaceToItsContract);
  it('brings the anchor into view before asking', bringsTheAnchorIntoViewBeforeAsking);
  it('declines from every route without any request', declinesFromEveryRouteWithoutARequest);
  it(
    'routes every submitting surface through the one gate',
    routesEverySubmitPathThroughTheOneGate,
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
